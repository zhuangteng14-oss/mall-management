package com.minke.mallmanagement.shared.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import com.minke.mallmanagement.shared.cache.L2HealthProbe;
import com.minke.mallmanagement.shared.cache.TwoLevelCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存埋点（C1）的语义实测 —— 分门别类对不对、双通道是不是真的两条都发了。
 *
 * 【为什么可以不起 Spring、不起 Redis】本类测的是 {@link MallCacheMetrics} 的**分类**
 * （哪种情形记哪个计数/哪条事件），不是 Caffeine 的单飞、也不是 Redis 的连接。故 L1/L2
 * 都用假实现（Mockito）：L1 用 Caffeine 的 Cache 接口 mock，可精确摆出「mapper 没被调用」
 * （= 被别的线程合并）这种真实现里要靠并发才出现的情形；L2 用 Spring 的 Cache mock，
 * 可精确摆出四路未命中。用 SimpleMeterRegistry ⇒ 每个测试方法一份（JUnit 每方法新建测试
 * 对象），计数天然隔离。
 *
 * ⚠️ 本测试**没有并发窗口**（对比 {@link L2HealthProbeConcurrencyTest} 需要窗口的那三个）：
 * 需要「被合并」这一情形时就 mock L1 让 mapper 不被调用，而不是去构造两个线程赛跑
 * —— 后者要么靠 sleep 买概率（会 flaky），要么就要一个长度 ≫ 突发跨度的窗口（B1 的教训）。
 * 真实现下的单飞（20 并发 → 1 次 DB）由 A3 的 A-S2b-1 端到端守，不在本文件的射程内。
 */
class MallCacheMetricsTest {

    private static final String CACHE = "goodstype";

    /** 故意取小值：探活测试要等窗口到期，窗口越长测试越慢。 */
    private static final Duration COOLDOWN = Duration.ofMillis(50);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallCacheMetrics metrics = new MallCacheMetrics(registry);

    // ================= L1 =================

    @Test
    @DisplayName("L1 命中/未命中都在同一个落点计数（直读路径与 loader 路径都经过 l1Get）")
    void l1RequestsAreCountedAtTheSingleReadSite() {
        TwoLevelCache cache = newCache(l1Hit("k", "v"), null, probeUsable(), metrics);

        assertThat(cache.get("k")).as("直读路径：L1 命中").isNotNull();
        assertThat(cache.get("k", () -> "永远不会被调用")).as("loader 路径：L1 命中").isEqualTo("v");

        assertThat(count(MallCacheMetrics.L1_REQUESTS, "cache", CACHE, "result", "hit")).isEqualTo(2.0);
        assertThat(count(MallCacheMetrics.L1_REQUESTS, "cache", CACHE, "result", "miss")).isZero();
    }

    // ================= L2：四路 =================

    @Test
    @DisplayName("L2 四路各归其位：hit / miss / degraded / error（且 degraded 根本没碰 L2）")
    void l2RequestsDistinguishAllFourOutcomes() {
        // ① hit：L2 有值
        Cache l2Hit = mock(Cache.class);
        when(l2Hit.get("k")).thenReturn(new SimpleValueWrapper("fromL2"));
        assertThat(newCache(l1Empty(), l2Hit, probeUsable(), metrics).get("k")).isNotNull();
        assertThat(count(MallCacheMetrics.L2_REQUESTS, "cache", CACHE, "result", "hit"))
                .as("① 读到值 → hit").isEqualTo(1.0);

        // ② miss：读了，Redis 返回 null（真缺失，与「没读」是两件事）
        Cache l2Empty = mock(Cache.class);
        when(l2Empty.get("k")).thenReturn(null);
        assertThat(newCache(l1Empty(), l2Empty, probeUsable(), metrics).get("k")).isNull();
        assertThat(count(MallCacheMetrics.L2_REQUESTS, "cache", CACHE, "result", "miss"))
                .as("② 读了但没值 → miss").isEqualTo(1.0);

        // ③ degraded：冷却窗口内跳过 —— 计数要记，且**绝不能**碰 L2（这是降级的全部意义）
        Cache l2Untouched = mock(Cache.class);
        assertThat(newCache(l1Empty(), l2Untouched, probeBlocked(), metrics).get("k")).isNull();
        verify(l2Untouched, never()).get(any());
        assertThat(count(MallCacheMetrics.L2_REQUESTS, "cache", CACHE, "result", "degraded"))
                .as("③ 冷却跳过 → degraded（不是 miss：miss 意味着碰过网络）").isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.L2_REQUESTS, "cache", CACHE, "result", "miss"))
                .as("③ 跳过不该被记成 miss").isEqualTo(1.0);   // 仍只有 ② 的那一次

        // ④ error：DataAccessException 被吞（读路径按未命中处理）
        Cache l2Broken = mock(Cache.class);
        when(l2Broken.get("k")).thenThrow(new RedisConnectionFailureException("模拟 Redis 不可达"));
        assertThat(newCache(l1Empty(), l2Broken, probeUsable(), metrics).get("k")).isNull();
        assertThat(count(MallCacheMetrics.L2_REQUESTS, "cache", CACHE, "result", "error"))
                .as("④ 异常被吞 → error").isEqualTo(1.0);
    }

    @Test
    @DisplayName("该 cacheName 没有 L2（装配期的配置事实）不进任何指标 —— 用户裁定：只有请求事件才配进热路径")
    void cacheWithoutL2IsNotCountedAtAll() {
        TwoLevelCache cache = newCache(l1Empty(), null, probeUsable(), metrics);

        assertThat(cache.get("k")).isNull();
        assertThat(registry.find(MallCacheMetrics.L2_REQUESTS).counters())
                .as("l2 == null 不该产生任何 mall.cache.l2.requests 时间序列").isEmpty();
        assertThat(count(MallCacheMetrics.L1_REQUESTS, "cache", CACHE, "result", "miss"))
                .as("L1 侧照常计数（它确实是这个 cacheName 的一次读请求）").isEqualTo(1.0);
    }

    // ================= loader 路径：loads / singleflight =================

    @Test
    @DisplayName("loader 路径三分：真加载 → loads；loader 内命中 L2 → 不记 loads；被合并 → singleflight")
    void loaderPathClassifiesLoadL2HitAndMerged() {
        // ① 冷 key：L1 miss + L2 miss → 真发起一次回落 DB 的加载
        Cache l2Empty = mock(Cache.class);
        when(l2Empty.get("k")).thenReturn(null);
        TwoLevelCache cold = newCache(l1Empty(), l2Empty, probeUsable(), metrics);
        assertThat(cold.get("k", () -> "fromDb")).isEqualTo("fromDb");
        assertThat(count(MallCacheMetrics.LOADS, "cache", CACHE))
                .as("① 真穿透 DB → loads = 1").isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.SINGLEFLIGHT, "cache", CACHE))
                .as("① 没有别的线程在加载 ⇒ 不是合并").isZero();

        // ② L2 有值：在 loader 内命中 → 一次 DB 都没碰
        Cache l2Hit = mock(Cache.class);
        when(l2Hit.get("k2")).thenReturn(new SimpleValueWrapper("fromL2"));
        TwoLevelCache warm = newCache(l1Empty(), l2Hit, probeUsable(), metrics);
        assertThat(warm.get("k2", () -> "不该被调用")).isEqualTo("fromL2");
        assertThat(count(MallCacheMetrics.LOADS, "cache", CACHE))
                .as("② loader 内命中 L2 ⇒ loads 仍是 1（没有第二次真加载）").isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.L2_REQUESTS, "cache", CACHE, "result", "hit"))
                .as("② 这一路的命中由 l2Get 记，不重复记").isEqualTo(1.0);

        // ③ 被合并：mapper 没被调用（等价于「另一个线程正在/已经加载完这个 key」）。
        //    真实现里这要么是 Caffeine 让后来者等赢家的结果，要么是等的时候值已经写进 L1。
        //    两种都算「被别人的加载合并」，故本计数不区分二者（口径见 MallCacheMetrics）。
        AtomicBoolean followerLoaderRan = new AtomicBoolean(false);
        TwoLevelCache merged = newCache(l1FilledByOtherThread(), mock(Cache.class), probeUsable(), metrics);
        assertThat(merged.get("k3", () -> {
            followerLoaderRan.set(true);
            return "本线程不该执行 loader";
        })).isEqualTo("fromOtherThread");

        assertThat(followerLoaderRan).as("③ follower 自己的 loader 不该被执行").isFalse();
        assertThat(count(MallCacheMetrics.SINGLEFLIGHT, "cache", CACHE))
                .as("③ 被合并 → singleflight = 1").isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.LOADS, "cache", CACHE))
                .as("③ 合并的那次不是真加载 ⇒ loads 仍为 1").isEqualTo(1.0);
    }

    // ================= 失效 =================

    @Test
    @DisplayName("失效三路：evicted / skipped_degraded / error，且单键与整片分开记（kind）")
    void evictsDistinguishOutcomeAndScope() {
        Cache l2 = mock(Cache.class);
        TwoLevelCache cache = newCache(l1Mock(), l2, probeUsable(), metrics);
        cache.evict("k");
        cache.clear();
        assertThat(count(MallCacheMetrics.EVICTS, "cache", CACHE, "kind", "key", "result", "evicted"))
                .as("单键失效到达 L2").isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.EVICTS, "cache", CACHE, "kind", "all", "result", "evicted"))
                .as("整片失效到达 L2").isEqualTo(1.0);

        // 冷却中：跳过（L2 里可能留旧值，由 TTL 兜底）
        TwoLevelCache degraded = newCache(l1Mock(), mock(Cache.class), probeBlocked(), metrics);
        degraded.evict("k");
        degraded.clear();
        assertThat(count(MallCacheMetrics.EVICTS, "cache", CACHE, "kind", "key", "result", "skipped_degraded"))
                .isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.EVICTS, "cache", CACHE, "kind", "all", "result", "skipped_degraded"))
                .isEqualTo(1.0);

        // 抛异常：既不是「已失效」也不是「跳过」—— 单独一路（L2 里的旧值可能还在）
        Cache broken = mock(Cache.class);
        doThrow(new RedisConnectionFailureException("模拟 Redis 不可达")).when(broken).evict("k");
        newCache(l1Mock(), broken, probeUsable(), metrics).evict("k");
        assertThat(count(MallCacheMetrics.EVICTS, "cache", CACHE, "kind", "key", "result", "error"))
                .isEqualTo(1.0);
    }

    // ================= 探活侧 =================

    @Test
    @DisplayName("探活：ok/failed 与耗时都记；transitions 只在真实翻转时 +1；l2.up 随之变化")
    void probeMetricsTrackFlipsWithoutDoubleCounting() throws Exception {
        AtomicBoolean pong = new AtomicBoolean(false);
        L2HealthProbe probe = probeOver(fakeFactory(pong));

        assertThat(count(MallCacheMetrics.L2_PROBES, "result", "failed")).as("还没探测过").isZero();
        assertThat(up()).as("初始健康").isEqualTo(1.0);

        // 故障：先有一次真实失败（markFailed）→ 降级
        probe.markFailed(new RedisConnectionFailureException("模拟 Redis 不可达"));
        assertThat(count(MallCacheMetrics.L2_TRANSITIONS, "to", "degraded")).isEqualTo(1.0);
        assertThat(up()).as("降级中：0（含义是「现在不依赖 L2」，不是「Redis 已死」）").isZero();

        // ⚠️ 故障期间再来一次失败（真实 L2 操作每次都调 markFailed）——不该再记一次翻转
        probe.markFailed(new RedisConnectionFailureException("模拟 Redis 不可达"));
        assertThat(count(MallCacheMetrics.L2_TRANSITIONS, "to", "degraded"))
                .as("同一段故障内多次失败只记一次翻转（与「只在翻转时 WARN」同点）").isEqualTo(1.0);

        // 窗口到期 → 由唯一一个请求探测一次；此时 Redis 仍不可达 ⇒ 探测失败
        sleepPastCooldown();
        probe.isL2UsableNow();
        assertThat(count(MallCacheMetrics.L2_PROBES, "result", "failed"))
                .as("探测失败记 failed").isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.L2_PROBES, "result", "ok")).isZero();

        // Redis 恢复 → 再等一个窗口 → 探测成功 → 回到健康
        pong.set(true);
        sleepPastCooldown();
        probe.isL2UsableNow();
        assertThat(count(MallCacheMetrics.L2_PROBES, "result", "ok")).isEqualTo(1.0);
        assertThat(count(MallCacheMetrics.L2_TRANSITIONS, "to", "healthy")).isEqualTo(1.0);
        assertThat(up()).as("恢复后回到 1").isEqualTo(1.0);

        assertThat(registry.find(MallCacheMetrics.L2_PROBE_DURATION).timer().count())
                .as("两次探测都进了耗时 Timer（失败那次的耗时代价是真的：上限 = 探测超时）")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("L1 观测桩：size 是 gauge、evictions 是单调 FunctionCounter")
    void bindL1ExposesSizeAndEvictions() {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = Caffeine.newBuilder()
                .maximumSize(10).recordStats().build();
        metrics.bindL1(CACHE, l1);
        l1.put("a", "1");
        l1.put("b", "2");

        assertThat(registry.find(MallCacheMetrics.L1_SIZE).tags("cache", CACHE).gauge().value())
                .as("size 读的是 Caffeine 的 estimatedSize").isEqualTo(2.0);
        // 注意类型：它是 FunctionCounter，用 count(...)→registry.find(...).counter() 查会**静默查不到**
        // 而让断言空过（返回 0.0）—— 故这里显式取 functionCounter() 并先断言它真存在
        FunctionCounter evictions = registry.find(MallCacheMetrics.L1_EVICTIONS)
                .tags("cache", CACHE).functionCounter();
        assertThat(evictions).as("必须真的注册成 FunctionCounter，否则下面的断言是假绿").isNotNull();
        assertThat(evictions.count()).isZero();
    }

    // ================= 事件流（第二通道）=================

    @Test
    @DisplayName("事件流：写满即覆盖最旧、seq 单调；两条通道在同一调用点一起发")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        for (int i = 0; i < capacity + 88; i++) {
            metrics.l1Request(CACHE, "k" + i, true);
        }

        List<MallCacheMetrics.MallCacheEvent> events = metrics.recentEvents();
        assertThat(events).as("容量有界：只留最近 %d 条", capacity).hasSize(capacity);
        assertThat(events.get(0).seq()).as("最旧的一条是第 88 个").isEqualTo(88L);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + 87L);
        assertThat(events.get(0).key()).as("key 只在事件里（tag 里绝不放 key）").isEqualTo("k88");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.cache()).isEqualTo(CACHE);
            assertThat(e.kind()).isEqualTo("l1");
            assertThat(e.result()).isEqualTo("hit");
            assertThat(e.epochMilli()).isPositive();
        });
        assertThat(count(MallCacheMetrics.L1_REQUESTS, "cache", CACHE, "result", "hit"))
                .as("计数通道与事件通道同源：同一次调用点两边都记").isEqualTo((double) (capacity + 88));
    }

    @Test
    @DisplayName("探活事件不带 cache/key（那是全局事实）")
    void probeEventsCarryNoCacheDimension() throws Exception {
        L2HealthProbe probe = probeOver(fakeFactory(new AtomicBoolean(true)));
        probe.markFailed(new RedisConnectionFailureException("模拟"));
        sleepPastCooldown();
        probe.isL2UsableNow();   // 探一次（成功）

        List<MallCacheMetrics.MallCacheEvent> events = metrics.recentEvents();
        assertThat(events).extracting(MallCacheMetrics.MallCacheEvent::kind)
                .contains("probe", "transition");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.cache()).as("探活/翻转是全局事实，故无 cache 维度").isNull();
            assertThat(e.key()).isNull();
        });
    }

    @Test
    @DisplayName("并发追加：快照里的 seq 严格递增且不重复（写者只做数组赋值，读者靠 seq 自校验）")
    void concurrentAppendsKeepSnapshotCoherent() throws Exception {
        int threads = 4;
        int perThread = 600;   // 4 × 600 = 2400 > 容量 2048 ⇒ 必然发生覆盖
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                String key = "k" + t;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            metrics.l1Request(CACHE, key, true);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<MallCacheMetrics.MallCacheEvent> snapshot = metrics.recentEvents();
        List<Long> seqs = new ArrayList<>();
        snapshot.forEach(e -> seqs.add(e.seq()));
        Set<Long> unique = new HashSet<>(seqs);
        assertThat(seqs).as("seq 严格递增（读者按 seq 自校验后不应出现错位）").isSorted();
        assertThat(unique).as("同一条事件不该在快照里出现两次").hasSameSizeAs(seqs);
        assertThat(snapshot).as("容量有界").hasSizeLessThanOrEqualTo(2048);
    }

    // ================= 工具 =================

    /** 计数器的当前值；没注册过该 (name, tags) 组合时按 0 —— 即「这条时间序列不存在」。 */
    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double up() {
        return registry.find(MallCacheMetrics.L2_UP).gauge().value();
    }

    /** L1 假实现：裸 mock —— getIfPresent / get(key, mapper) 都默认返回 null。 */
    @SuppressWarnings("unchecked")
    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> l1Mock() {
        return mock(com.github.benmanes.caffeine.cache.Cache.class);
    }

    /**
     * L1 假实现：一律未命中，但**真的会执行 mappingFunction** —— 等价于一个空的真 Caffeine 缓存。
     * ⚠️ 必须显式补这个 thenAnswer：裸 mock 的 get(key, mapper) 直接返回 null（不执行 mapper），
     * 那等价于「别的线程已经填好了」，TwoLevelCache 会走成 singleflight 而不是回落 DB
     * —— 本文件第一版就栽在这里（NPE：mapped == null）。对照组见 l1FilledByOtherThread()。
     */
    @SuppressWarnings("unchecked")
    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> l1Empty() {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = l1Mock();
        when(l1.get(any(), any())).thenAnswer(invocation -> {
            Function<Object, Object> mapper = invocation.getArgument(1);
            return mapper.apply(invocation.getArgument(0));
        });
        return l1;
    }

    /** L1 假实现：指定键命中（用于「两条读路径都在 l1Get 计数」）。 */
    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> l1Hit(String key, Object value) {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = l1Mock();
        when(l1.getIfPresent(key)).thenReturn(new SimpleValueWrapper(value));
        return l1;
    }

    /**
     * L1 假实现：mappingFunction **不被执行**、直接返回「别的线程写好的值」——
     * 即单飞里的 follower（真实现中是 Caffeine 让后来者直接拿赢家的结果）。
     */
    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> l1FilledByOtherThread() {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = l1Mock();
        when(l1.get(any(), any())).thenReturn(new SimpleValueWrapper("fromOtherThread"));
        return l1;
    }

    private static TwoLevelCache newCache(com.github.benmanes.caffeine.cache.Cache<Object, Object> l1,
                                          Cache l2, L2HealthProbe probe, MallCacheMetrics metrics) {
        return new TwoLevelCache(CACHE, l1, l2, probe, metrics);
    }

    /** 健康态的 probe：isL2UsableNow() 恒 true（本文件的 probe 计数由探活测试单独覆盖）。 */
    private static L2HealthProbe probeUsable() {
        L2HealthProbe probe = mock(L2HealthProbe.class);
        when(probe.isL2UsableNow()).thenReturn(true);
        return probe;
    }

    /** 冷却窗口内的 probe：isL2UsableNow() 恒 false ⇒ 走降级路径且不碰 L2。 */
    private static L2HealthProbe probeBlocked() {
        L2HealthProbe probe = mock(L2HealthProbe.class);
        when(probe.isL2UsableNow()).thenReturn(false);
        return probe;
    }

    /** 探活用的 probe：注入本测试的 metrics（探活的计数/事件就记在它上面，供上面两个测试断言）。 */
    private L2HealthProbe probeOver(RedisConnectionFactory factory) {
        L2HealthProbe probe = new L2HealthProbe(factory, COOLDOWN, metrics);
        // 镜像 L2ProbeConfig 的装配动作（那里在 @Bean 方法里绑 gauge）。
        // ⚠️ 所以本文件验的是 gauge 的**语义**（值随状态变化），**不是**「有没有绑定」——
        // 绑定的存在性由 /actuator/metrics 的实测覆盖（不绑定就查不到 mall.cache.l2.up）。
        metrics.bindL2Up(probe::isHealthy);
        return probe;
    }

    /**
     * 假探测通道：ping() 的成败由 {@code pong} 决定（与 L2HealthProbeConcurrencyTest 同一手法：
     * 真起不了 Redis 的东西按断言切「可达/不可达」）。
     */
    private static RedisConnectionFactory fakeFactory(AtomicBoolean pong) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenAnswer(invocation -> {
            if (pong.get()) {
                return "PONG";
            }
            throw new RedisConnectionFailureException("模拟 Redis 不可达");
        });
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        return factory;
    }

    /** 等窗口到期。纯等超时，不是并发赛跑 —— 多等 70ms 换掉 flaky。 */
    private static void sleepPastCooldown() throws InterruptedException {
        Thread.sleep(COOLDOWN.toMillis() + 70);
    }
}
