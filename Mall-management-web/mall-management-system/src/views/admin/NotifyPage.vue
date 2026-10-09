<template>
  <section class="n-page">
    <!-- ── ① 状态条 —— 「数字 → 状态」（`c3-modules.md` §五 的三招之一）──────────── -->
    <header class="n-status">
      <div class="n-stat">
        <b>{{ histTotal }}</b>
        <span>投递批次{{ histFiltered ? '（筛选后）' : '' }}</span>
      </div>
      <div class="n-stat">
        <b>{{ rows.length }}</b>
        <span>本次显示</span>
      </div>
      <div class="n-stat">
        <b>{{ templates.length }}</b>
        <span>模板</span>
      </div>
      <div class="n-tz">
        <span class="n-chip-utc">UTC</span>
        <span>库内时钟 → <b>浏览器时区</b></span>
        <span class="n-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <!-- ── ② 发送 ─────────────────────────────────────────────────────────── -->
    <form class="n-card" @submit.prevent="doSend">
      <h2 class="n-h2">发送通知</h2>

      <div class="n-row">
        <span class="n-lab">受众</span>
        <div class="n-radios">
          <label><input v-model="form.mode" type="radio" value="all" /> 全体</label>
          <label><input v-model="form.mode" type="radio" value="customer" /> 全部客户</label>
          <label><input v-model="form.mode" type="radio" value="merchant" /> 全部商家</label>
          <label><input v-model="form.mode" type="radio" value="one" /> 定向一人</label>
        </div>
      </div>

      <div v-if="form.mode === 'one'" class="n-row">
        <span class="n-lab">收件人</span>
        <span class="n-fields">
          <input v-model.trim="form.toId" type="text" placeholder="业务号（如 M001 / C001）" />
          <select v-model="form.toType">
            <option value="customer">customer（客户）</option>
            <option value="merchant">merchant（商家）</option>
          </select>
        </span>
      </div>

      <div class="n-row">
        <span class="n-lab">事件类型</span>
        <span class="n-fields">
          <select v-model="form.eventType">
            <option v-for="o in eventOptions" :key="o.code" :value="o.code">{{ o.label }}</option>
            <option :value="CUSTOM">自定义 code…</option>
          </select>
          <input
            v-if="form.eventType === CUSTOM"
            v-model.trim="form.customEvent"
            class="n-mono"
            type="text"
            placeholder="如 goods.audit.rejected"
          />
        </span>
      </div>
      <p class="n-hint">
        ⚠️ 后端**不校验**事件类型 —— 认不出的 code 照样落库，收件人那头按 code 原文显示
        （不空白、不崩）。这是「加类型不改表、不改后端」的兑现点，不是防御漏洞。
      </p>

      <div class="n-row">
        <span class="n-lab">标题</span>
        <input v-model="form.title" type="text" maxlength="128" placeholder="通知标题（≤128）" />
      </div>
      <div class="n-row n-row-top">
        <span class="n-lab">正文</span>
        <textarea v-model="form.body" rows="4" maxlength="1024" placeholder="通知正文（≤1024）"></textarea>
      </div>

      <details class="n-adv">
        <summary>关联对象（可选 · 软引用，不设 FK）</summary>
        <div class="n-row">
          <span class="n-lab">类型</span>
          <input v-model.trim="form.targetType" type="text" placeholder="goods / user / comment / order…" />
        </div>
        <div class="n-row">
          <span class="n-lab">标识</span>
          <input v-model.trim="form.targetId" type="text" placeholder="如 M001:P0001" />
        </div>
        <p class="n-hint">
          ⚠️ 软引用：目标对象**可能已被删除**（尤其在全量重导之后）⇒ 前端渲染时容错，
          显示「（相关对象已删除）」。正因为如此这里**故意不设外键** —— 有 FK 会让插入报错或级联删。
        </p>
      </details>

      <div class="n-row n-actions">
        <button type="submit" class="n-btn n-btn-on" :disabled="sending">
          {{ sending ? '发送中…' : '发送' }}
        </button>
        <span v-if="sendResult" class="n-sent" :class="{ bad: !sendResult.ok }">
          <template v-if="sendResult.ok">
            已投递 <b>{{ sendResult.sent }}</b> 人 · 批次 <code>{{ sendResult.batchId }}</code>
            <template v-if="sendResult.sent === 0">· ⚠️ 0 人是**正常结果**（该受众下没人在册），不是失败</template>
          </template>
          <template v-else>发送失败：{{ sendResult.msg }}</template>
        </span>
      </div>
    </form>

    <!-- ── ③ 投递历史（按批次归组）──────────────────────────────────────────── -->
    <h2 class="n-h2 n-h2-stand">投递历史</h2>
    <form class="n-filters" @submit.prevent="loadHistory">
      <label class="n-f">
        <span>事件类型</span>
        <select v-model="hf.eventType" @change="loadHistory">
          <option value="">全部</option>
          <option v-for="o in eventOptions" :key="o.code" :value="o.code">{{ o.label }}</option>
        </select>
      </label>
      <label class="n-f">
        <span>起（本地）</span>
        <input v-model="hf.fromLocal" type="datetime-local" />
      </label>
      <label class="n-f">
        <span>止（本地）</span>
        <input v-model="hf.toLocal" type="datetime-local" />
      </label>
      <label class="n-f n-f-narrow">
        <span>上限</span>
        <select v-model.number="hf.limit" @change="loadHistory">
          <option v-for="n in [50, 100, 200, 500]" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <div class="n-f-actions">
        <button type="submit" class="n-btn n-btn-on" :disabled="histLoading">
          {{ histLoading ? '查询中…' : '查询' }}
        </button>
        <button type="button" class="n-btn" @click="resetHistory">重置</button>
      </div>
    </form>

    <p v-if="histErr" class="n-err">{{ histErr }}</p>
    <p v-else-if="histTruncated" class="n-note">
      已截断：按上限 <b>{{ hf.limit }}</b> 批显示，共 <b>{{ histTotal }}</b> 批 —— 收窄筛选，或把上限调大。
    </p>

    <ol v-if="rows.length" class="n-stream">
      <li v-for="r in rows" :key="r.BatchId" class="n-ev">
        <span class="n-ev-hue" :style="{ background: hue(r.EventType) }"></span>
        <span class="n-ev-main">
          <span class="n-ev-l1">
            <b class="n-ev-title">{{ r.Title }}</b>
            <code class="n-tag">{{ labelOf(r.EventType) }}</code>
          </span>
          <span class="n-ev-l2">{{ r.Content }}</span>
          <span class="n-ev-l3">
            <span class="n-dim">批次 <code>{{ r.BatchId }}</code></span>
          </span>
        </span>
        <span class="n-ev-nums">
          <span><b>{{ r.RecipientCount }}</b> 人</span>
          <span class="n-dim">未读 {{ r.UnreadCount }}</span>
        </span>
        <span class="n-ev-time">
          <b>{{ toLocal(r.CreatedAt) }}</b>
          <span class="n-dim">库内 {{ r.CreatedAt }}</span>
        </span>
      </li>
    </ol>
    <p v-else-if="!histLoading && !histErr" class="n-empty">没有命中任何投递批次。</p>

    <!-- ── ④ 模板（块 1 只做存取，发送时不套用 —— 方案 §七 未决 3 的裁定）────── -->
    <h2 class="n-h2 n-h2-stand">通知模板</h2>
    <p class="n-hint">
      ⚠️ 块 1 只做模板的**存取**：`send` 收的是**字面** title/body，**不**把模板套上去。
      占位符渲染（`{orderId}` 之类）要先把语法与转义口径定死，留到有第一个真消费者的那一块。
      在那之前，本表就是一张**存着不用的登记簿** —— 这是刻意的。
    </p>

    <table class="n-tpl">
      <thead>
        <tr><th>模板键</th><th>标题模板</th><th>正文模板</th><th>启用</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="t in templates" :key="t.TemplateKey" :class="{ off: !t.Enabled }">
          <td class="n-k"><code>{{ t.TemplateKey }}</code></td>
          <td>{{ t.TitleTemplate }}</td>
          <td>{{ t.BodyTemplate }}</td>
          <td>{{ t.Enabled ? '是' : '否' }}</td>
          <td>
            <button type="button" class="n-link" @click="editTemplate(t)">编辑</button>
          </td>
        </tr>
        <tr v-if="!templates.length">
          <td colspan="5" class="n-dim">还没有模板。在下面新增一条。</td>
        </tr>
      </tbody>
    </table>

    <form class="n-card n-tplform" @submit.prevent="saveTemplate">
      <h3 class="n-h3">{{ tplForm.editing ? '编辑模板' : '新增模板' }}</h3>
      <div class="n-row">
        <span class="n-lab">模板键</span>
        <input v-model.trim="tplForm.templateKey" type="text" maxlength="64" placeholder="如 goods.audit.rejected" />
      </div>
      <div class="n-row">
        <span class="n-lab">标题模板</span>
        <input v-model="tplForm.titleTemplate" type="text" maxlength="255" />
      </div>
      <div class="n-row n-row-top">
        <span class="n-lab">正文模板</span>
        <textarea v-model="tplForm.bodyTemplate" rows="3" maxlength="1024"></textarea>
      </div>
      <div class="n-row">
        <span class="n-lab">启用</span>
        <label class="n-inline"><input v-model="tplForm.enabled" type="checkbox" /> 启用</label>
      </div>
      <div class="n-row n-actions">
        <button type="submit" class="n-btn n-btn-on" :disabled="tplSaving">
          {{ tplSaving ? '保存中…' : '保存' }}
        </button>
        <button v-if="tplForm.editing" type="button" class="n-btn" @click="resetTemplateForm">取消编辑</button>
        <span v-if="tplMsg" class="n-sent" :class="{ bad: !tplMsg.ok }">{{ tplMsg.text }}</span>
      </div>
    </form>
  </section>
</template>

<!--
  系统通知页（C3 块 1 · I2，2026-10-09）—— `/admin/business/notify` 的真页。

  它是 **占位里第二个换成真页的**（原走 `StubPage.vue`，路由记录 `router/index.js` 里
  `component: AdminStub` → 懒加载本组件）。第一个是 `AuditPage.vue`（批 A）。

  【它消费后端 4 个端点】`/admin/notify/send` · `/history` · `/template/list` · `/template/save`
  （`AdminNotificationController`）。**收件箱不在这里** —— 那是公共层的 `/notification/*`
  （`NotificationController`），由客户 / 商家两端的 `views/NotifyInbox.vue` 消费。
  「发」与「收」在后端就是两个 Controller，前端不合并（用户口径：通知 vs 消息入口分开；
  同一条口径的另一半是「发」与「收」也不该糅在一页）。

  【TD-18 —— 本页的端点**故意没有角色闸**】
  `/admin/**` 的端点当前都不做 `role=3` 判据：管理员的 cookie 身份铸不出 token
  （`/user/token` 要一个「已授权身份」），默认 profile 上加闸会让**管理员自己** 401。
  这条是**已知接缝**，与 `AdminAuditController` 绑定，等裁决 —— 别在本页「顺手」加一个前置判据。
  记录：`docs/agent/tech-debt.md` TD-18。

  【时区】与 `AuditPage.vue` 同一条，逐字：后端 `CreatedAt` 是 `DATE_FORMAT(..., '%Y-%m-%d %H:%i:%s')`
  的**无时区文本**，值是**库内时钟（UTC）**。显示 → 补 `Z` 解析、按浏览器时区渲染（`toLocal`）；
  筛选 → 本地时刻反算成 UTC 文本再发（`toSqlTime`）。⚠️ 那两个函数与 `AuditPage.vue` 里的
  **逐字同款**（刻意的重复，同 `api/notify.js` 块首那条判据：抽共享要新开文件、两处还没到「第三处」）。

  【视觉】按 `c3-modules.md` §五「可观测域视觉化原则」：数字→状态（顶部三个数）、
  表格→叙事（事件流卡片）、异常高亮（`hue(eventType)` 定色）。发送表单与历史**同一页**：
  发完立刻能在下面看见那一批 —— 这是「页面探针」，不是布局偷懒。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { notifyHistory, notifySend, notifyTemplateList, notifyTemplateSave } from '../../api/admin'
import { EVENT_OPTIONS, eventLabel, isKnownEvent } from '../../config/notify-events'

/** 下拉里那一项「自定义 code」的哨兵值 —— 它**不是**一个 code，别让它漏进请求。 */
const CUSTOM = '__custom__'

const eventOptions = EVENT_OPTIONS

// ── 时区（与 `AuditPage.vue:173-217` 逐字同款，理由见文件尾注释块）──────────────
const pad = (n) => String(n).padStart(2, '0')

const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

/** 库内时钟文本（UTC）→ 浏览器本地时间。⚠️ 末尾 `Z` **不能省**（理由同 `AuditPage.vue`）。 */
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
 * `<input type="datetime-local">` 的本地时刻 → 库内时钟文本（UTC）。起补 `:00`、止补 `:59`。
 * ⚠️ 与 `AuditPage.vue` 的 `toSqlTime` **同名同款**：后端点名过这个函数名
 *    （`AdminAuditController#list` 的 javadoc 写着「前端 … 见 `AuditPage.vue` 的 `toSqlTime`」）。
 */
function toSqlTime(localValue, isEnd) {
  if (!localValue) return ''
  const d = new Date(localValue)
  if (Number.isNaN(d.getTime())) return ''
  d.setSeconds(isEnd ? 59 : 0, 0)
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  )
}

/** 事件类型的**稳定**配色 —— 同一 code 永远同一个色相（照 `AuditPage.vue` 的 `hue`）。 */
function hue(key) {
  const s = String(key || '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return `hsl(${h} 55% 55%)`
}

/** code → 展示文字。未知 code 原样回（**任何一行都不许空白**，`config/notify-events.js:6-11`）。 */
function labelOf(code) {
  return isKnownEvent(code) ? eventLabel(code) : String(code == null ? '' : code)
}

// ── 发送 ─────────────────────────────────────────────────────────────────────
const form = reactive({
  mode: 'all',
  toId: '',
  toType: 'customer',
  eventType: 'system.notice',
  customEvent: '',
  title: '',
  body: '',
  targetType: '',
  targetId: '',
})
const sending = ref(false)
/** `null` = 还没发过；`{ok:true, sent, batchId}` / `{ok:false, msg}`。 */
const sendResult = ref(null)

/** 表单里的「自定义 code」取值 —— 哨兵态一律折成**空串**（见 `doSend` 的用法）。 */
const effectiveEvent = computed(() =>
  form.eventType === CUSTOM ? form.customEvent : form.eventType
)

async function doSend() {
  if (sending.value) return
  const event = effectiveEvent.value
  if (!form.title.trim()) { sendResult.value = { ok: false, msg: '缺少参数：title' }; return }
  if (!form.body.trim()) { sendResult.value = { ok: false, msg: '缺少参数：body' }; return }
  if (!event) { sendResult.value = { ok: false, msg: '请选择或填写事件类型 code' }; return }
  if (form.mode === 'one' && !form.toId.trim()) {
    sendResult.value = { ok: false, msg: '定向投递缺少参数：toId' }
    return
  }

  // ⚠️ `audience` 与 `toId`/`toType` **互斥**：后端 `NotificationBroadcastService.send`
  //    先看 `toId` —— 给了就走定向，没给才要 `audience`。两个都发虽不会被拒
  //    （定向那支根本不读 audience），但会在日志里留下一个**不生效**的参数。
  //    这里只发该发的那个。
  const params = {
    title: form.title.trim(),
    body: form.body.trim(),
    eventType: event,
    ...(form.mode === 'one'
      ? { toId: form.toId.trim(), toType: form.toType }
      : { audience: form.mode }),
    ...(form.targetType.trim() ? { targetType: form.targetType.trim() } : {}),
    ...(form.targetId.trim() ? { targetId: form.targetId.trim() } : {}),
  }

  sending.value = true
  sendResult.value = null
  try {
    const data = await notifySend(params)
    if (!data || !data.success) {
      sendResult.value = { ok: false, msg: (data && data.msg) || '发送失败' }
      return
    }
    sendResult.value = { ok: true, sent: Number(data.sent) || 0, batchId: data.batchId }
    // 发完立刻刷新历史 —— 那一条刚投出去，用户下一步就是去核它。
    await loadHistory()
  } catch (e) {
    sendResult.value = { ok: false, msg: (e && e.message) || '网络错误' }
  } finally {
    sending.value = false
  }
}

// ── 历史 ─────────────────────────────────────────────────────────────────────
const rows = ref([])
const histTotal = ref(0)
const histLoading = ref(false)
const histErr = ref('')
const hf = reactive({ eventType: '', fromLocal: '', toLocal: '', limit: 100 })

const histFiltered = computed(() => !!(hf.eventType || hf.fromLocal || hf.toLocal))
const histTruncated = computed(() => histTotal.value > rows.value.length)

async function loadHistory() {
  histLoading.value = true
  histErr.value = ''
  try {
    const data = await notifyHistory({
      eventType: hf.eventType || undefined,
      from: toSqlTime(hf.fromLocal, false) || undefined,
      to: toSqlTime(hf.toLocal, true) || undefined,
      limit: hf.limit,
    })
    if (!data || !data.success) {
      histErr.value = (data && data.msg) || '加载失败'
      rows.value = []
      return
    }
    rows.value = Array.isArray(data.list) ? data.list : []
    histTotal.value = Number(data.total) || 0
  } catch (e) {
    histErr.value = (e && e.message) || '网络错误'
    rows.value = []
  } finally {
    histLoading.value = false
  }
}

function resetHistory() {
  hf.eventType = ''
  hf.fromLocal = ''
  hf.toLocal = ''
  hf.limit = 100
  loadHistory()
}

// ── 模板 ─────────────────────────────────────────────────────────────────────
const templates = ref([])
const tplSaving = ref(false)
const tplMsg = ref(null)
const tplForm = reactive({
  editing: false,
  templateKey: '',
  titleTemplate: '',
  bodyTemplate: '',
  enabled: true,
})

async function loadTemplates() {
  try {
    const data = await notifyTemplateList()
    if (data && data.success) templates.value = Array.isArray(data.list) ? data.list : []
  } catch (e) {
    // 模板区是**次要内容**：拉不到就空着，别把整页变成错误页（发送与历史照常）。
    console.error('NOTIFY 模板列表加载失败', e)
  }
}

function editTemplate(t) {
  tplForm.editing = true
  tplForm.templateKey = t.TemplateKey
  tplForm.titleTemplate = t.TitleTemplate
  tplForm.bodyTemplate = t.BodyTemplate
  tplForm.enabled = !!t.Enabled
  tplMsg.value = null
}

function resetTemplateForm() {
  tplForm.editing = false
  tplForm.templateKey = ''
  tplForm.titleTemplate = ''
  tplForm.bodyTemplate = ''
  tplForm.enabled = true
  tplMsg.value = null
}

async function saveTemplate() {
  if (tplSaving.value) return
  if (!tplForm.templateKey.trim()) { tplMsg.value = { ok: false, text: '模板键不能为空' }; return }
  if (!tplForm.titleTemplate.trim() || !tplForm.bodyTemplate.trim()) {
    tplMsg.value = { ok: false, text: '标题模板与正文模板都不能为空' }
    return
  }
  tplSaving.value = true
  tplMsg.value = null
  try {
    const data = await notifyTemplateSave({
      templateKey: tplForm.templateKey.trim(),
      titleTemplate: tplForm.titleTemplate,
      bodyTemplate: tplForm.bodyTemplate,
      enabled: tplForm.enabled,
    })
    if (!data || !data.success) {
      tplMsg.value = { ok: false, text: (data && data.msg) || '保存失败' }
      return
    }
    // ⚠️ `affected` 1 = 新插、2 = 更新（MySQL 的 `INSERT … ON DUPLICATE KEY UPDATE` 口径：
    //    更新一行若值没变也算 0 行受影响）—— **两个都是成功**，别拿它判成败。
    tplMsg.value = { ok: true, text: '已保存' }
    await loadTemplates()
  } catch (e) {
    tplMsg.value = { ok: false, text: (e && e.message) || '网络错误' }
  } finally {
    tplSaving.value = false
  }
}

onMounted(() => {
  loadHistory()
  loadTemplates()
})
</script>

<style scoped>
/* 与 `AuditPage.vue` 同一套骨架（`.a-*` → `.n-*`）：同一个壳、同一批 token，
   两页在侧边栏来回切时不该有两种密度。⚠️ 类名前缀取 `n-` 而不是复用 `a-`：
   `AuditPage` 的样式是 `scoped`，但同名类在**样式块**层混淆起来仍会误导读者。 */
.n-page {
  padding: var(--s6) var(--s5) var(--s7);
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 状态条 ───────────────────────────────────────────────────────────────── */
.n-status {
  display: flex;
  align-items: center;
  gap: var(--s6);
  flex-wrap: wrap;
  padding: var(--s5);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  margin-bottom: var(--s5);
}
.n-stat {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.n-stat b {
  font-size: var(--fs-2xl);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  line-height: 1;
}
.n-stat span { font-size: var(--fs-xs); color: var(--ink-3); }
.n-tz {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.n-chip-utc {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-radius: var(--r1);
  padding: 0 var(--s2);
}
.n-tz-off { font-family: var(--font-mono); }

/* ── 卡片 / 表单 ───────────────────────────────────────────────────────────── */
.n-card {
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  padding: var(--s5);
  margin-bottom: var(--s5);
}
.n-h2 {
  margin: 0 0 var(--s4);
  font-size: var(--fs-md);
  font-weight: 600;
}
.n-h2-stand {
  margin: var(--s6) 0 var(--s4);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
}
.n-h3 {
  margin: 0 0 var(--s4);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.n-row {
  display: flex;
  align-items: center;
  gap: var(--s4);
  margin-bottom: var(--s3);
}
.n-row-top { align-items: flex-start; }
.n-lab {
  flex: none;
  width: 72px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.n-row input[type='text'],
.n-row select,
.n-row textarea {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
  min-width: 0;
  flex: 1;
}
.n-row textarea { resize: vertical; line-height: 1.55; }
.n-row input.n-mono { font-family: var(--font-mono); }
.n-fields {
  display: flex;
  gap: var(--s3);
  flex: 1;
  min-width: 0;
}
.n-radios {
  display: flex;
  gap: var(--s4);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
}
.n-radios label,
.n-inline {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  cursor: pointer;
}
.n-adv {
  margin: var(--s3) 0;
  padding: var(--s3) var(--s4);
  border: 1px dashed var(--line-2);
  border-radius: var(--r1);
}
.n-adv summary { cursor: pointer; font-size: var(--fs-xs); color: var(--ink-3); }
.n-adv .n-row:first-of-type { margin-top: var(--s3); }
.n-actions { align-items: center; margin-top: var(--s4); }

.n-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s4);
  cursor: pointer;
}
.n-btn-on {
  color: #fff;
  background: var(--brand);
  border-color: var(--brand);
}
.n-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.n-link {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: none;
  border: 0;
  cursor: pointer;
  padding: 0;
}

.n-sent { font-size: var(--fs-xs); color: var(--ink-2); }
.n-sent b { color: var(--brand-ink); font-variant-numeric: tabular-nums; }
.n-sent.bad { color: var(--danger); }
.n-sent code { font-family: var(--font-mono); }

.n-hint {
  margin: var(--s2) 0 var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.6;
}

/* ── 筛选条 ───────────────────────────────────────────────────────────────── */
.n-filters {
  display: flex;
  align-items: flex-end;
  gap: var(--s4);
  flex-wrap: wrap;
  margin-bottom: var(--s4);
}
.n-f {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.n-f input,
.n-f select {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
}
.n-f-narrow select { width: 96px; }
.n-f-actions { display: flex; gap: var(--s3); }

.n-err { color: var(--danger); font-size: var(--fs-sm); }
.n-note { color: var(--info-deep); font-size: var(--fs-sm); }
.n-empty { color: var(--ink-3); font-size: var(--fs-sm); padding: var(--s6) 0; }

/* ── 事件流（按批次）────────────────────────────────────────────────────────── */
.n-stream { list-style: none; margin: 0; padding: 0; }
.n-ev {
  display: grid;
  grid-template-columns: 3px minmax(0, 1fr) auto auto;
  align-items: start;
  gap: var(--s4);
  padding: var(--s4) var(--s3);
  border-bottom: 1px solid var(--line);
}
.n-ev:hover { background: var(--surface-2); }
.n-ev-hue { width: 3px; align-self: stretch; border-radius: var(--r1); }
.n-ev-main { display: flex; flex-direction: column; gap: var(--s2); min-width: 0; }
.n-ev-l1 { display: flex; align-items: baseline; gap: var(--s3); min-width: 0; }
.n-ev-title { font-size: var(--fs-sm); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.n-tag {
  flex: none;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 var(--s2);
}
.n-ev-l2 {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.n-ev-l3 { font-size: var(--fs-xs); }
.n-ev-nums {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  font-size: var(--fs-xs);
  text-align: right;
  color: var(--ink-2);
}
.n-ev-nums b { font-size: var(--fs-md); font-variant-numeric: tabular-nums; color: var(--ink); }
.n-ev-time {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  font-size: var(--fs-xs);
  text-align: right;
  white-space: nowrap;
}
.n-ev-time b { font-variant-numeric: tabular-nums; color: var(--ink); }
.n-dim { color: var(--ink-3); font-size: var(--fs-xs); }
.n-ev-l3 code, .n-dim code { font-family: var(--font-mono); }

/* ── 模板表 ───────────────────────────────────────────────────────────────── */
.n-tpl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
  margin-bottom: var(--s5);
}
.n-tpl th,
.n-tpl td {
  text-align: left;
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line);
  vertical-align: top;
}
.n-tpl th { font-size: var(--fs-xs); color: var(--ink-3); font-weight: 500; }
.n-tpl tr.off td { color: var(--ink-3); }
.n-tpl .n-k code { font-family: var(--font-mono); }
.n-tplform { margin-top: var(--s4); }
</style>
