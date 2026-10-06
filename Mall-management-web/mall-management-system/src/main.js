import { createApp } from 'vue'
import App from './App.vue'; import './assets/fonts/noto.css'; import './assets/fonts/mono.css'; import './assets/tokens.css'   // 界面优化笔 A 笔（2026-10-06）：自托管字体（黑体/宋体/等宽）+ 设计 token。四条压一行是为保住本文件 `:21`/`:28`/`:49` 的行号（同行压缩沿用本文件既有做法，见 `:27`）
import router from './router/index.js'
import { http, setUnauthorizedHandler, resetUnauthorizedLatch, UnauthorizedError } from './api/http'
import { endpoints } from './api/endpoints'
import { isLoggedIn, restoreFromSession, adoptSharedToken, getToken, setToken, publishSharedToken, clearToken } from './api/auth'

// ── 会话恢复（会话恢复笔，2026-10-05）───────────────────────────────────────────
// 价值面（**只有后者两档是本笔新增的**）：
//   ① 同一标签页刷新 —— sessionStorage 按标签页存活，**改前就不丢**，本笔不加价值；
//   ② 新标签页       —— storage 是空的，改前「认不得」；
//   ③ storage 被清   —— 同上。
// ⇒ ②③ 靠本函数：拿 **Cookie 会话**或 **token** 里的身份（`POST /user/me`）贴回 auth.js。⚠️ 多账户隔离笔（2026-10-06）多做三件事：**认领** localStorage 指针（与第一行守卫同行）、cookie 恢复后**补一张本标签页专属的票**、管理员 cookie ⇒ **退回登录页** —— 落在下面两个新函数里。
//
// ⚠️ 本请求**注定**会收到 401（匿名访客没有会话）⇒ 必须带 `allowUnauthorized: true`
//    旁路 401 出口，否则「谁都没登录」时一进站就被踢到 /login（首页本是**公开页**）。
// ⚠️ 超时 3000ms（**不是**默认的 10s）：本函数在挂载前 await，后端挂着时不该白屏 10 秒。
// ⚠️ **fail-open**：401 / 超时 / 网络错 / 非 JSON ⇒ 一律当「没有会话」，**静默**继续启动。
//    这里**不**调 `handleApiError` —— 启动期不该弹窗（且它已对 UnauthorizedError 提前 return）。
// ⚠️ 必须排在 `app.use(router)` **之前**：首次渲染就地读身份的地方
//    （`ProductPage.vue:148-152` 的 `buyerId`、`HomePage.vue:119` 的 `isLogin` computed —— ⚠️ 后者已于 2026-10-06 删除、`:119` 现为按语，见 §5-AG (13)）
//    在组件 setup 阶段把值**取一次就存下**，晚于挂载恢复就等于没恢复。
// ⚠️ 已知不覆盖：同一标签页里 storage 还在、但**服务端会话已过期**时，`isLoggedIn()` 为真
//    ⇒ 本函数直接返回、不做探针 ⇒ 要等某个业务请求 401 才会发现。这是**既有**行为，不在本笔。
async function restoreIdentity() {
  try {
    if (isLoggedIn()) return null; adoptSharedToken() // 本页已有身份 ⇒ 一次网络都不打；否则先**认领** localStorage 指针（同行压缩是为保住 `:28`/`:49` 的行号）
    const res = await http.post(endpoints.user.me, null, { allowUnauthorized: true, timeout: 3000 })
    const data = await res.json()
    if (data && data.success) { restoreFromSession(data); return data } // ⚠️ 交回 body：调用方要判 role=3 与「要不要补票」
  } catch (e) {
    // 静默：无会话 / 超时 / 网络错 / 非 JSON，**以及 sessionStorage 不可用**（`isLoggedIn()` 也在 try 内 ⇒
    // 浏览器禁存储时不会把 bootstrap 拖挂、导致整站白屏）—— 一律当匿名，fail-open。**唯一例外**：
    if (e instanceof UnauthorizedError) clearToken() // 401 ⇒ 本地那张票已废，必须丢（后端**不回落 cookie**，留着它本页之后每发都 401）
  }
}

async function bootstrap() {
  const recovered = await restoreIdentity()

  const app = createApp(App)
  app.config.devtools = false
  app.use(router)

  // 鉴权笔 4：401 的**跳转动作**在此注册。
  // ⚠️ 为什么不在 http.js 里直接 `router.push`：`router/index.js → views/* → api/http.js` 会成环
  //    ⇒ http.js 只负责「清态 + 抛 UnauthorizedError + 喊一声」，「跳哪」由图这里的路由决定
  //    （路径只写在这一处，将来改路由不会漏改传输层）。
  setUnauthorizedHandler(() => { router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } }) })   // 前端路由守卫笔（2026-10-06）：带出事那一页的 fullPath ⇒ 与守卫同一条契约（`LoginPage.landingAfterLogin` 消费）。⚠️ 此刻 `handleUnauthorized` 尚未抛错，`currentRoute` 仍是用户所在页（`http.js` 的注释已记该时机）

  // 401 闩锁的**复位点 = 路由变化**：跳登录后闩锁置位（同一次会话只跳一次），
  // 用户再次导航时重新武装。⚠️ 不做成「任一 2xx 复位」—— 闸开时白名单端点（如 goodsSelectHome）
  // 照常 2xx，那样闩锁会被反复误复位、等于没有。
  router.afterEach(() => resetUnauthorizedLatch())

  // 多账户隔离笔（2026-10-06）：恢复结果的三档收口（认领/补票/退回登录页）—— 见 `settleRecoveredIdentity`。
  // ⚠️ 必须在 `router.isReady()` **之前**：管理员那一档要在首帧之前把落点定成 /login，否则已挂载的壳会先渲染一次。
  if (await settleRecoveredIdentity(recovered)) await router.replace('/login')
  router.isReady().then(() => {
    app.mount('#app')
  })
}

bootstrap()

// ── 多账户隔离笔（2026-10-06）：恢复态的三档收口 ─────────────────────────────────
// 输入 = `restoreIdentity()` 的返回值（`/user/me` 的 body，或 `undefined`＝没恢复成）。
// 三档（**只在「本标签页原本没有身份」时才走到这里** —— 见 `restoreIdentity` 的第一行守卫）：
//   ① 手里**没有票**、而 cookie 会话是**管理员**（`role === 3`）⇒ 返回 `true`，调用方把落点改成 /login。
//      依据：管理员身份只能由 token 承载（后端 `AuthSession.authorize` 对 role=3 的 cookie 一律不授权）
//      ⇒ 留在页面上只会让之后每个业务请求都 401，不如让他显式登录一次。
//      ⚠️ 用**识别**口径的那一发 `/user/me` 才看得到这个 `role` —— 它答的是降级形状 `{success:true, role:3}`，
//         **没有资料字段**；这正是后端 `AUTH_IDENTITY_PATHS` 那一档存在的理由。
//   ② 恢复了身份（cookie 档）但**手里没有票** ⇒ 立刻铸一张**本标签页专属**的票并发布指针（`role ≠ 3`）
//      —— 这是隔离的落点：不补票的话，本页此后**一直**靠 cookie 认身份，别的标签页一登录就把它顶掉。
//   ③ 其余（没恢复成 / 本就是匿名）⇒ 什么都不做。
// ⚠️ 全程 fail-open：铸票失败**不阻断**启动（没票 = 退回 cookie 身份，与改前同形）。
async function settleRecoveredIdentity(recovered) {
  if (!recovered || !recovered.success) return false
  if (!getToken() && recovered.role === 3) return true
  if (!getToken()) await mintForThisTab(recovered.role)
  return false
}

/** cookie 恢复档补票（只被 `settleRecoveredIdentity` ② 调用）。
 *  ⚠️ **管理员永不发布指针**（用户裁决 D6）：本档取不到 `role=3`（那一档已在 ① 返回了），判据仍写全，
 *  免得将来放宽 ① 时**静默**把管理员指针发布出去。
 *  ⚠️ 带 `allowUnauthorized`：票铸不出来时（会话正好失效）要**静默**收场 —— 走 401 出口会去跳登录，
 *  而匿名启动是合法状态，不该被一次补票失败踢走。 */
async function mintForThisTab(role) {
  try {
    const res = await http.post(endpoints.user.token, null, { allowUnauthorized: true, timeout: 3000 })
    const data = await res.json()
    if (data && data.token) {
      setToken(data.token)
      if (role !== 3) publishSharedToken(data.token)
    }
  } catch (e) { /* fail-open：没票就退回 cookie 身份，等同改前 */ }
}
