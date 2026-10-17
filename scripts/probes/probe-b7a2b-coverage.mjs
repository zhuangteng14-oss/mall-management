/**
 * 3a.2b · 字段覆盖核查 —— **硬门禁**：任何在本次窗口里「全零」的字段**不许进图**
 * （拿真字段画一条贴地的直线，观感上就是假数据）。
 *
 * 输入：`_work/3a2b/frames.ndjson`（由 probe-b7a2b-capture-frames.mjs 逐字抓下）
 * 输出：逐字段的「非空帧数 / 非零帧数 / 总帧数」+ 判定 + 每锁明细。
 *
 * 口径：
 *   · `null` 与「字段不存在」都记作**空**（`requests.meanMs` 在无延迟样本时就是 null，
 *     见 `MetricsSnapshotService.java:271` 的 `hasLatency` 分支）。
 *   · `0` 记作**非空但零** —— 这正是要拦的那一类：有字段、有值、但整窗口恒 0。
 *   · 数组字段（`locks[]` / `protections[]`）按**任一元素非零**计一帧，
 *     并另列逐锁明细（锁名 × 有值的帧数）。
 */
import fs from 'node:fs'

const IN = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a2b/frames.ndjson'
const lines = fs.readFileSync(IN, 'utf8').split('\n').filter(Boolean)
const F = lines.map((l) => JSON.parse(l))
const N = F.length

const SCALARS = [
  ['requests.count', (f) => f.requests && f.requests.count],
  ['requests.qps', (f) => f.requests && f.requests.qps],
  ['requests.errors5xx', (f) => f.requests && f.requests.errors5xx],
  ['requests.errors4xx', (f) => f.requests && f.requests.errors4xx],
  ['requests.errorRate', (f) => f.requests && f.requests.errorRate],
  ['requests.clientErrorRate', (f) => f.requests && f.requests.clientErrorRate],
  ['requests.meanMs', (f) => f.requests && f.requests.meanMs],
  ['requests.p95Ms', (f) => f.requests && f.requests.p95Ms],
  ['requests.p99Ms', (f) => f.requests && f.requests.p99Ms],
  ['requests.maxMs', (f) => f.requests && f.requests.maxMs],
  ['cache.hit', (f) => f.cache && f.cache.hit],
  ['cache.miss', (f) => f.cache && f.cache.miss],
  ['cache.hitRate', (f) => f.cache && f.cache.hitRate],
  ['cache.l2Degraded', (f) => f.cache && f.cache.l2Degraded],
  ['gauges.l2Up', (f) => f.gauges && f.gauges.l2Up],
  ['gauges.lockUp', (f) => f.gauges && f.gauges.lockUp],
  ['health.level', (f) => f.health && f.health.level],
]

// 数组字段：任一元素非零 → 记一帧
const LOCKS = ['acquires', 'acquired', 'timeouts', 'degraded', 'errors', 'waitMeanMs']
const ARRAYS = LOCKS.map((k) => [
  'locks[]' + k,
  (f) => ((f.locks || []).some((r) => r[k] != null && r[k] !== 0) ? 1 : 0),
])
ARRAYS.push(['protections[].value', (f) => ((f.protections || []).some((p) => p.value) ? 1 : 0)])

function tally(get) {
  let nonNull = 0, nonZero = 0
  for (const f of F) {
    const v = get(f)
    if (v === null || v === undefined) continue
    nonNull++
    if (typeof v === 'number' ? v !== 0 : Boolean(v)) nonZero++
  }
  return { nonNull, nonZero }
}

console.log(`=== 3a.2b 字段覆盖核查：${N} 帧 ===\n`)
console.log('字段'.padEnd(26) + '非空帧'.padStart(8) + '非零帧'.padStart(8) + '  / 总  判定')
const verdict = []
for (const [name, get] of SCALARS.concat(ARRAYS)) {
  const { nonNull, nonZero } = tally(get)
  let v
  if (nonZero === N) v = '✅ 可用'
  else if (nonZero === 0 && nonNull === 0) v = '⛔ 全空 · 不上图'
  else if (nonZero === 0) v = '⛔ 有值但全零 · 不上图'
  else v = `🟡 部分有值（${nonZero}/${N}）· 待裁`
  verdict.push([name, nonZero, N, v])
  console.log(name.padEnd(26) + String(nonNull).padStart(8) + String(nonZero).padStart(8) + `  / ${N}  ` + v)
}

// ── 逐锁明细：锁名 × 有值的帧数 + 该锁的量级 ──
const byLock = {}
for (const f of F) {
  for (const r of f.locks || []) {
    const e = (byLock[r.lock] = byLock[r.lock] || { frames: 0, acquires: 0, timeouts: 0, waitMeanMs: 0, waitFrames: 0 })
    e.frames++
    e.acquires += r.acquires || 0
    e.timeouts += r.timeouts || 0
    if (r.waitMeanMs != null) { e.waitMeanMs += r.waitMeanMs; e.waitFrames++ }
  }
}
console.log('\n--- 逐锁明细（整个窗口的合计）---')
console.log('锁名'.padEnd(28) + '出现帧'.padStart(7) + 'acquires'.padStart(10) + 'timeouts'.padStart(10) + 'waitMeanAvg'.padStart(13) + '等待帧'.padStart(8))
for (const [k, e] of Object.entries(byLock)) {
  const avg = e.waitFrames ? (e.waitMeanMs / e.waitFrames).toFixed(1) : '—'
  console.log(k.padEnd(28) + String(e.frames).padStart(7) + String(e.acquires).padStart(10) + String(e.timeouts).padStart(10) + String(avg).padStart(13) + String(e.waitFrames).padStart(8))
}

// ── 逐防护明细：`protections[]` 第一行就是 `cacheHit ← cache.hit`（同一数字，两个入口）
//    ⇒ 若整表只有它非零，「防护柱状图」就只是缓存命中序列的复刻。逐行报出来，别靠推断。
const byProt = {}
for (const f of F) {
  for (const p of f.protections || []) {
    const e = (byProt[p.tag] = byProt[p.tag] || { label: p.label, frames: 0, nz: 0, sum: 0, max: 0 })
    e.frames++
    if (p.value) { e.nz++; e.sum += p.value; e.max = Math.max(e.max, p.value) }
  }
}
console.log('\n--- 逐防护明细（整个窗口）---')
console.log('tag'.padEnd(20) + 'label'.padEnd(18) + '出现帧'.padStart(7) + '非零帧'.padStart(7) + '合计'.padStart(8) + 'max'.padStart(7))
for (const [tag, e] of Object.entries(byProt)) {
  console.log(tag.padEnd(20) + String(e.label).padEnd(18) + String(e.frames).padStart(7)
    + String(e.nz).padStart(7) + String(e.sum).padStart(8) + String(e.max).padStart(7))
}

// ── 量级速览（画图前先知道范围，避免出现看不见的序列）──
// 「非零帧够不够」只是第一关；**幅度够不够**是第二关：
// 一条非零 2/60、峰值 1 的序列，堆叠柱上就是一根看不见的发丝 —— 也得如实报出来。
// 数组字段取「逐帧所有元素的最大值」，因为进图的是每把锁各一条线 / 各一摞柱。
const frameMax = {
  'locks[]acquires': (f) => Math.max(0, ...(f.locks || []).map((r) => r.acquires || 0)),
  'locks[]waitMeanMs': (f) => Math.max(0, ...(f.locks || []).map((r) => r.waitMeanMs || 0)),
  'protections[].value': (f) => Math.max(0, ...(f.protections || []).map((p) => p.value || 0)),
}
const series = [
  ...SCALARS.map(([n, g]) => [n, g]),
  ...Object.entries(frameMax),
].filter(([n]) => !['health.level', 'gauges.l2Up', 'gauges.lockUp'].includes(n))

const stat = (get) => {
  const vs = F.map(get).filter((v) => typeof v === 'number')
  if (!vs.length) return null
  const sorted = [...vs].sort((a, b) => a - b)
  const q = (p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))]
  return {
    n: vs.length,
    nz: vs.filter((v) => v !== 0).length,
    min: sorted[0], max: sorted[sorted.length - 1], p50: q(0.5), p95: q(0.95),
  }
}
console.log('\n--- 候选序列量级（第二关：幅度够不够）---')
console.log('字段'.padEnd(24) + '有值帧'.padStart(7) + '非零帧'.padStart(7) + 'min'.padStart(11) + 'p50'.padStart(11) + 'p95'.padStart(11) + 'max'.padStart(11))
for (const [n, g] of series) {
  const s = stat(g)
  if (!s) { console.log(n.padEnd(24) + '—'); continue }
  const f = (v) => (Math.abs(v) < 1 && v !== 0 ? v.toFixed(4) : String(Math.round(v * 1000) / 1000))
  console.log(n.padEnd(24) + String(s.n).padStart(7) + String(s.nz).padStart(7)
    + f(s.min).padStart(11) + f(s.p50).padStart(11) + f(s.p95).padStart(11) + f(s.max).padStart(11))
}

const blocked = verdict.filter((v) => v[3].startsWith('⛔'))
console.log(`\n合计：${verdict.length} 项 · 可用 ${verdict.filter((v) => v[3].startsWith('✅')).length} · 待裁 ${verdict.filter((v) => v[3].startsWith('🟡')).length} · 拦下 ${blocked.length}`)
if (blocked.length) console.log('拦下：' + blocked.map((b) => b[0]).join('、'))
