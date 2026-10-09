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
          <option v-for="a in facets.actions" :key="a" :value="a">{{ a }}</option>
        </select>
      </label>
      <label class="a-f">
        <span>对象类型</span>
        <select v-model="f.targetType" @change="load">
          <option value="">全部</option>
          <option v-for="t in facets.targetTypes" :key="t" :value="t">{{ t }}</option>
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
          {{ loading ? '查询中…' : '查询' }}
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

    <!-- ── ③ 事件流 —— 「表格 → 叙事」：一条一条讲，不是一张宽表 ──────────────
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
              <code class="a-tag">{{ r.Action }}</code>
              <span class="a-ev-target">{{ r.TargetType }} · {{ r.TargetId }}</span>
            </span>
            <span class="a-ev-l2">
              <span class="a-who-chip">{{ r.ActorId || '（认不出身份）' }}</span>
              <span class="a-dim">角色 {{ r.ActorRole == null ? '—' : r.ActorRole }}</span>
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
    「库时钟 = UTC」这条本身有记录：`docs/agent/context-packs/c3-block-1-infra.md:109`。

  【视觉】按 `c3-modules.md` §五「可观测域视觉化原则」：数字→状态（顶部三个大数）、
  表格→叙事（事件流卡片，不是宽表）、异常高亮（变化的列高亮）。布局**有意不分两栏**，理由见模板里那条注释。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { auditDetail, auditFacets, auditList } from '../../api/admin'

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
</style>
