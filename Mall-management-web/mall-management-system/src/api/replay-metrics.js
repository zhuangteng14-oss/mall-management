// src/api/replay-metrics.js —— 回放指标的**纯计算层**（2026-10-17 · 全链路联动回放）。
//
// 【它算什么】
//   后端 `/admin/observe/replay` 只回 `observed_event` 台账（事件），**没有**逐秒的 HTTP 指标帧
//   （`MetricsHistoryService` 那个 60 拍窗口是全局实时环，不按批次、跑完就被覆盖）。
//   本文件把「已经吐出来的事件」按**日志时间**做 1 秒滑动窗口，算出：
//     QPS（事件吞吐）/ 业务失败率（**error only**）/ 警告率（warn）/ 防护拦截率（protect）
//     / 均值与 P99 延迟（仅 DurationUs 有值的样本）/ 按类计数。
//
// 【口径边界（页面必须照抄，别把推算说成原始指标）】
//   · 这不是 HTTP QPS：分母是「台账里的事件条数」，不是后端 `http.server.requests` 的请求数。
//   · 没有 4xx/5xx 拆分：台账只有 `severity`（info/protect/warn/error），没有状态码。
//   · 业务失败率 = **error ÷ 事件数**（2026-10-17 用户重构口径：只有数据链断裂/崩溃算红）；
//     warn（降级/重试中）单列为警告率，protect（主动拦截）单列为防护拦截率，
//     两者都**不进业务失败率 / 健康度**。
//   · 没有缓存命中率 / 锁等待：台账没有对应字段，本文件不猜。
//   · `DurationUs` 只有部分事件有（审计 / 通知 / 身份 / 敏感词 / 限流拒绝等）⇒ 无样本时
//     均值 / P99 一律 `null`（页面画断点），**不画 0** —— 0 ms 是「很快」，null 是「没测到」。
//
// 【为什么单独成文件】
//   播放器（`replay-session.js`）只负责时钟与分发；这里是可以单测的纯函数，
//   窗口、分位数、空窗口这些边界不混进 rAF 主循环。

import { KINDS } from '../config/labels/observe-labels'

/** 指标窗口长度：日志时间 1 秒（与实时指标页的「每秒 1 拍」同口径）。 */
export const METRIC_WINDOW_MS = 1000

/** 新建一个窗口。`queue` 里只放**已经吐给页面**的事件（绝不吃未来事件）。 */
export function createMetricWindow() {
  return {
    /** `{ off, kind, severity, durationMs }`，按 off 升序（事件本身按时间吐）。 */
    queue: [],
    /** 上一个完整窗口的右边界（毫秒偏移）；0 = 还没出过完整拍。 */
    lastOff: 0,
    /** 已完成样本数（只用于调试/报告，不参与计算）。 */
    seq: 0,
  }
}

/**
 * 记一条**已吐出**的事件。`off` 是它相对首条事件的毫秒偏移。
 * ⚠️ `durationUs` 是微秒（后端列名如此）；这里先换算成毫秒，窗口里不再做单位变换。
 */
export function pushMetricEvent(win, event, off) {
  if (!win || !event) return
  const rawUs = event.durationUs
  const hasDuration = rawUs !== null && rawUs !== undefined && rawUs !== '' && Number.isFinite(Number(rawUs))
  win.queue.push({
    off: Number(off) || 0,
    kind: event.kind ? String(event.kind) : '',
    severity: event.severity ? String(event.severity) : '',
    durationMs: hasDuration ? Number(rawUs) / 1000 : null,
  })
}

/** 最近秩分位数（`p=0.99` ⇒ 第 ceil(0.99n) 个）。空数组回 `null`，不是 0。 */
function percentile(sorted, p) {
  if (!sorted.length) return null
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))
  return sorted[idx]
}

/**
 * 对一个窗口内的样本做统计。
 * @param events 已经过滤到 `(from, to]` 的事件
 * @param off    窗口右边界（日志时间偏移）
 * @param windowMs 实际窗口长度（完整拍 = 1000；收尾那半拍会短）
 */
function sample(events, off, windowMs) {
  const byKind = {}
  for (const k of KINDS) byKind[k] = 0
  let errors = 0
  let warns = 0
  let protects = 0
  const durations = []
  for (const e of events) {
    if (e.kind && byKind[e.kind] !== undefined) byKind[e.kind] += 1
    if (e.severity === 'error') errors += 1
    else if (e.severity === 'protect') protects += 1
    else if (e.severity === 'warn') warns += 1
    if (e.durationMs !== null && e.durationMs !== undefined) durations.push(e.durationMs)
  }
  durations.sort((a, b) => a - b)
  const count = events.length
  const seconds = Math.max(0.001, Number(windowMs) / 1000)
  const mean = durations.length
    ? durations.reduce((a, b) => a + b, 0) / durations.length
    : null
  return {
    off,
    windowMs,
    count,
    // 「事件推算 QPS」：窗口内事件条数 ÷ 窗口秒数。不是 HTTP 请求率（见文件头）。
    qps: count / seconds,
    errors,
    warns,
    protects,
    /** 业务失败数：**只有 error**（数据链断裂 / 崩溃）。⚠️ warn、protect 均不计入。 */
    fails: errors,
    /** 业务失败率 = error ÷ 事件数。 */
    failRate: count ? errors / count : null,
    /** 警告率 = warn ÷ 事件数。降级 / 重试中，单列展示，不进 failRate / 健康度。 */
    warnRate: count ? warns / count : null,
    /** 防护拦截率 = protect ÷ 事件数。只用于展示，不进 failRate / 健康度。 */
    protectRate: count ? protects / count : null,
    /** 与 failRate **同义**（保留旧字段名，避免破坏既有调用）；红色只数 error。 */
    errorRate: count ? errors / count : null,
    meanMs: mean === null ? null : Math.round(mean * 10) / 10,
    p99Ms: percentile(durations, 0.99),
    latencyCount: durations.length,
    byKind,
  }
}

/**
 * 把窗口推进到 `clockOff`，返回期间所有**完整**的 1 秒拍。
 *
 * - 第一个完整拍在 `off=1000`（窗口 `(0,1000]`）；
 * - 没有事件的秒也会出拍（`count=0 / qps=0`）—— 让图上的时间轴继续走，而不是「停住」；
 * - `queue` 只减不增；窗口左边界用 `<=` 弹出，右边界用 `<=` 收进（左右不重叠）。
 */
export function advanceMetricWindow(win, clockOff) {
  if (!win) return []
  const out = []
  let boundary = win.lastOff ? win.lastOff + METRIC_WINDOW_MS : METRIC_WINDOW_MS
  const target = Number(clockOff) || 0
  while (boundary <= target) {
    const from = boundary - METRIC_WINDOW_MS
    while (win.queue.length && win.queue[0].off <= from) win.queue.shift()
    // 时钟可能一次跨过多个边界（事件稀疏），队列里也可能有晚于本边界的事件
    // （它们在后续拍里用）⇒ 按 `(from, boundary]` 精确过滤。
    const events = win.queue.filter((e) => e.off > from && e.off <= boundary)
    out.push(sample(events, boundary, METRIC_WINDOW_MS))
    win.lastOff = boundary
    win.seq += 1
    boundary += METRIC_WINDOW_MS
  }
  return out
}

/**
 * 收尾：把最后一个不完整的窗口也出一拍（否则回放结束后最后几百毫秒永远不上图）。
 * 已经出过完整拍时窗口 = `(lastOff, clockOff]`；一拍都没出过时窗口 = `(0, clockOff]`
 * （此时 `windowMs` 就是整段跨度，`qps` 仍按真实秒数折算，不是拿 1 秒冒充）。
 */
export function flushMetricWindow(win, clockOff) {
  if (!win) return []
  const target = Number(clockOff) || 0
  if (target <= win.lastOff) return []
  const from = win.lastOff
  while (win.queue.length && win.queue[0].off <= from) win.queue.shift()
  const events = win.queue.filter((e) => e.off > from && e.off <= target)
  const windowMs = target - from
  const tick = sample(events, target, windowMs)
  win.lastOff = target
  win.seq += 1
  return [tick]
}
