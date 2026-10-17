// probe-b7a2b-mk-preview.mjs —— 由「真页面快照」生成两份**双击可开**的指标页预览（3a.2b，2026-10-16）。
//
// 整条流水线（四步，前一步的产物喂后一步；只有第 2 步的中间产物不落盘仓）：
//   ① probe-b7a2b-metrics-snapshot.mjs   真页面 → `_work/3a2b/snapshot.json`（DOM+样式+每块图的 option）
//   ② **本脚本**                           snapshot.json → `docs/美化/c3-admin-preview/指标页-{甲,乙}版.html` + `metrics-snapshot.css`
//   ③ probe-b7a2b-preview-shots.mjs       两版 + 真页面 → 逐字节核对（画布哈希/布局）+ `out-b7a2b-*.png`
//   ④ probe-b7a2b-shot-compare.mjs        `b7a2b-compare.html` → `docs/美化/c3-admin-preview/指标页-甲乙对照.png`
//   （`probe-b7a2b-heat-diff.mjs` 是 ③ 的补充：拿 ③ 的 `DUMP=` 原始像素，把「差在哪」定位到行。
//     `probe-b7a2b-zoom-shot.mjs` 是给人看的 4× 放大镜。）
//
//   输入：_work/3a2b/snapshot.json（① 的产物 · **不落盘仓**，是中间物）
//         scripts/probes/b7a2b-pv-render.js      （甲/乙共用的运行时）
//         scripts/probes/b7a2b-pv-variant-b.css  （**只有乙版**引入的差量样式）
//   输出：docs/美化/c3-admin-preview/metrics-snapshot.css   快照样式（两版共用一份，字体改指仓内）
//         docs/美化/c3-admin-preview/指标页-甲版.html        快照原样 + 复用渲染
//         docs/美化/c3-admin-preview/指标页-乙版.html        快照 + 维度 3/4/6/7 差量
//
// 设计取舍（写给后人）：
//   ① **样式进独立 .css**（而不是各自内联）：快照样式有 40 万字符，内联进两份 HTML 会让
//      「甲/乙到底差在哪」淹没在里面。现在两版的**差异只剩**：HTML 里那一个 `<style id="pv-variant-b">`
//      块 + `b7a2b-pv-render.js` 里三个带「乙版差量」标记的函数。
//   ② **甲版一个键都不改**（options 原样、无差量样式）⇒ 甲版预览页 ≡ 真页面，
//      这条**可验**：`probe-b7a2b-preview-shots.mjs` 用同一套截图口径各拍一张并排比。
//   ③ 字体：快照样式里的 `url(http://localhost:8080/fonts/<名>.<hash>.woff2)` 改指**仓内源文件**
//      （`src/assets/fonts/<名>.woff2`，dev server 只是给它加了内容哈希）。
//      ⚠️ 我曾**猜** `file://` 下会被 CORS 拦（origin=null）—— **实测推翻**，两份字体都加载成功。
//   ④ **注释不进产物**（CSS 与 DOM 都去注释）：本仓注释习惯写 `文件:行号`，原样抄进仓 =
//      往 `refs` 的引用集合里塞几十条**按设计永不更新**的 token。去前/去后画布哈希逐字节相同。
import { readFileSync, writeFileSync } from 'node:fs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const WEB = 'Mall-management-web/mall-management-system'
const OUT = `${ROOT}/docs/美化/c3-admin-preview`
const FONT_REL = `../../../${WEB}/src/assets/fonts/`

const snap = JSON.parse(readFileSync(`${ROOT}/_work/3a2b/snapshot.json`, 'utf8'))
const render = readFileSync(`${ROOT}/scripts/probes/b7a2b-pv-render.js`, 'utf8')
const deltaCss = readFileSync(`${ROOT}/scripts/probes/b7a2b-pv-variant-b.css`, 'utf8')

// ── ① 快照样式 ──────────────────────────────────────────────────────────────────────
let css = snap.styles.replace(
  /https?:\/\/localhost:8080\/fonts\/([a-z0-9-]+)\.[0-9a-f]{8}\.woff2/g,
  (m, name) => `${FONT_REL}${name}.woff2`,
)
const leftover = [...new Set(css.match(/url\([^)]*(localhost|127\.0\.0\.1)[^)]*\)/g) || [])]
const fontCount = (css.match(new RegExp(FONT_REL.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')) || []).length

/* ── ①b 去掉 CSS 注释（**唯一**的第二处改动）─────────────────────────────────────────
   为什么：真页面的 <style> 里带着本仓自己写的注释，而本仓注释的习惯是写 `文件:行号`
   ⇒ 「整份取下」等于把 ~60 个**按设计就永不更新**的引用 token 抄进仓里（`refs` 工具会把
   产物文件也当成引用来源，将来只会报成噪声）。注释**不参与渲染**。
   怎么证明没改坏：去注释后重跑 `probe-b7a2b-preview-shots.mjs`，四块画布的哈希/ink 与
   去注释前**逐字节相同**（这才是判据，不是「注释无所谓」这句话本身）。
   ⚠️ 下面是**认字符串**的剥离，不是裸正则 —— `content: "/*"` 或 data URI 里出现 `/*`
   时裸正则会连真内容一起吃掉；并且用**一个空格**替换而不是删空（CSS 里注释可充当词法
   分隔（中间夹一条注释的两个标识符，删空会粘成一个词）。 */
function stripCssComments(s) {
  let out = '', i = 0, q = null
  while (i < s.length) {
    const c = s[i]
    if (q) {                                   // 字符串内：只找闭合引号，尊重反斜杠转义
      if (c === '\\') { out += s.slice(i, i + 2); i += 2; continue }
      if (c === q) q = null
      out += c; i++; continue
    }
    if (c === '"' || c === "'") { q = c; out += c; i++; continue }
    if (c === '/' && s[i + 1] === '*') { const j = s.indexOf('*/', i + 2); out += ' '; i = j < 0 ? s.length : j + 2; continue }
    out += c; i++
  }
  return out
}
const rawLen = css.length
css = stripCssComments(css)
const cssTokens = [...new Set(css.match(/[A-Za-z0-9_./-]+\.(vue|js|java|md|css|html):[0-9]+/g) || [])]

writeFileSync(`${OUT}/metrics-snapshot.css`,
  `/* 指标页两版预览 · 快照样式（两版共用）—— 由 \`probe-b7a2b-metrics-snapshot.mjs\` 从**真页面**\n` +
  `   的 <style> 里整份取下（${snap.styles.length} 字符），做了两件事：\n` +
  `   ① 字体 URL 从 dev server 的 http://localhost:8080/fonts/<名>.<hash>.woff2 改指仓内源文件（${fontCount} 处）；\n` +
  `   ② **去掉注释**（${rawLen} → ${css.length} 字符）—— 注释不参与渲染，但本仓注释习惯写 \`文件:行号\`，\n` +
  `      留着会把几十条**按设计永不更新**的引用 token 抄进仓里（` +
  (cssTokens.length ? `⚠️ 之后仍剩 ${cssTokens.length} 条（在非注释处，别手删）：${cssTokens.slice(0, 4).join(' ')}…` : `实测去完注释后剩 0 条` + `）。`) + `\n` +
  `   ⚠️ 不要手改本文件 —— 它是产物；要改请改真代码再重跑探针。 */\n${css}\n`, 'utf8')

// ── ② 两份 HTML ─────────────────────────────────────────────────────────────────────
const NOTE = `<!--
  指标页两版对比预览（3a.2b）—— 静态快照，**不是**真页面。

  它从哪来：\`scripts/probes/probe-b7a2b-metrics-snapshot.mjs\` 在真页面的**稳定态**下把
  · 整个 #app 的 DOM      · 页面里全部 <style> 的文本      · 每块图**合并后**的 option
  落成一份 JSON，再由 \`scripts/probes/probe-b7a2b-mk-preview.mjs\` 拼成这一页。

  ⚠️ 三点须知（免得读成更强的结论）：
  1. **画布是重渲的**：outerHTML 搬不动 canvas 像素 ⇒ 四块图用**真页面当时那份 option** 重新 init。
     甲版一个键都没改（\`opts\` 原样）⇒ 它≡真页面；乙版在 option 上打了维度 6 的差量。
  2. **可交互性是零**：按钮/开关点不动（这是视觉稿，不是 demo）。
  3. **字体**：本仓字体是自托管 woff2，我原**猜** file:// 下会被 CORS 拒载（origin=null）——
     **实测推翻**：\`document.fonts.check('12px "Noto Sans SC"')\` 与 mono 两份在 file:// 下都是 true。
     字体文件按相对路径指回仓内（\`${WEB}/src/assets/fonts/\`），所以双击打开也不掉字。
  4. **注释已去**：CSS 与 DOM 里的注释都不进这一页 —— 本仓注释习惯写 \`文件:行号\`，
     原样抄下来等于把几十条**按设计永不更新**的引用 token 塞进仓（去注释后实测剩 0 条）。
     注释不参与渲染；去前/去后的四块画布哈希逐字节相同（\`probe-b7a2b-preview-shots.mjs\`）。
-->

<!--
  ── 甲/乙的差异**只有四维**（用户 2026-10-16 裁决：色板不动 / 不引字体 / 迷你图挂账）──
  维度 3  KPI 卡层次      甲：四边 1px 发丝线 + 平涂 --surface；乙：去描边 + 卡底渐变 + 顶边 1px 高光
  维度 4  数字分档        甲：整串同一个字号；乙：小数部降一档 .78em/--ink-2 + 环比行 2px 状态色竖条
  维度 6  图表网格·tooltip 甲：实线网格 + 类目轴轴线 + 方角浮层 [8,11]；乙：虚线网格 + 轴线上隐 + 圆角浮层 [10,14] + 拐点 6→4
  维度 7  动效            甲：数字 0.3s 线性插值；乙：0.45s easeOutCubic + 卡依次 40ms 淡入上移 + 四图依次错开 60ms
  ⇒ 差异的**落点**（甲版没有、乙版才有的那两块）：
     <style id="pv-variant-b">（维度 3/4/7 的 CSS 那一半）
     pv-render.js 里三处标着「乙版差量 · 维度 N」的函数/块
-->

<!--
  快照元信息
  · 入口 ${'admin.html#/admin/observe/metrics'}（${snap.viewport.w}x${snap.viewport.h}，内容高 ${snap.viewport.scrollH}）
  · 假流 ${snap.frames} 拍（真 HTTP 8088，**流不关**）· 收敛：${snap.stable ? '是' : '否'}
  · 取自 ${snap.at} · 画布 ${snap.chartCount} 块
-->`

const optsJson = JSON.stringify(snap.options).replace(/<\//g, '<\\/')

/* DOM 里的注释同理去掉（快照的 DOM 带着 Vue 模板注释，其中一条正是 `文件:行号`）。
   ⚠️ HTML 注释**不能**出现在 <script>/<style> 里被误伤 —— 这里只对**快照 DOM** 做，
     那份 DOM 里没有内联 <script>/<style>（本页的样式走外链、脚本在 body 末尾另外拼）。 */
const domSrc = snap.html.replace(/<!--[\s\S]*?-->/g, '')
const domTokens = [...new Set(domSrc.match(/[A-Za-z0-9_./-]+\.(vue|js|java|md|css|html):[0-9]+/g) || [])]

const page = (variant) => {
  const yi = variant === 'yi'
  const title = `指标页 · ${yi ? '乙版（视觉也换）' : '甲版（结构对了就高级）'} · 预览`
  return [
    '<!doctype html>',
    '<html lang="zh-CN" data-theme="admin">',
    '<head>',
    '<meta charset="utf-8">',
    `<title>${title}</title>`,
    NOTE,
    '<link rel="stylesheet" href="./metrics-snapshot.css">',
    yi ? `<style id="pv-variant-b">\n${deltaCss}\n</style>` : '<!-- 甲版：无差量样式（快照原样）-->',
    '</head>',
    '<body>',
    domSrc,
    '<script src="./echarts.min.js"></script>',
    `<script>window.PV = { variant: "${variant}", opts: ${optsJson} }</script>`,
    `<script>\n${render}\n</script>`,
    '</body>',
    '</html>',
    '',
  ].join('\n')
}

for (const [variant, file] of [['jia', '指标页-甲版.html'], ['yi', '指标页-乙版.html']]) {
  writeFileSync(`${OUT}/${file}`, page(variant), 'utf8')
}

console.log(`样式：metrics-snapshot.css（${snap.styles.length} → 去注释 ${css.length} 字符 → 字体改指 ${fontCount} 处）`)
console.log(`DOM 注释：${snap.html.length} → ${domSrc.length} 字符 · 去后残留 token ${domTokens.length} 条${domTokens.length ? '：' + domTokens.join(' ') : ''}`)
if (leftover.length) console.log(`⚠️ 仍指向 localhost 的 url() ${leftover.length} 处：${leftover.slice(0, 3).join(' ')}`)
else console.log('✅ 已无指向 localhost 的 url()')
for (const f of ['指标页-甲版.html', '指标页-乙版.html', 'metrics-snapshot.css']) {
  const s = readFileSync(`${OUT}/${f}`)
  console.log(`  ${f}  ${s.length} 字符`)
}
