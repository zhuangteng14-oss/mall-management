const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  transpileDependencies: true,
  // ── C3 骨架第一笔（2026-10-08）：多入口（**乙方案**）─────────────────────────────
  // 为什么是 `pages` 而不是另起一个 devServer：两个入口共用**同一台** dev server、
  // 同一个 8080 端口 ⇒ `localhost:8080/` 仍是商城，`localhost:8080/admin.html` 是管理端。
  //
  // ⚠️ 一旦写了 `pages`，**两个入口都得显式列全** —— vue-cli 不会再补那条默认项。
  // 故下面 `index` 的四项必须**逐字等于**原默认值，否则商城入口会被改坏：
  //   entry    = `src/main.js`        （vue-cli 默认）
  //   template = `public/index.html`  （vue-cli 默认）
  //   filename = `index.html`         （vue-cli 默认）
  //   title    = `mall-management-system`（= package.json 的 name）
  //   ⚠️ title 不是猜的：已构建产物 `dist/index.html` 的 `<title>` 逐字如此（2026-10-08 实读）。
  pages: {
    index: {
      entry: 'src/main.js',
      template: 'public/index.html',
      filename: 'index.html',
      title: 'mall-management-system',
    },
    admin: {
      entry: 'src/main-admin.js',
      template: 'public/admin.html',
      filename: 'admin.html',
      title: '管理端 · Mall Management',
    },
  },
  chainWebpack: (config) => {
    // 界面优化笔 A 笔（2026-10-06）：自托管 CJK 字体**禁 base64 内联**。
    // 初测：webpack 默认把小 woff2 转成 data URI 塞进 CSS —— 实测内联 10 个分片
    // （字体内容 49.2 KiB ⇒ base64 后 65.6 KiB），这 65.6 KiB 每个用户首屏都要下载并解析，
    // 而分片字体本意是「按 unicode-range 按需取」。设为 0 ⇒ 202 个分片全部落盘 /fonts/，
    // 浏览器只取页面上真的用到的那几片。零新依赖、只影响字体资产的落地方式。
    config.module.rule('fonts').set('parser', { dataUrlCondition: { maxSize: 0 } })
  },
  devServer: {
    port: 8080,   // ← C3（2026-10-08）：**显式钉住**。两入口同端口、不新开（用户口径）。
                  //   vue-cli 默认本来就是 8080，本行只是把它写死 —— 防 `--port` / 环境变量改走，
                  //   也防将来有人看到一个“空”的 devServer 块就随手加端口。
    allowedHosts: 'all',
    client: {
      overlay: false   
    }
  }
})