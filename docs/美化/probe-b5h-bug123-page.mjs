// probe-b5h-bug123-page.mjs —— C3 块 5 ·〈Bug 1/2/3〉**页面侧**核对（2026-10-12）。
//
// 与 `scripts/probes/probe-b5h-bug123.mjs`（HTTP 层）分工：那一支验**端点回的列**，
// 本支验**页面上真画出来的卡**（去重、已售完过滤、类别标签、店铺页灰态）。
//
// ⚠️ **不 mock**：`_cdp.mjs` 的 mock 传 `null` ⇒ 页面直连真 `8088`（a3 profile）。
//    要的就是真数据 —— 本笔要证的是「41 / 3 / 4 张卡」这种**数得出来的数**，mock 出来的
//    数只能证明我编对了。前提：dev server 在 **8080**（CORS 白名单里的那个端口），后端 8088 在跑。
//
// 四张面（用户 ②③ 点名的）：
//   ① 主页「为你推荐」—— 应为 41 张（43 −1 同码重复 −1 已售完）；耳机只一张；无 `.pcard.sold`
//   ② 搜索页「耳机」     —— 「共 3 件」；耳机只一张
//   ③ 店铺页 `dRD3gqtbFr` —— 本店网格 4 张；手环卡 **有** `.pcard.sold` + 红斜「已售完」+
//                            两枚类别标（秒杀商品 + 国补）同屏
//   ④ 秒杀页 `/seckill`   —— 手环**仍要出现**（用户 ②：秒杀页不过滤已售完）
//
// 用法：node docs/美化/probe-b5h-bug123-page.mjs
// ⚠️ 下面几段 MEASURE 是模板字符串 ⇒ **内部不出现反引号、不出现 `$` + `{`**（本仓已踩**四次** ——
//    第四次就在本文件 `DUMP` 的 kinds 那条注释里，当天刚修；见那条注释末尾的按语）。
import { open, sleep, report } from './_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const SHOP = 'dRD3gqtbFr'          // 演示店（yunqi_digital）
const EARPHONE = '无线蓝牙耳机 声谷标准版'
const BAND = '智能手环 5 代 血氧版'   // 已售完那件（IsSeckill=1 IsSoldOut=1 IsSubsidy=1）

// 一次把页面上**所有商品卡**读回来（卡一律是 `ProductCard.vue` 的 `.pcard`）。
// ⚠️ 店铺页的「本店网格」怎么认：**不能**靠 `.chips` —— 分类 chip 只在**本店 ≥2 个类目**时才画
//    （见 `ShopHome.vue:180-183` 那条按语），演示店只有「电子用品」一类 ⇒ 今天**没有** chip 行。
//    可靠的认法 = 总数减去「猜你喜欢」那一节里的卡（那是本页唯一取别家数据的 `.cards`）。
const DUMP = `JSON.stringify((function () {
  var rec = [].slice.call(document.querySelectorAll('section')).filter(function (s) {
    var h = s.querySelector('.sec-h')
    return h && h.textContent.indexOf('猜你喜欢') >= 0
  })[0]
  var recCards = rec ? [].slice.call(rec.querySelectorAll('.pcard')) : []
  var cards = [].slice.call(document.querySelectorAll('.pcard'))
  var grid = cards.filter(function (c) { return recCards.indexOf(c) < 0 })
  var nameOf = function (c) { var n = c.querySelector('.pcard-name'); return n ? n.textContent.trim() : '' }
  return {
    hash: location.hash,
    nCards: cards.length,
    names: cards.map(nameOf),
    kinds: cards.map(function (c) {
      /* ⚠️ 必须**排除 .ghost 那一类**（用户 ① 之后每个无标签的卡也有一枚签元素了，它的文案是
         真签那句「秒杀商品」）—— 不排除的话本读数会把「普通商品」全报成「秒杀商品」
         （第一次跑就是这个结果：主页 41 张全报红标）。同理，任何按 .pcard-kind 数标签的
         断言都要带这个过滤器。
         ⚠️ 这段注释**不能出现反引号**：本表达式整体是一段模板字符串，反引号会把模板提前闭合
         （本仓第四次踩，见 _work 与探针头注）。 */
      return [].slice.call(c.querySelectorAll('.pcard-kind')).filter(function (k) { return !k.classList.contains('ghost') }).map(function (k) { return k.textContent.trim() })
    }),
    soldCards: cards.filter(function (c) { return c.classList.contains('sold') }).map(nameOf),
    stamps: [].slice.call(document.querySelectorAll('.pcard-stamp')).map(function (s) { return s.textContent.trim() }),
    /* 〈用户 ①·标签行等高占位〉（2026-10-12）：卡的**像素高** + 这一行的两种签的数量。
       heightsOfGrid = 本店网格每张卡的 offsetHeight（等高 ⇔ 取值集合只有 1 个元素）。
       ghost  = 隐形占位签（普通商品那一枚，visibility:hidden）
       real   = 真标签签（秒杀 / 国补）。两者的**元素高度**必须相等，否则占位就白占了。 */
    heightsOfGrid: grid.map(function (c) { return c.offsetHeight }),
    ghost: [].slice.call(document.querySelectorAll('.pcard-kind.ghost')).length,
    ghostVisible: [].slice.call(document.querySelectorAll('.pcard-kind.ghost')).map(function (e) { return getComputedStyle(e).visibility }),
    kindHeights: [].slice.call(document.querySelectorAll('.pcard-kind')).map(function (e) { return e.offsetHeight }),
    realKinds: [].slice.call(document.querySelectorAll('.pcard-kind')).filter(function (e) { return !e.classList.contains('ghost') }).length,
    gridCards: grid.length,
    gridNames: grid.map(nameOf),
    recNames: recCards.map(nameOf),
    toolbarRight: (function () { var e = document.querySelector('.toolbar .right'); return e ? e.textContent.trim() : '' })(),
    bodyText: document.body.innerText.indexOf('手环') >= 0
  }
})())`

/** 点店铺页的二级 tab（`热销` / `全部` / `折扣`）。**默认停在「热销」**（柱状图，不是卡片）——
 *  「本店商品网格」要切到**「全部」**那格才画，别对着热销榜数卡。 */
const CLICK_ALL = `(function () {
  var t = [].slice.call(document.querySelectorAll('.tabs .tab')).filter(function (b) { return b.textContent.trim() === '全部' })[0]
  if (!t) return 'no-tab'
  t.click(); return 'clicked'
})()`

/** 已登录态：只贴 `sessionStorage`。
 *  ⚠️ 键名是 **`loginUsername`**，不是 `username` —— `api/auth.js:17` 的 `K.username = 'loginUsername'`
 *     （`sessionStorage` 里那个叫 `username` 的键**没人读**）。第一版贴了 `username` ⇒ `getUsername()`
 *     为空 ⇒ `isLoggedIn()` 假 ⇒ 秒杀页只渲染「登录后即可查看」（假红一项）。
 *     其余两键与 `K` 逐字相同（`api/auth.js:20` 的 `customerId` / `:21` 的 `role`）。 */
const LOGIN = `(function () {
  sessionStorage.setItem('loginUsername','1111')
  sessionStorage.setItem('customerid','uDopHnCeLS')
  sessionStorage.setItem('role','1')
  return sessionStorage.getItem('loginUsername')
})()`

const r = report('probe-b5h-bug123-page')
const b = await open({ w: 1440, h: 1000 })

/** 一张面：**硬换文档**（先 `about:blank`）再进目标 hash。
 *  ⚠️ 为什么不能连着改 hash：实测（本探针第一版）从搜索页 `Page.navigate` 到 `#/shop?...` 之后
 *     页面**仍停在搜索页**（截图与 DOM 都是搜索页），即「只改 fragment 的导航」这一跳没生效 ——
 *     于是店铺 / 秒杀两张面全测在搜索页上（7 项假红）。中间垫一次 `about:blank` 就稳了。 */
const face = async (hash, wait, shot) => {
  await b.goto('about:blank', 250)
  await b.goto('http://localhost:8080/' + hash, wait)
  await b.evalJS(LOGIN)                      // 每次载入后重新贴（about:blank 不影响同源 sessionStorage，但贴了更稳）
  if (shot) await b.shot(EV + shot)
  return JSON.parse(await b.evalJS(DUMP))
}

try {
  // ── ① 主页「为你推荐」────────────────────────────────────────────────────────
  const home = await face('#/', 5000, 'out-b5h-home-recommend.png')
  const ear = home.names.filter((n) => n === EARPHONE).length
  r.ok('主页卡片数 = 41（43 −1 同码 −1 已售完）', home.nCards === 41, home.nCards)
  r.ok('主页耳机只出现一次', ear === 1, ear)
  r.ok('主页没有已售完的卡（共享面已滤）', home.soldCards.length === 0, home.soldCards)
  r.ok('主页没有「已售完」贴图', home.stamps.length === 0, home.stamps.length)
  r.ok('主页有秒杀红标（键盘那件）', home.kinds.some((k) => k.indexOf('秒杀商品') >= 0),
    home.kinds.filter((k) => k.length).length)

  // ── ④ 用户 ①（2026-10-12）：标签行**等高占位** ──────────────────────────────────
  // 改前是 `v-if="kinds.length"` ⇒ 无标签的卡**连行都不画**（矮一行）⇒ 栅格参差。
  const uniqH = (a) => Array.from(new Set(a))
  const untagged = home.kinds.filter((k) => k.length === 0).length
  r.ok('主页：无标签的卡也占住标签行（隐形签数 = 无标签卡数）', home.ghost === untagged,
    { ghost: home.ghost, untagged })
  r.ok('主页：隐形签真的不可见（visibility:hidden，不是 display:none）',
    home.ghostVisible.length > 0 && home.ghostVisible.every((v) => v === 'hidden'), home.ghostVisible.slice(0, 3))
  r.ok('主页：真签与隐形签**同像素高**（占位成立的前提）', uniqH(home.kindHeights).length === 1,
    uniqH(home.kindHeights))
  r.ok('主页：41 张卡**全等高**（用户 ① 的诉求）', uniqH(home.heightsOfGrid).length === 1, uniqH(home.heightsOfGrid))

  // ── ② 搜索页「耳机」──────────────────────────────────────────────────────────
  const sea = await face('#/customer/search?kw=%E8%80%B3%E6%9C%BA', 4500, 'out-b5h-search-earphone.png')
  const ear2 = sea.names.filter((n) => n === EARPHONE).length
  r.ok('搜索页「共 3 件」（4 行 −1 同码）', sea.toolbarRight.indexOf('共 3 件') >= 0, sea.toolbarRight)
  r.ok('搜索页耳机只出现一次', ear2 === 1, ear2)
  r.ok('搜索页卡片数 = 3', sea.nCards === 3, sea.nCards)

  // ── ③ 店铺页（本店网格 + 猜你喜欢）───────────────────────────────────────────
  await b.goto('about:blank', 250)
  await b.goto('http://localhost:8080/#/shop?MerchantId=' + SHOP, 5000)
  await b.evalJS(LOGIN)
  const tab = await b.evalJS(CLICK_ALL)      // 默认停「热销」（柱状图）⇒ 先切「全部」
  await sleep(600)
  const shop = JSON.parse(await b.evalJS(DUMP))
  await b.shot(EV + 'out-b5h-shophome.png')
  r.ok('店铺页切到「全部」二级 tab', tab === 'clicked', tab)
  r.ok('本店网格 4 张（5 行 −1 同码）', shop.gridCards === 4, shop.gridCards)
  r.ok('本店网格含已售完的手环', shop.gridNames.indexOf(BAND) >= 0, shop.gridNames)
  r.ok('手环卡带 `.sold` 灰态', shop.soldCards.indexOf(BAND) >= 0, shop.soldCards)
  r.ok('手环卡有「已售完」红斜贴图', shop.stamps.indexOf('已售完') >= 0, shop.stamps)
  const bandKinds = shop.kinds[shop.names.indexOf(BAND)] || []
  r.ok('手环卡两枚类别标同屏（秒杀商品 + 国补）',
    bandKinds.indexOf('秒杀商品') >= 0 && bandKinds.indexOf('国补') >= 0, bandKinds)
  r.ok('本店网格耳机只一张', shop.gridNames.filter((n) => n === EARPHONE).length === 1,
    shop.gridNames.filter((n) => n === EARPHONE).length)
  r.ok('猜你喜欢（别家）不含已售完', shop.recNames.indexOf(BAND) < 0, shop.recNames)
  r.ok('店铺页「全部」网格：4 张卡**全等高**（含手环那张带两枚标签的）',
    uniqH(shop.heightsOfGrid).length === 1, uniqH(shop.heightsOfGrid))

  // ── ④ 秒杀页：已售完的**仍要显示**（用户 ②）──────────────────────────────────
  const sk = await face('#/seckill', 5000, 'out-b5h-seckill.png')
  r.ok('秒杀页仍列出已售完的手环', sk.bodyText === true, sk.bodyText)

  console.log('\n主页卡名（前 6）:', home.names.slice(0, 6).join(' | '))
  console.log('主页带真标签的卡（隐形签不算）:',
    home.names.map((n, i) => (home.kinds[i].length ? n + '[' + home.kinds[i].join('/') + ']' : null))
      .filter(Boolean).join(' | ') || '（无）')
  console.log('本店网格:', shop.gridNames.join(' | '))
  console.log('猜你喜欢:', shop.recNames.join(' | '))
  console.log('全页卡数:', shop.nCards, '（本店 ' + shop.gridCards + ' + 猜你喜欢 ' + shop.recNames.length + '）')
} finally {
  await b.close()
}
r.dump()
