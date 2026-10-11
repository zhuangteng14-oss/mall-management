import { createApp } from 'vue'
import AdminApp from './AdminApp.vue'; import './assets/fonts/noto.css'; import './assets/fonts/mono.css'; import './assets/tokens.css'   // 四条压一行：与 `main.js:2` 同款做法（那行是「为保住本文件行号」的先例）。字体/ token 必须由**本入口自己**引：多入口下 CSS 是随 entry 走的
import router from './router/index.js'
import { http, setUnauthorizedHandler, resetUnauthorizedLatch } from './api/http'
import { endpoints } from './api/endpoints'
import { isLoggedIn, restoreFromSession, dropPageToken } from './api/auth'

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
//      ⚠️ **补丁「丁」（2026-10-10）在此之上再加半条：不但不发布、不认领，还要**弃掉本页已有的票****
//      （`dropPageToken()`，见 `bootstrap()` 第一行）—— 理由见 `api/auth.js` 那个函数的块注释。
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
  // ── C3 块 2 手验后补丁「丁」（2026-10-10，用户裁决）：**管理端启动第一件事 = 弃掉本页的票** ──────
  // 由来与依据写在 `api/auth.js` 的 `dropPageToken()` 块注释里，一句话：**管理端的票只可能是别人的**
  // （管理员铸不出票 TD-18、也不发布指针 D6，认领本入口早已不做）⇒ 带着它，后端 `AuthSession.identify`
  // **票优先** ⇒ 认出的是上一次登录的客户/商家：收件箱 403（`'*'` 比不过他的业务号）+ `admin_audit`
  // 记错人（**实测** a3 库 Id=22 把管理端的 `user.ban` 记成客户 1111 的 `uDopHnCeLS`/role=1）。
  // ⚠️ **必须在 `restoreIdentity()` 之前**：那一发 `/user/me` 也会带票。
  // ⚠️ 弃票后管理端靠 **cookie 会话**认身份 ⇒ 闸关档（a3）下 `identify` 认得出 `role=3`、
  //    `IdentityGuard` 的管理员档放行、审计记 role=3。🔴 **闸开的档（默认 profile）本入口仍无凭据**
  //    ⇒ 全部 401 —— 那是 TD-18 的缺口、本补丁**不假装治它**（见 slot-registry §十三 13-5 与新 TD）。
  dropPageToken()

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

  router.isReady().then(() => { app.mount('#app') }, () => { app.mount('#app') })   // ⚠️ **第二参是 2026-10-11（问题①）新增的失败档**：`isReady()` 在首航**失败**时是 **reject** ⇒ 原来那个 `.then` 永不执行 ⇒ `app.mount` 永不发生 ⇒ `#app` 一直空着，而 `data-theme="admin"` 把底色涂成 `#101828` ⇒ **整页深色、无内容**（本日实测的正是这一档：`api/auth.js` 的 `restoreFromSession` 把 `/user/me` 的数字 `role` 原样存下，守卫 2 字符串比不中 ⇒ 重定向成环 ⇒ 首航失败）。归一那处已修掉环；这一参是**兜底**：真出了别的首航失败，也让 `AdminApp` 挂上去（它的 `matched.length === 0` 分支会给出可读的面板），而不是留一块不说话的深色。⚠️ 同行追加，零行号漂移（本文件的 `:55-60` / `:64-66` 被自身注释按行号互引）
}

bootstrap()
