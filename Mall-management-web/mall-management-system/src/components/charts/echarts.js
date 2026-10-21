/**
 * ECharts 按需引入的**唯一入口**（可视化段 commit 1，2026-10-11）。
 *
 * ⚠️ **只走 `echarts/core` + 具名子模块**，不写 `import * as echarts from 'echarts'`：
 * 后者是全量包（全部 22 种图 + 全部组件 + 两个渲染器），与我们躲开的那份 vendored
 * `docs/美化/c3-admin-preview/echarts.min.js`（5.5.1，1.0 MiB）同档。
 *
 * 本仓迄今为止**零第三方 UI 依赖**（`package.json` 原先只有 `vue` / `vue-router` / `core-js`）
 * ⇒ 本笔是最大的一处破例。破例的代价就用这个文件压住：**用到的才注册**。
 *
 * ⚠️ 本文件是**唯一** `echarts.use(...)` 的地方。新增图表类型时在这里加一行，
 * 不要在页面/组件里再 import 一套（重复 `use` 不会报错，只会悄悄把包撑回去，
 * 而这恰好是 `npm run build` 的产物体积能看出来、代码里看不出来的那种退化）。
 */
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart, HeatmapChart, GaugeChart } from 'echarts/charts'
import {
  GridComponent,        // 折线 / 柱 / 热力图都要直角坐标系网格
  TooltipComponent,     // 三端看板全都开 tooltip
  LegendComponent,      // 多序列图例
  VisualMapComponent,   // HeatmapChart 必需（色阶图例就是它）
  AxisPointerComponent, // tooltip 的 trigger:'axis' 靠它画指示线
  MarkPointComponent,   // 时间栈的峰值 / 谷值数字标注（3a.4）
  MarkLineComponent,    // 耗时直方图的「平均耗时」虚线辅助线（2026-10-17）
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  HeatmapChart,
  GaugeChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
  AxisPointerComponent,
  MarkPointComponent,
  MarkLineComponent,
  CanvasRenderer,
])

// ⚠️ **刻意不注册** `TitleComponent`：本仓的图表一律挂在卡片里，卡片的 `<h2>` 就是标题
//    （见《看板规范》§2）⇒ 图表自己再画一个 title 会与卡头重复。将来要在图内标标题时，
//    在这里补一行即可 —— 别在页面里 `import 'echarts/components'` 整个拿进来。
// ⚠️ 同样**不注册** `UniversalTransition` / `LabelLayout`：看板是静态图，不做图表间联动动画。

// ⚠️ **仅开发/探针用的出口**（3a.2）：把 echarts 挂到 `window`，让页面探针能
//    `__echarts.getInstanceByDom(el).getOption()` 逐键核对**实际生效**的 option。
//    为什么非要有它：主题是「**option > 主题 > 内置默认**」三级合并的结果，
//    **只读源码看不出最后生效的是什么** —— 而 3a.2 这件事的全部风险就是「主题写了、
//    但被 option 覆盖了 / 没被合并进去」，那种失败**不报错、也不改变观感**（因为观感本来就
//    是 option 给的），只能靠读合并结果发现。生产构建里这一行不执行（同 `IconGlyph.vue` 的口径）。
if (import.meta.env.DEV) window.__echarts = echarts

export default echarts
