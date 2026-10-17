/**
 * 3a.2b · 把**真抓的 60 拍帧**喂给三版结构草图（`docs/美化/c3-admin-preview/_struct-wire.js`）。
 *
 * 【为什么要有这一步】结构草图原先的数据来自 `_work/3a2b/snapshot.json` —— 那是**假流**下的
 * 7 拍 DOM 快照。7 拍判结构够了，但三版里那些「序列名」全都是假流编的（锁名 `goods_sku` /
 * `order_no` / `cart_item` 后端根本没有这个锁）。换成真窗口有两个后果，两个都要认：
 *   ① 序列全换（不只是「加新图」）—— 所以共享 x 轴那类老断言必须重跑，不能只验新图；
 *   ② 真窗口里有些字段**恒 0**，硬门禁直接把它们拦在图外（见 `probe-b7a2b-coverage.mjs`）。
 *
 * 【数据来源】`_work/3a2b/frames.ndjson`（`probe-b7a2b-capture-frames.mjs` 逐字抓的 60 帧）。
 * 本脚本**不抓帧、不连后端**，只做纯函数式的「帧 → DATA」。
 * ⚠️ `_work/` 在 `.gitignore` 里 ⇒ 仓里给了一份**同名内容的留档副本**：
 * `docs/agent/evidence/c3-block7/b7a2b-frames-60tick.ndjson`（60 拍 · 2026-10-16 · 2259 请求）。
 * 要复现本脚本的输出，把它拷回 `_work/3a2b/frames.ndjson` 再跑即可。
 *
 * 【写回方式】只替换 `_struct-wire.js` 里 `@@WIRE-DATA:BEGIN` 与 `@@WIRE-DATA:END` 之间的块。
 * 标记外一个字不动 ⇒ 可反复跑、幂等。
 *
 * 【纪律 · 生成的注释里不写「文件:行号」】
 * 本块是**产物**（每次重抓都重写）。产物里写行号 = 埋一批永不更新的 refs token
 * （同 TD-74 / TD-76 / TD-77 的处置：要定位请用**符号名**，不写行号）。
 * 顺带：旧块里那两条行号引用（`KpiCard.vue:18-24` / `MetricsPanel.vue:270-273`）
 * 随本次替换一并消失 —— 这是**有意的净减少**，不是漏抄。
 *
 * 跑法：`node scripts/probes/probe-b7a2b-wire-data.mjs`
 */
import fs from 'node:fs'
import { createHash } from 'node:crypto'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目/'
const IN = ROOT + '_work/3a2b/frames.ndjson'
const WIRE = ROOT + 'docs/美化/c3-admin-preview/_struct-wire.js'
const BEGIN = '  /* @@WIRE-DATA:BEGIN'
const END = '  /* @@WIRE-DATA:END'

const F = fs.readFileSync(IN, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
const N = F.length
const last = F[N - 1]

const r2 = (v) => Math.round(v * 100) / 100
const p2 = (n) => String(n).padStart(2, '0')
const hms = (ts) => { const d = new Date(Number(ts)); return p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds()) }

/* ── x 轴 + 三条 QPS 分量 ────────────────────────────────────────────────────
   后端口径（`MetricsSnapshotService.requests`）：`qps = 本拍请求数 ÷ 拍长(秒)`，
   `count` / `errors4xx` / `errors5xx` 都是**本拍增量计数**。
   ⇒ 堆叠要「三段加起来 == 原图总高」，就得把每段都除以**同一个**拍长，
      而不是拿 count 当纵轴。拍长取 `intervalMs/1000`（首帧是 0 ⇒ 该拍全 0）。 */
const x = F.map((f) => hms(f.ts))
const secs = F.map((f) => (f.intervalMs > 0 ? f.intervalMs / 1000 : 0))
const qps = F.map((f) => f.requests.qps)
const seg = (pick) => F.map((f, i) => (secs[i] <= 0 ? 0 : r2(pick(f) / secs[i])))
const qpsOk = seg((f) => f.requests.count - f.requests.errors4xx - f.requests.errors5xx)
const qps4xx = seg((f) => f.requests.errors4xx)
const qps5xx = seg((f) => f.requests.errors5xx)

const hit = F.map((f) => (f.cache.hitRate === null ? null : r2(f.cache.hitRate * 100)))
const hitN = F.map((f) => f.cache.hit)
const missN = F.map((f) => f.cache.miss)
const meanMs = F.map((f) => f.requests.meanMs)
const p99 = F.map((f) => f.requests.p99Ms)

/* ── 锁：行名 = 窗口内**出现过**的锁（顺序按首次出现，与页面 `lockNames` 同口径）── */
const lockNames = []
for (const f of F) for (const r of f.locks || []) if (!lockNames.includes(r.lock)) lockNames.push(r.lock)

/* 热力的格子 = 该锁该拍的**超时数**。值为 0 的格子**不发射**（与页面 `heatData` 同口径：
   只在 `r.locks[n] !== undefined` 时 push，画出来 0 落到最浅一档）—— 于是「整窗全 0」
   自然表现为空数组 + 整块最浅，而不是「缺数据」。 */
const heat = []
F.forEach((f, xi) => {
  lockNames.forEach((n, yi) => {
    const row = (f.locks || []).find((r) => r.lock === n)
    if (row && row.timeouts) heat.push([xi, yi, row.timeouts])
  })
})
/* 锁等待：每把锁一条 `waitMeanMs`。
   ⚠️ 后端在**本拍没有等待样本**时给 `null`（不是 0）⇒ 折线必须断，不能贴到 0
      （贴 0 会读成「等待耗时 0ms」，而事实是「这一拍没人等」）。 */
const waitSeries = lockNames.map((n) => F.map((f) => {
  const row = (f.locks || []).find((r) => r.lock === n)
  return row && row.waitMeanMs != null ? row.waitMeanMs : null
}))
const lockWaitArr = F.map((f) => (f.locks || []).reduce((s, r) => s + (r.timeouts || 0), 0))

/* ── KPI 环比：逐字照页面的 `avgOf` / `deltaOf` / `dtx` ─────────────────────
   `a === 0` 时返回 null（**分母为 0 不画箭头**，退回 hint）—— 锁等待恒 0 就会走到那一支。 */
const avgOf = (arr) => {
  const v = arr.filter((a) => a !== null && a !== undefined && Number.isFinite(Number(a)))
  return v.length ? v.reduce((a, b) => a + Number(b), 0) / v.length : null
}
const deltaOf = (arr, cur) => {
  const a = avgOf(arr), c = Number(cur)
  if (a === null || a === 0 || !Number.isFinite(c)) return null
  return r2(((c - a) / a) * 100)
}
const dtx = (d) => (d === null || d === undefined ? '' : Math.abs(d).toFixed(1) + '%')

const curQps = qps[N - 1], curP99 = p99[N - 1]
const curHit = last.cache.hitRate
const curLock = lockWaitArr[N - 1]
const kpis = [
  { key: 'qps', label: 'QPS', value: Number(curQps).toFixed(1), unit: '次/秒',
    delta: dtx(deltaOf(qps, curQps)), dl: '较 60 秒均值',
    hint: '本拍请求 ' + last.requests.count + ' 次', series: 'qps' },
  { key: 'p99', label: 'P99 延迟', value: String(Math.round(curP99)), unit: 'ms',
    delta: dtx(deltaOf(p99, curP99)), dl: '较 60 秒均值',
    hint: '全站分位数的上界近似（各序列取最大）', series: null },
  { key: 'hit', label: '缓存命中率', value: (curHit * 100).toFixed(1), unit: '%',
    delta: dtx(deltaOf(F.map((f) => f.cache.hitRate), curHit)), dl: '较 60 秒均值',
    hint: 'L1 命中 / (命中 + 未命中)', series: 'hit' },
  { key: 'lock', label: '锁等待次数', value: String(Math.round(curLock)), unit: '次',
    delta: dtx(deltaOf(lockWaitArr, curLock)), dl: '较 60 秒均值',
    hint: '全部锁名本拍 timeouts 之和', series: null },
]

/* ── 状态带（本窗最后一拍）+ 判据 ────────────────────────────────────────── */
const header = {
  health: (last.health && last.health.label) || '—',
  err5xx: ((last.requests.errorRate == null ? 0 : last.requests.errorRate) * 100).toFixed(1) + '%',
  err4xx: ((last.requests.clientErrorRate == null ? 0 : last.requests.clientErrorRate) * 100).toFixed(1) + '%',
  phase: '已连接', seq: last.seq, win: 60, tick: hms(last.ts),
  source: '正常（后端真流）', sourceAlt: '模拟日常',
}
const reasons = (last.health && last.health.reasons) || []

/* ── 防护 8 类（本拍增量）与库存（本窗最后一拍）──────────────────────────── */
const prot = {
  labels: (last.protections || []).map((p) => p.label),
  values: (last.protections || []).map((p) => Number(p.value) || 0),
}
prot.max = Math.max(1, Math.max.apply(null, prot.values))
const stock = ((last.stock && last.stock.rows) || []).map((r) => ({
  name: r.goodName || '（无商品名）',
  sku: r.sku + (r.axisName ? ' · ' + r.axisName : ''),
  stock: r.stock === null || r.stock === undefined ? '—' : String(r.stock),
  baseline: r.baseline === null || r.baseline === undefined ? '—' : String(r.baseline),
  ratio: r.ratio === null || r.ratio === undefined ? null : Number(r.ratio),
}))

/* ── 给图头用的两个数：单拍 4xx 峰值占比 / 整窗 4xx 均值占比 ────────────────
   ⚠️ 这两个数字**从本窗口现算**，不写死 —— 换一次负载它们就该变。 */
const rates = F.map((f) => (f.requests.count > 0 ? f.requests.errors4xx / f.requests.count : 0))
const peak4xx = Math.max.apply(null, rates) * 100
const mean4xx = (F.reduce((s, f) => s + f.requests.errors4xx, 0) /
                 Math.max(1, F.reduce((s, f) => s + f.requests.count, 0))) * 100
const pct1 = (v) => (Math.round(v * 10) / 10).toFixed(1) + '%'

/* ── 依赖状态点（#6）：`l2Up` / `lockUp` 是**常量 1.0** ────────────────────
   逐帧记下来是为了让「恒 1」这件事**可核**（非零帧数写在 DATA 里），
   而不是把它当趋势。图上是**状态点**，不是序列。 */
const state = {
  l2Up: last.gauges.l2Up, lockUp: last.gauges.lockUp,
  nz: {
    l2Up: F.filter((f) => f.gauges.l2Up === 1).length,
    lockUp: F.filter((f) => f.gauges.lockUp === 1).length,
  },
  n: N,
}

/* ══ 拼 DATA 块 ══════════════════════════════════════════════════════════
   纯字符串拼接 —— 不 eval、不把 JS 源码塞进字符串再求值。 */
const J = (a) => JSON.stringify(a)
const B = []
B.push(BEGIN + ' —— 由 scripts/probes/probe-b7a2b-wire-data.mjs 从真帧生成，勿手改 @@ */')
B.push('  var DATA = {')
B.push('    /* 状态带 = 本窗最后一拍 */')
B.push('    header: { health: ' + J(header.health) + ', err5xx: ' + J(header.err5xx) + ', err4xx: ' + J(header.err4xx) + ',')
B.push('      phase: ' + J(header.phase) + ', seq: ' + header.seq + ', win: ' + header.win + ', tick: ' + J(header.tick) + ',')
B.push('      source: ' + J(header.source) + ', sourceAlt: ' + J(header.sourceAlt) + ' },')
B.push('    /* 判据：健康态也可能非空（后端填的是正面理由）*/')
B.push('    reasons: ' + J(reasons) + ',')
B.push('    /* 4 张 KPI。delta 为 \'\' = 分母为 0 ⇒ 不画箭头、退回 hint（锁等待恒 0 走的就是这支）*/')
B.push('    kpis: [')
kpis.forEach((k, i) => B.push('      ' + J(k) + (i < kpis.length - 1 ? ',' : '')))
B.push('    ],')
B.push('')
B.push('    /* ── 60 拍共用 x 轴 + 各序列 ─────────────────────────────────────── */')
B.push('    x: ' + J(x) + ',')
B.push('    /* 原图纵轴（QPS）；KPI 与微条用这一条 */')
B.push('    qps: ' + J(qps) + ',')
B.push('    /* QPS 堆叠三段：成功 / 4xx / 5xx —— 三段同单位（次/秒）⇒ 加起来 = 原图总高。')
B.push('       ⚠️ qps5xx 整窗 0 ⇒ **不画**（硬门禁：全零字段不上图，画出来就是贴地假线）*/')
B.push('    qpsOk: ' + J(qpsOk) + ',')
B.push('    qps4xx: ' + J(qps4xx) + ',')
B.push('    qps5xx: ' + J(qps5xx) + ',')
B.push('    /* 缓存：命中率（0-100，null = 本拍无请求 ⇒ 断线）+ 命中/未命中**计数**（双轴堆叠柱用）*/')
B.push('    hit: ' + J(hit) + ',')
B.push('    hitN: ' + J(hitN) + ',')
B.push('    missN: ' + J(missN) + ',')
B.push('    /* 平均延迟：**逐拍真值**（后端用差分算，见 TD-77）。null = 本拍无延迟样本 */')
B.push('    meanMs: ' + J(meanMs) + ',')
B.push('    /* P99：**不是逐拍**（Micrometer 衰减窗口分位）—— 只喂 KPI 卡（原页面口径），不上折线 */')
B.push('    p99: ' + J(p99) + ',')
B.push('')
B.push('    /* ── 锁 ────────────────────────────────────────────────────────── */')
B.push('    /* 行名 = 窗口内出现过的锁（顺序按首次出现，与页面同口径）*/')
B.push('    lockNames: ' + J(lockNames) + ',')
B.push('    /* 热力格子 = 该锁该拍的**超时数**；0 不发射 ⇒ 整窗全 0 就是 [] + 整块最浅。')
B.push('       本窗两把锁的 timeouts 都是 0 ⇒ 这里必然是 []，见 DATA.notes.heat */')
B.push('    heatMax: ' + Math.max(1, Math.max.apply(null, heat.map((c) => c[2]).concat([0]))) + ',')
B.push('    heat: ' + J(heat) + ',')
B.push('    /* 锁等待 = 每把锁一条 waitMeanMs 折线。⚠️ 无等待样本的拍是 null ⇒ **断开**，不是 0 */')
B.push('    wait: { names: ' + J(lockNames) + ', series: ' + J(waitSeries) + ' },')
B.push('')
B.push('    /* ── 依赖状态点（#6）───────────────────────────────────────────── */')
B.push('    /* 恒 1 是**实测**（nz 是整窗命中帧数），图上是状态点不是趋势 */')
B.push('    state: ' + J(state) + ',')
B.push('')
B.push('    /* ── 无时间维两块（本窗最后一拍）──────────────────────────────── */')
B.push('    prot: { labels: ' + J(prot.labels) + ',')
B.push('      values: ' + J(prot.values) + ', max: ' + prot.max + ' },')
B.push('    stock: ' + J(stock) + ',')
B.push('')
B.push('    /* 图头要标的数：都从本窗口现算，不写死 */')
B.push('    notes: {')
B.push('      qps5xx: ' + J('5xx 段待 D 段接通（mqDown/dbSlow）后补') + ',')
B.push('      qps4xx: ' + J('1 秒桶 + 成簇 ⇒ 单拍峰值可达 ~' + pct1(peak4xx) + '，整窗均值 ' + pct1(mean4xx)) + ',')
B.push('      heat: ' + J('本窗 ' + N + ' 拍锁超时数全为 0 ⇒ 整块最浅（不是缺数据）') + ',')
B.push('      p99: ' + J('P99 卡是衰减窗口分位、不是逐拍 —— 别拿它跟「平均延迟」折线对照') + ',')
B.push('      state: ' + J('本窗 ' + state.nz.l2Up + '/' + N + ' 拍恒 1（常量，不是趋势）') + ',')
B.push('      stock: ' + J(stock.length + ' 行，基线（BaselineStock）全为空 ⇒ 水位算不出，画满格灰条') + ',')
B.push('    }')
B.push('  }')
const block = B.join('\n') + '\n' + END + ' 勿手改 @@ */\n'

const before = fs.readFileSync(WIRE, 'utf8')
const si = before.indexOf(BEGIN), ei = before.indexOf(END)
if (si < 0 || ei < 0) { console.error('⛔ 找不到 ' + BEGIN + ' / ' + END + ' 标记，未改动'); process.exit(1) }
const rest = before.slice(before.indexOf('\n', ei) + 1)
const after = before.slice(0, si) + block + rest
fs.writeFileSync(WIRE, after)

/* ── 报一句：写进去的每个序列「非零帧数 / 60」───────────────────────────── */
const nz = (a) => a.filter((v) => v !== null && v !== undefined && v !== 0).length
const h12 = (s) => createHash('sha1').update(s).digest('hex').slice(0, 12)
console.log('已写回 ' + WIRE)
/* 幂等自证：**前**是写之前那份文件、**后**是写之后那份。两者不同 = 这一跑真的改了文件
   （第一次跑会改：旧块是上一版脚本生成的）；两者相同 = 再跑一次不会再动它。
   光看「已写回」看不出这件事 —— 幂等要打印出来才算证据。 */
console.log('幂等：写前 ' + h12(before) + ' · 写后 ' + h12(after) + (h12(before) === h12(after) ? ' ✓ 未改动' : ' ✗ 本次有改动（重跑一次应显示 ✓）'))
console.log('帧数 ' + N + ' · 锁名 [' + lockNames.join(', ') + '] · 库存 ' + stock.length + ' 行 · 判据 ' + reasons.length + ' 条\n')
const rows = [
  ['qps', qps], ['qpsOk', qpsOk], ['qps4xx', qps4xx], ['qps5xx', qps5xx],
  ['hit', hit], ['hitN', hitN], ['missN', missN], ['meanMs', meanMs], ['p99', p99],
  ['heat(格子)', heat], ['lockWaitArr', lockWaitArr],
]
console.log('序列'.padEnd(16) + '非零帧'.padStart(8) + ' / ' + N)
for (const [n, a] of rows) console.log(n.padEnd(16) + String(nz(a)).padStart(8) + ' / ' + N)
for (let i = 0; i < waitSeries.length; i++) {
  console.log(('wait[' + lockNames[i] + ']').padEnd(16) + String(nz(waitSeries[i])).padStart(8) + ' / ' + N)
}
console.log('\n注：' + kpis.map((k) => k.label + ' ' + k.value + k.unit + (k.delta ? ' ' + k.delta : '（无箭头·分母0）')).join(' · '))
console.log('图头数：4xx 峰值占比 ' + pct1(peak4xx) + ' · 整窗均值 ' + pct1(mean4xx))
