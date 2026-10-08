// probe-top4c.mjs —— 2026-10-08 第 2 条：全商家路由 × 顶栏带 0–60 的「谁在上面」普查。
// 两步：① 谁在某点最顶层（elementFromPoint）；② 全仓「定位 + z-index」元素里谁与顶栏带相交。
import { open, sleep, report } from './_cdp.mjs'
const MOCK = () => ({ status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) })

const SCAN = `(() => {
  const nav = document.querySelector('.top-nav')
  if (!nav) return { err: 'no .top-nav' }
  const nb = nav.getBoundingClientRect()
  const desc = (el) => el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : '')
  const strip = []
  for (const y of [3, 15, 30, 45, 57]) {
    let prev = null
    for (let x = 1; x < nb.width; x += 6) {
      const el = document.elementFromPoint(x, y)
      const inside = el && nav.contains(el)
      const k = inside ? 'nav' : (el ? 'OUT:' + desc(el) : 'null')
      if (k !== prev) { strip.push({ y, x, k }); prev = k }
    }
  }
  // 定位元素 ∩ 顶栏带
  const cand = []
  for (const el of document.querySelectorAll('*')) {
    const cs = getComputedStyle(el)
    if (cs.position === 'static' || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (cs.zIndex === 'auto') continue
    const b = el.getBoundingClientRect()
    if (b.width < 1 || b.height < 1) continue
    if (b.top < nb.bottom && b.bottom > nb.top) cand.push({ el: desc(el), z: cs.zIndex, pos: cs.position,
      r: { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) } })
  }
  return { strip: strip.filter(s => s.k !== 'nav'), cand, docH: document.documentElement.scrollHeight, innerH: innerHeight,
           scrolled: Math.round(scrollY) }
})()`

const ROUTES = ['/merchant', '/merchant/search', '/merchant/msg', '/merchant/order', '/merchant/order/cart',
  '/merchant/user', '/merchant/user/info', '/merchant/user/add', '/merchant/user/list', '/merchant/user/history', '/merchant/user/friend']

const b = await open({ w: 1440, h: 900, mock: MOCK })
const R = report('probe-top4c')
try {
  await b.goto('http://localhost:8080/#/customer', 1500)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeM');sessionStorage.setItem('role','2');sessionStorage.setItem('merchantid','9001');1`)
  await b.goto(`http://localhost:8080/#${ROUTES[0]}`, 600)
  await b.send('Page.reload', { ignoreCache: true }); await sleep(2500)
  let bad = 0
  for (const r of ROUTES) {
    await b.goto('http://localhost:8080/#' + r, 1800)
    const at = await b.evalJS(SCAN)
    await b.evalJS('window.scrollTo(0, document.documentElement.scrollHeight)'); await sleep(300)
    const bt = await b.evalJS(SCAN)
    const line = (tag, m) => `${tag} docH=${m.docH} scY=${m.scrolled} 带外命中=${m.strip.length} 候选=${m.cand.length}` +
      (m.cand.length ? ' → ' + JSON.stringify(m.cand) : '') + (m.strip.length ? ' !!! ' + JSON.stringify(m.strip.slice(0, 8)) : '')
    console.log(`\n${r}\n  ${line('顶部', at)}\n  ${line('滚底', bt)}`)
    if (at.strip.length || bt.strip.length) { bad++; await b.shot(`docs/美化/dtop4/cover-${r.replace(/[\/]/g, '_')}.png`) }
  }
  R.ok('全商家路由 × 顶栏带：无外部元素在最顶层', bad === 0, { 命中路由数: bad })
  R.dump()
} finally { await b.close() }
