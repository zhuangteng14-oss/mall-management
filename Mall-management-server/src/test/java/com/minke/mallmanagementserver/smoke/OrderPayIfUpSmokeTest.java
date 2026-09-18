package com.minke.mallmanagementserver.smoke;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 状态变更接口 smoke test：POST /mysqlGoods/orderPayIfUp
 *
 * <p>该接口是 api-contract.md 标注为「是（CAS）」的三个订单状态接口之一，
 * 也是全项目唯一正确的并发控制。这里把「条件 UPDATE 的影响行数 → 返回体」
 * 这一映射关系钉死，防止后续改动破坏 CAS 分支语义。
 *
 * <p>注意：本测试用 Mock 替换 JdbcTemplate，不触达真实数据库，故不会写入数据。
 */
class OrderPayIfUpSmokeTest extends AbstractSmokeTest {

    private static final String ORDER_ID = "SMOKE-ORDER-1";

    /** (a) 条件 UPDATE 影响 1 行 → 付款成功。 */
    @Test
    void orderPayIfUp_CAS_branching_rowsAffected() throws Exception {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        mockMvc.perform(post("/mysqlGoods/orderPayIfUp")
                        .param("orderId", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("付款成功"));
    }

    /** (b) 条件 UPDATE 影响 0 行（订单不存在或已付款）→ 返回失败。 */
    @Test
    void orderPayIfUp_CAS_branching_noRowsAffected() throws Exception {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        mockMvc.perform(post("/mysqlGoods/orderPayIfUp")
                        .param("orderId", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("订单不存在或已付款"));
    }
}
