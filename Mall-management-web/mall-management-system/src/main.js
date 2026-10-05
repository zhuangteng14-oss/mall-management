import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'
import { http, setUnauthorizedHandler, resetUnauthorizedLatch } from './api/http'
import { endpoints } from './api/endpoints'
import { isLoggedIn, restoreFromSession } from './api/auth'

// ── 会话恢复（会话恢复笔，2026-10-05）───────────────────────────────────────────
// 价值面（**只有后者两档是本笔新增的**）：
//   ① 同一标签页刷新 —— sessionStorage 按标签页存活，**改前就不丢**，本笔不加价值；
//   ② 新标签页       —— storage 是空的，改前「认不得」；
//   ③ storage 被清   —— 同上。
// ⇒ ②③ 靠本函数：拿 **Cookie 会话**里的身份（`POST /user/me`）贴回 auth.js。
//
// ⚠️ 本请求**注定**会收到 401（匿名访客没有会话）⇒ 必须带 `allowUnauthorized: true`
//    旁路 401 出口，否则「谁都没登录」时一进站就被踢到 /login（首页本是**公开页**）。
// ⚠️ 超时 3000ms（**不是**默认的 10s）：本函数在挂载前 await，后端挂着时不该白屏 10 秒。
// ⚠️ **fail-open**：401 / 超时 / 网络错 / 非 JSON ⇒ 一律当「没有会话」，**静默**继续启动。
//    这里**不**调 `handleApiError` —— 启动期不该弹窗（且它已对 UnauthorizedError 提前 return）。
// ⚠️ 必须排在 `app.use(router)` **之前**：首次渲染就地读身份的地方
//    （`ProductPage.vue:148-152` 的 `buyerId`、`HomePage.vue:119` 的 `isLogin` computed）
//    在组件 setup 阶段把值**取一次就存下**，晚于挂载恢复就等于没恢复。
// ⚠️ 已知不覆盖：同一标签页里 storage 还在、但**服务端会话已过期**时，`isLoggedIn()` 为真
//    ⇒ 本函数直接返回、不做探针 ⇒ 要等某个业务请求 401 才会发现。这是**既有**行为，不在本笔。
async function restoreIdentity() {
  try {
    if (isLoggedIn()) return // 同一标签页已有身份 ⇒ 一次网络都不打
    const res = await http.post(endpoints.user.me, null, { allowUnauthorized: true, timeout: 3000 })
    const data = await res.json()
    if (data && data.success) restoreFromSession(data)
  } catch (e) {
    // 静默：无会话 / 超时 / 网络错 / 非 JSON，**以及 sessionStorage 不可用**
    // （`isLoggedIn()` 也在 try 内 ⇒ 浏览器禁存储时不会把 bootstrap 拖挂、导致整站白屏）
    // —— 一律当匿名，fail-open。
  }
}

async function bootstrap() {
  await restoreIdentity()

  const app = createApp(App)
  app.config.devtools = false
  app.use(router)

  // 鉴权笔 4：401 的**跳转动作**在此注册。
  // ⚠️ 为什么不在 http.js 里直接 `router.push`：`router/index.js → views/* → api/http.js` 会成环
  //    ⇒ http.js 只负责「清态 + 抛 UnauthorizedError + 喊一声」，「跳哪」由图这里的路由决定
  //    （路径只写在这一处，将来改路由不会漏改传输层）。
  setUnauthorizedHandler(() => { router.push('/login') })

  // 401 闩锁的**复位点 = 路由变化**：跳登录后闩锁置位（同一次会话只跳一次），
  // 用户再次导航时重新武装。⚠️ 不做成「任一 2xx 复位」—— 闸开时白名单端点（如 goodsSelectHome）
  // 照常 2xx，那样闩锁会被反复误复位、等于没有。
  router.afterEach(() => resetUnauthorizedLatch())

  router.isReady().then(() => {
    app.mount('#app')
  })
}

bootstrap()
