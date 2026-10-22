package com.minke.mallmanagement.chat.tool;

import com.minke.mallmanagement.chat.model.ChatIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用层：把注册表里的方法调用结果统一成前端认识的响应体。
 *
 * <h2>【为什么还要一层，而不在编排层直接反射】</h2>
 * 注册表只管「找到方法并调用」，本类只管「结果长什么样」。两种返回形态：
 * <ul>
 *   <li>内置业务工具（{@code ChatToolSet}）返回**完整响应 Map**（含 {@code action} /
 *       {@code suggestions}），原样透传 —— 与重构前的 switch 分支逐字节一致；</li>
 *   <li>插件工具返回 {@link ToolResult} / {@code String} ⇒ 本类套标准外壳
 *       {@code {success, intent, reply, data}}，插件作者不需要知道前端协议。</li>
 * </ul>
 * 任何异常都以 {@link ToolRegistry.ToolException} 抛出，由编排层决定降级到既有 switch。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    /** 按名字调用（模型在 {@code tool} 字段里给的名字）。 */
    public Map<String, Object> invoke(String toolName, ToolContext context, Map<String, Object> args) {
        return toResponse(registry.execute(toolName, context, args), registry.find(toolName));
    }

    /** 按意图调用（规则引擎判出意图、但模型没给 {@code tool} 时）。没有对应工具返回 {@code null}。 */
    public Map<String, Object> invokeForIntent(ChatIntent intent, ToolContext context,
                                               Map<String, Object> args) {
        ToolDescriptor tool = registry.forIntent(intent, context == null ? null : context.role());
        if (tool == null) {
            return null;
        }
        return toResponse(registry.execute(tool.name(), context, args), tool);
    }

    /** 工具结果 → 响应体。 */
    Map<String, Object> toResponse(Object raw, ToolDescriptor tool) {
        ChatIntent intent = tool == null || tool.intent() == null ? ChatIntent.UNKNOWN : tool.intent();
        if (raw instanceof ToolResult result) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("intent", intent.name());
            resp.put("reply", result.reply() == null ? "" : result.reply());
            if (result.data() != null) {
                resp.put("data", result.data());
            }
            return resp;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> resp = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                resp.put(String.valueOf(e.getKey()), e.getValue());
            }
            resp.putIfAbsent("success", true);
            resp.putIfAbsent("intent", intent.name());
            return resp;
        }
        if (raw instanceof CharSequence text) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("intent", intent.name());
            resp.put("reply", text.toString());
            return resp;
        }
        if (raw == null) {
            return null;
        }
        // 其余类型：不猜结构，按字符串播报（工具作者应返回 ToolResult / Map）。
        log.warn("工具 {} 返回了未约定类型 {}，按字符串播报", tool == null ? "?" : tool.name(),
                raw.getClass().getName());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("intent", intent.name());
        resp.put("reply", String.valueOf(raw));
        return resp;
    }

    /** 诊断端点 / 测试用：当前角色可见工具的名字列表（稳定排序）。 */
    public List<String> namesForRole(Integer role) {
        List<String> names = new ArrayList<>();
        for (ToolDescriptor t : registry.forRole(role)) {
            names.add(t.name());
        }
        return names;
    }
}
