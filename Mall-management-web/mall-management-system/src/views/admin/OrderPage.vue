<template>
  <section class="a-ord">
    <!-- ── ① 状态条（与 `AccountsPage.vue` / `AuditPage.vue` 同款，不另立一套）────────
         三个大数 + 时区口径。⚠️ 「库内总单数」取自 `/admin/order/list` 的 `total`
         —— 那是**全表** `COUNT(*)`，**不是**本次筛选数（后端刻意分开回报，见 `AdminOrderService.list`）。
         本页所有时间列都是**无时区**的库内文本（`CreateTime` 是 `varchar(50)`，不是 `datetime`）
         ⇒ 唯一「错也看不出来」的地方就是时区偏移，摆在台面上即可一眼核。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ total }}</b>
        <span>库内总单数（全表，非本次筛选）</span>
      </div>
      <div class="a-stat">
        <b>{{ rows.length }}</b>
        <span>本次显示{{ truncated ? '（已达上限）' : '' }}</span>
      </div>
      <div class="a-stat">
        <!-- ⚠️ 监控拉失败时这两个数显示 `—` 而**不是 `0`** —— 「真的是 0」与「没查出来」
             长得一模一样是最坏的一种错（同 `AdminOrderService.list` 对未知状态的处置口径）。 -->
        <b>{{ monitorFailed ? '—' : monitor.abnormalState }}</b>
        <span>异常态（未付款却已发货/收货）</span>
      </div>
      <div class="a-stat">
        <b>{{ monitorFailed ? '—' : monitor.unfinished }}</b>
        <span>未完成（未收货）</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">UTC</span>
        <span>库内时钟 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── 页内 tab ─────────────────────────────────────────────────────────────
         两块（`docs/agent/c3-modules.md` §五：「子项页含多块 ⇒ 页内 tab，tab 数 2–4 个」）。
         ⚠️ **「介入」不是第三个 tab**（用户口径是「全量视图 + 异常监控 + 介入」三件事）——
         介入是**行级动作**：它必须出现在**那一行的旁边**，拆成独立 tab 就得让管理员先记住单号
         再切页面。⇒ 它留在「订单全量」的卡片里，是**第三件事、不是第三页**。 -->
    <nav class="a-tabs">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        class="a-tab"
        :class="{ on: tab === t.key }"
        @click="tab = t.key"
      >
        {{ t.label }}<span class="a-tabn">{{ t.note() }}</span>
      </button>
    </nav>

    <!-- ══ ② 订单全量（tab 一）══════════════════════════════════════════════════ -->
    <template v-if="tab === 'list'">
      <h2 class="a-h2">订单全量 <span class="a-h2n">跨用户、跨商家（无归属约束）—— 这是管理端与订单域的分界</span></h2>

      <form class="a-filters" @submit.prevent="loadList">
        <label class="a-f">
          <span>买家</span>
          <input v-model.trim="f.CustomerId" type="text" placeholder="CustomerId" />
        </label>
        <label class="a-f">
          <span>商家</span>
          <input v-model.trim="f.MerchantId" type="text" placeholder="MerchantId" />
        </label>
        <label class="a-f">
          <span>状态</span>
          <select v-model="f.status">
            <option value="">全部</option>
            <option value="unpaid">未付款</option>
            <option value="paid">已付款未发货</option>
            <option value="shipped">已发货未收货</option>
            <option value="completed">已完成</option>
            <option value="abnormal">异常态</option>
          </select>
        </label>
        <!-- ⚠️ 时间筛**必须是文本框、不能换 `datetime-local`**：`CreateTime` 是字符串列，
             后端做的是**字典序**比较（`AdminOrderDao.selectOrders` 的注释写明），而
             `datetime-local` 产出的值带 `T` 分隔符（`2026-10-10T12:00`）—— `'T'(0x54)` 大于
             `' '(0x20)` ⇒ 「同一天的 12:00」会比「同一天的 12:00 整」**大**，筛选静默失准。
             格式依赖是 ISO 式零填充（`YYYY-MM-DD HH:MM:SS`），跟库里的写入格式**逐字**对齐。 -->
        <label class="a-f a-f-wide">
          <span>起始（含）</span>
          <input v-model.trim="f.from" type="text" placeholder="YYYY-MM-DD HH:MM:SS（须零填充）" />
        </label>
        <label class="a-f a-f-wide">
          <span>结束（含）</span>
          <input v-model.trim="f.to" type="text" placeholder="YYYY-MM-DD HH:MM:SS（须零填充）" />
        </label>
        <label class="a-f a-f-narrow">
          <span>上限</span>
          <input v-model.number="f.limit" type="number" min="1" max="500" />
        </label>
        <div class="a-f-actions">
          <button type="submit" class="a-btn a-btn-on" :disabled="loading">查询</button>
          <button type="button" class="a-btn" :disabled="loading" @click="resetFilters">清空</button>
        </div>
      </form>

      <p class="a-hint">
        未知的状态词**不当成「不筛」**：后端回 <code>success:false</code> 并把原词念回来
        —— 「筛错了一个词」与「这个状态确实没数据」在画面上都是空表，把前者说成后者是最坏的一种错。
        上限（默认 200 / 最大 <code>500</code>）只做**截断**不做真分页：<code>order</code> 表没有分页键。
      </p>
      <p v-if="truncated" class="a-note">
        本次结果已达取回上限：仅取回 {{ rows.length }} 条（按时间倒序），后面可能还有。
      </p>

      <p v-if="!rows.length" class="a-empty">{{ loading ? '加载中…' : '没有符合条件的订单。' }}</p>

      <ol v-else class="a-stream">
        <li v-for="r in rows" :key="r.OrderId" class="a-card" :class="{ 'is-abnormal': isAbnormal(r) }">
          <div class="a-card-l1">
            <b class="a-strong a-mono">{{ dash(r.OrderId) }}</b>
            <span class="a-badge" :class="statusBadge(r)">{{ statusLabel(r) }}</span>
            <span v-if="isAbnormal(r)" class="a-badge rejected">异常态</span>
            <span class="a-tag">¥{{ dash(r.Price) }}</span>
            <span class="a-tag">×{{ dash(r.Quantity) }}</span>
            <span class="a-grow"></span>
            <span class="a-dim">{{ toLocal(r.CreateTime) }}</span>
          </div>

          <div class="a-kv">
            <div><dt>买家</dt><dd class="a-mono">{{ dash(r.CustomerId) }}</dd></div>
            <div><dt>商家</dt><dd class="a-mono">{{ dash(r.MerchantId) }}</dd></div>
            <div><dt>商品</dt><dd>{{ dash(r.ProductInformation) }}</dd></div>
            <div><dt>结单</dt><dd>{{ toLocal(r.SettlementDate) }}</dd></div>
          </div>

          <!-- 介入 = **沿既有三条 CAS 边**（`pay` / `deliver` / `receive`），不是另一套写规则。
               ⚠️ 这里的 `actionsFor()` 只是**画几个按钮**，**不是闸** —— 真正的守卫是后端
               `OrderStateMachine`（CAS 失败就把原话回显在下面）。所以别把它当权限判据，
               也别因为这里没画就以为「后端做不到」。 -->
          <div class="a-acts">
            <template v-for="a in actionsFor(r)" :key="a">
              <button
                type="button"
                class="a-btn"
                :class="{ 'a-btn-danger': armed === r.OrderId + ':' + a }"
                :disabled="busyId !== null"
                @click="intervene(r, a)"
              >
                {{ armed === r.OrderId + ':' + a ? '再点一次确认：' + ACT_LABEL[a] : ACT_LABEL[a] }}
              </button>
            </template>
            <span v-if="!actionsFor(r).length" class="a-dim">已完成，无可推的边</span>
            <span class="a-grow"></span>
            <button type="button" class="a-btn" @click="toggleDetail(r)">
              {{ openId === r.OrderId ? '收起详情 ↑' : '详情与流转 ↓' }}
            </button>
          </div>

          <template v-if="openId === r.OrderId">
            <p v-if="detailLoading" class="a-empty">详情加载中…</p>
            <template v-else-if="detail && detail.row">
              <div class="a-kv">
                <div><dt>主键 Id</dt><dd class="a-mono">{{ dash(detail.row.Id) }}</dd></div>
                <div><dt>付款</dt><dd class="a-mono">{{ PAY_LABEL(detail.row) }}</dd></div>
                <div><dt>发货</dt><dd class="a-mono">{{ detail.row.DeliveryIf }}</dd></div>
                <div><dt>收货</dt><dd class="a-mono">{{ detail.row.ReceiveIf }}</dd></div>
              </div>
              <h3 class="a-h3">
                状态流转时间线<span class="a-h3n">
                  源：<code>order_state_transition</code> · 按 **OccurredAt** 升序（不是 Id —— 同单的
                  几条是并发 INSERT，Id 不保证与发生顺序一致）
                </span>
              </h3>
              <p v-if="!detail.timeline || !detail.timeline.length" class="a-empty">
                该单没有流转记录（早于 B3 起写的那批，或本单尚未发生任何 CAS 转移）。
              </p>
              <ol v-else class="a-tl">
                <li v-for="(e, i) in detail.timeline" :key="i">
                  <span class="a-mono a-dim">{{ toLocal(e.OccurredAt) }}</span>
                  <span class="a-tag">{{ dash(e.Action) }}</span>
                  <span class="a-mono">{{ dash(e.FromState) }} → {{ dash(e.ToState) }}</span>
                </li>
              </ol>
            </template>
            <p v-else class="a-empty">{{ detailMsg || '详情加载失败。' }}</p>
          </template>
        </li>
      </ol>
    </template>

    <!-- ══ ③ 异常监控（tab 二）═════════════════════════════════════════════════ -->
    <template v-else>
      <h2 class="a-h2">异常监控 <span class="a-h2n">**只读识别** —— 本页没有任何一个数字会让库发生改变</span></h2>

      <p class="a-hint">
        两个超时阈值（{{ monitorH.unpaidHours }}h 未付款 / {{ monitorH.undeliveredHours }}h 已付款未发货）
        是**拍出来的数**、**待业务裁**（本仓没有任何 SLA / 时效配置）—— 所以阈值**随接口回传**，
        本页不写死，改了后端这里自动跟着变。
      </p>

      <div class="a-grid">
        <div class="a-mcard">
          <b>{{ mv(monitor.unpaidTimeout) }}</b>
          <span>超 {{ monitorH.unpaidHours }}h 未付款</span>
          <button type="button" class="a-btn" @click="drill('unpaid')">筛未付款单 →</button>
          <small>⚠️ 该筛**比这个数宽**：含「还没超时的未付款单」，不是同一集合。</small>
        </div>
        <div class="a-mcard">
          <b>{{ mv(monitor.undeliveredTimeout) }}</b>
          <span>超 {{ monitorH.undeliveredHours }}h 已付款未发货</span>
          <button type="button" class="a-btn" @click="drill('paid')">筛已付款未发货 →</button>
          <small>⚠️ 同上：**比这个数宽**，含未超时的。</small>
        </div>
        <div class="a-mcard">
          <b>{{ mv(monitor.abnormalState) }}</b>
          <span>异常态（未付款却已发货/收货）</span>
          <button type="button" class="a-btn a-btn-on" @click="drill('abnormal')">筛异常态单 →</button>
          <small>✅ 这个筛与计数**同一条谓词**（`PayIf=0 AND (DeliveryIf=1 OR ReceiveIf=1)`），是同一集合。</small>
        </div>
        <div class="a-mcard">
          <b>{{ mv(monitor.unfinished) }}</b>
          <span>未完成（未收货）／ 库内共 {{ monitor.total }}</span>
          <button type="button" class="a-btn" @click="drill('')">看全部单 →</button>
          <small>全量视图默认按时间倒序。</small>
        </div>
      </div>

      <p class="a-note">
        ⚠️ 异常态**只识别、不自动处置**：本页不会替谁推进任何一单。要处置就得在「订单全量」里
        对**具体那一条**点介入（点两次确认），并且介入成功后**买卖双方都会被通知**。
      </p>
    </template>
  </section>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { orderAdminList, orderAdminDetail, orderAdminMonitor, orderAdminIntervene } from '../../api/order'

// ── 时区口径（照 `AccountsPage.vue`，逐字同源）──────────────────────────────
const tzLabel = computed(() => {
  const pad = (n) => String(n).padStart(2, '0')
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

/** 库内文本（`'YYYY-MM-DD HH:MM:SS'`，无时区）→ 浏览器本地时间。解析不了就原样回，不崩、不空白。 */
function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

const dash = (v) => (v === null || v === undefined || String(v).trim() === '' ? '—' : String(v))

// ── 反馈 ─────────────────────────────────────────────────────────────────────
const flash = reactive({ kind: 'ok', text: '' })
function say(kind, text) {
  flash.kind = kind
  flash.text = text
}
/** 后端 `{success:false,msg}` 或异常 → 一句人话。⚠️ **别覆盖 `msg`**：介入失败的三种文案各不相同
 *  （未实现 / 不认识 / CAS 原话），合并成一句「操作失败」就丢掉了唯一的区分信息。 */
function failOf(e) {
  if (e && e.status) return `HTTP ${e.status}`
  return (e && e.message) || String(e)
}

// ── tab（`note()` 写成函数：`rows` / `monitor` 在下面才声明，`const` 有 TDZ）──────
const tab = ref('list')
const TABS = [
  { key: 'list', label: '订单全量', note: () => `${rows.value.length} 行` },
  { key: 'mon', label: '异常监控', note: () => `${monitor.value.abnormalState} 异常态` },
]

// ── 全量视图 ─────────────────────────────────────────────────────────────────
const f = reactive({ CustomerId: '', MerchantId: '', status: '', from: '', to: '', limit: 200 })
const rows = ref([])
const total = ref(0)
/** ⚠️ 截断由**后端**判（`rows.size() >= 生效上限`），前端**不**拿全表 `total` 反推 ——
 *  筛 status 时 `total` **不随筛**，拿它比会**谎报「已截断」**（2026-10-10 修）。 */
const truncated = ref(false)
const loading = ref(false)
/** 正在处理的单（防重复点）。`null` = 空闲。 */
const busyId = ref(null)

async function loadList() {
  loading.value = true
  try {
    const data = await orderAdminList(f)
    if (!data || !data.success) {
      rows.value = []
      total.value = 0
      truncated.value = false
      say('bad', (data && data.msg) || '订单加载失败')
      return
    }
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
    truncated.value = !!data.truncated
  } catch (e) {
    rows.value = []
    total.value = 0
    truncated.value = false
    say('bad', `订单加载失败：${failOf(e)}`)
    console.error('admin orders', e)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  f.CustomerId = ''
  f.MerchantId = ''
  f.status = ''
  f.from = ''
  f.to = ''
  f.limit = 200
  loadList()
}

// ── 异常监控 ─────────────────────────────────────────────────────────────────
/** ⚠️ 阈值从响应里取，**不写死 24 / 48** —— 后端把它们当待裁常量回传（见 `AdminOrderDao`）。 */
const monitor = ref({ unpaidTimeout: 0, undeliveredTimeout: 0, abnormalState: 0, unfinished: 0, total: 0 })
const monitorH = ref({ unpaidHours: 24, undeliveredHours: 48 })
/** 载入失败时置 true —— 状态条上的两个数这时**是 0 但不可信**，须明说（0 与「查不到」长得一样）。 */
const monitorFailed = ref(false)
/** 监控数取值：失败时回 `—` 而不是 `0`（「事实是 0」与「没查出来」必须分得开）。 */
const mv = (v) => (monitorFailed.value ? '—' : v)

async function loadMonitor() {
  try {
    const data = await orderAdminMonitor()
    if (!data || !data.success) {
      monitorFailed.value = true
      say('bad', (data && data.msg) || '异常监控加载失败')
      return
    }
    monitorFailed.value = false
    monitor.value = data.row || monitor.value
    if (data.unpaidHours != null) monitorH.value.unpaidHours = data.unpaidHours
    if (data.undeliveredHours != null) monitorH.value.undeliveredHours = data.undeliveredHours
  } catch (e) {
    monitorFailed.value = true
    say('bad', `异常监控加载失败：${failOf(e)}`)
    console.error('admin order monitor', e)
  }
}

/** 从监控卡跳进全量视图并带上筛选 —— 复用**同一套**筛选状态，不另起一份。 */
function drill(status) {
  f.status = status
  tab.value = 'list'
  loadList()
}

// ── 状态位 → 人话 ────────────────────────────────────────────────────────────
/** 三位状态机的 5 个合法态 + 1 个非法态（与 `AdminOrderDao.statusWhere` 同一套判据，别另编）。 */
function isAbnormal(r) {
  return Number(r.PayIf) === 0 && (Number(r.DeliveryIf) === 1 || Number(r.ReceiveIf) === 1)
}
function statusLabel(r) {
  if (isAbnormal(r)) return '异常态'
  if (Number(r.ReceiveIf) === 1) return '已完成'
  if (Number(r.DeliveryIf) === 1) return '已发货未收货'
  if (Number(r.PayIf) === 1) return '已付款未发货'
  return '未付款'
}
function statusBadge(r) {
  if (isAbnormal(r)) return 'rejected'
  if (Number(r.ReceiveIf) === 1) return 'approved'
  return 'pending'
}
const PAY_LABEL = (row) => (Number(row.PayIf) === 1 ? '已付款' : '未付款')

// ── 介入：沿既有三条 CAS 边（`pay` / `deliver` / `receive`）────────────────────
const ACT_LABEL = { pay: '强制付款', deliver: '强制发货', receive: '强制完成' }

/**
 * 按当前状态位**建议**可推的边。⚠️ **这只是画几个按钮，不是闸** ——
 * 守卫在后端 `OrderStateMachine`（CAS），这里漏画或误画都不会让非法转移通过；
 * 反过来，这里没画也不代表后端做不到（三个动作后端都接）。
 *   · `PayIf=0` ⇒ 可推 `pay`（异常态也走这支：付款是把它拉回合法态的那条边）；
 *   · 已付款未发货 ⇒ 可推 `deliver`；已发货未收货 ⇒ 可推 `receive`；已完成 ⇒ 无边可推。
 */
function actionsFor(r) {
  if (Number(r.PayIf) === 0) return ['pay']
  if (Number(r.DeliveryIf) === 0) return ['deliver']
  if (Number(r.ReceiveIf) === 0) return ['receive']
  return []
}

/** 已「上膛」的那颗按钮（`OrderId:action`）。**点了第一次不会发请求** —— 见 `intervene`。 */
const armed = ref(null)

/**
 * 介入。**两次点击**才算数（第一次上膛、第二次执行）—— 理由是这条链会**改状态 + 通知双方**，
 * 是不可逆的对外动作；而本仓管理页的既有惯例里**没有** `confirm()`/`alert()`
 * （`eslintConfig` 的 `env` 里没有 `browser`，用浏览器全局会触发 `no-undef`），
 * 提示一律走页顶的 flash 条 ⇒ 二次确认就落在按钮自己身上。
 *
 * ⚠️ 成功后**重新拉列表**（状态位变了，卡片上的按钮集也会变）；失败时把后端 `msg`
 * **原样**显示 —— 三种失败（未实现 / 不认识 / CAS 原话）必须分得出来。
 */
async function intervene(r, action) {
  const key = r.OrderId + ':' + action
  if (armed.value !== key) {
    armed.value = key
    say('ok', `再点一次「${ACT_LABEL[action]}」以确认：订单 ${r.OrderId}（成功后会通知买卖双方）`)
    return
  }
  armed.value = null
  busyId.value = r.OrderId
  try {
    const data = await orderAdminIntervene({ OrderId: r.OrderId, Action: action })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '介入失败')
      return
    }
    say('ok', `已${ACT_LABEL[action]}：订单 ${r.OrderId}。通知已投递（见下条口径）。`)
    await loadList()
    if (openId.value === r.OrderId) await loadDetail(r.OrderId)
  } catch (e) {
    say('bad', `介入失败：${failOf(e)}`)
    console.error('admin order intervene', e)
  } finally {
    busyId.value = null
  }
}

// ── 详情 + 时间线（按需加载，一次一条）────────────────────────────────────────
const openId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const detailMsg = ref('')

async function loadDetail(orderId) {
  detailLoading.value = true
  detailMsg.value = ''
  try {
    const data = await orderAdminDetail(orderId)
    if (!data || !data.success) {
      detail.value = null
      detailMsg.value = (data && data.msg) || '详情加载失败'
      return
    }
    detail.value = data
  } catch (e) {
    detail.value = null
    detailMsg.value = `详情加载失败：${failOf(e)}`
    console.error('admin order detail', e)
  } finally {
    detailLoading.value = false
  }
}

function toggleDetail(r) {
  if (openId.value === r.OrderId) {
    openId.value = null
    return
  }
  openId.value = r.OrderId
  detail.value = null
  loadDetail(r.OrderId)
}

onMounted(() => {
  loadList()
  loadMonitor()
})
</script>

<style scoped>
.a-ord {
  max-width: 1120px;
}

/* ── ① 状态条（与 `AccountsPage.vue` / `AuditPage.vue` 同款）──────────────── */
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

/* ── 分段标题 ─────────────────────────────────────────────────────────────── */
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
.a-ord > .a-h2:first-of-type {
  margin-top: 0;
}
.a-h3 {
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
  margin: var(--s5) 0 var(--s3);
}
.a-h3n {
  margin-left: var(--s2);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}

/* ── 页内 tab（照 `AccountsPage.vue`，逐字同源 —— `c3-modules.md` §五 要求全管理端一套） */
.a-tabs {
  display: flex;
  gap: var(--s2);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--s5);
}
.a-tab {
  appearance: none;
  border: 0;
  background: none;
  cursor: pointer;
  padding: var(--s3) var(--s4);
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.a-tab:hover {
  color: var(--ink);
}
.a-tab.on {
  color: var(--ink);
  font-weight: var(--fw-semi);
  border-bottom-color: var(--brand-ink);
}
.a-tabn {
  margin-left: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-tab.on .a-tabn {
  color: var(--brand-ink);
}

/* ── 筛选条（照 `AccountsPage.vue` / `AuditPage.vue`）────────────────────── */
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
.a-f-wide {
  flex: 1 1 260px;
  min-width: 200px;
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
/* 「上膛」态 = 危险动作（同 `AccountsPage.vue` 的 `.a-btn-danger`）—— 与普通按钮在颜色上立刻分得开。 */
.a-btn-danger {
  color: var(--danger);
  background: var(--danger-ground);
  border-color: var(--danger);
}
.a-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.a-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  margin: var(--s2) 0 var(--s5);
}
.a-hint code,
.a-h3n code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.a-note {
  font-size: var(--fs-xs);
  color: var(--warning);
  background: var(--warning-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-top: var(--s3);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6);
  text-align: center;
}

/* ── 卡片式列表 ──────────────────────────────────────────────────────────── */
.a-stream {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  margin-top: var(--s3);
}
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
}
/* 异常态的行：左侧一道 `--danger`。⚠️ 是**边框**不是「侧边强调条」——
   `tokens.css` 那条房规禁的是「选中态用侧边条」，这里是**状态语义**（同 `.a-card.banned`）。 */
.a-card.is-abnormal {
  border-left: 3px solid var(--danger);
}
.a-card-l1 {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
}
.a-strong {
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.a-grow {
  flex: 1;
}
.a-tag {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border-radius: var(--r1);
  padding: 2px 6px;
}
.a-badge {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.a-badge.pending,
.a-badge.warn {
  color: var(--warning-deep);
  background: var(--warning-ground);
  border-color: transparent;
}
.a-badge.approved {
  color: var(--success-deep);
  background: var(--success-ground);
  border-color: transparent;
}
.a-badge.rejected {
  color: var(--danger);
  background: var(--danger-ground);
  border-color: transparent;
}
.a-dim {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-mono {
  font-family: var(--font-mono);
}

/* 字段清单：`dt` 标签 + `dd` 值，两列流式排布（宽窄都能读，不像宽表那样在窄档塌掉）。 */
.a-kv {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--s2) var(--s5);
  margin: var(--s3) 0 0;
}
.a-kv > div {
  display: flex;
  gap: var(--s2);
  min-width: 0;
  font-size: var(--fs-xs);
}
.a-kv dt {
  flex: none;
  color: var(--ink-3);
  min-width: 64px;
}
.a-kv dd {
  margin: 0;
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.a-acts {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  margin-top: var(--s3);
}

/* ── 状态流转时间线 ───────────────────────────────────────────────────────── */
.a-tl {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  margin: 0;
}
.a-tl li {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  background: var(--surface-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}

/* ── 异常监控四卡 ─────────────────────────────────────────────────────────── */
.a-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--s4);
}
.a-mcard {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
}
.a-mcard b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semi);
  line-height: 1.1;
  color: var(--ink);
}
.a-mcard span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-mcard small {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.6;
}
</style>
