package com.minke.mallmanagement.shared.cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 缓存层观测 —— 全应用唯一的埋点出口（Micrometer 指标 + 有界事件流）。
 *
 * 【为什么要有这个类】
 * B1 交付的几条不变量，在加本类之前**只能靠推理**：单飞究竟合并掉了几次、L2 的几路未命中
 * 各占多少、降级窗口内跳过了多少次 L2 —— 没有任何一个数字可复核。本类只做一件事：
 * 记录**已发生的事实**。判定仍在 {@link TwoLevelCache} / {@link L2HealthProbe}，
 * 本类不参与任何决策（无 if 决定业务行为，只有 tag 取值分支）。
 *
 * 【双通道：同一调用点发「计数 + 事件」】
 * 每个方法都同时（a）递增一个 Micrometer 计数器（tag 值取自封闭枚举，无字符串拼装）
 * 与（b）往有界环形缓冲追加一条 {@link MallCacheEvent}。两者合成在同一个方法里，
 * 是为了让两条通道不可能出现口径漂移 —— 而不是两处各写一半的埋点（教训：C2/C3 要过程，
 * 不只总数）。计数回答「多少」，事件回答「顺序与过程」；key 只出现在事件里（见下基数铁律）。
 *
 * 【命名规范 —— 全仓此前没有成文规范，本 javadoc 即唯一出处】
 * 1. 前缀 {@code mall.}，层级 {@code mall.<子系统>.<对象>.<量>}，全小写点分（Prometheus 友好）。
 * 2. **基数铁律：tag 只用有界取值。** {@code cache} 有界（= cacheNames），
 *    {@code result}/{@code kind}/{@code to} 是封闭枚举。**key 绝不进 tag**（无界）——
 *    key 只出现在事件流里。
 * 3. 结果类维度统一叫 {@code result}，取值单词化（hit|miss|degraded|error|…），禁止同义多写法。
 * 4. 计数用名词复数（{@code ...requests}），耗时用 {@code .duration}（Timer），
 *    状态用 {@code ...up} / {@code ...size}。
 * 5. {@code mall.lock.*} 为 B2（分布式锁）**预留前缀**，本类不创建任何锁指标。
 * 6. 由计数可派生的值（如合并倍数）不重复埋点，留给查询侧算。
 * 7. 不复用 Boot 的 {@code cache.*} 名字族（{@code CaffeineCacheMetrics} 只覆盖 L1，
 *    会与本类产生两套重叠口径）；也因此不启用 Boot 的缓存指标自动绑定。
 *
 * 【指标清单】{@code cache} tag = cacheName，按需懒建（不硬编码名字表）
 * <pre>
 * mall.cache.l1.requests{cache,result=hit|miss}
 *        L1 读结果。两条读路径（get(key) 与 get(key,loader)）共用一个落点（l1Get）。
 * mall.cache.l2.requests{cache,result=hit|miss|degraded|error}
 *        L2 读结果，四路互斥且完备：hit=读到；miss=真缺失（Redis 返回 null）；
 *        degraded=冷却窗口内跳过（根本没碰网络）；error=DataAccessException 被吞。
 *        ⚠️「该 cacheName 没有 L2」（TwoLevelCache 的 l2 == null）**不计数** ——
 *        那是装配期即确定的配置事实（RedisCacheManager 拒绝动态创建该名字），
 *        B1 已用一次 WARN 记录（TwoLevelCacheManager），不是请求事件，
 *        进热路径只会放大噪声。（用户裁定：属配置事实 → 不进 result 枚举。）
 * mall.cache.loads{cache}
 *        发起「回落 DB 加载」的次数 = 唯一真穿透点（@Cacheable sync=true 的核心数字）。
 *        无 result tag：来源只有 DB 一个，单值 tag 是给未来预留、不是已发生的事实。
 *        ⚠️ 不区分成败（失败那次同样打了 DB）；块 7 · 阶段 3a.3 起记在 loader **返回后**，为的是能带回源耗时。
 * mall.cache.singleflight{cache}
 *        被别的线程的加载合并掉的次数（Caffeine 单飞的 follower 侧）。同样无 result tag。
 *        与 loads 互补：leader 进 loads、follower 进这里，两者不重不漏。
 * mall.cache.evicts{cache,kind=key|all,result=evicted|skipped_degraded|error}
 *        失效是否真到达 L2。error=尝试了但抛 DataAccessException（L2 里可能仍留旧值，
 *        由 L2 TTL 兜底）—— 三个取值都真实可达，非预留。
 * mall.cache.l1.size{cache}          L1 条数（Caffeine estimatedSize，O(1)）
 * mall.cache.l1.evictions{cache}     L1 因容量/TTL 被淘汰的累计数 —— 回答
 *                                    application.properties:24 与 cache-layer.md#pack-cache-layer-11
 *                                    那笔「max-size=1000 是防御性上界，待 Micrometer 观测后调整」。
 * mall.cache.l2.up                   1=L2 可用；0=处于冷却窗口。
 *                                    ⚠️ 0 **不等于「Redis 已死」**：窗口内 L2 只是被跳过、
 *                                    本就没在读它，故 0 应读作「现在不依赖 L2」，不是可用性 SLA。
 *                                    无 cache tag —— 健康是全局事实（L2HealthProbe 全应用一份）。
 * mall.cache.l2.probes{result=ok|failed}   探活次数与结果
 * mall.cache.l2.probe.duration             探活墙钟（Timer；可用 MAX 复核 L2HealthProbe 类注释
 *                                          第 2 条记的「稳态约 233ms、进程内首次 0.5~0.8s」）
 * mall.cache.l2.transitions{to=degraded|healthy}
 *                                          降级/恢复各发生几次。只在**真实翻转**时 +1
 *                                          （与「只在翻转时 WARN」同一处判定，天然去重）
 * </pre>
 *
 * 【事件语法】{@link MallCacheEvent} 的 kind/result 取值按 kind 定，取值同构于上面的 tag：
 * <pre>
 *   kind=l1           result=hit|miss              key=键
 *   kind=l2           result=hit|miss|degraded|error  key=键
 *   kind=load         result=null（来源只有 DB 一个，见 loads 无 tag）  key=键
 *   kind=singleflight result=null（只记「被合并」这一个事实）           key=键
 *   kind=evict        result=evicted|skipped_degraded|error
 *                     key=null 表示整片失效（@CacheEvict(allEntries=true)），非 null 表示单键
 *   kind=probe        result=ok|failed             key=null，cache=null
 *   kind=transition   result=degraded|healthy      key=null，cache=null
 * </pre>
 *
 * 【热路径代价】每次事件 = 一次 {@code registry.counter(name, tags)}（Micrometer 内部按
 * Meter.Id 索引的 map 查找）+ 一次 LongAdder 自增 + 一次数组赋值 + 一个小对象。
 * **不做 Meter 句柄预解析**：那要维护一份与 registry 保持一致的缓存，在本项目量级
 * （线上 120 QPS，约 500 事件/s）换不来可测收益；若将来实测到争用，再预解析句柄。
 * 全路径无锁、无 I/O、无日志（agent.md 第 7 条：新增监控不得阻塞主业务线程）。
 */
@Component
public class MallCacheMetrics {

    // ===== 指标名：全仓唯一口径处。改名前请全仓检索（含文档与告警）=====

    static final String L1_REQUESTS = "mall.cache.l1.requests";
    static final String L2_REQUESTS = "mall.cache.l2.requests";
    static final String LOADS = "mall.cache.loads";
    static final String SINGLEFLIGHT = "mall.cache.singleflight";
    static final String EVICTS = "mall.cache.evicts";
    static final String L1_SIZE = "mall.cache.l1.size";
    static final String L1_EVICTIONS = "mall.cache.l1.evictions";
    static final String L2_UP = "mall.cache.l2.up";
    static final String L2_PROBES = "mall.cache.l2.probes";
    static final String L2_PROBE_DURATION = "mall.cache.l2.probe.duration";
    static final String L2_TRANSITIONS = "mall.cache.l2.transitions";

    /** 事件流容量：2 的幂（定位用位与，不用取模）。2048 条 ≈ 每秒千条量级下 2 秒的窗口。 */
    private static final int EVENT_CAPACITY = 2048;
    private static final int EVENT_MASK = EVENT_CAPACITY - 1;

    private final MeterRegistry registry;

    /**
     * 事件环形缓冲与单调游标。写者（业务线程）只做一次数组赋值，不加锁、不清理旧值
     * —— 覆盖即淘汰，读者靠 {@code seq} 自校验（见 {@link #recentEvents()}）。
     */
    private final MallCacheEvent[] events = new MallCacheEvent[EVENT_CAPACITY];
    private final AtomicLong eventCursor = new AtomicLong();

    /**
     * {@code mall.cache.l2.up} 的取值对象。
     * ⚠️ 必须由本字段强引用持有，理由见 {@link #bindL2Up(BooleanSupplier)}。
     * volatile：写在装配线程、读在 scrape 线程。
     */
    private volatile BooleanSupplier l2UpState;

    public MallCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ================= 缓存侧（由 TwoLevelCache 调用）=================

    /**
     * L1 读结果。hit=false 即 miss。
     * 两条读路径（{@code get(key)} 与 {@code get(key, loader)}）都经由 {@code l1Get} 落到这里，
     * 故 {@code hit+miss} 就是 L1 收到的全部读请求数。
     *
     * @param durationNanos L1 读的墙钟（块 7 · 阶段 3a.3 新增）。⚠️ 它是**纳秒级**的
     *                      （Caffeine 本地查表约 0.2~1 µs），且**含**两次 {@code System.nanoTime()}
     *                      自身的开销 —— 读这个数时按**量级**看，别抠小数点后两位。这也正是本笔
     *                      用微秒而非毫秒记 duration 的原因：毫秒下这一档**恒为 0**（假事实）
     */
    public void l1Request(String cache, Object key, boolean hit, long durationNanos) {
        registry.counter(L1_REQUESTS, "cache", cache, "result", hit ? "hit" : "miss").increment();
        event(cache, "l1", hit ? "hit" : "miss", key, durationNanos);
    }

    /**
     * L2 读结果。四路语义见类注释的指标清单（degraded 与 miss 的区别是前者没碰网络）。
     *
     * @param durationNanos 这次 L2 读的墙钟（块 7 · 阶段 3a.3 新增）；{@code DEGRADED} 传 {@code null}。
     *                      ⚠️ <b>{@code null} 不等于「没花钱」</b>：冷却窗口内本线程若恰好抢到探测权，
     *                      会在 {@code probe.isL2UsableNow()} 里**同步 PING 一次**（不可达时约 233ms）
     *                      —— 那段发生在别处、这里量不到，但它**记在 probe 事件上**（见 {@link #probe}），
     *                      不是丢失，只是不挂在这条事件上。故本参数用 {@code Long} 而非 {@code long}
     */
    public void l2Request(String cache, Object key, L2Result result, Long durationNanos) {
        registry.counter(L2_REQUESTS, "cache", cache, "result", result.tag).increment();
        event(cache, "l2", result.tag, key, durationNanos);
    }

    /**
     * 一次回落 DB 的加载（唯一真穿透点）。
     *
     * <p>⚠️ <b>计数时机（块 7 · 阶段 3a.3 改了，如实记）</b>：原来记在 loader <b>被调用前</b>，
     * 现在改成 loader <b>返回后</b> —— 为的是能拿到「查库多久」。计数意图（DB 压力：失败那次同样
     * 打了 DB）不变：调用点用 {@code try/finally}，异常路径照样计数，总量与时机在并发下无实质差异。
     *
     * @param durationNanos 这次回源的墙钟（块 7 · 阶段 3a.3 新增）—— 从 loader 被调用到它返回
     *                      （或抛出）。「查库多久」在这里第一次变得可读
     */
    public void loadedFromDb(String cache, Object key, long durationNanos) {
        registry.counter(LOADS, "cache", cache).increment();
        event(cache, "load", null, key, durationNanos);
    }

    /**
     * 本次读被别的线程的加载合并（Caffeine 单飞的 follower 侧）。
     *
     * <p>⚠️ 本上报点**不含 L1 命中**：调用方（{@code TwoLevelCache#get(key, loader)}）先在
     * {@code l1Get} 查过 L1、命中就早返回了，进到这里只剩「被别的线程的加载合并」这一种情形。
     *
     * @param durationNanos follower **等 leader 把这次加载做完**的墙钟（块 7 · 阶段 3a.3 新增）——
     *                      这正是单飞省下来的那段时间。与 {@link #loadedFromDb} 的耗时同源不同量：
     *                      leader 那边记「查库多久」，follower 这边记「等了多久」
     */
    public void singleflightMerged(String cache, Object key, long durationNanos) {
        registry.counter(SINGLEFLIGHT, "cache", cache).increment();
        event(cache, "singleflight", null, key, durationNanos);
    }

    /**
     * 一次失效的结果。
     *
     * @param allEntries    true = {@code clear()}/{@code @CacheEvict(allEntries=true)}；false = 单键
     * @param durationNanos 这次失效的墙钟（块 7 · 阶段 3a.3 新增）；{@code SKIPPED_DEGRADED} 传 {@code null}
     *                      —— 冷却窗口内跳过，根本没碰 L2（「没发生」不是「很快」）
     */
    public void evicted(String cache, boolean allEntries, EvictResult result, Object key, Long durationNanos) {
        registry.counter(EVICTS, "cache", cache, "kind", allEntries ? "all" : "key",
                "result", result.tag).increment();
        event(cache, "evict", result.tag, allEntries ? null : key, durationNanos);
    }

    /**
     * 绑定某个缓存名的 L1 观测桩（size + evictions）。由 {@link TwoLevelCacheManager}
     * 在创建 L1 时调用**一次**（每个缓存名只建一次 L1）。
     *
     * 取值对象直接传 Caffeine 实例（而非 lambda）：Micrometer 的 Gauge / FunctionCounter
     * 对取值对象只持**弱引用**，传实例才是结构上安全的（该实例由 TwoLevelCache 强引用，
     * 与应用同生命周期）。{@link #bindL2Up(BooleanSupplier)} 那边没有这样的现成持有者，
     * 故要另存一份强引用 —— 两处不对称的原因就是这个，不是风格差异。
     *
     * {@code l1.evictions} 用 FunctionCounter：Caffeine 的 evictionCount 单调递增，
     * 且每个缓存名的 L1 实例只创建一次（TwoLevelCacheManager 的 computeIfAbsent），
     * 不存在「计数器回退」（FunctionCounter 要求单调）。
     */
    public void bindL1(String cache, com.github.benmanes.caffeine.cache.Cache<?, ?> l1) {
        Gauge.builder(L1_SIZE, l1, c -> c.estimatedSize())
                .tags("cache", cache)
                .register(registry);
        FunctionCounter.builder(L1_EVICTIONS, l1, c -> c.stats().evictionCount())
                .tags("cache", cache)
                .register(registry);
    }

    // ================= 探活侧（由 L2HealthProbe 调用）=================

    /**
     * 一次探测的结果与耗时（无 cache tag：探活是全局的，与缓存名无关）。
     *
     * @param ok            PING 是否成功
     * @param durationNanos 本次探测墙钟 —— 与 L2HealthProbe 类注释记的实测值同源，
     *                      可直接用本指标复核（稳态约 233ms、进程内首次 0.5~0.8s）。
     *                      ⚠️ 块 7 · 阶段 3a.3 起这个值**同时进事件**（原来只进 timer）——
     *                      DEGRADED 那次「同步 PING」的钱就记在这条 probe 事件上，见 {@link #l2Request}
     */
    public void probe(boolean ok, long durationNanos) {
        registry.counter(L2_PROBES, "result", ok ? "ok" : "failed").increment();
        registry.timer(L2_PROBE_DURATION).record(durationNanos, TimeUnit.NANOSECONDS);
        event(null, "probe", ok ? "ok" : "failed", null, durationNanos);
    }

    /** 健康 → 降级（真实翻转）。 */
    public void degraded() {
        transition("degraded");
    }

    /** 降级 → 健康（真实翻转）。 */
    public void recovered() {
        transition("healthy");
    }

    /**
     * 绑定 {@code mall.cache.l2.up} 的取值函数。由 {@link L2ProbeConfig} 在建好 probe 后调用。
     *
     * ⚠️ 传进来的 lambda 除了 {@link #l2UpState} 没有任何强引用，而 Micrometer 的 Gauge
     * 对取值对象只持**弱引用**（{@code Gauge.builder(name, obj, f)} 的既有语义）——
     * 不存这一份，一次 GC 之后这个 gauge 会静默变成 NaN（不报错、不告警）。
     * 故本方法必须把 lambda 存进字段，且**先存后注册**（注册后可能立刻被 scrape）。
     */
    public void bindL2Up(BooleanSupplier l2Healthy) {
        this.l2UpState = l2Healthy;
        Gauge.builder(L2_UP, l2Healthy, s -> s.getAsBoolean() ? 1.0 : 0.0).register(registry);
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
    public List<MallCacheEvent> recentEvents() {
        long next = eventCursor.get();
        long oldest = Math.max(0L, next - EVENT_CAPACITY);
        List<MallCacheEvent> snapshot = new ArrayList<>((int) (next - oldest));
        for (long seq = oldest; seq < next; seq++) {
            MallCacheEvent candidate = events[(int) (seq & EVENT_MASK)];
            if (candidate != null && candidate.seq() == seq) {
                snapshot.add(candidate);
            }
        }
        return snapshot;
    }

    private void transition(String to) {
        registry.counter(L2_TRANSITIONS, "to", to).increment();
        // 不记耗时：翻正是「瞬时事实」，没有起止（与 evict 的 SKIPPED 同款，见 event 的 javadoc）
        event(null, "transition", to, null, null);
    }

    /**
     * 追加一条事件。{@code key} 只在事件里以字符串形式出现 —— key 可以是任意对象，
     * 但 tag 里绝不能有它（基数无界）。
     *
     * <p>⚠️ 块 7 · 阶段 3a.3 起 {@code durationNanos} 是**必填**（**故意不给重载**）——
     * 本仓栽过「有重载 ⇒ 新调用点顺手用了短的那个 ⇒ 耗时静默丢失」。于是每个调用点都必须
     * 明写「这一档量不出来（{@code null}）」还是「量出来了（值）」；{@code null} 与 {@code 0}
     * 在页面上意思完全不同（见 {@code Sourced#durationUs()}）。
     *
     * @param durationNanos 本条事件自身的墙钟（纳秒）；{@code null} = 量不出来。
     *                      ⚠️ 值为 {@code null} 的情形都是**有理由的**，不是漏了：
     *                      evict 被降级跳过 / 翻转（瞬时事实，根本没有起止）
     */
    private void event(String cache, String kind, String result, Object key, Long durationNanos) {
        long seq = eventCursor.getAndIncrement();
        events[(int) (seq & EVENT_MASK)] = new MallCacheEvent(
                seq, System.currentTimeMillis(), cache, kind, result,
                key == null ? null : String.valueOf(key), com.minke.mallmanagement.shared.web.TraceIdFilter.current(), com.minke.mallmanagement.shared.web.TraceIdFilter.currentBatch(),
                com.minke.mallmanagement.shared.web.TraceIdFilter.currentActorId(),
                com.minke.mallmanagement.shared.web.TraceIdFilter.currentActorRole(),
                durationNanos == null ? null : durationNanos / 1000);   // 块 7 · commit 8/8c：写全限定名而非 import —— 本文件 `:65`/`:102-103`/`:109` 那批「文件:行号」引用不能因加一行 import 而下移
    }

    /**
     * 一条缓存事件。字段含义按 {@code kind} 定，取值表见 {@link MallCacheMetrics} 类注释
     * 「事件语法」一节。
     *
     * @param seq        单调序号（缓冲被覆盖后靠它自校验与排序）
     * @param epochMilli 墙钟时间戳 —— 事件是给人看的过程记录，故用墙钟而非 nanoTime
     * @param cache      cacheName；探活/翻转事件为 null（那两个是全局事实）
     * @param kind       l1 | l2 | load | singleflight | evict | probe | transition
     * @param result     该 kind 下的结果；load 与 singleflight 恒为 null
     * @param key        涉及的业务键；整片失效、探活、翻转为 null
     * @param traceId    产生本条事件的那次请求的号（块 7 · commit 8 新增）；不在请求线程上时为 null
     * @param batchId    产生本条事件的那次请求**所属的演练批次**（块 7 · commit 8c，来自
     *                   {@code X-Drill-Batch} 头）；日常流量为 {@code null}。同 {@code traceId} 现读
     * @param actorId    发起那次请求的业务号（块 7 · 阶段 3a.3 新增）；无身份时为 null
     * @param actorRole  发起者的角色号（块 7 · 阶段 3a.3 新增，1 客户 / 2 商家 / 3 管理员）；无身份时为 null
     * @param durationUs 本条事件的耗时（**微秒**，块 7 · 阶段 3a.3 新增）；{@code null} = 量不出来。
     *                   ⚠️ 由纳秒入参除以 1000 得来 —— 微秒而非毫秒，因为最便宜的一档是纳秒级的
     */
    public record MallCacheEvent(long seq, long epochMilli, String cache, String kind,
                                 String result, String key, String traceId, String batchId,
                                 String actorId, Integer actorRole, Long durationUs)
            implements com.minke.mallmanagement.observe.metrics.Sourced {
    }

    /** {@code mall.cache.l2.requests} 的 result 取值。tag 字段避免每次调用拼字符串。 */
    public enum L2Result {
        HIT("hit"),
        /** 真缺失：Redis 读了、返回 null（不是「没读」）。 */
        MISS("miss"),
        /** 冷却窗口内跳过：根本没碰网络，与 Redis 连不上是同一层语义的降级。 */
        DEGRADED("degraded"),
        /** DataAccessException 被吞（读路径按未命中处理）。 */
        ERROR("error");

        final String tag;

        L2Result(String tag) {
            this.tag = tag;
        }
    }

    /** {@code mall.cache.evicts} 的 result 取值。 */
    public enum EvictResult {
        /** 失效真的到达了 L2。 */
        EVICTED("evicted"),
        /** 冷却窗口内跳过 —— L2 里可能留一个旧值，由 L2 TTL 兜底。 */
        SKIPPED_DEGRADED("skipped_degraded"),
        /** 尝试了但抛 DataAccessException（L2 里的旧值可能仍在）。 */
        ERROR("error");

        final String tag;

        EvictResult(String tag) {
            this.tag = tag;
        }
    }
}
