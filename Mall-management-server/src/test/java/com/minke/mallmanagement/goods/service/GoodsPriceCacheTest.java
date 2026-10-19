package com.minke.mallmanagement.goods.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NullValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * {@code GoodsCacheService.priceByTriple()} 的**缓存行为**实测 —— P0 #2（B+E 合成链）的收尾，
 * 2026-09-30。
 *
 * <h2>为什么不能只看返回值</h2>
 * 本次改的那条性质是<b>「失败会不会被固化到 TTL」</b>，它<b>不在返回值里</b> ——
 * 改前改后，{@code Controller} 对 0 行与技术异常都落同一个 {@code {success:false}}。
 * 故本类的判据一律是<b>「这段代码有没有第二次查库」</b>（{@code jdbc.calls}）。
 * 这不是取巧，是唯一能把这个差异观测出来的通道：返回值相同，副作用不同。
 *
 * <h2>为什么是 {@code @ContextConfiguration} + 最小上下文，而不是 {@code @SpringBootTest}</h2>
 * <ol>
 *   <li>本类要测的是 {@code @Cacheable} 这个<b>代理行为</b>（{@code sync=true}、
 *       命中/未命中、异常写不写缓存）—— 手写 {@code new GoodsCacheService()} 拿不到代理，
 *       注解会<b>静默</b>失效（本项目已在自调用上栽过一次，见 cache-layer.md §11 ㊸③）。</li>
 *   <li>但 {@code @SpringBootTest} 会拉起应用自己的 {@code CacheConfig}，那个 {@code CacheManager}
 *       要 {@code RedisConnectionFactory} —— 而 Backend CI job 没有 Redis
 *       （{@code ci.yml} 只跑 {@code ./mvnw -B test}，不启 Redis；用真 Redis 的
 *       {@code LockSceneRedisTest} 正因此被 {@code A3_ENABLED} 挡住）。
 *       若走那条路，本类会变成一个<b>只在本地绿</b>的用例。</li>
 *   <li>折中是：{@code @EnableCaching} + {@code ConcurrentMapCacheManager} —— <b>代理路径与生产
 *       完全同源</b>（都是 Spring 的 {@code CacheInterceptor}），只有 L1/L2 两层存储换成内存实现。
 *       于是本类证明的是「注解与方法的契约」，不是「Redis 连得通」。</li>
 * </ol>
 *
 * <h2>与生产的那点差异，以及它会不会让结论失真</h2>
 * 生产走 {@code TwoLevelCache}（Caffeine + Redis），本类走 {@code ConcurrentMapCache}。
 * 二者在**本类关心的三条**上行为一致：命中不查库、0 行的 {@code null} 会被存下来、
 * <b>载荷抛异常时不写入条目</b>（{@code TwoLevelCache:118-164} 的 {@code ValueRetrievalException}
 * 与 {@code ConcurrentMapCache.get(k, Callable)} 的 {@code computeIfAbsent} 是同一个约定）。
 * 但 <b>{@code null} 究竟是「命中并返回 null」还是「未命中」</b>这一条，本类用
 * {@link #zeroRowsIsCachedAsNullValue_notJustAMiss()} 单独钉住。⚠️ <b>这里要小心一个
 * 差一点写反的地方</b>（本类第一版就写反了，跑出来才改）：
 * <ul>
 *   <li>看 {@code cache.get(key).get()} —— <b>分不出来</b>，两者都是 {@code null}；</li>
 *   <li>看 {@code cache.get(key)} <b>本身</b>（wrapper 是否为 null）—— <b>能分</b>：
 *       未命中时整个 wrapper 是 {@code null}，负缓存时 wrapper 非 null 而它的值是 {@code null}。
 *       机制是 Spring 的 {@code AbstractValueAdaptingCache.toValueWrapper()}：
 *       它判的是<b>存进去的东西</b>（{@code NullValue.INSTANCE}，非 null），
 *       {@code fromStoreValue} 被关在 wrapper <b>里面</b>才降级成 null。</li>
 * </ul>
 * 这条对生产也成立 —— {@code RedisCache} 同样继承 {@code AbstractValueAdaptingCache}，
 * 且 {@code NullValue} 有 {@code readResolve}，反序列化回来还是那个单例。
 *
 * <h2>本类不覆盖的</h2>
 * Controller 侧的响应成型（catch → {@code {success:false}}）在
 * {@code smoke.PriceTripleFailSmokeTest}；真实 MySQL 的 collation（B 环）与
 * 「库内重复三元组有几组」在 A3 —— 2026-10-12〈Bug 1〉实测**有 1 组**
 * （{@code dRD3gqtbFr} / {@code GD-101-9552} = Id 76/120），本类的替身给不了 ORDER BY，
 * 故「取 MIN(Id)」这半边的判据在 SQL 字面量与那条 A3 实测，不在本类。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = GoodsPriceCacheTest.Ctx.class)
class GoodsPriceCacheTest {

    static final String CACHE_NAME = "goodsPrice";

    @Configuration
    @EnableCaching
    static class Ctx {

        /** 只记账不落库的替身 —— 本文件不需要数据库的全部原因，也是本类的主判据。 */
        @Bean
        StubJdbcTemplate jdbcTemplate() {
            return new StubJdbcTemplate();
        }

        @Bean
        GoodsCacheService goodsCacheService() {
            return new GoodsCacheService();
        }

        /**
         * 无 TTL —— 本类测的是「写不写」，不是「多久过期」。
         *
         * <p>⚠️ 用<b>无参</b>构造（动态建缓存），不用 {@code new ConcurrentMapCacheManager("goodsPrice")}：
         * 后者会把命名空间钉死，而 {@code evictByMerchant()} 的 {@code @Caching} 要清<b>四个</b>
         * 命名空间；{@code AbstractCacheResolver} 对未注册的名字直接抛
         * {@code IllegalStateException("Cannot find cache named ...")} ⇒
         * {@link #evictClearsTheNegativeEntryToo()} 会红在一个与被测性质无关的地方，
         * 且将来 evict 多清一个命名空间时还会再红一次。动态建缓存把这个耦合切断了。
         */
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private GoodsCacheService service;

    @Autowired
    private StubJdbcTemplate jdbc;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        jdbc.reset();
        cache().clear();
    }

    // ===================== 一、SQL 与参数原样穿过 =====================

    @Test
    @DisplayName("SQL 仍是原字面量三参数顺序（MerchantId, GoodName, ProductCode）—— 错位是「有值但值不对」")
    void sqlAndArgsArePassedThroughUnchanged() {
        jdbc.rows = List.of(row("Price", 9.9, "GoodImg", "/img/a.jpg"));

        service.priceByTriple("M1", "G1", "P1");

        assertThat(jdbc.lastSql).isEqualTo(
                "SELECT g.Price, g.GoodImg, g.OriginalPrice, g.ImgList, "
                        + "EXISTS(SELECT 1 FROM mall_management_sql.user u WHERE u.merchantid = g.MerchantId AND u.IsBrand = 1) AS IsBrand "
                        + "FROM mall_management_sql.goods g "
                        + "WHERE g.MerchantId=? AND g.GoodName=? AND g.ProductCode=? AND g.Status = 2 "
                        + "ORDER BY g.Id ASC LIMIT 1");
        assertThat(jdbc.lastArgs).containsExactly("M1", "G1", "P1");
    }

    // ===================== 二、命中路径（改动未触及，作为对照） =====================

    @Test
    @DisplayName("1 行：返回**裸行**（键 = 列名），第二次不再查库")
    void hitReturnsRawRowAndSecondCallSkipsDb() {
        jdbc.rows = List.of(row("Price", 9.9, "GoodImg", "/img/a.jpg"));

        Map<String, Object> first = service.priceByTriple("M1", "G1", "P1");

        // 返回契约已变：不再是 {success, price, goodImg}，而是原样一行。
        assertThat(first).containsOnlyKeys("Price", "GoodImg");
        assertThat(first.get("Price")).isEqualTo(9.9);

        assertThat(service.priceByTriple("M1", "G1", "P1")).isSameAs(first);
        assertThat(jdbc.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("不同三元组不互相命中（三维复合键的三段都真的参与）")
    void differentTriplesDoNotShareAnEntry() {
        jdbc.rows = List.of(row("Price", 9.9, "GoodImg", "/img/a.jpg"));

        service.priceByTriple("M1", "G1", "P1");
        service.priceByTriple("M1", "G1", "P2");
        service.priceByTriple("M1", "G2", "P1");
        service.priceByTriple("M2", "G1", "P1");

        assertThat(jdbc.calls).isEqualTo(4);
    }

    // ===================== 三、0 行：业务事实，作为值缓存 =====================

    @Test
    @DisplayName("0 行：返回 null，且这个 null 是**被缓存的值**，不是未命中")
    void zeroRowsIsCachedAsNullValue_notJustAMiss() {
        jdbc.rows = List.of();

        assertThat(service.priceByTriple("M1", "G1", "P1")).isNull();
        assertThat(jdbc.calls).isEqualTo(1);

        assertThat(service.priceByTriple("M1", "G1", "P1")).isNull();
        assertThat(jdbc.calls).as("防穿透：第二次不得再查库").isEqualTo(1);

        // ⚠️ 这三句是本类存在的一半理由：把「负缓存」与「未命中」分开。
        // 分得开的是 **wrapper 本身**，不是它的值 —— 看 .get() 才真的分不出来。
        Cache.ValueWrapper negative = cache().get("v4::M1::G1::P1");
        assertThat(negative.get()).isNull();                       // 值确实是 null
        assertThat(negative).as("负缓存 ⇒ wrapper 非 null").isNotNull();
        assertThat(cache().get("v4::M1::G1::P9")).as("对照组：没查过的键 ⇒ wrapper 是 null").isNull();

        // 机制（不是为了断言它，是为了让上一条读得懂）：存进去的是 Spring 的 NullValue，
        // fromStoreValue 把它降级成 null 是在 wrapper 内部发生的 —— 所以 wrapper 还在。
        @SuppressWarnings("unchecked")
        var nativeCache = (Map<Object, Object>) cache().getNativeCache();
        assertThat(nativeCache).containsKey("v4::M1::G1::P1");
        assertThat(nativeCache.get("v4::M1::G1::P1")).isInstanceOf(NullValue.class);
    }

    @Test
    @DisplayName("写接口清缓存后，0 行的负缓存随之消失（改前改后都靠这条自愈）")
    void evictClearsTheNegativeEntryToo() {
        jdbc.rows = List.of();
        service.priceByTriple("M1", "G1", "P1");
        assertThat(jdbc.calls).isEqualTo(1);

        service.evictByMerchant("M1");

        jdbc.rows = List.of(row("Price", 1.5, "GoodImg", "/img/b.jpg"));
        assertThat(service.priceByTriple("M1", "G1", "P1").get("Price")).isEqualTo(1.5);
        assertThat(jdbc.calls).isEqualTo(2);
    }

    // ======== 四、多行：取 MIN(Id) 那一行（2026-10-12〈Bug 1〉改判；原为「抛异常」） ========

    @Test
    @DisplayName(">1 行：**不再抛** —— 取第一行（序由 SQL 的 ORDER BY Id ASC LIMIT 1 保证）")
    void manyRowsTakesTheFirstRowInsteadOfThrowing() {
        jdbc.rows = List.of(
                row("Price", 9.9, "GoodImg", "/img/a.jpg"),
                row("Price", 9.8, "GoodImg", "/img/a.jpg"));

        assertThat(service.priceByTriple("M1", "G1", "P1").get("Price")).isEqualTo(9.9);
        assertThat(jdbc.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("★反向断言：多行不再是「调用方拿不到价」—— 改前它让 /goods/productCodePrice 恒失败")
    void manyRowsNoLongerStarvesTheCaller() {
        // 改前：这里抛 IncorrectResultSizeDataAccessException ⇒ GoodsService 接成 {success:false}
        // ⇒ ProductPage.loadAllItems() 的 `if (d2.success)` 跳过 ⇒ allItems 空 ⇒ **整页空白**。
        // 实测那条链：dRD3gqtbFr / GD-101-9552 有 Id 76/120 两行，且**两行数据都完整**
        // （Price 299 / OriginalPrice 373.75 / ShelfState 1）—— 不是缺数据，是读侧抛了。
        jdbc.rows = List.of(
                row("Price", 9.9, "GoodImg", "/img/a.jpg"),
                row("Price", 9.8, "GoodImg", "/img/a.jpg"));

        // ⚠️ 用 assertAll 而不是裸断言：变异检查（把生产改回「抛」）时要同时报出「拿不到」与
        //    「返回的不是第一行」两件事 —— 裸断言第一条一红就中止，第二条反而看不到。
        assertAll(
                () -> assertThat(service.priceByTriple("M1", "G1", "P1")).as("改前这里是抛").isNotNull(),
                () -> assertThat(service.priceByTriple("M1", "G1", "P1").get("Price")).isEqualTo(9.9),
                () -> assertThat(jdbc.calls).as("正常返回值 ⇒ 命中缓存，第二次不查库").isEqualTo(1));
    }

    @Test
    @DisplayName("⚠️ 代价如实钉住：多行结果**会**进缓存（改前「自愈」那一条由此不再成立）")
    void manyRowsResultIsCachedNow_unlikeTheOldThrowBehaviour() {
        jdbc.rows = List.of(
                row("Price", 9.9, "GoodImg", "/img/a.jpg"),
                row("Price", 9.8, "GoodImg", "/img/a.jpg"));

        service.priceByTriple("M1", "G1", "P1");
        assertThat(jdbc.calls).isEqualTo(1);

        service.priceByTriple("M1", "G1", "P1");
        // 改前这里是 2（异常 ⇒ @Cacheable 不写入 ⇒ 每次都真撞库 ⇒ 数据一修好立刻正确）。
        // 现在多行是**正常返回值** ⇒ 进 TTL（a3 5m）：库里的重复行被删/改后，本键在 TTL 内仍回旧价;
        // 自愈改由**写接口的 allEntries** 承担（evictByMerchant，见 evictClearsTheNegativeEntryToo）。
        assertThat(jdbc.calls).as("多行结果被缓存，TTL 内不再查库").isEqualTo(1);
    }

    // ===================== 五、其它技术异常：原样上抛，同样不缓存 =====================

    @Test
    @DisplayName("库连不上：异常**原样**上抛（不被包装成成功），且不缓存")
    void technicalFailurePropagatesUnwrappedAndIsNotCached() {
        DataAccessResourceFailureException boom =
                new DataAccessResourceFailureException("连不上库");
        jdbc.toThrow = boom;

        assertThatThrownBy(() -> service.priceByTriple("M1", "G1", "P1")).isSameAs(boom);
        assertThat(jdbc.calls).isEqualTo(1);

        assertThatThrownBy(() -> service.priceByTriple("M1", "G1", "P1")).isSameAs(boom);
        assertThat(jdbc.calls).as("技术异常不得被缓存").isEqualTo(2);
        assertThat(cache().get("v4::M1::G1::P1")).isNull();
    }

    // ===================== 工具 =====================

    private Cache cache() {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        assertThat(cache).as("cacheManager 里应有 %s", CACHE_NAME).isNotNull();
        return cache;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    /**
     * 只记账不落库的 JdbcTemplate 替身。
     *
     * <p>⚠️ 覆写的是 {@code queryForList(String, Object...)} —— 与生产同一条重载
     * （{@code JdbcTemplate} 上另有泛型重载 {@code <T> List<T> queryForList(String, Class<T>, Object...)}，
     * 那条的第一个可变参是 {@code Class}，本方法传的是 String，选不到它）。
     *
     * <p>⚠️ <b>变异检查的用法</b>：若把生产改回 {@code queryForMap}，本替身必须<b>同时</b>改覆写
     * {@code queryForMap}，否则调用会落到未被覆写的真实实现上去（它没有 DataSource，
     * 会抛 {@code IllegalStateException} 而不是我们想要的失败）—— 那会把「测试红了」
     * 伪装成「测试红了且原因正确」。
     */
    static final class StubJdbcTemplate extends JdbcTemplate {

        /** 主判据：这段代码有没有第二次查库。 */
        volatile int calls;
        volatile String lastSql;
        volatile Object[] lastArgs;
        /** 下一次查询返回的行。默认空 = 「查无此三元组」。 */
        volatile List<Map<String, Object>> rows = new ArrayList<>();
        /** 非 null 时优先抛出（模拟连不上库等技术异常）。 */
        volatile RuntimeException toThrow;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            calls++;
            lastSql = sql;
            lastArgs = args;
            RuntimeException e = toThrow;
            if (e != null) {
                throw e;
            }
            return rows;
        }

        /**
         * 把 {@code JdbcTemplate} 继承来的「必须有 DataSource」这条前置校验关掉。
         *
         * <p>它是给「忘了注入 DataSource」准备的，本类恰恰是<b>故意</b>不注入 —— 查询整个被
         * {@link #queryForList} 拦下，永远走不到真正需要 DataSource 的地方。
         * 不关掉它，作为 {@code @Bean} 建出来时就会在 {@code afterPropertiesSet()} 上炸
         * （{@code IllegalArgumentException: Property 'dataSource' is required}），
         * 而不是我们想测的那几条。<b>这也正是本类不能像 {@code OrderTimelineWriterTest}
         * 那样手工 new 的原因</b>：那边是构造器注入，不需要 Spring 容器；
         * 这边要的是 {@code @Cacheable} 的<b>代理</b>。
         *
         * <p>⚠️ 只关这一条。若将来生产改用别的方法（比如 {@code query}），本替身没覆写它，
         * 继承来的实现会抛 {@code "No DataSource set"} —— 一个响亮的失败，
         * 而不是静默地让用例变绿。
         */
        @Override
        public void afterPropertiesSet() {
            // 故意留空，理由见 javadoc
        }

        void reset() {
            calls = 0;
            lastSql = null;
            lastArgs = null;
            rows = new ArrayList<>();
            toThrow = null;
        }
    }
}
