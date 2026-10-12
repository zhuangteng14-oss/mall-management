// probe-b5-a-coupon-row.mjs —— C3 块 5 · A4 领券行的**几何实测**（2026-10-12，用户问题 1）。
//
// 用户原话三问：「券卡宽度规则（应 ~230px，一行 2.5 张）· ≥3 张券时渐隐 + 》是否出现 ·
// 是不是『券太少』所以没触发」。
//
// ⚠️ 与「_cdp.mjs 页面探针自带后端 mock」那条约定的关系：本份**不 mock**。
//   它要验的正是「真库里有 3 张券时页面长什么样」⇒ 全部请求打**真后端 8088（a3 profile → VM 库）**，
//   只以 `sessionStorage` 贴一个**真客户号**（`uDopHnCeLS` = `user.username='1111'`），
//   走的是 `getCustomerId()` 那一个读口 —— 与真登录后页面读到的**同一个值**。
//
// 对照组（缺了它就分不清「阈值没到」和「CSS 坏了」）：
//   A 组 · oppo12（商家 5N1k5K2guh）—— 库里 **3** 张商家券（1 张 #58 + 探针补的 2 张）
//   B 组 · 声谷标准版（商家 dRD3gqtbFr）—— 库里 **1** 张商家券
// 判据：宽度规则在两组**都**成立；渐隐 / `》` 只在 A 组出现。
//
// ⚠️ **2026-10-12 手验后口径改为「一张半」**（原「两张半」）：实测券区只有 315.9px，
//   两张半 ⇒ 每张 132.1px，而卡内内容最小 197px（溢出 65px）⇒ 与「可读卡」互斥。
//   用户裁定取可读 ⇒ `CP_VISIBLE` 2→1、基宽分母 `2.3`→`1.3`、渐隐带 `87%`→`78%`。
//   本探针的 `DIVISOR` / 露头期望值随之改，别拿旧值当基准。
//
// 用法（前端 8080 + 后端 8088 都要活着）：
//     node docs/美化/probe-b5-a-coupon-row.mjs
import fs from 'node:fs'
import { open, sleep, report } from './_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const CUSTOMER_ID = 'uDopHnCeLS'
// `CP_VISIBLE + 0.3`（= 1 + 0.3）。改 `ProductPage.vue` 那个常量就得改这里，否则断言必然假红。
const DIVISOR = 1.3
const MASK_STOP = '78%'

// 两个对照商品（参数口径 = `HomePage.vue:403` 推的那 7 个 query 键，键名**首字母大写**）
const CASES = [
  {
    tag: 'A', label: 'oppo12 · 商家 5N1k5K2guh（库里 3 张商家券）',
    query: { Id: 121, MerchantId: '5N1k5K2guh', GoodName: 'oppo12', Price: 1886, GoodImg: '', GoodType: '电子用品', ProductCode: 'OP2' },
    expect: 3,
  },
  {
    tag: 'B', label: '声谷标准版 · 商家 dRD3gqtbFr（库里 1 张商家券）',
    query: { Id: 120, MerchantId: 'dRD3gqtbFr', GoodName: '无线蓝牙耳机 声谷标准版', Price: 299, GoodImg: '', GoodType: '电子用品', ProductCode: 'GD-101-9552' },
    expect: 1,
  },
]

const qs = (o) => Object.entries(o).map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&')
// ⚠️ 加一个 `?p=` 破 hash-only 导航：只改 `#` 时 CDP 的 Page.navigate 是**同文档**导航，
//    文档不重载 ⇒ `sessionStorage` 虽然已写上，但 `onMounted` 早跑完了、券不会重取。
const url = (o) => `http://localhost:8080/?p=${Date.now().toString(36)}#/product?${qs(o)}`

// 一次量到底：宽度规则 / 渐隐 / `》` / 第一张被裁的卡露多少 / 卡内有没有撑破
const MEASURE = `(() => {
  const row = document.querySelector('.r-coupons')
  const wrap = document.querySelector('.r-coupons .coupons')
  if (!row || !wrap) return { err: 'no .r-coupons / .coupons', text: (document.body.innerText||'').slice(0,300) }
  const cards = [...wrap.querySelectorAll(':scope > .coupon')]
  const wr = wrap.getBoundingClientRect()
  const cs = getComputedStyle(wrap)
  const more = document.querySelector('.r-coupons .cp-more')
  const rd = (e) => { const r = e.getBoundingClientRect(); return { w: +r.width.toFixed(1), left: +(r.left - wr.left).toFixed(1), top: +(r.top - wr.top).toFixed(1) } }
  const rects = cards.map(rd)
  const t = (e, s) => (e.querySelector(s) || {}).textContent
  // 第一张**没被裁**的卡：从右缘往回看，最后一个 right <= 容器宽的（-1 = 一张都没裁）
  let cut = -1
  for (let i = 0; i < rects.length; i++) { if (rects[i].left + rects[i].w > wr.width + 0.5) { cut = i; break } }
  const c0 = cards[0] ? getComputedStyle(cards[0]) : null
  const promo = document.querySelector('.promo')
  return {
    env: {
      innerW: window.innerWidth, docW: document.documentElement.clientWidth,
      promoW: promo ? +promo.getBoundingClientRect().width.toFixed(1) : null,
      rowW: +row.getBoundingClientRect().width.toFixed(1),
      popupInDom: !!document.querySelector('.p-coupons'),
    },
    flex0: c0 && {
      shorthand: c0.flex, basis: c0.flexBasis, grow: c0.flexGrow, shrink: c0.flexShrink,
      minW: c0.minWidth, width: c0.width, s3: c0.getPropertyValue('--s3').trim(),
    },
    cls: wrap.className,
    count: cards.length,
    wrapW: +wr.width.toFixed(1),
    gridCols: getComputedStyle(row).gridTemplateColumns,
    flexWrap: cs.flexWrap,
    scrollW: wrap.scrollWidth,
    maskImage: cs.maskImage === 'none' ? null : cs.maskImage,
    rects,
    cutIndex: cut,
    cutVisiblePx: cut < 0 ? null : +(wr.width - rects[cut].left).toFixed(1),
    cutVisibleRatio: cut < 0 ? null : +((wr.width - rects[cut].left) / rects[cut < 0 ? 0 : 0].w).toFixed(3),
    moreText: more ? more.textContent.trim() : null,
    moreTitle: more ? more.getAttribute('title') : null,
    moreRect: more ? rd(more) : null,
    amts: cards.map((c) => t(c, '.amt')),
    names: cards.map((c) => t(c, '.nm')),
    conds: cards.map((c) => t(c, '.cond')),
    gets: cards.map((c) => t(c, '.get')),
    // 卡内内容有没有溢出卡框（scrollWidth > clientWidth）—— 132px 那版就是拿它抓到的
    cardOverflow: cards.map((c) => c.scrollWidth - c.clientWidth),
    emptyHint: (wrap.querySelector('.cond.cut-off') || {}).textContent || null,
  }
})()`

const b = await open({ w: 1440, h: 900, headed: false })
const errs = []
b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method === 'Runtime.exceptionThrown') {
    const d = m.params.exceptionDetails
    errs.push(String((d.exception && d.exception.description) || d.text).slice(0, 200))
  }
})

// 贴身份：直接写 `sessionStorage` 那三个键（`auth.js` 的 `getCustomerId()` 读的就是 `customerid`）。
await b.goto('http://localhost:8080/', 2500)
await b.evalJS(`(() => { sessionStorage.setItem('username','1111');
  sessionStorage.setItem('customerid','${CUSTOMER_ID}'); sessionStorage.setItem('role','1');
  return sessionStorage.getItem('customerid') })()`)

const r = report('A4 领券行几何')
const out = {}

for (const c of CASES) {
  await b.goto(url(c.query), 2000)
  let m = null
  for (let i = 0; i < 30; i++) {           // 轮询到券卡出现（或出空态）为止
    m = await b.evalJS(MEASURE)
    if (m && (m.count > 0 || m.emptyHint)) break
    await sleep(500)
  }
  out[c.tag] = m
  console.log(`\n── ${c.tag} · ${c.label} ──\n` + JSON.stringify(m, null, 1))
  if (!m || m.err) { r.ok(`${c.tag} 领券行渲染出来了`, false, m && m.err); continue }

  const cardW = m.rects[0] && m.rects[0].w
  const want = (m.wrapW - 12) / DIVISOR
  r.ok(`${c.tag} 券卡宽度 == (容器−12)/${DIVISOR}`, Math.abs(cardW - want) < 1.5, { got: cardW, want: +want.toFixed(1), wrapW: m.wrapW })
  r.ok(`${c.tag} 基宽真的生效（不是被 min-content 顶托）`, parseFloat(m.flex0.minW) === 0 && Math.abs(parseFloat(m.flex0.width) - want) < 1.5, { minW: m.flex0.minW, width: m.flex0.width })
  r.ok(`${c.tag} 一行铺开（nowrap，全在同一 top）`, m.flexWrap === 'nowrap' && new Set(m.rects.map((x) => x.top)).size === 1, { flexWrap: m.flexWrap, tops: m.rects.map((x) => x.top) })
  r.ok(`${c.tag} 卡内内容**不**溢出卡框`, m.cardOverflow.every((x) => x <= 0), m.cardOverflow)
  r.ok(`${c.tag} 领券行里认出了客户身份（不是「登录后可领券」）`, !m.emptyHint, m.emptyHint)

  if (c.expect > 1) {
    r.ok(`${c.tag} ${m.count} 张 ⇒ 加渐隐类 coupons-cut`, /coupons-cut/.test(m.cls), m.cls)
    r.ok(`${c.tag} ${m.count} 张 ⇒ mask-image 在（${MASK_STOP} 起淡）`, !!m.maskImage && m.maskImage.includes(MASK_STOP), m.maskImage)
    r.ok(`${c.tag} ${m.count} 张 ⇒ 「》」在`, m.moreText === '》', { moreText: m.moreText, moreTitle: m.moreTitle })
    r.ok(`${c.tag} 第 ${m.cutIndex + 1} 张**露一角**（0 < 可见 < 整张）`, m.cutVisiblePx > 0 && m.cutVisiblePx < cardW, { px: m.cutVisiblePx, cardW, ratio: m.cutVisibleRatio })
    r.ok(`${c.tag} 那一角 ≈ 三成张（阈值 0.15–0.45）`, m.cutVisibleRatio > 0.15 && m.cutVisibleRatio < 0.45, m.cutVisibleRatio)
    r.ok(`${c.tag} 「》」在容器右侧、不与券权重叠`, m.moreRect && m.moreRect.left >= m.rects[m.cutIndex - 1 < 0 ? 0 : m.cutIndex - 1].left + m.rects[0].w - 1, m.moreRect)
    r.ok(`${c.tag} 「》」的展开文案带真实张数`, m.moreTitle === `展开看全部 ${m.count} 张`, m.moreTitle)
    await b.shot(EV + `out-b5-a4-coupon-row-${c.tag}-${m.count}cut.png`)
    // 点「》」展开
    await b.evalJS(`document.querySelector('.r-coupons .cp-more').click()`)
    await sleep(600)
    const o = await b.evalJS(MEASURE)
    out[c.tag + '-open'] = o
    r.ok(`${c.tag} 展开后换行铺开（wrap）`, o.flexWrap === 'wrap', o.flexWrap)
    r.ok(`${c.tag} 展开后渐隐撤掉`, !o.maskImage && !/coupons-cut/.test(o.cls), { mask: o.maskImage, cls: o.cls })
    r.ok(`${c.tag} 展开后按钮翻成「《」`, o.moreText === '《', o.moreText)
    r.ok(`${c.tag} 展开后 ${o.count} 张全都在（不丢券）`, o.count === m.count, o.count)
    await b.shot(EV + `out-b5-a4-coupon-row-${c.tag}-open.png`)
  } else {
    r.ok(`${c.tag} ${m.count} 张（≤1）⇒ 不加渐隐类（判据是 >）`, !/coupons-cut/.test(m.cls), m.cls)
    r.ok(`${c.tag} ${m.count} 张 ⇒ 不出「》」`, m.moreText === null, m.moreText)
    r.ok(`${c.tag} ${m.count} 张 ⇒ 一张都没被裁到界外`, m.scrollW <= m.wrapW + 1, { scrollW: m.scrollW, wrapW: m.wrapW })
    await b.shot(EV + `out-b5-a4-coupon-row-${c.tag}-${m.count}.png`)
  }
}

r.ok('无 JS 异常', errs.length === 0, errs)
if (errs.length) console.log('\n── JS 异常 ──\n' + errs.join('\n'))
fs.writeFileSync('_work/b5-a4-coupon-row.json', JSON.stringify(out, null, 2))
const bad = r.dump()
console.log('几何原始值 → _work/b5-a4-coupon-row.json；截图 → ' + EV)
await b.close()
process.exit(bad ? 1 : 0)
