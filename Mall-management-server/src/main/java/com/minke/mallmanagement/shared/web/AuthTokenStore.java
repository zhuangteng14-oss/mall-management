package com.minke.mallmanagement.shared.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不透明 token 的**服务端存储**（多账户隔离笔，2026-10-06；TD-18 方案 ⓑ，2026-10-20）——
 * 全仓唯一签发/校验 {@code Authorization: Bearer} 的地方。
 *
 * <h2>【两种后端：默认 profile = Redis，测试 = 内存】</h2>
 * <ul>
 *   <li><b>Redis</b>（{@code app.auth.token-store=redis}，见 {@link RedisAuthTokenStoreConfig}）：
 *       键 {@code auth:token:<token>} 存 {@code userId / role / ver}，TTL = {@link #TTL_MILLIS}
 *       （命中即续期）；每个用户另有一个**版本号** {@code auth:user:<userId>:ver}。
 *       {@link #revokeAll(String)} 只递增版本号 ⇒ 该用户所有旧票在下一次解析时立刻失配、
 *       就地删除 —— 这就是「登出 / 封禁 / 改密后旧 token 立即 401」的落点，
 *       不需要遍历键空间，也不依赖 JWT 的 {@code exp}。</li>
 *   <li><b>内存表</b>（默认，{@code app.auth.token-store} 缺省或 {@code memory}）：
 *       单测与切片用。{@link #mintAt}/{@link #resolveAt} 是**只走内存**的可注入时钟钩子。
 *       生产不得依赖它：重启即丢、多实例不共享。</li>
 * </ul>
 *
 * <h2>【⚠️ Redis 不可达 = 全部 401（fail-closed）】</h2>
 * 这是 TD-18 第 3 条安全硬约束的直接代价：票表在 Redis，Redis 一挂就无法证明「这张票没被撤销」
 * ⇒ {@link RedisAuthTokenBackend#resolve} 记 ERROR 并答 {@code null}（闸回 401），
 * {@link RedisAuthTokenBackend#mint} 抛 {@link TokenStoreUnavailableException}（铸票端点回 503）。
 * 换句话说：**宁可全员重新登录，也不放行一张无法撤销的票**。
 *
 * <h2>【token 绑 {@code userId}，不绑会话 —— 这是隔离的唯一诀窍】</h2>
 * 表里存的是 {@code {userId, role}}，**不是** {@code JSESSIONID}，也不引用会话。
 * 于是同一浏览器两个标签页各自登录 ⇒ 两条互不相干的记录；标签 2 覆盖 cookie 会话，
 * 覆盖不了标签 1 手里那张票。⚠️ 故 {@link #mint} **每次调用都新铸一条**，永不回发既有 token
 * （{@code AuthTokenStoreTest.mintTwice_producesTwoDistinctLiveTokens} 钉这一条）。
 *
 * <h2>【有效期：30 分钟**滑动**】</h2>
 * 每次 {@link #resolve} 命中都把有效期推到当下 ⇒ 持续使用的标签页不会中途掉线，
 * 停用 30 分钟后失效。Redis 后端用 {@code EXPIRE} 实现；内存后端用 {@code lastAccessAt}。
 * 版本号键**不过期**：若它比票先过期，版本会回落到 0，一张被撤销后仍存活的旧票可能重新匹配 0
 * —— 那是一条静默的撤销失效漏洞。
 *
 * <h2>【与 401/403 的关系】</h2>
 * 本类**只答「认不认识这张票」**，不判权限、不写响应 —— 判据全在
 * {@link AuthSession#authorize}（闸与业务端点共用）。token 未命中/过期/版本失配 ⇒ 那里得到
 * {@code null} ⇒ 闸答 401；⚠️ <b>刻意不回落 cookie</b>（带了 Bearer 就是不认识，
 * 回落等于把隔离静默降级回「谁有 cookie 谁是身份」）。
 */
public final class AuthTokenStore {

    /** 承载 token 的请求头名。**唯一出处** —— 前端 {@code http.js} 按它拼头。 */
    public static final String HEADER = "Authorization";

    /** 头值的方案前缀（RFC 6750 的 Bearer 方案）。大小写敏感，前端逐字拼这个串。 */
    public static final String BEARER = "Bearer ";

    /** 滑动有效期（毫秒）。⚠️ 改这个数会同时改「登出后残留最长时间」与「XSS 窃得票的存活期」。 */
    public static final long TTL_MILLIS = 30 * 60 * 1000L;

    /** 32 字节 {@code SecureRandom} ⇒ Base64URL 无填充后 43 字符。 */
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * 活表（**仅内存后端**）。键 = token 本体，值 = 它代表谁（⚠️ 不含会话引用，见类注释）。
     */
    private static final ConcurrentHashMap<String, Entry> LIVE = new ConcurrentHashMap<>();

    /**
     * 当前后端；{@code null} = 内存表。由 {@link RedisAuthTokenStoreConfig} 在启动期安装，
     * 单测用 {@link #clearForTest()} 复位。{@code volatile}：安装发生在启动期、读取在每请求。
     */
    private static volatile Backend backend;

    private AuthTokenStore() {
    }

    /** 一次成功解析的产物 —— 只有身份，**没有**「这张票属于哪个会话」这种字段（类注释那条诀窍）。 */
    public record Grant(String userId, Integer role) {
    }

    /** 可替换的存储后端。⚠️ 只有两个实现：内存（本类静态方法）与 Redis（{@link RedisAuthTokenBackend}）。 */
    interface Backend {
        String mint(String userId, Integer role);

        Grant resolve(String token);

        void revoke(String token);

        /** 撤销某业务号的**全部**票（版本号递增）。 */
        void revokeAll(String userId);

        /** Redis 后端不提供 O(1) 计数 ⇒ 返回 -1；内存后端的计数由外层的 {@code LIVE} 承担。 */
        default int liveCount() {
            return -1;
        }
    }

    /** Redis 不可用（铸票侧）。⚠️ 解析侧不抛它 —— 那边按 401 处理（见类注释 fail-closed）。 */
    public static class TokenStoreUnavailableException extends RuntimeException {
        public TokenStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ==================== 安装 / 测试复位 ====================

    /** 安装后端（启动期一次）。包级可见：只允许 {@link RedisAuthTokenStoreConfig} 调。 */
    static void installBackend(Backend next) {
        backend = next;
    }

    // ==================== 对外 API ====================

    /**
     * 铸一张**新**票（⚠️ 每次都新铸，绝不回发既有 token —— 见类注释）。
     *
     * @param userId 业务号（{@code customerid} / {@code merchantid}）；调用方保证非空
     * @param role   1 客户 / 2 商家 / 3 管理员（来自登录时的会话，可为 {@code null}）
     */
    public static String mint(String userId, Integer role) {
        Backend b = backend;
        return (b == null) ? mintAt(userId, role, System.currentTimeMillis()) : b.mint(userId, role);
    }

    /**
     * {@link #mint} 的**可注入时钟**版本（包级可见，仅供测试）—— 它**只走内存表**，
     * 这样单测不依赖 Redis。理由与 {@link #resolveAt} 同源：
     * {@link #purgeExpiredAt} 那条清扫分支要求表里存在「已经过期」的项，而真实时钟下
     * 30 分钟造不出来（{@code mint} 自己取当下 ⇒ 刚铸的项永远不过期）。
     */
    static String mintAt(String userId, Integer role, long nowMillis) {
        purgeExpiredAt(nowMillis);

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = ENCODER.encodeToString(raw);
        LIVE.put(token, new Entry(userId, role, nowMillis));
        return token;
    }

    /** 解析（并**滑动续期**）。{@code null} = 不认识 / 已过期 / 已被撤销。 */
    public static Grant resolve(String token) {
        Backend b = backend;
        return (b == null) ? resolveAt(token, System.currentTimeMillis()) : b.resolve(token);
    }

    /**
     * {@link #resolve} 的**可注入时钟**版本（包级可见，仅供测试 —— 30 分钟的过期路径
     * 不能靠 {@code Thread.sleep} 测）。只走内存表。
     */
    static Grant resolveAt(String token, long nowMillis) {
        if (token == null) {
            return null;
        }
        Entry entry = LIVE.get(token);
        if (entry == null) {
            return null;
        }
        if (nowMillis - entry.lastAccessAt >= TTL_MILLIS) {
            LIVE.remove(token, entry);   // 懒清除：过期项在这次访问里就地删掉
            return null;
        }
        entry.lastAccessAt = nowMillis;  // 滑动续期
        return new Grant(entry.userId, entry.role);
    }

    /** 销一张票。**幂等**（不认识的 token 静默返回）—— 登出走它。 */
    public static void revoke(String token) {
        if (token == null) {
            return;
        }
        Backend b = backend;
        if (b == null) {
            LIVE.remove(token);
        } else {
            b.revoke(token);
        }
    }

    /**
     * 撤销某个业务号的**全部**票 —— 封禁 / 改密 / 强制下线走它。
     *
     * <p>Redis 后端 = 版本号 +1（O(1)、原子）；内存后端 = 逐条删。
     * ⚠️ 调用方传的必须是**业务号**（{@code customerid} / {@code merchantid}），不是登录名：
     * 票里存的就是业务号。
     */
    public static void revokeAll(String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        Backend b = backend;
        if (b == null) {
            LIVE.values().removeIf(entry -> userId.equals(entry.userId));
        } else {
            b.revokeAll(userId);
        }
    }

    /** 表内当前条数（仅内存后端；Redis 后端返回 -1）。仅供测试与排查。 */
    public static int liveCount() {
        Backend b = backend;
        return (b == null) ? LIVE.size() : b.liveCount();
    }

    /**
     * 清空全表并复位成内存后端。⚠️ <b>只给测试用</b>（用例之间必须互不残留：内存表是
     * {@code static}，整条 JVM 生命周期共享；而完整上下文测试可能安装过 Redis 后端）。
     * 生产代码**不得**调用 —— 那等于把所有人登出。
     */
    public static void clearForTest() {
        LIVE.clear();
        backend = null;
    }

    // ==================== 内存表实现 ====================

    /** 表项。{@code lastAccessAt} 可变（滑动续期），故不能用 record。 */
    private static final class Entry {
        private final String userId;
        private final Integer role;
        private volatile long lastAccessAt;

        private Entry(String userId, Integer role, long lastAccessAt) {
            this.userId = userId;
            this.role = role;
            this.lastAccessAt = lastAccessAt;
        }
    }

    /** 见类注释「过期项不在后台线程里清」。判据与 {@link #resolveAt} 的「{@code ≥ TTL} 即过期」逐字取齐。 */
    private static void purgeExpiredAt(long nowMillis) {
        LIVE.values().removeIf(entry -> nowMillis - entry.lastAccessAt >= TTL_MILLIS);
    }
}
