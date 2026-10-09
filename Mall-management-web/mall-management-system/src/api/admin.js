// src/api/admin.js —— 管理端（C3）API 封装。批 A = 「权限与审计」3 条只读端点；
// 块 1 · I2（2026-10-09）= 「系统通知」4 条（发送 / 历史 / 模板存取）。
//
// 为什么单列一个模块（而不是在 view 里直接 `http.post`）：
//   ① **路径不在这里硬编码** —— 一律取自 `endpoints.js`（全仓唯一允许出现后端路径字面量的文件，
//      该文件的头注释写明这条判据可 grep 自动化）；
//   ② 参数一律走 `URLSearchParams` 字符串 —— 这是本仓 `http.post` 的既有形态。
//      ⚠️ **别改成发 JSON**：后端 `AdminAuditController` 收的是 `@RequestParam`，
//      `application/json` 的 body 它**不绑定**，而请求照样 200 ⇒ 表现为「筛选静默失效」。
//      编码规则见 `http.js` 的 `buildInit`（三种 body 形态）；
//   ③ **空值一律不发** —— 后端四个筛参都是 `required = false`，而 `AdminAuditDao` 用
//      `notBlank(...)` 判「是否筛」。发一个空串过去 = 「有筛，筛空值」⇒ **一条都查不到**。
//      这个坑不写下来就会有人犯，故收在上面那个 `q()` 里。
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
 *
 * ⚠️ 这是本模块与既有 view 的一处**有意不同**：既有 view 自己写 `const data = await res.json()`
 * （`http.post` 返回的是裸 `Response`，见 `http.js`）。本模块是**专用的** API 层，
 * 把「拆壳」收在这里，view 只管数据；若哪天要让 view 自己拆，改本函数一处即可。
 */
async function post(path, body) {
  const res = await http.post(path, body)
  return res.json()
}

/**
 * 审计列表（时间倒序）。
 *
 * @param {{action?:string, targetType?:string, actorId?:string, from?:string, to?:string, limit?:number}} params
 *        `from` / `to` 是 **`'YYYY-MM-DD HH:MM:SS'`**（DB 时钟文本，见页面里的 `toSqlTime`）；
 *        不传的键 = 不筛。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          `limit` 是**后端回显的生效值**（请求 > 500 会被夹到 500，页面据此提示「已截断」）。
 */
export function auditList(params) {
  return post(endpoints.admin.auditList, q(params))
}

/**
 * 审计详情（按主键）。
 *
 * @returns {Promise<{success:boolean, row?:object, msg?:string}>}
 *          ⚠️ 主键不存在时 **200 + `success:false`**（不是 404）—— 后端随全仓惯例不抛异常。
 */
export function auditDetail(id) {
  return post(endpoints.admin.auditDetail, q({ id }))
}

/**
 * 筛选面：库里**已出现过**的 `Action` / `TargetType` 去重值 —— 供页面下拉。
 *
 * @returns {Promise<{success:boolean, actions:string[], targetTypes:string[]}>}
 */
export function auditFacets() {
  return post(endpoints.admin.auditFacets, null)
}

// ══════════════════════════════════════════════════════════════════════════════
// C3 块 1 · I2「系统通知」（2026-10-09）—— 管理端**发送侧** 4 条
//
// ⚠️ 本段的 4 条**全是写 / 读管理端的**（不是收件箱）—— 收件箱那 4 条住 `api/notify.js`：
//    它们是**三端共用的接收面**，与「管理员发一条公告」是两件事（后端也分在两个 Controller）。
// ⚠️ 复用同一个 `q()` + `post()`：三条坑（空值不发 / URLSearchParams 而非 JSON / 后端 `@RequestParam`）
//    逐条同样适用 —— 尤其是**空值不发**：`audience` 与 `toId` 后端是「二选一必填」，
//    发一个空串过去会被 `NotificationBroadcastService` 判成「两处都没给」而**当场报错**
//    （那是它比方案更严的第一条校验，见该服务块首）。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 发送一条系统通知（公告 / 群发 / 定向）。
 *
 * @param {{title:string, body:string, eventType?:string, audience?:string,
 *          toId?:string, toType?:string, targetType?:string, targetId?:string}} params
 *        `audience` 取 `'all'` / `'customer'` / `'merchant'`；**定向**时改用 `toId` + `toType`
 *        （`toType` 只认 `'customer'` / `'merchant'`）。
 *        ⚠️ `audience` 与 `toId` **必须给一个**，两个都不给后端**直接报错**
 *        （刻意不默认成 `all` —— 漏传一个参数就变成全站广播，代价太大）。
 *        `eventType` 缺省时后端取 `system.notice`。
 * @returns {Promise<{success:boolean, sent:number, batchId:string, msg?:string}>}
 *          ⚠️ `sent` = **真实落库条数**（不是「已提交任务数」）—— 受众为空（库里没这个角色的人）
 *          是**正常结果**，如实回 `sent:0`、**不**报 `success:false`。
 */
export function notifySend(params) {
  return post(endpoints.admin.notifySend, q(params))
}

/**
 * 投递历史（按 `BatchId` 归组，时间倒序）—— 「发过什么」那一眼。
 *
 * @param {{eventType?:string, from?:string, to?:string, limit?:number}} params
 *        `from` / `to` 是 **`'YYYY-MM-DD HH:MM:SS'`**（库内 UTC 文本，换算口径同 `auditList` ——
 *        页面复用 `AuditPage.vue` 的 `toSqlTime`）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          ⚠️ 一行的粒度为**一个批次**（不是一条通知）：`RecipientCount` = 投给了几人、
 *          `UnreadCount` = 几人还没读。`total` 也是**批次数**（`COUNT(DISTINCT BatchId)`）。
 */
export function notifyHistory(params) {
  return post(endpoints.admin.notifyHistory, q(params))
}

/**
 * 模板列表（按 `TemplateKey` 升序）。
 *
 * @returns {Promise<{success:boolean, list:Array}>}
 *          ⚠️ **块 1 只做存取、不渲染**：发送时收的是**字面** title / body，模板不参与拼接
 *          （占位符渲染留后，见 `NotificationService.saveTemplate` 的说明）。本端点是「看有哪些模板」。
 */
export function notifyTemplateList() {
  return post(endpoints.admin.notifyTemplateList, null)
}

/**
 * 模板 upsert（有则改、无则插 —— 靠唯一键 `TemplateKey`）。
 *
 * @param {{templateKey:string, titleTemplate:string, bodyTemplate:string, enabled?:boolean}} params
 *        `enabled` 不传 ⇒ 后端按「启用」处理（新建模板的默认态）。
 * @returns {Promise<{success:boolean, saved:boolean, affected:number, msg?:string}>}
 *          ⚠️ `affected` 是 MySQL 的**原样回报**：插入 = `1`、改了值 = `2`（规格如此，不是 bug）。
 *          两个数都算成功，别拿 `=== 1` 当判据。
 */
export function notifyTemplateSave(params) {
  return post(endpoints.admin.notifyTemplateSave, q(params))
}
