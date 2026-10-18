package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.SseConnectionRegistry;
import com.minke.mallmanagement.shared.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE 超限文案（{@link SseRejection}）的实测 —— 分角色说清楚**撞的是哪一档上限**。
 *
 * <p>判据只有两条：
 * <ol>
 *   <li>管理员看到 500 / 2000，普通用户（含匿名）看到 20 / 200；</li>
 *   <li>429 + {@code Retry-After} 与 JSON 形状不变（前端按 {@code msg} 直接展示）。</li>
 * </ol>
 * ⚠️ 不断言完整句子 —— 文案会改，数字与档位名才是契约。
 */
class SseRejectionTest {

    private static HttpServletRequest request(Integer role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        String ip = role == null ? "127.0.0.1" : "10.0.0.7";
        when(request.getRemoteAddr()).thenReturn(ip);
        if (role == null) {
            when(request.getSession(false)).thenReturn(null);
        } else {
            HttpSession session = mock(HttpSession.class);
            when(session.getAttribute(AuthInterceptor.SESSION_USER_ID)).thenReturn("U-1");
            when(session.getAttribute(AuthInterceptor.SESSION_ROLE)).thenReturn(role);
            when(request.getSession(false)).thenReturn(session);
        }
        return request;
    }

    private static String bodyOf(SseConnectionRegistry registry, HttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        StreamingResponseBody body = SseRejection.of(response, registry, request);
        body.writeTo(response.getOutputStream());

        assertThat(response.getStatus()).as("429 表示「你这个客户端开太多了」，不是 503")
                .isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentType()).startsWith("application/json");
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("管理员超限：文案说管理员档 500 / 2000")
    void adminMessageCarriesAdminLimits() throws Exception {
        SseConnectionRegistry registry = new SseConnectionRegistry(20, 200, 500, 2000);

        String body = bodyOf(registry, request(3));

        assertThat(body).contains("\"success\":false")
                .contains("管理员")
                .contains("500").contains("2000")
                .doesNotContain("单个账号 20");
    }

    @Test
    @DisplayName("普通用户超限：文案严格说 20 / 200（匿名 IP 同样走这一档）")
    void userMessageCarriesStrictLimits() throws Exception {
        SseConnectionRegistry registry = new SseConnectionRegistry(20, 200, 500, 2000);

        String body = bodyOf(registry, request(1));

        assertThat(body).contains("\"success\":false")
                .contains("普通用户")
                .contains("单个账号 20").contains("200")
                .doesNotContain("500");
    }

    @Test
    @DisplayName("匿名（dev/a3 鉴权关闭）也走普通档 —— 放宽的额度不送给脚本")
    void anonymousFallsIntoTheStrictTier() throws Exception {
        SseConnectionRegistry registry = new SseConnectionRegistry(20, 200, 500, 2000);

        String body = bodyOf(registry, request(null));

        assertThat(body).contains("普通用户").contains("单个账号 20");
        assertThat(registry.tierOf(request(null))).isEqualTo(SseConnectionRegistry.Tier.USER);
    }
}
