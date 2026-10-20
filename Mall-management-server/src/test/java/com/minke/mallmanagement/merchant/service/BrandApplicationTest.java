package com.minke.mallmanagement.merchant.service;

import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
import com.minke.mallmanagement.goods.service.GoodsCacheService;
import com.minke.mallmanagement.merchant.dao.MerchantApplicationDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批四第二批（2026-10-19）：品牌加盟的**两条判据**。
 *
 * <h2>【判据一：品牌申请不是入驻申请】</h2>
 * 品牌通过时只能置 {@code user.IsBrand=1}，**不得**再走 {@code promote}（它会写
 * {@code usertype / merchantid / login.Role} 并清两份用户缓存）—— 两条通知文案、审计动作、
 * 失败模式都不同。故本类用 {@code verify(never()).promoteToMerchant(...)} 钉住「没升级」。
 *
 * <h2>【判据二：非商家不能开通品牌 —— 且必须在消费 CAS 之前拦住】</h2>
 * {@code merchant_application.Status} 的待审行一旦被 CAS 改成 {@code approved} 就没有回退口；
 * 若先 CAS 再发现「账号不是商家」，这条申请会永远停在一个「已通过但没生效」的半完成态。
 * 本类为此单独一个用例：{@code casApprove} **一次都不许被调**。
 *
 * <p>⚠️ 本类不测真库唯一性 / 事务回滚（A3 档），只钉服务层把哪些参数交给了谁。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrandApplicationTest {

    @Mock private MerchantApplicationDao applicationDao;
    @Mock private UserGovernanceDao userGovernanceDao;
    @Mock private GoodsShelfDao goodsShelfDao;
    @Mock private UserCacheService userCacheService;
    @Mock private GoodsCacheService goodsCacheService;
    @Mock private com.minke.mallmanagement.merchant.brand.service.BrandService brandService;

    private MerchantApplicationService service;

    @BeforeEach
    void setUp() {
        service = new MerchantApplicationService(applicationDao, userGovernanceDao,
                goodsShelfDao, userCacheService, goodsCacheService, brandService);
    }

    private static Map<String, Object> application(boolean isBrand) {
        Map<String, Object> row = new HashMap<>();
        row.put("Id", 7L);
        row.put("Username", "u1");
        row.put("IsBrand", isBrand);
        return row;
    }

    @Test
    @DisplayName("品牌申请：账号不是商家 ⇒ 写库之前就拒（不制造注定驳回的队列项）")
    void brandApplyRejectsNonMerchant() {
        when(userGovernanceDao.selectMerchantId("u1")).thenReturn(null);

        Map<String, Object> result = service.apply("u1", "声谷旗舰店", "L-1",
                null, null, null, null, true);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("msg"))).contains("尚未入驻");
        verify(applicationDao, never()).insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(true));
        verify(applicationDao, never()).insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("品牌申请：商家提交 ⇒ 落一行 IsBrand=1，回 isBrand")
    void brandApplyFromMerchantInsertsBrandRow() {
        when(userGovernanceDao.selectMerchantId("u1")).thenReturn("M1");

        Map<String, Object> result = service.apply("u1", "声谷旗舰店", "L-1",
                null, null, null, null, true);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("isBrand")).isEqualTo(true);
        verify(applicationDao).insert(eq("u1"), eq("声谷旗舰店"), eq("L-1"), any(), any(), any(),
                any(), any(), any(LocalDateTime.class), eq(true));
    }

    @Test
    @DisplayName("入驻申请仍走旧签名 ⇒ IsBrand=0（加性重载没有反转旧行为）")
    void plainApplyStaysNonBrand() {
        Map<String, Object> result = service.apply("u1", "声谷旗舰店", "L-1",
                null, null, null, null);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("isBrand")).isEqualTo(false);
        verify(userGovernanceDao, never()).selectMerchantId(any());
        verify(applicationDao).insert(eq("u1"), eq("声谷旗舰店"), eq("L-1"), any(), any(), any(),
                any(), any(), any(LocalDateTime.class), eq(false));
    }

    @Test
    @DisplayName("品牌通过：置 user.IsBrand + 清商品缓存；不 promote、不碰用户身份缓存")
    void brandApproveSetsBrandOnly() {
        when(applicationDao.selectById(7L)).thenReturn(application(true));
        when(userGovernanceDao.selectMerchantId("u1")).thenReturn("M1");
        when(applicationDao.casApprove(eq(7L), any(), any())).thenReturn(1);

        Map<String, Object> result = service.audit("admin", 7L, true, null);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("brand-approved");
        verify(userGovernanceDao).setBrand("u1", true);
        verify(goodsCacheService).evictByMerchant("M1");
        verify(userGovernanceDao, never()).promoteToMerchant(any(), any());
        verify(userCacheService, never()).evictPublic(any());
    }

    @Test
    @DisplayName("品牌通过但账号已不是商家：CAS 之前拒，pending 不被吃掉")
    void brandApproveWithoutMerchantFailsBeforeCas() {
        when(applicationDao.selectById(7L)).thenReturn(application(true));
        when(userGovernanceDao.selectMerchantId("u1")).thenReturn("  ");

        Map<String, Object> result = service.audit("admin", 7L, true, null);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("msg"))).contains("尚未入驻");
        verify(applicationDao, never()).casApprove(anyLong(), any(), any());
        verify(userGovernanceDao, never()).setBrand(any(), eq(true));
        verify(goodsCacheService, never()).evictByMerchant(any());
    }

    @Test
    @DisplayName("品牌驳回：outcome=brand-rejected，不动 IsBrand")
    void brandRejectOutcome() {
        when(applicationDao.selectById(7L)).thenReturn(application(true));
        when(applicationDao.casReject(eq(7L), any(), any(), eq("资料不全"))).thenReturn(1);

        Map<String, Object> result = service.audit("admin", 7L, false, "资料不全");

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("outcome")).isEqualTo("brand-rejected");
        verify(userGovernanceDao, never()).setBrand(any(), eq(true));
        verify(userGovernanceDao, never()).selectMerchantId(any());
    }
}
