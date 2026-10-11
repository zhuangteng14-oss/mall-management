// probe-vis-charts.mjs —— 可视化段 commit 1（图表体系）的**渲染实测**（不是「文件写出来了」就算数）。
//
// 查六件事：
//   ① 五个图各画出了一块**非空** canvas（判据 = 画布上不透明像素数，不是「有 <canvas> 标签」）
//   ② KPI 卡四态（有环比 / 无环比 / 反向指标 / 加载）
//   ③ 图标：图标行 11 枚 + KPI 卡 5 枚 = 全页 16 枚，且**没有一枚落到回落字符 `·`**
//   ④ 三态浮层（加载骨架 / 空 / 错误 + 重试）
//   ⑤ 浅色与暗色两套 token 各自取到不同的值（这是本笔最易静默失效的点）
//   ⑥ 全程没有 JS 报错
//
// 用法：先 `npm run serve`（8080），再 `node scripts/probes/probe-vis-charts.mjs`
// 证据落 `scripts/probes/out-vis-charts-*.png`。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080/#/__probe-charts'

const SNAP = `(() => {
  const out = {}
  const cs = getComputedStyle(document.documentElement)
  out.theme = document.documentElement.getAttribute('data-theme') || '(none)'
  out.c1 = cs.getPropertyValue('--c1').trim()
  out.c2 = cs.getPropertyValue('--c2').trim()
  out.grid = cs.getPropertyValue('--grid-line').trim()
  out.axisLabel = cs.getPropertyValue('--axis-label').trim()
  out.bg = cs.getPropertyValue('--bg').trim()
  out.ink = cs.getPropertyValue('--ink').trim()

  const canvases = [...document.querySelectorAll('.bc-canvas canvas')]
  out.canvasCount = canvases.length
  out.canvasSizes = canvases.map(c => {
    const r = c.getBoundingClientRect()
    return [Math.round(r.width), Math.round(r.height)]
  })
  // 判据 = **不透明像素数**。空白画布（只 init 没 setOption）也有 canvas 标签，
  // 只有数像素才分得出「画了」和「没画」。
  out.inkPixels = canvases.map(c => {
    try {
      const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data
      let n = 0
      for (let i = 3; i < d.length; i += 4) if (d[i] > 24) n++
      return n
    } catch (e) { return -1 }
  })

  out.kpi = document.querySelectorAll('.kpi').length
  out.kpiNoValue = [...document.querySelectorAll('.kpi')]
    .filter(k => !k.querySelector('.kpi-n') && !k.querySelector('.kpi-skel')).length
  out.kpiLoading = document.querySelectorAll('.kpi .kpi-skel').length
  out.arrowGood = document.querySelectorAll('.kpi-arrow--good').length
  out.arrowBad = document.querySelectorAll('.kpi-arrow--bad').length
  out.arrowFlat = document.querySelectorAll('.kpi-arrow--flat').length

  // ⚠️ 口径要**限定在图标行**（.p-glyphs）：KPI 卡左上角也各挂一枚 IconGlyph
  //    ⇒ 全页 .ig 是 11 + 5 = 16，不是「图标行多画了 5 枚」。初版这里数的是全页，
  //    报了个假阳性。两件事分开断言：图标行 11 枚 + 全页 16 枚。
  const glyphRow = [...document.querySelectorAll('.p-glyphs .ig')]
  out.glyph = glyphRow.length
  out.glyphTotal = document.querySelectorAll('.ig').length
  out.glyphFallback = glyphRow.filter(e => e.textContent.trim() === '·').length
  out.glyphChars = glyphRow.map(e => e.textContent.trim())

  out.states = {
    loadingSkeleton: document.querySelectorAll('.bc-state--loading .bc-skel').length,
    empty: document.querySelectorAll('.bc-state--empty').length,
    error: document.querySelectorAll('.bc-state--error').length,
    retryBtn: document.querySelectorAll('.bc-retry').length
  }
  out.overlayBg = (() => {
    const e = document.querySelector('.bc-state')
    return e ? getComputedStyle(e).backgroundColor : '(none)'
  })()
  out.donutCenter = document.querySelectorAll('.dc-center').length
  out.donutCenterText = (document.querySelector('.dc-center') || {}).innerText || ''
  return JSON.stringify(out)
})()`

const r = report('vis-charts')

async function run(theme, label) {
  const b = await open({ w: 1440, h: 1400, headed: false })
  const errs = []
  b.ws.on('message', (raw) => {
    let m
    try { m = JSON.parse(raw) } catch (e) { return }
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params.exceptionDetails
      errs.push((d.text || '') + ' ' + ((d.exception && d.exception.description) || ''))
    } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      errs.push(m.params.args.map((a) => a.value || a.description || '').join(' '))
    }
  })

  const url = theme ? BASE + '?theme=' + theme : BASE
  await b.goto(url, 2600)
  const s = JSON.parse(await b.evalJS(SNAP))
  await b.shot(`scripts/probes/out-vis-charts-${label}.png`)
  await b.close()

  console.log(`\n── ${label} ──`)
  for (const [k, v] of Object.entries(s)) {
    console.log(`   ${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`)
  }
  if (errs.length) console.log('   JS 错误:\n     ' + errs.join('\n     '))
  return { s, errs }
}

const light = await run('', 'light')
const dark = await run('admin', 'dark')

// ── 断言 ────────────────────────────────────────────────────────────────────────────
const L = light.s
const D = dark.s

r.ok('浅色：8 块 canvas（5 个数据图 + 3 个三态图）', L.canvasCount === 8, L.canvasCount)
r.ok('浅色：画布都有尺寸', L.canvasSizes.every(([w, h]) => w > 0 && h > 0), L.canvasSizes)
r.ok(
  '浅色：前 5 块画布都真画了（不透明像素 > 300）',
  L.inkPixels.slice(0, 5).every((n) => n > 300),
  L.inkPixels
)
r.ok('浅色：三态画布几乎空白（< 3000）', L.inkPixels.slice(5).every((n) => n < 3000), L.inkPixels.slice(5))

r.ok('KPI 卡 5 张', L.kpi === 5, L.kpi)
r.ok('KPI 没有「既无数字又无骨架」的', L.kpiNoValue === 0, L.kpiNoValue)
r.ok('KPI 加载骨架 1 个', L.kpiLoading === 1, L.kpiLoading)
// 5 张卡：GMV +8.2(good) / 成单数 -2.4(bad) / 退款率 +0.9 且 inverse(bad) / 客单价 0(flat) / 加载(无箭头)
r.ok('箭头配色 good=1 bad=2 flat=1', L.arrowGood === 1 && L.arrowBad === 2 && L.arrowFlat === 1,
  { good: L.arrowGood, bad: L.arrowBad, flat: L.arrowFlat })

r.ok('图标行 11 枚', L.glyph === 11, L.glyph)
r.ok('全页图标 16 枚（图标行 11 + KPI 卡 5）', L.glyphTotal === 16, L.glyphTotal)
r.ok('没有图标落到回落字符 ·', L.glyphFallback === 0, L.glyphFallback)
r.ok('图标字符与 glyphs.js 一致', L.glyphChars.join('') === '◧▤▦◍◎⚡◔↻≣⚿⚙', L.glyphChars.join(''))

r.ok('三态：加载骨架在', L.states.loadingSkeleton === 1, L.states.loadingSkeleton)
r.ok('三态：空态在', L.states.empty === 1, L.states.empty)
r.ok('三态：错误态 + 重试按钮在', L.states.error === 1 && L.states.retryBtn === 1, L.states)
r.ok('三态浮层是**不透明**的（不是透明叠字）', /^rgb\(/.test(L.overlayBg), L.overlayBg)
r.ok('环图中心那组数在', L.donutCenter === 1, L.donutCenter)

r.ok('浅色 --bg 是白', L.bg === '#ffffff', L.bg)
r.ok('浅色 --grid-line 是浅色档', L.grid === '#e6ebef', L.grid)
r.ok('浅色 --c2 是压过明度的那版', L.c2 === '#f16400', L.c2)

r.ok('暗色：主题属性是 admin', D.theme === 'admin', D.theme)
r.ok('暗色 --bg 是深底', D.bg === '#101828', D.bg)
r.ok('暗色 --grid-line 是暗色档', D.grid === '#273449', D.grid)
r.ok('暗色 --c2 是丁版原值', D.c2 === '#ff6a00', D.c2)
r.ok('**两套主题的 --grid-line 确实不同**（token 按主题换值生效）', L.grid !== D.grid, [L.grid, D.grid])
r.ok('暗色：前 5 块画布都真画了', D.inkPixels.slice(0, 5).every((n) => n > 300), D.inkPixels)
r.ok('暗色：图标同为 11 枚', D.glyph === 11, D.glyph)

r.ok('浅色无 JS 错误', light.errs.length === 0, light.errs)
r.ok('暗色无 JS 错误', dark.errs.length === 0, dark.errs)

process.exit(r.dump())
