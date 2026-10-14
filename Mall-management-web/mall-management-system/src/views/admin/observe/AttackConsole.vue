<template>
  <section class="a-ac">
    <!-- ── ① 状态条 —— 与 `AuditPage.vue` / `StatsPage.vue` 同款（同域、最近邻），不另立一套 ──
         四个数都是「这一页自己能不能用」的判据（场景取到没有 / 勾了几个 / 有没有记录 / 权重合计
         是多少），不是业务读数 —— 业务读数在下面每一条记录里。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ scenarios.length }}</b>
        <span>预置场景</span>
      </div>
      <div class="a-stat">
        <b>{{ picked.length }}</b>
        <span>本次勾选</span>
      </div>
      <div class="a-stat">
        <b>{{ weightSum }}</b>
        <span>权重合计</span>
      </div>
      <div class="a-stat">
        <b>{{ total }}</b>
        <span>演练记录</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">库时钟</span>
        <span>记录时间 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── ② 固定一键：8 个预置场景，各一键 ──────────────────────────────────────
         用**当前**并发 / 持续秒数跑**这一个**场景（权重 100）。与下面「自定义」的区别只有两处：
         勾选固定成一个场景、参数仍取面板上的值 —— 这样「先把并发调到 300 再点秒杀风暴」也成立。 -->
    <h2 class="a-h2">
      固定一键
      <span class="a-h2n">点一下 = 单独跑这一个场景（按下面的并发 / 持续秒数）</span>
    </h2>

    <p v-if="!scenarios.length" class="a-empty">
      {{ loadingScenarios ? '正在取场景清单…' : '场景清单没取到 —— 见上方提示。' }}
    </p>
    <div v-else class="a-presets">
      <button
        v-for="s in scenarios"
        :key="s.Key"
        type="button"
        class="a-preset"
        :disabled="running"
        @click="runPreset(s)"
      >
        <span class="a-preset-n"><IconGlyph name="attack" tone="muted" />{{ s.Name }}</span>
        <span class="a-preset-p">{{ s.Protections }}</span>
        <span class="a-preset-w">缺省权重 {{ s.DefaultWeight }}%</span>
      </button>
    </div>

    <!-- ── ③ 自定义编排：8 根权重滑条 + 总并发 + 持续秒数 + 开始 ───────────────── -->
    <h2 class="a-h2">
      自定义编排
      <span class="a-h2n">权重定的是「分几个线程」，不是「百分之几的请求」· 每场景保底 1 个线程</span>
    </h2>
    <div class="a-card">
      <div class="a-sims">
        <WeightSlider
          v-for="s in scenarios"
          :key="s.Key"
          :label="s.Name"
          :hint="s.Protections"
          :need="s.Needs"
          :model-value="weightOf(s.Key)"
          :disabled="running"
          @update:model-value="(v) => setWeight(s.Key, v)"
        />
      </div>

      <div class="a-run">
        <label class="a-f a-f-narrow">
          <span>总并发数</span>
          <input v-model.number="concurrency" type="number" :min="CONC_MIN" :max="CONC_MAX" :disabled="running" />
        </label>
        <label class="a-f a-f-narrow">
          <span>持续（秒）</span>
          <input v-model.number="durationSeconds" type="number" :min="DUR_MIN" :max="DUR_MAX" :disabled="running" />
        </label>
        <p class="a-run-note">
          子场景数与并发的关系是后端判的：<b>并发数 &lt; 勾选场景数 ⇒ 400</b>（不悄悄少跑几个）。
        </p>
        <button
          type="button"
          class="a-btn a-btn-on"
          :disabled="running || !canRun"
          @click="runCustom"
        >
          <IconGlyph name="ok" />{{ running ? '演练中…' : '开始' }}
        </button>
      </div>

      <p v-if="blockReason && !running" class="a-warn">{{ blockReason }}</p>

      <!-- 进度条：**没有后端进度可查**（`/run` 是同步请求，跑完才回）⇒ 按「已用 / 预计」估。
           ⚠️ 不写成「实时进度」：页面不知道后端跑到哪一步，装作知道就是编数据。 -->
      <div v-if="running" class="a-prog">
        <div class="a-prog-bar"><i :style="{ width: pct + '%' }"></i></div>
        <p class="a-prog-t">
          已等 <b>{{ elapsedSec }}</b> 秒 / 预计 <b>{{ plannedSeconds }}</b> 秒
          —— 后端**跑完才回**，这条进度条是按已用时间估的，不是后端读数（另加 5 秒 Redis 断开阶段）。
        </p>
      </div>
    </div>

    <!-- ── ④ 演练记录（`drill_record` 时间倒序）─────────────────────────────── -->
    <h2 class="a-h2">
      演练记录
      <span class="a-h2n">时间倒序 · 共 {{ total }} 条</span>
    </h2>

    <form class="a-filters" @submit.prevent="loadRecords">
      <label class="a-f a-f-narrow">
        <span>上限</span>
        <select v-model.number="f.limit" :disabled="running" @change="loadRecords">
          <option v-for="n in [10, 20, 50, 100, 200]" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn" :disabled="loadingRecords">
          <IconGlyph name="ok" />{{ loadingRecords ? '载入中…' : '刷新' }}
        </button>
      </div>
    </form>

    <p v-if="recErr" class="a-err">{{ recErr }}</p>
    <p v-else-if="truncated" class="a-note">
      已截断：按上限 <b>{{ recLimit }}</b> 条显示，共命中 <b>{{ total }}</b> 条 —— 把上限调大。
    </p>

    <ol v-if="records.length" class="a-stream">
      <li v-for="r in records" :key="r.Id" class="a-ev" :class="{ on: openId === r.Id }">
        <button type="button" class="a-ev-head" @click="toggle(r)">
          <span class="a-ev-main">
            <span class="a-ev-l1">
              <code class="a-tag">{{ r.Name || '（未命名）' }}</code>
              <span class="a-ev-target">
                总 {{ num(r.TotalRequests) }} · 拦下 {{ num(r.BlockedRequests) }} · 失败 {{ num(r.FailedRequests) }}
              </span>
            </span>
            <span class="a-ev-l2">
              <span class="a-who-chip">{{ r.OperatorId || '（认不出身份）' }}</span>
              <span class="a-dim">角色 {{ r.OperatorRole == null ? '—' : r.OperatorRole }}</span>
              <span class="a-dim">耗时 {{ fmtMs(r.DurationMs) }}</span>
            </span>
          </span>
          <span class="a-ev-time">
            <b>{{ toLocal(r.CreatedAt) }}</b>
            <span class="a-dim">库内 {{ r.CreatedAt }}</span>
          </span>
          <span class="a-ev-caret">{{ openId === r.Id ? '▾' : '▸' }}</span>
        </button>

        <div v-if="openId === r.Id" class="a-ev-body">
          <!-- ⚠️ `Result` 为 null 只可能是**后端解析失败**（`DrillRecordService.split` 那一条：
               解析不了就把原串挂 `ResultRaw` + 标 `ResultParseError`）。这一支**单独画**，
               不并进下面那条正常渲染 —— 否则一条坏行会画成一个「0 请求 0 拦下」的成功演练。 -->
          <p v-if="r.Result == null" class="a-corrupt">
            ⚠️ 这条记录的结果读不出来：<code>{{ r.ResultParseError || 'Result 为空' }}</code>
            <template v-if="r.ResultRaw"> —— 原串：<code class="a-rawwire">{{ r.ResultRaw }}</code></template>
          </p>

          <template v-else>
            <div class="a-res">
              <div class="a-res-cell"><span>总请求</span><b>{{ num(r.TotalRequests) }}</b></div>
              <div class="a-res-cell"><span>拦下</span><b class="a-ok">{{ num(r.BlockedRequests) }}</b></div>
              <div class="a-res-cell"><span>失败</span><b :class="{ 'a-bad': Number(r.FailedRequests) > 0 }">{{ num(r.FailedRequests) }}</b></div>
              <div class="a-res-cell"><span>耗时</span><b>{{ fmtMs(r.DurationMs) }}</b></div>
            </div>

            <h3 class="a-sub">场景配置</h3>
            <p class="a-kv">
              并发 <b>{{ cfgOf(r).c }}</b> · 持续 <b>{{ cfgOf(r).d }}</b> 秒 · 勾选
              <template v-if="cfgOf(r).s.length">
                <code v-for="x in cfgOf(r).s" :key="x.k" class="a-chip">{{ x.k }}={{ x.w }}%</code>
              </template>
              <template v-else>—（记录里没有勾选清单）</template>
            </p>

            <h3 class="a-sub">各场景读数</h3>
            <table class="a-tbl">
              <thead>
                <tr><th>场景</th><th>请求</th><th>放行</th><th>拦下</th><th>失败</th></tr>
              </thead>
              <tbody>
                <tr v-for="p in listOf(r, 'Scenarios')" :key="p.Key">
                  <td class="a-tk">
                    <b>{{ p.Name }}</b>
                    <span class="a-dim2">{{ p.Protections }}</span>
                  </td>
                  <td>{{ num(p.Requests) }}</td>
                  <td>{{ num(p.Passed) }}</td>
                  <td class="a-ok">{{ num(p.Blocked) }}</td>
                  <td :class="{ 'a-bad': Number(p.Failed) > 0 }">{{ num(p.Failed) }}</td>
                </tr>
                <tr v-if="!listOf(r, 'Scenarios').length">
                  <td colspan="5" class="a-dim2">（这一轮没有分场景读数）</td>
                </tr>
              </tbody>
            </table>

            <!-- 库存：秒杀场景的判据**在库存上**，不在「拦下」上 —— 这一点写进页面，
                 否则读数会被读反（`deductStocks` 是 fail-open，超卖那部分照常 success:true）。 -->
            <h3 class="a-sub">库存（秒杀场景的判据）</h3>
            <p class="a-kv">
              <code>{{ stockOf(r).SkuCode || '—' }}</code> ·
              演练前 <b>{{ dash(stockOf(r).Before) }}</b> → 演练后 <b>{{ dash(stockOf(r).After) }}</b>
              <template v-if="stockOf(r).Deducted != null">（扣了 <b>{{ stockOf(r).Deducted }}</b>）</template>
              · 基线 <b>{{ dash(stockOf(r).Baseline) }}</b>
              · 秒杀放行单 <b>{{ num(stockOf(r).SeckillOrders) }}</b>
            </p>

            <h3 class="a-sub">Redis 断开（阶段 B）</h3>
            <p class="a-kv">
              <template v-if="rdOf(r).Ran">
                单独跑了 <b>{{ rdOf(r).Seconds }}</b> 秒 ·
                <template v-if="rdOf(r).Injected === true">
                  已注入故障、<template v-if="rdOf(r).Restored">已恢复</template><template v-else>未恢复</template>
                </template>
                <template v-else-if="rdOf(r).Injected === false">未注入（{{ rdOf(r).Note }}）</template>
                <template v-else>注入状态未记</template>
              </template>
              <template v-else>这一轮没勾选 Redis 断开。</template>
            </p>

            <h3 class="a-sub">攻击数据标记</h3>
            <p class="a-kv">
              订单业务串 <code>{{ mkOf(r).OrderIdPrefix || '—' }}</code> ·
              评论正文 <code>{{ mkOf(r).CommentContentPrefix || '—' }}</code> ·
              本轮序号 <b>{{ num(mkOf(r).RunSeq) }}</b>
              <template v-if="samplesOf(r).length">
                · 样本 <code>{{ samplesOf(r).join(' ') }}</code>
              </template>
            </p>

            <h3 class="a-sub">备注</h3>
            <ul class="a-notes">
              <li v-for="(n, i) in listOf(r, 'Notes')" :key="i">{{ n }}</li>
              <li v-if="!listOf(r, 'Notes').length" class="a-dim2">（这一轮没有备注）</li>
            </ul>
          </template>
        </div>
      </li>
    </ol>
    <p v-else-if="!loadingRecords && !recErr" class="a-empty">还没有任何演练记录。</p>
  </section>
</template>

<!--
  攻击模拟器页（块 7 · commit 5）—— `/admin/observe/attack-sim` 的真页（原走 `StubPage.vue`）。

  【它是块 7 四页里的第一页，也是**唯一不画图**的一页】
  《块7参考》§1.1 逐字：「攻击页 = AttackConsole.vue，只做**编排 + 跑 + 演练记录**；
  三面板已独立成页（D2 后期改判），不塞进攻击页」。⇒ 本页**不 import 任何图表组件**
  （`components/charts/*` 一个都不引），指标 / 状态流转 / 日志分别是 commit 6–8 的另外三页。
  ⚠️ 那条「不塞三面板」是**用户 2026-10-13 的改判**（原 D2 = 三面板同屏当总控台）；
     别按更早的方案把它合回去。

  【它消费后端 3 个端点，全部经 `api/observe.js`】
    · `attackScenarios()` —— 8 个预置场景（Key / Name / Protections / Needs / DefaultWeight）；
    · `runAttack(p)`      —— 跑一轮（**同步跑到结束**，见下）；
    · `attackRecords(p)`  —— 演练记录（`Result` 长文本已由后端拆成字段）。
  ⚠️ 本页**不出现任何后端路径字面量**（那条判据可 grep 自动化：路径只许出现在
     `api/endpoints.js`）。三个函数名即全部后端面。

  【⚠️ 跑一轮是**同步**请求 —— 这是本页最容易写错的一处】
  `AttackController#run` 阻塞到演练跑完（几十秒）才回。两条后果都落在本页：
    ① **不给超时**：`api/observe.js#runAttack` 已经内置 `durationSeconds*1000 + 60s`
       （`http.post` 默认 10 秒会把 60 秒的演练当场掐断，而后端照跑完照落库，页面上只剩一条超时）
       ⇒ **本页不再传 `timeout`**，传了就是把这个算式抄第二遍。
    ② **没有中途进度**：后端不提供「跑到哪一步了」这个面（本仓没有任务表）⇒ 页面**不能**
       自己轮询、也不能声称知道进度。进度条按「已用时间 / 预计时长」估（见 `pct`），
       上限压在 96% —— 它是个「还在跑」的指示器，不是读数。

  【跑完之后为什么不再自己拼一行】
  `run` 的响应与 `attackRecords` 的行**同源**（都是 `AttackEngine.DrillRun.result()`），但响应里
  **没有** `CreatedAt` / `OperatorId` / `OperatorRole` / `Scenario`（那四列是落库时才有的）
  ⇒ 拼一行出来只会得到一条「时间空着」的假记录。改为：跑完 `loadRecords()` 再按 `Id` 展开
  —— 多一次往返，换掉一整条「本地拼行」的重复渲染路径（本页只有一条渲染路径）。

  【时区 —— 与 `AuditPage.vue` 同一口径、同一暗礁】
  `drill_record.created_at` 由 DAO 用 `DATE_FORMAT(…)` 读成**无时区文本**，库内是 **UTC**
  ⇒ 显示时按 UTC 解析、按浏览器时区渲染（`toLocal` 末尾那个 `Z` 不能省，理由与
  `AuditPage.vue#toLocal` 逐字相同）。
-->

<script setup>
/* eslint-disable no-undef */ /* 理由见 `../../../components/charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
// ⚠️ 三层 `../`（不是管理端其它页的两层）：本页在 `views/admin/observe/`，比 `views/admin/` 深一层。
import { attackRecords, attackScenarios, runAttack } from '../../../api/observe'
import WeightSlider from '../../../components/observe/WeightSlider.vue'
import IconGlyph from '../../../components/ui/IconGlyph.vue'

// ── 入参上下限（**与后端逐字对齐**）──────────────────────────────────────────
// 后端 `AttackEngine.clampInt` 的口径：缺省 100 / 1..500（并发）、缺省 60 / 1..600（秒）。
// ⚠️ 前端**不重复实现**那套 clamp（抄第二遍迟早与后端走岔），这里只在**发请求前**把用户
//    填的空 / 小数 / 越界值收成一个「像样的数」，真判据仍然后端说了算（它回 400 就照 400 显示）。
const CONC_MIN = 1
const CONC_MAX = 500
const CONC_DEFAULT = 100
const DUR_MIN = 1
const DUR_MAX = 600
const DUR_DEFAULT = 60

/** 跑一轮的预计耗时里，`durationSeconds` 之外的那一段（Redis 断开阶段 B 的 5 秒）。 */
const REDIS_DOWN_GRACE_MS = 5000

const scenarios = ref([])
const loadingScenarios = ref(false)
/** `Key → 权重`。缺省值来自后端（见 `loadScenarios`），本页不写死一套分布。 */
const weights = reactive({})
const concurrency = ref(CONC_DEFAULT)
const durationSeconds = ref(DUR_DEFAULT)

const running = ref(false)
const flash = reactive({ text: '', kind: '' })
const plannedSeconds = ref(DUR_DEFAULT)
const elapsedMs = ref(0)
let expectedMs = 0
let startedAt = 0
let timer = null

const records = ref([])
const total = ref(0)
const recLimit = ref(20)
const loadingRecords = ref(false)
const recErr = ref('')
const f = reactive({ limit: 20 })
const openId = ref(null)

// ── 编排状态 ─────────────────────────────────────────────────────────────────
const weightOf = (key) => Number(weights[key]) || 0
function setWeight(key, v) {
  weights[key] = Number(v) || 0
}

/** 勾选 = 权重 > 0（后端 `parse` 把 0 权重直接剔除，见 `WeightSlider.vue` 块首）。 */
const picked = computed(() =>
  scenarios.value.filter((s) => weightOf(s.Key) > 0).map((s) => ({ key: s.Key, weight: weightOf(s.Key) })),
)
const weightSum = computed(() => picked.value.reduce((a, c) => a + c.weight, 0))

/**
 * 能不能开跑 + 不能的话为什么。
 * ⚠️ 「并发 < 勾选场景数」是**后端会回 400** 的一条（`AttackEngine`：不悄悄少跑几个）——
 *    前端先拦一道纯粹是为了省掉一次几十秒的往返（400 是立刻回的，但用户会以为「点了没反应」）。
 *    判据仍以后端为准，前端这条只是提前说同一句话。
 */
const canRun = computed(() => picked.value.length > 0 && normInt(concurrency.value, CONC_MIN, CONC_MAX, CONC_DEFAULT) >= picked.value.length)
const blockReason = computed(() => {
  if (!scenarios.value.length) return '场景清单还没取到，先等它加载完（或看上方提示）。'
  if (!picked.value.length) return '至少勾一个场景：把任意一根滑条拖到 0 以上即为勾选（0% = 没勾）。'
  const c = normInt(concurrency.value, CONC_MIN, CONC_MAX, CONC_DEFAULT)
  if (c < picked.value.length) return `总并发数必须 ≥ 勾选场景数（现勾了 ${picked.value.length} 个，并发填的是 ${c}）—— 后端对这条回 400。`
  return ''
})

/** 进度条的估算百分比。⚠️ 压在 **96%**：它是「还在跑」的指示器，不是后端读数（见文件头）。 */
const pct = computed(() => {
  if (!running.value || expectedMs <= 0) return 0
  return Math.min(96, Math.round((elapsedMs.value / expectedMs) * 100))
})
const elapsedSec = computed(() => Math.floor(elapsedMs.value / 1000))

// ── 时区（与 `AuditPage.vue` 同一口径的**逐字副本**，见文件头）────────────────
const pad = (n) => String(n).padStart(2, '0')
const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})
function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

// ── 小工具 ───────────────────────────────────────────────────────────────────
/** 空 / 非数 → 缺省；否则四舍五入并收进 `[min, max]`。**只用于发请求前**（见常量那节）。 */
function normInt(raw, min, max, fallback) {
  const s = String(raw ?? '').trim()
  if (s === '') return fallback
  const n = Number(s)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, Math.round(n)))
}

const num = (v) => (v == null || v === '' ? '—' : String(v))
const dash = (v) => (v == null ? '—' : String(v))

function fmtMs(ms) {
  if (ms == null || ms === '') return '—'
  const n = Number(ms)
  if (!Number.isFinite(n)) return String(ms)
  return n < 1000 ? `${n} ms` : `${(n / 1000).toFixed(1)} s`
}

/**
 * 后端 `Result` 里取一段**数组**（`Scenarios` / `Notes` 用）。
 * ⚠️ 四处兜底是必要的：`Result` 可能是个**没有这些键的对象**（序列化失败时后端落的是
 *    `{"error": …}`，见 `DrillRecordService.toJson`）⇒ 直接 `.Notes.length` 会整页白屏。
 */
function listOf(row, key) {
  const v = row && row.Result && row.Result[key]
  return Array.isArray(v) ? v : []
}

/** `Result.Stock`（对象，缺失时给空对象 ⇒ 模板里的 `dash()` 会画成 `—`）。 */
function stockOf(row) {
  const v = row && row.Result && row.Result.Stock
  return v && typeof v === 'object' ? v : {}
}
function rdOf(row) {
  const v = row && row.Result && row.Result.RedisDown
  return v && typeof v === 'object' ? v : {}
}
function mkOf(row) {
  const v = row && row.Result && row.Result.Markers
  return v && typeof v === 'object' ? v : {}
}

/**
 * `Result.Markers.SampleOrderIds`。
 * ⚠️ 单独一个函数而不是复用 `listOf(r, 'SampleOrderIds')`：它是**挂在 `Markers` 下面**的，
 *    不是 `Result` 的直接子键 —— 用 `listOf` 取会**永远**得到空数组，而这一行的写法是
 *    `v-if="…length"` ⇒ 那个错不会报任何异常，只会把这一行静默藏掉（本笔实测踩到过）。
 */
function samplesOf(row) {
  const mk = mkOf(row)
  return Array.isArray(mk.SampleOrderIds) ? mk.SampleOrderIds : []
}

/**
 * `drill_record.Scenario`（配置 JSON）→ 可读的三个数。
 * 键是**短名**（`{c,d,s:[{k,w}]}`）—— `AttackEngine.buildScenarioJson` 手写的那份，
 * 用的是短键（列只有 500 字符，勾满 8 个场景时长键名会把余量吃掉一半），故这里按短键读。
 * ⚠️ 超长截断后**不再是合法 JSON** ⇒ 后端会落到 `ScenarioRaw` + `ScenarioParseError`
 *    并把 `Scenario` 置 null —— 那种行走 `cfgOf` 的兜底（`—`），不画成空勾选。
 */
function cfgOf(row) {
  const o = row && row.Scenario
  if (!o || typeof o !== 'object' || Array.isArray(o)) return { c: '—', d: '—', s: [] }
  return {
    c: o.c == null ? '—' : o.c,
    d: o.d == null ? '—' : o.d,
    s: Array.isArray(o.s) ? o.s.filter((x) => x && typeof x === 'object') : [],
  }
}

/**
 * 错误 → 一句话。
 * ⚠️ **先读 `e.body.msg`**：入参非法走后端统一形状的 `400 + {success:false,msg}`
 *    （`AttackController` 把 `IllegalArgumentException` 翻成 400），那句 msg 是「哪个参数不对」
 *    的**唯一**出处（`http.js` 的 `HttpError` 把解析后的 body 挂在 `.body` 上）。
 *    只显示「HTTP 400」等于把后端写好的那句话扔掉。
 */
function errText(e) {
  const msg = e && e.body && (e.body.msg || e.body.message)
  if (msg) return msg
  if (e && e.status) return `HTTP ${e.status}`
  return (e && e.message) || String(e)
}

// ── 取数 ─────────────────────────────────────────────────────────────────────
async function loadScenarios() {
  loadingScenarios.value = true
  try {
    const data = await attackScenarios()
    const list = (data && data.list) || []
    scenarios.value = list
    // ⚠️ 缺省权重**取自后端**（现为 15/15/10/10/10/10/15/15，合计 100 ——《块7参考》§1.2 的分布）
    //    不在前端写死一套：分布的唯一出处是 `AttackEngine.SCENARIOS`，抄第二遍必走岔。
    // ⚠️ 只在**首次**赋值（`undefined` 才填）：重取清单不该把用户拖过的滑条打回缺省。
    list.forEach((s) => {
      if (weights[s.Key] === undefined) weights[s.Key] = Number(s.DefaultWeight) || 0
    })
  } catch (e) {
    scenarios.value = []
    flash.kind = 'bad'
    flash.text = `场景清单加载失败：${errText(e)}`
  } finally {
    loadingScenarios.value = false
  }
}

async function loadRecords() {
  loadingRecords.value = true
  recErr.value = ''
  try {
    const data = await attackRecords({ limit: f.limit, offset: 0 })
    records.value = (data && data.list) || []
    total.value = data && data.total != null ? Number(data.total) : 0
    // ⚠️ 用**后端回显**的生效上限（请求 9999 会被夹到 200），同 `AuditPage.vue` 的处置。
    recLimit.value = data && data.limit != null ? Number(data.limit) : f.limit
  } catch (e) {
    records.value = []
    total.value = 0
    recErr.value = `演练记录加载失败：${errText(e)}`
  } finally {
    loadingRecords.value = false
  }
}

const truncated = computed(() => total.value > records.value.length)

// ── 跑 ───────────────────────────────────────────────────────────────────────
/**
 * 跑一轮（两种入口共用这一条路径）。
 *
 * @param {string} name   场景名（进 `drill_record.Name`，后端按列宽截断到 64）
 * @param {Array<{key:string,weight:number}>} chosen 勾选与权重
 */
async function fire(name, chosen) {
  if (running.value) return
  running.value = true
  flash.text = ''
  openId.value = null

  const secs = normInt(durationSeconds.value, DUR_MIN, DUR_MAX, DUR_DEFAULT)
  plannedSeconds.value = secs
  expectedMs = secs * 1000 + REDIS_DOWN_GRACE_MS
  startedAt = Date.now()
  elapsedMs.value = 0
  timer = setInterval(() => {
    elapsedMs.value = Date.now() - startedAt
  }, 200)

  try {
    // ⚠️ **不传 opts**：`runAttack` 自己带 `durationSeconds*1000 + 60s` 的长超时
    //    （见 `api/observe.js` 文件头那条 ⚠️ 与 `AttackController` 类注释）。
    const data = await runAttack({
      name,
      concurrency: normInt(concurrency.value, CONC_MIN, CONC_MAX, CONC_DEFAULT),
      durationSeconds: secs,
      scenarios: chosen,
    })
    flash.kind = 'ok'
    flash.text =
      `「${data.Name || name}」跑完：总请求 ${data.TotalRequests} · 拦下 ${data.BlockedRequests} · ` +
      `失败 ${data.FailedRequests} · 耗时 ${fmtMs(data.DurationMs)}。` +
      `记录 Id=${data.Id} 已刷新到「演练记录」并展开。`
    // 跑完刷新列表并按 Id 展开 —— 理由见文件头「跑完之后为什么不再自己拼一行」。
    await loadRecords()
    if (data.Id != null) openId.value = data.Id
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `演练没跑起来：${errText(e)}`
  } finally {
    running.value = false
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }
}

/** 固定一键：**这一个**场景、权重 100（并发 / 持续秒数仍取面板上的值）。 */
function runPreset(s) {
  fire(s.Name, [{ key: s.Key, weight: 100 }])
}

/** 自定义编排：按滑条勾选跑。 */
function runCustom() {
  fire('自定义', picked.value.map((c) => ({ key: c.key, weight: c.weight })))
}

function toggle(r) {
  openId.value = openId.value === r.Id ? null : r.Id
}

onMounted(() => {
  loadScenarios()
  loadRecords()
})

// ⚠️ 页面切走要停掉本地的计时器 —— 它只是一个 `setInterval`，不停就一直在给一个没人看的
//    组件写 `elapsedMs`（演练本身在后端照常跑完、照常落库，本页只是不再等它）。
onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.a-ac {
  max-width: 1120px;
}

/* ── ① 状态条 / 通用件（与 `AuditPage.vue` 同款同值，不另立一套）───────────── */
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
  font-variant-numeric: tabular-nums;
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
.a-flash {
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-flash.ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.a-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}
.a-err {
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-note,
.a-warn {
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

/* ── ② 固定一键 ───────────────────────────────────────────────────────────── */
.a-presets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(232px, 1fr));
  gap: var(--s3);
}
.a-preset {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font: inherit;
  text-align: left;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s3) var(--s4);
  cursor: pointer;
}
.a-preset:hover:not(:disabled) {
  border-color: var(--brand-ink);
  background: var(--surface-2);
}
.a-preset:disabled {
  cursor: default;
  opacity: 0.55;
}
.a-preset-n {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
}
.a-preset-p {
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.a-preset-w {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* ── ③ 自定义编排 ─────────────────────────────────────────────────────────── */
.a-sims {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--s2);
}
.a-run {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  margin-top: var(--s4);
  padding-top: var(--s4);
  border-top: 1px solid var(--line);
}
.a-f {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-f-narrow {
  max-width: 132px;
}
.a-f input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-run-note {
  flex: 1 1 240px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
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
.a-btn:hover:not(:disabled) {
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

.a-prog {
  margin-top: var(--s4);
}
.a-prog-bar {
  height: 6px;
  border-radius: var(--r1);
  background: var(--surface-3);
  overflow: hidden;
}
.a-prog-bar i {
  display: block;
  height: 100%;
  background: var(--brand-ink);
  transition: width 0.2s linear;
}
.a-prog-t {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-top: var(--s2);
}

/* ── ④ 演练记录 ───────────────────────────────────────────────────────────── */
.a-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  padding: var(--s4) var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  margin-bottom: var(--s4);
}
.a-f select {
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
  color: inherit;
}
.a-ev-head:hover {
  background: var(--surface-2);
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
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-ev-l2 {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
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
.a-corrupt {
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
}
.a-rawwire {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  word-break: break-all;
}
.a-res {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: var(--s3);
  margin-bottom: var(--s3);
}
.a-res-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-res-cell span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-res-cell b {
  font-family: var(--font-mono);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: var(--ink);
}
.a-sub {
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  color: var(--ink-3);
  margin: var(--s3) 0 var(--s2);
}
.a-kv {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  line-height: 1.9;
}
.a-kv code {
  font-family: var(--font-mono);
  color: var(--ink);
}
.a-chip {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r1);
  padding: 1px 5px;
  margin-right: var(--s1);
}
.a-tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.a-tbl th {
  text-align: left;
  font-weight: var(--fw-med);
  color: var(--ink-3);
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
}
.a-tbl td {
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
  vertical-align: top;
  color: var(--ink-2);
}
.a-tbl tr:last-child td {
  border-bottom: 0;
}
.a-tk {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.a-tk b {
  color: var(--ink);
  font-weight: var(--fw-med);
}
/* 「拦下」是本页唯一的好数字（拦得越多越对）；「失败」是坏数字 —— 只有 >0 时才染色
   （模板里判 `Number(...) > 0`），避免满屏绿色把真异常淹掉。 */
.a-ok {
  color: var(--success);
}
.a-bad {
  color: var(--danger);
}
.a-notes {
  list-style: disc;
  padding-left: var(--s5);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  line-height: 1.7;
}
</style>
