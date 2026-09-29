package com.minke.mallmanagement_server;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 两级缓存管理器（cache-layer.md §8 方案 B）：L1 Caffeine → L2 Redis。
 *
 * 【为什么需要它】Spring 的 @Cacheable 背后只能有一个 CacheManager，而一个 CacheManager
 * 就是一层缓存。Boot 即便 classpath 上同时有 Caffeine 与 Redis，也只会装配其中**一个**
 * （CacheType 枚举里 REDIS 排在 CAFFEINE 之前 → Redis 胜出），于是「两级」实际是单级 Redis。
 * 故这里显式覆盖：把 Caffeine 与 Redis 各做成一层，由本类组合。
 *
 * 【缓存按名懒创建】@Cacheable(cacheNames = "goodstype") 第一次被调用时，Spring 才向本类
 * 要 getCache("goodstype")；本类此刻才同时建 L1 与 L2。故新增缓存名**不需要改本类**。
 *
 * 【已知边界：L1 无跨节点失效】L2 失效（@CacheEvict）只清本进程的 L1 + Redis；多实例部署时
 * 其他实例的 L1 要等各自的 L1 TTL 到期。故 L1 TTL 应当**明显短于** L2 TTL（见 CacheConfig），
 * 用短 TTL 把跨节点陈旧窗口压到可接受范围。彻底解法是 Redis pub/sub 广播失效，属后续工作。
 */
public class TwoLevelCacheManager implements CacheManager {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCacheManager.class);

    private final Duration l1TimeToLive;
    private final long l1MaxSize;
    private final CacheManager l2;
    /**
     * L2 健康状态 —— **同一个实例发给所有** TwoLevelCache（不是一个缓存名一个）。
     * 「Redis 挂了」是全局事实，per-cache 的状态会让一次故障变成 N 条告警；
     * 详见 {@link L2HealthProbe} 类注释。
     */
    private final L2HealthProbe probe;
    /** 观测出口 —— 全应用唯一一份，转交给每个 TwoLevelCache（见 {@link MallCacheMetrics}）。 */
    private final MallCacheMetrics metrics;
    private final ConcurrentMap<String, TwoLevelCache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(Duration l1TimeToLive, long l1MaxSize, CacheManager l2,
                                L2HealthProbe probe, MallCacheMetrics metrics) {
        this.l1TimeToLive = l1TimeToLive;
        this.l1MaxSize = l1MaxSize;
        this.l2 = l2;
        this.probe = probe;
        this.metrics = metrics;
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, n -> {
            Cache l2Cache = l2.getCache(n);
            if (l2Cache == null) {
                // 例如关闭了动态缓存创建 —— 不假装有 L2，降级为仅 L1（TwoLevelCache 会跳过 L2 调用）
                log.warn("L2 不存在缓存 '{}' 且不可动态创建，该缓存将只有 L1（Caffeine）", n);
            }
            com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = Caffeine.newBuilder()
                    .maximumSize(l1MaxSize)
                    .expireAfterWrite(l1TimeToLive)
                    .recordStats()   // 便于观测命中率（TwoLevelCache.getNativeCache()）
                    .build();
            // L1 观测桩（size / evictions）在这里绑：每个缓存名的 L1 只在这里创建一次，
            // 且 evictions 单调（FunctionCounter 的前提）—— 换个地方绑就会破这两个前提
            metrics.bindL1(n, l1);
            return new TwoLevelCache(n, l1, l2Cache, probe, metrics);
        });
    }

    /**
     * L2 已登记的缓存名 ∪ 本进程已创建的缓存名。
     * L1 是按名懒建的，若只报 L2 会漏掉「尚未被 @Cacheable 触发、但已建立」的边界情形。
     */
    @Override
    public Collection<String> getCacheNames() {
        Set<String> names = new LinkedHashSet<>(l2.getCacheNames());
        names.addAll(caches.keySet());
        return names;
    }
}
