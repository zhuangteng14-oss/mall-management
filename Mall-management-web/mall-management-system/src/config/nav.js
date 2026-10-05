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
// ⚠️ 两壳顶栏**唯一**差异 = 右起第 2 项（客户「购物车」⇄ 商家「订单中心」），其余逐字同构
//    ⇒ R6 抽 `AppShell` 时靠这份配置把两个壳合并成一个渲染 nav 的壳（见 progress.md §5-Y）。
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
