// ui-rework-search-shots.mjs —— A 版公域搜索页重写取证（2026-10-19，用户命令四）
//
// 验的是用户命令四的三条：
//   ① 无左栏、全宽：`<aside class="cat">` 必须**不在 DOM 里**（不是隐藏）；
//   ② 结果网格与首页「为你推荐」一致：`column-count:3` + 12px 列距 / 下距；
//   ③ 过滤器（分类 / 排序 / 价格 / 国补）横向排布在顶部，而不是左侧竖栏。
// 另验功能未丢：搜索框回填 `?kw=`、类目点击仍调 `categoryGoods`、国补筛选仍按登录态禁用、
// 无限滚动哨兵仍在。
//
// ⚠️ 后端接口全部经 Fetch 拦截就地伪造（`_work/home-goods.json` / `home-types.json` 真帧形状）；
//    商品图等静态资源 `'PASS'` 放行到真后端。
// 用法：静态服务起在 8080（产物 = npm run build），再 node scripts/probes/ui-rework-search-shots.mjs
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/ui-rework'
fs.mkdirSync(EV, { recursive: true })

const readJson = (p) => JSON.parse(fs.readFileSync(new URL(p, import.meta.url), 'utf8'))
const GOODS = readJson('../../_work/home-goods.json')   // 真后端：/goods/goodsSelectHome
const TYPES = readJson('../../_work/home-types.json')   // 真后端：/goods/goodstype

const json = (body) => ({ status: 200, body: JSON.stringify(body) })

function mock(url, method, postData) {
  const u = new URL(url)
  const p = u.pathname
  if (p.startsWith('/img/') || p.startsWith('/upload/') || /\.(png|jpe?g|webp|gif|svg)$/i.test(p)) return 'PASS'
  if (method === 'OPTIONS') return { status: 200, body: '' }
  const form = new URLSearchParams(postData || '')
  const offset = Number(form.get('offset') || 0)
  if (p === '/goods/productSearch' || p === '/goods/categoryGoods' || p === '/goods/goodsSelectHome') {
    return json(offset > 0 ? { success: true, list: [] } : GOODS)
  }
  if (p === '/goods/goodstype') return json(TYPES)
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
  const view = q('.goods-select-view')
  const cs = view ? getComputedStyle(view) : null
  const g = q('.grid')
  const kids = g ? [...g.children] : []
  const chipTops = qa('.cats .cat-l .chip').map(c => Math.round(rect(c).top))
  const rows = qa('.toolbar .trow').map(row => {
    // 用「垂直中心」而非 top：.tgroup 高度不同（价格区间 32px、纯文字更矮），
    // 行内 align-items:center 会让 top 天然错开，用 top 判「是否折行」会假红。
    const centers = [...row.querySelectorAll('.tgroup')].map(x => {
      const r = rect(x)
      return Math.round(r.top + r.height / 2)
    })
    return { groups: centers.length, distinctCenters: new Set(centers).size }
  })
  return {
    rail: !!q('.side-rail'),
    mainW: q('.shell-main') ? Math.round(rect(q('.shell-main')).width) : 0,
    clientW: root.clientWidth,
    scrollW: root.scrollWidth,
    viewW: view ? Math.round(rect(view).width) : 0,
    wrapContent: view && cs ? Math.round(rect(view).width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)) : 0,
    asideCat: !!q('aside.cat'),
    catMoreButtons: qa('.cat-more').length,
    cats: !!q('.cats'),
    chips: qa('.cats .cat-l .chip').map(c => (c.innerText || '').trim()),
    chipRows: new Set(chipTops).size,
    chipBg: (() => { const on = q('.cats .chip.on'); return on ? getComputedStyle(on).backgroundColor + '/' + getComputedStyle(on).color : '' })(),
    toolbar: !!q('.toolbar'),
    rows,
    searchVal: (q('.search input') || {}).value || '',
    searchH: q('.search') ? Math.round(rect(q('.search')).height) : 0,
    grid: !!g,
    gridStyle: g ? { count: getComputedStyle(g).columnCount, gap: getComputedStyle(g).columnGap } : {},
    cards: kids.length,
    widths: kids.slice(0, 12).map(k => Math.round(rect(k).width)),
    sameColGap: (() => {
      if (!kids[0]) return -1
      const r0 = rect(kids[0])
      const next = kids.find((k, i) => i > 0 && Math.abs(rect(k).left - r0.left) < 1)
      return next ? Math.round(rect(next).top - r0.bottom) : -1
    })(),
    cardHref: kids[0] ? kids[0].getAttribute('href') : '',
    more: !!q('.list-more'),
    subsidy: (() => { const b = qa('.toolbar .pick').find(x => x.innerText.includes('国补')); return b ? { disabled: b.disabled, text: b.innerText.trim() } : null })()
  }
})()`

const R = report('A 版公域搜索页（用户命令四）')
const b = await open({ w: 1440, h: 1000, mock })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try { ${seed({ loginUsername: 'probe_customer', customerid: 'C10086', role: '1' })} } catch (e) {}`
  })
  await b.goto(`${BASE}/#/customer/search?kw=电子用品`, 2600)
  const s = await b.evalJS(SHAPE)

  // ① 无左栏、全宽
  R.ok('搜索页：无左侧身份树', s.rail === false)
  R.ok('搜索页：工作区铺满可用宽', Math.abs(s.mainW - s.clientW) <= 1, `${s.mainW}/${s.clientW}`)
  R.ok('搜索页：无横向溢出', s.scrollW <= s.clientW + 1, `${s.scrollW}/${s.clientW}`)
  R.ok('旧左分类栏 <aside class="cat"> 不在 DOM 里', s.asideCat === false)
  R.ok('旧「更多」死按钮不残留', s.catMoreButtons === 0, s.catMoreButtons)
  R.ok('页面容器 = 可用宽（1440 上限内）', s.viewW === s.mainW, `${s.viewW}/${s.mainW}`)

  // ② 分类 / 过滤器横排顶部
  R.ok('分类 chip 条在位', s.cats === true)
  R.ok('分类 chip = 全部商品 + 5 个一级类目',
    s.chips.length === 6 && s.chips[0] === '全部商品', s.chips)
  R.ok('分类 chip 全部同一行（横排）', s.chipRows === 1, s.chipRows)
  R.ok('选中 chip 黑底反白', /rgb\(27, 30, 32\)\/rgb\(255, 255, 255\)/.test(s.chipBg), s.chipBg)
  R.ok('排序 / 筛选条在位', s.toolbar === true)
  R.ok('排序 / 筛选每组控件同排（两行都横排）',
    s.rows.length === 2 && s.rows[1].groups === 4 &&
    s.rows.every(r => r.distinctCenters === 1), s.rows)
  R.ok('搜索框回填 ?kw=', s.searchVal === '电子用品', s.searchVal)
  R.ok('搜索框在位且够大（≥ 46px）', s.searchH >= 46, s.searchH)

  // ③ 网格与首页一致
  R.ok('结果网格在位', s.grid === true)
  R.ok('瀑布流 = 3 栏（与首页一致）', s.gridStyle.count === '3', s.gridStyle)
  R.ok('瀑布流列距 = 12px（与首页一致）', s.gridStyle.gap === '12px', s.gridStyle.gap)
  R.ok('同列相邻卡下距 = 12px（砖墙无白缝）', s.sameColGap === 12, s.sameColGap)
  const wantCol = (s.wrapContent - 12 * 2) / 3
  R.ok('三栏等宽（列宽由内容区宽算出）',
    s.widths.length === 12 && s.widths.every(x => Math.abs(x - wantCol) <= 2),
    { got: s.widths.slice(0, 6), want: Math.round(wantCol) })
  R.ok('24 张商品卡（真帧）', s.cards === 24, s.cards)
  R.ok('商品卡链接走 7 参数契约',
    /^#\/product\?/.test(s.cardHref) && ['Id=', 'MerchantId=', 'GoodName=', 'Price=', 'GoodImg=', 'GoodType=', 'ProductCode='].every(k => s.cardHref.includes(k)),
    s.cardHref)
  R.ok('无限滚动哨兵在位', s.more === true)

  // 功能未丢：国补筛选仍按「登录 + 有国补商品」决定可用性
  R.ok('国补 chip 在位（无国补商品 ⇒ 禁用并给原因）',
    s.subsidy && s.subsidy.text === '国补商品' && s.subsidy.disabled === true, s.subsidy)

  await b.shot(`${EV}/search-public-1440.png`)
  await b.shotFull(`${EV}/search-public-full.png`)

  // 类目点击：仍调 categoryGoods，选中态切换，页面不跳走
  await b.evalJS(`[...document.querySelectorAll('.cats .cat-l .chip')][1].click()`)
  await sleep(900)
  const pick = await b.evalJS(`(() => {
    const on = [...document.querySelectorAll('.cats .cat-l .chip')].findIndex(c => c.classList.contains('on'))
    return { hash: location.hash, on, cards: document.querySelectorAll('.grid > *').length, page: !!document.querySelector('.goods-select-view') }
  })()`)
  R.ok('点一级类目：选中态落到该 chip', pick.on === 1, pick)
  R.ok('点一级类目：结果重载且仍在搜索页', pick.page && pick.cards === 24 && pick.hash.startsWith('#/customer/search'), `${pick.cards}/${pick.hash}`)

  // 回到「全部商品」
  await b.evalJS(`document.querySelectorAll('.cats .cat-l .chip')[0].click()`)
  await sleep(700)
  const all = await b.evalJS(`(() => { const on = [...document.querySelectorAll('.cats .cat-l .chip')].findIndex(c => c.classList.contains('on')); return { on, cards: document.querySelectorAll('.grid > *').length } })()`)
  R.ok('回「全部商品」：选中归零且结果 24 张', all.on === 0 && all.cards === 24, all)

  // 页内搜索：提交后仍在搜索页，关键词进 URL
  await b.evalJS(`(() => { const f = document.querySelector('.search'); f.querySelector('input').value = '茶器'; f.requestSubmit() })()`)
  await sleep(900)
  const sq = await b.evalJS(`({ hash: location.hash, cards: document.querySelectorAll('.grid > *').length })`)
  R.ok('页内搜索提交：URL 不变（关键词仍由顶栏承载）且结果在位',
    sq.hash.startsWith('#/customer/search') && sq.cards === 24, sq)

  // 断点：860 → 2 栏；600 → 1 栏
  await b.send('Emulation.setDeviceMetricsOverride', { width: 860, height: 1000, deviceScaleFactor: 1, mobile: false })
  await sleep(600)
  const w860 = await b.evalJS(`(() => { const g = document.querySelector('.grid'); return { count: getComputedStyle(g).columnCount, overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth } })()`)
  R.ok('断点 860：瀑布流 2 栏且无横向溢出', w860.count === '2' && w860.overflow <= 1, w860)

  await b.send('Emulation.setDeviceMetricsOverride', { width: 600, height: 1000, deviceScaleFactor: 1, mobile: false })
  await sleep(600)
  const w600 = await b.evalJS(`(() => { const g = document.querySelector('.grid'); return { count: getComputedStyle(g).columnCount, overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth } })()`)
  R.ok('断点 600：瀑布流 1 栏且无横向溢出', w600.count === '1' && w600.overflow <= 1, w600)
  await b.shot(`${EV}/search-public-600.png`)
} finally {
  await b.close()
}

const bad = R.dump()
console.log(`\n截图目录：${EV}`)
process.exit(bad ? 1 : 0)
