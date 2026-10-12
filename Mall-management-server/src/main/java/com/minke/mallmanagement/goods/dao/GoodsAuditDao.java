package com.minke.mallmanagement.goods.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code goods_audit} 表的内联 SQL 出口（C3 块 3 商品块）—— **提审单**的读写与审核 CAS。
 *
 * <h2>【本类为什么与 {@code GoodsDao} 分开，而不是并进它】</h2>
 * 这是**另一张表**、另一条生命周期（提审历史 append + 审核 UPDATE），与 {@code goods} 的
 * 增删改查没有共同谓词。并进 {@code GoodsDao} 只会让那个「8 条语句逐字搬来」的类注释失真。
 * 两条路径都以「表」为界，与 {@code MerchantApplicationDao} 独立于 {@code UserGovernanceDao} 同判据。
 *
 * <h2>【⚠️ 带 {@code mall_management_sql.} 前缀 —— 跟「本文件所属的域」】</h2>
 * 前缀口径见 {@code MerchantApplicationDao} 的类注释：判据是「本域既有写法」。
 * 本类在 {@code goods/} 域，该域既有 DAO（{@code GoodsDao} / {@code GoodsShelfDao}）**全部带前缀**
 * ⇒ 本类跟带。⚠️ 这与新域 {@code catalog/}（全仓此前无该包，故取 message 域那一支**不带**）
 * 是两个相反的结论，**不是**自相矛盾 —— 同一个判据（跟本域既有写法）的两个结果。
 *
 * <h2>【CAS 是这层的核心：审核幂等靠 {@code WHERE Status='pending'}】</h2>
 * {@link #casApprove} / {@link #casReject} 照 {@code MerchantApplicationDao} 的范式：
 * {@code >=1} 才算「我这次是第一次处理」，{@code 0} = 已被别的管理员处理过 ⇒ Service 回
 * 「该申请已被处理」，**不抛**。必须在库侧做（不能先 SELECT 再判断再 UPDATE）。
 */
@Repository
public class GoodsAuditDao {

    /**
     * 列表共用列清单（管理端待审列表 + 商家「我的提审」共用一份投影）。
     * ⚠️ 时间列经 {@code DATE_FORMAT} 归一成「yyyy-MM-dd HH:mm:ss」—— 与块 2 同口径
     * （让前端拿到的字符串稳定，不随 JDBC 时区映射变）。
     */
    private static final String COLUMNS =
            "Id, MerchantId, BatchId, GoodName, Price, OriginalPrice, GoodType, GoodTypeNew, TypeId, "
                    + "ProductCode, GoodImg, Detail, Spec, AfterSale, SkuSpec, "
                    + "PromiseGenuine, PromiseReturn7, PromiseFastRefund, PromiseFreightIns, "
                    + "Status, RejectReason, GoodsId, ReviewedBy, "
                    + "DATE_FORMAT(SubmittedAt, '%Y-%m-%d %H:%i:%s') AS SubmittedAt, "
                    + "DATE_FORMAT(ReviewedAt,  '%Y-%m-%d %H:%i:%s') AS ReviewedAt ";

    private final JdbcClient jdbcClient;

    public GoodsAuditDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：提交一条提审 ====================

    /**
     * 插入一条待审提审单。{@code Status} 固定 {@code 'pending'}、{@code SubmittedAt} 由 Java 侧给
     * —— 不靠列的 DEFAULT（读写同源，同块 2 的时间口径）。
     *
     * <p>⚠️ {@code GoodsId} 刻意不写（恒 NULL）—— 它由**审核通过**时的 {@link #casApprove}
     * 之后的回填写入，提审时商品还没进 {@code goods} 表。
     *
     * @return 受影响行数（正常恒为 1）
     */
    public int insert(String merchantId, String batchId, String goodName, Double price, Double originalPrice,
                      String goodType, Integer goodTypeNew, Integer typeId, String productCode, String goodImg,
                      String detail, String spec, String afterSale, String skuSpec, LocalDateTime submittedAt,
                      boolean promiseGenuine, boolean promiseReturn7, boolean promiseFastRefund, boolean promiseFreightIns) {
        String sql = "INSERT INTO mall_management_sql.goods_audit "
                + "(MerchantId, BatchId, GoodName, Price, OriginalPrice, GoodType, GoodTypeNew, TypeId, ProductCode, GoodImg, "
                + " Detail, Spec, AfterSale, SkuSpec, "
                + " PromiseGenuine, PromiseReturn7, PromiseFastRefund, PromiseFreightIns, Status, SubmittedAt) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)";
        return jdbcClient.sql(sql)
                // ⚠️ 绑参顺序 = SQL 里 `?` 的**出现顺序**（不是形参顺序）：`SubmittedAt` 的 `?` 在**最后**
                //    （`Status` 是字面量 'pending'、夹在 4 个承诺位与它之间）⇒ `submittedAt` 必须放在这一串的**末尾**。
                //    2026-10-11 探针实测：放在 `skuSpec` 之后 ⇒ `submittedAt` 被塞进 `PromiseGenuine`
                //    （`tinyint(1)`）⇒ `Data truncation: Out of range value for column 'PromiseGenuine'`，
                //    且其后整串错位。编译期看不出来。
                .params(merchantId, batchId, goodName, price, originalPrice, goodType, goodTypeNew, typeId, productCode, goodImg,
                        detail, spec, afterSale, skuSpec,
                        promiseGenuine, promiseReturn7, promiseFastRefund, promiseFreightIns, submittedAt)
                .update();
    }

    // ==================== 读：提审单 ====================

    /** 「我的提审」（按商家号，时间倒序）—— 商家端商品状态列用。 */
    public List<Map<String, Object>> selectByMerchant(String merchantId, int limit) {
        String sql = "SELECT " + COLUMNS + "FROM mall_management_sql.goods_audit "
                + "WHERE MerchantId = ? ORDER BY SubmittedAt DESC, Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(merchantId, limit).query().listOfRows();
    }

    /**
     * 按状态列出（管理端待审 / 全部）。{@code status} 为 {@code null} / 空 ⇒ 不筛。
     *
     * <p>⚠️ 先把 SQL 文本定稿、再按 {@code ?} 出现顺序绑参（同 {@code MerchantApplicationDao}
     * 的警告）—— 改本方法时成对改。
     */
    public List<Map<String, Object>> selectByStatus(String status, int limit) {
        boolean filtered = !isBlank(status);
        String sql = "SELECT " + COLUMNS + "FROM mall_management_sql.goods_audit "
                + (filtered ? "WHERE Status = ? " : "")
                + "ORDER BY SubmittedAt DESC, Id DESC LIMIT ?";
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        if (filtered) {
            spec = spec.param(status);
        }
        return spec.param(limit).query().listOfRows();
    }

    /** 与 {@link #selectByStatus} 同筛选的计数。 */
    public long countByStatus(String status) {
        String sql = isBlank(status)
                ? "SELECT COUNT(*) FROM mall_management_sql.goods_audit"
                : "SELECT COUNT(*) FROM mall_management_sql.goods_audit WHERE Status = ?";
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        if (!isBlank(status)) {
            spec = spec.param(status);
        }
        return spec.query(Long.class).single();
    }

    /**
     * 按主键取一条（审核前的快照读，也是审计 {@code before} 的来源）。查不到 ⇒ {@code null}。
     *
     * <p>⚠️ 这里要**全字段**（含 {@code GoodName} / {@code Price} / {@code GoodImg} / {@code TypeId}
     * …）—— 审核通过时要用它**重建**那一行 {@code goods}。
     */
    public Map<String, Object> selectById(long id) {
        String sql = "SELECT " + COLUMNS + "FROM mall_management_sql.goods_audit WHERE Id = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 写：审核（CAS 两条） ====================

    /**
     * 审核**通过**的 CAS。{@code WHERE Id = ? AND Status = 'pending'} 是幂等判据。
     *
     * @return 受影响行数：{@code 1} = 我这次拿下了；{@code 0} = 不存在或已被处理
     */
    public int casApprove(long id, String reviewedBy, LocalDateTime reviewedAt) {
        String sql = "UPDATE mall_management_sql.goods_audit "
                + "SET Status = 'approved', ReviewedBy = ?, ReviewedAt = ?, RejectReason = NULL "
                + "WHERE Id = ? AND Status = 'pending'";
        return jdbcClient.sql(sql).params(reviewedBy, reviewedAt, id).update();
    }

    /** 审核**驳回**的 CAS，形状同 {@link #casApprove}，多写一个 {@code RejectReason}。 */
    public int casReject(long id, String reviewedBy, LocalDateTime reviewedAt, String rejectReason) {
        String sql = "UPDATE mall_management_sql.goods_audit "
                + "SET Status = 'rejected', ReviewedBy = ?, ReviewedAt = ?, RejectReason = ? "
                + "WHERE Id = ? AND Status = 'pending'";
        return jdbcClient.sql(sql).params(reviewedBy, reviewedAt, rejectReason, id).update();
    }

    /**
     * 通过之后回填 {@code GoodsId} —— 与 {@link #casApprove} **同一事务**（由 Service 保证）。
     *
     * <p>⚠️ 谓词带 {@code AND Status='approved'}：只有 CAS 已成功的行才回填，防「先回填后 CAS」
     * 的乱序（本方法恒在 CAS 之后调用，这个条件是对调用顺序的结构性保险）。
     */
    public int fillGoodsId(long id, long goodsId) {
        String sql = "UPDATE mall_management_sql.goods_audit "
                + "SET GoodsId = ? WHERE Id = ? AND Status = 'approved'";
        return jdbcClient.sql(sql).params(goodsId, id).update();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
