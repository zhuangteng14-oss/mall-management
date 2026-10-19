package com.minke.mallmanagement.order.refund.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.refund.dao.OrderRefundDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批四第二批 commit 4：售后六态机的服务层判据。
 *
 * <h2>【钉的是什么】</h2>
 * <ul>
 *   <li><b>转移条件</b>：0/2 → 1、1 → 2/3/5、3 → 4/5；次数上限 2；越权 / 未收货 / 非本人拒绝。</li>
 *   <li><b>入账与回补只在 CAS 命中后</b>：这是「重复点击不重复退款」的唯一防线 ——
 *       只看返回值时，把 CAS 落空也入账的错误实现照样绿；本类用
 *       {@code verify(..., never())} 钉住「落空 ⇒ 不碰余额 / 不碰库存」。</li>
 *   <li><b>回补按明细逐条</b>，且先读 {@code StockDeducted}：该单没扣过库存就不回补。</li>
 * </ul>
 *
 * <p>⚠️ 本类不证明 SQL 在真库里只命中一行 —— 那是真库 / A3 的职责；本类证明服务层
 * 把哪些条件交给了 CAS、以及 CAS 落空后有没有继续动账。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderRefundStateMachineTest {

    @Mock private OrderRefundDao refundDao;
    @Mock private OrderDao orderDao;
    @Mock private GoodsDao goodsDao;

    private OrderRefundService service;

    @BeforeEach
    void setUp() {
        service = new OrderRefundService(refundDao, orderDao, goodsDao);
    }

    // ==================== 申请 ====================

    @Test
    @DisplayName("首次申请：CAS 命中 ⇒ RefundStatus=1 / count+1，回 applied")
    void applyFirstTime() {
        when(refundDao.apply("O1", "C1", "质量问题")).thenReturn(1);
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 1, 1, 99.0, 1));

        Map<String, Object> result = service.apply("C1", "O1", "质量问题");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("applied");
        assertThat(result.get("refundStatus")).isEqualTo(1);
        assertThat(result.get("retryCount")).isEqualTo(1);
        verify(refundDao).apply("O1", "C1", "质量问题");
    }

    @Test
    @DisplayName("重申：商家已拒（2）且次数=1 ⇒ CAS 仍可命中，次数变 2")
    void reapplyAfterMerchantReject() {
        when(refundDao.apply("O1", "C1", "还是不行")).thenReturn(1);
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 2, 2, 99.0, 1));

        Map<String, Object> result = service.apply("C1", "O1", "还是不行");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("retryCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("未收货 ⇒ CAS 落空，文案说「未收货」，不静默成通用失败")
    void applyRejectedWhenNotReceived() {
        when(refundDao.apply("O1", "C1", "坏了")).thenReturn(0);
        Map<String, Object> notReceived = order("O1", "C1", "M1", 0, 0, 99.0, 0);
        notReceived.put("ReceiveIf", 0);   // ⚠️ 夹具默认是已收货；这一条专门构造「未收货」前提
        when(refundDao.selectRefundOrder("O1")).thenReturn(notReceived);

        Map<String, Object> result = service.apply("C1", "O1", "坏了");

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("msg"))).contains("未收货");
    }

    @Test
    @DisplayName("非本人 ⇒ CAS 落空，文案「无权操作该订单」")
    void applyRejectedForOtherCustomer() {
        when(refundDao.apply("O1", "C1", "坏了")).thenReturn(0);
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C9", "M1", 0, 0, 99.0, 1));

        Map<String, Object> result = service.apply("C1", "O1", "坏了");

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("msg"))).contains("无权");
    }

    @Test
    @DisplayName("次数已用尽（2 次，状态 2）⇒ 明确回「上限 2 次」")
    void applyRejectedWhenRetryCapReached() {
        when(refundDao.apply("O1", "C1", "再来一次")).thenReturn(0);
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 2, 2, 99.0, 1));

        Map<String, Object> result = service.apply("C1", "O1", "再来一次");

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("msg"))).contains("上限");
    }

    @Test
    @DisplayName("申请原因必填；缺参时不碰 DAO")
    void applyRequiresReason() {
        Map<String, Object> result = service.apply("C1", "O1", "  ");

        assertThat(result.get("success")).isEqualTo(false);
        verifyNoInteractions(refundDao);
    }

    // ==================== 商家：同意 → 5 ====================

    @Test
    @DisplayName("商家同意：CAS 1→5 命中 ⇒ 入余额 + 按明细回补 + StockDeducted 置 0")
    void merchantApproveCreditsAndRestores() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 1, 1, 99.0, 1));
        when(refundDao.merchantApprove("O1", "M1", 1)).thenReturn(1);
        when(orderDao.selectStockDeducted("O1")).thenReturn(1);
        when(orderDao.selectItemStocksByOrder("O1")).thenReturn(List.of(
                item("SKU-1", 2), item("SKU-2", 1)));
        when(refundDao.creditBalance("C1", 99.0)).thenReturn(1);

        Map<String, Object> result = service.merchantHandle("M1", "O1", true, "同意");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("refunded");
        verify(refundDao).creditBalance("C1", 99.0);
        verify(goodsDao).releaseStock("SKU-1", 2);
        verify(goodsDao).releaseStock("SKU-2", 1);
        verify(refundDao).markStockRestored("O1");
    }

    @Test
    @DisplayName("商家同意但 CAS 落空（已被处理）⇒ 不入账、不回补")
    void merchantApproveCasMissDoesNotCredit() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 1, 1, 99.0, 1));
        when(refundDao.merchantApprove("O1", "M1", 1)).thenReturn(0);

        Map<String, Object> result = service.merchantHandle("M1", "O1", true, null);

        assertThat(result.get("success")).isEqualTo(false);
        verify(refundDao, never()).creditBalance(anyString(), anyDouble());
        verify(goodsDao, never()).releaseStock(anyString(), anyInt());
        verify(refundDao, never()).markStockRestored(anyString());
    }

    @Test
    @DisplayName("该单没扣过库存（StockDeducted=0）⇒ 不回补；余额照退")
    void merchantApproveSkipsRestoreWhenStockNotDeducted() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 1, 1, 99.0, 0));
        when(refundDao.merchantApprove("O1", "M1", 1)).thenReturn(1);
        when(orderDao.selectStockDeducted("O1")).thenReturn(0);

        Map<String, Object> result = service.merchantHandle("M1", "O1", true, null);

        assertThat(result.get("success")).isEqualTo(true);
        verify(refundDao).creditBalance("C1", 99.0);
        verify(goodsDao, never()).releaseStock(anyString(), anyInt());
        verify(refundDao, never()).markStockRestored(anyString());
    }

    @Test
    @DisplayName("别的商家处理 ⇒ 拒绝，且一个字都不写")
    void merchantCannotHandleOthersOrder() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M9", 1, 1, 99.0, 1));

        Map<String, Object> result = service.merchantHandle("M1", "O1", true, null);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("msg"))).contains("无权");
        verify(refundDao, never()).merchantApprove(anyString(), anyString(), anyInt());
        verify(refundDao, never()).creditBalance(anyString(), anyDouble());
    }

    // ==================== 商家：拒绝 → 2 / 3 ====================

    @Test
    @DisplayName("第 1 次拒绝 ⇒ 1→2（用户可重申），不动账")
    void firstRejectGoesTo2() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 1, 1, 99.0, 1));
        when(refundDao.merchantReject("O1", "M1", 1, 2)).thenReturn(1);

        Map<String, Object> result = service.merchantHandle("M1", "O1", false, "不影响二次销售");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("rejected");
        verify(refundDao).merchantReject("O1", "M1", 1, 2);
        verify(refundDao, never()).creditBalance(anyString(), anyDouble());
        verify(goodsDao, never()).releaseStock(anyString(), anyInt());
    }

    @Test
    @DisplayName("第 2 次拒绝 ⇒ 1→3（强制升级平台），不动账")
    void secondRejectEscalates() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 1, 2, 99.0, 1));
        when(refundDao.merchantReject("O1", "M1", 2, 3)).thenReturn(1);

        Map<String, Object> result = service.merchantHandle("M1", "O1", false, "还是拒");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("escalated");
        verify(refundDao).merchantReject("O1", "M1", 2, 3);
        verify(refundDao, never()).creditBalance(anyString(), anyDouble());
    }

    @Test
    @DisplayName("不在待商家审核（如 3）⇒ 拒绝处理")
    void merchantCannotHandleNonPending() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 3, 2, 99.0, 1));

        Map<String, Object> result = service.merchantHandle("M1", "O1", true, null);

        assertThat(result.get("success")).isEqualTo(false);
        verify(refundDao, never()).merchantApprove(anyString(), anyString(), anyInt());
        verify(refundDao, never()).merchantReject(anyString(), anyString(), anyInt(), anyInt());
    }

    // ==================== 平台：3 → 4 / 5 ====================

    @Test
    @DisplayName("平台同意（3→5）⇒ 入账 + 回补")
    void platformApproveCredits() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 3, 2, 88.5, 1));
        when(refundDao.platformHandle("O1", 2, 5, "admin")).thenReturn(1);
        when(orderDao.selectStockDeducted("O1")).thenReturn(1);
        when(orderDao.selectItemStocksByOrder("O1")).thenReturn(List.of(item("SKU-1", 1)));

        Map<String, Object> result = service.platformHandle("admin", "O1", true, "支持用户");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("refunded");
        verify(refundDao).creditBalance("C1", 88.5);
        verify(goodsDao).releaseStock("SKU-1", 1);
        verify(refundDao).markStockRestored("O1");
    }

    @Test
    @DisplayName("平台驳回（3→4，终态）⇒ 不动账、不回补")
    void platformRejectIsTerminal() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 3, 2, 88.5, 1));
        when(refundDao.platformHandle("O1", 2, 4, "admin")).thenReturn(1);

        Map<String, Object> result = service.platformHandle("admin", "O1", false, "不符合条件");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("refused");
        verify(refundDao, never()).creditBalance(anyString(), anyDouble());
        verify(goodsDao, never()).releaseStock(anyString(), anyInt());
    }

    @Test
    @DisplayName("平台处理但 CAS 落空 ⇒ 不入账（重复点击的防线）")
    void platformCasMissDoesNotCredit() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 3, 2, 88.5, 1));
        when(refundDao.platformHandle("O1", 2, 5, "admin")).thenReturn(0);

        Map<String, Object> result = service.platformHandle("admin", "O1", true, null);

        assertThat(result.get("success")).isEqualTo(false);
        verify(refundDao, never()).creditBalance(anyString(), anyDouble());
        verify(goodsDao, never()).releaseStock(anyString(), anyInt());
    }

    @Test
    @DisplayName("不在待平台审核（如 1）⇒ 拒绝处理")
    void platformCannotHandleNonEscalated() {
        when(refundDao.selectRefundOrder("O1")).thenReturn(order("O1", "C1", "M1", 1, 1, 88.5, 1));

        Map<String, Object> result = service.platformHandle("admin", "O1", true, null);

        assertThat(result.get("success")).isEqualTo(false);
        verify(refundDao, never()).platformHandle(anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("Approve 缺省 ⇒ 报缺参，绝不默认任何一侧")
    void approveIsMandatory() {
        Map<String, Object> result = service.merchantHandle("M1", "O1", null, null);

        assertThat(result.get("success")).isEqualTo(false);
        verifyNoInteractions(refundDao);
    }

    // ==================== 夹具 ====================

    private static Map<String, Object> order(String orderId, String customerId, String merchantId,
                                             int refundStatus, int retryCount, double price, int stockDeducted) {
        Map<String, Object> row = new HashMap<>();
        row.put("OrderId", orderId);
        row.put("CustomerId", customerId);
        row.put("MerchantId", merchantId);
        row.put("RefundStatus", refundStatus);
        row.put("RefundRetryCount", retryCount);
        row.put("Price", price);
        row.put("RefundAmount", price);
        row.put("ReceiveIf", 1);
        row.put("StockDeducted", stockDeducted);
        return row;
    }

    private static Map<String, Object> item(String code, int qty) {
        Map<String, Object> row = new HashMap<>();
        row.put("ProductCode", code);
        row.put("Quantity", qty);
        return row;
    }
}
