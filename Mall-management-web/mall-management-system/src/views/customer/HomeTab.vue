<template>
  <HomePage @go-search="onSearch" />
</template>

<script setup>
import { useRouter } from 'vue-router'
import HomePage from '../HomePage.vue'

// ⚠️ 这个 name 是 `CustomerPage.vue` 里 `<KeepAlive :include="['HomeTab', ...]">` 的**唯一**匹配依据。
//    改名（含改文件名）会让缓存**静默失效** —— 不报错，只是每次切回来都重新挂载（轮播重置）。
//    改名前先 grep `include`。下一行那条 `eslint-disable-next-line` 也是**必需**的：本仓 ESLint
//    未开 `vue/setup-compiler-macros`（宏 `defineOptions` 会被判 `no-undef`、`npm run build` 直接红），
//    同 `GoodsSelect.vue:165` 对 `defineExpose` 的既有处理。
// eslint-disable-next-line no-undef
defineOptions({ name: 'HomeTab' })

const router = useRouter()

// ── R1（2026-10-05）：本文件是「客户壳 首页 tab」的**路由页面**，只做一件事 ——
//    把**两个壳共用**的展示组件 `HomePage` 的 `go-search` 语义事件翻译成路由跳转。
//
// 为什么不直接改 `HomePage.vue` 让它 `router.push`：
//   `HomePage` 同时被**商家壳**挂载（`MerchantPage.vue:20` `@go-search="goSearch"`）⇒
//   在它内部推 `/customer/search` 会把「商家点搜索」也送去客户页。
//   本适配器让 `HomePage.vue` **一字不动** ⇒ 指向它的 7 处 inbound 行号引用**零漂移**。
//
// 为什么是 `kw` query 而不是 `goodsSelectRef` 通道：
//   `<router-view>` 里拿不到子组件 ref。关键词改由 URL 承载 ⇒ 搜索态**可深链 / 可刷新 / 可分享**。
const onSearch = (keyword) => {
  router.push({ path: '/customer/search', query: { kw: keyword } })
}
</script>
