<template>
  <!--
    D 笔 commit 9 —— 本页承载老稿 `子页面改造.html` 的 `data-page="fr"`（好友）与 `"fr0"`（好友 · 空态）
    两支（同一页的两种态，靠 `friendList.length` 切）。
    基线：`docs/美化/子页面改造.html`
        · 模板 `:666-677`（`fr`）+ `:679-693`（`fr0`）
        · 样式 `:400-436`（`.frg` / `.frt` / `.fav` / `.fn` / `.role` / `.fid` / `.empty`）
          + 全局件 `:57`（`.num`）`:159-165`（`.ph`）`:168-184`（`.btn` / `.btn-solid` / `.btn-ghost` / `.btn-text`）
          + `:281-287`（`.inp`）
    ⚠️ 基线口径 v2（`slot-registry.md` 口径 6）：`pages/` 里**没有**好友页的新稿 ⇒ **回到老稿**；
       `:787`（本页那一支）**有效**。
    ⚠️ 稿的 `.uc` / `.uc-menu` / `.stage` 三件**不进本组件** —— 落点是 `UserCenter.vue`（左菜单）
       与 `AppShell.vue` 的 `.user-page`（那一行的 padding / max-width）。本页只承载右栏那一支。
    ⚠️ 稿自身的「表里不一」照落、不改稿（`slot-registry.md` §二 commit 8/9 第 2 条）：
       稿 `:787` 的按钮文字是「**私信**」，挂的 `data-slot` 说的是「**备注 / 删除好友**」。
       本页照稿：**私信是真事件**（`goToChat`，原 Vue `:16` 就有），预留位另挂在**动作格**上。
  -->
  <main class="uc-main">

    <div class="page-h">
      <h1 class="page-h-t">好友</h1>
      <!-- 稿 `:670`（`fr` 面）/ `:683`（`fr0` 面）的 `#fr-n` / 静态文本都是「共 N 位」——
           这里落**真**条数（`friendList.length`，空态自然是「共 0 位」）。 -->
      <span class="page-h-n">共 {{ friendList.length }} 位</span>
      <!-- 稿 `:671-674` 的 `.ph-r`：两枚按钮从**浮空 fixed**（原 Vue `:21-24` 的 `.action-buttons`）
           搬进页头 —— 纯布局变更，两个 `@click` 处理函数一字未动。 -->
      <div class="page-h-r">
        <button class="btn btn-ghost" @click="openSearchDialog">搜索好友</button>
        <button class="btn btn-ghost" @click="openRequestDialog">
          好友申请 <b class="num">—</b>
        </button>
      </div>
    </div>

    <div v-if="friendList.length > 0" class="frg">
      <article v-for="friend in friendList" :key="friend.id" class="frt">
        <!-- 稿 `:782` 的 `.fav` 是 `AVA` 那个 SVG 人形占位 ——那是它的 mock。本页保留**既有真头像**
             （`friend.image || defaultAvatar`，与原 Vue `:6` 逐字一致）⇒ 只把尺寸从 80px 收到稿的 44px。 -->
        <div class="fav"><img :src="friend.image || defaultAvatar" alt="头像" /></div>
        <div class="frt-who">
          <!-- ⚠️ 角色文案沿用**既有映射**（`客户 → 用户`，原 Vue `:11`）—— 老稿 `:784` 直接印 `role`
               原值（`商家` / `客户`）。改文案 = 改产品词汇，不属本笔 ⇒ 差异已登记 registry，待用户裁。 -->
          <div class="fn"><b>{{ friend.username }}</b><i class="role">{{ friend.usertype === '客户' ? '用户' : '商家' }}</i></div>
          <!-- ⚠️ `gender` 是**既有真实字段**（原 Vue `:14` 单独一行）——老稿 `:785` 只画了 ID 一行。
               本笔按「稿未画、但既有的真实数据不删」保留（同 commit 7 保真头像的先例），并进同一行。 -->
          <div class="fid"><span class="num">{{ friend.id }}</span> · {{ friend.gender || '—' }}</div>
        </div>
        <!-- 预留位 #27（老稿 `:787`）—— 块级：动作格本身不是控件 ⇒ 只挂 `data-slot` + `title`（无 `disabled`）。
             ⚠️ 稿里这个 `data-slot` 挂在**第一张**好友卡的「私信」按钮上（`:787` 的 `${i === 0 ? … : ''}`），
             稿注 `:777-779` 自写「虚线只标第一格 —— 12 格全标会糊成一片」⇒ 那是**预览产物**。
             本页按「角标不进 Vue」的同一条口径落在**每一张**卡的动作格上 ⇒ **源码 1 处、渲染 = 好友数**（稿那「12 格」是它 mock 的条数，非本页条数，见 `slot-registry.md` §一 commit 9）。
             N 稿未标（未计）。 -->
        <div
          class="frt-act"
          data-slot="预留 · 备注 / 删除好友（friendUp / friendDe 已有端点）"
          title="预留 · 备注 / 删除好友（老稿 子页面改造.html:787）· 归属 C3〈消息〉——稿注：endpoints.js 的 message 域里已经有 friendUp / friendDe，但现在的 FriendRoom.vue 只用了列表和私信；备注、删除好友落在这里。⚠️「私信」是真事件（goToChat），不挂 disabled"
        >
          <button class="btn-text" @click="goToChat(friend)">私信</button>
        </div>
      </article>
    </div>

    <!-- 空态 = 稿 `fr0` 面（`:679-693`）。⚠️ 文案照稿换字不换态（同 commit 5 对 OrderCenter 空态的处理）。
         ⚠️ **批四（2026-10-18）接线**：输入框 + 搜索钮不再 `disabled` —— 它接通页头「搜索好友」
         用的**同一个** `.searchDialogVisible` 弹窗（能力早就存在，缺的只是入口）；有词就带进去并
         直接搜，没词就只开弹窗（弹窗自身提示「请输入用户ID」）。
         ⇒ `slot-registry` 里「稿外死控件」那条（原 `:71-76`）随本笔销账。 -->
    <div v-else class="empty">
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
          class="btn btn-solid"
          @click="searchFromEmpty"
          title="打开搜索好友弹窗（批四起与页头那颗按钮走同一处）"
        >搜索</button>
      </div>
    </div>

    <!-- ── 以下是**既有**的两只弹窗，不属于老稿的 `fr` / `fr0` 面（稿里没有弹窗）⇒ 结构、事件
         与样式**一字未动**（`.modal-*` 那一段 CSS 原样保留）。 ─────────────────────────────── -->

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
   取自 `docs/美化/子页面改造.html`：
     `:400-436`（`.frg` / `.frt` / `.fav` / `.fn` / `.role` / `.fid` / `.empty`）
     `:57`（`.num`）`:159-165`（`.ph`）`:168-184`（`.btn` / `.btn-solid` / `.btn-ghost` / `.btn-text`）
     `:281-287`（`.inp`）

   本次「从稿到 Vue」的**适配**，逐条登记：
     ① 稿的 `button{font:inherit;color:inherit;background:none;border:0;cursor:pointer}`（稿 `:121`）
        **必须搬进 scoped 且限定本页** —— 稿的「私信」是裸 `<button>`，不重设就会带上 UA 的
        `background: rgb(240,240,240)` / `border: 2px outset` / `font-family: Arial` / `font-size: 13.33px`
        （`UserInfoPage.vue` 的 commit 7 实踩过同一个坑，`AppShell.vue` 的顶栏也踩过）。
        ⚠️ 但 `.btn-ghost` 自带 `background: transparent` + `border-color` ⇒ 重设**必须在它之前**。
     ② 稿的 `body{font-family:var(--font);font-size:var(--fs-sm);color:var(--ink)}` 落到本页根 `.uc-main`；
        `background` **不搬**（壳已铺 `var(--bg)`，再铺一次会把右栏画成与左菜单不同的底）。
     ③ 页头类名 `.page-h` / `.page-h-t` / `.page-h-n` / `.page-h-r`（**不是**稿的 `.ph` / `.ph h1` /
        `.ph-n` / `.ph-r`）：与 `UserInfoPage.vue`（commit 7）、`HistoricalOrders.vue`（commit 8）
        共用一套 —— 同壳三页横向对读。⚠️ **字号逐字取稿但跨页不同值**：本页 `var(--fs-xl)` 在
        `tokens.css` 里是 **32px**（主页档），老稿自己的 `--fs-xl` 是 **28px**（子页面档，
        见 `tokens.css:12` 的「字号档冲突」条）；`UserInfoPage` 那页是 `--fs-2xl` = **36px**。
        两条差异都**不擅自拉平**，已登记 registry §一 commit 8 的「⚠️ 跨页标题字号」条，待用户裁。
     ④ 稿的 `.uc` / `.uc-menu` / `.stage` **不进本组件**（落点在 `UserCenter.vue` / `AppShell.vue`）。
     ⑤ 稿 `.frg` 的 `gap:1px; background:var(--line)` 是**发丝线网格**（稿 `:401-403` 原注：
        「`gap:1px` + 网格底色 = 分隔线，不用给每格加边框」）⇒ 逐字照落，未改成逐格 border。
     ⑥ `.fav` / `.frt-who` / `.frt-act` 是**稿没有的外壳类名** —— 稿靠 `grid-template-columns:
        44px minmax(0,1fr) auto` 直接指定三个子项，中间那格是裸 `<div>`。本页把中间那格具名，
        并给动作格具名（预留位挂在它上面，见下）；网格列定义**逐字同稿**。
     ⑦ `.inp` 只取稿 `:281-287` 的声明；`.empty .row .inp{flex:1;background:var(--surface)}`
        （稿 `:436`）一并落，否则输入框在 `.row` 里不撑开。
   ⚠️ `box-sizing` **不在此重复** —— `tokens.css` 的 `*` 重设已是全局。
   ⚠️ `.modal-overlay` 那一段（`:462-463` 的 `width/height`；原引 `:375-379` 是**错引** —— 那里是页头 `.page-h`，本笔订正）是**既有**代码（老稿里没有弹窗）——
      原文「一字未动」已由**结构二次调整（2026-10-07）**改口：只把 `100vw/100vh` 改成 `100%`，其余不动。
   ══════════════════════════════════════════════════════════════════════════════ */

/* ── 稿 `:121` 的 `button{...}` 重设（必须在 `.btn-*` 之前）────────────────────── */
button {
  font: inherit;
  color: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}

/* ── 本页根 ────────────────────────────────────────────────────────────────── */
.uc-main {
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头（稿 `.ph` `:159-165`）────────────────────────────────────────────── */
.page-h { display: flex; align-items: baseline; gap: var(--s4); margin-bottom: var(--s5); }
.page-h-t {
  font-family: var(--font-display);
  font-size: var(--fs-xl); font-weight: var(--fw-semi); letter-spacing: .5px; line-height: 1.2;
}
.page-h-n { font-size: var(--fs-sm); color: var(--ink-3); }
.page-h-r { margin-left: auto; display: flex; gap: var(--s2); align-self: center; }

/* ── 按钮（稿 `:168-184`）─────────────────────────────────────────────────── */
.btn {
  font-size: var(--fs-sm); font-weight: var(--fw-med);
  padding: 7px 16px; border-radius: var(--r2); cursor: pointer;
  border: 1px solid transparent; white-space: nowrap;
  transition: background .15s, color .15s, border-color .15s;
}
.btn-solid { background: var(--brand); color: #fff; }
.btn-solid:hover:not([disabled]) { background: var(--brand-hover); }
.btn-ghost { background: transparent; color: var(--ink-2); border-color: var(--line-2); }
.btn-ghost:hover:not([disabled]) { color: var(--brand-ink); border-color: var(--brand); }
/* 文字钮：只给行内动作（复制、私信）。一屏八个描边按钮是模板腔的来源之一（稿 `:178` 原注）。 */
.btn-text {
  font-size: var(--fs-xs); color: var(--ink-3);
  padding: 2px 0; border-bottom: 1px solid var(--line-2);
  transition: color .14s, border-color .14s;
}
.btn-text:hover { color: var(--brand-ink); border-color: var(--brand); }
/* ⚠️ 稿的全局 `button{cursor:pointer}` 不带 `:not([disabled])`，照抄会把禁用项画成可点
   （同 `UserInfoPage.vue` 的 commit 7 第 ③ 条）。本笔两处 `disabled` 在空态里。 */
button[disabled] { cursor: not-allowed; }

/* ── 发丝线网格（稿 `:405-414`）─────────────────────────────────────────────
   ⚠️ **A4（2026-10-07，用户实测 图8「好友卡的灰色底比卡片本体宽，溢出到白底外」）**：
      稿的手法（`:408` 的 `gap:1px` + `:408` 的**容器底色**当分隔线）在**不满一行**时会露馅 ——
      容器底色铺满整个网格盒，空轨道没人去盖 ⇒ 那一整格是灰的。**实测**（`123` 账号，2 位好友 /
      3 列）：`.frg` 盒宽 828px、`background: rgb(221,226,229)`（= `--line`），两张卡各 275px
      ⇒ 右边**多出 275px 一整格灰底**，正是用户看到的那块。
   ⇒ 本笔把「灰底」从**容器**搬到**卡片自己的 `box-shadow`**（见下 `.frt`）：`gap:1px` 一个不动、
      每格的白底一个不动 ⇒ 发丝线一根不少（每条仍 1px，右/下两条阴影正好落在 `gap` 里），
      而**空轨道没有卡就没有阴影** ⇒ 露出来的是容器底色（已改 `--surface` 白）。
      ⚠️ 末列的右阴影、末行的下阴影落在容器 `overflow:hidden` 之外 ⇒ **被裁掉**，那两条边正好由
      容器自己的 `border: 1px solid var(--line)` 顶替 ⇒ 四边仍闭合成框（实测无缺口、无 2px 重线）。
      ⚠️ 这是**对老稿的有意偏离**（稿注 `:406-407` 写着「不用给每格加边框」）—— 老稿那 12 格是 mock、
      恰好满行 ⇒ 它没撞上这个边界；本页按用户 图8 的诉求改。登记：`slot-registry.md`「A4」条。
      ⚠️ 备选（**未采**）：`auto-fill` → `auto-fit`。它只治「好友数 < 列数」，**不满行的最后一行照样
      露灰**（例：5 位好友 / 3 列 ⇒ 第 2 行第 3 格仍灰）⇒ 只治一半，故不采。
      ⚠️ 行数：本块在被全仓引用的最大行（`FriendRoom.vue:246`）之后 ⇒ 增行零漂移。 */
.frg {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 1px; background: var(--surface);
  border: 1px solid var(--line); border-radius: var(--r2); overflow: hidden;
}
.frt {
  display: grid; grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: center; gap: var(--s3);
  background: var(--surface); padding: var(--s4) var(--s5);
  /* ← A4：分隔线改由卡片自己画（右 + 下各 1px，正落在 `gap:1px` 里）—— 理由见上块注释。 */
  box-shadow: 1px 0 0 var(--line), 0 1px 0 var(--line);
}
.frt .fav {
  width: 44px; height: 44px; border-radius: 50%; background: var(--img); color: var(--ink-3);
  display: flex; align-items: center; justify-content: center; overflow: hidden;
}
.frt .fav img { width: 100%; height: 100%; object-fit: cover; border-radius: 50%; display: block; }
.frt-who { min-width: 0; }
.frt .fn { display: flex; align-items: center; gap: var(--s2); min-width: 0; }
.frt .fn b {
  font-size: var(--fs-sm); font-weight: var(--fw-med); color: var(--ink);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.role {
  flex: none; font-style: normal; font-size: 11px; line-height: 1.6;
  padding: 0 6px; border-radius: 2px; color: var(--ink-2); background: var(--surface-3);
}
.frt .fid { font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.frt-act { justify-self: end; }

.num { font-variant-numeric: tabular-nums; }
/* 页头「好友申请」角标：稿 `:673` 的 `2` 是编的数字 ⇒ 落 `—`（判据）。用朱砂（`--accent`）是稿原样。 */
.page-h-r .num { color: var(--accent); }

/* ── 空态（稿 `.empty` `:430-436`）────────────────────────────────────────── */
.empty { background: var(--zone); border-radius: var(--r2); padding: var(--s8) var(--s6); text-align: center; }
.empty h2 { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-semi); margin-bottom: var(--s2); }
.empty p { font-size: var(--fs-sm); color: var(--ink-3); margin-bottom: var(--s5); }
.empty .row { display: flex; gap: var(--s2); max-width: 460px; margin: 0 auto; }
.empty .row .inp { flex: 1; background: var(--surface); }

/* ── 输入框（稿 `:281-287`）───────────────────────────────────────────────── */
.inp {
  width: 100%; font-size: var(--fs-sm); color: var(--ink);
  background: var(--surface); border: 1px solid var(--line-2); border-radius: var(--r2);
  padding: 10px 14px; outline: none; transition: border-color .15s;
}
.inp::placeholder { color: var(--ink-3); }
.inp:focus { border-color: var(--brand); }

/* ── 窄档：稿 `:438-459` 的 `@media` 里只有 `.uc` / `.uc-menu` / `.p-*` / `.msgr` / `.convs`
   / `.fields` 六项，**都不在本组件**（`.frg` 用的是 `auto-fill` 自适应）⇒ 本页无对应声明。 ── */

/* ══ 两弹窗的样式：**既有**代码（老稿无弹窗面）。结构二次调整只动 `.modal-overlay` 的宽高 ══ */
.modal-overlay {
position: fixed;
top: 0;
left: 0;
width: 100%;
height: 100%;
background: rgba(0,0,0,0.5);
display: flex;
align-items: center;
justify-content: center;
z-index: 1000;
}
.modal-content {
background: white;
border-radius: 16px;
padding: 24px;
width: 400px;
max-width: 90%;
box-shadow: 0 4px 12px rgba(0,0,0,0.3);
}
.search-header {
display: flex;
gap: 10px;
margin-bottom: 20px;
}
.search-header input {
flex: 1;
padding: 8px 12px;
border: 1px solid #ddd;
border-radius: 24px;
}
.search-header button {
padding: 8px 16px;
background: #007bff;
color: white;
border: none;
border-radius: 24px;
cursor: pointer;
}
.search-result {
margin-top: 10px;
border-top: 1px solid #eee;
padding-top: 16px;
}
.result-item {
display: flex;
justify-content: space-between;
align-items: center;
}
.result-info div {
margin-bottom: 4px;
}
.result-item button {
background: #28a745;
color: white;
border: none;
padding: 6px 12px;
border-radius: 20px;
cursor: pointer;
}
.error-msg {
color: red;
margin-top: 12px;
}
.request-list h3 {
margin-top: 0;
}
.request-item {
border-bottom: 1px solid #eee;
padding: 12px 0;
}
.request-info {
margin-bottom: 10px;
}
.request-actions {
display: flex;
gap: 12px;
}
.request-actions button {
padding: 6px 16px;
border: none;
border-radius: 20px;
cursor: pointer;
}
.request-actions .agree {
background: #28a745;
color: white;
}
.request-actions .reject {
background: #dc3545;
color: white;
}
.empty-requests {
text-align: center;
color: #999;
padding: 20px;
}
</style>
