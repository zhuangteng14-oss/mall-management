/**
 * 图表取值 —— **色值一律从 CSS 变量读，不硬编码色值**（可视化段 commit 1，2026-10-11）。
 *
 * 取值面 = `assets/tokens.css` 里那 16 个图表 token（`--c1`-`--c10` + 辅助 6），
 * **两套主题同名同值面**：浅色 `:root`（商城 / 商家端）与 `html[data-theme="admin"]`（管理端）。
 * ⇒ 同一个组件在两端各自取到对的那套，组件里一个 `#hex` 都不出现。
 *
 * ⚠️ **分工（3a.2，2026-10-16 起写死）**：本文件管 **token 的读法** 与 **每图的参数**
 * （轴的 min/max/formatter、双轴染色、类目数据…）；**「整站一个样」的那些样式键全部搬去了
 * `theme.js`**（色板兜底 / 文字档 / tooltip / 图例 / 轴与网格线 / 图型默认）。
 * 判据：**同一份主题给五个图用，谁都不需要抗议** —— 需要抗议的（随形态/数据变）留在这里。
 * ⚠️ 这条分工不是洁癖：两边都写一遍时，改一处忘一处**不会报错**，只会「某个图跟其它图不一样」。
 * 本笔的 `baseOption` 整个删除、`axisTooltip` / `legendStyle` 两个取样式函数改成常量，就是收这条。
 *
 * ⚠️ **为什么是函数、不是模块级常量**：两套主题下这些名字**同名不同值**。模块级读一次就把
 * 「首次求值时的主题」冻住了 —— 暗色页面若晚于浅色求值就会拿到浅色的值（而在暗底上
 * 那几条浅色线几乎看不见）。⇒ **每次建图时现读**。
 *
 * ⚠️ `getComputedStyle(...).getPropertyValue('--x')` 返回的是**带前导空格的原样字串**
 * （`' #2f6fed'`）⇒ 一律 `.trim()`。少了这一步 ECharts 的颜色解析会**静默失败**、
 * 退化成它自己的默认色板 —— 而 ECharts 默认色板恰好也是蓝橙系，肉眼不容易发觉。
 * 这条不是洁癖，是「错了也看不出来」的那一类。
 *
 * ⚠️ 主题在**页面加载时**由 `public/index.html` / `public/admin.html` 定死（写在 `<html>` 上），
 * 本仓没有运行期切主题 ⇒ 「建图时现读」已经够。真要加切主题，得让组件的 option 也依赖
 * 主题信号（那时把这一层改成 reactive，并把 `theme.js` 的注册时机一并改），别只改这里。
 */

/** 图表内文字的字号 —— 与 `--fs-xs`(12px) 差一档，是刻意的：11px 是 ECharts 生态里
 *  「轴标签不抢戏」的常用档，且本仓 `--fs-xs` 是正文最小档，图表文字不该与正文同档。
 *  ⚠️ 取值点已搬到 `theme.js` 的 `textStyle`（那里引用本常量）—— 本文件只是它的家。 */
export const TEXT_SIZE = 11

/**
 * 读当前主题的图表 token。默认读 `<html>`（本仓主题挂在 `<html>` 上）；
 * 传元素则读该子树（自定义属性会继承，scoped 样式不隔离变量）。
 */
export function chartTokens(el) {
  const cs = getComputedStyle(el || document.documentElement)
  const v = (n) => cs.getPropertyValue(n).trim()
  return {
    series: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((i) => v('--c' + i)),
    gridLine: v('--grid-line'),
    axisLine: v('--axis-line'),
    axisLabel: v('--axis-label'),
    tipBg: v('--tip-bg'),
    tipBorder: v('--tip-border'),
    tipInk: v('--tip-ink'),
    ink: v('--ink'),
    ink2: v('--ink-2'),
    surface: v('--surface'),
    line: v('--line'),
    font: v('--font'),
  }
}

/* ── 浮层与图例：**只剩触发方式** —— 底色 / 描边 / 字色 / 内边距 / 指示线全在 `theme.js` 的
   `tooltip` 与 `legend` 里（3a.2 收口前这三个是取样式函数，各自还带一份样式副本）。 */

/** 轴触发（折线 / 柱）—— 鼠标落在哪一列就出那一列的值。 */
export const TOOLTIP_AXIS = { trigger: 'axis' }
/** 项触发（饼 / 环 / 热力）—— 落在哪个图元上就出哪个图元的值。 */
export const TOOLTIP_ITEM = { trigger: 'item' }

/**
 * 图例：**显示 = `{}`（吃主题默认）**、不显示 = `{ show: false }`。
 *
 * ⚠️ 显示时**不能把这个键整个省掉**：ECharts 里 `legend` 是一个**组件**，必须出现在 option 里
 * 才会存在 —— 主题只能给「已声明的组件」填默认值，**声明不了**组件。
 * （同一类坑还有 `tooltip` / `grid` / `xAxis`：省掉 = 组件根本没有，不是「用默认样式」。）
 */
export const LEGEND_SHOWN = {}
export const LEGEND_HIDDEN = { show: false }

/**
 * 类目轴。`boundaryGap` 折线传 false、柱传 true（ECharts 的既有默认，这里显式写出来）。
 * ⚠️ 轴线的灰 / 刻度不画 / 标签色三件全在 `theme.js` 的 `categoryAxis` 里 ⇒ 本函数只组装
 * **本图特有的**：类目数据、boundaryGap，以及两个可选覆盖（`axisLabel` 与 `splitArea`）。
 *
 * @param opts.axisLabel 只写**要覆盖的键**（如横向柱的 `formatter`）；色由主题给，别在这里重写一遍
 * @param opts.splitArea 热力图要的网格底纹（`{ show: true }`）
 */
export function categoryAxis(data, opts = {}) {
  return {
    type: 'category',
    data,
    boundaryGap: opts.boundaryGap === undefined ? true : opts.boundaryGap,
    ...(opts.axisLabel ? { axisLabel: opts.axisLabel } : {}),
    ...(opts.splitArea ? { splitArea: opts.splitArea } : {}),
  }
}

/**
 * 数值轴。⚠️ **一律不写 `name`** —— ECharts 把 y 轴名摆在轴顶端，多轴时几条会挤成一团
 * （丁版稿踩过这个坑，见 `docs/美化/c3-admin-preview/丁-运营蓝.html` 的 `#chart` 注释）
 * ⇒ 单位并进轴标签 `formatter`，轴名交给卡头那行「QPS / 锁等待 ms / 缓存命中率」。
 *
 * <p>⚠️ **2026-10-11 加两个可选入参**（都是为双 Y 轴，见 `LineChart.vue`），不传 = 逐字同加参前：
 * <ul>
 *   <li>{@code color} —— 把轴标签染成**它那条序列的颜色**。双轴时不写 `name` 就没法标清
 *       「哪个轴是哪个指标」，而染色是最省地方的一种标法（左轴色 = 左轴序列色，肉眼一对即知）。
 *       单轴时**不传**（轴标签保持主题给的 `--axis-label`）。</li>
 *   <li>{@code splitLine} 传 {@code false} —— 关掉本轴的横向网格线。<b>双轴必须关一条</b>：
 *       两条轴的 splitLine 会画在同一批刻度上，叠成一深一浅两条线，看着像画坏了。
 *       ⚠️ 这是**覆盖**主题的默认网格线 ⇒ 必须写成显式的 `{ show: false }`（不能省成 `undefined`，
 *       那样等于没覆盖、又回到双线）。</li>
 * </ul>
 * <p>⚠️ 3a.2 起网格线的**颜色**不再由本函数写（主题的 `valueAxis.splitLine` 给），
 * 本函数只管「这条轴要不要网格线」这个**参数**。
 */
export function valueAxis(opts = {}) {
  const axisLabel = {}
  if (opts.color) axisLabel.color = opts.color
  if (opts.formatter) axisLabel.formatter = opts.formatter
  return {
    type: 'value',
    min: opts.min,
    max: opts.max,
    ...(opts.splitLine === false ? { splitLine: { show: false } } : {}),
    ...(Object.keys(axisLabel).length ? { axisLabel } : {}),
  }
}

/**
 * 两个 6 位 hex 按比例混色（`ratio` = 0 取 `a`、1 取 `b`）。零依赖、纯算术。
 *
 * 用途只有一个：`HeatmapChart` 的 `visualMap.inRange` 需要一个「从底色到主色」的色阶，
 * 而渐变的**起点必须是当前主题的底色**（浅色下是白、暗色下是 `--surface`）
 * ⇒ 直接 `mix(t.surface, t.series[0], 0.08)`，两套主题各自成立，不用写两套色阶。
 *
 * ⚠️ 只吃 `#rgb` / `#rrggbb`。传 `rgb()` 会返回原值不改（宁可不变色，也不要静默变黑）。
 */
export function mixHex(a, b, ratio) {
  const parse = (h) => {
    const s = String(h).trim().replace('#', '')
    if (s.length === 3) return [...s].map((c) => parseInt(c + c, 16))
    if (s.length === 6) return [s.slice(0, 2), s.slice(2, 4), s.slice(4, 6)].map((c) => parseInt(c, 16))
    return null
  }
  const ca = parse(a)
  const cb = parse(b)
  if (!ca || !cb || ca.some(Number.isNaN) || cb.some(Number.isNaN)) return a
  const out = ca.map((c, i) => Math.round(c + (cb[i] - c) * ratio))
  return '#' + out.map((c) => Math.max(0, Math.min(255, c)).toString(16).padStart(2, '0')).join('')
}
