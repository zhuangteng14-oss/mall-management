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
 * @param {string} account 用户业务号（`user.customerid`）。
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
 * @param {string} account 用户业务号。
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
 * @param {string} account 用户业务号。
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
