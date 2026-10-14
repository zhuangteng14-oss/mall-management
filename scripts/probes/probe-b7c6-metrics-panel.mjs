// probe-b7c6-metrics-panel.mjs —— 块 7 · commit 6〈指标面板〉的**渲染实测**。
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7c6-metrics-panel.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ⚠️ **mock 的帧形状是照真后端抄的，不是编的**：跑本笔前先对 dev 后端（8088）拉了 4 秒真流
//    （`curl -N --max-time 4 -X POST .../admin/observe/metrics/stream` → `_work/metrics-stream-real.txt`，
//    3 帧）。下面每一帧的键名/键序/嵌套都与它逐字一致，只把数值换成能验出区别的。
//    ⚠️ 真帧给的三个「反直觉但真实」的事实，本探针专门覆盖：
//      · `health.reasons` 在**健康且无流量**时是 **`[]`**（那条正面理由只在 `cache.hitRate != null`
//        时才补，见 `MetricsSnapshotService.health()` :383）⇒ 不是「绿的必有话」；
//      · `requests.errorRate` / `clientErrorRate` / `cache.hitRate` 在**本拍没请求**时是 **`null`**
//        （不是 0）⇒ 页面必须显示 `—` 而不是 `0`；
//      · `stock.rows[].baseline` / `ratio` 现网全是 **`null`**（没打演示基线）⇒ 现网看到的就是灰斜纹条。
//
// ⚠️ **SSE 的 mock 手法**：`POST .../metrics/stream` 的响应是**流**，而 CDP
//    `Fetch.fulfillRequest` 只能**一次性**给完 body。好在 `api/observe.js:openStream` 是
//    「缓冲 + 按 `\n\n` 切帧」（`:99-105`）⇒ 把 N 帧拼成一个 body 一次投喂，`while` 循环会
//    把 N 帧**全部**解析出来，等价于收到了 N 拍。代价：body 投完流就结束 ⇒ 页面进
//    「已断开」态（⑩ 正是断言这个：**正常收尾不该被画成错误**）。
//
// 查十三件事：
//   ① 挂载 / 无 console.error / 无 unhandledrejection
//   ② 健康度大字 = 后端给的 level（本页**不重算**）+ 判据条渲染后端原话
//   ③ 状态条：5xx / 4xx 占比读的是 `errorRate` / `clientErrorRate`
//   ④ 四张 KPI 卡的**标签**逐字 = QPS / P99 延迟 / 缓存命中率 / 锁等待次数
//   ⑤ 四张卡的**值**：QPS 取最后一拍 / P99 四舍五入 / 锁等待 = 各锁 timeouts 之和
//   ⑥ 环比口径写在卡上（`较 60 秒均值`），不是「较上一拍」
//   ⑦ 四个图都画出来了（`.bc` × 4，且无空态/加载态浮层）
//   ⑧ 热力图**真有数据**（`HeatmapChart.vue` 的全仓首次调用点）
//   ⑨ 库存水位三条：有基线=绿/红、无基线=灰斜纹、且无基线那行数字是 `—` 不是 0
//   ⑩ 流正常收尾 ⇒ 「已断开」而**不是**红条报错
//   ⑪ 缺值不留 0：第二遍（命中率 null）⇒ 命中率卡显示 `—`
//   ⑫ 第二遍的健康度走 warn 分支（`.a-reasons--warn`）
//   ⑬ 页面卸载**真调了** `close()`（包 `ReadableStreamDefaultReader.prototype.cancel` 计数）
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/metrics'
const STUB = '#/admin/observe/timeline' // 切走用：真页面（需要卸载）→ 占位页（不发请求，不引入噪声）
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
const SHOT = `${EV}out-b7c6-metrics-panel.png`

const R = report('块 7 · commit 6 指标面板（mock 后端 · 帧形状照真流抄）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

// ── 8 类防护（**逐字抄真帧的 `protections[].label`**）──────────────────────────
const PROT = [
  ['cacheHit', '缓存命中'], ['lockTimeout', '锁等待超时'], ['orderRejected', '订单流转被拒'],
  ['stockInsufficient', '库存不足（防超卖）'], ['auditFailed', '审计写入失败'],
  ['notifyFailed', '通知发送失败'], ['sensitiveHit', '敏感词拦下'], ['identityRejected', '身份拒绝'],
]

/** 一条库存行（真帧的 4 行同款 SKU，只改数值）。 */
const sku = (s, good, axis, stock, baseline, ratio) => ({ sku: s, goodName: good, axisName: axis, stock, baseline, ratio })

/** 造一帧。**键序与真帧逐字一致**（`frame()` 用的是 LinkedHashMap）。 */
function frame(n, o) {
  return {
    seq: n,
    ts: 1791967503401 + n * 1000,
    intervalMs: n === 0 ? 0 : 1000,
    health: o.health,
    requests: {
      count: o.count, qps: o.qps, errors5xx: o.e5 ?? 0, errors4xx: o.e4 ?? 0,
      errorRate: o.errorRate ?? null, clientErrorRate: o.clientErrorRate ?? null,
      meanMs: o.meanMs ?? null, p95Ms: o.p95 ?? 0.0, p99Ms: o.p99 ?? 0.0, maxMs: o.max ?? 0.0,
    },
    cache: { hit: o.hit ?? 0, miss: o.miss ?? 0, hitRate: o.hitRate ?? null, l2Degraded: 0 },
    gauges: { l2Up: 1.0, lockUp: o.lockUp ?? 1.0 },
    locks: (o.locks || []).map(([lock, timeouts]) => ({
      lock, acquires: 40, acquired: 38, timeouts, degraded: 0, errors: 0, waitMeanMs: 1.5,
    })),
    protections: PROT.map(([tag, label], i) => ({ tag, label, value: (o.prot || [])[i] || 0 })),
    stock: {
      available: true,
      rows: o.stock || [
        sku('asdsad', 'oppo12', '珍珠白 / S', 100, null, null),
        sku('sadasd', 'oppo12', '珍珠白 / M', 100, null, null),
        sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null),
        sku('aaaaaa', 'oppo12', '珍珠白 / XL', 100, null, null),
      ],
    },
  }
}

const OK_HEALTH = { level: 'ok', label: '健康', reasons: ['L1 命中率 60.0%'] }

/**
 * 第一遍的 5 拍。⚠️ 最后一拍刻意**同时**满足两件事：命中率**非 null**（好让 `health` 那条
 * 「L1 命中率」正面理由**自洽**）+ 各指标都取一个能被四舍五入验出的值。
 */
const F_PASS1 = [
  frame(0, { health: { level: 'ok', label: '健康', reasons: [] }, count: 0, qps: 0.0 }),   // 真帧第 0 拍原样
  frame(1, { health: OK_HEALTH, count: 40, qps: 20.0, p99: 15.0, meanMs: 2.1, hit: 24, miss: 16, hitRate: 0.6,
    locks: [['goods_sku', 0], ['order_no', 0]], prot: [5, 0, 1, 0, 0, 0, 2, 0],
    stock: [sku('asdsad', 'oppo12', '珍珠白 / S', 90, 100, 0.9), sku('sadasd', 'oppo12', '珍珠白 / M', 10, 100, 0.1), sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null)] }),
  frame(2, { health: OK_HEALTH, count: 30, qps: 15.0, p99: 18.0, hit: 15, miss: 15, hitRate: 0.5,
    locks: [['goods_sku', 2], ['order_no', 0]], prot: [2, 2, 0, 1, 0, 0, 0, 0],
    stock: [sku('asdsad', 'oppo12', '珍珠白 / S', 90, 100, 0.9), sku('sadasd', 'oppo12', '珍珠白 / M', 10, 100, 0.1), sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null)] }),
  frame(3, { health: OK_HEALTH, count: 50, qps: 25.0, p99: 40.0, hit: 20, miss: 30, hitRate: 0.4,
    locks: [['goods_sku', 0], ['order_no', 3]], prot: [9, 0, 0, 0, 1, 0, 0, 1],
    stock: [sku('asdsad', 'oppo12', '珍珠白 / S', 90, 100, 0.9), sku('sadasd', 'oppo12', '珍珠白 / M', 10, 100, 0.1), sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null)] }),
  // 最后一拍 = 页面最终显示的那一拍。锁等待 = 2 + 0 = 2；P99 21.5 → 四舍五入 22。
  frame(4, { health: OK_HEALTH, count: 25, qps: 12.5, e5: 3, e4: 1, errorRate: 0.012, clientErrorRate: 0.004,
    meanMs: 3.4, p95: 12.0, p99: 21.5, max: 60.0, hit: 10, miss: 10, hitRate: 0.6,
    locks: [['goods_sku', 2], ['order_no', 0]], prot: [1, 2, 3, 0, 0, 0, 0, 0],
    stock: [sku('asdsad', 'oppo12', '珍珠白 / S', 90, 100, 0.9), sku('sadasd', 'oppo12', '珍珠白 / M', 10, 100, 0.1), sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null)] }),
]

/** 第二遍：最后一拍**命中率为 null**（真帧第 0 拍那种）+ 健康度走 **warn** 分支。 */
const F_PASS2 = [
  frame(0, { health: { level: 'ok', label: '健康', reasons: [] }, count: 0, qps: 0.0 }),
  // ⚠️ 本遍**两拍都不带锁**：⑫ 要验的是「窗口里没有任何锁活动 ⇒ 热力图**该**报空态」。
  //    只要有一拍带锁，`lockNames` 就非空、热力图就永远不进空态 ⇒ 那条断言必然假红。
  frame(1, { health: OK_HEALTH, count: 40, qps: 20.0, p99: 15.0, hit: 24, miss: 16, hitRate: 0.6 }),
  frame(2, {
    health: { level: 'warn', label: '注意', reasons: ['锁处于降级窗口（互斥未生效，fail-open 直行）'] },
    count: 0, qps: 0.0, hitRate: null, lockUp: 0.0, locks: [],
  }),
]

const CORS = [
  { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
  { name: 'Access-Control-Allow-Credentials', value: 'true' },
  { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
  { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
]

const seen = []
/** 0 = 第一遍（5 拍 · 命中率有值）｜1 = 第二遍（3 拍 · 末拍命中率 null）。见文件头 ⑪。 */
let mode = 0
const b = await open({ w: 1440, h: 1000 })

// ── 自接管 Fetch 拦截 ────────────────────────────────────────────────────────
await b.send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
const json = (obj) => ({ responseCode: 200, responseHeaders: [...CORS, { name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify(obj)).toString('base64') })

b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  const url = request.url
  seen.push({ url, method: request.method })
  if (request.method === 'OPTIONS') {
    b.send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: CORS, body: '' }).catch(() => {})
    return
  }
  let payload
  if (url.includes('/admin/observe/metrics/stream')) {
    // ⚠️ SSE：**把整段流一次投完**（理由见文件头）。`stream` 头照后端的 `SseStreamWriter` 写。
    const frames = mode === 0 ? F_PASS1 : F_PASS2
    const body = frames.map((f) => 'data: ' + JSON.stringify(f) + '\n\n').join('')
    payload = {
      responseCode: 200,
      responseHeaders: [...CORS, { name: 'Content-Type', value: 'text/event-stream' }, { name: 'Cache-Control', value: 'no-cache' }],
      body: Buffer.from(body).toString('base64'),
    }
  } else {
    payload = json({ success: true, list: [], total: 0, limit: 20, offset: 0 })
  }
  b.send('Fetch.fulfillRequest', { requestId, ...payload }).catch(() => {})
})

// ── 会话注入 + 「卸载是否真 close()」的计数器 ─────────────────────────────────
// ⚠️ 必须在**应用脚本之前**装：`openStream` 拿到 reader 后才会调 `cancel()`，晚了就包不上。
await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => {
    try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {}
    window.__cancels = 0
    const RC = ReadableStreamDefaultReader.prototype.cancel
    ReadableStreamDefaultReader.prototype.cancel = function (...a) {
      try { window.__cancels++ } catch (e) {}
      return RC.apply(this, a)
    }
    window.__errs = []
    const oe = console.error.bind(console)
    console.error = (...a) => { try { window.__errs.push(a.map(String).join(' ')) } catch (e) {} ; oe(...a) }
    window.addEventListener('unhandledrejection', (e) => { try { window.__errs.push('unhandledrejection: ' + e.reason) } catch (x) {} })
  })()`,
})

const TEXT = (sel) => `[...document.querySelectorAll('${sel}')].map(e => e.innerText.trim())`

try {
  await b.goto(`${BASE}/${ROUTE}`, 2600)
  await sleep(900) // 等 0.3s 数字滚动动画收敛

  // ── ① 挂载 ────────────────────────────────────────────────────────────────
  const bodyText = await b.evalJS('document.body.innerText')
  ok('① 页面挂载（正文 > 400 字）', String(bodyText).length > 400, String(bodyText).length)
  ok('① 面包屑含「指标面板」', String(bodyText).includes('指标面板'))
  // ⚠️ 这条是**截图逼出来的**：副标题里的 Markdown 星号被当 HTML 文本原样渲染
  //    （`本拍无请求的拍点上留**空**…`）。本仓注释/文档一律用 `**强调**`，很容易顺手
  //    写进 `<span>` 里 —— 加一条兜底，别只靠肉眼。
  ok('① 页面上没有裸露的 Markdown 星号（`**` 不许上屏）', !String(bodyText).includes('**'), String(bodyText).includes('**'))
  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))

  // ── ② 健康度（后端算，本页不重算）+ 判据条 ────────────────────────────────
  const hv = await b.evalJS(`(() => { const e = document.querySelector('.a-hv'); return e ? { t: e.innerText.trim(), c: e.className } : null })()`)
  ok('② 健康度大字 = 后端给的 label「健康」且挂 ok 配色', !!hv && hv.t === '健康' && hv.c.includes('a-hv--ok'), hv)
  const reasons = await b.evalJS(TEXT('.a-reasons li'))
  ok('② 判据条渲染**后端原话**（不是前端拼的）',
    reasons.length === 1 && reasons[0] === 'L1 命中率 60.0%', reasons)

  // ── ③ 状态条 5xx / 4xx ────────────────────────────────────────────────────
  const stats = await b.evalJS(TEXT('.a-stat b'))
  ok('③ 状态条 = 健康度 / 5xx 1.2% / 4xx 0.4%',
    stats.length === 3 && stats[1] === '1.2%' && stats[2] === '0.4%', stats)
  const phase = await b.evalJS(`(document.querySelector('.a-chip-utc') || {}).innerText || ''`)
  const seqTxt = await b.evalJS(`(document.querySelector('.a-tz') || {}).innerText || ''`)
  ok('③ 报出拍号（第 4 拍）与窗口长度（60 秒）', String(seqTxt).includes('4') && String(seqTxt).includes('60'), String(seqTxt).replace(/\s+/g, ' '))

  // ── ④ KPI 卡标签 ──────────────────────────────────────────────────────────
  // ⚠️ 取**每格最后一行**而不是整格 `innerText`：`.kpi-k` 里除标签外还有一个 `IconGlyph`
  //    ——它渲染的是**文字字形**（如 `◔`），`innerText` 会把它带成 `"◔\nQPS"`
  //    ⇒ 直接比 `'QPS'` 必然假红（本笔第一版就踩了，报出来的假象是「标签不对」，
  //    实际标签一个字没错）。
  const kLabels = await b.evalJS(`[...document.querySelectorAll('.kpi-k')].map(e => e.innerText.trim().split('\\n').pop().trim())`)
  ok('④ 四张 KPI 卡 = QPS / P99 延迟 / 缓存命中率 / 锁等待次数',
    JSON.stringify(kLabels) === JSON.stringify(['QPS', 'P99 延迟', '缓存命中率', '锁等待次数']), kLabels)

  // ── ⑤ KPI 值 ──────────────────────────────────────────────────────────────
  const kVals = await b.evalJS(TEXT('.kpi-n'))
  ok('⑤ 值 = 12.5 / 22 / 60.0 / 2（P99 21.5 四舍五入；锁等待 = 2+0）',
    JSON.stringify(kVals) === JSON.stringify(['12.5', '22', '60.0', '2']), kVals)
  const kUnits = await b.evalJS(TEXT('.kpi-u'))
  ok('⑤ 单位 = 次/秒 / ms / % / 次',
    JSON.stringify(kUnits) === JSON.stringify(['次/秒', 'ms', '%', '次']), kUnits)

  // ── ⑥ 环比口径 ────────────────────────────────────────────────────────────
  const dl = await b.evalJS(TEXT('.kpi-dl'))
  ok('⑥ 环比右侧文案是「较 60 秒均值」（不是「较上一拍」/「较昨日」）',
    dl.length >= 1 && dl.every((t) => t === '较 60 秒均值'), dl)
  // ⚠️ 同上，也是截图逼出来的：不给 `deltaText` 时 `KpiCard` 回落到 `Math.abs(delta) + '%'`
  //    ⇒ 上屏 `▼13.793103448275861%`。断言「箭头 + 一位小数 + %」，把浮点挡在门外。
  //    ⚠️ 箭头与数字之间**有一个空格**（`KpiCard` 模板里 `{{ arrow }}{{ deltaText }}` 渲染出来的
  //    实际是 `▼ 13.8%`）⇒ 正则要允许 `\s*`，写死 `^[▲▼]\d` 会假红。
  const arrows = await b.evalJS(TEXT('.kpi-arrow'))
  ok('⑥ 环比百分数只留一位小数（不把浮点原样上屏）',
    arrows.length >= 1 && arrows.every((t) => /^[▲▼]\s*\d+\.\d%$/.test(t)), arrows)

  // ── ⑦ 四个图 ──────────────────────────────────────────────────────────────
  const bc = await b.evalJS(`document.querySelectorAll('.bc').length`)
  const canv = await b.evalJS(`document.querySelectorAll('.bc canvas').length`)
  const state = await b.evalJS(`document.querySelectorAll('.bc-state').length`)
  ok('⑦ 四个图画布都在（QPS 面积 / 命中率折线 / 热力图 / 防护柱状）', bc === 4, bc)
  ok('⑦ 四个 canvas 都真画了', canv === 4, canv)
  ok('⑦ 没有空态/加载态浮层（数据都到位了）', state === 0, state)

  // ── ⑧ 热力图真有数据（首次调用点）─────────────────────────────────────────
  const hmEmpty = String(bodyText).includes('窗口内没有任何锁活动')
  ok('⑧ 热力图**不是**空态（窗口里出现过 2 把锁 ⇒ 2 行 × 4 列）', hmEmpty === false, hmEmpty)
  await b.shot(SHOT)

  // ── ⑨ 库存水位三条 ────────────────────────────────────────────────────────
  const bars = await b.evalJS(`[...document.querySelectorAll('.a-bar-fill')].map(e => e.className.replace('a-bar-fill ',''))`)
  ok('⑨ 水位条三条并各挂对的档：有基线 0.9=绿 / 0.1=红 / 无基线=灰斜纹',
    JSON.stringify(bars) === JSON.stringify(['is-ok', 'is-low', 'is-unknown']), bars)
  const barN = await b.evalJS(TEXT('.a-bar-n'))
  ok('⑨ 无基线那行是 `100 / —`（**不是 0**）', String(barN[2]).includes('—') && !String(barN[2]).includes('0 /'), barN)

  // ── ⑩ 正常收尾 ⇒ 已断开，不是错误 ─────────────────────────────────────────
  ok('⑩ 流投完后显示「已断开」', phase === '已断开', phase)
  const badFlash = await b.evalJS(`document.querySelectorAll('.a-flash.bad').length`)
  ok('⑩ 正常收尾**不**画红条（onEnd(null) 不是故障）', badFlash === 0, badFlash)

  // ── ⑪⑫ 第二遍：命中率 null + warn 健康度 ─────────────────────────────────
  // ⚠️ 先落 `about:blank` 再进路由：`Page.navigate` 到**同一个 URL** 不保证重载
  //    （SPA 里可能被当成一次 no-op）⇒ mode 改了、页面却还是第一遍的，⑪⑫ 会假红。
  //    顺带：新文档会重置 `__cancels`（计数器是 addScriptToEvaluateOnNewDocument 装的），
  //    这正是 ⑬ 需要的基线（只数第二遍那一次卸载）。
  mode = 1
  await b.goto('about:blank', 400)
  await b.goto(`${BASE}/${ROUTE}`, 2600)
  await sleep(900)
  const kVals2 = await b.evalJS(TEXT('.kpi-n'))
  ok('⑪ 命中率 null ⇒ 卡上显示 `—`（缺值不留 0）', kVals2[2] === '—', kVals2)
  ok('⑪ 同一拍 QPS 仍是数（只有 null 的那格才显示 —）', kVals2[0] === '0.0', kVals2)
  const hv2 = await b.evalJS(`(() => { const e = document.querySelector('.a-hv'); return e ? { t: e.innerText.trim(), c: e.className } : null })()`)
  ok('⑫ 健康度走 warn 分支（大字「注意」+ 黄色）', !!hv2 && hv2.t === '注意' && hv2.c.includes('a-hv--warn'), hv2)
  const re2 = await b.evalJS(`(() => { const e = document.querySelector('.a-reasons'); return e ? { c: e.className, n: e.querySelectorAll('li').length } : null })()`)
  ok('⑫ 判据条挂 `a-reasons--warn` 且 1 条', !!re2 && re2.c.includes('a-reasons--warn') && re2.n === 1, re2)
  const hmEmpty2 = await b.evalJS(`String(document.body.innerText).includes('窗口内没有任何锁活动')`)
  ok('⑫ 该拍没有锁活动 ⇒ 热力图**该**报空态（空态是真的空，不是画错）', hmEmpty2 === true, hmEmpty2)

  // ── ⑬ 卸载真调 close() ────────────────────────────────────────────────────
  await b.evalJS(`(() => { window.__errsBeforeNav = (window.__errs || []).length; return true })()`)
  await b.evalJS(`(() => { location.hash = '${STUB}'; return true })()`)
  await sleep(700)
  const cancels = await b.evalJS('window.__cancels || 0')
  ok('⑬ 切走后 `reader.cancel()` 被调用（页面卸载真的 close 了流）', cancels >= 1, `cancels=${cancels}`)
} finally {
  const errs2 = await b.evalJS('(window.__errs || [])').catch(() => [])
  const base = await b.evalJS('window.__errsBeforeNav || 0').catch(() => 0)
  ok('全流程收尾：切走之后无**新增** console.error / unhandledrejection', (errs2 || []).length === base, `total=${(errs2 || []).length} base=${base}`)
  const bad = R.dump()
  console.log(`\n截图：${SHOT}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
