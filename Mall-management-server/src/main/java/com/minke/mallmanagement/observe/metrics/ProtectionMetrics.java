package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.shared.web.TraceIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 防护面观测 —— <b>第 9 类事件环</b>（D 段 · commit 2）。
 *
 * <p>《块 7 参考》§4.2 当时定了八类（缓存 / 锁 / 订单 / 库存 / 审计 / 通知 / 敏感词 / 身份），
 * 那八类记的都是<b>业务与基础设施</b>发生了什么。本环记的是<b>防护层自己做了什么</b> ——
 * 「限流闸挡下了多少」「熔断什么时候开的、什么时候又放行试探」「哪个池满了把任务挤了出去」。
 * 少了它，D 段新增的三处闸在页面上<b>完全不可见</b>：受影响的是响应码（429 / 503）与延迟，
 * 而日志页只会在「业务侧报错」那一层看见后果，看不见原因。
 *
 * <h2>【与既有八环同款的部分（不重抄，见 {@link StockMetrics} 类注释）】</h2>
 * 2048 槽覆盖式环 + 单调游标 + 读者按 {@code seq} 自校验；两条通道（Micrometer 计数 + 事件环）
 * 在<b>同一调用点</b>一起发；无锁、无 I/O、<b>无日志</b>、不参与任何决策、不抛异常。
 *
 * <h2>【命名规范】</h2>
 * 前缀 {@code mall.}；<b>基数铁律</b>：进 tag 的只有两个<b>有界</b>取值 ——
 * {@code protection}（五个 key，见 {@code ProtectionGate.KEYS}）与 {@code result}（本类
 * {@link Result} 的七档）。⚠️ {@code scope} 与 {@code detail} <b>绝不进 tag</b>：
 * 前者虽然有界（闸名 / 下游名），但它是「这类事件发生在哪」的说明，放事件里够用；
 * 后者是自由文本（含业务键、异常类名），基数无界 —— 放进 tag 就是把指标基数交给运行时。
 *
 * <h2>【指标清单】</h2>
 * <pre>
 * mall.protection.events{protection=rateLimit|circuitBreak|degrade|retry|poolIsolation,
 *                        result=blocked|opened|half_open|closed|shed|retry_success|retry_failed}
 *        防护层的一次**处置**（拦下 / 状态翻转 / 挤出 / 重试）。七档互斥且完备：
 *        blocked   = 闸把请求挡在门外（入站限流 429 / 熔断开路拒绝）
 *        opened    = 熔断进入打开态（错误率越线）
 *        half_open = 熔断经等待期后进入半开态（放 5 个试探）
 *        closed    = 熔断试探通过、恢复关闭
 *        shed      = 线程池满，任务被挤出（不排队、直接拒）
 *        retry_success = 死锁受害者重试**成功**（第 2 次及以后的尝试把链路走通）
 *        retry_failed  = 死锁受害者重试**又失败**（还会再试，或已是最后一次）
 * </pre>
 * ⚠️ <b>不埋「放行了多少」</b>：那是「到达量 − 拦下量」的派生量，而到达量由
 * {@code http.server.requests} 自己回答（规范第 6 条：可派生的值不重复埋点）。
 *
 * <h2>【事件语法】</h2>
 * <pre>
 *   result=blocked|opened|half_open|closed|shed|retry_success|retry_failed   key=防护开关key   scope=闸/下游名   明细=自由文本
 * </pre>
 *
 * <h2>【本环的写入点】</h2>
 * 三处，全部在 {@code protect/} 包内：入站限流闸（{@code InboundRateLimitInterceptor} 的 429）、
 * 熔断状态机（{@code ProtectionMetricsBinder} 订阅 {@code CircuitBreaker} 的状态事件）、
 * 线程池拒绝策略（{@code ObservePoolConfig} 的 {@code RejectedExecutionHandler}）。
 * ⚠️ 三处都是「已经决定了才记」—— 本环<b>不做</b>任何判定，判定在各自闸里。
 */
@Component
public class ProtectionMetrics {

    // ===== 指标名：防护面唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String EVENTS = "mall.protection.events";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。与既有八环同值。 */
    private static final int EVENT_CAPACITY = 2048;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /** 事件环形缓冲与单调游标；写者只做一次数组赋值，读者靠 {@code seq} 自校验。 */
    private final ProtectionEvent[] events = new ProtectionEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    public ProtectionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次<b>拦下</b>：闸把请求挡在门外（入站限流 429，或熔断开路时直接拒绝）。
     *
     * @param key    防护开关 key（有界，进 tag）—— 「是哪个开关的闸」
     * @param scope  闸 / 下游名（只进事件流）—— 「拦在哪一段」，如 {@code inbound} / {@code mysql}
     * @param detail 人话明细（只进事件流，绝不进 tag）：被拦的路径 / 熔断器名 / 当时的错误率……
     * @param durationNanos 墙钟（块 7 · 阶段 3a.3 新增）；**只有入站限流这一路有值**
     *                   （{@code InboundRateLimitInterceptor} 从拦截入口量到 {@code acquirePermission()} 返回）。
     *                   熔断开路那一路（{@code ProtectionMetricsBinder}）是**瞬时回调**、没有自然起止 ⇒ 传 {@code null}
     */
    public void blocked(String key, String scope, String detail, Long durationNanos) {
        event(Result.BLOCKED.tag, key, scope, detail, durationNanos);
    }

    /**
     * 一次<b>挤出</b>：线程池满，任务没排队、当场被拒。
     *
     * <p>⚠️ 与 {@link #blocked} 分开记是刻意的：两者的**处置后果不同** ——
     * {@code blocked} 是「闸按规则没放它进来」，{@code shed} 是「闸想放但自己没有余量了」。
     * 合成一档会让「阈值定得太松」与「池子开得太小」两种病读出同一个数。
     */
    public void shed(String key, String scope, String detail) {
        // 不记耗时（块 7 · 阶段 3a.3 定）：池拒绝是**瞬时回调**（任务被当场弹回），没有起止 ⇒ 恒 null
        event(Result.SHED.tag, key, scope, detail, null);
    }

    /**
     * 一次<b>状态翻转</b>（熔断的 opened / half_open / closed）。
     *
     * @param result 必须是 {@link Result} 里状态三档之一 —— 由调用方给枚举而不是字符串，
     *               免得「opend」这种拼错变成一条谁也不认识的 tag（它不会报错，只会让图例少一格）
     */
    public void state(Result result, String key, String scope, String detail) {
        // 不记耗时（同 shed）：熔断状态翻转是瞬时事实，没有起止
        event(result.tag, key, scope, detail, null);
    }

    /**
     * 一次<b>重试</b>（D 段 · 阶段 2b 新增）—— 死锁受害者侧的「再试一遍」这一步动作本身。
     *
     * <p>⚠️ <b>与 {@link #blocked} / {@link #shed} 分开记</b>：那两档是「闸按规则处置了请求」，
     * 本档是「闸放行了、但链路自己摔了一跤又爬起来」—— 合成一档会让「限流挡了多少」
     * 与「死锁赔了多少」读出同一个数。
     *
     * <p>⚠️ {@code detail} 里写 {@code action=retry attempt=N}：{@code action=retry} 与
     * {@code key=retry} 同义（防护开关 key 就是这一维），刻意**不加**一个与之并行的字段 ——
     * 那会造出第二个同义维度，两者一旦不同步就没人说得清哪个算数。
     *
     * @param result 只能是 {@link Result#RETRY_SUCCESS} 或 {@link Result#RETRY_FAILED}
     */
    public void retry(Result result, String key, String scope, String detail) {
        // 不记耗时（块 7 · 阶段 3a.3 定）：本档要的自然量是「两次尝试的间隔」，而现有代码只有 attempt 计数、
        // 没有任何时刻差 ⇒ 要新增计时才行。**已知缺口**，如实记 —— 别把恒 null 当成「间隔为 0」
        event(result.tag, key, scope, detail, null);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序。并发口径同 {@link StockMetrics#recentEvents()}：
     * 每个槽位用 {@code e.seq() == seq} 自校验，对不上就跳过。
     *
     * <p>被 {@code EventBroadcaster}（第 9 类）与单测共同调用。
     */
    public List<ProtectionEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<ProtectionEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            ProtectionEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /** 追加一条事件：先计数、后入环（两条通道同点发，与既有八环同款）。 */
    private void event(String result, String key, String scope, String detail, Long durationNanos) {
        registry.counter(EVENTS, "protection", key == null ? "-" : key, "result", result).increment();
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new ProtectionEvent(
                seq, System.currentTimeMillis(), result,
                key == null ? null : String.valueOf(key),
                scope == null ? null : String.valueOf(scope),
                detail,
                TraceIdFilter.current(), TraceIdFilter.currentBatch(),
                TraceIdFilter.currentActorId(), TraceIdFilter.currentActorRole(),
                durationNanos == null ? null : durationNanos / 1000);
    }

    /**
     * 一条防护事件。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param result     {@link Result} 的七档之一
     * @param key        防护开关 key（{@code ProtectionGate.KEYS} 之一，有界）
     * @param scope      闸 / 下游名（{@code inbound} / {@code mysql} / {@code redis} / {@code mq} / {@code pool}）
     * @param detail     人话明细；可能含路径与异常类名，故只进事件流、不进 tag
     * @param traceId    产生本条事件的那次请求的号；不在请求线程上（如熔断状态翻转由业务线程触发时
     *                   仍可有值，而线程池挤出可能无值）时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（来自 {@code X-Drill-Batch} 头）；
     *                   日常流量为 {@code null}。同 {@code traceId} 现读
     * @param actorId    发起那次请求的业务号（块 7 · 阶段 3a.3 新增）；无身份时为 null
     * @param actorRole  发起者的角色号（块 7 · 阶段 3a.3 新增）；无身份时为 null
     * @param durationUs 拦截耗时（**微秒**，块 7 · 阶段 3a.3 新增）；**只有限流被拒那一档有值**
     *                   （见 {@link #blocked}）；熔断 / 池 / 重试三档恒 null（瞬时回调，无起止）
     */
    public record ProtectionEvent(long seq, long epochMilli, String result,
                                  String key, String scope, String detail, String traceId, String batchId,
                                  String actorId, Integer actorRole, Long durationUs)
            implements com.minke.mallmanagement.observe.metrics.Sourced {
    }

    /** {@code mall.protection.events} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Result {
        /** 闸把请求挡在门外（入站限流 429 / 熔断开路拒绝）。 */
        BLOCKED("blocked"),
        /** 熔断进入打开态（错误率越线）。 */
        OPENED("opened"),
        /** 熔断经等待期后进入半开态（放 5 个试探）。 */
        HALF_OPEN("half_open"),
        /** 熔断试探通过、恢复关闭。 */
        CLOSED("closed"),
        /** 线程池满，任务被挤出（不排队、直接拒）。 */
        SHED("shed"),
        /** 死锁受害者重试**成功**（第 2 次及以后的尝试把链路走通）。 */
        RETRY_SUCCESS("retry_success"),
        /** 死锁受害者重试**又失败**（还会再试，或已是最后一次尝试）。 */
        RETRY_FAILED("retry_failed");

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
