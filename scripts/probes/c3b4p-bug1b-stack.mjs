// c3b4p-bug1b-stack.mjs —— 验「收银台**看得见**」（用户 2026-10-10 报的 Bug 1 **二次修**）。
//
// 【为什么上一版探针不够】`c3b4p-bug1-cashier.mjs` 只断言 `.cscrim .cdlg` **在 DOM 里** ——
//   而用户报的正是「它在 DOM 里、但**被白色弹窗盖住**」。DOM 存在 ≠ 可见。本探针补的就是这一刀：
//   拿 `document.elementFromPoint(收银台中心)` 问「**这一点上最上面的是谁**」——
//   命中 `.cscrim` 内 = 真的可见；命中 `.popup-mask` / `.scrim` = 被盖住（改前就是这一种）。
//
// 口径：**真后端**（localhost:8088）+ **真实 a3 库**（VM）+ 真前端（localhost:8080，live source）。
//   ⚠️ 本探针会**真的建一张待付款单**（orderAdd 落到 VM 库）—— 跑完按输出的订单号清理：
//        DELETE FROM `order` WHERE OrderId='…';
//
// 判据（三条一起才叫「修好了」）：
//   ① 收银台在 DOM 里且含「确认支付」；
//   ② `elementFromPoint` 命中收银台（**层叠**判据 —— 改前会命中 `.popup-mask`）；
//   ③ 两层下层弹窗都已撤：`.popup-mask`（购入模块）与 `.scrim`（结算确认）都不在文档里。
//
// 用法：node scripts/probes/c3b4p-bug1b-stack.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const CUSTOMER = 'pIzTWafaGp'
const MERCHANT = '5N1k5K2guh'
const GOOD = encodeURIComponent('222')
const GTYPE = encodeURIComponent('电子用品')
const URL = `http://localhost:8080/#/product?MerchantId=${MERCHANT}&GoodName=${GOOD}&GoodType=${GTYPE}&Id=20`

/** 在页面里取一次「收银台中心点最上面是谁」的快照。 */
const SNAP = `JSON.stringify((() => {
  const scrim = document.querySelector('.cscrim')
  const dlg = document.querySelector('.cscrim .cdlg')
  const mask = document.querySelector('.popup-mask')
  const confirm = document.querySelector('.scrim')
  let hit = null, hitZ = null, insideCashier = null, hitText = null
  if (dlg) {
    const r = dlg.getBoundingClientRect()
    const x = Math.round(r.left + r.width / 2), y = Math.round(r.top + r.height / 2)
    const el = document.elementFromPoint(x, y)
    hit = el ? (el.className && el.className.toString ? el.className.toString() : el.tagName) : null
    hitZ = el ? getComputedStyle(el).zIndex : null
    insideCashier = !!(el && el.closest && el.closest('.cscrim'))
    hitText = el && el.closest ? (el.closest('.cdlg') || {}).innerText : null
    window.__pt = { x, y }
  }
  return {
    收银台在DOM: !!dlg,
    收银台正文: dlg ? dlg.innerText : null,
    收银台zIndex: scrim ? getComputedStyle(scrim).zIndex : null,
    中心点命中: hit,
    命中元素zIndex: hitZ,
    命中在收银台内: insideCashier,
    命中元素所属收银台正文: hitText,
    购入模块弹窗还在: !!mask,
    购入模块zIndex: mask ? getComputedStyle(mask).zIndex : null,
    结算确认弹窗还在: !!confirm,
    结算弹窗zIndex: confirm ? getComputedStyle(confirm).zIndex : null
  }
})())`

const b = await open({ w: 1440, h: 900 })
try {
  await b.goto('http://localhost:8080/#/login', 1200)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeB1b');sessionStorage.setItem('role','1');sessionStorage.setItem('customerid','${CUSTOMER}');1`)
  await b.evalJS(`window.__alerts=[];window.alert=(m)=>{window.__alerts.push(String(m))};1`)
  await b.goto(URL, 3000)

  const click = async (desc, js) => {
    const r = await b.evalJS(js)
    console.log(`点击【${desc}】：`, r)
    return r
  }

  await click('立即购买', `(() => {
    const bt=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='立即购买')
    if(!bt) return 'NO_BTN'; bt.click(); return 'OK'
  })()`)
  await sleep(1500)

  // 先记一笔「购入模块弹窗确实开着」——否则后面「它关掉了」这条判据没有对照。
  const before = await b.evalJS(`JSON.stringify({
    购入模块弹窗还在: !!document.querySelector('.popup-mask'),
    购入模块zIndex: (document.querySelector('.popup-mask')||{}).style ? getComputedStyle(document.querySelector('.popup-mask')||document.body).zIndex : null
  })`)
  console.log('点【结算】之前：', before)

  await click('结算(.p-confirm)', `(() => {
    const bt=document.querySelector('.p-confirm')
    if(!bt) return 'NO_PCONFIRM'; bt.click(); return 'OK'
  })()`)
  await sleep(500)

  await click('是(.dacts .b.ok)', `(() => {
    const bt=document.querySelector('.dacts .b.ok')
    if(!bt) return 'NO_OK'; bt.click(); return 'OK'
  })()`)

  await sleep(1200)
  const s = JSON.parse(await b.evalJS(SNAP))
  console.log('点【是】之后：', JSON.stringify(s, null, 2))

  const alerts = await b.evalJS('JSON.stringify(window.__alerts)')
  console.log('alerts：', alerts)
  await b.shot('scripts/probes/out-c3b4p-bug1b-stack.png')

  const ref = JSON.parse(before)
  const pass = []
  pass.push(['⓪ 前置：购入模块弹窗在点【结算】前确实开着（对照）', ref.购入模块弹窗还在 === true])
  pass.push(['① 收银台在 DOM 里、含「确认支付」', s.收银台在DOM === true && /确认支付/.test(s.收银台正文 || '')])
  pass.push(['② 收银台中心点**命中收银台自己**（层叠判据 —— 改前命中 .popup-mask）', s.命中在收银台内 === true])
  pass.push(['③ 购入模块弹窗（.popup-mask）已撤', s.购入模块弹窗还在 === false])
  pass.push(['④ 结算确认弹窗（.scrim）已撤', s.结算确认弹窗还在 === false])
  pass.push(['⑤ 全程无 alert', JSON.parse(alerts).length === 0])
  let bad = 0
  for (const [k, ok] of pass) { console.log(`${ok ? 'PASS' : 'FAIL'}  ${k}`); if (!ok) bad++ }
  console.log(bad ? `\n❌ ${bad} 条未过` : '\n✅ 全过')

  const m = String(s.收银台正文 || '').match(/([A-Z]{2,}[A-Z0-9]{10,})/)
  if (m) console.log(`\n⚠️ 本次建的订单号 = ${m[1]}（拿去 VM 库查/清理）`)
} finally {
  await b.close()
}
