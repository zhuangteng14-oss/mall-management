package com.minke.mallmanagement.merchant.service;

import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
import com.minke.mallmanagement.goods.service.GoodsCacheService;
import com.minke.mallmanagement.merchant.dao.MerchantApplicationDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 入驻申请的**明文**约定：管理端审核列表与「我的申请」都必须返回原始联系方式。
 *
 * <p>⚠️ 2026-10-18 手验后改：D3-4 曾把管理端列表掩成 {@code 138****8000}，客服无法按电话
 * 联系申请人 ⇒ 用户裁决恢复明文。本测试把「恢复」钉住：若有人再次把列表接上掩码，它会当场红。
 */
@ExtendWith(MockitoExtension.class)
class MerchantApplicationMaskingTest {

    @Mock
    private MerchantApplicationDao applicationDao;
    @Mock
    private UserGovernanceDao userGovernanceDao;
    @Mock
    private GoodsShelfDao goodsShelfDao;
    @Mock
    private UserCacheService userCacheService;
    @Mock
    private GoodsCacheService goodsCacheService;
    @Mock
    private com.minke.mallmanagement.merchant.brand.service.BrandService brandService;

    private MerchantApplicationService service() {
        return new MerchantApplicationService(applicationDao, userGovernanceDao,
                goodsShelfDao, userCacheService, goodsCacheService, brandService);
    }

    private static Map<String, Object> pendingRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("Id", 7L);
        row.put("ShopName", "小铺");
        row.put("ContactPhone", "13800138000");
        row.put("ShopAddress", "北京市朝阳区建国路1号");
        return row;
    }

    @Test
    @DisplayName("listForAdmin：审核列表必须返回明文电话 / 店铺地址（不再脱敏）")
    void listForAdminKeepsContactPlaintext() {
        Map<String, Object> row = pendingRow();
        when(applicationDao.selectByStatus("pending", 100)).thenReturn(List.of(row));
        when(applicationDao.countByStatus("pending")).thenReturn(1L);

        Map<String, Object> result = service().listForAdmin("pending", 100);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("ContactPhone")).isEqualTo("13800138000");
        assertThat(list.get(0).get("ShopAddress")).isEqualTo("北京市朝阳区建国路1号");
        assertThat(list.get(0).get("ShopName")).isEqualTo("小铺");
    }

    @Test
    @DisplayName("myApplications：本人查询保持明文（不接掩码）")
    void myApplicationsStaysPlaintext() {
        Map<String, Object> row = pendingRow();
        when(applicationDao.selectByUsername("alice", 100)).thenReturn(List.of(row));

        Map<String, Object> result = service().myApplications("alice", 100);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        assertThat(list.get(0).get("ContactPhone")).isEqualTo("13800138000");
        assertThat(list.get(0).get("ShopAddress")).isEqualTo("北京市朝阳区建国路1号");
    }
}
