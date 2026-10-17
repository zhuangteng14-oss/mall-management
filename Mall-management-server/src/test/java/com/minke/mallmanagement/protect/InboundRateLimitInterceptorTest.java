package com.minke.mallmanagement.protect;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入站限流闸（{@link InboundRateLimitInterceptor}）的实测 —— 裁定逐字要求的那几条：
 * <b>「健康检查 / 静态资源 / 探针 / error 页不要被误限」</b>、<b>「超限 → 429」</b>、
 * 以及「关掉这枚开关 ⇒ 没有闸」那一半。
 *
 * <p>不起 Spring 上下文：本类只吃三个构造参数（{@code RateLimiter} 由
 * {@code RateLimiter.of(…)} 现造，本仓 {@code ProtectConfig} 也是这么造的），
 * 判定逻辑与 web 容器无关。
 *
 * <p>⚠️ <b>刷新周期刻意取 60 秒而不是生产值的 1 秒</b>：本文件要验的是「令牌用光之后拦」，
 * 若周期是 1 秒，跑得慢一点（CI 上很常见）令牌就自己补回来了，用例会**偶发变绿**。
 * ⇒ 这里用一个大到不可能在用例时长内刷新的周期，把「用光」这个状态钉住。
 */
class InboundRateLimitInterceptorTest {

    private static final Duration NEVER_REFRESHES = Duration.ofSeconds(60);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProtectionMetrics metrics = new ProtectionMetrics(registry);
    private final ProtectionGate gate = new ProtectionGate();

    // ================= 白名单 =================

    @Test
    @DisplayName("六条白名单逐条豁免（裁定逐字：健康检查 / 静态资源 / 探针 / error 页）")
    void exemptPathsAreExactlyTheSixDeclaredOnes() {
        // 逐条写死：常量表改一条，这里就红在**那一条**上（而不是只红在 size 上）
        assertThat(InboundRateLimitInterceptor.exempt("/health")).as("运维探针本体").isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/health/liveness")).as("探针子路径").isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/actuator/health")).isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/actuator/metrics")).isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/error")).as("ERROR dispatch 落点").isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/img/goods/1.jpg")).as("静态资源").isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/favicon.ico")).isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/admin/observe/attack/stop"))
                .as("观测域整段 —— 少了它，「停止」按钮会和演练自己的流量抢令牌")
                .isTrue();
    }

    /**
     * ⚠️ 这条与上一条**同等重要**：白名单是「比上一次放宽了没有」最容易悄悄发生的改动 ——
     * 放宽不会让任何用例变红，只会让闸在某个业务面上**静默失效**。所以这里逐个钉住业务面**不豁免**。
     */
    @Test
    @DisplayName("业务面一条都不豁免 —— 放宽白名单不会让任何用例变红，故这里逐个钉住「不在表里」")
    void businessPathsAreNotExempt() {
        assertThat(InboundRateLimitInterceptor.exempt("/goods/goodsSelectHome")).isFalse();
        assertThat(InboundRateLimitInterceptor.exempt("/goods/goodsUp")).isFalse();
        assertThat(InboundRateLimitInterceptor.exempt("/order/orderReceive")).isFalse();
        assertThat(InboundRateLimitInterceptor.exempt("/admin/goods/list")).as("业务管理面也不豁免").isFalse();
        assertThat(InboundRateLimitInterceptor.exempt(null)).as("拿不到路径 ⇒ 不当作豁免（宁可拦）").isFalse();

        // ⚠️ 实测订正（2026-10-15）：`AntPathMatcher` 的 `/**` 匹配**零个或多个**路径段 ⇒
        //    `/admin/observe` 这个**不带尾斜杠的裸路径也豁免**（`/health` 同理）。
        //    本仓没有 `/admin/observe` 这个端点，故实际影响为零 —— 但这条写在这里，
        //    免得后来的人按「`/**` 至少要一段」去推白名单的边界（那会推出错的结论）。
        assertThat(InboundRateLimitInterceptor.exempt("/admin/observe")).isTrue();
        assertThat(InboundRateLimitInterceptor.exempt("/admin")).as("⚠️ 豁免的是 /admin/observe，不是整个 /admin").isFalse();
    }

    // ================= 429 =================

    @Test
    @DisplayName("超限 → 429：体是 {\"success\":false,\"msg\":…}（前端 http.js 的失败分支读的就是这两个键）")
    void overLimitAnswersWith429ShapeTheFrontendCanRead() throws Exception {
        InboundRateLimitInterceptor gate = newInterceptor(1);   // 一拍放 1 条
        MockHttpServletRequest request = get("/goods/goodsSelectHome");

        assertThat(gate.preHandle(request, new MockHttpServletResponse(), new Object()))
                .as("第一条第 1 个令牌 ⇒ 放行").isTrue();

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(gate.preHandle(request, response, new Object())).as("第二条没令牌 ⇒ 拦下").isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).as("中文必须带 charset，少了就是 GBK 乱码")
                .contains("application/json").contains("UTF-8");
        assertThat(response.getContentAsString())
                .as("形状与 Result.fail 同款 + 阈值从 limiter 现读（文案里的数字不许和生效值不同）")
                .contains("\"success\":false").contains("\"msg\"").contains("每 60 秒放行 1 条");
    }

    @Test
    @DisplayName("拦下时**记一笔**（key=rateLimit，scope=inbound，明细含方法与路径）—— 不记就等于闸隐形")
    void blockingIsRecordedIntoTheProtectionRing() throws Exception {
        InboundRateLimitInterceptor gate = newInterceptor(1);
        gate.preHandle(get("/goods/goodsUp"), new MockHttpServletResponse(), new Object());
        gate.preHandle(get("/goods/goodsUp"), new MockHttpServletResponse(), new Object());

        // 2026-10-17 起：**不再逐条写事件环**（800 QPS 时会刷屏），窗口结束时才发一条汇总。
        assertThat(metrics.recentEvents()).as("秒窗口未结束 ⇒ 事件环里还没有（请求已经被计数）").isEmpty();
        gate.flushAggregate();

        assertThat(metrics.recentEvents()).as("放行那条不记；被拦那条在窗口结束后汇总成一条").hasSize(1);
        ProtectionMetrics.ProtectionEvent event = metrics.recentEvents().get(0);
        assertThat(event.key()).isEqualTo("rateLimit");
        assertThat(event.scope()).isEqualTo("inbound");
        assertThat(event.result()).as("拦下 = blocked（不是 shed：闸按规则没放它进来）").isEqualTo("blocked");
        assertThat(event.detail())
                .as("汇总文案是用户点名的那句；示例里保留方法与路径，排查时仍能定位到面")
                .contains("入站限流触发：该秒内拦下 1 次请求")
                .contains("GET").contains("/goods/goodsUp");
    }

    @Test
    @DisplayName("按秒聚合：一秒内 N 次拦下 → 事件环只 1 条，Micrometer counter 仍记 N 次（指标口径不变）")
    void blockedRequestsAreAggregatedPerSecond() throws Exception {
        InboundRateLimitInterceptor gate = newInterceptor(1);   // 第 1 条放行，第 2~4 条被拦
        for (int i = 0; i < 4; i++) {
            gate.preHandle(get("/order/orderReceive"), new MockHttpServletResponse(), new Object());
        }
        assertThat(metrics.recentEvents()).as("窗口内一条事件都不发").isEmpty();
        gate.flushAggregate();

        // ⚠️ 指标名 / tag 与 ProtectionMetrics 同一口径（那里是包内常量，本类在 protect 包 ⇒ 用字面量）。
        assertThat(registry.get("mall.protection.events")
                .tag("protection", "rateLimit").tag("result", "blocked")
                .counter().count())
                .as("counter 仍是「被拦的请求数」，不是「发了几条事件」").isEqualTo(3.0);
        assertThat(metrics.recentEvents()).as("3 次拦下 ⇔ 1 条汇总事件").hasSize(1);
        assertThat(metrics.recentEvents().get(0).detail()).contains("该秒内拦下 3 次请求");

        // 第二个窗口：再来 2 次被拦 + 冲刷 ⇒ 再发 1 条（不是把上一个窗口重发）
        gate.preHandle(get("/order/orderReceive"), new MockHttpServletResponse(), new Object());
        gate.preHandle(get("/order/orderReceive"), new MockHttpServletResponse(), new Object());
        gate.flushAggregate();
        assertThat(metrics.recentEvents()).as("每个窗口一条 —— 两个窗口共 2 条").hasSize(2);
        assertThat(registry.get("mall.protection.events")
                .tag("protection", "rateLimit").tag("result", "blocked")
                .counter().count()).isEqualTo(5.0);
    }

    // ================= 「不接闸」的两条路 =================

    @Test
    @DisplayName("闸关掉（开关这枚关）⇒ 直行：令牌用光也不拦、不记 —— 这正是「关防护 ⇒ 吃满」那一半")
    void gateOffMeansNoThrottleAtAll() throws Exception {
        InboundRateLimitInterceptor interceptor = newInterceptor(1);
        gate.apply(List.of("circuitBreak"));   // 不勾 rateLimit ⇒ 这枚关

        assertThat(interceptor.preHandle(get("/goods/goodsUp"), new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(get("/goods/goodsUp"), new MockHttpServletResponse(), new Object()))
                .as("令牌明明用光了，但开关关着 ⇒ 一条都不拦").isTrue();
        assertThat(metrics.recentEvents()).as("也不记 —— 记了会让「关掉防护」在页面上看着像「拦了很多」").isEmpty();
    }

    @Test
    @DisplayName("不接闸（切片 / 显式关闭：limiter 为 null）⇒ 整体空转，且 armed() 如实报 false")
    void unarmedMeansEverythingPasses() throws Exception {
        InboundRateLimitInterceptor interceptor = new InboundRateLimitInterceptor(null, gate, metrics);

        assertThat(interceptor.armed()).as("启动日志与测试都读它").isFalse();
        for (int i = 0; i < 10; i++) {
            assertThat(interceptor.preHandle(get("/goods/goodsUp"), new MockHttpServletResponse(), new Object())).isTrue();
        }
        assertThat(metrics.recentEvents()).as("空转 ≠ 拦下，一条也不记").isEmpty();
    }

    @Test
    @DisplayName("白名单路径在**令牌用光之后**照样直行（顺序：先判豁免再看令牌）")
    void exemptPathPassesEvenWhenTokensAreGone() throws Exception {
        InboundRateLimitInterceptor interceptor = newInterceptor(1);
        interceptor.preHandle(get("/goods/goodsUp"), new MockHttpServletResponse(), new Object());   // 用掉唯一的令牌

        assertThat(interceptor.preHandle(get("/actuator/health"), new MockHttpServletResponse(), new Object()))
                .as("探针不能被自己的限流闸误伤 —— 那会让运维看不见这台机器还活着").isTrue();
    }

    // ================= 工具 =================

    /** 造一个真 limiter（形状与 {@code ProtectConfig} 一致：等待 0 ⇒ 不等、当场答）。 */
    private InboundRateLimitInterceptor newInterceptor(int limitForPeriod) {
        RateLimiter limiter = RateLimiter.of("inboundGate-test", RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(NEVER_REFRESHES)
                .timeoutDuration(Duration.ZERO)   // ⚠️ 「不排队」由这个 0 保证，不由方法名保证
                .build());
        return new InboundRateLimitInterceptor(limiter, gate, metrics);
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }
}
