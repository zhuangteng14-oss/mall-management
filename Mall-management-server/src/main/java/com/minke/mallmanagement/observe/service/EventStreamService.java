package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.service.EventBroadcaster.Kind;
import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import com.minke.mallmanagement.observe.service.EventBroadcaster.Severity;
import com.minke.mallmanagement.observe.mq.EventPublisher;
import com.minke.mallmanagement.shared.web.TraceLog;
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
 *   <li>{@link EventBroadcaster#poll()}：把八环里新的并进合并环（**这是唯一推动合并环游标的地方**）；</li>
 *   <li>{@link EventBroadcaster#sinceWithLoss(long)}：取本拍要送出去的增量（游标从 {@code -1} 起
 *       ⇒ 首帧带**一屏**，理由见下），连同这次读丢掉的条数；</li>
 *   <li>算三个汇总：按类计数（8 类 chip）、按严重度计数（分布环图）、速率（速率折线）。</li>
 * </ol>
 *
 * <h2>【三个汇总都是**本拍**的，不是累计】</h2>
 * 页面自己攒窗口（保留最近 200 条卡片 + 60 个速率点）。理由：累计量在「环被覆盖」之后就
 * **不再等于事实**（本类口径与 {@link EventBroadcaster} 一致：覆盖即淘汰），而本拍增量永远是真数。
 * 页面要累计（如 8 类 chip 的「本次会话共 N 条」）就自己加 —— 那是页面的窗口语义，不是服务端的。
 *
 * <h2>【首帧为什么可能很大】</h2>
 * 冷启动时八个环各自可能已经攒了一屏（≤2048 × 8），首帧的 {@code events} 会把合并环
 * **留得下的最后 2048 条**一次送出（页面上限 200 张卡，多的丢弃）。这是**如实**：那些事件
 * 就是这一拍新并入的。之后每拍只送真正新发生的（正常是个位数）。
 * ⚠️ 首帧那一屏**不算「丢弃」**：那是本类的语义（新连接要一屏），不是跟丢（见
 * {@link EventBroadcaster#sinceWithLoss} 里的那条口径）。
 *
 * <h2>【每连接一份】</h2>
 * 本类是单例，但**帧源不是**：{@link #newFrameSource()} 每条连接现建一份 {@link Session}，
 * 三个游标（事件 / 请求 / 上一次真算的时刻）与丢弃累计**都装在会话里**。理由与
 * {@link OrderStreamService} 同款：读增量**有副作用**（把游标往前推），两条连接共用一份状态时
 * 先算的那条会把后算的那条的数据吃掉 —— 页面看起来像死机。
 * <p>⚠️ 改前这里靠 {@code FramePacer} 把产帧压成「每半拍至多一次」来绕这个坑（代价是相位差小于
 * 半拍的连接会收到重复内容）。commit 10 的「丙」把游标按连接切开后，那个窗口**连同它的类一起删了**
 * （{@code FramePacer.java} / {@code FramePacerTest.java} 已不存在）。
 *
 * <p>⚠️ 会话里的字段**不需要** {@code volatile} / {@code synchronized}：{@code frame} 只被
 * 自己那条连接的流线程调用，状态不共享（{@link EventBroadcaster} / {@link TraceLog} 各自并发安全）。
 */
@Component
public class EventStreamService {

    private final EventBroadcaster broadcaster;

    /**
     * 事件台账的出口（块 7 · commit 8c）—— 本类只用它的 {@code connected()} 给帧里的 {@code mq}
     * 字段取值。⚠️ **本类不发布任何东西**：发布在 {@link EventBroadcaster#add} 里（唯一调点）。
     */
    private final EventPublisher publisher;

    public EventStreamService(EventBroadcaster broadcaster, EventPublisher publisher) {
        this.broadcaster = broadcaster;
        this.publisher = publisher;
    }

    /** 现建一份「这条连接自己的」帧源（见类注释「每连接一份」）。 */
    public SseStreamWriter.FrameSource newFrameSource() {
        return new Session();
    }

    /** 一条连接的全部可变状态。 */
    private final class Session implements SseStreamWriter.FrameSource {

        /** 已从合并环读走的 seq；-1 = 还没读过（首帧送一屏）。 */
        private long sentSeq = -1L;

        /** 已从 {@link TraceLog} 读走的 seq；口径同 {@link #sentSeq}（-1 = 首帧送一屏）。 */
        private long sentTraceSeq = -1L;

        /** 上一次**真算**的时刻（速率的分母）；0 = 还没算过（首拍速率报 0，不报无穷）。 */
        private long lastRealNanos;

        /** 这条连接**自己**跟丢的累计条数（合并环 → 这条连接那一路，口径见 {@link EventBroadcaster}）。 */
        private long droppedTotal;

        /**
         * 产一拍（**在流线程上被调用**，每条连接自己的那条）。
         *
         * @param tick 第几拍（从 0 起），原样回填
         */
        @Override
        public Map<String, Object> frame(long tick) {
            long now = System.nanoTime();

            broadcaster.poll();
            long cursor = broadcaster.latestSeq();
            EventBroadcaster.Batch fresh = broadcaster.sinceWithLoss(sentSeq);
            long deltaNanos = lastRealNanos == 0L ? 0L : now - lastRealNanos;
            lastRealNanos = now;
            sentSeq = cursor;
            droppedTotal += fresh.dropped();

            Map<String, Object> byKind = new LinkedHashMap<>();
            for (Kind k : Kind.values()) {
                byKind.put(k.tag, 0);
            }
            Map<String, Object> bySeverity = new LinkedHashMap<>();
            for (Severity s : Severity.values()) {
                bySeverity.put(s.tag, 0);
            }
            List<Map<String, Object>> events = new ArrayList<>(fresh.events().size());
            for (ObservedEvent e : fresh.events()) {
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
            // ⚠️ D3：改用 usable()（= broker 连着 **且** 未被演练注入断开）。mqDown 注入只拦
            //    发布侧 ⇒ 此时事件确实不落库，若仍用 connected() 会让日志页写「已连接」，
            //    与指标页 mq.up=0 及「不落库」告警互相矛盾。
            frame.put("mq", publisher.usable());
            // 块 7 · commit 10（裁决 2）：环满丢了多少。三个字段**口径不同，页面别混用**：
            //   dropped      本拍这条连接丢的条数（>0 ⇒ 这条页面的时间线有洞，且洞就在这一拍）
            //   droppedTotal 这条连接自打开以来累计丢的条数（会话级，页面重连后从 0 起）
            //   droppedIngest 源环→合并环 的累计（**全进程**口径，与上面两个不是一回事）
            frame.put("dropped", fresh.dropped());
            frame.put("droppedTotal", droppedTotal);
            frame.put("droppedIngest", broadcaster.droppedIngest());
            return frame;
        }
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
        // 块 7 · 阶段 3a.3 新增三键（帧 11 → 14）+ 第二步再增一键（帧 14 → 15）。
        // ⚠️ 四者的口径都在 ObservedEvent 的 javadoc 里：
        //   actor      「**谁**做的」—— 拼成 {id, role} 对象；无身份时**整个 actor 为 null**
        //              （不是 {id:null,role:null}：那是「有一个身份，但它的两个字段都空」，
        //               与「根本没有身份」在页面上是两句话，不能混）
        //   durationUs 本条事件主体的耗时（微秒）；null = 没测到 / 不适用（逐环口径见各 Metrics 类）
        //   remaining  扣减那一刻该 SKU 的剩余（件，块 7 · 阶段 3a.3 第二步新增）——
        //              **只有库存环的扣减档**有值，其余八环与库存环的释放档恒为 null
        //              （口径与三类 null 见 StockMetrics.StockEvent#remaining()）
        //   fields     结构化明细（与 detail 同源）；null 时前端回落到 detail
        // ⚠️ 顺序刻意排在这里（batchId 之后）：前 11 个键的名字与顺序一个字没动 ——
        //    既有前端与探针按位置或按名读都照旧；本步新增的 remaining 也排在**末尾那三键
        //    之前**（即 actor / durationUs 之后、fields 之前）：fields 是那只「袋子」，
        //    让它继续待在最后一位（袋子一挪，靠位置读它的调用方就全错位了）。
        row.put("actor", actorMap(e.actorId(), e.actorRole()));
        row.put("durationUs", e.durationUs());
        row.put("remaining", e.remaining());
        row.put("fields", e.fields());
        return row;
    }

    /**
     * {@code actor} 的**现状**：{@code {id, role}}，没有身份时返回 {@code null}。
     *
     * <p>⚠️ 为什么是对象而不是两个平铺的键（块 7 · 阶段 3a.3 裁定 ②）：这两个值**同生共死**
     * —— 要么都有要么都没有。平铺成 {@code actorId} / {@code actorRole} 时，「两个都是 null」
     * 与「忘了填」在页面上长得一模一样，而对象形态下 {@code actor === null} 是一个可判定的
     * 事实（前端一句 {@code e.actor ? ... : '系统'} 就够）。
     *
     * <p>⚠️ {@code role} 是**角色号**（{@code Integer}），不是角色名 —— 名字由前端查表。
     */
    private static Map<String, Object> actorMap(String actorId, Integer actorRole) {
        if (actorId == null && actorRole == null) {
            return null;
        }
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("id", actorId);
        actor.put("role", actorRole);
        return actor;
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
