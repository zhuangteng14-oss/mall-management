// probe-top4.mjs —— 2026-10-08 第 1/2 条：顶栏搜索框几何 + 顶栏是否被别的层盖住。
// 跑：node docs/美化/probe-top4.mjs   （需 8080 dev server；8088 由 mock 就地伪造）
import { open, sleep, report } from './_cdp.mjs'

const MOCK = (url, method) => {
  if (method === 'OPTIONS') return { status: 200, body: '' }
  return { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }
}

const MEASURE = `(() => {
  const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height)} }
  const nav = document.querySelector('.top-nav')
  const out = { nav: r(nav), parts: {}, cover: {} }
  for (const sel of ['.nav-left','.nav-search','.nav-right','.search-input','.search-btn']) out.parts[sel] = r(document.querySelector(sel))
  out.parts.navRightItems = [...document.querySelectorAll('.nav-right a')].map((a) => ({ t: a.textContent.trim(), r: r(a) }))
  out.searchCSS = (() => { const i = document.querySelector('.search-input'); const b = document.querySelector('.search-btn')
    if (!i || !b) return null
    const ci = getComputedStyle(i), cb = getComputedStyle(b)
    return { input: { h: ci.height, bg: ci.backgroundColor, border: ci.borderColor, fs: ci.fontSize, br: ci.borderRadius },
             btn: { h: cb.height, bg: cb.backgroundColor, color: cb.color, border: cb.borderColor, fs: cb.fontSize, pad: cb.padding } } })()
  // 顶栏带（y=30）横扫：看每个 x 上最顶层的元素属于谁
  const y = 30, owners = []
  if (nav) { const b = nav.getBoundingClientRect()
    for (let x = 5; x < b.width; x += 8) {
      const el = document.elementFromPoint(x, y)
      const inNav = el && nav.contains(el)
      const key = inNav ? 'top-nav/' + (el.closest('.top-nav > div') ? el.closest('.top-nav > div').className : el.tagName) : (el ? el.className || el.tagName : 'null')
      if (!owners.length || owners[owners.length-1].key !== key) owners.push({ key, from: x })
      else owners[owners.length-1].to = x
    }
    out.cover.strip = owners
    out.cover.outside = owners.filter((o) => !String(o.key).startsWith('top-nav'))
  }
  out.doc = { scrollH: document.documentElement.scrollHeight, innerH: window.innerHeight }
  return out })()`

const b = await open({ w: 1440, h: 900, mock: MOCK })
const R = report('probe-top4')

try {
  // ── A. 匿名客户壳（搜索框在场，无需登录）──
  await b.goto('http://localhost:8080/#/customer', 2500)
  const anon = await b.evalJS(MEASURE)
  console.log('\n[匿名 /customer]', JSON.stringify(anon.parts, null, 1))
  console.log('searchCSS:', JSON.stringify(anon.searchCSS, null, 1))
  console.log('覆盖扫描:', JSON.stringify(anon.cover, null, 1))
  await b.shot('docs/美化/dtop4/01-anon-customer.png')

  // ── B. 商家登录态（伪造 session，然后切到 /merchant + **整页 reload**）──
  // ⚠️ 实测 2026-10-08：`/customer` → `/merchant` **只改 hash ⇒ 同文档导航**，两条记录的
  //    `component` 是同一个 `AppShell` ⇒ Vue 复用实例、`setup` 不再跑（`navFor` 调一次）
  //    ⇒ 顶栏仍是匿名表。伪造登录态**必须配一次整页 reload**（`main.js` 重跑、壳重挂）。
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeM');sessionStorage.setItem('role','2');sessionStorage.setItem('merchantid','9001');sessionStorage.setItem('customerid','');1`)
  await b.goto('http://localhost:8080/#/merchant', 800)
  await b.send('Page.reload', { ignoreCache: true })
  await sleep(3000)
  const mer = await b.evalJS(MEASURE)
  console.log('\n[伪造登录 /merchant]', JSON.stringify(mer.parts, null, 1))
  console.log('覆盖扫描:', JSON.stringify(mer.cover, null, 1))
  await b.shot('docs/美化/dtop4/02-merchant-home.png')

  // ── C. 商家订单中心（📋/🛒 开关在场）——
  // ⚠️ 同样只改 hash，但这里是**同一条父记录的子路由**（本来就是复用），登录态已在上一步落地 ⇒ 够。
  await b.goto('http://localhost:8080/#/merchant/order', 2500)
  const ord = await b.evalJS(MEASURE)
  console.log('\n[商家 订单中心]', JSON.stringify(ord.parts, null, 1))
  console.log('覆盖扫描:', JSON.stringify(ord.cover, null, 1))
  await b.shot('docs/美化/dtop4/03-merchant-order.png')

  R.ok('顶栏高 = 60', mer.nav && mer.nav.h === 60, mer.nav)
  R.ok('顶栏内无外部元素覆盖（商家首页）', (mer.cover.outside || []).length === 0, mer.cover.outside)
  R.ok('顶栏内无外部元素覆盖（订单中心）', (ord.cover.outside || []).length === 0, ord.cover.outside)
  R.ok('搜索框与 nav-right 不重叠', mer.parts['.nav-search'] && mer.parts['.nav-right'] &&
       mer.parts['.nav-search'].x + mer.parts['.nav-search'].w <= mer.parts['.nav-right'].x + 1,
       { search: mer.parts['.nav-search'], right: mer.parts['.nav-right'] })
  R.dump()
} finally { await b.close() }
