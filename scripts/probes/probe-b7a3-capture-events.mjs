/**
 * 3a.3 · 抓**日志页**（EventLog）的 SSE 原始帧（逐字落盘，不筛选、不加工）。
 *
 * 【为什么要另抓一场】3a.2b 抓的 60 拍是 **metrics 流**（字段 `seq/ts/requests/cache/
 * gauges/locks/protections/stock`）—— **没有** `events` / `rate` / `mq` / `traces`。
 * 日志页消费的是另一条流：`POST /admin/observe/events/stream`
 * （`EventLog.vue` 走 `api/observe.js` 的 `openEventStream`），帧字段
 * `seq / ts / rate / mq / events / traces`。指标帧**喂不了**日志页的卡片流。
 *
 * 【为什么直连后端、不开浏览器】同 3a.2b：dev profile 关鉴权
 * ⇒ 不需要 `Authorization`。直连省掉登录流程、dev server 代理与 CORS 那一族坑。
 * ⚠️ 若返回 401/403 **不许默默改走 CDP** —— 当场非零退出，由人来定。
 *
 * 【为什么不需要手工掐断】服务端以 `--mall.observe.sse.max-ticks=60` 启动
 * ⇒ 推满 60 帧**自己正常收尾**。手工掐断会留半帧。
 *
 * 产出：`_work/3a3/frames-events.ndjson`，一行一帧（原始 JSON，未加工）。
 */
import fs from 'node:fs'

const URL_ = process.env.B7_EVENTS_URL || 'http://localhost:8088/admin/observe/events/stream'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a3/frames-events.ndjson'

const res = await fetch(URL_, {
  method: 'POST',
  headers: { 'Content-Type': 'text/plain' },
  body: '',
})
console.log('HTTP', res.status, '·', res.headers.get('content-type'))
if (!res.ok) {
  console.error('⚠️ 非 2xx —— 按约定停手，不切 CDP')
  process.exit(3)
}

const frames = []
let buf = ''
const dec = new TextDecoder()
const t0 = Date.now()
let bad = 0

for await (const chunk of res.body) {
  buf += dec.decode(chunk, { stream: true })
  let i
  while ((i = buf.indexOf('\n\n')) >= 0) {
    const raw = buf.slice(0, i)
    buf = buf.slice(i + 2)
    const line = raw.split('\n').find((l) => l.startsWith('data: '))
    if (!line) continue
    try {
      frames.push(JSON.parse(line.slice(6)))
    } catch (e) {
      bad++
      console.error('⚠️ 一帧解析失败（丢弃）：', e.message)
    }
    if (frames.length % 10 === 0) {
      console.log('  ...', frames.length, '帧 ·', Math.round((Date.now() - t0) / 1000) + 's')
    }
  }
}

fs.mkdirSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/3a3', { recursive: true })
fs.writeFileSync(OUT, frames.map((f) => JSON.stringify(f)).join('\n') + '\n')

/* 抓完当场报「本场到底有什么」——尤其**九类各来没来**：
   图例是 9 类，若某类本窗 0 条，结构稿里那一格就得如实显示 0，不能悄悄补齐。 */
const byKind = {}
const bySev = {}
let evTotal = 0
let traces = 0
for (const f of frames) {
  for (const e of f.events || []) {
    evTotal++
    byKind[e.kind] = (byKind[e.kind] || 0) + 1
    bySev[e.severity] = (bySev[e.severity] || 0) + 1
  }
  traces += (f.traces || []).length
}
console.log(JSON.stringify({
  frames: frames.length, bad,
  seconds: Math.round((Date.now() - t0) / 1000),
  firstSeq: frames[0] && frames[0].seq,
  lastSeq: frames.length && frames[frames.length - 1].seq,
  evTotal, byKind, bySev, traces,
  rateMin: Math.min(...frames.map((f) => f.rate ?? 0)),
  rateMax: Math.max(...frames.map((f) => f.rate ?? 0)),
  mq: frames.some((f) => f.mq) ? 'up' : 'down',
  out: OUT,
}, null, 1))
