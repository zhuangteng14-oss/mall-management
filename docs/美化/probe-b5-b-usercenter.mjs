// probe-b5-b-usercenter.mjs —— C3 块 5 · B 组 **commit B**（个人中心重排）的几何 + 交互实测（2026-10-12）。
//
// 要验的是用户定稿的那 5 条布局规则 + 4 条裁定 + 手验三点里的 ②③，逐条对上：
//   商家端：我的订单（4）**在上** · 【无名框】7 项**在下** · 一行 4 块 · 第 2 行不足 4 块**左对齐**
//           · **容器级纵向滚动** · 隐藏滚动条 · **下边缘渐隐 78%**
//   用户端：我的订单（4）在上 · 无名框（4）在下 · 一行 4 块 · **不滚不渐隐**
//   **③ 卡片式统一**（2026-10-12 手验三点之③）→ **同日第二轮「甲案：上亮下素」**：
//           `.tiles` 四格与 `.assets` 11 格**共用同一套三段解剖**（图标 / 名称 / 值，图标是
//           `<svg.card-ic>`，来自 `@lucide/vue`），但**卡壳分家**：
//             上半 `.tiles` = 浅绿底 `--brand-tint` + **无边框** + `--r2`，**竖向**（图标在名称上方）；
//             下半 `.assets` = **白底 `--surface` + 1px 发丝线 + `--r1`**，**横向**
//                              （图标在左、名称与值两行在右）⇒ 卡片**矮掉约三分之一**。
//           ⇒ 本份探针里凡是「两处壳逐值相等」的断言都**翻转**成「两处壳必须不同」。
//   **同日第三轮**（2026-10-12，用户「白底 + 更深边框」）⇒ 下半边色由 `--line` 加深成 `--line-2`。
//           ⚠️ **底色没变过**：用户说的「浅灰底」从不曾存在（`--surface` 自 ③ 起就是 `#ffffff`，
//              实测涂色 `rgb(255,255,255)`、页面底 `.app-shell` 也是白）⇒ 这一轮**没有底色可撤**。
//              ⇒ 探针这一轮新增两条：边色 == 同页 `.f input` 的边色（都吃 `--line-2`）· 边色**不是** `--line`。
//   交互：店铺信息跳主页（commit C 才有那条路由）· 认证 + 等级**弹窗** · 认证读真状态
//
// ⚠️ 与「_cdp.mjs 页面探针自带后端 mock」那条约定的关系：本份**用 mock**（`open({mock})`）——
//   三格要的三种身份（无客户号的商家 / 有客户号的商家 / 客户）在**同一个库**里凑不齐，
//   而且「认证四态」要一份份换着看 ⇒ 就地伪造 8088 比改库更可控。**只伪造、不落数据。**
//
// 用法（**只要前端 8080 活着**，后端不参与）：
//     node docs/美化/probe-b5-b-usercenter.mjs
import fs from 'node:fs'
import { open, sleep, report } from './_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const MASK_STOP = '78%'
const CUSTOMER_ID = 'uDopHnCeLS'
const MERCHANT_ID = '5N1k5K2guh'

/** `/user/me` 的载荷 —— `Result.ok(payload)` 是**平铺**（`{success:true, ...payload}`），下同。 */
const me = (over) => Object.assign({
  success: true, msg: 'ok',
  role: '2', username: '2222', merchantid: MERCHANT_ID, customerid: '',
  image: '', usertype: '商家', gender: '', age: null,
  email: '', address: '', selfintroduction: '',
  DowngradeState: 'none', ScheduledDowngradeAt: null,
  FrozenAt: null, FreezeUntil: null, DowngradeReason: null,
}, over)

const apps = (status, reason) => status
  ? { success: true, total: 1, limit: 1, list: [{ Status: status, RejectReason: reason || null, SubmittedAt: '2026-10-01 12:00:00' }] }
  : { success: true, total: 0, limit: 1, list: [] }

const MOCK = (meBody, appsBody) => (url) => {
  if (url.includes('/user/me')) return { body: JSON.stringify(meBody) }
  if (url.includes('/merchant/myApplications')) return { body: JSON.stringify(appsBody) }
  // 券包摘要（`loadWallet` → `/marketing/myCoupons`）—— 只给「有客户号」那两例用得上
  if (url.includes('/marketing/myCoupons')) {
    return { body: JSON.stringify({ success: true, redDot: 0, list: [{ Bucket: 'usable' }, { Bucket: 'used' }] }) }
  }
  // 兜底：其余一概空成功 **+ 一个空 list**（本探针不看它们，但壳里别的组件会读 `list.length`）
  // ⚠️ 兜底只回 `{"success":true}` 时，壳里某个列表组件会抛 `TypeError: reading 'length'`
  //    —— 实测对照过：粗兜底 1 条异常 / 带 `list` 兜底 0 条 ⇒ **那是 mock 的形状问题，不是页面缺陷**
  //    （`_work/_diag-b5b.mjs` 那两组对照就是为分清这两件事跑的）。
  return { body: '{"success":true,"list":[],"total":0,"limit":0}' }
}

// 一次量到底：容器几何 / 行分组 / 格序 / 卡片外壳 / 两枚弹窗 / 与「我的订单」的先后
// ⚠️ 卡片三段的选择器：图标 `svg.card-ic` · 名称 `b` · 值 `span`
//    —— `③ 卡片式统一`把原来的 `.v`（大字）/ `.k`（小标签）**换掉了**，两个选择器已不存在。
const MEASURE = `(() => {
  const main = document.querySelector('.uc-main')
  if (!main) return { err: 'no .uc-main', text: (document.body.innerText || '').slice(0, 200) }
  const mr = main.getBoundingClientRect()
  const box = main.querySelector('section.assets')
  const tiles = main.querySelector('.tiles')
  const h2s = [...main.querySelectorAll('.sec-h h2')].map((h) => h.textContent.trim())
  if (!box) return { err: 'no section.assets', h2s }
  const br = box.getBoundingClientRect()
  const cs = getComputedStyle(box)
  const cells = [...box.children]
  const cell = (c) => {
    const r = c.getBoundingClientRect()
    const ic = c.querySelector('svg.card-ic')
    const ics = ic ? getComputedStyle(ic) : null
    const bEl = c.querySelector('b'), sEl = c.querySelector('span')
    const ir = ic ? ic.getBoundingClientRect() : null
    const brB = bEl ? bEl.getBoundingClientRect() : null
    const srB = sEl ? sEl.getBoundingClientRect() : null
    return {
      k: (bEl || {}).textContent || null,
      v: (sEl || {}).textContent || null,
      ic: !!ic,
      icSize: ics ? ics.width + 'x' + ics.height : null,
      icColor: ics ? ics.color : null,
      icFirst: !!ic && c.firstElementChild === ic,     // 图标必须是卡片**第一个**子元素（上下半都该如此）
      icAboveText: !!ic && !!brB && (ir.bottom <= brB.top + 0.5),          // 上半版式：图标在名称**上方**
      icLeftOfName: !!ic && !!brB && (ir.right <= brB.left + 0.5),         // 下半版式：图标在名称**左侧**
      icCenteredInText: !!ic && !!brB && !!srB &&                          // 下半版式：图标在「名称+值」两行里垂直居中
        Math.abs((ir.top + ir.bottom) / 2 - (brB.top + srB.bottom) / 2) <= 1.5,
      nameFs: bEl ? getComputedStyle(bEl).fontSize : null,
      nameColor: bEl ? getComputedStyle(bEl).color : null,
      valFs: sEl ? getComputedStyle(sEl).fontSize : null,
      valColor: sEl ? getComputedStyle(sEl).color : null,
      left: +(r.left - br.left).toFixed(1),
      top: +(r.top - br.top).toFixed(1),
      w: +r.width.toFixed(1),
      h: +r.height.toFixed(1),
      entry: c.classList.contains('asset-entry'),
      tag: c.tagName, tabindex: c.getAttribute('tabindex'),
    }
  }
  const groups = new Map()
  cells.forEach((c) => {
    const r = c.getBoundingClientRect()
    const key = Math.round(r.top - br.top)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(cell(c))
  })
  const keys = [...groups.keys()].sort((a, b) => a - b)
  // 卡片外壳：assets 的格子 vs tiles 的按钮 —— 「甲案」之后**五项里三项必须不同**
  // （bg / border / radius）、一项必须相同（pad），另一项两边都是 none（shadow）
  const shell = (el) => { const s = getComputedStyle(el)
    return { bg: s.backgroundColor, border: s.borderTopWidth + ' ' + s.borderTopStyle + ' ' + s.borderTopColor,
             radius: s.borderRadius, pad: s.padding, shadow: s.boxShadow } }
  const tilesBtns = tiles ? [...tiles.querySelectorAll('button')] : []
  return {
    cls: box.className,
    h2s,
    count: cells.length,
    boxTop: +(br.top - mr.top).toFixed(1),
    tilesTop: tiles ? +(tiles.getBoundingClientRect().top - mr.top).toFixed(1) : null,
    clientH: box.clientHeight, scrollH: box.scrollHeight,
    maxH: cs.maxHeight, overflowY: cs.overflowY, scrollbarWidth: cs.scrollbarWidth,
    mask: cs.maskImage === 'none' ? null : cs.maskImage,
    webkitMask: cs.webkitMaskImage === 'none' ? null : cs.webkitMaskImage,
    assetsRowVar: cs.getPropertyValue('--assets-row').trim(),
    rows: keys.map((k) => ({ top: k, n: groups.get(k).length, cells: groups.get(k) })),
    orderOK: tiles ? tiles.getBoundingClientRect().top < br.top : null,
    // ── ③ 卡片式统一 ──
    shellAssets: cells.length ? shell(cells[0]) : null,
    shellTiles: tilesBtns.length ? shell(tilesBtns[0]) : null,
    // ── 第三轮「加容器层」：容器 .assets-box 的外壳 + 四条边**分别**取（要证「四条边都在」）──
    // 四条边分开量的理由：容器的**底边**正是「渐隐会不会把边界吃掉」这个风险的观察点 ——
    // 只要容器是 .assets 的**父元素**、且它自己**不带 mask**，底边就完整。
    boxShell: (() => { const e = box.parentElement
      if (!e || !e.classList.contains('assets-box')) return null
      const s = getComputedStyle(e)
      const edge = (k) => s[k + 'Width'] + ' ' + s[k + 'Style'] + ' ' + s[k + 'Color']
      return { pad: s.padding, radius: s.borderRadius, bg: s.backgroundColor, shadow: s.boxShadow,
               top: edge('borderTop'), bottom: edge('borderBottom'),
               left: edge('borderLeft'), right: edge('borderRight'),
               mask: s.maskImage === 'none' ? null : s.maskImage,
               radiusPx: s.borderTopLeftRadius, mt: s.marginTop,
               h: +e.getBoundingClientRect().height.toFixed(1) } })(),
    assetsMarginTop: cs.marginTop,
    // 「白底 + 更深边框」那一轮的两个对照：同页 .f input 的边色（也吃 --line-2）+ 页面底色
    // ⚠️ 这段是**模板字符串**：注释里也不能出现反引号或美元大括号 —— 会把模板提前闭合。
    fInputBorder: (() => { const e = document.querySelector('.f input')
      if (!e) return null; const s = getComputedStyle(e)
      return s.borderTopWidth + ' ' + s.borderTopStyle + ' ' + s.borderTopColor })(),
    pageBg: (() => { const e = document.querySelector('.app-shell') || document.body
      return getComputedStyle(e).backgroundColor })(),
    assetsIcons: cells.filter((c) => c.querySelector('svg.card-ic')).length,
    tiles: tilesBtns.map((b) => ({
      k: (b.querySelector('b') || {}).textContent || null,
      v: (b.querySelector('span') || {}).textContent || null,
      ic: !!b.querySelector('svg.card-ic'),
      icAboveText: (() => { const i = b.querySelector('svg.card-ic'), n = b.querySelector('b')
        return !!i && !!n && (i.getBoundingClientRect().bottom <= n.getBoundingClientRect().top + 0.5) })(),
      nameFs: (() => { const n = b.querySelector('b'); return n ? getComputedStyle(n).fontSize : null })(),
      nameColor: (() => { const n = b.querySelector('b'); return n ? getComputedStyle(n).color : null })(),
      valColor: (() => { const n = b.querySelector('span'); return n ? getComputedStyle(n).color : null })(),
      tag: b.tagName, disabled: b.disabled,
      w: +b.getBoundingClientRect().width.toFixed(1),
      h: +b.getBoundingClientRect().height.toFixed(1),
    })),
    // 4 格是 BUTTON ⇒ **整块**就是命中区（不是只有文字）；但它们是「待 C3〈订单〉」的占位
    tilesWholeBlock: tilesBtns.length ? tilesBtns.every((b) => b.tagName === 'BUTTON') : null,
    scrim: !!document.querySelector('.mc-scrim'),
    scrimTitle: (() => { const s = document.querySelector('.mc-dlg-h span'); return s ? s.textContent.trim() : null })(),
    scrimState: (() => { const s = document.querySelector('.mc-state'); return s ? s.textContent.trim() : null })(),
    scrimWhy: (() => { const s = document.querySelector('.mc-why'); return s ? s.textContent.trim() : null })(),
    walletDialog: !!document.querySelector('.cscrim'),
  }
})()`

const errs = []

// 卡片高的期望值（与 <style> 里的算式同源；探针只做**独立复算**，不读 CSS 变量）
//   上半 .tiles（竖向，③ 那版，本轮**一行未动**）：
//     --s4×2 + --fs-lg + --s3 + --fs-md×1.4 + --s2 + --fs-xs×1.4 = 32+24+12+26.6+8+16.8 = 119.4
//   下半 .assets（横向，「甲案」新版）：
//     --s4×2 + (--fs-sm×1.4 + --s2 + --fs-xs×1.4) + 两条 1px 边框 = 32 + (21+8+16.8) + 2 = 79.8
//     ⚠️ 没有 --fs-lg（图标 24 < 文字块 45.8，卡高由文字块定）
const CARD_H_TILES = 32 + 24 + 12 + 19 * 1.4 + 8 + 12 * 1.4   // 119.4
// ⚠️ 第三轮「加容器层」把卡片自己那圈框撤了 ⇒ 算式里**删掉 `+ 2`**（卡高 79.8 → 77.8），
//    对应 `UserInfoPage.vue` 里 `--assets-row` 那条（两处必须同步，否则算出来的容器高不是渲染值）。
const CARD_H_ASSETS = 32 + (15 * 1.4 + 8 + 12 * 1.4)          // 77.8
// 容器高 = 一卡 + 一格距（--s3）+ --s5
const CONTAINER_H = CARD_H_ASSETS + 12 + 24                   // 113.8（滚动区 max-height）
// 容器（`.assets-box`）的**外高** = 滚动区 + 内距×2（`--s4`）+ 边×2。两壳都该是这个算式。
// ⚠️ 客户壳没有滚动，它的盒子 = 一行卡 + 内距 + 边 = 77.8 + 32 + 2 = 111.8。
const BOX_H = CONTAINER_H + 16 * 2 + 2                        // 147.8（商家壳）
const BOX_H_C = CARD_H_ASSETS + 16 * 2 + 2                    // 111.8（客户壳）

/** 换一份 mock = 换一个身份 —— `Fetch.enable` 的回调是闭包，只能重开浏览器。 */
async function withMock(meBody, appsBody, fn) {
  const bb = await open({ w: 1440, h: 900, headed: false, mock: MOCK(meBody, appsBody) })
  bb.ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params.exceptionDetails
      errs.push(String((d.exception && d.exception.description) || d.text).slice(0, 200))
    }
  })
  try {
    await bb.goto('http://localhost:8080/', 2500)
    await bb.evalJS(`(() => { sessionStorage.setItem('username','2222');
      sessionStorage.setItem('merchantid','${MERCHANT_ID}'); sessionStorage.setItem('role','2'); return 1 })()`)
    return await fn(bb)
  } finally { await bb.close() }
}

const r = report('B 组 commit B · 个人中心重排 + ③ 卡片式统一')
const out = {}

// ══ 一、商家端：无客户号（= VM 库里 14/14 商家的真实形态，边界 2 的空态）══
await withMock(me({ customerid: '' }), apps('approved'), async (bb) => {
  await bb.goto('http://localhost:8080/#/merchant/user/info', 2600)
  await sleep(900)
  const m = await bb.evalJS(MEASURE)
  out['M-none'] = m
  console.log('\n── M-none · 商家 · customerid 为空 · 认证 approved ──\n' + JSON.stringify(m, null, 1))
  if (!m || m.err) { r.ok('M-none 页面渲染出来', false, m && m.err); return }

  r.ok('M-none 我的订单在【无名框】上方', m.orderOK === true, { tilesTop: m.tilesTop, assetsTop: m.boxTop })
  r.ok('M-none 无名框 7 格', m.count === 7, m.count)
  r.ok('M-none 格序 == 积分/优惠券/余额/我的收藏/店铺信息/商家认证/经营等级',
    JSON.stringify(m.rows.flatMap((x) => x.cells.map((c) => c.k))) ===
    JSON.stringify(['积分', '优惠券', '余额', '我的收藏', '店铺信息', '商家认证', '经营等级']),
    m.rows.flatMap((x) => x.cells.map((c) => c.k)))
  r.ok('M-none 一行 4 块 → 换行 → 第 2 行 3 块', m.rows.length === 2 && m.rows[0].n === 4 && m.rows[1].n === 3,
    m.rows.map((x) => [x.top, x.n]))
  r.ok('M-none 第 2 行**左对齐**（首格 left == 第 1 行首格 left）',
    m.rows[1] && m.rows[0] && Math.abs(m.rows[1].cells[0].left - m.rows[0].cells[0].left) < 0.6,
    { r1: m.rows[0].cells[0].left, r2: m.rows[1] && m.rows[1].cells[0].left })
  r.ok('M-none 容器挂上 .assets-scroll', /(^|\s)assets-scroll(\s|$)/.test(m.cls), m.cls)
  r.ok('M-none 容器**可滚**（scrollH > clientH）', m.scrollH > m.clientH + 1, { scrollH: m.scrollH, clientH: m.clientH })
  r.ok('M-none 第 1 行整行可见（底边 <= 容器高）',
    m.rows[0] && m.rows[0].cells[0].top + m.rows[0].cells[0].h <= m.clientH + 0.5,
    { row1Bottom: m.rows[0] && +(m.rows[0].cells[0].top + m.rows[0].cells[0].h).toFixed(1), clientH: m.clientH })
  r.ok('M-none 第 2 行**只露一段**（16 <= clientH − row2Top <= 48px ≈ 三分之一行高）',
    m.rows[1] && (m.clientH - m.rows[1].top) >= 16 && (m.clientH - m.rows[1].top) <= 48,
    m.rows[1] && { peek: +(m.clientH - m.rows[1].top).toFixed(1), row1H: m.rows[0].cells[0].h })
  r.ok('M-none 下边缘渐隐在（mask + -webkit-mask 都写，' + MASK_STOP + ' 起淡）',
    !!m.mask && m.mask.includes(MASK_STOP) && !!m.webkitMask && m.webkitMask.includes(MASK_STOP),
    { mask: m.mask, webkitMask: m.webkitMask })
  r.ok('M-none 隐藏原生滚动条（scrollbar-width:none）', m.scrollbarWidth === 'none', m.scrollbarWidth)

  // ── ③→甲 卡片：`.assets` 7 格（**下半**，本轮改的就是它）──
  r.ok('③ M-none 7 格**每格都有图标**（`svg.card-ic`）', m.assetsIcons === 7, m.assetsIcons)
  r.ok('③甲 M-none 图标是卡片的**第一个子元素**、在**名称左侧**、并在「名称+值」两行里**垂直居中**',
    m.rows.flatMap((x) => x.cells).every((c) => c.icFirst && c.icLeftOfName && c.icCenteredInText),
    m.rows.flatMap((x) => x.cells).map((c) => [c.k, c.icFirst, c.icLeftOfName, c.icCenteredInText]))
  r.ok('③甲 M-none 图标**不再**在名称上方（版式已由竖向改横向）',
    m.rows.flatMap((x) => x.cells).every((c) => c.icAboveText === false),
    m.rows.flatMap((x) => x.cells).map((c) => [c.k, c.icAboveText]))
  r.ok('③ M-none 图标边长 == --fs-lg（24×24）、色相 == --brand（#3b7966）',
    m.rows[0].cells[0].icSize === '24pxx24px' && m.rows[0].cells[0].icColor === 'rgb(59, 121, 102)',
    { size: m.rows[0].cells[0].icSize, color: m.rows[0].cells[0].icColor })
  r.ok('③甲 M-none 下半卡高 == 横向算式（' + CARD_H_ASSETS.toFixed(1) + 'px）',
    Math.abs(m.rows[0].cells[0].h - CARD_H_ASSETS) < 1.2,
    { got: m.rows[0].cells[0].h, want: +CARD_H_ASSETS.toFixed(1) })
  r.ok('③甲 M-none 容器高 == 一卡 + 一格距 + --s5（' + CONTAINER_H.toFixed(1) + 'px）',
    Math.abs(m.clientH - CONTAINER_H) < 1.5, { got: m.clientH, want: +CONTAINER_H.toFixed(1) })
  r.ok('③甲 **上下半分块手段不同轴**：.assets = 白底 **+ 无边框** + --r1；.tiles = 浅绿底 + --r2',
    !!m.shellAssets && !!m.shellTiles &&
      m.shellAssets.bg === 'rgb(255, 255, 255)' && m.shellAssets.radius === '4px' &&
      /(^| )0px /.test(m.shellAssets.border) &&
      m.shellTiles.bg === 'rgb(238, 252, 246)' && m.shellTiles.radius === '8px' &&
      m.shellAssets.bg !== m.shellTiles.bg && m.shellAssets.radius !== m.shellTiles.radius,
    { assets: m.shellAssets, tiles: m.shellTiles })
  r.ok('③甲三轮 **下半卡自己那圈框已撤**（第三轮「加容器层」：边界改由容器承担）',
    /(^| )0px /.test(m.shellAssets.border) && /(^| )0px /.test(m.shellTiles.border),
    { assets: m.shellAssets.border, tiles: m.shellTiles.border })
  // ── 第三轮「加容器层」：**边界在容器上**，而且是**四条边都在**（底边最危险 —— 见 boxShell 那段注释）──
  r.ok('③甲三轮 容器 `.assets-box` 存在，且**四条边都是 1px 实线**（底边没被渐隐吃掉）',
    !!m.boxShell && [m.boxShell.top, m.boxShell.bottom, m.boxShell.left, m.boxShell.right]
      .every((e) => /^1px solid rgb\(208, 214, 217\)$/.test(e)),
    m.boxShell && { top: m.boxShell.top, bottom: m.boxShell.bottom, left: m.boxShell.left, right: m.boxShell.right })
  r.ok('③甲三轮 容器边色 == 同页 `.f input` 的边色（都吃 `--line-2`）⇒ 与「有框的盒 / 控件」同族',
    !!m.boxShell && m.fInputBorder === m.boxShell.top, { box: m.boxShell && m.boxShell.top, input: m.fInputBorder })
  r.ok('③甲三轮 容器边色**不是 `--line`**（dde2e5 更浅；本页 `--line` 只用于盒内分隔线）',
    !!m.boxShell && m.boxShell.top === '1px solid rgb(208, 214, 217)' &&
      m.boxShell.top !== '1px solid rgb(221, 226, 229)', m.boxShell && m.boxShell.top)
  r.ok('③甲三轮 容器**自己不带 `mask-image`**（渐隐只落在滚动区上 ⇒ 才不会吃掉自己的边）',
    !!m.boxShell && m.boxShell.mask === null && !!m.mask && !!m.webkitMask,
    m.boxShell && { box: m.boxShell.mask, scroller: m.mask })
  r.ok('③甲三轮 容器 --r2（8px）+ 内距 --s4（16px）+ 无阴影',
    !!m.boxShell && m.boxShell.radiusPx === '8px' && m.boxShell.pad === '16px' &&
      m.boxShell.shadow === 'none',
    m.boxShell && { r: m.boxShell.radiusPx, pad: m.boxShell.pad, shadow: m.boxShell.shadow })
  r.ok('③甲三轮 `margin-top` 已**从 `.assets` 搬到 `.assets-box`**（不是两份）',
    !!m.boxShell && m.boxShell.mt === '76.8px' && m.assetsMarginTop === '0px',
    { box: m.boxShell && m.boxShell.mt, assets: m.assetsMarginTop })
  r.ok('③甲三轮 容器**外高 == 算式 ' + BOX_H.toFixed(1) + 'px**（滚动区 + 内距×2 + 边×2）',
    !!m.boxShell && Math.abs(m.boxShell.h - BOX_H) < 1.5,
    { got: m.boxShell && m.boxShell.h, want: +BOX_H.toFixed(1) })
  r.ok('③甲二轮 下半**仍是白底**（用户要的「白底」；所谓「浅灰底」从不曾存在）—— 卡面与**容器面**都是白',
    m.shellAssets.bg === 'rgb(255, 255, 255)' && !!m.boxShell &&
      m.boxShell.bg === 'rgb(255, 255, 255)' && m.pageBg === 'rgb(255, 255, 255)',
    { card: m.shellAssets.bg, box: m.boxShell && m.boxShell.bg, page: m.pageBg })
  r.ok('③甲 **两处都没有一条 box-shadow**（本体系用发丝线、不用阴影）',
    m.shellAssets.shadow === 'none' && m.shellTiles.shadow === 'none',
    { assets: m.shellAssets.shadow, tiles: m.shellTiles.shadow })
  r.ok('③甲 上下半**内边距仍同一档**（--s4 = 16px，只有这一项相等）',
    m.shellAssets.pad === '16px' && m.shellTiles.pad === '16px',
    { assets: m.shellAssets.pad, tiles: m.shellTiles.pad })
  r.ok('③甲 下半字阶降一档：名称 15px/--ink（27,30,32）、值 12px/--ink-3（99,105,109）',
    m.rows[0].cells[0].nameFs === '15px' && m.rows[0].cells[0].nameColor === 'rgb(27, 30, 32)' &&
      m.rows[0].cells[0].valFs === '12px' && m.rows[0].cells[0].valColor === 'rgb(99, 105, 109)',
    { nameFs: m.rows[0].cells[0].nameFs, nameColor: m.rows[0].cells[0].nameColor,
      valFs: m.rows[0].cells[0].valFs, valColor: m.rows[0].cells[0].valColor })
  r.ok('③ M-none 「我的订单」四格也**各带图标**', m.tiles.length === 4 && m.tiles.every((t) => t.ic === true),
    m.tiles.map((t) => [t.k, t.ic]))
  r.ok('③ M-none 四格是 `<button>` ⇒ **整块**就是命中区（不是只有文字可点）',
    m.tilesWholeBlock === true, m.tiles.map((t) => t.tag))
  r.ok('③甲 **上半仍是 ③ 那版、一行未动**：卡高 ' + CARD_H_TILES.toFixed(1) +
    'px + 名称 19px/--brand-ink（39,94,78）+ 图标仍在名称**上方**',
    Math.abs(m.tiles[0].h - CARD_H_TILES) < 1.2 &&
      m.tiles[0].nameFs === '19px' && m.tiles[0].nameColor === 'rgb(39, 94, 78)' &&
      m.tiles[0].valColor === 'rgb(76, 81, 85)' && m.tiles.every((t) => t.icAboveText === true),
    { h: m.tiles[0].h, fs: m.tiles[0].nameFs, color: m.tiles[0].nameColor,
      valColor: m.tiles[0].valColor, above: m.tiles.map((t) => t.icAboveText) })
  r.ok('③甲 上半四格比下半卡**高一档**（结构差，不只是颜色差）',
    m.tiles[0].h - m.rows[0].cells[0].h > 30,
    { tilesH: m.tiles[0].h, cardH: m.rows[0].cells[0].h, gap: +(m.tiles[0].h - m.rows[0].cells[0].h).toFixed(1) })

  // ── ③甲 hover 口径：换**边色**（.btn-plain 那条同款）而**不是底色** ──
  // ⚠️ 用 CDP 的 `CSS.forcePseudoState` 强制 `:hover` —— 真鼠标悬停要求元素在视口内，
  //    而第 1 枚 `.asset-entry`（店铺信息）在第 2 行、不一定可见。包 try/catch：
  //    这条挂了要看得见是「这条没测到」，不能让整份探针崩掉。
  let hv = null
  try {
    await bb.send('DOM.enable'); await bb.send('CSS.enable')
    const doc = await bb.send('DOM.getDocument', { depth: 1 })
    const q = await bb.send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '.asset-entry' })
    await bb.send('CSS.forcePseudoState', { nodeId: q.nodeId, forcedPseudoClasses: ['hover'] })
    await sleep(300)
    hv = await bb.evalJS(`(() => { const e = document.querySelector('.asset-entry'); const s = getComputedStyle(e)
      return { k: (e.querySelector('b') || {}).textContent, bg: s.backgroundColor,
               borderW: s.borderTopWidth, cursor: s.cursor } })()`)
    await bb.send('CSS.forcePseudoState', { nodeId: q.nodeId, forcedPseudoClasses: [] })
  } catch (e) { hv = { err: String(e).slice(0, 140) } }
  // ⚠️ 第三轮卡片无边框 ⇒ hover 改成**底色** `--surface-2`（242,245,247）。
  //    顺带：这**正好落回** `tokens.css:37`「（hover 用底色变化）」的字面 ⇒ 上一轮登记的那条
  //    「偏离 tokens.css:37」**作废**。断言仍写**值**，因为这里要钉的是「不是 `--brand-soft`」
  //    （下半不引绿）—— 若哪天有人把下半 hover 也改成绿，这条会红。
  r.ok('③甲三轮 hover 换的是**底色 --surface-2（242,245,247）**（卡片无边框；下半不引绿）、光标 pointer',
    !!hv && hv.bg === 'rgb(242, 245, 247)' && hv.borderW === '0px' && hv.cursor === 'pointer', hv)

  r.ok('M-none 边界 2：无客户号 ⇒ 券格**不可点**（不是 .asset-entry）',
    m.rows[0].cells[1].k === '优惠券' && m.rows[0].cells[1].entry === false,
    { k: m.rows[0].cells[1].k, entry: m.rows[0].cells[1].entry, v: m.rows[0].cells[1].v })
  r.ok('M-none 边界 2：券格空白态显示 —', m.rows[0].cells[1].v === '—', m.rows[0].cells[1].v)
  r.ok('M-none 认证格读真状态（approved ⇒ 已认证）',
    m.rows[1].cells[1].k === '商家认证' && m.rows[1].cells[1].v === '已认证',
    { k: m.rows[1].cells[1].k, v: m.rows[1].cells[1].v })
  r.ok('M-none 三枚真入口都是 .asset-entry + tabindex=0（键盘可达）',
    m.rows[1].cells.every((c) => c.entry === true && c.tabindex === '0'),
    m.rows[1].cells.map((c) => [c.k, c.entry, c.tabindex]))
  r.ok('M-none 经营等级占位落 —（裁定 ④）', m.rows[1].cells[2].v === '—', m.rows[1].cells[2].v)

  // ── 交互：认证弹窗 ──
  await bb.evalJS(`[...document.querySelectorAll('.asset-entry')].find((e)=>e.textContent.includes('商家认证')).click()`)
  await sleep(400)
  let s = await bb.evalJS(MEASURE)
  out['M-none-cert'] = s
  r.ok('点「商家认证」⇒ 遮罩出现', s.scrim === true, s.scrim)
  r.ok('认证弹窗标题 == 商家认证', s.scrimTitle === '商家认证', s.scrimTitle)
  r.ok('认证弹窗大字 == 已认证', s.scrimState === '已认证', s.scrimState)
  r.ok('认证弹窗正文带提交时刻', /2026-10-01 12:00:00/.test(s.scrimWhy || ''), s.scrimWhy)
  await bb.shot(EV + 'out-b5b-usercenter-M-cert-approved.png')
  await bb.evalJS(`document.querySelector('.mc-x').click()`)
  await sleep(300)
  s = await bb.evalJS(MEASURE)
  r.ok('点 × ⇒ 遮罩关掉', s.scrim === false, s.scrim)

  // ── 交互：等级弹窗（裁定 ④「点击弹窗提示功能开发中」）──
  await bb.evalJS(`[...document.querySelectorAll('.asset-entry')].find((e)=>e.textContent.includes('经营等级')).click()`)
  await sleep(400)
  s = await bb.evalJS(MEASURE)
  out['M-none-level'] = s
  r.ok('点「经营等级」⇒ 遮罩出现', s.scrim === true, s.scrim)
  r.ok('等级弹窗大字 == —（占位）', s.scrimState === '—', s.scrimState)
  r.ok('等级弹窗正文同时有「敬请期待」与「功能开发中」',
    /敬请期待/.test(s.scrimWhy || '') && /功能开发中/.test(s.scrimWhy || ''), s.scrimWhy)
  await bb.shot(EV + 'out-b5b-usercenter-M-level.png')
  await bb.evalJS(`document.querySelector('.mc-x').click()`)
  await sleep(300)

  // ── 点遮罩空白处也关（@click.self）──
  await bb.evalJS(`[...document.querySelectorAll('.asset-entry')].find((e)=>e.textContent.includes('经营等级')).click()`)
  await sleep(300)
  await bb.evalJS(`document.querySelector('.mc-scrim').click()`)
  await sleep(300)
  s = await bb.evalJS(MEASURE)
  r.ok('点遮罩空白处 ⇒ 也关（@click.self）', s.scrim === false, s.scrim)

  await bb.shot(EV + 'out-b5b-usercenter-M-none.png')

  // 再滚到底留一张 —— 第 2 行三格（店铺信息 / 商家认证 / 经营等级）的图标与副文案才是真入口那三枚
  await bb.evalJS(`(() => { const b = document.querySelector('.assets-scroll'); b.scrollTop = b.scrollHeight; return b.scrollTop })()`)
  await sleep(350)
  await bb.shot(EV + 'out-b5b-usercenter-M-none-scrolled.png')
})

// ══ 二、商家端：**有**客户号（promoteToMerchant 那条路来的商家）+ 认证四态 ══
for (const [tag, status, want, reason] of [
  ['approved', 'approved', '已认证', null],
  ['pending', 'pending', '审核中', null],
  ['rejected', 'rejected', '已驳回', '营业执照照片不清晰'],
  ['none', null, '未提交', null],
]) {
  await withMock(me({ customerid: CUSTOMER_ID }), apps(status, reason), async (bb) => {
    await bb.goto('http://localhost:8080/#/merchant/user/info', 2600)
    await sleep(900)
    const m = await bb.evalJS(MEASURE)
    out['M-cert-' + tag] = m
    if (!m || m.err) { r.ok(`M-cert-${tag} 页面渲染出来`, false, m && m.err); return }
    r.ok(`M-cert-${tag} 认证四态映射 ⇒ ${want}`,
      m.rows[1].cells[1].v === want, { got: m.rows[1].cells[1].v, want })
    r.ok(`M-cert-${tag} 有客户号 ⇒ 券格**可点**（.asset-entry + tabindex）`,
      m.rows[0].cells[1].entry === true && m.rows[0].cells[1].tabindex === '0',
      { entry: m.rows[0].cells[1].entry, tabindex: m.rows[0].cells[1].tabindex })
  })
}

// ══ 三、客户壳：4 项、**不滚不渐隐**，下半卡与商家壳**同款**（白底 + 发丝线 + 横向） ══
await withMock(me({ role: '1', username: '1111', merchantid: '', customerid: CUSTOMER_ID, usertype: '客户' }), apps(null), async (bb) => {
  await bb.goto('http://localhost:8080/#/customer/user/info', 2600)
  await sleep(900)
  const m = await bb.evalJS(MEASURE)
  out['C-customer'] = m
  console.log('\n── C · 客户壳 ──\n' + JSON.stringify(m, null, 1))
  if (!m || m.err) { r.ok('C 页面渲染出来', false, m && m.err); return }

  r.ok('C 我的订单在【无名框】上方', m.orderOK === true, { tilesTop: m.tilesTop, assetsTop: m.boxTop })
  r.ok('C 无名框 4 格', m.count === 4, m.count)
  r.ok('C 一行 4 块（不换行）', m.rows.length === 1 && m.rows[0].n === 4, m.rows.map((x) => [x.top, x.n]))
  r.ok('C 格序 == 积分/优惠券/余额/我的收藏',
    JSON.stringify(m.rows[0].cells.map((c) => c.k)) === JSON.stringify(['积分', '优惠券', '余额', '我的收藏']),
    m.rows[0].cells.map((c) => c.k))
  r.ok('C **不加** .assets-scroll（4 项一行放得下）', !/assets-scroll/.test(m.cls), m.cls)
  r.ok('C **不可滚**（scrollH <= clientH）', m.scrollH <= m.clientH + 1, { scrollH: m.scrollH, clientH: m.clientH })
  r.ok('C **无渐隐**（mask 为 none）', m.mask === null && m.webkitMask === null, { mask: m.mask, webkitMask: m.webkitMask })
  r.ok('③ C 4 格**每格都有图标**', m.assetsIcons === 4, m.assetsIcons)
  r.ok('③甲三轮 C 下半与商家壳**同款**（白底 + **无边框** + --r1 + 横向：图标左 / 两行文字右）',
    !!m.shellAssets && m.shellAssets.bg === 'rgb(255, 255, 255)' && m.shellAssets.radius === '4px' &&
      /(^| )0px /.test(m.shellAssets.border) && m.shellAssets.shadow === 'none' &&
      m.rows[0].cells.every((c) => c.icFirst && c.icLeftOfName && c.icCenteredInText),
    { assets: m.shellAssets, two: m.rows[0].cells.map((c) => [c.k, c.icLeftOfName, c.icCenteredInText]) })
  r.ok('③甲三轮 C **也有容器**（两壳同款容器；4 项放得下 ⇒ 只是不滚，不是不装盒）',
    !!m.boxShell && /^1px solid rgb\(208, 214, 217\)$/.test(m.boxShell.top) &&
      m.boxShell.pad === '16px' && m.boxShell.radiusPx === '8px',
    m.boxShell && { top: m.boxShell.top, pad: m.boxShell.pad, r: m.boxShell.radiusPx })
  r.ok('③甲三轮 C 容器外高 == 算式 ' + BOX_H_C.toFixed(1) + 'px（一行卡 + 内距×2 + 边×2）',
    !!m.boxShell && Math.abs(m.boxShell.h - BOX_H_C) < 1.5,
    { got: m.boxShell && m.boxShell.h, want: +BOX_H_C.toFixed(1) })
  r.ok('③甲 C 4 格卡高 == ' + CARD_H_ASSETS.toFixed(1) + 'px（与商家壳下半同高）',
    Math.abs(m.rows[0].cells[0].h - CARD_H_ASSETS) < 1.2,
    { got: m.rows[0].cells[0].h, want: +CARD_H_ASSETS.toFixed(1) })
  r.ok('③甲 C 上半仍是 ③ 那版（浅绿底 + 无边框 + 卡高 ' + CARD_H_TILES.toFixed(1) + 'px）',
    !!m.shellTiles && m.shellTiles.bg === 'rgb(238, 252, 246)' &&
      Math.abs(m.tiles[0].h - CARD_H_TILES) < 1.2,
    { bg: m.shellTiles && m.shellTiles.bg, h: m.tiles[0].h })
  r.ok('③ C 「我的订单」四格也各带图标 + 整块是 `<button>`',
    m.tiles.length === 4 && m.tiles.every((t) => t.ic === true) && m.tilesWholeBlock === true,
    m.tiles.map((t) => [t.k, t.ic, t.tag]))
  r.ok('C 券格可点（真券数）', m.rows[0].cells[1].entry === true, m.rows[0].cells[1])
  r.ok('C 无商家端那枚遮罩（未打开）', m.scrim === false, m.scrim)
  await bb.shot(EV + 'out-b5b-usercenter-C-customer.png')
})

// ══ 四、窄屏（980px < 1120 断点）：网格塌成 2 列，**仍然**是容器级滚动 ══
await withMock(me({ customerid: '' }), apps('pending'), async (bb) => {
  await bb.goto('http://localhost:8080/#/merchant/user/info', 2600)
  await bb.send('Emulation.setDeviceMetricsOverride', { width: 980, height: 900, deviceScaleFactor: 1, mobile: false })
  await sleep(900)
  const m = await bb.evalJS(MEASURE)
  out['M-narrow'] = m
  if (!m || m.err) { r.ok('窄屏页面渲染出来', false, m && m.err); return }
  r.ok('窄屏 980px：网格塌成 2 列（@media 既有规则）', m.rows[0].n === 2, m.rows.map((x) => [x.top, x.n]))
  r.ok('窄屏：仍是容器级滚动（容器高**不随**行数长高）', m.scrollH > m.clientH + 1, { scrollH: m.scrollH, clientH: m.clientH })
  r.ok('窄屏：渐隐仍在', !!m.mask && m.mask.includes(MASK_STOP), m.mask)
  r.ok('窄屏：7 格一个不少、一个图标不少', m.count === 7 && m.assetsIcons === 7, { n: m.count, ic: m.assetsIcons })
  r.ok('③甲 窄屏：容器高**仍是** ' + CONTAINER_H.toFixed(1) + 'px（不随行数长高）',
    Math.abs(m.clientH - CONTAINER_H) < 1.5, { got: m.clientH, want: +CONTAINER_H.toFixed(1) })
  r.ok('③甲 窄屏：7 格仍然是横向版式（图标在名称左侧）',
    m.rows.flatMap((x) => x.cells).every((c) => c.icLeftOfName === true && c.icAboveText === false),
    m.rows.flatMap((x) => x.cells).map((c) => [c.k, c.icLeftOfName]))
  await bb.shot(EV + 'out-b5b-usercenter-M-narrow980.png')
})

r.ok('无 JS 异常', errs.length === 0, errs)
if (errs.length) console.log('\n── JS 异常 ──\n' + errs.join('\n'))
fs.writeFileSync('_work/b5b-usercenter-cards.json', JSON.stringify(out, null, 2))
const bad = r.dump()
console.log('几何原始值 → _work/b5b-usercenter-cards.json；截图 → ' + EV)
process.exit(bad ? 1 : 0)
