package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ResolvedIdentity;
import com.minke.mallmanagement.chat.model.UserIdentity;
import com.minke.mallmanagement.merchant.brand.dao.BrandProfileDao;
import com.minke.mallmanagement.merchant.dao.MerchantApplicationDao;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IdentityResolver} 的六态判据（N4）。
 *
 * <p>DAO / Service 全部用替身：这里验的是**分支与取值**，SQL 本身归完整上下文与手验。
 * 每个用例只给「解析所需的最小数据」，避免一个用例顺带证明另一个分支。
 */
class IdentityResolverTest {

    private MerchantApplicationDao applicationDao;
    private BrandProfileDao brandProfileDao;
    private UserService userService;
    private UserGovernanceDao userGovernanceDao;
    private IdentityResolver resolver;

    @BeforeEach
    void setUp() {
        applicationDao = mock(MerchantApplicationDao.class);
        brandProfileDao = mock(BrandProfileDao.class);
        userService = mock(UserService.class);
        userGovernanceDao = mock(UserGovernanceDao.class);
        resolver = new IdentityResolver(applicationDao, brandProfileDao, userService, userGovernanceDao);
    }

    private void customer(String userId, String username) {
        when(userService.publicInfo(userId)).thenReturn(Map.of("username", username));
    }

    private Map<String, Object> application(String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("Status", status);
        row.put("ShopName", "米可数码店");
        row.put("SubmittedAt", "2026-10-20 10:00:00");
        row.put("RejectReason", "资料不全");
        return row;
    }

    private Map<String, Object> brandProfile(String status, String validTo) {
        Map<String, Object> row = new HashMap<>();
        row.put("Status", status);
        row.put("BrandName", "米可");
        row.put("ValidTo", validTo);
        return row;
    }

    @Test
    @DisplayName("未登录：ANONYMOUS，且一次库都不查")
    void anonymousDoesNotTouchDb() {
        ResolvedIdentity id = resolver.resolve(null, null);

        assertThat(id.identity()).isEqualTo(UserIdentity.ANONYMOUS);
        assertThat(id.boundary()).isEqualTo(ResolvedIdentity.Boundary.NONE);
        verify(applicationDao, never()).selectLatestByUsernameAndType(anyString(), anyString());
        verify(brandProfileDao, never()).selectByMerchant(anyString());
    }

    @Test
    @DisplayName("role=2 无档案：MERCHANT + 商家号")
    void merchantWithoutBrand() {
        when(brandProfileDao.selectByMerchant("m1")).thenReturn(null);

        ResolvedIdentity id = resolver.resolve("m1", 2);

        assertThat(id.identity()).isEqualTo(UserIdentity.MERCHANT);
        assertThat(id.merchantId()).isEqualTo("m1");
    }

    @Test
    @DisplayName("role=2 档案 active 且未过期：MERCHANT_BRAND")
    void activeBrand() {
        when(brandProfileDao.selectByMerchant("m1"))
                .thenReturn(brandProfile("active", "2099-01-01 00:00:00"));

        ResolvedIdentity id = resolver.resolve("m1", 2);

        assertThat(id.identity()).isEqualTo(UserIdentity.MERCHANT_BRAND);
        assertThat(id.brandName()).isEqualTo("米可");
        assertThat(id.brandValidTo()).isEqualTo("2099-01-01 00:00:00");
    }

    @Test
    @DisplayName("品牌判定不认 IsBrand：已撤销 / 已过期都回落 MERCHANT")
    void revokedOrExpiredBrandFallsBack() {
        when(brandProfileDao.selectByMerchant("m1"))
                .thenReturn(brandProfile("revoked", "2099-01-01 00:00:00"));
        assertThat(resolver.resolve("m1", 2).identity()).isEqualTo(UserIdentity.MERCHANT);

        when(brandProfileDao.selectByMerchant("m2"))
                .thenReturn(brandProfile("active", "2000-01-01 00:00:00"));
        assertThat(resolver.resolve("m2", 2).identity()).isEqualTo(UserIdentity.MERCHANT);
    }

    @Test
    @DisplayName("role=1 查不到登录名：ANONYMOUS（不拿空登录名查申请表）")
    void customerWithoutUsernameIsAnonymous() {
        when(userService.publicInfo("c1")).thenReturn(null);

        ResolvedIdentity id = resolver.resolve("c1", 1);

        assertThat(id.identity()).isEqualTo(UserIdentity.ANONYMOUS);
        verify(applicationDao, never()).selectLatestByUsernameAndType(anyString(), anyString());
    }

    @Test
    @DisplayName("USER_NO_APPLICATION：最近一条 settle 为空")
    void noApplication() {
        customer("c1", "demo_customer");
        when(applicationDao.selectLatestByUsernameAndType("demo_customer", "settle"))
                .thenReturn(null);

        ResolvedIdentity id = resolver.resolve("c1", 1);

        assertThat(id.identity()).isEqualTo(UserIdentity.USER_NO_APPLICATION);
        assertThat(id.username()).isEqualTo("demo_customer");
    }

    @Test
    @DisplayName("USER_PENDING：带店铺名与提交时间")
    void pending() {
        customer("c1", "demo_customer");
        when(applicationDao.selectLatestByUsernameAndType("demo_customer", "settle"))
                .thenReturn(application(MerchantApplicationService.STATUS_PENDING));

        ResolvedIdentity id = resolver.resolve("c1", 1);

        assertThat(id.identity()).isEqualTo(UserIdentity.USER_PENDING);
        assertThat(id.shopName()).isEqualTo("米可数码店");
        assertThat(id.submittedAt()).isEqualTo("2026-10-20 10:00:00");
    }

    @Test
    @DisplayName("USER_REJECTED：带驳回原因")
    void rejected() {
        customer("c1", "demo_customer");
        when(applicationDao.selectLatestByUsernameAndType("demo_customer", "settle"))
                .thenReturn(application(MerchantApplicationService.STATUS_REJECTED));

        ResolvedIdentity id = resolver.resolve("c1", 1);

        assertThat(id.identity()).isEqualTo(UserIdentity.USER_REJECTED);
        assertThat(id.rejectReason()).isEqualTo("资料不全");
    }

    @Test
    @DisplayName("Q3 边界：role=1 但 settle 已 approved ⇒ 已降级/冻结商家")
    void approvedButStillCustomerIsDegraded() {
        customer("c1", "demo_customer");
        when(applicationDao.selectLatestByUsernameAndType("demo_customer", "settle"))
                .thenReturn(application(MerchantApplicationService.STATUS_APPROVED));
        when(userGovernanceDao.selectMerchantId("demo_customer")).thenReturn("m1");

        ResolvedIdentity id = resolver.resolve("c1", 1);

        assertThat(id.identity()).isEqualTo(UserIdentity.MERCHANT);
        assertThat(id.boundary()).isEqualTo(ResolvedIdentity.Boundary.DEGRADED_MERCHANT);
        assertThat(id.merchantId()).isEqualTo("m1");
    }

    @Test
    @DisplayName("Q3 边界：role=3 ⇒ ADMIN，不查申请也不查品牌")
    void adminBoundary() {
        ResolvedIdentity id = resolver.resolve("admin", 3);

        assertThat(id.boundary()).isEqualTo(ResolvedIdentity.Boundary.ADMIN);
        assertThat(id.identity()).isNull();
        verify(applicationDao, never()).selectLatestByUsernameAndType(anyString(), anyString());
        verify(brandProfileDao, never()).selectByMerchant(anyString());
    }
}
