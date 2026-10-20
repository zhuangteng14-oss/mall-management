// ── 两壳顶栏配置（R2，2026-10-05；A 版一比一顶栏重写，2026-10-19）──────────────
//
// 形态：`{ right: [...] }`，项 = `{ label, tab, to, query?, badge?, account? }`。
//   `label`   —— 显示文字（`account: true` 的那一项由 `AppShell` 追加「 / <用户名>」）。
//   `tab`     —— 高亮判据：与路由记录的 `meta.tab` 比。
//   `to`      —— `<router-link>` 目标。
//   `query`   —— 可选，拼到 `to` 上（「通知」固定进消息中心的 `?tab=notify`）。
//   `badge`   —— 未读红点的数据源选择器；`'notify'` = 只认系统通知未读数。
//   `account` —— 「用户中心 / 用户名」那一项。
//
// ⚠️ **公域与私域共用同一张表**（用户 2026-10-19 命令一之 5）：两壳只差 `base` 与购物车
//    落点（客户 `/customer/cart`、商家 `/merchant/order/cart`）。左侧不再有导航项 ——
//    A 版顶栏左侧是黑色的 MALL 字标（`AppShell.vue` 的 `.top-mark`），不来自本表。
//
// ⚠️ **匿名态只留「登录 / 注册」**：全仓没有 `#/register` 路由（注册是 `LoginPage.vue`
//    内的滑动表单）⇒ 不拆成两条指向同一地址的假承诺。
const buyerRight = (base) => [
  { label: '首页', tab: 'home', to: base },
  { label: '通知', tab: 'msg', to: `${base}/msg`, query: { tab: 'notify' }, badge: 'notify' },
  { label: '购物车', tab: 'cart', to: `${base}/cart` },
  { label: '用户中心', tab: 'user', to: `${base}/user`, account: true }
]

const merchantRight = (base) => [
  { label: '首页', tab: 'home', to: base },
  { label: '通知', tab: 'msg', to: `${base}/msg`, query: { tab: 'notify' }, badge: 'notify' },
  { label: '购物车', tab: 'cart', to: `${base}/order/cart` },
  { label: '用户中心', tab: 'user', to: `${base}/user`, account: true }
]

export const CUSTOMER_NAV = { left: [], right: buyerRight('/customer') }
export const MERCHANT_NAV = { left: [], right: merchantRight('/merchant') }

const ANON_RIGHT = [{ label: '登录 / 注册', tab: 'login', to: '/login' }]

/** 顶栏表 —— 全仓唯一的登录态分支点（两壳共用）。
 *
 *  ⚠️ `isLogin` 必须在调用方的 `setup` 里算一次：`isLoggedIn()` 不是响应式
 *     （读 `auth.js` 的模块态 + `sessionStorage`）⇒ 写成 `computed(() => isLoggedIn())`
 *     永远不更新。壳每次进出都会重建，登录 / 退出 / 401 后必然重算，够用。
 */
export function navFor(table, isLogin) {
  return isLogin ? table : { left: table.left, right: ANON_RIGHT }
}
