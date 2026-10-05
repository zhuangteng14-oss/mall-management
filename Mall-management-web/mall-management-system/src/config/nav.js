// ── 两壳顶栏配置（R2，2026-10-05）—— 顶栏的**单一事实源**，为 R6 抽 `AppShell` 铺路 ──────────
//
// 形态：`{ left: [...], right: [...] }`，项 = `{ label, tab, to, badge? }`
//   `label` —— 显示文字。
//   `tab`   —— **高亮判据**：与路由记录的 `meta.tab` 比（`route.meta.tab === item.tab`）。
//              ⚠️ 别改成 `route.path.startsWith(item.to)` 之类：父路径前缀会让「商城主页」
//              在**所有**子路由上都高亮（R1 已在 `router/index.js` 的注释里记过同一条）。
//              搜索页**故意不设** `meta.tab` ⇒ 停在搜索时所有项都不高亮（与两壳路由化前一致）。
//   `to`    —— `<router-link>` 的目标。
//   `badge` —— 可选；`'unread'` = 该项右侧显示未读红点（两壳都挂在「消息」项）。
//
// ⚠️ 两套顶栏表（`CUSTOMER_NAV` / `MERCHANT_NAV`）的差异**只有两处**：左项「商城主页」的 `to`（各指自己的壳 —— 客户 `/customer`、
//    商家 `/merchant`），以及右起第 2 项（客户「购物车」⇄ 商家「订单中心」）——
//    其余（项数 / 文案 / `tab` / 红点）逐字同构
//    ⇒ **R6（2026-10-05）已落地**：`AppShell.vue` 靠这份配置把两个壳合并成一个渲染 nav 的壳 —— 本文件经路由记录的 `props.nav` 传入（见 progress.md §5-AB；R2 当初的预案见 §5-Y）。
//    ⚠️ 本条 R3 订正：原文写「**唯一**差异 = 右起第 2 项」，漏了左项的 `to` 也差（同句在
//    `router/index.js:30` 与 `AppShell.vue:61-63`（R6 前在 `CustomerPage.vue:70`，该文件已随两壳合一删除）各有一处，已一并订正（⚠️ 地址 R4 订正：原写 `:31`，实测在 `:30` —— `:31` 是 R3 的标题行，见 §5-AA (11)D8）。
export const CUSTOMER_NAV = {
  left: [
    { label: '商城主页', tab: 'home', to: '/customer' }
  ],
  right: [
    { label: '消息', tab: 'msg', to: '/customer/msg', badge: 'unread' },
    { label: '购物车', tab: 'cart', to: '/customer/cart' },
    { label: '用户中心', tab: 'user', to: '/customer/user' }
  ]
}

export const MERCHANT_NAV = {
  left: [
    { label: '商城主页', tab: 'home', to: '/merchant' }
  ],
  right: [
    { label: '消息', tab: 'msg', to: '/merchant/msg', badge: 'unread' },
    { label: '订单中心', tab: 'order', to: '/merchant/order' },
    { label: '用户中心', tab: 'user', to: '/merchant/user' }
  ]
}

// ── 未登录形态（R3，2026-10-05）────────────────────────────────────────────────────
// 未登录访客**可以进壳**（R3 起 `/` 也落到客户壳，见 `router/index.js` 的 `landingFor`）——
// 但顶栏右三项（消息 / 购物车|订单中心 / 用户中心）**每一项都以登录态为前提**：点进去不是
// 挂载即 401 被踢回 `/login`，就是拿到空列表。⇒ 未登录时把右三项**整体换掉**，只留「登录」。
//
// ⚠️ **只放「登录」，不放「注册」**：全仓**没有** `#/register` 路由 —— 注册是 `LoginPage.vue`
//    内的滑动表单（`isSlide`），凭空造一条路由 = 凭空造约定（同 `LEGACY_TAB` 两表不收
//    无生产者的键那条口径）。
// ⚠️ **左项原样保留**：每个壳的「商城主页」仍指自己的壳（客户 `/customer` / 商家 `/merchant`）
//    —— 匿名手敲 `#/merchant` 的人，看到的不该是「商城主页 → 客户壳」这种错指。
// ⚠️ 顶栏**只换表、不换壳**：壳其余部分（内容区 / `layoutClass` / 5s 轮询）对匿名访客本来就是
//    安全的（`AppShell.vue` 的 `getCurrentUser()` 无 cid/mid ⇒ 返回 `null` ⇒ 轮询空转；R6 前该函数在两壳里），
//    本笔**不新增**任何守卫、也不给壳加「未登录分支渲染」。
const ANON_RIGHT = [{ label: '登录', tab: 'login', to: '/login' }]

/** 顶栏表 —— **全仓唯一的登录态分支点**（两壳共用；R6 抽 `AppShell` 时一并带走）。
 *
 *  ⚠️ **`isLogin` 必须在调用方的 `setup` 里算一次**：`isLoggedIn()` **不是响应式**（读 `auth.js`
 *     的模块态 + `sessionStorage`）⇒ 写成 `computed(() => isLoggedIn())` **看起来更「正确」，
 *     实际永远不更新**（`computed` 只认得响应式依赖，`sessionStorage` 不是）。
 *     两壳也**不需要**它更新：会改变登录态的路径只有四条 —— 登录后 `push` 进壳、退出 `push('/login')`、
 *     401 出口 `push('/login')`、`/login` ⇄ 壳 —— **每一条都会重建壳组件**（壳不在任何
 *     `<KeepAlive>` 的 `include` 里，只有 `HomeTab` / `GoodsSelect` 被缓存）⇒ 重算自然发生。
 *
 *  ⚠️ `tab: 'login'` **不会点亮任何东西**：壳里的高亮判据是 `route.meta.tab === item.tab`，
 *     而 `route.meta.tab` 永远不是 `'login'`（`/login` 不是任何壳的子路由）⇒ 这个按钮是中性入口。
 */
export function navFor(table, isLogin) {
  return isLogin ? table : { left: table.left, right: ANON_RIGHT }
}
