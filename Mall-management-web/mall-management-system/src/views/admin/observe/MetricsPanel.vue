<template>
  <section class="a-mp">
    <!-- ── ① 状态带：健康度由后端算（`MetricsSnapshotService.health()`），本页不重算 ───── -->
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
      <DemoModeToggle />
    </header>

    <p v-if="lost" class="a-flash" :class="lost.err ? 'bad' : 'warn'">
      {{ lostText }}
      <button type="button" class="a-btn" @click="connect">立即重连</button>
    </p>

    <ul v-if="healthReasons.length" class="a-reasons" :class="'a-reasons--' + healthLevel">
      <li v-for="(r, i) in healthReasons" :key="i">{{ r }}</li>
    </ul>

    <!-- ── ② 主体：左时间栈 / 右实体栏 ───────────────────────────────────────────
         ⚠️ 滚动容器（`.mp-body`）与两栏网格（`.mp-grid`）**必须分开**：若让同一个元素既是
         `overflow-y:auto` 又是 `display:grid`，固定高度下右栏会比左栏高、溢出部分不计入
         `scrollHeight` ⇒ 库存区底部会被裁掉且滚不到（3a.4 实测）。网格高度交给内容撑。 -->
    <div class="mp-body">
      <div class="mp-grid">
      <div class="mp-stack">
        <!-- QPS 堆叠 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>QPS · 堆叠</h3>
            <span class="mp-chart-n">成功 / 4xx / 5xx · 每秒</span>
            <span class="mp-legend" aria-hidden="true">
              <i class="lg-ok"></i>成功
              <i class="lg-4xx"></i>4xx
              <i class="lg-5xx"></i>5xx
            </span>
          </div>
          <BarChart
            :categories="xLabels" :series="qpsChartSeries"
            height="200px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            value-formatter="{value}" :tooltip-formatter="qpsTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="还没收到第一拍" @retry="connect"
          />
        </section>

        <!-- 命中率：命中/未命中柱 + 命中率线 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>缓存命中率</h3>
            <span class="mp-chart-n">柱 = 命中/未命中计数（左轴）· 虚线 = 命中率（右轴 0–100）</span>
            <span class="mp-legend" aria-hidden="true">
              <i class="lg-hit"></i>命中
              <i class="lg-miss"></i>未命中
              <i class="lg-rate"></i>命中率
            </span>
          </div>
          <LineChart
            :categories="xLabels" :series="hitChartSeries"
            height="170px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            boundary-gap y-min="0" y2-min="0" y2-max="100"
            y-formatter="{value}" y2-formatter="{value}%"
            :tooltip-formatter="hitTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="还没收到第一拍" @retry="connect"
          />
        </section>

        <!-- 平均延迟 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>平均延迟</h3>
            <span class="mp-chart-n">逐拍均值 · 无样本的拍断开（不是 0）· 纵轴从 0 起</span>
          </div>
          <LineChart
            :categories="xLabels" :series="meanChartSeries"
            height="160px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            boundary-gap y-min="0" y-formatter="{value} ms"
            :tooltip-formatter="meanTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="还没收到第一拍" @retry="connect"
          />
        </section>

        <!-- 锁等待 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>锁等待</h3>
            <span class="mp-chart-n">每把锁一条 · 无等待的拍断开（不是 0）</span>
            <span v-if="lockNames.length" class="mp-legend" aria-hidden="true">
              <template v-for="(n, i) in lockNames" :key="n">
                <i :style="{ background: 'var(--c' + ((i % 10) + 1) + ')' }"></i><span :title="n">{{ lockLabel(n) }}</span>
              </template>
            </span>
          </div>
          <LineChart
            :categories="xLabels" :series="waitSeriesWithMarks"
            height="160px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            boundary-gap y-min="0" y-formatter="{value} ms"
            :tooltip-formatter="waitTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="窗口内没有任何锁活动" @retry="connect"
          />
        </section>

        <!-- 锁竞争热力图 -->
        <section class="mp-chart mp-chart--last">
          <div class="mp-chart-h">
            <h3>锁竞争热力图</h3>
            <span class="mp-chart-n">行 = 锁名 · 列 = 最近 {{ WINDOW }} 拍 · 越深 = 本拍超时越多</span>
          </div>
          <HeatmapChart
            :x-labels="xLabels" :y-labels="lockAxisLabels" :data="heatData" value-name="超时数"
            height="190px" :legend-text="['超时多', '超时少']" :x-label-interval="heatInterval"
            :grid-left="64" :grid-right="56" :tooltip-formatter="heatTooltip" :animation="false"
            :loading="first" :error="errMsg"
            empty-text="窗口内没有任何锁活动" @retry="connect"
          />
          <p class="mp-axis-note">
            ⇡ 以上五张图共用同一根 x 轴：前四张不画刻度，只在最底这张画一次。
          </p>
        </section>
      </div>

      <!-- ══ 右栏：实体域 ═════════════════════════════════════════════════════ -->
      <aside class="mp-rail">
        <!-- 本拍指标 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">本拍指标 <span>每秒 1 拍 · 缺值显示 —</span></h3>
          <div class="mp-kpis">
            <div class="mp-kpi">
              <span class="mp-kpi-k">QPS</span>
              <span class="mp-kpi-v"><b>{{ first ? '—' : fmt1(shown.qps) }}</b><em>次/秒</em></span>
              <span class="mp-kpi-d">本拍请求 {{ fmtInt(latestRow ? latestRow.count : 0) }} 次</span>
              <span class="mp-spark" aria-hidden="true">
                <i v-for="(b, i) in qpsSpark" :key="i" :class="{ off: b.off }" :style="{ height: b.h + '%' }"></i>
              </span>
            </div>
            <div class="mp-kpi">
              <span class="mp-kpi-k">平均延迟</span>
              <span class="mp-kpi-v"><b>{{ meanNow === null ? '—' : fmtInt(Math.round(shown.mean)) }}</b><em>ms</em></span>
              <span class="mp-kpi-d">P99（衰减窗口，非本拍）{{ p99Now === null ? '—' : fmtInt(Math.round(p99Now)) }} ms</span>
              <span class="mp-kpi-nospark">无序列</span>
            </div>
            <div class="mp-kpi">
              <span class="mp-kpi-k">缓存命中率</span>
              <span class="mp-kpi-v"><b>{{ hitRateNow === null ? '—' : fmt1(shown.hitRate * 100) }}</b><em>%</em></span>
              <span class="mp-kpi-d">命中 {{ fmtInt(latestRow ? latestRow.hit : 0) }} · 未命中 {{ fmtInt(latestRow ? latestRow.miss : 0) }}</span>
              <span class="mp-spark" aria-hidden="true">
                <i v-for="(b, i) in hitSpark" :key="i" :class="{ off: b.off }" :style="{ height: b.h + '%' }"></i>
              </span>
            </div>
            <div class="mp-kpi">
              <span class="mp-kpi-k">锁等待次数</span>
              <span class="mp-kpi-v"><b>{{ first ? '—' : fmtInt(Math.round(shown.lockWait)) }}</b><em>次</em></span>
              <span class="mp-kpi-d">全部锁名本拍 timeouts 之和</span>
              <span class="mp-kpi-nospark">无序列</span>
            </div>
          </div>
        </section>

        <!-- 设施 3 类 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">依赖状态 <span>常量 · 画点不画线</span></h3>
          <ul class="mp-facs">
            <li v-for="f in facilities" :key="f.key" class="mp-fac">
              <component :is="f.icon" :size="15" :style="{ color: f.color }" />
              <span class="mp-fac-k">{{ f.label }}</span>
              <i class="mp-fac-dot" :class="'is-' + f.tone"></i>
              <span class="mp-fac-s">{{ f.text }}</span>
            </li>
          </ul>
        </section>

        <!-- 严重度分布 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">严重度分布 <span>最近 {{ rows.length || 0 }} 拍 health.level</span></h3>
          <div class="mp-sev-bar" aria-hidden="true">
            <i
              v-for="s in SEV_META" :key="s.key"
              :style="{ flexGrow: sevCounts[s.key] || 0, background: s.color }"
            ></i>
          </div>
          <ul class="mp-sevs">
            <li v-for="s in SEV_META" :key="s.key">
              <component :is="s.icon" :size="14" :style="{ color: s.color }" />
              <span>{{ s.label }}</span>
              <b>{{ sevCounts[s.key] }}</b>
              <em>{{ sevPct(s.key) }}</em>
            </li>
          </ul>
        </section>

        <!-- 防护命中 8 类 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">防护命中 · 8 类 <span>本拍增量</span></h3>
          <ul v-if="protRows.length" class="mp-prots">
            <li v-for="p in protRows" :key="p.tag" class="mp-prot">
              <span class="mp-prot-k">
                <component :is="PROT_ICON[p.tag] || ShieldCheck" :size="14" :style="{ color: protColor(p.tag) }" />
                <span>{{ p.label }}</span>
              </span>
              <span class="mp-prot-track">
                <i :style="{ width: protPct(p.value), background: protColor(p.tag) }"></i>
              </span>
              <b>{{ fmtInt(p.value) }}</b>
            </li>
          </ul>
          <p v-else class="mp-empty">还没收到第一拍。</p>
        </section>

        <!-- 库存水位 -->
        <section class="mp-rail-sec mp-stock">
          <h3 class="mp-rail-h">
            <Droplets :size="16" :style="{ color: 'var(--c4)' }" />
            库存水位 <span>{{ stockRows.length }} 条 · 最低库存前 20</span>
          </h3>

          <p v-if="!stockAvailable" class="mp-empty">
            {{ first ? '还没收到第一拍。' : '本拍库存水位读取失败（后端已标注为不可用，流仍在推）—— 不是「库存为 0」。' }}
          </p>
          <template v-else>
            <div class="mp-stock-tools">
              <input
                v-model.trim="stockQuery" type="search" class="mp-stock-search"
                placeholder="搜 SKU 名 / 编码 / 规格" aria-label="搜索库存"
              />
              <div class="mp-stock-filters">
                <button
                  v-for="f in STOCK_FILTERS" :key="f.key" type="button"
                  class="mp-chip" :class="{ on: stockFilter === f.key }"
                  @click="stockFilter = f.key"
                >{{ f.label }}</button>
              </div>
              <p class="mp-stock-note">仅在最低库存 20 条内搜索 / 筛选</p>
            </div>

            <p v-if="!stockRows.length" class="mp-empty">库存查询没有返回任何 SKU。</p>
            <p v-else-if="!stockFiltered.length" class="mp-empty">没有匹配的 SKU。</p>
            <template v-else>
              <p v-if="stockAllUnknown" class="mp-stock-warn">
                真库当前没有 BaselineStock，水位百分比不可算；先按演示基线步骤打基线才会出现深浅。
              </p>
              <ul class="mp-stock-list">
                <li v-for="r in stockFiltered" :key="r.sku" class="mp-stock-row">
                  <div class="mp-stock-top">
                    <span class="mp-stock-name" :title="r.goodName || r.sku">{{ r.goodName || '（无商品名）' }}</span>
                    <span class="mp-stock-n">
                      <b>{{ r.stock === null || r.stock === undefined ? '—' : fmtInt(Math.round(displayOf(r))) }}</b>
                      <span>/ {{ r.baseline === null || r.baseline === undefined ? '—' : fmtInt(r.baseline) }}</span>
                    </span>
                  </div>
                  <div class="mp-stock-sub">
                    <code>{{ r.sku }}</code>
                    <span v-if="r.axisName"> · {{ r.axisName }}</span>
                    <em class="mp-stock-tag" :class="'is-' + stockStatus(r)">{{ stockStatusLabel(r) }}</em>
                  </div>
                  <div class="mp-stock-track" :title="stockTitle(r)">
                    <div class="mp-stock-fill" :class="barTone(r)" :style="{ width: barWidth(r) }"></div>
                  </div>
                </li>
              </ul>
            </template>
          </template>
        </section>
      </aside>
      </div>
    </div>
  </section>
</template>

<!--
  管理端「指标面板」页（C3 块 7 · 3a.4 重做为 S1「时间栈 + 实体栏」）。

  ⚠️ 数据源仍是一条 SSE 流（`POST /admin/observe/metrics/stream`，1 秒 1 帧），
     本页自己维护 60 拍滚动窗口（`rows`）；后端每帧只给本拍增量。
  ⚠️ 五张时间图共用 `xLabels` / `grid-left=64` / `grid-right=56`，前四张 `show-x-axis=false`，
     只由最底的锁竞争热力图画一次刻度。柱与折线都 `boundaryGap=true`，点/柱/热力格中心
     才会落在同一条竖线上。
  ⚠️ 延迟图用 `requests.meanMs`（逐拍真值）；`p95Ms/p99Ms/maxMs` 是 Micrometer 衰减窗口
     分位，不是本拍，只能放 KPI 的次要小字，不能进时间轴（TD-77）。
  ⚠️ 库存水位是全页唯一次真查询（后端 `stock()`）；真库当前 `baseline=null` 时画灰斜纹
     「未设基线」，不能当 0% 或充足。演示源里才有 baseline。
-->

<script setup>
/* eslint-disable no-undef */ /* 理由见 `BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  AlertCircle, AlertOctagon, AlertTriangle, Bell, Clipboard, Database,
  Droplets, Info, Lock, Package, Server, Shield, ShieldCheck, ShoppingCart,
} from '@lucide/vue'
import { metricsHistory, openMetricsStream } from '../../../api/observe'
import { demo, openDemoStream } from '../../../api/demo-stream'
import { KIND_COLOR } from '../../../config/labels/observe-labels'
import DemoModeToggle from '../../../components/observe/DemoModeToggle.vue'
import LineChart from '../../../components/charts/LineChart.vue'
import BarChart from '../../../components/charts/BarChart.vue'
import HeatmapChart from '../../../components/charts/HeatmapChart.vue'

/** 滚动窗口长度（拍）。热力图列数就是它。 */
const WINDOW = 60
/** KPI 数字滚动时长（与旧页一致的 0.3 秒）。 */
const TWEEN_MS = 300
/** 库存水位数字/条宽的推进时长：1 秒一帧，取 0.6 秒留出停顿。 */
const STOCK_TWEEN_MS = 600

/* ── 八类防护的图标与环色（与日志页 9 环同一套 `KIND_COLOR`）──────────────── */
const PROT_ICON = {
  cacheHit: Database,
  lockTimeout: Lock,
  orderRejected: ShoppingCart,
  stockInsufficient: Package,
  auditFailed: Clipboard,
  notifyFailed: Bell,
  sensitiveHit: AlertTriangle,
  identityRejected: Shield,
}
const PROT_RING = {
  cacheHit: 'cache',
  lockTimeout: 'lock',
  orderRejected: 'order',
  stockInsufficient: 'stock',
  auditFailed: 'audit',
  notifyFailed: 'notify',
  sensitiveHit: 'sensitive',
  identityRejected: 'identity',
}
function protColor(tag) {
  const ring = PROT_RING[tag]
  return (ring && KIND_COLOR[ring]) || 'var(--ink-3)'
}

const SEV_META = [
  { key: 'ok', label: '健康', color: 'var(--success)', icon: Info },
  { key: 'warn', label: '警告', color: 'var(--warning)', icon: AlertCircle },
  { key: 'error', label: '错误', color: 'var(--danger)', icon: AlertOctagon },
]

const STOCK_FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'ok', label: '充足' },
  { key: 'low', label: '偏低' },
  { key: 'out', label: '缺货' },
  { key: 'unknown', label: '未测' },
]

/* ── 基础状态 ─────────────────────────────────────────────────────────────── */
const phase = ref('connecting')       // connecting | live | stopped
const errMsg = ref('')
const lost = ref(null)
const latest = ref(null)              // 最近一帧的非数字派生量（health / protections / stock）
const rows = ref([])                  // 滚动窗口：每拍一条
const stockQuery = ref('')
const stockFilter = ref('all')

let stream = null
let rafId = 0
let stockRaf = 0

const reduceMotion = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
  : false

/* ── 小工具 ───────────────────────────────────────────────────────────────── */
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
function esc(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/* ── 状态带 ───────────────────────────────────────────────────────────────── */
const first = computed(() => latest.value === null && !errMsg.value)
const seq = computed(() => (latest.value && latest.value.seq) || 0)
const lastTickAt = computed(() => (latest.value ? hms(latest.value.ts) : '—'))
const healthLevel = computed(() => (latest.value && latest.value.health && latest.value.health.level) || 'ok')
const healthLabel = computed(() => (latest.value && latest.value.health && latest.value.health.label) || '连接中')
const healthReasons = computed(() => (latest.value && latest.value.health && latest.value.health.reasons) || [])
const errRate = computed(() => (latest.value && latest.value.errorRate !== undefined ? latest.value.errorRate : null))
const clientErrRate = computed(() => (latest.value && latest.value.clientErrorRate !== undefined ? latest.value.clientErrorRate : null))
const phaseText = computed(() => (phase.value === 'live' ? '已连接' : (phase.value === 'stopped' ? '已断开' : '连接中')))
const lostText = computed(() => {
  const s = lost.value
  if (!s) return ''
  const head = s.err
    ? `指标流中断：${(s.err && s.err.message) || '连接中断'}`
    : '指标流已断开（服务端正常收尾）'
  return `${head} —— 上面的数字停在断开前最后一拍，${s.nextRetryInMs / 1000} 秒后自动重连（第 ${s.attempt} 次）`
})

/* ── 最近一拍与 KPI ───────────────────────────────────────────────────────── */
const latestRow = computed(() => (rows.value.length ? rows.value[rows.value.length - 1] : null))
const meanNow = computed(() => (latestRow.value ? latestRow.value.meanMs : null))
const hitRateNow = computed(() => (latestRow.value ? latestRow.value.hitRate : null))
const p99Now = computed(() => (latestRow.value ? latestRow.value.p99 : null))

const shown = reactive({ qps: 0, mean: 0, hitRate: 0, lockWait: 0 })

/* ── 共用的 x 轴与各图序列 ────────────────────────────────────────────────── */
const xLabels = computed(() => rows.value.map((r) => hms(r.ts)))

/** `count` / `errors4xx` / `errors5xx` 是每拍计数；堆叠 QPS 统一换算成「每秒」。 */
const qps4xxSeries = computed(() => rows.value.map((r) => (r.err4xx / r.intervalSec)))
const qps5xxSeries = computed(() => rows.value.map((r) => (r.err5xx / r.intervalSec)))
const qpsOkSeries = computed(() => rows.value.map((r, i) => Math.max(
  0, r.qps - qps4xxSeries.value[i] - qps5xxSeries.value[i]
)))
const qpsTotalSeries = computed(() => rows.value.map((r) => r.qps))
const qpsMarkPoints = computed(() => markPointsOf(qpsTotalSeries.value, fmt1, ' 次/秒'))
const qpsChartSeries = computed(() => [
  { name: '成功', data: qpsOkSeries.value, colorIndex: 3, stack: 'qps' },
  { name: '4xx', data: qps4xxSeries.value, colorIndex: 4, stack: 'qps' },
  { name: '5xx', data: qps5xxSeries.value, colorIndex: 7, stack: 'qps', markPoints: qpsMarkPoints.value },
])

const hitBarSeries = computed(() => rows.value.map((r) => r.hit))
const missBarSeries = computed(() => rows.value.map((r) => r.miss))
/** 命中率转成 0–100；`null` 原样保留 —— ECharts 画成断点，不能连成 0。 */
const hitRateSeries = computed(() => rows.value.map((r) => (r.hitRate === null ? null : r.hitRate * 100)))
const hitMarkPoints = computed(() => markPointsOf(hitRateSeries.value, fmt1, '%'))
const hitChartSeries = computed(() => [
  { name: '命中', data: hitBarSeries.value, type: 'bar', colorIndex: 3, barMaxWidth: 5, stack: 'cache' },
  { name: '未命中', data: missBarSeries.value, type: 'bar', colorIndex: 4, barMaxWidth: 5, stack: 'cache' },
  {
    name: '命中率', data: hitRateSeries.value, type: 'line', axis: 'right',
    colorIndex: 1, dashed: true, markPoints: hitMarkPoints.value,
  },
])

const meanSeries = computed(() => rows.value.map((r) => r.meanMs))
const meanMarkPoints = computed(() => markPointsOf(meanSeries.value, (v) => fmtInt(Math.round(v)), ' ms'))
const meanChartSeries = computed(() => [
  { name: '平均延迟', data: meanSeries.value, area: true, colorIndex: 1, markPoints: meanMarkPoints.value },
])

/** 行 = 窗口内出现过的锁名；某锁 60 拍没动静就从行里退出。 */
const lockNames = computed(() => {
  const seen = []
  for (const r of rows.value) {
    for (const k of Object.keys(r.locks)) {
      if (!seen.includes(k)) seen.push(k)
    }
  }
  return seen
})
/**
 * 锁名 → 中文短名。**只用于显示**：`lockNames` 仍是后端原键，`heatData` / tooltip 取数一律
 * 走原键（改数据键会把图打空）。
 * ⚠️ 为什么需要它：五图共享 `grid-left=64`，y 轴文字可用宽度只有 56px，`order-add` 会被
 *    `overflow:'truncate'` 切成 `order-a...`（2026-10-17 用户实测）。中文三字 ≈36px，放得下；
 *    原键仍留在图例的 `title` 与 tooltip 里，`代码 ↔ 界面` 的对应关系不丢。
 * ⚠️ 表里没有的键原样显示（后端将来加锁时不会变成空白）。
 * ⚠️ `stock-deduct` / `coupon-claim` 只出现在「模拟日常」的假指标帧里（`api/demo-stream.js`
 *    的 metrics 段），后端真锁枚举里没有它们；一并给中文短名，免得演示模式下 y 轴又出现截断。
 */
const LOCK_LABELS = {
  'order-add': '下单锁',
  'order-pay': '支付锁',
  'order-receive': '收货锁',
  'order-delivery': '发货锁',
  'friend-add': '好友锁',
  'stock-deduct': '扣库存锁',
  'coupon-claim': '领券锁',
}
const lockLabel = (name) => LOCK_LABELS[name] || name
/** 热力图 y 轴用中文短名；顺序与 `lockNames` 一一对应（`heatData` 的 y 下标靠这个顺序）。 */
const lockAxisLabels = computed(() => lockNames.value.map(lockLabel))
const waitChartSeries = computed(() => lockNames.value.map((name, i) => ({
  name,
  data: rows.value.map((r) => (r.locks[name] ? r.locks[name].waitMeanMs : null)),
  colorIndex: (i % 10) + 1,
})))
const waitMarkPoints = computed(() => {
  if (!waitChartSeries.value.length) return []
  return globalMarkPoints(
    waitChartSeries.value.filter((s) => s.data.some((v) => v !== null && v !== undefined)),
    (v) => fmt1(v), ' ms',
  )
})
/** 峰值/谷值标注只挂到第一条序列上（标注的坐标可以落在别条线的值上）。 */
const waitSeriesWithMarks = computed(() => waitChartSeries.value.map((s, i) => (
  i === 0 ? { ...s, markPoints: waitMarkPoints.value } : s
)))

const heatData = computed(() => {
  const names = lockNames.value
  const out = []
  rows.value.forEach((r, x) => {
    names.forEach((n, y) => {
      const l = r.locks[n]
      if (l !== undefined) out.push([x, y, l.timeouts])
    })
  })
  return out
})
const heatInterval = computed(() => Math.max(0, Math.ceil(Math.max(1, rows.value.length) / 10) - 1))

/* ── 峰值 / 谷值标注 ──────────────────────────────────────────────────────── */
function markPointsOf(values, fmt, unit) {
  const pts = []
  for (let i = 0; i < values.length; i++) {
    const v = values[i]
    if (v !== null && v !== undefined && Number.isFinite(Number(v))) pts.push({ i, v: Number(v) })
  }
  if (!pts.length) return []
  let max = pts[0]
  let min = pts[0]
  for (const p of pts) {
    if (p.v > max.v) max = p
    if (p.v < min.v) min = p
  }
  if (max.v === min.v) {
    return [{ x: xLabels.value[max.i], y: max.v, text: `持平 ${fmt(max.v)}${unit}`, position: 'top' }]
  }
  return [
    { x: xLabels.value[max.i], y: max.v, text: `峰 ${fmt(max.v)}${unit}`, position: 'top' },
    // 谷值落在 0 时 `bottom` 会掉出绘图区（QPS 第一拍就是 0）⇒ 改标在 0 线上方。
    { x: xLabels.value[min.i], y: min.v, text: `谷 ${fmt(min.v)}${unit}`, position: min.v === 0 ? 'top' : 'bottom' },
  ]
}

/** 多序列（锁等待）里找全局峰/谷；文字带锁名，避免读者以为那是第一条线的点。 */
function globalMarkPoints(series, fmt, unit) {
  const pts = []
  for (const s of series) {
    s.data.forEach((v, i) => {
      if (v !== null && v !== undefined && Number.isFinite(Number(v))) {
        pts.push({ i, v: Number(v), name: s.name })
      }
    })
  }
  if (!pts.length) return []
  let max = pts[0]
  let min = pts[0]
  for (const p of pts) {
    if (p.v > max.v) max = p
    if (p.v < min.v) min = p
  }
  if (max.v === min.v) {
    return [{ x: xLabels.value[max.i], y: max.v, text: `持平 ${fmt(max.v)}${unit}`, position: 'top' }]
  }
  return [
    { x: xLabels.value[max.i], y: max.v, text: `峰 ${max.name} ${fmt(max.v)}${unit}`, position: 'top' },
    { x: xLabels.value[min.i], y: min.v, text: `谷 ${min.name} ${fmt(min.v)}${unit}`, position: min.v === 0 ? 'top' : 'bottom' },
  ]
}

/* ── tooltip ──────────────────────────────────────────────────────────────── */
function qpsTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  return `<b>${xLabels.value[i]}</b><br/>成功 ${fmt1(qpsOkSeries.value[i])} 次/秒`
    + `<br/>4xx ${fmt1(qps4xxSeries.value[i])} 次/秒 · 5xx ${fmt1(qps5xxSeries.value[i])} 次/秒`
    + `<br/>本拍请求 ${fmtInt(r.count)} 次`
}
function hitTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  return `<b>${xLabels.value[i]}</b><br/>命中 ${fmtInt(r.hit)} · 未命中 ${fmtInt(r.miss)}`
    + `<br/>命中率 ${r.hitRate === null ? '—' : fmt1(r.hitRate * 100) + '%'}`
}
function meanTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  return `<b>${xLabels.value[i]}</b><br/>平均延迟 ${r.meanMs === null ? '本拍无延迟样本' : fmt1(r.meanMs) + ' ms'}`
}
function waitTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  const parts = []
  for (const [name, l] of Object.entries(r.locks)) {
    if (l.waitMeanMs === null || l.waitMeanMs === undefined) continue
    parts.push(`${esc(lockLabel(name))}（${esc(name)}） ${fmt1(l.waitMeanMs)} ms（超时 ${fmtInt(l.timeouts)} 次）`)
  }
  return `<b>${xLabels.value[i]}</b><br/>${parts.length ? parts.join('<br/>') : '本拍无锁等待样本'}`
}
function heatTooltip(p) {
  const v = p && (Array.isArray(p.value) ? p.value : p.data)
  if (!v) return ''
  const x = Number(v[0])
  const y = Number(v[1])
  const val = Number(v[2])
  const name = lockNames.value[y] || ''
  const r = rows.value[x]
  const l = r && r.locks[name]
  let s = `<b>${esc(lockLabel(name))}</b>（${esc(name)}） · 第 ${x + 1} 拍`
    + `${xLabels.value[x] ? '（' + xLabels.value[x] + '）' : ''} · 超时 ${fmtInt(val)} 次`
  if (l) {
    s += `<br/>取锁 ${fmtInt(l.acquires)} · 成功 ${fmtInt(l.acquired)}`
      + ` · 降级 ${fmtInt(l.degraded)} · 错误 ${fmtInt(l.errors)}`
  }
  return s
}

/* ── 微条（K1 QPS / K3 命中率）───────────────────────────────────────────── */
function sparkBars(values, bins = 20) {
  const arr = values.slice(-bins)
  const nums = arr.filter((v) => v !== null && v !== undefined && Number.isFinite(Number(v)))
  const max = Math.max(1, ...nums.map(Number))
  return arr.map((v) => {
    if (v === null || v === undefined || !Number.isFinite(Number(v))) return { h: 0, off: true }
    return { h: Math.max(4, Math.round((Number(v) / max) * 100)), off: false }
  })
}
const qpsSpark = computed(() => sparkBars(qpsTotalSeries.value))
const hitSpark = computed(() => sparkBars(hitRateSeries.value))

/* ── 设施 3 类 ────────────────────────────────────────────────────────────── */
const facilities = computed(() => {
  const l2 = latest.value ? latest.value.l2Up : null
  const l2Deg = latest.value ? latest.value.l2Degraded : null
  const lock = latest.value ? latest.value.lockUp : null
  const db = latest.value ? !!(latest.value.stock && latest.value.stock.available) : null

  let l2s
  if (l2 === null || l2 === undefined) l2s = { text: '未上报', tone: 'unknown' }
  else if (!l2) l2s = { text: '不可用', tone: 'bad' }
  else if (l2Deg) l2s = { text: '降级', tone: 'warn' }
  else l2s = { text: '正常', tone: 'ok' }

  let locks
  if (lock === null || lock === undefined) locks = { text: '未上报', tone: 'unknown' }
  else if (lock) locks = { text: '正常', tone: 'ok' }
  else locks = { text: '不可用（fail-open）', tone: 'bad' }

  let dbs
  if (db === null) dbs = { text: '未上报', tone: 'unknown' }
  else if (db) dbs = { text: '正常', tone: 'ok' }
  else dbs = { text: '查询失败', tone: 'bad' }

  return [
    { key: 'l2', label: 'L2 缓存', icon: Database, color: 'var(--c6)', ...l2s },
    { key: 'lock', label: '锁客户端', icon: Lock, color: 'var(--c2)', ...locks },
    { key: 'db', label: '商品库', icon: Server, color: 'var(--c4)', ...dbs },
  ]
})

/* ── 严重度分布（health.level 的 60 拍计数）──────────────────────────────── */
const sevCounts = computed(() => {
  const c = { ok: 0, warn: 0, error: 0 }
  for (const r of rows.value) {
    if (c[r.health] !== undefined) c[r.health] += 1
  }
  return c
})
const sevTotal = computed(() => Math.max(1, rows.value.length))
function sevPct(key) {
  if (!rows.value.length) return '—'
  return `${Math.round((sevCounts.value[key] / sevTotal.value) * 100)}%`
}

/* ── 防护 8 类 ────────────────────────────────────────────────────────────── */
const protRows = computed(() => (latest.value && latest.value.protections) || [])
const protMax = computed(() => Math.max(1, ...protRows.value.map((p) => num(p.value))))
function protPct(v) {
  return Math.max(2, Math.min(100, (num(v) / protMax.value) * 100)) + '%'
}

/* ── 库存水位 ─────────────────────────────────────────────────────────────── */
const stockAvailable = computed(() => !!(latest.value && latest.value.stock && latest.value.stock.available))
const stockRows = computed(() => ((latest.value && latest.value.stock && latest.value.stock.rows) || []))
const stockAllUnknown = computed(() => stockRows.value.length > 0
  && stockRows.value.every((r) => r.baseline === null || r.baseline === undefined))
const stockFiltered = computed(() => {
  const q = stockQuery.value.trim().toLowerCase()
  return stockRows.value.filter((r) => {
    if (stockFilter.value !== 'all' && stockStatus(r) !== stockFilter.value) return false
    if (!q) return true
    return [r.goodName, r.sku, r.axisName]
      .some((x) => String(x || '').toLowerCase().includes(q))
  })
})

/** 显示值：由 rAF 从上一帧推进到目标 stock；无动画偏好时直接落位。 */
const displayStock = reactive({})
function animateStock(list) {
  const from = {}
  const to = {}
  for (const r of list) {
    const key = String(r.sku)
    const targetValue = r.stock === null || r.stock === undefined ? 0 : Number(r.stock)
    if (!Number.isFinite(targetValue)) continue
    const cur = displayStock[key]
    from[key] = Number.isFinite(Number(cur)) ? Number(cur) : 0
    to[key] = targetValue
    if (cur === undefined) displayStock[key] = 0
  }
  if (reduceMotion) {
    for (const k of Object.keys(to)) displayStock[k] = to[k]
    return
  }
  if (stockRaf) cancelAnimationFrame(stockRaf)
  const t0 = performance.now()
  const step = (now) => {
    const p = Math.min(1, (now - t0) / STOCK_TWEEN_MS)
    const e = 1 - Math.pow(1 - p, 3)          // easeOutCubic：起步快、收尾稳
    for (const k of Object.keys(to)) {
      displayStock[k] = from[k] + (to[k] - from[k]) * e
    }
    if (p < 1) {
      stockRaf = requestAnimationFrame(step)
    } else {
      stockRaf = 0
      for (const k of Object.keys(to)) displayStock[k] = to[k]
    }
  }
  stockRaf = requestAnimationFrame(step)
}
function displayOf(r) {
  const v = displayStock[String(r.sku)]
  return Number.isFinite(Number(v)) ? Number(v) : num(r.stock)
}
function stockStatus(r) {
  // 库存为 0 是**观测到的事实**，优先于「没有基线算不出比例」：真库现在就有
  // `stock:0, baseline:null` 的 SKU，不能因为未设基线就把它归进「未测」。
  if (Number(r.stock) <= 0) return 'out'
  if (r.ratio === null || r.ratio === undefined) return 'unknown'
  if (Number(r.ratio) < 0.5) return 'low'
  return 'ok'
}
function stockStatusLabel(r) {
  return { ok: '充足', low: '偏低', out: '缺货', unknown: '未测' }[stockStatus(r)]
}
function barWidth(r) {
  // 0 库存是确定的「缺货」，哪怕没有基线也不该画成满条灰斜纹。
  if (Number(r.stock) <= 0) return '2%'
  if (r.baseline === null || r.baseline === undefined || Number(r.baseline) <= 0) return '100%'
  const pct = Math.max(0, Math.min(100, (displayOf(r) / Number(r.baseline)) * 100))
  return Math.max(2, pct) + '%'
}
function barTone(r) {
  const s = stockStatus(r)
  if (s === 'unknown') return 'is-unknown'
  if (s === 'out' || s === 'low') return 'is-low'
  return 'is-ok'
}
function stockTitle(r) {
  if (r.baseline === null || r.baseline === undefined) {
    return '没有 BaselineStock，算不出水位；先把演示基线打上'
  }
  return `水位 ${(Number(r.ratio) * 100).toFixed(1)}% · 库存 ${fmtInt(r.stock)} / 基线 ${fmtInt(r.baseline)}`
}

/* ── KPI 数字滚动 ─────────────────────────────────────────────────────────── */
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
    const e = 1 - Math.pow(1 - p, 3)
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

/* ── 收帧 ─────────────────────────────────────────────────────────────────── */
/**
 * 一帧（流帧或历史帧，形状逐字相同）→ 窗口里的一行。
 * ⚠️ 抽出来是因为**首屏历史**（`loadHistory`）与 SSE 必须走同一条解析：
 *    两份实现迟早在某个派生量上漂（那正是本仓反复吃的亏）。
 */
function toRow(f) {
  const req = f.requests || {}
  const cache = f.cache || {}
  const intervalMs = num(f.intervalMs) || 1000
  const intervalSec = Math.max(0.001, intervalMs / 1000)
  const locks = Array.isArray(f.locks) ? f.locks : []

  const lockMap = {}
  let lockTimeouts = 0
  for (const l of locks) {
    const name = String(l.lock)
    const row = {
      acquires: num(l.acquires),
      acquired: num(l.acquired),
      timeouts: num(l.timeouts),
      degraded: num(l.degraded),
      errors: num(l.errors),
      waitMeanMs: l.waitMeanMs === null || l.waitMeanMs === undefined ? null : num(l.waitMeanMs),
    }
    lockMap[name] = row
    lockTimeouts += row.timeouts
  }

  const count = num(req.count)
  const qps = req.qps === null || req.qps === undefined ? count / intervalSec : num(req.qps)
  return {
    ts: num(f.ts) || Date.now(),
    seq: num(f.seq),
    intervalSec,
    count,
    qps,
    err4xx: num(req.errors4xx),
    err5xx: num(req.errors5xx),
    meanMs: req.meanMs === null || req.meanMs === undefined ? null : num(req.meanMs),
    p99: req.p99Ms === null || req.p99Ms === undefined ? null : num(req.p99Ms),
    hit: num(cache.hit),
    miss: num(cache.miss),
    hitRate: cache.hitRate === null || cache.hitRate === undefined ? null : num(cache.hitRate),
    health: (f.health && f.health.level) || 'ok',
    locks: lockMap,
    lockTimeouts,
  }
}

/** 「最近一帧」那组非时间序列的派生量（KPI 卡 / 健康度 / 库存 / 防护）。 */
function applyLatest(f) {
  const req = f.requests || {}
  const cache = f.cache || {}
  const gauge = f.gauges || {}
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
    l2Degraded: cache.l2Degraded === undefined ? null : cache.l2Degraded,
  }
}

function onFrame(f) {
  const row = toRow(f)
  // ⚠️ 每条连接的**首帧是差分预热拍**（`intervalMs=0`、全部差分量 0，见
  //    `MetricsSnapshotService.Session#frame`）：把它画进窗口会在首屏历史之后插一根
  //    假的 0 尖峰。跳过这一行，下一拍就是真增量（上一拍读数已被这一帧记住）。
  if (num(f.intervalMs) > 0) {
    rows.value.push(row)
    if (rows.value.length > WINDOW) rows.value.shift()
  }

  applyLatest(f)
  animateStock((f.stock && f.stock.rows) || [])
  if (num(f.intervalMs) > 0) {
    tweenTo({
      qps: row.qps,
      mean: row.meanMs === null ? 0 : row.meanMs,
      hitRate: row.hitRate === null ? 0 : row.hitRate,
      lockWait: row.lockTimeouts,
    })
  }
}

/**
 * 首屏历史（块 7 · 2026-10-17 新增）：拿服务端最近 60 拍已算好的帧，铺到实时帧**前面**。
 *
 * ⚠️ 只在「正常」源加载；切到模拟日常时历史必须清掉（否则真流历史会混进演示画面）。
 * ⚠️ 只填**比现有第一行更早**的那一段：历史请求与 SSE 首帧是并发发出的，回来的顺序不定；
 *    用 `ts` 截断就不会把同一拍画两次。请求失败**不报错**：历史是增强项，实时流照常。
 */
let historyToken = 0
async function loadHistory() {
  const token = ++historyToken
  try {
    const data = await metricsHistory(WINDOW)
    // 期间用户切了数据源 / 又发了一次历史请求 ⇒ 这次结果作废。
    if (token !== historyToken || demo.on) return
    const list = (data && data.list) || []
    // 预热拍（intervalMs=0）不是真实的一拍 —— 与 `onFrame` 同一条过滤。
    const hist = list.filter((f) => num(f.intervalMs) > 0).map(toRow)
    if (!hist.length) return
    const cutoff = rows.value.length ? rows.value[0].ts : Infinity
    const older = hist.filter((r) => r.ts < cutoff)
    if (older.length) rows.value = older.concat(rows.value).slice(-WINDOW)
    // 实时帧还没到时，用历史最后一拍兜住 KPI / 健康 / 库存（已有实时值则不动）。
    if (!latest.value) applyLatest(list[list.length - 1])
  } catch (e) {
    // 历史读失败不改变页面状态：SSE 照常推，页面上只是少了首屏的那 60 拍。
    // ⚠️ 但要在 console 留一条 —— 否则「为什么首屏还是 0」只能靠猜。
    console.warn('[metrics] 首屏历史读取失败（实时流不受影响）：', e && (e.message || e))
  }
}

/* ── 连接 ─────────────────────────────────────────────────────────────────── */
function connect() {
  closeStream()
  errMsg.value = ''
  lost.value = null
  phase.value = 'connecting'
  const handlers = {
    onOpen() {
      phase.value = 'live'
      lost.value = null
      errMsg.value = ''
    },
    onFrame,
    onLost(s) {
      phase.value = 'stopped'
      lost.value = s
      errMsg.value = s.err ? ((s.err && s.err.message) || '连接中断') : ''
    },
  }
  // 正常源：先把服务端最近 60 拍铺上（首屏不再是 0），再接着收 SSE 增量。
  // ⚠️ 历史与流**并发**发出：谁先回来都不会画重（见 `loadHistory` 里的 `ts` 截断）。
  if (!demo.on) loadHistory()
  stream = demo.on ? openDemoStream('metrics', handlers) : openMetricsStream(handlers)
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
  if (stockRaf) {
    cancelAnimationFrame(stockRaf)
    stockRaf = 0
  }
}

onMounted(connect)
// 切数据源 = 换一套世界：窗口必须清空（否则真流历史会混进演示画面），然后再连。
// ⚠️ 路由切换**不会**走到这里 —— 本组件由 `AdminShell.vue` 的 KeepAlive 缓存，
//    切到日志页再回来时流与窗口都原样还在（用户 2026-10-17 报的「切页从 0 开始」）。
watch(() => demo.on, () => {
  historyToken += 1
  rows.value = []
  latest.value = null
  connect()
})
onBeforeUnmount(closeStream)
</script>

<style scoped>
/* ── 页高：**一屏**（与日志页同一条 `calc(100vh - 顶部高度)`）────────────────
   ⚠️ 2026-10-17 修「双滚动条 + 左右两栏底部不齐」：原来的 `140vh` 让页面本身高于视口
      ⇒ ① 浏览器右侧出现全局长条；② `.mp-body` 里再滚一次；③ 右栏比左栏高出来的部分
      把整页撑长，首屏看不全。现在外层只负责占满一屏并裁掉溢出，滚动**下放给两栏各自**。 */
.a-mp {
  max-width: 1280px;
  height: calc(100vh - 124px);
  min-height: 360px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ── 状态带（与其它可观测页同源，只把字号收进 12/15/19 三档）───────────────── */
.a-status {
  flex: 0 0 auto;
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
  margin-bottom: var(--s3);
}
.a-stat {
  display: flex;
  flex-direction: column;
  min-width: 96px;
}
.a-stat b {
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  line-height: 1.15;
  color: var(--ink);
}
.a-stat span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-hv {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
}
.a-hv--ok { color: var(--success-deep); }
.a-hv--warn { color: var(--warning-deep); }
.a-hv--error { color: var(--danger); }
.a-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
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
.a-reasons {
  flex: 0 0 auto;
  list-style: none;
  font-size: var(--fs-sm);
  line-height: 1.6;
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  margin-bottom: var(--s3);
}
.a-reasons--ok { color: var(--success-deep); background: var(--success-ground); }
.a-reasons--warn { color: var(--warning-deep); background: var(--warning-ground); }
.a-reasons--error { color: var(--danger); background: var(--danger-ground); }
.a-flash {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--s3);
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s3);
}
.a-flash.bad { color: var(--danger); background: var(--danger-ground); }
/* 服务端正常收尾（`onEnd(null)`）不是故障：给琥珀色提示，别用红条冒充连接错误。 */
.a-flash.warn { color: var(--warning-deep); background: var(--warning-ground); }
.a-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s3);
  cursor: pointer;
  white-space: nowrap;
}
.a-btn:hover { color: var(--ink); background: var(--surface-3); }

/* ── 主体两栏 ─────────────────────────────────────────────────────────────── */
/* 外层 `.mp-body` **不滚**：它只把剩余一屏高度交给网格；两栏各自 `overflow-y:auto`。
   ⚠️ 这样左右两栏的**可视区底边等价**（同一行网格、同一高度），左栏短、右栏长也不会
      把页面撑长；滚动条只出现在各自栏内，不会再有「全局长条 + 内部条」两条。 */
.mp-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.mp-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: var(--s4);
  align-items: stretch;
  /* 用 flex 撑满（不靠 `height:100%`）：父级的“确定高度”来自 flex 分配，百分比高度
     在部分外壳形态下解析不出来（本仓 `ael-height-does-not-resolve` 那条实测的教训）。 */
  flex: 1 1 auto;
  min-height: 0;
}
/* 两栏独立滚动 + 等高。`min-height:0` 是让 flex/grid 子项真能收缩的那一刀；
   没有它内容会把栏撑高，`overflow` 永远轮不到生效。 */
.mp-stack,
.mp-rail {
  min-height: 0;
  overflow-y: auto;
  padding-right: 2px;
}
.mp-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  min-width: 0;
}
.mp-chart {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s2) var(--s3) 0;
  min-width: 0;
}
.mp-chart--last { padding-bottom: var(--s2); }
.mp-chart-h {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  flex-wrap: wrap;
  min-height: 24px;
  padding: 0 var(--s1);
}
.mp-chart-h h3 {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.mp-chart-n {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-legend {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
.mp-legend i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-left: var(--s1);
}
.mp-legend i:first-child { margin-left: 0; }
.lg-ok { background: var(--c3); }
.lg-4xx { background: var(--c4); }
.lg-5xx { background: var(--c7); }
.lg-hit { background: var(--c3); }
.lg-miss { background: var(--c4); }
.lg-rate { background: var(--c1); }
.mp-axis-note {
  margin: var(--s1) var(--s1) 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* ── 右栏 ─────────────────────────────────────────────────────────────────── */
.mp-rail {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}
.mp-rail-sec {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s3);
  min-width: 0;
}
.mp-rail-h {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin: 0 0 var(--s2);
  padding-bottom: var(--s1);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.mp-rail-h span {
  margin-left: auto;
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
  text-align: right;
}

/* 4 张读数卡：K1/K3 挂微条，K2/K4 无序列 */
.mp-kpis {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.mp-kpi {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 64px;
  grid-template-rows: auto auto auto;
  column-gap: var(--s2);
  align-items: center;
  padding: var(--s1) 0;
  border-bottom: 1px solid var(--line);
}
.mp-kpi:last-child { border-bottom: none; }
.mp-kpi-k {
  grid-column: 1;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-kpi-v {
  grid-column: 1;
  display: flex;
  align-items: baseline;
  gap: var(--s1);
}
.mp-kpi-v b {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.mp-kpi-v em {
  font-style: normal;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-kpi-d {
  grid-column: 1;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mp-spark {
  grid-column: 2;
  grid-row: 1 / span 3;
  height: 30px;
  display: flex;
  align-items: flex-end;
  gap: 1px;
}
.mp-spark i {
  flex: 1 1 0;
  min-width: 0;
  background: var(--c1);
  border-radius: 1px 1px 0 0;
}
.mp-spark i.off { background: var(--line-2); opacity: 0.5; }
.mp-kpi:nth-child(3) .mp-spark i { background: var(--c3); }
.mp-kpi-nospark {
  grid-column: 2;
  grid-row: 1 / span 3;
  align-self: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  text-align: center;
}

/* 设施 3 类 */
.mp-facs, .mp-sevs, .mp-prots {
  list-style: none;
  display: flex;
  flex-direction: column;
}
.mp-facs { gap: var(--s2); }
.mp-fac {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
}
.mp-fac-k { color: var(--ink-2); }
.mp-fac-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-left: auto;
  background: var(--ink-3);
}
.mp-fac-dot.is-ok { background: var(--success); }
.mp-fac-dot.is-warn { background: var(--warning); }
.mp-fac-dot.is-bad { background: var(--danger); }
.mp-fac-dot.is-unknown { background: var(--ink-3); }
.mp-fac-s {
  min-width: 92px;
  text-align: right;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* 严重度分布 */
.mp-sev-bar {
  display: flex;
  height: 8px;
  overflow: hidden;
  border-radius: var(--r1);
  background: var(--surface-2);
  margin-bottom: var(--s2);
}
.mp-sev-bar i { min-width: 0; }
.mp-sevs { gap: var(--s1); }
.mp-sevs li {
  display: grid;
  grid-template-columns: 16px 1fr auto 42px;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.mp-sevs b {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}
.mp-sevs em {
  font-style: normal;
  text-align: right;
  color: var(--ink-3);
}

/* 防护 8 类 */
.mp-prots { gap: 5px; }
.mp-prot {
  display: grid;
  grid-template-columns: 118px minmax(0, 1fr) 32px;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
}
.mp-prot-k {
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-width: 0;
  color: var(--ink-2);
}
.mp-prot-k span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mp-prot-track {
  height: 6px;
  background: var(--surface-2);
  border-radius: var(--r1);
  overflow: hidden;
}
.mp-prot-track i {
  display: block;
  height: 100%;
  border-radius: var(--r1);
}
.mp-prot b {
  text-align: right;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}

/* 库存水位 */
.mp-stock-tools {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.mp-stock-search {
  width: 100%;
  box-sizing: border-box;
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s2);
}
.mp-stock-search::placeholder { color: var(--ink-3); }
.mp-stock-search:focus {
  outline: none;
  border-color: var(--brand);
}
.mp-stock-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s1);
}
.mp-chip {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 8px;
  cursor: pointer;
}
.mp-chip.on {
  color: var(--ink);
  border-color: var(--brand);
  background: var(--brand-tint);
}
.mp-stock-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-stock-warn {
  font-size: var(--fs-xs);
  line-height: 1.5;
  color: var(--warning-deep);
  background: var(--warning-ground);
  border-radius: var(--r1);
  padding: var(--s1) var(--s2);
  margin-bottom: var(--s2);
}
/* ⚠️ 故意**不再**是 220px 的内嵌滚动区：右栏现在整体滚，内层再滚就是「栏内双滚动条」。
   库存列表按内容自然增高，滚的是 `.mp-rail` 这一根。 */
.mp-stock-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.mp-stock-row {
  flex: 0 0 auto;
  padding-bottom: var(--s1);
  border-bottom: 1px solid var(--line);
}
.mp-stock-top {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
}
.mp-stock-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
  color: var(--ink);
}
.mp-stock-n {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.mp-stock-n b {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--ink);
}
.mp-stock-sub {
  display: flex;
  align-items: center;
  gap: var(--s1);
  min-width: 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-stock-sub code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mp-stock-tag {
  margin-left: auto;
  flex: 0 0 auto;
  font-style: normal;
  font-size: var(--fs-xs);
  padding: 0 5px;
  border-radius: var(--r1);
}
.mp-stock-tag.is-ok { color: var(--success); background: var(--success-ground); }
.mp-stock-tag.is-low { color: var(--warning); background: var(--warning-ground); }
.mp-stock-tag.is-out { color: var(--danger); background: var(--danger-ground); }
.mp-stock-tag.is-unknown { color: var(--ink-3); background: var(--surface-2); }
.mp-stock-track {
  height: 7px;
  margin-top: var(--s1);
  background: var(--surface-2);
  border-radius: var(--r1);
  overflow: hidden;
}
.mp-stock-fill {
  height: 100%;
  border-radius: var(--r1);
}
.mp-stock-fill.is-ok {
  background: linear-gradient(90deg, var(--success), var(--success-deep));
}
.mp-stock-fill.is-low {
  background: linear-gradient(90deg, var(--warning), var(--danger));
}
.mp-stock-fill.is-unknown {
  background: repeating-linear-gradient(
    45deg,
    var(--surface-3),
    var(--surface-3) 4px,
    var(--surface-2) 4px,
    var(--surface-2) 8px
  );
}
.mp-empty {
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
  padding: var(--s2) 0;
}

/* 滚动条：7px（≤8px）+ `--line-2` 深色滑块，口径与日志页 `.ev-list` 逐条一致。
   ⚠️ 只写在**真正滚的两个栏**上；`.mp-stock-list` 已不是滚动容器。
   ⚠️ **绝不能**在祖先（`.mp-body` / 本页任何元素）上写 `scrollbar-width` / `scrollbar-color`：
      Chrome ≥121 只要元素上的这两个属性非 `auto`，整段 `::-webkit-scrollbar` 就被**整块忽略**；
      `scrollbar-color` 还会**继承**到 `.mp-stack` / `.mp-rail` ⇒ 实测这两栏退回默认 15px
      （2026-10-17 探针：`offsetWidth-clientWidth = 15`）。本页原来就有一条第 `.mp-body,
      .mp-stock-list` 的 `scrollbar-width: thin`（6px 规则同时被它作废）—— 已删。 */
.mp-stack::-webkit-scrollbar,
.mp-rail::-webkit-scrollbar {
  width: 7px;
  height: 7px;
}
.mp-stack::-webkit-scrollbar-thumb,
.mp-rail::-webkit-scrollbar-thumb {
  background: var(--line-2);
  border-radius: 4px;
}
.mp-stack::-webkit-scrollbar-thumb:hover,
.mp-rail::-webkit-scrollbar-thumb:hover {
  background: var(--ink-3);
}
.mp-stack::-webkit-scrollbar-track,
.mp-rail::-webkit-scrollbar-track {
  background: transparent;
}

@media (max-width: 1100px) {
  .a-mp { height: auto; min-height: 0; overflow: visible; }
  .mp-body { overflow: visible; }
  .mp-grid { grid-template-columns: minmax(0, 1fr); height: auto; }
  .mp-stack,
  .mp-rail { overflow: visible; }
  .mp-stock-list { height: 260px; overflow-y: auto; }
}
</style>

<script>
// ⚠️ 这个名字是 `AdminShell.vue` 里 `<keep-alive include="MetricsPanel,EventLog">` 的锚点。
//    为什么不用文件名自动推断：本仓的 Vue 是 **3.2.13**，按文件名推 `__name` 是 3.2.34 才有的。
//    ⇒ 名字写在这里，**别删**；改名必须两处一起改（否则 KeepAlive 静默不生效，
//    表现就是用户报过的「切到日志页再回来，指标又从 0 开始」）。
export default { name: 'MetricsPanel' }
</script>
