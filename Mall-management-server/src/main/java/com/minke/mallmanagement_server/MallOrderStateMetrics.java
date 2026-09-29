package com.minke.mallmanagement_server;

import com.minke.mallmanagement_service.OrderAction;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * 订单状态机观测 —— 状态子系统唯一的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * <h2>【与 MallCacheMetrics / MallLockMetrics 的关系：第三份拷贝，本笔刻意不抽】</h2>
 * 这是仓库里第三份「计数 + 512 槽事件环」。B2 已经论证过不抽共享环的三条理由
 * （{@code MallLockMetrics} 类注释）：抽环要动已被单测钉死并发语义的既有类、B2 的边界是
 * 「只增不改」、事件记录类型本就不同。那三条**在这里依然成立，但收益已经变小** ——
 * 判据是「抽环的收益是否压过改三个已合并类的代价」，第三份时还是「不」，**第四份时应重估**。
 * 这条判断写在这里，是为了让下一个做同类工作的人有个现成的起点，而不是从零再吵一遍。
 *
 * <h2>【命名规范】</h2>
 * 沿用 C1 在 {@code MallCacheMetrics} 类注释里立的规范（全仓唯一出处）：前缀 {@code mall.}、
 * **基数铁律**（tag 只用有界取值、业务键绝不进 tag）、结果类维度统一叫 {@code result}、
 * 可派生的值不重复埋点。
 *
 * <h2>【指标清单】</h2>
 * <pre>
 * mall.order.state.transitions{action=create|pay|deliver|receive, result=applied|rejected|error}
 *        一次**状态转移尝试**的结果，两维都是封闭枚举（4 × 3 = 最多 12 条时间序列）：
 *        applied  = 守卫命中、确实改了库（rows &gt; 0）；
 *        rejected = 守卫没命中（rows == 0）—— 重复流转 / 单不存在，都是**既有行为**，
 *                   本笔不新增任何拒绝文案；
 *        error    = UPDATE 抛异常（库连不上等）。
 * mall.order.state.illegal{shape=010|001|011|non_binary}
 *        观测到**非法状态位组合**的次数 —— TD-17 的探测器。合法集由 A-S4a-8 的不变式定义，
 *        3 个非法态只能由 orderAdd 的伪造入口造出。shape 是三位串；越界取值（orderAdd 收 int，
 *        连 2 都收）归一为 non_binary。**只观测、不拦截**：登记不门禁，是 D5 的既有裁决。
 * mall.order.state.timeline.writes{result=ok|error}
 *        时间线落库结果。result=error 即「这一条时间线永久丢了」——本笔取的是
 *        「丢最旧 + 计数」，允许丢，但**不许静默**。
 * mall.order.state.async.rejected{reason=queue_full|shutdown}
 *        被丢弃的异步任务。reason 区分「队列打满」与「关闭中提交」——两者的处置完全不同。
 * mall.order.state.async.queue.depth
 *        队列当前积压。**它同时是并发测试的排空判据**：断言前轮询到连续若干周期为 0，
 *        而不是 sleep 一个猜出来的固定值（B1 的教训：窗口必须 ≫ burst 墙钟，且要能证明）。
 * mall.order.state.async.duration
 *        异步任务本身的执行墙钟（一行 INSERT）。
 * </pre>
 * <b>不埋 {@code mall.order.state.async.submitted}</b>：本笔只对 applied 的转移投递任务，
 * 故「投递数」在 {@code timeline.enabled=true} 时恒等于
 * {@code transitions{result=applied}}，属规范第 6 条禁止的重复埋点。
 * 启停值由启动期日志打印生效值（同 {@code app.lock.*} / {@code app.cache.*} 的既有做法），
 * 于是那个等式是可判定的，不需要再多一条时间序列。
 *
 * <h2>【事件语法】</h2>
 * <pre>
 *   kind=transition  action=…  from=&lt;状态名|none&gt;  to=&lt;状态名&gt;  result=applied|rejected|error  key=订单号
 *   kind=illegal     shape=010|001|011|non_binary                                        key=订单号
 *   kind=async       result=ok|error|rejected（rejected 时 detail=queue_full|shutdown）    key=订单号
 * </pre>
 * <b>成功的落库不发事件</b>（只计数）：一次转移会产生「transition + async」两条事件，
 * 512 槽在满负荷下只覆盖约 4 秒；把成功的 async 也记进去会砍半。而「顺序」对成功落库没有
 * 增量信息 —— 时间线表自己带 {@code At}。失败与丢弃**必须**发事件，因为那时「哪一单」才是关键。
 *
 * <h2>【热路径代价】</h2>
 * 与 C1 / B2 同量级：registry 按 Meter.Id 查表 + LongAdder 自增 + 一次数组赋值 + 一个小对象。
 * 全路径无锁、无 I/O、**无日志**（agent.md 第 7 条：新增监控不得阻塞主业务线程）。
 * ⚠️ 本类的调用点在本笔是**在订单锁的临界区内**（与 UPDATE 同一处，见
 * {@code MallManagementGoodsController}）—— 这是刻意的：观测与业务事实必须同点记录，
 * 否则两个通道的口径会漂移。代价是纳秒级，不改变临界区的量级。
 */
@Component
public class MallOrderStateMetrics {

    // ===== 指标名：状态子系统唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String TRANSITIONS = "mall.order.state.transitions";
    static final String ILLEGAL = "mall.order.state.illegal";
    static final String TIMELINE_WRITES = "mall.order.state.timeline.writes";
    static final String ASYNC_REJECTED = "mall.order.state.async.rejected";
    static final String ASYNC_QUEUE_DEPTH = "mall.order.state.async.queue.depth";
    static final String ASYNC_DURATION = "mall.order.state.async.duration";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。同 C1 / B2。 */
    private static final int EVENT_CAPACITY = 512;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /**
     * 事件环形缓冲与单调游标。写者只做一次数组赋值，不加锁、不清理旧值 —— 覆盖即淘汰，
     * 读者靠 {@code seq} 自校验（见 {@link #recentEvents()}）。
     */
    private final OrderStateEvent[] events = new OrderStateEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    /**
     * {@code mall.order.state.async.queue.depth} 的取值函数。
     * ⚠️ 必须由本字段强引用持有，理由见 {@link #bindQueueDepth(IntSupplier)}。
     * volatile：写在装配线程、读在 scrape 线程。
     */
    private volatile IntSupplier queueDepth;

    public MallOrderStateMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次状态转移尝试的结果。**与业务事实同点调用**（UPDATE 返回 rows 的那一处）。
     *
     * @param action  动作（封闭枚举，4 个取值）
     * @param fromName 转移前状态名；无法确定时为 {@code "none"}（例如订单不存在）
     * @param toName   转移后状态名；**只有 applied 才有意义**，其余情形传 {@code null}
     * @param result   applied / rejected / error，三路互斥且完备
     * @param orderId  业务键。⚠️ 只进**事件流**的 key 字段，绝不进 tag（基数无界）；
     *                 记它是因为事件流要回答的正是「**哪一单**卡在哪个状态」
     */
    public void transition(OrderAction action, String fromName, String toName, Result result, String orderId) {
        registry.counter(TRANSITIONS, "action", action.tag(), "result", result.tag).increment();
        event("transition", action.tag(), fromName, toName, result.tag, null, orderId);
    }

    /**
     * 观测到非法状态位组合（TD-17 的产物）。**不拦截、不告警刷屏**，只计数 + 发事件。
     *
     * @param shape 三位串（{@code 010} / {@code 001} / {@code 011}）或 {@code non_binary}
     */
    public void illegal(String shape, String orderId) {
        registry.counter(ILLEGAL, "shape", shape).increment();
        event("illegal", null, null, null, null, shape, orderId);
    }

    /**
     * 时间线落库的结果。{@code ok} 只计数不发事件（理由见类注释的热路径段），
     * {@code error} 计数 + 发事件 —— 丢一条时间线是**要能追到哪一单**的事。
     */
    public void timelineWrite(boolean ok, String orderId) {
        registry.counter(TIMELINE_WRITES, "result", ok ? "ok" : "error").increment();
        if (!ok) {
            event("async", null, null, null, "error", null, orderId);
        }
    }

    /**
     * 异步任务被丢弃。**必须发事件** —— 这是「时间线永久缺一条」，不是可忽略的降级。
     *
     * @param reason {@code queue_full}（有界队列打满，丢最旧）或 {@code shutdown}（关闭中不再接收）
     */
    public void asyncRejected(RejectReason reason, String orderId) {
        registry.counter(ASYNC_REJECTED, "reason", reason.tag).increment();
        event("async", null, null, null, "rejected", reason.tag, orderId);
    }

    /** 异步任务本身的执行墙钟。 */
    public void asyncDuration(long nanos) {
        registry.timer(ASYNC_DURATION).record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 绑定 {@code mall.order.state.async.queue.depth} 的取值函数。由
     * {@link com.minke.mallmanagement_service.OrderStateExecutor} 在**构造期**调用。
     *
     * ⚠️ 传进来的 lambda 除了 {@link #queueDepth} 没有任何强引用，而 Micrometer 的 Gauge
     * 对取值对象只持**弱引用**（{@code Gauge.builder(name, obj, f)} 的既有语义）——
     * 不存这一份，一次 GC 之后这个 gauge 会静默变成 NaN（不报错、不告警）。
     * 故本方法必须把 lambda 存进字段，且**先存后注册**（注册后可能立刻被 scrape）。
     * 这与 C1 {@code bindL2Up}、B2 {@code bindUp} 踩的是同一个坑。
     */
    public void bindQueueDepth(IntSupplier depth) {
        this.queueDepth = depth;
        Gauge.builder(ASYNC_QUEUE_DEPTH, depth, s -> s.getAsInt()).register(registry);
    }

    // ================= 事件流 =================

    /**
     * 最近的事件快照，按 {@code seq} 升序（未写满则只含已写入的那几条）。
     *
     * 并发安全：写者只做「数组赋值 + 游标自增」，读者可能读到被覆盖的槽位 —— 故每个槽位
     * 都用 {@code e.seq() == seq} 自校验，对不上就跳过（宁可少一条，不给出一条错位的）。
     * 事件对象本身不可变，不存在读到「写了一半」的对象。
     *
     * 本笔**不**把它对外暴露（端点属 C2/C3）；目前只被单测调用，用来证明这一通道真的记下了事件。
     */
    public List<OrderStateEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<OrderStateEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            OrderStateEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    private void event(String kind, String action, String from, String to,
                       String result, String shape, String key) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new OrderStateEvent(
                seq, System.currentTimeMillis(), kind, action, from, to, result, shape,
                key == null ? null : String.valueOf(key));
    }

    /**
     * 一条订单状态事件。字段含义按 {@code kind} 定，取值表见 {@link MallOrderStateMetrics} 类注释。
     * 不适用的字段为 {@code null} —— 刻意不重载字段含义（把 shape 塞进 to 之类），
     * 宁可多两个 null 字段，也不让读事件的人去猜。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param kind       transition | illegal | async
     * @param action     transition 的动作 tag；其余 null
     * @param from       transition 的前态名；其余 null
     * @param to         transition 的后态名；其余 null
     * @param result     transition 的结果 / async 的结果（ok|error|rejected）；illegal 为 null
     * @param shape      illegal 的三位串或 non_binary；async 被拒时放 reason；其余 null
     * @param key        涉及的业务键（订单号）
     */
    public record OrderStateEvent(long seq, long epochMilli, String kind, String action,
                                  String from, String to, String result, String shape, String key) {
    }

    /** {@code mall.order.state.transitions} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Result {
        /** 守卫命中、确实改了库（rows &gt; 0）。 */
        APPLIED("applied"),
        /** 守卫没命中（rows == 0）—— 重复流转或单不存在，都是**既有行为**。 */
        REJECTED("rejected"),
        /** UPDATE 抛异常。 */
        ERROR("error");

        final String tag;

        Result(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界），理由同 {@code OrderAction#tag()}。 */
        public String tag() {
            return tag;
        }
    }

    /** {@code mall.order.state.async.rejected} 的 reason 取值。 */
    public enum RejectReason {
        /** 有界队列打满，按策略丢最旧。 */
        QUEUE_FULL("queue_full"),
        /** 线程池已关闭，不再接收新任务。 */
        SHUTDOWN("shutdown");

        final String tag;

        RejectReason(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界）。 */
        public String tag() {
            return tag;
        }
    }
}
