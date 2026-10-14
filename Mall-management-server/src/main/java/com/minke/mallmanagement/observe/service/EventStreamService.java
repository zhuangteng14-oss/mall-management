package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.service.EventBroadcaster.Kind;
import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import com.minke.mallmanagement.observe.service.EventBroadcaster.Severity;
import com.minke.mallmanagement.observe.mq.EventPublisher;
import com.minke.mallmanagement.shared.web.TraceLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时日志页（{@code POST /admin/observe/events/stream}）一拍一帧的产地 ——
 * 「本拍新发生的」事件 + 三个给图用的汇总。
 *
 * <h2>【一拍三件事】</h2>
 * <ol>
 *   <li>{@link EventBroadcaster#poll()}：把八环里新的并进合并环（**这是唯一推动游标的地方**）；</li>
 *   <li>{@link EventBroadcaster#since(long)}：取本拍要送出去的增量（游标从 {@code -1} 起
 *       ⇒ 首帧带**一屏**，理由见下）；</li>
 *   <li>算三个汇总：按类计数（8 类 chip）、按严重度计数（分布环图）、速率（速率折线）。</li>
 * </ol>
 *
 * <h2>【三个汇总都是**本拍**的，不是累计】</h2>
 * 页面自己攒窗口（保留最近 200 条卡片 + 60 个速率点）。理由：累计量在「环被覆盖」之后就
 * **不再等于事实**（本类口径与 {@link EventBroadcaster} 一致：覆盖即淘汰），而本拍增量永远是真数。
 * 页面要累计（如 8 类 chip 的「本次会话共 N 条」）就自己加 —— 那是页面的窗口语义，不是服务端的。
 *
 * <h2>【首帧为什么可能很大】</h2>
 * 冷启动时八个环各自可能已经攒了一屏（≤512 × 8），首帧的 {@code events} 会把合并环
 * **留得下的最后 512 条**一次送出（页面上限 200 张卡，多的丢弃）。这是**如实**：那些事件
 * 就是这一拍新并入的。之后每拍只送真正新发生的（正常是个位数）。
 *
 * <h2>【一拍只算一次】</h2>
 * 同 {@link MetricsSnapshotService}（见 {@link FramePacer}）：两个页面同时开时，
 * 后调用的那条不会「把事件读走」而让另一条看着像死机。
 */
@Component
public class EventStreamService {

    private final EventBroadcaster broadcaster;
    private final FramePacer pacer;

    /**
     * 事件台账的出口（块 7 · commit 8c）—— 本类只用它的 {@code connected()} 给帧里的 {@code mq}
     * 字段取值。⚠️ **本类不发布任何东西**：发布在 {@link EventBroadcaster#add} 里（唯一调点）。
     */
    private final EventPublisher publisher;

    /** 已送出到合并环的哪个 seq；-1 = 还没送过（首帧送一屏）。 */
    private long sentSeq = -1L;

    /** 已送出到 {@link TraceLog} 的哪个 seq；口径同 {@link #sentSeq}（-1 = 首帧送一屏）。 */
    private long sentTraceSeq = -1L;

    /** 上一次**真算**的时刻（速率的分母）；0 = 还没算过（首拍速率报 0，不报无穷）。 */
    private long lastRealNanos;

    public EventStreamService(EventBroadcaster broadcaster, EventPublisher publisher,
                              @Value("${mall.observe.sse.interval-ms:1000}") long intervalMs) {
        this.broadcaster = broadcaster;
        this.publisher = publisher;
        this.pacer = new FramePacer(intervalMs);
    }

    /**
     * 产一拍（**在流线程上被调用**）。
     *
     * @param tick 第几拍（从 0 起），原样回填
     */
    public synchronized Map<String, Object> frame(long tick) {
        long now = System.nanoTime();
        if (pacer.reusable(now)) {
            return pacer.reuse(tick);
        }

        broadcaster.poll();
        long cursor = broadcaster.latestSeq();
        List<ObservedEvent> fresh = broadcaster.since(sentSeq);
        long deltaNanos = lastRealNanos == 0L ? 0L : now - lastRealNanos;
        lastRealNanos = now;
        sentSeq = cursor;

        Map<String, Object> byKind = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            byKind.put(k.tag, 0);
        }
        Map<String, Object> bySeverity = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            bySeverity.put(s.tag, 0);
        }
        List<Map<String, Object>> events = new ArrayList<>(fresh.size());
        for (ObservedEvent e : fresh) {
            bump(byKind, e.kind());
            bump(bySeverity, e.severity());
            events.add(asMap(e));
        }

        // 本拍新走完的请求（commit 8）：与 events 同口径的增量 —— 页面的 traceId 抽屉靠它把
        // 「这一次请求的起止与总耗时」与事件卡片上的 traceId 对上。⚠️ 长连接 /stream 自己不记
        // （见 TraceLog.SKIP_SUFFIX），否则总耗时被这条永不结束的连接污染。
        List<TraceLog.RequestTrace> freshTraces = TraceLog.since(sentTraceSeq);
        List<Map<String, Object>> traces = new ArrayList<>(freshTraces.size());
        for (TraceLog.RequestTrace t : freshTraces) {
            traces.add(asTraceMap(t));
        }
        sentTraceSeq = TraceLog.latestSeq();

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("seq", tick);
        frame.put("ts", System.currentTimeMillis());
        frame.put("cursor", cursor);
        frame.put("count", events.size());
        frame.put("rate", rate(events.size(), deltaNanos));
        frame.put("counts", byKind);
        frame.put("severity", bySeverity);
        frame.put("events", events);
        frame.put("traces", traces);
        // 块 7 · commit 8c：MQ 桥接状态（true = 事件会落库 + 进 Redis）。页面上那个黄点读它 ——
        // 它是**实测**值（探测线程每 5 秒问一次 broker，见 RabbitEventPublisher），
        // 不是「本拍有没有发布成功」：后者会把「exchange 名字写错」也画成「MQ 离线」。
        frame.put("mq", publisher.connected());
        pacer.remember(frame, now);
        return frame;
    }

    private static Double rate(int count, long deltaNanos) {
        if (deltaNanos <= 0L) {
            return 0.0;
        }
        return Math.round(count / (deltaNanos / 1_000_000_000.0) * 1000.0) / 1000.0;
    }

    private static Map<String, Object> asMap(ObservedEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", e.seq());
        row.put("ts", e.epochMilli());
        row.put("kind", e.kind());
        row.put("severity", e.severity());
        row.put("title", e.title());
        row.put("detail", e.detail());
        row.put("key", e.key());
        row.put("objectType", e.objectType());
        row.put("objectId", e.objectId());
        row.put("traceId", e.traceId());
        // 块 7 · commit 8c：演练批次（日常流量为 null）。页面的 traceId 抽屉按它把
        // 「一次演练里的全部事件」与日常流量分开 —— 只读展示，不参与筛选。
        row.put("batchId", e.batchId());
        return row;
    }

    /** 一条「请求的起止」—— 实时日志页的 traceId 抽屉拿它算总耗时、画链路骨架。 */
    private static Map<String, Object> asTraceMap(TraceLog.RequestTrace t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", t.seq());
        row.put("traceId", t.traceId());
        row.put("method", t.method());
        row.put("path", t.path());
        row.put("status", t.status());
        row.put("startMs", t.startMs());
        row.put("endMs", t.endMs());
        row.put("ms", t.ms());
        return row;
    }

    private static void bump(Map<String, Object> counts, String key) {
        counts.put(key, (Integer) counts.getOrDefault(key, 0) + 1);
    }
}
