/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a2b-struct-shots.mjs —— 3a.2b（结构重做）三版结构草图的整页截图 + 结构断言

   跑法：node scripts/probes/probe-b7a2b-struct-shots.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP）—— 本探针**不起 dev server、不起后端**：
   三个 HTML 是自包含的（真数据烘在 `_struct-wire.js` 里、图是内联 SVG 画的）
   ⇒ 直接走 `file://` 打开即可。

   ⚠️ 断言只查它真查了的东西（「校验器报 OK ≠ 没问题」）：
      · `WF` 脚本真的加载了（否则图表一个都不会绘）
      · 每版 3 个 `.chart` 且 3 个都真的量到了非零尺寸
      · 横向条 8 行 / 库存 3 行（真数据的行数）
      · **主区实测宽度** —— 这条是本次的推断校验：外壳 232 + padding 32×2 ⇒ 1440 视口下
        主区应为 1144px，`max-width: 1280` 达不到。跑出来不是 1144 就说明我的宽度账算错了。
   ══════════════════════════════════════════════════════════════════════════════ */
import path from 'node:path'
import fs from 'node:fs'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const DIR = path.join(ROOT, 'docs', '美化', 'c3-admin-preview')
const { open, sleep, report } = await import(pathToFileURL(path.join(ROOT, 'docs', '美化', '_cdp.mjs')).href)

const VARIANTS = [
  { file: 'S1-时间栈实体栏.html', png: 'S1-时间栈实体栏.png', charts: 3, hbar: 8, stock: 3 },
  { file: 'S2-黄金信号判据带.html', png: 'S2-黄金信号判据带.png', charts: 3, hbar: 8, stock: 3 },
  { file: 'S3-三问句泳道.html', png: 'S3-三问句泳道.png', charts: 3, hbar: 8, stock: 3 }
]

const VIEW_W = 1440
const VIEW_H = 900
const r = report('3a2b 结构草图')
const b = await open({ w: VIEW_W, h: VIEW_H, headed: false })

for (const v of VARIANTS) {
  const html = path.join(DIR, v.file)
  if (!fs.existsSync(html)) { r.ok(v.file + ' 存在', false, 'NOT FOUND'); continue }
  await b.goto(pathToFileURL(html).href, 1200)

  const st = JSON.parse(await b.evalJS(`(function () {
    var svgs = [].slice.call(document.querySelectorAll('.chart svg'))
    var px = document.querySelector('.px')
    var drawn = svgs.filter(function (s) {
      var c = s.getBoundingClientRect()
      return c.width > 10 && c.height > 10
    })
    /* 信号带元信息「真占几行」—— 数**不同的垂直位置**，不是数 rect 个数
       （range.getClientRects 一个文本 run 给一个 rect，被 <b> 切开就会多给）。
       ⚠️ 为什么不用「溢出」当判据：本例里 clientWidth == scrollWidth == 160 **全绿**，
       而实际是 6 行 —— 因为折的成因是 flex item 码放（.seg 是 column flex），
       不是行内换行，white-space:nowrap 与溢出检测都抓不到。
       ⚠️ 本块在 evalJS 的模板字符串里：注释中不能出现反引号或美元花括号。 */
    var meta = document.querySelector('.band .meta')
    var metaLines = 0
    if (meta) {
      var rng = document.createRange(); rng.selectNodeContents(meta)
      var rects = rng.getClientRects(), seen = {}
      for (var i = 0; i < rects.length; i++) { seen[Math.round(rects[i].top)] = 1 }
      metaLines = Object.keys(seen).length
    }
    /* 库存行的 SKU 折没折行 —— 同一类坑（容器窄了、固定列宽没跟着收）。
       注意不是「有没有溢出」：折行时 scrollWidth 也等于 clientWidth，照样全绿。 */
    var sks = document.querySelectorAll('.stock-row .sk'), skWrap = 0
    for (var j = 0; j < sks.length; j++) {
      var rr = document.createRange(); rr.selectNodeContents(sks[j])
      var rcs = rr.getClientRects(), ss = {}, cnt = 0
      for (var k = 0; k < rcs.length; k++) {
        var key = Math.round(rcs[k].top)
        if (!ss[key]) { ss[key] = 1; cnt++ }
      }
      if (cnt > 1) skWrap++
    }
    return JSON.stringify({
      metaLines: metaLines,
      skWrap: skWrap,
      wf: typeof WF,
      charts: document.querySelectorAll('.chart').length,
      drawn: drawn.length,
      hbar: document.querySelectorAll('.hbar').length,
      heatLabels: document.querySelectorAll('.heat-labels > span').length,
      stock: document.querySelectorAll('.stock-row').length,
      contentW: px ? Math.round(px.getBoundingClientRect().width) : -1,
      clientW: document.documentElement.clientWidth,
      innerW: window.innerWidth,
      sideW: Math.round(document.querySelector('.side').getBoundingClientRect().width),
      mainPadL: getComputedStyle(document.querySelector('.main')).paddingLeft,
      pageH: document.documentElement.scrollHeight
    })
  })()`))

  r.ok(v.file + ' · WF 已加载', st.wf === 'object', st.wf)
  r.ok(v.file + ' · .chart 数', st.charts === v.charts, st.charts)
  r.ok(v.file + ' · 图真的绘出来了', st.drawn === v.charts, st.drawn + '/' + v.charts)
  r.ok(v.file + ' · 横向条行数', st.hbar === v.hbar, st.hbar)
  r.ok(v.file + ' · 库存行数', st.stock === v.stock, st.stock)
  r.ok(v.file + ' · 热力行名贴行（3 锁名）', st.heatLabels === 3, st.heatLabels)
  if (st.metaLines) r.ok(v.file + ' · 信号带元信息 3 行（未被 flex item 码放）', st.metaLines === 3, st.metaLines + ' 行')
  r.ok(v.file + ' · 库存 SKU 不折行', st.skWrap === 0, st.skWrap + ' 行折了')
  /* ⚠️ 校验的是**模型**不是常数：`.px` 宽 = clientW − 侧栏 232 − 主区左右 padding。
     第一版这里硬写 `=== 1144`（1440 − 232 − 64），跑出来 1129、我以为是算错了；
     实测 `innerWidth 1440 / clientWidth 1425` ⇒ **页面高于视口时经典滚动条占 15px**，
     1129 才是真值。写死常数会把「模型对不对」和「这一跑有没有滚动条」混成一条断言。 */
  const expectW = st.clientW - st.sideW - parseFloat(st.mainPadL) * 2
  r.ok(v.file + ' · 主区宽 == clientW − 232 − 2×pad', Math.abs(st.contentW - expectW) <= 1, st.contentW + ' vs ' + expectW)
  console.log(`     ${v.file} 页高 ${st.pageH}px · 主区宽 ${st.contentW}px（视口 ${st.innerW} / 客户区 ${st.clientW} / 滚动条 ${st.innerW - st.clientW}）`)

  const out = path.join(DIR, v.png)
  await b.shotFull(out)
  r.ok(v.png + ' 落盘', fs.existsSync(out) && fs.statSync(out).size > 5000,
    fs.existsSync(out) ? fs.statSync(out).size + 'B' : 'MISSING')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
