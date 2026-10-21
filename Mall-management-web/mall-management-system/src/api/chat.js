// src/api/chat.js —— 智能客服（2026-10-21）
//
// 唯一端点：`POST /chat/ask`，表单编码 + 字段名 `Message`（后端收 `@RequestParam`）。
// ⚠️ **不带任何身份参数**：后端从会话 / Bearer 票取身份，前端传 `CustomerId` 一类的值
//    既没用、又会让「越权查别人订单」变成显式关心的事。
// ⚠️ `allowUnauthorized: true`：默认 profile 下闸是开的，匿名必得 401；若走全局 401 出口，
//    访客点一下客服球就会被清登录态 + 踢去 /login —— 和「首页本来公开」的老问题同形。
//    这里按次旁路，由 ChatWidget 把 401 翻成「登录后才能查」的回复。
import { http } from './http'
import { endpoints } from './endpoints'

/** @param {string} message 用户原话 */
export function askChat(message) {
  return http.post(
    endpoints.chat.ask,
    new URLSearchParams({ Message: String(message ?? '') }).toString(),
    { allowUnauthorized: true },
  )
}
