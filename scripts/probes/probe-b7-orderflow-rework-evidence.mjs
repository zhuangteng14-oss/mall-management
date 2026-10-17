// probe-b7-orderflow-rework-evidence.mjs —— 状态流转页「核心数据流与交互彻底重构」验收取证。
//
// 对照用户验收标准，逐条取证（真后端 8088 + dev server 8080）：
//   ① 压测时数据**错落有致**地长（每 600ms 采样一次，看捕获数/五态数逐拍递增，不是一帧爆满）；
//      趋势右端每 3 秒长一格（读组件里的 `liveTail`，同时截图）。
//   ② SSE 流轨有光点在动（每帧数 `.a-of-particle-wrap`；流列表的 to 不再全是「待付款」）。
//   ③ 刷新页面历史不丢（Page.reload 后捕获数从快照恢复）；抽屉搜索/高亮/时间线状态正确
//      （待收货单的时间线当前节点必须是「发货 → 待收货」，不是「待付款」）。
//
// 产物：
//   scripts/probes/out-b7-orderflow-rework/frames/*.png   —— 30 帧（600ms/帧）
//   scripts/probes/out-b7-orderflow-rework/*.png          —— 验收关键页截图
//   scripts/probes/out-b7-orderflow-rework/samples.json   —— 每次采样的原始读数
//   证据与 GIF 由后续命令复制到 docs/agent/evidence/c3-block7/。
//
// 跑法：后端 dev profile 8088（健康 200）+ `npm run serve` 8080
//       `node scripts/probes/probe-b7-orderflow-rework-evidence.mjs`
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const BE = 'http://localhost:8088'
const ROUTE = '#/admin/observe/timeline'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7-orderflow-rework'
const FRAMES = path.join(OUT, 'frames')
fs.mkdirSync(FRAMES, { recursive: true })

const R = report('块 7 · 状态流转页重构验收（真后端）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }
const samples = []

const b = await open({ w: 1440, h: 1000 })

// ⚠️ 清一次存储就够了：注入脚本每次导航都会跑，若无条件清空，刷新恢复会被探针自己抹掉。
await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    try {
      if (!sessionStorage.getItem('__probe_b7_boot')) {
        Object.keys(sessionStorage)
          .filter((k) => k.indexOf('mall:orderFlow:') === 0)
          .forEach((k) => sessionStorage.removeItem(k))
        sessionStorage.setItem('__probe_b7_boot', '1')
      }
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

const TEXT = (sel) => `[...document.querySelectorAll('${sel}')].map(e => (e.innerText ?? e.textContent ?? '').trim())`

// 一次采样：同时读「用户看到的 DOM」与「组件内部状态」（liveTail / capturedTotal），两路互相校验。
const SAMPLE_JS = `(() => {
  const q = (s) => document.querySelector(s)
  const txt = (s) => { const e = q(s); return e ? (e.innerText || e.textContent || '').trim() : '' }
  const stats = [...document.querySelectorAll('.a-stat b')].map(e => e.innerText.trim())
  const lane = [...document.querySelectorAll('.a-of-lane-node em')].map(e => e.innerText.trim())
  const rows = [...document.querySelectorAll('.a-of-stream-i')].slice(0, 6).map(e => e.innerText.replace(/\\s+/g, ' ').trim())
  const hist = q('.a-of-hist')
  const can = hist ? hist.querySelector('canvas') : null
  const el = q('.a-of')
  const ss = el && el.__vueParentComponent && el.__vueParentComponent.setupState
  const liveTail = ss && Array.isArray(ss.liveTail) ? ss.liveTail.map(p => [p.label, p.waitPay, p.waitShip, p.waitReceive]) : null
  const captured = ss && typeof ss.capturedTotal === 'number' ? ss.capturedTotal : null
  const counts = ss && ss.counts ? JSON.parse(JSON.stringify(ss.counts)) : null
  return {
    phase: txt('.a-chip-live'),
    seq: Number(((txt('.a-tz').match(/第\\s*(\\d+)\\s*拍/) || [])[1]) || 0),
    totalText: stats[0] || '',
    capturedText: stats[1] || '',
    captured,
    counts,
    lane,
    rows,
    particles: document.querySelectorAll('.a-of-particle-wrap').length,
    histH: hist ? Math.round(hist.getBoundingClientRect().height) : 0,
    histCanvas: can ? [can.width, can.height] : null,
    liveTail,
    filterOn: !!(q('.a-of-filter input') && q('.a-of-filter input').checked),
    errs: (window.__errs || []).slice(-3),
  }
})()`

async function sample() {
  const s = await b.evalJS(SAMPLE_JS)
  samples.push({ at: Date.now(), ...s })
  return s
}

try {
  await b.send('Page.navigate', { url: `${WEB}/admin.html${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.a-of')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 页面挂载', mounted === true)

  // 先制造一段「安静的库」：停掉上一条压测，等派发线程与随机旅程尾巴排空；
  // 再清掉本页快照重开，验证「新连接不会重放环里的历史」——上一轮压测的尾事件
  // 若还在飞，会把 captured 从 0 抬到个位数，那不是重放，是连接之后真实发生的事。
  await b.evalJS(`fetch('${BE}/admin/observe/attack/stop', { method: 'POST', credentials: 'include' }).then(r=>r.text()).catch(()=>'')`)
  await sleep(8000)
  await b.evalJS(`(() => {
    Object.keys(sessionStorage).filter(k => k.indexOf('mall:orderFlow:') === 0).forEach(k => sessionStorage.removeItem(k))
    return true
  })()`)
  await b.send('Page.reload', { ignoreCache: false })
  for (let i = 0; i < 120; i++) {
    const m = await b.evalJS(`!!document.querySelector('.a-of')`).catch(() => false)
    if (m) break
    await sleep(250)
  }
  await sleep(4000)

  const boot = await sample()
  const dbTotal = Number(String(boot.totalText).replace(/,/g, '')) || 0
  ok('① 新连接「本会话捕获」≈0，而库内订单总数是三位数以上（历史没有被灌进实时流）',
    Number(boot.captured || 0) <= 2 && dbTotal > 100,
    { captured: boot.captured, text: boot.capturedText, dbTotal })
  ok('① 连上真流（已连接）且已推进 ≥2 拍', boot.phase === '已连接' && boot.seq >= 2,
    { phase: boot.phase, seq: boot.seq })
  ok('① 直方图容器有固定高度（空/满都不塌成白板）', boot.histH >= 180,
    { h: boot.histH, canvas: boot.histCanvas })

  // 新口径（用户 2026-10-17 纠正）：默认**全量**（含压测单 / 已取消单），开关默认关。
  const sw = await b.evalJS(`(() => {
    const cb = document.querySelector('.a-of-filter input')
    const swEl = document.querySelector('.a-of-filter .a-of-switch')
    const label = ((document.querySelector('.a-of-filter') || {}).innerText || '').replace(/\\s+/g, ' ').trim()
    return { exists: !!cb, checked: !!(cb && cb.checked), hasSwitch: !!swEl, label }
  })()`)
  ok('② 口径开关存在且默认关（全量展示，压测压迫感先上屏）',
    sw.exists && sw.hasSwitch && sw.checked === false, sw)
  ok('② 开关文案 =「剔除已取消 / 仅看真实数据」',
    sw.label.includes('剔除已取消') && sw.label.includes('仅看真实数据'), sw.label)
  ok('② 默认全量的环形图占比不算极端（max < 95% ⇒ minAngle 不启用）',
    (() => {
      const vals = Object.entries(boot.counts || {})
        .filter(([k]) => k !== 'total' && Number.isFinite(Number(boot.counts[k])))
        .map(([, v]) => Number(v)).filter((v) => v > 0)
      if (!vals.length) return true
      return Math.max(...vals) / vals.reduce((a, b) => a + b, 0) < 0.95
    })(), boot.counts)

  // 把趋势+流轨滚进视口，整段 GIF 都拍这一屏。
  await b.evalJS(`(() => { const e = document.querySelector('.a-of-hero'); if (e) e.scrollIntoView({ block: 'start' }); return true })()`)
  await sleep(400)
  await b.shot(path.join(OUT, '01-before-attack.png'))

  // 真压测：orderBurst 100%，24 并发 / 80 QPS / 24 秒（引擎内部对每单再加 1–5 秒随机旅程延迟）。
  const started = await b.evalJS(`(() => {
    const body = new URLSearchParams()
    body.set('name', 'b7-orderflow-evidence')
    body.set('concurrency', '24')
    body.set('durationSeconds', '24')
    body.set('targetQps', '80')
    body.set('scenarios', JSON.stringify([{ key: 'orderBurst', weight: 100 }]))
    window.__attackState = 'running'
    window.__attackResp = ''
    fetch('${BE}/admin/observe/attack/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
      credentials: 'include',
    }).then(r => r.json()).then(j => {
      window.__attackState = 'done'
      window.__attackResp = JSON.stringify(j).slice(0, 500)
    }).catch(e => { window.__attackState = 'error'; window.__attackResp = String(e) })
    return true
  })()`)
  ok('② 压测请求已发出', started === true)

  // 30 帧 × 600ms ≈ 18 秒（另加截图开销），覆盖建单 + 随机延迟后的付款/发货/收货跃迁。
  const frames = []
  for (let i = 0; i < 30; i++) {
    await sleep(600)
    const s = await sample()
    const f = path.join(FRAMES, `f${String(i).padStart(2, '0')}.png`)
    await b.shot(f)
    frames.push({ i, file: f, at: new Date().toISOString(), captured: s.captured, particles: s.particles, seq: s.seq, counts: s.counts, rows: s.rows, liveTail: s.liveTail })
    if (i === 7) await b.shot(path.join(OUT, '02-load-early.png'))
    if (i === 24) await b.shot(path.join(OUT, '03-load-late.png'))
  }
  fs.writeFileSync(path.join(OUT, 'samples.json'), JSON.stringify({ samples, frames }, null, 2))

  // ── 验收 ①：错落有致（逐拍递增）───────────────────────────────────────────
  const cap = samples.map((s) => Number(s.captured || 0))
  const maxStep = cap.reduce((m, v, i) => (i ? Math.max(m, v - cap[i - 1]) : m), 0)
  const grown = cap[cap.length - 1] - cap[0]
  ok('① 捕获数在压测期间持续增长（不是 0 或一帧爆满）',
    grown > 30, { first: cap[0], last: cap[cap.length - 1], grown })
  ok('① 任一 600ms 窗口的增量都远小于总量（错落有致，不瞬间突变）',
    maxStep <= Math.max(30, Math.round(grown * 0.4)), { maxStep, grown })
  const distinctCountVectors = new Set(samples.map((s) => JSON.stringify(s.counts))).size
  ok('① 五态计数出现了 ≥8 种不同组合（逐拍在变，不是截图造图）', distinctCountVectors >= 8, distinctCountVectors)
  const tails = samples.map((s) => (s.liveTail || []).length)
  const tailGrew = Math.max(...tails) > Math.min(...tails)
  ok('① 趋势实时尾窗在生长（liveTail 点数从少到多，最多 10 格）', tailGrew, { min: Math.min(...tails), max: Math.max(...tails) })
  const laneVectors = new Set(samples.map((s) => JSON.stringify(s.lane))).size
  ok('① 阶梯/轨道节点数字同步在变（≥8 种组合）', laneVectors >= 8, laneVectors)

  // ── 验收 ②：流轨真的在动 ────────────────────────────────────────────────
  const particleFrames = samples.filter((s) => s.particles > 0).length
  ok('② 有光点在轨道上移动（≥10 帧拍到粒子）', particleFrames >= 10, { particleFrames, maxParticles: Math.max(...samples.map((s) => s.particles)) })
  const allRowText = samples.flatMap((s) => s.rows).join(' | ')
  const toStates = ['待发货', '待收货', '已完成'].filter((x) => allRowText.includes('→ ' + x) || allRowText.includes('→' + x))
  ok('② 流列表的 to 不再是清一色「待付款」（付款/发货/收货都出现）', toStates.length >= 2, toStates)
  const notAllWaitPay = samples.some((s) => s.rows.some((r) => /→\s*(待发货|待收货|已完成)/.test(r)))
  ok('② 截图中能读到「→ 待发货 / → 待收货 / → 已完成」的流转', notAllWaitPay === true)

  // 压测请求的收尾状态必须**在导航前**读（页面一导航，window 上下文就换了）。
  // 帧循环的墙钟取决于截图耗时，可能恰好差几秒没跑完 ⇒ 先等它收尾，别把「还在跑」当失败。
  let attackState = { state: 'running', resp: '' }
  for (let i = 0; i < 40 && attackState.state === 'running'; i++) {
    attackState = await b.evalJS(`({ state: window.__attackState, resp: window.__attackResp })`)
    if (attackState.state === 'running') await sleep(500)
  }
  ok('② 压测正常结束（HTTP 200，非 error）', attackState.state === 'done', attackState)

  // ── 验收 ③ 刷新恢复 ─────────────────────────────────────────────────────
  const beforeReload = await sample()
  await b.shot(path.join(OUT, '04-before-reload.png'))
  const capBefore = Number(beforeReload.captured || 0)
  const tailBefore = (beforeReload.liveTail || []).length
  await b.send('Page.reload', { ignoreCache: false })
  let restored = null
  for (let i = 0; i < 100; i++) {
    await sleep(150)
    const v = await b.evalJS(`(() => {
      if (!document.querySelector('.a-of')) return null
      const el = document.querySelector('.a-of')
      const ss = el && el.__vueParentComponent && el.__vueParentComponent.setupState
      if (!ss) return null
      const txt = [...document.querySelectorAll('.a-stat b')].map(e => e.innerText.trim())
      return {
        captured: typeof ss.capturedTotal === 'number' ? ss.capturedTotal : null,
        capturedText: txt[1] || '',
        seq: ss.seq,
        liveTail: Array.isArray(ss.liveTail) ? ss.liveTail.length : -1,
        phase: (document.querySelector('.a-chip-live') || {}).innerText || '',
      }
    })()`).catch(() => null)
    if (v && v.captured !== null && (v.captured > 0 || v.capturedText !== '0')) { restored = v; break }
  }
  ok('③ 刷新后「本会话捕获」从 sessionStorage 恢复（不是归零）',
    !!restored && restored.captured >= capBefore, { capBefore, restored })
  ok('③ 刷新后趋势尾窗也恢复（liveTail 不为空）', !!restored && restored.liveTail > 0,
    { tailBefore, restoredTail: restored && restored.liveTail })
  await sleep(2500)
  const afterReload = await sample()
  // ⚠️ `seq` 是**每条连接自己的拍号**（新连接从 0 起），刷新后 UI 显示「第 1 拍」是正确行为；
  //    要验的不是它继续数到 17，而是连接重新活着、捕获数从快照续上。
  ok('③ 刷新后流重新连上并继续推进（新连接拍号从 0 起，已连接）',
    afterReload.phase === '已连接' && afterReload.seq >= 1,
    { phase: afterReload.phase, seq: afterReload.seq, restoredSeq: restored && restored.seq })
  ok('③ 刷新后捕获数不回退（继续只增）',
    Number(afterReload.captured || 0) >= (restored ? restored.captured : capBefore),
    { restored: restored && restored.captured, after: afterReload.captured })
  await b.shot(path.join(OUT, '05-after-refresh.png'))

  // ── 路由切换（KeepAlive）：去指标页再回来，捕获数不丢 ─────────────────────
  const capA = Number((await sample()).captured || 0)
  await b.send('Page.navigate', { url: `${WEB}/admin.html#/admin/observe/metrics` })
  await sleep(1800)
  await b.send('Page.navigate', { url: `${WEB}/admin.html${ROUTE}` })
  let backOk = false
  for (let i = 0; i < 60; i++) {
    await sleep(200)
    backOk = await b.evalJS(`!!document.querySelector('.a-of')`).catch(() => false)
    if (backOk) break
  }
  await sleep(1000)
  const capB = Number((await sample()).captured || 0)
  ok('③ 切页再切回（KeepAlive/快照）捕获数不归零', backOk && capB >= capA, { capA, capB })

  // ── 新口径开关：全量 → 健康业务分布 ──────────────────────────────────────
  const fullSample = await sample()
  const fullTotal = Number(fullSample.counts && fullSample.counts.total) || 0
  const fullCancelled = Number(fullSample.counts && fullSample.counts.cancelled) || 0
  const turnedOn = await b.evalJS(`(() => {
    const cb = document.querySelector('.a-of-filter input')
    if (!cb) return { ok: false }
    if (!cb.checked) cb.click()
    return { ok: true, checked: cb.checked }
  })()`)
  await sleep(3000)
  const healthySample = await sample()
  const healthyTotal = Number(healthySample.counts && healthySample.counts.total) || 0
  const healthyCancelled = Number(healthySample.counts && healthySample.counts.cancelled) || 0
  ok('② 开关打开：计数口径切到「健康业务分布」，总量显著收窄',
    turnedOn.ok && turnedOn.checked === true && healthyTotal > 0 && healthyTotal < fullTotal * 0.5,
    { fullTotal, healthyTotal })
  ok('② 开关打开后「已取消」不再进入计数（环形图 / 阶梯回到健康分布）',
    fullCancelled > 0 && healthyCancelled === 0,
    { fullCancelled, healthyCancelled })
  ok('② 开关打开后计数里仍有在途/完成各态（不是被一刀切成空表）',
    ['waitShip', 'waitReceive', 'completed'].some((k) => Number(healthySample.counts && healthySample.counts[k]) > 0),
    healthySample.counts)
  await b.shot(path.join(OUT, '09-healthy-distribution.png'))

  // 环图防挤爆：构造 99% 一态（注入组件计数，不改库），验证 DonutChart 收到 minAngle=10。
  // 注入前后各读一次 option；SSE 每秒会覆盖注入值，故这里短暂断网取稳定一帧。
  await b.send('Network.enable')
  await b.send('Network.emulateNetworkConditions', {
    offline: true, latency: 0, downloadThroughput: 0, uploadThroughput: 0,
  })
  const angle = await b.evalJS(`(async () => {
    const ss = document.querySelector('.a-of').__vueParentComponent.setupState
    const before = ss.donutMinAngle
    ss.counts = { waitPay: 0, waitShip: 10, waitReceive: 10, completed: 10, cancelled: 9970, total: 10000 }
    await new Promise(r => setTimeout(r, 250))
    const dc = document.querySelector('.a-of-flow-dist .dc').__vueParentComponent.setupState
    const series = dc && dc.option ? dc.option.series[0] : null
    return {
      before,
      after: ss.donutMinAngle,
      seriesMin: series ? series.minAngle : null,
      nonzero: series ? series.data.filter(d => d.value > 0).length : 0,
      values: series ? series.data.map(d => d.value) : [],
    }
  })()`)
  ok('③ 99% 一态时 donutMinAngle 从 0 切到 10°（其余四态仍可见）',
    angle.before === 0 && angle.after === 10 && angle.seriesMin === 10 && angle.nonzero >= 2,
    angle)
  await b.shot(path.join(OUT, '10-donut-minangle-99.png'))
  await b.send('Network.emulateNetworkConditions', {
    offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1,
  })
  await sleep(2500)

  // ── 抽屉：搜索 / 高亮 / 时间线状态 ───────────────────────────────────────
  // 先把主从筛选切到「待收货」：抽屉候选池按当前筛选取前 30 张**最慢**的单，
  // 不筛的话 1000+ 行里最慢的 30 张可能全是历史待发货/待付款，稳稳定不到待收货样本。
  const filtered = await b.evalJS(`(() => {
    const k = [...document.querySelectorAll('.a-of-kpi')]
      .find(e => (e.innerText || '').includes('待收货'))
    if (!k) return false
    k.click()
    return true
  })()`)
  await sleep(500)
  ok('③ 主从筛选切到「待收货」', filtered === true, filtered)

  // 从全局明细表点一行打开抽屉。
  // 明细表的行没有类名（`tr` 上只有 `is-jump/is-timeout`），用行内「链路」按钮开抽屉。
  const opened = await b.evalJS(`(() => {
    const btn = document.querySelector('.a-of-op')
    if (!btn) return false
    btn.click()
    return true
  })()`)
  await sleep(500)
  const drawerOpen = await b.evalJS(`!!document.querySelector('.a-of-drawer')`)
  ok('③ 点表格行打开链路抽屉', opened === true && drawerOpen === true, { opened, drawerOpen })

  if (drawerOpen) {
    const firstId = await b.evalJS(`((document.querySelector('.a-of-drawer-i .a-of-id') || {}).innerText || '').replace('#','').trim()`)
    // 按订单号搜索
    const byId = await b.evalJS(`(async () => {
      const inp = document.querySelector('.a-of-search')
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      set.call(inp, ${JSON.stringify(firstId)})
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      await new Promise(r => setTimeout(r, 400))
      const rows = [...document.querySelectorAll('.a-of-drawer-i')]
      return {
        n: rows.length,
        ids: rows.map(r => ((r.querySelector('.a-of-id') || {}).innerText || '').replace('#','').trim()),
        prods: rows.map(r => ((r.querySelector('.a-of-drawer-g') || {}).innerText || '').trim()),
        now: (document.querySelector('.a-of-search-n') || {}).innerText || '',
      }
    })()`)
    ok('③ 按订单号搜索命中且列表收敛', byId.n > 0 && byId.ids.every((x) => x === firstId), byId)
    await b.shot(path.join(OUT, '06-drawer-search-id.png'))

    // 按商品名搜索
    const prod = await b.evalJS(`(() => {
      const el = document.querySelector('.a-of-drawer-g')
      return el ? el.innerText.trim() : ''
    })()`)
    const byProd = await b.evalJS(`(async () => {
      const inp = document.querySelector('.a-of-search')
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      set.call(inp, ${JSON.stringify(prod)})
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      await new Promise(r => setTimeout(r, 400))
      const rows = [...document.querySelectorAll('.a-of-drawer-i')]
      return {
        n: rows.length,
        matched: rows.every(r => {
          const id = ((r.querySelector('.a-of-id') || {}).innerText || '').toLowerCase()
          const g = ((r.querySelector('.a-of-drawer-g') || {}).innerText || '').toLowerCase()
          const q = ${JSON.stringify(prod.toLowerCase())}
          return id.includes(q) || g.includes(q)
        }),
      }
    })()`)
    ok('③ 按商品名搜索命中', !!prod && byProd.n > 0 && byProd.matched === true, { prod, ...byProd })

    // 清空搜索，点第二行 → 高亮 + 右侧详情切换
    const pick = await b.evalJS(`(async () => {
      const inp = document.querySelector('.a-of-search')
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      set.call(inp, '')
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      await new Promise(r => setTimeout(r, 300))
      const rows = [...document.querySelectorAll('.a-of-drawer-i')]
      if (rows.length < 2) return { ok: false, n: rows.length }
      const target = rows[1]
      const id = ((target.querySelector('.a-of-id') || {}).innerText || '').replace('#','').trim()
      target.click()
      await new Promise(r => setTimeout(r, 300))
      const on = [...document.querySelectorAll('.a-of-drawer-i.on')]
      return {
        ok: on.length === 1,
        id,
        onId: on.length ? ((on[0].querySelector('.a-of-id') || {}).innerText || '').replace('#','').trim() : '',
        headerId: ((document.querySelector('.a-of-drawer-oh .a-of-id') || {}).innerText || '').replace('#','').trim(),
      }
    })()`)
    ok('③ 点击左列行只有一个「深蓝底」高亮，且右列头部同步切换',
      pick.ok === true && pick.onId === pick.id && pick.headerId === pick.id, pick)
    await b.shot(path.join(OUT, '07-drawer-highlight.png'))

    // 找一张「待收货」单：时间线当前节点必须是第 3 步（发货），前 3 步走过、最后一步灰。
    const waitRecv = await b.evalJS(`(async () => {
      const rows = [...document.querySelectorAll('.a-of-drawer-i')]
      const target = rows.find(r => ((r.querySelector('.a-of-st') || {}).innerText || '').includes('待收货'))
      if (!target) return { found: false }
      target.click()
      await new Promise(r => setTimeout(r, 400))
      const items = [...document.querySelectorAll('.a-of-tl-i')]
      return {
        found: true,
        id: ((target.querySelector('.a-of-id') || {}).innerText || '').replace('#','').trim(),
        headerId: ((document.querySelector('.a-of-drawer-oh .a-of-id') || {}).innerText || '').replace('#','').trim(),
        n: items.length,
        done: items.filter(x => x.classList.contains('is-done')).length,
        future: items.filter(x => x.classList.contains('is-future')).length,
        current: (document.querySelector('.a-of-tl-i.is-current .a-of-tl-d b') || {}).innerText || '',
        currentSmall: (document.querySelector('.a-of-tl-i.is-current .a-of-tl-d small') || {}).innerText || '',
        texts: items.map(x => x.innerText.replace(/\\s+/g, ' ').trim()),
      }
    })()`)
    ok('③ 待收货单的时间线：4 步齐全、前 3 步点亮、最后 1 步灰',
      waitRecv.found && waitRecv.n === 4 && waitRecv.done === 3 && waitRecv.future === 1,
      { found: waitRecv.found, n: waitRecv.n, done: waitRecv.done, future: waitRecv.future })
    ok('③ 待收货单的当前节点是「发货 → 待收货」，**不是**「创建订单（待付款）」',
      waitRecv.found && waitRecv.current.includes('发货') && waitRecv.currentSmall === '当前状态',
      { current: waitRecv.current, small: waitRecv.currentSmall })
    ok('③ 抽屉右列与左列选中的是同一张单', waitRecv.found && waitRecv.id === waitRecv.headerId,
      { id: waitRecv.id, header: waitRecv.headerId })
    await b.shot(path.join(OUT, '08-drawer-timeline-waitreceive.png'))

    // 清空搜索后左侧行仍可点（搜完不会把列表弄死）。
    const alive = await b.evalJS(`(async () => {
      const inp = document.querySelector('.a-of-search')
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      set.call(inp, '')
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      await new Promise(r => setTimeout(r, 300))
      const rows = [...document.querySelectorAll('.a-of-drawer-i')]
      if (!rows.length) return false
      rows[0].click()
      await new Promise(r => setTimeout(r, 250))
      return document.querySelectorAll('.a-of-drawer-i.on').length === 1
    })()`)
    ok('③ 清空搜索后仍可正常选中（高亮唯一）', alive === true, alive)
    await b.evalJS(`(() => { const x = [...document.querySelectorAll('.a-of-drawer-hd .a-btn')][0]; if (x) x.click(); return true })()`)
    await sleep(300)
    ok('③ 抽屉可关闭', (await b.evalJS(`!!document.querySelector('.a-of-drawer')`)) === false)
  }

  // 场次收尾：停止压测（若已自然结束则 /stop 返回「没有在跑」也无妨）。
  await b.evalJS(`fetch('${BE}/admin/observe/attack/stop', { method: 'POST', credentials: 'include' }).then(r=>r.text()).catch(()=>'')`)

  const errs = await b.evalJS('(window.__errs || [])')
  ok('全流程无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 5))
} catch (e) {
  console.log('探针中途异常：', (e && e.stack) || e)
} finally {
  const bad = R.dump()
  fs.writeFileSync(path.join(OUT, 'probe-report.txt'), `共 ${TOTAL} 项断言，失败 ${bad} 项\n`)
  console.log(`\n产物目录：${OUT}`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
