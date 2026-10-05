<template>
  <div class="user-menu">
    <router-link
      v-for="item in subMenu"
      :key="item.sub"
      :to="item.to"
      class="menu-btn"
      :class="{ active: route.meta.sub === item.sub }"
    >{{ item.label }}</router-link>
    <button @click="toLogin" class="logout">退出登录</button>
  </div>

  <router-view />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearCredentials, clearMerchantId } from '../api/auth'

// ── R1（2026-10-05）：用户中心从 `CustomerPage.vue` 的**内联 markup** 提成**独立页面组件** ──
// 4 个子 tab 已各自成路由（见 `router/index.js`）⇒ 本文件**不再持有 `curSubPage`** —— 子页面选择权交给路由。
//
// ── R2（2026-10-05）：由「客户专属」升级为**两壳共用**（用户裁决 3 ⇒ (a) 共享 + `meta` 驱动）──
// 菜单 = **数据**（路由记录的 `meta.subMenu`：客户 4 项 / 商家 6 项），退出语义 = `meta.logout`。
//   ⇒ 两壳的差异全部落在**配置**里，本文件**零分支**（只在退出那一处分一次），
//      R6 抽 `AppShell` 时二级导航已是配置化的（这正是 R2 作为「R6 预演」的实义）。
//   ⇒ 代价（照实记）：退出点从「分文件」变成「**同一组件两分支**」——
//      这是**语义**变更、不只是行号重指；`api/auth.js` 里四条按「两个退出点」写的注释已按新语义重写。
//
// ⚠️ **本组件是「多根」（fragment）**：两个根 = 左菜单 + 右子页面，**合起来**正好是壳里
//    `.user-page`（`display:flex` 行容器）的两个**直接**子元素 ⇒ 布局逐字等价。别在两者之间加包裹
//    `div` —— 那会把 flex 容器多隔一层。
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
}
</script>

<style scoped>
/* ⚠️ 本块是「用户中心左菜单」的样式，R1 前在 `CustomerPage.vue` 的 `<style scoped>` 里
   （`.user-menu` / `.user-menu button` / `.user-menu button.active` / `.user-menu button.logout`），
   R1 随 markup 一起搬到此处 ⇒ 各文件的样式**各归其主**。
   4 个 tab 由 `button` 改成 `router-link`（渲染成 `a`）⇒ 选择器与少数属性相应调整
   （`text-decoration: none`；`box-sizing: border-box` 让 `a` 与 `button` 的盒模型一致）。
   R2 起本组件被**两个壳**共用 ⇒ 这份样式同时服务客户 / 商家两侧（R2 前商家侧那份
   `.user-menu*` 在 `MerchantPage.vue` 的 `<style scoped>` 里，与这份逐条等价，已删）。 */
.user-menu {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 220px;
}
.user-menu .menu-btn,
.user-menu .logout {
  padding: 12px 16px;
  border: 1px solid #eee;
  border-radius: 6px;
  background: #fff;
  text-align: left;
  font-size: 15px;
  cursor: pointer;
  transition: 0.2s;
  text-decoration: none;
  color: #333;
  box-sizing: border-box;
}
.user-menu .menu-btn.active {
  border-color: #007bff;
  background: #e6f7ff;
  color: #007bff;
}
.user-menu .logout {
  border-color: #ff4d4f;
  color: #ff4d4f;
}
</style>
