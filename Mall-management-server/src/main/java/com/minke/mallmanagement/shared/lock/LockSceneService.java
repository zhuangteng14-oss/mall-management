package com.minke.mallmanagement.shared.lock;

import com.minke.mallmanagement.shared.lock.MallLockMetrics;
import com.minke.mallmanagement.shared.lock.MallLockMetrics.MallLockEvent;
import com.minke.mallmanagement.shared.lock.MallLockMetrics.Outcome;
import com.minke.mallmanagement.shared.lock.DistributedLockService.LockName;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;

/**
 * 锁专项场景（B2 / D6）—— 「等待 / 冲突 / 超时 / 死锁」四个可复现的并发形状，供 C3 复用。
 *
 * <h2>【它是什么：对 {@code runLocked} 的黑盒测试，不是第二套锁】</h2>
 * 本类**不**碰 Redis、不自己取锁、不记任何自定义状态：它只做三件事 —— 起线程、调
 * {@link DistributedLockService#runLocked}、把结果计数出来。更有价值的复用接口始终是
 * {@code runLocked} 本身；本类只是它的「示范 + 可断言」外壳（plan D6 的措辞）。
 *
 * <h2>【计数为什么读事件流，而不是读 Micrometer 计数器】</h2>
 * 事件流（{@code MallLockMetrics.recentEvents()}）是**公开**通道，且每条 acquire 事件都带
 * {@code key}（业务键）⇒ 场景可以**只数自己那把 key 的事件**。这一点在 A3 那种
 * 「场景与真实流量共处一个进程」的环境里是刚需：计数器是全局的，会把 A3 的订单流量一起数进来；
 * 事件流按 key 过滤后天然不串味。代价是容量有界（512 槽、覆盖即淘汰）——
 * 场景的「并发度 × 每线程事件数」必须远小于容量（两把锁的最坏情况是 4 条/线程），
 * 故 {@link Params#threads()} 上限设为 {@link #MAX_THREADS}（32 ⇒ 最坏 128 条，留 4 倍余量）。
 *
 * <h2>【场景状态用进程内计数，不写 Redis】</h2>
 * 除了「锁本身在 Redis 里」这件事，场景不往 Redis 写任何东西。额外好处：
 * **Redis 不可达时场景照样能跑** —— 那时四路计数会全部落在 {@code degraded} 上，
 * 这本身就是「降级场景」要断言的形状（见 LockSceneServiceTest）。
 *
 * <h2>【四个场景的形状与判据】</h2>
 * <table>
 *   <tr><td>{@code WAIT}</td><td>N 线程抢**同一把**锁、临界区极短（毫秒级）⇒ 全部拿到，
 *       等待量级远小于 {@code app.lock.wait}（5s 有 20 倍余量那一半的证据）。</td></tr>
 *   <tr><td>{@code CONFLICT}</td><td>同 WAIT，但临界区**明显变长** ⇒ 观察到串行化：
 *       总有线程的等待 ≳ 临界区长度（「锁真的把并发变成了串行」的证据）。</td></tr>
 *   <tr><td>{@code TIMEOUT}</td><td>一个持锁者持满 {@code holdMillis}（须 &gt; {@code app.lock.wait}），
 *       其余线程全部等到超时 ⇒ {@code timedOut > 0} 且它们的 action **照样执行**
 *       （fail-open 的直接证据，见 {@code runLocked} 的推演）。</td></tr>
 *   <tr><td>{@code DEADLOCK}</td><td>两把锁**反序**获取（A: L1→L2，B: L2→L1），且第二次获取发生在
 *       第一次的**临界区内**（否则根本不构成死锁形状，只是两次顺序调用）。
 *       因为 {@code tryLock} 带 waitTime，这**不会**真死锁、只会各自超时 ——
 *       要断言的正是「墙钟有界、无线程泄漏」。
 *       <p>⚠️ 必须**恰好 2 个线程**（{@link #run} 会校验）：死锁形状天生是两方的，
 *       多于 2 个线程时「谁在外面临界区里等」就无法保证，判据会退化成碰运气。
 *       场景另有一道闸门：**双方都进入自己的临界区之后**才允许去抢对方的锁 ——
 *       没有这道闸门，先起跑的那个可能已经跑完两把锁，形状根本不成立（判据会变成碰运气）。</td></tr>
 * </table>
 *
 * <h2>【不变量：每个任务都必须跑完】</h2>
 * 四路计数之和恒等于**预期取锁次数**（{@code runLocked} 每次调用记一条：WAIT/CONFLICT 为 threads、
 * TIMEOUT 为 threads+1、DEADLOCK 为 2×threads），且**任一任务抛异常即整个场景失败**
 * （{@code IllegalStateException}）。理由：{@code runLocked} 承诺「永不抛取锁失败类异常」，
 * 它抛了就是机制本身的 bug，不该被场景悄悄计成一个看不懂的分布。
 *
 * <p>本类**零日志**（同 {@code MallLockMetrics}）：它连 Redis、跑并发，逐次打印会污染 A3 日志；
 * 「发生了什么」由 {@link Observation} 与事件流回答。它**不被任何 Controller 注入**
 * ⇒ {@code @WebMvcTest} 切片零波及。
 */
@Service
public class LockSceneService {

    /**
     * 线程数上限。不是性能考虑，是**事件环容量**（512）的安全边界：
     * 死锁场景每线程最坏 4 条 acquire 事件，32 × 4 = 128 ⇒ 留 4 倍余量，
     * 不会把自己的事件挤出环形缓冲（挤出 ⇒ 计数偏小 ⇒ 场景给出假结论）。
     */
    static final int MAX_THREADS = 32;

    /** 任务收尾的等待上限：主要任务是「等所有线程真的退出」，用于 {@link Observation#anyThreadLeaked()}。 */
    private static final long LEAK_JOIN_MILLIS = 5000;

    /** 死锁场景「等双方都进临界区」的安全阀（见 {@link #awaitBothInside}），不是判据。 */
    private static final long GATE_WAIT_SECONDS = 15;

    private final DistributedLockService lockService;
    private final MallLockMetrics lockMetrics;

    public LockSceneService(DistributedLockService lockService, MallLockMetrics lockMetrics) {
        this.lockService = lockService;
        this.lockMetrics = lockMetrics;
    }

    /** 场景种类。四者共享同一套执行机制，区别只在任务形状与**判据**（见类注释的表）。 */
    public enum SceneKind {
        /** 全线程抢同一把锁、临界区极短 ⇒ 全部拿到。 */
        WAIT,
        /** 同上但临界区变长 ⇒ 观察到串行化。 */
        CONFLICT,
        /** 一个持锁者持满 holdMillis，其余全部超时 ⇒ fail-open 仍执行。 */
        TIMEOUT,
        /** 两把锁反序 + 嵌套获取 ⇒ 各自超时，但墙钟有界、无线程泄漏。 */
        DEADLOCK
    }

    /**
     * 场景参数。
     *
     * @param threads    并发线程数（1 ~ {@link #MAX_THREADS}）
     * @param holdMillis 临界区（action 内部）的持有毫秒数。
     *                   {@code TIMEOUT} 场景下它**必须大于** {@code app.lock.wait}（默认 5s），
     *                   否则持锁者会在等待方超时前就放手、场景退化成「都拿到」
     */
    public record Params(int threads, long holdMillis) {

        /** 便于阅读的构造入口（{@code Params.of(8, Duration.ofMillis(50))}）。 */
        public static Params of(int threads, Duration hold) {
            return new Params(threads, hold.toMillis());
        }
    }

    /**
     * 一次场景的结果。
     *
     * <p>⚠️ 四路计数之和 == **预期取锁次数**（{@code runLocked} 每次调用恰好记一条）：
     * {@code WAIT / CONFLICT} 为 {@code threads}、{@code TIMEOUT} 为 {@code threads + 1}、
     * {@code DEADLOCK} 为 {@code threads × 2}。这是场景自己的完备性断言，不是「大概对」；
     * 对不上时 {@link #run} 会抛异常，而不是返回一个偏小的分布。
     * 取值与 {@code mall.lock.acquires{result}} 同名同义
     * （{@link Outcome} 的 tag 就是那条时间序列的 tag）。
     *
     * @param acquired     拿到锁的任务数（互斥下执行了 action）
     * @param timedOut     waitTime 内没抢到的任务数（fail-open，action 照样执行）
     * @param degraded     冷却窗口内**根本没尝试**的任务数（Redis 已知不可达）
     * @param errored      取锁尝试抛异常的任务数（被吞掉并转入冷却）
     * @param maxWaitNanos **单个任务**在 {@code runLocked} 里停留的最长墙钟：
     *                     {@code action 进入时刻 − 调用 runLocked 时刻}。取 max 而不取平均 ——
     *                     平均会被「大部分线程没等」稀释掉，而这里要回答的正是「最坏等了多久」。
     *                     <p>⚠️ 与 {@code mall.lock.wait.duration} **只在真尝试过取锁的两路同口径**
     *                     （acquired / timeout：量到的就是排队等锁的墙钟，超时路径 ≈ waitTime）；
     *                     {@code degraded} 那一路量到的其实是 {@code obtain()} 的代价 ——
     *                     冷却窗口内 ≈ 0，但**冷启动或建连失败时是秒级**（要等建客户端那一步），
     *                     而 Timer 根本不记 degraded。两者不可直接对减。
     * @param anyThreadLeaked 场景结束后是否**还有线程活着**（池未在期限内终止，或有记录在案的线程
     *                     join 不上）—— 死锁场景的核心断言
     */
    public record Observation(int acquired, int timedOut, int degraded, int errored,
                              long maxWaitNanos, boolean anyThreadLeaked) {

        /** 投入的任务数（四路之和应当等于它）。 */
        public int total() {
            return acquired + timedOut + degraded + errored;
        }
    }

    /**
     * 跑一个场景。**同步**：返回时所有线程已结束（或被判为泄漏）。
     *
     * @param kind   场景种类
     * @param key    业务键（Redis key 里的那一段）。⚠️ 调用方应为每次运行给一个**唯一**的 key
     *               （如 {@code "scene-" + System.nanoTime()}）：① 事件流的计数按 key 过滤，
     *               重复用同一个 key 会与上一次运行的残余事件混在一起；② Redis 上的锁键
     *               若因上次异常未释放，只会在租期（{@code app.lock.lease}）后自清。
     * @param params 并发度与临界区长度
     * @return 四路计数 + 最长等待 + 有无线程泄漏
     * @throws IllegalStateException 有任务抛异常（{@code runLocked} 不该抛）；或本线程被中断
     */
    public Observation run(SceneKind kind, String key, Params params) {
        requireValid(kind, params);
        Set<String> keys = keysOf(kind, key);
        long fromSeq = nextSeq();

        LongAccumulator maxWait = newMaxWait();
        List<Runnable> tasks = tasksOf(kind, key, params, maxWait);
        ThreadRecorder recorder = new ThreadRecorder();
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size(), recorder);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(tasks.size());
        boolean leaked;
        try {
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();      // 闸门：让并发真的一起发生（同 A3 的 barrier 手法）
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();           // 逐个 get：任务里的异常在此上抛，不被线程池静默吞掉
            }
            leaked = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("锁场景被中断：" + kind, e);
        } catch (ExecutionException e) {
            // 两种可能都要如实说：runLocked 抛了（机制 bug —— 它承诺永不抛取锁类异常），
            // 或任务自己抛了（本类只 sleep，除非线程被中断）。别把后者说成前者。
            throw new IllegalStateException("锁场景任务失败：" + kind
                    + "（runLocked 承诺不抛取锁类异常，故要么是机制 bug、要么是任务自身被中断）", e.getCause());
        } finally {
            leaked = shutdownAndDetectLeak(pool, recorder);
        }

        return observe(kind, keys, fromSeq, maxWait.get(), leaked, expectedAcquisitions(kind, params));
    }

    /** 场景借用的第二把锁的业务键（{@code DEADLOCK} 用）。公开是**故意的**：测试要按它断言 Redis 键。 */
    public static String peerKey(String key) {
        return key + ":peer";
    }

    // ============================== 任务形状 ==============================

    private List<Runnable> tasksOf(SceneKind kind, String key, Params params, LongAccumulator maxWait) {
        List<Runnable> tasks = new ArrayList<>();
        switch (kind) {
            case WAIT, CONFLICT -> {
                for (int i = 0; i < params.threads(); i++) {
                    tasks.add(() -> maxWait.accumulate(acquireOnce(key, params.holdMillis(), null)));
                }
            }
            case TIMEOUT -> {
                // ① 持锁者：进临界区时放行闸门，然后持满 holdMillis（> app.lock.wait）。
                CountDownLatch holderInside = new CountDownLatch(1);
                tasks.add(() -> maxWait.accumulate(acquireOnce(key, params.holdMillis(), holderInside::countDown)));
                // ② 争用者：等持锁者真的进去之后再动手，否则可能赶在它之前拿到锁、场景整体失效。
                for (int i = 0; i < params.threads(); i++) {
                    tasks.add(() -> {
                        awaitHolderInside(holderInside);
                        maxWait.accumulate(acquireOnce(key, params.holdMillis(), null));
                    });
                }
            }
            case DEADLOCK -> {
                // 双方都在自己的临界区里之后才允许去抢对方的锁 —— 见类注释：没有这道闸门，
                // 先起跑的一方可能把两把锁都跑完，死锁形状根本不成立（判据会变成碰运气）。
                CountDownLatch bothInside = new CountDownLatch(params.threads());
                for (int i = 0; i < params.threads(); i++) {
                    boolean reversed = i % 2 == 1;   // 一方 L1→L2，另一方 L2→L1
                    String first = reversed ? peerKey(key) : key;
                    String second = reversed ? key : peerKey(key);
                    tasks.add(() -> {
                        long outer = acquireOnce(first, params.holdMillis(), bothInside::countDown, () -> {
                            // ⚠️ 第二次获取必须发生在**第一次的临界区内**：否则两把锁是顺序获取的，
                            // 根本没有「持着 A 等 B」的形状，也就不是死锁场景（只是两次正常调用）。
                            awaitBothInside(bothInside);
                            long inner = acquireOnce(second, params.holdMillis(), null);
                            maxWait.accumulate(inner);
                        });
                        maxWait.accumulate(outer);
                    });
                }
            }
        }
        return tasks;
    }

    /**
     * 一次取锁 + 临界区，返回**取锁等待的墙钟纳秒**。
     *
     * <p>量法：{@code action 进入时刻 − 调用 runLocked 时刻}。这在四条路径上都成立 ——
     * 拿到锁时是「排队等锁」的时间；超时/降级时 action 紧跟在 tryLock 返回之后，
     * 量到的就是 tryLock 自己的耗时（超时 ≈ waitTime、降级 ≈ 0）。之所以不拆成
     * 「等待 + 持有」两段：那需要在 action 里再自报一次时刻，而真实调用点没有这个探针，
     * 场景不该给自己造一个别人没有的洞。
     */
    private long acquireOnce(String key, long holdMillis, Runnable onEnter) {
        return acquireOnce(key, holdMillis, onEnter, null);
    }

    private long acquireOnce(String key, long holdMillis, Runnable onEnter, Runnable insideCriticalSection) {
        long[] enteredNanos = {0L};
        long startNanos = System.nanoTime();
        lockService.runLocked(LockName.ORDER_ADD, key, () -> {
            enteredNanos[0] = System.nanoTime();
            if (onEnter != null) {
                onEnter.run();
            }
            if (insideCriticalSection != null) {
                insideCriticalSection.run();
            }
            hold(holdMillis);
            return null;
        });
        return enteredNanos[0] - startNanos;
    }

    /** 临界区里待一会儿。{@code 0} 时直接跳过（连一次 syscall 都不值得）。 */
    private static void hold(long holdMillis) {
        if (holdMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(holdMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 不把中断语义吞掉（同 runLocked 的处置）
        }
    }

    private static void awaitHolderInside(CountDownLatch holderInside) {
        try {
            holderInside.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 等「双方都在临界区里」。带 {@link #GATE_WAIT_SECONDS} 上限是**安全阀**而非判据：
     * 任务在拿到锁与降级放行两种情况下**都会**进入 action，故正常不可能等到上限；
     * 真等到上限说明有线程连 action 都没进（机制 bug），这时让场景继续走下去并由其它判据暴露，
     * 比整场挂死在这里好。
     */
    private static void awaitBothInside(CountDownLatch bothInside) {
        try {
            bothInside.await(GATE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================== 结果汇总 ==============================

    /**
     * 本次场景**预期产生多少条 acquire 事件**（计数完备性的基准）。
     *
     * <p>⚠️ 不等于任务数：{@code runLocked} 每次调用恰好记一条 acquire 事件，而
     * {@code DEADLOCK} 的每个任务调用**两次**（外层 + 临界区内的内层）⇒ 2 × threads。
     * {@code TIMEOUT} 是 threads + 1（多一个持锁者）。这个基准是常量而非观测值，
     * 所以「对不上」必是事件被覆盖或 runLocked 漏记 —— 两种都该炸。
     */
    private static int expectedAcquisitions(SceneKind kind, Params params) {
        return switch (kind) {
            case WAIT, CONFLICT -> params.threads();
            case TIMEOUT -> params.threads() + 1;
            case DEADLOCK -> params.threads() * 2;
        };
    }

    /**
     * 数事件流里属于本次场景的 acquire 事件。
     *
     * <p>过滤三件套：{@code seq >= fromSeq}（不数上一次运行残留的）、{@code kind == acquire}
     * （hold 事件不参与）、{@code key ∈ keys}（不数进程里其他流量）。
     */
    private Observation observe(SceneKind kind, Set<String> keys, long fromSeq, long maxWaitNanos,
                                boolean leaked, int expectedAcquisitions) {
        int acquired = 0;
        int timedOut = 0;
        int degraded = 0;
        int errored = 0;
        for (MallLockEvent event : lockMetrics.recentEvents()) {
            if (event.seq() < fromSeq
                    || !"acquire".equals(event.kind())
                    || !keys.contains(event.key())) {
                continue;
            }
            String result = event.result();
            if (Outcome.ACQUIRED.tag().equals(result)) {
                acquired++;
            } else if (Outcome.TIMEOUT.tag().equals(result)) {
                timedOut++;
            } else if (Outcome.DEGRADED.tag().equals(result)) {
                degraded++;
            } else if (Outcome.ERROR.tag().equals(result)) {
                errored++;
            }
        }
        if (acquired + timedOut + degraded + errored != expectedAcquisitions) {
            // 事件环被覆盖（并发度超 MAX_THREADS 或进程里流量太大）时会走到这里。宁可炸，
            // 也不返回一个「看起来很正常」但偏小的分布 —— 场景的全部价值就是它的数字可信。
            throw new IllegalStateException(String.format(
                    "锁场景计数不完整（%s）：四路之和 %d != 预期取锁次数 %d —— 事件环被覆盖了？"
                            + "（MAX_THREADS=%d，进程内是否还有别的流量在写同一把 key？）",
                    kind, acquired + timedOut + degraded + errored, expectedAcquisitions, MAX_THREADS));
        }
        return new Observation(acquired, timedOut, degraded, errored, maxWaitNanos, leaked);
    }

    /** 本次场景会用到的事件 key 集合（DEADLOCK 两把，其余一把）。 */
    private static Set<String> keysOf(SceneKind kind, String key) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(key);
        if (kind == SceneKind.DEADLOCK) {
            keys.add(peerKey(key));
        }
        return keys;
    }

    /**
     * 下一个事件序号（本次场景的事件都 ≥ 它）。取不到就返回 0 —— 环形缓冲为空时是正常情形
     * （例如进程刚起来），此时 0 与「从 0 开始数」等价。
     */
    private long nextSeq() {
        List<MallLockEvent> snapshot = lockMetrics.recentEvents();
        return snapshot.isEmpty() ? 0L : snapshot.get(snapshot.size() - 1).seq() + 1;
    }

    private static void requireValid(SceneKind kind, Params params) {
        if (params.threads() <= 0 || params.threads() > MAX_THREADS) {
            throw new IllegalArgumentException("threads 必须在 1~" + MAX_THREADS + " 之间：" + params.threads());
        }
        if (params.holdMillis() < 0) {
            throw new IllegalArgumentException("holdMillis 不能为负：" + params.holdMillis());
        }
        if (kind == SceneKind.DEADLOCK && params.threads() != 2) {
            // 宁可拒绝，也不给一个判据会碰运气的场景：多于 2 个线程时无法保证「双方都在临界区内」，
            // 「谁在等谁」就不再确定（见类注释的 DEADLOCK 行）。
            throw new IllegalArgumentException("DEADLOCK 必须恰好 2 个线程（两把锁、两个方向）：" + params.threads());
        }
    }

    /**
     * 关闭线程池并判断是否还有线程活着。
     *
     * <p>顺序：{@code shutdown()} → 有界等待 → **逐个 join** 记录在案的线程。
     * 只靠 {@code awaitTermination} 会有一个小竞态（池已终止但某个线程还在退出路上），
     * join 一次就把这个窗口关掉。仍未退出 ⇒ 判为泄漏（并 {@code shutdownNow()} 收尾）。
     */
    private static boolean shutdownAndDetectLeak(ExecutorService pool, ThreadRecorder recorder) {
        pool.shutdown();
        boolean terminated = false;
        try {
            terminated = pool.awaitTermination(LEAK_JOIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean leaked = !terminated;
        for (Thread thread : recorder.created) {
            try {
                thread.join(LEAK_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                leaked = true;
            }
        }
        if (!terminated) {
            pool.shutdownNow();
        }
        return leaked;
    }

    /**
     * 记录创建的线程（用于泄漏判定）+ 起名 + **daemon**。
     *
     * <p>daemon 是重要的：场景若因机制 bug 卡死，非 daemon 线程会拖住 JVM 退出
     * （本仓在 Redisson 上已经踩过一次，见 {@code DistributedLockService#destroy()}）。
     * 泄漏不该表现为「mvn test 不退出」，而该表现为 {@code anyThreadLeaked=true}。
     */
    private static final class ThreadRecorder implements ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger();
        private final List<Thread> created = new CopyOnWriteArrayList<>();

        /**
         * 块 7 · commit 8c：取一次**本线程**（= 调用 {@link #run} 的那条线程，请求线程）的
         * (traceId, batchId) 快照，供下面每条新建线程绑上。
         *
         * <p>⚠️ 为什么捕获点在这里、而不是在投递任务的地方：本对象**在请求线程上被构造**
         * （{@code run} 里的 {@code new ThreadRecorder()}），而线程是在它之后才建的 —— 字段初始化
         * 发生在构造期 ⇒ 拿到的正是请求线程的上下文。⚠️ 为什么绑定发生在**建线程**而不是
         * **投递任务**：{@code ThreadPoolExecutor} 交给 {@code ThreadFactory} 的那个 {@code r}
         * 是**工作线程的循环体**（不是某一个任务），绑它 = 这条线程接下来跑的全部任务都在上下文里；
         * 而本池每次 {@code run} 现建现销毁 ⇒ 它的生命周期恰好等于一次场景。
         * ⚠️ 于是有一个**已知边界**：泄漏判定为真（线程没能终止）时，那条线程上的上下文也不会被还原
         * —— 但那正是「场景已经出问题」的状态，`anyThreadLeaked=true` 已经把它报出来了。
         */
        private final com.minke.mallmanagement.shared.web.ObserveContext ctx =
                com.minke.mallmanagement.shared.web.ObserveContext.capture();

        @Override
        public Thread newThread(Runnable r) {
            // 块 7 · commit 8c：包一层把上下文绑到工作线程上（理由见上面那个字段）。
            // ⚠️ 写全限定名而非 import：本文件 `:358` 被 defect-survey.md 按「文件:行号」引用，
            // 加一行 import 会让它下移。
            Thread thread = new Thread(() -> ctx.run(r), "lock-scene-" + seq.incrementAndGet());
            thread.setDaemon(true);
            created.add(thread);
            return thread;
        }
    }

    /** 取多个线程里的最长等待（语义是 max，不是加和）。初始值 0：等待墙钟不可能为负。 */
    private static LongAccumulator newMaxWait() {
        return new LongAccumulator(Math::max, 0L);
    }
}
