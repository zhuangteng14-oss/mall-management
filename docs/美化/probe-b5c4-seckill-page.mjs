// probe-b5c4-seckill-page.mjs —— C3 块 5 · commit 4〈秒杀〉的**新页**自评探针（2026-10-12）。
//
// 查的是**新画的** `views/SeckillPage.vue`，外加同一份数据在**主页秒杀模块**与**商品页秒杀徽标**上的样子。
// 后端**不参与**：`_cdp.mjs` 的 mock 在页面内拦截 `*8088*` ⇒ 只需要前端 dev server（8080）。
//
// ⚠️ 本探针**刻意只有十来条断言**：这是新页的「看一眼」自评，不是验收（验收归用户）。
//    重断言（26 / 47 项那种）本笔不做 —— 用户提速指令里明确禁了。
//
// 用法：node docs/美化/probe-b5c4-seckill-page.mjs
//
// ⚠️ 本文件里的 `MEASURE` 是**一整段模板字符串**（交给 `bb.evalJS` 执行）——
//    它内部**不能出现反引号，也不能出现 `${`**，否则模板提前闭合、报出来的错还指向注释行。
import { open, sleep, report } from './_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const MERCHANT = 'dRD3gqtbFr'
const CUSTOMER = 'uDopHnCeLS'

const IMG = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='200' height='200' fill='%23cdd8d4'/><rect x='40' y='60' width='120' height='80' fill='%23a9bdb6'/></svg>"

/** UTC 文本（后端 `DATE_FORMAT` 出的就是这个形状；**不带** `Z` —— 由页面前端自己补）。 */
const utc = (hours) => new Date(Date.now() + hours * 3600e3).toISOString().slice(0, 19).replace('T', ' ')

/** 一条成立活动（列名逐个照 `SeckillDao.ACTIVITY_COLUMNS` + tail + `selectLive` 的三列补丁）。 */
function act(id, stage, startAt, endAt, price) {
  return {
    Id: id, ActivityId: 'SKA-' + id, Name: '智能手环 5 代 血氧版', MerchantAccount: MERCHANT,
    GoodsId: 82, GoodName: '智能手环 5 代 血氧版', GoodImg: IMG, GoodType: '电子用品',
    ProductCode: 'P1', OriginalPrice: 249.0, SeckillPrice: price, TotalStock: 5, SoldStock: 0,
    SourceKind: 'application', SourceId: 'SKA-APP-' + id, Status: 'active',
    ApplyEndAt: utc(-3), StartAt: startAt, EndAt: endAt, Stage: stage,
    SoldOut: false, SoldLeft: 5, created_at: utc(-3), updated_at: utc(-3)
  }
}

const LIVE = act(1, 'live', utc(-1), utc(2), 49.0)
const SOON = act(2, 'upcoming', utc(3), utc(5), 99.0)
/** ⚠️ `signup` 那条**是故意塞进来的**：它进了列表就说明前端的 `Stage !== 'signup'` 过滤没生效。 */
const SIGNUP = act(3, 'signup', utc(6), utc(8), 9.9)
const ROWS = [LIVE, SOON, SIGNUP]

function makeMock(meBody) {
  return (url) => {
    if (url.includes('/user/me')) return { body: JSON.stringify(meBody) }
    if (url.includes('/marketing/seckill/live')) {
      return { body: JSON.stringify({ success: true, list: ROWS, total: ROWS.length, limit: 100 }) }
    }
    if (url.includes('/goods/goodsSelectHome')) return { body: JSON.stringify({ success: true, list: [] }) }
    if (url.includes('/goods/goodstype')) return { body: JSON.stringify({ success: true, list: [] }) }
    if (/GoodsProductCodeSelect/i.test(url)) return { body: JSON.stringify({ success: true, list: ['P1'] }) }
    if (url.includes('/goods/productCodePrice')) return { body: JSON.stringify({ success: true, price: 89, goodImg: IMG }) }
    return { body: JSON.stringify({ success: true, list: [], total: 0, limit: 0 }) }
  }
}
const ME_BUYER = { success: true, msg: 'ok', role: 1, username: '1111', customerid: CUSTOMER, merchantid: '', usertype: '客户' }
const ME_ANON = { success: false, msg: '未登录' }

const MEASURE = `(() => {
  const cs = (el) => el ? getComputedStyle(el) : null
  const t = (el) => el ? el.textContent.replace(/\\s+/g, ' ').trim() : null
  const items = [...document.querySelectorAll('.list .item')]
  const now = document.querySelector('.list .item .now')
  const band = document.querySelector('.band')
  return {
    hasPage: !!document.querySelector('.sk-page'),
    bandBg: band ? cs(band).backgroundColor : null,
    bandW: band ? +band.getBoundingClientRect().width.toFixed(1) : null,
    viewW: innerWidth,
    hdSub: t(document.querySelector('.hd-sub')),
    tip: t(document.querySelector('.tip')),
    n: items.length,
    names: items.map((i) => t(i.querySelector('.nm'))),
    chips: items.map((i) => t(i.querySelector('.chip'))),
    prices: items.map((i) => t(i.querySelector('.now'))),
    priceColor: now ? cs(now).color : null,
    shadows: [...document.querySelectorAll('.sk-page *')].filter((e) => cs(e).boxShadow !== 'none').length,
    // 主页秒杀模块
    flash: !!document.querySelector('.flash'),
    flashCount: document.querySelectorAll('.flash .flash-c').length,
    flashPrices: [...document.querySelectorAll('.flash .fc-pr')].map((e) => t(e)),
    flashSoon: [...document.querySelectorAll('.flash .fc-soon')].map((e) => t(e)),
    flashCd: t(document.querySelector('.flash .cd')),
    flashMore: t(document.querySelector('.flash .more')),
    // 商品页
    skTag: t(document.querySelector('.sk-tag')),
    skBg: (() => { const e = document.querySelector('.sk-tag'); return e ? cs(e).backgroundColor : null })()
  }
})()`

const R = report('probe-b5c4-seckill-page')
const bad = []
const ck = (l, c, g) => { R.ok(l, c, g); if (!c) bad.push(l) }

async function boot({ me = ME_BUYER, hash = '#/seckill', wait = 2600 } = {}) {
  const bb = await open({ w: 1440, h: 900, headed: false, mock: makeMock(me) })
  await bb.goto('http://localhost:8080/', 2400)
  await bb.evalJS(`(() => { sessionStorage.clear();
    sessionStorage.setItem('loginUsername', ${JSON.stringify(String(me.username || ''))});
    sessionStorage.setItem('customerid', ${JSON.stringify(String(me.customerid || ''))});
    sessionStorage.setItem('merchantid', ${JSON.stringify(String(me.merchantid || ''))});
    sessionStorage.setItem('role', ${JSON.stringify(String(me.role || ''))});
    return 1 })()`)
  await bb.goto('http://localhost:8080/' + hash, wait)
  await sleep(600)
  return bb
}

// ① 秒杀页（已登录）：两条真行 + 一条 signup 被滤掉
{
  const bb = await boot()
  try {
    const m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c4-seckill-page-buyer.png')
    ck('页面挂上了（.sk-page 在）', m.hasPage, m.hasPage)
    ck('页头是**整幅横带**（底色 --zone = 242,246,247，且铺满视口宽）',
      m.bandBg === 'rgb(242, 246, 247)' && m.bandW === m.viewW, [m.bandBg, m.bandW, m.viewW])
    ck('**signup 那一档没上页**：3 条里只画 2 条', m.n === 2, m.n)
    ck('阶段 chip：一条「进行中」、一条「即将开始」',
      m.chips[0] === '进行中' && m.chips[1] === '即将开始', m.chips)
    ck('进行中的那条画出秒杀价 ¥49.00', m.prices[0] === '¥49.00', m.prices[0])
    // ⚠️ 未开启的那条**照显预告价**：本页是**明细**（「这场卖多少钱、什么时候开始」都摆出来），
    //    与主页那个缩略条**有意不同**（那边只画「活动未开启」）—— 阶段靠 chip 说，不靠藏价格。
    ck('未开启的那条照显预告价 ¥99.00（阶段由 chip 承担）', m.prices[1] === '¥99.00', m.prices[1])
    ck('价格色 == --accent (178,66,47)', m.priceColor === 'rgb(178, 66, 47)', m.priceColor)
    ck('全页零 box-shadow（只用发丝线的同一句）', m.shadows === 0, m.shadows)
  } finally { await bb.close() }
}

// ② 秒杀页（未登录）：只出提示 + 登录出口，**一个请求都不发**
{
  const bb = await boot({ me: ME_ANON })
  try {
    const m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c4-seckill-page-anon.png')
    ck('未登录也**进得来**（裸页 / meta.public）', m.hasPage, m.hasPage)
    ck('未登录 ⇒ 三态里的「请看得到的那一档」：提示 + 去登录', /登录/.test(m.tip || ''), m.tip)
    ck('未登录 ⇒ **一条活动都没画**', m.n === 0, m.n)
  } finally { await bb.close() }
}

// ③ 主页秒杀模块：同一份数据 —— 进行中显价、未开启显占位、倒计时不是 `--`
{
  const bb = await boot({ hash: '#/' })
  try {
    const m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c4-home-flash.png')
    ck('主页秒杀模块**真的画出来了**（原来是 `data-slot` 预留位）', m.flash, m.flash)
    ck('模块里 2 张卡（signup 那条同样被滤掉）', m.flashCount === 2, m.flashCount)
    ck('进行中的显秒杀价、未开启的显「活动未开启」',
      m.flashPrices.length === 1 && m.flashSoon.length === 1, [m.flashPrices, m.flashSoon])
    ck('倒计时**不是写死的 `--`**（读的是后端 EndAt）', /^距结束 \d\d:\d\d:\d\d$/.test(m.flashCd || ''), m.flashCd)
    ck('「全部秒杀 →」是**真落点**（原来是 disabled 按钮）', m.flashMore === '全部秒杀 →', m.flashMore)
  } finally { await bb.close() }
}

// ④ 商品页价格行的秒杀徽标
{
  const bb = await boot({ hash: '#/product?MerchantId=' + MERCHANT + '&GoodName=' + encodeURIComponent('智能手环 5 代 血氧版') + '&GoodType=' + encodeURIComponent('电子用品') + '&Id=82', wait: 3400 })
  try {
    const m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c4-productpage-sktag.png')
    ck('商品页画出了秒杀徽标（按 ProductCode 命中）', !!m.skTag, m.skTag)
    ck('徽标里是秒杀价 + 剩余件数', /^秒杀 ¥49/.test(m.skTag || '') && /剩 5 件/.test(m.skTag || ''), m.skTag)
    // `tokens.css:125` 的 `--accent-soft: #ffe9e4` = rgb(255,233,228)（与 `.off` / `.subsidy` 同一个变量）。
    ck('徽标底色 == --accent-soft（与折扣 / 国补同一套）', m.skBg === 'rgb(255, 233, 228)', m.skBg)
  } finally { await bb.close() }
}

const n = R.dump()
if (bad.length) console.log('\n失败项：\n - ' + bad.join('\n - '))
process.exit(n ? 1 : 0)
