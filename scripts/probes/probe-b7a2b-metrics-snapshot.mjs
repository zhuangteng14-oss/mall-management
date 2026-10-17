// probe-b7a2b-metrics-snapshot.mjs —— 〈指标页两版对比〉的**快照生成器**（3a.2b，2026-10-16）。
//
// 它不判「对错」，它**取一份真页面的稳定态**：整个 `#app` 的 DOM + 页面里全部 `<style>` 的文本
// + 每块图**合并后**的 option。有了这三样，就能离线拼出两份**双击可开**的静态预览页：
//   甲版 = 快照原样            ⇒ 预览页 ≡ 真页面（**这条是可验的**：两边同法截图并排比）
//   乙版 = 快照 + 维度 3/4/6/7 的差量（差量单独成块，直接当**落地规格**读）
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7a2b-metrics-snapshot.mjs`
//        `OUT=<路径>` 换产物落点（默认 `_work/3a2b/snapshot.json`）。
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
//
// ── 为什么还要那台假 8088（照抄 `probe-b7a2-echarts-theme.mjs` 的结论，别再改回 Fetch 拦）──
// `openMetricsStream` 带**自动重连**：一次性喂完的假响应会让页面每 ~500ms 重连并**再叠一遍**
// 数据 ⇒ 画布永远在变、没有「稳定态」可快照。⇒ 自起一台 8088 把流**开着不关**（不调 res.end）。
//
// ── 快照里刻意**不含**的东西（免得被当成遗漏）────────────────────────────────────────
//   · canvas 像素：`outerHTML` 序列化不出画布内容 ⇒ 预览页按 option **重新 init 渲染**。
//     ⇒ 甲版预览页与真页面「长得一样」是**重新渲染的结果**，不是像素搬运（故本探针另附一条
//     同法截图并排比的核对步骤，见 `probe-b7a2b-preview-shots.mjs`）。
//   · 事件/接口：预览页是**静态**的（按钮点不动），这是刻意的 —— 它用来比**视觉**，不是比交互。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import { createServer } from 'node:http'
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const BASE = 'http://localhost:8080'
const ENTRY = 'admin.html'
const ROUTE = '#/admin/observe/metrics'
const OUT = process.env.OUT || 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a2b/snapshot.json'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'

// ── 假流：帧形状照 `probe-b7a2-echarts-theme.mjs`（同一份，两笔的对比前提一致）────────────
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
  stock: { available: true, rows: o.stock || [sku('asdsad', 'oppo12', '珍珠白 / S', 100, null, null)] },
})

// ⚠️ 与 3a.2 探针的差异：多给几拍（快照要落到**折线有 6 个点、热力图有 6 列**的形态上，
//    否则预览页里三条时间轴都是「一个点」，看不出网格/轴的形态）。
const wave = (i) => [
  [['goods_sku', i % 3 === 0 ? 2 : 0], ['order_no', 0], ['cart_item', i % 4 === 0 ? 1 : 0]],
  [5 + (i % 3), i % 2, 1, 0, 0, 0, 2, 0],
]
const FRAMES = [frame(0, { health: { level: 'ok', label: '健康', reasons: [] }, count: 0, qps: 0.0 })]
for (let i = 1; i <= 6; i++) {
  const [locks, prot] = wave(i)
  FRAMES.push(frame(i, {
    health: { level: 'ok', label: '健康', reasons: ['L1 命中率 60.0%'] },
    count: 14 + i * 3, qps: 14.0 + i * 1.5,
    e5: i % 3 === 0 ? 3 : 0, e4: i === 4 ? 1 : 0,
    errorRate: i % 3 === 0 ? 0.012 : 0, clientErrorRate: i === 4 ? 0.004 : 0,
    meanMs: 2.1 + i * 0.2, p95: 9 + i, p99: 15 + i * 1.5, max: 40 + i * 3,
    hit: 10 + i, miss: 6 + i, hitRate: 0.6,
    locks, prot,
    stock: [
      sku('asdsad', 'oppo12', '珍珠白 / S', 90 - i, 100, 0.9),
      sku('sadasd', 'oppo12', '珍珠白 / M', 10 + i, 100, 0.1),
      sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null),
    ],
  }))
}

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
  if (req.method === 'OPTIONS') { res.writeHead(204, cors); res.end(); return }
  if (req.url.includes('/metrics/stream')) {
    // ⚠️ **开着不关**（不调 res.end）：见文件头「为什么还要那台假 8088」。
    res.writeHead(200, { ...cors, 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
    res.flushHeaders()
    ;(async () => {
      for (const f of FRAMES) { res.write('data: ' + JSON.stringify(f) + '\n\n'); await sleep(400) }
      if (process.env.TRACE) console.log(`  ⇢ 假流已推完 ${FRAMES.length} 拍（连接保持，不关）`)
    })().catch(() => {})
    return
  }
  res.writeHead(200, { ...cors, 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ success: true, list: [], total: 0, limit: 20, offset: 0 }))
})
await new Promise((r) => srv.listen(8088, r))

// ── 收敛判据（与 3a.2 探针同口径）：连续两次取样的画布指纹逐字节相同 ────────────────────
// ⚠️ 本串是**模板字符串**：注释里不许出现反引号或 `$` + `{`（会把模板提前闭合，本仓已踩过）。
const FINGERPRINT = `(() => {
  const fnv = (d) => { let h = 2166136261; for (let i = 0; i < d.length; i++) { h ^= d[i]; h = Math.imul(h, 16777619) } return h >>> 0 }
  return [...document.querySelectorAll('[_echarts_instance_]')].map((r) => {
    const c = r.querySelector('canvas')
    if (!c) return 'nocanvas'
    try { return fnv(c.getContext('2d').getImageData(0, 0, c.width, c.height).data) } catch (e) { return 'ERR' }
  }).join(' ')
})()`

// ── 取快照 ───────────────────────────────────────────────────────────────────────────
const DUMP = `(() => {
  const E = window.__echarts
  if (!E) return { error: 'no window.__echarts（dev-only 出口没挂上？）' }
  const roots = [...document.querySelectorAll('[_echarts_instance_]')]
  let droppedFns = 0
  const strip = (k, v) => { if (typeof v === 'function') { droppedFns++; return undefined } return v }
  const options = roots.map((r, i) => {
    r.setAttribute('data-pv', String(i))
    const inst = E.getInstanceByDom(r)
    return inst ? JSON.parse(JSON.stringify(inst.getOption(), strip)) : null
  })
  const app = document.querySelector('#app')
  const attr = (el) => [...el.attributes].map((a) => [a.name, a.value])
  return {
    droppedFns,
    title: document.title,
    htmlAttrs: attr(document.documentElement),
    bodyAttrs: attr(document.body),
    html: app ? app.outerHTML : document.body.innerHTML,
    styles: [...document.querySelectorAll('style')].map((s) => s.textContent).join('\\n'),
    links: [...document.querySelectorAll('link[rel=stylesheet]')].map((l) => l.getAttribute('href')),
    chartCount: roots.length,
    options,
    viewport: { w: window.innerWidth, h: window.innerHeight, scrollH: document.documentElement.scrollHeight },
    fontsLoaded: { noto: document.fonts.check('16px "Noto Sans SC Variable"'), mono: document.fonts.check('16px "JetBrains Mono Variable"') },
  }
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
  let prev = null
  let stable = false
  let rounds = 0
  for (; rounds < 16; rounds++) {
    const now = await b.evalJS(FINGERPRINT)
    if (process.env.TRACE) console.log(`  [${rounds}] ${now}`)
    if (prev === now && now && now !== '') { stable = true; break }
    prev = now
    await sleep(500)
  }

  const snap = await b.evalJS(DUMP)
  if (snap.error) throw new Error(snap.error)
  Object.assign(snap, { stable, rounds, frames: FRAMES.length, at: new Date().toISOString() })

  mkdirSync(dirname(OUT), { recursive: true })
  writeFileSync(OUT, JSON.stringify(snap), 'utf8')
  // 同时落一份「给人看的那一屏」（甲版的对照基准）
  await b.shot(`${EV}out-b7a2b-real.png`)

  console.log(`\n── 快照 · ${ENTRY}${ROUTE} ──`)
  console.log(`收敛：${stable ? `是（第 ${rounds + 1} 次取样与前一次逐字节相同）` : `⚠️ 否（轮询满 ${rounds} 次仍在变）`}`)
  console.log(`画布 ${snap.chartCount} 块 / 取到 option ${snap.options.filter(Boolean).length} 块`)
  console.log(`DOM ${snap.html.length} 字符 · 样式 ${snap.styles.length} 字符 · link ${snap.links.length} 个`)
  console.log(`视口 ${snap.viewport.w}x${snap.viewport.h} 内容高 ${snap.viewport.scrollH} · 丢掉的函数键 ${snap.droppedFns} 个`)
  console.log(`html attrs ${JSON.stringify(snap.htmlAttrs)} · body attrs ${JSON.stringify(snap.bodyAttrs)}`)
  console.log(`页面内字体：Noto=${snap.fontsLoaded.noto} mono=${snap.fontsLoaded.mono}`)
  console.log(`产物：${OUT} 与 ${EV}out-b7a2b-real.png`)
} finally {
  srv.closeAllConnections?.()
  srv.close()
  await b.close()
}
