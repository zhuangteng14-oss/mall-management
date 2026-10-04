package com.minke.mallmanagement.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

/**
 * 登录闸（鉴权笔 **1**）—— <b>只判「放不放行」，不做认证本身</b>。
 *
 * <h2>【它判三件事，顺序固定】</h2>
 * <ol>
 *   <li><b>总开关</b>（{@code app.auth.enabled}）—— 关着就 {@code return true}，全部放行。
 *       默认**关**，故本笔对现有行为**零影响**。</li>
 *   <li><b>白名单</b>（{@link WebAuthConfig#AUTH_WHITELIST}，唯一出处）—— 命中即放行。
 *       判据是<b>路径</b>（{@code AntPathMatcher}），与 HTTP 方法无关。</li>
 *   <li><b>会话</b>—— 存在服务端会话**且**会话里带 {@link #SESSION_USER_ID} 才算已登录。</li>
 * </ol>
 * 三条都不通过 ⇒ 写 401 + {@code {"success":false,"msg":"未登录"}} 并 {@code return false}，
 * **请求不进 Controller**。
 *
 * <h2>【为什么「有会话」不等于「已登录」—— 这是本类最容易被放宽的一处】</h2>
 * 判据刻意是「会话**且**带 {@code userId} 属性」，不是「有会话即可」。会话在 Servlet 语义里只是
 * 一块服务端存储，任何一个 {@code getSession()} 调用都能创建它 —— 拿「存在会话」当登录态，
 * 等于把闸的判定权交给「谁碰过 session」。故逐条判属性，并把属性名固化成公开常量
 * {@link #SESSION_USER_ID}：<b>写入方（鉴权笔 2 的登录端点）引用本常量，不另抄字面量</b>
 * ——该要求已于 2026-10-04（笔 2）落地：写入方是 {@link AuthSession}，它只引用本常量。
 *
 * <h2>【为什么本类不是 {@code @Component}】</h2>
 * 由 {@link WebAuthConfig} 显式 {@code new} 出来（开关与白名单都是构造参数）。两个好处：
 * ① 白名单只有一处出处（配置类里的常量），不存在「注解里一份、代码里一份」的双写；
 * ② 本类**无需 Spring 上下文即可单测** —— 见 {@code AuthGateSmokeTest} 的白名单全表扫描用例
 * （{@code /health/db} 在 smoke profile 下无 handler，走 MockMvc 扫不到它，只能直调）。
 *
 * <h2>【为什么 401 响应体是字面量，而不是 {@code Result.fail("未登录")}】</h2>
 * {@link Result} 继承 {@code HashMap}，键序由 hash 桶决定 ⇒ 序列化出来可能是
 * {@code {"msg":…,"success":…}}。本闸的响应形状是对外承诺（{@code {"success":false,"msg":"未登录"}}），
 * 故写成字面量把字节钉死。⚠️ 同理它**刻意不抛异常**、也不走
 * {@link GlobalExceptionHandler} —— 后者的 {@code assignableTypes} 只覆盖
 * {@code MessageController} / {@code GoodsController} 两个类，拦截器拿不到「该走哪条 advice」
 * 这层信息，且让「未登录」借异常通道返回会把 401 与 200 两族失败混成一种。
 *
 * <h2>【本笔不做】</h2>
 * 不写会话（登录端点尚未改造 —— 那是鉴权笔 2）、不做 RBAC、不做密码 hash、不引
 * Spring Security / JWT / 新依赖。⚠️ 故<b>开关打开时全站非白名单接口都会被拒</b> ——
 * 这正是默认 {@code false} 的理由。
 *
 * <p>⚠️ <b>2026-10-04（笔 2）后本句的「不写会话」已过时</b>：写入方已落地
 * （{@link AuthSession}，由 {@code UserController.loginSelect} 在认证成功时调用）。
 * 其余三条（RBAC / 密码 hash / 不引新依赖）仍然成立；开关的默认值**仍是 {@code false}**
 * —— 笔 2 只把「认证侧」建起来，没有翻开关，故上面那两条 401 断言的适用面不变。
 */
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 会话里标记「已登录」的属性名。
     *
     * <p>⚠️ <b>本笔只读、不写</b>：写入方是鉴权笔 2 的登录端点，且它<b>应当引用本常量</b>
     * （{@code AuthInterceptor.SESSION_USER_ID}）而不是另抄一个 {@code "userId"} 字面量 ——
     * 两处字面量一旦不一致，闸会**静默**把已登录用户判成未登录（或反之），两边都不报错。
     */
    public static final String SESSION_USER_ID = "userId";

    /**
     * 401 响应体（逐字节固定）。键序 {@code success} → {@code msg}，与
     * {@link Result#fail(String)} 的写入序一致，但**不依赖** HashMap 的桶序来维持。
     *
     * <p>⚠️ <b>2026-10-04（笔 2）由 {@code private} 改为 {@code public}</b>：{@code /user/me}
     * 在「开关关着、但端点自身要求登录」时也要答同一个 401，而**不能**用
     * {@link Result#fail(String)} 拼 —— 实测（本机 JDK 17）：{@code new HashMap<>()} 里
     * {@code success} 落 6 号桶、{@code msg} 落 0 号桶 ⇒ 迭代序是 {@code msg} 在前，
     * 序列化得到 {@code {"msg":"未登录","success":false}}，与本字面量**不同字节**。
     * 故改为共享常量：两处 401 的字节相同**由构造保证**，不再靠「两个字面量恰好一样」。
     *
     * <p>⚠️ {@code AuthGateSmokeTest.BODY_NOT_LOGGED_IN} 是**另一份独立副本**（不是引用本常量）——
     * 那是刻意的：它是钉住本常量的**变更探测器**，把本常量改坏了它会红。
     */
    public static final String BODY_NOT_LOGGED_IN = "{\"success\":false,\"msg\":\"未登录\"}";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 总开关的取值（{@code app.auth.enabled}）。由 {@link WebAuthConfig} 在构造期读入。 */
    private final boolean enabled;

    /** 白名单（排除表）。副本，避免外部持有者事后改动而绕过闸。 */
    private final List<String> whitelist;

    public AuthInterceptor(boolean enabled, List<String> whitelist) {
        this.enabled = enabled;
        this.whitelist = List.copyOf(whitelist);
    }

    /** 总开关当前取值。仅供测试与启动期自检读取，不参与判定。 */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // ① 总开关。关着 ⇒ 不判白名单、不碰会话，直接放行。
        if (!enabled) {
            return true;
        }

        // ② 白名单。⚠️ 用 URI 减去 context-path，而不是 getServletPath() —— 后者在
        //    「按后缀/路径匹配」的配置下会被改写过，而排除表按完整业务路径书写。
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        if (isWhitelisted(uri)) {
            return true;
        }

        // ③ 会话。getSession(false)：**不存在就不创建** —— 否则本闸本身会给每个匿名请求
        //    发一个会话，「有会话」这条判据立刻失去意义（见类注释）。
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_USER_ID) != null) {
            return true;
        }

        writeNotLoggedIn(response);
        return false;
    }

    /**
     * 路径是否命中白名单。包级可见，供测试直接调用（{@code /health/db} 在 smoke profile 下
     * 无 handler，MockMvc 送不进去，见类注释）。
     */
    boolean isWhitelisted(String uri) {
        if (uri == null) {
            return false;
        }
        for (String pattern : whitelist) {
            if (MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写 401。⚠️ 显式设 charset：{@code msg} 含中文，不声明 charset 时容器可能按平台默认编码
     * 输出（本机为 GBK），前端按 UTF-8 解会得到乱码 —— 且只在某些机器上复现。
     */
    private void writeNotLoggedIn(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY_NOT_LOGGED_IN);
    }
}
