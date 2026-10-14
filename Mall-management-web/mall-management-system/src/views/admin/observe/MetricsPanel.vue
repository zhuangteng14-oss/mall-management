<template>
  <section class="a-mp">
    <!-- ── ① 健康度大字 + 状态条（与 `StatsPage.vue` / `AuditPage.vue` 同款，不另立一套）──
         ⚠️ 「健康度」是后端算的（`MetricsSnapshotService.health()`），本页**不重算**：
         阈值是演示口径（5xx ≥5% 红 / P99 ≥1000ms 红 / 锁或 L2 降级黄），重算一遍迟早与后端走岔。 -->
    <header class="a-status">
      <div class="a-stat">
        <b class="a-hv" :class="'a-hv--' + healthLevel">
          <span class="a-dot" aria-hidden="true"></span>{{ healthLabel }}
        </b>
        <span>健康度（后端算）</span>
      </div>
      <div class="a-stat">
        <b>{{ errRate === null ? '—' : (errRate * 100).toFixed(1) + '%' }}</b>
        <span>5xx 占比（本拍）</span>
      </div>
      <div class="a-stat">
        <b>{{ clientErrRate === null ? '—' : (clientErrRate * 100).toFixed(1) + '%' }}</b>
        <span>4xx 占比（本拍）</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">{{ phaseText }}</span>
        <span>第 <b>{{ seq }}</b> 拍 · 窗口 {{ WINDOW }} 秒</span>
        <span class="a-tz-off">{{ lastTickAt }}</span>
      </div>
    </header>

    <p v-if="errMsg" class="a-flash bad">
      指标流中断：{{ errMsg }}
      <button type="button" class="a-btn" @click="connect">重新连接</button>
    </p>

    <!-- 健康度的「判据」（后端 `health.reasons`）。⚠️ 绿色时这一串**常常不是空的** ——
         后端在健康态填的是「L1 命中率 94.2%」这类**正面**理由（见 `health()` 的 else 分支），
         故标题叫「判据」不叫「告警」：叫告警会在全绿时显示一行看起来像故障的话。
         ⚠️ 但它**也可能是空的**：那条正面理由只在 `cache.hitRate != null` 时才补（`health()` :383），
         而没流量时 `hitRate` 就是 `null` ⇒ 真起服务看到的第一帧正是 `reasons: []`
         （2026-10-14 对 dev 后端实测）。故这里是 `v-if="length"`，不是无条件渲染。 -->
    <ul v-if="healthReasons.length" class="a-reasons" :class="'a-reasons--' + healthLevel">
      <li v-for="(r, i) in healthReasons" :key="i">{{ r }}</li>
    </ul>

    <!-- ══ ② 4 个 KPI 卡 ══════════════════════════════════════════════════════
         ⚠️ 环比口径 = **本拍 vs 本窗口（近 60 秒）均值**，不是「较昨日」（本页没有日粒度基线）。
         均值把当前这一拍也算进去了，所以它是「高于/低于近期水位」而不是严格的前后对比 ——
         这一条必须写在卡上（`delta-label`），否则读者会按「较上一拍」理解，
         而 1 秒粒度的前后差全是噪声。分母为 0（如锁等待恒为 0）时**不画箭头**、退回 `hint`。 -->
    <h2 class="a-h2">本拍指标 <span class="a-h2n">每秒 1 拍 · 缺值显示 — 而不是 0</span></h2>
    <div class="a-kpis">
      <KpiCard
        label="QPS" glyph="metrics" unit="次/秒"
        :value="first ? '—' : fmt1(shown.qps)"
        :delta="first ? null : delta.qps" delta-label="较 60 秒均值" :delta-text="dtext.qps"
        :hint="'本拍请求 ' + fmtInt(tickCount) + ' 次'" :loading="first"
      />
      <KpiCard
        label="P99 延迟" glyph="metrics" unit="ms" inverse
        :value="p99 === null ? '—' : fmtInt(Math.round(shown.p99))"
        :delta="first ? null : delta.p99" delta-label="较 60 秒均值" :delta-text="dtext.p99"
        :hint="'全站分位数的上界近似（各序列取最大）'" :loading="first"
      />
      <KpiCard
        label="缓存命中率" glyph="metrics" unit="%"
        :value="hitRate === null ? '—' : (shown.hitRate * 100).toFixed(1)"
        :delta="first ? null : delta.hitRate" delta-label="较 60 秒均值" :delta-text="dtext.hitRate"
        :hint="'L1 命中 / (命中 + 未命中)'" :loading="first"
      />
      <KpiCard
        label="锁等待次数" glyph="metrics" unit="次" inverse
        :value="first ? '—' : fmtInt(Math.round(shown.lockWait))"
        :delta="first ? null : delta.lockWait" delta-label="较 60 秒均值" :delta-text="dtext.lockWait"
        :hint="'全部锁名本拍 timeouts 之和'" :loading="first"
      />
    </div>

    <!-- ══ ③ QPS 面积图（滚动 60 秒）══════════════════════════════════════════ -->
    <h2 class="a-h2">
      QPS · 滚动 {{ WINDOW }} 秒
      <span class="a-h2n">每秒 1 点 · 新点从右进、旧点从左出 · 前 {{ WINDOW }} 拍之外不留</span>
    </h2>
    <div class="a-card">
      <LineChart
        :categories="xLabels" :series="[{ name: 'QPS', data: qpsSeries, area: true }]"
        height="220px" :show-legend="false" y-formatter="{value}"
        :loading="first" :error="errMsg" empty-text="还没收到第一拍" @retry="connect"
      />
    </div>

    <!-- ══ ④ 缓存命中率折线（滚动 60 秒）═══════════════════════════════════════ -->
    <h2 class="a-h2">
      缓存命中率 · 滚动 {{ WINDOW }} 秒
      <span class="a-h2n">本拍无请求的拍点留空（不断线连成 0）</span>
    </h2>
    <div class="a-card">
      <LineChart
        :categories="xLabels" :series="[{ name: '命中率', data: hitSeries }]"
        height="220px" :show-legend="false" y-formatter="{value}%" :y-min="0" :y-max="100"
        :loading="first" :error="errMsg" empty-text="还没收到第一拍" @retry="connect"
      />
    </div>

    <!-- ══ ⑤ 锁竞争热力图（锁名 × 时间）═══════════════════════════════════════
         ⚠️ **`HeatmapChart.vue` 的首次调用点**（《块 7 参考》§8.3：该组件此前零调用点）。
         口径：行 = 锁名，列 = 拍点，颜色深浅 = 该锁**本拍超时数**（`locks[].timeouts`）。 -->
    <h2 class="a-h2">
      锁竞争热力图 <span class="a-h2n">行 = 锁名 · 列 = 最近 {{ WINDOW }} 拍 · 越深 = 本拍超时越多</span>
    </h2>
    <div class="a-card">
      <HeatmapChart
        :x-labels="xLabels" :y-labels="lockNames" :data="heatData" value-name="超时数"
        height="260px" :loading="first" :error="errMsg"
        empty-text="窗口内没有任何锁活动" @retry="connect"
      />
    </div>

    <!-- ══ ⑥ 防护命中柱状图（8 类）═════════════════════════════════════════════ -->
    <h2 class="a-h2">
      防护命中 · 8 类 <span class="a-h2n">本拍增量（不是累计）· 含「缓存命中」这种正面计数</span>
    </h2>
    <div class="a-card">
      <BarChart
        :categories="protLabels" :series="[{ name: '本拍命中', data: protValues }]"
        height="240px" :show-legend="false" :max-bar-width="26"
        :loading="first" :error="errMsg" empty-text="还没收到第一拍" @retry="connect"
      />
    </div>

    <!-- ══ ⑦ 库存水位（多 SKU 进度条）═════════════════════════════════════════
         ⚠️ 这一段是全页**唯一**一次真查询（后端 `stock()`），且它会**独立失败**：
         `available=false` 时该段不可用，但**流继续推**（见后端注释）⇒ 这里画成灰条 + 说明，
         不把整页判成错误。 -->
    <h2 class="a-h2">
      库存水位 <span class="a-h2n">Stock / BaselineStock · 只列有基线的前 {{ STOCK_MAX }} 个 SKU</span>
    </h2>
    <div class="a-card">
      <p v-if="!stockAvailable" class="a-empty">
        {{ first ? '还没收到第一拍。' : '本拍库存水位读取失败（后端已标注为不可用，流仍在推）—— 不是「库存为 0」。' }}
      </p>
      <p v-else-if="!stockRows.length" class="a-empty">库里有基线的 SKU 一个都没有（先把演示基线打上）。</p>
      <ul v-else class="a-bars">
        <li v-for="r in stockRows" :key="r.sku" class="a-bar">
          <div class="a-bar-k">
            <span class="a-bar-name">{{ r.goodName || '（无商品名）' }}</span>
            <span class="a-bar-sku">{{ r.sku }}<template v-if="r.axisName"> · {{ r.axisName }}</template></span>
          </div>
          <div class="a-bar-track">
            <div
              class="a-bar-fill"
              :class="barTone(r)"
              :style="{ width: barWidth(r) }"
              :title="barTitle(r)"
            ></div>
          </div>
          <div class="a-bar-n">
            <b>{{ r.stock === null ? '—' : fmtInt(r.stock) }}</b>
            <span>/ {{ r.baseline === null ? '—' : fmtInt(r.baseline) }}</span>
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>

<!--
  管理端「指标面板」页（C3 块 7 · commit 6，2026-10-14）。

  ⚠️ **数据源是一条 SSE 流**（`POST /admin/observe/metrics/stream`，1 秒 1 帧）——
     与 `StatsPage.vue`（一次性 JSON）**不是一类页面**：本页**没有「查询」按钮**，
     唯一的用户动作是「重新连接」。所以别照 `StatsPage` 的 `load()` 形态写。
     契约与帧字段见 `api/observe.js` 的 `openMetricsStream` 注释 + 后端
     `MetricsSnapshotService.frame()`。

  ⚠️ **页面卸载必须 `close()`**（`onBeforeUnmount` 里那一条）：SSE 的
     `StreamingResponseBody` 在服务端占**一条异步线程**，不断开就是一条常驻线程
     —— 页面切走了还留着 = 攒线程。`api/observe.js` 的 `openStream` 头注释记了这条代价。

  ⚠️ **本页自己维护一个 60 拍的滚动窗口**（`rows`）：后端每帧只给「本拍」的瞬时值，
     「QPS 面积图 / 命中率折线 / 热力图」三条时间轴都需要历史 ⇒ 由前端攒。
     攒的代价是**切页即丢**（刷新后重新攒 60 秒）—— 这是刻意的：本页是「实时看板」，
     不是「历史查询」（历史查询走 MySQL，见块 7 §三的读路径分工）。
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { openMetricsStream } from '../../../api/observe'
import KpiCard from '../../../components/ui/KpiCard.vue'
import LineChart from '../../../components/charts/LineChart.vue'
import BarChart from '../../../components/charts/BarChart.vue'
import HeatmapChart from '../../../components/charts/HeatmapChart.vue'

/** 滚动窗口长度（拍）。⚠️ 《块 7 参考》§2.2 写死 60 秒，别在这里改小 —— 热力图的行数是锁名、列数就是它。 */
const WINDOW = 60
/** 库存水位最多画多少行（与后端 `STOCK_LIMIT` 同量级；后端已经限过，这里只是兜底）。 */
const STOCK_MAX = 12
/** 数字滚动动画时长（《块 7 参考》§2.3 的口径 0.3 秒）。 */
const TWEEN_MS = 300

const phase = ref('connecting')       // connecting | live | stopped
const errMsg = ref('')
const latest = ref(null)              // 最近一帧的派生量（非数字那些：health / protections / stock）
const rows = ref([])                  // 滚动窗口：每拍一条
const target = reactive({ qps: 0, p99: 0, hitRate: 0, lockWait: 0 })
const shown = reactive({ qps: 0, p99: 0, hitRate: 0, lockWait: 0 })

let stream = null
let rafId = 0

/* ── 小工具（本页自备：三端显示口径各不相同，不抽公共模块 —— 同 `StatsPage.vue` 的口径）── */
const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')
const fmt1 = (v) => Number(v || 0).toFixed(1)
function hms(ts) {
  const d = new Date(Number(ts) || 0)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ── 从「最近一帧」直接读的字段（不做滚动，故不进 rows）──────────────────── */
const first = computed(() => latest.value === null && !errMsg.value)
const seq = computed(() => (latest.value && latest.value.seq) || 0)
const lastTickAt = computed(() => (latest.value ? hms(latest.value.ts) : '—'))
const healthLevel = computed(() => (latest.value && latest.value.health && latest.value.health.level) || 'ok')
const healthLabel = computed(() => (latest.value && latest.value.health && latest.value.health.label) || '连接中')
const healthReasons = computed(() => (latest.value && latest.value.health && latest.value.health.reasons) || [])
const errRate = computed(() => (latest.value && latest.value.errorRate !== undefined ? latest.value.errorRate : null))
const clientErrRate = computed(() => (latest.value && latest.value.clientErrorRate !== undefined ? latest.value.clientErrorRate : null))
const phaseText = computed(() => (phase.value === 'live' ? '已连接' : (phase.value === 'stopped' ? '已断开' : '连接中')))

const tickCount = computed(() => (rows.value.length ? rows.value[rows.value.length - 1].count : 0))
const p99 = computed(() => (rows.value.length ? rows.value[rows.value.length - 1].p99 : null))
const hitRate = computed(() => (rows.value.length ? rows.value[rows.value.length - 1].hitRate : null))

/* ── 三条时间轴共用的 x 轴（时间标签）──────────────────────────────────── */
const xLabels = computed(() => rows.value.map((r) => hms(r.ts)))
const qpsSeries = computed(() => rows.value.map((r) => r.qps))
/** ⚠️ 命中率**转成 0-100 的百分数**再给图（后端给的是 0-1 的小数，见 `ratio()`）；
 *  为 `null` 的拍点**原样传 null** —— ECharts 会把 null 画成断点，正是要的
 *  （连成 0 会读成「这一秒一个都没命中」，而事实是「这一秒没有请求」）。 */
const hitSeries = computed(() => rows.value.map((r) => (r.hitRate === null ? null : r.hitRate * 100)))

/* ── 热力图：行 = 锁名（窗口内出现过就算），列 = 拍点 ─────────────────────── */
/** ⚠️ 锁名**从窗口里现算**而不是累加到一个 ref 里：某个锁 60 拍没动静就该从行里退出，
 *  否则跑一场攻击演练之后，行会永久停在那一堆锁上、把常驻的两三个锁压扁。 */
const lockNames = computed(() => {
  const seen = []
  for (const r of rows.value) {
    for (const k of Object.keys(r.locks)) {
      if (!seen.includes(k)) seen.push(k)
    }
  }
  return seen
})
const heatData = computed(() => {
  const names = lockNames.value
  const out = []
  rows.value.forEach((r, x) => {
    names.forEach((n, y) => {
      const v = r.locks[n]
      if (v !== undefined) out.push([x, y, v])
    })
  })
  return out
})

/* ── 防护命中（8 类）：只画**最近一帧** ──────────────────────────────────── */
const protLabels = computed(() => ((latest.value && latest.value.protections) || []).map((p) => p.label))
const protValues = computed(() => ((latest.value && latest.value.protections) || []).map((p) => num(p.value)))

/* ── 库存水位 ────────────────────────────────────────────────────────────── */
const stockAvailable = computed(() => !!(latest.value && latest.value.stock && latest.value.stock.available))
const stockRows = computed(() => ((latest.value && latest.value.stock && latest.value.stock.rows) || []).slice(0, STOCK_MAX))
/** 条宽：`ratio` 为 null（无基线）时画满一格的**灰条**，表示「有这行但算不出水位」。 */
function barWidth(r) {
  if (r.ratio === null || r.ratio === undefined) return '100%'
  return Math.max(2, Math.min(100, Number(r.ratio) * 100)) + '%'
}
function barTone(r) {
  if (r.ratio === null || r.ratio === undefined) return 'is-unknown'
  if (r.ratio < 0.2) return 'is-low'
  if (r.ratio < 0.5) return 'is-mid'
  return 'is-ok'
}
function barTitle(r) {
  if (r.ratio === null || r.ratio === undefined) return '没有演示基线（BaselineStock），算不出水位'
  return `水位 ${(Number(r.ratio) * 100).toFixed(1)}%`
}

/* ── 环比：本拍 vs 本窗口均值（口径写在 KPI 卡的 `delta-label` 上）──────── */
function avgOf(pick) {
  const vals = rows.value.map(pick).filter((v) => v !== null && v !== undefined && Number.isFinite(Number(v)))
  if (!vals.length) return null
  return vals.reduce((a, b) => a + Number(b), 0) / vals.length
}
function deltaOf(pick, cur) {
  const a = avgOf(pick)
  const c = Number(cur)
  if (a === null || a === 0 || !Number.isFinite(c)) return null
  return ((c - a) / a) * 100
}
const delta = computed(() => ({
  qps: deltaOf((r) => r.qps, shown.qps),
  p99: p99.value === null ? null : deltaOf((r) => r.p99, shown.p99),
  hitRate: hitRate.value === null ? null : deltaOf((r) => r.hitRate, shown.hitRate),
  lockWait: deltaOf((r) => r.lockWait, shown.lockWait),
}))

/**
 * 环比的**显示文本**。
 * ⚠️ 必须显式交给 `KpiCard` 的 `deltaText`：不给的话它回落到 `Math.abs(delta) + '%'`，
 *    把浮点**原样上屏** —— 页面会出现 `▼13.793103448275861% 较 60 秒均值` 这种
 *    （2026-10-14 探针截图实测到，紧接着修的就是它）。`KpiCard` 只负责加箭头，
 *    **格式化是调用方的活**（与它 `value` 要「已格式化好的字符串」是同一条口径）。
 *    空串 = 回落到默认实现，安全。
 */
const dtext = computed(() => ({
  qps: dtx(delta.value.qps),
  p99: dtx(delta.value.p99),
  hitRate: dtx(delta.value.hitRate),
  lockWait: dtx(delta.value.lockWait),
}))
/** 一位小数 + `%`。⚠️ 不取绝对值以外的加工：正负由 `KpiCard` 的箭头表达，这里只给数。 */
function dtx(d) {
  const n = Number(d)
  return d === null || d === undefined || !Number.isFinite(n) ? '' : Math.abs(n).toFixed(1) + '%'
}

/* ── 数字滚动动画（0.3 秒）──────────────────────────────────────────────
   ⚠️ 用 rAF 自己插值而不是 CSS transition：`KpiCard` 的 `value` 是**格式化好的字符串**，
   CSS 认不出「1,234」→「1,256」中间的帧。⚠️ `prefers-reduced-motion` 时**直接落位**
   （同 `KpiCard` 里 `.kpi-skel` 的处置口径）。 */
const reduceMotion = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
  : false

function tweenTo(next) {
  const keys = Object.keys(next)
  if (reduceMotion) {
    keys.forEach((k) => { shown[k] = next[k] })
    return
  }
  const from = {}
  keys.forEach((k) => { from[k] = shown[k] })
  const t0 = performance.now()
  if (rafId) cancelAnimationFrame(rafId)
  const step = (now) => {
    const p = Math.min(1, (now - t0) / TWEEN_MS)
    const e = 1 - Math.pow(1 - p, 3)          // easeOutCubic：起步快、收尾稳
    keys.forEach((k) => { shown[k] = from[k] + (next[k] - from[k]) * e })
    if (p < 1) {
      rafId = requestAnimationFrame(step)
    } else {
      rafId = 0
      keys.forEach((k) => { shown[k] = next[k] })
    }
  }
  rafId = requestAnimationFrame(step)
}

/* ── 收帧 ──────────────────────────────────────────────────────────────── */
function onFrame(f) {
  const req = f.requests || {}
  const cache = f.cache || {}
  const locks = f.locks || []
  const gauge = f.gauges || {}

  const lockMap = {}
  let lockWait = 0
  locks.forEach((r) => {
    const name = String(r.lock)
    const t = num(r.timeouts)
    lockMap[name] = t
    lockWait += t
  })

  rows.value.push({
    ts: num(f.ts) || Date.now(),
    count: num(req.count),
    qps: num(req.qps),
    p99: req.p99Ms === null || req.p99Ms === undefined ? null : num(req.p99Ms),
    hitRate: cache.hitRate === null || cache.hitRate === undefined ? null : num(cache.hitRate),
    lockWait,
    locks: lockMap,
  })
  if (rows.value.length > WINDOW) rows.value.shift()

  latest.value = {
    seq: num(f.seq),
    ts: num(f.ts) || Date.now(),
    health: f.health || null,
    protections: f.protections || [],
    stock: f.stock || null,
    errorRate: req.errorRate === undefined ? null : req.errorRate,
    clientErrorRate: req.clientErrorRate === undefined ? null : req.clientErrorRate,
    l2Up: gauge.l2Up === undefined ? null : gauge.l2Up,
    lockUp: gauge.lockUp === undefined ? null : gauge.lockUp,
  }

  target.qps = rows.value.length ? rows.value[rows.value.length - 1].qps : 0
  target.p99 = rows.value.length ? rows.value[rows.value.length - 1].p99 || 0 : 0
  target.hitRate = rows.value.length ? rows.value[rows.value.length - 1].hitRate || 0 : 0
  target.lockWait = lockWait
  tweenTo({ qps: target.qps, p99: target.p99, hitRate: target.hitRate, lockWait: target.lockWait })
}

function connect() {
  closeStream()
  errMsg.value = ''
  phase.value = 'connecting'
  stream = openMetricsStream({
    onOpen() { phase.value = 'live' },
    onFrame,
    onEnd(err) {
      // ⚠️ `onEnd(null)` = 服务端正常收尾（后端推满后关闭）—— 那不是故障，但**流断了**
      //    用户得知道，故也置 stopped，只是不给红条。
      phase.value = 'stopped'
      if (err) errMsg.value = (err && err.message) || '连接中断'
    },
  })
}

function closeStream() {
  if (stream) {
    stream.close()
    stream = null
  }
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
}

onMounted(connect)
onBeforeUnmount(closeStream)
</script>

<style scoped>
.a-mp {
  max-width: 1120px;
}

/* ── ① 状态条（与 `StatsPage.vue:400-450` 逐字同源，只多一个色点）────────────── */
.a-status {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
  margin-bottom: var(--s5);
}
.a-stat {
  display: flex;
  flex-direction: column;
  min-width: 96px;
}
.a-stat b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semi);
  line-height: 1.1;
  color: var(--ink);
}
.a-stat span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
/* 健康度大字：字号比其它三格小一档 —— 它是**字**（「健康」两个字），
   与旁边那三格的数字混排时同字号会显得头重（并且它会换行）。 */
.a-hv {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-lg);
}
.a-hv--ok { color: var(--success-deep); }
.a-hv--warn { color: var(--warning-deep); }
.a-hv--error { color: var(--danger); }
.a-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: currentColor;
}
.a-tz {
  margin-left: auto;
  align-self: center;
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-chip-utc {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--info);
  background: var(--info-ground);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.a-tz-off {
  font-family: var(--font-mono);
  color: var(--brand-ink);
}

/* ── 判据条 / 反馈条 ─────────────────────────────────────────────────────── */
.a-reasons {
  list-style: none;
  font-size: var(--fs-sm);
  line-height: 1.8;
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-reasons--ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.a-reasons--warn {
  color: var(--warning-deep);
  background: var(--warning-ground);
}
.a-reasons--error {
  color: var(--danger);
  background: var(--danger-ground);
}
.a-flash {
  display: flex;
  align-items: center;
  gap: var(--s3);
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}

/* ── 通用件（照 `StatsPage.vue`，逐字同源）────────────────────────────────── */
.a-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  cursor: pointer;
  white-space: nowrap;
}
.a-btn:hover {
  color: var(--ink);
  background: var(--surface-3);
}
.a-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin: var(--s6) 0 var(--s3);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.a-mp > .a-h2:first-of-type {
  margin-top: 0;
}
.a-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s3);
}
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4);
  margin-bottom: var(--s4);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s4) 0;
}

/* ── ⑦ 库存水位进度条 ────────────────────────────────────────────────────── */
.a-bars {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}
.a-bar {
  display: grid;
  grid-template-columns: 1fr 160px 96px;
  align-items: center;
  gap: var(--s3);
}
@media (max-width: 720px) {
  .a-bar {
    grid-template-columns: 1fr;
    gap: var(--s1);
  }
}
.a-bar-k {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.a-bar-name {
  font-size: var(--fs-sm);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-bar-sku {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-bar-track {
  height: 8px;
  background: var(--surface-2);
  border-radius: var(--r1);
  overflow: hidden;
}
.a-bar-fill {
  height: 100%;
  border-radius: var(--r1);
  transition: width 0.3s ease;
}
.a-bar-fill.is-ok { background: var(--success); }
.a-bar-fill.is-mid { background: var(--warning); }
.a-bar-fill.is-low { background: var(--danger); }
/* 无基线：**不是**红条也不是绿条 —— 画成灰底斜纹，读作「这行算不出水位」。 */
.a-bar-fill.is-unknown {
  background: repeating-linear-gradient(
    45deg,
    var(--surface-3),
    var(--surface-3) 4px,
    var(--surface-2) 4px,
    var(--surface-2) 8px
  );
}
@media (prefers-reduced-motion: reduce) {
  .a-bar-fill { transition: none; }
}
.a-bar-n {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-sm);
  color: var(--ink);
}
.a-bar-n span {
  color: var(--ink-3);
  font-size: var(--fs-xs);
}
</style>
