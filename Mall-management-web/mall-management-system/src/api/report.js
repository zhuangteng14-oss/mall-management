// src/api/report.js —— 内容域「举报」API 封装（C3 块 6 整改 · commit 4；2026-10-13）。
//
// 三个端点、一个模块（后端 `ReportController`，`@RequestMapping("/report")`，三条全 POST）：
//   add    —— 提交一条举报（登录面）：评论 / 回复 / 商品 / 用户四种对象。
//            调用方：商品页 `ProductPage.vue`（评论 / 回复行的「举报」、商品行的「举报」、
//            提问行的「提删」—— 方案 §4.2「商家可提删」走的就是这一条）。
//   list   —— 举报列表（**管理端**「举报中心」）。调用方：管理端内容治理页。
//   handle —— 处理一条举报（**管理端**）：通过 ⇒ 删内容 + 警告（满 3 次自动封号）+ 通知双方。
//
// 为什么单列一个模块（而不是在 view 里直接 `http.post`）：三条理由与 `api/comment.js` /
// `api/marketing.js` 逐字同款 ——
//   ① **路径不在这里硬编码** —— 一律取自 `endpoints.js`；
//   ② 参数一律走 `URLSearchParams` 字符串 —— 本仓 `http.post` 的既有形态。
//      ⚠️ **别改成发 JSON**：后端收的是 `@RequestParam`，`application/json` 的 body 它**不绑定**，
//      而请求照样 200 ⇒ 表现为「静默没举报出去 / 静默没处理」；
//   ③ **空值一律不发** —— 后端入参**全部** `required = false` + Service 手判。
//
// ⚠️ 与 `api/comment.js` 的 `q()` / `post()` 是**同形复制、不是共享** —— 本仓 `api/*.js`
//    各模块自带这两个内联函数，未抽公共模块。
//
// ⚠️ **三条里只有 `add` 是登录面**（挂在 `IdentityGuard.requireSelfOrAdminByUsername`）；
//    `list` / `handle` 是管理端面 —— 按本仓一贯做法**不挂 `requireAdmin`**（见 `ReportController`
//    类注释的 ⚠️：靠 `AuthInterceptor` + `app.auth.enabled` 两道闸，口径同 `admin/**Controller`）。
//
// ⚠️ **`handle` 不给入参 `Handler`**：处理人从**会话身份**取（`AuthSession.identify().userId()`），
//    与审计表 `AdminAuditDao.ActorId` 同一口径。传别人的名字没有意义（后端不读）。
import { http } from './http'
import { endpoints } from './endpoints'

/**
 * 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。见文件头第 ③ 条。
 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/**
 * 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body。
 * （`http.post` 返回的是裸 `Response`，见 `http.js`；拆壳收在这里，view 只管数据。）
 */
async function post(path, body) {
  return (await http.post(path, body)).json()
}

/**
 * 提交一条举报（方案 §4.4）。
 *
 * ⚠️ **「不能举报自己」与「同一内容不能二次举报」是后端判的**（后者还有 DB 唯一键兜底）：
 *    - 举报自己 ⇒ `success:false` + `不能举报自己发布的内容`；
 *    - 已举报过（待处理 / 已处理）⇒ `success:false` + `您已举报过该内容，不能重复举报`；
 *    - **被驳回过的可以再举报** ⇒ 这一次会成功（`revived:true`，后端 UPDATE 同一行复活）。
 *    三种都是**业务规则**（不是故障），前端别弹「系统错误」。
 *
 * @param {object} p
 * @param {string} p.account 举报人**登录名**（只能是自己）—— 见文件头 ⚠️。
 * @param {'comment'|'goods'|'user'} p.targetType 被举报对象类型。
 *        ⚠️ **评论与回复共用 `comment`**（都是 `comment` 表的行）；被举报的**用户**要传他
 *        的**业务号**（`customerid` / `merchantid`），而**商品**要传 `goods.Id` —— 两者不同域。
 * @param {string|number} p.targetId 见上。
 * @param {'辱骂'|'广告'|'违法'|'其他'} p.reason ⚠️ 只能四选一，未知值**拒**。
 * @param {string} [p.description] 理由 = **`其他` 时必填**，≤ **100** 字。
 * @returns {Promise<{success:boolean, Id:number, revived?:boolean, msg:string}>}
 */
export function add(p) {
  return post(endpoints.report.add, q({
    Account: p.account,
    TargetType: p.targetType,
    TargetId: p.targetId,
    Reason: p.reason,
    Description: p.description,
  }))
}

/**
 * 举报列表（**管理端**「举报中心」）。
 *
 * ⚠️ **每条都带出了被举报对象的摘要**（`TargetContent` 评论正文 / `TargetGoodName` 商品名 /
 *    `TargetUserName` 用户名 + `TargetAuthor` 等）—— 后端用三个 `LEFT JOIN` 按 `TargetType`
 *    各取一边，**不是**各自再发一趟请求。
 *
 * ⚠️ **举报人对被举报人是匿名的，但对管理端是实名的**（方案 §4.4）：`ReporterId` 原样带出，
 *    管理端要看得见；**别把 `ReporterId` 回显到面向被举报人的任何地方**。
 *
 * @param {object} [p]
 * @param {'pending'|'handled'|'rejected'} [p.status] 省略 = 不筛。
 * @param {number} [p.limit] 页大小（默认 100，上限 500）。
 * @param {number} [p.offset] 偏移（默认 0）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, offset:number}>}
 */
export function list(p) {
  return post(endpoints.report.list, q({
    Status: (p || {}).status,
    Limit: (p || {}).limit,
    Offset: (p || {}).offset,
  }))
}

/**
 * 处理一条举报（**管理端**）。
 *
 * ⚠️ **`approve:true` 是一条不可逆的链**（后端 `ReportService.handle`）：删内容
 *    （评论走 `CommentService.delete`、商品走下架）+ 给作者 `WarnCount +1`
 *    （**满 3 次自动封号**，复用「用户与商家治理」的封禁）+ 通知**双方**。
 *    前端要二次确认，并把回体里的 `banned` / `warnCount` **如实报给管理员看**。
 *
 * ⚠️ **驳回也是有效结果**：`rejected` 会留给举报人**再举报**的机会（方案 §4.4）。
 *
 * @param {object} p
 * @param {string|number} p.id 举报单 Id。
 * @param {boolean} p.approve `true` = 通过；`false` = 驳回。
 * @param {string} [p.note] 处理说明（落 `HandleNote`，可空）。
 * @returns {Promise<{success:boolean, Id:number, Status:string, contentAffected?:number,
 *                    warnCount?:number, banned?:boolean, msg:string}>}
 *          ⚠️ `success:true` 也可能 `contentAffected:0`（内容已被作者自删 / 商品已下架）——
 *          那是**正常**的，别当失败重试（举报单此刻已消费，重试只会得「该举报已被处理」）。
 */
export function handle(p) {
  return post(endpoints.report.handle, q({
    Id: p.id,
    Approve: p.approve ? '1' : '0',
    Note: p.note,
  }))
}
