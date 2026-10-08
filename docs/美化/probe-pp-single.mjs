// probe-pp-single.mjs —— 弹窗图片区「单型号」专项：大图仍在 + 小图排也在（1440 / 820 两档各验一次）。
// 用法：node docs/美化/probe-pp-single.mjs
import { open, sleep, report } from './_cdp.mjs'
const R = report('pp-single')
const IMG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='64' height='64'%3E%3Crect width='64' height='64' fill='%23c9d6d1'/%3E%3C/svg%3E"
const MOCK = (url) => {
  if (/goodsSelectHome/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: [{ MerchantId: 'M9001', GoodName: '演示商品', UserName: '演示商家', GoodImg: IMG, Price: 129 }] }) }
  if (/GoodsProductCodeSelect/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: ['P001'] }) }   // ← 只有一个型号
  if (/productCodePrice/.test(url)) return { status: 200, body: JSON.stringify({ success: true, price: 19.9, goodImg: IMG }) }
  return { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }
}
const SNAP = `(() => {
  const bx = (e) => { if (!e) return null; const r = e.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)] }
  const m = document.querySelector('.popup-content .p-media')
  const img = document.querySelector('.popup-content .p-media .thumb img')
  const tb = document.querySelector('.popup-content .p-media .thumb')
  const pts = [...document.querySelectorAll('.popup-content .pt')]
  const pi = pts[0] ? pts[0].querySelector('img') : null
  return JSON.stringify({
    media: !!m, mediaBox: bx(m), thumbBox: bx(tb), bigBox: bx(img),
    bigSrc: img ? img.getAttribute('src') : null,
    thumbs: document.querySelectorAll('.popup-content .p-thumbs').length,
    ptN: pts.length, ptBox: pts[0] ? bx(pts[0]) : null, ptOn: pts[0] ? pts[0].classList.contains('on') : null,
    ptSrc: pi ? pi.getAttribute('src') : null,
    overX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    型号按钮: document.querySelectorAll('.popup-content .p-opt .md').length,
    行数: document.querySelectorAll('.popup-content .p-row').length
  })
})()`
const run = async (b, label, wantBig, wantPt) => {
  const o = JSON.parse(await b.evalJS(SNAP))
  console.log('\n[' + label + '] ' + JSON.stringify(o))
  R.ok(label + '：大图有 src', !!o.bigSrc, 'bigSrc=' + String(o.bigSrc).slice(0, 30))
  R.ok(label + '：大图容器真是 ' + wantBig + ' 宽', o.thumbBox && o.thumbBox[2] === wantBig, 'thumbBox=' + JSON.stringify(o.thumbBox))
  R.ok(label + '：大图可见且比容器少 2px 边框', o.bigBox && o.bigBox[2] === wantBig - 2 && o.bigBox[3] === wantBig - 2, 'bigBox=' + JSON.stringify(o.bigBox))
  R.ok(label + '：小图排存在（单型号也有）', o.thumbs === 1, 'thumbs=' + o.thumbs)
  R.ok(label + '：小图恰好 1 枚', o.ptN === 1, 'ptN=' + o.ptN)
  R.ok(label + '：小图是 ' + wantPt + ' 见方且在排里', o.ptBox && o.ptBox[2] === wantPt && o.ptBox[3] === wantPt, 'ptBox=' + JSON.stringify(o.ptBox))
  R.ok(label + '：大图 src === 小图 src', o.bigSrc === o.ptSrc, 'same=' + (o.bigSrc === o.ptSrc))
  R.ok(label + '：无横向溢出', o.overX <= 0, 'overX=' + o.overX)
  R.ok(label + '：型号只有一个', o.型号按钮 === 1, '型号=' + o.型号按钮)
  return o
}
const b = await open({ w: 1440, h: 900, mock: MOCK })
try {
  await b.goto('http://localhost:8080/#/customer', 1200)
  await b.evalJS("sessionStorage.setItem('loginUsername','probeC');sessionStorage.setItem('role','1');sessionStorage.setItem('customerid','C5001');1")
  await b.goto('http://localhost:8080/#/product?MerchantId=M9001&GoodName=' + encodeURIComponent('演示商品') + '&Id=1', 2400)
  await b.evalJS(`(() => { const bs = [...document.querySelectorAll('.acts .b')]; (bs.find((x) => x.textContent.trim() === '立即购买') || bs[0]).click(); return 1 })()`)
  await sleep(1400)
  await run(b, '1440', 232, 48)
  await b.shot('docs/美化/dpp/single-media-1440.png')

  await b.send('Emulation.setDeviceMetricsOverride', { width: 820, height: 900, deviceScaleFactor: 1, mobile: false })
  await b.goto('http://localhost:8080/#/product?MerchantId=M9001&GoodName=' + encodeURIComponent('演示商品') + '&Id=1', 2400)
  await b.evalJS(`(() => { const bs = [...document.querySelectorAll('.acts .b')]; (bs.find((x) => x.textContent.trim() === '立即购买') || bs[0]).click(); return 1 })()`)
  await sleep(1400)
  await run(b, '820', 88, 32)
  await b.shot('docs/美化/dpp/single-media-820.png')
  await b.send('Emulation.clearDeviceMetricsOverride')
} finally { await b.close(); R.dump() }
