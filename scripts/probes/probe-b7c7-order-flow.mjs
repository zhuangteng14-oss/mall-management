// probe-b7c7-order-flow.mjs —— 块 7 · commit 7〈状态流转页〉的**渲染 + 交互 + 实时跳动实测**。
//
// 跑法：① `npm run serve`（8080，两入口同一台）② `node scripts/probes/probe-b7c7-order-flow.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ⚠️ **自接管 `Fetch` 拦截，不用 `_cdp.mjs` 的 `mock`**（同 `probe-b7c5-*.mjs` 的理由）：
//   那个回调是同步的，而本页要验的「刚跳过的那条边亮一下」有**时间窗**（1.2 秒）——
//   必须把响应**压住 1.5 秒**再放，否则帧在导航过程中就到了。
//
// ⚠️ **本页的数据源是 SSE 流**，而 `Fetch.fulfillRequest` 只能给**一整块** body
//   （不是分块推送）⇒ 这里把 3 拍拼成一个 body 一起回。前端 `openStream` 会把它切成 3 帧、
//   逐帧 `onFrame`，行为与真流一致；差别只有「一次到齐」这一点，而**帧解析与渲染**正是
//   本探针要验的东西。真后端上的逐拍推进另算（本笔的端到端在真 8088 上验，见收笔报告）。
//
// 查十件事：
//   ① 页面挂载 / 无 console.error / 无 unhandledrejection
//   ② 5 张计数卡，标签 = 待付款 / 待发货 / 待收货 / 已完成 / 已取消（顺序逐字）
//   ③ 计数卡的数是**后端帧里的数**（12 / 34 / 56 / 78 / 5），且滚动动画已落位
//   ④ 环图 canvas 画出来了（非零宽）
//   ⑤ 实时订单列表 4 行（3 张 + 第 3 拍新出现的 ORD-004）
//   ⑥ 进度条节点数 = 该单的 `step`（ORD-003 已完成 = 4 个点亮）
//   ⑦ **实时跳动**：第 2 拍的 moves 让 `waitShip>waitReceive` 那条边亮着（`.on`）
//   ⑧ **实时跳动**：落到「待收货」节点脉冲 + ORD-002 那一行在闪（`.is-jump`）
//   ⑨ 状态机图 5 个节点盒
//   ⑩ 点 ORD-002 → 时间线弹层：创建 + 本会话捕获的那一步流转（共 2 项）
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/timeline'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
const SHOT = `${EV}out-b7c7-order-flow.png`
const FULL = `${EV}out-b7c7-order-flow-full.png`

const R = report('块 7 · commit 7 状态流转页（mock 后端 SSE）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

// ── 三拍（形状**逐字对齐** `OrderStreamService.frame()`：seq / ts / counts / orders / moves / moveCount）──
// ⚠️ `counts` 的键 = `OrderDao.countOrderStates()` 的 SQL 别名（小写）；`orders[]` 的键 = `orderRow()`
//    包出来的那一份（orderId / state / step / cancelled / product / amount / quantity / createdAt）。
const T = 1750000000000
const order = (id, state, step, product, amount, qty, created, cancelled = false) => ({
  orderId: id, customerId: 'u_001', merchantId: 'm_001',
  amount, quantity: qty, product, createdAt: created, state, step, cancelled,
})

const FRAME1 = {
  seq: 1, ts: T,
  counts: { cancelled: 5, completed: 78, waitReceive: 56, waitShip: 34, waitPay: 12, total: 185 },
  orders: [
    order('ORD-001', 'waitReceive', 3, '罗技 M330 静音鼠标', 89.0, 1, '2026-10-14 16:31:02'),
    order('ORD-002', 'waitShip', 2, '机械键盘 87 键', 329.0, 1, '2026-10-14 16:30:44'),
    order('ORD-003', 'completed', 4, '显示器支架', 159.0, 2, '2026-10-14 16:29:10'),
  ],
  moves: [], moveCount: 0,
}
// 第 2 拍：ORD-002 发了货（waitShip → waitReceive）⇒ 该亮边、亮节点、闪行。
const FRAME2 = {
  seq: 2, ts: T + 1000,
  counts: { cancelled: 5, completed: 78, waitReceive: 57, waitShip: 33, waitPay: 12, total: 185 },
  orders: [
    order('ORD-001', 'waitReceive', 3, '罗技 M330 静音鼠标', 89.0, 1, '2026-10-14 16:31:02'),
    order('ORD-002', 'waitReceive', 3, '机械键盘 87 键', 329.0, 1, '2026-10-14 16:30:44'),
    order('ORD-003', 'completed', 4, '显示器支架', 159.0, 2, '2026-10-14 16:29:10'),
  ],
  moves: [{ orderId: 'ORD-002', action: 'ship', from: 'waitShip', to: 'waitReceive', result: 'ok', ts: T + 900 }],
  moveCount: 1,
}
// 第 3 拍：新单 ORD-004 出现（前端该把它插到列表最前）。
const FRAME3 = {
  seq: 3, ts: T + 2000,
  counts: { cancelled: 5, completed: 78, waitReceive: 57, waitShip: 33, waitPay: 13, total: 186 },
  orders: [
    order('ORD-004', 'waitPay', 1, 'USB-C 扩展坞', 199.0, 1, '2026-10-14 16:32:20'),
    order('ORD-001', 'waitReceive', 3, '罗技 M330 静音鼠标', 89.0, 1, '2026-10-14 16:31:02'),
    order('ORD-002', 'waitReceive', 3, '机械键盘 87 键', 329.0, 1, '2026-10-14 16:30:44'),
    order('ORD-003', 'completed', 4, '显示器支架', 159.0, 2, '2026-10-14 16:29:10'),
  ],
  moves: [], moveCount: 0,
}

/** 三拍拼成一段 `text/event-stream`（帧格式只有一种：`data: <单行 JSON>\n\n`）。 */
const SSE_BODY = [FRAME1, FRAME2, FRAME3].map((f) => `data: ${JSON.stringify(f)}\n\n`).join('')

const CORS = [
  { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
  { name: 'Access-Control-Allow-Credentials', value: 'true' },
  { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
  { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
]

const seen = []
const b = await open({ w: 1440, h: 1100 })

await b.send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  seen.push({ url: request.url, method: request.method })
  const answer = () => {
    if (request.method === 'OPTIONS') {
      b.send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: CORS, body: '' }).catch(() => {})
      return
    }
    const isStream = request.url.includes('/admin/observe/orders/stream')
    const body = isStream ? SSE_BODY : JSON.stringify({ success: true })
    b.send('Fetch.fulfillRequest', {
      requestId, responseCode: 200,
      responseHeaders: [...CORS, { name: 'Content-Type', value: isStream ? 'text/event-stream' : 'application/json' }],
      body: Buffer.from(body).toString('base64'),
    }).catch(() => {})
  }
  // ⚠️ 压住 1.5 秒再放：见文件头。这也是「进度条看到时响应还没回」的同款手法。
  setTimeout(answer, 1500)
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

// ⚠️ `innerText ?? textContent`：SVG 元素（`<text>`）**没有** `innerText`（那是 HTMLElement 的），
//    只写 `innerText` 会在状态机那几句上炸 `Cannot read properties of undefined`（第一版实测）。
const TEXT = (sel) => `[...document.querySelectorAll('${sel}')].map(e => (e.innerText ?? e.textContent ?? '').trim())`

// ⚠️ `.a-of-id` **自 ab6f71d（块 7 收口 · 中文标签层）起带一个 `#` 前缀** —— 页面显示的是 `#ORD-002`。
//    本探针写在 033d62a（commit 7），那时 DOM 里还没有 `#` ⇒ 下面 ⑤ / ⑧-闪行 / ⑩ 这几条**从 ab6f71d
//    起就一直是红的**（`git log -S` 定过位：前缀是 ab6f71d 引入的，与 commit 10 无关）。
//    就地剥掉这个装饰前缀，而不是把 `#` 抄进每一条期望值里 —— 抄进去等于把「显示上怎么写」升格成契约，
//    下次谁再调一次样式，探针又跟着红一遍。
const TEXT_ID = (sel) => `[...document.querySelectorAll('${sel}')].map(e => (e.innerText ?? e.textContent ?? '').trim().replace(/^#/, ''))`

try {
  // ── 导航 + 轮询到挂载（首次访问会触发 webpack 按需编译，故用轮询而不是固定 sleep）──
  await b.send('Page.navigate', { url: `${BASE}/${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.a-of')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 页面挂载（.a-of 出现）', mounted === true)

  // ⚠️ 时序：挂载后 1.5 秒响应才到（mock 延迟）⇒ 帧到达 → 0.3 秒滚动动画落位 → 1.2 秒后高亮熄。
  //    从挂载算起到 2.0 秒：动画已落位、高亮还亮着 —— 这是唯一能同时断言两件事的窗口。
  await sleep(2000)

  // ── ① 挂载面 ──────────────────────────────────────────────────────────────
  const body = await b.evalJS('document.body.innerText')
  ok('① 正文含「状态流转」与「状态机」', String(body).includes('状态流转') && String(body).includes('状态机'))
  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))
  const streamReq = seen.find((r) => r.url.includes('/admin/observe/orders/stream'))
  ok('① 打的是 POST /admin/observe/orders/stream（不是 GET、不是 metrics）',
    !!streamReq && streamReq.method === 'POST', streamReq ? streamReq.method : '(没打到流)')

  // ── ② 5 张计数卡（顺序逐字）────────────────────────────────────────────────
  // ⚠️ 取 `:last-child`：`.kpi-k` 里第一格是 `IconGlyph`（它自己也是一个 `<span class="ig">↻</span>`）
  //    ⇒ 裸 `.kpi-k span` 会把 5 个字形也算成标签（第一版就踩了，抓到 10 个值）。
  const labels = await b.evalJS(TEXT('.a-of-kpis .kpi-k > span:last-child'))
  ok('② 计数卡 5 张，标签 = 待付款 / 待发货 / 待收货 / 已完成 / 已取消',
    JSON.stringify(labels) === JSON.stringify(['待付款', '待发货', '待收货', '已完成', '已取消']), labels)

  // ── ③ 卡上的数 = 帧里的数（第 3 拍：12 / 33 / 57 / 78 / 5）──────────────────
  const nums = await b.evalJS(TEXT('.a-of-kpis .kpi-n'))
  ok('③ 计数 = 13 / 33 / 57 / 78 / 5（第 3 拍的 counts，滚动动画已落位）',
    JSON.stringify(nums) === JSON.stringify(['13', '33', '57', '78', '5']), nums)

  // ── ④ 环图 ────────────────────────────────────────────────────────────────
  const canvas = await b.evalJS(`(() => { const c = document.querySelector('.a-of-donut canvas'); return c ? c.getBoundingClientRect().width : 0 })()`)
  ok('④ 状态分布环图 canvas 非零宽', Number(canvas) > 50, canvas)
  const center = await b.evalJS(`(document.querySelector('.a-of-donut .dc-v') || {}).innerText || ''`)
  ok('④ 环图中心 = 订单总数 186', String(center).trim() === '186', center)

  // ── ⑤ 列表 4 行（新单 ORD-004 排最前）──────────────────────────────────────
  const ids = await b.evalJS(TEXT_ID('.a-of-row .a-of-id'))
  ok('⑤ 列表 4 行，新单 ORD-004 插到最前',
    JSON.stringify(ids) === JSON.stringify(['ORD-004', 'ORD-001', 'ORD-002', 'ORD-003']), ids)

  // ── ⑥ 进度条 = step 个节点点亮（ORD-003 已完成 = 4）────────────────────────
  const onCounts = await b.evalJS(`[...document.querySelectorAll('.a-of-row')].map(r => r.querySelectorAll('.a-of-node.on').length)`)
  ok('⑥ 点亮节点数 = 各单 step（1 / 3 / 3 / 4）',
    JSON.stringify(onCounts) === JSON.stringify([1, 3, 3, 4]), onCounts)

  // ── ⑦ 实时跳动：第 2 拍的边还亮着 ──────────────────────────────────────────
  // ⚠️ 用**位置**取那条边（DOM 顺序：主链 3 条 line 在前、取消 2 条 path 在后）⇒ 索引 1 = `waitShip>waitReceive`。
  const litEdges = await b.evalJS(`[...document.querySelectorAll('.a-of-edge')].map(e => e.classList.contains('on'))`)
  ok('⑦ 第 2 拍的 `waitShip>waitReceive` 那条边亮着（索引 1）', litEdges[1] === true, litEdges)

  // ── ⑧ 实时跳动：节点脉冲 + 行闪 ────────────────────────────────────────────
  const pulse = await b.evalJS(TEXT('.a-of-nodes .is-pulse .a-of-box-t'))
  ok('⑧ 脉冲的是「待收货」节点', JSON.stringify(pulse) === JSON.stringify(['待收货']), pulse)
  const jumps = await b.evalJS(TEXT_ID('.a-of-row.is-jump .a-of-id'))
  ok('⑧ 闪的是 ORD-002（它这一拍从待发货跳到待收货）', JSON.stringify(jumps) === JSON.stringify(['ORD-002']), jumps)

  // ── ⑨ 状态机 5 节点 ───────────────────────────────────────────────────────
  const boxes = await b.evalJS(TEXT('.a-of-box-t'))
  ok('⑨ 状态机 5 个节点盒', JSON.stringify(boxes) === JSON.stringify(['待付款', '待发货', '待收货', '已完成', '已取消']), boxes)

  await b.shot(SHOT)
  // 整页（含最下面的状态机图）—— 视口截图看不到它，而它是本页 5 图里唯一一张手写 SVG。
  await b.shotFull(FULL)

  // ── ⑩ 点 ORD-002 → 时间线弹层 ──────────────────────────────────────────────
  await b.evalJS(`(() => {
    const row = [...document.querySelectorAll('.a-of-row')].find(r => r.querySelector('.a-of-id').innerText.trim().replace(/^#/, '') === 'ORD-002')
    row.click(); return true
  })()`)
  await sleep(300)
  const modalOpen = await b.evalJS(`!!document.querySelector('.a-of-modal')`)
  ok('⑩ 点一行弹出时间线弹层', modalOpen === true)
  const modalId = await b.evalJS(`(document.querySelector('.a-of-modal-hd .a-of-id') || {}).innerText || ''`)
  ok('⑩ 弹层标题 = ORD-002', String(modalId).trim().replace(/^#/, '') === 'ORD-002', modalId)
  const tl = await b.evalJS(`[...document.querySelectorAll('.a-of-tl-i')].map(e => e.innerText.replace(/\\s+/g,' ').trim())`)
  ok('⑩ 时间线 2 项：创建 + 本会话捕获的「待发货 → 待收货」',
    tl.length === 2 && tl[0].includes('创建') && tl[1].includes('待发货') && tl[1].includes('待收货'), tl)
  const tlTime = await b.evalJS(`[...document.querySelectorAll('.a-of-tl-t')].map(e => e.innerText.trim())`)
  ok('⑩ 两项都有时刻（HH:mm:ss）', tlTime.length === 2 && tlTime.every((t) => /^\d{2}:\d{2}:\d{2}$/.test(t)), tlTime)

  // 关闭
  await b.evalJS(`(() => { document.querySelector('.a-of-close').click(); return true })()`)
  await sleep(200)
  ok('⑩ 关闭后弹层消失', (await b.evalJS(`!!document.querySelector('.a-of-modal')`)) === false)

  // ── ⑪ Pager（封面的那一个，前端分页）──────────────────────────────────────
  const pagerSum = await b.evalJS(`(document.querySelector('.pager-sum') || {}).innerText || ''`)
  ok('⑪ 分页条在，且写明「实时订单 · 共 4 单」',
    String(pagerSum).replace(/\s+/g, '').includes('实时订单') && String(pagerSum).replace(/\s+/g, '').includes('共4单'),
    pagerSum.replace(/\s+/g, ' '))
} catch (e) {
  // ⚠️ 必须显式打出来：`finally` 里那句 `dump()` 只打印**已经收进表里**的断言 ⇒ 中途抛错时
  //    会看到「表里少了几项」而不是「哪一步炸了」（第一版就吃了这个亏）。
  console.log('探针中途异常：', (e && e.message) || e)
} finally {
  const errs2 = await b.evalJS('(window.__errs || [])').catch(() => [])
  ok('全流程收尾：仍无 console.error / unhandledrejection', (errs2 || []).length === 0, (errs2 || []).slice(0, 5))
  const bad = R.dump()
  console.log(`\n截图：${SHOT}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
