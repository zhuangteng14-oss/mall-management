<template>
  <div class="msg-center">
    <!--
      消息中心（2026-10-19 用户令，对齐 a.html 图四）：
      左栏「好友会话」、右栏「最近通知」，两侧同时在场；点会话开聊天抽屉。
      ⚠️ 两套数据链一个字都没换：左栏仍是 `message.*`，右栏仍是 `notification.*`。
      ⚠️ 无侧栏：路由记录已带 `noRail`（`/customer/msg`、`/merchant/msg`）。
    -->
    <section class="page-head">
      <div>
        <div class="kicker">BUYER / MESSAGE CENTER</div>
        <h1>消息中心</h1>
        <p>左侧继续好友会话，右侧查看最近通知。点开一个会话，聊天抽屉里支持商品卡、表情与图片。</p>
      </div>
      <div class="head-actions">
        <router-link class="btn" to="/customer/search">去逛逛</router-link>
      </div>
    </section>

    <div class="mc-grid">
      <!-- ── 左：好友会话 ─────────────────────────────────────────────── -->
      <section class="col conv-col" aria-label="好友会话">
        <header class="col-h">
          <h2>好友会话</h2>
          <span class="n">{{ conversations.length }}</span>
          <button class="refresh" type="button" title="刷新会话" @click="loadConversations()">刷新</button>
        </header>

        <p v-if="!loggedIn" class="col-hint">登录后显示会话。</p>
        <p v-else-if="convLoading && conversations.length === 0" class="col-hint">加载中…</p>
        <p v-else-if="conversations.length === 0" class="col-hint">还没有会话。去商品详情页点「咨询」或到好友页发起聊天。</p>

        <ul v-else class="conv-list">
          <li v-for="conv in conversations" :key="conv.other_id">
            <button class="conv" type="button" @click="openChat(conv)">
              <span class="av" :class="conv.other_type">{{ initialOf(conv._name) }}</span>
              <span class="tx">
                <span class="l1">
                  <span class="nm">{{ conv._name }}</span>
                  <span class="tm">{{ conv._time }}</span>
                </span>
                <span class="l2">
                  <span class="summary">{{ conv._summary || (conv.other_type === 'merchant' ? '商家会话' : '用户会话') }}</span>
                  <span v-if="conv.unread" class="unread" title="未读">未读</span>
                </span>
              </span>
            </button>
          </li>
        </ul>
      </section>

      <!-- ── 右：最近通知（完整复用 NotifyInbox 的读/已读/邀请应答） ────────── -->
      <section class="col notify-col" aria-label="最近通知">
        <NotifyInbox compact />
      </section>
    </div>

    <!-- ── 聊天抽屉：复用 ChatRoom（drawer 模式隐藏它自己的会话栏）────────── -->
    <div v-if="chatOpen" class="drawer-scrim" @click.self="closeChat">
      <aside class="chat-drawer" role="dialog" aria-modal="true" aria-label="聊天">
        <header class="drawer-h">
          <span class="av" :class="chatWith && chatWith.type">{{ initialOf(chatTitle) }}</span>
          <div class="drawer-tx">
            <strong>{{ chatTitle }}</strong>
            <small>{{ chatWith && chatWith.id }}</small>
          </div>
          <button class="x" type="button" aria-label="关闭聊天" @click="closeChat">×</button>
        </header>
        <div class="drawer-b">
          <ChatRoom drawer />
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId, isLoggedIn } from '../api/auth'
import ChatRoom from './ChatRoom.vue'
import NotifyInbox from './NotifyInbox.vue'

const route = useRoute()
const router = useRouter()

const currentId = ref('')
const currentType = ref('')
const conversations = ref([])
const convLoading = ref(false)
const loggedIn = ref(false)

const chatOpen = ref(false)
const chatWith = ref(null)

let pollTimer = null
const profileCache = new Map()
const pendingProfiles = new Set()

/** 与 ChatRoom 同一条身份降级链：按壳取自己的号，取不到才退回另一身份。 */
function initIdentity() {
  const path = route.path
  const usable = (v) => v && v !== 'null' && v !== 'undefined'
  const mid = getMerchantId()
  const cid = getCustomerId()

  if (path.startsWith('/merchant') && usable(mid)) {
    currentId.value = mid
    currentType.value = 'merchant'
  } else if (path.startsWith('/customer') && usable(cid)) {
    currentId.value = cid
    currentType.value = 'customer'
  } else if (usable(mid)) {
    currentId.value = mid
    currentType.value = 'merchant'
  } else if (usable(cid)) {
    currentId.value = cid
    currentType.value = 'customer'
  }
  loggedIn.value = isLoggedIn() && !!currentId.value
}

const initialOf = (v) => (v ? String(v).trim().slice(0, 1).toUpperCase() : '?')

/** 会话时间：库时钟是 UTC，补 `Z` 再按浏览器本地时区显示（与 ChatRoom 同一口径）。 */
function fmtWhen(v) {
  if (!v) return ''
  const d = v instanceof Date ? v : new Date(`${String(v).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(v)
  const p = (n) => String(n).padStart(2, '0')
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`
  const now = new Date()
  return d.toDateString() === now.toDateString() ? hm : `${p(d.getMonth() + 1)}-${p(d.getDate())} ${hm}`
}

/** 摘要：商品/图片消息不显示 base64 或 JSON，只显示类型标记。 */
function summarize(content) {
  if (!content) return ''
  if (content.startsWith('[PROD]')) return '[商品卡片]'
  if (content.startsWith('[IMG]')) return '[图片]'
  const s = String(content).replace(/\s+/g, ' ').trim()
  return s.length > 36 ? `${s.slice(0, 36)}…` : s
}

async function fetchName(id) {
  try {
    const res = await http.post(endpoints.message.userSelect, new URLSearchParams({ userId: id }).toString())
    const data = await res.json()
    if (data.success && data.user && data.user.username) return data.user.username
  } catch (e) { /* 名字取不到就退回 id，不阻断列表 */ }
  return id
}

/**
 * 头像旁的名字：只对新会话查一次，之后走 `profileCache`。
 * ⚠️ **摘要不走 `/message/history`**：那个读接口会写已读时间（既有副作用），
 *    逐个会话调用等于把整个列表一次标成已读。摘要直接取 `conversations` 新增的
 *    `last_content` 列（窗口函数只读）。
 */
function enrich(id) {
  if (pendingProfiles.has(id) || profileCache.has(id)) return
  pendingProfiles.add(id)
  fetchName(id)
    .then((name) => {
      profileCache.set(id, { name: name || id })
      const row = conversations.value.find((c) => c.other_id === id)
      if (row) row._name = name || id
    })
    .finally(() => pendingProfiles.delete(id))
}

async function loadConversations() {
  if (!currentId.value) {
    loggedIn.value = false
    return
  }
  loggedIn.value = true
  convLoading.value = true
  const params = new URLSearchParams()
  params.append('currentId', currentId.value)
  params.append('currentType', currentType.value)
  try {
    const res = await http.post(endpoints.message.conversations, params.toString())
    const data = await res.json()
    if (!data.success) return
    const list = Array.isArray(data.list) ? data.list : []
    conversations.value = list.map((c) => {
      const hit = profileCache.get(c.other_id)
      if (!hit) enrich(c.other_id)
      return {
        ...c,
        _name: hit ? hit.name : c.other_id,
        _summary: summarize(c.last_content),
        _time: fmtWhen(c.last_time)
      }
    })
  } catch (e) {
    // 列表失败不弹窗：右栏通知仍可用，左侧保留上一次结果。
    console.error('加载会话列表失败', e)
  } finally {
    convLoading.value = false
  }
}

const chatTitle = computed(() => (chatWith.value && chatWith.value.name) || '会话')

/**
 * 打开聊天抽屉：先把会话参数写进 query（`ChatRoom` 读的正是 `otherId` / `otherType`），
 * 再挂载抽屉 —— 顺序反了会让 ChatRoom 读到空参数、只开着空聊天框。
 */
async function openChat(conv) {
  if (!currentId.value || conv.other_id === currentId.value) return
  chatWith.value = { id: conv.other_id, type: conv.other_type, name: conv._name }
  await router.replace({
    query: { ...route.query, otherId: conv.other_id, otherType: conv.other_type }
  })
  chatOpen.value = true
}

function closeChat() {
  chatOpen.value = false
  const closed = chatWith.value
  chatWith.value = null
  if (closed) {
    // 抽屉里可能发过消息 ⇒ 重新拉一次会话（摘要随 `last_content` 一起刷新）。
    loadConversations()
  }
  // eslint-disable-next-line no-unused-vars
  const { otherId, otherType, _t, ...rest } = route.query
  router.replace({ query: rest })
}

onMounted(async () => {
  initIdentity()
  await loadConversations()
  // 深链 / 旧入口（商品咨询、好友「聊天」）带着 otherId 进来 ⇒ 直接开抽屉。
  const oid = route.query.otherId
  const otype = route.query.otherType
  if (oid && otype && currentId.value) {
    chatWith.value = { id: oid, type: otype, name: oid }
    chatOpen.value = true
  }
  pollTimer = setInterval(() => loadConversations(), 8000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.msg-center { font-family: var(--font); }
.page-head {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s7) var(--s5) 0;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s5);
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
  font-size: var(--fs-2xl);
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -.02em;
  color: var(--ink);
}
.page-head p {
  margin-top: 8px;
  max-width: 58ch;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-2);
}
.head-actions .btn {
  display: inline-flex;
  align-items: center;
  height: 36px;
  padding: 0 var(--s5);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  text-decoration: none;
  background: var(--surface);
}
.head-actions .btn:hover { border-color: var(--brand-ink); color: var(--brand-ink); }

/* 双栏：左会话（固定宽）+ 右通知（自适应）。 */
.mc-grid {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s6) var(--s5) var(--s8);
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: var(--s5);
  align-items: start;
}
.col {
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  overflow: hidden;
}
.col-h {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line);
  background: var(--surface-2);
}
.col-h h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: 600;
  color: var(--ink);
}
.col-h .n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.refresh {
  margin-left: auto;
  border: 0;
  background: none;
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  cursor: pointer;
}
.refresh:hover { color: var(--brand-ink); }
.col-hint {
  margin: 0;
  padding: var(--s5) var(--s4);
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-3);
}

.conv-list { list-style: none; margin: 0; padding: 0; }
.conv-list li + li { border-top: 1px solid var(--line); }
.conv {
  display: flex;
  align-items: center;
  gap: var(--s3);
  width: 100%;
  padding: var(--s3) var(--s4);
  border: 0;
  background: transparent;
  text-align: left;
  font: inherit;
  cursor: pointer;
}
.conv:hover { background: var(--surface-2); }
.av {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--brand-ink);
  background: var(--brand-tint);
  border: 1px solid var(--line-2);
}
.av.merchant { color: var(--accent); background: #fbf1ed; }
.tx { flex: 1; min-width: 0; }
.l1, .l2 { display: flex; align-items: center; gap: var(--s2); min-width: 0; }
.nm {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.tm { flex: 0 0 auto; font-family: var(--font-mono); font-size: 10px; color: var(--ink-3); }
.l2 { margin-top: 3px; }
.summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.unread {
  flex: 0 0 auto;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
}

.notify-col { min-width: 0; }

/* ── 聊天抽屉 ─────────────────────────────────────────────────────────── */
.drawer-scrim {
  position: fixed;
  inset: 0;
  z-index: 70;
  background: rgba(0, 0, 0, .38);
  display: flex;
  justify-content: flex-end;
}
.chat-drawer {
  width: min(760px, 96vw);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  box-shadow: -12px 0 48px rgba(0, 0, 0, .22);
}
.drawer-h {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line);
  background: var(--surface-2);
}
.drawer-tx { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.drawer-tx strong { font-size: var(--fs-sm); color: var(--ink); }
.drawer-tx small { font-family: var(--font-mono); font-size: 10px; color: var(--ink-3); }
.x {
  border: 0;
  background: transparent;
  font-size: var(--fs-lg);
  line-height: 1;
  color: var(--ink-3);
  cursor: pointer;
}
.drawer-b { flex: 1; min-height: 0; }

@media (max-width: 900px) {
  .mc-grid { grid-template-columns: minmax(0, 1fr); }
  .page-head { flex-direction: column; align-items: flex-start; }
}
</style>
