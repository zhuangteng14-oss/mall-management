// probe-b5-a4-mock-width.mjs —— 量**设计稿**（`docs/美化/pages/ProductPage.html`）里促销行/券卡的真实几何。
// 目的：把用户说的「应 ~230px，一行 2.5 张」落到稿上核对 —— 稿是不是真这么宽。
import { open } from './_cdp.mjs'

const M = `(() => {
  const promo = document.querySelector('.promo')
  const rows = [...document.querySelectorAll('.promo .r')]
  const wrap = document.querySelector('.coupons')
  const cards = wrap ? [...wrap.querySelectorAll(':scope > .coupon')] : []
  const rd = (e) => e ? +e.getBoundingClientRect().width.toFixed(1) : null
  return {
    innerW: window.innerWidth,
    wrapBox: rd(document.querySelector('.wrap') || document.querySelector('.pd')),
    pd: rd(document.querySelector('.pd')),
    pdCols: document.querySelector('.pd') ? getComputedStyle(document.querySelector('.pd')).gridTemplateColumns : null,
    promoW: rd(promo),
    rowsTag: rows.map((r) => (r.querySelector('.tag') || {}).textContent),
    rowsW: rows.map((r) => { const e = r.querySelector('.tag'); return e ? rd(e.parentElement) : null }),
    couponsW: rd(wrap),
    couponsFlexWrap: wrap ? getComputedStyle(wrap).flexWrap : null,
    cardW: cards.map(rd),
    cardFlex: cards[0] ? getComputedStyle(cards[0]).flex : null,
    cardText: cards.map((c) => c.innerText.replace(/\\s+/g, ' ').trim()),
  }
})()`

const b = await open({ w: 1440, h: 900, headed: false })
await b.goto('file:///C:/Users/86159/Desktop/软件工程/期末项目/docs/美化/pages/ProductPage.html', 2500)
console.log(JSON.stringify(await b.evalJS(M), null, 1))
await b.shot('C:/Users/86159/Desktop/软件工程/期末项目/_work/mock-product-1440.png')
await b.close()
