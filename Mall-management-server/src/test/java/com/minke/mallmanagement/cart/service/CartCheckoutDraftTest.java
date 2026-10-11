package com.minke.mallmanagement.cart.service;

import com.minke.mallmanagement.cart.service.CartService.CheckoutLine;
import com.minke.mallmanagement.cart.service.CartService.OrderDraft;
import com.minke.mallmanagement.order.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 购物车结算的**分组与聚合**（可视化段 commit 0b）—— 普通 JUnit，**不起 Spring、不碰库**。
 *
 * <h2>【为什么这条逻辑必须单测，而 checkout 本身测不了】</h2>
 * {@code checkout} 的建单路径在 smoke 切片里够不着：{@code JdbcClient} 打到被 mock 的
 * {@code JdbcTemplate} 上只会抛 NPE，断言它等于断言 Mockito 的默认行为
 * （{@code CartSmokeTest} 的类注释写着同一条边界，它只覆盖「查库之前」的拒绝路径）。
 * 而本笔**唯一的业务新逻辑**恰好是「怎么把 N 行并成 M 单」，所以它被抽成
 * {@link CartService#groupByMerchant} 与 {@link CartService#draftOf} 两个纯函数，
 * 由本类直接钉住。**集成层（真 SQL / 真锁 / 真订单行数）不在这里**，归默认 profile 手验与 A3。
 *
 * <h2>【改前的行为，写下来免得被后人当成本来如此】</h2>
 * commit 0b 之前，{@code checkout} 每行各生成一个 {@code OrderId}、各调一次 {@code orderAdd}
 * ⇒ 一辆 3 行的车生成 **3 张互相独立的订单**。本类的分组断言就是在钉「那个行为已经被取代」。
 */
class CartCheckoutDraftTest {

    // ==================== 一、分组 ====================

    /**
     * 两商家、行序交错 ⇒ **两组**，且**顺序 = 首次出现顺序**（{@code LinkedHashMap}），
     * 组内**保持原行序**。
     *
     * <p>⚠️ 这一条钉的是「建单顺序 = 购物车里的顺序」：前端 {@code ShoppingCart.vue} 按
     * {@code MerchantId} 画店铺分组（{@code ORDER BY c.Id ASC} 出库），若分组用了
     * {@code HashMap}，生成的订单顺序会在行数一多时跳来跳去，而**没有任何断言会红**。
     */
    @Test
    @DisplayName("交错的两商家 ⇒ 两组，顺序与组内行序都保持首次出现序")
    void groupByMerchant_keepsFirstSeenOrder_andRowOrderWithinGroup() {
        Map<String, List<CheckoutLine>> groups = CartService.groupByMerchant(List.of(
                line("M1", "A1"), line("M2", "B2"), line("M1", "A2"), line("M3", "C1"), line("M2", "B3")));

        assertThat(groups.keySet()).containsExactly("M1", "M2", "M3");
        assertThat(groups.get("M1")).extracting(CheckoutLine::code).containsExactly("A1", "A2");
        assertThat(groups.get("M2")).extracting(CheckoutLine::code).containsExactly("B2", "B3");
        assertThat(groups.get("M3")).extracting(CheckoutLine::code).containsExactly("C1");
    }

    /**
     * 商家号为 {@code null} ⇒ 自成一组（键 {@code ""}），**不并进**任何有名字的商家组。
     *
     * <p>⚠️ 这是「宁可多开一单，也不把两件来路不明的商品并成一笔」：{@code cart.MerchantId} 是
     * {@code NOT NULL}，这形状理论到不了；真到了，并进去 = 让 M1 的订单里混进一件不属于它的商品，
     * 而订单一旦落库就**不可逆**（订单链禁 {@code @Transactional}）。
     */
    @Test
    @DisplayName("商家号为 null ⇒ 自成一组，不被并进别的商家")
    void groupByMerchant_nullMerchantIsItsOwnGroup() {
        Map<String, List<CheckoutLine>> groups = CartService.groupByMerchant(List.of(
                line("M1", "A1"), line(null, "X1"), line("M1", "A2")));

        assertThat(groups.keySet()).containsExactly("M1", "");
        assertThat(groups.get("M1")).extracting(CheckoutLine::code).containsExactly("A1", "A2");
        assertThat(groups.get("")).extracting(CheckoutLine::code).containsExactly("X1");
    }

    // ==================== 二、聚合：单行组（改前改后必须逐字同值） ====================

    /**
     * **单行组**的三项聚合值 —— 这一条是「单件车的车结算没被本笔改动」的判据：
     * {@code price = 单价 × 数量}、{@code quantity = 数量}、{@code productInformation = 名:码}，
     * 与改前那一行**逐字相同**（改前也是 {@code goodName + ":" + code}）。
     */
    @Test
    @DisplayName("单行组 ⇒ 三个聚合值与改前的单品形态逐字同值")
    void draftOf_singleLine_matchesTheLegacySingleItemShape() {
        OrderDraft draft = CartService.draftOf(List.of(line("M1", "A1", "甲", 2.5, 3, "img/a.png")));

        assertThat(draft.price()).isEqualTo(7.5f);
        assertThat(draft.quantity()).isEqualTo(3);
        assertThat(draft.productInformation()).isEqualTo("甲:A1");
        assertThat(draft.codesJoined()).isEqualTo("A1");
        assertThat(draft.images()).isEqualTo("img/a.png");
        assertThat(draft.items()).containsExactly(new OrderService.ItemSpec("A1", "甲", 2.5, 3));
    }

    // ==================== 三、聚合：多行组（本笔的新形状） ====================

    /**
     * 同商家三件 ⇒ {@code price} 合计、{@code quantity} 总件数、
     * {@code productInformation} 以「；」相连、明细逐行保序、{@code images} 取**第一行**。
     *
     * <p>⚠️ {@code productInformation} 的**分隔符必须是「；」**，这不是显示口径：它同时是
     * {@code OrderService} 的**回落口径**（{@code Items} 没发出去时按它重建明细）。
     * 两边形状不一致 ⇒ 明细**静默丢失**，而订单本身照常成功。本断言是那条路的唯一钉子。
     */
    @Test
    @DisplayName("多行组 ⇒ 合计/总件数/「；」相连/明细保序/取首行图")
    void draftOf_multiLine_aggregatesAndJoinsWithFullWidthSemicolon() {
        OrderDraft draft = CartService.draftOf(List.of(
                line("M1", "A1", "甲", 2.0, 3, "img/a.png"),
                line("M1", "B2", "乙", 1.5, 2, "img/b.png")));

        assertThat(draft.price()).isEqualTo(9.0f);          // 2.0×3 + 1.5×2
        assertThat(draft.quantity()).isEqualTo(5);
        assertThat(draft.productInformation()).isEqualTo("甲:A1；乙:B2");
        assertThat(draft.codesJoined()).isEqualTo("A1；B2");
        assertThat(draft.images()).isEqualTo("img/a.png");   // 首行的图，不是最后一行的
        assertThat(draft.items()).containsExactly(
                new OrderService.ItemSpec("A1", "甲", 2.0, 3),
                new OrderService.ItemSpec("B2", "乙", 1.5, 2));
    }

    /**
     * 一行里 {@code 名:码} 的**冒号只有一个、且是半角 `:`** —— 与前端
     * {@code ProductPage.buildProductInformation} 同款。若这里改成全角「：」，回落口径会
     * 认不出（回落按**半角** `:` 切），两条路就此分家。
     */
    @Test
    @DisplayName("单品信息用半角冒号，与前端 buildProductInformation 同形")
    void draftOf_singleSegmentUsesHalfWidthColon() {
        OrderDraft draft = CartService.draftOf(List.of(line("M1", "PC-1", "可乐", 5.0, 2, null)));

        assertThat(draft.productInformation()).isEqualTo("可乐:PC-1");
        assertThat(draft.productInformation()).contains(":");
        assertThat(draft.productInformation()).doesNotContain("：");
    }

    /** 图快照为 {@code null} ⇒ 落成空串（不是 {@code "null"}，也不是 {@code null}）。 */
    @Test
    @DisplayName("goodImg 为 null ⇒ images 落成空串")
    void draftOf_nullImage_becomesEmptyString() {
        OrderDraft draft = CartService.draftOf(List.of(line("M1", "A1", "甲", 1.0, 1, null)));

        assertThat(draft.images()).isEmpty();
    }

    // ==================== 四、金额：只在最后窄化一次 ====================

    /**
     * ⚠️ <b>本类里唯一一条会「算错但不报错」的断言</b>：{@code order.Price} 是 {@code float}，
     * 而「逐行窄化成 float 再相加」与「用 double 加完只在最后窄化一次」**不是同一个数**。
     *
     * <p>本夹具（{@code 0.1×3} 与 {@code 0.29×3} 两组）就是特意挑的分歧点：
     * <ul>
     *   <li>本笔的口径（double 累加、最后窄化一次）⇒ {@code 1.1699999570846558f}；</li>
     *   <li>逐行窄化再相加 ⇒ {@code 1.1700000762939453f}。</li>
     * </ul>
     * 整组是一个金额，只该舍入一次 ⇒ 走前者。若有人把累加改回 {@code float}，
     * 本用例会**精确地**红在这里。
     */
    @Test
    @DisplayName("金额用 double 累加、只在最后窄化一次（0.1×3 与 0.29×3 就是分歧点）")
    void draftOf_accumulatesInDouble_narrowsOnceAtTheEnd() {
        OrderDraft draft = CartService.draftOf(List.of(
                line("M1", "A1", "甲", 0.1, 3, null),
                line("M1", "B2", "乙", 0.29, 3, null)));

        assertThat(draft.price()).isEqualTo(1.1699999570846558f);
        assertThat(draft.price()).isNotEqualTo(1.1700000762939453f);   // 逐行窄化会得到这个
    }

    // ==================== 夹具 ====================

    /** 缺省钱的重载：只有商家与码（用于纯分组用例）。 */
    private static CheckoutLine line(String merchantId, String code) {
        return line(merchantId, code, "名-" + code, 1.0, 1, null);
    }

    private static CheckoutLine line(String merchantId, String code, String goodName,
                                     double unit, int qty, String goodImg) {
        return new CheckoutLine(merchantId, code, goodName, unit, qty, goodImg);
    }
}
