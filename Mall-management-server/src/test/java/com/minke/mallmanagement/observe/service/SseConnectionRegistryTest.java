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
 * <p>钉四件事：
 * <ol>
 *   <li><b>单用户 / 全局是两个独立上限</b>：先撞哪一个都由它拒；</li>
 *   <li><b>被拒不占名额</b>：拒完 total 不能涨（否则一次超限就把全局永久吃掉一格）；</li>
 *   <li><b>释放幂等</b>：{@code finally} 与看门狗可能各调一次，重复 release 不能把计数减负；</li>
 *   <li><b>分角色额度</b>：管理员 500/2000、普通用户（含匿名 IP）20/200，两档全局名额互相独立。</li>
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

    /** 一个带 cookie 会话的管理员（role=3）。 */
    private static HttpServletRequest admin(String userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(AuthInterceptor.SESSION_USER_ID)).thenReturn(userId);
        when(session.getAttribute(AuthInterceptor.SESSION_ROLE)).thenReturn(3);
        when(request.getSession(false)).thenReturn(session);
        when(request.getRemoteAddr()).thenReturn("10.0.0.7");
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

    @Test
    @DisplayName("分角色：管理员档 3/4、普通档 2/10 —— 各撞各的上限")
    void tiersUseTheirOwnPerUserAndTotalLimits() {
        SseConnectionRegistry registry = new SseConnectionRegistry(2, 10, 3, 4);

        HttpServletRequest admin = admin("A-1");
        assertThat(registry.tryAcquire(admin)).isNotNull();
        assertThat(registry.tryAcquire(admin)).isNotNull();
        assertThat(registry.tryAcquire(admin)).isNotNull();
        assertThat(registry.tryAcquire(admin)).as("管理员第 4 条撞单账号上限 3").isNull();

        HttpServletRequest user = anon("127.0.0.1");
        assertThat(registry.tryAcquire(user)).isNotNull();
        assertThat(registry.tryAcquire(user)).isNotNull();
        assertThat(registry.tryAcquire(user)).as("普通档第 3 条撞单账号上限 2").isNull();

        assertThat(registry.connections()).as("两档之和：管理员 3 + 普通 2").isEqualTo(5);
        assertThat(registry.limitsFor(SseConnectionRegistry.Tier.ADMIN).maxPerUser()).isEqualTo(3);
        assertThat(registry.limitsFor(SseConnectionRegistry.Tier.USER).maxPerUser()).isEqualTo(2);
    }

    @Test
    @DisplayName("分角色：普通档全局占满不会把管理员挤掉，反之亦然")
    void tiersDoNotStealEachOthersGlobalQuota() {
        SseConnectionRegistry registry = new SseConnectionRegistry(10, 2, 10, 2);

        assertThat(registry.tryAcquire(anon("10.0.0.1"))).isNotNull();
        assertThat(registry.tryAcquire(anon("10.0.0.2"))).isNotNull();
        assertThat(registry.tryAcquire(anon("10.0.0.3"))).as("普通档全局 2 已满").isNull();

        assertThat(registry.tryAcquire(admin("A-1"))).as("管理员档不受普通档全局限制")
                .isNotNull();
        assertThat(registry.tryAcquire(admin("A-2"))).isNotNull();
        assertThat(registry.tryAcquire(admin("A-3"))).as("管理员档全局 2 已满").isNull();

        assertThat(registry.tierOf(admin("A-1"))).isEqualTo(SseConnectionRegistry.Tier.ADMIN);
        assertThat(registry.tierOf(anon("10.0.0.4"))).as("未登录按 IP 一律普通档，绝不享管理员额度")
                .isEqualTo(SseConnectionRegistry.Tier.USER);
    }

    @Test
    @DisplayName("分角色默认值：管理员 500/2000，普通用户 20/200（与 application.properties 一致）")
    void defaultLimitsMatchTheRuling() {
        SseConnectionRegistry registry = new SseConnectionRegistry();

        assertThat(registry.limitsFor(SseConnectionRegistry.Tier.ADMIN))
                .isEqualTo(new SseConnectionRegistry.Limits(500, 2000, "管理员"));
        assertThat(registry.limitsFor(SseConnectionRegistry.Tier.USER))
                .isEqualTo(new SseConnectionRegistry.Limits(20, 200, "普通用户"));
    }
}
