package com.minke.mallmanagement.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * 管理员弱口令判据（TD-18 第 2 条安全硬约束，2026-10-20）。
 *
 * <h2>【为什么代码里只有 SHA-256，没有口令原文】</h2>
 * 弱口令表本身不能成为新的泄漏面：把默认口令逐字写进源码，等于在仓库里再发一次默认凭据
 * （而且提交钩子会按明文凭据拦截）。这里只存**摘要**，比较时对输入取同一摘要再查集合。
 *
 * <h2>【两条判据】</h2>
 * <ol>
 *   <li><b>长度 &lt; 8</b> —— 覆盖任何短口令，不依赖黑名单；</li>
 *   <li><b>摘要命中已知默认口令</b> —— 覆盖「长度够但仍是出厂/种子口令」的那一类，
 *       例如登录表初始化时写入的那个默认管理员口令、{@code admin}、{@code 123456}、
 *       {@code password} 等常见弱值。</li>
 * </ol>
 *
 * <p>⚠️ 本类只负责**判定**。拒绝动作有两处：{@code UserService.loginSelect}（role=3）、
 * {@link AdminLoginService}（凭据驱动铸票端点）—— 两处都必须在签发任何凭据**之前**调用。
 */
final class AdminPasswordPolicy {

    /** 管理员口令最小长度。 */
    private static final int MIN_LENGTH = 8;

    /**
     * 已知默认/常见弱口令的 SHA-256（小写十六进制）。
     *
     * <p>⚠️ 新增条目只加摘要，不加原文；生成方式见本文件头的说明，别把口令写进注释。
     */
    private static final Set<String> WEAK_SHA256 = Set.of(
            "fcf4de896d04f16286de619c27595e44080d78a7a60b86263a6807e1631e92ba", // 登录表种子管理员口令
            "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92", // 6 位数字
            "ef797c8118f02dfb649607dd5d3f8c7623048c9c063d532cc95c5ed7a898a64f", // 8 位数字
            "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918", // 常见单词
            "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", // 常见单词
            "bcb15f821479b4d5772bd0ca866c00ad5f926e3580720659cc80d39c9d09802a", // 重复数字
            "4813494d137e1631bba301d5acab6e7bb7aa74ce1185d456565ef51d737677b2", // 常见单词
            "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"  // 常见单词+数字
    );

    private AdminPasswordPolicy() {
    }

    /** {@code true} = 该口令不允许用于管理员登录。 */
    static boolean isWeak(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return true;
        }
        return WEAK_SHA256.contains(sha256Hex(password));
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法；到不了这里。判不了就拒绝，绝不放行。
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
