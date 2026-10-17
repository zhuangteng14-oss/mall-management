// c3b7-3a4-real-link.mjs —— 块 7 · 2026-10-17「三个真实链路问题」验收探针。
//
// ⚠️ **不 mock 任何请求、不开「模拟日常」**：前端走真实 dev server（8080，admin.html 入口），
//    后端走真实 dev profile（8088）。造流量也只打真接口（Node fetch 直连 8088）。
//    `docs/美化/_cdp.mjs` 的 mock 参数**不传**（它默认就是关的）。
//
// 验三件事（用户 2026-10-17 的原话）：
//   ① 日志能实时刷：进日志页有行（缺省「全部」范围），真流量进来后行数在涨；
//   ② 指标切页不重置：指标页 → 日志页 → 指标页（SPA 路由，不刷新），「第 N 拍」继续往前走；
//   ③ 回放能一点点放出来：**先在没有日志页的情况下**跑一轮 8 秒演练 → 真接口读回批次台账
//      total>0（证明常驻落库生效）→ 在攻击页点【回放】，采样进度 X/N 逐步增长。
//
// 产物：
//   docs/agent/evidence/c3-block7/3a4-real-*.png   （三张实拍）
//   stdout 的 JSON 报告（由调用方重定向到 .txt —— 证据文件必须 .txt）
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
    signal: AbortSignal.timeout(120000),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) { /* 非 JSON 就留原文 */ }
  return { status: res.status, json, text }
}

const b = await open({ w: 1440, h: 900 })
try {
  // 只种前端路由守卫要的三个 sessionStorage 键（与既有可观测域探针同款；不 mock 任何请求）。
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      try {
        sessionStorage.setItem('loginUsername', 'ops_duan')
        sessionStorage.setItem('customerid', 'qa7PmpUojO')
        sessionStorage.setItem('role', '3')
      } catch (e) {}
    })()`,
  })

  const read = () => b.evalJS(`(() => {
    const txt = (s) => { const e = document.querySelector(s); return e ? e.innerText.replace(/\\s+/g, ' ').trim() : null }
    const num = (s) => { const e = document.querySelector(s); return e ? Number(String(e.innerText).replace(/[^0-9.]/g, '')) : null }
    // 组件内部状态（dev 构建才有 setupState；读不到就返回 null，不把它当判据）
    const state = (sel) => {
      const el = document.querySelector(sel)
      if (!el || !el.__vueParentComponent) return null
      const s = el.__vueParentComponent.setupState || {}
      const pick = (k) => { try { const v = s[k]; return Array.isArray(v) ? v.length : v } catch (e) { return null } }
      return { rows: pick('rows'), events: pick('events'), ranged: pick('ranged'), seq: pick('seq'), cursor: pick('cursor') }
    }
    return {
      theme: document.documentElement.dataset.theme || null,
      title: document.title,
      hash: location.hash,
      log: {
        status: txt('.ev-status'),
        ranged: num('.ev-status .cell b'),
        rows: document.querySelectorAll('#ev-list li:not(.ev-empty)').length,
        emptyText: txt('#ev-list .ev-empty'),
        rangeChip: txt('.ev-range'),
        state: state('.ev-frame'),
      },
      metrics: {
        seq: num('.a-tz b'),
        tz: txt('.a-tz'),
        charts: document.querySelectorAll('.mp-chart canvas').length,
        state: state('.a-mp'),
      },
      replay: {
        live: txt('.a-rp-live-t'),
        bar: txt('.a-rp-bar'),
        played: document.querySelectorAll('.a-rp-ev').length,
        empty: txt('.a-rp-say, .a-kv'),
      },
    }
  })()`)

  // ────────────────────────────────────────────────────────────────────────
  // ① 日志页：进页面就有行；真流量进来后行数在涨
  // ────────────────────────────────────────────────────────────────────────
  // 先打几发真接口把环里攒上事件（后端刚重启时环是空的 —— 那也会让页面「空」，
  // 但那是「确实没有事件」，不是本次要治的「有事件却被 5 分钟范围滤掉」）。
  const pre = []
  for (const path of ['/goods/goodsSelectHome', '/goods/productSearch?keyword=a', '/goods/goodstype']) {
    try {
      const r = await fetch(API + path, { signal: AbortSignal.timeout(5000) })
      pre.push({ path, status: r.status })
    } catch (e) {
      pre.push({ path, error: String(e && e.message) })
    }
  }
  out.samples.preTraffic = pre
  await sleep(2500)
  await b.goto(`${WEB}/admin.html#/admin/observe/logs`, 4000)
  await sleep(3500)
  const log1 = await read()
  out.samples.logEntry = log1
  check('admin-entry-theme', log1.theme === 'admin', { theme: log1.theme, title: log1.title })
  check('logs-not-blank-on-entry', log1.log.rows > 0 || (log1.log.state && log1.log.state.ranged > 0),
    { rows: log1.log.rows, ranged: log1.log.ranged, emptyText: log1.log.emptyText, state: log1.log.state })
  // 缺省范围必须是「全部」（用户报的空白根源 = 缺省 5m 把首帧快照滤光）
  check('logs-default-range-all', /时间范围 全部/.test(log1.log.status || ''),
    { status: log1.log.status })
  await b.shot(EV + '3a4-real-logs-entry.png')

  // 真流量：打 12 发商品接口（各 4 发 × 3 个端点），分 3 秒匀开发，保证日志页有多拍可看。
  // ⚠️ 「行数变多」**不是**判据：页面窗口封顶 1000 条（`EVENT_CAP`），旧行被新行挤掉后
  //    总数不变。这里按**时间戳**数「这之后新到的行」。
  const tTraffic = Date.now()
  const hits = []
  for (let i = 0; i < 12; i += 1) {
    const path = ['/goods/goodsSelectHome', '/goods/productSearch?keyword=a', '/goods/goodstype'][i % 3]
    try {
      const r = await fetch(API + path, { signal: AbortSignal.timeout(5000) })
      hits.push({ path, status: r.status })
    } catch (e) {
      hits.push({ path, error: String(e && e.message) })
    }
    await sleep(250)
  }
  out.samples.traffic = hits
  // 再用一轮**真实演练**（日志页开着）制造一眼可见的实时流：4 秒、订单并发。
  // ⚠️ 这是真接口（后端攻击引擎打真订单），不是 mock。
  const liveDrill = await post('/admin/observe/attack/run',
    new URLSearchParams({
      name: 'probe-live-logs-20261017',
      concurrency: '10',
      durationSeconds: '4',
      targetQps: '200',
      scenarios: JSON.stringify([{ key: 'orderBurst', weight: 1 }]),
    }).toString())
  out.samples.liveDrill = { status: liveDrill.status, ok: !!(liveDrill.json && liveDrill.json.success),
    id: liveDrill.json && liveDrill.json.Id, total: liveDrill.json && liveDrill.json.TotalRequests }
  await sleep(3000)
  const log2 = await read()
  const freshRows = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    if (!s) return { why: 'no setupState' }
    const unwrap = (v) => (v && typeof v === 'object' && 'value' in v ? v.value : v)
    const arr = unwrap(s.events) || unwrap(s.ranged) || []
    const cut = ${tTraffic}
    const list = Array.isArray(arr) ? arr : []
    return {
      total: list.length,
      fresh: list.filter((e) => Number(e.ts) >= cut).length,
      newestTs: list.reduce((m, e) => Math.max(m, Number(e.ts) || 0), 0),
      cut,
    }
  })()`)
  out.samples.logAfterTraffic = log2
  out.samples.logFreshRows = freshRows
  check('logs-live-growth', freshRows.fresh >= 50,
    { fresh: freshRows.fresh, total: freshRows.total, newestTs: freshRows.newestTs, cut: freshRows.cut,
      ranged: log2.log.ranged, seq: log2.log.state && log2.log.state.seq })
  await b.shot(EV + '3a4-real-logs-live.png')

  // ────────────────────────────────────────────────────────────────────────
  // ② 指标页：首屏有历史（不再是 0），切走再回来「第 N 拍」继续
  // ────────────────────────────────────────────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/metrics'; true`)
  await sleep(4500)
  const m1 = await read()
  out.samples.metricsFirst = m1
  check('metrics-first-screen-history', (m1.metrics.state && m1.metrics.state.rows > 5) || m1.metrics.seq > 5,
    { seq: m1.metrics.seq, rows: m1.metrics.state && m1.metrics.state.rows, tz: m1.metrics.tz })
  await b.shot(EV + '3a4-real-metrics-first.png')

  await b.evalJS(`location.hash = '#/admin/observe/logs'; true`)
  await sleep(4000)
  await b.evalJS(`location.hash = '#/admin/observe/metrics'; true`)
  await sleep(2500)
  const m2 = await read()
  out.samples.metricsAfterRoundTrip = m2
  const seq1 = m1.metrics.seq
  const seq2 = m2.metrics.seq
  check('metrics-survives-route-switch', seq1 != null && seq2 != null && seq2 > seq1 + 2,
    { seqBefore: seq1, seqAfter: seq2, rowsBefore: m1.metrics.state && m1.metrics.state.rows,
      rowsAfter: m2.metrics.state && m2.metrics.state.rows })
  await b.shot(EV + '3a4-real-metrics-roundtrip.png')

  // ────────────────────────────────────────────────────────────────────────
  // ③ 回放：先「无人看日志页」跑一轮 8 秒演练 → 台账 total>0 → UI 里逐步播放
  // ────────────────────────────────────────────────────────────────────────
  // 关掉浏览器里的一切流（导航到空白页），确保这一轮演练期间**没有日志页 SSE**。
  await b.goto('about:blank', 500)
  const run = await post('/admin/observe/attack/run',
    new URLSearchParams({
      name: 'probe-persist-20261017',
      concurrency: '20',
      durationSeconds: '8',
      targetQps: '200',
      scenarios: JSON.stringify([{ key: 'orderBurst', weight: 1 }]),
    }).toString())
  out.samples.drill = { status: run.status, ok: !!(run.json && run.json.success), id: run.json && run.json.Id,
    batchId: run.json && run.json.BatchId, total: run.json && run.json.TotalRequests,
    text: run.json ? undefined : String(run.text).slice(0, 300) }
  // 等常驻轮询把最后一批事件并进台账（间隔 1000ms，留 3 秒余量）。
  await sleep(3000)

  const recs = await post('/admin/observe/attack/records', '')
  const list = (recs.json && recs.json.list) || []
  const newest = list.find((r) => r.Id === (run.json && run.json.Id)) || list[0]
  out.samples.records = { status: recs.status, count: list.length,
    newest: newest && { Id: newest.Id, BatchId: newest.BatchId, Name: newest.Name, CreatedAt: newest.CreatedAt } }

  const batchId = (newest && newest.BatchId) || (run.json && run.json.BatchId)
  const replayApi = batchId
    ? await post(`/admin/observe/replay?batchId=${encodeURIComponent(batchId)}&limit=5000`, '')
    : { status: 0, json: null }
  const apiList = (replayApi.json && replayApi.json.list) || []
  out.samples.replayApi = { status: replayApi.status, success: replayApi.json && replayApi.json.success,
    total: replayApi.json && replayApi.json.total, list: apiList.length,
    firstKind: apiList[0] && apiList[0].Kind, firstAt: apiList[0] && apiList[0].OccurredAt }
  check('replay-persisted-without-viewer', !!(replayApi.json && replayApi.json.total > 0),
    { batchId, total: replayApi.json && replayApi.json.total, list: apiList.length, drillOk: !!(run.json && run.json.success) })

  // 打开攻击页 → 找到这一批那一行 → 点【回放】→ 采样进度
  await b.goto(`${WEB}/admin.html#/admin/observe/attack-sim`, 5000)
  await sleep(3000)
  const clicked = await b.evalJS(`(() => {
    const bid = ${JSON.stringify(batchId || '')}
    const rows = [...document.querySelectorAll('li.a-ev')]
    const row = rows.find((r) => bid && r.innerText.includes(bid)) || rows[0]
    if (!row) return { ok: false, why: 'no rows', n: rows.length }
    // ⚠️ 【回放】钮只在**展开**的行里（v-if="openId === r.Id"）⇒ 先点头部展开。
    const head = row.querySelector('.a-ev-head')
    if (head) head.click()
    return { ok: true, expanded: !!head, text: row.innerText.replace(/\\s+/g, ' ').slice(0, 160) }
  })()`)
  out.samples.replayClick = clicked
  await sleep(500)
  const playClicked = await b.evalJS(`(() => {
    const btns = [...document.querySelectorAll('li.a-ev.on button')]
    const btn = btns.find((x) => (x.textContent || '').includes('回放'))
    if (!btn) return { ok: false, why: 'no replay button', expanded: document.querySelectorAll('li.a-ev.on').length,
      buttons: btns.map((x) => (x.textContent || '').replace(/\\s+/g, ' ').trim()),
      bodyButtons: [...document.querySelectorAll('li.a-ev.on .a-rp-in button')].length }
    if (btn.disabled) return { ok: false, why: 'disabled', text: btn.textContent }
    btn.click()
    return { ok: true }
  })()`)
  out.samples.playClicked = playClicked
  await sleep(600)

  const ticks = []
  for (let i = 0; i < 10; i += 1) {
    const r = await read()
    const m = /进度\s*(\d+)\s*\/\s*(\d+)/.exec(r.replay.live || '')
    ticks.push({ t: i * 400 + 600, live: r.replay.live, played: r.replay.played,
      cursor: m ? Number(m[1]) : null, total: m ? Number(m[2]) : null })
    if (i === 0) {
      // 回放面板在记录列表下方（首屏看不到）⇒ 先滚到面板再拍，否则拍到的是页头。
      await b.evalJS(`(() => { const e = document.querySelector('.a-rp-live'); if (e) e.scrollIntoView({ block: 'center' }); return true })()`)
      await sleep(200)
      await b.shot(EV + '3a4-real-replay-early.png')
    }
    await sleep(400)
  }
  out.samples.replayTicks = ticks
  const seen = ticks.filter((x) => x.cursor != null)
  const monotone = seen.every((x, i) => i === 0 || x.cursor >= seen[i - 1].cursor)
  const grew = seen.length >= 2 && seen[seen.length - 1].cursor > seen[0].cursor
  const notAllAtOnce = seen.length >= 2 && seen[0].cursor < seen[0].total && seen[1].cursor < seen[1].total
  check('replay-gradual', grew && monotone && notAllAtOnce,
    { startedAt: seen[0], later: seen[seen.length - 1] })
  await b.evalJS(`(() => { const e = document.querySelector('.a-rp-live'); if (e) e.scrollIntoView({ block: 'center' }); return true })()`)
  await sleep(200)
  await b.shot(EV + '3a4-real-replay-progress.png')
} catch (e) {
  out.error = String((e && e.stack) || e)
  fail.push('probe-exception: ' + out.error)
} finally {
  await b.close()
}

out.fail = fail
console.log(JSON.stringify(out, null, 2))
process.exit(fail.length ? 1 : 0)
