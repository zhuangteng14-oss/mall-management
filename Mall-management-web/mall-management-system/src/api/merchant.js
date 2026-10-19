// src/api/merchant.js —— 商家**入驻自服务** API 封装（C3 块 2〈用户块〉，2026-10-09）。
//
// ⚠️ **它不是「商家端」的 API** —— 调用它的 `views/MerchantApplyPage.vue` 挂在**用户端**壳下
//    （`/customer/user/apply`）。理由：提交入驻申请的人**还没有** `merchantid`
//    （`MerchantApplicationController` 块首那条）⇒ 放进商家壳等于要求「先有商家身份才能申请当商家」。
//    相应地，**被冻结降级的商家**（`login.Role` 已被改回 1、人在**客户壳**里）也走这一页重新申请，
//    这正是「降级五阶段」里第 ④ 步在界面上的落点。
//
// ⚠️ 与 `src/api/accounts.js` 的分工：那一份是**管理端**的审核侧（`/admin/accounts/application/audit`），
//    本文件是**申请人自己**的提交 / 查询侧（`/merchant/*`）。两侧**都要**存在 —— 后端也是两个
//    Controller，且两条路的权限判据不同（本文件跑 `IdentityGuard.requireSelfOrAdmin`）。
//
// ⚠️ 三条口径与 `accounts.js` / `admin.js` / `notify.js` 逐条相同（本模块是那条 `q()`/`post()` 的
//    **又一份**本地副本，收不收敛见 `accounts.js` 文件头那条说明）：URLSearchParams 而非 JSON、
//    空值不发、一律拆 `Response` 外壳。
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
 * 提交一条入驻申请（**每次提交都是新的一条**，不是覆盖上一次）。
 *
 * @param {{username:string, shopName:string, licenseNo:string, contactName?:string,
 *          contactPhone?:string, shopAddress?:string, licenseImage?:string}} params
 *        ⚠️ `shopName` 与 `licenseNo` **必填**（后端答 `'店铺名称与营业执照号为必填'`）；
 *        其余四项可选，不填就落 NULL。
 *        `licenseImage` 是**图片地址字符串**（不是文件）—— 本块没有「上传资质图」的端点，
 *        页面收一个 URL/路径，留空即不填。
 * @returns {Promise<{success:boolean, batchId?:string, msg?:string}>}
 *          `batchId` = 本次提交的批次号，**也是通知的 `TargetId`** —— 管理端收件箱里那条
 *          `merchant.apply.submitted` 就是按它归位的（管理员靠它找到「要审哪一条」）。
 *          ⚠️ 提交**不改 `login.Role`、不改 `user.usertype`** —— 申请只是**躺在表里等审**，
 *          申请人在审核通过前**仍是客户**（这正是本页必须挂在客户壳下的原因）。
 */
export function merchantApply(params) {
  return post(endpoints.merchant.apply, q(params))
}

/**
 * 我提交过的申请（`SubmittedAt` 倒序）。
 *
 * @param {{username:string, limit?:number}} params
 * @returns {Promise<{success:boolean, list:Array, total:number, limit:number}>}
 *          行结构与管理端那份**同源同形**（同一个 `MerchantApplicationDao.selectByUsername`）：
 *          主要看 `Status`（`pending` / `approved` / `rejected`）与 `RejectReason`。
 *          ⚠️ `RejectReason` 只在 `rejected` 行上有值 —— 它是**给申请人看**的驳回理由。
 */
export function merchantMyApplications(params) {
  return post(endpoints.merchant.myApplications, q(params))
}

/**
 * 提交一条**品牌加盟**申请（批四第二批，2026-10-19）。
 *
 * ⚠️ 与 `merchantApply` 的区别：本接口**不改身份**（通过只置 `user.IsBrand=1`），
 * 且后端要求申请人**已是商家**（`MerchantApplicationService.apply` 用 `selectMerchantId` 判，
 * 否则回 `{success:false, msg:'该账号尚未入驻，不能申请品牌加盟'}`）。
 *
 * @param {{username:string, shopName:string, licenseNo:string, contactName?:string,
 *          contactPhone?:string, shopAddress?:string, licenseImage?:string}} params
 *        入参面与 {@link merchantApply} 相同（店铺名称 / 执照号必填）。
 * @returns {Promise<{success:boolean, batchId?:string, isBrand?:boolean, msg?:string}>}
 */
export function merchantBrandApply(params) {
  return post(endpoints.merchantB4.brandApply, q(params))
}
