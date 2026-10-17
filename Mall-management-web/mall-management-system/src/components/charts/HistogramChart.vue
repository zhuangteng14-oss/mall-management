<template>
  <div class="hist">
    <header class="hist-hd">
      <span class="hist-t">{{ title }}</span>
      <span class="hist-m">
        n={{ fmtInt(samples) }}
        <template v-if="samples"> · 均耗 {{ avgText }}</template>
      </span>
    </header>
    <BaseChart
      :option="option"
      :height="height"
      :empty="!hasData"
      empty-text="当前时间段暂无有效耗时样本"
    />
  </div>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import BaseChart from './BaseChart.vue'
import { chartTokens, categoryAxis, valueAxis } from './chartTokens'

const props = defineProps({
  /** 标题（如「付款耗时 · CreateTime → PaidAt」）。 */
  title: { type: String, required: true },
  /** 桶：`[{label, count, fromMinutes, toMinutes}]`。 */
  bins: { type: Array, required: true },
  /** 平均耗时（分钟）。0 / null = 没有样本，不画虚线。 */
  avgMinutes: { type: Number, default: 0 },
  /** 样本数（只用于标题行）。 */
  samples: { type: Number, default: 0 },
  height: { type: String, default: '220px' },
})

/** 分钟 → 人读文案（与桶标签同一个量纲）。 */
function humanMinutes(v) {
  const n = Number(v) || 0
  if (n < 60) return `${Math.round(n)}分`
  if (n < 1440) return `${(n / 60).toFixed(n < 600 ? 1 : 0)}小时`
  return `${(n / 1440).toFixed(1)}天`
}

const avgText = computed(() => humanMinutes(props.avgMinutes))
const hasData = computed(() => props.bins.some((b) => Number(b.count) > 0))

/**
 * 平均耗时落在哪个桶 —— 虚线只能画在类目轴上（ECharts 的 `markLine.xAxis` 接受类目名），
 * 故这里先定位桶，再由桶名画线。标签仍写**精确均耗**，不把「桶中心」冒充成均值。
 */
const avgBin = computed(() => {
  if (!props.samples || !props.avgMinutes) return null
  return props.bins.find((b) => props.avgMinutes >= b.fromMinutes && props.avgMinutes < b.toMinutes)
    || props.bins[props.bins.length - 1]
})

const option = computed(() => {
  const t = chartTokens()
  // 直方图按 DLC 口径：**横轴 = 时间桶、纵轴 = 单数**，与上方横向流动的阶梯成轴向对比。
  // 柱子走灰阶（`--axis-label` / `--ink-2`），不用彩色大色块 —— 桶边界是结构，不是配色分组。
  const labels = props.bins.map((b) => b.label)
  const counts = props.bins.map((b) => Number(b.count) || 0)
  const avgLabel = avgBin.value ? avgBin.value.label : null
  return {
    animation: false,
    grid: { left: 40, right: 14, top: 34, bottom: 30 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      ...categoryAxis(labels),
      axisTick: { show: true },
      axisLabel: { interval: 0, fontSize: 10, hideOverlap: false },
    },
    yAxis: { ...valueAxis({ min: 0 }), axisTick: { show: true } },
    series: [{
      type: 'bar',
      data: counts.map((v, i) => ({
        value: v,
        itemStyle: {
          color: labels[i] === avgLabel ? t.ink2 : t.axisLabel,
          // 灰阶柱也要有「屏幕发光」的一层；不改变柱子的色阶结构。
          shadowBlur: 7,
          shadowColor: t.ink2,
        },
      })),
      barMaxWidth: 26,
      itemStyle: { opacity: 0.9 },
      label: {
        show: true,
        position: 'top',
        color: t.ink2,
        fontSize: 10,
        formatter: (p) => (p.value ? String(p.value) : ''),
      },
      markLine: avgBin.value ? {
        symbol: 'none',
        silent: true,
        lineStyle: { color: t.ink2, type: 'dashed', width: 1 },
        label: {
          show: true,
          position: 'end',
          distance: 2,
          color: t.ink2,
          fontSize: 10,
          backgroundColor: t.surface,
          padding: [2, 4],
          formatter: `均耗 ${avgText.value}`,
        },
        data: [{ xAxis: avgBin.value.label }],
      } : undefined,
    }],
  }
})

const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')
</script>

<style scoped>
.hist {
  min-width: 0;
}
.hist-hd {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s2);
  padding: 0 var(--s1) var(--s2);
  border-bottom: 1px dashed var(--line);
  margin-bottom: var(--s2);
}
.hist-t {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hist-m {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-3);
  white-space: nowrap;
}
</style>
