// probe-b7c5-attack-console.mjs —— 块 7 · commit 5〈攻击模拟器页〉的**渲染 + 交互实测**。
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7c5-attack-console.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ⚠️ **本发自己接管 `Fetch` 拦截，不用 `_cdp.mjs` 的 `mock`**：那个回调是**同步**的
//    （`const r = mock(...) || {…}`，没 await）⇒ 想让它「晚 1.5 秒再答」做不到，而
//    「跑一轮几十秒」的那条路径**只有把响应压住才看得见进度条**。故直接 `Fetch.enable` +
//    自己监听 `Fetch.requestPaused`（`b.ws` 是暴露出来的），对 `/run` 挂一个定时器再放行。
//
// 查十件事（页面能不能用，不是「文件写出来了」）：
//   ① 页面挂载 / 无 console.error / 无 unhandledrejection
//   ② 8 个预置一键按钮，名字与后端清单逐字一致
//   ③ 8 根权重滑条，缺省 = 15/15/10/10/10/10/15/15（合计 100，后端 `DefaultWeight` 的分布）
//   ④ 状态条四数：场景 8 / 勾选 8 / 权重 100 / 记录 2
//   ⑤ 演练记录 2 行，损坏行画成 `.a-corrupt`（不是画成一个 0/0/0 的成功演练）
//   ⑥ 点一键 → **进度条出现并停在页面上**（响应被压住期间）→ 请求体是 `seckill=100`
//   ⑦ 跑完 flash 带四个数；新记录（Id=9）自动展开
//   ⑧ 展开态：四个读数格 / 场景表 8 行 / 库存与标记与备注
//   ⑨ 拖滑条到 0 ⇒ 勾选数 7、权重 85、合计随之变（`0 = 没勾` 那条口径）
//   ⑩ 全勾的场景**并发填 6 (< 8)** ⇒ 开始按钮禁用 + 出提示（后端这条回 400）
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/attack-sim'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
const SHOT = `${EV}out-b7c5-attack-console.png`

const R = report('块 7 · commit 5 攻击模拟器页（mock 后端）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

// ── 后端清单（**逐字抄 `AttackEngine.SCENARIOS`**，键序即界面顺序）────────────────
const SCENARIOS = [
  { Key: 'seckill', Name: '秒杀抢购', Protections: '库存扣减 + CAS + 唯一索引 + 锁', Needs: '无。看 Result.Stock：库存只减不增、停在 0 不为负', DefaultWeight: 15 },
  { Key: 'orderBurst', Name: '订单并发', Protections: '出生闸 + 价格闸 + 冻结闸 + 锁 + CAS', Needs: '无。四档轮转（合法 / 价格非法 / 出生态非法 / 换一件商品）', DefaultWeight: 15 },
  { Key: 'repeatSubmit', Name: '重复提交', Protections: '幂等 + 唯一索引 + 锁', Needs: '无。同一个 OrderId 并发打 ⇒ 只有一单落库', DefaultWeight: 10 },
  { Key: 'unpaidTimeout', Name: '超时不付款', Protections: '超时取消 job + 库存释放', Needs: '要看到取消：dev profile + 等 60 秒后的下一拍', DefaultWeight: 10 },
  { Key: 'cacheCold', Name: '缓存冷启', Protections: '单飞（sync=true）+ 负缓存', Needs: '无。同一波并发撞同一个未缓存键', DefaultWeight: 10 },
  { Key: 'redisDown', Name: 'Redis 断开', Protections: '冷却窗口 + fail-open', Needs: '无（本引擎自己注入并恢复）。阶段 B 单独跑 5 秒', DefaultWeight: 10 },
  { Key: 'bannedUser', Name: '封禁用户攻击', Protections: '封禁闸 + 身份校验', Needs: '身份校验那路恒可看（403）；封禁那路的 401 只在默认 profile 出现', DefaultWeight: 15 },
  { Key: 'sensitiveWord', Name: '敏感词攻击', Protections: '两阈值（<40% 打码 / ≥40% 拒收）+ 图片先审', Needs: '无。会真写评论行（正文带 SIM- 前缀）', DefaultWeight: 15 },
]

/** 一份完整的 `Result`（形状逐字对齐 `AttackEngine.buildResult`）。 */
const RESULT_OK = {
  Scenarios: SCENARIOS.map((s, i) => ({
    Key: s.Key, Name: s.Name, Protections: s.Protections,
    Requests: 100 + i * 10, Passed: 90 + i, Blocked: 5 + i, Failed: i === 1 ? 3 : 0,
  })),
  Stock: { SkuCode: 'SKU-DEMO-001', Before: 40, After: 27, Baseline: null, Deducted: 13, SeckillOrders: 13 },
  Markers: { OrderIdPrefix: 'SIM-', CommentContentPrefix: 'SIM-', RunSeq: 7, SampleOrderIds: ['SIM-7-0001', 'SIM-7-0002'] },
  RedisDown: { Ran: true, Seconds: 5, Injected: true, Restored: true, L2WasHealthyBefore: true },
  Notes: ['判据：401/403 与 200+success:false 记 Blocked；5xx/404/超时/响应体不合本仓形状记 Failed', '攻击数据标记 SIM-'],
}

const RECORD_OK = {
  Id: 8, OperatorId: 'ops_duan', OperatorRole: 3, Name: '秒杀抢购',
  Scenario: { c: 100, d: 60, s: [{ k: 'seckill', w: 100 }] },
  TotalRequests: 1200, BlockedRequests: 37, FailedRequests: 2, DurationMs: 61234,
  CreatedAt: '2026-10-14 04:00:00', Result: RESULT_OK,
}
const RECORD_BAD = {
  Id: 7, OperatorId: '', OperatorRole: 0, Name: '自定义',
  Scenario: null, ScenarioRaw: '{"c":100,"d":60,"s":[{"k":"seck', ScenarioParseError: 'Unexpected end-of-input',
  TotalRequests: 0, BlockedRequests: 0, FailedRequests: 0, DurationMs: 100,
  CreatedAt: '2026-10-13 12:00:00', Result: null,
  ResultRaw: '{"Scenarios":[', ResultParseError: 'Unexpected end-of-input at [Source: (String)',
}

const RUN_OK = {
  success: true, Id: 9, Name: '秒杀抢购', TotalRequests: 1234, BlockedRequests: 41,
  FailedRequests: 2, DurationMs: 60321, Result: RESULT_OK,
}

/**
 * `/run` 落库那一行在**下一次** `/records` 里的样子。
 *
 * ⚠️ 后端「**先跑后落**、落完再回 Id」⇒ 页面的 `loadRecords()` 一定看得见它；mock 若只回
 *    固定两行，页面把 `openId` 设成 9 也**没有那一行可展开**（表现是一排空断言，看着像页面坏了）。
 *    数字刻意与 `RECORD_OK`（1200 / 37 / 61.2 s）**不同** ⇒ ⑧ 读到 1234 / 41 / 60.3 s
 *    就是在读**新那一行**，而不是碰巧展开的旧行。
 */
const RECORD_NEW = {
  Id: 9, OperatorId: 'ops_duan', OperatorRole: 3, Name: '秒杀抢购',
  Scenario: { c: 100, d: 60, s: [{ k: 'seckill', w: 100 }] },
  TotalRequests: 1234, BlockedRequests: 41, FailedRequests: 2, DurationMs: 60321,
  CreatedAt: '2026-10-14 04:10:00', Result: RESULT_OK,
}

const CORS = [
  { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
  { name: 'Access-Control-Allow-Credentials', value: 'true' },
  { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
  { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
]

const seen = []          // 捕获到的请求（url / method / postData）
let ran = false          // `/run` 是否已经答过 —— 决定 `/records` 回几行（见 RECORD_NEW 注释）
const b = await open({ w: 1440, h: 1000 })

// ── 自接管 Fetch 拦截（理由见文件头）──────────────────────────────────────────
await b.send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
const json = (obj) => ({ responseCode: 200, responseHeaders: [...CORS, { name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify(obj)).toString('base64') })
b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  const url = request.url
  const method = request.method
  seen.push({ url, method, postData: request.postData || '' })
  const isRun = url.includes('/admin/observe/attack/run')
  const answer = () => {
    let payload
    if (method === 'OPTIONS') payload = { responseCode: 200, responseHeaders: CORS, body: '' }
    else if (url.includes('/admin/observe/attack/scenarios')) payload = json({ success: true, list: SCENARIOS })
    else if (url.includes('/admin/observe/attack/records')) {
      const list = ran ? [RECORD_NEW, RECORD_OK, RECORD_BAD] : [RECORD_OK, RECORD_BAD]
      payload = json({ success: true, list, total: list.length, limit: 20, offset: 0 })
    }
    // ⚠️ 置位在**答之前**：页面拿到 `/run` 响应后立刻 `loadRecords()`，晚一步就还是旧列表。
    else if (isRun) { ran = true; payload = json(RUN_OK) }
    else payload = json({ success: true })
    b.send('Fetch.fulfillRequest', { requestId, ...payload }).catch(() => {})
  }
  // ⚠️ `/run` **晚 1.5 秒**再答：这正是「进度条能不能出现」的唯一观测窗口。
  if (isRun) setTimeout(answer, 1500)
  else answer()
})

// ── 会话注入（必须在应用脚本之前；`role` 必须是字符串 "3"）──────────────────────
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

const TEXT = (sel) => `[...document.querySelectorAll('${sel}')].map(e => e.innerText.trim())`
const setSlider = (i, v) => b.evalJS(`(() => {
  const el = document.querySelectorAll('.ws-range')[${i}]
  if (!el) return false
  el.value = '${v}'
  el.dispatchEvent(new Event('input', { bubbles: true }))
  return true
})()`)

try {
  await b.goto(`${BASE}/${ROUTE}`, 2600)
  await sleep(800)

  // ── ① 挂载 ────────────────────────────────────────────────────────────────
  const body = await b.evalJS('document.body.innerText')
  ok('① 页面挂载（正文 > 400 字）', String(body).length > 400, String(body).length)
  ok('① 面包屑含「攻击模拟器」', String(body).includes('攻击模拟器'))
  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))

  // ── ② 8 个一键 ────────────────────────────────────────────────────────────
  const presets = await b.evalJS(`[...document.querySelectorAll('.a-preset .a-preset-n')].map(e => e.innerText.replace(/\\s/g,''))`)
  ok('② 预置按钮 8 个', presets.length === 8, presets.length)
  // ⚠️ **两边都要归一化空白**：页面那侧已 `replace(/\s/g,'')`（按钮里是「图标 + 名字」，故只能
  //   `includes` 判），而后端清单里有一条名字**自带空格**（`'Redis 断开'`）—— 只归一化页面一侧
  //   会拿「Redis断开」去 `includes('Redis 断开')`，必然 false（本笔第一版就踩了这条，
  //   报出来的假象是「按钮名字不对」，实际页面渲染的是后端原话 `{{ s.Name }}`，一个字没改）。
  const squash = (s) => String(s).replace(/\s/g, '')
  const names = SCENARIOS.map((s) => squash(s.Name))
  ok('② 按钮名字与后端清单逐字一致', JSON.stringify(presets.map((p) => names.find((n) => p.includes(n)) || p)) === JSON.stringify(names), presets)

  // ── ③ 8 根滑条 + 缺省权重 ─────────────────────────────────────────────────
  const sliderLabels = await b.evalJS(TEXT('.ws .ws-name'))
  const sliderVals = await b.evalJS(`[...document.querySelectorAll('.ws .ws-val')].map(e => e.innerText.trim())`)
  ok('③ 滑条 8 根', sliderLabels.length === 8, sliderLabels.length)
  ok('③ 缺省权重 = 15/15/10/10/10/10/15/15', JSON.stringify(sliderVals) === JSON.stringify(['15%', '15%', '10%', '10%', '10%', '10%', '15%', '15%']), sliderVals)

  // ── ④ 状态条四数 ──────────────────────────────────────────────────────────
  const stats = await b.evalJS(`[...document.querySelectorAll('.a-stat b')].map(e => e.innerText.trim())`)
  ok('④ 状态条 = 8 / 8 / 100 / 2', JSON.stringify(stats) === JSON.stringify(['8', '8', '100', '2']), stats)

  // ── ⑤ 记录两行 + 损坏行 ───────────────────────────────────────────────────
  const rows = await b.evalJS(`[...document.querySelectorAll('.a-ev .a-tag')].map(e => e.innerText.trim())`)
  ok('⑤ 记录 2 行（时间倒序：秒杀抢购在前）', JSON.stringify(rows) === JSON.stringify(['秒杀抢购', '自定义']), rows)
  // 展开第 2 行（损坏记录）
  await b.evalJS(`(() => { document.querySelectorAll('.a-ev-head')[1].click(); return true })()`)
  await sleep(250)
  const corrupt = await b.evalJS(TEXT('.a-corrupt'))
  ok('⑤ 损坏行画成「结果读不出来」而不是 0/0/0 的成功演练', corrupt.length === 1 && corrupt[0].includes('Unexpected end-of-input'), corrupt)
  await b.evalJS(`(() => { document.querySelectorAll('.a-ev-head')[1].click(); return true })()`)

  // ── ⑥ 点一键 → 进度条（响应被压住 1.5s 期间必须可见）──────────────────────
  const before = seen.length
  await b.evalJS(`(() => { document.querySelectorAll('.a-preset')[0].click(); return true })()`)
  await sleep(500)
  const prog = await b.evalJS(`document.querySelectorAll('.a-prog').length`)
  ok('⑥ 跑动中出现进度条（响应尚未回）', prog === 1, prog)
  const progText = await b.evalJS(`(document.querySelector('.a-prog-t') || {}).innerText || ''`)
  ok('⑥ 进度条自述「不是后端读数」', String(progText).includes('不是后端读数'), String(progText).slice(0, 60))
  const btnBusy = await b.evalJS(`(() => { const b = document.querySelector('.a-btn-on'); return b ? b.innerText.trim() : '' })()`)
  ok('⑥ 跑动中按钮变「演练中…」且禁用', btnBusy.includes('演练中'), btnBusy)
  await b.shot(SHOT)

  await sleep(2200)
  const runReq = seen.slice(before).find((r) => r.url.includes('/attack/run'))
  ok('⑥ /run 请求体 = name=秒杀抢购 & scenarios=[{seckill,100}]',
    !!runReq && runReq.method === 'POST' && decodeURIComponent(runReq.postData).includes('"key":"seckill","weight":100'),
    runReq ? decodeURIComponent(runReq.postData) : '(没打到 /run)')
  ok('⑥ 请求体**不含** timeout 之外的东西（并发 100 / 持续 60 随默认一并发）',
    !!runReq && /concurrency=100/.test(runReq.postData) && /durationSeconds=60/.test(runReq.postData),
    runReq ? runReq.postData : '')

  // ── ⑦ 跑完：flash + 新记录自动展开 ────────────────────────────────────────
  const flash = await b.evalJS(`(document.querySelector('.a-flash') || {}).innerText || ''`)
  ok('⑦ flash 报四个数（1234 / 41 / 2 / 60.3 s）',
    ['1234', '41', '2', '60.3 s'].every((x) => String(flash).includes(x)), String(flash).slice(0, 120))
  // ⚠️ 光断言「展开的是秒杀抢购」不够：旧行 `RECORD_OK` 也叫这个名字 ⇒ 顺带断言展开的是**第一行**
  //   （新纪录 Id=9 排在最前）。真正区分新旧的是 ⑧ 那四个读数（1234/41/60.3 s ≠ 旧行 1200/37/61.2 s）。
  const openIdx = await b.evalJS(`[...document.querySelectorAll('.a-ev')].findIndex(e => e.classList.contains('on'))`)
  const openTag = await b.evalJS(`(() => { const on = document.querySelector('.a-ev.on'); return on ? on.querySelector('.a-tag').innerText.trim() : '' })()`)
  ok('⑦ 新记录（Id=9）自动展开（不再是自己拼的一行——走的是刷新后的列表）', openTag === '秒杀抢购' && openIdx === 0, `openTag=${openTag} idx=${openIdx}`)
  const stillProg = await b.evalJS(`document.querySelectorAll('.a-prog').length`)
  ok('⑦ 跑完进度条收起', stillProg === 0, stillProg)

  // ── ⑧ 展开态的分字段渲染 ──────────────────────────────────────────────────
  const cells = await b.evalJS(`[...document.querySelectorAll('.a-ev.on .a-res-cell b')].map(e => e.innerText.trim())`)
  ok('⑧ 四个读数格 = 1234 / 41 / 2 / 60.3 s', JSON.stringify(cells) === JSON.stringify(['1234', '41', '2', '60.3 s']), cells)
  const tblRows = await b.evalJS(`document.querySelectorAll('.a-ev.on .a-tbl tbody tr').length`)
  ok('⑧ 分场景读数表 8 行', tblRows === 8, tblRows)
  const kv = await b.evalJS(`[...document.querySelectorAll('.a-ev.on .a-kv')].map(e => e.innerText.replace(/\\s+/g,' '))`)
  ok('⑧ 库存行（40 → 27，扣 13）+ 秒杀放行 13',
    kv.some((t) => t.includes('40') && t.includes('27') && t.includes('13')), kv[0])
  ok('⑧ 标记行含 SIM- 前缀与样本号', kv.some((t) => t.includes('SIM-') && t.includes('SIM-7-0001')), kv.find((t) => t.includes('SIM-')))
  ok('⑧ Redis 断开行 = 已注入并恢复', kv.some((t) => t.includes('已注入故障') && t.includes('已恢复')), kv.find((t) => t.includes('Redis') || t.includes('注入')))
  const notes = await b.evalJS(`document.querySelectorAll('.a-ev.on .a-notes li').length`)
  ok('⑧ 备注 2 条', notes === 2, notes)

  // ── ⑨ 滑条拖到 0 = 没勾 ───────────────────────────────────────────────────
  ok('⑨ 拖动前勾选 8 / 权重 100', JSON.stringify(await b.evalJS(`[...document.querySelectorAll('.a-stat b')].map(e=>e.innerText.trim())`)) .includes('"8"'))
  await setSlider(0, 0)
  await sleep(200)
  const after = await b.evalJS(`[...document.querySelectorAll('.a-stat b')].map(e => e.innerText.trim())`)
  // ⚠️ 第 4 格是「演练记录」条数：跑到这里时**已经跑过一轮**（⑦ 那一轮让 mock 的列表从 2 行变 3 行）
  //   ⇒ 写死 `'2'` 会拿「跑之前」的旧值当期望（本笔第二版就踩了）。改成断言「它 = 列表实际行数」，
  //   这样它查的是「状态条与列表一致」，而不是某个会被别处推高的数字。
  const evRows = await b.evalJS(`document.querySelectorAll('.a-ev').length`)
  ok('⑨ 拖到 0 后：勾选 7 / 权重 85（记录格与列表行数一致）',
    JSON.stringify(after.slice(0, 3)) === JSON.stringify(['8', '7', '85']) && Number(after[3]) === evRows,
    `${JSON.stringify(after)} rows=${evRows}`)
  const zeroLabel = await b.evalJS(`document.querySelectorAll('.ws .ws-val')[0].innerText.trim()`)
  ok('⑨ 0 权重画成「未勾选」（不是 0%）', zeroLabel === '未勾选', zeroLabel)
  await setSlider(0, 15)
  await sleep(200)

  // ── ⑩ 并发 < 场景数 ⇒ 拦住 ────────────────────────────────────────────────
  await b.evalJS(`(() => {
    const el = [...document.querySelectorAll('.a-run input[type=number]')][0]
    el.value = '6'; el.dispatchEvent(new Event('input', { bubbles: true })); return true
  })()`)
  await sleep(200)
  const warn = await b.evalJS(`(document.querySelector('.a-warn') || {}).innerText || ''`)
  ok('⑩ 并发 6 < 勾选 8 ⇒ 出提示', String(warn).includes('必须 ≥ 勾选场景数'), String(warn))
  const runDisabled = await b.evalJS(`(() => { const b = document.querySelector('.a-run .a-btn-on'); return b ? b.disabled : null })()`)
  ok('⑩ 开始按钮被禁用', runDisabled === true, runDisabled)
} finally {
  const errs2 = await b.evalJS('(window.__errs || [])').catch(() => [])
  ok('全流程收尾：仍无 console.error / unhandledrejection', (errs2 || []).length === 0, (errs2 || []).slice(0, 5))
  const bad = R.dump()
  console.log(`\n截图：${SHOT}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
