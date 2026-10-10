// c3b4p-bug1-cashier.mjs —— 验「商品详情页【立即购买】现在会弹收银台」（用户 2026-10-10 报的 Bug 1）。
//
// 口径：**真后端**（localhost:8088）+ **真实 a3 库**（VM）。不 mock —— 要看的就是真链路。
//   ⚠️ 本探针会**真的建一张待付款单**（orderAdd 落到 VM 库）。跑完拿 `订单号` 去查/清理：
//        SELECT OrderId, PayIf, TradeNo, PaymentMethod, PaidAt FROM `order` WHERE OrderId='…';
//        DELETE FROM `order` WHERE OrderId='…';
//
// 判据（本笔修的就是这条）：
//   ① 点完【是】之后，`.cscrim`（CashierDialog 根）**出现** —— 改前它永不出现（直接成已付款单）；
//   ② 该单 `PayIf = 0`（不是 1）⇒ 收银台才有意义（TradeNo / PaymentMethod / PaidAt 应仍是 NULL）；
//   ③ 全程 `alert` 为空（改前会弹「订单提交成功！」并跳页）。
//
// 用法：node scripts/probes/c3b4p-bug1-cashier.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const CUSTOMER = 'pIzTWafaGp'                        // VM 库里有单的客户
const MERCHANT = '5N1k5K2guh'                        // goods.Id=20 的商家
const GOOD = encodeURIComponent('222')               // goods.Id=20 的 GoodName（纯 ASCII）
const GTYPE = encodeURIComponent('电子用品')
const URL = `http://localhost:8080/#/product?MerchantId=${MERCHANT}&GoodName=${GOOD}&GoodType=${GTYPE}&Id=20`

const b = await open({ w: 1440, h: 900 })
try {
  await b.goto('http://localhost:8080/#/login', 1200)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeB1');sessionStorage.setItem('role','1');sessionStorage.setItem('customerid','${CUSTOMER}');1`)
  // ⚠️ alert 会**阻塞**渲染进程 ⇒ 换记录器（同 bug2 探针）。
  await b.evalJS(`window.__alerts=[];window.alert=(m)=>{window.__alerts.push(String(m))};1`)
  await b.goto(URL, 3000)

  const before = await b.evalJS(`JSON.stringify({
    标题: (document.querySelector('.p-title,.p-name,h1')||{}).innerText,
    有立即购买: !!([...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='立即购买'))
  })`)
  console.log('页面态：', before)

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

  await click('结算(.p-confirm)', `(() => {
    const bt=document.querySelector('.p-confirm')
    if(!bt) return 'NO_PCONFIRM'; bt.click(); return 'OK'
  })()`)
  await sleep(500)

  await click('是(.dacts .b.ok)', `(() => {
    const bt=document.querySelector('.dacts .b.ok')
    if(!bt) return 'NO_OK'; bt.click(); return 'OK'
  })()`)

  const sample = async (tag) => {
    const s = await b.evalJS(`JSON.stringify({
      t:${'`'}${tag}${'`'},
      收银台: !!document.querySelector('.cscrim .cdlg'),
      收银台正文: (document.querySelector('.cscrim .cdlg')||{}).innerText,
      订单号: (() => { const m=((document.querySelector('.cscrim .cdlg')||{}).innerText||'').match(/([A-Z]{2,}[A-Z0-9]{10,})/); return m?m[1]:'' })(),
      仍在商品页: location.hash.includes('/product'),
      alerts: window.__alerts
    })`)
    console.log(s)
    return JSON.parse(s)
  }
  await sleep(500); const s1 = await sample('+0.5s')
  await sleep(1500); await sample('+2s')
  await sleep(3000); await sample('+5s')

  await b.shot('scripts/probes/out-c3b4p-bug1.png')

  // ── 判据 ──
  const pass = []
  pass.push(['① 收银台出现（Bug 1 的核心）', s1.收银台 === true])
  pass.push(['② 收银台内有「确认支付」按钮文案', /确认支付/.test(s1.收银台正文 || '')])
  pass.push(['③ 无 alert（改前会弹「订单提交成功！」）', (s1.alerts || []).length === 0])
  let bad = 0
  for (const [k, ok] of pass) { console.log(`${ok ? 'PASS' : 'FAIL'}  ${k}`); if (!ok) bad++ }
  console.log(bad ? `\n❌ ${bad} 条未过` : '\n✅ 全过')
  if (s1.订单号) console.log(`\n⚠️ 本次建的订单号 = ${s1.订单号}（拿去 VM 库查 PayIf 是否为 0，查完可删）`)
} finally {
  await b.close()
}
