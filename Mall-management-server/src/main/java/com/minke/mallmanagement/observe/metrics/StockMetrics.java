package com.minke.mallmanagement.observe.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 库存观测 —— 库存治理（TD-36）唯一的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * <p>《块 7 参考》§4.5 五套新事件环之一（StockMetrics —— 库存扣减 / 释放 / 不足），
 * 与 {@code MallCacheMetrics} / {@code MallLockMetrics} / {@code MallOrderStateMetrics} 同款：
 * <b>2048 槽事件环</b> + 计数，两条通道在<b>同一调用点</b>一起发。
 *
 * <h2>【与既有三环的关系：结构照抄，事件环<b>不共用</b>】</h2>
 * 三条理由同 {@code MallLockMetrics} 类注释（那是全仓唯一出处，本文不重抄）：
 * 抽共享环要动已被单测钉死并发语义的 {@code MallCacheMetrics}、要引入事件接口、而事件类型本就
 * 每域不同（共享只能共享机制）。代价是五份 2048 槽数组，换的是 C1 与 B2 一个字不动。
 *
 * <h2>【命名规范】</h2>
 * 沿用 {@code MallCacheMetrics} 类注释立的规范：前缀 {@code mall.}、<b>基数铁律</b>
 * （tag 只用有界取值、业务键<b>绝不</b>进 tag）、结果类维度统一叫 {@code result}、
 * 可派生的值不重复埋点。本类的 {@code result} 取值来自下面的封闭枚举
 * （{@code Deduct} 3 个 / {@code Release} 3 个）⇒ 全部时间序列数 ≤ 6；{@code skuCode}
 * 只出现在事件流的 {@code skuCode} 字段里。
 *
 * <h2>【指标清单】</h2>
 * <pre>
 * mall.stock.deduct{result=ok|insufficient|error}
 *        一次**下单即扣**的结果（{@code OrderService.deductStocks} 逐条明细各一次）。
 *        ok           = CAS 命中，{@code Stock} 真的减了 n；
 *        insufficient = CAS 返回 0 行 —— ⚠️ **这是正常分支、不是失败**，两种成因合并在这一个取值里：
 *                       ① 库存不足（防超卖命中，本指标的主要目的）；② 非矩阵商品根本没有
 *                       {@code goods_sku} 行（订单装 {@code ProductCode}，只有矩阵商品的
 *                       {@code SkuCode} 才与之相等）。CAS 语句只看得到「没扣到」，
 *                       分不出来源 ⇒ 不另设取值（规范第 6 条：不重复埋点、不编造不可得的信息）。
 *        error        = DAO 抛异常（连接失败等），业务侧吞掉并只记 WARN。
 * mall.stock.release{result=ok|missing|error}
 *        一次**超时释放**的结果（{@code OrderTimeoutCancelJob.releaseStock} 逐条明细各一次）。
 *        ok      = 命中并加回 n；
 *        missing = 0 行 —— 该 {@code SkuCode} 在 {@code goods_sku} 里没有行（同上非矩阵商品），
 *                  **无处可放**，不是失败；语义与 deduct 的 insufficient 不同，故单列。
 *        error   = DAO 抛异常（业务侧吞掉并只记 WARN，回补失败**不回滚取消**）。
 * </pre>
 * 不埋「扣减总量 / 释放总量」：那是从 {@code ok} 次数 × 数量派生的（规范第 6 条），
 * 而数量本来就在事件流里（{@code qty}）。
 *
 * <h2>【事件语法】</h2>
 * <pre>
 *   kind=deduct    result=ok|insufficient|error   skuCode=业务键   qty=本次数量
 *   kind=release   result=ok|missing|error        skuCode=业务键   qty=本次数量
 * </pre>
 *
 * <h2>【写入点】（块 7 起，两处都在本块改造过的代码里）</h2>
 * <ol>
 *   <li>{@code order/service/OrderService.java#deductStocks} —— 下单即扣（第一拍）；</li>
 *   <li>{@code order/job/OrderTimeoutCancelJob.java#releaseStock} —— 超时释放（第三拍）。</li>
 * </ol>
 * 两处都<b>不改业务结论</b>：本类只记「已发生的事实」，不参与任何决策、不抛异常
 * （DAO 的异常由调用侧先接住 ⇒ 传到本类的只有枚举，不是 throwable）。
 *
 * <h2>【热路径代价】</h2>
 * 与既有三环同量级：registry 按 Meter.Id 查表 + LongAdder 自增 + 一次数组赋值 + 一个小对象。
 * 全路径无锁、无 I/O、<b>无日志</b>（agent.md 第 7 条：新增监控不得阻塞主业务线程）。
 * ⚠️ 本类在订单锁的临界区内被调用（{@code OrderService.addLocked}）⇒ 上面这条代价约束
 * 在这里是硬要求，不是风格。
 */
@Component
public class StockMetrics {

    // ===== 指标名：库存子系统唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String DEDUCT = "mall.stock.deduct";
    static final String RELEASE = "mall.stock.release";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。与既有三环同值 —— 2048 条 ≈ 每秒千条量级下 2 秒的窗口。 */
    private static final int EVENT_CAPACITY = 2048;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /**
     * 事件环形缓冲与单调游标。写者只做一次数组赋值，不加锁、不清理旧值 —— 覆盖即淘汰，
     * 读者靠 {@code seq} 自校验（见 {@link #recentEvents()}）。
     */
    private final StockEvent[] events = new StockEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    public StockMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次下单即扣的结果。
     *
     * @param skuCode 业务键（{@code goods_sku.SkuCode}）。⚠️ 只进<b>事件流</b>，绝不进 tag（基数无界）
     * @param qty     本次要扣的数量（{@code ok} 时是真扣掉的量）
     * @param result  CAS 三态之一，见类注释「指标清单」
     */
    public void deduct(String skuCode, int qty, Deduct result) {
        registry.counter(DEDUCT, "result", result.tag).increment();
        event("deduct", result.tag, skuCode, qty);
    }

    /**
     * 一次超时释放的结果。
     *
     * @param skuCode 业务键（只进事件流，理由同 {@link #deduct}）
     * @param qty     本次要回补的数量
     * @param result  三态之一，见类注释「指标清单」
     */
    public void release(String skuCode, int qty, Release result) {
        registry.counter(RELEASE, "result", result.tag).increment();
        event("release", result.tag, skuCode, qty);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序（未写满则只含已写入的那几条）。
     *
     * <p>并发安全：写者只做「数组赋值 + 游标自增」，读者可能读到被覆盖的槽位 —— 故每个槽位
     * 都用 {@code e.seq() == seq} 自校验，对不上就跳过（宁可少一条，不给出一条错位的）。
     * 事件对象本身不可变，不存在读到「写了一半」的对象。
     *
     * <p>本笔<b>不</b>把它对外暴露（事件流端点属下一笔的 {@code EventBroadcaster} / SSE）；
     * 目前只被单测调用，用来证明这一通道真的记下了事件。
     */
    public List<StockEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<StockEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            StockEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /**
     * 追加一条事件。{@code skuCode} 只在事件里以字符串形式出现 —— 它可以是任意业务键，
     * 但 tag 里绝不能有它（基数无界）。
     */
    private void event(String kind, String result, String skuCode, int qty) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new StockEvent(
                seq, System.currentTimeMillis(), kind, result,
                skuCode == null ? null : String.valueOf(skuCode), qty, com.minke.mallmanagement.shared.web.TraceIdFilter.current(), com.minke.mallmanagement.shared.web.TraceIdFilter.currentBatch());   // 块 7 · commit 8/8c：写全限定名而非 import，理由同 {@code MallCacheMetrics#event}（保本文件 `:75` 的引用不下移）
    }

    /**
     * 一条库存事件。同类事件的两条记录 field 含义按 {@code kind} 定，取值表见类注释「事件语法」。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param kind       deduct | release
     * @param result     该 kind 下的结果
     * @param skuCode    涉及的业务键（{@code goods_sku.SkuCode}）
     * @param qty        本次数量（进/出库的件数）
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；不在请求线程上时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（块 7 · commit 8c，来自
     *                   {@code X-Drill-Batch} 头）；日常流量为 {@code null}。同 {@code traceId} 现读
     */
    public record StockEvent(long seq, long epochMilli, String kind, String result,
                             String skuCode, int qty, String traceId, String batchId) {
    }

    /** {@code mall.stock.deduct} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Deduct {
        /** CAS 命中：{@code Stock} 真的减了 n。 */
        OK("ok"),
        /** CAS 返回 0 行 —— <b>正常分支</b>：库存不足，或该码在 {@code goods_sku} 里没有行。 */
        INSUFFICIENT("insufficient"),
        /** DAO 抛异常（连接失败等）；业务侧吞掉、只记 WARN，订单结论不受影响。 */
        ERROR("error");

        final String tag;

        Deduct(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界）。公开的理由同 {@code MallLockMetrics.Outcome#tag()}：调用侧不该重抄字面量。 */
        public String tag() {
            return tag;
        }
    }

    /** {@code mall.stock.release} 的 result 取值。 */
    public enum Release {
        /** 命中并加回 n。 */
        OK("ok"),
        /** 0 行 —— 该码在 {@code goods_sku} 里没有行，<b>无处可放</b>（不是失败，也不是「不足」）。 */
        MISSING("missing"),
        /** DAO 抛异常；回补失败<b>不回滚</b>取消（取消已落库）。 */
        ERROR("error");

        final String tag;

        Release(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界），理由同 {@link Deduct#tag()}。 */
        public String tag() {
            return tag;
        }
    }
}
