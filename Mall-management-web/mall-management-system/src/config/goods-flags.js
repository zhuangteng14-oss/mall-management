// goods-flags.js —— 商品**列表侧共用判据**的**唯一定义处**（前端侧）：
//   ① 三个「此刻是不是」标记（`isSeckill` / `isSoldOut` / `isSubsidy`）—— 「这件商品此刻是不是在
//      秒杀 / 国补 / 已售完」；
//   ② 同码去重（`dedupeGoods`）与「共享面要显示的那一份」（`sharedList`）。
//
// ⚠️ 与后端 `marketing/MarketingGoodsFlags.java`（2026-10-12 新增）**同名同职**：那边是元凶，
//    这里只读它算好的三个标记 —— 判据**只有一份**，就是那条 SQL。本模块**不**在前端重算
//    「在不在期 / 售没售完」：库钟与浏览器钟差 8 小时（`a3` 实测库时区 UTC），前端自己比时间
//    会整整错一个时区且**不报错**（同 `SeckillPage.vue` 模板里那条按语）。
//
// 三个标记的来路（`goodsSelectHome` / `productSearch` 两个**公开**端点投影里的三列）：
//   · `IsSeckill`  —— 挂着一条 `Status='active'` 且 `stage()='live'` 的秒杀活动
//   · `IsSoldOut`  —— 在 `IsSeckill` 之上再加 `TotalStock > 0 && SoldStock >= TotalStock`
//   · `IsSubsidy`  —— `subsidy_goods.Status='approved'` 且规则在期
//
// ⚠️ 判据写成 `Number(v) === 1` 而**不是** `!!v` 或 `v == true`：MySQL 的 `EXISTS` 回 0/1，
//    但**驱动把它退成字符串**这种坑本仓踩过（`ProductPage.vue` 里 `SoldOut` 那条订正逐字写着
//    「SQL 里写 0/1 会被驱动退成字符串，`'0'` 在 JS 里是真值」）⇒ 那种情况下 `!!'0'` = `true`，
//    会把**没**售完的当售完。`Number('0') === 1` = false、`Number('1') === 1` = true、缺键
//    （`undefined`）⇒ `NaN` ⇒ false —— 老响应 / 别的端点来的行都不会炸，也不会误判。
const flag = (v) => Number(v) === 1

/** 这一件此刻在秒杀（`live`，含已售完那种 —— 售完**仍是**秒杀商品，两个标记独立）。 */
export const isSeckill = (g) => flag(g && g.IsSeckill)

/** 这一件此刻在秒杀**且**那场已售完。⚠️ 只对「秒杀场次」有意义，不表示普通商品没库存（`goods` 表无库存列）。 */
export const isSoldOut = (g) => flag(g && g.IsSoldOut)

/** 这一件此刻在国补（商家已确认 + 规则在期）。 */
export const isSubsidy = (g) => flag(g && g.IsSubsidy)

// ── 列表侧的另外两件共用判据：同码去重 / 「共享面要显示的那一份」──────────────────────────
// ⚠️ 放在同一个模块里，是因为它们与上面三个标记**同源**：都由「列表端点回的那一行」的形状决定，
//    也都是**后端算不了、只能前端做**的事（同一个 `goodsSelectHome` 同时喂共享面与店铺面 ⇒ 服务端
//    不知道该按哪一边的规则筛）。

/**
 * 按 **`MerchantId|ProductCode`** 去重，同键留 **`Id` 最小**的那一行（空码行一律保留、不并）。
 *
 * ⚠️ 为什么需要它：`goods` 表**没有三元组唯一约束** ⇒ 同店同码可以有两行（实测全库 1 组：
 *    `dRD3gqtbFr` 的 `GD-101-9552` = `Id` 76 / 120 同名同价，见
 *    `docs/agent/evidence/c3-block5/b5h-bug1-detail-blank.md`）—— 不去重就会出现「同一次列表里
 *    同一件货两张卡」（用户 ① 报的现象）。
 * ⚠️ 键**必须含 `MerchantId`**：不同店用同一个 `ProductCode` 是两件**不同**的货（各自的编号体系），
 *    只按码去重会把别家的商品**误并**掉一件（跨店列表 = 主页 / 搜索页 / 两处「猜你喜欢」，都是跨店）。
 * ⚠️ 保留 **`Id` 最小**那一行：与详情页那半边**刻意一致** —— 〈Bug 1〉把
 *    `GoodsCacheService.priceByTriple` 改成了 `ORDER BY Id ASC LIMIT 1`（同一个判据）。两边选同一行，
 *    「列表里看到的那件」与「点进去看到的那件」才是同一件。
 * ⚠️ 空码不并：`ProductCode=''` = 没登记编码，多行空码是**不同**商品（后端那条聚合 SQL 也写着
 *    `WHERE ProductCode <> ''`）。
 */
export function dedupeGoods(list) {
  const best = new Map()
  const rows = list || []
  rows.forEach((g) => {
    const code = String((g && g.ProductCode) || '')
    if (!code) return
    const key = String((g && g.MerchantId) || '') + '|' + code
    const kept = best.get(key)
    if (!kept || Number(g.Id) < Number(kept.Id)) best.set(key, g)
  })
  return rows.filter((g) => {
    const code = String((g && g.ProductCode) || '')
    return !code || best.get(String((g && g.MerchantId) || '') + '|' + code) === g
  })
}

/**
 * **共享面**要显示的那一份列表 = 去重 + 滤掉已售完。
 *
 * 用户裁定（②）：已售完（`IsSoldOut` = 在秒杀且那场售罄）**只在自己店铺页显示** ⇒ 主页「为你推荐」/
 * 搜索页 / 两处「猜你喜欢」四处统一走这一支；店铺页本店商品 / 热销 / 折扣三个 tab **不走**（本店要
 * 显示含已售完，由 `ProductCard` 画灰态 + 红斜标）。`/seckill` 秒杀页也不走（那是另一个面，且它自己
 * 的数据源是 `/marketing/seckill/*`，不经过这里）。
 *
 * ⚠️ 顺序「先去重、后滤售完」：去重后一件货只剩一行，那一行的标记说了算 —— 而它正是详情页会读到的
 * 那一行（同 `dedupeGoods` 的 `MIN(Id)`）⇒ 列表藏了它、详情页也会说「已售完」，两处不打架。
 */
export function sharedList(list) {
  return dedupeGoods(list).filter((g) => !isSoldOut(g))
}
