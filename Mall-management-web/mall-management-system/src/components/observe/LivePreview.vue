<template>
  <!--
    攻击页「三块实时预览」（批四 · 第一批 1；2026-10-18 视觉升维）。

    【三列各自是什么】
      ① 指标 = 指标页 KPI 卡（`KpiSparkCard`，带 Sparkline 微条）× 3：QPS / 平均延迟(P99) /
         缓存命中率。**每拍只更新一次**（指标 SSE 本身就是 1 秒一拍），没有第二条重绘路径。
      ② 订单 = 状态页 SSE 实时流轨的**紧凑版**：时间 / 单号 / 四节点轨道（走过的段流动、当前
         节点发光）/ 右侧「待付款 → 已取消」。最多 10 行、有滚动条；入队按 **300ms 批量**，
         新行只带一颗一次性飞行的光点 —— 动画行数恒为 1，不随压测并发涨。
      ③ 日志 = 纯文字滚动（性能最好），保留 PROTECT 紫 / WARN 黄 / ERROR 红的分级左缘与标签。

    【性能红线（用户 2026-10-18 明令：500 / 2000 并发不能拖垮主页面）】
      · 指标：只吃 SSE 帧（1/s），Sparkline 只保留 20 个点，无定时器。
      · 订单：**300ms 合帧 + 单次 rAF 滚动**；每拍最多取 6 条，列表上限 10 行；待处理队列
        上限 30 条（超了丢最旧的）—— 压测时 moves 一拍可能几百条，预览不是台账。
      · 日志：每拍最多 6 条、列表上限 40 行；**没有任何逐行动画**。
      · 三条流在 `onBeforeUnmount` 一起 close，切走页面不再重连（探针实测 10 秒零新开）。

    【为什么不画大图】块 7 的「攻击页不画图」裁决仍然成立：这里没有 import 任何
      `components/charts/*`；KPI 微条与流轨都是 DOM/CSS，不是 ECharts。
  -->
  <section class="lp">
    <!-- 标题由宿主页的 `.a-h2` 出（与 ③/④ 同款）；这里只留一行总状态。 -->
    <header class="lp-head">
      <span class="lp-live" :class="overallState">
        <i class="lp-dot"></i>{{ overallText }}
      </span>
    </header>

    <div class="lp-grid">
      <!-- ① 指标：KPI 卡 + Sparkline ------------------------------------------ -->
      <article class="lp-col">
        <header class="lp-col-h">
          <IconGlyph name="metrics" tone="muted" />
          <b>指标</b>
          <span class="lp-conn" :class="conn.metrics">{{ connText(conn.metrics) }}</span>
        </header>
        <div class="lp-kpis">
          <KpiSparkCard
            label="QPS"
            :value="mKpi.qps === null ? '—' : fmt(mKpi.qps, 1)"
            unit="次/秒"
            :detail="`本拍请求 ${fmtInt(mKpi.count)} 次`"
            :spark="qpsSpark"
          />
          <KpiSparkCard
            label="平均延迟"
            :value="mKpi.mean === null ? '—' : fmtInt(mKpi.mean)"
            unit="ms"
            :detail="`P99 ${mKpi.p99 === null ? '—' : fmtInt(mKpi.p99)} ms · 衰减窗口`"
            :spark="meanSpark"
          />
          <KpiSparkCard
            label="缓存命中率"
            :value="mKpi.hitRate === null ? '—' : fmt(mKpi.hitRate * 100, 1)"
            unit="%"
            :detail="`命中 ${fmtInt(mKpi.hit)} · 未命中 ${fmtInt(mKpi.miss)}`"
            :spark="hitSpark"
            color="var(--c3)"
          />
        </div>
      </article>

      <!-- ② 订单：SSE 实时流轨（紧凑版） --------------------------------------- -->
      <article class="lp-col">
        <header class="lp-col-h">
          <IconGlyph name="flow" tone="muted" />
          <b>订单</b>
          <span class="lp-conn" :class="conn.orders">{{ connText(conn.orders) }}</span>
        </header>
        <p class="lp-kpi lp-kpi-flow">
          <span v-for="s in FLOW_STATES" :key="s.k">{{ orderFlowStateLabel(s.k) }} <b>{{ countOf(s.k) }}</b></span>
        </p>
        <ol ref="oList" class="lp-rail" aria-label="订单流实时流轨">
          <li v-for="m in oLines" :key="m.id" class="lp-move" :class="{ live: m.fresh }">
            <time>{{ m.t }}</time>
            <span class="lp-id" :title="'#' + m.orderId">#{{ m.orderId }}</span>
            <span class="lp-track" :style="{ '--lc': m.color }" aria-hidden="true">
              <template v-for="n in 4" :key="n">
                <i class="lp-node" :class="{ on: m.step >= n, now: m.step === n }"></i>
                <i v-if="n < 4" class="lp-seg" :class="{ on: m.step > n }"></i>
              </template>
              <!-- 只在「刚进来的那一行」画一颗飞行光点（一次性动画，不驻留、不循环）。 -->
              <i v-if="m.fresh" class="lp-fly"></i>
            </span>
            <span class="lp-d" :style="{ color: m.color }">
              <template v-if="m.from">{{ orderFlowStateLabel(m.from) }} → </template><b>{{ orderFlowStateLabel(m.to) }}</b>
            </span>
          </li>
          <li v-if="!oLines.length" class="lp-wait">等待流转（有单子动才会出现行）…</li>
        </ol>
      </article>

      <!-- ③ 日志：纯文字滚动 + 严重度分级 -------------------------------------- -->
      <article class="lp-col">
        <header class="lp-col-h">
          <IconGlyph name="logs" tone="muted" />
          <b>日志</b>
          <span class="lp-conn" :class="conn.events">{{ connText(conn.events) }}</span>
        </header>
        <p class="lp-kpi">
          <span>本拍 <b>{{ eFresh }}</b> 条</span>
          <span>累计预览 <b>{{ eTotal }}</b> 条</span>
        </p>
        <ul ref="eList" class="lp-list lp-log" aria-label="日志流滚动预览">
          <li v-for="l in eLines" :key="l.id" :class="'sev-' + l.sev">
            <time>{{ l.t }}</time>
            <span>
              <i class="lp-sev">{{ l.sevText }}</i>
              <i class="lp-kind" :style="{ color: l.color }">{{ l.kind }}</i>{{ l.text }}
            </span>
          </li>
          <li v-if="!eLines.length" class="lp-wait">等待事件…</li>
        </ul>
      </article>
    </div>
  </section>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `../charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { openEventStream, openMetricsStream, openOrderStream } from '../../api/observe'
import { KIND_COLOR, kindLabel, orderFlowStateLabel } from '../../config/labels/observe-labels'
import IconGlyph from '../ui/IconGlyph.vue'
import KpiSparkCard from './KpiSparkCard.vue'

/** Sparkline 柱数（与指标页 `sparkBars(..., 20)` 一致）。 */
const SPARK_BINS = 20
/** 订单流轨最多留几行（用户 2026-10-18 明令：最多 10 条，配滚动条）。 */
const ORDER_CAP = 10
/** 订单入队批处理窗口：压测时 moves 一拍几百条，300ms 合一次、每拍最多取 6 条。 */
const ORDER_FLUSH_MS = 300
const ORDER_PER_FRAME_CAP = 6
/** 待处理队列上限：再快也只留最近 30 条，预览不承担台账职责。 */
const ORDER_PENDING_CAP = 30
/** 日志每列保留行数 / 单拍最多入列条数（与首版一致，性能已验证）。 */
const LOG_CAP = 40
const LOG_PER_FRAME_CAP = 6

const FLOW_STATES = [
  { k: 'waitPay' },
  { k: 'waitShip' },
  { k: 'waitReceive' },
  { k: 'completed' },
  { k: 'cancelled' },
]
/**
 * 后端 `moves[].from/to` 发的可能是 `OrderState` 枚举名（真后端当前就是这套），也可能是五态
 * key 本身。⚠️ **与状态页 `OrderFlow.vue#STATE_KEY_OF_ORDER_STATE` 逐字同表**（那页的注释
 * 解释了每个枚举名怎么从三个布尔位推出来）；两张表必须一起改。
 */
const STATE_KEY_OF_ORDER_STATE = {
  CREATED: 'waitPay',
  PAID: 'waitShip',
  SHIPPED: 'waitReceive',
  RECEIVED_NODELIVERY: 'completed',
  COMPLETED: 'completed',
  CANCELLED: 'cancelled',
  ILLEGAL_DELIVERED_UNPAID: 'waitReceive',
  ILLEGAL_RECEIVED_UNPAID: 'completed',
  ILLEGAL_DELIVERED_RECEIVED_UNPAID: 'completed',
}
const stateKeyOf = (v) => (v ? STATE_KEY_OF_ORDER_STATE[v] || (STATE_COLOR[v] ? v : '') : '')

/** 四节点轨道上的步号（`cancelled` 是支线，不占步）。与状态页 `OrderFlow.vue` 同表。 */
const STATE_STEP = { waitPay: 1, waitShip: 2, waitReceive: 3, completed: 4 }
/** 与状态页 `stateColor()` 逐字同表：轨道/文字用同一个颜色语义。 */
const STATE_COLOR = {
  waitPay: 'var(--c4)',
  waitShip: 'var(--c1)',
  waitReceive: 'var(--c6)',
  completed: 'var(--c3)',
  cancelled: 'var(--ink-3)',
}
/** 日志严重度色（PROTECT 紫 / WARN 黄 / ERROR 红），与日志页 `SEVERITY_COLOR` 同源。 */
const SEV_TEXT = { info: 'INFO', protect: 'PROTECT', warn: 'WARN', error: 'ERROR' }

const mKpi = ref({ qps: null, mean: null, p99: null, hitRate: null, count: 0, hit: 0, miss: 0 })
const qpsHist = ref([])
const meanHist = ref([])
const hitHist = ref([])
const oCounts = ref({})
const oLines = ref([])
const eFresh = ref(0)
const eTotal = ref(0)
const eLines = ref([])
const conn = ref({ metrics: 'wait', orders: 'wait', events: 'wait' })

const oList = ref(null)
const eList = ref(null)

let uid = 0
let handles = []
/** 订单待入队队列 + 唯一的 300ms 定时器（任何时刻最多一个在飞）。 */
let pendingMoves = []
let flushTimer = 0
let scrollRaf = 0

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
const fmt = (v, d) => (v === null || v === undefined ? '—' : Number(v).toFixed(d))
const fmtInt = (v) => {
  const n = num(v)
  return n === null ? '—' : Math.round(n).toLocaleString('zh-CN')
}
const countOf = (k) => num((oCounts.value || {})[k]) || 0
const hms = (ts) => {
  // 后端 `ts` 可能是 epoch 毫秒（数字或数字串），也可能已经是 ISO 串 ⇒ 两种都认。
  const numeric = typeof ts === 'number' || (typeof ts === 'string' && /^\d+$/.test(ts))
  const d = ts === undefined || ts === null ? new Date() : new Date(numeric ? Number(ts) : ts)
  const safe = Number.isNaN(d.getTime()) ? new Date() : d
  return safe.toLocaleTimeString('zh-CN', { hour12: false })
}

/** 与指标页 `MetricsPanel.vue#sparkBars` 同款：取最近 N 点，按峰值归一到 0..100。 */
function sparkBars(values, bins = SPARK_BINS) {
  const arr = values.slice(-bins)
  const nums = arr.filter((v) => v !== null && v !== undefined && Number.isFinite(Number(v)))
  const max = Math.max(1, ...nums.map(Number))
  return arr.map((v) => {
    if (v === null || v === undefined || !Number.isFinite(Number(v))) return { h: 0, off: true }
    return { h: Math.max(4, Math.round((Number(v) / max) * 100)), off: false }
  })
}
const qpsSpark = computed(() => sparkBars(qpsHist.value))
const meanSpark = computed(() => sparkBars(meanHist.value))
const hitSpark = computed(() => sparkBars(hitHist.value))

function pushHist(target, v) {
  const next = target.value.length >= SPARK_BINS ? target.value.slice(1) : target.value.slice()
  next.push(v)
  target.value = next
}

/** 一帧最多滚一次（订单 + 日志）。rAF 合帧，避免每条入列都触发一次布局。 */
function scheduleScroll() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0
    nextTick(() => {
      for (const el of [oList.value, eList.value]) {
        if (el) el.scrollTop = el.scrollHeight
      }
    })
  })
}

// ── ① 指标：只吃 SSE 帧 ------------------------------------------------------
function onMetrics(f) {
  const req = f.requests || {}
  const cache = f.cache || {}
  const interval = num(f.intervalMs) || 0
  const count = num(req.count) || 0
  const qps = num(req.qps) !== null ? num(req.qps) : (interval > 0 ? count / (interval / 1000) : null)
  const mean = req.meanMs === null || req.meanMs === undefined ? null : num(req.meanMs)
  const p99 = req.p99Ms === null || req.p99Ms === undefined ? null : num(req.p99Ms)
  const hitRate = cache.hitRate === null || cache.hitRate === undefined ? null : num(cache.hitRate)
  mKpi.value = {
    qps,
    mean,
    p99,
    hitRate,
    count,
    hit: num(cache.hit) || 0,
    miss: num(cache.miss) || 0,
  }
  // 预热拍（intervalMs=0）差分量全是 0，塞进 Sparkline 会画出一根假的「掉到 0」柱。
  if (interval <= 0) return
  pushHist(qpsHist, qps)
  pushHist(meanHist, mean)
  pushHist(hitHist, hitRate)
}

// ── ② 订单：300ms 批量入队 + 单行光点 ----------------------------------------
function enqueueMove(m) {
  pendingMoves.push(m)
  if (pendingMoves.length > ORDER_PENDING_CAP) {
    pendingMoves.splice(0, pendingMoves.length - ORDER_PENDING_CAP)
  }
  if (flushTimer) return
  flushTimer = setTimeout(() => {
    flushTimer = 0
    flushMoves()
  }, ORDER_FLUSH_MS)
}

function flushMoves() {
  if (!pendingMoves.length) return
  const batch = pendingMoves
  pendingMoves = []
  const last = batch[batch.length - 1]
  for (const m of batch) {
    const toKey = stateKeyOf(m.to)
    oLines.value.push({
      id: ++uid,
      t: hms(m.ts),
      orderId: m.orderId == null ? '—' : m.orderId,
      from: stateKeyOf(m.from),
      // 认不出的枚举原样显示（与状态页 `moveStateLabel` 同一处置），不画成一个空白。
      to: toKey || (m.to ? String(m.to) : ''),
      step: STATE_STEP[toKey] || 0,
      color: STATE_COLOR[toKey] || 'var(--ink-3)',
      // 只有这一批的最后一行挂一次性光点；同批更早的行不再动 —— 动画行数恒为 1。
      fresh: m === last,
    })
  }
  if (oLines.value.length > ORDER_CAP) {
    oLines.value.splice(0, oLines.value.length - ORDER_CAP)
  }
  scheduleScroll()
}

function onOrders(f) {
  oCounts.value = f.counts || {}
  const moves = Array.isArray(f.moves) ? f.moves : []
  // 每拍最多取最后 6 条：一拍几百条时，前面的对「最近发生了什么」没有信息增量。
  for (const m of moves.slice(-ORDER_PER_FRAME_CAP)) enqueueMove(m)
}

// ── ③ 日志：纯文字 + 严重度分级 ---------------------------------------------
function onEvents(f) {
  const ev = Array.isArray(f.events) ? f.events : []
  eFresh.value = ev.length
  if (!ev.length) return
  for (const e of ev.slice(-LOG_PER_FRAME_CAP)) {
    eTotal.value += 1
    const sev = String(e.severity || 'info').toLowerCase()
    eLines.value.push({
      id: ++uid,
      t: hms(e.ts),
      text: String(e.title || ''),
      kind: kindLabel(e.kind),
      color: KIND_COLOR[e.kind] || undefined,
      sev: SEV_TEXT[sev] ? sev : 'info',
      sevText: SEV_TEXT[sev] || 'INFO',
    })
  }
  if (eLines.value.length > LOG_CAP) {
    eLines.value.splice(0, eLines.value.length - LOG_CAP)
  }
  scheduleScroll()
}

// ── 生命周期 ────────────────────────────────────────────────────────────────
function setConn(key, state) {
  conn.value = { ...conn.value, [key]: state }
}

function closeAll() {
  for (const h of handles) {
    try {
      h.close()
    } catch (e) {
      // 卸载路径上不抛：一个句柄关失败不该让另外两条留在后台重连。
    }
  }
  handles = []
}

function connectAll() {
  closeAll()
  handles = [
    openMetricsStream({
      onOpen: () => setConn('metrics', 'live'),
      onFrame: onMetrics,
      onLost: () => setConn('metrics', 'lost'),
    }),
    openOrderStream({
      onOpen: () => setConn('orders', 'live'),
      onFrame: onOrders,
      onLost: () => setConn('orders', 'lost'),
    }),
    openEventStream({
      onOpen: () => setConn('events', 'live'),
      onFrame: onEvents,
      onLost: () => setConn('events', 'lost'),
    }),
  ]
}

onMounted(connectAll)
// ⚠️ 用 onBeforeUnmount（不是 onUnmounted）：在子组件被拆掉**之前**就停流，
//    与 AttackConsole 页自身停 `pollStatus` 的时机一致，也留出 close() 同步收尾的余地。
onBeforeUnmount(() => {
  if (flushTimer) clearTimeout(flushTimer)
  flushTimer = 0
  pendingMoves = []
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
  scrollRaf = 0
  closeAll()
})

const connText = (s) => (s === 'live' ? '实时' : s === 'lost' ? '重连中' : '连接中')
const overallState = computed(() => {
  const v = Object.values(conn.value)
  return v.every((x) => x === 'live') ? 'ok' : v.some((x) => x === 'live') ? 'part' : 'off'
})
const overallText = computed(
  () => ({ ok: '三条流已接通', part: '部分流重连中', off: '正在建立三条流' })[overallState.value],
)
</script>

<style scoped>
.lp {
  margin-top: var(--s6);
}
.lp-head {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: var(--s3);
  flex-wrap: wrap;
  margin-bottom: var(--s3);
}
.lp-live {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.lp-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ink-3);
}
.lp-live.ok .lp-dot { background: var(--success); }
.lp-live.part .lp-dot { background: var(--warning); }

.lp-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s4);
}
@media (max-width: 1100px) {
  .lp-grid { grid-template-columns: minmax(0, 1fr); }
}

.lp-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s3);
}
.lp-col-h {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-sm);
  color: var(--ink);
}
.lp-col-h b { font-weight: 600; }
.lp-conn {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  padding: 1px 7px;
  border: 1px solid var(--line-2);
  border-radius: 999px;
}
.lp-conn.live { color: var(--success); border-color: var(--success); }
.lp-conn.lost { color: var(--warning); border-color: var(--warning); }

/* ① 指标：三张 KPI 卡纵向排（与指标页右栏同构）；总高对齐另两列的 180px 列表。 */
.lp-kpis {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 180px;
  padding-top: var(--s2);
}

.lp-kpi {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: var(--s2) 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.lp-kpi b {
  font-weight: 600;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.lp-kpi-flow { gap: 4px 10px; }

/* ② 订单流轨：一行 = 时间 / 单号 / 四节点 / 状态文本。右缘对齐，超出省略。 */
.lp-rail {
  list-style: none;
  margin: 0;
  padding: 0;
  height: 180px;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
}
.lp-move {
  display: grid;
  /* 单号列**定宽**（不是 auto）：压测单号是 `SIM-BURST-xxxxx-yy`，auto 会把它撑满整行、
     把右侧「待付款 → 待发货」挤成三个字。定宽后状态文字拿剩余宽度，超长省略。 */
  grid-template-columns: 46px minmax(0, 88px) 58px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  padding: 5px 0;
  border-bottom: 1px dashed var(--line);
  font-size: 11px;
}
.lp-move:last-child { border-bottom: 0; }
.lp-move time {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
}
.lp-id {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-2);
  font-family: var(--font-mono);
}
.lp-track {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
}
.lp-node {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--surface);
  border: 1px solid var(--line-2);
  box-sizing: border-box;
  flex: none;
}
.lp-node.on {
  background: var(--lc);
  border-color: var(--lc);
  box-shadow: 0 0 5px var(--lc);
}
/* 当前节点只做**一次性**脉冲（不是 infinite）：压测时列表里同时最多一行 .live。 */
.lp-move.live .lp-node.now {
  animation: lp-node-hit 1.1s ease-out 1;
}
.lp-seg {
  flex: 1;
  height: 1px;
  background-image: repeating-linear-gradient(90deg, var(--line-2) 0 3px, transparent 3px 6px);
}
.lp-seg.on {
  height: 2px;
  background-image: repeating-linear-gradient(90deg, var(--lc) 0 5px, transparent 5px 10px);
  animation: lp-seg-flow 0.9s linear 1;
}
/* 一次性光点：从轨道左端飞到右端后消失（`forwards` 停在透明帧）。 */
.lp-fly {
  position: absolute;
  left: 0;
  top: 50%;
  width: 6px;
  height: 6px;
  margin-top: -3px;
  border-radius: 50%;
  pointer-events: none;
  background: var(--lc);
  box-shadow: 0 0 6px var(--lc), 0 0 14px var(--lc);
  animation: lp-fly 1.1s cubic-bezier(0.25, 0.8, 0.35, 1) forwards;
}
@keyframes lp-node-hit {
  0% { box-shadow: 0 0 0 0 var(--lc), 0 0 6px var(--lc); }
  100% { box-shadow: 0 0 0 7px transparent, 0 0 2px transparent; }
}
@keyframes lp-seg-flow {
  from { background-position: 0 0; }
  to { background-position: -10px 0; }
}
@keyframes lp-fly {
  0% { left: 0; opacity: 0; transform: scale(0.4); }
  12% { opacity: 1; transform: scale(1); }
  82% { opacity: 1; transform: scale(1); }
  100% { left: calc(100% - 6px); opacity: 0; transform: scale(0.7); }
}
.lp-d {
  min-width: 0;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lp-d b { color: var(--ink); font-weight: 600; }

/* ③ 日志：纯文字滚动，严重度只染色不带动画。 */
.lp-list {
  list-style: none;
  margin: 0;
  padding: 0;
  height: 180px;
  overflow-y: auto;
  overflow-x: hidden;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  line-height: 1.55;
  color: var(--ink-2);
  scrollbar-width: thin;
}
.lp-list li {
  display: flex;
  gap: 8px;
  padding: 1px 0;
  border-left: 2px solid transparent;
  padding-left: 6px;
}
.lp-log li.sev-protect { border-left-color: var(--protect); }
.lp-log li.sev-warn { border-left-color: var(--warning); }
.lp-log li.sev-error { border-left-color: var(--danger); }
.lp-list time {
  flex: none;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.lp-list span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.lp-sev {
  font-style: normal;
  font-size: 9px;
  margin-right: 6px;
  letter-spacing: 0.04em;
}
.sev-info .lp-sev { color: var(--info); }
.sev-protect .lp-sev { color: var(--protect); }
.sev-warn .lp-sev { color: var(--warning); }
.sev-error .lp-sev { color: var(--danger); }
.lp-kind {
  font-style: normal;
  margin-right: 6px;
}
.lp-wait {
  color: var(--ink-3);
  font-family: var(--font);
}
</style>
