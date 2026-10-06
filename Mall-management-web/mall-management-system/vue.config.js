const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  transpileDependencies: true,
  chainWebpack: (config) => {
    // 界面优化笔 A 笔（2026-10-06）：自托管 CJK 字体**禁 base64 内联**。
    // 初测：webpack 默认把小 woff2 转成 data URI 塞进 CSS —— 实测内联 10 个分片
    // （字体内容 49.2 KiB ⇒ base64 后 65.6 KiB），这 65.6 KiB 每个用户首屏都要下载并解析，
    // 而分片字体本意是「按 unicode-range 按需取」。设为 0 ⇒ 202 个分片全部落盘 /fonts/，
    // 浏览器只取页面上真的用到的那几片。零新依赖、只影响字体资产的落地方式。
    config.module.rule('fonts').set('parser', { dataUrlCondition: { maxSize: 0 } })
  },
  devServer: {
    allowedHosts: 'all',
    client: {
      overlay: false   
    }
  }
})