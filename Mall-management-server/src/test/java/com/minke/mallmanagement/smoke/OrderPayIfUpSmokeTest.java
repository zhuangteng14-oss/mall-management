package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCreator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 状态变更接口 smoke test：POST /order/orderPayIfUp
 *
 * <p>该接口是 api-contract.md 标注为「是（CAS）」的三个订单状态接口之一，
 * 也是全项目唯一正确的并发控制。这里把「条件 UPDATE 的影响行数 → 返回体」
 * 这一映射关系钉死，防止后续改动破坏 CAS 分支语义。
 *
 * <p>注意：本测试用 Mock 替换 JdbcTemplate，不触达真实数据库，故不会写入数据。
 *
 * <p>⚠️ <b>三层第一笔（订单域）换了 stub 的匹配器 —— 不换会「绿着走错分支」</b>：
 * 订单 SQL 现在经由 {@code OrderDao} 的 {@code JdbcClient} 下发，而
 * {@code JdbcClient.update()} 走的是 {@code classicOps.update(statementCreatorForIndexedParams())}
 * （{@code DefaultJdbcClient} 源码），即落到 {@link org.springframework.jdbc.core.JdbcTemplate}
 * 的 <b>{@code update(PreparedStatementCreator)} 单参重载</b>，**不再是**
 * {@code update(String, Object...)}。
 * 若沿用旧的 {@code anyString(), any(Object[].class)} 匹配器，这两条用例会在**未 stub 命中**的
 * 情况下拿到 Mockito 的默认返回值 0 ⇒ (a) 的 {@code $.msg} 变成「订单不存在或已付款」而变红 ——
 * 那还是好的；(b) 则会**碰巧仍然是绿的**，于是这个类就从「钉住 rows → 文案」退化成一个空壳。
 * 换匹配器后，断言的仍是同一个映射关系，只是把「DAO 用什么签名下发 SQL」这件事也纳入了监视。
 */
class OrderPayIfUpSmokeTest extends AbstractSmokeTest {

    private static final String ORDER_ID = "SMOKE-ORDER-1";

    /** (a) 条件 UPDATE 影响 1 行 → 付款成功。 */
    @Test
    void orderPayIfUp_CAS_branching_rowsAffected() throws Exception {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);

        mockMvc.perform(post("/order/orderPayIfUp")
                        .param("orderId", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("付款成功"));
    }

    /** (b) 条件 UPDATE 影响 0 行（订单不存在或已付款）→ 返回失败。 */
    @Test
    void orderPayIfUp_CAS_branching_noRowsAffected() throws Exception {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(0);

        mockMvc.perform(post("/order/orderPayIfUp")
                        .param("orderId", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("订单不存在或已付款"));
    }
    /**
     * URL 契约防回归（2026-10-02 笔 2）：旧路径必须**不再有 handler**。
     *
     * <p>为什么不能只断言「新路径通」：那样把旧映射一并留着也照样绿 —— 「换了前缀」这件事就
     * 没有可观测证据。两个断言合起来才等价于「旧的不在、新的在」。
     */
    @Test
    void oldPrefixHasNoHandlerAnyMore() throws Exception {
        mockMvc.perform(post("/mysqlGoods/orderPayIfUp")
                        .param("orderId", ORDER_ID))
                .andExpect(status().isNotFound());
    }

}
