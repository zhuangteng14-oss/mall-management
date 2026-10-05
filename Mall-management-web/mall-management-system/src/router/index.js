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
// ⚠️ R2（2026-10-05）：`HomeTab` 由 `views/customer/` **上移到 `views/`** —— 它适配的
//    `HomePage` 本来就是**两壳共用**的，R2 起它自己也被两个壳共用（用户裁决 4）。
import HomeTab from '../views/HomeTab.vue'
import UserCenter from '../views/UserCenter.vue'
import ChatRoom from '../views/ChatRoom.vue'
import UserInfoPage from '../views/UserInfoPage.vue'
import HistoricalOrders from '../views/HistoricalOrders.vue'
import FriendRoom from '../views/FriendRoom.vue'
// ── R2（2026-10-05）新增：商家壳专属的子页面组件 ────────────────────────────────
import OrderPane from '../views/merchant/OrderPane.vue'
import GoodsAdd from '../views/GoodsAdd.vue'
import GoodsUp from '../views/GoodsUp.vue'

// ── R1（2026-10-05）：`/customer` 由**一条平级记录**改为**父记录 + 子路由** ───────────
// 5 个 tab 各自一条子路由，`CustomerPage` 只留顶栏 + `<router-view>`（壳/页面分层）。
// 于是 tab 状态**就是 URL**：可深链、可刷新、可后退、可分享。
//
// ── R2（2026-10-05）：`/merchant` 照同一模板改造（用户裁决：镜像 R1）───────────────
// 6 条子路由（home / search / msg / order / order/cart / user），`MerchantPage` 只留顶栏 + `<router-view>`。
// ⚠️ 两壳的顶栏**唯一**差异 = 右起第 2 项（客户「购物车」⇄ 商家「订单中心」）—— 顶栏项已抽到
//    `src/config/nav.js`，为 R6 抽 `AppShell` 铺路（用户裁决 6）。
//
// ⚠️ `meta` 各键的分工（**壳与页都读它**，别随手删）：
//   `tab`      —— 顶栏高亮（壳里的 `:class="{ active: route.meta.tab === item.tab }"`）。
//                 ⚠️ 用 `meta.tab` 而**不是** `route.path.startsWith(...)` 之类：
//                 父路径前缀会让「商城主页」在所有子路由上都高亮。
//                 搜索页**故意不设** `tab` ⇒ 停在搜索时所有按钮都不高亮（与路由化前一致）。
//   `layout`   —— 内容区外壳类名（壳里的 `layoutClass`），与路由化前那些兄弟 div
//                 的类名一一对应 ⇒ 布局逐字不变。子记录不写则**继承**父记录的（meta 合并）。
//   `sub`      —— 「用户中心」左菜单高亮（`UserCenter.vue`）。
//   `subMenu`  —— 「用户中心」左菜单**表**（R2 起；客户 4 项 / 商家 6 项）——
//                 `UserCenter.vue` 由它 `v-for` 渲染 ⇒ 两壳共用同一组件（用户裁决 3）。
//   `logout`   —— 退出登录的**分支键**（R2 起）：`'merchant'` ⇒ 另加 `clearMerchantId()`；
//                 缺省（客户侧）⇒ 只 `clearCredentials()`。见 `UserCenter.vue` 的 `toLogin`。
//   `orderTab` —— **仅**商家「订单中心」两条记录用：`OrderPane.vue` 靠它决定渲染哪一面
//                 （`/merchant/order` = `'order'`、`/merchant/order/cart` = `'cart'`）。
// ⚠️ `path: ''` 与 `path: 'info'` 两个记录指**同一个组件**（`UserInfoPage`）、同一个 `sub`：
//    `/…/user` 是「用户中心默认页」的短地址，`/…/user/info` 是显式地址。
//    不设 `redirect` 是为了不依赖「有 children 的记录能不能同时挂 redirect」这一细节。两壳同法。
const routes = [
  { path: '/', component: HomePage },
  { path: '/login', component: LoginPage },
  {
    path: '/customer',
    component: CustomerPage,
    children: [
      { path: '', component: HomeTab, props: { base: '/customer' }, meta: { tab: 'home', layout: 'home-container' } },
      { path: 'search', component: GoodsSelect, meta: { layout: 'page' } },
      { path: 'msg', component: ChatRoom, meta: { tab: 'msg', layout: 'page' } },
      { path: 'cart', component: ShoppingCart, props: { showSwitch: false }, meta: { tab: 'cart', layout: 'cart-container' } },   // ← ⚠️ **必须**传 false：R1 前客户壳是 `<ShoppingCart :showSwitch="false" />`，R1 落码时丢了 ⇒ 该页凭空多出 📋/🛒 开关（且无人监听 = 死控件）。`fix/legacy-chat-tab` 修（见 progress.md §5-X (16)2）
      {
        path: 'user',
        component: UserCenter,
        meta: {
          tab: 'user',
          layout: 'user-page',
          subMenu: [
            { label: '个人信息', sub: 'info', to: '/customer/user/info' },
            { label: '历史购买记录', sub: 'history', to: '/customer/user/history' },
            { label: '好友', sub: 'friend', to: '/customer/user/friend' },
            { label: '消息', sub: 'chat', to: '/customer/user/chat' }
          ]
        },
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
  {
    path: '/merchant',
    component: MerchantPage,
    children: [
      { path: '', component: HomeTab, props: { base: '/merchant' }, meta: { tab: 'home', layout: 'home-container' } },
      { path: 'search', component: GoodsSelect, meta: { layout: 'page' } },
      { path: 'msg', component: ChatRoom, meta: { tab: 'msg', layout: 'page' } },
      // ⚠️ 两条 order 记录都设 `meta.tab = 'order'` ⇒ 顶栏「订单中心」**两面都亮**（与路由化前
      //    `curPage === 'order'` 的判据一致）；`meta.orderTab` 才是 `OrderPane` 的分面依据。
      // ⚠️ `order/cart` 是**单条多段子路径**，不嵌第二层 `children`（`OrderPane` 自己渲染两面）。
      { path: 'order', component: OrderPane, meta: { tab: 'order', layout: 'order-container', orderTab: 'order' } },
      { path: 'order/cart', component: OrderPane, meta: { tab: 'order', layout: 'order-container', orderTab: 'cart' } },
      {
        path: 'user',
        component: UserCenter,
        meta: {
          tab: 'user',
          layout: 'user-page',
          logout: 'merchant',   // ⚠️ 商家侧退出多清 `merchantid`；客户侧无此键（见 UserCenter.vue 的 toLogin）
          subMenu: [
            { label: '个人信息', sub: 'info', to: '/merchant/user/info' },
            { label: '上架商品', sub: 'add', to: '/merchant/user/add' },
            { label: '查看商品', sub: 'list', to: '/merchant/user/list' },
            { label: '历史购买记录', sub: 'history', to: '/merchant/user/history' },
            { label: '好友', sub: 'friend', to: '/merchant/user/friend' },
            { label: '消息', sub: 'chat', to: '/merchant/user/chat' }
          ]
        },
        children: [
          { path: '', component: UserInfoPage, meta: { sub: 'info' } },
          { path: 'info', component: UserInfoPage, meta: { sub: 'info' } },
          { path: 'add', component: GoodsAdd, meta: { sub: 'add' } },
          { path: 'list', component: GoodsUp, meta: { sub: 'list' } },
          { path: 'history', component: HistoricalOrders, meta: { sub: 'history' } },
          { path: 'friend', component: FriendRoom, meta: { sub: 'friend' } },
          { path: 'chat', component: ChatRoom, meta: { sub: 'chat' } }
        ]
      }
    ]
  },
  { path: '/product', component: ProductPage },
  { path: '/shoppingCart', component: ShoppingCart },
  // 👇 新增搜索页面路由
  { path: '/goodsSelect', component: GoodsSelect }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// ── 兼容重定向（R1 起；R2 合表，2026-10-05）：旧 `?page=` / `?back=search` 深层链接 ⇒ 新子路由 ──
// 旧形态：`#/customer?page=cart|user|chat`（R1 前由 `CustomerPage.vue` 的 `onMounted` + 两个
//   `route.query` watcher 解读）、`#/merchant?page=order|user|chat`（R2 前同理，在 `MerchantPage.vue`）、
//   `#/…?back=search`（`ProductPage.vue:388` 的「返回」推的）。两壳同一套形状 ⇒ **一张表驱动**。
// ⚠️ **这些映射都不能省**，两条理由：
//   ① R5（商品详情页进壳）**不做** ⇒ `ProductPage.vue:361` / `:363` / `:388` / `:398` / `:400`
//      仍推旧 URL（商家的 3 处 + 客户的 2 处，见 `progress.md` §5-Y 的生产者表）；
//   ② 书签 / 浏览器历史 / 手敲 URL 本来就不受代码控制。
// ⚠️ **R1 时这里写的第 ② 条理由「`MerchantPage.vue` R2 才改」已随 R2 落地而消失** ——
//    商家侧的生产者是 `ProductPage`（理由 ①），不是壳自己了。
// 取舍：认出的 `page` 换成路径并**带走其余 query**（`otherId` / `otherType` / `_t` 是 `ChatRoom.vue`
//   读的会话参数，丢了就打不开对应聊天）；⚠️ **客户侧的 `chat` 与 `msg` 两个键都要收** —— `chat`
//   才是**生产者实际推的**键（`FriendRoom.vue:246`、`ProductPage.vue:447`），`msg` 是 R1 前的历史键
//   （旧 watcher 认它、且手敲 URL 也算）；R1 落码时误写成只收 `msg` ⇒ 「咨询商品 / 好友点聊天」
//   两条客户流程落回主页并丢会话参数，`fix/legacy-chat-tab` 加回（见 progress.md §5-X (16)1）。
//   认不出的 `page` 归位该壳的首页（顺带丢掉脏参数）；
//   `back=search` 换成搜索页**且保留 `back`**（`GoodsSelect.vue:158` 仍靠它触发 `loadState()`）。
// ⚠️ **商家表不带 `cart` / `msg`**：商家侧 `ShoppingCart` 只由 `OrderPane` 的页内开关进入、
//    `msg` 全仓无生产者 —— 收进来等于**凭空造一个旧约定**。客户表收到 `msg` 是因为它**历史上真有**
//    （R1 前的 watcher 认它）。两表的差异是有意的，别「对齐」掉。
const LEGACY_TAB = {
  '/customer': { cart: '/customer/cart', user: '/customer/user', msg: '/customer/msg', chat: '/customer/msg' },   // ⚠️ `chat` 与 `msg` 同指消息页、**都要在**（R1 漏 `chat`，`fix/legacy-chat-tab` 加回）
  '/merchant': { order: '/merchant/order', user: '/merchant/user', chat: '/merchant/msg' }
}

router.beforeEach((to) => {
  const table = LEGACY_TAB[to.path]
  if (!table) return true                  // 只认**精确**的 `/customer` 与 `/merchant`；子路由一律放行
  const { page, ...rest } = to.query
  if (page) {
    const target = table[page]
    return target ? { path: target, query: rest } : { path: to.path }   // 认不出 ⇒ 归位该壳首页
  }
  if (to.query.back === 'search') return { path: `${to.path}/search`, query: to.query }
  return true                              // 无 `page`、无 `back` ⇒ 原样（`/…` 本就是首页）
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
// ⚠️ 「仅首次」不需要额外标志位：全仓**没有任何代码 `router.push('/')`**（两壳顶栏「商城主页」自
//    R1/R2 起都是指向自己壳主页的 `<router-link>` —— **是路由跳转，但目标不是 `/`**）
//    ⇒ 本守卫实际只在「应用入口」那一次 + 「手敲 `#/`」时触发。手敲也被送回 = 有意的（那正是卡死态）。
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
