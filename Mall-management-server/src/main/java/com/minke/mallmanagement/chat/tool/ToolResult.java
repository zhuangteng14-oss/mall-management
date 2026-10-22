package com.minke.mallmanagement.chat.tool;

import java.util.Map;

/**
 * 通用工具结果：一句给用户的话 + 可选结构化数据。
 *
 * <p>内置业务工具为了保持既有响应形状（action / suggestions）直接返回响应 Map；
 * 新插件工具用本类型即可，编排层会套上标准的 {@code success/intent/reply/data} 外壳。
 */
public record ToolResult(String reply, Map<String, Object> data) {

    public static ToolResult of(String reply) {
        return new ToolResult(reply, null);
    }

    public static ToolResult of(String reply, Map<String, Object> data) {
        return new ToolResult(reply, data);
    }
}
