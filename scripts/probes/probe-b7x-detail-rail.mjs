// probe-b7x-detail-rail.mjs —— 块 7 P0（2026-10-17 用户三条硬口径）的**真后端**实测。
//
// 覆盖：
//   ① 全局流转明细**服务端分页**：点「待发货」⇒ 表头 / 分页脚的总数 = 库内该态真实 COUNT，
//      表里每一行都是该状态；翻页后仍是该状态（status 真的进了 SQL 的 WHERE，而不是前端拿
//      最新 1000 条自己过滤）。
//   ② 流轨**静态拓扑 ≠ 动态流转**：没有真实流转时轨道是暗的、无动画、无雷达。
//   ③ 超时取消的**汇总事件**：造 40 张未付款单（其中 1 张付掉用于验订单号完整显示），
//      等 60 秒阈值 + 15 秒扫描周期 ⇒ SSE 推一条 count>0 的 move、取消支线亮起、大号粒子下落、
//      取消流入 QPS 真实跳动。
//
// 跑法：① 后端 dev profile 在 8088（`app.auth.enabled=false`）② `npm run serve`（8080）
//       ③ `node scripts/probes/probe-b7x-detail-rail.mjs`
// ⚠️ **不拦截 Fetch**：本探针的价值就是打真后端 + 真库。
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const API = 'http://localhost:8088'
const ROUTE = '#/admin/observe/timeline'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const SHOT_IDLE = `${EV}b7x-idle-rail.png`
const SHOT_TABLE = `${EV}b7x-waitship-page.png`
const SHOT_LADDER = `${EV}b7x-cancel-ladder.png`
const SHOT_LANE = `${EV}b7x-cancel-lane.png`

const R = report('块 7 · P0 服务端分页 / 静态流轨 / 超时取消汇总（真后端 8088）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const numOf = (s) => Number(String(s || '').replace(/[^\d.-]/g, '')) || 0

async function post(path, params) {
  const body = new URLSearchParams(params)
  const res = await fetch(API + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  return res.json()
}

const b = await open({ w: 1440, h: 1000 })

await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
      // 清掉上一场快照：本次要看的「空轨」不能被历史行/历史尾点糊住。
      Object.keys(sessionStorage).filter((k) => k.includes('orderflow') || k.includes('a-of')).forEach((k) => sessionStorage.removeItem(k))
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
  await sleep(6000)

  const phase = await b.evalJS(`(document.querySelector('.a-chip-live') || {}).innerText || ''`)
  const seq = await b.evalJS(`(() => { const m = document.body.innerText.match(/第\\s*(\\d+)\\s*拍/); return m ? Number(m[1]) : 0 })()`)
  ok('① 真流活着且逐拍推进', String(phase).trim() === '已连接' && seq >= 2, `${phase} / seq=${seq}`)

  // ══════════════════════════════════════════════════════════════════════
  // ① 无真实流转时：轨道必须是「待命的拓扑图」，不是一直在跑
  // ══════════════════════════════════════════════════════════════════════
  const idle = await b.evalJS(`(() => {
    const tracks = [...document.querySelectorAll('.a-of-lane-track')]
    const dots = [...document.querySelectorAll('.a-of-lane-dot')]
    const cancelPaths = [...document.querySelectorAll('.a-of-cancel-links path')]
    return {
      tracks: tracks.length,
      onTracks: tracks.filter((e) => e.classList.contains('is-on')).length,
      trackAnim: tracks.map((e) => getComputedStyle(e).animationName),
      trackOpacity: tracks.map((e) => Number(getComputedStyle(e).opacity)),
      dotAnim: dots.map((e) => getComputedStyle(e, '::after').animationName),
      liveNodes: document.querySelectorAll('.a-of-lane-node.is-live').length,
      cancelOn: cancelPaths.filter((e) => e.classList.contains('on')).length,
      cancelAnim: cancelPaths.map((e) => getComputedStyle(e).animationName),
      particles: document.querySelectorAll('.a-of-cancel-particle').length,
    }
  })()`)
  // 主链 4 个节点 = 3 段拓扑线（不是 5；5 是五态计数，两回事）。
  ok('② 静态轨道在：主链 3 段拓扑线都在', idle.tracks === 3, idle)
  ok('② 没有任何一段被点亮（无真实流转 ⇒ 不亮）', idle.onTracks === 0, idle.onTracks)
  ok('② 轨道**没有动画**（暗灰待命，不是一直在跑）',
    idle.trackAnim.every((x) => x === 'none') && idle.trackOpacity.every((x) => x < 0.6),
    { anim: idle.trackAnim, opacity: idle.trackOpacity })
  ok('② 节点雷达不转、取消支线不亮、没有粒子',
    idle.dotAnim.every((x) => x === 'none') && idle.liveNodes === 0 && idle.cancelOn === 0 && idle.particles === 0,
    { dotAnim: idle.dotAnim, liveNodes: idle.liveNodes, cancelOn: idle.cancelOn, particles: idle.particles })
  await b.evalJS(`(document.querySelector('.a-of-lane') || document.body).scrollIntoView({ block: 'center' })`)
  await sleep(400)
  await b.shot(SHOT_IDLE)

  // ══════════════════════════════════════════════════════════════════════
  // ② 全局明细：点「待发货」⇒ 服务端 COUNT + WHERE
  // ══════════════════════════════════════════════════════════════════════
  const kpiCounts = await b.evalJS(`[...document.querySelectorAll('.a-of-kpi')].map((e) => e.innerText.replace(/\\s+/g, ' ').trim())`)
  const waitShipKpi = numOf((kpiCounts.find((t) => t.includes('待发货')) || '').match(/待发货\s*([\d,]+)/)?.[1])
  ok('① 待发货 KPI 有真实大数（不是 0，也不是被截断的 1000）', waitShipKpi > 1000, waitShipKpi)

  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-of-kpi')].find((e) => e.innerText.includes('待发货'))
    if (btn) btn.click()
  })()`)
  await sleep(2500)

  const table = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.a-of-table tbody tr')]
    const dataRows = rows.filter((r) => !r.querySelector('.a-empty'))
    // ⚠️ .a-h2n 在页面里不止一处（直方图说明也用它）⇒ 必须挂到「全局流转明细」这个 h2 上找。
    const h2 = [...document.querySelectorAll('.a-h2')].find((e) => e.innerText.includes('全局流转明细'))
    return {
      head: h2 ? (h2.querySelector('.a-h2n') || {}).innerText || '' : '',
      chipOn: (document.querySelector('.a-of-chip.on') || {}).innerText || '',
      rows: dataRows.length,
      states: dataRows.map((r) => (r.querySelector('.a-of-st') || {}).innerText || ''),
      page: (document.querySelector('.pager-sum b') || {}).innerText || '',
      pagerText: (document.querySelector('.pager') || {}).innerText || '',
      firstId: (dataRows[0]?.querySelector('.a-of-id') || {}).innerText || '',
      idClipped: dataRows.map((r) => {
        const el = r.querySelector('.a-of-id')
        return el ? el.scrollWidth > el.clientWidth + 1 : false
      }),
    }
  })()`)
  ok('① 点「待发货」后表头总数 = 该态真实 COUNT（≥ 4 位数）', numOf(table.head.split('·')[1]) > 1000, table.head)
  ok('① 分页脚总数 = 同一个数（不是本页 20 条）', numOf(table.page) === numOf(table.head.split('·')[1]), `${table.page} vs ${table.head}`)
  ok('① 表格每行都是「待发货」（status 进了 SQL 的 WHERE）',
    table.rows === 20 && table.states.every((s) => s.includes('待发货')), { rows: table.rows, states: table.states.slice(0, 3) })
  ok('① 表内订单号未被截断（空间足够时完整显示）', table.idClipped.every((x) => x === false), table.idClipped)

  // 翻到第 2 页：行必须换一批、且仍然全是待发货
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.pager-btns .pg')].find((e) => e.innerText.trim() === '2')
    if (btn) btn.click()
  })()`)
  await sleep(1800)
  const page2 = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.a-of-table tbody tr')].filter((r) => !r.querySelector('.a-empty'))
    return {
      rows: rows.length,
      states: rows.map((r) => (r.querySelector('.a-of-st') || {}).innerText || ''),
      firstId: (rows[0]?.querySelector('.a-of-id') || {}).innerText || '',
      activePage: (document.querySelector('.pager-btns .pg.on') || {}).innerText || '',
    }
  })()`)
  ok('① 第 2 页换了行、仍是待发货（服务端 offset 真的生效）',
    page2.rows === 20 && page2.states.every((s) => s.includes('待发货'))
      && page2.firstId !== table.firstId && page2.activePage === '2',
    { rows: page2.rows, active: page2.activePage, first: page2.firstId })

  await b.evalJS(`(document.querySelector('.a-of-table-card') || document.body).scrollIntoView({ block: 'center' })`)
  await sleep(400)
  await b.shot(SHOT_TABLE)

  // ══════════════════════════════════════════════════════════════════════
  // ③ 超时取消：造 40 张未付款单（1 张付掉，用于验订单号完整显示）
  // ══════════════════════════════════════════════════════════════════════
  const cancelledKpiBefore = await b.evalJS(`(() => {
    const t = [...document.querySelectorAll('.a-of-kpi')].map((e) => e.innerText.replace(/\\s+/g, ' '))
    return t.find((x) => x.includes('已取消')) || ''
  })()`)
  const cancelledBefore = numOf(cancelledKpiBefore.match(/已取消\s*([\d,]+)/)?.[1])

  const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
  const ids = []
  const failures = []
  for (let i = 0; i < 40; i++) {
    const id = `P0CANCEL-${stamp}-${String(i).padStart(2, '0')}`
    const json = await post('/order/orderAdd', {
      CustomerId: 'qa7PmpUojO',
      MerchantId: 'dRD3gqtbFr',
      OrderId: id,
      Price: '1',
      ProductInformation: 'P0超时取消验证',
      Images: '',
      PayIf: '0',
      DeliveryIf: '0',
      ReceiveIf: '0',
      Quantity: '1',
    })
    if (json.success === true) ids.push(id)
    else failures.push({ id, msg: json.msg })
  }
  ok('③ 40 张未付款单全部落库（orderAdd success=true）', ids.length === 40, { created: ids.length, failures: failures.slice(0, 3) })

  // 付掉第一张：产生一条**普通** move（waitPay → waitShip），用于验流轨订单号完整显示。
  const pay = ids.length ? await post('/order/orderPayIfUp', { orderId: ids[0] }) : { success: false }
  ok('③ 首张已付款（普通 move 的原料）', pay.success === true, pay)

  // ⚠️ 轨道高亮只亮 `FLASH_MS=1200ms` ⇒ 必须在付款后**立即**按 100ms 轮询抓峰值，
  //    2.5 秒后再看一定已经熄了（第一轮探针就是这样误报的）。
  await b.evalJS(`(document.querySelector('.a-of-stream') || document.body).scrollIntoView({ block: 'center' })`)
  let normalRow = null
  let maxTrackOn = 0
  for (let i = 0; i < 40; i++) {
    const snap = await b.evalJS(`(() => {
      const rows = [...document.querySelectorAll('.a-of-stream-i')]
      const target = rows.find((r) => (r.innerText || '').includes('P0CANCEL-'))
      const idEl = target ? target.querySelector('.a-of-id') : null
      return {
        text: target ? target.innerText.replace(/\\s+/g, ' ').trim() : '',
        id: idEl ? idEl.innerText : '',
        clipped: idEl ? idEl.scrollWidth > idEl.clientWidth + 1 : true,
        trackOn: document.querySelectorAll('.a-of-lane-track.is-on').length,
      }
    })()`)
    if (snap.text && !normalRow) normalRow = snap
    if (snap.trackOn > maxTrackOn) maxTrackOn = snap.trackOn
    if (normalRow && maxTrackOn > 0) break
    await sleep(100)
  }
  ok('③ 普通流转进了流轨（waitPay → waitShip，轨道亮起）',
    !!normalRow && maxTrackOn >= 1, { row: normalRow && normalRow.text, maxTrackOn })
  ok('③ 流轨里的订单号完整显示（不再被固定宽度截成 #SIM-BU…）',
    !!normalRow && normalRow.clipped === false, normalRow && { id: normalRow.id, clipped: normalRow.clipped })

  // 等超时取消（阈值 60s + 扫描 15s ⇒ 留 110s 余量）。粒子的生命周期 ~2.4s，按 150ms 轮询抓峰值。
  let peak = null
  const deadline = Date.now() + 110000
  while (Date.now() < deadline) {
    const snap = await b.evalJS(`(() => {
      const rows = [...document.querySelectorAll('.a-of-stream-i')].map((e) => e.innerText.replace(/\\s+/g, ' ').trim())
      return {
        particles: document.querySelectorAll('.a-of-cancel-particle').length,
        big: document.querySelectorAll('.a-of-cancel-particle.is-big').length,
        cancelOn: document.querySelectorAll('.a-of-cancel-links path.on').length,
        cancelAnim: [...document.querySelectorAll('.a-of-cancel-links path.on')].map((e) => getComputedStyle(e).animationName),
        cancelQps: (document.querySelector('.a-of-step.is-cancel .a-of-step-meta') || {}).innerText || '',
        waitPayOut: (document.querySelector('.a-of-step') || {}).innerText || '',
        streamRows: rows,
        batchRow: rows.find((t) => t.includes('批量取消')) || '',
      }
    })()`)
    if (!peak || snap.particles > peak.particles) peak = snap
    if (snap.particles >= 3 && snap.big >= 3) break
    await sleep(150)
  }

  ok('③ 取消支线真的亮起（waitPay>cancelled 下落路径）', !!peak && peak.cancelOn >= 1, peak && { cancelOn: peak.cancelOn, anim: peak.cancelAnim })
  ok('③ 大号瀑布粒子出现（汇总事件 ⇒ 不是一颗小光点）', !!peak && peak.big >= 3, peak && { particles: peak.particles, big: peak.big })
  ok('③ 取消流入 QPS 真实跳动（不是 0/s）',
    !!peak && /取消流入\s*[\d.]+/.test(peak.cancelQps) && Number((peak.cancelQps.match(/取消流入\s*([\d.]+)/) || [])[1]) > 0,
    peak && peak.cancelQps)
  ok('③ 流轨出现「批量取消 N 单」汇总行（orderId 为 null 的汇总事件在页面上有具名落点）',
    !!peak && /批量取消\s*\d+\s*单/.test(peak.batchRow), peak && peak.batchRow)

  // 峰值截图：阶梯（已取消数字 + 取消流入 QPS）与流轨（亮线 + 瀑布 + 汇总行）各一张。
  if (peak && peak.big > 0) {
    await b.evalJS(`(document.querySelector('.a-of-flow') || document.body).scrollIntoView({ block: 'center' })`)
    await sleep(120)
    await b.shot(SHOT_LADDER)
    await b.evalJS(`(document.querySelector('.a-of-stream') || document.body).scrollIntoView({ block: 'center' })`)
    await sleep(120)
    await b.shot(SHOT_LANE)
  }

  // 等一拍让计数落位，再取「已取消」的涨量。
  await sleep(2500)
  const after = await b.evalJS(`(() => {
    const t = [...document.querySelectorAll('.a-of-kpi')].map((e) => e.innerText.replace(/\\s+/g, ' '))
    return {
      cancelled: t.find((x) => x.includes('已取消')) || '',
      waitPay: t.find((x) => x.includes('待付款')) || '',
      counts: [...document.querySelectorAll('.a-of-step-n')].map((e) => e.innerText.trim()),
    }
  })()`)
  const cancelledAfter = numOf(after.cancelled.match(/已取消\s*([\d,]+)/)?.[1])
  ok('③ 左侧「已取消」数字实时 +39（付款那张没被取消）',
    cancelledAfter - cancelledBefore === 39, { before: cancelledBefore, after: cancelledAfter })
  ok('③ 待付款归零（40 张里 1 张付款、39 张取消）',
    numOf(after.waitPay.match(/待付款\s*([\d,]+)/)?.[1]) === 0, after.waitPay)

  const errs = await b.evalJS('(window.__errs || [])')
  ok('④ 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))

  const bad = R.dump()
  console.log('createdIds=' + JSON.stringify(ids))
  process.exitCode = bad ? 1 : 0
} finally {
  await b.close()
}
