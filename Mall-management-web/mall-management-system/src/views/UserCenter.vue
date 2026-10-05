<template>
  <div class="user-menu">
    <router-link to="/customer/user/info" class="menu-btn" :class="{ active: route.meta.sub === 'info' }">个人信息</router-link>
    <router-link to="/customer/user/history" class="menu-btn" :class="{ active: route.meta.sub === 'history' }">历史购买记录</router-link>
    <router-link to="/customer/user/friend" class="menu-btn" :class="{ active: route.meta.sub === 'friend' }">好友</router-link>
    <router-link to="/customer/user/chat" class="menu-btn" :class="{ active: route.meta.sub === 'chat' }">消息</router-link>
    <button @click="toLogin" class="logout">退出登录</button>
  </div>

  <router-view />
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { clearCredentials } from '../api/auth'

// ── R1（2026-10-05）：用户中心从 `CustomerPage.vue` 的**内联 markup** 提成**独立页面组件** ──
// 4 个子 tab（info / history / friend / chat）已各自成路由（见 `router/index.js`），
// 所以本文件**不再持有 `curSubPage`** —— 子页面选择权也交给路由。
//
// ⚠️ **本组件是「多根」（fragment）**：两个根 = 左菜单 + 右子页面，**合起来**正好是今天
//    `CustomerPage.vue` 里 `.user-page`（`display:flex` 行容器）的两个**直接**子元素
//    ⇒ 布局逐字等价。别在两者之间加包裹 `div` —— 那会把 flex 容器多隔一层。
// ⚠️ 下一行那条 `eslint-disable-next-line` 是**必需**的：本仓 ESLint 未开 `vue/setup-compiler-macros`
//    ⇒ 宏 `defineOptions` 被判 `no-undef`、`npm run build` 直接红（同 `GoodsSelect.vue:165` 的先例）。
// eslint-disable-next-line no-undef
defineOptions({ name: 'UserCenter' })

const route = useRoute()
const router = useRouter()

// ⚠️ 本函数是**客户侧唯一的「退出登录」点**（R1 前在 `CustomerPage.vue:111-114`）。
//    它 `clearCredentials()` 而**不清** `customerid` —— 与 `MerchantPage.vue:131-132`
//    （`clearCredentials()` + `clearMerchantId()`）的差异、以及和 401 出口的 `clearAll()`
//    的差异，见 `api/auth.js` 那两个函数的注释。**别顺手改成 `clearAll()`**（那是改退出行为）。
const toLogin = () => {
  clearCredentials()
  router.push('/login')
}
</script>

<style scoped>
/* ⚠️ 本块是「客户侧」的菜单样式，R1 前在 `CustomerPage.vue` 的 `<style scoped>` 里
   （`.user-menu` / `.user-menu button` / `.user-menu button.active` / `.user-menu button.logout`），
   R1 随 markup 一起搬到此处 ⇒ 各文件的样式**各归其主**。
   4 个 tab 由 `button` 改成 `router-link`（渲染成 `a`）⇒ 选择器与少数属性相应调整
   （`text-decoration: none`；`box-sizing: border-box` 让 `a` 与 `button` 的盒模型一致）。 */
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
