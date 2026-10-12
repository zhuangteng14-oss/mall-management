// probe-b5-admin-catmatch.mjs —— 一次性探针（2026-10-11，C3 块 5 · 「限商品种类」改多选 + 适用范围联动）
//
// 问的是：
//   ①「全场」下「限商品种类」整块**不出现**（原来是 :disabled 灰着还占位）；「归属商家」也不再有输入框；
//   ② 切「部分商家」⇒ 出现多选下拉；打开菜单 ⇒ 选项 = 既有一级类目（5 条，文案逐字对）；
//   ③ 勾两项 ⇒ 展示串 = 「电子用品，服装」（全角逗号）；「清空」按钮出现；
//   ④ 点「清空」⇒ 回到占位文案、已选归零；
//   ⑤ 提交时发出去的 `CategoryMatch` 仍是**半角逗号串**（后端存储格式不变）；
//   ⑥ 切回「全场」⇒ 块消失，**且已选被清掉**（不会发一张 Scope=all 却带类目名的券）。
//
// 全部走 `_cdp.mjs` 的 `mock`：**不碰真库**（后端 /goods/goodstype 与建券端点都被顶掉）。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const CATS = ['电子用品', '生活用品', '服装', '美食', '运动用品']
const GOODSTYPE = {
  success: true,
  // ParentId 一律 0（现有 goodstype 是单层），与真端点同形。
  list: CATS.map((n, i) => ({ Id: i + 1, GoodType: n, GoodTypeNew: 0, ParentId: 0, Sort: 0, State: true })),
}
const LIST = { success: true, total: 0, limit: 200, list: [] }
const STATS = { success: true, trend: { days: [], claimed: [], used: [] }, types: [], totals: {} }

/** 最近一次「建券」请求体（URL 编码原样），用来断言发出去的 `CategoryMatch`。 */
let createBody = ''

const b = await open({
  w: 1440,
  h: 1100,
  mock: (url, method, postData) => {
    if (url.includes('/goods/goodstype')) return { body: JSON.stringify(GOODSTYPE) }
    if (url.includes('/admin/marketing/coupon/list')) return { body: JSON.stringify(LIST) }
    if (url.includes('/admin/marketing/coupon/stats')) return { body: JSON.stringify(STATS) }
    if (url.includes('/admin/marketing/coupon/create')) {
      createBody = postData || ''
      return { body: JSON.stringify({ success: true, CouponId: 'CPPROBE00000001' }) }
    }
    if (url.includes('/user/me')) return { body: JSON.stringify({ role: 3, username: 'probe_admin' }) }
    return { body: '{}' }
  },
})

const r = report('管理端 券 · 限商品种类多选 + 适用范围联动')

const setScope = (v) =>
  b.evalJS(`(() => {
    const sel = [...document.querySelectorAll('.a-f select')].find((s) => [...s.options].some((o) => o.value === 'merchant'))
    if (!sel) return 'MISS-SELECT'
    sel.value = ${JSON.stringify(v)}
    sel.dispatchEvent(new Event('change'))
    return 'OK'
  })()`)

// ⚠️ 本段整体是**模板字面量** ⇒ 注释里**不能出现反引号**（写了会当场截断、报 SyntaxError）。
const shape = () => `(() => {
  const cat = document.querySelector('.a-cat')
  const menu = document.querySelector('.a-cat-menu')
  const val = document.querySelector('.a-cat-val')
  return {
    hasCat: !!cat,
    hasMerchantWord: /归属商家/.test(document.body.innerText),
    menuOpen: !!menu,
    opts: menu ? [...menu.querySelectorAll('.a-cat-opt span')].map((s) => s.textContent.trim()) : [],
    val: val ? val.textContent.trim() : null,
    placeholderShown: !!document.querySelector('.a-cat-ph'),
    hasClear: !!document.querySelector('.a-cat-clear'),
    checked: cat
      ? [...cat.querySelectorAll('.a-cat-opt')].filter((l) => l.querySelector('input').checked).map((l) => l.textContent.trim())
      : [],
  }
})()`

const clickToggle = () =>
  b.evalJS(`(() => { const t = document.querySelector('.a-cat-toggle'); if (!t) return 'MISS'; t.click(); return 'OK' })()`)
const clickClear = () =>
  b.evalJS(`(() => { const c = document.querySelector('.a-cat-clear'); if (!c) return 'MISS'; c.click(); return 'OK' })()`)
const pickCat = (name) =>
  b.evalJS(`(() => {
    const opt = [...document.querySelectorAll('.a-cat-opt')].find((l) => l.textContent.trim() === ${JSON.stringify(name)})
    if (!opt) return 'MISS:' + name
    opt.querySelector('input').click()
    return 'OK'
  })()`)
const clickCreate = () =>
  b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.a-btn')].find((x) => x.textContent.includes('建券'))
    if (!btn) return 'MISS'
    btn.click(); return 'OK'
  })()`)
/** 建券请求体里 `CategoryMatch` 的值（已解码）；没有该参数 ⇒ null。 */
const sentCategory = () => {
  const m = /(?:^|&)CategoryMatch=([^&]*)/.exec(createBody)
  return m ? decodeURIComponent(m[1]) : null
}
/** 菜单关着就先打开（`清空` 现在**不**收菜单，但切档 / 提交后是关的）。 */
const ensureMenu = async () => {
  const open = await b.evalJS(`!!document.querySelector('.a-cat-menu')`)
  if (!open) {
    await clickToggle()
    await sleep(400)
  }
}

try {
  await b.goto('http://localhost:8080/admin.html', 2500)
  await b.evalJS(
    `(() => { sessionStorage.setItem('role','3');sessionStorage.setItem('loginUsername','probe_admin'); location.hash = '#/admin/business/marketing'; location.reload() })()`
  )
  await sleep(5000)

  const init = await b.evalJS(shape())
  console.log('── 初始（Scope=all）──', JSON.stringify(init))
  r.ok('「全场」下没有「限商品种类」块', !init.hasCat)
  r.ok('页面上已无「归属商家」输入框', !init.hasMerchantWord, init.hasMerchantWord)

  // 切「部分商家」
  await setScope('merchant')
  await sleep(600)
  const mer = await b.evalJS(shape())
  console.log('── 切「部分商家」──', JSON.stringify(mer))
  r.ok('「部分商家」下出现「限商品种类」块', mer.hasCat)

  // 打开下拉
  await clickToggle()
  await sleep(500)
  const open1 = await b.evalJS(shape())
  console.log('── 打开下拉 ──', JSON.stringify(open1))
  r.ok('菜单打开', open1.menuOpen)
  r.ok('选项数 = 类目数（5）', open1.opts.length === 5, open1.opts.length)
  r.ok('选项文案 = 真实一级类目', JSON.stringify(open1.opts) === JSON.stringify(CATS), open1.opts)
  r.ok('未选时按钮显示占位文案', open1.placeholderShown)

  // 勾两项
  await pickCat('电子用品')
  await pickCat('服装')
  await sleep(500)
  const picked = await b.evalJS(shape())
  await b.shot('scripts/probes/out-b5-admin-catmatch.png')
  console.log('── 勾「电子用品」「服装」──', JSON.stringify(picked))
  r.ok('展示串 = 全角逗号拼接', picked.val === '电子用品，服装', picked.val)
  r.ok('两个 checkbox 为选中态', JSON.stringify(picked.checked) === JSON.stringify(['电子用品', '服装']), picked.checked)
  r.ok('出现「清空」按钮', picked.hasClear)

  // 清空
  await clickClear()
  await sleep(500)
  const cleared = await b.evalJS(shape())
  console.log('── 点「清空」──', JSON.stringify(cleared))
  r.ok('清空后回到占位文案', cleared.placeholderShown && cleared.val === null)
  r.ok('清空后已选归零', cleared.checked.length === 0, cleared.checked)
  r.ok('清空后「清空」按钮消失', !cleared.hasClear)
  r.ok('清空后菜单仍开着（方便原地重选）', cleared.menuOpen)

  // 再勾两项 → 提交，验发出去的仍是半角逗号串
  await ensureMenu()
  await pickCat('电子用品')
  await pickCat('服装')
  await sleep(400)
  await clickCreate()
  await sleep(800)
  console.log('── 提交请求体 CategoryMatch ──', JSON.stringify(sentCategory()))
  r.ok('提交的 CategoryMatch = 半角逗号串', sentCategory() === '电子用品,服装', sentCategory())

  // 切回「全场」：块消失 + 已选被清
  await setScope('merchant')
  await sleep(400)
  await ensureMenu()
  await pickCat('美食')
  await sleep(300)
  await setScope('all')
  await sleep(500)
  const backAll = await b.evalJS(shape())
  console.log('── 切回「全场」──', JSON.stringify(backAll))
  r.ok('切回「全场」后块消失', !backAll.hasCat)
  await clickCreate()
  await sleep(800)
  console.log('── 切回后再提交的 CategoryMatch ──', JSON.stringify(sentCategory()))
  r.ok('切回「全场」后不再发 CategoryMatch（已选被清）', sentCategory() === null, sentCategory())
} finally {
  await b.close()
}

process.exit(r.dump() ? 1 : 0)
