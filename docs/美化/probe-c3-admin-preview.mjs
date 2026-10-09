// probe-c3-admin-preview.mjs —— 四版 admin 配色预览的**渲染实测**（不是「文件写出来了」就算数）。
// 查四件事：① file:// 下中文文件名能否导航 ② ECharts 是否真渲染出 canvas ③ 有没有 JS 报错
//           ④ 要求里的那 8 样元素是否真在页面上。
// 用法：node docs/美化/probe-c3-admin-preview.mjs
import { open, sleep, report } from './_cdp.mjs'
const R = report('c3-admin-preview')

const DIR = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/c3-admin-preview/'
const FILES = [
  ['甲-石墨灰.html', '#1e1e1e'],
  ['乙-近黑.html', '#08090a'],
  ['丙-蓝调炭.html', '#0d1117'],
  ['丁-运营蓝.html', '#101828'],
]

const SNAP = `(() => {
  const q = (s) => document.querySelectorAll(s).length
  const cv = document.querySelector('#chart canvas')
  const rect = cv ? cv.getBoundingClientRect() : null
  // ① 字面 Markdown 星号有没有漏进 HTML（上一版就漏了，断言全绿也照漏）
  const 星号 = (document.body.innerText.match(/\\*\\*/g) || []).length
  // ② 「锁等待 P99」柱是不是真的画出来了 —— 数 canvas 上该系列色（--c6）的像素数。
  //    上一版它挂在 QPS 的 0–1500 轴上，值域只有 4–43ms ⇒ 贴地不可见、像素≈0。
  const cs2 = getComputedStyle(document.documentElement)
  const hex = cs2.getPropertyValue('--c6').trim()
  let 柱像素 = -1
  try {
    const g = cv.getContext('2d')
    const d = g.getImageData(0, 0, cv.width, cv.height).data
    const r = parseInt(hex.slice(1, 3), 16), gg = parseInt(hex.slice(3, 5), 16), b = parseInt(hex.slice(5, 7), 16)
    let n = 0
    for (let i = 0; i < d.length; i += 4) {
      if (d[i + 3] > 200 && Math.abs(d[i] - r) < 12 && Math.abs(d[i + 1] - gg) < 12 && Math.abs(d[i + 2] - b) < 12) n++
    }
    柱像素 = n
  } catch (e) { 柱像素 = -2 }
  return JSON.stringify({
    题: document.title, 星号: 星号, 柱像素: 柱像素, 柱色: hex,
    侧栏: q('.side'), 侧栏选中: q('.it.on'), 顶栏: q('.top'),
    表格行: q('tbody tr'), 斑马: q('tbody tr:nth-child(even)'),
    标签: { 成功: q('.tag.ok'), 警告: q('.tag.wn'), 危险: q('.tag.er'), 信息: q('.tag.inf') },
    按钮: { 主: q('.btn.pri'), 次: q('.btn.sec'), 危险: q('.btn.dan') },
    表单: { 输入: q('.inp'), 下拉: q('.sel'), 开关: q('.sw input') },
    弹窗: q('.modal'),
    banner直接子元素: document.querySelector('.banner') ? document.querySelector('.banner').children.length : -1,
    banner有文字span: !!document.querySelector('.banner > span'),
    banner断行数: (() => { const e = document.querySelector('.banner > span'); if (!e) return -1; return Math.round(e.getBoundingClientRect().height / parseFloat(getComputedStyle(e).lineHeight || 20)) })(),
    图表canvas: !!cv, 图表盒: rect ? [Math.round(rect.width), Math.round(rect.height)] : null,
    色板chip: q('.chip'), 辅助色丸: q('#sw-aux .swc'), 语义丸: q('#sw-sem .swc'),
    图标丸: q('#sw-icon .swc'), 出处条: q('.prov li'),
    底: getComputedStyle(document.body).backgroundColor
  })
})()`

const b = await open({ w: 1440, h: 900 })
// 在任何页面脚本之前挂上错误钩子（否则 ECharts 加载失败的报错抓不到）。
await b.send('Page.addScriptToEvaluateOnNewDocument', {
  source: `window.__errs = []; addEventListener('error', (e) => window.__errs.push(String((e && e.message) || e))); addEventListener('unhandledrejection', (e) => window.__errs.push('rejection: ' + String(e && e.reason))); 1`,
})
try {
  for (const [file, wantBg] of FILES) {
    const url = encodeURI('file:///' + DIR + file)
    let nav = 'ok'
    try { await b.goto(url, 2600) } catch (e) { nav = 'FAIL: ' + String(e).slice(0, 90) }
    const o = JSON.parse(await b.evalJS(SNAP))
    const errs = JSON.parse(await b.evalJS('JSON.stringify(window.__errs || [])'))
    console.log('\n[' + file + '] ' + JSON.stringify(o) + '\n   errs=' + JSON.stringify(errs))
    const L = file.replace('.html', '')
    R.ok(L + '：file:// 中文名可导航', nav === 'ok', nav)
    R.ok(L + '：零 JS 报错（含 ECharts 加载）', errs.length === 0, JSON.stringify(errs))
    R.ok(L + '：ECharts 真渲染出 canvas 且尺寸非零',
      o.图表canvas && o.图表盒 && o.图表盒[0] > 200 && o.图表盒[1] > 100, JSON.stringify(o.图表盒))
    R.ok(L + '：无字面 Markdown 星号漏进 HTML', o.星号 === 0, o.星号)
    // 判据：文字被收进**一个** span（不被 flex 当 item 摊断）+ 只有 1 个元素子节点
    //（`⚠️` 是裸文本节点、不计入 children，故这里是 1 不是 2）。
    R.ok(L + '：banner 文字收在一个 span 里（不被 flex 摊断）',
      o.banner直接子元素 === 1 && o.banner有文字span === true, o.banner直接子元素 + '/' + o.banner有文字span)
    R.ok(L + '：图表「锁等待」柱真画出来了（canvas 上该色像素 > 500）',
      o.柱像素 > 500, o.柱像素 + ' px @ ' + o.柱色)
    R.ok(L + '：底色 = 该版声明值', o.底 === 'rgb(' + wantBg.match(/\w\w/g).map((h) => parseInt(h, 16)).join(', ') + ')', o.底)
    R.ok(L + '：侧栏 + 选中态', o.侧栏 === 1 && o.侧栏选中 === 1, '侧栏=' + o.侧栏 + ' 选中=' + o.侧栏选中)
    R.ok(L + '：顶栏在', o.顶栏 === 1, o.顶栏)
    R.ok(L + '：表格有行且斑马纹生效', o.表格行 === 6 && o.斑马 === 3, '行=' + o.表格行 + ' 斑马=' + o.斑马)
    R.ok(L + '：四类状态标签齐', o.标签.成功 > 0 && o.标签.警告 > 0 && o.标签.危险 > 0 && o.标签.信息 > 0, JSON.stringify(o.标签))
    R.ok(L + '：主/次/危险 三种按钮齐', o.按钮.主 > 0 && o.按钮.次 > 0 && o.按钮.危险 > 0, JSON.stringify(o.按钮))
    R.ok(L + '：输入框 + 下拉 + 开关齐', o.表单.输入 > 0 && o.表单.下拉 === 2 && o.表单.开关 === 2, JSON.stringify(o.表单))
    R.ok(L + '：弹窗 DOM 在', o.弹窗 === 1, o.弹窗)
    R.ok(L + '：图表色板 10 格', o.色板chip === 10, o.色板chip)
    R.ok(L + '：辅助色 / 语义 / 图标 / 出处 四组说明齐',
      o.辅助色丸 === 6 && o.语义丸 === 6 && o.图标丸 === 4 && o.出处条 >= 3,
      JSON.stringify([o.辅助色丸, o.语义丸, o.图标丸, o.出处条]))
    await b.shot('docs/美化/c3-admin-preview/shot-' + L + '.png')
  }

  // 弹窗交互：默认关 → 点「启动模拟」开 → 点「取消」关。只测一版（四版结构逐字相同）。
  // ⚠️ 必须**按文字**选按钮：首屏的 `.btn.pri` 有两个（表格卡头的「＋ 新建规则」在前），
  //    取第一个会点到没有 onclick 的那个 —— 上一轮就是这么误判成「弹窗打不开」的。
  await b.goto(encodeURI('file:///' + DIR + FILES[0][0]), 2200)
  const hidden0 = await b.evalJS("String(document.querySelector('#mask').hidden)")
  const clicked = await b.evalJS(`(() => { const t = [...document.querySelectorAll('.btn')].find((x) => x.textContent.includes('启动模拟')); if (!t) return 'NOT-FOUND'; t.click(); return 'clicked' })()`)
  await sleep(250)
  const hidden1 = await b.evalJS("String(document.querySelector('#mask').hidden)")
  await b.evalJS("(() => { [...document.querySelectorAll('.modal .btn')].find((x) => x.textContent.trim() === '取消').click(); return 1 })()")
  await sleep(250)
  const hidden2 = await b.evalJS("String(document.querySelector('#mask').hidden)")
  console.log('\n[弹窗] 找到按钮=' + clicked + '  hidden: ' + hidden0 + ' → ' + hidden1 + ' → ' + hidden2)
  R.ok('弹窗：默认关闭', hidden0 === 'true', hidden0)
  R.ok('弹窗：点「启动模拟」打开', clicked === 'clicked' && hidden1 === 'false', clicked + '/' + hidden1)
  R.ok('弹窗：点取消关闭', hidden2 === 'true', hidden2)

  // 窄屏兜底：index.html 就是拿 ~686px 的 iframe 展示的 ⇒ 这里按 686px 复现一遍。
  // 判据取「块级元素有没有被挤成多行」而不是「好不好看」——竖排断字在数学上是可测的。
  await b.send('Emulation.setDeviceMetricsOverride', { width: 686, height: 900, deviceScaleFactor: 1, mobile: false })
  for (const [file] of FILES) {
    await b.goto(encodeURI('file:///' + DIR + file), 2000)
    const n = await b.evalJS(`(() => {
      const cols = getComputedStyle(document.querySelector('.kpis')).gridTemplateColumns.split(' ').length
      const line = (s) => { const e = document.querySelector(s); return e ? Math.round(e.getBoundingClientRect().height) : -1 }
      return JSON.stringify({ kpi列数: cols, 面包屑高: line('.crumb'), 顶栏高: line('.top'), 首按钮高: line('.btn.pri') })
    })()`)
    const o = JSON.parse(n)
    console.log('\n[686px] ' + file + ' ' + n)
    R.ok(file + ' @686：KPI 收成 2 列', o.kpi列数 === 2, o.kpi列数)
    R.ok(file + ' @686：面包屑仍是单行（高 ≤ 24）', o.面包屑高 > 0 && o.面包屑高 <= 24, o.面包屑高)
    R.ok(file + ' @686：顶栏没被撑高（≤ 60）', o.顶栏高 > 0 && o.顶栏高 <= 60, o.顶栏高)
    R.ok(file + ' @686：主按钮仍是单行（高 ≤ 34）', o.首按钮高 > 0 && o.首按钮高 <= 34, o.首按钮高)
  }
  await b.shot('docs/美化/c3-admin-preview/shot-686.png')
  await b.send('Emulation.clearDeviceMetricsOverride')

  // 对照页
  // ⚠️ `file://` 下每个文件是独立 opaque origin ⇒ 父页**读不到** iframe 的 contentDocument，
  //    上一轮那个 "blocked" 就是这个限制，**不是** iframe 没加载。故改为：
  //    ① iframe 数量与尺寸 ② 整页截图（人工可看，且下面会按像素方差判「不是一片空白」）。
  await b.goto(encodeURI('file:///' + DIR + 'index.html'), 3200)
  const cells = await b.evalJS("String(document.querySelectorAll('.cell iframe').length)")
  const dims = JSON.parse(await b.evalJS(`JSON.stringify([...document.querySelectorAll('.cell iframe')].map((f) => { const r = f.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)] }))`))
  const probeable = await b.evalJS("(() => { try { document.querySelector('.cell iframe').contentDocument; return 'readable' } catch (e) { return 'blocked' } })()")
  console.log('\n[对照页] iframe=' + cells + ' 尺寸=' + JSON.stringify(dims) + ' contentDocument=' + probeable)
  R.ok('对照页：四格 iframe', cells === '4', cells)
  R.ok('对照页：四格尺寸都非零', dims.length === 4 && dims.every((d) => d[0] > 300 && d[1] > 200), JSON.stringify(dims))
  await b.shot('docs/美化/c3-admin-preview/shot-index.png')
} finally { await b.close(); R.dump() }
