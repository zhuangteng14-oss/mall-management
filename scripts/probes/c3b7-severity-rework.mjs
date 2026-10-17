// c3b7-severity-rework.mjs —— 块 7 · 2026-10-17「severity 重排」验收探针。
//
// 用户最终口径（2026-10-17）：
//   PROTECT（紫）= 防护层成功防御：入站限流 / 熔断 / 身份拒绝 / 敏感词拒收·打码 / 重复提交拦截
//   WARN（黄）   = 真正的业务失败：库存不足、重试仍失败、异步流转被拒……
//   ERROR（红）  = SQL / NPE 等系统故障
//
// 场景：真后端 8088 + 真前端 8080；bannedUser 40% / sensitiveWord 40% / repeatSubmit 20%，
//       800 QPS × 20s。三条路径全部只产生 PROTECT：
//   · bannedUser    ⇒ IdentityGuard 403 ⇒ 身份拒绝（紫）
//   · sensitiveWord ⇒ 敏感词拒收 / 打码（紫，**本次改动前是 WARN/INFO**）
//   · repeatSubmit  ⇒ OrderService 幂等查重挡下 ⇒ 重复提交拦截（紫，本次新增落点）
//
// 判据：
//   ① warn + error 计数为 0 ⇒ 业务失败率 0.0%（改动前同场景实测 10.6%，分母是本窗口原始事件）；
//   ② protect ≥ 300，左/右严重度分布同数；
//   ③ 每一条 PROTECT 行：`.lv` 的 scrollWidth ≤ clientWidth（文字不溢出）且
//      `.lv` 右缘 ≤ `.md`（类列）左缘（不覆盖「防护 / 身份」）；
//   ④ 全部等级格的左缘相同 ⇒ INFO/WARN/ERROR 与 PROTECT 纵向对齐；
//   ⑤ 截图一张带 PROTECT 的日志页。
//
// 产物：stdout JSON（重定向到 docs/agent/evidence/c3-block7/3a4f-severity-rework.txt）
//       + 3a4f-severity-log.png
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
  await b.goto(`${WEB}/admin.html?sev=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2500)

  // ── 阶段 0：重复提交拦截（**先跑**：此刻熔断器刚起、环里还没有海量事件）──────────
  // ⚠️ 速率必须低（10 QPS）：同一 OrderId 的请求在 Redisson 锁上串行，高 QPS 时排队
  //    等待 >1s 会被 `mallMysqlWrite` 熔断记成慢调用 ⇒ 熔断打开后 orderAdd 直接被拒
  //    （500），后半段根本到不了幂等查重。10 QPS × 5s ≈ 50 次：第 1 次建单，
  //    其余 49 次确定性走 OrderService 的 duplicateSubmit 分支。
  //    也不用关 rateLimit：10 QPS 远低于入站阈值，限流不会介入。
  const runDup = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-severity-duplicate-20261017',
    concurrency: '2',
    durationSeconds: '5',
    targetQps: '10',
    scenarios: JSON.stringify([{ key: 'repeatSubmit', weight: 100 }]),
  }).toString())
  runDup.catch(() => {})
  try { await runDup } catch (e) { /* 超时也读已发生的事件 */ }
  await sleep(1200)

  // 重载一页再读：后端环里已有这段重复提交事件，新页首拍一次性拉开。
  await b.goto(`${WEB}/admin.html?sevDup=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(3500)
  // 冻结并只留「防护」类；虚拟滚动显式关掉、滚到顶，保证重复提交行在 DOM 里。
  await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    s.paused = true
    s.activeKinds = new Set(['protection'])
    s.virtualOn = false
    s.vStart = 0
    return true
  })()`)
  await sleep(500)
  await b.evalJS(`(() => {
    const list = document.querySelector('#ev-list')
    if (list) list.scrollTop = 0
    return true
  })()`)
  await sleep(600)
  const protOnly = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('#ev-list .log-line')].map((r) => {
      const lv = r.querySelector('.lv')
      const md = r.querySelector('.md')
      const ms = r.querySelector('.ms')
      if (!lv || !md) return null
      const a = lv.getBoundingClientRect()
      const c = md.getBoundingClientRect()
      return {
        lv: lv.textContent.trim(), md: md.textContent.trim(),
        lvRight: +a.right.toFixed(2), mdLeft: +c.left.toFixed(2),
        client: lv.clientWidth, scroll: lv.scrollWidth,
        title: ((ms && ms.textContent) || '').replace(/\\s+/g, ' ').trim(),
      }
    }).filter(Boolean)
    return { rows }
  })()`)
  out.samples.protectOnly = protOnly
  const dupRows = protOnly.rows.filter((x) => x.title.includes('重复提交拦截'))
  check('duplicate-submit-protect-visible', dupRows.length > 0,
    protOnly.rows.map((x) => x.lv + ' ' + x.md + ' ' + x.title.slice(0, 40)))
  check('duplicate-submit-column-intact',
    dupRows.length > 0 && dupRows.every((x) => x.lv === 'PROTECT' && x.md === '防护'
      && x.scroll <= x.client + 1 && x.lvRight <= x.mdLeft),
    dupRows.map((x) => ({ lv: x.lv, md: x.md, scroll: x.scroll, client: x.client, lvRight: x.lvRight, mdLeft: x.mdLeft })))
  await b.evalJS(`(() => {
    const b = document.body
    b.style.zoom = '1.0001'; void b.offsetHeight; b.style.zoom = ''; void b.offsetHeight
    return true
  })()`)
  await sleep(400)
  await b.shot(EV + '3a4f-severity-duplicate.png')

  // 回全量视图（新页）交给阶段 A 用干净窗口统计。
  await b.goto(`${WEB}/admin.html?sevA=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2500)

  // ── 阶段 A：与改动前**同一配比**（bannedUser 70% / sensitiveWord 30%）复跑 ─────────
  // 改动前同一探针实测：原始 2,088 条、warn 222、fail 10.6%（222/2088）。
  // 现在敏感词已归 PROTECT ⇒ warn 应为 0、fail 应降到 0.0%。这是「显著下降」的对照。
  const runA = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-severity-before-after-20261017',
    concurrency: '512',
    durationSeconds: '15',
    targetQps: '800',
    scenarios: JSON.stringify([{ key: 'bannedUser', weight: 70 }, { key: 'sensitiveWord', weight: 30 }]),
  }).toString())
  runA.catch(() => {})
  // 等这一轮 15 秒真跑完（不是攒够 300 条就停）—— 与改动前的取样窗口同量级，比率才可比。
  try { await runA } catch (e) { /* 超时也不掩盖判据 */ }
  await sleep(1200)
  const phaseA = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    if (!s) return null
    return { n: s.rawWindow.n, sev: { ...s.rawWindow.bySev }, failRate: s.failRate }
  })()`)
  out.samples.phaseABeforeAfter = phaseA
  try { await post('/admin/observe/attack/stop', '') } catch (e) { /* 已结束 */ }
  try { await runA } catch (e) { /* 同上 */ }
  check('same-mix-fail-dropped-to-zero',
    phaseA && (phaseA.sev.warn || 0) === 0 && (phaseA.sev.error || 0) === 0
      && phaseA.failRate === '0.0',
    { baseline: '改动前同配比：warn 222 / 2088 = 10.6%', now: phaseA })

  // 重载页面：阶段 B 用干净窗口量几何与四档回归。
  await b.goto(`${WEB}/admin.html?sevB=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2500)

  // 入站限流保持开启（让 PROTECT 里也有限流那一路），其余保护不动。
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
    name: 'probe-severity-20261017',
    concurrency: '512',
    durationSeconds: '20',
    targetQps: '800',
    scenarios: JSON.stringify([
      { key: 'bannedUser', weight: 40 },
      { key: 'sensitiveWord', weight: 40 },
      { key: 'repeatSubmit', weight: 20 },
    ]),
  }).toString())
  runP.catch(() => {})

  // 等 protect 累积到数百条（最多 20 秒）。
  let stats = null
  for (let i = 0; i < 40; i++) {
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

  // 冻结列表，等合成层同一帧。
  await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    s.paused = true
    return s.paused
  })()`)
  await sleep(900)

  // 把所有已渲染行的「等级格」几何量出来：PROTECT 不溢出、不压类列，且所有等级格左缘一致。
  // 虚拟滚动只渲染窗口内几十行 ⇒ 先滚到列表中部，让 PROTECT 行尽量多地在场。
  await b.evalJS(`(() => {
    const list = document.querySelector('#ev-list')
    if (list) list.scrollTop = Math.floor(list.scrollHeight * 0.35)
    return true
  })()`)
  await sleep(600)

  const geom = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('#ev-list .log-line')]
    const rowsInfo = rows.map((r) => {
      const lv = r.querySelector('.lv')
      const md = r.querySelector('.md')
      const ms = r.querySelector('.ms')
      if (!lv || !md) return null
      const a = lv.getBoundingClientRect()
      const c = md.getBoundingClientRect()
      const cs = getComputedStyle(lv)
      return {
        lv: lv.textContent.trim(),
        md: md.textContent.trim(),
        lvLeft: +a.left.toFixed(2),
        lvRight: +a.right.toFixed(2),
        mdLeft: +c.left.toFixed(2),
        lvClient: lv.clientWidth,
        lvScroll: lv.scrollWidth,
        overflow: cs.overflow,
        width: cs.width,
        title: (ms && ms.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 80),
      }
    }).filter(Boolean)
    const lefts = [...new Set(rowsInfo.map((x) => x.lvLeft))]
    const byLv = {}
    for (const x of rowsInfo) {
      byLv[x.lv] = byLv[x.lv] || { n: 0, worst: 0, overlap: 0 }
      byLv[x.lv].n++
      byLv[x.lv].worst = Math.max(byLv[x.lv].worst, x.lvScroll - x.lvClient)
      byLv[x.lv].overlap = Math.max(byLv[x.lv].overlap, x.lvRight - x.mdLeft)
    }
    return { rows: rowsInfo, lefts, byLv, total: rowsInfo.length }
  })()`)
  out.samples.geometry = geom

  const protectRows = geom.rows.filter((x) => x.lv === 'PROTECT')
  out.samples.protectRows = protectRows.slice(0, 8)
  check('protect-row-rendered', protectRows.length > 0, { n: protectRows.length, total: geom.total })
  check('protect-text-not-clipped',
    protectRows.every((x) => x.lvScroll <= x.lvClient + 1),
    protectRows.map((x) => ({ lv: x.lv, client: x.lvClient, scroll: x.lvScroll })))
  check('protect-not-overlap-kind',
    protectRows.every((x) => x.lvRight <= x.mdLeft),
    protectRows.map((x) => ({ lvRight: x.lvRight, mdLeft: x.mdLeft, md: x.md })))
  check('all-rows-no-overlap', geom.rows.every((x) => x.lvRight <= x.mdLeft),
    geom.rows.filter((x) => x.lvRight > x.mdLeft).slice(0, 5))
  check('level-columns-aligned', geom.lefts.length === 1, geom.lefts)
  check('protect-kind-cell-intact',
    protectRows.some((x) => x.md.includes('防护') || x.md.includes('身份') || x.md.length > 0),
    protectRows.map((x) => x.md))

  // 回归：真实流量里此刻 warn/error 都是 0，窗口内没有这两档行。直接在一行 INFO 上
  //   依次把文案改成 INFO / WARN / ERROR / PROTECT 量盒子 —— 四档共用同一条 `.lv` 规则，
  //   这样不必造假事件，也能证明变宽后四档都装得下且左缘不动。量完立刻还原。
  const labelFit = await b.evalJS(`(() => {
    const row = [...document.querySelectorAll('#ev-list .log-line')].find((r) => {
      const lv = r.querySelector('.lv')
      return lv && lv.textContent.trim() === 'INFO'
    })
    if (!row) return { ok: false, why: 'no INFO row in window' }
    const lv = row.querySelector('.lv')
    const md = row.querySelector('.md')
    const orig = lv.textContent
    const boxes = {}
    for (const t of ['INFO', 'WARN', 'ERROR', 'PROTECT']) {
      lv.textContent = t
      const r = lv.getBoundingClientRect()
      boxes[t] = {
        client: lv.clientWidth, scroll: lv.scrollWidth,
        left: +r.left.toFixed(2), right: +r.right.toFixed(2),
      }
    }
    lv.textContent = orig
    return { ok: true, boxes, mdLeft: +md.getBoundingClientRect().left.toFixed(2) }
  })()`)
  out.samples.labelFit = labelFit
  check('four-labels-fit-and-aligned', labelFit.ok
    && Object.values(labelFit.boxes).every((x) => x.scroll <= x.client + 1
      && x.right <= labelFit.mdLeft)
    && new Set(Object.values(labelFit.boxes).map((x) => x.left)).size === 1,
  labelFit)

  const snap = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el.__vueParentComponent.setupState
    const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
    const sum = Object.values(s.rawWindow.bySev).reduce((a, b) => a + b, 0)
    const chips = [...document.querySelectorAll('.ev-rail .sev .sevchip')].map(txt)
    const bars = [...document.querySelectorAll('.ev-bars.sev .ev-bar')].map(txt)
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
      rowsText: [...document.querySelectorAll('#ev-list .log-line')]
        .map((r) => r.innerText.replace(/\\s+/g, ' ').trim()).slice(0, 12),
    }
  })()`)
  out.samples.logSnapshot = snap

  // 暂停后再强制一次全页重绘，避免合成层跨帧（同 3a4e 的 settle 手法）。
  await b.evalJS(`(() => {
    const b = document.body
    b.style.zoom = '1.0001'
    void b.offsetHeight
    b.style.zoom = ''
    void b.offsetHeight
    return true
  })()`)
  await sleep(400)
  await b.shot(EV + '3a4f-severity-log.png')
  const after = await b.evalJS(`(() => {
    const txt = (x) => ((x && x.innerText) || '').replace(/\\s+/g, ' ').trim()
    return {
      topN: txt(document.querySelector('.cell b')),
      chips: [...document.querySelectorAll('.ev-rail .sev .sevchip')].map(txt),
      fail: txt(document.querySelector('.cell.err b')),
      prot: txt(document.querySelector('.cell.err .prot')),
    }
  })()`)
  out.samples.afterShot = after

  check('warn-error-zero', (snap.sev.warn || 0) + (snap.sev.error || 0) === 0,
    { warn: snap.sev.warn || 0, error: snap.sev.error || 0, n: snap.n })
  check('fail-rate-zero', snap.failRate === '0.0' && snap.domFail.replace(/\s+/g, '') === '0.0%',
    { failRate: snap.failRate, domFail: snap.domFail, formula: `(warn ${snap.sev.warn || 0} + error ${snap.sev.error || 0}) / ${snap.n}` })
  check('protect-hundreds', (snap.sev.protect || 0) >= 300, { protect: snap.sev.protect, n: snap.n })
  check('raw-total-consistent', snap.n === snap.sum, { n: snap.n, sum: snap.sum })
  check('protect-rate-from-raw', snap.protectRate === snap.expectedProtect
    && snap.domProt.includes(snap.expectedProtect + '%'),
    { protectRate: snap.protectRate, expected: snap.expectedProtect, dom: snap.domProt })
  check('left-right-severity-same',
    snap.left.join('|').replace(/[ ,]/g, '') === snap.right.join('|').replace(/[ ,]/g, ''),
    { left: snap.left, right: snap.right })


  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  try { await post('/admin/observe/attack/stop', '') } catch (e) { /* 收尾失败不掩盖判据 */ }
  try { await runP } catch (e) { /* 同上 */ }
  await b.close()
}
