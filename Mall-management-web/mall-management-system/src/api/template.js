// src/api/template.js —— 通用模板（`template` 表）API 封装。
//
// 2026-10-10〈模板表通用化〉新建：把原 `goods_template`（块 3）与 `notification_template`
// （块 1 · I2）**两张表合成一张** `template`，用 `Type` 区分用途：
//   · 商品类  `goods.detail` / `goods.spec` / `goods.afterSale`（**快照源**：选完填入输入框、可改，
//     商品存**最终文本**而**不是**模板 Id ⇒ 改模板**不影响**已上架商品）
//   · 理由类  `goods.reject` / `merchant.reject` / `merchant.approve` / `user.ban`
//   · 通知类  `notification.<原 TemplateKey>`
//   · 预留    `comment.reply`（块 6 才建行）
//
// 为什么单列一个模块（而不是在 view 里直接 `http.post`）：
//   ① **路径不在这里硬编码** —— 一律取自 `endpoints.js`（全仓唯一允许出现后端路径字面量的文件）；
//   ② 参数一律走 `URLSearchParams` 字符串 —— 本仓 `http.post` 的既有形态。⚠️ **别改成发 JSON**：
//      后端 `TemplateController` / `AdminTemplateController` 收的是 `@RequestParam`，
//      `application/json` 的 body 它**不绑定**，而请求照样 200 ⇒ 表现为「静默查不到 / 静默没改」；
//   ③ **空值一律不发** —— 后端参数都是 `required = false` + Service 手判；发一个空串过去，
//      `update` 会把它**真的写进列里**（后端是「只改传入的非空字段」，空串不是「没传」）。
//      这个坑不写下来就会有人犯，故收在下面那个 `q()` 里。
//
// ⚠️ 与 `api/admin.js` 的 `q()` / `post()` **是同形复制、不是共享** —— 本仓 `api/*.js` 各模块
//    自带这两个内联函数（见 `notify.js` / `accounts.js` / `merchant.js`），未抽公共模块。
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

// ── 公共读（三端共用，无 `/admin` 前缀）─────────────────────────────────────

/**
 * 列模板（按 `Type`）。**「选模板」弹窗打的就是这个。**
 *
 * ⚠️ 公共读**只有一个参数** `Type`，且**恒只列启用中的**（要看停用的走 {@link templateAdminList}）。
 * @param {string} [type] 精确类型（`'goods.detail'` / `'user.ban'` …）。
 *        不传 ⇒ 回**全部启用中的**（故意留的宽读，但弹窗一般都会传）。
 *        ⚠️ 后端**不校验未知 `Type`**：查不到回**空列表 + `success:true`**（不是错误）——
 *        弹窗据此显示「暂无模板」（新建的 `template` 表初始就是空的，这是**预期**）。
 * @returns {Promise<{success:boolean, list:Array<{Id:number, Type:string, Name:string,
 *          Title:string|null, Body:string|null, Enabled:number, Sort:number}>}>}
 *          ⚠️ 排序是 `Sort ASC, Id ASC`（同 `Type` 内）。
 *          ⚠️ **读回来的** `Enabled` 是 `tinyint(1)`，Connector/J 默认（`tinyInt1isBit=true`）
 *          把它映射成 **Boolean** ⇒ JSON 里可能是 `true` / `false`、也可能是 `1` / `0`
 *          ⇒ 判断要**宽容**（`v === true || v === 1 || v === '1' || v === 'true'`），
 *          别赌某一种形态（`AccountsPage.isBanned` / `NotifyPage.isEnabled` 是同一个理由）。
 *          ⚠️ 但**写出去的** `Enabled`（见 {@link templateAdminUpdate}）是后端 `Integer`，要发 `1` / `0`。
 */
export function templateList(type) {
  return post(endpoints.template.list, q({ Type: type }))
}

/**
 * 取一条 —— 某 `Type` 下**第一条启用中的**（按 `Sort ASC, Id ASC`）。
 *
 * @param {string} type 精确类型。
 * @returns {Promise<{success:boolean, row?:object|null, msg?:string}>}
 *          ⚠️ 两种「没有」是**不同**的回体，别混：
 *          · 缺 `Type` ⇒ `success:false` + `msg:'缺少参数：Type'`；
 *          · 有 `Type` 但一条启用行都没有 ⇒ **`success:true` + `row:null`**。
 *            「这个 type 还没建模板」是**正常状态**，不是错误。
 */
export function templateGet(type) {
  return post(endpoints.template.get, q({ Type: type }))
}

// ── 管理端 CRUD（`/admin/template/*`，TD-18：无角色闸）────────────────────────

/**
 * 管理端模板列表 —— 比公共读多两件事：可带 `TypePrefix` **批量看**、可带 `includeDisabled`
 * **看见停用的**。
 *
 * @param {{Type?:string, TypePrefix?:string, includeDisabled?:boolean}} [params]
 *        `TypePrefix` 例：`'goods.'`（`CatalogPage`）/ `'notification.'`（`NotifyPage`）。
 *        ⚠️ `Type` 与 `TypePrefix` **同时给**时按 `Type` 走（后端 `if/else if`，精确优先）。
 *        ⚠️ 两个都不给 ⇒ 全表（含停用，若 `includeDisabled:true`）。
 * @returns {Promise<{success:boolean, list:Array}>}
 */
export function templateAdminList(params) {
  return post(endpoints.templateAdmin.list, q(params))
}

/**
 * 新增模板。
 *
 * @param {{Type:string, Name:string, Title?:string, Body?:string, Sort?:number}} params
 *        ⚠️ `Type` 必须是**已知**类型（后端 `TemplateTypes.known`），否则 `success:false` +
 *        「未知模板类型」；`Name` 非空；`notification.*` 类**还要求 `Title` 非空**
 *        （商品类 / 理由类的 Title 无意义，允许空 —— 正文就是全部）。
 *        ⚠️ `(Type, Name)` 撞唯一键 ⇒ 后端回「该类型下已有同名模板」（`success:false`，
 *        靠库判定而非预查 —— 天然并发安全）。
 * @returns {Promise<{success:boolean, msg:string}>} ⚠️ **不带新行的 `Id`** —— 要回读走 `templateAdminList`。
 */
export function templateAdminCreate(params) {
  return post(endpoints.templateAdmin.create, q(params))
}

/**
 * 改模板（改名 / 改标题 / 改正文 / 启停 / 改排序）。
 *
 * @param {{id:number, Name?:string, Title?:string, Body?:string, Enabled?:number, Sort?:number}} params
 *        ⚠️ **没有 `Type`** —— 模板的 type 落库即定，不允许改（改了等于换了一个模板）；
 *        后端 `update` 端点根本不收这个参数，传了也是被忽略。
 *        ⚠️ `Enabled` 是 **`0` / `1` 的数字**（后端收 `Integer`）—— **别传 `false`**：`"false"`
 *        绑不进 `Integer`，会 400。要停用传 `0`。⚠️ 也**别传空串**当「不改」：
 *        空串会被 `q()` 丢掉 = 这一项不改（方向可能与你以为的相反，见文件头第 ③ 条）。
 *        其余字段**只改传入的非空部分**。
 * @returns {Promise<{success:boolean, msg:string, before?:object}>}
 *          ⚠️ `success:false` 的两种：`id` 查不到（「模板不存在」）、
 *          一个可改字段都没传（「没有可更新的字段」）。`before` 是**改动前**的快照（审计用）。
 */
export function templateAdminUpdate(params) {
  return post(endpoints.templateAdmin.update, q(params))
}

/**
 * 删模板 —— **逻辑废弃**（`Enabled = 0`，行还在，规则 v2：不物理删）。
 *
 * @returns {Promise<{success:boolean, msg:string, before?:object}>}
 *          ⚠️ `id` 查不到 ⇒ `success:false` + 「模板不存在」。
 */
export function templateAdminDelete(id) {
  return post(endpoints.templateAdmin.delete, q({ id }))
}
