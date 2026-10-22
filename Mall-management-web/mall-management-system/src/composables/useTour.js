// src/composables/useTour.js —— 跨页漫游引导（driver.js，2026-10-21）
//
// 两件事：
//   ① 「一键带我去开店」点了之后，**先记下待办的 tour**，再跳申请页；申请页 DOM 渲染完才开导览。
//      待办存 sessionStorage（刷新 / 登录跳转都还在），TTL 15 分钟（评审 P1-4：5 → 15）。
//   ② `waitForElement` 不只看「查询到没有」，还要**看得见**（P1-1）：
//      `offsetParent !== null` + `getBoundingClientRect().width > 0` —— KeepAlive 缓存页的
//      隐藏 DOM 也在文档里，只 `querySelector` 会对着 0×0 的元素开导览。
//
// ⚠️ driver.js 走**动态 import**：不打进首屏 chunk，聊天球打开 / 点按钮时才拉。
import { nextTick } from 'vue'
import { getRole, isLoggedIn } from '../api/auth'

const PENDING_KEY = 'mall.chat.pendingTour.v1'
const TTL_MS = 15 * 60 * 1000

const APPLY_PATH = { customer: '/customer/user/apply', merchant: '/merchant/user/apply' }
const ORDERS_PATH = { customer: '/customer/orders', merchant: '/merchant/orders' }
/** 商家秒杀报名页（N3 / Q5=A：只导航，不写库）。 */
const SECKILL_PATH = '/merchant/seckill'
/** 商家「上架商品」页（阶段一：need_publish=true 时的第一个胶囊）。 */
const PUBLISH_PATH = '/merchant/user/add'

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

/**
 * 登录回来后按**真实角色**续接待办：
 * · 开店待办 ⇒ 对应角色的申请页；
 * · 秒杀待办 ⇒ 只有商家能回 `/merchant/seckill`；客户返回 null，由 ChatWidget 重问一句
 *   「我要报名秒杀」，让后端按登录后的身份重新分支（Q5 的 ANONYMOUS 行）。
 */
export function resumePathAfterLogin(currentPath) {
  if (!isLoggedIn()) return null
  const pending = readPendingTour()
  if (!pending) return null
  if (pending.routeKey === 'seckill' && !isMerchantRole()) return null
  const target = pending.routeKey === 'seckill' ? seckillPath() : applyPath()
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

/** 让 driver 的浮层主题跟项目 token 对齐。 */
const POPOVER_CLASS = 'chat-tour-popover'

export async function startOpenStoreTour({ onDone } = {}) {
  // 先等两帧：路由切换后目标页可能刚挂载（KeepAlive 激活也走这条）。
  await nextTick()
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))

  const timeout = import.meta.env.DEV ? 8000 : 3000
  const shopName = await waitForElement('#tour-shop-name', timeout)
  const submit = await waitForElement('#tour-submit', timeout)
  if (!shopName || !submit) {
    clearPendingTour()
    onDone?.(false)
    return null
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
    steps: [
      {
        element: '#tour-shop-name',
        popover: {
          title: '① 给店铺起个名字',
          description: '这个名字会展示给买家（例如「米可数码专营店」），之后可以改。',
        },
      },
      {
        element: '#tour-submit',
        popover: {
          title: '② 提交入驻申请',
          description: '填完店铺名称和营业执照号后点这里。平台审核通过，当前账号就自动升级为商家。',
        },
      },
    ],
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
