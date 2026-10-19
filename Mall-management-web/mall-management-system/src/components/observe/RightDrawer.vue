<template>
  <div class="a-of-mask" @click.self="$emit('close')">
    <aside
      class="a-of-drawer"
      role="dialog"
      aria-modal="true"
      :aria-label="ariaLabel || title"
      :style="width ? { width } : null"
    >
      <header class="a-of-drawer-hd">
        <div>
          <h3>{{ title }}</h3>
          <p v-if="subtitle">{{ subtitle }}</p>
        </div>
        <button type="button" class="a-btn" @click="$emit('close')">{{ closeText || '关闭' }}</button>
      </header>
      <slot />
    </aside>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `ui/KpiCard.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { onBeforeUnmount, onMounted } from 'vue'

defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  ariaLabel: { type: String, default: '' },
  closeText: { type: String, default: '关闭' },
  /** 覆盖默认宽度（如死信抽屉内容更宽）；不传走 `min(780px, 94vw)`。 */
  width: { type: String, default: '' },
})
const emit = defineEmits(['close'])

/**
 * Esc = 关闭。⚠️ 监听挂在 `window` 上、不在根节点上：抽屉是 fixed 覆盖层，焦点未必在
 * `<aside>` 内（用户可能刚点过遮罩外或没点任何控件），根节点的 keydown 收不到。
 */
function onKeydown(e) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
/* ⚠️ 类名沿用状态页抽屉的 `a-of-*`（2026-10-18 抽成共享组件）：既有探针按
   `.a-of-drawer` / `.a-of-drawer-i` 等选择器取值，改名会让证据脚本静默找不到元素。
   外壳样式也留在本组件里 —— 两个调用方（状态页 / 指标页）共用同一份，不再各抄一遍。 */
.a-of-mask {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  justify-content: flex-end;
  background: rgba(0, 0, 0, 0.34);
}
.a-of-drawer {
  display: flex;
  flex-direction: column;
  width: min(780px, 94vw);
  height: 100%;
  background: var(--surface);
  border-left: 1px solid var(--line);
  box-shadow: -12px 0 30px rgba(0, 0, 0, 0.18);
}
.a-of-drawer-hd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s3);
  padding: var(--s4);
  border-bottom: 1px solid var(--line-2);
}
.a-of-drawer-hd h3 {
  margin: 0;
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
}
.a-of-drawer-hd p {
  margin: 2px 0 0;
  font-size: 10px;
  color: var(--ink-3);
}
/* 关闭按钮：`.a-btn` 在两个调用方里都是**页面局部**样式（scoped），作用不到子组件内部
   ⇒ 共享组件必须自带一份，否则按钮会裸成浏览器默认样式（这是一次实测会看见的回归）。 */
.a-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s3);
  cursor: pointer;
  white-space: nowrap;
}
.a-btn:hover { color: var(--ink); background: var(--surface-3); }
</style>
