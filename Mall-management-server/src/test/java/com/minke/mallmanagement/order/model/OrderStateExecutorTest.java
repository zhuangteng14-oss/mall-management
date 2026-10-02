package com.minke.mallmanagement.order.model;

import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.OrderStateEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link OrderStateExecutor} 的行为实测 —— 重点是**拒绝分支**。
 *
 * <h2>【为什么拒绝分支必须在单测里覆盖】</h2>
 * A3 的并发度是 20~40 线程、每单最多 3 次成功转移 ⇒ 瞬时在途任务个位数，离默认容量 1024
 * 三个数量级。也就是说：**A3 里这条分支永远不会被走到**，场景测试能断言的只是
 * 「{@code async.rejected} 恒为 0」。若不在单测里把「队列打满时到底丢谁、记在谁头上」钉死，
 * 这个策略就只存在于注释里 —— 而它恰恰是本笔唯一会**丢数据**的地方。
 *
 * <h2>【本文件里最值钱的两条】</h2>
 * <ol>
 *   <li>{@link #queueFullDropsTheOldestAndRecordsWhichOrderWasLost} —— 丢对任务
 *       （丢最旧，不是丢新来的）、且**丢之前记下了丢的是哪一单**。JDK 的
 *       {@code DiscardOldestPolicy} 正好错在后者（poll 完直接扔、不回调任何人）。</li>
 *   <li>{@link #shutdownNeverDropsTasksAlreadyQueued} —— 关闭只拒绝**新**任务，
 *       不借机丢弃已入队的。</li>
 * </ol>
 *
 * <p>⚠️ 本文件用**字面量**引用指标名：本类在 {@code …service} 包，而
 * {@code MallOrderStateMetrics} 里的名字常量是包私有的（沿用 {@code MallLockMetrics} 的既有约定，
 * 名字的唯一出处 + 基数约束由 {@code MallOrderStateMetricsTest} 在同一包内验证）。
 * 故这里的字面量是**独立的第二份陈述**，不是抄错的来源；改名字时两处都要改，
 * 而 {@code MallOrderStateMetricsTest} 会先红。
 *
 * <p>不起 Spring、不起 DB：{@code MallOrderStateMetrics} 是直接 new 的，
 * 线程池是本类按测试需要现配的（容量 1 —— 正常配置是 1024，那个数字在测试里没法制造满载）。
 */
class OrderStateExecutorTest {

    private static final String REJECTED = "mall.order.state.async.rejected";
    private static final String DURATION = "mall.order.state.async.duration";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallOrderStateMetrics metrics = new MallOrderStateMetrics(registry);

    /** 跑得快、不阻塞的池 —— 用于「正常路径」。 */
    private OrderStateExecutor executor(int core, int max, int queue, Duration shutdownTimeout) {
        return new OrderStateExecutor(metrics, core, max, queue, shutdownTimeout);
    }

    // ===================== 一、正常路径 =====================

    @Test
    @DisplayName("任务真的在池线程上跑（线程名 order-state-*），不占用调用线程")
    void taskRunsOnAPoolThreadNotTheCaller() throws Exception {
        OrderStateExecutor exec = executor(2, 4, 16, Duration.ofSeconds(5));
        try {
            CountDownLatch done = new CountDownLatch(1);
            Queue<String> threadNames = new ConcurrentLinkedQueue<>();
            exec.submit("ORDER-1", () -> {
                threadNames.add(Thread.currentThread().getName());
                done.countDown();
            });

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(threadNames).hasSize(1);
            assertThat(threadNames.peek())
                    .as("线程名是排障时唯一能定位「谁在写时间线」的线索，不该是 pool-N-thread-M")
                    .startsWith("order-state-");
            assertThat(threadNames.peek())
                    .as("必须是别处的线程，否则就是同步执行 —— @Async 的自调用正是这么静默失效的")
                    .isNotEqualTo(Thread.currentThread().getName());
        } finally {
            exec.destroy();
        }
    }

    @Test
    @DisplayName("每个任务都记一次执行墙钟 —— 抛异常的任务也记（finally 里记）")
    void durationIsRecordedEvenWhenTheTaskThrows() throws Exception {
        OrderStateExecutor exec = executor(1, 1, 8, Duration.ofSeconds(5));
        try {
            AtomicInteger ran = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(3);
            // ⚠️ 抛异常的那个任务：模拟「落库炸了」。它不该掀翻线程池，也不该漏掉计时 ——
            //    漏掉会让 mall.order.state.async.duration 系统性偏乐观（失败的那次同样占了线程）。
            exec.submit("ORDER-OK-1", () -> {
                ran.incrementAndGet();
                done.countDown();
            });
            exec.submit("ORDER-BOOM", () -> {
                ran.incrementAndGet();
                done.countDown();
                throw new IllegalStateException("模拟落库失败");
            });
            exec.submit("ORDER-OK-2", () -> {
                ran.incrementAndGet();
                done.countDown();
            });

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(ran).as("一个抛异常的任务不该让后续任务不跑").hasValue(3);

            assertThat(waitUntil(() -> durationCount() >= 3L)).as("三笔计时，一笔不少").isTrue();
            assertThat(durationCount()).isEqualTo(3L);
        } finally {
            exec.destroy();
        }
    }

    @Test
    @DisplayName("submit 永不把动作里的异常带回调用方（它跑在别的线程上）")
    void submitNeverPropagatesTaskFailure() {
        OrderStateExecutor exec = executor(1, 1, 8, Duration.ofSeconds(5));
        try {
            assertThatCode(() -> exec.submit("ORDER-BOOM", () -> {
                throw new IllegalStateException("模拟落库失败");
            })).doesNotThrowAnyException();
        } finally {
            exec.destroy();
        }
    }

    @Test
    @DisplayName("队列深度 gauge 是活值：池里积压时读得到（A3 的排空判据正是它）")
    void queueDepthGaugeReflectsPendingWork() throws Exception {
        OrderStateExecutor exec = executor(1, 1, 8, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            exec.submit("ORDER-BLOCK", () -> {
                started.countDown();
                awaitQuietly(release);
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            exec.submit("ORDER-Q1", () -> { });
            exec.submit("ORDER-Q2", () -> { });

            assertThat(waitUntil(() -> exec.queueSize() == 2)).isTrue();
            assertThat(registry.find("mall.order.state.async.queue.depth").gauge().value())
                    .as("gauge 与 queueSize() 同源，不是绑定时快照")
                    .isEqualTo(2.0);
        } finally {
            release.countDown();
            exec.destroy();
        }
    }

    // ===================== 二、拒绝分支（A3 走不到，只能在这里钉死）=====================

    @Test
    @DisplayName("队列打满 ⇒ 丢**最旧**，并记下丢的是哪一单（JDK DiscardOldestPolicy 记不下这一笔）")
    void queueFullDropsTheOldestAndRecordsWhichOrderWasLost() throws Exception {
        // 容量 1 的池：队首就是「最旧」。默认 1024 在测试里造不出满载。
        OrderStateExecutor exec = executor(1, 1, 1, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Queue<String> ran = new ConcurrentLinkedQueue<>();
        try {
            // ① 占住唯一的线程（该任务会一直阻塞到 release）
            exec.submit("ORDER-RUNNING", () -> {
                started.countDown();
                awaitQuietly(release);
                ran.add("ORDER-RUNNING");
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            // ② 入队 —— 此刻队列 [ORDER-OLDEST]
            exec.submit("ORDER-OLDEST", () -> ran.add("ORDER-OLDEST"));
            assertThat(waitUntil(() -> exec.queueSize() == 1)).isTrue();

            // ③ 再投一个 ⇒ 队列打满 ⇒ 丢队首（ORDER-OLDEST），放进 ORDER-NEWEST
            exec.submit("ORDER-NEWEST", () -> ran.add("ORDER-NEWEST"));

            assertThat(waitUntil(() -> count(REJECTED, "reason", "queue_full") == 1.0))
                    .as("丢弃必须被计数 —— 允许丢，不许静默").isTrue();
            assertThat(count(REJECTED, "reason", "queue_full")).isEqualTo(1.0);
            assertThat(count(REJECTED, "reason", "shutdown")).as("这不是关闭场景").isZero();

            List<OrderStateEvent> rejectedEvents = metrics.recentEvents().stream()
                    .filter(e -> "rejected".equals(e.result())).toList();
            assertThat(rejectedEvents).hasSize(1);
            assertThat(rejectedEvents.get(0).key())
                    .as("关键：丢的是**最旧**那条，且事件流能回答「是哪一单」——"
                            + "这正是本类不能用 JDK DiscardOldestPolicy 的原因")
                    .isEqualTo("ORDER-OLDEST");
            assertThat(rejectedEvents.get(0).shape()).isEqualTo("queue_full");
            assertThat(exec.queueSize()).as("新来的那条在队列里，不是被丢的那条").isEqualTo(1);

            // ④ 放开，看谁真的跑了
            release.countDown();
            assertThat(waitUntil(() -> ran.size() == 2)).isTrue();
            assertThat(ran).containsExactlyInAnyOrder("ORDER-RUNNING", "ORDER-NEWEST");
            assertThat(ran).as("被丢弃的那条**不会**执行 —— 它对应的时间线永久缺一条，这是已知代价")
                    .doesNotContain("ORDER-OLDEST");
        } finally {
            release.countDown();
            exec.destroy();
        }
    }

    @Test
    @DisplayName("关闭中提交 ⇒ reason=shutdown，且**不借机关闭丢弃已入队的任务**")
    void shutdownNeverDropsTasksAlreadyQueued() throws Exception {
        OrderStateExecutor exec = executor(1, 1, 8, Duration.ofSeconds(30));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Queue<String> ran = new ConcurrentLinkedQueue<>();
        try {
            exec.submit("ORDER-RUNNING", () -> {
                started.countDown();
                awaitQuietly(release);
                ran.add("ORDER-RUNNING");
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            exec.submit("ORDER-QUEUED-1", () -> ran.add("ORDER-QUEUED-1"));
            exec.submit("ORDER-QUEUED-2", () -> ran.add("ORDER-QUEUED-2"));
            assertThat(waitUntil(() -> exec.queueSize() == 2)).isTrue();

            // 关闭必须**在另一个线程上**发起：唯一的池线程此刻正阻塞在 release 上，
            // destroy() 会一直等它（awaitTermination）—— 在主线程直接调就会把本测试卡死。
            Thread closer = new Thread(exec::destroy, "closer");
            closer.start();
            assertThat(waitUntil(exec::isShutdown))
                    .as("等到关闭信号确实发出（destroy 已进入 awaitTermination）").isTrue();

            // 此刻：池已 shutdown，但两条已入队的任务还躺在队列里。
            // 关闭的语义是「不再收新的、已入队的跑完」，不是 shutdownNow。
            exec.submit("ORDER-AFTER-SHUTDOWN", () -> ran.add("ORDER-AFTER-SHUTDOWN"));

            assertThat(count(REJECTED, "reason", "shutdown"))
                    .as("关闭后的提交必须记 shutdown —— 与队列打满（queue_full）是两回事").isEqualTo(1.0);
            assertThat(count(REJECTED, "reason", "queue_full"))
                    .as("关闭路径不该顺手丢已入队的任务（那不是打满）").isZero();
            assertThat(metrics.recentEvents().stream()
                    .filter(e -> "rejected".equals(e.result()))
                    .map(OrderStateEvent::key).toList())
                    .containsExactly("ORDER-AFTER-SHUTDOWN");
            assertThat(exec.queueSize()).as("已入队的两条仍在队列里，没被关闭顺手清掉").isEqualTo(2);

            // 放开：跑完阻塞的那条，队列里的两条随后在关闭流程中跑完
            release.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(closer.isAlive()).as("destroy 应在排空后返回，不该挂住").isFalse();

            assertThat(ran).as("已入队的两条都跑完了，且新来的那条没有")
                    .containsExactlyInAnyOrder("ORDER-RUNNING", "ORDER-QUEUED-1", "ORDER-QUEUED-2");
            assertThat(ran).doesNotContain("ORDER-AFTER-SHUTDOWN");
        } finally {
            release.countDown();
            exec.destroy();
        }
    }

    // ===================== 三、关闭时排空 =====================

    @Test
    @DisplayName("destroy 会排空已入队的任务（shutdown + awaitTermination），不是直接掐掉")
    void destroyDrainsQueuedTasks() throws Exception {
        OrderStateExecutor exec = executor(2, 4, 64, Duration.ofSeconds(5));
        int n = 40;
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            String orderId = "ORDER-" + i;
            exec.submit(orderId, done::countDown);
        }
        exec.destroy();
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("%d 条时间线在关闭时全部落库，不该有未完成的被放弃", n).isTrue();
    }

    @Test
    @DisplayName("默认容量下的关停不丢任何任务：async.rejected 恒为 0（A3 要断言的正是这个 0）")
    void defaultConfigurationNeverRejectsUnderOrdinaryLoad() throws Exception {
        OrderStateExecutor exec = executor(2, 4, 1024, Duration.ofSeconds(10));
        int n = 200;
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            exec.submit("ORDER-" + i, done::countDown);
        }
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(count(REJECTED, "reason", "queue_full")).isZero();
        assertThat(count(REJECTED, "reason", "shutdown")).isZero();
        exec.destroy();
    }

    // ===================== 四、启动期自检（只 WARN，不该抛）=====================

    @Test
    @DisplayName("配置反了也不抛：core>max、capacity<1 只 WARN 并夹到合法值（同本仓其它启动期校验）")
    void misconfigurationWarnsButStillStarts() {
        assertThatCode(() -> {
            OrderStateExecutor exec = new OrderStateExecutor(metrics, 4, 2, 0, Duration.ofSeconds(5));
            try {
                exec.submit("ORDER-1", () -> { });
            } finally {
                exec.destroy();
            }
        }).doesNotThrowAnyException();
    }

    // ===================== 工具 =====================

    /** 计数器的当前值；没注册过该 (name, tags) 组合时按 0。 */
    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long durationCount() {
        var timer = registry.find(DURATION).timer();
        return timer == null ? 0L : timer.count();
    }

    /** 有界轮询等待。**不用固定 sleep** —— B1 的教训是窗口要能证明，而不是猜一个数。 */
    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() - deadline < 0) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(2);
        }
        return condition.getAsBoolean();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
