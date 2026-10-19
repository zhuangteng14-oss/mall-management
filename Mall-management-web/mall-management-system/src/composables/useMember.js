// src/composables/useMember.js —— 会员展示口径（批四第二批 commit 3，2026-10-19）
//
// ⚠️ 数据源**只有** `api/auth.js` 的会话恢复态（`/user/me` 的 `user` 整行）：
//    本模块**不发请求**。`main.js` 启动时已经打过一次 `/user/me`，再打只会让公开页多一条 401。
//
// ⚠️ 本模块算出来的价**只用于展示**。真正落库的实付价由服务端 `orderAdd` 决定：
//    `OrderService.addLocked` 用 `MemberPricing.priceOf` 算同一个数，用券门槛也以它为准。
//    前端这份副本必须与 `shared/MemberPricing.java` **同一个公式**：
//    `Math.round(price * 90) / 100`（不是 `price * 0.9` 后 `toFixed(2)` —— 后者在
//    99.99 上给出 89.99 没错，但在 JS 里先乘再四舍五入的落点与 Java 的 `Math.round`
//    在 .5 边界上一致，故两处可逐位对齐；⚠️ 别用 `toFixed`，它做的是「四舍五入到最近」，
//    而此处要的是「分位取整」且避免字符串化带来的二次误差）。
import { computed } from 'vue'
import { getMemberLevel, getBalance, getIsBrand } from '../api/auth'

/** 折扣率：全场 9 折（与 `MemberPricing.RATE` 同值，**只用于展示**）。 */
export const MEMBER_RATE = 0.9
/** 折扣文案：「9 折」。 */
export const MEMBER_RATE_TEXT = 9

/**
 * 会员价 = `round(price × 90) / 100`，与后端口径逐位一致。
 * 非数 / 负数 ⇒ 原样回传（不编造 0 或 NaN 的展示价）。
 */
export function memberPriceOf(price) {
  const n = Number(price)
  if (!Number.isFinite(n) || n <= 0) return n
  return Math.round(n * 90) / 100
}

/**
 * 会员展示态。返回的 computed 都只读本地身份：
 * - `level` 0 普通 / 1 VIP（无恢复态 ⇒ 0）
 * - `isVip` 是否 VIP
 * - `balance` 余额（展示用）
 * - `isBrand` 商家是否为品牌加盟店（仅商家侧有意义）
 */
export function useMember() {
  const level = computed(() => Number(getMemberLevel()) || 0)
  const isVip = computed(() => level.value > 0)
  const balance = computed(() => Number(getBalance()) || 0)
  const isBrand = computed(() => !!getIsBrand())
  return { level, isVip, balance, isBrand }
}
