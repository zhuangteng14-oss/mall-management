package com.minke.mallmanagement.member.controller;

import com.minke.mallmanagement.member.service.MemberService;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员 / 积分用户端（第三批㉘，2026-10-19）—— 4 个端点：
 * 等级矩阵 / 概览 / 签到 / 积分流水。
 *
 * <p>等级矩阵是**公开配置**，不判身份；其余三个判 {@code CustomerId}（业务号）。
 * 签到的时间判据全部在库侧（{@code CURDATE()}），Java 不构造日期 —— 与
 * {@code CouponDao} 的时间口径一致，避免 JVM 与库时钟差 8 小时。
 */
@RestController
@RequestMapping("/member")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** V0–V6 权益矩阵（前端会员中心直接渲染）。 */
    @PostMapping("/levels")
    public ResponseEntity<Object> levels() {
        return ResponseEntity.ok(Result.of(memberService.levels()));
    }

    @PostMapping("/overview")
    public ResponseEntity<Object> overview(@RequestParam("CustomerId") String CustomerId,
                                           HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(memberService.overview(CustomerId)));
    }

    @PostMapping("/signin")
    public ResponseEntity<Object> signin(@RequestParam("CustomerId") String CustomerId,
                                         HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(memberService.signin(CustomerId)));
    }

    @PostMapping("/points/logs")
    public ResponseEntity<Object> logs(@RequestParam("CustomerId") String CustomerId,
                                       @RequestParam(value = "limit", required = false) Integer limit,
                                       HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(memberService.logs(CustomerId, limit)));
    }

    /** 积分商城：用积分兑换一张不限时优惠券。 */
    @PostMapping("/points/exchange")
    public ResponseEntity<Object> exchange(@RequestParam("CustomerId") String CustomerId,
                                           @RequestParam(value = "Product", required = false) String Product,
                                           @RequestParam(value = "RequestId", required = false) String RequestId,
                                           HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(memberService.exchangeCoupon(CustomerId, Product, RequestId)));
    }

    /** 积分商城：1 积分 = 1 成长值。 */
    @PostMapping("/points/growth")
    public ResponseEntity<Object> growth(@RequestParam("CustomerId") String CustomerId,
                                         @RequestParam(value = "Points", required = false) String Points,
                                         @RequestParam(value = "RequestId", required = false) String RequestId,
                                         HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        int points;
        try {
            points = Points == null || Points.trim().isEmpty() ? 0 : Integer.parseInt(Points.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.ok(Result.of(fail("积分数量不合法")));
        }
        return ResponseEntity.ok(Result.of(memberService.exchangeGrowth(CustomerId, points, RequestId)));
    }

    private static java.util.Map<String, Object> fail(String msg) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }
}
