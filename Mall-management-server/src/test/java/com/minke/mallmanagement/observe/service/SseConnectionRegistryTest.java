package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.shared.web.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE 名额表（{@link SseConnectionRegistry}）的边界实测（D3-6）。
 *
 * <p>钉三件事：
 * <ol>
 *   <li><b>单用户 / 全局是两个独立上限</b>：先撞哪一个都由它拒；</li>
 *   <li><b>被拒不占名额</b>：拒完 total 不能涨（否则一次超限就把全局永久吃掉一格）；</li>
 *   <li><b>释放幂等</b>：{@code finally} 与看门狗可能各调一次，重复 release 不能把计数减负。</li>
 * </ol>
 */
class SseConnectionRegistryTest {

    /** 一个「无 token、无会话」的请求：身份回落成客户端 IP。 */
    private static HttpServletRequest anon(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getSession(false)).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    /** 一个带 cookie 会话的业务用户。 */
    private static HttpServletRequest loggedIn(String userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(AuthInterceptor.SESSION_USER_ID)).thenReturn(userId);
        when(session.getAttribute(AuthInterceptor.SESSION_ROLE)).thenReturn(1);
        when(request.getSession(false)).thenReturn(session);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        return request;
    }

    @Test
    @DisplayName("单用户上限：第 N+1 条被拒，且被拒的请求不占全局名额")
    void perUserLimitRejectsTheNextOneWithoutConsumingGlobalSlot() {
        SseConnectionRegistry registry = new SseConnectionRegistry(2, 10);
        HttpServletRequest req = anon("127.0.0.1");

        assertThat(registry.tryAcquire(req)).isNotNull();
        assertThat(registry.tryAcquire(req)).isNotNull();
        assertThat(registry.tryAcquire(req)).as("第 3 条撞单用户上限").isNull();

        assertThat(registry.connections()).as("被拒不涨全局").isEqualTo(2);
        assertThat(registry.rejectedCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("全局上限：不同用户也占同一份总名额")
    void globalLimitAppliesAcrossIdentities() {
        SseConnectionRegistry registry = new SseConnectionRegistry(10, 2);

        assertThat(registry.tryAcquire(anon("10.0.0.1"))).isNotNull();
        assertThat(registry.tryAcquire(anon("10.0.0.2"))).isNotNull();
        assertThat(registry.tryAcquire(anon("10.0.0.3"))).as("全局满").isNull();

        assertThat(registry.connections()).isEqualTo(2);
        assertThat(registry.identities()).isEqualTo(2);
    }

    @Test
    @DisplayName("释放幂等：release 两次不会把计数减负；释放后名额可再用")
    void releaseIsIdempotentAndFreesTheSlot() {
        SseConnectionRegistry registry = new SseConnectionRegistry(1, 10);
        HttpServletRequest req = anon("127.0.0.1");

        SseConnectionRegistry.Permit first = registry.tryAcquire(req);
        assertThat(first).isNotNull();
        assertThat(registry.tryAcquire(req)).as("上限 1 已占满").isNull();

        first.release();
        first.release();     // 模拟 finally 与看门狗各调一次

        assertThat(registry.connections()).isZero();
        assertThat(registry.identities()).isZero();
        assertThat(registry.tryAcquire(req)).as("释放后名额回来了").isNotNull();
    }

    @Test
    @DisplayName("身份：登录用户按 userId 分组（与同 IP 的匿名连接互不相干）")
    void loggedInUserAndAnonymousIpAreDifferentBuckets() {
        SseConnectionRegistry registry = new SseConnectionRegistry(1, 10);

        assertThat(registry.tryAcquire(loggedIn("U-1"))).isNotNull();
        // 同 IP 的匿名请求：若把 loggedIn 也按 IP 分组，这一条会被拒
        assertThat(registry.tryAcquire(anon("10.0.0.9"))).isNotNull();
        assertThat(registry.identities()).isEqualTo(2);
    }
}
