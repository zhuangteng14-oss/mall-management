// src/api/http.js —— 前端 API 层「传输层」唯一出口
//
// 职责边界（只做三件事）：
//   ① 拼装请求：base URL 前缀 / Content-Type / body 形态 / **凭据**（cookie 用 `credentials: 'include'`、token 用 `Authorization` 头 —— 都在 `buildInit`）
//   ② 判定传输层结果：超时 / 非 2xx（→ 抛类型化错误）
//   ③ **401 出口**（鉴权笔 4）：清本地登录态 + 触发**已注册**的跳转。
//      ⚠️ **动作本体不在本层** —— 本层只 `clearAuth()` + 喊一声；「跳哪」由 `main.js` 决定
//      （本层**不能** import router：`router → views → http` 会成环，见 `setUnauthorizedHandler`）。
//      ⚠️ 可**按次旁路**：`{ allowUnauthorized: true }` —— **今日 7 处**：会话恢复笔的启动探针（注定收到 401）
//      + P2.6 的三个资料读点（`LoginPage.vue` / `GoodsUp.vue` / `UserInfoPage.vue`）+ 翻开关笔的退出销会话 + 多账户隔离笔的 2 处铸票。**不是**全局开关。
//
// ⚠️ 本层**不判业务成功**。`body.success` 的语义各页不同（有的弹 msg、有的静默、
//    有的走另一分支），统一判 = 一次性改 34 处行为 ⇒ 留在各 view 自己判。
//    本层返回的是**未解析的 Response**，调用方照旧 `await res.json()`。
//
// 基址来源：`.env.development` / `.env.production` 的 `VUE_APP_API_BASE`（Vue CLI 构建期内联）。
//
// ⚠️ 401 出口（职责③）要清本地登录态 ⇒ 依赖 `auth.js`。`auth.js` 自身**无任何 import**
//    ⇒ 反向依赖不存在，不成环（这与 router 的情况不同 —— 见职责③的注释）。
import { clear as clearAuth, getToken } from './auth'

const API_BASE = process.env.VUE_APP_API_BASE || ''

// 静态资源（图片）前缀。⚠️ 与 API_BASE **分开命名** —— 反代可能给静态资源不同前缀。
const ASSET_BASE = process.env.VUE_APP_ASSET_BASE || ''

// ⚠️ 超时不能设小：后端 Redis 黑洞降级窗口的「首个冷读」实测 **3.376s / 3.40s / 3.38s**
//    （`context-packs/cache-layer.md#pack-cache-layer-9-1`，2026-09-28），该耗时**整个落在 HTTP 请求里**
//    ⇒ 设 3s 会误杀正常请求。10s = 覆盖最坏冷读 + 余量。
const DEFAULT_TIMEOUT = 10000
// 带 FormData 的上传（图片体积不可控）。
const UPLOAD_TIMEOUT = 30000

/** 非 2xx。`status` / `body` 留给调用方与鉴权笔使用。 */
export class HttpError extends Error {
  constructor(status, body, url) {
    super(`HTTP ${status} ${url}`)
    this.name = 'HttpError'
    this.status = status
    this.body = body
    this.url = url
  }
}

/** 401 —— 比 `HttpError` 多一层语义：**本地登录态已被清、且已触发跳转**。
 *
 *  ⚠️ **判 `status` 而不是 `body`**：闸的 401 与 `/user/me` 的 401 **响应体逐字节相同**
 *     （共享常量 `AuthInterceptor.BODY_NOT_LOGGED_IN`；真容器里 `cmp` 实测 IDENTICAL，
 *     见 `progress.md §5-R (2)` 第 10 条）⇒ 判 body 等于判「字符串相等」，不增加信息；
 *     且后端明写 `Result` 的键序**不承诺**（`UserController.java:228`）⇒ body 判据不可靠。
 *
 *  它**继承 `HttpError`** ⇒ 所有 `instanceof HttpError` 的既有调用方行为不变。
 */
export class UnauthorizedError extends HttpError {
  constructor(body, url) {
    super(401, body, url)
    this.name = 'UnauthorizedError'
  }
}

// ── 401 出口（鉴权笔 4）────────────────────────────────────────────────────────

/** 跳转动作的**注册点** —— 由 `src/main.js` 提供。
 *  ⚠️ 本层**不能** import router（`router/index.js → views/* → api/http.js` 会成环）；
 *     也**不硬编码** `#/login`（路由路径改了这里会坏）。⇒ 只留一个口子，「跳哪」由 main.js 定。
 *  ⚠️ **未注册时不跳转、只清态** —— 保证本层永远不猜路由。 */
let onUnauthorized = null
export function setUnauthorizedHandler(fn) { onUnauthorized = fn }

/** 一次性闩锁：**同一次会话只处理一次 401**。挡两个来源：
 *    ① **并发突发** —— 一次挂载里可有多处请求同时 401（如 `ProductPage` 挂载打 7 处）；
 *    ② **轮询** —— `ChatRoom.pollUpdates` 2s 一轮、`AppShell.fetchUnreadCount`（R6 前在两个壳里）
 *       5s 一轮 ⇒ 不挡就是 **12+ 次/分钟**的跳转 + 清态风暴。
 *  ⚠️ 复位**不做成「任一 2xx」**：闸开时白名单端点（如 `goodsSelectHome`）照常 2xx，
 *     那样闩锁会被反复误复位、等于没有。复位点 = **路由变化**（main.js 的 `router.afterEach`）。 */
let unauthorizedHandled = false
export function resetUnauthorizedLatch() { unauthorizedHandled = false }

/** 出口本体。⚠️ **只有一个调用点**（`request` 的 401 分支），且带 `allowUnauthorized` 的条件
 *  —— 别在别处直接调它：绕开条件就等于恢复了「匿名访客一进站被踢」的老问题。 */
function handleUnauthorized() {
  if (unauthorizedHandled) return
  unauthorizedHandled = true
  clearAuth()
  if (onUnauthorized) onUnauthorized()
}

/** 超时（AbortController 触发）。 */
export class TimeoutError extends Error {
  constructor(ms, url) {
    super(`请求超时（${ms}ms）${url}`)
    this.name = 'TimeoutError'
    this.ms = ms
    this.url = url
  }
}

/** 相对路径 → 绝对 URL。已是绝对 URL 则原样返回（供「URL 变量」类调用点使用）。 */
export function urlOf(path) {
  return /^https?:\/\//.test(path) ? path : API_BASE + path
}

/** 静态资源路径 → 绝对 URL。⚠️ 已是绝对 URL 则原样返回（与 `urlOf` 同口径）—— `comment_image.ImageUrl` 存的就是绝对地址，裸拼前缀会拼出 `http://localhost:8088http://…`（管理端「待审图片」破图即此）。 */
export function assetOf(path) {
  return /^https?:\/\//.test(path) ? path : ASSET_BASE + path
}

/**
 * 组装 fetch init。三种 body 形态与改前**逐字一致**：
 *   FormData                 → 不设 Content-Type（浏览器自带 boundary）
 *   URLSearchParams / string → application/x-www-form-urlencoded
 *   POST 无 body             → 仍带 application/x-www-form-urlencoded（改前 2 处即如此）
 *   GET  无 body             → **不带任何头**（改前唯一那处 GET 是裸 `fetch(url)`）
 *
 * ⚠️ 头之所以要看 method：改前 41 处 POST **全部**带 urlencoded 头（含 2 处无 body 的
 *    `goodsSelectHome` / `goodstype`），而唯一的 GET（`GoodsAdd.loadGoodsType`）是裸
 *    `fetch(url)`、一个头都没有。「无 body 也带头」这条规则不能直接套到 GET 上 ——
 *    本笔自查时旧写法就给那个 GET 多送了一个 Content-Type（后端 `@GetMapping` 无
 *    `@RequestBody`，实际无影响，但违反「搬家 = 逐字不变」）。
 *
 * ⚠️ **2026-10-05 落地（鉴权笔 4）**：BD-03 选 A ⇒ 加 `credentials: 'include'`。
 *    「**一行覆盖 42/42**」的依据：全 `src/` **只有本文件一处 `fetch(`**（`grep -rn "fetch(" src/`
 *    只命中本文件的调用行；另两处在注释里）⇒ 42 处 API 调用**全部**经 `http.post/get`。
 *    含那 3 处「变量 URL」（`GoodsSelect.vue:92`（R2 重指；R1 记 `:91`、原记 `:81`）/ `OrderCenter.vue:129` / `FriendRoom.vue:231`）——
 *    它们 `urlOf(...)` 之后同样交给 `http.post`，**没有**绕过本层。
 *    后端侧已现成（⚠️ 2026-10-06 CORS 收窄笔起 = **白名单化**，见下行按语）：`ImgConfig` 的 CorsFilter = `setAllowedOrigins(app.cors.allowed-origins)` +
 *    `allowCredentials(true)` ⇒ Spring **回显具体 origin**（不是 `*`），浏览器接受带凭据的跨源请求；⚠️ **按语（CORS 收窄笔，2026-10-06，§5-AH）**：本行原写「`addAllowedOriginPattern("*")`」——**那个通配已被收窄**成字面量白名单（dev = `http://localhost:8080` + `http://127.0.0.1:8080`，prod 用 `APP_CORS_ALLOWED_ORIGINS` **整体替换**），但**结论不变**（仍回显具体 origin、仍允许凭据）⇒ dev 的 8080 → 8088 跨源与登录态流程**不受影响**；非白名单 origin 现在**被服务端当场拒**（403 + 零个 `Access-Control-*`，实测见 `docs/agent/progress.md` §5-AH (7) B1 档）—— **不是**「服务端照常处理、只靠浏览器拦」。
 *    真容器实测见 `progress.md §5-R (2)` 的 [12] / [13]。
 *    ⚠️ **2026-10-06（多账户隔离笔）已在此加 `Authorization: Bearer <token>`**（就在本函数体末尾）——
 *    这不是「改选 B」：票**由服务端签发**（`POST /user/token`），前端只负责携带，见 `auth.js` 末尾那节。
 */
function buildInit(method, body) {
  const init = { method, credentials: 'include' }
  if (body instanceof FormData) {
    init.body = body
  } else {
    const hasBody = body !== undefined && body !== null
    if (method === 'POST' || hasBody) {
      init.headers = { 'Content-Type': 'application/x-www-form-urlencoded' }
    }
    if (hasBody) init.body = body
  }
  // ⚠️ 多账户隔离笔（2026-10-06）：有票就带上（`Authorization: Bearer <token>`）。这一句让
  //    「42/42 处 API 调用全都自动带票」—— 与 `credentials: 'include'` 同一手法（都收在本函数里）。
  //    ⚠️ 有票时，**GET 无 body 那条路径不再「一个头都不带」**：它带且**只带**这一个头
  //       （上面的 Content-Type 仍按原判据只发给 POST / 带 body 者）⇒ 不与「GET 不加内容头」那条判据冲突。
  //    ⚠️ 票要 **`getToken()` 现取**、不做缓存：登录 / 认领 / 登出都可能在两次请求之间改它。
  //    ⚠️ 头值逐字 `Bearer ` + 票（后端 `AuthTokenStore.BEARER` 大小写敏感，别改成小写）。
  const token = getToken()
  if (token) init.headers = { ...(init.headers || {}), Authorization: `Bearer ${token}` }
  return init
}

async function request(path, { method = 'GET', body, timeout, allowUnauthorized = false } = {}) {
  const url = urlOf(path)
  const ms = timeout || (body instanceof FormData ? UPLOAD_TIMEOUT : DEFAULT_TIMEOUT)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ms)
  try {
    const res = await fetch(url, { ...buildInit(method, body), signal: controller.signal })

    // ⚠️ 非 2xx 判定（P1.1）。改前非 2xx 也被当**正常响应**，靠 `data.success` 兜 ⇒
    //    笔 3（PR #36）起后端对校验失败返回的 400（带 JSON body）**对用户完全不可见**。
    if (!res.ok) {
      let errBody = null
      try { errBody = await res.json() } catch (e) { /* 非 JSON body（如 404 静态资源） */ }
      // 401 出口（鉴权笔 4）—— 判 **status**，不判 body（理由见 UnauthorizedError 的注释）。
      // ⚠️ 仍然**抛**（不吞）：`progress.md §6-A P1.2` 写死「失败路径必须继续抛异常落 catch」，
      //    吞掉会让调用方拿不到东西却继续往下跑。本类另有一条：403 ⇒ 提示无权限（本笔不做）。
      if (res.status === 401) {
        // ⚠️ `allowUnauthorized`（会话恢复笔）：**单次调用级**旁路，不做全局开关。
        //    用处只有一个 —— 启动探针 `POST /user/me`（`main.js`）：匿名访客**注定**收到 401，
        //    若照常触发出口，谁都没登录时一进站就被踢到 /login（首页本是公开页）。
        //    ⚠️ 旁路时**不置闩锁**（连 `handleUnauthorized` 都不进）⇒ 不影响正常流的一次性语义。
        //    ⚠️ **仍然照抛**：交调用方自己判「无会话」。本函数不吞异常。
        if (!allowUnauthorized) handleUnauthorized()
        throw new UnauthorizedError(errBody, url)
      }
      throw new HttpError(res.status, errBody, url)
    }
    return res
  } catch (e) {
    if (e.name === 'AbortError') throw new TimeoutError(ms, url)
    throw e
  } finally {
    clearTimeout(timer)
  }
}

export function get(path, opts) {
  return request(path, { ...opts, method: 'GET' })
}

export function post(path, body, opts) {
  return request(path, { ...opts, method: 'POST', body })
}

/**
 * 命名空间出口 —— view 统一 `import { http } from '../api/http'` 后写 `http.post(...)`。
 * ⚠️ 漏掉这一行 = 全站每个请求都变成 `undefined.post`，而 webpack 只给 **warning**、
 *    构建**退出码仍是 0** ⇒ 本笔实际踩到，靠 build 日志里的
 *    `export 'http' was not found` 才逮住。改动本文件时务必保留。
 */
export const http = { get, post }

/**
 * view 的统一 catch 出口（P2.3）。
 * 改前 14 个 view 共 41 个 catch 块，各自 `console.error/console.log/alert`，文案散且不统一。
 *
 * ⚠️ 只接「原先就只写日志」的那类 catch —— 实测 **22 处**。改前就在**弹提示**或
 *    **改组件状态**的另 19 处一律没动 —— 那些是业务分支，不是日志出口（换掉 = 改行为）。
 *
 * ⚠️ 非 2xx（HttpError）自 P1.1 起**对用户可见**：改前这类响应被当正常响应静默处理。
 *    注意本函数**不对 TimeoutError 弹窗**（超时）—— 否则轮询类 catch
 *    的网络抖动会变成弹窗风暴。
 * ⚠️ **也不对 UnauthorizedError 弹窗**（鉴权笔 4 起）：401 已由「401 出口」统一处理
 *    （清态 + 跳登录），再逐处弹就是「同一次失效弹 N 次」。它与轮询风暴是**两个独立来源** ——
 *    闩锁只挡住跳转与清态，挡不住这里的弹窗。
 * ⚠️ **已知残留**：并非所有 catch 都走本函数 —— 有一批直接 `alert(...)` / 只 `console.error`，
 *    那些**本笔管不到**（登记在 `progress.md §5-S`）。
 */
export function handleApiError(e, msg) {
  const label = msg || '请求失败'
  console.error(label, e)
  // ⚠️ 顺序要紧：UnauthorizedError **继承** HttpError ⇒ 必须先判子类。
  if (e instanceof UnauthorizedError) return
  if (e instanceof HttpError) {
    alert(`${label}（HTTP ${e.status}）`)
  }
}
