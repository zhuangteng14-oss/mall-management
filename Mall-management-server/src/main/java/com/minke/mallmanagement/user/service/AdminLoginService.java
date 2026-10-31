package com.minke.mallmanagement.user.service;

import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.AuthTokenStore;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import com.minke.mallmanagement.user.dao.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 凭据驱动的**管理员铸票**（TD-18 方案 ⓑ，2026-10-20）—— 只接受用户名 + 密码，只给
 * {@code role=3} 铸票。
 *
 * <h2>【为什么另开端点，而不是让 {@code POST /user/token} 认管理员 cookie】</h2>
 * 后者会形成「管理员票无限续期」的口子：任何拿到一张管理员 cookie 的请求都能反复换新票。
 * 本服务要求**每次都用管理员口令重新证明身份**，而 {@code /user/token} 对 cookie 管理员的
 * 401 保持不变。
 *
 * <h2>【四条安全硬约束的落点】</h2>
 * <ol>
 *   <li><b>防爆破</b>：Redis 键 {@code auth:admin:fail:<username>} 计连续失败次数。
 *       第 1 次失败设 15 分钟 TTL，第 5 次失败重置 TTL 并拒绝；锁定期间不再校验口令
 *       （因此也不会把「锁定中」与「密码错」区分成可探测的信号）。成功登录删除计数。</li>
 *   <li><b>禁弱口令</b>：凭据校验通过且角色为管理员后，{@link AdminPasswordPolicy#isWeak}
 *       命中即拒绝 —— 拒绝发生在 {@link AuthTokenStore#mint} **之前**，不会签出任何票。</li>
 *   <li><b>可撤销</b>：票由 {@link AuthTokenStore} 签发；默认 profile 下是 Redis 后端，
 *       {@code revoke(token)} / {@code revokeAll(userId)} 立即生效。</li>
 *   <li><b>不产生双凭据歧义</b>：本端点**不写 cookie 会话**，只回 token；请求期「Bearer
 *       优先、无 Bearer 才看 cookie」的判定在 {@link AuthSession#identify} 一处。</li>
 * </ol>
 *
 * <h2>【失败计数的键为什么按登录名，而不是业务号】</h2>
 * 攻击者提交的是登录名；此时还没有通过认证，拿不到业务号。登录名先
 * {@code trim + toLowerCase(Locale.ROOT)} 再进键 —— MySQL 默认排序规则本身大小写不敏感，
 * 不归一就等于给「Admin / admin」两套计数。
 *
 * <h2>【Redis 不可用 ⇒ 503（fail-closed）】</h2>
 * 计数与票都在 Redis。证明不了「没被锁定 / 票可撤销」时不发凭据，由 Controller 答 503。
 */
@Service
public class AdminLoginService {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginService.class);

    private static final String KEY_PREFIX = "auth:admin:fail:";

    private final UserDao userDao;
    private final UserService userService;
    private final UserStateLookup userStateLookup;
    private final StringRedisTemplate redis;
    private final int failLimit;
    private final int lockMinutes;

    /**
     * 票存储后端（{@code redis} / {@code memory}），2026-10-31 起也决定**失败计数存哪**。
     *
     * <p>⚠️ 用字段注入（带初始值 {@code "redis"}）而不是构造器参数：本类的单测
     * {@code AdminLoginServiceTest} 直接 {@code new AdminLoginService(..., 5, 15)}，
     * 加构造器参数会让它编译不过；初始值保持「单测默认走 Redis」这条既有语义。
     *
     * <p>理由：dev / a3 / smoke 三个 profile 的 {@code app.auth.token-store=memory}，
     * 本地没有 Redis。原先失败计数硬走 Redis ⇒ {@code assertNotLocked} 直接抛
     * {@code RedisConnectionFailureException} ⇒ 管理员登录**恒 503**（fail-closed）。
     * 本地演示要的是「管理员演示账号 100% 能进」（口令见 `_work/admin-pass`，不进仓），故在 memory 后端下把计数
     * 同样放进程内；**默认 profile（redis）仍 fail-closed**，语义不变。
     */
    @Value("${app.auth.token-store:redis}")
    private String tokenStore = "redis";

    /** memory 后端下的失败计数（键同 Redis；条目带到期时刻）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, FailCounter> memoryFailures =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record FailCounter(long count, long expiresAt) {
    }

    public AdminLoginService(UserDao userDao,
                             UserService userService,
                             UserStateLookup userStateLookup,
                             StringRedisTemplate redis,
                             @Value("${app.auth.admin.fail-limit:5}") int failLimit,
                             @Value("${app.auth.admin.lock-minutes:15}") int lockMinutes) {
        this.userDao = userDao;
        this.userService = userService;
        this.userStateLookup = userStateLookup;
        this.redis = redis;
        this.failLimit = failLimit;
        this.lockMinutes = lockMinutes;
    }

    /** 拒绝原因。Controller 按它映射 HTTP 状态码。 */
    public enum Reason {
        BAD_CREDENTIALS,
        NOT_ADMIN,
        BANNED,
        WEAK_PASSWORD,
        LOCKED,
        UNAVAILABLE
    }

    /** 带原因与可选 {@code Retry-After} 的受检结果（用异常表达，Controller 一处映射）。 */
    public static class RejectedException extends RuntimeException {
        private final Reason reason;
        private final long retryAfterSeconds;

        RejectedException(Reason reason, String message, long retryAfterSeconds) {
            super(message);
            this.reason = reason;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public Reason reason() {
            return reason;
        }

        /** 仅 {@link Reason#LOCKED} 与 {@link Reason#UNAVAILABLE} 有值；其余为 0。 */
        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /**
     * 校验管理员凭据并铸一张新票。
     *
     * @return 43 字符的不透明 token
     * @throws RejectedException 原因见 {@link Reason}
     */
    public String login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new RejectedException(Reason.BAD_CREDENTIALS, "账号或密码不正确", 0);
        }

        String key = KEY_PREFIX + username.trim().toLowerCase(Locale.ROOT);
        assertNotLocked(key);

        Integer role;
        try {
            role = userDao.selectRoleByCredentials(username, password);
        } catch (IncorrectResultSizeDataAccessException e) {
            // 零行（凭据不匹配）与多行（数据异常）都按「凭据没通过」处理。
            // ⚠️ 不把底层异常文本回给客户端，避免泄露表结构 / 库状态。
            onBadCredentials(key);
            return null;
        } catch (DataAccessException e) {
            // 库不可达 ≠ 密码错误：记 503，**不计数** —— 否则一次库抖动会把管理员锁 15 分钟。
            log.error("管理员登录校验时数据库不可用", e);
            throw unavailable(e);
        }

        if (role == null) {
            onBadCredentials(key);
            return null;
        }
        if (role != AuthSession.ROLE_ADMIN) {
            // 凭据有效但不是管理员：不计数（这不是猜密码），也不发票。
            throw new RejectedException(Reason.NOT_ADMIN, "该账号不是管理员账号", 0);
        }
        if (userStateLookup != null && userStateLookup.isBannedByUsername(username)) {
            throw new RejectedException(Reason.BANNED, "该管理员账号已被禁用，请联系平台处理", 0);
        }
        if (AdminPasswordPolicy.isWeak(password)) {
            throw new RejectedException(Reason.WEAK_PASSWORD,
                    "检测到默认弱口令，已拒绝登录；请先更换为强口令", 0);
        }

        String userId = userService.businessId(username, AuthSession.ROLE_ADMIN);
        if (userId == null) {
            // 凭据有效但 user 行缺业务号：属于数据不完整，不铸一张绑不上人的票。
            throw new RejectedException(Reason.BAD_CREDENTIALS, "账号数据不完整，请联系平台处理", 0);
        }

        String token;
        try {
            token = AuthTokenStore.mint(userId, AuthSession.ROLE_ADMIN);
        } catch (AuthTokenStore.TokenStoreUnavailableException e) {
            log.error("管理员铸票失败：Redis 不可用", e);
            throw new RejectedException(Reason.UNAVAILABLE, "认证服务暂不可用，请稍后重试",
                    lockMinutes * 60L);
        }

        clearFailures(key);
        return token;
    }

    // ==================== 失败计数 ====================

    private boolean memoryMode() {
        return "memory".equalsIgnoreCase(tokenStore);
    }

    private void assertNotLocked(String key) {
        if (memoryMode()) {
            FailCounter counter = memoryFailures.get(key);
            long now = System.currentTimeMillis();
            if (counter != null && counter.expiresAt() >= now && counter.count() >= failLimit) {
                throw locked(Math.max(1, (counter.expiresAt() - now + 999) / 1000));
            }
            return;
        }
        try {
            String raw = redis.opsForValue().get(key);
            long count = parseCount(raw);
            if (count >= failLimit) {
                Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
                long seconds = (ttl == null || ttl <= 0) ? lockMinutes * 60L : ttl;
                throw locked(seconds);
            }
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void onBadCredentials(String key) {
        if (memoryMode()) {
            long now = System.currentTimeMillis();
            long lockMs = lockMinutes * 60_000L;
            FailCounter counter = memoryFailures.compute(key, (k, old) -> {
                long count = (old == null || old.expiresAt() < now) ? 1 : old.count() + 1;
                long expiresAt = (count == 1 || count >= failLimit)
                        ? now + lockMs
                        : old.expiresAt();
                return new FailCounter(count, expiresAt);
            });
            if (counter.count() >= failLimit) {
                throw locked(lockMinutes * 60L);
            }
            throw new RejectedException(Reason.BAD_CREDENTIALS,
                    "账号或密码不正确，还可尝试 " + (failLimit - counter.count()) + " 次", 0);
        }
        try {
            Long incremented = redis.opsForValue().increment(key);
            long count = incremented == null ? 1L : incremented;
            // 第一次失败起计时；第 5 次失败把锁定期重新钉满 15 分钟（从最后一次失败算）。
            if (count == 1L || count >= failLimit) {
                redis.expire(key, Duration.ofMinutes(lockMinutes));
            }
            if (count >= failLimit) {
                throw locked(lockMinutes * 60L);
            }
            long remaining = failLimit - count;
            throw new RejectedException(Reason.BAD_CREDENTIALS,
                    "账号或密码不正确，还可尝试 " + remaining + " 次", 0);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void clearFailures(String key) {
        if (memoryMode()) {
            memoryFailures.remove(key);
            return;
        }
        try {
            redis.delete(key);
        } catch (DataAccessException e) {
            // 已经拿到了有效凭据；计数清不掉不应把一次成功登录变成 503。
            // 代价：下一次失败会从旧计数继续累加，方向是「更早锁定」，不是放行。
            log.warn("管理员登录成功，但失败计数未能清除（key={}）", key, e);
        }
    }

    private RejectedException locked(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return new RejectedException(Reason.LOCKED,
                "登录失败次数过多，账号已锁定，请 " + minutes + " 分钟后再试", seconds);
    }

    private RejectedException unavailable(DataAccessException e) {
        log.error("管理员登录依赖的存储不可用（Redis / 数据库）", e);
        return new RejectedException(Reason.UNAVAILABLE, "认证服务暂不可用，请稍后重试",
                lockMinutes * 60L);
    }

    private static long parseCount(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
