/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a2b-struct-shots.mjs —— 3a.2b（结构重做）结构草图的整页截图 + 结构断言

   跑法：node scripts/probes/probe-b7a2b-struct-shots.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP）—— 本探针**不起 dev server、不起后端**：
   两个 HTML 是自包含的（真数据烘在 `_struct-wire.js` 里、图是内联 SVG 画的）
   ⇒ 直接走 `file://` 打开即可。

   ⚠️ 断言只查它真查了的东西（「校验器报 OK ≠ 没问题」）：
      · `WF` 脚本真的加载了（否则图表一个都不会绘）
      · 每版 `.chart` 数**且**每个都真的量到了非零尺寸
      · 横向条 8 行 / 库存 **8** 行 / 热力行名 **2** 行（都是**本窗真数据**的行数）
      · **主区实测宽度** —— 外壳 232 + padding 32×2 ⇒ 1440 视口下主区应为 1144px，
        `max-width: 1280` 达不到。跑出来不是 1144 就说明宽度账算错了
        （实测 1129 是因为页面高于视口、经典滚动条占 15px —— **模型**要对，不是常数要对）。
      · **x 轴对齐**（本笔新加，见下）

   ── x 轴对齐：为什么必须**量**而不是**算** ────────────────────────────────────
   S1 的主张是「五张**上下叠**的图共用同一根 x 轴」。上一版这句话是**假的**：
   热力图的行名走一个 62px 的 HTML 左列、SVG 只占剩下的宽，而折线图 SVG 占满整宽
   ⇒ 两者的「第 i 列」落在不同像素上（差 36px 量级），但两边都「看起来有个左边距」，
   肉眼与直觉都发现不了。所以这里量**画出来的**绘图区底边（`_struct-wire.js` 给每张图
   渲的 `.plot-edge`）：
     ① 每张图：绘图区左沿 == SVG 左沿 + SVG 宽 × PAD_L/VBW，右沿同理（同一容差内）；
     ② **叠图**组（S1 的 `.axis-group`）内：各图的左右沿**逐像素相同**。
   容差 1.5px = 0.5 描边 + 取整。

   ⚠️ 并排图（S3 泳道 2 的 `.lane3`）**不能照抄这条判据**：三张图左右并排、各有自己的轴，
   左右沿本来就**不该**相同（实测差 664.7px —— 这是对的）。并排形态可量的是「**等宽 +
   逐拍格距相同**」，它保证「同一拍在第 i 张里落在各自的同一相对位置」＝ 共享游标能同步，
   但**不等于**「三张共享一根 x 轴」（并排图的第 i 拍本来就是三个不同的页面 x）。
   本探针第一跑把这条判据套到了 `.lane3` 上，报出 664.7px「未对齐」—— 是判据错、不是页面错。
   ⇒ 组按 `mode` 分 `shared` / `side` 两种判法，见下面 VARIANTS。
   ══════════════════════════════════════════════════════════════════════════════ */
import path from 'node:path'
import fs from 'node:fs'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const DIR = path.join(ROOT, 'docs', '美化', 'c3-admin-preview')
const { open, sleep, report } = await import(pathToFileURL(path.join(ROOT, 'docs', '美化', '_cdp.mjs')).href)

/* ⚠️ S2 **不在**这份清单里：用户裁定「S1 优于 S2」后 S2 标了废弃、**不再更新**
   （裁定落在 `S2-黄金信号判据带.html` 自己的页头里，见 commit b1284a5）。
   它的 HTML 还在，但它引的同一份 `_struct-wire.js` 已经换成本窗真数据 ——
   于是它自己的脚注（「快照那刻只有 7 拍」）与它画出来的东西**互相矛盾**。
   那是**已知的不一致**，不是本探针要修的：把它放进 VARIANTS 只会得到一堆红断言，
   而那堆红断言说的不是「S1/S3 坏了」。要恢复 S2 的断言，先决定它的去留。 */
/* `filled` = 页面脚本里**由 JS 填值**的那些 id。它们初始都是占位符 `—`，脚本跑完才会变。
   ⚠️ 为什么要单列这一条：图数是靠 `.chart` **有尺寸**判的，而「图有了」不代表**脚本跑完了** ——
   脚本后半段一抛（比如某个 id 不存在），后面的填值全废，但图早就画好了 ⇒ 只查图数会全绿。
   本页脚本没有 try/catch，抛了就静默停在中途，所以这里逐个查「还留在占位符上的 id」。 */
const VARIANTS = [
  {
    file: 'S1-时间栈实体栏.html', png: 'S1-时间栈实体栏.png', charts: 5, hbar: 8, stock: 8, heatLabels: 2,
    groups: [{ sel: '.axis-group', mode: 'shared' }],
    filled: ['st-health', 'st-5xx', 'st-4xx', 'st-tz1', 'st-tz2', 'st-reasons',
      'n-qps', 'n-mean', 'n-wait', 'n-heat', 'w-metric', 'w-pitch']
  },
  {
    file: 'S3-三问句泳道.html', png: 'S3-三问句泳道.png', charts: 5, hbar: 8, stock: 8, heatLabels: 2,
    groups: [{ sel: '.lane3', mode: 'side' }],
    filled: ['st-health', 'st-5xx', 'st-4xx', 'st-tz1', 'st-tz2', 'st-reasons',
      'n-qps', 'n-mean', 'n-wait', 'w-mini', 'w-cell', 'w-mini2', 'w-cell2', 'w-pitch3']
  }
]

const VIEW_W = 1440
const VIEW_H = 900
const r = report('3a2b 结构草图')
console.log('=== 3a.2b 结构草图：整页截图 + 结构断言 ===')
console.log('跑法：node scripts/probes/probe-b7a2b-struct-shots.mjs')
console.log('底座：' + 'docs/美化/_cdp.mjs（headless Edge + CDP，--headless=new，' + VIEW_W + '×' + VIEW_H + '，file:// 直开，不起 server / 不起后端）')
console.log('版本：' + VARIANTS.map((v) => v.file).join(' / ') + '（S2 已按用户裁定废弃，不在断言清单内 —— 见脚本头注释）')
console.log('')

const b = await open({ w: VIEW_W, h: VIEW_H, headed: false })

for (const v of VARIANTS) {
  const html = path.join(DIR, v.file)
  if (!fs.existsSync(html)) { r.ok(v.file + ' 存在', false, 'NOT FOUND'); continue }
  await b.goto(pathToFileURL(html).href, 1200)

  const IDS = JSON.stringify(v.filled)
  const st = JSON.parse(await b.evalJS(`(function () {
    var IDS = ` + IDS + `
    var svgs = [].slice.call(document.querySelectorAll('.chart svg'))
    var px = document.querySelector('.px')
    var drawn = svgs.filter(function (s) {
      var c = s.getBoundingClientRect()
      return c.width > 10 && c.height > 10
    })
    /* 库存行的 SKU 折没折行。注意不是「有没有溢出」：折行时 scrollWidth 也等于
       clientWidth，照样全绿（这个坑上一版踩过，见台账）。 */
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
    /* ── x 轴几何：逐图量**画出来的**绘图区底边 ── */
    var f = WF.PAD_L / WF.VBW, g = (WF.VBW - WF.PAD_R) / WF.VBW
    var geo = []
    ;[].slice.call(document.querySelectorAll('.chart')).forEach(function (c, i) {
      var svg = c.querySelector('svg'), pe = c.querySelector('.plot-edge')
      if (!svg || !pe) { geo.push({ i: i, miss: !svg ? 'svg' : 'plot-edge' }); return }
      var sb = svg.getBoundingClientRect(), pb = pe.getBoundingClientRect()
      geo.push({
        i: i, svgW: Math.round(sb.width * 10) / 10,
        dL: Math.round((pb.left - (sb.left + sb.width * f)) * 10) / 10,
        dR: Math.round((pb.right - (sb.left + sb.width * g)) * 10) / 10,
        plotL: Math.round(pb.left * 10) / 10, plotR: Math.round(pb.right * 10) / 10,
        plotW: Math.round(pb.width * 10) / 10,
      })
    })
    /* ── 分组：只登记「哪几张图属于哪一组」，量出来的 geo 由 Node 侧按索引取 ── */
    var groups = []
    ;[].slice.call(document.querySelectorAll('.axis-group, .lane3')).forEach(function (g0) {
      var cs = [].slice.call(g0.querySelectorAll('.chart'))
      var all = [].slice.call(document.querySelectorAll('.chart'))
      groups.push({
        name: g0.className,
        n: cs.length,
        idx: cs.map(function (c) { return all.indexOf(c) }),
      })
    })
    return JSON.stringify({
      skWrap: skWrap, geo: geo, groups: groups,
      ph: IDS.filter(function (id) { var e = document.getElementById(id); return !e || e.textContent.trim() === '—' }),
      wf: typeof WF, xN: WF.DATA.x.length,
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
  r.ok(v.file + ' · 库存行数（本窗真数据）', st.stock === v.stock, st.stock)
  r.ok(v.file + ' · 热力行名数（本窗真锁名）', st.heatLabels === v.heatLabels, st.heatLabels)
  r.ok(v.file + ' · 库存 SKU 不折行', st.skWrap === 0, st.skWrap + ' 行折了')
  r.ok(v.file + ' · 页面脚本跑到底（' + v.filled.length + ' 个填值 id 都不在占位符上）',
    st.ph.length === 0, st.ph.length ? '仍为「—」：' + st.ph.join(', ') : v.filled.length + ' 个')

  /* ── x 轴对齐（提醒 1 要的「老断言」：以前**没有**这条，因为以前没测）── */
  const miss = st.geo.filter((x) => x.miss)
  r.ok(v.file + ' · 每张图都有绘图区底边可量', miss.length === 0, miss.length ? JSON.stringify(miss) : st.geo.length + ' 张')
  const meas = st.geo.filter((x) => !x.miss)
  const badModel = meas.filter((x) => Math.abs(x.dL) > 1.5 || Math.abs(x.dR) > 1.5)
  const worstL = meas.length ? Math.max(...meas.map((x) => Math.abs(x.dL))) : NaN
  const worstR = meas.length ? Math.max(...meas.map((x) => Math.abs(x.dR))) : NaN
  r.ok(v.file + ' · 绘图区左右沿 == SVG ± PAD/VBW（几何模型）', badModel.length === 0,
    badModel.length ? JSON.stringify(badModel) : '最大偏差 L ' + worstL + 'px / R ' + worstR + 'px')
  /* ⚠️ 「对齐」有两种，断言必须分开写 —— 混成一条就会把 S3 判红（本笔第一跑就踩到）：
     · shared（S1 的 .axis-group）：**上下叠**的图共享一根 x 轴 ⇒ 左右沿必须逐像素相同；
     · side  （S3 的 .lane3）    ：**左右并排**的图各有自己的轴，左右沿**本来就不该相同**
       （差 664.7px 是对的）。可量的是「等宽 + 等格距」⇒ 同一拍在三张里落在**各自的同一相对位置**。
     把 side 也按 shared 判，得到的是「三张并排图没有共享一根轴」——那不是缺陷，是把 S1 的判据
     套到了 S3 的形态上。 */
  for (const spec of v.groups) {
    const g0 = st.groups.find((g) => g.name.indexOf(spec.sel.slice(1)) >= 0)
    if (!g0) { r.ok(v.file + ' · 找到 ' + spec.sel + ' 组', false, 'NOT FOUND'); continue }
    const g = g0.idx.map((i) => st.geo[i])
    if (g.some((x) => !x || x.miss)) { r.ok(v.file + ' · ' + spec.sel + ' 组内图可量', false, JSON.stringify(g0.idx)); continue }
    if (spec.mode === 'shared') {
      const dL = Math.max(...g.map((x) => x.plotL)) - Math.min(...g.map((x) => x.plotL))
      const dR = Math.max(...g.map((x) => x.plotR)) - Math.min(...g.map((x) => x.plotR))
      r.ok(v.file + ' · ' + spec.sel + ' 组内 ' + g.length + ' 张叠图共享一根 x 轴（左右沿逐像素相同）',
        dL <= 1.5 && dR <= 1.5, '左沿差 ' + (Math.round(dL * 10) / 10) + 'px · 右沿差 ' + (Math.round(dR * 10) / 10) + 'px')
    } else {
      const dW = Math.max(...g.map((x) => x.plotW)) - Math.min(...g.map((x) => x.plotW))
      const dC = Math.max(...g.map((x) => x.svgW)) - Math.min(...g.map((x) => x.svgW))
      r.ok(v.file + ' · ' + spec.sel + ' 组内 ' + g.length + ' 张并排图等宽（容器 / 作图区都一样宽）',
        dW <= 1.5 && dC <= 1.5, '容器差 ' + (Math.round(dC * 10) / 10) + 'px · 作图区差 ' + (Math.round(dW * 10) / 10) + 'px')
      /* 等宽 ⇒ 同一拍 i 落在各自的同一相对位置；这一条是「共享游标」能对齐的**几何前提**。
         注意它**不等于**「三张共享一根 x 轴」—— 并排图的第 i 拍本来就是三个不同的页面 x。 */
      const pitches = g.map((x) => x.plotW / (st.xN - 1))
      const dP = Math.max(...pitches) - Math.min(...pitches)
      r.ok(v.file + ' · ' + spec.sel + ' 组内逐拍格距相同（同一拍 = 同一相对位置）', dP <= 0.1,
        '格距 ' + pitches.map((p) => p.toFixed(2)).join(' / ') + 'px')
    }
  }

  /* ⚠️ 校验的是**模型**不是常数：`.px` 宽 = clientW − 侧栏 232 − 主区左右 padding。
     硬写 `=== 1144` 会把「模型对不对」和「这一跑有没有滚动条」混成一条断言。 */
  const expectW = st.clientW - st.sideW - parseFloat(st.mainPadL) * 2
  r.ok(v.file + ' · 主区宽 == clientW − 232 − 2×pad', Math.abs(st.contentW - expectW) <= 1, st.contentW + ' vs ' + expectW)

  console.log(`     ${v.file} 页高 ${st.pageH}px · 主区宽 ${st.contentW}px（视口 ${st.innerW} / 客户区 ${st.clientW} / 滚动条 ${st.innerW - st.clientW}）`)
  st.geo.forEach((x) => console.log(`       图${x.i} 容器 ${x.svgW}px · 作图区 ${x.plotW}px`))

  const out = path.join(DIR, v.png)
  await b.shotFull(out)
  r.ok(v.png + ' 落盘', fs.existsSync(out) && fs.statSync(out).size > 5000,
    fs.existsSync(out) ? fs.statSync(out).size + 'B' : 'MISSING')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
