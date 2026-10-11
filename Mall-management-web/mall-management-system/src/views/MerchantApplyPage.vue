<template>
  <main class="uc-main">
    <div class="page-h">
      <h2 class="page-h-t">商家入驻</h2>
      <span class="page-h-n">
        共 {{ rows.length }} 条申请<template v-if="pendingOfMine > 0"> · 待审 {{ pendingOfMine }}</template>
      </span>
    </div>

    <p v-if="!who" class="ph-note">
      认不出当前账号（会话里没有用户名）—— 请先<b>退出后重新登录</b>再来提交申请。
    </p>

    <template v-else>
      <!-- ── ① 提交申请 ─────────────────────────────────────────────────────
           ⚠️ 每次提交都**新增一条**，不是覆盖上一条（后端 `insert`，见 `api/merchant.js`）——
           表单下方那句提示是刻意写明的，免得申请人以为「改一改再交就能覆盖」。 -->
      <section class="card">
        <h3 class="card-h">提交入驻申请</h3>

        <form class="f" @submit.prevent="submit">
          <label class="fld">
            <span>店铺名称 <i>必填</i></span>
            <input v-model.trim="form.shopName" type="text" maxlength="64" placeholder="例如：米可数码专营店" />
          </label>
          <label class="fld">
            <span>营业执照号 <i>必填</i></span>
            <input v-model.trim="form.licenseNo" type="text" maxlength="64" placeholder="统一社会信用代码 / 注册号" />
          </label>
          <label class="fld">
            <span>联系人</span>
            <input v-model.trim="form.contactName" type="text" maxlength="32" placeholder="真实姓名" />
          </label>
          <label class="fld">
            <span>联系电话</span>
            <input v-model.trim="form.contactPhone" type="text" maxlength="20" placeholder="手机号 / 固定电话" />
          </label>
          <label class="fld fld-wide">
            <span>店铺地址</span>
            <input v-model.trim="form.shopAddress" type="text" maxlength="128" placeholder="经营场所地址" />
          </label>
          <label class="fld fld-wide">
            <span>资质图地址</span>
            <input v-model.trim="form.licenseImage" type="text" maxlength="255" placeholder="图片 URL / 路径（本块没有上传入口，留空即可）" />
          </label>

          <div class="f-acts">
            <button type="submit" class="btn" :disabled="busy">
              <IconGlyph name="ok" />{{ busy ? '提交中…' : '提交申请' }}
            </button>
            <button type="button" class="btn btn-ghost" :disabled="busy" @click="resetForm">清空</button>
          </div>
        </form>

        <p v-if="formErr" class="err">{{ formErr }}</p>
        <p v-if="okMsg" class="done-note">{{ okMsg }}</p>

        <p class="tip">
          ⚠️ 提交<b>不等于</b>通过：申请只是躺在表里等管理员审核，通过前你仍然是<b>普通客户</b>；
          每次提交都会<b>新增一条</b>记录（不会覆盖旧的）。
          审核通过后需<b>重新登录</b>才会进入商家端。
        </p>
      </section>

      <!-- ── ② 我的申请 ───────────────────────────────────────────────────── -->
      <section class="card">
        <div class="card-h-row">
          <h3 class="card-h">我的申请</h3>
          <button type="button" class="btn btn-ghost btn-sm" :disabled="loading" @click="loadMine">
            {{ loading ? '刷新中…' : '刷新' }}
          </button>
        </div>

        <p v-if="err" class="err">{{ err }}</p>
        <p v-else-if="loading && rows.length === 0" class="ph-note">加载中…</p>
        <p v-else-if="rows.length === 0" class="ph-note">还没有提交过入驻申请。</p>

        <ol v-else class="apps">
          <li v-for="r in rows" :key="r.Id" class="app" :class="r.Status">
            <div class="app-l1">
              <b class="app-shop">{{ r.ShopName }}</b>
              <span class="badge" :class="r.Status"><IconGlyph :name="statusGlyph(r.Status)" />{{ statusLabel(r.Status) }}</span>
              <span class="grow"></span>
              <span class="dim">{{ fmt(r.SubmittedAt) }}</span>
            </div>
            <div class="app-l2">
              <span class="dim">执照号 {{ dash(r.LicenseNo) }}</span>
              <span class="dim">批次 <code>{{ dash(r.BatchId) }}</code></span>
              <span v-if="r.ReviewedAt" class="dim">审毕 {{ fmt(r.ReviewedAt) }}</span>
            </div>
            <p v-if="r.Status === 'rejected'" class="why">
              驳回理由：{{ dash(r.RejectReason) }}
            </p>
            <p v-else-if="r.Status === 'approved'" class="done">
              已通过 —— 重新登录后即可进入商家端。
            </p>
          </li>
        </ol>

        <p v-if="truncated" class="tip">只显示了最近 {{ rows.length }} 条（共 {{ total }} 条）。</p>
      </section>
    </template>
  </main>
</template>

<!--
  商家入驻申请页（C3 块 2〈用户块〉，2026-10-09）—— `/customer/user/apply` 的真页。

  【⚠️ 为什么挂在**用户端**壳下，而不是商家端】提交申请的人**还没有** `merchantid`；
  放进商家壳（`meta.roles: ['2','3']`）等于要求「先有商家身份才能申请当商家」—— 逻辑闭环。
  同理，**被冻结降级的商家**（`login.Role` 已被改回 1、人在客户壳里）也走这一页重新申请，
  这正是「降级五阶段」第 ④ 步在界面上的落点。

  【它消费后端 2 个端点】`/merchant/apply`（`MerchantApplicationController`）与
  `/merchant/myApplications`，均经 `api/merchant.js` 包一层 —— 本页**不直接**碰 `http`。
  ⚠️ 提交后会顺手往**管理端的共享收件箱**投一条 `merchant.apply.submitted`
  （`ToType='admin'` / `ToId='*'`，见 `MerchantApplicationController`）—— 那是**后端**的行为，
  本页不参与，也不会因此多发一次请求。

  【入口】用户中心左菜单的「商家入驻」项（`router/index.js:72` 同行追加，`sub: 'apply'`）；
  路由记录在 `router/index.js` 文件尾（`addRoute('customerUser', …)`），不在字面数组里。

  【视觉】与 `HistoricalOrders.vue` / `UserInfoPage.vue` **同槽位**（`.user-page` 的右栏）
  ⇒ 根元素必须是 `<main class="uc-main">`、自带 `flex:1; min-width:0`，页头沿用 `.page-h` 三件。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getUsername } from '../api/auth'
import { merchantApply, merchantMyApplications } from '../api/merchant'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行的惯例见各页同款注释

// ── 申请人 ───────────────────────────────────────────────────────────────────
// ⚠️ `getUsername()` 读 `sessionStorage`，未登录时可能回**字面串** `'null'` / `'undefined'`
//    （`sessionStorage.setItem(k, null)` 存的就是字符串 `"null"`）—— 与 `NotifyInbox.vue:108-113`
//    同一条判据：这两个值**必须**当「没登录」，否则会拿它们去请求（后端会当成一个不存在的用户名）。
const who = computed(() => {
  const v = getUsername()
  if (v === null || v === undefined) return ''
  const s = String(v)
  return s === '' || s === 'null' || s === 'undefined' ? '' : s
})

const pad = (n) => String(n).padStart(2, '0')
const dash = (v) => (v === null || v === undefined || String(v).trim() === '' ? '—' : String(v))

/** 库内时钟（无时区文本，**UTC**）→ 浏览器本地时间。末尾那个 `Z` 不能省（同 `AuditPage.vue`）。 */
function fmt(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function statusLabel(s) {
  if (s === 'pending') return '待审'
  if (s === 'approved') return '已通过'
  if (s === 'rejected') return '已驳回'
  return dash(s)
}
// 状态徽章左侧那枚字形（2026-10-11 补图标那一笔；与 `admin/AccountsPage.vue` 同款 —— 两边判的是**同一批**
// `merchant_application.Status`）。未知态给 `wait`：原文已由 `statusLabel` 显示。
const statusGlyph = (s) => (s === 'approved' ? 'ok' : s === 'rejected' ? 'no' : 'wait')

// ── 表单 ─────────────────────────────────────────────────────────────────────
const form = reactive({
  shopName: '',
  licenseNo: '',
  contactName: '',
  contactPhone: '',
  shopAddress: '',
  licenseImage: '',
})
const busy = ref(false)
/** ① 表单那一段自己的反馈 —— **不与 ② 列表的 `err` 共用**：两者同时在屏上，混用会让「提交失败」显示在列表头上。 */
const formErr = ref('')
const okMsg = ref('')

function resetForm() {
  form.shopName = ''
  form.licenseNo = ''
  form.contactName = ''
  form.contactPhone = ''
  form.shopAddress = ''
  form.licenseImage = ''
}

/**
 * 提交。
 * ⚠️ 前端这两条必填校验与后端**同一句话**（`MerchantApplicationService`：`'店铺名称与营业执照号为必填'`）——
 *    前端先拦是为了不让用户白等一次往返，**不是**判据（判据在后端）。
 */
async function submit() {
  if (!form.shopName || !form.licenseNo) {
    formErr.value = '店铺名称与营业执照号为必填。'
    return
  }
  busy.value = true
  formErr.value = ''
  okMsg.value = ''
  try {
    const data = await merchantApply({ username: who.value, ...form })
    if (!data || !data.success) {
      formErr.value = (data && data.msg) || '提交失败'
      return
    }
    okMsg.value = `已提交（批次 ${dash(data.batchId)}）—— 等管理员审核，通过后需重新登录。`
    resetForm()
    await loadMine()
  } catch (e) {
    formErr.value = `提交失败：${e && e.status ? `HTTP ${e.status}` : (e && e.message) || e}`
    console.error('merchant apply', e)
  } finally {
    busy.value = false
  }
}

// ── 我的申请 ─────────────────────────────────────────────────────────────────
const LIMIT = 50
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const err = ref('')

const truncated = computed(() => total.value > rows.value.length)
const pendingOfMine = computed(() => rows.value.filter((r) => r.Status === 'pending').length)

async function loadMine() {
  if (!who.value) return
  loading.value = true
  try {
    const data = await merchantMyApplications({ username: who.value, limit: LIMIT })
    if (!data || !data.success) {
      rows.value = []
      err.value = (data && data.msg) || '加载失败'
      return
    }
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
  } catch (e) {
    rows.value = []
    total.value = 0
    err.value = `加载失败：${e && e.status ? `HTTP ${e.status}` : (e && e.message) || e}`
    console.error('merchant myApplications', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadMine)
</script>

<style scoped>
/* ── 本页根（与 `HistoricalOrders.vue` / `FriendRoom.vue` 同槽位、同款）──────── */
.uc-main {
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头 ─────────────────────────────────────────────────────────────────── */
.page-h {
  display: flex;
  align-items: baseline;
  gap: var(--s4);
  margin-bottom: var(--s5);
}
.page-h-t {
  font-family: var(--font-display);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  letter-spacing: 0.5px;
  line-height: 1.2;
  margin: 0;
}
.page-h-n {
  font-size: var(--fs-sm);
  color: var(--ink-3);
}

/* ── 卡片 ─────────────────────────────────────────────────────────────────── */
.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
  margin-bottom: var(--s5);
}
.card-h {
  margin: 0 0 var(--s4);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.card-h-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin-bottom: var(--s4);
}
.card-h-row .card-h {
  margin: 0;
  flex: 1;
}

/* ── 表单 ─────────────────────────────────────────────────────────────────── */
.f {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s4);
}
.fld {
  flex: 1 1 220px;
  min-width: 180px;
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.fld-wide {
  flex-basis: 100%;
}
.fld i {
  font-style: normal;
  color: var(--accent);
}
.fld input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.fld input:focus {
  outline: none;
  border-color: var(--brand-ink);
}
.f-acts {
  flex-basis: 100%;
  display: flex;
  gap: var(--s3);
  margin-top: var(--s2);
}

/* ── 按钮 ─────────────────────────────────────────────────────────────────── */
.btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border: 1px solid var(--brand-ink);
  border-radius: var(--r2);
  padding: var(--s2) var(--s5);
  cursor: pointer;
}
.btn:hover {
  background: var(--brand-tint);
}
.btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.btn-ghost {
  color: var(--ink-2);
  background: var(--surface-2);
  border-color: var(--line-2);
}
.btn-ghost:hover {
  color: var(--ink);
  background: var(--surface-3);
}
.btn-sm {
  font-size: var(--fs-xs);
  padding: var(--s1) var(--s3);
}

/* ── 提示 / 错误 ──────────────────────────────────────────────────────────── */
.tip,
.ph-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  margin: var(--s4) 0 0;
}
.tip code,
.app code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.err {
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin: var(--s4) 0 0;
}
.done-note {
  font-size: var(--fs-sm);
  color: var(--success-deep);
  background: var(--success-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin: var(--s4) 0 0;
}
.ph-note + .tip {
  margin-top: var(--s3);
}

/* ── 我的申请列表 ─────────────────────────────────────────────────────────── */
.apps {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}
.app {
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  background: var(--surface-2);
}
.app.approved {
  border-left: 3px solid var(--success);
}
.app.rejected {
  border-left: 3px solid var(--danger);
}
.app.pending {
  border-left: 3px solid var(--warning);
}
.app-l1 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
}
.app-shop {
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.grow {
  flex: 1;
}
.dim {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.badge {
  flex: none;
  font-size: var(--fs-xs);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
  background: var(--surface-3);
  color: var(--ink-2);
}
.badge.pending {
  color: var(--warning-deep);
  background: var(--warning-ground);
}
.badge.approved {
  color: var(--success-deep);
  background: var(--success-ground);
}
.badge.rejected {
  color: var(--danger);
  background: var(--danger-ground);
}
.app-l2 {
  display: flex;
  gap: var(--s4);
  flex-wrap: wrap;
  margin-top: var(--s2);
}
.why,
.done {
  font-size: var(--fs-xs);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
  margin: var(--s3) 0 0;
}
.why {
  color: var(--danger);
  background: var(--danger-ground);
}
.done {
  color: var(--success-deep);
  background: var(--success-ground);
}
</style>
