package com.minke.mallmanagement.shop.follow.controller;

import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.shop.follow.service.ShopFollowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺域「关注关系」的 Web 层（C3 块 5 · B 组）—— 3 个端点：
 * 关注 / 取关 / 查关注状态。
 *
 * <h2>【挂 {@code /shop}，**不**挂 {@code /customer/**}】</h2>
 * 与 {@code CartController}（{@code /cart}）同款：这是**用户侧的业务域**，不是壳。
 * 前端 {@code api/shop.js} 是唯一调用方，三个端点都在**用户端**被调用
 * （{@code ShopHome.vue} 的头卡关注按钮 + 关注状态回读；个人中心的店铺信息格跳的也是本页）。
 *
 * <h2>【3 个端点全部要身份：判的是 {@code CustomerId} 入参】</h2>
 * 走 {@link IdentityGuard#requireSelfOrAdmin}（与购物车 5 条、优惠券 4 条同款）——
 * 「关注关系只能是我自己那一份」。
 * ⚠️ {@code CustomerId} 在这里是**用户业务号**（{@code user.customerid}），不是登录名 ——
 * 与 {@code CartController} 的 {@code CustomerId} 是同一个东西、同一个判据。
 * ⇒ **传别人的 {@code CustomerId} 是 403**（不只是读不到别人的数据）。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}】</h2>
 * 同 {@code CartController} / {@code CouponController}：{@code GlobalExceptionHandler} 是
 * {@code assignableTypes} **白名单** ⇒ 约束异常会逃逸成 400 空体，且撞
 * {@code xxxControllerIsNotAopProxied} 断言族。参数一律 {@code required=false} + Service 手判。
 *
 * <p>⚠️ <b>B 档自裁 + 报备：这里与同族的 {@code CartController} / {@code CouponController} 有一处
 * 刻意的差异</b> —— 那两个把「代表调用者自己」的必填入参写成**裸** {@code @RequestParam}
 * （缺参 ⇒ Spring 直接答 400 空体，走不到 Service）；本类三个端点**六个入参全部
 * {@code required=false}**（本笔方案 §3 的口径）。取舍：让「缺参」也走
 * {@code 200 + success:false + msg} 这条本仓**统一**的业务失败通道
 * （{@code ShopFollowService.paramRejection}），而不是在绑定层就换一种错误形状。
 * 代价是「缺 {@code CustomerId}」时 {@link IdentityGuard#requireSelfOrAdmin} 会因
 * {@code paramId == null} 而放行 —— **安全上无影响**：放行之后 Service 立刻以
 * 「缺少参数」拒绝，一次库读写都不会发生（比 400 更早，不比 400 更晚）。
 * 若要回到同族写法，把这两行改回裸 {@code @RequestParam} 即可，其余一字不动。
 *
 * <h2>【⚠️ 新增 Controller 必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 本类的依赖链是 {@code ShopFollowController → ShopFollowService → ShopFollowDao}。
 * {@code @WebMvcTest} 切片**不加载 {@code @Service}/{@code @Repository}** ⇒ 那两个 bean
 * 都要手工进 {@code @Import}，否则整个切片在**上下文加载期**就红（不是本类单独红，
 * 块 1 那次实测是 237 测试 / 77 errors）。
 */
@RestController
@RequestMapping("/shop")
public class ShopFollowController {

    private final ShopFollowService shopFollowService;

    public ShopFollowController(ShopFollowService shopFollowService) {
        this.shopFollowService = shopFollowService;
    }

    /**
     * 关注一家店。**幂等** —— 已关注再点一次仍是 {@code success:true}，表里仍 1 行
     * （撞唯一键被 Service 吞掉，理由见 {@code ShopFollowService} 类注释）。
     */
    @PostMapping("/follow")
    public ResponseEntity<Object> follow(
            @RequestParam(value = "CustomerId", required = false) String CustomerId,
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(shopFollowService.follow(CustomerId, MerchantId)));
    }

    /**
     * 取关（**物理 DELETE 行** —— 用户 2026-10-12 裁定 8，语义见 {@code ShopFollowDao} 类注释）。
     * 本来就没关注 ⇒ 也回 {@code success:true}（同幂等口径）。
     */
    @PostMapping("/unfollow")
    public ResponseEntity<Object> unfollow(
            @RequestParam(value = "CustomerId", required = false) String CustomerId,
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(shopFollowService.unfollow(CustomerId, MerchantId)));
    }

    /**
     * 关注状态 + 粉丝数（{@code ShopHome} 头卡的一次请求 —— 见 {@code ShopFollowService} 类注释
     * 里那张响应形状表）。
     */
    @PostMapping("/followState")
    public ResponseEntity<Object> followState(
            @RequestParam(value = "CustomerId", required = false) String CustomerId,
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(shopFollowService.followState(CustomerId, MerchantId)));
    }
}
