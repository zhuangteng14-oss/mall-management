<template>
  <!-- 环图 = 饼图中间挖空 + **洞里的那一组数**。挖空在 option 里（`radius: [内,外]`），
       洞里的字走真正的 HTML（不是 ECharts 的 `graphic`/`title`）——
       HTML 才吃 `var(--font-mono)` / `tabular-nums` / 主题色，canvas 里的字要自己再描一遍。 -->
  <div class="dc">
    <BaseChart
      :option="option"
      :height="height"
      :loading="loading"
      :empty="!hasData"
      :error="error"
      :empty-text="emptyText"
      @retry="emit('retry')"
    />
    <div v-if="showCenter" class="dc-center">
      <span v-if="centerValue !== ''" class="dc-v">{{ centerValue }}</span>
      <span v-if="centerTitle" class="dc-k">{{ centerTitle }}</span>
    </div>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import BaseChart from './BaseChart.vue'
import { TOOLTIP_ITEM, LEGEND_SHOWN, LEGEND_HIDDEN } from './chartTokens'

const props = defineProps({
  /** 扇区：`[{ name, value }]`。必填。 */
  data: { type: Array, required: true },
  height: { type: String, default: '320px' },
  /** 洞里的大字（如合计值 `'1,284'`）。空串 = 不显示。 */
  centerValue: { type: String, default: '' },
  /** 洞里大字下面的小字（如 `'订单总数'`）。 */
  centerTitle: { type: String, default: '' },
  /** 内圈占比（`'58%'` 这种）。越大小洞越大 —— 洞里要放两行字时别低于 55%。 */
  innerRatio: { type: String, default: '58%' },
  outerRatio: { type: String, default: '78%' },
  showLegend: { type: Boolean, default: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  emptyText: { type: String, default: '区间内暂无数据' },
})

const emit = defineEmits(['retry'])

const hasData = computed(() => props.data.length > 0)
/** 三态浮层在时不能把洞里那组数露出来（否则「暂无数据」上面浮着个 `1,284`）。 */
const showCenter = computed(
  () => hasData.value && !props.loading && !props.error && (props.centerValue !== '' || !!props.centerTitle)
)

const option = computed(() => {
  return {
    tooltip: TOOLTIP_ITEM,
    legend: props.showLegend ? LEGEND_SHOWN : LEGEND_HIDDEN,
    series: [
      {
        type: 'pie',
        radius: [props.innerRatio, props.outerRatio],
        center: ['50%', props.showLegend ? '45%' : '50%'],
        // 扇区底色描边 / 标签字色字号 / 引导线全在主题的 `pie` 里；这里只留 formatter。
        label: { formatter: '{b} {d}%' },
        // 洞里放大字 ⇒ 关掉 ECharts 的高亮放大，否则 hover 时扇区会盖住那组数。
        emphasis: { scale: false },
        data: props.data,
      },
    ],
  }
})
</script>

<style scoped>
.dc {
  position: relative;
  width: 100%;
}
.dc-center {
  position: absolute;
  left: 50%;
  top: 45%; /* = option 里 pie 的 `center[1]`，两边必须一起改 */
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  text-align: center;
  /* ⚠️ 必须穿透：否则洞里这块会吃掉 hover，扇区 tooltip 在中心区域失灵。 */
  pointer-events: none;
}
.dc-v {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  color: var(--ink);
  line-height: 1.1;
}
.dc-k {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
</style>
