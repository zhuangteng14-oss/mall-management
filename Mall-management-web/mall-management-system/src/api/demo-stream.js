// src/api/demo-stream.js —— 「模拟日常」**演示模式的假数据源**（C3 块 7 收尾 ②）。
//
// 【它是什么】一条**假的三页 SSE**：对外形状与 `api/observe.js` 的 `openMetricsStream` /
//   `openOrderStream` / `openEventStream` **逐字一样**（收 `{ onOpen, onFrame, onLost }`、
//   回一个 `{ close() }`），但**一个字节都不出网** —— 没有请求、没有连接、没有后端参与。
//
// 【为什么和真身放在 `api/` 下】三页里「换数据源」的那**一处**就是 `openXxxStream(...)` 那一行
//   （见三页的 `connect()`）⇒ 替身与真身摆在一起，切换时只换那一行的调用方。
//   ⚠️ 本文件**不 import `api/observe.js`**，也不碰 `endpoints.js`：演示模式的判据之一是
//   「纯前端、不入库」，只要它引了任何一个端点封装，"不入库" 就只剩一句口头承诺。
//
// 【三条硬口径（用户 2026-10-15 定，逐条对应）】
//   ① **频率 5–20 条/秒**：每拍 1 秒（与真流同节拍），一拍投 `DEMO_MIN_PER_TICK ~ DEMO_MAX_PER_TICK`
//      条，且**分布有偏** —— 偏小（`Math.pow(rnd, DEMO_SKEW)` 把均匀分布压向低端），
//      故实测均值约 11 条/秒而不是 12.5（均匀分布才是 12.5）。
//   ② **总量 ≤ {@link DEMO_TOTAL}**：投放总数封顶 500。⚠️ **放完之后不是断流**：帧照推、
//      条数变 0（事件页的「本拍速率」自己落到 0，指标页 QPS 落到 0）—— 「日常安静下来」与
//      「连接断了」在页面上必须看得出差别（后者会亮那条红条，演示数据不该冒用那个信号）。
//   ③ **纯前端、不入库**：见上一段；页面上还必须**看得见**这是假数据 —— 那个 `DemoModeToggle`
//      与指标页 `health.reasons` 里那句「模拟日常」就是干这个的（不靠用户记得）。
//
// 【与真数据的差别：哪些地方**故意不一样**，别让演示把结论带偏】
//   · `batchId` 一律 `null`：模拟的**不是演练**（演练才有批次）。所以订单页那条「本批次单数」
//     小字在演示模式下**不出现** —— 真演练才有的东西，演示里不该凭空长出来（`batchCounts`
//     在后端是「本批次落的单」，前端算不出一个诚实的假值）。
//   · `mq` 一律 `false`：**这一条是有意的谎** —— 不，是**实话**：演示事件的确只在内存里、
//     不落库、不进队列，而日志页那条黄条说的正是「八环的事件都只在进程内存里…不落库」
//     ⇒ 让它亮着**比压住它诚实**。演示模式**不改**这台后端真实连没连上 MQ，也不该假装知道。
//   · 事件**不进任何环、不落库、不发 MQ**：关掉页面就没了，与「刷新一下还在」的真数据不同。
//   · `seq` 从 1 起、只在本场演示内递增（真流那个是**进程级**的）。
//
// 【随机会不会「每次不一样」】不会：种子是写死的 `DEMO_SEED` ⇒ **同一场演示每次逐帧一致**。
//   理由是要能背（答辩前可以照着走一遍，数与图都对得上）。要换一套就改这一个常量。

import { reactive } from 'vue'
import { PROTECTIONS, PROTECTION_LABEL } from '../config/labels/observe-labels'

/** 一场「模拟日常」投放的活动条数上限（用户 2026-10-15 定的 500）。 */
export const DEMO_TOTAL = 500
/** 每拍（1 秒）至少 / 最多投几条（用户 2026-10-15 定的 5–20）。 */
export const DEMO_MIN_PER_TICK = 5
export const DEMO_MAX_PER_TICK = 20
/** 偏差指数：> 1 ⇒ 偏小（`pow` 把均匀分布推向左端，均值从 12.5 落到约 11）。 */
const DEMO_SKEW = 1.8
/** 帧节拍：与三条真流一致（`1 秒 1 帧`）。 */
const TICK_MS = 1000
/** 写死的随机种子 ⇒ 同一场演示可复演（理由见文件头最后一段）。 */
const DEMO_SEED = 20261015

/**
 * 演示模式的**全局开关**（三个页面共用一份 —— 它们不是三个模式，是同一个模式的三块面板）。
 *
 * ⚠️ 模块级 `reactive` 单例：页与页之间切换（`指标 → 状态 → 日志`）时这个开关**不重置**，
 *    否则「切过去就自己变回真实数据」会让演示中途穿帮。
 * ⚠️ `emitted` 由**当前那条**演示流每拍更新 —— 同时只可能有一个页面在跑，故它不会打架；
 *    但它**不是**累计到 500 就永久封顶的全局账（换页重新开流会从 0 起，这是对的：
 *    每一场演示是独立的一场）。
 */
export const demo = reactive({
  /** true = 页面读的是本文件的假数据；false = 读后端真流。 */
  on: false,
  /** 本场已投放的条数（≤ `DEMO_TOTAL`）。 */
  emitted: 0,
  /** 本场正在喂哪一页（`metrics` / `orders` / `events`；未开流时为空串）。 */
  kind: '',
})

/** 切数据源（`DemoModeToggle` 调这一个；三页各自 `watch` 它并重连）。 */
export function setDemoMode(on) {
  demo.on = !!on
}

/* ══════════════════════════════════════════════════════════════════════════
   一、随机与加权（分布有偏的实现）
   ══════════════════════════════════════════════════════════════════════════ */

/** mulberry32：一个小而稳的 PRNG（写死种子即可复演；不用 `Math.random` 就是为了这一点）。 */
function mulberry32(seed) {
  let a = seed >>> 0
  return function rnd() {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 从 `[min, max]` 里取一个整数（含两端）。 */
function intBetween(rnd, min, max) {
  return min + Math.floor(rnd() * (max - min + 1))
}

/** 从数组里等概率取一个。 */
function pickOne(rnd, arr) {
  return arr[Math.floor(rnd() * arr.length)]
}

/**
 * 从「条目数组」里**按权重**取一条（`w` 缺省当 1）。
 * ⚠️ 这是本文件「分布有偏」的**唯一**实现点：条目的 `w` 就是它的相对频率。
 */
function pickWeighted(rnd, entries) {
  let total = 0
  for (const e of entries) total += e.w === undefined ? 1 : e.w
  let r = rnd() * total
  for (const e of entries) {
    r -= e.w === undefined ? 1 : e.w
    if (r < 0) return e
  }
  return entries[entries.length - 1]
}

/* ══════════════════════════════════════════════════════════════════════════
   二、八类事件的「日常长什么样」
   ⚠️ 严重度**跟着标题走**（每条标题自带 `sev`），不另抽一次 —— 这是后端 `EventBroadcaster.severityOf`
      那条判据的镜像：`error` ← 失败；`warn` ← 被挡/被拒/超时/降级/不足；其余 `info`。
      于是「绝大多数是 info」这件事由**标题的权重**自然给出（见每类第一、二条的 `w` 偏大）。
   ⚠️ `objectType` / `objectId` 的口径见 `observe-labels.js` 的 `OBJECT_FIELD_LABEL`：八类里
      这一格指的**不是同一种东西**（`cache` 是键、`order` 是订单号、`sensitive` 是命中的词…）。
      `objectType` 只对 `audit` 有意义（`TARGET_TYPE_LABEL` 的域前缀），其余留空串。
   ══════════════════════════════════════════════════════════════════════════ */

/** 每类多少条（相对权重）——**偏日常**：缓存与订单占大头，身份/敏感词是零星。 */
const KIND_WEIGHT = [
  { kind: 'cache', w: 30 },
  { kind: 'order', w: 24 },
  { kind: 'lock', w: 13 },
  { kind: 'audit', w: 10 },
  { kind: 'stock', w: 8 },
  { kind: 'notify', w: 7 },
  { kind: 'sensitive', w: 5 },
  { kind: 'identity', w: 3 },
]

/** 类 → 标题池（`w` 是相对频率、`sev` 是严重度）+ 对象池。 */
const KIND_CONTENT = {
  cache: {
    ids: ['goods:1021', 'goods:1044', 'sku:GD-101-9552', 'coupon:51', 'seckill:3'],
    titles: [
      { w: 20, sev: 'info', title: 'L1 命中' },
      { w: 10, sev: 'info', title: 'L2 命中' },
      { w: 5, sev: 'info', title: '未命中，回源数据库' },
      { w: 2, sev: 'warn', title: '缓存预热超时' },
    ],
  },
  lock: {
    ids: ['t_order', 't_goods', 't_seckill_stock', 't_coupon'],
    titles: [
      { w: 18, sev: 'info', title: '获得行锁' },
      { w: 6, sev: 'info', title: '锁等待 120ms' },
      { w: 2, sev: 'warn', title: '锁等待超时，已放弃' },
      { w: 1, sev: 'error', title: '死锁，已回滚重试' },
    ],
  },
  order: {
    ids: [], // 订单号由世界现编（见 `nextOrderId`）
    titles: [
      { w: 16, sev: 'info', title: '订单创建（待付款）' },
      { w: 12, sev: 'info', title: '支付成功（待发货）' },
      { w: 9, sev: 'info', title: '发货成功（待收货）' },
      { w: 8, sev: 'info', title: '确认收货（已完成）' },
      { w: 2, sev: 'warn', title: '订单取消' },
    ],
  },
  stock: {
    ids: ['GD-101-9552', 'GD-103-5196', 'GD-102-3310'],
    titles: [
      { w: 12, sev: 'info', title: '库存扣减' },
      { w: 3, sev: 'warn', title: '库存水位偏低' },
      { w: 2, sev: 'warn', title: '库存不足，已拒绝' },
      { w: 1, sev: 'error', title: '超卖拦截' },
    ],
  },
  audit: {
    // ⚠️ `objectType` 走 `TARGET_TYPE_LABEL` 的域前缀（审计那类才带它）。
    ids: [
      ['goods', '20'],
      ['order', '7'],
      ['sensitive_word', '3'],
      ['report', '11'],
    ],
    titles: [
      { w: 10, sev: 'info', title: '商品更新' },
      { w: 6, sev: 'info', title: '通知发送' },
      { w: 4, sev: 'info', title: '敏感词新增' },
      { w: 3, sev: 'info', title: '举报处理' },
    ],
  },
  notify: {
    ids: ['u-1001', 'u-1007', 'u-1024'],
    titles: [
      { w: 10, sev: 'info', title: '通知已投递' },
      { w: 3, sev: 'warn', title: '通知投递失败，已重试' },
      { w: 1, sev: 'error', title: '通知模板缺失' },
    ],
  },
  sensitive: {
    ids: ['代购', '刷单', '假货'],
    titles: [
      { w: 6, sev: 'warn', title: '敏感词命中，已拦截' },
      { w: 4, sev: 'info', title: '敏感词命中（仅告警）' },
    ],
  },
  identity: {
    ids: ['/api/order/create', '/api/goods/list', '/api/seckill/1'],
    titles: [
      { w: 8, sev: 'info', title: '身份放行' },
      { w: 1, sev: 'error', title: '身份校验失败' },
    ],
  },
}

/* ══════════════════════════════════════════════════════════════════════════
   三、世界（一场演示一份）
   ══════════════════════════════════════════════════════════════════════════ */

/** 订单页那五态（与后端 `OrderDao.countOrderStates` 的判据链同一套 key）。 */
const STATE_KEYS = ['waitPay', 'waitShip', 'waitReceive', 'completed', 'cancelled']
/** 一张单在状态机里的推进顺序（枚举名 —— 与后端 `moves[].from/to` 同一套词汇）。 */
const CHAIN = ['CREATED', 'PAID', 'SHIPPED', 'COMPLETED']
/** 本场演示在内存里最多记多少张单（页面自己还会按 `LIST_CAP` 截）。 */
const WORLD_ORDERS = 40

const PRODUCTS = ['无糖气泡水 330ml', '智能手环 5 代', '棉麻短袖 T 恤', '儿童积木套装', '速溶黑咖啡 100 条']

function newWorld() {
  const rnd = mulberry32(DEMO_SEED)
  return {
    rnd,
    seq: 0,          // 帧序号
    // ⚠️ 事件序号**从一个高位起**（不是 1）：日志页按 `seq` 去重，而那个 Set 跨数据源不清
    //    ⇒ 若演示从 1 起，一台**刚重启**的后端（全局 seq 还很小）自己的事件会被当成重复丢掉。
    //    取 900000 是个不小的数：真流在演示场景里跑几轮就到十万级，这里留出一个量级的余量。
    evSeq: 900000,
    emitted: 0,      // 本场已投放的活动条数（≤ DEMO_TOTAL）
    reqTotal: 0,     // 本场累计请求数（指标页）
    orderNo: 1040,   // 订单号递增器
    orders: [],      // 会话内建过的单（新→旧）
    counts: { waitPay: 14, waitShip: 9, waitReceive: 6, completed: 41, cancelled: 4 },
  }
}

/** 本拍投几条：先按有偏分布取，再被剩余额度截断。 */
function batchSize(w) {
  const left = DEMO_TOTAL - w.emitted
  if (left <= 0) return 0
  const raw = DEMO_MIN_PER_TICK
    + Math.floor(Math.pow(w.rnd(), DEMO_SKEW) * (DEMO_MAX_PER_TICK - DEMO_MIN_PER_TICK + 1))
  return Math.min(left, Math.min(DEMO_MAX_PER_TICK, raw))
}

function nextOrderId(w) {
  w.orderNo += 1
  return `SO-20261015-${String(w.orderNo).padStart(4, '0')}`
}

/** 库里那种无时区文本（`CreateTime` 一列的形状），不是 ISO —— 页面对这一格是原文直出。 */
function dbTime(ms) {
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ══════════════════════════════════════════════════════════════════════════
   四、造事件 / 造订单动作（两页共用同一批「活动条数」）
   ══════════════════════════════════════════════════════════════════════════ */

/** 造一条事件（`events` 帧里的 `events[]` 一行）。 */
function makeEvent(w, nowMs) {
  const { kind } = pickWeighted(w.rnd, KIND_WEIGHT)
  const c = KIND_CONTENT[kind]
  const t = pickWeighted(w.rnd, c.titles)
  w.evSeq += 1
  let objectType = ''
  let objectId
  if (kind === 'audit') {
    const pair = pickOne(w.rnd, c.ids)
    objectType = pair[0]
    objectId = pair[1]
  } else if (kind === 'order') {
    objectId = w.orders.length ? w.orders[0].orderId : nextOrderId(w)
  } else {
    objectId = c.ids.length ? pickOne(w.rnd, c.ids) : ''
  }
  return {
    seq: w.evSeq,
    ts: nowMs,
    kind,
    severity: t.sev,
    title: t.title,
    detail: `${kind} · 模拟日常`,
    key: `${kind}:${objectId}`,
    objectType,
    objectId,
    // ⚠️ 每 4 条里 3 条挂得上链路（真库里也是有的有、有的没有）—— 全挂上反而不像真的。
    traceId: w.rnd() < 0.75 ? `t${(w.evSeq % 997).toString(16).padStart(3, '0')}` : null,
    batchId: null, // 演示不是演练 ⇒ 没有批次（理由见文件头）
  }
}

/** 造一条订单动作（`orders` 帧里的 `moves[]` 一行），并把它记进世界里。 */
function makeMove(w, nowMs) {
  const alive = w.orders.filter((o) => !o.cancelled && o.step < CHAIN.length - 1)
  // 六成推一张已有的单、四成开新单（偏「有新单在进来」的日常）。
  if (!alive.length || w.rnd() < 0.4) {
    const order = {
      orderId: nextOrderId(w),
      customerId: `C${intBetween(w.rnd, 1001, 1099)}`,
      merchantId: `M${intBetween(w.rnd, 201, 209)}`,
      amount: (intBetween(w.rnd, 1990, 29900) / 100).toFixed(2),
      quantity: intBetween(w.rnd, 1, 3),
      product: pickOne(w.rnd, PRODUCTS),
      createdAt: dbTime(nowMs),
      step: 0,
      cancelled: false,
    }
    w.orders.unshift(order)
    if (w.orders.length > WORLD_ORDERS) w.orders.length = WORLD_ORDERS
    w.counts.waitPay += 1
    // 建档那一步 `from` 为空串（后端 `action=create` 那一格，页面的「时间线」会把它滤掉）。
    return { orderId: order.orderId, from: '', to: 'CREATED', ts: nowMs, batchId: null }
  }
  const order = alive[Math.floor(w.rnd() * alive.length)]
  const from = CHAIN[order.step]
  // 一步走不完：多数只推一格，偶尔跳一格（后端也允许跨步，页面只在画了边的那些对上亮边）。
  const advance = w.rnd() < 0.85 ? 1 : 2
  const cancelled = order.step === 0 && w.rnd() < 0.25
  const to = cancelled ? 'CANCELLED' : CHAIN[Math.min(CHAIN.length - 1, order.step + advance)]
  if (cancelled) {
    order.cancelled = true
    w.counts.waitPay -= 1
    w.counts.cancelled += 1
  } else {
    order.step = CHAIN.indexOf(to)
    w.counts[STATE_KEYS[order.step]] += 1
    w.counts[STATE_KEYS[CHAIN.indexOf(from)]] -= 1
  }
  return { orderId: order.orderId, from, to, ts: nowMs, batchId: null }
}

/* ══════════════════════════════════════════════════════════════════════════
   五、三页各自的帧（形状逐字对齐后端，见 `EventStreamService.frame()`
       / `OrderStreamService.frame()` / `MetricsSnapshotService`）
   ══════════════════════════════════════════════════════════════════════════ */

function eventsFrame(w, nowMs, n) {
  const events = []
  for (let i = 0; i < n; i += 1) events.push(makeEvent(w, nowMs))
  w.seq += 1
  const counts = {}
  const severity = { info: 0, warn: 0, error: 0 }
  for (const e of events) {
    counts[e.kind] = (counts[e.kind] || 0) + 1
    severity[e.severity] += 1
  }
  return {
    seq: w.seq,
    ts: nowMs,
    rate: n,
    mq: false, // 演示事件确实不落库 —— 那句话是真的（理由见文件头）
    events,
    traces: [], // 演示不造请求链路（日志页那张抽屉留空，不去编一条假的调用链）
  }
}

function ordersFrame(w, nowMs, n) {
  const moves = []
  for (let i = 0; i < n; i += 1) moves.push(makeMove(w, nowMs))
  w.seq += 1
  const counts = { ...w.counts }
  counts.total = STATE_KEYS.reduce((a, k) => a + (counts[k] || 0), 0)
  return {
    seq: w.seq,
    ts: nowMs,
    counts,
    batchCounts: null, // 演示不是演练 ⇒ 页面那条「本批次单数」不出现（理由见文件头）
    orders: w.orders.slice(0, WORLD_ORDERS).map((o) => ({
      orderId: o.orderId,
      customerId: o.customerId,
      merchantId: o.merchantId,
      amount: o.amount,
      quantity: o.quantity,
      product: o.product,
      createdAt: o.createdAt,
      state: o.cancelled ? 'cancelled' : STATE_KEYS[o.step],
      step: o.step,
      cancelled: o.cancelled,
    })),
    moves,
  }
}

function metricsFrame(w, nowMs, n) {
  const rnd = w.rnd
  w.seq += 1
  w.reqTotal += n
  const hitRate = Math.round((0.86 + rnd() * 0.09) * 1000) / 1000
  const lockTimeouts = rnd() < 0.12 ? intBetween(rnd, 1, 3) : 0
  return {
    seq: w.seq,
    ts: nowMs,
    requests: {
      count: n,
      qps: n,
      p99Ms: intBetween(rnd, 38, 260),
      errorRate: Math.round(rnd() * 150) / 10000,      // 0 ~ 1.5%
      clientErrorRate: Math.round(100 + rnd() * 500) / 10000, // 1% ~ 6%（日常的 4xx 大多是「没登录」这类）
    },
    cache: { hitRate },
    locks: lockTimeouts ? [{ lock: 't_order', timeouts: lockTimeouts }] : [],
    gauges: { l2Up: true, lockUp: true },
    // ⚠️ 健康度照后端那套形状给（`level` / `label` / `reasons`），且**在 `reasons` 里明说**
    //    这是模拟数据 —— 那块大字写着「健康度（后端算）」，演示模式下必须自报家门，
    //    否则一屏「健康」会被当成这台后端真的健康。
    health: {
      level: 'ok',
      label: '健康',
      reasons: [`模拟日常（假数据，不入库）· L1 命中率 ${(hitRate * 100).toFixed(1)}%`],
    },
    protections: PROTECTIONS.map((tag) => ({
      tag,
      label: PROTECTION_LABEL[tag],
      value: rnd() < 0.7 ? intBetween(rnd, 0, 3) : 0,
    })),
    stock: {
      available: true,
      rows: [
        { sku: 'DEMO-GD-101', goodName: '模拟商品 · 气泡水', axisName: '330ml', stock: 42 + intBetween(rnd, -6, 6), baseline: 120, ratio: null },
        { sku: 'DEMO-GD-103', goodName: '模拟商品 · 智能手环', axisName: '标准版', stock: 88 + intBetween(rnd, -8, 8), baseline: 120, ratio: null },
        { sku: 'DEMO-GD-107', goodName: '模拟商品 · 积木套装', axisName: '大盒', stock: 12 + intBetween(rnd, -4, 4), baseline: 120, ratio: null },
      ],
    },
  }
}

// 库存水位：`ratio` 由 `stock / baseline` 现算（后端也是在 `MetricsSnapshotService` 里算的，
// 这里照算一遍只为让那三条柱子的颜色分档（<20% 红 / <50% 黄 / 其余绿）真的会分档）。
function withRatio(frame) {
  for (const r of frame.stock.rows) {
    r.ratio = Math.max(0, Math.min(1, r.stock / r.baseline))
  }
  return frame
}

const BUILDERS = {
  events: eventsFrame,
  orders: ordersFrame,
  metrics: (w, nowMs, n) => withRatio(metricsFrame(w, nowMs, n)),
}

/* ══════════════════════════════════════════════════════════════════════════
   六、对外的那个「流」
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * 开一条演示流。**接口与 `api/observe.js` 的三条 `openXxxStream` 一致** —— 三页因此只要把
 * 那个调用点换成三元式即可（见各页 `connect()`）。
 *
 * @param {'metrics'|'orders'|'events'} kind 喂哪一页（决定帧形状）
 * @param {{onOpen?: Function, onFrame?: Function, onLost?: Function}} handlers
 *        `onLost` **永远不会被调用**：演示流不会断（它没有连接）—— 留着这个形参是为了让
 *        调用点两边长得一样，切回真流时不用改结构。
 * @returns {{close: Function}} 与真流同形；`close()` 之后不再有任何回调。
 */
export function openDemoStream(kind, handlers = {}) {
  const w = newWorld()
  demo.kind = kind
  demo.emitted = 0
  const build = BUILDERS[kind] || BUILDERS.events
  let closed = false

  const tick = () => {
    if (closed) return
    const nowMs = Date.now()
    const n = batchSize(w)
    w.emitted += n
    demo.emitted = w.emitted
    // ⚠️ 额度放完之后**继续推帧**（`n = 0`）：页面因此看上去是「安静下来了」而不是「断了」。
    if (handlers.onFrame) handlers.onFrame(build(w, nowMs, n))
  }

  if (handlers.onOpen) handlers.onOpen()
  tick()
  const timer = setInterval(tick, TICK_MS)

  return {
    close() {
      closed = true
      clearInterval(timer)
    },
  }
}
