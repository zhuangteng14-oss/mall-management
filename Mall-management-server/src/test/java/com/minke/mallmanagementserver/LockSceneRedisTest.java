package com.minke.mallmanagementserver;

import com.minke.mallmanagement_service.DistributedLockService.LockName;
import com.minke.mallmanagement_service.LockSceneService;
import com.minke.mallmanagement_service.LockSceneService.Observation;
import com.minke.mallmanagement_service.LockSceneService.Params;
import com.minke.mallmanagement_service.LockSceneService.SceneKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁专项场景在**真 Redis** 上的实测 —— {@code LockSceneService} 的四个形状端到端跑一遍。
 *
 * <p>与 {@code LockSceneServiceTest}（mock，进 backend job）的分工：那一份钉「计数与线程收尾」，
 * 本份钉「**争用真的会发生**」—— mock 不会互相排他，故「持锁者把别人挡在门外」「双方在对方的锁上
 * 各等到超时」这两件事只有真 Redis 能验。
 *
 * <h2>【门控与运行方式】</h2>
 * 与 {@code MallManagementApplicationTests} 同一条路：{@code A3_ENABLED=true} 才跑，
 * 其余情况（含 backend job 的全量 {@code mvn test}）自动跳过 —— 跳过不是失败。
 *
 * <p>用**默认 profile**（不激活 a3）：本测试要的是「Redis 能从当前环境连上」，而 a3 profile 的
 * 默认值钉死在 VM 的 {@code 192.168.229.129}。连接目标由环境变量覆盖：
 * <pre>
 *   A3_ENABLED=true \
 *   SPRING_DATA_REDIS_HOST=192.168.229.129 \
 *   SPRING_DATA_REDIS_PASSWORD=123456 \
 *   ./mvnw -B test -Dtest=LockSceneRedisTest
 * </pre>
 * ⚠️ 默认 profile 下 {@code application.properties} **不含**任何 {@code spring.data.redis.*}
 * ⇒ 不补上面两个环境变量会走 Boot 默认（localhost:6379、无口令），在有口令的 Redis 上是 NOAUTH，
 * 而 NOAUTH 的表现是**四路全 degraded**（不是报错）—— 故每个场景都显式断言
 * {@code degraded == 0}，把「环境不通」与「锁不对」分开报。
 *
 * <h2>【为什么敢断言精确的分布（而不是「大概」）】</h2>
 * 三个场景各自带防伪条件：{@code TIMEOUT} 的争用者等持锁者真进临界区后才动手；
 * {@code DEADLOCK} 有「双方都进临界区」的闸门且线程数恒为 2；{@code WAIT}/{@code CONFLICT}
 * 的负载全部落在同一把 key 上。故断言的是分布本身，不是「大于某个数」。
 *
 * <p>本类**不启动 Web 容器**（默认 {@code webEnvironment=MOCK}），也就不会与同 job 里
 * 跑着的后端抢 8088；场景用的 key 带 {@code scene-} 前缀 + 纳秒后缀，
 * 与后端进程的真实订单 key 不会撞（两者共用一个 Redis 库）。
 */
@EnabledIfEnvironmentVariable(named = "A3_ENABLED", matches = "true")
@SpringBootTest
class LockSceneRedisTest {

    /**
     * {@code app.lock.wait} 的生效值（默认 profile 的 {@code application.properties} 写死 5s，
     * a3 不覆盖它）。⚠️ 本测试**不**读属性，而是照抄这个约定值用于断言 —— 一旦运行时改了
     * {@code app.lock.wait}，这里的判据会立刻失配而红（宁可红，也不要一个静默失效的判据）。
     */
    private static final Duration APP_WAIT = Duration.ofSeconds(5);

    /** 持锁者必须持满 **大于** {@code app.lock.wait} 才可能让争用者超时（8s 留 3s 余量）。 */
    private static final Duration HOLD_BEYOND_WAIT = Duration.ofSeconds(8);

    @Autowired
    private LockSceneService lockScene;

    @Autowired
    private MeterRegistry registry;

    // ================= WAIT =================

    @Test
    @DisplayName("WAIT：8 线程同一把 key 全部拿到；同一 key 连跑两次仍全部拿到 ⇒ 每次临界区结束都真的放锁了")
    void waitSceneAcquiresEverythingAndReleases() {
        String key = uniqueKey();

        Observation first = lockScene.run(SceneKind.WAIT, key, Params.of(8, Duration.ZERO));

        assertThat(first.anyThreadLeaked()).isFalse();
        assertThat(first.degraded()).as("degraded>0 说明测试环境连不上 Redis（如 NOAUTH），不是锁的问题").isZero();
        assertThat(first.errored()).isZero();
        assertThat(first.acquired()).as("临界区是毫秒级 ⇒ 8 个线程在 5s 等待窗口内轻松跑完。观测=%s", first).isEqualTo(8);
        assertThat(first.timedOut()).isZero();
        assertThat(first.maxWaitNanos()).isLessThan(APP_WAIT.toNanos());

        // 真 Redis 上的「放锁」证据：若第一次运行结束后锁没被释放（例如 finally 里漏了 unlock、
        // 或租期到期前锁键残留），第二次运行要么全超时、要么被 leaseTime 拖住 —— 断言会立刻红。
        Observation second = lockScene.run(SceneKind.WAIT, key, Params.of(8, Duration.ZERO));
        assertThat(second.acquired()).as("同一把 key 复用：第二次仍然全部拿到。观测=%s", second).isEqualTo(8);
        assertThat(second.timedOut()).isZero();
    }

    // ================= CONFLICT =================

    @Test
    @DisplayName("CONFLICT：临界区 200ms × 6 线程 ⇒ 观察到串行化（最长等待 ≳ 2 个临界区）")
    void conflictSceneSerializes() {
        Duration criticalSection = Duration.ofMillis(200);

        Observation observation = lockScene.run(SceneKind.CONFLICT, uniqueKey(),
                Params.of(6, criticalSection));

        assertThat(observation.degraded()).isZero();
        assertThat(observation.acquired()).as("6 × 200ms = 1.2s ≪ 5s 等待窗口。观测=%s", observation).isEqualTo(6);
        assertThat(observation.timedOut()).isZero();
        assertThat(observation.anyThreadLeaked()).isFalse();
        // 串行化的直接证据：最后进场的线程前面排着 ~5 个临界区。断言 2 个临界区（400ms）是
        // 留足余量的下界 —— 6 个线程里第 3 个开始就已经等了 ≳ 2 个临界区。
        assertThat(observation.maxWaitNanos())
                .as("最长等待必须 ≳ 2 个临界区（400ms）：这条是「锁真的把并发变成串行」的唯一证据")
                .isGreaterThanOrEqualTo(criticalSection.toNanos() * 2);
    }

    // ================= TIMEOUT =================

    @Test
    @DisplayName("TIMEOUT：持锁者持满 8s（> wait 5s）⇒ 3 个争用者全部超时且走 fail-open；两条通道（事件流/计数器）互证")
    void timeoutSceneTimesOutAndFallsOpen() {
        double timeoutBefore = counter("timeout");
        double degradedBefore = counter("degraded");
        Timer waitTimer = registry.find("mall.lock.wait.duration").tags("lock", LockName.ORDER_ADD.tag()).timer();
        long timerCountBefore = waitTimer == null ? 0L : waitTimer.count();

        Observation observation = lockScene.run(SceneKind.TIMEOUT, uniqueKey(),
                Params.of(3, HOLD_BEYOND_WAIT));

        assertThat(observation.degraded()).as("degraded>0 说明测试环境连不上 Redis，不是锁的问题").isZero();
        // 精确分布的两个前提都由场景自身保证：争用者等持锁者真进临界区后才开始计时（deadline ≈ 5s），
        // 而持锁者要持到 8s 才放手 ⇒ 中间有 3s 余量，争用者不可能「等到锁被放出来」。
        assertThat(observation.acquired()).as("只有持锁者拿到。观测=%s", observation).isEqualTo(1);
        assertThat(observation.timedOut()).as("3 个争用者全部超时").isEqualTo(3);
        assertThat(observation.maxWaitNanos())
                .as("最长等待 ≈ waitTime：证明 tryLock 真的等满了等待窗口（而不是立刻返回/无限等待）")
                .isGreaterThanOrEqualTo((long) (APP_WAIT.toNanos() * 0.9));
        assertThat(observation.anyThreadLeaked()).as("超时走 fail-open 直行 ⇒ 不该有线程卡住").isFalse();

        // 交叉验算：事件流数出来的 3 条 timeout，必须等于 Micrometer 计数器的增量（全局指标，故用增量）。
        assertThat(counter("timeout") - timeoutBefore).isEqualTo(3.0);
        assertThat(counter("degraded") - degradedBefore).as("超时不是降级：那一轮真的碰了 Redis").isZero();
        Timer after = registry.find("mall.lock.wait.duration").tags("lock", LockName.ORDER_ADD.tag()).timer();
        assertThat(after).isNotNull();
        // 4 次取锁尝试都真碰了网络（1 acquired + 3 timeout）⇒ Timer 记 4 次；
        // 这与 MallLockMetrics 的取舍一致：degraded 不记（它没碰网络）。
        assertThat(after.count() - timerCountBefore).isEqualTo(4L);
        assertThat(after.max(TimeUnit.MILLISECONDS))
                .as("Timer 的 MAX 应当落在超时那次 ≈ waitTime（5s）上")
                .isGreaterThanOrEqualTo(APP_WAIT.toMillis() * 0.9);
    }

    // ================= DEADLOCK =================

    @Test
    @DisplayName("DEADLOCK：两把锁反序 + 嵌套 ⇒ 双方各在对方的锁上超时，墙钟有界、无线程泄漏")
    void deadlockSceneIsBoundedAndLeaksNoThread() {
        long startNanos = System.nanoTime();

        Observation observation = lockScene.run(SceneKind.DEADLOCK, uniqueKey(),
                Params.of(2, Duration.ofMillis(50)));

        long elapsedNanos = System.nanoTime() - startNanos;
        assertThat(observation.degraded()).isZero();
        // 闸门保证「双方都在自己的临界区里」才去抢对方的锁 ⇒ 外层 2 次必然成功、内层 2 次必然超时。
        // 这正是 tryLock 带 waitTime 的意义：形状是死锁，结果是有界超时（不是真挂住）。
        assertThat(observation.acquired()).as("双方各自拿到外层锁。观测=%s", observation).isEqualTo(2);
        assertThat(observation.timedOut()).as("内层各等到超时 —— 谁也没能拿到对方的锁").isEqualTo(2);
        assertThat(observation.maxWaitNanos())
                .as("最长等待 ≈ waitTime：双方确实互相阻塞过（不是「先后跑完」的假死锁形状）")
                .isGreaterThanOrEqualTo((long) (APP_WAIT.toNanos() * 0.9));
        assertThat(observation.anyThreadLeaked()).as("核心断言：没有线程被永久留在锁上").isFalse();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(elapsedNanos))
                .as("墙钟有界：内层两次超时各 ≈ 5s，30s 是极宽松的上界").isLessThan(30L);
    }

    // ================= 工具 =================

    /** 每次运行一个唯一 key：事件流按 key 过滤、Redis 上也不与上一次运行的残余键相撞。 */
    private static String uniqueKey() {
        return "scene-" + System.nanoTime();
    }

    /**
     * {@code mall.lock.acquires{lock=order-add,result=<t>}} 的当前值。
     * ⚠️ 指标名用字面量是**故意的**：它是对外契约（{@code /actuator/metrics} 上的名字），
     * 测试按契约查；取值名从枚举取，避免与实现侧的取值表脱节。
     */
    private double counter(String result) {
        Counter counter = registry.find("mall.lock.acquires")
                .tags("lock", LockName.ORDER_ADD.tag(), "result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
