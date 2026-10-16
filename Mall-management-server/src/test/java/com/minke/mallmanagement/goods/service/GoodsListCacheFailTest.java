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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
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
 * {@code GoodsCacheService} 的**三个列表读方法**（{@code listHome} / {@code listByMerchant} /
 * {@code listCodes}）的「失败会不会被固化到 TTL」实测 —— ② 笔（2026-10-03）的收尾。
 *
 * <h2>为什么不能只看返回值</h2>
 * 本次改的那条性质是<b>「失败会不会被缓存」</b>，它<b>不在返回值里</b> —— 改前改后，
 * 调用方（{@code GoodsService.goodsSelectHome} 等三个）对技术异常都落同一个
 * {@code {success:false,msg:"查询失败"}}（{@code listCodes} 那条无 {@code msg}）。
 * 故本类的判据一律是<b>「这段代码有没有第二次查库」</b>（{@code jdbc.calls} / {@code jdbc.innerCalls}）。
 * 与 {@link GoodsPriceCacheTest} 同一套判据，同一套理由：返回值相同，副作用不同。
 *
 * <h2>与 {@code GoodsPriceCacheTest} 的分工</h2>
 * 那个类管 {@code priceByTriple}（P0 #2，2026-09-30 修）—— 它的缺陷形态是
 * {@code queryForMap} 把「0 行」与「>1 行」折叠进同一个 catch。本类管的是**同一缺陷的另外三个
 * 实例**（② 笔，2026-10-03），它们的形态不同：0 行本来就返回 {@code success:true, list:[]}，
 * 与失败**没有**被折叠 ⇒ 所以修法只需「删 catch」，**不需要**改返回形状，但 {@code listHome}
 * 的 <b>per-row 兜底</b>是例外（见下）。
 *
 * <h2>本类刻意覆盖的四件事</h2>
 * <ol>
 *   <li><b>阴性 ×3</b>：三个方法各自「技术异常 ⇒ 不缓存」；</li>
 *   <li><b>阳性对照</b>：成功路径仍缓存 —— 没有它，一个「从不缓存」的错误实现照样全绿
 *       （与 {@code ValidationProbeTest} 阴性/阳性成对是同一个理由）；</li>
 *   <li><b>{@code listHome} 内层 0 行仍是 {@code UserName:""}</b> —— 收窄 catch 时最容易
 *       误伤的就是这条业务语义；</li>
 *   <li><b>{@code listHome} 内层 &gt;1 行改为上抛</b> —— 这是本次<b>有意引入</b>的行为变更
 *       （改前静默 {@code ""}），必须被钉住，否则将来有人会把它「修回去」。</li>
 * </ol>
 *
 * <h2>为什么是 {@code @ContextConfiguration} + 最小上下文，而不是 {@code @SpringBootTest}</h2>
 * 同 {@code GoodsPriceCacheTest}，理由不复述（见那边的 javadoc）：要的是 {@code @Cacheable}
 * 的<b>代理</b>，但不能拉起应用自己的 {@code CacheConfig}（它要 Redis，而 Backend CI job 没有）。
 * 折中是 {@code @EnableCaching} + {@code ConcurrentMapCacheManager} —— 代理路径与生产同源，
 * 只有 L1/L2 两层存储换成内存实现。二者在本类关心的那条上行为一致：
 * <b>载荷抛异常时不写入条目</b>（{@code TwoLevelCache:150-152} 的 {@code ValueRetrievalException}
 * 与 {@code ConcurrentMapCache.get(k, Callable)} 的 {@code computeIfAbsent} 是同一个约定）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = GoodsListCacheFailTest.Ctx.class)
class GoodsListCacheFailTest {

    static final String HOME = "goodsHome";
    static final String BY_MERCHANT = "goodsByMerchant";
    static final String CODES = "goodsCodes";

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

        /** 无参构造（动态建缓存）：三个 cacheName 都要，且 {@code evictByMerchant} 的 @Caching 碰四个。 */
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
        for (String name : List.of(HOME, BY_MERCHANT, CODES)) {
            cache(name).clear();
        }
    }

    // ===================== 一、阴性：技术异常不缓存（本次改动的全部内容） =====================

    @Test
    @DisplayName("★反向断言 listByMerchant：库连不上 ⇒ 异常上抛，且**第二次仍要查库**（改前的要害正是它不再查）")
    void listByMerchantFailureIsNeverCached() {
        DataAccessResourceFailureException boom = new DataAccessResourceFailureException("连不上库");
        jdbc.toThrow = boom;

        // ⚠️ 用 assertAll 而不是裸断言：变异检查时要同时报出「没抛异常」与「calls 停在 1（失败被缓存）」，
        //    写成裸断言时第一条一红就中止，而第二条才是真正说明缺陷的那句（GoodsPriceCacheTest 实测踩到）。
        assertAll(
                () -> assertThatThrownBy(() -> service.listByMerchant("M1")).isSameAs(boom),
                () -> assertThat(jdbc.calls).as("第一次：确实查了库").isEqualTo(1),

                () -> assertThatThrownBy(() -> service.listByMerchant("M1")).isSameAs(boom),
                // ← 这句是本笔修复的全部内容。改前这里会是 1（失败 marker 被缓存满 TTL）。
                () -> assertThat(jdbc.calls).as("失败不得固化到 TTL").isEqualTo(2),
                () -> assertThat(cache(BY_MERCHANT).get("M1")).as("失败不得留下条目").isNull());
    }

    @Test
    @DisplayName("★反向断言 listCodes：同上（二维键 M::G，另跑一遍是因为它走的是另一个 queryForList 重载）")
    void listCodesFailureIsNeverCached() {
        DataAccessResourceFailureException boom = new DataAccessResourceFailureException("连不上库");
        jdbc.toThrow = boom;

        assertAll(
                () -> assertThatThrownBy(() -> service.listCodes("M1", "G1")).isSameAs(boom),
                () -> assertThat(jdbc.calls).isEqualTo(1),
                () -> assertThatThrownBy(() -> service.listCodes("M1", "G1")).isSameAs(boom),
                () -> assertThat(jdbc.calls).as("失败不得固化到 TTL").isEqualTo(2),
                () -> assertThat(cache(CODES).get("M1::G1")).isNull());
    }

    @Test
    @DisplayName("★反向断言 listHome：主查询失败 ⇒ 上抛（外层 catch 已删），不为那个全局单键 'all' 留下失败条目")
    void listHomeFailureIsNeverCached() {
        DataAccessResourceFailureException boom = new DataAccessResourceFailureException("连不上库");
        jdbc.toThrow = boom;

        assertAll(
                () -> assertThatThrownBy(() -> service.listHome()).isSameAs(boom),
                () -> assertThat(jdbc.calls).isEqualTo(1),
                () -> assertThatThrownBy(() -> service.listHome()).isSameAs(boom),
                // 改前：外层 catch 把失败吞成 {success:false,msg:"查询失败"} 并缓存到 'all'，
                //       于是 TTL 内**所有**客户端首页请求都拿到「查询失败」—— 库修好了也不恢复。
                () -> assertThat(jdbc.calls).as("失败不得固化到 TTL").isEqualTo(2),
                () -> assertThat(cache(HOME).get("all")).isNull());
    }

    // ===================== 二、阳性对照：成功路径仍然缓存 =====================

    @Test
    @DisplayName("阳性对照：成功路径**仍然**进缓存（防「一律不缓存」的假绿）—— 在**删掉 catch 之后**再钉一次")
    void successIsStillCached() {
        jdbc.rows = List.of(row("Id", 1, "MerchantId", "M1", "GoodName", "G1"));

        Map<String, Object> first = service.listByMerchant("M1");

        assertThat(first).containsOnlyKeys("success", "list");
        assertThat(first.get("success")).isEqualTo(true);
        assertThat(first.get("list")).isNotNull();

        // 同键第二次：不得再查库（若把「删 catch」误做成「关掉缓存」，这句会红）
        assertThat(service.listByMerchant("M1")).isSameAs(first);
        assertThat(jdbc.calls).isEqualTo(1);

        // 不同键仍要查（键真的参与了）
        service.listByMerchant("M2");
        assertThat(jdbc.calls).isEqualTo(2);
    }

    // ===================== 三、listHome 的 per-row 兜底：收窄后两头都要钉 =====================

    @Test
    @DisplayName("内层 0 行（商家在 login 无对应行）⇒ UserName 仍是空串，且整个结果**照常缓存**")
    void listHomeInnerZeroRowsStillYieldsEmptyUserName() {
        jdbc.rows = List.of(row("Id", 1, "MerchantId", "M1"));
        jdbc.userNameMissing = true; // login 里没有 id = 'M1' 的行

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) service.listHome().get("list");

        assertThat(service.listHome().get("success")).isEqualTo(true);
        assertThat(list).hasSize(1);
        // 收窄 catch 时最容易误伤的就是这条：它是**业务事实**（商家无对应行），不是失败。
        assertThat(list.get(0).get("UserName")).isEqualTo("");

        // 整个结果（含被降级的那一列）照常缓存 ⇒ 两个计数器都不再增长
        assertThat(jdbc.calls).as("主查询不得重跑").isEqualTo(1);
        assertThat(jdbc.innerCalls).as("per-row 查询不得重跑").isEqualTo(1);
    }

    @Test
    @DisplayName("★内层 >1 行（login.Id 无唯一约束 ⇒ schema 层可达）⇒ 上抛，不再静默降级成空串")
    void listHomeInnerManyRowsThrowsInsteadOfSilentlyDegrading() {
        jdbc.rows = List.of(row("Id", 1, "MerchantId", "M1"));
        jdbc.userNameRows = 2; // 两行 login 共用同一个 Id

        // 这是本笔**有意引入**的行为变更：改前被 catch (Exception) 吞成 "". 钉住它，
        // 免得将来有人把「静默降级」当作正确行为「修回去」。
        assertAll(
                () -> assertThatThrownBy(() -> service.listHome())
                        .isInstanceOf(IncorrectResultSizeDataAccessException.class)
                        .hasMessageContaining("expected 1")
                        .hasMessageContaining("actual 2"),
                () -> assertThat(jdbc.calls).isEqualTo(1),
                () -> assertThat(jdbc.innerCalls).isEqualTo(1),

                () -> assertThatThrownBy(() -> service.listHome())
                        .isInstanceOf(IncorrectResultSizeDataAccessException.class),
                () -> assertThat(jdbc.calls).as("数据异常不得固化到 TTL").isEqualTo(2),
                () -> assertThat(cache(HOME).get("all")).isNull());
    }

    // ===================== 工具 =====================

    private Cache cache(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).as("cacheManager 里应有 %s", name).isNotNull();
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
     * <p>⚠️ <b>三个重载都必须覆写</b>，因为三个被测方法走的不是同一条：
     * <ul>
     *   <li>{@code listHome} → {@code queryForList(String)}（空 varargs）+ 循环里的
     *       {@code queryForObject(String, Object[], Class)}；</li>
     *   <li>{@code listByMerchant} → {@code queryForList(String, Object...)}；</li>
     *   <li>{@code listCodes} → {@code queryForList(String, Class, Object...)}（单列，走列序号 1）。</li>
     * </ul>
     * 漏覆写任何一个，调用都会落到继承来的真实实现上 —— 它没有 DataSource，会抛
     * {@code IllegalStateException} 而不是我们想要的失败，把「测试红了」伪装成
     * 「测试红了且原因正确」（{@code GoodsPriceCacheTest} 的替身里记着同一条教训）。
     */
    static final class StubJdbcTemplate extends JdbcTemplate {

        /** 主查询次数（两个 queryForList 重载共用一个计数器）。 */
        volatile int calls;
        /** per-row 查询次数（listHome 的 N+1 循环）。 */
        volatile int innerCalls;
        volatile String lastSql;
        volatile Object[] lastArgs;
        /** 下一次主查询返回的行。默认空 = 「查无结果」。 */
        volatile List<Map<String, Object>> rows = new ArrayList<>();
        /** {@code listCodes} 的单列结果。 */
        volatile List<String> singleColumnRows = new ArrayList<>();
        /** 非 null 时优先抛出（模拟连不上库等技术异常）。 */
        volatile RuntimeException toThrow;

        /** 让 per-row 查询抛「0 行」（模拟该商家在 login 无对应行）。 */
        volatile boolean userNameMissing;
        /** &gt;1 时让 per-row 查询抛「结果过多」。 */
        volatile int userNameRows = 1;
        /** per-row 查询正常时返回的值。 */
        volatile String userName = "merchant-name";

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            // ⚠️ 这是**独立重载**（javap spring-jdbc 6.2.6 :32），不是 varargs 那条的语法糖 ——
            //    漏掉它，listHome 的 `queryForList(sql)` 会落到真实实现上，报
            //    `IllegalStateException: No DataSource set`（本类第一版就栽在这里，
            //    且伪装成「测试红了」，原因却是错的）。
            return record(sql, new Object[0]);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return record(sql, args);
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            record(sql, args);
            @SuppressWarnings("unchecked")
            List<T> out = (List<T>) new ArrayList<>(singleColumnRows);
            return out;
        }

        /** 三个重载共用的记账 + 抛异常（返回值只有前两个用得上）。 */
        private List<Map<String, Object>> record(String sql, Object[] args) {
            calls++;
            lastSql = sql;
            lastArgs = args;
            RuntimeException e = toThrow;
            if (e != null) {
                throw e;
            }
            return rows;
        }

        @Override
        public <T> T queryForObject(String sql, Object[] args, Class<T> requiredType) {
            innerCalls++;
            if (userNameMissing) {
                throw new EmptyResultDataAccessException(1);
            }
            if (userNameRows > 1) {
                throw new IncorrectResultSizeDataAccessException(1, userNameRows);
            }
            @SuppressWarnings("unchecked")
            T out = (T) userName;
            return out;
        }

        /**
         * 关掉 {@code JdbcTemplate} 继承来的「必须有 DataSource」前置校验。
         *
         * <p>它是给「忘了注入 DataSource」准备的，本类恰恰是<b>故意</b>不注入 —— 查询整个被
         * 三个覆写拦下，永远走不到真正需要 DataSource 的地方。不关掉它，作为 {@code @Bean}
         * 建出来时就会在 {@code afterPropertiesSet()} 上炸。
         */
        @Override
        public void afterPropertiesSet() {
            // 故意留空，理由见 javadoc
        }

        void reset() {
            calls = 0;
            innerCalls = 0;
            lastSql = null;
            lastArgs = null;
            rows = new ArrayList<>();
            singleColumnRows = new ArrayList<>();
            toThrow = null;
            userNameMissing = false;
            userNameRows = 1;
            userName = "merchant-name";
        }
    }
}
