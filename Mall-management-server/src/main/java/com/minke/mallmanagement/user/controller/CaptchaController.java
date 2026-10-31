package com.minke.mallmanagement.user.controller;

import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.user.service.CaptchaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 图形验证码端点（2026-10-31「上线前扫雷」）—— 未登录可达，故在 {@code WebAuthConfig.AUTH_WHITELIST}。
 *
 * <p>响应：{@code {"success":true,"captchaId":"…","image":"data:image/png;base64,…"}}。
 * 前端拿 {@code image} 直接塞 {@code <img src>}，登录时把 {@code captchaId + captchaCode} 一起提交
 * 给 {@code /user/loginSelect}。⚠️ 本端点**不返回验证码明文**（明文只在服务端 / Redis 里）。
 */
@RestController
@RequestMapping("/user")
public class CaptchaController {

    private final CaptchaService captchaService;

    public CaptchaController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    @GetMapping("/captcha")
    public Result<Map<String, Object>> captcha() {
        CaptchaService.Challenge challenge = captchaService.issue();
        Map<String, Object> payload = new HashMap<>();
        payload.put("captchaId", challenge.id());
        payload.put("image", challenge.image());
        return Result.ok(payload);
    }
}
