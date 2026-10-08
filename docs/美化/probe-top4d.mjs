// probe-top4d.mjs —— 2026-10-08 第 2 条**决定性复现**：
// 首页/订单页在**空数据**下不滚 ⇒ 顶栏带永远安全。真实商家有 71 单 ⇒ 页会滚。
// 这里 mock 一份**长订单列表**，再看「滚动途中」顶栏带（0–60）里谁在最顶层、
// 以及 `.switch-wrapper`（z-index:10，与顶栏同级、且在 DOM 里靠后）有没有滑进顶栏带。
import { open, sleep, report } from './_cdp.mjs'

const ORDERS = Array.from({ length: 25 }, (_, i) => ({
  OrderId: 'ORD-20261006-' + String(i + 1).padStart(4, '0'),
  ProductInformation: '测试商品 ' + (i + 1) + ' · 规格 A',
  Images: '', CustomerId: 'c' + (100 + i), Price: 129.0 + i,
  PayIf: 1, DeliveryIf: 0, ReceiveIf: 0,
  CreateTime: '2026-10-06 12:00:0' + (i % 10), SettlementDate: ''
}))

const MOCK = (url) => {
  if (/\/order\/order/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: ORDERS, msg: '' }) }
  return { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }
}

const SCAN = `(() => {
  const nav = document.querySelector('.top-nav').getBoundingClientRect()
  const sw = document.querySelector('.switch-wrapper')
  const swr = sw ? sw.getBoundingClientRect() : null
  const outside = []
  for (const y of [5, 20, 40, 55]) {
    for (let x = 300; x < nav.width - 5; x += 4) {
      const el = document.elementFromPoint(x, y)
      if (el && !document.querySelector('.top-nav').contains(el)) {
        const k = el.tagName.toLowerCase() + '.' + String(el.className).trim().split(/\s+/).join('.')
        if (!outside.includes(k + '@y' + y)) outside.push(k + '@y' + y)
      }
    }
  }
  return { scY: Math.round(scrollY), docH: document.documentElement.scrollHeight,
    sw: swr ? { y: Math.round(swr.y), right: Math.round(swr.right), z: getComputedStyle(sw).zIndex, pos: getComputedStyle(sw).position } : null,
    navZ: getComputedStyle(document.querySelector('.top-nav')).zIndex,
    outside }
})()`

const b = await open({ w: 1440, h: 900, mock: MOCK })
const R = report('probe-top4d')
try {
  await b.goto('http://localhost:8080/#/customer', 1500)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeM');sessionStorage.setItem('role','2');sessionStorage.setItem('merchantid','9001');1`)
  await b.goto('http://localhost:8080/#/merchant/order', 600)
  await b.send('Page.reload', { ignoreCache: true }); await sleep(3000)
  for (const route of ['/merchant/order', '/merchant/order/cart']) {
    await b.goto('http://localhost:8080/#' + route, 2200)
    console.log(`\n===== ${route} =====`)
    console.log('行数 =', await b.evalJS(`document.querySelectorAll('.trow, .crow, .orow').length`))
    const H = await b.evalJS('document.documentElement.scrollHeight')
    console.log('docH =', H, ' innerH =', await b.evalJS('innerHeight'))
    for (let y = 0; y <= Math.max(0, H - 900); y += 200) {
      await b.evalJS(`window.scrollTo(0, ${y})`); await sleep(120)
      const m = await b.evalJS(SCAN)
      console.log(`scY=${m.scY} sw=${JSON.stringify(m.sw)} navZ=${m.navZ} 带外命中=${JSON.stringify(m.outside)}`)
      if (m.outside.length) { await b.shot(`docs/美化/dtop4/scrollcover-${m.scY}.png`); break }
    }
  }
  R.ok('滚动途中顶栏带无外部元素', true, '见上表')
  R.dump()
} finally { await b.close() }
