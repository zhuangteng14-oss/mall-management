// c3b7-replay-fidelity.mjs —— 块 7 · 2026-10-17「回放不是简陋方块」验收探针。
//
// 用户交付验收 ③：指标页和状态页在回放模式下，**真实图表在运行**（折线 / 环形 / 阶梯 / 直方图），
// 不是另一套静态方块。回放期间实时 SSE 已切断，图表绑定的是台账帧 `replaySession.*`。
//
// 场景：真后端 8088 + 真前端 8080。
//   ① 打开日志页（= 当时开着日志页的演练，台账才会落行）跑一轮 `orderBurst`；
//   ② 到攻击模拟器点这一批的「回放」（`startReplaySession` + 跳日志页）；
//   ③ **用 hash 切页**（不能 `goto` —— 整页刷新会丢掉模块级回放会话）；
//   ④ 两次采样回放时钟，必须前进；图表 canvas 必须有实际像素（不是白板），
//      且两次采样之间像素指纹变化（证明在逐帧重绘）。
//
// 产物：stdout JSON + `c3b7-replay-metrics.png` / `c3b7-replay-timeline.png`。
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

/** 画布指纹 + 非空像素比例。白板 canvas 的 both 会接近 0。 */
const CANVAS_STATS = `(() => {
  const cs = [...document.querySelectorAll('canvas')].filter((c) => c.width > 20 && c.height > 20)
  return cs.map((c) => {
    const ctx = c.getContext('2d')
    let data
    try { data = ctx.getImageData(0, 0, c.width, c.height).data } catch (e) { return { err: String(e) } }
    let nz = 0, sum = 0
    const colors = new Set()
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] > 0) {
        nz += 1
        const k = (data[i] >> 4) * 256 + (data[i + 1] >> 4) * 16 + (data[i + 2] >> 4)
        colors.add(k)
        sum = (sum * 31 + k) >>> 0
      }
    }
    return { w: c.width, h: c.height, nz, ratio: +(nz / (c.width * c.height)).toFixed(5), colors: colors.size, sum }
  })
})()`

const b = await open({ w: 1440, h: 900 })
let run = null
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => { try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
  })

  // ── ① 开着日志页跑一轮：台账落行 ───────────────────────────────────────────
  await b.goto(`${WEB}/admin.html?rp=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2000)
  const theme = await b.evalJS(`document.documentElement.getAttribute('data-theme')`)
  check('admin-shell-sentinel', theme === 'admin', theme)

  run = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-replay-fidelity-20261017',
    concurrency: '32',
    durationSeconds: '12',
    targetQps: '250',
    scenarios: JSON.stringify([{ key: 'orderBurst', weight: 100 }]),
  }).toString()).catch((e) => ({ err: String(e) }))
  const runRes = await run
  out.samples.run = { status: runRes.status, name: runRes.json && runRes.json.Name, ok: !!(runRes.json && (runRes.json.Success ?? runRes.json.success)) }
  await sleep(1200)

  // ── ② 攻击模拟器 → 展开最新一批 → 点「回放」────────────────────────────────
  // ⚠️ 回放按钮在**展开后的** `.a-ev-body` 里（`v-if="openId === r.Id"`），
  //    不先点行头，选择器一定是空的。
  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  await sleep(3000)
  const expanded = await b.evalJS(`(() => {
    const head = document.querySelector('ol.a-stream li.a-ev .a-ev-head')
    if (!head) return false
    head.click()
    return true
  })()`)
  await sleep(900)
  const rows = await b.evalJS(`(() => {
    const btns = [...document.querySelectorAll('.a-rp-in button.a-btn')]
    return btns.map((x, i) => ({ i, disabled: x.disabled, text: x.textContent.trim() }))
  })()`)
  out.samples.expanded = expanded
  out.samples.replayButtons = rows
  const idx = rows.findIndex((x) => !x.disabled)
  check('replay-entry-enabled', expanded && idx >= 0, rows)
  if (idx >= 0) {
    await b.evalJS(`(() => {
      const btns = [...document.querySelectorAll('.a-rp-in button.a-btn')]
      btns[${idx}].click()
      return true
    })()`)
  }

  // 等跳转到日志页 + 横幅出现（装载是一次几千行的 SELECT，给足 15 秒）。
  let active = false
  for (let i = 0; i < 60; i += 1) {
    await sleep(500)
    active = await b.evalJS(`location.hash.includes('/observe/logs') && !!document.querySelector('.a-rb')`)
    if (active) break
  }
  out.samples.bannerActive = active
  check('replay-session-active', active, await b.evalJS(`({ hash: location.hash, banner: !!document.querySelector('.a-rb') })`))

  // 回放时钟是真时间轴：本批 12 秒，一路放到底只需 ~12 秒。
  // ⇒ 每次切页前先**暂停**，到页上再继续采样，避免走到「已放完」后 cursor 停住。
  const toggleBtn = `(() => {
    const x = [...document.querySelectorAll('.a-rb-btn')].find((e) => !e.classList.contains('is-exit'))
    if (!x) return null
    const before = x.textContent.trim()
    x.click()
    return before
  })()`
  const pausedNow = () => b.evalJS(`!!document.querySelector('.a-rb-state.is-paused')`)

  await b.evalJS(toggleBtn) // 暂停
  await sleep(500)
  check('replay-can-pause', await pausedNow(), await b.evalJS(`(document.querySelector('.a-rb-state') || {}).textContent || ''`))

  // ── ③ 状态页（同一套高保真组件；先于指标页，离回放起点最近）────────────────
  await b.evalJS(`location.hash = '#/admin/observe/timeline'`)
  await sleep(1800)
  await b.evalJS(toggleBtn) // 继续
  await sleep(700)
  const flowDom = await b.evalJS(`(() => ({
    banner: !!document.querySelector('.a-rb'),
    hero: !!document.querySelector('.a-of-hero'),
    kpis: document.querySelectorAll('.a-of-kpi.is-static').length,
    ladder: document.querySelectorAll('.a-of-step.is-static').length,
    hist: document.querySelectorAll('.hist').length,
    replayBadges: document.querySelectorAll('.bc--replay').length,
    canvases: document.querySelectorAll('.a-of-hero canvas, .a-of-flow canvas, .hist canvas').length,
    cursor: (document.querySelector('.a-rb-prog b') || {}).textContent || '',
  }))()`)
  out.samples.flowDom = flowDom
  check('flow-replay-view', flowDom.banner && flowDom.hero && flowDom.kpis === 5 && flowDom.ladder >= 5, flowDom)
  check('flow-real-charts', flowDom.canvases >= 4 && flowDom.replayBadges >= 2 && flowDom.hist >= 1, flowDom)

  const f1 = await b.evalJS(CANVAS_STATS)
  const fc1 = await b.evalJS(`(document.querySelector('.a-rb-prog b') || {}).textContent || ''`)
  await sleep(900)
  const f2 = await b.evalJS(CANVAS_STATS)
  const fc2 = await b.evalJS(`(document.querySelector('.a-rb-prog b') || {}).textContent || ''`)
  out.samples.flowCanvas1 = f1
  out.samples.flowCanvas2 = f2
  out.samples.flowCursor = { fc1, fc2 }
  check('flow-canvas-not-blank', f1.filter((x) => x.ratio > 0.005 && x.colors > 5).length >= 2, f1)
  check('flow-canvas-redraws', f1.length === f2.length && f1.some((x, i) => x.sum !== f2[i].sum), { f1: f1.map((x) => x.sum), f2: f2.map((x) => x.sum) })
  check('flow-clock-advances', Number(fc2.replace(/,/g, '')) > Number(fc1.replace(/,/g, '')), { fc1, fc2 })
  await b.shot(EV + '3a4f-replay-timeline.png')
  await b.evalJS(toggleBtn) // 暂停
  await sleep(400)

  // ── ④ 指标页（hash 切页，保住模块级会话）──────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/metrics'`)
  await sleep(1800)
  await b.evalJS(toggleBtn) // 继续
  await sleep(700)

  const metricsDom = await b.evalJS(`(() => ({
    banner: !!document.querySelector('.a-rb'),
    replayHeader: !!document.querySelector('.a-status--rp'),
    note: !!document.querySelector('.mp-rp-warn'),
    replayBadges: document.querySelectorAll('.bc--replay').length,
    charts: document.querySelectorAll('.mp-chart').length,
    canvases: document.querySelectorAll('.mp-chart canvas').length,
    cursor: (document.querySelector('.a-rb-prog b') || {}).textContent || '',
  }))()`)
  out.samples.metricsDom = metricsDom
  check('metrics-replay-view', metricsDom.banner && metricsDom.replayHeader && metricsDom.note, metricsDom)
  check('metrics-real-charts', metricsDom.charts >= 2 && metricsDom.canvases >= 2 && metricsDom.replayBadges >= 2, metricsDom)

  const m1 = await b.evalJS(CANVAS_STATS)
  const cur1 = await b.evalJS(`(document.querySelector('.a-rb-prog b') || {}).textContent || ''`)
  await sleep(900)
  const m2 = await b.evalJS(CANVAS_STATS)
  const cur2 = await b.evalJS(`(document.querySelector('.a-rb-prog b') || {}).textContent || ''`)
  out.samples.metricsCanvas1 = m1
  out.samples.metricsCanvas2 = m2
  out.samples.metricsCursor = { cur1, cur2 }
  check('metrics-canvas-not-blank', m1.length >= 2 && m1.filter((x) => x.ratio > 0.005 && x.colors > 5).length >= 2, m1)
  check('metrics-canvas-redraws', m1.length === m2.length && m1.some((x, i) => x.sum !== m2[i].sum), { m1: m1.map((x) => x.sum), m2: m2.map((x) => x.sum) })
  check('metrics-clock-advances', Number(cur2.replace(/,/g, '')) > Number(cur1.replace(/,/g, '')), { cur1, cur2 })
  await b.shot(EV + '3a4f-replay-metrics.png')

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  try { await post('/admin/observe/attack/stop', '') } catch (e) { /* 收尾 */ }
  try { await run } catch (e) { /* 收尾 */ }
  await b.close()
}
