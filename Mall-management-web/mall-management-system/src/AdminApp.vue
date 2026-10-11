<template>
  <div class="admin-app">
    <!-- ⚠️ 「未匹配的 hash」兜底（2026-10-11，问题①的善后）：全仓**没有 catch-all**，两条守卫对
         `to.matched.length === 0` 一律**放行**（`router/index.js:295`，注释原文「白屏 + warning」）
         ⇒ 这一格底下原来渲染**什么都没有**，而 `data-theme="admin"` 把页面底色涂成 `#101828`
         ⇒ **整页深色、无内容**，且 Console 只有一条 warning、Network 一片干净 —— 用户的原话。
         本兜底把它换成一张**会说话**的面板（当前 hash + 出口），只在「真的没匹配上」时出现，
         有匹配时渲染 `router-view`，与改前逐字等价。 -->
    <div v-if="unmatched" class="admin-404">
      <h1>这个地址在管理端不存在</h1>
      <p>当前地址：<code>{{ route.fullPath }}</code></p>
      <p>管理端入口是 <code>admin.html#/admin</code>（<code>vue.config.js</code> 的 <code>pages.admin</code>）。</p>
      <a class="admin-404-go" href="#/admin">回管理端首页</a>
    </div>
    <router-view v-else />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
/** 没匹配上任何路由记录 ⇒ 本来会渲染成空白（见模板里的注释）。 */
const unmatched = computed(() => route.matched.length === 0)
</script>

<!--
  管理端根组件（C3 骨架第一笔，2026-10-08）。
  ⚠️ 与商城的 `App.vue` 同构（都只是 `<router-view>` 的宿主），差异只有一处：
     **这里不重复 `id="app"`** —— `admin.html` 已经有一个挂载点 `#app`，
     `App.vue` 那个同名内层 div 会产生两个同 id 元素（历史遗留），新入口不复制它。
  页面底色/文字色在这儿落（而不是放进 `tokens.css`）：tokens 文件只放变量，
  规则放组件 —— 与商城「`.app-shell` 自己声明 background: var(--bg)」同款分工。
  ⚠️ 2026-10-11 起多一个 `<script setup>`（只为上面那张兜底面板）——
     它**不改**渲染路径：有匹配时仍只有 `<router-view>`。
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
/* 兜底面板：只用 token（`data-theme="admin"` 下自动是深色一套），不写死颜色。 */
.admin-404 {
  max-width: 640px;
  margin: 18vh auto 0;
  padding: 28px 32px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2, 14px);
}
.admin-404 h1 {
  margin: 0 0 12px;
  font-size: 22px;
  color: var(--ink);
}
.admin-404 p {
  margin: 6px 0;
  color: var(--ink-2, var(--ink));
  font-size: 14px;
}
.admin-404 code {
  padding: 2px 6px;
  border-radius: 6px;
  background: var(--surface-2);
  font-family: var(--font-mono, monospace);
}
.admin-404-go {
  display: inline-block;
  margin-top: 14px;
  padding: 8px 16px;
  border-radius: 10px;
  background: var(--brand);
  color: var(--brand-ink, #fff);
  text-decoration: none;
  font-size: 14px;
}
</style>
