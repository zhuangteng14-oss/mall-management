import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'
import { setUnauthorizedHandler, resetUnauthorizedLatch } from './api/http'

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