package com.minke.mallmanagement.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 会话读写收口（鉴权笔 **2**）—— <b>全仓唯一一处直接读写
 * {@link AuthInterceptor#SESSION_USER_ID} 的地方</b>。
 *
 * <h2>【为什么要有这个类，而不是在 Controller 里直接 getSession()】</h2>
 * 属性名 {@code "userId"} 一旦在第二处被抄成字面量，两处不一致时<b>双方都不报错</b>
 * —— 闸把已登录用户判成未登录（或反之）。{@link AuthInterceptor} 的 javadoc 已把这条
 * 写成对写入方的要求（「应当引用本常量，而不是另抄一个 {@code "userId"} 字面量」），
 * 本类就是那条要求的落点：把它收进一个只有 3 个方法的类，比散在 3 个端点里更难抄错。
 *
 * <h2>【三个方法各自的 {@code getSession} 纪律 —— 这层差异是刻意的】</h2>
 * <ul>
 *   <li>{@link #login} 收的是**调用方给的** {@link HttpSession}，自己不创建。
 *       创建与否由调用点决定（{@code UserController.loginSelect} 只在**登录成功**时
 *       才 {@code request.getSession()}）—— 失败的登录请求因此**不会**被发一个会话。</li>
 *   <li>{@link #currentUserId} / {@link #logout} 一律 {@code getSession(false)}：
 *       <b>不存在就不创建</b>。与 {@link AuthInterceptor#preHandle} 同一条纪律，
 *       理由见该处注释（否则「谁碰过 session」就成了登录态）。</li>
 * </ul>
 *
 * <h2>【会话里存的是什么】</h2>
 * <b>业务号</b>（{@code user.customerid} 或 {@code user.merchantid}），不是登录名、不是
 * {@code login.Id} 之外的东西。这个选择不是随手定的：{@code UserCacheService.publicInfo}
 * 的键就是它（{@code WHERE customerid = ? OR merchantid = ?}）⇒ 〔会话 → 用户信息〕
 * 只需一跳。⚠️ <b>不存密码、不存角色</b> —— 本笔不做 RBAC，角色在请求期没有消费者。
 *
 * <h2>【会话存哪儿：纯内存（{@code HttpSession} 默认实现）—— 这是裁决，不是默认】</h2>
 * 不用 Redis 存会话，两条代价**已登记** {@code progress.md §5-Q}：① 多实例部署不共享会话；
 * ② 重启即丢（所有在线用户被登出）。不选 Redis 的**最硬**一条不是「多引入一个组件」，而是
 * 它与本仓既有的**降级契约**冲突：{@code application.properties} 明写 Redis 不可达是
 * **被容忍的降级态**（且 {@code management.health.redis.enabled=false} ⇒ 连健康检查都不报），
 * 而 Redis 存的会话在 Redis 挂掉时会**从「不确定」变成「已登出」** —— 那是一个新契约，
 * 且它的失败**不可观测**。
 */
public final class AuthSession {

    private AuthSession() {
    }

    /**
     * 登录成功：把业务号写进会话。⚠️ 会话由调用方提供 —— 本方法**不创建**它。
     *
     * <p>是否该创建由调用点判断（只有认证成功才创建），见类注释。
     */
    public static void login(HttpSession session, String userId) {
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, userId);
    }

    /**
     * 当前会话里登记的业务号。未登录（**无会话**、或会话里**没有该属性**）返回 {@code null}。
     *
     * <p>⚠️ 两种「未登录」在这里被折成同一个返回值，这是刻意的：对调用方（{@code /user/me}）
     * 而言两者都是「没有可用的身份」，区别只在闸那一层有意义（它需要区分「有会话无属性」
     * 才能把判据钉死在属性上）。
     *
     * <p>⚠️ {@code getSession(false)}：本方法**不会**因为被调用而制造出一个会话。
     */
    public static String currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object userId = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        return (userId == null) ? null : userId.toString();
    }

    /**
     * 退出登录：会话**存在才** {@code invalidate()}，不存在则什么都不做 ⇒ <b>幂等</b>。
     *
     * <p>⚠️ 用 {@code invalidate()} 而不是 {@code removeAttribute(SESSION_USER_ID)}：
     * 后者只清掉身份，会话本身（连同容器为它分配的资源）仍在。本笔选前者，
     * 由 {@code AuthSessionSmokeTest} 的 {@code logout_reallyInvalidates} 钉住
     * （判据是 {@code MockHttpSession.isInvalid()}，不是「属性没了」）。
     *
     * <p>⚠️ 无会话时**不创建**会话再销毁 —— {@code getSession(false)} 返回 {@code null}
     * 就直接返回。「先 getSession() 再 invalidate()」会为每个登出请求 mint 一个空会话，
     * 是纯粹的资源浪费。
     */
    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
