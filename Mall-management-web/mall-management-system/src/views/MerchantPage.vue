<template>
  <div class="merchant-page">
    <div class="top-nav">
      <div class="nav-left">
        <router-link
          v-for="item in navTop.left"
          :key="item.tab"
          :to="item.to"
          class="nav-btn"
          :class="{ active: route.meta.tab === item.tab }"
        >
          {{ item.label }}
          <span v-if="item.badge === 'unread' && unreadCount > 0" class="nav-red-dot"></span>
        </router-link>
      </div>
      <div class="nav-right">
        <router-link
          v-for="item in navTop.right"
          :key="item.tab"
          :to="item.to"
          class="nav-btn"
          :class="{ active: route.meta.tab === item.tab }"
        >
          {{ item.label }}
          <span v-if="item.badge === 'unread' && unreadCount > 0" class="nav-red-dot"></span>
        </router-link>
      </div>
    </div>

    <div class="content">
      <div :class="layoutClass">
        <router-view v-slot="{ Component }">
          <KeepAlive :include="CACHE_TABS">
            <component :is="Component" />
          </KeepAlive>
        </router-view>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId, isLoggedIn } from '../api/auth'
import { MERCHANT_NAV, navFor } from '../config/nav'

// ── R2（2026-10-05）：商家壳由「内部 `curPage` / `curSubPage` ref + `v-if`/`v-show`」改为
//    「嵌套路由 + `<router-view>`」—— 与 R1 的 `CustomerPage.vue` 逐字同构（用户裁决：镜像 R1）──
// 壳现在只剩三件事：顶栏、内容区外壳、未读红点轮询。**页面选择权全交给路由**（见 `router/index.js`）。
// 被删掉的：`curPage` / `curSubPage` / `goodsSelectRef` / `orderTab` / 两个 `route.query` watcher /
//   `goSearch` / `toggleOrderTab` / `goToUserCenter` / `toLogin` —— 它们现在分别是「路由」、
//   `OrderPane.vue`、`UserCenter.vue` 的职责。顶栏改由 `src/config/nav.js` 的表驱动（用户裁决 6）。
//
// ⚠️ **本壳不会因切 tab 而重挂**（这是下面 `onMounted` 里那支未读轮询「只跑一次」的**唯一**依据）：
//    `/merchant` 是**父记录**，`children` 之间切换**不重建父实例**（同 R1 记于 §5-X (4) 的性质）。
//    后人若把子路由提成**平级记录**、或给壳加 `:key`（如 `<router-view :key="route.path">`），
//    这个性质立刻消失 —— 表现是「每切一次 tab 就多一支 5s 轮询、永不回收」。
//
// ⚠️ `CACHE_TABS` 的匹配依据是**组件的 name**（不是路径）。这两个 name 写在
//    `views/HomeTab.vue` 与 `views/GoodsSelect.vue` 的 `defineOptions` 里 ——
//    改名 = 缓存**静默失效**（不报错，只是切回来重新挂载、轮播重置 / 搜索态丢失）。改名前先 grep `include`。
//    为什么只缓存这两个：R2 前 home / search 是 `v-show`（切走**不卸载**）、其余是 `v-if`（卸载）
//    ⇒ 只缓存这两个才与改前**逐字等价**。⚠️ **两壳的 `CACHE_TABS` 逐字相同**（商家侧 R2 前同样是
//    home / search `v-show`）—— 这正是 `HomeTab` 可以两壳共用的前提；两个壳的 `<KeepAlive>` 实例
//    各自独立，**不会串缓存**。
//    ⚠️ **别把 ChatRoom 加进来**：它有 2s 轮询（`ChatRoom.vue:369`），缓存 = 离开消息页后
//    **后台继续轮询**（新行为，且与壳里那支 5s 未读轮询重复）。
const CACHE_TABS = ['HomeTab', 'GoodsSelect']

const route = useRoute()
const router = useRouter()

// 内容区外壳类名随路由走（`meta.layout`）—— 与 R2 前那 5 个兄弟 div 的类名**一一对应**
// （`.home-container` / `.page` / `.order-container` / `.user-page`），所以布局逐字不变。
// ⚠️ 布局类名的**所有权**留在本壳（页组件自己不带布局类），R6 抽 AppShell 时再谈归属。
const layoutClass = computed(() => route.meta.layout || 'page')

// 未读消息数量（用于顶部红点）
const unreadCount = ref(0)
let unreadPollingTimer = null

// 获取当前用户信息
const getCurrentUser = () => {
  const mid = getMerchantId()
  if (mid && mid !== 'null' && mid !== 'undefined') {
    return { id: mid, type: 'merchant' }
  }
  // 兜底：也可能取到客户ID（但商家端应只有商家）
  const cid = getCustomerId()
  if (cid && cid !== 'null' && cid !== 'undefined') {
    return { id: cid, type: 'customer' }
  }
  return null
}

// 获取未读消息数量
const fetchUnreadCount = async () => {
  const user = getCurrentUser()
  if (!user) return
  const params = new URLSearchParams()
  params.append('currentId', user.id)
  params.append('currentType', user.type)
  try {
    const res = await http.post(endpoints.message.conversations, params.toString())
    const data = await res.json()
    if (data.success) {
      unreadCount.value = data.list.filter(conv => conv.unread === true).length
    }
  } catch (err) {
    handleApiError(err, '获取未读消息数失败')
  }
}

// 进入消息页则立即清红点（R2：改看路由 —— 壳里已无 `curPage`；判据与 R2 前 `curPage === 'msg'` **完全一致**，
//   即用户中心里的「消息」子页 `/merchant/user/chat` **不清**）
watch(() => route.path, (p) => {
  if (p === '/merchant/msg') unreadCount.value = 0
})

// 跨页待搜索关键词（由商品详情页的搜索框写入：`ProductPage.vue:380` 写、`:382` 推 `/merchant`）
// R2：改为**推路由** `/merchant/search?kw=`（关键词由 URL 承载）；R2 前是
//     置 `curPage='search'` + `nextTick` 后调子组件 ref，`<router-view>` 下没有 ref 可用。
const handlePendingSearch = () => {
  const pendingKeyword = sessionStorage.getItem('PENDING_SEARCH_KEYWORD')
  if (pendingKeyword === null) return
  sessionStorage.removeItem('PENDING_SEARCH_KEYWORD')
  router.push({ path: '/merchant/search', query: { kw: pendingKeyword } })
}

onMounted(async () => {
  handlePendingSearch()
  // 启动轮询获取未读消息数
  await fetchUnreadCount()
  unreadPollingTimer = setInterval(fetchUnreadCount, 5000)
})

onUnmounted(() => {
  if (unreadPollingTimer) clearInterval(unreadPollingTimer)
})

// R3：顶栏的登录态分支 —— **在这里算一次**（`isLoggedIn()` 不是响应式，写成 `computed` 会**永远不
//   更新**；见 `config/nav.js` 的 `navFor`）。壳每次进出都会重建 ⇒ 登录 / 退出 / 401 后必然重算，够用。
const navTop = navFor(MERCHANT_NAV, isLoggedIn())
</script>

<style scoped>
.merchant-page {
width: 100vw;
height: 100vh;
display: flex;
flex-direction: column;
background: #f9fafb;
}
.top-nav {
height: 55px;
display: flex;
justify-content: space-between;
align-items: center;
padding: 0 40px;
border-bottom: 1px solid #eee;
background: #fff;
}
/* R2：顶栏三个入口由 `button` 改成 `router-link`（渲染成 `a`）⇒ 选择器随之改；
   加 `text-decoration: none`，其余属性与改前的 `.top-nav button` 逐条相同。 */
.top-nav a {
  display: inline-block;
  border: none;
  background: transparent;
  font-size: 15px;
  color: #333;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 4px;
  transition: 0.2s;
  text-decoration: none;
}
.top-nav a.active {
color: #007bff;
font-weight: 500;
}
.nav-left,
.nav-right {
display: flex;
gap: 12px;
}
.nav-red-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  background-color: red;
  border-radius: 50%;
  margin-left: 6px;
  vertical-align: middle;
}
.content {
flex: 1;
padding: 30px;
}
.page {
padding: 20px;
}
.home-container {
position: absolute;
top: 55px;
left: 0;
width: 100vw;
height: calc(100vh - 55px);
background: #f5f5f5;
padding: 0;
margin: 0;
z-index: 1;
}
.order-container {
height: 100%;
background: #fff;
border-radius: 12px;
box-shadow: 0 2px 8px rgba(0,0,0,0.05);
overflow: hidden;
position: relative;
}
.user-page {
display: flex;
gap: 30px;
height: 100%;
}
/* ⚠️ 用户中心左菜单的样式（`.user-menu*`）已随 markup 搬进 `UserCenter.vue` 自己的
   `<style scoped>` 里（R2，2026-10-05）—— 两壳共用同一个菜单组件，样式各归其主，此处不再保留死规则。
   `.user-page` 留在本壳：它是**布局容器**（`meta.layout`），不是菜单自己的样式。 */
.empty-page {
flex: 1;
display: flex;
align-items: center;
justify-content: center;
font-size: 16px;
color: #999;
}
</style>
