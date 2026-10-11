<template>
  <!-- KPI 卡（可视化段 commit 1）：一个标签 + 一个大数字 + 一行环比。 -->
  <div class="kpi">
    <div class="kpi-k">
      <IconGlyph v-if="glyph" :name="glyph" tone="muted" />
      <span>{{ label }}</span>
    </div>

    <div class="kpi-v">
      <span v-if="loading" class="kpi-skel" aria-hidden="true" />
      <template v-else>
        <span class="kpi-n">{{ value }}</span>
        <span v-if="unit" class="kpi-u">{{ unit }}</span>
      </template>
    </div>

    <div class="kpi-d">
      <template v-if="delta !== null && delta !== undefined && !loading">
        <span class="kpi-arrow" :class="'kpi-arrow--' + tone">{{ arrow }}{{ deltaText || Math.abs(delta) + '%' }}</span>
        <span class="kpi-dl">{{ deltaLabel }}</span>
      </template>
      <template v-else-if="hint">{{ hint }}</template>
      <template v-else>&nbsp;</template>
    </div>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `../charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import IconGlyph from './IconGlyph.vue'

const props = defineProps({
  /** 指标名（如 `'成交额（GMV）'`）。必填。 */
  label: { type: String, required: true },
  /** 大数字。**已格式化好的字符串**（`'1,284'` / `'96.4'`）—— 组件不做数字格式化，
   *  因为「千分位 / 保留几位 / 是否缩写」是三端各不相同的显示口径，属于调用方的决定。 */
  value: { type: [String, Number], required: true },
  /** 单位（`'元'` / `'%'`）。跟在大数字后面，小一号。 */
  unit: { type: String, default: '' },
  /** 左上角的字形名（`glyphs.js` 的键）。不传就不画。 */
  glyph: { type: String, default: '' },
  /** 环比百分数（**带符号**：`8.2` = 涨 8.2%，`-0.6` = 跌 0.6%）。`null` = 没有环比。 */
  delta: { type: Number, default: null },
  /** 环比文案的右半句（如 `'较昨日'` / `'较上周'`）。 */
  deltaLabel: { type: String, default: '较昨日' },
  /** 环比文案的左半句。不传则自动用 `▲`/`▼` + `|delta|%`（如改 `'3ms'` 这种绝对量差异）。 */
  deltaText: { type: String, default: '' },
  /**
   * ⚠️ **该指标「涨了是坏事」时置 true**（典型：退款率、异常数、锁等待）。
   * 只影响**配色**（涨=红 / 跌=绿），不影响箭头方向。
   */
  inverse: { type: Boolean, default: false },
  /** 没有环比时的那行说明（如 `'其中 2 条待处置'`）。也没有就留空占位。 */
  hint: { type: String, default: '' },
  /** 加载态：大数字处显示脉冲骨架。 */
  loading: { type: Boolean, default: false },
})

/** 箭头方向只看数值符号。 */
const arrow = computed(() => (props.delta > 0 ? '▲ ' : props.delta < 0 ? '▼ ' : ''))
/** 配色看的是「这算好还是坏」——`inverse` 指标在这个点上与常规相反。 */
const tone = computed(() => {
  if (!props.delta) return 'flat'
  const raising = props.delta > 0
  const good = props.inverse ? !raising : raising
  return good ? 'good' : 'bad'
})
</script>

<style scoped>
.kpi {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s4) var(--s5);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  background: var(--surface);
}
.kpi-k {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.kpi-v {
  display: flex;
  align-items: baseline;
  gap: var(--s1);
  min-height: calc(var(--fs-2xl) * 1.1); /* 加载态与常态等高 ⇒ 数据回来时卡片不跳 */
}
.kpi-n {
  font-family: var(--font-mono);
  /* ⚠️ 房规：数字列一律 tabular-nums（`tokens.css` 「用法规矩」第 6 条）——
     否则多个 KPI 并排时，数字宽度不同会让「元」字左右错位。 */
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-2xl);
  font-weight: var(--fw-med);
  line-height: 1.1;
  color: var(--ink);
}
.kpi-u {
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.kpi-d {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.kpi-arrow--good { color: var(--success); }
.kpi-arrow--bad { color: var(--danger); }
.kpi-arrow--flat { color: var(--ink-3); }
.kpi-dl { color: var(--ink-3); }

.kpi-skel {
  display: inline-block;
  width: 5.5em;
  height: calc(var(--fs-2xl) * 0.72);
  border-radius: var(--r1);
  background: var(--surface-3);
  animation: kpi-pulse 1.2s ease-in-out infinite;
}
@keyframes kpi-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
@media (prefers-reduced-motion: reduce) {
  .kpi-skel { animation: none; }
}
</style>
