package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C3 块 4 · 购物车域（A1 甲「新建 {@code cart} 表」）的 smoke test：
 * {@code POST /cart/{add,update,list,checkout}} 的**入参闸**。
 *
 * <h2>【本类钉的是什么 —— 以及**不**钉什么】</h2>
 * 钉的是**进库之前**就能判定的四条拒绝路径：数量下界 / 数量上界 / 缺参数 / 缺 CustomerId。
 * 它们全部在 {@code CartService} 的**第一段**（校验）返回，**一次库都不碰** ⇒ 用
 * {@code @MockitoBean JdbcTemplate} 的切片就能真跑（同 {@code OrderPayIfUpSmokeTest} 的理由）。
 *
 * <p>⚠️ <b>本类**不**覆盖任何真读写的路径</b>：{@code add} 的「商品不存在」/「已加入」、
 * {@code list} 的返回行、{@code checkout} 的建单，全部要真库（{@code JdbcClient} 打到被 mock 的
 * {@code JdbcTemplate} 上只会抛 NPE，落到 catch 里变成一句不确定的「…失败：…」文案）
 * ⇒ 断言它们只会写出**看起来在测、其实在测 Mockito 默认行为**的用例。
 * 那三条路径的验收归 A3（{@code concurrency}，**continue-on-error ⇒ 按 artifact 的
 * {@code gates.count} 读，别读 job 颜色**）与 VM 上的手工验证。这条边界**写在这里**，
 * 免得下一个人以为「CartSmokeTest 绿了 ⇒ 购物车是好的」。
 *
 * <p>⚠️ 每一类只钉**一条**用例：数量上界与下界是**同一个闸**（{@link com.minke.mallmanagement.cart.service.CartService#MAX_QUANTITY}
 * 的两端），多写几条只是在重复证同一件事。
 */
class CartSmokeTest extends AbstractSmokeTest {

    /** 数量下界：{@code Quantity = 0} ⇒ 拒（移除请走 {@code /cart/remove}，两条语义不混）。 */
    @Test
    void addQuantityZeroIsRejected() throws Exception {
        mockMvc.perform(post("/cart/add")
                        .param("CustomerId", "smoke-customer")
                        .param("MerchantId", "smoke-merchant")
                        .param("ProductCode", "smoke-code")
                        .param("Quantity", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("数量必须大于 0"));
    }

    /**
     * 数量上界：{@code Quantity = MAX_QUANTITY + 1 = 1000} ⇒ 拒。
     *
     * <p>⚠️ 断言串里的 {@code 999} 与 {@code CartService.MAX_QUANTITY} **是两个地方** ——
     * 谁改了常量而没改这里，本用例会红（这正是想要的：上界是一个**对外契约数**，
     * 不是一个能随手调的内部参数）。
     */
    @Test
    void addQuantityOverMaxIsRejected() throws Exception {
        mockMvc.perform(post("/cart/add")
                        .param("CustomerId", "smoke-customer")
                        .param("MerchantId", "smoke-merchant")
                        .param("ProductCode", "smoke-code")
                        .param("Quantity", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("数量最多 999 件"));
    }

    /** 改量走的是**同一个**数量闸（下界一侧）。 */
    @Test
    void updateQuantityZeroIsRejected() throws Exception {
        mockMvc.perform(post("/cart/update")
                        .param("CustomerId", "smoke-customer")
                        .param("ProductCode", "smoke-code")
                        .param("Quantity", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("数量必须大于 0"));
    }

    /** 空 {@code CustomerId} ⇒ 在**碰库之前**就拒（否则会变成一次全表/全空扫描）。 */
    @Test
    void listWithBlankCustomerIsRejected() throws Exception {
        mockMvc.perform(post("/cart/list").param("CustomerId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：CustomerId"));
    }

    /** 结算同理：空 {@code CustomerId} 在碰库之前拒。 */
    @Test
    void checkoutWithBlankCustomerIsRejected() throws Exception {
        mockMvc.perform(post("/cart/checkout").param("CustomerId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：CustomerId"));
    }
}
