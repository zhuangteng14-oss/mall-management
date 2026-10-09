<template>
  <section class="a-stub">
    <h1>{{ title }}</h1>
    <p class="a-lead">
      本页是 <b>C3 骨架第三笔</b> 的<b>占位页</b> —— 判据只有一条：
      「侧边栏点进来落得到这儿，暗色壳不散」。<b>业务内容不在本笔</b>，
      骨架阶段<b>不写库、也不建业务端点</b>。
    </p>

    <div class="a-card">
      <h2>本页已知</h2>
      <ul class="a-list">
        <li>路由：<code>{{ route.path }}</code> —— 父记录 <code>/admin</code> 的 <code>meta.roles: ['3']</code> 沿 <code>matched</code> 链继承，本记录不重标</li>
        <li>所属域：<router-link :to="domain.to" class="a-link">{{ domain.label }}</router-link></li>
        <li>数据：<b>零请求</b>（与 <code>AdminHome.vue</code> 同口径 —— 判据是<b>渲染</b>，不是数据）</li>
      </ul>

      <!-- 域入口页额外列出本域的子项（可点 → 直接进子项页）。
           子项页**不再列**（避免每页挂一整串同域兄弟，页脚又给不出更多信息）。 -->
      <ul v-if="subs.length" class="a-sub-list">
        <li v-for="s in subs" :key="s.key">
          <router-link :to="s.to" class="a-link" :class="{ on: route.meta.sub === s.key }">{{ s.label }}</router-link>
        </li>
      </ul>
    </div>
  </section>
</template>

<!--
  管理端占位页（C3 骨架第二笔建、**第三笔改名并扩用**，2026-10-08）—— 一次服务 **11 条记录**：
    · 2 条**域入口**（`/admin/business`、`/admin/observe`）—— 额外列出本域子项；
    · 9 条**子项页**（`/admin/business/accounts` … `/admin/observe/logs`）—— 只报自己是谁。
      ⚠️ 原 11 条：`/admin/observe/audit` 已于 2026-10-08（C3 批 A）换成**真页** `AuditPage.vue`
         —— 这就是本组件块首那句「等某一块真拿到内容时再单独拆」的第一次兑现。
      ⚠️ 再减 1：`/admin/business/notify` 已于 2026-10-09（C3 块 1 · I2）换成**真页** `NotifyPage.vue`
         —— 同一句话的第二次兑现（域入口 2 条不变，子项 10 → 9、总数 12 → 11）。
  （「概览」不走本组件，它是 `AdminHome.vue`，用户裁决保留。）
  ⚠️ 名字从 `DomainStub.vue` 改成 `StubPage.vue`：第二笔它只管域入口，现在子项页也走它
     —— 再叫 `DomainStub` 就是**名字说谎**。改名不产生任何行号漂移（全仓无按行号引用，见 §13-10）。
  ⚠️ 为什么**仍是同一个组件**：这 11 页的差异**全部是数据**（`meta.title` / `meta.key` / `meta.sub`
     / 本域子项表），没有一处结构差异。为它们各建一个文件，等于把同一段 markup 抄 10 遍。
     等某一块真拿到内容时再单独拆（届时有真差异可拆，不是现在）。
  ⚠️ **它故意不做任何网络请求** —— 管理员今天还没有可用的业务端点（身份口径见
     slot-registry §十三 13-5 + TD-18）；一发请求就会 401、把 401 出口触发到 /login，
     反而看不出「骨架通不通」。
-->
<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ADMIN_SIDEBAR } from '../../config/admin-nav'

const route = useRoute()
const title = computed(() => route.meta.title || '管理端')

// ⚠️ 所属域**按 `meta.key` 从侧边栏那张表里反查**，不另抄一份 —— 两张表各写各的，迟早走岔
//    （侧边栏改名、页里还印旧名）。这与 `UserCenter.vue:45` 从 `route.meta.subMenu` 读菜单
//    是**同一个手法**：菜单是数据，页面只渲染。
// ⚠️ 子路由记录的 `meta.key` **写的是所属域的键**（不是子项自己的键）⇒ 这一句在两种页上都成立。
const domain = computed(() => ADMIN_SIDEBAR.find((d) => d.key === route.meta.key) || { label: '管理端', to: '/admin' })
const subs = computed(() => domain.value.subs || [])
</script>

<style scoped>
.a-stub {
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
.a-link {
  color: var(--brand-ink);
  text-decoration: none;
}
.a-link:hover {
  text-decoration: underline;
}
.a-sub-list {
  list-style: none;
  margin-top: var(--s4);
  padding-top: var(--s4);
  border-top: 1px solid var(--line);
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
}
.a-sub-list li {
  display: inline-flex;
}
.a-sub-list .a-link {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-sub-list .a-link.on {
  color: var(--brand-ink);
  border-color: var(--brand-ink);
}
.a-sub-list .a-link:hover {
  text-decoration: none;
  color: var(--ink);
}
</style>
