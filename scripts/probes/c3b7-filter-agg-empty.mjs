// c3b7-filter-agg-empty.mjs —— 块 7 · 2026-10-17「筛选与聚合冲突 ⇒ 列表空」复现/验收探针。
//
// 用户复现路径：勾选「同类合并（聚合）」→ 点「≥ WARN」→ 左下角统计有「警告 N」，
// 中间列表却写「当前筛选下一条也没有」。根因是两侧数据源不同：
//   · 统计读 RAW（≤8192，压缩前原始台账）；
//   · ③ 列表过去读 W.events（展示窗口 ≤200）⇒ WARN 一旦被后来的 INFO 挤出 200 条窗口，
//     按 ≥WARN 过滤就得到 0 条。
//
// 本探针用真后端造出这个形状（不允许 mock）：
//   ① seckill 打到库存耗尽 ⇒ 大量 WARN「库存不足（未扣，防超卖命中）」；
//   ② cacheCold 800 QPS 灌 INFO，把 WARN 全部挤出 W.events 的 200 条窗口（RAW 仍保留）；
//   ③ 冻结视图 → 勾聚合 → 点 ≥WARN → 读两侧状态。
//
// 判据（期望值 = 修复后）：
//   · rawWindow.bySev.warn > 0（统计侧确实有警告）；
//   · W.events 里 warn === 0（证明这正是「展示窗口与统计不同源」的形状）；
//   · filtered.length > 0 且其中全部是 warn/protect/error（先筛原始台账再聚合）；
//   · streamRows.length > 0、DOM 行数 > 0、空状态不可见；
//   · 虚拟滚动游标在范围内（vStart < streamRows.length）。
//
// 产物：stdout JSON；截图 3a4g-filter-agg-before.png / after.png（由调用参数决定名字）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const API = 'http://localhost:8088'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const SHOT = process.argv[2] || '3a4g-filter-agg.png'

const out = { at: new Date().toISOString(), shot: SHOT, checks: {}, samples: {} }
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
    signal: AbortSignal.timeout(180000),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) { /* 留原文 */ }
  return { status: res.status, json, text }
}

const b = await open({ w: 1440, h: 900 })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => { try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
  })
  await b.goto(`${WEB}/admin.html?fagg=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2500)

  // ── ① 秒杀打到库存耗尽：WARN 的来源（库存不足 / 防超卖命中）────────────────────
  // 200 QPS × 12s：远低于入站限流，也不会把 orderAdd 的 Redisson 锁排到 >1s（避免熔断）。
  const runKill = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-filter-agg-seckill-20261017',
    concurrency: '128',
    durationSeconds: '12',
    targetQps: '200',
    scenarios: JSON.stringify([{ key: 'seckill', weight: 100 }]),
  }).toString())
  runKill.catch(() => {})
  // 等到统计侧出现足够的 WARN（库存耗尽后会持续产生「库存不足」）。
  let warnStats = null
  for (let i = 0; i < 60; i++) {
    await sleep(500)
    warnStats = await b.evalJS(`(() => {
      const el = document.querySelector('.ev-frame')
      const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
      if (!s) return null
      return { n: s.rawWindow.n, warn: s.rawWindow.bySev.warn || 0, protect: s.rawWindow.bySev.protect || 0 }
    })()`)
    if (warnStats && warnStats.warn >= 120) break
  }
  out.samples.afterSeckill = warnStats
  try { await post('/admin/observe/attack/stop', '') } catch (e) { /* 已结束 */ }
  try { await runKill } catch (e) { /* 超时也读已发生的事件 */ }
  await sleep(800)

  // ── ② 灌 INFO：把 WARN 挤出 W.events（EVENT_CAP=200），RAW 仍保留 ────────────
  // 这一段**临时关掉入站限流**：600 QPS 会被 rateLimit 拦成 PROTECT（≥WARN 会命中它），
  // 那样列表里还剩一条 PROTECT 行，看不到用户报的「一条也没有」。灌完立刻还原。
  const cfg = await (await fetch(API + '/admin/observe/attack/config', { signal: AbortSignal.timeout(8000) })).json()
  const protBefore = (cfg && cfg.Protections) || []
  const protNoLimit = protBefore.filter((p) => p !== 'rateLimit')
  out.samples.protectionsBefore = protBefore
  await post('/admin/observe/attack/config',
    new URLSearchParams({ protections: JSON.stringify(protNoLimit) }).toString())
  const runCold = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-filter-agg-flood-20261017',
    concurrency: '256',
    durationSeconds: '6',
    targetQps: '600',
    scenarios: JSON.stringify([{ key: 'cacheCold', weight: 100 }]),
  }).toString())
  runCold.catch(() => {})
  try { await runCold } catch (e) { /* 同上 */ }
  await post('/admin/observe/attack/config',
    new URLSearchParams({ protections: JSON.stringify(protBefore) }).toString())
  await sleep(1500)

  // ── ③ 冻结视图（只停并入，不动已有数据）⇒ 勾聚合 ⇒ 点 ≥ WARN ─────────────────
  await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    s.paused = true
    s.aggEnabled = true
    return { paused: s.paused, agg: s.aggEnabled }
  })()`)
  await sleep(500)
  const clicked = await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('button.chip')].find((x) => x.textContent.trim() === '≥ WARN')
    if (!btn) return false
    btn.click()
    return true
  })()`)
  out.samples.clickedWarnUp = clicked
  await sleep(900)

  const st = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    if (!s) return null
    const evSev = {}
    for (const e of s.events) evSev[e.severity] = (evSev[e.severity] || 0) + 1
    const fSev = {}
    for (const e of s.filtered) fSev[e.severity] = (fSev[e.severity] || 0) + 1
    const list = document.querySelector('#ev-list')
    const empty = document.querySelector('.ev-empty')
    return {
      warnUp: s.warnUp,
      aggEnabled: s.aggEnabled,
      raw: { n: s.rawWindow.n, sev: { ...s.rawWindow.bySev } },
      displayWindow: { n: s.events.length, sev: evSev },
      filtered: { n: s.filtered.length, sev: fSev },
      streamRows: s.streamRows.length,
      virtual: { on: s.virtualOn, start: s.vStart, end: s.vEnd },
      scrollTop: list ? list.scrollTop : null,
      scrollHeight: list ? list.scrollHeight : null,
      domRows: document.querySelectorAll('#ev-list .log-line').length,
      emptyVisible: !!empty,
      emptyText: empty ? (empty.innerText || '').replace(/\\s+/g, ' ').trim() : '',
      rowTitles: [...document.querySelectorAll('#ev-list .log-line')]
        .map((r) => (r.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 70)).slice(0, 6),
    }
  })()`)
  out.samples.state = st

  check('warn-exists-in-raw', st && (st.raw.sev.warn || 0) > 0, st && st.raw)
  check('warn-absent-from-display-window', st && (st.displayWindow.sev.warn || 0) === 0,
    st && st.displayWindow)
  check('filter-keeps-raw-warn', st && st.filtered.n > 0
    && (st.filtered.sev.warn || 0) > 0 && (st.filtered.sev.info || 0) === 0, st && st.filtered)
  check('stream-rows-not-empty', st && st.streamRows > 0, st && { streamRows: st.streamRows })
  check('dom-rows-rendered', st && st.domRows > 0, st && { domRows: st.domRows })
  check('empty-state-hidden', st && !st.emptyVisible, st && st.emptyText)
  check('virtual-cursor-in-range', st && (!st.virtual.on || st.virtual.start < st.streamRows),
    st && { virtual: st.virtual, streamRows: st.streamRows })

  // 截图前重绘一次，避免合成层跨帧。
  await b.evalJS(`(() => {
    const b = document.body
    b.style.zoom = '1.0001'; void b.offsetHeight; b.style.zoom = ''; void b.offsetHeight
    return true
  })()`)
  await sleep(400)
  await b.shot(EV + SHOT)

  // ── ④ 虚拟滚动游标复位（用户要求：改筛选必须立刻归零，不能切出越界空窗口）──────
  // 关聚合 ⇒ 逐条行（≤200 + trace 脚手架）触发窗口化；强制 virtualOn 后把游标推到
  // 远越界位置，再改一次筛选条件，看是否：a) 越界时 vRows 仍夹得到行（不空白）；
  // b) 筛选一变立即回到 vStart=0、scrollTop=0，且 DOM 非空。
  await b.evalJS(`(() => {
    const s = document.querySelector('.ev-frame').__vueParentComponent.setupState
    s.aggEnabled = false
    return true
  })()`)
  await sleep(700)
  await b.evalJS(`(() => {
    const s = document.querySelector('.ev-frame').__vueParentComponent.setupState
    s.virtualOn = true
    s.vStart = 9999
    s.vEnd = 10039
    return true
  })()`)
  await sleep(400)
  const stale = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    return {
      n: s.streamRows.length,
      vStart: s.vStart, vEnd: s.vEnd,
      vRows: s.vRows.length,
      domRows: document.querySelectorAll('#ev-list .log-line').length,
    }
  })()`)
  out.samples.staleCursor = stale
  check('stale-cursor-clamped-not-blank', stale.n === 0 || stale.vRows > 0,
    stale)

  await b.evalJS(`(() => {
    const s = document.querySelector('.ev-frame').__vueParentComponent.setupState
    s.warnUp = false
    return true
  })()`)
  await sleep(800)
  const reset = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    const list = document.querySelector('#ev-list')
    return {
      n: s.streamRows.length, vStart: s.vStart, vEnd: s.vEnd,
      scrollTop: list ? list.scrollTop : null,
      domRows: document.querySelectorAll('#ev-list .log-line').length,
      empty: !!document.querySelector('.ev-empty'),
    }
  })()`)
  out.samples.afterFilterReset = reset
  check('cursor-reset-on-filter-change',
    reset.n > 0 && reset.vStart === 0 && reset.scrollTop === 0 && reset.domRows > 0 && !reset.empty,
    reset)

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  try { await post('/admin/observe/attack/stop', '') } catch (e) { /* 收尾失败不掩盖判据 */ }
  await b.close()
}
