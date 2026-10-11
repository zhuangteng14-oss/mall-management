// probe-b5-admin-grants.mjs —— 一次性探针（2026-10-11，C3 块 5 手验 ④）
//
// 问的是「点『明细』之后，明细**长在被点的那一行下方**了吗」：
//   ① 点之前：表体只有 N 行券行，没有 `.a-expand`；
//   ② 点第 1 行的「明细」：`.a-expand` 出现，且**紧跟在第 1 个 `<tr>` 之后**（不是页底）；
//   ③ 展开行的 `colspan` = 表头列数（8），且它**在 `<tbody>` 之内**（表里加行、不动列结构）；
//   ④ 点同一行「收起」：`.a-expand` 消失；
//   ⑤ 点第 2 行：`.a-expand` 跟着换到第 2 行下面（不会两处同时展开）。
//
// 全部走 `_cdp.mjs` 的 `mock`：**不碰真库**（真库只有 2 张券，也不该为探针建数据）。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const coupon = (i, name) => ({
  Id: i + 1,
  CouponId: 'PROBE00' + (i + 1),
  Name: name,
  Type: i === 0 ? 'threshold' : 'percent',
  FaceValue: i === 0 ? 20 : 0.85,
  Threshold: i === 0 ? 100 : 0,
  TotalQty: 0,
  ClaimedQty: i + 1,
  Scope: 'all',
  MerchantAccount: '',
  CategoryMatch: '',
  Status: 'active',
  StartAt: '2026-10-01 00:00:00',
  EndAt: '2026-12-31 00:00:00',
})

const LIST = {
  success: true,
  total: 2,
  limit: 200,
  list: [coupon(0, '探针满减券'), coupon(1, '探针折扣券')],
}

// ⚠️ `row` 必须**回显请求里那张券**（后端 `selectGrant` 是这么答的）。第一版写死成第 1 张
//    ⇒ 点第 2 行的「明细」也会把展开位弹回第 1 行，看起来像前端 bug，其实是 mock 的错。
const GRANTS = (couponId) => ({
  success: true,
  row: LIST.list.find((c) => c.CouponId === couponId) || LIST.list[0],
  list: [
    { GrantId: 11, ToAccount: 'probe_a', Source: 'self', Status: 'unused', GrantedAt: '2026-10-11 02:00:00', UsedAt: null, OrderId: null },
    { GrantId: 12, ToAccount: 'probe_b', Source: 'grant', Status: 'used', GrantedAt: '2026-10-11 03:00:00', UsedAt: '2026-10-11 04:00:00', OrderId: 'ORD-9' },
  ],
})

const STATS = {
  success: true,
  trend: { days: ['10-10', '10-11'], claimed: [1, 2], used: [0, 1] },
  types: [{ Type: 'threshold', count: 1 }, { Type: 'percent', count: 1 }],
  totals: { coupons: 2, claimed: 3, used: 1 },
}

const b = await open({
  w: 1440,
  h: 1100,
  mock: (url, method, postData) => {
    if (url.includes('/admin/marketing/coupon/list')) return { body: JSON.stringify(LIST) }
    if (url.includes('/admin/marketing/coupon/grants')) {
      const id = /CouponId=([^&]*)/.exec(postData || '')
      return { body: JSON.stringify(GRANTS(id ? decodeURIComponent(id[1]) : '')) }
    }
    if (url.includes('/admin/marketing/coupon/stats')) return { body: JSON.stringify(STATS) }
    if (url.includes('/user/me')) return { body: JSON.stringify({ role: 3, username: 'probe_admin' }) }
    return { body: '{}' }
  },
})

const r = report('管理端 券 · 明细行内展开 ④')

/**
 * 点一个按钮。
 * ⚠️ 判据不能用 `^明细$`：按钮里有个**字形图标**（`IconGlyph`），`innerText` 是 `"≡\n明细"`
 *    ⇒ 一律用 `textContent.includes(标签)`。（第一版探针就栽在这，报 click of undefined。）
 */
const clickBtn = (label, idx = 0) =>
  b.evalJS(`(() => {
    const bs = [...document.querySelectorAll('.a-btn')].filter((x) => x.textContent.includes(${JSON.stringify(label)}))
    if (!bs[${idx}]) return 'MISS:' + document.querySelectorAll('.a-btn').length
    bs[${idx}].click(); return 'OK'
  })()`)

/**
 * 点**第 N 个券行**操作列里最后一个按钮（= 「明细 / 收起」那个开关）。
 * ⚠️ 不能按「含『明细』的第 N 个按钮」选：展开后那一行的按钮就变成「收起」了，
 *    含「明细」的按钮数会从 2 掉到 1 ⇒ `[1]` 取不到（第二版探针栽在这）。
 */
const clickRowBtn = (rowIdx) =>
  b.evalJS(`(() => {
    // ⚠️ 只能取**外层** tbody 的**直接子元素**：选择器会把展开行里那张内层明细表的领取行
    //    也一并选进来（它在文档序里排在第 2 个券行**之前**）⇒ 取到的就不是券行，
    //    那个行里没有 .a-ops 按钮。⚠️ 本行在**模板字符串**里，注释里**不能出现反引号**。
    const trs = [...document.querySelector('.a-tbl tbody').children].filter((tr) => !tr.classList.contains('a-expand'))
    const tr = trs[${rowIdx}]; if (!tr) return 'MISS-ROW'
    const bs = tr.querySelectorAll('.a-ops .a-btn'); if (!bs.length) return 'MISS-BTN'
    bs[bs.length - 1].click(); return 'OK'
  })()`)

const shape = () => `(() => {
  const tbody = document.querySelector('.a-tbl tbody')
  const trs = [...(tbody ? tbody.children : [])]
  return {
    rows: trs.length,
    // 每一行是什么：'coupon' = 券行（含 .a-ops 按钮列）/ 'expand' = 明细行
    kinds: trs.map((tr) => (tr.classList.contains('a-expand') ? 'expand' : 'coupon')),
    // 券行的券名（用来分辨「展开的是第几行」）
    names: trs.filter((tr) => !tr.classList.contains('a-expand')).map((tr) => (tr.querySelector('.a-ink') || {}).innerText),
    // 展开行落在整体 children 里的下标（1 ⇒ 在第 1 个券行之后）
    expandIdx: trs.findIndex((tr) => tr.classList.contains('a-expand')),
    expandColspan: (trs.find((tr) => tr.classList.contains('a-expand')) || {}).firstElementChild
      ? trs.find((tr) => tr.classList.contains('a-expand')).firstElementChild.getAttribute('colspan') : null,
    expandInTbody: !!trs.find((tr) => tr.classList.contains('a-expand')),
    // 页底那一块**不该**再有（模板里已删）：页面里应当只有 1 个「领取明细」标题
    heads: [...document.querySelectorAll('*')].filter((e) => e.children.length === 0 && /领取明细/.test(e.innerText || '')).length,
    tableCols: document.querySelector('.a-tbl thead').querySelectorAll('th').length,
    grantsShown: trs.find((tr) => tr.classList.contains('a-expand'))
      ? trs.find((tr) => tr.classList.contains('a-expand')).querySelectorAll('.a-tbl-in tbody tr').length : -1,
    beforeExpand: !!document.querySelector('.a-expand'),
  }
})()`

try {
  await b.goto('http://localhost:8080/admin.html', 2500)
  await b.evalJS(`(() => { sessionStorage.setItem('role','3');sessionStorage.setItem('loginUsername','probe_admin'); location.hash = '#/admin/business/marketing'; location.reload() })()`)
  await sleep(5000)
  await b.evalJS(`(() => { const h = [...document.querySelectorAll('.a-btn')].find(b => /明细/.test(b.innerText)); if (h) h.scrollIntoView({ block: 'center' }) })()`)
  await sleep(500)

  const before = await b.evalJS(shape())
  console.log('── 点之前 ──', JSON.stringify(before))
  r.ok('表体只有 2 个券行', before.kinds.filter((k) => k === 'coupon').length === 2, before.kinds)
  r.ok('点之前没有展开行', before.expandIdx === -1)
  r.ok('点之前页面无「领取明细」标题', before.heads === 0, before.heads)

  // 点第 1 行的「明细」
  await clickRowBtn(0)
  await sleep(1500)
  const one = await b.evalJS(shape())
  await b.shot('scripts/probes/out-b5-admin-expand.png')
  console.log('── 点第 1 行「明细」 ──', JSON.stringify(one))
  r.ok('展开行出现了', one.expandIdx > -1)
  r.ok('展开行紧跟在**第 1 个**券行之后', one.expandIdx === 1, one.expandIdx)
  r.ok('展开行 colspan = 表头列数', one.expandColspan === String(one.tableCols), `${one.expandColspan} vs ${one.tableCols}`)
  r.ok('明细表渲染出 2 行领取记录', one.grantsShown === 2, one.grantsShown)

  // 点第 2 行的「明细」→ 展开位该跟着换
  await clickRowBtn(1)
  await sleep(1500)
  const two = await b.evalJS(shape())
  console.log('── 再点第 2 行「明细」 ──', JSON.stringify(two))
  r.ok('只有一处展开（不同时展开两处）', two.kinds.filter((k) => k === 'expand').length === 1, two.kinds)
  r.ok('展开位换到第 2 个券行之后', two.expandIdx === 2, two.expandIdx)   // 0-based：券1 / 券2 / 展开

  // 再点一次 → 收起
  await clickRowBtn(1)
  await sleep(1500)
  const three = await b.evalJS(shape())
  console.log('── 点「收起」 ──', JSON.stringify(three))
  r.ok('收起后展开行消失', three.expandIdx === -1)
  r.ok('收起后仍是 2 个券行', three.kinds.filter((k) => k === 'coupon').length === 2, three.kinds)
} finally {
  await b.close()
}

process.exit(r.dump() ? 1 : 0)
