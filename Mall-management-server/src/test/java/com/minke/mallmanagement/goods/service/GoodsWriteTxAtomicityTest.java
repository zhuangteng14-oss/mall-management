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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * <p>⚠️ <b>C3 块 3（2026-10-10）提审制把这条判据挪了位置</b>：{@code goodsAdd} 不再写
 * {@code goods} / {@code productsales}，只写一条 {@code goods_audit(pending)} ⇒ 「两张表同一事务」
 * 的那对写口<b>整体搬到了 {@code GoodsAuditService.audit} 的通过路径</b>（CAS 改状态 + 写 goods
 * + 写 productsales + 回填 GoodsId，同一个 {@code @Transactional}）。
 * <b>本类用例 1/2/3 因此按新语义重写、判据原样平移</b>：阳性对照改成「审核通过 ⇒ 两表各 1 行」，
 * 回滚断言改成「通过路径第二步失败 ⇒ goods 行回滚、提审单退回 pending」。
 * {@code goodsDe} / {@code goodsUp} 未受提审制影响，用例 4/5 不动。
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
 * 真实失败向量另立一条用例（见 {@link #auditRollsBackOnRealColumnTooLong()})，并标注其前提。
 *
 * <p>⚠️ <b>Spy 的桩只打一处</b>：只令 {@code insertProductsales} 抛，
 * <b>其余 DAO 调用（尤其 {@code goods} 的 INSERT）一律走真实现、真落库</b>。
 * 若连 {@code insertGoods} 也被桩掉，回滚断言就成了一句空话。
 * ⚠️ 提审制让这条桩的作用域更干净：{@code goodsAdd} 现在<b>压根不调</b> {@code insertProductsales}，
 * 故「造待审单」那一步不会被打桩误伤，只有审核通过路径会撞上它。
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
    private static final String GOODS_AUDIT = "mall_management_sql.goods_audit";

    @Autowired
    private GoodsService goodsService;

    /** 提审制的审核侧 —— 用例 1b/2/3 的写口（提审制把两表写口搬到了这里）。 */
    @Autowired
    private GoodsAuditService goodsAuditService;

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
        jdbcTemplate.update("DELETE FROM " + GOODS_AUDIT + " WHERE MerchantId = ?", merchantId);
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

    // ==================== 用例 1 / 1b / 2 / 3：goodsAdd（提审）+ 审核通过路径 ====================

    /**
     * <b>阳性对照（提审侧）</b>：{@code goodsAdd} 不打桩 ⇒ {@code goods_audit} 恰 1 行、且是 pending；
     * <b>{@code goods} / {@code productsales} 各 0 行</b>。
     *
     * <p>后两条断言正是<b>提审制本身的正面证据</b>：「买家看不到待审商品」不是因为读路径把脏行
     * 过滤掉了，而是因为<b>压根没写进 goods 表</b>（验收项 3）。
     */
    @Test
    @DisplayName("用例 1：goodsAdd 不打桩 ⇒ goods_audit 1 行(pending)、goods/productsales 均 0 行（提审制）")
    void goodsAddWritesOnlyPendingAuditRow() {
        Map<String, Object> r = goodsService.goodsAdd(
                merchantId, goodName, 1.0, jpeg(), "电子用品", productCode, 0, null, null, null, null);

        assertThat(r).containsEntry("success", true).containsEntry("msg", "已提交审核");
        assertThat(auditRows()).isEqualTo(1);
        assertThat(auditStatuses()).containsExactly("pending");
        assertThat(goodsRows()).isZero();
        assertThat(productsalesRows()).isZero();
    }

    /**
     * <b>阳性对照（审核侧）</b>：提审一条 → 审核通过不打桩 ⇒ 两表各 1 行、提审单转 approved、回填 GoodsId。
     *
     * <p>它证明的是「在这套环境里，Spy 之下的真 DAO 真的把行写进了真库」—— 用例 2/3 里那个
     * {@code == 0} 才有意义（否则可以因为「写根本没生效」而假绿）。
     */
    @Test
    @DisplayName("用例 1b：审核通过不打桩 ⇒ goods 与 productsales 各 1 行、提审单 approved（阳性对照）")
    void auditApprovedWritesBothRowsWhenNothingFails() {
        long id = submitPending(goodName);

        Map<String, Object> r = goodsAuditService.audit("adminUT", id, true, null);

        assertThat(r).containsEntry("success", true).containsEntry("outcome", "approved");
        assertThat(goodsRows()).isEqualTo(1);
        assertThat(productsalesRows()).isEqualTo(1);
        assertThat(auditStatuses()).containsExactly("approved");
    }

    /**
     * <b>主判据（提审制下平移过来）</b>：审核通过路径第二步（{@code insertProductsales}）失败
     * ⇒ 第一步的 {@code goods} 行必须回滚，<b>且 CAS 改的状态一起退回 pending</b>。
     *
     * <p>三条断言各有分工，缺一不可：
     * <ol>
     *   <li>异常真的抛出（{@code audit} 不吞——它是事务边界，吞了就失去回滚信号）；</li>
     *   <li>{@code verify(times(1)).insertGoods(...)} —— <b>第一步真的执行过</b>。
     *       没有它，{@code goodsRows()==0} 也能被「第一步压根没跑」解释；</li>
     *   <li>{@code goodsRows()==0} + 提审单仍 {@code pending} —— 执行过、且整笔事务被回滚
     *       （不留「approved 但没商品」的半完成态，这是块 3 把三件事放进同一事务的理由）。</li>
     * </ol>
     * 配上用例 1b 的阳性对照，「写不进去」这一解释也被排除 ⇒ 只剩「写进去又回滚了」。
     */
    @Test
    @DisplayName("用例 2：审核通过第二步 INSERT 失败 ⇒ goods 行回滚、提审单退回 pending")
    void auditRollsBackGoodsWhenProductsalesFails() {
        long id = submitPending(goodName);

        doThrow(new DataIntegrityViolationException("用例注入：强制第二步失败"))
                .when(goodsDao).insertProductsales(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> goodsAuditService.audit("adminUT", id, true, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(goodsDao, times(1)).insertGoods(any());   // 第一步确实跑过
        assertThat(goodsRows()).isZero();                // 且其效果已回滚
        assertThat(productsalesRows()).isZero();
        assertThat(auditStatuses()).containsExactly("pending");   // CAS 也一起退回
    }

    /**
     * <b>真实失败向量</b>（无桩）：{@code goods.GoodName char(40)} 能装下、{@code productsales.GoodName
     * char(20)} 装不下 ⇒ 第二步报 <b>Error 1406</b>（{@code three-layers.md §G} 记「实测，用户可见失败」）。
     *
     * <p>提审制下的路径：27 字符名在 {@code goods_audit.GoodName varchar(40)} 里存得下 ⇒ 提审成功；
     * 审核通过重建 goods 行时，{@code goods.GoodName char(40)} 过、{@code productsales.GoodName char(20)}
     * 不过 ⇒ 第二步失败 ⇒ 整笔回滚。
     *
     * <p>⚠️ <b>前提</b>：依赖 MySQL 严格模式。非严格模式下超长会被静默截断、第二步反而成功 ⇒
     * 本用例<b>假绿</b>。故它是<b>加分项</b>，主判据是用例 2（确定性）。
     */
    @Test
    @DisplayName("用例 3：真实超长名（>20 字符）触发 productsales 1406 ⇒ 同样回滚")
    void auditRollsBackOnRealColumnTooLong() {
        String tooLong = "GN" + "x".repeat(25);   // 27 字符：goods char(40) 过、productsales char(20) 不过
        long id = submitPending(tooLong);

        assertThatThrownBy(() -> goodsAuditService.audit("adminUT", id, true, null))
                .isInstanceOf(Exception.class);

        assertThat(goodsRows()).isZero();
        assertThat(productsalesRows()).isZero();
        assertThat(auditStatuses()).containsExactly("pending");
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

    /**
     * 走 {@code goodsAdd} 造一条待审单，返回它在 {@code goods_audit} 里的 {@code Id}。
     * 用例 1b/2/3 的前置都走这里 —— 让「待审单从哪来」与生产路径同源，而不是裸 INSERT。
     */
    private long submitPending(String name) {
        Map<String, Object> r = goodsService.goodsAdd(
                merchantId, name, 1.0, jpeg(), "电子用品", productCode, 0, null, null, null, null);
        assertThat(r).containsEntry("success", true);
        Long id = jdbcTemplate.queryForObject(
                "SELECT Id FROM " + GOODS_AUDIT + " WHERE MerchantId = ? AND ProductCode = ? "
                        + "ORDER BY Id DESC LIMIT 1",
                Long.class, merchantId, productCode);
        assertThat(id).as("提审后必须能在 goods_audit 里查到那一行").isNotNull();
        return id;
    }

    /** 本商家在 {@code goods_audit} 里的行数（用例 1 的提审侧阳性对照）。 */
    private int auditRows() {
        return jdbcTemplate.queryForList(
                "SELECT Id FROM " + GOODS_AUDIT + " WHERE MerchantId = ?", merchantId).size();
    }

    /** 本商家最近若干条提审单的状态（用例 1/2/3 判 CAS 是否真的回滚 / 生效）。 */
    private java.util.List<String> auditStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT Status FROM " + GOODS_AUDIT + " WHERE MerchantId = ? ORDER BY Id",
                String.class, merchantId);
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
