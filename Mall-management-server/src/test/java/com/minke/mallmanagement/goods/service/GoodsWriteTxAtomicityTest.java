package com.minke.mallmanagement.goods.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * C#2（{@code fix/goods-write-tx}）：{@code GoodsService} 三个写方法的<b>事务原子性</b>。
 *
 * <h2>本类钉的是什么</h2>
 * 三个写方法（{@code goodsAdd} / {@code goodsDe} / {@code goodsUp}）各写
 * {@code goods} + {@code productsales} <b>两张表</b>，改前无事务 ⇒ 第二条语句失败时
 * 第一条已提交，留下「半成品」。本类要证的是一条：<b>第二步失败 ⇒ 第一步真的回滚了</b>。
 *
 * <p>⚠️ <b>本类必须能区分「回滚了」与「压根没写」</b> —— 两者都表现为「表里 0 行」。
 * 故每一组都配一条<b>阳性对照</b>（同一个调用不打桩 ⇒ 行数 = 1），先证明在这套环境里
 * 真 DAO 真的把行写进了真库；再让第二步失败，断言那行<b>消失</b>。
 * 少了阳性对照，{@code == 0} 这条断言可以因为「写根本没生效」而变绿 —— 空转的假绿。
 *
 * <h2>为什么用 {@code @MockitoSpyBean} 而不是多线程撞</h2>
 * 要复现的「第二步失败」在真实系统里确实可达（{@code productsales.GoodName char(20)} vs
 * {@code goods.GoodName char(40)}，21–40 字符名会报 Error 1406），但那依赖 MySQL 严格模式，
 * 在不同环境里可能退化成静默截断 ⇒ 用例<b>假绿</b>。故主判据用 Spy 把失败<b>确定性</b>地摆出来，
 * 真实失败向量另立一条用例（见 {@link #goodsAddRollsBackOnRealColumnTooLong()})，并标注其前提。
 *
 * <p>⚠️ <b>Spy 的桩只打一处</b>：只令 {@code insertProductsales} 抛，
 * <b>其余 DAO 调用（尤其 {@code goods} 的 INSERT）一律走真实现、真落库</b>。
 * 若连 {@code insertGoods} 也被桩掉，回滚断言就成了一句空话。
 *
 * <h2>为什么必须挂 {@code A3_ENABLED}</h2>
 * 原子性要真 MySQL 才验得了（要的正是「提交/回滚真的发生」）。CI 的 {@code backend} job
 * 没有 MySQL，不挂开关会变红 —— 与 {@code MallManagementApplicationTests} /
 * {@code GoodsTypeUniqueIndexTest} 同一条做法。⚠️ 本类还额外要求 Redis 可达：
 * 三个写方法的唯一出口都调 {@code goodsCacheService.evictByMerchant}（{@code @CacheEvict}），
 * 那一步在 {@code try} 之外、抛异常会打断用例。CI 的 {@code concurrency} job 两者齐备。
 *
 * <h2>⚠️ 本类不能替代 A3 的验收判据</h2>
 * A3 <b>一个都不打</b>这三个端点（{@code three-layers.md §H.3}：{@code /goods} 8/9 未覆盖，
 * 正是 {@code goodsAdd} / {@code goodsDe} / {@code goodsUp}）⇒ 本笔的护栏只有单测，
 * 不存在「A3 会替我报警」这回事。
 */
@EnabledIfEnvironmentVariable(named = "A3_ENABLED", matches = "true")
@SpringBootTest
// ⚠️ 必须重定向上传目录：默认值是 ${user.dir}/src/main/resources/static/img/（真仓库目录），
//    goodsAdd/goodsUp 会往那里真写图片文件 ⇒ 跑一次测试就脏一次工作区。
//    target/ 已在 .gitignore 内、且 mvn clean 会清掉。
//    代价与 GoodsAddUploadFailSmokeTest 相同：本类因此独占一份 application context。
@TestPropertySource(properties = "app.upload-dir=target/tx-test-img/")
class GoodsWriteTxAtomicityTest {

    /** schema 限定名照抄被测代码（TD-13：项目 SQL 硬编码 {@code mall_management_sql.}，不能省）。 */
    private static final String GOODS = "mall_management_sql.goods";
    private static final String PRODUCTSALES = "mall_management_sql.productsales";

    @Autowired
    private GoodsService goodsService;

    /** 真 JdbcTemplate：用来造前置行、也用来数行数 —— 两条路径都绕开被测代码。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * ⚠️ 用 Spy 而非替身：本类<b>只打一处桩</b>（各用例的第二步失败），其余调用一律回落到
     * 真实现 ⇒ 真 JdbcTemplate ⇒ 真库。这正是「回滚断言」成立的前提。
     */
    @MockitoSpyBean
    private GoodsDao goodsDao;

    /** 每例一套独立的业务键，使各例互不干扰、且清理只删自己这几行。 */
    private String merchantId;
    private String productCode;
    private String goodName;

    @BeforeEach
    void freshKeys() {
        // 长度全部稳落在列宽内：MerchantId char(20) / ProductCode char(40) / GoodName char(20)
        // ⚠️ goodName 必须 ≤ 20 字符，否则会撞 productsales 的 char(20)（那正是下一条用例的题材）
        long n = System.nanoTime() % 100_000_000L;
        merchantId = "TX" + n;
        productCode = "PC" + n;
        goodName = "GN" + n;
        cleanup();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM " + GOODS + " WHERE MerchantId = ?", merchantId);
        jdbcTemplate.update("DELETE FROM " + PRODUCTSALES + " WHERE MerchantId = ?", merchantId);
    }

    // ==================== 用例 0：代理生效性（回答「注解是不是死注解」）====================

    /**
     * 与 {@code MallManagementApplicationTests} 那四条 Controller 断言<b>同形</b>，方向相反：
     * 那边钉「Controller 无代理」，这边钉「{@code GoodsService} <b>有</b>代理」。
     *
     * <p>它防的是一种静默失效：注解挂在方法上、但 bean 没被代理 ⇒ 事务根本没开，
     * 而所有行为用例在「刚好没失败」时照样全绿。这条断言让「注解生效」本身可复核。
     */
    @Test
    @DisplayName("用例 0：GoodsService 在完整上下文里确实被 AOP 代理（@Transactional 不是死注解）")
    void goodsServiceIsAopProxiedSoTransactionalIsLive() {
        assertThat(AopUtils.isAopProxy(goodsService))
                .as("GoodsService 没被代理 ⇒ 三个写方法上的 @Transactional 不会生效。"
                        + "若这条红了，先查是不是有人摘掉了注解，或把它挪去了同类内自调用的位置。")
                .isTrue();
    }

    // ==================== 用例 1 / 2：goodsAdd ====================

    /**
     * <b>阳性对照</b>：不打桩 ⇒ 两张表各留 1 行。
     * 它证明的是「在这套环境里，Spy 之下的真 DAO 真的把行写进了真库」。
     */
    @Test
    @DisplayName("用例 1：goodsAdd 不打桩 ⇒ goods 与 productsales 各 1 行（阳性对照，防下面的 0 行是空转）")
    void goodsAddWritesBothRowsWhenNothingFails() {
        Map<String, Object> r = goodsService.goodsAdd(
                merchantId, goodName, 1.0, jpeg(), "电子用品", productCode, 0);

        assertThat(r).containsEntry("success", true).containsEntry("msg", "商品上架成功！");
        assertThat(goodsRows()).isEqualTo(1);
        assertThat(productsalesRows()).isEqualTo(1);
    }

    /**
     * <b>本笔的主判据</b>：第二步（{@code insertProductsales}）失败 ⇒ 第一步的 {@code goods} 行必须回滚。
     *
     * <p>三条断言各有分工，缺一不可：
     * <ol>
     *   <li>{@code success:false} + 文案不变 —— 对外契约不动（不是 500）；</li>
     *   <li>{@code verify(times(1)).insertGoods(...)} —— <b>第一步真的执行过</b>。
     *       没有它，{@code goodsRows()==0} 也能被「第一步压根没跑」解释；</li>
     *   <li>{@code goodsRows()==0} —— 执行过、且被回滚。配上用例 1 的阳性对照，
     *       「写不进去」这一解释也被排除 ⇒ 只剩「写进去又回滚了」。</li>
     * </ol>
     *
     * <p><b>突变对照</b>：去掉 catch 里的 {@code markRollbackOnlyIfActive()}，本条在 (3) 处
     * 得 <b>1</b>（半成品）—— 这正是本笔要消掉的那件事，也是「只加 {@code @Transactional}」
     * 不够的证据。
     */
    @Test
    @DisplayName("用例 2：goodsAdd 第二步 INSERT 失败 ⇒ 第一步的 goods 行回滚，且响应契约不变")
    void goodsAddRollsBackGoodsWhenProductsalesFails() {
        doThrow(new DataIntegrityViolationException("用例注入：强制第二步失败"))
                .when(goodsDao).insertProductsales(anyString(), anyString(), anyString());

        Map<String, Object> r = goodsService.goodsAdd(
                merchantId, goodName, 1.0, jpeg(), "电子用品", productCode, 0);

        assertThat(r).containsEntry("success", false).containsEntry("msg", "商品上架失败");
        verify(goodsDao, times(1)).insertGoods(any());   // 第一步确实跑过
        assertThat(goodsRows()).isZero();                // 且其效果已回滚
        assertThat(productsalesRows()).isZero();
    }

    /**
     * <b>真实失败向量</b>（无桩）：{@code goods.GoodName char(40)} 能装下、{@code productsales.GoodName
     * char(20)} 装不下 ⇒ 第二步报 <b>Error 1406</b>（{@code three-layers.md §G} 记「实测，用户可见失败」）。
     *
     * <p>⚠️ <b>前提</b>：依赖 MySQL 严格模式。非严格模式下超长会被静默截断、第二步反而成功 ⇒
     * 本用例<b>假绿</b>。故它是<b>加分项</b>，主判据是用例 2（确定性）。
     */
    @Test
    @DisplayName("用例 3：真实超长名（>20 字符）触发 productsales 1406 ⇒ 同样回滚")
    void goodsAddRollsBackOnRealColumnTooLong() {
        String tooLong = "GN" + "x".repeat(25);   // 27 字符：goods char(40) 过、productsales char(20) 不过

        Map<String, Object> r = goodsService.goodsAdd(
                merchantId, tooLong, 1.0, jpeg(), "电子用品", productCode, 0);

        assertThat(r).containsEntry("success", false);
        assertThat(goodsRows()).isZero();
        assertThat(productsalesRows()).isZero();
    }

    // ==================== 用例 4 / 5：goodsDe、goodsUp ====================

    /**
     * 下架是两条 DELETE。第二步失败时，第一步删掉的 {@code goods} 行必须回来。
     * 做法：先真造两行 → 打桩让第二条 DELETE 抛 → 断言 {@code goods} 行<b>仍在</b>。
     */
    @Test
    @DisplayName("用例 4：goodsDe 第二条 DELETE 失败 ⇒ 第一条的删除回滚（goods 行仍在）")
    void goodsDeRollsBackFirstDelete() {
        insertGoodsRow();
        insertProductsalesRow();

        doThrow(new DataIntegrityViolationException("用例注入：强制第二条 DELETE 失败"))
                .when(goodsDao).deleteProductsales(anyString(), anyString(), anyString());

        Map<String, Object> r = goodsService.goodsDe(merchantId, goodName, productCode);

        assertThat(r).containsEntry("success", false).containsEntry("msg", "下架失败");
        assertThat(goodsRows()).isEqualTo(1);   // 回滚把删掉的那行还回来了
    }

    /**
     * 更新是两条 UPDATE。第二步失败时，第一步改掉的 {@code goods.GoodName} 必须退回旧值
     * —— 否则两表名字不一致（{@code goods} 是新名、{@code productsales} 是旧名）。
     *
     * <p>用「按旧名数行」判定存活，而不是把名字读回来做字符串比较：{@code char} 列有尾随空格
     * 填充，比较容易踩坑，数行更稳。
     */
    @Test
    @DisplayName("用例 5：goodsUp 第二条 UPDATE 失败 ⇒ 第一条的改名回滚（还是旧名）")
    void goodsUpRollsBackRename() {
        insertGoodsRow();
        insertProductsalesRow();
        String newName = "NEW" + merchantId;

        doThrow(new DataIntegrityViolationException("用例注入：强制第二条 UPDATE 失败"))
                .when(goodsDao).updateProductsalesGoodName(anyString(), anyString(), anyString());

        Map<String, Object> r = goodsService.goodsUp(merchantId, newName, 9.9, "电子用品", productCode, null);

        assertThat(r).containsEntry("success", false).containsEntry("msg", "商品更新失败");
        assertThat(rowsWithName(goodName)).isEqualTo(1);   // 旧名还在 ⇒ 改名被回滚
        assertThat(rowsWithName(newName)).isZero();        // 新名没留下
    }

    // ==================== 夹具 ====================

    private static MultipartFile jpeg() {
        return new MockMultipartFile("file", "tx-1.jpg", "image/jpeg", "IMG".getBytes());
    }

    private void insertGoodsRow() {
        jdbcTemplate.update("INSERT INTO " + GOODS
                + " (MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                merchantId, goodName, 1.0, "http://localhost:8088/img/tx-1.jpg", "电子用品", productCode, 0);
    }

    private void insertProductsalesRow() {
        jdbcTemplate.update("INSERT INTO " + PRODUCTSALES
                + " (MerchantId, GoodName, ProductCode, OrderVolume) VALUES (?, ?, ?, ?)",
                merchantId, goodName, productCode, 0);
    }

    /**
     * 数行数用 {@code queryForList().size()} 而不是 {@code queryForObject}：后者容易被
     * 被测代码的同文 SQL 撞上（{@code GoodsTypeUniqueIndexTest} 记过这个坑）。
     */
    private int goodsRows() {
        return jdbcTemplate.queryForList(
                "SELECT Id FROM " + GOODS + " WHERE MerchantId = ? AND ProductCode = ?",
                merchantId, productCode).size();
    }

    private int productsalesRows() {
        return jdbcTemplate.queryForList(
                "SELECT Id FROM " + PRODUCTSALES + " WHERE MerchantId = ? AND ProductCode = ?",
                merchantId, productCode).size();
    }

    private int rowsWithName(String name) {
        return jdbcTemplate.queryForList(
                "SELECT Id FROM " + GOODS + " WHERE MerchantId = ? AND ProductCode = ? AND GoodName = ?",
                merchantId, productCode, name).size();
    }
}
