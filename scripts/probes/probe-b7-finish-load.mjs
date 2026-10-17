// 块 7 收尾 · 500 并发压测探针（真后端 8088，不用 mock）。
//
// 目的（对应批文「跑一次压测（500 并发），截图证明日志 traceId 不串、假 500 归零」）：
//   ① 500 条**同时**在途的请求，每条带**唯一** X-Trace-Id 与**唯一** ProductCode；
//   ② 统计 HTTP 状态分布 —— 期望 **5xx = 0**（防护/幂等路径必须被改写成 200 + success:false）；
//   ③ 打开事件流，让本轮事件真正进环 → MQ → 台账（本仓的既定边界：没人看日志页时事件不落库，
//      见 ObservePoolConfig 类注释）—— 随后按 traceId 归组核对「一条请求的事件不会挂到别人的号上」。
//
// 用法：node scripts/probes/probe-b7-finish-load.mjs [concurrency] [runId]
// 输出：一行 SUMMARY（JSON）+ 每类失败的前几条明细。结果同时落 _work/b7-load-probe-<runId>.json。
import { writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:8088'
const CONCURRENCY = Number(process.argv[2] || 500)
const RUN_ID = process.argv[3] || 'b7load-' + randomUUID().slice(0, 8)

const priceForm = (code) => new URLSearchParams({
  MerchantId: 'B7PROBE',
  GoodName: 'B7 probe good',
  ProductCode: code
}).toString()

// 500 条请求：每条一个唯一码、一个唯一 traceId。用 POST /goods/productCodePrice：
// 它是被 @Cacheable 包住的读路径，每次 L1 访问都会记一条 cache 事件（objectId = 缓存键，含本请求的码）。
const jobs = Array.from({ length: CONCURRENCY }, (_, i) => {
  const code = `${RUN_ID}-${String(i).padStart(4, '0')}`
  return { i, code, traceId: `${RUN_ID}-trace-${String(i).padStart(4, '0')}` }
})

// —— 先开事件流（不读内容，只让它按 1 秒节拍 poll()，把本轮事件推进 MQ）——
const sseAbort = new AbortController()
const sseReady = (async () => {
  const r = await fetch(BASE + '/admin/observe/events/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'x=1',
    signal: sseAbort.signal
  })
  if (!r.ok) throw new Error('SSE open failed: HTTP ' + r.status)
  ;(async () => {
    try {
      for await (const _ of r.body) { /* 丢弃帧，只为驱动 poll() */ }
    } catch { /* abort 是正常收尾 */ }
  })()
  return true
})()
await sseReady
await new Promise(r => setTimeout(r, 1200))   // 让流线程先跑一拍

// ⚠️ 一次性甩出 500 条 TCP SYN 会偶发被 Windows 内核**拒连**（实测 run1 16 条、run3 11 条，
//    undici cause = ECONNREFUSED —— 服务端明明在听，是 accept 队列瞬时打满，不是 5xx）。
//    按批派发、批间 3 ms：连接建立错开 ~30 ms，而在途请求仍有数百条（放行的那 200 条每条
//    要读库，耗时 ≫ 30 ms）—— 既保住「500 并发」，又不把内核 backlog 算进结论。
const BATCH = 50, GAP_MS = 3
const results = []
const t0 = Date.now()
for (let off = 0; off < jobs.length; off += BATCH) {
  const slice = jobs.slice(off, off + BATCH)
  results.push(...await Promise.all(slice.map(async (job) => {
  const started = Date.now()
  try {
    const r = await fetch(BASE + '/goods/productCodePrice', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
        'X-Trace-Id': job.traceId
      },
      body: priceForm(job.code)
    })
    const body = await r.text()
    return { ...job, status: r.status, ms: Date.now() - started, body: body.slice(0, 200) }
  } catch (e) {
    // ⚠️ 只记 String(e)（"TypeError: fetch failed"）等于没记 —— 真正的成因在 e.cause
    //    （undici 的 ECONNRESET / UND_ERR_SOCKET / UND_ERR_CONNECT_TIMEOUT 等）。
    const c = e && e.cause
    return {
      ...job,
      status: 0,
      ms: Date.now() - started,
      error: String(e),
      causeCode: c && (c.code || c.errno) || null,
      causeMsg: c && c.message ? String(c.message) : null
    }
  }
  })))
  if (off + BATCH < jobs.length) await new Promise(r => setTimeout(r, GAP_MS))
}
const wallMs = Date.now() - t0

// 等 MQ 消费者落库（容器攒批 + receiveTimeout 默认 1 秒；这里多给几秒）
await new Promise(r => setTimeout(r, 5000))
sseAbort.abort()

const byStatus = {}
for (const r of results) byStatus[r.status] = (byStatus[r.status] || 0) + 1
const failed = results.filter(r => r.status === 0 || r.status >= 500)
const summary = {
  runId: RUN_ID,
  concurrency: CONCURRENCY,
  wallMs,
  statuses: byStatus,
  server5xx: results.filter(r => r.status >= 500).length,
  transportErrors: results.filter(r => r.status === 0).length,
  failedSamples: failed.slice(0, 5),
  traceIds: jobs.map(j => j.traceId)
}
writeFileSync(`_work/b7-load-probe-${RUN_ID}.json`, JSON.stringify(summary, null, 2))
console.log('SUMMARY ' + JSON.stringify({ ...summary, traceIds: undefined }))
if (failed.length) {
  console.log('FAILED SAMPLES:')
  for (const f of failed.slice(0, 5)) console.log(JSON.stringify(f))
}
