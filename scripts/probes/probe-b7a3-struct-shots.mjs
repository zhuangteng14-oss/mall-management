/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a3-struct-shots.mjs —— 3a.3（日志页结构重做）甲 / 丙两版的整页截图 + 结构断言

   跑法：node scripts/probes/probe-b7a3-struct-shots.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP）—— **不起 dev server、不起后端**：
   两个 HTML 除了两份 CSS / 一份 JS 之外是自包含的（真数据烘在 `_struct-wire-ev.js` 里）。

   ── 五条判据怎么量（每条都要能复核）──────────────────────────────────────
   ① C1 相邻区块不同构 —— 给每个**顶层区块**（渲染时打的 `[data-blk]`）算一枚**结构指纹**
      （标签 + display + 主轴方向 + **去重后的后代标签集**），再取「相邻对」（某一轴上间距 ≤ 40px
      且另一轴上有重叠），断言每对指纹都不同。
      ⚠️ 指纹里必须带**后代标签集**：只用「标签+display+子元素个数」时，⑤ 链路抽屉与 ⑥ 开关栏
      会双双算成 `div|block|-|3` 而被判同构 —— 那是**指纹太弱**，不是页面同构。
   ② C3 区块间距 > 区块内间距 —— **两个轴都量**（C3 的原话不区分横竖）：
      · 区间距 = 两块之间**真正的空白带**（`next.top − prev.bottom`，取重叠轴那一侧）；
      · 区内距 = 块内相邻两项的**空白**（盒间空隙 + 前一项的下内边距 + 后一项的上内边距）；
      断言 min(区间距) > max(区内距)。⚠️ 只按「盒子相接 ⇒ 间距 0」量区内距会**假绿**
      （列表卡片之间有 6+6 的内边距，看着是白的，盒子却挨着）。
   ③ 各区宽度实测 —— 量每块的 `getBoundingClientRect().width`，并与「主区可用宽」对账。
      主区宽**不写常数**：`clientWidth − 侧栏 232 − 主区左右 padding×2`（滚动条宽随 headless 模式变）。
   ④ 8 处动效落点 —— 数 `[data-anim]` 的角标，要求**恰好 8 个且编号 1–8 各一次**。
      ⚠️ 落点在 ≠ 有数据可演：5（error 闪红）本窗 error 0 条，页面上如实标了「无可演示的卡片」。
   ⑤ 三样易漏项 —— 按**文本**查在不在：⑤ 的「起止只来自 traces ⇒ 模拟日常下恒空」、
      ③ 的「只淘汰卡片、被丢的 seq 要从 seenSeq 摘掉」、图例的「本页窗口内累计、不是帧里 counts」。
      外加硬约束 5：「数据源（正常 / 模拟日常）」两版都在，且「已放 N / 500 条」**常驻**。
   ══════════════════════════════════════════════════════════════════════════════ */
import path from 'node:path'
import fs from 'node:fs'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const DIR = path.join(ROOT, 'docs', '美化', 'c3-admin-preview')
const { open, report } = await import(pathToFileURL(path.join(ROOT, 'docs', '美化', '_cdp.mjs')).href)

const VARIANTS = [
  { file: '3a3-甲-三栏驾驶舱.html', png: '3a3-甲-三栏驾驶舱.png', blks: ['status', 'flash', 'rail', 'stream', 'live', 'drawer', 'switch'] },
  { file: '3a3-丙-上下分区.html', png: '3a3-丙-上下分区.png', blks: ['status', 'flash', 'rail', 'band', 'stream', 'drawer', 'switch'] },
]

const VIEW_W = 1440
const VIEW_H = 900
const r = report('3a3 日志页结构草图（甲 / 丙）')
console.log('=== 3a.3 日志页结构草图：整页截图 + 结构断言 ===')
console.log('跑法：node scripts/probes/probe-b7a3-struct-shots.mjs')
console.log('底座：docs/美化/_cdp.mjs（headless Edge + CDP，--headless=new，' + VIEW_W + '×' + VIEW_H +
  '，file:// 直开，不起 server / 不起后端）')
console.log('版本：' + VARIANTS.map((v) => v.file).join(' / '))
console.log('')

const MEASURE = `(function () {
  function rect(e) { return e.getBoundingClientRect() }
  function vis(e) { var c = rect(e); return c.width > 0.5 && c.height > 0.5 }
  function tags(root) {
    var m = {}, all = [].slice.call(root.querySelectorAll('*'))
    for (var i = 0; i < all.length; i++) m[all[i].tagName.toLowerCase()] = 1
    return Object.keys(m).sort()
  }
  function sig(e) {
    var cs = getComputedStyle(e)
    var axis = cs.display === 'grid' ? cs.gridTemplateColumns
      : (cs.display.indexOf('flex') >= 0 ? cs.flexDirection : '-')
    /* 直接子元素的标签计数（如 div:14）—— 指纹里**必须**有这一项：
       只用「标签集」时，② 过滤栏（14 行 div）与 ③ 事件流（4 个 div 容器）会双双算成
       {div,span,b} 而被判同构 —— 那是**指纹太弱**，不是页面同构（本探针第一跑就这么红的）。 */
    var cnt = {}, kids = [].slice.call(e.children)
    for (var i = 0; i < kids.length; i++) {
      var t = kids[i].tagName.toLowerCase(); cnt[t] = (cnt[t] || 0) + 1
    }
    var childSig = Object.keys(cnt).sort().map(function (t) { return t + ':' + cnt[t] }).join(',')
    return e.tagName.toLowerCase() + '|' + cs.display + '|' + axis + '|' + childSig + '|' + tags(e).join(',')
  }
  var blocks = [].slice.call(document.querySelectorAll('[data-blk]')).filter(vis)
  /* ⚠️ 几何量存**原值**、只在打印时取整：先 round 再作差会把 32px 的间距算成 31px
     （本探针第一跑就报了 31 —— 那不是页面的问题，是我自己把子像素吃掉了）。 */
  var info = blocks.map(function (e) {
    var c = rect(e)
    return { blk: e.getAttribute('data-blk'), sig: sig(e), el: e,
      x: c.left, y: c.top, w: c.width, h: c.height }
  })
  var infoShow = info.map(function (x) {
    return { blk: x.blk, sig: x.sig, x: Math.round(x.x), y: Math.round(x.y),
      w: Math.round(x.w), h: Math.round(x.h) }
  })

  /* ── ① 区间距 / 相邻对 ─────────────────────────────────────────────── */
  var H_OVER = 1, V_OVER = 1     // 「另一轴上有重叠」的最小重叠像素
  var pairs = []
  for (var i = 0; i < info.length; i++) {
    for (var j = i + 1; j < info.length; j++) {
      var a = info[i], b = info[j]
      if (a.blk === b.blk) continue
      var ovX = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
      var ovY = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)
      var gapV = Math.max(a.y - (b.y + b.h), b.y - (a.y + a.h))
      var gapH = Math.max(a.x - (b.x + b.w), b.x - (a.x + a.w))
      var rec = null
      if (ovX > H_OVER && gapV >= 0) rec = { axis: 'v', gap: gapV, pair: a.blk + '|' + b.blk }
      else if (ovY > V_OVER && gapH >= 0) rec = { axis: 'h', gap: gapH, pair: a.blk + '|' + b.blk }
      if (!rec) continue
      rec.a = a.blk; rec.b = b.blk
      /* 相邻 = 间距 ≤ 48px（区块间距就是 48） */
      if (rec.gap <= 48 + 0.5) rec.adjacent = true
      pairs.push(rec)
    }
  }
  var adj = pairs.filter(function (p) { return p.adjacent })
  var interGaps = pairs.map(function (p) { return p.gap })

  /* ── ② 区内距（块内相邻两项的空白）──────────────────────────────────── */
  var intra = { v: 0, h: 0, vAt: '', hAt: '', list: [] }
  /* 留一份最大的几对，供报告复核「这个 22px 到底是哪两个东西之间」*/
  function pick(list, axis, gap, at) {
    list.push({ axis: axis, gap: Math.round(gap * 10) / 10, at: at })
    if (list.length > 40) {
      list.sort(function (x, y) { return y.gap - x.gap })
      list.length = 12
    }
  }
  blocks.forEach(function (root) {
    var all = [root].concat([].slice.call(root.querySelectorAll('*')))
    for (var i = 0; i < all.length; i++) {
      var p = all[i]
      var kids = [].slice.call(p.children).filter(vis)
      if (kids.length < 2) continue
      /* ⚠️ 「间距」不等于「两块之间的空白」：被 margin-left:auto 或 justify-content:space-*
         顶开的两个元素之间可以隔着几百像素，那是**对齐**不是**节奏**。
         本探针第一跑把状态条里 .ev-src（margin-left:auto）与 .tz 之间的 265.9px 当成了
         「区内间距」，于是 C3 假红 —— 判据错，不是页面错。这里逐对排除。 */
      /* ⚠️ 认「撑开的空白」不能查 computedStyle 的 marginLeft === 'auto'：flex 项的自动外边距
         在布局后会被解析成**已用值**（这里读回来是 265.9px，不是 'auto'）—— 本探针第一跑就是
         栽在这上面（判据看着写了、其实一行都没生效）。改成和**容器声明的 gap** 比：
         某一侧的 margin 大于 gap，那就是撑开的（对齐），不是节奏。
         gap 读不到（block 布局是 'normal' ⇒ NaN）时按 0 算，此时任何 0 边距都算「紧挨」。
         ⚠️⚠️ 还有一类**同源的假红**：内联级盒子（b / span 这类）之间的水平距离是**文本流动**
         （中间隔着正文），那不是作者声明的排布节奏。甲第二跑报的「横 210 @ note」就是 .note
         里两个 <b> 中间那 210px 的说明文字 —— 判据错，不是页面错。这里把内联级盒子整对跳过。 */
      var pc = getComputedStyle(p)
      var gapH = parseFloat(pc.columnGap) || 0
      var gapV = parseFloat(pc.rowGap) || 0
      for (var k = 1; k < kids.length; k++) {
        var A = kids[k - 1], B = kids[k]
        var ca = getComputedStyle(A), cb = getComputedStyle(B)
        if (ca.display.indexOf('inline') === 0 || cb.display.indexOf('inline') === 0) continue
        var ra = rect(A), rb = rect(B)
        var pd = parseFloat(ca.paddingBottom) || 0, pu = parseFloat(cb.paddingTop) || 0
        var pr = parseFloat(ca.paddingRight) || 0, pl = parseFloat(cb.paddingLeft) || 0
        var mB = parseFloat(ca.marginBottom) || 0, mT = parseFloat(cb.marginTop) || 0
        var mR = parseFloat(ca.marginRight) || 0, mL = parseFloat(cb.marginLeft) || 0
        var at = (p.getAttribute('class') || p.tagName.toLowerCase()) + ' (#' + k + ')'
        /* 竖向：B 在 A 下面 */
        if (rb.top >= ra.bottom - 0.5 && mB <= gapV + 0.5 && mT <= gapV + 0.5) {
          var gv = (rb.top - ra.bottom) + pd + pu
          pick(intra.list, 'v', gv, at)
          if (gv > intra.v) { intra.v = gv; intra.vAt = at }
        }
        /* 横向：B 在 A 右边 */
        if (rb.left >= ra.right - 0.5 && mR <= gapH + 0.5 && mL <= gapH + 0.5) {
          var gh = (rb.left - ra.right) + pr + pl
          pick(intra.list, 'h', gh, at)
          if (gh > intra.h) { intra.h = gh; intra.hAt = at }
        }
      }
    }
  })

  /* ── ④ 动效落点 ────────────────────────────────────────────────────── */
  /* 落点标注是逗号列表（4 与 7 本来就落在同一个数字上）⇒ 拆开、去重、排序 */
  var anims = []
  ;[].slice.call(document.querySelectorAll('[data-anim]')).forEach(function (e) {
    e.getAttribute('data-anim').split(',').forEach(function (a) {
      a = a.trim(); if (a && anims.indexOf(a) < 0) anims.push(a)
    })
  })
  anims.sort()

  var txt = document.body.innerText || ''
  var px = document.querySelector('.px')
  return JSON.stringify({
    d: typeof DATA, kinds: DATA && DATA.kinds.length, sevs: DATA && DATA.sevs.length,
    info: infoShow, pairs: pairs, adj: adj, interMin: Math.min.apply(null, interGaps),
    intra: intra, anims: anims,
    src: (document.querySelector('.ev-src') || {}).innerText || '',
    railRows: document.querySelectorAll('.ev-rail .row').length,
    cards: document.querySelectorAll('.ev-card').length,
    drawerNote: (document.querySelector('.ev-drawer .note') || {}).innerText || '',
    sentinel: [].slice.call(document.querySelectorAll('.ev-block .slot')).map(function (e) { return e.innerText }).join(' '),
    railFoot: (document.querySelector('.ev-rail .sub') || {}).innerText || '',
    /* ⚠️ 取**全部** .ttl，不是第一个：仪表带里第一格是「每秒事件速率」，
       第一跑只取了它，于是丙版「9 类计数（本页窗口内累计）」那句话根本没被查到。 */
    bandNote: [].slice.call(document.querySelectorAll('.band .ttl')).map(function (e) { return e.innerText }).join(' | '),
    streamH2: (document.querySelector('.ev-block .ev-h2') || {}).innerText || '',
    contentW: px ? Math.round(rect(px).width) : -1,
    clientW: document.documentElement.clientWidth,
    innerW: window.innerWidth,
    sideW: Math.round(rect(document.querySelector('.side')).width),
    padL: getComputedStyle(document.querySelector('.main')).paddingLeft,
    pageH: document.documentElement.scrollHeight,
    txtHasDemo: txt.indexOf('模拟日常') >= 0,
    txtHasCap500: txt.indexOf('500') >= 0,
  })
})()`

const b = await open({ w: VIEW_W, h: VIEW_H, headed: false })

for (const v of VARIANTS) {
  const html = path.join(DIR, v.file)
  if (!fs.existsSync(html)) { r.ok(v.file + ' 存在', false, 'NOT FOUND'); continue }
  await b.goto(pathToFileURL(html).href, 1200)
  const st = JSON.parse(await b.evalJS(MEASURE))

  r.ok(v.file + ' · DATA 已加载', st.d === 'object', st.d)
  r.ok(v.file + ' · 9 类 / 3 严重度', st.kinds === 9 && st.sevs === 3, st.kinds + ' 类 / ' + st.sevs + ' 档')

  /* 硬约束 5：数据源切换两版都在，且计数常驻 */
  r.ok(v.file + ' · 数据源切换在场（正常 / 模拟日常）', st.txtHasDemo && st.src.indexOf('正常') >= 0, st.src.replace(/\n/g, ' '))
  r.ok(v.file + ' · 「已放 N / 500 条」常驻', st.txtHasCap500 && st.src.indexOf('已放') >= 0,
    st.src.indexOf('已放') >= 0 ? st.src.replace(/\n/g, ' ') : '没找到「已放」')

  /* 区块数（渲染时打的 [data-blk]） */
  const got = st.info.map((x) => x.blk).sort().join(',')
  r.ok(v.file + ' · 区块数 = ' + v.blks.length, got === v.blks.slice().sort().join(','), got)

  /* ④ 8 处动效落点 */
  const want = '1,2,3,4,5,6,7,8'
  r.ok(v.file + ' · 动效落点恰好 8 处、编号 1–8 各一次', st.anims.join(',') === want, st.anims.join(','))

  /* ② C3 */
  const rd1 = (x) => Math.round(x * 10) / 10
  const intraMax = Math.max(st.intra.v, st.intra.h)
  r.ok(v.file + ' · C3 区间距 > 区内距（两轴）', st.interMin > intraMax,
    '区间距最小 ' + st.interMin + 'px vs 区内距最大 ' + rd1(intraMax) +
    'px（竖 ' + rd1(st.intra.v) + ' @ ' + st.intra.vAt + ' / 横 ' + rd1(st.intra.h) + ' @ ' + st.intra.hAt + '）')

  /* ① C1 相邻区块不同构 */
  const same = st.adj.filter((p) => {
    const A = st.info.find((x) => x.blk === p.a), B = st.info.find((x) => x.blk === p.b)
    return A.sig === B.sig
  })
  r.ok(v.file + ' · C1 相邻区块不同构（' + st.adj.length + ' 对相邻）', same.length === 0,
    same.length ? JSON.stringify(same.map((p) => p.pair)) : st.adj.map((p) => p.pair).join(' '))

  /* ③ 宽度 */
  const expectW = st.clientW - st.sideW - parseFloat(st.padL) * 2
  r.ok(v.file + ' · 主区宽 == clientW − 232 − 2×pad（模型，不写常数）',
    Math.abs(st.contentW - expectW) <= 1, st.contentW + ' vs ' + expectW)

  /* ⑤ 三样易漏项 + 图例口径 */
  r.ok(v.file + ' · 易漏项 1：⑤ 写明「起止只来自 traces ⇒ 模拟日常下恒空」',
    st.drawerNote.indexOf('traces') >= 0 && st.drawerNote.indexOf('模拟日常') >= 0,
    st.drawerNote.slice(0, 40) || '（空）')
  r.ok(v.file + ' · 易漏项 2：③ 写明「被淘汰的 seq 要从 seenSeq 摘掉」',
    st.sentinel.indexOf('seenSeq') >= 0, st.sentinel.slice(0, 40) || '（空）')
  r.ok(v.file + ' · 易漏项 3：图例写明「本页窗口内累计／不用帧里 counts」',
    (st.railFoot + st.streamH2 + st.bandNote).indexOf('窗口内累计') >= 0,
    st.railFoot || st.bandNote)

  console.log('    ' + v.file + '  页高 ' + st.pageH + 'px · 主区 ' + st.contentW +
    'px（视口 ' + st.innerW + ' / 客户区 ' + st.clientW + ' / 滚动条 ' + (st.innerW - st.clientW) + '）')
  console.log('      块宽：' + st.info.map((x) => x.blk + ' ' + x.w).join(' · '))
  console.log('      区间距：' + st.pairs.map((p) => p.axis + p.gap).join(' '))
  const topIn = st.intra.list.slice().sort((a, b) => b.gap - a.gap).slice(0, 5)
  console.log('      区内距前 5：' + topIn.map((x) => x.axis + rd1(x.gap) + '@' + x.at).join(' '))

  const out = path.join(DIR, v.png)
  await b.shotFull(out)
  r.ok(v.png + ' 落盘', fs.existsSync(out) && fs.statSync(out).size > 5000,
    fs.existsSync(out) ? fs.statSync(out).size + 'B' : 'MISSING')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
