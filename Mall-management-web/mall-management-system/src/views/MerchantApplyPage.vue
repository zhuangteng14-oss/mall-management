<template>
  <main class="uc-main">
    <!-- A 版线框（MERCHANT / APPLICATION REQUIRED）：页头 + 入驻说明横幅 + 表单/规则两栏 + 已提交申请。
         三个数据入口一个字不动：`applyType` / `form` 的 v-model、`submit` / `resetForm`、`loadMine`。 -->
    <section class="page-head">
      <div>
        <div class="kicker">MERCHANT / APPLICATION REQUIRED</div>
        <h1>{{ applyType === 'brand' ? '品牌加盟申请' : '先开通商家身份' }}</h1>
        <!-- 2026-10-19 用户令：删掉顶部那行品牌说明小字，页面从标题直接进横幅。 -->
      </div>
      <div class="head-actions">
        <button type="button" class="btn" :disabled="loading" @click="loadMine">
          {{ loading ? '刷新中…' : '刷新记录' }}
        </button>
        <router-link class="btn btn-ghost" :to="$route.path.startsWith('/merchant') ? '/merchant/user/info' : '/customer/user/info'">返回个人中心</router-link>
      </div>
    </section>

    <p v-if="!who" class="ph-note">
      认不出当前账号（会话里没有用户名）—— 请先<b>退出后重新登录</b>再来提交申请。
    </p>

    <template v-else>
      <!-- ── ① 入驻说明横幅 ───────────────────────────────────────────────── -->
      <section class="join-banner">
        <div>
          <div class="kicker">THE ONLY WAY TO MERCHANT</div>
          <h2 :class="{ 'brand-gold': applyType === 'brand' }">{{ applyType === 'brand' ? '品牌不是另一个账号，' : '入驻不是换号，' }}<br />{{ applyType === 'brand' ? '是商家之上的一层认证。' : '是给同一个账户加一层能力。' }}</h2>
        </div>
        <div class="jb-actions">
          <div class="jb-state">
            <span class="kicker">CURRENT STATUS</span>
            <b>{{ rows.length ? statusLabel(rows[0].Status) : '尚未提交' }}</b>
            <span class="jb-meta">共 {{ rows.length }} 条申请<template v-if="pendingOfMine > 0"> · 待审 {{ pendingOfMine }}</template></span>
          </div>
        </div>
      </section>

      <!-- ── ② 表单 panel：左侧字段、右侧规则/进度 ─────────────────────────── -->
      <div class="join-layout">
        <section class="join-form">
          <!-- ── 提交申请 ─────────────────────────────────────────────────────
               ⚠️ 每次提交都**新增一条**，不是覆盖上一条（后端 `insert`，见 `api/merchant.js`）——
               表单下方那句提示是刻意写明的，免得申请人以为「改一改再交就能覆盖」。 -->
          <form class="f" @submit.prevent="submit">
            <div class="form-section">
              <div class="section-title">
                <h2>{{ applyType === 'brand' ? '品牌信息' : '店铺信息' }}</h2>
                <span class="tiny">STEP 01 / {{ applyType === 'brand' ? 'BRAND' : 'SHOP' }}</span>
              </div>

              <!-- 批四第二批（2026-10-19）：申请类型。品牌加盟只对已入驻商家开放，
                   前端让选、后端用 `selectMerchantId` 判 —— 前端不猜身份，也不因此多发一次请求。 -->
              <!-- 2026-10-19 用户令：按身份二选一 —— 普通买家只看得到「商家入驻」，
                   已入驻商家只看得到「品牌加盟」；不再把两个选项同时摆给所有人。 -->
              <div class="field">
                <span class="field-label">申请类型</span>
                <div class="type-row">
                  <label v-if="!isMerchantAccount" class="type-opt"><input v-model="applyType" type="radio" value="join" /> 商家入驻</label>
                  <label v-else class="type-opt"><input v-model="applyType" type="radio" value="brand" /> 品牌加盟</label>
                </div>
              </div>

              <div class="form-grid">
                <label class="field">
                  <span>店铺名称 <i>必填</i></span>
                  <input v-model.trim="form.shopName" type="text" maxlength="64" placeholder="例如：米可数码专营店" />
                </label>
                <label class="field">
                  <span>营业执照号 <i>必填</i></span>
                  <input v-model.trim="form.licenseNo" type="text" maxlength="64" placeholder="统一社会信用代码 / 注册号" />
                </label>
              </div>

              <div class="form-grid">
                <label class="field">
                  <span>联系人</span>
                  <input v-model.trim="form.contactName" type="text" maxlength="32" placeholder="真实姓名" />
                </label>
                <label class="field">
                  <span>联系电话</span>
                  <input v-model.trim="form.contactPhone" type="text" maxlength="20" placeholder="手机号 / 固定电话" />
                </label>
              </div>

              <label class="field">
                <span>店铺地址</span>
                <input v-model.trim="form.shopAddress" type="text" maxlength="128" placeholder="经营场所地址" />
              </label>
            </div>

            <div class="form-section">
              <div class="section-title">
                <h2>资质材料</h2>
                <span class="tiny">STEP 02 / QUALIFICATION</span>
              </div>

              <div class="upload">
                <div class="lic-preview" :class="{ empty: !form.licenseImage }">
                  <img v-if="form.licenseImage" :src="form.licenseImage" alt="营业执照 / 资质图预览" @load="$event.target.style.opacity = '1'" @error="$event.target.style.opacity = '0'" />
                  <span v-else>执照图<br />预览</span>
                </div>
                <div class="up-body">
                  <label class="up-label" for="licenseImage">资质图地址</label>
                  <input id="licenseImage" v-model.trim="form.licenseImage" type="text" maxlength="255" placeholder="图片 URL / 路径（后端只收地址字符串，留空即可）" />
                  <p class="up-note">后端只收图片地址字符串，本页没有文件上传端点；填写后左侧即时预览，留空则该项不提交。</p>
                </div>
              </div>
            </div>

            <div class="form-section">
              <div class="section-title">
                <h2>{{ applyType === 'brand' ? '提交品牌加盟' : '提交入驻申请' }}</h2>
                <span class="tiny">STEP 03 / SUBMIT</span>
              </div>

              <div class="f-acts">
                <button type="submit" class="btn btn-solid" :disabled="busy">
                  <IconGlyph name="ok" />{{ busy ? '提交中…' : '提交申请' }}
                </button>
                <button type="button" class="btn btn-ghost" :disabled="busy" @click="resetForm">清空</button>
              </div>

              <p v-if="formErr" class="err">{{ formErr }}</p>
              <p v-if="okMsg" class="done-note">{{ okMsg }}</p>

              <div class="join-note">
                <b>审核与结果说明</b>
                <p>
                  ⚠️ 提交<b>不等于</b>通过：申请只是躺在表里等管理员审核，通过前你仍然是<b>普通客户</b>；
                  每次提交都会<b>新增一条</b>记录（不会覆盖旧的）。
                  审核通过后需<b>重新登录</b>才会进入商家端。
                  <template v-if="applyType === 'brand'">品牌加盟<b>仅限已入驻商家</b>：通过后<b>不改身份</b>，只给本店商品加品牌标并进入品牌专区。</template>
                </p>
              </div>
            </div>
          </form>
        </section>

        <aside class="stack">
          <section class="panel">
            <div class="panel-head">
              <div><div class="kicker">PROGRESS</div><h2>申请进度</h2></div>
              <span class="badge" :class="rows.length ? rows[0].Status : ''">{{ rows.length ? statusLabel(rows[0].Status) : '未提交' }}</span>
            </div>
            <div class="ledger">
              <div class="ledger-row"><div class="l"><b>已提交</b><small>TOTAL</small></div><div class="v">{{ rows.length }}</div></div>
              <div class="ledger-row"><div class="l"><b>待审</b><small>PENDING</small></div><div class="v">{{ pendingOfMine }}</div></div>
              <div class="ledger-row"><div class="l"><b>申请类型</b><small>TYPE</small></div><div class="v">{{ applyType === 'brand' ? '品牌加盟' : '商家入驻' }}</div></div>
              <div class="ledger-row"><div class="l"><b>最近提交</b><small>LATEST</small></div><div class="v">{{ rows.length ? fmt(rows[0].SubmittedAt) : '—' }}</div></div>
            </div>
            <div class="panel-foot"><span class="small muted">每次提交新增一条，不覆盖旧记录。</span></div>
          </section>

          <section class="panel">
            <div class="panel-head"><div><div class="kicker">FLOW</div><h2>入驻动线</h2></div></div>
            <ul class="join-steps">
              <li><b>01</b><div><strong>填写信息</strong>店铺名称与营业执照号为必填，其余可按实际补充。</div></li>
              <li><b>02</b><div><strong>提交资质</strong>填写资质图地址后可即时预览；留空则不提交该项。</div></li>
              <li><b>03</b><div><strong>等待审核</strong>申请进入管理员审核队列，前端不代替审核结果。</div></li>
              <li><b>04</b><div><strong>查看结果</strong>通过后重新登录即可进入商家端；驳回可在记录中看到原因。</div></li>
            </ul>
          </section>

          <section class="panel">
            <div class="panel-head"><div><div class="kicker">HELP</div><h2>需要准备</h2></div></div>
            <ul class="help-list">
              <li>店铺名称与营业执照号。</li>
              <li>联系人与联系电话，便于平台核对。</li>
              <li>经营场所地址与资质图地址。</li>
              <li v-if="applyType === 'brand'">品牌加盟仅限已入驻商家，通过后不改商家身份。</li>
              <li v-else>审核通过后需重新登录，买家资产与历史订单不受影响。</li>
            </ul>
          </section>
        </aside>
      </div>

      <!-- ── ③ 已提交申请 ─────────────────────────────────────────────────── -->
      <section class="panel panel-list">
        <div class="panel-head">
          <div>
            <div class="kicker">APPLICATIONS</div>
            <h2>我的申请</h2>
          </div>
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
              <span v-if="isBrandRow(r)" class="badge brandt">品牌加盟</span>
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
  ⇒ 根元素必须是 `<main class="uc-main">`、自带 `flex:1; min-width:0`；页头按 A 版 `.page-head`（kicker / h1 / 说明 + 右侧动作）。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getMerchantId, getUsername } from '../api/auth'
import { merchantApply, merchantBrandApply, merchantMyApplications } from '../api/merchant'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行的惯例见各页同款注释

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
/**
 * 身份判据（2026-10-19 用户令）：会话里有 `merchantid` ⇒ 已入驻商家，只能申请品牌加盟；
 * 否则是普通买家，只能申请商家入驻。前端只决定**显示哪个选项**，真正的判据仍在后端
 * （品牌申请未入驻会被 `MerchantApplicationService.audit` 拒绝）。
 */
const merchantIdRaw = getMerchantId()
const isMerchantAccount = computed(() => {
  const v = merchantIdRaw
  return !!v && v !== 'null' && v !== 'undefined'
})
/** 申请类型：`join` 入驻（旧行为）/ `brand` 品牌加盟（批四第二批）；由身份直接决定。 */
const applyType = ref(isMerchantAccount.value ? 'brand' : 'join')
const isBrandRow = (r) => r && (r.IsBrand === true || r.IsBrand === 1 || String(r.IsBrand) === '1')
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
    const isBrand = applyType.value === 'brand'
    const data = isBrand
      ? await merchantBrandApply({ username: who.value, ...form })
      : await merchantApply({ username: who.value, ...form })
    if (!data || !data.success) {
      formErr.value = (data && data.msg) || '提交失败'
      return
    }
    okMsg.value = isBrand
      ? `品牌加盟申请已提交（批次 ${dash(data.batchId)}）—— 等管理员审核，通过后商品会带品牌标并进入品牌专区。`
      : `已提交（批次 ${dash(data.batchId)}）—— 等管理员审核，通过后需重新登录。`
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

/* ── A 版页头（`.page-head` / `.kicker` / `.head-actions`）───────────────── */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s5);
  padding-bottom: var(--s5);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--s5);
}
.kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.page-head h1 {
  margin: 10px 0 12px;
  font-family: var(--font-display);
  font-size: 48px;
  font-weight: var(--fw-semi);
  line-height: 1.02;
  letter-spacing: -0.035em;
  color: var(--ink);
}
.page-head p {
  max-width: 700px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink-3);
}
.head-actions {
  display: flex;
  gap: var(--s2);
  flex: none;
  flex-wrap: wrap;
  justify-content: flex-end;
}

/* ── 入驻说明横幅（A 版 `.join-banner`）────────────────────────────────── */
.join-banner {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) 290px;
  gap: 34px;
  align-items: center;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--surface);
  padding: 30px 34px;
  margin-bottom: var(--s5);
}
/* 2026-10-19 用户令：横幅里的三行说明文字（kicker / 当前状态 / 条数）统一用贵金属金，
   与黑底形成黑金对比；正文 `<p>` 已按同一条令删除。 */
.join-banner .kicker {
  color: #d4af37;
  opacity: 1;
}
.join-banner h2 {
  margin: 8px 0 10px;
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: var(--fw-semi);
  line-height: 1.05;
  letter-spacing: -0.035em;
}
/* 2026-10-19 用户令：品牌加盟时，黑底横幅主标题用金色。 */
.join-banner h2.brand-gold { color: #d4af37; }
.join-banner p {
  max-width: 650px;
  font-size: 12px;
  line-height: 1.75;
  opacity: 0.78;
}
.jb-actions {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  min-width: 0;
}
.jb-state {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding-left: var(--s5);
  border-left: 1px solid currentColor;
}
.jb-state b {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  line-height: 1.15;
  color: #d4af37;
}
.jb-meta {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.05em;
  color: #d4af37;
  opacity: 0.88;
}

/* ── 两栏：左侧表单 / 右侧规则与进度 ───────────────────────────────────── */
.join-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 38px;
  align-items: start;
  margin-bottom: var(--s5);
}
.join-form {
  min-width: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: var(--s5);
}
.stack > * + * {
  margin-top: var(--s5);
}
.panel {
  min-width: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 22px 24px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s4);
  margin-bottom: var(--s4);
}
.panel-head .kicker {
  margin-bottom: var(--s1);
}
.panel-head h2 {
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: var(--fw-semi);
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.panel-foot {
  margin-top: var(--s4);
  padding-top: var(--s3);
  border-top: 1px solid var(--line);
}
.small {
  font-size: var(--fs-xs);
}
.muted {
  color: var(--ink-3);
}

/* ── 表单分段（A 版 `.form-section` / `.form-grid` / `.field`）──────────── */
.f {
  display: block;
}
.form-section {
  border-top: 1px solid var(--line);
  padding-top: var(--s4);
  margin-top: var(--s5);
}
.form-section:first-child {
  border-top: 0;
  padding-top: 0;
  margin-top: 0;
}
.section-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s4);
  border-bottom: 1px solid var(--line);
  padding-bottom: var(--s2);
  margin-bottom: var(--s4);
}
.section-title h2 {
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: var(--fw-semi);
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.tiny {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--ink-3);
}
.form-grid {
  display: grid;
  grid-template-columns: 1.2fr 0.8fr;
  gap: var(--s4);
}
.field {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  min-width: 0;
  margin-bottom: var(--s3);
}
.field > span,
.field-label {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.field i {
  font-style: normal;
  color: var(--accent);
}
.field > input,
.up-body input {
  width: 100%;
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
}
.field > input:focus,
.up-body input:focus {
  outline: none;
  border-color: var(--brand-ink);
  box-shadow: 0 0 0 1px var(--brand-ink);
}
.type-row {
  display: flex;
  gap: var(--s5);
  flex-wrap: wrap;
  padding-top: 2px;
}
.type-opt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--ink);
  cursor: pointer;
}
.type-opt input {
  accent-color: var(--brand-ink);
}

/* ── 资质图：地址输入 + 即时预览（A 版 `.upload` 位置）──────────────────── */
.upload {
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: var(--s4);
  border: 1px dashed var(--line-2);
  background: var(--surface-2);
  padding: var(--s4);
}
.lic-preview {
  width: 104px;
  height: 104px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--line-2);
  background: var(--surface);
}
.lic-preview.empty {
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1.5;
  letter-spacing: 0.05em;
  text-align: center;
  color: var(--ink-3);
}
.lic-preview img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  transition: opacity 0.15s;
}
.up-body {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  min-width: 0;
}
.up-label {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.up-note {
  font-size: var(--fs-xs);
  line-height: 1.65;
  color: var(--ink-3);
}
.f-acts {
  display: flex;
  gap: var(--s3);
  flex-wrap: wrap;
}

/* ── 按钮 ─────────────────────────────────────────────────────────────────── */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font: inherit;
  font-size: 13px;
  line-height: 1.2;
  color: var(--brand-ink);
  background: var(--surface);
  border: 1px solid var(--brand-ink);
  border-radius: var(--r1);
  padding: 10px 15px;
  cursor: pointer;
  text-decoration: none;
  transition: background-color 0.12s linear, color 0.12s linear;
}
.btn:hover {
  background: var(--brand-ink);
  color: var(--surface);
}
.btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.btn-ghost {
  background: transparent;
  border-color: var(--line-2);
}
.btn-ghost:hover {
  background: var(--ink);
  color: var(--surface);
  border-color: var(--ink);
}
.btn-sm {
  font-size: 11px;
  padding: 6px 10px;
}
.btn-solid {
  background: var(--brand-ink);
  color: var(--surface);
  border-color: var(--brand-ink);
}
.btn-solid:hover {
  background: var(--surface);
  color: var(--brand-ink);
}

/* ── 提示 / 错误 / 审核说明 ───────────────────────────────────────────────── */
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
  border-radius: var(--r1);
  padding: var(--s3) var(--s4);
  margin: var(--s4) 0 0;
}
.done-note {
  font-size: var(--fs-sm);
  color: var(--success-deep);
  background: var(--success-ground);
  border-radius: var(--r1);
  padding: var(--s3) var(--s4);
  margin: var(--s4) 0 0;
}
.join-note {
  border: 1px solid var(--line);
  background: var(--surface-2);
  padding: var(--s4);
  margin-top: var(--s4);
  font-size: var(--fs-xs);
  line-height: 1.75;
  color: var(--ink-2);
}
.join-note > b {
  display: block;
  margin-bottom: var(--s1);
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.join-note p {
  margin: 0;
}
.join-note b {
  color: var(--ink);
  font-weight: var(--fw-med);
}

/* ── 进度账本（A 版 `.ledger`）────────────────────────────────────────── */
.ledger {
  border-top: 1px solid var(--line);
}
.ledger-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s3);
  align-items: center;
  padding: var(--s3) 0;
  border-bottom: 1px solid var(--line);
}
.ledger-row:last-child {
  border-bottom: 0;
}
.ledger-row .l {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  min-width: 0;
}
.ledger-row .l b {
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  color: var(--ink-2);
}
.ledger-row .l small {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-3);
}
.ledger-row .v {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  text-align: right;
  color: var(--ink);
}

/* ── 动线 / 准备清单（A 版 `.join-steps` / `.help-list`）────────────────── */
.join-steps,
.help-list {
  list-style: none;
  border-top: 1px solid var(--line);
}
.join-steps li {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: var(--s2);
  padding: var(--s3) 0;
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
}
.join-steps li:last-child,
.help-list li:last-child {
  border-bottom: 0;
}
.join-steps li b {
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 400;
  color: var(--ink);
}
.join-steps li strong {
  display: block;
  margin-bottom: 2px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.help-list li {
  padding: var(--s2) 0;
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
}

/* ── 已提交申请列表 ───────────────────────────────────────────────────────── */
.panel-list {
  margin-bottom: var(--s5);
}
.apps {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}
.app {
  border: 1px solid var(--line);
  border-radius: var(--r1);
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
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-xs);
  line-height: 1.4;
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
/* 批四第二批：品牌加盟标签（与审核状态徽章并排，故不沿用状态色）。 */
.badge.brandt {
  color: var(--brand-ink);
  background: var(--brand-soft);
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

/* ── 窄屏：两栏收成一栏（A 版同款断点）────────────────────────────────── */
@media (max-width: 1080px) {
  .join-layout {
    grid-template-columns: 1fr;
  }
  .join-banner {
    grid-template-columns: 1fr;
  }
  .jb-state {
    padding-left: 0;
    padding-top: var(--s4);
    border-left: 0;
    border-top: 1px solid currentColor;
  }
}
@media (max-width: 720px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }
  .page-head h1 {
    font-size: 38px;
  }
  .head-actions {
    justify-content: flex-start;
  }
  .form-grid,
  .upload {
    grid-template-columns: 1fr;
  }
}
</style>
