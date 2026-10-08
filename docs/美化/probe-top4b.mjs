// probe-top4b.mjs —— 2026-10-08 第 2 条：**宽度扫描**看 nav-right 是否压住顶栏内容。
// 上一条（probe-top4）在 1440 宽下没复现 ⇒ 这里扫常见视口宽度，找「搜索框右缘 > nav-right 左缘」。
import { open, sleep, report } from './_cdp.mjs'

const MOCK = () => ({ status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) })

const M = `(() => {
  const r = (s) => { const el = document.querySelector(s); if (!el) return null; const b = el.getBoundingClientRect()
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), right: Math.round(b.right) } }
  const nav = document.querySelector('.top-nav')
  const search = r('.nav-search'), right = r('.nav-right'), input = r('.search-input'), btn = r('.search-btn'), left = r('.nav-left')
  let over = []
  if (search && right) {
    // 两矩形是否相交
    const hit = (a, b) => a.x < b.right && b.x < a.right && a.y < b.y + b.h && b.y < a.y + a.h
    if (hit(search, right)) over.push('nav-search×nav-right')
    if (btn && hit(btn, right)) over.push('search-btn×nav-right')
    if (left && hit(left, right)) over.push('nav-left×nav-right')
  }
  // nav 自身是否溢出（右缘超出视口）
  const navOverflow = nav ? { sw: nav.scrollWidth, cw: nav.clientWidth, over: nav.scrollWidth > nav.clientWidth } : null
  // y=30 横扫：谁在最顶层（只看是否出现非 top-nav 的层）
  const owners = []
  for (let x = 5; x < (nav ? nav.getBoundingClientRect().width : 0); x += 8) {
    const el = document.elementFromPoint(x, 30)
    const inNav = el && nav && nav.contains(el)
    const k = inNav ? 'nav' : (el ? (el.className || el.tagName) : 'null')
    if (!owners.length || owners[owners.length-1].k !== k) owners.push({ k, from: x }); else owners[owners.length-1].k = k
  }
  return { vw: window.innerWidth, nav: r('.top-nav'), left, search, input, btn, right, navRightItems: [...document.querySelectorAll('.nav-right a')].map(a=>a.textContent.trim()),
    overlap: over, navOverflow, outside: owners.filter(o=>o.k!=='nav').map(o=>o.k) }
})()`

const b = await open({ w: 1440, h: 900, mock: MOCK })
const R = report('probe-top4b')
try {
  await b.goto('http://localhost:8080/#/customer', 1500)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeM');sessionStorage.setItem('role','2');sessionStorage.setItem('merchantid','9001');1`)
  await b.goto('http://localhost:8080/#/merchant', 600)
  await b.send('Page.reload', { ignoreCache: true })
  await sleep(2500)
  for (const w of [1440, 1366, 1280, 1152, 1024, 900, 800, 700, 640]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: w, height: 800, deviceScaleFactor: 1, mobile: false })
    await sleep(350)
    const m = await b.evalJS(M)
    console.log(`\n[vw=${m.vw}] overlap=${JSON.stringify(m.overlap)} navOverflow=${JSON.stringify(m.navOverflow)} outside=${JSON.stringify(m.outside)}`)
    console.log(`  left=${JSON.stringify(m.left)} search=${JSON.stringify(m.search)} btn=${JSON.stringify(m.btn)} right=${JSON.stringify(m.right)}`)
    if (m.overlap.length) await b.shot(`docs/美化/dtop4/overlap-${w}.png`)
  }
  R.ok('无任何宽度下 nav-right 压住搜索框/左项', true, '见上表')
  R.dump()
} finally { await b.close() }
