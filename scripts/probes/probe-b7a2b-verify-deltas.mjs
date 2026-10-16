// verify-deltas.mjs —— 把「乙版到底改了什么」**读回来**（3a.2b，2026-10-16）。
//
// 为什么要有它：预览页是给人看的，**不能只靠看**。这份脚本在两页上跑同一段读取，逐条打印：
//   · 维度 3 / 4 / 7 的 CSS 那一半 —— 读**计算值**（`getComputedStyle`），不读源码
//   · 维度 6 / 7 的图表那一半 —— 读**ECharts 实例合并后的 option**（`getOption()`）
// ⇒ 判据是「甲版 = 现状值、乙版 = 目标值」，两列并排，改没改一眼可查（而不是「我觉得改了」）。
// 跑法：node _work/3a2b/verify-deltas.mjs
import { open } from '../../docs/美化/_cdp.mjs'

const PREV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/c3-admin-preview/'
const READ = `(() => {
  const cs = (sel, prop, pseudo) => { const e = document.querySelector(sel); return e ? getComputedStyle(e, pseudo || null).getPropertyValue(prop) : '∅' }
  const E = window.echarts
  const roots = [...document.querySelectorAll('[_echarts_instance_]')]
  const opts = roots.map((r) => { const i = E.getInstanceByDom(r); return i ? i.getOption() : null }).filter(Boolean)
  const line = opts.find((o) => (o.series || []).some((s) => s.type === 'line'))
  return {
    '维度3 · .kpi border-color': cs('.kpi', 'border-top-color'),
    '维度3 · .kpi background-image': cs('.kpi', 'background-image').slice(0, 46),
    '维度3 · .kpi::before content/height': cs('.kpi', 'content', '::before') + ' / ' + cs('.kpi', 'height', '::before'),
    '维度3 · .kpi-k color': cs('.kpi-k', 'color'),
    '维度3 · .kpi-k letter-spacing': cs('.kpi-k', 'letter-spacing'),
    '维度4 · .kpi-n 子元素': document.querySelector('.kpi-n') ? document.querySelector('.kpi-n').innerHTML.replace(/ data-v-[a-z0-9]+=""/g, '') : '∅',
    '维度4 · .n-dec 字号/色': (() => { const e = document.querySelector('.kpi-n .n-dec'); return e ? getComputedStyle(e).fontSize + ' / ' + getComputedStyle(e).color : '∅（无小数或未拆）' })(),
    '维度4 · .kpi-d padding-left': cs('.kpi-d', 'padding-left'),
    '维度4 · .kpi-d::before 宽/色': cs('.kpi-d', 'width', '::before') + ' / ' + cs('.kpi-d', 'background-color', '::before'),
    '维度4 · .kpi-d 的 tone 类': document.querySelector('.kpi-d') ? document.querySelector('.kpi-d').className.replace(/ data-v-[a-z0-9]+/g, '') : '∅',
    '维度6 · yAxis.splitLine 色/线型': line ? JSON.stringify({ c: line.yAxis[0].splitLine.lineStyle.color, t: line.yAxis[0].splitLine.lineStyle.type }) : '∅',
    '维度6 · xAxis.axisLine.show': line ? JSON.stringify(line.xAxis[0].axisLine.show) : '∅',
    '维度6 · tooltip 圆角/内距': line ? JSON.stringify({ r: line.tooltip[0].borderRadius, p: line.tooltip[0].padding }) : '∅',
    '维度6 · line symbolSize': line ? line.series[0].symbolSize : '∅',
    '维度7 · 图表 animationDuration/Easing': line ? line.animationDuration + ' / ' + line.animationEasing : '∅',
    '维度7 · html.pv-motion 在不在': document.documentElement.classList.contains('pv-motion'),
  }
})()`

const b = await open({ w: 1440, h: 1000 })
const out = {}
try {
  for (const [v, file] of [['甲', '指标页-甲版.html'], ['乙', '指标页-乙版.html']]) {
    await b.goto('file:///' + encodeURI(PREV + file), 2600)
    out[v] = await b.evalJS(READ)
  }
} finally { await b.close() }

const keys = Object.keys(out['甲'])
const w = Math.max(...keys.map((k) => k.length))
console.log('判据'.padEnd(w) + ' | 甲版（= 现状）'.padEnd(34) + ' | 乙版')
console.log('-'.repeat(w + 60))
let same = 0
for (const k of keys) {
  const a = out['甲'][k]
  const y = out['乙'][k]
  if (a === y) same++
  console.log(k.padEnd(w) + ' | ' + String(a).slice(0, 32).padEnd(32) + ' | ' + String(y).slice(0, 60) + (a === y ? '   ← 两版相同' : ''))
}
console.log(`\n共 ${keys.length} 条判据，其中 ${same} 条两版相同（其余 = 本笔的差量）`)
