<template>
  <HomePage @go-search="onSearch" />
</template>

<script setup>
import { useRouter } from 'vue-router'
import HomePage from './HomePage.vue'

// ⚠️ 这个 name 是 `AppShell.vue` 里 `<KeepAlive :include="['HomeTab', ...]">`（R6 前在两壳里）
//    的**唯一**匹配依据。改名（含改文件名）会让缓存**静默失效** —— 不报错，只是每次切回来
//    都重新挂载（轮播重置）。改名前先 grep `include`。
//    下面两条 `eslint-disable-next-line` 是**必需**的：本仓 `eslint-plugin-vue` 是 **8.7.1**，
//    其 `vue3-essential` **不带** `vue/setup-compiler-macros` 宏环境 ⇒ `defineOptions` / `defineProps`
//    一律被判 `no-undef`、`npm run build` 直接红（`defineOptions` 那条是 R1 首次构建红出来的；
//    `defineProps` 同理，先例见 `ShoppingCart.vue:73` 的块级 `/* eslint-disable no-undef */`）。
// eslint-disable-next-line no-undef
defineOptions({ name: 'HomeTab' })

// ── R2（2026-10-05）：由「客户壳专属」升级为**两壳共用**（用户裁决 4 ⇒ (a) 共享参数化）──
//    本文件 R1 时在 `views/customer/HomeTab.vue`，R2 上移到 `views/` —— 它适配的 `HomePage`
//    本来就是**两壳共用**的，适配器挂在 `customer/` 下是路径上的从属错觉。
//
// 本组件只做一件事：把 `HomePage` 的 `go-search` 语义事件翻成**本壳**的搜索路由跳转。
//
// 为什么不直接改 `HomePage.vue` 让它 `router.push`：
//   `HomePage` 同时被**两个壳**挂载 ⇒ 在它内部推死一个路径，另一个壳就会被送错页。
//   本适配器让 `HomePage.vue` **一字不动** ⇒ 指向它的 inbound 行号引用**零漂移**。
//
// `base` 由**路由记录**给（`router/index.js` 的 `props: { base: '/customer' }` / `{ base: '/merchant' }`）——
//   前提已实测：vue-router **4.6.4** 的 `<router-view v-slot>` 仍把路由记录的 `props` **预绑**到
//   交给 slot 的 `Component` 上（`node_modules/vue-router/dist/vue-router.mjs:1101-1106`）⇒ 生效，
//   且两个壳组件都**不必**为它写任何传参代码。
//
// 为什么是 `kw` query 而不是组件 ref 通道：
//   `<router-view>` 里拿不到子组件 ref。关键词改由 URL 承载 ⇒ 搜索态**可深链 / 可刷新 / 可分享**。
// eslint-disable-next-line no-undef
const props = defineProps({ base: { type: String, required: true } })

const router = useRouter()

const onSearch = (keyword) => {
  router.push({ path: `${props.base}/search`, query: { kw: keyword } })
}
</script>
