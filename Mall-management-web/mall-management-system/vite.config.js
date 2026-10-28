import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// ── Vite 迁移（2026-10-20，用户令：替换 Vue CLI）──────────────────────────────────
// 与旧 `vue.config.js` 的对应关系：
//   · `pages.index` / `pages.admin`  → 根目录两个 HTML 入口 + `build.rollupOptions.input`；
//   · `devServer.port = 8080`        → `server.port`（strictPort：被占用就报错，不静默换端口，
//                                      否则会落到 CORS 白名单之外的端口）；
//   · 字体禁 base64 的 `fonts` rule  → `build.assetsInlineLimit: 0`（见下）。
//
// ⚠️ 开发期走**本地代理**：`.env.development` 把基址留空 ⇒ 请求是相对路径 ⇒ 由本文件的
//    `server.proxy` 转给 8088。这样浏览器看到的是同源请求，不再依赖后端 CORS 白名单，
//    带 cookie 的登录链也不会被跨源策略咬。
// ⚠️ 代理键用**正则**而不是裸前缀：`/admin` 直接当键会把 `/admin.html` 也转发到后端
//    （Vite 的前缀匹配不区分「路径段」与「文件名前缀」），管理端入口会当场 404。
const BACKEND = 'http://localhost:8088'

// 后端接口的顶层路径段（来源：src/api/endpoints.js 的取值 + 基础设施端点）。
// ⚠️ 不把 `/login` / `/register` / `/product` 等**前端路由**放进来：hash 路由下它们根本不会
//    变成 HTTP 路径，混进来只会给将来留一条「页面被代理走」的隐患。
const API_SEGMENTS = [
  'user', 'goods', 'order', 'merchant', 'admin', 'message', 'comment', 'report',
  'sensitive', 'notification', 'favorite', 'cart', 'shop', 'catalog', 'template',
  'marketing', 'member', 'wallet', 'img', 'observe', 'health', 'stats', 'attack',
  'demo-stream', 'seckill', 'subsidy', 'coupon', 'address', 'api', 'chat',
]

const proxy = Object.fromEntries(
  API_SEGMENTS.map((segment) => [
    `^/${segment}(?:/|$)`,
    { target: BACKEND, changeOrigin: true },
  ])
)

export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    host: '0.0.0.0',
    port: 8080,          // 与旧 dev server 同端口：CORS 白名单、探针与书签都不必改
    strictPort: true,    // 端口被占用时直接失败 —— 静默落到 5173 会绕过白名单
    allowedHosts: true,
    proxy,
  },

  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 旧 `chainWebpack` 把字体 rule 的 dataUrlCondition 设为 0（202 个 CJK 分片全部落盘，
    // 让 unicode-range 按需取）。Vite 没有按类型分开的开关 ⇒ 全量关掉内联，口径一致。
    assetsInlineLimit: 0,
    rollupOptions: {
      input: {
        index: fileURLToPath(new URL('./index.html', import.meta.url)),
        admin: fileURLToPath(new URL('./admin.html', import.meta.url)),
      },
    },
  },
})
