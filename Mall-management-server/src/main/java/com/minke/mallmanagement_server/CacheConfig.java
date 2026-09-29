package com.minke.mallmanagement_server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

/**
 * 缓存装配 —— 显式覆盖 Spring Boot 的 CacheManager 自动装配。
 *
 * 【为什么必须显式覆盖】
 * pom 里同时有 Caffeine 与 spring-boot-starter-data-redis，但 Boot 只会装配**一个**
 * CacheManager：CacheType 枚举的顺序里 REDIS 排在 CAFFEINE 之前，RedisCacheManager 胜出。
 * 于是 §8 方案的「Caffeine(L1) + Redis(L2)」实际退化成**单级 Redis** ——
 * 每次读都跨网络，本地缓存形同不存在。本类把它改回两级。
 * （该风险原登记于 cache-layer.md §11「两级缓存尚未生效」，本类即其处置。）
 *
 * 【L2 的配置从哪来】
 * 不走 Boot 的自动装配，而是拿同一份 {@link CacheProperties}（即 spring.cache.* 属性）
 * 自己建 RedisCacheManager，并把 spring.cache.redis.* 逐项映射到 RedisCacheConfiguration。
 * 逐项显式映射而非依赖自动装配，是为了避免「属性写了但不生效」的静默失效 ——
 * 本项目已因 commons-pool2 缺失踩过一次同类坑（cache-layer.md §11）。
 *
 * ⚠️ 若将来新增 spring.cache.redis.* 属性，必须同时在 {@link #l2Configuration} 里映射，
 * 否则该属性会被静默忽略。
 *
 * 【两级 TTL 的关系】L1 TTL 应当 ≤ L2 TTL，且推荐明显更短：
 * L1 没有跨节点失效通道（见 TwoLevelCacheManager 类注释），短 TTL 是当前唯一的兜底。
 *
 * 【不在本类范围内的】L2 健康状态与探活通道见 {@link L2ProbeConfig}：本类只装配
 * CacheManager，健康判定由 {@link TwoLevelCache} 喂给 {@link L2HealthProbe}。
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * 两级 CacheManager。@Primary 是为了在任何按类型注入 CacheManager 的地方
     * （缓存切面、actuator）都取到这一份，而不是可能同时存在的 Boot 自动装配产物。
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                     CacheProperties cacheProperties,
                                     L2HealthProbe l2HealthProbe,
                                     MallCacheMetrics cacheMetrics,
                                     @Value("${app.cache.caffeine.max-size:1000}") long l1MaxSize,
                                     @Value("${app.cache.caffeine.time-to-live:60s}") Duration l1TimeToLive) {
        RedisCacheManager l2 = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(l2Configuration(cacheProperties))
                .build();
        warnIfL1OutlivesL2(l1TimeToLive, cacheProperties);
        // 把生效值打进启动日志：本类自己做了 spring.cache.redis.* 的映射，
        // 若属性名写错（例如 app.cache.caffeine.time-to-live 拼错），@Value 会静默
        // 退化成默认值 —— 而默认值恰好与 properties 同值，行为上看不出来。
        // 有这一行，「属性被读到」就是启动即可取证的事实，不靠「值相同所以无害」。
        log.info("两级缓存已装配：L1 Caffeine(maxSize={}, ttl={}) -> L2 Redis(ttl={})",
                l1MaxSize, l1TimeToLive, cacheProperties.getRedis().getTimeToLive());
        // l2HealthProbe 由 L2ProbeConfig 提供、全局一份：所有缓存名共用同一个健康状态
        // cacheMetrics 同理全局一份（Bean），只做记录、不参与装配决策
        return new TwoLevelCacheManager(l1TimeToLive, l1MaxSize, l2, l2HealthProbe, cacheMetrics);
    }

    /**
     * 不变量 L1 ≤ L2 的启动期校验。
     *
     * 为什么是「不变量」而不是「风格偏好」：L1 没有跨节点失效通道（见 TwoLevelCacheManager
     * 类注释），L2 的 TTL 是陈旧上界的真正来源。若 L1 比 L2 长，一个已被 L2 淘汰的旧值
     * 仍能在 L1 上继续被读到 —— 陈旧窗口从 L2 TTL 被放大到 L1 TTL，且没有任何机制兜底。
     *
     * 只 WARN 不阻断：这是配置取值问题，不是装配错误；且「L2 永不过期」（Duration.ZERO /
     * 负值）时该不变量天然成立，无需告警。
     */
    private static void warnIfL1OutlivesL2(Duration l1TimeToLive, CacheProperties cacheProperties) {
        Duration l2TimeToLive = cacheProperties.getRedis().getTimeToLive();
        if (l2TimeToLive == null || l2TimeToLive.isZero() || l2TimeToLive.isNegative()) {
            return;   // L2 未设 TTL（永不过期），不存在「L1 更长」的问题
        }
        if (l1TimeToLive.compareTo(l2TimeToLive) > 0) {
            log.warn("L1 TTL({}) 长于 L2 TTL({})：L1 无跨节点失效通道，陈旧窗口会被放大到 L1 TTL。"
                    + "建议 app.cache.caffeine.time-to-live ≤ spring.cache.redis.time-to-live。",
                    l1TimeToLive, l2TimeToLive);
        }
    }

    /**
     * spring.cache.redis.* → RedisCacheConfiguration，与 Boot 的映射逐项对齐。
     *
     * 键前缀沿用 Boot 默认（cacheName + "::"）：现网 Redis 里已有的
     * `goodstype::all` 等键因此仍然可用，改本类不需要 flush 缓存。
     */
    private static RedisCacheConfiguration l2Configuration(CacheProperties cacheProperties) {
        CacheProperties.Redis redis = cacheProperties.getRedis();
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig();

        // 不设 = Boot 默认 Duration.ZERO = 永不过期（TTL -1）= 内存无限增长 + 数据永久陈旧。
        // a3 profile 已显式设 5m（application-a3.properties）。
        if (redis.getTimeToLive() != null) {
            config = config.entryTtl(redis.getTimeToLive());
        }
        if (redis.getKeyPrefix() != null) {
            config = config.prefixCacheNameWith(redis.getKeyPrefix());
        }
        if (!redis.isCacheNullValues()) {
            config = config.disableCachingNullValues();
        }
        if (!redis.isUseKeyPrefix()) {
            config = config.disableKeyPrefix();
        }
        return config;
    }
}
