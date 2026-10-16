// probe-b7a2-echarts-theme.mjs —— 3a.2〈ECharts 主题〉的**等价性实测**（2026-10-16）。
//
// 跑法：① `npm run serve`（8080，改前/改后各起一次）② `node scripts/probes/probe-b7a2-echarts-theme.mjs <tag>`
//        tag 是自由文本（`after` / `after2` / `before` …）⇒ 产物名带 tag，互不覆盖。
//        `TRACE=1` 会逐轮打画布指纹 + 数据指纹（排「画布在变」时用）。
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
// 产物：`scripts/probes/out-b7a2-<tag>.json`（逐图摘要 + 像素校验和 + 合并后的 option）
//       `scripts/probes/out-b7a2-<tag>.png`（整页截图，给人看的那份）
//
// ── 本笔要证的两件事，以及为什么这么证 ──────────────────────────────────────────────
// (A) **观感零漂移**：3a.2 把「整站一个样」的样式键从每个组件搬进主题，本意是**一个像素都不动**。
//     ⇒ 判据不是「我觉得没变」，是**同一张页面、同一份假流，两次运行的画布逐字节相同**。
//     ⚠️ 为此**不比 PNG**（两张 PNG 还夹着压缩/元数据差异），而是**在页面里直接读 canvas 像素**、
//     对整块 RGBA 算一个 FNV-1a 校验和（`ink` = 不透明像素数，顺带当「真画了没有」的判据）。
//     校验和相等 ⇒ 画布逐字节相同，比「看起来一样」强。
// (B) **主题不是装饰性代码**：主题只在 option 没写到的键上生效 ⇒ 必须证明那些键**真的进了
//     合并结果**。`window.__echarts.getInstanceByDom(el).getOption()` 给出**合并后**的 option
//     （option > 主题 > 内置默认 三级合并的结果）。这一步也顺带证了**深合并**真的发生
//     （指标页的 y 轴 `formatter` 来自 option、`axisLabel.color` 来自主题，两边都在才算过）。
//     ⚠️ **改前那棵树没有这个出口**（`window.__echarts` 是 3a.2 才加的）⇒ before 跑出来的
//     `options` 里全是 `null`，这是**预期内的**：那一侧只需要 (A)。
//
// ── 假流为什么是「真 HTTP 服务」而不是 CDP 拦请求（本笔踩过的坑，别再改回去）──────────
// 起先照 `probe-b7c6-metrics-panel.mjs` 用 `Fetch.fulfillRequest` 一次性喂完三拍 —— 但那个
// 响应体**必然结束**，而 `openMetricsStream` 是**带自动重连**的一层（`onEnd` → `onLost` → 重连）
// ⇒ 页面**每 ~500ms 重发一次 `POST …/metrics/stream`**，每重连一次就把三拍**再叠一遍**。
// 实测（2026-10-16）：折线数据 15→18→21 点、热力图 20→24→28 格，画布永远在变 ⇒ 拿不到
// 「稳定态」，两棵树的对比前提直接不成立。⇒ 改成本文件自己起一台 8088，把流**开着不关**：
// 数据停在第 3 拍，页面不再重连，画布才会收敛。（这跟真后端的行为也更像。）
//
// ── 已知边界（别把本探针读成更强的话）────────────────────────────────────────────────
//   · 只覆盖**指标页**（折线 ×2 / 热力图 / 柱状）。`series.type: 'pie'`（`PieChart` /
//     `DonutChart`）在指标页**没有调用点** ⇒ 那一档的键级等价**未被本探针覆盖**。
//   · 帧是假的，形状照 `probe-b7c6-metrics-panel.mjs` 的真帧抄 —— 两遍用**同一份**假流，
//     故 (A) 的对比不受影响。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import { createServer } from 'node:http'
import { writeFileSync } from 'node:fs'

const TAG = (process.argv[2] || 'after').replace(/[^a-z0-9_-]/gi, '')
const BASE = 'http://localhost:8080'
// ⚠️ **入口要显式选**：本包是两个 HTML 入口共用一台 dev server（`vue.config.js:6`），
//    `admin.html` 才把 `data-theme="admin"` 写在 `<html>` 上 ⇒ **管理端是暗色那套 token**，
//    `index.html` 拿到的是浅色 `:root`。而 3a 这事全挂在「现读 CSS 变量」上
//    ⇒ 两套 token 都得验（同一个探针跑两遍：`ENTRY=index.html` / `ENTRY=admin.html`）。
const ENTRY = process.env.ENTRY || 'admin.html'
const ROUTE = '#/admin/observe/metrics'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
/** 产物名前缀：**tag + 入口**（两入口的产物不许互相覆盖，见上面 ENTRY 那条注释）。 */
const FT = `${TAG}-${ENTRY.replace(/\.html$/, '')}`

// ── 假流：帧形状照 `probe-b7c6-metrics-panel.mjs`（`:48-110`）的真帧抄，只留三拍 ──────────
const PROT = [
  ['cacheHit', '缓存命中'], ['lockTimeout', '锁等待超时'], ['orderRejected', '订单流转被拒'],
  ['stockInsufficient', '库存不足（防超卖）'], ['auditFailed', '审计写入失败'],
  ['notifyFailed', '通知发送失败'], ['sensitiveHit', '敏感词拦下'], ['identityRejected', '身份拒绝'],
]
const sku = (s, good, axis, stock, baseline, ratio) => ({ sku: s, goodName: good, axisName: axis, stock, baseline, ratio })
const frame = (n, o) => ({
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
    ],
  },
})

const OK = { level: 'ok', label: '健康', reasons: ['L1 命中率 60.0%'] }
const STOCK = [
  sku('asdsad', 'oppo12', '珍珠白 / S', 90, 100, 0.9),
  sku('sadasd', 'oppo12', '珍珠白 / M', 10, 100, 0.1),
  sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null),
]
const FRAMES = [
  frame(0, { health: { level: 'ok', label: '健康', reasons: [] }, count: 0, qps: 0.0 }),
  frame(1, { health: OK, count: 40, qps: 20.0, p99: 15.0, meanMs: 2.1, hit: 24, miss: 16, hitRate: 0.6,
    locks: [['goods_sku', 0], ['order_no', 0]], prot: [5, 0, 1, 0, 0, 0, 2, 0], stock: STOCK }),
  frame(2, { health: OK, count: 25, qps: 12.5, e5: 3, e4: 1, errorRate: 0.012, clientErrorRate: 0.004,
    meanMs: 3.4, p95: 12.0, p99: 21.5, max: 60.0, hit: 10, miss: 10, hitRate: 0.6,
    locks: [['goods_sku', 2], ['order_no', 0]], prot: [1, 2, 3, 0, 0, 0, 0, 0], stock: STOCK }),
]

// ⚠️ 白名单 origin 逐字 = dev 那两个（`http://localhost:8080`）。其余 origin 不发 CORS 头
//    —— 真后端的口径就是这样（被拒的 origin 是**当场 403 + 零 `Access-Control-*`**）。
const corsFor = (origin) => (origin === 'http://localhost:8080' || origin === 'http://127.0.0.1:8080'
  ? {
      'Access-Control-Allow-Origin': origin,
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    }
  : {})

const srv = createServer((req, res) => {
  const cors = corsFor(req.headers.origin)
  if (process.env.TRACE) console.log(`  ⇢ ${req.method} ${req.url}  origin=${req.headers.origin || '-'}`)
  if (req.method === 'OPTIONS') {
    res.writeHead(204, cors)
    res.end()
    return
  }
  if (req.url.includes('/metrics/stream')) {
    // ⚠️ **开着不关**（不调 `res.end()`）：见文件头「假流为什么是真 HTTP 服务」。
    res.writeHead(200, {
      ...cors,
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    res.flushHeaders()
    ;(async () => {
      for (const f of FRAMES) {
        res.write('data: ' + JSON.stringify(f) + '\n\n')
        await sleep(400)
      }
    })().catch(() => {})
    return
  }
  res.writeHead(200, { ...cors, 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ success: true, list: [], total: 0, limit: 20, offset: 0 }))
})
await new Promise((r) => srv.listen(8088, r))

// ── 页内取样：每块画布的像素校验和 + 逐图 getOption ─────────────────────────────────────
// ⚠️ 校验和用 FNV-1a 逐字节累进（不是求和）：求和会把「两条线互换颜色」这种差异抹平。
// ⚠️ 取画布**从 ECharts 自己的实例根节点出发**（`[_echarts_instance_]` 是 `echarts.init` 挂上去的
//    属性），不是从 `.bc-canvas canvas` 摸：实测（2026-10-16）ECharts 5.6 会在我们给的根 div 里
//    **再套一层无 class 的容器**装 canvas ⇒ `canvas.parentElement` 不是 init 的那个元素
//    （`getInstanceByDom` 于是一律返回 null、`options` 全成 null）。
// ⚠️ 本串是**模板字符串**：注释里不许出现反引号或 `$` + `{` —— 会把模板提前闭合（本仓已踩过）。
const SAMPLE = `(() => {
  const roots = [...document.querySelectorAll('[_echarts_instance_]')]
  const canvases = roots.map((r) => r.querySelector('canvas')).filter(Boolean)
  const fnv = (d) => {
    let h = 2166136261
    for (let i = 0; i < d.length; i++) { h ^= d[i]; h = Math.imul(h, 16777619) }
    return h >>> 0
  }
  const charts = canvases.map((c) => {
    let ink = 0, hash = null
    try {
      const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
      hash = fnv(d)
      for (let i = 3; i < d.length; i += 4) if (d[i] > 24) ink++
    } catch (e) { hash = 'ERR:' + e.message }
    return { w: c.width, h: c.height, ink, hash }
  })

  // 合并后的 option（改前那棵树没有 window.__echarts ⇒ 逐项为 null，见文件头 (B)）
  let options = null
  if (window.__echarts) {
    options = roots.map((r) => {
      const inst = window.__echarts.getInstanceByDom(r)
      if (!inst) return null
      const o = inst.getOption()
      // ⚠️ 不整个 dump：series[].data 又大又与主题无关，会把 diff 淹掉；只留判据要用的键。
      const pick = (s) => ({
        type: s.type, name: s.name,
        itemStyle: s.itemStyle, lineStyle: s.lineStyle, label: s.label, labelLine: s.labelLine,
        emphasis: s.emphasis, symbol: s.symbol, symbolSize: s.symbolSize,
        // 数据留个指纹：画布变了要能一眼分清是「数据变了」还是「动画没停」。
        dataLen: Array.isArray(s.data) ? s.data.length : null,
        dataHash: fnv(new TextEncoder().encode(JSON.stringify(s.data || null))),
      })
      return {
        color: o.color, textStyle: o.textStyle, backgroundColor: o.backgroundColor,
        legend: (o.legend || []).map((l) => ({ show: l.show, bottom: l.bottom, left: l.left, icon: l.icon,
          itemWidth: l.itemWidth, itemHeight: l.itemHeight, itemGap: l.itemGap, textStyle: l.textStyle })),
        tooltip: (o.tooltip || []).map((t) => ({ trigger: t.trigger, backgroundColor: t.backgroundColor,
          borderColor: t.borderColor, borderWidth: t.borderWidth, padding: t.padding, textStyle: t.textStyle,
          axisPointer: t.axisPointer && { type: t.axisPointer.type, lineStyle: t.axisPointer.lineStyle } })),
        xAxis: (o.xAxis || []).map((a) => ({ type: a.type, boundaryGap: a.boundaryGap, axisLine: a.axisLine,
          axisTick: a.axisTick, axisLabel: a.axisLabel, splitArea: a.splitArea })),
        yAxis: (o.yAxis || []).map((a) => ({ type: a.type, axisLine: a.axisLine, axisTick: a.axisTick,
          axisLabel: a.axisLabel, splitLine: a.splitLine && { show: a.splitLine.show, lineStyle: a.splitLine.lineStyle } })),
        visualMap: (o.visualMap || []).map((v) => ({ textStyle: v.textStyle, inRange: v.inRange,
          min: v.min, max: v.max, calculable: v.calculable, orient: v.orient })),
        series: (o.series || []).map(pick),
      }
    })
  }
  // 诊断（很小，出问题时省一轮排查）：钩子在不在、实例根节点认出来几个、根节点的 class
  // （用来确认取的确实是 .bc-canvas 那四块，而不是别的什么东西）。
  const diag = {
    hook: typeof window.__echarts,
    attrNodes: roots.length,
    rootClasses: roots.map((r) => r.className),
    fetched: options ? options.filter(Boolean).length : 0,
  }
  return { canvases: charts, options, diag }
})()`

const b = await open({ w: 1440, h: 1000 })
await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => { try {
    sessionStorage.setItem('loginUsername', 'ops_duan')
    sessionStorage.setItem('customerid', 'qa7PmpUojO')
    sessionStorage.setItem('role', '3')
  } catch (e) {} })()`,
})

try {
  await b.goto(`${BASE}/${ENTRY}${ROUTE}`, 2600)

  // ── 扰动钩子（只在排查时用）：`PERTURB=<页内表达式>`，表达式里 `inst` = 当前这块图的实例
  //    （`E` = `window.__echarts`）。**画布变了别猜**：把可疑的那个键**回填/换掉**再取一次，
  //    看画布是不是就变成了另一侧的哈希 —— 是则机制钉住，不是则假设作废。
  //    ⚠️ 只在**改后那棵树**可用（改前没有 `window.__echarts`）。
  if (process.env.PERTURB) {
    const n = await b.evalJS(`(() => {
      const E = window.__echarts
      if (!E) return 0
      let n = 0
      document.querySelectorAll('[_echarts_instance_]').forEach((r) => {
        const inst = E.getInstanceByDom(r)
        if (!inst) return
        n++
        try { ${process.env.PERTURB} } catch (e) { console.warn('扰动失败：' + e.message) }
      })
      return n
    })()`)
    console.log(`  [扰动] 已对 ${n} 块图施加：${process.env.PERTURB}`)
    await sleep(900)
  }

  // ⚠️ **不能靠固定 sleep 等动画收敛**（踩过，见文件头）。改成**轮询到连续两次取样逐字节相同**
  //    才收 —— 这才是「稳定态」的可操作定义。若一直不稳，明确报「没收敛」，产物标记不可用。
  let prev = null
  let s = null
  let rounds = 0
  for (; rounds < 12; rounds++) {
    s = await b.evalJS(SAMPLE)
    const now = JSON.stringify(s.canvases)
    if (process.env.TRACE) {
      const fp = (s.options || [])
        .map((o) => (o && o.series ? o.series.map((x) => `${x.type}#${x.dataLen}@${x.dataHash}`).join(',') : '-'))
        .join(' | ')
      console.log(`  [${rounds}] ` + s.canvases.map((c) => c.hash).join(' ') + `  数据：${fp}`)
    }
    if (prev === now) break
    prev = now
    await sleep(500)
  }
  const stable = rounds < 12

  await b.shot(`${EV}out-b7a2-${FT}.png`)

  // 逐图**裁切**另存一份：整页截图里那四块画布很小，肉眼比对时看不出「哪块变了」；
  // 裁到单块之后，「改前/改后」两张可以并排看（探针报的 hash 不一致时，这就是定位手段）。
  const rects = await b.evalJS(`(() => [...document.querySelectorAll('[_echarts_instance_]')].map((r) => {
    const b = r.getBoundingClientRect()
    return { x: Math.floor(b.x), y: Math.floor(b.y + (window.scrollY || 0)), width: Math.ceil(b.width), height: Math.ceil(b.height) }
  }))()`)
  for (let i = 0; i < rects.length; i++) {
    const r = await b.send('Page.captureScreenshot', { format: 'png', clip: { ...rects[i], scale: 1 }, captureBeyondViewport: true })
    writeFileSync(`${EV}out-b7a2-${FT}-c${i}.png`, Buffer.from(r.data, 'base64'))
  }

  writeFileSync(`${EV}out-b7a2-${FT}.json`, JSON.stringify({ tag: TAG, entry: ENTRY, stable, ...s }, null, 1), 'utf8')

  console.log(`\n── ${TAG} · ${ENTRY} ──`)
  console.log(`收敛：${stable ? `是（第 ${rounds + 1} 次取样与上一次逐字节相同）` : `⚠️ 否（轮询满 ${rounds} 次仍在变 ⇒ 本份产物不可用于比对）`}`)
  console.log(`画布 ${s.canvases.length} 块：`)
  s.canvases.forEach((c, i) => console.log(`  [${i}] ${c.w}x${c.h}  ink=${c.ink}  hash=${c.hash}`))
  console.log(s.options
    ? `合并后的 option 已取样（${s.diag.fetched}/${s.canvases.length} 张）`
    : '⚠️ 页内没有 window.__echarts ⇒ 未取 option（改前那棵树的**预期**行为，见文件头 (B)）')
  console.log('诊断：' + JSON.stringify(s.diag))
  console.log(`产物：scripts/probes/out-b7a2-${FT}.json / .png / -c0..c${rects.length - 1}.png`)
} finally {
  srv.closeAllConnections?.()
  srv.close()
  await b.close()
}
