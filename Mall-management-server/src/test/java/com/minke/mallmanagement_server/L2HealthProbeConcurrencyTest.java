package com.minke.mallmanagement_server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
 * ⚠️ 三个测试**都可能**抓住「非原子占位」（CAS → set），但**都不保证次次抓得到**：
 * 2026-09-29 突变三次 = 高竞争那个红 2 次、2s 窗口那两个各红 1 次，计数一律 = 2（多探 1 次）。
 * 故突变对照不能只看一次、也不能只看一个测试。证据写在那个测试的注释里。
 */
class L2HealthProbeConcurrencyTest {

    /**
     * 冷却窗口取 2s：远长于一次并发突发，避免「突发慢了几百毫秒 → 窗口在断言中途到期」
     * 造成的偶发失败（这类 flaky 比慢几秒贵得多）。
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(2);
    private static final int CONCURRENCY = 20;

    /**
     * 高竞争版用的短窗口。**不能取 1ms**：它的长度必须远大于「一次并发突发的墙钟跨度」，
     * 否则突发会跨过多个**合法**的新窗口（探测失败即把窗口重设为 now + cooldown），
     * 计数 > 1 与 CAS 是否原子无关，测试自己变成噪声源（详见
     * {@link #everyWindowHasExactlyOneProber()}）。
     * 实测跨度 max 0.62ms（2026-09-29，本机 200 轮）⇒ 取 20ms，满足「跨度 × 3 < 窗口」。
     */
    private static final Duration SHORT_COOLDOWN = Duration.ofMillis(20);

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
     * 上面那个测试**照样全绿**。故只能靠本测试的轮数买机会。
     *
     * 【本测试自己的前提：一次突发的墙钟跨度 ≪ 窗口长度】CAS 保证的是「**同一个**窗口内
     * 恰好 1 个探测者」；「整段突发里恰好 1 次探测」只在前者成立**且**突发不跨窗口时才等价。
     * 而跨窗口是**合法**的：探测失败即 `markFailed`，把窗口从失败那一刻重设为 now + cooldown
     * （`L2HealthProbe:148-149`，设计如此），故任何在重设后才读到截止时间的线程，看到的是一个
     * 刚过期的**新窗口**，它 CAS 并探测完全合规。
     * ⚠️ 2026-09-29 前的写法（每轮**当场新建**线程池 + 1ms 窗口 + `sleep(2)`）违反此前提：
     * 未被 OS 启动的线程要数毫秒后才读截止时间 ⇒ 突发跨过多个窗口 ⇒ 计数 2~3 与 CAS 无关。
     * 这就是 CI 首跑第 0 个窗口红（计数 2）、本机 4/4 红（计数 2~3）的原因。
     * 现写法：线程池与 20 个线程**建在循环外一次**、用 `CyclicBarrier` 放行（不再有「线程还没
     * 启动」那一段）、循环前**预热一轮**；实测跨度 max 0.62ms（本机 200 轮），窗口取 20ms。
     *
     * 【突变实测（证明本测试真的有牙齿）】
     *   - 完全不占位（窗口到期就探，不写任何标记）→ 200/200 轮不合格，单轮最多 20 次探测
     *     （2026-09-28，**旧写法**下测）
     *   - 占位但非原子（CAS → set）        → 2026-09-29 新写法下跑 3 次，本测试红 2 次：
     *     分别在第 **7** / 第 **93** 个窗口，计数均 = 2（旧写法下 37/200 轮不合格、单轮最多 3 次）
     *   - 现行实现（CAS 占位）             → 0/200 轮不合格；新写法下 5 次全绿（2026-09-29）
     * ⚠️ 第 2 行那 3 次里，2s 窗口的两个测试各红 1 次（其中一次与本测试同时红）⇒ 三个测试都抓得住
     * 「不是 1」，但**没有一个是次次都抓**。第一行说明本测试也守得住「完全不占位」那一侧；
     * 第三行是它现在守的不变量。
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
        L2HealthProbe probe = new L2HealthProbe(factory, SHORT_COOLDOWN);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENCY + 1);
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                pool.execute(() -> {
                    try {
                        while (true) {
                            barrier.await();          // 停在这里等放行
                            probe.isL2UsableNow();
                            barrier.await();          // 报告：本线程已读完
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        // 屏障被打破（收尾时）：退出
                    }
                });
            }

            // 预热一轮（丢弃）：让 20 个线程全部启动并停到屏障上 ——
            // 线程启动开销只发生在这里，不进第一个被断言的窗口。
            barrier.await(30, TimeUnit.SECONDS);
            barrier.await(30, TimeUnit.SECONDS);

            int rounds = 200;
            for (int round = 0; round < rounds; round++) {
                probe.markFailed(new RedisConnectionFailureException("模拟 Redis 不可达"));
                Thread.sleep(SHORT_COOLDOWN.toMillis() + 3);   // 等窗口到期（多等 3ms，跨度 ≪ 20ms）

                int before = pingCount.get();
                barrier.await(30, TimeUnit.SECONDS);   // 放行 20 个线程
                barrier.await(30, TimeUnit.SECONDS);   // 全部读完
                assertThat(pingCount.get() - before)
                        .as("第 %d 个窗口：20 并发应恰好 1 次探测", round)
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
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
