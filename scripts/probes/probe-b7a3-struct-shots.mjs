/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a3-struct-shots.mjs —— 3a.3（日志页结构重做）甲 / 丙两版的整页截图 + 结构断言

   跑法：node scripts/probes/probe-b7a3-struct-shots.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP）—— **不起 dev server、不起后端**：
   两个 HTML 除了两份 CSS / 一份 JS 之外是自包含的（真数据烘在 `_struct-wire-ev.js` 里）。

   ── 判据怎么量 ──────────────────────────────────────────────────────────────
   〇 窗口化（上一轮的核心判据）
      · **六区全部落在首个视口内**（每区 rect 的 top ≥ 0 且 bottom ≤ innerHeight）
        —— 这就是「打开预览 → 不滚 → 能看完 5 区 + 第 6 区」的可复核形式。
      · **页面高度不随数据变**：往事件流里**临时塞 200 行**，前后比三个数 ——
        文档高**不变**、事件流的 clientHeight **不变**、scrollHeight **变大**。
        这三条同时成立才叫「事件流内部滚、页面不跟着长」；只量「有没有滚动条」是不够的
        （页面自己滚起来也会有滚动条）。
      · **主区（`.px`）不被撑高**：scrollHeight == clientHeight。
      · **整页就是一屏**：标题条 + 外壳 + 页脚 = innerHeight（页脚是折叠的 `<details>`）。
      · **一个大外框包住六区**：`.ev-frame` 的 DOM 子树含六区，且几何上包含六区。
   ⓪ ③ 事件流 = 控制台行（**这一轮的新判据**，用户 2026-10-16 逐条裁定）
      · **每条一行**：所有行的 offsetHeight **只有一个取值**（没有两行的卡）。
      · **无复选框 / 无左侧竖线 / 无右侧独立列**：行内没有 `input`；行内任何格子都不得有
        `border-left` 或宽度 < 6px 的色块；**traceId 併在消息行尾** —— `.tr` 必须是 `.ms`
        的后代、且右边界不超出 `.ms` 的右边界。
      · **三列定宽**：`grid-template-columns` 前五列是像素值（前导 / 时刻 / 图标 / 等级 / 类），
        第六列是 `minmax(0, 1fr)`（消息吃剩下的宽）。
      · **同 trace 用前导符归组**：前导符取值只能是 `├─` / `│  ├─` / `│  └─` / `└─`；
        组内行（以 `│` 开头）上面必须能找到同号、前导符不带 `│` 的组首行。
      · **点某行 ⇒ 同 trace 整组微染**：用**真 click 事件**点一行，被染的行数必须等于
        该号在 DOM 里的行数（且 ≥ 2）；只许改背景 —— 不许出现 border / 背景图；
        点完 **页高与事件流可视高都不许变**（否则点一下页面就跳）。
      · **滚动条 6–8px**：`list.offsetWidth − list.clientWidth` 实测（不写常数）。
      · **上移流畅**：追加 3 行，按 `requestAnimationFrame` 采 `scrollTop` ——
        去重后的取值序列必须**单调不减**、**至少 6 个不同取值**（不是一跳）、**末值与最大
        滚动位置相等**（停在底部）、动画实测时长落在 200–300ms。
      · **每秒 5+ 条不抖不卡**：100 行分 20 批喂进去（= 每秒 100 条）—— 同步那段必须够快、
        队列必须排空、DOM 行数仍被封顶（不多不少）、**动画次数远少于批数**（合并生效）。
      · **手动滚打断 / 回到底部恢复**：`scrollTop = 0` 后自动跟随必须关、贴回底部必须开。
   ① C1 相邻区块不同构 —— 每个顶层区块算一枚**结构指纹**（标签 + display + 主轴方向 +
      **直接子元素标签计数** + 后代标签集），相邻对（某一轴相接或间距 ≤ 4px、另一轴有重叠）
      指纹必须两两不同。
      ⚠️ 窗口化之后区块**直接相接**（间距 0，只隔一条分隔线）⇒ 相邻对变多，这条比上一轮更严。
   ② 8 处动效落点 —— 数 `[data-anim]` 的角标，要求**恰好 8 个且编号 1–8 各一次**。
      落点在 ≠ 有数据可演：5（error 闪红）本窗 error 0 条，页面上如实标了。
   ③ 各区宽度实测 —— 与「主区可用宽」对账。主区宽**不写常数**：
      `clientWidth − 侧栏 232 − 主区左右 padding×2`（滚动条宽随 headless 模式变）。
   ④ 三样易漏项 —— 按**文本**查在不在：⑤ 的「起止只来自 traces ⇒ 模拟日常下恒空」、
      ③ 的「被淘汰的 seq 要从 seenSeq 摘掉」、图例的「本页窗口内累计、不是帧里 counts」。
      外加硬约束 5：「数据源（正常 / 模拟日常）」两版都在，且「已放 N / 500 条」**常驻**。

   ⚠️ **上一轮跑过的 C3（区块间距 > 区块内间距）已撤**：窗口化之后区与区之间是
    **一条 1px 分隔线**、不是空白带，「间距」这个量在窗口形态里没有意义 —— 分组由**外框**
    表达，不由留白表达。撤掉而不是改数，理由写在交付报告里。
   ⚠️ ③ 那几条是**行为**判据（要跑起来才量得到），所以 MEASURE 是个 async IIFE、
      靠 CDP 的 `awaitPromise` 取值 —— 静态图上「流不流畅」是看不出来的。
   ══════════════════════════════════════════════════════════════════════════════ */
import path from 'node:path'
import fs from 'node:fs'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const DIR = path.join(ROOT, 'docs', '美化', 'c3-admin-preview')
const { open, report } = await import(pathToFileURL(path.join(ROOT, 'docs', '美化', '_cdp.mjs')).href)

/* 六区（对照 txt 线框图的 ①…⑥）—— 甲 / 丙 的第 ④ 区宿主不同：甲是右栏、丙是仪表带。 */
const REG_JIA = [['status', '①'], ['rail', '②'], ['stream', '③'], ['live', '④'], ['drawer', '⑤'], ['switch', '⑥']]
const REG_BING = [['status', '①'], ['rail', '②'], ['stream', '③'], ['band', '④'], ['drawer', '⑤'], ['switch', '⑥']]
const VARIANTS = [
  { file: '3a3-甲-三栏驾驶舱.html', png: '3a3-甲-三栏驾驶舱.png', regions: REG_JIA },
  { file: '3a3-丙-上下分区.html', png: '3a3-丙-上下分区.png', regions: REG_BING },
]
const ALL_BLKS = ['status', 'flash', 'rail', 'stream', 'live', 'band', 'drawer', 'switch']

/* 声明值从**源文件**读，不从页面的 CSSOM 读 —— 实测 `document.styleSheets[i].cssRules`
   在 `file://` 下**抛 SecurityError**（样式表被视为不透明来源），try/catch 一兜就变成空串，
   于是「声明里是不是 minmax(0, 1fr)」这条判据会**永远假红**。源文件才是可取证的那个副本。 */
const CSS_SRC = fs.readFileSync(path.join(DIR, '_struct-wire-ev.css'), 'utf8')
const GRID_BLOCK = (CSS_SRC.match(/\.log-head,\s*\.log-line\s*\{([^}]*)\}/) || [])[1] || ''
const GRID_DECL = (GRID_BLOCK.match(/grid-template-columns:\s*([^;]+);/) || [])[1] || ''

const VIEW_W = 1440
const VIEW_H = 900
const INJECT_N = 200       /* 临时塞进事件流的行数（证伪「事件越多页面越长」） */
const BURST_BATCH = 20     /* 每批 5 行 × 20 批 = 100 行 = 每秒 100 条 */
const BURST_ROW = 5
const BAR_LO = 6, BAR_HI = 8     /* 用户要的滚动条宽（实测必须落在这里面） */
const DUR_LO = 200, DUR_HI = 300 /* 用户要的平滑上移时长 */

const r = report('3a3 日志页结构草图（甲 / 丙 · 控制台行）')
console.log('=== 3a.3 日志页结构草图：控制台行 + 行为 + 整页截图 + 结构断言 ===')
console.log('跑法：node scripts/probes/probe-b7a3-struct-shots.mjs')
console.log('底座：docs/美化/_cdp.mjs（headless Edge + CDP，--headless=new，' + VIEW_W + '×' + VIEW_H +
  '，file:// 直开，不起 server / 不起后端）')
console.log('版本：' + VARIANTS.map((v) => v.file).join(' / '))
console.log('')

/* ⚠️ 模板字符串里**不许**出现反引号或 ${（会把模板提前闭合，报错还指向注释行）——
   全部用字符串拼接；页内要用的 `│` 写成 '\\u2502'（外层模板先吃一层反斜杠）。 */
const MEASURE = `(async function () {
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
       只用「标签集」时两个不同区块会双双算成同一枚指纹而被判同构 —— 那是**指纹太弱**。 */
    var cnt = {}, kids = [].slice.call(e.children)
    for (var i = 0; i < kids.length; i++) {
      var t = kids[i].tagName.toLowerCase(); cnt[t] = (cnt[t] || 0) + 1
    }
    var childSig = Object.keys(cnt).sort().map(function (t) { return t + ':' + cnt[t] }).join(',')
    return e.tagName.toLowerCase() + '|' + cs.display + '|' + axis + '|' + childSig + '|' + tags(e).join(',')
  }
  function frame2() { return new Promise(function (res) { requestAnimationFrame(function () { requestAnimationFrame(res) }) }) }

  var frame = document.querySelector('.ev-frame')
  var fr = frame ? rect(frame) : null
  var blocks = [].slice.call(document.querySelectorAll('[data-blk]')).filter(vis)
  /* 几何量存**原值**、只在打印时取整：先 round 再作差会把 1px 的差吃掉。 */
  var info = blocks.map(function (e) {
    var c = rect(e)
    var inFrame = !!(frame && frame.contains(e) &&
      c.left >= fr.left - 0.5 && c.top >= fr.top - 0.5 &&
      c.right <= fr.right + 0.5 && c.bottom <= fr.bottom + 0.5)
    return { blk: e.getAttribute('data-blk'), sig: sig(e), txt: (e.innerText || '').replace(/\\s+/g, ' ').slice(0, 60),
      x: c.left, y: c.top, w: c.width, h: c.height, right: c.right, bottom: c.bottom, inFrame: inFrame }
  })
  var infoShow = info.map(function (x) {
    return { blk: x.blk, sig: x.sig, txt: x.txt, x: Math.round(x.x), y: Math.round(x.y),
      w: Math.round(x.w), h: Math.round(x.h), bottom: Math.round(x.bottom), inFrame: x.inFrame }
  })

  /* ── 相邻对（窗口化之后：相接 = 间距 0，只隔一条分隔线）────────────── */
  var ADJ = 4
  var pairs = []
  for (var i = 0; i < info.length; i++) {
    for (var j = i + 1; j < info.length; j++) {
      var a = info[i], b = info[j]
      if (a.blk === b.blk) continue
      var ovX = Math.min(a.right, b.right) - Math.max(a.x, b.x)
      var ovY = Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y)
      var gapV = Math.max(a.y - b.bottom, b.y - a.bottom)
      var gapH = Math.max(a.x - b.right, b.x - a.right)
      var rec = null
      if (ovX > 1 && gapV >= 0) rec = { axis: 'v', gap: gapV, pair: a.blk + '|' + b.blk }
      else if (ovY > 1 && gapH >= 0) rec = { axis: 'h', gap: gapH, pair: a.blk + '|' + b.blk }
      if (!rec) continue
      rec.a = a.blk; rec.b = b.blk
      if (rec.gap <= ADJ) rec.adjacent = true
      pairs.push(rec)
    }
  }
  var adj = pairs.filter(function (p) { return p.adjacent })

  /* ── 动效落点（逗号列表拆开去重）──────────────────────────────────── */
  var anims = []
  ;[].slice.call(document.querySelectorAll('[data-anim]')).forEach(function (e) {
    e.getAttribute('data-anim').split(',').forEach(function (a) {
      a = a.trim(); if (a && anims.indexOf(a) < 0) anims.push(a)
    })
  })
  anims.sort()

  /* ── 〇 注入实验：事件流里**再塞 200 行**，看页高动不动 ────────────────
     这是「事件越多页面越长」这条病的**直接证伪**：文档高与列表可视高必须**一模一样**，
     而列表的 scrollHeight 必须**变大**（说明它自己扛住了）。量完立刻清理。 */
  var list = document.getElementById('ev-list')
  var px = document.querySelector('.px')
  function snap() {
    return { page: document.documentElement.scrollHeight, docH: document.body.scrollHeight,
      listC: list.clientHeight, listS: list.scrollHeight }
  }
  var before = snap()
  var proto = list.firstElementChild
  for (var q = 0; q < ` + INJECT_N + `; q++) {
    var c2 = proto.cloneNode(true)
    c2.removeAttribute('data-anim')       /* 别把落点角标也复制一份，否则「恰好 8 处」当场失真 */
    var inner = c2.querySelectorAll('[data-anim]')
    for (var z = 0; z < inner.length; z++) inner[z].removeAttribute('data-anim')
    list.appendChild(c2)
  }
  var after = snap()
  for (var q2 = 0; q2 < ` + INJECT_N + `; q2++) list.removeChild(list.lastElementChild)
  var afterClean = snap()

  /* ── ⓪-1 行的形态：一行高 / 前导符 / 列 / 图标 / 等级 / trace 尾 ────── */
  var rows = [].slice.call(list.children)
  var hs = {}, pres = {}, lv = {}, trTail = 0
  rows.forEach(function (r2) {
    var h = r2.offsetHeight; hs[h] = (hs[h] || 0) + 1
    pres[r2.querySelector('.pre').textContent] = (pres[r2.querySelector('.pre').textContent] || 0) + 1
    var t = r2.querySelector('.lv').textContent
    lv[t || '(空·req行)'] = (lv[t || '(空·req行)'] || 0) + 1
    var ms = r2.querySelector('.ms'), tr = r2.querySelector('.tr')
    if (ms && tr && ms.contains(tr) && tr.getBoundingClientRect().right <= ms.getBoundingClientRect().right + 0.5) trTail++
  })
  var presKeys = Object.keys(pres).sort()
  /* 组内行（以 │ 开头）**上面必须能找到同号的组首行** —— 树不是画上去的，是真结构 */
  var orphan = 0
  for (var i2 = 0; i2 < rows.length; i2++) {
    var p = rows[i2].querySelector('.pre').textContent
    if (p.indexOf('\\u2502') !== 0) continue
    var tr2 = rows[i2].getAttribute('data-trace'), found = false
    for (var k2 = i2 - 1; k2 >= 0; k2--) {
      if (rows[k2].getAttribute('data-trace') !== tr2) break
      if (rows[k2].querySelector('.pre').textContent.indexOf('\\u2502') !== 0) { found = true; break }
    }
    if (!found) orphan++
  }
  /* 行内不许有 input；不许有 border-left、也不许有窄色块（= 老版那条左竖线） */
  var inputs = list.querySelectorAll('input').length
  var vb = 0, inn = list.querySelectorAll('.log-line *')
  for (var k3 = 0; k3 < inn.length; k3++) {
    var cs2 = getComputedStyle(inn[k3])
    if (parseFloat(cs2.borderLeftWidth) > 0) vb++
    else if (cs2.backgroundColor !== 'rgba(0, 0, 0, 0)' && rect(inn[k3]).width < 6) vb++
  }
  var cols = rows.length ? getComputedStyle(rows[0]).gridTemplateColumns : ''
  var colArr = cols.split(' ').map(function (s) { return s })
  /* ⚠️ 计算值把 minmax(0, 1fr) **解析成了 px**（甲 353px / 丙 653px）⇒ 光看计算值
     判不出「第六列是可伸缩的」。所以另外从 CSSOM 取**声明值**（.log-line 那条规则里的
     原文），两样一起看：声明里必须是 minmax(0, 1fr)，解析出来必须等于「可视宽 − 定宽 − 缝 − 内衬」。 */
  var decl = '', cs0 = rows.length ? getComputedStyle(rows[0]) : null
  try {
    ;[].slice.call(document.styleSheets).forEach(function (ss) {
      var rs; try { rs = ss.cssRules } catch (e) { return }
      ;[].slice.call(rs).forEach(function (rl) {
        /* ⚠️ 那条规则的选择器是 .log-head, .log-line（两个共用一套格子）⇒
           必须用 indexOf、不能拿 '.log-line' 做相等比较（相等比较一条也匹配不上 ⇒ 读到空串） */
        if (rl.selectorText && rl.selectorText.indexOf('.log-line') >= 0 &&
            rl.style && rl.style.gridTemplateColumns) decl = rl.style.gridTemplateColumns
      })
    })
  } catch (e) {}
  var fixed5 = 0
  for (var f5 = 0; f5 < 5 && f5 < colArr.length; f5++) fixed5 += parseFloat(colArr[f5])
  var gapPh = cs0 ? parseFloat(cs0.columnGap) : 0
  var padLR = cs0 ? parseFloat(cs0.paddingLeft) + parseFloat(cs0.paddingRight) : 0
  var colModel = Math.round(list.clientWidth - fixed5 - gapPh * 5 - padLR)

  /* ── ⓪-2 点某行 ⇒ 同 trace 整组微染（走**真 click 事件**）───────────── */
  var cH0 = list.clientHeight, pageH0 = document.documentElement.scrollHeight
  var g = null
  for (var i3 = 0; i3 < rows.length; i3++) {
    var t3 = rows[i3].getAttribute('data-trace'), same = 0
    for (var j3 = 0; j3 < rows.length; j3++) if (rows[j3].getAttribute('data-trace') === t3) same++
    if (same >= 3 && rows[i3].querySelector('.pre').textContent.indexOf('\\u2502') === 0) { g = rows[i3]; break }
  }
  var sel = null
  if (g) {
    window.__evStream.select(-99)                  /* 先清干净（-99 谁也匹配不上） */
    g.click()
    var trSel = g.getAttribute('data-trace')
    var cg = getComputedStyle(g)
    sel = { tr: trSel, hits: list.querySelectorAll('.sel').length,
      same: list.querySelectorAll('[data-trace="' + trSel + '"]').length,
      bg: cg.backgroundColor, bL: cg.borderLeftWidth, bImg: cg.backgroundImage, color: cg.color,
      cH: list.clientHeight, pageH: document.documentElement.scrollHeight,
      hdH: Math.round(document.querySelector('#ev-drawer .hd').getBoundingClientRect().height),
      drawerTr: document.getElementById('ev-drawer').getAttribute('data-seltr') }
  }

  /* ── ⓪-3 上移流畅：追加 3 行，按 rAF 采 scrollTop ─────────────────── */
  window.__evStream.select(-99)
  var t0 = performance.now(), samples = []
  window.__evStream.append(3)
  await new Promise(function (res) {
    function tick() {
      samples.push([Math.round(performance.now() - t0), Math.round(list.scrollTop)])
      if (performance.now() - t0 < 420) requestAnimationFrame(tick); else res()
    }
    requestAnimationFrame(tick)
  })
  var stS = window.__evStream.state()
  var vals = []
  samples.forEach(function (s) { if (!vals.length || vals[vals.length - 1] !== s[1]) vals.push(s[1]) })
  var mono = vals.every(function (v, i4) { return i4 === 0 || v >= vals[i4 - 1] })
  var lastRow = list.lastElementChild
  var smooth = { vals: vals, mono: mono, n: vals.length, first: vals[0], last: vals[vals.length - 1],
    dur: stS.lastMs, maxTop: stS.maxTop, top: stS.scrollTop, drawn: stS.drawn, cap: stS.cap,
    tailGap: lastRow ? Math.round(rect(list).bottom - rect(lastRow).bottom) : -999 }

  /* ── ⓪-4 手动滚打断 / 回到底部恢复 ───────────────────────────────── */
  list.scrollTop = 0
  await frame2()
  var autoAfterTop = window.__evStream.state().auto
  list.scrollTop = list.scrollHeight
  await frame2()
  var autoAfterBottom = window.__evStream.state().auto

  /* ── ⓪-5 每秒 5+ 条：100 行分 20 批喂（= 每秒 100 条）─────────────── */
  var s0 = window.__evStream.state()
  var tA = performance.now()
  for (var q3 = 0; q3 < ` + BURST_BATCH + `; q3++) window.__evStream.append(` + BURST_ROW + `)
  var syncMs = Math.round(performance.now() - tA)
  await new Promise(function (res) { setTimeout(res, 1400) })
  var s1 = window.__evStream.state()
  var burst = { fed: s1.fed - s0.fed, starts: s1.starts - s0.starts, anims: s1.anims - s0.anims,
    syncMs: syncMs, drawn: s1.drawn, cap: s1.cap, queued: s1.queued, bar: s1.bar }

  /* ── ⓪-6 ▶重放：走一遍真帧的拍序 ──────────────────────────────────── */
  window.__evStream.replay()
  /* ⚠️ 不能「等 1.5 秒就读数」：一拍 100+ 行要分 9 批喂、每批 60ms 合并 + 250ms 动画，
     读到的时候动画往往还在飞（实测停在离底 42px 处 = 假失败）。等到「队列空 + 没动画」再读。 */
  var waited = 0, sW = null, polls = 0
  while (waited < 60) {
    sW = window.__evStream.state()
    if (sW.drawn > 0 && sW.queued === 0 && !sW.animating) break
    await new Promise(function (res) { setTimeout(res, 100) })
    waited++
  }
  var rep = sW
  var replay = { tick: rep.tick, drawn: rep.drawn, top: rep.scrollTop, max: rep.maxTop, fed: rep.fed,
    head: DATA.replayHead, cutAt: DATA.cuts[rep.tick], waitMs: waited * 100,
    animating: rep.animating, queued: rep.queued }
  window.__evStream.reset()                      /* 复位成「末 200 行」——截图要的是那一屏 */

  var txt = document.body.innerText || ''
  var notes = [].slice.call(document.querySelectorAll('.ev-h2 .n, .log-note, .ev-rail .sub, .ev-drawer .note, .ev-cond, .band .ttl, .ev-switch .n'))
    .map(function (e) { return e.innerText }).join(' | ')
  var head = document.querySelector('.wire-head'), shell = document.querySelector('.shell'), foot = document.querySelector('.wire-foot')
  return JSON.stringify({
    d: typeof DATA, kinds: DATA && DATA.kinds.length, sevs: DATA && DATA.sevs.length,
    info: infoShow, pairs: pairs, adj: adj, anims: anims,
    frame: fr ? { x: Math.round(fr.left), y: Math.round(fr.top), w: Math.round(fr.width), h: Math.round(fr.height),
      right: Math.round(fr.right), bottom: Math.round(fr.bottom),
      bw: getComputedStyle(frame).borderTopWidth } : null,
    inject: { before: before, after: after, clean: afterClean, n: ` + INJECT_N + `,
      pageSame: before.page === after.page, listSame: before.listC === after.listC,
      grew: after.listS > before.listS + ` + INJECT_N + ` },
    listC: list.clientHeight, listS: list.scrollHeight,
    pxC: px.clientHeight, pxS: px.scrollHeight,
    src: (document.querySelector('.ev-src') || {}).innerText || '',
    notes: notes,
    headH: head ? Math.round(rect(head).height) : -1,
    shellH: shell ? Math.round(rect(shell).height) : -1,
    footH: foot ? Math.round(rect(foot).height) : -1,
    lines: rows.length, heights: hs, pres: pres, presKeys: presKeys, lv: lv, trTail: trTail,
    orphan: orphan, inputs: inputs, vb: vb, cols: cols, colArr: colArr,
    decl: decl, colModel: colModel, colGap: gapPh, colPad: padLR,
    sel: sel, smooth: smooth, autoAfterTop: autoAfterTop, autoAfterBottom: autoAfterBottom,
    burst: burst, replay: replay, bar: window.__evStream.state().bar,
    contentW: px ? Math.round(rect(px).width) : -1,
    clientW: document.documentElement.clientWidth,
    innerW: window.innerWidth,
    innerH: window.innerHeight,
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

  /* ══ 〇 窗口化（上一轮的核心判据）════════════════════════════════════════ */
  const by = {}
  st.info.forEach((x) => { by[x.blk] = x })
  const missing = v.regions.filter(([k]) => !by[k]).map(([k]) => k)
  r.ok(v.file + ' · 六区齐全（' + v.regions.map(([, n]) => n).join('') + '）', missing.length === 0,
    missing.length ? '缺 ' + missing.join(',') : Object.keys(by).join(','))

  const offScreen = v.regions.filter(([k]) => by[k] && (by[k].y < -0.5 || by[k].bottom > st.innerH + 0.5))
    .map(([k, n]) => n + ' ' + by[k].y + '→' + by[k].bottom + '（视口 ' + st.innerH + '）')
  r.ok(v.file + ' · 〇 六区全在首屏内（不用滚动）', offScreen.length === 0, offScreen.length ? offScreen.join(' · ') : '全部 ≤ ' + st.innerH + 'px')

  const noIdx = v.regions.filter(([k, n]) => by[k] && by[k].txt.indexOf(n) < 0).map(([k, n]) => n + '@' + k)
  r.ok(v.file + ' · 〇 六区各自带区号标签（可用眼睛数）', noIdx.length === 0, noIdx.length ? noIdx.join(' ') : '①…⑥ 都在')

  r.ok(v.file + ' · 〇 一个大外框包住六区（DOM 子树 + 几何包含）',
    !!st.frame && st.frame.bw === '1px' && v.regions.every(([k]) => by[k] && by[k].inFrame),
    st.frame ? ('框 ' + st.frame.w + '×' + st.frame.h + ' · 边线 ' + st.frame.bw + ' · 六区 inFrame=' +
      v.regions.filter(([k]) => by[k] && by[k].inFrame).length + '/6') : 'NO FRAME')

  r.ok(v.file + ' · 〇 整页就是一屏（标题 + 外壳 + 页脚 = 视口高）',
    Math.abs(st.headH + st.shellH + st.footH - st.innerH) <= 1,
    st.headH + ' + ' + st.shellH + ' + ' + st.footH + ' vs ' + st.innerH)
  r.ok(v.file + ' · 〇 文档高 == 视口高（整页不滚）', Math.abs(st.pageH - st.innerH) <= 1,
    st.pageH + ' vs ' + st.innerH + '（页面级滚动条 ' + (st.innerW - st.clientW) + 'px）')
  r.ok(v.file + ' · 〇 主区不被撑高（.px scrollHeight == clientHeight）', st.pxS <= st.pxC + 1,
    st.pxS + ' vs ' + st.pxC)

  r.ok(v.file + ' · 〇 事件流有内部滚动条（内容超出可视区）', st.listS > st.listC + 1,
    'scrollHeight ' + st.listS + ' > clientHeight ' + st.listC)
  r.ok(v.file + ' · 〇 塞 ' + st.inject.n + ' 行：页高不变、列表可视高不变、内部滚变长',
    st.inject.pageSame && st.inject.listSame && st.inject.grew,
    '页高 ' + st.inject.before.page + '→' + st.inject.after.page + '（' + (st.inject.pageSame ? '不变' : '**变了**') + '）· ' +
    '列表可视 ' + st.inject.before.listC + '→' + st.inject.after.listC + '（' + (st.inject.listSame ? '不变' : '**变了**') + '）· ' +
    '列表内部 ' + st.inject.before.listS + '→' + st.inject.after.listS + '（' + (st.inject.grew ? '变长' : '**没变长**') + '）· ' +
    '清理后回到 ' + st.inject.clean.page + '/' + st.inject.clean.listS)

  /* ══ ⓪ ③ 事件流 = 控制台行（这一轮的新判据）═════════════════════════════ */
  const hKeys = Object.keys(st.heights)
  r.ok(v.file + ' · ⓪ 每条一行（全表只有一个行高）', hKeys.length === 1 && Number(hKeys[0]) > 0,
    '行高 ' + hKeys.map((h) => h + 'px×' + st.heights[h]).join(' / ') + ' · 共 ' + st.lines + ' 行')
  r.ok(v.file + ' · ⓪ 行数 == DOM 封顶 ' + st.smooth.cap, st.lines === st.smooth.cap, st.lines + ' 行')

  r.ok(v.file + ' · ⓪ 无复选框 / 无左竖线（行内无 input、无 border-left、无窄色块）',
    st.inputs === 0 && st.vb === 0, 'input ' + st.inputs + ' · 竖线/窄色块 ' + st.vb)
  r.ok(v.file + ' · ⓪ 无右侧独立列（traceId 併进消息行尾）',
    st.trTail === st.lines && st.colArr.length === 6,
    '带 trace 尾且在 .ms 内的行 ' + st.trTail + '/' + st.lines + ' · 列 ' + st.cols)
  r.ok(v.file + ' · ⓪ 五列定宽 + 消息列吃掉剩下的宽（声明 = minmax(0, 1fr)、解析值对得上模型）',
    st.colArr.length === 6 && st.colArr.slice(0, 5).every((c) => /^[0-9.]+px$/.test(c)) &&
    GRID_DECL.indexOf('minmax(0, 1fr)') >= 0 &&
    Math.abs(parseFloat(st.colArr[5]) - st.colModel) <= 1,
    'CSS 声明「' + GRID_DECL.trim() + '」· 页面解析 ' + st.cols + ' · 消息列 ' + st.colArr[5] +
    ' vs 模型 ' + st.colModel + 'px（按 ' + st.colArr.slice(0, 5).join('+') + ' − 缝 ' + st.colGap +
    '×5 − 内衬 ' + st.colPad + '）')

  const P4 = ['├─', '└─', '│  ├─', '│  └─']
  r.ok(v.file + ' · ⓪ 前导符只用四种树形（├─ │ ├─ │ └─ └─）',
    st.presKeys.every((k) => P4.indexOf(k) >= 0) && st.presKeys.length >= 2,
    st.presKeys.map((k) => JSON.stringify(k) + '×' + st.pres[k]).join(' · ') +
    (st.presKeys.some((k) => k.indexOf('│') === 0) ? ' ⇒ 有真组' : ' ⇒ 本窗没有组内行'))
  r.ok(v.file + ' · ⓪ 组内行上面找得到同号的组首（不是画上去的树）', st.orphan === 0,
    '找不到组首的组内行 ' + st.orphan + ' 条')

  r.ok(v.file + ' · ⓪ 点某行 ⇒ 同 trace 整组微染（且只改背景）',
    !!st.sel && st.sel.hits === st.sel.same && st.sel.hits >= 3 &&
    st.sel.bL === '0px' && st.sel.bImg === 'none' && st.sel.drawerTr === st.sel.tr,
    st.sel ? ('同号 ' + st.sel.tr + '：染 ' + st.sel.hits + '/' + st.sel.same + ' 行 · 底 ' + st.sel.bg +
      ' · border-left ' + st.sel.bL + ' · 背景图 ' + st.sel.bImg + ' · ⑤ 跟着切到 ' + st.sel.drawerTr) : '没找到可点的组内行')
  r.ok(v.file + ' · ⓪ 点完页高与事件流可视高都不变',
    !!st.sel && st.sel.pageH === st.pageH && st.sel.cH === st.listC,
    st.sel ? ('页高 ' + st.pageH + '→' + st.sel.pageH + ' · 事件流可视 ' + st.listC + '→' + st.sel.cH +
      ' · ⑤ 头一行 ' + st.sel.hdH + 'px') : '-')

  r.ok(v.file + ' · ⓪ 滚动条实测 ' + BAR_LO + '–' + BAR_HI + 'px（不写常数）',
    st.bar >= BAR_LO && st.bar <= BAR_HI, st.bar + 'px')

  r.ok(v.file + ' · ⓪ 上移流畅：单调不减、' + st.smooth.n + ' 个不同取值、停在底部、' +
    DUR_LO + '–' + DUR_HI + 'ms',
    st.smooth.mono && st.smooth.n >= 6 && st.smooth.last === st.smooth.maxTop &&
    st.smooth.dur >= DUR_LO && st.smooth.dur <= DUR_HI,
    st.smooth.first + '→' + st.smooth.last + '（maxTop ' + st.smooth.maxTop + '）· ' + st.smooth.n +
    ' 个取值 · 实测 ' + st.smooth.dur + 'ms · ' + st.smooth.vals.slice(0, 8).join(' '))
  r.ok(v.file + ' · ⓪ 追加后停在尾部（末行贴住列表底边）', st.smooth.tailGap >= -1 && st.smooth.tailGap <= 1,
    '末行底距列表底 ' + st.smooth.tailGap + 'px')

  r.ok(v.file + ' · ⓪ 手动滚 → 打断自动跟随', st.autoAfterTop === false, 'auto=' + st.autoAfterTop)
  r.ok(v.file + ' · ⓪ 滚回底部 → 自动恢复', st.autoAfterBottom === true, 'auto=' + st.autoAfterBottom)

  r.ok(v.file + ' · ⓪ 每秒 100 条（' + BURST_BATCH + '×' + BURST_ROW + '）：同步段够快、队列排空、行数仍封顶、动画被合并',
    st.burst.fed === BURST_BATCH * BURST_ROW && st.burst.syncMs < 100 && st.burst.queued === 0 &&
    st.burst.drawn === st.burst.cap && st.burst.starts < BURST_BATCH &&
    st.burst.starts <= Math.ceil(BURST_BATCH * BURST_ROW / st.smooth.cap * 14) + 4,
    '喂 ' + st.burst.fed + ' 行 · 同步段 ' + st.burst.syncMs + 'ms · 动画起 ' + st.burst.starts +
    ' 次（批数 ' + BURST_BATCH + '）· 完成 ' + st.burst.anims + ' 次 · DOM ' + st.burst.drawn + '/' + st.burst.cap +
    ' · 队列残留 ' + st.burst.queued)

  r.ok(v.file + ' · ⓪ ▶重放：从「第一拍有事件」处起、喂的条数 == 真帧该拍累计、喂进去的都画出来、停在底',
    st.replay.fed > 0 && st.replay.drawn === st.replay.fed && st.replay.fed === st.replay.cutAt &&
    st.replay.tick >= st.replay.head && !st.replay.animating && st.replay.top === st.replay.max,
    '前 ' + st.replay.head + ' 拍本窗 0 条（跳过的全是空拍）· 停在第 ' + st.replay.tick + ' 拍 · 喂了 ' +
    st.replay.fed + ' 行 = cuts[' + st.replay.tick + ']=' + st.replay.cutAt + '、在屏 ' + st.replay.drawn +
    ' 行 · 等 ' + st.replay.waitMs + 'ms 后队列空 · 停在底 ' + st.replay.top + '/' + st.replay.max)

  /* ══ 其余判据 ════════════════════════════════════════════════════════════ */
  /* 硬约束 5：数据源切换两版都在，且计数常驻 */
  r.ok(v.file + ' · 数据源切换在场（正常 / 模拟日常）', st.txtHasDemo && st.src.indexOf('正常') >= 0, st.src.replace(/\n/g, ' '))
  r.ok(v.file + ' · 「已放 N / 500 条」常驻', st.txtHasCap500 && st.src.indexOf('已放') >= 0,
    st.src.indexOf('已放') >= 0 ? st.src.replace(/\n/g, ' ') : '没找到「已放」')

  const got = st.info.map((x) => x.blk).sort().join(',')
  r.ok(v.file + ' · 区块数 = 7（六区 + 条件块）',
    v.regions.length === 6 && got === ALL_BLKS.filter((k) => got.indexOf(k) >= 0).sort().join(','), got)

  r.ok(v.file + ' · 8 处动效落点恰好 8 个、编号 1–8 各一次', st.anims.join(',') === '1,2,3,4,5,6,7,8', st.anims.join(','))

  /* ① C1 相邻不同构 */
  const same = st.adj.filter((p) => {
    const A = st.info.find((x) => x.blk === p.a), B = st.info.find((x) => x.blk === p.b)
    return A.sig === B.sig
  })
  r.ok(v.file + ' · C1 相邻区块不同构（' + st.adj.length + ' 对相邻）', same.length === 0,
    same.length ? JSON.stringify(same.map((p) => p.pair)) : st.adj.map((p) => p.pair).join(' '))

  /* ③ 宽度：与模型对账，不写常数 */
  const expectW = st.clientW - st.sideW - parseFloat(st.padL) * 2
  r.ok(v.file + ' · 主区宽 == clientW − 232 − 2×pad（模型，不写常数）',
    Math.abs(st.contentW - expectW) <= 1, st.contentW + ' vs ' + expectW)

  /* ④ 三样易漏项 */
  r.ok(v.file + ' · 易漏项 1：⑤ 写明「起止只来自 traces ⇒ 模拟日常下恒空」',
    st.notes.indexOf('traces') >= 0 && st.notes.indexOf('模拟日常') >= 0,
    (st.notes.match(/[^|]*traces[^|]*/) || ['（空）'])[0].trim().slice(0, 60))
  r.ok(v.file + ' · 易漏项 2：③ 写明「被淘汰的 seq 要从 seenSeq 摘掉」',
    st.notes.indexOf('seenSeq') >= 0, (st.notes.match(/[^|]*seenSeq[^|]*/) || ['（空）'])[0].trim().slice(0, 60))
  r.ok(v.file + ' · 易漏项 3：图例写明「本页窗口内累计／不用帧里 counts」',
    st.notes.indexOf('窗口内累计') >= 0, (st.notes.match(/[^|]*窗口内累计[^|]*/) || ['（空）'])[0].trim().slice(0, 60))

  console.log('    ' + v.file + '  文档高 ' + st.pageH + 'px · 视口 ' + st.innerH + ' · 客户区宽 ' +
    st.clientW + '（页面级滚动条 ' + (st.innerW - st.clientW) + 'px）· 主区 ' + st.contentW + 'px')
  console.log('      块高：' + st.info.map((x) => x.blk + ' ' + x.h + '（y' + x.y + '→' + x.bottom + '）').join(' · '))
  console.log('      事件流：可视 ' + st.listC + ' · 内部 ' + st.listS + ' · 行 ' + st.lines + ' × ' +
    Object.keys(st.heights)[0] + 'px（一屏 ' + Math.floor(st.listC / Number(Object.keys(st.heights)[0])) + ' 行）· 滚动条 ' + st.bar + 'px')
  console.log('      前导符：' + st.presKeys.map((k) => k + '×' + st.pres[k]).join(' ') + ' · 等级 ' +
    Object.keys(st.lv).map((k) => k + '×' + st.lv[k]).join(' ') + ' · trace 尾 ' + st.trTail + '/' + st.lines)
  console.log('      上移：' + st.smooth.vals.join(' ') + '  ⇒ ' + st.smooth.dur + 'ms 单调=' + st.smooth.mono)
  console.log('      相邻对：' + st.adj.map((p) => p.pair).join(' '))

  const out = path.join(DIR, v.png)
  await b.shotFull(out)
  r.ok(v.png + ' 落盘', fs.existsSync(out) && fs.statSync(out).size > 5000,
    fs.existsSync(out) ? fs.statSync(out).size + 'B' : 'MISSING')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
