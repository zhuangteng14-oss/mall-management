// c3b7-3a4e-stress-protect.mjs —— 块 7 · 2026-10-17「PROTECT 独立 + 压测」验收探针。
//
// 场景：真后端 8088 + 真前端 8080；800 QPS、25 秒、bannedUser 70% / sensitiveWord 30%。
//   · bannedUser 的「带票但指向他人」⇒ 每次 403 一条 identity 拒绝事件 ⇒ **紫色 PROTECT**；
//   · sensitiveWord 的 ≥40% 拒收 ⇒ **黄色 WARN**（真正业务失败）；
//   · 入站限流 200 QPS ⇒ 被挡的请求按秒汇总一条 PROTECT（不会刷屏）。
//   · 两类都不产生 5xx、不扣库存 ⇒ 健康度应保持绿色。
//
// 判据：
//   ① 原始统计里 protect ≥ 数百条（不是压缩后的个位数）；
//   ② fail 率 = (warn + error) / 原始总数，且**不含 protect**（页面值与用原始桶重算一致）；
//   ③ error 占比很低（< 5%），健康度仍为 ok（绿）；
//   ④ 顶部四色条：protect 段存在且宽度 > 0，>0 的块有 min-width 4px；
//   ⑤ 左侧/右侧严重度分布同数。
//
// 产物：stdout JSON（重定向到 docs/agent/evidence/c3-block7/3a4e-stress-protect.txt）
//       + 3a4e-stress-log-protect.png / 3a4e-stress-metrics.png
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

const b = await open({ w: 1440, h: 900 })
let runP = null
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => { try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
  })
  await b.goto(`${WEB}/admin.html?stress=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2500)

  // 确保入站限流开着（其余保护保持原样）。
  const cfgRes = await fetch(API + '/admin/observe/attack/config', { signal: AbortSignal.timeout(8000) })
  const cfg = await cfgRes.json()
  const prot = (cfg && cfg.Protections) || []
  out.samples.configBefore = prot
  if (!prot.includes('rateLimit')) {
    const next = [...new Set([...prot, 'rateLimit'])]
    const setRes = await post('/admin/observe/attack/config',
      new URLSearchParams({ protections: JSON.stringify(next) }).toString())
    out.samples.configSet = { status: setRes.status, json: setRes.json }
  }

  runP = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-protect-20261017',
    concurrency: '512',
    durationSeconds: '25',
    targetQps: '800',
    scenarios: JSON.stringify([{ key: 'bannedUser', weight: 70 }, { key: 'sensitiveWord', weight: 30 }]),
  }).toString())
  runP.catch(() => {})

  // 等 protect 累积到数百条（最多 18 秒）。
  let stats = null
  for (let i = 0; i < 36; i++) {
    await sleep(500)
    stats = await b.evalJS(`(() => {
      const el = document.querySelector('.ev-frame')
      const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
      if (!s) return null
      return { n: s.rawWindow.n, sev: { ...s.rawWindow.bySev }, kind: { ...s.rawWindow.byKind } }
    })()`)
    if (stats && (stats.sev.protect || 0) >= 300) break
  }
  out.samples.accumulated = stats

  // 冻结列表，等一拍再截图（避免合成层跨帧）。
  await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    s.paused = true
    return s.paused
  })()`)
  await sleep(900)

  const snap = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
    const sum = Object.values(s.rawWindow.bySev).reduce((a, b) => a + b, 0)
    const chips = [...document.querySelectorAll('.ev-rail .sev .sevchip')].map(txt)
    const bars = [...document.querySelectorAll('.ev-bars.sev .ev-bar')].map(txt)
    const mini = [...document.querySelectorAll('.sevmini i')].map((x) => {
      const cs = getComputedStyle(x)
      return { bg: cs.backgroundColor, w: x.getBoundingClientRect().width, minW: cs.minWidth, grow: cs.flexGrow }
    })
    const rawN = s.rawWindow.n
    const p = s.rawWindow.bySev.protect || 0
    const w = s.rawWindow.bySev.warn || 0
    const e = s.rawWindow.bySev.error || 0
    return {
      n: rawN, sum, sev: { ...s.rawWindow.bySev }, kind: { ...s.rawWindow.byKind },
      left: chips, right: bars,
      failRate: s.failRate, protectRate: s.protectRate,
      domFail: txt(document.querySelector('.cell.err b')),
      domProt: txt(document.querySelector('.cell.err .prot')),
      expectedFail: rawN ? (((w + e) / rawN) * 100).toFixed(1) : '0.0',
      expectedProtect: rawN ? ((p / rawN) * 100).toFixed(1) : '0.0',
      mini,
      displayedRows: document.querySelectorAll('#ev-list .log-line').length,
      aggChips: [...document.querySelectorAll('#ev-list .agg-chip')].map(txt).slice(0, 6),
    }
  })()`)
  out.samples.logSnapshot = snap
  await b.shot(EV + '3a4e-stress-log-protect.png')

  check('protect-hundreds', (snap.sev.protect || 0) >= 300, { protect: snap.sev.protect, n: snap.n })
  check('raw-total-consistent', snap.n === snap.sum, { n: snap.n, sum: snap.sum })
  check('fail-excludes-protect', snap.failRate === snap.expectedFail
    && snap.domFail.replace(/\s+/g, '') === snap.expectedFail + '%', {
    failRate: snap.failRate, expected: snap.expectedFail,
    formula: `(warn ${snap.sev.warn || 0} + error ${snap.sev.error || 0}) / ${snap.n}`,
    dom: snap.domFail,
  })
  check('protect-rate-from-raw', snap.protectRate === snap.expectedProtect
    && snap.domProt.includes(snap.expectedProtect + '%'), {
    protectRate: snap.protectRate, expected: snap.expectedProtect, dom: snap.domProt,
  })
  check('error-share-low', ((snap.sev.error || 0) / Math.max(1, snap.n)) < 0.05,
    { error: snap.sev.error || 0, n: snap.n })
  check('left-right-severity-same',
    snap.left.join('|').replace(/[ ,]/g, '') === snap.right.join('|').replace(/[ ,]/g, ''),
    { left: snap.left, right: snap.right })
  // 四色条：protect 段的实际宽度 > 0，且 min-width 是 4px（>0 不被吞）。
  const purple = snap.mini[1] || {}
  check('purple-segment-visible', purple.w > 0, purple)
  check('purple-segment-min4', purple.minW === '4px', purple)

  // ── 指标页（实时态）：高拦截下健康度仍绿 ─────────────────────────────────
  // ⚠️ 实时视图没有事件严重度口径（后端只推 HTTP 指标），失败率/拦截率标签在
  //    **回放视图**里（由事件台账逐秒推算）。两步分开验，别把两种视图混成一条判据。
  await b.goto(`${WEB}/admin.html?stressm=${Date.now()}#/admin/observe/metrics`, 6000)
  await sleep(3500)
  const metrics = await b.evalJS(`(() => {
    const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
    const hv = document.querySelector('.a-hv')
    return {
      healthClass: hv ? hv.className : '',
      healthText: txt(hv),
      reasons: [...document.querySelectorAll('.a-reasons li')].map(txt),
      statusText: txt(document.querySelector('.a-status')),
    }
  })()`)
  out.samples.metrics = metrics
  await b.shot(EV + '3a4e-stress-metrics.png')

  check('health-green', metrics.healthClass.includes('a-hv--ok') || metrics.healthText.includes('健康'), {
    healthClass: metrics.healthClass, healthText: metrics.healthText, reasons: metrics.reasons,
  })

  // ── 指标页（回放态）：业务失败率 vs 防护拦截率 + 不计入 fail 的文案 ────────
  await b.goto(`${WEB}/admin.html?stressr=${Date.now()}#/admin/observe/attack-sim`, 6000)
  await sleep(2500)
  const rp = await b.evalJS(`(async () => {
    const li = [...document.querySelectorAll('.a-ev')][0]
    if (!li) return { ok: false, why: 'no .a-ev' }
    if (!li.querySelector('.a-ev-body')) {
      li.querySelector('.a-ev-head').click()
      await new Promise((r) => setTimeout(r, 500))
    }
    const btn = [...li.querySelectorAll('.a-rp-in button')].find((x) => x.textContent.includes('回放') && !x.disabled)
    if (!btn) return { ok: false, why: 'no replay button' }
    btn.click()
    return { ok: true, record: li.innerText.replace(/\\s+/g, ' ').slice(0, 120) }
  })()`)
  // 点「回放」会先跳日志页；等横幅起来后用 **hash 路由**切到指标页
  // （不能 b.goto —— 整页重载会清掉内存里的 replaySession）。
  let bannerUp = false
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    bannerUp = await b.evalJS(`(async () => {
      const rb = document.querySelector('.a-rb')
      if (!rb) return false
      location.hash = '#/admin/observe/metrics'
      return true
    })()`)
    if (bannerUp) break
  }
  // 回放台账较大，加载 + 首拍渲染要等；轮询而不是死等固定时长。
  let metricsRp = null
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    metricsRp = await b.evalJS(`(() => {
      const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
      const failCell = document.querySelector('.a-stat--fail')
      return {
        clicked: ${JSON.stringify(rp)},
        failStat: txt(failCell),
        failTitle: (failCell && failCell.title) || '',
        failBig: txt(failCell && failCell.querySelector('b')),
        protSmall: txt(failCell && failCell.querySelector('.rp-prot')),
        chartTitles: [...document.querySelectorAll('.mp-chart-h h3')].map(txt).filter((x) => x.includes('失败率')),
        protWording: [...document.querySelectorAll('p')].map(txt).find((x) => x.includes('防护拦截') && x.includes('不计入')) || '',
        replayActive: !!document.querySelector('.a-status--rp'),
      }
    })()`)
    // 等到第一拍台账指标算出来（横幅起来时首拍可能还是「—」）。
    if (metricsRp && metricsRp.replayActive && metricsRp.failStat
      && !metricsRp.failBig.includes('—')) break
  }
  out.samples.metricsReplay = metricsRp
  await b.shot(EV + '3a4e-stress-metrics-replay.png')

  check('metrics-replay-active', !!metricsRp.replayActive, metricsRp)
  check('metrics-fail-stat-present', metricsRp.failStat.includes('业务失败率') && metricsRp.failStat.includes('防护拦截率'),
    metricsRp)
  check('metrics-chart-title', metricsRp.chartTitles.length > 0, metricsRp.chartTitles)
  check('metrics-protect-not-in-fail-wording',
    metricsRp.failStat.includes('不计入') || metricsRp.protWording.length > 0, metricsRp)
  // 收尾：退出回放，别把会话留给下一位用户。
  await b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb) return
    const x = [...rb.querySelectorAll('.a-rb-btn')].find((y) => y.textContent.trim() === '退出回放')
    if (x) x.click()
  })()`).catch(() => {})
  await sleep(300)

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  // 停演练（幂等：已结束时后端直接答「没在跑」也无妨）。
  try { await post('/admin/observe/attack/stop', '') } catch (e) { /* 收尾失败不掩盖判据 */ }
  try { await runP } catch (e) { /* 同上 */ }
  await b.close()
}
