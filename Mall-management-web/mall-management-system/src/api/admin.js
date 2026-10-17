// src/api/admin.js —— 管理端（C3）API 封装。批 A = 「权限与审计」3 条只读端点；
// 块 1 · I2（2026-10-09）= 「系统通知」2 条（发送 / 历史）。⚠️ **模板存取已迁出**（原 4 条）：
// 2026-10-10〈模板表通用化〉把通知模板并进通用 `template` 表 ⇒ 那 2 条函数与 `/admin/notify/template/*`
// 一起下线，模板 CRUD 现在住在 `src/api/template.js`（端点组 `templateAdmin`）。
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
// ⚠️ 本段的 2 条**全是写 / 读管理端的**（不是收件箱）—— 收件箱那 4 条住 `api/notify.js`：
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

// ⚠️ **本段原有第 3 项「模板」两条函数已删**（2026-10-10〈模板表通用化〉）：
//    `notifyTemplateList` / `notifyTemplateSave`（原 122-137 行）连同端点 `endpoints.admin.notifyTemplate*`
//    （原 80-81 行）与后端 `/admin/notify/template/*` 一起下线 —— 通知模板并进通用 `template` 表，
//    CRUD 改走 `src/api/template.js` 的 `templateAdminList` / `templateAdminCreate` / …。
//    ⇒ 口径变化：旧 upsert 靠唯一键 `TemplateKey`「有则改、无则插」；新面是**按 Id 显式**改
//      （`templateAdminUpdate`），新增走 `templateAdminCreate`，`Name` 就是原 `TemplateKey`。

// ══════════════════════════════════════════════════════════════════════════════
// C3 块 3〈商品块〉（2026-10-10）—— 管理端**商品审核 / 类目 / 违规** 7 条
//
// ⚠️ 四条坑逐字沿用上面两段（`q()` / `post()` / 空值不发 / URLSearchParams 而非 JSON）。
// ⚠️ 特别说一次**空值不发**在**更新**类端点上更要紧：`/category/update`
//    是「只改传入的非空字段」（后端 `setter` 判空）—— 把「没改」和「改成空串」混在一起，
//    会把一个空串真的写进列里。`q()` 已经替你挡掉了。
// ⚠️ 布尔参数（`approve` / `includeDisabled`）**照发**：后端收 `Boolean`，`"false"` 绑成 `false`。
//    ⚠️ 但**别传空串**当 false —— 空串会被 `q()` 丢掉，后端看到 `null` ⇒ `approve` 缺省即「驳回」，
//    那是**方向相反**的语义。要驳回就显式传 `false`。
// ⚠️ 同样**不给角色闸留位置** —— 后端这 7 条也没有角色判据（TD-18）。
// ⚠️ **原有第 3 项「模板」4 条函数已删**（2026-10-10〈模板表通用化〉）：`catalogTemplateList` /
//    `Create` / `Update` / `Delete`（原 221-238 行）连同端点 `endpoints.catalogAdmin.template*`
//    （原 146-149 行）与后端 `/admin/catalog/template/*` 一起下线 —— 改走 `src/api/template.js`
//    的 `templateAdminList` 等 4 条（SQL 同表，Type 从 `detail/spec/after_sale` 换成
//    `goods.detail/goods.spec/goods.afterSale`）。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 类目树（**读**）—— 走**既有**公开端点 `GET /goods/goodstype`（块 3 裁决：**不新开读端点**）。
 *
 * ⚠️ 这里是本模块**唯一的裸 GET**（其余全是 `post()`）—— 因为那个端点就是「GET 无参 ⇒ 读 / POST 带参 ⇒ 写」
 *   的双形态，读分支必须用 GET 才落到 `GoodType == null` 那一支。⚠️ **不能**改用 `post(path, null)`：
 *    后端判的是 `GoodType == null`，POST 无参**也会**走读分支 —— 但那就改了既有调用形态
 *    （`GoodsAdd.vue` 的裸 GET 是那条端点的判据先例），故照旧用 GET。
 *
 * ⚠️ 返回**不筛 `State`**（`listTypes` 有意如此）：管理端要看见被停用（`State=0`）的类目。
 *   买家侧的一级 chips 才在前端按 `ParentId === 0` 过滤（见 `HomePage.vue`）。
 *
 * @returns {Promise<{success:boolean, list:Array<{Id:number, GoodType:string,
 *          GoodTypeNew:number, ParentId:number, Sort:number, State:number}>}>}
 *          ⚠️ `ParentId === 0` 即一级（不是 `null`）。
 */
export function catalogCategories() {
  return http.get(endpoints.goods.goodstype).then((res) => res.json())
}

/**
 * 买家**可见**的商品清单（违规处置 tab 的「选哪件」来源）—— 走既有端点 `POST /goods/goodsSelectHome`。
 *
 * ⚠️ **为什么用这个而不是新端点**：违规处置的谓词是 `(MerchantId, GoodName, ProductCode)` 三元组
 *    （见 `GoodsViolationService` 类注释），而**买家可见面**恰好就是「该处置哪些」的直觉面
 *    —— 已下架 / 已删除的行本来就不在这里（该端点 SQL 带 `ShelfState = 1 AND DeletedAt IS NULL`）
 *    ⇒ 管理员不会看到「已处置过还想再处置」的行。**不新开端点**（块 3 的裁决：能复用就复用）。
 *
 * ⚠️ `SELECT *` 投影 ⇒ 字段随 `goods` 表列集变（块 3 已 +5 列），本页只**读**用到的几列。
 *
 * @returns {Promise<{success:boolean, list:Array}>} 每行含 `MerchantId` / `GoodName` /
 *          `ProductCode` / `Price` / `GoodImg` / `UserName`（商家名，查不到为空串）
 */
export function catalogVisibleGoods(params) {
  // 块 7 收尾 · P1：`goodsSelectHome` 已改为真分页（缺省 24、上限 100）。
  // 违规处置是「从在架商品里挑一件」的下拉，**必须请求上限** —— 不传就只能看到前 24 件，
  // 排在后面的商品会静默地从候选里消失。>100 件的场景登记在《长列表接口盘点报告》
  // （真正修法是给这个下拉接关键字搜索或无限滚动）。
  return post(endpoints.goods.goodsSelectHome, q({ limit: 100, offset: 0, ...params }))
}

/** 提审单列表。`status` 可空 ⇒ 全部；传 `'pending'` 即待审。 */
export function catalogApplications(params) {
  return post(endpoints.catalogAdmin.applications, q(params))
}

/**
 * 审核一条提审单。**通过** = `approve:true`（`goods` 表新增 1 行）；**驳回** = `approve:false` +
 * `rejectReason`（**理由必填**，缺失时后端拒且一条 SQL 都不发）。
 *
 * @returns {Promise<{success:boolean, outcome?:string, merchantId?:string, goodsId?:number, msg?:string}>}
 *          ⚠️ `outcome='already-handled'`（并发下已被别的管理员处理）**不是失败**：`success:true`，
 *          且**不发通知、不记审计**。页面据此提示「已被处理」而不是报错。
 */
export function catalogAudit(params) {
  return post(endpoints.catalogAdmin.audit, q(params))
}

/** 新增类目（`ParentId` 空 / 0 ⇒ 一级）。⚠️ 同父级下同名会被唯一键拦，后端回「该父级下已有同名类目」。 */
export function catalogCategoryCreate(params) {
  return post(endpoints.catalogAdmin.categoryCreate, q(params))
}

/** 改类目（只改传入的非空字段；`State` = 1 启用 / 0 停用）。 */
export function catalogCategoryUpdate(params) {
  return post(endpoints.catalogAdmin.categoryUpdate, q(params))
}

/** 删类目 —— **逻辑废弃**（`State=0`，规则 v2：不物理删）。 */
export function catalogCategoryDelete(id) {
  return post(endpoints.catalogAdmin.categoryDelete, q({ id }))
}

/** 违规下架（`ShelfState=0`，行还在）。⚠️ 谓词是 `(MerchantId, GoodName, ProductCode)` 三元组。 */
export function catalogViolationDelist(params) {
  return post(endpoints.catalogAdmin.violationDelist, q(params))
}

/** 违规删除（`DeletedAt=now`，**逻辑删除**，行还在）。 */
export function catalogViolationDelete(params) {
  return post(endpoints.catalogAdmin.violationDelete, q(params))
}
