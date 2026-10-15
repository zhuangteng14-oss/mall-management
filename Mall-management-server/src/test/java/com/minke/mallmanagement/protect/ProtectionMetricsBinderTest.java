package com.minke.mallmanagement.protect;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics.ProtectionEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断状态翻转 → 第 9 类事件环（{@link ProtectionMetricsBinder}）的实测 ——
 * 裁定逐字要求的那一条：<b>「半开恢复要有记录」</b>。
 *
 * <p>用**真的** {@code CircuitBreakerRegistry} + 真的 {@code CircuitBreaker}（不是替身）：
 * 这里要验的恰恰是「Resilience4j 的状态机真的会把事件发出来、我们真的接住了」——
 * 拿替身去顶替事件源，测出来的是「我调了我自己的回调」，等于没测。
 *
 * <p>⚠️ 本文件**不**验熔断的判定规则（错误率 50% / 30s 窗口 / 半开放 5 个）：
 * 那是 Resilience4j 自己的行为，本仓只负责「阈值配对了」与「翻转被记下来了」。
 * 前者读 {@code application.properties}（由 {@code ProtectConfigTest} 一类的配置断言管），
 * 后者就是本文件。
 */
class ProtectionMetricsBinderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProtectionMetrics metrics = new ProtectionMetrics(registry);

    // ================= scope：实例名 → 下游名 =================

    @Test
    @DisplayName("scopeOf：两个 mallMysql* 都归到 mysql —— 页面上的「哪条下游」按它分组")
    void mysqlInstancesShareOneDownstreamScope() {
        assertThat(ProtectionMetricsBinder.scopeOf("mallMysqlRead")).isEqualTo("mysql");
        assertThat(ProtectionMetricsBinder.scopeOf("mallMysqlWrite")).isEqualTo("mysql");
    }

    /**
     * ⚠️ 这条钉的是「**不猜**」：认不出的实例名**原样返回**，而不是归到某个默认下游上。
     * 猜错的代价不是少一个标签，而是把「MQ 挡下的」画成「MySQL 挡下的」—— 两者要查的方向完全不同。
     */
    @Test
    @DisplayName("scopeOf：认不出的**原样**返回（不猜、不归到默认下游），null ⇒ unknown")
    void unknownInstancesKeepTheirOwnName() {
        assertThat(ProtectionMetricsBinder.scopeOf("mallRedis")).isEqualTo("mallRedis");
        assertThat(ProtectionMetricsBinder.scopeOf("somethingElse")).isEqualTo("somethingElse");
        assertThat(ProtectionMetricsBinder.scopeOf(null)).isEqualTo("unknown");
        assertThat(ProtectionMetricsBinder.scopeOf("")).as("空串是「有名字但认不出」，不是「没有名字」").isEqualTo("");
    }

    // ================= 翻转被记下来 =================

    @Test
    @DisplayName("三档状态翻转各记一笔：OPENED / HALF_OPEN / CLOSED（半开恢复要有记录 = 这三条都在）")
    void stateTransitionsAreRecordedInAllThreeSteps() {
        CircuitBreaker breaker = breakerBoundTo("mallMysqlWrite");

        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        breaker.transitionToClosedState();

        List<ProtectionEvent> events = metrics.recentEvents();
        assertThat(events).extracting(ProtectionEvent::result).containsExactly("opened", "half_open", "closed");
        assertThat(events).extracting(ProtectionEvent::key).containsOnly("circuitBreak");
        assertThat(events).extracting(ProtectionEvent::scope).as("实例名 mallMysqlWrite → 下游 mysql").containsOnly("mysql");
        assertThat(events.get(1).detail()).as("明细里带 from → to：光看「半开过」推不出它从哪来")
                .contains("OPEN").contains("HALF_OPEN");
        assertThat(events.get(2).detail()).as("恢复关闭那一条 —— 裁定要求「半开恢复要有记录」").contains("HALF_OPEN → CLOSED");
    }

    @Test
    @DisplayName("开路后的调用被拒 ⇒ 记一笔 blocked（「熔断拦了多少」这个数只能来自 onCallNotPermitted）")
    void callsRejectedWhileOpenAreRecordedAsBlocked() {
        CircuitBreaker breaker = breakerBoundTo("mallMysqlRead");
        breaker.transitionToOpenState();   // ⚠️ recentEvents() 是只读快照、不清空 ⇒ 下面按 result 挑

        assertThatThrownBy(breaker::acquirePermission).isInstanceOf(Exception.class);

        ProtectionEvent blocked = metrics.recentEvents().stream()
                .filter(e -> "blocked".equals(e.result()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("开路后的调用被拒没有记下来 —— 页面只能看到「打开过」看不到「挡下了多少」"));
        assertThat(blocked.key()).isEqualTo("circuitBreak");
        assertThat(blocked.scope()).isEqualTo("mysql");
        assertThat(blocked.detail()).as("明细必须写明「本次调用未执行」—— 这是与「执行了但失败」的分界").contains("未执行");
    }

    @Test
    @DisplayName("本段不用的档（DISABLED）不记 —— 认不出就不记，不编造一个语义去记它")
    void statesThisSegmentDoesNotUseAreNotFabricated() {
        CircuitBreaker breaker = breakerBoundTo("mallMysqlWrite");

        breaker.transitionToDisabledState();

        assertThat(metrics.recentEvents()).as("DISABLED 不是本段三档之一 ⇒ 环里一条都没有").isEmpty();
    }

    /**
     * ⚠️ 这条才是**生产里真正会走的那条路**：{@code mallMysqlRead} / {@code mallMysqlWrite}
     * 两个名字**不在 {@code application.properties} 里**（只有 {@code configs.default.*}）⇒
     * 它们不是启动时批量建出来的，而是**第一次被 {@code @CircuitBreaker} 调用时**惰性建出来的 ——
     * 那一刻本类早就构造完了。⇒ 接得上的唯一路径是 {@code onEntryAdded}。
     *
     * <p>反过来说：构造器里那圈 {@code getAllCircuitBreakers()} 遍历，在本仓的配置下**大概率一个都
     * 遍历不到**。两条路都接，是为了「将来有人把实例写进 properties（那时就是启动时批量建）」时不用再改这里。
     */
    @Test
    @DisplayName("后建的熔断器照样接得上（onEntryAdded）—— 惰性创建是生产里真正走的那条路")
    void breakersCreatedAfterTheBinderIsStillBound() {
        CircuitBreakerRegistry breakerRegistry = CircuitBreakerRegistry.ofDefaults();
        new ProtectionMetricsBinder(breakerRegistry, metrics);   // 此刻 registry 是空的

        CircuitBreaker later = breakerRegistry.circuitBreaker("mallMysqlWrite");   // 后建
        later.transitionToOpenState();

        assertThat(metrics.recentEvents()).as("接不上的话，生产里两个实例的翻转一条都不会被记下来")
                .hasSize(1);
        assertThat(metrics.recentEvents().get(0).scope()).isEqualTo("mysql");
    }

    // ================= 工具 =================

    /** 造一个已登记（但还没被调用过）的熔断器，并接上本环。 */
    private CircuitBreaker breakerBoundTo(String name) {
        CircuitBreakerRegistry breakerRegistry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = breakerRegistry.circuitBreaker(name);
        new ProtectionMetricsBinder(breakerRegistry, metrics);
        return breaker;
    }
}
