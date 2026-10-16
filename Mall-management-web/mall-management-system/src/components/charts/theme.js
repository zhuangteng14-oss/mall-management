/**
 * ECharts 主题（3a.2，2026-10-16）—— 图表面**唯一的取值点**。
 *
 * <h3>【为什么是 `.js` 而不是 `.json`】</h3>
 * 主题的规范载体是「一个可 JSON 序列化的 theme 对象」，本文件交的就是**等价的 theme 对象**，
 * 键名逐条对应 ECharts 的主题规范（`categoryAxis` / `valueAxis` / `line` / `bar` / `pie` /
 * `heatmap` / `tooltip` / `legend` / `grid` …）。不交字面 `.json` 的唯一原因：**色值必须现读
 * CSS 变量**（本仓硬纪律，见 `chartTokens.js` 头注），而 `.json` 读不到变量。
 * ⇒ 拆开看：「JSON 的那部分」= 结构与档位（下面逐条）；「变量的那部分」= 色值（`buildTheme(t)`
 * 的入参）。
 *
 * <h3>【它在合并链里的位置 —— 决定它到底管得到什么】</h3>
 * ECharts 的取值顺序是 **option（组件传的） > theme（本文件） > 内置默认**。
 * ⇒ 主题只在 **option 没写到的键** 上生效。所以本文件承载的恰好是两类东西：
 * <ol>
 *   <li><b>此前没人在管的那些</b>：五个图各自只写「自己那张图特有」的键，其余全吃 ECharts
 *       内置默认（与设计系统无关的值）。⇒ 这里把它们一次收干净；</li>
 *   <li><b>甲/乙两版的差异面</b>（3a.2b）：两版共用同一份 token，**差异用「第二份主题」表达**
 *       —— 这正是本文件被拆出来的目的（见下面「乙版怎么接」）。</li>
 * </ol>
 * ⚠️ **别把图特有的东西搬进来**：`grid` 的左右留白、轴的 `min`/`max`/`formatter`、双轴染色、
 * 柱子的圆角方向、饼图 label 的形态 —— 那些是**每图的参数**（随数据/形态变），属于组件；
 * 这里只放**整站一个样**的键。判据：**同一份主题给五个图用，谁都不需要抗议**。
 *
 * <h3>【⚠️ 两个必须记住的边界】</h3>
 * <ol>
 *   <li><b>主题不能「声明」组件</b>：ECharts 里 `legend` / `tooltip` / `grid` / `xAxis` 这些
 *       组件必须**出现在 option 里**才会存在，主题只负责给已声明的组件填默认值。
 *       ⇒ 组件里要写 `legend: {}`（用主题默认）而不是把 `legend` 整个省掉（那样图例根本不出现）。</li>
 *   <li><b>「改了主题但没人看得见」是这类重构的典型翻车</b>：若 option 把每个键都写死了，
 *       主题就是一段**装饰性代码**（改它、跑它、什么都不会发生）。⇒ 本笔同时把 `chartTokens.js`
 *       里重复的那几组键**删掉**（`baseOption` 整个删除），并用「改前/改后 `getOption()` 逐键比」
 *       证明**观感零漂移**（证据见本笔收笔报告）。</li>
 * </ol>
 *
 * <h3>【乙版怎么接（3a.2b 预留）】</h3>
 * 乙版**不是**第二套设计语言：色板仍取自同一批 token（`chartTokens()`），差异只在本文件的
 * **结构档位**上换一组值（网格留白 / 轴与分割线 / tooltip 形态 / 图型默认）。届时在这里加一个
 * `buildTheme(t, variant)`（或并列一个 `buildThemeB(t)`），`BaseChart` 按…（3a.2b 再定接线方式）
 * —— ⚠️ 别为此在组件里加分支。
 */
import echarts from './echarts'
import { chartTokens, TEXT_SIZE } from './chartTokens'

/**
 * 主题名 —— `BaseChart.vue` 的 `echarts.init(el, CHART_THEME, …)` 用它。
 * ⚠️ 名字一旦注册进 ECharts 就是全局的（`registerTheme` 是全局注册表），改名字要同时改
 * `init` 那一处；不要出现第二个名字指向同一份主题。
 */
export const CHART_THEME = 'minke'

/**
 * 主题对象（纯函数：入参就是 token 快照）。
 *
 * ⚠️ **传 `t` 而不是在内部读 `chartTokens()`** —— 两个理由：
 * ① 便于在探针里喂一份**假 token** 断言（不需要真 DOM / 真 CSS 变量）；
 * ② 取色时机由**调用方**决定（见 `registerChartTheme`：必须晚于样式生效）。
 */
export function buildTheme(t) {
  return {
    // ── ① 色 ────────────────────────────────────────────────────────────────
    // 十色板的**兜底位**：某条序列若没自带颜色（新图忘了传、或将来加图型），
    // 取到的是本主题的这一份，而不是 ECharts 自己的默认色（那套是蓝橙系、
    // 恰好与本仓色板相近 ⇒ 混进来**肉眼看不出来**）。序列自带的色仍以 option 为准。
    color: t.series,
    backgroundColor: 'transparent',
    // 图上的一切文字都在这里定档：`--font` + 11px（图表档，与 `--fs-xs` 差一档，理由见
    // `chartTokens.js` 的 `TEXT_SIZE`）。默认色取 `--ink-2`，与「轴标签用 `--axis-label`」并存
    // —— 轴标签是它自己的一档，其余文字（label / visualMap / 图例）走这一档。
    textStyle: { fontFamily: t.font, fontSize: TEXT_SIZE, color: t.ink2 },

    // ── ② 直角坐标系 ────────────────────────────────────────────────────────
    // 兜底的四边留白：给「没有自己写 grid 的新图」一个不贴边的默认。
    // ⚠️ 现有五个图**各自都写了 grid**（左右留白随形态变：横向柱要 `containLabel`），
    // 所以这里改值不会动到它们 —— 这是**兜底位**，不是它们的取值点。
    grid: { left: 56, right: 24, top: 18, bottom: 44 },
    categoryAxis: {
      axisLine: { lineStyle: { color: t.axisLine } },
      axisTick: { show: false },
      axisLabel: { color: t.axisLabel },
    },
    valueAxis: {
      // 网格线：看得见但不抢线（`--grid-line` 比 `--line` 再浅一档，理由见 tokens.css）。
      splitLine: { lineStyle: { color: t.gridLine, type: 'solid' } },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: t.axisLabel },
    },

    // ── ③ 浮层与图例 ────────────────────────────────────────────────────────
    // tooltip 底色/描边/字色三档 + 内边距；`axisPointer` 是指示线（虚线、用轴线的灰）。
    tooltip: {
      backgroundColor: t.tipBg,
      borderColor: t.tipBorder,
      borderWidth: 1,
      padding: [8, 11],
      textStyle: { color: t.tipInk, fontSize: TEXT_SIZE },
      axisPointer: { type: 'line', lineStyle: { color: t.axisLine, width: 1, type: 'dashed' } },
    },
    // 图例在**底部居中**：顶部留给卡头（本仓图表一律挂在卡片里，卡片的 `<h2>` 就是标题 ——
    // 这也是 `echarts.js` 刻意不注册 `TitleComponent` 的原因）。
    legend: {
      bottom: 0,
      left: 'center',
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 18,
      icon: 'roundRect',
      textStyle: { color: t.ink2, fontSize: TEXT_SIZE },
    },

    // ── ④ 图型默认（逐条对应五个组件此前的写法，收进来做单一取值点）────────────
    // ⚠️ 只放「与形态无关」的：`bar` 的圆角方向（竖柱上两角 / 横柱右两角）与 `maxBarWidth`
    //    是**参数**，留在 `BarChart.vue`。
    line: { lineStyle: { width: 2 }, symbol: 'circle', symbolSize: 6 },
    // ⚠️ 扇区/热力格的描边色用**当前主题的底色**（浅色 = 白、暗色 = `--surface`）：
    //    写死白的话暗色主题下格子之间会出现一圈白框。
    pie: {
      itemStyle: { borderColor: t.surface, borderWidth: 2 },
      label: { color: t.ink2, fontSize: TEXT_SIZE },
      labelLine: { lineStyle: { color: t.axisLine } },
    },
    heatmap: {
      label: { show: false },
      itemStyle: { borderColor: t.surface, borderWidth: 2 },
      emphasis: { itemStyle: { borderColor: t.ink, borderWidth: 2 } },
    },
    // 色阶（热力图专用）只带**文字档**；`inRange` 的色阶本身由图表按**当前底色**现算
    // （见 `HeatmapChart.vue` 的 `mixHex` —— 起点必须是当前主题的底，不能写死白）。
    visualMap: { textStyle: { color: t.ink2, fontSize: TEXT_SIZE } },
  }
}

/**
 * 把主题注册进 ECharts（**幂等**）。返回主题名，便于 `echarts.init(el, registerChartTheme())`
 * 这样一行写完。
 *
 * ⚠️ **必须在样式生效之后调用**：色值来自 CSS 变量，而变量由 `tokens.css` 提供 ⇒ 若在
 * `tokens.css` 之前取，拿到的是空串（ECharts 会静默退化成内置色板，肉眼极难发现 ——
 * `chartTokens.js` 头注记了同一类坑）。`BaseChart.vue` 在 `onMounted` 里调，那时
 * `<link>` 已生效。
 * ⚠️ **只注册一次**：ECharts 的注册表是全局的，反复注册同一名字只是白做一遍工作；
 * 主题在**页面加载时**由 `<html data-theme>` 定死（本仓没有运行期切主题）⇒ 一次足矣。
 * 真要加切主题，得让图表 option 也依赖主题信号 —— 那时**改的是组件**，不是这里。
 */
let registered = false
export function registerChartTheme() {
  if (!registered) {
    echarts.registerTheme(CHART_THEME, buildTheme(chartTokens()))
    registered = true
  }
  return CHART_THEME
}
