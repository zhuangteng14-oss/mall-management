package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.observe.metrics.AuditMetrics.AuditEvent;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics.IdentityEvent;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics.NotifyEvent;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics.ProtectionEvent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件广播器 —— 把**九个事件环**（既有三环 + 块 7 五环 + D 段一环「防护」）汇成一条有序的事件流，
 * 供 {@code POST /admin/observe/events/stream}（实时日志页）读。
 *
 * <p>⚠️ <b>第九环（{@link Kind#PROTECTION}）与其他八环有一处性质不同，必须知道</b>：前八环记的是
 * 「业务与基础设施发生了什么」，第九环记的是「**防护层自己做了什么**」（限流拦下 / 熔断翻转 /
 * 池挤出）。它由 {@code protect/} 包内三处闸写入，且熔断那一路的翻转<b>可能没有任何业务请求在跑</b>
 * （Resilience4j 的半开由它自己的线程按等待期推动）⇒ 它不是「业务请求的副产品」，
 * 是一条独立的信号源。本类对它的处置与前八环逐字相同（只读、只过滤），
 * 差别只在「它的写入方在哪」——写进源环的时机不受本类影响。
 *
 * <h2>【为什么需要这一层：九个环各自「有界且会被覆盖」，而读的人只想要一条时间线】</h2>
 * 每个环都是 2048 槽的**覆盖式**环形缓冲，读法只有「读最近一屏」。实时日志页要的是
 * 「**这一拍新发生的**」，于是需要一个「按源记住读到哪了」的游标 —— 那就是本类。
 * ⚠️ <b>本类不重写任何环、不改变它们的语义</b>：只读 {@code recentEvents()}（各环公开的
 * 只读快照），且**只过滤、不加工**业务字段（例外：下面「派生字段」一节）。
 *
 * <h2>【三件事，逐件说清】</h2>
 * <ol>
 *   <li><b>归一化</b>：九环的事件记录字段各不相同（缓存环有 {@code cache} 没有 {@code orderId}，
 *       订单环有九列，防护环是 {@code key / scope / detail} 三列……）。本类把它们翻成一个统一的
 *       {@link ObservedEvent}（{@code kind / severity / title / detail / key}）——「哪些平台字段」
 *       是**页面要的**，不是「环里有的」。翻译表逐条写在下面的 {@code fromXxx} 私有方法里。</li>
 *   <li><b>severity 规则（一处定义，九处适用）</b>：
 *       <pre>
 *       error   ← result = error（抛异常 / 真失败）
 *       protect ← 入站限流 / 熔断翻转（blocked / opened / half_open / shed）、身份鉴权拒绝、
 *                 重复提交拦截、敏感词拒收 / 打码
 *                 —— 「防护层主动挡下或改写，业务没有被执行」；页面用紫色，**不计入业务失败率**
 *       warn    ← result ∈ {failed, rejected, timeout, degraded, skipped_degraded,
 *                          insufficient, lease_lost, retry_failed}
 *                 —— 「真的业务失败 / 降级 / 重试仍失败」（如库存不足、超卖拦截、异步流转被拒）
 *       info    ← 其余（成功路径、以及 miss / missing 这类「什么也没发生」）
 *       </pre>
 *       ⚠️ 它是**展示口径**（页面据此配色），不是契约 —— 改它只影响日志页的颜色，
 *       不影响任何计数。⚠️ {@code miss} / {@code missing} 刻意留在 info：
 *       本环回答「**发生了什么**」，把每一次未命中都染成黄/红会让整屏都是警告色。
 *       ⚠️ 防护拦截（protect）**既不是 warn 也不是 error**：它是系统按预期挡住了请求，
 *       业务失败率（warn+error）与健康度（只看 error / P99）都不含它。
 *       ⚠️ D 段第九环的 {@code closed}（熔断试探通过、恢复关闭）也刻意留在 info：
 *       那是**恢复**，染成黄/红会让「闸刚救回来一次」看起来像又一次故障。</li>
 *   <li><b>游标 + 合并环</b>：每个源记一个「已并入的最大 seq」（初始 -1 = 空环）；
 *       {@link #poll()} 把九环里更新的条目按源顺序并入本类的 2048 槽环，
 *       {@link #since(long)} 让调用方按「我上次读到哪」取增量。</li>
 * </ol>
 *
 * <h2>【⚠️ 边界：一拍内超过 2048 条会丢——而且**丢的是最早的**，不是最新的】</h2>
 * 源环自己就是覆盖式的：一拍内某源写了 3000 条，它的 {@code recentEvents()} 只答得出最后 2048 条。
 * 本类**照抄**这个语义（合并环同样是 2048 槽覆盖式，与九环同值），不另设统计口径。
 * 依据：@调度器/攻击引擎把每秒事件数打到 2048 以上时，**逐条读已经不是正确的读法**
 * （那要看 {@code mall.*} 的计数），日志页要的是**可读的过程**，不是完整审计流水。
 *
 * <p><b>拒收策略（块 7 · commit 10 · 裁决 2：容量 512 → 2048 + 环满监控）</b>：
 * 「丢最老的」不变（覆盖式环不阻塞生产方、不报错、不重试 —— 这条**刻意不改**：本类是热路径上的
 * 只读旁路，让它去背压业务线程会把观测变成故障源）。改的是**丢了要说**：两个丢失口各记各的数
 * —— 源环 → 合并环 记成全局累计 {@link #droppedIngest()}，合并环 → 连接 随那次读返回
 * （{@link Batch#dropped()}），由实时日志页上屏成帧里的 {@code dropped} / {@code droppedTotal}。
 * 为什么两个口的口径不同，见类末「环满记账」一节。
 * <p>容量取 2048 而不是更大的理由：它是**这一拍的缓冲**，不是流水账 —— 攻击引擎满档时每秒数千条，
 * 再大的环也只是把「丢的时间点」往后挪；真正的取舍是让日志页在**满档下仍然可读**，
 * 而「丢了多少」由计数如实交代（堆内存换算见提交说明，2048 槽 × 8 环的引用成本可忽略）。
 *
 * <h2>【派生字段：{@code seq} 是本类的，不是源的】</h2>
 * {@link ObservedEvent#seq()} 是**合并环自己的**单调序号（页面的「取增量」用它），
 * 与源环的 seq **无关**、也**不可比**。源的 seq 与所属环名一起写进 {@code detail}
 * （形如 {@code stock#128}），排查时能定位回原环。
 *
 * <h2>【⚠️ 同一拍内的顺序 = **环序**，不是事件发生的先后】</h2>
 * {@link #poll()} 是**一环一环地并**的（{@code cache → lock → order → stock → audit → notify
 * → sensitive → identity → protection}），故同一拍里跨环的事件在合并环中是**按环分组**的：
 * 本拍最后一刻发生的缓存事件，会排在本拍最早发生的订单事件**前面**。这是本类明确的口径，
 * 不是实现疏漏 —— 逐条按墙钟全局排序需要跨环合并后重排（而各环的墙钟只保证「同环内单调」），
 * 本块的取舍是**不排序**：日志页要的是「这一拍发生了什么」，跨环的毫秒级先后由卡片上的
 * {@code ts} 自己还原。测试钉这条口径的位置：{@code EventBroadcasterTest#severityFollowsTheOneRuleTable}。
 * <p>⚠️ 第九环排在**最后**，理由只有一条：**不改既有八环的相对顺序**（那八条顺序写在文档里、
 * 也被测试钉着；把它插在中间会让所有引用「第 N 环」的地方集体漂移）。⚠️ 由此**不要**从
 * 合并环的顺序去推因果 —— {@code protection} 排在最末**不代表**「这一拍里防护事件发生得最晚」，
 * 环序与事件时间无关这件事对第九环同样成立。
 *
 * <h2>【热路径代价】</h2>
 * {@link #poll()} 是**被流线程每秒调一次**的（不是在业务线程上）：九次
 * {@code recentEvents()}（各 ≤2048 条、无锁）+ 一次 {@code synchronized}（+ 环满时两个计数自增）。
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

    /** 合并环容量：与九个源环同值（2048 = 2 的幂，位与定位；语义与取舍见类注释最后一节）。 */
    private static final int EVENT_CAPACITY = 2048;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    /**
     * 事件类别 —— 前八类与《块 7 参考》§4.2 的八类一一对应，第九类 {@link #PROTECTION} 是
     * D 段新增（顺序 = 页面图例顺序，也 = {@link #poll()} 的并入顺序）。
     * ⚠️ {@code tag} 是**页面筛选键**，改它等于改协议（前端 {@code observe-labels.js} 的
     * {@code KINDS} 与攻击页的过滤类都按它匹配）；加类要两边一起加。
     */
    public enum Kind {
        CACHE("cache", "缓存"),
        LOCK("lock", "锁"),
        ORDER("order", "订单"),
        STOCK("stock", "库存"),
        AUDIT("audit", "审计"),
        NOTIFY("notify", "通知"),
        SENSITIVE("sensitive", "敏感词"),
        IDENTITY("identity", "身份"),
        /** 防护层自己的动作（限流拦下 / 熔断翻转 / 池挤出）—— D 段新增，源环是 {@code ProtectionMetrics}。 */
        PROTECTION("protection", "防护");

        /** 页面用作筛选键 / 配色的稳定标识（英文，别拿 {@code label} 当键）。 */
        public final String tag;
        /** 页面图例上的中文名。 */
        public final String label;

        Kind(String tag, String label) {
            this.tag = tag;
            this.label = label;
        }
    }

    /** 严重度四档（规则见类注释第 2 件）。 */
    public enum Severity {
        INFO("info"), PROTECT("protect"), WARN("warn"), ERROR("error");

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
     *                   敏感词环是 {@code sensitive}，身份环是 {@code identity}，
     *                   防护环（D 段）是 {@code protection}。取值在九个 {@code ingestXxx} 里逐环给定
     * @param objectId   上面那个类型的**具体标识**（块 7 · commit 8 新增）：缓存键 / 锁键 / 订单号 /
     *                   SKU 码 / 通知收件人 → 那是各环本来就有的 {@code key}；审计环是 {@code TargetId}；
     *                   敏感词环是**命中的词**；身份环是**请求路径**；防护环是**防护开关 key**
     *                   （{@code rateLimit} / {@code circuitBreak} / {@code poolIsolation}……）。
     *                   没有就 null
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；定时任务 / 流线程上产生时为 null。
     *                   ⚠️ 它由**源环在记录那一刻**从 MDC 取好（见各 metrics 类的 {@code event(...)}），
     *                   不是并入本环时取的 —— 并入跑在 SSE 流线程上，那里 MDC 是空的
     * @param batchId    产生本条事件的请求**所属的演练批次**（块 7 · commit 8c 新增）；
     *                   {@code null} = 不属于任何演练批次（日常流量）。
     *                   ⚠️ 取值点与 {@code traceId} 逐字相同（源环记录那一刻现读 MDC），
     *                   原因也相同 —— 并入时读不到。它落进 {@code observed_event.BatchId}
     * @param actorId    <b>发起那次请求的业务号</b>（块 7 · 阶段 3a.3 新增）—— 「<b>谁</b>做的」。
     *                   {@code null} = 无身份：闸关着（{@code app.auth.enabled=false}）、
     *                   或这条事件产生在**非请求线程**上（线程池 / 定时任务 / SSE 流线程）。
     *                   ⚠️ 与 {@code traceId} 一样由**源环在记录那一刻**现读 MDC，不是并入时取的。
     *                   ⚠️ 审计环是唯一一个「{@code actorId} 另有来源」的环（取审计行的
     *                   {@code Actor} 列，见 {@code AuditMetrics.AuditEvent#actorId}）
     * @param actorRole  发起者的**角色号**（块 7 · 阶段 3a.3 新增）；与 {@code actorId} 同生共死
     *                   （要么都有、要么都 {@code null}）。页面把这一对拼成 {@code actor} 对象
     * @param durationUs 本条事件主体的耗时（**微秒**，块 7 · 阶段 3a.3 新增）；{@code null} =
     *                   没测到 / 不适用（各环口径不同，逐环写在各 {@code *Metrics#event} 的 javadoc 里）。
     *                   ⚠️ 单位是微秒不是毫秒：L1 命中这类操作是**纳秒级**，毫秒永远显示 0
     * @param remaining  <b>扣减那一刻该 SKU 的剩余件数</b>（块 7 · 阶段 3a.3 第二步新增）。
     *                   ⚠️ 九个环里<b>只有库存环的扣减档</b>有这个量，其余八环恒为 {@code null}
     *                   —— 那是**判定**不是遗漏：它和 {@code key} / {@code objectType} /
     *                   {@code objectId} 是同一类东西（「各环自己的业务字段」，
     *                   见 {@code Sourced} 类注释里那段「刻意不声明 key()」的裁定），
     *                   故由 {@code ingestStock} 在调用点给定，**不进**九环公共面。
     *                   ⚠️ 取数点、三类 {@code null}、并发边界都在
     *                   {@code StockMetrics.StockEvent#remaining()} 的 javadoc 里（唯一出处，别处不重抄）。
     * @param fields     <b>结构化明细</b>（块 7 · 阶段 3a.3 新增）：把 {@code detail} 里那些
     *                   {@code k=v} 拆成一张表，供前端拼自然语言句子（{@code sentence()}）用。
     *                   ⚠️ 与 {@code detail} <b>同源</b>（在同一个 {@link Emit} 里一起构造）
     *                   —— 分两处构造必然漂移，那正是本仓反复吃的亏。
     *                   ⚠️ 可空：没构造出结构化字段时为 {@code null}（前端须按「拿 detail 兜底」处理）
     */
    public record ObservedEvent(long seq, long epochMilli, String kind, String severity,
                                String title, String detail, String key,
                                String objectType, String objectId, String traceId, String batchId,
                                String actorId, Integer actorRole, Long durationUs,
                                Integer remaining, Map<String, Object> fields) {
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
     * 第 9 类源环（D 段新增）。⚠️ 它是九个源里**唯一一个写入方可能不在业务请求线程上**的
     * —— 熔断半开由 Resilience4j 自己的线程推动（见类注释首节）。本类对此无所谓（只读快照），
     * 但排障时要记住：本环有事件**不等于**那一刻有业务流量。
     */
    private final ProtectionMetrics protectionMetrics;

    /**
     * 事件台账（{@code observed_event}）的出口（块 7 · commit 8c）。⚠️ <b>调点只有本类一处</b>
     * —— 九个环各自发布会在「哪条事件算已发布」这件事上产生九份口径（其中八份迟早会漂），
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
                            ProtectionMetrics protectionMetrics,
                            EventPublisher eventPublisher) {
        this.cacheMetrics = cacheMetrics;
        this.lockMetrics = lockMetrics;
        this.orderMetrics = orderMetrics;
        this.stockMetrics = stockMetrics;
        this.auditMetrics = auditMetrics;
        this.notifyMetrics = notifyMetrics;
        this.sensitiveMetrics = sensitiveMetrics;
        this.identityMetrics = identityMetrics;
        this.protectionMetrics = protectionMetrics;
        this.eventPublisher = eventPublisher;
        Arrays.fill(sourceSeq, -1L);
    }

    /**
     * 把九个源环里**还没并入过**的事件并进合并环。由流线程每秒调一次。
     *
     * @return 本次并入的条数（页面「每秒事件速率」用的就是这个数 —— 它是**新增**而不是累计）。
     *         ⚠️ **冷启动那一拍会偏大**：九个环各自可能已经攒了一屏（≤2048 × 9），首拍一并并入。
     *         这是**真实发生的事**（那一拍确实读到了这么多），不修正、不封顶 —— 页面按 60 秒窗口
     *         显示，尖峰自己会滚出去；`since(-1)` 也只取合并环留得下的最后 2048 条。
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
        ingestProtection();
        return (int) (cursor.get() - before);
    }

    /**
     * 一次增量读的结果：这一批事件 + **这次读丢了多少条**（原因见 {@link #sinceWithLoss}）。
     *
     * <p>⚠️ {@code dropped} 是**调用方这次询问**造成的丢失，不是全局数：两条连接各问各的，
     * 各自只看到自己跟丢的那部分（游标每连接一份，见类注释「每连接一份」）。
     */
    public record Batch(List<ObservedEvent> events, long dropped) {
    }

    /**
     * 取 {@code afterSeq} **之后**的所有事件（按 seq 升序），连同这次读的丢失数。
     *
     * @param afterSeq 调用方上次读到的 seq；首次读给 {@code -1}（或任何 &lt; 0 的值）= 要一屏全量
     * @return 未被覆盖的部分 + 被覆盖掉的条数；被覆盖掉的**不报错也不补**（覆盖即淘汰，同源环），
     *         但**会如实报数**（这就是本类的「拒收策略」里「丢了要说」的那一半）
     */
    public synchronized Batch sinceWithLoss(long afterSeq) {
        long next = cursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        long from = Math.max(oldest, afterSeq + 1);
        // ⚠️ 调用方上次读到 afterSeq，而环里最老的只剩 oldest ⇒ [afterSeq+1, oldest) 那一截
        //    已经被覆盖，永远补不回来。**问一次只算一次**：调用方拿到帧后会把游标推到
        //    latestSeq()（越过缺口），下一次问的 from 已经 ≥ oldest，不会重复计。
        // ⚠️ afterSeq < 0 是**首帧一屏**（新开的连接要一屏全量），那是本类的语义而不是丢失，
        //    不计 —— 否则每开一个页面都会凭空记下 2048 条「丢失」。
        long dropped = afterSeq < 0L ? 0L : Math.max(0L, oldest - (afterSeq + 1L));
        List<ObservedEvent> snapshot = new ArrayList<>((int) Math.max(0L, next - from));
        for (long seq = from; seq < next; seq++) {
            ObservedEvent candidate = events[(int) (seq & EVENT_MASK)];
            // 读者可能读到被覆盖的槽位：每个槽位用 seq 自校验，对不上就跳过（宁可少一条，不给错位的一条）。
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return new Batch(snapshot, dropped);
    }

    /**
     * 只看事件的那一份（{@link #sinceWithLoss} 的薄壳）。留着它是因为**大多数调用方不关心丢失数**
     * —— 只有实时日志页把它上屏；测试与其余读法用它即可，读的是同一段临界区（结果逐条相同）。
     */
    public synchronized List<ObservedEvent> since(long afterSeq) {
        return sinceWithLoss(afterSeq).events();
    }

    /** 合并环当前的「下一个 seq」减一；没有事件时返回 -1（与 {@link #since} 的入参同一坐标系）。 */
    public long latestSeq() {
        return cursor.get() - 1;
    }

    // ==================== A4：死信事件（块 7 收尾 · 2026-10-17） ====================

    /**
     * 本进程累计收到的死信条数（{@code ObservedEventConsumer} 重试耗尽后按**批内条数**加）。
     *
     * <p>⚠️ 它是**累计**（重启归零），不是 DLQ 队列深度：读队列深度要连 broker，
     * 而这里要的是「本次运行期间有多少条证据没落库」这个页面可读的数。
     */
    private final AtomicLong deadLetterCount = new AtomicLong();

    /**
     * 发一条**ERROR 级**的防护事件，把「一批事件落库失败、已转死信」这件事送进合并环
     * （因此实时日志页、链路抽屉、指标面板都能看到），并累加死信计数。
     *
     * <p>为什么由 {@code EventBroadcaster} 提供入口，而不是让消费者自己拼一个
     * {@link ObservedEvent}：合并环的 seq 分配与「先入环、后发布」的顺序只在这里维护
     * （见 {@link #add} 的注释）。消费者从外面 new 一条会绕过 seq 分配 ⇒ 页面游标错位。
     *
     * <p>⚠️ 本方法**不抛**：死信上报失败不能反过来把消费者的 NACK/ACK 流程打断
     * （fail-soft 与 {@link EventPublisher#publish} 同口径）。
     *
     * @param traceId 原始事件的 traceId（整批取第一个非空；一批可能跨多个 traceId，
     *                所以 {@code fields.traces} 里另记「本批涉及的 traceId 个数」）
     * @param detail  人话明细（异常类名 + 错误信息 + 条数）
     * @param fields  结构化明细（供链路抽屉展开）
     * @return 本条死信事件的累计序号（从 1 起）
     */
    public synchronized long emitDeadLetter(String traceId, String detail, Map<String, Object> fields) {
        long n = deadLetterCount.incrementAndGet();
        long seq = cursor.getAndIncrement();
        Map<String, Object> f = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
        ObservedEvent event = new ObservedEvent(
                seq, System.currentTimeMillis(), Kind.PROTECTION.tag, Severity.ERROR.tag,
                "事件落库失败 ⇒ 转死信", detail + " src=dlq#" + n,
                null, "dlq", null, traceId, null, null, null, null, null, f);
        events[(int) (seq & EVENT_MASK)] = event;
        eventPublisher.publish(event);
        return n;
    }

    /** 本进程累计死信条数（见 {@link #emitDeadLetter}）。 */
    public long deadLetterCount() {
        return deadLetterCount.get();
    }

    // ==================== 并入：每个源一段，游标推进写在各自的段里 ====================

    private void ingestCache() {
        long previous = sourceSeq[Kind.CACHE.ordinal()];
        long last = previous;
        long first = -1L;
        for (MallCacheEvent e : cacheMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            add(e, Kind.CACHE, fromCache(e), e.key(), e.seq(), "cache", str(e.key()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.CACHE.ordinal()] = last;
    }

    private void ingestLock() {
        long previous = sourceSeq[Kind.LOCK.ordinal()];
        long last = previous;
        long first = -1L;
        for (MallLockEvent e : lockMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            add(e, Kind.LOCK, fromLock(e), e.key(), e.seq(), "lock", str(e.key()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.LOCK.ordinal()] = last;
    }

    private void ingestOrder() {
        long previous = sourceSeq[Kind.ORDER.ordinal()];
        long last = previous;
        long first = -1L;
        for (OrderStateEvent e : orderMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            add(e, Kind.ORDER, fromOrder(e), e.key(), e.seq(), "order", str(e.key()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.ORDER.ordinal()] = last;
    }

    private void ingestStock() {
        long previous = sourceSeq[Kind.STOCK.ordinal()];
        long last = previous;
        long first = -1L;
        for (StockEvent e : stockMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            // ⚠️ 九个调用点里**唯一**传真值的一个：本环的扣减档带「当时剩余」，
            //    释放档为 null（见 StockMetrics.StockEvent#remaining()）。
            add(e, Kind.STOCK, fromStock(e), e.skuCode(), e.seq(), "sku", str(e.skuCode()), e.remaining());
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.STOCK.ordinal()] = last;
    }

    private void ingestAudit() {
        long previous = sourceSeq[Kind.AUDIT.ordinal()];
        long last = previous;
        long first = -1L;
        for (AuditEvent e : auditMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            add(e, Kind.AUDIT, fromAudit(e), e.actor(), e.seq(), str(e.targetType()), str(e.targetId()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.AUDIT.ordinal()] = last;
    }

    private void ingestNotify() {
        long previous = sourceSeq[Kind.NOTIFY.ordinal()];
        long last = previous;
        long first = -1L;
        for (NotifyEvent e : notifyMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            add(e, Kind.NOTIFY, fromNotify(e), e.key(), e.seq(), "notify", str(e.key()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.NOTIFY.ordinal()] = last;
    }

    private void ingestSensitive() {
        long previous = sourceSeq[Kind.SENSITIVE.ordinal()];
        long last = previous;
        long first = -1L;
        for (SensitiveEvent e : sensitiveMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            add(e, Kind.SENSITIVE, fromSensitive(e), e.scene(), e.seq(), "sensitive", str(e.words()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.SENSITIVE.ordinal()] = last;
    }

    private void ingestIdentity() {
        long previous = sourceSeq[Kind.IDENTITY.ordinal()];
        long last = previous;
        long first = -1L;
        for (IdentityEvent e : identityMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            add(e, Kind.IDENTITY, fromIdentity(e), e.subject(), e.seq(), "identity", str(e.path()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.IDENTITY.ordinal()] = last;
    }

    private void ingestProtection() {
        long previous = sourceSeq[Kind.PROTECTION.ordinal()];
        long last = previous;
        long first = -1L;
        for (ProtectionEvent e : protectionMetrics.recentEvents()) {
            if (first < 0L) {
                first = e.seq();
            }
            if (e.seq() <= last) {
                continue;
            }
            // ⚠️ 这里的 key 取的是**防护开关 key**（rateLimit / circuitBreak / poolIsolation），
            //    不是「被拦的那个业务键」—— 防护事件没有业务键。页面「涉及对象」那一列因此显示
            //    「防护开关名」，这是刻意的：它回答「哪道闸动了」，而不是「谁被拦了」
            //    （后者要看同拍里业务侧的 429/503 记录）。
            add(e, Kind.PROTECTION, fromProtection(e), e.key(), e.seq(),
                    "protection", str(e.key()), /* remaining */ null);
            last = e.seq();
        }
        droppedIngest += missed(previous, first);
        sourceSeq[Kind.PROTECTION.ordinal()] = last;
    }

    // ==================== 翻译：源事件 → Emit(severity, title, detail, fields) ====================

    /**
     * 一条**翻译好**的事件：给页面看的那三个字符串 + 给结构化消费方的字段表
     * （块 7 · 阶段 3a.3 新增）。
     *
     * <h2>【为什么把 {@code fields} 和 {@code detail} 放在同一个对象里】</h2>
     * 它们说的是**同一批事实**的两种呈现：{@code detail} 是给人一眼扫的 {@code k=v} 串，
     * {@code fields} 是给前端拼自然语言句子的表。若分两个方法各构造一遍，改了一个忘了另一个
     * 就会漂移 —— 而「同一事实两处实现、迟早在某一条分支上不一致」正是本仓反复吃的亏
     * （见 {@code MallCacheMetrics} 类注释立的第 6 条规范）。故它们**只能**在同一个
     * {@code Emit} 构造里一起产生。
     *
     * @param severity 三档之一（{@link Severity#tag}）
     * @param title    卡片第一行（中文短标题）
     * @param detail   卡片第二行；最终会被 {@link #add} 追加 {@code src=环名#源seq}
     * @param fields   结构化明细；{@code null} = 这一环没构造（前端须回落到 {@code detail}）。
     *                 ⚠️ 允许值本身为 {@code null}（与 {@code key} 同口径：缺就记 null，
     *                 不写 {@code "-"} 也不写 {@code "null"} 字面量）
     */
    private record Emit(String severity, String title, String detail, Map<String, Object> fields) {
    }

    /** 缓存环：kind ∈ {l1, l2, load, singleflight, evict, probe, transition}（取值表在 {@code MallCacheMetrics} 类注释）。 */
    private static Emit fromCache(MallCacheEvent e) {
        String result = String.valueOf(e.result());
        switch (String.valueOf(e.kind())) {
            case "l1":
                return hit("hit".equals(result) ? "缓存命中（L1）" : "L1 未命中", result, e.cache(), e.key(), e.reason());
            case "l2":
                switch (result) {
                    case "hit":
                        return hit("缓存命中（L2）", result, e.cache(), e.key(), e.reason());
                    case "miss":
                        return hit("L2 未命中", result, e.cache(), e.key(), e.reason());
                    case "degraded":
                        return cacheEmit(e, Severity.WARN.tag, "L2 降级跳过", result);
                    default:
                        return cacheEmit(e, severityOf(result), "L2 读取失败", result);
                }
            case "load":
                return hit("回源查库", result, e.cache(), e.key(), e.reason());
            case "singleflight":
                return hit("并发合并（单飞）", result, e.cache(), e.key(), e.reason());
            case "evict":
                switch (result) {
                    case "evicted":
                        return hit("缓存失效", result, e.cache(), e.key(), e.reason());
                    case "skipped_degraded":
                        return cacheEmit(e, Severity.WARN.tag, "缓存失效被跳过（降级窗口）", result);
                    default:
                        return cacheEmit(e, severityOf(result), "缓存失效失败", result);
                }
            case "probe":
                return "ok".equals(result)
                        ? cacheEmit(e, Severity.INFO.tag, "L2 探活成功", result)
                        : cacheEmit(e, Severity.WARN.tag, "L2 探活失败", result);
            default:
                // kind=transition：healthy / degraded（L2 可用性**真实翻转**时才有）
                return "healthy".equals(result)
                        ? cacheEmit(e, Severity.INFO.tag, "L2 可用性恢复", result)
                        : cacheEmit(e, Severity.WARN.tag, "L2 进入降级窗口", result);
        }
    }

    /** 锁环：kind ∈ {acquire, hold, transition}（取值表在 {@code MallLockMetrics} 类注释）。 */
    private static Emit fromLock(MallLockEvent e) {
        String result = String.valueOf(e.result());
        switch (String.valueOf(e.kind())) {
            case "acquire":
                switch (result) {
                    case "acquired":
                        return lockEmit(e, Severity.INFO.tag, "取锁成功", result);
                    case "timeout":
                        // fail-open 直行：不是错误，是「互斥没生效」的降级 —— 页面必须看得见。
                        return lockEmit(e, Severity.WARN.tag, "取锁超时（fail-open 直行）", result);
                    case "degraded":
                        return lockEmit(e, Severity.WARN.tag, "取锁被跳过（降级窗口）", result);
                    default:
                        return lockEmit(e, severityOf(result), "取锁异常", result);
                }
            case "hold":
                switch (result) {
                    case "released":
                        return lockEmit(e, Severity.INFO.tag, "释放锁", result);
                    case "lease_lost":
                        return lockEmit(e, Severity.WARN.tag, "租约丢失（临界区可能重叠）", result);
                    default:
                        return lockEmit(e, severityOf(result), "释放锁异常", result);
                }
            default:
                return "healthy".equals(result)
                        ? lockEmit(e, Severity.INFO.tag, "锁可用性恢复", result)
                        : lockEmit(e, Severity.WARN.tag, "锁进入降级窗口", result);
        }
    }

    /** 订单状态环：kind ∈ {transition, illegal, async}（取值表在 {@code MallOrderStateMetrics} 类注释）。 */
    private static Emit fromOrder(OrderStateEvent e) {
        String result = String.valueOf(e.result());
        switch (String.valueOf(e.kind())) {
            case "transition": {
                String title = "状态流转 " + e.from() + " → " + e.to();
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("action", e.action());
                fields.put("result", result);
                fields.put("from", e.from());
                fields.put("to", e.to());
                fields.put("order", e.key());
                // rejected / error 才有值；applied 为 null ⇒ 不写这个键
                putReason(fields, e.reason());
                return new Emit(severityOf(result), title,
                        "action=" + e.action() + " result=" + result + " order#"
                                + (e.key() == null ? "-" : e.key()), fields);
            }
            case "illegal": {
                // 两种来源：真观测到非法态（result=null）与出生闸拦下（result=rejected）
                boolean atBirth = "rejected".equals(result);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("shape", e.shape());
                fields.put("order", e.key());
                // ⚠️ 出生闸那一支多带一个「这是入口拦下的」标记：真观测到非法态时
                //    {@code e.result()} 是 null，而 {@code String.valueOf(null)} 会得到字面量
                //    "null" —— 那个字符串绝不能进 fields（页面上会显示成一个叫 null 的结果）。
                //    故这里放的是**判出来的布尔**，不是 result 原样
                fields.put("atBirth", atBirth);
                return new Emit(Severity.WARN.tag, atBirth ? "出生闸拦下非法态" : "观测到非法状态",
                        "三位=" + e.shape() + " order#" + (e.key() == null ? "-" : e.key()), fields);
            }
            default: {
                // 原因优先级：shape（async 被拒的 queue_full|shutdown）→ 新加的 reason（时间线写入失败）
                String cause = e.shape() != null ? e.shape() : e.reason();
                String reason = cause == null ? "" : " reason=" + cause;
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("result", result);
                putReason(fields, cause);
                fields.put("order", e.key());
                return new Emit(severityOf(result), "异步流转" + asyncSuffix(result),
                        "result=" + result + reason + " order#" + (e.key() == null ? "-" : e.key()), fields);
            }
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
    private static Emit fromStock(StockEvent e) {
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
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", e.kind());
        fields.put("sku", e.skuCode());
        fields.put("qty", e.qty());
        fields.put("result", result);
        // 块 7 · 阶段 3a.3 后续：超卖拦截 / 扣减失败的具体原因（旧帧没有这个字段 ⇒ 不写键）
        putReason(fields, e.reason());
        return new Emit(severityOf(result), title,
                "kind=" + e.kind() + " sku=" + e.skuCode() + " qty=" + e.qty() + " result=" + result, fields);
    }

    private static Emit fromAudit(AuditEvent e) {
        boolean ok = "written".equals(String.valueOf(e.result()));
        String detail = "actor=" + e.actor() + " action=" + e.action()
                + (e.reason() == null ? "" : " reason=" + e.reason());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor", e.actor());
        fields.put("action", e.action());
        fields.put("reason", e.reason());
        return new Emit(ok ? Severity.INFO.tag : Severity.WARN.tag,
                ok ? "审计写入" : "审计写入失败", detail, fields);
    }

    private static Emit fromNotify(NotifyEvent e) {
        boolean ok = "sent".equals(String.valueOf(e.result()));
        String detail = "to=" + e.toType() + " event=" + e.eventType()
                + (e.reason() == null ? "" : " reason=" + e.reason());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("to", e.toType());
        fields.put("event", e.eventType());
        fields.put("reason", e.reason());
        return new Emit(ok ? Severity.INFO.tag : Severity.WARN.tag,
                ok ? "通知发送" : "通知发送失败", detail, fields);
    }

    private static Emit fromSensitive(SensitiveEvent e) {
        boolean masked = "masked".equals(String.valueOf(e.result()));
        String detail = "scene=" + e.scene() + " hit=" + e.hit() + "/" + e.total();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scene", e.scene());
        fields.put("hit", e.hit());
        fields.put("total", e.total());
        putReason(fields, e.reason());
        // 用户 2026-10-17 最终口径：敏感词拒收 / 打码都是**防护层成功防御**（内容被挡下/被改写，
        //   业务没有受损）⇒ PROTECT（紫），不是业务失败。只有真正执行失败才配 WARN。
        //   未知 result 仍按防护处理：本环只可能因「敏感词」触发，未知值不该倒灌成业务失败。
        return new Emit(Severity.PROTECT.tag,
                masked ? "敏感词打码" : "敏感词拒收", detail, fields);
    }

    private static Emit fromIdentity(IdentityEvent e) {
        boolean passed = "passed".equals(String.valueOf(e.result()));
        String detail = "subject=" + e.subject() + (e.reason() == null ? "" : " reason=" + e.reason());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("subject", e.subject());
        fields.put("reason", e.reason());
        return new Emit(passed ? Severity.INFO.tag : Severity.PROTECT.tag,
                passed ? "身份放行" : "身份拒绝", detail, fields);
    }

    /**
     * 防护环（第 9 类 · D 段）：result ∈ {blocked, opened, half_open, closed, shed}
     * （各档语义在 {@code ProtectionMetrics.Result} 的枚举注释里）。
     *
     * <p>⚠️ 标题按「**哪道闸 + 哪一档**」拼，不按 result 一档一个标题：{@code blocked} 同时来自
     * 入站限流与熔断开路，而这两者的排障方向相反（前者查流量、后者查下游），挤成同一个标题
     * 会让人读错方向。severity 仍走 {@link #severityOf} 那唯一一处规则：
     * blocked / opened / half_open / shed 四档是 {@link Severity#PROTECT}（防护拦截），
     * retry_failed 是真失败 ⇒ {@link Severity#WARN}。
     */
    private static Emit fromProtection(ProtectionEvent e) {
        String result = String.valueOf(e.result());
        String key = e.key() == null ? "-" : e.key();
        String detail = "protection=" + key + " scope=" + (e.scope() == null ? "-" : e.scope())
                + (e.detail() == null ? "" : " " + e.detail());
        String title;
        switch (result) {
            case "blocked":
                // 本环只有两处会记 blocked：入站限流闸与熔断开路。按 key 分开写，
                // 认不出 key 时**不硬套**其中任何一个（套错会把「限流挡的」说成「下游坏了」）。
                if ("rateLimit".equals(key)) {
                    title = "入站限流拦下";
                } else if ("circuitBreak".equals(key)) {
                    title = "熔断开路拒绝（未执行）";
                } else if ("duplicateSubmit".equals(key)) {
                    // 幂等查重挡下同一 OrderId 的重复提交（OrderService.addLocked）——
                    // 属于防护层的成功防御，不是业务失败。
                    title = "重复提交拦截（未执行）";
                } else {
                    title = "防护拦下：" + key;
                }
                break;
            case "opened":
                title = "熔断打开";
                break;
            case "half_open":
                title = "熔断半开（放行试探）";
                break;
            case "closed":
                title = "熔断恢复关闭";
                break;
            case "shed":
                title = "线程池挤出（未排队）";
                break;
            case "retry_success":
                title = "死锁重试成功";
                break;
            case "retry_failed":
                title = "死锁重试失败";
                break;
            default:
                // 七档是**枚举给定的有界集**，真出现第八种值只可能是「有人加了 result 却忘了改这里」
                // —— 那就照实显示，不猜一个好看的标题（与各标签表「认不出就不翻译」同源）。
                title = "防护处置：" + result;
                break;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("protection", e.key());
        fields.put("scope", e.scope());
        fields.put("extra", e.detail());
        // detail 是自由文本明细，reason 是单独可着色的一句「为什么」—— 两者都留
        putReason(fields, e.reason());
        return new Emit(severityOf(result), title, detail, fields);
    }

    // ==================== 小工具 ====================

    /** 命中类事件（缓存命中 / 回源 / 单飞）：一律 info。 */
    private static Emit hit(String title, String result, String cache, String key, String reason) {
        return new Emit(Severity.INFO.tag, title, cacheAspect(result, cache, key),
                cacheFields(result, cache, key, reason));
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
            // ↓ D 段第九环（防护）里唯一算「真失败」的一档：重试又失败了。
            //   ⚠️ closed（熔断恢复关闭）与 retry_success（重试成功）**不在这里**：
            //   那是恢复，不是故障（理由写在类注释第 2 件里）。
            case "retry_failed":
                return Severity.WARN.tag;
            // ↓ 防护层主动挡下 / 闸自己翻转：业务没有被执行，既不算 warn 也不算 error。
            case "blocked":
            case "opened":
            case "half_open":
            case "shed":
                return Severity.PROTECT.tag;
            default:
                return Severity.INFO.tag;
        }
    }

    /** 非命中口径的缓存事件（降级 / 失败 / 探活）：severity 与标题由调用方给定。 */
    private static Emit cacheEmit(MallCacheEvent e, String severity, String title, String result) {
        return new Emit(severity, title, cacheAspect(result, e.cache(), e.key()),
                cacheFields(result, e.cache(), e.key(), e.reason()));
    }

    private static String cacheAspect(String result, String cache, String key) {
        return "cache=" + (cache == null ? "-" : cache) + " result=" + result
                + " key=" + (key == null ? "-" : key);
    }

    /** {@link #cacheAspect} 的**同一批事实**的结构化形态 —— 两者必须在同一个 {@link Emit} 里一起产生。 */
    private static Map<String, Object> cacheFields(String result, String cache, String key, String reason) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("cache", cache);
        fields.put("result", result);
        fields.put("key", key);
        putReason(fields, reason);
        return fields;
    }

    /**
     * 把失败 / 降级原因放进 {@code fields.reason}（块 7 · 阶段 3a.3 后续新增）。
     *
     * <p>⚠️ 只在非空白时写：{@code null} 不该在页面上变成一个空的「原因」键。
     * reason 永不进顶层帧（帧仍 15 键）、不进 Micrometer tag（基数无界）—— 它只走
     * {@code fields}，与既有 {@code result} / {@code key} 同一列。
     */
    private static void putReason(Map<String, Object> fields, String reason) {
        if (reason != null && !reason.isBlank()) {
            fields.put("reason", reason);
        }
    }

    private static Emit lockEmit(MallLockEvent e, String severity, String title, String result) {
        return new Emit(severity, title,
                "lock=" + (e.lock() == null ? "-" : e.lock()) + " result=" + result
                        + " key=" + (e.key() == null ? "-" : e.key()),
                lockFields(e, result));
    }

    private static Map<String, Object> lockFields(MallLockEvent e, String result) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("lock", e.lock());
        fields.put("result", result);
        fields.put("key", e.key());
        putReason(fields, e.reason());
        return fields;
    }

    /** 并入一条：分配合并环 seq 并覆盖式写入（写者只做两件事，读者靠 seq 自校验）。
     *
     * @param src     源事件本身（块 7 · 阶段 3a.3 起改收接口）—— 它带来 {@code epochMilli} /
     *                {@code traceId} / {@code batchId} / {@code actorId} / {@code actorRole} /
     *                {@code durationUs} 六样，**都必须是源环在记录那一刻取好的**
     *                （并入跑在 SSE 流线程上，那里 MDC 是空的，现读会全变 null）。
     *                ⚠️ 走 {@code Sourced} 接口而不是逐个传形参：九个环的「谁/何时/多久」
     *                口径以后要一起变，只改这里一处即可（形状见 {@code observe.metrics.Sourced}）
     * @param srcSeq 源环的 seq —— 只进 {@code detail} 尾部（形如 {@code src=stock#128}），
     *               供排查时定位回原环；⚠️ **不是** {@link ObservedEvent#seq()}（那个是本环的）。
     *               它不进 {@code fields}（那一列给前端拼句子用，源环序号是排查用的）
     * @param objectType 被操作对象的**类型**（块 7 · commit 8 新增）—— 九个 {@code ingestXxx}
     *                   在调用点逐环给定；取值口径见 {@link ObservedEvent#objectType()}
     * @param objectId   被操作对象的**标识**（同上）；取值口径见 {@link ObservedEvent#objectId()}
     * @param remaining  **扣减那一刻该 SKU 的剩余件数**（块 7 · 阶段 3a.3 第二步新增）——
     *                   与上面两个一样在调用点**逐环给定**。⚠️ 九个调用点里**八个恒传
     *                   {@code null}**：只有库存环的**扣减档**有这个量，其余七环与库存环的
     *                   释放档**没有「剩余」可传**（那是判定，不是漏传 —— 完整理由见
     *                   {@link ObservedEvent#remaining()}）。正因为「八个 null 一个值」这种
     *                   形状最容易被后来的读者当成噪音而顺手填错，八个点上都带了一句
     *                   行内标记 {@code remaining}，把那个裸 {@code null} 的归属写在它自己旁边。
     */
    private void add(com.minke.mallmanagement.observe.metrics.Sourced src, Kind kind, Emit emit,
                     String key, long srcSeq, String objectType, String objectId,
                     Integer remaining) {
        long seq = cursor.getAndIncrement();
        ObservedEvent event = new ObservedEvent(
                seq, src.epochMilli(), kind.tag, emit.severity(), emit.title(),
                emit.detail() + " src=" + kind.tag + "#" + srcSeq,
                key == null ? null : String.valueOf(key), objectType, objectId,
                src.traceId(), src.batchId(), src.actorId(), src.actorRole(), src.durationUs(),
                remaining, emit.fields());
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

    // ==================== 环满记账（块 7 · commit 10 · 裁决 2） ====================
    //
    // 【为什么要记账】覆盖式环的**拒收策略就是「丢最老的」**（不阻塞生产方、不报错、不重试）。
    // 这是本类一直以来的语义（见类注释「边界」一节），但**丢了而不说**会让页面看起来一切正常
    // —— 而「看起来正常」正是本块最不想要的那种失效。所以：丢可以，但**要留下数**。
    //
    // 【丢失只可能发生在两个消费口，两个口各记各的 —— 且**口径不同**】
    //   ① 源环 → 合并环（在九个 ingestXxx 里）：源环一拍写了 3000 条，它的 recentEvents()
    //      只答得出最后 2048 条 ⇒ 中间那截就没了。**这是全进程的现象**（九个源环是单例、
    //      与谁在观看无关）⇒ 记成**全局累计** {@link #droppedIngest()}。
    //   ② 合并环 → 调用方（在 {@link #sinceWithLoss} 里）：调用方上次读到 afterSeq，再读时
    //      那一截已经被后来的事件覆盖。**这是「每连接一份」的现象**（游标本身就每连接一份）
    //      ⇒ 不作为全局数返回，而是**随那一次读一起返回**（{@link Batch#dropped()}），
    //      由调用方自己累计成「我这条流丢了多少」。理由：做成全局数就得回答「这条连接丢的
    //      算不算那条连接头上」，而那个问题没有正确答案 —— 各自算各自的，谁也不欠谁。
    //   起点（业务侧写进源环）**不记**：源环的 add 是「写进环形数组」，写进去就一定在环里待过，
    //   它的丢失只会在 ① 处被看见。记起点会让同一条事件被记两次。
    //
    // 【语义】{@code droppedIngest} 是「本进程起来之后」的累计（重启归零，同合并环里的 seq）。
    // ⚠️ 它**不区分是被谁丢的**，也不代表日志页在丢：源环溢出时页面的连接可能正好没问题
    //    （或者压根没人开页面）。要看「我这条流跟丢了没有」，读帧里的 {@code dropped} /
    //    {@code droppedTotal}（来自 ②），不要把这两个数当同一个东西。

    /** 源环 → 合并环 一路丢掉的累计条数（九个源环合计，全进程口径，见上面那段注释 ①）。 */
    private long droppedIngest;

    /**
     * 「源环的窗口 [previous, 现在] 与本次读到的窗口之间，隔了多少条」。
     *
     * <p>读法：{@code previous} = 上次并入到的源 seq；{@code firstAvailable} = 本次
     * {@code recentEvents()} 里**最老**的那条的 seq（空环给 -1）。两者之间的缺口
     * = {@code firstAvailable - previous - 1}。环没被覆盖时 {@code firstAvailable == previous + 1}
     * ⇒ 差 0。首拍（{@code previous == -1}，环里可能已攒了一屏）按同一个式子算，
     * 那次「丢」是**冷启动前**发生的（本类还没开始并），如实计入而不是抹掉。
     */
    private static long missed(long previous, long firstAvailable) {
        if (firstAvailable < 0L) {
            return 0L;   // 环是空的：什么也没读到，谈不上丢
        }
        return Math.max(0L, firstAvailable - previous - 1L);
    }

    /**
     * 源环 → 合并环 一路丢掉的累计条数（见类末「环满记账」一节 ①，口径说明也在那里）。
     *
     * <p>⚠️ 必须 {@code synchronized}：它写在 {@link #poll()} 的那个临界区里，而读的人
     * （流线程）在临界区外。
     */
    public synchronized long droppedIngest() {
        return droppedIngest;
    }
}
