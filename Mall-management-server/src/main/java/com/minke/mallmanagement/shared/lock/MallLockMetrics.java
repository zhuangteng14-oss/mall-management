package com.minke.mallmanagement.shared.lock;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 分布式锁观测 —— 锁子系统唯一的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * 【与 MallCacheMetrics 的关系：结构照抄，事件环**不共用**】
 * 两者是同构的两个埋点出口，但本类**不**去抽一个共享的事件环。三条理由都不是风格问题：
 * ① 抽环要引入 {@code ToLongFunction} / 事件接口，等于给一个已被单测钉死并发语义的组件
 *    （{@code MallCacheMetricsTest.concurrentAppendsKeepSnapshotCoherent}）动刀；
 * ② 抽环必然要改 C1 已合并的 MallCacheMetrics 的既有行（B2 的边界是「只增不改」）；
 * ③ 事件类型本就不同 —— {@code MallCacheEvent} 的 kind 取值表是缓存专用、本类是锁专用，
 *    共享只能共享机制、共享不了记录类型，收益进一步缩小。
 * 代价是两份 2048 槽数组（各约 16KB 引用），换的是 C1 一个字不动。
 *
 * 【命名规范】沿用 C1 在 MallCacheMetrics 类注释里立的规范（那是全仓唯一出处）：
 * 前缀 {@code mall.}、**基数铁律**（tag 只用有界取值、业务键绝不进 tag）、
 * 结果类维度统一叫 {@code result}、可派生的值不重复埋点。
 * 本类的 {@code lock} tag 取值来自 {@link com.minke.mallmanagement.shared.lock.DistributedLockService.LockName}
 * 这个**封闭枚举**（5 个取值，有界）；业务键只出现在事件流的 key 字段里。
 *
 * 【指标清单】{@code lock} tag = 逻辑锁名，5 个取值
 * <pre>
 * mall.lock.acquires{lock,result=acquired|timeout|degraded|error}
 *        一次**取锁尝试**的结果，四路互斥且完备：
 *        acquired = 拿到了；timeout = waitTime 内没抢到（fail-open 直行，见 runLocked 的推演）；
 *        degraded = 冷却窗口内**根本没尝试**（Redis 已知不可达，零网络代价）；
 *        error    = 尝试抛异常（连接失败/超时，被吞掉并转入冷却）。
 * mall.lock.wait.duration{lock}
 *        取锁墙钟（Timer）。「等没等、等多久」是它的派生量（规范第 6 条），故只在**真尝试过**
 *        的两路（acquired / timeout）记录 —— degraded 根本没碰网络，给它记 0 会把
 *        「没尝试」混进「等了 0 秒」。MAX 回答「争用最坏等了多久」。
 * mall.lock.holds{lock,result=released|lease_lost|error}
 *        一次**释放**的结果。lease_lost = finally 里 isHeldByCurrentThread() 为假
 *        ⇒ 临界区跑赢了 leaseTime、锁已被别人拿走 —— 这是**双写风险的探测器**，故单列。
 * mall.lock.up
 *        1 = 可尝试取锁；0 = 处于冷却窗口。无 lock tag（可用性是全局事实，Redisson 客户端全应用一份）。
 *        ⚠️ 0 **不等于「Redis 已死」**，与 mall.cache.l2.up 同一口径：0 应读作「现在不依赖锁」。
 * </pre>
 * 不埋 {@code mall.lock.transitions}：翻转由 {@code up} 这个 0/1 状态通道 + 事件流里的
 * transition 事件共同表达，另起计数器属规范第 6 条禁止的重复埋点。
 * 也不埋「拒绝次数」：orderAdd 的拒绝来自临界区内的查重（业务判定），不是取锁失败 ——
 * 取锁失败的计数已经在 {@code acquires{result=timeout}} 里。
 *
 * 【事件语法】
 * <pre>
 *   kind=acquire     result=acquired|timeout|degraded|error   key=业务键
 *   kind=hold        result=released|lease_lost|error         key=业务键
 *   kind=transition  result=degraded|healthy                  key=null
 * </pre>
 *
 * 【热路径代价】与 MallCacheMetrics 同量级：registry 按 Meter.Id 查表 + LongAdder 自增 +
 * 一次数组赋值 + 一个小对象。全路径无锁、无 I/O、**无日志**（agent.md 第 7 条：新增监控
 * 不得阻塞主业务线程）。需要日志的「真实翻转」记在执行取锁的那一侧
 * （DistributedLockService），不在本类 —— 本类只记**已发生的事实**，不参与任何决策。
 */
@Component
public class MallLockMetrics {

    // ===== 指标名：锁子系统唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String ACQUIRES = "mall.lock.acquires";
    static final String WAIT = "mall.lock.wait.duration";
    static final String HOLDS = "mall.lock.holds";
    static final String UP = "mall.lock.up";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。同 C1 —— 2048 条 ≈ 每秒千条量级下 2 秒的窗口。 */
    private static final int EVENT_CAPACITY = 2048;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /**
     * 事件环形缓冲与单调游标。写者只做一次数组赋值，不加锁、不清理旧值 —— 覆盖即淘汰，
     * 读者靠 {@code seq} 自校验（见 {@link #recentEvents()}）。
     */
    private final MallLockEvent[] events = new MallLockEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    /**
     * {@code mall.lock.up} 的取值对象。
     * ⚠️ 必须由本字段强引用持有，理由见 {@link #bindUp(BooleanSupplier)}。
     * volatile：写在装配线程、读在 scrape 线程。
     */
    private volatile BooleanSupplier upState;

    public MallLockMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次取锁尝试的结果。
     *
     * @param bizKey    业务键。⚠️ 只进**事件流**的 key 字段，绝不进 tag（基数无界）；
     *                  记它是因为事件流要回答的正是「**哪一单**在争用/撞上了基础设施事件」
     * @param waitNanos 本次取锁的墙钟；只有 acquired / timeout 两路会真的记进 Timer
     *                  （degraded 没碰网络，记 0 会把「没尝试」混进「等了 0 秒」）
     */
    public void acquire(String lock, String bizKey, Outcome result, long waitNanos) {
        registry.counter(ACQUIRES, "lock", lock, "result", result.tag).increment();
        if (result == Outcome.ACQUIRED || result == Outcome.TIMEOUT) {
            registry.timer(WAIT, "lock", lock).record(waitNanos, TimeUnit.NANOSECONDS);
        }
        event(lock, "acquire", result.tag, bizKey);
    }

    /**
     * 一次释放的结果。由 {@code runLocked} 的 finally 调用。
     * ⚠️ 本方法**不记日志**：lease_lost 在「Redis 很慢以致租期不够用」的窗口里会逐次发生，
     * 逐次 WARN 会刷屏；它的「多少」由本指标回答、「顺序」由事件流回答 —— 与 C1 对
     * 「故障期间不逐次记录」的处理一致。
     *
     * @param bizKey 业务键（只进事件流，理由同 {@link #acquire}）
     */
    public void hold(String lock, String bizKey, Hold result) {
        registry.counter(HOLDS, "lock", lock, "result", result.tag).increment();
        event(lock, "hold", result.tag, bizKey);
    }

    /**
     * 锁可用性发生**真实翻转**时调用（与 DistributedLockService 里那行 WARN/INFO 同一处判定，
     * 天然去重 —— 冷却窗口内的后续失败不重复记）。
     *
     * 只发事件、不记计数器：翻转次数可由 {@code mall.lock.up} 这个 0/1 状态通道配合事件流
     * 读出，另起一个计数器属规范第 6 条禁止的重复埋点。
     */
    public void transition(boolean up) {
        event(null, "transition", up ? "healthy" : "degraded", null);
    }

    /**
     * 绑定 {@code mall.lock.up} 的取值函数。由 {@link com.minke.mallmanagement.shared.lock.DistributedLockService}
     * 在**构造期**调用（绑定不是 I/O，且这样 up 恒存在、初值 1 —— 与「还没建过客户端」的
     * 初始态语义一致：没有失败证据时不该预先降级）。
     *
     * ⚠️ 传进来的 lambda 除了 {@link #upState} 没有任何强引用，而 Micrometer 的 Gauge
     * 对取值对象只持**弱引用**（{@code Gauge.builder(name, obj, f)} 的既有语义）——
     * 不存这一份，一次 GC 之后这个 gauge 会静默变成 NaN（不报错、不告警）。
     * 故本方法必须把 lambda 存进字段，且**先存后注册**（注册后可能立刻被 scrape）。
     * 这与 C1 {@code bindL2Up} 踩的是同一个坑。
     */
    public void bindUp(BooleanSupplier lockUsable) {
        this.upState = lockUsable;
        Gauge.builder(UP, lockUsable, s -> s.getAsBoolean() ? 1.0 : 0.0).register(registry);
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
    public List<MallLockEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<MallLockEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            MallLockEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /**
     * 追加一条事件。{@code key} 只在事件里以字符串形式出现 —— key 可以是任意业务键，
     * 但 tag 里绝不能有它（基数无界）。
     */
    private void event(String lock, String kind, String result, Object key) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new MallLockEvent(
                seq, System.currentTimeMillis(), lock, kind, result,
                key == null ? null : String.valueOf(key), com.minke.mallmanagement.shared.web.TraceIdFilter.current(), com.minke.mallmanagement.shared.web.TraceIdFilter.currentBatch());   // 块 7 · commit 8/8c：写全限定名而非 import，理由同 {@code MallCacheMetrics#event}（保本文件 `:71`/`:72`/`:74` 的引用不下移）
    }

    /**
     * 一条锁事件。字段含义按 {@code kind} 定，取值表见 {@link MallLockMetrics} 类注释「事件语法」。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param lock       逻辑锁名（LockName 的 tag）；可用性翻转为 null（那是全局事实）
     * @param kind       acquire | hold | transition
     * @param result     该 kind 下的结果
     * @param key        涉及的业务键；翻转为 null
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；不在请求线程上时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（块 7 · commit 8c，来自
     *                   {@code X-Drill-Batch} 头）；日常流量为 {@code null}。同 {@code traceId} 现读
     */
    public record MallLockEvent(long seq, long epochMilli, String lock, String kind,
                                String result, String key, String traceId, String batchId) {
    }

    /** {@code mall.lock.acquires} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum Outcome {
        /** 拿到了锁，后续 action 在互斥下执行。 */
        ACQUIRED("acquired"),
        /** waitTime 内没抢到 —— fail-open 直行（语义见 DistributedLockService#runLocked）。 */
        TIMEOUT("timeout"),
        /** 冷却窗口内跳过：根本没碰网络，与「Redis 不可达」是同一层语义。 */
        DEGRADED("degraded"),
        /** 尝试了但抛异常（连接失败/命令超时），被吞掉并转入冷却。 */
        ERROR("error");

        final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界）。公开的理由同 {@code LockName.tag()}：调用侧不该重抄字面量。 */
        public String tag() {
            return tag;
        }
    }

    /** {@code mall.lock.holds} 的 result 取值。 */
    public enum Hold {
        /** 释放成功（isHeldByCurrentThread() 为真且 unlock 未抛）。 */
        RELEASED("released"),
        /** 租期已过、锁已被别人拿走 ⇒ 本次临界区与他人**可能**重叠。 */
        LEASE_LOST("lease_lost"),
        /** unlock 自身抛异常（锁仍会随 leaseTime 到期自清）。 */
        ERROR("error");

        final String tag;

        Hold(String tag) {
            this.tag = tag;
        }

        /** 事件流 / 指标 tag 的取值（有界），理由同 {@link Outcome#tag()}。 */
        public String tag() {
            return tag;
        }
    }
}
