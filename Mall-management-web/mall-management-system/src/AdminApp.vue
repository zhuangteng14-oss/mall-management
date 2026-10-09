<template>
  <div class="admin-app">
    <router-view />
  </div>
</template>

<!--
  管理端根组件（C3 骨架第一笔，2026-10-08）。
  ⚠️ 与商城的 `App.vue` 同构（都只是 `<router-view>` 的宿主），差异只有一处：
     **这里不重复 `id="app"`** —— `admin.html` 已经有一个挂载点 `#app`，
     `App.vue` 那个同名内层 div 会产生两个同 id 元素（历史遗留），新入口不复制它。
  页面底色/文字色在这儿落（而不是放进 `tokens.css`）：tokens 文件只放变量，
  规则放组件 —— 与商城「`.app-shell` 自己声明 background: var(--bg)」同款分工。
-->
<style>
html[data-theme="admin"] body {
  background: var(--bg);
  color: var(--ink);
}
.admin-app {
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
}
</style>
