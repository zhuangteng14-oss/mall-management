// final-sweep-cards.mjs —— 店铺「全部」tab 的卡片标签抽样（2026-10-19）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const R = (t, v) => console.log(t.padEnd(30) + JSON.stringify(v, null, 0))
const b = await open({ w: 1440, h: 900 })
try {
  await b.goto(BASE + '/#/shop?MerchantId=dRD3gqtbFr')
  await sleep(3200)
  await b.evalJS(`(() => { const t = [...document.querySelectorAll('.tabs .tab')].find(x => x.innerText.includes('全部')); if (t) t.click(); return 'all' })()`)
  await sleep(1800)
  R('cards', await b.evalJS(`[...document.querySelectorAll('.pcard')].map(c => { const k = c.querySelector('.pcard-kind'); return { name: c.querySelector('.pcard-name').innerText, kind: k ? k.innerText : '', cls: k ? k.className : '', color: k ? getComputedStyle(k).color : '' } })`))
} finally {
  await b.close()
}
