// probe-b7r-cancel-gif.mjs —— 用户验收第 3 条的**真后端**动图取证：
//   ① 流轨光点从左向右（回放动图 b7r-timeline-rail.gif 已单独证）；
//   ② 超时取消时大号瀑布粒子明显流向「已取消」；
//   ③ 取消流入 QPS 实时跳动、「批量取消 N 单」汇总行出现。
//
// 做法：造 40 张未付款单 → 付掉 1 张 → 轮询到取消峰值（60s 阈值 + 15s 扫描）
//       → 在粒子存活期（~2.4s）连拍 16 帧 → 离线拼 GIF。
//
// 跑法：后端 dev(8088) + `npm run serve`(8080) 都在，`node scripts/probes/probe-b7r-cancel-gif.mjs`
// ⚠️ 不拦截 Fetch：本探针价值就是真库 + 真 SSE。
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const API = 'http://localhost:8088'
const ROUTE = '#/admin/observe/timeline'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const FRAMES = `${EV}b7r-cancel-frames/`

const R = report('块 7 · 超时取消流轨动图取证（真后端 8088）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }
const numOf = (s) => Number(String(s || '').replace(/[^\d.-]/g, '')) || 0

async function post(path, params) {
  const res = await fetch(API + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  })
  return res.json()
}

fs.mkdirSync(FRAMES, { recursive: true })
for (const n of fs.readdirSync(FRAMES)) if (n.endsWith('.png')) fs.unlinkSync(`${FRAMES}${n}`)

const b = await open({ w: 1440, h: 1000 })

await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {}
    window.__errs = []
    const oe = console.error.bind(console)
    console.error = (...a) => { try { window.__errs.push(a.map(String).join(' ')) } catch (e) {} ; oe(...a) }
    window.addEventListener('unhandledrejection', (e) => { try { window.__errs.push('unhandledrejection: ' + e.reason) } catch (x) {} })
  })()`,
})

try {
  await b.send('Page.navigate', { url: `${BASE}/${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.a-of')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 页面挂载', mounted === true)
  await sleep(5000)
  const live = await b.evalJS(`(document.querySelector('.a-chip-live') || {}).innerText || ''`)
  ok('① 真流已连接', String(live).trim() === '已连接', live)

  // 把流轨 + 阶梯滚到同一屏：这屏同时能拍到「已取消数字 / 取消流入 QPS / 瀑布粒子 / 汇总行」。
  await b.evalJS(`(document.querySelector('.a-of-flow') || document.body).scrollIntoView({ block: 'center' })`)
  await sleep(600)
  await b.shot(`${EV}b7r-cancel-before.png`)

  const cancelledBefore = await b.evalJS(`(() => {
    const t = [...document.querySelectorAll('.a-of-kpi')].map((e) => e.innerText.replace(/\\s+/g, ' '))
    return t.find((x) => x.includes('已取消')) || ''
  })()`)
  const beforeNum = numOf(cancelledBefore.match(/已取消\s*([\d,]+)/)?.[1])

  // ── 造 40 张未付款单；付掉 1 张（它的普通 move 也在同一张动图里） ──
  const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
  const ids = []
  for (let i = 0; i < 40; i++) {
    const id = `P0CANCEL-${stamp}-${String(i).padStart(2, '0')}`
    const json = await post('/order/orderAdd', {
      CustomerId: 'qa7PmpUojO',
      MerchantId: 'dRD3gqtbFr',
      OrderId: id,
      Price: '1',
      ProductInformation: 'P0超时取消动图',
      Images: '',
      PayIf: '0',
      DeliveryIf: '0',
      ReceiveIf: '0',
      Quantity: '1',
    })
    if (json.success === true) ids.push(id)
  }
  ok('② 40 张未付款单落库', ids.length === 40, ids.length)
  const pay = ids.length ? await post('/order/orderPayIfUp', { orderId: ids[0] }) : { success: false }
  ok('② 首张付款成功（普通 move 原料）', pay.success === true, pay)

  // ── 轮询取消峰值（粒子生命周期 ~2.4s ⇒ 100ms 轮询）──
  let peak = null
  const deadline = Date.now() + 125000
  while (Date.now() < deadline) {
    const snap = await b.evalJS(`(() => {
      const rows = [...document.querySelectorAll('.a-of-stream-i')].map((e) => e.innerText.replace(/\\s+/g, ' ').trim())
      return {
        particles: document.querySelectorAll('.a-of-cancel-particle').length,
        big: document.querySelectorAll('.a-of-cancel-particle.is-big').length,
        cancelOn: document.querySelectorAll('.a-of-cancel-links path.on').length,
        cancelQps: (document.querySelector('.a-of-step.is-cancel .a-of-step-meta') || {}).innerText || '',
        cancelledKpi: ([...document.querySelectorAll('.a-of-kpi')].map((e) => e.innerText.replace(/\\s+/g, ' ')).find((x) => x.includes('已取消')) || ''),
        batchRow: rows.find((t) => t.includes('批量取消')) || '',
        clock: ((document.querySelector('.a-chip-live') || {}).innerText || ''),
      }
    })()`)
    if (!peak || snap.big > peak.big || (snap.particles > peak.particles && snap.big >= peak.big)) peak = snap
    if (snap.big >= 3 && snap.cancelOn >= 1) break
    await sleep(100)
  }

  ok('③ 取消支线亮起（waitPay → 已取消）', !!peak && peak.cancelOn >= 1, peak && { cancelOn: peak.cancelOn })
  ok('③ 大号瀑布粒子出现（≥3 颗）', !!peak && peak.big >= 3, peak && { particles: peak.particles, big: peak.big })
  ok('③ 取消流入 QPS 实时跳动（> 0/s）',
    !!peak && /取消流入\s*([\d.]+)/.test(peak.cancelQps || '') && numOf((peak.cancelQps.match(/取消流入\s*([\d.]+)/) || [])[1]) > 0,
    peak && peak.cancelQps)
  ok('③ 流轨出现「批量取消 N 单」汇总行', !!peak && /批量取消\s*\d+\s*单/.test(peak.batchRow || ''), peak && peak.batchRow)

  // ── 峰值连拍 16 帧（每帧约 120ms；截图本身也有耗时，覆盖 ≥2s）──
  let shots = 0
  if (peak && peak.big >= 1) {
    for (let i = 0; i < 16; i++) {
      await b.shot(`${FRAMES}f${String(i).padStart(2, '0')}.png`)
      shots++
      await sleep(120)
    }
  }
  ok('③ 峰值连拍帧数 ≥ 12（动图原料足够）', shots >= 12, shots)

  // 等一拍计数落位，再取「已取消」涨量。
  await sleep(3000)
  const after = await b.evalJS(`(() => {
    const t = [...document.querySelectorAll('.a-of-kpi')].map((e) => e.innerText.replace(/\\s+/g, ' '))
    return {
      cancelled: t.find((x) => x.includes('已取消')) || '',
      waitPay: t.find((x) => x.includes('待付款')) || '',
      cancelQps: (document.querySelector('.a-of-step.is-cancel .a-of-step-meta') || {}).innerText || '',
    }
  })()`)
  const afterNum = numOf(after.cancelled.match(/已取消\s*([\d,]+)/)?.[1])
  ok('③ 左侧「已取消」实时 +39（付款那张未被取消）', afterNum - beforeNum === 39, { before: beforeNum, after: afterNum })
  ok('③ 待付款归零（40 张里 1 张付款、39 张取消）',
    numOf(after.waitPay.match(/待付款\s*([\d,]+)/)?.[1]) === 0, after.waitPay)
  // 取消流入是 **1 秒窗口速率**（见 QPS 计算口径），批量取消一波过后必然回落到 0/s；
  // 峰值那次已由上面的断言证明「实时跳动」，这里验的是它会随窗口滑出而回落（不是累计数）。
  ok('③ 取消流入 QPS 是瞬时窗口速率：事件过后回落到 0/s（不是累计值）',
    numOf((String(after.cancelQps).match(/取消流入\s*([\d.]+)/) || [])[1]) === 0,
    { peak: peak && peak.cancelQps, after: after.cancelQps })

  await b.shot(`${EV}b7r-cancel-after.png`)

  const errs = await b.evalJS('(window.__errs || [])')
  ok('④ 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))

  const bad = R.dump()
  console.log('createdIds=' + JSON.stringify(ids))
  process.exitCode = bad ? 1 : 0
} finally {
  await b.close()
}
