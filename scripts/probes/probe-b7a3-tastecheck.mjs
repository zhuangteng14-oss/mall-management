/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7a3-tastecheck.mjs —— 用 taste-skill 的**结构子集**量 3a.3 的两版日志页
                              （甲 · 三栏驾驶舱 / 丙 · 上下分区）

   跑法：node scripts/probes/probe-b7a3-tastecheck.mjs
   底座：`docs/美化/_cdp.mjs`（headless Edge + CDP，1440×900，file://，不起 server）

   ── 与 3a2b 那份（`probe-b7a2b-tastecheck.mjs`）的关系 ──────────────────────
   判据同源、**对象不同**，所以不是它的复制品。直接拿 2b 那份跑本页会得到一份**假台账**
   （实测过：区块数报 1、C3 报「Infinity vs 2591」还判 OK），原因三条，逐条对应本版的改法：

     ① **区块模型不同**：2b 的 `.px` 是一串 `.sec`，所以「区块 = `.px` 的孩子」成立；
        本页 `.px` 只有一个 `<section class="ev-*">`，六区是它的后代 ⇒ 2b 那份量到**区块 1 个**，
        C1（相邻不同构）/ C3 / C7 三条**全空过**（空过还报 OK 是最坏的一种绿）。
        本版：区块 = `[data-blk]` 六区，相邻关系**由几何算**（横竖重叠 > 1px 才算相邻）。
     ② **绝对定位的孩子会造出假间距**：2b 那份的 innerGaps 把行内浮层 `.acts`
        （`position:absolute`、贴在行尾）当成正常流里的孩子 ⇒ 量出 **2591px** 的「内部间距」
        （其实是「消息格内容 → 贴着行尾的浮层」）。本版：**绝对定位 / 固定定位的孩子不进流**，
        不参与构图、间距、重复普查。
     ③ **C3 在本版**没有对应物（六区之间是 1px 分隔线、不是空白带）——
        和 `probe-b7a3-struct-shots.mjs` 里撤掉 C3 是同一个理由。本版**报「不适用」**，
        不出一枚永真或永假的绿/红。C4（单栏营销页的左线数）同理。

   取用的原文（出处行号与 2b 台账一致，逐条可核）：
     C1  L191  step 6.3  "Neighbouring sections must not share a layout."
     C2  L345  Space     "…none is a card grid."
     C5  L304  House     "No icon-topped feature cards, no rows of identical cards."
     C6  L226  step 6.6  "…the inspector fails a page with two or more near-empty screens."
     C7  L192  step 6.3  "…the commonest failure… is a distinctive first screen followed by stock blocks."
     C3 / C4 —— 本版**不适用**，理由见上。

   ⚠️ **C5 只报候选、不下判**（照 2b 的口径）：区块级重复 = 假；**一个图表/表内部的行** = 合法
      （L311-312 豁免 "real tables and form fields"）。本版只分「卡·候审 / 非卡·标签」
      （后者 = 自身不组合东西，一条小标题重复三次不是卡），并把「容器在数据白名单里」
      当作**提示**印出来 —— **真命中与否由台账逐条裁决**。
      ⚠️ 这一版**故意不给「数据容器」自动豁免**：豁免名单等于把「什么算表」写死在探针里，
      把图表容器加进名单就能把 C5 刷成全绿 —— 那正是「凑门限」。

   ⚠️ 本轮**不出「分数」**（理由同 2b：它的 rubric 在 `references/critique.md`，本机没有该文件）。
   ══════════════════════════════════════════════════════════════════════════════ */
import path from 'node:path'
import fs from 'node:fs'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const DIR = path.join(ROOT, 'docs', '美化', 'c3-admin-preview')
const { open, report } = await import(pathToFileURL(path.join(ROOT, 'docs', '美化', '_cdp.mjs')).href)

const VARIANTS = [
  { file: '3a3-甲-三栏驾驶舱.html', version: '甲' },
  { file: '3a3-丙-上下分区.html', version: '丙' },
]

const r = report('3a3 tastecheck（结构子集）')
const b = await open({ w: 1440, h: 900, headed: false })

for (const v of VARIANTS) {
  const html = path.join(DIR, v.file)
  if (!fs.existsSync(html)) { r.ok(v.version + ' 存在', false, 'NOT FOUND'); continue }
  await b.goto(pathToFileURL(html).href, 1200)

  const st = JSON.parse(await b.evalJS(`(function () {
    var frame = document.querySelector('.ev-frame')
    function vis(e) { var c = e.getBoundingClientRect(); return c.width > 1 && c.height > 1 }
    function sig(el) {
      var c = (el.getAttribute('class') || '').trim().split(/\\s+/).filter(Boolean).sort().join('.')
      return el.tagName.toLowerCase() + (c ? '.' + c : '')
    }
    function isInline(el) { return getComputedStyle(el).display === 'inline' }
    /* ⚠️ **不进流的孩子不参与构图 / 间距 / 重复普查**：inline、display:none、absolute/fixed。
       实测（_work/3a3/gap-attrib.mjs）：不排除时最大「内距」报 2591px（甲）/ 2592px（丙），
       落点是 .log-line > span.pre → span.acts。**机制不是「浮层贴在哪算到哪」**——
       .acts 首屏是 display:none ⇒ 0×0 退化盒（top=0）；而事件流面板内部滚动过，
       滚出视口的行算出来是**负数**（rect.top + scrollY 只算窗口滚动、不算面板内滚动）
       ⇒ 两个退化值相减。所以这里两条都排除，不是只排绝对定位。
       ⚠️ 本段在 **MEASURE 模板字符串内**：注释里出现反引号、或美元加大括号，
          都会把模板提前闭合 / 开插值（本次连踩三次，报错行都指向注释行）。
          改完先 node --check 再跑。 */
    function isLayout(el) {
      if (isInline(el)) return false
      var cs = getComputedStyle(el)
      if (cs.display === 'none') return false
      return cs.position !== 'absolute' && cs.position !== 'fixed'
    }
    function skel(el) {
      var cs = getComputedStyle(el)
      return cs.display + ' | ' + cs.gridTemplateColumns + ' | ' + cs.flexDirection
    }
    function comp(el) {
      var kids = [].slice.call(el.children).filter(function (e) { return e.nodeType === 1 && isLayout(e) })
      if (!kids.length) return ''
      return skel(el) + ' :: ' + kids.map(sig).join(' > ')
    }
    function box(e) { var c = e.getBoundingClientRect(); return [Math.round(c.left), Math.round(c.top), Math.round(c.right), Math.round(c.bottom)] }

    /* 区块 = 六区（[data-blk]），不是 .px 的孩子 —— 见文件头 ① */
    var blocks = [].slice.call(document.querySelectorAll('[data-blk]')).filter(vis).map(function (e) {
      return { blk: e.getAttribute('data-blk'), el: e, box: box(e), comp: comp(e), skel: skel(e) }
    })

    /* C3 撤掉，但「区块内最大纵向间距」仍然是个有用的读数（台账拿它说明窗口形态里无此量）*/
    function contentBox(el) {
      var t = Infinity, bo = -Infinity, any = false
      var d = el.querySelectorAll('*')
      for (var i = 0; i < d.length; i++) {
        if (d[i].ownerSVGElement) continue
        if (!isLayout(d[i])) continue
        var rc = d[i].getBoundingClientRect()
        if (rc.height < 1 || rc.width < 1) continue
        var tp = rc.top + window.scrollY
        if (tp < t) t = tp
        if (tp + rc.height > bo) bo = tp + rc.height
        any = true
      }
      if (!any) { var rr = el.getBoundingClientRect(); t = rr.top + window.scrollY; bo = t + rr.height }
      return [Math.round(t), Math.round(bo)]
    }
    function innerGapTop(root, n) {
      var found = []
      var cs = [root].concat([].slice.call(root.querySelectorAll('*')))
      cs.forEach(function (c) {
        var kids = [].slice.call(c.children).filter(function (e) { return e.nodeType === 1 && isLayout(e) })
        if (kids.length < 2) return
        var lastB = null
        kids.forEach(function (k) {
          var cb = contentBox(k)
          if (lastB !== null && cb[0] >= lastB) found.push({ g: cb[0] - lastB, at: sig(c) })
          if (lastB === null || cb[1] > lastB) lastB = cb[1]
        })
      })
      found.sort(function (x, y) { return y.g - x.g })
      return found.slice(0, n || 3)
    }
    blocks.forEach(function (bk) { bk.inner = innerGapTop(bk.el, 3); bk.h = bk.box[3] - bk.box[1] })

    /* 相邻 = **共一条边**：一轴上投影 > 1px、另一轴**间隔 ≤ 2px**（贴着，只隔一条 1px 分隔线）。
       ⚠️ 两处都踩过坑，写下来：
         · 只判「某轴有重叠」⇒ 对角也会中（status 与 drawer 在 x 上重叠、y 上隔着 700px），
           7 个区块得到 C(7,2) = 21 对 = 没筛；
         · 只判「另一轴不重叠」⇒ 上面那对还是中（ovY 是负数，照样 ≤ 0）。
       要的是**间隔**（gap），不是「有没有重叠」。 */
    var adj = []
    function gapV(A, B) { return Math.max(A.box[1] - B.box[3], B.box[1] - A.box[3]) }
    function gapH(A, B) { return Math.max(A.box[0] - B.box[2], B.box[0] - A.box[2]) }
    for (var i2 = 0; i2 < blocks.length; i2++) {
      for (var j2 = i2 + 1; j2 < blocks.length; j2++) {
        var A = blocks[i2], B = blocks[j2]
        var ovX = Math.min(A.box[2], B.box[2]) - Math.max(A.box[0], B.box[0])
        var ovY = Math.min(A.box[3], B.box[3]) - Math.max(A.box[1], B.box[1])
        var gv = gapV(A, B), gh = gapH(A, B)
        var near = (ovX > 1 && gv >= -1 && gv <= 2) || (ovY > 1 && gh >= -1 && gh <= 2)
        if (near) adj.push({ a: A.blk, b: B.blk, same: A.comp === B.comp, gv: gv, gh: gh,
          ca: A.comp, cb: B.comp })
      }
    }

    /* 数据容器白名单：这些容器里的「重复」是**一个图/表内部的行**，L311-312 豁免 */
    var DATAISH = { 'ev-list': 1, 'ev-bars': 1, 'lst': 1, 'rows': 1, 'ev-top': 1, 'tr1': 1,
      'ev-read': 1, 'r1': 1, 'r2': 1, 'ctl': 1, 'band': 1 }
    function dataish(el) {
      var cls = (el.getAttribute('class') || '').split(/\\s+/)
      var hit = el.tagName.toLowerCase() === 'svg'
      cls.forEach(function (c) { if (DATAISH[c]) hit = true })
      return hit
    }
    /* C5 候选：容器直接**布局**子元素里，结构签名相同且 ≥3 个的组
       ⚠️ 普查范围**含区块元素自身**（不只是后代）：丙 的 ④ 就是区块本身（data-blk="band"），
          只扫后代 ⇒ 「一行四张同构格」在丙里不进表、在甲里（④ 是后代）进表，
          同一个命题两版答案不同 —— 那种洞正好能藏住一个真命中。 */
    var dup = []
    var all = []
    blocks.forEach(function (bk) {
      all = all.concat([bk.el]).concat([].slice.call(bk.el.querySelectorAll('*')))
    })
    for (var i3 = 0; i3 < all.length; i3++) {
      var kd = [].slice.call(all[i3].children).filter(function (e) { return e.nodeType === 1 && isLayout(e) })
      if (kd.length < 3) continue
      var cnt = {}
      kd.forEach(function (k) { var s = sig(k); cnt[s] = (cnt[s] || 0) + 1 })
      for (var s2 in cnt) if (cnt[s2] >= 3) {
        var sample = null
        for (var t = 0; t < kd.length; t++) if (sig(kd[t]) === s2) { sample = kd[t]; break }
        var composes = [].slice.call(sample.children).filter(function (e) { return e.nodeType === 1 && isLayout(e) }).length
        /* ⚠️ 只分「卡 / 非卡·标签」两类 —— **不给「数据容器」自动豁免**：
           白名单等于把「什么算表」写死在探针里，红线是**换一个容器就换一个答案**，
           那样把图表容器加进名单就能把 C5 刷成全绿。dataish 只作为**提示**打印出来，
           真命中与否由台账逐条裁决（列表容器、图表容器各给理由），与 2b 那份口径一致。 */
        var kind = composes === 0 ? '非卡·标签' : '卡·候审'
        dup.push({ at: sig(all[i3]), n: cnt[s2], child: s2, kind: kind, dataish: dataish(all[i3]), composes: composes })
      }
    }

    /* C2 等列网格：解析后的列宽全等（±1px）且 ≥3 列 */
    var eqGrid = []
    for (var q = 0; q < all.length; q++) {
      var gcs = getComputedStyle(all[q])
      if (gcs.display !== 'grid') continue
      var cols = gcs.gridTemplateColumns.split(/\\s+/).filter(Boolean)
      if (cols.length < 3) continue
      var pxv = cols.map(parseFloat)
      if (pxv.some(isNaN)) continue
      if (Math.max.apply(null, pxv) - Math.min.apply(null, pxv) > 1) continue
      var gk = [].slice.call(all[q].children).filter(function (e) { return e.nodeType === 1 && isLayout(e) })
      var gcnt = {}
      gk.forEach(function (k) { var s = sig(k); gcnt[s] = (gcnt[s] || 0) + 1 })
      var top = 0
      for (var s3 in gcnt) if (gcnt[s3] > top) top = gcnt[s3]
      if (top >= 3) eqGrid.push({ at: sig(all[q]), cols: cols.length, sameKids: top })
    }

    /* C6 近空屏：外框内最大的未覆盖竖缝 */
    var iv = []
    var fb = frame.querySelectorAll('*')
    for (var m = 0; m < fb.length; m++) {
      if (fb[m].ownerSVGElement) continue
      if (!isLayout(fb[m])) continue
      var br = fb[m].getBoundingClientRect()
      if (br.height < 2) continue
      iv.push([Math.round(br.top + window.scrollY), Math.round(br.bottom + window.scrollY)])
    }
    iv.sort(function (a, c) { return a[0] - c[0] })
    var cur = Math.round(frame.getBoundingClientRect().top + window.scrollY), maxBlank = 0
    for (var n2 = 0; n2 < iv.length; n2++) {
      if (iv[n2][0] > cur && iv[n2][0] - cur > maxBlank) maxBlank = iv[n2][0] - cur
      if (iv[n2][1] > cur) cur = iv[n2][1]
    }

    return JSON.stringify({
      pxW: Math.round(document.querySelector('.px').getBoundingClientRect().width),
      frameH: Math.round(frame.getBoundingClientRect().height),
      blocks: blocks.map(function (bk) { return { blk: bk.blk, sig: sig(bk.el), skel: bk.skel, comp: bk.comp,
        h: bk.h, inner: bk.inner } }),
      adj: adj, dup: dup, eqGrid: eqGrid, maxBlank: maxBlank
    })
  })()`))

  console.log('\n=== ' + v.version + ' · 页宽 ' + st.pxW + 'px · 外框高 ' + st.frameH + ' · 区块 ' +
    st.blocks.length + ' 个（= [data-blk] 六区）===')
  st.blocks.forEach(function (bk) {
    console.log('  [' + bk.blk + '] ' + bk.sig + '  h=' + bk.h)
    console.log('        构图: ' + (bk.comp || '（无布局子元素 → 非构图块，不参与 C1）'))
    bk.inner.forEach(function (g) { console.log('        内距 ' + g.g + 'px: ' + g.at) })
  })
  console.log('  相邻对(C1): ' + st.adj.length + ' 对 —— ' + (st.adj.length ? st.adj.map(function (p) {
    return p.a + '|' + p.b + (p.same ? '【同构!】' : '')
  }).join(' ') : '（无）'))
  console.log('  等列网格(C2): ' + (st.eqGrid.length ? JSON.stringify(st.eqGrid) : '无'))
  console.log('  重复兄弟组(C5) 候选 ' + st.dup.length + ' 组：')
  st.dup.forEach(function (d) {
    console.log('    [' + d.kind + '] ' + d.at + ' > ' + d.n + ' × ' + d.child +
      '（自身布局子元素 ' + d.composes + (d.dataish ? ' · 容器在数据白名单里' : '') + '）')
  })
  console.log('  最大空白带(C6): ' + st.maxBlank + 'px')
  console.log('  C3 不适用 —— 六区之间是 1px 分隔线不是空白带（窗口形态里「区块间距」没有对应物）')
  console.log('  C4 不适用 —— 单栏营销页的左线规则；两栏管理端 + 右对齐数字格天然 ≥3 条')

  r.ok(v.version + ' · 区块 = 六区 + 条件块 flash（C1/C7 才有对象可量，不是空过）',
    st.blocks.length === 7, st.blocks.length + ' 个')
  const sameC = st.adj.filter(function (p) { return p.same })
  r.ok(v.version + ' · C1 相邻区块不共用构图（' + st.adj.length + ' 对相邻）', sameC.length === 0,
    sameC.length ? JSON.stringify(sameC.map(function (p) { return p.a + '|' + p.b })) :
      st.adj.map(function (p) { return p.a + '|' + p.b }).join(' '))
  r.ok(v.version + ' · C2 无「≥3 等列 + ≥3 同构子元素」的网格', st.eqGrid.length === 0,
    st.eqGrid.length ? JSON.stringify(st.eqGrid) : '本页无等列网格')
  r.ok(v.version + ' · C6 无近空屏（最大空白带 < 300px）', st.maxBlank < 300, st.maxBlank + 'px')
  r.ok(v.version + ' · C7 首块之后没有回到同一构图', st.blocks.length < 2 ||
    st.blocks.slice(1).every(function (k) { return k.comp !== st.blocks[0].comp }),
    st.blocks.length < 2 ? '区块不足 2 个' :
      st.blocks.slice(1).filter(function (k) { return k.comp === st.blocks[0].comp }).map(function (k) { return k.blk }).join(',') || '0 个')
  const cards = st.dup.filter(function (d) { return d.kind === '卡·候审' })
  const labels = st.dup.filter(function (d) { return d.kind === '非卡·标签' })
  r.ok(v.version + ' · C5 候选全摆出来（**真命中与否由台账逐条裁决，探针不下判**）', true,
    '卡·候审 ' + cards.length + ' 组：' + cards.map(function (d) { return d.at + '>' + d.n + '×' + d.child }).join(' / ') +
    '｜非卡·标签 ' + labels.length + ' 组（' + labels.map(function (d) { return d.at }).join(' ') + '）')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
