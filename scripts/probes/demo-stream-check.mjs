// scripts/probes/demo-stream-check.mjs —— 「模拟日常」假数据源的**离线判据**（块 7 收尾 ②）。
//
// 不起浏览器、不起后端：直接把 `api/demo-stream.js` 拿进 node 跑，把三条硬口径与三页帧形状
// 逐条量一遍。⚠️ 时钟是**假的**（`setInterval` 被换成手动队列）⇒ 一场演示在一瞬间跑完，
// 与真实 1 秒/拍在数值上等价（模块只按「拍」计数，不读墙钟做算术）。
//
// 判据：
//   ① 频率：每一拍 5–20 条（`DEMO_MIN/MAX_PER_TICK`），且**分布有偏**（均值明显低于 12.5）
//   ② 总量：恰好投满 500（`DEMO_TOTAL`），且**永不超**；放完继续推帧，只是 n=0
//   ③ 三页帧形状：与后端三条流的键逐一对齐（缺键/多键都算错）
//   ④ 复演：同一种子跑两遍，逐帧一致
//   ⑤ 类的分布有偏：八类都出现，且最高的两类合计过半；严重度里 info 占八成以上
import { pathToFileURL, fileURLToPath } from 'node:url'
import { register } from 'node:module'
import path from 'node:path'

// ⚠️ 前端源码是 `.js` 而工程没有 `"type": "module"` ⇒ 得先让 node 把 `src/**/*.js` 当 ESM
//    （理由见那个 loader 的文件头）。**必须在下面那次动态 import 之前**注册。
register('./esm-src-loader.mjs', import.meta.url)

const here = path.dirname(fileURLToPath(import.meta.url))
const entry = path.resolve(here, '../../Mall-management-web/mall-management-system/src/api/demo-stream.js')

let fail = 0
function ok(name, cond, extra = '') {
  if (!cond) fail += 1
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  —— ' + extra : ''}`)
}

// ── 假时钟：`setInterval` 只登记回调、**不真的计时**；由本探针自己派发「拍」────────
// ⚠️ 模块按拍计数（不读墙钟做算术）⇒ 与真实的 1 秒/拍在数值上等价。
const timers = []
globalThis.setInterval = (fn) => { timers.push(fn); return timers.length }
globalThis.clearInterval = () => {}

const mod = await import(pathToFileURL(entry).href)
const { DEMO_TOTAL, DEMO_MIN_PER_TICK, DEMO_MAX_PER_TICK, openDemoStream, demo } = mod

/** 派发一拍（当前所有已注册的定时器各跑一次）。 */
function step() {
  for (const fn of [...timers]) fn()
}

/** 跑一场：返回每一帧（`openDemoStream` 里那次同步 tick 算第 1 拍）。 */
function run(kind, ticks = 400) {
  const out = []
  const handle = openDemoStream(kind, { onFrame: (f) => out.push(f) })
  for (let i = 0; i < ticks; i += 1) step()
  handle.close()
  timers.length = 0
  return out
}

// ── ① + ② + ⑤：事件页 ───────────────────────────────────────────────────
const ev = run('events')
const counts = ev.map((f) => f.events.length)
const total = counts.reduce((a, b) => a + b, 0)
const body = counts.filter((n) => n > 0)
const avg = total / body.length

ok('② 总量恰好投满 DEMO_TOTAL', total === DEMO_TOTAL, `total=${total}`)
ok('① 每拍都在 5–20 之间', counts.every((n) => n >= 0 && n <= DEMO_MAX_PER_TICK)
  && body.every((n) => n >= DEMO_MIN_PER_TICK), `min=${Math.min(...body)} max=${Math.max(...body)}`)
ok('① 分布有偏（均值 < 12.5 的均匀值）', avg < 12.5, `均值=${avg.toFixed(2)} 拍数=${body.length}`)
ok('② 放完后继续推帧且 n=0', counts.slice(body.length).length > 0
  && counts.slice(body.length).every((n) => n === 0), `空拍=${counts.length - body.length}`)
ok('② demo.emitted 停在 DEMO_TOTAL', demo.emitted === DEMO_TOTAL, `emitted=${demo.emitted}`)

const events = ev.flatMap((f) => f.events)
const byKind = {}
const bySev = { info: 0, warn: 0, error: 0 }
for (const e of events) {
  byKind[e.kind] = (byKind[e.kind] || 0) + 1
  bySev[e.severity] += 1
}
const kinds = Object.entries(byKind).sort((a, b) => b[1] - a[1])
const two = (kinds[0][1] + kinds[1][1]) / events.length
ok('⑤ 八类都出现', kinds.length === 8, kinds.map((k) => `${k[0]}:${k[1]}`).join(' '))
ok('⑤ 最高的两类合计过半', two > 0.5, `前二=${(two * 100).toFixed(1)}%`)
ok('⑤ info 占八成以上', bySev.info / events.length > 0.8,
  `info=${bySev.info} warn=${bySev.warn} error=${bySev.error}`)
ok('③ 事件行带 batchId=null（演示不是演练）', events.every((e) => e.batchId === null))
ok('③ 严重度的值都在三档内', events.every((e) => ['info', 'warn', 'error'].includes(e.severity)))

// ── ③+ 2026-10-17：假帧补上 3a.3 的三样（actor / durationUs / fields），形状对真帧 ──
// ⚠️ 这里比的是**键集**（不是「有没有这几个键」）：多一个少一个都会让前端 `fields` 优先那条路
//    静默走到回落分支，或让导出（JSON/ndjson 原样键）与真流长得不一样。
const REAL_EVENT_KEYS = 'actor,batchId,detail,durationUs,fields,key,kind,objectId,objectType,remaining,seq,severity,title,traceId,ts'
const keySet = (o) => Object.keys(o).sort().join(',')
ok('③+ 每条事件的**十五键与真帧逐字一致**（含 actor / durationUs / remaining / fields）',
  events.every((e) => keySet(e) === REAL_EVENT_KEYS),
  [...new Set(events.map(keySet))].filter((s) => s !== REAL_EVENT_KEYS).slice(0, 3))

const actors = events.map((e) => e.actor)
ok('③+ `actor` 是 `{id, role}` 对象或 **null**（不是两个平铺字段、不是空对象）',
  actors.every((a) => a === null
    || (typeof a === 'object' && typeof a.id === 'string' && [1, 2, 3].includes(a.role))),
  { 有身份: actors.filter(Boolean).length, 无身份: actors.filter((a) => a === null).length })
// ⚠️ 「全都有」或「全没有」都会让上一条的合法性变成空真：真流两种都会出现，演示也该两种都有。
ok('③+ actor 的**两种形态都真的出现过**（既有 `{id, role}`，也有 null）',
  actors.some(Boolean) && actors.some((a) => a === null),
  { 有身份: actors.filter(Boolean).length, 无身份: actors.filter((a) => a === null).length })
ok('③+ 审计环的 `fields.actor` 与顶层 `actor.id` **同一个人**（审计行的 Actor 列与 MDC 同源）',
  events.filter((e) => e.kind === 'audit').every((e) => e.actor && e.fields.actor === e.actor.id))

const durs = events.map((e) => e.durationUs)
ok('③+ `durationUs` 是**正整数（微秒）**或 null（不是毫秒、不是 0 冒充「测过」）',
  durs.every((d) => d === null || (Number.isInteger(d) && d > 0)),
  { 有耗时: durs.filter((d) => d !== null).length, 无耗时: durs.filter((d) => d === null).length })
ok('③+ 每类都至少有一个带耗时的样本（九类里没有一整类恒 null）',
  [...new Set(events.filter((e) => e.durationUs !== null).map((e) => e.kind))].length === 8,
  [...new Set(events.filter((e) => e.durationUs !== null).map((e) => e.kind))].sort().join(','))

const FIELDS_KEYS = {
  cache: 'cache,key,result',
  lock: 'key,lock,result',
  stock: 'kind,qty,result,sku',
  audit: 'action,actor,reason',
  notify: 'event,reason,to',
  sensitive: 'hit,scene,total',
  identity: 'reason,subject',
}
const fieldGroups = {}
for (const e of events) {
  const k = keySet(e.fields)
  fieldGroups[e.kind] = fieldGroups[e.kind] || new Set()
  fieldGroups[e.kind].add(k)
}
ok('③+ 各环 `fields` 的**键集逐环对齐后端 `Emit`**（cache/lock/stock/audit/notify/sensitive/identity 七套）',
  Object.entries(FIELDS_KEYS).every(([k, want]) => fieldGroups[k] && [...fieldGroups[k]].every((got) => got === want)),
  Object.fromEntries(Object.entries(fieldGroups).map(([k, v]) => [k, [...v]])))
ok('③+ `order` 的 `fields` 是**真帧那套词**：`action/result/from/to/order`（取消档只带 result/reason/order），'
  + '不是另起一套 key',
  events.filter((e) => e.kind === 'order').every((e) => {
    const ks = Object.keys(e.fields)
    return ks.every((k) => ['action', 'result', 'from', 'to', 'order', 'reason'].includes(k))
      && 'result' in e.fields && 'order' in e.fields
  })
  && events.filter((e) => e.kind === 'order' && e.fields.from && e.fields.to).length > 0,
  [...new Set(events.filter((e) => e.kind === 'order').map((e) => keySet(e.fields)))])

ok('③+ `src=环名#n` 只在 `detail` 尾部、**不在 fields 里**（后端 `add()` 的追加位置）',
  events.every((e) => new RegExp(` src=${e.kind}#\\d+$`).test(e.detail)
    && !('src' in e.fields)),
  events.slice(0, 2).map((e) => e.detail))

const stockEv = events.filter((e) => e.kind === 'stock')
ok('③+ `remaining` 是**顶层键**且只有库存扣减的 ok / insufficient 两档有值（error 档恒 null）',
  stockEv.every((e) => (e.fields.result === 'ok' || e.fields.result === 'insufficient'
    ? Number.isInteger(e.remaining) && e.remaining >= 0
    : e.remaining === null))
  && events.filter((e) => e.kind !== 'stock').every((e) => e.remaining === null),
  {
    扣减有剩余: stockEv.filter((e) => e.fields.result === 'ok' && e.remaining !== null).length,
    不足有剩余: stockEv.filter((e) => e.fields.result === 'insufficient' && e.remaining !== null).length,
    错误为null: stockEv.filter((e) => e.fields.result === 'error' && e.remaining === null).length,
  })

// ── ③ 三页帧形状（与后端逐键对齐）────────────────────────────────────────
const keysOf = (o) => Object.keys(o).sort().join(',')
ok('③ events 帧键', keysOf(ev[0]) === 'events,mq,rate,seq,traces,ts', keysOf(ev[0]))
ok('③ events 帧 mq=false（演示事件不落库，黄条说的是实话）', ev[0].mq === false)
ok('③ events 帧 traces 为空数组', Array.isArray(ev[0].traces) && ev[0].traces.length === 0)

const or = run('orders')
ok('③ orders 帧键', keysOf(or[0]) === 'batchCounts,counts,moves,orders,seq,ts', keysOf(or[0]))
ok('③ orders 帧 batchCounts=null（无批次）', or[0].batchCounts === null)
const ST = ['waitPay', 'waitShip', 'waitReceive', 'completed', 'cancelled']
ok('③ counts 含五态 + total', ST.every((k) => typeof or[0].counts[k] === 'number')
  && typeof or[0].counts.total === 'number', keysOf(or[0].counts))
const moves = or.slice(0, 12).flatMap((f) => f.moves)
ok('③ move 行键', keysOf(moves[0]) === 'batchId,from,orderId,to,ts', keysOf(moves[0]))
ok('③ move.from 是枚举名或空串（页面按枚举名翻译）',
  moves.every((m) => m.from === '' || ['CREATED', 'PAID', 'SHIPPED'].includes(m.from)),
  [...new Set(moves.map((m) => m.from))].join('/'))
const orders = or[0].orders
ok('③ order 行键', ['orderId', 'customerId', 'merchantId', 'amount', 'quantity', 'product',
  'createdAt', 'state', 'step', 'cancelled'].every((k) => k in orders[0]), keysOf(orders[0]))
ok('③ order.state 是五态 key', orders.every((o) => ST.includes(o.state)))

const me = run('metrics')
ok('③ metrics 帧键', keysOf(me[0]) === 'cache,gauges,health,locks,protections,requests,seq,stock,ts',
  keysOf(me[0]))
ok('③ requests 键', keysOf(me[0].requests) === 'clientErrorRate,count,errorRate,p99Ms,qps',
  keysOf(me[0].requests))
ok('③ health 自带「模拟日常」自报', me[0].health.reasons.join('').includes('模拟日常'),
  me[0].health.reasons.join(''))
ok('③ protections 五条且带 label', me[0].protections.length === 5
  && me[0].protections.every((p) => typeof p.label === 'string' && p.label.length > 0))
ok('③ stock 三行且 ratio ∈ [0,1]', me[0].stock.rows.length === 3
  && me[0].stock.rows.every((r) => r.ratio >= 0 && r.ratio <= 1))

// ── ④ 复演 ──────────────────────────────────────────────────────────────
// ⚠️ 比的是**除时刻之外**的一切：`ts` 是真实墙钟（这是对的，演示数据也要显示「刚刚」），
//    去掉它才谈得上「同一种子 ⇒ 同一条剧情」。
// ⚠️ 帧根上的 `ts` 与**每条事件里**的 `ts` 都要去掉 —— 第一版只去了帧根那个，于是
//    「每拍第 11 帧差 1 毫秒」被当成剧情不一致（差的是时刻，不是剧情）。
const stripTs = (v) => {
  if (Array.isArray(v)) return v.map(stripTs)
  if (v && typeof v === 'object') {
    return Object.fromEntries(Object.entries(v)
      .filter(([k]) => k !== 'ts')
      .map(([k, val]) => [k, stripTs(val)]))
  }
  return v
}
const strip = (frames) => JSON.stringify(stripTs(frames))
const a = run('events', 20)
const b = run('events', 20)
const same = strip(a) === strip(b)
ok('④ 同一场演示逐帧一致（时刻除外）', same)
if (!same) {
  // 失败时把**第一处不同**打出来（只报「不一致」等于没说哪里不一致）。
  console.log(`     拍数 A=${a.length} B=${b.length}`)
  // ⚠️ `strip([f])` 返回的是**字符串**（`JSON.stringify` 的结果）—— 别再 `[0]`，那是取第一个字符。
  const A = a.map((f) => strip([f]))
  const B = b.map((f) => strip([f]))
  for (let i = 0; i < Math.max(A.length, B.length); i += 1) {
    if (A[i] !== B[i]) {
      console.log(`     第 ${i} 拍：\n      A ${String(A[i]).slice(0, 500)}\n      B ${String(B[i]).slice(0, 500)}`)
      break
    }
  }
}

console.log(`\n${fail === 0 ? '全部通过' : fail + ' 条未通过'}`)
process.exit(fail === 0 ? 0 : 1)
