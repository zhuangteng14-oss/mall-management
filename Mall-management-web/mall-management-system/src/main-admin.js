import { createApp } from 'vue'
import AdminApp from './AdminApp.vue'; import './assets/fonts/noto.css'; import './assets/fonts/mono.css'; import './assets/tokens.css'   // 四条压一行：与 `main.js:2` 同款做法（那行是「为保住本文件行号」的先例）。字体/ token 必须由**本入口自己**引：多入口下 CSS 是随 entry 走的
import router from './router/index.js'
import { http, setUnauthorizedHandler, resetUnauthorizedLatch } from './api/http'
import { endpoints } from './api/endpoints'
import { isLoggedIn, restoreFromSession } from './api/auth'

// ── 管理端入口（C3 骨架第一笔，2026-10-08）──────────────────────────────────────
// 与 `main.js` **同构但有意更薄**。骨架这笔只做四件事：恢复身份 → 挂路由 → 注册 401 出口
//   → 把「无 hash 直开」定到 `/admin`。业务域 / 风控域**不在本笔**。
//
// ⚠️ **有意少做的三件（与 `main.js` 的差异，逐条写明原因，别当成漏写）**：
//   ① **不做 `settleRecoveredIdentity` 三档收口**（`main.js:78-83`）。那一档的 ① 会把
//      「cookie 管理员且无票」退回 `/login`，而管理员的**铸票路径尚未定案**（`/user/token`
//      的 javadoc 明文禁止 cookie 管理员换票，是刻意的安全属性 —— 见 slot-registry §十三 13-5）。
//      照抄它 = 管理端**每次刷新都弹回登录页**。骨架阶段改由 `/admin` 的守卫兜可达性：
//      未登录 ⇒ `/login?redirect=/admin`，登录后 `landingAfterLogin` 回原目标。
//      ⇒ 待铸造路径定案后，本文件应与 `main.js` 合并这段（登记在 §十三 13-5）。
//   ② **不注册 `publishSharedToken` 那套多账户隔离**：骨架无业务请求，票无从谈起。
//   ③ **不 await 恢复后再 `settleRecoveredIdentity`**：同上。
//
// ⚠️ 恢复身份这段是 `main.js:25-36` 的**精简复制**（少了 `adoptSharedToken()` 与
//    `UnauthorizedError ⇒ clearToken()` 两条 —— 都属多账户隔离那套）。复制而非提取，
//    是因为提取要动 `main.js`（最大被引行 `:49`，删 12 行必漂移）；等②定案时一并收敛。
async function restoreIdentity() {
  try {
    if (isLoggedIn()) return
    const res = await http.post(endpoints.user.me, null, { allowUnauthorized: true, timeout: 3000 })
    const data = await res.json()
    if (data && data.success) restoreFromSession(data)
  } catch (e) {
    // fail-open：无会话 / 超时 / 网络错 / 非 JSON ⇒ 一律当匿名（与 `main.js` 同口径，静默继续启动）
  }
}

async function bootstrap() {
  await restoreIdentity()

  const app = createApp(AdminApp)
  app.config.devtools = false

  // ⚠️ 「无 hash 直开 ⇒ 定到 #/admin」**不在本文件做** —— 已挪到 `public/admin.html` 的
  //    `<head>` 内联脚本。原因（实测，2026-10-08）：`createWebHashHistory()` 在**模块求值那一刻**
  //    就把 `location` 抓成了字符串，而本文件顶部的 `import router` 早于 `bootstrap()` ⇒ 在这里
  //    `replaceState` **来不及**：首航读到的 hash 仍是空的 ⇒ `{ path:'/', redirect: landingFor }`
  //    ⇒ 未登录 ⇒ 落 `#/customer`（商城客户页被渲进管理端入口）。
  //    ⇒ 这段**不能**搬回来，除非把 `import router` 改成动态 `await import()`。

  app.use(router)

  // 401 出口：与 `main.js:49` 是**同一条契约**（带出事那一页的 fullPath，`LoginPage.landingAfterLogin` 消费）。
  setUnauthorizedHandler(() => { router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } }) })
  router.afterEach(() => resetUnauthorizedLatch())

  router.isReady().then(() => { app.mount('#app') })
}

bootstrap()
