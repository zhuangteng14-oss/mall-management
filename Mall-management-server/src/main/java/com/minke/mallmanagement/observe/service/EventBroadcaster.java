package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.observe.metrics.AuditMetrics.AuditEvent;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics.IdentityEvent;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics.NotifyEvent;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics.SensitiveEvent;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics.StockEvent;
import com.minke.mallmanagement.observe.mq.EventPublisher;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.OrderStateEvent;
import com.minke.mallmanagement.shared.cache.MallCacheMetrics;
import com.minke.mallmanagement.shared.cache.MallCacheMetrics.MallCacheEvent;
import com.minke.mallmanagement.shared.lock.MallLockMetrics;
import com.minke.mallmanagement.shared.lock.MallLockMetrics.MallLockEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件广播器 —— 把**八个事件环**（既有三环 + 块 7 五环）汇成一条有序的事件流，
 * 供 {@code POST /admin/observe/events/stream}（实时日志页）读。
 *
 * <h2>【为什么需要这一层：八个环各自「有界且会被覆盖」，而读的人只想要一条时间线】</h2>
 * 每个环都是 512 槽的**覆盖式**环形缓冲，读法只有「读最近一屏」。实时日志页要的是
 * 「**这一拍新发生的**」，于是需要一个「按源记住读到哪了」的游标 —— 那就是本类。
 * ⚠️ <b>本类不重写任何环、不改变它们的语义</b>：只读 {@code recentEvents()}（各环公开的
 * 只读快照），且**只过滤、不加工**业务字段（例外：下面「派生字段」一节）。
 *
 * <h2>【三件事，逐件说清】</h2>
 * <ol>
 *   <li><b>归一化</b>：八环的事件记录字段各不相同（缓存环有 {@code cache} 没有 {@code orderId}，
 *       订单环有九列……）。本类把它们翻成一个统一的 {@link ObservedEvent}
 *       （{@code kind / severity / title / detail / key}）——「哪些平台字段」是**页面要的**，
 *       不是「环里有的」。翻译表逐条写在下面的 {@code fromXxx} 私有方法里。</li>
 *   <li><b>severity 规则（一处定义，八处适用）</b>：
 *       <pre>
 *       error  ← result = error（抛异常 / 真失败）
 *       warn   ← result ∈ {failed, rejected, timeout, degraded, skipped_degraded,
 *                          insufficient, lease_lost} —— 「被挡下 / 被拒 / 降级 / 抢不到」
 *       info   ← 其余（成功路径、以及 miss / missing 这类「什么也没发生」）
 *       </pre>
 *       ⚠️ 它是**展示口径**（页面据此配色），不是契约 —— 改它只影响日志页的颜色，
 *       不影响任何计数。⚠️ {@code miss} / {@code missing} 刻意留在 info：
 *       本环回答「**发生了什么**」，把每一次未命中都染成黄/红会让整屏都是警告色。
 *       想读「防护拦了多少」请走指标（那是 {@link MetricsSnapshotService} 的活）。</li>
 *   <li><b>游标 + 合并环</b>：每个源记一个「已并入的最大 seq」（初始 -1 = 空环）；
 *       {@link #poll()} 把八环里更新的条目按源顺序并入本类的 512 槽环，
 *       {@link #since(long)} 让调用方按「我上次读到哪」取增量。</li>
 * </ol>
 *
 * <h2>【⚠️ 边界：一拍内超过 512 条会丢——而且**丢的是最早的**，不是最新的】</h2>
 * 源环自己就是覆盖式的：一拍内某源写了 600 条，它的 {@code recentEvents()} 只答得出最后 512 条。
 * 本类**照抄**这个语义（合并环同样是 512 槽覆盖式，与八环同值），不另设统计口径。
 * 依据：@调度器/攻击引擎把每秒事件数打到 512 以上时，**逐条读已经不是正确的读法**
 * （那要看 {@code mall.*} 的计数），日志页要的是**可读的过程**，不是完整审计流水。
 *
 * <h2>【派生字段：{@code seq} 是本类的，不是源的】</h2>
 * {@link ObservedEvent#seq()} 是**合并环自己的**单调序号（页面的「取增量」用它），
 * 与源环的 seq **无关**、也**不可比**。源的 seq 与所属环名一起写进 {@code detail}
 * （形如 {@code stock#128}），排查时能定位回原环。
 *
 * <h2>【⚠️ 同一拍内的顺序 = **环序**，不是事件发生的先后】</h2>
 * {@link #poll()} 是**一环一环地并**的（{@code cache → lock → order → stock → audit → notify
 * → sensitive → identity}），故同一拍里跨环的事件在合并环中是**按环分组**的：本拍最后一刻发生的
 * 缓存事件，会排在本拍最早发生的订单事件**前面**。这是本类明确的口径，不是实现疏漏 ——
 * 逐条按墙钟全局排序需要跨环合并后重排（而各环的墙钟只保证「同环内单调」），
 * 本块的取舍是**不排序**：日志页要的是「这一拍发生了什么」，跨环的毫秒级先后由卡片上的
 * {@code ts} 自己还原。测试钉这条口径的位置：{@code EventBroadcasterTest#severityFollowsTheOneRuleTable}。
 *
 * <h2>【热路径代价】</h2>
 * {@link #poll()} 是**被流线程每秒调一次**的（不是在业务线程上）：八次
 * {@code recentEvents()}（各 ≤512 条、无锁）+ 一次 {@code synchronized}。
 * 业务侧（下单、扣库存……）**从不**调本类 ⇒ 本类不在任何临界区里。
 *
 * <h2>【块 7 · commit 8c：事件台账的出口，以及它的**已知边界**】</h2>
 * {@link #poll()} 并入一条的同时，把那条 {@link ObservedEvent} 交给 {@link EventPublisher}
 * （MQ → {@code observed_event} 落库 + Redis {@code events:latest}）。调点只有
 * {@link #add} 一处（理由见那个字段的注释）。
 * <p>⚠️ <b>边界（必须知道，不许假装不存在）</b>：{@code poll()} 由 <b>SSE 流线程</b>驱动
 * ⇒ <b>没有人打开实时日志页时，合并环不动、也就没有事件落库</b>。也就是说本笔的台账
 * 是「**观看时**的过程记录」，不是「无人值守的全量审计流水」。要让它与观看无关，
 * 得另给 {@code poll()} 一个时钟（定时任务）—— 那会动到「a3 下调度器总闸关着」这条既有口径，
 * 超出本笔（批文只授权「调点挂归一点」），如实登记，不在本笔偷偷加。
 */
@Component
public class EventBroadcaster {

    /** 合并环容量：与八个源环同值（512 = 2 的幂，位与定位；语义与取舍见类注释最后一节）。 */
    private static final int EVENT_CAPACITY = 512;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    /** 事件类别 —— 与《块 7 参考》§4.2 的八类一一对应（顺序 = 页面图例顺序）。 */
    public enum Kind {
        CACHE("cache", "缓存"),
        LOCK("lock", "锁"),
        ORDER("order", "订单"),
        STOCK("stock", "库存"),
        AUDIT("audit", "审计"),
        NOTIFY("notify", "通知"),
        SENSITIVE("sensitive", "敏感词"),
        IDENTITY("identity", "身份");

        /** 页面用作筛选键 / 配色的稳定标识（英文，别拿 {@code label} 当键）。 */
        public final String tag;
        /** 页面图例上的中文名。 */
        public final String label;

        Kind(String tag, String label) {
            this.tag = tag;
            this.label = label;
        }
    }

    /** 严重度三档（规则见类注释第 2 件）。 */
    public enum Severity {
        INFO("info"), WARN("warn"), ERROR("error");

        public final String tag;

        Severity(String tag) {
            this.tag = tag;
        }
    }

    /**
     * 一条归一化后的观测事件。
     *
     * @param seq        合并环的单调序号（**不是**源环的；见类注释「派生字段」）
     * @param epochMilli 源事件的墙钟时刻（各环记的就是墙钟，原样透传）
     * @param kind       {@link Kind#tag}
     * @param severity   {@link Severity#tag}
     * @param title      中文短标题（卡片第一行）
     * @param detail     明细串（卡片第二行）：含源的业务字段 + {@code src=环名#源seq}
     * @param key        涉及的业务键（订单号 / SKU / 收件人……）；没有就 null
     * @param objectType <b>这条事件动的是「什么类型的东西」</b>（块 7 · commit 8 新增）——
     *                   缓存 / 锁 / 订单 / 商品（SKU）/ 通知 五环取的是各自那个稳定词，审计环取的是
     *                   {@code audit} 表本来就有的 {@code TargetType}（{@code goods} / {@code comment}…），
     *                   敏感词环是 {@code sensitive}，身份环是 {@code identity}。取值在八个 {@code ingestXxx} 里逐环给定
     * @param objectId   上面那个类型的**具体标识**（块 7 · commit 8 新增）：缓存键 / 锁键 / 订单号 /
     *                   SKU 码 / 通知收件人 → 那是各环本来就有的 {@code key}；审计环是 {@code TargetId}；
     *                   敏感词环是**命中的词**；身份环是**请求路径**。没有就 null
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；定时任务 / 流线程上产生时为 null。
     *                   ⚠️ 它由**源环在记录那一刻**从 MDC 取好（见各 metrics 类的 {@code event(...)}），
     *                   不是并入本环时取的 —— 并入跑在 SSE 流线程上，那里 MDC 是空的
     * @param batchId    产生本条事件的请求**所属的演练批次**（块 7 · commit 8c 新增）；
     *                   {@code null} = 不属于任何演练批次（日常流量）。
     *                   ⚠️ 取值点与 {@code traceId} 逐字相同（源环记录那一刻现读 MDC），
     *                   原因也相同 —— 并入时读不到。它落进 {@code observed_event.BatchId}
     */
    public record ObservedEvent(long seq, long epochMilli, String kind, String severity,
                                String title, String detail, String key,
                                String objectType, String objectId, String traceId, String batchId) {
    }

    private final MallCacheMetrics cacheMetrics;
    private final MallLockMetrics lockMetrics;
    private final MallOrderStateMetrics orderMetrics;
    private final StockMetrics stockMetrics;
    private final AuditMetrics auditMetrics;
    private final NotifyMetrics notifyMetrics;
    private final SensitiveMetrics sensitiveMetrics;
    private final IdentityMetrics identityMetrics;

    /**
     * 事件台账（{@code observed_event}）的出口（块 7 · commit 8c）。⚠️ <b>调点只有本类一处</b>
     * —— 八个环各自发布会在「哪条事件算已发布」这件事上产生八份口径（其中七份迟早会漂），
     * 而 {@link #add} 本来就是**唯一**创建 {@link ObservedEvent} 的地方。
     *
     * <p>⚠️ 由此带来一个**必须知道**的后果：{@link #add} 只在 {@link #poll()} 里被调到，而
     * {@code poll()} 由 SSE 流线程驱动 ⇒ <b>没人打开实时日志页时，事件不落库</b>（合并环不动）。
     * 这是本笔的已知边界，如实记在这里，不假装它不存在（见类注释末节与 {@code EventPublisher}）。
     */
    private final EventPublisher eventPublisher;

    /** 合并环本体 + 单调游标（写者只做「数组赋值 + 游标自增」，读者靠 seq 自校验）。 */
    private final ObservedEvent[] events = new ObservedEvent[EVENT_CAPACITY];
    private final AtomicLong cursor = new AtomicLong();

    /** 每个源「已并入的最大 seq」；-1 = 还没并入过任何一条（空环的 seq 从 0 起，故 -1 是安全的初值）。 */
    private final long[] sourceSeq = new long[Kind.values().length];

    public EventBroadcaster(MallCacheMetrics cacheMetrics,
                            MallLockMetrics lockMetrics,
                            MallOrderStateMetrics orderMetrics,
                            StockMetrics stockMetrics,
                            AuditMetrics auditMetrics,
                            NotifyMetrics notifyMetrics,
                            SensitiveMetrics sensitiveMetrics,
                            IdentityMetrics identityMetrics,
                            EventPublisher eventPublisher) {
        this.cacheMetrics = cacheMetrics;
        this.lockMetrics = lockMetrics;
        this.orderMetrics = orderMetrics;
        this.stockMetrics = stockMetrics;
        this.auditMetrics = auditMetrics;
        this.notifyMetrics = notifyMetrics;
        this.sensitiveMetrics = sensitiveMetrics;
        this.identityMetrics = identityMetrics;
        this.eventPublisher = eventPublisher;
        Arrays.fill(sourceSeq, -1L);
    }

    /**
     * 把八个源环里**还没并入过**的事件并进合并环。由流线程每秒调一次。
     *
     * @return 本次并入的条数（页面「每秒事件速率」用的就是这个数 —— 它是**新增**而不是累计）。
     *         ⚠️ **冷启动那一拍会偏大**：八个环各自可能已经攒了一屏（≤512 × 8），首拍一并并入。
     *         这是**真实发生的事**（那一拍确实读到了这么多），不修正、不封顶 —— 页面按 60 秒窗口
     *         显示，尖峰自己会滚出去；`since(-1)` 也只取合并环留得下的最后 512 条。
     */
    public synchronized int poll() {
        long before = cursor.get();
        ingestCache();
        ingestLock();
        ingestOrder();
        ingestStock();
        ingestAudit();
        ingestNotify();
        ingestSensitive();
        ingestIdentity();
        return (int) (cursor.get() - before);
    }

    /**
     * 取 {@code afterSeq} **之后**的所有事件（按 seq 升序）。
     *
     * @param afterSeq 调用方上次读到的 seq；首次读给 {@code -1}（或任何 &lt; 0 的值）= 要一屏全量
     * @return 未被覆盖的部分；被覆盖掉的**不报错也不补**（覆盖即淘汰，同源环）
     */
    public synchronized List<ObservedEvent> since(long afterSeq) {
        long next = cursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        long from = Math.max(oldest, afterSeq + 1);
        List<ObservedEvent> snapshot = new ArrayList<>((int) Math.max(0L, next - from));
        for (long seq = from; seq < next; seq++) {
            ObservedEvent candidate = events[(int) (seq & EVENT_MASK)];
            // 读者可能读到被覆盖的槽位：每个槽位用 seq 自校验，对不上就跳过（宁可少一条，不给错位的一条）。
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    /** 合并环当前的「下一个 seq」减一；没有事件时返回 -1（与 {@link #since} 的入参同一坐标系）。 */
    public long latestSeq() {
        return cursor.get() - 1;
    }

    // ==================== 并入：每个源一段，游标推进写在各自的段里 ====================

    private void ingestCache() {
        long last = sourceSeq[Kind.CACHE.ordinal()];
        for (MallCacheEvent e : cacheMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.CACHE, fromCache(e), e.key(), e.seq(), "cache", str(e.key()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.CACHE.ordinal()] = last;
    }

    private void ingestLock() {
        long last = sourceSeq[Kind.LOCK.ordinal()];
        for (MallLockEvent e : lockMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.LOCK, fromLock(e), e.key(), e.seq(), "lock", str(e.key()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.LOCK.ordinal()] = last;
    }

    private void ingestOrder() {
        long last = sourceSeq[Kind.ORDER.ordinal()];
        for (OrderStateEvent e : orderMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.ORDER, fromOrder(e), e.key(), e.seq(), "order", str(e.key()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.ORDER.ordinal()] = last;
    }

    private void ingestStock() {
        long last = sourceSeq[Kind.STOCK.ordinal()];
        for (StockEvent e : stockMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.STOCK, fromStock(e), e.skuCode(), e.seq(), "sku", str(e.skuCode()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.STOCK.ordinal()] = last;
    }

    private void ingestAudit() {
        long last = sourceSeq[Kind.AUDIT.ordinal()];
        for (AuditEvent e : auditMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.AUDIT, fromAudit(e), e.actor(), e.seq(), str(e.targetType()), str(e.targetId()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.AUDIT.ordinal()] = last;
    }

    private void ingestNotify() {
        long last = sourceSeq[Kind.NOTIFY.ordinal()];
        for (NotifyEvent e : notifyMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.NOTIFY, fromNotify(e), e.key(), e.seq(), "notify", str(e.key()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.NOTIFY.ordinal()] = last;
    }

    private void ingestSensitive() {
        long last = sourceSeq[Kind.SENSITIVE.ordinal()];
        for (SensitiveEvent e : sensitiveMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.SENSITIVE, fromSensitive(e), e.scene(), e.seq(), "sensitive", str(e.words()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.SENSITIVE.ordinal()] = last;
    }

    private void ingestIdentity() {
        long last = sourceSeq[Kind.IDENTITY.ordinal()];
        for (IdentityEvent e : identityMetrics.recentEvents()) {
            if (e.seq() <= last) {
                continue;
            }
            add(e.epochMilli(), Kind.IDENTITY, fromIdentity(e), e.subject(), e.seq(), "identity", str(e.path()), e.traceId(), e.batchId());
            last = e.seq();
        }
        sourceSeq[Kind.IDENTITY.ordinal()] = last;
    }

    // ==================== 翻译：源事件 → (severity, title, detail) ====================

    /** 缓存环：kind ∈ {l1, l2, load, singleflight, evict, probe, transition}（取值表在 {@code MallCacheMetrics} 类注释）。 */
    private static String[] fromCache(MallCacheEvent e) {
        String result = String.valueOf(e.result());
        switch (String.valueOf(e.kind())) {
            case "l1":
                return hit("hit".equals(result) ? "缓存命中（L1）" : "L1 未命中", result, e.cache(), e.key());
            case "l2":
                switch (result) {
                    case "hit":
                        return hit("缓存命中（L2）", result, e.cache(), e.key());
                    case "miss":
                        return hit("L2 未命中", result, e.cache(), e.key());
                    case "degraded":
                        return new String[]{Severity.WARN.tag, "L2 降级跳过", cacheDetail(e, result)};
                    default:
                        return new String[]{severityOf(result), "L2 读取失败", cacheDetail(e, result)};
                }
            case "load":
                return hit("回源查库", result, e.cache(), e.key());
            case "singleflight":
                return hit("并发合并（单飞）", result, e.cache(), e.key());
            case "evict":
                switch (result) {
                    case "evicted":
                        return hit("缓存失效", result, e.cache(), e.key());
                    case "skipped_degraded":
                        return new String[]{Severity.WARN.tag, "缓存失效被跳过（降级窗口）", cacheDetail(e, result)};
                    default:
                        return new String[]{severityOf(result), "缓存失效失败", cacheDetail(e, result)};
                }
            case "probe":
                return "ok".equals(result)
                        ? new String[]{Severity.INFO.tag, "L2 探活成功", cacheDetail(e, result)}
                        : new String[]{Severity.WARN.tag, "L2 探活失败", cacheDetail(e, result)};
            default:
                // kind=transition：healthy / degraded（L2 可用性**真实翻转**时才有）
                return "healthy".equals(result)
                        ? new String[]{Severity.INFO.tag, "L2 可用性恢复", cacheDetail(e, result)}
                        : new String[]{Severity.WARN.tag, "L2 进入降级窗口", cacheDetail(e, result)};
        }
    }

    /** 锁环：kind ∈ {acquire, hold, transition}（取值表在 {@code MallLockMetrics} 类注释）。 */
    private static String[] fromLock(MallLockEvent e) {
        String result = String.valueOf(e.result());
        switch (String.valueOf(e.kind())) {
            case "acquire":
                switch (result) {
                    case "acquired":
                        return new String[]{Severity.INFO.tag, "取锁成功", lockDetail(e, result)};
                    case "timeout":
                        // fail-open 直行：不是错误，是「互斥没生效」的降级 —— 页面必须看得见。
                        return new String[]{Severity.WARN.tag, "取锁超时（fail-open 直行）", lockDetail(e, result)};
                    case "degraded":
                        return new String[]{Severity.WARN.tag, "取锁被跳过（降级窗口）", lockDetail(e, result)};
                    default:
                        return new String[]{severityOf(result), "取锁异常", lockDetail(e, result)};
                }
            case "hold":
                switch (result) {
                    case "released":
                        return new String[]{Severity.INFO.tag, "释放锁", lockDetail(e, result)};
                    case "lease_lost":
                        return new String[]{Severity.WARN.tag, "租约丢失（临界区可能重叠）", lockDetail(e, result)};
                    default:
                        return new String[]{severityOf(result), "释放锁异常", lockDetail(e, result)};
                }
            default:
                return "healthy".equals(result)
                        ? new String[]{Severity.INFO.tag, "锁可用性恢复", lockDetail(e, result)}
                        : new String[]{Severity.WARN.tag, "锁进入降级窗口", lockDetail(e, result)};
        }
    }

    /** 订单状态环：kind ∈ {transition, illegal, async}（取值表在 {@code MallOrderStateMetrics} 类注释）。 */
    private static String[] fromOrder(OrderStateEvent e) {
        String result = String.valueOf(e.result());
        switch (String.valueOf(e.kind())) {
            case "transition":
                String title = "状态流转 " + e.from() + " → " + e.to();
                return new String[]{severityOf(result), title,
                        "action=" + e.action() + " result=" + result + " order#"
                                + (e.key() == null ? "-" : e.key())};
            case "illegal":
                // 两种来源：真观测到非法态（result=null）与出生闸拦下（result=rejected）
                return "rejected".equals(result)
                        ? new String[]{Severity.WARN.tag, "出生闸拦下非法态",
                                "三位=" + e.shape() + " order#" + (e.key() == null ? "-" : e.key())}
                        : new String[]{Severity.WARN.tag, "观测到非法状态",
                                "三位=" + e.shape() + " order#" + (e.key() == null ? "-" : e.key())};
            default:
                String reason = e.shape() == null ? "" : " reason=" + e.shape();
                return new String[]{severityOf(result), "异步流转" + asyncSuffix(result),
                        "result=" + result + reason + " order#" + (e.key() == null ? "-" : e.key())};
        }
    }

    private static String asyncSuffix(String result) {
        switch (result) {
            case "error":
                return "失败";
            case "rejected":
                return "被拒";
            default:
                return "完成";
        }
    }

    /** 库存环：kind ∈ {deduct, release}（取值表在 {@code StockMetrics} 类注释）。 */
    private static String[] fromStock(StockEvent e) {
        String result = String.valueOf(e.result());
        boolean deduct = "deduct".equals(e.kind());
        String title;
        switch (result) {
            case "ok":
                title = deduct ? "库存扣减" : "库存释放";
                break;
            case "insufficient":
                title = "库存不足（未扣，防超卖命中）";
                break;
            case "missing":
                title = "释放未命中（无该 SKU 行）";
                break;
            default:
                title = deduct ? "库存扣减失败" : "库存释放失败";
                break;
        }
        return new String[]{severityOf(result), title,
                "kind=" + e.kind() + " sku=" + e.skuCode() + " qty=" + e.qty() + " result=" + result};
    }

    private static String[] fromAudit(AuditEvent e) {
        boolean ok = "written".equals(String.valueOf(e.result()));
        String detail = "actor=" + e.actor() + " action=" + e.action()
                + (e.reason() == null ? "" : " reason=" + e.reason());
        return new String[]{ok ? Severity.INFO.tag : Severity.WARN.tag,
                ok ? "审计写入" : "审计写入失败", detail};
    }

    private static String[] fromNotify(NotifyEvent e) {
        boolean ok = "sent".equals(String.valueOf(e.result()));
        String detail = "to=" + e.toType() + " event=" + e.eventType()
                + (e.reason() == null ? "" : " reason=" + e.reason());
        return new String[]{ok ? Severity.INFO.tag : Severity.WARN.tag,
                ok ? "通知发送" : "通知发送失败", detail};
    }

    private static String[] fromSensitive(SensitiveEvent e) {
        boolean masked = "masked".equals(String.valueOf(e.result()));
        String detail = "scene=" + e.scene() + " hit=" + e.hit() + "/" + e.total();
        return new String[]{masked ? Severity.INFO.tag : Severity.WARN.tag,
                masked ? "敏感词打码" : "敏感词拒收", detail};
    }

    private static String[] fromIdentity(IdentityEvent e) {
        boolean passed = "passed".equals(String.valueOf(e.result()));
        String detail = "subject=" + e.subject() + (e.reason() == null ? "" : " reason=" + e.reason());
        return new String[]{passed ? Severity.INFO.tag : Severity.WARN.tag,
                passed ? "身份放行" : "身份拒绝", detail};
    }

    // ==================== 小工具 ====================

    /** 命中类事件（缓存命中 / 回源 / 单飞）：一律 info。 */
    private static String[] hit(String title, String result, String cache, String key) {
        return new String[]{Severity.INFO.tag, title, cacheAspect(result, cache, key)};
    }

    /** 严重度规则（**唯一出处**，见类注释第 2 件）。 */
    private static String severityOf(String result) {
        if ("error".equals(result)) {
            return Severity.ERROR.tag;
        }
        switch (result) {
            case "failed":
            case "rejected":
            case "timeout":
            case "degraded":
            case "skipped_degraded":
            case "insufficient":
            case "lease_lost":
                return Severity.WARN.tag;
            default:
                return Severity.INFO.tag;
        }
    }

    private static String cacheDetail(MallCacheEvent e, String result) {
        return cacheAspect(result, e.cache(), e.key());
    }

    private static String cacheAspect(String result, String cache, String key) {
        return "cache=" + (cache == null ? "-" : cache) + " result=" + result
                + " key=" + (key == null ? "-" : key);
    }

    private static String lockDetail(MallLockEvent e, String result) {
        return "lock=" + (e.lock() == null ? "-" : e.lock()) + " result=" + result
                + " key=" + (e.key() == null ? "-" : e.key());
    }

    /** 并入一条：分配合并环 seq 并覆盖式写入（写者只做两件事，读者靠 seq 自校验）。
     *
     * @param srcSeq 源环的 seq —— 只进 {@code detail} 尾部（形如 {@code src=stock#128}），
     *               供排查时定位回原环；⚠️ **不是** {@link ObservedEvent#seq()}（那个是本环的）
     * @param objectType 被操作对象的**类型**（块 7 · commit 8 新增）—— 八个 {@code ingestXxx}
     *                   在调用点逐环给定；取值口径见 {@link ObservedEvent#objectType()}
     * @param objectId   被操作对象的**标识**（同上）；取值口径见 {@link ObservedEvent#objectId()}
     * @param traceId    产生本条事件的那次请求的号（同上）；不在请求线程上时为 {@code null}
     * @param batchId    产生本条事件的请求所属的演练批次（块 7 · commit 8c，同上）；
     *                   不属于任何批次时为 {@code null}
     */
    private void add(long epochMilli, Kind kind, String[] triple, String key, long srcSeq,
                     String objectType, String objectId, String traceId, String batchId) {
        long seq = cursor.getAndIncrement();
        ObservedEvent event = new ObservedEvent(
                seq, epochMilli, kind.tag, triple[0], triple[1], triple[2] + " src=" + kind.tag + "#" + srcSeq,
                key == null ? null : String.valueOf(key), objectType, objectId, traceId, batchId);
        events[(int) (seq & EVENT_MASK)] = event;
        // 块 7 · commit 8c：并入合并环 = 「这条事件已经成形」的那一刻，发布台账就走这里。
        // ⚠️ 发布方**不抛**（fail-soft，见 EventPublisher）—— 台账落不了库不该让日志页断流。
        // ⚠️ 顺序刻意是「先入环、后发布」：发布方再怎么慢/挂，内存环与页面都已拿到这一条。
        eventPublisher.publish(event);
    }

    /** {@code null → null}（三个新字段的空值口径与 {@code key} 一致：缺就记 {@code null}，
     * 不写 {@code "null"} 字面量）；否则转成字符串。 */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
