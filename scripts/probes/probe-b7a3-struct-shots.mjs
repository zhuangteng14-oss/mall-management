/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a3-struct-shots.mjs —— 3a.3（日志页结构重做）甲 / 丙两版的整页截图 + 结构断言

   跑法：node scripts/probes/probe-b7a3-struct-shots.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP）—— **不起 dev server、不起后端**：
   两个 HTML 除了两份 CSS / 一份 JS 之外是自包含的（真数据烘在 `_struct-wire-ev.js` 里）。

   ── 判据怎么量（用户 2026-10-16 第二轮裁定之后的口径）────────────────────
   〇 窗口化（这一轮的新判据，**优先级最高**）
      · **六区全部落在首个视口内**（每区 rect 的 top ≥ 0 且 bottom ≤ innerHeight）
        —— 这就是「打开预览 → 不滚 → 能看完 5 区 + 第 6 区」的可复核形式。
      · **页面高度不随数据变**：往 `.ev-list` 里**临时塞 200 张卡片**，前后比三个数 ——
        文档高**不变**、`.ev-list` 的 clientHeight **不变**、`.ev-list` 的 scrollHeight **变大**。
        这三条同时成立才叫「事件流内部滚、页面不跟着长」；只量「有没有滚动条」是不够的
        （页面自己滚起来也会有滚动条）。
      · **主区（`.px`）不被撑高**：scrollHeight == clientHeight。
      · **整页就是一屏**：标题条 + 外壳 + 页脚 = innerHeight（页脚是折叠的 `<details>`，
        在 100vh **之内**，所以它不破坏这一条）。
      · **一个大外框包住六区**：`.ev-frame` 的 DOM 子树含六区，且几何上包含六区。
   ① C1 相邻区块不同构 —— 每个顶层区块算一枚**结构指纹**（标签 + display + 主轴方向 +
      **直接子元素标签计数** + 后代标签集），相邻对（某一轴相接或间距 ≤ 4px、另一轴有重叠）
      指纹必须两两不同。
      ⚠️ 窗口化之后区块**直接相接**（间距 0，只隔一条分隔线）⇒ 相邻对变多，这条比上一轮更严。
   ② 8 处动效落点 —— 数 `[data-anim]` 的角标，要求**恰好 8 个且编号 1–8 各一次**。
      落点在 ≠ 有数据可演：5（error 闪红）本窗 error 0 条，页面上如实标了。
   ③ 各区宽度实测 —— 与「主区可用宽」对账。主区宽**不写常数**：
      `clientWidth − 侧栏 232 − 主区左右 padding×2`（滚动条宽随 headless 模式变；这一轮
      整页不再滚 ⇒ 页面级滚动条可能**不存在**，宽度账因此与上一轮不同，这正是不写常数的原因）。
   ④ 三样易漏项 —— 按**文本**查在不在：⑤ 的「起止只来自 traces ⇒ 模拟日常下恒空」、
      ③ 的「被淘汰的 seq 要从 seenSeq 摘掉」、图例的「本页窗口内累计、不是帧里 counts」。
      外加硬约束 5：「数据源（正常 / 模拟日常）」两版都在，且「已放 N / 500 条」**常驻**。

   ⚠️ **本探针上一轮跑过的 C3（区块间距 > 区块内间距）已撤**：窗口化之后区与区之间是
    **一条 1px 分隔线**、不是空白带，「间距」这个量在窗口形态里没有意义 —— 分组由**外框**
   表达，不由留白表达。撤掉而不是改数，理由写在交付报告里。
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

const VIEW_W = 1440
const VIEW_H = 900
const INJECT_N = 200       /* 临时塞进事件流的卡片数（证伪「事件越多页面越长」） */

const r = report('3a3 日志页结构草图（甲 / 丙 · 窗口化后）')
console.log('=== 3a.3 日志页结构草图：窗口化 + 整页截图 + 结构断言 ===')
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
       只用「标签集」时两个不同区块会双双算成同一枚指纹而被判同构 —— 那是**指纹太弱**。 */
    var cnt = {}, kids = [].slice.call(e.children)
    for (var i = 0; i < kids.length; i++) {
      var t = kids[i].tagName.toLowerCase(); cnt[t] = (cnt[t] || 0) + 1
    }
    var childSig = Object.keys(cnt).sort().map(function (t) { return t + ':' + cnt[t] }).join(',')
    return e.tagName.toLowerCase() + '|' + cs.display + '|' + axis + '|' + childSig + '|' + tags(e).join(',')
  }

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

  /* ── 〇 注入实验：事件流里**再塞 200 张卡**，看页高动不动 ──────────────
     这是「事件越多页面越长」这条病的**直接证伪**：文档高与列表可视高必须**一模一样**，
     而列表的 scrollHeight 必须**变大**（说明它自己扛住了）。量完立刻清理。 */
  var list = document.querySelector('.ev-list')
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

  var txt = document.body.innerText || ''
  var notes = [].slice.call(document.querySelectorAll('.ev-h2 .n, .ev-rail .sub, .ev-drawer .note, .ev-cond, .band .ttl, .ev-switch .n'))
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
    cards: document.querySelectorAll('.ev-card').length,
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

  /* ══ 〇 窗口化（本轮的核心判据）══════════════════════════════════════════ */
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
  r.ok(v.file + ' · 〇 塞 ' + st.inject.n + ' 张卡：页高不变、列表可视高不变、内部滚变长',
    st.inject.pageSame && st.inject.listSame && st.inject.grew,
    '页高 ' + st.inject.before.page + '→' + st.inject.after.page + '（' + (st.inject.pageSame ? '不变' : '**变了**') + '）· ' +
    '列表可视 ' + st.inject.before.listC + '→' + st.inject.after.listC + '（' + (st.inject.listSame ? '不变' : '**变了**') + '）· ' +
    '列表内部 ' + st.inject.before.listS + '→' + st.inject.after.listS + '（' + (st.inject.grew ? '变长' : '**没变长**') + '）· ' +
    '清理后回到 ' + st.inject.clean.page + '/' + st.inject.clean.listS)

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
  console.log('      事件流：可视 ' + st.listC + ' · 内部 ' + st.listS + ' · 卡片 ' + st.cards + ' 张 · 注入 ' +
    st.inject.n + ' 张后 ' + st.inject.before.listS + '→' + st.inject.after.listS)
  console.log('      相邻对：' + st.adj.map((p) => p.pair).join(' '))

  const out = path.join(DIR, v.png)
  await b.shotFull(out)
  r.ok(v.png + ' 落盘', fs.existsSync(out) && fs.statSync(out).size > 5000,
    fs.existsSync(out) ? fs.statSync(out).size + 'B' : 'MISSING')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
