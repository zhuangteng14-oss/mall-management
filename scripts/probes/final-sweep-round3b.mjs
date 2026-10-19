// final-sweep-round3b.mjs —— 补测品牌 / 普通两档 H1 + 页签主色（2026-10-19）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const R = (t, v) => console.log(t.padEnd(46) + JSON.stringify(v))
const q = (o) => new URLSearchParams(o).toString()

const b = await open({ w: 1440, h: 1000 })
try {
  await b.goto(BASE + '/')
  await sleep(1200)
  const cases = [
    { tag: 'brand', g: { Id: '130', MerchantId: 'yERU17koBc', GoodName: 'Draft-B7-Demo', Price: '99.9', GoodImg: 'http://localhost:8088/img/draft-b7.png', GoodType: 'electronics', ProductCode: 'DRAFT-B7-001' } },
    { tag: 'normal', g: { Id: '76', MerchantId: 'dRD3gqtbFr', GoodName: '无线蓝牙耳机 声谷标准版', Price: '299', GoodImg: 'https://picsum.photos/seed/GD-101-9552/400', GoodType: '电子用品', ProductCode: 'GD-101-9552' } },
  ]
  for (const c of cases) {
    await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(c.g))}; location.reload(); 'reload'`)
    await sleep(3600)
    R(c.tag + ' root class', await b.evalJS(`(document.querySelector('.goods-detail-page') || {}).className`))
    R(c.tag + ' h1 color', await b.evalJS(`(() => { const x = document.querySelector('.pname'); return x ? getComputedStyle(x).color : null })()`))
    R(c.tag + ' tab on bg/fg/shadow', await b.evalJS(`(() => { const x = document.querySelector('.a-pd-tabs button.on'); if (!x) return null; const s = getComputedStyle(x); return { bg: s.backgroundColor, fg: s.color, shadow: s.boxShadow } })()`))
    R(c.tag + ' pd-sub exists', await b.evalJS(`!!document.querySelector('.pd-sub')`))
  }
} finally {
  await b.close()
}
