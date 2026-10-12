// c3b5d-cards-before.mjs —— C3 块 5 B 组续笔的「改前取证」：
// 把三个页面（HomePage「为你推荐」/ ShopHome「本店商品」/ ProductPage「猜你喜欢」）
// 的商品卡**在同一宽度下**各截一张，并把 DOM 结构 + 计算样式 dump 出来。
//
// 为什么需要它：本笔要把三处收成一个 `ProductCard.vue`，判据是「改前到底长什么样」。
//   方案 §一·问 1 的对照表是读源码读出来的；这份产物是**跑出来的**，两者互为交叉。
//
// 口径：**真后端**（localhost:8088，a3 profile）+ **真库**，不 mock —— 要看的就是真数据。
//   ⚠️ 本探针**纯读**：不发任何写请求。
//
// 用法：node scripts/probes/c3b5d-cards-before.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const SHOP = 'dRD3gqtbFr'                                  // yunqi_digital（有真销量的那家）
const GOOD = encodeURIComponent('无线蓝牙耳机 声谷标准版')
const GTYPE = encodeURIComponent('电子用品')
const PRODUCT = `http://localhost:8080/#/product?MerchantId=${SHOP}&GoodName=${GOOD}&GoodType=${GTYPE}&Id=76`

// ⚠️ 整段交给 evalJS —— 里面不能有反引号、不能有 ${（否则模板提前闭合）。
const MEASURE = `(function(){
  var pick = function(sel){
    var n = document.querySelector(sel);
    if (!n) return null;
    var cs = getComputedStyle(n);
    return { cls: n.className, w: Math.round(n.getBoundingClientRect().width),
             h: Math.round(n.getBoundingClientRect().height),
             border: cs.borderTopWidth + ' ' + cs.borderTopColor,
             radius: cs.borderTopLeftRadius, shadow: cs.boxShadow,
             bg: cs.backgroundColor };
  };
  var cards = document.querySelectorAll('article.card, a.card, .card');
  var first = cards.length ? cards[0] : null;
  var out = { cardCount: cards.length, firstCard: pick(cards.length ? (cards[0].tagName.toLowerCase() + '.card') : '.none') };
  out.firstCardHTML = first ? first.outerHTML.slice(0, 900) : null;
  out.imgRatio = null;
  if (first) {
    var im = first.querySelector('img');
    if (im) { var r = im.getBoundingClientRect(); out.imgRatio = r.width && r.height ? (r.width / r.height).toFixed(3) : null; }
  }
  out.classList = first ? Array.prototype.map.call(first.querySelectorAll('*'), function(e){ return e.className }).filter(function(s){ return typeof s === 'string' && s }).slice(0, 14) : [];
  out.text = first ? first.innerText.replace(/\\n+/g, ' | ') : null;
  return JSON.stringify(out);
})()`

const b = await open({ w: 1440, h: 1000 })
const R = (await import('../../docs/美化/_cdp.mjs')).report('c3b5d-cards-before')

async function shoot(url, waitMs, scrollSel, file, label) {
  await b.goto(url, waitMs)
  const scrolled = await b.evalJS(`(function(){ var n = document.querySelector('${scrollSel}'); if (!n) return null; n.scrollIntoView({ block: 'start' }); return Math.round(n.getBoundingClientRect().top); })()`)
  await sleep(600)
  const m = await b.evalJS(MEASURE)
  await b.shot(EV + file)
  const d = m ? JSON.parse(m) : null
  R.ok(label + ' · 区块在页上（scrollIntoView 命中）', scrolled !== null, scrolled)
  R.ok(label + ' · 卡片数 > 0', !!d && d.cardCount > 0, d && d.cardCount)
  R.ok(label + ' · 卡上**没有**店名/销量字样', !!(d && d.text) && !/已售|销量/.test(d.text), d && d.text)
  console.log('--- ' + label + ' ---')
  console.log('  firstCard :', d && JSON.stringify(d.firstCard))
  console.log('  imgRatio  :', d && d.imgRatio)
  console.log('  text      :', d && d.text)
  console.log('  cls       :', d && d.classList.join(' > '))
  console.log('  html      :', d && d.firstCardHTML)
  return d
}

try {
  // ① HomePage「为你推荐」
  const home = await shoot('http://localhost:8080/#/', 3500, '.rec', 'before-home-cards.png', '① HomePage 为你推荐')
  R.ok('① 图比例是 4/3', !!home && home.imgRatio && Math.abs(Number(home.imgRatio) - 1.333) < 0.02, home && home.imgRatio)
  R.ok('① 卡上有加购钮', !!home && /card-buy/.test(home.firstCardHTML || ''))
  // ⚠️ 判据取 innerText 而非 HTML：`firstCardHTML` 是 900 字截断的，`.card-meta` 落在截断之外
  //    ⇒ 拿 HTML 判会假红（v1 实测如此）。分类名在 text 末尾。
  R.ok('① 元信息行是分类名（不是店名）', !!home && /电子用品|生活用品|服装|美食|运动用品/.test(home.text || ''), home && home.text)

  // ② ShopHome「本店商品」
  const shop = await shoot(`http://localhost:8080/#/shop?MerchantId=${SHOP}`, 3500, '.cards', 'before-shop-cards.png', '② ShopHome 本店商品')
  R.ok('② 图比例是 1/1', !!shop && shop.imgRatio && Math.abs(Number(shop.imgRatio) - 1) < 0.02, shop && shop.imgRatio)
  R.ok('② 卡上无加购钮', !!shop && !/card-buy/.test(shop.firstCardHTML || ''))

  // ③ ProductPage「猜你喜欢」
  const prod = await shoot(PRODUCT, 4000, '.reco', 'before-product-cards.png', '③ ProductPage 猜你喜欢')
  R.ok('③ 图比例是 1/1', !!prod && prod.imgRatio && Math.abs(Number(prod.imgRatio) - 1) < 0.02, prod && prod.imgRatio)

  R.ok('两页卡形不同（4/3 vs 1/1）—— 这就是「要统一」的那件事',
    !!home && !!shop && home.imgRatio !== shop.imgRatio, { home: home && home.imgRatio, shop: shop && shop.imgRatio })
} finally {
  R.dump()
  await b.close()
}
