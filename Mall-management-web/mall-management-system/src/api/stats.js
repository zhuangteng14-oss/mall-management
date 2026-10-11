// src/api/stats.js —— 经营数据看板 API 封装（可视化段 commit 2，2026-10-11）。
//
// 三条端点：`POST /admin/stats`（全站）、`POST /admin/stats/rebuild`（全量重建）、
// `POST /merchant/stats`（本店）。**三条都是 POST** —— 路径集中在 `endpoints.js`，
// 本文件里一个后端路径字面量都不出现（那条判据可 grep 自动化）。
//
// 形状与 `api/admin.js` **有意一致**：`q()` 组装 `URLSearchParams`、`post()` 拆掉 `Response` 外壳。
// ⚠️ 没有抽成公共模块是**刻意的**，与 `api/order.js` 的日期格式化多份副本同一条口径：
// 本仓不建 `src/utils/`，共用一小段代码换来的是「改一处要想到三处」。
import { http } from './http'
import { endpoints } from './endpoints'

/**
 * 组装表单 body：只收**有值**的键（`null` / `undefined` / `''` 一律不发）。
 *
 * ⚠️ 这一条在本文件尤其要紧：后端的 `from` / `to` / `days` 都是 `required = false`，
 * 判「是否筛」用的是 `isBlank(...)`。发一个**空串**过去 = 「有筛，筛空值」——
 * 对 `days` 而言空串会被 `Integer` 绑定判成 `null`（侥幸没事），但对 `from`/`to`
 * 就会变成一次查不到任何东西的筛选。⇒ 统一在入口丢掉。
 */
function q(params) {
  const usp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && String(v) !== '') usp.append(k, String(v))
  })
  return usp.toString()
}

/** 发一发 POST 并**拆掉 `Response` 外壳**，直接答 JSON body（同 `api/admin.js` 的 `post()`）。 */
async function post(path, body) {
  const res = await http.post(path, body)
  return res.json()
}

/**
 * 管理端看板：全站逐日 + 区间合计 + 环比 + 各商家汇总。
 *
 * @param {{from?:string, to?:string, days?:number}} params
 *        `days` 与 `from`/`to` 三选一关系见后端 `StatsService.resolveRange`：
 *        **`days` 优先**（相对库时钟的最近 N 天，且**只有它**能给出 `prevTotals` 环比）；
 *        都没给 ⇒ 回全部已聚合日。`from`/`to` 是 `'YYYY-MM-DD'`。
 * @returns {Promise<{success:boolean, builtRows:number, range:object, days:Array,
 *          totals:object, prevTotals:object|null, merchants:Array, msg?:string}>}
 *          ⚠️ `builtRows === 0` ⇒ **还没预聚合过**（不是「没有订单」）：让用户先去重建，
 *          而不是显示一个「本期 0 元」的、看起来正常的空看板。
 */
export function adminStats(params) {
  return post(endpoints.stats.admin, q(params))
}

/**
 * 全量重建 `stats_daily`：清空 + 从 `order` 重算两个口径。**会写库**。
 *
 * <p>用途：① 首次部署 / 换数据后把历史补上（滚动 job 只碰最近几天，演示数据在窗口外）；
 * ② 订单被全量重导后清掉已经不存在的日期行。
 *
 * ⚠️ 不是幂等语义上的「安全按钮」：它**先删后插**（后端 `@Transactional` 包住）。
 * 页面把它挂在一个**显式按钮**上、点完要重新拉一次数据 —— 别做成进入页面自动调。
 */
export function adminStatsRebuild() {
  return post(endpoints.stats.adminRebuild, '')
}

/**
 * 商家端看板：本店逐日 + 区间合计 + 环比 + 商品榜。
 *
 * @param {{MerchantId:string, from?:string, to?:string, days?:number, goodsLimit?:number}} params
 *        ⚠️ `MerchantId` **必填**（后端第一行手判；空 ⇒ 200 + `success:false` + 中文 msg）。
 * @returns {Promise<{success:boolean, MerchantId:string, builtRows:number, range:object,
 *          days:Array, totals:object, prevTotals:object|null, topGoods:Array, msg?:string}>}
 *          ⚠️ `topGoods` **上线即空是预期**：`order_item` 按裁定不回填存量单
 *          ⇒ 页面按「历史单无明细」呈现空态，**不要**改成去解析 `ProductInformation`。
 */
export function merchantStats(params) {
  return post(endpoints.stats.merchant, q(params))
}
