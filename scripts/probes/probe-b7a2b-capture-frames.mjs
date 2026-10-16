/**
 * 3a.2b · 抓指标页的 **SSE 原始帧**（逐字落盘，不做任何筛选/加工）。
 *
 * 【为什么要抓】真页面只把 4 条 series 画进了 DOM ⇒ `_work/3a2b/snapshot.json`（DOM 快照）
 * 里**只有那 4 条**。本次要补的序列（cache.hit/miss、locks[].waitMeanMs、requests.p95Ms…）
 * 从来没进过 DOM，DOM 快照里必然没有 ⇒ 只能回到源头抓帧。
 *
 * 【为什么直连后端、不开浏览器】dev profile 关鉴权（`application-dev.properties:81`
 * `app.auth.enabled=false`；默认 profile 才是 true，`application.properties:163`）⇒ 不需要
 * `Authorization`。直连省掉登录流程、dev server 代理与 CORS 可达性那一族坑。
 * ⚠️ 若返回 401/403 **不许默默改走 CDP** —— 当场非零退出，由人来定（否则事后无法确定
 * 实际走的是哪条路）。
 *
 * 【为什么不需要手工掐断】服务端以 `--mall.observe.sse.max-ticks=60` 启动
 * （`SseStreamWriter.java:99` 的 `@Value("${mall.observe.sse.max-ticks:0}")`，循环在 `:123`）
 * ⇒ 推满 60 帧**自己正常收尾**。手工掐断会留半帧。
 *
 * 帧格式见 `SseStreamWriter.java:47-51`：一帧一行 `data: {json}` + 一个空行。
 *
 * 产出：`_work/3a2b/frames.ndjson`，一行一帧（原始 JSON，未加工）。
 */
import fs from 'node:fs'

const URL_ = process.env.B7_STREAM_URL || 'http://localhost:8088/admin/observe/metrics/stream'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a2b/frames.ndjson'

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

fs.writeFileSync(OUT, frames.map((f) => JSON.stringify(f)).join('\n') + '\n')
console.log(JSON.stringify({
  frames: frames.length,
  bad,
  seconds: Math.round((Date.now() - t0) / 1000),
  firstSeq: frames[0] && frames[0].seq,
  lastSeq: frames.length && frames[frames.length - 1].seq,
  out: OUT,
}))
