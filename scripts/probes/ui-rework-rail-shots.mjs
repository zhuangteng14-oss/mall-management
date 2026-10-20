// ui-rework-rail-shots.mjs —— A 版骨架 · 身份树布局分层取证（2026-10-19 修正版）
//
// 用户 2026-10-19 紧急修正后的**唯一**布局契约：
//   · 公域前台（路由 `meta.public`：两壳首页 / 搜索页）⇒ **没有**左侧身份树，顶栏 + 内容全宽；
//   · 私域中台（购物车 / 订单 / 消息 / 发布 / 售后）⇒ 272px 身份树 + 右工作台；
//   · 个人中心例外（用户 2026-10-19 命令二）：身份树**从 DOM 删除**，整页全宽居中。
// 覆盖三种真实身份：
//   ① 客户壳（role=1）：公域无树 / 私域 272px、买家区 11 条、商家区锁定 + 入驻 CTA、未读两笔
//   ② 商家壳（role=2）：/merchant 无树；/merchant/order 有树、买家功能映射到商家壳地址（9 条）、商家区 7 条
//   ③ 管理员（role=3）：私域两壳都开、闸门三向切换
// 另测断点：1180 收窄 240px、900 以下回文档流。
//
// ⚠️ 后端接口全部经 Fetch 拦截就地伪造；只为把导航状态稳定拍下来，应用代码没有 Mock 分支。
// 用法：静态服务起在 8080（产物 = npm run build），再 node scripts/probes/ui-rework-rail-shots.mjs
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/ui-rework'
fs.mkdirSync(EV, { recursive: true })

const R = report('A 版骨架 · 身份树布局分层')
const json = (body) => ({ status: 200, body: JSON.stringify(body) })

function mock(url, method) {
  if (method === 'OPTIONS') return { status: 200, body: '' }
  const p = new URL(url).pathname
  if (p === '/message/conversations') return json({ success: true, list: [{ unread: true }, { unread: false }] })
  if (p === '/notification/unreadCount') return json({ success: true, unread: 3 })
  return json({ success: true, list: [], unread: 0, total: 0 })
}

const seed = (obj) => Object.entries(obj)
  .map(([k, v]) => `sessionStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)})`)
  .join(';')

const SNAP = `(() => {
  const rail = document.querySelector('.side-rail')
  const cs = rail && getComputedStyle(rail)
  const zones = [...document.querySelectorAll('.rail-zones .zone')]
  const linksOf = (z) => [...z.querySelectorAll('.zone-link')].map(a => ({
    text: (a.innerText || '').replace(/\\s+/g, ' ').trim(),
    href: a.getAttribute('href'),
    active: a.classList.contains('active'),
    bg: getComputedStyle(a).backgroundColor,
    fg: getComputedStyle(a).color,
  }))
  const top = document.querySelector('.top-nav')
  const root = document.documentElement
  return {
    exists: !!rail,
    width: rail ? Math.round(rail.getBoundingClientRect().width) : 0,
    position: cs ? cs.position : '',
    top: cs ? cs.top : '',
    topSticky: top ? getComputedStyle(top).position + '/' + getComputedStyle(top).top : '',
    topInsideMain: !!(top && top.closest('.shell-main')),
    mainWidth: (() => { const m = document.querySelector('.shell-main'); return m ? Math.round(m.getBoundingClientRect().width) : 0 })(),
    brand: (document.querySelector('.rail-brand') || {}).innerText || '',
    account: (document.querySelector('.rail-account') || {}).innerText || '',
    zoneHeads: [...document.querySelectorAll('.zone-head')].map(h => (h.innerText || '').replace(/\\s+/g, ' ').trim()),
    zoneCount: zones.length,
    buyer: zones[0] ? linksOf(zones[0]) : [],
    merchant: zones[1] ? linksOf(zones[1]) : [],
    locked: !!document.querySelector('.rail-locked'),
    gate: (document.querySelector('.rail-gate') || {}).innerText || '',
    gateCtas: [...document.querySelectorAll('.rail-gate .rail-cta')].map(a => a.innerText.trim()),
    foot: (document.querySelector('.rail-foot') || {}).innerText || '',
    active: [...document.querySelectorAll('.zone-link.active')].map(a => (a.innerText || '').replace(/\\s+/g, ' ').trim()),
    scrollW: root.scrollWidth,
    clientW: root.clientWidth,
    redDot: !!document.querySelector('.top-nav .unread-dot'),
    railLinks: [...document.querySelectorAll('.side-rail a')].map(a => a.getAttribute('href')),
  }
})()`

async function withIdentity(idt, fn) {
  const b = await open({ w: 1440, h: 1000, mock })
  try {
    await b.send('Page.addScriptToEvaluateOnNewDocument', { source: `try { ${seed(idt)} } catch (e) {}` })
    return await fn(b)
  } finally {
    await b.close()
  }
}

// ── ① 客户壳：公域无树、私域有树 ──────────────────────────────────────────────
await withIdentity(
  { loginUsername: 'probe_customer', customerid: 'C10086', role: '1' },
  async (b) => {
    // 公域首页：不许有身份树
    await b.goto(`${BASE}/#/customer`, 2200)
    const pub = await b.evalJS(SNAP)
    R.ok('客户壳·公域首页：无身份树', pub.exists === false)
    R.ok('客户壳·公域首页：工作区铺满可用宽', Math.abs(pub.mainWidth - pub.clientW) <= 1, `${pub.mainWidth}/${pub.clientW}`)
    R.ok('客户壳·公域首页：顶栏仍在（sticky / shell-main 内）', pub.topInsideMain && pub.topSticky === 'sticky/0px', pub.topSticky)
    R.ok('客户壳·公域首页：无横向溢出', pub.scrollW <= pub.clientW + 1, `${pub.scrollW}/${pub.clientW}`)
    await b.shot(`${EV}/rail-customer-public-none.png`)

    // 公域搜索页：同样无树
    await b.goto(`${BASE}/#/customer/search?kw=电子用品`, 1600)
    const sea = await b.evalJS(SNAP)
    R.ok('客户壳·公域搜索页：无身份树', sea.exists === false)
    R.ok('客户壳·公域搜索页：工作区铺满可用宽', Math.abs(sea.mainWidth - sea.clientW) <= 1, `${sea.mainWidth}/${sea.clientW}`)

    // 私域个人中心（用户命令二）：身份树从 DOM 删除、全宽居中、旧侧栏菜单不渲染
    await b.goto(`${BASE}/#/customer/user/info`, 2000)
    const ui = await b.evalJS(`(() => ({
      rail: !!document.querySelector('.side-rail'),
      menu: !!document.querySelector('.user-menu'),
      mainW: (() => { const m = document.querySelector('.shell-main'); return m ? Math.round(m.getBoundingClientRect().width) : 0 })(),
      clientW: document.documentElement.clientWidth,
      pageHead: !!document.querySelector('.page-head'),
      plaque: !!document.querySelector('.plaque'),
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
    }))()`)
    R.ok('客户壳·个人中心：身份树不在 DOM 里', ui.rail === false)
    R.ok('客户壳·个人中心：工作区铺满可用宽', Math.abs(ui.mainW - ui.clientW) <= 1, `${ui.mainW}/${ui.clientW}`)
    R.ok('客户壳·个人中心：旧 .user-menu 侧栏不渲染', ui.menu === false)
    R.ok('客户壳·个人中心：A 版身份页结构在位', ui.pageHead && ui.plaque, `${ui.pageHead}/${ui.plaque}`)
    R.ok('客户壳·个人中心：无横向溢出', ui.overflow <= 1, ui.overflow)
    await b.shot(`${EV}/rail-customer-userinfo-norail.png`)

    // 私域购物车仍是 272px 身份树（个人中心是唯一例外）
    await b.goto(`${BASE}/#/customer/cart`, 2000)
    const s = await b.evalJS(SNAP)

    R.ok('客户壳·购物车：身份树存在', s.exists === true)
    R.ok('客户壳·购物车：侧栏宽 272', s.width === 272, s.width)
    R.ok('客户壳·购物车：sticky 100vh', s.position === 'sticky' && s.top === '0px', `${s.position}/${s.top}`)
    // ⚠️ 用 clientW（扣掉竖滚动条）而非 innerWidth：`--headless=new` 的量法有 15px 滚动条，
    //    拿 innerW 算会每跑必红（本仓已踩过这个坑，见 headless-scrollbar-width-zero）。
    R.ok('客户壳·购物车：右侧工作区 = 可用宽 − 272', Math.abs(s.mainWidth - (s.clientW - 272)) <= 1, `${s.mainWidth}/${s.clientW}`)
    R.ok('客户壳·购物车：品牌区 MALL + 双身份', s.brand.includes('MALL') && s.brand.includes('DUAL IDENTITY'))

    R.ok('客户壳：账户名 = probe_customer', s.account.includes('probe_customer'), s.account.replace(/\n/g, ' | '))
    R.ok('客户壳：身份标 = 普通买家', s.account.includes('普通买家'))
    R.ok('客户壳：BUYER 业务号在位', s.account.includes('BUYER C10086'))
    R.ok('客户壳：MERCHANT 未开通', s.account.includes('MERCHANT 未开通'))

    R.ok('客户壳：两个区（买家 / 商家）', s.zoneCount === 2, s.zoneCount)
    R.ok('客户壳：区头 买家区·常驻 / 商家区·未开通',
      s.zoneHeads[0].includes('买家区') && s.zoneHeads[0].includes('常驻') &&
      s.zoneHeads[1].includes('商家区') && s.zoneHeads[1].includes('未开通'), s.zoneHeads)

    R.ok('客户壳：买家区 11 条入口', s.buyer.length === 11, s.buyer.length)
    const labels = s.buyer.map((x) => x.text.split(' ').slice(1, -1).join(' '))
    const want = ['商城主页', '个人中心', '购物车', '我的订单', '历史购买记录', '好友', '消息 · 会话', '通知中心', '会员 · 积分 · 余额', '秒杀日程', '品牌专区']
    R.ok('客户壳：买家区顺序与全量入口', JSON.stringify(labels) === JSON.stringify(want), labels)

    R.ok('客户壳：未读会话徽标 = 1', /\b1$/.test(s.buyer[6].text), s.buyer[6].text)
    R.ok('客户壳：未读通知徽标 = 3', /\b3$/.test(s.buyer[7].text), s.buyer[7].text)
    R.ok('客户壳：顶栏未读圆点在位（两笔合计）', s.redDot === true)

    R.ok('客户壳：商家区锁定', s.locked === true && s.merchant.length === 0, s.merchant.length)
    R.ok('客户壳：锁定区给入驻 CTA', s.gateCtas.includes('申请商家入驻'), s.gateCtas)

    R.ok('客户壳·购物车：选中「购物车」且黑底反白',
      s.active.length === 1 && s.active[0].includes('购物车') &&
      s.buyer[2].active && s.buyer[2].bg === 'rgb(27, 30, 32)' && s.buyer[2].fg === 'rgb(255, 255, 255)',
      `${s.active} ${s.buyer[2].bg}/${s.buyer[2].fg}`)

    R.ok('客户壳：无横向溢出', s.scrollW <= s.clientW + 1, `${s.scrollW}/${s.clientW}`)
    R.ok('客户壳：侧栏链接全部是真实 hash 地址',
      s.railLinks.length > 10 && s.railLinks.every((h) => typeof h === 'string' && h.startsWith('#/')), s.railLinks.filter((h) => !h || !h.startsWith('#/')))

    await b.shot(`${EV}/rail-customer.png`)

    // 选中态跟随路由：/customer/user/history → 第 05 行
    await b.goto(`${BASE}/#/customer/user/history`, 1400)
    const h = await b.evalJS(SNAP)
    R.ok('客户壳：/user/history 选中「历史购买记录」', h.active.length === 1 && h.active[0].includes('历史购买记录'), h.active)

    // 消息两个 tab 各选各的
    await b.goto(`${BASE}/#/customer/msg?tab=notify`, 1400)
    const n = await b.evalJS(SNAP)
    R.ok('客户壳：?tab=notify 选中「通知中心」', n.active.length === 1 && n.active[0].includes('通知中心'), n.active)

    // 私域购物车也有树
    await b.goto(`${BASE}/#/customer/cart`, 1400)
    const c = await b.evalJS(SNAP)
    R.ok('客户壳·购物车：身份树 272px', c.exists === true && c.width === 272, `${c.exists}/${c.width}`)

    // 断点（在私域页上量）
    await b.send('Emulation.setDeviceMetricsOverride', { width: 1000, height: 1000, deviceScaleFactor: 1, mobile: false })
    await sleep(600)
    const w1000 = await b.evalJS(SNAP)
    R.ok('断点 1000：侧栏收窄到 240', w1000.width === 240, w1000.width)

    await b.send('Emulation.setDeviceMetricsOverride', { width: 880, height: 1000, deviceScaleFactor: 1, mobile: false })
    await sleep(600)
    const w880 = await b.evalJS(SNAP)
    R.ok('断点 880：侧栏回文档流、全宽', w880.position === 'static' && w880.width > 800, `${w880.position}/${w880.width}`)
    R.ok('断点 880：无横向溢出', w880.scrollW <= w880.clientW + 1, `${w880.scrollW}/${w880.clientW}`)
    await b.shot(`${EV}/rail-customer-880.png`)
  }
)

// ── ② 商家壳 ──────────────────────────────────────────────────────────────────
await withIdentity(
  { loginUsername: 'probe_merchant', merchantid: 'M001', customerid: 'C10086', role: '2' },
  async (b) => {
    // 商家壳首页仍是**公域**商城首页（两壳共用 HomeTab）⇒ 无树
    await b.goto(`${BASE}/#/merchant`, 2200)
    const pub = await b.evalJS(SNAP)
    R.ok('商家壳·公域首页：无身份树', pub.exists === false)
    R.ok('商家壳·公域首页：工作区铺满可用宽', Math.abs(pub.mainWidth - pub.clientW) <= 1, `${pub.mainWidth}/${pub.clientW}`)

    // 私域订单中心：有树
    await b.goto(`${BASE}/#/merchant/order`, 2000)
    const s = await b.evalJS(SNAP)

    R.ok('商家壳·订单中心：侧栏宽 272', s.exists === true && s.width === 272, `${s.exists}/${s.width}`)
    R.ok('商家壳：账户标 = 商家', s.account.includes('商家'), s.account.replace(/\n/g, ' | '))
    R.ok('商家壳：MERCHANT M001 在位', s.account.includes('MERCHANT M001'))
    R.ok('商家壳：商家区已开通', s.zoneHeads[1].includes('已开通') && !s.locked, s.zoneHeads[1])

    R.ok('商家壳：买家区 9 条（不含重复的商城主页）', s.buyer.length === 9, s.buyer.length)
    R.ok('商家壳：买家功能映射到商家壳地址',
      s.buyer.every((x) => x.href.startsWith('#/merchant') || x.href.startsWith('#/seckill') || x.href.startsWith('#/brand')),
      s.buyer.map((x) => x.href))
    R.ok('商家壳：购物车 / 订单走 /merchant/order/cart',
      s.buyer.some((x) => x.text.includes('购物车') && x.href === '#/merchant/order/cart') &&
      s.buyer.some((x) => x.text.includes('我的订单') && x.href === '#/merchant/order/cart?tab=orders'),
      s.buyer.map((x) => x.href).slice(0, 5))
    R.ok('商家壳：商家区 6 条（已删重复的「商家工作台」）', s.merchant.length === 6, s.merchant.length)
    R.ok('商家壳：商家入口齐全',
      ['店铺主页', '订单中心', '商品发布', '商品管理', '秒杀报名', '经营数据']
        .every((t) => s.merchant.some((x) => x.text.includes(t))),
      s.merchant.map((x) => x.text))
    R.ok('商家壳：店铺主页带 MerchantId', s.merchant.some((x) => x.text.includes('店铺主页') && x.href === '#/shop?MerchantId=M001'))

    R.ok('商家壳·订单中心：只选中「订单中心」',
      s.active.length === 1 && s.active[0].includes('订单中心'),
      s.active)
    R.ok('商家壳：无横向溢出', s.scrollW <= s.clientW + 1, `${s.scrollW}/${s.clientW}`)

    await b.shot(`${EV}/rail-merchant.png`)
  }
)

// ── ③ 管理员（真实跨壳身份，私域页） ──────────────────────────────────────────
await withIdentity(
  { loginUsername: 'probe_admin', merchantid: 'M001', customerid: 'C10086', role: '3' },
  async (b) => {
    await b.goto(`${BASE}/#/customer/user/info`, 2000)
    const adminUi = await b.evalJS(`({ rail: !!document.querySelector('.side-rail'), pageHead: !!document.querySelector('.page-head') })`)
    R.ok('管理员·个人中心：身份树同样不在 DOM 里', adminUi.rail === false)
    R.ok('管理员·个人中心：A 版身份页在位', adminUi.pageHead === true)
    await b.goto(`${BASE}/#/customer/cart`, 2000)
    const s = await b.evalJS(SNAP)
    R.ok('管理员：身份树在位', s.exists === true)
    R.ok('管理员：身份标 = 管理员', s.account.includes('管理员'))
    R.ok('管理员：客户壳里商家区也解锁（两壳都能进）', !s.locked && s.merchant.length === 6, s.merchant.length)
    R.ok('管理员：闸门三向切换',
      ['买家端', '商家端', '管理控制台'].every((t) => s.gateCtas.includes(t)), s.gateCtas)
    R.ok('管理员：闸门文案说明真实跨壳', s.gate.includes('两壳都能进'))
    await b.shot(`${EV}/rail-admin.png`)
  }
)

const bad = R.dump()
console.log(`\n截图目录：${EV}`)
process.exit(bad ? 1 : 0)
