package com.minke.mallmanagement_server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L2HealthProbe 的并发语义实测 —— 本仓唯一一处真正靠 CAS 成立的并发不变量。
 *
 * 【为什么要单独写这个测试】对应 cache-layer.md §11 那条「机制已写 ≠ 机制生效」：
 * 「同一时刻只有一个探测者」是整段冷却窗口设计的地基，而它靠的是
 * AtomicLong 上的一次 CAS —— 这种东西**审阅审不出来**，A3 也测不到
 * （A3 用可达的 Redis，永远进不了冷却窗口）。只能靠并发实测证伪。
 *
 * 【判据】（与设计评审给定的三条一一对应）
 * 1. 窗口到期 → 20 并发 → 探测次数 = 1（不是 0，不是 20）
 * 2. 探测失败 → 后续请求 → 全部跳过 L2（探测次数不再增长）
 * 3. 窗口再次到期 → 又 20 并发 → 又 1 次
 *
 * 【为什么用假实现而不是真起 Redis】要测的是 CAS，不是网络。真 Redis 无法精确制造
 * 「窗口刚好到期」的瞬间，也无法按断言在「不可达 / 可达」之间切换。
 * L2HealthProbe 的字段类型就是接口 RedisConnectionFactory，故这里直接给假实现。
 *
 * ⚠️ 三个测试里，只有 everyWindowHasExactlyOneProber 验得了 CAS 的**原子性** ——
 * 前两个用 2s 窗口只争抢几次，CAS 换成非原子的「先读后写」它们照样全绿（实测）。
 * 证据写在那个测试的注释里。
 */
class L2HealthProbeConcurrencyTest {

    /**
     * 冷却窗口取 2s：远长于一次并发突发，避免「突发慢了几百毫秒 → 窗口在断言中途到期」
     * 造成的偶发失败（这类 flaky 比慢几秒贵得多）。
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(2);
    private static final int CONCURRENCY = 20;

    private final AtomicInteger pingCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        pingCount.set(0);
    }

    @Test
    @DisplayName("窗口到期 → 20 并发恰好 1 次探测；探测失败 → 重设窗口；再次到期 → 又恰好 1 次")
    void onlyOneProberPerWindow() throws Exception {
        L2HealthProbe probe = probeFailingWith(new RedisConnectionFailureException("模拟 Redis 不可达"));

        assertThat(probe.isL2UsableNow()).as("健康态：可用，且不探测").isTrue();
        assertThat(pingCount.get()).as("健康态不该有任何探测").isZero();

        // 一次真实的 L2 操作失败 → 进冷却窗口
        probe.markFailed(new RedisConnectionFailureException("模拟 Redis 不可达"));
        assertThat(probe.isL2UsableNow()).as("冷却中：不可用").isFalse();

        Thread.sleep(COOLDOWN.toMillis() + 100);

        burst(probe);
        assertThat(pingCount.get()).as("窗口到期后 20 并发应恰好探测 1 次").isEqualTo(1);

        int afterFirstProbe = pingCount.get();
        burst(probe);
        assertThat(pingCount.get()).as("探测失败即重设窗口：后续并发不再探测").isEqualTo(afterFirstProbe);

        Thread.sleep(COOLDOWN.toMillis() + 100);
        burst(probe);
        assertThat(pingCount.get()).as("窗口再次到期后又是恰好 1 次").isEqualTo(afterFirstProbe + 1);
    }

    @Test
    @DisplayName("探测成功 → 标记清零、L2 重新可用；此后并发不再探测")
    void successfulProbeClearsCooldown() throws Exception {
        L2HealthProbe probe = probePonging();

        probe.markFailed(new RedisConnectionFailureException("模拟 Redis 不可达"));
        Thread.sleep(COOLDOWN.toMillis() + 100);

        burst(probe);
        assertThat(pingCount.get()).as("并发里只有抢到探测权的那一个探了").isEqualTo(1);
        assertThat(probe.isL2UsableNow()).as("探测成功：标记清零，L2 可用").isTrue();

        burst(probe);
        assertThat(pingCount.get()).as("恢复之后不应再探测").isEqualTo(1);
        assertThat(probe.isL2UsableNow()).isTrue();
    }

    /**
     * 高竞争版：200 个窗口 × 每个窗口 20 并发（= 4000 次争抢），**每一个窗口都必须恰好 1 次探测**。
     *
     * 【为什么不能只有上面那个测试】上面那个用 2s 窗口只争抢了 3 次 —— 它其实**测不出**
     * CAS 与「先读后写」的区别：20 个线程被唤醒是串行的，第一个探测者跑完整个临界区
     * （约 1μs）时，其余线程还没被调度到，于是它们读到的已是占位后的截止时间。
     * 这不是推测，是实测：把 CAS 换成 `set(now + PROBE_INFLIGHT)`（丢掉读-改-写的原子性），
     * 上面那个测试**照样全绿**。窗口越短、轮数越多，才越有机会让两个线程读到同一个
     * 「已到期」值。本测试把窗口压到 1ms、跑 200 轮，就是要买到这种机会。
     *
     * 【突变实测（证明本测试真的有牙齿，2026-09-28）】
     *   - 完全不占位（窗口到期就探，不写任何标记）→ 200/200 轮不合格，单轮最多 20 次探测
     *   - 占位但非原子（CAS → set）        → 37/200 轮不合格，单轮最多 3 次
     *   - 现行实现（CAS 占位）             → 0/200 轮不合格，单轮恒为 1 次
     * 前两行说明本测试抓得住「不是 1」的两侧（>1 与 =20）；第三行是它现在守的不变量。
     */
    @Test
    @DisplayName("200 轮窗口 × 20 并发：每个窗口恰好 1 次探测（CAS 的原子性只有这个量级才验得出来）")
    void everyWindowHasExactlyOneProber() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenAnswer(invocation -> {
            pingCount.incrementAndGet();
            throw new RedisConnectionFailureException("模拟 Redis 不可达");
        });
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        L2HealthProbe probe = new L2HealthProbe(factory, Duration.ofMillis(1));

        int rounds = 200;
        for (int round = 0; round < rounds; round++) {
            probe.markFailed(new RedisConnectionFailureException("模拟 Redis 不可达"));
            Thread.sleep(2);   // 等 1ms 的窗口到期

            int before = pingCount.get();
            burst(probe);
            assertThat(pingCount.get() - before)
                    .as("第 %d 个窗口：20 并发应恰好 1 次探测", round)
                    .isEqualTo(1);
        }
    }

    /** 20 个线程同时来碰 L2 —— 等价于一个冷 key 上的 20 个并发请求。 */
    private void burst(L2HealthProbe probe) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        probe.isL2UsableNow();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).as("并发突发应在 10s 内结束").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 探测通道：getConnection() 给一条连接，ping() 记账后抛异常（模拟 Redis 不可达）。 */
    private L2HealthProbe probeFailingWith(RuntimeException failure) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenAnswer(invocation -> {
            pingCount.incrementAndGet();
            throw failure;
        });
        return probeOver(connection);
    }

    /** 探测通道：ping() 记账后正常返回（模拟 Redis 可达）。 */
    private L2HealthProbe probePonging() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenAnswer(invocation -> {
            pingCount.incrementAndGet();
            return "PONG";
        });
        return probeOver(connection);
    }

    private L2HealthProbe probeOver(RedisConnection connection) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        return new L2HealthProbe(factory, COOLDOWN);
    }
}
