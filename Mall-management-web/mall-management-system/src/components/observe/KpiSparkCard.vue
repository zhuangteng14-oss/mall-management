<template>
  <!--
    带迷你柱状图（Sparkline）的读数卡 —— 批四视觉升维（2026-10-18）从指标页
    `MetricsPanel.vue` 的 KPI 卡（`.mp-kpi` / `.mp-spark`）抽出来的可复用版。

    【为什么不直接改指标页去调它】`MetricsPanel.vue` 的模板行号被 `docs/agent/context-packs/`
      按「文件:行号」引用着（`:128`、`:421`）⇒ 把那里 4 张卡换成组件会整体前移，引用集体失效。
      本组件的 DOM 结构与 `.mp-kpi` 逐行同构（同一套栅格：左列三行文字 / 右列 64px 微条），
      视觉一致；指标页的下一次改动再顺手收编。

    【数据形状】`spark` 是已经归一化好的柱：`{ h: 0..100, off: true|false }`，
      `h` 是百分比高度、`off` 是「这一拍缺值」（画灰柱而不是 0 高柱，二者含义不同）。
      ⇒ 归一化留在调用方（指标页的 `sparkBars()` 同款），组件不猜量纲。
  -->
  <div class="kpi" :class="{ alt }">
    <span class="kpi-k">{{ label }}</span>
    <span class="kpi-v"><b>{{ value }}</b><em v-if="unit">{{ unit }}</em></span>
    <span class="kpi-d" :title="detail">{{ detail }}</span>
    <span class="kpi-spark" aria-hidden="true" :style="{ '--bar': color }">
      <i v-for="(b, i) in spark" :key="i" :class="{ off: b.off }" :style="{ height: b.h + '%' }"></i>
    </span>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `../charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  unit: { type: String, default: '' },
  detail: { type: String, default: '' },
  spark: { type: Array, default: () => [] },
  /** 柱色：默认 `--c1`，命中率卡用 `--c3`（与 `MetricsPanel` 的 nth-child(3) 同色）。 */
  color: { type: String, default: 'var(--c1)' },
  alt: { type: Boolean, default: false },
})
</script>

<style scoped>
.kpi {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 64px;
  grid-template-rows: auto auto auto;
  column-gap: var(--s2);
  align-items: center;
  padding: var(--s1) 0;
  border-bottom: 1px solid var(--line);
}
.kpi:last-child { border-bottom: none; }
.kpi-k {
  grid-column: 1;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.kpi-v {
  grid-column: 1;
  display: flex;
  align-items: baseline;
  gap: var(--s1);
}
.kpi-v b {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.kpi-v em {
  font-style: normal;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.kpi-d {
  grid-column: 1;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kpi-spark {
  grid-column: 2;
  grid-row: 1 / span 3;
  height: 30px;
  display: flex;
  align-items: flex-end;
  gap: 1px;
}
.kpi-spark i {
  flex: 1 1 0;
  min-width: 0;
  background: var(--bar, var(--c1));
  border-radius: 1px 1px 0 0;
}
.kpi-spark i.off {
  background: var(--line-2);
  opacity: 0.5;
}
</style>
