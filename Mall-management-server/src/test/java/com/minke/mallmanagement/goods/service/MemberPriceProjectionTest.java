package com.minke.mallmanagement.goods.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.marketing.discount.service.DiscountLimitService;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GoodsService.productCodePrice} 的会员价投影（批四第二批 commit 3）。
 *
 * <p>判据只有一条，但必须钉死：<b>{@code memberPrice} 是严格加性键</b> ——
 * 只有「登录客户 + VIP + 定价 &gt; 0」三者同时成立才出现；其余情形响应逐字段与改动前相同。
 * 这条键是详情页划线价/高亮价的唯一数据源，多给一个键等于给普通用户看错的价。
 *
 * <p>为什么直接 mock {@code GoodsCacheService}：{@code priceByTriple} 的缓存行为另有
 * {@code GoodsPriceCacheTest} 覆盖，本类只测「拿到行之后怎么投影」，两者判据不重叠。
 */
@ExtendWith(MockitoExtension.class)
class MemberPriceProjectionTest {

    @Mock private GoodsDao goodsDao;
    @Mock private GoodsCacheService goodsCacheService;
    @Mock private GoodsAuditService goodsAuditService;
    @Mock private DiscountLimitService discountLimitService;
    @Mock private NotificationService notificationService;
    @Mock private ObjectProvider<UserStateLookup> userStateLookupProvider;
    @Mock private UserStateLookup userStateLookup;

    private GoodsService service;

    @BeforeEach
    void setUp() {
        service = new GoodsService(goodsDao, goodsCacheService, goodsAuditService,
                discountLimitService, notificationService, userStateLookupProvider, "/tmp/upload");
    }

    @Test
    @DisplayName("VIP：memberPrice = round(99.99 × 0.9, 2) = 89.99，其余键不变")
    void vipGetsMemberPrice() {
        when(goodsCacheService.priceByTriple("M1", "杯子", "G-1")).thenReturn(priceRow(99.99d));
        when(userStateLookupProvider.getIfAvailable()).thenReturn(userStateLookup);
        when(userStateLookup.memberLevel("C1")).thenReturn(1);

        Map<String, Object> result = service.productCodePrice("M1", "杯子", "G-1", "C1");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("price")).isEqualTo(99.99d);
        assertThat(result.get("goodImg")).isEqualTo("cup.jpg");
        assertThat((Double) result.get("memberPrice")).isCloseTo(89.99d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("非 VIP：响应里没有 memberPrice（与改动前逐字相同）")
    void nonVipGetsNoMemberPrice() {
        when(goodsCacheService.priceByTriple("M1", "杯子", "G-1")).thenReturn(priceRow(99.99d));
        when(userStateLookupProvider.getIfAvailable()).thenReturn(userStateLookup);
        when(userStateLookup.memberLevel("C2")).thenReturn(0);

        Map<String, Object> result = service.productCodePrice("M1", "杯子", "G-1", "C2");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result).doesNotContainKey("memberPrice");
    }

    @Test
    @DisplayName("未登录（customerId 为空）：不查等级，也不返回 memberPrice")
    void anonymousGetsNoMemberPriceAndNoLookup() {
        when(goodsCacheService.priceByTriple("M1", "杯子", "G-1")).thenReturn(priceRow(99.99d));

        Map<String, Object> result = service.productCodePrice("M1", "杯子", "G-1", null);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result).doesNotContainKey("memberPrice");
        verify(userStateLookupProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("fail-open：等级读失败 ⇒ 不带会员价，详情页照常可看")
    void lookupFailureFallsBackToOrdinaryPrice() {
        when(goodsCacheService.priceByTriple("M1", "杯子", "G-1")).thenReturn(priceRow(99.99d));
        when(userStateLookupProvider.getIfAvailable()).thenReturn(userStateLookup);
        when(userStateLookup.memberLevel("C3")).thenThrow(new RuntimeException("db down"));

        Map<String, Object> result = service.productCodePrice("M1", "杯子", "G-1", "C3");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("price")).isEqualTo(99.99d);
        assertThat(result).doesNotContainKey("memberPrice");
    }

    @Test
    @DisplayName("定价 0 / null：不编造会员价（0 × 0.9 只会画出一个假折扣）")
    void zeroPriceGetsNoMemberPrice() {
        when(goodsCacheService.priceByTriple("M1", "赠品", "G-0")).thenReturn(priceRow(0d));
        when(userStateLookupProvider.getIfAvailable()).thenReturn(userStateLookup);
        when(userStateLookup.memberLevel("C1")).thenReturn(1);

        Map<String, Object> result = service.productCodePrice("M1", "赠品", "G-0", "C1");

        assertThat(result).doesNotContainKey("memberPrice");
    }

    @Test
    @DisplayName("三元组不存在：维持 {success:false}，不因会员段多出任何键")
    void missingTripleKeepsOldShape() {
        when(goodsCacheService.priceByTriple("M1", "杯子", "NOPE")).thenReturn(null);

        Map<String, Object> result = service.productCodePrice("M1", "杯子", "NOPE", "C1");

        assertThat(result).containsOnlyKeys("success");
        assertThat(result.get("success")).isEqualTo(false);
    }

    private static Map<String, Object> priceRow(double price) {
        Map<String, Object> row = new HashMap<>();
        row.put("Price", price);
        row.put("GoodImg", "cup.jpg");
        row.put("OriginalPrice", null);
        row.put("ImgList", null);
        row.put("IsBrand", 0);
        return row;
    }
}
