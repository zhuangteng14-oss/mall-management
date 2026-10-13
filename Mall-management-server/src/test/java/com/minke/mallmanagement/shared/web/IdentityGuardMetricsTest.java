package com.minke.mallmanagement.shared.web;

import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 块 7 · commit 2b —— 身份环的**真观测点**（{@code IdentityGuard} 的两条判据）。
 *
 * <p>本环与其余四环的口径差别在于**两路都记**：「拒绝率」要有分母才成立。故三个用例
 * 分别钉：放行记 {@code passed}、拒绝记 {@code rejected}、管理员豁免记 {@code passed}
 * （它<b>不是</b>拒绝 —— 记成拒绝会让拒绝率凭管理员动作升高）。
 *
 * <p>⚠️ 本类**不**给断言加装配前提：守卫是静态工具、走 {@code bind(...)} 静态口，
 * 未装配时逐字照旧（不记、也不抛）。最后一条用例就地钉这一点 —— 它同时是
 * 切片（{@code @WebMvcTest} 不扫 {@code ObserveMetricsConfig}）不会因本笔变红的原因。
 */
class IdentityGuardMetricsTest {

    /** ⚠️ 与 {@code IdentityMetrics.CHECKS} 同一个字面量（那边是包私有常量）。 */
    private static final String CHECKS = "mall.identity.checks";

    private SimpleMeterRegistry registry;
    private IdentityMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new IdentityMetrics(registry);
        IdentityGuard.bind(metrics);
    }

    /** 静态口是**全局**的 ⇒ 用例之间必须复原，否则别的测试类会往这个环里写。 */
    @AfterEach
    void tearDown() {
        IdentityGuard.bind(null);
    }

    /** 造一个带 cookie 会话的请求（{@link AuthSession#identify} 的 session 分支）。 */
    private static HttpServletRequest session(String userId, Integer role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, userId);
        session.setAttribute(AuthInterceptor.SESSION_ROLE, role);
        request.setSession(session);
        return request;
    }

    @Test
    @DisplayName("入参 == 自己 ⇒ 放行 + passed（所有角色）")
    void selfPasses() {
        assertThat(IdentityGuard.requireSelfOrAdmin(session("c-1", 1), "c-1")).isTrue();
        // 没传参也是放行（缺参由 Service 报，本类不替它做校验）
        assertThat(IdentityGuard.requireSelfOrAdmin(session("c-1", 1), null)).isTrue();

        assertThat(registry.counter(CHECKS, "result", "passed").count()).isEqualTo(2.0d);
        assertThat(metrics.recentEvents()).hasSize(2)
                .allSatisfy(e -> assertThat(e.reason()).isNull());
    }

    @Test
    @DisplayName("入参指向他人且非管理员 ⇒ 拒 + rejected（拒绝率的分母由此成立）")
    void strangerIsRejected() {
        assertThat(IdentityGuard.requireSelfOrAdmin(session("c-1", 1), "c-2")).isFalse();

        assertThat(registry.counter(CHECKS, "result", "rejected").count()).isEqualTo(1.0d);
        assertThat(metrics.recentEvents()).singleElement().satisfies(e -> {
            assertThat(e.result()).isEqualTo("rejected");
            assertThat(e.subject()).isEqualTo("c-2");
            assertThat(e.reason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("管理员改别人 ⇒ 放行 + passed（是职责，不是拒绝）")
    void adminPasses() {
        assertThat(IdentityGuard.requireSelfOrAdmin(session("a-1", AuthSession.ROLE_ADMIN), "c-2")).isTrue();

        assertThat(registry.counter(CHECKS, "result", "passed").count()).isEqualTo(1.0d);
        assertThat(registry.counter(CHECKS, "result", "rejected").count()).isZero();
    }

    @Test
    @DisplayName("未装配（切片 / 单测）⇒ 判据一字不变、也不记 —— 观测绝不影响放行")
    void unboundGuardKeepsItsVerdict() {
        IdentityGuard.bind(null);

        assertThat(IdentityGuard.requireSelfOrAdmin(session("c-1", 1), "c-2")).isFalse();
        assertThat(IdentityGuard.requireSelfOrAdmin(session("c-1", 1), "c-1")).isTrue();
        assertThat(metrics.recentEvents()).isEmpty();
    }
}
