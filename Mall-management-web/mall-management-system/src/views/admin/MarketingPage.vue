<template>
  <section class="a-mk">
    <!-- ── ① 状态条（与 `StatsPage.vue` / `OrderPage.vue` / `AccountsPage.vue` 同款，不另立一套）──
         ⚠️ 「券总数」是**全表** `COUNT(*)`（`/list` 的 `total`），不是本次筛选数 —— 后端刻意分开
         回报（同 `AdminOrderService.list` 的口径），本页就把它摆在台面上，免得读成筛选数。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ total }}</b>
        <span>券总数（全表，非本次筛选）</span>
      </div>
      <div class="a-stat">
        <b>{{ rows.length }}</b>
        <span>本次显示{{ truncated ? '（已达上限）' : '' }}</span>
      </div>
      <div class="a-stat">
        <b>{{ statsFailed ? '—' : stats.totals.claimed }}</b>
        <span>累计领取</span>
      </div>
      <div class="a-stat">
        <b>{{ statsFailed ? '—' : stats.totals.used }}</b>
        <span>累计核销</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">UTC</span>
        <span>有效期存库内时钟 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ══ ② 建券 ═════════════════════════════════════════════════════════════ -->
    <h2 class="a-h2">
      建券
      <span class="a-h2n">类型 / 面值 / 门槛 / 限量 / 适用范围 / 有效期</span>
    </h2>
    <form class="a-filters" @submit.prevent="doCreate">
      <label class="a-f a-f-wide">
        <span>券名 *</span>
        <input v-model.trim="c.Name" type="text" placeholder="如：双十一满减券" />
      </label>
      <label class="a-f">
        <span>类型 *</span>
        <select v-model="c.Type" @change="onTypeChange">
          <option v-for="t in TYPES" :key="t.code" :value="t.code">{{ t.label }}</option>
        </select>
      </label>
      <label class="a-f a-f-narrow">
        <span>{{ faceHint }}</span>
        <input v-model.trim="c.FaceValue" type="text" :placeholder="facePlaceholder" />
      </label>
      <label class="a-f a-f-narrow">
        <span>门槛（元）{{ c.Type === 'threshold' ? ' *' : '' }}</span>
        <input v-model.trim="c.Threshold" type="text" :disabled="c.Type === 'none'" placeholder="0" />
      </label>
      <label class="a-f a-f-narrow">
        <span>发放总量</span>
        <input v-model.trim="c.TotalQty" type="text" placeholder="0 = 不限量" />
      </label>
      <label class="a-f">
        <span>适用范围</span>
        <select v-model="c.Scope">
          <option value="all">全场</option>
          <option value="merchant">部分商家（限商品种类）</option>
        </select>
      </label>
      <label class="a-f a-f-wide">
        <span>限商品种类{{ c.Scope === 'merchant' ? ' *' : '' }}</span>
        <input
          v-model.trim="c.CategoryMatch"
          type="text"
          :disabled="c.Scope !== 'merchant'"
          placeholder="类目名，逗号分隔（如：手机,电脑）"
        />
      </label>
      <label class="a-f a-f-wide">
        <span>归属商家（空 = 平台券）</span>
        <input v-model.trim="c.MerchantAccount" type="text" placeholder="MerchantId（可空）" />
      </label>
      <label class="a-f a-f-wide">
        <span>有效期起</span>
        <input v-model.trim="c.StartAt" type="text" placeholder="YYYY-MM-DD HH:MM:SS（可空）" />
      </label>
      <label class="a-f a-f-wide">
        <span>有效期止</span>
        <input v-model.trim="c.EndAt" type="text" placeholder="YYYY-MM-DD HH:MM:SS（可空）" />
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="creating">
          <IconGlyph name="ok" />{{ creating ? '提交中…' : '建券' }}
        </button>
        <button type="button" class="a-btn" :disabled="creating" @click="resetCreate">清空</button>
      </div>
    </form>
    <p class="a-hint">
      · <b>折扣券的「面值」是折扣率</b>（<code>0.85</code> = 8.5 折，必须小于 1）；满减 / 无门槛券的面值才是<b>减免金额</b>。发满减券时<b>必须</b>给门槛。
      · <b>总量 <code>0</code> = 不限量</b>；范围选「部分商家」<b>必须</b>同时给类目名。
      · 有效期必须<b>零填充</b>（<code>2026-11-11 00:00:00</code>）—— 后端按此格式校验，别的写法会被当场拒掉而不是静默改。
      · 留空的有效期 = 不设那一条边界。
    </p>

    <!-- ══ ③ 券列表 ═══════════════════════════════════════════════════════════ -->
    <h2 class="a-h2">
      券列表
      <span class="a-h2n">状态未知值会被拒（不静默当成「不筛」）</span>
    </h2>
    <form class="a-filters" @submit.prevent="loadList">
      <label class="a-f">
        <span>状态</span>
        <select v-model="f.Status">
          <option value="">全部</option>
          <option value="active">启用中</option>
          <option value="stopped">已停用</option>
        </select>
      </label>
      <label class="a-f a-f-wide">
        <span>券名关键字</span>
        <input v-model.trim="f.keyword" type="text" placeholder="模糊匹配 Name" />
      </label>
      <label class="a-f a-f-narrow">
        <span>上限</span>
        <input v-model.number="f.limit" type="number" min="1" max="500" />
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="loading"><IconGlyph name="ok" />查询</button>
        <button type="button" class="a-btn" :disabled="loading" @click="resetFilters">清空</button>
      </div>
    </form>

    <p v-if="truncated" class="a-note">
      本次结果已达取回上限：仅取回 {{ rows.length }} 条（按 Id 倒序），后面可能还有。
    </p>
    <p v-if="!rows.length" class="a-empty">{{ loading ? '加载中…' : '没有符合条件的券。' }}</p>
    <table v-else class="a-tbl">
      <thead>
        <tr>
          <th>券</th>
          <th>类型 / 面值</th>
          <th>门槛</th>
          <th>范围</th>
          <th class="num">已领 / 总量</th>
          <th>有效期</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="r.CouponId">
          <td>
            <div class="a-ink">{{ r.Name }}</div>
            <div class="a-mono a-mini">{{ r.CouponId }}</div>
          </td>
          <td>{{ typeLabel(r.Type) }}<span class="a-mono a-mini"> · {{ faceLabel(r) }}</span></td>
          <td class="num">{{ r.Type === 'none' ? '—' : fmtMoney(r.Threshold) }}</td>
          <td>
            {{ r.Scope === 'merchant' ? '部分商家' : '全场' }}
            <div v-if="r.CategoryMatch" class="a-mono a-mini">{{ r.CategoryMatch }}</div>
            <div v-if="r.MerchantAccount" class="a-mono a-mini">商家 {{ r.MerchantAccount }}</div>
          </td>
          <td class="num">{{ fmtInt(r.ClaimedQty) }} / {{ Number(r.TotalQty) === 0 ? '不限量' : fmtInt(r.TotalQty) }}</td>
          <td class="a-mono a-mini">
            {{ r.StartAt || '—' }}<br />{{ r.EndAt || '—' }}
          </td>
          <td>
            <span class="a-chip" :class="r.Status === 'active' ? 'ok' : 'off'">
              {{ r.Status === 'active' ? '启用中' : '已停用' }}
            </span>
          </td>
          <td class="a-ops">
            <button
              v-if="r.Status === 'active'"
              type="button"
              class="a-btn a-btn-sm"
              :disabled="busy === r.CouponId"
              @click="doStop(r.CouponId)"
            ><IconGlyph name="no" />停用</button>
            <button
              v-else
              type="button"
              class="a-btn a-btn-sm"
              :disabled="busy === r.CouponId"
              @click="doActivate(r.CouponId)"
            ><IconGlyph name="ok" />启用</button>
            <button
              type="button"
              class="a-btn a-btn-sm"
              :disabled="busy === r.CouponId"
              @click="openGrants(r.CouponId)"
            ><IconGlyph name="logs" />明细</button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- ══ ④ 图表（三件齐里的「图」）═══════════════════════════════════════════ -->
    <h2 class="a-h2">
      <IconGlyph name="metrics" />领取 / 核销趋势
      <span class="a-h2n">近 {{ TREND_DAYS }} 天 · 两条线已按日期并集对齐</span>
    </h2>
    <div class="a-card">
      <LineChart
        :categories="trendDays"
        :series="[
          { name: '领取', data: trendClaimed, area: true },
          { name: '核销', data: trendUsed },
        ]"
        :height="'300px'"
        y-formatter="{value} 张"
        :loading="statsLoading"
        :error="statsFailed ? statsMsg : ''"
        empty-text="近两周没有任何领取记录"
        @retry="loadStats"
      />
    </div>

    <h2 class="a-h2">
      <IconGlyph name="marketing" />券类型占比
      <span class="a-h2n">按券定义数（不是领取数）</span>
    </h2>
    <div class="a-card">
      <PieChart
        :data="typePie"
        :height="'280px'"
        :loading="statsLoading"
        :error="statsFailed ? statsMsg : ''"
        empty-text="还没有建过券"
        @retry="loadStats"
      />
    </div>

    <!-- ══ ⑤ 领取明细 + 定向发放（选中某张券后才出现）══════════════════════════ -->
    <template v-if="picked">
      <h2 class="a-h2">
        领取明细 —— {{ picked.Name }}
        <span class="a-h2n">{{ picked.CouponId }} · 按领取时间倒序</span>
      </h2>

      <form class="a-filters" @submit.prevent="doGrant">
        <label class="a-f a-f-wide">
          <span>定向发放给（客户业务号）</span>
          <input v-model.trim="grantTo" type="text" placeholder="user.customerid" />
        </label>
        <div class="a-f-actions">
          <button type="submit" class="a-btn a-btn-on" :disabled="granting">
            <IconGlyph name="msg" />{{ granting ? '发放中…' : '发放' }}
          </button>
          <button type="button" class="a-btn" @click="closeGrants">收起</button>
        </div>
      </form>

      <p v-if="!grantRows.length" class="a-empty">
        {{ grantLoading ? '加载中…' : '这张券还没有人领取。' }}
      </p>
      <table v-else class="a-tbl">
        <thead>
          <tr>
            <th>领取人</th>
            <th>来源</th>
            <th>状态</th>
            <th>领取时间</th>
            <th>核销时间</th>
            <th>订单</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in grantRows" :key="g.GrantId">
            <td class="a-mono">{{ g.ToAccount }}</td>
            <td>{{ g.Source === 'grant' ? '定向发放' : '自助领取' }}</td>
            <td>
              <span class="a-chip" :class="g.Status === 'used' ? 'ok' : 'wait'">
                {{ g.Status === 'used' ? '已核销' : '未使用' }}
              </span>
            </td>
            <td class="a-mono a-mini">{{ g.GrantedAt || '—' }}</td>
            <td class="a-mono a-mini">{{ g.UsedAt || '—' }}</td>
            <td class="a-mono a-mini">{{ g.OrderId || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>

<!--
  管理端「营销运营 · 券」页（C3 块 5 · commit 1，2026-10-11）。

  ⚠️ **本页当前只有「券」一个子域 ⇒ 没有页内 tab**（`c3-modules.md` §五 的「多块才上 tab」）。
     commit 2/3/4 落折扣 / 国补 / 秒杀时，本页才该长出 tab 栏 —— 现在先摆一个只有一项的
     tab 栏，等于承诺了三个不存在的页面（同 `admin-nav.js` 对「给占位页挂图标」的那条口径）。

  ⚠️ **核销不在这里**：券的 `unused → used` 发生在**结算**（`orderAdd` 带 `couponId`），
     本页只「读」核销结果（明细里的状态与订单号），块 5 commit 5 才接上结算侧。

  ⚠️ **时间列是库内无时区文本**（`'YYYY-MM-DD HH:MM:SS'`，后端 `DATE_FORMAT` 出来的）
     ⇒ 本页所有时间都**原样显示**、不做 `new Date()` 再格式化（那会按浏览器时区解释无时区文本，
     凭空造出偏移 —— 同 `StatsPage.vue` 的日期口径）。右侧那个 `tzLabel` 是**说明**，不是转换器。
-->
<script setup>
/* ⚠️ 本仓 `eslint-plugin-vue` 是 8.7.1，`vue3-essential` 不带宏环境 ⇒ 只要用了 `defineProps` /
   `defineEmits` 就必须写这条（理由见 `components/charts/BaseChart.vue` 同款注释）。本页没有宏。 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  couponList, couponCreate, couponStop, couponActivate,
  couponGrants, couponGrant, couponStats,
} from '../../api/marketing'
import LineChart from '../../components/charts/LineChart.vue'
import PieChart from '../../components/charts/PieChart.vue'
import IconGlyph from '../../components/ui/IconGlyph.vue'

/** 后端 `CouponService.TREND_DAYS` 的**回显**（图标题上写死一个数会与后端走岔）。 */
const TREND_DAYS = 14

const TYPES = [
  { code: 'threshold', label: '满减' },
  { code: 'percent', label: '折扣' },
  { code: 'none', label: '无门槛' },
]

const loading = ref(false)
const creating = ref(false)
const statsLoading = ref(false)
const statsFailed = ref(false)
const statsMsg = ref('')
/** 正在改状态的那一张券（防连点）—— 也是「哪一行的按钮转圈」的判据。 */
const busy = ref('')
const flash = reactive({ text: '', kind: 'ok' })

const rows = ref([])
const total = ref(0)
const limit = ref(100)
const f = reactive({ Status: '', keyword: '', limit: 200 })

const stats = ref({ trend: { days: [], claimed: [], used: [] }, types: [], totals: {} })

const picked = ref(null)
const grantRows = ref([])
const grantLoading = ref(false)
const granting = ref(false)
const grantTo = ref('')

const c = reactive(blankCoupon())

function blankCoupon() {
  return {
    Name: '', Type: 'threshold', FaceValue: '', Threshold: '', TotalQty: '',
    Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '', EndAt: '',
  }
}

// ── 显示口径（本页自备，不抽公共模块 —— 同 `api/marketing.js` 的 `q()` 口径）──────────
const fmtInt = (v) => (v === null || v === undefined ? '0' : Number(v).toLocaleString('zh-CN'))
const fmtMoney = (v) =>
  Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function typeLabel(type) {
  const t = TYPES.find((x) => x.code === type)
  return t ? t.label : String(type || '—')
}

/** 面值的人话：折扣券读成「8.5 折」，另两类读成「减 ¥N」。 */
function faceLabel(row) {
  const v = Number(row.FaceValue || 0)
  if (row.Type === 'percent') return `${(v * 10).toFixed(1)} 折`
  return `减 ¥${fmtMoney(v)}`
}

const faceHint = computed(() => (c.Type === 'percent' ? '折扣率（0.85 = 8.5 折）*' : '面值（元）*'))
const facePlaceholder = computed(() => (c.Type === 'percent' ? '0.85' : '20'))

const truncated = computed(() => rows.value.length >= limit.value && total.value > rows.value.length)

const tzLabel = computed(() => {
  const pad = (n) => String(n).padStart(2, '0')
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

const trendDays = computed(() => stats.value.trend.days || [])
const trendClaimed = computed(() => stats.value.trend.claimed || [])
const trendUsed = computed(() => stats.value.trend.used || [])
const typePie = computed(() =>
  (stats.value.types || []).map((t) => ({ name: typeLabel(t.Type), value: Number(t.Cnt || 0) }))
)

/** 换类型时把不适用字段清掉 —— 留着「折扣券带门槛 100」会让后端报一条与本意无关的错。 */
function onTypeChange() {
  if (c.Type === 'none') {
    c.Threshold = ''
  }
}

function resetCreate() {
  Object.assign(c, blankCoupon())
}

function resetFilters() {
  f.Status = ''
  f.keyword = ''
  f.limit = 200
  loadList()
}

async function loadList() {
  loading.value = true
  try {
    const data = await couponList({ Status: f.Status, keyword: f.keyword, limit: f.limit })
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '券列表读取失败：' + ((data && data.msg) || '未知原因')
      return
    }
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total || 0)
    limit.value = Number(data.limit || 0)
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '券列表读取失败：' + ((e && e.message) || '未知原因')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  statsLoading.value = true
  statsFailed.value = false
  statsMsg.value = ''
  try {
    const data = await couponStats()
    if (!data || !data.success) {
      statsFailed.value = true
      statsMsg.value = (data && data.msg) || '统计读取失败'
      return
    }
    stats.value = {
      trend: data.trend || { days: [], claimed: [], used: [] },
      types: Array.isArray(data.types) ? data.types : [],
      totals: data.totals || {},
    }
  } catch (e) {
    statsFailed.value = true
    statsMsg.value = (e && e.message) || '统计读取失败'
  } finally {
    statsLoading.value = false
  }
}

async function doCreate() {
  creating.value = true
  flash.text = ''
  try {
    const data = await couponCreate({ ...c })
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '建券失败：' + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `建券成功：${data.CouponId}`
    resetCreate()
    await loadList()
    await loadStats()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '建券失败：' + ((e && e.message) || '未知原因')
  } finally {
    creating.value = false
  }
}

/** 停用 / 启用共用的收尾：报结果 → 重拉列表（**状态列要跟着变**）→ 重拉统计。 */
async function changeStatus(couponId, action, verb) {
  busy.value = couponId
  flash.text = ''
  try {
    const data = await action(couponId)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = `${verb}失败：` + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `${verb}成功：${couponId}`
    await loadList()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `${verb}失败：` + ((e && e.message) || '未知原因')
  } finally {
    busy.value = ''
  }
}

const doStop = (id) => changeStatus(id, couponStop, '停用')
const doActivate = (id) => changeStatus(id, couponActivate, '启用')

async function openGrants(couponId) {
  const row = rows.value.find((r) => r.CouponId === couponId) || null
  picked.value = row
  grantRows.value = []
  grantTo.value = ''
  grantLoading.value = true
  try {
    const data = await couponGrants(couponId, 200)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '明细读取失败：' + ((data && data.msg) || '未知原因')
      return
    }
    picked.value = data.row || row
    grantRows.value = Array.isArray(data.list) ? data.list : []
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '明细读取失败：' + ((e && e.message) || '未知原因')
  } finally {
    grantLoading.value = false
  }
}

function closeGrants() {
  picked.value = null
  grantRows.value = []
  grantTo.value = ''
}

async function doGrant() {
  granting.value = true
  flash.text = ''
  try {
    const data = await couponGrant(picked.value.CouponId, grantTo.value)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '发放失败：' + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `已发放给 ${grantTo.value}`
    grantTo.value = ''
    await openGrants(picked.value.CouponId)
    await loadList()
    await loadStats()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '发放失败：' + ((e && e.message) || '未知原因')
  } finally {
    granting.value = false
  }
}

onMounted(() => {
  loadList()
  loadStats()
})
</script>

<style scoped>
.a-mk {
  max-width: 1120px;
}

/* ── ① 状态条（照 `StatsPage.vue`，逐字同源）────────────────────────────── */
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

/* ── 表单（照 `StatsPage.vue`）──────────────────────────────────────────── */
.a-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
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
.a-f-narrow {
  flex: 0 1 130px;
  min-width: 110px;
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
.a-f input:disabled,
.a-f select:disabled {
  color: var(--ink-3);
  background: var(--surface-3);
  cursor: default;
}
.a-f-actions {
  display: flex;
  gap: var(--s2);
  margin-left: auto;
}

.a-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--s1);
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
.a-btn-sm {
  padding: var(--s1) var(--s2);
  font-size: var(--fs-xs);
}

.a-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.8;
  margin: var(--s3) 0 var(--s5);
}
.a-hint code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.a-note {
  font-size: var(--fs-xs);
  color: var(--warning-deep);
  margin: var(--s3) 0 var(--s2);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6) 0;
}

/* ── 分段标题（照 `StatsPage.vue` / `OrderPage.vue`）───────────────────── */
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
.a-mk > .a-h2:first-of-type {
  margin-top: 0;
}

/* ── 图卡 ───────────────────────────────────────────────────────────────── */
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4);
  margin-bottom: var(--s4);
}

/* ── 表 ─────────────────────────────────────────────────────────────────── */
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
  vertical-align: top;
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
.a-tbl .mono,
.a-mono {
  font-family: var(--font-mono);
}
.a-mini {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-ink {
  color: var(--ink);
  font-weight: var(--fw-med);
}
.a-ops {
  white-space: nowrap;
}
.a-ops .a-btn + .a-btn {
  margin-left: var(--s1);
}

/* 状态 chip —— 与 `AccountsPage.vue` / `NotifyPage.vue` 同语言（底色 + 前景同族）。 */
.a-chip {
  display: inline-block;
  font-size: var(--fs-xs);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.a-chip.ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.a-chip.off {
  color: var(--ink-3);
  background: var(--surface-3);
}
.a-chip.wait {
  color: var(--warning-deep);
  background: var(--warning-ground);
}
</style>
