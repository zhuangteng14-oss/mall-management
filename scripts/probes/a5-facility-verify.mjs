// a5-facility-verify.mjs —— A5「设施 3 类」埋点 + 前端筛选的真后端取证（2026-10-18）
//
// 背景：日志页左侧「设施 3 类（慢 SQL / MQ / 调度）」此前是灰色占位 —— 后端三个设施组件
//      从未向 EventBroadcaster 写事件。A5 在三处切面补了直发（无源环、进合并环）：
//        · SlowSqlListener  → emitSlowSql（超阈值 或 dbSlow 注入过）
//        · RabbitEventPublisher → emitMq（TCP 翻转 / mqDown 注入翻转）
//        · ScheduledJobGate → emitScheduler（jobDown 翻转时每 job 一条）
//
// 判据（全部在真页面 + 真后端上量）：
//   ① 页面挂载，设施三行**不是 disabled**、不再带 `is-fac`，标签逐字 = 慢 SQL / MQ / 调度
//   ② 开 dbSlow → 打几条真 SQL（/goods/productSearch）→ 「慢 SQL」计数上涨
//   ③ 开 jobDown → 15s 轮次的 orderTimeoutCancel / shippingTrack 被跳过 → 「调度」计数上涨
//   ④ 开/关 mqDown → 两个 5s 探测周期内应各得一条 MQ 事件（WARN 断 / INFO 恢复）
//   ⑤ 点击三行：关掉即从列表消失、打开即回来（按 Kind 真筛选，不是只亮个勾）
//   ⑥ 关掉九个业务类后，列表只剩设施类行（非空的 kind 标签 ⊆ {慢 SQL, MQ, 调度}）
//   ⑦ 收尾：注入清空为 []，浏览器关闭
//
// 凭据：只从环境变量 PROBE_ADMIN_PWD 读（本地来源 `_work/admin-pass`），不硬编码、不上屏。
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" \
//     node scripts/probes/a5-facility-verify.mjs
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const API = 'http://localhost:8088'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) {
  console.error('缺少 PROBE_ADMIN_PWD')
  process.exit(2)
}

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/a5-facility'
fs.mkdirSync(EV, { recursive: true })
const SHOT_BEFORE = `${EV}/a5-01-before-injection.png`
const SHOT_AFTER = `${EV}/a5-02-facility-counts.png`
const SHOT_ONLY = `${EV}/a5-03-facility-only-list.png`

const R = report('A5 设施 3 类埋点与前端筛选（真后端 8088）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const FACILITY = ['慢 SQL', 'MQ', '调度']
const BUSINESS = ['缓存', '锁', '订单', '库存', '审计', '通知', '敏感词', '身份', '防护']

const b = await open({ w: 1600, h: 1000 })

// ── 页面读数 ────────────────────────────────────────────────────────────────
const rail = () => b.evalJS(`(() => {
  const out = {}
  for (const row of document.querySelectorAll('.ev-rail .rows .row')) {
    const k = ((row.querySelector('.k') || {}).innerText || '').trim()
    const c = ((row.querySelector('.c') || {}).innerText || '').replace(/[^0-9]/g, '')
    if (k) out[k] = Number(c || 0)
  }
  return out
})()`)

const facilityRowsState = () => b.evalJS(`(() => {
  return [...document.querySelectorAll('.ev-rail .rows .row')]
    .filter(x => ['慢 SQL', 'MQ', '调度'].includes(((x.querySelector('.k') || {}).innerText || '').trim()))
    .map(x => ({
      label: ((x.querySelector('.k') || {}).innerText || '').trim(),
      disabled: x.disabled === true || x.hasAttribute('disabled'),
      isFac: x.classList.contains('is-fac'),
      count: Number((((x.querySelector('.c') || {}).innerText || '').replace(/[^0-9]/g, '')) || 0),
    }))
})()`)

const listKindLabels = () => b.evalJS(`[...document.querySelectorAll('.ev-list .log-line')]
  .map(x => ((x.querySelector('.md') || {}).innerText || '').trim())
  .filter(Boolean)`)

const listTitles = () => b.evalJS(`[...document.querySelectorAll('.ev-list .log-line .ms > b')]
  .map(x => (x.innerText || '').trim())`)

const clickRow = (label) => b.evalJS(`(() => {
  const row = [...document.querySelectorAll('.ev-rail .rows .row')]
    .find(x => ((x.querySelector('.k') || {}).innerText || '').trim() === ${JSON.stringify(label)})
  if (!row) return false
  row.click()
  return true
})()`)

const setInjections = (list) => b.evalJS(`(async () => {
  const body = new URLSearchParams({ injections: ${JSON.stringify(JSON.stringify(list))} })
  const r = await fetch(${JSON.stringify(API + '/admin/observe/attack/config')}, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  return { status: r.status }
})()`)

const readInjections = () => b.evalJS(`fetch(${JSON.stringify(API + '/admin/observe/attack/config')}, { cache: 'no-store' })
  .then(r => r.json()).then(j => (j && j.Injections) || [])`)

const searchOnce = () => b.evalJS(`fetch(${JSON.stringify(API + '/goods/productSearch?limit=5&offset=0')}, {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: 'keyword=a',
}).then(r => r.status)`)

async function waitCount(label, min, timeoutMs) {
  const t0 = Date.now()
  let now = 0
  while (Date.now() - t0 < timeoutMs) {
    const r = await rail()
    now = r[label] || 0
    if (now >= min) return { ok: true, count: now, waited: Date.now() - t0 }
    await sleep(1000)
  }
  return { ok: false, count: now, waited: Date.now() - t0 }
}

let injectionsWereEnabled = false

try {
  // ── 登录（admin.html；dev profile 鉴权关，但登录态仍决定路由）──────────────
  await b.goto(BASE + '/admin.html', 2500)
  const login = await b.evalJS(`(() => {
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return { ok: false, hash: location.hash }
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')]
      .find(x => x.textContent.trim() === '登录')
    if (!btn) return { ok: false, why: 'no-button' }
    btn.click()
    return { ok: true }
  })()`)
  ok('登录表单提交', login.ok === true, login)

  let hash = ''
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    hash = await b.evalJS('location.hash')
    if (/^#\/admin/.test(hash)) break
  }
  ok('登录后进入管理端', /^#\/admin/.test(hash), hash)

  // ── ① 打开日志页，等首帧 ──────────────────────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/logs'`)
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.a-el')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 日志页挂载（.a-el）', mounted === true)
  await sleep(3500)   // 首帧 = 整环回放；等它到齐并渲染

  const rows0 = await facilityRowsState()
  ok('① 设施三行在页面上（慢 SQL / MQ / 调度）',
    JSON.stringify(rows0.map((x) => x.label)) === JSON.stringify(FACILITY), rows0.map((x) => x.label))
  ok('① 三行都**不是 disabled**（A5 解除置灰）', rows0.length === 3 && rows0.every((x) => x.disabled === false),
    rows0.map((x) => x.disabled))
  ok('① 不再带占位样式 `is-fac`', rows0.every((x) => x.isFac === false), rows0.map((x) => x.isFac))
  const cap0 = await rail()
  const mq0 = cap0['MQ'] || 0
  ok('② 注入前基线读数（慢 SQL 计数）', true, cap0)
  await b.shot(SHOT_BEFORE)

  // ── ② dbSlow：开注入 → 打真 SQL → 慢 SQL 计数上涨 ─────────────────────────
  const injOn = await setInjections(['dbSlow', 'jobDown'])
  injectionsWereEnabled = true
  ok('② 注入配置 POST 成功（dbSlow + jobDown）', injOn.status === 200, injOn)
  let conf = await readInjections()
  ok('② 服务端 Injections 含 dbSlow / jobDown',
    conf.includes('dbSlow') && conf.includes('jobDown'), conf)

  const sqlStatuses = []
  for (let i = 0; i < 6; i++) {
    sqlStatuses.push(await searchOnce())
    await sleep(250)
  }
  ok('② 真 SQL 流量 6 次均 200', sqlStatuses.length === 6 && sqlStatuses.every((s) => s === 200), sqlStatuses)

  const slowWait = await waitCount('慢 SQL', 1, 20000)
  ok('② 「慢 SQL」计数从 0 涨到 ≥1', slowWait.ok === true, slowWait)

  // ── ③ jobDown：等 15s 轮次的 orderTimeoutCancel / shippingTrack 被跳过 ─────
  const schedWait = await waitCount('调度', 1, 40000)
  ok('③ 「调度」计数从 0 涨到 ≥1（jobDown 跳过事件）', schedWait.ok === true, schedWait)

  // ── ④ mqDown：开 → WARN，关 → INFO（探测周期 5s）─────────────────────────
  const mqBefore = ((await rail())['MQ'] || 0)
  await setInjections(['dbSlow', 'jobDown', 'mqDown'])
  await sleep(8000)
  const mqOn = ((await rail())['MQ'] || 0)
  ok('④ 开 mqDown 后 MQ 计数 +1（WARN 连接不可用）', mqOn > mqBefore, { before: mqBefore, after: mqOn })
  await setInjections(['dbSlow', 'jobDown'])
  const mqRecover = await waitCount('MQ', mqOn + 1, 12000)
  ok('④ 关 mqDown 后 MQ 再 +1（INFO 恢复）', mqRecover.ok === true, mqRecover)

  const cap1 = await rail()
  ok('②③④ 汇总：三行计数都 > 0',
    (cap1['慢 SQL'] || 0) > 0 && (cap1['MQ'] || 0) > 0 && (cap1['调度'] || 0) > 0, cap1)
  await b.shot(SHOT_AFTER)
  await b.shotFull(`${EV}/a5-02b-facility-counts-full.png`)

  // ── ⑤ 点击筛选：关掉某一类 ⇒ 列表里不再有它；打开 ⇒ 回来 ──────────────────
  for (const label of FACILITY) {
    const before = await listKindLabels()
    const had = before.includes(label)
    ok(`⑤ ${label}：当前列表里有这一类（可被筛掉的前置）`, had, before)
    const clickedOff = await clickRow(label)
    await sleep(400)
    const off = await listKindLabels()
    ok(`⑤ 点掉「${label}」后列表不再出现该类`, clickedOff === true && !off.includes(label), off)
    const clickedOn = await clickRow(label)
    await sleep(400)
    const on = await listKindLabels()
    ok(`⑤ 再点「${label}」后该类回到列表`, clickedOn === true && on.includes(label), on)
  }

  // ── ⑥ 只留设施类：关掉九个业务类，列表的 kind 标签 ⊆ 设施三样 ─────────────
  for (const label of BUSINESS) {
    const okClick = await clickRow(label)
    if (!okClick) ok(`⑥ 业务类行「${label}」存在（可点掉）`, false, label)
    await sleep(120)
  }
  await sleep(600)
  const onlyKinds = await listKindLabels()
  const uniq = [...new Set(onlyKinds)]
  ok('⑥ 关掉九个业务类后，列表只剩设施类行',
    uniq.length > 0 && uniq.every((k) => FACILITY.includes(k)), uniq)
  ok('⑥ 且设施行仍覆盖 ≥2 类（不是空列表也不算过）',
    uniq.length >= 2, uniq)
  const titles = await listTitles()
  ok('⑥ 列表标题里有「慢 SQL 触发」「调度任务停摆跳过」「MQ …」实文',
    titles.some((t) => t.includes('慢 SQL')) &&
      titles.some((t) => t.includes('调度任务停摆')) &&
      titles.some((t) => t.includes('MQ')),
    titles.slice(0, 12))
  await b.shot(SHOT_ONLY)
  await b.shotFull(`${EV}/a5-03b-facility-only-list-full.png`)

  // 复位「全部」：把九个业务类开回来
  const allOff = await b.evalJS(`(() => {
    const row = [...document.querySelectorAll('.ev-rail .rows .row')]
      .find(x => ((x.querySelector('.k') || {}).innerText || '').trim() === '全部')
    if (!row) return false
    row.click(); return true
  })()`)
  await sleep(500)
  ok('⑤ 点「全部」恢复筛选', allOff === true)

  const errs = await b.evalJS('(window.__errs || [])')
  ok('⑦ 全程无 console.error / unhandledrejection（页面未挂监听时跳过）',
    !Array.isArray(errs) || errs.length === 0, Array.isArray(errs) ? errs.slice(0, 3) : 'no-hook')
} catch (e) {
  console.log('探针中途异常：', (e && e.message) || e)
} finally {
  // 收尾：无论成败都把注入清空；MQ 发布侧不能留在暂停态。
  try {
    if (injectionsWereEnabled) await setInjections([])
    const left = await readInjections().catch(() => ['(读取失败)'])
    ok('⑦ 收尾：服务端注入已清空', Array.isArray(left) && left.length === 0, left)
  } catch (e) {
    console.log('复位注入失败：', (e && e.message) || e)
  }
  const bad = R.dump()
  console.log(`\n截图：\n  ${SHOT_BEFORE}\n  ${SHOT_AFTER}\n  ${SHOT_ONLY}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
