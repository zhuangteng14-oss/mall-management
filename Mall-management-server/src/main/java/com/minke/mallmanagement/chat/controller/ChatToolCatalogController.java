package com.minke.mallmanagement.chat.controller;

import com.minke.mallmanagement.chat.tool.ToolContext;
import com.minke.mallmanagement.chat.tool.ToolExecutor;
import com.minke.mallmanagement.chat.tool.ToolRegistry;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具目录诊断端点（**仅 dev**）：把启动扫描的成果原样吐出来，便于肉眼核对。
 *
 * <ul>
 *   <li>{@code GET /chat/tools} —— 当前会话角色**可见**的工具 + 反射生成的 JSON Schema；</li>
 *   <li>{@code POST /chat/tools/invoke} —— 按名字调一次（参数在 {@code args} 里），
 *       身份同样只从会话取。</li>
 * </ul>
 *
 * <p>⚠️ 角色**不从请求体读**：{@code role} 只由 {@link AuthSession#currentRole} 给出，
 * 否则这个端点就成了「随便冒充商家调工具」的后门。仅 dev profile 暴露。
 */
@RestController
@RequestMapping("/chat/tools")
@Profile("dev")
public class ChatToolCatalogController {

    private final ToolRegistry registry;
    private final ToolExecutor executor;

    public ChatToolCatalogController(ToolRegistry registry, ToolExecutor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    /**
     * @param format {@code prompt} ⇒ 返回**逐字**拼进 System Prompt 的那一段工具目录
     *               （排查「模型为什么看不到某个工具」时看这个，而不是猜）；
     *               其它值 ⇒ 结构化清单 + JSON Schema
     */
    @GetMapping
    public Result<Object> list(@RequestParam(value = "format", required = false) String format,
                               HttpServletRequest request) {
        Integer role = AuthSession.currentRole(request);
        if ("prompt".equals(format)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", role);
            out.put("prompt", registry.promptCatalog(role));
            return Result.of(out);
        }
        return Result.of(registry.describeForRole(role));
    }

    @PostMapping("/invoke")
    public Result<Object> invoke(@RequestBody(required = false) Map<String, Object> body,
                                 HttpServletRequest request) {
        String tool = body == null ? null : str(body.get("tool"));
        Map<String, Object> args = argsOf(body);
        String message = body == null ? null : str(body.get("message"));
        ToolContext ctx = new ToolContext(AuthSession.currentUserId(request),
                AuthSession.currentRole(request), message);
        try {
            return Result.of(executor.invoke(tool, ctx, args));
        } catch (ToolRegistry.ToolException e) {
            return Result.fail(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argsOf(Map<String, Object> body) {
        if (body == null || !(body.get("args") instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            args.put(String.valueOf(e.getKey()), e.getValue());
        }
        return args;
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
