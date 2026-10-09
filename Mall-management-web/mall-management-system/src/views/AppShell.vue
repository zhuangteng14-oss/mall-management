<template>
  <div class="app-shell">
    <div class="top-nav">
      <div class="nav-left">
        <router-link v-for="item in navTop.left" :key="item.tab" :to="item.to" class="nav-btn"
                     :class="{ active: route.meta.tab === item.tab }">{{ item.label }}
          <span v-if="dotCount(item.badge) > 0" class="nav-red-dot"></span></router-link>
      </div>
      <!-- ── 顶栏搜索框（用户 2026-10-08 第 1 条）：顶栏只留一行 = 商城主页 | 搜索框 | 账号。
           形态照 `ProductPage.vue` 的顶栏搜索（用户：「参考商品详情页的顶栏结构，那个是对的」）；落点 = `${base}/search?kw=`,
           与 `handlePendingSearch`（本文件 `:201`）同路径、同 query 键。⚠️ 匿名也渲染（搜索页 `meta.public`）；空串由 `required` 挡。 -->
      <form class="nav-search" @submit.prevent="router.push({ path: `${base}/search`, query: { kw: $event.target.kw.value.trim() } })">
        <input name="kw" type="text" class="search-input" placeholder="搜索商品、品牌、分类" required />
        <button type="submit" class="search-btn">搜索</button>
      </form>
      <div class="nav-right">
        <router-link
          v-for="item in navTop.right"
          :key="item.tab"
          :to="item.to"
          class="nav-btn"
          :class="{ active: route.meta.tab === item.tab }"
        >
          {{ item.label }}
          <span v-if="dotCount(item.badge) > 0" class="nav-red-dot"></span>
        </router-link>
      </div>
    </div>

    <!-- ── 黄条（补丁 B2，2026-10-09 手验后，用户裁决**甲案**）──────────────────────
         「通知要在主页显眼提示」。甲案 = 保留 2026-10-09 已合的**单一入口 + 红点**（那笔**不回退**、
         `nav.js` 的墓碑也留着），**只加这条黄条** ⇒ 红点负责「有东西没看」，黄条负责「说人话、给个去路」。
         · 触发 = **未读通知数 > 0**（`bannerText`），与红点**同源同一支 5s 轮询**（不新起请求）。
           ⚠️ 只认通知、**不认未读消息**（`unreadCount` 不进这里）：消息是两人会话，「对方回了一句」
           本来就天天发生，弹黄条会变成噪音；管理员发来的通知是**平台单方面找你**，才值得打断。这是两者唯一的区别。
         · 落点 = `${base}/msg?tab=notify`：进那个 tab 后上面的 `watch` 会把 `notifyUnreadCount` 清 0，黄条自然消失
           —— **不需要**在这里再写一条「已读」逻辑。
         · 模板里那两处 `dotCount(item.badge)` **一个都没动**（合并入口那笔的成文口径见 `<script>` 里 `dotCount` 上方注释）。 -->
    <div v-if="bannerText" class="nav-notice">
      <span class="nav-notice-text">{{ bannerText }}</span>
      <router-link class="nav-notice-go" :to="{ path: MSG_PATH, query: { tab: 'notify' } }">去查看 →</router-link>
      <button type="button" class="nav-notice-x" aria-label="关闭提示" @click="dismissBanner">×</button>
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
import { navFor } from '../config/nav'

// ── R6（2026-10-05）：两壳合一 ────────────────────────────────────────────────────
// 本组件由 `CustomerPage.vue`（R1 客户壳）与 `MerchantPage.vue`（R2 商家壳）合并而来 ——
// 两者各 240 行、逐行 diff 只有 **6 处语义差异**（用户裁决 D2 = 删两壳、本组件全权接管）：
//   ① 根类名（两份 CSS 体逐条相同 ⇒ 合一为 `.app-shell`）
//   ② 顶栏表（客户 `CUSTOMER_NAV` ⇄ 商家 `MERCHANT_NAV`）
//   ③ `getCurrentUser()` 的降级链**顺序相反**（⇒ 第三 prop `role`）
//   ④ 消息页路径（`/customer/msg` ⇄ `/merchant/msg`）
//   ⑤ 待搜索关键词的落点（`/customer/search` ⇄ `/merchant/search`） ⇒ ④⑤ 由 `base` 承载
//   ⑥ 布局容器类名（`.cart-container` ⇄ `.order-container`，后者多一行 `position: relative`）
// ⇒ ①②③ 走 props；④⑤ 由 `base` 拼；⑥ **两类名都保留**（裁决 D7，不合并 —— 见样式块注释）。
//
// ⚠️ **验收口径**：两壳的全部差异 = 上面 6 条。其中**顶栏**只差两处 —— 左项「商城主页」的
//    `to`（各指自己的壳）+ 右起第 2 项（客户「购物车」⇄ 商家「订单中心」）；其余项数 / 文案 /
//    `tab` / 红点逐字同构。（这句话 R6 前在 `CustomerPage.vue:70`，随两壳合一搬来本文件。）
//
// 壳现在仍是三件事：顶栏、内容区外壳、未读红点轮询。**页面选择权全交给路由**（见 `router/index.js`）。
//
// ⚠️ 与 R1/R2 的**两轴口径**不变（见 progress.md §5-X / §5-Y 节首）：轴 1 = tab 状态住在哪
//    （内部 ref ⇄ 路由）；轴 2 = 壳与页是否拆开（`v-if` ⇄ `<router-view>`）。本笔只做**消重**，
//    两轴都不动 —— 不合并 `landingFor` 与 `roleKey`（R4 的 D7 记为「R6 可选」，R6 的 D10 裁定
//    同样**不做**：会动 R3 登记的 `router/index.js:192` / `:197`，且与 AppShell 无关）。

// ⚠️ **本壳不会因切 tab 而重挂**（这是下面 `onMounted` 里那支未读轮询「只跑一次」的**唯一**依据）：
//    `/customer` / `/merchant` 都是**父记录**，各自的 `children` 之间切换**不重建父实例**。
//    后人若把子路由提成**平级记录**、或给壳加 `:key`（如 `<router-view :key="route.path">`），
//    这个性质立刻消失 —— 表现是「每切一次 tab 就多一支 5s 轮询、永不回收」。

// ⚠️ `CACHE_TABS` 的匹配依据是**组件的 name**（不是路径）。这两个 name 写在
//    `views/HomeTab.vue` 与 `views/GoodsSelect.vue` 的 `defineOptions` 里 ——
//    改名 = 缓存**静默失效**（不报错，只是切回来重新挂载、轮播重置 / 搜索态丢失）。改名前先 grep `include`。
//    为什么只缓存这两个：路由化前两壳的 home / search 都是 `v-show`（切走**不卸载**），其余是 `v-if`（卸载）
//    ⇒ 只缓存这两个才与路由化前**逐字等价**。
//    ⚠️ **两壳原本的值逐字相同 ⇒ 写死在此、不进 props**（用户裁决 D1：同一常量、同一理由）。
//    ⚠️ 本组件对两条记录**各挂一次**，两个 `<KeepAlive>` 实例各自独立，**不会串缓存**。
//    ⚠️ **别把 ChatRoom 加进来**：它有 2s 轮询（`ChatRoom.vue:369`），缓存 = 离开消息页后
//    **后台继续轮询**（新行为，且与壳里那支 5s 未读轮询重复）。
const CACHE_TABS = ['HomeTab', 'GoodsSelect']

// ⚠️ `eslint-disable-next-line no-undef` 是**必需**的：本仓 `eslint-plugin-vue` 是 **8.7.1**，
//    其 `vue3-essential` **不带**宏环境（连它导出的 `setup-compiler-macros` 也只有 4 个宏、**不含**
//    `defineOptions`）⇒ `defineProps` 一律被判 `no-undef`、`npm run build` 直接红。先例：`HomeTab.vue`。
// eslint-disable-next-line no-undef
const props = defineProps({
  /** 顶栏表 —— `src/config/nav.js` 的 `CUSTOMER_NAV` / `MERCHANT_NAV`（两壳顶栏的**单一事实源**）。 */
  nav: { type: Object, required: true },
  /** 本壳的 URL 前缀（`/customer` / `/merchant`）—— 拼「消息页清红点」与「跨页待搜索」两条路径。 */
  base: { type: String, required: true },
  /** 本壳身份（`'customer'` / `'merchant'`）—— **只**用于 `getCurrentUser()` 的降级链顺序（见下）。 */
  role: { type: String, required: true }
})

const route = useRoute()
const router = useRouter()

// 内容区外壳类名随路由走（`meta.layout`）—— 与路由化前那些兄弟 div 的类名**一一对应**
// （`.home-container` / `.page` / `.cart-container` / `.order-container` / `.user-page`），
// 所以布局逐字不变。子记录不写 `layout` 则**继承**父记录的（`meta` 沿 `matched` 链**合并**）。
// ⚠️ 布局类名的**所有权**在壳（页组件自己不带布局类）—— 这句话 R1 起就成立，R6 后仍然成立。
// ⚠️ **R6 裁决 D4：推翻 §5-X (13)1 那条承诺**（原文「R6 应把『内容区外壳 div』连同类名**下移**到
//    各页组件、`meta.layout` 随之退役」）—— **本笔不做**：① 下移要动 12 个页组件，远超任何预算；
//    ② `meta.layout` 是「路由决定布局」的**自洽**机制（一条 computed + 路由表里的字符串），
//    那条承诺写下时（R1）还没看到它跑起来的效果；③ R6 的章程是**纯消重**。
const layoutClass = computed(() => route.meta.layout || 'page')

// 未读消息数量（用于顶部红点）
const unreadCount = ref(0)
// 未读**系统通知**数量（I2，2026-10-09）—— 与消息那条是**两条独立的链**：不同的表、不同的端点、不同的清点时机；
//    ⚠️ **两条链各算各的**（合并成一个**源**得先有后端的「未读合计」端点，方案 §七 未决 5 的裁定），但 ⚠️ **顶栏那一个点
//    是两笔之和**（2026-10-09 入口合并）—— 相加在 `dotCount` 里、不在本变量，清点也仍分两笔（见下方 `watch`）。
const notifyUnreadCount = ref(0)
let unreadPollingTimer = null

/** 当前用户 —— ⚠️ **`role` prop 唯一的用武之地，且两壳原本的降级链顺序是「有意相反」的**：
 *  客户端 `customerid` → `merchantid` 兜底（原注释「客户端应只有客户」）；
 *  商家端 `merchantid` → `customerid` 兜底（「商家端应只有商家」）。
 *  ⇒ 这**不是**「碰巧写成不同顺序」，**不能统一**：两端 id 可以同时在 —— 新标签页恢复成商家 Y
 *  之后再登录成客户 B、而 B 的 `merchantid` 为空时（`LoginPage.vue` 的 `if` 跳过写），
 *  `restored.merchantId` 会**留着 Y**（见 `api/auth.js` 的 `restore` 段说明）。
 *  与改前**逐步等价**：原两条链都是「先取本端 id，能用则返回；否则取另一端，能用则返回；否则 null」。
 *  ⚠️ 两个读口都是纯读（`auth.js` 的 `pick`），全取出来再按顺序挑，不引入副作用。 */
const usable = (id) => (id && id !== 'null' && id !== 'undefined' ? id : null)
const getCurrentUser = () => {
  const ids = { customer: usable(getCustomerId()), merchant: usable(getMerchantId()) }
  const order = props.role === 'merchant' ? ['merchant', 'customer'] : ['customer', 'merchant']
  const type = order.find((t) => ids[t])
  return type ? { id: ids[type], type } : null
}

// 获取未读消息数量
const fetchUnreadCount = async () => {
  const user = isLoggedIn() ? getCurrentUser() : null   // ⚠️ 门用 `isLoggedIn()`（= `username || merchantid`），不是「拿得到一个 id」——「点退出」只清 `username` / `role`、**不清 `customerid`**（`api/auth.js:156-161`，且 `:174-177` 明写这是**有意**的）⇒ 残留的 `customerid` 会让本函数照打 `/message/conversations` ⇒ 401 ⇒ `api/http.js` 的 401 出口把**公开面**（首页 / 搜索）也踢到 `/login`。实测：点退出后回首页被送到 `#/login?redirect=/customer`，商品全空；手工抹掉那个残留 id 即恢复 7 卡。登记见 `docs/agent/slot-registry.md` §九
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

// 获取未读系统通知数量（I2）—— 与上面 `fetchUnreadCount` **逐条对应**，只差端点与两个参数名。
// ⚠️ 同样**第一句就判 `isLoggedIn()`**（理由见上一条的 ⚠️：点退出只清 `username` / `role`、
//    **不清** `customerid` ⇒ 残留 id 会让匿名请求打到受保护端点 ⇒ 401 ⇒ 401 出口把公开页也踢走）。
// ⚠️ 参数名是 `toId` / `toType`（**不是**消息域的 `currentId` / `currentType`）—— 两个域各叫各的，
//    别顺手「统一」：那是后端两个 Controller 的既定入参名（`NotificationController` ⇄ `MessageController`）。
const fetchNotifyUnread = async () => {
  const user = isLoggedIn() ? getCurrentUser() : null
  if (!user) return
  const params = new URLSearchParams()
  params.append('toId', user.id)
  // ⚠️ `getCurrentUser().type` 的取值域（`'customer'` / `'merchant'`）**恰好**与后端 `ToType` 一致
  //    —— 两者同源于 `message.from_type` 那套英文（后端 `NotificationService.TO_TYPE_*`）⇒ 可直接透传，
  //    不需要任何映射。这条巧合**写下来**：哪天 `message` 的英文改了口径，这里是第二处要跟着改的地方。
  params.append('toType', user.type)
  try {
    const res = await http.post(endpoints.notification.unreadCount, params.toString())
    const data = await res.json()
    if (data.success) notifyUnreadCount.value = data.unread || 0
  } catch (err) {
    handleApiError(err, '获取未读通知数失败')
  }
}

/** 顶栏项的未读数 —— `item.badge` 的**唯一**解释处（模板里两处红点条件都走它）；认不出的 `badge`（含 `undefined`）一律 `0`。
 *  ⚠️ **2026-10-09 入口合并后 `'unread'` 是两笔的合计**（未读消息 + 未读通知）、`'notify'` 那支**已删** —— 顶栏只剩一个「消息」项（`nav.js` 有墓碑），红点必须两笔都带（详见 `slot-registry.md §十六`）。
 *  ⚠️ 合并的**只是这一个点**：两笔计数与两支 5s 轮询（`fetchUnreadCount` / `fetchNotifyUnread`）**仍各走各的**；匿名态 `ANON_RIGHT` 不带 `badge` ⇒ 天然无红点、不必为「未登录」另写分支（`navFor` 只换表、不换壳）。 */
const dotCount = (badge) => (badge === 'unread' ? unreadCount.value + notifyUnreadCount.value : 0)

// 进入消息中心则立即清红点（路由化后改看路由 —— 壳里已无 `curPage`）。判据与路由化前 `curPage === 'msg'` 一致：只认**壳的** `/customer/msg`
//    （⚠️ **2026-10-09 合并入口**后同一地址下**多了两个 tab**（`?tab=notify`）⇒ 清哪一笔要跟 tab 走、`route.path` 在此**不足**。）
//    ⚠️ A5（2026-10-07）后本条**只剩一种地址**：`/…/user/chat` 已改 `redirect` → `/…/msg`（`router/index.js`）⇒ 旧深层链接也会落到本判据上、红点照样清。
const MSG_PATH = `${props.base}/msg`
// ⚠️ 两个 tab **各清自己那一笔**（顶栏红点是**合计**，但「读没读」是两件事）：会话 tab 清 `unreadCount`、通知 tab 清 `notifyUnreadCount`
//    ⇒ 合计红点随两个 tab 被逐个访问而递减（有意）。⚠️ 原 `NOTIFY_PATH` 常量**已删**（那页已 redirect 到 `…/msg?tab=notify` ⇒ `route.path` 永远不是它、留着即死分支）。
const isNotifyTab = (t) => t === 'notify' || (Array.isArray(t) && t[0] === 'notify')   // ⚠️ `?tab=a&tab=b` 是**数组**（非 `'notify'` 一律当会话，与 `MessageCenter` 同口径）
watch(() => [route.path, route.query.tab], ([p, t]) => {
  if (p !== MSG_PATH) return
  isNotifyTab(t) ? (notifyUnreadCount.value = 0) : (unreadCount.value = 0)
})

// ── 补丁 B2：主页黄条 ─────────────────────────────────────────────────────────
/** 关掉黄条那一刻的未读数；`-1` = 本次进出壳还没关过。
 *  ⚠️ 它**不是**「已读水位」——只是「关掉时是多少」⇒ 在它**之上**再来一条新通知，黄条会重新出现
 *     （关掉不该等于对后来的通知永久静音；这一点是**有意**的，不是 bug）。 */
const bannerSeenAt = ref(-1)
/** 黄条文案；空串 = 不渲染（`v-if="bannerText"`）。⚠️ 匿名态 `notifyUnreadCount` 恒 0 ⇒ 天然不出现，不必另判登录。 */
const bannerText = computed(() => {
  const n = notifyUnreadCount.value
  if (n <= 0 || n <= bannerSeenAt.value) return ''
  return `您有 ${n} 条来自管理员的通知`
})
function dismissBanner() {
  bannerSeenAt.value = notifyUnreadCount.value
}

// 跨页待搜索关键词（由商品详情页的搜索框写入：`ProductPage.vue:380` 写、`:382` 推回壳）
// 路由化（R1/R2）起改为**推路由** `…/search?kw=`（关键词由 URL 承载）；路由化前是
//     置 `curPage='search'` + `nextTick` 后调子组件 ref，`<router-view>` 下没有 ref 可用。
const handlePendingSearch = () => {
  const pendingKeyword = sessionStorage.getItem('PENDING_SEARCH_KEYWORD')
  if (pendingKeyword === null) return
  sessionStorage.removeItem('PENDING_SEARCH_KEYWORD')
  router.push({ path: `${props.base}/search`, query: { kw: pendingKeyword } })
}

onMounted(async () => {
  handlePendingSearch()
  // 启动轮询获取未读消息数 / 未读通知数
  await fetchUnreadCount()
  await fetchNotifyUnread()
  // ⚠️ 两支轮询**共用同一个定时器句柄**（本壳一次进出只挂一次，见上方那条「不重挂」的说明）
  //    ⇒ 卸载时一次 `clearInterval` 两件都停。⚠️ 别图省事再起第二支 5s 轮询：那要多一个句柄变量，
  //    而 `unreadPollingTimer` 这个名字会变成半真半假（它只停得住其中一支）。
  unreadPollingTimer = setInterval(() => {
    fetchUnreadCount()
    fetchNotifyUnread()
  }, 5000)
})

onUnmounted(() => {
  if (unreadPollingTimer) clearInterval(unreadPollingTimer)
})

// R3：顶栏的登录态分支 —— **在这里算一次**（`isLoggedIn()` 不是响应式，写成 `computed` 会**永远不
//   更新**；见 `config/nav.js` 的 `navFor`）。壳每次进出都会重建 ⇒ 登录 / 退出 / 401 后必然重算，够用。
const navTop = navFor(props.nav, isLoggedIn())
</script>

<style scoped>
.app-shell {
width: 100%;
/* ⚠️ **D 笔 commit 7c**（用户 2026-10-07 裁决，7b 的延伸）—— `height:100vh` → `min-height:100vh`。
   7b 给 `.top-nav` 加了 `sticky`，但 sticky 的约束矩形 = **包含块**，而 `.app-shell` 原先是
   `height:100vh` = 900px 的**固定盒** ⇒ 吸附上限 = 900 − 60 = **840px**，长页滚到底顶栏仍会释放
   （实测 `/customer/user/info` 滚 857 时 y=−17）。改成 `min-height` 后壳随内容长高 ⇒ sticky 全程生效（⚠️ **A 笔 A2（2026-10-07）实测限定**：只对「内容在流」的页成立，**首页不成立** —— `.home-container` 是 `position:absolute`（见本文件下方那条）⇒ 不贡献高度，`.content` 停在 840、`.app-shell` 仍是 **900**〈量测 shell 900 / content 840 / homePage 1881〉⇒ 吸附上限仍 **840px**（实测滚 841 时 `navY = −1`）；正文见 `HomePage.vue` 吸顶带块，那是**结构问题**、本笔不顺手改）。
   ⚠️ sticky 只做一半 = 行为上退回甲案（顶栏随页滚走）⇒ 这是**同一 bug 的延伸**，不是新决策。
   ⚠️ **`width:100vw` → `100%`（结构二次调整，2026-10-07）**：`100vw` 按规范**含竖向滚动条宽**
   ⇒ 本壳一出现竖向滚动条就横向溢出「一条滚动条宽」。`.home-container` 是同款、同笔同改（见下）；
   原文「留后续笔」已兑现，正文见 `docs/agent/slot-registry.md` §八（§一 commit 7c 是它的由来）。 */
min-height: 100vh;
display: flex;
flex-direction: column;
background: var(--bg);
}
/* ── 顶栏（C 笔 2026-10-07）────────────────────────────────────────────────────
   形态来源 = `docs/美化/pages/` 的**基准 6 份**，它们逐字相同：`ChatRoom:142-148` /
   `GoodsAdd:163-169` / `GoodsUp:155-161` / `GoodsSelect:258-264` /
   `OrderCenter-merchant:329-335` / `ShoppingCart-customer:296-302`。
   ⚠️ **用户裁决：不加** `UserCenter-customer.html:275-278` / `UserCenter-merchant.html:261-264`
      那个 520px 的 `.nav-search`（只有这两份稿有它 ⇒ `docs/美化/README.md` 的「十份同源、
      无搜索框」是**事实错误**，本笔已就地订正）。`ProductPage.html` 是变体且不进壳，
      `LoginPage.html` 无顶栏。
   ⚠️ 高 55 → 60、内距 40px → `var(--s7)`、下边线 `#eee` → `var(--line)`、
      底 `#fff` → `var(--surface)`（同值，只换成语义名）。
   ⚠️ **高度必须与 `.home-container` 的 `top` / `height` 锁步**（那两条已同步改成 60）。 */
.top-nav {
height: 60px;
/* ⚠️ **D 笔 commit 7b 丁案**（用户 2026-10-07 裁决，三案择「丁 = 甲 + sticky」）——
   修「顶栏被压扁」（见 `docs/agent/slot-registry.md` 同名小节）。
   `flex: none` 是**修 bug 必需**：`.app-shell` 是 `height:100vh` 的 flex column，
   `.top-nav` 原先只有 `height:60px`、`flex` 取默认 `0 1 auto`（`flex-shrink:1`），
   而 `.content`（`flex:1` + `min-height:auto` + 无滚动容器）被高子页顶高后，
   flex 会把顶栏压到 min-content（实测 60 → 36px，`.nav-left` 贴顶 y=0）。
   `sticky` 是**壳应有的行为**：甲案（只 `flex:none`）会让顶栏随页滚走 = 壳降级回
   「没有壳」的形态，而本壳的设计哲学是「壳稳定、内容切换」⇒ 加 `sticky` 让滚动时吸顶。
   ⚠️ **不采乙案**（`.content { min-height:0; overflow-y:auto }`）：嵌套滚动容器 +
   百分比高度 + `absolute` 参照物，回归面大，且滚动行为与稿不一致。
   ⚠️ `z-index:10` 高于 `.home-container` 的 `1`（**`:360`**，`position:relative; top:0` —— ⚠️ **裁决 ③（用户 2026-10-08，「一指错就是错」）：已改号** —— 本节原文是裸 `:293`，而 293 行现行内容是 `.nav-red-dot` 的 `background`（不是 `.home-container`）⇒ 改指到 `z-index:1` 的现行行 `:360`（块 `AppShell.vue:430-440`）；「`position:absolute; top:60px`」那半句同批被第 1 条改掉，值见同块）—— ⚠️ **I2 重定基（2026-10-09）**：本文件因 I2 三端前端插了 43 行，上面两个号随之后移（行号 `317`→`360`、块 `308-318`→`351-361`）——
   两者不重叠（`.home-container` 自 `top:60px` 起），改后仍不重叠（它现在从 `.content` 的 y=60 起算，仍不与顶栏的 0–60 带重叠），只保证滚动时顶栏在上。 */
flex: none;
position: sticky;
top: 0;
z-index: 10;
display: flex;
justify-content: space-between;
align-items: center;
padding: 0 var(--s7);
border-bottom: 1px solid var(--line);
background: var(--surface);
/* ⚠️ 本仓**没有**稿子那条全局 `body{font-family:…}`：`public/index.html` 与全仓 CSS 里
   一条字体声明都没有 ⇒ 今天走浏览器默认（本机实测解析到 `"Noto Sans SC"`）。
   用户裁决：本笔的字体**只声明在壳三件上**（顶栏 / 左菜单），页面主体留给 D 笔
   ⇒ 在这里声明一次，右三项与左项各自继承 / 覆盖。 */
font-family: var(--font);
}
/* ── 黄条（补丁 B2）────────────────────────────────────────────────────────────
   ⚠️ `sticky; top:60px` 与顶栏 `top:0` 是**配对**的：顶栏高 60px 上述那条「高度必须与
      `.home-container` 锁步」已把它钉死 ⇒ 黄条吸在它正下方，两层不重叠。
   ⚠️ `z-index:9` **必须小于顶栏的 10** —— 滚动时顶栏压住黄条（壳的层次：顶栏 > 提示 > 内容）。
   ⚠️ 配色走 `--warning-ground` / `--warning-deep`（本仓的「提醒」就是这对，别另起一个黄）。
      ⚠️ **用 `-deep` 而不是 `--warning`**：本壳是**商城**入口，只吃 `:root`（浅色）那套 —— 浅色下
      `--warning-deep` #604100 对 `--warning-ground` #f9efdf 是 **8.2:1**，而 `--warning` #966800 只有 **4.3:1**
      （15px 正文，不够）。⚠️ 暗色那套（`html[data-theme="admin"]`）下 `--warning-deep` 反而只有 1.4:1 ——
      但**本壳不在暗色作用域里**（暗色只有管理端 `AdminShell.vue` 会套，见 `tokens.css` 那条
      「作用域 = `html[data-theme=\"admin\"]`」）⇒ 不构成问题。**若哪天把本壳搬进暗色，这里要改回 `--warning`。** */
.nav-notice {
  flex: none;
  position: sticky;
  top: 60px;
  z-index: 9;
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) var(--s7);
  background: var(--warning-ground);
  border-bottom: 1px solid var(--line);
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--warning-deep);
}
.nav-notice-text {
  font-weight: var(--fw-med);
}
.nav-notice-go {
  color: inherit;
  text-decoration: underline;
  font-weight: var(--fw-semi);
}
.nav-notice-x {
  margin-left: auto;
  appearance: none;
  border: 0;
  background: none;
  cursor: pointer;
  padding: 0 var(--s2);
  font: inherit;
  font-size: var(--fs-lg);
  line-height: 1;
  color: inherit;
  opacity: 0.7;
}
.nav-notice-x:hover {
  opacity: 1;
}
/* 顶栏两族入口与稿子的一一对应：左 = 稿子的 `.brand`（文字「商城主页」，`nav.js` 的 `left`
   恒为这 1 项）；右 = 稿子的 `.nav-right`（3 项，逐字同构）。
   ⚠️ **不能照抄稿子那条全局 `a{color:inherit;text-decoration:none}`**：本仓没有它，而
      `.top-nav a` 的特异性 (0,1,1) 会**压过** `.brand` 的 (0,1,0) —— 破法是拆成两条**显式**
      规则，而不是提高 `.brand` 的特异性。
   ⚠️ 稿子的顶栏**没有 hover 态**（三态先例 `主页改造.html:562-568` 的 `.subnav a` 属
      **二级导航**，不属顶栏）⇒ 照稿不加；改前那条也没有 ⇒ **本项净零**。
   ⚠️ 左项落成 `.brand` 后**在首页不再高亮**（稿子的 `.brand` 没有 `.on` 变体）—— 这是照稿的
      必然结果。模板里那个 `:class="{ active: … }"` 原样保留，只是 `.nav-left a.active` 不再有规则。 */
.nav-left a {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  color: var(--brand-ink);
  text-decoration: none;
}
.nav-right {
margin-left: auto;
display: flex;
align-items: center;
gap: var(--s5);
color: var(--nav-tx); flex: none; white-space: nowrap;   /* ⚠️ 同上（用户 2026-10-08 第 2 条）：右三项（商家 = 消息/订单中心/用户中心）**不许缩、不许换行** */
}
/* R1/R2：顶栏入口由 `button` 改成 `router-link`（渲染成 `a`）⇒ 选择器随之改。
   ⚠️ C 笔：原 `.top-nav a` 那条**整条拆掉** —— 它的 `display:inline-block` / `border:none` /
      `background:transparent` / `font-size:15px` / `color:#333` / `cursor:pointer` /
      `padding:6px 12px` / `border-radius:4px` / `transition:0.2s` 是**按钮**的形态；
      稿子的顶栏入口是无内距、无圆角、无过渡的**文字链**（`router-link` 渲染成带 `href` 的
      `a`，指针形状浏览器自带，不需要 `cursor`）。高亮 `#007bff` → `--nav-now`、500 → `--fw-semi`。 */
.nav-right a {
color: inherit;
text-decoration: none;
}
.nav-right a.active {
color: var(--nav-now);
font-weight: var(--fw-semi);
}
.nav-left {
display: flex;
align-items: center; flex: none; white-space: nowrap;   /* ⚠️ **用户 2026-10-08 第 2 条（顶栏被盖）**：挤不下时 flex 默认**先压品牌与右三项** —— 中文可逐字换行 ⇒ 品牌被压成 2 行、右三项 2–4 行（`_dbg9` 实测：700 宽时品牌 y=−5..65、右三项高 48px；600 宽时 96px）⇒ 60px 顶栏的**内容溢到带外、压住页面**。修法 = 两头**不许缩、不许换行**，让位交给中间那个 `flex:1 1 auto` 的搜索框（本行里唯一该缩的东西）。 */
}
/* 未读红点：照稿的 `.dot`（同上那 6 处顶栏逐字相同）。
   ⚠️ 改前是 8px **纯红** + `vertical-align: middle`；现在 6px **朱砂 `--accent`** +
      `super`（位置上移到上标位）。两条都是用户裁决项 —— 色那一项连带把 `tokens.css` 的房规
      「朱砂只负责价格」订正为「价格、促销与未读提示」。 */
.nav-red-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  background: var(--accent);
  border-radius: 50%;
  margin-left: 3px;
  vertical-align: super;
}
.content {
flex: 1;
padding: 0;
}
.page {
padding: 0;
}
/* ⚠️ C 笔：`top` / `height` 跟着顶栏 55 → 60 **锁步**（用户裁决：容器取保守口径）。
   只改这两个数。⚠️ **D 笔 commit 0（乙案）已取走 `background`**（`#f5f5f5` → `var(--bg)`），
   其中 `width:100vw` **已由结构二次调整（2026-10-07）改成 `100%`**（消横向溢出）；余两条**已由用户 2026-10-08 第 1 条兑现**（见下方 `position` / `min-height` 两条，`top:60px` → `0` 是随之必改的第三条）。⚠️ 只改顶栏不改这里 = 首页被顶栏压掉 5px。 */
.home-container {
position: relative;   /* ⚠️ **用户 2026-10-08 第 1 条（顶栏 sticky）**：原 `absolute` ⇒ 本块**不参与流**、不撑高 `.app-shell` */
top: 0;               /*    ⇒ 壳恒 900px、`.top-nav` 的 sticky 约束盒只有 900 ⇒ 首页滚过 840px 顶栏就释放（实测滚 1041 时 `navY = −201`，用户所见「滚到底顶栏没了」）。改回流内；`top` 随之归零（流内它已在 `.content` 的 y=60 上，再留 60 会再压 60px）。 */
left: 0;
width: 100%;
min-height: calc(100vh - 60px);   /* ⚠️ 同行 `height` → `min-height`（同上）：定高只让内容**溢出**（`.home-page` 实高 1881）而壳不动；`min-height` 既保住「内容短时页脚贴底」，又让壳随内容长高（改后量测 shell 1941）⇒ sticky 全程有效。数值一个没动。 */
background: var(--bg);
padding: 0;
margin: 0;
z-index: 1;
}
/* ⚠️ `.cart-container`（客户购物车）与 `.order-container`（商家订单中心）**两个类名都保留**
   （R6 裁决 D7，不合并成一个）：商家那份多一行 `position: relative`，而 `OrderCenter.vue:200/:235`
   与 `ShoppingCart.vue:205/:240` 都有 `absolute` 子元素 ⇒ 合并 = **改行为**，归界面优化笔。
   两类名由路由记录的 `meta.layout` 选中。 */
.cart-container {
height: 100%;
background: #fff;
border: 1px solid var(--line);
overflow: hidden;
/* 原 `box-shadow: 0 2px 8px rgba(0,0,0,0.05);` 删（乙案 / 房规「禁·阴影铺满，改 1px 发丝线」） */
}
.order-container {
height: 100%;
background: #fff;
border: 1px solid var(--line);
/* 原 `box-shadow: 0 2px 8px rgba(0,0,0,0.05);` 删（同上：乙案 / 房规「禁·阴影铺满」） */
overflow: hidden;
position: relative;
}
/* ⚠️ C 笔：`gap` 30 → `var(--s8)`（=64px），照稿 `UserCenter-customer.html:141` 的
   `.uc{display:flex;align-items:flex-start;gap:var(--s8)}`。只改 `gap` —— `display:flex` /
   `height:100%` 不动（稿子多一个 `align-items:flex-start`，改了会让右栏不再撑满，
   归 D 笔）。左右两栏 = `UserCenter.vue` 那两个**根**（多根 fragment）⇒ 别在中间加包裹 div。
   ⚠️ **D 笔 commit 7 已取走 `align-items:flex-start`**（C 笔明文交接）——右栏子页
   （`UserInfoPage` / `HistoricalOrders` / `FriendRoom`，commit 7/8/9 三页共用）根节点是
   `<main class="uc-main">`，撑满高度会让「身份卡以下的区块」被拉到视口底。`height:100%` 保留。 */
/* ⚠️ **D 笔 commit 7b —— 补稿的 `.wrap` 居中容器**（用户 2026-10-07 裁决「做」；
   交接来源 `UserCenter.vue:81-82`「`.content` 的 1180 居中不在本笔（D 笔）」）。
   稿 `UserCenter-customer.html:136` / `UserCenter-merchant.html:132` 逐字相同：
   `.wrap{max-width:1140px;margin:0 auto;padding:var(--s7) var(--s5) var(--s8)}`，
   它包住的 `.uc`（`:141`）= 本壳的 `.user-page`（`UserCenter.vue` 的两个根
   `.uc-menu` + `main.uc-main` 直接落在这里）。
   ⚠️ **宽度取 1140（照本页这两份稿），不是交接话里说的 1180** —— 实测：本笔的**两份**稿是
   `docs/美化/pages/` 里**唯一**用 1140 的（其余 7 份 + 已落的 `GoodsSelect.vue:305` /
   `ShoppingCart.vue:446` 都是 1180）；按「以稿为唯一基线」照落。
   ⚠️ 只加居中三件 —— `display:flex` / `align-items:flex-start` / `gap` / `height:100%` **不动**。 */
.user-page {
display: flex;
align-items: flex-start;
gap: var(--s8);
height: 100%;
max-width: 1140px;
margin: 0 auto;
padding: var(--s7) var(--s5) var(--s8);
}
/* ⚠️ 用户中心左菜单的样式（`.user-menu*`）R1 起已随 markup 搬进 `UserCenter.vue` 自己的
   `<style scoped>` 里 —— 各文件的样式各归其主。`.user-page` 留在壳里：它是**布局容器**
   （`meta.layout`），不是菜单自己的样式。
   ⚠️ R6 裁决 D8：原两壳里那条 `.empty-page`（`display:flex` 居中提示）**已随两壳删除一并终结** ——
   全仓 0 使用（R1 前是 `curPage` 认不出时的兜底，路由化后没有「认不出」这回事），属死规则。 */

/* ── 顶栏搜索框（用户 2026-10-08 第 1 条；**尺寸/颜色** = 同日第 2 条的回退）──────────
   ▸ **摆法**（容器怎么放在顶栏里）来源 = `ProductPage.vue:797-805` 已有顶栏那一组
     （用户：「参考商品详情页的顶栏结构，那个是对的」）—— 只把最外层容器名改成
     `.nav-search`（`.search-wrapper` 在本仓已有两处**页内**同名，壳里另起一名免得混）。
   ▸ **尺寸与颜色**来源 = 本批搬走的那条**页内搜索条**的原值（`HomePage.vue` 原
     `:391-439` 的 4 条规则，删后原文存档在该文件的按语块里）⇒ 40px 白底整条容器 +
     `--line` 发丝线 + `--r2` 圆角，内嵌 32px `--brand` 实底、白字按钮。
     ⚠️ 取**页内**那条而**不是** `ProductPage` 那条：用户口径是「回到**改动前**的尺寸和
     颜色」—— 改动前用户在首页看到的就是页内那条（`ProductPage` 那条只做**结构**参照）。
   ⚠️ `flex: 1 1 auto` + `max-width: 420px` + `margin: 0 var(--s5)` 是**与 `.nav-right` 的
      `margin-left: auto` 配对**的：搜索框先吃满 free space（上限 420），余下的被那条 auto
      margin 全吃走 ⇒ 右三项仍贴右（`.top-nav` 的 `justify-content: space-between` 此时
      不起作用：自动外边距优先于它）。
   ⚠️ 落点是 `<form>` 的 `@submit.prevent`（写在模板那行上）：`Enter` 走表单隐式提交、
      `搜索` 是 `type="submit"` ⇒ 两条入口**同一个处理**，不新造键盘判据。
   ⚠️ 匿名态同样渲染（`ANON_RIGHT` 只换右三项）：搜索页是公开面，见模板那块注释。
   ⚠️ 左内距 14px 是页内那条**给放大镜图标让位**留下的（壳里没搬图标 ⇒ 现只当左侧留白）。
   ⚠️ `.search-input` / `.search-btn` 两条**一格不写字体**：改动前那两条也没写（本仓没有全局
      `input` / `button{font:…}`）⇒ 输入框走浏览器默认、按钮由下面那条 `font-size:13px` 定。
      这是「逐字回退」的必然结果，**不是**漏了 token；要不要并进 `var(--font)` / `--fs-*` 属
      另议（登记于台账本批条）。 */
.nav-search {
  flex: 1 1 auto;
  min-width: 88px;   /* ⚠️ 同上（用户 2026-10-08 第 2 条）：`min-width:auto` = 输入框的默认固有宽（≈170px，`size` 默认 20 字符）⇒ 顶栏挤到 640 以下时**本框不肯再缩**、改由整条顶栏溢出视口（`_dbg9` 实测 640 溢 25 → 400 溢 265）。但**不能补 `0`**：`0` 允许盒子缩到**比自身内容还窄**，盒内那枚「搜索」按钮就会**溢出到盒外压住右侧「消息」**（`_dbg9` 实测 520 宽时按钮右沿越出表单 32px、与「消息」交叠 8px；480/400 时交叠 32px —— 那正是用户说的「盖过」的极端形态）。88px = 表单内边距 18 + 按钮 66 + 一点输入框宽；代价是视口 <约 535px 时改为整条顶栏横向溢出（见本文件 :326 那条「不许缩、不许换行」的口径，此宽度超出页面设计范围）。⚠️ **I2（2026-10-09）订正**：原文写 :226 —— 那是个**陈旧错址**（按 I2 的 +43 重定基也只是挪到 :269，那儿是 `.home-container` 的 `z-index` 注释，与不可缩无关）；口径正文实测在 :326（`AppShell.vue` 的 `.nav-right` 那条「修法 = 两头不许缩、不许换行」），同款另有一句在 :308。 */
  max-width: 420px;
  margin: 0 var(--s5);
  display: flex;
  align-items: center;
  gap: var(--s2);
  height: 40px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: 0 4px 0 14px;
  transition: border-color 0.15s, background 0.15s;
}
.nav-search:focus-within { border-color: var(--brand); background: var(--surface); }
.search-input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; }
.search-input::placeholder { color: var(--ink-3); }
.search-btn {
  flex: none;
  height: 32px;
  padding: 0 18px;
  border-radius: var(--r1);
  background: var(--brand);
  color: #fff;
  font-size: 13px;
  font-weight: var(--fw-med);
  transition: background 0.15s;
}
.search-btn:hover { background: var(--brand-hover); }
</style>
