// c3b5e-coupon-account.mjs —— C3 块 5「券改**账号口径**」的**前端自检**（2026-10-12）。
//
// 验的是用户裁的那三条里、**只能在浏览器里看出来**的部分：
//   ① 商家端【无名框】的「优惠券」格**可点**了（旧口径下它 `v-if="customerId"` 恒 false ⇒ 不可点的裸 `div`）；
//   ② 点它**真的开** `CouponWalletDialog`（4 个 tab：可领 / 未使用 / 已使用 / 已过期）；
//   ③ 这一格发出去请求的 `Account` 是**登录名**（`login.UserName`），**不是** `user.customerid`；
//   ④ 弹窗 4 个 tab 与客户版**一字不差**（同组件、同数据源、同行为）。
//
// ⚠️ **为什么用 mock 而不是真后端**（同 `probe-b5-b-usercenter.mjs` 的取舍）：
//    a3 profile 闸关（`app.auth.enabled=false`）⇒ `/user/me` 拿不到真身份，而本笔要控的恰恰是
//    「**商家**账号、且 `customerid` 为空」这**一个人格**（真实库里 13 家商家全是这样，但登不进去）。
//    就地伪造 8088 比改库可控。**只伪造、不落数据**；拦截到的请求 URL **全都记下来**当证据。
//
// 用法（**只要前端 8080 活着**，后端不参与）：
//     node scripts/probes/c3b5e-coupon-account.mjs
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'

// 真库实测（2026-10-12）：`user` 表里 `merchantid='5N1k5K2guh'` 那行的 `username='2222'`、
// `customerid` 为 **NULL** —— 正是「有登录名、没有客户号」的商家，旧口径下这一格必然落空态。
const MERCHANT_ID = '5N1k5K2guh'
const LOGIN_NAME = '2222'

/** 记录所有被拦截的请求（URL + 方法 + 表单体），供「Account 是登录名」那条断言用。
 *  ⚠️ `Account` 在 **POST 体**里（`marketing.js` 用 `URLSearchParams` 发送），**不在 query 上** ——
 *     第一版只存 URL，`Account` 全取到 null（这就是那条 FAIL 的成因，探针自己的缺陷，不是页面的）。 */
const HITS = []

/** `/user/me` 的载荷 —— `Result.ok(payload)` 是**平铺**（`{success:true, ...payload}`）。 */
const ME = {
  success: true, msg: 'ok',
  role: '2', username: LOGIN_NAME, merchantid: MERCHANT_ID, customerid: '',
  image: '', usertype: '商家', gender: '', age: null,
  email: '', address: '', selfintroduction: '',
  DowngradeState: 'none', ScheduledDowngradeAt: null,
  FrozenAt: null, FreezeUntil: null, DowngradeReason: null,
}

/** 券相关一律**空 wallet** —— 本探针要的是「空态如实画」，不是有券的样子。 */
const MOCK = (url, method, postData) => {
  HITS.push({ url: url, method: method, postData: postData || '' })
  if (url.includes('/user/me')) return { body: JSON.stringify(ME) }
  if (url.includes('/merchant/myApplications')) {
    return { body: JSON.stringify({ success: true, total: 0, limit: 1, list: [] }) }
  }
  if (url.includes('/marketing/myCoupons') || url.includes('/marketing/coupons')) {
    return { body: JSON.stringify({ success: true, redDot: 0, list: [] }) }
  }
  // 兜底：**必须带 `list`**（只回 `{"success":true}` 时壳里的列表组件会抛 length 异常 —— 那是 mock
  // 形状问题不是页面缺陷，`probe-b5-b-usercenter.mjs` 的注释里记过这次对照）。
  return { body: '{"success":true,"list":[],"total":0,"limit":0}' }
}

// ⚠️ 整段交给 evalJS —— 里面不能有反引号、不能有 ${（否则模板提前闭合，报错会指向注释行）。
const MEASURE_CELL = `(function(){
  var all = Array.prototype.slice.call(document.querySelectorAll('[role=button]'));
  var cell = null;
  for (var i = 0; i < all.length; i++) {
    if (all[i].innerText && all[i].innerText.indexOf('优惠券') >= 0) { cell = all[i]; break; }
  }
  var out = { roleButtonCount: all.length, found: !!cell };
  if (cell) {
    var r = cell.getBoundingClientRect();
    out.tag = cell.tagName;
    out.cls = String(cell.className);
    out.tabindex = cell.getAttribute('tabindex');
    out.title = cell.getAttribute('title');
    out.text = cell.innerText.replace(/\\n+/g, ' | ');
    out.rect = [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
    out.cx = Math.round(r.left + r.width / 2);
    out.cy = Math.round(r.top + r.height / 2);
    out.visible = r.width > 0 && r.height > 0 && r.top >= 0 && r.top < window.innerHeight;
  }
  out.dlgOpen = !!document.querySelector('.cdlg');
  // 旧口径的「空态裸 div」的特征：不可点、无 role —— 顺带量一下它还在不在
  out.bareCouponDivs = Array.prototype.filter.call(document.querySelectorAll('.asset-card'), function(e){
    return !e.getAttribute('role') && e.innerText.indexOf('优惠券') >= 0;
  }).length;
  return JSON.stringify(out);
})()`

const MEASURE_DLG = `(function(){
  var d = document.querySelector('.cdlg');
  if (!d) return JSON.stringify({ open: false });
  var head = d.querySelector('.cdlg-h span');
  var tabs = Array.prototype.map.call(d.querySelectorAll('.w-tab'), function(e){
    return e.innerText.replace(/\\n+/g, ' ').replace(/\\s+/g, ' ').trim();
  });
  var tip = d.querySelector('.empty-tip');
  return JSON.stringify({
    open: true,
    title: head ? head.innerText : null,
    tabs: tabs,
    activeTab: (d.querySelector('.w-tab-on') || {}).innerText,
    tip: tip ? tip.innerText : null,
    scrim: !!document.querySelector('.cscrim')
  });
})()`

const CLICK_CELL = `(function(){
  var all = Array.prototype.slice.call(document.querySelectorAll('[role=button]'));
  for (var i = 0; i < all.length; i++) {
    if (all[i].innerText && all[i].innerText.indexOf('优惠券') >= 0) { all[i].click(); return true; }
  }
  return false;
})()`

const b = await open({ w: 1440, h: 1000, mock: MOCK })
const R = report('c3b5e-coupon-account')

try {
  // ── 商家壳的用户中心（`/merchant/user/info` → `UserInfoPage.vue`）────────────────
  await b.goto('http://localhost:8080/#/merchant/user/info', 5000)
  await sleep(800)

  const cell = JSON.parse(await b.evalJS(MEASURE_CELL))
  await b.shot(EV + 'b5e-merchant-coupon-cell.png')

  R.ok('① 商家壳里找到了「优惠券」格', cell.found === true, cell)
  R.ok('① 那一格是**可点**的（`div[role=button]` + `tabindex`）',
    cell.tag === 'DIV' && cell.tabindex === '0', { tag: cell.tag, tabindex: cell.tabindex })
  R.ok('① 旧口径的「不可点裸 div」空态**已不在**', cell.bareCouponDivs === 0, cell.bareCouponDivs)
  R.ok('① 那一格在视口里（可点得到）', cell.visible === true, cell.rect)
  R.ok('① 弹窗此刻是**关**的', cell.dlgOpen === false)

  // ── ③ 账号口径：请求里 `Account` 必须是**登录名** `2222` ─────────────────────────
  const myCouponsHit = HITS.filter((h) => h.url.includes('/marketing/myCoupons'))
  const acctIn = (h) => {
    const m = /(?:^|&)Account=([^&]*)/.exec(h.postData)
    return m ? decodeURIComponent(m[1]) : null
  }
  const accts = myCouponsHit.map(acctIn)
  R.ok('③ 商家壳**发出了**券包摘要请求（旧口径下 `customerid` 空 ⇒ 一个请求都不发）',
    myCouponsHit.length > 0, myCouponsHit.length)
  R.ok('③ 那个请求的 `Account` 是**登录名** ' + LOGIN_NAME, accts.length > 0 && accts.every((a) => a === LOGIN_NAME), accts)
  R.ok('③ `Account` **不是**商家号/客户号（没退回旧口径）',
    accts.every((a) => a !== MERCHANT_ID), { accts: accts, merchantId: MERCHANT_ID })

  // ── ② 点一下 → 弹窗真的开 ──────────────────────────────────────────────────────
  const clicked = await b.evalJS(CLICK_CELL)
  await sleep(1200)
  const dlg = JSON.parse(await b.evalJS(MEASURE_DLG))
  await b.shot(EV + 'b5e-merchant-wallet-dialog.png')

  R.ok('② 点击返回 true', clicked === true)
  R.ok('② 弹窗真的开了（`.cdlg` + 遮罩）', dlg.open === true && dlg.scrim === true, dlg)
  R.ok('② 标题是「我的券包」', dlg.title === '我的券包', dlg.title)
  R.ok('④ 4 个 tab，且与客户版**同一套**（可领 / 未使用 / 已使用 / 已过期）',
    JSON.stringify(dlg.tabs) === JSON.stringify(['可领', '未使用', '已使用', '已过期']), dlg.tabs)
  R.ok('④ 默认落在「可领」', (dlg.activeTab || '').indexOf('可领') >= 0, dlg.activeTab)
  R.ok('④ 空数据 ⇒ **如实空态**（不造假、不画假券）',
    typeof dlg.tip === 'string' && dlg.tip.length > 0, dlg.tip)

  console.log('--- 拦截到的 8088 请求 ---')
  HITS.forEach((h) => console.log('  ' + h.method + ' ' + h.url.replace('http://localhost:8088', '')
    + (h.postData ? '   body=' + h.postData : '')))
  console.log('--- 格子 ---')
  console.log('  ' + JSON.stringify(cell))
  console.log('--- 弹窗 ---')
  console.log('  ' + JSON.stringify(dlg))
} finally {
  const bad = R.dump()
  await b.close()
  process.exitCode = bad === 0 ? 0 : 1
}
