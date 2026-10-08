// probe-topnav2.mjs —— 2026-10-08 用户第 2 条：「商家端顶栏右侧切换按钮（消息/订单中心/用户中心）盖过顶栏内容」。
// 口径：这是一条**视觉 bug 断言**（「盖过」= 盒子交叠 / 溢出），先复现再谈修法 ⇒ 把商家壳顶栏三块
//   （`.nav-left` 品牌 / `.nav-search` 搜索框 / `.nav-right` 三个入口）的盒子量出来，逐对算**交叠面积**，
//   外加顶栏自身的横向溢出；宽度扫 1440→900（窄到多窄才出事）。未读红点也一并量（`vertical-align:super` 那件）。
import { open, sleep, report } from './_cdp.mjs'
import fs from 'fs'

fs.mkdirSync('docs/美化/dnav', { recursive: true })

// 未读会话 3 条 ⇒ 商家壳顶栏「消息」带红点（红点是最可能「越格」的东西）
const CONV = { success: true, list: [{ unread: true }, { unread: true }, { unread: true }, { unread: false }] }
const MOCK = (url) => {
  if (/conversations/.test(url)) return { status: 200, body: JSON.stringify(CONV) }
  return { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }
}

const SNAP = `(() => {
  const bx = (e) => { if (!e) return null; const r = e.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.right), Math.round(r.top), Math.round(r.bottom)] }
  const items = [...document.querySelectorAll('.top-nav .nav-right > *')].map((e) => ({ t: e.textContent.trim(), x: bx(e), dot: !!e.querySelector('.nav-red-dot') }))
  const L = bx(document.querySelector('.top-nav .nav-left'))
  const S = bx(document.querySelector('.top-nav .nav-search'))
  const Rr = bx(document.querySelector('.top-nav .nav-right'))
  const tn = document.querySelector('.top-nav')
  return JSON.stringify({
    left: L, search: S, right: Rr, items,
    nav: bx(tn), navOverX: tn.scrollWidth - tn.clientWidth,
    pageOverX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    searchH: S ? S[3] - S[2] : null, searchInner: bx(document.querySelector('.search-input')),
    btn: bx(document.querySelector('.search-btn')),
    btnBg: (() => { const b = document.querySelector('.search-btn'); return b ? getComputedStyle(b).backgroundColor + ' / ' + getComputedStyle(b).color + ' / ' + getComputedStyle(b).fontSize : null })()
  })
})()`
const overlap = (a, b) => (a && b) ? Math.max(0, Math.min(a[1], b[1]) - Math.max(a[0], b[0])) * Math.max(0, Math.min(a[3], b[3]) - Math.max(a[2], b[2])) : -1

const b = await open({ w: 1440, h: 900, mock: MOCK })
b.ws.on('message', (m) => { if (m.method === 'Page.javascriptDialogOpening') b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {}) })
const R = report('probe-topnav2')
try {
  await b.goto('http://localhost:8080/#/customer', 1200)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeM');sessionStorage.setItem('role','2');sessionStorage.setItem('merchantid','M9001');1`)
  await b.goto('http://localhost:8080/#/merchant', 2000)
  await b.send('Page.reload', { ignoreCache: true }); await sleep(2600)
  for (const W of [1440, 1280, 1120, 1024, 960, 900]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: W, height: 900, deviceScaleFactor: 1, mobile: false })
    await sleep(400)
    const s = JSON.parse(await b.evalJS(SNAP))
    console.log(`\n===== ${W} 宽 =====`)
    console.log('  品牌', s.left, ' 搜索框', s.search, ' 右侧三项', s.right)
    console.log('  右三项：', s.items.map((i) => i.t + (i.dot ? '●' : '') + '[' + i.x[0] + '-' + i.x[1] + ' 上' + i.x[2] + '下' + i.x[3] + ']').join(' | '))
    console.log('  搜索框高', s.searchH, ' 输入框', s.searchInner, ' 按钮', s.btn, s.btnBg)
    console.log('  交叠：品牌×搜索', overlap(s.left, s.search), ' 搜索×右侧', overlap(s.search, s.right), ' 顶栏溢出', s.navOverX, ' 页面溢出', s.pageOverX)
    R.ok(`${W} 搜索框与右侧三项不交叠`, overlap(s.search, s.right) <= 0, overlap(s.search, s.right))
    R.ok(`${W} 品牌与搜索框不交叠`, overlap(s.left, s.search) <= 0, overlap(s.left, s.search))
    R.ok(`${W} 顶栏无横向溢出`, s.navOverX === 0 && s.pageOverX === 0, [s.navOverX, s.pageOverX])
    R.ok(`${W} 右侧三项都在顶栏带内（0–60）`, s.items.every((i) => i.x[2] >= 0 && i.x[3] <= 60), s.items.map((i) => [i.x[2], i.x[3]]))
    if (W === 1280 || W === 1024) await b.shot(`docs/美化/dnav/topnav-${W}.png`)
  }

  // ── 补测：**命中测试**（「盖过」的功能义 = 点不到）——沿顶栏横扫一遍，看每一点最上层是谁；
  //    再补两档极窄（700/600）看右三项会不会换行/顶出 60px 带（那是「盖过」的另一种形态）。 */
  for (const W of [1280, 1024, 700, 600]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: W, height: 900, deviceScaleFactor: 1, mobile: false })
    await sleep(400)
    const hit = JSON.parse(await b.evalJS(`(() => {
      const s = document.querySelector('.top-nav .nav-search').getBoundingClientRect()
      const out = []
      // ⚠️ 从 left+2 扫到 right-2：盒子的右沿 x=right **不属于盒子**（半开区间），取到它只会命中
      //    父级 .top-nav ⇒ 那是**采样造的假红**（2026-10-08 首轮 1280/1024 各 1 条），不是页面被盖。
      //    ⚠️ 本段在**模板字符串里**，注释里不能出现反引号（会把模板截断 → ReferenceError）。
      for (let x = Math.round(s.left) + 2; x <= Math.round(s.right) - 2; x += 20) {
        const e = document.elementFromPoint(x, 30)
        out.push(e ? (e.className || e.tagName) : 'null')
      }
      const r = document.querySelector('.top-nav .nav-right').getBoundingClientRect()
      const items = [...document.querySelectorAll('.top-nav .nav-right > *')].map((e) => { const q = e.getBoundingClientRect(); return [Math.round(q.top), Math.round(q.bottom), Math.round(q.height), e.textContent.trim()] })
      return JSON.stringify({ hits: [...new Set(out)], items, navH: document.querySelector('.top-nav').getBoundingClientRect().height })
    })()`))
    console.log(`\n【命中 ${W}】搜索框内最上层元素集合: ${JSON.stringify(hit.hits)}  顶栏高 ${hit.navH}`)
    console.log('   右三项盒:', JSON.stringify(hit.items))
    R.ok(`${W} 搜索框内命中的都是搜索框自己的零件`, hit.hits.every((h) => /search-input|search-btn|nav-search/.test(h)), hit.hits)
    R.ok(`${W} 右三项不换行（高 ≤ 30）且不出 60px 带`, hit.items.every((i) => i[2] <= 30 && i[0] >= 0 && i[1] <= 60), hit.items)
    if (W === 700) await b.shot('docs/美化/dnav/topnav-700.png')
  }
  R.dump()
} finally { await b.close() }
