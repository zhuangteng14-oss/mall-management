// src/api/notify.js —— 系统通知**接收面** API 封装（C3 块 1 · I2，2026-10-09）。
//
// 与 `src/api/admin.js` 的分工 —— 这两份**有意不合并**：
//   · 本文件 = **收件箱**（`/notification/*`，**公共层**，无 `/admin` 前缀）—— 客户 / 商家两壳共用；
//   · `admin.js` = **管理端的发送侧**（`/admin/notify/*`）—— 只有 `views/admin/NotifyPage.vue` 用。
//   「收」与「发」在**后端也是两个 Controller**（`NotificationController` ⇄ `AdminNotificationController`），
//   前端跟着分开，免得日后有人以为「通知都在一个模块里」而把权限口径也顺带合并。
//
// ⚠️ **`q()` / `post()` 与 `admin.js` 里那两个是**逐字相同**的本地副本**（刻意的重复）：
//   抽成共享模块要新开一个文件、并回头看 `admin.js`（刚落的批 A）—— 那笔改动**越出了本块的范围**，
//   而这里只有 4 个函数、两处 helper。等第三处出现再抽。
//   ⚠️ 两条附带的口径**同样适用**（不是抄漏）：
//     ① 参数一律 `URLSearchParams` 字符串、**别改成 JSON** —— 后端收 `@RequestParam`，
//        `application/json` 的 body 它不绑定，而请求照样 200 ⇒ 表现为「静默无效」；
//     ② 空值一律不发 —— 后端的缺参判据是 `isBlank`，发空串 = 「给了个空值」。
import { http } from './http'
import { endpoints } from './endpoints'

/** 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。同上 ②。 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/** 发一发 POST 并**拆掉 `Response` 外壳**（`http.post` 返回裸 `Response`），直接答 JSON body。 */
async function post(path, body) {
  const res = await http.post(path, body)
  return res.json()
}

/**
 * 收件箱列表（时间倒序）。
 *
 * @param {{toId:string, toType:string, onlyUnread?:boolean, limit?:number}} params
 *        `toId` / `toType` **必给** —— 后端 `NotificationController` 对这两项跑 `IdentityGuard`，
 *        传别人的号是 **403**（不是「查不到」）。
 * @returns {Promise<{success:boolean, list:Array, total:number, unread:number, limit:number}>}
 *          ⚠️ 每行**多一个 `unread` 布尔**（后端由 `ReadAt` 是否为 `null` 推出）—— 别自己去判
 *          `ReadAt` 是不是空串：未读的判据是 **SQL `NULL`**。
 *          ⚠️ `total` 与 `list` 是**同一个筛选条件**下的数（含 `onlyUnread`）；
 *          `unread` 则**恒为全量未读数**（顶栏红点用它）—— 两者不可互换。
 */
export function notifyList(params) {
  return post(endpoints.notification.list, q(params))
}

/**
 * 未读数（顶栏红点）—— 三端 2 × 5s 轮询里**最热**的那一发。
 *
 * @returns {Promise<{success:boolean, unread:number}>}
 */
export function notifyUnreadCount(params) {
  return post(endpoints.notification.unreadCount, q(params))
}

/**
 * 标记**一条**已读。
 *
 * @param {{toId:string, toType:string, id:number|string}} params
 * @returns {Promise<{success:boolean, updated:number}>}
 *          ⚠️ **`updated:0` 是正常结果，不是失败** —— 它同时覆盖「这条已经标过了」与
 *          「这个 Id 不属于你 / 不存在」两种情形（后端 SQL 幂等 + 按收件人圈定）。
 *          重复点击不该弹错，判据请用 `success`，**别用 `updated === 1`**。
 */
export function notifyRead(params) {
  return post(endpoints.notification.read, q(params))
}

/**
 * 把该收件人的**全部**未读标为已读（收件箱顶部那个按钮）。
 *
 * @returns {Promise<{success:boolean, updated:number}>} 语义同 `notifyRead`（`updated:0` 正常）。
 */
export function notifyReadAll(params) {
  return post(endpoints.notification.readAll, q(params))
}
