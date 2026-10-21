package com.minke.mallmanagement.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 把 {@link AuthTokenStore} 切到 Redis 后端（TD-18 方案 ⓑ，2026-10-20）。
 *
 * <p>开关是 {@code app.auth.token-store}：
 * <ul>
 *   <li>{@code redis} —— 默认 profile（{@code application.properties}）。票可由 Redis 撤销，
 *       登出 / 封禁 / 改密后旧票立即 401；</li>
 *   <li>{@code memory}（或缺省）—— dev / a3 / smoke 与单测。语义与 TD-18 之前一致，
 *       重启即丢、多实例不共享。⚠️ 这几个 profile 的 {@code app.auth.enabled=false}，
 *       切内存不会削弱任何生产面。</li>
 * </ul>
 *
 * <p>⚠️ 本类只**安装**后端，不在启动期连 Redis：建 bean 不会触发连接，
 * 首次铸票/验票才碰网络。这样 CI 等无 Redis 的环境仍能起上下文（用到票时才 503/401）。
 */
@Configuration
@ConditionalOnProperty(name = "app.auth.token-store", havingValue = "redis")
public class RedisAuthTokenStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthTokenStoreConfig.class);

    public RedisAuthTokenStoreConfig(StringRedisTemplate redis) {
        AuthTokenStore.installBackend(new RedisAuthTokenBackend(redis));
        // 启动期取证口：与 WebAuthConfig 的 "AUTH" 日志同一惯例。
        log.info("Bearer 票存储已切换（AUTH）：Redis 后端（可撤销 / 版本号）");
    }
}
