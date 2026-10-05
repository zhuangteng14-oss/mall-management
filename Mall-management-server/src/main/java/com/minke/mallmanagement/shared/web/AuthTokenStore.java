package com.minke.mallmanagement.shared.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不透明 token 的**服务端内存表**（多账户隔离笔，2026-10-06）—— 全仓唯一签发/校验
 * {@code Authorization: Bearer} 的地方。
 *
 * <h2>【为什么不透明随机串，而不是 JWT / Redis】</h2>
 * <ul>
 *   <li><b>不是 JWT</b>：JWT 的价值是「无状态、跨服务自证」，本仓只有**一个**服务端进程、
 *       且已经有一张可随手查内存表 ⇒ 签名与自证这段复杂度<b>零收益</b>。反面是 JWT 的
 *       撤销问题：要「登出即失效」就得再引入一张黑名单表 —— 那就是本表，白绕一圈。</li>
 *   <li><b>不落 Redis</b>：与 {@link AuthSession} 拒绝 Redis 的理由**同源**（见该类注释）——
 *       本仓把「Redis 不可达」定义为**被容忍的降级态**（{@code application.properties} 明写、
 *       且 {@code management.health.redis.enabled=false}）。token 表若在 Redis，Redis 一挂
 *       就<b>从「不确定」变成「全部已登出」</b>，那是个新的、不可观测的契约。
 *       ⚠️ 代价与 {@code AuthSession} 完全一样，也已同样登记：**多实例不共享、重启即丢**。</li>
 * </ul>
 *
 * <h2>【token 绑 {@code userId}，不绑会话 —— 这是隔离的唯一诀窍】</h2>
 * 表里存的是 {@code {userId, role}}，**不是** {@code JSESSIONID}，也不引用会话。
 * 于是同一浏览器两个标签页各自登录 ⇒ 表里是**两条互不相干的记录**；标签 2 覆盖 cookie 会话，
 * 覆盖不了标签 1 手里那张票。⚠️ 反过来推：只要有人把本类改成「按会话发 token」或
 * 「同一会话回发同一张票」，两标签页就会共享同一个身份，**隔离当场失效** ——
 * 故 {@link #mint} <b>每次调用都新铸一条</b>，永不回发既有 token（有测试钉这一条）。
 *
 * <h2>【有效期：30 分钟**滑动**】</h2>
 * 每次 {@link #resolve} 命中都把 {@code lastAccessAt} 推到当下 ⇒ 持续使用的标签页不会中途掉线，
 * 停用 30 分钟后失效（下次 {@code resolve} 发现过期即删并答「不认识」）。
 * 过期项**不在后台线程里清**：清理只在 {@link #mint} 时顺带扫一遍（登录频率低，一次 O(n) 可接受），
 * 免得「谁都不再访问的 token」永久占表。
 *
 * <h2>【与 401/403 的关系】</h2>
 * 本类**只答「认不认识这张票」**，不判权限、不写响应 —— 判据全在
 * {@link AuthSession#authorize}（闸与业务端点共用）。token 未命中/过期 ⇒ 那里得到 {@code null}
 * ⇒ 闸答 401；⚠️ <b>刻意不回落 cookie</b>（带了 token 就是不认识，回落等于把隔离静默降级回
 * 「谁有 cookie 谁是身份」）。
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

    /** 活表。键 = token 本体，值 = 它代表谁（⚠️ 不含会话引用，见类注释）。 */
    private static final ConcurrentHashMap<String, Entry> LIVE = new ConcurrentHashMap<>();

    private AuthTokenStore() {
    }

    /** 一次成功解析的产物 —— 只有身份，**没有**「这张票属于哪个会话」这种字段（类注释那条诀窍）。 */
    public record Grant(String userId, Integer role) {
    }

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

    /**
     * 铸一张**新**票（⚠️ 每次都新铸，绝不回发既有 token —— 见类注释）。
     *
     * @param userId 业务号（{@code customerid} / {@code merchantid}）；调用方保证非空
     * @param role   1 客户 / 2 商家 / 3 管理员（来自登录时的会话，可为 {@code null}）
     */
    public static String mint(String userId, Integer role) {
        return mintAt(userId, role, System.currentTimeMillis());
    }

    /**
     * {@link #mint} 的**可注入时钟**版本（包级可见，仅供测试）—— 理由与 {@link #resolveAt} 同源：
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

    /** 解析（并**滑动续期**）。{@code null} = 不认识或已过期。 */
    public static Grant resolve(String token) {
        return resolveAt(token, System.currentTimeMillis());
    }

    /**
     * {@link #resolve} 的**可注入时钟**版本（包级可见，仅供测试 —— 30 分钟的过期路径
     * 不能靠 {@code Thread.sleep} 测）。
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
        if (token != null) {
            LIVE.remove(token);
        }
    }

    /** 表内当前条数（含尚未被访问而未被懒清除的过期项）。仅供测试与排查。 */
    public static int liveCount() {
        return LIVE.size();
    }

    /**
     * 清空全表。⚠️ <b>只给测试用</b>（用例之间必须互不残留：本表是 {@code static}，
     * 整条 JVM 生命周期共享）。生产代码**不得**调用 —— 那等于把所有人登出。
     */
    public static void clearForTest() {
        LIVE.clear();
    }

    /** 见类注释「过期项不在后台线程里清」。判据与 {@link #resolveAt} 的「{@code ≥ TTL} 即过期」逐字取齐。 */
    private static void purgeExpiredAt(long nowMillis) {
        LIVE.values().removeIf(entry -> nowMillis - entry.lastAccessAt >= TTL_MILLIS);
    }
}
