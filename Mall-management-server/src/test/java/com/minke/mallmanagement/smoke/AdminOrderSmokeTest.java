package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C3 块 4 · 管理端订单治理的 smoke test：{@code POST /admin/order/{list,detail,intervene}}
 * 的**筛选白名单与动作白名单**。
 *
 * <h2>【为什么这两条白名单值得单独钉】</h2>
 * 它们守的是同一种错：**把「用错」说成「事实」**。
 * <ul>
 *   <li>{@code status} 筛了个不存在的词 ⇒ 若静默忽略该条件，返回的就是**全量**，
 *       而「筛了空」与「筛了全部」在画面上都是「有数据的表」—— 管理员会拿着错的数据下判断。</li>
 *   <li>{@code Action} 传了个拼错的词 ⇒ 若静默当成「不该做的事」或「未知即忽略」，
 *       管理员会以为自己做成了一次介入。故未知动作**必须报错**。</li>
 * </ul>
 *
 * <h2>【{@code refund} 走的是另一条路：**知道但不做**】</h2>
 * {@code Action = refund} **不是**未知动作 —— 用户 2026-10-10 的 A3 裁决就是「强制退款只登记不实现」
 * （{@code order} 表没有资金态，{@code PayIf} 回退会破三位单调性）。故它回的是一条
 * **说清原因**的失败文案，与「未知动作」**措辞不同**。
 * <p>⚠️ 这两条的文案差一点点，很容易在重构里被合并成一条 —— 本类就是把它们的**区别**钉住的地方：
 * 「不认识」和「知道但不做」对使用者是完全不同的两件事。
 *
 * <p>⚠️ 同样**不覆盖**任何真查库/真改库的路径（{@code list} 的实际筛选、{@code intervene} 的成功分支）
 * —— 那要真库，归 A3 与 VM 手工验证。边界同 {@link CartSmokeTest}。
 */
class AdminOrderSmokeTest extends AbstractSmokeTest {

    /** 未知状态筛选值 ⇒ **报错**，不静默降级成「不筛」。 */
    @Test
    void listWithUnknownStatusIsRejected() throws Exception {
        mockMvc.perform(post("/admin/order/list").param("status", "weird"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(
                        "未知的状态筛选值：weird（只认 unpaid / paid / shipped / completed / abnormal / all）"));
    }

    /** 未知介入动作 ⇒ 报错，且把合法取值列出来（使用者不必去读源码）。 */
    @Test
    void interveneWithUnknownActionIsRejected() throws Exception {
        mockMvc.perform(post("/admin/order/intervene")
                        .param("OrderId", "smoke-order")
                        .param("Action", "foo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未知的介入动作：foo（只认 pay / deliver / receive）"));
    }

    /** {@code refund} ⇒ **另一条文案**：说清为什么不做（不是「不认识」）。 */
    @Test
    void interveneRefundIsRefusedWithReason() throws Exception {
        mockMvc.perform(post("/admin/order/intervene")
                        .param("OrderId", "smoke-order")
                        .param("Action", "refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(
                        "强制退款本块未实现：order 表没有资金态，PayIf 回退会破三位单调性（登记待办）"));
    }

    /** 缺 {@code OrderId} ⇒ 在碰库之前拒。 */
    @Test
    void interveneWithoutOrderIdIsRejected() throws Exception {
        mockMvc.perform(post("/admin/order/intervene")
                        .param("OrderId", "")
                        .param("Action", "pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：OrderId"));
    }

    /** 详情缺 {@code OrderId} ⇒ 同样在碰库之前拒。 */
    @Test
    void detailWithoutOrderIdIsRejected() throws Exception {
        mockMvc.perform(post("/admin/order/detail").param("OrderId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：OrderId"));
    }
}
