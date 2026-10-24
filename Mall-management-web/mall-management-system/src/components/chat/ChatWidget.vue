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
            @typed="onTyped(m)"
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
import { isLoggedIn } from '../../api/auth'
import { UnauthorizedError } from '../../api/http'
import {
  applyPath,
  clearPendingTour,
  isMerchantRole,
  ordersPath,
  PUBLISH_FLOW_ID,
  readPendingTour,
  resumePathAfterLogin,
  resumeTourFlow,
  savePendingNav,
  savePendingTour,
  SECKILL_FLOW_ID,
  setTourNavigator, startGoodsTour, startNavGuidedTour, startTourFlow,
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
function isLoginPath(p) {
  const n = normalize(p)
  return n === '/login' || n === '/register'
}

const reduceMotion = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
  : false

/**
 * 滚到底，**分两种语义**（2026-10-22 用户实测：打开面板时先甩到顶部再瞬移，掉帧闪烁）：
 * · `smooth=false`（打开面板 / 渲染历史）：`nextTick` 里直接赋 `scrollTop` ——
 *   回调在 Vue 打完补丁、浏览器**绘制之前**的微任务里执行，所以第一帧就已经在底部；
 *   直接赋值还会**取消**上一段未完成的平滑滚动，不会出现两条滚动打架。
 * · `smooth=true`（自己发消息 / 客服回复 / 打字结束）：`behavior:'smooth'`，看得到气泡滑入。
 * `prefers-reduced-motion` 下一律瞬移。
 */
function scrollToEnd(smooth = false) {
  nextTick(() => {
    const el = listRef.value
    if (!el) return
    if (smooth && !reduceMotion) {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    } else {
      el.scrollTop = el.scrollHeight
    }
  })
}

/** 一条客服消息打完字 ⇒ 标记成 `instant`：再次打开面板时不再重放打字机，也不触发滚动跳动。 */
function onTyped(msg) {
  if (msg && msg.role === 'bot') msg.instant = true
  scrollToEnd(true)
}

function openChat() {
  open.value = true
  unread.value = 0
  // 历史渲染：无感定位到底部（绘制前完成，不做平滑动画）。
  scrollToEnd(false)
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
  scrollToEnd(true)
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
  // 自己发出去的新消息：平滑滑入。
  scrollToEnd(true)

  let ok = false
  try {
    // `route.path` 让后端把「当前在哪一页」补进 Prompt；前端仍以 BFS 算路为准。
    const res = await askChat(clean, route.path)
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
    // 回复到达：平滑滑入（面板没开时这次滚动无副作用）。
    scrollToEnd(true)
  }
}

function onRetry(msg) {
  send(msg.retryMessage || msg.text, msg)
}

async function onSuggestion(s) {
  if (s && typeof s === 'object') {
    if (s.routeKey === 'goods') {
      // 商品详情：从顶栏搜索框开始一步步高亮（首页 / 搜索页 / 商家后台都能起步）。
      close()
      const tour = await startGoodsTour(s.keyword, route.path)
      if (!tour) {
        pushBot('当前页面没有顶栏搜索框。先回到商城主页，再点一次「带我去商品详情」，我从搜索框带你走。')
      }
      return
    }
    if (s.routeKey === 'apply') {
      // 默认引导：从当前页 BFS 走到入驻申请页，而不是瞬移过去。
      onAction({ type: 'tour', targetPath: applyPath(), targetLabel: '商家入驻申请', label: s.label })
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
      // 多步路径引导：先高亮当前页的入口，用户点「下一步」才跳；不直接 push 目标页。
      close()
      startTourFlow(PUBLISH_FLOW_ID, route.path).then((tour) => {
        // 裸页（商品详情 / 店铺页）没有顶栏入口 ⇒ 不替用户跳转，只提示回商城主页。
        if (!tour) pushBot('当前页没有「用户中心」入口。先回到商城主页，再点一次「去发布商品」，我从右上角带你进去。')
      })
      return
    }
    if (s.routeKey === 'applyStatus') {
      // 「查入驻进度」= 只去申请页看状态，不自动开漫游（pending 状态没有可填的表）。
      close()
      router.push(applyPath()).catch(() => {})
      return
    }
    if (s.routeKey === 'search') {
      // 「去商城搜索」：买家 / 商家分壳；关键词走 query，由 GoodsSelect 读取。
      close()
      const base = isMerchantRole() ? '/merchant/search' : '/customer/search'
      const kw = s.keyword ? String(s.keyword) : ''
      router.push({ path: base, query: kw ? { kw } : {} }).catch(() => {})
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

/**
 * 动作按钮分发（2026-10-22 交互纠偏后的两种模式）：
 * · `jump`  —— 用户明说「直接去 / 我要去」⇒ **不触发漫游**，`router.push` 直达；
 * · `tour`  —— 默认「怎么去」⇒ 从**当前页**跑 BFS 逐步高亮，绝不替用户瞬移；
 * · `navigate` 是旧协议的引导值，按 `tour` 处理。
 */
async function onAction(action) {
  if (!action) return
  const targetPath = action.targetPath ? String(action.targetPath) : ''

  if (action.type === 'jump' && targetPath) {
    close()
    if (isLoggedIn()) clearPendingTour()
    else savePendingNav('jump', targetPath)
    if (normalize(route.path) !== normalize(targetPath)) {
      router.push(targetPath).catch(() => {})
    }
    return
  }

  if ((action.type === 'tour' || action.type === 'navigate') && targetPath) {
    close()
    if (isLoggedIn()) clearPendingTour()
    else savePendingNav('tour', targetPath)
    const result = await startNavGuidedTour(route.path, targetPath)
    if (!result) {
      pushBot(`当前页面没有通往「${action.targetLabel || targetPath}」的入口。先回到商城主页，再点一次这个按钮，我从头带你走。`)
    }
    return
  }

  // 旧协议兜底（只有 tourId / routeKey 的入口）：先到申请页，再开两步表单高亮。
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
  // 秒杀两步在本页内完成；不在报名页时 startTourFlow 会先导航，watcher 再续。
  startTourFlow(SECKILL_FLOW_ID, route.path)
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
      if (pending && (pending.routeKey === 'seckill' || pending.routeKey === 'publish') && !isMerchantRole()) {
        clearPendingTour()
        if (pending.routeKey === 'seckill') {
          openChat()
          send('我要报名秒杀')
        }
        return
      }
      const target = resumePathAfterLogin(normalize(to))
      if (target) {
        // 登录打断了「引导」：把待办换成开店两步表单，申请页挂载后自动接着教；
        // 「直达」则保持干脆 —— 清掉待办，落页即结束。
        if (pending && pending.nav) {
          if (pending.nav.type === 'tour' && isApplyPath(target)) savePendingTour('open-store', 'apply')
          else clearPendingTour()
        }
        router.push(target).catch(() => {})
        return
      }
      if (pending && (pending.routeKey === 'seckill' || pending.routeKey === 'publish')) clearPendingTour()
    }

    if (isApplyPath(to)) {
      const pending = readPendingTour()
      if (pending && pending.routeKey === 'apply') await startTour()
    }
    // 多步引导：上一步的「下一步」已经 push 完，这里等新页挂载后接着高亮。
    // 用户若自己导航到流程之外，resume 内部会把索引判为 -1 并结束流程。
    await resumeTourFlow(to)
  },
)

function onKeydown(e) {
  if (e.key === 'Escape' && open.value) close()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  // 引导流的跨页跳转由本组件代理（useTour 不能直接 import router，否则成环）。
  setTourNavigator((path) => router.push(path).catch(() => {}))
  // 直接刷新在申请页 / 从别处回到申请页：待办也可能已经在了。
  // 发布 / 秒杀页的锚点续跑由各自页面 onMounted 兜底，这里只补流程存档。
  const pending = readPendingTour()
  if (isApplyPath(route.path) && pending && pending.routeKey === 'apply') startTour()
  resumeTourFlow(route.path)
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
/* ── 漫游态：同一颗球飞出去当「具身小助手」（useTour.js 给 .chat-root 加 tour-mode） ──
   z-index 必须盖过 driver 的遮罩（10000）：球是在「飞行 + 落地高亮」全程唯一被看到的角色。
   飞行位置由行内 style.transform 控制；这里只管层级与可点性（按钮在 .tour-bar 里）。 */
.chat-root.tour-mode { z-index: 10002; }
.chat-root.tour-mode .ball { pointer-events: none; }

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

<!-- 漫游期间的 body 级 DOM（driver 遮罩 / 控制条）在 scoped 样式的作用域之外 ⇒ 这一段是**全局**的。 -->
<style>
/* ── 彻底关掉 driver 自带的 popover（2026-10-22 用户裁决） ──
   driver 只保留「压暗整页 + 挖出目标」；文案与【上一步 / 下一步 / 跳过】全部由
   `.tour-bar`（贴在飞过去的悬浮球旁边）承担。display:none 而不是透明：透明元素仍会
   吃点击、仍会被读屏念出来。 */
.chat-tour-popover.driver-popover { display: none !important; }

/* ── 漫游控制条（useTour.js 创建，2026-10-22） ──
   `left/top` 由脚本按落点算；这里只负责长相与层级（要压在遮罩之上）。 */
.tour-bar {
  position: fixed;
  z-index: 10003;
  width: 268px;
  max-width: calc(100vw - 24px);
  padding: 12px 14px 14px;
  border: 1px solid var(--line, #dde2e5);
  border-radius: var(--r3, 10px);
  background: var(--surface, #fff);
  color: var(--ink, #1d2b26);
  box-shadow: 0 18px 48px rgba(15, 23, 42, .18);
  font-family: inherit;
}
.tour-bar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.tour-bar-step {
  font-size: var(--fs-xs, 12px);
  color: var(--muted, #67736e);
  font-variant-numeric: tabular-nums;
}
.tour-bar-skip {
  /* 弱化的文字按钮：放在操作行最左，靠 `margin-right:auto` 与右侧按钮拉开。 */
  margin-right: auto;
  border: 0;
  padding: 2px 0;
  background: transparent;
  color: var(--muted, #67736e);
  font: inherit;
  font-size: var(--fs-xs, 12px);
  white-space: nowrap;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.tour-bar-skip:hover { color: var(--ink, #1d2b26); }
.tour-bar-title {
  font-size: var(--fs-md, 16px);
  font-weight: 600;
  line-height: 1.35;
}
.tour-bar-desc {
  margin-top: 4px;
  font-size: var(--fs-sm, 14px);
  line-height: 1.6;
  color: var(--ink, #1d2b26);
  opacity: .82;
}
.tour-bar-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.tour-bar-prev,
.tour-bar-next {
  border-radius: var(--r1, 4px);
  padding: 6px 12px;
  font: inherit;
  font-size: var(--fs-xs, 12px);
  cursor: pointer;
}
.tour-bar-prev {
  border: 1px solid var(--line, #dde2e5);
  background: var(--surface, #fff);
  color: var(--brand-ink, #275e4e);
}
.tour-bar-next {
  border: 1px solid var(--brand, #3b7966);
  background: var(--brand, #3b7966);
  color: #fff;
}
.tour-bar-prev:hover { border-color: var(--brand, #3b7966); }
.tour-bar-next:hover { background: var(--brand-ink, #275e4e); }

/* ── 把控制条从 driver 的「全局禁点」里救回来（2026-10-22 实测修） ──
   driver 一 drive() 就给 body 挂 .driver-active，并启用自带规则
     `.driver-active * { pointer-events: none }`（只豁免被高亮元素与 .driver-popover）。
   我们的 `.tour-bar` 是 body 级节点 ⇒ 视觉上在、三个按钮全部点不动（真鼠标点击
   直接穿透到遮罩上；JS 的 el.click() 不经过命中测试，所以探针一直没抓到）。
   `.driver-active .tour-bar` 的特异性 (0,2,0) 高于 `.driver-active *` (0,1,0)，
   不依赖样式插入顺序。 */
.driver-active .tour-bar,
.driver-active .tour-bar * { pointer-events: auto; }
</style>
