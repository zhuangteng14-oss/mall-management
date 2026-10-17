package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.shared.web.TraceIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知观测 —— 站内通知发送面的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * <p>《块 7 参考》§4.5 五套新事件环之一（NotifyMetrics —— 通知发送 / 失败），
 * 与 {@code MallCacheMetrics} / {@code MallLockMetrics} / {@code MallOrderStateMetrics} 同款：
 * <b>2048 槽事件环</b> + 计数，两条通道在<b>同一调用点</b>一起发。抽环与否的论证同
 * {@link StockMetrics} 类注释（不重抄）；「只有一个 kind ⇒ 事件里不设 kind 字段」的理由同
 * {@link AuditMetrics} 类注释（不重抄）。
 *
 * <h2>【命名规范】</h2>
 * 前缀 {@code mall.}、<b>基数铁律</b>（tag 只用有界取值、业务键绝不进 tag）、
 * 结果类维度统一叫 {@code result}、可派生的值不重复埋点。
 * ⚠️ {@code toType}（收件人类型）与 {@code eventType}（通知类型，
 * 常量表见 {@code notification/NotificationEvents.java}）<b>都不进 tag</b>：
 * 前者虽是小整数域，但本环的写入点要等到攻击引擎落地，届时若真要按维度切分再按
 * 基数铁律补（取值必须有界且封闭）；后者是条数以十计的登记簿，做 tag 会让序列数随它增长。
 *
 * <h2>【指标清单】</h2>
 * <pre>
 * mall.notify.sends{result=sent|failed}
 *        一次通知发送的结果，两路互斥且完备：
 *        sent   = 通知已投递（{@code NotificationService.send} 的失败是 **fail-soft** 的：
 *                 它不抛异常、不挡调用方，故「失败」只能在这里显式记一笔）；
 *        failed = 发送失败（收件人不存在 / 写通知行失败等）。
 * </pre>
 * 不埋「通知总数」：{@code sent + failed} 就是它（规范第 6 条：可派生的值不重复埋点）。
 *
 * <h2>【事件语法】</h2>
 * <pre>
 *   result=sent|failed   toType=收件人类型   eventType=通知类型   key=收件人业务号   reason=失败原因
 * </pre>
 *
 * <h2>【本环的写入点（已被 commit 2b 接上）：块 1 的 {@code NotificationService}】</h2>
 * commit 2 当时只建出口；**commit 2b 改走真观测点**：{@code NotificationService.send} 的
 * 返回 true 之前记 {@code sent}、catch 里记 {@code failed}（那一路 fail-soft、不外抛，
 * 失败**只有**本环看得见）。在块 1 的代码里只加「发事件」一步，判据与返回不动。
 * ⚠️ pack §约束「照抄不扩展」经用户裁决放宽为「不扩展行为、允许在既有写入点补观测出口」
 * ⇒ 本条<b>不再是</b> B 档自裁；下一笔的攻击引擎仍**经真发送路径**发流量，不自己记结果。
 *
 * <h2>【热路径代价】</h2>
 * 同既有三环：registry 查表 + LongAdder 自增 + 一次数组赋值 + 一个小对象；无锁、无 I/O、
 * <b>无日志</b>（agent.md 第 7 条）。本类只记「已发生的事实」，不参与任何决策、不抛异常。
 */
@Component
public class NotifyMetrics {

    // ===== 指标名：通知面唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String SENDS = "mall.notify.sends";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。与既有三环同值。 */
    private static final int EVENT_CAPACITY = 2048;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /** 事件环形缓冲与单调游标；写者只做一次数组赋值，读者靠 {@code seq} 自校验。 */
    private final NotifyEvent[] events = new NotifyEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    public NotifyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次<b>成功</b>的通知投递。
     *
     * @param toType    收件人类型（与 {@code NotificationService.send} 的第三个形参同口径）
     * @param eventType 通知类型（{@code NotificationEvents} 里的常量）
     * @param key       收件人业务号（{@code toId}）。⚠️ 只进事件流，绝不进 tag（基数无界）
     * @param durationNanos 墙钟（块 7 · 阶段 3a.3 新增）：<b>从 {@code NotificationService.send}
     *                   入口到通知行入库</b>（含组装），见该类注释
     */
    public void sent(String toType, String eventType, String key, long durationNanos) {
        registry.counter(SENDS, "result", Result.SENT.tag).increment();
        event(Result.SENT.tag, toType, eventType, key, null, durationNanos);
    }

    /**
     * 一次<b>失败</b>的通知发送。
     *
     * <p>⚠️ {@code NotificationService.send} 是 fail-soft 的（失败不抛、不挡调用方）⇒
     * 「失败率」这个数<b>只有</b>从本环读得到，别处没有。
     *
     * @param reason 失败原因（异常摘要 / 落库失败说明）。只进事件流 —— 自由文本、基数无界
     * @param durationNanos 墙钟（块 7 · 阶段 3a.3 新增）；失败路径止于 catch（「到抛出为止」）
     */
    public void failed(String toType, String eventType, String key, String reason, long durationNanos) {
        registry.counter(SENDS, "result", Result.FAILED.tag).increment();
        event(Result.FAILED.tag, toType, eventType, key, reason, durationNanos);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序。并发口径同 {@link StockMetrics#recentEvents()}：
     * 每个槽位用 {@code e.seq() == seq} 自校验，对不上就跳过。
     *
     * <p>本笔<b>不</b>把它对外暴露（事件流端点属下一笔）；目前只被单测调用。
     */
    public List<NotifyEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<NotifyEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            NotifyEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /** 追加一条事件。{@code key} / {@code reason} 只在事件里以字符串形式出现 —— tag 里绝不能有它们。 */
    private void event(String result, String toType, String eventType, String key, String reason,
                       Long durationNanos) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new NotifyEvent(
                seq, System.currentTimeMillis(), result,
                toType == null ? null : String.valueOf(toType),
                eventType == null ? null : String.valueOf(eventType),
                key == null ? null : String.valueOf(key), reason,
                TraceIdFilter.current(), TraceIdFilter.currentBatch(),
                TraceIdFilter.currentActorId(), TraceIdFilter.currentActorRole(),
                durationNanos == null ? null : durationNanos / 1000);
    }

    /**
     * 一条通知事件。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param result     sent | failed
     * @param toType     收件人类型
     * @param eventType  通知类型
     * @param key        收件人业务号
     * @param reason     失败原因；{@code sent} 时为 null
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；不在请求线程上时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（块 7 · commit 8c，来自
     *                   {@code X-Drill-Batch} 头）；日常流量为 {@code null}。同 {@code traceId} 现读
     * @param actorId    发起那次请求的业务号（块 7 · 阶段 3a.3 新增）；无身份时为 null
     * @param actorRole  发起者的角色号（块 7 · 阶段 3a.3 新增）；无身份时为 null
     * @param durationUs 发送耗时（**微秒**，块 7 · 阶段 3a.3 新增）；{@code null} = 没测到
     */
    public record NotifyEvent(long seq, long epochMilli, String result, String toType,
                              String eventType, String key, String reason, String traceId, String batchId,
                              String actorId, Integer actorRole, Long durationUs)
            implements com.minke.mallmanagement.observe.metrics.Sourced {
    }

    /** {@code mall.notify.sends} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Result {
        /** 通知已投递。 */
        SENT("sent"),
        /** 发送失败（fail-soft：调用方不受影响，失败只在这里留痕）。 */
        FAILED("failed");

        final String tag;

        Result(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界）。公开的理由同 {@code MallLockMetrics.Outcome#tag()}。 */
        public String tag() {
            return tag;
        }
    }
}
