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
//   · 用户**直接点被高亮的入口** ⇒ 浏览器自己完成跳转，watcher 读到新路由后
//     用 `step.at(path)` 把索引对齐到「当前页该出现的那一步」（见 `alignIndex`），
//     否则会对着上一页的锚点空等 8 秒然后整条流断掉。
import { nextTick } from 'vue'
import { getRole, isLoggedIn } from '../api/auth'

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
const FLOWS = {
  [PUBLISH_FLOW_ID]: {
    id: PUBLISH_FLOW_ID,
    merchantOnly: true,
    steps: [
      {
        element: '#tour-entry-merchant',
        at: (p) => p.startsWith('/merchant')
          && p !== USER_CENTER_PATH
          && p !== `${USER_CENTER_PATH}/info`
          && p !== PUBLISH_PATH,
        path: '/merchant',
        title: '① 进入商家后台',
        description: '我们首先要进入商家后台，请点击这里。',
      },
      {
        element: '#tour-menu-add',
        at: (p) => p === USER_CENTER_PATH || p === `${USER_CENTER_PATH}/info`,
        path: USER_CENTER_PATH,
        title: '② 上架商品',
        description: '在这里您可以发布新的商品，点击进入。',
      },
      {
        element: '#tour-publish-title',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '③ 商品名称',
        description: '先给商品起个名字。',
      },
      {
        element: '#tour-publish-image',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '④ 商品主图',
        description: '上传商品主图。',
      },
      {
        element: '#tour-publish-submit',
        at: (p) => p === PUBLISH_PATH,
        path: PUBLISH_PATH,
        title: '⑤ 提交审核',
        description: '填好后点击提交，等审核通过，您就可以去秒杀报名了。',
      },
    ],
  },
  [SECKILL_FLOW_ID]: {
    id: SECKILL_FLOW_ID,
    merchantOnly: true,
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
  if (!parsed || !FLOWS[parsed.flowId] || !parsed.at || Date.now() - parsed.at > FLOW_TTL_MS) {
    clearTourFlow()
    return null
  }
  return parsed
}

export function clearTourFlow() {
  try {
    sessionStorage.removeItem(FLOW_KEY)
  } catch (e) { /* 无存储可用 ⇒ 本来也没有流程 */ }
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

function destroyTourDriver() {
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

  const total = flow.steps.length
  const last = index === total - 1
  destroyTourDriver()
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
  const flow = FLOWS[state.flowId]
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
  activePath = current

  // 已经有一条活着的引导（watcher 与 onMounted 同时触发）⇒ 不重复开。
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
 * 胶囊点击入口：决定从哪一步开始，必要时先导航。
 *
 * @param {string} flowId `PUBLISH_FLOW_ID` / `SECKILL_FLOW_ID`
 * @param {string} [path] 当前路由
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
  writeTourFlow(flow.id, index)
  activePath = current

  // 当前页没有起始锚点（例如从 `/product` 点的胶囊）⇒ 先去它应该在的页面。
  // 路由 watcher 会接着调 `resumeTourFlow`。
  const start = flow.steps[index]
  const target = normalizePath(start.path)
  if (!document.querySelector(start.element) && target && target !== current) {
    activePath = target
    navigate(target)
    return null
  }
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
