package com.minke.mallmanagement_service;

import com.minke.mallmanagement_server.MallLockMetrics;
import com.minke.mallmanagement_server.MallLockMetrics.MallLockEvent;
import com.minke.mallmanagement_service.DistributedLockService.LockName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DistributedLockService} 的机制实测 —— 四路结果、冷却、恢复、释放，全用假实现驱动。
 *
 * <h2>【为什么可以不起 Spring、不起 Redis】</h2>
 * 本类测的是**状态机**（哪种情形记哪一路、冷却有没有真的省掉网络、恢复有没有被判到），不是
 * Redisson 的分布式语义。客户端建连是本类里唯一必须真连 Redis 的动作，它被单独拎成
 * {@link DistributedLockService#createClient} 这个接缝 ⇒ 这里用假实现覆盖它。
 * 「锁在真 Redis 上真的互斥」由 A3（A-S2a-1 翻正后的断言）与 {@code LockSceneRedisTest} 守，
 * 不在本文件的射程内。
 *
 * <p>⚠️ 指标名在本文件里写**字面量**而不是引用 {@code MallLockMetrics.ACQUIRES}（那些常量是
 * 包级私有，本类在另一个包）：这是**故意的** —— 字面量钉的是外部契约（{@code /actuator/metrics}
 * 暴露给运维与 curl 的那个名字），改动指标名必须让本文件一起红。
 */
class DistributedLockServiceTest {

    private static final String LOCK = "order-add";
    private static final String BIZ = "ORDER-1";

    /** 取锁参数故意取小：本文件不起真客户端，它们只用来给 mock 打桩。 */
    private static final Duration WAIT = Duration.ofMillis(300);
    private static final Duration LEASE = Duration.ofSeconds(3);
    /** 故意取小：恢复/到期那几个用例要等窗口过去，窗口越长测试越慢。 */
    private static final Duration COOLDOWN = Duration.ofMillis(50);

    /** 每个用例一份：计数天然隔离（JUnit 每方法新建测试对象）。 */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallLockMetrics metrics = new MallLockMetrics(registry);

    // ================= 四路结果 =================

    @Test
    @DisplayName("拿到锁：action 在锁下执行、返回值原样透传、finally 释放；顺带钉住文档里的 key 格式")
    void acquiredPathRunsActionAndReleases() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(WAIT.toMillis(), LEASE.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonClient client = clientReturning(lock);

        String got = service(c -> client).runLocked(LockName.ORDER_ADD, BIZ, () -> "action-ran");

        assertThat(got).as("action 的返回值原样透传（Controller 的响应文案零变化）").isEqualTo("action-ran");
        assertThat(DistributedLockService.keyOf(LockName.ORDER_ADD, BIZ))
                .as("key 格式的唯一出处：mall:lock:<逻辑名>:<业务键>")
                .isEqualTo("mall:lock:order-add:ORDER-1");
        verify(client).getLock("mall:lock:order-add:ORDER-1");
        verify(lock).unlock();
        assertThat(count("mall.lock.acquires", "lock", LOCK, "result", "acquired")).isEqualTo(1.0);
        assertThat(count("mall.lock.holds", "lock", LOCK, "result", "released")).isEqualTo(1.0);
        assertThat(timerCount()).as("真尝试过 ⇒ 记了耗时（等没等、等多久）。").isEqualTo(1L);
        assertThat(kinds()).containsExactly("acquire", "hold");
    }

    @Test
    @DisplayName("争用超时 ⇒ fail-open 直行（action 照跑、不碰锁），但**耗时照记**")
    void timeoutFallsOpenWithoutTouchingTheLock() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(WAIT.toMillis(), LEASE.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThat(service(c -> clientReturning(lock)).runLocked(LockName.ORDER_ADD, BIZ, () -> "action-ran"))
                .as("争用超时与 Redis 不可达同级：都放行（对 orderAdd 的推演见 runLocked 的 javadoc）")
                .isEqualTo("action-ran");

        verify(lock, never()).unlock();
        verify(lock, never()).isHeldByCurrentThread();
        assertThat(count("mall.lock.acquires", "lock", LOCK, "result", "timeout")).isEqualTo(1.0);
        assertThat(timerCount()).as("超时也记耗时：MAX 要回答「争用最坏等了多久」").isEqualTo(1L);
        assertThat(registry.find("mall.lock.holds").counters())
                .as("没持有过 ⇒ 不该有任何 holds 时间序列").isEmpty();
    }

    @Test
    @DisplayName("取锁抛异常 ⇒ fail-open + 进冷却；冷却窗口内的下一个请求**零网络代价**（先查冷却、再查已有客户端）")
    void lockFailureDegradesAndTheCooldownSkipsTheNetwork() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenThrow(new RedisException("模拟：命令失败"));
        AtomicInteger creations = new AtomicInteger();
        DistributedLockService svc = service(c -> {
            creations.incrementAndGet();
            return clientReturning(lock);
        });

        assertThat(svc.runLocked(LockName.ORDER_ADD, BIZ, () -> "a")).isEqualTo("a");
        assertThat(svc.runLocked(LockName.ORDER_ADD, BIZ, () -> "b")).as("降级放行").isEqualTo("b");

        assertThat(count("mall.lock.acquires", "lock", LOCK, "result", "error")).isEqualTo(1.0);
        assertThat(count("mall.lock.acquires", "lock", LOCK, "result", "degraded")).isEqualTo(1.0);
        // ⚠️ 这一条是本设计的守门断言：客户端已经建好了，若 obtain() 先 return 已有客户端、
        //    再判冷却，第二次请求会**再撞一次网络**（白等 connectTimeout）—— 冷却形同虚设。
        verify(lock, times(1)).tryLock(anyLong(), anyLong(), any());
        assertThat(creations).hasValue(1);
        assertThat(up()).as("降级中：0 的含义是「现在不依赖锁」，不是「Redis 已死」").isZero();
    }

    @Test
    @DisplayName("建客户端失败 ⇒ 降级；冷却窗口内 N 个请求只尝试建连 **1** 次")
    void clientCreationFailureTripsTheCooldownOnce() {
        AtomicInteger attempts = new AtomicInteger();
        DistributedLockService svc = service(c -> {
            attempts.incrementAndGet();
            throw new RedisException("模拟：连不上");
        });

        assertThat(svc.runLocked(LockName.FRIEND_ADD, "A:B", () -> "x")).isEqualTo("x");
        assertThat(svc.runLocked(LockName.FRIEND_ADD, "A:B", () -> "y")).isEqualTo("y");
        assertThat(svc.runLocked(LockName.FRIEND_ADD, "A:B", () -> "z")).isEqualTo("z");

        assertThat(count("mall.lock.acquires", "lock", "friend-add", "result", "degraded")).isEqualTo(3.0);
        assertThat(attempts).as("三次请求只有第一次真去建连").hasValue(1);
        assertThat(up()).isZero();
    }

    // ================= 冷启动突发 =================

    @Test
    @DisplayName("冷启动首个并发突发：非创建者**等待**而不是判降级 —— 这是 A-S2a-1 的前提，不是指标好看的问题")
    void concurrentFirstBurstWaitsForTheCreatorInsteadOfDegrading() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(WAIT.toMillis(), LEASE.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonClient client = clientReturning(lock);
        AtomicInteger creations = new AtomicInteger();
        // 建连故意慢 100ms：确保另外 3 个线程一定撞在「正在建」的窗口里
        DistributedLockService svc = service(c -> {
            creations.incrementAndGet();
            sleep(100);
            return client;
        });

        int threads = 4;
        AtomicInteger actions = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        svc.runLocked(LockName.ORDER_ADD, BIZ, actions::incrementAndGet);
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

        assertThat(actions).as("4 个请求都执行了 action").hasValue(threads);
        assertThat(count("mall.lock.acquires", "lock", LOCK, "result", "acquired"))
                .as("全部走的是「拿到锁」分支（不是降级直行）").isEqualTo(4.0);
        assertThat(registry.find("mall.lock.acquires").tags("lock", LOCK, "result", "degraded").counter())
                .as("⚠️ 本设计最值钱的一条：若非创建者直接判降级，冷启动突发会**绕过互斥**"
                        + "（20 并发下单仍可能插 2 行），而不只是指标变难看")
                .isNull();
        assertThat(creations).as("创建权单飞：只建一次").hasValue(1);
        assertThat(up()).isEqualTo(1.0);
    }

    // ================= 释放 =================

    @Test
    @DisplayName("临界区跑赢租期：isHeldByCurrentThread() 为假 ⇒ 记 lease_lost 且**绝不 unlock**（无脑 unlock 会释放别人的锁）")
    void leaseLostIsRecordedAndNeverUnlocked() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(WAIT.toMillis(), LEASE.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        assertThat(service(c -> clientReturning(lock)).runLocked(LockName.ORDER_PAY, BIZ, () -> "ok")).isEqualTo("ok");

        verify(lock, never()).unlock();
        assertThat(count("mall.lock.holds", "lock", "order-pay", "result", "lease_lost")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("action 抛异常：异常原样上抛，但锁**仍然被释放**（finally 在 lambda 之外）")
    void actionExceptionPropagatesButTheLockIsStillReleased() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(WAIT.toMillis(), LEASE.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Supplier<String> boom = () -> {
            throw new IllegalStateException("模拟：action 内部炸了");
        };

        assertThatThrownBy(() -> service(c -> clientReturning(lock)).runLocked(LockName.ORDER_ADD, BIZ, boom))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟：action 内部炸了");

        verify(lock).unlock();
        assertThat(count("mall.lock.holds", "lock", LOCK, "result", "released")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("取锁时被中断：action 照跑（fail-open），但中断位被恢复 —— 不把中断语义一起吞掉")
    void interruptFallsOpenAndRestoresTheFlag() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenThrow(new InterruptedException("模拟：取锁时被中断"));

        assertThat(service(c -> clientReturning(lock)).runLocked(LockName.ORDER_ADD, BIZ, () -> "ran")).isEqualTo("ran");
        assertThat(Thread.currentThread().isInterrupted()).as("中断位必须被恢复").isTrue();
        Thread.interrupted();   // 清掉，别污染后续用例与 JUnit 自己
        assertThat(count("mall.lock.acquires", "lock", LOCK, "result", "error")).isEqualTo(1.0);
        assertThat(up()).as("中断不是「Redis 不可用」的证据 ⇒ 不该进冷却").isEqualTo(1.0);
    }

    // ================= 恢复 =================

    @Test
    @DisplayName("恢复由一次**命令成功往返**判定：两段故障各记一次真实翻转（若恢复只认「新建客户端」，第二次故障不会再有 WARN/事件）")
    void recoveryIsRecognizedByASuccessfulRoundTrip() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenThrow(new RedisException("第一段故障"));
        DistributedLockService svc = service(c -> clientReturning(lock));

        svc.runLocked(LockName.ORDER_ADD, BIZ, () -> "a");
        assertThat(up()).isZero();
        assertThat(transitions()).containsExactly("degraded");

        sleepPastCooldown();
        // ⚠️ 重打桩必须用 doReturn：此刻 mock 上已有「抛异常」的 stub，而 when(mock.method(...)) 会
        // **真的调用** mock ⇒ 旧 stub 抛出的 RedisException 会逃出 when(...) 本身，测试在打桩行就红。
        // doReturn 不调用 mock，直接覆盖（后打的桩胜出）。
        doReturn(true).when(lock).tryLock(WAIT.toMillis(), LEASE.toMillis(), TimeUnit.MILLISECONDS);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        svc.runLocked(LockName.ORDER_ADD, BIZ, () -> "b");    // ← 这次命令成功往返
        assertThat(up()).as("恢复后 up 回到 1").isEqualTo(1.0);

        doThrow(new RedisException("第二段故障")).when(lock).tryLock(anyLong(), anyLong(), any());
        svc.runLocked(LockName.ORDER_ADD, BIZ, () -> "c");
        assertThat(transitions()).as("两段故障各记一次翻转").containsExactly("degraded", "healthy", "degraded");
    }

    // ================= 装配与关闭 =================

    @Test
    @DisplayName("Redisson 配置从 RedisProperties 派生（同源，不重抄字面量）+ 四项超时被显式调小")
    void clientConfigIsDerivedFromRedisProperties() {
        RedisProperties props = new RedisProperties();
        props.setHost("redis.internal");
        props.setPort(6380);
        props.setDatabase(15);
        props.setPassword("s3cret");
        AtomicReference<Config> seen = new AtomicReference<>();

        service(c -> {
            seen.set(c);
            return clientReturning(mock(RLock.class));
        }, props).runLocked(LockName.ORDER_ADD, BIZ, () -> "x");

        SingleServerConfig server = seen.get().useSingleServer();
        assertThat(server.getAddress()).isEqualTo("redis://redis.internal:6380");
        assertThat(server.getDatabase()).isEqualTo(15);
        assertThat(server.getPassword()).isEqualTo("s3cret");
        // Redisson 的默认值对本项目而言过大：不可达端点上一个请求会挂十几秒（见 D2 的取舍）
        assertThat(server.getConnectTimeout()).isEqualTo(1000);
        assertThat(server.getTimeout()).isEqualTo(1000);
        assertThat(server.getRetryAttempts()).isEqualTo(1);
        assertThat(seen.get().getNettyThreads()).isEqualTo(4);
        assertThat(seen.get().getCodec().getClass().getSimpleName())
                .as("StringCodec：否则 Redis 里的 key 是二进制（文档说的明文 key 就不成立）")
                .isEqualTo("StringCodec");
    }

    @Test
    @DisplayName("destroy()：建过就 shutdown，没建过是 no-op（Redisson 的 Netty 线程非 daemon，不关会拖住 JVM 退出）")
    void destroyShutsDownOnlyWhenAClientWasCreated() {
        RedissonClient client = clientReturning(mock(RLock.class));
        DistributedLockService created = service(c -> client);
        created.runLocked(LockName.ORDER_ADD, BIZ, () -> "x");
        created.destroy();
        verify(client).shutdown();

        DistributedLockService neverCreated = service(c -> client);
        neverCreated.destroy();
        verify(client, times(1)).shutdown();   // 仍然只有上面那一次
    }

    @Test
    @DisplayName("friendAdd 的**无序对**键：A→B 与 B→A 是同一把锁（A3 只打一个方向，测不出这个洞）")
    void pairKeyIsSymmetric() {
        assertThat(DistributedLockService.pairKey("B", "A"))
                .as("反序必须落到同一个键上")
                .isEqualTo(DistributedLockService.pairKey("A", "B"))
                .isEqualTo("A:B");
        assertThat(DistributedLockService.keyOf(LockName.FRIEND_ADD, DistributedLockService.pairKey("B", "A")))
                .as("两个方向的完整 key 也必须一致")
                .isEqualTo(DistributedLockService.keyOf(LockName.FRIEND_ADD, DistributedLockService.pairKey("A", "B")));
    }

    // ================= 工具 =================

    /**
     * 建一个把「建客户端」换成假实现的 service —— 这是本文件能不带 Redis 跑起来的全部原因。
     * 用匿名子类而不是改生产代码的可见性：接缝只有 {@code createClient} 一个。
     */
    private DistributedLockService service(Function<Config, RedissonClient> factory) {
        return service(factory, new RedisProperties());
    }

    private DistributedLockService service(Function<Config, RedissonClient> factory, RedisProperties props) {
        return new DistributedLockService(metrics, props, WAIT, LEASE, COOLDOWN) {
            @Override
            protected RedissonClient createClient(Config config) {
                return factory.apply(config);
            }
        };
    }

    private static RedissonClient clientReturning(RLock lock) {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getLock(anyString())).thenReturn(lock);
        return client;
    }

    /** 计数器的当前值；没注册过该 (name, tags) 组合时按 0 —— 即「这条时间序列不存在」。 */
    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long timerCount() {
        var timer = registry.find("mall.lock.wait.duration").tags("lock", LOCK).timer();
        return timer == null ? 0L : timer.count();
    }

    private double up() {
        return registry.find("mall.lock.up").gauge().value();
    }

    private List<String> kinds() {
        return metrics.recentEvents().stream().map(MallLockEvent::kind).toList();
    }

    private List<String> transitions() {
        return metrics.recentEvents().stream()
                .filter(e -> "transition".equals(e.kind()))
                .map(MallLockEvent::result)
                .toList();
    }

    /** 等窗口到期。纯等超时，不是并发赛跑 —— 多等 70ms 换掉 flaky（同 C1 的 sleepPastCooldown）。 */
    private static void sleepPastCooldown() throws InterruptedException {
        Thread.sleep(COOLDOWN.toMillis() + 70);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
