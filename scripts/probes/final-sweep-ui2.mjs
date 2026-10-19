// final-sweep-ui2.mjs —— 补测：签到（等渲染稳定）、店铺页商品卡标签（全部 tab）、详情主色（每次整页重载）2026-10-19
import { open, sleep } from '../../docs/美化/_cdp.mjs'

// 探针账号口令从环境变量取（2026-10-19）：仓库内不落明文口令。
// 运行：PROBE_M_ACC=... PROBE_M_PWD=... node scripts/probes/<本文件>
const ACC = process.env.PROBE_M_ACC
const PWD = process.env.PROBE_M_PWD
if (!ACC || !PWD) { console.error('缺少 PROBE_M_ACC / PROBE_M_PWD'); process.exit(1) }

const BASE = 'http://localhost:8080'
const R = (t, v) => console.log(t.padEnd(46) + JSON.stringify(v))
const q = (o) => new URLSearchParams(o).toString()

const b = await open({ w: 1440, h: 900 })
try {
  await b.goto(BASE + '/#/login')
  await sleep(800)
  await b.evalJS(`
    const set = (sel, v) => { const el = document.querySelector(sel); const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) };
    set('#acc', ${JSON.stringify(ACC)}); set('#pwd', ${JSON.stringify(PWD)});
    [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录' && x.className.includes('btn-block')).click();
    'clicked'
  `)
  await sleep(2500)

  // ── 签到 ──
  await b.evalJS(`location.hash = '#/merchant/user/member'; 'go'`)
  await sleep(4500)
  R('sign btn before', await b.evalJS(`(() => { const x = [...document.querySelectorAll('button')].find(y => y.innerText.includes('签到')); return x ? { text: x.innerText, disabled: x.disabled } : null })()`))
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('button')].find(y => y.innerText.includes('签到')); if (x && !x.disabled) x.click(); return 'clicked' })()`)
  await sleep(2600)
  R('sign btn after', await b.evalJS(`(() => { const x = [...document.querySelectorAll('button')].find(y => y.innerText.includes('签到')); return x ? { text: x.innerText, disabled: x.disabled } : null })()`))
  R('mc msg after sign', await b.evalJS(`[...document.querySelectorAll('.mc-msg')].map(x => x.innerText)`))

  await b.evalJS(`location.hash = '#/merchant/user/info'; 'go'`)
  await sleep(3000)
  R('points log panel after sign', await b.evalJS(`(() => { const p = [...document.querySelectorAll('.panel')].find(x => x.innerText.includes('积分动态')); return p ? p.innerText.replace(/\\n+/g, ' / ').slice(0, 260) : null })()`))

  // ── 店铺页商品卡标签（点「全部」tab）──
  await b.evalJS(`location.hash = '#/shop?MerchantId=5N1k5K2guh'; 'go'`)
  await sleep(3000)
  await b.evalJS(`(() => { const t = [...document.querySelectorAll('.tabs .tab')].find(x => x.innerText.includes('全部')); if (t) t.click(); return 'all' })()`)
  await sleep(1500)
  R('shop 121 kind', await b.evalJS(`(() => { const c = [...document.querySelectorAll('.pcard')].find(x => x.innerText.includes('oppo12')); if (!c) return null; const k = c.querySelector('.pcard-kind'); return k ? { text: k.innerText, cls: k.className, color: getComputedStyle(k).color } : null })()`))

  await b.evalJS(`location.hash = '#/shop?MerchantId=dRD3gqtbFr'; 'go'`)
  await sleep(3000)
  await b.evalJS(`(() => { const t = [...document.querySelectorAll('.tabs .tab')].find(x => x.innerText.includes('全部')); if (t) t.click(); return 'all' })()`)
  await sleep(1500)
  R('shop 82 kind', await b.evalJS(`(() => { const c = [...document.querySelectorAll('.pcard')].find(x => x.innerText.includes('智能手环 5 代')); if (!c) return null; const k = c.querySelector('.pcard-kind'); return k ? { text: k.innerText, cls: k.className, color: getComputedStyle(k).color } : null })()`))

  // ── 详情页主色：每次改 hash 后整页重载，避开「同路由复用组件」的既有行为 ──
  const g121 = { Id: '121', MerchantId: '5N1k5K2guh', GoodName: 'oppo12', Price: '1886', GoodImg: 'http://localhost:8088/img/色款.jpg', GoodType: '电子用品', ProductCode: 'OP2' }
  await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(g121))}; location.reload(); 'reload'`)
  await sleep(3800)
  R('detail 121 root class', await b.evalJS(`(document.querySelector('.goods-detail-page') || {}).className`))
  R('detail 121 price color', await b.evalJS(`(() => { const p = document.querySelector('.price'); return p ? getComputedStyle(p).color : null })()`))

  const g82 = { Id: '82', MerchantId: 'dRD3gqtbFr', GoodName: '智能手环 5 代 血氧版', Price: '249', GoodImg: 'https://picsum.photos/seed/GD-107-0959/400', GoodType: '电子用品', ProductCode: 'GD-107-0959' }
  await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(g82))}; location.reload(); 'reload'`)
  await sleep(3800)
  R('detail 82 root class', await b.evalJS(`(document.querySelector('.goods-detail-page') || {}).className`))
  R('detail 82 price color', await b.evalJS(`(() => { const p = document.querySelector('.price'); return p ? getComputedStyle(p).color : null })()`))
} finally {
  await b.close()
}
