// probe-b5h-bug123.mjs —— C3 块 5 ·〈Bug 1/2/3〉真库核对（2026-10-12）
//
// 查什么（对跑着的 a3 后端 8088，不用 mock）：
//   ① 两个公开列表端点是否都带 `IsSeckill` / `IsSoldOut` / `IsSubsidy` 三个投影列；
//   ② 全库同码重复组有几组、都是谁（判据 = `(MerchantId, ProductCode)`）；
//   ③ **前端那一支 `sharedList`（去重 + 滤已售完）到底会把 N 行压成几行** —— 直接 import
//      `src/config/goods-flags.js` 的**真源码**跑，不是在这里重写一遍判据（重写就是自证）。
//   ④ 本店商品端点（店铺页那一支）**照旧含已售完**（用户裁定 ②：售完只在自己店铺页显示）。
//
// ⚠️ 为什么要 import 真源码：本笔的判据有两处（后端 SQL + 前端 `goods-flags.js`），
//    这里验的是**前端那一处**；在本文件里手抄一份 `dedupeGoods` 只会验出「我抄对了」。
//    前端是 vue-cli 工程（package.json 无 `type: module`）⇒ 直接 import 会被当 CJS 解析而报
//    `Unexpected token 'export'`。手法：读源码 → 去掉 `export ` 词 → 走 `data:` URL 动态 import。
//    **只删 `export` 这个词**，其余逐字不碰（改一个字符这探针就不算验真源码了）。
//
// 用法：node scripts/probes/probe-b5h-bug123.mjs [baseUrl] [outPath]
// 产物：默认 _work/b5h-probe.json（原始响应）+ 控制台摘要
// ⚠️ **第二参是后加的**（2026-10-12）：本探针会被**反复跑**（每轮修完再取一次快照），
//    而它写的文件被证据文件按名引用 ⇒ 直接覆盖会把上一轮的原始响应冲掉。要留旧的那份，
//    或者换个名字跑：`node … probe-b5h-bug123.mjs http://localhost:8088 _work/b5h-probe-round2.json`。

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..', '..')
const BASE = process.argv[2] || 'http://localhost:8088'
const OUT = resolve(ROOT, process.argv[3] || '_work/b5h-probe.json')

// ── 真源码 import（见头注）────────────────────────────────────────────────────────
const FLAGS_SRC = resolve(ROOT, 'Mall-management-web/mall-management-system/src/config/goods-flags.js')
const src = readFileSync(FLAGS_SRC, 'utf8')
const stripped = src.replace(/\bexport\s+/g, '')
// ⚠️ 去掉 `export` 词后声明就成了模块私有的 ⇒ 必须**在末尾补一条 export**（否则 import 回来是空对象，
//    表现是「没导出 dedupeGoods」而不是报语法错 —— 这一坑本探针第一版就踩了）。补的只有这一条
//    export 语句，函数体逐字是仓里那份。
const shim = stripped
  + '\nexport { dedupeGoods, sharedList, isSeckill, isSoldOut, isSubsidy }\n'
const flags = await import('data:text/javascript;charset=utf-8,' + encodeURIComponent(shim))
const { dedupeGoods, sharedList, isSeckill, isSoldOut, isSubsidy } = flags
for (const [n, f] of Object.entries({ dedupeGoods, sharedList, isSeckill, isSoldOut, isSubsidy })) {
  if (typeof f !== 'function') throw new Error(`goods-flags.js 没导出 ${n}（import 手法失效了？）`)
}

// ── HTTP ───────────────────────────────────────────────────────────────────────
const postForm = async (path, params) => {
  const body = new URLSearchParams(params || {})
  const r = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body
  })
  const text = await r.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) { json = { __unparsed: text.slice(0, 400) } }
  return { status: r.status, json }
}

const rowsOf = (resp) => {
  const j = resp.json || {}
  const d = j.data || j
  const list = (d && (d.list || d.list_goods || d.goods)) || []
  return Array.isArray(list) ? list : []
}

const dupGroups = (rows) => {
  const m = new Map()
  rows.forEach((g) => {
    const code = String(g.ProductCode || '')
    if (!code) return
    const k = String(g.MerchantId || '') + '|' + code
    if (!m.has(k)) m.set(k, [])
    m.get(k).push({ Id: g.Id, GoodName: g.GoodName, Price: g.Price })
  })
  return Array.from(m.entries()).filter(([, v]) => v.length > 1)
    .map(([k, v]) => ({ key: k, rows: v }))
}

const flagCount = (rows, fn) => rows.filter(fn).length
const hasFlags = (rows) => rows.length > 0 && rows.every((g) => 'IsSeckill' in g && 'IsSoldOut' in g && 'IsSubsidy' in g)

// ── 跑 ─────────────────────────────────────────────────────────────────────────
const home = await postForm('/goods/goodsSelectHome', {})
const home1 = rowsOf(home)                       // 第一次（可能打库）
const home2 = rowsOf(await postForm('/goods/goodsSelectHome', {}))   // 第二次（应命中缓存）
// 搜索：**中文关键词必须走 URLSearchParams**（Git Bash 的 argv→CP936 会把中文绞成乱码）
const search = await postForm('/goods/productSearch', { keyword: '耳机' })
const searchRows = rowsOf(search)
const shop = await postForm('/goods/goodsSelect', { MerchantId: 'dRD3gqtbFr' })
const shopRows = rowsOf(shop)

const out = {
  at: new Date().toISOString(),
  base: BASE,
  home: {
    status: home.status, rows: home1.length, cacheSecondCall: home2.length,
    hasThreeFlags: hasFlags(home1),
    isSeckill: flagCount(home1, isSeckill), isSoldOut: flagCount(home1, isSoldOut), isSubsidy: flagCount(home1, isSubsidy),
    dups: dupGroups(home1),
    sharedListLen: sharedList(home1).length,
    dedupeLen: dedupeGoods(home1).length,
    soldOutNames: home1.filter(isSoldOut).map((g) => `${g.Id}/${g.GoodName}/${g.ProductCode}`)
  },
  search: {
    status: search.status, keyword: '耳机', rows: searchRows.length,
    hasThreeFlags: hasFlags(searchRows),
    isSeckill: flagCount(searchRows, isSeckill), isSoldOut: flagCount(searchRows, isSoldOut), isSubsidy: flagCount(searchRows, isSubsidy),
    dups: dupGroups(searchRows),
    sharedListLen: sharedList(searchRows).length,
    codes: searchRows.map((g) => String(g.ProductCode || ''))
  },
  shop: {
    status: shop.status, merchant: 'dRD3gqtbFr', rows: shopRows.length,
    hasThreeFlags: hasFlags(shopRows),
    isSoldOut: flagCount(shopRows, isSoldOut),
    soldOutNames: shopRows.filter(isSoldOut).map((g) => `${g.Id}/${g.GoodName}`)
  }
}
mkdirSync(resolve(ROOT, '_work'), { recursive: true })
writeFileSync(OUT, JSON.stringify({ out, raw: { home: home.json, search: search.json, shop: shop.json } }, null, 2), 'utf8')

console.log(JSON.stringify(out, null, 2))
console.log('\n── 摘要 ──')
console.log(`主页 goodsSelectHome：${out.home.rows} 行，三列${out.home.hasThreeFlags ? '齐' : '缺'}；`
  + `秒杀 ${out.home.isSeckill} / 售完 ${out.home.isSoldOut} / 国补 ${out.home.isSubsidy}；`
  + `去重后 ${out.home.dedupeLen} 行 ⇒ sharedList ${out.home.sharedListLen} 行（第二次调用仍 ${out.home.cacheSecondCall} 行）`)
console.log(`同码重复组：${out.home.dups.length} 组 → ${JSON.stringify(out.home.dups)}`)
console.log(`搜索「耳机」：${out.search.rows} 行 ⇒ sharedList ${out.search.sharedListLen} 行；售完 ${out.search.isSoldOut}；码 = ${out.search.codes.join(', ')}`)
console.log(`本店 goodsSelect(dRD3gqtbFr)：${out.shop.rows} 行，其中已售完 ${out.shop.isSoldOut} 行（店铺页照显）`)
console.log(`原始响应 → ${OUT}`)
