<template>
  <section class="a-st">
    <!-- ── ① 状态条（与 `AccountsPage.vue` / `OrderPage.vue` / `AuditPage.vue` 同款，不另立一套）──
         ⚠️ 第一个数「行数」是**看板自己是否可用**的判据，不是业务数字：`0` 时的看板**长得像**
         「这段时间一单没有」—— 两者必须在这一眼就分得开（见下方 `notBuilt` 那块横幅）。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ builtRows }}</b>
        <span>stats_daily 行数（预聚合）</span>
      </div>
      <div class="a-stat">
        <b>{{ days.length }}</b>
        <span>本次逐日行</span>
      </div>
      <div class="a-stat">
        <b>{{ merchants.length }}</b>
        <span>区间内有单的商家</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">库时钟</span>
        <span>相对区间（近 N 天）按<b>数据库</b>的今天算</span>
        <span class="a-tz-off">{{ rangeLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── ② 没预聚合过 ⇒ 显式横幅 + 重建入口 ────────────────────────────────────
         ⚠️ `builtRows === 0` 与「区间内确实没单」是**两件事**，但画面上都是「空的」。
         本页在这里把它说破；不做成「进入页面自动重建」—— 重建是**先删后插**的写操作。 -->
    <div v-if="notBuilt" class="a-gate">
      <b>这张看板还没预聚合过。</b>
      <span>
        <code>stats_daily</code> 是空表 ⇒ 下面所有数字都是 0，但那**不代表没有订单** ——
        订单在 <code>order</code> 表里，只是还没被算成日聚合。点右边按钮跑一次全量重建。
      </span>
      <button type="button" class="a-btn a-btn-on" :disabled="rebuilding" @click="doRebuild">
        <IconGlyph name="ok" />{{ rebuilding ? '重建中…' : '立即全量重建' }}
      </button>
    </div>

    <!-- ── ③ 区间选择 ───────────────────────────────────────────────────────────
         ⚠️ 快捷档与自定义**互斥**：快捷档发 `days`（唯一能给环比的形态），自定义发 `from`/`to`
         （此时 `prevTotals` 恒为 `null` ⇒ KPI 卡**不画环比箭头**，而不是画一个 0%）。 -->
    <form class="a-filters" @submit.prevent="load">
      <div class="a-f a-f-quick">
        <span>快捷区间</span>
        <div class="a-seg">
          <button
            v-for="r in RANGES"
            :key="r.key"
            type="button"
            class="a-seg-b"
            :class="{ on: quick === r.key }"
            @click="pickQuick(r.key)"
          >{{ r.label }}</button>
        </div>
      </div>
      <label class="a-f a-f-wide">
        <span>起始（含）</span>
        <input v-model.trim="f.from" type="text" placeholder="YYYY-MM-DD" />
      </label>
      <label class="a-f a-f-wide">
        <span>结束（含）</span>
        <input v-model.trim="f.to" type="text" placeholder="YYYY-MM-DD" />
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="loading"><IconGlyph name="ok" />查询</button>
        <button type="button" class="a-btn" :disabled="loading || rebuilding" @click="doRebuild">
          全量重建
        </button>
      </div>
    </form>

    <p class="a-hint">
      日期口径：<code>stats_daily.StatDate</code> 由 <code>DATE(order.CreateTime)</code> 切出，
      而 <code>CreateTime</code> 是零填充的 ISO 文本（<code>YYYY-MM-DD HH:MM:SS</code>）—— 与订单页的时间筛同一依赖。
      区间是<b>闭区间</b>，缺一头即那一头敞开。
      ⚠️ <b>「近 N 天」才会算环比</b>：自定义区间只给了一头时区间没有长度，硬凑「前一等长区间」等于编一个没人要的区间。</p>

    <!-- ══ ④ 区间合计（KPI）════════════════════════════════════════════════════ -->
    <h2 class="a-h2">区间合计 <span class="a-h2n">{{ rangeLabel }}</span></h2>

    <!-- ⚠️ 拉失败时大数字显示 `—` 而**不是 `0`** —— 同 `OrderPage.vue` 对异常态的处置口径：
         「真的是 0」与「没查出来」长得一模一样是最坏的一种错。 -->
    <div class="a-kpis">
      <KpiCard
        label="成交额（GMV）" glyph="metrics" unit="元"
        :value="loadFailed ? '—' : fmtMoney(totals.Gmv)"
        :delta="deltaOf('Gmv')" delta-label="较上一等长区间"
        :loading="loading"
      />
      <KpiCard
        label="成单数" glyph="orders" unit="单"
        :value="loadFailed ? '—' : fmtInt(totals.Orders)"
        :delta="deltaOf('Orders')" delta-label="较上一等长区间"
        :loading="loading"
      />
      <KpiCard
        label="商品件数" glyph="goods" unit="件"
        :value="loadFailed ? '—' : fmtInt(totals.Items)"
        :delta="deltaOf('Items')" delta-label="较上一等长区间"
        :loading="loading"
      />
      <KpiCard
        label="买家数" glyph="users" unit="人日"
        :value="loadFailed ? '—' : fmtInt(totals.Buyers)"
        :delta="deltaOf('Buyers')" delta-label="较上一等长区间"
        :hint="'按日去重后相加，不是跨日人数'"
        :loading="loading"
      />
      <KpiCard
        label="客单价" glyph="metrics" unit="元"
        :value="loadFailed ? '—' : fmtMoney(totals.aov)"
        :delta="deltaOf('aov')" delta-label="较上一等长区间"
        :loading="loading"
      />
    </div>

    <!-- ══ ⑤ 成交额 / 成单数趋势（双 Y 轴折线）══════════════════════════════════
         ⚠️ 折线按日期序列**补 0**（后端刻意不补，见 `StatsAggregateJob` / `StatsService`：
         服务端补零会让「这天没单」与「这天没聚合」在库里长得一样）。
         做法：把 `days` 按 `StatDate` 铺成连续日期串，缺的填 0。
         ⚠️ **2026-10-11 用户裁决：合并为一张双 Y 轴折线**（原先是「成交额折线」+「成单数柱」
         两张图 —— GMV 1e7 / 单数 1e1 差三个数量级，同轴会把小的压成一条线）。现在：
         **左轴 = 成交额（万元）、右轴 = 成单数（单）**，两条轴各自染色 ⇒ 不写轴名也一眼对得上
         （见 `LineChart.vue` 的 `y2Formatter` 与 `valueAxis` 的 `color` 入参）。
         合起来的收益就是这张图存在的理由：**量涨价跌还是量价齐升**，分两张图要来回对眼珠。 -->
    <h2 class="a-h2">
      成交额 / 成单数趋势
      <span class="a-h2n">左轴 ¥ 单位万元 · 右轴 单位单 · 缺日补 0</span>
    </h2>
    <div class="a-card">
      <LineChart
        :categories="seriesDates"
        :series="[
          { name: '成交额', data: gmvSeries, area: true },
          { name: '成单数', data: ordersSeries, axis: 'right' },
        ]"
        :height="'300px'"
        y-formatter="{value} 万"
        y2-formatter="{value} 单"
        :loading="loading"
        :error="loadFailed ? loadMsg : ''"
        @retry="load"
      />
    </div>

    <!-- ══ ⑦ 商家榜 ═══════════════════════════════════════════════════════════
         ⚠️ 这块**不是**全站数据按商家再分一次的那种「明细」—— 它走的是 `Scope = merchant` 的行，
         与上面的全站行是**同一批订单的两种切法**，但 `Buyers` 不可加（各商家内去重）。
         ⇒ 别做「商家合计 = 全站合计」的断言，那条不成立（后端 `StatsService.adminStats` 明写）。 -->
    <h2 class="a-h2">商家榜 <span class="a-h2n">口径 = merchant · 按成交额降序 · 单位：万元</span></h2>
    <div class="a-card">
      <BarChart
        :categories="merchantNames"
        :series="[{ name: '成交额', data: merchantGmv }]"
        :height="merchantHeight"
        horizontal
        :show-legend="false"
        :label-truncate="14"
        :max-bar-width="22"
        :loading="loading"
        :error="loadFailed ? loadMsg : ''"
        empty-text="区间内没有产生订单的商家"
        @retry="load"
      />
    </div>

    <!-- ══ ⑧ 逐日明细 ═════════════════════════════════════════════════════════ -->
    <h2 class="a-h2">逐日明细 <span class="a-h2n">Scope = site · 升序</span></h2>
    <p v-if="!days.length" class="a-empty">
      {{ loading ? '加载中…' : (notBuilt ? '还没预聚合，先跑一次全量重建。' : '该区间内没有已聚合的日。') }}
    </p>
    <table v-else class="a-tbl">
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
    <!-- 块 7 收尾 · P1：标准分页（默认每页 20）。合计行（`tfoot`）画的是**整段**合计，
         不是当前页合计 —— 故它不随翻页变。 -->
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
</template>

<!--
  管理端「经营数据」看板（可视化段 commit 2，2026-10-11）。

  ⚠️ **本页是「读」页，唯一的写动作是那两枚「全量重建」按钮**（`POST /admin/stats/rebuild`）。
     它**先删后插**（后端 `@Transactional` 包住）⇒ 不做成「进页面自动调」：那会让每次翻页都
     重算一遍全表。重建完**必须重新拉一次数据**（见 `doRebuild`），否则页面还挂着旧数。

  ⚠️ **本页一次都不直接读 `order` 表** —— 所有业务数字都来自预聚合的 `stats_daily`
     （这正是 commit 2 建那张表的全部意义：端点响应不随订单表增长而变慢）。
     `order_item` 只在商家端的商品榜里被实时聚合（粒度是「商品」不是「日」）。

  ⚠️ **日期一律是字符串**（`'YYYY-MM-DD'`）：后端在 SQL 里做了 `DATE_FORMAT`，序列化路径上
     没有日期类型 ⇒ 前端不必处理「epoch 毫秒」。别在本页对它做 `new Date(...)` 再格式化返回
     —— 那会把无时区的库内文本按浏览器时区解释，凭空造出一天的偏移。
-->
<script setup>
/* ⚠️ 本仓 `eslint-plugin-vue` 是 8.7.1，`vue3-essential` 不带宏环境 ⇒ 只要用了 `defineProps` /
   `defineEmits` 就必须写这条（理由见 `components/charts/BaseChart.vue` 同款注释）。本页没有宏，
   但 `components/ui/KpiCard.vue` 等子组件里有 —— 那条已在各自文件内声明，无需在这里补。 */
import { computed, onMounted, reactive, ref } from 'vue'
import { adminStats, adminStatsRebuild } from '../../api/stats'
import LineChart from '../../components/charts/LineChart.vue'
import BarChart from '../../components/charts/BarChart.vue'
import KpiCard from '../../components/ui/KpiCard.vue'; import IconGlyph from '../../components/ui/IconGlyph.vue'; import Pager from '../../components/Pager.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移

/** 快捷档。⚠️ `days: null` = 「全部」= **不发任何日期参数**（后端退化成「不设谓词」），
 *  与 `days = 一个很大的数` **不等价** —— 后者会去编一个从今天往回数 N 天的区间。 */
const RANGES = [
  { key: '7', label: '近 7 天', days: 7 },
  { key: '30', label: '近 30 天', days: 30 },
  { key: '90', label: '近 90 天', days: 90 },
  { key: 'all', label: '全部', days: null },
]

const loading = ref(false)
const rebuilding = ref(false)
const builtRows = ref(0)
const days = ref([])
// 块 7 收尾 · P1：逐日明细前端分页（默认每页 20）。区间本身是日期有界的，
// 这里只切数组、不再请求；`total` 用整段行数，合计行仍按整段算。
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
const merchants = ref([])
const prevTotals = ref(null)
/** 本区间是否给得出环比：只有快捷档（`days`）才有 —— 决定 KPI 卡画不画箭头。 */
const ranged = ref(false)
const loadFailed = ref(false)
const loadMsg = ref('')
const flash = reactive({ text: '', kind: 'ok' })

/** 快捷档 key；非空即「不用自定义 from/to」（两者互斥，见模板注释）。 */
const quick = ref('all')
const f = reactive({ from: '', to: '' })

/** 还没预聚合过。⚠️ 与「区间内没数据」是两件事，模板里靠它把前者说破。 */
const notBuilt = computed(() => builtRows.value === 0 && !loadFailed.value)

/** 后端**自己解析出来的**那对日期（快捷档回算过的，不是我们发过去的原始参数）。 */
const lastRange = ref({ from: '', to: '' })

/** 区间文案：回显后端解析出来的那对日期 —— 不让用户猜「近 7 天」到底算成了哪七天。 */
const rangeLabel = computed(() => {
  const rg = lastRange.value
  const span = rg.from && rg.to ? `${rg.from} ~ ${rg.to}` : (rg.from || rg.to || '')
  const r = RANGES.find((x) => x.key === quick.value)
  if (r && r.days) return span ? `${span}（${r.label}）` : r.label
  return span ? `${span}（自定义）` : '全部已聚合日'
})

// ── 格式化（本页自备：三端显示口径各不相同，不抽公共模块 —— 同 `api/stats.js` 的 `q()` 口径）──
const fmtInt = (v) => (v === null || v === undefined ? '0' : Number(v).toLocaleString('zh-CN'))
const fmtMoney = (v) =>
  Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
/** 环比：`null` = 不画（没有前一区间，或前一区间为 0 ⇒ 除法没有意义）。 */
function deltaOf(key) {
  if (!ranged.value || !prevTotals.value) return null
  const prev = Number(prevTotals.value[key] || 0)
  if (prev === 0) return null
  return ((Number(totals.value[key] || 0) - prev) / prev) * 100
}

/**
 * 连续日期串。⚠️ **只补两端之间的空洞**，不在两端外延伸：`days` 是「库里已聚合的日」，
 * 它本身就定义了看板的横轴范围；按「近 N 天」去补满 N 个格子的话，还没到的那些日子
 * 会以「0 单」出现在图上 —— 那是把「未来」画成了「没生意」。
 */
const seriesDates = computed(() => {
  const list = days.value.map((r) => r.StatDate).filter(Boolean)
  if (!list.length) return []
  // `days` 是后端 `ORDER BY StatDate ASC` 回来的 ⇒ 首末即最小 / 最大日。
  const start = new Date(list[0] + 'T00:00:00')
  const end = new Date(list[list.length - 1] + 'T00:00:00')
  // ⚠️ 解析不出来（日期串不是 `YYYY-MM-DD`）就**原样回**：宁可横轴有洞，
  //    也不要在前端编一串假日期去「补」一个本来就不成立的区间。
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
/** ⚠️ 折线纵轴用**万元**：GMV 是 1e7 量级，直接用元的话轴标签是 8 位数、挤成一片。
 *  KPI 卡与逐日表仍按**元**（那是「要对的数」），图是「看形状的」——两处口径不同且都有标注。 */
const gmvSeries = computed(() => seriesDates.value.map((d) => Number(byDate.value.get(d)?.Gmv || 0) / 10000))
const ordersSeries = computed(() => seriesDates.value.map((d) => Number(byDate.value.get(d)?.Orders || 0)))

const merchantNames = computed(() => merchants.value.map((m) => m.MerchantId))
const merchantGmv = computed(() => merchants.value.map((m) => Number(m.Gmv || 0) / 10000))
/** 横向柱的高度随条数长：固定 320px 时 10 个商家会挤成 32px 一根。 */
const merchantHeight = computed(() => `${Math.max(200, merchantNames.value.length * 34 + 60)}px`)

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
    const params = quick.value === 'all'
      ? {}
      : { days: RANGES.find((x) => x.key === quick.value).days }
    // 自定义区间：只在**没有**选快捷档时生效（快捷档已把 f.from/f.to 清空）。
    if (quick.value === 'all') {
      if (f.from) params.from = f.from
      if (f.to) params.to = f.to
    }
    const data = await adminStats(params)
    if (!data || !data.success) {
      loadFailed.value = true
      loadMsg.value = (data && data.msg) || '经营数据读取失败'
      return
    }
    builtRows.value = Number(data.builtRows || 0)
    days.value = Array.isArray(data.days) ? data.days : []
    pageOffset.value = 0   // 换区间 / 重建后回第一页
    totals.value = data.totals || {}
    merchants.value = Array.isArray(data.merchants) ? data.merchants : []
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

async function doRebuild() {
  rebuilding.value = true
  flash.text = ''
  try {
    const data = await adminStatsRebuild()
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '重建失败：' + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `重建完成：清掉 ${data.deleted} 行，写入 全站 ${data.siteRows} 行 + 商家 ${data.merchantRows} 行，现共 ${data.totalRows} 行。`
    // ⚠️ 必须重拉：重建只动了库，页面手上那份还是旧的。
    await load()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '重建失败：' + ((e && e.message) || '未知原因')
  } finally {
    rebuilding.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.a-st {
  max-width: 1120px;
}

/* ── ① 状态条（照 `OrderPage.vue`，逐字同源）────────────────────────────── */
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

/* ── 反馈条 ──────────────────────────────────────────────────────────────── */
.a-flash {
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
  white-space: pre-wrap;
}
.a-flash.ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.a-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}

/* ── ② 「还没预聚合」横幅 —— **警告色而不是错误色**：它不是故障，是「还没跑过一次」。 */
.a-gate {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s3);
  font-size: var(--fs-sm);
  color: var(--warning-deep);
  background: var(--warning-ground);
  border: 1px solid var(--warning);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
  margin-bottom: var(--s5);
  line-height: 1.7;
}
.a-gate b {
  font-weight: var(--fw-semi);
}
.a-gate span {
  flex: 1 1 320px;
  min-width: 240px;
}

/* ── ③ 区间选择（表单外壳照 `OrderPage.vue`，多一组分段按钮）──────────────── */
.a-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  /* ⑥：与上面 `.a-card` 同一条理由（稿 `.pad` = 16 四周）—— 同一页里两种「面板内边距」是「不对齐」。 */
  padding: var(--s4);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
}
.a-f {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-f-wide {
  flex: 1 1 180px;
  min-width: 150px;
}
.a-f select,
.a-f input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-f-actions {
  display: flex;
  gap: var(--s2);
  margin-left: auto;
}

/* 分段按钮（照 `OrderPane.vue` 那枚两态开关的语言：`--surface-2` 轨道 + 选中态 `--brand-ink`） */
.a-seg {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.a-seg-b {
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
.a-seg-b:hover {
  color: var(--ink-2);
}
.a-seg-b.on {
  background: var(--surface);
  color: var(--brand-ink);
  font-weight: var(--fw-med);
}

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
.a-btn-on {
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
.a-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.a-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  margin: var(--s3) 0 var(--s5);
}
.a-hint code,
.a-gate code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}

/* ── 分段标题（照 `OrderPage.vue`）────────────────────────────────────── */
.a-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  /* ⚠️ 2026-10-11（用户裁决 ⑥「去 AI 化」）：`--s7`(48) → `--s6`(32)、下 `--s4`(16) → `--s3`(12)。
     改前「上一张卡 margin-bottom 16 + 本标题 margin-top 48」= 段间距 64px，稿里整个 `.body` 的块间距
     是 16px（`丁-运营蓝.html:148`）—— 4 倍的留白正是「一眼 AI 味」。本页不能真收到 16（稿的标题在卡
     **内**，本页标题在卡**外**、靠这两条 margin 划段）⇒ 收到 32/12。
     ⚠️ 与商家端**对齐**：`merchant/StatsPage.vue:479-485` 的 `.ms-h2` 本来就是 `margin: var(--s6) 0 var(--s3)`
     —— 两个看板此前一个 24px/48px、一个 19px/32px，是「同一件事两种排法」。 */
  margin: var(--s6) 0 var(--s3);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
  /* ⑥：`--fs-lg`(24) → `--fs-md`(19)，向商家端 `.ms-h2` 看齐（同上）。 */
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
.a-st > .a-h2:first-of-type {
  margin-top: 0;
}

/* ── ④ KPI 行 ─────────────────────────────────────────────────────────────── */
.a-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  /* ⑥：`--s4`(16) → `--s3`(12) = 稿 `.kpis{gap:12px}`（`丁-运营蓝.html:176`）的**同一个 token**。 */
  gap: var(--s3);
}

/* ── ⑤⑥⑦ 图卡 ───────────────────────────────────────────────────────────── */
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  /* ⑥：`--s4 --s5`（16/24）→ `--s4`（16 四周）= 稿 `.pad{padding:16px}`（`丁-运营蓝.html:173`）。
     卡内是图表与表格，左右各让出 8px 只会让图更宽，不动结构。 */
  padding: var(--s4);
  margin-bottom: var(--s4);
}

/* ── ⑧ 明细表 ─────────────────────────────────────────────────────────────── */
.a-tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  overflow: hidden;
}
.a-tbl th,
.a-tbl td {
  text-align: left;
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
}
.a-tbl th {
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  color: var(--ink-3);
  background: var(--surface-2);
}
.a-tbl .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.a-tbl .mono {
  font-family: var(--font-mono);
}
.a-tbl tfoot td {
  font-weight: var(--fw-semi);
  color: var(--ink);
  border-bottom: 0;
  background: var(--surface-2);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6) 0;
}
</style>
