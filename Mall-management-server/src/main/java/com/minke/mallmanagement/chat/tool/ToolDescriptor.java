package com.minke.mallmanagement.chat.tool;

import com.minke.mallmanagement.chat.model.ChatIntent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注册表里的一条工具元数据（启动扫描时生成，之后只读）。
 *
 * @param name        工具名
 * @param description 能力说明
 * @param targetRole  允许的角色（空 = 所有角色）
 * @param intent      对应的既有意图；{@link ChatIntent#UNKNOWN} = 插件型通用工具
 * @param schema      由参数类型反射生成的 JSON Schema（对象）
 * @param bean        Bean 实例（可能是 AOP 代理）
 * @param method      可调用的方法（已换成代理上可调用的 Method）
 * @param params      参数绑定表（含 {@link ToolContext} 注入位）
 */
public record ToolDescriptor(String name, String description, Set<Integer> targetRole,
                             ChatIntent intent, Map<String, Object> schema,
                             Object bean, Method method, List<ToolParam> params) {
}
