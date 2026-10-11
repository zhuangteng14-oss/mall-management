<template>
  <div class="ms-page">
    <header class="ms-head">
      <div>
        <h1 class="ms-h1">经营数据</h1>
        <p class="ms-sub">
          本店口径（<code>Scope = merchant</code>），业务号
          <b class="ms-mid">{{ mid || '（未取到）' }}</b>
          · 数据来自预聚合的 <code>stats_daily</code>，不直读订单表
        </p>
      </div>
      <div class="ms-range">
        <span class="ms-chip">区间</span>
        <b class="ms-rangespan">{{ rangeLabel }}</b>
      </div>
    </header>

    <p v-if="loadMsg" class="ms-flash">{{ loadMsg }}</p>

    <!-- ── 没预聚合过 ⇒ 说破它 ────────────────────────────────────────────────
         ⚠️ 与订单页 / 管理端看板同一条口径：`0`（真的没生意）与「还没算过」必须分得开。
         商家端**没有重建入口**（`/admin/stats/rebuild` 是管理员的）⇒ 这里只解释、不给按钮。 -->
    <p v-if="notBuilt" class="ms-gate">
      <b>本店还没被预聚合过。</b>
      <code>stats_daily</code> 里暂时没有本店的行 ⇒ 下面全是 0，但那不代表没有订单。
      全量重建是<b>管理员</b>的动作（<code>POST /admin/stats/rebuild</code>），本页不提供入口。
    </p>

    <!-- ── 区间选择 ──────────────────────────────────────────────────────────── -->
    <form class="ms-filters" @submit.prevent="load">
      <div class="ms-seg">
        <button
          v-for="r in RANGES"
          :key="r.key"
          type="button"
          class="ms-seg-b"
          :class="{ on: quick === r.key }"
          @click="pickQuick(r.key)"
        >{{ r.label }}</button>
      </div>
      <label class="ms-f">
        <span>起始（含）</span>
        <input v-model.trim="f.from" type="text" placeholder="YYYY-MM-DD" />
      </label>
      <label class="ms-f">
        <span>结束（含）</span>
        <input v-model.trim="f.to" type="text" placeholder="YYYY-MM-DD" />
      </label>
      <button type="submit" class="ms-btn ms-btn-on" :disabled="loading">查询</button>
    </form>

    <!-- ══ 区间合计 ═══════════════════════════════════════════════════════════ -->
    <h2 class="ms-h2">区间合计</h2>
    <div class="ms-kpis">
      <KpiCard
        label="成交额（GMV）" glyph="metrics" unit="元"
        :value="loadFailed ? '—' : fmtMoney(totals.Gmv)"
        :delta="deltaOf('Gmv')" delta-label="较上一等长区间" :loading="loading"
      />
      <KpiCard
        label="成单数" glyph="orders" unit="单"
        :value="loadFailed ? '—' : fmtInt(totals.Orders)"
        :delta="deltaOf('Orders')" delta-label="较上一等长区间" :loading="loading"
      />
      <KpiCard
        label="商品件数" glyph="goods" unit="件"
        :value="loadFailed ? '—' : fmtInt(totals.Items)"
        :delta="deltaOf('Items')" delta-label="较上一等长区间" :loading="loading"
      />
      <KpiCard
        label="买家数" glyph="users" unit="人日"
        :value="loadFailed ? '—' : fmtInt(totals.Buyers)"
        :delta="deltaOf('Buyers')" delta-label="较上一等长区间"
        hint="按日去重后相加，不是跨日人数" :loading="loading"
      />
      <KpiCard
        label="客单价" glyph="metrics" unit="元"
        :value="loadFailed ? '—' : fmtMoney(totals.aov)"
        :delta="deltaOf('aov')" delta-label="较上一等长区间" :loading="loading"
      />
    </div>

    <!-- ══ 成交额趋势 ═════════════════════════════════════════════════════════ -->
    <h2 class="ms-h2">成交额趋势 <span class="ms-h2n">单位：万元 · 缺日补 0</span></h2>
    <div class="ms-card">
      <LineChart
        :categories="seriesDates"
        :series="[{ name: '成交额', data: gmvSeries, area: true }]"
        :height="'280px'"
        y-formatter="{value} 万"
        :loading="loading"
        :error="loadFailed ? loadMsg : ''"
        @retry="load"
      />
    </div>

    <h2 class="ms-h2">成单数 <span class="ms-h2n">单位：单</span></h2>
    <div class="ms-card">
      <BarChart
        :categories="seriesDates"
        :series="[{ name: '成单数', data: ordersSeries }]"
        :height="'220px'"
        value-formatter="{value} 单"
        :show-legend="false"
        :loading="loading"
        :error="loadFailed ? loadMsg : ''"
        @retry="load"
      />
    </div>

    <!-- ══ 商品榜 ═════════════════════════════════════════════════════════════
         ⚠️ **上线即空是预期，不是故障**（用户裁定 §三：`order_item` 不回填存量单）。
         所以这里的空态文案**必须**解释成因，而不是那句通用的「暂无数据」——
         否则商家会以为是自己这店没卖出去东西。 -->
    <h2 class="ms-h2">商品榜 <span class="ms-h2n">实时聚合 order_item · 按销售额降序</span></h2>
    <div class="ms-card">
      <BarChart
        :categories="goodsNames"
        :series="[{ name: '销售额', data: goodsSales }]"
        :height="goodsHeight"
        horizontal
        :show-legend="false"
        :label-truncate="16"
        :max-bar-width="22"
        :loading="loading"
        :error="loadFailed ? loadMsg : ''"
        empty-text="暂无可统计的商品明细 —— 历史单没有明细行（order_item 不回填存量单），只统计新下的单。"
        @retry="load"
      />
    </div>

    <!-- ══ 逐日明细 ═══════════════════════════════════════════════════════════ -->
    <h2 class="ms-h2">逐日明细 <span class="ms-h2n">升序</span></h2>
    <p v-if="!days.length" class="ms-empty">
      {{ loading ? '加载中…' : (notBuilt ? '还没预聚合，等管理员跑一次全量重建。' : '该区间内本店没有已聚合的日。') }}
    </p>
    <table v-else class="ms-tbl">
      <thead>
        <tr>
          <th>日期</th>
          <th class="num">成单数</th>
          <th class="num">成交额（元）</th>
          <th class="num">件数</th>
          <th class="num">去重买家</th>
          <th class="num">客单价（元）</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in days" :key="r.StatDate">
          <td class="mono">{{ r.StatDate }}</td>
          <td class="num">{{ fmtInt(r.Orders) }}</td>
          <td class="num">{{ fmtMoney(r.Gmv) }}</td>
          <td class="num">{{ fmtInt(r.Items) }}</td>
          <td class="num">{{ fmtInt(r.Buyers) }}</td>
          <td class="num">{{ fmtMoney(r.aov) }}</td>
        </tr>
      </tbody>
      <tfoot>
        <tr>
          <td>合计</td>
          <td class="num">{{ fmtInt(totals.Orders) }}</td>
          <td class="num">{{ fmtMoney(totals.Gmv) }}</td>
          <td class="num">{{ fmtInt(totals.Items) }}</td>
          <td class="num">{{ fmtInt(totals.Buyers) }}</td>
          <td class="num">{{ fmtMoney(totals.aov) }}</td>
        </tr>
      </tfoot>
    </table>
  </div>
</template>

<!--
  商家端「经营数据」看板（可视化段 commit 2，2026-10-11）。

  ⚠️ **本店业务号从会话取**（`getMerchantId()`，同 `GoodsUp.vue` / `GoodsAdd.vue` 的做法），
     没有从路由参数取 —— 让 `MerchantId` 出现在 URL 里就等于允许改一个 URL 看别家的账。
     后端 `MerchantStatsController` 今日**没有**归属校验（沿用既有商家端点的口径，已登记为已知缺口），
     所以这一层「不让它出现在 URL」是**当前唯一的隔离**，别顺手把 id 挪进 query。

  ⚠️ **本页没有「重建」入口**：`POST /admin/stats/rebuild` 是全量写、属管理员。商家端只读。

  ⚠️ 布局：本路由 `meta.layout = 'page'` ⇒ 壳**不给**内边距（`AppShell.vue` 的 `.page { padding: 0 }`），
     页面自己提供居中宽度与留白（同 `MessageCenter.vue` 的 `.mc-bar`）。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { merchantStats } from '../../api/stats'
import { getMerchantId } from '../../api/auth'
import LineChart from '../../components/charts/LineChart.vue'
import BarChart from '../../components/charts/BarChart.vue'
import KpiCard from '../../components/ui/KpiCard.vue'

/** 快捷档。`days: null` = 「全部」= 不发日期参数（后端退化成不设谓词，**不等价于**一个很大的 N）。 */
const RANGES = [
  { key: '7', label: '近 7 天', days: 7 },
  { key: '30', label: '近 30 天', days: 30 },
  { key: '90', label: '近 90 天', days: 90 },
  { key: 'all', label: '全部', days: null },
]

/** ⚠️ 在 `setup` 里算一次即可：`getMerchantId()` **不是响应式**（读 `auth.js` 的模块态 +
 *  `sessionStorage`），且本页只在本壳里挂载 ⇒ 身份不会在页面停留期间变化（同 `AppShell.vue` 那条
 * 对 `isLoggedIn()` 的说明）。 */
const mid = ref(getMerchantId() || '')

const loading = ref(false)
const builtRows = ref(0)
const days = ref([])
const totals = ref({})
const topGoods = ref([])
const prevTotals = ref(null)
const ranged = ref(false)
const loadFailed = ref(false)
const loadMsg = ref('')

const quick = ref('all')
const f = reactive({ from: '', to: '' })
const lastRange = ref({ from: '', to: '' })

const notBuilt = computed(() => builtRows.value === 0 && !loadFailed.value)

const rangeLabel = computed(() => {
  const rg = lastRange.value
  const span = rg.from && rg.to ? `${rg.from} ~ ${rg.to}` : (rg.from || rg.to || '')
  const r = RANGES.find((x) => x.key === quick.value)
  if (r && r.days) return span ? `${span}（${r.label}）` : r.label
  return span ? `${span}（自定义）` : '全部已聚合日'
})

// ── 格式化（本页自备，不抽公共模块 —— 与 `api/stats.js` 的 `q()` 同一条口径）──
const fmtInt = (v) => (v === null || v === undefined ? '0' : Number(v).toLocaleString('zh-CN'))
const fmtMoney = (v) =>
  Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
/** 环比：`null` = 不画箭头（没有前一区间，或前一区间为 0 ⇒ 除法没有意义）。 */
function deltaOf(key) {
  if (!ranged.value || !prevTotals.value) return null
  const prev = Number(prevTotals.value[key] || 0)
  if (prev === 0) return null
  return ((Number(totals.value[key] || 0) - prev) / prev) * 100
}

/** 连续日期串，只在 `days` 的首末之间补空洞（两端不外扩 —— 别把「还没到」画成「没生意」）。 */
const seriesDates = computed(() => {
  const list = days.value.map((r) => r.StatDate).filter(Boolean)
  if (!list.length) return []
  const start = new Date(list[0] + 'T00:00:00')
  const end = new Date(list[list.length - 1] + 'T00:00:00')
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return list
  const out = []
  const d = new Date(start)
  while (d <= end) {
    out.push(
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    )
    d.setDate(d.getDate() + 1)
  }
  return out
})
const byDate = computed(() => {
  const m = new Map()
  days.value.forEach((r) => m.set(r.StatDate, r))
  return m
})
/** ⚠️ 图用**万元**（GMV 是 1e7 量级，用元会让轴标签挤成一片），KPI 卡与明细表仍按**元**。 */
const gmvSeries = computed(() => seriesDates.value.map((d) => Number(byDate.value.get(d)?.Gmv || 0) / 10000))
const ordersSeries = computed(() => seriesDates.value.map((d) => Number(byDate.value.get(d)?.Orders || 0)))

const goodsNames = computed(() => topGoods.value.map((g) => g.GoodName || g.ProductCode || '（无码）'))
const goodsSales = computed(() => topGoods.value.map((g) => Number(g.Subtotal || 0)))
const goodsHeight = computed(() => `${Math.max(180, goodsNames.value.length * 34 + 60)}px`)

function pickQuick(key) {
  quick.value = key
  f.from = ''
  f.to = ''
  load()
}

async function load() {
  loading.value = true
  loadFailed.value = false
  loadMsg.value = ''
  try {
    const params = { MerchantId: mid.value }
    if (quick.value !== 'all') {
      params.days = RANGES.find((x) => x.key === quick.value).days
    } else {
      if (f.from) params.from = f.from
      if (f.to) params.to = f.to
    }
    const data = await merchantStats(params)
    if (!data || !data.success) {
      loadFailed.value = true
      // ⚠️ 后端把「缺 MerchantId」也走 `success:false` ⇒ 这句 msg 要原样透出来（它就是原因）。
      loadMsg.value = (data && data.msg) || '经营数据读取失败'
      return
    }
    builtRows.value = Number(data.builtRows || 0)
    days.value = Array.isArray(data.days) ? data.days : []
    totals.value = data.totals || {}
    topGoods.value = Array.isArray(data.topGoods) ? data.topGoods : []
    prevTotals.value = data.prevTotals || null
    ranged.value = !!(data.range && data.range.days > 0)
    lastRange.value = { from: (data.range && data.range.from) || '', to: (data.range && data.range.to) || '' }
  } catch (e) {
    loadFailed.value = true
    loadMsg.value = (e && e.message) || '经营数据读取失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* ⚠️ 本路由 `meta.layout = 'page'` ⇒ 壳的 `.page` **没有内边距**（`AppShell.vue`），
   居中宽度与留白全在这里（同 `MessageCenter.vue` 的 `.mc-bar` 口径）。 */
.ms-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s6) var(--s5) var(--s8);
}

.ms-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s4);
  flex-wrap: wrap;
}
.ms-h1 {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.ms-sub {
  margin-top: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
}
.ms-mid {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.ms-range {
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
.ms-chip {
  color: var(--info);
  background: var(--info-ground);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.ms-rangespan {
  font-family: var(--font-mono);
  color: var(--brand-ink);
}

.ms-flash {
  margin-top: var(--s4);
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
}
.ms-gate {
  margin-top: var(--s4);
  font-size: var(--fs-sm);
  color: var(--warning-deep);
  background: var(--warning-ground);
  border: 1px solid var(--warning);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
  line-height: 1.7;
}
.ms-gate code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}

.ms-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  margin-top: var(--s5);
  padding: var(--s4) var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
}
.ms-seg {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.ms-seg-b {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  background: none;
  border: 0;
  border-radius: var(--r1);
  padding: var(--s1) var(--s3);
  cursor: pointer;
  white-space: nowrap;
}
.ms-seg-b:hover { color: var(--ink-2); }
.ms-seg-b.on {
  background: var(--surface);
  color: var(--brand-ink);
  font-weight: var(--fw-med);
}
.ms-f {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  flex: 1 1 150px;
  min-width: 130px;
}
.ms-f input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.ms-btn {
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
.ms-btn-on {
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
.ms-btn:disabled { cursor: default; opacity: 0.6; }

.ms-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin: var(--s6) 0 var(--s3);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.ms-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}

.ms-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s4);
}
.ms-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
}

.ms-tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  overflow: hidden;
}
.ms-tbl th,
.ms-tbl td {
  text-align: left;
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
}
.ms-tbl th {
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  color: var(--ink-3);
  background: var(--surface-2);
}
.ms-tbl .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.ms-tbl .mono { font-family: var(--font-mono); }
.ms-tbl tfoot td {
  font-weight: var(--fw-semi);
  color: var(--ink);
  border-bottom: 0;
  background: var(--surface-2);
}
.ms-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s5) 0;
}
</style>
