// probe-b5h-matrint-merchant-pages.mjs —— `Math.rint` 那处笔误的**另两个受害页面**页面级验证
// （C3 块 5 · 2026-10-12）
//
// 背景：`Math.rint` 是 **Java 的**，JS 的 `Math` 上没有这个函数 ⇒ 一调用就
//   `TypeError: Math.rint is not a function`。块 5〈折扣〉/〈秒杀〉那一批**写错了三处**：
//     ① `ProductPage.vue:520`（`discountRate`）—— 用户报的「详情页标题在、价/型号/图空」就是它
//        （复现在 `docs/美化/probe-b5h-bug2b-detail-page.mjs`）
//     ② `GoodsAdd.vue:526`（`fmtRate`）—— 被 `discountError` / `discountHint` 两个 computed 调用
//     ③ `SeckillApplyPage.vue:155`（`qtyBad`）—— 商家一填限量就轮到它
//
// 为什么 ②③ **必须**单独上页面验一次，不能拿「eslint 干净 + build 过」顶替：
//   `Math.rint` 恰恰是**从 eslint 底下溜过去**的 —— `no-undef` 只认**变量**，`Math.rint` 是
//   「已定义对象上的未知成员」，规则管不着；build 更不会执行这行代码。⇒ 只有**真触发一次**才算证。
//
// 手法：进页面 → 用**原生 setter + `input`/`change` 事件**喂值（Vue 的 `v-model` 只认这两种事件，
//       直接改 `.value` 不触发）→ 读提示行文案 + 看有没有未捕获异常。
//
// ⚠️ 前置（两件都踩过，别再踩）：
//   1. 登录态要在**导航之前**就位 —— `/merchant` 那族带 `meta.roles`，守卫在导航当下就判，
//      导航完再贴 `sessionStorage` 已经晚了一步（会被踢到 `#/login?redirect=…`）。
//   2. 读提示行要读**本字段那一块**里的 `.tip`/`.hint` —— 页面上第一个 `.hint` 是别的字段的
//      （第一版读到的就是「同一商品的不同规格靠它区分」，与折扣无关）。
//
// 用法：node docs/美化/probe-b5h-matrint-merchant-pages.mjs
// 前提：dev server 8080 + 后端 8088（a3）在跑；**不 mock**。
import { open, sleep, report } from './_cdp.mjs'

const LOGIN = `(function () {
  sessionStorage.setItem('loginUsername','1111')
  sessionStorage.setItem('customerid','uDopHnCeLS')
  sessionStorage.setItem('merchantid','dRD3gqtbFr')
  sessionStorage.setItem('role','2')
  return sessionStorage.getItem('role')
})()`

/** 原生 setter + 事件（Vue v-model 的入口，见头注）。 */
const setInput = (sel, val) => `(function () {
  var el = document.querySelector('${sel}')
  if (!el) return 'no-el'
  var proto = el.tagName === 'SELECT' ? window.HTMLSelectElement.prototype : window.HTMLInputElement.prototype
  var setter = Object.getOwnPropertyDescriptor(proto, 'value').set
  setter.call(el, '${val}')
  el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }))
  return 'ok:' + el.value
})()`

/** **对照**：在本页运行时里把一个 `Math.rint(...)` 放进 try 里调一次。
 *  ⚠️ 这一条是给下面两条断言**撑腰**用的 —— 它证明「没抛异常」不是「这段代码压根没跑」：
 *  同一个运行时、同一个 `Math`，`Math.rint` 确实是个不存在的函数。 */
const PROBE_RINT = `(function () {
  try { Math.rint(1.5); return 'NO-THROW' } catch (e) { return e.message }
})()`

/** 折扣那块字段里的提示行（**不是**页面上第一个 `.hint`，见头注 2）。 */
const DISCOUNT_HINT = `(function () {
  var o = document.querySelector('#g-original')
  var f = o && o.closest('.f')
  var e = f && (f.querySelector('.tip') || f.querySelector('.hint'))
  return e ? e.textContent.trim() : '(无)'
})()`

const b = await open({ w: 1440, h: 1000 })
const errs = []
b.ws.on('message', (raw) => {
  let m = null
  try { m = JSON.parse(raw) } catch (e) { return }
  const p = m.params || {}
  if (m.method === 'Runtime.exceptionThrown') {
    errs.push(p.exceptionDetails && p.exceptionDetails.exception
      ? (p.exceptionDetails.exception.description || '').split('\n')[0] : p.exceptionDetails.text)
  }
  if (m.method === 'Runtime.consoleAPICalled' && p.type === 'error') {
    errs.push('console: ' + (p.args || []).map((a) => a.value || a.description || '').join(' ').slice(0, 200))
  }
})

/** 进一张面：先落到同源任意页贴登录态，再进目标 hash（见头注 1）。 */
const face = async (hash, wait) => {
  await b.goto('about:blank', 250)
  await b.goto('http://localhost:8080/#/', 2500)
  await b.evalJS(LOGIN)
  await b.goto('http://localhost:8080/' + hash, wait)
  await sleep(1200)
  return b.evalJS('location.hash')
}

const r = report('probe-b5h-matrint-merchant-pages')
try {
  // ── 对照：这个运行时里 `Math.rint` 真的调用即抛 ─────────────────────────────────
  await face('#/', 2500)
  const rint = await b.evalJS(PROBE_RINT)
  r.ok('对照：本运行时的 `Math.rint` 调用即抛（证明下面「无异常」有意义）',
    rint === 'Math.rint is not a function', rint)

  // ── ② GoodsAdd：现价 100 / 划线原价 200 ⇒ 提示行该算出「5 折」 ────────────────────
  const hAdd = await face('#/merchant/user/add', 5000)
  let mark = errs.length
  const hasPrice = await b.evalJS('!!document.querySelector("#g-price")')
  r.ok('GoodsAdd：页面进得去（未被登录守卫踢走）', hAdd.indexOf('/merchant/user/add') >= 0 && hasPrice, hAdd)
  await b.evalJS(setInput('#g-price', '100'))
  await b.evalJS(setInput('#g-original', '200'))
  await sleep(600)
  const addHint = await b.evalJS(DISCOUNT_HINT)
  r.ok('GoodsAdd：折扣提示行算出了「5 折」（`fmtRate` 跑通）',
    addHint.indexOf('折扣 5 折') >= 0 && addHint.indexOf('¥200') >= 0, addHint)
  r.ok('GoodsAdd：填值过程**零未捕获异常**', errs.length === mark, errs.slice(mark))

  // ── ③ SeckillApplyPage：选商品 + 填价 + 填限量 ⇒ 提示行该带原价与区间 ──────────────
  const hSk = await face('#/merchant/seckill', 5000)
  mark = errs.length
  r.ok('秒杀报名页：页面进得去', hSk.indexOf('/merchant/seckill') >= 0, hSk)
  const opt = await b.evalJS('(function(){var s=document.querySelector("select.skl-input");if(!s)return "(无)";'
    + 'for(var i=0;i<s.options.length;i++){if(s.options[i].value)return s.options[i].value}return "(全空)"})()')
  // ⚠️ 必须选**真选项**：`hintBad` 头一行是 `if (!picked) return false` ⇒ 没选中时那支根本不求值，
  //    「没异常」就什么也没证明（第一版瞎填 '1' 踩过）。
  await b.evalJS(setInput('select.skl-input', opt))
  await b.evalJS(setInput('input[placeholder="如 99.00"]', '99'))
  await b.evalJS(setInput('input[placeholder="如 20"]', '5'))
  await sleep(600)
  const skHint = await b.evalJS('(function(){var e=document.querySelector(".skl-hint");return e?e.textContent.trim():"(无)"})()')
  const skCls = await b.evalJS('(function(){var e=document.querySelector(".skl-hint");return e?e.className:"(无)"})()')
  r.ok('秒杀报名页：提示行给出了原价与区间（`hintBad` 求值到底、没在半路炸）',
    skHint.indexOf('原价 ¥') >= 0 && skHint.indexOf('非负整数') >= 0, skHint)
  r.ok('秒杀报名页：限量填 5 时**不是** bad 态（合法值不该报错）', skCls.indexOf('bad') < 0, skCls)
  r.ok('秒杀报名页：填值过程**零未捕获异常**', errs.length === mark, errs.slice(mark))
} finally {
  await b.close()
}
r.dump()
