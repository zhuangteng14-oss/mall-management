// probe-ho-hover.mjs —— 2026-10-08 用户 ② ：历史订单行的 **hover 态**到底长什么样（图6）。
// 做法：mock 3 行（含一条超长商品名，压 §9-4 的 overflow 约束），鼠标真停在首行上（`Input.dispatchMouseEvent`），
//   截图 + 量几何：行 / 各 td / 缩略图 / 按钮 的盒子，看 hover 底色有没有被图或按钮「越出格」。
import { open, sleep, report } from './_cdp.mjs'

// ⚠️ 缩略图用 **SVG data-URI**（内联一张 48×48 灰底白圆）：mock 里不能指望 8088 真出图
//    —— 上一版写 `/img/Mall-Section-2.jpg`，被 mock 答成 JSON ⇒ 浏览器画的是**破图图标 + alt 文字溢出**，
//    那是**探针的假象**、不是页面的样子（2026-10-08 踩过）。
const IMG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='48' height='48'%3E%3Crect width='48' height='48' fill='%23c9d6d1'/%3E%3Ccircle cx='24' cy='19' r='7' fill='%23ffffff'/%3E%3C/svg%3E"
const ROWS = [
  { OrderId: 'ORD-20261006-0001', ProductInformation: '机械键盘 · 87 键 · 茶轴', Images: IMG, MerchantId: 'M9001', SettlementDate: '2026-10-01 10:00:00' },
  { OrderId: 'ORD-20261006-0002', ProductInformation: '叠法双方:dffffffffffffffffffffffffffffffffffffffffffff', Images: '', MerchantId: 'M9001', SettlementDate: '2026-10-02 11:30:00' },
  { OrderId: 'ORD-20261006-0003', ProductInformation: '显示器支架', Images: '', MerchantId: 'M9001', SettlementDate: '2026-10-03 09:15:00' }
]
const MOCK = (url) => /historicalOrdersSelect/.test(url)
  ? { status: 200, body: JSON.stringify({ success: true, list: ROWS }) }
  : { status: 200, body: JSON.stringify({ success: true, list: [], msg: '' }) }

const M = (sel, host) => `(() => {
  const e = document.querySelector(${JSON.stringify(sel)}); if (!e) return null
  const r = e.getBoundingClientRect(); const cs = getComputedStyle(e)
  return JSON.stringify({ box: [Math.round(r.top), Math.round(r.bottom), Math.round(r.left), Math.round(r.right)],
    h: Math.round(r.height), w: Math.round(r.width), bg: cs.backgroundColor, pad: cs.paddingTop + '/' + cs.paddingBottom })
})()`

const b = await open({ w: 1440, h: 900, mock: MOCK })
const R = report('probe-ho-hover')
try {
  await b.goto('http://localhost:8080/#/customer', 900)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeM');sessionStorage.setItem('role','2');sessionStorage.setItem('merchantid','M9001');1`)
  await b.goto('http://localhost:8080/#/merchant/user/history', 700)
  await b.send('Page.reload', { ignoreCache: true }); await sleep(2600)
  for (const W of [1440, 1280, 1120]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: W, height: 900, deviceScaleFactor: 1, mobile: false })
    await sleep(400)
    const info = JSON.parse(await b.evalJS(`(() => {
      const tr = document.querySelectorAll('.ords tbody tr')[0]
      const r = tr.getBoundingClientRect()
      const tds = [...tr.children].map((td) => { const q = td.getBoundingClientRect(); return { c: td.className, l: Math.round(q.left), r: Math.round(q.right), t: Math.round(q.top), b: Math.round(q.bottom) } })
      const th = document.querySelector('.ords thead tr').getBoundingClientRect()
      const im = document.querySelector('.ords .thumb img')
      const imr = im ? im.getBoundingClientRect() : null
      const btn = document.querySelector('.ords .btn-text').getBoundingClientRect()
      const tbl = document.querySelector('.ords').getBoundingClientRect()
      return JSON.stringify({ row: [Math.round(r.top), Math.round(r.bottom)], rowH: Math.round(r.height), tds,
        table: [Math.round(tbl.left), Math.round(tbl.right)], headBottom: Math.round(th.bottom),
        img: imr ? { t: Math.round(imr.top), b: Math.round(imr.bottom), l: Math.round(imr.left), r: Math.round(imr.right), w: Math.round(imr.width), h: Math.round(imr.height) } : null,
        btn: { t: Math.round(btn.top), b: Math.round(btn.bottom), l: Math.round(btn.left), r: Math.round(btn.right) },
        overX: document.documentElement.scrollWidth - document.documentElement.clientWidth })
    })()`))
    // 鼠标真停在首行中线
    const cy = (info.row[0] + info.row[1]) / 2
    await b.moveTo(600, cy)
    await sleep(400)
    const hov = JSON.parse(await b.evalJS(M('.ords tbody tr:first-child .c-prod', null)))
    const img = JSON.parse(await b.evalJS(M('.ords tbody tr:first-child .thumb img', null)))
    const btn = JSON.parse(await b.evalJS(M('.ords tbody tr:first-child .btn-text', null)))
    const td = JSON.parse(await b.evalJS(M('.ords tbody tr:first-child .c-prod', null)))
    console.log(`\n===== ${W} 宽 =====`)
    console.log(' 行:', info.row, '高', info.rowH, ' 表:', info.table, ' 表头底', info.headBottom)
    console.log(' 列:', JSON.stringify(info.tds))
    console.log(' 图:', JSON.stringify(info.img), ' 按钮:', JSON.stringify(info.btn), ' 横向溢出:', info.overX)
    console.log(' hover 后 → c-prod 底色:', td && td.bg, ' 盒', td && td.box)
    console.log('  图 盒', img && img.box, ' 按钮 盒', btn && btn.box)
    // 越界判据：图 / 按钮是否越出所在 td 的盒子
    const tdProd = info.tds[0]
    const tdAct = info.tds[info.tds.length - 1]
    if (info.img) R.ok(`${W} 缩略图未越出商品格（上下）`, info.img.t >= tdProd.t && info.img.b <= tdProd.b, [info.img.t - tdProd.t, tdProd.b - info.img.b])
    R.ok(`${W} 按钮未越出末格（左右）`, info.btn.l >= tdAct.l && info.btn.r <= tdAct.r, [info.btn.l - tdAct.l, tdAct.r - info.btn.r])
    R.ok(`${W} 无横向溢出`, info.overX === 0, info.overX)
    await b.shot(`docs/美化/dho3/hover-${W}.png`)
    await b.shotFull(`docs/美化/dho3/hover-full-${W}.png`)
  }
  R.dump()
} finally { await b.close() }
