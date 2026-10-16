/**
 * 3a.2b · 候选序列的**逐拍轨迹**（60 个值摊平印出来）。
 *
 * 【为什么还要这一步】覆盖核查只回答「有没有值 / 幅度多大」，回答不了**形状**：
 *   · `requests.p95Ms` 是 Micrometer 的**进程累计分位**（Timer.percentile），
 *     不是「这一拍的分位」⇒ 它**不可差分成每拍量**（分位数不能相减）。
 *     若 60 拍里它只动了一两次，那么「P95/P99 折线」画出来是条**阶梯**而不是趋势 ——
 *     比一条贴地直线更坑：看着像数据，其实是被启动初期那几笔慢请求钉住的常数。
 *   · `locks[].waitMeanMs` 只在有竞争的那 16 拍有值 ⇒ 要看清是「稀疏尖刺」还是「平台」。
 *
 * 输入：`_work/3a2b/frames.ndjson`
 * 输出：stdout（每序列一行 60 个数，四舍五入到 3 位）
 */
import fs from 'node:fs'

const IN = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/3a2b/frames.ndjson'
const F = fs.readFileSync(IN, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))

const maxOf = (rows, k) => (rows && rows.length ? Math.max(0, ...rows.map((r) => r[k] || 0)) : 0)

const SERIES = [
  ['requests.qps', (f) => f.requests && f.requests.qps],
  ['requests.meanMs', (f) => f.requests && f.requests.meanMs],
  ['requests.p95Ms', (f) => f.requests && f.requests.p95Ms],
  ['requests.p99Ms', (f) => f.requests && f.requests.p99Ms],
  ['requests.maxMs', (f) => f.requests && f.requests.maxMs],
  ['cache.hit', (f) => f.cache && f.cache.hit],
  ['cache.miss', (f) => f.cache && f.cache.miss],
  ['protections[].value(max)', (f) => maxOf(f.protections, 'value')],
  ['locks[].acquires(max)', (f) => maxOf(f.locks, 'acquires')],
  ['locks[].waitMeanMs(max)', (f) => maxOf(f.locks, 'waitMeanMs')],
]

const r3 = (v) => (v === null || v === undefined ? '--' : String(Math.round(v * 1000) / 1000))

/* ⚠️ 「不同取值」只在**有值**的那几个上数 —— 早先这一版把 `'--'`（空值占位）也算进集合，
   于是同一份文件里表头写 56、判读行写 55，差 1 而**都不是笔误**：两处算法不同。
   这种「一个文件自己跟自己不一致」比少写一个数危险得多：抄表头的人会抄到 56。
   ⇒ 表头与判读行共用下面这一个 stat()，从根上不可能再分叉。 */
const stat = (raw) => {
  const nn = raw.filter((v) => typeof v === 'number')
  const changes = nn.reduce((a, v, i) => a + (i > 0 && v !== nn[i - 1] ? 1 : 0), 0)
  return { n: raw.length, live: nn.length, distinct: new Set(nn.map(r3)).size, changes }
}

console.log(`=== 3a.2b 候选序列逐拍轨迹（${F.length} 拍，seq 0→${F.length - 1}）===`)
for (const [name, get] of SERIES) {
  const vs = F.map(get)
  const s = stat(vs)
  console.log(`\n${name}   （有值 ${s.live}/${s.n} 拍 · 不同取值 ${s.distinct} 个）`)
  console.log('  ' + vs.map(r3).join(' '))
}

// 分位数的「动了没有」单独说清楚：这是本轮最容易被误读的一条。
// ⚠️ 取数必须走 SERIES 里的取值函数 —— 别在这里另写 `f.requests[p]`
//    （键是 `p95Ms`，不是 `requests.p95Ms`；写错会静默全 undefined ⇒ 恒报「变了 0 次」）。
for (const p of ['requests.meanMs', 'requests.p95Ms', 'requests.p99Ms', 'requests.maxMs']) {
  const get = SERIES.find(([n]) => n === p)[1]
  const s = stat(F.map(get))
  console.log(`\n[判读] ${p}：有值 ${s.live} 拍 · 不同取值 ${s.distinct} 个 · 相邻变化 ${s.changes} 次`
    + ` ⇒ ${s.distinct <= 6 ? '阶梯 / 近常数，不是逐拍趋势' : '逐拍都在动'}`)
}
