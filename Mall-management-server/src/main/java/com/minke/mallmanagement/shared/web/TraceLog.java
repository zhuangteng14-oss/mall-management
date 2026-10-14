package com.minke.mallmanagement.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「一次请求」的头尾台账（C3 块 7 · commit 8）—— 与 {@link TraceIdFilter} 配对：filter 在请求
 * 结束时把「这一趟跑了多久」记进来，事件流（{@code EventStreamService}）每拍把它随帧推给前端。
 *
 * <h2>【它解决什么 —— 与「事件环」的分工】</h2>
 * commit 8 的实时日志页点任一条事件卡片要展开「**这一次请求的完整链路**」：哪几环动过、总耗时多少。
 * 前半截靠 {@code ObservedEvent.traceId}（每条事件自带号，前端按号归堆），后端不需要新东西；
 * 后半截（**总耗时**）在本类 —— 单看事件推不出「请求何时结束」：一次下单的最后一环（审计写入）
 * 是**异步落库后**才记的，且没有事件意味着「什么都没发生」而不是「结束了」。
 *
 * <h2>【为什么是静态台账，不是 Spring bean】</h2>
 * 写入方 {@link TraceIdFilter} 的构造函数<b>必须零参数</b>（{@code @WebMvcTest} 全部切片都会加载
 * 它 —— 理由见该类的长注释），故它拿不到任何注入。这与 {@code IdentityGuard} 面对的是同一件事，
 * 但解法不同：那边是「{{@code @PostConstruct} 装配一个静态口」，这里连装配都不需要 ——
 * 本类**无依赖**（一个数组 + 一个计数器），直接静态持有即可，少一层接线、少一个可能忘了接的线头。
 *
 * <h2>【⚠️ 已知边界，如实记】</h2>
 * <ul>
 *   <li><b>只收请求线程的请求</b>：异步线程 / 定时任务 / SSE 流自身打的日志没有号，也不进本台账。
 *       跨线程透传按批文归 8c（纯乙：显式传参）。</li>
 *   <li><b>{@code /stream} 那条长连接刻意不记</b>（见 {@link #SKIP_SUFFIX}）：SSE 请求要等客户端断开
 *       才「结束」，记进去就是一条几十分钟、且每拍都在变的假记录，会把「总耗时」这个数污染成噪声。</li>
 *   <li><b>覆盖式淘汰</b>：超出 {@link #CAPACITY} 丢最早的（同事件环口径）—— 前端只是拿它给抽屉
 *       配上下文，丢一条最多是「那条卡片点开没有总耗时」，不影响任何判据。</li>
 * </ul>
 */
public final class TraceLog {

    /** 台账容量。与前端抽屉要看的量级对齐：一屏几百条卡片，够配上下文。 */
    private static final int CAPACITY = 256;

    private static final int MASK = CAPACITY - 1;

    /** 长连接不算「一次请求」的耗时 —— 见类注释「已知边界」第 2 条。 */
    private static final String SKIP_SUFFIX = "/stream";

    private static final RequestTrace[] SLOTS = new RequestTrace[CAPACITY];

    /** 单调游标：写者只做一次数组赋值，读者靠 {@code seq} 自校验（同事件环的并发口径）。 */
    private static final AtomicLong CURSOR = new AtomicLong();

    private TraceLog() {
    }

    /**
     * 记一趟请求的收尾。
     *
     * @param traceId  与 {@link TraceIdFilter#HEADER} 同源的那个号
     * @param method   HTTP 动词
     * @param path     请求路径（{@code getRequestURI()}，不含 query —— query 里可能有敏感值）
     * @param status   响应码
     * @param startMs  开始时刻（毫秒，墙钟）
     * @param endMs    结束时刻（毫秒，墙钟）
     */
    public static void record(String traceId, String method, String path, int status,
                              long startMs, long endMs) {
        if (traceId == null) {
            return;
        }
        if (path != null && path.endsWith(SKIP_SUFFIX)) {
            return;
        }
        long seq = CURSOR.getAndIncrement();
        SLOTS[(int) (seq & MASK)] = new RequestTrace(seq, traceId, method, path, status, startMs, endMs);
    }

    /** 台账当前的「下一个 seq」减一；空台账返回 -1（与 {@link #since} 的入参同一坐标系）。 */
    public static long latestSeq() {
        return CURSOR.get() - 1;
    }

    /**
     * 取 {@code afterSeq} **之后**的记录（按 seq 升序）。
     *
     * @param afterSeq 调用方上次读到的 seq；首次读给 {@code -1}（或任何 &lt; 0 的值）= 要一屏全量
     * @return 未被覆盖的部分；被覆盖掉的**不报错也不补**（覆盖即淘汰）
     */
    public static List<RequestTrace> since(long afterSeq) {
        long next = CURSOR.get();
        long oldest = Math.max(0L, next - CAPACITY);
        long from = Math.max(oldest, afterSeq + 1);
        List<RequestTrace> out = new ArrayList<>((int) Math.max(0L, next - from));
        for (long seq = from; seq < next; seq++) {
            RequestTrace candidate = SLOTS[(int) (seq & MASK)];
            if (candidate != null && candidate.seq() == seq) {
                out.add(candidate);
            }
        }
        return out;
    }

    /**
     * 一趟请求的头尾 + 耗时。
     *
     * @param seq     台账内单调序号（前端的游标；<b>不是</b>事件流的 seq —— 两套号互不相干）
     * @param traceId 请求号
     * @param ms      耗时（毫秒）—— 由 {@code endMs - startMs} 算好放进来，前端不必再减
     */
    public record RequestTrace(long seq, String traceId, String method, String path,
                               int status, long startMs, long endMs, long ms) {

        RequestTrace(long seq, String traceId, String method, String path,
                     int status, long startMs, long endMs) {
            this(seq, traceId, method, path, status, startMs, endMs, Math.max(0L, endMs - startMs));
        }
    }
}
