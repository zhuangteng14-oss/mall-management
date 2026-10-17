// probe-b7c11-trilink-replay.mjs —— 块 7 · 全链路联动回放的**三页实测**（2026-10-17）。
//
// 查（用户 2026-10-17 指定的三条验收）：
//   ① 日志页：回放行**逐条流出**（进度/行索引随墙钟增长），且**没有 ×N**（`.agg-chip` 恒为 0）。
//   ② 指标页：回放图表由 `metricTicks` 动态重绘（状态条 / 类别分布 / 拍数随回放时钟变化），不是白板。
//   ③ 状态页：框内角标**会加会减**（先 +285 建档，再 −6 付款、−3 发货、−2 收货），框下小字只增。
//
// 数据批次：`trilink-evidence`（drill_record.Id=81，BatchId=1c5e7b66-…）：
//   真的走了一遍 ① 短演练（orderBurst）② 真 HTTP 调 `/order/orderPayIfUp` / `orderDeliveryIfUp` /
//   `orderReceiveIfUp`，并带 `X-Drill-Batch` 头 ⇒ 台账里有 create / pay / deliver / receive 四种流转。
//   ⚠️ 这是真实链路（真后端 + 真 DB + 真 MQ + 真前端），不是 mock。
//
// 跑法：① `npm run serve`（8080）+ 后端 8088（dev）② `node scripts/probes/probe-b7c11-trilink-replay.mjs`
// 依赖：`docs/美化/_cdp.mjs`。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const RECORD_NAME = 'trilink-evidence'
const out = (l, v) => console.log(l, typeof v === 'string' ? v : JSON.stringify(v))

// ⚠️ 管理员口令**不写进仓库**（明文口令会被推拦）。从环境变量读；
//    本地跑法：PROBE_ADMIN_PWD="$(cat _work/admin-pass)" node scripts/probes/probe-b7c11-trilink-replay.mjs
const ADMIN_ACC = process.env.PROBE_ADMIN_ACC || 'admin'
const ADMIN_PWD = process.env.PROBE_ADMIN_PWD || ''
if (!ADMIN_PWD) {
  console.error('缺少 PROBE_ADMIN_PWD（管理员口令）。用法：PROBE_ADMIN_PWD="$(cat _work/admin-pass)" node scripts/probes/probe-b7c11-trilink-replay.mjs')
  process.exit(2)
}

const b = await open({ w: 1440, h: 900, headed: false })

/** 展开指定名字的演练记录，并点它的「回放」。 */
async function clickReplay(name) {
  return b.evalJS(`(async () => {
    const li = [...document.querySelectorAll('.a-ev')].find(x => x.innerText.includes(${JSON.stringify(name)}))
    if (!li) return { ok: false, why: 'record not found' }
    if (!li.querySelector('.a-ev-body')) {
      li.querySelector('.a-ev-head').click()
      await new Promise(r => setTimeout(r, 400))
    }
    const btn = [...li.querySelectorAll('.a-rp-in button')].find(x => x.textContent.includes('回放') && !x.disabled)
    if (!btn) return { ok: false, why: 'replay button missing', text: li.innerText.slice(0, 120) }
    btn.click()
    return { ok: true }
  })()`)
}

/** 装「进回放就暂停」的看门狗（跨 SPA 路由存活）。 */
async function armAutoPause() {
  await b.evalJS(`(() => {
    if (window.__ap) clearInterval(window.__ap)
    window.__ap = setInterval(() => {
      const rb = document.querySelector('.a-rb')
      if (!rb) return
      const st = (rb.querySelector('.a-rb-state') || {}).textContent || ''
      const raw = (rb.querySelector('.a-rb-prog b') || {}).textContent || '0'
      const prog = parseInt(raw.replace(/[^0-9]/g, '') || '0', 10)
      if (st.includes('播放中') && prog > 0) {
        const btn = [...rb.querySelectorAll('.a-rb-btn')].find(x => x.textContent.trim() === '暂停')
        if (btn) { btn.click(); clearInterval(window.__ap); window.__ap = null }
      }
    }, 10)
    return true
  })()`)
}

/** 点横幅上的「暂停 / 继续 / 退出回放」。 */
async function bannerClick(label) {
  return b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb) return false
    const btn = [...rb.querySelectorAll('.a-rb-btn')].find(x => x.textContent.trim() === ${JSON.stringify(label)})
    if (!btn) return false
    btn.click()
    return true
  })()`)
}

async function bannerState() {
  return b.evalJS(`(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb) return null
    const q = (s) => { const el = rb.querySelector(s); return el ? el.textContent.trim() : '' }
    return { prog: q('.a-rb-prog b'), count: (rb.querySelector('.a-rb-prog') || {}).textContent || '', state: q('.a-rb-state'), clock: q('.a-rb-clock b') }
  })()`)
}

async function gotoAttack() {
  await b.goto(BASE + '#/admin/observe/attack-sim', 4500)
  await sleep(600)
}

async function exitReplayIfAny() {
  const st = await bannerState()
  if (st) {
    await bannerClick('退出回放')
    await sleep(500)
  }
}

// ── 登录 ─────────────────────────────────────────────────────────────────────
await b.goto(`${BASE}#/login`, 2500)
await b.evalJS(`(() => {
  const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
  set(document.querySelector('#acc'), ${JSON.stringify(ADMIN_ACC)})
  set(document.querySelector('#pwd'), ${JSON.stringify(ADMIN_PWD)})
  // ⚠️ 页面上有多个文字为「登录」的按钮（顶部选项卡、手机号分栏的置灰钮）；
  //    提交钮的判据是类名组合（btn btn-primary btn-block），不是文案。
  const btn = document.querySelector('button.btn.btn-primary.btn-block')
  if (btn) btn.click()
})()`)
for (let i = 0; i < 40; i++) {
  await sleep(500)
  if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
}
out('LOGIN', await b.evalJS('location.hash'))

// ══ ① 日志页：逐条流出 + 无 ×N ═══════════════════════════════════════════════
await gotoAttack()
await b.evalJS(`(() => {
  if (window.__recTimer) clearInterval(window.__recTimer)
  window.__rec = { t0: performance.now(), log: [] }
  window.__recTimer = setInterval(() => {
    const rb = document.querySelector('.a-rb')
    if (!rb && !location.hash.includes('observe/logs')) return
    const lines = document.querySelectorAll('#ev-list .log-line')
    const aggs = document.querySelectorAll('#ev-list .agg-chip')
    let lastIdx = -1
    if (lines.length) {
      const v = lines[lines.length - 1].getAttribute('data-vidx')
      lastIdx = v === null ? -1 : Number(v)
    }
    const q = (s) => { const el = rb && rb.querySelector(s); return el ? el.textContent.trim() : '' }
    window.__rec.log.push({
      ms: Math.round(performance.now() - window.__rec.t0),
      hash: location.hash,
      prog: q('.a-rb-prog b'),
      clock: q('.a-rb-clock b'),
      state: q('.a-rb-state'),
      rows: lines.length,
      lastIdx,
      agg: aggs.length,
    })
  }, 25)
  return true
})()`)
out('REPLAY_LOG_START', await clickReplay(RECORD_NAME))
for (let i = 0; i < 30; i++) { await sleep(100); if ((await b.evalJS('location.hash')).includes('observe/logs')) break }
await sleep(400)
await b.shot(OUT + 'b7c11-trilink-log-1.png')
await sleep(1200)
await b.shot(OUT + 'b7c11-trilink-log-2.png')
// 等它放完（最多 10 秒）
for (let i = 0; i < 100; i++) {
  const st = await bannerState()
  if (!st || st.state.includes('已放完')) break
  await sleep(100)
}
const logRec = await b.evalJS(`(() => { clearInterval(window.__recTimer); window.__recTimer = null; return window.__rec.log })()`)
const logCheck = await b.evalJS(`(() => {
  const txt = [...document.querySelectorAll('#ev-list .log-line')].map(x => x.innerText).join('\\n')
  const m = txt.match(/×\\s*\\d+/g) || []
  return { xN: m.slice(0, 5), xNCount: m.length, rows: document.querySelectorAll('#ev-list .log-line').length }
})()`)
await b.shot(OUT + 'b7c11-trilink-log-done.png')
out('LOG_CHECK', logCheck)
await exitReplayIfAny()

// ══ ② 指标页：图表/读数随回放时钟变化 ══════════════════════════════════════════
await gotoAttack()
await armAutoPause()
out('REPLAY_METRICS_START', await clickReplay(RECORD_NAME))
for (let i = 0; i < 40; i++) { await sleep(100); const st = await bannerState(); if (st && st.state.includes('已暂停')) break }
await b.evalJS(`location.hash = '#/admin/observe/metrics'`)
await sleep(1500)
out('METRICS_PAUSED_AT', await bannerState())
await b.evalJS(`(() => {
  if (window.__mrecTimer) clearInterval(window.__mrecTimer)
  window.__mrec = { t0: performance.now(), log: [] }
  window.__mrecTimer = setInterval(() => {
    const rb = document.querySelector('.a-rb')
    const q = (s) => { const el = rb && rb.querySelector(s); return el ? el.textContent.trim() : '' }
    const band = [...document.querySelectorAll('.a-status--rp .a-stat')].map(x => x.innerText.replace(/\\s+/g, ' ').trim())
    const kinds = [...document.querySelectorAll('.mp-rp-kind b')].map(x => x.textContent.trim())
    window.__mrec.log.push({
      ms: Math.round(performance.now() - window.__mrec.t0),
      prog: q('.a-rb-prog b'),
      clock: q('.a-rb-clock b'),
      state: q('.a-rb-state'),
      band,
      kinds: kinds.join(','),
      warn: (document.querySelector('.mp-rp-warn') || {}).innerText ? true : false,
      ticks: (document.querySelector('.mp-rp-facts') || {}).innerText || '',
      shots: document.querySelectorAll('.mp-chart canvas').length,
    })
  }, 25)
  return true
})()`)
// 三段「继续 → 停」：每段让时钟走一点，采到不同的秒拍。
await bannerClick('继续'); await sleep(700); await bannerClick('暂停'); await sleep(250)
await b.shot(OUT + 'b7c11-trilink-metrics-1.png')
out('METRICS_T1', await bannerState())
await bannerClick('继续'); await sleep(900); await bannerClick('暂停'); await sleep(250)
out('METRICS_T2', await bannerState())
await bannerClick('继续'); await sleep(900); await bannerClick('暂停'); await sleep(250)
await b.shot(OUT + 'b7c11-trilink-metrics-2.png')
out('METRICS_T3', await bannerState())
// 放到结束，确保拍数继续涨（白板的话这里不会变）
await bannerClick('继续')
for (let i = 0; i < 120; i++) { const st = await bannerState(); if (!st || st.state.includes('已放完')) break; await sleep(100) }
await sleep(400)
await b.shot(OUT + 'b7c11-trilink-metrics-done.png')
const metricsRec = await b.evalJS(`(() => { clearInterval(window.__mrecTimer); window.__mrecTimer = null; return window.__mrec.log })()`)
out('METRICS_FINAL', await b.evalJS(`(() => {
  const band = [...document.querySelectorAll('.a-status--rp .a-stat')].map(x => x.innerText.replace(/\\s+/g, ' ').trim())
  return { band, facts: (document.querySelector('.mp-rp-facts') || {}).innerText || '', warn: (document.querySelector('.mp-rp-warn') || {}).innerText || '', canvases: document.querySelectorAll('.mp-chart canvas').length }
})()`))
await exitReplayIfAny()

// ══ ③ 状态页：角标会加会减 + 框下小字只增 ═══════════════════════════════════════
await gotoAttack()
await armAutoPause()
out('REPLAY_STATE_START', await clickReplay(RECORD_NAME))
for (let i = 0; i < 40; i++) { await sleep(100); const st = await bannerState(); if (st && st.state.includes('已暂停')) break }
await b.evalJS(`location.hash = '#/admin/observe/timeline'`)
await sleep(1600)
out('STATE_PAUSED_AT', await bannerState())
await b.evalJS(`(() => {
  if (window.__srecTimer) clearInterval(window.__srecTimer)
  window.__srec = { t0: performance.now(), log: [] }
  window.__srecTimer = setInterval(() => {
    const rb = document.querySelector('.a-rb')
    const q = (s) => { const el = rb && rb.querySelector(s); return el ? el.textContent.trim() : '' }
    window.__srec.log.push({
      ms: Math.round(performance.now() - window.__srec.t0),
      prog: q('.a-rb-prog b'),
      clock: q('.a-rb-clock b'),
      state: q('.a-rb-state'),
      badges: [...document.querySelectorAll('.a-of-box-n')].map(x => x.textContent.trim()).join('/'),
      hits: [...document.querySelectorAll('.a-of-box-b')].map(x => x.textContent.trim()).join('/'),
      edgesOn: document.querySelectorAll('.a-of-edge.on').length,
      recent: document.querySelectorAll('.a-of-rpm-i').length,
      moves: (document.querySelector('.a-status--rp') || {}).innerText || '',
    })
  }, 20)
  return true
})()`)
// 分几段放：每段 700ms 墙钟 ≈ 7 秒日志时间。
for (let i = 0; i < 4; i++) {
  await bannerClick('继续')
  await sleep(120)
  await b.shot(OUT + 'b7c11-trilink-state-run' + (i + 1) + '.png')
  await sleep(600)
  await bannerClick('暂停')
  await sleep(250)
  out('STATE_T' + (i + 1), await b.evalJS(`(() => ({
    badges: [...document.querySelectorAll('.a-of-box-n')].map(x => x.textContent.trim()).join('/'),
    hits: [...document.querySelectorAll('.a-of-box-b')].map(x => x.textContent.trim()).join('/'),
    banner: (() => { const rb = document.querySelector('.a-rb'); return rb ? rb.querySelector('.a-rb-prog b').textContent.trim() : '' })()
  }))()`))
}
await b.shot(OUT + 'b7c11-trilink-state-paused.png')
// 放完
await bannerClick('继续')
for (let i = 0; i < 120; i++) { const st = await bannerState(); if (!st || st.state.includes('已放完')) break; await sleep(100) }
await sleep(500)
await b.shot(OUT + 'b7c11-trilink-state-done.png')
const stateRec = await b.evalJS(`(() => { clearInterval(window.__srecTimer); window.__srecTimer = null; return window.__srec.log })()`)
out('STATE_FINAL', await b.evalJS(`(() => ({
  badges: [...document.querySelectorAll('.a-of-box-n')].map(x => x.textContent.trim()),
  hits: [...document.querySelectorAll('.a-of-box-b')].map(x => x.textContent.trim()),
  recent: document.querySelectorAll('.a-of-rpm-i').length,
  total: (document.querySelector('.a-status--rp') || {}).innerText || ''
}))()`))

// ══ 汇总（落 _work/ 供报告引用）══════════════════════════════════════════════════
const summary = {
  log: summarizeLog(logRec),
  metrics: summarizeMetrics(metricsRec),
  state: summarizeState(stateRec),
  logCheck,
}
function uniq(a) { return [...new Set(a)] }
/** 横幅进度带千分位（`1,016`）⇒ 直接 Number() 会得 NaN、把上千后的采样全丢掉。 */
function num(v) { return Number(String(v == null ? '' : v).replace(/,/g, '')) }
function summarizeLog(rec) {
  const live = rec.filter((x) => x.prog !== '')
  const progs = uniq(live.map((x) => num(x.prog))).filter((n) => Number.isFinite(n)).sort((a, b) => a - b)
  const rowCounts = uniq(live.map((x) => x.rows)).sort((a, b) => a - b)
  return {
    samples: rec.length,
    liveSamples: live.length,
    firstProg: progs[0],
    lastProg: progs[progs.length - 1],
    distinctProgress: progs.length,
    progressHead: progs.slice(0, 8),
    progressTail: progs.slice(-8),
    rowCountsMin: rowCounts[0],
    rowCountsMax: rowCounts[rowCounts.length - 1],
    aggMax: Math.max(0, ...rec.map((x) => x.agg || 0)),
    states: uniq(rec.map((x) => x.state).filter(Boolean)),
  }
}
function summarizeMetrics(rec) {
  const bands = uniq(rec.map((x) => x.band.join('|')))
  const kinds = uniq(rec.map((x) => x.kinds))
  const ticks = uniq(rec.map((x) => x.ticks))
  return {
    samples: rec.length,
    distinctBands: bands.length,
    bandHead: bands.slice(0, 6),
    bandTail: bands.slice(-3),
    distinctKindBars: kinds.length,
    distinctTicks: ticks.length,
    warnSeen: rec.some((x) => x.warn),
    canvases: Math.max(0, ...rec.map((x) => x.shots || 0)),
  }
}
function summarizeState(rec) {
  const badges = uniq(rec.map((x) => x.badges).filter(Boolean))
  const hits = uniq(rec.map((x) => x.hits).filter(Boolean))
  const perState = {}
  for (const s of rec) {
    if (!s.badges) continue
    s.badges.split('/').forEach((v, i) => { (perState[i] = perState[i] || new Set()).add(v) })
  }
  return {
    samples: rec.length,
    distinctBadgeVectors: badges.length,
    badgeHead: badges.slice(0, 8),
    badgeTail: badges.slice(-8),
    statesSeen: Object.fromEntries(Object.entries(perState).map(([k, v]) => [k, [...v]])),
    distinctHitVectors: hits.length,
    hitHead: hits.slice(0, 5),
    hitTail: hits.slice(-5),
    edgeOnMax: Math.max(0, ...rec.map((x) => x.edgesOn || 0)),
    recentMax: Math.max(0, ...rec.map((x) => x.recent || 0)),
  }
}

const fs = await import('node:fs')
fs.writeFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/trilink-probe-summary.json', JSON.stringify(summary, null, 2))
fs.writeFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/trilink-probe-log-samples.json', JSON.stringify(logRec, null, 2))
fs.writeFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/trilink-probe-metrics-samples.json', JSON.stringify(metricsRec, null, 2))
fs.writeFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/trilink-probe-state-samples.json', JSON.stringify(stateRec, null, 2))
out('SUMMARY', summary)

await b.close()
