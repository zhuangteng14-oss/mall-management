// c3b7-empty-axis.mjs —— 块 7 · 2026-10-17「没数据也不能变白板」验收探针。
//
// 用户 P0：图表没数据就变白板 ——
//   · 指标页趋势图 / 状态页趋势图：X 轴必须始终保留最近 12 / 24 小时完整刻度；
//   · 状态页耗时分布图：X 轴桶边界始终保留；
//   · 空态只在画布上盖一层胶囊提示，坐标轴与网格线不能塌。
//
// 做法：真页面 + 真 ECharts，**确定性构造 n=0**（边界必须强制构造，别等真流量碰巧走到）：
//   · 指标页：关流 → 清空滚动窗口 `rows`；
//   · 状态页：清空 `insight` 与实时尾点 `liveTail`。
// 然后直接读图表组件绑定的 option（类目轴长度），并用画布像素比例证明「不是白板」。
//
// 产物：stdout JSON + `3a4f-empty-metrics.png` / `3a4f-empty-timeline.png`。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const out = { at: new Date().toISOString(), checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
}

const CANVAS_NZ = `(() => {
  const cs = [...document.querySelectorAll('canvas')].filter((c) => c.width > 20 && c.height > 20)
  return cs.map((c) => {
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
    let nz = 0
    for (let i = 3; i < d.length; i += 4) if (d[i] > 0) nz += 1
    return { w: c.width, h: c.height, ratio: +(nz / (c.width * c.height)).toFixed(5) }
  })
})()`

/** 读某个图表组件的 option（图表组件根 → BaseChart → parent = LineChart/BarChart/HistogramChart）。 */
const CHART_OPTIONS = (rootSel) => `(() => {
  const els = [...document.querySelectorAll(${JSON.stringify(rootSel)})]
  return els.map((el) => {
    const bc = el.classList && el.classList.contains('bc') ? el : el.querySelector('.bc')
    const base = bc && bc.__vueParentComponent
    const p = base && base.parent
    const st = p && p.setupState
    // setupState 对 ref/computed 是解包后的值（不是 ref 对象）；两种都兼容。
    const raw = st && st.option
    const opt = raw && (raw.value !== undefined ? raw.value : raw)
    if (!opt) return null
    const xa = Array.isArray(opt.xAxis) ? opt.xAxis[0] : opt.xAxis
    const cats = (xa && xa.data) || []
    return {
      name: (p.type && (p.type.__name || p.type.name)) || '',
      xLen: cats.length,
      first: cats[0], last: cats[cats.length - 1],
      series: (opt.series || []).length,
    }
  }).filter(Boolean)
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

  // ── ① 指标页：关流 + 清窗口 ────────────────────────────────────────────────
  await b.goto(`${WEB}/admin.html?empty=${Date.now()}#/admin/observe/metrics`, 6500)
  await sleep(2500)
  check('mix-metrics-admin', (await b.evalJS(`document.documentElement.getAttribute('data-theme')`)) === 'admin')

  const forcedM = await b.evalJS(`(() => {
    const el = document.querySelector('.a-mp')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    if (!s) return { ok: false, why: 'no setupState' }
    try { s.closeStream() } catch (e) {}
    if (Array.isArray(s.rows)) s.rows.splice(0)
    return { ok: true, rows: Array.isArray(s.rows) ? s.rows.length : 'not-array' }
  })()`)
  out.samples.forcedMetrics = forcedM
  check('metrics-window-forced-empty', forcedM.ok && forcedM.rows === 0, forcedM)
  await sleep(700)

  const xLabels = await b.evalJS(`(() => {
    const el = document.querySelector('.a-mp')
    const s = el.__vueParentComponent.setupState
    const raw = s.xLabels
    const x = raw && (raw.value !== undefined ? raw.value : raw)
    return { len: x ? x.length : null, first: x && x[0], last: x && x[x.length - 1] }
  })()`)
  out.samples.metricsXLabels = xLabels
  check('metrics-x-axis-60-ticks', xLabels.len === 60, xLabels)

  const mOpts = await b.evalJS(CHART_OPTIONS('.mp-chart'))
  out.samples.metricsOptions = mOpts
  check('metrics-chart-axis-kept', mOpts.length >= 2 && mOpts.some((o) => o.xLen === 60), mOpts)

  const mPix = await b.evalJS(CANVAS_NZ)
  out.samples.metricsPixels = mPix
  check('metrics-canvas-still-drawn', mPix.length >= 2 && mPix.filter((x) => x.ratio > 0.0005).length >= 2, mPix)

  const mEmpty = await b.evalJS(`(() => {
    const e = document.querySelector('.mp-chart .bc-state--overlay')
    return { overlay: !!e, text: e ? e.innerText.replace(/\\s+/g, ' ').trim() : '' }
  })()`)
  out.samples.metricsEmpty = mEmpty
  check('metrics-empty-pill-transparent', mEmpty.overlay, mEmpty)
  await b.shot(EV + '3a4f-empty-metrics.png')

  // ── ② 状态页：清 insight + 实时尾点 ───────────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/timeline'`)
  await sleep(2800)
  const forcedF = await b.evalJS(`(() => {
    const el = document.querySelector('.a-of')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    if (!s) return { ok: false, why: 'no setupState' }
    s.insight = null
    if (Array.isArray(s.liveTail)) s.liveTail.splice(0)
    return { ok: true }
  })()`)
  out.samples.forcedFlow = forcedF
  check('flow-insight-forced-empty', forcedF.ok, forcedF)
  await sleep(700)

  const trendOpts = await b.evalJS(CHART_OPTIONS('.a-of-trend .bc'))
  out.samples.trendOptions = trendOpts
  check('flow-trend-25-hour-ticks', trendOpts.length === 1 && trendOpts[0].xLen === 25, trendOpts)

  const histOpts = await b.evalJS(CHART_OPTIONS('.hist .bc'))
  out.samples.histOptions = histOpts
  check('flow-duration-buckets-kept', histOpts.length >= 3 && histOpts.every((o) => o.xLen >= 5), histOpts)

  const fPix = await b.evalJS(CANVAS_NZ)
  out.samples.flowPixels = fPix
  check('flow-canvas-still-drawn', fPix.filter((x) => x.ratio > 0.0005).length >= 4, fPix)

  const fEmpty = await b.evalJS(`(() => {
    const es = [...document.querySelectorAll('.hist .bc-state--overlay')]
    return { n: es.length, texts: es.map((e) => e.innerText.replace(/\\s+/g, ' ').trim()).slice(0, 3) }
  })()`)
  out.samples.flowEmpty = fEmpty
  check('flow-empty-pill-on-histograms', fEmpty.n >= 3, fEmpty)
  await b.shot(EV + '3a4f-empty-timeline.png')
  await b.evalJS(`(() => { const h = document.querySelector('.hist'); if (h) h.scrollIntoView({ block: 'center' }); return true })()`)
  await sleep(500)
  await b.shot(EV + '3a4f-empty-timeline-hist.png')

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  await b.close()
}
