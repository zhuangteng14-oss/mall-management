// src/api/replay-session.js —— 演练回放的**播放器内核**（2026-10-17 重做）。
//
// 【它解决什么问题】
//   旧实现把回放做成「一次 JSON 装载 + 一次性 `played.push`」的列表：点回放后整批历史
//   立刻铺满，没有时间轴；而实时 SSE 还在旁边继续往视图里塞新数据，两种数据混在一起。
//   本模块把回放抽成**跨页面的会话**：装载一次台账 → 用 rAF 播放时钟把事件
//   **按原时间戳一条一条吐出来**，并在同一拍里向三页广播：
//     · 日志页：`sink(batch)`（高频、非响应式；不在屏时进 backlog）
//     · 指标页：`replaySession.metricTicks`（按日志时间 1 秒一拍，前端推算）
//     · 状态页：`replaySession.stateChanges`（订单流转，含原 from/to 枚举名）
//
// 【时钟口径（用户 2026-10-17 的规格 + 「全链路联动」补充）】
//   · 播放位置 `virtualMs` 是**日志时间偏移**，从 0 起；时钟按 **rAF**（≈16ms）推进，
//     每帧推进 `speed × Δt`。**缺省 1×**（1 秒日志 = 1 秒墙钟，用户 2026-10-17 改），
//     横幅上可切 0.25x / 0.5x / 1x / 2x / 5x / 10x；切换**不重置** `virtualMs` / `cursor`。
//     ⚠️ 慢速档同样是**真实时间差值**：每帧 `virtualMs += Δt × speed`，不是把时间戳改写成
//        `Date.now()`，也不是把历史事件摊平成固定间隔 —— 0.25x 时 1 秒墙钟只推进 250ms 日志时间。
//   · 每帧上限 `maxPerFrame` 不是常数：装载完成后按该批次的**峰值事件密度**算
//     `clamp(ceil(peakPerSec × speed / 60), 50, 200)`（50 = 用户定的基线，200 = 防一帧
//     把主线程打爆的天花板）。稀疏批次 50 条/帧就够，密集批次自动放宽，**不靠 5 条/帧慢放**。
//     切倍速时按新倍率**重算**这个上限（不重新装载，也不动播放位置）。
//   · 到期但没吐完（上限真的被顶住）时，播放位置钳在已吐出的最后一条上，下一帧继续 ——
//     三页才在同一秒；横幅如实显示「实际速率 / 本帧上限」，不拿 10× 冒充。
//   · 播放是**本地时钟**：后端在装载之后不再参与（一次性的只读 SELECT）。
//
// 【拖拽定位（Seek，2026-10-18）】
//   横幅的进度条可拖拽：拖拽中（`@input`）只改目标时间文字，**不重绘**；松开（`@change`）
//   调用一次 `seekReplaySession(ratio)` —— 停表 → 清空三页视图 → 把 `0 → 目标索引` 的
//   事件全量 apply 一遍（指标窗口逐秒重算、订单流转逐条重放、日志行一把补画）→ 瞬间重绘；
//   之前在播则从新位置续播，之前暂停则停在新位置。重建期间 `replaySession.seeking=true`，
//   横幅显示 loading 遮罩；`seekSeq` 是给日志页 / 状态页的清空信号（与 `restartSeq` 分开）。
//
// 【回放绝不合并同类事件】
//   日志页的 `absorbEvents(..., { noAgg: true })` 由 EventLog 保证（用户 2026-10-17：
//   回放是查案，要 1:1 看并发时序，不允许 ×N）。本模块不提供任何聚合开关。
//
// 【为什么要有 sink / backlog】
//   回放可以从攻击模拟器页发起，而真正画行的是日志页。会话先跑起来、日志页后挂载时，
//   这段空窗里吐出来的事件不能丢 ⇒ 没 sink 就进 `backlog`，日志页挂载后按 50 条一组补画。
//   用户切到指标页时日志页会 detach（KeepAlive 失活），同样走 backlog；切回来再补。
//
// 【与「模拟日常」的边界】
//   回放只进 `normal` 桶（日志页的 `absorbEvents(..., 'normal')`）。进入回放前调用方会
//   把 `demo.on` 关掉 —— 两个「过去/假数据」模式同时开，页面上就说不清在看什么。

import { nextTick, reactive } from 'vue'
import { replay as fetchReplay } from './observe'
import { advanceMetricWindow, createMetricWindow, flushMetricWindow, pushMetricEvent } from './replay-metrics'
// 回放只进 `normal` 桶（日志页的 `absorbEvents(..., 'normal')`）：若「模拟日常」还开着，
// 日志页的 `cur` 会指向 demo 桶 ⇒ 回放行写了却看不见。进回放前强制切回正常源。
import { setDemoMode } from './demo-stream'

/**
 * 一次回放最多向后台要多少条台账。
 *
 * ⚠️ 必须显式给 **后端上限**（`ReplayService.MAX_LIMIT = 50000`）：不传时后端缺省只给 5000，
 * 一轮 35k 条的演练会被截成前 10 秒 —— 用户 2026-10-17 的验收口径是「回放与实时演练
 * 完全一致」，只放前一段就看不到后面所有取消/失败。超 5 万条的批次仍会截断，
 * 但横幅会把 `total / count / limit` 三者的差写出来，不静默。
 */
export const REPLAY_LOAD_LIMIT = 50000

/** 播放时钟节拍：**rAF**（帧同步，≈16ms）——由用户「不要抽帧感」那条指令定死。 */
export const REPLAY_TICK_MS = 16
/** 默认时间倍率：1 秒墙钟 = 1 秒日志（用户 2026-10-17：「点回放就直接 10× 狂奔」要改掉）。 */
export const REPLAY_SPEED = 1
/** 横幅上可选的倍速档位（声明序 = 按钮从左到右）。
 *  ⚠️ 0.25 / 0.5 是用户 2026-10-17 明令补的慢速档：查案时要看清同一毫秒内的并发顺序。
 *     时钟按 `virtualMs += Δt × speed` 推进 ⇒ 慢速档只是「日志时间走得慢」，不改事件时间戳。 */
export const REPLAY_SPEEDS = [0.25, 0.5, 1, 2, 5, 10]
/** 每帧吞吐的**基线**（用户 2026-10-17 最终定：50 条/帧起）。
 *  ⚠️ 真正生效的是 `replaySession.maxPerFrame`：装载后按批次峰值算
 *     `clamp(ceil(peakPerSec × speed / 60), 50, 200)`。稀疏批次就是 50；密集批次自动放宽，
 *     不把 10× 录像拖成慢放。**回放不走同类聚合**（查案要 1:1 看并发时序，不允许 ×N）
 *     ⇒ 压力由「每帧上限 + 日志页窗口化（DOM ≤ ~220 行）」扛，而不是靠合并。 */
export const REPLAY_MAX_PER_FRAME = 50
/** 自适应上限的天花板：再高就不是「一帧」而是一次性铺满，主线程会被打爆。 */
export const REPLAY_MAX_PER_FRAME_CEILING = 200
/** 回放专用 seq 基址：与真流（进程级 seq）和演示流（900000 起）都不重叠。 */
const REPLAY_SEQ_BASE = 1_000_000_000
/** 日志页不在屏时最多替它攒多少条（超出丢最旧，避免切页久了吃内存）。 */
const REPLAY_BACKLOG_CAP = 4096

export const replaySession = reactive({
  /** 数据已装载、处于回放模式（横幅的显示判据）。 */
  active: false,
  /** 正在读台账（攻击模拟器页的按钮文案用）。 */
  loading: false,
  /** 播放时钟在跑（`done` 之前可能因为事件稀疏而在两拍之间为 false，不用于 UI 判断）。 */
  playing: false,
  batchId: '',
  err: '',
  /** 后端 `total`（整批条数）/ `limit`（本次请求上限）/ `count`（本次实际载入）。 */
  total: 0,
  limit: 0,
  count: 0,
  /** 已经吐出的条数（进度 X / Y 的 X）。 */
  cursor: 0,
  /** 原时间轴：首条 ts / 末条 ts / 当前回放时钟的 ts（毫秒）。 */
  startTs: 0,
  endTs: 0,
  clockTs: 0,
  spanMs: 0,
  /** 倍率（横幅显示用）。 */
  speed: REPLAY_SPEED,
  paused: false,
  done: false,
  /** 已推进的**日志时间**（毫秒，封顶到 `spanMs`）。 */
  elapsedMs: 0,
  /** 本帧实际生效的条数上限（按批次密度自适应，见 `REPLAY_MAX_PER_FRAME`）。 */
  maxPerFrame: REPLAY_MAX_PER_FRAME,
  /** 最近 1 秒墙钟实际吐出的事件条数（横幅如实显示，不拿 10× 冒充）。 */
  effectiveRate: 0,
  /** 演练记录那一行的聚合快照（记录级，非时间序列；用于「本轮总量」卡片）。 */
  recordSnapshot: null,
  /** 指标拍（滚动最近 600 个）。由 `replay-metrics.js` 按日志时间 1 秒一个算出。 */
  metricTicks: [],
  /** 订单流转（全部保留；状态页晚挂载时从头重建到当前回放位置）。 */
  stateChanges: [],
  metricSeq: 0,
  stateSeq: 0,
  /** 「重新开始」计数：每次重放 +1。三页 watch 它来清掉自己那份回放视图
   *  （日志行、状态机重建、直方图样本），会话本身不动。 */
  restartSeq: 0,
  /** 拖拽定位（Seek）**正在重建**：横幅画 loading 遮罩，`@input` 期间不触发任何重绘。 */
  seeking: false,
  /** 本次 seek 的目标百分比（只在 `seeking` 时有效，横幅显示「正在重建到 40%」）。 */
  seekPct: 0,
  /** 「定位重建」信号：每次 seek +1。日志页据此清掉旧行、状态页据此清掉状态机，
   *  然后会话把 `0 → 目标索引` 的历史事件重新 apply 一遍。与 `restartSeq` 分开：
   *  重放是「回到 0 继续播」，seek 是「跳到任意位置且可能不再继续」。 */
  seekSeq: 0,
  /** 正在批量 apply 历史事件（不是正常的一帧一帧吐）。
   *  ⚠️ 状态页据此**跳过逐条粒子/高亮动画**：历史高亮发生在过去，重建时补闪等于把
   *     几千条流转同时说成「刚刚」。 */
  rebuilding: false,
})

/** 全量行（非响应式）：`{ off, ts, event }`，`off` = 距首条事件的毫秒偏移。 */
let rows = []
/** 日志页画行用的回调；为空时事件进 `backlog`。 */
let sink = null
/** 日志页不在屏时攒下来的事件（普通数组，不触发响应式）。 */
let backlog = []
let timer = 0
/** 当前播放位置（日志时间，毫秒）。 */
let virtualMs = 0
/** 上一拍的 `performance.now()`；暂停/恢复时重置，避免暂停时长被算进回放。 */
let lastNow = 0
/** 指标滑动窗口（非响应式；只装已吐出的事件）。 */
let metricWindow = createMetricWindow()
/** 自适应上限的统计口：1 秒墙钟内的吐出条数。 */
let rateStart = 0
let rateCount = 0
/** 拖拽定位的世代号：新的一次 seek / 退出会话会让旧的异步重建立即作废。 */
let seekToken = 0

/** 指标拍滚动保留数：页面窗口 60，留 10 倍余量给切页 / 暂停后回来。 */
const METRIC_TICK_CAP = 600
/** 每帧上限：按「峰值事件/日志秒 × 倍率 ÷ 60fps」算，夹在 [50, 200]。 */
function adaptiveCap(list, speed) {
  const buckets = new Map()
  for (const r of list) {
    const sec = Math.floor(r.off / 1000)
    buckets.set(sec, (buckets.get(sec) || 0) + 1)
  }
  let peak = 0
  for (const n of buckets.values()) peak = Math.max(peak, n)
  const rate = Number(speed) > 0 ? Number(speed) : REPLAY_SPEED
  const need = Math.ceil((peak * rate) / 60)
  return Math.max(REPLAY_MAX_PER_FRAME, Math.min(REPLAY_MAX_PER_FRAME_CEILING, need || 0))
}

/**
 * SQL 的 `CAST(OccurredAt AS CHAR)` → epoch 毫秒。
 *
 * ⚠️ 这一列是 **Asia/Shanghai 本地墙钟文本，不是 UTC**（2026-10-17 对 dev 库实测）：
 *   `observed_event.OccurredAt` 由 `ObservedEventDao.insertBatch` 绑 `new Timestamp(epochMilli)`，
 *   驱动按连接串 `serverTimezone=Asia/Shanghai` 转成 +08 墙钟再写进 `datetime(3)`；
 *   而 MySQL 服务器时区是 UTC（`@@system_time_zone=UTC`，`NOW()` 比 JVM 慢 8 小时）。
 *   同一批 `8fdb21b7…`：`drill_record.created_at = 15:42:26`（库里 UTC），
 *   台账首末 `OccurredAt = 23:41:12.958 ~ 23:42:26.570`（本地 +08）—— 首尾正好差 8 小时。
 * ⇒ 必须补 `+08:00`。补 `Z` 会把回放时钟整体推后 8 小时，进度条旁的时间与批次列表对不上。
 */
const LEDGER_TZ_OFFSET = '+08:00'
function epochOf(sqlTime) {
  if (sqlTime === null || sqlTime === undefined || sqlTime === '') return 0
  const text = String(sqlTime).replace(' ', 'T')
  // 台账文本没有时区标记；已带 Z / ±hh:mm 的值原样解析（防御后端未来改口径）。
  const withTz = /(?:Z|[+-]\d{2}:?\d{2})$/.test(text) ? text : `${text}${LEDGER_TZ_OFFSET}`
  const ms = new Date(withTz).getTime()
  return Number.isNaN(ms) ? 0 : ms
}

/** 台账行 → 日志页 `absorbEvents()` 认的事件形状（字段名逐条对齐 `ObservedEventDao.COLUMNS`）。 */
function toEvent(r, i) {
  const ts = epochOf(r.OccurredAt)
  let fields = null
  const raw = r.FieldsJson
  if (raw && typeof raw === 'object') {
    fields = raw
  } else if (typeof raw === 'string' && raw.trim()) {
    try { fields = JSON.parse(raw) } catch (e) { fields = null }
  }
  const actorId = r.ActorId === null || r.ActorId === undefined || r.ActorId === '' ? null : String(r.ActorId)
  return {
    seq: REPLAY_SEQ_BASE + i,
    ts,
    kind: r.Kind,
    severity: r.Severity,
    title: r.Title,
    // ⚠️ `detail` / `key` 两张表都**没有列**（见 ObservedEventDao 的类注释）⇒ 回放里给空，
    //    不要把「没有这一列」伪装成「当时没有这个值」。
    detail: '',
    key: r.ObjectId === null || r.ObjectId === undefined ? null : String(r.ObjectId),
    objectType: r.ObjectType === null || r.ObjectType === undefined ? null : r.ObjectType,
    objectId: r.ObjectId === null || r.ObjectId === undefined ? null : r.ObjectId,
    traceId: r.TraceId === null || r.TraceId === undefined ? '' : String(r.TraceId),
    batchId: r.BatchId === null || r.BatchId === undefined ? null : String(r.BatchId),
    actor: actorId ? {
      id: actorId,
      role: r.ActorRole === null || r.ActorRole === undefined ? null : Number(r.ActorRole),
    } : null,
    durationUs: r.DurationUs === null || r.DurationUs === undefined ? null : Number(r.DurationUs),
    remaining: r.RemainingStock === null || r.RemainingStock === undefined ? null : Number(r.RemainingStock),
    fields,
  }
}

function resetSession() {
  // 作废仍在 await 里的 seek：否则它在装载/退出之后还会往新会话里写一次历史重建。
  seekToken += 1
  rows = []
  backlog = []
  virtualMs = 0
  lastNow = 0
  metricWindow = createMetricWindow()
  rateStart = 0
  rateCount = 0
  replaySession.active = false
  replaySession.loading = false
  replaySession.playing = false
  replaySession.batchId = ''
  replaySession.err = ''
  replaySession.total = 0
  replaySession.limit = 0
  replaySession.count = 0
  replaySession.cursor = 0
  replaySession.startTs = 0
  replaySession.endTs = 0
  replaySession.clockTs = 0
  replaySession.spanMs = 0
  replaySession.speed = REPLAY_SPEED
  replaySession.paused = false
  replaySession.done = false
  replaySession.elapsedMs = 0
  replaySession.maxPerFrame = REPLAY_MAX_PER_FRAME
  replaySession.effectiveRate = 0
  replaySession.recordSnapshot = null
  replaySession.metricTicks = []
  replaySession.stateChanges = []
  replaySession.metricSeq = 0
  replaySession.stateSeq = 0
  replaySession.seeking = false
  replaySession.seekPct = 0
  replaySession.rebuilding = false
}

function stopClock() {
  if (timer) {
    cancelAnimationFrame(timer)
    timer = 0
  }
  replaySession.playing = false
}

function startClock() {
  if (timer) return
  lastNow = performance.now()
  timer = requestAnimationFrame(frame)
}

/** rAF 主循环：**先算再排下一帧**（暂停 / 放完时不再排，避免空转）。 */
function frame(now) {
  timer = 0
  if (!replaySession.active || replaySession.done) return
  if (replaySession.paused) return
  tick(now)
  if (replaySession.active && !replaySession.done && !replaySession.paused) {
    timer = requestAnimationFrame(frame)
  }
}

/** 把一批事件交给日志页；它不在屏就先攒着。 */
function deliver(batch) {
  if (!batch.length) return
  if (sink) {
    sink(batch)
    return
  }
  backlog.push(...batch)
  const over = backlog.length - REPLAY_BACKLOG_CAP
  if (over > 0) backlog.splice(0, over)
}

/** 指标拍入会话（滚动截断，避免长回放把数组撑到几万条）。 */
function appendMetricTicks(ticks) {
  for (const t of ticks) {
    replaySession.metricSeq += 1
    replaySession.metricTicks.push({
      ...t,
      seq: replaySession.metricSeq,
      ts: replaySession.startTs + t.off,
    })
  }
  const over = replaySession.metricTicks.length - METRIC_TICK_CAP
  if (over > 0) replaySession.metricTicks.splice(0, over)
}

/** 从本拍吐出的事件里摘出订单流转（from/to 原样保留，由状态页的 `stateKeyOf` 翻译）。
 *
 *  ⚠️ **汇总事件也要收**（2026-10-17 回放复核）：超时取消任务每轮只发一条
 *     `count=N / orderId=null / from=CREATED / to=CANCELLED` 的汇总 move（见
 *     `MallOrderStateMetrics.cancelBatch`）。旧实现见到空订单号就 `continue` ⇒ 回放里
 *     每一次批量取消都凭空消失，状态机的「已取消」永远不会涨。
 *     → 汇总事件以 `orderId=null + count=N` 入列，状态页按 N 加权重放。
 *  ⚠️ 时间戳一律取台账的 `OccurredAt`（`e.ts`），**不是**装载/播放时刻。 */
function appendStateChanges(batchRows) {
  for (const row of batchRows) {
    const e = row.event
    if (!e || e.kind !== 'order') continue
    const f = e.fields || {}
    const to = f.to
    if (!to) continue
    const orderId = f.order === null || f.order === undefined ? e.objectId : f.order
    const count = Number(f.count) > 0 ? Number(f.count) : 0
    // 既没有订单号、也不是汇总事件 ⇒ 这条流转无从落地（脏台账），不猜。
    if (!count && (orderId === null || orderId === undefined || orderId === '')) continue
    replaySession.stateSeq += 1
    replaySession.stateChanges.push({
      seq: replaySession.stateSeq,
      ts: e.ts,
      off: row.off,
      orderId: orderId === null || orderId === undefined || orderId === '' ? null : String(orderId),
      count,
      from: f.from === undefined ? null : f.from,
      to,
      result: f.result === undefined || f.result === null ? '' : String(f.result),
      action: f.action === undefined || f.action === null ? '' : String(f.action),
      severity: e.severity || '',
    })
  }
}

/** 实际吐出速率（1 秒墙钟窗口）—— 横幅用它，而不是拿倍率冒充。 */
function noteRate(n, now) {
  if (!rateStart) rateStart = now
  rateCount += n
  const elapsed = now - rateStart
  if (elapsed >= 1000) {
    replaySession.effectiveRate = Math.round((rateCount * 1000) / elapsed)
    rateStart = now
    rateCount = 0
  }
}

/** 一帧：推进日志时钟 → 吐到期事件（限量）→ 三页同一拍更新。 */
function tick(now) {
  // 标签页在后台时 rAF 会停；把单帧增量夹住（100ms），切回来才不会一次跳完。
  const dt = Math.max(0, Math.min(100, now - lastNow))
  lastNow = now
  virtualMs += dt * replaySession.speed

  const batchRows = []
  while (
    batchRows.length < replaySession.maxPerFrame &&
    replaySession.cursor < rows.length &&
    rows[replaySession.cursor].off <= virtualMs
  ) {
    batchRows.push(rows[replaySession.cursor])
    replaySession.cursor += 1
  }
  // 到期事件没吐完（高密度批次）⇒ 播放位置钳在已吐出的最后一条上，下一拍继续。
  // ⚠️ 不这么做的话时钟会先跑到批次末尾，进度条和画面就各说各话。
  if (
    replaySession.cursor < rows.length &&
    rows[replaySession.cursor].off <= virtualMs &&
    replaySession.cursor > 0
  ) {
    virtualMs = rows[replaySession.cursor - 1].off
  }
  const clockOff = Math.min(virtualMs, replaySession.spanMs)
  replaySession.clockTs = replaySession.startTs + clockOff
  replaySession.elapsedMs = clockOff

  if (batchRows.length) {
    replaySession.playing = true
    // ① 日志页：只投事件（高频、不能进响应式）。
    deliver(batchRows.map((r) => r.event))
    // ② 指标窗口：只吃已经吐出来的事件（绝不吃未来）。
    for (const row of batchRows) pushMetricEvent(metricWindow, row.event, row.off)
    // ③ 状态页：订单流转。
    appendStateChanges(batchRows)
  }

  // 指标拍按**日志时间**推进，没事件的秒也出拍（0 QPS），图上的时间轴不停。
  appendMetricTicks(advanceMetricWindow(metricWindow, clockOff))
  noteRate(batchRows.length, now)

  if (replaySession.cursor >= rows.length) {
    // 收尾：补最后一个不完整的指标窗口，再标记放完。
    appendMetricTicks(flushMetricWindow(metricWindow, Math.max(clockOff, replaySession.spanMs)))
    replaySession.done = true
    replaySession.clockTs = replaySession.endTs
    replaySession.elapsedMs = replaySession.spanMs
    replaySession.effectiveRate = 0
    stopClock()
  }
}

/**
 * 装载一个批次并开始播放（攻击模拟器页的 [回放] 钮调用）。
 * ⚠️ 失败时**不进入**回放模式（`active` 保持 false），错误抛给调用方显示 ——
 *    否则用户会被导航到一个「横幅写着回放、实际什么都没有」的日志页。
 */
export async function startReplaySession(batchId, recordSnapshot) {
  stopClock()
  resetSession()
  replaySession.batchId = batchId
  replaySession.recordSnapshot = recordSnapshot || null
  replaySession.loading = true
  // 先关「模拟日常」再读台账：日志页的 `watch(demo.on)` 会调 `connect()`，而它的守卫
  // （`active || loading`）会拦住这次重连 —— 回放准备期间不该先闪一帧实时数据。
  setDemoMode(false)
  try {
    const data = await fetchReplay(batchId, REPLAY_LOAD_LIMIT)
    // 后端已经是 `ORDER BY OccurredAt, Id`；这里再排一次只为**稳定**：同一毫秒内按 Id 兜底，
    // 不靠 JS 的「恰好稳定」碰运气（V8 稳定，但契约不写死排序器实现）。
    const list = ((data && data.list) || []).slice()
      .sort((a, b) => (epochOf(a.OccurredAt) - epochOf(b.OccurredAt))
        || (Number(a.Id) - Number(b.Id)))
    const t0 = list.length ? epochOf(list[0].OccurredAt) : 0
    const t1 = list.length ? epochOf(list[list.length - 1].OccurredAt) : 0
    rows = list.map((r, i) => {
      const ts = epochOf(r.OccurredAt)
      return { off: Math.max(0, ts - t0), ts, event: toEvent(r, i) }
    })
    replaySession.total = Number(data && data.total) || 0
    replaySession.limit = Number(data && data.limit) || 0
    replaySession.count = rows.length
    replaySession.startTs = t0
    replaySession.endTs = t1
    replaySession.clockTs = t0
    replaySession.spanMs = Math.max(0, t1 - t0)
    // 自适应每帧上限：50 条/帧是下限，密集批次按峰值放宽（上限 200）。
    replaySession.maxPerFrame = adaptiveCap(rows, replaySession.speed)

    // ⚠️ 2026-10-18：**空批次绝不能进入回放模式**。
    //    `observed_event` 有保留期清理（攻击批次 3 天），而 `drill_record` 不参与那次清理
    //    ⇒ 历史批次下拉里可能有「记录还在、台账已删空」的孤儿批次。旧代码在这里把
    //    `active=true`、`done=true` 后直接 return —— 页面会显示「进度 0/0 · 已放完」，
    //    看起来像放完了，实际是假死（用户批文点名禁止）。
    //    现在的处置：**先退出会话**（`resetSession` 会把 active/loading/cur 全部归零，
    //    三个页面据此恢复实时流），再把错误挂回这个批次并抛给调用方显示。
    //    ⚠️ `batchId` 要在 reset **之后**写回：攻击页那行错误是按
    //    `replaySession.batchId === r.BatchId` 匹配的，清掉它就看不到错误了。
    if (!rows.length) {
      const message = `该批次历史数据已过期被清理，无法回放（批次 ${batchId}）`
      resetSession()
      replaySession.batchId = batchId
      replaySession.err = message
      throw new Error(message)
    }
    // ⚠️ 先 `active` 后 `loading`：日志页有一个 `loading: true→false` 的兜底 watch
    //    （装载失败时恢复实时流）—— 顺序反了会在成功的这一格误触发一次重连。
    replaySession.active = true
    replaySession.loading = false
    replaySession.done = false
    startClock()
    return replaySession
  } catch (e) {
    replaySession.loading = false
    replaySession.err = (e && (e.message || String(e))) || '回放装载失败'
    throw e
  }
}

/** 日志页挂载/回到屏时接手画行；先补上离开期间攒下的事件。 */
export function attachReplaySink(fn) {
  sink = fn
  if (!backlog.length) return
  const pending = backlog
  backlog = []
  let i = 0
  const drain = () => {
    if (!sink) {
      // 补画中途又离开：剩下的放回 backlog 头部，下次接手继续。
      backlog = pending.slice(i).concat(backlog)
      return
    }
    const chunk = pending.slice(i, i + replaySession.maxPerFrame)
    i += chunk.length
    if (chunk.length) sink(chunk)
    // 与播放时钟同节拍补画（≈16ms 一块，每块不超过**本批自适应上限**）：切页回来时也是
    // 「流出来」，不是一次性铺满。分块大小与正常播放的 `maxPerFrame` 一致。
    if (i < pending.length) setTimeout(drain, REPLAY_TICK_MS)
  }
  setTimeout(drain, 0)
}

/** 日志页失活/卸载时交还画行权（事件继续进 backlog，不停播）。 */
export function detachReplaySink(fn) {
  if (sink === fn) sink = null
}

export function pauseReplaySession() {
  if (!replaySession.active || replaySession.done) return
  replaySession.paused = true
  lastNow = performance.now()
  // 暂停时长不算进「实测速率」窗口（否则续播后第一拍会被暂停的秒数摊薄）。
  rateStart = 0
  rateCount = 0
  replaySession.effectiveRate = 0
}

export function resumeReplaySession() {
  if (!replaySession.active || replaySession.done) return
  replaySession.paused = false
  lastNow = performance.now()
  rateStart = 0
  rateCount = 0
  if (!timer) startClock()
}

export function toggleReplayPause() {
  if (replaySession.paused) resumeReplaySession()
  else pauseReplaySession()
}

/**
 * 切换播放倍率（横幅的 1x / 2x / 5x / 10x）。
 * ⚠️ **只改倍率**：`virtualMs` / `cursor` / 已画出的行 / 指标拍一律不动 —— 用户点名
 *    「不能点一下 2x 就从头开始」。下一帧的 `dt × speed` 自然按新倍率推进。
 * ⚠️ `maxPerFrame` 按新倍率重算（高倍率下每秒要吐更多条）；重算只读已装载的 `rows`。
 */
export function setReplaySpeed(speed) {
  const next = Number(speed)
  if (!REPLAY_SPEEDS.includes(next) || next === replaySession.speed) return
  replaySession.speed = next
  replaySession.maxPerFrame = adaptiveCap(rows, next)
  // 从「现在」重新计帧间隔：避免把切档前的那一小段按新倍率再算一遍。
  lastNow = performance.now()
  rateStart = 0
  rateCount = 0
}

/**
 * 「重新开始（重放）」：进度归零，从这批台账的第一条重播。
 *
 * <p>⚠️ 与 {@link stopReplaySession} 的区别：**不退出回放、不重新请求后端**。
 *    批次、原时间轴、已装载行、当前倍速都保留，只把播放位置、游标、指标拍与
 *    状态流转清回起点 —— 重放读的是同一份历史，不该再走一次网络。
 * <p>⚠️ `restartSeq` 自增是给三页的**信号**：日志页要清掉已画的回放行，状态页要清掉
 *    重建出的五态计数 / 耗时样本 / 趋势样本。没有这个信号，重放后旧画面会与新行叠在一起。
 * <p>⚠️ 时钟仍是「真实时间差值」：从第一条事件的 `OccurredAt` 重新起算，慢速档照样
 *    `virtualMs += Δt × speed`，不会把历史时间戳改写成墙钟。
 */
export function restartReplaySession() {
  if (!replaySession.active || !rows.length) return
  stopClock()
  backlog = []
  virtualMs = 0
  lastNow = 0
  metricWindow = createMetricWindow()
  rateStart = 0
  rateCount = 0
  replaySession.cursor = 0
  replaySession.paused = false
  replaySession.done = false
  replaySession.playing = false
  replaySession.elapsedMs = 0
  replaySession.clockTs = replaySession.startTs
  replaySession.effectiveRate = 0
  replaySession.metricTicks = []
  replaySession.stateChanges = []
  replaySession.metricSeq = 0
  replaySession.stateSeq = 0
  replaySession.maxPerFrame = adaptiveCap(rows, replaySession.speed)
  replaySession.restartSeq += 1
  startClock()
}

/** 等到下一帧：让横幅的 loading 遮罩先上屏，再开始同步重建（否则 35k 条的重建
 *  会在同一个任务里做完，用户看不到任何「正在计算」的反馈）。 */
function nextFrame() {
  return new Promise((resolve) => {
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(() => resolve())
    else setTimeout(resolve, 0)
  })
}

/** 首个 `off > targetOff` 的下标（二分）。返回它 = 目标时刻**已发生**的事件条数。 */
function upperBoundByOff(targetOff) {
  let lo = 0
  let hi = rows.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (rows[mid].off <= targetOff) lo = mid + 1
    else hi = mid
  }
  return lo
}

/**
 * 拖拽定位（Seek）：把播放位置跳到 `ratio`（0~1），并**重建那一刻的全部状态**。
 *
 * <p>步骤（与用户 2026-10-18 的「视频播放器」规格逐条对应）：
 * <ol>
 *   <li>立刻停表（暂停自动播放）；`seeking=true` 让横幅画 loading 遮罩。</li>
 *   <li>按 `ratio × spanMs` 换算出目标时间戳，再用二分找到 `0 → 目标索引`。</li>
 *   <li>发 `seekSeq` 信号，让日志页清行、状态页清状态机；清空指标拍数组
 *       （指标页的图表全部是 `metricTicks` 的 computed，数组一空即重绘为空）。</li>
 *   <li>在一个同步批次里把 `0 → 目标索引` 的事件**全量 apply 一遍**：指标窗口逐条重算、
 *       指标拍逐秒补齐、订单流转逐条入列、日志行一把交给日志页 —— 得到的就是那一刻的
 *       积压 / QPS / 流轨 / 明细，而不是「目标之前的状态 + 目标之后的增量」。</li>
 *   <li>若之前是播放态，从新位置继续播；若之前是暂停/已放完，则停在新位置等用户继续。</li>
 * </ol>
 *
 * <p>⚠️ **拖拽过程中不调用它**：横幅的 `@input` 只改时间文字，`@change`（松开鼠标）
 *    才调用一次。这里自己也按世代号防重入：快速连拖时旧的重建会在下一个 await 处作废。
 *
 * @param {number} ratio 0~1
 * @param {{resume?: boolean}} [opts] `resume`：重建完成后是否继续播（默认按当前是否在播推断；
 *        横幅在 pointerdown 时已经暂停了时钟，所以会把「拖拽前是否在播」显式传进来）
 */
export async function seekReplaySession(ratio, opts) {
  if (!replaySession.active || !rows.length) return
  const token = ++seekToken
  const targetRatio = Math.max(0, Math.min(1, Number(ratio) || 0))
  const resume = opts && typeof opts.resume === 'boolean'
    ? opts.resume
    : (!replaySession.paused && !replaySession.done)

  // ① 立刻停表：拖拽/重建期间不允许时钟继续往前走（否则重建完又被下一帧覆盖）。
  stopClock()
  replaySession.playing = false
  replaySession.seeking = true
  replaySession.seekPct = Math.round(targetRatio * 100)
  replaySession.rebuilding = true

  // ② 先让遮罩上屏一帧，再做同步重建。
  await nextTick()
  await nextFrame()
  if (token !== seekToken) return

  try {
    // ③ 目标时间戳与目标索引。`spanMs` = 首末事件的差；拖动 40% ⇒ 日志时间 40% 处。
    //    ⚠️ 极端批次（所有事件同一毫秒、spanMs=0）没有时间轴可拖 ⇒ 退回按条数比例。
    let targetOff = 0
    let idx = 0
    if (replaySession.spanMs > 0) {
      targetOff = targetRatio * replaySession.spanMs
      idx = upperBoundByOff(targetOff)
    } else {
      idx = Math.round(targetRatio * rows.length)
      targetOff = idx > 0 ? rows[idx - 1].off : 0
    }
    const atEnd = idx >= rows.length
    if (atEnd) {
      idx = rows.length
      targetOff = replaySession.spanMs
    }

    // ④ 清空「三页共享的派生状态」。指标页的图表全是 computed，数组一空就清空；
    //    日志页 / 状态页没有 computed 源，需要 `seekSeq` 信号。
    replaySession.metricTicks = []
    replaySession.metricSeq = 0
    replaySession.stateChanges = []
    replaySession.stateSeq = 0
    replaySession.cursor = 0
    replaySession.elapsedMs = 0
    replaySession.clockTs = replaySession.startTs
    // 指标窗口 / backlog 是模块级的，必须一起归零：否则重建会从旧窗口的 `lastOff`
    // 继续推进（往前的 seek 出不了拍，往后的 seek 会把旧队列里的未来事件算进来）。
    metricWindow = createMetricWindow()
    backlog = []
    replaySession.seekSeq += 1
    await nextTick()
    if (token !== seekToken) return

    // ⑤ 把 0 → 目标索引全量重放。指标窗口、指标拍、状态流转、日志行进同一份历史。
    const applied = idx > 0 ? rows.slice(0, idx) : []
    // ⚠️ 指标窗口必须**先把这一整段全部入队，再统一推进到目标时刻**。
    //    逐条 `push → advance` 会在「同一毫秒有多条事件」时丢数：推进到边界的那一条
    //    提前生成本秒拍，同毫秒的后续条还没来得及入队，而它们又因 `off <= 左边界`
    //    永远进不了下一拍（实测 40% 处本拍少 1 条）。一次性推进由 `advanceMetricWindow`
    //    按 `(from, boundary]` 过滤，同毫秒的事件全部落在同一拍。
    for (const row of applied) pushMetricEvent(metricWindow, row.event, row.off)
    appendMetricTicks(advanceMetricWindow(metricWindow, targetOff))
    appendStateChanges(applied)
    if (applied.length) deliver(applied.map((row) => row.event))

    // ⑥ 落位。`virtualMs` 与目标时间戳一致，下一帧不会补吐/倒退。
    virtualMs = targetOff
    lastNow = performance.now()
    rateStart = 0
    rateCount = 0
    replaySession.cursor = idx
    replaySession.elapsedMs = atEnd ? replaySession.spanMs : targetOff
    replaySession.clockTs = atEnd ? replaySession.endTs : replaySession.startTs + targetOff
    replaySession.playing = false
    replaySession.effectiveRate = 0

    if (atEnd) {
      // 拖到最右 = 放完：与正常播放的收尾同口径（补最后半拍、游标落在末尾）。
      appendMetricTicks(flushMetricWindow(metricWindow, replaySession.spanMs))
      replaySession.done = true
      replaySession.paused = false
    } else {
      replaySession.done = false
      // 之前是播放态 ⇒ 从新位置继续；之前是暂停/已放完 ⇒ 停在新位置等用户继续。
      replaySession.paused = !resume
      if (resume) startClock()
    }

    await nextTick()
  } finally {
    // 只有最新一代 seek 有资格关遮罩（快速连拖时，旧的一代不能把新的一代的遮罩关掉）。
    if (token === seekToken) {
      replaySession.rebuilding = false
      replaySession.seeking = false
      replaySession.seekPct = 0
    }
  }
}

/** 退出回放：停表、放掉全量数组。日志页/指标页各自 watch `active` 恢复实时。 */
export function stopReplaySession() {
  stopClock()
  sink = null
  resetSession()
}
