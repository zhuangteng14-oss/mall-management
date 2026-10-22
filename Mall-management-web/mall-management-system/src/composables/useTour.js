// src/composables/useTour.js —— 跨页漫游引导（driver.js，2026-10-21 / 2026-10-22 状态机）
//
// 三件事：
//   ① 「一键带我去开店」点了之后，**先记下待办的 tour**，再跳申请页；申请页 DOM 渲染完才开导览。
//      待办存 sessionStorage（刷新 / 登录跳转都还在），TTL 15 分钟（评审 P1-4：5 → 15）。
//   ② 商品发布 / 秒杀报名改成**多步路径引导**：一个 flow 横跨「当前页 → 个人中心 → 发布页」，
//      每一步只高亮当前页上的一个入口；用户点 driver 的「下一步」才真正 `router.push`。
//      flow 进度同样存 sessionStorage（`FLOW_KEY`，TTL 10 分钟），路由变化后由调用方
//      （ChatWidget 的 route watcher / 目标页 onMounted）调 `resumeTourFlow()` 续跑。
//   ③ `waitForElement` 不只看「查询到没有」，还要**看得见**（P1-1）：
//      `offsetParent !== null` + `getBoundingClientRect().width > 0` —— KeepAlive 缓存页的
//      隐藏 DOM 也在文档里，只 `querySelector` 会对着 0×0 的元素开导览。
//
// ⚠️ driver.js 走**动态 import**：不打进首屏 chunk，聊天球打开 / 点按钮时才拉。
//
// ⚠️ 「下一步」的两种走法都要照顾：
//   · 用户点 popover 的「下一步」⇒ 我们写下一索引 + `destroy()` + `router.push()`；
//   · 用户**直接点被高亮的入口**（2026-10-22 自动推进）⇒ 给该元素挂一次性 click 监听，
//     路由真的变了才销毁 driver、把索引推到下一步；新页的 watcher 调 `resumeTourFlow()`
//     时发现「旧页的 driver 还活着」会先销毁它再开下一步（见 `staleDriver`）。
//     点击元素不是链接（如文件选择框）时，1.2 秒内没换页就什么都不做，漫游继续。
//
// ⚠️ 具身飞行（2026-10-22）：每一步先把右下角的小助手「飞」到目标上方 0.8 秒，
//     飞行期间**不 drive()** —— 所以不会出现「遮罩先盖住整页、小助手瞬移」的观感。
import { nextTick } from 'vue'
import { getRole, isLoggedIn } from '../api/auth'
import { buildNavSteps, navNode, normalizeNavPath } from '../config/navGraph'

const PENDING_KEY = 'mall.chat.pendingTour.v1'
const FLOW_KEY = 'mall.chat.tourFlow.v1'
const TTL_MS = 15 * 60 * 1000
const FLOW_TTL_MS = 10 * 60 * 1000

/** 让 driver 的浮层主题跟项目 token 对齐。 */
const POPOVER_CLASS = 'chat-tour-popover'

const APPLY_PATH = { customer: '/customer/user/apply', merchant: '/merchant/user/apply' }
const ORDERS_PATH = { customer: '/customer/orders', merchant: '/merchant/orders' }
/** 商家秒杀报名页（N3 / Q5=A：只导航，不写库）。 */
const SECKILL_PATH = '/merchant/seckill'
/** 商家「上架商品」页（阶段一：need_publish=true 时的第一个胶囊）。 */
const PUBLISH_PATH = '/merchant/user/add'
/** 商家个人中心（「上架商品」快捷入口所在页）。 */
const USER_CENTER_PATH = '/merchant/user'

/** 旧胶囊待办 id（开店仍在用；发布 / 秒杀已改走 flow）。 */
export const PUBLISH_TOUR_ID = 'publish-tour'
export const SECKILL_TOUR_ID = 'seckill-tour'
/** 新流程 id（`startTourFlow` / ChatWidget 用）。 */
export const PUBLISH_FLOW_ID = 'publish'
export const SECKILL_FLOW_ID = 'seckill'

export function isMerchantRole() {
  return String(getRole() || '') === '2'
}

/** 「查看全部订单」的落点。管理员按客户侧走（客户壳的 roles 含 '3'）。 */
export function ordersPath() {
  return isMerchantRole() ? ORDERS_PATH.merchant : ORDERS_PATH.customer
}

/** 申请页落点。未登录时按客户侧猜；登录后由 `resumePathAfterLogin` 按真实角色纠正。 */
export function applyPath() {
  return isMerchantRole() ? APPLY_PATH.merchant : APPLY_PATH.customer
}

/** 秒杀报名页落点（只有商家壳有这条路由，Q5 表里也只对商家给导航）。 */
export function seckillPath() {
  return SECKILL_PATH
}

/** 商品发布页落点（只有商家壳有这条路由；非商家不会拿到这个胶囊）。 */
export function publishPath() {
  return PUBLISH_PATH
}

/**
 * 引导流的步骤表。
 *
 * `at(path)` = 「这一步应该出现在哪些页面」。用函数而不是等号，是因为同一个锚点可能在
 * 多个可达地址上（`/merchant/user` 与 `/merchant/user/info` 是同一个 `UserInfoPage`），
 * 而 `/merchant` 下的其它页（订单 / 历史购买…）仍然有顶栏的「用户中心」入口。
 *
 * `path`    = **这一步所在页面**。driver 的 next 按钮先写「下一索引」，再按下一步的
 *             `path` 决定是否跳转（同页则原地换高亮）。⚠️ 别把 `path` 写成「下一步要去
 *             哪里」—— 跨页两跳会在一次点击里连跳两页（2026-10-22 实测踩过）。
 */
/** 个人中心（两壳同页；商家账号在客户壳里也渲染「上架商品」快捷入口）。 */
const PERSONAL_PATHS = [
  USER_CENTER_PATH,
  `${USER_CENTER_PATH}/info`,
  '/customer/user',
  '/customer/user/info',
]
const isPersonalPage = (p) => PERSONAL_PATHS.includes(p)
/** 公域 / 后台上带「用户中心」顶栏入口的页（`/product`、`/shop` 是裸页，不在此列）。 */
const isShellPage = (p) => p.startsWith('/merchant') || p.startsWith('/customer')

const FLOWS = {
  [PUBLISH_FLOW_ID]: {
    id: PUBLISH_FLOW_ID,
    merchantOnly: true,
    steps: [
      {
        // 第 1 步永远落在**当前页**：只要不在个人中心 / 发布页，就高亮右上角入口。
        // ⚠️ 不能因为锚点暂时没渲染就 `router.push` —— 用户没点「下一步」前不许换页。
        element: '#tour-entry-merchant',
        at: (p) => isShellPage(p) && !isPersonalPage(p) && p !== PUBLISH_PATH,
        path: '/merchant',
        title: '① 进入商家后台',
        description: '我们首先要进入商家后台，请点击右上角的「用户中心」。',
      },
      {
        element: '#tour-menu-add',
        at: isPersonalPage,
        path: USER_CENTER_PATH,
        title: '② 上架商品',
        description: '在这里您可以发布新的商品，点击进入「上架商品」。',
      },
      {
        // 进入发布页后按真实表单顺序：主图 → 基本信息 → 价格库存 → 详情 → 提交。
        // 主图在页面下方（03 区），先让用户把封面选好；后面三步再回到对应区块。
        element: '#tour-publish-image',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '③ 商品主图',
        description: '首先，上传一张清晰的商品主图。',
      },
      {
        // 这一区同时含「商品名称 / 商品型号 / 商品类型」三个必填项；
        // driver 一个步骤只能框一个元素，故框整块，避免漏掉分类。
        element: '#tour-publish-basic',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '④ 名称与分类',
        description: '填写商品名称，并选择正确的商品分类（类型）。',
      },
      {
        // 这一区含价格与库存。库存输入目前是平台预留项（disabled），文案如实说明，
        // 不能假装它可填 —— 现状见 GoodsAdd 的 data-slot 注释。
        element: '#tour-publish-pricing',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '⑤ 价格与库存',
        description: '设置商品的售卖价格；库存当前为平台预留项（灰显），可先跳过。',
      },
      {
        element: '#tour-publish-detail',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '⑥ 商品详情',
        description: '填写详细的图文详情（选填），让买家更了解商品。',
      },
      {
        element: '#tour-publish-submit',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '⑦ 提交审核',
        description: '检查无误后，点击提交平台审核。',
      },
    ],
  },
  [SECKILL_FLOW_ID]: {
    id: SECKILL_FLOW_ID,
    merchantOnly: true,
    // 秒杀胶囊本身就是「带我去报名页」的导航入口（用户点它即确认跳转）；
    // 发布流程不同：入口必须落在当前页，所以 **不能** 打开这个开关。
    jumpToStart: true,
    steps: [
      {
        element: '#tour-seckill-select',
        at: (p) => p === SECKILL_PATH,
        path: SECKILL_PATH,
        title: '① 选择商品',
        description: '在这里选择您要参加秒杀的商品。',
      },
      {
        element: '#tour-seckill-submit',
        at: (p) => p === SECKILL_PATH,
        path: SECKILL_PATH,
        title: '② 提交报名',
        description: '点击这里完成报名。',
      },
    ],
  },
}

function normalizePath(path) {
  let p = String(path || '').split('?')[0].split('#')[0].trim()
  if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1)
  return p
}

/** 当前地址（hash 路由）。页内调用 `resumeTourFlow()` 时可省略参数。 */
export function currentTourPath() {
  const raw = typeof window === 'undefined' ? '' : (window.location.hash || '')
  return normalizePath(raw.startsWith('#') ? raw.slice(1) : raw)
}

/**
 * 路由适配器：由 ChatWidget 注入 `router.push`。
 * ⚠️ 不在这里直接 `import router` —— `router/index.js` → GoodsAdd → useTour 会成环。
 */
let navigateTo = null
export function setTourNavigator(push) {
  navigateTo = typeof push === 'function' ? push : null
}

function navigate(path) {
  if (navigateTo) {
    navigateTo(path)
    return
  }
  // 没有适配器（单测 / 极端早调用）时退回 hash 赋值，行为与 router.push 等价。
  if (typeof window !== 'undefined') window.location.hash = `#${path}`
}

/**
 * 胶囊点击 → 写一条待办漫游（先写、后 `router.push`，目标页读取后开高亮）。
 *
 * <p>与 {@link savePendingTour} 同一份存储；仅开店流程还在用。
 */
export function requestTour(tourId, routeKey) {
  savePendingTour(tourId, routeKey, null)
}

/** 目标页判据：待办存在且 tourId 对得上。直接走菜单进来 ⇒ false ⇒ 不开高亮。 */
export function hasPendingTour(tourId) {
  const pending = readPendingTour()
  return !!pending && pending.tourId === tourId
}

export function savePendingTour(tourId = 'open-store', routeKey = 'apply', resume = null) {
  try {
    sessionStorage.setItem(PENDING_KEY, JSON.stringify({ tourId, routeKey, resume, at: Date.now() }))
  } catch (e) { /* 隐私模式 / 存储满 ⇒ 本次不跨页，但不阻断跳转本身 */ }
}

/** 读待办；过期的顺手清掉。 */
export function readPendingTour() {
  let raw = null
  try {
    raw = sessionStorage.getItem(PENDING_KEY)
  } catch (e) {
    return null
  }
  if (!raw) return null
  let parsed = null
  try {
    parsed = JSON.parse(raw)
  } catch (e) {
    clearPendingTour()
    return null
  }
  if (!parsed || !parsed.tourId || !parsed.at || Date.now() - parsed.at > TTL_MS) {
    clearPendingTour()
    return null
  }
  return parsed
}

export function clearPendingTour() {
  try {
    sessionStorage.removeItem(PENDING_KEY)
  } catch (e) { /* 无存储可用 ⇒ 本来也没有待办 */ }
}

// ── 引导流（TourFlow）状态 ─────────────────────────────────────────────────────

/**
 * 运行时注册的流程（NavGraph 动态算出来的步骤表）。
 *
 * ⚠️ 只存内存：路由切换（`router.push`）不会丢，整页刷新会丢 —— 动态导航流刷新后自然结束，
 *    这是有意的（用户已经离开了那条流）。`FLOWS` 里那几条静态流程不受影响。
 */
const runtimeFlows = new Map()

function flowById(id) {
  return FLOWS[id] || runtimeFlows.get(id) || null
}

function writeTourFlow(flowId, index) {
  try {
    sessionStorage.setItem(FLOW_KEY, JSON.stringify({ flowId, index, at: Date.now() }))
  } catch (e) { /* 存不了就只在本次内存里跑 */ }
}

function readTourFlow() {
  let raw = null
  try {
    raw = sessionStorage.getItem(FLOW_KEY)
  } catch (e) {
    return null
  }
  if (!raw) return null
  let parsed = null
  try {
    parsed = JSON.parse(raw)
  } catch (e) {
    clearTourFlow()
    return null
  }
  if (!parsed || !flowById(parsed.flowId) || !parsed.at || Date.now() - parsed.at > FLOW_TTL_MS) {
    clearTourFlow()
    return null
  }
  return parsed
}

export function clearTourFlow() {
  try {
    const raw = sessionStorage.getItem(FLOW_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed && parsed.flowId) runtimeFlows.delete(parsed.flowId)
    }
  } catch (e) { /* 存档坏了 ⇒ 下面照删 */ }
  try {
    sessionStorage.removeItem(FLOW_KEY)
  } catch (e) { /* 无存储可用 ⇒ 本来也没有流程 */ }
  hideSprite()
}

/** 这一步是否属于当前页。 */
function stepAllowed(step, path) {
  if (step.at) return step.at(path)
  return normalizePath(step.path) === path
}

/**
 * 把「存档里的索引」对齐到当前页：
 * · 当前索引本来就属于本页 ⇒ 原样；
 * · 用户直接点了被高亮的入口、页面已经往前走了 ⇒ 往后找第一步属于本页的；
 * · 往后也找不到（用户自己导航去了别处）⇒ 返回 -1，调用方清流。
 */
function alignIndex(flow, index, path) {
  if (stepAllowed(flow.steps[index], path)) return index
  for (let i = index + 1; i < flow.steps.length; i += 1) {
    if (stepAllowed(flow.steps[i], path)) return i
  }
  return -1
}

function pickStartIndex(flow, path) {
  for (let i = 0; i < flow.steps.length; i += 1) {
    if (stepAllowed(flow.steps[i], path)) return i
  }
  return 0
}

// driver 实例与「主动交接」标志：销毁是流程自己发起的时候，onDestroyed 不能清存档。
let tourDriver = null
let handoff = false
let activePath = ''
let resumeToken = 0

// ── 具身飞行精灵（模块三） ───────────────────────────────────────────────────

const SPRITE_CLASS = 'tour-sprite'
/** 飞行时长：与 ChatWidget 里 `.tour-sprite` 的 transition 必须一致。 */
const FLIGHT_MS = 800
/** 点了高亮元素但这段时间内路由没变 ⇒ 当作普通点击（文件选择框 / 页内展开），不推进。 */
const CLICK_NAV_WAIT_MS = 1200

let sprite = null
let spriteX = 0
let spriteY = 0
let flightToken = 0
let clickToken = 0
let activeStepEl = null
let activeStepClick = null

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function spriteHome() {
  return {
    x: Math.max(16, (window.innerWidth || 0) - 96),
    y: Math.max(16, (window.innerHeight || 0) - 96),
  }
}

function prefersReducedMotion() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 创建（或复用）固定定位的小助手。样式在 ChatWidget.vue 的全局段（`.tour-sprite`）。 */
function ensureSprite() {
  if (typeof document === 'undefined') return null
  if (sprite && sprite.isConnected) return sprite
  sprite = document.createElement('div')
  sprite.className = SPRITE_CLASS
  sprite.setAttribute('aria-hidden', 'true')
  sprite.textContent = '🤖'
  const home = spriteHome()
  spriteX = home.x
  spriteY = home.y
  sprite.style.transform = `translate3d(${spriteX}px, ${spriteY}px, 0)`
  document.body.appendChild(sprite)
  return sprite
}

function hideSprite() {
  // 让正在进行中的飞行作废：它醒来时 token 已变，不会再打开高亮。
  flightToken += 1
  if (!sprite) return
  sprite.classList.remove('is-visible')
  sprite.dataset.flightMs = '0'
  // 归位在透明状态下做：下一轮漫游仍从右下角起飞，而不是从上次的落点。
  sprite.style.transition = 'none'
  const home = spriteHome()
  spriteX = home.x
  spriteY = home.y
  sprite.style.transform = `translate3d(${spriteX}px, ${spriteY}px, 0)`
  void sprite.offsetWidth
  sprite.style.transition = ''
}

/**
 * 让精灵从当前位置滑到目标元素上方 60px（0.8s ease-in-out）。
 *
 * @returns {Promise<boolean>} false = 目标在飞行途中消失，或这次飞行已被更新的调用取代
 */
async function flyTo(el) {
  const sp = ensureSprite()
  if (!sp) return true
  const token = ++flightToken
  const fromX = spriteX
  const fromY = spriteY

  // 先把目标滚进视口再量 —— 表单后半段的锚点初始可能在屏幕外，
  // 不滚的话精灵会飞到窗口外面去。
  try {
    el.scrollIntoView({ block: 'center', inline: 'nearest' })
  } catch (e) { /* 平滑滚动不被支持时，量到的 rect 仍然可用 */ }
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
  if (!el.isConnected || token !== flightToken) return false

  const rect = el.getBoundingClientRect()
  const x = Math.max(8, Math.min(rect.left, (window.innerWidth || 0) - 56))
  const y = Math.max(8, rect.top - 60)

  sp.dataset.flightFrom = `${Math.round(fromX)},${Math.round(fromY)}`
  sp.dataset.flightTo = `${Math.round(x)},${Math.round(y)}`
  const duration = prefersReducedMotion() ? 0 : FLIGHT_MS
  sp.dataset.flightMs = String(duration)
  sp.classList.add('is-visible')
  // 强制一帧：元素刚创建时没有「上一帧」可供过渡，直接改终点会瞬移。
  void sp.offsetWidth
  spriteX = x
  spriteY = y
  sp.style.transform = `translate3d(${x}px, ${y}px, 0)`

  if (duration === 0) return true
  await sleep(duration)
  return token === flightToken && el.isConnected
}

// ── 自动推进（模块二）：只监听当前高亮元素 ──────────────────────────────────

function detachStepClick() {
  if (activeStepEl && activeStepClick) {
    try {
      activeStepEl.removeEventListener('click', activeStepClick)
    } catch (e) { /* 元素已随路由移除 */ }
  }
  activeStepEl = null
  activeStepClick = null
}

/**
 * 给**当前高亮的 DOM** 挂一次性 click 监听（模块二）。
 *
 * <p>判据是「路由真的变了」，不是「点了一下」：
 * · 变成新路由 ⇒ 立刻把进度推到下一步、销毁旧 driver（`handoff` 保证存档不丢），
 *   新页挂载后由 `resumeTourFlow` 自动开下一步高亮；
 * · 1.2 秒内没换页（如主图是 `<input type=file>`）⇒ 当作普通点击，漫游原样继续。
 *
 * <p>用户自己关掉漫游时 `destroyTourDriver` 会 `detachStepClick`，监听不会残留。
 */
function watchStepClick(flow, index, el, owner) {
  detachStepClick()
  const token = ++clickToken
  const from = activePath
  const onClick = () => {
    const startedAt = Date.now()
    const tick = () => {
      if (token !== clickToken) return
      if (currentTourPath() !== from) {
        const next = index + 1
        if (next < flow.steps.length) writeTourFlow(flow.id, next)
        else clearTourFlow()
        // ⚠️ 只销毁**这一步的** driver：新页的 watcher 可能已经开出了下一步的新实例，
        //    按「当前全局 tourDriver」去销毁会把刚亮起来的那一步一起打掉。
        if (owner && tourDriver === owner && owner.isActive()) destroyTourDriver()
        return
      }
      if (Date.now() - startedAt > CLICK_NAV_WAIT_MS) return
      requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
  }
  el.addEventListener('click', onClick, { once: true })
  activeStepEl = el
  activeStepClick = onClick
}

function destroyTourDriver() {
  detachStepClick()
  const d = tourDriver
  if (!d) return
  handoff = true
  tourDriver = null
  try {
    d.destroy()
  } catch (e) {
    // driver 已自行销毁 / DOM 已被路由换掉 —— 没有可补救的，保持静默。
  }
  handoff = false
}

/**
 * 开当前索引对应的一步。
 *
 * ⚠️ 每次只给 driver **一个 step**：跨页的步骤此刻还不存在，不能一次性塞进数组。
 *    进度文字用完整的 `flow.steps.length`，所以用户看到的仍是「第 3 步 / 共 5 步」。
 */
async function openFlowStep(flow, index) {
  const timeout = import.meta.env.DEV ? 8000 : 3000
  const step = flow.steps[index]
  if (!(await waitForElement(step.element, timeout))) return null

  const modules = await Promise.all([
    import('driver.js'),
    import('driver.js/dist/driver.css'),
  ])
  const { driver } = modules[0]

  // 等元素的过程中路由又变了（用户直接点了高亮入口）⇒ 这一步已经不属于当前页。
  // 交给下一次 `resumeTourFlow` 按新路径重新对齐，别用 dummy 元素开一个空洞的高亮。
  if (!isVisible(document.querySelector(step.element))) return null

  // 先拆上一步（遮罩消失），再让精灵飞过去 —— **飞行这 0.8 秒页面上没有遮罩**，
  // 飞到位后才 drive()。顺序反过来就是「遮罩先盖上、小助手瞬移」。
  destroyTourDriver()
  const el = document.querySelector(step.element)
  const arrived = await flyTo(el)
  if (!arrived || !isVisible(el)) return null

  const total = flow.steps.length
  const last = index === total - 1
  tourDriver = driver({
    showProgress: true,
    progressText: `第 ${index + 1} 步 / 共 ${total} 步`,
    nextBtnText: last ? '完成' : '下一步',
    prevBtnText: '上一步',
    doneBtnText: last ? '完成' : '下一步',
    showButtons: index > 0 ? ['previous', 'next', 'close'] : ['next', 'close'],
    allowClose: true,
    // 点遮罩不销毁：用户可能只是想点被高亮的入口（那个洞是通的），误触代价太大。
    overlayClickBehavior: 'none',
    overlayColor: 'rgba(15, 23, 42, .55)',
    stagePadding: 8,
    stageRadius: 8,
    popoverClass: POPOVER_CLASS,
    steps: [{
      element: step.element,
      // ⚠️ driver 会把「实例内没有上一步」自动翻译成 `disableButtons:['previous']`；
      //    本实现一次只给 driver 一个 step，但流程整体可能有上一步 ⇒ 必须显式清空。
      popover: { title: step.title, description: step.description, disableButtons: [] },
    }],
    onNextClick: () => advanceFlow(flow, index),
    onPrevClick: () => retreatFlow(flow, index),
    onCloseClick: () => {
      clearTourFlow()
      destroyTourDriver()
    },
    onDestroyed: () => {
      tourDriver = null
      if (!handoff) clearTourFlow()
    },
  })
  tourDriver.drive()
  // 模块二：只给**这一步高亮的那一个元素**挂一次性监听。
  watchStepClick(flow, index, el, tourDriver)
  return tourDriver
}

/** 下一步：写索引 → 拆 driver → 同页续跑 / 跨页 push（路由 watcher 再续）。 */
function advanceFlow(flow, index) {
  const next = index + 1
  if (next >= flow.steps.length) {
    clearTourFlow()
    destroyTourDriver()
    return
  }
  writeTourFlow(flow.id, next)
  destroyTourDriver()
  const target = normalizePath(flow.steps[next].path)
  if (target && target !== activePath) {
    activePath = target
    navigate(target)
    return
  }
  resumeTourFlow(activePath)
}

/** 上一步：对称实现；第一步没有 previous 按钮。 */
function retreatFlow(flow, index) {
  if (index <= 0) return
  const prev = index - 1
  writeTourFlow(flow.id, prev)
  destroyTourDriver()
  const target = normalizePath(flow.steps[prev].path)
  if (target && target !== activePath) {
    activePath = target
    navigate(target)
    return
  }
  resumeTourFlow(activePath)
}

/**
 * 从当前页续跑已存档的引导流。
 *
 * 调用点（三处都要）：
 *   1. `ChatWidget` 的 `watch(route.path)` —— 挂载新页后自动续；
 *   2. 发布页 / 秒杀页的 `onMounted` —— 兜住「watcher 比组件挂载早」的竞态；
 *   3. `startTourFlow` 与同页 `advanceFlow`。
 *
 * @param {string} [path] 当前路由（缺省读 `window.location.hash`）
 */
export async function resumeTourFlow(path) {
  const state = readTourFlow()
  if (!state) return null
  const flow = flowById(state.flowId)
  if (!flow) {
    clearTourFlow()
    return null
  }

  const current = normalizePath(path || currentTourPath())
  const index = alignIndex(flow, state.index, current)
  if (index < 0) {
    // 用户自己导航去了流程之外的页面 ⇒ 结束，不把人拽回来。
    clearTourFlow()
    return null
  }
  if (index !== state.index) writeTourFlow(flow.id, index)

  // 自动推进（模块二）的关键一步：用户直接点了被高亮的入口，旧 driver 还指着
  // **上一页**的 DOM。必须先销毁它，否则下面的「已有一条活着的引导」会把它原样
  // 返回给新页 —— 高亮停在旧位置，整条流看起来像卡死了。
  if (tourDriver && normalizePath(current) !== activePath) destroyTourDriver()
  activePath = current

  // 同页同时触发（watcher 与 onMounted）⇒ 不重复开。
  if (tourDriver && tourDriver.isActive()) return tourDriver

  const token = ++resumeToken
  await nextTick()
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
  if (token !== resumeToken) return null

  const tour = await openFlowStep(flow, index)
  if (!tour && token === resumeToken) {
    // 只有「用户还停在这一步该出现的页面、却始终没有锚点」才清流；
    // 若元素是在等待期间被路由换走的，留给新页的 resume 重新对齐，别误杀整条流程。
    if (stepAllowed(flow.steps[index], normalizePath(currentTourPath()))) clearTourFlow()
  }
  return tour
}

/**
 * 胶囊点击入口：只决定从当前页的哪一步开始，**绝不主动换页**。
 * 换页只发生在用户点 driver 的「下一步 / 上一步」之后（见 `advanceFlow` / `retreatFlow`）。
 *
 * @param {string} flowId `PUBLISH_FLOW_ID` / `SECKILL_FLOW_ID`
 * @param {string} [path] 当前路由
 * @returns {Promise<object|null>} driver 实例；当前页不承载该流程时返回 null
 */
export async function startTourFlow(flowId, path) {
  const flow = FLOWS[flowId]
  if (!flow) return null
  if (flow.merchantOnly && !isMerchantRole()) {
    clearTourFlow()
    return null
  }

  const current = normalizePath(path || currentTourPath())
  const index = pickStartIndex(flow, current)
  const start = flow.steps[index]

  // ⚠️ 入口点击**绝不自动换页**（2026-10-22 用户实测纠偏）：
  //    · 锚点还没渲染（异步组件 / 资料未到）⇒ 照样在当前页等它，`resumeTourFlow` 会轮询；
  //    · 当前页压根不是这一步该出现的页（裸页 `/product` / `/shop`）⇒ 直接返回 null，
  //      由调用方提示用户先回商城主页，而不是替用户做一次 `router.push`。
  if (!stepAllowed(start, current)) {
    // 只有 flow 明确声明 `jumpToStart`（秒杀报名）才允许在胶囊点击后导航到起始页。
    // 发布流程没有这个开关 ⇒ 当前页不承载入口时返回 null，由调用方提示，绝不瞬移。
    if (flow.jumpToStart && start.path) {
      writeTourFlow(flow.id, index)
      activePath = normalizePath(start.path)
      navigate(activePath)
    }
    return null
  }

  writeTourFlow(flow.id, index)
  activePath = current
  return resumeTourFlow(current)
}

/**
 * 大模型给出的 `target_path` → 一段可点的自动寻路。
 *
 * 流程：
 *   ① 目标页在 NavGraph 里 ⇒ 用 `buildNavSteps` 从**当前页**跑 BFS，得到逐步高亮表；
 *   ② 发布 / 秒杀两个目标复用已有的精细流程（发布有 5 个表单步，不能被通用寻路截短）；
 *   ③ 当前页到目标页在图上不连通（如裸页 `/product`）⇒ 返回 null，由调用方提示用户回商城首页。
 *
 * ⚠️ 与 `startTourFlow` 同一条铁律：**入口点击不主动换页**。动态流的每一步都落在当前页，
 *    只有用户点「下一步」时才由 `advanceFlow` 按下一步的 `path` 推路由。
 *
 * @param {string} fromPath   当前路由
 * @param {string} targetPath 大模型返回的目标路径
 * @returns {Promise<object|true|null>} driver 实例 / 已交给跳转流程（秒杀）/ 无路可走
 */
export async function startNavGuidedTour(fromPath, targetPath) {
  const target = normalizeNavPath(targetPath)
  if (!navNode(target)) return null
  const current = normalizePath(fromPath || currentTourPath())

  if (target === PUBLISH_PATH && isMerchantRole()) {
    return startTourFlow(PUBLISH_FLOW_ID, current)
  }
  if (target === SECKILL_PATH && isMerchantRole()) {
    // 秒杀流程带 `jumpToStart`：点胶囊 = 用户确认要去报名页，允许直接导航。
    startTourFlow(SECKILL_FLOW_ID, current)
    return true
  }

  const steps = buildNavSteps(current, target)
  if (!steps) return null

  const id = `nav-${target.replace(/[^a-z0-9]+/gi, '-')}-${Date.now()}`
  runtimeFlows.set(id, { id, steps })
  writeTourFlow(id, 0)
  activePath = current
  return resumeTourFlow(current)
}

/** 登录回来后按**真实角色**续接待办（开店流程仍在用）。 */
export function resumePathAfterLogin(currentPath) {
  if (!isLoggedIn()) return null
  const pending = readPendingTour()
  if (!pending) return null
  // 秒杀 / 发布都只属于商家壳；客户登录后不能硬闯商家路由。
  if ((pending.routeKey === 'seckill' || pending.routeKey === 'publish') && !isMerchantRole()) return null
  const target = pending.routeKey === 'seckill' ? seckillPath()
    : pending.routeKey === 'publish' ? publishPath()
      : applyPath()
  return currentPath === target ? null : target
}

/**
 * 等一个元素**可见**。
 *
 * @param {string} selector
 * @param {number} timeoutMs 开发 8s / 生产 3s（由调用方给）
 * @returns {Promise<Element|null>} 超时回 null（调用方负责兜底提示）
 */
export function waitForElement(selector, timeoutMs = 3000) {
  return new Promise((resolve) => {
    const started = Date.now()
    const tick = () => {
      const el = document.querySelector(selector)
      if (el && isVisible(el)) {
        resolve(el)
        return
      }
      if (Date.now() - started >= timeoutMs) {
        resolve(null)
        return
      }
      requestAnimationFrame(tick)
    }
    tick()
  })
}

/** P1-1 的判据：既在布局里、又有实际尺寸。 */
export function isVisible(el) {
  if (!el || !el.isConnected) return false
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return false
  // `position: fixed` 的元素 offsetParent 天生是 null —— 本页两处目标都在表单里，
  // 但把这条豁免写清楚，免得以后换目标时误判。
  if (el.offsetParent === null) {
    const pos = getComputedStyle(el).position
    return pos === 'fixed' || pos === 'sticky'
  }
  return true
}

/**
 * 通用漫游：等目标页渲染 → 逐个等锚点可见 → 拉 driver.js（动态 import）→ 开。
 *
 * @param {{element:string,title:string,description:string}[]} steps 锚点与文案
 * @param {{onDone?:(ok:boolean)=>void}} [opts] 结束回调（false = 锚点没等到）
 */
async function runTour(steps, { onDone } = {}) {
  // 先等两帧：路由切换后目标页可能刚挂载（KeepAlive 激活也走这条）。
  await nextTick()
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))

  const timeout = import.meta.env.DEV ? 8000 : 3000
  for (const step of steps) {
    if (!(await waitForElement(step.element, timeout))) {
      clearPendingTour()
      onDone?.(false)
      return null
    }
  }

  const modules = await Promise.all([
    import('driver.js'),
    import('driver.js/dist/driver.css'),
  ])
  const { driver } = modules[0]

  const tour = driver({
    showProgress: true,
    progressText: '第 {{current}} 步 / 共 {{total}} 步',
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '开始填写',
    allowClose: true,
    overlayColor: 'rgba(15, 23, 42, .55)',
    stagePadding: 8,
    stageRadius: 8,
    popoverClass: POPOVER_CLASS,
    steps: steps.map((s) => ({
      element: s.element,
      popover: { title: s.title, description: s.description },
    })),
    onDestroyed: () => {
      clearPendingTour()
      onDone?.(true)
    },
  })

  // ⚠️ 先清再开：开着导览时路由再变（如用户点浏览器返回）不会二次启动。
  clearPendingTour()
  tour.drive()
  return tour
}

export function startOpenStoreTour({ onDone } = {}) {
  return runTour([
    {
      element: '#tour-shop-name',
      title: '① 给店铺起个名字',
      description: '这个名字会展示给买家（例如「米可数码专营店」），之后可以改。',
    },
    {
      element: '#tour-submit',
      title: '② 提交入驻申请',
      description: '填完店铺名称和营业执照号后点这里。平台审核通过，当前账号就自动升级为商家。',
    },
  ], { onDone })
}
