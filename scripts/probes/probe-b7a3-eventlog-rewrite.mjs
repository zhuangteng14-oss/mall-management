// probe-b7a3-eventlog-rewrite.mjs —— 块 7 · 3a.3 第 ⑥ 步〈日志页重做成三栏驾驶舱〉的实测。
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7a3-eventlog-rewrite.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ⚠️ 与 `probe-b7c8-event-log.mjs` 的关系：那支探针断言的是**重做前的 DOM**
//   （`.a-el-kind` 图例 / `.a-el-row` 两行卡片 / `Pager` 分页器）—— 这次重做把那些**全换了**，
//   所以那支探针现在必然红。本支是它的**替代**，不是补充。旧那支留档不删（对照物）。
//
// ⚠️ 自接管 `Fetch` 拦截，不用 `_cdp.mjs` 的 `mock`（同 `probe-b7c5` / `probe-b7c7` / `probe-b7c8`）。
// ⚠️ `Fetch.fulfillRequest` 只能给一整块 body ⇒ 两拍拼一个 body 一起回；前端 `openStream` 会切帧。
// ⚠️ 帧形状必须与后端 `EventStreamService.frame()` 对齐（10 个键，含 `mq`）。
//
// 查十五件事：
//   ① 挂载 / 无 console.error / POST 到 stream
//   ② **六区齐**（① 状态条 ② 过滤栏 ③ 事件流 ④ 实时统计 ⑤ 链路展开 ⑥ 开关栏）
//   ③ ② 九类行清单：标签逐字 + 每类一枚 **lucide svg**（不再是内联 path 数组）
//   ④ **分页器没了**：158 条全在 DOM 里（旧版每页 100）
//   ⑤ ③ 是**时间正序**（tail -f 方向：首行时刻 < 末行时刻）—— 与旧版「最新在最上」相反
//   ⑥ **树形前导符**：一组包起来了（组头 + 成员缩进 3ch + 末行收口），且末组子行左槽是空格不是 `│`
//   ⑦ ④ Top-N 三档在 + 换档会重排
//   ⑧ ⑤ 抽屉**默认塌着**，点 ③ 里带 traceId 的行才拉起，链里含「请求结束 · 总耗时 42 ms」
//   ⑨ 没有 traceId 的行不带 role=button
//   ⑩ ⑧ 处 `data-anim` 落点都在
//   ⑪ 窗口形态：**整页不滚**，滚动只发生在 ③ 内部
//   ⑫ ① 的数据源计数**常驻**（模拟日常没开时也有「已放 N / 500 条」）
//   ⑬ ② 的类计数 = 本页**窗口内累计**（= 158，而不是帧里本拍 counts 的那些小数字）
//   ⑭ ⑥ 开关栏：`degrade` 画成不可开关（后端 PROTECTIONS_NOT_WIRED 的镜像）
//   ⑮ 色板锁定：取到的是 admin 主题的计算色（不是线框灰阶）
//   ⑯–㉒ 数据源分段（真流 ⇄ 模拟日常）· 导出三格式 · 时间范围 · 窄视口 1280 · 设施 3 类置灰
//   ㉓ **第 ⑦ 步**（视觉与呈现，用户 2026-10-16 的裁定）：滚动条 ≤8px + 轨道透明（CSSOM **与放大图**）
//      · 折线图两排小字（峰值/当前/−60s/now，且不画轴线）· ④ 横条对比度 ≥3:1 · ②④ 图标
//      · 折行几何（2 行上限 / 续行 2ch / hover 展开，**注入一段长文本**才量得到）· 树形前缀两段 + 提亮
//      · 对象可点与 `objectId` 一致 · ⑤ 链里每条事件的中英文两层。
//      ⚠️ 本段**必须在 ⑯ 之前**（⑯ 会把数据源切到「模拟日常」，那之后量不到真流那 161 行）。
//   ㉔ **3a.3 前端 4 块**（用户 2026-10-17 放行）：A `sentence()` 自然流拼接器（逐 kind 从句 +
//      括号里的 trace 短号 / 耗时 / **剩余 N**；`fields` 优先、回落 `detail`；缺槽位整段不出现）
//      · B 色系（键/值/值·最次要/可点四档，对的是 **admin 主题的计算色**）· C `api-labels.js`
//      146 条 + 兜底 + 三个取用口 · D 图标与文字 **6–8px**（② 类清单 / ② 严重度 / ④ 两排 / ⑤ 链）。
//      ⚠️ 同样**必须在 ⑯ 之前**（理由同上）。CSV 表头 13 → 16 列那一条挂在 ⑲ 里。
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

// ⚠️ **必须是 `admin.html`**：本仓双入口（`vue.config.js:16-28`），管理端挂在 `admin.html`
//   （`title` = 「管理端 · Mall Management」），商城/商家挂在 `index.html`。
//   打到 `index.html` 上不会 404（路由表是共用的、组件也挂了），但**外壳不是 `AdminShell`**，
//   于是量到的是别家布局 + `data-theme` 缺失（`null`）—— 本次实测踩过，见下 ⑮ 的注释。
const BASE = 'http://localhost:8080/admin.html'
const ROUTE = '#/admin/observe/logs'
const WORK = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a3/'

const R = report('块 7 · 3a.3 第 ⑥ 步 日志页重做（三栏驾驶舱 · mock 后端 SSE）')
let TOTAL = 0
/** 按 CSS 选择器的盒子裁一张**放大图**（scale 倍），只落 `_work/3a3/`。
 *  ⚠️ 为什么要它：探针的能量在「规则接上了没有」（CSSOM），而**肉眼看到的**是渲染出来那一格。
 *    7px 滚动条、`└─` 的颗粒度、续行那 2ch 的缩进 —— 这些都只有放大图能验。
 *  ⚠️ clip 只吃整数且必须在视口内（`captureBeyondViewport: false`），所以先 clamp。 */
const zoomShot = async (sel, file, { 宽 = 0, 高 = 240, 左 = 0, 右缘 = false, scale = 6 } = {}) => {
  const box = await b.evalJS(`(() => {
    const e = document.querySelector(${JSON.stringify(sel)})
    if (!e) return null
    const r = e.getBoundingClientRect()
    return { left: r.left, top: r.top, right: r.right, width: r.width, height: r.height }
  })()`)
  if (!box) return null
  const w = 宽 || Math.round(box.width)
  const x = Math.max(0, Math.round(右缘 ? box.right - w : box.left + 左))
  const y = Math.max(0, Math.round(box.top))
  const hh = Math.min(高, Math.round(box.height), 1100 - y)
  if (hh <= 2) return null
  const r = await b.send('Page.captureScreenshot', { format: 'png', clip: { x, y, width: w, height: hh, scale } })
  fs.writeFileSync(file, Buffer.from(r.data, 'base64'))
  return { x, y, w, hh, scale }
}
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

// ⚠️ 起点必须**贴着现在**（上一版写死 1750000000000 = 2025-06-13，本轮不能再用）：
//   ① 的「时间范围」默认是**近 5 分**，写死的旧时刻会被那条窗口整批滤掉 ⇒ 页面空的、断言全红。
//   取「20 秒前」：BULK（T+0…T+1050）与 PERKIND（T+5000…T+5036）都落在近 5 分内，
//   而 ⑳ 那条自定义判据要用 **T+3000 当刀口**把这两批精确切开。
const T = Date.now() - 20000
const TRACE = 'trace0001'

/** 九类「有意思的」事件（一条一类）—— 与 FRAME2 的 8 条**同属一次请求**，
 *  ⇒ 它们会连续、且 `traces` 里有这条记录 ⇒ 满足包组条件，③ 里会长成一组树。
 *  ⚠️ ts 按 i 递减：全部晚于 BULK，且收拍内按 ts 倒序排 ⇒ 卡片顺序 = KINDS 声明序。
 *
 *  ⚠️ 3a.3 起这九条**补上了 `fields` 等五个新键**（帧 11 → 15 键），原因是 A 块 `sentence()`
 *     的**首选**路径就是读 `fields`：不补的话本段所有句子断言走的都是回落路径，
 *     「优先读 fields」那一半判据等于没测（同「负向一半不算判据」）。
 *     五个新键的形状逐条对齐后端 `EventBroadcaster` 的 `Emit`（九个环各一套，别混）。
 *  ⚠️ 三条**故意**造出来当对照，缺一条就有一半判据是空的：
 *     · `cache` 的 `fields.cache` 与 `detail` 里的区名**不一致** ⇒ 才判得出「谁优先」；
 *     · `notify` **只给 detail、不给 fields** ⇒ 才是真的在测回落分支；
 *     · `protection` 带顶层 `actor` + `batchId` ⇒ 才量得到第一层「发起人 M205（商家）」与 ⑤ 的
 *       结构键（`batchId（批次号）`）。收尾批 2 起 `src` 一律只放 detail 尾部（真帧形状）。 */
const PERKIND = [
  {
    kind: 'cache', title: '缓存命中（L1）',
    detail: 'cache=goodstype result=hit key=all src=cache#7', objectId: 'goodsType:all',
    // ⚠️ `src` **只在 detail 尾部**、不进 fields（真帧形状：后端 `EventBroadcaster.add` 不往
    //    `fields` 里放 src）。上一版这里把它塞进了 fields —— 那是替错形状背书，收尾批 2 已纠正。
    fields: { cache: 'goodsHome', result: 'hit', key: 'all' },
  },
  {
    kind: 'lock', title: '取锁成功（order:pay）',
    detail: 'lock=order-pay result=acquired key=order:pay/O-1 src=lock#4', objectId: 'order:pay/O-1',
    // ⚠️ 锁名必须是 `DistributedLockService.LockName` 那**五把**之一（`order-pay`），
    //    写 `order:create` 这种不存在的值只会走兜底、测不到 `LOCK_LABEL` 那一步。
    fields: { lock: 'order-pay', result: 'acquired', key: 'order:pay/O-1' },
  },
  {
    // ⚠️ 3a.3 后续（失败原因）：这一条改成 **error + reason**（sev=error），用来验 ⑤ 的
    //    红色原因段。to 必须为 null —— 真帧里 error 档的「转移后」是不知道，后端 `fromOrder`
    //    的 title 就是 `状态流转 CREATED → null`，编一个 to=PAID 是替错形状背书。
    kind: 'order', title: '状态流转 CREATED → null', severity: 'error',
    detail: 'action=pay result=error order#O-2 src=order#2', objectId: 'O-2',
    // ⚠️ from/to 用 `OrderState` 的**枚举名**（后端 `stateName()` 的返回值）—— 与真实
    //    vocabulary 对齐；`waitPay` 那套是 `ORDER_FLOW_STATE_LABEL`，是**另一张表**。
    fields: {
      action: 'pay', result: 'error', from: 'CREATED', to: null, order: 'O-2',
      reason: '数据库错误：IllegalStateException：db down',
    },
    durationUs: 12400,
  },
  {
    // ⚠️ 3a.3 后续：这一条改成**真实的超卖拦截档**（insufficient + reason，sev=warn）。
    //    原来是 ok 档，验不到「失败原因为什么要看得到」—— 而用户报的就是这个场景。
    kind: 'stock', title: '库存不足（未扣，防超卖命中）', severity: 'warn',
    detail: 'kind=deduct sku=SKU-0002 qty=8 result=insufficient src=stock#9', objectId: 'SKU-0002',
    fields: {
      kind: 'deduct', sku: 'SKU-0002', qty: '8', result: 'insufficient',
      reason: '防超卖拦截：库存不足（需 8 件，剩余 1 件）',
    },
    // ⚠️ 「剩余 N」—— 这是 3a.3 第二步才进帧的那一列（九个调用点里只有扣减档有值）
    //    ⇒ 用户点名要它出现在句子里，本段由它一处提供。
    remaining: 1,
  },
  {
    kind: 'audit', title: '审计写入',
    detail: 'actor=C-9 action=goods.delete reason=违规下架 src=audit#3', objectId: 'M-1:P-1',
    fields: { actor: 'C-9', action: 'goods.delete', reason: '违规下架' },
  },
  {
    kind: 'notify', title: '通知发送',
    // ⚠️ `to` 的取值域是后端 `NotificationService.TO_TYPE_CUSTOMER/MERCHANT`（customer / merchant），
    //    不是邮箱 —— 上一版写 `email` 属于假形状，收尾批 2 已改成真值域里的 `merchant`。
    detail: 'to=merchant event=order.paid reason=下单成功 src=notify#6',
    // ⚠️ **本条是两重对照**，两处都不是随手写的：
    //    ① `fields: null`（显式）⇒ 走 `parseDetail(e.detail)` 的**回落路径**；
    //    ② `objectId: ''` ⇒ 页面在「对象列」上该做的是**根本不画**（`objText()` 对空 id 返回
    //       空串，模板 `v-if="r.obj"` 挡掉），而不是画一个点不动/点了没反应的假链接。
    //       没有这条负面对照，㉓7 那条「有则都有、无则都无」就是**空真**（159 === 159，两侧都 0）。
    objectId: '',
    fields: null,
  },
  {
    kind: 'sensitive', title: '敏感词拒收',
    detail: 'scene=评论 hit=9/20 result=rejected src=sensitive#1', objectId: '傻逼,滚',
    fields: { scene: '评论', hit: 9, total: 20 },
    // ⚠️ 亚毫秒那档（<1000 µs）：句子该写 `850 µs` 而不是四舍五入成 `1 ms`（或 `0 ms`）。
    durationUs: 850,
  },
  {
    kind: 'identity', title: '身份拒绝',
    detail: 'subject=C-1 reason=令牌过期 result=rejected src=identity#5', objectId: 'C-1',
    fields: { subject: 'C-1', reason: '令牌过期' },
  },
  {
    kind: 'protection', title: '限流拦截',
    detail: 'protection=rateLimit scope=/admin/observe/attack/run result=blocked src=protection#2', objectId: 'rateLimit',
    fields: {
      protection: 'rateLimit',
      scope: '/admin/observe/attack/run 全路径限流窗口',
      extra: 'qps=20',
      // ⚠️ 故意放一个**表里没有的键**：用户裁定「认不出的回落英文原名，不显示空白」——
      //    这条是那句裁定的唯一活判据（全用已知键时，兜底分支等于没测）。
      weirdKey: '未来值',
    },
    actor: { id: 'C-9', role: 3 },
    batchId: 'b-7a3-#12',
  },
].map((x, i) => ({
  seq: 1000 + i,
  ts: T + 5000 + (8 - i) * 4,
  kind: x.kind,
  // 显式 severity 优先（order=error / stock=warn）；其余沿用「这三类 warn、其余 info」
  severity: x.severity || (x.kind === 'sensitive' || x.kind === 'identity' || x.kind === 'protection' ? 'warn' : 'info'),
  title: x.title,
  detail: x.detail,
  key: x.objectId,
  objectType: x.objectType || x.kind,
  objectId: x.objectId,
  traceId: TRACE,
  // ⚠️ 3a.3 第二步起的五个新键。`undefined` 会被 `JSON.stringify` 丢掉（= 帧里没有这一键，
  //    与旧后端同形）；`fields: null` 那一条是**显式**要它缺席，两者在 `absorbEvents` 里
  //    落到同一个结果（`null`），但写出来的意图不同 —— 别把 `null` 那条改成 `undefined`。
  batchId: x.batchId,
  actor: x.actor,
  durationUs: x.durationUs,
  remaining: x.remaining,
  fields: x.fields,
}))

/** 批量事件（第 1 拍），把条数撑过旧版的一页（100）。**没有 traceId** —— 专门用来验「不可点」。 */
const BULK = Array.from({ length: 150 }, (_, i) => ({
  seq: i,
  ts: T + i * 7,
  kind: ['cache', 'lock', 'order', 'stock', 'audit', 'notify', 'sensitive', 'identity', 'protection'][i % 9],
  severity: i % 11 === 0 ? 'error' : (i % 5 === 0 ? 'warn' : 'info'),
  title: `批量事件 ${i}`,
  detail: `idx=${i} src=bulk#${i}`,
  key: `K-${i}`,
  objectType: 'bulk',
  objectId: `B-${i}`,
  traceId: null,
}))

const FRAME1 = {
  seq: 1, ts: T + 1000, cursor: 149, count: 150, rate: 12.5, mq: false,
  counts: { cache: 17, lock: 17, order: 17, stock: 17, audit: 17, notify: 17, sensitive: 16, identity: 16, protection: 16 },
  severity: { info: 118, warn: 29, error: 3 },
  events: BULK, traces: [],
}
const FRAME2 = {
  seq: 2, ts: T + 2000, cursor: 159, count: 9, rate: 8.0, mq: false,
  counts: { cache: 1, lock: 1, order: 1, stock: 1, audit: 1, notify: 1, sensitive: 1, identity: 1, protection: 1 },
  severity: { info: 5, warn: 3, error: 1 },
  events: PERKIND,
  traces: [{
    seq: 0, traceId: TRACE, method: 'POST', path: '/goods/goodsUp',
    status: 200, startMs: T + 4990, endMs: T + 5032, ms: 42,
  }],
}

const SSE_BODY = [FRAME1, FRAME2].map((f) => `data: ${JSON.stringify(f)}\n\n`).join('')

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
    const isStream = request.url.includes('/admin/observe/events/stream')
    const body = isStream ? SSE_BODY : JSON.stringify({ success: true })
    b.send('Fetch.fulfillRequest', {
      requestId, responseCode: 200,
      responseHeaders: [...CORS, { name: 'Content-Type', value: isStream ? 'text/event-stream' : 'application/json' }],
      body: Buffer.from(body).toString('base64'),
    }).catch(() => {})
  }
  setTimeout(answer, 1200)
})

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

const TEXT = (sel) => `[...document.querySelectorAll('${sel}')].map(e => (e.innerText ?? e.textContent ?? '').trim())`
/** 元素自身的方框（不含后代）—— 量行高/缩进用。 */
const BOX = (sel) => `(() => { const e = document.querySelector('${sel}'); if (!e) return null; const r = e.getBoundingClientRect(); return { l: Math.round(r.left), w: Math.round(r.width), h: Math.round(r.height) } })()`
/** 按 CSV 规则拆一行（引号包裹 / `""` 转义 / 引号内的逗号）。
 *  ⚠️ 不能用 `split(',')`：本段 mock 里 `sensitive` 那条的 `objectId` = `傻逼,滚`（带逗号），
 *     导出侧按 CSV 规则加了引号 ⇒ 朴素拆分把它数成两格，「每行 16 格」会**假红**。 */
const csvSplit = (line) => {
  const out = []
  let cur = ''
  let q = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (q) {
      if (c === '"') { if (line[i + 1] === '"') { cur += '"'; i++ } else q = false } else cur += c
    } else if (c === '"') q = true
    else if (c === ',') { out.push(cur); cur = '' } else cur += c
  }
  out.push(cur)
  return out
}
/** `datetime-local` 的取值串（**本地时区、秒级**）—— 页面上的 `toLocalInput` 是同一条口径。
 *  ⚠️ 不能用 `toISOString()`：那是 UTC，喂进去会整体偏一个时区（东八区偏 −8h）。 */
const p2 = (n) => String(n).padStart(2, '0')
const localInput = (ms) => {
  const d = new Date(ms)
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`
    + `T${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`
}

try {
  await b.send('Page.navigate', { url: `${BASE}/${ROUTE}` })
  let mounted = false
  for (let i = 0; i < 120; i++) {
    mounted = await b.evalJS(`!!document.querySelector('.ev-frame')`).catch(() => false)
    if (mounted) break
    await sleep(250)
  }
  ok('① 页面挂载（.ev-frame 出现）', mounted === true)
  await sleep(2200)
  await sleep(600)

  const body = await b.evalJS('document.body.innerText')
  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))
  const streamReq = seen.find((r) => r.url.includes('/admin/observe/events/stream'))
  ok('① 打的是 POST /admin/observe/events/stream',
    !!streamReq && streamReq.method === 'POST', streamReq ? streamReq.method : '(没打到流)')
  const elText = await b.evalJS(`(document.querySelector('.a-el') || {}).innerText || ''`)
  ok('① 渲染文本里没有 markdown 星号', !String(elText).includes('**'))

  // ── ② 六区齐 ──────────────────────────────────────────────────────────────
  const regions = await b.evalJS(`(() => {
    const q = (s) => !!document.querySelector(s)
    return { s1: q('.ev-status'), s2: q('.ev-rail'), s3: q('.ev-col'), s4: q('.ev-live'), s5: q('.ev-drawer'), s6: q('.ev-switch') }
  })()`)
  ok('② 六区齐（① 状态条 / ② 过滤栏 / ③ 事件流 / ④ 实时统计 / ⑤ 链路展开 / ⑥ 开关栏）',
    Object.values(regions).every(Boolean), regions)

  // ── ③ 九类行清单 + lucide svg 图标 ─────────────────────────────────────────
  // ⚠️ 必须排掉 `.is-fac` 那 3 行（裁定 D 加进来的设施类），否则「九类」会数成 12 条。
  const kindLabels = await b.evalJS(TEXT('.ev-rail .rows .row:not(.all):not(.is-fac) .k'))
  ok('③ ② 的九类行清单标签逐字 = 缓存/锁/订单/库存/审计/通知/敏感词/身份/防护',
    JSON.stringify(kindLabels) === JSON.stringify(['缓存', '锁', '订单', '库存', '审计', '通知', '敏感词', '身份', '防护']), kindLabels)
  const lucideN = await b.evalJS(`[...document.querySelectorAll('.log-line .ic svg')].length`)
  const iconN = await b.evalJS(`[...document.querySelectorAll('.log-line .ic')].length`)
  const reqmarkN = await b.evalJS(`[...document.querySelectorAll('.log-line .ic .reqmark')].length`)
  // ⚠️ 组头/组尾那两行的图标格画的是 `.reqmark`（一条短横线），**不是** lucide svg ——「请求开始/结束」
  //    在原版 9 类图标里没有对应项，硬套一个 Lucide 图标反而是编。故断言要减去它们。
  ok('③ ③ 里的类图标走 @lucide/vue（除 2 条请求行外，每格一个 <svg>）',
    iconN > 0 && lucideN === iconN - reqmarkN && reqmarkN === 2, { 图标格: iconN, svg: lucideN, 请求行占位: reqmarkN })
  const kindColors = await b.evalJS(`[...document.querySelectorAll('.log-line .ic svg')].slice(0, 9).map(e => getComputedStyle(e).color)`)
  ok('③ 类色互不相同（前 9 枚取到 ≥5 个不同计算色）',
    new Set(kindColors).size >= 5, new Set(kindColors).size)

  // ── ④ 分页器没了 ──────────────────────────────────────────────────────────
  // 事件 159 条（BULK 150 + PERKIND 9），DOM 里另有组头/组尾 2 行 ⇒ 161 行 `.log-line`。
  const rowN = await b.evalJS(`document.querySelectorAll('.log-line').length`)
  ok('④ **分页器已删**：159 条事件全在 DOM 里（旧版每页 100）', rowN === 161, { 行: rowN, 期望: '159 事件 + 2 请求行' })
  ok('④ 页面里没有分页控件，也没有「下一页」字样',
    (await b.evalJS(`document.querySelectorAll('.ev-frame .pager, .ev-frame .a-pager').length`)) === 0
    && !String(body).includes('下一页'))

  // ── ④b 每一格都真有内容（本轮实拍抓到：类列整列空的）─────────────────────────
  // ⚠️ 这三条是**看图**抓出来的洞：`makeRow` 漏了 `md` ⇒ 渲染后 40px 的类列全空，
  //    而 `eslint` 与「无 console.error」都报绿。凡「字段有名字、模板有插槽」的组合都要这样钉。
  const cellTexts = await b.evalJS(`(() => {
    const r = document.querySelector('.log-line:not(.req)')
    return { md: r.querySelector('.md').textContent.trim(), ms: r.querySelector('.ms').textContent.trim(), tm: r.querySelector('.tm').textContent.trim(), lv: r.querySelector('.lv').textContent.trim() }
  })()`)
  ok('④b ③ 每行的**类列**非空且是九类中文之一',
    ['缓存', '锁', '订单', '库存', '审计', '通知', '敏感词', '身份', '防护'].includes(cellTexts.md), cellTexts.md)
  ok('④b ③ 每行的时刻 / 等级 / 消息三格都非空',
    /^\d{2}:\d{2}:\d{2}\.\d{3}$/.test(cellTexts.tm) && ['INFO', 'WARN', 'ERROR'].includes(cellTexts.lv) && cellTexts.ms.length > 0, cellTexts)
  const reqMd = await b.evalJS(`(document.querySelector('.log-line.req .md') || {}).textContent`)
  ok('④b 请求行的类列 = 「请求」', String(reqMd).trim() === '请求', reqMd)
  // ⚠️ 折线：2 拍数据时 `sparkPts` 非空 ⇒ svg 必须真有高、polyline 必须有点
  const spark = await b.evalJS(`(() => {
    const svg = document.querySelector('.ev-spark svg')
    const pl = document.querySelector('.ev-spark polyline')
    const r = svg.getBoundingClientRect()
    return { h: Math.round(r.height), w: Math.round(r.width), pts: pl ? (pl.getAttribute('points') || '').split(' ').filter(Boolean).length : 0 }
  })()`)
  ok('④b 顶部速率折线真画出来了（svg 有高 + polyline ≥2 个点）',
    spark.h >= 10 && spark.w > 100 && spark.pts >= 2, spark)
  // ⚠️ ④ 的类计数横条：最大值那一类的 fill 必须**占满轨道**（本轮实拍曾疑似只有 1/3）
  const bar = await b.evalJS(`(() => {
    const b0 = document.querySelector('.ev-bars .ev-bar')
    const t = b0.querySelector('.track')
    const f = b0.querySelector('.fill')
    return { nm: b0.querySelector('.nm').textContent.trim(), track: Math.round(t.getBoundingClientRect().width), fill: Math.round(f.getBoundingClientRect().width) }
  })()`)
  ok('④b ④ 类计数横条按最大值成比例（首条 = 最大值 ⇒ fill 占满轨道）',
    bar.track > 80 && Math.abs(bar.fill - bar.track) <= 2, bar)

  // ── ⑤ 时间正序（tail -f 方向）─────────────────────────────────────────────
  const times = await b.evalJS(`[...document.querySelectorAll('.log-line .tm')].map(e => e.textContent.trim())`)
  ok('⑤ ③ 是**时间正序**（tail -f：首行时刻 ≤ 末行时刻，与旧版「最新在最上」相反）',
    times.length > 1 && times[0] <= times[times.length - 1], [times[0], times[times.length - 1]])

  // ── ⑥ 树形前导符 ──────────────────────────────────────────────────────────
  // ⚠️ **别量 `.pre` 的左沿**：前导符的「缩进」不是把 `.pre` 右推（它永远贴在行左沿），
  //   而是**列宽**——`--pc` 逐行写 `calc(层级×3ch + 2ch + 8px)`，把**时刻列**推右 3ch。
  //    量 `.pre` 会得到「组头和成员左沿相同」并被误判成「没缩进」（本次实测踩过）。
  const reqN = await b.evalJS(`document.querySelectorAll('.log-line.req').length`)
  ok('⑥ 包了一组（组头「请求开始」+ 收口「请求结束」= 2 条 .req）', reqN === 2, reqN)
  const tree = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.log-line')]
    const headI = rows.findIndex(e => e.classList.contains('req') && (e.querySelector('.ms b') || {}).textContent === '请求开始')
    if (headI < 0) return null
    const g = (e) => ({ pre: e.querySelector('.pre').textContent, tmX: Math.round(e.querySelector('.tm').getBoundingClientRect().left) })
    return { head: g(rows[headI]), c1: g(rows[headI + 1]), c2: g(rows[headI + 2]), last: g(rows[rows.length - 1]) }
  })()`)
  ok('⑥ 组头是顶层条目 ⇒ 前导符 `├─` 或 `└─`', tree && ['├─', '└─'].includes(tree.head.pre), tree && tree.head.pre)
  ok('⑥ 成员行的**时刻列**比组头右移 3ch（≈10–32px）—— 缩进落在列宽上',
    tree && (tree.c1.tmX - tree.head.tmX) > 10 && (tree.c1.tmX - tree.head.tmX) < 32,
    tree && { 组头时刻列: tree.head.tmX, 成员: tree.c1.tmX, 差: tree.c1.tmX - tree.head.tmX })
  ok('⑥ 两个成员行的缩进一致（同一层）',
    tree && tree.c1.tmX === tree.c2.tmX, tree && [tree.c1.tmX, tree.c2.tmX])
  ok('⑥ 本窗最后一组 ⇒ 组头是 `└─`、成员左槽是**空格**（不是 `│`）',
    tree && tree.head.pre === '└─' && tree.c1.pre.startsWith('   '),
    tree && { 组头: tree.head.pre, 成员: tree.c1.pre })
  ok('⑥ 组尾（窗口最后一行）是 `   └─`', tree && tree.last.pre === '   └─', tree && tree.last.pre)

  // ── ⑥ 树形**实拍**（把 ③ 滚到底再裁）─────────────────────────────────────────────
  // ⚠️ 为什么单开这一张：本探针此前那两张实拍（`-stream` / 无后缀）都停在列表**顶部**，
  //    而组在**窗口末尾**（两拍数据才有 9 条同 trace）⇒ **证据里从来没有出现过一棵树**。
  //    用户 2026-10-16 的意见「截图里所有行看起来是平的，树形没生效」说的就是那两张图 ——
  //    先有「能看见树」的实拍，才谈得上判断树形到底立不立得住。
  await b.evalJS(`(() => { const l = document.querySelector('.ev-list'); l.scrollTop = l.scrollHeight; return l.scrollTop })()`)
  await sleep(600)
  const groupBox = await b.evalJS(`(() => {
    const el = [...document.querySelectorAll('.log-line')].find(r => r.classList.contains('req'))
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { top: Math.round(r.top), h: Math.round(r.height) }
  })()`)
  ok('⑥ 树形实拍：③ 滚到底后组头在视口内（否则截图还是拍不到树）',
    !!groupBox && groupBox.top >= 0 && groupBox.top < 1100, groupBox)
  await b.shot(`${WORK}out-b7a3-eventlog-tree.png`)

  // ── ⑦ ④ Top-N 三档 ───────────────────────────────────────────────────────
  const tabs = await b.evalJS(TEXT('.ev-top .tb'))
  ok('⑦ ④ Top-N 三档 = 对象/慢请求/错误', JSON.stringify(tabs) === JSON.stringify(['对象', '慢请求', '错误']), tabs)
  const topObj = await b.evalJS(TEXT('.ev-top .tr1 .nm'))
  ok('⑦ 对象榜有行（B-* 那批批量事件的对象）', topObj.length > 0, topObj.slice(0, 3))
  await b.evalJS(`[...document.querySelectorAll('.ev-top .tb')].find(e => e.textContent.includes('慢请求')).click()`)
  await sleep(300)
  const topSlow = await b.evalJS(TEXT('.ev-top .tr1 .nm'))
  ok('⑦ 换到「慢请求」档 ⇒ 列表重排（内容换成请求路径）',
    topSlow.length > 0 && topSlow[0].includes('/goods/goodsUp'), topSlow.slice(0, 3))
  await b.evalJS(`[...document.querySelectorAll('.ev-top .tb')].find(e => e.textContent.includes('对象')).click()`)
  await sleep(200)

  // ── ⑧ ⑤ 抽屉：默认塌着，点行才拉起 ────────────────────────────────────────
  const beforeOpen = await b.evalJS(`document.querySelectorAll('.ev-drawer .chain').length`)
  ok('⑧ ⑤ 默认塌着（没有 .chain）', beforeOpen === 0, beforeOpen)
  const openable = await b.evalJS(`document.querySelectorAll('.log-line[role="button"]').length`)
  ok('⑧ ③ 里带 traceId 的行可点（有 role=button）', openable > 0, openable)
  await b.evalJS(`document.querySelector('.log-line[role="button"]').click()`)
  await sleep(400)
  const chain = await b.evalJS(TEXT('.ev-drawer .chain .chain-i .d'))
  ok('⑧ 点一行 ⇒ ⑤ 拉起，链里含「请求开始」与「请求结束 · 总耗时 42 ms」',
    chain.some((t) => t.includes('请求开始')) && chain.some((t) => t.includes('总耗时 42 ms')), chain.slice(0, 3).concat(['…', chain[chain.length - 1]]))
  ok('⑧ 链里中间节点 = 同 trace 的事件（≥2 条）', chain.length >= 4, chain.length)
  // ⚠️ 同 trace 整组微染（动效落点 6）
  const selN = await b.evalJS(`document.querySelectorAll('.log-line.sel').length`)
  ok('⑧ 同 trace **整组**微染（.sel 行数 = 那一组的行数，> 1）', selN > 1, selN)
  ok('⑨ 没有 traceId 的行**不可点**（不带 role=button）',
    (await b.evalJS(`[...document.querySelectorAll('.log-line')].filter(e => !e.hasAttribute('role')).length`)) > 0)

  // ── ⑩ 八处 data-anim 落点 ─────────────────────────────────────────────────
  const anims = await b.evalJS(`[...document.querySelectorAll('[data-anim]')].map(e => e.getAttribute('data-anim'))`)
  const want = ['3', '4,7', '6', '8', '9', '10', '12']
  const flat = anims.join(',').split(',')
  ok('⑩ 8 处动效落点都在（data-anim 3 / 4,7 / 6 / 8 / 9 / 10 / 12）',
    want.every((w) => w.split(',').every((x) => flat.includes(x))), anims)

  // ── ⑪ 窗口形态：整页不滚，③ 内部滚 ────────────────────────────────────────
  const layout = await b.evalJS(`(() => {
    const de = document.documentElement
    const list = document.querySelector('#ev-list')
    const frame = document.querySelector('.ev-frame')
    const fr = frame.getBoundingClientRect()
    const ae = document.querySelector('.a-el')
    const ar = ae.getBoundingClientRect()
    const main = document.querySelector('.a-main')
    return {
      pageScroll: de.scrollHeight - de.clientHeight,
      listScroll: list ? list.scrollHeight - list.clientHeight : -1,
      frameH: Math.round(fr.height),
      frameTop: Math.round(fr.top),
      frameBottom: Math.round(fr.bottom),
      aelH: Math.round(ar.height),
      aelTop: Math.round(ar.top),
      aelCssH: getComputedStyle(ae).height,
      mainH: main ? Math.round(main.getBoundingClientRect().height) : -1,
      mainDisplay: main ? getComputedStyle(main).display : '?',
      winH: window.innerHeight,
    }
  })()`)
  ok('⑪ **整页不滚**（documentElement 无溢出，≤ 2px 容差）', Math.abs(layout.pageScroll) <= 2, layout)
  ok('⑪ ③ 内部可滚（scrollHeight > clientHeight）', layout.listScroll > 0, layout.listScroll)
  ok('⑪ 外框**装在一屏内**（底边不超出视口，≤ 2px 容差）', layout.frameBottom <= layout.winH + 2, { 底: layout.frameBottom, 视口: layout.winH })

  // ── ⑫ ① 数据源计数常驻 ────────────────────────────────────────────────────
  ok('⑫ ① 有「数据源」分段控件', String(body).includes('数据源'))
  ok('⑫ 「已放 N / 500 条」**常驻**（模拟日常没开时也在，含「未开」字样）',
    String(body).includes('/ 500 条') && String(body).includes('未开'), String(body).match(/已放[^）]*/)?.[0])

  // ── ⑬ ② 的类计数 = 窗口内累计 ─────────────────────────────────────────────
  const railNums = await b.evalJS(TEXT('.ev-rail .rows .row:not(.all) .c'))
  const sum = railNums.reduce((a, s) => a + Number(String(s).replace(/[^\d]/g, '') || 0), 0)
  ok('⑬ ② 九类计数合计 = 窗口内 159 条（**不是**帧里本拍 counts 的 9）', sum === 159, { 合计: sum, 分项: railNums })
  const allN = await b.evalJS(`(document.querySelector('.ev-rail .row.all .c') || {}).textContent || ''`)
  ok('⑬ 「全部」行 = 159', Number(String(allN).replace(/[^\d]/g, '')) === 159, allN)

  // ── ⑭ ⑥ 开关栏：degrade 不可开关 ──────────────────────────────────────────
  const dead = await b.evalJS(TEXT('.ev-switch .sw2.dead'))
  ok('⑭ ⑥ 开关栏把 `degrade`（降级）画成不可开关', dead.some((t) => t.includes('降级') && t.includes('不可开关')), dead)
  const inj = await b.evalJS(TEXT('.ev-switch .sw2'))
  ok('⑭ ⑥ 注入那三枚在（Redis 断开 / MQ 断开 / 慢 SQL）',
    ['Redis 断开', 'MQ 断开', '慢 SQL'].every((k) => inj.some((t) => t.includes(k))), inj)

  // ── ⑮ 色板锁定 = admin 主题 ───────────────────────────────────────────────
  const theme = await b.evalJS(`document.documentElement.getAttribute('data-theme')`)
  const inkColor = await b.evalJS(`getComputedStyle(document.querySelector('.ev-frame')).color`)
  const brandInk = await b.evalJS(`getComputedStyle(document.documentElement).getPropertyValue('--brand-ink').trim()`)
  // ⚠️ 这一条同时是「入口打对了没」的哨兵：打到 `index.html`（商城入口）时
  //    `data-theme` 是 **null**、`--brand-ink` 是浅色的 `#275e4e` —— 本次实测踩过。
  ok('⑮ 色板 = admin 主题（html[data-theme="admin"]，不是线框灰阶 / 浅色商城档）',
    theme === 'admin' && inkColor !== 'rgb(34, 34, 34)', { theme, 文字色: inkColor, brandInk })

  // ── ⑰ skill 台账（落地后重过：frontend-design 三查 / taste C1·C2·C5 / 色板）──────────
  const skin = await b.evalJS(`(() => {
    const all = [...document.querySelectorAll('.a-el *')]
    const shadows = new Set(), radii = new Set()
    for (const e of all) {
      const s = getComputedStyle(e)
      if (s.boxShadow && s.boxShadow !== 'none') shadows.add(s.boxShadow)
      radii.add(s.borderTopLeftRadius)
    }
    return { shadows: [...shadows], radii: [...radii].sort() }
  })()`)
  ok('⑰ frontend-design ②：全页 **0 处阴影**（发丝线替代阴影，同 tokens 口径）',
    skin.shadows.length === 0, skin.shadows)
  ok('⑰ frontend-design ②：圆角只取 token 档（0 / 4 / 8 / 10px，无自造值）',
    skin.radii.every((r) => ['0px', '4px', '8px', '10px', '50%'].includes(r)), skin.radii)
  // C5：四边框 + 与邻居有间隙 + 同构兄弟 ≥3 ⇒ 卡网格（控件除外 —— 判据同 `_work/3a3/tastecheck-台账.md` §三）
  // ⚠️ **判据本轮修过一次（用户 2026-10-16 执行清单第 2 项：C5 判据漏洞二选一）**。
  //    旧版把**所有子元素**拿去算「同构签名」⇒ ⑥ 开关组里那枚 `.lab` 标签（无边框）把签名
  //    打散成两个值，于是那 5 枚 `.sw2` **根本没被看见**，判据报「OK」—— 那不是「它没问题」，
  //    是「它没查」（同 [[checker-ok-means-only-what-it-checks]]）。
  //    现在：签名只取**有边框的兄弟**（`four`），`.lab` 再也挡不住 → 它必须被**显式登记**成例外。
  //    配套加了下面的**阳性对照**：先证明判据仍看得见那组，再声明它是已登记的控件组。
  const c5 = await b.evalJS(`(() => {
    try {
      const cz = (e) => { const s = getComputedStyle(e); return [s.borderTopWidth, s.borderRightWidth, s.borderBottomWidth, s.borderLeftWidth].join(' ') }
      const CTRL = new Set(['BUTTON', 'INPUT', 'SELECT'])
      const hits = []
      for (const p of document.querySelectorAll('.ev-frame *')) {
        const kids = [...p.children]
        if (kids.length < 3) continue
        const four = kids.filter((k) => cz(k) === '1px 1px 1px 1px')
        const gap = parseFloat(getComputedStyle(p).gap) || 0
        if (four.length < 3 || gap < 2) continue
        const sig = new Set(four.map((k) => cz(k) + '|' + k.tagName))
        if (sig.size !== 1) continue
        // 控件组的两种长相：整组都是原生控件，或组里有一枚「.lab」组名标签（⑥ 开关栏那种）。
        // ⚠️ 这里**不能**写反引号：整段是 b.evalJS 的模板字符串，一个反引号就把模板提前闭合，
        //    后面全变成「源码里的可执行 JS」，报错长成 TypeError: "<整段源码>" is not a function
        //    —— 指向的却是**注释行**，看不出来是标点问题（同 [[probe-measure-is-a-template-literal]]）。
        const ctrl = kids.every((k) => CTRL.has(k.tagName)) || kids.some((k) => k.classList.contains('lab'))
        hits.push({ parent: String(p.className), n: four.length, gap, 控件: ctrl, 组名: (kids.find((k) => k.classList.contains('lab')) || {}).textContent || '' })
      }
      return { hits }
    } catch (e) { return { err: String((e && e.stack) || e) } }
  })()`)
  // ⚠️ 先确认判据这段**自己没炸**：`.ev-frame *` 里有 SVG（lucide 图标），`className` 在 SVG 上
  //    是 `SVGAnimatedString` 而不是字符串 —— 直接放进返回值会让 CDP 序列化失败、整轮静默中止
  //    （上一版踩过：46 项之后突然「探针自身没抛异常 = FAIL」）。已改 `String()` + try/catch 兜住。
  ok('⑰ taste C5 判据自身可执行（SVG 的 className 已归一化，序列化不炸）', !c5.err, c5.err || 'ok')
  const c5h = c5.hits || []
  // ⚠️ **阳性对照**：先证明修完的判据**仍看得见** ⑥ 的那组开关（5 枚 `.sw2`，同构、有边框、有间隙）。
  //    没有这一条，「非控件命中 = 0」既可能是「真没有」，也可能是「判据又瞎了」—— 两者不可分。
  ok('⑰ taste C5 阳性对照：修完的判据**仍看得见** ⑥ 开关栏那组（≥3 枚同构带框的 `.sw2`）',
    c5h.some((h) => String(h.parent).includes('grp') && h.n >= 3), c5h)
  ok('⑰ taste C5：`.ev-frame` 内没有「四边框 + 有间隙 + 同构」的 ≥3 **数据卡**网格（命中项全是已登记的控件组）',
    c5h.length > 0 && c5h.every((h) => h.控件), c5h)
  // C1：相邻区块不共用构图 —— 六区两两相邻，各区的「子元素骨架」签名互不相同
  const c1 = await b.evalJS(`(() => {
    const zones = [...document.querySelectorAll('.ev-frame > *')]
    const sig = (e) => [...e.children].map((k) => k.tagName + '.' + String(k.className).split(' ')[0]).join('>')
    const pairs = []
    for (let i = 0; i + 1 < zones.length; i++) pairs.push([zones[i].className, zones[i + 1].className, sig(zones[i]) === sig(zones[i + 1])])
    return { n: zones.length, same: pairs.filter((p) => p[2]) }
  })()`)
  // ⚠️ 量的是 `.ev-frame` 的**顶层条带**（① / 条件块 / .ev-body / ⑤ / ⑥ = 5 条），
  //    不是「六区」—— ②③④ 是 `.ev-body` 的**内部**三列，不在这条链上。
  ok('⑰ taste C1：外框的顶层条带两两相邻，无一对共用构图（探针打印同构对）',
    c1.n >= 5 && c1.same.length === 0, { 条带: c1.n, 相邻对: c1.n - 1, 同构对: c1.same })

  // 真流状态下的实拍（⑤ 拉起、有起止）—— 与下面演示模式那张对照
  await b.shot(`${WORK}out-b7a3-eventlog-stream.png`)

  // ── ⑰b ① 第二排：一行 + 不溢出（裁定 A 落地后新加的两条）───────────────────────
  // ⚠️ `.ev-status` 是 `flex: 0 0 auto`（窗口形态），`.r2` 一换行就从 `.ev-body` 嘴里抢高度；
  //    三枚控件 + 「自定义」的起止两栏最容易顶到边。溢出必须量 `scrollWidth` 才看得见 ——
  //    组件里写了 `overflow: hidden`，光看高度与截图都发现不了「右边被切掉」。
  const r2box = () => b.evalJS(`(() => {
    const e = document.querySelector('.ev-status .r2')
    const hs = [...e.children].map(k => Math.round(k.getBoundingClientRect().height))
    const w = [...e.children].map(k => ({ c: String(k.className).split(' ')[0], w: Math.round(k.getBoundingClientRect().width), t: (k.textContent || '').trim().slice(0, 10) }))
    return { scroll: Math.round(e.scrollWidth), client: Math.round(e.clientWidth), h: Math.round(e.getBoundingClientRect().height), maxKid: Math.max.apply(null, hs), n: hs.length, w: w }
  })()`)
  const r2a = await r2box()
  ok('⑰b ① 第二排是**一行**（没换行 ⇒ 行高 = 最高的那个子元素）', r2a.h <= r2a.maxKid + 4, r2a)
  ok('⑰b ① 第二排**不溢出**（三枚控件 + 拍/MQ 都在可用宽内）', r2a.scroll <= r2a.client + 2, r2a)

  // ══ 裁定 A · ① 第二排的三枚控件（三个都必须是真功能，不是换了个说法的占位）════════

  // ── ⑱ 密度：22 ⇄ 28px ────────────────────────────────────────────────────────
  // 量的是**行盒**（不是 CSS 变量）：变量写对了但没接上（选择器没命中）照样量不出差别。
  // ⚠️ 载荷里带**盒模型解剖**（`ln` / `flow` / 首个 `.ic` 的高度与 `line-height`）：
  //    第 ⑦ 步把数据行从栅格改成「一行自然流」之后，紧凑态一度量到 25px（≠22）——
  //    这种「只差 3px」的红，没有解剖就只能靠猜。带上它，红的时候一眼看出是谁撑高的。
  const rowH = () => b.evalJS(`(() => {
    const r = document.querySelector('.log-line')
    const q = (s) => { const e = r.querySelector(s); return e ? Math.round(e.getBoundingClientRect().height) : null }
    const cs = getComputedStyle(r)
    return {
      line: Math.round(r.getBoundingClientRect().height),
      head: Math.round(document.querySelector('.log-head').getBoundingClientRect().height),
      盒: {
        行内边距: cs.paddingTop + '/' + cs.paddingBottom,
        行lineHeight: cs.lineHeight,
        ln: q('.ln'), flow: q('.flow'), pre: q('.pre'), tm: q('.tm'), ic: q('.ic'), md: q('.md'), ms: q('.ms'),
        icVAlign: getComputedStyle(r.querySelector('.ic')).verticalAlign,
      },
    }
  })()`)
  if (process.env.DBG) {
    const boxDump = await b.evalJS('(() => {' +
      'const line = document.querySelector(".log-line");' +
      'const ln = line.querySelector(".ln");' +
      'const H = (e) => { const r = e.getBoundingClientRect(); return { h: +r.height.toFixed(2), top: +r.top.toFixed(2) } };' +
      'const kids = [...ln.querySelectorAll("*")].map((e) => { const cs = getComputedStyle(e);' +
      ' const c = (e.className && e.className.baseVal !== undefined) ? e.className.baseVal : (e.className || e.tagName);' +
      ' return { c, dis: cs.display, va: cs.verticalAlign, lh: cs.lineHeight, fs: cs.fontSize, pad: cs.padding, bor: cs.borderTopWidth + "/" + cs.borderBottomWidth + "/" + cs.borderBottomStyle, ...H(e) } });' +
      'const lcs = getComputedStyle(ln);' +
      'return { line: H(line), ln: { ...H(ln), lh: lcs.lineHeight, fs: lcs.fontSize, maxH: lcs.maxHeight, pad: lcs.paddingTop + "/" + lcs.paddingBottom }, minH: getComputedStyle(line).minHeight, kids }' +
      '})()')
    console.log('\n=== DBG .ln 盒解剖 ===')
    console.log(JSON.stringify(boxDump, null, 1))
    const probeH = () => b.evalJS('+document.querySelector(".log-line .ln").getBoundingClientRect().height.toFixed(2)')
    console.log('现状 .ln =', await probeH())
    const cands = [
      ['A 前缀列 vertical-align:middle', '.log-line .pre, .log-line .tm, .log-line .ic, .log-line .lv, .log-line .md { vertical-align: middle !important }'],
      ['B 前缀列 line-height:1', '.log-line .pre, .log-line .tm, .log-line .ic, .log-line .lv, .log-line .md { line-height: 1 !important }'],
      ['C A+B', '.log-line .pre, .log-line .tm, .log-line .ic, .log-line .lv, .log-line .md { vertical-align: middle !important; line-height: 1 !important }'],
    ]
    for (const [nm, css] of cands) {
      await b.evalJS('(() => { let s = document.getElementById("dbgfix"); if (!s) { s = document.createElement("style"); s.id = "dbgfix"; document.head.appendChild(s) } s.textContent = ' + JSON.stringify(css) + ' })()')
      await sleep(250)
      console.log(nm, '=> .ln =', await probeH())
    }
    await b.evalJS('(() => { const s = document.getElementById("dbgfix"); if (s) s.remove() })()')
    await sleep(150)
    console.log('=== DBG END ===\n')
  }
  const clickSeg = (t) => b.evalJS(`[...document.querySelectorAll('.ev-status .seg .chip')].find(e => e.textContent.trim() === '${t}').click()`)
  const h1 = await rowH()
  ok('⑱ 密度「紧凑」（默认）⇒ 行高 22px', h1.line === 22, h1)
  await clickSeg('舒适')
  await sleep(300)
  const h2 = await rowH()
  ok('⑱ 密度「舒适」⇒ 行高 28px（切了真的会变）', h2.line === 28, h2)
  ok('⑱ 表头与数据行**同高**（同一条栅格的两段，不是只改行不改表头）',
    h1.head === h1.line && h2.head === h2.line, { 紧凑: h1, 舒适: h2 })
  await clickSeg('紧凑')
  await sleep(300)

  // ── ⑲ 导出：CSV / JSON / ndjson 三档各验内容 ─────────────────────────────────
  // ⚠️ 拦 `URL.createObjectURL` 把 Blob 留下来读文本 —— 这是**唯一**能把「点了导出」变成
  //    「导出内容对不对」的办法（headless 里真落盘的位置不稳定，不可当判据）。
  //    顺带把 `revokeObjectURL` 变空函数：不拦的话读完就撤了，读不到。
  await b.evalJS(`(() => {
    window.__blobs = []
    const orig = URL.createObjectURL.bind(URL)
    URL.createObjectURL = (blob) => { window.__blobs.push(blob); return orig(blob) }
    URL.revokeObjectURL = () => {}
    return true
  })()`)
  // ⚠️ **必须读 `arrayBuffer()` 看原始三字节，不能用 `Blob.text()`**：`Blob.text()` 走的是
  //    Encoding 规范的「UTF-8 decode」，它会**吃掉开头的 BOM**（`ignoreBOM` 默认 false）。
  //    本轮实测踩过：代码里 BOM 明明在，`.charCodeAt(0)` 量到 115（'s'）⇒ 差点把对的判成错的。
  const lastBlob = `(async () => {
    const x = window.__blobs[window.__blobs.length - 1]
    if (!x) return null
    const u8 = new Uint8Array(await x.arrayBuffer())
    return { head: [u8[0], u8[1], u8[2]], bytes: u8.length, text: new TextDecoder('utf-8').decode(u8) }
  })()`

  await clickSeg('CSV')
  await sleep(400)
  const csvB = (await b.evalJS(lastBlob)) || { head: [], text: '' }
  const csv = String(csvB.text)
  const csvLines = csv.split('\r\n').filter(Boolean)
  ok('⑲ 导出 CSV：**字节**以 BOM 开头（EF BB BF）—— Windows 的 Excel 才不会把中文按 GBK 解成乱码',
    csvB.head[0] === 0xef && csvB.head[1] === 0xbb && csvB.head[2] === 0xbf,
    { 前三字节: csvB.head, 期望: [0xef, 0xbb, 0xbf], 字节数: csvB.bytes })
  const csvHead = csvSplit(csvLines[0])
  ok('⑲ 导出 CSV：表头 **16 列**（3a.3 帧 11→15 键，其中 3 个并进 CSV：`actorId` / `actorRole` / `remaining`）+ 159 行数据',
    csvHead.length === 16 && csvLines.length === 160,
    { 列: csvHead.length, 行: csvLines.length, 头: csvLines[0] })
  // ⚠️ 每行**逐行拆格**必须用 `csvSplit()`，不能用 `split(',')`：本段 mock 里
  //    `sensitive` 那条的 `objectId` 是 `傻逼,滚`（带逗号）⇒ 导出侧按 CSV 规则加了引号，
  //    朴素 `split(',')` 会把它数成两格，「每行 16 格」就假红（错的结论）。
  const csvRows = csvLines.slice(1)
  ok('⑲ 导出 CSV：新增那三列**在表头里逐字可见**，且**每一行都是 16 格**（不是只有表头加宽了）',
    ['actorId', 'actorRole', 'remaining'].every((h) => csvHead.includes(h))
    && csvRows.length === 159 && csvRows.every((l) => csvSplit(l).length === 16),
    { 尾四列: csvHead.slice(-4), 行数: csvRows.length, 各行列数: [...new Set(csvRows.map((l) => csvSplit(l).length))] })
  // 值也对账（列名 → 下标，不写死数字）：`protection` 那条是本段唯一带 `actor` 的，`stock` 那条唯一带 `remaining`。
  const colOf = (name) => csvHead.indexOf(name)
  const cellOf = (row) => csvSplit(row)
  const rowOfTitle = (t) => csvRows.map(cellOf).find((c) => c[colOf('title')] === t)
  const protRow = rowOfTitle('限流拦截')
  const stockRow = rowOfTitle('库存不足（未扣，防超卖命中）')
  ok('⑲ 导出 CSV：`actorId` / `actorRole` 两列**真的有值**（`protection` 那条 = `C-9` / `3`），无身份的行留白',
    !!protRow && protRow[colOf('actorId')] === 'C-9' && protRow[colOf('actorRole')] === '3'
    && !!stockRow && stockRow[colOf('actorId')] === '',
    { 有身份: protRow && [protRow[colOf('actorId')], protRow[colOf('actorRole')]], 无身份: stockRow && stockRow[colOf('actorId')] })
  ok('⑲ 导出 CSV：`remaining` 列**真的有值**（超卖那条 = `1`），其余行留白（不是满列 0）',
    !!stockRow && stockRow[colOf('remaining')] === '1'
    && csvRows.filter((l) => cellOf(l)[colOf('remaining')] === '1').length === 1,
    { 该行: stockRow && stockRow[colOf('remaining')], 全表非空条数: csvRows.filter((l) => cellOf(l)[colOf('remaining')] !== '').length })

  await clickSeg('JSON')
  await sleep(400)
  const jsonTxt = String(((await b.evalJS(lastBlob)) || {}).text || '')
  let jsonParse = null
  try { jsonParse = JSON.parse(jsonTxt) } catch (e) { jsonParse = null }
  ok('⑲ 导出 JSON：能被 JSON.parse，159 条，字段名 = 帧里的原名（且带 source）',
    Array.isArray(jsonParse) && jsonParse.length === 159
    && 'source' in jsonParse[0] && 'objectId' in jsonParse[0] && 'traceId' in jsonParse[0],
    { 条数: Array.isArray(jsonParse) ? jsonParse.length : '(parse 失败)', 样例键: jsonParse ? Object.keys(jsonParse[0]) : null })
  // ⚠️ 机器那两档是「帧里的原名全带」⇒ 15 键 + `source` = **16 键**，含 CSV 刻意排除的
  //    `batchId` / `durationUs` / `fields`（排除理由见 `EventLog.vue` 的 `EXPORT_HEAD` 上方）。
  const jKeys = jsonParse && jsonParse.length ? Object.keys(jsonParse[0]) : []
  ok('⑲ 导出 JSON：**16 个键**（15 键 + `source`），五个新键全在（含 CSV 排除的那三个）',
    jKeys.length === 16 && ['batchId', 'actor', 'durationUs', 'remaining', 'fields'].every((k) => k in jsonParse[0]),
    { 键数: jKeys.length, 键: jKeys })
  const jProt = (jsonParse || []).find((x) => x.title === '限流拦截')
  const jStock = (jsonParse || []).find((x) => x.title === '库存不足（未扣，防超卖命中）')
  const jOrder = (jsonParse || []).find((x) => x.title === '状态流转 CREATED → null')
  const jNoti = (jsonParse || []).find((x) => x.title === '通知发送')
  ok('⑲ 导出 JSON：`actor` 保持**帧里的对象形状**（`{id,role}`），没有身份的行是 `null` 而不是 `{}`',
    !!jProt && JSON.stringify(jProt.actor) === JSON.stringify({ id: 'C-9', role: 3 })
    && !!jStock && jStock.actor === null,
    { 有身份: jProt && jProt.actor, 无身份: jStock && jStock.actor })
  ok('⑲ 导出 JSON：`remaining` 只有那一条有值（`1`），其余是 `null`；`durationUs` / `batchId` 也在',
    !!jStock && jStock.remaining === 1 && !!jProt && jProt.remaining === null
    && !!jProt && jProt.durationUs === null && jProt.batchId === 'b-7a3-#12',
    { stock: jStock && jStock.remaining, prot: jProt && { remaining: jProt.remaining, durationUs: jProt.durationUs, batchId: jProt.batchId } })
  ok('⑲ 导出结构不因原因而变：顶层没有 `reason`，原因只在 `fields.reason`；CSV 里干脆没有原因文本',
    !!jStock && !!jOrder
    && !('reason' in jStock) && !('reason' in jOrder)
    && jStock.fields && jStock.fields.reason === '防超卖拦截：库存不足（需 8 件，剩余 1 件）'
    && jOrder.fields && jOrder.fields.reason === '数据库错误：IllegalStateException：db down'
    && !csv.includes('防超卖拦截') && !csv.includes('数据库错误'),
    { stock: jStock && jStock.fields, order: jOrder && jOrder.fields, 顶层键数: jStock && Object.keys(jStock).length })
  ok('⑲ 导出 JSON：`fields` 是**原样那袋**（`notify` 那条显式 `null` ⇒ 回落路径才有意义）',
    !!jNoti && jNoti.fields === null && !!jProt && !!jProt.fields && jProt.fields.scope === '/admin/observe/attack/run 全路径限流窗口',
    { notify: jNoti && jNoti.fields, protection: jProt && jProt.fields })

  await clickSeg('ndjson')
  await sleep(400)
  const ndTxt = String(((await b.evalJS(lastBlob)) || {}).text || '')
  const ndLines = ndTxt.split('\n').filter(Boolean)
  let ndBad = 0
  let ndObjs = []
  for (const l of ndLines) { try { ndObjs.push(JSON.parse(l)) } catch (e) { ndBad++ } }
  ok('⑲ 导出 ndjson：一行一条、每行都是合法 JSON、条数与列表一致',
    ndLines.length === 159 && ndBad === 0, { 行: ndLines.length, 坏行: ndBad })
  // ⚠️ ndjson 与 JSON 是**同一份 `exportRaw`**（组件里两个分支共用）⇒ 这里不再逐键复述，
  //    只钉一条「两档同源」：同一批对象序列化出来，键集必然一样（不同源就会在这一条上分叉）。
  const ndKeys = ndObjs.length ? Object.keys(ndObjs[0]) : []
  ok('⑲ 导出 ndjson：键集与 JSON 档**逐字相同**（两档同源，不是各写一套字段列表）',
    ndKeys.length === 16 && jKeys.length === 16 && ndKeys.join(',') === jKeys.join(','),
    { ndjson: ndKeys.length, json: jKeys.length })

  const note = await b.evalJS(`(document.querySelector('.ev-status .seg .hint') || {}).textContent || ''`)
  ok('⑲ 导出后页面自报「刚刚导出 N 条 → 文件名」（下载被浏览器拦掉时，这是用户唯一的线索）',
    /刚刚导出\s*159\s*条/.test(note), note)
  const r2b = await r2box()
  ok('⑲ 导出提示出现后 ① 第二排**仍然不溢出**（这条提示不能把拍/MQ 挤出屏幕）',
    r2b.scroll <= r2b.client + 2, r2b)

  // ── ⑳ 时间范围：自定义刀口切在 T+3000 ⇒ 只剩第 2 拍那 9 条 ────────────────────
  const rowN0 = await b.evalJS(`document.querySelectorAll('.log-line').length`)
  ok('⑳ 「近 5 分」：两拍（150 + 9）全在范围内 ⇒ 161 行', rowN0 === 161, rowN0)
  await clickSeg('自定义')
  await sleep(300)
  const dtVals = await b.evalJS(`[...document.querySelectorAll('.ev-status .seg .dt')].map(e => e.value)`)
  ok('⑳ 切「自定义」⇒ 起止两个 datetime-local 出现，且**已预填**当前窗口（不是两个空框）',
    dtVals.length === 2 && dtVals[0].length === 19 && dtVals[1].length === 19, dtVals)
  // ⚠️ 这是 ① 第二排**最宽**的状态：三枚控件 + 起止两栏 + 拍/MQ。组件里 `.r2` 是
  //    `flex-wrap: nowrap` + `overflow: hidden` ⇒ 溢出不会换行，只会**静默切掉最右边的
  //    拍/MQ**（那是真数据，不是提示）—— 所以必须在最宽的这一态量，不能只在默认态量。
  // ⚠️ 那条导出小字 **10 秒后自己消失**（组件里的设计）⇒ 不能靠时序碰运气撞上它。
  //    这里**主动再导一次**，把「提示 + 自定义两栏同时在场」这个最宽的一态钉成确定态。
  await clickSeg('ndjson')
  await sleep(300)
  const r2c = await r2box()
  ok('⑳ 切「自定义」后 ① 第二排**仍然不溢出**（起止两栏 + 导出提示都不许把拍/MQ 挤出屏幕）',
    r2c.scroll <= r2c.client + 2, r2c)
  // ⚠️ 「不溢出」有两种达成方式：① 压缩垫底的那几个；② **把 `.tz` 直接切掉**（上一版就是这样，
  //    而且 `overflow: hidden` 让它**一声不响**）。所以必须另有一条把 `.tz` 钉在框内 ——
  //    只量 `scrollWidth` 的话，② 也能让这一条变绿。同「校验器报 OK ≠ 没问题」。
  const tzIn = () => b.evalJS(`(() => {
    const e = document.querySelector('.ev-status .r2'), t = document.querySelector('.ev-status .tz')
    const h = document.querySelector('.ev-status .seg .hint')
    const er = e.getBoundingClientRect(), tr = t.getBoundingClientRect()
    return { 右溢: Math.round(tr.right - er.right), tz宽: Math.round(tr.width), 提示宽: h ? Math.round(h.getBoundingClientRect().width) : null }
  })()`)
  const tz1 = await tzIn()
  ok('⑳ 自定义态下 `.tz` **整块留在框内**（右边缘不越过 `.r2`）—— 拍数/MQ 是真数据，不能拿它换',
    tz1.右溢 <= 0, tz1)
  ok('⑳ 自定义态下那条导出小字**是被压小了、不是被删了**（240px 上限没占满 = 它让了位）',
    tz1.提示宽 !== null && tz1.提示宽 < 240, tz1)

  // ── ⑳b 窄视口（1280）仍要活 ───────────────────────────────────────────────────
  // ⚠️ 只在 1440 一档绿**不给背书**：`.r2` 的可用宽跟着视口走，而「自定义 + 导出提示同时在」
  //    是最宽的一态。压到 1280（可用 ≈ 958px）跑同一组判据，才算把压缩链验过。
  await b.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1100, deviceScaleFactor: 1, mobile: false })
  await sleep(500)
  const r2n = await r2box()
  const tzn = await tzIn()
  ok('⑳b 窄视口 1280：① 第二排仍不溢出（三枚控件都还在框内）',
    r2n.scroll <= r2n.client + 2, r2n)
  ok('⑳b 窄视口 1280：`.tz` 仍整块在框内（压缩链按「hint → dt → 兜底」的顺序吃掉了压力）',
    tzn.右溢 <= 0, tzn)
  await b.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false })
  await sleep(500)
  const r2back = await r2box()
  ok('⑳b 视口还原 1440（后面的断言与截图按原尺寸走）',
    r2back.client > r2n.client && r2back.scroll <= r2back.client + 2, { 窄: r2n.client, 还原: r2back.client })
  const cutISO = localInput(T + 3000)
  await b.evalJS(`(() => {
    const i = document.querySelectorAll('.ev-status .seg .dt')[0]
    i.value = '${cutISO}'
    i.dispatchEvent(new Event('input', { bubbles: true }))
    return i.value
  })()`)
  await sleep(350)
  const rowN1 = await b.evalJS(`document.querySelectorAll('.log-line').length`)
  ok('⑳ 刀口 = T+3000 ⇒ 只剩第 2 拍那 9 条（+ 组头组尾 2 行 = 11）',
    rowN1 === 11, { 行: rowN1, 刀口: cutISO })
  const allAfterCut = await b.evalJS(`(document.querySelector('.ev-rail .row.all .c') || {}).textContent || ''`)
  ok('⑳ ② 的「全部」也跟着变（9 条）—— 时间范围是**全局**口径，不是只切 ③ 的列表',
    Number(String(allAfterCut).replace(/[^\d]/g, '')) === 9, allAfterCut)
  await clickSeg('近 5 分')
  await sleep(350)
  ok('⑳ 切回「近 5 分」⇒ 161 行原样回来（时间范围是可逆的，不是一次性的）',
    (await b.evalJS(`document.querySelectorAll('.log-line').length`)) === 161)

  // ── ㉑ 裁定 C：5 条全文在 `title` 里 ─────────────────────────────────────────
  const covTitle = await b.evalJS(`(() => { const e = document.querySelector('.ev-rail .sub'); return e ? (e.getAttribute('title') || '') : '' })()`)
  const covParas = covTitle.split('\n')
  ok('㉑ ② 栏底小字的 `title` = 「覆盖与边界」5 条**全文**（5 段，且含只在原文里出现的关键词）',
    covParas.length === 5 && covTitle.includes('severityOf') && covTitle.includes('OBJECT_FIELD_LABEL') && covTitle.includes('512'),
    { 段数: covParas.length, 字数: covTitle.length, 首段: covParas[0].slice(0, 24) })
  const bodyNow = String(await b.evalJS('document.body.innerText'))
  ok('㉑ 页面里那行小字**自报**「已压缩 + 悬停看全文」（不是把 5 条悄悄压掉）',
    bodyNow.includes('覆盖与边界') && bodyNow.includes('悬停看全文'))

  // ── ㉒ 裁定 D：② / ④ 各多一组「设施 3 类」，置灰、不可点 ───────────────────────
  const facRail = await b.evalJS(`[...document.querySelectorAll('.ev-rail .rows .row.is-fac')].map(e => ({
    k: e.querySelector('.k').textContent.trim(), c: e.querySelector('.c').textContent.trim(), dis: e.disabled,
  }))`)
  ok('㉒ ② 的设施 3 类 = 慢 SQL / MQ / 调度，全部 `disabled`、计数写 `—`（不是 0）',
    facRail.length === 3
    && JSON.stringify(facRail.map((x) => x.k)) === JSON.stringify(['慢 SQL', 'MQ', '调度'])
    && facRail.every((x) => x.dis === true && x.c === '—'), facRail)
  ok('㉒ ② 的业务 9 类**仍是 9 行**（那 3 行是加出来的，没有把真图例顶掉）',
    (await b.evalJS(`document.querySelectorAll('.ev-rail .rows .row:not(.all):not(.is-fac)').length`)) === 9)
  ok('㉒ ④ 的横条里也有那 3 类（置灰、恒空）',
    (await b.evalJS(`document.querySelectorAll('.ev-bars .ev-bar.is-fac').length`)) === 3)
  // ⚠️ 点了没副作用 —— 这一条**没有**独立的阳性对照：「计数会变」这件事由 ⑳ 证明过
  //    （同一枚 `.row.all` 的读数在时间范围切换时确实会变）⇒ 这里「不变」是有信息的。
  const allBefore = await b.evalJS(`(document.querySelector('.ev-rail .row.all .c') || {}).textContent`)
  await b.evalJS(`(() => { const e = document.querySelector('.ev-rail .row.is-fac'); if (e) e.click(); return true })()`)
  await sleep(250)
  const allAfter = await b.evalJS(`(document.querySelector('.ev-rail .row.all .c') || {}).textContent`)
  ok('㉒ 点那 3 行**什么也不发生**（`disabled` 真挡住了，不是「点了没反应」靠运气）',
    allBefore === allAfter && (await b.evalJS(`document.querySelectorAll('.log-line').length`)) === 161,
    { 前: allBefore, 后: allAfter })

  // ══ ㉓ 第 ⑦ 步（用户 2026-10-16：滚动条 / 折线标签 / 对比度 / 图标 / 人话化 / 折行 / 树形 / 对象可点）══
  // ⚠️ 这一段**必须在 ⑯ 之前**：⑯ 结尾会把数据源切到「模拟日常」，之后再量 ③ 的行就是演示流那批，
  //    而本段的判据全都要在**真流那 161 行**上量（图标、树形、对象可点都得有内容才量得出来）。
  //
  // ⚠️ 先把 ⑤ 打开：㉓1 要量 `.chain` 的滚动条、㉓8 要量链里每条的技术行 —— 两个都要求抽屉里
  //    真的挂着一条链。不主动开的话，本段就悄悄依赖「⑧ 点过的那一行还没被别处关掉」，
  //    那种依赖一旦断掉，红的是「找不到 .chain」而不是它想验的东西。
  //   ⚠️ 点哪一行有讲究：`openTrace()` 对**已经打开的那一行**是**收起**（`if (openedTrace === tid)
  //      drawerOpen = !drawerOpen`）。盲点「第一条带 traceId 的行」，若它正好就是 ⑧ 打开过的那条，
  //      这一下把抽屉**关掉**了 —— 于是 ㉓1 量到 `.chain` 不存在、㉓8 量到 0 条链，红的是
  //      「找不到元素」而不是它们想验的东西。这里挑一条 `.sel`（= 当前打开的那条）**以外**的行。
  const openDrawer = () => b.evalJS(`(() => {
    if (document.querySelector('.chain')) return 'already'
    const cand = document.querySelector('.log-line[role="button"]:not(.sel)')
      || document.querySelector('.log-line[role="button"]')
    if (!cand) return 'none'
    cand.click()
    return 'clicked'
  })()`)
  const drawerTry = await openDrawer()
  await sleep(400)
  if (!(await b.evalJS(`!!document.querySelector('.chain')`))) {
    await openDrawer()               // 兜底：万一上面那一下仍是收起，再点一下
    await sleep(400)
  }
  ok('㉓ 起点：⑤ 抽屉里真的挂着一条链（㉓1 要量 `.chain` 的滚动条、㉓8 要量链里每条的技术行）',
    await b.evalJS(`!!document.querySelector('.chain')`), drawerTry)

  // ── ㉓1 滚动条：宽度 ≤8px、轨道不抢戏、滑块是真色（读的是 CSSOM 里的**规则**，不是渲染结果）
  //   ⚠️ 为什么不用 `offsetWidth - clientWidth`：headless 下不可靠（经典 headless 恒 0，
  //      `--headless=new` 才给 15）—— 那个量的是**渲染出来的**滚动条，本段的判据是「我们写的规则」。
  //      ⚠️ 代价要说清：这一条**不能**证明「屏幕上真的变窄了」，只能证明「规则接上了」。
  //      「肉眼确实变窄」由 `out-b7a3-eventlog*.png` 那几张图承担（人看）。
  const sb = await b.evalJS(`(() => {
    const out = {}
    for (const sel of ['.ev-list', '.ev-rail .rows', '.ev-live', '.chain']) {
      const el = document.querySelector(sel)
      if (!el) { out[sel] = null; continue }
      const cs = getComputedStyle(el, '::-webkit-scrollbar')
      const ct = getComputedStyle(el, '::-webkit-scrollbar-track')
      const ch = getComputedStyle(el, '::-webkit-scrollbar-thumb')
      out[sel] = { w: cs.width, h: cs.height, track: ct.backgroundColor, thumb: ch.backgroundColor }
    }
    return out
  })()`)
  const sbList = sb['.ev-list']
  ok('㉓1 ③ 列表的滚动条**规则**接上了：宽 ≤ 8px（不是 15px）',
    !!sbList && parseFloat(sbList.w) > 0 && parseFloat(sbList.w) <= 8, sbList)
  ok('㉓1 轨道**几乎不可见**（transparent，不是浅灰块）',
    !!sbList && /rgba\(0, 0, 0, 0\)|transparent/.test(sbList.track), sbList && sbList.track)
  ok('㉓1 滑块**是真色**（不是 transparent —— 否则根本看不见滚动条在哪）',
    !!sbList && !/rgba\(0, 0, 0, 0\)/.test(sbList.thumb), sbList && sbList.thumb)
  ok('㉓1 四个滚动容器（② 栏 / ③ 列表 / ④ 栏 / ⑤ 链）都接上了同一条规则',
    Object.values(sb).every((v) => v && v.w === sbList.w), sb)
  // 放大图：③ 的**右缘**（滚动条那一格）+ ② 栏右缘 —— 上面那三条只证明「规则接上了」，
  // 「屏幕上真的只是一条细线」由这两张图承担（人看）。滑块要可见 ⇒ 先把列表滚到底。
  await b.evalJS(`(() => { const el = document.querySelector('.ev-list'); el.scrollTop = el.scrollHeight; return el.scrollTop })()`)
  await sleep(300)
  const sbShot1 = await zoomShot('.ev-list', `${WORK}zoom-b7a3-scrollbar-list.png`, { 宽: 16, 高: 260, 右缘: true, scale: 8 })
  const sbShot2 = await zoomShot('.ev-rail .rows', `${WORK}zoom-b7a3-scrollbar-rail.png`, { 宽: 16, 高: 260, 右缘: true, scale: 8 })
  ok('㉓1 两张滚动条放大图都裁出来了（③ 列表右缘 / ② 栏右缘）',
    !!sbShot1 && !!sbShot2, { 列表: sbShot1, 栏: sbShot2 })

  // ── ㉓2 折线图：图内小字标签在，且**没有**轴线 / 刻度线（用户的判据是「标量级与范围，不要坐标轴」）
  const sparkLb = await b.evalJS(`(() => {
    const s = document.querySelector('.ev-spark')
    if (!s) return null
    return {
      ax: [...s.querySelectorAll('.ax span')].map(e => e.textContent.trim()),
      折线数: s.querySelectorAll('polyline').length,
      线数: s.querySelectorAll('line').length,
      路径数: s.querySelectorAll('path').length,
      标签色: getComputedStyle(s.querySelector('.ax')).color,
    }
  })()`)
  ok('㉓2 折线图有**两排**图内小字（量级 + 时间范围）', !!sparkLb && sparkLb.ax.length === 4, sparkLb && sparkLb.ax)
  ok('㉓2 y 轴标了「峰值 X/s」与「当前 Y/s」（量级）',
    !!sparkLb && sparkLb.ax.some(t => /^峰值 \d/.test(t)) && sparkLb.ax.some(t => /^当前 \d/.test(t)), sparkLb && sparkLb.ax)
  ok('㉓2 x 轴标了时间范围（−60s / now）',
    !!sparkLb && sparkLb.ax.includes('−60s') && sparkLb.ax.includes('now'), sparkLb && sparkLb.ax)
  ok('㉓2 **不画坐标轴**：图里只有那一条折线，没有任何 <line> / <path>（轴线、刻度线都没画）',
    !!sparkLb && sparkLb.折线数 === 1 && sparkLb.线数 === 0 && sparkLb.路径数 === 0, sparkLb)

  // ── ㉓3 横条对比度：前景 / 背景 ≥ 3:1（用户给的线）。⚠️ 从**计算色**算 WCAG 比，不手抄色值
  const contrast = await b.evalJS(`(() => {
    const lum = (c) => {
      const m = c.match(/\\d+/g).map(Number)
      const f = (v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4) }
      return 0.2126 * f(m[0]) + 0.7152 * f(m[1]) + 0.0722 * f(m[2])
    }
    const ratio = (a, bb) => { const [x, y] = [lum(a), lum(bb)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05) }
    const one = (bar) => {
      const f = getComputedStyle(bar.querySelector('.fill')).backgroundColor
      const t = getComputedStyle(bar.querySelector('.track')).backgroundColor
      return { 填: f, 轨: t, 比: Number(ratio(f, t).toFixed(2)) }
    }
    const kindBars = [...document.querySelectorAll('.ev-bars:not(.sev) .ev-bar:not(.is-fac)')]
    const sevBars = [...document.querySelectorAll('.ev-bars.sev .ev-bar')]
    return { 类: kindBars.map(one), 严重度: sevBars.map(one) }
  })()`)
  ok('㉓3 ④ 类计数九条的前景/背景对比 **全部 ≥ 3:1**（原来 1.05:1，等于看不见）',
    contrast.类.length === 9 && contrast.类.every(x => x.比 >= 3), contrast.类)
  ok('㉓3 ④ 严重度三条的对比也 ≥ 3:1（本来是好的，这条是防回退）',
    contrast.严重度.length === 3 && contrast.严重度.every(x => x.比 >= 3), contrast.严重度)

  // ── ㉓4 图标四处：② 九类 / ④ 类计数九条 / ④ 严重度三条（③ 事件流本来就有，见 ⑧）
  const icons = await b.evalJS(`(() => {
    const sig = (el) => { const s = el.querySelector('svg'); return s ? s.innerHTML : null }
    const rail = [...document.querySelectorAll('.ev-rail .rows .row:not(.all):not(.is-fac)')]
    const kindBars = [...document.querySelectorAll('.ev-bars:not(.sev) .ev-bar:not(.is-fac)')]
    const sevBars = [...document.querySelectorAll('.ev-bars.sev .ev-bar')]
    return {
      railN: rail.length, railIc: rail.filter(r => r.querySelector('.ic svg')).length,
      railSig: new Set(rail.map(r => sig(r.querySelector('.ic')))).size,
      kindIc: kindBars.filter(x => x.querySelector('.nm svg')).length,
      sevIc: sevBars.filter(x => x.querySelector('.nm svg')).length,
      sevSig: new Set(sevBars.map(x => sig(x.querySelector('.nm')))).size,
    }
  })()`)
  ok('㉓4 ② 过滤栏九类**每类前都有图标**', icons.railN === 9 && icons.railIc === 9, icons)
  ok('㉓4 ② 九类的图标**两两不同**（9 个不同签名 —— 全用同一个等于没按类分）',
    icons.railSig === 9, icons)
  ok('㉓4 ④ 类计数九条都有图标', icons.kindIc === 9, icons)
  ok('㉓4 ④ 严重度三条都有图标，且两两不同', icons.sevIc === 3 && icons.sevSig === 3, icons)

  // ── ㉓5 折行：2 行上限 + 技术字段**默认常显**（收尾批 2：不再 hover 才出）
  const lnCap = await b.evalJS(`(() => {
    const ln = document.querySelector('.log-line .ln')
    if (!ln) return null
    const cs = getComputedStyle(ln)
    const lh = parseFloat(getComputedStyle(document.querySelector('.log-line')).lineHeight)
    const dt = document.querySelector('.log-line .ms .dt')
    const tf = dt ? dt.querySelector('.tf') : null
    return {
      maxH: cs.maxHeight, 行lineHeight: lh, 两行: Math.round(lh * 2),
      maxHpx: Math.round(parseFloat(cs.maxHeight)),
      dtDisplay: dt ? getComputedStyle(dt).display : '(没有 .dt)',
      技术项数: document.querySelectorAll('.log-line .ms .dt .tf').length,
      首对键: tf ? (tf.querySelector('.tf-k') || {}).textContent : null,
      首对值: tf ? (tf.querySelector('.tf-v') || {}).textContent : null,
      省略号: cs.textOverflow,
    }
  })()`)
  ok('㉓5 `.ln` 的 2 行上限 = 2 × 行高（不是 `-webkit-line-clamp`，那个自带 23px 高）',
    !!lnCap && lnCap.maxHpx === lnCap.两行, lnCap)
  ok('㉓5 技术字段（`.dt`）**默认就显示**（收尾批 2：一睁眼看得见五要素，不等 hover）'
    + '，且确实排出了 `中文键 值` 对',
    !!lnCap && lnCap.dtDisplay === 'inline' && lnCap.技术项数 > 0
    && lnCap.首对键 !== null && lnCap.首对值 !== null, lnCap)
  ok('㉓5 **没有** `text-overflow: ellipsis`（用户：「不截断，删掉它」）',
    !!lnCap && lnCap.省略号 === 'clip', lnCap && lnCap.省略号)
  // 真 hover：把鼠标**移到**某一行上（`Input.dispatchMouseEvent`），再读 —— 只查规则不算数。
  //   ⚠️ 挑行要挑**在视口里**的：③ 是内部滚动容器、且会自动滚到底，DOM 里第一条 `.log-line`
  //      往往已经滚到屏幕上方之外 —— 那种行的 `getBoundingClientRect()` 算出的 y 是负数，
  //      `moveTo` 过去鼠标并不在那行上（`matches(':hover')` 恒 0，红的是「hover 没生效」的假象）。
  //      这里用 `elementFromPoint` 反查：只认「鼠标真能压到那一行」的行。
  //   ⚠️ 再挑**本身没被 2 行夹住的**行（`scrollHeight <= clientHeight`）：只有这种行，
  //      用户说的「≤2 行时 hover 高度不变」才可判 —— 被夹住的行 hover 本来就该变高。
  const fitRow = await b.evalJS(`(() => {
    for (const e of document.querySelectorAll('.log-line')) {
      const ln = e.querySelector('.ln')
      if (!ln || ln.scrollHeight > ln.clientHeight + 1) continue
      const x = e.getBoundingClientRect()
      if (x.top < 0 || x.bottom > innerHeight) continue
      const cx = Math.round(Math.max(1, x.left + 40)), cy = Math.round(x.top + x.height / 2)
      const hit = document.elementFromPoint(cx, cy)
      if (!hit || !e.contains(hit)) continue
      return { x: cx, y: cy, 高度: ln.clientHeight, 技术项数: e.querySelectorAll('.ms .tf').length }
    }
    return null
  })()`)
  if (fitRow) await b.moveTo(fitRow.x, fitRow.y)
  await sleep(250)
  if (fitRow && !(await b.evalJS(`!!document.querySelector('.log-line:hover')`))) {
    await b.moveTo(fitRow.x + 1, fitRow.y)   // 兜底：有些 headless 要第二下才把 hover 状态派下来
    await sleep(250)
  }
  const hovered = await b.evalJS(`(() => {
    const rs = [...document.querySelectorAll('.log-line')]
    const on = rs.filter(r => r.matches(':hover'))
    if (!on.length) return { hover: 0 }
    const ln = on[0].querySelector('.ln')
    const dt = on[0].querySelector('.ms .dt')
    return {
      hover: on.length,
      高度: ln ? ln.clientHeight : null,
      dtDisplay: dt ? getComputedStyle(dt).display : null,
      技术项数: on[0].querySelectorAll('.ms .tf').length,
    }
  })()`)
  ok('㉓5 **真 hover 到一行 ≤2 行的行 ⇒ 高度不变**（纯 CSS：`.ln` 已装得下，`max-height:none` 不产生视觉变化）',
    !!fitRow && fitRow.技术项数 > 0 && hovered.hover > 0
    && hovered.dtDisplay === 'inline' && Math.abs(hovered.高度 - fitRow.高度) <= 1,
    { fitRow, hovered })
  await b.evalJS(`document.querySelector('.ev-list').dispatchEvent(new MouseEvent('mouseleave'))`)

  // ── ㉓5b 折行的**几何**：续行缩进 2ch、最多 2 行、hover 展开（用户规则三 / 四）
  //   ⚠️ 为什么要**注入一段长文本**：这套 mock 数据里最长的标题也就 20 来字，在 1440 宽下
  //      **一行就装得下** ⇒ 不注入就量不到折行（量不到 ≠ 折行对了）。注入的只是**一个 `.ms b`
  //      的文本**、且只在本段内存在（量完立刻还原），量的是 CSS 的折行几何，不是编数据。
  //      ⚠️ 注入的是**树内行**（`is-tree`）：用户规则四专门要求「树内行折行时续行不带前导符」。
  //   ⚠️ 判据用 `Range.getClientRects()` 逐**行盒**读：`rects[0]` = 首行那一行盒、
  //      `rects[1]` = 续行行盒。**不能拿 `rects[0].left` 当基准** —— 首行那一行的左端被前导符、
  //      时刻、图标这些行内元素推到了右边（实测 732px），拿它减续行会得出负数。基准取
  //      「这一行文本流的**首行起点**」= `.ln` 盒左缘 + `padding-left` + `text-indent`（见代码里的注释）。
  //   ⚠️ 量之前先把鼠标**移开**（移到页面角落）：行上有 `:hover` 时 `max-height: none` 生效、
  //      2 行上限**解除** —— 那时量到的 `clientHeight` 是「展开后」的高度，永远等于 `scrollHeight`，
  //      于是「夹在 2 行」这条判据会**假绿**（上一版就是鼠标正停在同一行上，量到 55 = 未夹）。
  await b.moveTo(4, 4)
  await sleep(250)
  const wrapInfo = await b.evalJS(`(() => {
    if (document.querySelector('.log-line:hover')) return { 出错: '量之前鼠标仍压在行上 —— 2 行上限被 hover 解除了' }
    const inView = (e) => { const x = e.getBoundingClientRect(); return x.top >= 0 && x.bottom <= innerHeight }
    const all = [...document.querySelectorAll('.log-line')]
    // 优先挑**树内行**：用户规则四专门要求「树内行折行时续行不带前导符」。
    const row = all.find(e => e.classList.contains('is-tree') && inView(e)) || all.find(inView)
    if (!row) return { 出错: '视口里没有可量的行' }
    const bt = row.querySelector('.ms b')
    const before = bt.textContent
    bt.dataset.wrapOrig = before
    bt.textContent = before + '（' + '这一条消息故意写得很长，用来量折行的几何：续行挂在小缩进上、不是对齐消息列，也不该再画一遍前导符'.repeat(2) + '）'
    const ln = row.querySelector('.ln')
    const lnCS = getComputedStyle(ln)
    const rowCS = getComputedStyle(row)
    const lh = parseFloat(rowCS.lineHeight)
    // 「首行起点」= 这一行**文本流第一行从哪儿起笔** —— 用 .ln 自己的盒 + 它自己的缩进值算，
    // 不写死任何数字：border 左缘 + padding-left + text-indent（悬挂缩进那对值互相抵掉时
    // 就等于 border 左缘）。续行缩进量的是「相对**这个起点**」，因为首行那一行的左端被前导符、
    // 时刻这些**行内元素**推到了右边（首行文字实际从 732 起），拿首行文字当基准会量成负数。
    const 首行起点 = ln.getBoundingClientRect().left + parseFloat(lnCS.paddingLeft) + parseFloat(lnCS.textIndent)
    // 量的只是 b 元素（消息本体）的行盒，不掺对象胶囊 / 技术字段
    const rg = document.createRange(); rg.selectNodeContents(bt)
    const rects = [...rg.getClientRects()].filter((x) => x.width > 1)
    // 前缀会不会跟着折行：.pre 是**行内块**，一行里只会有一个；再量它自己的盒高 ——
    // 高 = 一行行高 ⇒ 它没被折成两行（若续行又画一遍 └─，这个盒子会变成两行高）。
    const preEl = row.querySelector('.pre')
    // 1 个 ch 的实测宽（用**这一行自己的 font**量，不是拍脑袋 8px）
    const probe = document.createElement('span')
    probe.textContent = '0000'
    probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;font:' + rowCS.font
    document.body.appendChild(probe)
    const ch = probe.getBoundingClientRect().width / 4
    probe.remove()
    return {
      lnH: ln.clientHeight, lnScroll: ln.scrollHeight, lh, 两行: Math.round(lh * 2),
      行数: rects.length,
      首行左: Math.round(rects[0].left), 续行左: Math.round(rects[1].left),
      续行缩进: +(rects[1].left - 首行起点).toFixed(1),
      续行绝对左: Math.round(rects[1].left), 首行起点: Math.round(首行起点),
      消息列相对行左: Math.round(rects[0].left - row.getBoundingClientRect().left),
      ch: +ch.toFixed(2), 前缀元素数: row.querySelectorAll('.pre').length,
      前缀盒高: Math.round(preEl.getBoundingClientRect().height),
      树内行: row.classList.contains('is-tree'),
      首行前缀: row.querySelector('.pre').textContent,
    }
  })()`)
  ok('㉓5b 长消息**真的折起来了**（≥3 行，才量得到「最多 2 行」这条上限）',
    !!wrapInfo && wrapInfo.行数 >= 3, wrapInfo)
  ok('㉓5b 折行被夹在 **2 行**（`clientHeight` = 2 × 行高，第 3 行起看不见）',
    !!wrapInfo && wrapInfo.lnH === wrapInfo.两行 && wrapInfo.lnScroll > wrapInfo.lnH, wrapInfo)
  ok('㉓5b 续行缩进 ≈ **2ch**（用户规则三：「2-3 字符就够」；基准是**这一行文本流的首行起点**）',
    !!wrapInfo && wrapInfo.续行缩进 >= wrapInfo.ch * 1.8 && wrapInfo.续行缩进 <= wrapInfo.ch * 3.2,
    wrapInfo && { 续行缩进: wrapInfo.续行缩进, ch: wrapInfo.ch, 上下限: [+(wrapInfo.ch * 1.8).toFixed(1), +(wrapInfo.ch * 3.2).toFixed(1)] })
  ok('㉓5b 续行起点**落在首行起点的右侧、首行文字的左侧**（既缩进了一点点，又没退到消息列）',
    !!wrapInfo && wrapInfo.续行绝对左 > wrapInfo.首行起点 && wrapInfo.续行绝对左 < wrapInfo.首行左,
    wrapInfo && { 首行起点: wrapInfo.首行起点, 续行绝对左: wrapInfo.续行绝对左, 首行文字左: wrapInfo.首行左 })
  ok('㉓5b 续行**不对齐消息列**（对齐消息列 = 用户说的「预留大段空白」）',
    !!wrapInfo && wrapInfo.续行缩进 < wrapInfo.消息列相对行左 / 2, wrapInfo && { 续行缩进: wrapInfo.续行缩进, 消息列相对行左: wrapInfo.消息列相对行左 })
  ok('㉓5b 折了多行，前缀**仍然只有一份、且只有一行高**（⇒ 续行没有第二遍 `└─`）',
    !!wrapInfo && wrapInfo.前缀元素数 === 1 && wrapInfo.前缀盒高 <= wrapInfo.lh + 1,
    wrapInfo && { 前缀元素数: wrapInfo.前缀元素数, 前缀盒高: wrapInfo.前缀盒高, 行高: wrapInfo.lh, 行数: wrapInfo.行数, 树内行: wrapInfo.树内行 })
  await b.shot(`${WORK}out-b7a3-eventlog-wrap.png`)
  // ⚠️ 先把注入的那一行滚进视口：`.ev-list` 是内部滚动容器，`getBoundingClientRect().top`
  //    为负时裁图会从容器**上缘**开始（裁到别的行）—— 那是「图上没有那一行」，不是「没折行」。
  await b.evalJS(`(() => {
    const r = document.querySelector('.log-line:has(.ms b[data-wrap-orig])')
    if (r) r.scrollIntoView({ block: 'center' })
    return !!r
  })()`)
  await sleep(300)
  const wrapShot = await zoomShot('.log-line:has(.ms b[data-wrap-orig])', `${WORK}zoom-b7a3-wrap.png`, { 宽: 660, 高: 90, scale: 2 })
  ok('㉓5b 折行的放大图裁出来了（看续行那 2ch 的缩进与第 3 行被夹掉）', !!wrapShot, wrapShot)
  // hover 那一行 ⇒ 上限解除（用户规则三：「hover 展开全文」）—— 同一行、真鼠标事件。
  const wrapBox = await b.evalJS(`(() => {
    const r = [...document.querySelectorAll('.log-line')].find(e => e.querySelector('.ms b').dataset.wrapOrig !== undefined)
    if (!r) return null
    const x = r.getBoundingClientRect()
    return { x: Math.round(x.left + 40), y: Math.round(x.top + 12) }
  })()`)
  if (wrapBox) await b.moveTo(wrapBox.x, wrapBox.y)
  await sleep(300)
  const wrapHover = await b.evalJS(`(() => {
    const r = [...document.querySelectorAll('.log-line')].find(e => e.querySelector('.ms b').dataset.wrapOrig !== undefined)
    if (!r || !r.matches(':hover')) return null
    const ln = r.querySelector('.ln')
    return { lnH: ln.clientHeight, lnScroll: ln.scrollHeight, 展开: ln.clientHeight >= ln.scrollHeight }
  })()`)
  ok('㉓5b hover 那一行 ⇒ **2 行上限解除**（`clientHeight` 追平 `scrollHeight`，全文露出来）',
    !!wrapHover && wrapHover.展开, wrapHover)
  await b.shot(`${WORK}out-b7a3-eventlog-wrap-hover.png`)
  // 还原注入的文本（后面 ⑯ 还要在真流数据上断言，不许带着这段假消息走）
  await b.evalJS(`(() => {
    for (const b of document.querySelectorAll('.ms b[data-wrap-orig]')) { b.textContent = b.dataset.wrapOrig; delete b.dataset.wrapOrig }
    return document.querySelectorAll('.ms b[data-wrap-orig]').length
  })()`)
  await b.evalJS(`document.querySelector('.ev-list').dispatchEvent(new MouseEvent('mouseleave'))`)
  await sleep(200)

  // ── ㉓6 树形：前缀拆两段 + 树形行提亮（用户裁定 B 甲：**画法**问题，不是判据问题）
  const treeShape = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.log-line')]
    const per = (r) => {
      const p = r.querySelector('.pre')
      const rl = p.querySelector('.rl'), gl = p.querySelector('.gl')
      return {
        两段: !!rl && !!gl, pre: p.textContent,
        gl色: gl ? getComputedStyle(gl).color : null,
        rl色: rl ? getComputedStyle(rl).color : null,
        tree: r.classList.contains('is-tree'),
      }
    }
    const all = rows.map(per)
    const treeRows = all.filter(x => x.tree)
    const flatRows = all.filter(x => !x.tree)
    return {
      总数: all.length, 两段数: all.filter(x => x.两段).length,
      树形数: treeRows.length, 平铺数: flatRows.length,
      树形gl色: [...new Set(treeRows.map(x => x.gl色))],
      平铺gl色: [...new Set(flatRows.map(x => x.gl色))],
      样例: all.filter(x => x.tree).slice(0, 3),
    }
  })()`)
  ok('㉓6 每行的前导符都**拆成了两段**（`rl` 脚手架 / `gl` 分叉）', treeShape.两段数 === treeShape.总数, treeShape)
  ok('㉓6 **树形行确实存在**（不是「树形没触发」：③ 里有 `is-tree` 的行）',
    treeShape.树形数 > 0, treeShape)
  ok('㉓6 树形行的分叉色与平铺行**不同**（提亮生效 —— 全同色就等于没提亮）',
    treeShape.树形gl色.every(c => !treeShape.平铺gl色.includes(c)), treeShape)
  const treeShot = await zoomShot('.ev-list', `${WORK}zoom-b7a3-prefix.png`, { 宽: 200, 高: 300, 左: 0, scale: 3 })
  ok('㉓6 前导符列的放大图裁出来了（`├─` / `└─` / 竖向脚手架 一起看）', !!treeShot, treeShot)

  // ── ㉓7 对象可点：**没有 objectId 的对象不许画成可点**（原模板把 `.p` 硬编码，这是回归判据）
  const obClick = await b.evalJS(`(() => {
    const obs = [...document.querySelectorAll('.log-line .ms .ob')]
    return {
      n: obs.length,
      带下划线: obs.filter(e => e.classList.contains('p')).length,
      无下划线: obs.filter(e => !e.classList.contains('p')).length,
      有title: obs.filter(e => e.getAttribute('title')).length,
      无title: obs.filter(e => !e.getAttribute('title')).length,
    }
  })()`)
  // ⚠️ 「无则都无」那半**在本段是空真**（两侧都是 0）—— 真正的负面对照在 ㉔5：mock 里
  //    `notify` 那条 `objectId` 为空，页面应当**根本不画**对象列 ⇒ 见 ㉔5 那条断言。
  ok('㉓7 可点提示（`.p` + `title`）**与「有没有 objectId」一致**：有则都有、无则都无',
    obClick.带下划线 === obClick.有title && obClick.无下划线 === obClick.无title, obClick)
  ok('㉓7 对象列**确实画出了**可点的那些（不是一条都没有）', obClick.带下划线 > 0, obClick)

  // ── ㉓8 ⑤ 详情层：中英文**两层都在**（用户：「⚠️ 两个都不能省」）
  //   ⚠️ 判据要**排除不是事件的那两行**：链首 / 链尾是 `is-ends`（「请求开始」「请求结束 · 总耗时 42 ms」），
  //      它们来自 **trace 记录**（method / path / ms），帧里**没有**事件字段 —— 给它们硬凑一行
  //      `kind=… severity=…` 就是编字段。所以判据写「链里**每一条事件**都有技术行」，`is-ends` / `is-miss` 不算。
  //   ⚠️ 载荷里的「人话」要把嵌套的 `.tech` 抠掉再读：`.tech` 在 `.d` **里面**，
  //      直接读 `d.textContent` 会把技术行也算进人话（看上去像「人话里混着 kind=」，其实没那回事）。
  const readChain = () => b.evalJS(`(() => {
    const items = [...document.querySelectorAll('.chain .chain-i')]
    const evs = items.filter(i => !i.classList.contains('is-ends') && !i.classList.contains('is-miss'))
    const withTech = evs.filter(i => i.querySelector('.tech'))
    const dText = (i) => {
      const d = i.querySelector('.d')
      if (!d) return ''
      const c = d.cloneNode(true)
      const t = c.querySelector('.tech'); if (t) t.remove()
      // ⚠️ 反斜杠要写两个。这一整段是**模板字符串**，在它里面单个 \s 会被求值成裸的字母 s
      //    （模板串的非法转义落回原字符）⇒ 页面里拿到的是 /s+/g，只替掉字母 s。
      //    上一版这里就是单反斜杠（当时没有断言依赖它，所以一直没露头）。
      // ⚠️ 这段注释里**不能出现反引号**：整段是模板字符串，一个反引号就把它提前闭合了。
      return c.textContent.replace(/\\s+/g, ' ').trim().slice(0, 40)
    }
    return {
      链里条数: items.length, 事件条数: evs.length, 非事件条数: items.length - evs.length,
      带技术行: withTech.length,
      样例: withTech.slice(0, 2).map(i => ({
        人话: dText(i),
        技术: (i.querySelector('.tech') || {}).textContent,
      })),
      技术全文: withTech.map(i => (i.querySelector('.tech') || {}).textContent || ''),
      技术行色: withTech.length ? getComputedStyle(withTech[0].querySelector('.tech')).color : null,
      技术行字体: withTech.length ? getComputedStyle(withTech[0].querySelector('.tech')).fontFamily.split(',')[0] : null,
    }
  })()`)
  let chainTwo = await readChain()
  if (chainTwo.事件条数 === 0) {
    // 兜底：万一抽屉停在一条「没有事件、也没有收尾记录」的请求上（链里只有 `is-miss`），
    // 换一条带 traceId 的行再看一次 —— 否则红的是「这条链恰好是空的」，不是「两层没做」。
    await b.evalJS(`(() => {
      const c = document.querySelector('.log-line[role="button"]:not(.sel)')
      if (c) c.click()
      return true
    })()`)
    await sleep(400)
    chainTwo = await readChain()
  }
  ok('㉓8 ⑤ 链里**每一条事件**都带技术字段那一行（中英文两层，不是只留中文）',
    chainTwo.事件条数 > 0 && chainTwo.带技术行 === chainTwo.事件条数, chainTwo)
  ok('㉓8 链首/链尾那两行**不是事件**（请求开始 / 请求结束来自 trace 记录）⇒ 不该有技术行',
    chainTwo.非事件条数 === 2, chainTwo)
  ok('㉓8 那行技术字段是**「中文（英文原键）= 值」**（收尾批 2）：含 `类型（kind）=` / `级别（severity）=`，'
    + '且 `fields` 的键也按这个格式带英文原键（值照原文，可拿去 grep）',
    chainTwo.技术全文.length > 0
    && chainTwo.技术全文.every(s => /类型（kind）=/.test(s) && /级别（severity）=/.test(s))
    && chainTwo.技术全文.some(s => /（cache）= goodsHome/.test(s) && /来源环（src）= cache#7/.test(s))
    && chainTwo.技术全文.some(s => /weirdKey（weirdKey）= 未来值/.test(s)),
    chainTwo.技术全文.slice(0, 3))

  // ══ ㉔ 3a.3 前端 4 块（A 句子 · B 色系 · C 端点标签 · D 图标间距；用户 2026-10-17 放行）════
  // ⚠️ 与 ㉓ 同一条约束：**必须在 ⑯ 之前** —— ⑯ 结尾把数据源切到「模拟日常」，而本段的判据
  //    全都要在**真流那 161 行**上量（句子/短号/对象可点都得有从帧里来的字段才量得出来）。
  // ⚠️ 量的是**渲染出来的 DOM**，不是去 import 组件里的 `sentence()` 直接调：后者只证明
  //    「函数写得对」，前者才是「用户看见了那句话」（同「校验器报 OK ≠ 没问题」）。
  //    唯一的例外是 ㉔1 —— C 块是一张**模块级表**，页面上只有两个消费点，表本身只能直接读。
  // ⚠️ 先把鼠标挪开：下面几条要量**默认态**（hover 会解除 2 行上限、并把 `.dt` 显出来）。
  await b.moveTo(4, 4)
  await sleep(250)

  // ── ㉔1 C 块：`api-labels.js` 这张表本身（条数 / 键形 / 兜底 / 三个取用口）
  // ⚠️ 不能直接 `import('.../api-labels.js')`：那个目录的 `package.json` 没有 `"type":"module"`
  //    ⇒ Node 按 **CJS** 解析 `.js`，见到 `export` 直接抛 SyntaxError（本轮实测踩过，
  //    报错停在文件第 43 行 `export const API_ENDPOINTS`）。浏览器里它是 webpack 编的 ESM，
  //    与 Node 的判定无关 ⇒ 这里把**同一份源码文本**用 `data:` URL 当模块喂进去。
  const apiSrc = fs.readFileSync(new URL('../../Mall-management-web/mall-management-system/src/config/labels/api-labels.js', import.meta.url), 'utf8')
  const api = await import('data:text/javascript;charset=utf-8,' + encodeURIComponent(apiSrc))
  const apiKeys = Object.keys(api.API_ENDPOINTS)
  ok('㉔1 `api-labels.js`：**146 条**（= 全仓 `*Controller.java` 的方法级 `@(Post|Get|Put|Delete)Mapping` 数），且无重复键',
    api.API_ENDPOINT_COUNT === 146 && apiKeys.length === 146,
    { 自报: api.API_ENDPOINT_COUNT, 真实键数: apiKeys.length })
  ok('㉔1 每条键都是 `METHOD /path` 形（**带方法是刻意的**：同路径两条方法要能分开）',
    apiKeys.every((k) => /^(POST|GET|PUT|DELETE) \//.test(k)),
    apiKeys.filter((k) => !/^(POST|GET|PUT|DELETE) \//.test(k)))
  ok('㉔1 **同路径两条方法真的都登记了**（`/admin/observe/attack/config` 的 POST 写配置与 GET 读配置各一条）',
    apiKeys.includes('POST /admin/observe/attack/config') && apiKeys.includes('GET /admin/observe/attack/config'),
    apiKeys.filter((k) => k.includes('/admin/observe/attack/config')))
  ok('㉔1 认得出：query / 尾斜杠 / 大小写都归一到同一条中文名',
    api.apiLabel('POST', '/goods/goodsUp?t=1') === '修改商品'
    && api.apiLabel('post', '/goods/goodsUp/') === '修改商品'
    && api.apiLabel('POST', '/goods/goodsUp') === '修改商品',
    [api.apiLabel('POST', '/goods/goodsUp?t=1'), api.apiLabel('post', '/goods/goodsUp/')])
  ok('㉔1 兜底：认不出 ⇒ **回落原值**（不是空串、不是「未知接口」）；两者皆空 ⇒ 空串（调用方据此跳槽位）',
    api.apiLabel('POST', '/no-such-endpoint') === 'POST /no-such-endpoint'
    && api.apiLabel('', '') === '' && api.apiLabel(null, undefined) === '',
    [api.apiLabel('POST', '/no-such-endpoint'), api.apiLabel('', '')])
  ok('㉔1 锁名（5 把）/ 缓存区名（8 个）：取值**有界**，认得出给中文、认不出回落原值、空 ⇒ 空串',
    Object.keys(api.LOCK_LABEL).length === 5 && Object.keys(api.CACHE_AREA_LABEL).length === 8
    && api.lockLabel('order-pay') === '支付' && api.lockLabel('nope') === 'nope' && api.lockLabel(null) === ''
    && api.cacheAreaLabel('goodstype') === '商品类型' && api.cacheAreaLabel('nope') === 'nope' && api.cacheAreaLabel('') === '',
    { 锁: Object.keys(api.LOCK_LABEL), 区: Object.keys(api.CACHE_AREA_LABEL) })

  // ── ㉔2 第一层（收尾批 2）：**全 fields + actor + src** 默认就在行里。
  //   ⚠️ 读法：`.dt .tf` 每一对 = `中文键 值`；`.se-act` = 发起人、`.se-src` = 来源环。
  //      括号 `.se-ctx` 只剩 trace / 耗时 两格（见 ㉔3）。
  const readRow = (title) => b.evalJS(`(() => {
    const row = [...document.querySelectorAll('.log-line')].find(r => {
      const b = r.querySelector('.ms b')
      return b && b.textContent === ${JSON.stringify(title)}
    })
    if (!row) return null
    const seg = (sel) => [...row.querySelectorAll(sel + ' > span')].map(s => ({ c: s.className.replace('se-', ''), t: s.textContent }))
    const ctx = seg('.se-ctx')
    const fields = [...row.querySelectorAll('.ms .dt .tf')].map(f => ({
      k: (f.querySelector('.tf-k') || {}).textContent || '',
      v: (f.querySelector('.tf-v') || {}).textContent || '',
    }))
    const textOf = (sel) => { const n = row.querySelector(sel); return n ? n.textContent.trim() : '' }
    return {
      title: row.querySelector('.ms b').textContent,
      字段: fields,
      字段文本: fields.map(f => f.k + ' ' + f.v).join(' · '),
      act: textOf('.se-act'),
      src: textOf('.se-src'),
      ctx,
      ctx文本: ctx.map(s => s.t).join(''),
      有ctx: !!row.querySelector('.se-ctx'),
      对象: (row.querySelector('.ms .ob') || {}).textContent || null,
      可点: !!row.querySelector('.ms .ob.p'),
      短号: [...row.querySelectorAll('.se-ctx .se-a')].map(s => s.textContent),
    }
  })()`)
  const TITLES = ['缓存命中（L1）', '取锁成功（order:pay）', '状态流转 CREATED → null', '库存不足（未扣，防超卖命中）',
    '审计写入', '通知发送', '敏感词拒收', '身份拒绝', '限流拦截', '批量事件 0']
  const rows = {}
  for (const t of TITLES) rows[t] = await readRow(t)
  const gotRow = (t) => rows[t] && (rows[t].字段文本 + ' ｜ ' + rows[t].act + ' ｜ ' + rows[t].src + ' ｜ ' + rows[t].ctx文本)
  // 「某一对键值在不在」—— 按**键名 + 值**一起比，避免「页面上恰好有个同名值」混过去。
  const hasPair = (t, k, v) => !!rows[t] && rows[t].字段.some((f) => f.k === k && f.v === v)
  ok('㉔2 起点：十条参照行（九类各一条 + 一条批量）全在 ③ 里按标题找得到',
    TITLES.every((t) => !!rows[t]), TITLES.filter((t) => !rows[t]))
  // ⚠️ 这条是**目检抓到的回归**（截图里「缓存」只剩「存」）：`.ln` 的悬挂缩进
  //    `text-indent: -2ch` 会被 `.md` 继承，行内块首行左移 24px 后被 `overflow:hidden` 裁掉。
  //    判据读几何（scrollWidth vs clientWidth），不读 CSS 声明。
  const mdBox = await b.evalJS(`(() => {
    const r = [...document.querySelectorAll('.log-line')].find(x => {
      const m = x.querySelector('.md'); return m && m.textContent === '缓存'
    })
    if (!r) return null
    const md = r.querySelector('.md')
    return { 文本: md.textContent, clientWidth: md.clientWidth, scrollWidth: md.scrollWidth,
             textIndent: getComputedStyle(md).textIndent }
  })()`)
  ok('㉔2 类名列（`.md`）**没有被悬挂缩进裁掉第一个字**：`缓存` 的 clientWidth 装得下 scrollWidth，且 text-indent 归零',
    !!mdBox && mdBox.clientWidth >= mdBox.scrollWidth && mdBox.textIndent === '0px', mdBox)

  ok('㉔2 `cache`：第一层就念**缓存区**，且取的是 `fields.cache`（`goodsHome` → 「首页商品」）'
    + ' —— 不是 detail 里的 `goodstype`（两条故意不一致，这是唯一能证明「谁优先」的办法）',
    hasPair('缓存命中（L1）', '缓存区', '首页商品'), gotRow('缓存命中（L1）'))
  ok('㉔2 `lock`：第一层念**锁名**（`order-pay` → 「支付」）；与对象列同值的 `key` 不重复念',
    hasPair('取锁成功（order:pay）', '锁', '支付')
    && !rows['取锁成功（order:pay）'].字段文本.includes('order:pay/O-1'), gotRow('取锁成功（order:pay）'))
  ok('㉔2 `order`（流转·失败档）：`前态` 翻成中文（`CREATED` → 「待付款」）、`动作` 翻；'
    + '`后态` 是 null ⇒ **整格不出现**（不写占位），`原因` 逐字上屏',
    hasPair('状态流转 CREATED → null', '前态', '待付款')
    && hasPair('状态流转 CREATED → null', '动作', '付款')
    && hasPair('状态流转 CREATED → null', '原因', '数据库错误：IllegalStateException：db down')
    && !rows['状态流转 CREATED → null'].字段.some((f) => f.k === '后态'),
    gotRow('状态流转 CREATED → null'))
  ok('㉔2 `stock`：`操作 扣减`（`fields.kind` 的值翻中文）+ `数量 8 件` + `原因`；'
    + '「剩余 N 件」也进了第一层（顶层 `remaining` 那一格，不在 ctx 里）',
    hasPair('库存不足（未扣，防超卖命中）', '操作', '扣减')
    && hasPair('库存不足（未扣，防超卖命中）', '数量', '8 件')
    && hasPair('库存不足（未扣，防超卖命中）', '原因', '防超卖拦截：库存不足（需 8 件，剩余 1 件）')
    && hasPair('库存不足（未扣，防超卖命中）', '剩余', '1 件'), gotRow('库存不足（未扣，防超卖命中）'))
  ok('㉔2 `stock` 的 `sku` 与对象列同值 ⇒ **不重复念**（对象列已经写着 `SKU SKU-0002`）',
    !!rows['库存不足（未扣，防超卖命中）']
    && !rows['库存不足（未扣，防超卖命中）'].字段文本.includes('SKU-0002'),
    gotRow('库存不足（未扣，防超卖命中）'))
  ok('㉔2 `sensitive`：`场景 评论` + `命中数 9` + `总数 20`（数字按字段原样拼，不出现 `9/20undefined`）',
    hasPair('敏感词拒收', '场景', '评论') && hasPair('敏感词拒收', '命中数', '9')
    && hasPair('敏感词拒收', '总数', '20'), gotRow('敏感词拒收'))
  ok('㉔2 `audit`：`发起人 C-9` / 动作 `goods.delete` → 「商家删除商品」/ 原因原样',
    hasPair('审计写入', '发起人', 'C-9') && hasPair('审计写入', '动作', '商家删除商品')
    && hasPair('审计写入', '原因', '违规下架'), gotRow('审计写入'))
  ok('㉔2 **回落分支真的走到了**：`notify` 那条没有 `fields` ⇒ 从 `detail` 抠出三格，'
    + '`to=merchant` 按真值域翻成「商家」',
    hasPair('通知发送', '收件人类型', '商家') && hasPair('通知发送', '事件类型', 'order.paid')
    && hasPair('通知发送', '原因', '下单成功'), gotRow('通知发送'))
  ok('㉔2 `identity` 两格：主体 + 原因',
    hasPair('身份拒绝', '主体', 'C-1') && hasPair('身份拒绝', '原因', '令牌过期'), gotRow('身份拒绝'))
  ok('㉔2 `protection`：范围（长值）+ 附加信息；顶层 `actor` 落成「发起人 C-9（管理员）」，不藏在 ⑤ 里',
    hasPair('限流拦截', '范围', '/admin/observe/attack/run 全路径限流窗口')
    && hasPair('限流拦截', '附加信息', 'qps=20')
    && rows['限流拦截'].act.includes('发起人 C-9（管理员）'), gotRow('限流拦截'))
  ok('㉔2 `fields` 里**认不出的键回落英文原名**（`weirdKey 未来值`），不是空白 —— 用户裁定的兜底口径',
    hasPair('限流拦截', 'weirdKey', '未来值'), gotRow('限流拦截'))

  // ── ㉔3 第一层的尾巴：`src` 单独成段 + 括号只剩 trace / 耗时
  ok('㉔3 `src` 只在 detail 尾部 ⇒ 页面单独念成「来源 cache#7」（不混进 fields 键值对）',
    !!rows['缓存命中（L1）'] && /^来源\s+cache#7$/.test(rows['缓存命中（L1）'].src), gotRow('缓存命中（L1）'))
  ok('㉔3 括号尾带 `trace` + **8 位短号**；事件自身没耗时就退到**请求级**那一格，且标签换成「请求耗时」',
    !!rows['缓存命中（L1）'] && /trace trace000/.test(rows['缓存命中（L1）'].ctx文本)
    && /请求耗时 42 ms/.test(rows['缓存命中（L1）'].ctx文本), gotRow('缓存命中（L1）'))
  ok('㉔3 **耗时两档分开写**：事件自带 `durationUs=12400` ⇒ 「耗时 12 ms」；`850` ⇒ 「耗时 850 µs」'
    + '（亚毫秒不四舍五入成 `0 ms`，那会把「很快」写成「没花时间」）',
    !!rows['状态流转 CREATED → null'] && /耗时 12 ms/.test(rows['状态流转 CREATED → null'].ctx文本)
    && !!rows['敏感词拒收'] && /耗时 850 µs/.test(rows['敏感词拒收'].ctx文本)
    && !/请求耗时/.test(rows['敏感词拒收'].ctx文本),
    { order: gotRow('状态流转 CREATED → null'), sensitive: gotRow('敏感词拒收') })
  ok('㉔3 第一层只把「谁/来源/时间坐标」留在字段与括号里：`ctx` 里**不再重复** fields 的键值对'
    + '（收尾批 2 取消从句后，ctx 只剩 trace / 耗时）',
    !!rows['审计写入'] && !/发起人|动作|原因/.test(rows['审计写入'].ctx文本), gotRow('审计写入'))

  // ── ㉔2b 失败原因（块 7 · 3a.3 后续）：③ 第一层中性、⑤ 只有 reason 段按严重度着色 ──────
  //   ⚠️ ③ 必须保持中性灰：用户裁定「③ 第一层保持中性灰，不变」—— 行的 severity 升到
  //      warn / error 也**不该**让 `.tf-k` / `.tf-v` 变色。负向半边同时钉住「值不是 danger/warning」，
  //      否则把整块染红也能让「等于 ink-2」那一半假绿。
  const reasonInk = await b.evalJS(`(() => {
    const tok = (n) => {
      const d = document.createElement('span')
      d.style.color = 'var(' + n + ')'
      document.body.appendChild(d)
      const c = getComputedStyle(d).color
      d.remove()
      return c
    }
    const rowOf = (title) => [...document.querySelectorAll('.log-line')]
      .find((r) => { const m = r.querySelector('.ms b'); return m && m.textContent.trim() === title })
    const reasonPair = (title) => {
      const r = rowOf(title)
      if (!r) return null
      const tf = [...r.querySelectorAll('.ms .dt .tf')]
        .find((f) => { const k = f.querySelector('.tf-k'); return k && k.textContent.trim() === '原因' })
      if (!tf) return null
      const k = tf.querySelector('.tf-k')
      const v = tf.querySelector('.tf-v')
      return {
        键: getComputedStyle(k).color, 值: getComputedStyle(v).color,
        键文本: k.textContent.trim(), 值文本: v.textContent.trim(),
      }
    }
    return {
      令牌: { ink2: tok('--ink-2'), ink3: tok('--ink-3'), danger: tok('--danger'), warning: tok('--warning') },
      库存: reasonPair('库存不足（未扣，防超卖命中）'),
      订单: reasonPair('状态流转 CREATED → null'),
    }
  })()`)
  ok('㉔2b ③ 第一层：warn / error 行的 `原因` 键值仍是中性灰（`--ink-3` / `--ink-2`），'
    + '**不跟着严重度变色** —— ③ 的视觉口径不变',
    !!reasonInk.库存 && !!reasonInk.订单
    && reasonInk.库存.键 === reasonInk.令牌.ink3 && reasonInk.库存.值 === reasonInk.令牌.ink2
    && reasonInk.订单.键 === reasonInk.令牌.ink3 && reasonInk.订单.值 === reasonInk.令牌.ink2
    && reasonInk.库存.值 !== reasonInk.令牌.warning && reasonInk.订单.值 !== reasonInk.令牌.danger,
    reasonInk)

  const techReason = await b.evalJS(`(() => {
    const tok = (n) => {
      const d = document.createElement('span')
      d.style.color = 'var(' + n + ')'
      document.body.appendChild(d)
      const c = getComputedStyle(d).color
      d.remove()
      return c
    }
    const items = [...document.querySelectorAll('.chain .chain-i')]
    const pick = (kind) => {
      const item = items.find((x) => {
        const c = x.querySelector('.tech')
        return c && c.textContent.includes('类型（kind）= ' + kind)
      })
      if (!item) return null
      const code = item.querySelector('.tech')
      const spans = [...code.children]
      const seps = spans.filter((s, i) => i % 2 === 1)
      const segs = spans.filter((s, i) => i % 2 === 0)
      const reason = segs.find((s) => s.textContent.startsWith('原因（reason）='))
      const others = segs.filter((s) => s !== reason)
      return {
        段数: segs.length,
        分隔符: seps.map((s) => s.textContent),
        分隔符全为点隔: seps.length > 0 && seps.every((s) => s.textContent === ' · '),
        // ⚠️ Vue 的 template v-for 每个 fragment 会留下**空文本锚点**（nodeType 3、内容 ''），
        //    它们不排版、不进 textContent ⇒ 要钉的是「**非空**裸文本节点」= 0（有内容的文本会绕过逐段渲染）。
        非空文本节点数: [...code.childNodes].filter((n) => n.nodeType === 3 && n.textContent !== '').length,
        子元素全为span: [...code.children].every((n) => n.tagName === 'SPAN'),
        reason文本: reason ? reason.textContent : null,
        reason类: reason ? reason.className : null,
        reason色: reason ? getComputedStyle(reason).color : null,
        其余段色: [...new Set(others.map((s) => getComputedStyle(s).color))],
        其余段类: [...new Set(others.map((s) => s.className))],
        整串: code.textContent,
        拼回: segs.map((s) => s.textContent).join(' · '),
      }
    }
    return {
      令牌: { ink3: tok('--ink-3'), danger: tok('--danger'), warning: tok('--warning') },
      库存: pick('stock'), 订单: pick('order'), 缓存: pick('cache'),
      链节点数: items.length,
      li总数: document.querySelectorAll('.chain li').length,
      dReason节点数: document.querySelectorAll('.d-reason').length,
    }
  })()`)
  ok('㉔2b ⑤ 不新增节点：`.tech` 仍是**一个 `<code>`**、子元素只有逐段 `<span>`、段间恰为 ` · `、'
    + '没有非空裸文本节点（Vue 的 fragment 空锚点不算）；`.d-reason` = 0，`<li>` 仍是 9 事件 + 起止 2 = 11',
    !!techReason.库存 && !!techReason.订单
    && techReason.库存.子元素全为span && techReason.库存.非空文本节点数 === 0
    && techReason.库存.分隔符全为点隔 && techReason.库存.整串 === techReason.库存.拼回
    && techReason.订单.整串 === techReason.订单.拼回
    && techReason.dReason节点数 === 0 && techReason.li总数 === 11 && techReason.链节点数 === 11,
    techReason)
  ok('㉔2b ⑤ 原因段按严重度上色：order（error）= `--danger`、stock（warn）= `--warning`，'
    + '同一条里的**其余段仍是 `--ink-3`**（只染那一段，不是整行）',
    techReason.订单.reason类 === 'is-error' && techReason.订单.reason色 === techReason.令牌.danger
    && techReason.库存.reason类 === 'is-warn' && techReason.库存.reason色 === techReason.令牌.warning
    && techReason.订单.其余段色.length === 1 && techReason.订单.其余段色[0] === techReason.令牌.ink3
    && techReason.库存.其余段色.length === 1 && techReason.库存.其余段色[0] === techReason.令牌.ink3
    && techReason.订单.其余段类.every((c) => c === '') && techReason.库存.其余段类.every((c) => c === ''),
    techReason)
  ok('㉔2b ⑤ 无原因的 info 事件（缓存命中）没有 `原因（reason）` 段、也没有 `.is-error/.is-warn` 子节点 '
    + '—— 染色只认 `fields.reason`，不会误伤别的段',
    !!techReason.缓存 && techReason.缓存.reason文本 === null
    && !techReason.缓存.整串.includes('原因（reason）')
    && techReason.缓存.其余段类.every((c) => c === ''),
    techReason.缓存)

  // ── ㉔4 A 块：**缺槽位跳过**（不写占位符）—— 这条要按「整页扫」，不能只看那十条
  const slots = await b.evalJS(`(() => {
    const joinSeg = (sel) => [...document.querySelectorAll(sel)].map(s => s.textContent).join('\\n')
    const segText = joinSeg('.log-line .ms .se-mid span') + '\\n' + joinSeg('.log-line .ms .se-ctx span')
    const emptyParen = []
    for (const e of document.querySelectorAll('.log-line .ms')) {
      const c = e.cloneNode(true)
      const d = c.querySelector('.dt'); if (d) d.remove()      // 技术层不算人话
      if (/[（(]\\s*[）)]/.test(c.textContent)) emptyParen.push(c.textContent.trim().slice(0, 40))
    }
    return {
      占位符: ['undefined', 'null', 'NaN'].filter(w => segText.includes(w)),
      空括号: emptyParen,
      从句段数: document.querySelectorAll('.log-line .ms .se-mid').length,
      括号段数: document.querySelectorAll('.log-line .ms .se-ctx').length,
      批量那条: (() => {
        const r = [...document.querySelectorAll('.log-line')].find(x => {
          const b = x.querySelector('.ms b'); return b && b.textContent === '批量事件 0'
        })
        if (!r) return null
        return { 有从句: !!r.querySelector('.se-mid'), 有括号: !!r.querySelector('.se-ctx'), 有技术层: !!r.querySelector('.dt') }
      })(),
    }
  })()`)
  ok('㉔4 全页扫一遍：从句/括号里**一个占位符都没有**（没有 `undefined` / `null` / `NaN`），也没有**空括号**',
    slots.占位符.length === 0 && slots.空括号.length === 0, slots)
  ok('㉔4 **缺槽位 = 整段不出现**（不是画一对空括号）：从句恰好 **1** 段、括号恰好 **10** 段'
    + '（收尾批 2 取消事件从句后，`.se-mid` 只剩组头那 1 段中文接口名；括号 = 9 条事件 + 组尾 1 段）',
    slots.从句段数 === 1 && slots.括号段数 === 10, { 从句: slots.从句段数, 括号: slots.括号段数 })
  ok('㉔4 没有 traceId 的批量行（150 条里的一条）：**从句与括号都不在**，但第一层技术字段照常出现'
    + '（`fields` 缺失时回落解析 detail —— 不是靠 traceId 才有的东西）',
    !!slots.批量那条 && slots.批量那条.有从句 === false && slots.批量那条.有括号 === false
    && slots.批量那条.有技术层 === true, slots.批量那条)

  // ── ㉔5 B 块：色系。⚠️ 对的是**计算色**，token 也从 CSS 里现取 —— 不手抄任何十六进制
  const colors = await b.evalJS(`(() => {
    const tok = (n) => {
      const d = document.createElement('span')
      d.style.color = 'var(' + n + ')'
      document.body.appendChild(d)
      const c = getComputedStyle(d).color
      d.remove()
      return c
    }
    const pick = (sel) => { const e = document.querySelector(sel); return e ? getComputedStyle(e).color : null }
    return {
      主题: document.documentElement.getAttribute('data-theme'),
      令牌: { ink: tok('--ink'), ink2: tok('--ink-2'), ink3: tok('--ink-3'), brandInk: tok('--brand-ink') },
      取到: {
        k: pick('.log-line .ms .se-k'), v: pick('.log-line .ms .se-v'),
        x: pick('.log-line .ms .se-x'), a: pick('.log-line .ms .se-a'),
        对象可点: pick('.log-line .ms .ob.p'),
      },
    }
  })()`)
  ok('㉔5 色板确实锁在 **admin 主题**上（`data-theme` 哨兵 —— 打到 `index.html` 上会量到别家的 token）',
    colors.主题 === 'admin', colors.主题)
  ok('㉔5 键 → `--ink`；值 → `--ink-2`；连接词 `·` → `--ink-3`（全部读的是计算色）',
    colors.取到.k === colors.令牌.ink && colors.取到.v === colors.令牌.ink2
    && colors.取到.x === colors.令牌.ink3,
    { 取到: colors.取到, 令牌: colors.令牌 })
  const deco = await b.evalJS(`(() => {
    const a = document.querySelector('.log-line .ms .se-a')
    const o = document.querySelector('.log-line .ms .ob.p')
    const g = (e) => (e ? { line: getComputedStyle(e).textDecorationLine, style: getComputedStyle(e).textDecorationStyle, cursor: getComputedStyle(e).cursor } : null)
    return { 短号: g(a), 对象: g(o) }
  })()`)
  ok('㉔5 可点那两处（trace 短号 `.se-a` / 对象 `.ob.p`）→ `--brand-ink` + **点状下划线**',
    colors.取到.a === colors.令牌.brandInk && colors.取到.对象可点 === colors.令牌.brandInk
    && !!deco.短号 && deco.短号.line === 'underline' && deco.短号.style === 'dotted'
    && !!deco.对象 && deco.对象.line === 'underline' && deco.对象.style === 'dotted',
    { 色: { 短号: colors.取到.a, 对象: colors.取到.对象可点, 品牌: colors.令牌.brandInk }, 线: deco })
  ok('㉔5 三档色**两两不同**（若全同，「分档」就是假的）',
    new Set([colors.取到.k, colors.取到.v, colors.取到.a]).size === 3,
    [colors.取到.k, colors.取到.v, colors.取到.a])
  // 第一层新结构（`.tf-k` 中文键 / `.tf-v` 值）自己也要分色 —— 这段是收尾批 2 新加的渲染层，
  // 不能只验旧的分段 token。读的仍是**计算色**，不是查 CSS 声明。
  const tfColors = await b.evalJS(`(() => {
    const k = document.querySelector('.log-line .ms .dt .tf-k')
    const v = document.querySelector('.log-line .ms .dt .tf-v')
    const a = document.querySelector('.log-line .ms .se-act .v')
    const s = document.querySelector('.log-line .ms .se-src .v')
    const c = (e) => (e ? getComputedStyle(e).color : null)
    return { 键: c(k), 值: c(v), 发起人值: c(a), 来源值: c(s),
      键文本: k ? k.textContent : null, 值文本: v ? v.textContent : null }
  })()`)
  ok('㉔5 第一层 `中文键` → `--ink-3`、`值` → `--ink-2`；发起人 / 来源的值也走 `--ink-2`',
    tfColors.键 === colors.令牌.ink3 && tfColors.值 === colors.令牌.ink2
    && tfColors.发起人值 === colors.令牌.ink2 && tfColors.来源值 === colors.令牌.ink2,
    { 取到: tfColors, 令牌: colors.令牌 })
  // ⚠️ 「可点不许骗人」的**负面对照**在这一条里：`notify` 那条没有 `objectId`，页面对它的正确
  //    行为是**根本不画对象列**（`objText()` 对空 id 返回空串、模板 `v-if="r.obj"` 挡掉）。
  //    ⇒「画了却不可点」的对象数应当为 **0**。
  //    ⚠️ 这一条是 ㉓7 那条「有则都有、无则都无」的**非空真前提**：没有它，㉓7 两侧都是 0、
  //       换成「永远挂 `.p`」的实现也照样绿（同「负向一半不算判据」）。
  const obMix = await b.evalJS(`(() => {
    const obs = [...document.querySelectorAll('.log-line .ms .ob')]
    const rows = [...document.querySelectorAll('.log-line')]
    const noOb = rows.filter(r => r.querySelector('.ms b') && !r.querySelector('.ms .ob'))
    return {
      画出的对象: obs.length,
      可点的: obs.filter(e => e.classList.contains('p')).length,
      画了但不可点: obs.filter(e => !e.classList.contains('p')).length,
      没有对象列的标题: noOb.map(r => r.querySelector('.ms b').textContent),
    }
  })()`)
  ok('㉔5 负面对照：`objectId` 为空的那条（`通知发送`）**根本不画对象列**、'
    + '组头/组尾也不画 ⇒ 「画了却不可点」的对象数 = **0**',
    obMix.画了但不可点 === 0 && obMix.画出的对象 === obMix.可点的
    && obMix.没有对象列的标题.includes('通知发送')
    && obMix.没有对象列的标题.includes('请求开始') && obMix.没有对象列的标题.includes('请求结束'),
    obMix)

  // ── ㉔6 B 块「可点不许骗人」：新加的那一格（trace 短号）**必须与真实可点同源**
  const ck = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.log-line')]
    const shortEls = [...document.querySelectorAll('.log-line .ms .se-ctx .se-a')]
    const obEls = [...document.querySelectorAll('.log-line .ms .ob')]
    return {
      行数: rows.length,
      可点行: rows.filter(r => r.getAttribute('role') === 'button').length,
      带短号行: rows.filter(r => r.querySelector('.se-ctx .se-a')).length,
      短号数: shortEls.length,
      短号文本: [...new Set(shortEls.map(e => e.textContent))],
      短号在括号里: shortEls.every(e => !!e.closest('.se-ctx')),
      短号在可点行里: shortEls.every(e => e.closest('.log-line').getAttribute('role') === 'button'),
      对象可点: obEls.filter(e => e.classList.contains('p')).length,
      对象总数: obEls.length,
      对象带title: obEls.filter(e => e.getAttribute('title')).length,
    }
  })()`)
  // ⚠️ 可点行是 **11** 行（9 条事件 + 组头/组尾那 2 行 `is-ends`），而短号只有 **9** 条 —— 组头
  //    组尾不是事件（它们来自 `traces` 记录、压根不走 `sentence()`）⇒ 判据不能写成两者相等。
  ok('㉔6 trace 短号只落在**事件行**上（9 条），且**每一条都落在真可点的行里**（`role="button"`）'
    + '—— 画了那根点状下划线就一定能点开',
    ck.带短号行 === 9 && ck.短号数 === 9 && ck.短号在可点行里 === true && ck.可点行 > ck.带短号行,
    { 可点行: ck.可点行, 带短号行: ck.带短号行, 短号数: ck.短号数, 行数: ck.行数 })
  ok('㉔6 短号文本 = `traceId` 的**前 8 位**，且只出现在括号段里（不散落到别处）',
    ck.短号文本.length === 1 && ck.短号文本[0] === TRACE.slice(0, 8) && ck.短号在括号里 === true, ck.短号文本)
  ok('㉔6 对象列那一条回归判据照旧：`.p` 与 `title` **与「有没有 objectId」一致**'
    + '（画出来的 158 个全可点、全带 `title`；没有 id 的那条**不画**、所以两边都不计）',
    ck.对象可点 === ck.对象带title && ck.对象可点 === ck.对象总数 && ck.对象可点 === 158,
    { 可点: ck.对象可点, 带title: ck.对象带title, 总数: ck.对象总数 })

  // ── ㉔7 A/B 块：原 `traceShort` 那一格（`.tr`）**已删**，短号改为默认可见
  const trGone = await b.evalJS(`(() => {
    const e = document.querySelector('.log-line .ms .se-ctx .se-a')
    const cs = e ? getComputedStyle(e) : null
    const r = e ? e.getBoundingClientRect() : null
    return {
      旧格子数: document.querySelectorAll('.log-line .tr').length,
      样例类名: e ? e.className : null,
      短号display: cs ? cs.display : null,
      短号宽: r ? Math.round(r.width * 10) / 10 : null,
      该行hover: e ? e.closest('.log-line').matches(':hover') : null,
      技术层display: (() => { const d = document.querySelector('.log-line .ms .dt'); return d ? getComputedStyle(d).display : null })(),
    }
  })()`)
  ok('㉔7 原 `traceShort` 那一格（`.tr`）**已删**（同一条事实不留两份：短号搬进了括号里）',
    trGone.旧格子数 === 0, trGone.旧格子数)
  ok('㉔7 短号**默认就可见**（不 hover 也是 `inline` 且有实测宽度）—— 搬进括号的意义就在这里',
    trGone.短号display !== 'none' && trGone.短号宽 > 8 && trGone.该行hover === false, trGone)
  ok('㉔7 对照：技术层 `.dt` 现在**默认就显示**（收尾批 2 把 fields 提到第一层；不再等 hover）',
    trGone.技术层display === 'inline', trGone.技术层display)

  // ── ㉔8 D 块：图标与文字之间 **6–8px**（用户给的线）。⚠️ 量**几何距离**，不是查 `gap` 声明
  //   ⚠️ 为什么要量几何：`gap` 声明在不在 ≠ 屏幕上真隔了那么远（弹性盒里插进匿名文本项、
  //      或者图标自带 `margin`，都会让声明的数字失真）。五处都是 `--s2` = 8px。
  const gapProbe = (iconSel, nextSel, 谁) => b.evalJS(`(() => {
    const r1 = (x) => Math.round(x * 10) / 10
    const ic = document.querySelector(${JSON.stringify(iconSel)})
    if (!ic) return { 谁: ${JSON.stringify(谁)}, 出错: '图标不在' }
    const ir = ic.getBoundingClientRect()
    const nx = ${nextSel === null ? 'null' : `document.querySelector(${JSON.stringify(nextSel)})`}
    if (nx) {
      const nr = nx.getBoundingClientRect()
      return { 谁: ${JSON.stringify(谁)}, 图标右缘: r1(ir.right), 文字左缘: r1(nr.left), 间距: r1(nr.left - ir.right) }
    }
    let n = ic.nextSibling
    let t = null
    while (n && !t) { if (n.nodeType === 3 && n.textContent.trim() !== '') t = n; else n = n.nextSibling }
    if (!t) return { 谁: ${JSON.stringify(谁)}, 出错: '图标后面没有文字' }
    const rg = document.createRange()
    rg.selectNodeContents(t)
    const rects = [...rg.getClientRects()].filter((x) => x.width > 1)
    if (!rects.length) return { 谁: ${JSON.stringify(谁)}, 出错: '量不到文字盒子' }
    const tl = Math.min(...rects.map((x) => x.left))
    return { 谁: ${JSON.stringify(谁)}, 图标右缘: r1(ir.right), 文字左缘: r1(tl), 间距: r1(tl - ir.right) }
  })()`)
  const gaps = []
  gaps.push(await gapProbe('.ev-rail .rows .row:not(.all):not(.is-fac) .ic svg', '.ev-rail .rows .row:not(.all):not(.is-fac) .k', '② 类清单（图标 → 类名）'))
  gaps.push(await gapProbe('.ev-rail .sevchip .dot', null, '② 栏底严重度开关（圆点 → 等级文字）'))
  gaps.push(await gapProbe('.ev-bars:not(.sev) .ev-bar:not(.is-fac) .nm svg', null, '④ 类计数九条（图标 → 类名）'))
  gaps.push(await gapProbe('.ev-bars.sev .ev-bar .nm svg', null, '④ 严重度三条（图标 → 等级文字）'))
  gaps.push(await gapProbe('.chain-i:not(.is-ends):not(.is-miss) .d .d-hd svg', '.chain-i:not(.is-ends):not(.is-miss) .d .d-hd b', '⑤ 链里（图标 → 类名）'))
  // 用户 2026-10-17 微调：③ 行内也有「图标 → 等级 INFO」，上一轮漏量了这一处。
  gaps.push(await gapProbe('.log-line:not(.req) .ic', '.log-line:not(.req) .lv', '③ 事件流（图标 → 等级 INFO）'))
  const gapOK = (g) => !!g && typeof g.间距 === 'number' && g.间距 >= 6 && g.间距 <= 8
  ok('㉔8 六处「图标 + 文字」的**实测间距都在 6–8px**（② 两处 / ③ 一处 / ④ 两处 / ⑤ 一处）',
    gaps.every(gapOK), gaps)
  ok('㉔8 那六处**不是同一个元素量了六遍**（六条都拿到了数、且都有各自的「谁」标签）',
    gaps.length === 6 && gaps.every((g) => typeof g.谁 === 'string'), gaps.map((g) => g.谁))
  // ⚠️ 关键回归：盒子上有 8px 间距 ≠ INFO 没压到图标上。`.ln` 的 `text-indent: -2ch` 会被
  //    inline-block 继承 ⇒ `.lv` 里的文字整体左移（实测 -14.4px），盒子仍停在原地。
  //    所以这里量的是**文字墨迹**的起点，不是 `.lv` 的盒子左缘。
  const lvInk = await b.evalJS(`(() => {
    const lv = document.querySelector('.log-line:not(.req) .lv')
    if (!lv) return null
    const box = lv.getBoundingClientRect()
    const rg = document.createRange()
    rg.selectNodeContents(lv)
    const ink = rg.getBoundingClientRect()
    return { 文本左: +ink.left.toFixed(1), 格左: +box.left.toFixed(1), 差: +(ink.left - box.left).toFixed(1), textIndent: getComputedStyle(lv).textIndent }
  })()`)
  ok('㉔8 ③ 的「INFO」从格子左缘起排（text-indent 归零）—— 否则会被悬挂缩进拉到图标上',
    !!lvInk && Math.abs(lvInk.差) <= 1 && lvInk.textIndent === '0px', lvInk)
  // 用户 2026-10-17 微调：③ 的类名文字与图标同色。
  const mdColor = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.log-line:not(.req)')].filter((r) => r.querySelector('.ic svg') && r.querySelector('.md'))
    return rows.slice(0, 9).map((r) => ({
      类: r.querySelector('.md').textContent,
      图标色: getComputedStyle(r.querySelector('.ic')).color,
      类名色: getComputedStyle(r.querySelector('.md')).color,
      c: r.style.getPropertyValue('--c'),
    }))
  })()`)
  ok('㉔8 ③ 类名文字与图标**同色**（缓存蓝 / 订单绿 / 锁黄…都取自行上的 `--c`，不再一律 `--ink-2`）',
    mdColor.length > 0 && mdColor.every((x) => x.图标色 === x.类名色), mdColor)
  // 反向半边：类色必须只由「类」决定，**不能随严重度变**。
  //   ⚠️ 这一条是 2026-10-17 量出来的真缺陷：行内严重度变量原来叫 `--c2`，与色板的「锁」
  //      同名 ⇒ 锁行的 `--c: var(--c2)` 自己吃自己，INFO 青 / WARN 橙 / ERROR 红。
  //      「图标色 === 类名色」在两边一起错时是全绿的，所以要单独钉「同类同色」。
  const kindStable = await b.evalJS(`(() => {
    const out = {}
    for (const r of document.querySelectorAll('.log-line:not(.req)')) {
      const md = r.querySelector('.md')
      const lv = r.querySelector('.lv')
      const ic = r.querySelector('.ic')
      if (!md || !lv || !ic || !ic.querySelector('svg')) continue
      const k = (md.textContent || '').trim()
      if (!out[k]) out[k] = []
      out[k].push({ 等级: (lv.textContent || '').trim(), 色: getComputedStyle(ic).color })
    }
    return Object.keys(out).map((k) => ({
      类: k,
      等级数: new Set(out[k].map((x) => x.等级)).size,
      色数: new Set(out[k].map((x) => x.色)).size,
      色: out[k][0].色,
    }))
  })()`)
  ok('㉔8 同一类名不随等级变色（至少一个类覆盖两种等级，且每类只有一种色）',
    kindStable.some((x) => x.等级数 >= 2) && kindStable.every((x) => x.色数 === 1), kindStable)

  // ── ㉔11 性能微调（用户 2026-10-17）：渲染隔离 + 小数据量不走窗口化 ───────────────
  //   ⚠️ 窗口化本身在 `probe-b7a3-eventlog-virtual.mjs` 里用 1000 条专测；
  //      这里只钉「161 行 ≤ 200 阈值 ⇒ 仍全量渲染」与几条 CSS 默认值，防止把探针的判据基础改掉。
  const perfCss = await b.evalJS(`(() => {
    const ln = document.querySelector('.log-line .ln')
    const list = document.getElementById('ev-list')
    return {
      contain: ln ? getComputedStyle(ln).contain : null,
      vpad: document.querySelectorAll('.ev-list .vpad').length,
      vidx: document.querySelectorAll('.log-line[data-vidx]').length,
      rows: document.querySelectorAll('.log-line').length,
      scrollBehavior: getComputedStyle(list).scrollBehavior,
      willChange: getComputedStyle(list).willChange,
    }
  })()`)
  ok('㉔11 每行 `.ln` 开了渲染隔离（computed 归一化为 `content` = layout + paint + style）',
    perfCss.contain === 'content', perfCss.contain)
  ok('㉔11 161 行 ≤ 阈值 200 ⇒ **不启用**窗口化（无 vpad、无 data-vidx；整表仍在 DOM 里）',
    perfCss.vpad === 0 && perfCss.vidx === 0 && perfCss.rows === 161, perfCss)
  ok('㉔11 滚动容器：没有原生平滑滚动 + 有 `will-change: scroll-position`',
    (perfCss.scrollBehavior || 'auto') === 'auto' && /scroll-position/.test(perfCss.willChange || ''), perfCss)
  await zoomShot('.ev-rail .rows', `${WORK}zoom-3a3-d-rail.png`, { 宽: 210, 高: 120, scale: 5 })
  await zoomShot('.ev-bars', `${WORK}zoom-3a3-d-bars.png`, { 宽: 300, 高: 150, scale: 5 })

  // ── ㉔9 C 块：`apiLabel()` 在页面上的消费点 —— ⑤ 链首那行（另一处是 ③ 组头行）
  const headLine = await b.evalJS(`(() => {
    const e = document.querySelector('.chain-i.is-ends .d')
    return e ? e.textContent.replace(/\\s+/g, ' ').trim() : null
  })()`)
  ok('㉔9 ⑤ 链首 = 「请求开始（修改商品 · POST /goods/goodsUp）」—— 认得出就给「中文名 + 原标题」两段',
    typeof headLine === 'string' && headLine.includes('请求开始')
    && headLine.includes('修改商品') && headLine.includes('POST /goods/goodsUp'), headLine)
  // ③ 的组头行：`POST /goods/goodsUp` 那一组，标题该是中文名（认不出才会回落原路径）
  const groupHead = await b.evalJS(`(() => {
    const r = [...document.querySelectorAll('.log-line')].find(x => (x.querySelector('.ms') || {}).textContent
      && x.querySelector('.ms').textContent.includes('请求开始'))
    return r ? r.querySelector('.ms').textContent.replace(/\\s+/g, ' ').trim().slice(0, 60) : null
  })()`)
  ok('㉔9 ③ 的组头行也带中文名（`startLabel()` 的第二个消费点；认不出时会回落成裸路径）',
    typeof groupHead === 'string' && groupHead.includes('修改商品'), groupHead)

  // ── ㉔10 图标色：② / ④ 必须与 ③ 同类**同色**（用户 2026-10-17 截图实测：这两处原本灰白）
  //   ⚠️ 量的是**计算色**，令牌从 CSS 现取（不手抄十六进制）。三种元素分别读：
  //      · ② / ③ / ④ 的 lucide 图标 → `color`（吃 `currentColor`）
  //      · ② 栏底严重度那枚 8px 圆点 → `background-color`（它是色块，不是描边图标）
  //   ⚠️ 负向一半：同时断言它们都**不再是 `--ink-2`** —— 否则「CSS 没接上」也能因为
  //      `--c1` 恰好等于灰而假绿（虽然当前值不相等，但判据要写死这一条）。
  const iconColor = await b.evalJS(`(() => {
    const tok = (n) => {
      const d = document.createElement('span')
      d.style.color = 'var(' + n + ')'
      document.body.appendChild(d)
      const c = getComputedStyle(d).color
      d.remove()
      return c
    }
    const colorOf = (root, sel) => {
      const e = root && root.querySelector(sel)
      return e ? getComputedStyle(e).color : null
    }
    const railRow = (label) => [...document.querySelectorAll('.ev-rail .rows .row:not(.all):not(.is-fac)')]
      .find((r) => { const k = r.querySelector('.k'); return k && k.textContent.trim() === label })
    const barRow = (sel, label) => [...document.querySelectorAll(sel)]
      .find((r) => { const n = r.querySelector('.nm'); return n && n.textContent.trim().startsWith(label) })
    // ⚠️ ③ 的行没有「类名」列：.md 是折叠摘要（主行恒空），要认类只能看标题
    //    （.ms b = title）。探针假帧里「缓存命中（L1）」/「库存不足（未扣，防超卖命中）」是**不同标题**，
    //    所以这能唯一定位到两类各一行。
    //    ⚠️ 本段是模板字符串：注释里写反引号会把模板提前闭合（本轮就是这样炸的）。
    const lineByTitle = (title) => [...document.querySelectorAll('.log-line')]
      .find((r) => { const m = r.querySelector('.ms b'); return m && m.textContent.trim() === title })
    const sevChip = (label) => [...document.querySelectorAll('.ev-rail .sevchip')]
      .find((r) => r.textContent.trim().startsWith(label))
    return {
      令牌: {
        c1: tok('--c1'), c4: tok('--c4'),
        warning: tok('--warning'), ink2: tok('--ink-2'),
      },
      '②类': { 缓存: colorOf(railRow('缓存'), '.ic svg'), 库存: colorOf(railRow('库存'), '.ic svg') },
      '②严重度圆点': (() => {
        const d = sevChip('警告') && sevChip('警告').querySelector('.dot')
        return d ? getComputedStyle(d).backgroundColor : null
      })(),
      '③类': {
        缓存: colorOf(lineByTitle('缓存命中（L1）'), '.ic svg'),
        库存: colorOf(lineByTitle('库存不足（未扣，防超卖命中）'), '.ic svg'),
      },
      '④类': { 缓存: colorOf(barRow('.ev-bars:not(.sev) .ev-bar', '缓存'), '.nm svg') },
      '④严重度': { 警告: colorOf(barRow('.ev-bars.sev .ev-bar', '警告'), '.nm svg') },
    }
  })()`)
  ok('㉔10 ② 过滤栏：缓存图标 = `--c1`（蓝）、库存图标 = `--c4`，且都**不再是 `--ink-2` 灰白**',
    iconColor['②类'].缓存 === iconColor.令牌.c1 && iconColor['②类'].库存 === iconColor.令牌.c4
    && iconColor['②类'].缓存 !== iconColor.令牌.ink2 && iconColor['②类'].库存 !== iconColor.令牌.ink2,
    iconColor)
  ok('㉔10 ④ 类计数：缓存图标 = `--c1`；④ 严重度：警告图标 = `--warning`（黄），不是灰白',
    iconColor['④类'].缓存 === iconColor.令牌.c1
    && iconColor['④严重度'].警告 === iconColor.令牌.warning
    && iconColor['④类'].缓存 !== iconColor.令牌.ink2 && iconColor['④严重度'].警告 !== iconColor.令牌.ink2,
    iconColor)
  ok('㉔10 **同类同色**：③ 的缓存/库存图标 = ② 的缓存/库存图标 = ④ 的缓存图标；'
    + '④ 警告图标 = ② 栏底那枚警告圆点（同一档严重度色）',
    iconColor['③类'].缓存 === iconColor['②类'].缓存
    && iconColor['③类'].库存 === iconColor['②类'].库存
    && iconColor['④类'].缓存 === iconColor['②类'].缓存
    && iconColor['④严重度'].警告 === iconColor['②严重度圆点'],
    iconColor)
  await zoomShot('.ev-rail .rows', `${WORK}zoom-3a3-icons-color-rail.png`, { 宽: 210, 高: 120, scale: 5 })
  await zoomShot('.ev-live', `${WORK}zoom-3a3-icons-color-live.png`, { 宽: 340, 高: 330, scale: 4 })

  // ══ 裁定 B · 切源 = 隔离，不是清空 + 易漏项① 演示下 ⑤ 起止恒空 ══════════════════
  // `api/demo-stream.js:364` 逐字：「演示不造请求链路（日志页那张抽屉留空，不去编一条假的调用链）」
  // ⇒ 演示帧的 `traces` **恒为 `[]`**；而事件行 75% 带 `traceId`（`:298`）
  // ⇒ 演示下：**行可点、链可开、但「请求开始/结束」两行必然缺席**。
  //
  // ⚠️ 上一版这一段必须「离页再回」才能量：那时切源**不清窗口**，列表里仍留着切换前真流的
  //    159 条 ⇒ 直接断言量到的是混在一起的两套数据（本次实测踩过）。**裁定 B 之后不用了** ——
  //    两套数据按 `source` 分桶，切源当场就隔离干净。这一段同时就是 B 的验收。
  const nBefore = await b.evalJS(`document.querySelectorAll('.log-line').length`)
  ok('⑯ 切换前：真流源有 161 行、其中一组包起来（2 条 .req）',
    nBefore === 161 && (await b.evalJS(`document.querySelectorAll('.log-line.req').length`)) === 2, nBefore)
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === '模拟日常')
    if (btn) btn.click()
    return !!btn
  })()`)
  await sleep(4500)      // 演示流是定时器驱动的，等它吐几拍
  const demoOn = await b.evalJS(`(() => {
    const x = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === '模拟日常')
    return x ? /on|active|is-on/.test(x.className) : null
  })()`)
  ok('⑯ 切到「模拟日常」（数据源分段控件）', demoOn === true, demoOn)
  const demoCnt = await b.evalJS(`(document.body.innerText.match(/已放[\\s\\S]{0,24}/) || [''])[0]`)
  ok('⑯ ① 的「已放 N / 500 条」在演示模式下继续常驻（且 N > 0）',
    /已放/.test(demoCnt), demoCnt)
  const demoRows = await b.evalJS(`document.querySelectorAll('.log-line').length`)
  const demoReq = await b.evalJS(`document.querySelectorAll('.log-line.req').length`)
  ok('⑯ 演示流确实在推（③ 有行）', demoRows > 0, demoRows)
  // ⚠️ **裁定 B 的主判据**：演示下**不许**再出现真流那批事件（它们只存在于另一个桶里）。
  //    直接按内容找，比数行数准 —— 「批量事件 N」是 mock 里真流独有的标题。
  const demoHasNormal = await b.evalJS(`document.querySelector('.ev-list').textContent.includes('批量事件')`)
  ok('⑯ **切源 = 隔离**：演示模式下一条真流事件都不显示（列表里找不到「批量事件」）',
    demoHasNormal === false && demoReq === 0, { 有真流残留: demoHasNormal, req: demoReq })
  const demoOpenable = await b.evalJS(`document.querySelectorAll('.log-line[role="button"]').length`)
  ok('⑯ 演示下的行**仍然可点**（事件带 traceId）', demoOpenable > 0, demoOpenable)
  // ── ⑯+ 3a.3：演示假帧补的三样（fields / durationUs / remaining / actor）**在页面上真的看得见**
  //   ⚠️ 只验「假帧里有这几个键」是离线探针（`demo-stream-check.mjs`）的事；这里验的是
  //      **前端有没有把它们念出来** —— 两件事缺一不可（假数据对了但页面不显示，等于没做）。
  //   ⚠️ 演示场次是随机的（种子固定但已推了几秒），所以按「八大类里找得到一条」来断言，
  //      不钉死某一行；`库存扣减` / 审计四档标题在演示里是高频，4.5 秒足够出现。
  const demoShape = await b.evalJS(`(() => {
    const rows = [...document.querySelectorAll('.log-line')]
    const ctxOf = (r) => { const m = r.querySelector('.se-ctx'); return m ? m.textContent.trim() : '' }
    const titleOf = (r) => { const b = r.querySelector('.ms b'); return b ? b.textContent.trim() : '' }
    const pairOf = (r, k) => {
      const f = [...r.querySelectorAll('.ms .dt .tf')].find((x) => {
        const n = x.querySelector('.tf-k'); return n && n.textContent === k
      })
      const v = f && f.querySelector('.tf-v')
      return v ? v.textContent.trim() : ''
    }
    const CACHE_ZH = ['商品类型', '首页商品', '店铺商品', '商品价格', '商品编码', '好友列表', '好友申请', '用户公开资料']
    const cache = rows.find((r) => CACHE_ZH.includes(pairOf(r, '缓存区')))
    const stock = rows.find((r) => titleOf(r) === '库存扣减' && /^\\d+ 件$/.test(pairOf(r, '剩余')))
    const withAct = rows.find((r) => r.querySelector('.se-act'))
    return {
      缓存区值: cache ? pairOf(cache, '缓存区') : null,
      有发起人行数: rows.filter((r) => r.querySelector('.se-act')).length,
      发起人样例: withAct ? withAct.querySelector('.se-act').textContent.trim() : null,
      剩余样例: stock ? pairOf(stock, '剩余') : null,
      带耗时行数: rows.filter((r) => ctxOf(r).includes('耗时')).length,
      总行数: rows.length,
    }
  })()`)
  ok('⑯+ 演示假帧的 `fields` 真的落到了第一层：某条缓存的 `缓存区` = 中文区名（不是英文 tag）',
    demoShape.缓存区值 !== null, demoShape)
  ok('⑯+ `actor` 真的落到了第一层：演示行里出现「发起人 <id>（客户|商家|管理员）」（不止一行）',
    demoShape.有发起人行数 > 1 && /发起人\s+\S+（(客户|商家|管理员)）/.test(demoShape.发起人样例 || ''), demoShape)
  ok('⑯+ 顶层 `remaining` 真的落到了第一层：库存扣减行的 `剩余` = `N 件`',
    !!demoShape.剩余样例 && /^\d+ 件$/.test(demoShape.剩余样例), demoShape)
  ok('⑯+ `durationUs` 真的落到了页面上：演示行里有「耗时」这一格（且有不止一行）',
    demoShape.带耗时行数 > 1, demoShape)
  if (demoOpenable > 0) {
    await b.evalJS(`document.querySelector('.log-line[role="button"]').click()`)
    await sleep(400)
    const dchain = await b.evalJS(TEXT('.ev-drawer .chain .chain-i .d'))
    ok('⑯ ⑤ 抽屉**打得开**（链里是这一次请求的事件）', dchain.length > 0, dchain.slice(0, 2))
    ok('⑯ **易漏项①实测成立**：⑤ 里出现「起止还没到」的空态，且**没有**「请求开始 / 请求结束」两行',
      dchain.some((t) => t.includes('起止') && t.includes('还没到'))
      && !dchain.some((t) => t.includes('请求开始') || t.includes('总耗时')),
      dchain.slice(-2))
  }
  // ══ ⑯+ WARN / ERROR 演示帧必须带原因，且 ⑤ 原因段按严重度着色 ══════════════════
  //   ⚠️ 用户实测反馈：演示里的「敏感词命中，已拦截」点开只有 kind / severity / src，
  //      没有「原因」这一段，整段 .tech 全灰。根因在 api/demo-stream.js：假帧只写了标题，
  //      没写 fields.reason（后端真流一直有）。本段钉住两件事：
  //      ① ③ 第一层：每条 WARN / ERROR 演示行都有「原因」键值对；
  //      ② ⑤ 详情：点开其中可点的一条，出现「原因（reason）= …」段，且计算色按级别
  //         落到 --warning（warn）/ --danger（error），其余段仍是中性 --ink-3。
  //   ⚠️ 演示流是定期间隔吐帧，4.5 秒不一定已经吐到 WARN；这里最多再等 20 秒。
  const demoWarn = await (async () => {
    const t0 = Date.now()
    while (Date.now() - t0 < 20000) {
      const r = await b.evalJS(`(() => {
        const titleOf = (r) => { const b = r.querySelector('.ms b'); return b ? b.textContent.trim() : '' }
        const levelOf = (r) => { const x = r.querySelector('.lv'); return x ? x.textContent.trim() : '' }
        const pair = (r, k) => {
          const tf = [...r.querySelectorAll('.ms .dt .tf')].find((f) => {
            const n = f.querySelector('.tf-k'); return n && n.textContent.trim() === k
          })
          return tf ? tf.querySelector('.tf-v').textContent.trim() : null
        }
        const rows = [...document.querySelectorAll('.log-line:not(.req)')]
        const warnErr = rows.filter((r) => /^(WARN|ERROR)$/.test(levelOf(r)))
        const clickable = warnErr.find((r) => r.getAttribute('role') === 'button')
        return {
          总行数: rows.length,
          警告错误行数: warnErr.length,
          缺原因: warnErr.filter((r) => !pair(r, '原因')).map(titleOf),
          样例: warnErr.slice(0, 3).map((r) => ({ 标题: titleOf(r), 级别: levelOf(r), 原因: pair(r, '原因') })),
          可点警告: clickable ? { 标题: titleOf(clickable), 级别: levelOf(clickable) } : null,
        }
      })()`)
      if (r.警告错误行数 > 0 && r.可点警告) return r
      await sleep(500)
    }
    return null
  })()
  ok('⑯+ **所有 WARN / ERROR 演示帧第一层都带「原因」**（缺原因行数为 0，且至少出现一条）',
    !!demoWarn && demoWarn.警告错误行数 > 0 && demoWarn.缺原因.length === 0, demoWarn)
  if (demoWarn && demoWarn.可点警告) {
    await b.evalJS(`(() => {
      const titleOf = (r) => { const b = r.querySelector('.ms b'); return b ? b.textContent.trim() : '' }
      const r = [...document.querySelectorAll('.log-line')].find((x) =>
        titleOf(x) === ${JSON.stringify(demoWarn.可点警告.标题)} && x.getAttribute('role') === 'button')
      if (r) r.click()
      return !!r
    })()`)
    await sleep(500)
    const dReason = await b.evalJS(`(() => {
      const tok = (n) => {
        const d = document.createElement('span'); d.style.color = 'var(' + n + ')'
        document.body.appendChild(d); const c = getComputedStyle(d).color; d.remove(); return c
      }
      const item = [...document.querySelectorAll('.chain .chain-i')].find((x) => {
        const c = x.querySelector('.tech')
        return c && c.textContent.includes('原因（reason）=')
      })
      if (!item) return null
      const code = item.querySelector('.tech')
      const segs = [...code.children].filter((s, i) => i % 2 === 0)
      const reason = segs.find((s) => s.textContent.startsWith('原因（reason）='))
      const others = segs.filter((s) => s !== reason)
      return {
        reason文本: reason ? reason.textContent : null,
        reason类: reason ? reason.className : null,
        reason色: reason ? getComputedStyle(reason).color : null,
        其余段色: [...new Set(others.map((s) => getComputedStyle(s).color))],
        dReason: document.querySelectorAll('.d-reason').length,
        令牌: { warning: tok('--warning'), danger: tok('--danger'), ink3: tok('--ink-3') },
      }
    })()`)
    const lv = demoWarn.可点警告.级别
    const wantCls = lv === 'ERROR' ? 'is-error' : 'is-warn'
    const wantColor = lv === 'ERROR' ? (dReason && dReason.令牌.danger) : (dReason && dReason.令牌.warning)
    ok('⑯+ **点开 WARN / ERROR 演示行：⑤ 出现「原因（reason）= …」，段色按级别 = --warning / --danger，'
      + '其余段仍 = --ink-3，且 .d-reason = 0**（用户实报缺原因的那条路径）',
      !!dReason && !!dReason.reason文本 && dReason.reason类 === wantCls
      && dReason.reason色 === wantColor
      && dReason.其余段色.length > 0 && dReason.其余段色.every((c) => c === dReason.令牌.ink3)
      && dReason.dReason === 0,
      { 级别: lv, 期望类: wantCls, 期望色: wantColor, ...dReason })
  }

  // 实拍仍在**演示模式**下拍（与 `-stream.png` 那张真流对照）—— 顺序与上一版一致。
  await b.shot(`${WORK}out-b7a3-eventlog.png`)
  await b.shot(`${WORK}out-b7a3-eventlog-full.png`)

  // ⚠️ **裁定 B 的反向判据**：切回「正常」⇒ 切换前那个窗口**原样还在**。
  //    「不丢数据」是 B 的三个好处之一，不验这一条就等于只做了「隔离」没做「不丢」。
  await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === '正常')
    if (btn) btn.click()
    return !!btn
  })()`)
  await sleep(2000)
  const nBack = await b.evalJS(`document.querySelectorAll('.log-line').length`)
  const backHasBulk = await b.evalJS(`document.querySelector('.ev-list').textContent.includes('批量事件')`)
  ok('⑯ **切源不丢数据**：切回「正常」⇒ 之前的 161 行原样回来（切来切去不丢窗口）',
    nBack === nBefore && backHasBulk === true, { 切前: nBefore, 切回后: nBack, 有真流: backHasBulk })
} catch (e) {
  TOTAL++
  // ⚠️ 原始堆栈**直印 stdout**，不走 `R.ok`：`R.ok` 的 got 会被截到 500 字，
  //    而「模板字符串被反引号提前闭合」那种报错，正文 = 整段源码 ⇒ 截断后只剩源码前几行、
  //    真正的 `is not a function` 落在第 500 字之后，看报告只会以为「探针炸了」而找不到原因。
  console.log('\n=== RAW STACK ===')
  console.log(String((e && e.stack) || e))
  console.log('=== /RAW ===')
  R.ok('探针自身没抛异常', false, String(e && e.stack ? e.stack.split('\n').slice(0, 4).join(' | ') : e))
} finally {
  const errs2 = await b.evalJS('(window.__errs || [])').catch(() => [])
  ok('全流程收尾：仍无 console.error / unhandledrejection', (errs2 || []).length === 0, (errs2 || []).slice(0, 5))
  const bad = R.dump()
  console.log(`\n截图：${WORK}out-b7a3-eventlog.png`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
