<template>
  <section class="a-acc">
    <!-- ── ① 状态条 ──────────────────────────────────────────────────────────
         三个大数 + 时区口径（照 `AuditPage.vue` 那一格：本页所有时间列都是**无时区**的
         库内文本，唯一「错也看不出来」的地方就是偏移，摆在台面上即可一眼核）。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ pendingCount }}</b>
        <span>条待审入驻{{ appsFiltered ? '（筛选后）' : '' }}</span>
      </div>
      <div class="a-stat">
        <b>{{ users.length }}</b>
        <span>本次显示用户</span>
      </div>
      <div class="a-stat">
        <b>{{ bannedCount }}</b>
        <span>其中已封禁</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">UTC</span>
        <span>库内时钟 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── 页内 tab（补丁 B1，2026-10-09 手验后）────────────────────────────────
         用户裁决「甲案」：本页三块用**页内 tab** 切分，**不**拆成三个地址 —— 拆了会让管理员
         在三个地址之间来回跳，而这三块同属「这个账号现在是什么身份、能不能继续当」一个域。
         ⚠️ 原先的第 ⑤ 块（管理员收件箱）**已提到侧边栏顶层独立项**（`/admin/inbox`）：
         它跟前三块**不同域**（是「发给管理员的待办」，不是「治理某个账号」），
         见 `config/admin-nav.js` 与 `router/index.js` 各一条按语。
         📌 **规范**：`docs/agent/c3-modules.md` §五「子项页含多块 ⇒ 页内 tab，tab 数 2–4 个」。 -->
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

    <!-- ══ ② 商家入驻审核（tab 一）═════════════════════════════════════════════ -->
    <template v-if="tab === 'apps'">
    <h2 class="a-h2">商家入驻审核 <span class="a-h2n">通过 = 客户升级为商家（`user.usertype` + `login.Role` 同事务改）</span></h2>

    <form class="a-filters" @submit.prevent="loadApps">
      <label class="a-f">
        <span>状态</span>
        <select v-model="af.status" @change="loadApps">
          <option value="">全部</option>
          <option value="pending">待审</option>
          <option value="approved">已通过</option>
          <option value="rejected">已驳回</option>
        </select>
      </label>
      <label class="a-f a-f-narrow">
        <span>上限</span>
        <select v-model.number="af.limit" @change="loadApps">
          <option v-for="n in [50, 100, 200, 500]" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="loadingApps">
          {{ loadingApps ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </form>

    <ol v-if="apps.length" class="a-stream">
      <li v-for="r in apps" :key="r.Id" class="a-card" :class="{ done: r.Status !== 'pending' }">
        <div class="a-card-l1">
          <code class="a-tag">#{{ r.Id }}</code>
          <b class="a-strong">{{ r.ShopName }}</b>
          <span class="a-badge" :class="r.Status">{{ statusLabel(r.Status) }}</span>
          <span class="a-dim">申请人 {{ r.Username }}</span>
          <span class="a-grow"></span>
          <span class="a-dim">{{ toLocal(r.SubmittedAt) }}</span>
        </div>

        <dl class="a-kv">
          <div><dt>营业执照号</dt><dd>{{ dash(r.LicenseNo) }}</dd></div>
          <div><dt>联系人</dt><dd>{{ dash(r.ContactName) }}</dd></div>
          <div><dt>联系电话</dt><dd>{{ dash(r.ContactPhone) }}</dd></div>
          <div><dt>店铺地址</dt><dd>{{ dash(r.ShopAddress) }}</dd></div>
          <div><dt>资质图</dt><dd>{{ dash(r.LicenseImage) }}</dd></div>
          <div><dt>批次</dt><dd class="a-mono">{{ dash(r.BatchId) }}</dd></div>
          <div><dt>审毕</dt><dd>{{ r.ReviewedAt ? toLocal(r.ReviewedAt) : '—' }}</dd></div>
          <div><dt>审核人</dt><dd>{{ dash(r.ReviewedBy) }}</dd></div>
        </dl>

        <p v-if="r.Status === 'rejected'" class="a-why">驳回理由：{{ dash(r.RejectReason) }}</p>

        <div v-if="r.Status === 'pending'" class="a-acts">
          <input
            v-model.trim="rejectReasons[r.Id]"
            class="a-inp"
            type="text"
            placeholder="驳回理由（驳回时必填，会展示给申请人）"
            @keyup.enter="reject(r)"
          />
          <button type="button" class="a-btn a-btn-on" :disabled="busyId !== null" @click="approve(r)">
            通过
          </button>
          <button type="button" class="a-btn a-btn-danger" :disabled="busyId !== null" @click="reject(r)">
            驳回
          </button>
        </div>
      </li>
    </ol>
    <p v-else-if="!loadingApps" class="a-empty">没有符合条件的入驻申请。</p>
    <p v-if="appsTruncated" class="a-note">
      已截断：按上限 <b>{{ appsLimit }}</b> 条显示，共命中 <b>{{ appsTotal }}</b> 条。
    </p>

    </template>

    <!-- ══ ③ 用户治理（tab 二）═════════════════════════════════════════════ -->
    <template v-else-if="tab === 'users'">
    <h2 class="a-h2">用户治理 <span class="a-h2n">封禁只标 `user` 行、不动 `login`（封禁是状态，不是删除）</span></h2>

    <form class="a-filters" @submit.prevent="loadUsers">
      <label class="a-f">
        <span>关键字</span>
        <input
          v-model.trim="uf.keyword"
          type="text"
          placeholder="用户名 / 客户号 / 商家号"
          @keyup.enter="loadUsers"
        />
      </label>
      <label class="a-f a-f-narrow">
        <span>上限</span>
        <select v-model.number="uf.limit" @change="loadUsers">
          <option v-for="n in [50, 100, 200, 500]" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="loadingUsers">
          {{ loadingUsers ? '查询中…' : '查询' }}
        </button>
        <button type="button" class="a-btn" @click="resetUsers">重置</button>
      </div>
    </form>
    <p class="a-hint">
      ⚠️ 关键字是<b>包含</b>匹配（三列任一命中即算）；封禁<br />
      对<b>管理员无效</b>（`AuthInterceptor` 对 `role=3` 豁免）—— 列表里看得到管理员，封不动。
    </p>

    <ol v-if="users.length" class="a-stream">
      <li v-for="u in users" :key="u.username" class="a-card" :class="{ banned: isBanned(u) }">
        <div class="a-card-l1">
          <b class="a-strong">{{ u.username }}</b>
          <span class="a-badge">{{ dash(u.usertype) }}</span>
          <span v-if="isBanned(u)" class="a-badge rejected">已封禁</span>
          <span v-if="dgOf(u)" class="a-badge warn">{{ dgOf(u) }}</span>
          <span class="a-grow"></span>
          <span class="a-dim">{{ idsOf(u) }}</span>
        </div>

        <p v-if="isBanned(u)" class="a-why">
          封禁理由：{{ dash(u.banReason) }}<span v-if="u.bannedAt"> · {{ toLocal(u.bannedAt) }}</span>
        </p>
        <p v-if="u.DowngradeReason" class="a-why">降级原因：{{ u.DowngradeReason }}</p>

        <div class="a-acts">
          <template v-if="!isBanned(u)">
            <input
              v-model.trim="banReasons[u.username]"
              class="a-inp"
              type="text"
              placeholder="封禁理由（必填，会展示给用户）"
              @keyup.enter="ban(u)"
            />
            <button type="button" class="a-btn a-btn-danger" :disabled="busyName !== null" @click="ban(u)">
              封禁
            </button>
          </template>
          <button
            v-else
            type="button"
            class="a-btn a-btn-on"
            :disabled="busyName !== null"
            @click="unban(u)"
          >
            解封
          </button>
          <span class="a-grow"></span>
          <button type="button" class="a-btn" @click="pickDowngrade(u.username)">降级操作 ↓</button>
        </div>
      </li>
    </ol>
    <p v-else-if="!loadingUsers" class="a-empty">没有命中任何用户。</p>
    <p v-if="usersTruncated" class="a-note">
      已截断：按上限 <b>{{ usersLimit }}</b> 条显示，共命中 <b>{{ usersTotal }}</b> 条 —— 收窄关键字，或把上限调大。
    </p>

    </template>

    <!-- ══ ④ 商家降级控制（tab 三）═════════════════════════════════════════ -->
    <template v-else>
    <h2 class="a-h2">商家降级控制 <span class="a-h2n">排定 → 通知期（1 天）→ 冻结 → 解冻恢复 / 重新申请</span></h2>

    <form class="a-filters" @submit.prevent="scheduleDowngrade">
      <label class="a-f">
        <span>商家用户名</span>
        <input v-model.trim="df.username" type="text" placeholder="username" />
      </label>
      <label class="a-f a-f-wide">
        <span>降级原因（必填）</span>
        <input v-model.trim="df.reason" type="text" placeholder="会写进 user.DowngradeReason，商家看得到" />
      </label>
      <div class="a-f-actions">
        <button
          type="submit"
          class="a-btn a-btn-danger"
          :disabled="busy || !!dfBlocked"
          :title="dfBlocked ? '已发送过降级请求，请于用户治理模块查看' : ''"
        >
          排定降级
        </button>
        <button type="button" class="a-btn" :disabled="busy" @click="cancelDowngrade">撤销排定</button>
        <button type="button" class="a-btn" :disabled="busy" @click="relistMerchant">人工恢复</button>
      </div>
    </form>
    <!-- 补丁 A3 前端：这个账号**目前**已有降级标记 ⇒ 排定降级按钮 disabled 并就地说明原因。
         判据来自「用户治理」页已拉到的 users 行（本页 tab 二），不是新请求 —— 见 `dfBlocked`。 -->
    <p v-if="dfBlocked" class="a-blocked">{{ dfBlockMsg }}</p>
    <p class="a-hint">
      ⚠️ <b>排定 ≠ 立刻降级</b>：通知期内商家照常经营，只在 <code>/user/me</code> 上多一个 `scheduled` 态；
      到点由后端 <code>@Scheduled</code> 冻结（<b>本页不轮询那一步</b>）。<br />
      ⚠️ <b>撤销排定</b>与<b>人工恢复</b>是两条不同的路：前者治「通知期内反悔」，后者治「已经冻结了、窗口内想回来」。<br />
      ⚠️ <b>同一个降级只能排定一次</b>（补丁 A3，后端 CAS）：重复点击会在下方红字里被拦下，<b>不会</b>再排一次、也不会再发一条通知。
    </p>

    <!-- ══ 降级态清单（补丁 A3 的前端落点）══════════════════════════════════
         为什么非要有这一段：上面的表单是**盲填用户名**的 —— 管理员看不见「这个账号现在到底排定没
         排定」。而那份信息只有「用户治理」页（tab 二）拉到的行里才有（`user.DowngradeState` 等四列）
         ⇒ 把带标记的行在这里列出来，且每行的「排定降级」**恒为 disabled**：
         同一次降级不能排第二遍（这正是补丁 A3 要防的、也是 A4 那 14 条刷屏的根因）。 -->
    <h3 class="a-h3">
      当前有降级标记的账号
      <span class="a-h3n">来自「用户治理」页已拉到的 {{ users.length }} 行 ⇒ 共 {{ dgUsers.length }} 个</span>
    </h3>
    <ol v-if="dgUsers.length" class="a-stream">
      <li v-for="u in dgUsers" :key="u.username" class="a-card">
        <div class="a-card-l1">
          <b class="a-strong">{{ u.username }}</b>
          <span class="a-badge warn">{{ dgOf(u) }}</span>
          <span class="a-grow"></span>
          <span class="a-dim">{{ idsOf(u) }}</span>
        </div>
        <p v-if="u.DowngradeReason" class="a-why">降级原因：{{ u.DowngradeReason }}</p>
        <p v-if="dgTimeOf(u)" class="a-why">{{ dgTimeOf(u) }}</p>
        <div class="a-acts">
          <button
            type="button"
            class="a-btn a-btn-danger"
            disabled
            title="已发送过降级请求，请于用户治理模块查看"
          >
            排定降级
          </button>
          <button
            v-if="u.DowngradeState === 'scheduled'"
            type="button"
            class="a-btn"
            :disabled="busy"
            @click="cancelFor(u)"
          >
            撤销排定
          </button>
          <button v-else type="button" class="a-btn" :disabled="busy" @click="relistFor(u)">人工恢复</button>
        </div>
      </li>
    </ol>
    <p v-else-if="!loadingUsers" class="a-empty">
      当前没有处于「已排定」或「已冻结」的账号 —— 先去「用户治理」tab 拉一次用户列表。
    </p>
    <p v-if="dgTruncated" class="a-note">
      上面的清单只覆盖<b>已拉到</b>的 {{ users.length }} 行（共命中 {{ usersTotal }} 行）——
      还有账号没列出来，去「用户治理」tab 收窄关键字再查。
    </p>
    </template>
  </section>
</template>

<!--
  用户与商家治理页（C3 块 2〈用户块〉，2026-10-09）—— `/admin/business/accounts` 的真页。

  它是 `StubPage.vue` 预告的「等某一块真拿到内容时再单独拆」的**第二个**实例
  （第一个是 `AuditPage.vue`；名单见 `router/index.js` 的 `children`）。

  【它消费后端两组只读 + 五组写】：
    · `/admin/accounts/users` · `user/ban` · `user/unban`                  —— 用户治理（`UserGovernanceService`）
    · `/admin/accounts/applications` · `application/audit`                —— 入驻审核（`MerchantApplicationService`）
    · `/admin/accounts/downgrade/schedule` · `cancel` · `relist`          —— 降级控制（`MerchantDowngradeService`）
  全部经 `api/accounts.js` 包一层 —— 本页**不直接**碰 `http`。

  【⚠️ 补丁 B1（2026-10-09 手验后）：结构变了，注释随之改】
  原先这页是**四块靠滚动区分**（入驻审核 / 用户治理 / 降级控制 / 管理员收件箱），用户裁决「甲案」：
    · 前三块 ⇒ **页内 tab**（`[入驻审核] [用户治理] [降级控制]`）。它们同属一个域（「这个账号
      现在是什么身份、能不能继续当」），**不**拆成三个地址 —— 拆了管理员要在三个地址之间来回跳。
    · 第 ④ 块（管理员收件箱）⇒ **移出本页**，成侧边栏**顶层独立项** `/admin/inbox`：它是「发给管理员的
      待办」，与治理某个账号**不同域**。`NotifyInbox.vue` 按 `route.path` 派生收件人，挂在 `/admin/**`
      下自动取 `ToType='admin'` / `ToId='*'` ⇒ **组件一行未改**。
    · ⇒ 本页**不再** import `NotifyInbox`、不再有 `.a-inbox` 样式。
  规范成文于 `docs/agent/c3-modules.md` §五：「子项页含多块 ⇒ 页内 tab，tab 数 2–4 个」。

  【补丁 A3（同一降级只能排定一次）在本页的落点】「降级控制」tab 里，
    （a）表单的「排定降级」按钮在该账号已有降级标记时 disabled + 红字（`dfBlocked` / `dfBlockMsg`）；
    （b）新增「当前有降级标记的账号」清单，每行的「排定降级」**恒 disabled** 并写明去向，
        可按状态就地「撤销排定」（scheduled）或「人工恢复」（frozen）。
  判据**不新请求**：取自「用户治理」tab 已拉到的 `users` 行（`DowngradeState` / `DowngradeReason` /
  `ScheduledDowngradeAt` / `FrozenAt`）。后端的 CAS 才是真闸，前端这层只是**别让人白点**。

  【视觉】照 `c3-modules.md` §五 与 `AuditPage.vue`：数字→状态（顶部三个大数）、列表→卡片式叙事
  （不是宽表）、危险动作用 `--danger` 系、状态用 `--warning` / `--danger` / `--success` 三族。
  ⚠️ 危险动作（封禁 / 驳回 / 排定降级）**都要求填理由**，且理由会**展示给当事人** —— 界面上写明了这一点。

  【⚠️ 本页最大的坑：管理员自己】`selectUsers` **不筛** `usertype` ⇒ 列表里包含管理员本人与其它管理员。
  封禁对 `role=3` **无效**（`AuthInterceptor` 豁免）—— 故页面上允许点，但点了不生效。这是**刻意**
  留着的（藏起来会让人以为「管理员不在治理范围内」），在筛选条下方那段 hint 里写明了。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  accountsApplications,
  accountsAudit,
  accountsBan,
  accountsUnban,
  accountsUsers,
  downgradeCancel,
  downgradeRelist,
  downgradeSchedule,
} from '../../api/accounts'

// ── 时区（照 `AuditPage.vue` 的 `toLocal` / `tzLabel`，逐字同款）────────────────
// ⚠️ 后端所有时间列都是 `DATE_FORMAT(…, '%Y-%m-%d %H:%i:%s')` 的**无时区文本**，
//    而库里存的是 **UTC**。末尾那个 `Z` 不能省：省掉就按浏览器本地时区解析，
//    显示上整差一个偏移，且**错得很安静**（格式完全正常）。
const pad = (n) => String(n).padStart(2, '0')

const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)   // 解析不了原样回 —— 不崩、不空白
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

const dash = (v) => (v === null || v === undefined || String(v).trim() === '' ? '—' : String(v))

// ── 页内 tab（补丁 B1）───────────────────────────────────────────────────────
// ⚠️ `note()` 是**函数**不是字符串：`pendingCount` / `users` / `dgUsers` 都在下面才声明
//    （`const` 有 TDZ），写成字符串会在模块求值期就炸；写成函数则只在**渲染时**求值，安全。
// ⚠️ 三个 tab 的**顺序**就是「事情的自然顺序」：先审入驻 → 再管账号 → 最后才是降级。
const tab = ref('apps')
const TABS = [
  { key: 'apps', label: '入驻审核', note: () => `${pendingCount.value} 待审` },
  { key: 'users', label: '用户治理', note: () => `${users.value.length} 行` },
  { key: 'dg', label: '降级控制', note: () => `${dgUsers.value.length} 个带标记` },
]

// ── 反馈 ─────────────────────────────────────────────────────────────────────
/** 一次性提示条。`kind` 只有 `'ok'` / `'bad'` 两种（CSS 也只需这两支）。 */
const flash = reactive({ kind: 'ok', text: '' })
function say(kind, text) {
  flash.kind = kind
  flash.text = text
}
/** 把后端的 `{success:false,msg}` 或异常统一转成一句人话。 */
function failOf(e) {
  if (e && e.status) return `HTTP ${e.status}`
  return (e && e.message) || String(e)
}

// ── ② 入驻申请 ───────────────────────────────────────────────────────────────
const af = reactive({ status: '', limit: 100 })
const apps = ref([])
const appsTotal = ref(0)
const appsLimit = ref(100)
const loadingApps = ref(false)
/** 每条申请的驳回理由输入框（键 = `Id`）。⚠️ 用 `reactive({})` 而不是给每行塞字段 —— 行数据是后端原样，别污染它。 */
const rejectReasons = reactive({})
/** 正在被处理的那一条（防重复点）。`null` = 空闲。 */
const busyId = ref(null)

const appsFiltered = computed(() => af.status !== '')
const appsTruncated = computed(() => appsTotal.value > apps.value.length)
/** 「待审」那个大数**只在看全量时**才准确 —— 筛了别的状态就不该拿它当待审数（会骗人）。 */
const pendingCount = computed(() =>
  af.status === 'pending' ? appsTotal.value : apps.value.filter((r) => r.Status === 'pending').length,
)

function statusLabel(s) {
  if (s === 'pending') return '待审'
  if (s === 'approved') return '已通过'
  if (s === 'rejected') return '已驳回'
  return dash(s)
}

async function loadApps() {
  loadingApps.value = true
  try {
    const data = await accountsApplications({ status: af.status, limit: af.limit })
    if (!data || !data.success) {
      apps.value = []
      say('bad', (data && data.msg) || '入驻申请加载失败')
      return
    }
    apps.value = Array.isArray(data.list) ? data.list : []
    appsTotal.value = Number(data.total) || 0
    appsLimit.value = data.limit == null ? af.limit : data.limit   // 用**后端回显**的上限（它会夹到 500）
  } catch (e) {
    apps.value = []
    appsTotal.value = 0
    say('bad', `入驻申请加载失败：${failOf(e)}`)
    console.error('admin applications', e)
  } finally {
    loadingApps.value = false
  }
}

/** 通过。副作用是一串（`user.usertype` + `merchantid` + `login.Role` 同事务），故文案要如实说清。 */
async function approve(r) {
  busyId.value = r.Id
  try {
    const data = await accountsAudit({ id: r.Id, approve: true })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '操作失败')
      return
    }
    // ⚠️ `outcome` 是判据（见 `api/accounts.js`）：`already-handled` **不是失败**，别报「通过成功」。
    if (data.outcome === 'already-handled') {
      say('bad', `#${r.Id} 这条已经被处理过了 —— 列表已刷新。`)
    } else if (data.outcome === 'approved-restored') {
      say('ok', `#${r.Id} 通过，并恢复了 30 天冻结期内的旧商家号（${dash(data.username)}）。`)
    } else {
      say('ok', `#${r.Id} 通过：${dash(data.username)} 已升级为商家（对方**重新登录**后才进商家壳）。`)
    }
    await loadApps()
  } catch (e) {
    say('bad', `通过失败：${failOf(e)}`)
    console.error('admin approve', e)
  } finally {
    busyId.value = null
  }
}

/** 驳回。理由**必填** —— 前端先拦一次，后端也会拦（两处判据同一句话）。 */
async function reject(r) {
  const reason = rejectReasons[r.Id]
  if (!reason) {
    say('bad', `驳回 #${r.Id} 必须填写理由（该理由会展示给申请人）。`)
    return
  }
  busyId.value = r.Id
  try {
    const data = await accountsAudit({ id: r.Id, approve: false, rejectReason: reason })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '操作失败')
      return
    }
    if (data.outcome === 'already-handled') {
      say('bad', `#${r.Id} 这条已经被处理过了 —— 列表已刷新。`)
    } else {
      say('ok', `#${r.Id} 已驳回。`)
      delete rejectReasons[r.Id]
    }
    await loadApps()
  } catch (e) {
    say('bad', `驳回失败：${failOf(e)}`)
    console.error('admin reject', e)
  } finally {
    busyId.value = null
  }
}

// ── ③ 用户治理 ───────────────────────────────────────────────────────────────
const uf = reactive({ keyword: '', limit: 100 })
const users = ref([])
const usersTotal = ref(0)
const usersLimit = ref(100)
const loadingUsers = ref(false)
const banReasons = reactive({})
/** 正在被处理的那个账号（防重复点）。`null` = 空闲。 */
const busyName = ref(null)

const usersTruncated = computed(() => usersTotal.value > users.value.length)
const bannedCount = computed(() => users.value.filter(isBanned).length)

/**
 * `banned` 是 `tinyint(1)`，Connector/J 默认（`tinyInt1isBit=true`）把它映射成 **Boolean**
 * ⇒ JSON 里可能就是 `true` / `false`，也可能是 `1` / `0`（换驱动设置就会变）。
 * 判据写**宽容**一点，别赌某一种形态（后端 `UserGovernanceDao.truthy` 是同一个理由）。
 */
function isBanned(u) {
  const v = u && u.banned
  return v === true || v === 1 || v === '1' || v === 'true'
}

/** 降级态的中文标签；`none` / 空 ⇒ 不画那枚徽章。 */
function dgOf(u) {
  const s = u && u.DowngradeState
  if (s === 'scheduled') return '降级已排定'
  if (s === 'frozen') return '已冻结'
  return ''
}

function idsOf(u) {
  const parts = []
  if (u.customerid) parts.push(`客户 ${u.customerid}`)
  if (u.merchantid) parts.push(`商家 ${u.merchantid}`)
  return parts.length ? parts.join(' · ') : '（无业务号）'
}

async function loadUsers() {
  loadingUsers.value = true
  try {
    const data = await accountsUsers({ keyword: uf.keyword, limit: uf.limit })
    if (!data || !data.success) {
      users.value = []
      say('bad', (data && data.msg) || '用户列表加载失败')
      return
    }
    users.value = Array.isArray(data.list) ? data.list : []
    usersTotal.value = Number(data.total) || 0
    usersLimit.value = data.limit == null ? uf.limit : data.limit
  } catch (e) {
    users.value = []
    usersTotal.value = 0
    say('bad', `用户列表加载失败：${failOf(e)}`)
    console.error('admin users', e)
  } finally {
    loadingUsers.value = false
  }
}

function resetUsers() {
  uf.keyword = ''
  uf.limit = 100
  loadUsers()
}

/** 封禁。理由**必填**，且**写给被封的人看** —— 前端先拦，后端也拦。 */
async function ban(u) {
  const reason = banReasons[u.username]
  if (!reason) {
    say('bad', `封禁 ${u.username} 必须填写理由（该理由会展示给用户）。`)
    return
  }
  busyName.value = u.username
  try {
    const data = await accountsBan({ username: u.username, reason })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '封禁失败')
      return
    }
    say('ok', `${u.username} 已封禁 —— 生效面是对方的下一次请求（登录闸 + 请求闸双查），不是这一发。`)
    delete banReasons[u.username]
    await loadUsers()
  } catch (e) {
    say('bad', `封禁失败：${failOf(e)}`)
    console.error('admin ban', e)
  } finally {
    busyName.value = null
  }
}

/** 解封。幂等 —— 后端对没封过的账号也答 `success:true`。 */
async function unban(u) {
  busyName.value = u.username
  try {
    const data = await accountsUnban({ username: u.username })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '解封失败')
      return
    }
    say('ok', `${u.username} 已解封。`)
    await loadUsers()
  } catch (e) {
    say('bad', `解封失败：${failOf(e)}`)
    console.error('admin unban', e)
  } finally {
    busyName.value = null
  }
}

// ── ④ 商家降级 ───────────────────────────────────────────────────────────────
const df = reactive({ username: '', reason: '' })
const busy = ref(false)

/**
 * 已排定 / 已冻结的账号清单（补丁 A3 前端）。
 * ⚠️ 判据**不新发请求** —— 复用「用户治理」tab 已拉到的 `users`。理由：那份列表本来就带着
 *    `DowngradeState` 四列，再打一次接口只会让两个 tab 的数字**互相打架**（同一个时刻两个真值）。
 * ⚠️ 代价写明：`users` 只有 100 行（上限）⇒ 清单也是「这 100 行里有降级标记的」。
 *    完整清单要以「用户治理」页的查询为准，页头上那行小字已如实标出这一点。
 */
const dgUsers = computed(() => users.value.filter((u) => dgOf(u) !== ''))
const dgTruncated = computed(() => usersTruncated.value)

/** 降级态里那两个时间列的中文说法（`scheduled` 看「何时生效」，`frozen` 看「何时冻的」）。 */
function dgTimeOf(u) {
  if (u.DowngradeState === 'scheduled') {
    return u.ScheduledDowngradeAt ? `生效时刻 ${toLocal(u.ScheduledDowngradeAt)}` : ''
  }
  if (u.DowngradeState === 'frozen') {
    return u.FrozenAt ? `冻结于 ${toLocal(u.FrozenAt)}` : ''
  }
  return ''
}

/**
 * 表单里那个用户名**当前**的降级态。⚠️ 只会从**已拉到**的行里找：找不到（还没点过「查询」、
 * 或该用户不在这一页）就当作「不知道」⇒ **不拦** —— 前端拦不住的事后端 CAS 一定拦得住，
 * 反过来「因为不知道就禁用按钮」会让人根本没法排定降级。**宁可放行让人撞一次红字。**
 */
const dfUser = computed(() => {
  if (!df.username) return null
  return users.value.find((u) => u.username === df.username) || null
})
const dfState = computed(() => (dfUser.value && dfUser.value.DowngradeState) || '')
const dfBlocked = computed(() => dfState.value === 'scheduled' || dfState.value === 'frozen')
/** 文案按 `outcome` 挑，**不按 `msg` 做字符串匹配**（后端 `MerchantDowngradeService` 的成文口径）。 */
const dfBlockMsg = computed(() => {
  if (dfState.value === 'frozen') {
    return `${df.username} 已处于降级冻结状态 —— 无需再次排定；要恢复请用「人工恢复」。`
  }
  if (dfState.value === 'scheduled') {
    return `${df.username} 已发送过降级请求，请于用户治理模块查看。`
  }
  return ''
})

/** 从用户行「带过去」到降级表单 —— 少一次手打用户名的机会（也就少一次打错人的机会）。 */
function pickDowngrade(username) {
  df.username = username
  tab.value = 'dg'   // 补丁 B1：表单在**另一个 tab** 里，不过去就等于「点了没反应」
  say('ok', `已把 ${username} 填进「降级控制」页的降级表单。`)
}

/** 清单里就地「撤销排定」/「人工恢复」—— 先把它填进表单，再走**同一条**函数（不复制逻辑）。 */
async function cancelFor(u) {
  df.username = u.username
  await cancelDowngrade()
}
async function relistFor(u) {
  df.username = u.username
  await relistMerchant()
}

async function scheduleDowngrade() {
  if (!df.username || !df.reason) {
    say('bad', '排定降级需要**用户名 + 原因**两项都填（原因是写给商家的，后端也拦）。')
    return
  }
  busy.value = true
  try {
    const data = await downgradeSchedule({ username: df.username, reason: df.reason })
    if (!data || !data.success) {
      // ⚠️ 补丁 A3：这两种 **不是**「失败」，是「拦下了」—— 后端 CAS 命中 0 行。
      //    按 `outcome` 分别说（成文口径：前端挑文案看 `outcome`，不匹配 `msg` 字符串）。
      //    走这两支时后端**没有**发通知（`success:false` ⇒ Controller 的发送块进不去）⇒ A4 的刷屏根治。
      if (data && data.outcome === 'already-scheduled') {
        say('bad', '已发送过降级请求，请于用户治理模块查看。')
        return
      }
      if (data && data.outcome === 'already-frozen') {
        say('bad', '该账号已处于降级冻结状态，无需再次排定 —— 要恢复请用「人工恢复」。')
        return
      }
      say('bad', (data && data.msg) || '排定失败')
      return
    }
    say('ok', `${df.username} 的降级已排定，生效时刻 ${toLocal(data.scheduledAt)}（通知期内照常经营）。`)
    await loadUsers()
  } catch (e) {
    say('bad', `排定失败：${failOf(e)}`)
    console.error('downgrade schedule', e)
  } finally {
    busy.value = false
  }
}

async function cancelDowngrade() {
  if (!df.username) {
    say('bad', '撤销排定需要填用户名。')
    return
  }
  busy.value = true
  try {
    const data = await downgradeCancel({ username: df.username })
    if (!data || !data.success) {
      // ⚠️ 补丁 A3 附带：对**已冻结**的账号「撤销排定」被后端拒（那条路叫「人工恢复」）。
      //    这里只是把后端的话说成同一句 —— 拦住的是状态机，不是这个按钮。
      if (data && data.outcome === 'already-frozen') {
        say('bad', '该账号已处于降级冻结状态，不能用「撤销排定」—— 请走「人工恢复」。')
        return
      }
      say('bad', (data && data.msg) || '撤销失败')
      return
    }
    say('ok', `${df.username} 的降级排定已撤销。`)
    await loadUsers()
  } catch (e) {
    say('bad', `撤销失败：${failOf(e)}`)
    console.error('downgrade cancel', e)
  } finally {
    busy.value = false
  }
}

/** 人工恢复。⚠️ `relisted:false` **不是失败** —— 它本来就正常（幂等命中），文案要分开说。 */
async function relistMerchant() {
  if (!df.username) {
    say('bad', '人工恢复需要填用户名。')
    return
  }
  busy.value = true
  try {
    const data = await downgradeRelist({ username: df.username })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '恢复失败')
      return
    }
    say(
      data.relisted ? 'ok' : 'bad',
      data.relisted
        ? `${df.username} 已恢复（Role 改回 2、商品重新上架、冻结态清空）。`
        : `${df.username} 本来就正常（没被冻结过 / 已经恢复过）—— 无改动。`,
    )
    await loadUsers()
  } catch (e) {
    say('bad', `恢复失败：${failOf(e)}`)
    console.error('downgrade relist', e)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  loadApps()
  loadUsers()
})
</script>

<style scoped>
.a-acc {
  max-width: 1120px;
}

/* ── ① 状态条（与 `AuditPage.vue` 同款，不另立一套）───────────────────────── */
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
.a-acc > .a-h2:first-of-type {
  margin-top: 0;
}

/* ── 筛选条（照 `AuditPage.vue`）─────────────────────────────────────────── */
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
/* 危险动作（封禁 / 驳回 / 排定降级）—— 与「通过 / 解封」在颜色上立刻分得开。 */
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
.a-why code {
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
.a-card.done {
  background: var(--surface-2);
}
/* 已封禁的行：左侧一道 `--danger`。⚠️ 是**边框**不是「侧边强调条」——
   `tokens.css` 那条房规禁的是「选中态用侧边条」，这里是状态语义（同 `NotifyInbox` 的未读条）。 */
.a-card.banned {
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

.a-why {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border-left: 2px solid var(--line-2);
  border-radius: 0 var(--r1) var(--r1) 0;
  padding: var(--s2) var(--s3);
  margin: var(--s3) 0 0;
}
.a-acts {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  margin-top: var(--s3);
}
.a-inp {
  flex: 1 1 220px;
  min-width: 160px;
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}

/* ── 页内 tab（补丁 B1）────────────────────────────────────────────────────
   ⚠️ 用的是**下划线**（`border-bottom` 当前项变色），不是 `tokens.css` 房规里禁的
   「选中态用左侧强调条」。四个域页面各有一套 tab 时也不许各造各的 —— 本套是第一个，
   后续页照抄（`c3-modules.md` §五）。 */
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

/* ── 三级标题（降级态清单）—— 它是 tab 内的**小节**，比 `.a-h2` 低一级 ───── */
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

/* ── 被拦下的排定（补丁 A3）：**红字**，且比 flash 更轻 —— 它不随时间消失 ── */
.a-blocked {
  font-size: var(--fs-sm);
  color: var(--danger);
  margin: var(--s3) 0 0;
}
</style>
