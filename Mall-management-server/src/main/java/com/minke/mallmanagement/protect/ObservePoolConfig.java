package com.minke.mallmanagement.protect;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.shared.web.ObserveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>观测线程池分池</b>（D 段 · commit 2 · 裁定「线程池分池 = 接闸」）—— 给三条 SSE 流
 * （以及驱动它们的合并环 {@code poll()}）一个**专用且有界**的池。
 *
 * <h2>【本段一共几个池：三个，逐个说清「分的是什么」】</h2>
 * <pre>
 *   池          此前是什么                本段改成什么                     在哪
 *   ──────────  ────────────────────────  ──────────────────────────────  ──────────────────────
 *   订单池      已经是专用的（B2 就有）    **不动**（本段一行没碰）          OrderStateExecutor
 *   观测池      Boot 的 applicationTask-   本类的专用有界池（core 8/max 32）  本类
 *               Executor（与全站异步共用）
 *   MQ 消费池   没配 ⇒ 默认 1 条消费者     2 条起步、最多 8 条               application.properties
 * </pre>
 * ⚠️ <b>「事件池」与「SSE 池」在本仓是同一个池，不是两个</b>：合并环 {@code poll()} 由
 * **SSE 流线程**驱动（{@code EventBroadcaster} 类注释末节逐字记过这条边界）⇒ 给流线程一个池
 * 就等于给轮询一个池。⚠️ 另起一个定时线程去 {@code poll()} 会把「**没人看日志页时事件不落库**」
 * 这条既有口径直接改掉（那是块 7 的明确边界，不是 bug），本段**不碰**。
 *
 * <h2>【⚠️ 这个池只服务「异步返回类型」，业务请求一条都不走它 —— 别把它想成 Tomcat 的池】</h2>
 * MVC 的 async 执行器只用在 {@code Callable} / {@code DeferredResult} / {@code StreamingResponseBody}
 * 这三种返回类型上；普通阻塞式 Controller 跑在 **Tomcat 的工作线程**上。本仓用到的只有
 * {@code StreamingResponseBody}（{@code SseStreamWriter}）⇒ 这个池的占用数 = **开着的流条数**
 * （一个页面 3 条流，本仓 ≤4 个页面），不是「每秒多少请求」。
 * <p>⚠️ 由此得出池的尺寸口径：**它不是吞吐池，是并发连接池**。装不下就是「第 N+1 个页面打不开流」。
 * 32 条 ≈ 10 个页面同时开三条流，对本仓（单实例 + 内部管理端）是远超需求的余量。
 *
 * <h2>【{@code poolIsolation} 这枚开关在这里做什么：有界 ↔ 无界】</h2>
 * <ul>
 *   <li><b>开（默认）</b>：{@link #dedicated}（core 8 / max 32 / 队列 64 / 满则当场拒 + 记 SHED）；</li>
 *   <li><b>关</b>：{@code SimpleAsyncTaskExecutor} —— **每条流新起一个线程、不排队、无上界**。</li>
 * </ul>
 * ⚠️ 「关掉分池」不是「回到共用」而是「回到**无界**」，这是**刻意的**，与「关掉限流 = 没有闸」
 * 是同一种口径：**关掉一层防护 = 回到没有这一层时的形态**。B3 演示里「关防护 + 拉满 ⇒ 崩」
 * 那一格就吃它 —— 无界形态在高并发下会一直起线程，直到把机器拖垮。
 * <p>⚠️ 如实记一条**本笔做不到的**：切换发生在 {@code execute()} 那一刻（每次请求现读开关），
 * 而 Boot 的 {@code applicationTaskExecutor} 仍然存在且仍服务别的东西 —— 本类**没有**把它拆掉，
 * 也没有能力拆（那是自动装配的 bean）。所以准确的说法是「**观测流从这个池走**」，
 * 不是「全站的异步只有这一个池」。
 *
 * <h2>【切片安全：与 {@link ProtectConfig} 同一条处置】</h2>
 * {@code @WebMvcTest} 会扫到本类（{@code WebMvcConfigurer} 在切片类型名单里），而切片里
 * **没有** {@code ProtectionGate} / {@code ProtectionMetrics}（它们是 {@code @Component}，切片不扫）
 * ⇒ 两个依赖都走 {@code ObjectProvider}，取不到时本池退化成「有界、不记 SHED」的静态形态（不抛错）。
 */
@Configuration
public class ObservePoolConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ObservePoolConfig.class);

    /** 池名（线程名前缀用得到，排障时 {@code jstack} 一眼能认出来）。 */
    public static final String POOL_NAME = "observe-stream";

    static final int CORE_POOL_SIZE = 8;
    static final int MAX_POOL_SIZE = 32;
    static final int QUEUE_CAPACITY = 64;

    private final ThreadPoolTaskExecutor dedicated;
    private final SimpleAsyncTaskExecutor unbounded;
    private final ProtectionGate gate;
    private final ProtectionMetrics metrics;

    public ObservePoolConfig(ObjectProvider<ProtectionGate> gateProvider,
                             ObjectProvider<ProtectionMetrics> metricsProvider) {
        this.gate = gateProvider.getIfAvailable();
        this.metrics = metricsProvider.getIfAvailable();
        this.dedicated = new ThreadPoolTaskExecutor();
        dedicated.setCorePoolSize(CORE_POOL_SIZE);
        dedicated.setMaxPoolSize(MAX_POOL_SIZE);
        dedicated.setQueueCapacity(QUEUE_CAPACITY);
        dedicated.setThreadNamePrefix(POOL_NAME + "-");
        // 拒绝策略：**不阻塞调用方、不丢在无声处** —— 记一条 SHED（第 9 类事件环）再抛。
        // 抛出去的后果是那一条流开不起来（Spring 转 503），而这是**如实**的：
        // 池真的没有余量了，编一个「开起来了但没数据」的流比 503 坏得多。
        dedicated.setRejectedExecutionHandler((runnable, executor) -> {
            if (metrics != null) {
                metrics.shed("poolIsolation", "pool", "pool=" + POOL_NAME
                        + " active=" + executor.getActiveCount()
                        + " poolSize=" + executor.getPoolSize()
                        + " queue=" + executor.getQueue().size()
                        + " ⇒ 拒绝（不排队）：观测量已超池容量",
                        // 块 7 · 阶段 3a.3 后续：挤出的人话原因（与 detail 同一批事实，供页面单独着色）
                        "线程池已满：任务未排队、当场拒绝（active=" + executor.getActiveCount()
                                + "，poolSize=" + executor.getPoolSize()
                                + "，queue=" + executor.getQueue().size() + "）");
            }
            throw new java.util.concurrent.RejectedExecutionException(
                    POOL_NAME + " 已满（active=" + executor.getActiveCount()
                            + " poolSize=" + executor.getPoolSize() + "）");
        });
        // ⚠️ 这两句是这个池**能工作**与**不吊住 JVM** 的前提，逐条记为什么：
        // ① `initialize()`：本池是**在构造函数里 new 出来的普通对象**，不是 Spring bean ⇒
        //    `afterPropertiesSet()` 永远不会被容器调（那个钩子只对 bean 生效）⇒ 不显式初始化，
        //    池是空的，第一次 `execute` 会抛 IllegalStateException("not initialized")。
        //    （`initialize()` 是 ExecutorConfigurationSupport 的 public 方法，就是这个用途。）
        // ② `setDaemon(true)`：池线程默认是**非守护线程**，而本池不属于容器生命周期
        //    （容器不会 stop 一个不是 bean 的池）⇒ 不设守护，几条 SSE 流开过之后
        //    这些线程会一直活着，**JVM 关不掉**（后端重启脚本会卡住等它）。
        //    代价如实记：JVM 退出时正在跑的流被切断 —— 但退出本来就会切断一切，不新增失效模式。
        //    （下面那个 @PreDestroy 仍保留：正常关容器时先把池收掉，比靠守护位兜底干净。）
        dedicated.setDaemon(true);
        dedicated.initialize();
        this.unbounded = new SimpleAsyncTaskExecutor(POOL_NAME + "-unbounded-");
        // 同上 ②：无界那一支同样是「一次任务一条线程」，不设守护就同样吊 JVM。
        this.unbounded.setDaemon(true);
        log.info("[PROTECT] 观测线程池已分池（PROTECT）：pool={} core={} max={} queue={}（poolIsolation 关 ⇒ 退化为无界）",
                POOL_NAME, CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
    }

    /** 正常关容器时先把专用池收掉（`shutdown()` = 不再收新任务 + 打断在跑的）。 */
    @jakarta.annotation.PreDestroy
    public void shutdownPool() {
        dedicated.shutdown();
    }

    /** 把 MVC 的异步执行器换成「按 {@code poolIsolation} 现选」的那个。⚠️ 不动 async 超时（那一项在 properties）。 */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new GatedExecutor());
    }

    /**
     * 每次 {@code execute} 现读开关并选池 —— 这是「能被演示拨动」的实现点。
     * ⚠️ 用一个薄壳而不是「启动时选一次」：开关是**演练期间**被拨的，
     * 启动时选一次就等于「改了要重启」，那不是「接闸」。
     */
    private class GatedExecutor implements AsyncTaskExecutor {

        /** 选中的池 + 累计选择次数（给测试断言「拨了真的换了」）。 */
        private final AtomicLong dedicatedPicks = new AtomicLong();
        private final AtomicLong unboundedPicks = new AtomicLong();

        private AsyncTaskExecutor pick() {
            if (gate != null && !gate.isOn("poolIsolation")) {
                unboundedPicks.incrementAndGet();
                return unbounded;
            }
            dedicatedPicks.incrementAndGet();
            return dedicated;
        }

        /**
         * 把任务包一层 {@link ObserveContext} 搬运：**提交方线程**（通常是 Tomcat 请求线程）
         * 捕获 (traceId, batchId, actorId, actorRole)，在池线程上绑定、任务结束还原。
         *
         * <p>⚠️ 这是 P0「traceId 串线」修复的第二半（第一半在 {@code TraceIdFilter}）：
         * 池线程是**建一次用多次**的，不绑不还原就会出现「A 请求的流线程日志顶着 B 请求的号」。
         * 用仓库既有的 {@link ObserveContext}（显式搬运、finally 还原）而不是 InheritableThreadLocal
         * —— 理由见该类注释（池线程只会继承创建那一刻的上下文，带错号比不带更糟）。
         *
         * <p>⚠️ 捕获必须发生在 {@code execute/submit} **被调用的那一刻**（提交方线程）；
         * 若挪到池线程里再 capture，读到的是该线程上一次任务残留的上下文。
         */
        private Runnable wrap(Runnable task) {
            ObserveContext context = ObserveContext.capture();
            return () -> context.run(task);
        }

        private <T> Callable<T> wrap(Callable<T> task) {
            ObserveContext context = ObserveContext.capture();
            return () -> context.callChecked(task);
        }

        @Override
        public void execute(Runnable task) {
            pick().execute(wrap(task));
        }

        @Override
        public void execute(Runnable task, long startTimeout) {
            pick().execute(wrap(task), startTimeout);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return pick().submit(wrap(task));
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return pick().submit(wrap(task));
        }
    }

    /** 池的真实状态（给测试与排障读；⚠️ 不暴露成端点 —— 本段没有新增出口）。 */
    public ThreadPoolExecutor pool() {
        return dedicated.getThreadPoolExecutor();
    }
}
