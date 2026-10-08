// probe-pp-cart.mjs —— 2026-10-08 用户「补充项」：商品详情页购买弹窗的**下半**改完没有。
// 口径（用户原话）：下半 = `图3.jpg` 式完整购物车列表 —— 表头 商品|单价|数量|小计|操作；行内 缩略图+名称+规格+订单号
//   | 单价 | ± 数量 | 小计 | 删除；右侧 结算栏（商品件数 / 商品总额 / 运费 / 合计 / 按钮）。两个入口只有 CTA 文案不同。
// 做法：mock 4 个型号 ⇒ 打开 `#/product`，量几何（列头与行格是否对齐、结算栏是否真在右侧、有无横向溢出），
//   点行内 +、点删除、两入口各开一次，最后**真点「结算 → 是」**：截下 `order.orderAdd` 的 body，
//   比对里面那枚 `OrderId` 与行内显示的是不是**同一枚**（这是本笔自己定的口径：所见即所报）。
import { open, sleep, report } from './_cdp.mjs'
import fs from 'fs'

fs.mkdirSync('docs/美化/dpp', { recursive: true })

// ⚠️ 缩略图用 **SVG data-URI**（同 probe-ho-hover 的教训：mock 里给 `/img/...` 会被答成 JSON ⇒ 画破图）
const IMG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='64' height='64'%3E%3Crect width='64' height='64' fill='%23c9d6d1'/%3E%3Ccircle cx='32' cy='26' r='9' fill='%23ffffff'/%3E%3C/svg%3E"
// ⚠️ 2026-10-08 六条【一-2】把型号加到 8 个：4 个型号时列表**撑不满** sheet（1440×900 下 4 行全露、
//    根本不触发滚动）⇒ 那条「超出即滚」的断言没有可观察对象。8 个型号 ⇒ 8 行 > 可用高度 ⇒ 真滚。
const CODES = ['P001', 'P002', 'P003', 'P004', 'P005', 'P006', 'P007', 'P008']
const PRICE = { P001: 129, P002: 69, P003: 19.9, P004: 259, P005: 39, P006: 88, P007: 158, P008: 999 }
const HUES = ['c9d6d1', 'd1c9d6', 'd6d1c9', 'c9d1d6', 'd6c9d1', 'd1d6c9', 'b8c9c2', 'e0d6c9']   // ⚠️ 五条【3】：8 个型号各一张图（见 MOCK）
const ORDERS = []
const MOCK = (url, method, postData) => {
  if (/goodsSelectHome/.test(url)) {
    return { status: 200, body: JSON.stringify({ success: true, list: [{ MerchantId: 'M9001', GoodName: '演示商品 · 无线蓝牙耳机 声谷标准版', UserName: '演示商家', GoodImg: IMG, Price: 129, GoodType: '数码' }] }) }
  }
  if (/GoodsProductCodeSelect/.test(url)) return { status: 200, body: JSON.stringify({ success: true, list: CODES }) }
  if (/productCodePrice/.test(url)) {
    const m = /ProductCode=([A-Za-z0-9]+)/.exec(postData || '')
    const code = m ? m[1] : ''
    // ⚠️ 2026-10-08 五条【3】：**每个型号给不同底色的图** —— 「点缩略图 ⇒ 大图跟着换」那条断言才有可观察对象
    //    （原先 8 个型号共用同一张 IMG，src 恒等 ⇒ 断言只能报绿，测不出东西）。
    const img = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='64' height='64'%3E%3Crect width='64' height='64' fill='%23" + HUES[CODES.indexOf(code)] + "'/%3E%3C/svg%3E"
    return { status: 200, body: JSON.stringify({ success: true, price: PRICE[code] || 99, goodImg: img }) }
  }
  if (/orderAdd/.test(url)) { ORDERS.push(postData || ''); return { status: 200, body: JSON.stringify({ success: true }) } }
  return { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }
}

const SNAP = `(() => {
  const q = (s) => document.querySelector(s)
  const bx = (e) => { if (!e) return null; const r = e.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.right), Math.round(r.top), Math.round(r.bottom)] }
  const heads = [...document.querySelectorAll('.p-head > span')].map((e) => ({ t: e.textContent.trim(), x: bx(e) }))
  const cells = [...document.querySelectorAll('.p-row:first-child > *')].map((e) => ({ c: e.className, x: bx(e) }))
  const s = q('.popup-content')
  // ── 2026-10-08 用户「六条改动」补的观测量 ────────────────────────────────
  const orderId = (() => { const e = q('.p-row .sku:last-child'); return e ? e.textContent.trim().replace(/^订单号\s*/, '') : null })()
  const close = q('.p-close')
  const closeHit = (() => { if (!close) return null; const r = close.getBoundingClientRect(); const e = document.elementFromPoint(Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2)); return e ? (e.className || e.tagName) : null })()
  const rowsEl = q('.p-rows')
  return JSON.stringify({
    sheet: bx(s), body: bx(q('.p-body')), rows: bx(q('.p-rows')), sum: bx(q('.p-sum')),
    close: bx(close), closeHit, closeText: close ? close.textContent.trim() : null,
    docW: document.documentElement.clientWidth, vw: innerWidth,
    rowsScroll: rowsEl ? [rowsEl.scrollHeight, rowsEl.clientHeight, rowsEl.offsetWidth - rowsEl.clientWidth] : null,
    sbw: rowsEl ? getComputedStyle(rowsEl).scrollbarWidth : null,
    sbPseudo: rowsEl ? getComputedStyle(rowsEl, '::-webkit-scrollbar').display : null,
    popOpen: !!q('.popup-mask'), scrimTitle: q('.scrim .dlg-h') ? q('.scrim .dlg-h').textContent.trim() : null,
    orderId, count: (() => { const e = q('.p-sum-r .v'); return e ? e.textContent.trim() : null })(),
    heads, cells, headB: heads[0] && heads[0].x[3], rowT: cells[0] && cells[0].x[2],
    sumRows: [...document.querySelectorAll('.p-sum-r, .p-sum-t')].map((e) => e.textContent.trim().replace(/\\s+/g, ' ')),
    cta: (q('.p-confirm') || {}).textContent ? q('.p-confirm').textContent.trim() : null,
    rowCount: document.querySelectorAll('.p-row').length,
    skuLines: [...document.querySelectorAll('.p-row:first-child .sku')].map((e) => e.textContent.trim()),
    nums: [...document.querySelectorAll('.p-row .num .n')].map((e) => e.textContent.trim()),
    subs: [...document.querySelectorAll('.p-row .sub')].map((e) => e.textContent.trim()),
    units: [...document.querySelectorAll('.p-row .unit')].map((e) => e.textContent.trim()),
    dels: [...document.querySelectorAll('.p-row .del')].map((e) => e.textContent.trim()),
    // ── 2026-10-08 用户「五条」补的观测量（【1】body 锁 /【3】图片区）──────────
    bodyOv: [document.body.style.overflow, getComputedStyle(document.body).overflow],
    media: bx(q('.p-media')), thumb: bx(q('.p-media .thumb')), buy: bx(q('.p-buy')),
    coupons: bx(q('.p-coupons')), qty: bx(q('.p-qty')),
    ptN: document.querySelectorAll('.p-thumbs .pt').length,
    ptBoxes: [...document.querySelectorAll('.p-thumbs .pt')].slice(0, 4).map(bx),
    ptOn: (() => { const e = q('.p-thumbs .pt.on'); return e ? [...document.querySelectorAll('.p-thumbs .pt')].indexOf(e) : -1 })(),
    mdOn: (() => { const e = q('.p-opt .md.on'); return e ? e.textContent.trim() : null })(),
    bigSrc: (() => { const e = q('.p-media .thumb img'); return e ? e.getAttribute('src') : null })(),
    ptSrc: (() => { const e = q('.p-thumbs .pt.on img'); return e ? e.getAttribute('src') : null })(),
    overX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    sheetOverX: s ? s.scrollWidth - s.clientWidth : null
  })
})()`

const b = await open({ w: 1440, h: 900, mock: MOCK })
b.ws.on('message', (m) => {
  if (m.method === 'Page.javascriptDialogOpening') {
    console.log('  ⚠️ 页面弹窗:', JSON.stringify(m.params.message))
    b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
})
const R = report('probe-pp-cart')
const goProduct = async () => {
  await b.goto('http://localhost:8080/#/customer', 1200)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeC');sessionStorage.setItem('role','1');sessionStorage.setItem('customerid','C5001');1`)
  await b.goto('http://localhost:8080/#/product?MerchantId=M9001&GoodName=' + encodeURIComponent('演示商品 · 无线蓝牙耳机 声谷标准版') + '&GoodType=' + encodeURIComponent('数码') + '&Id=1', 2200)
}
const openPopup = async (entry) => {
  await b.evalJS(`(() => { const bs = [...document.querySelectorAll('.acts .b')]; const t = bs.find((x) => x.textContent.trim() === ${JSON.stringify(entry)}); if (t) t.click(); return 1 })()`)
  await sleep(900)
}

try {
  await goProduct()
  console.log('页面就绪：', await b.evalJS(`location.hash.slice(0, 40) + ' | 按钮: ' + [...document.querySelectorAll('.acts .b')].map((x) => x.textContent.trim()).join('/')`))

  // ── ① 立即购买：结构与几何 ─────────────────────────────────────────────
  await openPopup('立即购买')
  let s = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【① 立即购买】sheet', s.sheet, ' body', s.body, '\n  列表', s.rows, ' 结算栏', s.sum, ' 横向溢出', s.overX, '/ sheet 内', s.sheetOverX)
  console.log('  表头:', s.heads.map((h) => h.t + h.x[0] + '-' + h.x[1]).join(' | '))
  console.log('  首行:', s.cells.map((c) => c.c.replace(/ .*/, '') + c.x[0] + '-' + c.x[1]).join(' | '))
  console.log('  行内副行:', s.skuLines, ' 数量', s.nums, ' 单价', s.units, ' 小计', s.subs, ' 删除', s.dels)
  console.log('  结算栏四行:', s.sumRows, ' CTA:', s.cta, ' 行数:', s.rowCount)
  R.ok('表头五列 = 商品/单价/数量/小计/操作', JSON.stringify(s.heads.map((h) => h.t)) === JSON.stringify(['商品', '单价', '数量', '小计', '操作']), s.heads.map((h) => h.t))
  R.ok('开窗即有 1 行（不再是空表）', s.rowCount === 1, s.rowCount)
  R.ok('行内副行 = 规格 + 订单号', s.skuLines.length === 2 && /^规格：/.test(s.skuLines[0]) && /^订单号 /.test(s.skuLines[1]), s.skuLines)
  R.ok('结算栏在列表**右侧**', s.sum[0] >= s.rows[1], [s.rows[1], s.sum[0]])
  R.ok('结算栏四行措辞', JSON.stringify(s.sumRows.slice(0, 4)) === JSON.stringify(['商品件数1', '商品总额¥129.00', '运费免运费', '合计¥129.00']), s.sumRows)
  R.ok('无横向溢出（页 / sheet）', s.overX === 0 && s.sheetOverX === 0, [s.overX, s.sheetOverX])
  R.ok('表头与行格右边界对齐（单价/数量/小计/操作）', s.heads.slice(1).every((h, i) => Math.abs(h.x[1] - s.cells[i + 2].x[1]) <= 1), s.heads.slice(1).map((h, i) => h.x[1] - s.cells[i + 2].x[1]))
  R.ok('列头不随行滚动（列头在 .p-rows 之上）', s.headB <= s.rowT, [s.headB, s.rowT])
  await b.shot('docs/美化/dpp/buy-1440.png')

  // ── ② 行内 ± 与删除 ───────────────────────────────────────────────────
  await b.evalJS(`(() => { const n = document.querySelector('.p-row .num'); n.querySelectorAll('.pm')[1].click(); return 1 })()`); await sleep(400)
  let s2 = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【② 行内 +】数量', s2.nums, ' 小计', s2.subs, ' 结算栏', s2.sumRows)
  R.ok('点 + ⇒ 数量 2 / 小计 258.00', s2.nums[0] === '2' && s2.subs[0] === '¥258.00', [s2.nums, s2.subs])
  R.ok('件数与合计跟着变（2 / ¥258.00）', /商品件数2/.test(s2.sumRows[0]) && /合计¥258\.00/.test(s2.sumRows[3]), s2.sumRows)
  await b.evalJS(`(() => { const n = document.querySelector('.p-row .num'); n.querySelectorAll('.pm')[0].click(); n.querySelectorAll('.pm')[0].click(); return 1 })()`); await sleep(400)
  const s3 = JSON.parse(await b.evalJS(SNAP))
  R.ok('连点 − ⇒ 归 0 行消失（min 0）', s3.rowCount === 0 && /商品件数0/.test(s3.sumRows[0]), [s3.rowCount, s3.sumRows[0]])
  await b.shot('docs/美化/dpp/empty-1440.png')
  // 重新加一件再来删
  await b.evalJS(`(() => { const p = document.querySelector('.p-qty .stepper button:last-child'); p.click(); return 1 })()`); await sleep(400)
  const s4 = JSON.parse(await b.evalJS(SNAP))
  R.ok('顶部步进器也能让行回来（两个入口同源）', s4.rowCount === 1, s4.rowCount)
  await b.evalJS(`(() => { document.querySelector('.p-row .del').click(); return 1 })()`); await sleep(400)
  const s5 = JSON.parse(await b.evalJS(SNAP))
  R.ok('点「删除」⇒ 行消失', s5.rowCount === 0, s5.rowCount)

  // ── ③ 加入购物车入口：只有 CTA 文案不同 ────────────────────────────────
  // ⚠️ 2026-10-08 六条【二】之后，**「开窗即有 1 行」只在首次开窗成立** —— 关掉再开是**保留**上一次的
  //   状态（上一段刚把行删光 ⇒ 再开就是 0 行，这正是「关闭 ≠ 清数据」）。所以这里先把那 1 件加回来，
  //   才谈得上「两个入口只有 CTA 文案不同」。
  await b.evalJS(`(() => { document.querySelector('.p-qty .stepper button:last-child').click(); return 1 })()`); await sleep(400)
  await b.evalJS(`(() => { document.querySelector('.popup-mask').click(); return 1 })()`); await sleep(300)
  await openPopup('加入购物车')
  const s6 = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【③ 加入购物车】CTA:', s6.cta, ' 行数', s6.rowCount, ' 结算栏', s6.sumRows[0])
  R.ok('CTA = 加入购物车', s6.cta === '加入购物车', s6.cta)
  R.ok('其余与立即购买一致（1 行 / 件数 1）', s6.rowCount === 1 && /商品件数1/.test(s6.sumRows[0]), [s6.rowCount, s6.sumRows[0]])
  await b.shot('docs/美化/dpp/cart-1440.png')

  // ── ④ 真点「结算 → 是」：上报的 OrderId 是不是行内那一枚 ─────────────────
  await b.evalJS(`(() => { document.querySelector('.popup-mask').click(); return 1 })()`); await sleep(300)
  await openPopup('立即购买')
  const shown = (JSON.parse(await b.evalJS(SNAP)).skuLines[1] || '').replace(/^订单号\s*/, '')
  await b.evalJS(`(() => { document.querySelector('.p-confirm').click(); return 1 })()`); await sleep(700)
  const dlg = await b.evalJS(`(() => { const d = document.querySelector('.scrim .dlg-h'); return d ? d.textContent.trim() : null })()`)
  console.log('\n【④ 结算】行内显示订单号 =', shown, ' 确认框:', dlg)
  R.ok('弹出付款确认框（未被弹窗盖住）', dlg === '是否现在付款？', dlg)
  await b.shot('docs/美化/dpp/payconfirm-1440.png')
  await b.evalJS(`(() => { document.querySelector('.scrim .b.ok').click(); return 1 })()`); await sleep(1500)
  const body = ORDERS[ORDERS.length - 1] || ''
  const sent = (new URLSearchParams(body).get('OrderId') || '')
  console.log('  order.orderAdd 实际收到 OrderId =', sent, '（共', ORDERS.length, '发）')
  R.ok('上报的 OrderId == 行内显示的订单号', !!sent && sent === shown, [shown, sent])
  R.ok('Price 仍是数值（图三式展示不改上报）', /Price=258|Price=129/.test(body), body.slice(0, 120))

  // ── ⑤ 窄屏两档：≤1100 叠成一栏（2026-10-08 六条把档位从 960 抬到 1100）、≤820 六轨再压 ──────
  await goProduct()          // ⚠️ ④ 提交成功后页面已经跳走了（`handlePay` 的 router.push）⇒ 必须重新进详情页
  await openPopup('立即购买')
  for (const W of [960, 900, 820, 768]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: W, height: 900, deviceScaleFactor: 1, mobile: false })
    await sleep(500)
    const n = JSON.parse(await b.evalJS(SNAP))
    const stacked = n.sum[2] >= n.rows[3]      // 结算栏顶 ≥ 列表底 ⇒ 已叠到下面
    console.log(`\n【⑤ ${W} 宽】sheet ${n.sheet[0]}-${n.sheet[1]}  列表 ${n.rows[0]}-${n.rows[1]}  结算栏 ${n.sum[0]}-${n.sum[1]} (y ${n.sum[2]}-${n.sum[3]})  溢出 ${n.overX}/${n.sheetOverX}`)
    console.log('   表头:', n.heads.map((h) => h.t + h.x[0] + '-' + h.x[1]).join(' | '))
    R.ok(`${W} 无横向溢出`, n.overX === 0 && n.sheetOverX === 0, [n.overX, n.sheetOverX])
    R.ok(`${W} ${W <= 960 ? '结算栏已叠到列表下面' : '结算栏在右侧'}`, W <= 960 ? stacked : !stacked, [n.rows[1], n.sum[0], n.sum[2], n.rows[3]])
    R.ok(`${W} 行内六格仍在（±/删除没被挤掉）`, n.cells.length === 6 && n.dels[0] === '删除', [n.cells.length, n.dels])
    await b.shot(`docs/美化/dpp/narrow-${W}.png`)
  }
  // ══ 2026-10-08 用户「六条改动」（结构二次调整，不受 D 笔边界约束） ═══════════════════════════
  // 【一-1 满占】sheet 左沿贴 0、右沿贴**页面可视宽**（`documentElement.clientWidth` —— 页面带竖滚动条时
  //   「可视宽」= 视口 − 滚动条；差那 15px 不算「没满占」，`fixed; inset:0` 本来就到不了滚动条下面）。
  // 【一-2 滚动条隐藏】三条同真才算过：computed `scrollbar-width:none`、`::-webkit-scrollbar` 的
  //   `display:none`、且 `.p-rows` 的 `offsetWidth − clientWidth === 0`（真占了槽的话这里不是 0）。
  // 【一-3 X】在 sheet 右上角（右/上各 16）且**命中它自己**（不是被别的层盖住）。
  await goProduct()
  // ⚠️ ⑤ 把视口压到 768 了 —— 本段要在**宽屏**量满占/X，必须先把 override 调回 1440（`b.goto` 不会重置它）。
  await b.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false }); await sleep(400)
  await openPopup('立即购买')
  await b.evalJS(`(() => { const mds = [...document.querySelectorAll('.p-opt .md')]; for (const m of mds) { m.click(); document.querySelector('.p-qty .stepper button:last-child').click() } return 1 })()`); await sleep(800)
  const g = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【⑥ 满占 / 隐藏滚动条 / X】sheet', g.sheet, ' 页面可视宽', g.docW, '（视口', g.vw, '）  X', g.close, g.closeHit)
  console.log('   列表 scrollH/clientH/滚动条占宽', g.rowsScroll, ' scrollbar-width', g.sbw, ' ::-webkit-scrollbar.display', g.sbPseudo, ' 行数', g.rowCount)
  R.ok('sheet 左右满占（左沿 0 / 右沿 = 页面可视宽）', g.sheet[0] === 0 && Math.abs(g.sheet[1] - g.docW) <= 1, [g.sheet, g.docW])
  R.ok('列表滚动条已隐藏（三条同真）', g.sbw === 'none' && g.sbPseudo === 'none' && g.rowsScroll[2] === 0, [g.sbw, g.sbPseudo, g.rowsScroll])
  R.ok('铺满 8 行 ⇒ 列表真的在滚（scrollH > clientH）', g.rowsScroll[0] > g.rowsScroll[1], g.rowsScroll)
  R.ok('X 在 sheet 右上角、命中它自己', g.closeHit === 'p-close' && Math.abs(g.close[1] - (g.sheet[1] - 16)) <= 1 && Math.abs(g.close[2] - (g.sheet[2] + 16)) <= 1, [g.close, g.sheet, g.closeHit])
  R.ok('X 文案 =「×」且不是 0 尺寸', g.closeText === '×' && g.close[1] - g.close[0] >= 24, [g.closeText, g.close])
  await b.shot('docs/美化/dpp/six-full-1440.png')

  // 【二 关闭 ≠ 清数据】两条关闭路（点旁边空白 / 点 X）各走一遍：再打开后订单号与件数**一个字都不许变**。
  const keep = { id: g.orderId, n: g.count }
  await b.evalJS(`(() => { document.querySelector('.popup-mask').click(); return 1 })()`); await sleep(400)
  const closedByMask = await b.evalJS(`document.querySelector('.popup-mask') ? 'still-open' : 'closed'`)
  await openPopup('立即购买')
  const r1 = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【⑦-1 点空白关 → 再开】订单号', keep.id, '→', r1.orderId, ' 件数', keep.n, '→', r1.count)
  R.ok('点旁边空白 ⇒ 弹窗关闭', closedByMask === 'closed', closedByMask)
  R.ok('点空白关、再开：订单号未变（数据保留）', r1.orderId === keep.id && !!r1.orderId, [keep.id, r1.orderId])
  R.ok('点空白关、再开：件数未变（数据保留）', r1.count === keep.n, [keep.n, r1.count])
  await b.evalJS(`(() => { document.querySelector('.p-close').click(); return 1 })()`); await sleep(400)
  const closedByX = await b.evalJS(`document.querySelector('.popup-mask') ? 'still-open' : 'closed'`)
  await openPopup('立即购买')
  const r2 = JSON.parse(await b.evalJS(SNAP))
  console.log('【⑦-2 点 X 关 → 再开】订单号', r2.orderId, ' 件数', r2.count)
  R.ok('点 X ⇒ 弹窗关闭', closedByX === 'closed', closedByX)
  R.ok('点 X 关、再开：订单号 / 件数都保留', r2.orderId === keep.id && r2.count === keep.n, [keep.id, r2.orderId, keep.n, r2.count])

  // 【二 清数据只有两条】① 商品 code 变 —— 用**同文档内换 query** 模拟：组件被复用、refs 还在，
  //   这一档只有 `popupKey` 能救（刷新那条见 ②，组件重建本来就归零）。
  await b.evalJS(`(() => { document.querySelector('.popup-mask').click(); return 1 })()`); await sleep(300)
  await b.evalJS(`(() => { location.hash = '#/product?MerchantId=M9001&GoodName=' + encodeURIComponent('换一件商品') + '&GoodType=' + encodeURIComponent('数码') + '&Id=2'; return 1 })()`)
  await sleep(1200)
  await openPopup('立即购买')
  const r3 = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【⑧-1 换商品（同文档换 query）→ 再开】订单号', r3.orderId, ' 件数', r3.count, ' 路由', await b.evalJS(`decodeURIComponent(location.hash)`))
  R.ok('换商品（code 变）⇒ 数据清空（新订单号 + 回到 1 件）', r3.orderId !== keep.id && r3.count === '1', [keep.id, r3.orderId, keep.n, r3.count])
  await b.evalJS(`(() => { document.querySelector('.popup-mask').click(); return 1 })()`); await sleep(300)
  await b.send('Page.reload', { ignoreCache: true }); await sleep(2600)
  await openPopup('立即购买')
  const r4 = JSON.parse(await b.evalJS(SNAP))
  console.log('【⑧-2 刷新页面 → 再开】订单号', r4.orderId, ' 件数', r4.count)
  R.ok('刷新页面 ⇒ 数据清空（新订单号 + 回到 1 件）', r4.orderId !== r3.orderId && r4.count === '1', [r3.orderId, r4.orderId, r4.count])

  // 【三 确认框分流】核心：「否」**一个请求都不发** —— 旧实现两支都掉 `order.orderAdd`（用户点名的 bug）。
  await goProduct()
  await openPopup('加入购物车')
  const n0 = ORDERS.length
  await b.evalJS(`(() => { document.querySelector('.p-confirm').click(); return 1 })()`); await sleep(500)
  const cDlg = JSON.parse(await b.evalJS(SNAP)).scrimTitle
  await b.shot('docs/美化/dpp/six-confirm-cart.png')
  await b.evalJS(`(() => { document.querySelector('.scrim .b.no').click(); return 1 })()`); await sleep(600)
  const cNo = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【⑨ 加入购物车】确认框「' + cDlg + '」 点否后：orderAdd 累计', ORDERS.length, '（此前', n0, '） 弹窗', cNo.popOpen, ' 件数', cNo.count)
  R.ok('加入购物车入口：确认框文案 =「是否加入购物车？」', cDlg === '是否加入购物车？', cDlg)
  R.ok('加入购物车「否」⇒ 不发请求 + 弹窗与数据保留', ORDERS.length === n0 && cNo.popOpen && cNo.count === '1', [n0, ORDERS.length, cNo.popOpen, cNo.count])
  await b.evalJS(`(() => { document.querySelector('.p-confirm').click(); return 1 })()`); await sleep(400)
  await b.evalJS(`(() => { document.querySelector('.scrim .b.ok').click(); return 1 })()`); await sleep(1600)
  const cartBody = ORDERS[ORDERS.length - 1] || ''
  console.log('   入口「是」⇒ orderAdd', ORDERS.length, ' 体:', cartBody.slice(0, 140))
  R.ok('加入购物车「是」⇒ 发 1 次 orderAdd、PayIf=0（加入购物车 ≠ 付款）', ORDERS.length === n0 + 1 && /PayIf=0/.test(cartBody), [n0, ORDERS.length, cartBody.slice(0, 160)])

  await goProduct()
  await openPopup('立即购买')
  const m0 = ORDERS.length
  await b.evalJS(`(() => { document.querySelector('.p-confirm').click(); return 1 })()`); await sleep(500)
  const bDlg = JSON.parse(await b.evalJS(SNAP)).scrimTitle
  await b.evalJS(`(() => { document.querySelector('.scrim .b.no').click(); return 1 })()`); await sleep(600)
  const bNo = JSON.parse(await b.evalJS(SNAP))
  console.log('【⑨ 结算】确认框「' + bDlg + '」 点否后：orderAdd 累计', ORDERS.length, '（此前', m0, '） 弹窗', bNo.popOpen, ' 件数', bNo.count)
  R.ok('结算入口：确认框文案 =「是否现在付款？」', bDlg === '是否现在付款？', bDlg)
  R.ok('结算「否」⇒ **不发请求**（旧 bug 的判据）+ 弹窗与数据保留', ORDERS.length === m0 && bNo.popOpen && bNo.count === '1', [m0, ORDERS.length, bNo.popOpen, bNo.count])
  await b.evalJS(`(() => { document.querySelector('.p-confirm').click(); return 1 })()`); await sleep(400)
  await b.evalJS(`(() => { document.querySelector('.scrim .b.ok').click(); return 1 })()`); await sleep(1600)
  const buyBody = ORDERS[ORDERS.length - 1] || ''
  R.ok('结算「是」⇒ 发 1 次 orderAdd、PayIf=1（付款）', ORDERS.length === m0 + 1 && /PayIf=1/.test(buyBody), [m0, ORDERS.length, buyBody.slice(0, 160)])

  // 【一-1 的档位重算】1101 两栏 / 1100 叠一栏。
  await goProduct()
  await openPopup('立即购买')
  for (const W of [1101, 1100]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: W, height: 900, deviceScaleFactor: 1, mobile: false })
    await sleep(500)
    const n = JSON.parse(await b.evalJS(SNAP))
    const stacked = n.sum[2] >= n.rows[3]
    console.log(`\n【⑩ ${W} 宽】sheet ${n.sheet[0]}-${n.sheet[1]}  列表 ${n.rows[0]}-${n.rows[1]}  结算栏 ${n.sum[0]}-${n.sum[1]} (y ${n.sum[2]}-${n.sum[3]})`)
    R.ok(`${W} ${W >= 1101 ? '结算栏在右侧' : '结算栏叠到列表下面'}`, W >= 1101 ? !stacked : stacked, [n.rows[1], n.sum[0], n.rows[3], n.sum[2]])
    R.ok(`${W} 满占且无横向溢出`, n.sheet[0] === 0 && Math.abs(n.sheet[1] - n.docW) <= 1 && n.overX === 0, [n.sheet, n.docW, n.overX])
    await b.shot(`docs/美化/dpp/six-w${W}.png`)
  }
  // ── ⑪ 五条【1】：body 锁（开 = 锁 / 关 = 解锁，且**真滚不动**）──────────────
  await b.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await goProduct()
  // ⚠️ **判据必须是「真滚轮」不是 `window.scrollTo`** —— `overflow:hidden` 只挡**用户输入**，程序化
  //    `scrollTo` 照样生效（探针第一版就栽在这：body/html 都已 `hidden`，`scrollTo(0,400)` 仍读到 400，
  //    看着像「没锁住」，其实是**判据测错了东西**）。改用 CDP `Input.dispatchMouseEvent` 发真滚轮。
  const scrollY = 'Math.round(window.scrollY || document.scrollingElement.scrollTop)'
  const wheel = async (x, y, dy) => { await b.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x, y, deltaX: 0, deltaY: dy, button: 'none', pointerType: 'mouse' }); await sleep(350); return await b.evalJS(scrollY) }
  await b.evalJS('window.scrollTo(0, 0); 1')
  const pre = JSON.parse(await b.evalJS(SNAP))
  const preY = await wheel(1400, 120, 400)
  await b.evalJS('window.scrollTo(0, 0); 1')
  console.log('\n【⑪ 滚动锁】开窗前 body overflow', pre.bodyOv, ' 真滚轮 400 ⇒', preY)
  R.ok('弹窗未开：overflow 非 hidden', pre.bodyOv[1] !== 'hidden', pre.bodyOv)
  R.ok('弹窗未开：真滚轮真能滚', preY > 0, preY)
  await openPopup('立即购买')
  const on = JSON.parse(await b.evalJS(SNAP))
  const onY = await wheel(1400, 120, 400)
  console.log('  开窗后 body overflow', on.bodyOv, ' 真滚轮 400 ⇒', onY, ' 无滚动条（innerW−clientW）', await b.evalJS('innerWidth - document.documentElement.clientWidth'))
  R.ok('弹窗开：body overflow = hidden（用户【1】「满占 = 弹窗 + 背后页面一起锁」）', on.bodyOv[0] === 'hidden' && on.bodyOv[1] === 'hidden', on.bodyOv)
  R.ok('弹窗开：**真滚轮**滚不动（scrollY 仍 0）', onY === 0, onY)
  R.ok('弹窗开：页面滚动条消失（锁生效的可见后果）', await b.evalJS('innerWidth - document.documentElement.clientWidth') === 0, await b.evalJS('innerWidth - document.documentElement.clientWidth'))
  // 对照：把那一行锁**当场撤掉**、同一发真滚轮 ⇒ 必须立刻能滚。否则上面那条绿只是「测具本身失灵」。
  await b.evalJS(`(() => { document.body.style.overflow = ''; return 1 })()`); await sleep(200)
  const ctrlY = await wheel(1400, 120, 400)
  await b.evalJS(`(() => { document.body.style.overflow = 'hidden'; window.scrollTo(0, 0); return 1 })()`); await sleep(200)
  console.log('  对照（撤掉 body 锁）：真滚轮 400 ⇒', ctrlY)
  R.ok('对照：撤掉锁 ⇒ 同一发真滚轮立刻能滚（证明上面那条不是测具失灵）', ctrlY > 0, ctrlY)
  R.ok('对照后重新锁上 ⇒ 真滚轮又归 0', await wheel(1400, 120, 400) === 0, await b.evalJS(scrollY))
  await b.evalJS(`(() => { document.querySelector('.popup-mask').click(); return 1 })()`); await sleep(500)
  const off = JSON.parse(await b.evalJS(SNAP))
  const offY = await wheel(1400, 120, 400)
  console.log('  关窗后 body overflow', off.bodyOv, ' 真滚轮 400 ⇒', offY, ' 弹窗', off.popOpen)
  R.ok('关窗（点旁边空白）⇒ 解锁', off.bodyOv[0] === '' && off.popOpen === false, [off.bodyOv, off.popOpen])
  R.ok('关窗后：真滚轮恢复能滚', offY > 0, offY)

  // ── ⑫ 五条【3】：大图 + 一排型号缩略图（含「点缩略图切型号」）────────────────
  await goProduct()
  await openPopup('立即购买')
  await b.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await sleep(700)
  const m = JSON.parse(await b.evalJS(SNAP))
  console.log('\n【⑫ 图片区】图片列', m.media, ' 大图', m.thumb, ' 缩略图', m.ptN, '枚 首枚', m.ptBoxes[0], ' 选中 idx', m.ptOn)
  console.log('  信息列', m.buy, ' 券行', m.coupons, ' 数量行', m.qty)
  R.ok('大图宽 = 232（= 轨道宽）', Math.round(m.thumb[1] - m.thumb[0]) === 232, m.thumb)
  R.ok('缩略图枚数 = 型号数（8）', m.ptN === 8, m.ptN)
  R.ok('缩略图在大图**正下方**且同左沿', m.ptBoxes[0][0] === m.thumb[0] && m.ptBoxes[0][2] >= m.thumb[3], [m.ptBoxes[0], m.thumb])
  R.ok('开窗默认选中第 1 枚（与型号首个同）', m.ptOn === 0 && m.mdOn === 'P001', [m.ptOn, m.mdOn])
  const beforeSrc = m.bigSrc
  await b.evalJS(`(() => { document.querySelectorAll('.p-thumbs .pt')[2].click(); return 1 })()`); await sleep(500)
  const m2 = JSON.parse(await b.evalJS(SNAP))
  console.log('  点第 3 枚后：选中 idx', m2.ptOn, ' 型号', m2.mdOn, ' 大图换没换', m2.bigSrc !== beforeSrc)
  R.ok('点第 3 枚缩略图 ⇒ 选中态跟到第 3（型号 = P003）', m2.ptOn === 2 && m2.mdOn === 'P003', [m2.ptOn, m2.mdOn])
  R.ok('**大图真的换了图**（每个型号一张，src 变）', m2.bigSrc !== beforeSrc, [beforeSrc && beforeSrc.slice(-30), m2.bigSrc && m2.bigSrc.slice(-30)])
  R.ok('大图与选中的缩略图同源（同一张）', m2.bigSrc === m2.ptSrc, [m2.bigSrc && m2.bigSrc.slice(-30), m2.ptSrc && m2.ptSrc.slice(-30)])
  R.ok('顶部信息列不再比图片列矮一截（【3】「补掉空白」的判据）', Math.abs(m2.buy[3] - m2.media[3]) <= 2, [m2.buy, m2.media])
  await b.shot('docs/美化/dpp/five-media-1440.png')
  // ≤820 档的回退（本笔在那档把 `.pt` 压到 32px）—— 别让新加的一排把窄屏挤爆。
  await b.send('Emulation.setDeviceMetricsOverride', { width: 820, height: 900, deviceScaleFactor: 1, mobile: false })
  await sleep(500)
  const n8 = JSON.parse(await b.evalJS(SNAP))
  const ptW = n8.ptBoxes[0] ? Math.round(n8.ptBoxes[0][1] - n8.ptBoxes[0][0]) : null
  console.log('\n【⑫-b 820】大图', n8.thumb, ' 缩略图首枚宽', ptW, ' 横向溢出', n8.overX, '/ sheet 内', n8.sheetOverX)
  R.ok('820：大图回退到 88 宽', Math.round(n8.thumb[1] - n8.thumb[0]) === 88, n8.thumb)
  R.ok('820：缩略图回退到 32 宽且不与大图错位', ptW === 32 && n8.ptBoxes[0][0] === n8.thumb[0], [ptW, n8.ptBoxes[0], n8.thumb])
  R.ok('820：无横向溢出（新加的一排没挤爆）', n8.overX === 0 && n8.sheetOverX === 0, [n8.overX, n8.sheetOverX])
  await b.shot('docs/美化/dpp/five-media-820.png')

  R.dump()
} finally { await b.close() }
