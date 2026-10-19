package com.minke.mallmanagement.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.marketing.coupon.service.CouponService;
import com.minke.mallmanagement.marketing.seckill.dao.SeckillDao;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.dao.ShippingTrackDao;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.model.OrderTransitionRecorder;
import com.minke.mallmanagement.shared.MemberPricing;
import com.minke.mallmanagement.shared.lock.DistributedLockService;
import com.minke.mallmanagement.shared.lock.DistributedLockService.LockName;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员价（批四第二批 commit 3）—— 9 折公式、下单落库价、券叠加顺序、fail-open。
 *
 * <h2>为什么用 mock 直接打 {@code orderAdd}</h2>
 * 本笔要钉的三条都<b>不在响应形状里</b>：
 * <ol>
 *   <li>落库价：{@code order.Price} 必须是 9 折后的数 —— 判据是 {@code insertOrder} 收到的
 *       {@link OrderDao.OrderRow#price()}，不是响应；</li>
 *   <li>叠加顺序：券的基数必须是 <b>memberPrice</b> —— 判据是 {@code CouponService.redeem}
 *       第 4 个实参，不是最终 {@code Discount}；</li>
 *   <li>普通用户形状不变：{@code MemberPrice/MemberDiscount} 两个键<b>不出现</b>。</li>
 * </ol>
 * 用 {@code @SpringBootTest} 打这三条要先造商品、SKU、券包、库时钟，噪声远大于判据。
 *
 * <p>下单入参里的 {@code Items / ProductInformation} 传空串 ⇒ {@code resolveItems} 回空表 ⇒
 * 不触 {@code order_item} 与 {@code goods_sku}，把被测段收窄到「会员段 + 用券段」。
 */
@ExtendWith(MockitoExtension.class)
class MemberDiscountTest {

    @Mock private OrderDao orderDao;
    @Mock private DistributedLockService lockService;
    @Mock private OrderStateMachine orderStateMachine;
    @Mock private OrderTransitionRecorder orderTransitionRecorder;
    @Mock private ObjectProvider<UserStateLookup> userStateLookupProvider;
    @Mock private NotificationService notificationService;
    @Mock private ShippingTrackDao shippingTrackDao;
    @Mock private CouponService couponService;
    @Mock private GoodsDao goodsDao;
    @Mock private SeckillDao seckillDao;
    @Mock private StockMetrics stockMetrics;
    @Mock private ProtectionMetrics protectionMetrics;
    @Mock private com.minke.mallmanagement.wallet.service.WalletService walletService;
    @Mock private com.minke.mallmanagement.member.service.MemberService memberService;

    // ==================== 一、公式 ====================

    @Test
    @DisplayName("9 折公式：priceOf = round(price*90)/100，单价/角分都向下取整到分")
    void priceOfRoundsToCents() {
        assertThat(MemberPricing.RATE).isEqualTo(0.9d);
        assertThat(MemberPricing.RATE_TEXT).isEqualTo(9);
        assertThat(MemberPricing.priceOf(100d)).isEqualTo(90d);
        assertThat(MemberPricing.priceOf(99.99d)).isEqualTo(89.99d);   // 8999.1 → 8999
        assertThat(MemberPricing.priceOf(1.11d)).isEqualTo(1.00d);     // 99.9 → 100
        assertThat(MemberPricing.priceOf(0.01d)).isEqualTo(0.01d);     // 0.9 → 1
    }

    // ==================== 二、下单落库 ====================

    @Test
    @DisplayName("VIP 下单：order.Price 落 9 折价，响应才带 MemberPrice / MemberDiscount")
    void vipOrderStoresMemberPrice() {
        UserStateLookup lookup = newLockedLookup("C1", 1);
        OrderService service = newService(lookup);
        stubInsert(1);

        Map<String, Object> result = service.orderAdd(
                "C1", "M1", "O1", 99.99f, "", "", 0, 0, 0, 1, "", "", "", "");

        assertThat(result.get("success")).isEqualTo(true);
        ArgumentCaptor<OrderDao.OrderRow> row = ArgumentCaptor.forClass(OrderDao.OrderRow.class);
        verify(orderDao).insertOrder(row.capture(), anyString(), any(), anyLong(), any());
        assertThat(row.getValue().price()).isCloseTo(89.99f, within(0.001f));
        assertThat((Double) result.get("MemberPrice")).isCloseTo(89.99d, within(1e-9));
        assertThat((Double) result.get("MemberDiscount")).isCloseTo(10.00d, within(1e-3));
    }

    @Test
    @DisplayName("非 VIP：落库价与响应形状都与改动前逐字相同（无会员键）")
    void nonVipOrderIsUnchanged() {
        UserStateLookup lookup = newLockedLookup("C2", 0);
        OrderService service = newService(lookup);
        stubInsert(1);

        Map<String, Object> result = service.orderAdd(
                "C2", "M1", "O2", 99.99f, "", "", 0, 0, 0, 1, "", "", "", "");

        assertThat(result.get("success")).isEqualTo(true);
        ArgumentCaptor<OrderDao.OrderRow> row = ArgumentCaptor.forClass(OrderDao.OrderRow.class);
        verify(orderDao).insertOrder(row.capture(), anyString(), any(), anyLong(), any());
        assertThat(row.getValue().price()).isCloseTo(99.99f, within(0.001f));
        assertThat(result).doesNotContainKeys("MemberPrice", "MemberDiscount", "Discount");
    }

    @Test
    @DisplayName("fail-open：等级读失败 ⇒ 按普通用户计价，下单照常成功")
    void memberLevelFailureFallsBackToOrdinaryPrice() {
        UserStateLookup lookup = org.mockito.Mockito.mock(UserStateLookup.class);
        when(lookup.isMerchantFrozen("M1")).thenReturn(false);
        when(lookup.memberLevel("C3")).thenThrow(new RuntimeException("db down"));
        OrderService service = newService(lookup);
        stubInsert(1);

        Map<String, Object> result = service.orderAdd(
                "C3", "M1", "O3", 99.99f, "", "", 0, 0, 0, 1, "", "", "", "");

        assertThat(result.get("success")).isEqualTo(true);
        ArgumentCaptor<OrderDao.OrderRow> row = ArgumentCaptor.forClass(OrderDao.OrderRow.class);
        verify(orderDao).insertOrder(row.capture(), anyString(), any(), anyLong(), any());
        assertThat(row.getValue().price()).isCloseTo(99.99f, within(0.001f));
        assertThat(result).doesNotContainKeys("MemberPrice", "MemberDiscount");
    }

    // ==================== 三、券叠加（先 9 折、再算券） ====================

    @Test
    @DisplayName("叠加顺序：券的基数 = 会员价（100 → 90），实付 = 90 - 20 = 70")
    void couponIsAppliedOnTopOfMemberPrice() {
        UserStateLookup lookup = newLockedLookup("C1", 1);
        OrderService service = newService(lookup);
        stubInsert(1);
        when(couponService.usernameOf("C1")).thenReturn("alice");
        when(couponService.redeem("alice", "CP1", "M1", 90.0d, false, "O4"))
                .thenReturn(new CouponService.Redeem(true, "", 20.0d));

        Map<String, Object> result = service.orderAdd(
                "C1", "M1", "O4", 100f, "", "", 0, 0, 0, 1, "", "CP1", "", "");

        assertThat(result.get("success")).isEqualTo(true);
        ArgumentCaptor<OrderDao.OrderRow> row = ArgumentCaptor.forClass(OrderDao.OrderRow.class);
        verify(orderDao).insertOrder(row.capture(), anyString(), any(), anyLong(), any());
        // 70 = max(0, 90 - 20)
        assertThat(row.getValue().price()).isCloseTo(70.00f, within(0.001f));
        // ⚠️ 这一行才是「叠加顺序」的判据：第 4 个实参是**会员价**，不是入参原价 100。
        verify(couponService).redeem(eq("alice"), eq("CP1"), eq("M1"),
                eq(90.0d), eq(false), eq("O4"));
        assertThat((Double) result.get("MemberDiscount")).isCloseTo(10.00d, within(1e-9));
        assertThat((Double) result.get("Discount")).isCloseTo(20.00d, within(1e-9));
    }

    @Test
    @DisplayName("非 VIP 用券：基数仍是原价（100）—— 会员段不改变老链路")
    void couponBaseForNonVipIsOriginalPrice() {
        UserStateLookup lookup = newLockedLookup("C2", 0);
        OrderService service = newService(lookup);
        stubInsert(1);
        when(couponService.usernameOf("C2")).thenReturn("bob");
        when(couponService.redeem("bob", "CP2", "M1", 100.0d, false, "O5"))
                .thenReturn(new CouponService.Redeem(true, "", 20.0d));

        Map<String, Object> result = service.orderAdd(
                "C2", "M1", "O5", 100f, "", "", 0, 0, 0, 1, "", "CP2", "", "");

        assertThat(result.get("success")).isEqualTo(true);
        ArgumentCaptor<OrderDao.OrderRow> row = ArgumentCaptor.forClass(OrderDao.OrderRow.class);
        verify(orderDao).insertOrder(row.capture(), anyString(), any(), anyLong(), any());
        assertThat(row.getValue().price()).isCloseTo(80.00f, within(0.001f));
        verify(couponService).redeem(eq("bob"), eq("CP2"), eq("M1"),
                eq(100.0d), eq(false), eq("O5"));
        assertThat(result).doesNotContainKeys("MemberPrice", "MemberDiscount");
    }

    // ==================== 内部 ====================

    private UserStateLookup newLockedLookup(String customerId, int level) {
        UserStateLookup lookup = org.mockito.Mockito.mock(UserStateLookup.class);
        when(lookup.isMerchantFrozen("M1")).thenReturn(false);
        when(lookup.memberLevel(customerId)).thenReturn(level);
        return lookup;
    }

    private OrderService newService(UserStateLookup lookup) {
        when(userStateLookupProvider.getIfAvailable()).thenReturn(lookup);
        when(lockService.runLocked(any(LockName.class), anyString(),
                ArgumentMatchers.<Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());
        return new OrderService(orderDao, lockService, orderStateMachine, orderTransitionRecorder,
                userStateLookupProvider, notificationService, shippingTrackDao, new ObjectMapper(),
                couponService, goodsDao, seckillDao, stockMetrics, protectionMetrics,
                walletService, memberService);
    }

    private void stubInsert(int rows) {
        when(orderDao.countByOrderId(anyString())).thenReturn(0);
        // 第三批㉙：OrderService 现在走带支付/积分快照的 5 参重载。
        when(orderDao.insertOrder(any(OrderDao.OrderRow.class), anyString(), any(), anyLong(), any()))
                .thenReturn(rows);
    }
}
