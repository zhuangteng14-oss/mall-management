<!-- src/components/chat/ChatWidget.vue —— 全局悬浮智能客服（2026-10-21）
     · 右下角 56px 呼吸灯球；面板 368px 宽、贴底右，气泡左右分栏。
     · 回复走 `POST /chat/ask`（`api/chat.js`），本组件只负责展示与交互。
     · **白名单页隐藏**：登录 / 注册 / 管理端 / 预览 / 敬请期待（P0-3）。组件本身**始终挂载**
       —— 不能卸载，否则「点了开店 → 被守卫踢去登录 → 登录回来」这段路由观察就断了。
     · 跨页漫游的待办与 DOM 等待在 `composables/useTour.js`；本文件只在动作点击与路由变化时接线。 -->
<template>
  <div v-if="!hidden" class="chat-root">
    <Transition name="panel">
      <section v-if="open" class="panel" role="dialog" aria-label="米可小助手">
        <header class="hd">
          <span class="avatar" aria-hidden="true"><Bot :size="19" /></span>
          <span class="who">
            <b>米可小助手</b>
            <small>订单 · 售后 · 开店</small>
          </span>
          <button type="button" class="icon-btn" aria-label="关闭客服" @click="close">
            <X :size="18" />
          </button>
        </header>

        <div ref="listRef" class="list">
          <ChatBubble
            v-for="m in messages"
            :key="m.id"
            :message="m"
            @action="onAction"
            @suggestion="onSuggestion"
            @retry="onRetry"
            @typed="scrollToEnd"
          />
        </div>

        <form class="composer" @submit.prevent="onSubmit">
          <textarea
            ref="inputRef"
            v-model="input"
            rows="1"
            maxlength="200"
            placeholder="问我订单、售后、开店…"
            :disabled="busy"
            @keydown.enter.exact.prevent="onSubmit"
          />
          <button type="submit" class="send" :disabled="busy || !input.trim()" aria-label="发送">
            <Send :size="17" />
          </button>
        </form>
        <p class="hint">Enter 发送 · Shift + Enter 换行</p>
      </section>
    </Transition>

    <button
      type="button"
      class="ball"
      :class="{ active: open }"
      :aria-expanded="open"
      aria-label="打开智能客服"
      @click="toggle"
    >
      <X v-if="open" :size="24" />
      <Bot v-else :size="26" />
      <span v-if="!open && unread" class="badge" aria-label="有新回复">{{ unread > 9 ? '9+' : unread }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot, Send, X } from '@lucide/vue'
import ChatBubble from './ChatBubble.vue'
import { askChat } from '../../api/chat'
import { UnauthorizedError } from '../../api/http'
import {
  applyPath,
  clearPendingTour,
  isMerchantRole,
  ordersPath,
  publishPath,
  readPendingTour,
  resumePathAfterLogin,
  savePendingTour,
  seckillPath,
  startOpenStoreTour,
} from '../../composables/useTour'

/** 这些路径下不出现客服入口（P0-3）。前缀匹配，`/register` 也覆盖 `/register?…`。 */
const HIDDEN = ['/login', '/register', '/admin', '/preview', '/coming-soon']

const route = useRoute()
const router = useRouter()

const open = ref(false)
const busy = ref(false)
const unread = ref(0)
const input = ref('')
const listRef = ref(null)
const inputRef = ref(null)
const tourRunning = ref(false)
const tourStarting = ref(false)

let seq = 0
const nextId = () => `m${Date.now()}-${++seq}`

const messages = reactive([
  {
    id: 'welcome',
    role: 'bot',
    text: '你好呀～我是米可小助手。可以帮你查订单、查售后进度，也能一键带你去开店。',
    suggestions: ['查我的订单', '怎么开店'],
    instant: true,
  },
])

const hidden = computed(() => {
  const path = normalize(route.path)
  return HIDDEN.some((p) => path === p || path.startsWith(`${p}/`))
})

function normalize(p) {
  return String(p || '').replace(/\/+$/, '') || '/'
}
function isApplyPath(p) {
  return normalize(p).endsWith('/user/apply')
}
function isSeckillPath(p) {
  return normalize(p) === normalize(seckillPath())
}
function isLoginPath(p) {
  const n = normalize(p)
  return n === '/login' || n === '/register'
}

function scrollToEnd() {
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function openChat() {
  open.value = true
  unread.value = 0
  nextTick(() => inputRef.value?.focus())
}
function close() {
  open.value = false
}
function toggle() {
  open.value ? close() : openChat()
}

function pushBot(text, extra = {}) {
  messages.push(reactive({ id: nextId(), role: 'bot', text, ...extra }))
  scrollToEnd()
}

function onSubmit() {
  const text = input.value.trim()
  if (!text || busy.value) return
  input.value = ''
  send(text)
}

/**
 * 发一条消息。
 * @param {string} text 原文
 * @param {object} [existing] 重试时复用的用户消息对象（P1-3：不重新造一条）
 */
async function send(text, existing) {
  const clean = String(text || '').trim()
  if (!clean || busy.value) return

  let userMsg = existing
  if (!userMsg) {
    userMsg = reactive({ id: nextId(), role: 'user', text: clean, retryMessage: clean, failed: false })
    messages.push(userMsg)
  } else {
    userMsg.text = clean
    userMsg.retryMessage = clean
    userMsg.failed = false
  }

  busy.value = true
  const bot = reactive({ id: nextId(), role: 'bot', text: '', pending: true })
  messages.push(bot)
  scrollToEnd()

  let ok = false
  try {
    const res = await askChat(clean)
    const body = await res.json().catch(() => null)
    bot.pending = false
    if (!body) {
      bot.text = '服务返回了看不懂的内容，请稍后再试。'
    } else if (body.success === false) {
      bot.text = body.msg || '这句话没能处理，换一种说法试试？'
    } else {
      ok = true
      bot.text = body.reply || '（没有回复内容）'
      bot.data = body.data || null
      bot.action = body.action || null
      bot.suggestions = Array.isArray(body.suggestions) ? body.suggestions : []
    }
  } catch (e) {
    // 占位的「正在输入」气泡撤掉，错误用一条独立回复说明。
    const i = messages.indexOf(bot)
    if (i >= 0) messages.splice(i, 1)
    userMsg.failed = true
    userMsg.retryMessage = clean
    if (e instanceof UnauthorizedError) {
      pushBot('登录后才能查询你的订单和售后。先登录，再回来问我吧～', { suggestions: ['怎么开店'] })
    } else if (e && e.name === 'TimeoutError') {
      pushBot('网络有点慢，刚才没查到。稍后再试一次吧。')
    } else {
      pushBot('客服暂时不可用，请稍后再试。')
    }
  } finally {
    busy.value = false
    if (ok && !open.value) unread.value += 1
    scrollToEnd()
  }
}

function onRetry(msg) {
  send(msg.retryMessage || msg.text, msg)
}

function onSuggestion(s) {
  if (s && typeof s === 'object') {
    if (s.routeKey === 'apply') {
      onAction({ type: 'tour', tourId: 'open-store', routeKey: 'apply', label: s.label })
      return
    }
    if (s.routeKey === 'orders') {
      close()
      router.push(ordersPath()).catch(() => {})
      return
    }
    if (s.routeKey === 'seckill') {
      startSeckill()
      return
    }
    if (s.routeKey === 'publish') {
      // 阶段一：need_publish=true 时先引导去「上架商品」，不替用户猜商品/价格。
      close()
      router.push(publishPath()).catch(() => {})
      return
    }
    if (s.routeKey === 'applyStatus') {
      // 「查入驻进度」= 只去申请页看状态，不自动开漫游（pending 状态没有可填的表）。
      close()
      router.push(applyPath()).catch(() => {})
      return
    }
    // 未知 routeKey（R6 / N7）：回退到 label 当普通消息发送。
    // ⚠️ 绝不能落到 send(String(s)) —— 那会把对象串成 "[object Object]" 发给后端。
    if (s.label) {
      send(s.label)
      return
    }
    return
  }
  send(String(s))
}

async function onAction(action) {
  savePendingTour(action.tourId || 'open-store', action.routeKey || 'apply')
  close()
  const target = applyPath()
  if (normalize(route.path) === normalize(target)) {
    await startTour()
    return
  }
  router.push(target).catch(() => {})
}

/**
 * 秒杀报名（N3）：只导航，不猜商品/价格、不写秒杀表。
 * 未登录时先存待办再 push；路由守卫会送 /login，登录回来由 watcher 按真实角色续接。
 */
function startSeckill() {
  close()
  if (isSeckillPath(route.path)) {
    clearPendingTour()
    return
  }
  savePendingTour('add-to-seckill', 'seckill', 'ADD_TO_SECKILL')
  router.push(seckillPath()).catch(() => {})
}

async function startTour() {
  if (tourRunning.value || tourStarting.value) return
  const pending = readPendingTour()
  // 只有「开店」待办才开漫游；秒杀待办绝不能误触发申请页高亮。
  if (!pending || pending.routeKey !== 'apply') return
  tourStarting.value = true
  const tour = await startOpenStoreTour({
    onDone: () => {
      tourRunning.value = false
    },
  })
  tourStarting.value = false
  tourRunning.value = !!tour
  if (!tour) {
    pushBot('没能定位到申请表单（页面可能还没加载完）。刷新页面后再点一次「一键带我去开店」就好。')
  }
}

watch(
  () => route.path,
  async (to, from) => {
    if (hidden.value) close()

    // 登录回跳：待办还在 ⇒ 按**真实角色**续接。
    if (isLoginPath(from) && !isLoginPath(to)) {
      const pending = readPendingTour()
      // 秒杀待办 + 登录后仍是客户：不硬闯商家壳（壳归属守卫会踢回首页），
      // 直接重问一句，让后端按登录后的真实身份重新走六态分支。
      if (pending && pending.routeKey === 'seckill' && !isMerchantRole()) {
        clearPendingTour()
        openChat()
        send('我要报名秒杀')
        return
      }
      const target = resumePathAfterLogin(normalize(to))
      if (target) {
        router.push(target).catch(() => {})
        return
      }
      if (pending && pending.routeKey === 'seckill') clearPendingTour()
    }

    if (isApplyPath(to)) {
      const pending = readPendingTour()
      if (pending && pending.routeKey === 'apply') await startTour()
    }
    if (isSeckillPath(to)) {
      // 商家已到达秒杀页 ⇒ 待办使命结束，别让它影响下一次导航。
      const pending = readPendingTour()
      if (pending && pending.routeKey === 'seckill') clearPendingTour()
    }
  },
)

function onKeydown(e) {
  if (e.key === 'Escape' && open.value) close()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  // 直接刷新在申请页 / 从别处回到申请页：待办也可能已经在了。
  const pending = readPendingTour()
  if (isApplyPath(route.path) && pending && pending.routeKey === 'apply') startTour()
  if (isSeckillPath(route.path) && pending && pending.routeKey === 'seckill') clearPendingTour()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  // 组件理论上不会卸载（挂在 App 根上）；真被卸载时别把游客的待办带进下一次会话。
  if (tourRunning.value) clearPendingTour()
})
</script>

<style scoped>
.chat-root {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 9000;
  font-family: inherit;
}

/* ── 悬浮球 ── */
.ball {
  position: relative;
  width: 56px;
  height: 56px;
  border: 0;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(135deg, var(--brand, #3b7966), var(--brand-ink, #275e4e));
  display: grid;
  place-items: center;
  cursor: pointer;
  box-shadow: 0 10px 24px rgba(39, 94, 78, .28);
  transition: transform .18s ease, box-shadow .18s ease;
}
.ball:hover { transform: translateY(-1px); box-shadow: 0 14px 28px rgba(39, 94, 78, .32); }
.ball:focus-visible { outline: 2px solid var(--brand-ink, #275e4e); outline-offset: 3px; }
.ball::before,
.ball::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1px solid var(--brand, #3b7966);
  pointer-events: none;
  animation: breathe 2.8s ease-out infinite;
  z-index: -1;
}
.ball::after { animation-delay: 1.4s; }
@keyframes breathe {
  0% { transform: scale(1); opacity: .5; }
  70%, 100% { transform: scale(1.55); opacity: 0; }
}
.badge {
  position: absolute;
  top: -3px;
  right: -3px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--accent, #b2422f);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* ── 面板 ── */
.panel {
  position: absolute;
  right: 0;
  bottom: 72px;
  width: min(368px, calc(100vw - 32px));
  height: min(560px, calc(100vh - 128px));
  display: flex;
  flex-direction: column;
  background: var(--surface, #fff);
  border: 1px solid var(--line, #dde2e5);
  border-radius: 12px;
  box-shadow: 0 18px 48px rgba(15, 23, 42, .18);
  overflow: hidden;
}
.hd {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 12px 12px 14px;
  color: #fff;
  background: linear-gradient(135deg, var(--brand, #3b7966), var(--brand-ink, #275e4e));
  flex: none;
}
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, .16);
  border: 1px solid rgba(255, 255, 255, .28);
}
.who { display: flex; flex-direction: column; line-height: 1.25; flex: 1; min-width: 0; }
.who b { font-size: var(--fs-sm, 15px); font-weight: 600; }
.who small { font-size: var(--fs-xs, 12px); opacity: .82; letter-spacing: .02em; }
.icon-btn {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: .9;
}
.icon-btn:hover { background: rgba(255, 255, 255, .16); }

.list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 14px 12px;
  background: var(--surface-2, #f2f5f7);
  scrollbar-width: thin;
}

.composer {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 12px 4px;
  border-top: 1px solid var(--line, #dde2e5);
  background: var(--surface, #fff);
}
.composer textarea {
  flex: 1;
  resize: none;
  max-height: 96px;
  padding: 9px 11px;
  border: 1px solid var(--line, #dde2e5);
  border-radius: var(--r2, 8px);
  background: var(--surface-2, #f2f5f7);
  color: var(--ink, #1d2b26);
  font: inherit;
  font-size: var(--fs-sm, 15px);
  line-height: 1.5;
  outline: none;
}
.composer textarea:focus { border-color: var(--brand, #3b7966); background: var(--surface, #fff); }
.send {
  width: 38px;
  height: 38px;
  flex: none;
  border: 0;
  border-radius: 50%;
  background: var(--brand, #3b7966);
  color: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: background .15s ease;
}
.send:hover:not(:disabled) { background: var(--brand-ink, #275e4e); }
.send:disabled { opacity: .45; cursor: not-allowed; }
.hint {
  flex: none;
  padding: 0 14px 8px;
  background: var(--surface, #fff);
  color: var(--muted, #67736e);
  font-size: 11px;
}

/* ── 过渡 ── */
.panel-enter-active, .panel-leave-active { transition: opacity .18s ease, transform .18s ease; }
.panel-enter-from, .panel-leave-to { opacity: 0; transform: translateY(8px) scale(.98); }

@media (max-width: 520px) {
  .chat-root { right: 16px; bottom: 16px; }
  .panel { height: min(72vh, calc(100vh - 112px)); }
}

@media (prefers-reduced-motion: reduce) {
  .ball::before, .ball::after { animation: none; opacity: 0; }
  .panel-enter-active, .panel-leave-active { transition: none; }
}
</style>

<!-- driver.js 的浮层渲染在 body 下，scoped 样式够不到 ⇒ 这一段是**全局**的，
     `popoverClass: 'chat-tour-popover'` 只在漫游时命中。 -->
<style>
.chat-tour-popover.driver-popover {
  background: var(--surface, #fff);
  color: var(--ink, #1d2b26);
  border: 1px solid var(--line, #dde2e5);
  border-radius: var(--r3, 10px);
  box-shadow: 0 18px 48px rgba(15, 23, 42, .18);
  font-family: inherit;
  max-width: 320px;
}
.chat-tour-popover .driver-popover-title {
  font-size: var(--fs-md, 16px);
  font-weight: 600;
  color: var(--ink, #1d2b26);
}
.chat-tour-popover .driver-popover-description {
  font-size: var(--fs-sm, 15px);
  line-height: 1.6;
  color: var(--ink, #1d2b26);
  opacity: .82;
}
.chat-tour-popover .driver-popover-progress-text {
  font-size: var(--fs-xs, 12px);
  color: var(--muted, #67736e);
}
.chat-tour-popover .driver-popover-footer button {
  border-radius: var(--r1, 4px);
  text-shadow: none;
  font-size: var(--fs-xs, 12px);
  padding: 5px 10px;
}
.chat-tour-popover .driver-popover-next-btn {
  background: var(--brand, #3b7966);
  border: 1px solid var(--brand, #3b7966);
  color: #fff;
}
.chat-tour-popover .driver-popover-prev-btn {
  background: var(--surface, #fff);
  border: 1px solid var(--line, #dde2e5);
  color: var(--brand-ink, #275e4e);
}
.chat-tour-popover .driver-popover-close-btn {
  color: var(--muted, #67736e);
}
</style>
