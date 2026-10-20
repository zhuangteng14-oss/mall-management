// src/api/wallet.js —— 钱包用户端 API（第三批㉙，2026-10-19）
//
// 三条端点，身份判据一律是 `CustomerId`（客户**业务号**，`user.customerid`）：
//   · `POST /wallet/overview` —— 钱包账户 + 最近 5 条流水
//   · `POST /wallet/logs`     —— 完整流水分页（limit 默认 20、上限 100）
//   · `POST /wallet/recharge` —— **沙箱**充值（无支付通道；幂等键 = 前端生成的 `RequestId`）
//
// ⚠️ 提现 `POST /wallet/withdraw` **不进本模块**：它只答「未开放」，前端没有调用点
//    （服务端保留占位，浏览器端不提供入口 —— 见 `WalletController.withdraw` 的注释）。
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
 * 钱包概览：账户行 + 最近 5 条流水。
 *
 * @param {string} customerId 客户业务号（`user.customerid`，**不是**登录名）。
 * @returns {Promise<{success:boolean, wallet:{UserId:string, Available:number, Frozen:number,
 *          TotalRecharge:number, TotalRefund:number, TotalConsume:number, Status?:string}|null,
 *          logs:Array}>}
 *          ⚠️ `wallet` 为 `null` 表示该客户还没有钱包行（首次入账时才建）——
 *          前端应显示 0.00 而不是报错。
 */
export function walletOverview(customerId) {
  return post(endpoints.walletB4.overview, q({ CustomerId: customerId }))
}

/**
 * 钱包流水（按时间倒序）。
 *
 * @param {{customerId:string, limit?:number}} params `limit` 缺省 20、服务端夹在 1–100。
 * @returns {Promise<{success:boolean, list:Array<{Id:number, ChangeType:string, Direction:string,
 *          Amount:number, BalanceBefore:number, BalanceAfter:number, BizType?:string,
 *          BizId?:string, Remark?:string, created_at:string}>, total:number, limit:number}>}
 */
export function walletLogs(params) {
  return post(endpoints.walletB4.logs, q({
    CustomerId: params && params.customerId,
    limit: params && params.limit,
  }))
}

/**
 * 沙箱充值。
 *
 * @param {{customerId:string, amount:number|string, requestId:string}} params
 *        `requestId` **由调用方生成**（8–64 字符）并在重试时**原样复用** —— 这是幂等键：
 *        同一个 `RequestId` 重复提交返回 `duplicate:true` 且不重复入账。
 * @returns {Promise<{success:boolean, msg?:string, duplicate?:boolean, rechargeId?:string,
 *          amount?:number, balanceAfter?:number, status?:string}>}
 *          ⚠️ 金额与单笔上限由服务端判（非法金额是 200 + `success:false`）。
 */
export function walletRecharge(params) {
  return post(endpoints.walletB4.recharge, q({
    CustomerId: params && params.customerId,
    Amount: params && params.amount,
    RequestId: params && params.requestId,
  }))
}
