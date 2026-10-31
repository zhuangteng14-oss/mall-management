package com.minke.mallmanagement.shared.lock;

import com.minke.mallmanagement.shared.lock.MallLockMetrics.Hold;
import com.minke.mallmanagement.shared.lock.MallLockMetrics.MallLockEvent;
import com.minke.mallmanagement.shared.lock.MallLockMetrics.Outcome;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁埋点（B2 / {@link MallLockMetrics}）的语义实测 —— 分门别类对不对、两条通道是不是真的都发。
 *
 * <p>与 {@code MallCacheMetricsTest} 同一套路：不起 Spring、不起 Redis。本类测的是**分类**
 * （哪种情形记哪个计数/哪条事件），SimpleMeterRegistry 每个测试方法一份（JUnit 每方法新建
 * 测试对象）⇒ 计数天然隔离。
 *
 * <p>⚠️ 本文件**验不到**两件事，别把它当成「都验过了」：
 * <ol>
 *   <li><b>绑定的存在性</b>（{@code bindUp} 到底有没有被调用）—— 那由 {@code DistributedLockService}
 *       的构造器负责，端到端由 {@code /actuator/metrics/mall.lock.up} 的实测覆盖；
 *       本文件只验 gauge 的**语义**（值是活的、随状态变）。</li>
 *   <li><b>Gauge 的弱引用陷阱</b>（不存强引用 ⇒ 一次 GC 后静默变 NaN）—— 无法确定性断言
 *       （{@code System.gc()} 不保证发生，断言会变成碰运气）。它在生产侧由
 *       {@code MallLockMetrics.upState} 字段持有，与 C1 {@code bindL2Up} 同一处修法。</li>
 * </ol>
 */
class MallLockMetricsTest {

    private static final String LOCK = "order-add";
    private static final String BIZ = "ORDER-1";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallLockMetrics metrics = new MallLockMetrics(registry);

    // ================= acquires / wait.duration =================

    @Test
    @DisplayName("acquires 四路各自计数，且 wait.duration 只在**真碰过网络**的两路记录（degraded 记 0 会把「没尝试」混进「等了 0 秒」）")
    void acquireOutcomesAreRecordedAndTimerSkipsDegraded() {
        metrics.acquire(LOCK, BIZ, Outcome.ACQUIRED, TimeUnit.MILLISECONDS.toNanos(3), null);
        metrics.acquire(LOCK, BIZ, Outcome.TIMEOUT, TimeUnit.MILLISECONDS.toNanos(5000),
                "取锁超时：等待 5000 ms 未获得");
        metrics.acquire(LOCK, BIZ, Outcome.DEGRADED, 0L,
                "锁客户端不可用：降级冷却期内跳过取锁");
        metrics.acquire(LOCK, BIZ, Outcome.ERROR, TimeUnit.MILLISECONDS.toNanos(1000),
                "取锁异常：IllegalStateException：db down");

        assertThat(count(MallLockMetrics.ACQUIRES, "lock", LOCK, "result", "acquired")).isEqualTo(1.0);
        assertThat(count(MallLockMetrics.ACQUIRES, "lock", LOCK, "result", "timeout")).isEqualTo(1.0);
        assertThat(count(MallLockMetrics.ACQUIRES, "lock", LOCK, "result", "degraded")).isEqualTo(1.0);
        assertThat(count(MallLockMetrics.ACQUIRES, "lock", LOCK, "result", "error")).isEqualTo(1.0);

        // 注意类型：用 count(...)→registry.find(...).counter() 查 Timer 会**静默查不到**而让断言空过，
        // 故显式取 timer() 并先断言它真存在（同 C1 对 FunctionCounter 的处置）。
        Timer wait = registry.find(MallLockMetrics.WAIT).tags("lock", LOCK).timer();
        assertThat(wait).as("必须真的注册成 Timer，否则下面的断言是假绿").isNotNull();
        assertThat(wait.count()).as("4 次尝试里只有 2 次真碰了网络").isEqualTo(2L);
        assertThat(wait.max(TimeUnit.MILLISECONDS))
                .as("MAX 回答「争用最坏等了多久」—— 取的是 timeout 那次 5000ms").isEqualTo(5000.0);

        // 块 7 · 阶段 3a.3 新增：耗时（纳秒入参 → 微秒落进事件）与发起者身份。
        // ⚠️ DEGRADED 那一路传的是 0L，但落进事件的是 **null**（「压根没尝试」不是「等了 0 秒」）
        List<MallLockEvent> events = metrics.recentEvents();
        assertThat(events.get(0).durationUs()).as("纳秒→微秒：3000000ns→3000µs").isEqualTo(3_000L);
        assertThat(events.get(2).durationUs()).as("DEGRADED 没尝试 ⇒ null（0L 落成 null，不是 0）").isNull();
        assertThat(events.get(0).actorId()).as("无 MDC ⇒ actorId 为 null").isNull();
        assertThat(events.get(0).actorRole()).as("无 MDC ⇒ actorRole 为 null").isNull();
        // 块 7 · 阶段 3a.3 后续新增：失败/降级档的人话原因；acquired 为 null
        assertThat(events).extracting(MallLockEvent::reason)
                .containsExactly(null,
                        "取锁超时：等待 5000 ms 未获得",
                        "锁客户端不可用：降级冷却期内跳过取锁",
                        "取锁异常：IllegalStateException：db down");
    }

    @Test
    @DisplayName("业务键只进事件流、绝不进 tag（基数铁律）：两次不同 bizKey 的尝试共用一个时间序列")
    void businessKeyNeverBecomesATag() {
        metrics.acquire(LOCK, "ORDER-1", Outcome.ACQUIRED, 0L, null);
        metrics.acquire(LOCK, "ORDER-2", Outcome.ACQUIRED, 0L, null);

        assertThat(count(MallLockMetrics.ACQUIRES, "lock", LOCK, "result", "acquired"))
                .as("两个不同的业务键落在同一条计数上").isEqualTo(2.0);
        assertThat(registry.find(MallLockMetrics.ACQUIRES).counters())
                .as("该指标下的时间序列数 = 1（若 key 进了 tag 就会变成 2，基数无界）").hasSize(1);
        assertThat(metrics.recentEvents()).extracting(MallLockEvent::key)
                .as("但事件流里必须能看出是哪一单").containsExactly("ORDER-1", "ORDER-2");
    }

    @Test
    @DisplayName("waited 只数「真排过队」的取锁（墙钟 ≥ 5ms）：超时必算、亚毫秒与 degraded/error 不算")
    void waitedCountsOnlyContendedAcquires() {
        metrics.acquire(LOCK, BIZ, Outcome.ACQUIRED, TimeUnit.MILLISECONDS.toNanos(1), null);
        metrics.acquire(LOCK, BIZ, Outcome.ACQUIRED, TimeUnit.MILLISECONDS.toNanos(60), null);
        metrics.acquire(LOCK, BIZ, Outcome.TIMEOUT, TimeUnit.MILLISECONDS.toNanos(5000),
                "取锁超时：等待 5000 ms 未获得");
        metrics.acquire(LOCK, BIZ, Outcome.DEGRADED, 0L, "锁客户端不可用：降级冷却期内跳过取锁");
        metrics.acquire(LOCK, BIZ, Outcome.ERROR, TimeUnit.MILLISECONDS.toNanos(900),
                "取锁异常：IllegalStateException：db down");

        assertThat(count(MallLockMetrics.WAITED, "lock", LOCK))
                .as("60ms + 5000ms 两次；1ms 是空闲抖动、degraded 没碰网络、error 不计入等待")
                .isEqualTo(2.0);
    }

    // ================= holds =================

    @Test
    @DisplayName("holds 三路：released / lease_lost / error，都带业务键")
    void holdOutcomesAreRecorded() {
        metrics.hold(LOCK, BIZ, Hold.RELEASED, null);
        metrics.hold(LOCK, BIZ, Hold.LEASE_LOST, "租约已丢失：临界区可能与他人重叠");
        metrics.hold(LOCK, BIZ, Hold.ERROR, "释放锁失败：IllegalStateException：db down");

        assertThat(count(MallLockMetrics.HOLDS, "lock", LOCK, "result", "released")).isEqualTo(1.0);
        assertThat(count(MallLockMetrics.HOLDS, "lock", LOCK, "result", "lease_lost")).isEqualTo(1.0);
        assertThat(count(MallLockMetrics.HOLDS, "lock", LOCK, "result", "error")).isEqualTo(1.0);
        assertThat(metrics.recentEvents()).extracting(MallLockEvent::kind).containsOnly("hold");
        assertThat(metrics.recentEvents()).extracting(MallLockEvent::reason)
                .containsExactly(null, "租约已丢失：临界区可能与他人重叠",
                        "释放锁失败：IllegalStateException：db down");
    }

    // ================= up =================

    @Test
    @DisplayName("lock.up 是活值：scrape 时才调用取值函数（不是绑定时快照）")
    void upReadsTheSupplierLive() {
        AtomicBoolean usable = new AtomicBoolean(true);
        metrics.bindUp(usable::get);

        assertThat(up()).isEqualTo(1.0);
        usable.set(false);
        assertThat(up()).as("取值函数被调用 ⇒ 不是绑定时快照").isZero();
        usable.set(true);
        assertThat(up()).as("双向都能反映").isEqualTo(1.0);
    }

    // ================= 事件流（第二通道）=================

    @Test
    @DisplayName("事件流：写满即覆盖最旧、seq 单调；两条通道在同一调用点一起发")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        for (int i = 0; i < capacity + 88; i++) {
            metrics.acquire(LOCK, BIZ, Outcome.ACQUIRED, 1_000_000L, null);
        }

        List<MallLockEvent> events = metrics.recentEvents();
        assertThat(events).as("容量有界：只留最近 %d 条", capacity).hasSize(capacity);
        assertThat(events.get(0).seq()).as("最旧的一条是第 88 个").isEqualTo(88L);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + 87L);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.lock()).isEqualTo(LOCK);
            assertThat(e.kind()).isEqualTo("acquire");
            assertThat(e.result()).isEqualTo("acquired");
            assertThat(e.key()).isEqualTo(BIZ);
            assertThat(e.epochMilli()).isPositive();
        });
        assertThat(count(MallLockMetrics.ACQUIRES, "lock", LOCK, "result", "acquired"))
                .as("计数通道与事件通道同源：同一次调用点两边都记").isEqualTo((double) (capacity + 88));
    }

    @Test
    @DisplayName("transition 只发事件、不发计数器（翻转次数由 up 状态通道 + 事件流共同表达，规范第 6 条）")
    void transitionEmitsEventWithoutItsOwnCounter() {
        metrics.transition(false, "锁进入降级：Redis 不可达");
        metrics.transition(true, null);

        assertThat(metrics.recentEvents()).extracting(MallLockEvent::kind).containsExactly("transition", "transition");
        assertThat(metrics.recentEvents()).extracting(MallLockEvent::result).containsExactly("degraded", "healthy");
        assertThat(metrics.recentEvents()).extracting(MallLockEvent::reason)
                .as("降级带原因；恢复（healthy）为 null")
                .containsExactly("锁进入降级：Redis 不可达", null);
        assertThat(metrics.recentEvents()).allSatisfy(e -> {
            assertThat(e.lock()).as("可用性是全局事实（客户端全应用一份）⇒ 无 lock 维度").isNull();
            assertThat(e.key()).isNull();
        });
        assertThat(registry.find("mall.lock.transitions").counters())
                .as("不该存在翻转计数器（可派生值不重复埋点）").isEmpty();
    }

    @Test
    @DisplayName("并发追加：快照里的 seq 严格递增且不重复（写者只做数组赋值，读者靠 seq 自校验）")
    void concurrentAppendsKeepSnapshotCoherent() throws Exception {
        int threads = 4;
        int perThread = 600;   // 4 × 600 = 2400 > 容量 2048 ⇒ 必然发生覆盖
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
                            metrics.hold(LOCK, key, Hold.RELEASED, null);
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

        List<MallLockEvent> snapshot = metrics.recentEvents();
        List<Long> seqs = new ArrayList<>();
        snapshot.forEach(e -> seqs.add(e.seq()));
        Set<Long> unique = new HashSet<>(seqs);
        assertThat(seqs).as("seq 严格递增（读者按 seq 自校验后不应出现错位）").isSorted();
        assertThat(unique).as("同一条事件不该在快照里出现两次").hasSameSizeAs(seqs);
        assertThat(snapshot).as("容量有界").hasSizeLessThanOrEqualTo(2048);
    }

    // ================= 工具 =================

    /** 计数器的当前值；没注册过该 (name, tags) 组合时按 0 —— 即「这条时间序列不存在」。 */
    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double up() {
        return registry.find(MallLockMetrics.UP).gauge().value();
    }
}
