package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单明细（{@code order_item}）smoke test —— <b>可视化段 commit 0</b>。
 *
 * <p>被测的东西只有一件：{@code OrderService.resolveItems} 把一次 {@code orderAdd} 变成
 * <b>几行明细</b>。本类**不**测价格闸、**不**测出生状态闸（那两条各有自己的类，
 * 且它们<b>刻意不</b>管明细）、**不**测锁与查重（{@code DistributedLockServiceTest} 与 A3）。
 *
 * <h2>【怎么在这个切片里「数清明细行数」】</h2>
 * 切片里 {@code JdbcTemplate} 是替身（{@code AbstractSmokeTest} 上那个 {@code @MockitoBean}），
 * 而 {@code OrderDao} 的写侧走 {@code JdbcClient.update()} ⇒ 落到
 * {@code JdbcTemplate.update(PreparedStatementCreator)} <b>单参重载</b>。
 * 于是 <b>调用次数 = 1（订单那一行）+ N（明细行数）+ N（明细逐条的 {@code goods_sku.Stock} 下单即扣）
 * + 1（块 7 commit 2d：本单「库存真扣成过」的标记，只在 N ≥ 1 时多这一次）</b>
 * —— 这是本类全部断言的地基（<b>末两项 = 块 7 新增</b>：{@code OrderService.deductStocks} 与
 * {@code writeItems} 同吃一批 specs，故 <b>有 N 行明细就恰多 N 次 update</b>，一条也不多；
 * 而 {@code markStockDeducted} 是一句**整单一次**的幂等 UPDATE，判据是「这批扣减里至少有一条
 * CAS 命中」⇒ 切片里替身恒回 1 ⇒ <b>N ≥ 1 就恰多 1 次</b>，N = 0 一次都不多 ——
 * 这一项正是本类「明细一条没有 ⇒ 1 次」那几条不受 2d 影响的原因）。
 * ⚠️ 这也是它**能**在 backend 那个阻塞 job 里真跑（不连 MySQL / 不连 Redis、不带
 * {@code A3_ENABLED} 门控）的原因，与 {@code OrderAddPriceGuardSmokeTest} 同一条理由。
 *
 * <h2>【本的判据全部是「精确次数」】</h2>
 * 用 {@code times(n)} 而**不是** {@code atLeast}：多写一行（例如同码没合并、回落口径多推了）
 * 与少写一行（例如回落该触发却没触发）都必须红。
 *
 * <h2>【本类测不到的】</h2>
 * 写进去的**列值**对不对（SQL 不出切片，看不到参数）。单价/小计是否恒等、唯一键是否真防重，
 * 归「默认 profile 手验」（{@code POST /admin/stats/rebuild} 造数那一步）与 A3。
 */
class OrderAddItemsSmokeTest extends AbstractSmokeTest {

    /**
     * 判据三条一起成立：HTTP <b>200</b> + {@code success:true} + 文案逐字
     * {@code "订单添加成功"}（<b>明细的成败不许改动对外文案</b> —— 那是 {@code writeItems}
     * fail-open 的对外可观测面），以及**写库次数精确等于 {@code written}**。
     */
    private void assertOrderLandsWithWrites(int written, MockHttpServletRequestBuilder builder) throws Exception {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);
        mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("订单添加成功"));
        verify(jdbcTemplate, times(written)).update(any(PreparedStatementCreator.class));
    }

    /**
     * 除明细相关的三项外全部固定；形参名逐字对齐 {@code OrderController.orderAdd} 的
     * {@code @RequestParam}。
     *
     * <p>⚠️ {@code OrderId} 固定（同 {@code OrderAddPriceGuardSmokeTest}）：{@code @MockitoBean}
     * 在每个用例后由 Spring 重置，用例内只发一次请求，不需要随机化。
     */
    private MockHttpServletRequestBuilder baseRequest(String price, String quantity, String productInformation) {
        return post("/order/orderAdd")
                .param("CustomerId", "SMOKE-CUST-1")
                .param("MerchantId", "SMOKE-MERCH-1")
                .param("OrderId", "SMOKE-ITEM-ORDER-1")
                .param("Price", price)
                .param("ProductInformation", productInformation)
                .param("Images", "")
                .param("PayIf", "0")
                .param("DeliveryIf", "0")
                .param("ReceiveIf", "0")
                .param("Quantity", quantity);
    }

    // ==================== 一、显式 Items（首选口径） ====================

    /** 显式明细两件 ⇒ 1（订单）+ 2（明细）+ 2（块 7 逐件的库存扣减）+ 1（2d 的整单标记）= 6 次。一单多品在这里第一次成为**数据**。 */
    @Test
    void explicitItems_twoProducts_writeTwoRows() throws Exception {
        assertOrderLandsWithWrites(6, baseRequest("8", "5", "甲:A1；乙:B2").param("Items",
                "[{\"code\":\"A1\",\"name\":\"甲\",\"price\":2.5,\"qty\":2},"
                        + "{\"code\":\"B2\",\"name\":\"乙\",\"price\":1,\"qty\":3}]"));
    }

    /** 显式明细一件 ⇒ 1（订单）+ 1（明细）+ 1（块 7 该件库存扣减）+ 1（2d 的整单标记）= 4 次。 */
    @Test
    void explicitItems_oneProduct_writesOneRow() throws Exception {
        assertOrderLandsWithWrites(4, baseRequest("5", "1", "甲:A1")
                .param("Items", "[{\"code\":\"A1\",\"name\":\"甲\",\"price\":5,\"qty\":1}]"));
    }

    /**
     * 同码两件被**合并成一行** ⇒ 1（订单）+ 1（明细）+ 1（块 7：合并后仍只一条库存扣减）+ 1（2d 的整单标记）= 4 次。
     *
     * <p>⚠️ 这条钉的是「明细整批丢失」那个失效模式：{@code order_item} 的唯一键是
     * {@code (OrderId, ProductCode)}，合并漏了 ⇒ 第 2 行撞键 ⇒ 整批 INSERT 报错 ⇒
     * 被 {@code writeItems} 的 fail-open 吞掉 ⇒ <b>订单好好的、明细一条不剩</b>，
     * 而这一整套断言仍然全绿（因为写库次数只数「尝试了几次 update」，不数成功几行）。
     * 所以这里的 {@code 4}（= 1 + 1 + 1 + 1，明细与扣减**各只有一行**）是「合并确实发生过」的**唯一**切片内证据。
     */
    @Test
    void explicitItems_sameCodeTwice_mergedIntoOneRow() throws Exception {
        assertOrderLandsWithWrites(4, baseRequest("10", "3", "甲:A1")
                .param("Items", "[{\"code\":\"A1\",\"name\":\"甲\",\"price\":2.5,\"qty\":1},"
                        + "{\"code\":\"A1\",\"name\":\"甲\",\"price\":2.5,\"qty\":2}]"));
    }

    /**
     * 缺 {@code code} 的那一条被丢掉，另一条照写 ⇒ 1（订单）+ 1（明细）+ 1（块 7：只有留下那条扣）+ 1（2d 的整单标记）= 4 次。
     *
     * <p>⚠️ 「丢一条、留一条」而不是「整批不写」是刻意的：没有码就没法把这一件挂到商品维度，
     * 但**其余几件是好的** —— 为一条坏数据丢掉整单明细是「错数据比没数据更坏」的反面误用。
     */
    @Test
    void explicitItems_rowWithoutCode_isDroppedTheOthersLive() throws Exception {
        assertOrderLandsWithWrites(4, baseRequest("5", "1", "甲:A1")
                .param("Items", "[{\"name\":\"没有码\",\"price\":9,\"qty\":1},"
                        + "{\"code\":\"A1\",\"name\":\"甲\",\"price\":5,\"qty\":1}]"));
    }

    // ==================== 二、不传 Items 时的两种下落 ====================

    /**
     * 坏 JSON ⇒ 记 WARN、显式口径产出空 ⇒ **继续走回落口径**（本夹具的 {@code 甲:A1} 恰一段）
     * ⇒ 1（订单）+ 1（明细）+ 1（块 7：回落出的那件扣减）+ 1（2d 的整单标记）= 4 次。
     *
     * <p>⚠️ 这条钉的是「坏 JSON **不等于** 不写明细」：{@code parseExplicitItems} 返回空表是
     * 「这一路没产出」，不是「本次不许有明细」—— 两路是**串联**的（见 {@code resolveItems}）。
     */
    @Test
    void malformedItemsJson_fallsBackToProductInformation() throws Exception {
        assertOrderLandsWithWrites(4, baseRequest("5", "1", "甲:A1").param("Items", "{不是 JSON"));
    }

    /**
     * 坏 JSON <b>且</b>回落也推不出（{@code ProductInformation} 没有 {@code :}）⇒ 订单照落、
     * 明细一条不写 ⇒ 1 次。
     *
     * <p>⚠️ 这是「不能阻碍旧功能运作」在明细这条路上的落点：两条路都废掉时，
     * 对外仍是 {@code success:true} + 文案逐字，**不抛异常、不改文案**。
     */
    @Test
    void malformedItemsJson_andUnparsableProductInformation_writesNothing() throws Exception {
        assertOrderLandsWithWrites(1, baseRequest("5", "1", "可乐").param("Items", "{不是 JSON"));
    }

    /** 空 {@code Items}（前端 {@code build()} 把 {@code undefined} 转成空串就是这一形状）⇒ 走回落口径。 */
    @Test
    void blankItems_paramPresentButEmpty_fallsBack() throws Exception {
        assertOrderLandsWithWrites(4, baseRequest("10", "2", "可乐:PC-1").param("Items", ""));
    }

    /**
     * 回落：{@code ProductInformation} **恰一段且带 {@code :}** ⇒ 推得出一件
     * （码 = 最后一个 {@code :} 之后，数量 = {@code Quantity}，单价 = {@code Price ÷ Quantity}）
     * ⇒ 1（订单）+ 1（明细）+ 1（块 7：该件库存扣减）+ 1（2d 的整单标记）= 4 次。购物车结算在 commit 0b 之前走的正是这一形状。
     */
    @Test
    void fallback_singleSegmentProductInformation_writesOneRow() throws Exception {
        assertOrderLandsWithWrites(4, baseRequest("10", "2", "可乐:PC-1"));
    }

    /**
     * 回落：{@code ProductInformation} **多段**（一单多品）⇒ 单价不可反推（{@code Price} 是小计、
     * {@code Quantity} 是总件数，商只是加权均价）⇒ <b>一条明细都不写</b> ⇒ 1 次。
     *
     * <p>⚠️ 这条是「宁可不写」的正当性所在：推出来的行是**脏数据**，会污染商品榜。
     */
    @Test
    void fallback_multiSegmentProductInformation_writesNothing() throws Exception {
        assertOrderLandsWithWrites(1, baseRequest("8", "5", "甲:A1；乙:B2"));
    }

    /** 回落：{@code ProductInformation} **没有 `:`** ⇒ 推不出码 ⇒ 不写 ⇒ 1 次。 */
    @Test
    void fallback_productInformationWithoutColon_writesNothing() throws Exception {
        assertOrderLandsWithWrites(1, baseRequest("8", "5", "可乐"));
    }

    /** 回落：{@code ProductInformation} 以 {@code :} 结尾（码为空）⇒ 不写 ⇒ 1 次。 */
    @Test
    void fallback_productInformationEndsWithColon_writesNothing() throws Exception {
        assertOrderLandsWithWrites(1, baseRequest("8", "5", "可乐:"));
    }

    /** 回落：{@code ProductInformation} 为空 ⇒ 不写 ⇒ 1 次。 */
    @Test
    void fallback_blankProductInformation_writesNothing() throws Exception {
        assertOrderLandsWithWrites(1, baseRequest("8", "5", ""));
    }
}
