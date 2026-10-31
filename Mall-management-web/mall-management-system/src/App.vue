<template>
  <div id="app">
    <router-view v-slot="{ Component }">
      <!-- ⚠️ 商品详情页按 fullPath 重挂载：详情页内点「猜你喜欢」只是同路由换 query，
           默认会复用组件、只有 URL 变而内容不刷新（实测：点 oppo12 后 hash 变了、标题仍是原商品）。
           其它路由保持原行为（key=None ⇒ 不强制重挂载）。 -->
      <component :is="Component" :key="route.path === '/product' ? route.fullPath : undefined" />
    </router-view>
    <ChatWidget />
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'
import ChatWidget from './components/chat/ChatWidget.vue'

const route = useRoute()
</script>