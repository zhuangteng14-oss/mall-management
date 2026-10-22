// src/config/navGraph.js —— 全局路由图（NavGraph）单一事实源（2026-10-22）
//
// 目的：把「用户想去哪个页面」与「从当前页面怎么一步步走过去」拆成两层：
//   · 节点（NAV_NODES） = 真实存在的页面（路径与 `router/index.js` 逐字一致）；
//   · 边（NAV_EDGES）   = 页面上**真实可点**的入口（选择器落在 DOM 上，不是想象出来的链接）。
//
// 前端拿到大模型返回的 `targetPath` 后，用 `findShortestPath(currentPath, targetPath)`
// 跑一次 BFS，把结果交给 `useTour` 逐步高亮；**换页只发生在用户点「下一步」之后**。
//
// ⚠️ 与后端 `chat/service/RouteCatalog.java` 是**两份镜像**：新增 / 改名页面时两边都要改
//    （后端那份给大模型看中文说明，这一份给前端算路）。路径必须与 `router/index.js` 逐字一致。
//
// ⚠️ 边的 `selector` 依赖组件上的 `data-nav-to` 属性（AppShell 顶栏 / UserInfoPage 快捷入口）。
//    新增边时先确认选择器在**真实 DOM** 里能命中；命中不了 ⇒ `waitForElement` 会超时、整条流断掉。

const MERCHANT = ['2', '3']
const CUSTOMER = ['1', '3']

/** 页面节点。`anchor` 是「到达目标页」那一步要高亮的整页容器。 */
export const NAV_NODES = [
  // ── 商家壳 ────────────────────────────────────────────────────────────────
  { path: '/merchant', title: '商家首页', description: '商城主页：浏览商品、搜索、进入平台活动。', anchor: '.home-page', roles: MERCHANT },
  { path: '/merchant/search', title: '商品搜索', description: '按关键词搜索商品。', anchor: '.goods-select-view', roles: MERCHANT },
  { path: '/merchant/msg', title: '消息中心', description: '私信与系统通知。', anchor: '.msg-center', roles: MERCHANT },
  { path: '/merchant/order', title: '订单处理', description: '商家订单中心：发货、收货与订单状态流转。', anchor: '.order-center-page', roles: MERCHANT },
  { path: '/merchant/order/cart', title: '购物车', description: '购物车与结算。', anchor: '.pane-page', roles: MERCHANT },
  { path: '/merchant/orders', title: '我的订单', description: '买家视角的我的订单列表（含售后进度）。', anchor: '.ordermine-page', roles: MERCHANT },
  { path: '/merchant/user', title: '个人中心', description: '用户中心：个人资料、商品、订单与资产的枢纽。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/info', title: '个人信息', description: '头像、昵称与账号资料。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/add', title: '上架商品', description: '发布新商品：主图、名称、分类、价格、详情与提交审核。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/list', title: '查看商品', description: '商品管理：在售 / 待审 / 驳回商品与 SKU，可编辑价格与库存。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/history', title: '历史购买记录', description: '已完成的历史订单。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/friend', title: '好友', description: '好友与私信。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/member', title: '会员中心', description: '会员等级、积分、余额、优惠券与资产明细。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/apply', title: '商家入驻申请', description: '商家入驻 / 品牌加盟申请。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/user/favorites', title: '我的收藏', description: '收藏的商品。', anchor: '.uc-main', roles: MERCHANT },
  { path: '/merchant/stats', title: '经营数据', description: '店铺经营看板：销售额、订单量、趋势与库存。', anchor: '.ms-page', roles: MERCHANT },
  { path: '/merchant/seckill', title: '秒杀报名', description: '选择商品参加平台秒杀。', anchor: '.skl-page', roles: MERCHANT },
  { path: '/merchant/after-sales', title: '售后工作台', description: '退款 / 退货 / 售后队列与处理。', anchor: '.order-center-page', roles: MERCHANT },

  // ── 裸页 ──────────────────────────────────────────────────────────────────
  // 商品详情（`/product`，`meta.public`）。它不是壳页，但**必须进图**：
  // 用户在详情页问「售后」时，BFS 要先把他带回商城首页，再走壳内的入口 ——
  // 见下方 `/product → /merchant|customer` 的两条「‹ 返回」边。
  { path: '/product', title: '商品详情', description: '商品详情：图文、SKU、价格与购买入口。', anchor: '.goods-detail-page', roles: ['1', '2', '3'] },

  // ── 客户壳 ────────────────────────────────────────────────────────────────
  { path: '/customer', title: '商城首页', description: '商城主页：浏览商品、搜索、进入平台活动。', anchor: '.home-page', roles: CUSTOMER },
  { path: '/customer/search', title: '商品搜索', description: '按关键词搜索商品。', anchor: '.goods-select-view', roles: CUSTOMER },
  { path: '/customer/msg', title: '消息中心', description: '私信与系统通知。', anchor: '.msg-center', roles: CUSTOMER },
  { path: '/customer/cart', title: '购物车', description: '购物车与结算。', anchor: '.pane-page', roles: CUSTOMER },
  { path: '/customer/orders', title: '我的订单', description: '我的订单列表（含售后进度）。', anchor: '.ordermine-page', roles: CUSTOMER },
  { path: '/customer/user', title: '个人中心', description: '用户中心：个人资料、订单与资产的枢纽。', anchor: '.uc-main', roles: CUSTOMER },
  { path: '/customer/user/info', title: '个人信息', description: '头像、昵称与账号资料。', anchor: '.uc-main', roles: CUSTOMER },
  { path: '/customer/user/history', title: '历史购买记录', description: '已完成的历史订单。', anchor: '.uc-main', roles: CUSTOMER },
  { path: '/customer/user/friend', title: '好友', description: '好友与私信。', anchor: '.uc-main', roles: CUSTOMER },
  { path: '/customer/user/apply', title: '商家入驻申请', description: '商家入驻 / 品牌加盟申请。', anchor: '.uc-main', roles: CUSTOMER },
  { path: '/customer/user/member', title: '会员中心', description: '会员等级、积分、余额、优惠券与资产明细。', anchor: '.uc-main', roles: CUSTOMER },
  { path: '/customer/user/favorites', title: '我的收藏', description: '收藏的商品。', anchor: '.uc-main', roles: CUSTOMER },
]

/** 顶栏入口（AppShell 渲染，带 `data-nav-to`）。 */
function topLinks(base, cartTo) {
  return [
    { to: base, selector: `.top-mark[data-nav-to="${base}"]`, label: '商城主页' },
    { to: `${base}/msg`, selector: `[data-nav-to="${base}/msg"]`, label: '消息' },
    { to: cartTo, selector: `[data-nav-to="${cartTo}"]`, label: '购物车' },
    { to: `${base}/user`, selector: '#tour-entry-merchant', label: '用户中心' },
  ]
}

/** 个人中心快捷入口（UserInfoPage 渲染，带 `data-nav-to`）。 */
const MERCHANT_HUB_LINKS = [
  { to: '/merchant/orders', label: '我的订单', selector: '[data-nav-to="/merchant/orders"]' },
  { to: '/merchant/user/history', label: '历史购买记录', selector: '[data-nav-to="/merchant/user/history"]' },
  { to: '/merchant/user/friend', label: '好友 / 消息', selector: '[data-nav-to="/merchant/user/friend"]' },
  { to: '/merchant/order', label: '商家订单处理', selector: '[data-nav-to="/merchant/order"]' },
  { to: '/merchant/user/add', label: '上架商品', selector: '[data-nav-to="/merchant/user/add"]' },
  { to: '/merchant/user/list', label: '查看商品', selector: '[data-nav-to="/merchant/user/list"]' },
  { to: '/merchant/stats', label: '经营数据', selector: '[data-nav-to="/merchant/stats"]' },
  { to: '/merchant/user/member', label: '会员中心', selector: '[data-nav-to="/merchant/user/member"]' },
  { to: '/merchant/user/apply', label: '商家入驻 / 品牌加盟', selector: '[data-nav-to="/merchant/user/apply"]' },
  { to: '/merchant/user/favorites', label: '我的收藏', selector: '[data-nav-to="/merchant/user/favorites"]' },
  { to: '/merchant/after-sales', label: '售后工作台', selector: '[data-nav-to="/merchant/after-sales"]' },
  { to: '/merchant/user/info', label: '个人信息', selector: '[data-nav-to="/merchant/user/info"]' },
]

const CUSTOMER_HUB_LINKS = [
  { to: '/customer/orders', label: '我的订单', selector: '[data-nav-to="/customer/orders"]' },
  { to: '/customer/user/history', label: '历史购买记录', selector: '[data-nav-to="/customer/user/history"]' },
  { to: '/customer/user/friend', label: '好友 / 消息', selector: '[data-nav-to="/customer/user/friend"]' },
  { to: '/customer/user/apply', label: '商家入驻申请', selector: '[data-nav-to="/customer/user/apply"]' },
  { to: '/customer/user/member', label: '会员中心', selector: '[data-nav-to="/customer/user/member"]' },
  { to: '/customer/user/favorites', label: '我的收藏', selector: '[data-nav-to="/customer/user/favorites"]' },
  { to: '/customer/user/info', label: '个人信息', selector: '[data-nav-to="/customer/user/info"]' },
  { to: '/customer/cart', label: '购物车', selector: '[data-nav-to="/customer/cart"]' },
]

/** 把快捷入口表补上来源页（`from`），变成有向边。 */
function hubLinks(hub, links) {
  return links.map((l) => ({
    from: hub,
    to: l.to,
    selector: l.selector,
    label: l.label,
  }))
}

function topEdges(base, cartTo) {
  const nodes = NAV_NODES.filter((n) => n.path === base || n.path.startsWith(`${base}/`))
  const links = topLinks(base, cartTo)
  const out = []
  for (const node of nodes) {
    for (const link of links) {
      if (link.to !== node.path) {
        out.push({ from: node.path, to: link.to, selector: link.selector, label: link.label })
      }
    }
  }
  return out
}

/** 有向边。一律「从某页上的真实入口 → 目标页」。 */
export const NAV_EDGES = [
  ...topEdges('/merchant', '/merchant/order/cart'),
  ...topEdges('/customer', '/customer/cart'),
  ...hubLinks('/merchant/user', MERCHANT_HUB_LINKS),
  ...hubLinks('/customer/user', CUSTOMER_HUB_LINKS),
  // 详情页的唯一出口：左上角「‹ 返回」（ProductPage.goBack 按身份回商家 / 客户首页）。
  // 两条边同一个选择器：BFS 只会走通向目标的那一条，不需要前端再判角色。
  { from: '/product', to: '/merchant', selector: '.nav-back', label: '返回商城首页' },
  { from: '/product', to: '/customer', selector: '.nav-back', label: '返回商城首页' },
]

/** 去掉 query / hash / 末尾斜杠 —— 与 `useTour.currentTourPath()` 同口径。 */
export function normalizeNavPath(path) {
  let p = String(path || '').split('?')[0].split('#')[0].trim()
  if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1)
  return p
}

export function navNode(path) {
  const p = normalizeNavPath(path)
  return NAV_NODES.find((n) => n.path === p) || null
}

/**
 * BFS 最短路径。
 *
 * @returns {Array<{from:string,to:string,edge:object}>|null} `null` = 图里走不过去
 *          （例如从裸页 `/product` 出发）；`[]` = 已经在目标页。
 */
export function findShortestPath(from, to) {
  const start = normalizeNavPath(from)
  const goal = normalizeNavPath(to)
  if (!navNode(start) || !navNode(goal)) return null
  if (start === goal) return []

  const adjacency = new Map()
  for (const edge of NAV_EDGES) {
    if (!adjacency.has(edge.from)) adjacency.set(edge.from, [])
    adjacency.get(edge.from).push(edge)
  }

  const queue = [start]
  const prev = new Map([[start, null]])
  while (queue.length) {
    const current = queue.shift()
    if (current === goal) break
    for (const edge of adjacency.get(current) || []) {
      if (prev.has(edge.to)) continue
      prev.set(edge.to, { from: current, edge })
      queue.push(edge.to)
    }
  }
  if (!prev.has(goal)) return null

  const hops = []
  let cursor = goal
  while (cursor !== start) {
    const step = prev.get(cursor)
    if (!step) return null
    hops.unshift({ from: step.from, to: cursor, edge: step.edge })
    cursor = step.from
  }
  return hops
}

/**
 * 把最短路径翻译成 `useTour` 的步骤表。
 *
 * 每一步的 `path` 都是「这一步出现在哪一页」：入口步落在来源页，最后一步落在目标页
 * （高亮整页容器，表示「到了」）。`useTour` 的「下一步」才会按 `path` 换页。
 *
 * @returns {Array<object>|null}
 */
export function buildNavSteps(from, to) {
  const targetPath = normalizeNavPath(to)
  const target = navNode(targetPath)
  if (!target) return null
  const hops = findShortestPath(from, targetPath)
  if (hops === null) return null

  const steps = hops.map((hop) => {
    const dest = navNode(hop.to)
    return {
      element: hop.edge.selector,
      at: (p) => normalizeNavPath(p) === hop.from,
      path: hop.from,
      title: `点击「${hop.edge.label}」`,
      description: `这一步会带你去「${dest ? dest.title : hop.to}」。`,
    }
  })

  steps.push({
    element: target.anchor || '.content',
    at: (p) => normalizeNavPath(p) === targetPath,
    path: targetPath,
    title: `已到达「${target.title}」`,
    description: target.description,
  })
  return steps
}
