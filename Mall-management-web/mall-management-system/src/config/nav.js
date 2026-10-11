// ── 两壳顶栏配置（R2，2026-10-05）—— 顶栏的**单一事实源**，为 R6 抽 `AppShell` 铺路 ──────────
//
// 形态：`{ left: [...], right: [...] }`，项 = `{ label, tab, to, badge? }`
//   `label` —— 显示文字。
//   `tab`   —— **高亮判据**：与路由记录的 `meta.tab` 比（`route.meta.tab === item.tab`）。
//              ⚠️ 别改成 `route.path.startsWith(item.to)` 之类：父路径前缀会让「商城主页」
//              在**所有**子路由上都高亮（R1 已在 `router/index.js` 的注释里记过同一条）。
//              搜索页**故意不设** `meta.tab` ⇒ 停在搜索时所有项都不高亮（与两壳路由化前一致）。
//   `to`    —— `<router-link>` 的目标。
//   `badge` —— 可选；**未读红点的数据源选择器**（I2 起**曾**有两个取值，2026-10-09）：
//              `'unread'` = 未读**消息 + 系统通知**的合计（⚠️ 2026-10-09 两入口合并后只剩它 —— 见下条）；
//              `'notify'` **随「通知」项一并退役**（本文件已无用户，`AppShell.vue` 的 `dotCount` 也删了那支）。
//              它是键，不是显示开关 —— 真正判「要不要画点」的是 `AppShell.vue` 的 `dotCount(badge)`；两壳各 **1 项**。
//
// ⚠️ 两套顶栏表（`CUSTOMER_NAV` / `MERCHANT_NAV`）的差异**只有两处 + 商家的多两项**（「多两项」的前一项为 C3 块 4 新增、后一项为可视化段 commit 2 新增）：左项「商城主页」的 `to`（各指自己的壳 —— 客户 `/customer`、
//    商家 `/merchant`），以及**商家侧多两项「订单中心」「经营数据」**（「订单中心」插在「消息」与「购物车」之间、「经营数据」**追加在末位** ⇒ 商家右 5 项 / 客户右 3 项）——
//    其余（文案 / `tab` / 红点）逐字同构 —— ⚠️ 所以「右起第 2 项」两侧**都是**「购物车」（本条块 4 前的写法把它当成了差异项之一）
//    ⇒ **R6（2026-10-05）已落地**：`AppShell.vue` 靠这份配置把两个壳合并成一个渲染 nav 的壳 —— 本文件经路由记录的 `props.nav` 传入（见 progress.md §5-AB；R2 当初的预案见 §5-Y）。
//    ⚠️ 本条 R3 订正：原文写「**唯一**差异 = 右起第 2 项」，漏了左项的 `to` 也差（同句在
//    `router/index.js:30` 与 `AppShell.vue:76-78`（R6 前在 `CustomerPage.vue:70`，该文件已随两壳合一删除）各有一处，已一并订正（⚠️ 地址 R4 订正：原写 `:31`，实测在 `:30` —— `:31` 是 R3 的标题行，见 §5-AA (11)D8）。
export const CUSTOMER_NAV = {
  left: [
    { label: '商城主页', tab: 'home', to: '/customer' }
  ],
  right: [
    { label: '消息', tab: 'msg', to: '/customer/msg', badge: 'unread' },
    // ⚠️ I2（2026-10-09）：**系统通知**在顶栏**曾是**独立一项 —— `{ label:'通知', tab:'notify',
    //    to:'/customer/notify', badge:'notify' }`，当笔的口径是「通知 vs 消息入口必须分开」。
    //    ⚠️ **同日晚些时候该口径更新：两个入口合并**（用户裁决）⇒ 上面那项**已删**，本 4 行是**墓碑**、不是空白
    //    （刻意**净零行数**：本文件被按行号引 **17 处**，本行**之后**的 **7 处**（`:30` / `:31` / `:32-53` / `:61` / `:63`×2）删一行即集体漂移）。
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
    // ⚠️ I2（2026-10-09）：同上（客户侧那条墓碑说明的理由逐字适用）—— 商家端的「通知」项**已删**
    //    （入口合并进「消息」的内页 tab）；⚠️ 同款**净零行数**（本行**之后**仍有行号引用 `:32-53` / `:61` / `:63`×2 ⇒ 删行即漂移）。
    //    ⚠️ 两条旧 URL（`/customer/notify` / `/merchant/notify`）**没死**：`router/index.js` 改成 redirect 落到 `…/msg?tab=notify`。
    { label: '订单中心', tab: 'order', to: '/merchant/order' },{ label: '购物车', tab: 'cart', to: '/merchant/order/cart' },   // ⚠️ C3 块 4〈订单块〉：**同行**追加（净零行数，理由同 `:29` 那条墓碑）—— 「购物车块」（`/merchant/order/cart`）在块 4 之前**没有顶栏入口**，只能靠订单中心页里那枚开关进；开关停用后这是唯一的门。`tab: 'cart'` 与 `/customer/cart` 那条**同键**（两侧都是购物车块），而路由记录 `/merchant/order/cart` 的 `meta.tab` 在块 4 一并改成 `'cart'`（改前是 `'order'`）⇒ 高亮成立
    { label: '用户中心', tab: 'user', to: '/merchant/user' },{ label: '经营数据', tab: 'stats', to: '/merchant/stats' },   // ⚠️ 可视化段 commit 2（2026-10-11）：**同行**追加（净零行数，理由同 `:29` 那条墓碑 —— 本文件被按行号引 17 处，最大被引行 `:61` 在本行**之后**）。「经营数据」**商家侧专有**：本段只落了 `/admin/stats` 与 `/merchant/stats` 两个端点、**无客户维度** ⇒ 客户右栏仍 3 项。`tab: 'stats'` 与 `router/index.js` 文件尾那条 `router.addRoute('merchant', { path: 'stats', …, meta: { tab: 'stats' } })` **逐字相同** ⇒ 高亮成立
  ]
}

// ── 未登录形态（R3，2026-10-05）────────────────────────────────────────────────────
// 未登录访客**可以进壳**（R3 起 `/` 也落到客户壳，见 `router/index.js` 的 `landingFor`）——
// 但顶栏右栏各项（消息 / 「购物车|订单中心」 / 用户中心，**商家侧**另有「经营数据」）**每一项都以登录态为前提**：点进去不是
// 挂载即 401 被踢回 `/login`，就是拿到空列表。⇒ 未登录时把右三项**整体换掉**，只留「登录 / 注册」。
//
// ⚠️ **标签写「登录 / 注册」，`to` 只有一条 `/login` —— 刻意的**（2026-10-06 改判 2，§5-AG (13)）：全仓
//    **没有** `#/register` 路由（凭空造路由 = 凭空造约定，同 `LEGACY_TAB` 那条口径），注册是 `LoginPage.vue`
//    内的滑动表单（`isSlide`）；且实测 LoginPage **不读任何 query 切注册态**（只读 `query.redirect`）⇒ 拆成两条链接 = 两条都指 `/login` 的**假承诺**。
// ⚠️ **左项原样保留**：每个壳的「商城主页」仍指自己的壳（客户 `/customer` / 商家 `/merchant`）
//    —— 匿名手敲 `#/merchant` 的人，看到的不该是「商城主页 → 客户壳」这种错指。
// ⚠️ 顶栏**只换表、不换壳**：壳其余部分（内容区 / `layoutClass` / 5s 轮询）对匿名访客本来就是
//    安全的（`AppShell.vue` 的 `getCurrentUser()` 无 cid/mid ⇒ 返回 `null` ⇒ 轮询空转；R6 前该函数在两壳里），
//    本笔**不新增**任何守卫、也不给壳加「未登录分支渲染」。
const ANON_RIGHT = [{ label: '登录 / 注册', tab: 'login', to: '/login' }]

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
