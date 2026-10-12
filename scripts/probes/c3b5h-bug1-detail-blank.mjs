// c3b5h-bug1-detail-blank.mjs —— ①「耳机详情页空白」的实测探针（2026-10-12）。
//
// 假设（读码得出，本探针来证实/证伪）：`GoodsCacheService.priceByTriple` 对
// 「(MerchantId, GoodName, ProductCode) 命中 >1 行」是**显式抛异常**的
// （`throw new IncorrectResultSizeDataAccessException(1, rows.size())`），
// 而 `dRD3gqtbFr` 店里 `无线蓝牙耳机 声谷标准版` / `GD-101-9552` 恰好有**两行**
// （goods.Id = 76 与 120）⇒ 该端点对这件商品恒失败 ⇒ 详情页 `allItems` 为空 ⇒ 整页空白。
//
// 对照：同店无重复码的「机械键盘 87 键 茶轴」应当一路 success。
//
// 为什么用 Node 不用 curl：中文（GoodName）经 Git Bash spawn 原生 curl 会被折成 CP936
// （机制见 scripts/probes/c3b4p-curl-encoding.sh）。Node 源码是 UTF-8，中文不经 argv。
//
// 用法：node scripts/probes/c3b5h-bug1-detail-blank.mjs
const BASE = 'http://localhost:8088'
const M = 'dRD3gqtbFr'          // yunqi_digital 的 login.Id

const post = async (p, params) => {
  const r = await fetch(BASE + p, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: new URLSearchParams(params).toString()
  })
  const t = await r.text()
  console.log('── ' + p + '  [' + r.status + ']  ' + JSON.stringify(params))
  console.log('   → ' + (t.length > 700 ? t.slice(0, 700) + ' …' : t))
  try { return JSON.parse(t) } catch { return {} }
}

// 同店两组对照：耳机（同码两行）vs 键盘（无重复）
for (const name of ['无线蓝牙耳机 声谷标准版', '机械键盘 87 键 茶轴']) {
  console.log('\n===== GoodName = ' + name + ' =====')
  const codes = await post('/goods/GoodsProductCodeSelect', { MerchantId: M, GoodName: name })
  const list = (codes && codes.list) || []
  console.log('   型号码 = ' + JSON.stringify(list))
  for (const c of list) {
    await post('/goods/productCodePrice', { MerchantId: M, GoodName: name, ProductCode: c })
  }
}

console.log('\n===== 搜索端点（同码是否显两次）=====')
const s = await post('/goods/productSearch', { keyword: '无线蓝牙耳机' })
const rows = Array.isArray(s.list) ? s.list : []
console.log('   命中行数 = ' + rows.length)
console.log('   [Id, ProductCode, GoodName, MerchantId] = ' +
  JSON.stringify(rows.map((r) => [r.Id, r.ProductCode, r.GoodName, r.MerchantId])))
