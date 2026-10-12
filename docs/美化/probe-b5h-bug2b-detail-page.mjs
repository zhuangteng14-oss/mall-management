// probe-b5h-bug2b-detail-page.mjs —— C3 块 5 ·〈Bug 2b · 详情页「标题在、价/型号/图空缺」〉**页面侧**复现
// （2026-10-12）
//
// 与 `scripts/probes/probe-b5h-bug2b-detail.mjs`（HTTP 层）分工：那一支已经证明**后端那一串全绿**
//   （`GoodsProductCodeSelect` ⇒ `["GD-101-9552"]`；`productCodePrice` ⇒ `success:true price:299
//   goodImg:… originalPrice:373.75`）。⇒ 断点只能在**浏览器里**。本支就把那一发在真页面上跑一遍。
//
// 做法（**不猜**）：
//   ① 先到店铺页取出**耳机那张卡自己的 `href`** —— 用户在页面上点的是这一条 URL，不是我以为的那条
//      （`ProductCard` 的落点由 `config/product-link.js` 拼，7 个 query 参数，逐字读出来才作数）。
//   ② 直接进这条 URL，等页面稳定，读**模板真渲染出来的 DOM**：
//        `.pname`（标题）· `.price .int`（价）· `.pick .md`（型号钮）· `img.big-img`（大图）
//      —— 这四件正好对应用户截图里的「标题 ✅ / 价格 ❌ / 型号 ❌ / 图片 ❌」。
//   ③ 同时抓**网络与控制台**（CDP `Network.*` / `Runtime.*`）：有请求失败 / JS 抛错，这里能看见
//      —— 只读 DOM 的话，「请求没发」「请求 4xx」「发回来了但没渲染」三种因会被混成同一个空。
//   ④ 再拿**另一件商品**（氮化镓充电器 65W 双口）跑同一套读法当**对照**：
//      两件都空 ⇒ 断在共性那段（请求层 / 组件）；只有耳机空 ⇒ 断在这件商品自己的数据上。
//
// ⚠️ **不 mock**（`_cdp.mjs` 的 mock 传 `null`）：本支要复现的是真后端下的真页面。
//    前提：dev server 在 **8080**（CORS 白名单里的那个端口），后端 8088 在跑。
//
// 用法：node docs/美化/probe-b5h-bug2b-detail-page.mjs
// 产物：截图 `docs/agent/evidence/c3-block5/out-b5h-detail-earphone.png`（另加对照那张）
// ⚠️ 下面几段是模板字符串 ⇒ **内部不出现反引号、不出现 `$` + `{`**（本仓**已踩四次**；第四次就在今天、
//    就在本目录另一支 `probe-b5h-bug123-page.mjs` 里：写 `.ghost` 那条注释时用了一对反引号，当场闭合）。
import { open, sleep, report } from './_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const SHOP = 'dRD3gqtbFr'
const EARPHONE = '无线蓝牙耳机 声谷标准版'
const CONTROL = '氮化镓充电器 65W 双口'   // 对照：本店另一件（理论上正常）

/** 店铺页 → 切「全部」→ 把所有卡的 `href` + 卡名读回来。
 *  （卡一律是 `ProductCard.vue` 的 `.pcard`；默认停「热销」是柱状图，要先切 tab。） */
const CARDS = `(function () {
  var t = [].slice.call(document.querySelectorAll('.tabs .tab')).filter(function (b) { return b.textContent.trim() === '全部' })[0]
  if (t) t.click()
  return 'clicked'
})()`

const HREFS = `JSON.stringify([].slice.call(document.querySelectorAll('a.pcard')).map(function (a) {
  var n = a.querySelector('.pcard-name')
  return { name: n ? n.textContent.trim() : '', href: a.getAttribute('href') }
}))`

/** 详情页四件套的真渲染值。 */
const STATE = `JSON.stringify((function () {
  var txt = function (sel) { var e = document.querySelector(sel); return e ? e.textContent.trim() : null }
  var imgs = [].slice.call(document.querySelectorAll('img.big-img')).map(function (i) {
    return { src: i.getAttribute('src'), w: i.naturalWidth, cls: i.className }
  })
  return {
    hash: location.hash,
    pname: txt('.pname'),
    priceLine: txt('.priceline'),
    priceInt: txt('.price .int'),
    was: txt('.was'),
    off: txt('.off'),
    pickLabel: txt('.pick .lbl'),
    models: [].slice.call(document.querySelectorAll('.pick .md')).map(function (b) { return b.textContent.trim() }),
    bigImgs: imgs,
    thumbs: document.querySelectorAll('.thb').length,
    allItemsCfg: (function () { var v = document.querySelector('#app'); return v ? v.__vue__ ? 'has-vue' : 'no-vue' : 'no-app' })(),
    bodyHasPlaceholder: document.body.innerText.indexOf('商品主图') >= 0
  }
})())`

const r = report('probe-b5h-bug2b-detail-page')
const b = await open({ w: 1440, h: 1000 })

// ── 网络 / 控制台抓取（CDP 事件；`_cdp.mjs` 把 `ws` 暴露出来了，这里自己挂一个 listener）──
const net = []
const errs = []
b.ws.on('message', (raw) => {
  let m = null
  try { m = JSON.parse(raw) } catch (e) { return }
  const p = m.params || {}
  if (m.method === 'Network.responseReceived' && /:8088\//.test(p.response ? p.response.url : '')) {
    net.push({ url: p.response.url.replace(/^http:\/\/localhost:8088/, ''), status: p.response.status, type: p.type })
  }
  if (m.method === 'Network.loadingFailed') errs.push('loadingFailed: ' + JSON.stringify(p).slice(0, 200))
  if (m.method === 'Runtime.exceptionThrown') errs.push('exception: ' + JSON.stringify(p.exceptionDetails).slice(0, 300))
  if (m.method === 'Runtime.consoleAPICalled' && p.type === 'error') {
    errs.push('console.error: ' + (p.args || []).map((a) => a.value || a.description || '').join(' ').slice(0, 300))
  }
})
await b.send('Network.enable')

try {
  // ── ① 从店铺页取**真链接** ────────────────────────────────────────────────────
  await b.goto('about:blank', 250)
  await b.goto('http://localhost:8080/#/shop?MerchantId=' + SHOP, 5000)
  await b.evalJS(CARDS)
  await sleep(800)
  const cards = JSON.parse(await b.evalJS(HREFS))
  console.log('店铺页「全部」tab 的卡：')
  cards.forEach((c) => console.log('  ' + c.name + '  →  ' + c.href))
  const ear = cards.find((c) => c.name === EARPHONE)
  const ctl = cards.find((c) => c.name === CONTROL)
  r.ok('店铺页有耳机卡（取到它的 href）', !!ear, ear && ear.href)

  // ── ② 进详情页，读真 DOM + 网络 ───────────────────────────────────────────────
  const visit = async (url, shot, tag) => {
    net.length = 0
    const mark = errs.length
    await b.goto('about:blank', 250)
    await b.goto(url, 5000)
    const st = JSON.parse(await b.evalJS(STATE))
    if (shot) await b.shot(EV + shot)
    const mine = net.slice()
    console.log('\n[' + tag + '] ' + url)
    console.log('  DOM: ' + JSON.stringify(st))
    console.log('  8088 响应: ' + (mine.length ? mine.map((n) => n.status + ' ' + n.url).join(' | ') : '（无）'))
    const e2 = errs.slice(mark)
    console.log('  报错: ' + (e2.length ? e2.join('\n        ') : '（无）'))
    return { st, net: mine, errs: e2 }
  }

  const a = ear ? await visit('http://localhost:8080' + ear.href, 'out-b5h-detail-earphone.png', '耳机') : null
  const c = ctl ? await visit('http://localhost:8080' + ctl.href, 'out-b5h-detail-control.png', '对照·充电器') : null

  if (a) {
    r.ok('耳机：标题渲染出来了（与 route.query 同源）', a.st.pname === EARPHONE, a.st.pname)
    r.ok('耳机：价格有数字', !!(a.st.priceInt && a.st.priceInt !== 'null'), a.st.priceInt)
    r.ok('耳机：型号钮 ≥1 个', a.st.models.length > 0, a.st.models)
    r.ok('耳机：大图有 src', a.st.bigImgs.length > 0 && !!a.st.bigImgs[0].src, a.st.bigImgs)
    r.ok('耳机：调到了 productCodePrice', a.net.some((n) => n.url.indexOf('productCodePrice') >= 0), a.net.map((n) => n.url))
  }
  if (c) {
    r.ok('对照·充电器：价格有数字', !!(c.st.priceInt && c.st.priceInt !== 'null'), c.st.priceInt)
    r.ok('对照·充电器：大图有 src', c.st.bigImgs.length > 0 && !!c.st.bigImgs[0].src, c.st.bigImgs.length)
  }
} finally {
  await b.close()
}
r.dump()
