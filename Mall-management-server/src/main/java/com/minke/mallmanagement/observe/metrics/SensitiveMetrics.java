package com.minke.mallmanagement.observe.metrics;

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
 * <h2>【⚠️ 本环的写入点：**不在**块 6 的敏感词服务里】</h2>
 * 内容安检的实现属<b>块 6</b>，而 pack §约束写死：「块 7 <b>不碰块 6</b> 的表 / 端点 / 代码
 * （只读它们的指标 / 事件）」⇒ 本笔<b>只建出口</b>：写入点挂在块 7 自己的代码
 * （下一笔的攻击引擎 —— 场景 8「敏感词攻击」真发内容、真触发两档处置，然后把结果记进来）。
 * ⚠️ 同 {@code AuditMetrics}：这条 B 档自裁已报备；若要求「由块 6 的服务自己埋点」，
 * 那是动块 6 的代码 = 越界，必须另批。
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
     */
    public void masked(String scene, int hit, int total) {
        registry.counter(CHECKS, "result", Result.MASKED.tag).increment();
        event(Result.MASKED.tag, scene, hit, total);
    }

    /**
     * 一次<b>拒收</b>的处置（命中率达到阈值）。
     *
     * @param scene 安检场景（只进事件流，理由同 {@link #masked}）
     */
    public void rejected(String scene, int hit, int total) {
        registry.counter(CHECKS, "result", Result.REJECTED.tag).increment();
        event(Result.REJECTED.tag, scene, hit, total);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序。并发口径同 {@link StockMetrics#recentEvents()}：
     * 每个槽位用 {@code e.seq() == seq} 自校验，对不上就跳过。
     *
     * <p>本笔<b>不</b>把它对外暴露（事件流端点属下一笔）；目前只被单测调用。
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
    private void event(String result, String scene, int hit, int total) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new SensitiveEvent(
                seq, System.currentTimeMillis(), result,
                scene == null ? null : String.valueOf(scene), hit, total);
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
     */
    public record SensitiveEvent(long seq, long epochMilli, String result,
                                 String scene, int hit, int total) {
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
