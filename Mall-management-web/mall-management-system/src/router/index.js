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

const routes = [
  { path: '/', component: HomePage },
  { path: '/login', component: LoginPage },
  { path: '/customer', component: CustomerPage },
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
// ⚠️ 「仅首次」不需要额外标志位：全仓**没有任何代码 `router.push('/')`**（顶栏「商城主页」是
//    `CustomerPage.vue:5` 的 `curPage='home'` 内部状态、**不是路由**）⇒ 本守卫实际只在
//    「应用入口」那一次 + 「手敲 `#/`」时触发。手敲也被送回 = 有意的（那正是卡死态）。
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