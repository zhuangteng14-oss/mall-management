<template>
  <div class="user-menu">
    <router-link
      v-for="item in subMenu"
      :key="item.sub"
      :to="item.to"
      class="menu-btn"
      :class="{ active: route.meta.sub === item.sub }"
    ><IconGlyph v-if="item.glyph" :name="item.glyph" />{{ item.label }}</router-link>
    <button @click="toLogin" class="logout">退出登录</button>
  </div>

  <router-view />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearCredentials, clearMerchantId, clearToken } from '../api/auth'
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 为**净零行数**：本文件被 `api-contract.md:216`（引 `UserCenter.vue:64`）与 `progress.md:569/570/629/664`（引 `:52-55`）按行号钉住，顶部加一行会让它们漂移。左菜单那枚 `<IconGlyph …>` 同理是**同行**插的（`:8`）

// ── R1（2026-10-05）：用户中心从 `CustomerPage.vue` 的**内联 markup** 提成**独立页面组件** ──
// 4 个子 tab 已各自成路由（见 `router/index.js`）⇒ 本文件**不再持有 `curSubPage`** —— 子页面选择权交给路由。
//
// ── R2（2026-10-05）：由「客户专属」升级为**两壳共用**（用户裁决 3 ⇒ (a) 共享 + `meta` 驱动）──
// 菜单 = **数据**（路由记录的 `meta.subMenu`：A5 起**客户 3 项 / 商家 5 项** —— 两侧各去掉「消息」，与顶栏重复，见 `router/index.js:42` 与那条 A5 注），退出语义 = `meta.logout`。
//   ⇒ 两壳的差异全部落在**配置**里，本文件**零分支**（只在退出那一处分一次），
//      R6 抽 `AppShell` 时二级导航已是配置化的（这正是 R2 作为「R6 预演」的实义）。
//   ⇒ 代价（照实记）：退出点从「分文件」变成「**同一组件两分支**」—— **语义**变更、不只是行号重指；`api/auth.js` 的四条注释已按新语义重写。
//
// ⚠️ **本组件是「多根」（fragment）**：两个根 = 左菜单 + 右子页面，**合起来**正好是壳里 `.user-page`
//    （`display:flex` 行容器）的两个**直接**子元素 ⇒ 布局逐字等价。别在两者之间加包裹 `div` —— 那会把 flex 容器多隔一层。
// ⚠️ 下一行那条 `eslint-disable-next-line` 是**必需**的：本仓 `eslint-plugin-vue` 是 **8.7.1**，
//    其 `vue3-essential` **不带** `vue/setup-compiler-macros` 宏环境 ⇒ 宏 `defineOptions` 被判
//    `no-undef`、`npm run build` 直接红（先例见 `ShoppingCart.vue:73` 的块级 `/* eslint-disable no-undef */`）。
// eslint-disable-next-line no-undef
defineOptions({ name: 'UserCenter' })

const route = useRoute()
const router = useRouter()

// 菜单项来自**当前路由记录**的 `meta.subMenu`（`meta` 会沿 `matched` 链合并 ⇒ 子路由上读到的
// 仍是 `user` 那条父记录给的表；`meta.sub` 则被**子**记录覆盖，用来点亮当前项）。
const subMenu = computed(() => route.meta.subMenu || [])

// ⚠️ 退出登录 —— **两壳共用同一函数，靠 `meta.logout` 分两支**（R2 起；R2 前是「两个文件各一份」）：
//    · 客户侧（`meta.logout` 缺省）：只 `clearCredentials()`、**不清** `customerid`；
//    · 商家侧（`meta.logout === 'merchant'`）：另加 `clearMerchantId()`。
//    与 401 出口的 `clearAll()`（`clear()`）的差异见 `api/auth.js` 那两个函数的注释。
//    **别顺手改成 `clear()`**（那是改退出行为：`clear()` 多清 `customerid`）。
const toLogin = () => {
  clearCredentials()
  if (route.meta.logout === 'merchant') clearMerchantId()
  router.push('/login')
  // ⚠️ 翻开关笔（2026-10-05）：**销服务端会话** —— 闸开之后，只清本地 = 没登出（cookie 还在，直开 `#/customer` 照样取到数据）。
  //    · `allowUnauthorized`：未登录时点退出**必 401**（`/user/logout` 不在白名单）⇒ 开了它就**不进 401 出口**（不置闩锁、不重复跳转）；点退出永远无害。
  //    · **不 await + `.catch` 吞掉**：fail-open —— 后端不可达/超时也不能把用户困在「看起来还登着」的界面里。残留（服务端会话活到过期）是有意接受的代价。
  //    · 放在 `router.push` **之后**：保住上面 `:52-55` 的行号锚（`auth.js` / `progress.md` 多处按行号引这一段；两条语句同一 tick，先后对行为无影响）。
  //    ⚠️ 多账户隔离笔（2026-10-06）：**销票放在 `.finally` 里**，不能提前 —— 票要在这次 POST 里送出去，
  //      后端 `AuthSession.logout` 才销得掉它；提前清（或把它塞进上面的 `clearCredentials()`）会让这一发
  //      带着空票出去 ⇒ 服务端那张票**活到 TTL**（最长 30 分钟）。`clearToken()` 只清**本页这张票**
  //      （D6：指针只有在等于本页票时才一并清）⇒ 不误伤别的标签页。两套凭据同一次退出一起销。
  http.post(endpoints.user.logout, null, { allowUnauthorized: true }).catch(() => {}).finally(() => clearToken())
}
</script>

<style scoped>
/* ⚠️ 本块是「用户中心左菜单」的样式，R1 前在 `CustomerPage.vue` 的 `<style scoped>` 里
   （`.user-menu` / `.user-menu button` / `.user-menu button.active` / `.user-menu button.logout`），
   R1 随 markup 一起搬到此处 ⇒ 各文件的样式**各归其主**。
   4 个 tab 由 `button` 改成 `router-link`（渲染成 `a`）⇒ 选择器与少数属性相应调整
   （`text-decoration: none`；`box-sizing: border-box` 让 `a` 与 `button` 的盒模型一致）。
   R2 起本组件被**两个壳**共用 ⇒ 这份样式同时服务客户 / 商家两侧（R2 前商家侧那份
   `.user-menu*` 在 `MerchantPage.vue` 的 `<style scoped>` 里，与这份逐条等价，已删）。

   ── C 笔（2026-10-07）：形态改照稿 ────────────────────────────────────────────
   来源 = `docs/美化/pages/UserCenter-customer.html:141-151` 的 `.uc-menu`（商家稿
   `UserCenter-merchant.html:134-145` **逐字同构**）。改动就是「**圆角描边按钮列 → 发丝线列表**」
   外加宽 220 → 200。
   ⚠️ 用户裁决：这一档属「左菜单 200px 连带的」；`.content` 的 1180 居中不在本笔（D 笔）。
   ⚠️ 稿子的 `.uc`（`display:flex;align-items:flex-start;gap:var(--s8)`）是**壳里的 `.user-page`**，
      不在本块 —— 它的 `gap` 那条改在 `AppShell.vue`。
   ⚠️ 稿子里的占位项（`.uc-menu a.future`：`我的收藏` / `浏览足迹` / `收货地址`）**本笔不引入**：
      加一项 = 改 `router/index.js` 一处，且属「凭空造约定」（同 `LEGACY_TAB` 口径）。
      故稿子的 `.uc-menu a.future{color:var(--ink-3);cursor:not-allowed}` 也一并**不落**。 */
.user-menu {
  /* ⚠️ `flex: none` 是**新增**的：改前只有 `width: 220px`，而本元素是 `.user-page`（flex 行）
     的子项 ⇒ 实测被压到 **195.9px**（不是 220）。加上它，200px 才真是 200px。 */
  width: 200px;
  flex: none;
  /* ⚠️ 去掉 `display:flex;flex-direction:column;gap:12px` —— 稿子的 `.uc-menu` 是**自然块流**
     （`a` 本来就是 `display:block`），条目之间靠 `border-bottom` 那根发丝线连成一条；
     留着 flex + gap 会把线断成一段一段，与稿子不符。
     ⚠️ 字体与顶栏同理：本仓没有全局 `body{font-family}`（用户裁决：本笔只声明在壳三件上）。 */
  font-family: var(--font);
}
/* ⚠️ 稿子把 `width` 写了**两遍**（`width:100%` 与 `width:calc(100% + var(--s3) * 2)`），**后者**
   生效 ⇒ 这里只写生效值一条，不留重复声明。`margin` 的负值与 `calc` 宽度配对，让发丝线比
   文字列左右各宽出 `var(--s3)`（=12px），照稿。
   ⚠️ 改前的 `border:1px solid #eee` / `border-radius:6px` / `background:#fff` /
      `transition:0.2s` 全部去掉；`box-sizing:border-box` 也去掉 —— 它由 `tokens.css:52-56`
      的 `*` reset 兜底（`:55`），删掉**不改变盒模型**。
   ⚠️⚠️ `border:0` + `background:none` + `font-family` 三行是**必需**的，不是冗余：本条选择器
      同时命中 `.logout`，而它是 `<button>`（模板 `:10`）**不是** `<a>` ⇒ 稿子的 `.uc-menu a`
      没有 UA 外观可漏，`<button>` 有。改前靠 `border:1px solid #eee`（简写，四边）+ `background:#fff`
      顺手压住；C 笔把简写收成「只 `border-bottom`」并删掉底色 ⇒ **`2px outset` 边框与 `ButtonFace`
      灰底会从 UA 样式表漏回来**（实测：`borderTopWidth:2px`、`backgroundColor:rgb(240,240,240)`），
      退出项看着像颗真按钮，与其余四项的发丝线行不一致。
      三条各自对治：`border:0` 清三边（`border-bottom` 一条在**其后**重新给菜单项接上发丝线）、
      `background:none` 清灰底、`font-family` 治 `<button>` **不继承**父级字体（UA 给按钮自带
      `font:...Arial`，只写 `font-size` 压不住族名）⇒ 不加它退出项与菜单项**不同字体**。 */
.user-menu .menu-btn,
.user-menu .logout {
  display: block;
  width: calc(100% + var(--s3) * 2);
  padding: var(--s3);
  margin: 0 calc(var(--s3) * -1);
  border: 0;
  border-bottom: 1px solid var(--line);
  background: none;
  font-family: var(--font);
  text-align: left;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  cursor: pointer;
  text-decoration: none;
}
.user-menu .menu-btn:hover {
  color: var(--ink);
  background: var(--surface-2);
}
/* 选中项给 `--brand-tint` 底 + `--brand-ink` 字 —— **底色变化，不是侧边强调条**
   （`tokens.css:37` 房规「禁 · 侧边强调条」）。 */
.user-menu .menu-btn.active {
  background: var(--brand-tint);
  color: var(--brand-ink);
  font-weight: var(--fw-semi);
}
/* 退出登录：与上面共用盒模型，但**不吃那根发丝线**，且与菜单拉开一段（稿子 `margin-top:var(--s6)`）。
   ⚠️ 改前的红字红框（`border-color:#ff4d4f;color:#ff4d4f`）改成「常态置灰、hover 才红」。
   ⚠️ hover 里**不再重复** `background:none` —— 共用规则已给（同本块对 `width` 的口径：不留重复声明）。 */
.user-menu .logout {
  margin-top: var(--s6);
  border-bottom: 0;
  color: var(--ink-3);
}
.user-menu .logout:hover {
  color: var(--danger);
}
</style>
