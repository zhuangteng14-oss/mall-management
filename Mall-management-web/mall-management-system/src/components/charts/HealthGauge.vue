<template>
  <BaseChart :option="option" :height="height" />
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import BaseChart from './BaseChart.vue'
import { chartTokens } from './chartTokens'

const props = defineProps({
  /** 0–100。 */
  score: { type: Number, default: 0 },
  /** 等级文字（健康 / 关注 / 告警 / 危险）。 */
  grade: { type: String, default: '—' },
  height: { type: String, default: '180px' },
})

function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 分数越低越暖：危险取 `--danger`，告警取 `--c4`，关注取 `--c1`，健康取 `--c3`。 */
const tone = computed(() => {
  if (props.score < 35) return cssVar('--danger', '#d64545')
  if (props.score < 60) return cssVar('--c4', '#f16400')
  if (props.score < 85) return cssVar('--c1', '#2f6fed')
  return cssVar('--c3', '#2f9e6e')
})

const option = computed(() => {
  const t = chartTokens()
  return {
    animation: false,
    series: [{
      type: 'gauge',
      min: 0,
      max: 100,
      startAngle: 210,
      endAngle: -30,
      radius: '96%',
      center: ['50%', '56%'],
      progress: { show: true, width: 11, itemStyle: { color: tone.value } },
      axisLine: {
        lineStyle: {
          width: 11,
          color: [
            [0.35, cssVar('--danger', '#d64545')],
            [0.6, cssVar('--c4', '#f16400')],
            [0.85, cssVar('--c1', '#2f6fed')],
            [1, cssVar('--c3', '#2f9e6e')],
          ],
        },
      },
      axisTick: { show: false },
      splitLine: { show: false },
      axisLabel: { show: false },
      pointer: { show: false },
      anchor: { show: false },
      title: {
        show: true,
        offsetCenter: [0, '26%'],
        color: t.ink2,
        fontSize: 11,
      },
      detail: {
        valueAnimation: false,
        offsetCenter: [0, '-2%'],
        formatter: '{value}',
        color: tone.value,
        fontSize: 34,
        fontWeight: 600,
      },
      data: [{ value: Math.max(0, Math.min(100, Math.round(props.score))), name: props.grade }],
    }],
  }
})
</script>
