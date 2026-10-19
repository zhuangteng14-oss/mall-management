<template>
  <main class="ms-page">
    <!-- A 版页头（稿 `a.html` · pageStats 的 `.page-head`）：kicker / h1 / 说明 + 右侧出口。
         ⚠️ 右侧「刷新」调用的仍是既有 `load()`（不新增端点）；区间文案仍来自 `rangeLabel`。 -->
    <header class="page-head">
      <div>
        <div class="kicker">MERCHANT / STATS</div>
        <h1>经营数据</h1>
        <p class="lead">
          本店口径（<code>Scope = merchant</code>），业务号
          <b class="mid">{{ mid || '（未取到）' }}</b>
          · 数据来自预聚合的 <code>stats_daily</code>，不直读订单表
        </p>
      </div>
      <div class="head-actions">
        <span class="range-chip">
          <span class="range-k">区间</span>
          <b>{{ rangeLabel }}</b>
        </span>
        <button type="button" class="btn" :disabled="loading" @click="load">
          <IconGlyph name="flow" />刷新
        </button>
      </div>
    </header>

    <p v-if="loadMsg" class="flash">{{ loadMsg }}</p>

    <!-- ── 没预聚合过 ⇒ 说破它 ────────────────────────────────────────────────
         ⚠️ 与订单页 / 管理端看板同一条口径：`0`（真的没生意）与「还没算过」必须分得开。
         商家端**没有重建入口**（`/admin/stats/rebuild` 是管理员的）⇒ 这里只解释、不给按钮。 -->
    <p v-if="notBuilt" class="gate">
      <b>本店还没被预聚合过。</b>
      <code>stats_daily</code> 里暂时没有本店的行 ⇒ 下面全是 0，但那不代表没有订单。
      全量重建是<b>管理员</b>的动作（<code>POST /admin/stats/rebuild</code>），本页不提供入口。
    </p>

    <!-- ── 区间选择（A 版 chips + 发丝线输入框）──────────────────────────────── -->
    <form class="filter-bar" @submit.prevent="load">
      <div class="chips">
        <button
          v-for="r in RANGES"
          :key="r.key"
          type="button"
          class="chip"
          :class="{ on: quick === r.key }"
          @click="pickQuick(r.key)"
        >{{ r.label }}</button>
      </div>
      <label class="field">
        <span>起始（含）</span>
        <input v-model.trim="f.from" type="text" placeholder="YYYY-MM-DD" />
      </label>
      <label class="field">
        <span>结束（含）</span>
        <input v-model.trim="f.to" type="text" placeholder="YYYY-MM-DD" />
      </label>
      <button type="submit" class="btn solid" :disabled="loading"><IconGlyph name="ok" />查询</button>
    </form>

    <!-- ══ 区间合计（A 版 stat-band：直角 + 发丝线 + mono 数字）════════════════ -->
    <section class="band-block">
      <div class="section-title">
        <h2>区间合计</h2>
        <span class="tiny">TOTALS / RANGE</span>
      </div>
      <div class="stat-band">
        <div class="stat-cell">
          <KpiCard
            label="成交额（GMV）" glyph="metrics" unit="元"
            :value="loadFailed ? '—' : fmtMoney(totals.Gmv)"
            :delta="deltaOf('Gmv')" delta-label="较上一等长区间" :loading="loading"
          />
        </div>
        <div class="stat-cell">
          <KpiCard
            label="成单数" glyph="orders" unit="单"
            :value="loadFailed ? '—' : fmtInt(totals.Orders)"
            :delta="deltaOf('Orders')" delta-label="较上一等长区间" :loading="loading"
          />
        </div>
        <div class="stat-cell">
          <KpiCard
            label="商品件数" glyph="goods" unit="件"
            :value="loadFailed ? '—' : fmtInt(totals.Items)"
            :delta="deltaOf('Items')" delta-label="较上一等长区间" :loading="loading"
          />
        </div>
        <div class="stat-cell">
          <KpiCard
            label="买家数" glyph="users" unit="人日"
            :value="loadFailed ? '—' : fmtInt(totals.Buyers)"
            :delta="deltaOf('Buyers')" delta-label="较上一等长区间"
            hint="按日去重后相加，不是跨日人数" :loading="loading"
          />
        </div>
        <div class="stat-cell">
          <KpiCard
            label="客单价" glyph="metrics" unit="元"
            :value="loadFailed ? '—' : fmtMoney(totals.aov)"
            :delta="deltaOf('aov')" delta-label="较上一等长区间" :loading="loading"
          />
        </div>
      </div>
    </section>

    <!-- ══ 成交额 / 成单数趋势（双 Y 轴折线）═══════════════════════════════════
         ⚠️ 2026-10-11：与**管理端** `views/admin/StatsPage.vue` 同款整改，两边口径必须一致
         （同一个后端端点、同一批 `Scope = merchant` 的行，图长得不一样会让人以为数据不同）——
         GMV 1e7 / 单数 1e1 差三个数量级，同轴会把小的压成一条线，故拆**左右两条轴**：
         **左轴 = 成交额（万元）、右轴 = 成单数（单）**，轴各自染色（见 `LineChart.vue`）。 -->
    <section class="panel">
      <div class="section-title">
        <h2>成交额 / 成单数趋势</h2>
        <span class="tiny">左轴 ¥ 单位万元 · 右轴 单位单 · 缺日补 0</span>
      </div>
      <LineChart
        :categories="seriesDates"
        :series="[
          { name: '成交额', data: gmvSeries, area: true },
          { name: '成单数', data: ordersSeries, axis: 'right' },
        ]"
        :height="'280px'"
        y-formatter="{value} 万"
        y2-formatter="{value} 单"
        :loading="loading"
        :error="loadFailed ? loadMsg : ''"
        @retry="load"
      />
    </section>

    <!-- ══ 商品榜 ═════════════════════════════════════════════════════════════
         ⚠️ **上线即空是预期，不是故障**（用户裁定 §三：`order_item` 不回填存量单）。
         所以这里的空态文案**必须**解释成因，而不是那句通用的「暂无数据」——
         否则商家会以为是自己这店没卖出去东西。 -->
    <section class="panel">
      <div class="section-title">
        <h2>商品榜</h2>
        <span class="tiny">实时聚合 order_item · 按销售额降序</span>
      </div>
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
    </section>

    <!-- ══ 逐日明细 ═══════════════════════════════════════════════════════════ -->
    <section class="panel">
      <div class="section-title">
        <h2>逐日明细</h2>
        <span class="tiny">ASC / 升序</span>
      </div>
      <p v-if="!days.length" class="empty">
        {{ loading ? '加载中…' : (notBuilt ? '还没预聚合，等管理员跑一次全量重建。' : '该区间内本店没有已聚合的日。') }}
      </p>
      <div v-else class="table-wrap">
        <table class="data-table">
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
            <tr v-for="r in pageDays" :key="r.StatDate">
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
      <!-- 块 7 收尾 · P1：标准分页（默认每页 20）；早先的 `tfoot` 合计仍按**整段**算。 -->
      <Pager
        v-if="days.length"
        :total="days.length"
        :limit="pageLimit"
        :offset="pageOffset"
        :sizes="[20, 50, 100]"
        unit="天"
        label="逐日明细"
        @change="onDaysPage"
      />
    </section>
  </main>
</template>

<!--
  商家端「经营数据」看板（可视化段 commit 2，2026-10-11）。

  ⚠️ **本店业务号从会话取**（`getMerchantId()`，同 `GoodsUp.vue` / `GoodsAdd.vue` 的做法），
     没有从路由参数取 —— 让 `MerchantId` 出现在 URL 里就等于允许改一个 URL 看别家的账。
     后端 `MerchantStatsController` 今日**没有**归属校验（沿用既有商家端点的口径，已登记为已知缺口），
     所以这一层「不让它出现在 URL」是**当前唯一的隔离**，别顺手把 id 挪进 query。

  ⚠️ **本页没有「重建」入口**：`POST /admin/stats/rebuild` 是全量写、属管理员。商家端只读。

  ⚠️ **布局（2026-10-11，用户裁决 ④ 后重写）**：本页原挂 `/merchant`（`meta.layout:'page'`
     ⇒ 独立整页、自带居中与留白），现挂 `merchantUser` 下 ⇒ 跑在**个人中心壳**的右栏
     （`.user-page` 的 flex 行），居中与留白由 `.user-page` 给，本页只留 `flex:1; min-width:0`。
     ⚠️ **地址没变**（仍是 `/merchant/stats`），靠的是子记录写绝对路径 —— 见 `router/index.js` 最尾。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { merchantStats } from '../../api/stats'
import { getMerchantId } from '../../api/auth'
import LineChart from '../../components/charts/LineChart.vue'
import BarChart from '../../components/charts/BarChart.vue'
import KpiCard from '../../components/ui/KpiCard.vue'; import IconGlyph from '../../components/ui/IconGlyph.vue'; import Pager from '../../components/Pager.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移

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
// 块 7 收尾 · P1：逐日明细前端分页（默认每页 20）。区间有界，只切数组、不再请求。
const pageLimit = ref(20)
const pageOffset = ref(0)
const pageDays = computed(() => {
  const total = days.value.length
  const maxOffset = Math.max(0, Math.ceil(total / pageLimit.value) - 1) * pageLimit.value
  const off = Math.min(Math.max(0, pageOffset.value), maxOffset)
  return days.value.slice(off, off + pageLimit.value)
})
function onDaysPage({ limit, offset }) {
  pageLimit.value = limit
  pageOffset.value = offset
}
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
    pageOffset.value = 0   // 换区间后回第一页
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
/* ⚠️ 2026-10-11（用户裁决 ④「经营数据入口放个人中心里」）：本页跑在**个人中心壳**
   （`AppShell.vue` 的 `.user-page` flex 行）的右栏槽位里 ⇒ ① 根元素是 flex 子项，
   要 `flex:1; min-width:0` 才撑住右栏（min-width:0 防宽表把右栏顶宽）；② 外层居中与
   留白交还 `.user-page`（它已给 max-width + margin auto + padding），本页不再各来一套。
   ⚠️ 2026-10-19：版式按 A 版线框（`_work/ui-wireframe/a.html` · `pageStats`）重排 ——
   直角、发丝线、mono 数字；只动模板结构与样式，`<script setup>` 与所有数据链原样。 */
.ms-page {
  flex: 1;
  min-width: 0;
}

/* ── 页头（稿 `.page-head`）──────────────────────────────────────────────── */
.page-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s5);
  align-items: end;
  padding-bottom: var(--s5);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--s6);
}
.kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.page-head h1 {
  margin: 10px 0 12px;
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semi);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.lead {
  max-width: 700px;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-3);
}
.lead code,
.mid {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.mid { font-weight: var(--fw-med); }
.head-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--s2);
}
.range-chip {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  padding: 8px 10px;
  border: 1px solid var(--line-2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.range-k {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--ink-3);
}
.range-chip b {
  font-family: var(--font-mono);
  font-weight: var(--fw-reg);
  color: var(--ink);
}

/* ── 按钮（稿 `.btn` / `.btn.solid`：直角、1px 墨线）──────────────────────── */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--s2);
  height: 36px;
  padding: 0 var(--s5);
  border: 1px solid var(--line-2);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink-2);
  font: inherit;
  font-size: var(--fs-sm);
  cursor: pointer;
  white-space: nowrap;
}
.btn:hover { border-color: var(--ink); color: var(--ink); }
.btn.solid {
  border-color: var(--ink);
  background: var(--ink);
  color: var(--surface);
}
.btn.solid:hover { background: var(--surface); color: var(--ink); }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }

/* ── 提示（原有 0 / 未聚合 / 错误文案原样保留）────────────────────────────── */
.flash {
  margin-top: var(--s5);
  padding: var(--s3) var(--s4);
  border: 1px solid var(--danger);
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
}
.gate {
  margin-top: var(--s5);
  padding: var(--s4);
  border: 1px solid var(--warning);
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--warning-deep);
  background: var(--warning-ground);
}
.gate code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}

/* ── 区间筛选（稿 `.filter-bar` / `.chips` / `.chip`）────────────────────── */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  margin-top: var(--s5);
  padding: var(--s4);
  border: 1px solid var(--line);
  background: var(--surface);
}
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  padding: 7px 10px;
  border: 1px solid var(--line-2);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink-2);
  font: inherit;
  font-size: var(--fs-xs);
  cursor: pointer;
  white-space: nowrap;
}
.chip:hover { border-color: var(--ink); }
.chip.on {
  border-color: var(--ink);
  background: var(--ink);
  color: var(--surface);
}
.field {
  display: flex;
  flex: 1 1 150px;
  flex-direction: column;
  gap: var(--s1);
  min-width: 130px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.field span {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
}
.field input {
  padding: 7px 10px;
  border: 1px solid var(--line-2);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  font-size: var(--fs-sm);
}
.field input:focus,
.btn:focus-visible,
.chip:focus-visible {
  outline: 2px solid var(--brand-soft);
  outline-offset: 1px;
}

/* ── 区块标题（稿 `.section-title`）──────────────────────────────────────── */
.section-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s4);
  padding-bottom: 10px;
  margin-bottom: var(--s4);
  border-bottom: 1px solid var(--line);
}
.section-title h2 {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.tiny {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--ink-3);
}

/* ── KPI 带（稿 `.stat-band` / `.stat-cell`）───────────────────────────────
   ⚠️ 仍用 `KpiCard`（环比、骨架、`hint`、按字符数自适应字号都是它的功能），
   只在本页把它的卡片外观压平成 stat-cell：直角、去独立边框、数字 mono。 */
.band-block { margin-top: var(--s6); }
.stat-band {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  border: 1px solid var(--line);
  background: var(--surface);
}
.stat-cell {
  min-width: 0;
  border-right: 1px solid var(--line);
}
.stat-cell:last-child { border-right: 0; }
.stat-cell :deep(.kpi) {
  height: 100%;
  padding: var(--s4);
  border: 0;
  border-radius: 0;
  background: transparent;
}
.stat-cell :deep(.kpi-k) {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.05em;
  color: var(--ink-3);
}
.stat-cell :deep(.kpi-v) { margin-top: var(--s2); }
.stat-cell :deep(.kpi-n) {
  font-weight: var(--fw-reg);
  letter-spacing: -0.02em;
}
.stat-cell :deep(.kpi-u),
.stat-cell :deep(.kpi-d) { font-size: 10px; }

/* ── 图表面板（稿 `.panel`）─────────────────────────────────────────────── */
.panel {
  padding: var(--s5);
  border: 1px solid var(--line);
  background: var(--surface);
}
.panel + .panel { margin-top: var(--s5); }
.band-block + .panel { margin-top: var(--s6); }

/* ── 逐日明细（稿 `.log-table`：发丝线 + mono 表头 / 数字列）─────────────── */
.table-wrap { overflow-x: auto; }
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.data-table th {
  padding: 9px 8px;
  border-bottom: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: var(--fw-reg);
  letter-spacing: 0.05em;
  color: var(--ink-3);
  text-align: left;
  white-space: nowrap;
}
.data-table td {
  padding: 11px 8px;
  border-bottom: 1px solid var(--line);
}
.data-table tbody tr:hover { background: var(--zone); }
.data-table .num {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  text-align: right;
  white-space: nowrap;
}
.data-table .mono {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.data-table tfoot td {
  border-top: 1px solid var(--line-2);
  border-bottom: 0;
  background: var(--surface-2);
  color: var(--ink);
  font-weight: var(--fw-semi);
}
.data-table tfoot .num { font-weight: var(--fw-semi); }
.empty {
  padding: var(--s7) var(--s5);
  border: 1px dashed var(--line-2);
  font-size: var(--fs-sm);
  color: var(--ink-3);
  text-align: center;
}

/* ── 窄屏：KPI 带 3 列 → 1 列；页头与区块标题纵向堆叠 ────────────────────── */
@media (max-width: 1100px) {
  .stat-band { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .stat-cell { border-bottom: 1px solid var(--line); }
  .stat-cell:nth-child(3n) { border-right: 0; }
  .stat-cell:nth-last-child(-n + 2) { border-bottom: 0; }
}
@media (max-width: 760px) {
  .page-head {
    grid-template-columns: 1fr;
    align-items: start;
  }
  .head-actions { justify-content: flex-start; }
  .stat-band { grid-template-columns: 1fr; }
  /* `:nth-child(n)` 与上面 1100 断点的 `:nth-last-child(-n+2)` 同特异度、靠后覆盖 ——
     直接写 `.stat-cell` 会因特异度更低而盖不住第 4、5 格的「无下边框」。 */
  .stat-cell:nth-child(n) {
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .stat-cell:last-child { border-bottom: 0; }
  .section-title {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--s1);
  }
}

/* 共享 `Pager` 在本页也走直角（只覆盖本页实例，不改组件本身）。 */
.ms-page :deep(.pager .pg),
.ms-page :deep(.pager-size select) { border-radius: 0; }
.ms-page :deep(.pager .pg.on) {
  border-color: var(--ink);
  background: var(--ink);
  color: var(--surface);
}
</style>
