// final-sweep-round3.mjs —— 第三轮用户实测 5 项的手验（2026-10-19）
// 覆盖：沙箱充值（商家身份）、身份标签/浅色牌匾对比度、H1 下方冗余行、
//       H1 + 页签随 pdThemeClass 变色。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const R = (t, v) => console.log(t.padEnd(46) + JSON.stringify(v))
const q = (o) => new URLSearchParams(o).toString()

// 探针账号口令从环境变量取：仓库内不落明文口令。
const ACC = process.env.PROBE_M_ACC
const PWD = process.env.PROBE_M_PWD
if (!ACC || !PWD) { console.error('缺少 PROBE_M_ACC / PROBE_M_PWD'); process.exit(1) }

const b = await open({ w: 1440, h: 1000 })
try {
  // ── 登录（商家探针账户，customerid 为 NULL）──
  await b.goto(BASE + '/#/login')
  await sleep(800)
  await b.evalJS(`
    const set = (sel, v) => { const el = document.querySelector(sel); const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) };
    set('#acc', ${JSON.stringify(ACC)}); set('#pwd', ${JSON.stringify(PWD)});
    [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录' && x.className.includes('btn-block')).click();
    'clicked'
  `)
  await sleep(2600)

  // ── 个人中心：身份标签 + 牌匾对比度 ──
  await b.evalJS(`location.hash = '#/merchant/user/info'; 'go'`)
  await sleep(3400)
  R('id badge text', await b.evalJS(`(() => { const x = document.querySelector('.id-badge'); return x ? x.innerText : null })()`))
  R('plaque class', await b.evalJS(`(document.querySelector('.plaque') || {}).className`))
  R('plaque fg/colors', await b.evalJS(`(() => {
    const g = (sel) => { const el = document.querySelector(sel); return el ? getComputedStyle(el).color : null };
    return { level: g('.plaque .member-level'), balanceSmall: g('.plaque-mid .balance small'), link: g('.plaque-link'), name: g('.plaque-bottom .name'), num: g('.plaque-num'), inner: g('.plaque-inner') }
  })()`))
  R('barcode bar color', await b.evalJS(`(() => { const x = document.querySelector('.barcode i'); return x ? getComputedStyle(x).backgroundColor : null })()`))

  // ── 沙箱充值 ──
  const balBefore = await b.evalJS(`(() => { const x = document.querySelector('.plaque-mid .balance'); return x ? x.innerText.replace(/\\n+/g, ' / ') : null })()`)
  R('balance before', balBefore)
  await b.evalJS(`(() => { const x = document.querySelector('.plaque-mid .balance'); if (x) x.click(); return 'open' })()`)
  await sleep(1500)
  R('dialog open', await b.evalJS(`!!document.querySelector('.recharge-dlg')`))
  R('dialog msg before', await b.evalJS(`(document.querySelector('.recharge-dlg .mc-why') || {}).innerText || null`))
  await b.evalJS(`(() => {
    const inp = document.querySelector('.recharge-dlg input[type=number]');
    const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(inp, '10'); inp.dispatchEvent(new Event('input', { bubbles: true }));
    [...document.querySelectorAll('.recharge-dlg button')].find(x => x.innerText.includes('确认充值')).click();
    return 'submitted'
  })()`)
  await sleep(2600)
  R('dialog msg after', await b.evalJS(`(document.querySelector('.recharge-dlg .mc-why') || {}).innerText || null`))
  R('balance in dialog', await b.evalJS(`(() => { const p = document.querySelector('.recharge-dlg .mc-why'); const m = document.querySelector('.recharge-dlg'); return m ? m.innerText.replace(/\\n+/g, ' / ').slice(0, 220) : null })()`))
  R('recharge log rows', await b.evalJS(`document.querySelectorAll('.recharge-log').length`))
  R('plaque balance after', await b.evalJS(`(() => { const x = document.querySelector('.plaque-mid .balance'); return x ? x.innerText.replace(/\\n+/g, ' / ') : null })()`))
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.recharge-dlg button')].find(y => y.innerText.trim() === '关闭'); if (x) x.click(); return 'closed' })()`)
  await sleep(600)

  // ── 商品详情：冗余行 + H1/页签主色 ──
  const g121 = { Id: '121', MerchantId: '5N1k5K2guh', GoodName: 'oppo12', Price: '1886', GoodImg: 'http://localhost:8088/img/色款.jpg', GoodType: '电子用品', ProductCode: 'OP2' }
  await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(g121))}; location.reload(); 'reload'`)
  await sleep(3800)
  R('121 root class', await b.evalJS(`(document.querySelector('.goods-detail-page') || {}).className`))
  R('121 pd-sub exists', await b.evalJS(`!!document.querySelector('.pd-sub')`))
  R('121 h1 color', await b.evalJS(`(() => { const x = document.querySelector('.pname'); return x ? getComputedStyle(x).color : null })()`))
  R('121 tab on bg/fg/shadow', await b.evalJS(`(() => { const x = document.querySelector('.a-pd-tabs button.on'); if (!x) return null; const s = getComputedStyle(x); return { bg: s.backgroundColor, fg: s.color, shadow: s.boxShadow } })()`))

  const g82 = { Id: '82', MerchantId: 'dRD3gqtbFr', GoodName: '智能手环 5 代 血氧版', Price: '249', GoodImg: 'https://picsum.photos/seed/GD-107-0959/400', GoodType: '电子用品', ProductCode: 'GD-107-0959' }
  await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(g82))}; location.reload(); 'reload'`)
  await sleep(3800)
  R('82 root class', await b.evalJS(`(document.querySelector('.goods-detail-page') || {}).className`))
  R('82 pd-sub exists', await b.evalJS(`!!document.querySelector('.pd-sub')`))
  R('82 h1 color', await b.evalJS(`(() => { const x = document.querySelector('.pname'); return x ? getComputedStyle(x).color : null })()`))
  R('82 tab on bg/fg/shadow', await b.evalJS(`(() => { const x = document.querySelector('.a-pd-tabs button.on'); if (!x) return null; const s = getComputedStyle(x); return { bg: s.backgroundColor, fg: s.color, shadow: s.boxShadow } })()`))
} finally {
  await b.close()
}
