// b7-sse-count-after.mjs —— 修复后验收（单标签页）。
// ① 攻击页 ↔ 其他页反复 3 次：攻击页稳定 3 条，离开后 0 条；
// ② 指标 → 攻击 → 指标：KeepAlive 页切走必须关流，最大不超过 3 条；
// ③ 每次回到页面，帧计数必须继续增长（数据在刷新，不是白板）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const b = await open({ w: 1500, h: 1000, headed: false })

const snap = async (label) => {
  const s = await b.evalJS(`(() => ({
    count: (window.__sse_instances || []).length,
    paths: (window.__sse_instances || []).map(e => e.path),
    frames: Object.assign({}, window.__sse_frames || {}),
    text: (document.body.innerText || '').slice(0, 120)
  }))()`)
  console.log(label, JSON.stringify(s))
  return s
}
const go = async (hash, wait) => {
  await b.evalJS(`location.hash = ${JSON.stringify(hash)}`)
  await sleep(wait)
}
const postMs = async () => b.evalJS(`(async () => {
  const t = performance.now()
  try {
    const r = await fetch('http://localhost:8088/admin/observe/attack/scenarios', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: ''
    })
    await r.text()
    return { status: r.status, ms: Math.round(performance.now() - t) }
  } catch (e) { return { error: String(e), ms: Math.round(performance.now() - t) } }
})()`)

const out = { maxCount: 0, cycles: [], maxPostMs: 0 }
const track = (s) => { out.maxCount = Math.max(out.maxCount, s.count) }

try {
  await b.goto(WEB + '/admin.html#/login', 3000)
  await b.evalJS(`(() => { sessionStorage.setItem('loginUsername', 'admin'); sessionStorage.setItem('role', '3'); return true })()`)

  // ① 攻击页 ↔ 账户页，3 次
  for (let i = 1; i <= 3; i += 1) {
    await go('#/admin/observe/attack-sim', 4500)
    const on = await snap('attack#' + i)
    track(on)
    const f1 = JSON.stringify(on.frames)
    await sleep(2200)
    const on2 = await snap('attack#' + i + '-2s')
    const moved = JSON.stringify(on2.frames) !== f1
    const p = await postMs()
    out.maxPostMs = Math.max(out.maxPostMs, p.ms || 0)
    await go('#/admin/accounts', 2000)
    const off = await snap('accounts#' + i)
    track(off)
    out.cycles.push({ i, onCount: on.count, offCount: off.count, framesMoved: moved, post: p })
  }

  // ② 指标 ↔ 攻击，3 次
  for (let i = 1; i <= 3; i += 1) {
    await go('#/admin/observe/metrics', 4000)
    const m1 = await snap('metrics#' + i)
    track(m1)
    const mf = JSON.stringify(m1.frames)
    await sleep(2200)
    const m2 = await snap('metrics#' + i + '-2s')
    const moved = JSON.stringify(m2.frames) !== mf
    await go('#/admin/observe/attack-sim', 4000)
    const a = await snap('attack-m#' + i)
    track(a)
    out.cycles.push({ i, page: 'metrics→attack', metricsCount: m1.count, attackCount: a.count, framesMoved: moved })
  }

  console.log('RESULT', JSON.stringify(out, null, 2))
} finally {
  await b.close()
}
