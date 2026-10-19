package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.SseConnectionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * SSE 超限时的统一拒绝响应（D3-6）—— 三条观测流共用同一句话，避免三处各写一份文案。
 *
 * <p>⚠️ 状态码 **429**（Too Many Requests），不是 503：这是「你这个客户端开太多了」，
 * 不是「服务端整体不可用」。{@code Retry-After: 5} 给前端/浏览器一个明确的退避提示。
 *
 * <p>⚠️ 不能改成抛异常 / 返回普通 JSON 实体：本方法所在的端点声明返回
 * {@link StreamingResponseBody}，返回类型里装不下 JSON 字符串。这里自己往输出流写一小段 JSON，
 * 响应头在**返回之前**设好（异步路径随后才开始写 body，不会覆盖状态码与 Content-Type）。
 */
final class SseRejection {

    private SseRejection() {
    }

    static StreamingResponseBody of(HttpServletResponse response, SseConnectionRegistry registry,
                                    HttpServletRequest request) {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", "5");
        // 分角色文案：管理员看到的是 500/2000，普通用户/商家看到的是 20/200。
        // ⚠️ 档位从**这次请求**重新判定（与 tryAcquire 同一口径），不是从全局配置里猜。
        SseConnectionRegistry.Limits limits = registry.limitsFor(request);
        String msg = "SSE 连接数已达上限（" + limits.label() + "：单个账号 " + limits.maxPerUser()
                + " 条 / 全局 " + limits.maxTotal() + " 条）。请关闭不用的观测页标签，稍后重试；"
                + "管理员可调整 application.properties 的 "
                + (registry.tierOf(request) == SseConnectionRegistry.Tier.ADMIN
                        ? "mall.observe.sse.admin.max-per-user / mall.observe.sse.admin.max-total"
                        : "mall.observe.sse.max-per-user / mall.observe.sse.max-total");
        byte[] body = ("{\"success\":false,\"msg\":\"" + msg + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return out -> {
            out.write(body);
            out.flush();
        };
    }
}
