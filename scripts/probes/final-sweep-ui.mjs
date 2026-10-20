// final-sweep-ui.mjs —— 终局清道夫 + 补充二 的真实 UI 点检（2026-10-19）
// 覆盖：个人中心资产（积分/收藏/未读/经营等级/积分流水）、签到、领券、收藏、列表标签、详情主色。
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
  // ── 登录（商家探针账户，customerid 为 NULL ⇒ 正好验证三键兜底）──
  await b.goto(BASE + '/#/login')
  await sleep(800)
  await b.evalJS(`
    const set = (sel, v) => { const el = document.querySelector(sel); const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) };
    set('#acc', ${JSON.stringify(ACC)}); set('#pwd', ${JSON.stringify(PWD)});
    [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录' && x.className.includes('btn-block')).click();
    'clicked'
  `)
  await sleep(2500)
  R('login hash', await b.evalJS('location.hash'))

  // ── 个人中心（商家壳）──
  await b.evalJS(`location.hash = '#/merchant/user/info'; 'go'`)
  await sleep(2800)
  R('asset rows', await b.evalJS(`[...document.querySelectorAll('.ledger-row')].map(r => r.innerText.replace(/\\n+/g, ' / '))`))
  R('ledger padding-bottom', await b.evalJS(`(() => { const l = document.querySelector('.ledger'); return l ? getComputedStyle(l).paddingBottom : null })()`))
  R('fav entry cell', await b.evalJS(`(() => { const c = [...document.querySelectorAll('.entry-cell')].find(x => x.innerText.includes('我的收藏')); return c ? c.innerText.replace(/\\n+/g, ' / ') : null })()`))
  R('points log panel', await b.evalJS(`(() => { const p = [...document.querySelectorAll('.panel')].find(x => x.innerText.includes('积分动态')); return p ? p.innerText.replace(/\\n+/g, ' / ').slice(0, 220) : null })()`))
  R('placeholder text gone', await b.evalJS(`!document.body.innerText.includes('积分端点')`))

  // ── 签到（会员中心）──
  await b.evalJS(`location.hash = '#/merchant/user/member'; 'go'`)
  await sleep(2600)
  R('member overview err', await b.evalJS(`(() => { const e = [...document.querySelectorAll('.mc-msg.bad')].map(x => x.innerText); return e })()`))
  R('sign button before', await b.evalJS(`(() => { const x = [...document.querySelectorAll('.mc-actions button')][0]; return x ? { text: x.innerText, disabled: x.disabled } : null })()`))
  await b.evalJS(`(() => { const x = [...document.querySelectorAll('.mc-actions button')][0]; if (x && !x.disabled) x.click(); return 'clicked' })()`)
  await sleep(2200)
  R('sign button after', await b.evalJS(`(() => { const x = [...document.querySelectorAll('.mc-actions button')][0]; return x ? { text: x.innerText, disabled: x.disabled } : null })()`))
  R('mc msg after sign', await b.evalJS(`[...document.querySelectorAll('.mc-msg')].map(x => x.innerText)`))
  R('points log rows in center', await b.evalJS(`document.querySelectorAll('table tbody tr').length`))

  // ── 领券（券包弹窗「可领」tab → 领取）──
  await b.evalJS(`location.hash = '#/merchant/user/info'; 'go'`)
  await sleep(2400)
  await b.evalJS(`(() => { const r = [...document.querySelectorAll('.ledger-row')].find(x => x.innerText.includes('优惠券')); if (r) r.click(); return 'wallet' })()`)
  await sleep(1600)
  R('wallet open', await b.evalJS(`!!document.querySelector('.w-tabs')`))
  await b.evalJS(`(() => { const t = [...document.querySelectorAll('.w-tab')].find(x => x.innerText.includes('可领')); if (t) t.click(); return 'tab' })()`)
  await sleep(1600)
  R('claim rows', await b.evalJS(`document.querySelectorAll('.w-row').length`))
  R('claim btn before', await b.evalJS(`(() => { const x = document.querySelector('.w-get'); return x ? { text: x.innerText, disabled: x.disabled } : null })()`))
  await b.evalJS(`(() => { const x = document.querySelector('.w-get'); if (x && !x.disabled) x.click(); return 'claim' })()`)
  await sleep(2000)
  R('wallet msg after claim', await b.evalJS(`(document.querySelector('.w-msg') || {}).innerText || null`))

  // ── 收藏（商品详情 ❤ → 收藏页出现）──
  const goods = { Id: '76', MerchantId: 'dRD3gqtbFr', GoodName: '无线蓝牙耳机 声谷标准版', Price: '299', GoodImg: 'https://picsum.photos/seed/GD-101-9552/400', GoodType: '电子用品', ProductCode: 'GD-101-9552' }
  await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(goods))}; 'go'`)
  await sleep(2600)
  R('fav btn before', await b.evalJS(`(() => { const x = document.querySelector('.fav-toggle'); return x ? { text: x.innerText, cls: x.className } : null })()`))
  await b.evalJS(`(() => { const x = document.querySelector('.fav-toggle'); if (x) x.click(); return 'fav' })()`)
  await sleep(1800)
  R('fav btn after', await b.evalJS(`(() => { const x = document.querySelector('.fav-toggle'); return x ? { text: x.innerText, cls: x.className } : null })()`))
  await b.evalJS(`location.hash = '#/merchant/user/favorites'; 'go'`)
  await sleep(2200)
  R('favorites page rows', await b.evalJS(`[...document.querySelectorAll('.fav-card, .uc-card, .fav-row')].map(x => x.innerText.replace(/\\n+/g, ' / ').slice(0, 80))`))
  R('favorites page text', await b.evalJS(`(document.querySelector('.uc-main') || document.body).innerText.replace(/\\n+/g, ' / ').slice(0, 260)`))

  // ── 商品卡标签（店铺页：121 = 秒杀+国补 应只红「秒杀」；82 = 只国补）──
  await b.evalJS(`location.hash = '#/shop?MerchantId=5N1k5K2guh'; 'go'`)
  await sleep(2800)
  R('shop 121 kind', await b.evalJS(`(() => { const c = [...document.querySelectorAll('.pcard')].find(x => x.innerText.includes('oppo12')); if (!c) return null; const k = c.querySelector('.pcard-kind'); return k ? { text: k.innerText, cls: k.className, color: getComputedStyle(k).color } : null })()`))
  await b.evalJS(`location.hash = '#/shop?MerchantId=dRD3gqtbFr'; 'go'`)
  await sleep(2800)
  R('shop 82 kind', await b.evalJS(`(() => { const c = [...document.querySelectorAll('.pcard')].find(x => x.innerText.includes('智能手环 5 代')); if (!c) return null; const k = c.querySelector('.pcard-kind'); return k ? { text: k.innerText, cls: k.className, color: getComputedStyle(k).color } : null })()`))

  // ── 详情页主色 class ──
  const g121 = { Id: '121', MerchantId: '5N1k5K2guh', GoodName: 'oppo12', Price: '1886', GoodImg: 'http://localhost:8088/img/色款.jpg', GoodType: '电子用品', ProductCode: 'OP2' }
  await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(g121))}; 'go'`)
  await sleep(3000)
  R('detail 121 root class', await b.evalJS(`(document.querySelector('.goods-detail-page') || {}).className`))
  R('detail 121 price color', await b.evalJS(`(() => { const p = document.querySelector('.price'); return p ? getComputedStyle(p).color : null })()`))
  const g82 = { Id: '82', MerchantId: 'dRD3gqtbFr', GoodName: '智能手环 5 代 血氧版', Price: '249', GoodImg: 'https://picsum.photos/seed/GD-107-0959/400', GoodType: '电子用品', ProductCode: 'GD-107-0959' }
  await b.evalJS(`location.hash = '#/product?' + ${JSON.stringify(q(g82))}; 'go'`)
  await sleep(3000)
  R('detail 82 root class', await b.evalJS(`(document.querySelector('.goods-detail-page') || {}).className`))
  R('detail 82 price color', await b.evalJS(`(() => { const p = document.querySelector('.price'); return p ? getComputedStyle(p).color : null })()`))
} finally {
  await b.close()
}
