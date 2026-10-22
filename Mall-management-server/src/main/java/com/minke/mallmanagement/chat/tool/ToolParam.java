package com.minke.mallmanagement.chat.tool;

import java.lang.reflect.Type;

/**
 * 一个工具方法的参数绑定（反射缓存的元数据）。
 *
 * @param index   在方法参数表中的下标（调用时按位置填值）
 * @param name    参数名（Schema 里的键）
 * @param type    Java 类型（用于 Schema 与字符串 → 类型的转换）
 * @param generic 泛型类型（Schema 对集合 / 泛型参数更精确）
 * @param context 是否 {@link ToolContext}（注入身份，不进 Schema）
 * @param required 是否必填
 * @param description 给模型看的说明
 */
public record ToolParam(int index, String name, Class<?> type, Type generic,
                        boolean context, boolean required, String description) {
}
