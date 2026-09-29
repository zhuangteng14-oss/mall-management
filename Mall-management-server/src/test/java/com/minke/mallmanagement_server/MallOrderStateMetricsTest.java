package com.minke.mallmanagement_server;

import com.minke.mallmanagement_server.MallOrderStateMetrics.OrderStateEvent;
import com.minke.mallmanagement_server.MallOrderStateMetrics.RejectReason;
import com.minke.mallmanagement_server.MallOrderStateMetrics.Result;
import com.minke.mallmanagement_service.OrderAction;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机埋点（B3 / {@link MallOrderStateMetrics}）的语义实测 —— 分门别类对不对、两条通道是不是真的都发。
 *
 * <p>与 {@code MallLockMetricsTest} / {@code MallCacheMetricsTest} 同一套路：不起 Spring、不起 Redis、
 * 不起线程池。SimpleMeterRegistry 每个测试方法一份 ⇒ 计数天然隔离。
 *
 * <p>⚠️ 本文件**验不到**两件事，别把它当成「都验过了」：
 * <ol>
 *   <li><b>绑定的存在性</b>（{@code bindQueueDepth} 到底有没有被调用）—— 那由 {@code OrderStateExecutor}
 *       的构造期负责，端到端由 {@code /actuator/metrics/mall.order.state.async.queue.depth} 覆盖；
 *       本文件只验 gauge 的**语义**（值是活的）。</li>
 *   <li><b>Gauge 的弱引用陷阱</b>（不存强引用 ⇒ 一次 GC 后静默变 NaN）—— 无法确定性断言
 *       （{@code System.gc()} 不保证发生，断言会变成碰运气）。它在生产侧由
 *       {@code MallOrderStateMetrics.queueDepth} 字段持有，与 C1 {@code bindL2Up}、B2 {@code bindUp}
 *       同一处修法。</li>
 * </ol>
 */
class MallOrderStateMetricsTest {

    private static final String ORDER = "A3TEST-run-DUP";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallOrderStateMetrics metrics = new MallOrderStateMetrics(registry);

    // ================= transitions =================

    @Test
    @DisplayName("transitions 三路各自计数，且事件流里带着行动作与前后态")
    void transitionOutcomesAreRecordedOnBothChannels() {
        metrics.transition(OrderAction.PAY, "CREATED", "PAID", Result.APPLIED, ORDER);
        metrics.transition(OrderAction.PAY, "PAID", null, Result.REJECTED, ORDER);
        metrics.transition(OrderAction.DELIVER, "PAID", null, Result.ERROR, ORDER);

        assertThat(count(MallOrderStateMetrics.TRANSITIONS, "action", "pay", "result", "applied")).isEqualTo(1.0);
        assertThat(count(MallOrderStateMetrics.TRANSITIONS, "action", "pay", "result", "rejected")).isEqualTo(1.0);
        assertThat(count(MallOrderStateMetrics.TRANSITIONS, "action", "deliver", "result", "error")).isEqualTo(1.0);

        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::kind).containsOnly("transition");
        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::action)
                .containsExactly("pay", "pay", "deliver");
        assertThat(metrics.recentEvents().get(0).from()).isEqualTo("CREATED");
        assertThat(metrics.recentEvents().get(0).to()).as("只有 applied 才有「转移后」").isEqualTo("PAID");
        assertThat(metrics.recentEvents().get(1).to()).as("被拒绝时没有后态").isNull();
    }

    @Test
    @DisplayName("业务键只进事件流、绝不进 tag（基数铁律）：两次不同订单的转移共用一个时间序列")
    void businessKeyNeverBecomesATag() {
        metrics.transition(OrderAction.PAY, "CREATED", "PAID", Result.APPLIED, "ORDER-1");
        metrics.transition(OrderAction.PAY, "CREATED", "PAID", Result.APPLIED, "ORDER-2");

        assertThat(count(MallOrderStateMetrics.TRANSITIONS, "action", "pay", "result", "applied"))
                .as("两个不同的订单落在同一条计数上").isEqualTo(2.0);
        assertThat(registry.find(MallOrderStateMetrics.TRANSITIONS).counters())
                .as("该指标下的时间序列数 = 1（若订单号进了 tag 就会变成 2，基数无界）").hasSize(1);
        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::key)
                .as("但事件流里必须能看出是哪一单").containsExactly("ORDER-1", "ORDER-2");
    }

    @Test
    @DisplayName("transitions 的 tag 全部封闭：4 个动作 × 3 个结果，最多 12 条时间序列")
    void transitionTagsAreBounded() {
        for (OrderAction action : new OrderAction[]{OrderAction.CREATE, OrderAction.PAY,
                OrderAction.DELIVER, OrderAction.RECEIVE}) {
            for (Result r : Result.values()) {
                metrics.transition(action, "X", "Y", r, ORDER);
            }
        }
        assertThat(registry.find(MallOrderStateMetrics.TRANSITIONS).counters())
                .as("4 × 3，一条不多 —— 这是基数铁律的可执行形式")
                .hasSize(12);
    }

    // ================= illegal =================

    @Test
    @DisplayName("illegal：四种 shape 各自计数，事件里带订单号（TD-17 的探测器）")
    void illegalShapesAreRecorded() {
        metrics.illegal("010", ORDER);
        metrics.illegal("001", ORDER);
        metrics.illegal("011", ORDER);
        metrics.illegal("non_binary", ORDER);

        assertThat(count(MallOrderStateMetrics.ILLEGAL, "shape", "010")).isEqualTo(1.0);
        assertThat(count(MallOrderStateMetrics.ILLEGAL, "shape", "011")).isEqualTo(1.0);
        assertThat(count(MallOrderStateMetrics.ILLEGAL, "shape", "non_binary")).isEqualTo(1.0);
        assertThat(registry.find(MallOrderStateMetrics.ILLEGAL).counters())
                .as("shape 是有界取值：3 个非法位组合 + non_binary，恰好 4 条").hasSize(4);

        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::kind).containsOnly("illegal");
        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::shape)
                .containsExactly("010", "001", "011", "non_binary");
        assertThat(metrics.recentEvents()).allSatisfy(e -> {
            assertThat(e.action()).as("非法态观测不是一次转移动作").isNull();
            assertThat(e.key()).isEqualTo(ORDER);
        });
    }

    // ================= timeline.writes =================

    @Test
    @DisplayName("timeline.writes：ok 只计数**不发事件**，error 两条通道都发 —— 失败才需要追到哪一单")
    void timelineWriteOnlyEmitsAnEventOnFailure() {
        metrics.timelineWrite(true, ORDER);
        metrics.timelineWrite(true, ORDER);
        metrics.timelineWrite(false, ORDER);

        assertThat(count(MallOrderStateMetrics.TIMELINE_WRITES, "result", "ok")).isEqualTo(2.0);
        assertThat(count(MallOrderStateMetrics.TIMELINE_WRITES, "result", "error")).isEqualTo(1.0);

        assertThat(metrics.recentEvents())
                .as("3 次落库只产生 1 条事件 —— 成功的落库走计数，理由见类注释的「热路径」段")
                .hasSize(1);
        assertThat(metrics.recentEvents().get(0).kind()).isEqualTo("async");
        assertThat(metrics.recentEvents().get(0).result()).isEqualTo("error");
        assertThat(metrics.recentEvents().get(0).key()).isEqualTo(ORDER);
    }

    // ================= async.rejected / duration / queue.depth =================

    @Test
    @DisplayName("async.rejected：两路 reason 各自计数，且**必须发事件**（丢一条时间线不是可忽略的降级）")
    void asyncRejectionIsAlwaysAnEvent() {
        metrics.asyncRejected(RejectReason.QUEUE_FULL, ORDER);
        metrics.asyncRejected(RejectReason.SHUTDOWN, ORDER);

        assertThat(count(MallOrderStateMetrics.ASYNC_REJECTED, "reason", "queue_full")).isEqualTo(1.0);
        assertThat(count(MallOrderStateMetrics.ASYNC_REJECTED, "reason", "shutdown")).isEqualTo(1.0);
        assertThat(registry.find(MallOrderStateMetrics.ASYNC_REJECTED).counters()).hasSize(2);

        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::result)
                .containsExactly("rejected", "rejected");
        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::shape)
                .as("被拒时 reason 落在 shape 字段上").containsExactly("queue_full", "shutdown");
    }

    @Test
    @DisplayName("async.duration 是 Timer（不是计数器 —— 用 count(...) 查会静默查不到而假绿）")
    void asyncDurationIsATimer() {
        metrics.asyncDuration(TimeUnit.MILLISECONDS.toNanos(2));
        metrics.asyncDuration(TimeUnit.MILLISECONDS.toNanos(9));

        Timer t = registry.find(MallOrderStateMetrics.ASYNC_DURATION).timer();
        assertThat(t).as("必须真的注册成 Timer，否则下面的断言是假绿").isNotNull();
        assertThat(t.count()).isEqualTo(2L);
        assertThat(t.max(TimeUnit.MILLISECONDS)).isEqualTo(9.0);
        assertThat(metrics.recentEvents()).as("计时走独立的计时器，不发事件").isEmpty();
    }

    @Test
    @DisplayName("queue.depth 是活值：scrape 时才调用取值函数（不是绑定时快照）")
    void queueDepthReadsTheSupplierLive() {
        AtomicInteger depth = new AtomicInteger(0);
        metrics.bindQueueDepth(depth::get);

        assertThat(registry.find(MallOrderStateMetrics.ASYNC_QUEUE_DEPTH).gauge().value()).isZero();
        depth.set(7);
        assertThat(registry.find(MallOrderStateMetrics.ASYNC_QUEUE_DEPTH).gauge().value())
                .as("取值函数被调用 ⇒ 不是绑定时快照；它同时是并发测试的排空判据")
                .isEqualTo(7.0);
        depth.set(0);
        assertThat(registry.find(MallOrderStateMetrics.ASYNC_QUEUE_DEPTH).gauge().value()).isZero();
    }

    @Test
    @DisplayName("不埋 async.submitted：它在 timeline.enabled=true 时恒等于 transitions{applied}（规范第 6 条）")
    void submittedIsDeliberatelyNotASeparateMeter() {
        metrics.transition(OrderAction.PAY, "CREATED", "PAID", Result.APPLIED, ORDER);

        assertThat(registry.find("mall.order.state.async.submitted").counters())
                .as("可派生值不重复埋点 —— 该等式由启动期打印的 timeline.enabled 生效值保证可判定")
                .isEmpty();
    }

    // ================= 事件环 =================

    @Test
    @DisplayName("事件流：写满即覆盖最旧、seq 单调；两条通道在同一调用点一起发")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 512;
        for (int i = 0; i < capacity + 88; i++) {
            metrics.transition(OrderAction.PAY, "CREATED", "PAID", Result.APPLIED, ORDER);
        }

        List<OrderStateEvent> events = metrics.recentEvents();
        assertThat(events).as("容量有界：只留最近 %d 条", capacity).hasSize(capacity);
        assertThat(events.get(0).seq()).as("最旧的一条是第 88 个").isEqualTo(88L);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + 87L);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.kind()).isEqualTo("transition");
            assertThat(e.result()).isEqualTo("applied");
            assertThat(e.key()).isEqualTo(ORDER);
            assertThat(e.epochMilli()).isPositive();
        });
        assertThat(count(MallOrderStateMetrics.TRANSITIONS, "action", "pay", "result", "applied"))
                .as("计数通道与事件通道同源：600 次调用 = 计数 600").isEqualTo(600.0);
    }

    @Test
    @DisplayName("并发追加：快照里的 seq 严格递增且不重复（写者只做数组赋值，读者靠 seq 自校验）")
    void concurrentAppendsKeepSnapshotCoherent() throws Exception {
        int threads = 4;
        int perThread = 300;   // 4 × 300 = 1200 > 容量 512 ⇒ 必然发生覆盖
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                String key = "ORDER-" + t;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            metrics.transition(OrderAction.RECEIVE, "SHIPPED", "COMPLETED",
                                    Result.APPLIED, key);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<OrderStateEvent> snapshot = metrics.recentEvents();
        List<Long> seqs = new ArrayList<>();
        snapshot.forEach(e -> seqs.add(e.seq()));
        Set<Long> unique = new HashSet<>(seqs);
        assertThat(seqs).as("seq 严格递增（读者按 seq 自校验后不应出现错位）").isSorted();
        assertThat(unique).as("同一条事件不该在快照里出现两次").hasSameSizeAs(seqs);
        assertThat(snapshot).as("容量有界").hasSizeLessThanOrEqualTo(512);
    }

    // ================= 工具 =================

    /** 计数器的当前值；没注册过该 (name, tags) 组合时按 0 —— 即「这条时间序列不存在」。 */
    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
