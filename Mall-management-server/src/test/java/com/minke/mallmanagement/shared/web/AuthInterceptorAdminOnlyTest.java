package com.minke.mallmanagement.shared.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TD-64（块 7 收尾）：破坏性端点 {@code /admin/observe/attack/cleanup} 的**角色闸**。
 *
 * <h2>【三档判据，缺一不可】</h2>
 * <ol>
 *   <li>未登录 ⇒ <b>401</b>（与既有 401 逐字节相同）；</li>
 *   <li>有效身份但非管理员（role 1/2）⇒ <b>403</b>；</li>
 *   <li>管理员（role 3 的**票**）⇒ 放行。</li>
 * </ol>
 * ⚠️ 只测 ① 时，把实现写成「非管理员一律 403 且不认管理员」也会全绿 —— 故 ②③ 是判据的一半。
 *
 * <h2>【为什么不走 HTTP 而直接调拦截器】</h2>
 * 默认 profile 下管理员**铸不出票**（{@code /user/token} 对 cookie 管理员答 401，是刻意的安全属性，
 * 见 {@code LoginPage.vue:326} 的注释与 slot-registry §十三 13-5）⇒ 真实链路上「管理员该放行」
 * 这一档今天没有 HTTP 入口可打。本类直接用 {@link AuthTokenStore#mint} 铸一张 role=3 的票，
 * 让**真实的** {@link AuthSession#authorize} + 拦截器判定跑一遍 —— 判据是真实代码，不是替身。
 */
class AuthInterceptorAdminOnlyTest {

    private static final String CLEANUP = "/admin/observe/attack/cleanup";
    private static final String ORDINARY = "/admin/observe/metrics/history";

    private final AuthInterceptor interceptor =
            new AuthInterceptor(true, List.of(), List.of(), null);

    private static MockHttpServletResponse call(AuthInterceptor it, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        it.preHandle(req, res, new Object());
        return res;
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        return req;
    }

    private static MockHttpServletRequest withSessionRole(String userId, int role) {
        MockHttpServletRequest req = request(CLEANUP);
        req.getSession(true).setAttribute(AuthInterceptor.SESSION_USER_ID, userId);
        req.getSession(false).setAttribute(AuthInterceptor.SESSION_ROLE, role);
        return req;
    }

    @Test
    @DisplayName("未登录打破坏性端点 ⇒ 401，body 与全站 401 逐字节相同")
    void anonymousGets401() throws Exception {
        MockHttpServletResponse res = call(interceptor, request(CLEANUP));

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).isEqualTo(AuthInterceptor.BODY_NOT_LOGGED_IN);
    }

    @Test
    @DisplayName("普通客户（role=1，有效会话）⇒ 403，且请求不进 Controller")
    void customerGets403() throws Exception {
        MockHttpServletRequest req = withSessionRole("C-1", 1);

        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()))
                .as("preHandle 返回 false = 请求被拦下").isFalse();
        MockHttpServletResponse res = call(interceptor, withSessionRole("C-1", 1));
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).isEqualTo("{\"success\":false,\"msg\":\"需要管理员权限\"}");
    }

    @Test
    @DisplayName("商家（role=2）⇒ 同样 403 —— 闸判的是「是不是管理员」，不是「有没有身份」")
    void merchantGets403() throws Exception {
        assertThat(call(interceptor, withSessionRole("M-1", 2)).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("管理员票（role=3，Bearer）⇒ 放行")
    void adminTokenPasses() throws Exception {
        MockHttpServletRequest req = request(CLEANUP);
        req.addHeader(AuthTokenStore.HEADER, AuthTokenStore.BEARER + AuthTokenStore.mint("admin-1", 3));

        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("管理员只带 cookie（无票）⇒ 401，不是 403 —— 授权口径把 cookie 管理员折成未登录")
    void adminCookieOnlyGets401() throws Exception {
        MockHttpServletResponse res = call(interceptor, withSessionRole("admin-1", 3));

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).isEqualTo(AuthInterceptor.BODY_NOT_LOGGED_IN);
    }

    @Test
    @DisplayName("对照：普通端点对 role=1 放行 —— 403 只该落在 ADMIN_ONLY_PATHS 上")
    void ordinaryAdminPathIsNotLockedByTheRoleGate() throws Exception {
        MockHttpServletRequest req = request(ORDINARY);
        req.getSession(true).setAttribute(AuthInterceptor.SESSION_USER_ID, "C-1");
        req.getSession(false).setAttribute(AuthInterceptor.SESSION_ROLE, 1);

        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
