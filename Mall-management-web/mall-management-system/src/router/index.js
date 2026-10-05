import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn, getRole, getMerchantId } from '../api/auth'
import HomePage from '../views/HomePage.vue'
import LoginPage from '../views/LoginPage.vue'
import CustomerPage from '../views/CustomerPage.vue'
import MerchantPage from '../views/MerchantPage.vue'
import ProductPage from '../views/ProductPage.vue'
import ShoppingCart from '../views/ShoppingCart.vue'
// 👇 引入你新的搜索页面
import GoodsSelect from '../views/GoodsSelect.vue'
// ── R1（2026-10-05）新增：客户壳的子页面组件 ────────────────────────────────────
import HomeTab from '../views/customer/HomeTab.vue'
import UserCenter from '../views/UserCenter.vue'
import ChatRoom from '../views/ChatRoom.vue'
import UserInfoPage from '../views/UserInfoPage.vue'
import HistoricalOrders from '../views/HistoricalOrders.vue'
import FriendRoom from '../views/FriendRoom.vue'

// ── R1（2026-10-05）：`/customer` 由**一条平级记录**改为**父记录 + 子路由** ───────────
// 5 个 tab 各自一条子路由，`CustomerPage` 只留顶栏 + `<router-view>`（壳/页面分层）。
// 于是 tab 状态**就是 URL**：可深链、可刷新、可后退、可分享。
//
// ⚠️ `meta` 三键的分工（**壳与页都读它**，别随手删）：
//   `tab`    —— 顶栏高亮（`CustomerPage.vue` 的 `:class="{ active: route.meta.tab === '…' }"`）。
//               ⚠️ 用 `meta.tab` 而**不是** `route.path.startsWith('/customer')` 之类：
//               父路径前缀会让「商城主页」在所有子路由上都高亮。
//               搜索页**故意不设** `tab` ⇒ 停在搜索时四个按钮都不高亮（与 R1 前一致）。
//   `layout` —— 内容区外壳类名（`CustomerPage.vue` 的 `layoutClass`），与 R1 前那 5 个
//               兄弟 div 的类名一一对应 ⇒ 布局逐字不变。子记录不写则**继承**父记录的（meta 合并）。
//   `sub`    —— 「用户中心」左菜单高亮（`UserCenter.vue`）。
// ⚠️ `path: ''` 与 `path: 'info'` 两个记录指**同一个组件**（`UserInfoPage`）、同一个 `sub`：
//    `/customer/user` 是「用户中心默认页」的短地址，`/customer/user/info` 是显式地址。
//    不设 `redirect` 是为了不依赖「有 children 的记录能不能同时挂 redirect」这一细节。
const routes = [
  { path: '/', component: HomePage },
  { path: '/login', component: LoginPage },
  {
    path: '/customer',
    component: CustomerPage,
    children: [
      { path: '', component: HomeTab, meta: { tab: 'home', layout: 'home-container' } },
      { path: 'search', component: GoodsSelect, meta: { layout: 'page' } },
      { path: 'msg', component: ChatRoom, meta: { tab: 'msg', layout: 'page' } },
      { path: 'cart', component: ShoppingCart, meta: { tab: 'cart', layout: 'cart-container' } },
      {
        path: 'user',
        component: UserCenter,
        meta: { tab: 'user', layout: 'user-page' },
        children: [
          { path: '', component: UserInfoPage, meta: { sub: 'info' } },
          { path: 'info', component: UserInfoPage, meta: { sub: 'info' } },
          { path: 'history', component: HistoricalOrders, meta: { sub: 'history' } },
          { path: 'friend', component: FriendRoom, meta: { sub: 'friend' } },
          { path: 'chat', component: ChatRoom, meta: { sub: 'chat' } }
        ]
      }
    ]
  },
  { path: '/merchant', component: MerchantPage },
  { path: '/product', component: ProductPage },
  { path: '/shoppingCart', component: ShoppingCart },
  // 👇 新增搜索页面路由
  { path: '/goodsSelect', component: GoodsSelect }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// ── R1 兼容重定向（2026-10-05）：旧 `?page=` / `?back=search` 深层链接 ⇒ 新子路由 ──────────
// 旧形态：`#/customer?page=cart|user|chat`（R1 前由 `CustomerPage.vue` 的 `onMounted` + 两个
//   `route.query` watcher 解读）、`#/customer?back=search`（`ProductPage.vue:388` 的「返回」推的）。
// ⚠️ **这两条映射都不能省**，三条理由：
//   ① R5（商品详情页进壳）**不做** ⇒ `ProductPage.vue:363` / `:388` / `:400` 仍推旧 URL；
//   ② `MerchantPage.vue` R2 才改（它推 `/merchant?page=order` —— 本守卫**不认 `/merchant`**）；
//   ③ 书签 / 浏览器历史 / 手敲 URL 本来就不受代码控制。
// 取舍：认出的 `page` 换成路径并**带走其余 query**（`otherId` / `otherType` / `_t` 是 `ChatRoom.vue`
//   读的会话参数，丢了就打不开对应聊天）；认不出的 `page` 归位首页（顺带丢掉脏参数）；
//   `back=search` 换成搜索页**且保留 `back`**（`GoodsSelect.vue:158` 仍靠它触发 `loadState()`）。
const LEGACY_TAB = { cart: '/customer/cart', user: '/customer/user', msg: '/customer/msg' }

router.beforeEach((to) => {
  if (to.path !== '/customer') return true   // 只认**精确** `/customer`；子路由与 `/merchant*` 全放行
  const { page, ...rest } = to.query
  if (page) {
    const target = LEGACY_TAB[page]
    return target ? { path: target, query: rest } : { path: '/customer' }
  }
  if (to.query.back === 'search') return { path: '/customer/search', query: to.query }
  return true                                // 无 `page`、无 `back` ⇒ 原样（`/customer` 本就是首页）
})

// ── 首页落点守卫（止血笔，2026-10-05）────────────────────────────────────────────
// 治的病：`#/` 裸挂 `HomePage`（**无外壳** ⇒ 无导航栏）。未登录时还有 `.login-hint-bar` 引导登录，
//   已登录时提示条不渲染、三条守卫也不触发 ⇒ **卡在裸首页**（会话恢复笔把这个状态从
//   「不可见」变成「可见」，见 progress.md §5-U / §5-V；未登录的整页锁已于 §5-W 改为提示条）。
//
// ⚠️ **时序耦合（本守卫正确性的唯一前提，别挪）**：本守卫读的是 `auth.js` 的**恢复态**，
//    `main.js` 的顺序是 `await restoreIdentity()` ⇒ `app.use(router)`（初始导航由此触发）。
//    本文件在 `main.js` 顶部被 import ⇒ 守卫在此**模块加载时**注册，必然早于 `app.use(router)`。
//    若将来有人把恢复挪到 `app.use(router)` **之后**，守卫会读到「匿名」⇒ 不重定向 ⇒
//    首页再度卡死（**不报错、不崩**，最难查）。改那行之前先看这段。
//
// ⚠️ 「仅首次」不需要额外标志位：全仓**没有任何代码 `router.push('/')`**（顶栏「商城主页」自
//    R1 起是 `CustomerPage.vue:5` 的 `<router-link to="/customer">` —— **是路由跳转，但目标是
//    `/customer`、不是 `/`**）⇒ 本守卫实际只在「应用入口」那一次 + 「手敲 `#/`」时触发。
//    手敲也被送回 = 有意的（那正是卡死态）。
//
// ⚠️ `ROLE_HOME` 是**唯一**的角色→落地页映射表。C3 加管理员：**只在本表加一行**
//    `'3': '/admin'`（另需后端把 `role` 加进 `/user/me` 投影 + 前端加 `#/admin` 路由，见 §5-V）。
const ROLE_HOME = { '1': '/customer', '2': '/merchant' }

router.beforeEach((to) => {
  if (to.path !== '/') return true      // 只守根路径，其余一律放行
  if (!isLoggedIn()) return true        // 未登录 ⇒ 停在公开落地页（锁引导保留，不回归）
  // 已登录：role 优先，`getMerchantId()` 兜底。
  // ⚠️ 兜底**不是**边角：新标签页恢复时 `getRole()` 拿不到（`/user/me` 不含 `role`）⇒ 走兜底是常态。
  //    兜底必须能区分商家（硬回 `/customer` 会把新标签页的商家送错页）。
  return ROLE_HOME[getRole()] || (getMerchantId() ? '/merchant' : '/customer')
})

export default router
