/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a3-struct-shots.mjs —— 3a.3（日志页结构重做）甲 / 丙两版的整页截图 + 结构断言

   跑法：node scripts/probes/probe-b7a3-struct-shots.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP）—— **不起 dev server、不起后端**：
   两个 HTML 除了两份 CSS / 一份 JS 之外是自包含的（真数据烘在 `_struct-wire-ev.js` 里）。

   ── 判据怎么量 ──────────────────────────────────────────────────────────────
   〇 窗口化（**2026-10-16 改判：页高 100vh → 132vh**）
      · **页高 = 132vh**（1440×900 ⇒ 1188 ±2），且 ≤ 1.4×900 = 1260（用户要留 60–80px 余量）。
      · 页高**不随数据变**：往事件流里**临时塞 200 行**，前后比三个数 —— 文档高**不变**、
        事件流的 clientHeight **不变**、scrollHeight **变大**。
      · **六区全在页内**（每区 rect 的 top ≥ 0 且 bottom ≤ 文档高）—— 注意**不是**「全在首屏内」：
        132vh 的代价就是**整页要滚 ~288px**，⑤⑥ 要先滚一下才看得全。这是**批准过的取舍**，
        所以判据也从「一屏装完」改成「一页装完 + 至少要滚得动」。
      · **主区（`.px`）不被撑高**：scrollHeight == clientHeight。
      · **一个大外框包住六区**：`.ev-frame` 的 DOM 子树含六区，且几何上包含六区。
   ⓪ ③ 事件流 = 控制台行（用户 2026-10-16 逐条裁定）
      · **每条一行**：所有行的 offsetHeight **只有一个取值**（没有两行的卡）。
      · **无复选框 / 无左侧竖线 / 无右侧独立列**：行内没有 `input`；行内任何**可见**格子都不得有
        `border-left` 或宽度 < 6px 的色块。⚠️「可见」是这一轮补的：行内浮层（`.acts`）静息态是
        `display:none`，零尺寸 —— 不跳过它就会把「无窄色块」判成假红。
      · **三列定宽**：`grid-template-columns` 前五列是像素值，第六列是 `minmax(0, 1fr)`。
      · **行内四键不占列**：`.acts` 必须 `position: absolute`（绝对定位的孩子不进栅格 ⇒ 仍是 6 列）。
      · **同 trace 用前导符归组**：前导符形状 = `^(│  )*[├└]─$`（每层 3 字）。
        组内行（以 `│` 开头）上面必须能找到同号、前导符不带 `│` 的组首行。
      · **树形独立重算**（2026-10-16 用户报「只加了符号，没做层级和缩进」后补）：
        按三条规则在窗口里**重画一遍**再逐行比对 —— ① 层级 = 第一个 `├`/`└` 前面每 3 字一层；
        ② 左起第 j 组字符管第 j 层祖先（往上走 `dep-j` 次）；③ 本层末位 `└─`。
        ⚠️ 重算**不抄**页面 `decorate()` 的循环写法（抄了就是自己跟自己对数）；
        1 层时两种走法同解 ⇒ 1 层的绿**不给 2 层背书**，2 层只能在 node 里对合成序列验
        （`_work/3a3/tree-shape-check.mjs`）。缩进另量：`.pre` 列宽与时刻左沿都要右移 3ch。
      · **点某行 ⇒ 同 trace 整组微染**：真 click，被染行数 == 该号行数（且 ≥ 2）；只改背景；
        点完**页高与事件流可视高都不许变**。
      · **滚动条 6–8px** / **上移流畅** / **每秒 100 条不抖** / **手动滚打断、回底恢复**。
   ①′ 本轮四处改动（①②④⑤）＋ 三条联动（L1 / L2 / L3）—— **全部走真 click / 真 mouseover**：
      · ① **两排**（读数一排、控件一排），「错误率」与「mini 三色条」**相邻**（就近），
        三色条 3 段。
      · **L2**：错误率 > 0 ⇒ 读数变 + `.alert` 开。本窗 warn/error **全 0** ⇒ 天然不可达，
        所以**必须先注入**（`__evStream.injectSev(w, e)`，注入的是**读数**、不往 ③ 塞假行），
        量完 `clearInj()` 复位。⚠️「不注入 = L2 永远不可达 = 假绿」是用户点名的，不接受省略。
      · **L1**：点 ④ 的严重度条 / ① 的三色条段 / ② 的严重度 chip 三处**任一**，另外两处跟着变，
        且 ③ **真过滤**（可见行数变、DOM 行数不变 —— 筛掉的是藏起来、不是删掉）；
        再点同一处 = 取消。类 / ≥WARN / 对象 / 只看这条 trace 同理。
      · **L3（可选档，已加）**：悬停某档 ⇒ ③ 里同档的行浅底高亮；⚠️ **选中态要压得住高亮**
        （`:where()` 把 L3 的特异性压成 0 + 规则写在 `.sel` 之前 ⇒ 同分靠文档顺序）。
      · ③ 行内四键：复制本行（**真写剪贴板**，写不成就把原因写在按钮上）/ 只看这条 trace /
        展开（插一行原始字段、**插行期间锁住自动跟随**、再点收起）/ 暂停跟随。
      · ④ Top-N **三档真切**（对象 / 慢请求 / 错误），错误档本窗是**空态文案**不是假数据。
      · ⑤ 「关联跳转」行**两种选中态都只有一行高**（不然 ③ 被压矮、整页底部跳）。
      · ② 12 类分**两组**（业务 9 / 设施 3，中间一行组名）+ 控件 4 行 + 「源」**2 项**；
        **甲 给数字、丙 不给**（丙 连 `.c` 计数列都不存在，不是画个「—」占位）。
   ② 13 处动效落点（这一轮从 8 处涨到 13 处）—— 数 `[data-anim]` 的角标，要求**恰好 13 个
      且编号 1–13 各一次**（按**数值**排，不按字符串 —— '10' 字典序排在 '2' 前面）。
   ③ 各区宽度实测 —— 与「主区可用宽」对账，不写常数。
   ④ 易漏项 —— 按**文本**查在不在：⑤ 的「起止只来自 traces」、③ 的「被淘汰的 seq 要摘掉」、
      图例的「本页窗口内累计」、**③ 的时刻列口径原话**「同拍内按时刻局部排，跨拍按环序」、
      数据源（正常 / 模拟日常）两版都在且「已放 N / 500 条」常驻。

   ⚠️ **上一轮跑过的 C3（区块间距 > 区块内间距）已撤**：窗口化之后区与区之间是
    **一条 1px 分隔线**、不是空白带，「间距」这个量在窗口形态里没有意义。
   ⚠️ 行为判据（上移流畅 / 每秒 100 条 / 联动）要跑起来才量得到 ⇒ MEASURE 是 async IIFE，
      靠 CDP 的 `awaitPromise` 取值。
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
  { file: '3a3-甲-三栏驾驶舱.html', png: '3a3-甲-三栏驾驶舱.png', regions: REG_JIA, v: 'jia' },
  { file: '3a3-丙-上下分区.html', png: '3a3-丙-上下分区.png', regions: REG_BING, v: 'bing' },
]
const ALL_BLKS = ['status', 'flash', 'rail', 'stream', 'live', 'band', 'drawer', 'switch']

/* 声明值从**源文件**读，不从页面的 CSSOM 读 —— 实测 `document.styleSheets[i].cssRules`
   在 `file://` 下**抛 SecurityError**（样式表被视为不透明来源）。源文件才是可取证的那个副本。 */
const CSS_SRC = fs.readFileSync(path.join(DIR, '_struct-wire-ev.css'), 'utf8')
const GRID_BLOCK = (CSS_SRC.match(/\.log-head,\s*\.log-line\s*\{([^}]*)\}/) || [])[1] || ''
const GRID_DECL = (GRID_BLOCK.match(/grid-template-columns:\s*([^;]+);/) || [])[1] || ''

const VIEW_W = 1440
const VIEW_H = 900
const PAGE_VH = 1.32                  /* 用户 2026-10-16 改判：页高 = 132vh */
const PAGE_EXP = Math.round(VIEW_H * PAGE_VH)   /* 1188 */
const PAGE_MAX = Math.round(VIEW_H * 1.4)       /* 1260 —— 用户：「别卡 1.4 倍边缘」*/
const INJECT_N = 200       /* 临时塞进事件流的行数（证伪「事件越多页面越长」） */
const BURST_BATCH = 20     /* 每批 5 行 × 20 批 = 100 行 = 每秒 100 条 */
const BURST_ROW = 5
const BAR_LO = 6, BAR_HI = 8     /* 用户要的滚动条宽（实测必须落在这里面） */
const DUR_LO = 200, DUR_HI = 300 /* 用户要的平滑上移时长 */
const ANIM_EXP = '1,2,3,4,5,6,7,8,9,10,11,12,13'   /* 13 处落点（数值序）*/

const r = report('3a3 日志页结构草图（甲 / 丙 · 控制台行）')
console.log('=== 3a.3 日志页结构草图：控制台行 + 行为 + 联动 + 整页截图 ===')
console.log('跑法：node scripts/probes/probe-b7a3-struct-shots.mjs')
console.log('底座：docs/美化/_cdp.mjs（headless Edge + CDP，--headless=new，' + VIEW_W + '×' + VIEW_H +
  '，file:// 直开，不起 server / 不起后端）')
console.log('版本：' + VARIANTS.map((v) => v.file).join(' / '))
console.log('')

/* ⚠️ 模板字符串里**不许**出现反引号或 ${（会把模板提前闭合，报错还指向注释行）——
   全部用字符串拼接；页内要用的 `│` 写成 '\\u2502'（外层模板先吃一层反斜杠）。 */
const MEASURE = `(async function () {
  window.__errs = []
  window.addEventListener('error', function (e) { window.__errs.push(String(e && e.message)) })
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
    var cnt = {}, kids = [].slice.call(e.children)
    for (var i = 0; i < kids.length; i++) {
      var t = kids[i].tagName.toLowerCase(); cnt[t] = (cnt[t] || 0) + 1
    }
    var childSig = Object.keys(cnt).sort().map(function (t) { return t + ':' + cnt[t] }).join(',')
    return e.tagName.toLowerCase() + '|' + cs.display + '|' + axis + '|' + childSig + '|' + tags(e).join(',')
  }
  function frame2() { return new Promise(function (res) { requestAnimationFrame(function () { requestAnimationFrame(res) }) }) }
  function T(e) { return e ? (e.innerText || '').replace(/\\s+/g, ' ') : '' }

  var EVS = window.__evStream
  var frame = document.querySelector('.ev-frame')
  var fr = frame ? rect(frame) : null
  var blocks = [].slice.call(document.querySelectorAll('[data-blk]')).filter(vis)
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

  /* 动效落点：逗号列表拆开去重、**按数值排**（'10' 的字典序在 '2' 前面）*/
  var anims = []
  ;[].slice.call(document.querySelectorAll('[data-anim]')).forEach(function (e) {
    e.getAttribute('data-anim').split(',').forEach(function (a) {
      a = a.trim(); if (a && anims.indexOf(a) < 0) anims.push(a)
    })
  })
  anims.sort(function (x, y) { return Number(x) - Number(y) })

  /* ── 〇 注入实验：事件流里**再塞 200 行**，看页高动不动 ──────────────── */
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
    c2.removeAttribute('data-anim')
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
  /* 组内行（以 '│' 开头）往上找同号的组首行。⚠️ **顶端的组可能被环形窗口截断** ——
     组首行先被淘汰出 200 行窗口，它后面的组内行就成了「找不到组首」。
     所以判据不是「orphan == 0」，而是「所有孤儿行都同属**最顶上那一组**」
     （id 与 rows[0] 相同）—— 那是窗口截断，不是树画错了。 */
  var orphan = 0, orphanIds = {}, orphanTop = 0
  for (var i2 = 0; i2 < rows.length; i2++) {
    var p = rows[i2].querySelector('.pre').textContent
    if (p.indexOf('\\u2502') !== 0) continue
    var tr2 = rows[i2].getAttribute('data-trace'), found = false
    for (var k2 = i2 - 1; k2 >= 0; k2--) {
      if (rows[k2].getAttribute('data-trace') !== tr2) break
      if (rows[k2].querySelector('.pre').textContent.indexOf('\\u2502') !== 0) { found = true; break }
    }
    if (!found) {
      orphan++
      orphanIds[tr2] = (orphanIds[tr2] || 0) + 1
      if (tr2 === rows[0].getAttribute('data-trace')) orphanTop++
    }
  }
  var orphanId = Object.keys(orphanIds).join(',')
  /* ── ⓪-1b 树形**独立重算**：不信 decorate() 的输出，按用户给的三条规则在窗口里重画一遍 ──
     规则：① 层级 = 前导符里第一个 '├'/'└' 前面每 3 字算一层；
           ② **左起第 j 组**字符管**第 j 层**祖先（往上走 dep - j 次）：该祖先还有后续兄弟 ⇒ '│  '；
           ③ 本层末位用 '└─'、否则 '├─'。
     ⚠️ 这一段**故意不抄页面 decorate() 的循环写法** —— 抄了就变成「自己和自己对账」。
        第 ② 条的走法在 1 层时两种写法同解（只有一组），所以 1 层的绿**不能**替 2 层背书。 */
  var tree = (function () {
    var m = rows.length, dep = [], txt = [], i2, j2, t2
    for (i2 = 0; i2 < m; i2++) {
      var s2 = rows[i2].querySelector('.pre').textContent
      txt.push(s2)
      var bi = Math.max(s2.indexOf('\\u251C'), s2.indexOf('\\u2514'))
      dep.push(bi < 0 ? -1 : Math.floor(bi / 3))
    }
    var stk = [], par = []
    for (i2 = 0; i2 < m; i2++) {
      if (dep[i2] < 0) { par.push(-2); continue }
      stk.length = dep[i2]
      par.push(dep[i2] > 0 ? (stk[dep[i2] - 1] === undefined ? -1 : stk[dep[i2] - 1]) : -1)
      stk[dep[i2]] = i2
    }
    var seen = {}, last = []
    for (i2 = m - 1; i2 >= 0; i2--) if (seen[par[i2]] === undefined) seen[par[i2]] = i2
    for (i2 = 0; i2 < m; i2++) last.push(seen[par[i2]] === i2)
    var bad = [], dd = {}
    for (i2 = 0; i2 < m; i2++) {
      if (dep[i2] < 0) { bad.push({ i: i2, want: '(含 ├/└ 的前导符)', got: txt[i2] }); continue }
      var want = ''
      for (j2 = 0; j2 < dep[i2]; j2++) {
        var a = i2
        for (t2 = 0; t2 < dep[i2] - j2; t2++) a = a < 0 ? -1 : par[a]
        want += (a < 0 || !last[a]) ? '\\u2502  ' : '   '
      }
      want += (last[i2] ? '\\u2514' : '\\u251C') + '\\u2500'
      dd[dep[i2]] = (dd[dep[i2]] || 0) + 1
      if (want !== txt[i2] && bad.length < 5) bad.push({ i: i2, want: want, got: txt[i2] })
    }
    /* 缩进是否真是 3 字：量 1 个字符的宽（Range 取字形盒），再比 .pre 列宽与时刻左沿的位移 */
    var cw = 0
    var t0 = rows.length ? rows[0].querySelector('.pre').firstChild : null
    if (t0 && t0.nodeValue) {
      var rg = document.createRange(); rg.setStart(t0, 0); rg.setEnd(t0, 1)
      cw = rg.getBoundingClientRect().width
    }
    var w = {}, tmL = {}
    for (i2 = 0; i2 < m; i2++) {
      var d2 = dep[i2]; if (d2 < 0 || w[d2] !== undefined) continue
      w[d2] = getComputedStyle(rows[i2].querySelector('.pre')).width
      tmL[d2] = rows[i2].querySelector('.tm').getBoundingClientRect().left
    }
    var ddOk = 0
    for (i2 = 0; i2 < m; i2++) if (Number(rows[i2].getAttribute('data-d')) === dep[i2]) ddOk++
    function startsWith(x, p) { return txt.filter(function (s3) { return s3.indexOf(p) === 0 }).length }
    return { rows: m, depCnt: dd, mismatch: bad, cw: cw, w: w, tmL: tmL, ddOk: ddOk === m,
      maxDep: Math.max.apply(null, dep),
      barRows: startsWith(txt, '\\u2502'), cornerRows: txt.filter(function (s3) { return s3.indexOf('\\u2514') >= 0 }).length,
      presKeys: Object.keys(pres).sort() }
  })()
  /* 行内不许有 input / border-left / 窄色块。
     ⚠️ **跳过零尺寸**（静息态的行内浮层是 display:none）：不跳的话每个隐藏元素
        都算「宽度 0 < 6 的色块」，这条判据会永远假红。 */
  var inputs = list.querySelectorAll('input').length
  var vb = 0, inn = list.querySelectorAll('.log-line *')
  for (var k3 = 0; k3 < inn.length; k3++) {
    var rc3 = rect(inn[k3])
    if (rc3.width < 0.5 && rc3.height < 0.5) continue
    var cs2 = getComputedStyle(inn[k3])
    if (parseFloat(cs2.borderLeftWidth) > 0) vb++
    else if (cs2.backgroundColor !== 'rgba(0, 0, 0, 0)' && rc3.width < 6) vb++
  }
  var cols = rows.length ? getComputedStyle(rows[0]).gridTemplateColumns : ''
  var colArr = cols.split(' ')
  var cs0 = rows.length ? getComputedStyle(rows[0]) : null
  var fixed5 = 0
  for (var f5 = 0; f5 < 5 && f5 < colArr.length; f5++) fixed5 += parseFloat(colArr[f5])
  var gapPh = cs0 ? parseFloat(cs0.columnGap) : 0
  var padLR = cs0 ? parseFloat(cs0.paddingLeft) + parseFloat(cs0.paddingRight) : 0
  var colModel = Math.round(list.clientWidth - fixed5 - gapPh * 5 - padLR)

  /* ── ⓪-2 点某行 ⇒ 同 trace 整组微染 ────────────────────────────────── */
  var cH0 = list.clientHeight, pageH0 = document.documentElement.scrollHeight
  var g = null
  for (var i3 = 0; i3 < rows.length; i3++) {
    var t3 = rows[i3].getAttribute('data-trace'), same = 0
    for (var j3 = 0; j3 < rows.length; j3++) if (rows[j3].getAttribute('data-trace') === t3) same++
    if (same >= 3 && rows[i3].querySelector('.pre').textContent.indexOf('\\u2502') === 0) { g = rows[i3]; break }
  }
  var sel = null
  if (g) {
    EVS.select(-99)
    g.click()
    var trSel = g.getAttribute('data-trace')
    var cg = getComputedStyle(g)
    var jr = document.querySelector('#ev-drawer .jr')
    sel = { tr: trSel, hits: list.querySelectorAll('.sel').length,
      same: list.querySelectorAll('[data-trace="' + trSel + '"]').length,
      bg: cg.backgroundColor, bL: cg.borderLeftWidth, bImg: cg.backgroundImage, color: cg.color,
      cH: list.clientHeight, pageH: document.documentElement.scrollHeight,
      hdH: Math.round(document.querySelector('#ev-drawer .hd').getBoundingClientRect().height),
      jrH: jr ? Math.round(rect(jr).height) : -1,
      drawerH: Math.round(rect(document.querySelector('[data-blk="drawer"]')).height),
      drawerTr: document.getElementById('ev-drawer').getAttribute('data-seltr') }
  }

  /* ── ⓪-3 上移流畅 ─────────────────────────────────────────────────── */
  EVS.select(-99)
  var t0 = performance.now(), samples = []
  EVS.append(3)
  await new Promise(function (res) {
    function tick() {
      samples.push([Math.round(performance.now() - t0), Math.round(list.scrollTop)])
      if (performance.now() - t0 < 420) requestAnimationFrame(tick); else res()
    }
    requestAnimationFrame(tick)
  })
  var stS = EVS.state()
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
  var autoAfterTop = EVS.state().auto
  list.scrollTop = list.scrollHeight
  await frame2()
  var autoAfterBottom = EVS.state().auto

  /* ── ⓪-5 每秒 100 条 ──────────────────────────────────────────────── */
  var s0 = EVS.state()
  var tA = performance.now()
  for (var q3 = 0; q3 < ` + BURST_BATCH + `; q3++) EVS.append(` + BURST_ROW + `)
  var syncMs = Math.round(performance.now() - tA)
  await new Promise(function (res) { setTimeout(res, 1400) })
  var s1 = EVS.state()
  var burst = { fed: s1.fed - s0.fed, starts: s1.starts - s0.starts, anims: s1.anims - s0.anims,
    syncMs: syncMs, drawn: s1.drawn, cap: s1.cap, queued: s1.queued, bar: s1.bar }

  /* ── ⓪-6 ▶重放 ───────────────────────────────────────────────────── */
  EVS.replay()
  var waited = 0, sW = null
  while (waited < 60) {
    sW = EVS.state()
    if (sW.drawn > 0 && sW.queued === 0 && !sW.animating) break
    await new Promise(function (res) { setTimeout(res, 100) })
    waited++
  }
  var rep = sW
  var replay = { tick: rep.tick, drawn: rep.drawn, top: rep.scrollTop, max: rep.maxTop, fed: rep.fed,
    head: DATA.replayHead, cutAt: DATA.cuts[rep.tick], waitMs: waited * 100,
    animating: rep.animating, queued: rep.queued }
  EVS.reset()
  await frame2()

  /* ══ ①′ ①②④⑤ 四处改动 + 三条联动 ══════════════════════════════════════════
     ⚠️ 全部走**真 click / 真 mouseover**（element.click() 会真的派发并冒泡到 document 上的
        委托监听器）——直接调 setFlt() 量不到「线接没接上」。 */
  function visRows(f) {
    var n = 0, ks = list.children
    for (var i = 0; i < ks.length; i++) {
      if (ks[i].style.display === 'none' || ks[i].getAttribute('data-xp')) continue
      if (f && !f(ks[i])) continue
      n++
    }
    return n
  }
  var stEl = document.querySelector('.ev-status')
  var cellErr = document.querySelector('.ev-status .cell.err')
  var mini = document.querySelector('.ev-status [data-role="sevmini"]')
  var status = { h: Math.round(rect(stEl).height),
    two: !!document.querySelector('.ev-status .r1') && !!document.querySelector('.ev-status .r2'),
    r1Top: Math.round(rect(document.querySelector('.ev-status .r1')).top),
    r2Top: Math.round(rect(document.querySelector('.ev-status .r2')).top),
    r1H: Math.round(rect(document.querySelector('.ev-status .r1')).height),
    r2H: Math.round(rect(document.querySelector('.ev-status .r2')).height),
    errInR1: !!document.querySelector('.ev-status .r1 .cell.err'),
    near: mini ? Math.round(rect(mini).left - rect(cellErr).right) : -999,
    miniN: mini ? mini.children.length : -1,
    txt: T(stEl) }

  /* ── L2：注入 warn/error ⇒ 读数 + 变红（**不注入就永远不可达**）────── */
  var l2 = { before: { txt: T(cellErr), alert: cellErr.classList.contains('alert') } }
  EVS.injectSev(3, 2)
  l2.after = { txt: T(cellErr), alert: cellErr.classList.contains('alert'),
    flex: [].slice.call(document.querySelectorAll('[data-role="sevmini"] i')).map(function (e) { return e.style.flex }).join('|'),
    bars: [].slice.call(document.querySelectorAll('[data-role="sevbars"] .num')).map(function (e) { return e.textContent }).join('|'),
    hexp: [].slice.call(document.querySelectorAll('[data-role="sevchip"]')).map(function (e) { return e.textContent }).join('|'),
    statusH: Math.round(rect(stEl).height) }
  EVS.clearInj()
  l2.back = { txt: T(cellErr), alert: cellErr.classList.contains('alert') }

  /* ── L1：三处入口 + 五种过滤 ─────────────────────────────────────── */
  var l1 = {}
  var bars = document.querySelectorAll('[data-role="sevbars"] .ev-bar')
  bars[0].click()
  l1.byBar = { desc: EVS.fltDesc(), shown: visRows(), dom: list.children.length, drawn: EVS.state().drawn,
    chip: document.querySelector('[data-role="sevchip"][data-s="0"]').classList.contains('on'),
    mini: document.querySelectorAll('[data-role="sevmini"] i')[0].classList.contains('pick'),
    bar: bars[0].classList.contains('pick'), rowsTxt: T(document.querySelector('[data-role="rows"]')) }
  bars[0].click()
  l1.off = { desc: EVS.fltDesc(), shown: visRows() }
  document.querySelectorAll('[data-role="sevmini"] i')[1].click()
  l1.byMini = { desc: EVS.fltDesc(), shown: visRows(),
    chip: document.querySelector('[data-role="sevchip"][data-s="1"]').classList.contains('on'),
    bar: bars[1].classList.contains('pick') }
  document.querySelector('[data-role="sevchip"][data-s="1"]').click()
  l1.byChipOff = { desc: EVS.fltDesc(), shown: visRows() }
  document.querySelector('.ev-rail .row[data-f="kind"][data-k="cache"]').click()
  l1.byKind = { desc: EVS.fltDesc(), shown: visRows(), dom: list.children.length,
    allCache: visRows(function (e) { return e.getAttribute('data-k') === 'cache' }) === visRows() }
  document.querySelector('[data-role="warnup"]').click()
  l1.warnUp = { desc: EVS.fltDesc(), shown: visRows() }
  document.querySelector('[data-role="clear"]').click()
  l1.cleared = { desc: EVS.fltDesc(), shown: visRows(), dom: list.children.length,
    chip: document.querySelector('[data-role="sevchip"][data-s="0"]').classList.contains('on'),
    objBox: T(document.querySelector('[data-role="objbox"]')) }
  var obEl = list.querySelector('.log-line .ob.p')
  if (obEl) {
    var obLine = obEl.closest('.log-line'), obV = obLine.getAttribute('data-obj')
    obEl.click()
    l1.byObj = { obj: obV, box: T(document.querySelector('[data-role="objbox"]')), shown: visRows(),
      allMatch: visRows(function (e) { return e.getAttribute('data-obj') === obV }) === visRows() }
    EVS.clearFlt()
  }
  var g2 = null
  for (var i5 = 0; i5 < list.children.length; i5++) {
    var e5 = list.children[i5]
    if (e5.getAttribute('data-trace') && e5.querySelector('.pre').textContent.indexOf('\\u2502') === 0) { g2 = e5; break }
  }
  if (g2) {
    EVS.select(-99)
    g2.click()
    var trS = g2.getAttribute('data-trace')
    var selN = list.querySelectorAll('.sel').length
    g2.querySelector('[data-xact="trace"]').click()
    l1.byTrace = { tr: trS, selN: selN, desc: EVS.fltDesc(), shown: visRows(),
      allMatch: visRows(function (e) { return e.getAttribute('data-trace') === trS }) === visRows() }
    EVS.clearFlt(); EVS.select(-99)
  }
  /* 复制本行：**真去写剪贴板**；file:// 下会失败 —— 判据是「要么成功、要么把原因写出来」 */
  var cpRow = list.children[7]
  cpRow.querySelector('[data-xact="copy"]').click()
  await new Promise(function (res) { setTimeout(res, 250) })
  l1.copy = { last: EVS.state().lastCopy, btn: cpRow.querySelector('[data-xact="copy"]').textContent }

  /* 展开行：插一行 ⇒ 锁跟随 ⇒ 再点收起 */
  var xRow = list.children[9]
  var page1 = document.documentElement.scrollHeight, cH1 = list.clientHeight, sH1 = list.scrollHeight
  xRow.querySelector('[data-xact="expand"]').click()
  await frame2()
  var xp = list.querySelector('[data-xp]')
  l1.xp = { n: list.querySelectorAll('[data-xp]').length, auto: EVS.state().auto, drawn: EVS.state().drawn,
    dom: list.children.length, next: !!(xp && xRow.nextElementSibling === xp), h: xp ? Math.round(rect(xp).height) : -1,
    pageSame: document.documentElement.scrollHeight === page1, cSame: list.clientHeight === cH1,
    sGrew: list.scrollHeight > sH1, txt: T(xp).slice(0, 60) }
  if (xp) xp.click()
  await frame2()
  l1.xp.after = list.querySelectorAll('[data-xp]').length
  /* ⚠️ 这里**必须**取 list.children[0]（活引用）不是上面那份 rows 快照：
     EVS.reset() 已经把列表整段重渲过 ⇒ 快照里的节点早脱离文档，
     而 getComputedStyle 对**脱离文档**的元素返回空声明（.position 读出 ''），
     于是这条断言会以「position 空」假红。 */
  l1.acts = { perRow: list.children[0].querySelectorAll('.acts i').length,
    keys: [].slice.call(list.children[0].querySelectorAll('[data-xact]')).map(function (e) { return e.getAttribute('data-xact') }).join(','),
    pos: getComputedStyle(list.children[0].querySelector('.acts')).position }

  /* ── ④ 榜单三档真切 ──────────────────────────────────────────────── */
  var t1 = document.querySelector('[data-role="toptabs"] .tb[data-top="slow"]')
  t1.click()
  var tabs = { slow: { on: t1.classList.contains('on'), n: document.getElementById('ev-top-lst').children.length,
    txt: T(document.getElementById('ev-top-lst')).slice(0, 50) } }
  var t2 = document.querySelector('[data-role="toptabs"] .tb[data-top="err"]')
  t2.click()
  tabs.err = { on: t2.classList.contains('on'), empty: !!document.querySelector('#ev-top-lst .empty'),
    txt: T(document.getElementById('ev-top-lst')).slice(0, 40) }
  var t3 = document.querySelector('[data-role="toptabs"] .tb[data-top="obj"]')
  t3.click()
  tabs.obj = { on: t3.classList.contains('on'), n: document.getElementById('ev-top-lst').children.length,
    txt: T(document.getElementById('ev-top-lst')).slice(0, 40) }

  /* ── L3：悬停某档 ⇒ ③ 同档高亮；选中态要压得住它 ──────────────────── */
  var tr3 = Number(list.children[4].getAttribute('data-trace'))
  EVS.select(-99); EVS.select(tr3)
  await frame2()
  var selRow = list.querySelector('.log-line.sel'), infoRow = null
  for (var i6 = 0; i6 < list.children.length; i6++) {
    if (list.children[i6].classList.contains('lv-i') && !list.children[i6].classList.contains('sel')) { infoRow = list.children[i6]; break }
  }
  document.querySelectorAll('[data-role="sevmini"] i')[0].dispatchEvent(new MouseEvent('mouseover', { bubbles: true }))
  await frame2()
  var l3 = { cls: list.className, hasSel: !!selRow, hasInfo: !!infoRow,
    selBg: selRow ? getComputedStyle(selRow).backgroundColor : '',
    infoBg: infoRow ? getComputedStyle(infoRow).backgroundColor : '' }
  stEl.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }))
  await frame2()
  l3.off = { cls: list.className, infoBg: infoRow ? getComputedStyle(infoRow).backgroundColor : '' }

  /* ── ⑤ 关联跳转行：两种选中态都只有一行高 ───────────────────────── */
  var draw = document.querySelector('[data-blk="drawer"]')
  var trRec = -1
  for (var i7 = 0; i7 < list.children.length; i7++) {
    var t7 = Number(list.children[i7].getAttribute('data-trace'))
    if (DATA.trRec[t7]) { trRec = t7; break }
  }
  EVS.select(-99)
  await frame2()
  var jr = {}
  jr.none = { h: Math.round(rect(document.querySelector('#ev-drawer .jr')).height),
    dead: !!document.querySelector('#ev-drawer .jr .jm.dead'), drawerH: Math.round(rect(draw).height),
    page: document.documentElement.scrollHeight }
  if (trRec > 0) {
    EVS.select(trRec)
    await frame2()
    jr.withRec = { tr: trRec, h: Math.round(rect(document.querySelector('#ev-drawer .jr')).height),
      jms: document.querySelectorAll('#ev-drawer .jr .jm').length,
      drawerH: Math.round(rect(draw).height), page: document.documentElement.scrollHeight }
  }
  EVS.reset()
  await frame2()
  var afterReset = { desc: EVS.fltDesc(), shown: visRows(), xp: list.querySelectorAll('[data-xp]').length,
    inj: EVS.state().inj, topTab: EVS.state().topTab, auto: EVS.state().auto, drawn: EVS.state().drawn,
    page: document.documentElement.scrollHeight }

  /* ── ② 的形态 ─────────────────────────────────────────────────────── */
  var rail = { kindRows: document.querySelectorAll('.ev-rail .row[data-f="kind"]').length,
    facKeys: [].slice.call(document.querySelectorAll('.ev-rail .row[data-f="kind"]')).map(function (e) { return e.getAttribute('data-k') }).slice(9).join(','),
    gh: document.querySelectorAll('.ev-rail .row.gh').length,
    ghTxt: T(document.querySelector('.ev-rail .row.gh')),
    counts: document.querySelectorAll('.ev-rail .row .c').length,
    ctlLines: document.querySelectorAll('.ev-rail .ctl .ln').length,
    srcChips: document.querySelectorAll('.ev-rail .ctl [data-src]').length,
    srcLabels: [].slice.call(document.querySelectorAll('.ev-rail .ctl [data-src]')).map(function (e) { return e.textContent }).join('/'),
    warnUp: !!document.querySelector('[data-role="warnup"]'),
    foot: T(document.querySelector('[data-role="clear"]')),
    sub: T(document.querySelector('.ev-rail .sub')) }

  var txt = document.body.innerText || ''
  var notes = [].slice.call(document.querySelectorAll('.ev-h2 .n, .log-note, .ev-rail .sub, .ev-drawer .note, .ev-cond, .band .ttl, .ev-top .cap, .ev-sub, .ev-switch .n, .ev-drawer .jr .hint'))
    .map(function (e) { return e.innerText }).join(' | ')
  var head = document.querySelector('.wire-head'), shell = document.querySelector('.shell'), foot = document.querySelector('.wire-foot')
  return JSON.stringify({
    d: typeof DATA, kinds: DATA && DATA.kinds.length, sevs: DATA && DATA.sevs.length,
    sources: DATA && DATA.sources.length, fac: DATA && DATA.kinds.filter(function (k) { return k.grp === 'fac' }).length,
    orderRule: DATA && DATA.order && DATA.order.rule,
    wired: window.__evWired && window.__evWired.kinds,
    info: infoShow, pairs: pairs, adj: adj, anims: anims,
    frame: fr ? { x: Math.round(fr.left), y: Math.round(fr.top), w: Math.round(fr.width), h: Math.round(fr.height),
      right: Math.round(fr.right), bottom: Math.round(fr.bottom),
      bw: getComputedStyle(frame).borderTopWidth } : null,
    inject: { before: before, after: after, clean: afterClean, n: ` + INJECT_N + `,
      pageSame: before.page === after.page, listSame: before.listC === after.listC,
      grew: after.listS > before.listS + ` + INJECT_N + ` },
    listC: list.clientHeight, listS: list.scrollHeight,
    pxC: px.clientHeight, pxS: px.scrollHeight,
    src: T(document.querySelector('.ev-src')),
    notes: notes,
    headH: head ? Math.round(rect(head).height) : -1,
    shellH: shell ? Math.round(rect(shell).height) : -1,
    footH: foot ? Math.round(rect(foot).height) : -1,
    lines: rows.length, heights: hs, pres: pres, presKeys: presKeys, lv: lv, trTail: trTail,
    orphan: orphan, orphanId: orphanId, orphanTop: orphanTop, tree: tree,
    topTr: rows.length ? rows[0].getAttribute('data-trace') : '-',
    inputs: inputs, vb: vb, cols: cols, colArr: colArr,
    decl: GRID_DECL_PLACEHOLDER, colModel: colModel, colGap: gapPh, colPad: padLR,
    sel: sel, smooth: smooth, autoAfterTop: autoAfterTop, autoAfterBottom: autoAfterBottom,
    burst: burst, replay: replay, bar: EVS.state().bar,
    status: status, l2: l2, l1: l1, tabs: tabs, l3: l3, jr: jr, rail: rail, afterReset: afterReset,
    sameTxt: { status: T(document.querySelector('[data-blk="status"]')), drawer: T(draw), switch: T(document.querySelector('[data-blk="switch"]')) },
    errs: window.__errs,
    contentW: px ? Math.round(rect(px).width) : -1,
    clientW: document.documentElement.clientWidth,
    innerW: window.innerWidth,
    innerH: window.innerHeight,
    sideW: Math.round(rect(document.querySelector('.side')).width),
    padL: getComputedStyle(document.querySelector('.main')).paddingLeft,
    pageH: document.documentElement.scrollHeight,
    txtHasDemo: txt.indexOf('模拟日常') >= 0,
    txtHasCap500: txt.indexOf('500') >= 0,
    pdf: 1
  })
})()`
/* ⚠️ `decl` 那条判据要的是**源文件里的声明**（页面 CSSOM 在 file:// 下取不到）——
   这里把源文件里的原文拼进 MEASURE 的字符串里（不是页里读的）。 */
const MEASURE_FIXED = MEASURE.replace('GRID_DECL_PLACEHOLDER', JSON.stringify(GRID_DECL))

const b = await open({ w: VIEW_W, h: VIEW_H, headed: false })
const sameSet = {}

for (const v of VARIANTS) {
  const html = path.join(DIR, v.file)
  if (!fs.existsSync(html)) { r.ok(v.file + ' 存在', false, 'NOT FOUND'); continue }
  await b.goto(pathToFileURL(html).href, 1200)
  const st = JSON.parse(await b.evalJS(MEASURE_FIXED))
  sameSet[v.v] = st.sameTxt

  r.ok(v.file + ' · DATA 已加载', st.d === 'object', st.d)
  r.ok(v.file + ' · 12 类（业务 9 + 设施 3）/ 3 严重度 / 源 2 项',
    st.kinds === 12 && st.sevs === 3 && st.sources === 2 && st.fac === 3,
    st.kinds + ' 类（设施 ' + st.fac + '）· ' + st.sevs + ' 档 · 源 ' + st.sources + ' 项')
  r.ok(v.file + ' · 时刻列口径写进数据契约', st.orderRule === '同拍内按时刻局部排，跨拍按环序', st.orderRule)
  r.ok(v.file + ' · 页内没有 JS 报错（含所有交互跑完）', st.errs.length === 0, st.errs.join(' | ') || '（无）')

  /* ══ 〇 窗口化（这一轮改判：132vh）══════════════════════════════════════ */
  const by = {}
  st.info.forEach((x) => { by[x.blk] = x })
  const missing = v.regions.filter(([k]) => !by[k]).map(([k]) => k)
  r.ok(v.file + ' · 六区齐全（' + v.regions.map(([, n]) => n).join('') + '）', missing.length === 0,
    missing.length ? '缺 ' + missing.join(',') : Object.keys(by).join(','))

  r.ok(v.file + ' · 〇 页高 = 132vh（' + VIEW_H + '×1.32 ⇒ ' + PAGE_EXP + ' ±2）',
    Math.abs(st.pageH - PAGE_EXP) <= 2, st.pageH + ' vs ' + PAGE_EXP)
  r.ok(v.file + ' · 〇 页高 ≤ 1.4×视口（' + PAGE_MAX + '，留 ' + (PAGE_MAX - PAGE_EXP) + 'px 余量给「别贴边」）',
    st.pageH <= PAGE_MAX && st.pageH / st.innerH >= 1.25,
    st.pageH + ' / ' + st.innerH + ' = ' + (st.pageH / st.innerH).toFixed(3))

  const offPage = v.regions.filter(([k]) => by[k] && (by[k].y < -0.5 || by[k].bottom > st.pageH + 0.5))
    .map(([k, n]) => n + ' ' + by[k].y + '→' + by[k].bottom + '（页高 ' + st.pageH + '）')
  r.ok(v.file + ' · 〇 六区全在**页内**（不是首屏内 —— 132vh 的代价就是整页要滚）',
    offPage.length === 0, offPage.length ? offPage.join(' · ') : '六区 bottom ≤ ' + st.pageH)

  r.ok(v.file + ' · 〇 整页可滚 + ⑤⑥ 要滚一次才看得全（批准过的取舍）',
    st.pageH > st.innerH && by.switch.bottom > st.innerH,
    '页高 ' + st.pageH + ' > 视口 ' + st.innerH + ' · ⑥ 底 ' + by.switch.bottom + ' > ' + st.innerH +
    '（差 ' + (by.switch.bottom - st.innerH) + 'px）')

  const noIdx = v.regions.filter(([k, n]) => by[k] && by[k].txt.indexOf(n) < 0).map(([k, n]) => n + '@' + k)
  r.ok(v.file + ' · 〇 六区各自带区号标签（可用眼睛数）', noIdx.length === 0, noIdx.length ? noIdx.join(' ') : '①…⑥ 都在')

  r.ok(v.file + ' · 〇 一个大外框包住六区（DOM 子树 + 几何包含）',
    !!st.frame && st.frame.bw === '1px' && v.regions.every(([k]) => by[k] && by[k].inFrame),
    st.frame ? ('框 ' + st.frame.w + '×' + st.frame.h + ' · 边线 ' + st.frame.bw + ' · 六区 inFrame=' +
      v.regions.filter(([k]) => by[k] && by[k].inFrame).length + '/6') : 'NO FRAME')

  r.ok(v.file + ' · 〇 标题 + 外壳 + 页脚 = 页高',
    Math.abs(st.headH + st.shellH + st.footH - st.pageH) <= 2,
    st.headH + ' + ' + st.shellH + ' + ' + st.footH + ' vs ' + st.pageH)
  r.ok(v.file + ' · 〇 主区不被撑高（.px scrollHeight == clientHeight）', st.pxS <= st.pxC + 1,
    st.pxS + ' vs ' + st.pxC)

  r.ok(v.file + ' · 〇 事件流有内部滚动条（内容超出可视区）', st.listS > st.listC + 1,
    'scrollHeight ' + st.listS + ' > clientHeight ' + st.listC)
  r.ok(v.file + ' · 〇 塞 ' + st.inject.n + ' 行：页高不变、列表可视高不变、内部滚变长',
    st.inject.pageSame && st.inject.listSame && st.inject.grew,
    '页高 ' + st.inject.before.page + '→' + st.inject.after.page + '（' + (st.inject.pageSame ? '不变' : '**变了**') + '）· ' +
    '列表可视 ' + st.inject.before.listC + '→' + st.inject.after.listC + '（' + (st.inject.listSame ? '不变' : '**变了**') + '）· ' +
    '列表内部 ' + st.inject.before.listS + '→' + st.inject.after.listS + '（' + (st.inject.grew ? '变长' : '**没变长**') + '）')

  /* ══ ⓪ ③ 事件流 = 控制台行 ══════════════════════════════════════════════ */
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
    ' vs 模型 ' + st.colModel + 'px')
  r.ok(v.file + ' · ⓪ 行内四键**不占列**（绝对定位 + 四个键 + 仍是 6 列）',
    st.l1.acts.pos === 'absolute' && st.l1.acts.perRow === 4 && st.colArr.length === 6,
    'position ' + st.l1.acts.pos + ' · 每行 ' + st.l1.acts.perRow + ' 键 · 列 ' + st.colArr.length)

  const P4RE = /^(│  )*[├└]─$/
  r.ok(v.file + ' · ⓪ 前导符形状：每层 3 字 + 末位 ├/└（正则 ^(│  )*[├└]─$，不再列死四种字面量）',
    st.presKeys.every((k) => P4RE.test(k)) && st.presKeys.length >= 2,
    st.presKeys.map((k) => JSON.stringify(k) + '×' + st.pres[k]).join(' · '))
  const tm0 = st.tree.tmL['0'], tm1 = st.tree.tmL['1']
  const pc0 = parseFloat(st.tree.w['0']), pc1 = parseFloat(st.tree.w['1'])
  const round = (x) => Math.round(x * 100) / 100
  r.ok(v.file + ' · ⓪ 树形**独立重算**逐行对齐（层级=前导符/3、末兄弟 └─、第 j 组 ↔ 第 j 层祖先）',
    st.tree.mismatch.length === 0 && st.tree.rows > 0,
    st.tree.mismatch.length
      ? ('前 ' + st.tree.mismatch.length + ' 处不符：' + st.tree.mismatch.map((x) =>
        '第' + x.i + '行 应 ' + JSON.stringify(x.want) + ' 实 ' + JSON.stringify(x.got)).join(' · '))
      : (st.tree.rows + ' 行全部一致 · 层级分布 ' + JSON.stringify(st.tree.depCnt)))
  r.ok(v.file + ' · ⓪ 缩进真是 3 字（.pre 列宽与时刻左沿同步右移 3ch）',
    st.tree.cw > 0 && Math.abs((pc1 - pc0) - 3 * st.tree.cw) <= 0.5 &&
    Math.abs((tm1 - tm0) - 3 * st.tree.cw) <= 0.5,
    '1ch=' + round(st.tree.cw) + 'px · .pre 0层 ' + round(pc0) + ' → 1层 ' + round(pc1) +
    '（Δ ' + round(pc1 - pc0) + '）· 时刻左沿 ' + round(tm0) + ' → ' + round(tm1) + '（Δ ' + round(tm1 - tm0) + '）')
  r.ok(v.file + ' · ⓪ 窗口里真有竖线 │ 与末兄弟 └─（不是「一个符号走天下」）',
    st.tree.barRows > 0 && st.tree.cornerRows > 0,
    '以 │ 开头的行 ' + st.tree.barRows + ' · 含 └ 的行 ' + st.tree.cornerRows)
  r.ok(v.file + ' · ⓪ data-d 与独立重算的层级逐行一致',
    st.tree.ddOk, st.tree.ddOk ? '逐行相同' : '有行对不上（见 tree.mismatch）')
  r.ok(v.file + ' · ⓪ 栅格第一列是逐行的 var(--pc)（缩进列不是定宽）',
    GRID_DECL.indexOf('var(--pc') >= 0,
    'CSS 声明「' + GRID_DECL.trim() + '」· ⚠️ 但**列宽仍解析成 px**（--pc 落到行上）⇒ 下面那条五列定宽照旧成立')
  if (st.tree.maxDep < 2) {
    console.log('      ⚠️ 当前数据最深 ' + st.tree.maxDep + ' 层 ⇒ 「左起第 j 组 ↔ 第 j 层祖先」' +
      '**在页面上不可达**，只有 1 层实测；2 层的正确性只能在 node 里对合成序列验算' +
      '（_work/3a3/tree-shape-check.mjs：旧写法错 2 处、新写法 0 处）。')
  }
  r.ok(v.file + ' · ⓪ 组内行上面找得到同号的组首（唯一例外：环形窗口截断的最顶一组）',
    st.orphan === 0 || (st.orphanTop === st.orphan && st.orphanId === String(st.topTr)),
    st.orphan === 0 ? '孤儿行 0 条'
      : ('孤儿行 ' + st.orphan + ' 条，全部属于最顶上的 trace ' + st.topTr +
        '（该组组首先被 200 行窗口挤出，是窗口截断不是树画错）'))

  r.ok(v.file + ' · ⓪ 点某行 ⇒ 同 trace 整组微染（且只改背景）',
    !!st.sel && st.sel.hits === st.sel.same && st.sel.hits >= 3 &&
    st.sel.bL === '0px' && st.sel.bImg === 'none' && st.sel.drawerTr === st.sel.tr,
    st.sel ? ('同号 ' + st.sel.tr + '：染 ' + st.sel.hits + '/' + st.sel.same + ' 行 · 底 ' + st.sel.bg +
      ' · ⑤ 跟着切到 ' + st.sel.drawerTr) : '没找到可点的组内行')
  r.ok(v.file + ' · ⓪ 点完页高与事件流可视高都不变',
    !!st.sel && st.sel.pageH === st.pageH && st.sel.cH === st.listC,
    st.sel ? ('页高 ' + st.pageH + '→' + st.sel.pageH + ' · 事件流可视 ' + st.listC + '→' + st.sel.cH) : '-')

  r.ok(v.file + ' · ⓪ 滚动条实测 ' + BAR_LO + '–' + BAR_HI + 'px（不写常数）',
    st.bar >= BAR_LO && st.bar <= BAR_HI, st.bar + 'px')

  r.ok(v.file + ' · ⓪ 上移流畅：单调不减、' + st.smooth.n + ' 个不同取值、停在底部、' + DUR_LO + '–' + DUR_HI + 'ms',
    st.smooth.mono && st.smooth.n >= 6 && st.smooth.last === st.smooth.maxTop &&
    st.smooth.dur >= DUR_LO && st.smooth.dur <= DUR_HI,
    st.smooth.first + '→' + st.smooth.last + '（maxTop ' + st.smooth.maxTop + '）· ' + st.smooth.n +
    ' 个取值 · 实测 ' + st.smooth.dur + 'ms')
  r.ok(v.file + ' · ⓪ 追加后停在尾部（末行贴住列表底边）', st.smooth.tailGap >= -1 && st.smooth.tailGap <= 1,
    '末行底距列表底 ' + st.smooth.tailGap + 'px')

  r.ok(v.file + ' · ⓪ 手动滚 → 打断自动跟随', st.autoAfterTop === false, 'auto=' + st.autoAfterTop)
  r.ok(v.file + ' · ⓪ 滚回底部 → 自动恢复', st.autoAfterBottom === true, 'auto=' + st.autoAfterBottom)

  r.ok(v.file + ' · ⓪ 每秒 100 条（' + BURST_BATCH + '×' + BURST_ROW + '）：同步段够快、队列排空、行数仍封顶、动画被合并',
    st.burst.fed === BURST_BATCH * BURST_ROW && st.burst.syncMs < 100 && st.burst.queued === 0 &&
    st.burst.drawn === st.burst.cap && st.burst.starts < BURST_BATCH &&
    st.burst.starts <= Math.ceil(BURST_BATCH * BURST_ROW / st.smooth.cap * 14) + 4,
    '喂 ' + st.burst.fed + ' 行 · 同步段 ' + st.burst.syncMs + 'ms · 动画起 ' + st.burst.starts +
    ' 次（批数 ' + BURST_BATCH + '）· DOM ' + st.burst.drawn + '/' + st.burst.cap + ' · 队列残留 ' + st.burst.queued)

  r.ok(v.file + ' · ⓪ ▶重放：从「第一拍有事件」处起、喂的条数 == 真帧该拍累计、喂进去的都画出来、停在底',
    st.replay.fed > 0 && st.replay.drawn === st.replay.fed && st.replay.fed === st.replay.cutAt &&
    st.replay.tick >= st.replay.head && !st.replay.animating && st.replay.top === st.replay.max,
    '前 ' + st.replay.head + ' 拍本窗 0 条 · 停在第 ' + st.replay.tick + ' 拍 · 喂了 ' +
    st.replay.fed + ' 行 = cuts[' + st.replay.tick + ']=' + st.replay.cutAt + '、在屏 ' + st.replay.drawn +
    ' 行 · 停在底 ' + st.replay.top + '/' + st.replay.max)

  /* ══ ①′ ①②④⑤ 四处改动 + 三条联动 ═════════════════════════════════════════ */
  const S = st.status
  r.ok(v.file + ' · ① 改：**两排**（读数一排 + 控件一排），错误率在第一排',
    S.two && S.r1Top < S.r2Top && S.errInR1 && S.r1H >= 30 && S.r2H >= 20,
    '① 总高 ' + S.h + ' · 第一排 ' + S.r1H + '（y' + S.r1Top + '）· 第二排 ' + S.r2H + '（y' + S.r2Top + '）')
  r.ok(v.file + ' · ① 改：错误率与 mini 三色条**相邻**（就近，中间不隔别的读数）+ 三色条 3 段',
    S.miniN === 3 && S.near >= 0 && S.near <= 30,
    '两者间距 ' + S.near + 'px · 三色条 ' + S.miniN + ' 段')

  r.ok(v.file + ' · L2 基线：未注入 ⇒ 错误率 0.0%、无 alert（本窗 warn/error 全 0 ⇒ **天然不可达**）',
    S.txt.indexOf('0.0') >= 0 && st.l2.before.alert === false && st.l2.before.txt.indexOf('0.0') >= 0,
    st.l2.before.txt)
  /* ⚠️ ② 档位 chip 的文案两版**不一样**（这正是本题的一处裁定）：甲给数字、丙不给。
     所以判据也得跟着分叉 —— 拿甲的数去量丙，只会得到一条永远红的假红。 */
  const chipOK = v.v === 'jia'
    ? st.l2.after.hexp.indexOf('警告 3') >= 0 && st.l2.after.hexp.indexOf('错误 2') >= 0
    : !/[0-9]/.test(st.l2.after.hexp) &&
      st.l2.after.hexp.indexOf('警告') >= 0 && st.l2.after.hexp.indexOf('错误') >= 0
  r.ok(v.file + ' · L2 注入 warn 3 / error 2 ⇒ 读数变 + alert 开 + ② 档位 / ① 三色条 / ④ 三条**一起变**' +
    (v.v === 'jia' ? '（甲：chip 带数字）' : '（丙：chip 不带数字）'),
    st.l2.after.alert === true && st.l2.after.txt.indexOf('0.5') >= 0 &&
    st.l2.after.txt.indexOf('3 / 2') >= 0 && chipOK && st.l2.after.bars === '1,000|3|2' &&
    st.l2.after.flex === '1000 0 auto|3 0 auto|2 0 auto',
    st.l2.after.txt + ' · ②档位chip「' + st.l2.after.hexp + '」· ④三条「' + st.l2.after.bars + '」· ①三色条 flex「' + st.l2.after.flex + '」')
  r.ok(v.file + ' · L2 注入**不改 ① 的高度**（变红不能把版面顶动）', st.l2.after.statusH === S.h,
    '① ' + S.h + ' → ' + st.l2.after.statusH)
  r.ok(v.file + ' · L2 清除注入 ⇒ 回到 0.0% 且 alert 灭（可复位，不是一次性）',
    st.l2.back.alert === false && st.l2.back.txt.indexOf('0.0') >= 0, st.l2.back.txt)

  r.ok(v.file + ' · L1 点 ④ 的严重度条 ⇒ ② chip + ① 三色条 + ③ **真过滤** + DOM 行数不变',
    st.l1.byBar.desc === '严重度=0' && st.l1.byBar.chip && st.l1.byBar.mini && st.l1.byBar.bar &&
    st.l1.byBar.shown > 0 && st.l1.byBar.shown < st.l1.byBar.dom && st.l1.byBar.dom === st.l1.byBar.drawn &&
    st.l1.byBar.rowsTxt.indexOf('筛中 ' + st.l1.byBar.shown + ' 行') >= 0,
    '筛选「' + st.l1.byBar.desc + '」· 可见 ' + st.l1.byBar.shown + ' / DOM ' + st.l1.byBar.dom +
    '（封顶 ' + st.l1.byBar.cap + '）· 计数行「' + st.l1.byBar.rowsTxt + '」')
  r.ok(v.file + ' · L1 再点同一处 ⇒ 取消（三处一起复位）',
    st.l1.off.desc === '无' && st.l1.off.shown === st.l1.byBar.dom, st.l1.off.desc + ' · 可见 ' + st.l1.off.shown)
  r.ok(v.file + ' · L1 点 ① 的三色条段 ⇒ 另一处入口同样生效（② chip + ④ 条跟着变）',
    st.l1.byMini.desc === '严重度=1' && st.l1.byMini.chip && st.l1.byMini.bar,
    st.l1.byMini.desc + ' · ② chip=' + st.l1.byMini.chip + ' · ④ 条=' + st.l1.byMini.bar +
    ' · 可见 ' + st.l1.byMini.shown + '（本窗 warn/error 0 条 ⇒ 0 是实话）')
  r.ok(v.file + ' · L1 点 ② 的严重度 chip 也能取消', st.l1.byChipOff.desc === '无', st.l1.byChipOff.desc)

  r.ok(v.file + ' · L1 类筛选：只留该类 + DOM 行数不变（筛掉的是藏起来、不是删掉）',
    st.l1.byKind.desc === '类=cache' && st.l1.byKind.allCache && st.l1.byKind.shown > 0 &&
    st.l1.byKind.dom === st.l1.byBar.dom,
    st.l1.byKind.desc + ' · 可见 ' + st.l1.byKind.shown + ' · DOM ' + st.l1.byKind.dom + '（不变）· 全为该类=' + st.l1.byKind.allCache)
  r.ok(v.file + ' · L1 ≥WARN：本窗 warn/error 0 条 ⇒ 命中 0 行（**如实显示 0**，不补数）',
    st.l1.warnUp.desc === '≥WARN 类=cache' && st.l1.warnUp.shown === 0,
    st.l1.warnUp.desc + ' · 可见 ' + st.l1.warnUp.shown + ' 行')

  r.ok(v.file + ' · L1 点行里的对象 ⇒ 对象框填上 + 只剩该对象的行',
    !!st.l1.byObj && st.l1.byObj.box === st.l1.byObj.obj && st.l1.byObj.allMatch,
    st.l1.byObj ? ('对象「' + st.l1.byObj.obj + '」· 可见 ' + st.l1.byObj.shown + ' · 全部命中=' + st.l1.byObj.allMatch) : '没找到可点对象')
  r.ok(v.file + ' · L1 只看这条 trace ⇒ 可见行数 == 同号行数（与选中态同一组）',
    !!st.l1.byTrace && st.l1.byTrace.shown === st.l1.byTrace.selN && st.l1.byTrace.allMatch,
    st.l1.byTrace ? ('trace ' + st.l1.byTrace.tr + ' · 可见 ' + st.l1.byTrace.shown +
      ' == 选中态染的 ' + st.l1.byTrace.selN + ' 行') : '-')
  r.ok(v.file + ' · L1 清筛选 ⇒ 全回来 + chip 灭 + 对象框复位',
    st.l1.cleared.desc === '无' && st.l1.cleared.shown === st.l1.cleared.dom && !st.l1.cleared.chip &&
    st.l1.cleared.objBox.indexOf('点 ③ 里的对象') >= 0,
    st.l1.cleared.desc + ' · 可见 ' + st.l1.cleared.shown + ' · chip=' + st.l1.cleared.chip + ' · 对象框「' + st.l1.cleared.objBox + '」')

  r.ok(v.file + ' · ③ 行内「复制本行」：**要么真写进剪贴板、要么把失败原因写出来**（不假装成功）',
    /^ok:|^fail:/.test(st.l1.copy.last) && st.l1.copy.btn.indexOf('复制') >= 0,
    '结果「' + st.l1.copy.last + '」· 按钮「' + st.l1.copy.btn + '」')
  r.ok(v.file + ' · ③ 行内「展开」：插一行（紧跟本行）· 页高与可视高不变 · 内部滚变长 · **锁住自动跟随**',
    st.l1.xp.n === 1 && st.l1.xp.next && st.l1.xp.h > 0 && st.l1.xp.pageSame && st.l1.xp.cSame &&
    st.l1.xp.sGrew && st.l1.xp.auto === false && st.l1.xp.dom === st.l1.byBar.dom + 1,
    '插 ' + st.l1.xp.n + ' 行（高 ' + st.l1.xp.h + '）· 紧随=' + st.l1.xp.next + ' · 页高不变=' + st.l1.xp.pageSame +
    ' · 可视不变=' + st.l1.xp.cSame + ' · 内部滚变长=' + st.l1.xp.sGrew + ' · auto=' + st.l1.xp.auto +
    ' · 行数 ' + st.l1.xp.dom + '（展开行不计入封顶的 ' + st.l1.byBar.dom + '）')
  r.ok(v.file + ' · ③ 再点展开行本身 ⇒ 收起（不会越插越多）', st.l1.xp.after === 0, '残留 ' + st.l1.xp.after + ' 行')

  r.ok(v.file + ' · ④ Top-N 三档**真切**（慢请求 / 错误 / 对象各重排一次）',
    st.tabs.slow.on && st.tabs.slow.n > 0 && st.tabs.slow.txt.indexOf('ms') >= 0 &&
    st.tabs.obj.on && st.tabs.obj.n > 0 && st.tabs.obj.txt.indexOf('条') >= 0,
    '慢请求 ' + st.tabs.slow.n + ' 行「' + st.tabs.slow.txt + '」· 对象 ' + st.tabs.obj.n + ' 行')
  r.ok(v.file + ' · ④ 错误档本窗**恒空** ⇒ 给的是空态文案（不给假数据）',
    st.tabs.err.on && st.tabs.err.empty && st.tabs.err.txt.indexOf('error 0 条') >= 0,
    st.tabs.err.txt)

  r.ok(v.file + ' · L3 悬停 ① 的某一档 ⇒ ③ 里同档的行浅底高亮；**选中态仍压得住它**',
    st.l3.hasSel && st.l3.hasInfo && st.l3.cls.indexOf('hl-i') >= 0 &&
    st.l3.infoBg !== 'rgba(0, 0, 0, 0)' && st.l3.selBg === 'rgb(230, 230, 230)' &&
    st.l3.off.cls.indexOf('hl-') < 0 && st.l3.off.infoBg === 'rgba(0, 0, 0, 0)',
    '悬停档 0 ⇒ 同档行底 ' + st.l3.infoBg + ' · 选中行底 ' + st.l3.selBg + '（**没被高亮盖掉**）· 移开后 ' + st.l3.off.infoBg)

  r.ok(v.file + ' · ⑤ 关联跳转行：两种选中态**都只有一行高**、⑤ 与页高都不变',
    !!st.jr.none && st.jr.none.dead === true && st.jr.none.h > 0 && !!st.jr.withRec &&
    st.jr.withRec.h === st.jr.none.h && st.jr.withRec.drawerH === st.jr.none.drawerH &&
    st.jr.withRec.page === st.jr.none.page && st.jr.withRec.jms >= 1,
    '无记录 ' + st.jr.none.h + 'px（dead=' + st.jr.none.dead + '）· 有记录 ' + st.jr.withRec.h +
    'px（跳转 ' + st.jr.withRec.jms + ' 个）· ⑤ 高 ' + st.jr.none.drawerH + '→' + st.jr.withRec.drawerH +
    ' · 页高 ' + st.jr.none.page + '→' + st.jr.withRec.page)

  r.ok(v.file + ' · ② 改：12 类分**两组**（业务 9 / 设施 3，中间一行组名）+ 源 **2 项** + 控件 4 行',
    st.rail.kindRows === 12 && st.rail.facKeys === 'slow_sql,mq,scheduler' && st.rail.gh === 1 &&
    st.rail.ghTxt.indexOf('设施 3 类') >= 0 && st.rail.srcChips === 2 && st.rail.ctlLines === 4,
    '类行 ' + st.rail.kindRows + ' · 组名「' + st.rail.ghTxt + '」· 源 ' + st.rail.srcLabels + ' · 控件 ' + st.rail.ctlLines + ' 行')
  r.ok(v.file + ' · ② 有「清筛选」出口 + 脚注写明只有三样真过滤',
    st.rail.foot.indexOf('清筛选') >= 0 && st.rail.sub.indexOf('三样真过滤') >= 0,
    '脚「' + st.rail.foot + '」· 注「' + st.rail.sub.slice(0, 70) + '…」')
  if (v.v === 'jia') {
    r.ok(v.file + ' · ② 甲**给数字**（13 处计数：全部 + 12 类）', st.rail.counts === 13, st.rail.counts + ' 处')
  } else {
    r.ok(v.file + ' · ② 丙**不给数字**（连计数列都不存在，不是画个「—」占位）', st.rail.counts === 0,
      st.rail.counts + ' 处 · 脚注「' + st.rail.sub.slice(0, 60) + '…」')
  }

  r.ok(v.file + ' · 复位（做完成交互回到「刚打开」那一屏）',
    st.afterReset.desc === '无' && st.afterReset.shown === st.afterReset.drawn && st.afterReset.xp === 0 &&
    st.afterReset.inj === null && st.afterReset.topTab === 'obj' && st.afterReset.auto === true &&
    st.afterReset.page === st.pageH,
    st.afterReset.desc + ' · 可见 ' + st.afterReset.shown + ' · 展开行 ' + st.afterReset.xp +
    ' · 注入 ' + st.afterReset.inj + ' · 榜档 ' + st.afterReset.topTab + ' · auto=' + st.afterReset.auto)

  /* ══ 其余判据 ════════════════════════════════════════════════════════════ */
  r.ok(v.file + ' · 数据源切换在场（正常 / 模拟日常）', st.txtHasDemo && st.src.indexOf('正常') >= 0, st.src)
  r.ok(v.file + ' · 「已放 N / 500 条」常驻', st.txtHasCap500 && st.src.indexOf('已放') >= 0,
    st.src.indexOf('已放') >= 0 ? st.src : '没找到「已放」')

  const got = st.info.map((x) => x.blk).sort().join(',')
  r.ok(v.file + ' · 区块数 = 7（六区 + 条件块）',
    v.regions.length === 6 && got === ALL_BLKS.filter((k) => got.indexOf(k) >= 0).sort().join(','), got)

  r.ok(v.file + ' · 13 处动效落点恰好 13 个、编号 1–13 各一次', st.anims.join(',') === ANIM_EXP, st.anims.join(','))

  const same = st.adj.filter((p) => {
    const A = st.info.find((x) => x.blk === p.a), B = st.info.find((x) => x.blk === p.b)
    return A.sig === B.sig
  })
  r.ok(v.file + ' · C1 相邻区块不同构（' + st.adj.length + ' 对相邻）', same.length === 0,
    same.length ? JSON.stringify(same.map((p) => p.pair)) : st.adj.map((p) => p.pair).join(' '))

  const expectW = st.clientW - st.sideW - parseFloat(st.padL) * 2
  r.ok(v.file + ' · 主区宽 == clientW − 232 − 2×pad（模型，不写常数）',
    Math.abs(st.contentW - expectW) <= 1, st.contentW + ' vs ' + expectW)

  r.ok(v.file + ' · 易漏项 1：⑤ 写明「起止只来自 traces ⇒ 模拟日常下恒空」',
    st.notes.indexOf('traces') >= 0 && st.notes.indexOf('模拟日常') >= 0,
    (st.notes.match(/[^|]*traces[^|]*/) || ['（空）'])[0].trim().slice(0, 60))
  r.ok(v.file + ' · 易漏项 2：③ 写明「被淘汰的 seq 要从 seenSeq 摘掉」',
    st.notes.indexOf('seenSeq') >= 0, (st.notes.match(/[^|]*seenSeq[^|]*/) || ['（空）'])[0].trim().slice(0, 60))
  r.ok(v.file + ' · 易漏项 3：图例写明「本页窗口内累计／不用帧里 counts」',
    st.notes.indexOf('窗口内累计') >= 0, (st.notes.match(/[^|]*窗口内累计[^|]*/) || ['（空）'])[0].trim().slice(0, 60))
  r.ok(v.file + ' · 易漏项 4：③ 写明时刻列口径（用户点名要补的那句原话）',
    st.notes.indexOf('同拍内按时刻局部排，跨拍按环序') >= 0 &&
    st.notes.indexOf('865') >= 0 && st.notes.indexOf('29 个组') >= 0,
    (st.notes.match(/[^|]*局部排[^|]*/) || ['（空）'])[0].trim().slice(0, 110))

  console.log('    ' + v.file + '  页高 ' + st.pageH + 'px（' + (st.pageH / st.innerH).toFixed(3) + ' 视口）· 客户区宽 ' +
    st.clientW + '（页面级滚动条 ' + (st.innerW - st.clientW) + 'px）· 主区 ' + st.contentW + 'px')
  console.log('      块高：' + st.info.map((x) => x.blk + ' ' + x.h + '（y' + x.y + '→' + x.bottom + '）').join(' · '))
  console.log('      ③ 事件流：可视 ' + st.listC + ' · 内部 ' + st.listS + ' · 行 ' + st.lines + ' × ' +
    Object.keys(st.heights)[0] + 'px（一屏 ' + Math.floor(st.listC / Number(Object.keys(st.heights)[0])) +
    ' 行）· 滚动条 ' + st.bar + 'px · ① 高 ' + st.status.h)
  console.log('      前导符：' + st.presKeys.map((k) => k + '×' + st.pres[k]).join(' ') + ' · 等级 ' +
    Object.keys(st.lv).map((k) => k + '×' + st.lv[k]).join(' ') + ' · trace 尾 ' + st.trTail + '/' + st.lines)
  console.log('      联动：L1 严重度=0 可见 ' + st.l1.byBar.shown + ' / 类=cache 可见 ' + st.l1.byKind.shown +
    ' · L2 0.0%→' + st.l2.after.txt + '→' + st.l2.back.txt.slice(0, 4) +
    ' · L3 同档底 ' + st.l3.infoBg + '（选中行 ' + st.l3.selBg + '）')
  console.log('      相邻对：' + st.adj.map((p) => p.pair).join(' '))

  const out = path.join(DIR, v.png)
  await b.shotFull(out)
  r.ok(v.png + ' 落盘', fs.existsSync(out) && fs.statSync(out).size > 5000,
    fs.existsSync(out) ? fs.statSync(out).size + 'B' : 'MISSING')
}

/* 两版**必须一模一样**的三区（①⑤⑥）：改一处就得两处一起改，否则两版比分不成立 */
const A = sameSet.jia, B = sameSet.bing
if (A && B) {
  const diffs = ['status', 'drawer', 'switch'].filter((k) => A[k] !== B[k])
  r.ok('甲 / 丙 的 ①⑤⑥ 文本**逐字一致**（两版只在 ②③④ 上分叉）', diffs.length === 0,
    diffs.length ? '不一致：' + diffs.join(',') : '① ⑤ ⑥ 一致')
  r.ok('甲 / 丙 在 ① 里都露出「已放 N / 500 条」', A.status.indexOf('已放') >= 0 && B.status.indexOf('已放') >= 0,
    A.status.indexOf('已放') >= 0 && B.status.indexOf('已放') >= 0 ? '两版都有' : '缺')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
