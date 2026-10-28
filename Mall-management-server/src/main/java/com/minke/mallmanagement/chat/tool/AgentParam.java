package com.minke.mallmanagement.chat.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述 {@link AgentTool} 方法的一个业务参数（会生成 JSON Schema）。
 *
 * <p>⚠️ 参数名**显式写在这里**，不依赖 {@code -parameters} 编译开关：反射拿到的
 * {@code arg0} 一旦进 Prompt，模型就会按错名字填槽。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentParam {

    /** 参数名（与模型输出 {@code slots} 里的键一致）。 */
    String name();

    /** 给模型看的说明。 */
    String description() default "";

    /** 是否必填；可选参数必须给默认语义（引用类型可为 null，基本类型取零值）。 */
    boolean required() default true;
}
