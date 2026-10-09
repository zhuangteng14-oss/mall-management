<template>
  <section class="a-home">
    <h1>管理端骨架已就绪</h1>
    <p class="a-lead">
      本页是管理端的<b>概览占位页</b> —— 只证明「能打开、能跳转、暗色主题已生效」。第一笔建壳，
      第二笔补侧边栏与两个域入口，第三笔把 <b>11 个子项</b>接上路由；<b>各页内容仍是占位</b>，
      清单与设计意图见 <code>docs/agent/c3-modules.md</code>。
    </p>

    <div class="a-card">
      <h2>骨架自检</h2>
      <ul class="a-list">
        <li>入口：<code>admin.html</code>（<code>vue.config.js</code> 的 <code>pages.admin</code>）</li>
        <li>外壳：<code>AdminShell.vue</code>（暗色侧边栏 + 顶栏 + <code>&lt;router-view&gt;</code>）</li>
        <li>路由：<code>/admin</code>，<code>meta.roles: ['3']</code>（仅管理员）；域入口 <code>/admin/business</code> · <code>/admin/observe</code>，其下共 <b>11</b> 条子项</li>
        <li>主题：<code>html[data-theme="admin"]</code> 作用域，色值取自<b>丁版（电商运营蓝）</b></li>
      </ul>
    </div>

    <!-- 色带：把关键 token 摆出来，让「暗色主题生效」一眼可验（非业务内容、无数据请求） -->
    <div class="a-card">
      <h2>主题取样</h2>
      <div class="a-sw">
        <span class="a-chip" style="background: var(--bg)">bg</span>
        <span class="a-chip" style="background: var(--surface)">surface</span>
        <span class="a-chip" style="background: var(--surface-2)">surface-2</span>
        <span class="a-chip" style="background: var(--brand)">brand</span>
        <span class="a-chip" style="background: var(--brand-ink)">brand-ink</span>
        <span class="a-chip" style="background: var(--accent)">accent</span>
      </div>
      <div class="a-sw a-chart">
        <span v-for="i in 10" :key="i" class="a-bar" :style="{ background: `var(--c${i})` }"></span>
      </div>
      <p class="a-note">上排 = 中性 / 品牌 / 强调；下排 = 图表色板 <code>--c1..--c10</code>。</p>
    </div>
  </section>
</template>

<!--
  管理端占位页（C3 骨架第一笔，2026-10-08）。
  ⚠️ 它**故意不做任何网络请求** —— 管理员今天还没有可用的业务端点（身份口径见
     slot-registry §十三 13-5）；一发请求就会 401、把 401 出口触发到 /login，
     反而看不出「骨架通不通」。判据是**渲染**，不是数据。
-->
<script setup>
</script>

<style scoped>
.a-home {
  max-width: 880px;
}
h1 {
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  color: var(--ink);
  margin-bottom: var(--s3);
}
h2 {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink-2);
  margin-bottom: var(--s3);
}
.a-lead {
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-2);
  margin-bottom: var(--s6);
}
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
  margin-bottom: var(--s5);
}
.a-list {
  list-style: none;
  font-size: var(--fs-sm);
  line-height: 2;
  color: var(--ink-2);
}
code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border-radius: var(--r1);
  padding: 2px 6px;
}
.a-sw {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
  margin-bottom: var(--s3);
}
.a-chip {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
}
.a-chart {
  gap: 0;
}
.a-bar {
  flex: 1 1 0;
  height: 28px;
}
.a-bar:first-child {
  border-radius: var(--r1) 0 0 var(--r1);
}
.a-bar:last-child {
  border-radius: 0 var(--r1) var(--r1) 0;
}
.a-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
</style>
