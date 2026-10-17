/* ══════════════════════════════════════════════════════════════════════════════
   probe-b7b7-orderflow-struct-shots.mjs —— 3b.7 状态流转页两版结构草图的
   整页截图 + 结构断言（纯 file://，不起 dev server / 后端）。

   跑法：node scripts/probes/probe-b7b7-orderflow-struct-shots.mjs

   断言只查它真查了的东西（「校验器报 OK ≠ 没问题」）：
     · 两版都能挂载；主装置/分割真的画出来且尺寸非零
     · C2：`.kpi` 数 = 0（没有等宽等高卡片）
     · C3：区块间距 28px > 块内 12px（读计算样式，不写「看起来」）
     · C4：主区宽 = clientW − 侧栏 232 − 主区左右 padding（模型，不是常数）
     · C1：顶层/栏内相邻区块的直接子元素结构指纹互不相同
     · 灰阶：所有元素的前景/背景/边框色 R=G=B（无品牌色）
     · 无圆角、无阴影、无过渡/动画
     · 无横向溢出；SVG 文本不越出 viewBox
   ══════════════════════════════════════════════════════════════════════════════ */
import path from 'node:path'
import fs from 'node:fs'
import { pathToFileURL } from 'node:url'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const DIR = path.join(ROOT, 'docs', '美化', 'c3-admin-preview')
const { open, report } = await import(pathToFileURL(path.join(ROOT, 'docs', '美化', '_cdp.mjs')).href)

const VIEW_W = 1440
const VIEW_H = 1000
const VARIANTS = [
  { file: '3b7-甲-漏斗流转.html', png: '3b7-甲-漏斗流转.png', kind: 'jia' },
  { file: '3b7-乙-非对称双栏.html', png: '3b7-乙-非对称双栏.png', kind: 'yi' },
]

const r = report('3b7 状态流转页结构草图')
console.log('=== 3b.7 状态流转页两版结构草图：整页截图 + 结构断言 ===')
console.log('跑法：node scripts/probes/probe-b7b7-orderflow-struct-shots.mjs')
console.log('')

const b = await open({ w: VIEW_W, h: VIEW_H, headed: false })

for (const v of VARIANTS) {
  const html = path.join(DIR, v.file)
  if (!fs.existsSync(html)) { r.ok(v.file + ' 存在', false, 'NOT FOUND'); continue }
  await b.goto(pathToFileURL(html).href, 1200)

  const st = JSON.parse(await b.evalJS(`(function () {
    var page = document.querySelector('.of-page')
    function sig(el) {
      return [].slice.call(el.children).map(function (c) {
        return c.tagName.toLowerCase() + '.' + (c.className || '').toString().trim().split(/\\s+/).join('.')
      }).join(' | ')
    }
    /* 顶层相邻区块：读数带 + 各 of-block；乙是读数带 + of-split */
    var tops = [].slice.call(document.querySelectorAll('#app > section, #app > .of-head'))
    var topSigs = tops.map(function (e) { return { cls: e.className, sig: sig(e) } })

    /* 栏内相邻区块（乙）：左栏 / 右栏各自去掉 h2 后的直接子元素指纹 */
    function inner(cls) {
      var e = document.querySelector(cls)
      if (!e) return null
      return [].slice.call(e.children).filter(function (c) { return !/\\bh2\\b/.test(c.className) })
        .map(function (c) { return c.tagName.toLowerCase() + '.' + (c.className || '').toString().trim().split(/\\s+/).join('.') }).join(' | ')
    }

    /* 灰阶 / 形状 / 动效：遍历页面内所有元素的计算样式 */
    var colored = [], radius = [], shadow = [], motion = []
    ;[].slice.call(page.querySelectorAll('*')).forEach(function (e) {
      var cs = getComputedStyle(e)
      function chroma(c) {
        var m = String(c).match(/rgba?\\(([^)]+)\\)/)
        if (!m) return false
        var p = m[1].split(',').map(function (x) { return parseFloat(x) })
        if (p.length > 3 && p[3] === 0) return false
        return !(p[0] === p[1] && p[1] === p[2])
      }
      ;['color', 'backgroundColor', 'borderTopColor', 'borderRightColor', 'borderBottomColor', 'borderLeftColor'].forEach(function (k) {
        if (chroma(cs[k])) colored.push(e.tagName + '.' + e.className + ' ' + k + '=' + cs[k])
      })
      if (parseFloat(cs.borderTopLeftRadius) > 0) radius.push(e.tagName + '.' + e.className)
      if (cs.boxShadow && cs.boxShadow !== 'none') shadow.push(e.tagName + '.' + e.className)
      if (parseFloat(cs.transitionDuration) > 0 || (cs.animationName && cs.animationName !== 'none')) motion.push(e.tagName + '.' + e.className)
    })

    /* SVG 文本不越出 viewBox */
    var svgOut = []
    ;[].slice.call(document.querySelectorAll('svg')).forEach(function (s) {
      var vb = s.viewBox.baseVal
      ;[].slice.call(s.querySelectorAll('text')).forEach(function (t) {
        try {
          var bb = t.getBBox()
          if (bb.x < vb.x - 0.5 || bb.y < vb.y - 0.5 || bb.x + bb.width > vb.x + vb.width + 0.5 || bb.y + bb.height > vb.y + vb.height + 0.5) {
            svgOut.push((t.textContent || '').trim() + ' @ ' + Math.round(bb.x) + ',' + Math.round(bb.y) + ' ' + Math.round(bb.width) + 'x' + Math.round(bb.height))
          }
        } catch (e) {}
      })
    })

    var split = document.querySelector('.of-split')
    var device = document.querySelector('.of-device svg')
    var blocks = document.querySelectorAll('.of-block')
    var secondBlock = blocks.length ? blocks[0] : null
    return JSON.stringify({
      mounted: !!page,
      clientW: document.documentElement.clientWidth,
      innerW: window.innerWidth,
      scrollW: document.documentElement.scrollWidth,
      pageH: document.documentElement.scrollHeight,
      contentW: Math.round(document.querySelector('.px').getBoundingClientRect().width),
      sideW: Math.round(document.querySelector('.side').getBoundingClientRect().width),
      mainPadL: parseFloat(getComputedStyle(document.querySelector('.main')).paddingLeft),
      kpi: document.querySelectorAll('.kpi').length,
      boxes: document.querySelectorAll('.box').length,
      device: device ? { w: Math.round(device.getBoundingClientRect().width), h: Math.round(device.getBoundingClientRect().height) } : null,
      split: split ? {
        ratio: (function () {
          var cs = getComputedStyle(split)
          return { left: Math.round(split.children[0].getBoundingClientRect().width), right: Math.round(split.children[1].getBoundingClientRect().width), gap: cs.columnGap }
        })()
      } : null,
      blockGap: secondBlock ? getComputedStyle(secondBlock).marginTop : null,
      innerStepGap: document.querySelector('.of-step-body') ? getComputedStyle(document.querySelector('.of-step-body')).columnGap : null,
      foldGap: document.querySelector('.of-fold-body') ? getComputedStyle(document.querySelector('.of-fold-body')).columnGap : null,
      topSigs: topSigs,
      leftInner: inner('.of-left'),
      rightInner: inner('.of-right'),
      colored: colored.slice(0, 5), coloredN: colored.length,
      radiusN: radius.length, shadowN: shadow.length, motionN: motion.length,
      svgOut: svgOut.slice(0, 5), svgOutN: svgOut.length,
      stream: document.querySelector('.of-stream') ? {
        h: Math.round(document.querySelector('.of-stream').getBoundingClientRect().height),
        overflow: getComputedStyle(document.querySelector('.of-stream')).overflowY,
        rows: document.querySelectorAll('.of-stream-row').length
      } : null
    })
  })()`))

  const ok = (label, cond, got) => r.ok(v.file + ' · ' + label, cond, got)

  ok('页面挂载', st.mounted === true)
  ok('C2 无等宽等高卡片（.kpi = 0，.box = 0）', st.kpi === 0 && st.boxes === 0, 'kpi=' + st.kpi + ' box=' + st.boxes)
  ok('C3 区块间距 > 块内间距（28 vs 12）', parseFloat(st.blockGap) === 28 &&
    (!st.innerStepGap || parseFloat(st.innerStepGap) === 12) &&
    (!st.foldGap || parseFloat(st.foldGap) === 12),
    'block=' + st.blockGap + ' step=' + st.innerStepGap + ' fold=' + st.foldGap)
  const expectW = st.clientW - st.sideW - st.mainPadL * 2
  ok('C4 主区宽 == clientW − 侧栏 − 2×padding', Math.abs(st.contentW - expectW) <= 1, st.contentW + ' vs ' + expectW)
  ok('无横向溢出', st.scrollW <= st.clientW, 'scrollW=' + st.scrollW + ' clientW=' + st.clientW)

  if (v.kind === 'jia') {
    ok('主装置 SVG 已画出且尺寸非零', !!st.device && st.device.w > 800 && st.device.h > 200, st.device)
  } else {
    ok('8/4 非对称分割（左/右 ≈ 2，gap 28）', !!st.split && st.split.ratio.gap === '28px' &&
      Math.abs(st.split.ratio.left / st.split.ratio.right - 2) < 0.08, st.split && st.split.ratio)
    ok('实时流是内部滚动（overflow auto 且内容多于一屏）', !!st.stream && st.stream.overflow === 'auto' &&
      st.stream.rows >= 8, st.stream)
  }

  /* C1：相邻区块直接子元素指纹两两不同 */
  const sigs = st.topSigs.map((x) => x.sig)
  const dupTop = sigs.filter((s, i) => sigs.indexOf(s) !== i)
  ok('C1 顶层相邻区块不同构', dupTop.length === 0, dupTop.length ? dupTop.join(' / ') : sigs.length + ' 块互不相同')
  if (v.kind === 'yi') {
    const uniq = (s) => s && new Set(s.split(' | ')).size === s.split(' | ').length && s.split(' | ').length >= 2
    ok('C1 左栏内相邻区块不同构', uniq(st.leftInner), st.leftInner)
    ok('C1 右栏内相邻区块不同构', uniq(st.rightInner), st.rightInner)
  }

  ok('灰阶：无品牌色（R=G=B）', st.coloredN === 0, st.coloredN ? JSON.stringify(st.colored) : '0 个彩色声明')
  ok('无圆角 / 无阴影 / 无动效', st.radiusN === 0 && st.shadowN === 0 && st.motionN === 0,
    'radius=' + st.radiusN + ' shadow=' + st.shadowN + ' motion=' + st.motionN)
  ok('SVG 文本不越出 viewBox', st.svgOutN === 0, st.svgOutN ? JSON.stringify(st.svgOut) : '0 条')

  console.log(`     ${v.file} 页高 ${st.pageH}px · 主区宽 ${st.contentW}px（视口 ${st.innerW} / 客户区 ${st.clientW} / 滚动条 ${st.innerW - st.clientW}）`)
  if (v.kind === 'yi') console.log(`     左右栏 ${st.split.ratio.left} / ${st.split.ratio.right}px · 流 ${st.stream.rows} 行 / 可视高 ${st.stream.h}px`)

  const out = path.join(DIR, v.png)
  await b.shotFull(out)
  r.ok(v.png + ' 落盘', fs.existsSync(out) && fs.statSync(out).size > 5000,
    fs.existsSync(out) ? fs.statSync(out).size + 'B' : 'MISSING')
}

await b.close()
process.exit(r.dump() ? 1 : 0)
