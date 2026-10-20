<template>
  <!-- A 版语言重排（2026-10-19）：
       好友页在 A 版线框里没有独立稿 ⇒ 沿用 `.page-head` / `.kicker` / `.panel` / `.btn` /
       空态这一套（与 `UserInfoPage.vue` / `MessageCenter.vue` 同一语言）。
       数据链、事件、两只弹窗与「备注 / 删除好友」预留位一个不丢。 -->
  <main class="uc-main">

    <section class="page-head">
      <div>
        <!-- 本页同时挂在客户壳与商家壳 ⇒ kicker 按既有身份判据 `isMerchantUser()` 取侧别，
             不写死 BUYER。 -->
        <div class="kicker">{{ isMerchantUser() ? 'MERCHANT / FRIENDS' : 'BUYER / FRIENDS' }}</div>
        <h1>好友 <span class="n">共 {{ friendList.length }} 位</span></h1>
        <p>搜索商家 ID 或客户 ID 添加好友；收到申请后可在这里同意或拒绝，点「私信」直接进入会话。</p>
      </div>
      <!-- 两枚按钮的 `@click` 与旧页头一致：`openSearchDialog` / `openRequestDialog`。 -->
      <div class="head-actions">
        <!-- 2026-10-19 用户令：好友页顶部必须能回到个人中心。 -->
        <button class="btn" type="button" @click="goUserCenter">← 返回个人中心</button>
        <button class="btn" type="button" @click="openSearchDialog">搜索好友</button>
        <button class="btn" type="button" @click="openRequestDialog">
          好友申请 <b class="num">—</b>
        </button>
      </div>
    </section>

    <!-- 好友列表：A 版面板块 + 好友卡。真头像、角色映射、ID / 性别逐项保留。 -->
    <section v-if="friendList.length > 0" class="panel friend-panel" aria-label="好友列表">
      <div class="panel-head">
        <div>
          <div class="kicker">FRIENDS / 全部好友</div>
          <h2>好友列表</h2>
        </div>
        <span class="tag">{{ friendList.length }} 位</span>
      </div>

      <div class="friend-grid">
        <article v-for="friend in friendList" :key="friend.id" class="friend-card">
          <div class="fc-main">
            <!-- 头像沿用既有真字段 `friend.image || defaultAvatar`，不换 mock。 -->
            <div class="fav"><img :src="friend.image || defaultAvatar" alt="头像" /></div>
            <div class="fc-who">
              <!-- 角色文案沿用既有映射（客户 → 用户），与两只弹窗同一口径。 -->
              <div class="fn">
                <b>{{ friend.username }}</b>
                <i class="role">{{ friend.usertype === '客户' ? '用户' : '商家' }}</i>
              </div>
              <!-- `gender` 是既有真实字段：稿未画也保留，取不到时落 `—`。 -->
              <div class="fid"><span class="mono">{{ friend.id }}</span> · {{ friend.gender || '—' }}</div>
            </div>
          </div>
          <div class="fc-foot">
            <!-- 预留位 #27 原样保留：`data-slot` 与 `title` 一字未动；
                 「私信」仍是真事件 `goToChat(friend)`。 -->
            <div
              class="frt-act"
              data-slot="预留 · 备注 / 删除好友（friendUp / friendDe 已有端点）"
              title="预留 · 备注 / 删除好友（老稿 子页面改造.html:787）· 归属 C3〈消息〉——稿注：endpoints.js 的 message 域里已经有 friendUp / friendDe，但现在的 FriendRoom.vue 只用了列表和私信；备注、删除好友落在这里。⚠️「私信」是真事件（goToChat），不挂 disabled"
            >
              <button class="btn xs solid" type="button" @click="goToChat(friend)">私信</button>
            </div>
          </div>
        </article>
      </div>
    </section>

    <div v-else class="empty">
      <!-- 空态：输入框与搜索钮仍走 `emptyKeyword` + `searchFromEmpty`
           （批四起与页头「搜索好友」打开同一只弹窗）。 -->
      <h2>还没有好友</h2>
      <p>输入对方的商家 ID 或客户 ID，就能把人加进来。</p>
      <div class="row">
        <input
          class="inp"
          v-model="emptyKeyword"
          placeholder="例如 84sH27Lw"
          @keyup.enter="searchFromEmpty"
        >
        <button
          class="btn solid"
          type="button"
          @click="searchFromEmpty"
          title="打开搜索好友弹窗（与页头那颗按钮走同一处）"
        >搜索</button>
      </div>
    </div>

    <!-- 以下两只弹窗的**结构、类名、事件**原样保留（`@click.self` 关闭、搜索 / 添加 / 同意 / 拒绝）；
         本次只换外观，与 A 版面板 / 按钮同一套发丝线语言。 -->

    <!-- 搜索好友弹窗 -->
    <div v-if="searchDialogVisible" class="modal-overlay" @click.self="closeSearchDialog">
      <div class="modal-content">
        <div class="search-header">
          <input type="text" v-model="searchKeyword" placeholder="输入用户ID（商家ID或客户ID）" />
          <button @click="searchUser">搜索</button>
        </div>
        <div v-if="searchResult" class="search-result">
          <div class="result-item">
            <div class="result-info">
              <div>用户名：{{ searchResult.username }}</div>
              <div>用户类型：{{ searchResult.usertype === '客户' ? '用户' : '商家' }}</div>
              <div>性别：{{ searchResult.gender || '未知' }}</div>
            </div>
            <button @click="addFriend(searchResult.id)">添加好友</button>
          </div>
        </div>
        <div v-if="searchError" class="error-msg">{{ searchError }}</div>
      </div>
    </div>

    <!-- 好友申请弹窗 -->
    <div v-if="requestDialogVisible" class="modal-overlay" @click.self="closeRequestDialog">
      <div class="modal-content request-list">
        <h3>好友申请</h3>
        <div v-if="pendingRequests.length === 0" class="empty-requests">暂无好友申请</div>
        <div v-for="req in pendingRequests" :key="req.id" class="request-item">
          <div class="request-info">
            <div>{{ req.gender ? (req.gender === '男' ? '男用户' : '女用户') : '用户' }}：{{ req.username }}（{{ req.usertype === '客户' ? '用户' : '商家' }}）希望添加您为好友，是否同意？</div>
          </div>
          <div class="request-actions">
            <button class="agree" @click="handleRequest(req.id, 'agree')">是</button>
            <button class="reject" @click="handleRequest(req.id, 'reject')">否</button>
          </div>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { http, handleApiError, urlOf, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId } from '../api/auth'

const router = useRouter()
const defaultAvatar = assetOf('/img/default_user.png')

const currentUserId = ref('')
const friendList = ref([])

const searchDialogVisible = ref(false)
const searchKeyword = ref('')
const searchResult = ref(null)
const searchError = ref(''); const emptyKeyword = ref('')

const requestDialogVisible = ref(false)
const pendingRequests = ref([])

// 获取当前登录用户ID（优先取 merchantid，再取 customerid）
const getCurrentUserId = () => {
  let id = getMerchantId()
  if (id && id !== 'null' && id !== 'undefined') return id
  id = getCustomerId()
  if (id && id !== 'null' && id !== 'undefined') return id
  return null
}

// 判断当前用户是否为商家
const isMerchantUser = () => {
  const mid = getMerchantId()
  return mid && mid !== 'null' && mid !== 'undefined'
}

/** 返回个人中心：商家壳 / 客户壳各有自己的中转站（2026-10-19 用户令）。 */
const goUserCenter = () => {
  router.push(isMerchantUser() ? '/merchant/user' : '/customer/user')
}

// 加载好友列表
const loadFriendList = async () => {
  const uid = getCurrentUserId()
  if (!uid) return
  currentUserId.value = uid
  const params = new URLSearchParams()
  params.append('customerId', uid)
  try {
    const res = await http.post(endpoints.message.friendSelect, params.toString())
    const data = await res.json()
    if (data.success) {
      // 过滤掉自己
      friendList.value = data.list.filter(f => f.id !== uid)
    }
  } catch (err) {
    handleApiError(err, '加载好友列表失败')
  }
}

// 搜索用户
const searchUser = async () => {
  if (!searchKeyword.value.trim()) {
    searchError.value = '请输入用户ID'
    return
  }
  searchError.value = ''
  const params = new URLSearchParams()
  params.append('userId', searchKeyword.value.trim())
  try {
    const res = await http.post(endpoints.message.userSelect, params.toString())
    const data = await res.json()
    if (data.success) {
      searchResult.value = data.user
    } else {
      searchResult.value = null
      searchError.value = data.msg || '用户不存在'
    }
  } catch (err) {
    searchError.value = '网络错误'
  }
}

// 添加好友
const addFriend = async (friendId) => {
  if (friendId === currentUserId.value) {
    alert('不能添加自己为好友')
    return
  }
  const params = new URLSearchParams()
  params.append('customerId', currentUserId.value)
  params.append('friendId', friendId)
  try {
    const res = await http.post(endpoints.message.friendAdd, params.toString())
    const data = await res.json()
    if (data.success) {
      alert('好友申请已发送')
      closeSearchDialog()
    } else {
      alert(data.msg)
    }
  } catch (err) {
    alert('请求失败')
  }
}

// 打开/关闭搜索弹窗
const openSearchDialog = () => {
  searchKeyword.value = ''
  searchResult.value = null
  searchError.value = ''
  searchDialogVisible.value = true
}
const closeSearchDialog = () => { searchDialogVisible.value = false }
// 批四：空态搜索框接通既有弹窗 —— 有词就带进去并直接搜，没词就只开弹窗（弹窗里会提示「请输入」）。
const searchFromEmpty = () => { const kw = emptyKeyword.value.trim(); openSearchDialog(); if (kw) { searchKeyword.value = kw; searchUser() } }

// 加载待处理的好友申请
const loadPendingRequests = async () => {
  const uid = getCurrentUserId()
  if (!uid) return
  const params = new URLSearchParams()
  params.append('friendId', uid)
  try {
    const res = await http.post(endpoints.message.selectFriend, params.toString())
    const data = await res.json()
    if (data.success) {
      pendingRequests.value = data.list
    } else {
      pendingRequests.value = []
    }
  } catch (err) {
    handleApiError(err, '加载申请失败')
  }
}

// 打开/关闭好友申请弹窗
const openRequestDialog = async () => {
  await loadPendingRequests()
  requestDialogVisible.value = true
}
const closeRequestDialog = () => {
  requestDialogVisible.value = false
}

// 处理好友申请（同意/拒绝）
const handleRequest = async (applicantId, action) => {
  const params = new URLSearchParams()
  params.append('customerId', applicantId)
  params.append('friendId', currentUserId.value)
  let url = ''
  if (action === 'agree') {
    url = urlOf(endpoints.message.friendUp)
  } else {
    url = urlOf(endpoints.message.friendDe)
  }
  try {
    const res = await http.post(url, params.toString())
    const data = await res.json()
    if (data.success) {
      alert(action === 'agree' ? '已添加为好友' : '已拒绝申请')
      await loadFriendList()      // 刷新好友列表
      await loadPendingRequests() // 刷新申请列表
      closeRequestDialog()
    } else {
      alert(data.msg)
    }
  } catch (err) {
    alert('操作失败')
  }
}

// 跳转到聊天页面
const goToChat = (friend) => {
  if (friend.id === currentUserId.value) {
    alert('不能与自己聊天')
    return
  }
  const otherType = friend.usertype === '客户' ? 'customer' : 'merchant'
  const isMerchant = isMerchantUser()
  const basePath = isMerchant ? '/merchant' : '/customer'
  // 添加时间戳确保每次 URL 不同，强制路由更新
  router.push({
    path: basePath,
    query: {
      page: 'chat',
      otherId: friend.id,
      otherType: otherType,
      _t: Date.now()
    }
  })
}

onMounted(() => {
  loadFriendList()
})
</script>


<style scoped>
/* ══════════════════════════════════════════════════════════════════════════════
   A 版语言重排（2026-10-19）。
   好友页在 A 版线框里没有独立稿 ⇒ 沿用同一套语言：
     · `.page-head` / `.kicker` / `.head-actions`（同 `UserInfoPage.vue` / `MessageCenter.vue`）
     · `.panel` / `.panel-head` / `.tag` / `.btn`（同 `UserInfoPage.vue`）
     · 空态 / 输入框（同 A 版 `.empty` / `.inp`）
   两只弹窗只换外观：结构、类名、开关与事件均未动。
   ══════════════════════════════════════════════════════════════════════════════ */

button, input { font: inherit; color: inherit; }
button { background: none; border: 0; cursor: pointer; }
button[disabled] { cursor: not-allowed; }

/* ── 本页根 ────────────────────────────────────────────────────────────────── */
.uc-main {
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头（A 版 `.page-head` / `.kicker`）─────────────────────────────────── */
.page-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s6);
  align-items: end;
  padding-bottom: var(--s5);
  border-bottom: 1px solid var(--line);
  margin-bottom: 38px;
}
.kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .16em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.page-head h1 {
  margin-top: 6px;
  font-family: var(--font-display);
  font-size: 48px;
  font-weight: var(--fw-semi);
  line-height: 1.02;
  letter-spacing: -.035em;
  color: var(--ink);
}
.page-head h1 .n {
  margin-left: var(--s2);
  font-family: var(--font);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  letter-spacing: 0;
  color: var(--ink-3);
}
.page-head p {
  margin-top: 8px;
  max-width: 58ch;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-2);
}
.head-actions { display: flex; gap: var(--s2); flex: 0 0 auto; align-items: center; }

/* ── 按钮（A 版 `.btn`）───────────────────────────────────────────────────── */
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  border: 1px solid var(--brand-ink); background: var(--surface); color: var(--brand-ink);
  padding: 10px 15px; font-size: 13px; line-height: 1.2; text-decoration: none;
  white-space: nowrap;
  transition: background-color .12s linear, color .12s linear;
}
.btn:hover { background: var(--brand-ink); color: var(--surface); }
.btn.solid { background: var(--brand-ink); color: var(--surface); }
.btn.solid:hover { background: var(--surface); color: var(--brand-ink); }
.btn.ghost { background: transparent; }
.btn.xs { padding: 5px 8px; font-size: 11px; }
.num { font-variant-numeric: tabular-nums; }
.mono { font-family: var(--font-mono); }
/* 页头「好友申请」角标：沿用旧页头的朱砂色（`--accent`）。 */
.head-actions .num { color: var(--accent); font-family: var(--font-mono); font-weight: var(--fw-med); }

/* ── 面板（A 版 `.panel` / `.panel-head` / `.tag`）────────────────────────── */
.panel { border: 1px solid var(--line); background: var(--surface); padding: 22px 24px; }
.panel-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: var(--s4); margin-bottom: var(--s5);
}
.panel-head h2 {
  font-family: var(--font-display); font-size: var(--fs-lg); line-height: 1.15;
  font-weight: var(--fw-semi); letter-spacing: -.02em;
}
.panel-head .kicker { margin-bottom: 5px; }
.tag {
  display: inline-flex; align-items: center; border: 1px solid var(--line-2);
  padding: 2px 7px; font-family: var(--font-mono); font-size: 9px;
  letter-spacing: .05em; color: var(--ink-3); line-height: 1.5; white-space: nowrap;
}

/* ── 好友卡（A 版卡面语言：1px 发丝线 + 面板内留白）───────────────────────── */
.friend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: var(--s3);
}
.friend-card {
  display: flex; flex-direction: column; gap: var(--s4);
  min-width: 0; padding: var(--s4) var(--s5);
  border: 1px solid var(--line-2); border-radius: var(--r2);
  background: var(--surface);
}
.fc-main { display: flex; align-items: center; gap: var(--s3); min-width: 0; }
.fav {
  width: 56px; height: 56px; flex: none; border-radius: 50%;
  background: var(--img); overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.fav img { width: 100%; height: 100%; object-fit: cover; border-radius: 50%; display: block; }
.fc-who { min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.fn { display: flex; align-items: center; gap: var(--s2); min-width: 0; }
.fn b {
  font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-med);
  color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.role {
  flex: none; font-style: normal; font-family: var(--font-mono);
  font-size: 9px; line-height: 1.5; letter-spacing: .05em;
  padding: 2px 7px; border: 1px solid var(--line-2);
  color: var(--ink-3); white-space: nowrap;
}
.fid {
  font-size: var(--fs-xs); color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.fc-foot {
  display: flex; align-items: center; justify-content: flex-end;
  margin-top: auto; padding-top: var(--s3); border-top: 1px solid var(--line);
}
.frt-act { display: flex; align-items: center; }

/* ── 空态（A 版 `.empty`）────────────────────────────────────────────────── */
.empty {
  border: 1px solid var(--line); border-radius: var(--r2);
  background: var(--surface-2); padding: var(--s8) var(--s6); text-align: center;
}
.empty h2 {
  font-family: var(--font-display); font-size: var(--fs-lg);
  font-weight: var(--fw-semi); margin-bottom: var(--s2);
}
.empty p { font-size: var(--fs-sm); color: var(--ink-3); margin-bottom: var(--s5); }
.empty .row { display: flex; gap: var(--s2); max-width: 460px; margin: 0 auto; }
.empty .row .inp { flex: 1; background: var(--surface); }

/* ── 输入框（A 版 `.inp`）────────────────────────────────────────────────── */
.inp {
  width: 100%; font-size: var(--fs-sm); color: var(--ink);
  background: var(--surface); border: 1px solid var(--line-2);
  border-radius: var(--r2); padding: 10px 14px; outline: none;
  transition: border-color .15s;
}
.inp::placeholder { color: var(--ink-3); }
.inp:focus { border-color: var(--brand-ink); }

/* ── 窄档 ─────────────────────────────────────────────────────────────────── */
@media (max-width: 860px) {
  .page-head { grid-template-columns: 1fr; align-items: start; }
  .head-actions { justify-content: flex-start; }
  .page-head h1 { font-size: 38px; }
}
@media (max-width: 600px) {
  .page-head h1 { font-size: 32px; }
  .friend-grid { grid-template-columns: 1fr; }
  .empty { padding: var(--s6) var(--s4); }
  .empty .row { flex-direction: column; }
}

/* ══ 两只弹窗：结构 / 类名 / 开关与事件原样，仅换外观 ═════════════════════════ */
.modal-overlay {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(0, 0, 0, 0.42);
  display: flex; align-items: center; justify-content: center;
  padding: 20px;
}
.modal-content {
  width: min(560px, 100%); padding: 24px;
  background: var(--surface); border: 1px solid var(--line-2);
  border-radius: var(--r2); box-shadow: 0 12px 48px rgba(0, 0, 0, 0.22);
}
.search-header { display: flex; gap: var(--s2); margin-bottom: var(--s5); }
.search-header input {
  flex: 1; min-width: 0; padding: 10px 14px;
  border: 1px solid var(--line-2); border-radius: var(--r2);
  background: var(--surface); font-size: var(--fs-sm); outline: none;
  transition: border-color .15s;
}
.search-header input:focus { border-color: var(--brand-ink); }
.search-header button {
  flex: none; padding: 0 18px;
  background: var(--brand-ink); color: var(--surface);
  border: 1px solid var(--brand-ink); border-radius: var(--r1);
  font-size: var(--fs-sm);
}
.search-header button:hover { background: var(--surface); color: var(--brand-ink); }
.search-result { margin-top: var(--s4); border-top: 1px solid var(--line); padding-top: var(--s4); }
.result-item { display: flex; justify-content: space-between; align-items: center; gap: var(--s4); }
.result-info { min-width: 0; }
.result-info div { margin-bottom: 4px; font-size: var(--fs-sm); color: var(--ink-2); }
.result-item button {
  flex: none; padding: 7px 12px;
  background: var(--brand-ink); color: var(--surface);
  border: 1px solid var(--brand-ink); border-radius: var(--r1);
  font-size: var(--fs-xs);
}
.result-item button:hover { background: var(--surface); color: var(--brand-ink); }
.error-msg { margin-top: var(--s3); font-size: var(--fs-xs); color: var(--danger); }

.request-list h3 {
  margin: 0 0 var(--s4); font-family: var(--font-display);
  font-size: var(--fs-md); font-weight: var(--fw-semi);
}
.request-item { border-bottom: 1px solid var(--line); padding: var(--s3) 0; }
.request-item:last-of-type { border-bottom: 0; }
.request-info {
  margin-bottom: var(--s3); font-size: var(--fs-sm);
  line-height: 1.7; color: var(--ink-2);
}
.request-actions { display: flex; gap: var(--s2); }
.request-actions button {
  padding: 6px 16px; border: 1px solid var(--line-2); border-radius: var(--r1);
  background: var(--surface); color: var(--ink-2); font-size: var(--fs-xs);
  transition: background .12s, color .12s, border-color .12s;
}
.request-actions .agree {
  background: var(--brand-ink); border-color: var(--brand-ink); color: var(--surface);
}
.request-actions .agree:hover { background: var(--surface); color: var(--brand-ink); }
.request-actions .reject:hover { border-color: var(--danger); color: var(--danger); }
.empty-requests {
  padding: var(--s5) 0; text-align: center;
  font-size: var(--fs-sm); color: var(--ink-3);
}
</style>
