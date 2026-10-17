// c3b7-3a4d-backpressure.mjs —— 块 7 · 2026-10-17「动态背压 / 智能聚合」验收探针。
//
// 三段：
//   A. 渲染耐受度：在真日志页上注入 50 / 100 / 200 条，跑 10 拍（100ms 间隔），
//      量每拍 sync / Vue flush / 强制 Layout 的耗时、窗口 FPS、>50ms Long Task。
//      判据 = 「不产生 >50ms Long Task」的最大档位 ⇒ 写回 MAX_RENDER_PER_TICK。
//   B. 聚合单元：一次注入 500 条同类 + 1 条异类，验证 501 → 2（一条 ×500 聚合 + 一条单条）。
//   C. 真实 800 QPS：真后端 8088 开一轮 800 QPS / 20 秒的演练，日志页在**演练进行中**测
//      Long Task / FPS / DOM / 聚合行 / 入站限流行数 / 时间戳顺序，并截图。
//
// 产物：stdout JSON（调用方重定向为 docs/agent/evidence/c3-block7/3a4d-backpressure-report.txt）
//       + 3a4d-render-limit.png / 3a4d-agg-synthetic.png / 3a4d-800qps-logs.png
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
    signal: AbortSignal.timeout(180000),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) { /* 留原文 */ }
  return { status: res.status, json, text }
}

const SETUP = (n) => `(async () => {
  const el = document.querySelector('.ev-frame')
  const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
  if (!s) throw new Error('no setupState')
  ${n}
})()`

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

  // ────────────────────────────────────────────────────────────────────────
  // A. 渲染耐受度（合成批次；不 mock 任何请求 —— 这一段的取样对象就是渲染层本身）
  // ────────────────────────────────────────────────────────────────────────
  await b.goto(`${WEB}/admin.html#/admin/observe/logs`, 6000)
  await sleep(2500)
  await b.evalJS(SETUP(`
    // 停掉真流 + 冻结 100ms 冲刷：这一段只量「往视图里推 N 行」的成本。
    if (s.closeStream) s.closeStream()
    s.paused = true
    window.__rlState = { base: 5_000_000_000 + Math.floor(Math.random() * 1_000_000) * 10_000 }
  `))

  // 空闲基线 FPS（1 秒）
  out.samples.idleFps = await b.evalJS(SETUP(`
    let frames = 0
    let on = true
    const loop = () => { frames += 1; if (on) requestAnimationFrame(loop) }
    requestAnimationFrame(loop)
    await new Promise((r) => setTimeout(r, 1000))
    on = false
    return frames
  `))

  const sweep = []
  for (const n of [50, 100, 200]) {
    const r = await b.evalJS(SETUP(`
      s.W.normal.events = []
      s.seenSeq.clear()
      await new Promise((r) => s.nextTick(r))
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
      const list = document.querySelector('#ev-list')
      let frames = 0
      let on = true
      const loop = () => { frames += 1; if (on) requestAnimationFrame(loop) }
      requestAnimationFrame(loop)
      const lt = []
      const obs = new PerformanceObserver((l) => { for (const e of l.getEntries()) lt.push(Math.round(e.duration)) })
      obs.observe({ type: 'longtask' })
      const ticks = []
      const t0 = performance.now()
      for (let k = 0; k < 10; k++) {
        const batch = []
        for (let i = 0; i < ${n}; i++) {
          batch.push({
            seq: window.__rlState.base + k * 1000 + i,
            ts: Date.now() + i,
            kind: 'cache',
            severity: 'info',
            // ⚠️ 标题逐条不同 ⇒ 不让聚合生效，量的是**原始 N 行**的渲染成本
            title: '渲染压测-' + i,
            detail: 'probe', key: null, objectType: null, objectId: null, traceId: null,
            batchId: null, actor: null, durationUs: null, remaining: null, fields: null,
          })
        }
        const a = performance.now()
        s.absorbEvents(batch, 'normal')
        const syncEnd = performance.now()
        await new Promise((r) => s.nextTick(r))
        const tickEnd = performance.now()
        void list.offsetHeight            // 强制 Layout 落进这次测量
        const layoutEnd = performance.now()
        ticks.push({
          syncMs: +(syncEnd - a).toFixed(1),
          flushMs: +(tickEnd - syncEnd).toFixed(1),
          layoutMs: +(layoutEnd - tickEnd).toFixed(1),
          totalMs: +(layoutEnd - a).toFixed(1),
        })
        await new Promise((r) => setTimeout(r, 85))
      }
      const dur = performance.now() - t0
      on = false
      await new Promise((r) => setTimeout(r, 150))   // 等 longtask 条目投递
      obs.disconnect()
      const rows = [...document.querySelectorAll('#ev-list .log-line')]
      return {
        n: ${n},
        ticks,
        maxTickMs: Math.max(...ticks.map((t) => t.totalMs)),
        avgTickMs: +(ticks.reduce((x, t) => x + t.totalMs, 0) / ticks.length).toFixed(1),
        frames,
        durationMs: Math.round(dur),
        fps: +(frames / (dur / 1000)).toFixed(1),
        longTasks: lt,
        longTasksOver50: lt.filter((x) => x > 50).length,
        domRows: rows.length,
        eventsLen: s.events.length,
      }
    `))
    sweep.push(r)
  }
  out.samples.renderSweep = sweep
  // 阈值 = 不产生 >50ms Long Task 的最大档位（探针输出与代码常量必须一致）
  const passing = sweep.filter((x) => x.longTasksOver50 === 0).map((x) => x.n)
  out.samples.maxSafeRenderPerTick = passing.length ? Math.max(...passing) : 0
  await b.shot(EV + '3a4d-render-limit.png')

  // ────────────────────────────────────────────────────────────────────────
  // A2. 窗口化滞回：行数 201 → 200 必须**保持**窗口化。
  //     2026-10-17 首轮实测：没有滞回时，这一翻会把 200 行真实字段一次挂载 = 68ms Long Task。
  //     本段直接构造该边界（201 行开、掉到 200、再掉到 99 才关），把修复钉成硬判据。
  // ────────────────────────────────────────────────────────────────────────
  const hyst = await b.evalJS(SETUP(`
    const mk = (base, n) => {
      const arr = []
      for (let i = 0; i < n; i++) {
        arr.push({
          seq: base + i, ts: Date.now() + i, kind: 'cache', severity: 'info',
          title: '滞回-' + i, detail: 'hysteresis', key: null, objectType: null, objectId: null,
          traceId: '', batchId: null, actor: null, durationUs: 1, remaining: null, fields: null,
        })
      }
      return arr
    }
    const snapshot = () => ({
      rows: s.streamRows.length,
      virtual: s.virtualOn,
      dom: document.querySelectorAll('#ev-list .log-line').length,
    })
    const paint = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
    const lt = []
    const obs = new PerformanceObserver((l) => { for (const e of l.getEntries()) lt.push(Math.round(e.duration)) })
    obs.observe({ type: 'longtask' })
    const base = window.__rlState.base + 2_000_000
    s.W.normal.events = mk(base, 201)
    await new Promise((r) => s.nextTick(r))
    await paint()
    const at201 = snapshot()
    s.W.normal.events = mk(base + 1000, 200)
    await new Promise((r) => s.nextTick(r))
    await paint()
    const at200 = snapshot()
    s.W.normal.events = mk(base + 2000, 99)
    await new Promise((r) => s.nextTick(r))
    await paint()
    const at99 = snapshot()
    await new Promise((r) => setTimeout(r, 250))
    obs.disconnect()
    return { at201, at200, at99, longTasks: lt, longTasksOver50: lt.filter((x) => x > 50).length }
  `))
  out.samples.virtualHysteresis = hyst
  check('hysteresis-on-above-200',
    hyst.at201.rows > 200 && hyst.at201.virtual === true && hyst.at201.dom < 200, hyst.at201)
  check('hysteresis-holds-at-200-boundary',
    hyst.at200.rows === 200 && hyst.at200.virtual === true && hyst.at200.dom < 200, hyst.at200)
  check('hysteresis-off-only-when-small',
    hyst.at99.rows === 99 && hyst.at99.virtual === false, hyst.at99)
  check('hysteresis-no-long-task', hyst.longTasksOver50 === 0, { longTasks: hyst.longTasks })

  // ────────────────────────────────────────────────────────────────────────
  // B. 聚合单元：500 条同类 + 1 条异类 ⇒ 1 条 ×500 + 1 条单条
  // ────────────────────────────────────────────────────────────────────────
  const aggUnit = await b.evalJS(SETUP(`
    s.W.normal.events = []
    s.seenSeq.clear()
    await new Promise((r) => s.nextTick(r))
    const base = window.__rlState.base + 900_000
    const now = Date.now()
    const dups = []
    for (let i = 0; i < 500; i++) {
      dups.push({
        seq: base + i, ts: now + i, kind: 'cache', severity: 'info', title: '缓存命中',
        detail: 'protection=cache hit', key: 'goods:1', objectType: 'goods', objectId: '1',
        traceId: '', batchId: null, actor: null, durationUs: 120, remaining: null, fields: null,
      })
    }
    dups.push({
      seq: base + 500, ts: now + 600, kind: 'lock', severity: 'warn', title: '锁等待超时',
      detail: 'lock=goods:1 waitMs=3000', key: 'goods:1', objectType: 'goods', objectId: '1',
      traceId: '', batchId: null, actor: null, durationUs: 3_000_000, remaining: null, fields: null,
    })
    const before = 501
    s.absorbEvents(dups, 'normal')
    await new Promise((r) => s.nextTick(r))
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
    const ev = s.events
    return {
      before, after: ev.length,
      aggRows: ev.filter((e) => e.agg).length,
      plainRows: ev.filter((e) => !e.agg).length,
      aggCount: ev.filter((e) => e.agg).map((e) => e.aggCount),
      plainTitles: ev.filter((e) => !e.agg).map((e) => e.title),
      domRows: document.querySelectorAll('#ev-list .log-line').length,
      chips: [...document.querySelectorAll('#ev-list .agg-chip')].map((x) => x.innerText),
    }
  `))
  out.samples.aggUnit = aggUnit
  await b.shot(EV + '3a4d-agg-synthetic.png')
  check('agg-500-to-1', aggUnit.after === 2 && aggUnit.aggRows === 1 && aggUnit.plainRows === 1,
    aggUnit)
  check('agg-keeps-single-low-frequency', aggUnit.plainTitles.includes('锁等待超时'), aggUnit.plainTitles)

  // ────────────────────────────────────────────────────────────────────────
  // C. 真实 800 QPS（真后端 + 真前端；演练在背景跑，页面在**进行中**取样）
  // ────────────────────────────────────────────────────────────────────────
  // ⚠️ 必须带 query 强制**整页重载**：Phase A/B 在同一 URL 上关过流并置过 `paused=true`，
  //    只 goto 同 hash 的 URL 是 same-document 导航（不执行 onMounted）⇒ 采样窗口会读到
  //    Phase B 的残留 2 行，指标全绿但根本没接真流（本轮实测踩到，见报告 liveTicks 恒 2）。
  await b.goto(`${WEB}/admin.html?bp=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2500)
  const cfgRes = await fetch(API + '/admin/observe/attack/config', { signal: AbortSignal.timeout(8000) })
  const cfg = await cfgRes.json()
  const prot = (cfg && cfg.Protections) || []   // Result 是**顶层平铺**，载荷不在 .data 下
  out.samples.configBefore = prot
  if (!prot.includes('rateLimit')) {
    const next = [...new Set([...prot, 'rateLimit'])]
    const setRes = await post('/admin/observe/attack/config',
      new URLSearchParams({ protections: JSON.stringify(next) }).toString())
    out.samples.configSet = { status: setRes.status, json: setRes.json }
  }

  const runP = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-800qps-20261017',
    concurrency: '512',
    durationSeconds: '20',
    targetQps: '800',
    scenarios: JSON.stringify([{ key: 'orderBurst', weight: 80 }, { key: 'seckill', weight: 20 }]),
  }).toString())
  runP.catch(() => {})
  await sleep(3000)   // 让流量与 SSE 批次进入稳态

  await b.evalJS(`(() => {
    window.__bp = { start: performance.now(), lt: [], frames: 0 }
    window.__bp.obs = new PerformanceObserver((list) => {
      for (const e of list.getEntries()) window.__bp.lt.push({ d: Math.round(e.duration), t: Math.round(e.startTime - window.__bp.start) })
    })
    window.__bp.obs.observe({ type: 'longtask', buffered: false })
    const loop = () => { window.__bp.frames += 1; requestAnimationFrame(loop) }
    requestAnimationFrame(loop)
    return true
  })()`)

  const ticks = []
  for (let i = 1; i <= 6; i += 1) {
    const s = await b.evalJS(SETUP(`
      const rows = [...document.querySelectorAll('#ev-list .log-line')]
      // ⚠️ class=req 是 buildStream 给 trace 组插的「请求开始/结束」脚手架行（不是事件）：
      //    它们用 trace 的起止时刻，天然会落在相邻事件之间 ⇒ 时间序判据必须排除它们，
      //    否则测的是「脚手架和事件混排」，不是用户要的「事件时间戳不乱」。
      const evRows = rows.filter((li) => !li.classList.contains('req'))
      const tm = evRows.map((li) => (li.querySelector('.tm') || {}).innerText || '')
      // 标题里带「入站限流」的那几行就是后端按秒聚合的汇总帧（每窗一条），单独数出来。
      const rateLimit = evRows.filter((li) => li.innerText.includes('入站限流'))
      const plain = evRows.filter((li) => !li.querySelector('.agg-chip'))
      return {
        atMs: ${i * 1000},
        events: s.events.length,
        ranged: s.ranged.length,
        domRows: rows.length,
        chips: document.querySelectorAll('#ev-list .agg-chip').length,
        plainRows: plain.length,
        plainBusinessRows: plain.filter((li) => !li.innerText.includes('入站限流')).length,
        // 单条行的标题：本轮真实演练里业务组全是高频（每拍数百条）⇒ 唯一「只出现一次」的组
        // 就是后端按秒汇总的 protection|入站限流拦下。低频业务单条由 Phase B 的聚合单元单独证明。
        plainTitles: plain.map((li) => ((li.querySelector('.ms b') || {}).innerText || '').trim()).slice(0, 8),
        eventRows: evRows.length,
        rateLimitRows: rateLimit.length,
        rateLimitText: (rateLimit[0] && rateLimit[0].innerText.replace(/\\s+/g, ' ').slice(0, 200)) || null,
        tmFirst: tm[0] || null,
        tmLast: tm[tm.length - 1] || null,
        tmSorted: tm.every((x, j) => j === 0 || x >= tm[j - 1]),
        paused: s.paused,
        stats: { ...s.aggStats },
      }
    `))
    s.atMs = i * 1000
    ticks.push(s)
    if (i === 3) await b.shot(EV + '3a4d-800qps-logs.png')
    await sleep(1000)
  }
  out.samples.liveTicks = ticks

  const livePerf = await b.evalJS(`(() => {
    const p = window.__bp
    if (p.obs) p.obs.disconnect()
    const dur = performance.now() - p.start
    const d = p.lt.map((x) => x.d)
    return {
      durationMs: Math.round(dur),
      frames: p.frames,
      fps: +(p.frames / (dur / 1000)).toFixed(1),
      longTasks: d.length,
      longTasksOver50: d.filter((x) => x > 50).length,
      maxLongTask: d.length ? Math.max(...d) : 0,
      lt: p.lt.slice(0, 12),
    }
  })()`)
  out.samples.livePerf = livePerf

  // 冻结验证（演练仍在跑，流量持续）：① 点「暂停」② 手动上滚 —— 两者都只进池、不入视图。
  const freeze = await b.evalJS(SETUP(`
    const btn = document.querySelector('.ev-pause')
    if (!btn) return { ok: false, why: 'no .ev-pause button' }
    const snap = () => ({
      events: s.events.length,
      newest: s.events[0] ? s.events[0].seq : null,
      raw: s.rawBuf.normal.events.length,
      paused: s.paused,
      autoBottom: s.autoBottom,
      label: btn.innerText.trim(),
    })
    const before = snap()
    btn.click()
    await new Promise((r) => setTimeout(r, 1600))
    const during = snap()
    btn.click()
    await new Promise((r) => setTimeout(r, 1400))
    const after = snap()
    return { ok: true, before, during, after }
  `))
  out.samples.freeze = freeze
  check('pause-freezes-view', freeze.ok && freeze.during.paused === true
      && freeze.during.events === freeze.before.events && freeze.during.newest === freeze.before.newest,
    freeze)
  check('pause-keeps-buffering', freeze.ok && freeze.during.raw > 0, freeze.during)
  check('resume-flushes-buffer', freeze.ok && freeze.after.paused === false
      && freeze.after.newest !== freeze.during.newest, freeze.after)

  const scrollFreeze = await b.evalJS(SETUP(`
    const list = document.querySelector('#ev-list')
    const snap = () => ({
      events: s.events.length,
      newest: s.events[0] ? s.events[0].seq : null,
      raw: s.rawBuf.normal.events.length,
      autoBottom: s.autoBottom,
      paused: s.paused,
      label: (document.querySelector('.ev-pause') || {}).innerText,
    })
    const before = snap()
    list.scrollTop = 0
    list.dispatchEvent(new Event('scroll'))
    await new Promise((r) => setTimeout(r, 1200))
    const during = snap()
    list.scrollTop = list.scrollHeight
    list.dispatchEvent(new Event('scroll'))
    await new Promise((r) => setTimeout(r, 900))
    const after = snap()
    return { before, during, after, scrollHeight: list.scrollHeight, clientHeight: list.clientHeight }
  `))
  out.samples.scrollFreeze = scrollFreeze
  check('scroll-up-freezes-view', scrollFreeze.during.autoBottom === false
      && scrollFreeze.during.events === scrollFreeze.before.events
      && scrollFreeze.during.newest === scrollFreeze.before.newest, scrollFreeze)
  check('scroll-up-keeps-buffering', scrollFreeze.during.raw > 0, scrollFreeze.during)
  check('scroll-back-resumes', scrollFreeze.after.autoBottom === true
      && scrollFreeze.after.newest !== scrollFreeze.during.newest, scrollFreeze.after)

  const drill = await runP
  out.samples.drill = {
    status: drill.status,
    ok: !!(drill.json && drill.json.success),
    id: drill.json && drill.json.Id,
    total: drill.json && drill.json.TotalRequests,
    blocked: drill.json && drill.json.BlockedRequests,
    failed: drill.json && drill.json.FailedRequests,
    shed: drill.json && drill.json.ShedRequests,
    durationMs: drill.json && drill.json.DurationMs,
    raw: drill.text.slice(0, 400),
  }
  const avgQps = drill.json && drill.json.DurationMs
    ? +(drill.json.TotalRequests / (drill.json.DurationMs / 1000)).toFixed(1)
    : null
  out.samples.avgQps = avgQps

  const last = ticks[ticks.length - 1]
  const st = last.stats || {}
  check('live-no-long-task-over-50ms', livePerf.longTasksOver50 === 0,
    { longTasksOver50: livePerf.longTasksOver50, maxLongTask: livePerf.maxLongTask, lt: livePerf.lt })
  check('live-fps-healthy', livePerf.fps >= 50, { fps: livePerf.fps })
  check('live-view-capped-200', last.events <= 200, { events: last.events })
  check('live-dom-capped-200', last.domRows <= 200, { domRows: last.domRows })
  // ⚠️ 本轮首跑踩过的假绿：Phase A 关过流并置了 paused，Phase C 只 goto 同 hash ⇒ 页面根本没接真流，
  //    上面几条在「2 行残留」上全绿。这条钉住「采样窗口内真的在收帧」。
  check('live-receiving-real-frames', st.raw - ticks[0].stats.raw > 100,
    { firstRaw: ticks[0].stats.raw, lastRaw: st.raw, firstFlushes: ticks[0].stats.flushes, lastFlushes: st.flushes })
  check('live-aggregation-active', st.raw > st.view,
    { raw: st.raw, view: st.view, aggregated: st.aggregated, lastRaw: st.lastRaw, lastView: st.lastView })
  check('live-has-agg-chip', last.chips > 0, { chips: last.chips })
  // 判据：列表里**同时**有聚合行（chips>0）与单条行（plainRows>0）。
  // ⚠️ 实测（2026-10-17，800 QPS）：业务组每拍都是数百条 ⇒ 全被合并；唯一「每拍只 1 条」的组
  //    是后端按秒汇总的 protection|入站限流拦下 —— 它正确地保持单条（这正是用户要的「限流只一条」）。
  //    「低频业务不被合并」的对照组由 Phase B（500 同类 + 1 条锁超时 ⇒ 1 agg + 1 plain）证明。
  check('live-has-plain-row', last.plainRows > 0,
    { plainRows: last.plainRows, plainTitles: last.plainTitles, plainBusinessRows: last.plainBusinessRows, chips: last.chips })
  // 判据不是「0 行」：后端按秒聚合 ⇒ **每秒 1 行**是正确行为（每行自己写着「该秒内拦下 N 次请求」）。
  // 采样 6 秒 + 首屏快照里可能带进前几秒的 1~3 行 ⇒ 12 行上限足够判「没有逐请求刷屏」。
  check('live-rate-limit-not-flooding', last.rateLimitRows <= 12,
    { rateLimitRows: last.rateLimitRows, text: last.rateLimitText })
  check('live-rate-limit-is-second-summary',
    ticks.some((t) => t.rateLimitText && t.rateLimitText.includes('次请求')),
    ticks.map((t) => ({ at: t.atMs, rows: t.rateLimitRows, text: t.rateLimitText })))
  check('live-timestamps-sorted', ticks.every((t) => t.tmSorted),
    ticks.map((t) => ({ at: t.atMs, first: t.tmFirst, last: t.tmLast, sorted: t.tmSorted })))
  check('live-drill-really-800qps', avgQps != null && avgQps >= 700,
    { avgQps, total: out.samples.drill.total, durationMs: out.samples.drill.durationMs })
} catch (e) {
  out.error = String((e && e.stack) || e)
  fail.push('probe-exception: ' + out.error)
} finally {
  await b.close()
}

out.fail = fail
console.log(JSON.stringify(out, null, 2))
process.exit(fail.length ? 1 : 0)
