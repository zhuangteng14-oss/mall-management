// probe-b5-a4-flex-exp.mjs —— A4 券卡宽度**反证实验**（2026-10-12）。
// 假设：`.coupons > .coupon` 的 `flex: 0 0 calc(...)` 生效了，但 flex item 的
// `min-width: auto`（自动最小尺寸 = min-content）把宽度**顶托**在内容宽度上，
// 于是 `flex-basis` 那个 132.1px 从来没被用上。
// 判据：把卡片 `min-width` 手工压成 0 之后，宽度应当**跳到** (容器−12)/2.3。
import { open, sleep } from './_cdp.mjs'

const Q = { Id: 121, MerchantId: '5N1k5K2guh', GoodName: 'oppo12', Price: 1886, GoodImg: '', GoodType: '电子用品', ProductCode: 'OP2' }
const qs = (o) => Object.entries(o).map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&')

const M = `(() => {
  const wrap = document.querySelector('.r-coupons .coupons')
  const cards = [...wrap.querySelectorAll(':scope > .coupon')]
  const wr = wrap.getBoundingClientRect()
  return {
    wrapW: +wr.width.toFixed(1),
    basis0: getComputedStyle(cards[0]).flexBasis,
    minW: cards.map(c => getComputedStyle(c).minWidth),
    widths: cards.map(c => +c.getBoundingClientRect().width.toFixed(1)),
    lefts: cards.map(c => +(c.getBoundingClientRect().left - wr.left).toFixed(1)),
    minContent: cards.map(c => +c.getBoundingClientRect().width.toFixed(1)),
    overflow: cards.map(c => c.scrollWidth - c.clientWidth),
  }
})()`

const b = await open({ w: 1440, h: 900, headed: false })
await b.goto('http://localhost:8080/', 2500)
await b.evalJS(`(() => { sessionStorage.setItem('username','1111'); sessionStorage.setItem('customerid','uDopHnCeLS'); sessionStorage.setItem('role','1'); return 1 })()`)
await b.goto(`http://localhost:8080/?e=${Date.now().toString(36)}#/product?${qs(Q)}`, 2000)
let m = null
for (let i = 0; i < 30; i++) { m = await b.evalJS(M); if (m && m.widths.length >= 3) break; await sleep(500) }
console.log('── 改前 ──\n' + JSON.stringify(m, null, 1))

await b.evalJS(`[...document.querySelectorAll('.r-coupons .coupons > .coupon')].forEach(c => { c.style.minWidth = '0' }); 1`)
await sleep(500)
const after = await b.evalJS(M)
console.log('── 手工把卡片 min-width 压成 0 之后 ──\n' + JSON.stringify(after, null, 1))

const want = (m.wrapW - 12) / 2.3
const third = after.widths.length >= 3 ? +(after.wrapW - after.lefts[2]).toFixed(1) : null
console.log('\n按容器算的应有宽 = (%.1f − 12)/2.3 = %.1f' % [m.wrapW, want])
console.log('改后宽度 = %s ⇒ %s', JSON.stringify(after.widths), after.widths.every((w) => Math.abs(w - want) < 1.5) ? '✅ 与公式吻合' : '❌ 仍不符')
console.log('改后第 3 张露头 = %s px（占整张 %.2f）', third, third === null ? 0 : third / want)
await b.shot('C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/out-b5-a4-flexexp-minwidth0.png')
await b.close()
