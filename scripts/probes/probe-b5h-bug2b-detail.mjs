// probe-b5h-bug2b-detail.mjs —— C3 块 5 ·〈Bug 2b · 详情页「标题在、价/型号/图空缺」〉真库核对
// （2026-10-12）
//
// 用户报的现象（截图 2）：「无线蓝牙耳机 声谷标准版」详情页 —— 标题 ✅ / 价格区只有 `¥` 没数字 /
// 型号区空白 / 图片区空白占位图。
//
// 页面侧读数：标题来自 `route.query.GoodName`（`ProductPage.vue:82` 的 `{{ goodName }}`，**与数据无关**），
// 而 价格 / 图片 / 型号 三处**全部**来自 `allItems`（`:84` 价格行 `currentItem.price`、`:54` 图集
// `v-for item in allItems`、`:183` 型号钮 `allItems.slice(0,4)`）。⇒ 这三处同时空 = **`allItems` 是空数组**。
//
// `allItems` 只有一条填充路径（`loadAllItems`，`:546-573`）：
//   ① `POST /goods/GoodsProductCodeSelect` {MerchantId, GoodName} ⇒ 取 `d1.list` 当**型号码表**
//   ② 对每个码 `POST /goods/productCodePrice` {MerchantId, GoodName, ProductCode} ⇒ `d2.success` 才入列
// 任一步断在前面，`allItems` 就是空的。本探针**逐字照抄前端这两步**（含「中文与空格**不编码**、
// 直接塞进 form body」这个写法 —— 前端就是这么发的，见 `:548` 的模板串），把断点定位到具体那一步。
//
// ⚠️ 为什么不能用 curl：Git Bash 的 argv→CP936 会把中文绞成乱码，凭空造出 `success:false`
//    （本笔上一轮就吃了一次亏）。中文参数一律走本文件的 `postForm`（Node，UTF-8 干净）。
//
// 用法：node scripts/probes/probe-b5h-bug2b-detail.mjs [baseUrl]
// 产物：_work/b5h-bug2b-detail.json

import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..', '..')
const BASE = process.argv[2] || 'http://localhost:8088'
const OUT = resolve(ROOT, '_work', 'b5h-bug2b-detail.json')

const SHOP = 'dRD3gqtbFr'
const GOOD = '无线蓝牙耳机 声谷标准版'   // 用户点名的那件

/** **逐字照抄前端**的发送方式：body 是一段**没有做百分号编码**的字符串（`ProductPage.vue:548`）。
 *  ⚠️ 这是刻意不「修正」的 —— 本探针要复现的就是前端这一发；若这里换成 `URLSearchParams`，
 *     中文与空格会被编成 `%E2%80%A6` / `+`，那验的就不是同一条路（万一差别恰在编码上，
 *     探针会绿而页面照旧空白）。所以两种写法**都发**，对比着看。 */
const postRaw = async (path, body) => {
  const r = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body
  })
  const t = await r.text()
  let j = null
  try { j = JSON.parse(t) } catch (e) { j = { __unparsed: t.slice(0, 300) } }
  return { status: r.status, json: j }
}

/** 同一发请求的「编码版」（`URLSearchParams`）。用于把「编码差异」这一可能单独摘出来。 */
const postEncoded = async (path, params) => postRaw(path, new URLSearchParams(params).toString())

const brief = (j) => {
  if (!j) return null
  const s = JSON.stringify(j)
  return s.length > 500 ? s.slice(0, 500) + '…' : s
}

const out = { at: new Date().toISOString(), base: BASE, shop: SHOP, good: GOOD }

// ── ① 型号码表：前端 :548 那一发（原样）vs 编码版 ────────────────────────────────
const p1raw = await postRaw('/goods/GoodsProductCodeSelect', `MerchantId=${SHOP}&GoodName=${GOOD}`)
const p1enc = await postEncoded('/goods/GoodsProductCodeSelect', { MerchantId: SHOP, GoodName: GOOD })
const codesOf = (resp) => {
  const j = resp.json || {}
  const d = j.data || j
  const list = (d && d.list) || []
  return Array.isArray(list) ? list.map((x) => (typeof x === 'string' ? x : String(x.ProductCode || ''))) : []
}
const codesRaw = codesOf(p1raw)
const codesEnc = codesOf(p1enc)
out.step1 = {
  raw: { status: p1raw.status, codes: codesRaw, body: brief(p1raw.json) },
  encoded: { status: p1enc.status, codes: codesEnc, body: brief(p1enc.json) }
}

// ── ② 逐码问价：前端 :553 那一发 ────────────────────────────────────────────────
const codes = codesRaw.length ? codesRaw : codesEnc
out.step2 = []
for (const code of codes) {
  const r = await postRaw('/goods/productCodePrice', `MerchantId=${SHOP}&GoodName=${GOOD}&ProductCode=${code}`)
  out.step2.push({ code, status: r.status, json: r.json })
}

// ── ③ SKU 表（购入弹窗那一支走 `mergeSkus`，详情页主区**不走**；列出来备查）────────
const sku = await postRaw('/goods/skuList', `MerchantId=${SHOP}&GoodName=${GOOD}`)
out.step3 = { status: sku.status, body: brief(sku.json), rows: ((sku.json && (sku.json.list || [])) || []).length }

// ── ④ 这件商品在两个共享列表端点里长什么样（码 / 名 / 价 / 图 是否齐）─────────────
const home = await postRaw('/goods/goodsSelectHome', '')
const rows = (() => {
  const j = home.json || {}
  const d = j.data || j
  const l = (d && (d.list || d.list_goods || d.goods)) || []
  return Array.isArray(l) ? l : []
})()
out.step4 = rows
  .filter((g) => String(g.MerchantId) === SHOP && String(g.GoodName) === GOOD)
  .map((g) => ({
    Id: g.Id, ProductCode: g.ProductCode, Price: g.Price, OriginalPrice: g.OriginalPrice,
    GoodImg: g.GoodImg, UserName: g.UserName, GoodType: g.GoodType, ShelfState: g.ShelfState
  }))
out.homeRowsTotal = rows.length

// ── 落盘 + 摘要 ─────────────────────────────────────────────────────────────────
mkdirSync(resolve(ROOT, '_work'), { recursive: true })
writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8')

console.log('① GoodsProductCodeSelect')
console.log(`   前端写法（不编码）：status=${p1raw.status} codes=${JSON.stringify(codesRaw)}`)
console.log(`   编码版            ：status=${p1enc.status} codes=${JSON.stringify(codesEnc)}`)
console.log(`   原始体：${brief(p1raw.json)}`)
console.log('② productCodePrice（逐码）')
out.step2.forEach((s) => console.log(`   ${s.code} ⇒ status=${s.status} ${brief(s.json)}`))
console.log(`③ skuList：status=${out.step3.status} rows=${out.step3.rows} ${out.step3.body}`)
console.log('④ goodsSelectHome 里这件商品的每一行：')
console.log(JSON.stringify(out.step4, null, 2))
console.log(`原始响应 → ${OUT}`)
