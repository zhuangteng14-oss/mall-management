// src/config/coupon-format.js —— 券的**字段 → 人话**（C3 块 5 · 券的信息架构重排，2026-10-11）。
//
// 为什么单开一个模块（而不是两页各抄一份）：
//   这些函数的**取值域全部与后端强耦合** —— `Type` ∈ {threshold, percent, none}、
//   `Bucket` ∈ {expiring, usable, used, expired}、`Scope` ∈ {all, merchant} 逐字写在
//   `marketing/coupon/dao/CouponDao.java` 的 SQL 里（那个分桶 `CASE` 与 `COUPON_COLUMNS`）。
//   抄两份 ⇒ 第二处抄错时**两边都不报错**，只是某一页慢慢说起了另一种话。
//   ⇒ 主页「领券中心」（`views/HomePage.vue`）与券包弹窗（`components/CouponWalletDialog.vue`）**共用这一份**。
//   房内先例：`config/notify-events.js`（后端 code ↔ 前端中文标签，同为「共享的非组件逻辑」）。
//
// ⚠️ 与 `views/admin/MarketingPage.vue` 的 `faceLabel` / `typeLabel` 是**同口径的另一份**
//    （管理端券治理表格与建券表单，那页还要管 `Type` 的可选项）。本笔**不动**它：它被
//    `docs/agent/slot-registry.md` 按行号引。若将来两处打架，以**后端 SQL** 为准。

/** SQL 的 `'YYYY-MM-DD HH:MM:SS'` 里那一位补零（`toLocal` 用）。 */
const pad = (n) => String(n).padStart(2, '0')

/**
 * 库内无时区文本 → **浏览器本地**的 `'YYYY-MM-DD HH:MM'`。
 *
 * ⚠️ 末尾那个 `Z` **不是**可有可无：后端所有时间列都是 `DATE_FORMAT(…, '%Y-%m-%d %H:%i:%s')`
 *    出来的**无时区文本**，而库里存的是 **UTC**。省掉 `Z` 就按浏览器本地时区解析，
 *    显示上整差一个偏移，且**错得很安静**（格式完全正常）。
 * ⚠️ 券的 `StartAt` / `EndAt` **确实是 UTC**，别被「有效期是用户填的」误导：
 *    管理端建券时在 `datetime-local` 里按本地时区挑、**提交前反算成 UTC 再发**
 *    （`admin/MarketingPage.vue` 的 `toSqlTime`；2026-10-11 手验修的就是这一处），
 *    列表与明细显示时再换算回来 —— 与这里同一个口径。
 *
 * ⚠️ 逐字同款副本已散在 6 个管理端 view（`AccountsPage` / `CatalogPage` / `MarketingPage` /
 *    `NotifyPage` / `AuditPage` / `OrderPage` 各一份 `function toLocal`）。本文件是**第 7 份**，
 *    刻意**不回头去收敛那 6 份**：它们各自被 `slot-registry.md` 按行号引，抽公共 = 一次越块改动。
 */
export function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime) // 解析不了原样回 —— 不崩、不空白
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

/**
 * `Claimed` / `SoldOut` / `IsNew` / `redDot` 的宽容判据。
 * ⚠️ 这几个布尔由 JDBC 出来，形态**可能是 `true` 也可能是 `1`**（Connector/J 的 `tinyInt1isBit`）
 *    ⇒ 别写 `=== true`，那会在「库里回 1」的那天把「已领完」的券画成能领。
 */
export const truthy = (v) => v === true || v === 1 || v === '1'

/** 面值的人话：折扣券读「8.5 折」，另两类读「¥N」。 */
export const couponFace = (c) => {
  const v = Number(c.FaceValue || 0)
  return c.Type === 'percent' ? (v * 10).toFixed(1) + ' 折' : '¥' + (Math.round(v * 100) / 100)
}

/** 门槛的人话。`Threshold` 后端对 `none` 型**强制**置 0 ⇒ 「无门槛」是数据说的，不是我猜的。 */
export const couponCond = (c) => {
  if (c.Type === 'percent') return '折扣券'
  const t = Number(c.Threshold || 0)
  return t > 0 ? '满 ' + t + ' 可用' : '无门槛'
}

/**
 * 适用范围的人话。`MerchantAccount` **不晒** —— 那是后台业务号（`M…` 串），
 * 给用户看没有意义；用户认的是**商品种类名**，有就带上。
 */
export const scopeLabel = (c) =>
  c.Scope === 'merchant'
    ? (c.CategoryMatch ? '部分商家 · 限 ' + c.CategoryMatch : '部分商家')
    : '全场通用'

/**
 * `Bucket` → 中文。取值域 = `CouponDao.selectMyGrants` 那个分桶 `CASE`
 * （`expiring` / `usable` / `used` / `expired`）。
 * ⚠️ 兜底回 `b` 原文（照 `notify-events.js` 那条：**任何一行都不许渲染成空白**）。
 */
export const bucketLabel = (b) =>
  ({ expiring: '快过期', usable: '可用', used: '已使用', expired: '已过期' }[b] || b)

/**
 * 「X 天后过期」/「今天过期」。入参 = `CouponDao.selectMyGrants` 的 `EndDaysLeft`
 * （`TIMESTAMPDIFF(DAY, NOW(), c.EndAt)`，**由库时钟算** —— 前端不碰浏览器钟）。
 *
 * ⚠️ `<= 0` 一律说「今天过期」：`TIMESTAMPDIFF` 是**截断**（24h 内回 0、已过期回负值），
 *    两者都属「今天到头了」，分开说只会让人算不明白。
 * ⚠️ 无到期日（`EndAt IS NULL`）回**空串** —— 调用方据此不渲染这一行。
 *    别让它落到 `Number(null) === 0` 那条上，那会把「长期有效」说成「今天过期」。
 */
export const endLabel = (d) => {
  if (d === null || d === undefined || d === '') return ''
  const n = Number(d)
  if (!Number.isFinite(n)) return ''
  return n <= 0 ? '今天过期' : n + ' 天后过期'
}

/**
 * 有效期一行。两端**各自可空**（`COUPON_COLUMNS` / `selectMyGrants` 投影出来就是 `NULL`）：
 *   · 两端都空 ⇒ 「长期有效」（后端也确实不判任何边界）；
 *   · 只填止    ⇒ 「即日起 ~ …」（后端「只填止不填起」= 立即可领）；
 *   · 只填起    ⇒ 「… 起」（后端只要过了这一刻才可领，话术是「该券还未开始」）。
 */
export const periodLabel = (startAt, endAt) => {
  if (!startAt && !endAt) return '长期有效'
  if (!startAt) return '即日起 ~ ' + toLocal(endAt)
  if (!endAt) return toLocal(startAt) + ' 起'
  return toLocal(startAt) + ' ~ ' + toLocal(endAt)
}
