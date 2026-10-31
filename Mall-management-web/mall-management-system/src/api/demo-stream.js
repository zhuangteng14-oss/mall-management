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
      那条判据的镜像：`error` ← 真失败；`protect` ← 防护拦截（身份鉴权拒绝等）；
      `warn` ← 真正的业务失败 / 超时 / 降级 / 不足；其余 `info`。
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

/* ══════════════════════════════════════════════════════════════════════════
   ★ 2026-10-17（3a.3 收尾）：假帧补上 3a.3 新增的三样 —— `actor` / `durationUs` / `fields`。
   ⚠️ **形状逐字对齐真帧**（`EventStreamService.frame()` 的 `eventRow()` 十五键），值是编的：
      · `actor` 是 `{id, role}` 对象或 **null**（无身份），不是两个平铺字段；
      · `durationUs` 单位是**微秒**（亚毫秒的照实写 µs，不四舍五入成 0 ms）；
      · `fields` 的键名**逐环照抄后端 `EventBroadcaster` 的 `Emit`**
        （cache/lock 是 cache|lock/result/key，order 是 action/result/from/to/order，
         stock 是 kind/sku/qty/result，audit 是 actor/action/reason，
         notify 是 to/event/reason，sensitive 是 scene/hit/total，identity 是 subject/reason）；
      · **warn / error 档必须带 `fields.reason`**（3a.3 后续）：成功放行的 info 档不写这个键
        （与后端 `EventBroadcaster.putReason` 的「空即无」同口径）；
      · `remaining` 仍是**顶层键**（只有库存扣减档有值，扣得动/扣不动两档），**不塞进 fields**
        —— 真帧就是这么给的（见 `StockMetrics.StockEvent#remaining()`）；
      · `src=环名#源seq` 只在 `detail` 尾部，**不进 fields**（后端 `add()` 的追加位置）。
   ⚠️ **诚实边界**：这里的 `actor` 是编出来的演示身份，不是这台后端的真实会话。
      默认 profile 下真流有 actor；dev/a3 下 `app.auth.enabled=false`，真流恒为 null。
      演示模式出现 actor，是为了让「谁做的」这一要素在模拟日常里也看得见，
      **不代表**这台后端登录过谁（页面上「模拟日常」那枚开关仍在自报家门）。
   ══════════════════════════════════════════════════════════════════════════ */

/** 缓存区 8 个（有界 —— `@Cacheable(cacheNames=…)` 的去重值，见 `api-labels.js` 的 `CACHE_AREA_LABEL`）。
 *  每项 = [区名, 该区一个像样的键]；演示的 `objectId` 就是键。 */
const CACHE_AREAS = [
  ['userPublic', 'adminC3r00t'],
  ['goodsHome', 'page:1'],
  ['goodstype', 'all'],
  ['goodsByMerchant', 'M205'],
  ['goodsPrice', 'GD-101-9552'],
  ['goodsCodes', 'GD-101-9552'],
  ['friendList', 'u-1001'],
  ['friendRequests', 'u-1007'],
]

/** 锁名 5 把（`DistributedLockService.LockName` 的 tag，有界 —— 别写不存在的值）。 */
const LOCK_NAMES = ['order-add', 'order-pay', 'order-receive', 'order-delivery', 'friend-add']

/** 审计的发起人池：管理员 / 商家 / 客户各一（角色号即 `login.Role`：1 客户 / 2 商家 / 3 管理员）。 */
const AUDIT_ACTORS = [
  ['adminC3r00t', 3],
  ['M205', 2],
  ['C1086', 1],
]

/** 类 → 标题池（`w` 是相对频率、`sev` 是严重度）+ 各环自己的业务参数。
 *  ⚠️ `dur` = `durationUs` 的取值区间（**微秒**；缺 = 这一档没有有意义的耗时，落 null）。
 *     `null` 是**判定**不是漏填：真帧里也不是每条事件都测得到耗时。 */
const KIND_CONTENT = {
  cache: {
    titles: [
      { w: 20, sev: 'info', title: 'L1 命中', result: 'hit', dur: [1, 8] },
      { w: 10, sev: 'info', title: 'L2 命中', result: 'hit', dur: [80, 400] },
      { w: 5, sev: 'info', title: '未命中，回源数据库', result: 'miss', dur: [300, 1500] },
      { w: 2, sev: 'warn', title: '缓存预热超时', result: 'timeout', reason: 'L2 降级冷却中：本次未访问 Redis', dur: [2000, 8000] },
    ],
  },
  lock: {
    titles: [
      { w: 18, sev: 'info', title: '获得行锁', result: 'acquired', dur: [20, 300] },
      { w: 6, sev: 'info', title: '锁等待 120ms', result: 'timeout', dur: [120000, 120000] },
      { w: 2, sev: 'warn', title: '锁等待超时，已放弃', result: 'timeout', reason: '取锁超时：等待 5 s 未获得', dur: [3000000, 5000000] },
      { w: 1, sev: 'error', title: '死锁，已回滚重试', result: 'error', reason: '死锁重试失败：第 3 次尝试仍撞上死锁', dur: [1000000, 3000000] },
    ],
  },
  order: {
    titles: [
      { w: 16, sev: 'info', title: '订单创建（待付款）', action: 'create', result: 'ok', to: 'CREATED', dur: [300, 1200] },
      { w: 12, sev: 'info', title: '支付成功（待发货）', action: 'pay', result: 'ok', from: 'CREATED', to: 'PAID', dur: [500, 2500] },
      { w: 9, sev: 'info', title: '发货成功（待收货）', action: 'deliver', result: 'ok', from: 'PAID', to: 'SHIPPED', dur: [400, 2000] },
      { w: 8, sev: 'info', title: '确认收货（已完成）', action: 'receive', result: 'ok', from: 'SHIPPED', to: 'COMPLETED', dur: [300, 1500] },
      { w: 2, sev: 'warn', title: '订单取消', result: 'cancelled', reason: 'user_cancel', dur: [200, 1000] },
    ],
  },
  stock: {
    ids: ['GD-101-9552', 'GD-103-5196', 'GD-102-3310'],
    titles: [
      // `remaining` = 扣减那一刻的剩余件数：真帧里 `deduct` 的 ok / insufficient 两档有值，
      // error 与 release 恒 null（口径见 `StockMetrics.StockEvent#remaining()`）。
      { w: 12, sev: 'info', title: '库存扣减', result: 'ok', remaining: [20, 90], dur: [200, 1200] },
      { w: 3, sev: 'warn', title: '库存水位偏低', result: 'ok', remaining: [1, 8], dur: [200, 1200] },
      { w: 2, sev: 'warn', title: '库存不足，已拒绝', result: 'insufficient', remaining: [0, 0], dur: [100, 600] },
      { w: 1, sev: 'error', title: '超卖拦截', result: 'error', reason: '扣减异常：影响行数为 2（SkuCode 可能非唯一）', remaining: null, dur: [100, 800] },
    ],
  },
  audit: {
    // ⚠️ `target` = [TargetType, TargetId]；`objectType` 走 `TARGET_TYPE_LABEL` 的域前缀（审计那类才带它）。
    titles: [
      { w: 10, sev: 'info', title: '商品更新', action: 'goods.update', target: ['goods', '20'], dur: [100, 900] },
      { w: 6, sev: 'info', title: '通知发送', action: 'notification.send', target: ['notification', '5'], dur: [50, 500] },
      { w: 4, sev: 'info', title: '敏感词新增', action: 'sensitive_word.add', target: ['sensitive_word', '3'], dur: [50, 400] },
      { w: 3, sev: 'info', title: '举报处理', action: 'report.handle', target: ['report', '11'], dur: [100, 700] },
    ],
  },
  notify: {
    ids: ['u-1001', 'u-1007', 'u-1024'],
    titles: [
      { w: 10, sev: 'info', title: '通知已投递', result: 'sent', to: 'customer', event: 'order.notice', dur: [50, 400] },
      { w: 3, sev: 'warn', title: '通知投递失败，已重试', result: 'failed', to: 'merchant', event: 'goods.audit.passed', reason: 'SMTP 超时', dur: [1000, 3000] },
      { w: 1, sev: 'error', title: '通知模板缺失', result: 'failed', to: 'customer', event: 'comment.reply', reason: '模板不存在', dur: [1, 20] },
    ],
  },
  sensitive: {
    ids: ['代购', '刷单', '假货'],
    titles: [
      // 2026-10-17 用户最终口径：拒收 / 打码都是防护层成功防御 ⇒ 紫（protect），不进业务失败率。
      { w: 6, sev: 'protect', title: '敏感词命中，已拦截', result: 'rejected', dur: [20, 300] },
      { w: 4, sev: 'protect', title: '敏感词命中（仅告警）', result: 'masked', dur: [20, 300] },
    ],
  },
  identity: {
    ids: ['/api/order/create', '/api/goods/list', '/api/seckill/1'],
    titles: [
      { w: 8, sev: 'info', title: '身份放行', result: 'passed', dur: [10, 100] },
      { w: 1, sev: 'protect', title: '身份校验失败', result: 'rejected', reason: '令牌过期', dur: [10, 100] },
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
    // 每个环自己的「源 seq」—— 只喂 `detail` 尾部那格 `src=环名#n`（真帧由各源环的计数器给）。
    // ⚠️ 按环分开计数（不是全场一个），否则九类会共用一条递增线，读起来不像九条源环。
    srcSeqs: {},
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

/** 某个环的源 seq（`detail` 尾部那格 `src=环名#n` 用它）。按环独立、0 起 —— 同真帧。 */
function nextSrcSeq(w, kind) {
  const cur = w.srcSeqs[kind]
  const next = cur === undefined ? 0 : cur + 1
  w.srcSeqs[kind] = next
  return next
}

/** 业务键：优先用会话里最新那张单（订单环与锁环共用同一个「世界」）。 */
function orderRef(w) {
  return w.orders.length ? w.orders[0].orderId : nextOrderId(w)
}

/** 编一个演示身份对象。角色号与 `login.Role` 同口径：1 客户 / 2 商家 / 3 管理员。 */
function actorOf(id) {
  const s = String(id)
  if (s === 'adminC3r00t') return { id: s, role: 3 }
  return { id: s, role: s.startsWith('M') ? 2 : 1 }
}

/** 这条事件挂在谁身上。`null` = 没有身份（定时任务 / 消息线程；真帧同样允许）。 */
function makeActor(w, kind) {
  // 九成挂在请求线程上；剩下一成是后台任务 —— 真流里那些 actor 就是 null。
  if (w.rnd() >= 0.9) return null
  if (kind === 'notify') {
    return actorOf(w.rnd() < 0.5
      ? `C${intBetween(w.rnd, 1001, 1099)}`
      : `M${intBetween(w.rnd, 201, 209)}`)
  }
  return actorOf(`C${intBetween(w.rnd, 1001, 1099)}`)
}

/** 去掉值为 `undefined` 的键（JSON 里本来也会丢；这里先丢，免得 `hasOwnProperty` 判到它）。 */
function dropUndef(o) {
  for (const k of Object.keys(o)) if (o[k] === undefined) delete o[k]
  return o
}

/**
 * 这一条事件的「各环自己的业务字段」—— `objectType` / `objectId` / `key` / `fields`
 * （外加库存扣减档的 `remaining`、审计档的 `actor`）。
 *
 * ⚠️ 逐环键名**照抄后端 `EventBroadcaster` 的九个 `fromXxx`**，不是一套通用词汇：
 *    `cache`/`lock` 用 `cache|lock/result/key`，`order` 用 `action/result/from/to/order`，
 *    `stock` 用 `kind/sku/qty/result`，`audit` 用 `actor/action/reason`，
 *    `notify` 用 `to/event/reason`，`sensitive` 用 `scene/hit/total`，`identity` 用 `subject/reason`。
 *    多一个键或少一个键都会让前端「fields 优先」那条路静默走到回落分支。
 * ⚠️ 3a.3 后续：warn / error 档额外带 `reason`（失败/降级的人话原因），info 档不写这个键 ——
 *    与真帧一致，⑤ 的 `.tech` 才有东西可着色。
 */
function buildBody(w, kind, t) {
  switch (kind) {
    case 'cache': {
      const [area, key] = pickOne(w.rnd, CACHE_AREAS)
      return {
        objectType: '', objectId: key, key,
        // 成败放行的 info 不写 reason；降级 / 失败档带人话原因（同真帧 fields.reason 的有无口径）。
        fields: dropUndef({ cache: area, result: t.result, key, reason: t.reason }),
      }
    }
    case 'lock': {
      const lock = pickOne(w.rnd, LOCK_NAMES)
      const bizKey = lock === 'friend-add'
        ? `friend:add/u-${intBetween(w.rnd, 1001, 1099)}->u-${intBetween(w.rnd, 1001, 1099)}`
        : `${lock}/${orderRef(w)}`
      return {
        objectType: '', objectId: bizKey, key: bizKey,
        fields: dropUndef({ lock, result: t.result, key: bizKey, reason: t.reason }),
      }
    }
    case 'order': {
      const orderId = orderRef(w)
      return {
        objectType: '', objectId: orderId, key: orderId,
        fields: dropUndef({
          action: t.action, result: t.result, from: t.from, to: t.to,
          order: orderId, reason: t.reason,
        }),
      }
    }
    case 'stock': {
      const sku = pickOne(w.rnd, KIND_CONTENT.stock.ids)
      const qty = intBetween(w.rnd, 1, 3)
      const remaining = t.remaining ? intBetween(w.rnd, t.remaining[0], t.remaining[1]) : null
      // warn / error 档必须带原因（3a.3 后续）：低水位与防超卖各说各的事实，
      // 与真帧 `StockMetrics` 的文案同款；成功档保持 undefined ⇒ 不写这个键。
      let reason = t.reason
      if (t.title === '库存水位偏低') {
        reason = `库存水位偏低：剩余 ${remaining} 件（阈值 10 件）`
      } else if (t.result === 'insufficient') {
        reason = `防超卖拦截：库存不足（需 ${qty} 件，剩余 ${remaining} 件）`
      }
      return {
        objectType: '', objectId: sku, key: sku,
        fields: dropUndef({ kind: 'deduct', sku, qty, result: t.result, reason }),
        // ⚠️ `remaining` 是**顶层键**（不是 fields 的一格）：只有扣减的 ok / insufficient 两档有值。
        remaining,
      }
    }
    case 'audit': {
      const [id, role] = pickOne(w.rnd, AUDIT_ACTORS)
      return {
        objectType: t.target[0], objectId: t.target[1], key: id,
        // 审计环的 `actor` 是**字符串业务号**（来自审计行的 Actor 列），与顶层 `actor` 对象不是一回事。
        fields: { actor: id, action: t.action, reason: null },
        actor: { id, role },
      }
    }
    case 'notify': {
      const toId = pickOne(w.rnd, KIND_CONTENT.notify.ids)
      return {
        objectType: '', objectId: toId, key: toId,
        fields: { to: t.to, event: t.event, reason: t.reason === undefined ? null : t.reason },
      }
    }
    case 'sensitive': {
      const words = pickOne(w.rnd, KIND_CONTENT.sensitive.ids)
      // 真帧里 `hit` 是**命中的字符数**（`SensitiveWordService.filter` 的累加口径），
      // 所以按比例造：rejected 必须 ≥ 40%，masked 必须 < 40% —— 否则事件与原因自相矛盾。
      const total = intBetween(w.rnd, 20, 120)
      const rejected = t.result === 'rejected'
      const hit = rejected
        ? Math.max(8, Math.ceil(total * 0.5))
        : Math.max(2, Math.floor(total * 0.2))
      return {
        objectType: '', objectId: words, key: words,
        fields: dropUndef({
          scene: '评论', hit, total,
          reason: rejected
            ? `敏感词拒收：命中比例 ${Math.round((hit / total) * 100)}% ≥ 40%（命中 ${hit}/${total}）`
            : undefined,
        }),
      }
    }
    case 'identity': {
      const path = pickOne(w.rnd, KIND_CONTENT.identity.ids)
      const subject = `u-${intBetween(w.rnd, 1001, 1099)}`
      return {
        objectType: '', objectId: path, key: subject,
        fields: { subject, reason: t.reason === undefined ? null : t.reason },
      }
    }
    default:
      return { objectType: '', objectId: '', key: '', fields: null }
  }
}

/**
 * `detail` 的拼法 —— **逐环照抄后端 `EventBroadcaster` 的 `detail` 形状**，尾部追加
 * `src=环名#源seq`（后端在 `add()` 里统一追加，前端不把 src 放进 `fields`）。
 * ⚠️ 这是给人读的孪生串；结构化那份在 `fields`，两者说的是同一批事实。
 */
function detailFor(kind, fields, srcSeq) {
  let body
  switch (kind) {
    case 'cache':
      body = `cache=${fields.cache} result=${fields.result} key=${fields.key}`
      break
    case 'lock':
      body = `lock=${fields.lock} result=${fields.result} key=${fields.key}`
      break
    case 'order':
      body = fields.action
        ? `action=${fields.action} result=${fields.result} order#${fields.order}`
        : `result=${fields.result}${fields.reason ? ` reason=${fields.reason}` : ''} order#${fields.order}`
      break
    case 'stock':
      body = `kind=${fields.kind} sku=${fields.sku} qty=${fields.qty} result=${fields.result}`
      break
    case 'audit':
      body = `actor=${fields.actor} action=${fields.action}${fields.reason ? ` reason=${fields.reason}` : ''}`
      break
    case 'notify':
      body = `to=${fields.to} event=${fields.event}${fields.reason ? ` reason=${fields.reason}` : ''}`
      break
    case 'sensitive':
      body = `scene=${fields.scene} hit=${fields.hit}/${fields.total}`
      break
    case 'identity':
      body = `subject=${fields.subject}${fields.reason ? ` reason=${fields.reason}` : ''}`
      break
    default:
      body = ''
  }
  return `${body} src=${kind}#${srcSeq}`
}

/** 造一条事件（`events` 帧里的 `events[]` 一行）。 */
function makeEvent(w, nowMs) {
  const { kind } = pickWeighted(w.rnd, KIND_WEIGHT)
  const c = KIND_CONTENT[kind]
  const t = pickWeighted(w.rnd, c.titles)
  w.evSeq += 1
  const srcSeq = nextSrcSeq(w, kind)
  const body = buildBody(w, kind, t)
  return {
    seq: w.evSeq,
    ts: nowMs,
    kind,
    severity: t.sev,
    title: t.title,
    detail: detailFor(kind, body.fields, srcSeq),
    key: body.key,
    objectType: body.objectType,
    objectId: body.objectId,
    // ⚠️ 每 4 条里 3 条挂得上链路（真库里也是有的有、有的没有）—— 全挂上反而不像真的。
    traceId: w.rnd() < 0.75 ? `t${(w.evSeq % 997).toString(16).padStart(3, '0')}` : null,
    batchId: null, // 演示不是演练 ⇒ 没有批次（理由见文件头）
    // 3a.3 的三样（帧 11 → 15 键的后三个）。形状：actor 是 `{id, role}` 或 null。
    actor: body.actor !== undefined ? body.actor : makeActor(w, kind),
    durationUs: t.dur ? intBetween(w.rnd, t.dur[0], t.dur[1]) : null,
    remaining: body.remaining === undefined ? null : body.remaining,
    fields: body.fields,
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
  const severity = { info: 0, protect: 0, warn: 0, error: 0 }
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

/**
 * 指标帧。
 *
 * ⚠️ 形状**逐键对齐** `MetricsSnapshotService.frame()`（2026-10-17 按真实抓帧
 *    `_work/3a4/metrics-real-sample.txt` 补齐）：本文件原先只有 `count/qps/p99Ms/errorRate/
 *    clientErrorRate/hitRate` 这几格，指标页 3a.4 的五图一栏需要 `errors4xx/errors5xx`、
 *    `meanMs`、`cache.hit/miss/l2Degraded`、锁的 `acquires/acquired/timeouts/waited/degraded/
 *    errors/waitMeanMs` —— 缺一格图上就少一条线。**这里只补形状，不把演示值说成真值**：
 *    `health.reasons` 里那句「模拟日常」仍在，页面照旧显示「模拟日常」。
 *
 * ⚠️ 本场活动额度放完后 `n = 0`：与真流空闲时同形 —— `qps=0`、`hitRate=null`、
 *    `meanMs=null`、`locks=[]`。不能落成「0 ms / 0%」（那是「测到了 0」，不是「没样本」）。
 */
function metricsFrame(w, nowMs, n) {
  const rnd = w.rnd
  w.seq += 1
  w.reqTotal += n
  const busy = n > 0

  // 5xx 罕见（约 1.5% 拍），4xx 每拍 0–2 条；两者都按「本拍计数」给，占比现算（与后端同口径）。
  const errors5xx = busy && rnd() < 0.015 ? 1 : 0
  const errors4xx = busy ? intBetween(rnd, 0, 2) : 0
  const errorRate = n > 0 ? Math.round((errors5xx / n) * 10000) / 10000 : null
  const clientErrorRate = n > 0 ? Math.round((errors4xx / n) * 10000) / 10000 : null

  // 缓存命中/未命中：一次请求平均 2 次缓存访问，命中率 86%–95%（演示口径，不是后端真值）。
  const cacheOps = busy ? n * 2 : 0
  const hitRateRaw = 0.86 + rnd() * 0.09
  const hit = Math.round(cacheOps * hitRateRaw)
  const miss = cacheOps - hit
  const hitRate = hit + miss > 0 ? Math.round((hit / (hit + miss)) * 1000) / 1000 : null

  // 三把锁：日常都有取锁，偶尔超时/降级。`waitMeanMs` 有流量才有样本，空闲时为 null。
  const lockNames = ['order-pay', 'stock-deduct', 'coupon-claim']
  const locks = []
  let lockTimeouts = 0
  if (busy) {
    for (const lock of lockNames) {
      const acquires = intBetween(rnd, 2, 9)
      const timeouts = rnd() < 0.1 ? intBetween(rnd, 1, 3) : 0
      const degraded = rnd() < 0.02 ? 1 : 0
      const errors = rnd() < 0.01 ? 1 : 0
      const waitMeanMs = Math.round((0.4 + rnd() * 6.5) * 10) / 10
      lockTimeouts += timeouts
      locks.push({
        lock,
        acquires,
        acquired: Math.max(0, acquires - timeouts - degraded - errors),
        timeouts,
        // 「等待次数」= 取锁耗时 ≥5ms 的次数（真流口径见 MallLockMetrics.CONTENDED_NANOS）：
        // 均值 ≥5ms 时大部分取锁都在排队；均值只有亚毫秒时只算零星抖动。
        waited: Math.round(acquires * (waitMeanMs >= 5 ? 0.5 + rnd() * 0.35 : 0.05 + rnd() * 0.1)),
        degraded,
        errors,
        waitMeanMs,
      })
    }
  }

  // 健康度与后端同阈值（warn > 1% 5xx，error ≥ 5%），但 reasons 里永远先自报「模拟日常」。
  let level = 'ok'
  let label = '健康'
  const reasons = [`模拟日常（假数据，不入库）· L1 命中率 ${hitRate === null ? '—' : (hitRate * 100).toFixed(1) + '%'}`]
  if (errorRate !== null && errorRate > 0.01) {
    level = 'warn'
    label = '警告'
    reasons.push(`模拟 5xx 占比 ${(errorRate * 100).toFixed(1)}%（> 1%）`)
  }
  if (errorRate !== null && errorRate >= 0.05) {
    level = 'error'
    label = '错误'
  }

  // 库存：12 条覆盖 充足 / 偏低 / 缺货 / 未设基线 四种状态，才能验搜索、筛选与内部滚动。
  // 活动拍轻微抖动（0 库存的仍保持 0），空闲拍不动。
  const stockBase = [
    { sku: 'DEMO-GD-101', goodName: '模拟商品 · 气泡水', axisName: '330ml', stock: 0, baseline: 120 },
    { sku: 'DEMO-GD-102', goodName: '模拟商品 · 无线耳机', axisName: '标准版', stock: 12, baseline: 120 },
    { sku: 'DEMO-GD-103', goodName: '模拟商品 · 智能手环', axisName: '标准版', stock: 42, baseline: 120 },
    { sku: 'DEMO-GD-104', goodName: '模拟商品 · 机械键盘', axisName: '茶轴', stock: 88, baseline: 160 },
    { sku: 'DEMO-GD-105', goodName: '模拟商品 · 2K 显示器', axisName: '27 英寸', stock: 120, baseline: 120 },
    { sku: 'DEMO-GD-106', goodName: '模拟商品 · 积木套装', axisName: '大盒', stock: 9, baseline: 80 },
    { sku: 'DEMO-GD-107', goodName: '模拟商品 · 保温杯', axisName: '500ml', stock: 64, baseline: 200 },
    { sku: 'DEMO-GD-108', goodName: '模拟商品 · 蓝牙音箱', axisName: 'mini', stock: 35, baseline: 80 },
    { sku: 'DEMO-GD-109', goodName: '模拟商品 · 充电宝', axisName: '10000mAh', stock: 150, baseline: 200 },
    { sku: 'DEMO-GD-110', goodName: '模拟商品 · 台灯', axisName: '护眼版', stock: 18, baseline: 120 },
    { sku: 'DEMO-GD-111', goodName: '模拟商品 · 鼠标垫', axisName: '加厚', stock: 0, baseline: 60 },
    { sku: 'DEMO-GD-112', goodName: '模拟商品 · 数据线', axisName: 'Type-C', stock: 48, baseline: null },
  ]
  const stockRows = stockBase.map((r) => {
    const delta = busy && r.stock > 0 ? intBetween(rnd, -2, 2) : 0
    return { ...r, stock: Math.max(0, r.stock + delta), ratio: null }
  })

  return {
    seq: w.seq,
    ts: nowMs,
    intervalMs: 1000, // 演示流固定 1 秒 1 拍（真流按实际间隔给，页面用同一公式折算）
    requests: {
      count: n,
      qps: n, // 一拍 1 秒 ⇒ 本拍条数就是每秒条数
      errors5xx,
      errors4xx,
      errorRate,
      clientErrorRate,
      meanMs: busy ? intBetween(rnd, 8, 90) : null,
      p95Ms: busy ? intBetween(rnd, 60, 180) : null,
      p99Ms: busy ? intBetween(rnd, 90, 260) : null,
      maxMs: busy ? intBetween(rnd, 120, 420) : null,
    },
    cache: {
      hit,
      miss,
      hitRate,
      l2Degraded: busy && rnd() < 0.02 ? 1 : 0,
    },
    gauges: { l2Up: 1.0, lockUp: 1.0 },
    locks,
    health: { level, label, reasons },
    protections: PROTECTIONS.map((tag) => {
      let value
      if (tag === 'cacheHit') value = hit
      else if (tag === 'lockTimeout') value = lockTimeouts
      else value = rnd() < 0.7 ? intBetween(rnd, 0, 3) : 0
      return { tag, label: PROTECTION_LABEL[tag], value }
    }),
    stock: { available: true, rows: stockRows },
  }
}

// 库存水位：`ratio` 由 `stock / baseline` 现算（后端也是在 `MetricsSnapshotService` 里算的，
// 这里照算一遍只为让颜色分档（<20% 红 / <50% 黄 / 其余绿）真的会分档）。
// ⚠️ `baseline` 为 null（真库还没打演示基线）时 **ratio 必须是 null**，不能 `stock / null`
//    → Infinity → clamp 成 1：那会把「未测」画成「满仓」，与后端 `ratio:null` 的形状相反。
function withRatio(frame) {
  for (const r of frame.stock.rows) {
    r.ratio = r.baseline === null || r.baseline === undefined || Number(r.baseline) <= 0
      ? null
      : Math.max(0, Math.min(1, Number(r.stock) / Number(r.baseline)))
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
