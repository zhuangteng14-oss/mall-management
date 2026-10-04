<template>
  <div class="friend-room">
    <div class="friends-grid" v-if="friendList.length > 0">
      <div v-for="friend in friendList" :key="friend.id" class="friend-card">
        <div class="avatar">
          <img :src="friend.image || defaultAvatar" alt="头像" />
        </div>
        <div class="friend-info">
          <div class="name-row">
            <span class="username">{{ friend.username }}</span>
            <span class="user-type">{{ friend.usertype === '客户' ? '用户' : '商家' }}</span>
          </div>
          <div class="user-id">{{ friend.id }}</div>
          <div class="gender">{{ friend.gender || '未知' }}</div>
        </div>
        <button class="chat-btn" @click="goToChat(friend)">私信</button>
      </div>
    </div>
    <div v-else class="empty-friends">暂无好友，快去添加吧~</div>

    <div class="action-buttons">
      <button class="search-friend-btn" @click="openSearchDialog">搜索好友</button>
      <button class="friend-request-btn" @click="openRequestDialog">好友申请</button>
    </div>

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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { http, handleApiError, urlOf, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId } from '../api/auth'

const router = useRouter()
const defaultAvatar = assetOf('/img/default-avatar.jpg')

const currentUserId = ref('')
const friendList = ref([])

const searchDialogVisible = ref(false)
const searchKeyword = ref('')
const searchResult = ref(null)
const searchError = ref('')

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
const closeSearchDialog = () => {
  searchDialogVisible.value = false
}

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
.friend-room {
position: relative;
width: 100%;
height: 100%;
overflow-y: auto;
padding: 20px;
box-sizing: border-box;
}
.friends-grid {
display: grid;
grid-template-columns: repeat(4, 1fr);
gap: 20px;
}
.friend-card {
background: #fff;
border-radius: 16px;
box-shadow: 0 2px 8px rgba(0,0,0,0.1);
padding: 16px;
display: flex;
flex-direction: column;
align-items: center;
text-align: center;
transition: transform 0.2s;
}
.friend-card:hover {
transform: translateY(-4px);
}
.avatar {
width: 80px;
height: 80px;
border-radius: 50%;
overflow: hidden;
margin-bottom: 12px;
}
.avatar img {
width: 100%;
height: 100%;
object-fit: cover;
}
.friend-info {
width: 100%;
margin-bottom: 12px;
}
.name-row {
display: flex;
justify-content: center;
align-items: baseline;
gap: 8px;
flex-wrap: wrap;
}
.username {
font-size: 16px;
font-weight: 600;
}
.user-type {
font-size: 12px;
color: #007bff;
background: #e6f7ff;
padding: 2px 6px;
border-radius: 12px;
}
.user-id {
font-size: 12px;
color: #888;
margin: 4px 0;
}
.gender {
font-size: 12px;
color: #666;
}
.chat-btn {
background: #28a745;
border: none;
border-radius: 20px;
padding: 6px 12px;
font-size: 12px;
cursor: pointer;
color: white;
}
.chat-btn:hover {
background: #218838;
}
.empty-friends {
text-align: center;
padding: 40px;
color: #999;
font-size: 16px;
}
.action-buttons {
position: fixed;
bottom: 30px;
right: 30px;
display: flex;
gap: 12px;
z-index: 10;
}
.search-friend-btn, .friend-request-btn {
padding: 10px 20px;
border: none;
border-radius: 30px;
font-size: 14px;
cursor: pointer;
box-shadow: 0 2px 6px rgba(0,0,0,0.2);
}
.search-friend-btn {
background: #007bff;
color: white;
}
.friend-request-btn {
background: #28a745;
color: white;
}
.modal-overlay {
position: fixed;
top: 0;
left: 0;
width: 100vw;
height: 100vh;
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