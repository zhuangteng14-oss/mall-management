package com.minke.mallmanagement.shop.profile.controller;

import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.shop.profile.service.ShopProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺域「店铺档案」的 Web 层（C3 块 5 · B 组续笔）—— 1 个端点：公开档案。
 *
 * <h2>【⚠️ 本端点是 {@code /shop/**} 下**唯一**一条公开路径 —— 刻意不挂闸】</h2>
 * 同族的 {@code ShopFollowController}（{@code /shop/follow} / {@code unfollow} / {@code followState}）
 * 三条**全部**跑 {@code IdentityGuard.requireSelfOrAdmin}；本类**一条都不跑**，因为
 * **店铺主页未登录也要能看**（它是商品详情页「进店逛逛」的落点，而详情页本身是公开面
 * —— {@code router/index.js} 里 {@code /product} 与 {@code /shop} 都挂 {@code meta.public}）。
 * <p>这不是「顺手放宽」：{@code /shop/profile} 已登记进 {@code WebAuthConfig.AUTH_WHITELIST}
 * （**12 → 13 条**），是**显式的公开面决策**，四处文案同笔改（见该常量与其 smoke 断言）。
 * ⚠️ 反过来说：**别**因为本类没有闸，就以为 {@code IdentityGuard} 在这一域可以省 ——
 * 那是另一条路径的事。
 *
 * <h2>【本端点返回的都是「谁都能看」的东西】</h2>
 * 登录名、注册时刻、入驻审核状态 —— 都是店铺主页头卡上公开挂着的牌子。
 * ⚠️ **不含**任何买家侧数据（谁关注了这家店、买了什么），那些仍只在身份面。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}】</h2>
 * 同 {@code CartController} / {@code CouponController} / {@code ShopFollowController}：
 * {@code GlobalExceptionHandler} 是 {@code assignableTypes} **白名单** ⇒ 约束异常会逃逸成
 * 400 空体，且撞 {@code xxxControllerIsNotAopProxied} 断言族。参数一律 {@code required=false}
 * + Service 手判（缺参走 {@code 200 + success:false + msg} 这条本仓**统一**的业务失败通道）。
 *
 * <h2>【⚠️ 新增 Controller 必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 依赖链是 {@code ShopProfileController → ShopProfileService → ShopProfileDao}。
 * {@code @WebMvcTest} 切片**不加载 {@code @Service}/{@code @Repository}** ⇒ 那两个 bean
 * 都要手工进 {@code @Import}，否则整个切片在**上下文加载期**就红（**第九次同一处地雷**，
 * 块 1 那次实测是 237 测试 / 77 errors）。
 */
@RestController
@RequestMapping("/shop")
public class ShopProfileController {

    private final ShopProfileService shopProfileService;

    public ShopProfileController(ShopProfileService shopProfileService) {
        this.shopProfileService = shopProfileService;
    }

    /**
     * 一家店的公开档案：登录名 / 注册时刻 / 入驻审核状态（{@code ShopHome} 头卡的一次请求）。
     *
     * <p>⚠️ 与 {@code followState} 分工不同：那条回「**我和这家店的关系**」（要身份、要 CustomerId），
     * 本条回「**这家店本身是谁**」（不要身份、只要 MerchantId）。
     */
    @PostMapping("/profile")
    public ResponseEntity<Object> profile(
            @RequestParam(value = "MerchantId", required = false) String MerchantId
    ) {
        return ResponseEntity.ok(Result.of(shopProfileService.profile(MerchantId)));
    }
}
