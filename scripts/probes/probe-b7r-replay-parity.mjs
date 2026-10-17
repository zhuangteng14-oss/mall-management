// probe-b7r-replay-parity.mjs —— 块 7「回放架构与数据口径彻底重构」的真后端实测。
//
// 用户 2026-10-17 指令：
//   ① 三页顶部都能直接选历史批次回放（不再只能从攻击页进）；
//   ② 回放复用高保真组件（LineChart / DonutChart / BarChart / HeatmapChart / 流轨）；
//   ③ 回放时钟用台账原时间戳（不得是 NOW / 墙钟），0.25x / 0.5x 慢速可用；
//   ④ 「重新开始」进度归零重播；
//   ⑤ 回放期间不飘「MQ 未连接」误报。
//
// 跑法：① 后端 dev profile 在 8088 ② `npm run serve`（8080）
//       ③ `node scripts/probes/probe-b7r-replay-parity.mjs`
// ⚠️ **不拦截 Fetch**：本探针打真后端 + 真库（批次 8fdb21b7…，35204 条台账）。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const BATCH = '8fdb21b7-40e1-4c98-8698-a7970235e7b8'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const SHOT_METRICS = `${EV}b7r-metrics-replay.png`
const SHOT_TIMELINE = `${EV}b7r-timeline-replay.png`
const SHOT_TIMELINE_TOP = `${EV}b7r-timeline-replay-top.png`
const SHOT_LOGS = `${EV}b7r-logs-replay.png`
const FRAMES = 8
const FRAME_DIR = `${EV}b7r-frames/`

const R = report('块 7 · 回放架构 / 时间口径 / 顶部入口（真后端 8088，批次 8fdb21b7…）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const b = await open({ w: 1440, h: 1000 })

await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {}
    window.__errs = []
    const oe = console.error.bind(console)
    console.error = (...a) => { try { window.__errs.push(a.map(String).join(' ')) } catch (e) {} ; oe(...a) }
    window.addEventListener('unhandledrejection', (e) => { try { window.__errs.push('unhandledrejection: ' + e.reason) } catch (x) {} })
  })()`,
})

async function waitFor(expr, ms = 20000) {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    const v = await b.evalJS(expr).catch(() => false)
    if (v) return v
    await sleep(200)
  }
  return false
}

async function goto(hash, rootSel) {
  await b.evalJS(`location.hash = ${JSON.stringify(hash)}`)
  const mounted = await waitFor(`!!document.querySelector(${JSON.stringify(rootSel)})`, 15000)
  await sleep(500)
  return mounted
}

/** banner 里的读数：进度 / 时钟 / 状态。 */
async function bannerState() {
  return b.evalJS(`(() => {
    const el = document.querySelector('.a-rb:not(.a-rb--idle)')
    if (!el) return null
    const t = el.innerText.replace(/\\s+/g, ' ').trim()
    const m = t.match(/回放时钟\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})/)
    const p = t.match(/进度\\s*([\\d,]+)\\s*\\/\\s*([\\d,]+)/)
    return {
      text: t,
      clock: m ? m[1] : '',
      cursor: p ? Number(p[1].replace(/,/g, '')) : 0,
      count: p ? Number(p[2].replace(/,/g, '')) : 0,
      speeds: [...el.querySelectorAll('.a-rb-sp')].map((x) => x.innerText.trim()),
      restart: !!([...el.querySelectorAll('.a-rb-btn')].find((x) => x.innerText.includes('重新开始'))),
      activeSpeed: (el.querySelector('.a-rb-sp.on') || {}).innerText || '',
    }
  })()`)
}

const clockMs = (hms) => {
  const m = String(hms || '').match(/^(\d{2}):(\d{2}):(\d{2})\.(\d{3})$/)
  return m ? ((+m[1] * 60 + +m[2]) * 60 + +m[3]) * 1000 + +m[4] : 0
}

try {
  // ══════════════════════════════════════════════════════════════════════
  // 一、三页顶部都有「选择历史批次」入口（未回放时常驻）
  // ══════════════════════════════════════════════════════════════════════
  for (const [hash, root, name] of [
    ['#/admin/observe/metrics', '.a-mp', '指标页'],
    ['#/admin/observe/timeline', '.a-of', '状态页'],
    ['#/admin/observe/logs', '.a-el', '日志页'],
  ]) {
    await b.send('Page.navigate', {
      url: name === '指标页'
        ? `${BASE}/admin.html${hash}`
        : `${BASE}/admin.html${hash}`,
    }).catch(() => {})
    // 首次导航用 Page.navigate；之后同页 hash 切换即可（会话是模块级单例）。
    const mounted = await waitFor(`!!document.querySelector(${JSON.stringify(root)})`, 20000)
    ok(`① ${name}挂载`, mounted === true)
    const entry = await waitFor(`(() => {
      const sel = document.querySelector('#a-rb-batch')
      if (!sel || sel.options.length < 2) return null
      return { options: sel.options.length, hint: (document.querySelector('.a-rb-hint') || {}).innerText || '' }
    })()`, 15000)
    ok(`① ${name}顶部有「选择历史批次」下拉（真数据 ≥ 2 个选项，含占位）`,
      !!entry && entry.options >= 2, entry)
    await sleep(400)
  }

  // 以上三次 Page.navigate 会整页重载 ⇒ 回到指标页，从顶部入口真正启动一次回放。
  await b.send('Page.navigate', { url: `${BASE}/admin.html#/admin/observe/metrics` })
  await waitFor(`!!document.querySelector('.a-mp #a-rb-batch')`, 20000)
  await sleep(600)

  const picked = await b.evalJS(`(() => {
    const sel = document.querySelector('#a-rb-batch')
    if (!sel || sel.options.length < 2) return null
    // 按 BatchId 精确选：演练记录按时间倒序，新增一批就会把 options[1] 换成别的批次。
    const opt = [...sel.options].find((o) => o.value === '${BATCH}') || sel.options[1]
    sel.value = opt.value
    sel.dispatchEvent(new Event('change', { bubbles: true }))
    return { value: opt.value, label: opt.textContent.trim() }
  })()`)
  ok('② 选中目标历史批次（8fdb21b7…，带 BatchId）', picked && picked.value === BATCH, picked)

  const clicked = await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-rb-pick .a-rb-btn')].find((x) => x.innerText.trim() === '回放')
    if (!btn) return false
    btn.click()
    return true
  })()`)
  ok('② 顶部〔回放〕按钮可点', clicked === true)

  const started = await waitFor(`(() => {
    const el = document.querySelector('.a-rb:not(.a-rb--idle)')
    return !!el && /正在回放演练记录/.test(el.innerText)
  })()`, 30000)
  ok('② 回放会话启动（横幅切成「正在回放」）', started === true)

  // 等回放时钟吐出足够事件（35k 条 1:1 播放，不是一口气铺满）
  const flowing = await waitFor(`(() => {
    const t = (document.querySelector('.a-rb:not(.a-rb--idle)') || {}).innerText || ''
    const m = t.match(/进度\\s*([\\d,]+)/)
    return m && Number(m[1].replace(/,/g, '')) > 400
  })()`, 30000)
  ok('② 回放时钟在逐条吐出（进度 > 400）', flowing === true)

  const loaded = await bannerState()
  ok('② 整批载入（不是后端缺省 5,000 截断）：进度分母 = 35,204',
    !!loaded && loaded.count === 35204, loaded && { cursor: loaded.cursor, count: loaded.count })

  // ══════════════════════════════════════════════════════════════════════
  // 三、时间口径：台账原始时间戳（23:41:12.958 ~ 23:42:26.570），不是墙钟
  // ══════════════════════════════════════════════════════════════════════
  const s1 = await bannerState()
  const clock0 = clockMs(s1 && s1.clock)
  ok('③ 回放时钟是台账原时间（23:41/23:42 段），不是当前墙钟',
    /^23:4[12]:/.test((s1 && s1.clock) || ''), s1 && s1.clock)
  ok('③ 时钟不是「补 Z」造成的 +8 偏移（不得出现 07:4x）',
    !/^0[78]:/.test((s1 && s1.clock) || ''), s1 && s1.clock)

  // 1x 下 2.4 秒墙钟应推进约 2.4 秒日志时间
  await sleep(2400)
  const s2 = await bannerState()
  const adv1 = clockMs(s2 && s2.clock) - clock0
  ok('③ 1x：2.4 秒墙钟 ≈ 推进 2.4 秒日志时间（真实时间差值播放）',
    adv1 >= 1800 && adv1 <= 3000, { adv1, from: s1 && s1.clock, to: s2 && s2.clock })

  ok('③ 慢速档 0.25x / 0.5x 在 1x 旁', !!s1 && ['0.25x', '0.5x'].every((x) => s1.speeds.includes(x)), s1 && s1.speeds)
  ok('③ 倍速档共 6 档（0.25/0.5/1/2/5/10）', !!s1 && s1.speeds.length === 6, s1 && s1.speeds)

  // 切 0.25x：进度不重置，时钟推进变慢
  const beforeSlow = await bannerState()
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-rb-sp')].find((e) => e.innerText.trim() === '0.25x'); if (x) x.click() })()`)
  await sleep(300)
  const slowOn = await b.evalJS(`(document.querySelector('.a-rb-sp.on') || {}).innerText || ''`)
  const c0 = clockMs((await bannerState()).clock)
  await sleep(2600)
  const c1 = clockMs((await bannerState()).clock)
  const advSlow = c1 - c0
  const afterSlow = await bannerState()
  ok('③ 切 0.25x 后档位高亮、进度继续（不重置）',
    String(slowOn).trim() === '0.25x' && afterSlow.cursor >= beforeSlow.cursor, { slowOn, before: beforeSlow.cursor, after: afterSlow.cursor })
  ok('③ 0.25x：2.6 秒墙钟只推进约 0.65 秒日志时间（慢放生效）',
    advSlow >= 250 && advSlow <= 1400 && advSlow < adv1, { advSlow, adv1 })

  // 「重新开始」：进度归零、时钟回到本批第一条附近
  const beforeRestart = await bannerState()
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-rb-btn')].find((e) => e.innerText.includes('重新开始')); if (x) x.click() })()`)
  await sleep(350)
  const afterRestart = await bannerState()
  ok('④「重新开始」把进度打回 0 附近（重播而非继续）',
    afterRestart.cursor < beforeRestart.cursor && afterRestart.cursor < 3000,
    { before: beforeRestart.cursor, after: afterRestart.cursor })
  ok('④「重新开始」后回放时钟回到本批首条（23:41:12 附近）',
    /^23:41:(1[2-9]|[2-5]\d)/.test(afterRestart.clock), afterRestart.clock)

  // 回到 1x，方便后面截高保真图
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-rb-sp')].find((e) => e.innerText.trim() === '1x'); if (x) x.click() })()`)
  await sleep(2000)

  // ══════════════════════════════════════════════════════════════════════
  // 一、指标页：高保真组件 + is-replay 角标（不是简化列表）
  // ══════════════════════════════════════════════════════════════════════
  const metricCharts = await b.evalJS(`(() => {
    const charts = [...document.querySelectorAll('.bc--replay')]
    const badges = [...document.querySelectorAll('.bc-replay')]
    return {
      charts: charts.length,
      badges: badges.length,
      types: charts.map((c) => c.querySelector('canvas') ? 'canvas' : 'other'),
      mqWarn: /MQ 未连接/.test(document.body.innerText),
    }
  })()`)
  ok('① 指标页回放复用 ≥ 4 个高保真图表（QPS/失败/耗时/种类/热力）', metricCharts.charts >= 4, metricCharts.charts)
  ok('① 每个图表都带「回放」角标（is-replay 真的传到了 BaseChart）',
    metricCharts.badges === metricCharts.charts && metricCharts.badges >= 4, { charts: metricCharts.charts, badges: metricCharts.badges })
  ok('① 指标页没有「MQ 未连接」误报', metricCharts.mqWarn === false)
  await b.shot(SHOT_METRICS)

  // ══════════════════════════════════════════════════════════════════════
  // 一、状态页：同源流轨 / KPI / 环形图 / 趋势线 + 回放 QPS
  // ══════════════════════════════════════════════════════════════════════
  const mountedTl = await goto('#/admin/observe/timeline', '.a-of')
  ok('① 切到状态页后回放会话仍在（模块级共享，不重载）', mountedTl === true)
  await waitFor(`!!document.querySelector('.a-of-kpis')`, 10000)
  await sleep(2500)

  const tl = await b.evalJS(`(() => {
    const kpis = [...document.querySelectorAll('.a-of-kpi')].map((e) => e.innerText.replace(/\\s+/g, ' ').trim())
    const charts = [...document.querySelectorAll('.bc--replay')]
    const tracks = [...document.querySelectorAll('.a-of-lane-track')]
    const moves = [...document.querySelectorAll('.a-of-rpm-t')].map((e) => e.innerText.trim())
    const ladder = document.body.innerText
    return {
      kpis,
      kpiCount: kpis.length,
      charts: charts.length,
      badges: document.querySelectorAll('.bc-replay').length,
      tracks: tracks.length,
      onTracks: tracks.filter((e) => e.classList.contains('is-on')).length,
      moves: moves.slice(0, 12),
      moveCount: moves.length,
      hasInflow: /流入/.test(ladder),
      hasOutflow: /流出/.test(ladder),
      hasQps: /\\/s/.test(ladder),
      hasBatchLabel: /批量取消/.test(ladder),
    }
  })()`)
  ok('① 状态页回放 5 个状态 KPI 都在', tl.kpiCount === 5, tl.kpiCount)
  ok('① 状态页回放复用趋势线 + 环形图等高保真图表（≥ 2，均带角标）',
    tl.charts >= 2 && tl.badges === tl.charts, { charts: tl.charts, badges: tl.badges })
  ok('① 回放流轨是同一套拓扑（主链 3 段轨道）', tl.tracks === 3, tl.tracks)
  ok('③ 回放明细时间戳 = 台账原时间（23:41/23:42），不是墙钟',
    tl.moveCount > 0 && tl.moves.every((t) => /^23:4[12]:/.test(t)), tl.moves)
  ok('三、流入/流出 QPS 都在（不是只算新建、也不是全 0）', tl.hasInflow && tl.hasOutflow && tl.hasQps,
    { inflow: tl.hasInflow, outflow: tl.hasOutflow, qps: tl.hasQps })

  // 先把流轨滚进视口再量粒子：headless 下离屏内容的 CSS 动画可能被节流，
  // 量不到「正在飞」的帧（实测同一批次两次成功、两次量不到）。用户也是看着轨道的。
  await b.evalJS(`(document.querySelector('.a-of-lane') || document.body).scrollIntoView({ block: 'center' })`)
  await sleep(700)

  // 流轨粒子：证明「同一段轨道上，飞得越久的位置越靠右」。
  // ⚠️ 不跟踪单颗 DOM 跨时间：回放页每帧重建 v-for 的粒子节点（实测 320ms 后 isConnected=false，
  //    节点被替换、动画 currentTime 归 0），跟踪引用会量出假反向。改为**同一瞬间**取同一段轨道上
  //    所有正在飞的粒子，按动画进度排序后比较左右位置 —— 方向是集合性质，不怕节点重建。
  let dir = null
  const pTry = { rounds: 0, samples: [] }
  for (let i = 0; i < 20 && !dir; i++) {
    pTry.rounds++
    const m = await b.evalJS(`(() => {
      const groups = new Map()
      for (const w of document.querySelectorAll('.a-of-particle-wrap')) {
        const p = w.querySelector('.a-of-particle')
        const a = p && p.getAnimations ? p.getAnimations()[0] : null
        if (!a || a.currentTime < 30) continue
        const key = w.style.left + '|' + w.style.width
        if (!groups.has(key)) groups.set(key, [])
        groups.get(key).push({
          t: a.currentTime,
          left: p.getBoundingClientRect().left,
          anim: getComputedStyle(p).animationName,
        })
      }
      // 取样本最多的一段轨道；至少 3 颗才能比出前后。
      const best = [...groups.values()].sort((a, b) => b.length - a.length)[0]
      if (!best || best.length < 3) return null
      const sorted = best.slice().sort((a, b) => a.t - b.t)
      const young = sorted[0]
      const old = sorted[sorted.length - 1]
      return {
        n: best.length,
        young,
        old,
        dx: old.left - young.left,
        dt: old.t - young.t,
        spread: Math.max(...best.map((x) => x.left)) - Math.min(...best.map((x) => x.left)),
      }
    })()`).catch(() => null)
    if (m) {
      pTry.samples.push({ n: m.n, dx: Math.round(m.dx), dt: Math.round(m.dt) })
      // 动画进度大（飞得久）⇒ 位置更靠右；且整组横向铺开 > 10px。
      if (m.dx > 10 && m.spread > 10) dir = m
    }
    if (!dir) await sleep(150)
  }
  // Vue SFC 的 scoped CSS 会改动画名（实测 `a-of-fly-fec5c042`）⇒ 判前缀，不判全等。
  ok('四、流轨光点朝左→右飞（同轨同帧：飞得久的粒子位置更靠右）',
    !!dir && String(dir.old.anim).startsWith('a-of-fly') && String(dir.young.anim).startsWith('a-of-fly'),
    dir
      ? {
          n: dir.n,
          young: { t: Math.round(dir.young.t), left: Math.round(dir.young.left) },
          old: { t: Math.round(dir.old.t), left: Math.round(dir.old.left) },
          dx: Math.round(dir.dx), dt: Math.round(dir.dt), anim: dir.old.anim,
        }
      : pTry)

  // 顶部：横幅 + 回放时钟 + 趋势线 / 环形图（证明三页之一从顶部入口进入后的完整形态）
  await b.evalJS(`window.scrollTo(0, 0)`)
  await sleep(400)
  await b.shot(SHOT_TIMELINE_TOP)

  await b.evalJS(`(document.querySelector('.a-of-lane') || document.body).scrollIntoView({ block: 'center' })`)
  await sleep(400)
  await b.shot(SHOT_TIMELINE)

  // 动图帧：把时间轴主视觉区连续截 8 帧（每帧 450ms），后续用 PIL 合成 GIF
  try {
    const fs = await import('node:fs')
    fs.mkdirSync(FRAME_DIR, { recursive: true })
    for (let i = 0; i < FRAMES; i++) {
      await b.shot(`${FRAME_DIR}f${String(i).padStart(2, '0')}.png`)
      await sleep(450)
    }
  } catch (e) { /* 帧目录失败不阻塞断言 */ }

  // ══════════════════════════════════════════════════════════════════════
  // 四、日志页：回放不飘 MQ 误报；时钟/进度可见
  // ══════════════════════════════════════════════════════════════════════
  const mountedLog = await goto('#/admin/observe/logs', '.a-el')
  ok('① 切到日志页后回放会话仍在', mountedLog === true)
  await sleep(2500)
  const lg = await b.evalJS(`(() => {
    const text = document.body.innerText
    const led = document.querySelector('.ev-status .led')
    return {
      mqWarn: /MQ 未连接/.test(text),
      replayClock: /回放时钟/.test(text),
      noCheck: /不检查 MQ \\/ SSE/.test(text),
      ledReplay: !!led && led.classList.contains('is-replay'),
      rows: document.querySelectorAll('.log-line').length,
      clock: (text.match(/回放时钟\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})/) || [])[1] || '',
    }
  })()`)
  ok('五、回放期间日志页**没有**「MQ 未连接」误报', lg.mqWarn === false)
  ok('五、状态条写明「回放时钟 · 不检查 MQ / SSE」，LED 切回放色',
    lg.replayClock && lg.noCheck && lg.ledReplay, { replayClock: lg.replayClock, noCheck: lg.noCheck, led: lg.ledReplay })
  ok('① 日志列表真的在铺回放事件（> 0 行）', lg.rows > 0, lg.rows)
  ok('③ 日志页时钟同样是台账原时间（23:4x）', /^23:4[12]:/.test(lg.clock), lg.clock)
  await b.shot(SHOT_LOGS)

  const errs = await b.evalJS('window.__errs || []')
  ok('运行期无 console.error / unhandledrejection', !errs || errs.length === 0, errs && errs.slice(0, 3))
} finally {
  await b.close()
}

const bad = R.dump()
console.log(`\n证据截图：\n  ${SHOT_METRICS}\n  ${SHOT_TIMELINE}\n  ${SHOT_LOGS}\n  帧目录：${FRAME_DIR}`)
process.exit(bad ? 1 : 0)
