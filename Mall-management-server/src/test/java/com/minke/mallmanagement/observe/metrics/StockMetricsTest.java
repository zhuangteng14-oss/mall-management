package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.observe.metrics.StockMetrics.Deduct;
import com.minke.mallmanagement.observe.metrics.StockMetrics.Release;
import com.minke.mallmanagement.observe.metrics.StockMetrics.StockEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存埋点（{@link StockMetrics}）的语义实测 —— 分门别类对不对、两条通道是不是真的都发。
 *
 * <p>与 {@code MallLockMetricsTest} 同一套路：不起 Spring、不起 MySQL。测的是<b>分类</b>
 * （哪种情形记哪个计数 / 哪条事件），SimpleMeterRegistry 每个测试方法一份（JUnit 每方法新建
 * 测试对象）⇒ 计数天然隔离。
 *
 * <p>⚠️ 本文件验的是**本类**（出口）的语义；「下单真的会走到这里吗」由 smoke 链路
 * （{@code OrderAddItemsSmokeTest} 等）与本类在 {@code OrderService} / {@code OrderTimeoutCancelJob}
 * 里的调用点共同保证 —— 本文件不重复验那一层。
 */
class StockMetricsTest {

    private static final String SKU = "SKU-0001";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StockMetrics metrics = new StockMetrics(registry);

    // ================= deduct =================

    @Test
    @DisplayName("deduct 三路各自计数，且事件与计数在同一调用点一起发")
    void deductOutcomesAreRecorded() {
        metrics.deduct(SKU, 2, Deduct.OK, 1_500L, 98, null);
        metrics.deduct(SKU, 2, Deduct.INSUFFICIENT, 2_000_000L, 1,
                "防超卖拦截：库存不足（需 2 件，剩余 1 件）");
        metrics.deduct(SKU, 2, Deduct.ERROR, 1_500L, null,
                com.minke.mallmanagement.shared.text.EventReason.of("库存扣减失败", new IllegalStateException("db down")));

        assertThat(count(StockMetrics.DEDUCT, "ok")).isEqualTo(1.0);
        assertThat(count(StockMetrics.DEDUCT, "insufficient")).isEqualTo(1.0);
        assertThat(count(StockMetrics.DEDUCT, "error")).isEqualTo(1.0);

        List<StockEvent> events = metrics.recentEvents();
        assertThat(events).extracting(StockEvent::kind).containsExactly("deduct", "deduct", "deduct");
        assertThat(events).extracting(StockEvent::result).containsExactly("ok", "insufficient", "error");
        assertThat(events).extracting(StockEvent::skuCode).containsExactly(SKU, SKU, SKU);
        assertThat(events).extracting(StockEvent::qty).containsExactly(2, 2, 2);
        assertThat(events).allSatisfy(e -> assertThat(e.epochMilli()).isPositive());
        // 块 7 · 阶段 3a.3 新增：扣减耗时（纳秒入参 → 微秒落进事件）与发起者身份
        assertThat(events).extracting(StockEvent::durationUs)
                .as("纳秒→微秒：1500ns→1µs、2000000ns→2000µs").containsExactly(1L, 2_000L, 1L);
        assertThat(events.get(0).actorId()).as("无 MDC ⇒ actorId 为 null").isNull();
        assertThat(events.get(0).actorRole()).as("无 MDC ⇒ actorRole 为 null").isNull();
        // 块 7 · 阶段 3a.3 第二步新增：**当时剩余**原样透传（本类不查库、不兜底 —— 见 StockEvent#remaining）
        assertThat(events).extracting(StockEvent::remaining)
                .as("ok=98 / insufficient=1（扣不动时还剩多少）/ error=null（UPDATE 生效没有都不知道）")
                .containsExactly(98, 1, null);
        // 块 7 · 阶段 3a.3 后续新增：失败原因（成功档为 null，不写空原因）
        assertThat(events).extracting(StockEvent::reason)
                .as("ok ⇒ null；insufficient / error ⇒ 具体人话原因")
                .containsExactly(null, "防超卖拦截：库存不足（需 2 件，剩余 1 件）",
                        "库存扣减失败：IllegalStateException：db down");
    }

    @Test
    @DisplayName("reason 有界：超长原因裁到 160 字并以「…」结尾（异常消息不可控）")
    void reasonIsClipped() {
        String longMessage = "x".repeat(400);
        metrics.deduct(SKU, 1, Deduct.ERROR, 1_500L, null,
                com.minke.mallmanagement.shared.text.EventReason.of("库存扣减失败",
                        new IllegalStateException(longMessage)));

        String reason = metrics.recentEvents().get(0).reason();
        assertThat(reason).hasSize(160).endsWith("…");
        assertThat(reason).startsWith("库存扣减失败：IllegalStateException：xxx");
    }

    @Test
    @DisplayName("⚠️ CAS 0 行记 insufficient、**不是** error —— 判成失败会让告警误报（A 档第 4 条）")
    void zeroRowsIsNotAnError() {
        metrics.deduct(SKU, 1, Deduct.INSUFFICIENT, 1_500L, 0, "防超卖拦截：库存不足（需 1 件，剩余 0 件）");

        assertThat(count(StockMetrics.DEDUCT, "insufficient")).isEqualTo(1.0);
        assertThat(count(StockMetrics.DEDUCT, "error"))
                .as("0 行是正常分支（库存不足 / 非矩阵商品无行），落进 error 就是系统性误报")
                .isZero();
        assertThat(registry.find(StockMetrics.DEDUCT).counters())
                .as("该指标下只该有 insufficient 这一条序列").hasSize(1);
    }

    // ================= release =================

    @Test
    @DisplayName("release 三路各自计数；0 行记 missing（无处可放）、不是 error")
    void releaseOutcomesAreRecorded() {
        metrics.release(SKU, 3, Release.OK, 1_500L, null);
        metrics.release(SKU, 3, Release.MISSING, 1_500L, "释放未命中：goods_sku 中不存在该 SKU");
        metrics.release(SKU, 3, Release.ERROR, 1_500L,
                com.minke.mallmanagement.shared.text.EventReason.of("库存释放失败", new IllegalStateException("db down")));

        assertThat(count(StockMetrics.RELEASE, "ok")).isEqualTo(1.0);
        assertThat(count(StockMetrics.RELEASE, "missing")).isEqualTo(1.0);
        assertThat(count(StockMetrics.RELEASE, "error")).isEqualTo(1.0);
        assertThat(count(StockMetrics.RELEASE, "insufficient"))
                .as("不进 deduct 的取值表：两个指标各有一套封闭取值（missing ≠ insufficient）")
                .isZero();

        assertThat(metrics.recentEvents()).extracting(StockEvent::kind).containsOnly("release");
        assertThat(metrics.recentEvents()).extracting(StockEvent::result)
                .containsExactly("ok", "missing", "error");
        assertThat(metrics.recentEvents()).extracting(StockEvent::reason)
                .as("release 的失败原因也带上（ok ⇒ null）")
                .containsExactly(null, "释放未命中：goods_sku 中不存在该 SKU",
                        "库存释放失败：IllegalStateException：db down");
    }

    // ================= 基数铁律 =================

    @Test
    @DisplayName("业务键只进事件流、绝不进 tag（基数铁律）：两个不同 SKU 共用一个时间序列")
    void skuCodeNeverBecomesATag() {
        metrics.deduct("SKU-0001", 1, Deduct.OK, 1_500L, null, null);
        metrics.deduct("SKU-0002", 1, Deduct.OK, 1_500L, null, null);

        assertThat(count(StockMetrics.DEDUCT, "ok")).as("两个不同的码落在同一条计数上").isEqualTo(2.0);
        assertThat(registry.find(StockMetrics.DEDUCT).counters())
                .as("该指标下的时间序列数 = 1（若 skuCode 进了 tag 就会随商品数无界增长）").hasSize(1);
        assertThat(metrics.recentEvents()).extracting(StockEvent::skuCode)
                .as("但事件流里必须能看出是哪个码").containsExactly("SKU-0001", "SKU-0002");
    }

    // ================= 事件环 =================

    @Test
    @DisplayName("事件环：写满即覆盖最旧、seq 单调；容量有界（2048，块 7 · commit 10 裁决 2）")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        int extra = 88;
        for (int i = 0; i < capacity + extra; i++) {
            metrics.deduct(SKU, 1, Deduct.OK, 1_500L, null, null);
        }

        List<StockEvent> events = metrics.recentEvents();
        assertThat(events).as("容量有界：只留最近 %d 条", capacity).hasSize(capacity);
        assertThat(events.get(0).seq()).as("最旧的一条是第 %d 个", extra).isEqualTo(extra);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + extra - 1L);
        assertThat(count(StockMetrics.DEDUCT, "ok")).as("计数通道与事件通道同源").isEqualTo(capacity + extra);
    }

    // ================= 工具 =================

    /** 计数器的当前值；没注册过该 (name, result) 组合时按 0 —— 即「这条时间序列不存在」。 */
    private double count(String name, String result) {
        var counter = registry.find(name).tags("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
