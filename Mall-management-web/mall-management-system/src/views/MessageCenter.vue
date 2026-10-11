<template>
  <div class="msg-center">
    <div class="mc-bar" role="tablist" aria-label="消息中心">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="mc-tab"
        type="button"
        role="tab"
        :aria-selected="tab === t.key ? 'true' : 'false'"
        :class="{ active: tab === t.key }"
        @click="goTab(t.key)"
      >
        <IconGlyph :name="t.glyph" />{{ t.label }}
      </button>
    </div>

    <!-- ⚠️ **必须是 `v-if` 不是 `v-show`**：`ChatRoom` 带一支 **2s 轮询**（`:447`），`v-show` 只藏不卸
         ⇒ 停在「通知」tab 时后台仍每 2s 打一次 `/message/*`（`AppShell.vue:99-100` 早就为 KeepAlive
         记过同一条：「别把 ChatRoom 加进来」）。卸载则 `onUnmounted` 里的 `clearInterval` 照常回收。 -->
    <ChatRoom v-if="tab === 'chat'" />
    <NotifyInbox v-else />
  </div>
</template>

<script setup>
// src/views/MessageCenter.vue —— **消息中心**：把「消息」与「通知」两个入口合并成一个页（2026-10-09）。
//
// ⚠️ **本组件只换「怎么进」，不换「进去干什么」**（用户口径逐字）：`ChatRoom.vue` 与
//    `NotifyInbox.vue` 的**内部逻辑一字不动** —— 这里只做「选哪个显示」+ 一条 tab 条。
//    后端、两张表（`message` / `notification`）、两套端点、两个 Controller **也都不动**：
//    合并的是**前端入口**，不是两套域。所以 `NotifyInbox` 该调 `/notification/*` 还是照调。
//
// ⚠️ **为什么两个子组件不需要任何 prop**：它们各自从 `route` 反推身份 —— `ChatRoom.vue:180-211`
//    读 `window.location.pathname`、`NotifyInbox.vue:99-103` 读 `route.path.startsWith('/merchant')`。
//    本组件挂在 `${base}/msg` 下（`/customer/msg` ⇄ `/merchant/msg`），**路径前缀不变**
//    ⇒ 两边的反推结果与合并前**逐字相同**（这正是「不改它们内部」能成立的原因）。
//
// ⚠️ **tab 状态住在 query（`?tab=notify`）**，不住组件内部 state（用户口径 5）：
//    ⇒ 可深链、可刷新、可后退，且 `/customer/notify` 那条旧地址能靠 redirect 直接落到通知 tab。
//    判据是 `route.query.tab === 'notify'`（**字符串精确比**）：`?tab=chat`、缺 `tab`、`tab` 是数组
//    （`?tab=a&tab=b`）**一律**落「会话」—— 默认值选会话，因为它是合并前 `/…/msg` 的行为。
import { computed } from 'vue'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移
import { useRoute, useRouter } from 'vue-router'
import ChatRoom from './ChatRoom.vue'
import NotifyInbox from './NotifyInbox.vue'

/** ⚠️ 顺序 = 显示顺序，且**会话在前**：与合并前「点顶栏『消息』默认进会话」一致。 */
const TABS = [
  // ⚠️ `glyph`（2026-10-11 补图标那一笔）与**顶栏**同一套语义：会话 = 人与人（`msg` ✉）、
  //    通知 = 系统发给你（`notify` ✎）—— 与 `glyphs.js` 里「`msg` 一枚两处共用」的口径同源。
  { key: 'chat', label: '会话', glyph: 'msg' },
  { key: 'notify', label: '通知', glyph: 'notify' }
]

const route = useRoute()
const router = useRouter()

/** 当前 tab —— `route.query` 是响应式的 ⇒ 浏览器后退 / 手改地址都会跟着切。 */
const tab = computed(() => (route.query.tab === 'notify' ? 'notify' : 'chat'))

/**
 * 切 tab：**只改 query、不换路径** ⇒ 壳（`AppShell`）不重建、`route.path` 不变
 * （壳里那条清红点的 `watch` 看的是 `[route.path, route.query.tab]`，见 `AppShell.vue`）。
 *
 * ⚠️ `{ ...route.query, tab: key }` 里的 `...route.query` **不能省**：`ChatRoom` 读
 *    `otherId` / `otherType` / `_t` 三个会话参数（`ChatRoom.vue:419-445`）—— 直接
 *    `{ query: { tab: key } }` 会把它们冲掉，表现是「切去通知再切回来，会话没了」。
 * ⚠️ `replace` 而**不是** `push`：切 tab 是页内视图切换，不该在浏览器历史里堆一格
 *    （否则「后退」要点两次才回得去上一页）。⚠️ 代价：切 tab **进不了后退栈**，
 *    要分享带 tab 的地址得复制 URL —— 与「tab 住 query」的收益相比这是刻意的取舍。
 */
function goTab(key) {
  if (key === tab.value) return
  router.replace({ query: { ...route.query, tab: key } })
}
</script>

<style scoped>
/* tab 条与两个子页**对齐**：子页（`.chat-room:489` / `.notify-room:296`）都是
   `max-width:1180px; margin:0 auto; padding:var(--s6) var(--s5) …` ⇒ 本条约同宽同侧距，
   否则切 tab 时内容会左右跳一下。⚠️ 下内距为 0、由子页自己的 `padding-top` 出间距（不叠加）。 */
.mc-bar {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s6) var(--s5) 0;
  display: flex;
  gap: var(--s2);
  border-bottom: 1px solid var(--line);
  font-family: var(--font);
}
.mc-tab {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: none;
  border: 0;
  /* 2px 底线压在 `.mc-bar` 的 1px 发丝线上（`margin-bottom:-1px`）⇒ 选中态与分隔线**同一条**，
     不出现「双线」。改这里要跟 `.mc-bar` 的 padding-bottom 一起看。 */
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  padding: var(--s2) var(--s3);
  cursor: pointer;
}
.mc-tab:hover {
  color: var(--ink);
}
/* 选中态用 `--nav-now`：与本壳顶栏「消息」项的高亮**同一个颜色**（切进本页后顶栏那项是亮的、
   页内的 tab 也是亮的，用户看到的是同一次「我在消息里」）。 */
.mc-tab.active {
  color: var(--nav-now);
  font-weight: var(--fw-semi);
  border-bottom-color: var(--nav-now);
}
</style>
