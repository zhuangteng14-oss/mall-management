// src/api/brand.js —— 品牌加盟自服务 API（第三批㉗，2026-10-19）
//
// 两条端点，身份判据都是**登录名**（`user.username`）—— 与 `api/merchant.js` 同口径
// （`IdentityGuard.requireSelfOrAdminByUsername`；传业务号会 403）：
//   · `POST /merchant/brand/overview` —— 四态概览（active / pending / rejected / none）
//   · `POST /merchant/brand/apply`    —— 提交品牌加盟申请
//
// ⚠️ 与 `api/merchant.js#merchantBrandApply`（`/merchant/brandApply`）**不是同一个端点**：
//    那条是批四第二批的旧入口（入参面 = 入驻申请的字段集）；本模块对应第三批㉗ 的
//    品牌档案接口，多收 `brandName` / `trademarkNo` / `logoUrl` 等品牌字段。
//    两个端点都还在，页面各自调用，别合并。
//
// ⚠️ 参数口径同 `api/merchant.js` 文件头：URLSearchParams、空值不发、拆 `Response` 外壳。
import { http } from './http'
import { endpoints } from './endpoints'

/** 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/** 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body。 */
async function post(path, body) {
  const res = await http.post(path, body)
  return res.json()
}

/**
 * 品牌加盟四态概览。
 *
 * @param {string} username 登录名（`user.username`）。
 * @returns {Promise<{success:boolean, state:'active'|'pending'|'rejected'|'none',
 *          merchantId:string, profile:object|null, application:object|null,
 *          pendingApplication:object|null, rejectReason:string|null,
 *          attemptsInWindow:number, maxAttemptsInWindow:number, cooldownHours:number,
 *          nextApplyAt:string|null, canApply:boolean, msg:string}>}
 *          ⚠️ `state` 是服务端算的**唯一判据**（前端四张态卡按它高亮，不要自己拼条件）；
 *          `canApply` 已含 24h 冷却与 30 天 3 次上限，`msg` 是给用户看的拦截原因。
 */
export function brandOverview(username) {
  return post(endpoints.brandB4.overview, q({ username }))
}

/**
 * 提交品牌加盟申请。
 *
 * @param {{username:string, brandName:string, shopName?:string, licenseNo?:string,
 *          contactName?:string, contactPhone?:string, shopAddress?:string,
 *          licenseImage?:string, brandNameEn?:string, trademarkNo?:string,
 *          trademarkClass?:string, brandLevel?:string, logoUrl?:string,
 *          licenseUrl?:string, mainCategory?:string, remark?:string}} params
 *        `username` 与 `brandName` 必填；其余可选。
 * @returns {Promise<{success:boolean, msg:string, applicationId?:number, batchId?:string,
 *          attemptNo?:number, state?:string}>}
 *          ⚠️ 申请**不改身份**：通过审核只置 `user.IsBrand=1`，且服务端要求申请人**已是商家**
 *          （否则 `success:false` +「该账号尚未入驻，不能申请品牌加盟」）。
 */
export function brandApply(params) {
  return post(endpoints.brandB4.apply, q(params))
}
