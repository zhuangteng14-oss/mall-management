package com.minke.mallmanagement.marketing.coupon.controller;

import com.minke.mallmanagement.marketing.coupon.service.CouponService;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端「优惠券」Web 层（C3 块 5 · commit 1）—— 3 个端点：
 * 领券中心 / 我的券包 / 领券。
 *
 * <h2>【挂 {@code /marketing}，**不**挂 {@code /customer/**}】</h2>
 * 与 {@code CartController}（{@code /cart}）同款：这是**用户侧的业务域**，不是壳。前端
 * {@code api/marketing.js} 是唯一调用方；三个端点都在**用户端壳**下被调用
 * （{@code HomePage.vue} 的「领券中心」与「券包位」）。
 *
 * <h2>【3 个端点全部要身份：判的是 {@code Account} 入参】</h2>
 * 走 {@link IdentityGuard#requireSelfOrAdmin}（与购物车 5 条同款）—— 「券包只能看/领自己的」。
 * ⚠️ {@code Account} 在这里是**用户业务号**（{@code user.customerid}），不是登录名 ——
 * 与 {@code CartController} 的 {@code CustomerId} 是同一个东西、同一个判据。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}】</h2>
 * 同 {@code CartController}：约束异常会逃逸成 400 空体，且撞 {@code xxxControllerIsNotAopProxied}
 * 断言族。参数一律 {@code required=false} + Service 手判。
 *
 * <h2>【⚠️ 新增 Controller 必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 本类的依赖链是 {@code CouponController → CouponService → CouponDao}，
 * {@code AdminCouponController} 还多吃一个 {@code AuditService}（块 1 已在清单里）。
 * {@code @WebMvcTest} 切片**不加载 {@code @Service}/{@code @Repository}** ⇒ 那两个 bean
 * 都要手工进 {@code @Import}，否则整个切片在**上下文加载期**就红（不是本类单独红）。
 */
@RestController
@RequestMapping("/marketing")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 领券中心：可领的券（含「我已领」与「已领完」两个布尔，供前端把按钮画灰）。 */
    @PostMapping("/coupons")
    public ResponseEntity<Object> coupons(
            @RequestParam("Account") String Account,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, Account)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(couponService.claimable(Account, limit)));
    }

    /**
     * 券包（我的券）。排序与分桶在后端做（见 {@code CouponDao.selectMyGrants}）——
     * 前端只按拿到的顺序画，并据 {@code Bucket} 上标签/上色。
     * 回体另带 {@code redDot} / {@code expiringCount}（裁定 #4 ② 的券包红点）。
     */
    @PostMapping("/myCoupons")
    public ResponseEntity<Object> myCoupons(
            @RequestParam("Account") String Account,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, Account)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(couponService.myCoupons(Account)));
    }

    /**
     * 领券。⚠️ **核销**（{@code unused → used}）不在这里 —— 它发生在结算，
     * 由 {@code orderAdd} 带 {@code couponId} 触发（块 5 commit 5）。
     */
    @PostMapping("/coupon/claim")
    public ResponseEntity<Object> claim(
            @RequestParam("Account") String Account,
            @RequestParam("CouponId") String CouponId,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, Account)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(couponService.claim(CouponId, Account)));
    }
}
