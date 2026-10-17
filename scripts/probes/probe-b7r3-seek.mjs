// probe-b7r3-seek.mjs —— 回放进度条拖拽（Seek）与状态重建的**真浏览器实测**（2026-10-18）。
//
// 查四件事：
//   ① 拖拽中（`@input`）**不重绘**：指标页 ECharts canvas 的像素哈希在 input 前后完全一致，
//      只有滑块旁的目标时间文字变化；松开（`@change`）才触发一次重建。
//   ② 松开后三块视图**同时**跳到目标时刻：横幅进度 = 独立算出的事件索引；指标 KPI / 拍数、
//      状态页五态 / 捕获数、日志页最新行时间，都与「按台账独立重算到 40%」一致。
//   ③ 重建期间出现 loading 遮罩（MutationObserver 记录出现/消失时刻与耗时）。
//   ④ 之前在播 ⇒ 重建完成后自动续播；之前暂停 ⇒ 停在新位置。
//
// 跑法：前端 8080 + 后端 8088（dev）+ `PROBE_ADMIN_PWD="$(cat _work/admin-pass)" node scripts/probes/probe-b7r3-seek.mjs`
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/b7r3-seek/'
const API = 'http://localhost:8088'
const BATCH = process.env.PROBE_BATCH || 'b81ab9b5-3497-4e16-965e-6c67f567c403'
const ORPHAN = process.env.PROBE_ORPHAN || 'b7e62494-7c7f-46f0-882a-4663715f4cda'
const ADMIN_ACC = process.env.PROBE_ADMIN_ACC || 'admin'
const ADMIN_PWD = process.env.PROBE_ADMIN_PWD || ''
const out = (l, v) => console.log(l, typeof v === 'string' ? v : JSON.stringify(v))

if (!ADMIN_PWD) {
  console.error('缺少 PROBE_ADMIN_PWD（用法：PROBE_ADMIN_PWD="$(cat _work/admin-pass)" node scripts/probes/probe-b7r3-seek.mjs）')
  process.exit(2)
}

/* ── 独立口径：直接读台账，按 40% 算出「那一刻」应有的数 ─────────────────── */
const MS = { '+08:00': 8 * 3600 * 1000 }
function epochOf(sqlTime) {
  if (!sqlTime) return 0
  const text = String(sqlTime).replace(' ', 'T')
  const withTz = /(?:Z|[+-]\d{2}:?\d{2})$/.test(text) ? text : `${text}+08:00`
  return new Date(withTz).getTime()
}
async function loadLedger() {
  const body = new URLSearchParams({ batchId: BATCH, limit: '50000' })
  const res = await fetch(`${API}/admin/observe/replay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) throw new Error(`replay API HTTP ${res.status}`)
  const data = await res.json()
  const list = (data.list || []).slice().sort((a, b) => (epochOf(a.OccurredAt) - epochOf(b.OccurredAt))
    || (Number(a.Id) - Number(b.Id)))
  return { list, total: data.total }
}
function expectedAt(list, ratio) {
  if (!list.length) return null
  const t0 = epochOf(list[0].OccurredAt)
  const t1 = epochOf(list[list.length - 1].OccurredAt)
  const span = Math.max(0, t1 - t0)
  const targetOff = ratio * span
  let idx = 0
  while (idx < list.length && (epochOf(list[idx].OccurredAt) - t0) <= targetOff) idx += 1
  const boundary = Math.floor(targetOff / 1000) * 1000
  const tickFrom = boundary - 1000
  const tick = list.slice(0, idx).filter((r) => {
    const off = epochOf(r.OccurredAt) - t0
    return boundary > 0 && off > tickFrom && off <= boundary
  })
  const counts = tick.reduce((acc, r) => {
    acc.n += 1
    if (r.Severity === 'error') acc.errors += 1
    else if (r.Severity === 'protect') acc.protects += 1
    else if (r.Severity === 'warn') acc.warns += 1
    return acc
  }, { n: 0, errors: 0, protects: 0, warns: 0 })
  const moves = list.slice(0, idx).filter((r) => {
    if (r.Kind !== 'order') return false
    let f = r.FieldsJson
    if (typeof f === 'string') { try { f = JSON.parse(f) } catch (e) { f = null } }
    return !!(f && f.to)
  }).length
  const last = idx > 0 ? list[idx - 1] : null
  const lastMs = last ? epochOf(last.OccurredAt) : 0
  return {
    span, targetOff, boundary, idx, total: list.length, moves, lastMs,
    lastClock: lastMs ? clockOf(lastMs) : '—',
    clock: clockOf(t0 + targetOff),
    tick: counts,
  }
}
function clockOf(ms) {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`
}
const numOf = (text) => Number(String(text || '').replace(/[^0-9.-]/g, '')) || 0

const ledger = await loadLedger()
const EXP40 = expectedAt(ledger.list, 0.4)
const EXP70 = expectedAt(ledger.list, 0.7)
out('LEDGER', { total: ledger.total, loaded: ledger.list.length, spanMs: EXP40.span })
out('EXPECTED@40%', EXP40)
out('EXPECTED@70%', { idx: EXP70.idx, clock: EXP70.clock, moves: EXP70.moves })

const b = await open({ w: 1440, h: 1000, headed: false })
const waitFor = async (expr, ms = 15000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    if (await b.evalJS(expr)) return true
    await sleep(80)
  }
  return false
}

// ── 登录（dev profile 闸关，但前端仍走正常登录页）────────────────────────────
await b.goto(`${BASE}#/login`, 2500)
await b.evalJS(`(() => {
  const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
  set(document.querySelector('#acc'), ${JSON.stringify(ADMIN_ACC)})
  set(document.querySelector('#pwd'), ${JSON.stringify(ADMIN_PWD)})
  const btn = document.querySelector('button.btn.btn-primary.btn-block')
  if (btn) btn.click()
})()`)
for (let i = 0; i < 40; i++) {
  await sleep(500)
  if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
}
out('LOGIN', await b.evalJS('location.hash'))

await b.goto(`${BASE}#/admin/observe/metrics`, 4500)
await waitFor(`!!document.querySelector('#a-rb-batch')`, 15000)

// ── ① 批次下拉：孤儿批次置灰、正样本可选 ─────────────────────────────────────
const opts = await b.evalJS(`(() => [...document.querySelectorAll('#a-rb-batch option')].map((o) => ({
  v: o.value, t: o.textContent.trim(), disabled: o.disabled
})))()`)
const optPositive = opts.find((o) => o.v === BATCH)
const optOrphan = opts.find((o) => o.v === ORPHAN)
out('OPTION_POSITIVE', optPositive || null)
out('OPTION_ORPHAN', optOrphan || null)
await b.evalJS(`(() => { const s = document.querySelector('#a-rb-batch'); s.value = ${JSON.stringify(ORPHAN)}; s.dispatchEvent(new Event('change', { bubbles: true })); return true })()`)
await sleep(300)
out('ORPHAN_PICK_BTN_DISABLED', await b.evalJS(`(() => {
  const btn = [...document.querySelectorAll('.a-rb-pick .a-rb-btn')].find((x) => x.textContent.trim() === '回放')
  return btn ? btn.disabled : null
})()`))
await b.shot(`${OUT}01-orphan-disabled.png`)

// ── ② 启动正样本回放，播一会后暂停 ───────────────────────────────────────────
await b.evalJS(`(() => { const s = document.querySelector('#a-rb-batch'); s.value = ${JSON.stringify(BATCH)}; s.dispatchEvent(new Event('change', { bubbles: true })); return true })()`)
await sleep(300)
await b.evalJS(`(() => {
  const btn = [...document.querySelectorAll('.a-rb-pick .a-rb-btn')].find((x) => x.textContent.trim() === '回放')
  if (btn) btn.click()
  return !!btn
})()`)
const active = await waitFor(`(() => {
  const rb = document.querySelector('.a-rb')
  const prog = rb && rb.querySelector('.a-rb-prog b')
  return !!prog && Number((prog.textContent || '0').replace(/[^0-9]/g, '')) > 0
})()`, 20000)
out('REPLAY_ACTIVE', active)
await b.evalJS(`(() => {
  const btn = [...document.querySelectorAll('.a-rb .a-rb-btn')].find((x) => x.textContent.trim() === '暂停')
  if (btn) btn.click()
  return !!btn
})()`)
await sleep(400)

const snapshot = async () => b.evalJS(`(() => {
  const rb = document.querySelector('.a-rb')
  const q = (s) => { const el = rb && rb.querySelector(s); return el ? el.textContent.trim() : '' }
  const kpis = [...document.querySelectorAll('.mp-kpi')].map((k) => k.textContent.replace(/\\s+/g, ' ').trim())
  const canvases = [...document.querySelectorAll('.mp-chart canvas')]
  const hash = (s) => { let h = 5381; for (let i = 0; i < s.length; i += 97) h = ((h * 33) ^ s.charCodeAt(i)) >>> 0; return h }
  return {
    prog: q('.a-rb-prog b'),
    progAll: q('.a-rb-prog'),
    clock: q('.a-rb-clock b'),
    state: q('.a-rb-state'),
    tip: q('.a-rb-seektip'),
    range: (document.querySelector('.a-rb-range') || {}).value,
    kpis,
    canvas: canvases.map((c) => { try { return hash(c.toDataURL()) } catch (e) { return 'err' } }),
    railText: [...document.querySelectorAll('.mp-rail')].map((e) => e.textContent.replace(/\\s+/g, ' ').trim()).join(' | '),
  }
})()`)

const before = await snapshot()
out('BEFORE_DRAG', before)

// ── ③ 拖到 40%：input 只改文字（canvas 哈希不变），change 才重建 ─────────────
const drag = await b.evalJS(`(async () => {
  const rb = document.querySelector('.a-rb')
  const r = document.querySelector('.a-rb-range')
  const hash = (s) => { let h = 5381; for (let i = 0; i < s.length; i += 97) h = ((h * 33) ^ s.charCodeAt(i)) >>> 0; return h }
  const canvasHash = () => [...document.querySelectorAll('.mp-chart canvas')].map((c) => { try { return hash(c.toDataURL()) } catch (e) { return 'err' } })
  const before = canvasHash()
  window.__seekMarks = []
  let maskOn = !!document.querySelector('.a-rb-mask')
  const mo = new MutationObserver(() => {
    const on = !!document.querySelector('.a-rb-mask')
    if (on !== maskOn) { maskOn = on; window.__seekMarks.push({ mask: on, t: performance.now() }) }
  })
  mo.observe(document.body, { childList: true, subtree: true })
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  setter.call(r, '40')
  r.dispatchEvent(new Event('input', { bubbles: true }))
  await new Promise((res) => requestAnimationFrame(() => requestAnimationFrame(res)))
  const afterInput = canvasHash()
  const tipDuring = (rb.querySelector('.a-rb-seektip') || {}).textContent || ''
  const t0 = performance.now()
  r.dispatchEvent(new Event('change', { bubbles: true }))
  // 等遮罩消失（最多 5s）
  const deadline = performance.now() + 5000
  while (performance.now() < deadline) {
    const on = !!document.querySelector('.a-rb-mask')
    if (!on && performance.now() - t0 > 50) break
    await new Promise((res) => setTimeout(res, 16))
  }
  await new Promise((res) => setTimeout(res, 300))
  mo.disconnect()
  return {
    before, afterInput, tipDuring,
    durationMs: Math.round(performance.now() - t0),
    marks: window.__seekMarks,
  }
})()`)
out('DRAG_INPUT_VS_CHANGE', drag)
await sleep(400)
const at40 = await snapshot()
out('AT_40', at40)
await b.shot(`${OUT}02-seek-40-metrics.png`)

// ── ④ 状态页：五态与捕获数 ──────────────────────────────────────────────────
await b.goto(`${BASE}#/admin/observe/timeline`, 3500)
await waitFor(`!!document.querySelector('.a-of-kpis')`, 10000)
await sleep(500)
const flow = await b.evalJS(`(() => {
  const txt = (s) => { const el = document.querySelector(s); return el ? el.textContent.replace(/\\s+/g, ' ').trim() : '' }
  return {
    kpis: [...document.querySelectorAll('.a-of-kpis .a-of-kpi')].map((e) => e.textContent.replace(/\\s+/g, ' ').trim()),
    ladder: [...document.querySelectorAll('.a-of-ladder .a-of-step')].map((e) => e.textContent.replace(/\\s+/g, ' ').trim()),
    streamSum: txt('.a-of-stream-sum'),
    streamNote: txt('.a-of-stream .a-of-chd'),
    cursorProg: txt('.a-of-stream-sum'),
    donut: txt('.a-of-health'),
  }
})()`)
out('AT_40_FLOW', flow)
await b.shot(`${OUT}03-seek-40-timeline.png`)

// ── ⑤ 日志页：最新行时间 / 行数 / 进度 ───────────────────────────────────────
await b.goto(`${BASE}#/admin/observe/logs`, 3500)
await waitFor(`!!document.querySelector('#ev-list')`, 10000)
await sleep(600)
// ⚠️ `streamRows` 取最新 200 条后**反转为「旧在上、新在下」**（autoBottom 滚到底看最新）。
// ⇒ 最新行是列表**底部**那一行；窗口化只渲染可视区，所以先滚到底并主动派发 scroll
// （改 scrollTop 不会自己触发监听），再读最后一个带时间戳的行。
// ⚠️ 日志页是「挂载后才接手 sink」：会话的 backlog 按每帧 50 条补画，4k 条要 ~1.3s。
//    读一次就断言会把「还在补画」误判成数据不一致 ⇒ 轮询到最新行时间追上目标（或超时）。
async function readLogs() {
  await b.evalJS(`(() => {
    const list = document.querySelector('#ev-list')
    if (!list) return false
    list.scrollTop = list.scrollHeight
    list.dispatchEvent(new Event('scroll'))
    return true
  })()`)
  await sleep(300)
  return b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('#ev-list .log-line')]
    const timed = rows.map((r) => ({
      tm: ((r.querySelector('.tm') || {}).textContent || '').trim(),
      txt: r.textContent.replace(/\\s+/g, ' ').trim().slice(0, 160),
    })).filter((x) => x.tm)
    const newest = timed.length ? timed[timed.length - 1] : { tm: '', txt: '' }
    const rb = document.querySelector('.a-rb')
    return {
      rendered: rows.length,
      newestRowTime: newest.tm,
      newestRowText: newest.txt,
      prog: rb ? (rb.querySelector('.a-rb-prog') || {}).textContent.trim() : '',
      clock: rb ? (rb.querySelector('.a-rb-clock b') || {}).textContent.trim() : '',
      status: rb ? (rb.querySelector('.a-rb-state') || {}).textContent.trim() : '',
      aggChips: document.querySelectorAll('#ev-list .agg-chip').length,
    }
  })()`)
}
let logs = await readLogs()
for (let i = 0; i < 20 && logs.newestRowTime !== EXP40.lastClock; i += 1) {
  logs = await readLogs()
}
out('LOGS_POLL_TAIL', { newestRowTime: logs.newestRowTime, expected: EXP40.lastClock })
out('AT_40_LOGS', logs)
await b.shot(`${OUT}04-seek-40-logs.png`)

// ── ⑥ 回到指标页，续播后拖到 70%：重建完应自动继续播 ────────────────────────
await b.goto(`${BASE}#/admin/observe/metrics`, 3000)
await sleep(500)
await b.evalJS(`(() => {
  const btn = [...document.querySelectorAll('.a-rb .a-rb-btn')].find((x) => x.textContent.trim() === '继续')
  if (btn) btn.click()
  return !!btn
})()`)
await sleep(1200)
await b.evalJS(`(() => {
  const r = document.querySelector('.a-rb-range')
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  setter.call(r, '70')
  r.dispatchEvent(new Event('input', { bubbles: true }))
  r.dispatchEvent(new Event('change', { bubbles: true }))
  return true
})()`)
const resumed = await waitFor(`(() => {
  const rb = document.querySelector('.a-rb')
  const st = rb && rb.querySelector('.a-rb-state')
  return !!st && st.textContent.includes('播放中')
})()`, 8000)
const p1 = await b.evalJS(`Number(((document.querySelector('.a-rb-prog b') || {}).textContent || '0').replace(/[^0-9]/g, ''))`)
await sleep(1500)
const p2 = await b.evalJS(`Number(((document.querySelector('.a-rb-prog b') || {}).textContent || '0').replace(/[^0-9]/g, ''))`)
out('RESUME_AFTER_SEEK_70', { resumed, progAfterSeek: p1, progAfter1500ms: p2, expected70: EXP70.idx })
await b.shot(`${OUT}05-seek-70-resumed.png`)

// ── ⑦ 汇总判定 ─────────────────────────────────────────────────────────────
const gotProg = numOf(at40.prog)
const gotTick = numOf((at40.kpis[0] || '').replace(/^.*?([0-9,]+)条.*$/, '$1'))
const metricsOk = gotProg === EXP40.idx
  && at40.canvas.join(',') !== before.canvas.join(',')
  && drag.before.join(',') === drag.afterInput.join(',')
  && drag.tipDuring.includes('松开')
  && drag.marks.some((m) => m.mask === true) && drag.marks.some((m) => m.mask === false)
const clockOk = at40.clock === EXP40.clock
const logProgOk = numOf((logs.prog.match(/[\d,]+/g) || [])[0]) === EXP40.idx
const logClockOk = logs.clock === EXP40.clock
const logRowOk = logs.newestRowTime === EXP40.lastClock
  || logs.newestRowTime.endsWith(EXP40.lastClock.split('.')[0])
const movesMatch = flow.streamNote.match(/已捕获\s*([\d,]+)\s*条/)
const flowMovesOk = movesMatch ? numOf(movesMatch[1]) === EXP40.moves : null
const verdict = {
  noRedrawDuringInput: drag.before.join(',') === drag.afterInput.join(','),
  redrawnAfterRelease: at40.canvas.join(',') !== before.canvas.join(','),
  maskShown: drag.marks.some((m) => m.mask === true),
  maskHideMs: drag.marks.length >= 2 ? Math.round(drag.marks[1].t - drag.marks[0].t) : null,
  bannerCursorMatchesOracle: gotProg === EXP40.idx,
  clockMatchesOracle: clockOk,
  metricsTickMatchesOracle: gotTick === EXP40.tick.n,
  logsCursorMatchesOracle: logProgOk,
  logsClockMatchesOracle: logClockOk,
  newestLogRowMatchesLastApplied: logRowOk,
  flowMovesMatchesOracle: flowMovesOk,
  resumedAfterSeek: resumed && p2 > p1,
  orphanGreyed: !!optOrphan && optOrphan.disabled === true && optOrphan.t.includes('数据已过期清理'),
  positiveSelectable: !!optPositive && optPositive.disabled === false,
  metricsOk,
}
out('VERDICT', verdict)
const failed = Object.entries(verdict).filter(([k, v]) => v === false || v === null)
out('FAILED', failed)
await b.close?.()
process.exit(failed.length ? 1 : 0)
