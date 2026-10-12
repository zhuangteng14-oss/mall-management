// src/api/shop.js —— 店铺域「关注关系 + 公开档案」API 封装（C3 块 5 · B 组 · commit C；续笔 2026-10-12）。
//
// 四个端点、一个模块（后端 `ShopFollowController` + `ShopProfileController`，都是 `@RequestMapping("/shop")`，
// 四条全 POST）：
//   follow / unfollow / followState —— 唯一调用方 `views/ShopHome.vue`（店铺主页的头卡）。
//   profile                         —— 同上（头卡的店名 / 开店时长 / 认证徽章）。
//
// 为什么单列一个模块（而不是在 view 里直接 `http.post`）：三条理由与 `api/marketing.js` 逐字同款 ——
//   ① **路径不在这里硬编码** —— 一律取自 `endpoints.js`（全仓唯一允许出现后端路径字面量的文件）；
//   ② 参数一律走 `URLSearchParams` 字符串 —— 本仓 `http.post` 的既有形态。⚠️ **别改成发 JSON**：
//      后端收的是 `@RequestParam`，`application/json` 的 body 它**不绑定**，而请求照样 200
//      ⇒ 表现为「静默没关注 / 静默没取关 / 静默查不到」；
//   ③ **空值一律不发** —— 后端六个入参**全部** `required = false` + Service 手判（为什么不做成
//      裸 `@RequestParam` 的 400，理由写在 `ShopFollowController` 类注释里）。
//
// ⚠️ 与 `api/marketing.js` / `api/order.js` / `api/template.js` 的 `q()` / `post()` 是**同形复制、
//    不是共享** —— 本仓 `api/*.js` 各模块自带这两个内联函数，未抽公共模块。
//
// ⚠️ **四条里只有 `profile` 是公开面**（续笔 2026-10-12）：`/shop/profile` 已进
//    `WebAuthConfig.AUTH_WHITELIST`（**12 → 13 条**）⇒ **未登录也能打** —— 店铺主页是商品详情页
//    「进店逛逛」的落点，未登录进来也得知晓店名 / 开店时长。⚠️ **另三条**（follow / unfollow /
//    followState）**仍不在白名单**（匿名调它必 401，与 `marketing` / `cart` 同族）⇒ 那三条的调用方
//    **必须先有登录态**，`account` 只能传**当前登录者自己的**业务号。
//    判据与落法见 `ShopHome.vue` 的 `buyerId`（`customerid` 兜 `merchantid`，与 `ProductPage.vue`
//    的 `customerId` **同一条降级链** —— 两条链分叉就等于两种「我是谁」）。
//
// ⚠️ **传别人的 `CustomerId` 是 403**（不是「查不到」）：关注三条各自跑
//    `IdentityGuard.requireSelfOrAdmin(request, CustomerId)`。（⚠️ `profile` 不跑 —— 见上。）
//    ⇒ `account` **绝不要**从路由 / URL 取。
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
 * 关注一家店。**幂等** —— 已关注再点一次仍是 `success:true`，`shop_follow` 表里仍 1 行
 * （重复关注撞唯一键被 Service 吞掉，理由见 `ShopFollowService` 类注释）。
 *
 * @param {string} account    关注者的业务号（`user.customerid`）—— **只能是自己的**。
 * @param {string} merchantId 被关注商家的业务号（`user.merchantid`）。
 * @returns {Promise<{success:boolean, msg:string, following:boolean, followers:number}>}
 *          ⚠️ `success:false` 有一条是**业务规则**、不是故障：`msg` = `'不能关注自己'`
 *          （`CustomerId` 与 `MerchantId` 字面相等时，见 `ShopFollowService.isSelf`）。
 *          ⚠️ `followers` 是**回读**（写后重查全店粉丝数）—— **不是**「原值 + 1」。
 */
export function follow(account, merchantId) {
  return post(endpoints.shop.follow, q({ CustomerId: account, MerchantId: merchantId }))
}

/**
 * 取关。**物理 DELETE 行**（关系表取关即删，用户 2026-10-12 裁定 8；语义见 `ShopFollowDao` 类注释）。
 * 本来就没关注 ⇒ 也回 `success:true`（同幂等口径）。
 *
 * ⚠️ 这条**不判**「不能关注自己」：自己关注自己的行本来就建不出来（那道闸挡在 `follow` 写侧）
 * ⇒ 给自己传自己只会删 0 行，是个无害空操作。
 *
 * @returns {Promise<{success:boolean, msg:string, following:boolean, followers:number}>}
 *          `following` 同样是**回读**（取关一条本就不存在的关注 ⇒ 写侧删 0 行，但「现在没关注」
 *          依然是真话，见 `ShopFollowService.stateResult`）。
 */
export function unfollow(account, merchantId) {
  return post(endpoints.shop.unfollow, q({ CustomerId: account, MerchantId: merchantId }))
}

/**
 * 关注状态 + 该店粉丝数（`ShopHome` 头卡的**一次**请求）。
 *
 * ⚠️ **两个值一次拿**：后端三个端点**都**回 `followers`，就是为了让头卡不必为「粉丝数」多发一次往返。
 *
 * ⚠️ **这条也刻意不判**「是不是自己的店」（`follow` 那道闸不在它身上）—— 判了会把
 *    「自己的店铺主页」整页的粉丝数弄没；前端那边是**隐藏关注按钮**，不是不请求。
 *    ⇒ 自己的店：这条照常发，`following` 恒 `false`（自己关注自己的行建不出来），`followers` 照真。
 *
 * @returns {Promise<{success:boolean, msg?:string, following:boolean, followers:number}>}
 *          失败时（缺参 / 库错）回 `{success:false, msg}` —— **不带** `following` / `followers`
 *          ⇒ 调用方**别**把缺失当 `false` / `0` 画出去（那是两个假事实）。
 */
export function followState(account, merchantId) {
  return post(endpoints.shop.followState, q({ CustomerId: account, MerchantId: merchantId }))
}

/**
 * 一家店的**公开档案**：登录名 / 注册时刻 / 入驻审核状态（`ShopHome` 头卡的**一次**请求）。
 * 后端 `ShopProfileController`，`@PostMapping("/shop/profile")`。
 *
 * ⚠️ **本条与上面三条不同族 —— 它是公开面**：已进 `WebAuthConfig.AUTH_WHITELIST` ⇒
 *    **未登录也能打**，`MerchantId` 从路由取（不是「我是谁」的问题，是「看哪家店」）。
 *    也**不**跑 `IdentityGuard`（不碰买家侧数据，理由见 `ShopProfileController` 类注释）。
 *
 * @param {string} merchantId 被查看的商家业务号（`route.query.MerchantId`）。
 * @returns {Promise<{success:boolean, MerchantId:string, UserName:string,
 *                    RegisteredAt:(string|null), CertStatus:string, msg?:string}>}
 *          ⚠️ **四个「不知道」各有各的写法，别把它们归成一个默认值**（见 `ShopProfileService`
 *          类注释「三个不编」）：`UserName` 查不到 ⇒ **空串**；`RegisteredAt` 列本身可为 NULL
 *          ⇒ **`null`**（前端据此**不画**「开店时长」，画「今天开店」是假话）；
 *          `CertStatus` **一条入驻申请都没有 ⇒ 空串**，**不是 `'pending'`**（「没提交过」≠「在审核中」）
 *          ⇒ 前端只有 `=== 'approved'` 才画徽章。
 */
export function profile(merchantId) {
  return post(endpoints.shop.profile, q({ MerchantId: merchantId }))
}
