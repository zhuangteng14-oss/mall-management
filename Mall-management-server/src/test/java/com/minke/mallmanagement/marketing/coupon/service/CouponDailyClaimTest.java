package com.minke.mallmanagement.marketing.coupon.service;

import com.minke.mallmanagement.marketing.coupon.dao.CouponDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员每日专属券（批四第二批 commit 3）。
 *
 * <h2>为什么「同日只能领一次」不需要改唯一键</h2>
 * 每日券的 {@code CouponId} 带日期（{@code VIPDAILY-yyyyMMdd}）⇒ 既有唯一键
 * {@code (CouponId, ToAccount)} 天然退化成「同一天同一人一次」。本类要钉的是：
 * <ul>
 *   <li>非 VIP <b>连建券都不该走到</b>；</li>
 *   <li>同一天第二次：{@code insertGrant} 撞唯一键 ⇒ 退名额 + 明确文案（不是异常 500）；</li>
 *   <li>跨天：{@code CouponId} 变了 ⇒ 能再领一张（这是「每日」二字的判据）；</li>
 *   <li>列表筛：普通用户看不到 {@code MemberLevel=1} 的券。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CouponDailyClaimTest {

    @Mock private CouponDao couponDao;

    private CouponService service;

    @BeforeEach
    void setUp() {
        service = new CouponService(couponDao);
    }

    @Test
    @DisplayName("非 VIP 领每日券：拒绝，且不碰模板 / 不建券")
    void nonVipCannotClaimDailyCoupon() {
        when(couponDao.selectMemberLevelByAccount("bob")).thenReturn(0);

        Map<String, Object> result = service.claimDaily("bob");

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("msg")).isEqualTo("仅限 VIP 会员领取");
        verify(couponDao, never()).ensureDailyCoupon(anyString(), anyString());
        verify(couponDao, never()).tryClaim(anyString());
    }

    @Test
    @DisplayName("VIP 当日领取：按日期生成 CouponId，走既有领券路径并记 vip-daily 来源")
    void vipClaimsTodayCoupon() {
        when(couponDao.selectMemberLevelByAccount("alice")).thenReturn(1);
        when(couponDao.selectLocalDateCompact()).thenReturn("20261019");
        when(couponDao.ensureDailyCoupon("VIP_DAILY_TEMPLATE", "VIPDAILY-20261019")).thenReturn(1);
        when(couponDao.selectCoupon("VIPDAILY-20261019")).thenReturn(activeCoupon());
        when(couponDao.selectMerchantIdByUsername("alice")).thenReturn(null);
        when(couponDao.tryClaim("VIPDAILY-20261019")).thenReturn(1);
        when(couponDao.insertGrant("VIPDAILY-20261019", "alice", "vip-daily")).thenReturn(1);

        Map<String, Object> result = service.claimDaily("alice");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("msg")).isEqualTo("领取成功");
        assertThat(result.get("CouponId")).isEqualTo("VIPDAILY-20261019");
        verify(couponDao).ensureDailyCoupon("VIP_DAILY_TEMPLATE", "VIPDAILY-20261019");
        verify(couponDao).insertGrant("VIPDAILY-20261019", "alice", "vip-daily");
    }

    @Test
    @DisplayName("同日重复领取：撞唯一键 ⇒ 退名额 + 「已领取过」，不是异常")
    void sameDayDuplicateIsRejectedGracefully() {
        when(couponDao.selectMemberLevelByAccount("alice")).thenReturn(1);
        when(couponDao.selectLocalDateCompact()).thenReturn("20261019");
        when(couponDao.ensureDailyCoupon("VIP_DAILY_TEMPLATE", "VIPDAILY-20261019")).thenReturn(1);
        when(couponDao.selectCoupon("VIPDAILY-20261019")).thenReturn(activeCoupon());
        when(couponDao.selectMerchantIdByUsername("alice")).thenReturn(null);
        when(couponDao.tryClaim("VIPDAILY-20261019")).thenReturn(1);
        when(couponDao.insertGrant("VIPDAILY-20261019", "alice", "vip-daily"))
                .thenThrow(new DuplicateKeyException("uk_coupon_grant_coupon_acct"));

        Map<String, Object> result = service.claimDaily("alice");

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("msg")).isEqualTo("您已领取过该券");
        verify(couponDao).releaseClaim("VIPDAILY-20261019");
    }

    @Test
    @DisplayName("跨天可领：CouponId 随日期变化，第二次不撞唯一键")
    void nextDayClaimsAgainWithNewCouponId() {
        when(couponDao.selectMemberLevelByAccount("alice")).thenReturn(1);
        when(couponDao.selectLocalDateCompact()).thenReturn("20261019", "20261020");
        when(couponDao.ensureDailyCoupon("VIP_DAILY_TEMPLATE", "VIPDAILY-20261019")).thenReturn(1);
        when(couponDao.ensureDailyCoupon("VIP_DAILY_TEMPLATE", "VIPDAILY-20261020")).thenReturn(1);
        when(couponDao.selectCoupon(anyString())).thenReturn(activeCoupon());
        when(couponDao.selectMerchantIdByUsername("alice")).thenReturn(null);
        when(couponDao.tryClaim(anyString())).thenReturn(1);
        when(couponDao.insertGrant(anyString(), anyString(), anyString())).thenReturn(1);

        Map<String, Object> day1 = service.claimDaily("alice");
        Map<String, Object> day2 = service.claimDaily("alice");

        assertThat(day1.get("CouponId")).isEqualTo("VIPDAILY-20261019");
        assertThat(day2.get("CouponId")).isEqualTo("VIPDAILY-20261020");
        assertThat(day2.get("success")).isEqualTo(true);
    }

    @Test
    @DisplayName("模板缺失 / 迁移未落：给可诊断文案，不伪装成「已领」")
    void missingTemplateGivesActionableMessage() {
        when(couponDao.selectMemberLevelByAccount("alice")).thenReturn(1);
        when(couponDao.selectLocalDateCompact()).thenReturn("20261019");
        when(couponDao.ensureDailyCoupon("VIP_DAILY_TEMPLATE", "VIPDAILY-20261019")).thenReturn(0);
        when(couponDao.selectCoupon("VIPDAILY-20261019")).thenReturn(null);

        Map<String, Object> result = service.claimDaily("alice");

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("msg")).isEqualTo("今日会员券未配置，请联系管理员");
        verify(couponDao, never()).tryClaim(anyString());
    }

    @Test
    @DisplayName("领券中心：会员等级作为筛参下推 —— 普通用户 0、VIP 1")
    void claimablePassesMemberLevelToDao() {
        when(couponDao.selectMemberLevelByAccount("bob")).thenReturn(0);
        when(couponDao.selectClaimable("bob", 100, false, null, 0)).thenReturn(List.of());
        when(couponDao.selectWeekWindow()).thenReturn(weekWindow());

        Map<String, Object> result = service.claimable("bob", null, null, null);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("list")).isEqualTo(List.of());
        verify(couponDao).selectClaimable("bob", 100, false, null, 0);
    }

    @Test
    @DisplayName("一键领取：集合同样按会员等级下推（专属券不混进普通用户的批量里）")
    void claimBatchUsesMemberLevelFilter() {
        when(couponDao.selectMemberLevelByAccount("alice")).thenReturn(1);
        when(couponDao.selectClaimable("alice", 100, true, null, 1)).thenReturn(List.of());
        when(couponDao.selectWeekWindow()).thenReturn(weekWindow());

        Map<String, Object> result = service.claimBatch("alice");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("claimed")).isEqualTo(0);
        verify(couponDao).selectClaimable("alice", 100, true, null, 1);
        verify(couponDao, never()).tryClaim(anyString());
    }

    // ==================== 内部 ====================

    private static Map<String, Object> activeCoupon() {
        Map<String, Object> coupon = new HashMap<>();
        coupon.put("CouponId", "VIPDAILY-20261019");
        coupon.put("Status", "active");
        coupon.put("MerchantAccount", null);
        return coupon;
    }

    private static Map<String, Object> weekWindow() {
        Map<String, Object> window = new HashMap<>();
        window.put("DaysLeft", 3);
        return window;
    }
}
