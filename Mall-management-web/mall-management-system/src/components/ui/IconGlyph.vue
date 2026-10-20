<template>
  <!-- 字形图标（可视化段 commit 1）。字形表见 `./glyphs.js`；用法与禁区见《看板规范》§4。 -->
  <span
    class="ig"
    :class="tone ? 'ig--' + tone : ''"
    :style="{ fontSize: size }"
    :title="title || undefined"
    :role="title ? 'img' : undefined"
    :aria-label="title || undefined"
    :aria-hidden="title ? undefined : 'true'"
  >{{ glyph }}</span>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `../charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import { GLYPHS } from './glyphs'

const props = defineProps({
  /** 字形名（`glyphs.js` 的键，如 `'orders'` / `'metrics'`）。必填 —— **不收裸字符**。 */
  name: { type: String, required: true },
  /** 字号。默认 `1em` ⇒ 跟父级文字同大小、随父级缩放（图标通常就该这样）。 */
  size: { type: String, default: '1em' },
  /** 色相：`''`(继承) / `muted` / `brand` / `danger` / `success` / `warning` / `info`。 */
  tone: { type: String, default: '' },
  /**
   * 无障碍名。⚠️ **只在图标单独承载信息时才传**（如「⚡ 攻击模拟器」这种导航项）。
   * 图标与文字并排时**不要传** —— 那会被读屏念两遍；此时组件自动 `aria-hidden`。
   */
  title: { type: String, default: '' },
})

const glyph = computed(() => {
  const g = GLYPHS[props.name]
  if (g) return g
  // 未知名字**不静默**：画一个「·」，并在开发期点名 —— 否则页面上只是少了个图标，
  // 而「少个图标」是最不容易被发现的回归（截图对比也常看不出来）。
  if (import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.warn(`[IconGlyph] 未登记的字形名：'${props.name}'（合法名见 components/ui/glyphs.js）`)
  }
  return '·'
})
</script>

<style scoped>
.ig {
  /* ⚠️ 行内块 + 定宽：字形字符的 advance width 各不相同，并排的几行若不定宽，
     图标列会参差（稿里就是靠 `inline-block` 对齐的）。 */
  display: inline-block;
  width: 1.35em;
  text-align: center;
  line-height: 1;
  /* 字形本身是装饰性的，别被选中、别被当作可点击目标。 */
  user-select: none;
}
.ig--muted { color: var(--ink-3); }
.ig--brand { color: var(--brand); }
.ig--danger { color: var(--danger); }
.ig--success { color: var(--success); }
.ig--warning { color: var(--warning); }
.ig--info { color: var(--info); }
</style>
