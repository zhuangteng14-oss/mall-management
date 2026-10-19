// src/api/member.js —— 会员 VIP 用户端 API（批四第二批 commit 3，2026-10-19）
//
// ⚠️ 2026-10-19（第三批㉘）**文件尾追加**了会员中心的四条（等级矩阵 / 概览 / 签到 /
//    积分流水，`MemberController`）—— 它们与下面两条不是同一组端点，身份判据也不同
//    （下面收登录名，下面那组收客户业务号）。文件头这段描述的是**原有两条**。
//
// 两条端点、一个模块：
//   · `POST /user/member/open`      —— 会话内自助开通 VIP（演示用；管理员也可经
//                                      `api/accounts.js#accountsSetMember` 设置等级）
//   · `POST /marketing/coupon/daily` —— 领取今日会员专属券（服务端按模板建券，前端不传 CouponId）
//
// 为什么单列模块：调用方是新建的 `views/MemberCenter.vue` 与 `UserInfoPage.vue`
// （「开通会员」按钮），与 `api/accounts.js`（管理端）/ `api/marketing.js`（券治理）
// 都不是同一个壳。⚠️ 管理端的「设置等级」仍在 `api/accounts.js` —— 它与用户治理同一组端点前缀。
//
// ⚠️ 参数口径同全仓 `api/*.js` 的既有形态（见 `api/marketing.js` 文件头）：
//    ① 路径一律取自 `endpoints.js`；② 发 `URLSearchParams` 表单串（后端收 `@RequestParam`，
//    发 JSON 会**静默不绑定**）；③ 空值一律不发。
import { http } from './http'
import { endpoints } from './endpoints'

function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

async function post(path, body) {
  return (await http.post(path, body)).json()
}

/**
 * 自助开通 VIP（把 `user.MemberLevel` 置 1）。
 *
 * @param {{username:string}} params `username` = **登录名**（`user.username`），身份判据是
 *        `IdentityGuard.requireSelfOrAdminByUsername` —— 传业务号会 403。
 * @returns {Promise<{success:boolean, msg:string, username?:string, memberLevel?:number}>}
 *          ⚠️ 开通后**不会**自动刷新本地展示态：调用方应提示「刷新后生效」，或在拿到
 *          `success` 后原地把页面展示切到 VIP（本页 `MemberCenter.vue` 就是这么做的）。
 *          重新进站 / 刷新时 `main.js` 的 `/user/me` 会把新档位带回来。
 */
export function openMember(username) {
  return post(endpoints.memberB4.open, q({ username }))
}

/**
 * 领取今日会员专属券。仅 VIP 可领；同一账号同一天只能领一张
 * （`CouponId=VIPDAILY-yyyyMMdd` + 既有唯一键 `(CouponId, ToAccount)`）。
 *
 * @param {{Account:string}} params `Account` = 登录名。
 * @returns {Promise<{success:boolean, msg:string, CouponId?:string}>}
 *          同日重复 ⇒ `success:false` +「您已领取过该券」（不是异常）。
 */
export function claimDailyCoupon(account) {
  return post(endpoints.memberB4.dailyCoupon, q({ Account: account }))
}

// ── 会员中心 · 等级 / 积分 / 签到（第三批㉘，2026-10-19）────────────────────────
//
// ⚠️ 与上面的 `memberB4` 是**两组端点**：那组是「开通 / 管理端设级 / 每日券」，
//    这组是 `MemberController` 的 V0–V6 矩阵与积分账户。身份判据也不同 ——
//    下面三处收的是 `CustomerId`（客户**业务号**），不是登录名。

/**
 * V0–V6 权益矩阵（公开配置，不判身份）。
 *
 * @returns {Promise<{success:boolean, list:Array<{LevelNo:number, Code:string, Name:string,
 *          MinGrowth:number, DiscountRate:number, PointsRate:number, MonthlyCoupons:number,
 *          PriorityWeight:number, Theme:string, BenefitsJson:string}>}>}
 */
export function memberLevels() {
  return post(endpoints.memberCenter.levels, '')
}

/**
 * 会员概览：积分账户 + 当前等级 + 距下一级还差多少成长值 + 今日是否已签到。
 *
 * @param {string} customerId 客户业务号（`user.customerid`）。
 * @returns {Promise<{success:boolean, account:object|null, level:object|null,
 *          nextLevel:object|null, growthToNext:number, signedToday:boolean}>}
 *          ⚠️ `account` 为 `null` 表示还没有积分账户（首次获得积分时才建）——
 *          前端按 0 积分渲染，不要当成错误。
 */
export function memberOverview(customerId) {
  return post(endpoints.memberCenter.overview, q({ CustomerId: customerId }))
}

/**
 * 每日签到（库侧 `CURDATE()` 判今日，JVM 不构造日期）。
 *
 * @param {string} customerId 客户业务号。
 * @returns {Promise<{success:boolean, msg:string, points?:number, streak?:number,
 *          balance?:number, signedToday:boolean}>}
 *          已签到 ⇒ `success:true, signedToday:true, points:0`（**不是**异常）。
 */
export function memberSignin(customerId) {
  return post(endpoints.memberCenter.signin, q({ CustomerId: customerId }))
}

/**
 * 积分流水（按时间倒序）。
 *
 * @param {{customerId:string, limit?:number}} params `limit` 缺省 20、服务端夹在 1–100。
 * @returns {Promise<{success:boolean, list:Array<{Id:number, ChangeAmount:number,
 *          BalanceAfter:number, GrowthDelta:number, BizType:string, BizId:string,
 *          RemainAmount:number, ExpireAt:string|null, Remark?:string, created_at:string}>,
 *          total:number, limit:number}>}
 */
export function memberPointLogs(params) {
  return post(endpoints.memberCenter.logs, q({
    CustomerId: params && params.customerId,
    limit: params && params.limit,
  }))
}
