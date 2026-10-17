/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a2b-tastecheck.mjs —— 用 taste-skill 的**结构子集**量 S1 / S3

   跑法：node scripts/probes/probe-b7a2b-tastecheck.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP）。三个 HTML 自包含，走 file:// 即可。

   ⚠️ 范围：只取 taste-skill（v2 "design-taste-frontend"）里**能在线框稿上判**的那几条。
      它的 house rules 里「字体 / 颜色 / 装饰 / 图片 / 动效」一整块**故意不适用** ——
      理由三条，都不是我的口味：
        ① SKILL.md L3 明写 "Not for dashboards or app ends."（指标面板就是 dashboard）
        ② 它量的是**成品视觉**，而本轮线框稿的 1px 灰框是**故意的**，量它就是量错对象
        ③ 它的禁用表（no 1px outlined boxes / no monospace / no tiny uppercase
           letter-spaced labels / no index numbering）会把现有设计语言整体推翻 ——
           撞硬约束 4「不引第二套设计语言」

   取用的原文（逐条标了出处行号）：
     C1  L191  step 6.3  "Neighbouring sections must not share a layout."
     C2  L345  Space     "Each section has a different composition from its neighbours;
                          none is a card grid."
     C3  L343  Space     "The gap between sections is clearly larger than any gap inside one:
                          120–220px on desktop."
     C4  L342  Space     "One grid, one pair of tight margins (16–32px), used everywhere…
                          Text blocks share two or three left edges on the whole page."
     C5  L304  House     "No icon-topped feature cards, no rows of identical cards."
     C6  L226  step 6.6  "…the inspector fails a page with two or more near-empty screens."
     C7  L192  step 6.3  "…the commonest failure in the study… is a distinctive first
                          screen followed by stock blocks."

   ⚠️ C5 必须分两层判，否则会出一堆**假命中**：
       · **区块级重复** = 页面上不同的「区块」长成一个样 ← 这才是 C5 要抓的
       · **数据行重复** = 一个图/表内部的 N 行结构相同 ← **合法**，L311-312 明确豁免
         「real tables and form fields (they may have lines)」。防护命中 8 条横条就是这一类。
       本探针两层都报，但只对**区块级**下判。

   ⚠️ 本轮**不出「分数」**：它的评分 rubric 在 `references/critique.md`，本机没有该文件。
      编一个 0–10 的分是假精确。这里出的是一份**逐条命中台账**（哪条撞了、证据是多少）。
   ══════════════════════════════════════════════════════════════════════════════ */
import path from 'node:path'
import fs from 'node:fs'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const DIR = path.join(ROOT, 'docs', '美化', 'c3-admin-preview')
const { open, report } = await import(pathToFileURL(path.join(ROOT, 'docs', '美化', '_cdp.mjs')).href)

const VARIANTS = [
  { file: 'S1-时间栈实体栏.html', version: 'S1' },
  { file: 'S3-三问句泳道.html', version: 'S3' }
]

const r = report('3a2b tastecheck（结构子集）')
const b = await open({ w: 1440, h: 900, headed: false })

for (const v of VARIANTS) {
  const html = path.join(DIR, v.file)
  if (!fs.existsSync(html)) { r.ok(v.version + ' 存在', false, 'NOT FOUND'); continue }
  await b.goto(pathToFileURL(html).href, 1200)

  const st = JSON.parse(await b.evalJS(`(function () {
    var px = document.querySelector('.px')

    /* 结构签名：标签 + 排序后的 class 列表（不看文字，只看骨架）*/
    function sig(el) {
      var c = (el.getAttribute('class') || '').trim().split(/\\s+/).filter(Boolean).sort().join('.')
      return el.tagName.toLowerCase() + (c ? '.' + c : '')
    }
    /* 行内元素 = 文本标记，不是布局盒子。左线普查与「重复卡」普查都要先把它们剔掉，
       否则段落里的 <b>/<code> 会被当成「一排相同的卡」，而它们的 left 本来就该是任意的。 */
    function isInline(el) { return getComputedStyle(el).display === 'inline' }
    /* 布局骨架：display + 列定义 + 主轴方向 */
    function skel(el) {
      var cs = getComputedStyle(el)
      return cs.display + ' | ' + cs.gridTemplateColumns + ' | ' + cs.flexDirection
    }
    /* 构图签名：骨架 + 直接**布局**子元素的签名序列。
       ⚠️ 只比较有布局子元素的块 —— 一条纯通知栏（内部全是行内文字）没有「构图」可言，
       拿它去和另一条通知栏比「是否共用布局」是拿错尺子。 */
    function comp(el) {
      var kids = [].slice.call(el.children).filter(function (e) { return e.nodeType === 1 && !isInline(e) })
      if (!kids.length) return ''
      return skel(el) + ' :: ' + kids.map(sig).join(' > ')
    }

    /* ⚠️ 交付说明块（.foot-note）不算设计的一部分，不能参与任何结构判定 */
    var blocks = [].slice.call(px.children).filter(function (e) {
      return e.nodeType === 1 && !/(^|\\s)foot-note(\\s|$)/.test(e.getAttribute('class') || '')
    })
    /* **内容盒**：元素所有后代盒子的并集区间（不带自己的 padding/border）。
       ⚠️ C3 必须量「内容到内容」，不能量「盒子到盒子」：.lane 有 padding:14px 0 + 1px 边线，
       盒子紧贴 ⇒ 盒子间距 0，而眼睛看到的间隔其实是 14+1+14 = 29px。第二版就在这里量反了。
       （注意：本段在模板字符串里，注释中不能出现反引号或美元花括号。） */
    function contentBox(el) {
      var t = Infinity, bt = -Infinity, any = false
      var d = el.querySelectorAll('*')
      for (var i = 0; i < d.length; i++) {
        /* ⚠️ 不钻进 SVG 内部：折线的图形元素画在图的中下部，算它会把「图的内容」
           当成从 70px 才开始（实测出过 52px 的假内部间距）。图表的「内容」就是它的框。
           ownerSVGElement 非空 ⇒ 该元素在某个 <svg> 里面。 */
        if (d[i].ownerSVGElement) continue
        var rc = d[i].getBoundingClientRect()
        if (rc.height < 1 || rc.width < 1) continue
        var tp = rc.top + window.scrollY, bo = tp + rc.height
        if (tp < t) t = tp
        if (bo > bt) bt = bo
        any = true
      }
      if (!any) {
        var rr = el.getBoundingClientRect()
        t = rr.top + window.scrollY
        bt = t + rr.height
      }
      return [Math.round(t), Math.round(bt)]
    }

    /* 最大的**纵向**内部间距：递归走遍块内所有容器，只算「上一个孩子内容结束后、
       下一个孩子内容才开始」的那种缝（左右并排不算缝）。 */
    /* 返回**前 5 名**并带出处 —— 台账里每个数都得说得出是哪儿来的 */
    function innerGapsOf(root) {
      var found = []
      var containers = [root].concat([].slice.call(root.querySelectorAll('*')))
      containers.forEach(function (c) {
        var kids = [].slice.call(c.children).filter(function (e) { return e.nodeType === 1 && !isInline(e) })
        if (kids.length < 2) return
        var lastB = null, lastK = null
        kids.forEach(function (k) {
          var cb = contentBox(k)
          if (lastB !== null && cb[0] >= lastB) found.push({ g: cb[0] - lastB, at: sig(c), a: lastK, b: sig(k) })
          if (lastB === null || cb[1] > lastB) { lastB = cb[1] }
          lastK = sig(k)
        })
      })
      found.sort(function (x, y) { return y.g - x.g })
      return found.slice(0, 5)
    }

    var list = [], prevBottom = null
    blocks.forEach(function (blk) {
      var cb = contentBox(blk)
      list.push({
        sig: sig(blk), skel: skel(blk), comp: comp(blk),
        top: cb[0], h: cb[1] - cb[0],
        gapBefore: prevBottom === null ? null : cb[0] - prevBottom,
        inner: innerGapsOf(blk)
      })
      prevBottom = cb[1]
    })

    /* 数据容器白名单：这些容器里的重复是「一个图/表内部的行」，L311-312 明确豁免 */
    var EXEMPT = { svg: 1, stack: 1, 'heat-labels': 1 }
    /* 重复兄弟普查：容器直接**布局**子元素里，结构签名相同且 ≥3 个的组 */
    var dup = []
    var all = px.querySelectorAll('*')
    for (var i = 0; i < all.length; i++) {
      var kd = [].slice.call(all[i].children).filter(function (e) { return e.nodeType === 1 && !isInline(e) })
      if (kd.length < 3) continue
      var cnt = {}
      kd.forEach(function (k) { var s = sig(k); cnt[s] = (cnt[s] || 0) + 1 })
      for (var s2 in cnt) if (cnt[s2] >= 3) {
        var cls = (all[i].getAttribute('class') || '').split(/\\s+/)
        var exempt = all[i].tagName.toLowerCase() === 'svg'
        cls.forEach(function (c) { if (EXEMPT[c]) exempt = true })
        /* 「卡」必须是**会组合东西的盒子**：自己的布局子元素 ≥1。
           否则一条小标题（.sec-h，内部只有行内 span）重复三次也会被当成一排卡 —— 那是标签，不是卡。 */
        var sample = null
        for (var t = 0; t < kd.length; t++) if (sig(kd[t]) === s2) { sample = kd[t]; break }
        var composes = sample ? [].slice.call(sample.children).filter(function (e) {
          return e.nodeType === 1 && !isInline(e)
        }).length : 0
        dup.push({ at: sig(all[i]), n: cnt[s2], child: s2, exempt: exempt, composes: composes })
      }
    }

    /* 等列网格普查（C2）：把解析后的列宽列出来，全等且 ≥3 列才算「卡片网格」 */
    var eqGrid = []
    var cand = px.querySelectorAll('*')
    for (var q = 0; q < cand.length; q++) {
      var gcs = getComputedStyle(cand[q])
      if (gcs.display !== 'grid') continue
      var cols = gcs.gridTemplateColumns.split(/\\s+/).filter(Boolean)
      if (cols.length < 3) continue
      var pxv = cols.map(parseFloat)
      if (pxv.some(isNaN)) continue
      var mx = Math.max.apply(null, pxv), mn = Math.min.apply(null, pxv)
      if (mx - mn > 1) continue
      var gk = [].slice.call(cand[q].children).filter(function (e) { return e.nodeType === 1 && !isInline(e) })
      var gcnt = {}
      gk.forEach(function (k) { var s = sig(k); gcnt[s] = (gcnt[s] || 0) + 1 })
      var top = 0
      for (var s3 in gcnt) if (gcnt[s3] > top) top = gcnt[s3]
      if (top >= 3) eqGrid.push({ at: sig(cand[q]), cols: cols.length, sameKids: top })
    }

    /* 文本左边线普查（C4）：只数**块级**文字容器 —— 行内元素在一行中间，left 本就任意 */
    var lefts = {}
    var leaves = px.querySelectorAll('*')
    for (var j = 0; j < leaves.length; j++) {
      var e = leaves[j]
      if (e.children.length) continue
      if (!(e.textContent || '').trim()) continue
      var dsp = getComputedStyle(e).display
      if (dsp === 'inline' || dsp === 'inline-block') continue
      var lr = e.getBoundingClientRect()
      if (lr.width < 1) continue
      lefts[Math.round(lr.left)] = (lefts[Math.round(lr.left)] || 0) + 1
    }

    /* 空白带：把 .px 内所有元素盒子合成区间，找最大的未覆盖竖缝（C6）*/
    var iv = []
    var boxes = px.querySelectorAll('*')
    for (var m = 0; m < boxes.length; m++) {
      var br = boxes[m].getBoundingClientRect()
      if (br.height < 2) continue
      iv.push([Math.round(br.top + window.scrollY), Math.round(br.bottom + window.scrollY)])
    }
    iv.sort(function (a, c) { return a[0] - c[0] })
    var pxTop = Math.round(px.getBoundingClientRect().top + window.scrollY)
    var cur = pxTop, maxBlank = 0, blankAt = 0
    for (var n2 = 0; n2 < iv.length; n2++) {
      if (iv[n2][0] > cur) { if (iv[n2][0] - cur > maxBlank) { maxBlank = iv[n2][0] - cur; blankAt = cur } }
      if (iv[n2][1] > cur) cur = iv[n2][1]
    }

    return JSON.stringify({
      pxW: Math.round(px.getBoundingClientRect().width),
      blocks: list,
      dup: dup,
      eqGrid: eqGrid,
      lefts: lefts,
      maxBlank: maxBlank, blankAt: blankAt
    })
  })()`))

  console.log('\n=== ' + v.version + ' · 页宽 ' + st.pxW + 'px · 区块 ' + st.blocks.length + ' 个（已剔除 .foot-note）===')
  st.blocks.forEach(function (bk, i) {
    console.log('  [' + i + '] ' + bk.sig + '  h=' + bk.h +
      '  gap前=' + (bk.gapBefore === null ? '—' : bk.gapBefore) +
      '  内距Top' + (bk.inner.length ? bk.inner[0].g : 0))
    console.log('        构图: ' + (bk.comp || '（无布局子元素 → 非构图块，不参与 C1）'))
    bk.inner.forEach(function (g) {
      console.log('        内距 ' + g.g + 'px: ' + g.at + ' 里 ' + g.a + ' → ' + g.b)
    })
  })
  console.log('  等列网格(C2): ' + (st.eqGrid.length ? JSON.stringify(st.eqGrid) : '无'))
  console.log('  重复兄弟组(C5):')
  st.dup.forEach(function (d) {
    var tag = d.exempt ? '[豁免·数据行]' : (d.composes ? '[候选·卡]  ' : '[非卡·标签]')
    console.log('    ' + tag + ' ' + d.at + ' > ' + d.n + ' × ' + d.child + '  (自身布局子元素 ' + d.composes + ')')
  })
  console.log('  文本左边线(C4): ' + JSON.stringify(st.lefts))
  console.log('  最大空白带(C6): ' + st.maxBlank + 'px @y=' + st.blankAt)

  /* ── C1 相邻区块不得共用布局 ───────────────────────────────────────────── */
  var composed = st.blocks.filter(function (x) { return x.comp })
  var sameComp = []
  for (var i = 1; i < composed.length; i++) {
    if (composed[i].comp === composed[i - 1].comp) {
      sameComp.push(composed[i - 1].sig + ' ≈ ' + composed[i].sig)
    }
  }
  r.ok(v.version + ' C1 相邻区块不共用构图', sameComp.length === 0,
    sameComp.join(' / ') || ('参与比较的构图块 ' + composed.length + ' 个，无相邻同构'))

  /* ── C2 无「等列卡片网格」 ─────────────────────────────────────────────── */
  r.ok(v.version + ' C2 无「≥3 等列 + ≥3 同构子元素」的网格', st.eqGrid.length === 0,
    st.eqGrid.length ? JSON.stringify(st.eqGrid) : '本页无等列网格')

  /* ── C3 区块间距 > 区块内间距（**只判相对**，120–220px 属营销页口径，不适用）── */
  var gaps = st.blocks.map(function (x) { return x.gapBefore }).filter(function (x) { return x !== null })
  var inner = st.blocks.map(function (x) { return x.inner.length ? x.inner[0].g : 0 })
  var minGap = Math.min.apply(null, gaps), maxInner = Math.max.apply(null, inner)
  r.ok(v.version + ' C3 区块间距 > 区块内间距', minGap > maxInner,
    '最小区块间距 ' + minGap + ' vs 最大内部间距 ' + maxInner)

  /* ── C4 文本左边线：**不出 PASS/FAIL** ────────────────────────────────── */
  /* 原规则是给单栏营销页写的（「text blocks share two or three left edges on the whole
     page」）。本页是两栏管理端：左列一条主边线、右栏一条、右栏内部还有一条，外加右对齐的
     数字格 —— 天然 ≥3 条。量出来的还有绝对定位的图上小签、栏内说明等。硬判会让它必红，
     调个门限让它变绿则是「凑门限」。故只出分布，标不适用。 */
  var edges = Object.keys(st.lefts).map(function (k) { return { x: Number(k), n: st.lefts[k] } })
  var real = edges.filter(function (e) { return e.n >= 3 }).sort(function (a, c) { return a.x - c.x })
  console.log('     C4 不适用（单栏营销页规则，本页是两栏管理端）。承载 ≥3 个文本的边线 ' +
    real.length + ' 条: ' + real.map(function (e) { return e.x + '(' + e.n + ')'; }).join(' , ') +
    ' ；另有 ' + (edges.length - real.length) + ' 条单例边线（图上的绝对定位小签 / 右对齐数字格）')

  /* ── C5 无「一排完全相同的卡」（数据行与标签都不算）───────────────────── */
  var cardRows = st.dup.filter(function (d) { return !d.exempt && d.composes })
  r.ok(v.version + ' C5 无「一排完全相同的卡」', cardRows.length === 0,
    cardRows.length ? cardRows.map(function (d) { return d.at + ' > ' + d.n + '×' + d.child }).join(' / ')
      : '仅剩数据行 / 标签级重复')

  /* ── C6 无近空屏 ──────────────────────────────────────────────────────── */
  r.ok(v.version + ' C6 无近空屏（最大空白带 < 300px）', st.maxBlank < 300, st.maxBlank + 'px')

  /* ── C7 不是「独特首屏 + 之后回到同一构图」────────────────────────────── */
  if (composed.length >= 2) {
    var first = composed[0], rest = composed.slice(1)
    var back = rest.filter(function (x) { return x.comp === first.comp }).length
    r.ok(v.version + ' C7 首块之后未回到同一构图', back === 0,
      '首块 ' + first.sig + ' 之后同构图 ' + back + ' 个')
  } else {
    console.log('     C7 跳过：构图块不足 2 个')
  }
}

await b.close()
process.exit(r.dump() ? 1 : 0)
