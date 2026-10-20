<template>
  <svg
    class="pv-ic"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="stroke"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    v-html="body"
  />
</template>

<script setup>
/* UI 先行阶段（2026-10-19）：预览页专用线性图标。
 * 只收本阶段用得到的 20 余枚；全部是内联 path，不引第三方图标包、不发任何网络请求。 */
import { computed } from 'vue'

// eslint-disable-next-line no-undef -- 本仓 eslint-plugin-vue 8.7.1 的 vue3-essential 不带宏环境
const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 24 },
  stroke: { type: [Number, String], default: 1.8 },
})

const ICONS = {
  crown: '<path d="M3 8l4.4 3L12 5l4.6 6L21 8l-2.1 10H5.1L3 8z"/><path d="M7 21h10"/>',
  star: '<path d="M12 3l2.7 5.5 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1L3.2 9.4l6.1-.9L12 3z"/>',
  spark: '<path d="M12 3l1.6 5.4L19 10l-5.4 1.6L12 17l-1.6-5.4L5 10l5.4-1.6L12 3z"/><path d="M19 15.5l.7 2.3 2.3.7-2.3.7-.7 2.3-.7-2.3-2.3-.7 2.3-.7.7-2.3z"/>',
  gift: '<path d="M20 12v9H4v-9"/><path d="M2 7h20v5H2z"/><path d="M12 7v14"/><path d="M12 7H7.5a2.5 2.5 0 010-5C11 2 12 7 12 7z"/><path d="M12 7h4.5a2.5 2.5 0 000-5C13 2 12 7 12 7z"/>',
  ticket: '<path d="M3 9a3 3 0 000 6v3h18v-3a3 3 0 000-6V6H3v3z"/><path d="M13 6v2M13 11v2M13 16v2"/>',
  headset: '<path d="M4 13v-2a8 8 0 0116 0v2"/><path d="M4 13h3v6H5a1 1 0 01-1-1v-5z"/><path d="M20 13h-3v6h2a1 1 0 001-1v-5z"/><path d="M17 19a4 4 0 01-4 3h-1"/>',
  wallet: '<path d="M3 7h16a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/><path d="M3 7l2-4h12l2 4"/><path d="M16 13h2"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  refund: '<path d="M9 14l-4-4 4-4"/><path d="M5 10h9a6 6 0 010 12h-2"/>',
  cart: '<path d="M3 4h2l2.4 11.5a2 2 0 002 1.5h8.2a2 2 0 002-1.6L21 8H6"/><circle cx="10" cy="21" r="1.3"/><circle cx="18" cy="21" r="1.3"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  flame: '<path d="M12 22c4 0 7-2.8 7-6.6 0-2.8-1.6-4.8-3.3-6.4C14.2 7.5 13 5.5 13 3c-3 2-4.2 4.6-4.2 6.6 0-1-.4-2.2-1.3-3.1C6.5 8.1 5 10.6 5 13.5 5 18.2 8 22 12 22z"/><path d="M12 22c1.8 0 3-1.3 3-3 0-1.4-.7-2.4-1.5-3.2-.8-.8-1.5-1.8-1.5-3.3-1.4 1-2 2.3-2 3.3 0-.5-.2-1.1-.6-1.6C8.5 15 8 16 8 17.2c0 2.6 1.7 4.8 4 4.8z"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 018 0v4"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  shield: '<path d="M12 3l8 3v6c0 5-3.4 8.3-8 9-4.6-.7-8-4-8-9V6l8-3z"/><path d="M8.5 12l2.4 2.4 4.6-5"/>',
  medal: '<path d="M8 3h8l-1.4 6h-5.2L8 3z"/><circle cx="12" cy="15" r="6"/><path d="M12 12.2l.9 1.8 2 .3-1.5 1.4.4 2-1.8-1-1.8 1 .4-2-1.5-1.4 2-.3.9-1.8z"/>',
  calendar: '<rect x="4" y="5" width="16" height="16" rx="2"/><path d="M8 3v4M16 3v4M4 10h16"/>',
  headphones: '<path d="M4 14v-2a8 8 0 0116 0v2"/><rect x="3" y="13" width="4" height="7" rx="1.6"/><rect x="17" y="13" width="4" height="7" rx="1.6"/>',
  watch: '<path d="M9 2h6l1 4H8l1-4z"/><path d="M8 18h8l-1 4H9l-1-4z"/><circle cx="12" cy="12" r="7"/><path d="M12 9v3.4l2.2 1.3"/>',
  laptop: '<rect x="4" y="5" width="16" height="11" rx="1.5"/><path d="M2 20h20M9 16l-1 4M15 16l1 4"/>',
  speaker: '<rect x="6" y="3" width="12" height="18" rx="3"/><circle cx="12" cy="15" r="3"/><path d="M12 7h.01"/>',
  phone: '<rect x="7" y="2" width="10" height="20" rx="2.5"/><path d="M11 18h2"/>',
  camera: '<path d="M4 7h4l2-3h4l2 3h4v13H4V7z"/><circle cx="12" cy="13" r="3.4"/>',
  keyboard: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10h.01M11 10h.01M15 10h.01M18 10h.01M7 14h.01M11 14h.01M15 14h.01M18 14h.01"/>',
  earbuds: '<path d="M7 4a3 3 0 013 3v6a3 3 0 11-6 0V7a3 3 0 013-3z"/><path d="M17 4a3 3 0 013 3v6a3 3 0 11-6 0V7a3 3 0 013-3z"/><path d="M7 16v2M17 16v2"/>',
  document: '<path d="M6 2h8l4 4v16H6V2z"/><path d="M14 2v5h5"/><path d="M9 12h6M9 16h6"/>',
  alert: '<path d="M12 3l10 18H2L12 3z"/><path d="M12 9v5M12 18h.01"/>',
  upload: '<path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v5h16v-5"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  chevron: '<path d="M6 9l6 6 6-6"/>',
  trend: '<path d="M3 17l6-6 4 4 8-8"/><path d="M15 7h6v6"/>',
  coin: '<circle cx="12" cy="12" r="9"/><path d="M9 9h6M9 12h6M12 9v6"/>',
  bag: '<path d="M6 7h12l1 14H5L6 7z"/><path d="M9 7a3 3 0 016 0"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0116 0"/>',
  diamond: '<path d="M6 3h12l4 6-10 12L2 9l4-6z"/><path d="M2 9h20M9 3l3 18M15 3l-3 18"/>',
}

const body = computed(() => ICONS[props.name] || ICONS.spark)
</script>

<style scoped>
.pv-ic { display: block; flex: none; }
</style>
