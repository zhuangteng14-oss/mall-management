// src/api/marketing.js —— 营销域「优惠券」API 封装（C3 块 5 · commit 1）。
//
// 两组端点、一个模块：
//   · 管理端 `/admin/marketing/coupon/*` 7 条 —— 唯一调用方 `views/admin/MarketingPage.vue`
//   · 用户端 `/marketing/*` 4 条 —— 调用方 `views/HomePage.vue`（领券中心 = 本周新券）、
//     `components/CouponWalletDialog.vue`（用户中心的券包弹窗）与 `views/ProductPage.vue`（详情页「领券」块）
// ⚠️ 两组**同域不同壳**（管理端 vs 用户端），故合在一个模块里 —— 它们读写的是**同一张 `coupon` 表**，
//    拆成两个文件只会让「券的类型码 / Bucket 取值」这类口径有两处可抄错。
//
// 为什么单列一个模块（而不是在 view 里直接 `http.post`）：
//   ① **路径不在这里硬编码** —— 一律取自 `endpoints.js`（全仓唯一允许出现后端路径字面量的文件）；
//   ② 参数一律走 `URLSearchParams` 字符串 —— 本仓 `http.post` 的既有形态。⚠️ **别改成发 JSON**：
//      后端收的是 `@RequestParam`，`application/json` 的 body 它**不绑定**，而请求照样 200
//      ⇒ 表现为「静默查不到 / 静默没建」；
//   ③ **空值一律不发** —— 后端参数都是 `required = false` + Service 手判；一个空串在
//      `create` 里会被当成「传了空值」而报出「参数不合法」的错，而不是「没传」（见下面 `q()`）。
//
// ⚠️ 与 `api/template.js` / `api/order.js` 的 `q()` / `post()` **是同形复制、不是共享** ——
//    本仓 `api/*.js` 各模块自带这两个内联函数，未抽公共模块。
//
// ⚠️ **`account` 是「账号」= 登录名（`login.UserName` = `user.username`）—— 2026-10-12 改口径。**
//    改前它是 `user.customerid`（客户业务号）。改制的**唯一理由**：真库 13 个商家 `customerid`
//    全是 NULL（种子商家直接建号、没走过客户阶段）⇒ 商家在券包上**没有账号可挂**，「两端一份券包」
//    就没法成立。登录名是两类账号**都有、且同一个**的那个键（`login` 47 行 ↔ `user` 47 行一一对应）。
//    ⚠️ **后端别只改 DAO**：`CouponController` 四条的身份判据也从「比业务号」换成了
//    `requireSelfOrAdminByUsername`（比登录名）—— 不换就是非管理员**恒 403**。
//    ⚠️ 调用方一律用 `api/auth.js` 的 `getUsername()`，**不要**用 `getCustomerId()`（对商家是空串）
//    也**不要**用 `resolveBuyerId()`（它 customerid 优先 ⇒ 客户会拿到错的那个值）。
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

// ── 管理端：券治理（`/admin/marketing/coupon/*`，TD-18：无角色闸）─────────────────

/**
 * 券列表。
 * @param {{Status?:string, keyword?:string, limit?:number}} [params]
 *        `Status` 走白名单：`'active'` / `'stopped'`；**未知值后端报错**（不静默当成「不筛」）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, msg?:string}>}
 *          ⚠️ `total` 是**同筛选条件下**的总数（不是全表）；`list.length < total` ⇒ 已截断。
 */
export function couponList(params) {
  return post(endpoints.marketingCoupon.list, q(params))
}

/**
 * 建券。
 *
 * @param {{Name:string, Type:string, FaceValue:string|number, Threshold?:string|number,
 *          TotalQty?:string|number, Scope?:string, MerchantAccount?:string,
 *          CategoryMatch?:string, StartAt?:string, EndAt?:string}} params
 *        · `Type` ∈ `'threshold'`（满减）/ `'percent'`（折扣）/ `'none'`（无门槛）
 *        · `FaceValue`：满减/无门槛 = **减免金额**；折扣 = **折扣率**（`0.85` = 8.5 折，**须 < 1**）
 *        · `Threshold`：门槛金额；`none` 时后端**强制**置 0；`threshold` 时**必须 > 0**
 *        · `TotalQty`：`0` 或省略 = **不限量**
 *        · `Scope` ∈ `'all'`（默认）/ `'merchant'`（部分商家 ⇒ **必须**同时给 `CategoryMatch`）
 *        · `StartAt` / `EndAt`：`'YYYY-MM-DD HH:MM:SS'`（**必须零填充**），可空；两个都给时 `EndAt` 必须晚于 `StartAt`
 *        ⚠️ 数字一律以**字符串**发（后端自解析并点名是哪个字段错），别依赖 JS 的数字→字符串转换。
 * @returns {Promise<{success:boolean, msg:string, CouponId?:string}>}
 *          成功时带新建的 `CouponId`（业务串，形如 `CPABCD12345678`）—— 停用 / 查明细要它。
 */
export function couponCreate(params) {
  return post(endpoints.marketingCoupon.create, q(params))
}

/**
 * 停用券（**逻辑废弃**：`Status='stopped'`，行还在）。
 * ⚠️ 已领的券不受影响，只挡**新的**领取。重复停用 ⇒ `success:false` +「券已经是停用状态」。
 * @returns {Promise<{success:boolean, msg:string}>}
 */
export function couponStop(couponId) {
  return post(endpoints.marketingCoupon.stop, q({ CouponId: couponId }))
}

/**
 * 重新启用券。
 * ⚠️ **已过有效期的券会被拒**（「启用也不会有人能领」）—— 那不是可以靠改状态绕过的。
 * @returns {Promise<{success:boolean, msg:string}>}
 */
export function couponActivate(couponId) {
  return post(endpoints.marketingCoupon.activate, q({ CouponId: couponId }))
}

/**
 * 某张券的领取明细（含券定义本身，供页面并排显示）。
 * @returns {Promise<{success:boolean, row:object|null, list:Array, limit:number}>}
 *          `list[]` 每行：`{GrantId, CouponId, ToAccount, Source:'claim'|'grant', Status:'unused'|'used',
 *          OrderId, UsedAt, GrantedAt}`
 */
export function couponGrants(couponId, limit) {
  return post(endpoints.marketingCoupon.grants, q({ CouponId: couponId, limit }))
}

/**
 * 定向发放：给**指定的人**发一张券（`Source='grant'`）。与自助领取共用同一套闸
 * （券存在 / 未停用 / 未过期 / 还有余量 / 这个没领过）。
 * @returns {Promise<{success:boolean, msg:string, CouponId?:string}>}
 */
export function couponGrant(couponId, toAccount) {
  return post(endpoints.marketingCoupon.grant, q({ CouponId: couponId, ToAccount: toAccount }))
}

/**
 * 券 tab 的图表数据（三件齐里的「图」）。
 * @returns {Promise<{success:boolean,
 *   trend:{days:string[], claimed:number[], used:number[]},
 *   types:Array<{Type:string, Cnt:number}>,
 *   totals:{claimed:number, used:number, coupons:number}}>}
 *          ⚠️ `trend` 的三条数组**等长且同序**（后端已按日期并集补零对齐）——
 *          别在前端再对齐一次，会错位一格。
 */
export function couponStats() {
  return post(endpoints.marketingCoupon.stats, '')
}

// ── 用户端：领券中心 / 券包 / 领券（`/marketing/*`）─────────────────────────────

/**
 * 领券中心：可领的券。
 * @param {string} account **账号（登录名）** —— 见文件头那条 ⚠️。
 * @param {number} [limit] 列表上限；省略 ⇒ 后端默认 100（`CouponService.DEFAULT_LIMIT`）。
 * @param {'1'} [thisWeek] `'1'` ⇒ 只回**本周新发布**的（主页领券中心）；省略 ⇒ **全部可领**
 *        （用户中心弹窗的「可领」tab，含旧券）。
 *        ⚠️ 两者是**同一个查询的两个筛法**（用户 2026-10-11 裁定「甲」）⇒ 主页那批天然是
 *        「可领」的**子集**，不会有两个真相。`q()` 对 `undefined` 不发键 ⇒ 省略即「全量」。
 * @param {string} [merchantAccount] 商家业务号 ⇒ **只回该商家发的商家券**（后端 `Scope='merchant'`
 *        那一支；空 / 省略 = 平台券与商家券**都要**，即改动前的行为）。
 *        ⚠️ C3 块 5 · A 组新加（用户 A4）：商品详情页领券区显的是**这家店的券** ——
 *        用户拍板的是「不按商品/类目过滤」（后端没有那一维），但**要按商家收窄**。
 *        ⚠️ 与 `thisWeek` 是**正交**的两个筛：可以同时传（本店 · 本周新）。
 * @returns {Promise<{success:boolean, list:Array, limit:number, daysLeft:number}>}
 *          `list[]` 在券定义之外多两个**给这个人算的**布尔：
 *          · `Claimed`（`true`/`1`）—— 此人已领过 ⇒ 按钮画成「已领取」并禁用
 *          · `SoldOut`（`true`/`1`）—— 券已领完 ⇒ 按钮画成「已领完」并禁用
 *          外加一个 `IsNew`（`true`/`1`）—— **近 3 天新建**（用户裁定 ⑥）⇒ 打 NEW 角标。
 *          `daysLeft` = **本周还剩几天**（后端按**库时钟**算，周一 = 7 … 周日 = 1；前端不碰浏览器钟）。
 *          ⚠️ 三个布尔的**形态可能是 `true` 也可能是 `1`**（Connector/J 的 `tinyInt1isBit`）
 *          ⇒ 判断要**宽容**（走 `config/coupon-format.js` 的 `truthy`）。
 *          ⚠️ **排序由后端定好**（`CouponDao.selectClaimable` 按 `updated_at ASC` ⇒ **最早更新的在最左**，
 *             用户 2026-10-11 裁定）⇒ 前端**按数组原序从左到右铺**（`HomePage.vue` 的 `.tk-strip`），
 *             别自己再排一遍。⚠️ 与 `myCoupons()` **不是一套口径**：那边按**紧迫度**，这边按**新旧**。
 */
export function coupons(account, limit, thisWeek, merchantAccount) {
  return post(endpoints.marketing.coupons, q({ Account: account, limit, thisWeek, MerchantAccount: merchantAccount }))
}

/**
 * 券包（我的券）。
 *
 * @param {string} account **账号（登录名）**。
 * @returns {Promise<{success:boolean, list:Array, redDot:boolean, expiringCount:number}>}
 *          ⚠️ **排序由后端定好**（`expiring` 快过期 → 最上 / `usable` 正常 → 中间 /
 *          `used` 已用 / `expired` 已过期 → 沉底）⇒ **前端按数组原序渲染，别自己再排一遍**。
 *          每行带 `Bucket`（`'expiring'|'usable'|'used'|'expired'`）供上标签与上色。
 *          `redDot` = 有快过期的券（券包红点，用户 2026-10-11 裁定 #4 ②）。
 */
export function myCoupons(account) {
  return post(endpoints.marketing.myCoupons, q({ Account: account }))
}

/**
 * 领券。
 * ⚠️ **核销不在这里** —— 核销发生在结算（`orderAdd` 带 `couponId`），块 5 commit 5 落。
 * @returns {Promise<{success:boolean, msg:string, CouponId?:string}>}
 *          ⚠️ 后端 `msg` **不带「领取失败：」前缀**（前缀归调用方 `HomePage.vue` / `ProductPage.vue` 拼），
 *             也别把几档原因合并 —— 每条都是**一个**原因：「券不存在」/「该券还未开始」/「该券已过期」/「该券已领完」/「该券已停止发放」/「您已领取过该券」。
 */
export function claim(account, couponId) {
  return post(endpoints.marketing.claim, q({ Account: account, CouponId: couponId }))
}

/**
 * **一键领取「本周全部」**（主页领券中心右侧那颗按钮）。
 *
 * ⚠️ **不传 CouponId** —— 领哪些由**服务端**定（「本周新发布 + 启用 + 未过期 + 我还没领过 + 还有余量」）。
 *    「本周」是服务端语义，前端手里那份列表可能是旧的 ⇒ 不在前端定集合。
 * @param {string} account **账号（登录名）**。
 * @returns {Promise<{success:boolean, msg:string, total:number, claimed:number, failed:number,
 *   daysLeft:number, items:Array<{CouponId:string, Name:string, success:boolean, msg:string}>}>}
 *          ⚠️ **顶层 `success:true` ≠ 全成功** —— 它是「批量动作**执行完毕**」。
 *          部分失败**刻意**不回落 `success:false`：那会诱导调用方重发整批，而每张的成败
 *          已经在 `items[].success` 里逐条如实回报了（后端 `CouponService.claimBatch` 写了三条理由）。
 *          ⇒ 前端要**按 `claimed` / `failed` 汇总**，别只看顶层布尔。
 */
export function claimBatch(account) {
  return post(endpoints.marketing.claimBatch, q({ Account: account }))
}

// ── C3 块 5 · commit 3〈国补〉────────────────────────────────────────────────────
//
// 三个子域的**同一张表族**（`subsidy_rule` 规则 + `subsidy_goods` 提名/确认），双向制：
// **平台提名 → 商家确认**。故这里的函数分两拨：管理端「建规则 / 提名 / 看提名」
// （对应 `AdminSubsidyController`），商家端「看发给我的 / 同意或拒绝」（对应 `SubsidyController`）。
//
// ⚠️ **补贴金额由后端现算**（`SubsidyAmount` / `PayAmount`），前端**不许**自己拿
//    `Price × Ratio` 再算一遍 —— 「Cap = 0 表示不封顶」这条规则复刻到前端就多一份会跑偏的真相。
// ⚠️ **`/marketing/subsidy/goods` 是「登录可见、匿名 401」**（它不挂 `IdentityGuard`，但也不在
//    `AUTH_WHITELIST` 里 —— 后端刻意没为它放宽公开面）。⇒ 调用方必须**先判 `isLoggedIn()`**
//    再发，否则 `http.js` 的 `handleUnauthorized` 会把公开页踢回登录页（同 `NotifyInbox.vue`
//    与商品详情页领券块那条先例）。

// ── 管理端：国补治理（`/admin/marketing/subsidy/*`，TD-18：无角色闸）─────────────

/**
 * 国补规则列表。
 * @param {{Status?:string, limit?:number}} [params]
 *        `Status` 走白名单：`'active'` / `'stopped'`；**未知值后端报错**（不静默当成「不筛」）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, msg?:string}>}
 *          `list[]`：`{Id, RuleId, Name, CategoryMatch, Ratio, Cap, Status, StartAt, EndAt, created_at, updated_at}`
 *          ⚠️ `Ratio` 是**小数**（`0.1500` = 15%）；`Cap = 0` = **不封顶**。
 *          `StartAt` / `EndAt` 为 `null` = **不限**（不是「已过期」）。
 */
export function subsidyRuleList(params) {
  return post(endpoints.marketingSubsidy.list, q(params))
}

/**
 * 建国补规则。
 * @param {{Name:string, CategoryMatch?:string, Ratio:string|number, Cap?:string|number,
 *          StartAt?:string, EndAt?:string}} params
 *        · `CategoryMatch`：适用类目名，**逗号分隔**；空 = 不限（对齐 `goods.GoodType`）
 *        · `Ratio`：补贴比例。⚠️ **既收 `0.15` 也收 `15`** —— 后者按百分数读成 0.15
 *          （页面上那格写「15」比写「0.15」自然）。必须 > 0 且 ≤ 1（100%）
 *        · `Cap`：单笔补贴上限（元）。`0` / 省略 = **不封顶**
 *        · `StartAt` / `EndAt`：`'YYYY-MM-DD HH:MM:SS'`（也收 `'YYYY-MM-DD'`），可空 = 不限
 * @returns {Promise<{success:boolean, msg:string, RuleId?:string}>}
 *          成功时带新建的 `RuleId`（业务串，形如 `SBABCD12345678`）—— 提名 / 停用要它。
 */
export function subsidyRuleCreate(params) {
  return post(endpoints.marketingSubsidy.create, q(params))
}

/** 停用国补规则（**逻辑废弃**：`Status='stopped'`，行还在）。重复停用 ⇒ `success:true` + `updated:0`。 */
export function subsidyRuleStop(ruleId) {
  return post(endpoints.marketingSubsidy.stop, q({ RuleId: ruleId }))
}

/** 重新启用国补规则。 */
export function subsidyRuleActivate(ruleId) {
  return post(endpoints.marketingSubsidy.activate, q({ RuleId: ruleId }))
}

/**
 * 提名一件商品参加国补 —— 本域**唯一**会触达商家的写端点。
 * ⚠️ 成功会**同时**给商品所属商家发一条 `subsidy.nominated` 系统通知（商家在消息中心同意 / 拒绝）。
 *    ⇒ 商家那边收到之前，`subsidy_goods.Status` 恒为 `pending`，**用户端看不到任何东西**。
 * @param {string} ruleId
 * @param {number|string} goodsId `goods.Id`（**自增主键**，不是 `ProductCode`）
 * @returns {Promise<{success:boolean, msg:string, GoodsId?:number, GoodName?:string,
 *   MerchantId?:string, RuleName?:string}>}
 *          重复提名 ⇒ `success:false` +「该商品已在这条规则下，无需重复提名」（撞唯一键，不是 500）。
 */
export function subsidyNominate(ruleId, goodsId) {
  return post(endpoints.marketingSubsidy.nominate, q({ RuleId: ruleId, GoodsId: goodsId }))
}

/**
 * 提名列表（管理端看全量：谁被提名了、商家点头没有）。
 * @param {{Status?:string, MerchantId?:string, limit?:number}} [params]
 *        `Status` ∈ `'pending'` / `'approved'` / `'rejected'`；省略 = 全部
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          `list[]`：`{Id, RuleId, GoodsId, GoodName, MerchantId, Status, Reason, created_at, updated_at,
 *          RuleName, Ratio, Cap}`
 */
export function subsidyNominations(params) {
  return post(endpoints.marketingSubsidy.nominations, q(params))
}

// ── 商家端 / 用户端（`/marketing/subsidy/*`）───────────────────────────────────

/**
 * 商家端：**发给我的**国补提名。
 * @param {string} merchantId 商家业务号（`goods.MerchantId` = `login.Id`）。⚠️ 传别人的 ⇒ **403**。
 * @param {{Status?:string, OnlyOpen?:string, limit?:number}} [params] `Status` 省略 = 全部（页面自己分「待处理 / 已处理」）；`OnlyOpen` 传字面 `'true'` ⇒ 只回**规则还没到期**的行（店铺主页「平台活动」tab 的待办表用，⚠️ 缺省即同旧行为）—— ⚠️ 那条判据比的是 `subsidy_rule.EndAt`，是**替代量**（`subsidy_goods` 没有报名截止列），只作**列表显示范围**用、不是准入闸（逐字见 `SubsidyDao.NOT_EXPIRED` 的 javadoc）
 * @returns {Promise<{success:boolean, list:Array, msg?:string}>}
 *          `list[]` 在提名行之外多带 `RuleName` / `Ratio` / `Cap` / `RuleEndAt`（页面要显示「补多少、到什么时候」）。
 */
export function subsidyInvitations(merchantId, params) {
  return post(endpoints.subsidy.invitations, q({ MerchantId: merchantId, ...(params || {}) }))
}

/**
 * 商家端：同意 / 拒绝一条提名。
 *
 * @param {string} merchantId 商家业务号 —— 传别人的 ⇒ **403**（`IdentityGuard`）。
 * @param {number|string} id `subsidy_goods.Id`
 * @param {boolean} approve ⚠️ **缺省即拒绝**（后端把非 `"true"` 一律当拒绝）——
 *        「没表态」绝不等于「同意参加」。拒绝时 `reason` **必填**。
 * @param {string} [reason] 拒绝理由（同意时忽略）
 * @returns {Promise<{success:boolean, outcome?:'confirmed'|'rejected'|'already-handled',
 *   msg:string, Id?:number}>}
 *          ⚠️ `outcome:'already-handled'` 时 **`success` 仍是 `true`** —— 那是「这行早处理过了」，
 *          不是失败（CAS 落空）。页面照常刷新列表即可，**别弹错误**。
 */
export function subsidyConfirm(merchantId, id, approve, reason) {
  return post(endpoints.subsidy.confirm,
    q({ MerchantId: merchantId, Id: id, Approve: approve ? 'true' : 'false', Reason: reason }))
}

/**
 * 国补商品列表（用户端「国家补贴」标签 / 筛选的数据源）。
 *
 * ⚠️ **只在已登录时发**（见本段文件头那条）。后端只回**已确认 + 规则在期 + 商品在架**的行
 * ⇒ 见到一行就等于「这件商品此刻真的在享受国补」。
 *
 * @param {{GoodType?:string, limit?:number}} [params] `GoodType` 非空 ⇒ 只回该类目
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          `list[]`：`{Id, GoodsId, GoodName, MerchantId, RuleId, RuleName, Ratio, Cap,
 *          Price, GoodImg, GoodType, ProductCode, SubsidyAmount, PayAmount}`
 *          **`SubsidyAmount` / `PayAmount` 是后端算好的**（见文件头那条 ⚠️）。
 */
export function subsidyGoods(params) {
  return post(endpoints.subsidy.goods, q(params))
}

// ── C3 块 5 · commit 4〈秒杀〉────────────────────────────────────────────────────
//
// **双向制**（`docs/agent/c3-modules.md` §9.1 的硬口径）：两条路径，发起方与决定方**相反** ——
//   · 路径 A **邀请**：管理端发起（`/admin/marketing/seckill/invite`）→ **商家**应答
//     （`/marketing/seckill/respond`）。
//   · 路径 B **申请**：商家发起（`/marketing/seckill/apply`）→ **管理端**裁决
//     （`/admin/marketing/seckill/review`）。
// ⚠️ **没有「管理端单向指定」** —— 那会让商家在不知情的情况下被挂上秒杀价。
// ⚠️ **两条路径「成立」时各生成一行 `seckill_activity`**；用户端 / 主页 / 秒杀页**只读活动表**
//    （`/marketing/seckill/live`），不 union 两张流程表。
//
// ⚠️ **时间点进库前必须换算成 UTC**：`seckill_activity` / 两张流程表的 `ApplyEndAt` / `StartAt` /
//    `EndAt` 都是**时刻**，后端判据比的是**库时钟 `NOW()`**（a3 实测 `@@system_time_zone='UTC'`）。
//    页面侧 `datetime-local` 拿到的**是本地时间** ⇒ 必须经 `toSqlTime()` 反算成 UTC 文本再发
//    （`MarketingPage.vue` 里那两个方向对称的换算函数），否则活动会**早开 / 晚开 8 小时且不报错**。
//
// ⚠️ **`/marketing/seckill/live` 是「登录可见、匿名 401」**（不挂 `IdentityGuard`，也不在
//    `AUTH_WHITELIST` 里 —— 后端刻意没放宽公开面），口径逐字同 `/marketing/subsidy/goods`
//    ⇒ 调用方必须**先判 `isLoggedIn()`** 再发，否则 `handleUnauthorized` 会把公开页踢回登录页。

// ── 管理端：秒杀治理（`/admin/marketing/seckill/*`，TD-18：无角色闸）─────────────

/**
 * 邀请列表。
 * @param {{Status?:string, MerchantAccount?:string, limit?:number}} [params]
 *        `Status` 走白名单：`'invited'` / `'accepted'` / `'declined'`（未知值后端报错）。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number, msg?:string}>}
 *          `list[]`：`{Id, InvitationId, MerchantAccount, GoodsId, GoodName, OriginalPrice,
 *          SeckillPrice, Quantity, ApplyEndAt, StartAt, EndAt, Status, Reason, created_at,
 *          updated_at, Stage}`
 *          ⚠️ `Id`（自增）与 `InvitationId`（业务串 `SKI-…`）**两个都在**：前者是通知 `TargetId`
 *          与商家「同意 / 拒绝」要打的那个号，后者进审计的 `TargetId`。
 */
export function seckillInvitationList(params) {
  return post(endpoints.marketingSeckill.invitations, q(params))
}

/**
 * 邀请商家上秒杀（路径 A 的发起端）—— 会**同时**给该商家发一条 `seckill.invited` 通知。
 *
 * @param {{MerchantAccount:string, GoodsId:number|string, SeckillPrice:string|number,
 *          Quantity?:string|number, ApplyEndAt?:string, StartAt?:string, EndAt?:string}} params
 *        · `MerchantAccount`：商家业务号 —— **必须与商品所属商家一致**（不一致后端报
 *          「这件商品不属于该商家」，不许把 A 店的商品挂到 B 店名下打折）
 *        · `SeckillPrice`：秒杀价。**必须 > 0 且 < 商品原价**
 *        · `Quantity`：限量。`0` / 省略 = **不限量**（与「`Cap=0` 不封顶」同一个「没填」口径）
 *        · `ApplyEndAt` / `StartAt` / `EndAt`：`'YYYY-MM-DD HH:MM:SS'`（**UTC**，见段注释），可空。
 *          给了 `StartAt` 与 `EndAt` 时必须是「开始早于结束」；`ApplyEndAt` 不得晚于 `StartAt`
 *        ⚠️ 数字一律以**字符串**发（后端自解析并点名是哪个字段错）。
 * @returns {Promise<{success:boolean, msg:string, Id?:number, InvitationId?:string,
 *   GoodName?:string, OriginalPrice?:number, SeckillPrice?:number}>}
 *          ⚠️ 同商家同商品**已有一条待应答邀请**时 ⇒ `success:false` +「已有一条待应答的邀请」
 *          （唯一键管不到这条，是 Service 层的守卫）。
 */
export function seckillInvite(params) {
  return post(endpoints.marketingSeckill.invite, q(params))
}

/**
 * 申请列表（管理端看全量）。`Status` ∈ `'submitted'` / `'approved'` / `'rejected'`；省略 = 全部。
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          `list[]` 列同邀请，业务号那列是 `ApplicationId`（`SKA-…`）。
 */
export function seckillApplicationList(params) {
  return post(endpoints.marketingSeckill.applications, q(params))
}

/**
 * 审核商家的秒杀申请（路径 B 的裁决端）。
 *
 * @param {{Id:number|string, Approve:boolean, Reason?:string, Name?:string,
 *          ApplyEndAt?:string, StartAt?:string, EndAt?:string}} params
 *        · `Approve`：⚠️ **缺省 / 非 `"true"` 一律当驳回** —— 「没表态」绝不等于同意。
 *          驳回时 `Reason` **必填**（后端判）。
 *        · `Name`：活动名；留空 ⇒ 回落成**商品名**（流程表没有 `Name` 列）。
 *        · **通过时 `StartAt` / `EndAt` 必填**（申请路径的时间由**管理端审核时**确认，不是商家报名时带的）
 *          —— 缺了后端直接拒：没有窗口的活动判不出阶段。`ApplyEndAt` 可空。
 * @returns {Promise<{success:boolean, outcome?:'approved'|'rejected'|'already-handled',
 *   msg:string, Id?:number, ActivityId?:string, Name?:string}>}
 *          ⚠️ `outcome:'already-handled'` 时 **`success` 仍是 `true`**（CAS 落空，不是失败）。
 *          ⚠️ 时段与同商品已成立的活动**重叠** ⇒ `success:false`（不落一场重叠活动）。
 */
export function seckillReview(params) {
  return post(endpoints.marketingSeckill.review, q(params))
}

/**
 * 活动列表（**已成立**的那些：哪条路径来的、什么阶段、卖了多少）。
 * @param {{Status?:string, MerchantAccount?:string, limit?:number}} [params] `Status` ∈ `'active'` / `'stopped'`
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          `list[]`：`{Id, ActivityId, Name, MerchantAccount, GoodsId, GoodName, OriginalPrice,
 *          SeckillPrice, TotalStock, SoldStock, SourceKind:'invitation'|'application', SourceId,
 *          Status, ApplyEndAt, StartAt, EndAt, created_at, updated_at, Stage}`
 *          ⚠️ `Stage` 由**后端按库时钟现算**（`'signup'|'upcoming'|'live'|'ended'`）——
 *          前端**别自己拿时间再算一遍**（浏览器钟 ≠ 库钟，差 8 小时）。
 */
export function seckillActivityList(params) {
  return post(endpoints.marketingSeckill.activities, q(params))
}

/**
 * 停用一场已成立的秒杀活动（**逻辑废弃**：`Status='stopped'`，行还在）。
 * ⚠️ 入参是 **`ActivityId` 业务串**（不是自增 `Id`）—— 那一列在管理端列表里就有。
 * ⚠️ 本动作**不发通知**：商家在自己的活动列表里看得到 `stopped`，再寄一封就是同一件事说两遍。
 */
export function seckillStop(activityId) {
  return post(endpoints.marketingSeckill.stop, q({ ActivityId: activityId }))
}

// ── 商家端 / 用户端（`/marketing/seckill/*`）────────────────────────────────────

/**
 * 商家端：**发给我的**秒杀邀请。
 * @param {string} merchantAccount 商家业务号（`goods.MerchantId` = `login.Id`）。⚠️ 传别人的 ⇒ **403**。
 * @param {{Status?:string, limit?:number}} [params] `Status` 省略 = 全部（页面自己分「待应答 / 已处理」）。⚠️ `list[]` 每行带 **`Stage`**（`SeckillDao.stage()` 在**库时钟**上算的：`signup` 报名截止未到 / `upcoming` 未开启 / `live` 进行中 / `ended` 已结束）——「平台活动」tab 的待办表只留 `signup`（⚠️ 前端**不自己比时间**：库里存 UTC、浏览器按本地时区解析会差一个时区）。⚠️ 回体**不含 `Status` 列**（`INVITATION_COLUMNS` 没投影它）⇒ 「还没答」只能靠入参 `Status:'invited'` 收窄，别想在行上判
 */
export function seckillInvitations(merchantAccount, params) {
  return post(endpoints.seckill.invitations, q({ MerchantAccount: merchantAccount, ...(params || {}) }))
}

/**
 * 商家端：同意 / 拒绝一条秒杀邀请。
 *
 * @param {string} merchantAccount 商家业务号 —— 传别人的 ⇒ **403**（`IdentityGuard`）。
 * @param {number|string} id `seckill_invitation.Id`
 * @param {boolean} accept ⚠️ **缺省即拒绝**。拒绝时 `reason` **必填**。
 * @param {string} [reason] 拒绝理由（同意时忽略）
 * @returns {Promise<{success:boolean, outcome?:'accepted'|'declined'|'already-handled',
 *   msg:string, Id?:number, ActivityId?:string}>}
 *          ⚠️ **同意 ⇒ 当场成立一场活动**（回带 `ActivityId`）；若该邀请**没有开始 / 结束时间**
 *          或时段与已有活动**重叠** ⇒ `success:false`（**不会**翻状态，见后端 Service 的两道闸）。
 *          ⚠️ `already-handled` 时 `success` 仍是 `true`，页面刷新即可，**别弹错误**。
 */
export function seckillRespond(merchantAccount, id, accept, reason) {
  return post(endpoints.seckill.respond,
    q({ MerchantAccount: merchantAccount, Id: id, Accept: accept ? 'true' : 'false', Reason: reason }))
}

/** 商家端：我提交过的秒杀申请（含审核结果）。 */
export function seckillApplications(merchantAccount, params) {
  return post(endpoints.seckill.applications, q({ MerchantAccount: merchantAccount, ...(params || {}) }))
}

/**
 * 商家端：报名秒杀（路径 B 的发起端）—— 会**同时**给管理端发一条 `seckill.application.submitted`。
 *
 * ⚠️ **不带时间点**：申请路径的时间由管理端审核时确认。
 * @param {string} merchantAccount 商家业务号 —— 商品必须属于它，否则后端拒。
 * @param {number|string} goodsId `goods.Id`（**自增主键**，不是 `ProductCode`）
 * @param {string|number} seckillPrice 秒杀价：**> 0 且 < 商品原价**
 * @param {string|number} [quantity] 限量；`0` / 省略 = 不限量
 * @returns {Promise<{success:boolean, msg:string, Id?:number, ApplicationId?:string}>}
 *          同商品**已有一条待审核的申请** ⇒ `success:false`。
 */
export function seckillApply(merchantAccount, goodsId, seckillPrice, quantity) {
  return post(endpoints.seckill.apply,
    q({ MerchantAccount: merchantAccount, GoodsId: goodsId, SeckillPrice: seckillPrice, Quantity: quantity }))
}

/** 商家端：我参与（已成立）的秒杀活动 —— 含被停用的（`Status='stopped'`）。 */
export function seckillActivities(merchantAccount, params) {
  return post(endpoints.seckill.activities, q({ MerchantAccount: merchantAccount, ...(params || {}) }))
}

/**
 * 用户端：进行中 / 未开启的秒杀活动（主页秒杀模块 + 秒杀页的唯一数据源）。
 *
 * ⚠️ **只在已登录时发**（见本段文件头那条）。后端已挡掉**已结束 / 已停用 / 商品下架**，
 * 故见到一行就等于「这一场此刻仍然挂着」。
 *
 * @param {{limit?:number}} [params]
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          `list[]` = 活动列 + `GoodImg` / `GoodType` / `ProductCode` + 后端补的两个字段：
 *          · `Stage`：`'signup'`（报名期，**主页不显示**——后端会回，前端按 `Stage` 分流）/
 *            `'upcoming'`（未开启，主页显「活动未开启」占位）/ `'live'`（进行中，显秒杀段）
 *          · `SoldOut`（**真布尔**，后端在 Java 侧用数字比出来的）/ `SoldLeft`（剩余件数，不限量时为 `null`）
 *            ⚠️ 别拿 `TotalStock` / `SoldStock` 自己比 —— 不限量（`TotalStock=0`）那一档的语义
 *            在 `SoldOut` 里已经定好了。
 */
export function seckillLive(params) {
  return post(endpoints.seckill.live, q(params))
}
