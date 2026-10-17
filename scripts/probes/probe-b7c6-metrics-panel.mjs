// probe-b7c6-metrics-panel.mjs —— 块 7 · 3a.4〈指标面板重做 S1 时间栈 + 实体栏〉的**渲染实测**。
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7c6-metrics-panel.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ⚠️ **mock 的帧形状照真后端抄**（`_work/3a4/metrics-real-sample.txt`）：`intervalMs`、`errors4xx/
//    errors5xx`、`meanMs`、`cache.hit/miss/l2Degraded`、锁的 `acquires/acquired/timeouts/degraded/
//    errors/waitMeanMs` 一个不少。**指标页的时间轴只画 `meanMs`**；`p99Ms` 是 Micrometer 衰减窗口
//    分位（TD-77），探针专门断言它没有混进延迟序列。
//
// ⚠️ **SSE 的 mock 手法**：CDP `Fetch.fulfillRequest` 只能一次性给完 body ⇒ 把 N 帧拼成一个
//    `text/event-stream` body 一次投喂，`api/observe.js` 的缓冲切帧循环会把它们全部解析出来。
//    代价是流随即收尾 ⇒ 页面进「已断开」态（⑩ 断言正常收尾不画红条）。
//
// 查这些事：
//   ① 挂载 / 无 console.error / 无 unhandledrejection / 无裸露 Markdown
//   ② 五张图（QPS / 命中率 / 平均延迟 / 锁等待 / 热力图）都是 canvas，且无空态浮层
//   ③ **共享 x 轴**：五图 `grid.left=64 / right=56`、x 类目逐字相同、前四张 `xAxis.show=false`、
//      最底热力图才画刻度；五图 `boundaryGap=true`（柱心 / 折线点 / 热力格中心同一条竖线）
//   ④ 每张图都有 tooltip；tooltip 能报到正确单位（ms / 次/秒 / % / 超时 N 次）
//   ⑤ 峰值 / 谷值数字标注（markPoint）前四张各至少 1 个
//   ⑥ 颜色不贴背景 + 按值渐变：热力图 visualMap 双端色、平均延迟面积渐变存在
//   ⑦ 热力图：格数 > 0、色阶图例 `['超时多','超时少']`、tooltip 文案「锁名 · 第 N 拍 · 超时 N 次」
//   ⑧ KPI K1–K4 = QPS / 平均延迟 / 缓存命中率 / 锁等待次数；值取最后一拍（含 0.3s 滚动收敛）
//   ⑨ K2 用 `meanMs`，**不是 P99**；P99 只作卡片次要小字
//   ⑩ 状态带 / 健康度 / 严重度分布（health.level 的 ok/warn/error 拍数）
//   ⑪ 设施 3 类 + 8 类防护图标各带颜色（8 个不同环色）+ 图标文字间距 8px
//   ⑫ 库存水位：15 条、固定 220px 内部滚动、搜索 / 五个筛选、四种状态标签、无基线显示 `—`
//   ⑬ 库存水流动画：切到「模拟日常」后条宽随 rAF 推进（不是瞬间跳）
//   ⑭ 页高 ≤ 1.4×900=1260px（实测 140vh−124px=1136px）、右栏 380px、字号只有 12/15/19 三档
//   ⑮ 卸载真调 `reader.cancel()`
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ROUTE = '#/admin/observe/metrics'
const STUB = '#/admin/observe/timeline'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
const SHOT_TOP = `${EV}out-b7c6-metrics-panel-top.png`
const SHOT_BOTTOM = `${EV}out-b7c6-metrics-panel-bottom.png`

const R = report('块 7 · 3a.4 指标面板（S1 时间栈 + 实体栏 · mock 后端）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const PROT = [
  ['cacheHit', '缓存命中'], ['lockTimeout', '锁等待超时'], ['orderRejected', '订单流转被拒'],
  ['stockInsufficient', '库存不足（防超卖）'], ['auditFailed', '审计写入失败'],
  ['notifyFailed', '通知发送失败'], ['sensitiveHit', '敏感词拦下'], ['identityRejected', '身份拒绝'],
]

const sku = (s, good, axis, stock, baseline, ratio) => ({ sku: s, goodName: good, axisName: axis, stock, baseline, ratio })

/** 15 条库存：覆盖 充足 / 偏低 / 缺货 / 未设基线，并且足够多到能撑出内部滚动。 */
const STOCK = [
  sku('S-001', '气泡水 330ml', '330ml', 90, 100, 0.9),
  sku('S-002', '无线耳机', '标准版', 10, 100, 0.1),
  sku('S-003', '积木套装', '大盒', 0, 100, 0),
  sku('S-004', '数据线', 'Type-C', 50, null, null),
  sku('S-005', '保温杯', '500ml', 0, null, null),
  sku('S-006', '机械键盘', '茶轴', 72, 120, 0.6),
  sku('S-007', '2K 显示器', '27 英寸', 110, 120, 0.9167),
  sku('S-008', '蓝牙音箱', 'mini', 35, 80, 0.4375),
  sku('S-009', '充电宝', '10000mAh', 150, 200, 0.75),
  sku('S-010', '护眼台灯', '标准版', 18, 120, 0.15),
  sku('S-011', '鼠标垫', '加厚', 55, 60, 0.9167),
  sku('S-012', '智能手环', '标准版', 88, 160, 0.55),
  sku('S-013', '旅行背包', '20L', 24, 80, 0.3),
  sku('S-014', '咖啡豆', '500g', 64, 200, 0.32),
  sku('S-015', '保鲜盒', '三件套', 42, 120, 0.35),
]

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
    gauges: { l2Up: 1.0, lockUp: 1.0 },
    locks: (o.locks || []).map(([lock, timeouts, wait]) => ({
      lock,
      acquires: 40,
      acquired: Math.max(0, 40 - timeouts),
      timeouts,
      degraded: 0,
      errors: 0,
      waitMeanMs: wait === undefined ? 1.5 : wait,
    })),
    protections: PROT.map(([tag, label], i) => ({ tag, label, value: (o.prot || [])[i] || 0 })),
    stock: { available: true, rows: o.stock || STOCK },
  }
}

const OK_H = { level: 'ok', label: '健康', reasons: ['L1 命中率 60.0%'] }
const WARN_H = { level: 'warn', label: '注意', reasons: ['5xx 占比 1.2%（> 1%）'] }
const ERR_H = { level: 'error', label: '错误', reasons: ['5xx 占比 6.0%（≥ 5%）'] }

/**
 * 5 拍。最后一拍是页面最终读数：QPS 12.5 / mean 3.4 / hitRate 0.6 / 锁超时 2。
 * 健康度三档都出现：ok ×3（第 0/1/2 拍）、error ×1（第 3 拍）、warn ×1（第 4 拍）。
 */
const FRAMES = [
  frame(0, { health: { level: 'ok', label: '健康', reasons: [] }, count: 0, qps: 0.0 }),
  frame(1, {
    health: OK_H, count: 40, qps: 20.0, meanMs: 2.1, p99: 15.0, hit: 24, miss: 16, hitRate: 0.6,
    locks: [['goods_sku', 0, 1.5], ['order_no', 0, 3.2]], prot: [5, 0, 1, 0, 0, 0, 2, 0],
  }),
  frame(2, {
    health: OK_H, count: 30, qps: 15.0, meanMs: 3.0, p99: 18.0, hit: 15, miss: 15, hitRate: 0.5,
    locks: [['goods_sku', 2, 4.5], ['order_no', 0, 2.1]], prot: [2, 2, 0, 1, 0, 0, 0, 0],
  }),
  frame(3, {
    health: ERR_H, count: 50, qps: 25.0, e5: 3, errorRate: 0.06, clientErrorRate: 0.02,
    meanMs: 4.0, p95: 20.0, p99: 30.0, max: 90.0, hit: 20, miss: 30, hitRate: 0.4,
    locks: [['goods_sku', 0, 1.2], ['order_no', 3, 6.0]], prot: [9, 0, 0, 0, 1, 0, 0, 1],
  }),
  frame(4, {
    health: WARN_H, count: 25, qps: 12.5, e5: 3, e4: 1, errorRate: 0.012, clientErrorRate: 0.004,
    meanMs: 3.4, p95: 12.0, p99: 21.5, max: 60.0, hit: 10, miss: 10, hitRate: 0.6,
    locks: [['goods_sku', 2, 1.5], ['order_no', 0, 3.2]], prot: [1, 2, 3, 0, 0, 0, 0, 0],
  }),
]

const CORS = [
  { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
  { name: 'Access-Control-Allow-Credentials', value: 'true' },
  { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
  { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
]

const b = await open({ w: 1440, h: 900 })

/**
 * ⚠️ 只投一次帧。
 *
 * `openMetricsStream` 是**带自动重连**的一层：mock 的有限 body 一投完，流就「正常收尾」，
 * 500ms 后页面会自己再开一条。若每次都投同一组 5 帧，30 秒内会攒出几十拍、严重度分布和
 * 序列长度全被重复喂大（本探针第一版就是这么假红的）。
 * ⇒ 第一次请求投帧；之后的重连请求**不 fulfill**（留成 pending），页面停在「已断开」，
 *    不会再有新帧进来。代价是收尾的「已断开」要在切演示模式**之前**断言。
 */
let METRICS_REQ = 0
let METRICS_SERVED = false

await b.send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
const json = (obj) => ({
  responseCode: 200,
  responseHeaders: [...CORS, { name: 'Content-Type', value: 'application/json' }],
  body: Buffer.from(JSON.stringify(obj)).toString('base64'),
})

b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  if (request.method === 'OPTIONS') {
    b.send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: CORS, body: '' }).catch(() => {})
    return
  }
  let payload
  if (request.url.includes('/admin/observe/metrics/stream')) {
    METRICS_REQ += 1
    if (METRICS_SERVED) return // 重连请求留成 pending：不再投帧，页面停在「已断开」
    METRICS_SERVED = true
    const body = FRAMES.map((f) => 'data: ' + JSON.stringify(f) + '\n\n').join('')
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
  await sleep(900) // 等 0.3s KPI 滚动 / 0.6s 库存推进收敛

  // ── ① 挂载 ────────────────────────────────────────────────────────────────
  const bodyText = await b.evalJS('document.body.innerText')
  ok('① 页面挂载（正文 > 400 字）', String(bodyText).length > 400, String(bodyText).length)
  ok('① 面包屑含「指标面板」', String(bodyText).includes('指标面板'))
  ok('① 页面上没有裸露的 Markdown 星号', !String(bodyText).includes('**'), String(bodyText).includes('**'))
  const errs = await b.evalJS('(window.__errs || [])')
  ok('① 无 console.error / unhandledrejection', errs.length === 0, errs.slice(0, 3))

  // ── ② 五张图 ──────────────────────────────────────────────────────────────
  const bc = await b.evalJS(`document.querySelectorAll('.bc').length`)
  const canv = await b.evalJS(`document.querySelectorAll('.bc canvas').length`)
  const chartSections = await b.evalJS(`document.querySelectorAll('.mp-chart').length`)
  const state = await b.evalJS(`document.querySelectorAll('.bc-state').length`)
  ok('② 五张图都在（`.mp-chart` × 5 / `.bc` × 5）', chartSections === 5 && bc === 5, { chartSections, bc })
  ok('② 五个 canvas 都真画了', canv === 5, canv)
  ok('② 没有空态/加载态浮层', state === 0, state)

  // ── ③ 共享 x 轴几何 + ④ tooltip + ⑤ markPoint + ⑥ 渐变 / ⑦ 热力图 ────────
  const charts = await b.evalJS(`(() => {
    const els = [...document.querySelectorAll('.bc-canvas')]
    return els.map((el) => {
      const c = window.__echarts.getInstanceByDom(el)
      if (!c) return null
      const o = c.getOption()
      return {
        grid: o.grid && o.grid[0] ? { l: o.grid[0].left, r: o.grid[0].right } : null,
        xShow: o.xAxis && o.xAxis[0] ? o.xAxis[0].show !== false : null,
        xData: o.xAxis && o.xAxis[0] ? o.xAxis[0].data : null,
        bg: o.xAxis && o.xAxis[0] ? o.xAxis[0].boundaryGap : null,
        yAxis: (o.yAxis || []).map((a) => ({ min: a.min, max: a.max, show: a.show !== false })),
        tooltip: (o.tooltip || []).map((t) => ({ show: t.show !== false, trigger: t.trigger })),
        visualMap: (o.visualMap || []).map((v) => ({ max: v.max, calculable: v.calculable, text: v.text, colors: v.inRange && v.inRange.color })),
        series: (o.series || []).map((s) => ({
          type: s.type, name: s.name, stack: s.stack, yAxisIndex: s.yAxisIndex,
          data: s.data,
          markPoints: s.markPoint && s.markPoint.data ? s.markPoint.data.length : 0,
          markLabels: s.markPoint && s.markPoint.data ? s.markPoint.data.map((d) => d.labelText || '') : [],
          area: !!s.areaStyle,
          color: (s.itemStyle && s.itemStyle.color) || (s.lineStyle && s.lineStyle.color) || '',
        })),
      }
    })
  })()`)
  ok('③ 五个 ECharts 实例都取到 option', Array.isArray(charts) && charts.every(Boolean), charts && charts.map(Boolean))
  const geometry = charts.map((c) => ({ l: c.grid && Math.round(c.grid.l), r: c.grid && Math.round(c.grid.r) }))
  ok('③ 五图绘图区同几何：left=64 / right=56',
    geometry.every((g) => g.l === 64 && g.r === 56), geometry)
  ok('③ 前四张隐藏 x 轴、最底热力图画刻度',
    JSON.stringify(charts.map((c) => c.xShow)) === JSON.stringify([false, false, false, false, true]),
    charts.map((c) => c.xShow))
  const firstX = charts[0].xData
  const xSame = charts.every((c) => JSON.stringify(c.xData) === JSON.stringify(firstX))
  ok('③ 五图 x 类目逐字相同（同一根轴）', xSame, charts.map((c) => (c.xData || []).slice(-2)))
  ok('③ 五图 boundaryGap=true（柱心/折线点/热力格中心对齐）',
    charts.every((c) => c.bg === true), charts.map((c) => c.bg))
  ok('③ 五图 y 轴都在', charts.every((c) => c.yAxis.length > 0 && c.yAxis[0].show), charts.map((c) => c.yAxis))

  // tooltip 存在 + 自定义内容能报单位
  ok('④ 五图都开 tooltip（前四张轴触发 / 热力图格触发）',
    charts.every((c) => c.tooltip.length > 0 && c.tooltip[0].show)
      && charts.slice(0, 4).every((c) => c.tooltip[0].trigger === 'axis')
      && charts[4].tooltip[0].trigger === 'item',
    charts.map((c) => c.tooltip))

  const tip = (idx, dataIndex) => `(() => {
    const el = document.querySelectorAll('.bc-canvas')[${idx}]
    const c = window.__echarts.getInstanceByDom(el)
    c.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: ${dataIndex} })
    return new Promise((resolve) => setTimeout(() => {
      const d = [...el.querySelectorAll('div')].map((x) => x.innerText).find((t) => t && t.length > 4)
      resolve(d || '')
    }, 120))
  })()`
  const qpsTip = await b.evalJS(tip(0, 4))
  ok('④ QPS tooltip 报「次/秒」和本拍请求数', qpsTip.includes('次/秒') && qpsTip.includes('本拍请求'), qpsTip)
  const hitTip = await b.evalJS(tip(1, 4))
  ok('④ 命中率 tooltip 报命中 / 未命中 / %', hitTip.includes('命中') && hitTip.includes('%'), hitTip)
  const meanTip = await b.evalJS(tip(2, 1))
  ok('④ 平均延迟 tooltip 报 ms', meanTip.includes('平均延迟') && meanTip.includes('ms'), meanTip)
  const waitTip = await b.evalJS(tip(3, 3))
  ok('④ 锁等待 tooltip 报锁名 + ms + 超时', waitTip.includes('order_no') && waitTip.includes('ms') && waitTip.includes('超时'), waitTip)
  const heatTip = await b.evalJS(tip(4, 0))
  ok('⑦ 热力图 tooltip =「锁名 · 第 N 拍 · 超时 N 次」+ 取锁明细',
    heatTip.includes('goods_sku') && /第 \d+ 拍/.test(heatTip) && heatTip.includes('超时') && heatTip.includes('取锁'),
    heatTip)

  // markPoint 峰值/谷值
  const mp = charts.map((c) => c.series.map((s) => s.markPoints))
  ok('⑤ QPS 图有峰/谷数字标注', charts[0].series.some((s) => s.markPoints >= 1), mp[0])
  ok('⑤ 命中率图有峰/谷数字标注', charts[1].series.some((s) => s.markPoints >= 1), mp[1])
  ok('⑤ 平均延迟图有峰/谷数字标注', charts[2].series.some((s) => s.markPoints >= 1), mp[2])
  ok('⑤ 锁等待图有峰/谷数字标注', charts[3].series.some((s) => s.markPoints >= 1), mp[3])
  const mpText = [].concat(...charts.slice(0, 4).map((c) => [].concat(...c.series.map((s) => s.markLabels))))
  ok('⑤ 标注文字是「峰 / 谷 / 持平」+ 数值', mpText.some((t) => /^(峰|谷|持平)/.test(String(t))), mpText)

  // 延迟序列只认 meanMs，不混入 p99
  const meanData = charts[2].series[0].data
  ok('⑨ 平均延迟序列 = `meanMs`（[null,2.1,3,4,3.4]），P99 没有混进时间轴',
    JSON.stringify(meanData) === JSON.stringify([null, 2.1, 3, 4, 3.4]), meanData)
  ok('⑨ K2 卡上 P99 只作次要小字（21.5 → 22 ms）', String(bodyText).includes('P99（衰减窗口，非本拍）22 ms'))

  // 面积渐变 / 热力色阶
  ok('⑥ 平均延迟面积渐变存在', charts[2].series[0].area === true)
  const vm = charts[4].visualMap[0]
  ok('⑦ 热力图色阶图例 = 超时多 / 超时少，且两档色值不同',
    !!vm && JSON.stringify(vm.text) === JSON.stringify(['超时多', '超时少'])
      && Array.isArray(vm.colors) && vm.colors.length === 2 && vm.colors[0] !== vm.colors[1],
    vm)
  ok('⑦ 热力图有格子数据（8 格：4 拍 × 2 锁）', charts[4].series[0].data.length >= 6, charts[4].series[0].data.length)

  // 颜色离背景 & QPS 堆叠三色
  const bg = await b.evalJS(`getComputedStyle(document.querySelector('.mp-chart')).backgroundColor`)
  const dist = (hex) => {
    const h = String(hex).replace('#', '')
    const n = parseInt(h.length === 3 ? h.split('').map((x) => x + x).join('') : h, 16)
    const bg2 = String(bg).match(/\d+/g).map(Number)
    return Math.sqrt(((n >> 16) - bg2[0]) ** 2 + (((n >> 8) & 255) - bg2[1]) ** 2 + ((n & 255) - bg2[2]) ** 2)
  }
  const qpsColors = charts[0].series.map((s) => s.color)
  ok('⑥ QPS 三色都离背景足够远', qpsColors.every((c) => /^#[0-9a-f]{6}$/i.test(c) && dist(c) > 60), qpsColors.map((c) => [c, Math.round(dist(c))]))

  // ── ⑧ KPI ─────────────────────────────────────────────────────────────────
  const kLabels = await b.evalJS(TEXT('.mp-kpi-k'))
  ok('⑧ 四张卡 = QPS / 平均延迟 / 缓存命中率 / 锁等待次数',
    JSON.stringify(kLabels) === JSON.stringify(['QPS', '平均延迟', '缓存命中率', '锁等待次数']), kLabels)
  const kVals = await b.evalJS(TEXT('.mp-kpi-v b'))
  ok('⑧ 值取最后一拍 = 12.5 / 3 / 60.0 / 2',
    JSON.stringify(kVals) === JSON.stringify(['12.5', '3', '60.0', '2']), kVals)
  const kUnits = await b.evalJS(TEXT('.mp-kpi-v em'))
  ok('⑧ 单位 = 次/秒 / ms / % / 次',
    JSON.stringify(kUnits) === JSON.stringify(['次/秒', 'ms', '%', '次']), kUnits)
  const sparks = await b.evalJS(`document.querySelectorAll('.mp-spark').length`)
  ok('⑧ 微条只挂在 K1 / K3 两张卡上（K2/K4 写「无序列」）', sparks === 2, sparks)

  // ── ⑩ 正常收尾（必须在切演示模式之前验：之后的重连请求不再投帧）────────────
  const phase = await b.evalJS(`(document.querySelector('.a-chip-utc') || {}).innerText || ''`)
  ok('⑩ mock 流投完后显示「已断开」', phase === '已断开', phase)
  const badFlash = await b.evalJS(`document.querySelectorAll('.a-flash.bad').length`)
  ok('⑩ 正常收尾不画红条', badFlash === 0, badFlash)

  // ── ⑩ 状态带 / 严重度 ─────────────────────────────────────────────────────
  const hv = await b.evalJS(`(() => { const e = document.querySelector('.a-hv'); return e ? { t: e.innerText.trim(), c: e.className } : null })()`)
  ok('⑩ 健康度大字 = 后端 label「注意」+ warn 配色', !!hv && hv.t === '注意' && hv.c.includes('a-hv--warn'), hv)
  const stats = await b.evalJS(TEXT('.a-stat b'))
  ok('⑩ 状态条 = 健康度 / 5xx 1.2% / 4xx 0.4%',
    stats.length === 3 && stats[1] === '1.2%' && stats[2] === '0.4%', stats)
  const reasons = await b.evalJS(TEXT('.a-reasons li'))
  ok('⑩ 判据条渲染后端原话', reasons.length === 1 && reasons[0] === '5xx 占比 1.2%（> 1%）', reasons)
  const sev = await b.evalJS(TEXT('.mp-sevs b'))
  ok('⑩ 严重度分布 = ok 3 / warn 1 / error 1（按 health.level 数拍）',
    JSON.stringify(sev) === JSON.stringify(['3', '1', '1']), sev)

  // ── ⑪ 设施 / 8 类防护 ────────────────────────────────────────────────────
  const fac = await b.evalJS(TEXT('.mp-fac-s'))
  ok('⑪ 设施 3 类都在 = 正常 / 正常 / 正常', fac.length === 3 && fac.every((t) => t === '正常'), fac)
  const protCount = await b.evalJS(`document.querySelectorAll('.mp-prot').length`)
  ok('⑪ 防护 8 类逐行渲染', protCount === 8, protCount)
  const iconColors = await b.evalJS(`[...document.querySelectorAll('.mp-prot-k svg')].map((e) => getComputedStyle(e).color)`)
  ok('⑪ 8 个防护图标各带颜色（≥ 7 种不同色）', new Set(iconColors).size >= 7, iconColors)
  const gap = await b.evalJS(`getComputedStyle(document.querySelector('.mp-prot-k')).columnGap`)
  ok('⑪ 图标与文字间距 8px', gap === '8px', gap)
  const stockIconColor = await b.evalJS(`(() => { const e = document.querySelector('.mp-rail-h svg'); return e ? getComputedStyle(e).color : '' })()`)
  ok('⑪ 第 9 类图标（库存水位水滴）有颜色', !!stockIconColor && stockIconColor !== 'rgb(0, 0, 0)', stockIconColor)

  // ── ⑫ 库存水位 ────────────────────────────────────────────────────────────
  const stockRows = await b.evalJS(`document.querySelectorAll('.mp-stock-row').length`)
  ok('⑫ 15 条库存都渲染', stockRows === 15, stockRows)
  const listBox = await b.evalJS(`(() => { const e = document.querySelector('.mp-stock-list'); const cs = getComputedStyle(e); return { h: Math.round(e.getBoundingClientRect().height), sh: e.scrollHeight, ch: e.clientHeight, oy: cs.overflowY } })()`)
  ok('⑫ 库存区固定 220px 且内部滚动（scrollHeight > clientHeight）',
    listBox.h === 220 && listBox.sh > listBox.ch && listBox.oy === 'auto', listBox)
  const statuses = await b.evalJS(TEXT('.mp-stock-tag'))
  ok('⑫ 状态标签 = 充足 / 偏低 / 缺货 / 未测',
    statuses.slice(0, 4).join('/') === '充足/偏低/缺货/未测', statuses.slice(0, 6))
  const barTones = await b.evalJS(`[...document.querySelectorAll('.mp-stock-fill')].slice(0, 4).map((e) => e.className.replace('mp-stock-fill ', ''))`)
  ok('⑫ 水位条档位 = is-ok / is-low / is-low / is-unknown',
    JSON.stringify(barTones) === JSON.stringify(['is-ok', 'is-low', 'is-low', 'is-unknown']), barTones)
  const nullBase = await b.evalJS(`document.querySelectorAll('.mp-stock-n')[3].innerText.replace(/\\s+/g, ' ')`)
  ok('⑫ 无基线那行基线显示 `—`（不是 0）', String(nullBase).includes('/ —'), nullBase)
  const scrollCss = await b.evalJS(`getComputedStyle(document.querySelector('.mp-stock-list')).scrollbarWidth`)
  ok('⑫ 滚动条按 thin（≤8px 档）', scrollCss === 'thin', scrollCss)

  // 搜索：按商品名
  await b.evalJS(`(() => {
    const el = document.querySelector('.mp-stock-search')
    el.value = '气泡水'
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(120)
  const searchCount = await b.evalJS(`document.querySelectorAll('.mp-stock-row').length`)
  ok('⑫ 搜索「气泡水」只剩 1 条', searchCount === 1, searchCount)
  // 清空后按「缺货」筛选
  await b.evalJS(`(() => {
    const el = document.querySelector('.mp-stock-search')
    el.value = ''
    el.dispatchEvent(new Event('input', { bubbles: true }))
    const btn = [...document.querySelectorAll('.mp-chip')].find((x) => x.innerText.trim() === '缺货')
    btn.click()
    return true
  })()`)
  await sleep(120)
  const outTags = await b.evalJS(TEXT('.mp-stock-tag'))
  ok('⑫ 筛选「缺货」= 2 条且标签全为缺货', outTags.length === 2 && outTags.every((t) => t === '缺货'), outTags)
  await b.evalJS(`(() => { const btn = [...document.querySelectorAll('.mp-chip')].find((x) => x.innerText.trim() === '全部'); btn.click(); return true })()`)
  await sleep(80)
  ok('⑫ 页面上写明搜索范围仅限最低库存 20 条', String(bodyText).includes('仅在最低库存 20 条内搜索'))

  // 证据图：顶部（完整 5 拍 · 五图 / 右栏 KPI）与底部（防护 / 库存水位）。
  // ⚠️ 先收掉前面 `showTip` 留下的 tooltip，不然截图里三张图各挂一块黑框。
  await b.evalJS(`(() => {
    [...document.querySelectorAll('.bc-canvas')].forEach((el) => {
      const c = window.__echarts.getInstanceByDom(el)
      if (c) c.dispatchAction({ type: 'hideTip' })
    })
    return true
  })()`)
  await b.evalJS(`(() => { document.querySelector('.mp-body').scrollTop = 0; return true })()`)
  await sleep(120)
  await b.shot(SHOT_TOP)
  await b.evalJS(`(() => { const e = document.querySelector('.mp-body'); e.scrollTop = e.scrollHeight; return true })()`)
  // 页高是 1.4×视口，900 高窗口里下半页要滚动外层页面才看得见（内部 `.mp-body` 还有一层滚动）。
  await b.evalJS(`(() => { window.scrollTo(0, document.documentElement.scrollHeight); return true })()`)
  await sleep(120)
  await b.shot(SHOT_BOTTOM)
  const reach = await b.evalJS(`(() => {
    const body = document.querySelector('.mp-body').getBoundingClientRect()
    const list = document.querySelector('.mp-stock-list').getBoundingClientRect()
    return { bodyTop: body.top, bodyBottom: body.bottom, listTop: list.top, listBottom: list.bottom }
  })()`)
  ok('⑫ 滚到底时库存列表完整落在可视区内（右栏高度计入 scrollHeight）',
    reach.listBottom <= reach.bodyBottom + 1 && reach.listTop >= reach.bodyTop - 1, reach)
  await b.evalJS(`(() => { window.scrollTo(0, 0); document.querySelector('.mp-body').scrollTop = 0; return true })()`)

  // ── ⑬ 库存水流动画（切到模拟日常，rAF 推进条宽）─────────────────────────
  await b.evalJS(`(() => { const btn = [...document.querySelectorAll('.a-seg-b')].find((x) => x.innerText.trim() === '模拟日常'); btn.click(); return true })()`)
  await sleep(80)
  const w1 = await b.evalJS(`document.querySelectorAll('.mp-stock-row')[1].querySelector('.mp-stock-fill').getBoundingClientRect().width`)
  await sleep(700)
  const w2 = await b.evalJS(`document.querySelectorAll('.mp-stock-row')[1].querySelector('.mp-stock-fill').getBoundingClientRect().width`)
  ok('⑬ 库存条宽随 rAF 从 0 推进到目标（80ms 时宽 < 800ms 时宽）', w2 > w1 + 5, { w1: Math.round(w1 * 10) / 10, w2: Math.round(w2 * 10) / 10 })
  const demoText = await b.evalJS(`(document.querySelector('.a-dm-n') || {}).innerText || ''`)
  ok('⑬ 演示模式自报「假数据 · 不入库」', String(demoText).includes('假数据 · 不入库'), String(demoText).replace(/\\s+/g, ' '))

  // ── ⑭ 页高 / 右栏宽度 / 字号 ────────────────────────────────────────────────
  const page = await b.evalJS(`(() => {
    const e = document.querySelector('.a-mp')
    const body = document.querySelector('.mp-body')
    const rail = document.querySelector('.mp-rail')
    return {
      h: Math.round(e.getBoundingClientRect().height),
      bodyScroll: body.scrollHeight, bodyClient: body.clientHeight,
      rail: Math.round(rail.getBoundingClientRect().width),
    }
  })()`)
  ok('⑭ 页高 ≤ 1.4×900=1260px（实测 140vh−124px）', page.h <= 1260 && page.h >= 1000, page)
  ok('⑭ 主体内部滚动（内容高于可视区）', page.bodyScroll > page.bodyClient, page)
  ok('⑭ 右栏 380px', page.rail >= 379 && page.rail <= 381, page.rail)
  const fs = await b.evalJS(`[...document.querySelectorAll('.a-mp *')]
    .filter((e) => e.children.length === 0 && e.textContent.trim())
    .map((e) => getComputedStyle(e).fontSize)`)
  const fsSet = [...new Set(fs)].sort()
  ok('⑭ 字号只有 12 / 15 / 19 三档', JSON.stringify(fsSet) === JSON.stringify(['12px', '15px', '19px']), fsSet)

  // ── ⑮ 卸载停流：切走后不再发起新的指标流请求 ──────────────────────────────
  // ⚠️ 不再断言 `reader.cancel()`：演示模式切回「正常」时那条请求还挂在 pending（见文件头
  //    「只投一次帧」），reader 尚未建立 ⇒ cancel 不可能被调用。用「请求数不再增长」
  //    验证 `close()` 停掉了自动重连，比数 cancel 更贴近「切走之后不再污染后端日志」。
  await b.evalJS(`(() => { window.__errsBeforeNav = (window.__errs || []).length; return true })()`)
  const reqBefore = METRICS_REQ
  await b.evalJS(`(() => { location.hash = '${STUB}'; return true })()`)
  await sleep(1600)
  const reqAfter = METRICS_REQ
  const cancels = await b.evalJS('window.__cancels || 0')
  ok('⑮ 切走后不再发起新的指标流请求（close 停掉了重连）',
    reqAfter === reqBefore, { reqBefore, reqAfter, cancels })
} finally {
  const errs2 = await b.evalJS('(window.__errs || [])').catch(() => [])
  const base = await b.evalJS('window.__errsBeforeNav || 0').catch(() => 0)
  ok('全流程收尾：切走之后无新增 console.error / unhandledrejection', (errs2 || []).length === base, `total=${(errs2 || []).length} base=${base}`)
  const bad = R.dump()
  console.log(`\n截图：\n  ${SHOT_TOP}\n  ${SHOT_BOTTOM}`)
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
