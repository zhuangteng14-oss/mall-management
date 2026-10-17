package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics.ProtectionEvent;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics.Result;
import com.minke.mallmanagement.shared.web.TraceIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 9 类事件环（{@link ProtectionMetrics}）的语义实测 —— 七档 result 各自记什么、
 * 两条通道（计数 + 事件）是不是真的在同一调用点一起发、基数铁律（谁进 tag 谁不进）。
 *
 * <p>不起 Spring、不起 MySQL、不起任何闸（同 {@code StockMetricsTest} 的姿势）。
 *
 * <p>⚠️ 本文件验的是**本环这个出口**。「真的有闸在调它吗」由三处写入方各自的测试负责：
 * {@code InboundRateLimitInterceptorTest}（429）、{@code ProtectionMetricsBinderTest}（熔断翻转）、
 * {@code ObservePoolConfigTest}（池挤出）—— 本文件不重复验那一层。
 */
class ProtectionMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProtectionMetrics metrics = new ProtectionMetrics(registry);

    // ================= 两条通道同点发 =================

    @Test
    @DisplayName("七档 result 各自计数、各自入环（计数与事件在同一调用点一起发）")
    void everyResultIsCountedAndRecorded() {
        metrics.blocked("rateLimit", "inbound", "path=/goods/goodsSelectHome", 1_500L,
                "入站限流：未取得令牌（limitForPeriod=200）");
        metrics.state(Result.OPENED, "circuitBreak", "mysql", "circuitBreaker=mallMysqlWrite CLOSED → OPEN",
                "熔断打开：失败率 50%，慢调用率 20%");
        metrics.state(Result.HALF_OPEN, "circuitBreak", "mysql", "OPEN → HALF_OPEN",
                "熔断半开：等待期结束，放行试探请求");
        metrics.state(Result.CLOSED, "circuitBreak", "mysql", "HALF_OPEN → CLOSED", null);
        metrics.shed("poolIsolation", "pool", "pool=observe-stream active=32",
                "线程池已满：任务未排队、当场拒绝（active=32，poolSize=32，queue=64）");
        // 阶段 2b 新增的两档（死锁受害者重试的两种结局）—— 走的是**另一个**入口
        // `retry(...)` 而不是 `state(...)`：它记的不是「闸的状态翻转」，是「一次重试尝试的结果」。
        metrics.retry(Result.RETRY_SUCCESS, "retry", "relist", "action=retry attempt=2 result=success", null);
        metrics.retry(Result.RETRY_FAILED, "retry", "relist", "action=retry attempt=2 result=deadlock",
                "死锁重试失败：第 2 次尝试仍撞上死锁");

        assertThat(count("blocked")).isEqualTo(1.0);
        assertThat(count("opened")).isEqualTo(1.0);
        assertThat(count("half_open")).isEqualTo(1.0);
        assertThat(count("closed")).isEqualTo(1.0);
        assertThat(count("shed")).isEqualTo(1.0);
        assertThat(count("retry_success")).isEqualTo(1.0);
        assertThat(count("retry_failed")).isEqualTo(1.0);

        List<ProtectionEvent> events = metrics.recentEvents();
        assertThat(events).extracting(ProtectionEvent::result)
                .containsExactly("blocked", "opened", "half_open", "closed", "shed",
                        "retry_success", "retry_failed");
        assertThat(events).extracting(ProtectionEvent::key)
                .containsExactly("rateLimit", "circuitBreak", "circuitBreak", "circuitBreak", "poolIsolation",
                        "retry", "retry");
        assertThat(events).extracting(ProtectionEvent::scope)
                .containsExactly("inbound", "mysql", "mysql", "mysql", "pool", "relist", "relist");
        assertThat(events).allSatisfy(e -> assertThat(e.epochMilli()).isPositive());
        // 块 7 · 阶段 3a.3 新增：拦截耗时（纳秒入参 → 微秒落进事件）与发起者身份。
        // 只有 blocked（入站限流）这一档有值；熔断翻转 / 池挤出 / 重试都是瞬时回调 ⇒ null
        assertThat(events.get(0).durationUs()).as("纳秒→微秒：1500ns→1µs").isEqualTo(1L);
        assertThat(events.get(1).durationUs()).as("熔断翻转是瞬时回调 ⇒ null（没测到，不是 0）").isNull();
        assertThat(events.get(0).actorId()).as("无 MDC ⇒ actorId 为 null").isNull();
        assertThat(events.get(0).actorRole()).as("无 MDC ⇒ actorRole 为 null").isNull();
        // 块 7 · 阶段 3a.3 后续新增：失败/翻转档的人话原因；CLOSED（恢复）与 RETRY_SUCCESS 为 null
        assertThat(events).extracting(ProtectionEvent::reason)
                .as("blocked / opened / half_open / shed / retry_failed 有原因；closed / retry_success 无")
                .containsExactly("入站限流：未取得令牌（limitForPeriod=200）",
                        "熔断打开：失败率 50%，慢调用率 20%",
                        "熔断半开：等待期结束，放行试探请求",
                        null,
                        "线程池已满：任务未排队、当场拒绝（active=32，poolSize=32，queue=64）",
                        null,
                        "死锁重试失败：第 2 次尝试仍撞上死锁");
    }

    /**
     * ⚠️ 这条钉的是**基数铁律**：进 tag 的只有 {@code protection}（五个 key）与 {@code result}
     * （七档），两列都是**有界**的。{@code scope} 与 {@code detail} 进了事件、**不进 tag** ——
     * {@code detail} 是自由文本（含路径、熔断器名、异常类名……），放 tag 就是把指标基数交给运行时：
     * 每个不同的路径一个时间序列，Prometheus 那边会先爆。
     */
    @Test
    @DisplayName("基数铁律：detail 里的自由文本**不进 tag**（两条不同 detail 落进同一个时间序列）")
    void freeTextDetailNeverBecomesATag() {
        metrics.blocked("rateLimit", "inbound", "path=/goods/goodsSelectHome", null, "入站限流：未取得令牌");
        metrics.blocked("rateLimit", "inbound", "path=/order/orderReceive", null, "入站限流：未取得令牌");

        // 两条事件的 detail 不同，但计数只有一个序列、值 2 ⇒ detail 没进 tag
        assertThat(count("blocked")).as("同一个 (protection, result) 两个不同 detail ⇒ 仍是一条序列").isEqualTo(2.0);
        assertThat(registry.find(ProtectionMetrics.EVENTS).counters())
                .as("只注册过一个计数器（detail 若进 tag 这里会变成 2 个）").hasSize(1);
        assertThat(metrics.recentEvents()).extracting(ProtectionEvent::detail)
                .as("但两条事件的明细各自保留（那是人看的那一路）")
                .containsExactly("path=/goods/goodsSelectHome", "path=/order/orderReceive");
    }

    @Test
    @DisplayName("key 进 tag：不同 key 是不同序列（页面按 key 数「哪道闸拦得多」靠它）")
    void keyIsATag() {
        metrics.blocked("rateLimit", "inbound", "d1", null, "入站限流：未取得令牌");
        metrics.blocked("rateLimit", "inbound", "d2", null, "入站限流：未取得令牌");
        metrics.blocked("circuitBreak", "mysql", "d3", null, "熔断开路：熔断器 mallMysqlWrite 已打开，本次调用未执行");

        assertThat(registry.find(ProtectionMetrics.EVENTS).tag("protection", "rateLimit")
                .tag("result", "blocked").counter().count()).isEqualTo(2.0);
        assertThat(registry.find(ProtectionMetrics.EVENTS).tag("protection", "circuitBreak")
                .tag("result", "blocked").counter().count()).isEqualTo(1.0);
    }

    // ================= 上下文（traceId / batchId 在**记录那一刻**取）=================

    /**
     * ⚠️ 取值点与九个环逐字相同：**记录那一刻**读 MDC，而不是并入合并环时读
     * （并入跑在 SSE 流线程上，那里 MDC 是空的 —— 理由写在 {@code EventBroadcaster.ObservedEvent}）。
     * 本环的写入方里有一路（熔断半开）**不在请求线程上**，那时两个值就是 {@code null}，
     * 而那是**如实**的：它不属于任何一次请求、任何一批演练。
     */
    @Test
    @DisplayName("traceId / batchId 在记录那一刻从 MDC 取；MDC 空时为 null（不是 \"null\" 字面量）")
    void contextIsCapturedAtRecordTime() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace0009");
        MDC.put(TraceIdFilter.MDC_BATCH_KEY, "batch-9");
        try {
            metrics.blocked("rateLimit", "inbound", "在请求线程上", null, "入站限流：未取得令牌");
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY);
            MDC.remove(TraceIdFilter.MDC_BATCH_KEY);
        }
        // 到这里 MDC 已清空 —— 若取值点落在 recentEvents() 上，下面两条都会是 null
        metrics.shed("poolIsolation", "pool", "在池线程上（无 MDC）", "线程池已满：任务未排队、当场拒绝");

        List<ProtectionEvent> events = metrics.recentEvents();
        assertThat(events.get(0).traceId()).isEqualTo("trace0009");
        assertThat(events.get(0).batchId()).isEqualTo("batch-9");
        assertThat(events.get(1).traceId()).isNull();
        assertThat(events.get(1).batchId()).isNull();
    }

    // ================= 事件环 =================

    @Test
    @DisplayName("事件环：写满即覆盖最旧、seq 单调；容量有界（2048，与既有九环同值）")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        int extra = 88;
        for (int i = 0; i < capacity + extra; i++) {
            metrics.shed("poolIsolation", "pool", "第 " + i + " 次", "线程池已满：任务未排队、当场拒绝");
        }

        List<ProtectionEvent> events = metrics.recentEvents();
        assertThat(events).as("容量有界：只留最近 %d 条", capacity).hasSize(capacity);
        assertThat(events.get(0).seq()).as("最旧的一条是第 %d 个", extra).isEqualTo(extra);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + extra - 1L);
        assertThat(count("shed")).as("计数通道与事件通道同源").isEqualTo(capacity + extra);
    }

    @Test
    @DisplayName("空环：recentEvents() 给空表（不是 null）—— 并入方据此判「什么也没有」")
    void emptyRingIsAnEmptyList() {
        assertThat(metrics.recentEvents()).isNotNull().isEmpty();
    }

    // ================= 工具 =================

    /** 计数器当前值；没注册过该 result 时按 0 —— 即「这条时间序列不存在」（同 {@code StockMetricsTest}）。 */
    private double count(String result) {
        var counter = registry.find(ProtectionMetrics.EVENTS).tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
