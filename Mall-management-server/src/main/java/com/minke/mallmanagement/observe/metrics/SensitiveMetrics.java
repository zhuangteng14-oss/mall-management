package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.shared.web.TraceIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 敏感词观测 —— 内容安检面的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * <p>《块 7 参考》§4.5 五套新事件环之一（SensitiveMetrics —— 敏感词打码 / 拒收），
 * 与 {@code MallCacheMetrics} / {@code MallLockMetrics} / {@code MallOrderStateMetrics} 同款：
 * <b>512 槽事件环</b> + 计数，两条通道在<b>同一调用点</b>一起发。抽环与否的论证同
 * {@link StockMetrics} 类注释；「只有一个 kind ⇒ 事件里不设 kind 字段」的理由同
 * {@link AuditMetrics} 类注释（都不重抄）。
 *
 * <h2>【本环记的是<b>处置结果</b>，不是「发现没发现」】</h2>
 * 块 6 的内容安检对同一段文本有两档处置（两阈值）：命中率低于阈值 ⇒ <b>打码</b>放行；
 * 达到阈值 ⇒ <b>拒收</b>。本环就这两个取值 —— 干净文本（零命中）<b>不记</b>：
 * 它不是「防护起了作用」，把它记进来会把「拦住了多少」稀释成「扫了多少」。
 * 「扫了多少」这类吞吐由调用侧自己的计数回答（规范第 6 条：可派生的值不重复埋点）。
 *
 * <h2>【命名规范】</h2>
 * 前缀 {@code mall.}、<b>基数铁律</b>（tag 只用有界取值、业务键绝不进 tag）、
 * 结果类维度统一叫 {@code result}、可派生的值不重复埋点。
 * ⚠️ {@code scene}（场景：评论 / 举报 / 昵称…）<b>不进 tag</b>：它的取值表在块 6 手里、
 * 本环不假定它封闭；同样的信息在事件的 {@code scene} 字段里，按场景切片是消费侧的事。
 *
 * <h2>【指标清单】</h2>
 * <pre>
 * mall.sensitive.checks{result=masked|rejected}
 *        一次内容安检的**处置**结果，两路互斥且完备：
 *        masked   = 命中率低于阈值 ⇒ 打码后放行；
 *        rejected = 命中率达到阈值 ⇒ 整条拒收。
 * </pre>
 * 不埋命中率本身：它是事件里 {@code hit / total} 的派生量（规范第 6 条）。
 *
 * <h2>【事件语法】</h2>
 * <pre>
 *   result=masked|rejected   scene=安检场景   hit=命中词数   total=参与判定的词数
 * </pre>
 *
 * <h2>【本环的写入点（已被 commit 2b 接上）：块 6 的 {@code SensitiveWordService}】</h2>
 * commit 2 当时只建出口；**commit 2b 改走真观测点**：{@code filter(...)} 在拒收 / 打码
 * 两档处置后各记一条（干净文本在三处早退里，压根到不了那一行 ⇒ 天然不记，与本类口径一致）。
 * 在块 6 的代码里只加「发事件」一步：不动它的阈值判定、不动返回的四个键、不改端点与契约。
 * ⚠️ pack §约束「块 7 不碰块 6 的表 / 端点 / 代码」经用户裁决放宽为「表 / 端点 / 契约不碰，
 * 允许在既有写入点补一行观测出口」⇒ 本条<b>不再是</b> B 档自裁。
 *
 * <h2>【热路径代价】</h2>
 * 同既有三环：registry 查表 + LongAdder 自增 + 一次数组赋值 + 一个小对象；无锁、无 I/O、
 * <b>无日志</b>（agent.md 第 7 条）。本类只记「已发生的事实」，不参与任何决策、不抛异常
 * —— 特别是：本类<b>不</b>做阈值判定，两档处置由内容安检侧定，本环只接收结论。
 */
@Component
public class SensitiveMetrics {

    // ===== 指标名：内容安检面唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String CHECKS = "mall.sensitive.checks";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。与既有三环同值。 */
    private static final int EVENT_CAPACITY = 512;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /** 事件环形缓冲与单调游标；写者只做一次数组赋值，读者靠 {@code seq} 自校验。 */
    private final SensitiveEvent[] events = new SensitiveEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    public SensitiveMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次<b>打码放行</b>的处置（命中率低于阈值）。
     *
     * @param scene 安检场景（评论 / 举报 / 昵称…）。⚠️ 只进事件流，绝不进 tag
     * @param hit   命中的敏感词数
     * @param total 参与本次判定的词数（{@code hit / total} 就是那个被拿去比阈值的命中率）
     * @param words <b>命中的那些词</b>（块 7 · commit 8 新增，逗号分隔，已去重、已截断）——
     *              以前只记「命中几个」，实时日志页答不出「命中了什么」这个最该看的数。
     *              ⚠️ 只进事件流，绝不进 tag（它是**词表内容**，基数无界且可能含攻击者构造的长串）
     */
    public void masked(String scene, int hit, int total, String words) {
        registry.counter(CHECKS, "result", Result.MASKED.tag).increment();
        event(Result.MASKED.tag, scene, hit, total, words);
    }

    /**
     * 一次<b>拒收</b>的处置（命中率达到阈值）。
     *
     * @param scene 安检场景（只进事件流，理由同 {@link #masked}）
     * @param words ／同 {@link #masked}
     */
    public void rejected(String scene, int hit, int total, String words) {
        registry.counter(CHECKS, "result", Result.REJECTED.tag).increment();
        event(Result.REJECTED.tag, scene, hit, total, words);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序。并发口径同 {@link StockMetrics#recentEvents()}：
     * 每个槽位用 {@code e.seq() == seq} 自校验，对不上就跳过。
     *
     * <p>被 {@code EventBroadcaster}（块 7 · commit 3 的三条 SSE 端点）与单测共同调用。
     */
    public List<SensitiveEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<SensitiveEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            SensitiveEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /** 追加一条事件。{@code scene} 只在事件里以字符串形式出现 —— tag 里绝不能有它。 */
    private void event(String result, String scene, int hit, int total, String words) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new SensitiveEvent(
                seq, System.currentTimeMillis(), result,
                scene == null ? null : String.valueOf(scene), hit, total, words,
                TraceIdFilter.current(), TraceIdFilter.currentBatch());
    }

    /**
     * 一条内容安检事件。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param result     masked | rejected
     * @param scene      安检场景
     * @param hit        命中词数
     * @param total      参与判定的词数
     * @param words      命中的词（块 7 · commit 8 新增，逗号分隔；一个都没命中时为 null）
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；不在请求线程上时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（块 7 · commit 8c，来自
     *                   {@code X-Drill-Batch} 头）；日常流量为 {@code null}。同 {@code traceId} 现读
     */
    public record SensitiveEvent(long seq, long epochMilli, String result,
                                 String scene, int hit, int total, String words, String traceId, String batchId) {
    }

    /** {@code mall.sensitive.checks} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Result {
        /** 命中率低于阈值 ⇒ 打码后放行。 */
        MASKED("masked"),
        /** 命中率达到阈值 ⇒ 整条拒收。 */
        REJECTED("rejected");

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
