package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀扣减接线（{@code orderAdd.ActivityId} → {@code SeckillDao.deductSoldStock}）smoke test
 * —— C3 块 7 · commit 2c。
 *
 * <p>被测的只有一件：{@link com.minke.mallmanagement.order.service.OrderService} 的秒杀段
 * <b>在什么条件下会让这张单不成立</b>。它是本仓<b>唯一一条 fail-closed 的下单路径</b>
 * —— 与同一次 {@code orderAdd} 里 {@code deductStocks} 的 fail-open（0 行不挡单）刻意相反。
 *
 * <h2>【这条路径为什么值得单独钉】</h2>
 * 秒杀段是<b>先扣名额、后建单</b>（同用券段）：名额没抢到就必须**整单不落**，
 * 否则「超卖」这件事只会体现在一个没人看的数字上。三条用例分别钉三种「不成立 / 成立」：
 * <ol>
 *   <li>活动查不到 ⇒ 不建单（一次写库都不发）；</li>
 *   <li>名额没抢到（{@code deductSoldStock} 回 0）⇒ 不建单；</li>
 *   <li>名额抢到 ⇒ 订单照落，且写库次数 = 1（订单）+ 1（明细）+ 1（库存扣减）
 *       + 1（2d 的整单标记）+ 1（秒杀名额）= <b>5</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>本切片看不到 SQL 文本</b>（SQL 不出切片）⇒ 这里钉的是「调了几次写 / 走没走到建单」，
 * 「{@code SoldStock + n <= TotalStock} 这条判据本身对不对」归 2c 的默认 profile 手验与 A3。
 * ⚠️ 与 {@code OrderAddItemsSmokeTest} 同款：{@code JdbcClient.update()} 落到
 * {@code JdbcTemplate.update(PreparedStatementCreator)} 单参重载，故次数可精确断言。
 */
class SeckillOrderDeductSmokeTest extends AbstractSmokeTest {

    private static final String ACTIVITY = "SMOKE-SK-1";

    /**
     * 一单一件（{@code Items} 显式一件）—— 件数少是为了让「写库次数」这条判据读得出来：
     * 每多一件明细就会多两次 update（明细 + 该件库存扣减），混在一起就看不出多了谁。
     */
    private MockHttpServletRequestBuilder base() {
        return post("/order/orderAdd")
                .param("CustomerId", "SMOKE-CUST-1")
                .param("MerchantId", "SMOKE-MERCH-1")
                .param("OrderId", "SMOKE-SK-ORDER-1")
                .param("Price", "5")
                .param("ProductInformation", "甲:A1")
                .param("Images", "")
                .param("PayIf", "0")
                .param("DeliveryIf", "0")
                .param("ReceiveIf", "0")
                .param("Quantity", "1")
                .param("Items", "[{\"code\":\"A1\",\"name\":\"甲\",\"price\":5,\"qty\":1}]")
                .param("ActivityId", ACTIVITY);
    }

    /**
     * 桩：活动查得到。
     *
     * <p>⚠️ <b>这条 stub 打在哪个重载上，是读源码定下来的、不是猜的</b>（本笔实测踩过两次）：
     * {@code SeckillDao.selectActivityByActivityId} 用的是 {@code .param("activityId", v)} ——
     * <b>具名参数</b> ⇒ {@code JdbcClient} 走
     * {@code DefaultJdbcClient$NamedParamResultQuerySpec.listOfRows()} ⇒
     * {@code namedParamOps.queryForList(sql, paramSource)} ⇒
     * spring-jdbc 6.2.6 的 {@code NamedParameterJdbcTemplate.queryForList(String, SqlParameterSource)}
     * 逐字是 {@code query(sql, paramSource, new ColumnMapRowMapper())} ⇒ 而它又是
     * {@code getJdbcOperations().query(PreparedStatementCreator, RowMapper)}（**双参重载**）。
     *
     * <p>⚠️ <b>两次错的桩（都已实测红过，记在这里免得下一个人再走一遍）</b>：
     * <ol>
     *   <li>{@code query(String, RowMapper, Object...)} —— 那是 {@code query(Class).single()}
     *       （**位置参数**侧）那条路，见 {@code AuthSessionSmokeTest.stubRole}；</li>
     *   <li>{@code queryForList(String, Object...)} —— 那是 {@code IndexedParamResultQuerySpec}
     *       的 {@code listOfRows()}，只有**位置**参数才走它。</li>
     * </ol>
     * 桩不中的后果是替身回空表 ⇒ 活动判成「不存在」⇒ 用例红在 {@code $.msg = 秒杀活动不存在} 上
     * ——<b>红在值上，不红在静默里</b>。
     *
     * <p>⚠️ 这条重载**不会与查重相撞**：{@code OrderDao.countByOrderId} 走的是
     * {@code query(Integer.class).single()}（位置参数 ⇒ 三参重载）—— 两条路分属不同重载，
     * 故这里桩出的那一行**不会**让查重误判「订单已存在」。
     * <p>⚠️ 注意**更新**侧反而是统一的：具名与位置参数的 {@code update} 最终都落到
     * {@code JdbcTemplate.update(PreparedStatementCreator)}（{@code NamedParameterJdbcTemplate.update}
     * 的实现就是它）⇒ 本类「写了几次」的精确次数判据不受参数形态影响。
     *
     * <p>⚠️ 行里必须有 {@code Id}（秒杀段要 {@code ((Number) activity.get("Id")).longValue()}）——
     * 缺了会 NPE、被那一段自己的 catch 接住后回「校验失败」，两条断言一起红得很难读。
     * ⚠️ 用**可变** HashMap（照 {@code AuthSessionSmokeTest} 那条按语：DAO 给的本来就是可变行）。
     */
    private void stubActivity(long id) {
        Map<String, Object> row = new HashMap<>();
        row.put("Id", id);
        row.put("ActivityId", ACTIVITY);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(row));
    }

    @Test
    @DisplayName("活动查不到 ⇒ 整单不落（一次写库都不发）—— fail-closed 的第一种成因")
    void activityNotFound_doesNotBuildTheOrder() throws Exception {
        // 刻意**不 stub** 任何读：替身的默认答复是空列表 ⇒ selectActivityByActivityId 回 null。
        mockMvc.perform(base())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("秒杀活动不存在"));

        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class));
    }

    @Test
    @DisplayName("名额没抢到（CAS 回 0 行）⇒ 整单不落 —— fail-closed 的第二种成因")
    void quotaNotGrabbed_doesNotBuildTheOrder() throws Exception {
        stubActivity(7L);
        // 所有写一律回 0 ⇒ 秒杀那句 CAS 必然也是 0（**不依赖调用顺序**：无论谁先跑，
        // 都到不了建单那一步）。⇒ 唯一发生过的写就是那一次没命中的名额 CAS。
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(0);

        mockMvc.perform(base())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("秒杀活动已售完"));

        verify(jdbcTemplate, times(1)).update(any(PreparedStatementCreator.class));
    }

    @Test
    @DisplayName("名额抢到 ⇒ 订单照落；写库 = 1 订单 + 1 明细 + 1 库存 + 1 标记 + 1 名额 = 5")
    void quotaGrabbed_landsOrderAndAllWrites() throws Exception {
        stubActivity(7L);
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);

        mockMvc.perform(base())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("订单添加成功"));

        // ⚠️ 精确次数：多一次说明秒杀段多扣了 / 标记写了两次；少一次说明名额那道 CAS 压根没发。
        verify(jdbcTemplate, times(5)).update(any(PreparedStatementCreator.class));
    }
}
