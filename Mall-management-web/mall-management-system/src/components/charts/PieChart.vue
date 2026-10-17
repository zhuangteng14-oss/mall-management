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
import { TOOLTIP_ITEM, LEGEND_SHOWN, LEGEND_HIDDEN } from './chartTokens'

const props = defineProps({
  /** 扇区：`[{ name, value }]`。必填。 */
  data: { type: Array, required: true },
  height: { type: String, default: '320px' },
  /** 南丁格尔玫瑰（半径随值变）—— 占比之外还想看**绝对量**差异时用。 */
  rose: { type: Boolean, default: false },
  /** 标签形态：`'name'` 只写名 / `'percent'` 只写占比 / `'both'` 两行 / `''` 不出标签。 */
  labelMode: { type: String, default: 'both' },
  showLegend: { type: Boolean, default: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  emptyText: { type: String, default: '区间内暂无数据' },
})

const emit = defineEmits(['retry'])

const hasData = computed(() => props.data.length > 0)

const option = computed(() => {
  const { labelMode } = props
  return {
    tooltip: TOOLTIP_ITEM,
    legend: props.showLegend ? LEGEND_SHOWN : LEGEND_HIDDEN,
    series: [
      {
        type: 'pie',
        // ⚠️ 玫瑰图用 `roseType: 'radius'`（**不改面积口径**：角度仍是占比）；
        //    不用 `'area'` —— 那个把角度也一起改掉，占比就读不出来了。
        roseType: props.rose ? 'radius' : undefined,
        radius: props.rose ? ['12%', '70%'] : '70%',
        // 图例在底部 ⇒ 圆心略上移，否则扇区会压到图例。
        center: ['50%', props.showLegend ? '45%' : '50%'],
        // ⚠️ 扇区底色描边 / 标签字色字号 / 引导线：都在主题的 `pie` 里。
        //    这里只留**本图特有的** —— label 的 formatter 与「不出标签」这一档。
        label:
          labelMode === ''
            ? { show: false }
            : {
                formatter:
                  labelMode === 'name' ? '{b}'
                    : labelMode === 'percent' ? '{d}%'
                      : '{b}\n{d}%',
              },
        ...(labelMode === '' ? { labelLine: { show: false } } : {}),
        data: props.data,
      },
    ],
  }
})
</script>
