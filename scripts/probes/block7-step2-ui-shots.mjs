// block7-step2-ui-shots.mjs —— 第二步 UI 升维取证（2026-10-19）
//
// 三页：品牌专区 / 会员中心（积分说明 + 明细）/ 购物车积分抵扣。
//
// ⚠️ 这是**截图探针**：后端接口全部经 `_cdp.mjs` 的 Fetch 拦截就地伪造（数据形状照抄
//    真实响应），只为把三种状态稳定拍下来；**应用代码里没有退回 Mock** ——
//    `BrandZone` 仍打 `/goods/brandHome`、`MemberCenter` 仍打 `/member/*`、
//    `ShoppingCart` 的开关把 `PointsUsed` 真传给 `/cart/checkout`（本探针不点结算）。
//
// 用法：先起静态服务 `python -m http.server 8080 --directory dist`（产物 = npm run build），
//       再 `node scripts/probes/block7-step2-ui-shots.mjs`。
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7'
fs.mkdirSync(EV, { recursive: true })

const R = report('第二步 UI 升维（品牌专区 / 积分说明与明细 / 结算积分抵扣）')

/** 占位商品图：渐变 + 一个大字。data URI ⇒ 不经过 8088 拦截，也不会出现破图。 */
function img(glyph, c1, c2) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600" viewBox="0 0 800 600">`
    + `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c1}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs>`
    + `<rect width="800" height="600" fill="url(#g)"/>`
    + `<g fill="none" stroke="rgba(255,255,255,.14)"><circle cx="654" cy="128" r="130"/><circle cx="654" cy="128" r="196"/></g>`
    + `<text x="606" y="214" font-family="Microsoft YaHei, sans-serif" font-size="104" font-weight="700" fill="rgba(255,255,255,.14)">${glyph}</text>`
    + `</svg>`
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

const GOODS = [
  ['M001', 'G1001', '山海造物', '云脊通勤双肩包', '包', '#333d42', '#101417', 899, 1299, 126, 1],
  ['M002', 'G1002', '凛冬织物', '羊毛混纺长款大衣', '衣', '#3b322a', '#16110d', 1680, 2299, 88, 0],
  ['M003', 'G1003', '木下工房', '胡桃木手冲咖啡套装', '咖', '#3e3023', '#17110b', 469, 599, 214, 0],
  ['M004', 'G1004', '拾光茶事', '明前龙井礼盒', '茶', '#2f3c34', '#101613', 328, 398, 342, 0],
  ['M005', 'G1005', '北纬三十度', '手工植鞣皮托特包', '包', '#3c3129', '#15100c', 1290, 1690, 57, 0],
  ['M006', 'G1006', '研白', '极简陶瓷香薰灯', '灯', '#34393d', '#121416', 259, 329, 431, 0],
  ['M007', 'G1007', '逐风', '轻量碳板跑鞋', '跑', '#27343b', '#0d1215', 999, 1299, 175, 0],
  ['M008', 'G1008', '缎光', '真丝方巾', '巾', '#3b2e35', '#151014', 399, 499, 268, 0],
  ['M009', 'G1009', '砺石', '钛合金腕表', '表', '#2d3134', '#0f1113', 2599, 3199, 39, 0],
].map(([MerchantId, ProductCode, UserName, GoodName, glyph, c1, c2, Price, OriginalPrice, SoldQty, IsSeckill], i) => ({
  Id: 900 - i,
  MerchantId,
  ProductCode,
  UserName,
  GoodName,
  GoodImg: img(glyph, c1, c2),
  Price,
  OriginalPrice,
  GoodType: '精选',
  SoldQty,
  IsBrand: true,
  IsSeckill: IsSeckill === 1,
  IsSubsidy: false,
  IsSoldOut: 0,
}))

const MEMBER = {
  success: true,
  account: { UserId: 'C10086', Balance: 12680, Frozen: 0, GrowthValue: 760 },
  level: { Id: 1, LevelNo: 1, Code: 'V1', Name: '银卡会员', MinGrowth: 500, DiscountRate: 0.9, PointsRate: 1.0, MonthlyCoupons: 1, PriorityWeight: 10, Theme: 'silver' },
  nextLevel: { Id: 2, LevelNo: 2, Code: 'V2', Name: '金卡会员', MinGrowth: 2000, DiscountRate: 0.88, PointsRate: 1.2, MonthlyCoupons: 1, PriorityWeight: 20, Theme: 'gold' },
  growthToNext: 1240,
  signedToday: false,
}

const LOGS = [
  { Id: 9, ChangeAmount: -1000, BalanceAfter: 12680, GrowthDelta: 0, BizType: 'order_spend', BizId: 'O20261019001', Remark: '订单积分抵扣', created_at: '2026-10-19 05:42:10' },
  { Id: 8, ChangeAmount: 129, BalanceAfter: 13680, GrowthDelta: 129, BizType: 'order_earn', BizId: 'O20261018002', Remark: '订单收货积分', created_at: '2026-10-18 11:20:03' },
  { Id: 7, ChangeAmount: 5, BalanceAfter: 13551, GrowthDelta: 5, BizType: 'signin', BizId: '2026-10-17', Remark: '签到奖励（连续 3 天）', created_at: '2026-10-17 00:12:44' },
  { Id: 6, ChangeAmount: 300, BalanceAfter: 13546, GrowthDelta: 0, BizType: 'order_refund', BizId: 'O20261015007', Remark: '退款返还', created_at: '2026-10-15 08:30:11' },
  { Id: 5, ChangeAmount: -20, BalanceAfter: 13246, GrowthDelta: 0, BizType: 'expire', BizId: 'EXP-20261001', Remark: '积分过期', created_at: '2026-10-01 00:00:00' },
  { Id: 4, ChangeAmount: 25, BalanceAfter: 13266, GrowthDelta: 25, BizType: 'signin', BizId: '2026-09-30', Remark: '签到奖励（连续 7 天）', created_at: '2026-09-30 00:05:20' },
]

const CART = [
  { Id: 1, CustomerId: 'C10086', MerchantId: 'M001', ProductCode: 'G1001', GoodName: '云脊通勤双肩包', GoodImg: GOODS[0].GoodImg, Quantity: 1, PriceSnapshot: 899, PriceNow: 899, OriginalPrice: 1299, GoodType: '箱包' },
  { Id: 2, CustomerId: 'C10086', MerchantId: 'M001', ProductCode: 'G1005', GoodName: '手工植鞣皮托特包', GoodImg: GOODS[4].GoodImg, Quantity: 2, PriceSnapshot: 1290, PriceNow: 1290, OriginalPrice: 1690, GoodType: '箱包' },
  { Id: 3, CustomerId: 'C10086', MerchantId: 'M001', ProductCode: 'G1008', GoodName: '真丝方巾', GoodImg: GOODS[7].GoodImg, Quantity: 1, PriceSnapshot: 399, PriceNow: 399, OriginalPrice: 499, GoodType: '配饰' },
]

const json = (body) => ({ status: 200, body: JSON.stringify(body) })

function mock(url, method) {
  if (method === 'OPTIONS') return { status: 200, body: '' }
  const p = new URL(url).pathname
  if (p === '/goods/brandHome') return json({ success: true, list: GOODS, limit: 24, offset: 0 })
  if (p === '/member/overview') return json(MEMBER)
  if (p === '/member/points/logs') return json({ success: true, list: LOGS, total: LOGS.length, limit: 20 })
  if (p === '/member/signin') return json({ success: true, msg: '签到成功', points: 5, streak: 4, balance: 12685, signedToday: true })
  if (p === '/cart/list') return json({ success: true, list: CART })
  if (p === '/marketing/myCoupons') return json({ success: true, list: [], redDot: false, expiringCount: 0 })
  if (p === '/cart/checkout') return json({ success: false, msg: '截图探针不落单' })
  return json({ success: true, list: [], unread: 0 })
}

const IDENTITY = {
  loginUsername: 'probe_customer',
  customerid: 'C10086',
  role: '1',
  memberLevel: '1',
  balance: '128.00',
}
const INJECT = Object.entries(IDENTITY)
  .map(([k, v]) => `sessionStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)})`)
  .join(';')

const b = await open({ w: 1440, h: 1000, mock })
try {
  // 身份在**文档起始**写入 ⇒ 应用启动时 `isLoggedIn()` 已为真，不会打 `/user/me`。
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try { ${INJECT} } catch (e) {}`
  })

  // ── ① 品牌专区 ────────────────────────────────────────────────────────────────
  await b.goto(`${BASE}/#/brand`, 2500)
  const brand = await b.evalJS(`(() => ({
    hero: (document.querySelector('.hero-title') || {}).textContent || '',
    feature: !!document.querySelector('.show-feature'),
    minis: document.querySelectorAll('.show-mini').length,
    cards: document.querySelectorAll('.grid .pcard').length,
    wide: document.querySelectorAll('.grid .pcard.wide').length,
    featureName: (document.querySelector('.show-name') || {}).textContent || '',
    err: (document.querySelector('.tip') || {}).textContent || '',
  }))()`)
  R.ok('品牌专区头图标题', brand.hero.trim() === '品牌专区', brand.hero)
  R.ok('品牌橱窗：左大卡在场', brand.feature === true)
  R.ok('品牌橱窗：右侧四枚小卡', brand.minis === 4, brand.minis)
  R.ok('货架：9 件商品全部渲染', brand.cards === 9, brand.cards)
  R.ok('货架：错落宽卡 3 枚', brand.wide === 3, brand.wide)
  R.ok('无错误提示', !brand.err.trim(), brand.err)
  await b.shotFull(`${EV}/step2-brand-zone.png`)

  // ── ② 会员中心（积分获取说明 + 明细）──────────────────────────────────────────
  await b.goto(`${BASE}/#/customer/user/member`, 2500)
  const member = await b.evalJS(`(() => ({
    level: (document.querySelector('.mc-level') || {}).textContent || '',
    code: (document.querySelector('.mc-level-sub') || {}).textContent || '',
    points: (document.querySelector('.mc-v') || {}).textContent || '',
    growth: ((document.querySelector('.mc-bar i') || {}).style || {}).width || '',
    earn: document.querySelectorAll('.earn').length,
    rules: document.querySelectorAll('.rules li').length,
    logs: document.querySelectorAll('.log-row').length,
    firstLog: (document.querySelector('.log-row .log-main b') || {}).textContent || '',
    firstAmount: (document.querySelector('.log-row .log-amt b') || {}).textContent || '',
    firstTime: (document.querySelector('.log-row .log-main span') || {}).textContent || '',
    empty: !!document.querySelector('.log-empty'),
    err: (document.querySelector('.mc-msg.bad') || {}).textContent || '',
  }))()`)
  R.ok('会员等级', member.level.trim() === '银卡会员', member.level)
  R.ok('等级副行含折扣与倍率', /9 折/.test(member.code) && /积分 1 倍/.test(member.code), member.code)
  R.ok('可用积分 = 12680', member.points.trim() === '12680', member.points)
  R.ok('成长进度条有宽度', member.growth !== '' && member.growth !== '0%', member.growth)
  R.ok('积分获取说明 2 张卡', member.earn === 2, member.earn)
  R.ok('积分规则 3 条', member.rules === 3, member.rules)
  R.ok('积分明细 6 行', member.logs === 6, member.logs)
  R.ok('明细首行是消耗 1000', member.firstLog.trim() === '积分抵扣' && member.firstAmount.trim() === '−1000', `${member.firstLog} / ${member.firstAmount}`)
  R.ok('明细时间已转本地时区', /2026-10-19 13:42/.test(member.firstTime), member.firstTime)
  R.ok('明细非空态', member.empty === false)
  R.ok('无错误提示', !member.err.trim(), member.err)
  await b.shotFull(`${EV}/step2-member-points.png`)

  // ── ③ 购物车积分抵扣 ──────────────────────────────────────────────────────────
  await b.goto(`${BASE}/#/customer/cart`, 2500)
  const before = await b.evalJS(`(() => ({
    paybar: !!document.querySelector('.paybar'),
    pts: !!document.querySelector('.pts'),
    total: (document.querySelector('.paybar .total .v') || {}).textContent || '',
    balance: (document.querySelector('.pts-balance') || {}).textContent || '',
    disabled: !!(document.querySelector('.pts-sw input') || {}).disabled,
  }))()`)
  R.ok('结算栏在场', before.paybar === true)
  R.ok('积分开关在场', before.pts === true)
  R.ok('开关可点（单商家 + 有余额）', before.disabled === false)
  R.ok('开关前总价 = 会员价合计 3490.20', /3490\.20/.test(before.total), before.total)
  R.ok('余额提示 12680 积分', /12680/.test(before.balance), before.balance)

  await b.evalJS(`(() => { const el = document.querySelector('.pts-sw input'); if (el) el.click(); return !!el })()`)
  await sleep(500)
  const after = await b.evalJS(`(() => ({
    checked: !!(document.querySelector('.pts-sw input') || {}).checked,
    detail: (document.querySelector('.pts-detail') || {}).textContent || '',
    total: (document.querySelector('.paybar .total .v') || {}).textContent || '',
    save: (document.querySelector('.paybar .sums .d') || {}).textContent || '',
  }))()`)
  R.ok('开关已打开', after.checked === true)
  R.ok('实时文案「已使用 12680 积分，抵扣 ¥126.80」', /已使用\s*12680\s*积分/.test(after.detail) && /126\.80/.test(after.detail), after.detail)
  R.ok('总价已更新为 3363.40', /3363\.40/.test(after.total), after.total)
  R.ok('商品总额行含积分减免', /积分\s*−¥126\.80/.test(after.save), after.save)
  await b.shot(`${EV}/step2-cart-points.png`)
} finally {
  await b.close()
}

const bad = R.dump()
process.exit(bad ? 1 : 0)
