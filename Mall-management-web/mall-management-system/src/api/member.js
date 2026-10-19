// src/api/member.js —— 会员 VIP 用户端 API（批四第二批 commit 3，2026-10-19）
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
