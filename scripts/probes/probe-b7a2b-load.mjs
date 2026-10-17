/**
 * 3a.2b · 抓帧期间的真实负载。**存在的理由**：后端空闲时 QPS≈0、cache.hit/miss=0、
 * locks[].waitMeanMs=0 ⇒ 三条新序列全会是贴地的直线，画进结构预览**等于加假象**。
 * 要么有真负载，要么不上图 —— 没有第三条路。
 *
 * ⚠️ **它会真落库**（`/order/orderAdd` 建订单）。所有自建订单的 `OrderId` 一律带
 *    `B7CAP-` 前缀，跑完把 id 清单写 `_work/3a2b/created-orders.txt`，
 *    收场按前缀清（报告里必须核实清了没有）。
 *
 * 流量形状（对着本次要补的序列设计）：
 *   · 4 个读 worker 打两个**走缓存**的接口（`goodstype` / `goodsSelectHome`，
 *     见 `goods/service/GoodsCacheService.java:97`）⇒ 制造 http.server.requests
 *     （QPS / 延迟）+ 缓存 L1 命中
 *   · 1 个 **miss worker** 打 `/goods/goodsSelect`（键 = `#MerchantId`，`:196`），
 *     每次换一个**全新** MerchantId ⇒ 每次都是真未命中
 *   · 1 个 **bad worker** 打**真 400**（见下）⇒ 制造 4xx
 *   · 每轮一次**并发订单突发**：同批 12 个不同 OrderId 同时 orderAdd（争同一个商品行），
 *     1.2s 后再对这 12 个 id 并发推进状态 ⇒ 制造锁竞争（acquires / waitMeanMs）
 *     + 订单流转 + 库存扣减
 *
 * ── 关于「真 400」的立场（2026-10-16 用户裁定，纠正了本脚本初版的措辞）──
 * 初版写的是「**不故意注入失败**」，把「不许失败流量」和「不许伪造数据」混成了一件事。
 * 正确的分界：**立场是「不伪造数据」，不是「不产生失败流量」。**
 * 用应用**自己的入参校验**触发 400 属于**真实使用场景** —— 请求是真的、拒绝是真的、
 * 响应是真的，`http.server.requests{status=400}` 会计到（`MetricsSnapshotService.java:239-245`
 * 按 status 首位取数）⇒ `errors4xx` / `clientErrorRate` 被真实点亮。
 * **仍然不做的**：伪造响应体、敲不存在的路径凑 404、编 5xx。
 * 四种入口（均已实测 400）：
 *   ① `/order/orderAdd` 缺 `Price`        ② `/order/orderAdd` `Price=abc`
 *   ③ `/goods/goodsSelect` 缺 `MerchantId` ④ `/order/orderPayIfUp` 缺 `orderId`
 * ⚠️ 400 在**绑定阶段**就被拒 ⇒ 不进 handler、不触库、不落任何行。
 *
 * ⚠️ **4xx 点不亮 health 的红态**：`health()` 的判据只有 `errorRate`（**5xx** 占比）、
 *    `p99Ms`、`l2Up`、`lockUp`、`cacheHitRate`、`stock.available`（`MetricsSnapshotService.java:452-502`）
 *    —— `clientErrorRate` **不参与**。想让 level 变 warn/error 需要真 5xx 或 p99≥1000ms，
 *    本轮两条都不可达，别把「打了 4xx health 没翻」当成探针故障。
 */
import fs from 'node:fs'

const BASE = process.env.B7_BASE || 'http://localhost:8088'
const TAG = 'B7CAP'
const RUN = Date.now().toString(36).slice(-5)
const CID = `${TAG}C-${RUN}`
const MID = `${TAG}M-${RUN}`
const DURATION_MS = Number(process.env.B7_DURATION_MS || 70000)
const CREATED = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a2b/created-orders.txt'

const created = []
const stats = { total: 0, err: 0, byCode: {}, byName: {} }
let stop = false

/** 函数声明会提升 ⇒ 两个新 worker（写在文件后半）可以引用它们。 */
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)) }

function rec(name, code) {
  stats.total++
  if (code === 'ERR') stats.err++
  stats.byCode[code] = (stats.byCode[code] || 0) + 1
  const k = name + ':' + code
  stats.byName[k] = (stats.byName[k] || 0) + 1
}

async function hit(name, path, init) {
  try {
    const r = await fetch(BASE + path, { ...init, signal: AbortSignal.timeout(8000) })
    await r.arrayBuffer()
    rec(name, r.status)
    return r.status
  } catch (e) {
    rec(name, 'ERR')
    return 'ERR'
  }
}

const form = (o) => new URLSearchParams(o).toString()
const post = (body) => ({ method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body })

async function readWorker(id) {
  const eps = [
    () => hit('goodsSelectHome', '/goods/goodsSelectHome', post('')),
    () => hit('goodstype', '/goods/goodstype'),
    () => hit('productSearch', '/goods/productSearch', post(form({ keyword: 'oppo' }))),
  ]
  let i = id
  while (!stop) {
    await eps[i % eps.length]()
    i++
    await new Promise((r) => setTimeout(r, 90 + Math.random() * 60))
  }
}

/**
 * miss worker：`/goods/goodsSelect` 的缓存键是 `#MerchantId`（`GoodsCacheService.java:196`），
 * 每次换**全新** id ⇒ 每一次都是真未命中。
 * 为什么必须「全新」而不是「轮换固定池」：固定的 P 个 id 只在**第一圈** miss，
 * 第二圈起全 hit（Caffeine TTL 远长于本窗口）⇒ 未命中全挤在前几拍，正是要避免的形状。
 * ⚠️ **只读**：`goodsSelect` 不改任何数据（`GoodsCacheService.listByMerchant` 只有一条 SELECT）。
 *
 * 【形状】**成簇 + 长短交替**，不是等间隔。第一版用固定 300ms ⇒ `cache.miss` 每拍恒 3–4，
 * 是**完美栅栏**。真页面上未命中该是忽高忽低的 ⇒ 这里一轮打 4–12 发（60–140ms 一发），
 * 再静默 0.9–3.1s。总人次仍在 ~230/74s 量级（每发自成一个新键 ⇒ 全是真 miss）。
 */
async function missWorker() {
  let n = 0
  while (!stop) {
    const k = 4 + Math.floor(Math.random() * 9)      // 4..12 发
    for (let i = 0; i < k && !stop; i++) {
      await hit('goodsSelect', '/goods/goodsSelect', post(form({ MerchantId: `${TAG}S-${RUN}-${n++}` })))
      await sleep(60 + Math.random() * 80)
    }
    await sleep(900 + Math.random() * 2200)
  }
}

/**
 * bad worker：打**真 400** —— 用应用自己的入参校验，不伪造任何东西（立场见文件头）。
 * 四种入口轮转，每种都实测过 400。
 *
 * 【形状】**成簇 + 长短交替**。第一版用固定 1200ms ⇒ `errors4xx` 每拍恒 1、周期正好 6，
 * 也是完美栅栏 —— 而真页面上 4xx 是成簇来的（一次前端发版 / 一轮爬虫）。
 * ⇒ 一轮打 1–4 发（40–90ms 一发），再静默 1.0–3.5s；约 20% 的轮次额外多静默一段，
 * 让「有些拍一个错都没有」也真实出现。总量仍 ~2%。
 * ⚠️ 目的只是让 `errors4xx` 有值 —— 不是要把页面打成红的（打了也不会红，理由见文件头）。
 */
const BAD = [
  () => hit('bad:orderAdd-noPrice', '/order/orderAdd', post(form({
    CustomerId: CID, MerchantId: MID, OrderId: `${TAG}B-np`,
    ProductInformation: TAG, Images: TAG, PayIf: 0, DeliveryIf: 0, ReceiveIf: 0,
  }))),
  () => hit('bad:orderAdd-badPrice', '/order/orderAdd', post(form({
    CustomerId: CID, MerchantId: MID, OrderId: `${TAG}B-bp`, Price: 'abc',
    ProductInformation: TAG, Images: TAG, PayIf: 0, DeliveryIf: 0, ReceiveIf: 0,
  }))),
  () => hit('bad:goodsSelect-noMerchant', '/goods/goodsSelect', post('')),
  () => hit('bad:orderPayIfUp-noId', '/order/orderPayIfUp', post('')),
]
async function badWorker() {
  let i = 0
  while (!stop) {
    const k = 1 + Math.floor(Math.random() * 4)      // 1..4 发
    for (let j = 0; j < k && !stop; j++) {
      await BAD[i++ % BAD.length]()
      await sleep(40 + Math.random() * 50)
    }
    await sleep(1000 + Math.random() * 2500)
    if (Math.random() < 0.2) await sleep(1500 + Math.random() * 2500)
  }
}

const orderForm = (orderId) => form({
  CustomerId: CID, MerchantId: MID, OrderId: orderId, Price: '1.5',
  ProductInformation: `${TAG}-PI`, Images: `${TAG}-IMG`,
  PayIf: 0, DeliveryIf: 0, ReceiveIf: 0,
})

async function burst(round) {
  const ids = []
  for (let i = 0; i < 12; i++) ids.push(`${TAG}-${RUN}-r${round}-${i}`)
  await Promise.all(ids.map((id) => hit('orderAdd', '/order/orderAdd', post(orderForm(id)))))
  created.push(...ids)
  await new Promise((r) => setTimeout(r, 1200))
  await Promise.all(ids.map((id) => hit('orderPayIfUp', '/order/orderPayIfUp', post(form({ orderId: id })))))
}

const t0 = Date.now()
const workers = [0, 1, 2, 3].map(readWorker)
workers.push(missWorker(), badWorker())
let round = 0
while (Date.now() - t0 < DURATION_MS) {
  await burst(round++)
  await new Promise((r) => setTimeout(r, 6000))
}
stop = true
await Promise.all(workers)

fs.writeFileSync(CREATED, created.join('\n') + '\n')
console.log(JSON.stringify({
  seconds: Math.round((Date.now() - t0) / 1000),
  createdOrders: created.length,
  customerId: CID, merchantId: MID,
  total: stats.total, err: stats.err,
  byCode: stats.byCode, byName: stats.byName,
  createdList: CREATED,
}, null, 1))
