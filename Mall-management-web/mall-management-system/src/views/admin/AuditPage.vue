<template>
  <section class="a-audit">
    <!-- ── ① 状态条 —— 「数字 → 状态」（`c3-modules.md` §五 的三招之一）────────────
         三个大数 + 时区口径。时区那一格是**刻意**摆到台面上的：本页唯一「错也看不出来」
         的地方就是它（格式永远正常、只差一个偏移），摆在顶上即可一眼核。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ total }}</b>
        <span>条记录{{ hasFilter ? '（筛选后）' : '' }}</span>
      </div>
      <div class="a-stat">
        <b>{{ rows.length }}</b>
        <span>本次显示</span>
      </div>
      <div class="a-stat">
        <b>{{ facets.actions.length }}</b>
        <span>动作种类</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">UTC</span>
        <span>库内时钟 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <!-- ── ② 筛选条（操作者 / 时间区间 / 动作 + 对象类型 / 上限）──────────────── -->
    <form class="a-filters" @submit.prevent="load">
      <label class="a-f">
        <span>动作</span>
        <select v-model="f.action" @change="load">
          <option value="">全部</option>
          <option v-for="a in facets.actions" :key="a" :value="a">{{ auditActionLabel(a) }}</option>
        </select>
      </label>
      <label class="a-f">
        <span>对象类型</span>
        <select v-model="f.targetType" @change="load">
          <option value="">全部</option>
          <option v-for="t in facets.targetTypes" :key="t" :value="t">{{ targetTypeLabel(t) }}</option>
        </select>
      </label>
      <label class="a-f">
        <span>操作者</span>
        <input v-model.trim="f.actorId" type="text" placeholder="业务号" @keyup.enter="load" />
      </label>
      <label class="a-f">
        <span>起（本地）</span>
        <input v-model="f.fromLocal" type="datetime-local" />
      </label>
      <label class="a-f">
        <span>止（本地）</span>
        <input v-model="f.toLocal" type="datetime-local" />
      </label>
      <label class="a-f a-f-narrow">
        <span>上限</span>
        <select v-model.number="f.limit" @change="load">
          <option v-for="n in [50, 100, 200, 500]" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="loading">
          <IconGlyph name="ok" />{{ loading ? '查询中…' : '查询' }}
        </button>
        <button type="button" class="a-btn" @click="reset">重置</button>
      </div>
    </form>
    <p class="a-hint">
      ⚠️ 起 / 止按<b>你浏览器所在时区</b>填；提交前会换算成库内时钟（UTC）再发 —— 见
      <code>toSqlTime</code>。
    </p>

    <p v-if="err" class="a-err">{{ err }}</p>
    <p v-else-if="truncated" class="a-note">
      已截断：按上限 <b>{{ limit }}</b> 条显示，共命中 <b>{{ total }}</b> 条 —— 收窄筛选，或把上限调大。
    </p>

    <!-- ── ③ 审计量趋势（可视化段 commit 3）────────────────────────────────────
         ⚠️ 数据源是**本次已取回的 `rows`**（受筛选条「上限」约束，≤ 500）—— 它不是
         「全库审计量趋势」，是**这一屏**按本地日历日的分布；`truncated` 为真时曲线只覆盖
         最近的那些条。⇒ 标题带「本次 N 条」，不冒充全量（同看板页对「区间」的处置）。
         ⚠️ 分桶用 `toLocal(...)` 的**本地**日期，不是切 `OccurredAt` 的前 10 字 —— 后者是
         UTC 日，东八区凌晨的记录会被算进前一天；本页的时区口径只有这一处，分桶沿它。 -->
    <h2 class="a-h2">审计量趋势 <span class="a-h2n">按本地日历日 · 本次 {{ rows.length }} 条 · 缺日补 0</span></h2>
    <div class="a-card">
      <LineChart
        :categories="trend.days"
        :series="[{ name: '审计条数', data: trend.counts, area: true }]"
        :height="'240px'"
        y-formatter="{value} 条"
        :loading="loading"
        :error="err"
        empty-text="本次没有可分桶的审计记录"
        @retry="load"
      />
    </div>

    <!-- ── ③b 动作类型分布（可视化段 commit 3；2026-10-11 复合图整改新增）────────────
         上面那张管**什么时候有事**，这张管**多的是哪类事、各是谁在动** —— 两张不重叠。
         ⚠️ x 轴是**动作域**不是日：本表只有几十条，按日铺开每根柱 0-2 条、整张图看着像坏的
            （见 `byDomain` 的注释）；日维度的「量」已由上面那张折线承担。
         ⚠️ 同样只覆盖**本次已取的 `rows`**（≤「上限」那档），标题照实写「本次 N 条」。 -->
    <h2 class="a-h2">
      动作类型分布
      <span class="a-h2n">按动作域 · 按操作者角色分组 · 本次 {{ rows.length }} 条</span>
    </h2>
    <div class="a-card">
      <BarChart
        :categories="byDomain.categories"
        :series="byDomain.series"
        :height="'240px'"
        value-formatter="{value} 条"
        :max-bar-width="18"
        :loading="loading"
        :error="err"
        empty-text="本次没有可分域的审计记录"
        @retry="load"
      />
    </div>

    <!-- ── ④ 事件流 —— 「表格 → 叙事」：一条一条讲，不是一张宽表 ──────────────
         ⚠️ `c3-modules.md` §五 的原型是「主视图区 + 右侧事件流栏」。本页**有意不分两栏**：
            这里**事件流本身就是主视图**（审计页没有「主视图」这回事），再切一条右边栏
            等于把唯一的内容挤到 60% 宽。§五 说的是**可观测域的联动页**（指标 / 状态流转），
            那里才需要「主图 + 侧流」。 -->
    <ol v-if="rows.length" class="a-stream">
      <li v-for="r in rows" :key="r.Id" class="a-ev" :class="{ on: openId === r.Id }">
        <button type="button" class="a-ev-head" @click="toggle(r)">
          <span class="a-ev-hue" :style="{ background: hue(r.Action) }"></span>
          <span class="a-ev-main">
            <span class="a-ev-l1">
              <code class="a-tag" :title="r.Action">{{ auditActionLabel(r.Action) }}</code>
              <span class="a-ev-target">{{ targetTypeLabel(r.TargetType) }} · #{{ r.TargetId }}</span>
            </span>
            <span class="a-ev-l2">
              <span class="a-who-chip">{{ r.ActorId || '（认不出身份）' }}</span>
              <span class="a-dim">角色 {{ roleLabel(r.ActorRole) }}</span>
            </span>
          </span>
          <span class="a-ev-time">
            <b>{{ toLocal(r.OccurredAt) }}</b>
            <span class="a-dim">库内 {{ r.OccurredAt }}</span>
          </span>
          <span class="a-ev-caret">{{ openId === r.Id ? '▾' : '▸' }}</span>
        </button>

        <div v-if="openId === r.Id" class="a-ev-body">
          <p class="a-dim2 a-src">
            详情来自 <code>/admin/audit/detail</code>（id={{ r.Id }}）
            <template v-if="detailLoading">· 载入中…</template>
            <template v-else-if="detailErr">· ⚠️ {{ detailErr }} —— 退回用**列表行**的快照</template>
            <template v-else-if="detailOf === r.Id">· ✅ 已取到</template>
          </p>

          <div class="a-diff-head">
            <span class="a-dim2">改前 / 改后（只高亮变化的列）</span>
            <label class="a-only">
              <input v-model="onlyChanged" type="checkbox" /> 只看差异
            </label>
          </div>

          <table class="a-diff">
            <thead>
              <tr><th>列</th><th>改前</th><th>改后</th></tr>
            </thead>
            <tbody>
              <tr v-for="d in visibleDiff" :key="d.key" :class="{ chg: d.changed }">
                <td class="a-k">{{ d.key }}</td>
                <td class="a-v">{{ d.before }}</td>
                <td class="a-v">{{ d.after }}</td>
              </tr>
              <tr v-if="!visibleDiff.length">
                <td colspan="3" class="a-dim2">{{ onlyChanged ? '（无差异）' : '（快照为空）' }}</td>
              </tr>
            </tbody>
          </table>

          <details class="a-raw">
            <summary>原始 JSON</summary>
            <pre>BeforeJson：{{ openRow.BeforeJson || 'null' }}</pre>
            <pre>AfterJson&nbsp;：{{ openRow.AfterJson || 'null' }}</pre>
          </details>
        </div>
      </li>
    </ol>
    <p v-else-if="!loading && !err" class="a-empty">没有命中任何审计记录。</p>
  </section>
</template>

<!--
  权限与审计页（C3 批 A，2026-10-08）—— `/admin/observe/audit` 的真页。

  它是 **12 条占位里第一个换成真页的**（原走 `StubPage.vue`）—— 正是那个组件块首预告的
  「等某一块真拿到内容时再单独拆」。名单见 `router/index.js` 的 `children`。

  【它消费后端 3 个只读端点】`/admin/audit/list` · `/detail` · `/facets`
  （`AdminAuditController`）。写入口**不经 HTTP**：审计行只能由系统在各业务端点挂钩处追加。

  【时区 —— 本页的硬要求，也是唯一「错也看不出来」的地方】
  后端 `OccurredAt` 是 `DATE_FORMAT(OccurredAt, '%Y-%m-%d %H:%i:%s')` 的**文本**，没有时区标记；
  它是**库内时钟**（VM / a3 上是 **UTC**，全仓 `NOW()` 一致）。故：
    · **显示** 库内文本 → 当 UTC 解析 → 按**浏览器时区**渲染（`toLocal`）；
    · **筛选** 用户按**本地时区**挑的时刻 → 反算成 UTC 文本再发（`toSqlTime`）。
  ⚠️ **不改后端写入**（用户 2026-10-08 裁决：库时钟 UTC 是对的，根因是「前端展示没转时区」）。
  ⚠️ 已知边界【**推断，未实测**】：**本地开发库**（`application.properties` 那套，非 VM）的时钟
    应是机器本地时间、不是 UTC ⇒ 在本机开发库上看这张表，显示会**再差一个偏移**。这是环境差异，
    不是本页的 bug；真要判「显示对不对」，拿 VM / a3 那份看。
    「库时钟 = UTC」这条本身有记录：`docs/agent/context-packs/c3-block-1-infra.md#pack-c3-block-1-infra-frontend-nav`。

  【视觉】按 `c3-modules.md` §五「可观测域视觉化原则」：数字→状态（顶部三个大数）、
  表格→叙事（事件流卡片，不是宽表）、异常高亮（变化的列高亮）。布局**有意不分两栏**，理由见模板里那条注释。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { auditDetail, auditFacets, auditList } from '../../api/admin'
import LineChart from '../../components/charts/LineChart.vue'
// 2026-10-11 复合图整改新增：趋势折线管「什么时候有事」，下面那张分组柱管「多的是哪类事」。
import BarChart from '../../components/charts/BarChart.vue'; import IconGlyph from '../../components/ui/IconGlyph.vue'; import { auditActionLabel, targetTypeLabel, TARGET_TYPE_LABEL } from '../../config/labels/audit-labels'; import { roleLabel } from '../../config/labels/user-labels'   // ⚠️ 这几条 import **同行**（2026-10-11 补图标那一笔起、commit 9 又加了两条标签层）—— 合并到既有行 = 不新增行号漂移（本页 `:72-75`/`:125-184`/`:441-469` 被 `c3-block-2-user.md:76-77`、`c3-block-3-goods.md:106`、`c3-block-4-order.md:112` 引 —— **原 82-141 / 301-329 行**，C1 那几笔已推过一次、2026-10-11 收口按 refs-scan 再推一次）

// ── 时区换算（两个方向必须对称，否则「筛出来的」和「显示出来的」对不上）──────────
const pad = (n) => String(n).padStart(2, '0')

/** 浏览器时区偏移标签，形如 `UTC+08:00`。 */
const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

/**
 * 展示向：库内时钟文本（`'YYYY-MM-DD HH:MM:SS'`，UTC）→ 浏览器本地时间。
 *
 * ⚠️ 末尾那个 `Z` **不能省**。省掉它，`Date` 会按**本地**时区解析这串数字 ——
 *    等于把 UTC 值当成本地值，显示上整整差一个偏移（东八区差 8 小时），
 *    而且**错得很安静**：格式完全正常，肉眼看不出来。本页唯一的暗礁就是它。
 */
function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/**
 * 筛选向：`<input type="datetime-local">` 的值（`'YYYY-MM-DDTHH:MM'`，**本地时区**语义）
 * → 库内时钟文本（UTC）。
 *
 * 起补 `:00`、止补 `:59`（用户挑到哪一分，就把那一分查满）。
 * ⚠️ 函数名 `toSqlTime` 是被后端点名的：`AdminAuditController#list` 的 javadoc 写着
 *    「前端 … 需自行补秒 —— 见 `AuditPage.vue` 的 `toSqlTime`」。改名要同时改那边。
 */
function toSqlTime(localValue, isEnd) {
  if (!localValue) return ''
  const d = new Date(localValue) // 无偏移的 date-time ⇒ 按**本地**时区解析（ES2016+）
  if (Number.isNaN(d.getTime())) return ''
  d.setSeconds(isEnd ? 59 : 0, 0)
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  )
}

// ── 快照 diff ────────────────────────────────────────────────────────────────
/**
 * JSON 文本 → 待 diff 的对象；坏值原样兜住（不因为一条脏数据把整页弄白）。
 *
 * ⚠️ **单元素数组必须拆开**：后端把快照存成**数组**（一次审计可能涉及多行，单行 = 单元素数组 ——
 *    实测 `goods.update` 的 `BeforeJson` 逐字是 `[{"Id":20,"Price":5556.0,…}]`）。
 *    不拆的话 `diffRows` 只会得到**一个**键 `"0"`，值是两个整段 JSON ⇒ 显示成
 *    「0: [整段] → [整段]」：技术上确实「变了」，但**看不到改的是哪个字段** —— diff 视图就白做了。
 *    多元素数组**不拆**（那是批量写，逐元素比对没有单行那种「同一实体的改前改后」语义）。
 */
function parseJson(text) {
  if (!text) return null
  try {
    const v = JSON.parse(text)
    if (Array.isArray(v) && v.length === 1 && v[0] && typeof v[0] === 'object') return v[0]
    return v
  } catch (e) {
    return { __raw: String(text) }
  }
}

const show = (v) => {
  if (v === undefined || v === null) return '—'
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

/**
 * 两份快照 → 逐列对照表。**变化列排前面**（叙事顺序：先看改了什么，再看没变的）。
 * 键的并集取自两侧 —— 新增行（改前为 `null`）也走这里。
 */
function diffRows(beforeJson, afterJson) {
  const b = parseJson(beforeJson) || {}
  const a = parseJson(afterJson) || {}
  const keys = Array.from(new Set([...Object.keys(b), ...Object.keys(a)]))
  return keys
    .map((k) => {
      const bv = show(b[k])
      const av = show(a[k])
      return { key: k, before: bv, after: av, changed: bv !== av }
    })
    .sort((x, y) => Number(y.changed) - Number(x.changed) || x.key.localeCompare(y.key))
}

/** 动作 tag → 一个稳定的色板下标（同一种动作永远同色，便于扫读）。 */
function hue(key) {
  const s = String(key || '')
  let h = 0
  for (let i = 0; i < s.length; i += 1) h = (h * 31 + s.charCodeAt(i)) % 997
  return `var(--c${(h % 10) + 1})`
}

// ── 状态 ─────────────────────────────────────────────────────────────────────
const f = reactive({ action: '', targetType: '', actorId: '', fromLocal: '', toLocal: '', limit: 100 })
const rows = ref([])
const total = ref(0)
const limit = ref(100)
const facets = reactive({ actions: [], targetTypes: [] })
const loading = ref(false)
const err = ref('')

const openId = ref(null)
const detailRow = ref(null)
const detailOf = ref(null)
const detailLoading = ref(false)
const detailErr = ref('')
const onlyChanged = ref(true)

const hasFilter = computed(
  () => !!(f.action || f.targetType || f.actorId || f.fromLocal || f.toLocal),
)
const truncated = computed(() => total.value > rows.value.length)

/** 展开行：**优先用 detail 端点的返回**，取不到才退回列表行（列表行带同一批列）。 */
const openRow = computed(() => {
  if (openId.value == null) return {}
  if (detailOf.value === openId.value && detailRow.value) return detailRow.value
  return rows.value.find((r) => r.Id === openId.value) || {}
})
const diff = computed(() => diffRows(openRow.value.BeforeJson, openRow.value.AfterJson))
const visibleDiff = computed(() => (onlyChanged.value ? diff.value.filter((d) => d.changed) : diff.value))

// ── 审计量趋势（可视化段 commit 3）────────────────────────────────────────────
/**
 * 本次取回的审计行 → **按本地日历日**分桶的连续日期序列。
 *
 * ⚠️ 覆盖范围只有**本次已取的 `rows`**（≤「上限」那档，最多 500），不是全库 ——
 *    `truncated` 为真时曲线只画最近的那些。这一点写在图标题里，不在图上找补。
 * ⚠️ 分桶沿 `toLocal`（库内 UTC → 浏览器时区）再取前 10 字，**不是**直接切 `OccurredAt`：
 *    后者是 UTC 日，东八区凌晨的记录会被算到前一天，与页面别处显示的时刻对不上。
 * ⚠️ 首尾之间缺的日**补 0**：折线的 x 轴必须等距，否则「10-01 直接连到 10-05」会被读成
 *    那几天一直有量。跨度 > 180 天时不补（那不是趋势，是点阵）。
 */
const trend = computed(() => {
  const byDay = new Map()
  for (const r of rows.value) {
    const day = toLocal(r.OccurredAt).slice(0, 10)
    if (/^\d{4}-\d{2}-\d{2}$/.test(day)) byDay.set(day, (byDay.get(day) || 0) + 1)
  }
  const keys = [...byDay.keys()].sort()
  if (!keys.length) return { days: [], counts: [] }
  const start = new Date(`${keys[0]}T00:00:00Z`)
  const span = Math.round((new Date(`${keys[keys.length - 1]}T00:00:00Z`) - start) / 86400000)
  if (span > 180) return { days: keys, counts: keys.map((d) => byDay.get(d)) }
  const days = []
  for (let i = 0; i <= span; i += 1) {
    days.push(new Date(start.getTime() + i * 86400000).toISOString().slice(0, 10))
  }
  return { days, counts: days.map((d) => byDay.get(d) || 0) }
})

// ── 动作类型分布（可视化段 commit 3；2026-10-11 复合图整改新增）────────────────────
/** 动作域中文名 —— 取值取自 `config/labels/audit-labels.js` 的 `TARGET_TYPE_LABEL`（中文表唯一出处）。 */
const DOMAIN_LABEL = {
  order: TARGET_TYPE_LABEL.order,
  goods: TARGET_TYPE_LABEL.goods,
  goodstype: TARGET_TYPE_LABEL.goodstype,
  merchant: TARGET_TYPE_LABEL.merchant,
  user: TARGET_TYPE_LABEL.user,
  template: TARGET_TYPE_LABEL.template,
  goods_template: TARGET_TYPE_LABEL.goods_template, // 与 `template` 同归一类：对读者是同一件事（改模板）
  notification: TARGET_TYPE_LABEL.notification,
}
/** `ActorRole` 中文名 —— 值取自 `config/labels/user-labels.js` 的 `roleLabel()`（唯一出处）；本页留这层映射壳只为把认不出的码并成「未识别」（`roleLabel` 自己的兜底是回码原文，那一侧是对的）。 */
const ROLE_LABEL = { 1: roleLabel(1), 2: roleLabel(2), 3: roleLabel(3) }
/** 图例的固定画序 —— 管理员在前（本页是管理端审计），「未识别」垫底。 */
const ROLE_ORDER = ['管理员', '商家', '客户', '未识别']

/**
 * 本次取回的审计行 → **分组柱**数据：x 轴 = 动作域，每个域里按**操作者角色**并排。
 *
 * ⚠️ 为什么 x 轴取「动作域」而不是像订单页那样取「日」：本表是**几十条 / 30-60 天**的量级，
 *    按日铺开每根柱平均 1 条出头、大片日子是 0，分组柱会稀成点阵、看着像画坏了；
 *    而域只有 7-8 个，分组柱一眼读得出「哪类多」。日维度的「量」由上面那张折线承担，
 *    两张图分工不重叠（这也是《看板规范》「一张图最多 2-3 个指标」的落地：此处 3 个角色）。
 * ⚠️ `ActorRole` 为空 = **认不出身份**（建表注释：会话里取不到业务号）—— **不丢弃**、
 *    单列一条「未识别」。审计页把「多少条认不出人」藏起来才是真的危险；没有这种行时
 *    这一条**不出现**（不会留一条恒 0 的空图例）。
 * ⚠️ 域 > 8 类时只留前 7 + 「其他」合并段：再多就回归成一片糊掉的柱林。
 * ⚠️ 覆盖范围同样只有**本次已取的 `rows`**（≤「上限」那档，最多 500），不是全库。
 */
const byDomain = computed(() => {
  const grid = new Map() // 域 → 角色 → 条数
  for (const r of rows.value) {
    const dom = DOMAIN_LABEL[String(r.Action || '').split('.')[0]] || '其他'
    const role = ROLE_LABEL[r.ActorRole] || '未识别'
    if (!grid.has(dom)) grid.set(dom, new Map())
    const g = grid.get(dom)
    g.set(role, (g.get(role) || 0) + 1)
  }
  if (!grid.size) return { categories: [], series: [] }
  const total = (g) => [...g.values()].reduce((a, b) => a + b, 0)
  let doms = [...grid.entries()]
    .map(([d, g]) => [d, g, total(g)])
    .sort((a, b) => b[2] - a[2])
  if (doms.length > 8) {
    const tail = doms.slice(7)
    doms = doms.slice(0, 7)
    // 已经有一个「其他」（未知前缀）就并进去，别出来两个同名类目。
    let bucket = doms.find(([d]) => d === '其他')
    if (!bucket) {
      bucket = ['其他', new Map(), 0]
      doms.push(bucket)
    }
    for (const [, g] of tail) {
      for (const [role, n] of g) bucket[1].set(role, (bucket[1].get(role) || 0) + n)
    }
  }
  return {
    categories: doms.map(([d]) => d),
    series: ROLE_ORDER
      .filter((role) => doms.some(([, g]) => (g.get(role) || 0) > 0))
      .map((role) => ({ name: role, data: doms.map(([, g]) => g.get(role) || 0) })),
  }
})

// ── 取数 ─────────────────────────────────────────────────────────────────────
async function load() {
  loading.value = true
  err.value = ''
  openId.value = null
  detailRow.value = null
  detailOf.value = null
  try {
    const data = await auditList({
      action: f.action,
      targetType: f.targetType,
      actorId: f.actorId,
      from: toSqlTime(f.fromLocal, false),
      to: toSqlTime(f.toLocal, true),
      limit: f.limit,
    })
    rows.value = data.list || []
    total.value = data.total == null ? 0 : data.total
    // ⚠️ 用**后端回显**的生效上限，不用本地 `f.limit` —— 请求 > 500 时后端夹到 500，
    //    页面据实提示「已截断」，而不是骗自己发了多少。
    limit.value = data.limit == null ? f.limit : data.limit
  } catch (e) {
    rows.value = []
    total.value = 0
    err.value = `审计列表加载失败：${e && e.status ? `HTTP ${e.status}` : (e && e.message) || e}`
    console.error('audit list', e)
  } finally {
    loading.value = false
  }
}

async function loadFacets() {
  try {
    const data = await auditFacets()
    facets.actions = data.actions || []
    facets.targetTypes = data.targetTypes || []
  } catch (e) {
    // 筛选面拿不到不拦路：下拉为空、正文仍可查（只是个降级，不是错误）
    console.error('audit facets', e)
  }
}

async function toggle(r) {
  if (openId.value === r.Id) {
    openId.value = null
    return
  }
  openId.value = r.Id
  detailRow.value = null
  detailOf.value = null
  detailErr.value = ''
  detailLoading.value = true
  try {
    const data = await auditDetail(r.Id)
    if (data && data.success) {
      detailRow.value = data.row
      detailOf.value = r.Id
    } else {
      detailErr.value = (data && data.msg) || 'success=false'
    }
  } catch (e) {
    detailErr.value = e && e.status ? `HTTP ${e.status}` : (e && e.message) || '请求失败'
    console.error('audit detail', e)
  } finally {
    detailLoading.value = false
  }
}

function reset() {
  f.action = ''
  f.targetType = ''
  f.actorId = ''
  f.fromLocal = ''
  f.toLocal = ''
  f.limit = 100
  load()
}

onMounted(() => {
  loadFacets()
  load()
})
</script>

<style scoped>
.a-audit {
  max-width: 1120px;
}

/* ── ① 状态条 ─────────────────────────────────────────────────────────────── */
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
/* 时区那一格推到右端 —— 它是「口径声明」，不是第四个统计数 */
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

/* ── ② 筛选条 ─────────────────────────────────────────────────────────────── */
.a-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  padding: var(--s4) var(--s5);
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
.a-f-narrow {
  max-width: 88px;
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
.a-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  cursor: pointer;
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
  margin: var(--s2) 0 var(--s5);
}
.a-err {
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-note {
  font-size: var(--fs-xs);
  color: var(--warning);
  background: var(--warning-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6);
  text-align: center;
}

/* ── ③ 事件流 ─────────────────────────────────────────────────────────────── */
.a-stream {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.a-ev {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  overflow: hidden;
}
.a-ev.on {
  border-color: var(--brand-ink);
}
.a-ev-head {
  display: flex;
  align-items: center;
  gap: var(--s4);
  width: 100%;
  font: inherit;
  text-align: left;
  background: none;
  border: 0;
  padding: var(--s3) var(--s4);
  cursor: pointer;
  /* ⚠️ hover **只改底色**，不加左侧强调条 —— `tokens.css` 块首抄下来的设计稿规矩。 */
  color: inherit;
}
.a-ev-head:hover {
  background: var(--surface-2);
}
/* 左侧色条 = 动作的稳定配色（同动作永远同色） */
.a-ev-hue {
  flex: none;
  width: 3px;
  align-self: stretch;
  border-radius: var(--r1);
}
.a-ev-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s1);
}
.a-ev-l1 {
  display: flex;
  align-items: center;
  gap: var(--s3);
  min-width: 0;
}
.a-tag {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border-radius: var(--r1);
  padding: 2px 6px;
}
.a-ev-target {
  font-size: var(--fs-sm);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-ev-l2 {
  display: flex;
  align-items: center;
  gap: var(--s3);
}
.a-who-chip {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.a-ev-time {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.a-ev-time b {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.a-dim {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-dim2 {
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.a-ev-caret {
  flex: none;
  color: var(--ink-3);
}

.a-ev-body {
  border-top: 1px solid var(--line);
  padding: var(--s4);
  background: var(--surface-2);
}
.a-src {
  margin-bottom: var(--s3);
}
.a-src code {
  font-family: var(--font-mono);
  color: var(--ink);
}
.a-diff-head {
  display: flex;
  align-items: center;
  gap: var(--s4);
  margin-bottom: var(--s2);
}
.a-only {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  cursor: pointer;
  margin-left: auto;
}
.a-diff {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.a-diff th {
  text-align: left;
  font-weight: var(--fw-med);
  color: var(--ink-3);
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
}
.a-diff td {
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
  vertical-align: top;
  color: var(--ink-2);
  word-break: break-all;
}
.a-diff tr:last-child td {
  border-bottom: 0;
}
.a-k {
  font-family: var(--font-mono);
  color: var(--ink-3);
  white-space: nowrap;
}
.a-v {
  font-family: var(--font-mono);
}
/* 变化列高亮 —— §五「异常高亮」 */
.a-diff tr.chg td {
  color: var(--ink);
  background: var(--warning-ground);
}
.a-diff tr.chg .a-k {
  color: var(--warning-deep);
}

.a-raw {
  margin-top: var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.a-raw summary {
  cursor: pointer;
}
.a-raw pre {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
  margin-top: var(--s2);
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ── 分段标题 / 图表卡（可视化段 commit 3）─────────────────────────────────────
   本页原无这两类。定义成与 `AccountsPage` / `OrderPage` 的 `.a-h2` / `.a-card`
   **同款**（同前缀同值），三页的管理端小节标题才长得一样 —— 不要在这里另立一套。 */
.a-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin: var(--s7) 0 var(--s4);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
}
</style>
