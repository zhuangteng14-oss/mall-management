package com.minke.mallmanagement.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link AuthTokenStore} 的 **Redis 后端**（TD-18 方案 ⓑ，2026-10-20）。
 *
 * <h2>【键设计】</h2>
 * <ul>
 *   <li>{@code auth:token:<token>} —— hash：{@code userId / role / ver}，TTL = 30 分钟滑动；</li>
 *   <li>{@code auth:user:<userId>:ver} —— 该业务号的**票版本号**，{@code revokeAll} 对它
 *       {@code INCR}。⚠️ **不设 TTL**：版本号若比票先过期，会回落到 0，被撤销的旧票可能重新匹配
 *       —— 那是撤销失效。版本号键很小（每用户一个），随用户数增长，可接受。</li>
 * </ul>
 *
 * <h2>【撤销的原子性】</h2>
 * {@code resolve} 先读票记录的 {@code ver}，再读当前版本号；不相等就删票答「不认识」。
 * {@code mint} 先读版本号再写票记录 —— 若两步之间发生 {@code revokeAll}，新票会带着旧版本号，
 * 下一次解析立刻失配 ⇒ **失败方向是「多登出一次」，不是「撤销漏掉一张票」**。
 *
 * <h2>【Redis 挂了的两种姿态】</h2>
 * <ul>
 *   <li>{@link #resolve}：记 ERROR、答 {@code null}（上层闸回 401）—— fail-closed；</li>
 *   <li>{@link #mint} / {@link #revoke} / {@link #revokeAll}：抛
 *       {@link AuthTokenStore.TokenStoreUnavailableException}，由端点答 503 / 500。
 *       ⚠️ **不吞**：铸票失败必须让调用方知道，撤销失败更不能假装成功。</li>
 * </ul>
 */
final class RedisAuthTokenBackend implements AuthTokenStore.Backend {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthTokenBackend.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final String TOKEN_PREFIX = "auth:token:";
    private static final String VERSION_PREFIX = "auth:user:";
    private static final String VERSION_SUFFIX = ":ver";

    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_VERSION = "ver";

    private static final Duration TTL = Duration.ofMillis(AuthTokenStore.TTL_MILLIS);

    private final StringRedisTemplate redis;

    RedisAuthTokenBackend(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String mint(String userId, Integer role) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = ENCODER.encodeToString(raw);

        String key = TOKEN_PREFIX + token;
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put(FIELD_USER_ID, userId);
            fields.put(FIELD_ROLE, role == null ? "" : role.toString());
            fields.put(FIELD_VERSION, String.valueOf(currentVersion(userId)));

            redis.opsForHash().putAll(key, fields);
            redis.expire(key, TTL);
        } catch (DataAccessException e) {
            throw new AuthTokenStore.TokenStoreUnavailableException("Redis 不可用，无法铸票", e);
        }
        return token;
    }

    @Override
    public AuthTokenStore.Grant resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String key = TOKEN_PREFIX + token;
        try {
            Map<Object, Object> fields = redis.opsForHash().entries(key);
            if (fields == null || fields.isEmpty()) {
                return null;
            }

            String userId = stringOf(fields.get(FIELD_USER_ID));
            if (userId == null || userId.isEmpty()) {
                redis.delete(key);   // 残缺记录：宁可多登出一次
                return null;
            }

            long storedVersion = parseVersion(stringOf(fields.get(FIELD_VERSION)));
            if (storedVersion != currentVersion(userId)) {
                redis.delete(key);   // 版本失配 = 已被 revokeAll 撤销
                return null;
            }

            Integer role = parseRole(stringOf(fields.get(FIELD_ROLE)));
            redis.expire(key, TTL);   // 滑动续期
            return new AuthTokenStore.Grant(userId, role);
        } catch (DataAccessException e) {
            // fail-closed：证明不了「没被撤销」就不认这张票。上层闸据此回 401。
            log.error("Redis 不可用，Bearer 票无法校验，按未登录处理（fail-closed）", e);
            return null;
        }
    }

    @Override
    public void revoke(String token) {
        if (token == null) {
            return;
        }
        try {
            redis.delete(TOKEN_PREFIX + token);
        } catch (DataAccessException e) {
            throw new AuthTokenStore.TokenStoreUnavailableException("Redis 不可用，无法销票", e);
        }
    }

    @Override
    public void revokeAll(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        try {
            redis.opsForValue().increment(VERSION_PREFIX + userId + VERSION_SUFFIX);
        } catch (DataAccessException e) {
            throw new AuthTokenStore.TokenStoreUnavailableException("Redis 不可用，无法撤销该用户的全部票", e);
        }
    }

    /** 当前版本号；键不存在 ⇒ 0。读取失败向上抛（由调用方按各自姿态处理）。 */
    private long currentVersion(String userId) {
        String raw = redis.opsForValue().get(VERSION_PREFIX + userId + VERSION_SUFFIX);
        return parseVersion(raw);
    }

    private static long parseVersion(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Integer parseRole(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }
}
