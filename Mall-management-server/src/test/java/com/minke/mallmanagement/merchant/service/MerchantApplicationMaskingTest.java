package com.minke.mallmanagement.merchant.service;

import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
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
 * D3-4 读侧接线：入驻申请**管理端列表**掩 {@code ContactPhone} / {@code ShopAddress}，
 * 「我的申请」（本人查询）保持明文。
 *
 * <p>⚠️ 本人查询那条不是凑数：商家要靠明文核对「我填的号码对不对」；如果只测管理端，
 * 把 {@code myApplications} 一并接上掩码也会全绿。
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

    private MerchantApplicationService service() {
        return new MerchantApplicationService(applicationDao, userGovernanceDao,
                goodsShelfDao, userCacheService);
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
    @DisplayName("listForAdmin：电话 / 店铺地址掩码，店铺名原样，源行不被改写")
    void listForAdminMasksPhoneAndShopAddress() {
        Map<String, Object> row = pendingRow();
        when(applicationDao.selectByStatus("pending", 100)).thenReturn(List.of(row));
        when(applicationDao.countByStatus("pending")).thenReturn(1L);

        Map<String, Object> result = service().listForAdmin("pending", 100);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("ContactPhone")).isEqualTo("138****8000");
        assertThat(list.get(0).get("ShopAddress")).isEqualTo("北京市朝***");
        assertThat(list.get(0).get("ShopName")).isEqualTo("小铺");
        assertThat(row.get("ContactPhone")).as("DAO 返回的源行必须保持明文").isEqualTo("13800138000");
        assertThat(row.get("ShopAddress")).as("DAO 返回的源行必须保持明文").isEqualTo("北京市朝阳区建国路1号");
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
