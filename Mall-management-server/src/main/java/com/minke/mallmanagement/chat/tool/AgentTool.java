package com.minke.mallmanagement.chat.tool;

import com.minke.mallmanagement.chat.model.ChatIntent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个 Spring Bean 的**方法**声明为大模型可调用的业务工具（2026-10-22 插件化重构）。
 *
 * <h2>【它解决什么】</h2>
 * 旧实现把「工具清单」手写在 {@code LlmIntentClassifier.SYSTEM_PROMPT} 里，每加一个查询
 * 就要同步改 Prompt、改意图枚举、改编排层 switch。现在只要在一个方法上打注解：
 * <ul>
 *   <li>{@link ToolRegistry} 启动时自动扫描、反射出参数类型并生成 JSON Schema；</li>
 *   <li>按 {@link #targetRole()} 过滤后动态拼进 System Prompt；</li>
 *   <li>新增工具**不需要改**任何分发代码 —— 注册表按名字反射调用。</li>
 * </ul>
 *
 * <h2>【方法签名】</h2>
 * 第一个参数可以是 {@link ToolContext}（由注册表注入调用者身份，<b>不</b>出现在 JSON Schema
 * 里）；其余参数用 {@link AgentParam} 描述，会出现在 Schema 的 {@code properties} 中。
 * 返回值可以是 {@link ToolResult}（通用播报）或编排层已有的响应 Map（内置业务）。
 *
 * <h2>【角色】</h2>
 * {@code targetRole} 用登录表的 Role 值：1 买家 / 2 商家 / 3 管理员；留空 = 所有角色。
 * 未登录（role=null）按买家（1）过滤 —— 匿名用户仍然可以问「查订单」，只是会得到
 * 「请先登录」的确定性回答。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentTool {

    /** 工具名（唯一）。会逐字出现在 System Prompt 与模型的 {@code tool} 字段里。 */
    String name();

    /** 给模型看的能力说明：做什么、用户一般怎么说。 */
    String description();

    /** 允许调用该角色的 Role 值；空数组 = 所有角色。 */
    int[] targetRole() default {};

    /**
     * 与既有意图的对应关系。
     *
     * <p>内置工具填真实意图（如 {@link ChatIntent#ORDER_QUERY}），编排层据此把旧的 switch
     * 分派切到注册表；新插件工具留 {@link ChatIntent#UNKNOWN}，由通用执行路径处理。
     */
    ChatIntent intent() default ChatIntent.UNKNOWN;
}
