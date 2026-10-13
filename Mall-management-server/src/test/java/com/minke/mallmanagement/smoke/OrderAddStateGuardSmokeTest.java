package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C#6 出生状态闸 smoke test：POST /order/orderAdd 的 {@code PayIf / DeliveryIf / ReceiveIf} 闸。
 *
 * <p>被测的东西只有一件：{@code OrderService.orderAdd} 开头那道 {@code OrderState.isInitial} 判断
 * （在 {@code priceRejection} **之后**、{@code runLocked} **之前**）。本类**不**测锁、**不**测查重、
 * **不**测价格闸（后者由 {@link OrderAddPriceGuardSmokeTest} 独立负责）。
 *
 * <p>⚠️ <b>为什么这个类会被 CI 真正执行（本笔的验收证据就靠它）</b>：它继承
 * {@link AbstractSmokeTest}（{@code @WebMvcTest} 切片 + {@code @MockitoBean JdbcTemplate}），
 * **不连 MySQL、不连 Redis**、**不带 {@code A3_ENABLED} 门控** ⇒ 它在 {@code backend} job 的
 * 全量 {@code ./mvnw -B test} 里**真跑**。这是刻意的：A3 的 {@code concurrency} job 是
 * {@code continue-on-error: true}（{@code ci.yml}），**不拦 merge**，故 A3 侧那三条新门禁
 * （{@code A-S4a-0} / {@code A-C6-1} / {@code A-C6-2}）只能算旁证。
 *
 * <h2>【规则：{@code {(0,0,0), (1,0,0)}} —— 两条边都不是可选项】</h2>
 * <ul>
 *   <li><b>为什么必须收 {@code (1,0,0)}</b>：下单页「是否现在付款？」点「是」时前端直传
 *       {@code PayIf=1}（{@code ProductPage.vue:354}），{@code DeliveryIf} / {@code ReceiveIf}
 *       恒为 0（同文件 {@code :342-343}）⇒ 生产**实际只会产生这两个组合**。拒 {@code (1,0,0)}
 *       会当场断掉「下单即付款」这条主流程，而它**不是**伪造态。</li>
 *   <li><b>为什么 {@code (1,1,0)} / {@code (1,0,1)} / {@code (1,1,1)} 必须被拒</b>：这三条
 *       **是合法态**（满足 A-S4a-8 的不变式），也就是说「只收合法态」那种更宽的规则拦不住它们
 *       —— 而 TD-17 正文举的正是 {@code (1,1,1)}「已付款+已发货+已收货」。
 *       ⇒ 判据必须是**初始态白名单**，不能是不变式。</li>
 * </ul>
 * <b>下面 3 条「合法但非初始」的用例就是这条裁决的钉子</b>：将来谁把规则放宽成
 * {@code OrderState.legal()}（或反过来删掉 {@code (1,0,0)}），它们会先红。
 */
class OrderAddStateGuardSmokeTest extends AbstractSmokeTest {

    /** 全部用例共用的订单号 —— 固定值才能对 {@code rejectedAtBirth} 做**精确** verify。 */
    private static final String ORDER = "SMOKE-STATE-ORDER-1";

    /**
     * 拒绝路径的判据，四条一起成立才算过：
     * <ol>
     *   <li>HTTP <b>200</b> + {@code {success:false, msg:"订单状态不合法"}} —— 与全仓业务失败同形
     *       （刻意**不是** 400，理由见 {@code OrderService.stateRejection} 的 javadoc）；</li>
     *   <li><b>零写库</b>：INSERT 经 {@code JdbcClient.update()} 落到
     *       {@code update(PreparedStatementCreator)} 单参重载（见 {@code OrderPayIfUpSmokeTest}
     *       的匹配器说明），故断言它**一次都没被调用**；</li>
     *   <li><b>不取锁</b>：{@code runLocked} 一次都没被调用 —— 这是对「闸在锁**之前**」这个
     *       位置决策的直接钉死（顺带证死「查重也没跑」：查重在 {@code addLocked} 里，而它在锁内）；</li>
     *   <li><b>观测同点</b>：{@code rejectedAtBirth} 被**按原样**调用了一次，三位逐字对齐
     *       —— 拦下了却记错 shape/位值，指标与响应体就会互相矛盾，而且不报错。</li>
     * </ol>
     */
    private void assertRejected(int payIf, int deliveryIf, int receiveIf) throws Exception {
        mockMvc.perform(baseRequest(ORDER, "1.5", payIf, deliveryIf, receiveIf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("订单状态不合法"));
        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class));
        verify(distributedLockService, never()).runLocked(any(), any(), any());
        verify(orderTransitionRecorder, times(1)).rejectedAtBirth(payIf, deliveryIf, receiveIf, ORDER);
    }

    /**
     * 放行路径：闸必须对白名单内的值**完全透明** —— 照常取锁、照常 INSERT、文案与改动前逐字一致。
     *
     * <p>阳性对照不是可有可无的：没有它，上面所有 {@code == 0 次调用} 的断言都可能是
     * 「整个链路压根没通」造成的空转绿（与 B#1 的 {@code validPrice_passesTheGuard_andStillWrites}
     * 同一个理由）。
     */
    private void assertAccepted(int payIf, int deliveryIf, int receiveIf) throws Exception {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);
        mockMvc.perform(baseRequest(ORDER, "1.5", payIf, deliveryIf, receiveIf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("订单添加成功"));
        // ⚠️ **块 7（2026-10-13）起这里是 3 次**（commit 0 时 2 次 / 更早 1 次），如实订正、不是放松：
        //    本夹具 `ProductInformation`（`可乐:SMOKE-PC-1`）恰一段且带 `:` ⇒ 命中回落口径，除订单那次
        //    INSERT 外多写一行 `order_item`；块 7 又对该明细**下单即扣**一行 `goods_sku.Stock`（TD-36）。
        //    次数仍是**精确值**（2 或 4 都会红）。⚠️ 明细判据在 `OrderAddItemsSmokeTest`，本类只钉状态闸。
        verify(jdbcTemplate, times(3)).update(any(PreparedStatementCreator.class));
        // 放行路径不许记「拦截」—— 否则 birth.rejected 会把放行的请求也算进去
        verify(orderTransitionRecorder, never()).rejectedAtBirth(anyInt(), anyInt(), anyInt(), any());
    }

    /** 除 Price 与三个状态位外全部固定；形参名逐字对齐 {@code OrderController.orderAdd} 的 {@code @RequestParam}。 */
    private MockHttpServletRequestBuilder baseRequest(String orderId, String price,
                                                      int payIf, int deliveryIf, int receiveIf) {
        return post("/order/orderAdd")
                .param("CustomerId", "SMOKE-CUST-1")
                .param("MerchantId", "SMOKE-MERCH-1")
                .param("OrderId", orderId)
                .param("Price", price)
                .param("ProductInformation", "可乐:SMOKE-PC-1")
                .param("Images", "")
                .param("PayIf", String.valueOf(payIf))
                .param("DeliveryIf", String.valueOf(deliveryIf))
                .param("ReceiveIf", String.valueOf(receiveIf));
    }

    // ==================== 阳性对照：白名单内的两个初始态 ====================

    /** 下单未付款。前端「是否现在付款？」点「否」即此态。 */
    @Test
    void created_isAccepted() throws Exception {
        assertAccepted(0, 0, 0);
    }

    /**
     * ⚠️ <b>本用例是这道闸最容易被误改的一半</b>：{@code (1,0,0)} 看起来像「非初始态」，
     * 但生产前端点「是」时**就是**直接传 {@code PayIf=1} 造出它（{@code ProductPage.vue:354}）
     * ⇒ 它是**第二个合法初始态**。删掉它 = 弄坏生产。
     */
    @Test
    void paid_isAlsoAccepted_becauseTheFrontendCreatesItDirectly() throws Exception {
        assertAccepted(1, 0, 0);
    }

    // ==================== 拒绝：3 个非法形状（A-S4a-8 的不变式） ====================

    /** 未付款已发货。 */
    @Test
    void illegalDeliveredUnpaid_isRejected() throws Exception {
        assertRejected(0, 1, 0);
    }

    /** 未付款已收货。 */
    @Test
    void illegalReceivedUnpaid_isRejected() throws Exception {
        assertRejected(0, 0, 1);
    }

    /** 未付款已发货已收货 —— TD-17 正文举的另一个例子。 */
    @Test
    void illegalDeliveredReceivedUnpaid_isRejected() throws Exception {
        assertRejected(0, 1, 1);
    }

    // ==================== 拒绝：3 个「合法但非初始」（本裁决的钉子） ====================

    /**
     * ⚠️ {@code (1,1,0)} <b>是合法态</b>（满足 A-S4a-8 的不变式），却**必须被拒** ——
     * 客户端不该能凭一个 POST 就把订单造成「已发货」。若把闸放宽成 {@code OrderState.legal()}，
     * 本用例与下面两条会一起红。
     */
    @Test
    void shipped_isRejected_evenThoughItIsALegalState() throws Exception {
        assertRejected(1, 1, 0);
    }

    /** ⚠️ 同 {@link #shipped_isRejected_evenThoughItIsALegalState}：{@code (1,0,1)} 合法但非初始。 */
    @Test
    void receivedNoDelivery_isRejected_evenThoughItIsALegalState() throws Exception {
        assertRejected(1, 0, 1);
    }

    /**
     * ⚠️ <b>本用例是整道闸存在的理由</b>：{@code (1,1,1)} 是合法态，所以「只挡非法态」那种规则
     * **拦不住它**，而 TD-17 的问题陈述原文就是「调用方可直接插入一条 {@code (1,1,1)} 的
     * 『已付款+已发货+已收货』订单，全程不经过付款/发货/收货」。⇒ 它必须被拒，否则本笔
     * 只能写「不变式前移」，不能写「TD-17 已修」。
     */
    @Test
    void completed_isRejected_evenThoughItIsALegalState() throws Exception {
        assertRejected(1, 1, 1);
    }

    // ==================== 拒绝：k∈{0,1} 之外（TD-17 建议②的另一半） ====================

    /**
     * {@code @RequestParam int} 连 2 都收（TD-17 建议②原文）。它不算「非法形状」也不算
     * 「合法但非初始」，而是**越界**，被 {@code OrderState.isCanonical} 挡下；
     * 记进指标时归一到 {@code non_binary}（否则 tag 基数会随客户端传什么都变）。
     */
    @Test
    void payIfTwo_isRejected_outOfTheBinaryDomain() throws Exception {
        assertRejected(2, 0, 0);
    }

    // ==================== 两闸的顺序（契约，不许对调） ====================

    /**
     * ⚠️ <b>价格非法 + 状态非法 ⇒ 必须返回「价格不合法」</b>。
     *
     * <p>这不是"哪个更便宜"的选择，是**契约**：{@code api-contract.md} 的 B#1 那条已经把
     * 「价格不通过 ⇒ {@code msg="价格不合法"}」写成对外承诺。若把状态闸挪到价格闸之前，
     * 这条请求会改返回 {@code "订单状态不合法"} ⇒ **静默收窄一条已发布的契约**。
     */
    @Test
    void priceGateRunsFirst_whenBothGatesWouldReject() throws Exception {
        mockMvc.perform(baseRequest(ORDER, "NaN", 0, 1, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("价格不合法"));
        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class));
        verify(distributedLockService, never()).runLocked(any(), any(), any());
        // 价格闸先返回 ⇒ 状态闸那条观测**一次都不该发生**（否则指标会把「价格被拒」记成「状态被拒」）
        verify(orderTransitionRecorder, never()).rejectedAtBirth(anyInt(), anyInt(), anyInt(), any());
    }
}
