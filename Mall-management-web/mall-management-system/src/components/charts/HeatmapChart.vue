<template>
  <BaseChart
    :option="option"
    :height="height"
    :loading="loading"
    :empty="!hasData"
    :error="error"
    :empty-text="emptyText"
    @retry="emit('retry')"
  />
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import BaseChart from './BaseChart.vue'
import { chartTokens, categoryAxis, mixHex, TOOLTIP_ITEM } from './chartTokens'

const props = defineProps({
  /** x 轴类目（如 24 个小时）。必填。 */
  xLabels: { type: Array, required: true },
  /** y 轴类目（如星期 / 状态）。必填。 */
  yLabels: { type: Array, required: true },
  /** 格值：`[[x下标, y下标, 值], …]`。缺的格子留白。必填。 */
  data: { type: Array, required: true },
  height: { type: String, default: '320px' },
  /** 色阶上界。不给就按数据最大值自动。 */
  max: { type: Number, default: null },
  /** tooltip / 色阶上的量名（如 `'订单数'`）。 */
  valueName: { type: String, default: '值' },
  /** 色阶两端的文字：`[高值, 低值]`（ECharts `visualMap.text` 的顺序）。 */
  legendText: { type: Array, default: () => ['多', '少'] },
  /** x 轴标签间隔（0 = 全显示）。60 拍时传 9 即每 10 拍一个刻度，避免挤成一团。 */
  xLabelInterval: { type: Number, default: 0 },
  /** 绘图区内边距。五图共用同一套左右值才能「共享一根 x 轴」。 */
  gridLeft: { type: Number, default: 64 },
  gridRight: { type: Number, default: 24 },
  /** tooltip 自定义 formatter（如「锁名 · 第 N 拍 · 超时 N 次」）。 */
  tooltipFormatter: { type: Function, default: null },
  /** 首帧是否开补间动画（时间栈每拍重建时关掉）。 */
  animation: { type: Boolean, default: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  emptyText: { type: String, default: '区间内暂无数据' },
})

const emit = defineEmits(['retry'])

const hasData = computed(() => props.data.length > 0)

/**
 * 色阶上界：调用方给了就用，没给就按**数据最大值**取一个整数档。
 *
 * ⚠️⚠️ **这个值不给会同时出两件事，而且都不报错**（可视化段 commit 1 实测）：
 *   ① **报错**：`max` 若以「键在、值为 `undefined`」的形式传下去（`max: undefined`），
 *      ECharts 会把它算进 `dataExtent`（`VisualMapModel.resetExtent` = `asc([min, max])`，
 *      **不从数据推算**）⇒ 上界成 `undefined` ⇒ `_makeColorGradient` 采样出 `NaN` 索引
 *      ⇒ `visual[NaN]` = `undefined` ⇒ 画手柄横条时
 *      `addColorStop('undefined')` 抛 `SyntaxError`，**整块画布停在半帧**。
 *   ② **不报错但更阴**：`visualMap` 的默认 `max` 是 **200**。真数据全落在 60 上下时，
 *      整张热力图的格子全挤在色阶最底部深浅难分 —— 图上「有东西」，但读不出梯度。
 *   ⇒ 所以这里**永远给一个数**（`resolvedMax`），且**由数据算**。
 *   档位只为了让色阶刻度是个整数（10 / 20 / 50 /…），与精度无关。
 */
const resolvedMax = computed(() => {
  if (props.max !== null && props.max !== undefined) return props.max
  let m = 0
  for (const d of props.data || []) {
    const v = Number(d && d[2])
    if (Number.isFinite(v) && v > m) m = v
  }
  if (m <= 0) return 1 // 全 0 / 空数据：给 1 让色阶有个合法区间，别落回 undefined
  const step = m <= 10 ? 1 : m <= 50 ? 5 : m <= 200 ? 10 : m <= 1000 ? 50 : 100
  return Math.ceil(m / step) * step
})

const option = computed(() => {
  const t = chartTokens()
  /**
   * 热力图的两条轴都是类目轴，且都要 `splitArea`（格子底纹）；灰阶与标签色由主题给。
   *
   * ⚠️ 不再用 `containLabel:true`：那会让绘图区左边界随 y 标签宽度浮动，五张图就无法
   * 共用同一根 x 轴。改为固定 `gridLeft` + y 标签截断。
   */
  const axisOf = (labels, extra = {}) => categoryAxis(labels, {
    splitArea: { show: true }, ...extra,
  })
  return {
    animation: props.animation,
    grid: { left: props.gridLeft, right: props.gridRight, top: 12, bottom: 56 },
    tooltip: props.tooltipFormatter
      ? { ...TOOLTIP_ITEM, formatter: props.tooltipFormatter }
      : TOOLTIP_ITEM,
    xAxis: axisOf(props.xLabels, {
      axisLabel: { interval: props.xLabelInterval },
    }),
    yAxis: axisOf(props.yLabels, {
      axisLabel: { width: Math.max(28, props.gridLeft - 8), overflow: 'truncate' },
    }),
    visualMap: {
      min: 0,
      max: resolvedMax.value, // 见上方 resolvedMax 注释：**绝不能是 `undefined`**
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      itemWidth: 12,
      itemHeight: 90,
      text: props.legendText,
      // 字色字号走主题的 `visualMap`；`calculable` / 位置 / 尺寸是本图特有的布局参数。
      // ⚠️ 色阶**起点取当前主题的底色**（浅色=白、暗色=`--surface`），不是写死白或写死黑 ——
      //    写死白的话暗色主题下低值格子会变成一片白斑。`mixHex` 就是干这个的。
      //    3a.4：起点从 0.08 提到 0.22 —— 0.08 在 `--surface:#172032` 上几乎看不见，
      //    「低值浅、高值深」的梯度读不出来。
      inRange: { color: [mixHex(t.surface, t.series[0], 0.22), t.series[0]] },
    },
    series: [
      // 格子的描边 / 悬停描边 / 不画标签：都在主题的 `heatmap` 里。
      { name: props.valueName, type: 'heatmap', data: props.data },
    ],
  }
})
</script>
