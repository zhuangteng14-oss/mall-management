// probe-b7a3-eventlog-virtual.mjs —— ③ 日志流「窗口化渲染（虚拟滚动）」的专项探针。
//
// 目的（用户 2026-10-17 性能微调）：1000 条数据在内存里时，DOM 只应铺视口附近那几十行；
// 向上翻阅时窗口要跟着滚动位置移动，且不能劫持滚动 / 不能做平滑。
//
// 口径：
//   · 自接管 `Fetch` 拦截（同 probe-b7a3-eventlog-rewrite），只喂**一帧 1000 条**事件；
//   · 行数 > VIRTUAL_MIN（200）⇒ 窗口化生效；小数据量（主探针的 161 行）仍走全量渲染，
//     所以本文件与主探针**互不影响**；
//   · 帧率用 rAF 帧间隔测（DevTools 的 Frame Rendering Stats 是 UI 面板，CDP 无对应指令；
//     这里量的是同一个东西的代理量：向上滚动 2 秒内每次 rAF 的间隔分布）。
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080/admin.html'
const ROUTE = '#/admin/observe/logs'
const WORK = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a3/'

const R = report('块 7 · 3a.3 性能微调 · ③ 窗口化渲染（1000 条 · rAF 帧间隔代理量）')
let TOTAL = 0
const ok = (label, cond, got) => { TOTAL++; R.ok(label, cond, got) }

const T = Date.now() - 20000
const KINDS = ['cache', 'lock', 'order', 'stock', 'audit', 'notify', 'sensitive', 'identity', 'protection']
const EVENTS = Array.from({ length: 1000 }, (_, i) => ({
  seq: i + 1,
  ts: T + i,
  kind: KINDS[i % 9],
  severity: ['info', 'info', 'warn'][i % 3],
  title: `虚拟事件 ${i + 1}`,
  detail: `result=ok src=${KINDS[i % 9]}#${i}`,
  key: `key-${i}`,
  objectType: 'goods',
  objectId: `G-${i}`,
  traceId: null,
  batchId: null,
  actor: null,
  durationUs: 100 + (i % 900),
  remaining: null,
  fields: { result: 'ok' },
}))
const FRAME = {
  seq: 1, ts: T, cursor: 1000, count: 1000, rate: 10, mq: false,
  counts: {}, severity: {}, events: EVENTS, traces: [],
}
const SSE_BODY = `data: ${JSON.stringify(FRAME)}\n\n`
const CORS = [
  { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
  { name: 'Access-Control-Allow-Credentials', value: 'true' },
  { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
  { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
]

const b = await open({ w: 1440, h: 1000 })
try {
  await b.send('Fetch.enable', { patterns: [{ urlPattern: '*8088*' }] })
  b.ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.method !== 'Fetch.requestPaused') return
    const { requestId, request } = m.params
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
  })

  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      try {
        sessionStorage.setItem('loginUsername', 'probe-virtual')
        sessionStorage.setItem('customerid', 'probe-virtual')
        sessionStorage.setItem('role', '3')
      } catch (e) {}
      window.__errs = []
      window.__scrollOpts = []
      const oe = console.error.bind(console)
      console.error = (...a) => { try { window.__errs.push(a.map(String).join(' ')) } catch (e) {} ; oe(...a) }
      window.addEventListener('unhandledrejection', (e) => { try { window.__errs.push('unhandledrejection: ' + e.reason) } catch (x) {} })
      // 记录所有 scroll 监听器的注册参数 —— 用来判「被动监听」真的传下去了。
      const add = EventTarget.prototype.addEventListener
      EventTarget.prototype.addEventListener = function (type, fn, opts) {
        if (type === 'scroll') {
          try {
            window.__scrollOpts.push({
              id: (this && this.id) || '',
              passive: !!(opts && typeof opts === 'object' && opts.passive),
            })
          } catch (e) {}
        }
        return add.call(this, type, fn, opts)
      }
    })()`,
  })

  await b.goto(`${BASE}${ROUTE}`)
  await sleep(4500)

  /** 当前窗口的读数：DOM 行数、首末索引、上下占位、首末行文本。 */
  const readWin = () => b.evalJS(`(() => {
    const el = document.getElementById('ev-list')
    const lis = [...el.querySelectorAll('.log-line[data-vidx]')]
    const pads = [...el.querySelectorAll('.vpad')]
    const txt = (li) => ((li && (li.innerText || li.textContent)) || '').trim()
    return {
      总数: document.querySelectorAll('.log-line').length,
      窗口行数: lis.length,
      首个idx: lis.length ? +lis[0].dataset.vidx : null,
      末个idx: lis.length ? +lis[lis.length - 1].dataset.vidx : null,
      首行: txt(lis[0]).replace(/\\s+/g, ' ').slice(0, 90),
      末行: txt(lis[lis.length - 1]).replace(/\\s+/g, ' ').slice(0, 90),
      顶占位: pads[0] ? Math.round(pads[0].getBoundingClientRect().height) : null,
      底占位: pads[1] ? Math.round(pads[1].getBoundingClientRect().height) : null,
      占位个数: pads.length,
      scrollTop: Math.round(el.scrollTop),
      scrollHeight: Math.round(el.scrollHeight),
      clientHeight: Math.round(el.clientHeight),
      已收文本: (() => {
        const c = document.querySelector('.ev-status .cell')
        if (!c) return ''
        return ((c.getAttribute('title') || '') + ' | ' + (c.innerText || '')).replace(/\\s+/g, ' ')
      })(),
    }
  })()`)

  const bottom = await readWin()
  ok('数据侧确实收了 1000 条（① 的「本页已收」title）', bottom.已收文本.includes('本页已收 1,000'), bottom.已收文本)
  ok('初始停在底部：窗口首个 idx 远大于 0', bottom.首个idx !== null && bottom.首个idx > 500, bottom)
  ok('末行 = 最后一条（虚拟事件 1000）', bottom.末行.includes('虚拟事件 1000'), bottom.末行)
  ok('DOM 行数 ≪ 1000（窗口化生效）', bottom.窗口行数 > 0 && bottom.窗口行数 < 80, bottom.窗口行数)
  ok('上下各有一个空白占位（撑 scrollHeight 用的）', bottom.占位个数 === 2, bottom.占位个数)
  ok('底部时下占位 ≈ 0、上占位 > 0', bottom.底占位 !== null && bottom.底占位 <= 2 && bottom.顶占位 > 1000,
    { 顶占位: bottom.顶占位, 底占位: bottom.底占位 })
  ok('scrollHeight 是「1000 条的总高」量级（估算 + 实测，> 30000px）', bottom.scrollHeight > 30000, bottom.scrollHeight)

  // ── 滚到顶 ────────────────────────────────────────────────────────────────
  await b.evalJS(`(() => { const el = document.getElementById('ev-list'); el.scrollTop = 0; return el.scrollTop })()`)
  await sleep(600)
  const top = await readWin()
  ok('滚到顶 ⇒ 窗口从第 0 条开始', top.首个idx === 0, top)
  ok('滚到顶 ⇒ 首行 = 虚拟事件 1（时间正序）', top.首行.includes('虚拟事件 1'), top.首行)
  ok('滚到顶 ⇒ 上占位 = 0', top.顶占位 !== null && top.顶占位 === 0, top.顶占位)
  ok('滚到顶后 DOM 行数仍然 ≪ 1000', top.窗口行数 > 0 && top.窗口行数 < 80, top.窗口行数)

  // ── 滚到中段 ──────────────────────────────────────────────────────────────
  await b.evalJS(`(() => { const el = document.getElementById('ev-list'); el.scrollTop = Math.round(el.scrollHeight / 2); return el.scrollTop })()`)
  await sleep(600)
  const mid = await readWin()
  ok('中段：上下占位都 > 0（两侧都还有未渲染的行）', mid.顶占位 > 0 && mid.底占位 > 0, { 顶: mid.顶占位, 底: mid.底占位 })
  ok('中段：首行不是第 1 条、也不在末尾（窗口真的跟着 scrollTop 走）',
    mid.首个idx > 50 && mid.末个idx < 990, { 首: mid.首个idx, 末: mid.末个idx })
  ok('中段：DOM 行数仍然 ≪ 1000（滑动窗口没有退化成全量）', mid.窗口行数 > 0 && mid.窗口行数 < 80, mid.窗口行数)

  // ── 帧率（rAF 帧间隔）：从 70% 处向上连续滚动约 2 秒 ─────────────────────
  const frames = await b.evalJS(`new Promise((resolve) => {
    const el = document.getElementById('ev-list')
    el.scrollTop = Math.round(el.scrollHeight * 0.7)
    const out = []
    let last = performance.now()
    const t0 = last
    function step(now) {
      out.push(now - last)
      last = now
      el.scrollTop = Math.max(0, el.scrollTop - 40)
      if (now - t0 < 2000) requestAnimationFrame(step)
      else resolve(out)
    }
    requestAnimationFrame(step)
  })`)
  const sorted = frames.slice().sort((a, c) => a - c)
  const pct = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))]
  const over = (ms) => frames.filter((x) => x > ms).length
  const stat = {
    帧数: frames.length,
    p50: +pct(0.5).toFixed(2), p95: +pct(0.95).toFixed(2), max: +Math.max(...frames).toFixed(2),
    '>16.7ms': over(16.7), '>33ms': over(33),
  }
  ok('滚动采样确实跑了（> 60 帧）', frames.length > 60, stat)
  ok('p95 帧间隔 ≤ 25ms（向上翻阅不掉到 40fps 以下）', stat.p95 <= 25, stat)
  ok('超过 33ms 的长帧占比 ≤ 5%', over(33) / Math.max(1, frames.length) <= 0.05, stat)
  ok('采样后窗口仍只渲染几十行（滚动中没有退化成全量）',
    (await readWin()).窗口行数 < 80, (await readWin()).窗口行数)

  // ── 组合判据：contain / passive / 无平滑 ─────────────────────────────────
  const css = await b.evalJS(`(() => {
    const ln = document.querySelector('.log-line .ln')
    const list = document.getElementById('ev-list')
    return {
      contain: ln ? getComputedStyle(ln).contain : null,
      scrollBehavior: getComputedStyle(list).scrollBehavior,
      willChange: getComputedStyle(list).willChange,
      scrollOpts: window.__scrollOpts,
    }
  })()`)
  ok('每行 `.ln` 开了渲染隔离（contain = `content` = layout + paint + style）',
    css.contain === 'content', css.contain)
  ok('滚动监听是 passive（`@scroll.passive` 传到了 addEventListener）',
    (css.scrollOpts || []).some((x) => x.id === 'ev-list' && x.passive === true), css.scrollOpts)
  ok('没有开启原生平滑滚动（scroll-behavior 不是 smooth）',
    !css.scrollBehavior || css.scrollBehavior === 'auto', css.scrollBehavior)
  ok('滚动容器有 will-change: scroll-position', /scroll-position/.test(css.willChange || ''), css.willChange)

  await b.shot(`${WORK}virtual-top.png`)
  await b.evalJS(`(() => { const el = document.getElementById('ev-list'); el.scrollTop = el.scrollHeight; return true })()`)
  await sleep(500)
  await b.shot(`${WORK}virtual-bottom.png`)

  const shot = `${WORK}virtual-bottom.png`
  fs.writeFileSync(`${WORK}virtual-frames.json`, JSON.stringify({ stat, 取样: frames.map((x) => +x.toFixed(1)) }, null, 2))
  console.log(`\n帧率样本：${JSON.stringify(stat)}`)
  console.log(`截图：${shot}（滚动到底）· ${WORK}virtual-top.png（滚动到顶）`)
} catch (e) {
  TOTAL++
  console.log('\n=== RAW STACK ===')
  console.log(String((e && e.stack) || e))
  console.log('=== /RAW ===')
  R.ok('探针自身没抛异常', false, String(e && e.stack ? e.stack.split('\n').slice(0, 4).join(' | ') : e))
} finally {
  const errs2 = await b.evalJS('(window.__errs || [])').catch(() => [])
  ok('全流程收尾：仍无 console.error / unhandledrejection', (errs2 || []).length === 0, (errs2 || []).slice(0, 5))
  const bad = R.dump()
  console.log(`共 ${TOTAL} 项断言。`)
  await b.close()
  process.exit(bad === 0 ? 0 : 1)
}
