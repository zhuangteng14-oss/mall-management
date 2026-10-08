// probe-glaze.mjs —— 主页轮播图背景上的「天青」二字：取证 + 修复后复验。
// 用法：node docs/美化/probe-glaze.mjs [before|after]
import { open, sleep, report } from './_cdp.mjs'
const R = report(process.argv[2] === 'after' ? 'glaze-after' : 'glaze-before')
const IMG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='640' height='256'%3E%3Crect width='640' height='256' fill='%23b8c9c2'/%3E%3C/svg%3E"
const MOCK = (url) => {
  if (/goodsSelectHome/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: [{ MerchantId: 'M9001', GoodName: '演示商品', UserName: '演示商家', GoodImg: IMG, Price: 129 }] }) }
  if (/goodstype/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: [{ Id: 1, GoodType: '电子用品' }] }) }
  return { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }
}
const b = await open({ w: 1440, h: 900, mock: MOCK })
try {
  await b.goto('http://localhost:8080/#/customer', 1200)
  await b.evalJS("sessionStorage.setItem('loginUsername','probeC');sessionStorage.setItem('role','1');sessionStorage.setItem('customerid','C5001');1")
  await b.goto('http://localhost:8080/#/customer', 2600)
  await sleep(1200)
  const o = JSON.parse(await b.evalJS(`(() => {
    const g = document.querySelector('.glaze')
    const m = document.querySelector('.glaze-mark')
    const gi = document.querySelectorAll('.glaze-media img')
    const bx = (e) => { if (!e) return null; const r = e.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)] }
    return JSON.stringify({
      hasGlaze: !!g, markN: document.querySelectorAll('.glaze-mark').length,
      markText: m ? m.textContent : null, markBox: bx(m), markColor: m ? getComputedStyle(m).color : null,
      glazeText: g ? g.textContent.trim() : null,
      imgN: gi.length, imgBox: bx(gi[0]), imgVisible: gi[0] ? getComputedStyle(gi[0]).opacity : null,
      heroBox: bx(document.querySelector('.hero-main')), glazeBox: bx(g)
    })
  })()`))
  console.log(JSON.stringify(o, null, 1))
  await b.shot('docs/美化/shots/' + (process.argv[2] === 'after' ? 'glaze-after.png' : 'glaze-before.png'))
  R.ok('轮播区存在', o.hasGlaze)
  R.ok('有真图（homeImgList>0）', o.imgN > 0 && o.imgVisible === '1', 'imgN=' + o.imgN + ' opacity=' + o.imgVisible)
  if (process.argv[2] === 'after') {
    R.ok('无 .glaze-mark 元素', o.markN === 0, 'markN=' + o.markN)
    R.ok('轮播区文本不含「天青」', !(o.glazeText || '').includes('天青'), 'glazeText=' + JSON.stringify(o.glazeText))
  } else {
    R.ok('取证：存在 .glaze-mark 且文本为「天青」', o.markN === 1 && o.markText === '天青', 'markText=' + o.markText)
    R.ok('取证：它压在图之上（图已 opacity:1 仍在）', o.imgVisible === '1' && o.markBox !== null, 'markBox=' + JSON.stringify(o.markBox))
  }
} finally { await b.close(); R.dump() }
