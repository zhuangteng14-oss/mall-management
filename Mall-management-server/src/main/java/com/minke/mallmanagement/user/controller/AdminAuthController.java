package com.minke.mallmanagement.user.controller;

import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.user.service.AdminLoginService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理员凭据驱动铸票端点（TD-18 方案 ⓑ，2026-10-20）。
 *
 * <h2>【为什么是独立 Controller，而不是塞进 {@link UserController}】</h2>
 * <ul>
 *   <li>{@code UserController} 的 9 个端点与行号被多处文档互引（净零行数纪律），
 *       加一个端点会把其后所有地址整体推一格；</li>
 *   <li>本端点是**未登录可达**的凭据入口，与 {@code UserController} 的
 *       「身份驱动」端点语义不同，独立成类后白名单审计只看这一处。</li>
 * </ul>
 *
 * <h2>【为什么依赖用 {@code ObjectProvider}】</h2>
 * 与 {@code WebAuthConfig} / {@code UserController} 同一条切片纪律：{@code @WebMvcTest}
 * 会扫到本类（它是 {@code @RestController}），但**不扫** {@code @Service}。
 * 裸构造依赖会让全仓每一个 smoke 切片在启动期「找不到 bean」集体失败。
 * 切片里 {@code getIfAvailable() == null} ⇒ 本端点答 503；生产上下文里 bean 存在。
 *
 * <h2>【HTTP 契约】</h2>
 * <ul>
 *   <li>200：{@code {success:true, token:"<43 字符>", role:3, username:"…"}}；</li>
 *   <li>401：账号或密码不正确（含第 1–4 次失败）；</li>
 *   <li>403：不是管理员 / 已被禁用 / 默认弱口令；</li>
 *   <li>429：连续失败 5 次锁定 15 分钟（带 {@code Retry-After}）；</li>
 *   <li>503：Redis 不可用，拒绝签发不可撤销的票。</li>
 * </ul>
 * ⚠️ 本端点**不写 cookie 会话**：管理员身份只由 Bearer 票承载，与
 * {@link AuthSession#authorize} 对 cookie 管理员折成未登录的既有设计一致。
 */
@RestController
@RequestMapping("/user")
public class AdminAuthController {

    private final ObjectProvider<AdminLoginService> adminLoginService;

    public AdminAuthController(ObjectProvider<AdminLoginService> adminLoginService) {
        this.adminLoginService = adminLoginService;
    }

    @PostMapping("/adminToken")
    public ResponseEntity<Object> adminToken(
            @RequestParam("username") String username,
            @RequestParam("password") String password) {
        AdminLoginService service = adminLoginService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.fail("认证服务未装配"));
        }

        try {
            String token = service.login(username, password);
            Map<String, Object> payload = new HashMap<>();
            payload.put("token", token);
            payload.put("role", AuthSession.ROLE_ADMIN);
            payload.put("username", username);
            return ResponseEntity.ok(Result.ok(payload));
        } catch (AdminLoginService.RejectedException e) {
            HttpStatus status = switch (e.reason()) {
                case BAD_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
                case NOT_ADMIN, BANNED, WEAK_PASSWORD -> HttpStatus.FORBIDDEN;
                case LOCKED -> HttpStatus.TOO_MANY_REQUESTS;
                case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
            if (e.retryAfterSeconds() > 0) {
                builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
            }
            return builder.body(Result.fail(e.getMessage()));
        }
    }
}
