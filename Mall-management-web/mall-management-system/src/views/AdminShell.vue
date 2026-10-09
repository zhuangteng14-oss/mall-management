<template>
  <div class="admin-shell">
    <!-- ── 侧边栏 ─────────────────────────────────────────────────────────────
         第 1 项是品牌（同时是回「概览」的快捷入口），其后是「概览」+ 各「域」：
         一行域入口 + 其下**可点的子项**（第三笔起各有路由，见 `config/admin-nav.js`）。 -->
    <aside class="a-side">
      <router-link to="/admin" class="a-brand">
        <span class="a-mark"></span>
        <span class="a-brand-tx">
          <b>管理端</b>
          <span class="a-dim">Mall Management · Admin</span>
        </span>
      </router-link>

      <nav class="a-nav">
        <template v-for="d in SIDEBAR" :key="d.key">
          <router-link :to="d.to" class="a-dom" :class="{ on: current === d.key }">{{ d.label }}</router-link>
          <ul v-if="d.subs.length" class="a-subs">
            <li v-for="s in d.subs" :key="s.key">
              <router-link :to="s.to" class="a-sub" :class="{ on: sub === s.key }">{{ s.label }}</router-link>
            </li>
          </ul>
        </template>
      </nav>

      <div class="a-side-foot">
        <span class="a-dim">骨架第三笔 · 2026-10-08</span>
      </div>
    </aside>

    <div class="a-col">
      <!-- ── 顶栏 ─────────────────────────────────────────────────────────────
           左 = 面包屑（当前页来自 `route.meta.title`），右 = 管理员身份 + 退出。 -->
      <header class="a-top">
        <h2 class="a-crumb">管理端<span class="a-sep">/</span><b>{{ pageTitle }}</b></h2>
        <div class="a-right">
          <span class="a-who"><b>{{ who }}</b><span class="a-dim">{{ roleLabel }}</span></span>
          <button type="button" class="a-out" @click="exit">退出</button>
        </div>
      </header>

      <main class="a-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<!--
  管理端外壳（C3 骨架：第一笔建壳 → 第二笔补侧边栏 / 顶栏身份 / 两个域入口 →
  **第三笔让 11 个子项可点可跳可高亮**，2026-10-08）。
  ⚠️ 与商城的 `AppShell.vue` **有意保持三处不同**（别当成漏写）：
     ① **不收 `nav` / `base` / `role` 三个 prop** —— 那三个的存在理由是「一套壳服务客户 + 商家
        两条记录、差异全落在配置里」；管理端**只有一条**记录，收 prop 就得为它造一张
        `ADMIN_NAV` 再传进来（多一层纯粹为对称而设的间接）。
     ② **不消费 `meta.layout`** —— 那是商城「内容区外壳 div 的类名随路由走」的机制
        （`AppShell.vue:112`），管理端只有一种布局，收了就等于把类名表抄一遍。
     ③ **不带 `KeepAlive`** —— 商城那份只缓存 `HomeTab` / `GoodsSelect` 两个 tab（理由写在
        `AppShell.vue:77-86`）。管理端**一格业务页都还没有**，没有东西值得缓存；
        将来真有带轮询的页时，**别顺手加**：缓存 = 离开后后台继续轮询（同那条注释的警告）。

  ⚠️ **第一笔那个「顶栏直接摆三个 tab」的形状已被第二笔取代** —— 概览 / 业务域 / 可观测域 改住
     `config/admin-nav.js` 的 `ADMIN_SIDEBAR`（单一事实源），本文件只渲染。`slot-registry.md:3056`
     那行「暗色顶栏 + `<router-view>`」是对**第一笔**当时状态的记录，作为历史仍然成立。
  ⚠️ **第三笔的两处命名更正**（用户裁决）：域名「风控域」→ **「可观测域」**（风控业界指反欺诈，
     与内容不符）；URL `/admin/biz` → `/admin/business`、`/admin/risk` → `/admin/observe`。
     两处旧名**已废**，别再改回去。
-->
<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ADMIN_SIDEBAR } from '../config/admin-nav'
import { getUsername, getRole, clearCredentials, clearToken } from '../api/auth'
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'

const SIDEBAR = ADMIN_SIDEBAR
const route = useRoute()
const router = useRouter()

// 点亮判据 = **键**比较（`meta.key`），不是路径前缀 —— 理由见 `config/admin-nav.js` 块首那条 ⚠️
// （`/admin` 是 `/admin/biz` 的前缀，`startsWith` 会让「概览」永远同时亮）。
// 兜底 `'ov'`：万一某条子记录忘写 `meta.key`，宁可错亮「概览」也不要全暗。
const current = computed(() => route.meta.key || 'ov')
// 子项高亮的键 —— 与 `current` 同源（都来自路由记录，**都不比路径前缀**）。
// 空串 = 当前不在子项页（域入口 / 概览）⇒ 没有子项亮。
// ⚠️ 两个键**分工不同、都在用**：`meta.key` 是**所属域**（子项页上也写着域的键，故域行保持点亮），
//    `meta.sub` 才是**子项自己**。一份记录写两个键，正是为了「进子项后父域不熄灯」。
const sub = computed(() => route.meta.sub || '')
const pageTitle = computed(() => route.meta.title || '概览')

// ⚠️ 身份**只在 setup 期读一次**，不订阅 —— 与 `AppShell.vue` 里「`isLoggedIn()` 不是响应式的、
//   要在 setup 里算一次」是同一条口径。这里够用，因为：进 `/admin` 必然经过 `/login`
//   （换父记录 ⇒ `AdminShell` **新挂载**），退出又把它卸掉；而**切域只在 children 之间走，
//   不重建本壳** ⇒ 挂载时读到的那一份身份，在整个管理端会话内不会过时。
// ⚠️ 别为了「更实时」改成 `watch`：那要 `api/auth.js` 开一个响应式读口，为一个不会发生的场景
//   改公共模块，不划算。
const who = getUsername() || '管理员'
const roleLabel = { '1': '客户', '2': '商家', '3': '管理员' }[getRole()] || '管理员'

// ⚠️ 退出：与 `UserCenter.vue:52-65` 是**同一条契约**，但**少一支** —— 管理端没有 `merchant`
//    那一支（`meta.logout` 在 `/admin` 上缺省）⇒ 只 `clearCredentials()`。
//    **别顺手改成 `clear()`**：那会多清 `customerid`（两个函数的差异见 `api/auth.js` 的注释）。
//    四条顺序/开关照抄那份，逐条理由也在那儿：
//      ① `http.post` 放在 `router.push` **之后**（同 tick，行为无差，但保住那边的行号锚）；
//      ② `.catch` 吞掉 —— fail-open：后端不可达也不该把用户困在「看起来还登着」的界面里；
//      ③ `.finally` 才 `clearToken()` —— 票要**在这一发里送出去**后端才销得掉；
//      ④ `allowUnauthorized: true` —— 未登录点退出必 401，不该进 401 出口。
//    ⚠️ 管理员的**票本来就是空的**（TD-18：cookie 管理员铸不出票）⇒ ③ 今天是个空操作。
//       留着它是为了「将来铸票路径定案后不用回来补」—— 而不是因为今天有用。
const exit = () => {
  clearCredentials()
  router.push('/login')
  http.post(endpoints.user.logout, null, { allowUnauthorized: true }).catch(() => {}).finally(() => clearToken())
}
</script>

<style scoped>
/* ⚠️ 底色 / 文字色**不在这儿落** —— 在 `AdminApp.vue` 的 `html[data-theme="admin"] body`。
   本文件只声明**自己的**布局，与商城「tokens 只放变量、规则放组件」同款分工。 */
.admin-shell {
  display: flex;
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
}

/* ── 侧边栏 ──────────────────────────────────────────────────────────────── */
.a-side {
  flex: 0 0 232px;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-right: 1px solid var(--line);
  position: sticky;
  top: 0;
  height: 100vh;
}
.a-brand {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s4);
  border-bottom: 1px solid var(--line);
  text-decoration: none;
}
.a-mark {
  flex: none;
  width: 18px;
  height: 18px;
  border-radius: var(--r1);
  background: var(--brand);
}
.a-brand-tx {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  min-width: 0;
}
/* ⚠️ 副标题**不许折行** —— 侧栏 232px 减两侧内边距（`--s4`）再减标记 18 + 间隔 12，
   留给它的约 170px 刚好放得下这一串；用 `--s5` 内边距时不够，它会折成两行、把品牌块撑高。 */
.a-brand-tx .a-dim {
  white-space: nowrap;
}
.a-brand-tx b {
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-dim {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.a-nav {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: var(--s4) var(--s3);
}
/* ⚠️ 高亮 / hover **只改底色**，不加左侧强调条 —— 那是 `tokens.css` 块首抄下来的设计稿
   用法规矩之一（「禁 · 侧边强调条（hover 用底色变化）」）。 */
.a-dom {
  display: block;
  padding: var(--s3) var(--s4);
  border-radius: var(--r2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink-2);
  text-decoration: none;
}
.a-dom:hover {
  background: var(--surface-2);
  color: var(--ink);
}
.a-dom.on {
  background: var(--brand-soft);
  color: var(--brand-ink);
}
/* 子项**可点**（第三笔起有路由）—— 比域行小一号、暗一档，但同样只靠底色表达 hover / 高亮
   （设计稿规矩「禁 · 侧边强调条」）。 */
.a-subs {
  list-style: none;
  margin: 0 0 var(--s3) var(--s4);
  padding-left: var(--s3);
  border-left: 1px solid var(--line);
}
.a-sub {
  display: block;
  padding: var(--s2) var(--s3);
  border-radius: var(--r1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  text-decoration: none;
}
.a-sub:hover {
  background: var(--surface-2);
  color: var(--ink);
}
.a-sub.on {
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.a-side-foot {
  flex: none;
  padding: var(--s4) var(--s5);
  border-top: 1px solid var(--line);
}

/* ── 右列：顶栏 + 主区 ───────────────────────────────────────────────────── */
/* `.a-col` 必须 `min-width: 0`：flex 子项默认 `min-width: auto`，宽内容会把它撑破、溢出到侧边栏上。 */
.a-col {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.a-top {
  flex: none;
  height: 60px;
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: var(--s5);
  padding: 0 var(--s5);
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}
.a-crumb {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.a-crumb b {
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-sep {
  color: var(--line-2);
}
/* `margin-left: auto` 把右组推到另一端 —— 比 `space-between` 更稳（左面包屑是弹性的） */
.a-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--s4);
}
.a-who {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  font-size: var(--fs-sm);
}
.a-who b {
  font-weight: var(--fw-med);
  color: var(--ink);
}
.a-out {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  cursor: pointer;
}
.a-out:hover {
  color: var(--ink);
  background: var(--surface-3);
}

.a-main {
  flex: 1 1 auto;
  min-width: 0;
  padding: var(--s6);
}
</style>
