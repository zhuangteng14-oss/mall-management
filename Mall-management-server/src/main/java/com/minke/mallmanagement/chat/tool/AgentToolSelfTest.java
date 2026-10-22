package com.minke.mallmanagement.chat.tool;

import com.minke.mallmanagement.chat.model.ChatIntent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件化自检工具（**仅 dev**）：验证「新增一个打注解的方法，重启即生效，不改任何分发代码」。
 *
 * <p>它不在 {@code ChatToolSet} 里、没有被 {@code ChatAgentService} 引用、Prompt 里也没有任何
 * 硬编码提到它 —— 能出现在 {@code GET /chat/tools} 且能被 {@code POST /chat/tools/invoke}
 * 调通，只可能是启动扫描把它捡了进去。
 *
 * <p>生产 profile 不加载：这不是业务能力，只是可观测性探针。
 */
@Component
@Profile("dev")
public class AgentToolSelfTest {

    private final ToolRegistry registry;

    public AgentToolSelfTest(ToolRegistry registry) {
        this.registry = registry;
    }

    @AgentTool(name = "dev_ping",
            description = "开发自检：返回当前注册表规模与调用者角色，用来证明新增工具会被自动发现与注入。",
            targetRole = {1, 2, 3},
            intent = ChatIntent.UNKNOWN)
    public ToolResult ping(
            @AgentParam(name = "echo", description = "任意回显文本，用来核对参数绑定", required = false)
            String echo,
            ToolContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registeredTools", registry.size());
        data.put("role", ctx.role());
        data.put("userId", ctx.userId());
        data.put("echo", echo);
        return ToolResult.of("pong（注册表里共 " + registry.size() + " 个工具）", data);
    }
}
