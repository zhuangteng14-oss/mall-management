<template>
  <div class="notify-room">
    <div class="nhead">
      <h1>系统通知</h1>
      <span class="sub">
        共 {{ total }} 条<template v-if="unread > 0"> · 未读 <b class="hot">{{ unread }}</b></template>
      </span>
      <span class="grow"></span>
      <label class="switch">
        <input v-model="onlyUnread" type="checkbox" @change="load" />
        仅看未读
      </label>
      <button class="act" type="button" :disabled="unread === 0 || busy" @click="markAll">
        全部已读
      </button>
    </div>

    <p v-if="loading" class="hint">加载中…</p>
    <p v-else-if="error" class="hint bad">{{ error }}</p>
    <p v-else-if="rows.length === 0" class="hint">
      {{ onlyUnread ? '没有未读通知' : '暂无通知' }}
    </p>

    <ul v-else class="nlist">
      <li v-for="row in rows" :key="row.Id">
        <button
          class="nrow"
          type="button"
          :class="{ unread: row.unread }"
          :aria-current="row.unread ? 'true' : null"
          @click="openRow(row)"
        >
          <span class="bar" aria-hidden="true"></span>
          <span class="stack">
            <span class="l1">
              <span class="title">{{ row.Title }}</span>
              <span class="ev">{{ labelOf(row.EventType) }}</span>
            </span>
            <span class="body">{{ row.Content }}</span>
            <span class="l3">
              <span v-if="row._target" class="tgt" :class="row._target.kind">{{ row._target.text }}</span>
              <span class="tm">{{ fmtTime(row.CreatedAt) }}</span>
            </span>
          </span>
        </button>
      </li>
    </ul>

    <p v-if="!loading && !error && truncated" class="hint">
      只显示了最近 {{ rows.length }} 条（共 {{ total }} 条）
    </p>
  </div>
</template>

<script setup>
// src/views/NotifyInbox.vue —— 系统通知**收件箱**（客户 / 商家两端共用。C3 块 1 · I2，2026-10-09）。
//
// ⚠️ **为什么一个组件服务两端**：两端的差异**只有数据**（收件人 id 与 `ToType`），结构逐字相同（C3 块 2 起是**三端** —— 多了管理端的**共享收件箱**，`ToType='admin'` / `ToId='*'`，落在 `/admin/inbox`（**侧边栏顶层独立项**；块 2 手验后从 `AccountsPage.vue` 的页内第 ⑤ 块提出来）；派生见 `:99-103`）
// —— 判断依据与 `views/admin/StubPage.vue` 块首那条一致（差异只在数据、不在结构 ⇒ 不拆文件）。
// 这与「消息」那边不同：`ChatRoom.vue` 要同时服务 `/customer/msg` 与 `/merchant/msg`，
// 它也是**一个组件**；本页只是把同一手法再走一遍。
//
// ⚠️ **身份从哪来**：本页由壳（`AppShell.vue`）经 `<router-view>` 挂载，路由记录里**没有** `props`
// ⇒ 拿不到 `base` / `role`。两条路：① 像 `ChatRoom.vue:180-211` 那样读 `window.location.pathname`
// 反推；② 读 `route.path`。**取 ②** —— `ChatRoom` 那支的 `window.location` 是因为它在
// hash 路由下还要兼容旧地址，本页是新加的、没有历史包袱，`route.path` 才是**响应式**的那份
// （`:150-170` 的 `watch` 因此能在两个壳之间切换时重载）。
//
// ⚠️ **与「消息」的两条独立链**（用户口径：通知 ≠ 消息，前端入口必须分开）：
//   后端就是两张表（`notification` ⇄ `message`）、两套端点、两个 Controller
//   （`NotificationController` ⇄ `MessageController`）⇒ 本页**只**调 `/notification/*`，
//   一个 `message.*` 端点都不碰。合并两者的收件箱 = 把用户明令分开的东西又糅回去。
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { handleApiError } from '../api/http'
import { getMerchantId, getCustomerId, isLoggedIn } from '../api/auth'
import { notifyList, notifyRead, notifyReadAll } from '../api/notify'
import { eventLabel, isKnownEvent } from '../config/notify-events'

/** 一页最多拉多少条。后端 `DEFAULT_LIMIT=100` / `MAX_LIMIT=500`（`NotificationService`），
 *  这里显式给 100：与后端默认值**同值但不同源**，写出来才能让「为什么只显示 100 条」
 *  在提示条（`truncated`）里被看见，而不是个隐形截断。 */
const LIMIT = 100

const route = useRoute()

const rows = ref([])
const total = ref(0)
const unread = ref(0)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const onlyUnread = ref(false)

// ── 两端怎么分：只看路径前缀 ─────────────────────────────────────────────────
// 本页有**三个**挂载点：`/customer/notify` ⇄ `/merchant/notify`（`router/index.js` 尾部两条
// `addRoute`）+ `/admin/inbox`（`/admin` children 末条，**静态子路由**）。⚠️ 别写成 `route.path.includes('merchant')` —— `includes` 对将来任何
// 含该子串的路径都为真（`startsWith('/merchant')` 才是「在这个壳下」的判据，同 `nav.js:6` 那条）。
const base = computed(() => (route.path.startsWith('/merchant') ? '/merchant' : route.path.startsWith('/admin') ? '/admin' : '/customer'))   // ⚠️ C3 块 2〈用户块〉：**同行**加 `admin` 那一支（第三个挂载点 = `/admin/inbox`，块 2 手验后从 `AccountsPage.vue` 第 ⑤ 块提出来、成侧边栏顶层独立项；`api/endpoints.js:84` 与后端 `MerchantApplicationController.ADMIN_INBOX_ID` 都点名了这一处，它不是「顺手加的壳」）。⚠️ 本行连同 `:100` / `:103` 被 `MessageCenter.vue:35` 按 **`:99-103`** 整段引 ⇒ 只许**同行**改、一行都不许插。
const toType = computed(() => (base.value === '/merchant' ? 'merchant' : base.value === '/admin' ? 'admin' : 'customer'))
/** 当前收件人业务号。⚠️ 与 `AppShell.vue` 的 `getCurrentUser()` **同一条降级链**：
 *  先按壳取自己的号；取不到就是「没登录」—— 别退回另一种身份（那样商家的会话会用客户的号查）。⚠️ **管理端是这条链的唯一例外**（C3 块 2）：它的 `ToId` 是**共享收件箱的哨兵值** `'*'`，不是「自己的号」—— 全仓管理员共用一个收件箱（入驻申请就发到这里）。见 `:103`。 */
const toId = computed(() => (base.value === '/merchant' ? getMerchantId() : base.value === '/admin' ? '*' : getCustomerId()))   // ⚠️ 哨兵 `'*'` 与后端 `MerchantApplicationController.ADMIN_INBOX_ID` **逐字一致**（改一处要同改另一处）。后端放行**不是**靠「自己等于自己」（`'*'` 当然不等于调用者的号），而是 `IdentityGuard.requireSelfOrAdmin` 的**管理员那一档**（`identity.role() == ROLE_ADMIN`）—— 即只有管理员读得到这个收件箱。

/** 拿到一个可用的收件人号吗。`getMerchantId()` 在未登录时可回 `'null'` / `'undefined'` 字面串
 *  （它读 `sessionStorage`，而 `sessionStorage.setItem(k, null)` 存的是字符串 `"null"`）——
 *  这两个值**必须**当没登录，否则会拿它们去请求 ⇒ 403（传了别人的号）。同 `ChatRoom.vue:185`。 */
const usableId = computed(() => {
  const v = toId.value
  if (!isLoggedIn() || v === null || v === undefined) return ''
  const s = String(v)
  return s === '' || s === 'null' || s === 'undefined' ? '' : s
})

const truncated = computed(() => !onlyUnread.value && total.value > rows.value.length)

// ── 软引用容错（用户口径，建表块注释 + pack 同步）─────────────────────────────
// `TargetType` / `TargetId` 是**软引用**（表上刻意**不设 FK**：有 FK 则全量重导后目标失效会
// 让插入报错或触发级联删）。代价是**渲染时**必须容错：目标可能已经不存在了。
//
// ⚠️ 解析表**今天故意是空的** —— 块 1 只发公告 / 系统通知，两者都不带关联对象。
//    块 2–6 各自加一行（`goods` / `user` / `comment` / `order` …）。
//    ⇒ 于是本页**今天拿不到 `kind: 'ok'`**：带靶子的通知只有 `raw`（认不出类型）或 `gone`
//      （解析器说没有）两种画法。两条都**不空白、不崩**，这正是口径要的。
const TARGET_RESOLVERS = {}

/**
 * 把一行的软引用画成一段文字。
 *
 * @returns {null | {kind:'ok'|'raw'|'gone', text:string, to?:string|null}}
 *          `null` = 这行本来就没有关联对象（画都不画）；
 *          `ok`   = 解析出来了（将来 `to` 可点跳转）；
 *          `raw`  = 有靶子但本页不认识这个 `TargetType`（画原文，别猜、别谎称已删）；
 *          `gone` = 认识这个类型，但对象没了 ⇒「（相关对象已删除）」（用户口径逐字）。
 */
function resolveTarget(row) {
  const type = row.TargetType
  const id = row.TargetId
  if (isBlank(type) && isBlank(id)) return null
  try {
    // ⚠️ 必须 `hasOwnProperty`，不能 `TARGET_RESOLVERS[type]` 直接取 ——
    //    后者对 `'constructor'` / `'toString'` 这类**原型链上的键**会拿到一个**函数**，
    //    然后被当成解析器调用（同 `config/notify-events.js:13-16` 那条，同一个坑）。
    const has = Object.prototype.hasOwnProperty.call(TARGET_RESOLVERS, type)
    if (!has || typeof TARGET_RESOLVERS[type] !== 'function') {
      return { kind: 'raw', text: `关联：${isBlank(type) ? '?' : type} · ${isBlank(id) ? '?' : id}` }
    }
    const hit = TARGET_RESOLVERS[type](id)
    if (!hit) return { kind: 'gone', text: '（相关对象已删除）' }
    return { kind: 'ok', text: hit.text, to: hit.to || null }
  } catch (e) {
    // 解析器自己炸了（网络 / 数据脏）—— 这是**渲染**路径，绝不能把异常抛给 Vue
    // （抛出去就是整页白屏，而口径要的是「不崩、不空白」）。降级成「已删除」并留一行日志。
    console.error('NOTIFY 目标解析失败', type, id, e)
    return { kind: 'gone', text: '（相关对象已删除）' }
  }
}

function isBlank(v) {
  return v === null || v === undefined || String(v).trim() === ''
}

/** 首屏 / 每次重载：拉列表。 */
async function load() {
  if (!usableId.value) {
    // 未登录：**不发请求**。发了就是 401（`http.js` 的 `handleUnauthorized` 会把公开页踢回
    // `/login`）—— 壳里 `fetchNotifyUnread` 先判 `isLoggedIn()` 是同一条理由。
    rows.value = []
    total.value = 0
    unread.value = 0
    loading.value = false
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await notifyList({
      toId: usableId.value,
      toType: toType.value,
      // ⚠️ 只在勾选时带这个键：`notify.js` 的 `q()` 只丢 `''`/`null`/`undefined`，**不丢 `false`**
      //    ⇒ 传 `false` 会发出 `onlyUnread=false` 这个**有值的键**。后端 `Boolean` 绑得出 false、
      //    结果一样，但「有值」与「没给」在日志里是两回事 —— 没勾就不该有这个参数。
      onlyUnread: onlyUnread.value ? true : undefined,
      limit: LIMIT,
    })
    if (!data || !data.success) {
      error.value = (data && data.msg) || '加载失败'
      rows.value = []
      return
    }
    const list = Array.isArray(data.list) ? data.list : []
    // 逐行预先算好靶子文案（模板里再算会每渲染一次算一次）。
    rows.value = list.map((r) => ({ ...r, _target: resolveTarget(r) }))
    total.value = Number(data.total) || 0
    unread.value = Number(data.unread) || 0
  } catch (e) {
    // 403（传了别人的号）/ 网络错误都走这支。`handleApiError` 对 `HttpError` 会弹一次 ——
    // 保留它：403 是**该被看见**的（不是「查不到」）。
    handleApiError(e, '加载系统通知失败')
    error.value = '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/**
 * 点一行：① 标已读 ② 若有可跳转的靶子则跳过去。
 *
 * ⚠️ **先本地改、再发请求**（乐观更新）：点击立刻去掉左侧红条，不让用户盯着一次往返。
 *    请求回来若 `success` 为假，**回滚**这一行的 `unread` —— 别静默留一个假象。
 * ⚠️ `updated:0` **不是失败**（`notify.js:64-66`：它同时覆盖「已经标过了」与「这 Id 不属于你」）
 *    ⇒ 判据只用 `success`。
 */
async function openRow(row) {
  if (row._target && row._target.to) {
    window.location.hash = row._target.to
    // 跳走也照样标已读：用户已经读过这条了。
  }
  if (!row.unread) return
  row.unread = false
  unread.value = Math.max(0, unread.value - 1)
  try {
    const data = await notifyRead({ toId: usableId.value, toType: toType.value, id: row.Id })
    if (!data || !data.success) {
      row.unread = true
      unread.value += 1
    }
  } catch (e) {
    row.unread = true
    unread.value += 1
    handleApiError(e, '标记已读失败')
  }
}

/** 全部已读。 */
async function markAll() {
  if (!usableId.value || busy.value || unread.value === 0) return
  busy.value = true
  const snapshot = rows.value.map((r) => r.unread)
  const beforeUnread = unread.value
  rows.value.forEach((r) => { r.unread = false })
  unread.value = 0
  try {
    const data = await notifyReadAll({ toId: usableId.value, toType: toType.value })
    if (!data || !data.success) {
      snapshot.forEach((v, i) => { rows.value[i].unread = v })
      unread.value = beforeUnread
      error.value = (data && data.msg) || '操作失败'
    } else if (onlyUnread.value) {
      // 「仅看未读」时全标了已读 ⇒ 列表该空。不重拉（重拉也回空），本地清掉更快。
      rows.value = []
    }
  } catch (e) {
    snapshot.forEach((v, i) => { rows.value[i].unread = v })
    unread.value = beforeUnread
    handleApiError(e, '全部已读失败')
  } finally {
    busy.value = false
  }
}

// ── 事件类型 → 展示文字 ──────────────────────────────────────────────────────
// ⚠️ `isKnownEvent` 是**给工具条用的**（未知 code 要能看出来），不是给正文用的 ——
//    正文一律走 `eventLabel`，它认不出就回 code 原文（**任何一行都不许渲染成空白**，
//    `config/notify-events.js:6-11`）。这里的用法：已知 → 中文标签；未知 → 标签位置直接
//    画 code 原文并加 `unknown` 类（等宽 + 描边），一眼能看出「这不是个中文标签」。
function labelOf(code) {
  return isKnownEvent(code) ? eventLabel(code) : String(code == null ? '' : code)
}

// ── 时间 ────────────────────────────────────────────────────────────────────
// ⚠️ **必须补 `Z`**：后端 `DATE_FORMAT` 交回的是**无时区**的 `YYYY-MM-DD HH:MM:SS`，
//    而库里存的是 **UTC**（建表块注释）。不补 `Z` 则 `new Date()` 按**浏览器本地时区**解释
//    这串字 ⇒ 时间差一个时区。手法照 `views/admin/AuditPage.vue` 的 `toLocal`（同一个坑）。
function fmtTime(sqlTime) {
  if (!sqlTime) return ''
  const t = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(t.getTime())) return String(sqlTime)   // 解析不了就原样回 —— 不崩、不空白
  const p = (n) => String(n).padStart(2, '0')
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())} ${p(t.getHours())}:${p(t.getMinutes())}`
}

// 在两个壳之间切换（`/customer/notify` ⇄ `/merchant/notify`）时重载 —— 收件人换了。
// ⚠️ 在真实使用里这条边**几乎不会**走到（换壳意味着换登录身份，`LoginPage` 会把壳组件整个重建
//    ⇒ `onMounted` 自然再跑一次）；但 `AppShell` 里已有同款 `watch`（它按 `route.path` 清红点），
//    这里对齐写法，免得「壳换了、页没换」这种组合出现时列表停在旧收件人上。
watch(() => route.path, () => { load() })

onMounted(load)
</script>

<style scoped>
/* 根：承接壳里 `.page`（`AppShell.vue:424`，内距已归零）的交还 —— 与 `ChatRoom.vue` 的
   `.chat-room` 同款（同栏宽、同内距），两个页面在同一个壳里来回切时不该有不同的左右边距。 */
.notify-room {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s6) var(--s5) var(--s7);
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头 ─────────────────────────────────────────────────────────────────── */
.nhead {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  padding-bottom: var(--s4);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--s5);
}
.nhead h1 {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: 600;
  color: var(--ink);
}
.nhead .sub {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.nhead .hot {
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.grow { flex: 1; }
.switch {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  cursor: pointer;
}
.act {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border: 1px solid transparent;
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
  cursor: pointer;
}
.act:disabled {
  color: var(--ink-3);
  background: var(--surface-2);
  cursor: not-allowed;
}

/* ── 列表 ─────────────────────────────────────────────────────────────────── */
.nlist {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  overflow: hidden;
  background: var(--surface);
}
.nlist > li + li .nrow { border-top: 1px solid var(--line); }

.nrow {
  display: grid;
  grid-template-columns: 3px minmax(0, 1fr);
  width: 100%;
  text-align: left;
  font: inherit;
  color: inherit;
  background: none;
  border: 0;
  padding: 0;
  cursor: pointer;
}
.nrow:hover { background: var(--surface-2); }

/* 未读行：左侧一道 `--accent`（用户 2026-10-07 裁决：朱砂 = 价格 + 促销 + **未读提示**；
   顶栏红点用的也是它 ⇒ 同一件事在两处是同一个颜色）。已读行留一条透明占位，
   免得整列左右错位。 */
.nrow .bar {
  background: transparent;
}
.nrow.unread .bar {
  background: var(--accent);
}

.stack {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s4) var(--s5);
  min-width: 0;
}
.l1 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  min-width: 0;
}
.title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nrow.unread .title { color: var(--ink); }

/* 事件标签：已知 code 是普通小标签；未知 code 画 code 原文（等宽 + 描边），
   「这不是个中文标签」一眼可见 —— 前端不认识它，不是它没内容（`config/notify-events.js:6-11`）。 */
.ev {
  flex: none;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 var(--s2);
  white-space: nowrap;
}

.body {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  line-height: 1.55;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.l3 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  min-width: 0;
}
.tgt {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border-radius: var(--r1);
  padding: 0 var(--s2);
  background: var(--surface-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 不认识的目标类型：画原文，用等宽 + 描边区别于「点了能跳」的将来态。 */
.tgt.raw {
  font-family: var(--font-mono);
  background: transparent;
  border: 1px dashed var(--line-2);
}
/* 对象已删除：用警示色说清「这里本来有个东西，现在没了」—— 不是空白。 */
.tgt.gone {
  color: var(--warning-deep);
  background: var(--warning-ground);
}
.tm {
  margin-left: auto;
  flex: none;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

/* ── 各种「没有内容」的分支 ─────────────────────────────────────────────────── */
.hint {
  margin: var(--s6) 0;
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.hint.bad { color: var(--danger); }
</style>
