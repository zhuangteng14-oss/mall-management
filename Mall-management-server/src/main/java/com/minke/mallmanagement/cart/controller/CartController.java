package com.minke.mallmanagement.cart.controller;

import com.minke.mallmanagement.cart.service.CartService;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车域的 Web 层（C3 块 4 · A1 甲）—— 5 个端点：
 * {@code add} / {@code update} / {@code remove} / {@code list} / {@code checkout}。
 *
 * <h2>【与既有 8 个订单端点的关系：**不重复、不覆盖**】</h2>
 * 「我的订单」tab 用的仍是既有的 {@code /order/orderSelect}（未完成）与
 * {@code /order/historicalOrdersSelect}（已完成）—— **本类不重做它们**。
 * 本类只管 {@code cart} 表那张**真购物车**（A1 甲新建的表）。
 *
 * <h2>【5 个端点全部要身份：判的是 {@code CustomerId} 入参】</h2>
 * 全部走 {@link IdentityGuard#requireSelfOrAdmin}（与既有 5 个订单端点同款）——
 * 「购物车只能看/改自己的」。⚠️ 与那 5 个不同的一点：本类**每个**端点都判，
 * 没有「入参只有 orderId 所以判不了」那类例外（购物车的行主键就是
 * {@code (CustomerId, ProductCode)}，身份天然在入参里）。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}】</h2>
 * 同 {@code AdminTemplateController} 的警告：本仓的约束异常会逃逸成 400 空体
 * （{@code GlobalExceptionHandler} 的类级白名单不含本 Controller），
 * 且会撞 {@code xxxControllerIsNotAopProxied} 断言族。参数一律 {@code required=false} + Service 手判。
 *
 * <h2>【⚠️ 新增 Controller 必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 本类的依赖链是 {@code CartController → CartService → CartDao}，而
 * {@code CartService} 还吃 {@code OrderService}（结算用它建单）。{@code @WebMvcTest} 切片
 * **不加载 {@code @Service}/{@code @Repository}** ⇒ 三个 bean 都要手工进 {@code @Import}，
 * 否则整个切片在**上下文加载期**就红（不是本类单独红）。
 */
@RestController
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /** 加入购物车。{@code Quantity} 缺省 1；已在车里 ⇒ 合并数量（不新开行）。 */
    @PostMapping("/add")
    public ResponseEntity<Object> add(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("ProductCode") String ProductCode,
            @RequestParam(value = "Quantity", required = false) Integer Quantity,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：只能往**自己**的车里加（管理员豁免）。判 CustomerId ——
        //    与 orderAdd 同理，MerchantId 是**对端**（卖给我东西的人），当然要填别人的，不判。
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(cartService.add(CustomerId, MerchantId, ProductCode, Quantity)));
    }

    /** 改量（绝对赋值）。数量 < 1 会被拒 —— 移除请走 {@code /cart/remove}。 */
    @PostMapping("/update")
    public ResponseEntity<Object> update(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam("ProductCode") String ProductCode,
            @RequestParam(value = "Quantity", required = false) Integer Quantity,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(cartService.update(CustomerId, ProductCode, Quantity)));
    }

    /** 移除一行（**物理 DELETE** —— 全仓对规则 v2 的唯一破例，理由见 {@code CartDao} 类注释）。 */
    @PostMapping("/remove")
    public ResponseEntity<Object> remove(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam("ProductCode") String ProductCode,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(cartService.remove(CustomerId, ProductCode)));
    }

    /** 整车（展示用）。返回 {@code {success, list}}。 */
    @PostMapping("/list")
    public ResponseEntity<Object> list(
            @RequestParam("CustomerId") String CustomerId,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(cartService.list(CustomerId)));
    }

    /**
     * 结算：**按商家分组建单**（走 {@code OrderService.orderAdd} 的锁与五道闸），一组成功清这一组。
     * ⚠️ 可视化段 commit 0b 改：**改前逐行建单**（N 行 ⇒ N 张）；现 N 行 ⇒ **M 张**（M = 商家数）。
     * <p>{@code ProductCode} 不传 ⇒ 结算**整车**；传了 ⇒ 只结算那一行（那一行自然只属一个商家）。
     *
     * <p>⚠️ C3 块 5 commit 5：多一个可选的 {@code CouponId}（结算用券）。不传 ⇒ 与加它之前
     * **逐字节同行为**。多商家整车 + 带券 ⇒ {@code CartService.checkout} 直接回失败
     * （{@code coupon_grant} 的核销在 {@code orderAdd} 里，一张券只抵一笔订单）。
     */
    @PostMapping("/checkout")
    public ResponseEntity<Object> checkout(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam(value = "ProductCode", required = false) String ProductCode,
            @RequestParam(value = "CouponId", required = false, defaultValue = "") String CouponId,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(cartService.checkout(CustomerId, ProductCode, CouponId)));
    }
}
