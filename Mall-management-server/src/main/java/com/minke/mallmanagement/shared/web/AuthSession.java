package com.minke.mallmanagement.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 身份读写收口（鉴权笔 **2** 建立会话侧；多账户隔离笔 **2026-10-06** 扩成「token + 会话」双凭据）
 * —— <b>全仓唯一一处直接读写 {@link AuthInterceptor#SESSION_USER_ID} /
 * {@link AuthInterceptor#SESSION_ROLE} 的地方</b>，也是唯一一处解析
 * {@link AuthTokenStore#HEADER} 的地方。
 *
 * <h2>【为什么要有这个类，而不是在 Controller 里直接 getSession()】</h2>
 * 属性名 {@code "userId"} 一旦在第二处被抄成字面量，两处不一致时<b>双方都不报错</b>
 * —— 闸把已登录用户判成未登录（或反之）。{@link AuthInterceptor} 的 javadoc 已把这条
 * 写成对写入方的要求（「应当引用本常量，而不是另抄一个 {@code "userId"} 字面量」），
 * 本类就是那条要求的落点：「读身份」这件事现在也只有一条路（见下两条口径）。
 *
 * <h2>【两条口径 —— 本类最重要的一处区分，别合并】</h2>
 * <ul>
 *   <li>{@link #authorize}（**授权**口径）：token 优先；无 token 时看 cookie 会话，但
 *       <b>{@code role=3}（管理员）的 cookie 一律不授权</b> —— 管理员身份只能由
 *       {@code Authorization} token 承载。闸（{@link AuthInterceptor}）与全部业务端点读的都是它
 *       ⇒ 拿不到身份 = 没资格做事。</li>
 *   <li>{@link #identify}（**识别**口径）：token 优先；无 token 时 cookie **一律认**
 *       （含 {@code role=3}）。只有 {@code /user/me} 用它 —— 那个端点的语义是「告诉我我是谁」，
 *       于是它有权知道「你是管理员」，并据此**降级作答**（只回 {@code role}、不回资料），
 *       让前端走「退回登录页」而不是静默当匿名。</li>
 * </ul>
 * ⚠️ 口径分叉的**唯一目的**是让「你是谁」与「你能做什么」分开：把两者合成一条，要么管理员
 * 的新标签页拿不到「你是管理」这个事实（需求 ③ 落空），要么角色 3 的 cookie 能直接干活
 * （隔离落空）。两条口径各自的判据都只有本类一处，别再往调用点搬。
 *
 * <h2>【token 不认识 ⇒ 401，**不回落 cookie**（有意）】</h2>
 * 请求带了 {@code Authorization} 但那张票不认识（被撤销 / 过期 / 伪造）时，{@link #identify}
 * 与 {@link #authorize} 都答 {@code null} —— 即便此时 cookie 会话是好的。
 * 理由：回落 = 隔离**静默降级**回「谁有 cookie 谁是身份」，而那正是本笔要治的病。
 *
 * <h2>【三个方法的 {@code getSession} 纪律 —— 这层差异是刻意的】</h2>
 * <ul>
 *   <li>{@link #login} 收的是**调用方给的** {@link HttpSession}，自己不创建。
 *       创建与否由调用点决定（{@code UserController.loginSelect} 只在**登录成功**时
 *       才 {@code request.getSession()}）—— 失败的登录请求因此**不会**被发一个会话。</li>
 *   <li>{@link #identify} / {@link #logout} 一律 {@code getSession(false)}：
 *       <b>不存在就不创建</b>。与 {@link AuthInterceptor#preHandle} 同一条纪律，
 *       理由见该处注释（否则「谁碰过 session」就成了登录态）。</li>
 * </ul>
 *
 * <h2>【会话里存的是什么】</h2>
 * <b>业务号</b>（{@code user.customerid} 或 {@code user.merchantid}），不是登录名、不是
 * {@code login.Id} 之外的东西。这个选择不是随手定的：{@code UserCacheService.publicInfo}
 * 的键就是它（{@code WHERE customerid = ? OR merchantid = ?}）⇒ 〔会话 → 用户信息〕
 * 只需一跳。⚠️ <b>不存密码</b>。
 *
 * <p>⚠️ <b>「不存角色」是**有意破**（多账户隔离笔，2026-10-06）—— 别当成误改删掉</b>。
 * 原文写的是「不存角色：本笔不做 RBAC，角色在请求期没有消费者」，那个判断在本笔之后
 * **不再成立**，消费者出现了两个：
 * <ol>
 *   <li>{@code POST /user/token} 要往 token 记录里写 {@code role}，而 {@code role} 的唯一来源是
 *       登录时 {@code login} 表的 {@code Role} 列 —— <b>会话是它此刻唯一的载体</b>；</li>
 *   <li>「cookie 回落对 {@code role=3} 不授权」这条判据（上面 {@link #authorize}）本身就要读角色。</li>
 * </ol>
 * ⇒ 会话新增 {@link AuthInterceptor#SESSION_ROLE}（与 {@code SESSION_USER_ID} 同处、同一写入方
 * {@link #login}）。⚠️ 仍然**不存密码**、不做细粒度授权（RBAC 归 C3 笔）。
 *
 * <h2>【会话存哪儿：纯内存（{@code HttpSession} 默认实现）—— 这是裁决，不是默认】</h2>
 * 不用 Redis 存会话，两条代价**已登记** {@code progress.md §5-Q}：① 多实例部署不共享会话；
 * ② 重启即丢（所有在线用户被登出）。不选 Redis 的**最硬**一条不是「多引入一个组件」，而是
 * 它与本仓既有的**降级契约**冲突：{@code application.properties} 明写 Redis 不可达是
 * **被容忍的降级态**（且 {@code management.health.redis.enabled=false} ⇒ 连健康检查都不报），
 * 而 Redis 存的会话在 Redis 挂掉时会**从「不确定」变成「已登出」** —— 那是一个新契约，
 * 且它的失败**不可观测**。
 * ⚠️ {@link AuthTokenStore}（token 表）是同一裁决的第二个落点，理由同源 —— 见该类注释。
 */
public final class AuthSession {

    /** 管理员角色号（{@code login.Role = 3}）。⚠️ 唯一出处：本常量 + {@link IdentityGuard} 的引用。 */
    public static final int ROLE_ADMIN = 3;

    private AuthSession() {
    }

    /** 一次请求解析出的身份。{@code viaToken} 是「这条身份从哪来」—— {@link #authorize} 的
     *  {@code role=3} 判据与 {@code /user/me} 的降级作答都要用它，故必须显式带出来。 */
    public record Identity(String userId, Integer role, boolean viaToken) {
    }

    /**
     * 登录成功：把业务号与角色写进会话。⚠️ 会话由调用方提供 —— 本方法**不创建**它。
     *
     * <p>是否该创建由调用点判断（只有认证成功才创建），见类注释。
     *
     * @param role 1 客户 / 2 商家（{@code loginSelect} 的返回值原样带入）；可为 {@code null}
     */
    public static void login(HttpSession session, String userId, Integer role) {
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, userId);
        session.setAttribute(AuthInterceptor.SESSION_ROLE, role);
    }

    /**
     * **识别**口径：这次请求「是谁」。token 优先，其次 cookie 会话；都没有 ⇒ {@code null}。
     *
     * <p>⚠️ 带了 token 但票不认识 ⇒ {@code null}（**不回落 cookie**），见类注释。
     * <p>⚠️ {@code getSession(false)}：本方法**不会**因为被调用而制造出一个会话。
     */
    public static Identity identify(HttpServletRequest request) {
        String token = currentToken(request);
        if (token != null) {
            AuthTokenStore.Grant grant = AuthTokenStore.resolve(token);
            return (grant == null) ? null : new Identity(grant.userId(), grant.role(), true);
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object userId = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        if (userId == null) {
            return null;
        }
        Object role = session.getAttribute(AuthInterceptor.SESSION_ROLE);
        return new Identity(userId.toString(), (role instanceof Integer) ? (Integer) role : null, false);
    }

    /**
     * **授权**口径：这次请求「有资格做事吗」。= {@link #identify}，但 cookie 来的
     * {@code role=3} 一律折成 {@code null}（管理员身份只能由 token 承载）。闸与业务端点用它。
     */
    public static Identity authorize(HttpServletRequest request) {
        Identity identity = identify(request);
        if (identity == null) {
            return null;
        }
        boolean adminFromCookie = !identity.viaToken()
                && identity.role() != null && identity.role() == ROLE_ADMIN;
        return adminFromCookie ? null : identity;
    }

    /**
     * 当前会话里登记的业务号（**授权**口径）。未登录（无凭据 / token 不认 / 管理员只带了 cookie）
     * 返回 {@code null}。
     *
     * <p>⚠️ 两种「未登录」在这里被折成同一个返回值，这是刻意的：对调用方（{@code /user/me} 之外
     * 的业务端点、{@link IdentityGuard}）而言两者都是「没有可用的身份」，区别只在闸那一层有意义
     * （它需要区分「有会话无属性」才能把判据钉死在属性上）。
     */
    public static String currentUserId(HttpServletRequest request) {
        Identity identity = authorize(request);
        return (identity == null) ? null : identity.userId();
    }

    /**
     * 当前身份的角色号（**授权**口径；1 客户 / 2 商家 / 3 管理员），无身份 ⇒ {@code null}。
     *
     * <p>本笔新增，两个消费者：{@code POST /user/token}（写进 token 记录）与
     * {@link IdentityGuard}（管理员豁免）。
     */
    public static Integer currentRole(HttpServletRequest request) {
        Identity identity = authorize(request);
        return (identity == null) ? null : identity.role();
    }

    /**
     * 本次请求携带的**原始 token**（没有则 {@code null}）—— 解析 {@code Authorization: Bearer <token>}。
     *
     * <p>⚠️ 只有两处需要「原始票」：{@link #logout}（销票）与测试。业务端点要身份请用
     * {@link #currentUserId} / {@link #currentRole}，别自己解析头。
     */
    public static String currentToken(HttpServletRequest request) {
        String header = request.getHeader(AuthTokenStore.HEADER);
        if (header == null || !header.startsWith(AuthTokenStore.BEARER)) {
            return null;
        }
        String token = header.substring(AuthTokenStore.BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 退出登录：销掉本请求带来的 token（若有）+ 会话**存在才** {@code invalidate()}，
     * 不存在则什么都不做 ⇒ <b>幂等</b>。
     *
     * <p>⚠️ 用 {@code invalidate()} 而不是 {@code removeAttribute(SESSION_USER_ID)}：
     * 后者只清掉身份，会话本身（连同容器为它分配的资源）仍在。本笔选前者，
     * 由 {@code AuthSessionSmokeTest} 的 {@code logout_reallyInvalidates} 钉住
     * （判据是 {@code MockHttpSession.isInvalid()}，不是「属性没了」）。
     *
     * <p>⚠️ 无会话时**不创建**会话再销毁 —— {@code getSession(false)} 返回 {@code null}
     * 就直接返回。「先 getSession() 再 invalidate()」会为每个登出请求 mint 一个空会话，
     * 是纯粹的资源浪费。
     *
     * <p>⚠️ <b>token 与 cookie 是两套凭据，登出必须**同时**销掉</b>（多账户隔离笔）：
     * 只销 cookie 会让那张票继续活到 TTL（最长 30 分钟），而「登出即失效」是本笔对
     * 不透明 token 的基本承诺。销票**只销请求里带来的那一张** —— 别的标签页手里的票
     * 不是本标签页的凭据，替它们销等于「一个标签页登出，全家掉线」（隔离的反面）。
     */
    public static void logout(HttpServletRequest request) {
        AuthTokenStore.revoke(currentToken(request));

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
