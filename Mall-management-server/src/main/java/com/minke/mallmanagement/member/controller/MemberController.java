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
}
