<template>
  <div class="chat-room">
    <div class="chat-header">
      <div class="current-conversation">
        <span v-if="currentOther">与 {{ currentOther.username }} 的会话</span>
        <span v-else>请选择一个会话</span>
      </div>
      <div class="conversation-selector">
        <div class="custom-select" @click="toggleDropdown">
          <div class="selected-display">
            {{ selectedConversationName || '-- 选择会话 --' }}
            <span class="arrow">▼</span>
          </div>
          <div class="dropdown-list" v-if="dropdownOpen">
            <div
              v-for="conv in conversations"
              :key="conv.other_id"
              class="dropdown-item"
              :class="{ active: selectedConversation === conv.other_id }"
              @click="selectConversation(conv)"
            >
              <span class="conv-label">
                {{ conv.other_type === 'customer' ? '用户' : '商家' }}：{{ conv.other_id }}
              </span>
              <span v-if="conv.unread" class="red-dot"></span>
            </div>
            <div v-if="conversations.length === 0" class="dropdown-empty">暂无会话</div>
          </div>
        </div>
      </div>
    </div>

    <div class="message-list" ref="messageListRef">
      <div v-for="msg in messages" :key="msg.id" class="message-item"
           :class="{ 'self': isSelfMessage(msg), 'other': !isSelfMessage(msg) }">
        <div class="message-bubble">
          <div class="message-content">
            <template v-if="isProductMessage(msg)">
              <div class="product-card" @click="goToProduct(parseProductInfo(msg.content))">
                <div class="product-card-left">
                  <img :src="parseProductInfo(msg.content).goodImg" alt="商品图片" />
                </div>
                <div class="product-card-right">
                  <div class="product-name">{{ parseProductInfo(msg.content).goodName }}</div>
                  <div class="product-detail">型号：{{ parseProductInfo(msg.content).productCode }}</div>
                  <div class="product-detail">价格：¥{{ parseProductInfo(msg.content).price }}</div>
                  <div class="product-link">点击查看商品详情 →</div>
                </div>
              </div>
            </template>
            <template v-else>
              {{ msg.content }}
            </template>
          </div>
          <div class="message-time">{{ formatDateTime(msg.create_time) }}</div>
        </div>
      </div>
    </div>

    <div class="message-input-area">
      <input type="text" v-model="newMessage" placeholder="输入消息..." @keyup.enter="sendMessage" />
      <button @click="sendMessage">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const currentId = ref('')
const currentType = ref('')
const conversations = ref([])
const selectedConversation = ref('')
const currentOther = ref(null)
const messages = ref([])
const newMessage = ref('')
const messageListRef = ref(null)

// 自定义下拉框状态
const dropdownOpen = ref(false)

const selectedConversationName = computed(() => {
  if (!selectedConversation.value) return ''
  const conv = conversations.value.find(c => c.other_id === selectedConversation.value)
  if (!conv) return ''
  const typeLabel = conv.other_type === 'customer' ? '用户' : '商家'
  return `${typeLabel}：${conv.other_id}`
})

let pollingTimer = null

// 判断是否为商品消息
const isProductMessage = (msg) => {
  return msg.content && msg.content.startsWith('[PROD]')
}

// 解析商品信息
const parseProductInfo = (content) => {
  try {
    const jsonStr = content.substring(6)
    return JSON.parse(jsonStr)
  } catch (e) {
    return null
  }
}

// 跳转到商品详情页
const goToProduct = (productInfo) => {
  if (!productInfo) return
  router.push({
    path: '/product',
    query: {
      Id: productInfo.id,
      MerchantId: productInfo.merchantId,
      GoodName: productInfo.goodName,
      Price: productInfo.price,
      GoodImg: productInfo.goodImg,
      GoodType: productInfo.goodType,
      ProductCode: productInfo.productCode
    }
  })
}

// 初始化当前用户
const initCurrentUser = () => {
  const path = window.location.pathname
  if (path.includes('/merchant')) {
    const mid = sessionStorage.getItem('merchantid')
    if (mid && mid !== 'null' && mid !== 'undefined') {
      currentId.value = mid
      currentType.value = 'merchant'
      return
    }
  } else if (path.includes('/customer')) {
    const cid = sessionStorage.getItem('customerid')
    if (cid && cid !== 'null' && cid !== 'undefined') {
      currentId.value = cid
      currentType.value = 'customer'
      return
    }
  }
  let id = sessionStorage.getItem('merchantid')
  if (id && id !== 'null' && id !== 'undefined') {
    currentId.value = id
    currentType.value = 'merchant'
  } else {
    id = sessionStorage.getItem('customerid')
    if (id && id !== 'null' && id !== 'undefined') {
      currentId.value = id
      currentType.value = 'customer'
    } else {
      console.error('未登录')
    }
  }
}

// 获取用户信息
const fetchUserInfo = async (userId) => {
  try {
    const res = await fetch('http://localhost:8088/message/userSelect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ userId }).toString()
    })
    const data = await res.json()
    if (data.success) return data.user
  } catch (err) {
    console.error('获取用户信息失败', err)
  }
  return null
}

// 加载会话列表
const loadConversations = async () => {
  if (!currentId.value) return
  const params = new URLSearchParams()
  params.append('currentId', currentId.value)
  params.append('currentType', currentType.value)
  try {
    const res = await fetch('http://localhost:8088/message/conversations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
    const data = await res.json()
    if (data.success) {
      conversations.value = data.list
      if (currentOther.value && !conversations.value.some(c => c.other_id === currentOther.value.id)) {
        conversations.value.unshift({
          other_id: currentOther.value.id,
          other_type: currentOther.value.type,
          unread: false
        })
      }
    }
  } catch (err) {
    console.error('加载会话列表失败', err)
  }
}

// 加载聊天历史
const loadMessages = async (otherId, otherType) => {
  const params = new URLSearchParams()
  params.append('currentId', currentId.value)
  params.append('currentType', currentType.value)
  params.append('otherId', otherId)
  params.append('otherType', otherType)
  try {
    const res = await fetch('http://localhost:8088/message/history', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
    const data = await res.json()
    if (data.success) {
      messages.value = data.list
      await nextTick()
      scrollToBottom()
    }
  } catch (err) {
    console.error('加载消息失败', err)
  }
}

// 打开会话
const openConversation = async (otherId, otherType) => {
  if (!currentId.value) return false
  if (otherId === currentId.value) {
    alert('不能与自己聊天')
    return false
  }
  let username = otherId
  const userInfo = await fetchUserInfo(otherId)
  if (userInfo && userInfo.username) username = userInfo.username
  currentOther.value = { id: otherId, type: otherType, username }
  await loadMessages(otherId, otherType)
  if (!conversations.value.some(c => c.other_id === otherId)) {
    conversations.value.unshift({ other_id: otherId, other_type: otherType, unread: false })
  }
  selectedConversation.value = otherId
  return true
}

// 选择会话（自定义下拉框）
const selectConversation = async (conv) => {
  dropdownOpen.value = false
  if (selectedConversation.value === conv.other_id) return
  selectedConversation.value = conv.other_id
  await openConversation(conv.other_id, conv.other_type)
}

// 切换下拉框
const toggleDropdown = () => {
  dropdownOpen.value = !dropdownOpen.value
}

// 点击外部关闭下拉框
const handleClickOutside = (event) => {
  if (!event.target.closest('.conversation-selector')) {
    dropdownOpen.value = false
  }
}

// 轮询更新
const pollUpdates = async () => {
  if (!currentId.value) return
  await loadConversations()
  if (currentOther.value) {
    const params = new URLSearchParams()
    params.append('currentId', currentId.value)
    params.append('currentType', currentType.value)
    params.append('otherId', currentOther.value.id)
    params.append('otherType', currentOther.value.type)
    try {
      const res = await fetch('http://localhost:8088/message/history', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params.toString()
      })
      const data = await res.json()
      if (data.success) {
        const newMessages = data.list
        if (newMessages.length > messages.value.length) {
          const added = newMessages.slice(messages.value.length)
          if (added.length > 0) {
            messages.value.push(...added)
            await nextTick()
            scrollToBottom()
          }
        }
      }
    } catch (err) {
      console.error('轮询刷新消息失败', err)
    }
  }
}

const isSelfMessage = (msg) => msg.from_id === currentId.value && msg.from_type === currentType.value

const formatDateTime = (timeStr) => {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')} ${String(date.getHours()).padStart(2,'0')}:${String(date.getMinutes()).padStart(2,'0')}:${String(date.getSeconds()).padStart(2,'0')}`
}

const scrollToBottom = () => {
  if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
}

const sendMessage = async () => {
  if (!newMessage.value.trim()) return
  if (!currentOther.value) {
    alert('请先选择一个会话')
    return
  }
  const params = new URLSearchParams()
  params.append('fromId', currentId.value)
  params.append('fromType', currentType.value)
  params.append('toId', currentOther.value.id)
  params.append('toType', currentOther.value.type)
  params.append('content', newMessage.value.trim())
  try {
    const res = await fetch('http://localhost:8088/message/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
    const data = await res.json()
    if (data.success) {
      const tempMsg = {
        id: Date.now(),
        from_id: currentId.value,
        from_type: currentType.value,
        to_id: currentOther.value.id,
        to_type: currentOther.value.type,
        content: newMessage.value.trim(),
        create_time: new Date().toISOString().replace('T', ' ').substring(0, 19)
      }
      messages.value.push(tempMsg)
      newMessage.value = ''
      await nextTick()
      scrollToBottom()
      await loadConversations()
    } else {
      alert('发送失败：' + data.msg)
    }
  } catch (err) {
    console.error('发送消息失败', err)
    alert('网络错误，请稍后重试')
  }
}

watch(
  () => [route.query.otherId, route.query.otherType],
  async ([newOtherId, newOtherType]) => {
    if (!currentId.value) return
    if (newOtherId && newOtherType) {
      await openConversation(newOtherId, newOtherType)
      // eslint-disable-next-line no-unused-vars
      const { otherId, otherType, _t, ...rest } = route.query
      router.replace({ query: { ...rest, page: 'chat' } })
    }
  },
  { immediate: true }
)

watch(messages, () => {
  nextTick(() => scrollToBottom())
})

onMounted(async () => {
  initCurrentUser()
  await loadConversations()
  const otherId = route.query.otherId
  const otherType = route.query.otherType
  if (otherId && otherType && currentId.value) {
    await openConversation(otherId, otherType)
    // eslint-disable-next-line no-unused-vars
    const { otherId: oid, otherType: otype, _t, ...rest } = route.query
    router.replace({ query: { ...rest, page: 'chat' } })
  }
  pollingTimer = setInterval(() => pollUpdates(), 2000)
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  if (pollingTimer) clearInterval(pollingTimer)
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
.chat-room {
  display: flex;
  flex-direction: column;
  height: 80vh;
  background: #f5f5f5;
}
.chat-header {
  height: 80px;
  background: #fff;
  border-bottom: 1px solid #e0e0e0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
}
.current-conversation {
  font-size: 18px;
  font-weight: 500;
}
.conversation-selector {
  position: relative;
  width: 220px;
}
.custom-select {
  position: relative;
  cursor: pointer;
  user-select: none;
}
.selected-display {
  padding: 8px 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #fff;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.arrow {
  font-size: 12px;
  color: #666;
}
.dropdown-list {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #ccc;
  border-top: none;
  border-radius: 0 0 6px 6px;
  max-height: 200px;
  overflow-y: auto;
  z-index: 100;
}
.dropdown-item {
  padding: 8px 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
}
.dropdown-item:hover {
  background: #f0f0f0;
}
.dropdown-item.active {
  background: #e6f7ff;
}
.conv-label {
  flex: 1;
}
.dropdown-empty {
  padding: 8px 12px;
  color: #999;
  text-align: center;
}
.red-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  background-color: red;
  border-radius: 50%;
  margin-left: 8px;
  flex-shrink: 0;
}
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
}
.message-item {
  display: flex;
  margin-bottom: 15px;
}
.message-item.self {
  justify-content: flex-end;
}
.message-item.other {
  justify-content: flex-start;
}
.message-bubble {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,0.1);
}
.message-item.self .message-bubble {
  background: #dcf8c5;
}
.message-item.other .message-bubble {
  background: #fff;
}
.message-content {
  word-wrap: break-word;
  font-size: 14px;
}
.message-time {
  font-size: 10px;
  color: #999;
  text-align: right;
  margin-top: 4px;
}
/* 商品卡片样式 */
.product-card {
  display: flex;
  background: #fff3e0;
  border-radius: 12px;
  padding: 8px;
  cursor: pointer;
  border: 1px solid #ffcc80;
  transition: 0.2s;
  max-width: 280px;
}
.product-card:hover {
  background: #ffe0b2;
  transform: translateY(-2px);
}
.product-card-left {
  width: 40%;
  flex-shrink: 0;
}
.product-card-left img {
  width: 100%;
  height: auto;
  border-radius: 8px;
  object-fit: cover;
}
.product-card-right {
  width: 60%;
  padding-left: 10px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.product-name {
  font-size: 15px;
  font-weight: bold;
  margin-bottom: 4px;
}
.product-detail {
  font-size: 12px;
  color: #666;
  margin-bottom: 2px;
}
.product-link {
  font-size: 12px;
  color: #ff9800;
  margin-top: 6px;
  text-align: right;
}
.message-input-area {
  height: 60px;
  background: #fff;
  border-top: 1px solid #e0e0e0;
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 10px;
}
.message-input-area input {
  flex: 1;
  padding: 10px 15px;
  border: 1px solid #ddd;
  border-radius: 24px;
  outline: none;
}
.message-input-area button {
  padding: 8px 20px;
  background: #007bff;
  color: white;
  border: none;
  border-radius: 24px;
  cursor: pointer;
}
.message-input-area button:hover {
  background: #0056b3;
}
</style>