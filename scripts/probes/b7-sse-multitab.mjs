// b7-sse-multitab.mjs —— 多标签页验收（同源连接池是**整个浏览器**共用的）。
// 要求（用户口径）：
//   ① 开 3 个以上不同页面标签，数据都在正常更新（帧计数在涨）；
//   ② 不再出现 10 秒超时（每次激活都打一条普通 POST 计时）；
//   ③ 任何时刻整个浏览器的同源 SSE 实连数不超过 4（本仓可见页最多 3 条）；
//   ④ 攻击页常驻 3 条预览流，停留期间持续滚动。
//
// 实现要点：
//   - 同一个 Edge 进程 / 同一个 profile（连接池才真正共享）；用 /json/new 开新标签，
//     再对每个标签各开一条 CDP WebSocket。
//   - 「实连数」用两条独立口径交叉验证：
//       a) 页面侧 window.__sse_connections（openStream 里维护的计数）；
//       b) CDP Network 域里「已发起、未 loadingFinished」的 /stream 请求数。
//   - 隐藏标签让流：observe.js 的 openResilientStream 监听 visibilitychange，
//     隐藏即 close；重新可见立即重连。本探针用 Page.bringToFront 切前台。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import { createRequire } from 'node:module'
import http from 'node:http'
import path from 'node:path'

const MALL = 'C:/Users/86159/Desktop/软件工程/期末项目/Mall-management-web/mall-management-system'
const WebSocket = createRequire(path.join(MALL, 'package.json'))('ws')
const WEB = 'http://localhost:8080'

function jsonNew(port, url) {
  return new Promise((res, rej) => {
    const req = http.request({ host: '127.0.0.1', port, path: '/json/new?' + url, method: 'PUT' }, (r) => {
      let d = ''
      r.on('data', (c) => (d += c))
      r.on('end', () => { try { res(JSON.parse(d)) } catch (e) { rej(new Error(d)) } })
    })
    req.on('error', rej)
    req.end()
  })
}

/** 对一个标签页开 CDP 连接，并统计 /stream 的在途请求数。 */
async function attach(wsUrl) {
  const ws = new WebSocket(wsUrl, { perMessageDeflate: false, maxPayload: 256 * 1024 * 1024 })
  await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })
  let id = 0
  const pend = new Map()
  const inflight = new Set()
  let streamPeak = 0
  ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.id && pend.has(m.id)) {
      const { res, rej } = pend.get(m.id); pend.delete(m.id)
      m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result)
      return
    }
    const p = m.params
    // ⚠️ 订单流带查询串（`?healthy=false`），只匹配结尾会把 3 条数成 2 条、状态流转数成 0 条。
    if (m.method === 'Network.requestWillBeSent' && /\/stream(\?|$)/.test(p.request.url)) {
      inflight.add(p.requestId)
      streamPeak = Math.max(streamPeak, inflight.size)
    } else if ((m.method === 'Network.loadingFinished' || m.method === 'Network.loadingFailed') && inflight.has(p.requestId)) {
      inflight.delete(p.requestId)
    }
  })
  const send = (method, params = {}) => new Promise((res, rej) => {
    const i = ++id; pend.set(i, { res, rej })
    ws.send(JSON.stringify({ id: i, method, params }))
  })
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  const t = {
    ws,
    send,
    inflight,
    get streamInFlight() { return inflight.size },
    get peak() { return streamPeak },
    resetPeak() { streamPeak = inflight.size },
    async evalJS(expr) {
      const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
      if (r.exceptionDetails) throw new Error('eval 失败：' + JSON.stringify(r.exceptionDetails.exception))
      return r.result.value
    },
    async goto(url, waitMs = 2000) {
      await send('Page.navigate', { url })
      await sleep(waitMs)
    },
    close() { try { ws.close() } catch (e) {} },
  }
  return t
}

const snapExpr = `(() => ({
  vis: document.visibilityState,
  logical: (window.__sse_instances || []).length,
  paths: (window.__sse_instances || []).map(e => e.path),
  real: window.__sse_connections || 0,
  frames: Object.assign({}, window.__sse_frames || {}),
  text: (document.body.innerText || '').slice(0, 80)
}))()`

const postExpr = `(async () => {
  const t = performance.now()
  const ctl = new AbortController()
  const to = setTimeout(() => ctl.abort(), 11000)
  try {
    const r = await fetch('http://localhost:8088/admin/observe/attack/scenarios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: '',
      signal: ctl.signal,
      credentials: 'include'
    })
    await r.text()
    return { status: r.status, ms: Math.round(performance.now() - t) }
  } catch (e) {
    return { error: String(e), ms: Math.round(performance.now() - t) }
  } finally { clearTimeout(to) }
})()`

const PAGES = [
  { key: 'metrics', hash: '#/admin/observe/metrics' },
  { key: 'logs', hash: '#/admin/observe/logs' },
  { key: 'timeline', hash: '#/admin/observe/timeline' },
  { key: 'attack', hash: '#/admin/observe/attack-sim' },
]

const out = { rounds: [], maxRealAggregate: 0, maxPostMs: 0, fails: [] }
let b = null
const tabs = []
try {
  b = await open({ w: 1500, h: 1000, headed: false })
  const list = JSON.parse(await new Promise((res, rej) => {
    http.get(`http://127.0.0.1:${b.port}/json/list`, (r) => { let d = ''; r.on('data', (c) => (d += c)); r.on('end', () => res(d)) }).on('error', rej)
  }))
  const first = list.find((t) => t.type === 'page')
  tabs.push({ key: 'metrics', t: await attach(first.webSocketDebuggerUrl) })
  for (const key of ['logs', 'timeline', 'attack']) {
    const info = await jsonNew(b.port, 'about:blank')
    tabs.push({ key, t: await attach(info.webSocketDebuggerUrl) })
  }

  // 每个标签先登录，再进各自页面。
  for (const tab of tabs) {
    await tab.t.goto(WEB + '/admin.html#/login', 2600)
    await tab.t.evalJS(`(() => { sessionStorage.setItem('loginUsername','admin'); sessionStorage.setItem('role','3'); return true })()`)
    const page = PAGES.find((p) => p.key === tab.key)
    await tab.t.evalJS(`location.hash = ${JSON.stringify(page.hash)}`)
    await sleep(1200)
  }
  await sleep(5000)

  const readAll = async () => {
    const rows = {}
    for (const tab of tabs) rows[tab.key] = await tab.t.evalJS(snapExpr)
    return rows
  }

  for (let round = 1; round <= 2; round += 1) {
    const perTab = []
    for (const tab of tabs) {
      await tab.t.send('Page.bringToFront')
      await sleep(900) // 让上一个标签收到 hidden、本标签收到 visible
      await sleep(4200) // 建流 + 首帧
      const s1 = await tab.t.evalJS(snapExpr)
      await sleep(2300)
      const s2 = await tab.t.evalJS(snapExpr)
      const moved = JSON.stringify(s1.frames) !== JSON.stringify(s2.frames)
      const post = await tab.t.evalJS(postExpr)
      const others = tabs.filter((x) => x !== tab)
      const hiddenRows = {}
      for (const o of others) hiddenRows[o.key] = await o.t.evalJS(snapExpr)
      const aggReal = s2.real + Object.values(hiddenRows).reduce((n, r) => n + r.real, 0)
      const aggInflight = tab.t.streamInFlight + others.reduce((n, o) => n + o.t.streamInFlight, 0)
      out.maxRealAggregate = Math.max(out.maxRealAggregate, aggReal)
      out.maxPostMs = Math.max(out.maxPostMs, post.ms || 0)
      const row = {
        round, tab: tab.key,
        vis: s2.vis,
        logical: s2.logical, real: s2.real,
        cdpInflight: tab.t.streamInFlight,
        hiddenReal: Object.fromEntries(Object.entries(hiddenRows).map(([k, v]) => [k, v.real])),
        aggReal, aggInflight,
        framesMoved: moved,
        post,
      }
      if (s2.vis !== 'visible') out.fails.push(`${tab.key}: 前台标签 visibilityState=${s2.vis}`)
      if (s2.real !== tab.t.streamInFlight) out.fails.push(`${tab.key}: 页面计数 ${s2.real} ≠ CDP 在途 ${tab.t.streamInFlight}`)
      if (tab.key === 'attack' && s2.real !== 3) out.fails.push(`attack: 实连 ${s2.real} ≠ 3`)
      if (tab.key !== 'attack' && s2.real !== 1) out.fails.push(`${tab.key}: 实连 ${s2.real} ≠ 1`)
      if (!moved) out.fails.push(`${tab.key}: 2.3s 内帧计数未增长（白板）`)
      if (!post.status) out.fails.push(`${tab.key}: 普通 POST 失败 ${JSON.stringify(post)}`)
      if ((post.ms || 0) >= 9000) out.fails.push(`${tab.key}: 普通 POST 耗时 ${post.ms}ms（疑似 10s 超时）`)
      if (aggReal > 4) out.fails.push(`${tab.key} 前台时全浏览器实连 ${aggReal} > 4`)
      perTab.push(row)
    }
    out.rounds.push(perTab)
    console.log('ROUND', round, JSON.stringify(perTab, null, 1))
  }

  // ⚠️ 不要把各标签的**峰值**相加当作「同时在线数」：峰值出现在不同时刻（建页时会有重叠），
  //    相加只会得到一个从没真实存在过的数字。同时刻的总量只取每次测量时逐标签读数的和。
  out.perTabPeak = Object.fromEntries(tabs.map((x) => [x.key, x.t.peak]))
  console.log('RESULT', JSON.stringify(out, null, 2))
} finally {
  for (const tab of tabs) tab.t.close()
  if (b) await b.close()
}
