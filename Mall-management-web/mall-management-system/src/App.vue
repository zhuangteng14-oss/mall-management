<template>
  <div id="app">
    <router-view />
    <ChatWidget />
  </div>
</template>

<script setup>
import ChatWidget from './components/chat/ChatWidget.vue'
</script>