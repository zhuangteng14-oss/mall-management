package com.minke.mallmanagement.observe.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 事件台账的**常驻驱动**（块 7 · 2026-10-17）—— 让 {@link EventBroadcaster#poll()} 与
 * 「此刻有没有人开着实时日志页」解耦。
 *
 * <h2>【它治的是什么：没人在看的那一段，事件根本不落库】</h2>
 * 改前 {@code poll()} 只有一个调用点 —— {@code EventStreamService.Session#frame}，也就是
 * <b>日志页那条 SSE 的流线程</b>。于是：
 * <ul>
 *   <li>没人打开日志页 ⇒ 合并环的游标不前进 ⇒ {@link EventBroadcaster#add} 不被调用
 *       ⇒ {@code EventPublisher} 收不到任何一条 ⇒ {@code observed_event} 空、Redis
 *       {@code events:latest} 空；</li>
 *   <li>演练现场最常见的用法恰恰是「在攻击模拟器页起一轮，跑完再回放」——
 *       那段演练就<b>一条也没落库</b>，回放出来是空的（TD-61 一族，2026-10-17 实测：
 *       批次 42 / 43 的 {@code total=0}，而 44–50 有人看着就有数据）。</li>
 * </ul>
 * 本类每秒替「所有观测者」驱动一次 {@code poll()}，于是**不管有没有页面开着**，
 * 事件都会走完「合并环 → MQ → {@code observed_event} + Redis」这条路。
 *
 * <h2>【为什么不用 {@code @Scheduled}】</h2>
 * 本仓有<b>调度器总闸</b>：{@code @EnableScheduling} 挂在一个被
 * {@code mall.downgrade.job.enabled} 关着的载体类上（a3 profile 下整台调度器不存在）。
 * 把「台账要不要落库」押在那枚与降级任务共用的开关上，会让本笔在 a3 下静默失效 ——
 * 那正是它要治的「静默缺失」。故这里与 {@code RabbitEventPublisher} 的探测线程同款：
 * <b>自建 daemon 线程</b>，不依赖任何调度基础设施。
 *
 * <h2>【与 SSE 流线程的关系：各调各的，不互相吃数据】</h2>
 * {@code poll()} 是 {@code synchronized} 的，且九个源环的游标（{@code sourceSeq}）在
 * {@link EventBroadcaster} 里按源只前进一次 ⇒ 本类与日志页的流线程谁先调都不会重复并入、
 * 也不会把对方要的数据吃掉。新开的 SSE 连接仍用 {@code since(-1)} 读合并环里留得下的
 * 最后一屏（≤2048 条），首帧快照语义<b>一个字没变</b>。
 *
 * <h2>【代价】</h2>
 * 每秒一次九环扫描 + 同步块（{@link EventBroadcaster#poll()} 原本就是每秒一次的量级），
 * 与是否有人观看无关。发布仍由 {@code RabbitEventPublisher} 做 fail-soft：MQ 不可达时
 * 直接返回（不碰网络），事件照旧留在内存环里给页面看。
 */
@Component
public class EventPersistPoller {

    private static final Logger log = LoggerFactory.getLogger(EventPersistPoller.class);

    private final EventBroadcaster broadcaster;

    /** 驱动间隔（毫秒）。与 SSE 的 1 秒一拍同量级；下限 100ms 只防「配成 0 把 CPU 打满」。 */
    private final long intervalMs;

    private volatile boolean running = true;
    private Thread thread;

    public EventPersistPoller(EventBroadcaster broadcaster,
                              @Value("${mall.observe.persist.interval-ms:1000}") long intervalMs) {
        this.broadcaster = broadcaster;
        this.intervalMs = Math.max(100L, intervalMs);
    }

    /**
     * 起 daemon 线程。⚠️ 只 start、不 join：与 {@code RabbitEventPublisher#startProbe} 同一条
     * 理由 —— 后端冷启动不该等任何人，且容器没走到 {@link #stop} 时非 daemon 线程会把
     * {@code mvn test} 的 JVM 拖住。
     */
    @PostConstruct
    void start() {
        thread = new Thread(() -> {
            while (running) {
                try {
                    broadcaster.poll();
                } catch (Throwable t) {
                    // 兜底：一次并入异常不能让常驻时钟停掉 —— 停了就退回「没人看就不落库」，
                    // 而且页面上完全看不出来（这正是 TD-61 的形状）。
                    log.warn("[EVENT] 常驻落库轮询异常（忽略，下轮继续）：{}", t.toString());
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "observe-event-persist");
        thread.setDaemon(true);
        thread.start();
        log.info("[EVENT] 常驻落库已启动：每 {} ms 驱动一次 EventBroadcaster.poll()"
                + "（与是否开着实时日志页无关；可用 mall.observe.persist.interval-ms 调整）", intervalMs);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
