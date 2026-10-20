package com.minke.mallmanagement.marketing.coupon.controller;

import com.minke.mallmanagement.marketing.coupon.service.CouponService;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端「优惠券」Web 层（C3 块 5 · commit 1）—— 4 个端点：
 * 领券中心 / 我的券包 / 领券 / 一键领取本周全部。
 *
 * <h2>【挂 {@code /marketing}，**不**挂 {@code /customer/**}】</h2>
 * 与 {@code CartController}（{@code /cart}）同款：这是**用户侧的业务域**，不是壳。前端
 * {@code api/marketing.js} 是唯一调用方；四个端点都在**用户端壳**下被调用
 * （{@code HomePage.vue} 的「领券中心」、用户中心的券包弹窗、{@code ProductPage.vue} 的领券块）。
 *
 * <h2>【4 个端点全部要身份：判的是 {@code Account} 入参】</h2>
 * 走 {@link IdentityGuard#requireSelfOrAdminByUsername}（补丁 A1 那条登录名版）——
 * 「券包只能看/领自己的」。
 * <p>⚠️ <b>2026-10-12 之前这里走的是 {@link IdentityGuard#requireSelfOrAdmin}，量的是业务号</b>
 * （当时 {@code Account} = {@code user.customerid}）。随「券改按**账号**口径」（用户裁定「乙」）
 * 改成登录名之后，**旧的判据必须一起换**：{@code requireSelfOrAdmin} 比的是
 * {@code identity.userId()}（业务号），而登录名**永远不等于**它 ⇒ 不换就是对每个非管理员
 * **恒 403**（前端会显示成「无权操作该账户」，而请求本身没错 —— 与补丁 A1 记录的那次
 * 双 403 事故同一个形状）。
 * <p>⚠️ {@code Account} 现在是**登录名**（{@code login.UserName} = {@code user.username}），
 * 不再是 {@code user.customerid}。**列名 / 参数名都不用改**（{@code coupon_grant.ToAccount}
 * 本来就叫这个），变的是**值**。改这个值的原因见 {@code CouponDao} 类注释那一节。
 *
 * <h2>【⚠️ {@code UserStateLookup} 一律走 {@code ObjectProvider}】</h2>
 * 与 {@code MerchantApplicationController} 同款：{@code UserStateLookupService} 是
 * {@code @Service} 而 {@code @WebMvcTest} 切片**不扫**它，且它**刻意不被
 * {@code AbstractSmokeTest} {@code @Import}** ⇒ 裸注入会让整个切片上下文起不来。
 * 取不到 ⇒ {@code getIfAvailable()} 回 {@code null} ⇒ {@code requireSelfOrAdminByUsername}
 * 的第三处 fail-open（见那个方法的 ⚠️）。
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

    /**
     * ⚠️ <b>刻意是 {@link ObjectProvider}、不是裸的 {@code UserStateLookup}</b> —— 理由见类注释
     * 那一节（{@code @WebMvcTest} 切片不扫 {@code @Service}，裸注入会让上下文起不来）。
     */
    private final ObjectProvider<UserStateLookup> userStateLookupProvider;

    public CouponController(CouponService couponService,
                            ObjectProvider<UserStateLookup> userStateLookupProvider) {
        this.couponService = couponService;
        this.userStateLookupProvider = userStateLookupProvider;
    }

    /**
     * 领券中心：可领的券（含「我已领」与「已领完」两个布尔，供前端把按钮画灰）。
     *
     * @param thisWeek {@code "1"} ⇒ 只回**本周新发布的**（主页领券中心）；省略 ⇒ 全部可领
     *                 （用户中心「可领」tab）。**同一个查询的两个筛法**，见 {@code CouponDao.selectClaimable}。
     *                 ⚠️ 可选参数 &gt; 必填 —— 既有调用方（{@code ProductPage.vue} 的领券块）不传它，
     *                 行为与改动前一致（仍是全量领券中心，没被收窄）。
     * @param merchantAccount 非空 ⇒ **只回该商家的商家券**（{@code Scope='merchant'}），
     *                        平台券一条不回。C3 块 5 · A 组给**商品详情页领券区**加的筛
     *                        （那里显本店发的券）；主页 / 用户中心不传它 ⇒ 行为一字未变。
     */
    @PostMapping("/coupons")
    public ResponseEntity<Object> coupons(
            @RequestParam("Account") String Account,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "thisWeek", required = false) String thisWeek,
            @RequestParam(value = "MerchantAccount", required = false) String MerchantAccount,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(couponService.claimable(Account, limit, thisWeek, MerchantAccount)));
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
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
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
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(couponService.claim(CouponId, Account)));
    }

    /**
     * **一键领取本周全部**（主页领券中心右侧那颗按钮）。
     * ⚠️ **不吃 {@code CouponId}** —— 集合由**服务端**定（「本周」是服务端语义），
     * 见 {@code CouponService.claimBatch} 的头两节。逐张走同一条单张路径，返回每张的成败与原因；
     * **部分失败仍回 {@code success:true}**（那里写了三条理由）。
     */
    /**
     * **领取今日会员专属券**（批四第二批 commit 3）。仅 VIP 可领，同一天只能领一张；
     * 券定义由服务端按模板生成，前端不传 {@code CouponId}（与 {@code claimBatch} 同一条理由：
     * 「今日」是服务端语义，别把定义抄到前端）。
     */
    @PostMapping("/coupon/daily")
    public ResponseEntity<Object> daily(
            @RequestParam("Account") String Account,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(couponService.claimDaily(Account)));
    }

    @PostMapping("/coupon/claimBatch")
    public ResponseEntity<Object> claimBatch(
            @RequestParam("Account") String Account,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(couponService.claimBatch(Account)));
    }
}
