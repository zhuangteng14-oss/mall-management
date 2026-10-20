package com.minke.mallmanagement.order.refund.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 售后（退款）域的数据出口 —— 批四第二批 commit 4。
 *
 * <h2>【为什么单开 DAO，而不是往 {@code OrderDao} 里加】</h2>
 * 退款是**旁路状态域**：它不参与 {@code PayIf / DeliveryIf / ReceiveIf} 三位状态机，
 * 也不许碰 {@code OrderStateMachine}。把 SQL 放进 `order/refund` 子包，
 * 让「哪些语句属于状态机、哪些只是旁路」在文件位置上就分得开。
 *
 * <h2>【所有流转都是 CAS —— 入账与回补只在 CAS 命中后执行】</h2>
 * 申请 / 商家同意 / 商家拒绝 / 平台处理四条语句都带**前态 + 申请次数**做条件；
 * 返回 0 行 = 竞态输了（别人已经处理过 / 状态已变）⇒ 调用方按「已被处理」回答，
 * <b>不许</b>再补一次入账或回补库存。这是退款重复入账的唯一防线。
 *
 * <h2>【库名】</h2>
 * 订单主表按 {@link com.minke.mallmanagement.order.dao.OrderDao} 的既有写法带
 * {@code mall_management_sql.} 前缀；{@code user} 表同（余额入账）。不改既有形态。
 */
@Repository
public class OrderRefundDao {

    private final JdbcClient jdbcClient;

    public OrderRefundDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：用户申请 ====================

    /**
     * 用户申请售后：{@code 0/2 → 1}，次数 +1（上限 2）。
     *
     * <p>谓词逐条：
     * <ul>
     *   <li>{@code CustomerId = ?} —— 只能申请自己的单；</li>
     *   <li>{@code ReceiveIf = 1} —— **已收货**才能申请（未收货的单走取消，不走售后）；</li>
     *   <li>{@code RefundStatus IN (0,2)} —— 首次申请或「商家已拒绝、可重申」；</li>
     *   <li>{@code RefundRetryCount < 2} —— 次数上限；第 2 次之后即使状态是 2 也不再收。</li>
     * </ul>
     *
     * @return 1 = 申请成功；0 = 任一谓词没命中（调用方再读一次订单给出明确文案）
     */
    public int apply(String orderId, String customerId, String reason) {
        String sql = "UPDATE mall_management_sql.`order` SET RefundStatus = 1, "
                + "RefundRetryCount = RefundRetryCount + 1, RefundReason = ?, "
                + "RefundAmount = Price, RefundApplyAt = NOW(3), "
                + "RefundHandledAt = NULL, RefundHandler = NULL "
                + "WHERE OrderId = ? AND CustomerId = ? AND ReceiveIf = 1 "
                + "AND RefundStatus IN (0, 2) AND RefundRetryCount < 2";
        return jdbcClient.sql(sql).params(reason, orderId, customerId).update();
    }

    /**
     * 商家同意：{@code 1 → 5}（带申请次数前态）。
     *
     * <p>{@code RefundRetryCount = ?} 是 CAS 的一部分：读到的次数必须仍是处理时的次数，
     * 否则说明期间又有一次申请 / 处理穿过去了 —— 回 0 行，调用方不重复入账。
     */
    public int merchantApprove(String orderId, String merchantId, int retryCount) {
        String sql = "UPDATE mall_management_sql.`order` SET RefundStatus = 5, "
                + "RefundHandledAt = NOW(3), RefundHandler = ? "
                + "WHERE OrderId = ? AND MerchantId = ? AND RefundStatus = 1 AND RefundRetryCount = ?";
        return jdbcClient.sql(sql).params(merchantId, orderId, merchantId, retryCount).update();
    }

    /**
     * 商家拒绝：第一次拒绝 {@code 1 → 2}（用户可重申）；第二次 {@code 1 → 3}（强制升级平台）。
     *
     * @param target {@code 2} 或 {@code 3} —— 由 Service 按次数决定，SQL 不自己 IF。
     */
    public int merchantReject(String orderId, String merchantId, int retryCount, int target) {
        String sql = "UPDATE mall_management_sql.`order` SET RefundStatus = ?, "
                + "RefundHandledAt = NOW(3), RefundHandler = ? "
                + "WHERE OrderId = ? AND MerchantId = ? AND RefundStatus = 1 AND RefundRetryCount = ?";
        return jdbcClient.sql(sql).params(target, merchantId, orderId, merchantId, retryCount).update();
    }

    /**
     * 平台处理：只对 {@code RefundStatus = 3}（商家二次拒绝后转入）生效。
     *
     * @param target {@code 5} = 同意退款 / {@code 4} = 驳回（终态）
     */
    public int platformHandle(String orderId, int retryCount, int target, String admin) {
        String sql = "UPDATE mall_management_sql.`order` SET RefundStatus = ?, "
                + "RefundHandledAt = NOW(3), RefundHandler = ? "
                + "WHERE OrderId = ? AND RefundStatus = 3 AND RefundRetryCount = ?";
        return jdbcClient.sql(sql).params(target, admin, orderId, retryCount).update();
    }

    // ==================== 写：入账 / 回补 ====================

    /** 退款入余额（明细在 {@code order.RefundAmount}，由申请那一刻的 {@code Price} 快照而来）。 */
    public int creditBalance(String customerId, double amount) {
        String sql = "UPDATE mall_management_sql.`user` SET Balance = Balance + ? WHERE customerid = ?";
        return jdbcClient.sql(sql).params(amount, customerId).update();
    }

    /**
     * 库存回补后把 {@code StockDeducted} 置 0 —— 幂等：已是 0 就回 0 行。
     * 调用方**先读** {@code OrderDao.selectStockDeducted}，只有 1 才回补并走这一句；
     * 这一句同时也是「同一单不会回补第二次」的落库标记。
     */
    public int markStockRestored(String orderId) {
        String sql = "UPDATE mall_management_sql.`order` SET StockDeducted = 0 "
                + "WHERE OrderId = ? AND StockDeducted = 1";
        return jdbcClient.sql(sql).param(orderId).update();
    }

    // ==================== 读：单条（CAS 失败后的文案判据 / 处理前快照） ====================

    /** 售后处理需要的订单列（不存在 ⇒ {@code null}）。**显式列清单**，不用 {@code SELECT *}。 */
    public Map<String, Object> selectRefundOrder(String orderId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ReceiveIf, StockDeducted, "
                + "RefundStatus, RefundRetryCount, RefundReason, RefundAmount, "
                + "RefundApplyAt, RefundHandledAt, RefundHandler "
                + "FROM mall_management_sql.`order` WHERE OrderId = ? LIMIT 1";
        return jdbcClient.sql(sql).param(orderId).query().listOfRows().stream().findFirst().orElse(null);
    }

    // ==================== 读：三端列表 ====================

    /** 用户的售后单（{@code RefundStatus <> 0}），最近申请的在前。 */
    public List<Map<String, Object>> selectMyRefunds(String customerId, int limit) {
        String sql = "SELECT OrderId, MerchantId, ProductInformation, Images, Price, Quantity, "
                + "SettlementDate, RefundStatus, RefundRetryCount, RefundReason, RefundAmount, "
                + "RefundApplyAt, RefundHandledAt, RefundHandler "
                + "FROM mall_management_sql.`order` "
                + "WHERE CustomerId = ? AND RefundStatus <> 0 "
                + "ORDER BY RefundApplyAt DESC, OrderId DESC LIMIT ?";
        return jdbcClient.sql(sql).params(customerId, limit).query().listOfRows();
    }

    /** 商家售后队列（{@code status} 为空 ⇒ 全部有售后记录的单）。 */
    public List<Map<String, Object>> selectMerchantRefunds(String merchantId, Integer status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT OrderId, CustomerId, ProductInformation, Images, Price, Quantity, "
                + "SettlementDate, RefundStatus, RefundRetryCount, RefundReason, RefundAmount, "
                + "RefundApplyAt, RefundHandledAt, RefundHandler "
                + "FROM mall_management_sql.`order` WHERE MerchantId = ? AND RefundStatus <> 0 ");
        if (status != null) {
            sql.append("AND RefundStatus = ? ");
        }
        sql.append("ORDER BY RefundApplyAt DESC, OrderId DESC LIMIT ?");
        if (status != null) {
            return jdbcClient.sql(sql.toString()).params(merchantId, status, limit).query().listOfRows();
        }
        return jdbcClient.sql(sql.toString()).params(merchantId, limit).query().listOfRows();
    }

    /**
     * 平台售后队列 —— **VIP 优先**：按 {@code user.MemberLevel DESC} 再按申请时刻升序。
     *
     * <p>⚠️ 会员等级取**对方客户行**（{@code u.customerid = o.CustomerId}），不是当前登录管理员；
     * 该单客户查不到行时 {@code LEFT JOIN} 仍保留这一单（等级按 0 排）。
     */
    public List<Map<String, Object>> selectPlatformRefunds(Integer status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT o.OrderId, o.CustomerId, o.MerchantId, o.ProductInformation, "
                + "o.Images, o.Price, o.Quantity, o.SettlementDate, o.RefundStatus, o.RefundRetryCount, "
                + "o.RefundReason, o.RefundAmount, o.RefundApplyAt, o.RefundHandledAt, o.RefundHandler, "
                + "u.MemberLevel, u.username AS CustomerName "
                + "FROM mall_management_sql.`order` o "
                + "LEFT JOIN mall_management_sql.`user` u ON u.customerid = o.CustomerId "
                + "WHERE o.RefundStatus <> 0 ");
        if (status != null) {
            sql.append("AND o.RefundStatus = ? ");
            sql.append("ORDER BY u.MemberLevel DESC, o.RefundApplyAt ASC, o.OrderId DESC LIMIT ?");
            return jdbcClient.sql(sql.toString()).params(status, limit).query().listOfRows();
        }
        sql.append("ORDER BY u.MemberLevel DESC, o.RefundApplyAt ASC, o.OrderId DESC LIMIT ?");
        return jdbcClient.sql(sql.toString()).param(limit).query().listOfRows();
    }
}
