// c3b7-3a4c-perf.mjs —— 块 7 · 2026-10-17「卡顿 / 回放逐帧」验收探针。
//
// 真后端（8088）+ 真前端（8080，admin.html 入口），**不 mock 任何请求**。
//
// 验两件事（用户 2026-10-17 原话）：
//   ① 不卡顿：打开指标页，录 3 秒 CDP Performance Trace；切到日志页再切回指标页。
//      期间 Long Task（>50ms）数量 / >100ms 数量 / 平均 FPS；日志列表 DOM ≤ 200 行。
//   ② 回放逐帧：打开回放，每 500ms 采一次「屏上事件行数」并截图，连续 4 次；
//      行数必须严格递增、第一秒不允许铺满全量。
//
// 产物：
//   docs/agent/evidence/c3-block7/3a4c-perf-*.png
//   stdout 的 JSON（调用方重定向到 .txt —— 证据文件必须 .txt）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const API = 'http://localhost:8088'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const out = { at: new Date().toISOString(), checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
}

async function post(path, body) {
  const res = await fetch(API + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: body || '',
    signal: AbortSignal.timeout(120000),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) { /* 留原文 */ }
  return { status: res.status, json, text }
}

const b = await open({ w: 1440, h: 900 })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      try {
        sessionStorage.setItem('loginUsername', 'ops_duan')
        sessionStorage.setItem('customerid', 'qa7PmpUojO')
        sessionStorage.setItem('role', '3')
      } catch (e) {}
    })()`,
  })

  // 先造一批真事件（日志页首帧快照要 > 200 行才测得到窗口化）。
  const pre = []
  for (const path of ['/goods/goodsSelectHome', '/goods/productSearch?keyword=a', '/goods/goodstype']) {
    try { const r = await fetch(API + path, { signal: AbortSignal.timeout(5000) }); pre.push({ path, status: r.status }) }
    catch (e) { pre.push({ path, error: String(e && e.message) }) }
  }
  out.samples.preTraffic = pre
  const drill = await post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-perf-20261017c',
    concurrency: '20',
    durationSeconds: '6',
    targetQps: '200',
    scenarios: JSON.stringify([{ key: 'orderBurst', weight: 1 }]),
  }).toString())
  out.samples.drill = {
    status: drill.status, ok: !!(drill.json && drill.json.success),
    id: drill.json && drill.json.Id, batchId: drill.json && drill.json.BatchId,
    total: drill.json && drill.json.TotalRequests,
  }
  await sleep(3000)

  // ────────────────────────────────────────────────────────────────────────
  // ① 性能：先进指标页并稳定 4 秒（用户口径：指标页是「起点」），再开始录
  // ────────────────────────────────────────────────────────────────────────
  await b.goto(`${WEB}/admin.html#/admin/observe/metrics`, 6000)
  await sleep(3500)
  out.samples.metricsSettled = await b.evalJS(`(() => ({
    theme: document.documentElement.dataset.theme || null,
    hash: location.hash,
    seq: (() => { const e = document.querySelector('.a-tz b'); return e ? Number(e.innerText.replace(/[^0-9]/g, '')) : null })(),
    charts: document.querySelectorAll('.mp-chart canvas').length,
  }))()`)

  // CDP Trace 收集（RunTask = 主线程任务；>50ms 即 Long Task）
  const traceEvents = []
  const onTrace = (raw) => {
    let m = null
    try { m = JSON.parse(raw) } catch (e) { return }
    if (m.method === 'Tracing.dataCollected' && m.params && m.params.value) traceEvents.push(...m.params.value)
  }
  b.ws.on('message', onTrace)
  const traceDone = new Promise((resolve) => {
    const onDone = (raw) => {
      let m = null
      try { m = JSON.parse(raw) } catch (e) { return }
      if (m.method === 'Tracing.tracingComplete') { b.ws.off('message', onDone); resolve() }
    }
    b.ws.on('message', onDone)
  })
  await b.send('Tracing.start', {
    categories: 'devtools.timeline,v8,blink.user_timing',
    transferMode: 'ReportEvents',
  })

  // 性能观测器：Long Task + rAF 帧数（只在「切页窗口」里统计）
  await b.evalJS(`(() => {
    window.__perf = { start: performance.now(), lt: [], frames: 0, switchStart: 0 }
    window.__perf.ltObs = new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        window.__perf.lt.push({ d: Math.round(e.duration), t: Math.round(e.startTime - window.__perf.start) })
      }
    })
    window.__perf.ltObs.observe({ type: 'longtask', buffered: false })
    const loop = () => { window.__perf.frames++; requestAnimationFrame(loop) }
    requestAnimationFrame(loop)
    return true
  })()`)

  // 阶段 A：指标页静止 3 秒（Trace 覆盖）
  await sleep(3000)
  out.samples.metricsIdle = await b.evalJS(`(() => {
    const p = window.__perf
    const d = p.lt.map((x) => x.d)
    return { longTasks: d.length, maxLongTask: d.length ? Math.max(...d) : 0, lt: p.lt.slice(0, 10) }
  })()`)

  // 阶段 B：指标 → 日志 → 指标（只统计这一段）
  await b.evalJS(`(() => {
    window.__perf.switchStart = performance.now()
    window.__perf.lt = []
    window.__perf.frames = 0
    location.hash = '#/admin/observe/logs'
    return true
  })()`)
  await sleep(2500)
  const logDom = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    const unwrap = (v) => (v && typeof v === 'object' && 'value' in v ? v.value : v)
    const ev = s && unwrap(s.events)
    const rg = s && unwrap(s.ranged)
    return {
      rendered: document.querySelectorAll('#ev-list .log-line').length,
      renderedLi: document.querySelectorAll('#ev-list li').length,
      events: Array.isArray(ev) ? ev.length : null,
      ranged: Array.isArray(rg) ? rg.length : null,
      nodes: document.getElementsByTagName('*').length,
      range: (() => { const e = document.querySelector('.ev-status'); return e ? e.innerText.replace(/\\s+/g, ' ').slice(0, 80) : null })(),
    }
  })()`)
  out.samples.logDom = logDom
  await b.shot(EV + '3a4c-perf-logs-cap.png')
  // 滚到顶部再读一次：窗口化必须仍然 ≤200（不是「只有第一屏有效」）。
  await b.evalJS(`(() => { const e = document.querySelector('#ev-list'); if (e) e.scrollTop = 0; return true })()`)
  await sleep(600)
  out.samples.logDomTop = await b.evalJS(`(() => ({
    rendered: document.querySelectorAll('#ev-list .log-line').length,
    renderedLi: document.querySelectorAll('#ev-list li').length,
    nodes: document.getElementsByTagName('*').length,
  }))()`)

  await b.evalJS(`location.hash = '#/admin/observe/metrics'; true`)
  await sleep(2500)
  const switchPerf = await b.evalJS(`(() => {
    const p = window.__perf
    const dur = performance.now() - p.switchStart
    const d = p.lt.map((x) => x.d).sort((a, b) => a - b)
    return {
      durationMs: Math.round(dur), frames: p.frames, fps: +(p.frames / (dur / 1000)).toFixed(1),
      longTasks: d.length, maxLongTask: d.length ? d[d.length - 1] : 0,
      longTasksOver100: d.filter((x) => x > 100).length,
      lt: p.lt,
      seq: (() => { const e = document.querySelector('.a-tz b'); return e ? Number(e.innerText.replace(/[^0-9]/g, '')) : null })(),
    }
  })()`)
  out.samples.switchPerf = switchPerf
  await b.shot(EV + '3a4c-perf-metrics-return.png')

  await b.send('Tracing.end')
  await traceDone
  b.ws.off('message', onTrace)
  // Trace 摘要：本版 Edge 不发 `RunTask`（实测 12k 事件里 0 条）⇒ 用「X 事件」看主线程在干什么。
  // 先取 X 事件最多的 tid 当渲染主线程，再按名字聚合 >10ms 的段（Layout / EvaluateScript / Paint…）。
  const X = traceEvents.filter((e) => e.ph === 'X' && typeof e.dur === 'number')
  const byTid = new Map()
  for (const e of X) byTid.set(e.tid, (byTid.get(e.tid) || 0) + 1)
  const mainTid = [...byTid.entries()].sort((a, b) => b[1] - a[1])[0]
  const mainX = mainTid ? X.filter((e) => e.tid === mainTid[0]) : X
  const nameAgg = new Map()
  for (const e of mainX) {
    if (e.dur < 10000) continue
    const cur = nameAgg.get(e.name) || { n: 0, total: 0, max: 0 }
    cur.n += 1; cur.total += e.dur; cur.max = Math.max(cur.max, e.dur)
    nameAgg.set(e.name, cur)
  }
  const runTasks = traceEvents.filter((e) => e.name === 'RunTask' && e.ph === 'X')
  const xMax = mainX.reduce((m, e) => Math.max(m, e.dur), 0) / 1000
  out.samples.trace = {
    events: traceEvents.length,
    xEvents: X.length,
    mainTid: mainTid ? mainTid[0] : null,
    runTaskEvents: runTasks.length,   // 0 = 本版 Edge 的 devtools.timeline 不产出 RunTask
    maxSingleEventMs: +xMax.toFixed(1),
    over10ms: [...nameAgg.entries()].sort((a, b) => b[1].total - a[1].total).slice(0, 8)
      .map(([name, v]) => ({ name, n: v.n, totalMs: +(v.total / 1000).toFixed(1), maxMs: +(v.max / 1000).toFixed(1) })),
  }
  check('trace-no-single-event-over-100ms', xMax <= 100, { maxSingleEventMs: out.samples.trace.maxSingleEventMs })

  check('switch-no-long-task-over-100ms', switchPerf.longTasksOver100 === 0,
    { longTasksOver100: switchPerf.longTasksOver100, maxLongTask: switchPerf.maxLongTask, top: switchPerf.lt.slice(0, 6) })
  check('switch-fps-healthy', switchPerf.fps >= 50, { fps: switchPerf.fps, durationMs: switchPerf.durationMs })
  check('logs-dom-under-200', logDom.rendered <= 200 && logDom.renderedLi <= 202,
    { rendered: logDom.rendered, li: logDom.renderedLi, events: logDom.events, ranged: logDom.ranged })
  check('logs-dom-under-200-after-scroll', out.samples.logDomTop.rendered <= 200 && out.samples.logDomTop.renderedLi <= 202,
    out.samples.logDomTop)
  check('logs-window-keeps-data', (logDom.events || 0) > 200, { events: logDom.events, ranged: logDom.ranged })

  // ────────────────────────────────────────────────────────────────────────
  // ② 回放：每 500ms 采一次行数 + 截图，连续 4 次
  // ────────────────────────────────────────────────────────────────────────
  const recs = await post('/admin/observe/attack/records', '')
  const list = (recs.json && recs.json.list) || []
  const newest = list.find((r) => r.Id === (drill.json && drill.json.Id)) || list[0]
  const batchId = (newest && newest.BatchId) || (drill.json && drill.json.BatchId)
  out.samples.batchId = batchId

  await b.goto(`${WEB}/admin.html#/admin/observe/attack-sim`, 5000)
  await sleep(2500)
  const clicked = await b.evalJS(`(() => {
    const bid = ${JSON.stringify(batchId || '')}
    const rows = [...document.querySelectorAll('li.a-ev')]
    const row = rows.find((r) => bid && r.innerText.includes(bid)) || rows[0]
    if (!row) return { ok: false, why: 'no rows', n: rows.length }
    const head = row.querySelector('.a-ev-head')
    if (head) head.click()
    return { ok: true, expanded: !!head }
  })()`)
  out.samples.replayClick = clicked
  await sleep(500)
  const playClicked = await b.evalJS(`(() => {
    const btns = [...document.querySelectorAll('li.a-ev.on button')]
    const btn = btns.find((x) => (x.textContent || '').includes('回放'))
    if (!btn) return { ok: false, why: 'no replay button', n: btns.length }
    if (btn.disabled) return { ok: false, why: 'disabled' }
    btn.click()
    return { ok: true }
  })()`)
  out.samples.playClicked = playClicked
  await sleep(600)
  await b.evalJS(`(() => { const e = document.querySelector('.a-rp-live'); if (e) e.scrollIntoView({ block: 'center' }); return true })()`)
  await sleep(200)

  const replayTicks = []
  for (let i = 1; i <= 4; i += 1) {
    const s = await b.evalJS(`(() => {
      const t = document.querySelector('.a-rp-live-t')
      const txt = t ? t.innerText.replace(/\\s+/g, ' ').trim() : null
      const m = /进度\\s*(\\d+)\\s*\\/\\s*(\\d+)/.exec(txt || '')
      return {
        rows: document.querySelectorAll('.a-rp-ev').length,
        cursor: m ? Number(m[1]) : null,
        total: m ? Number(m[2]) : null,
        nodes: document.getElementsByTagName('*').length,
        txt,
      }
    })()`)
    s.atMs = i * 500
    replayTicks.push(s)
    await b.shot(EV + `3a4c-replay-t${(i * 0.5).toFixed(1)}.png`)
    await sleep(500)
  }
  out.samples.replayTicks = replayTicks
  const counts = replayTicks.map((x) => x.rows)
  const cursors = replayTicks.map((x) => x.cursor)
  const monotone = counts.every((v, i) => i === 0 || v > counts[i - 1])
  const cursorMonotone = cursors.every((v, i) => i === 0 || (v != null && cursors[i - 1] != null && v > cursors[i - 1]))
  const notInstant = cursors[0] != null && cursors[0] < cursors[0 + 1] && replayTicks[3].total != null
    && replayTicks[0].cursor < replayTicks[0].total * 0.2
  check('replay-dom-grows-gradually', monotone && cursorMonotone && notInstant,
    { ticks: replayTicks.map((x) => ({ at: x.atMs, rows: x.rows, cursor: x.cursor, total: x.total })) })
  out.samples.replayDom = replayTicks.map((x) => ({ at: x.atMs, rows: x.rows, nodes: x.nodes }))
} catch (e) {
  out.error = String((e && e.stack) || e)
  fail.push('probe-exception: ' + out.error)
} finally {
  await b.close()
}

out.fail = fail
console.log(JSON.stringify(out, null, 2))
process.exit(fail.length ? 1 : 0)
