package com.minke.mallmanagement.goods.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 块 3（2026-10-10）**提审制**的状态机与 CAS 语义 —— 真库确定性用例。
 *
 * <h2>与 {@code GoodsWriteTxAtomicityTest} 的分工（两类别互相替代）</h2>
 * <ul>
 *   <li>{@code GoodsWriteTxAtomicityTest}：<b>事务原子性</b>（第二步失败 ⇒ 整笔回滚）。
 *       它管的是「写了一半会不会留下半成品」。</li>
 *   <li>本类：<b>状态机与 CAS</b>（提审写哪张表、通过/驳回各改什么、重复审核会怎样）。
 *       它管的是「同一张提审单被处理两次会不会出现两条 goods 行」。</li>
 * </ul>
 * 两者共用的前置（提审一条 → 审核）写法相同，但断言的东西没有重叠。
 *
 * <h2>本类钉的四条</h2>
 * <ol>
 *   <li><b>提审只写 {@code goods_audit}</b>（不写 {@code goods}）—— 即便该三元组在 {@code goods}
 *       里**已经有行**也不动它。这是提审制的负向断言，与 {@code GoodsWriteTxAtomicityTest} 用例 1
 *       的「goods 0 行」互补：那边证「没写」，这边证「**不会误写/误改既有的那一行**」；</li>
 *   <li><b>CAS 幂等</b>：同一条提审单审两次 ⇒ 第二次 {@code outcome=already-handled}、
 *       {@code goods} 仍**只有 1 行**、{@code ReviewedBy} 保持第一次的值（不被覆盖）；</li>
 *   <li><b>驳回不碰 {@code goods}</b>：{@code Status=rejected} + {@code RejectReason} 落库；</li>
 *   <li><b>驳回必须给理由</b>：缺理由直接拒，**一条 SQL 都不发**（与块 2 同口径）。</li>
 * </ol>
 *
 * <h2>为什么必须挂 {@code A3_ENABLED}</h2>
 * 四条都要真 MySQL（要的正是「CAS 的 WHERE Status='pending' 真的只命中一次」这种库级语义，
 * 替身给不了）。CI 的 {@code backend} job 没有 MySQL ⇒ 不挂开关会变红 —— 与
 * {@code GoodsWriteTxAtomicityTest} / {@code GoodsTypeUniqueIndexTest} 同一条做法。
 *
 * <p>⚠️ 本类走的是「两次审核」这种**确定性**形态，不是多线程去撞。真并发下的 CAS 只在 A3 的
 * B 组场景里量 —— 在单测里再开线程池只会得到「又慢又不稳」的第二份证据。
 * 但两条路径的判据是同一个：{@code casApprove} 的 {@code WHERE Status='pending'} 只可能命中一次。
 *
 * <p>⚠️ Redis 不是前置：审核**通过**成功路径会调 {@code evictByMerchant}，而
 * {@code TwoLevelCache} 对 L2 故障是 fail-soft（{@code l2Evict} 捕获 {@code DataAccessException}
 * 后转降级指标）⇒ 本机没起 Redis 也能跑。
 */
@EnabledIfEnvironmentVariable(named = "A3_ENABLED", matches = "true")
@SpringBootTest
// ⚠️ 同 GoodsWriteTxAtomicityTest：不重定向上传目录，goodsAdd 会往真仓库的 static/img/ 写文件。
@TestPropertySource(properties = "app.upload-dir=target/audit-tx-test-img/")
class GoodsAuditTxAtomicityTest {

    /** schema 限定名照抄被测代码（TD-13：项目 SQL 硬编码 {@code mall_management_sql.}，不能省）。 */
    private static final String GOODS = "mall_management_sql.goods";
    private static final String PRODUCTSALES = "mall_management_sql.productsales";
    private static final String GOODS_AUDIT = "mall_management_sql.goods_audit";

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsAuditService goodsAuditService;

    /** 真 JdbcTemplate：造前置行、数行数 —— 两条路径都绕开被测代码。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String merchantId;
    private String productCode;
    private String goodName;

    @BeforeEach
    void freshKeys() {
        long n = System.nanoTime() % 100_000_000L;
        merchantId = "AU" + n;
        productCode = "AP" + n;
        goodName = "AN" + n;
        cleanup();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM " + GOODS + " WHERE MerchantId = ?", merchantId);
        jdbcTemplate.update("DELETE FROM " + PRODUCTSALES + " WHERE MerchantId = ?", merchantId);
        jdbcTemplate.update("DELETE FROM " + GOODS_AUDIT + " WHERE MerchantId = ?", merchantId);
    }

    // ==================== 用例 1：提审的负向断言 ====================

    /**
     * 先用裸 INSERT 造一行**既有的** {@code goods} 行（模拟「该商家此前已经有这件商品」），
     * 再走 {@code goodsAdd} 提审同三元组 ⇒ 既有那一行必须**一个字段都不变**。
     *
     * <p>这比「goods 表 0 行」更强：它排除的不只是「提审顺手写了新行」，还包括
     * 「提审顺手 UPDATE 了同名行」。判据用<b>行数与 GoodImg 双向</b>：数行数看有没有增删，
     * 看 {@code GoodImg} 看有没有被改写成提审时那张新图。
     */
    @Test
    @DisplayName("用例 1：提审既不新增也不改动既有 goods 行（提审制的负向断言）")
    void submitNeverTouchesExistingGoodsRow() {
        insertGoodsRow("http://localhost:8088/img/preexisting.jpg");
        insertProductsalesRow();
        assertThat(goodsRows()).isEqualTo(1);

        Map<String, Object> r = goodsService.goodsAdd(
                merchantId, goodName, 2.0, jpeg(), "电子用品", productCode, 0, null, null, null, null);

        assertThat(r).containsEntry("success", true).containsEntry("msg", "已提交审核");
        assertThat(auditRows()).isEqualTo(1);
        assertThat(goodsRows()).as("提审不许新增 goods 行").isEqualTo(1);
        assertThat(productsalesRows()).as("提审不许新增 productsales 行").isEqualTo(1);
        assertThat(goodsImgOf()).as("提审不许改写既有 goods 行").isEqualTo("http://localhost:8088/img/preexisting.jpg");
    }

    // ==================== 用例 2：通过（正向）====================

    @Test
    @DisplayName("用例 2：审核通过 ⇒ goods 新增 1 行、Status=approved、GoodsId 回填非空")
    void approveWritesGoodsRowAndBackfillsId() {
        long id = submitPending();

        Map<String, Object> r = goodsAuditService.audit("adminA", id, true, null);

        assertThat(r).containsEntry("success", true).containsEntry("outcome", "approved");
        assertThat(goodsRows()).isEqualTo(1);
        assertThat(productsalesRows()).isEqualTo(1);
        assertThat(auditStatus(id)).isEqualTo("approved");
        assertThat(goodsIdOfAudit(id)).as("通过后必须回填 goods.Id").isNotNull();
        assertThat(goodsIdOfAudit(id)).as("回填的必须是真存在的那一行")
                .isEqualTo(existingGoodsId());
    }

    // ==================== 用例 3：CAS 幂等 ====================

    /**
     * 同一条提审单审两次 —— 这是「两个管理员同时点了通过」的确定性形态。
     *
     * <p>判据三条，缺一不可：第二次必须是 {@code already-handled}（不是错误）；{@code goods}
     * 仍只 1 行（CAS 拦住了第二次写）；{@code ReviewedBy} 保持第一次的值（第二次连 UPDATE 都没发）。
     */
    @Test
    @DisplayName("用例 3：同一条单审两次 ⇒ 第二次 already-handled、goods 仍 1 行、ReviewedBy 不被覆盖")
    void secondApproveIsAlreadyHandled() {
        long id = submitPending();

        Map<String, Object> first = goodsAuditService.audit("adminA", id, true, null);
        assertThat(first).containsEntry("outcome", "approved");

        Map<String, Object> second = goodsAuditService.audit("adminB", id, true, null);

        assertThat(second).containsEntry("success", true).containsEntry("outcome", "already-handled");
        assertThat(goodsRows()).as("第二次不许再写一行 goods").isEqualTo(1);
        assertThat(productsalesRows()).isEqualTo(1);
        assertThat(reviewedBy(id)).as("CAS 落空 ⇒ 第一次的审核者不被覆盖").isEqualTo("adminA");
    }

    // ==================== 用例 4 / 5：驳回 ====================

    @Test
    @DisplayName("用例 4：驳回 ⇒ Status=rejected + 理由落库 + goods 零新行")
    void rejectWritesReasonAndLeavesGoodsUntouched() {
        long id = submitPending();

        Map<String, Object> r = goodsAuditService.audit("adminA", id, false, "图片与实物不符");

        assertThat(r).containsEntry("success", true).containsEntry("outcome", "rejected");
        assertThat(auditStatus(id)).isEqualTo("rejected");
        assertThat(rejectReason(id)).isEqualTo("图片与实物不符");
        assertThat(goodsRows()).as("驳回不许写 goods").isZero();
        assertThat(productsalesRows()).isZero();
        assertThat(goodsIdOfAudit(id)).as("驳回不该回填 GoodsId").isNull();
    }

    /**
     * 驳回缺理由 ⇒ 直接拒，且**一条 SQL 都不发**（连 {@code selectById} 都不该跑）：
     * 校验在「取 before 快照」之前。判据用「提审单状态仍是 pending」+「无 GoodsId」两侧夹住 ——
     * 若校验被挪到快照之后、CAS 之前，状态会变（红）；若被挪到 CAS 之后，会多写一行 goods（红）。
     */
    @Test
    @DisplayName("用例 5：驳回缺理由 ⇒ success:false，提审单纹丝不动（仍是 pending）")
    void rejectWithoutReasonIsRefusedBeforeTouchingDb() {
        long id = submitPending();

        Map<String, Object> r = goodsAuditService.audit("adminA", id, false, "   ");   // 空白 = 缺失

        assertThat(r).containsEntry("success", false);
        assertThat(r.get("msg").toString()).contains("理由");
        assertThat(auditStatus(id)).isEqualTo("pending");
        assertThat(reviewedBy(id)).as("拒绝在写之前，不该留下审核者").isNull();
        assertThat(goodsRows()).isZero();
    }

    // ==================== 夹具 ====================

    private static MultipartFile jpeg() {
        return new MockMultipartFile("file", "au-1.jpg", "image/jpeg", "IMG".getBytes());
    }

    /** 走生产路径造一条待审单，返回它的 {@code goods_audit.Id}。 */
    private long submitPending() {
        Map<String, Object> r = goodsService.goodsAdd(
                merchantId, goodName, 1.0, jpeg(), "电子用品", productCode, 0, null, null, null, null);
        assertThat(r).containsEntry("success", true);
        Long id = jdbcTemplate.queryForObject(
                "SELECT Id FROM " + GOODS_AUDIT + " WHERE MerchantId = ? AND ProductCode = ? "
                        + "ORDER BY Id DESC LIMIT 1",
                Long.class, merchantId, productCode);
        assertThat(id).as("提审后必须能在 goods_audit 里查到那一行").isNotNull();
        return id;
    }

    /** 裸造一行既有 goods —— 用例 1 用它来造「提审前就已经有这件商品」的状态。 */
    private void insertGoodsRow(String img) {
        jdbcTemplate.update("INSERT INTO " + GOODS
                + " (MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                merchantId, goodName, 1.0, img, "电子用品", productCode, 0);
    }

    /** 配 {@link #insertGoodsRow} 用：让「既有的那件商品」是完整的一条（两表都有行）。 */
    private void insertProductsalesRow() {
        jdbcTemplate.update("INSERT INTO " + PRODUCTSALES
                + " (MerchantId, GoodName, ProductCode, OrderVolume) VALUES (?, ?, ?, ?)",
                merchantId, goodName, productCode, 0);
    }

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

    private int auditRows() {
        return jdbcTemplate.queryForList(
                "SELECT Id FROM " + GOODS_AUDIT + " WHERE MerchantId = ?", merchantId).size();
    }

    private String goodsImgOf() {
        return jdbcTemplate.queryForObject(
                "SELECT GoodImg FROM " + GOODS + " WHERE MerchantId = ? AND ProductCode = ?",
                String.class, merchantId, productCode);
    }

    private Long existingGoodsId() {
        return jdbcTemplate.queryForObject(
                "SELECT Id FROM " + GOODS + " WHERE MerchantId = ? AND ProductCode = ?",
                Long.class, merchantId, productCode);
    }

    private String auditStatus(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT Status FROM " + GOODS_AUDIT + " WHERE Id = ?", String.class, id);
    }

    private String rejectReason(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT RejectReason FROM " + GOODS_AUDIT + " WHERE Id = ?", String.class, id);
    }

    private String reviewedBy(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT ReviewedBy FROM " + GOODS_AUDIT + " WHERE Id = ?", String.class, id);
    }

    private Long goodsIdOfAudit(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT GoodsId FROM " + GOODS_AUDIT + " WHERE Id = ?", Long.class, id);
    }
}
