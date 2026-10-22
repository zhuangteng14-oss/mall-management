package com.minke.mallmanagement.chat.tool;

/**
 * 工具调用的运行时身份（由注册表注入，<b>不</b>出现在 JSON Schema 里）。
 *
 * <p>⚠️ 身份只从会话来：{@code userId}/{@code role} 由 Controller 的
 * {@code AuthSession.currentUserId/currentRole} 取出后一路传下来。工具方法永远不该从
 * 入参里接受「代表谁」的 id —— 那是越权面。
 *
 * @param userId  业务号（客户 customerid / 商家 merchantid），未登录为 null
 * @param role    1 买家 / 2 商家 / 3 管理员；未登录为 null
 * @param message 用户原话（工具生成话术时可用）
 */
public record ToolContext(String userId, Integer role, String message) {
}
