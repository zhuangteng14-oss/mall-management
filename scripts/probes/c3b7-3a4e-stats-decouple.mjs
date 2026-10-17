// c3b7-3a4e-stats-decouple.mjs —— 块 7 · 2026-10-17「日志页统计与聚合解耦」验收探针。
//
// 判据（用户原话拆成可测项）：
//   1. 统计 / Top-N / 严重度分布 / 失败率 + 拦截率 **绝不读聚合后的 events**；
//      注入 541 条原始事件（其中 200 条同类 protect）后，统计必须是 541 / protect=200，
//      而不是「展示窗口 200 条里被并成的 3 行」。
//   2. 过滤栏旁边的「同类合并（聚合）」开关：勾选 = ×N 合并；取消 = 原始逐条（DOM ≤200 行）。
//   3. 开关前后统计**一分不变**；左侧「信息」与右侧严重度分布**同一个数**。
//
// 注入走页面 setupState 的 `absorbEvents`（与真实收帧同一条链），不 mock 任何请求；
// 注入前关流 + 清桶，避免实时事件的噪声。
//
// 产物：
//   stdout JSON（重定向到 docs/agent/evidence/c3-block7/3a4e-stats-decouple.txt）
//   + 3a4e-stats-agg-on.png / 3a4e-stats-agg-off.png
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const out = { at: new Date().toISOString(), checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
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
  await b.goto(`${WEB}/admin.html?stats=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2500)

  // ── 注入 541 条原始事件：300 info / 200 protect / 40 warn / 1 error ──────────
  await b.evalJS(SETUP(`
    // 关流 + 冻结 + 清两个源桶（含 RAW 原始台账），只量注入的这批。
    s.closeStream()
    s.paused = true
    s.clearBucket('normal')
    s.clearBucket('demo')
    s.seenSeq.clear()
    const base = 7_000_000_000
    const now = Date.now()
    const mk = (i, kind, severity, title) => ({
      seq: base + i, ts: now + i, kind, severity, title, detail: 'probe-' + i,
      key: null, objectType: null, objectId: null, traceId: '',
      batchId: null, actor: null, durationUs: 1_000, remaining: null, fields: null,
    })
    const batch = []
    for (let i = 0; i < 300; i++) batch.push(mk(i, 'cache', 'info', '缓存命中'))
    for (let i = 0; i < 200; i++) batch.push(mk(300 + i, 'protection', 'protect', '入站限流拦下'))
    for (let i = 0; i < 40; i++) batch.push(mk(500 + i, 'stock', 'warn', '库存不足'))
    batch.push(mk(540, 'audit', 'error', '审计写入失败'))
    s.absorbEvents(batch, 'normal')
    s.aggEnabled = true
    await new Promise((r) => s.nextTick(r))
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
    return {
      injected: batch.length,
      rawN: s.rawWindow.n,
      rawSev: { ...s.rawWindow.bySev },
      displayEvents: s.events.length,
      streamRows: s.streamRows.length,
      aggEnabled: s.aggEnabled,
    }
  `))

  const read = () => b.evalJS(SETUP(`
    const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
    const leftSev = [...document.querySelectorAll('.ev-rail .sev .sevchip')].map(txt)
    const rightSev = [...document.querySelectorAll('.ev-bars.sev .ev-bar')].map(txt)
    const rows = [...document.querySelectorAll('#ev-list .log-line')]
    const chips = rows.map((li) => txt(li.querySelector('.agg-chip'))).filter(Boolean)
    const readSum = [...document.querySelectorAll('.ev-read span')].map(txt).find((x) => x.includes('范围内合计')) || ''
    return {
      aggEnabled: s.aggEnabled,
      rawN: s.rawWindow.n,
      rawSev: { ...s.rawWindow.bySev },
      rawKind: { ...s.rawWindow.byKind },
      displayEvents: s.events.length,
      streamRows: s.streamRows.length,
      domRows: rows.length,
      aggChips: chips,
      leftSev,
      rightSev,
      readSum,
      failText: txt(document.querySelector('.cell.err b')).replace(/\\s+/g, ''),
      protText: txt(document.querySelector('.cell.err .prot')),
      topFailTitle: (document.querySelector('.cell.err') || {}).title || '',
    }
  `))

  const on = await read()
  out.samples.aggOn = on
  await b.shot(EV + '3a4e-stats-agg-on.png')

  check('injected-541', on.rawN === 541, { rawN: on.rawN })
  check('raw-severity-counts', on.rawSev.info === 300 && on.rawSev.protect === 200
    && on.rawSev.warn === 40 && on.rawSev.error === 1, on.rawSev)
  check('display-window-capped-200', on.displayEvents === 200, { displayEvents: on.displayEvents })
  check('agg-on-collapses-rows', on.streamRows < 20 && on.aggChips.length >= 2,
    { streamRows: on.streamRows, aggChips: on.aggChips })
  check('fail-rate-from-raw', on.failText === '7.6%',
    { failText: on.failText, expected: '7.6%', detail: '41 / 541' })
  check('protect-rate-from-raw', on.protText.includes('37.0%'), { protText: on.protText })
  check('left-right-severity-same',
    on.leftSev.join('|').includes('信息 300') && on.rightSev.join('|').includes('信息 300')
    && on.leftSev.join('|').includes('防护 200') && on.rightSev.join('|').includes('防护 200')
    && on.leftSev.join('|').includes('警告 40') && on.rightSev.join('|').includes('警告 40')
    && on.leftSev.join('|').includes('错误 1') && on.rightSev.join('|').includes('错误 1'),
    { leftSev: on.leftSev, rightSev: on.rightSev })
  check('range-total-from-raw', on.readSum.includes('541'), { readSum: on.readSum })

  // ── 点开关：取消聚合 ⇒ 原始逐条（展示窗口 200 行） ────────────────────────
  await b.evalJS(`(() => {
    const cb = document.querySelector('.ev-rail .agg input[type=checkbox]')
    if (!cb) throw new Error('no agg checkbox')
    cb.click()
    return cb.checked
  })()`)
  await sleep(350)
  const off = await read()
  out.samples.aggOff = off
  await b.shot(EV + '3a4e-stats-agg-off.png')

  check('agg-off-raw-rows', off.aggEnabled === false && off.aggChips.length === 0 && off.domRows === 200,
    { aggEnabled: off.aggEnabled, domRows: off.domRows, aggChips: off.aggChips })
  check('stats-identical-after-toggle',
    off.rawN === on.rawN && JSON.stringify(off.rawSev) === JSON.stringify(on.rawSev)
    && off.failText === on.failText && off.protText === on.protText
    && JSON.stringify(off.leftSev) === JSON.stringify(on.leftSev)
    && JSON.stringify(off.rightSev) === JSON.stringify(on.rightSev),
    {
      on: { rawN: on.rawN, rawSev: on.rawSev, failText: on.failText, protText: on.protText, leftSev: on.leftSev },
      off: { rawN: off.rawN, rawSev: off.rawSev, failText: off.failText, protText: off.protText, leftSev: off.leftSev },
    })

  // ── 再点回来：统计仍然不变 ────────────────────────────────────────────────
  await b.evalJS(`(() => {
    const cb = document.querySelector('.ev-rail .agg input[type=checkbox]')
    cb.click()
    return cb.checked
  })()`)
  await sleep(350)
  const back = await read()
  out.samples.aggBackOn = back
  check('stats-identical-back-on',
    back.rawN === on.rawN && JSON.stringify(back.rawSev) === JSON.stringify(on.rawSev)
    && back.failText === on.failText && back.protText === on.protText,
    { rawN: back.rawN, rawSev: back.rawSev, failText: back.failText, protText: back.protText })

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  await b.close()
}
