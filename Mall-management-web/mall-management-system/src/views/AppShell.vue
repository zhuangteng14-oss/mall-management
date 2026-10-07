<template>
  <div class="app-shell">
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

// 进入消息页则立即清红点（路由化后改看路由 —— 壳里已无 `curPage`）。
// ⚠️ 判据与路由化前 `curPage === 'msg'` **完全一致**：只认**壳的**消息页（`/customer/msg` / `/merchant/msg`），
//    用户中心里的「消息」子页（`/…/user/chat`）**不清**。
const MSG_PATH = `${props.base}/msg`
watch(() => route.path, (p) => {
  if (p === MSG_PATH) unreadCount.value = 0
})

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
  // 启动轮询获取未读消息数
  await fetchUnreadCount()
  unreadPollingTimer = setInterval(fetchUnreadCount, 5000)
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
width: 100vw;
height: 100vh;
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
color: var(--nav-tx);
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
align-items: center;
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
   余下 `position:absolute` / `width:100vw` / `padding:0` 留后续笔。⚠️ 只改顶栏不改这里 = 首页被顶栏压掉 5px。 */
.home-container {
position: absolute;
top: 60px;
left: 0;
width: 100vw;
height: calc(100vh - 60px);
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
.user-page {
display: flex;
align-items: flex-start;
gap: var(--s8);
height: 100%;
}
/* ⚠️ 用户中心左菜单的样式（`.user-menu*`）R1 起已随 markup 搬进 `UserCenter.vue` 自己的
   `<style scoped>` 里 —— 各文件的样式各归其主。`.user-page` 留在壳里：它是**布局容器**
   （`meta.layout`），不是菜单自己的样式。
   ⚠️ R6 裁决 D8：原两壳里那条 `.empty-page`（`display:flex` 居中提示）**已随两壳删除一并终结** ——
   全仓 0 使用（R1 前是 `curPage` 认不出时的兜底，路由化后没有「认不出」这回事），属死规则。 */
</style>
