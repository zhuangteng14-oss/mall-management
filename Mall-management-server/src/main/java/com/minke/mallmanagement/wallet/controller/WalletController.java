package com.minke.mallmanagement.wallet.controller;

import com.minke.mallmanagement.shared.Money;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 钱包用户端（第三批㉙，2026-10-19）—— 概览 / 流水 / 沙箱充值 / 提现占位，共 4 个端点。
 *
 * <p>全部走 {@link IdentityGuard#requireSelfOrAdmin}：{@code CustomerId} 是业务号，
 * 与其余客户侧端点同一口径。{@code app.auth.enabled=false} 的 a3 / dev profile 下
 * 闸与身份判据都放行 —— smoke 与 A3 不受影响。
 *
 * <p>⚠️ 金额一律经 {@link Money#parse(String)} 解析，**不用** {@code @RequestParam BigDecimal}
 * 直接绑定：绑定失败会逃逸成 400 空体，而全仓业务失败的口径是 200 + {@code {success:false,msg}}。
 */
@RestController
@RequestMapping("/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/overview")
    public ResponseEntity<Object> overview(@RequestParam("CustomerId") String CustomerId,
                                           HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(walletService.overview(CustomerId)));
    }

    @PostMapping("/logs")
    public ResponseEntity<Object> logs(@RequestParam("CustomerId") String CustomerId,
                                       @RequestParam(value = "limit", required = false) Integer limit,
                                       HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(walletService.logs(CustomerId, limit)));
    }

    /**
     * 沙箱充值。{@code RequestId} 由前端生成并**在重试时复用** —— 这就是幂等键。
     * {@code ClientIp} 服务端取 {@code request.getRemoteAddr()}，不接受入参（否则可伪造绕过风控）。
     */
    @PostMapping("/recharge")
    public ResponseEntity<Object> recharge(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam(value = "Amount", required = false) String Amount,
            @RequestParam(value = "RequestId", required = false) String RequestId,
            HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        BigDecimal amount = Money.parse(Amount);
        if (amount == null) {
            return ResponseEntity.ok(Result.of(fail("充值金额不合法")));
        }
        return ResponseEntity.ok(Result.of(
                walletService.recharge(CustomerId, amount, RequestId, request.getRemoteAddr())));
    }

    /** 提现占位：只回答「未开放」，不写任何单据。 */
    @PostMapping("/withdraw")
    public ResponseEntity<Object> withdraw(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam(value = "Amount", required = false) String Amount,
            HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(walletService.withdraw(CustomerId, Money.parse(Amount))));
    }

    private static java.util.Map<String, Object> fail(String msg) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }
}
