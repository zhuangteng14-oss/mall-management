// ui-rework-home-shots.mjs —— A 版骨架第 2 块：公域商城主页重写取证（2026-10-19）
//
// 验的是四件事：
//   ① 公域前台（`meta.public`）**没有**左侧身份树，`.shell-main` = 可用宽；
//   ② 主视觉沉浸全宽 + 毛玻璃分类/快捷入口悬浮条；
//   ③ 秒杀（倒计时 + 真实已抢进度）、撕纸券卡、12 栏错落网格（6-3-3 / 4-4-4 / 6-3-3）；
//   ④ 私域中台仍有 272px 身份树；公域搜索页也不挂侧栏。
//
// ⚠️ 取证用 Fetch 拦截伪造 8088 的**接口**；商品图等静态资源用 `'PASS'` 放行到真后端。
//    券数据本库本周为空（真响应 `list: []`），这里按真帧形状造 6 张仅用于拍摄券卡外观，
//    金额 / 门槛 / 类型字段逐字用后端 `COUPON_COLUMNS` 的名字。
// 用法：静态服务起在 8080（产物 = npm run build），再 node scripts/probes/ui-rework-home-shots.mjs
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/ui-rework'
fs.mkdirSync(EV, { recursive: true })

const readJson = (p) => JSON.parse(fs.readFileSync(new URL(p, import.meta.url), 'utf8'))
const GOODS = readJson('../../_work/home-goods.json')        // 真后端：/goods/goodsSelectHome?limit=24&offset=0
const TYPES = readJson('../../_work/home-types.json')        // 真后端：/goods/goodstype
const SECKILL = readJson('../../_work/home-seckill.json')    // 真后端：/marketing/seckill/live?limit=8

const COUPONS = [
  { CouponId: 'PROBE-01', Name: '新客立减券', Type: 'threshold', FaceValue: 20, Threshold: 199, Claimed: false, SoldOut: false, IsNew: true, MemberLevel: 0 },
  { CouponId: 'PROBE-02', Name: '周末茶器券', Type: 'threshold', FaceValue: 5, Threshold: 59, Claimed: false, SoldOut: false, IsNew: false, MemberLevel: 0 },
  { CouponId: 'PROBE-03', Name: '全场 8.5 折', Type: 'percent', FaceValue: 0.85, Threshold: 0, Claimed: false, SoldOut: false, IsNew: false, MemberLevel: 0 },
  { CouponId: 'PROBE-04', Name: '无门槛红包', Type: 'none', FaceValue: 3, Threshold: 0, Claimed: false, SoldOut: false, IsNew: false, MemberLevel: 0 },
  { CouponId: 'PROBE-05', Name: '满 299 减 50', Type: 'threshold', FaceValue: 50, Threshold: 299, Claimed: false, SoldOut: false, IsNew: false, MemberLevel: 0 },
  { CouponId: 'PROBE-06', Name: '会员专享券', Type: 'threshold', FaceValue: 10, Threshold: 99, Claimed: true, SoldOut: false, IsNew: false, MemberLevel: 1 }
]

const json = (body) => ({ status: 200, body: JSON.stringify(body) })

function mock(url, method, postData) {
  const u = new URL(url)
  const p = u.pathname
  // 静态资源（商品图 / 头像）放行到真后端：Fulfill 成 JSON 会把图片打坏。
  if (p.startsWith('/img/') || p.startsWith('/upload/') || /\.(png|jpe?g|webp|gif|svg)$/i.test(p)) return 'PASS'
  if (method === 'OPTIONS') return { status: 200, body: '' }
  const form = new URLSearchParams(postData || '')

  if (p === '/goods/goodsSelectHome') {
    const offset = Number(form.get('offset') || 0)
    return json(offset > 0 ? { success: true, list: [] } : GOODS)
  }
  if (p === '/goods/goodstype') return json(TYPES)
  if (p === '/marketing/seckill/live') return json(SECKILL)
  if (p === '/marketing/coupons') return json({ success: true, list: COUPONS, daysLeft: 5 })
  if (p === '/message/conversations') return json({ success: true, list: [{ unread: true }, { unread: false }] })
  if (p === '/notification/unreadCount') return json({ success: true, unread: 3 })
  return json({ success: true, list: [], unread: 0, total: 0 })
}

const seed = (obj) => Object.entries(obj)
  .map(([k, v]) => `sessionStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)})`)
  .join(';')

const SHAPE = `(() => {
  const q = (s) => document.querySelector(s)
  const qa = (s) => [...document.querySelectorAll(s)]
  const rect = (el) => el ? el.getBoundingClientRect() : null
  const root = document.documentElement
  const dock = q('.stage-dock')
  const g = q('.grid')
  const kids = g ? [...g.children] : []
  const w = (el) => Math.round(rect(el).width)
  return {
    rail: !!q('.side-rail'),
    wrapContent: (() => {
      const el = q('.wrap')
      if (!el) return 0
      const cs = getComputedStyle(el)
      return Math.round(el.getBoundingClientRect().width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight))
    })(),
    mainW: q('.shell-main') ? Math.round(rect(q('.shell-main')).width) : 0,
    clientW: root.clientWidth,
    scrollW: root.scrollWidth,
    topNav: !!q('.top-nav'),
    search: !!q('.top-search input'),
    topLinks: qa('.top-nav .top-links a, .top-nav .top-cart, .top-nav .top-account').map(a => (a.innerText || '').replace(/\s+/g, ' ').trim()),
    topMark: (q('.top-mark') || {}).innerText || '',
    gridStyle: (() => { if (!g) return {}; const c = getComputedStyle(g); return { count: c.columnCount, gap: c.columnGap } })(),
    sameColGap: (() => {
      if (!g || !kids[0]) return -1
      const r0 = kids[0].getBoundingClientRect()
      const next = kids.find((k, i) => i > 0 && Math.abs(k.getBoundingClientRect().left - r0.left) < 1)
      return next ? Math.round(next.getBoundingClientRect().top - r0.bottom) : -1
    })(),
    stageW: q('.stage') ? Math.round(rect(q('.stage')).width) : 0,
    stageH: q('.stage') ? Math.round(rect(q('.stage')).height) : 0,
    dock: !!dock,
    dockGlass: dock ? getComputedStyle(dock).backdropFilter || getComputedStyle(dock).webkitBackdropFilter : '',
    dockCats: qa('.dock-cat').length,
    dockAll: (q('.dock-all') || {}).innerText || '',
    dockLinks: qa('.dock-link').map(a => (a.innerText || '').replace(/\\s+/g, ' ').trim()),
    oldLeftCol: !!q('.hero') || !!q('.cats') || !!q('.a-mall-cats'),
    slides: qa('.stage-slide').length,
    dots: qa('.stage-dots button').length,
    title: (q('.stage-title') || {}).innerText || '',
    flash: !!q('.flash'),
    clockCells: qa('.flash .clock-cell').length,
    clock: qa('.flash-clock') ? (q('.flash-clock').innerText || '').replace(/\\s+/g, ' ').trim() : '',
    flashCards: qa('.flash-card').length,
    bars: qa('.fc-bar').length,
    barWidths: qa('.fc-bar i').map(i => Math.round(rect(i).width)),
    pcts: qa('.fc-pct').map(e => (e.innerText || '').replace(/\\s+/g, ' ').trim()),
    tickets: !!q('.tickets'),
    ticketRect: q('.tickets') ? Math.round(rect(q('.tickets')).width) : 0,
    ticketCards: qa('.tk').length,
    ticketAmts: qa('.tk-amt').map(e => e.innerText.trim()),
    ticketConds: qa('.tk-cond').map(e => e.innerText.trim()),
    ticketBtns: qa('.tk-get').map(b => ({ text: b.innerText.trim(), disabled: b.disabled })),
    ticketCut: !!q('.tk-strip-cut'),
    gridKids: kids.length,
    widths: kids.slice(0, 12).map(w),
    // 矮卡（3 栏）的元信息行是否被钉到卡底：否则同高拉伸会在卡内留一大片空白。
    footGap: kids[1] && kids[1].querySelector('.pcard-meta')
      ? Math.round(rect(kids[1]).bottom - rect(kids[1].querySelector('.pcard-meta')).bottom)
      : -1,
    productLinks: qa('.grid .pcard').slice(0, 2).map(a => a.getAttribute('href')),
    cardsInGrid: qa('.grid .pcard').length,
    trust: qa('.trust > div').length,
    foot: !!q('.home-foot'),
    flashRect: q('.flash') ? Math.round(rect(q('.flash')).width) : 0,
    recRect: q('.rec') ? Math.round(rect(q('.rec')).width) : 0
  }
})()`

const R = report('A 版骨架 · 公域商城主页（第 2 块）')
const b = await open({ w: 1440, h: 1000, mock })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try { ${seed({ loginUsername: 'probe_customer', customerid: 'C10086', role: '1' })} } catch (e) {}`
  })
  await b.goto(`${BASE}/#/customer`, 3200)
  const s = await b.evalJS(SHAPE)

  // ① 公域：无侧栏、全宽
  R.ok('公域主页：无左侧身份树', s.rail === false)
  R.ok('公域主页：工作区铺满可用宽', Math.abs(s.mainW - s.clientW) <= 1, `${s.mainW}/${s.clientW}`)
  R.ok('公域主页：无横向溢出', s.scrollW <= s.clientW + 1, `${s.scrollW}/${s.clientW}`)
  R.ok('顶栏在位：MALL 标 + 搜索框 + 通知/购物车/用户中心',
    s.topNav && s.search && /MALL/.test(s.topMark) && ['通知', '购物车', '用户中心'].every(t => s.topLinks.some(x => x.includes(t))), { mark: s.topMark, links: s.topLinks })
  R.ok('旧「左分类栏 / hero 三栏」结构已不存在', s.oldLeftCol === false)

  // ② 沉浸主视觉 + 毛玻璃悬浮条
  R.ok('主视觉 = 全宽（与工作区同宽）', s.stageW === s.clientW, `${s.stageW}/${s.clientW}`)
  R.ok('主视觉高度 ≥ 500', s.stageH >= 500, s.stageH)
  R.ok('主视觉有轮播与指示器', s.slides === 5 && s.dots === 5, `${s.slides}/${s.dots}`)
  R.ok('主视觉文案在位', s.title.length > 0, s.title)
  R.ok('毛玻璃分类条在位且真毛玻璃', s.dock && /blur/.test(s.dockGlass), s.dockGlass)
  R.ok('分类条：全部商品分类 + 5 个一级类目', s.dockAll.includes('全部商品分类') && s.dockCats === 5, `${s.dockAll}/${s.dockCats}`)
  R.ok('快捷入口 5 项齐全',
    ['今日秒杀', '领券中心', '品牌专区', '会员中心', '客户服务'].every(t => s.dockLinks.some(x => x.includes(t))), s.dockLinks)

  // ③ 秒杀 / 券 / 推荐
  R.ok('内容区不被侧栏挤压（≥ 可用宽的 92%）', s.wrapContent >= s.clientW * 0.92, `${s.wrapContent}/${s.clientW}`)
  R.ok('秒杀区块 = 内容区全宽', s.flash && s.flashRect === s.wrapContent, `${s.flashRect}/${s.wrapContent}`)
  R.ok('秒杀倒计时 = 3 格（时:分:秒）', s.clockCells === 3 && /\d{2}\s*HRS.*\d{2}\s*MIN.*\d{2}\s*SEC/.test(s.clock), s.clock)
  R.ok('秒杀横向商品卡 = 2（真后端场次数）', s.flashCards === 2, s.flashCards)
  R.ok('秒杀进度条逐卡存在（真 SoldStock/TotalStock）', s.bars === 2 && s.pcts.every(t => t.includes('已抢')), s.pcts)
  // 期望值直接由**同一份真响应**算，数据库里卖出去了也不会让这条变红。
  const live = SECKILL.list.filter(r => r.Stage !== 'signup').slice(0, 4)
  const wantPct = live.map(r => Number(r.TotalStock) > 0
    ? Math.max(0, Math.min(100, Math.round(Number(r.SoldStock) / Number(r.TotalStock) * 100)))
    : null)
  R.ok('秒杀「已抢 X%」与真实库存比一致',
    s.pcts.length === live.length && s.pcts.every((t, i) => wantPct[i] === null ? !t.includes('已抢') : t.startsWith('已抢 ' + wantPct[i] + '%')),
    { got: s.pcts, want: wantPct })

  R.ok('领券中心 = 内容区全宽', s.tickets && s.ticketRect === s.wrapContent, `${s.ticketRect}/${s.wrapContent}`)
  R.ok('券卡 6 张（5 张可见 + 1 张越位渐隐）', s.ticketCards === 6 && s.ticketCut === true, `${s.ticketCards}/${s.ticketCut}`)
  R.ok('券面额大字不为空', s.ticketAmts.length === 6 && s.ticketAmts.every(t => t.length > 0), s.ticketAmts)
  R.ok('券门槛/类型文案在位', s.ticketConds.length === 6 && s.ticketConds.every(t => t.length > 0), s.ticketConds)
  R.ok('券按钮：5 张可领 + 1 张已领取禁用',
    s.ticketBtns.filter(x => x.text === '立即领取' && !x.disabled).length === 5 &&
    s.ticketBtns.some(x => x.text === '已领取' && x.disabled), s.ticketBtns)

  R.ok('推荐区 24 张商品卡', s.gridKids === 24 && s.cardsInGrid === 24, `${s.gridKids}/${s.cardsInGrid}`)
  const w = s.widths
  // A 版瀑布流（用户命令三）：column-count:3、列距与下距 12px，三栏等宽。
  const wantCol = (s.wrapContent - 12 * 2) / 3
  R.ok('瀑布流 = 3 栏（column-count:3）', s.gridStyle.count === '3', s.gridStyle)
  R.ok('瀑布流列距 = 12px', s.gridStyle.gap === '12px', s.gridStyle.gap)
  R.ok('同列相邻卡下距 = 12px（砖墙无白缝）', s.sameColGap === 12, s.sameColGap)
  R.ok('三栏等宽（列宽由内容区宽算出）',
    w.slice(0, 12).every(x => Math.abs(x - wantCol) <= 2),
    { got: w.slice(0, 6), want: Math.round(wantCol) })
  R.ok('矮卡文字区被拉满：元信息行贴近卡底（≤ 24px）', s.footGap >= 0 && s.footGap <= 24, s.footGap)
  R.ok('商品卡链接走 7 参数商品页契约',
    s.productLinks.every(h => h && h.startsWith('#/product?') && h.includes('Id=') && h.includes('MerchantId=') &&
      h.includes('GoodName=') && h.includes('Price=') && h.includes('GoodImg=') && h.includes('GoodType=') && h.includes('ProductCode=')),
    s.productLinks)
  R.ok('信任条 4 项 + 页脚在位', s.trust === 4 && s.foot, `${s.trust}/${s.foot}`)

  await b.shot(`${EV}/home-public-1440.png`)
  await b.shotFull(`${EV}/home-public-full.png`)

  // 分块截图
  const yOf = async (sel) => b.evalJS(`(() => { const el = document.querySelector(${JSON.stringify(sel)}); return el ? Math.round(el.getBoundingClientRect().top + window.scrollY) : -1 })()`)
  for (const [sel, name] of [['.flash', 'flash'], ['.tickets', 'coupons'], ['.rec', 'recommend']]) {
    const y = await yOf(sel)
    await b.evalJS(`window.scrollTo(0, ${Math.max(0, y - 88)})`)
    await sleep(600)
    await b.shot(`${EV}/home-public-${name}.png`)
  }

  // 券卡 hover 抬升（撕纸卡片的微交互）
  await b.evalJS(`window.scrollTo(0, ${Math.max(0, (await yOf('.tickets')) - 88)})`)
  await sleep(400)
  const tk = await b.evalJS(`(() => { const el = document.querySelector('.tk'); const r = el.getBoundingClientRect(); return { x: r.left + r.width - 18, y: r.top + r.height / 2 } })()`)
  await b.moveTo(tk.x, tk.y)
  const hoverTf = await b.evalJS(`getComputedStyle(document.querySelector('.tk')).transform`)
  R.ok('券卡 hover 抬升 4px', /matrix\(1, 0, 0, 1, 0, -4\)/.test(hoverTf), hoverTf)
  await b.shot(`${EV}/home-public-coupon-hover.png`)

  // 分类 chip → 公域搜索页（且搜索页仍无侧栏）
  await b.evalJS(`window.scrollTo(0, 0)`)
  await sleep(300)
  await b.evalJS(`document.querySelector('.dock-cat').click()`)
  await sleep(1200)
  const search = await b.evalJS(`({ url: location.hash, rail: !!document.querySelector('.side-rail'), mainW: document.querySelector('.shell-main') ? Math.round(document.querySelector('.shell-main').getBoundingClientRect().width) : 0, clientW: document.documentElement.clientWidth })`)
  R.ok('分类 chip 跳到公域搜索页且带 kw', /^#\/customer\/search\?kw=/.test(search.url), search.url)
  R.ok('搜索页仍无侧栏且全宽', search.rail === false && Math.abs(search.mainW - search.clientW) <= 1, `${search.rail}/${search.mainW}/${search.clientW}`)
  await b.shot(`${EV}/home-public-search.png`)

  // 真实点击链路（不是只看 href）：领券回调 / 秒杀卡 / 商品卡 / 顶栏搜索
  await b.goto(`${BASE}/#/customer`, 2600)
  await b.evalJS(`[...document.querySelectorAll('.tk-get')].find(x => !x.disabled).click()`)
  await sleep(900)
  const claimMsg = await b.evalJS(`(() => (document.querySelector('.tk-msg') || {}).innerText || '')()`)
  R.ok('领券按钮真实回调：出现「已领取」提示', claimMsg.includes('已领取'), claimMsg)
  await b.evalJS(`document.querySelector('.flash-card').click()`)
  await sleep(900)
  const sk = await b.evalJS(`({ hash: location.hash })`)
  R.ok('秒杀卡点击 → 商品详情（带 Id + MerchantId）',
    /^#\/product\?/.test(sk.hash) && sk.hash.includes('Id=') && sk.hash.includes('MerchantId='), sk.hash)

  await b.goto(`${BASE}/#/customer`, 2600)
  await b.evalJS(`document.querySelector('.grid .pcard').click()`)
  await sleep(900)
  const pc = await b.evalJS(`({ hash: location.hash })`)
  R.ok('商品卡点击 → 商品详情（带 7 参数）',
    /^#\/product\?/.test(pc.hash) && ['Id=', 'MerchantId=', 'GoodName=', 'Price=', 'GoodImg=', 'GoodType=', 'ProductCode='].every(k => pc.hash.includes(k)), pc.hash)

  await b.goto(`${BASE}/#/customer`, 2600)
  await b.evalJS(`(() => { const f = document.querySelector('.top-search'); f.kw.value = '耳机'; f.requestSubmit() })()`)
  await sleep(900)
  const sq = await b.evalJS(`({ hash: location.hash })`)
  R.ok('顶栏搜索提交 → 公域搜索页且带 kw', /^#\/customer\/search\?kw=/.test(sq.hash), sq.hash)

  // ④ 私域个人中心（用户命令二）：**身份树从 DOM 删除**、全宽居中、不渲染旧侧栏菜单
  await b.goto(`${BASE}/#/customer/user/info`, 1800)
  const priv = await b.evalJS(`(() => {
    const rail = document.querySelector('.side-rail')
    const main = document.querySelector('.shell-main')
    return {
      rail: !!rail,
      menu: !!document.querySelector('.user-menu'),
      mainW: main ? Math.round(main.getBoundingClientRect().width) : 0,
      clientW: document.documentElement.clientWidth,
      pageHead: !!document.querySelector('.page-head'),
      plaque: !!document.querySelector('.plaque'),
      twoCol: !!document.querySelector('.two-col'),
      shortcuts: document.querySelectorAll('.shortcut-grid > *').length,
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
    }
  })()`)
  R.ok('私域个人中心：身份树不在 DOM 里', priv.rail === false)
  R.ok('私域个人中心：工作区铺满可用宽', Math.abs(priv.mainW - priv.clientW) <= 1, `${priv.mainW}/${priv.clientW}`)
  R.ok('私域个人中心：旧 .user-menu 侧栏不渲染', priv.menu === false)
  R.ok('私域个人中心：A 版结构在位（page-head / 黑金牌匾 / two-col）',
    priv.pageHead && priv.plaque && priv.twoCol, `${priv.pageHead}/${priv.plaque}/${priv.twoCol}`)
  R.ok('私域个人中心：快捷入口 4 格', priv.shortcuts === 4, priv.shortcuts)
  R.ok('私域个人中心：无横向溢出', priv.overflow <= 1, priv.overflow)
  await b.shot(`${EV}/home-private-userinfo.png`)

  // 私域购物车同样有身份树
  await b.goto(`${BASE}/#/customer/cart`, 1500)
  const cart = await b.evalJS(`({ rail: !!document.querySelector('.side-rail'), width: (() => { const r = document.querySelector('.side-rail'); return r ? Math.round(r.getBoundingClientRect().width) : 0 })() })`)
  R.ok('私域购物车：身份树在位 272px', cart.rail === true && cart.width === 272, cart)
} finally {
  await b.close()
}

const bad = R.dump()
console.log(`\n截图目录：${EV}`)
process.exit(bad ? 1 : 0)
