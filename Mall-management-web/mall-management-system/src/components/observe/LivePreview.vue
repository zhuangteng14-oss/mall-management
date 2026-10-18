<template>
  <!--
    攻击页「三块实时预览」（批四 · 第一批 1）。

    【它是什么】把指标 / 订单 / 日志页各自在消费的那三条 SSE 流**再开一份**，三列纯滚动展示。
      不画图、不筛选、不暂停 —— 本组件**只有一个职责**：让人在编排页就能看见「刚才那一轮
      在别的页面上长什么样」，而不必来回切页。

    【为什么不是三张图】《块 7 参考》§1.1 的「攻击页不画图」仍然成立：这里没有 import 任何
      `components/charts/*`，只有文字行。它展示的是**流本身**（每秒一行），不是图。
      原来那条「本页不 import 任何图表组件」的注释已随本组件改成「不 import 图表组件，但展示
      三条流的文字预览」——见 `AttackConsole.vue` 块首。

    【生命周期 —— 本组件最容易写错的一处】
      三条流由 `openMetricsStream` / `openOrderStream` / `openEventStream` 打开，它们**自带
      指数退避重连**（`api/observe.js#openResilientStream`）⇒ 不 `close()` 就会在页面切走之后
      永远每 8 秒打一次请求。`onBeforeUnmount` 里三条一起 close；`onMounted` 才建流。
      ⚠️ 只 close 不置空是不够的：`close()` 停的是**那一条**（连同它的重连定时器），
         数组里留着旧句柄再连一次会重复计数，所以在 `closeAll()` 里把它置空。

    【不卡主线程】
      每列只保留 `CAP` 行（默认 40），到顶从头部丢；一次 rAF 只滚一次、只在真有新行时滚。
      事件流一拍可能回几十条（压测时），这里**每拍最多取 `PER_FRAME_CAP` 条**，剩下的丢弃
      —— 预览不是台账，日志页才是（那里有完整缓冲与筛选）。
  -->
  <section class="lp">
    <!-- 标题由宿主页的 `.a-h2` 出（与 ③/④ 同款）；这里只留一行总状态，
         否则「实时预览」会上下叠两遍（首版截图里正是这样）。 -->
    <header class="lp-head">
      <span class="lp-live" :class="overallState">
        <i class="lp-dot"></i>{{ overallText }}
      </span>
    </header>

    <div class="lp-grid">
      <!-- ① 指标 ------------------------------------------------------------ -->
      <article class="lp-col">
        <header class="lp-col-h">
          <IconGlyph name="metrics" tone="muted" />
          <b>指标</b>
          <span class="lp-conn" :class="conn.metrics">{{ connText(conn.metrics) }}</span>
        </header>
        <p class="lp-kpi">
          <span><b>{{ fmt(mKpi.qps, 1) }}</b> QPS</span>
          <span>P99 <b>{{ fmt(mKpi.p99, 0) }}</b> ms</span>
          <span>命中 <b>{{ pct(mKpi.hitRate) }}</b></span>
          <span>错误 <b>{{ pct(mKpi.errorRate) }}</b></span>
        </p>
        <ul ref="mList" class="lp-list" aria-label="指标流滚动预览">
          <li v-for="l in mLines" :key="l.id" :class="l.tone">
            <time>{{ l.t }}</time><span>{{ l.text }}</span>
          </li>
          <li v-if="!mLines.length" class="lp-wait">等待第一拍（预热拍不计）…</li>
        </ul>
      </article>

      <!-- ② 订单 ------------------------------------------------------------ -->
      <article class="lp-col">
        <header class="lp-col-h">
          <IconGlyph name="flow" tone="muted" />
          <b>订单</b>
          <span class="lp-conn" :class="conn.orders">{{ connText(conn.orders) }}</span>
        </header>
        <p class="lp-kpi lp-kpi-flow">
          <span v-for="s in FLOW_STATES" :key="s.k">{{ orderFlowStateLabel(s.k) }} <b>{{ countOf(s.k) }}</b></span>
        </p>
        <ul ref="oList" class="lp-list" aria-label="订单流滚动预览">
          <li v-for="l in oLines" :key="l.id" :class="l.tone">
            <time>{{ l.t }}</time><span>{{ l.text }}</span>
          </li>
          <li v-if="!oLines.length" class="lp-wait">等待流转（有单子动才会出现行）…</li>
        </ul>
      </article>

      <!-- ③ 日志 ------------------------------------------------------------ -->
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
        <ul ref="eList" class="lp-list" aria-label="日志流滚动预览">
          <li v-for="l in eLines" :key="l.id" :class="l.tone">
            <time>{{ l.t }}</time>
            <span><i class="lp-kind" :style="{ color: l.color }">{{ l.kind }}</i>{{ l.text }}</span>
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

/** 每列保留的行数。到顶从头部丢 —— 预览只负责「最近发生了什么」。 */
const CAP = 40
/** 单拍最多入列的事件/流转条数：压测一拍可能几十条，全画会把 DOM 顶穿。 */
const PER_FRAME_CAP = 6

const FLOW_STATES = [
  { k: 'waitPay' },
  { k: 'waitShip' },
  { k: 'waitReceive' },
  { k: 'completed' },
  { k: 'cancelled' },
]

const mLines = ref([])
const oLines = ref([])
const eLines = ref([])
const mKpi = ref({ qps: null, p99: null, hitRate: null, errorRate: null })
const oCounts = ref({})
const eFresh = ref(0)
const eTotal = ref(0)
const conn = ref({ metrics: 'wait', orders: 'wait', events: 'wait' })

const mList = ref(null)
const oList = ref(null)
const eList = ref(null)

let uid = 0
let handles = []

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
const fmt = (v, d) => (v === null || v === undefined ? '—' : Number(v).toFixed(d))
const pct = (v) => (v === null || v === undefined ? '—' : (Number(v) * 100).toFixed(1) + '%')
const countOf = (k) => num((oCounts.value || {})[k]) || 0
const hms = () => new Date().toLocaleTimeString('zh-CN', { hour12: false })

/** 入列 + 截断。tone 只影响左侧行色条（warn/danger），不参与筛选。 */
function push(listRef, text, tone = '', extra = {}) {
  const line = { id: ++uid, t: hms(), text, tone, ...extra }
  const arr = listRef.value
  arr.push(line)
  if (arr.length > CAP) arr.splice(0, arr.length - CAP)
  return line
}

/** 一帧最多滚一次（三个列表一起滚到底）。rAF 合帧，避免每条事件触发一次布局。 */
let scrollRaf = 0
function scheduleScroll() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0
    nextTick(() => {
      for (const el of [mList.value, oList.value, eList.value]) {
        if (el) el.scrollTop = el.scrollHeight
      }
    })
  })
}

// ── 三条流的收帧 ────────────────────────────────────────────────────────────
function onMetrics(f) {
  const req = f.requests || {}
  const cache = f.cache || {}
  const interval = num(f.intervalMs) || 0
  const count = num(req.count) || 0
  const qps = num(req.qps) !== null ? num(req.qps) : (interval > 0 ? count / (interval / 1000) : null)
  const level = (f.health && f.health.level) || 'ok'
  mKpi.value = {
    qps,
    p99: req.p99Ms === null || req.p99Ms === undefined ? null : num(req.p99Ms),
    hitRate: cache.hitRate === null || cache.hitRate === undefined ? null : num(cache.hitRate),
    errorRate: req.errorRate === null || req.errorRate === undefined ? null : num(req.errorRate),
  }
  // 预热拍（intervalMs=0）的差分量全是 0，画成一行「0 QPS」就是编数据 —— 与指标页同一条过滤。
  if (interval <= 0) return
  push(
    mLines,
    `${fmt(qps, 1)} QPS · P99 ${fmt(mKpi.value.p99, 0)}ms · 命中 ${pct(mKpi.value.hitRate)} · 错误 ${pct(mKpi.value.errorRate)}`,
    level === 'ok' ? '' : 'warn',
  )
  scheduleScroll()
}

function onOrders(f) {
  oCounts.value = f.counts || {}
  const moves = Array.isArray(f.moves) ? f.moves : []
  for (const m of moves.slice(-PER_FRAME_CAP)) {
    const from = orderFlowStateLabel(m.from)
    const to = orderFlowStateLabel(m.to)
    const result = m.result ? ` · ${m.result}` : ''
    push(oLines, `${m.orderId == null ? '—' : m.orderId} ${from} → ${to}${result}`)
  }
  if (moves.length) scheduleScroll()
}

function onEvents(f) {
  const ev = Array.isArray(f.events) ? f.events : []
  eFresh.value = ev.length
  if (!ev.length) return
  for (const e of ev.slice(-PER_FRAME_CAP)) {
    eTotal.value += 1
    const kind = kindLabel(e.kind)
    const sev = String(e.severity || '').toUpperCase()
    push(eLines, String(e.title || ''), sev === 'ERROR' || sev === 'WARN' ? 'warn' : '', {
      kind,
      color: KIND_COLOR[e.kind] || undefined,
    })
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
.lp-list li.warn {
  border-left-color: var(--warning);
  color: var(--ink);
}
.lp-list time {
  flex: none;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.lp-list span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.lp-kind {
  font-style: normal;
  margin-right: 6px;
}
.lp-wait {
  color: var(--ink-3);
  font-family: var(--font);
}
</style>
