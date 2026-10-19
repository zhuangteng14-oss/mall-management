<template>
  <div class="chat-room">
    <!-- ⚠️ D 笔（2026-10-07）：本块按 `docs/美化/pages/ChatRoom.html` 重写。稿的 5 个
         「面」（客户·消息 / 商家·消息 / 客户·用户中心·消息 / 商家·用户中心·消息 / 空态）
         在 Vue 里**只是两条路由 + 有无会话**，共用本组件 ⇒ 只落 `panel()` 那层
         （`.chat`），稿的 `.nav-bar` / `.wrap` / `.split` / `.uc-menu` 分别归
         `AppShell.vue` 与 `UserCenter.vue`，**不进本文件**。
         ⚠️ 稿把原来的 **220px 下拉**换成**常驻左栏**（`.rail`，数据同一份
         `message.conversations`）⇒ 下拉那套脚本随之变死，见 `<script>` 里的按语。 -->
    <div class="chat">
      <div class="rail">
        <div class="rail-h">会话 · {{ conversations.length }}</div>
        <div class="rail-list">
          <button
            v-for="conv in conversations"
            :key="conv.other_id"
            class="conv"
            type="button"
            :aria-current="selectedConversation === conv.other_id ? 'true' : null"
            title="切换会话"
            @click="selectConversation(conv)"
          >
            <span class="cav" :class="conv.other_type">{{ initialOf(conv.other_id) }}</span>
            <span class="cinfo">
              <span class="id">{{ conv.other_id }}</span>
              <span class="ty">{{ conv.other_type === 'customer' ? '用户' : '商家' }}</span>
            </span>
            <span v-if="conv.unread" class="udot" title="未读"></span>
          </button>
          <div v-if="conversations.length === 0" class="rail-empty">暂无会话</div>
        </div>
      </div>

      <div class="main">
        <div class="chead">
          <template v-if="currentOther">
            <span class="cav lg" :class="currentOther.type">{{ initialOf(currentOther.username) }}</span>
            <div class="chead-tx">
              <h2>{{ currentOther.username }}</h2>
              <span class="meta">{{ currentOther.id }}</span>
            </div>
          </template>
          <span v-else class="none">请选择一个会话</span>
        </div>

        <div ref="messageListRef" class="msgs">
          <template v-if="messages.length > 0">
            <div class="day">{{ dayLabel }}</div>
            <div
              v-for="msg in messages"
              :key="msg.id"
              class="mi"
              :class="isSelfMessage(msg) ? 'self' : 'other'"
            >
              <span class="mav" :class="isSelfMessage(msg) ? 'self' : 'other'">{{ initialOf(isSelfMessage(msg) ? currentId : currentOther?.id) }}</span>
              <div class="bwrap">
                <div class="bub" :class="{ bare: isProductMessage(msg) || isImageMessage(msg) }">
                  <template v-if="isProductMessage(msg)">
                    <div class="pc" title="现有功能 goToProduct" @click="goToProduct(parseProductInfo(msg.content))">
                      <div class="th">
                        <img :src="parseProductInfo(msg.content).goodImg" alt="商品图片" />
                      </div>
                      <div>
                        <div class="nm">{{ parseProductInfo(msg.content).goodName }}</div>
                        <div class="dt">型号：{{ parseProductInfo(msg.content).productCode }}</div>
                        <div class="pr">
                          <span class="u">¥</span>
                          <span class="i">{{ getPriceInt(parseProductInfo(msg.content).price) }}</span>
                          <span class="d">.{{ getPriceDec(parseProductInfo(msg.content).price) }}</span>
                        </div>
                        <span class="lk">查看商品详情</span>
                      </div>
                    </div>
                  </template>
                  <template v-else-if="isImageMessage(msg)">
                    <img class="msg-img" :src="imageSrc(msg)" alt="图片消息" @load="onImageLoaded" />
                  </template>
                  <template v-else>{{ msg.content }}</template>
                </div>
                <div class="tm">{{ formatTimeHM(msg.create_time) }}</div>
              </div>
            </div>
          </template>
        </div>

        <div v-if="currentOther" class="inp-area">
          <div class="emoji-wrap">
            <button class="icon" type="button" :aria-expanded="emojiOpen ? 'true' : 'false'" title="表情 —— 纯前端：插入字符进输入框，走现有 message.send，不需要新端点" @click="emojiOpen = !emojiOpen">表情</button>
            <div v-if="emojiOpen" class="emoji-pop">
              <button v-for="e in EMOJI" :key="e" class="emoji-btn" type="button" :aria-label="`插入 ${e}`" @click="pickEmoji(e)">{{ e }}</button>
            </div>
          </div>
          <button
            class="icon"
            type="button"
            title="发图片 —— 纯前端压缩成 data URL，走现有 message.send（不改后端）"
            @click="pickImage"
          >图片</button>
          <input ref="fileInputRef" class="file-hidden" type="file" accept="image/*" @change="onPickImage" />
          <input
            v-model="newMessage"
            type="text"
            placeholder="输入消息..."
            title="回车发送"
            @keyup.enter="sendMessage"
          />
          <button class="send" type="button" title="现有端点 message.send" @click="sendMessage">发送</button>
        </div>

        <div v-else class="inp-area">
          <button class="icon" type="button" disabled title="未选择会话">表情</button>
          <button class="icon" type="button" disabled title="未选择会话">图片</button>
          <input
            type="text"
            placeholder="请先选择一个会话"
            disabled
            title="没选会话就发不出去（原为 alert），改成看得见的状态"
          />
          <button class="send" type="button" disabled title="未选择会话">发送</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId } from '../api/auth'

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

// ── 图片消息（2026-10-19 极速 UI 重构，命令二「支持图片发送」）──────────────
// ⚠️ **不改后端**：`message.content` 是 `TEXT`（64KB），图片在浏览器端缩到
//    长边 720px 的 JPEG 后以 `[IMG]data:image/jpeg;base64,…` 形式走现有
//    `message.send`。两条判据：① 压缩后字符数 ≤ 60000（给 UTF-8/表单编码留余量）；
//    ② 仍超限就明确报错，不静默截断。
const IMG_PREFIX = '[IMG]'
const IMG_MAX_CHARS = 60000
const isImageMessage = (msg) => !!(msg.content && msg.content.startsWith(IMG_PREFIX))
const imageSrc = (msg) => msg.content.slice(IMG_PREFIX.length)
const fileInputRef = ref(null)
const onImageLoaded = () => { nextTick(() => scrollToBottom()) }

const pickImage = () => {
  if (!currentOther.value) { alert('请先选择一个会话'); return }
  if (fileInputRef.value) fileInputRef.value.click()
}

/** 读文件 → 画布缩放 → JPEG 迭代降质，直到 ≤ IMG_MAX_CHARS。 */
const compressImage = (file) => new Promise((resolve, reject) => {
  const reader = new FileReader()
  reader.onerror = () => reject(new Error('读取图片失败'))
  reader.onload = () => {
    const img = new Image()
    img.onerror = () => reject(new Error('图片格式不支持'))
    img.onload = () => {
      const maxSide = 720
      const scale = Math.min(1, maxSide / Math.max(img.width, img.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(img.width * scale))
      canvas.height = Math.max(1, Math.round(img.height * scale))
      const ctx = canvas.getContext('2d')
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
      let quality = 0.72
      let dataUrl = canvas.toDataURL('image/jpeg', quality)
      while (dataUrl.length > IMG_MAX_CHARS && quality > 0.28) {
        quality -= 0.12
        dataUrl = canvas.toDataURL('image/jpeg', quality)
      }
      resolve(dataUrl)
    }
    img.src = reader.result
  }
  reader.readAsDataURL(file)
})

const onPickImage = async (e) => {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''   // 允许连续选同一张图
  if (!file) return
  if (!file.type.startsWith('image/')) { alert('请选择图片文件'); return }
  try {
    const dataUrl = await compressImage(file)
    if (dataUrl.length > IMG_MAX_CHARS) { alert('图片过大，压缩后仍超出消息上限，请换一张更小的图片'); return }
    await sendContent(IMG_PREFIX + dataUrl)
  } catch (err) {
    console.error('发送图片失败', err)
    alert('图片处理失败，请换一张再试')
  }
}

// ── D 笔（2026-10-07）：价格拆两档，承接稿 `.pc .pr` 的三段式（`¥` 12 / 整数 19 / 小数 12）──
// 改动理由：稿的商品卡把原先生成的一行 `价格：¥{{ price }}` 换成三段排版（稿 `:374` 按语
// 「三段同号时卡里没有对比」）⇒ 需要整数/小数两个取值点。
// ⚠️ 这两个函数**不是为 D 笔新造的抽象**：`GoodsSelect.vue:249-258` 与 `HomePage.vue:203-213`
//    里逐字同款，早就在仓里（同一套写法、同一套空值兜底）。
const getPriceInt = (price) => {
  if (!price) return '0'
  return parseInt(price) || '0'
}
const getPriceDec = (price) => {
  if (!price) return '00'
  const s = String(price)
  const arr = s.split('.')
  return arr[1] ? arr[1].padEnd(2, '0').slice(0, 2) : '00'
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
    const mid = getMerchantId()
    if (mid && mid !== 'null' && mid !== 'undefined') {
      currentId.value = mid
      currentType.value = 'merchant'
      return
    }
  } else if (path.includes('/customer')) {
    const cid = getCustomerId()
    if (cid && cid !== 'null' && cid !== 'undefined') {
      currentId.value = cid
      currentType.value = 'customer'
      return
    }
  }
  let id = getMerchantId()
  if (id && id !== 'null' && id !== 'undefined') {
    currentId.value = id
    currentType.value = 'merchant'
  } else {
    id = getCustomerId()
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
    const res = await http.post(endpoints.message.userSelect, new URLSearchParams({ userId }).toString())
    const data = await res.json()
    if (data.success) return data.user
  } catch (err) {
    handleApiError(err, '获取用户信息失败')
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
    const res = await http.post(endpoints.message.conversations, params.toString())
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
    handleApiError(err, '加载会话列表失败')
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
    const res = await http.post(endpoints.message.history, params.toString())
    const data = await res.json()
    if (data.success) {
      messages.value = data.list
      await nextTick()
      scrollToBottom()
    }
  } catch (err) {
    handleApiError(err, '加载消息失败')
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

// ── D 笔（2026-10-07）按语：**下拉那套已随稿退役** ───────────────────────────────
// 稿把「220px 自定义下拉」换成**常驻左栏**（`.rail`）⇒ 原 `dropdownOpen` /
// `selectedConversationName` / `toggleDropdown` / `handleClickOutside` 四件**全部失去引用面**。
// ⚠️ 它们**必须删**、不是「顺手清」：`eslint:recommended` 带 `no-unused-vars`（`package.json`
//    的 `eslintConfig`）⇒ 留着 `npm run lint` 直接红；而 `handleClickOutside` 与
//    `selectConversation` 又都写 `dropdownOpen.value = …` ⇒ 只删声明会变成**引用未定义**、`build` 红。
// ⚠️ 会话列表**选中态**改由 `:aria-current="selectedConversation === conv.other_id"` 承担
//    （稿 `.conv[aria-current="true"]`），`selectedConversation` 这个 ref 本身**照旧**。

// 选择会话（左栏点击）
const selectConversation = async (conv) => {
  if (selectedConversation.value === conv.other_id) return
  selectedConversation.value = conv.other_id
  await openConversation(conv.other_id, conv.other_type)
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
      const res = await http.post(endpoints.message.history, params.toString())
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
      handleApiError(err, '轮询刷新消息失败')
    }
  }
}

const isSelfMessage = (msg) => msg.from_id === currentId.value && msg.from_type === currentType.value

const formatDateTime = (timeStr) => {   // ⚠️ **2026-10-10（问题 2，即时区：用户报「轨迹 22:27:51 vs 订单列表 14:29:16」差 8 小时）**：`message.create_time` 由 `MessageDao` 的 SQL `NOW()` 填 ⇒ 它是**库时钟**、而库时钟是 **UTC**（VM / a3 实测 `@@system_time_zone=UTC`，见 `context-packs/c3-block-1-infra.md#pack-c3-block-1-infra-frontend-nav`）⇒ 交回 `'YYYY-MM-DD HH:mm:ss'` **无时区文本**；少补末尾那个 `Z`，`new Date()` 按**浏览器本地时区**解释它 ⇒ 整整差一个偏移且**错得很安静**（本页 `:402` 的乐观回显走 `toISOString()`、本来就是 UTC 文本 —— 改前两边**一致地错**，改后**一致地对**）。手法照 `views/admin/AuditPage.vue:236` 的 `toLocal`（原 190 行 —— 2026-10-11 收口按 refs-scan 重指）。⚠️ 下面那行**必须**留 `Date` 实例这条分支：`dayLabel` 拿 `formatDateTime(new Date())` 比「今天」，而 `String(new Date())` 是 `'Fri Oct 10 2026 …'` 那种形态，套上 `Z` 会解析失败 ⇒ 整个「今天」标签会消失。
  if (!timeStr) return ''
  const date = timeStr instanceof Date ? timeStr : new Date(`${String(timeStr).replace(' ', 'T')}Z`)
  return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')} ${String(date.getHours()).padStart(2,'0')}:${String(date.getMinutes()).padStart(2,'0')}:${String(date.getSeconds()).padStart(2,'0')}`
}

// ── D 笔（2026-10-07）按语：**时间降噪**（稿 `ChatRoom.html:48` 反模板清单 ⑤ / `:62` 给改法）──
// 稿点名要改的原状正是本页原来的样子：「每个气泡下面都压一行 10px 的 `YYYY-MM-DD HH:MM:SS`」。
// 改法：日期收进 `.day` 分隔线，气泡下只留 `HH:MM`。
// `formatDateTime` 本体**不动**（它是仓里既有的格式化器），只在它上面切两刀取所需半截。
const formatTimeHM = (timeStr) => {
  const full = formatDateTime(timeStr)
  return full ? full.slice(11, 16) : ''
}

// `.day` 的文案：稿的 `msgs()`（`ChatRoom.html:390`）把「今天」**写死成字面量** ——
// 那稿的 mock 里压根没有日期字段（只有 `t: '10:21'`），它没法不写死。本页拿的是真
// `create_time` ⇒ 照稿落这个元素、但标签按**首条消息的真实日期**出：是今天就「今天」，
// 否则照 `YYYY-MM-DD`。写死会让一条三天前的历史硬说「今天」。
// ⚠️ 这里**只做标签、不做分组**：按天分段要改消息列表的结构（每段一个 `.day`），那是
//    **功能**不是版式，不在 D 笔边界内；稿自己也没有那个模型（它只有一个静态常量）。
const dayLabel = computed(() => {
  const first = messages.value[0]
  if (!first) return ''
  const full = formatDateTime(first.create_time)
  if (!full) return ''
  return full.slice(0, 10) === formatDateTime(new Date()).slice(0, 10) ? '今天' : full.slice(0, 10)
})

const scrollToBottom = () => {
  if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
}

// ── D 笔（2026-10-07）：「表情」是本稿**唯一**被画成「可点、且不需要新端点」的按钮 ──────────
// 稿的判据：`title="表情 —— 纯前端：插入字符进输入框，走现有 message.send，不需要新端点"`。
// 若落成 `disabled` 占位就与稿相反；若落成可点却无处理函数，就是**死控件**
// （`progress.md:1652` 验收判据①）⇒ 照稿实现：按下即把下一枚字符追加进输入框。
// 2026-10-19 用户令：表情从「连点轮播」升级为**选择面板**（点开 → 选一枚 → 关面板）。
// 仍是纯前端插入，走现有 `message.send`，不新增端点。
const EMOJI = ['😊', '👍', '🙏', '❤️', '😄', '🎉', '🔥', '👏', '🤝', '💯', '😍', '🛒']
const emojiOpen = ref(false)
const pickEmoji = (e) => {
  newMessage.value += e
  emojiOpen.value = false
}

/** 会话头像的占位字符（没有头像字段，用 id/用户名的首字符，稳定且不额外请求）。 */
const initialOf = (v) => (v ? String(v).trim().slice(0, 1).toUpperCase() : '?')

const sendContent = async (content) => {
  if (!content) return
  if (!currentOther.value) {
    alert('请先选择一个会话')
    return
  }
  const params = new URLSearchParams()
  params.append('fromId', currentId.value)
  params.append('fromType', currentType.value)
  params.append('toId', currentOther.value.id)
  params.append('toType', currentOther.value.type)
  params.append('content', content)
  try {
    const res = await http.post(endpoints.message.send, params.toString())
    const data = await res.json()
    if (data.success) {
      const tempMsg = {
        id: Date.now(),
        from_id: currentId.value,
        from_type: currentType.value,
        to_id: currentOther.value.id,
        to_type: currentOther.value.type,
        content,
        create_time: new Date().toISOString().replace('T', ' ').substring(0, 19)
      }
      messages.value.push(tempMsg)
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

const sendMessage = async () => {
  const text = newMessage.value.trim()
  if (!text) return
  await sendContent(text)
  newMessage.value = ''
}

watch(
  () => [route.query.otherId, route.query.otherType],
  async ([newOtherId, newOtherType]) => {
    if (!currentId.value) return
    if (newOtherId && newOtherType) {
      await openConversation(newOtherId, newOtherType)
      // eslint-disable-next-line no-unused-vars
      const { otherId, otherType, _t, ...rest } = route.query
      router.replace({ query: rest })   // R4：清掉「`page: 'chat'`」残留（R1 前的壳内 `?page=` 约定，全仓已无读者）；`rest` 仍照旧剥掉上面三键
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
    router.replace({ query: rest })   // R4：同上（`onMounted` 支路）
  }
  pollingTimer = setInterval(() => pollUpdates(), 2000)
})

onUnmounted(() => {
  if (pollingTimer) clearInterval(pollingTimer)
})
</script>

<style scoped>
/* ⚠️ D 笔（2026-10-07）：全块按 `docs/美化/pages/ChatRoom.html` 的 `<style>` 重写。
   类名一律取稿名（`chat` / `rail` / `conv` / `chead` / `msgs` / `mi` / `bwrap` / `bub` /
   `pc` / `inp-area` / …），旧名（`chat-header` / `message-list` / `product-card` / …）全部退役。
   ⚠️ 稿的 `.bar` / `.page` / `.nav-bar` / `.wrap` / `.split` / `.uc-menu` 是预览与**壳**的东西，
   不进本文件：稿 `:142-148` 的顶栏已由 C 笔落进 `AppShell.vue`，`.uc-menu` 归 `UserCenter.vue`。
   ⚠️ 稿的 `body.slots` 角标规则**不进 Vue** —— 角标是稿预览开关的产物（`::after{content:attr(data-slot)}`）。
   ⚠️ 同理**不搬 `.slot-stack`**（稿 `:247-248`）：它只干一件事 —— 把 `data-future` 那枚角标从
   上边改挂下边（`top:auto;bottom:-11px`），好让 52px 宽的「图片」按钮不至于两枚角标叠成一团。
   两枚角标既然都不进 Vue，这个类在 Vue 里**没有任何规则可匹配**，落下去就是个悬空类名。
   ⚠️ 又：稿 `:88` 的占位清单写「动现有代码 **2 处**」，而稿 `:76` 的功能清单表与 `:418` 的实码
   都写 **4 处** —— 以 **4** 为准（表 + 实码二对一，且 `:419` 的 `title` 逐字列了那 4 处是哪 4 处）。
   ⚠️ 稿 `:160-162` 的 `height` 写 `calc(100vh - 180px)`，那 180 里有 **40px 是稿自己的预览条**；
   本仓没有预览条 ⇒ 缺口只剩「壳顶栏 60 + 本页上下内距 80」= **140**。`min(680px, …)` 在 820px
   以上的视口都取 680 ⇒ 平时看不出差别，小视口才见真章，故按 140 写（1080 断点同理递改）。 */

/* 基础重置：稿的**全局** `a` / `button` / `input` 规则搬进 scoped（只作用本组件的元素）。 */
button,
input {
  font: inherit;
  color: inherit;
}
button {
  background: none;
  border: 0;
  cursor: pointer;
}

/* 本页根：稿 `.wrap` 的角色由它承接（壳 `.content` / `.page` 已归零内距 —— commit 0 / 乙案）。
   ⚠️ `flex:1` + `min-width:0` **必须在**：原 `/…/user/chat` 挂载点下本组件是壳 `.user-page`
   （`display:flex`，`AppShell.vue:440-444`）的**直接** flex 子项 —— 不 `flex:1` 只能拿内容宽、
   不 `min-width:0` 内部栅格会把父容器撑破。在 `/…/msg` 下父级不是 flex，两行属性零副作用。
   ⚠️ A5（2026-10-07）起 `/…/user/chat` 改 `redirect` → `/…/msg`（`router/index.js`）⇒ 现存挂载点
   只剩 `/…/msg`（父级不是 flex）—— 这两行**暂时**无活干，但**不删**：路由若回退，它们立刻又是必需的。 */
.chat-room {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s6) var(--s5) var(--s7);
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 聊天面板 ─────────────────────────────────────────────────────────────── */
.chat {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  height: min(680px, calc(100vh - 140px));
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  overflow: hidden;
  background: var(--surface);
}

/* 左栏：会话列表（原为 220px 下拉，数据同一份 `message.conversations`）*/
.rail {
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--surface-2);
}
.rail-h {
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.rail-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.conv {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s3);
  width: 100%;
  text-align: left;
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line);
}
/* 头像占位（2026-10-19 重做）：会话/消息/页头共用，无头像字段 ⇒ 用首字符，不新增请求。 */
.cav {
  width: 36px; height: 36px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  flex: none; background: var(--ink); color: var(--bg);
  font-family: var(--font-display); font-size: 15px; line-height: 1;
}
.cav.customer { background: var(--brand-ink); }
.cav.merchant { background: var(--accent); }
.cav.lg { width: 44px; height: 44px; font-size: 18px; }
.cinfo { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
/* ⚠️ 会话 id 是 15px、类型标签是 12px：两截都做 12px 时整列没有主次 —— 一列导航**得能扫**；
   id 用等宽是因为它是个标识符，不是句子。 */
.conv .ty {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.conv .id {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv .udot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  align-self: center;
}
.conv:hover {
  background: var(--surface);
}
.conv[aria-current='true'] {
  background: var(--brand-tint);
}
.conv[aria-current='true'] .id {
  color: var(--brand-ink);
  font-weight: var(--fw-med);
}
.rail-empty {
  padding: var(--s7) var(--s5);
  font-size: var(--fs-sm);
  color: var(--ink-3);
  text-align: center;
}

/* 主列 */
.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.chead {
  padding: var(--s3) var(--s5);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: var(--s3);
}
.chead-tx { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.chead h2 {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  letter-spacing: -0.01em;
}
.chead .meta {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.chead .none {
  font-size: var(--fs-lg);
  color: var(--ink-3);
}

.msgs {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s5);
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}
/* ⚠️ 消息**贴底**（旧 `scrollToBottom` 的静态等价物）：不用 `justify-content:flex-end` ——
   它在 `overflow-y:auto` 下会把溢出内容顶出可视区且滚不上去；用首元素 `margin-top:auto`：
   内容不足时贴底，内容超出时正常从顶滚。 */
.msgs > *:first-child {
  margin-top: auto;
}
.day {
  align-self: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mi {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}
.mi.self {
  justify-content: flex-end;
}
.mav {
  width: 30px; height: 30px; border-radius: 50%; flex: none;
  display: inline-flex; align-items: center; justify-content: center;
  font-family: var(--font-display); font-size: 12px; line-height: 1;
}
.mav.other { background: var(--brand-ink); color: var(--bg); }
.mav.self { background: var(--ink); color: var(--bg); order: 2; }
.bwrap {
  max-width: min(74%, 460px);
  display: flex;
  flex-direction: column;
}
.mi.self .bwrap {
  align-items: flex-end;
}
/* 现代气泡（2026-10-19 重做）：对方浅灰 + 自己墨黑，圆角只在「尾侧」收一点。 */
.bub {
  padding: 11px 14px;
  border-radius: 16px 16px 16px 4px;
  font-size: var(--fs-sm);
  line-height: 1.65;
  word-break: break-word;
}
.mi.other .bub {
  background: var(--surface-2);
  border: 1px solid var(--line);
}
.mi.self .bub {
  background: var(--ink);
  color: var(--bg);
  border-radius: 16px 16px 4px 16px;
}
.bub.bare {
  padding: 0;
  background: none;
}
.tm {
  margin-top: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* 商品消息卡：发丝边 + 白底，朱砂只留给价格 */
.pc {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: var(--s4);
  align-items: center;
  width: 320px;
  padding: var(--s3);
  text-align: left;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  /* ⚠️ 稿的 `.pc` 是 `<div>` + 稿自身的 `.pc:hover`；本仓它是可点元素 ⇒ 补 `cursor`。 */
  cursor: pointer;
}
.pc:hover {
  border-color: var(--brand);
}
/* ⚠️ 稿的 `.th` 是**空图框**（稿里没有图数据）；本仓有 `goodImg` ⇒ 保留 `<img>`，
   与 `GoodsSelect.vue` 的 `.thumb` 同一处理（不为了照稿丢真功能）。 */
.pc .th {
  width: 64px;
  height: 64px;
  border-radius: var(--r1);
  background: var(--img);
  border: 1px solid var(--line);
  overflow: hidden;
}
.pc .th img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.pc .nm {
  font-size: var(--fs-sm);
  line-height: 1.4;
}
.pc .dt {
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.pc .pr {
  margin-top: var(--s2);
  font-family: var(--font-display);
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.pc .pr .u {
  font-size: var(--fs-xs);
}
.pc .pr .i {
  font-size: var(--fs-md);
}
.pc .pr .d {
  font-size: var(--fs-xs);
}
.pc .lk {
  display: block;
  margin-top: var(--s2);
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  font-weight: var(--fw-med);
}

/* 输入区：没选会话时整条是「关」的 */
.inp-area {
  padding: var(--s4) var(--s5);
  border-top: 1px solid var(--line);
  display: flex;
  gap: var(--s3);
  align-items: center;
}
.inp-area input {
  flex: 1;
  min-width: 0;
  height: 40px;
  padding: 0 var(--s4);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
}
.inp-area input:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-tint);
}
.inp-area input[disabled] {
  background: var(--surface-2);
  color: var(--ink-3);
  cursor: not-allowed;
}
/* 表情 / 图片：用**文字小按钮**而不是 ☺ / 🖼 图标字 —— 自托管 Noto 子集不保证有这些字形
   （▾ / ▼ 就缺过，见 README），缺字会渲染成豆腐块；文字按钮零风险且更克制。 */
.icon {
  height: 40px;
  padding: 0 var(--s3);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  flex: 0 0 auto;
}
.icon:hover {
  border-color: var(--ink-3);
  color: var(--ink);
}
.icon[disabled] {
  color: var(--ink-3);
  cursor: not-allowed;
}
/* 表情面板（2026-10-19）：点开在输入区上方浮出，选一枚即插入并收起。 */
.emoji-wrap { position: relative; flex: 0 0 auto; }
.emoji-pop {
  position: absolute; left: 0; bottom: 48px; z-index: 20;
  display: grid; grid-template-columns: repeat(6, 34px); gap: 2px;
  padding: 8px;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.14);
}
.emoji-btn {
  height: 34px; border-radius: var(--r1);
  font-size: 18px; line-height: 1;
}
.emoji-btn:hover { background: var(--surface-2); }
.send {
  height: 40px;
  padding: 0 var(--s6);
  border-radius: var(--r2);
  background: var(--brand);
  color: var(--bg);
  font-weight: var(--fw-med);
  flex: 0 0 auto;
}
.send:hover {
  background: var(--brand-hover);
}
.send[disabled] {
  background: var(--surface-3);
  color: var(--ink-3);
  cursor: not-allowed;
}

@media (max-width: 1080px) {
  /* ⚠️ 1080 下**只把会话栏收窄**（248 → 200），**不**改成顶部横排：横排要占掉
     `rail-h` + 一行 ≈ 150px 高度，而聊天区总高是死的 ⇒ 消息区被压到 330px，
     最后一屏气泡直接被裁掉。竖栏只吃宽度不吃高度。 */
  .chat {
    grid-template-columns: 200px minmax(0, 1fr);
    height: min(660px, calc(100vh - 140px));
  }
  .bwrap {
    max-width: 88%;
  }
  .pc {
    width: 100%;
    max-width: 340px;
  }
}
/* 真正的窄屏（手机）才把会话栏横过来 —— 此时宽度本来就装不下两列 */
/* ── 图片消息（2026-10-19）── 气泡已走 `.bare`（零内距/无底色），图片自带发丝边。 */
.file-hidden { display: none; }
.msg-img {
  display: block;
  max-width: min(320px, 100%);
  max-height: 320px;
  border-radius: var(--r2);
  border: 1px solid var(--line);
  background: var(--img);
}

@media (max-width: 760px) {
  .chat {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }
  .rail {
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .rail-h {
    padding-top: var(--s3);
    padding-bottom: var(--s3);
  }
  .rail-list {
    display: flex;
    overflow-x: auto;
    overflow-y: hidden;
  }
  .conv {
    width: auto;
    flex: 0 0 auto;
    border-bottom: 0;
    border-right: 1px solid var(--line);
  }
}
</style>
