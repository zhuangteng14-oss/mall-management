package com.minke.mallmanagement.observe.mq;

import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link EventPublisher} 的 RabbitMQ 实现（块 7 · commit 8c）—— **永不抛、不阻塞调用方超过一次
 * 连接尝试**地把一条事件投到 {@link RabbitObserveConfig#EXCHANGE}。
 *
 * <h2>【调点在 SSE 流线程上，这一条决定了本类的全部设计】</h2>
 * {@code EventBroadcaster#add} 由 {@code EventStreamService.frame} 调用，而后者跑在
 * <b>SSE 流线程</b>上（一秒一拍）。⇒ 发布这条路**慢一点就是页面卡一点**，而 MQ 不可达时的
 * 失败模式恰恰是「慢」：连接尝试要等到 {@code connection-timeout} 才抛。
 * 本类的两道防线：
 * <ol>
 *   <li><b>{@code connected} 闸</b>：已知连不上时 {@link #publish} <b>直接返回、一次网络都不碰</b>。
 *       这个状态由一个**独立的守护线程**每 5 秒实测一次（见 {@link #probe}）——
 *       「探测」与「发布」分开，是为了让「MQ 掉了」这件事最多花掉**一条**消息的等待，
 *       而不是每条消息都等一遍。</li>
 *   <li><b>有界的连接超时</b>：{@code spring.rabbitmq.connection-timeout}（dev profile 里配 2s）
 *       —— 没有它，Boot 的默认值是 60 秒，那不是「卡一下」而是「页面整整一分钟不动」。</li>
 * </ol>
 * ⚠️ 如实登记的**残留窗口**：MQ 掉线后的头 5 秒（探测还没跑到）里，逐条 {@code publish}
 * 会各自阻塞至多一个 connection-timeout。要彻底消除它得把发布也搬到独立线程（引入队列与
 * 顺序问题），本笔不走那一步 —— 批文要的是 fail-soft，不是「发布异步化」。
 *
 * <h2>【2026-10-17 订正：发布**已**异步化（上面那条「不走那一步」作废）】</h2>
 * 800 QPS 实测暴露了同步发布的第二个代价，比「掉线头 5 秒」严重得多：发布跑在
 * {@code EventBroadcaster#add} 里，而 {@code add} 只在 {@code synchronized poll()} 的临界区内
 * 被调用 ⇒ 每秒 800 条 {@code convertAndSend} 把 <b>broadcaster 的锁</b>占住约 5 秒，
 * 于是 {@code /events/stream} 的帧线程卡在同一个锁上：实测帧间隔从 1 秒变成 5~8 秒，
 * 页面上「看起来不更新」（探针产物 {@code 3a4d-backpressure-report.txt} 首跑即此现象）。
 * 这不是「页面渲染慢」，是**数据根本没到页面**。
 * ⇒ 现在 {@link #publish} 只做一次 {@code offer}（非阻塞），由单条守护线程
 * {@code observe-mq-publish} 按队列顺序逐条发；队列有界（{@value #PUBLISH_QUEUE_CAPACITY}），
 * 满了就丢并计数 —— 与 {@link EventPublisher} 的「不保证送达」契约一致，**页面优先**。
 * 顺序由单线程 + FIFO 保持；连接探测与发布仍是两条线程，互不阻塞。
 *
 * <h2>【{@code connected()} 是「实测」，不是「我刚才发成功过」】</h2>
 * 它由 {@link #probe} 用 {@code channel.isOpen()} 主动问 broker（拿连接、开通道、看它开不开），
 * 而不是「上次发布没抛异常」这种**间接证据**：发布是异步的（Publisher Confirms 只保证 broker
 * **收到**，不保证**路由到**），拿它当连接状态会把「exchange 名字写错」也读成「MQ 离线」。
 * 用户换一个常量试试时会因此查错方向。
 *
 * <h2>【持久化：{@code deliveryMode=2}】</h2>
 * 事件台账的价值是「进程重启后还查得到」，而非持久化的消息在 broker 重启时会消失 ⇒
 * 每条都标 persistent。⚠️ 这一条**必须**由发布方给：队列 durable 只管队列，不管消息。
 *
 * <h2>【⚠️ 周期性 WARN：本笔补的，起因是一次实测打脸】</h2>
 * 原先本类**只在翻转时**记日志。8c 验证时实测发现这留了个洞：broker 从启动起就不可达时，
 * {@code connected} 初值 false、探测每轮仍是 false ⇒ {@code now == was} 恒成立 ⇒
 * <b>整份启动日志里 {@code [EVENT]} 一条都没有</b>（实测：50KB 启动日志，串起来只有 Spring AMQP
 * 自己的 {@code AmqpIOException: SocketTimeoutException: Connect timed out} 重连栈）。
 * 而「一直不通」恰恰是最该被看见的场景（host 配错、broker 没起）。⇒ 失败也按间隔重复记：
 * <ul>
 *   <li><b>翻转必记</b>（从通到断）—— 原行为，一字不改；</li>
 *   <li><b>首条不受节流</b>：启动即不可达时立刻有声音（`unreachableWarned` 那个布尔就是为它）；</li>
 *   <li><b>之后每 {@code mall.observe.mq.warn-interval-ms}（默认 5 分钟）一条</b>。为什么不逐轮记：
 *       探测 5 秒一轮 ⇒ 一天约 1.7 万条（86400/5，算术），日志会被它淹没；5 分钟 ⇒ 一天 288 条，
 *       既不淹没又能一眼看出「还挂着」。</li>
 * </ul>
 * 记日志的线程是探测线程、**不在 SSE 流线程上** ⇒ 多记几条不会拖页面的帧。
 */
@Component
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    /** 实测间隔。5 秒 = 「页面上的点最多滞后 5 秒」与「探测线程的开销可忽略」之间的取舍。 */
    private static final long PROBE_INTERVAL_MS = 5_000L;

    /** 发布队列容量（条）。800 QPS × 10 秒的余量；满了丢弃（契约见 {@link EventPublisher}）。 */
    static final int PUBLISH_QUEUE_CAPACITY = 8192;

    /** 待发布事件（{@link #publish} 只 offer，守护线程按 FIFO 逐条发 —— 见类注释「2026-10-17 订正」）。 */
    private final BlockingQueue<ObservedEvent> publishQueue = new ArrayBlockingQueue<>(PUBLISH_QUEUE_CAPACITY);

    /** 队列满时丢掉的累计条数（只给日志用；`publish` 不因它抛异常）。 */
    private final AtomicLong droppedPublish = new AtomicLong();

    /** 发布线程的运行令旗（{@link #stopPublisher} 置 false 后，线程把队列收尾再退出）。 */
    private volatile boolean publishing = true;
    private Thread publishThread;

    private final RabbitTemplate rabbitTemplate;

    /** 上一次实测的结论。读在 SSE 流线程、写在探测线程 ⇒ volatile 语义就够（只要求「看得见」）。 */
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** 周期性 WARN 的间隔（纳秒，来自 `mall.observe.mq.warn-interval-ms`，默认 5 分钟）。
     *  ⚠️ 配 0 或负数 = 关掉节流、恢复「每轮探测都记」（即本笔修复之前的刷屏行为）—— 不另设开关，
     *  让「配小了会刷屏」这件事**看得见**，而不是被一个悄悄生效的下限掩盖。 */
    private final long warnIntervalNanos;

    /** 有没有记过「不可达」。⚠️ 与 {@link #lastWarnNanos} 配套，而不是拿 `lastWarnNanos == 0`
     *  表示「没记过」：{@code System.nanoTime()} 的基准是任意的（理论上可为负），0 不是安全哨兵
     *  —— 同 {@code DistributedLockService} 的「一个布尔 + 一个截止时刻」口径。 */
    private final AtomicBoolean unreachableWarned = new AtomicBoolean(false);

    /** 上一条「不可达」WARN 的纳秒时刻。写只在探测线程，读也只在探测线程 ⇒ 但由上面那个布尔
     *  的 happens-before 兜着（两处都在同一线程改，volatile 只是给「万一将来被别处读」留后路）。 */
    private volatile long lastWarnNanos;

    /** 探测线程的唯一停止信号（{@link #stopProbe} 置 false 并 interrupt）。 */
    private volatile boolean probing = true;
    private Thread probeThread;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate,
                                @Value("${mall.observe.mq.warn-interval-ms:300000}") long warnIntervalMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.warnIntervalNanos = TimeUnit.MILLISECONDS.toNanos(warnIntervalMs);
    }

    @Override
    public void publish(ObservedEvent event) {
        if (!connected.get()) {
            // 已知连不上：**一次网络都不碰**（理由见类注释）。⚠️ 这里刻意不记日志：
            // 这一路是「稳态失败」的常态路径，逐条记会把它变成刷屏；「连不上」这件事
            // 已经由探测线程记过（翻转那一刻 + 之后按间隔重复，见 refreshConnected），
            // 且页面上的点是黄的。
            return;
        }
        // ⚠️ 只入队、**不碰网络**：本方法在 `EventBroadcaster#add` 里被调，而那条路径
        //    跑在 `synchronized poll()` 的临界区内 —— 一旦在这里等待 broker，整条日志流
        //    都跟着卡住（800 QPS 实测帧间隔 5~8 秒，见类注释「2026-10-17 订正」）。
        if (!publishQueue.offer(event)) {
            long dropped = droppedPublish.incrementAndGet();
            // 只在第一条与每 10000 条记一次：队列满通常意味着 broker/网络整体变慢，
            // 逐条记会把日志刷得比事件本身还多。
            if (dropped == 1L || dropped % 10_000L == 0L) {
                log.warn("[EVENT] 发布队列已满（容量 {}），事件不落库（仍留在内存环里；累计丢弃 {} 条）",
                        PUBLISH_QUEUE_CAPACITY, dropped);
            }
        }
    }

    /** 真正发出：只在 {@code observe-mq-publish} 线程上调用（排队顺序 = 事件顺序）。 */
    private void send(ObservedEvent event) {
        try {
            rabbitTemplate.convertAndSend(RabbitObserveConfig.EXCHANGE, RabbitObserveConfig.ROUTING_KEY,
                    event,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    },
                    new CorrelationData(String.valueOf(event.seq())));
        } catch (Exception e) {
            // fail-soft（契约见 EventPublisher）：事件仍在内存合并环里，页面照看得到，只是不落库。
            // 不做重发：见 RabbitObserveConfig#observeRabbitTemplate 上那段（无幂等键，重发会写重）。
            log.warn("[EVENT] 发布失败（事件只留在内存环里，不落库）：seq={} kind={} cause={}",
                    event.seq(), event.kind(), e.toString());
        }
    }

    @Override
    public boolean connected() {
        return connected.get();
    }

    // ==================== 探测线程 ====================

    /**
     * 起一个 daemon 探测线程。⚠️ <b>启动期不阻塞</b>：这里只 start，不 join —— 后端冷启动时
     * MQ 若不可达，等一次 connection-timeout 是**探测线程**的事，不是 Spring 上下文的事。
     * <p>⚠️ daemon 的理由同本仓其它自建线程（{@code OrderStateExecutor} / {@code LockSceneService}）：
     * 容器没走到 {@link #stopProbe} 时，非 daemon 线程会把 JVM 拖住（{@code mvn test} 不退出）。
     */
    @PostConstruct
    void startProbe() {
        probeThread = new Thread(() -> {
            while (probing) {
                try {
                    refreshConnected();
                } catch (Throwable t) {
                    // 兜底：探测**绝不能**把这个线程打死（打死了 connected 会永远停在旧值，
                    // 而页面上那个点会一直画「已连接」—— 比没有这个点更坏）。
                    log.warn("[EVENT] MQ 探测出现意外异常（忽略，下轮继续）：{}", t.toString());
                }
                try {
                    Thread.sleep(PROBE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "observe-mq-probe");
        probeThread.setDaemon(true);
        probeThread.start();
    }

    @PreDestroy
    void stopProbe() {
        probing = false;
        if (probeThread != null) {
            probeThread.interrupt();
        }
    }

    /**
     * 起**唯一一条**发布线程（daemon）：按 FIFO 把队列里的条目交给 broker。
     *
     * <p>⚠️ daemon 的理由同探测线程：容器没走到 {@link #stopPublisher} 时不能把 JVM 拖住。
     * <p>⚠️ 收尾顺序（{@link #stopPublisher}）：先把令旗放下，线程醒后会把队列**排空再退**；
     * {@code join} 等 3 秒还不够才 interrupt —— 正常关停不该丢已经收下的事件。
     */
    @PostConstruct
    void startPublisher() {
        publishThread = new Thread(() -> {
            while (true) {
                ObservedEvent event;
                try {
                    // 运行中最多等 1 秒；收到停止令后不再等，只把队列里剩下的发完。
                    event = publishQueue.poll(publishing ? 1L : 0L, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (event == null) {
                    if (!publishing) {
                        return;
                    }
                    continue;
                }
                send(event);
            }
        }, "observe-mq-publish");
        publishThread.setDaemon(true);
        publishThread.start();
    }

    @PreDestroy
    void stopPublisher() {
        publishing = false;
        if (publishThread == null) {
            return;
        }
        try {
            publishThread.join(3_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (publishThread.isAlive()) {
            publishThread.interrupt();
        }
    }

    /**
     * 实测一次并记日志。**成功只记翻转**（同 {@code DistributedLockService} 的冷却窗口、
     * {@code L2HealthProbe} 的降级翻转那条口径）；**失败有两档**：翻转必记 + 稳态按
     * {@code mall.observe.mq.warn-interval-ms} 周期记（本笔加的，理由见类注释末节）。
     */
    private void refreshConnected() {
        boolean now = probe();
        boolean was = connected.getAndSet(now);
        if (now) {
            if (!was) {
                unreachableWarned.set(false);   // 通了 ⇒ 下一次失败重新走「首条」路径
                log.info("[EVENT] MQ 已连接：事件将落库（observed_event）+ 进 Redis events:latest"
                        + "（exchange={} queue={}）", RabbitObserveConfig.EXCHANGE, RabbitObserveConfig.QUEUE);
            }
            return;
        }
        long at = System.nanoTime();
        boolean flipped = was;                              // 从通到断：原行为，必须留
        boolean first = !unreachableWarned.get();           // 启动即不可达 / 上一轮之后第一次
        if (!flipped && !first && at - lastWarnNanos < warnIntervalNanos) {
            return;
        }
        unreachableWarned.set(true);
        lastWarnNanos = at;
        log.warn("[EVENT] MQ 不可达（{}）：事件**不落库**，只留在内存环里（页面上的 MQ 点会变黄）"
                        + "—— 检查 spring.rabbitmq.host/port/username/password（dev profile）",
                flipped ? "刚从「已连接」掉下来" : (first ? "启动即不可达" : "仍未恢复，按间隔重复"));
    }

    /**
     * 真问一次 broker：拿一条连接、开一个通道、看它开不开。
     * ⚠️ {@code execute} 在连接不可达时会阻塞到 {@code connection-timeout} —— 这是本方法
     * 必须只在探测线程上被调的原因（{@link #publish} 只读 {@code connected}，不走这里）。
     */
    private boolean probe() {
        try {
            Boolean open = rabbitTemplate.execute(channel -> channel.isOpen());
            return Boolean.TRUE.equals(open);
        } catch (Exception e) {
            return false;
        }
    }
}
