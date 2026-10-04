package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B#1 价格校验 smoke test：POST /order/orderAdd 的 {@code Price} 闸。
 *
 * <p>被测的东西只有一件：{@code OrderService.orderAdd} 开头那道 {@code priceRejection}。
 * 本类**不**测锁、**不**测查重、**不**测事务 —— 那些分别由 {@code DistributedLockServiceTest}
 * 与 A3 的 {@code A-S2a-1} 系列负责。这里只钉「什么样的 Price 该被挡、什么样的该放行」，
 * 以及**闸的位置**（在 {@code runLocked} 之前）。
 *
 * <p>⚠️ <b>为什么这个类会被 CI 真正执行（本笔的验收证据就靠它）</b>：它继承
 * {@link AbstractSmokeTest}（{@code @WebMvcTest} 切片 + {@code @MockitoBean JdbcTemplate}），
 * **不连 MySQL、不连 Redis**、**不带 {@code A3_ENABLED} 门控** ⇒ 它在 {@code backend} job 的
 * 全量 {@code ./mvnw -B test} 里**真跑**。这正好补上 #11 与 C#2 的缺口：那两个笔的新测试
 * 都靠 {@code A3_ENABLED} 门控 + 真库，在阻塞 job 里被**逐字 Skip**（见 {@code progress.md §5-E}）。
 *
 * <p>⚠️ <b>而 A3 不会替本笔报警</b>：{@code ConcurrencyRunner} 里 {@code "Price"} 只出现一次
 * （{@code orderForm(...)}，值恒为 {@code "1.5"}），S4a 的 8 条断言依赖的是**状态位**（TD-17），
 * 不依赖 Price 可任意传。⇒ 本类是价格闸**唯一**的自动化证据，不是补充证据。
 */
class OrderAddPriceGuardSmokeTest extends AbstractSmokeTest {

    /**
     * 拒绝路径的判据，三条一起成立才算过：
     * <ol>
     *   <li>HTTP <b>200</b> + {@code {success:false, msg:"价格不合法"}} —— 与全仓业务失败同形
     *       （刻意**不是** 400，理由见 {@code OrderService.priceRejection} 的 javadoc）；</li>
     *   <li><b>零写库</b>：INSERT 经 {@code JdbcClient.update()} 落到
     *       {@code update(PreparedStatementCreator)} 单参重载（见 {@code OrderPayIfUpSmokeTest}
     *       的匹配器说明），故断言它**一次都没被调用**；</li>
     *   <li><b>不取锁</b>：{@code runLocked} 一次都没被调用 —— 这是对「闸在锁**之前**」这个
     *       位置决策的直接钉死。顺带证死了「查重也没跑」：查重（{@code countByOrderId}）在
     *       {@code addLocked} 里，而 {@code addLocked} 在锁内。</li>
     * </ol>
     */
    private void assertRejected(String price) throws Exception {
        mockMvc.perform(baseRequest(price))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("价格不合法"));
        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class));
        verify(distributedLockService, never()).runLocked(any(), any(), any());
    }

    /** 放行路径：闸必须对合法值**完全透明** —— 照常取锁、照常 INSERT、文案与改动前逐字一致。 */
    private void assertAccepted(String price) throws Exception {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);
        mockMvc.perform(baseRequest(price))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("订单添加成功"));
        verify(jdbcTemplate, times(1)).update(any(PreparedStatementCreator.class));
    }

    /** 除 Price 外全部固定；形参名逐字对齐 {@code OrderController.orderAdd} 的 {@code @RequestParam}。 */
    private MockHttpServletRequestBuilder baseRequest(String price) {
        return post("/order/orderAdd")
                .param("CustomerId", "SMOKE-CUST-1")
                .param("MerchantId", "SMOKE-MERCH-1")
                .param("OrderId", "SMOKE-PRICE-ORDER-1")
                .param("Price", price)
                .param("ProductInformation", "可乐:SMOKE-PC-1")
                .param("Images", "")
                .param("PayIf", "0")
                .param("DeliveryIf", "0")
                .param("ReceiveIf", "0");
    }

    // ==================== 阳性对照 ====================

    /**
     * <b>阳性对照</b>：合法价仍能落库。没有这一条，下面所有 {@code == 0 次调用} 的断言
     * 都可能是「整个链路压根没通」造成的空转绿（与 C#2 的 {@code GoodsWriteTxAtomicityTest}
     * 里那条阳性对照同一个理由）。
     */
    @Test
    void validPrice_passesTheGuard_andStillWrites() throws Exception {
        assertAccepted("1.5");
    }

    /** 下界是**闭**的（{@code >= 0}）：0 元商品合法（{@code goods.Price} 无下界约束，商家可设 0）。 */
    @Test
    void zero_isAccepted_lowerBoundIsInclusive() throws Exception {
        assertAccepted("0");
    }

    /** 上界是**闭**的（{@code <= MAX_ORDER_PRICE}）：恰好等于上界要放行，否则是「差一」错误。 */
    @Test
    void exactlyUpperBound_isAccepted() throws Exception {
        assertAccepted("10000000");
    }

    // ==================== 拒绝 ====================

    /** 主判据：负价被挡。 */
    @Test
    void negativePrice_isRejected() throws Exception {
        assertRejected("-1");
    }

    /**
     * ⚠️ <b>NaN 是被挡的核心用例 —— 它防的正是「把判据写成负面形式」这个错</b>：
     * {@code NaN < 0} 是 {@code false}，若守卫写成 {@code !(price < 0) && !(price > MAX)}，
     * NaN 会被**放行**（至于放行后是否真能落库，取决于 JDBC 驱动与 MySQL 对特殊浮点值的处理 ——
     * 本条**不声称**已验，本类断言的是「被拒」，不是「改前能落库」）。
     *
     * <p>⚠️ <b>不要把本用例理解成「{@code Float.isFinite} 的守卫」</b>：2026-10-04 突变对照实测，
     * 在**链式正面判据**下删掉 {@code Float.isFinite} ⇒ 本类 <b>0 条红</b>（{@code NaN >= 0f}
     * 已经是 {@code false}，NaN 照样被挡）。挡住 NaN 的是**判据取正面形式**这件事本身：
     * 同一份突变对照里，把判据改写成上面那条负面形式 ⇒ <b>恰 1 条红，就是本用例</b>。
     * 这两条读起来像同一件事，判据却不同 —— 别把它们合并成「isFinite 是 NaN 的防线」。
     */
    @Test
    void nan_isRejected() throws Exception {
        assertRejected("NaN");
    }

    /** {@code Float.valueOf("Infinity")} 合法 ⇒ 不能假设「绑定成功 ⇒ 有限数」。 */
    @Test
    void positiveInfinity_isRejected() throws Exception {
        assertRejected("Infinity");
    }

    /** {@code -Infinity < 0} 为真，负面写法**能**挡住它 —— 所以它不是反例，是覆盖边界。 */
    @Test
    void negativeInfinity_isRejected() throws Exception {
        assertRejected("-Infinity");
    }

    /**
     * 上界之上的**有限**值被挡，且 10000001 与上界在 float 下**可区分**
     * （两者都 &lt; 2^24 = 16777216，整数精确）⇒ 这条不是在测浮点舍入，是在测比较本身。
     */
    @Test
    void aboveUpperBound_isRejected() throws Exception {
        assertRejected("10000001");
    }
}
