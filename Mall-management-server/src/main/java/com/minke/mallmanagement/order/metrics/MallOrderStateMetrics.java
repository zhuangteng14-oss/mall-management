package com.minke.mallmanagement.order.metrics;

import com.minke.mallmanagement.order.model.OrderAction;
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
 * 这是仓库里第三份「计数 + 2048 槽事件环」。B2 已经论证过不抽共享环的三条理由
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
 *        ⚠️ **2026-10-04（C#6）起，本序列在 API 路径上不可达** —— 出生闸在 INSERT 之前就
 *        挡掉了非法态，而本计数只在 INSERT 成功之后（{@code OrderTransitionRecorder.created}）
 *        才记。**保留、不删**（同 {@code agent.md} 规则 2）：① 存量脏数据与将来若重开入口时的
 *        兜底观测；② 删它等于抹掉 TD-17 的历史口径。**它与 birth.rejected 不是重复埋点**：
 *        一个记「已经落库的非法态」（过去时），一个记「在门口被挡下的」（拦截时）。
 * mall.order.state.birth.rejected{shape=010|001|011|non_binary|non_initial}
 *        下单出生闸（C#6）**拦下**的请求数。shape 由
 *        {@code OrderStateMachine.rejectionShape} 判出，**5 个封闭取值**：
 *        前 4 个与 {@code illegal} **共用同一份词表**（越界一律 non_binary），第 5 个
 *        {@code non_initial} = 形状合法但不是初始态（{@code (1,1,0)}/{@code (1,0,1)}/{@code (1,1,1)}）
 *        —— 少了它，C3 的「今日拦截 N 次」会**漏记六分之三**且不报错。
 *        这一点是硬要求：shape 必须是封闭枚举，**三位原值绝不进 tag**（基数无界）。
 *        ⚠️ 它记的是**被拒**,不是"出生失败"（后者是 {@code transitions{action=create,
 *        result=applied}} 的反面，不在此处）；两者不可相加。
 *        ⚠️ **本计数是进程生命周期累计，给不出「今日」** —— 要按日切，读**事件流**
 *        （{@code recentEvents()} 带 {@code epochMilli} 墙钟）自行聚合，或接 Prometheus
 *        （**本仓没有**，{@code application.properties} 只暴露 health/metrics）。
 *        ⚠️ C#6 **未**为它加 {@code action} 之类的 tag：shape 已含全部信息，加维即造出
 *        规范第 6 条禁止的冗余序列。
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
 *   kind=illegal     result=（空）   shape=010|001|011|non_binary   落库后观测到非法态       key=订单号
 *   kind=illegal     result=rejected shape=010|001|011|non_binary   C#6 出生闸**拦下**   key=订单号
 *   kind=async       result=ok|error|rejected（rejected 时 detail=queue_full|shutdown）    key=订单号
 * </pre>
 * <b>{@code kind=illegal} 的两行靠 {@code result} 区分，不新增 {@code kind}</b>：
 * 前者是「已经落库的非法态」（C#6 后 API 路径不可达，见上），后者是「在门口被挡下的」。
 * 不新增 {@code kind} 是为了让 {@code OrderStateEvent}（9 字段的 record）**一个字段都不用改**
 * —— 为一个新事件加一个 kind 值，却仍要复用 shape/key 两个字段，不值得动 record。
 * <b>成功的落库不发事件</b>（只计数）：一次转移会产生「transition + async」两条事件，
 * 把成功的 async 也记进去会砍半，而「顺序」对成功落库没有增量信息 —— 时间线表自己带 {@code At}。
 * 失败与丢弃**必须**发事件，因为那时「哪一单」才是关键（容量 512 → 2048 后，这一条更不在于省槽位）。
 *
 * <h2>【热路径代价】</h2>
 * 与 C1 / B2 同量级：registry 按 Meter.Id 查表 + LongAdder 自增 + 一次数组赋值 + 一个小对象。
 * 全路径无锁、无 I/O、**无日志**（agent.md 第 7 条：新增监控不得阻塞主业务线程）。
 * ⚠️ 本类的调用点在本笔是**在订单锁的临界区内**（与 UPDATE 同一处，见
 * {@link com.minke.mallmanagement.order.service.OrderService}）—— 这是刻意的：观测与业务事实必须同点记录，
 * 否则两个通道的口径会漂移。代价是纳秒级，不改变临界区的量级。
 */
@Component
public class MallOrderStateMetrics {

    // ===== 指标名：状态子系统唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String TRANSITIONS = "mall.order.state.transitions";
    static final String ILLEGAL = "mall.order.state.illegal";
    static final String BIRTH_REJECTED = "mall.order.state.birth.rejected";
    static final String TIMELINE_WRITES = "mall.order.state.timeline.writes";
    static final String ASYNC_REJECTED = "mall.order.state.async.rejected";
    static final String ASYNC_QUEUE_DEPTH = "mall.order.state.async.queue.depth";
    static final String ASYNC_DURATION = "mall.order.state.async.duration";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。同 C1 / B2。 */
    private static final int EVENT_CAPACITY = 2048;
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
     * @param durationNanos 墙钟（块 7 · 阶段 3a.3 新增）：{@code OrderService} 里
     *                 <b>{@code orderDao.casUpdate(...)} 之前到之后</b>（裁定 ⑤）。
     *                 ⚠️ 该段<b>在订单锁的临界区内</b> ⇒ 这个数含**持锁等待 + 真 UPDATE 往返**，
     *                 不是纯 SQL 耗时；两者在页面上不分开（分开需要另一次埋点，且分不出「等锁」的归属）
     * @param reason   失败 / 拒绝的人话原因（块 7 · 阶段 3a.3 后续新增）；applied 传 {@code null}。
     *                 ⚠️ 与 {@code shape} 分工：{@code shape} 是**三位形状 / async 的 queue_full|shutdown**，
     *                 本参数只补 transition 的 rejected / error 与 timelineWrite 的失败 ——
     *                 同一事实不写两遍。只进 {@code fields.reason}，绝不进 tag
     */
    public void transition(OrderAction action, String fromName, String toName, Result result, String orderId,
                           long durationNanos, String reason) {
        registry.counter(TRANSITIONS, "action", action.tag(), "result", result.tag).increment();
        event("transition", action.tag(), fromName, toName, result.tag, null, orderId, durationNanos, reason);
    }

    /**
     * 观测到非法状态位组合（TD-17 的产物）。**不拦截、不告警刷屏**，只计数 + 发事件。
     *
     * <p>⚠️ <b>2026-10-04（C#6）起，本方法在 API 路径上不可达</b>：出生闸在 INSERT 之前挡掉了
     * 非法态，而本方法只在 INSERT 成功之后（{@code OrderTransitionRecorder.created}）被调。
     * <b>保留不删</b>（{@code agent.md} 规则 2），作存量脏数据的兜底观测 ——
     * 它记的是「已经落库的非法态」，与 {@link #birthRejected} 记的「在门口被挡下的」是两回事。
     *
     * @param shape 三位串（{@code 010} / {@code 001} / {@code 011}）或 {@code non_binary}
     * @param durationNanos 墙钟（块 7 · 阶段 3a.3 新增）：<b>分类那一步</b>本身 ——
     *                 {@code OrderTransitionRecorder.recordIllegalIfAny} 里起表于
     *                 {@code stateMachine.illegalShape(...)} 之前、止于其后。
     *                 ⚠️ 它是<b>纯内存位运算</b>（亚微秒级），落在页面上多半显示成 &lt;1000µs
     *                 那一档 —— 这不是没测到，是它真的这么快；两个入口（读回后 / 出生时）同一个含义
     */
    public void illegal(String shape, String orderId, long durationNanos) {
        registry.counter(ILLEGAL, "shape", shape).increment();
        // 原因由 shape 表达（三位形状就是原因）⇒ reason 传 null，不重复写同一事实
        event("illegal", null, null, null, null, shape, orderId, durationNanos, null);
    }

    /**
     * 下单出生闸（C#6）**拦下**一次请求。**与业务事实同点调用** —— 在
     * {@code OrderService.orderAdd} 判定拒绝的同一处（锁**外**，故本方法不得做任何 I/O）。
     *
     * <p>⚠️ <b>{@code shape} 必须来自 {@code OrderStateMachine.rejectionShape(...)} </b>
     * （5 个取值：{@code 010} / {@code 001} / {@code 011} / {@code non_binary} / {@code non_initial}）。
     * 传三位原值会**直接打破基数铁律**：{@code @RequestParam int} 收任意整数，
     * 时间序列数会随客户端传什么而爆炸。前 4 个取值与 {@link #illegal} 是同一份词表，
     * 不是巧合，是刻意的 —— 两处的 shape 应当可直接对比。
     *
     * <p>记的是**被拒**，不是「出生失败」：{@code transitions{action=create,result=applied}}
     * 记成功出生，本序列记被拒。两者**不可相加**成「出生尝试数」之外的任何东西。
     *
     * <p>⚠️ <b>本方法假定 {@code shape} 非 null</b>（它进 tag，而 Micrometer 的
     * {@code ImmutableTag} 对 null 值 {@code requireNonNull} ⇒ 这里会抛 NPE）。
     * 该前提由调用方 {@code OrderTransitionRecorder.rejectedAtBirth} 挡下
     * —— 它在 {@code shape == null} 时 WARN 后直接返回，不到这里。
     *
     * @param shape   归一后的形状（5 值封闭枚举，见上）
     * @param orderId 业务键。⚠️ 只进**事件流**的 {@code key}，**绝不进 tag**（基数无界）
     * @param durationNanos 墙钟（块 7 · 阶段 3a.3 新增）：{@code OrderTransitionRecorder.rejectedAtBirth}
     *                 的<b>方法入口 → 发本事件之前</b>。⚠️ 它**含那一行 WARN 的格式化与输出** ——
     *                 被拒路径上这行日志本就是拦截动作的一部分，剔除它反而要额外取样；
     *                 通过路径不发事件、不付这个代价（同 protection 环「只在被拒样本有耗时」的精神）
     */
    public void birthRejected(String shape, String orderId, long durationNanos) {
        registry.counter(BIRTH_REJECTED, "shape", shape).increment();
        // 同上：shape 就是原因，reason 传 null
        event("illegal", null, null, null, "rejected", shape, orderId, durationNanos, null);
    }

    /**
     * 时间线落库的结果。{@code ok} 只计数不发事件（理由见类注释的热路径段），
     * {@code error} 计数 + 发事件 —— 丢一条时间线是**要能追到哪一单**的事。
     *
     * @param durationNanos 那次 INSERT 的墙钟（块 7 · 阶段 3a.3 新增）。
     *                 ⚠️ <b>本参数是 {@code Long} 不是 {@code long}</b>，与 {@link #transition}
     *                 那一组不同 —— 因为 {@code false} 有**两个来源**，含义不一样：
     *                 <ul>
     *                   <li>{@code OrderTimelineWriter.write} 的 catch：INSERT **真跑过**、抛了 ⇒
     *                       耗时照传（记的是「白花了多久」）；</li>
     *                   <li>{@code OrderTransitionRecorder.applied} 的读回失败分支：三位读不回来
     *                       ⇒ 根本没有 from/to 可写、INSERT <b>一次都没发生过</b> ⇒ 传 {@code null}。
     *                       这里 {@code null} 不是「测不到」而是「没发生」，两者在页面上意思完全不同</li>
     *                 </ul>
     * @param reason  失败的人话原因（块 7 · 阶段 3a.3 后续新增）；{@code ok} 传 {@code null}。
     *                只进事件 record → {@code fields.reason}
     */
    public void timelineWrite(boolean ok, String orderId, Long durationNanos, String reason) {
        registry.counter(TIMELINE_WRITES, "result", ok ? "ok" : "error").increment();
        if (!ok) {
            event("async", null, null, null, "error", null, orderId, durationNanos, reason);
        }
    }

    /**
     * 异步任务被丢弃。**必须发事件** —— 这是「时间线永久缺一条」，不是可忽略的降级。
     *
     * @param reason {@code queue_full}（有界队列打满，丢最旧）或 {@code shutdown}（关闭中不再接收）
     */
    public void asyncRejected(RejectReason reason, String orderId) {
        registry.counter(ASYNC_REJECTED, "reason", reason.tag).increment();
        // 耗时恒 null（块 7 · 阶段 3a.3）：被拒的语义是「这个任务**根本没跑**」——
        // 没有可测的执行段（线程池的拒绝发生在提交处，纳秒级且不是任务本身的耗时），
        // 与 protection 环「熔断/池/重试恒 null」同一条处置
        // 原因由 shape（queue_full / shutdown）表达 ⇒ 新加的 reason 形参传 null，不重复
        event("async", null, null, null, "rejected", reason.tag, orderId, null, null);
    }

    /** 异步任务本身的执行墙钟。 */
    public void asyncDuration(long nanos) {
        registry.timer(ASYNC_DURATION).record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 绑定 {@code mall.order.state.async.queue.depth} 的取值函数。由
     * {@link com.minke.mallmanagement.order.model.OrderStateExecutor} 在**构造期**调用。
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
                       String result, String shape, String key, Long durationNanos, String reason) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new OrderStateEvent(
                seq, System.currentTimeMillis(), kind, action, from, to, result, shape,
                key == null ? null : String.valueOf(key), com.minke.mallmanagement.shared.web.TraceIdFilter.current(), com.minke.mallmanagement.shared.web.TraceIdFilter.currentBatch(),
                com.minke.mallmanagement.shared.web.TraceIdFilter.currentActorId(),
                com.minke.mallmanagement.shared.web.TraceIdFilter.currentActorRole(),
                durationNanos == null ? null : durationNanos / 1000,   // 块 7 · commit 8/8c：写全限定名而非 import，理由同 {@code MallCacheMetrics#event}（保本文件 `:3` 与 `:104` 的引用不下移）。⚠️ durationNanos 是**纳秒**、落进 record 的是**微秒**
                reason == null ? null : com.minke.mallmanagement.shared.text.EventReason.clip(reason));
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
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；不在请求线程上时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（块 7 · commit 8c，来自
     *                   {@code X-Drill-Batch} 头）；日常流量为 {@code null}。同 {@code traceId} 现读
     * @param actorId    发起那次请求的业务号（块 7 · 阶段 3a.3 新增）；无身份时为 null。
     *                   ⚠️ {@code kind=async} 这一路**恒 null**（它在 {@code OrderStateExecutor}
     *                   的线程池线程上跑，MDC 不带身份 —— 见 {@code ObserveContext} 的派生线程传播）
     * @param actorRole  发起者的角色号（块 7 · 阶段 3a.3 新增）；无身份时为 null
     * @param durationUs 本条事件主体的耗时（**微秒**，块 7 · 阶段 3a.3 新增）；
     *                   口径逐 kind 不同：{@code transition} = CAS 前到后（**含持锁等待**）、
     *                   {@code illegal} = 三位到手到判完形状（纯内存）、{@code async/error} =
     *                   那次 INSERT 的墙钟（读回失败那一路为 null）；{@code async/rejected} 恒 null
     * @param reason     失败 / 拒绝的人话原因（块 7 · 阶段 3a.3 后续新增）。
     *                   <b>只补</b> {@code transition} 的 rejected / error 与 {@code async/error}；
     *                   {@code illegal} / 出生闸 / async 被拒的「原因」仍由 {@code shape} 表达
     *                   （{@code 010} 或 {@code queue_full|shutdown}）—— 同一事实不写两遍
     */
    public record OrderStateEvent(long seq, long epochMilli, String kind, String action,
                                  String from, String to, String result, String shape, String key,
                                  String traceId, String batchId, String actorId, Integer actorRole,
                                  Long durationUs, String reason)
            implements com.minke.mallmanagement.observe.metrics.Sourced {
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
