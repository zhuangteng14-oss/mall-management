package com.minke.mallmanagement_service;

import com.minke.mallmanagement_server.MallLockMetrics;
import com.minke.mallmanagement_service.DistributedLockService.LockName;
import com.minke.mallmanagement_service.LockSceneService.Observation;
import com.minke.mallmanagement_service.LockSceneService.Params;
import com.minke.mallmanagement_service.LockSceneService.SceneKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LockSceneService} 的机制实测 —— 计数、完备性断言、线程收尾，**不连 Redis**。
 *
 * <p>为什么要有这一份（门控的 {@code LockSceneRedisTest} 不是已经覆盖了吗）：那一份在 CI 的
 * backend job 里**不跑**（挂 {@code A3_ENABLED}），而场景的计数逻辑本身跟 Redis 毫无关系 ——
 * 用 mock 的 {@code RLock} 就能把「数得对不对」钉死在快速 job 里。真 Redis 那一份负责的是
 * 「争用真的会发生」，两份的分工写在 {@code LockSceneRedisTest} 的类注释里。
 *
 * <p>⚠️ 本文件验不到（也说不了）的事：mock 不会真的阻塞，故 {@code TIMEOUT} 场景在这里
 * 只验「计数与 fail-open 路径」，**验不到**「持锁者真的把别人挡在门外」。
 */
class LockSceneServiceTest {

    private static final Duration WAIT = Duration.ofMillis(300);
    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final Duration COOLDOWN = Duration.ofMillis(50);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallLockMetrics metrics = new MallLockMetrics(registry);

    static {
        // ⚠️ 预热 Mockito，**必须**，且是实测逼出来的：RLock/RedissonClient 的**首次** mock 创建要跑
        // ByteBuddy + 类加载，在这台机器上实测 > 2.7s（冷 JVM 下更久）。而创建发生在
        // DistributedLockService.createClient 内部、即被计时的区间里 —— 一旦超过
        // CREATOR_WAIT_MILLIS(2700ms)，非创建者就会按「创建者没建成」判定**降级**，
        // 场景于是给出「1 acquired + 7 degraded」这种**与锁行为无关**的分布（2026-09-29 实测踩到）。
        // 修法与本仓 L2HealthProbe 的并发测试一致：把冷启动代价挪到计时之外。
        clientWith(true);
    }

    // ================= 降级（Redis 不可达）=================

    @Test
    @DisplayName("Redis 不可达：四路全部落在 degraded，且两条通道（事件流 / 计数器）给出同一个数")
    void allPathsDegradeWhenRedisIsUnreachable() {
        LockSceneService scene = scene(config -> {
            throw new RedisException("模拟：Redis 不可达");
        });

        Observation observation = scene.run(SceneKind.WAIT, "scene-degraded", Params.of(8, Duration.ZERO));

        assertThat(observation.acquired()).isZero();
        assertThat(observation.timedOut()).isZero();
        assertThat(observation.errored()).as("建客户端失败被吞掉 ⇒ 走的是 degraded 而非 error").isZero();
        assertThat(observation.degraded()).isEqualTo(8);
        assertThat(observation.total()).as("完备性：与预期取锁次数相等").isEqualTo(8);
        assertThat(observation.maxWaitNanos()).as("降级根本没碰网络 ⇒ 等待约 0").isLessThan(WAIT.toNanos());
        assertThat(observation.anyThreadLeaked()).isFalse();

        // 交叉验算：事件流数出来的数，必须等于 Micrometer 那条时间序列上的数。
        // ⚠️ 这里用字面量是**故意的**：指标名是对外契约，测试按契约查（同 MallLockMetricsTest 的取字面量）。
        assertThat(registry.find("mall.lock.acquires")
                .tags("lock", LockName.ORDER_ADD.tag(), "result", "degraded")
                .counter().count()).isEqualTo(8.0);
        assertThat(registry.find("mall.lock.wait.duration").tags("lock", LockName.ORDER_ADD.tag()).timer())
                .as("degraded 完全不记 Timer（没碰网络；记 0 会把「没尝试」混进「等了 0 秒」）⇒ 该时间序列根本不存在")
                .isNull();
    }

    // ================= 拿到锁（争用不发生）=================

    @Test
    @DisplayName("WAIT：全部拿到（threads 条），而不是 2×threads —— 每个任务只取一次锁")
    void waitSceneAcquiresEverything() {
        LockSceneService scene = scene(config -> permissiveClient());

        Observation observation = scene.run(SceneKind.WAIT, "scene-wait", Params.of(8, Duration.ZERO));

        assertThat(observation.acquired()).isEqualTo(8);
        assertThat(observation.total()).isEqualTo(8);
        assertThat(observation.maxWaitNanos()).as("mock 不阻塞 ⇒ 等待远小于 wait").isLessThan(WAIT.toNanos());
        assertThat(observation.anyThreadLeaked()).isFalse();
    }

    @Test
    @DisplayName("CONFLICT：与 WAIT 同一执行路径，区别只在临界区长度（判据不同，不是机制不同）")
    void conflictSceneIsTheSameMechanismAsWait() {
        LockSceneService scene = scene(config -> permissiveClient());

        Observation observation = scene.run(SceneKind.CONFLICT, "scene-conflict",
                Params.of(4, Duration.ofMillis(10)));

        assertThat(observation.acquired()).as("临界区长 10ms 也不该丢锁（mock 恒成功）").isEqualTo(4);
        assertThat(observation.total()).isEqualTo(4);
    }

    // ================= 超时 =================

    @Test
    @DisplayName("TIMEOUT：预期取锁次数是 threads + 1（多一个持锁者），全部走 fail-open（action 照跑）")
    void timeoutSceneCountsTheHolderAsAnExtraAcquisition() {
        LockSceneService scene = scene(config -> refusingClient());

        Observation observation = scene.run(SceneKind.TIMEOUT, "scene-timeout",
                Params.of(4, Duration.ofMillis(1)));

        assertThat(observation.acquired()).isZero();
        assertThat(observation.timedOut()).as("4 个争用者 + 1 个持锁者，都没拿到").isEqualTo(5);
        assertThat(observation.total()).as("基准是「预期取锁次数」= threads + 1，不是任务数").isEqualTo(5);
        assertThat(observation.anyThreadLeaked()).as("超时走 fail-open 直行 ⇒ 不该有线程卡住").isFalse();
    }

    // ================= 死锁形状 =================

    @Test
    @DisplayName("DEADLOCK：每个任务在临界区内再取一次 ⇒ 2×threads 条 acquire，且线程全部收尾")
    void deadlockScenePerformsTwoAcquisitionsPerTask() {
        LockSceneService scene = scene(config -> permissiveClient());

        Observation observation = scene.run(SceneKind.DEADLOCK, "scene-deadlock", Params.of(2, Duration.ZERO));

        assertThat(observation.acquired()).as("外层 + 内层各一次（mock 不互相排他，故内层也成功）").isEqualTo(4);
        assertThat(observation.total()).as("DEADLOCK 的基准是 2×threads").isEqualTo(4);
        assertThat(observation.anyThreadLeaked()).isFalse();
    }

    // ================= 计数的边界（key 与游标过滤）=================

    @Test
    @DisplayName("计数只认自己那把 key：连跑两次不同 key 各数 8 条；同一个 key 复用也不会把上一次的数进来")
    void countingIsScopedToTheRunsOwnKey() {
        LockSceneService scene = scene(config -> permissiveClient());

        Observation first = scene.run(SceneKind.WAIT, "scene-a", Params.of(8, Duration.ZERO));
        assertThat(first.acquired()).as("第一次的完整分布=%s", first).isEqualTo(8);
        assertThat(scene.run(SceneKind.WAIT, "scene-b", Params.of(8, Duration.ZERO)).acquired())
                .as("换了 key 后只数自己的 8 条（若按 key 过滤失效，这里会是 16）").isEqualTo(8);
        assertThat(scene.run(SceneKind.WAIT, "scene-a", Params.of(8, Duration.ZERO)).acquired())
                .as("复用同一个 key 时靠 seq 游标挡住上一次的残留（否则会是 16）").isEqualTo(8);
    }

    // ================= 参数校验 =================

    @Test
    @DisplayName("参数校验：threads 有上界（事件环容量决定的）、holdMillis 不得为负、DEADLOCK 必须恰好 2 个线程")
    void paramsAreValidated() {
        LockSceneService scene = scene(config -> permissiveClient());

        assertThatThrownBy(() -> scene.run(SceneKind.WAIT, "k", new Params(0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.run(SceneKind.WAIT, "k", new Params(LockSceneService.MAX_THREADS + 1, 0)))
                .as("上界不是性能考虑，是 512 槽事件环的安全边界").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.run(SceneKind.WAIT, "k", new Params(1, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.run(SceneKind.DEADLOCK, "k", Params.of(3, Duration.ZERO)))
                .as("死锁形状只能两方：线程多了「谁在等谁」不再确定，判据会碰运气")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ================= 工具 =================

    private LockSceneService scene(Function<Config, RedissonClient> factory) {
        // 复用 DistributedLockServiceTest 的接缝：只有 createClient 被替换，冷却/翻转/释放全走生产代码。
        DistributedLockService locks =
                new DistributedLockService(metrics, new RedisProperties(), WAIT, LEASE, COOLDOWN) {
                    @Override
                    protected RedissonClient createClient(Config config) {
                        return factory.apply(config);
                    }
                };
        return new LockSceneService(locks, metrics);
    }

    /** 一把「永远给」的锁：tryLock 恒 true、释放判定恒真。 */
    private static RedissonClient permissiveClient() {
        return clientWith(true);
    }

    /** 一把「永远拒绝」的锁：tryLock 恒 false ⇒ 走 timeout 的 fail-open 分支。 */
    private static RedissonClient refusingClient() {
        return clientWith(false);
    }

    /** ⚠️ 不声明 {@code throws}：要能直接塞进 {@code Function} 的 lambda（它不允许 checked 异常）。 */
    private static RedissonClient clientWith(boolean held) {
        RLock lock = mock(RLock.class);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(held);
        } catch (InterruptedException e) {
            throw new IllegalStateException("mock 的打桩过程不会真的被中断", e);   // 够不到
        }
        when(lock.isHeldByCurrentThread()).thenReturn(held);
        RedissonClient client = mock(RedissonClient.class);
        when(client.getLock(anyString())).thenReturn(lock);
        return client;
    }
}
