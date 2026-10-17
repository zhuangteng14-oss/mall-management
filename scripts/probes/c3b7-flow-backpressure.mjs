// c3b7-flow-backpressure.mjs —— 块 7 · 2026-10-17「压测瞬间数千条 move 不卡死」验收探针。
//
// 用户 P0（流轨第二条）：压测瞬间数千条事件时，前端必须做背压 ——
//   渲染队列限长 / 抽样，不能把主线程写死。
//
// 判据（确定性构造，不等真实流量碰运气）：
//   ① 单帧 3,000 条 move：`capturedTotal` **全量 +3000**（账不能压），但
//      `recentMoves` ≤ 300、主链粒子 ≤ 8、取消粒子 ≤ 8、DOM 粒子 ≤ 8（只压渲染）；
//   ② 单帧处理耗时 < 50ms；
//   ③ 连续 10 帧 × 2,000 条（模拟 20,000 条集中到达）：无 >50ms Long Task、
//      每帧处理耗时仍 < 50ms、累计条数 = 23,000（全量记账）。
//
// ⚠️ 这是渲染层探针：先 `closeStream()` 再直接调页面自己的 `onFrame` —— 不 mock 后端，
//    也不改业务代码；验的就是 `absorbMoves` 那一段。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const out = { at: new Date().toISOString(), checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
}

const SETUP = (body) => `(async () => {
  const el = document.querySelector('.a-of')
  const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
  if (!s) throw new Error('no setupState')
  ${body}
})()`

const b = await open({ w: 1440, h: 900 })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => { try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
  })
  await b.goto(`${WEB}/admin.html?bp=${Date.now()}#/admin/observe/timeline`, 6000)
  await sleep(2500)
  check('admin-shell-sentinel', (await b.evalJS(`document.documentElement.getAttribute('data-theme')`)) === 'admin')

  // 停真流，避免它在测量窗口里插帧。
  const ready = await b.evalJS(SETUP(`
    if (typeof s.closeStream === 'function') s.closeStream()
    const c = s.counts || {}
    window.__bpBase = {
      captured: Number(s.capturedTotal) || 0,
      counts: {
        waitPay: Number(c.waitPay) || 0, waitShip: Number(c.waitShip) || 0,
        waitReceive: Number(c.waitReceive) || 0, completed: Number(c.completed) || 0,
        cancelled: Number(c.cancelled) || 0,
      },
    }
    return window.__bpBase
  `))
  out.samples.base = ready

  // ── ① 单帧 3,000 条（其中 10% 取消）────────────────────────────────────────
  const one = await b.evalJS(SETUP(`
    const base = window.__bpBase
    const now = Date.now()
    const moves = []
    for (let i = 0; i < 3000; i += 1) {
      const cancel = i % 10 === 0
      moves.push({
        orderId: 'BP-' + i,
        // ⚠️ 非取消必须给**正向边**（CREATED → PAID），否则主链粒子不会生成；
        //    取消里再拆一半从「待发货」出发，两条弧都走得到。
        from: cancel && i % 20 === 0 ? 'PAID' : 'CREATED',
        to: cancel ? 'CANCELLED' : 'PAID',
        ts: now + i,
        batchId: 'BP-BATCH',
      })
    }
    const before = Number(s.capturedTotal) || 0
    const t0 = performance.now()
    s.onFrame({ seq: 9001, ts: now, counts: base.counts, batchCounts: null, orders: [], moves })
    const elapsed = performance.now() - t0
    await new Promise((r) => requestAnimationFrame(() => r()))
    return {
      via: 'synthetic',
      capturedDelta: (Number(s.capturedTotal) || 0) - before,
      recentMoves: (s.recentMoves || []).length,
      laneParticles: (s.laneParticles || []).length,
      cancelParticles: (s.cancelParticles || []).length,
      domParticles: document.querySelectorAll('.a-of-particle').length,
      domCancel: document.querySelectorAll('.a-of-cancel-particle').length,
      elapsedMs: +elapsed.toFixed(2),
    }
  `))
  out.samples.single3000 = one
  check('single-frame-accounts-all', one.capturedDelta === 3000, one)
  // ⚠️ 粒子有 1.4–1.6 秒的存活期，跨帧会叠加 ⇒ 单帧看**新增预算**（≤8），
  //    全局看两条例线的**数组上限**（主链 10 / 取消 8，DOM 合计 ≤18）。
  check('single-frame-render-sampled',
    one.recentMoves <= 300 && one.laneParticles <= 10 && one.cancelParticles <= 8
      && one.laneParticles + one.cancelParticles <= 8
      && one.domParticles <= 10 && one.domCancel <= 8
      && one.domParticles + one.domCancel <= 18, one)
  check('single-frame-under-50ms', one.elapsedMs < 50, one)

  // ── ② 连续 10 帧 × 2,000 条 ────────────────────────────────────────────────
  const burst = await b.evalJS(SETUP(`
    const base = window.__bpBase
    const before = Number(s.capturedTotal) || 0
    const obs = []
    const po = new PerformanceObserver((list) => {
      for (const e of list.getEntries()) obs.push({ start: e.startTime, dur: e.duration })
    })
    try { po.observe({ type: 'longtask', buffered: false }) } catch (e) {}
    const per = []
    let maxDom = 0
    const now = Date.now()
    for (let f = 0; f < 10; f += 1) {
      const moves = []
      for (let i = 0; i < 2000; i += 1) {
        const k = f * 2000 + i
        const cancel = k % 10 === 0
        moves.push({
          orderId: 'BQ-' + k,
          from: cancel && k % 20 === 0 ? 'PAID' : 'CREATED',
          to: cancel ? 'CANCELLED' : 'PAID',
          ts: now + k,
          batchId: 'BQ-BATCH',
        })
      }
      const t0 = performance.now()
      s.onFrame({ seq: 9100 + f, ts: now + f * 16, counts: base.counts, batchCounts: null, orders: [], moves })
      per.push(+(performance.now() - t0).toFixed(2))
      await new Promise((r) => requestAnimationFrame(() => r()))
      maxDom = Math.max(maxDom,
        document.querySelectorAll('.a-of-particle').length
          + document.querySelectorAll('.a-of-cancel-particle').length)
    }
    await new Promise((r) => setTimeout(r, 400))
    try { po.disconnect() } catch (e) {}
    return {
      capturedDelta: (Number(s.capturedTotal) || 0) - before,
      perFrameMs: per,
      maxPerFrameMs: Math.max(...per),
      longTasks: obs,
      longTaskOver50: obs.filter((x) => x.dur > 50).length,
      maxDomParticles: maxDom,
    }
  `))
  out.samples.burst10x2000 = burst
  check('burst-accounts-all', burst.capturedDelta === 20000, { delta: burst.capturedDelta })
  check('burst-no-long-task', burst.longTaskOver50 === 0, burst.longTasks)
  check('burst-per-frame-under-50ms', burst.maxPerFrameMs < 50, burst.perFrameMs)
  check('burst-dom-particles-capped', burst.maxDomParticles <= 18, burst.maxDomParticles)
  check('burst-lane-particles-fly', burst.maxDomParticles > 0, burst.maxDomParticles)

  await b.shot(EV + '3a4f-flow-backpressure.png')

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  await b.close()
}
