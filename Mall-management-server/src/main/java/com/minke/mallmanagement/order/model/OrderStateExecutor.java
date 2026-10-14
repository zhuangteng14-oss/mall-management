package com.minke.mallmanagement.order.model;

import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单状态流转的异步出口（B3）—— 一次转移搬动的是**一行 INSERT**，不该让下单/付款请求等它。
 *
 * <h2>【为什么是显式 ThreadPoolExecutor，不是 {@code @Async}】</h2>
 * <ol>
 *   <li><b>调用点在锁的临界区里</b>：{@code OrderService} 的三个
 *       {@code *Locked} 方法体在 {@code runLocked} 的 lambda 内。{@code @Async} 靠 Spring 代理生效，
 *       而**同类内部的 lambda 调用不经过代理** ⇒ 自调用，静默变成同步执行（不报错、不变红，
 *       只是没异步）。这正是本仓 B2 已经踩过的形状（{@code DistributedLockService} 类注释第 4 条：
 *       两个 Controller 被 {@code MallManagementApplicationTests} 钉死为**无 AOP 代理**）。</li>
 *   <li><b>拒绝行为要能被本仓自己定义</b>：{@code @Async} 的拒绝策略藏在
 *       {@code AsyncConfigurer} 里，而本笔对「队列打满丢谁」有明确裁决（D3 = 丢最旧），
 *       且要求**丢了要记在谁头上**（见 {@link #rejectingPolicy()}）—— 那需要一个知道
 *       任务携带的订单号的处理器。</li>
 *   <li><b>观测出口统一</b>：队列深度要绑成 gauge。显式持有 Executor 才有
 *       {@code getQueue().size()} 可绑。</li>
 * </ol>
 *
 * <h2>【为什么**不用** {@code CallerRunsPolicy}】</h2>
 * 它是这里最诱人的错误答案：「队列满了就让调用线程自己跑」听起来是零丢弃。但调用线程
 * **此刻正持着订单锁**（{@code app.lock.lease=10s}），于是状态流转的 INSERT 被塞进临界区，
 * 临界区时长从毫秒级变成「队列积压深度 × 单条 INSERT」—— 租期一旦跑输，{@code lease_lost}
 * 出现、两个线程可能同时进入临界区，**用一个可观测的丢弃换来一个不可观测的正确性风险**。
 * 丢弃是**有界且被计数**的（{@code mall.order.state.async.rejected}），租期丢失不是。
 * 故：{@link #rejectingPolicy()} 丢最旧 + 计数。
 *
 * <h2>【丢最旧（D3）—— 以及它的代价，必须说清楚】</h2>
 * 队列是**有界**的（默认 1024），打满时丢**队首**（最旧的那条时间线），接新的。
 * 直觉上丢新更"安全"，其实相反：时间线是 append-only 的过程记录，最新的一条对应
 * **刚刚发生、用户正在看的那个状态**；丢它反而让「刚下的单查不到流转记录」。
 * 代价照实说：**丢一条 = 时间线永久缺一条**，无法事后补齐（状态位本身在 {@code order} 表里，
 * 不丢；丢的是「什么时候、从什么状态转到什么状态」）。所以它**一定**记
 * {@code mall.order.state.async.rejected{reason=queue_full}} + 一条事件 —— 允许丢，不许静默。
 *
 * <p>⚠️ JDK 自带的 {@link ThreadPoolExecutor.DiscardOldestPolicy} **在这里不可用**：它
 * {@code poll()} 掉队首后**直接丢弃且不回调任何人**，那条时间线丢了连计数都没有。故本类
 * 自己写一个：丢之前先把「是哪一单」记下来。这是本类唯一一处必须自定义 JDK 已有策略的地方。
 *
 * <h2>【A3 期间这套机制不会触发 —— 这是刻意的，不是没测到】</h2>
 * A3 的并发度是 20~40 线程，每单最多 3 次成功转移 ⇒ 瞬时在途任务是个位数，
 * 离 1024 的容量三个数量级。**拒绝分支只能由单测覆盖**（见 {@code OrderStateExecutorTest}），
 * 在 A3 里它应该恒等于 0 —— 场景里要断言的正是这个 0，而不是"观察到了拒绝"。
 *
 * <h2>【关闭】</h2>
 * 实现 {@link DisposableBean}：{@code shutdown()} → {@code awaitTermination(默认 10s)} →
 * 超时则 {@code shutdownNow()}。线程本身是 **daemon**（本类自己造线程，与 Redisson 那种
 * 只能拿到非 daemon 线程的情形不同）⇒ 即使容器没走到 {@code destroy()}，也不会拖住 JVM 退出。
 */
@Service
public class OrderStateExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OrderStateExecutor.class);

    /** 线程名前缀。线程名是排障时唯一能定位「谁在写时间线」的线索，不省略。 */
    private static final String THREAD_NAME_PREFIX = "order-state-";

    private final MallOrderStateMetrics metrics;
    private final ThreadPoolExecutor executor;
    private final Duration shutdownTimeout;

    /**
     * 已丢弃的任务数。**只用于启动日志/自检**，真正的观测出口是
     * {@code mall.order.state.async.rejected} —— 两条通道不重复（规范第 6 条），
     * 这个 AtomicLong 不进指标，只在 {@link #destroy()} 的收尾日志里报一个总数。
     */
    private final AtomicLong dropped = new AtomicLong();

    public OrderStateExecutor(MallOrderStateMetrics metrics,
                              @Value("${app.order.state.async.core-size:2}") int coreSize,
                              @Value("${app.order.state.async.max-size:4}") int maxSize,
                              @Value("${app.order.state.async.queue-capacity:1024}") int queueCapacity,
                              @Value("${app.order.state.async.shutdown-timeout:10s}") Duration shutdownTimeout) {
        this.metrics = metrics;
        this.shutdownTimeout = shutdownTimeout;

        warnIfMisconfigured(coreSize, maxSize, queueCapacity);

        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.executor = new ThreadPoolExecutor(coreSize, Math.max(coreSize, maxSize),
                // 非核心线程的空闲存活时间：本池的负载是低频突发，不留常驻空闲线程。
                60L, TimeUnit.SECONDS,
                queue, new OrderStateThreadFactory(), rejectingPolicy());

        // 启动期把生效值打进日志（同 CacheConfig 的「两级缓存已装配」、DistributedLockService 的
        // 「分布式锁已装配」）：属性名拼错时 @Value 会静默退化成默认值，而默认值恰好与 properties
        // 同值 —— 有这一行，「属性被读到」才是启动即可取证的事实。ASCII 片段 "B3" 供 GBK 日志下检索。
        log.info("订单状态异步出口已装配（B3）：core={} max={} queueCapacity={} 策略=丢最旧+计数 "
                        + "shutdownTimeout={}（拒绝分支在 A3 中应恒为 0，见 OrderStateExecutor 类注释）",
                coreSize, Math.max(coreSize, maxSize), queue.size(), shutdownTimeout);

        // 队列深度出口：gauge mall.order.state.async.queue.depth。
        // 传 lambda 而不是本对象：取值对象没有别的强引用，必须由 MallOrderStateMetrics 自己存一份 ——
        // 否则一次 GC 后 gauge 静默变 NaN（见 bindQueueDepth）。queueSize() 是纯读取，不碰线程池调度。
        metrics.bindQueueDepth(this::queueSize);
    }

    // ============================== 对外唯一入口 ==============================

    /**
     * 投递一次时间线落库。**永不抛、永不阻塞**（除了入队本身），永不把异常带回调用方。
     *
     * <p>⚠️ 本方法**在订单锁的临界区内被调用**（与 UPDATE 同一处，见
     * {@code OrderService}）。故这里只做「包装 + 入队」，不做任何 I/O：
     * 入队是 O(1) 的，代价纳秒级，不改变临界区的量级。
     *
     * @param orderId 业务键。**只用于事件流与丢弃计数**，绝不进 tag（基数无界）。
     *                任务被丢弃时，靠它才能回答「丢的是哪一单」。
     * @param task    真正的落库动作（{@code OrderTimelineWriter}）。它自己负责吞掉 SQL 异常，
     *                本类不替它 catch —— 但**计时**在 {@code finally} 里，抛了也照记。
     */
    public void submit(String orderId, Runnable task) {
        // 块 7 · commit 8c：把 (traceId, batchId) 从**调用线程**搬到池线程。池线程是「建一次用多次」
        // 的，MDC 不会跟着任务过来（理由见 ObserveContext 类注释）⇒ 时间线落库路径上记出的事件
        // 若不带这两个号，抽屉里就串不起来。⚠️ 捕获点必须在**入队之前**：入队之后就没有
        // 「请求线程」这个身份了。⚠️ 写全限定名而非 import：本文件 `:79` 被
        // concurrency-map.md 按「文件:行号」引用（两处），加一行 import 会让它下移。
        com.minke.mallmanagement.shared.web.ObserveContext ctx =
                com.minke.mallmanagement.shared.web.ObserveContext.capture();
        try {
            executor.execute(new TimelineTask(orderId, () -> ctx.run(timed(task))));
        } catch (RejectedExecutionException e) {
            // 正常路径不该到这里：rejectingPolicy() 已经处理了队列满与关闭两种情形，且它自己
            // 不再上抛。能落到这里只可能是「处理器内部再次 execute 时又撞上关闭」的竞态，
            // 或者是本类的实现被改坏了。**不让它变成用户可见的 5xx** —— 记一笔就走。
            metrics.asyncRejected(RejectReason.SHUTDOWN, orderId);
        }
    }

    /** 当前积压。{@code mall.order.state.async.queue.depth} 的取值来源，也是 A3 的排空判据。 */
    public int queueSize() {
        return executor.getQueue().size();
    }

    /** 当前在跑的线程数。收尾自检用（不是指标 —— 入口已由 queue.depth + rejected 覆盖）。 */
    public int activeCount() {
        return executor.getActiveCount();
    }

    /**
     * 池是否已收到关闭信号。
     *
     * <p>存在的理由是**可测性**：关闭路径上要断言「已入队的任务不被丢弃、新任务被拒且记
     * {@code shutdown}」，而这两件事都必须发生在 {@code shutdown()} 之后、排空之前 ——
     * 没有这个只读访问器，测试只能用「再投一个探针看看计数有没有涨」来判断，而那个探针
     * 本身就是一次被拒的提交，会把待断言的计数污染掉。同 {@link #queueSize()}，是纯读取。
     */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // 超时：剩下的任务**没有**落库。它们对应的转移已经在 order 表里生效了，
                // 只是时间线缺条 —— 属于已知且有计数的代价（同队列满），不是数据损坏。
                int abandoned = executor.shutdownNow().size();
                log.warn("订单状态异步出口关闭超时（B3）：{} 个时间线任务未完成即被放弃；"
                                + "状态位本身不受影响，缺的只是流转记录", abandoned);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("订单状态异步出口已关闭（B3）：累计丢弃 {} 条时间线（明细见 mall.order.state.async.rejected）",
                dropped.get());
    }

    // ============================== 拒绝策略 ==============================

    /**
     * 队列打满 ⇒ 丢队首（最旧）并记账，接新的；线程池已关闭 ⇒ 不接、也不丢别人的，只记账。
     *
     * <p>与 JDK {@link ThreadPoolExecutor.DiscardOldestPolicy} 的差别只有一个 ——
     * **丢之前先记下丢的是哪一单**（JDK 版 poll 完直接扔，那条时间线丢了连计数都没有）。
     * 语义（丢谁、接谁）与 JDK 版逐字一致，故这里不是"发明了新策略"，是给同一个策略补上出口。
     *
     * <p>poll 之后**再查一次** {@code isShutdown()}：并发关闭时不能因为"队列里腾出格了"
     * 就把新任务塞进去（那时线程池正在收尾，塞进去仍然落不了库）。
     */
    private RejectedExecutionHandler rejectingPolicy() {
        return (task, pool) -> {
            if (pool.isShutdown()) {
                metrics.asyncRejected(RejectReason.SHUTDOWN, orderIdOf(task));
                return;
            }
            Runnable oldest = pool.getQueue().poll();
            if (oldest != null) {
                dropped.incrementAndGet();
                metrics.asyncRejected(RejectReason.QUEUE_FULL, orderIdOf(oldest));
            }
            // 竞态兜底：刚 poll 完的那一刻可能开始关闭。**再查一次**，避免往正在关闭的池里塞
            // （首次查与这次查之间隔了一次 poll，足够另一个线程走完 shutdown()）。
            if (pool.isShutdown()) {
                metrics.asyncRejected(RejectReason.SHUTDOWN, orderIdOf(task));
                return;
            }
            try {
                pool.execute(task);
            } catch (RejectedExecutionException e) {
                // 极端竞态（poll 与 execute 之间队列又被别的线程填满、随后关闭）。
                // 不再递归——记一笔结束，宁可丢这一条也不再自旋。
                dropped.incrementAndGet();
                metrics.asyncRejected(RejectReason.QUEUE_FULL, orderIdOf(task));
            }
        };
    }

    private static String orderIdOf(Runnable task) {
        return task instanceof TimelineTask t ? t.orderId() : "unknown";
    }

    /**
     * 给落库动作套上计时。{@code finally} 里记 —— 落库抛异常时**照样记**：
     * 那次执行同样占了线程、同样花了时间，把它漏掉会让
     * {@code mall.order.state.async.duration} 系统性偏乐观。
     */
    private Runnable timed(Runnable body) {
        return () -> {
            long startNanos = System.nanoTime();
            try {
                body.run();
            } finally {
                metrics.asyncDuration(System.nanoTime() - startNanos);
            }
        };
    }

    /**
     * 投递单元。**带上业务键**，否则拒绝时无法回答「丢的是哪一单」——
     * 这正是本类不能直接用 JDK {@code DiscardOldestPolicy} 的原因。
     */
    private record TimelineTask(String orderId, Runnable body) implements Runnable {
        @Override
        public void run() {
            body.run();
        }
    }

    /**
     * 造 daemon 线程并命名。daemon 的理由：本池是本类自己造的，但关闭路径只由 Spring 保证 ——
     * 若容器没走到 {@link #destroy()}（例如测试里直接 new、或上下文异常退出），非 daemon 线程
     * 会把 JVM 拖住（{@code mvn test} 不退出，B2 的 Redisson 代理客户端踩过同一个坑）。
     * 双重保险比单点依赖便宜。
     */
    private static final class OrderStateThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, THREAD_NAME_PREFIX + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * 配置自检。只 WARN 不抛：这是配置疑点，不该让应用起不来（同
     * {@code DistributedLockService.warnIfWaitNotShorterThanLease}、L2ProbeConfig 的两道校验）。
     */
    private static void warnIfMisconfigured(int coreSize, int maxSize, int queueCapacity) {
        if (coreSize < 1) {
            log.warn("app.order.state.async.core-size({}) 小于 1：线程池将无法启动任何线程。", coreSize);
        }
        if (maxSize < coreSize) {
            log.warn("app.order.state.async.max-size({}) 小于 core-size({})：max-size 将被抬到 core-size，"
                    + "配置未按预期生效。", maxSize, coreSize);
        }
        if (queueCapacity < 1) {
            log.warn("app.order.state.async.queue-capacity({}) 小于 1：队列被抬到 1，"
                    + "届时几乎每次投递都会走「丢最旧」分支。", queueCapacity);
        }
    }
}
