package com.minke.mallmanagement.observe.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计观测 —— 审计写入面的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * <p>《块 7 参考》§4.5 五套新事件环之一（AuditMetrics —— 审计写入 / 失败），
 * 与 {@code MallCacheMetrics} / {@code MallLockMetrics} / {@code MallOrderStateMetrics} 同款：
 * <b>512 槽事件环</b> + 计数，两条通道在<b>同一调用点</b>一起发。抽环与否的论证同
 * {@link StockMetrics} 类注释（不重抄）。
 *
 * <h2>【本环只有一个 kind ⇒ 事件里不设 kind 字段】</h2>
 * 「审计写入」只有一件事（写一条审计行），成/败由 {@code result} 表达 ⇒ 每条事件再带一个
 * 恒定 {@code kind="write"} 是重复信息。<b>事件类别由事件所属的环表达</b>（消费侧按环打标签），
 * 不是每条事件里重复一遍。{@code StockMetrics} 有 deduct / release 两种 kind，故那里保留字段。
 *
 * <h2>【命名规范】</h2>
 * 前缀 {@code mall.}、<b>基数铁律</b>（tag 只用有界取值、业务键绝不进 tag）、
 * 结果类维度统一叫 {@code result}、可派生的值不重复埋点。
 * ⚠️ {@code actor}（业务号）与 {@code action}（动作名，常量表见 {@code admin/AuditActions.java}）
 * <b>都不进 tag</b>：前者基数无界；后者虽然是个封闭常量表，但条数以十计，
 * 做 tag 会让时间序列数随动作表增长而增长 —— 而「哪个动作被记了几次」用事件流回答更准。
 *
 * <h2>【指标清单】</h2>
 * <pre>
 * mall.audit.writes{result=written|failed}
 *        一次审计写入的结果，两路互斥且完备：
 *        written = 审计行落库成功；
 *        failed  = 写入失败（**fail-open**：调用方照常返回业务结论，失败只留痕）。
 * </pre>
 * 不埋「审计总数」：{@code written + failed} 就是它（规范第 6 条：可派生的值不重复埋点）。
 * 不埋「谁写的」：那是事件的 {@code actor} 字段。
 *
 * <h2>【事件语法】</h2>
 * <pre>
 *   result=written|failed   actor=业务号   action=动作名   reason=失败原因（written 时为 null）
 * </pre>
 *
 * <h2>【本环的写入点（已被 commit 2b 接上）：块 1 的 {@code AuditService}】</h2>
 * commit 2 当时只建出口、把写入点挂在块 7 自己的代码上；**commit 2b 改走真观测点**：
 * {@code AuditService.record} 的 try / catch 两路各记一条（written / failed）——
 * 那条 fail-open 的失败**只有**本环看得见。在块 1 的代码里只加「发事件」一步，判据与返回不动。
 * ⚠️ pack §约束「块 1 的通知与审计照抄不扩展」经用户裁决放宽为「不扩展行为、允许在既有写入点补
 * 观测出口」⇒ 本条<b>不再是</b> B 档自裁。
 * 下一笔的攻击引擎仍**经这些真路径**发流量（真提交动作、真写审计行），不再自己记结果。
 *
 * <h2>【热路径代价】</h2>
 * 同既有三环：registry 查表 + LongAdder 自增 + 一次数组赋值 + 一个小对象；无锁、无 I/O、
 * <b>无日志</b>（agent.md 第 7 条）。本类只记「已发生的事实」，不参与任何决策、不抛异常。
 */
@Component
public class AuditMetrics {

    // ===== 指标名：审计面唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String WRITES = "mall.audit.writes";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。与既有三环同值。 */
    private static final int EVENT_CAPACITY = 512;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /** 事件环形缓冲与单调游标；写者只做一次数组赋值，读者靠 {@code seq} 自校验。 */
    private final AuditEvent[] events = new AuditEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    public AuditMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次<b>成功</b>的审计写入。
     *
     * @param actor  业务号。⚠️ 口径同 {@code AuditService}：取<b>会话</b>、不是入参
     *               （入参由调用方给 ⇒ 可伪造）。只进事件流，绝不进 tag
     * @param action 动作名（{@code AuditActions} 里的常量）
     */
    public void written(String actor, String action) {
        registry.counter(WRITES, "result", Result.WRITTEN.tag).increment();
        event(Result.WRITTEN.tag, actor, action, null);
    }

    /**
     * 一次<b>失败</b>的审计写入（fail-open：调用方照常返回业务结论，这里只留痕）。
     *
     * @param reason 失败原因（异常摘要）。⚠️ 只进事件流 —— 它是自由文本，基数无界
     */
    public void failed(String actor, String action, String reason) {
        registry.counter(WRITES, "result", Result.FAILED.tag).increment();
        event(Result.FAILED.tag, actor, action, reason);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序。并发口径同 {@link StockMetrics#recentEvents()}：
     * 每个槽位用 {@code e.seq() == seq} 自校验，对不上就跳过（宁可少一条，不给出一条错位的）。
     *
     * <p>本笔<b>不</b>把它对外暴露（事件流端点属下一笔）；目前只被单测调用。
     */
    public List<AuditEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<AuditEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            AuditEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /** 追加一条事件。{@code actor} / {@code reason} 只在事件里以字符串形式出现 —— tag 里绝不能有它们。 */
    private void event(String result, String actor, String action, String reason) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new AuditEvent(
                seq, System.currentTimeMillis(), result,
                actor == null ? null : String.valueOf(actor),
                action == null ? null : String.valueOf(action), reason);
    }

    /**
     * 一条审计事件。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param result     written | failed
     * @param actor      谁写的（业务号，会话取值）
     * @param action     动作名
     * @param reason     失败原因；{@code written} 时为 null
     */
    public record AuditEvent(long seq, long epochMilli, String result,
                             String actor, String action, String reason) {
    }

    /** {@code mall.audit.writes} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Result {
        /** 审计行落库成功。 */
        WRITTEN("written"),
        /** 写入失败（fail-open，只留痕）。 */
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
