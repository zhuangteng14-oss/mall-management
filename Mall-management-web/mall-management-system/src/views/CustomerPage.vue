<template>
  <div class="customer-page">
    <div class="top-nav">
      <div class="nav-left">
        <button @click="curPage = 'home'" :class="{ active: curPage === 'home' }">商城主页</button>
      </div>
      <div class="nav-right">
        <button @click="curPage = 'msg'" :class="{ active: curPage === 'msg' }">
          消息
          <span v-if="unreadCount > 0" class="nav-red-dot"></span>
        </button>
        <button @click="curPage = 'cart'" :class="{ active: curPage === 'cart' }">购物车</button>
        <!-- 用户中心按钮：内部状态切换，清除URL参数 -->
        <button @click="goToUserCenter" :class="{ active: curPage === 'user' }">用户中心</button>
      </div>
    </div>

    <div class="content">
      <div v-show="curPage === 'home'" class="home-container">
        <HomePage @go-search="goSearch" />
      </div>

      <div v-show="curPage === 'search'" class="page">
        <GoodsSelect ref="goodsSelectRef" />
      </div>

      <div v-if="curPage === 'msg'" class="page">
        <ChatRoom />
      </div>

      <div v-if="curPage === 'cart'" class="cart-container">
        <ShoppingCart :showSwitch="false" />
      </div>

      <div v-if="curPage === 'user'" class="user-page">
        <div class="user-menu">
          <button @click="curSubPage = 'info'" :class="{ active: curSubPage === 'info' }">个人信息</button>
          <button @click="curSubPage = 'history'" :class="{ active: curSubPage === 'history' }">历史购买记录</button>
          <button @click="curSubPage = 'friend'" :class="{ active: curSubPage === 'friend' }">好友</button>
          <button @click="curSubPage = 'chat'" :class="{ active: curSubPage === 'chat' }">消息</button>
          <button @click="toLogin" class="logout">退出登录</button>
        </div>

        <UserInfoPage v-if="curSubPage === 'info'" />
        <HistoricalOrders v-if="curSubPage === 'history'" />
        <FriendRoom v-if="curSubPage === 'friend'" />
        <ChatRoom v-if="curSubPage === 'chat'" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import UserInfoPage from './UserInfoPage.vue'
import HomePage from './HomePage.vue'
import GoodsSelect from './GoodsSelect.vue'
import ShoppingCart from './ShoppingCart.vue'
import HistoricalOrders from './HistoricalOrders.vue'
import ChatRoom from './ChatRoom.vue'
import FriendRoom from './FriendRoom.vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId, clearCredentials } from '../api/auth'

const router = useRouter()
const route = useRoute()

const curPage = ref('home')
const curSubPage = ref('info')
const goodsSelectRef = ref(null)

// 未读消息数量（用于顶部红点）
const unreadCount = ref(0)
let unreadPollingTimer = null

// 获取当前用户信息
const getCurrentUser = () => {
  const cid = getCustomerId()
  if (cid && cid !== 'null' && cid !== 'undefined') {
    return { id: cid, type: 'customer' }
  }
  // 兜底：也可能取到商家 ID（但客户端应只有客户）
  const mid = getMerchantId()
  if (mid && mid !== 'null' && mid !== 'undefined') {
    return { id: mid, type: 'merchant' }
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
      // 统计 unread 为 true 的会话数量
      unreadCount.value = data.list.filter(conv => conv.unread === true).length
    }
  } catch (err) {
    handleApiError(err, '获取未读消息数失败')
  }
}

const toLogin = () => {
  clearCredentials()
  router.push('/login')
}

const goSearch = async (keyword) => {
  sessionStorage.removeItem('SEARCH_KEYWORD')
  sessionStorage.removeItem('SEARCH_RESULTS')
  curPage.value = 'search'
  await nextTick()
  if (goodsSelectRef.value) {
    goodsSelectRef.value.searchByKeyword(keyword)
  }
}

const handlePendingSearch = async () => {
  const pendingKeyword = sessionStorage.getItem('PENDING_SEARCH_KEYWORD')
  if (pendingKeyword !== null) {
    sessionStorage.removeItem('PENDING_SEARCH_KEYWORD')
    curPage.value = 'search'
    await nextTick()
    if (goodsSelectRef.value) {
      goodsSelectRef.value.searchByKeyword(pendingKeyword)
    }
    return true
  }
  return false
}

watch(
  () => route.query.back,
  (back) => {
    if (route.path === '/customer') {
      curPage.value = back === 'search' ? 'search' : 'home'
    }
  },
  { immediate: true }
)

watch(
  () => route.query.page,
  (newPage) => {
    if (route.path === '/customer') {
      if (newPage === 'cart') {
        curPage.value = 'cart'
      } else if (newPage === 'user') {
        curPage.value = 'user'
      } else if (newPage === 'chat') {
        curPage.value = 'msg'
      }
    }
  },
  { immediate: true }
)

// 监听当前页面，如果进入消息页则立即清除红点数量（用户已进入消息界面）
watch(curPage, (newVal) => {
  if (newVal === 'msg') {
    unreadCount.value = 0
  }
})

onMounted(async () => {
  // 优先处理路由参数，不管是否有待处理搜索
  if (route.query.page === 'cart') {
    curPage.value = 'cart'
  } else if (route.query.page === 'user') {
    curPage.value = 'user'
  } else if (route.query.page === 'chat') {
    curPage.value = 'msg'
  } else {
    // 没有 page 参数，再处理搜索相关
    const handled = await handlePendingSearch()
    if (!handled && !route.query.back) {
      curPage.value = 'home'
    }
  }
  // 启动轮询获取未读消息数
  await fetchUnreadCount()
  unreadPollingTimer = setInterval(fetchUnreadCount, 5000)
})

onUnmounted(() => {
  if (unreadPollingTimer) clearInterval(unreadPollingTimer)
})

// 用户中心跳转方法：直接设置内部状态，并清除 URL 参数
const goToUserCenter = () => {
  curPage.value = 'user'
  router.replace({ query: {} })
}
</script>

<style scoped>
.customer-page {
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
.top-nav button {
border: none;
background: transparent;
font-size: 15px;
color: #333;
cursor: pointer;
padding: 6px 12px;
border-radius: 4px;
transition: 0.2s;
}
.top-nav button.active {
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
.cart-container {
height: 100%;
background: #fff;
border-radius: 12px;
overflow: hidden;
box-shadow: 0 2px 8px rgba(0,0,0,0.05);
}
.user-page {
display: flex;
gap: 30px;
height: 100%;
}
.user-menu {
display: flex;
flex-direction: column;
gap: 12px;
width: 220px;
}
.user-menu button {
padding: 12px 16px;
border: 1px solid #eee;
border-radius: 6px;
background: #fff;
text-align: left;
font-size: 15px;
cursor: pointer;
transition: 0.2s;
}
.user-menu button.active {
border-color: #007bff;
background: #e6f7ff;
color: #007bff;
}
.user-menu button.logout {
border-color: #ff4d4f;
color: #ff4d4f;
}
.empty-page {
flex: 1;
display: flex;
align-items: center;
justify-content: center;
font-size: 16px;
color: #999;
}
</style>