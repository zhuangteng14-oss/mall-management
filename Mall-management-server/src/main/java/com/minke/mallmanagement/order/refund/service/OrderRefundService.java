package com.minke.mallmanagement.order.refund.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.refund.dao.OrderRefundDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 售后状态机（批四第二批 commit 4）—— 严格六态 + 申请次数上限 2。
 *
 * <pre>
 *   RefundStatus: 0 无 / 1 待商家 / 2 商家已拒（可重申）/ 3 待平台 / 4 平台已拒（终态）/ 5 已退款
 *
 *   0 --apply--> 1 (count=1)
 *   2 --apply--> 1 (count=2)
 *   1 --商家同意--> 5
 *   1 --商家拒绝(count=1)--> 2
 *   1 --商家拒绝(count=2)--> 3
 *   3 --平台同意--> 5
 *   3 --平台拒绝--> 4
 * </pre>
 *
 * <h2>【与既有订单状态机的关系】</h2>
 * 本类是**旁路状态域**：不读也不写 {@code PayIf / DeliveryIf / ReceiveIf}，
 * 不引 {@code OrderStateMachine}。退款结论不改订单的三位状态 —— 「已收货且已退款」
 * 在订单视角仍是 {@code ReceiveIf=1}，售后结论看 {@code RefundStatus}。
 *
 * <h2>【重复入账 / 重复回补的唯一防线 = CAS】</h2>
 * 每条流转语句都带前态与 {@code RefundRetryCount} 做条件；只有 CAS 命中（返回 1 行）
 * 才执行「入余额 + 回补库存」。CAS 落空一律按「该售后已被处理」回失败，
 * <b>不</b>再补写一次。这比「先查再写」可靠：并发两次点击时只有一次能命中。
 *
 * <h2>【{@code @Transactional} 的真实性】</h2>
 * 本类**不是**订单链上的 bean（{@code DistributedLockServiceTest.orderChainIsNotTransactional}
 * 的扫描列表只有 order + message 两条链里那几个类），故 @Transactional 合法且有必要：
 * CAS 与入账 / 回补必须同生共死。切片（{@code AbstractSmokeTest}）里没有事务管理器，
 * 注解是空操作；catch 里的 {@link #markRollbackOnlyIfActive()} 用同款守卫保证切片不炸
 * （与 {@code GoodsService} 同一手法）。
 */
@Service
public class OrderRefundService {

    private final OrderRefundDao refundDao;
    private final OrderDao orderDao;
    private final GoodsDao goodsDao;

    public OrderRefundService(OrderRefundDao refundDao, OrderDao orderDao, GoodsDao goodsDao) {
        this.refundDao = refundDao;
        this.orderDao = orderDao;
        this.goodsDao = goodsDao;
    }

    // ==================== 用户：申请 ====================

    /**
     * 用户申请售后。{@code RefundAmount} 由 SQL 取 {@code order.Price}（下单实付，
     * 会员价 / 券后的落库价），前端不传金额 —— 金额永远以库里的成交价为准。
     */
    @Transactional
    public Map<String, Object> apply(String customerId, String orderId, String reason) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        if (isBlank(orderId)) {
            return fail("缺少参数：OrderId");
        }
        if (isBlank(reason)) {
            return fail("请填写售后原因");
        }
        if (reason.trim().length() > 255) {
            return fail("售后原因过长（最多 255 字）");
        }
        try {
            int rows = refundDao.apply(orderId.trim(), customerId.trim(), reason.trim());
            if (rows > 0) {
                Map<String, Object> row = refundDao.selectRefundOrder(orderId.trim());
                Map<String, Object> result = ok("售后申请已提交，等待商家审核");
                result.put("outcome", "applied");
                result.put("orderId", orderId.trim());
                result.put("refundStatus", 1);
                if (row != null) {
                    result.put("merchantId", str(row, "MerchantId"));
                    result.put("customerId", str(row, "CustomerId"));
                    result.put("retryCount", intOf(row, "RefundRetryCount"));
                }
                return result;
            }
            return explainApplyFailure(customerId.trim(), orderId.trim());
        } catch (Exception e) {
            markRollbackOnlyIfActive();
            e.printStackTrace();
            return fail("售后申请失败：" + e.getMessage());
        }
    }

    /** CAS 落空后**再读一次**，把「为什么不行」说清楚（不静默成一句「失败」）。 */
    private Map<String, Object> explainApplyFailure(String customerId, String orderId) {
        Map<String, Object> row = refundDao.selectRefundOrder(orderId);
        if (row == null) {
            return fail("订单不存在");
        }
        if (!customerId.equals(str(row, "CustomerId"))) {
            return fail("无权操作该订单");
        }
        if (intOf(row, "ReceiveIf") != 1) {
            return fail("未收货的订单不能申请售后");
        }
        int status = intOf(row, "RefundStatus");
        int retry = intOf(row, "RefundRetryCount");
        switch (status) {
            case 1 -> {
                return fail("该订单的售后正在等待商家审核");
            }
            case 3 -> {
                return fail("该订单的售后已升级平台处理");
            }
            case 4 -> {
                return fail("平台已驳回该售后申请（终态）");
            }
            case 5 -> {
                return fail("该订单已退款");
            }
            default -> {
                // 落到这里只剩「次数用完、状态仍是 2」这一种
            }
        }
        if (retry >= 2) {
            return fail("售后申请次数已达上限（2 次）");
        }
        return fail("当前订单状态不允许申请售后");
    }

    // ==================== 商家：处理 ====================

    /**
     * 商家处理待审核的售后。
     *
     * <p>同意 ⇒ {@code 1→5} + 入余额 + 回补库存（三者同事务）。
     * 拒绝 ⇒ 第 1 次 {@code 1→2}（用户可重申）；第 2 次 {@code 1→3}（强制升级平台）。
     *
     * @param approve {@code true} = 同意退款，{@code false} = 拒绝
     */
    @Transactional
    public Map<String, Object> merchantHandle(String merchantId, String orderId, Boolean approve, String note) {
        if (isBlank(merchantId)) {
            return fail("缺少参数：MerchantId");
        }
        if (isBlank(orderId)) {
            return fail("缺少参数：OrderId");
        }
        if (approve == null) {
            return fail("缺少参数：Approve");
        }
        try {
            Map<String, Object> row = refundDao.selectRefundOrder(orderId.trim());
            if (row == null) {
                return fail("订单不存在");
            }
            if (!merchantId.trim().equals(str(row, "MerchantId"))) {
                return fail("无权处理该订单的售后");
            }
            if (intOf(row, "RefundStatus") != 1) {
                return fail("该售后不在待商家审核状态（可能已被处理）");
            }
            int retry = intOf(row, "RefundRetryCount");
            if (approve) {
                int rows = refundDao.merchantApprove(orderId.trim(), merchantId.trim(), retry);
                if (rows == 0) {
                    return fail("该售后已被处理，请刷新后重试");
                }
                settleRefund(row);
                Map<String, Object> result = ok("已同意退款，款项已退回用户余额");
                result.put("outcome", "refunded");
                putParties(result, row);
                return result;
            }
            int target = retry >= 2 ? 3 : 2;
            int rows = refundDao.merchantReject(orderId.trim(), merchantId.trim(), retry, target);
            if (rows == 0) {
                return fail("该售后已被处理，请刷新后重试");
            }
            Map<String, Object> result = ok(target == 3
                    ? "已拒绝；本单售后已升级平台处理"
                    : "已拒绝；用户可再次申请");
            result.put("outcome", target == 3 ? "escalated" : "rejected");
            putParties(result, row);
            if (!isBlank(note)) {
                result.put("note", note.trim());
            }
            return result;
        } catch (Exception e) {
            markRollbackOnlyIfActive();
            e.printStackTrace();
            return fail("售后处理失败：" + e.getMessage());
        }
    }

    // ==================== 平台：处理 ====================

    /** 平台处理已升级的售后：{@code 3→5}（同意，入余额 + 回补）或 {@code 3→4}（驳回，终态）。 */
    @Transactional
    public Map<String, Object> platformHandle(String admin, String orderId, Boolean approve, String note) {
        if (isBlank(orderId)) {
            return fail("缺少参数：OrderId");
        }
        if (approve == null) {
            return fail("缺少参数：Approve");
        }
        try {
            Map<String, Object> row = refundDao.selectRefundOrder(orderId.trim());
            if (row == null) {
                return fail("订单不存在");
            }
            if (intOf(row, "RefundStatus") != 3) {
                return fail("该售后不在待平台审核状态（可能已被处理）");
            }
            int retry = intOf(row, "RefundRetryCount");
            int target = approve ? 5 : 4;
            int rows = refundDao.platformHandle(orderId.trim(), retry, target, isBlank(admin) ? "admin" : admin.trim());
            if (rows == 0) {
                return fail("该售后已被处理，请刷新后重试");
            }
            if (approve) {
                settleRefund(row);
            }
            Map<String, Object> result = ok(approve ? "已同意退款，款项已退回用户余额" : "已驳回该售后申请（终态）");
            result.put("outcome", approve ? "refunded" : "refused");
            putParties(result, row);
            if (!isBlank(note)) {
                result.put("note", note.trim());
            }
            return result;
        } catch (Exception e) {
            markRollbackOnlyIfActive();
            e.printStackTrace();
            return fail("售后处理失败：" + e.getMessage());
        }
    }

    // ==================== 列表（纯读） ====================

    public Map<String, Object> myRefunds(String customerId, Integer limit) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        return listOf(refundDao.selectMyRefunds(customerId.trim(), clamp(limit)));
    }

    public Map<String, Object> merchantRefunds(String merchantId, Integer status, Integer limit) {
        if (isBlank(merchantId)) {
            return fail("缺少参数：MerchantId");
        }
        if (status != null && (status < 1 || status > 5)) {
            return fail("未知的售后状态：" + status);
        }
        return listOf(refundDao.selectMerchantRefunds(merchantId.trim(), status, clamp(limit)));
    }

    public Map<String, Object> platformRefunds(Integer status, Integer limit) {
        if (status != null && (status < 1 || status > 5)) {
            return fail("未知的售后状态：" + status);
        }
        return listOf(refundDao.selectPlatformRefunds(status, clamp(limit)));
    }

    // ==================== 私有：入账 + 回补 ====================

    /**
     * 退款最终态（{@code →5}）的收尾：入余额 + 回补库存。
     *
     * <p>⚠️ 只在 CAS 命中**之后**调用。两步都在同一事务里：入账成功而回补抛异常 ⇒
     * 整体回滚（余额与 {@code StockDeducted} 一起还原），不会留下「钱退了、库存没回」的半截状态。
     */
    private void settleRefund(Map<String, Object> row) {
        String orderId = str(row, "OrderId");
        String customerId = str(row, "CustomerId");
        double amount = doubleOf(row, "RefundAmount");
        if (amount <= 0d) {
            amount = doubleOf(row, "Price");
        }
        if (!isBlank(customerId) && amount > 0d) {
            refundDao.creditBalance(customerId, amount);
        }
        restoreStock(orderId);
    }

    /**
     * 回补本单**真扣过**的库存。判据与超时取消同款：{@code StockDeducted = 1} 才回补。
     * 逐条明细各加回一次；全部走完再把标记置 0（幂等）。
     */
    private void restoreStock(String orderId) {
        if (isBlank(orderId) || orderDao.selectStockDeducted(orderId) != 1) {
            return;
        }
        List<Map<String, Object>> items = orderDao.selectItemStocksByOrder(orderId);
        for (Map<String, Object> item : items) {
            String code = str(item, "ProductCode");
            int qty = intOf(item, "Quantity");
            if (!isBlank(code) && qty > 0) {
                goodsDao.releaseStock(code, qty);
            }
        }
        refundDao.markStockRestored(orderId);
    }

    // ==================== 私有：结果 / 取值 ====================

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", msg);
        return result;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    private static Map<String, Object> listOf(List<Map<String, Object>> rows) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", rows);
        result.put("total", rows.size());
        return result;
    }

    private static void putParties(Map<String, Object> result, Map<String, Object> row) {
        result.put("orderId", str(row, "OrderId"));
        result.put("customerId", str(row, "CustomerId"));
        result.put("merchantId", str(row, "MerchantId"));
        result.put("retryCount", intOf(row, "RefundRetryCount"));
        double amount = doubleOf(row, "RefundAmount");
        result.put("refundAmount", amount > 0d ? amount : doubleOf(row, "Price"));
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 200);
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static double doubleOf(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0d;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 见类注释「@Transactional 的真实性」：切片里没有事务 ⇒ 本方法是空操作。 */
    private static void markRollbackOnlyIfActive() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
