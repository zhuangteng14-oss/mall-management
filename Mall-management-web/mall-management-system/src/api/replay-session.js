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
//     横幅上可切 1x / 2x / 5x / 10x；切换**不重置** `virtualMs` / `cursor`。
//   · 每帧上限 `maxPerFrame` 不是常数：装载完成后按该批次的**峰值事件密度**算
//     `clamp(ceil(peakPerSec × speed / 60), 50, 200)`（50 = 用户定的基线，200 = 防一帧
//     把主线程打爆的天花板）。稀疏批次 50 条/帧就够，密集批次自动放宽，**不靠 5 条/帧慢放**。
//     切倍速时按新倍率**重算**这个上限（不重新装载，也不动播放位置）。
//   · 到期但没吐完（上限真的被顶住）时，播放位置钳在已吐出的最后一条上，下一帧继续 ——
//     三页才在同一秒；横幅如实显示「实际速率 / 本帧上限」，不拿 10× 冒充。
//   · 播放是**本地时钟**：后端在装载之后不再参与（一次性的只读 SELECT）。
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

import { reactive } from 'vue'
import { replay as fetchReplay } from './observe'
import { advanceMetricWindow, createMetricWindow, flushMetricWindow, pushMetricEvent } from './replay-metrics'
// 回放只进 `normal` 桶（日志页的 `absorbEvents(..., 'normal')`）：若「模拟日常」还开着，
// 日志页的 `cur` 会指向 demo 桶 ⇒ 回放行写了却看不见。进回放前强制切回正常源。
import { setDemoMode } from './demo-stream'

/** 播放时钟节拍：**rAF**（帧同步，≈16ms）——由用户「不要抽帧感」那条指令定死。 */
export const REPLAY_TICK_MS = 16
/** 默认时间倍率：1 秒墙钟 = 1 秒日志（用户 2026-10-17：「点回放就直接 10× 狂奔」要改掉）。 */
export const REPLAY_SPEED = 1
/** 横幅上可选的倍速档位（声明序 = 按钮从左到右）。 */
export const REPLAY_SPEEDS = [1, 2, 5, 10]
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

/** SQL 的 `CAST(OccurredAt AS CHAR)`（库内 UTC、无时区文本）→ epoch 毫秒。 */
function epochOf(sqlTime) {
  if (sqlTime === null || sqlTime === undefined || sqlTime === '') return 0
  const ms = new Date(`${String(sqlTime).replace(' ', 'T')}Z`).getTime()
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

/** 从本拍吐出的事件里摘出订单流转（from/to 原样保留，由状态页的 `stateKeyOf` 翻译）。 */
function appendStateChanges(batchRows) {
  for (const row of batchRows) {
    const e = row.event
    if (!e || e.kind !== 'order') continue
    const f = e.fields || {}
    const to = f.to
    if (!to) continue
    const orderId = f.order === null || f.order === undefined ? e.objectId : f.order
    if (orderId === null || orderId === undefined || orderId === '') continue
    replaySession.stateSeq += 1
    replaySession.stateChanges.push({
      seq: replaySession.stateSeq,
      ts: e.ts,
      off: row.off,
      orderId: String(orderId),
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
    const data = await fetchReplay(batchId)
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
    // ⚠️ 先 `active` 后 `loading`：日志页有一个 `loading: true→false` 的兜底 watch
    //    （装载失败时恢复实时流）—— 顺序反了会在成功的这一格误触发一次重连。
    replaySession.active = true
    replaySession.loading = false
    replaySession.done = rows.length === 0
    if (!rows.length) return replaySession
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

/** 退出回放：停表、放掉全量数组。日志页/指标页各自 watch `active` 恢复实时。 */
export function stopReplaySession() {
  stopClock()
  sink = null
  resetSession()
}
