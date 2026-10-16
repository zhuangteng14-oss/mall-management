// probe-b7a2b-preview-shots.mjs —— 〈指标页两版对比〉的**核对与出图**（3a.2b，2026-10-16）。
//
// 它回答两个问题，并留下给人看的图：
//   (A) **甲版预览 ≡ 真页面？** —— 同一条假流喂真页面、也喂预览页，然后比**同一堆判据**：
//       内容高（布局）、画布数、每块画布的 FNV-1a 像素校验和。甲版预览若真是「快照原样」，
//       这四项应当**逐字节相同**；不同的项**必须**能解释（本探针**同时**报
//       `document.fonts.check(...)`，别把字体差异误读成「快照不可信」）。
//       ⚠️ 我原先**猜**差异会来自 `file://` 下自托管 woff2 被 CORS 拒载 —— **实测推翻**：
//          `file://` 下 Noto/mono 两份都 check 为 true。所以本探针不预设原因，
//          只给「是/否 + 数值」；**在哪**由 `probe-b7a2b-heat-diff.mjs` 从原始像素里定位。
//   (B) **乙版真的只差了那四维？** —— 甲 vs 乙逐块画布比：**该变的变、该不变的没变**。
//       四块图里只有「网格/tooltip/拐点」（维度 6）与「动画时长」（维度 7）会改画布；
//       折线/柱/热的**数据与几何**不该动。
//
// 跑法：`node scripts/probes/probe-b7a2b-preview-shots.mjs`
//       （真页面那一侧要 8080 ⇒ 得先 `npm run serve`；预览页那侧是 `file://`，不需要）
//       环境变量：`DUMP=<画布序号>` 额外导出该画布原始 RGBA + 底部 30px 的 4× 放大条
// 产物：`scripts/probes/out-b7a2b-<谁>-{full,top}.png` + 五张 `-crop-*.png` + `out-b7a2b-shots.json`
//       控制台打比对表。(A)/(B) 的三处用法：`real` = 真页面、`jia` = 甲版预览 `file://`、
//       `jia-http` = 甲版预览走 http 供（用来把「字体加载路径」这一项单独拎出来看）、`yi` = 乙版。
// 环境：`DUMP=` 的原始 RGBA 落 `_work/3a2b/raw-dumps/`（**不**进仓，见下面的 DUMP_DIR 注释），
//       从它派生的逐行直方图才落 `scripts/probes/out-b7a2b-heat-diff.txt`。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import { createServer } from 'node:http'
import { writeFileSync } from 'node:fs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/'
/* ⚠️ `DUMP=` 模式的原始 RGBA 落这儿，**不是** EV —— 一个 canvas 的裸像素就是 1.5MB 的
   base64，而 `scripts/probes/` 是**被跟踪的**目录（`out-*.png` 一直都在提交里）⇒ 落在 EV 会
   让一笔提交凭空多出 4.5MB 的「不可读字节」，而且它只是**中间物**（结论由
   `probe-b7a2b-heat-diff.mjs` 派生成 3KB 的逐行直方图）。`_work/` 在 .gitignore 里。 */
const DUMP_DIR = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a2b/raw-dumps/'
const PREV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/c3-admin-preview/'
const fileUrl = (n) => 'file:///' + encodeURI(PREV + n)

// ── 假流（照抄 `probe-b7a2-echarts-theme.mjs` 的两条口径：**流不关** + origin 白名单）──────
const PROT = [
  ['cacheHit', '缓存命中'], ['lockTimeout', '锁等待超时'], ['orderRejected', '订单流转被拒'],
  ['stockInsufficient', '库存不足（防超卖）'], ['auditFailed', '审计写入失败'],
  ['notifyFailed', '通知发送失败'], ['sensitiveHit', '敏感词拦下'], ['identityRejected', '身份拒绝'],
]
const sku = (s, good, axis, stock, baseline, ratio) => ({ sku: s, goodName: good, axisName: axis, stock, baseline, ratio })
const frame = (n, o) => ({
  seq: n, ts: 1791967503401 + n * 1000, intervalMs: n === 0 ? 0 : 1000, health: o.health,
  requests: {
    count: o.count, qps: o.qps, errors5xx: o.e5 ?? 0, errors4xx: o.e4 ?? 0,
    errorRate: o.errorRate ?? null, clientErrorRate: o.clientErrorRate ?? null,
    meanMs: o.meanMs ?? null, p95Ms: o.p95 ?? 0.0, p99Ms: o.p99 ?? 0.0, maxMs: o.max ?? 0.0,
  },
  cache: { hit: o.hit ?? 0, miss: o.miss ?? 0, hitRate: o.hitRate ?? null, l2Degraded: 0 },
  gauges: { l2Up: 1.0, lockUp: o.lockUp ?? 1.0 },
  locks: (o.locks || []).map(([lock, timeouts]) => ({ lock, acquires: 40, acquired: 38, timeouts, degraded: 0, errors: 0, waitMeanMs: 1.5 })),
  protections: PROT.map(([tag, label], i) => ({ tag, label, value: (o.prot || [])[i] || 0 })),
  stock: { available: true, rows: o.stock || [sku('asdsad', 'oppo12', '珍珠白 / S', 100, null, null)] },
})
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
    hit: 10 + i, miss: 6 + i, hitRate: 0.6, locks, prot,
    stock: [
      sku('asdsad', 'oppo12', '珍珠白 / S', 90 - i, 100, 0.9),
      sku('sadasd', 'oppo12', '珍珠白 / M', 10 + i, 100, 0.1),
      sku('dddddd', 'oppo12', '珍珠白 / L', 100, null, null),
    ],
  }))
}
const corsFor = (origin) => (origin === 'http://localhost:8080' || origin === 'http://127.0.0.1:8080'
  ? {
      'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    }
  : {})
// 静态面：把预览页也**用 http 供一份**（`/pv/…`），用来做那条「字体走 http vs 走 file://」的对照实验。
// ⚠️ 这条实验是为**定位**服务的：甲版预览在 file:// 下与真页面有 255 个像素的差（全在底部文字带），
//    若同一份预览页改走 http 后与真页面逐字节相同 ⇒ 机制就钉在**字体的加载路径**上（栅格化差异），
//    而不是「快照失真」。别把它读成「http 才是正确姿势」——用户双击打开走的就是 file://。
const ROOT_DIR = 'C:/Users/86159/Desktop/软件工程/期末项目'
const MIME = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json', '.woff2': 'font/woff2', '.png': 'image/png', '.svg': 'image/svg+xml' }
const serveStatic = async (req, res) => {
  const fs = await import('node:fs')
  const path = await import('node:path')
  const u = decodeURIComponent(new URL(req.url, 'http://x').pathname)
  const rel = u.startsWith('/pv/') ? `docs/美化/c3-admin-preview/${u.slice(4)}` : u.slice(1)
  const file = path.resolve(ROOT_DIR, rel)
  // ⚠️ Windows 上 `path.resolve` 回的是反斜杠路径 ⇒ 比较前两边都归一成 `/`，
  //    否则这条守卫会把**每一个**请求都判成越界（本探针第一版就栽在这：整页 403 空白）。
  if (!file.replace(/\\/g, '/').startsWith(ROOT_DIR.replace(/\\/g, '/'))) { res.writeHead(403); res.end(); return }
  try {
    const buf = fs.readFileSync(file)
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Access-Control-Allow-Origin': '*' })
    res.end(buf)
  } catch (e) { res.writeHead(404); res.end('404 ' + rel) }
}

const srv = createServer((req, res) => {
  const cors = corsFor(req.headers.origin)
  if (req.method === 'OPTIONS') { res.writeHead(204, cors); res.end(); return }
  if (req.url.includes('/metrics/stream')) {
    res.writeHead(200, { ...cors, 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
    res.flushHeaders()
    ;(async () => { for (const f of FRAMES) { res.write('data: ' + JSON.stringify(f) + '\n\n'); await sleep(400) } })().catch(() => {})
    return
  }
  if (req.url.startsWith('/pv/') || req.url.startsWith('/Mall-management-web/')) { serveStatic(req, res); return }
  res.writeHead(200, { ...cors, 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ success: true, list: [], total: 0, limit: 20, offset: 0 }))
})
await new Promise((r) => srv.listen(8088, r))

// ⚠️ 模板字符串：注释里不许出现反引号或 `$` + `{`（本仓已踩过两次）。
const SAMPLE = `(() => {
  const fnv = (d) => { let h = 2166136261; for (let i = 0; i < d.length; i++) { h ^= d[i]; h = Math.imul(h, 16777619) } return h >>> 0 }
  const cs = [...document.querySelectorAll('[_echarts_instance_]')].map((r) => {
    const c = r.querySelector('canvas')
    if (!c) return { w: 0, h: 0, ink: 0, hash: 'nocanvas' }
    try {
      const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
      let ink = 0; for (let i = 3; i < d.length; i += 4) if (d[i] > 24) ink++
      return { w: c.width, h: c.height, ink, hash: fnv(d) }
    } catch (e) { return { w: 0, h: 0, ink: 0, hash: 'ERR:' + e.message } }
  })
  const q = (s) => { const e = document.querySelector(s); return e ? Math.round(e.getBoundingClientRect().width) : null }
  return {
    canvases: cs,
    scrollH: document.documentElement.scrollHeight,
    kpiW: [...document.querySelectorAll('.kpi')].map((e) => Math.round(e.getBoundingClientRect().width)),
    kpiH: [...document.querySelectorAll('.kpi')].map((e) => Math.round(e.getBoundingClientRect().height)),
    fonts: { noto: document.fonts.check('16px "Noto Sans SC Variable"'), mono: document.fonts.check('16px "JetBrains Mono Variable"') },
    err: window.__pvErr || null,
    probe: q('.a-mp'),
  }
})()`

const who = [
  ['real', 'http://localhost:8080/admin.html#/admin/observe/metrics'],
  ['jia', fileUrl('指标页-甲版.html')],
  ['jia-http', 'http://127.0.0.1:8088/pv/' + encodeURI('指标页-甲版.html')],
  ['yi', fileUrl('指标页-乙版.html')],
]

const out = {}
const b = await open({ w: 1440, h: 1000 })
await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `(() => { window.__pvErr = null
    window.addEventListener('error', (e) => { window.__pvErr = String(e.message || e) })
    try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
})

try {
  for (const [name, url] of who) {
    await b.goto(url, name === 'real' ? 3000 : 2200)
    // 稳定态：连续两次取样逐字节相同（与 3a.2 探针同口径）
    let prev = null
    let s = null
    let rounds = 0
    for (; rounds < 16; rounds++) {
      s = await b.evalJS(SAMPLE)
      const now = JSON.stringify(s.canvases)
      if (prev === now && s.canvases.length) break
      prev = now
      await sleep(500)
    }
    out[name] = { ...s, stable: rounds < 16, rounds }
    // ── 定位用（`DUMP=<画布号>`）：把那一块画布的**原始 RGBA**整块导出，供逐像素比对 ──
    //    用途：甲版与真页面若有画布哈希不等，**别猜机制**，先把差在哪两个像素找出来
    //    （3a.2 的教训：扰动实验能证伪假设，逐像素 diff 能定位事实）。
    if (process.env.DUMP !== undefined && name !== 'yi') {
      const idx = Number(process.env.DUMP)
      const b64 = await b.evalJS(`(() => {
        const cs = [...document.querySelectorAll('[_echarts_instance_]')].map((r) => r.querySelector('canvas'))
        const c = cs[${idx}]
        if (!c) return null
        const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
        let out = '', step = 8192
        for (let i = 0; i < d.length; i += step) out += String.fromCharCode.apply(null, d.subarray(i, i + step))
        return c.width + 'x' + c.height + ':' + btoa(out)
      })()`)
      writeFileSync(`${DUMP_DIR}out-b7a2b-dump-${name}.txt`, String(b64), 'utf8')
      console.log(`  [${name}] 画布[${idx}] 原始 RGBA 已导出（${String(b64).length} 字符 base64）`)
    }
    await b.shotFull(`${EV}out-b7a2b-${name}-full.png`)
    // ── 定位用（放大条）：热力图（画布[2]）**底部 30 像素**放大 4 倍另存 ──
    //    用途：甲/乙或预览/真页面画布不等时，肉眼直接看「差在哪一行字」。
    if (process.env.DUMP !== undefined) {
      const r = await b.evalJS(`(() => {
        const cs = [...document.querySelectorAll('[_echarts_instance_]')].map((x) => x.querySelector('canvas'))
        const c = cs[2]; if (!c) return null
        const b2 = c.getBoundingClientRect()
        return { x: Math.floor(b2.x), y: Math.floor(b2.y + (window.scrollY || 0) + b2.height - 30), width: Math.ceil(b2.width), height: 30 }
      })()`)
      if (r) {
        const sh = await b.send('Page.captureScreenshot', { format: 'png', clip: { ...r, scale: 4 }, captureBeyondViewport: true })
        writeFileSync(`${EV}out-b7a2b-${name}-hstrip.png`, Buffer.from(sh.data, 'base64'))
      }
    }
    await b.goto(url, name === 'real' ? 3000 : 2200)          // 回到第一屏（shotFull 会改视口）
    s = await b.evalJS(SAMPLE)
    await b.shot(`${EV}out-b7a2b-${name}-top.png`)
    // ── 对照截图：把「KPI 卡行 + 四张图卡」逐块裁下来（2 倍），供并排比对 ──
    if (name !== 'jia-http') {
      const rects = await b.evalJS(`(() => {
        const rect = (e) => { const r = e.getBoundingClientRect(); return { x: Math.floor(r.x), y: Math.floor(r.y + (window.scrollY || 0)), width: Math.ceil(r.width), height: Math.ceil(r.height) } }
        const k = document.querySelector('.a-kpis')
        return { kpi: k ? rect(k) : null, cards: [...document.querySelectorAll('.a-card')].map(rect) }
      })()`)
      const regions = [['kpi', rects.kpi], ['qps', rects.cards[0]], ['hit', rects.cards[1]], ['heat', rects.cards[2]], ['bar', rects.cards[3]]]
      for (const [tag, r] of regions) {
        if (!r) continue
        const sh = await b.send('Page.captureScreenshot', { format: 'png', clip: { ...r, scale: 2 }, captureBeyondViewport: true })
        writeFileSync(`${EV}out-b7a2b-${name}-crop-${tag}.png`, Buffer.from(sh.data, 'base64'))
      }
    }
    console.log(`  [${name}] 收敛=${rounds < 16 ? '是' : '否'} 画布=${s.canvases.length} 内容高=${s.scrollH} 字体 noto=${s.fonts.noto} mono=${s.fonts.mono}${s.err ? ' ⚠️ JS 错：' + s.err : ''}`)
  }

  writeFileSync(`${EV}out-b7a2b-shots.json`, JSON.stringify(out, null, 1), 'utf8')

  // ── 比对表 ───────────────────────────────────────────────────────────────────────
  const eq = (a, b2) => (a === b2 ? '相同' : `不同（${a} vs ${b2}）`)
  console.log('\n── (A) 甲版预览 vs 真页面 ──')
  console.log(`内容高      ${eq(out.jia.scrollH, out.real.scrollH)}`)
  console.log(`.a-mp 宽    ${eq(out.jia.probe, out.real.probe)}`)
  console.log(`KPI 卡宽×高 ${JSON.stringify(out.jia.kpiW)}x${JSON.stringify(out.jia.kpiH)} vs ${JSON.stringify(out.real.kpiW)}x${JSON.stringify(out.real.kpiH)}`)
  const n = Math.min(out.jia.canvases.length, out.real.canvases.length)
  for (let i = 0; i < n; i++) {
    console.log(`画布[${i}] 尺寸 ${eq(out.jia.canvases[i].w + 'x' + out.jia.canvases[i].h, out.real.canvases[i].w + 'x' + out.real.canvases[i].h)} · ink ${eq(out.jia.canvases[i].ink, out.real.canvases[i].ink)} · hash ${eq(out.jia.canvases[i].hash, out.real.canvases[i].hash)}`)
  }
  console.log('\n── (A2) 甲版预览（走 http 供）vs 真页面 —— 用来定位「字体加载路径」这一项 ──')
  console.log(`内容高      ${eq(out['jia-http'].scrollH, out.real.scrollH)}`)
  for (let i = 0; i < n; i++) {
    console.log(`画布[${i}] ink ${eq(out['jia-http'].canvases[i].ink, out.real.canvases[i].ink)} · hash ${eq(out['jia-http'].canvases[i].hash, out.real.canvases[i].hash)}`)
  }

  console.log('\n── (B) 乙版 vs 甲版 ──')
  console.log(`内容高      ${eq(out.yi.scrollH, out.jia.scrollH)}`)
  console.log(`KPI 卡宽×高 ${JSON.stringify(out.yi.kpiW)}x${JSON.stringify(out.yi.kpiH)} vs ${JSON.stringify(out.jia.kpiW)}x${JSON.stringify(out.jia.kpiH)}`)
  for (let i = 0; i < Math.min(out.yi.canvases.length, out.jia.canvases.length); i++) {
    console.log(`画布[${i}] ink ${eq(out.yi.canvases[i].ink, out.jia.canvases[i].ink)} · hash ${eq(out.yi.canvases[i].hash, out.jia.canvases[i].hash)}`)
  }
  console.log(`\n产物：scripts/probes/out-b7a2b-{real,jia,yi}-{full,top}.png + out-b7a2b-shots.json`)
} finally {
  srv.closeAllConnections?.()
  srv.close()
  await b.close()
}
