package com.minke.mallmanagement_server;

import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.dao.DataAccessException;

import java.util.concurrent.Callable;

/**
 * 两级缓存：L1 = Caffeine（进程内）→ L2 = Redis（分布式）。
 *
 * 【职责边界】本类只做「两级读写 + 降级」，不持有任何业务语义。
 * 业务代码零改动：@Cacheable / @CacheEvict 仍按单级缓存的方式书写（注解落在 Service，
 * 见 docs/agent/context-packs/cache-layer.md §0.1），本类被 {@link TwoLevelCacheManager}
 * 按缓存名装配进「注解背后的实现」。
 *
 * 【L1 里存的是 ValueWrapper，不是裸值】
 * Caffeine 不接受 value 为 null（put(null) 直接抛 NPE），而 Spring 的 Cache 语义要求
 * 区分「未命中」与「命中且值为 null」—— 后者正是 cache-layer.md §9 的空值缓存防穿透。
 * 故 L1 一律存 {@link Cache.ValueWrapper}：命中（含命中空值）返回非 null 的 wrapper，
 * 未命中才返回 null。L2 侧不做包装，由 RedisCache 自己按 allowNullValues 处理。
 *
 * 【降级是硬约束，不是优化项】见 cache-layer.md §9 / §11。
 * Redis 的 DataAccessException（连接失败 / 超时 / 命令错误）在本类内被吞掉：
 *   读  → 返回 null（视作未命中，由调用方回落 DB）
 *   写 / 失效 → 静默跳过
 * 绝不上抛：上抛会把 Redis 抖动传导为 HTTP 5xx，直接打挂 A3 的 A-S1-1 / A-S4b-1。
 * L2 是否「现在值得碰」由 {@link L2HealthProbe} 判定（**全局一份**，不是每个缓存名一份）：
 * 每次 L2 操作前先问它，冷却窗口内直接跳过 —— 也就是「Redis 挂了」这个全局事实在这里
 * 只体现为一次 volatile 读，而不是每次操作都去撞一遍网络。本类只负责把每次 L2 操作的
 * 成败喂回去（markOk / markFailed）。
 *
 * 只吞 DataAccessException，不吞 RuntimeException 全集：序列化失败（SerializationException）
 * 之类的编程错误必须照常上抛 —— 那属于「缓存放了不该放的东西」，要在开发期暴露，
 * 不能被降级静默掩盖成「缓存永不生效」。
 */
public class TwoLevelCache implements Cache {

    private final String name;
    /** L1：Caffeine 原生实例，value 一律是 {@link Cache.ValueWrapper}。 */
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> l1;
    /** L2：RedisCache（可能为 null —— L2 拒绝动态创建该缓存名时退化为仅 L1）。 */
    private final Cache l2;
    /**
     * L2 健康状态 —— 由 {@link TwoLevelCacheManager} 注入的**共享**实例。
     * 不在这里持有副本：状态必须是全局一份，理由见 {@link L2HealthProbe} 类注释。
     */
    private final L2HealthProbe probe;

    TwoLevelCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> l1,
                  Cache l2, L2HealthProbe probe) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
        this.probe = probe;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * 返回 L1 原生实例（Caffeine）。
     * ⚠️ 其中的 value 是 ValueWrapper，不是业务值 —— 只用于观测（如 stats()），不要当数据源用。
     */
    @Override
    public Object getNativeCache() {
        return l1;
    }

    // ================= 读 =================

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper fromL1 = l1Get(key);
        if (fromL1 != null) {
            return fromL1;
        }
        ValueWrapper fromL2 = l2Get(key);
        if (fromL2 != null) {
            // 回填 L1：L2 命中说明这是热数据，本地留一份免去后续跨网络往返
            l1.put(key, fromL2);
        }
        return fromL2;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    /**
     * loader 形态的读 —— cache-layer.md §9 的「击穿：一级（Caffeine）单飞」就落在本方法。
     *
     * 走 Caffeine 的原子 get(key, mappingFunction)：同一 key 上的并发调用只有一个会真正执行
     * mappingFunction，其余阻塞等待其结果，而不是各自去查 L2 / 回落 DB。
     * mappingFunction 内先探 L2、再落 loader，故 L2 命中时也不会重复加载。
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper fromL1 = l1Get(key);
        if (fromL1 != null) {
            return (T) fromL1.get();
        }

        // 只有真正执行了 loader 的那次才回写 L2（避免 L2 命中路径把 TTL 无谓地续期）。
        // 用数组承载：lambda 需要一个可写的闭包槽位。
        boolean[] loadedHere = {false};

        Object mapped = l1.get(key, k -> {
            ValueWrapper fromL2 = l2Get(k);
            if (fromL2 != null) {
                return fromL2;
            }
            loadedHere[0] = true;
            try {
                return new SimpleValueWrapper(valueLoader.call());
            } catch (Exception e) {
                // 抛出去 → Caffeine 不写入任何条目，异常原样上抛给调用方
                throw new ValueRetrievalException(k, valueLoader, e);
            }
        });

        ValueWrapper wrapper = (ValueWrapper) mapped;
        if (loadedHere[0]) {
            l2Put(key, wrapper.get());
        }
        return (T) wrapper.get();
    }

    // ================= 写 / 失效 =================

    @Override
    public void put(Object key, Object value) {
        l1.put(key, new SimpleValueWrapper(value));
        l2Put(key, value);
    }

    /**
     * 两级都清。
     * 这是「写接口必须清缓存」的落点（cache-layer.md §10）：只加 @Cacheable 不加 evict，
     * 得到的是永不更新的脏缓存。
     */
    @Override
    public void evict(Object key) {
        l1.invalidate(key);
        l2Evict(key);
    }

    /** @CacheEvict(allEntries = true) 落在这里：两级全清。 */
    @Override
    public void clear() {
        l1.invalidateAll();
        l2Clear();
    }

    // ================= L2 访问 + 降级 =================
    // 每个 L2 操作都先问 probe.isL2UsableNow()：冷却窗口内直接跳过，零网络代价。
    // ⚠️ 该方法**可能阻塞 ≤ 探测超时**（窗口到期时，唯一抢到探测权的线程会同步 PING 一次），
    //    且探测者本人也走降级路径 —— 见 L2HealthProbe.isL2UsableNow() 的注释。

    private ValueWrapper l1Get(Object key) {
        // L1 里存的必然是 ValueWrapper（见类注释），故强转安全
        return (ValueWrapper) l1.getIfPresent(key);
    }

    private ValueWrapper l2Get(Object key) {
        if (l2 == null) {
            return null;
        }
        if (!probe.isL2UsableNow()) {
            // 冷却中（或本线程刚探完）：视作 L2 未命中 —— 与「Redis 连不上」同一条降级路径
            return null;
        }
        try {
            ValueWrapper wrapper = l2.get(key);
            probe.markOk();
            return wrapper;
        } catch (DataAccessException e) {
            probe.markFailed(e);
            return null;
        }
    }

    private void l2Put(Object key, Object value) {
        if (l2 == null) {
            return;
        }
        if (!probe.isL2UsableNow()) {
            return;   // 冷却中：跳过写（与降级路径一致）
        }
        try {
            l2.put(key, value);
            probe.markOk();
        } catch (DataAccessException e) {
            probe.markFailed(e);
        }
    }

    private void l2Evict(Object key) {
        if (l2 == null) {
            return;
        }
        if (!probe.isL2UsableNow()) {
            // 冷却中：跳过失效。L2 里可能因此留下一个旧值，但：
            //   (a) 这段时间本就没在读 L2，(b) L2 TTL（a3 为 5m）是陈旧上界的兜底
            return;
        }
        try {
            l2.evict(key);
            probe.markOk();
        } catch (DataAccessException e) {
            probe.markFailed(e);
        }
    }

    private void l2Clear() {
        if (l2 == null) {
            return;
        }
        if (!probe.isL2UsableNow()) {
            return;   // 冷却中：跳过整片失效（同上，L2 TTL 兜底）
        }
        try {
            l2.clear();
            probe.markOk();
        } catch (DataAccessException e) {
            probe.markFailed(e);
        }
    }
}
