// c3b5d-cards-after.mjs —— C3 块 5 B 组续笔的「改后自检」：与 `c3b5d-cards-before.mjs` **同三个页面、
// 同一个宽度、同一种量法**，判据反过来 —— 改前量的是「三处不一样」，这里量「三处一模一样」。
//
// 判据（逐条，全部是**量出来的**，不是读源码推的）：
//   ① 三处的卡都是 `<a class="pcard">`，图 4/3，1px `--line` 细框 + 8px 圆角 + 白底 + **零阴影**；
//   ② 三处都有加购预留件（`.pcard-buy`）；
//   ③ 元信息行 = 左店名（`.shop`）+ 右「已售 N 件」（`.sold`，**只有 N>0 才在**），且**不出现 `·`**；
//   ④ 跳转 query **7 个参数**（T-guard-6 要求，见 `HomePage.vue` 的 `onCardNav` 注释）；
//   ⑤ 三处的计算样式**两两相同**（框色 / 圆角 / 底色 / 阴影 / 图比例）。
//
// 口径：**真后端**（localhost:8088，a3 profile）+ **真库**，不 mock。⚠️ 本探针**纯读**。
//
// 用法：node scripts/probes/c3b5d-cards-after.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const SHOP = 'dRD3gqtbFr'                                  // yunqi_digital（有真销量的那家）
const GOOD = encodeURIComponent('无线蓝牙耳机 声谷标准版')
const GTYPE = encodeURIComponent('电子用品')
const PRODUCT = `http://localhost:8080/#/product?MerchantId=${SHOP}&GoodName=${GOOD}&GoodType=${GTYPE}&Id=76`

// ⚠️ 整段交给 evalJS —— 里面不能有反引号、不能有 ${（否则模板提前闭合，报错会指向注释行）。
const MEASURE = `(function(){
  var cards = document.querySelectorAll('a.pcard');
  var first = cards.length ? cards[0] : null;
  var pick = function(n){
    if (!n) return null;
    var cs = getComputedStyle(n);
    return { border: cs.borderTopWidth + '|' + cs.borderTopColor,
             radius: cs.borderTopLeftRadius, bg: cs.backgroundColor, shadow: cs.boxShadow };
  };
  var out = { cardCount: cards.length, style: pick(first), oldCardLeft: document.querySelectorAll('article.card, .card .ph').length };
  if (first) {
    var im = first.querySelector('img');
    if (im) { var r = im.getBoundingClientRect(); out.imgRatio = r.width && r.height ? (r.width / r.height).toFixed(3) : null; }
    out.href = first.getAttribute('href');
    out.qs = first.getAttribute('href').split('?')[1] || '';
    out.qKeys = out.qs.split('&').map(function(p){ return p.split('=')[0] }).filter(function(s){ return s });
    out.hasBuy = !!first.querySelector('.pcard-buy');
    out.shopName = first.querySelector('.pcard-meta .shop') ? first.querySelector('.pcard-meta .shop').innerText : null;
    out.sold = first.querySelector('.pcard-meta .sold') ? first.querySelector('.pcard-meta .sold').innerText : null;
    out.text = first.innerText.replace(/\\n+/g, ' | ');
    out.classList = Array.prototype.map.call(first.querySelectorAll('*'), function(e){ return e.className }).filter(function(s){ return typeof s === 'string' && s }).slice(0, 12);
  }
  // 「已售」在整页出现几次（判「N=0 不画」那一条不能只看第一张卡）
  out.soldOnPage = (document.body.innerText.match(/已售 \\d+ 件/g) || []).length;
  out.zeroSoldOnPage = (document.body.innerText.match(/已售 0 件/g) || []).length;
  return JSON.stringify(out);
})()`

const b = await open({ w: 1440, h: 1000 })
const R = (await import('../../docs/美化/_cdp.mjs')).report('c3b5d-cards-after')

async function shoot(url, waitMs, scrollSel, file, label) {
  await b.goto(url, waitMs)
  const scrolled = await b.evalJS(`(function(){ var n = document.querySelector('${scrollSel}'); if (!n) return null; n.scrollIntoView({ block: 'start' }); return Math.round(n.getBoundingClientRect().top); })()`)
  await sleep(600)
  const m = await b.evalJS(MEASURE)
  await b.shot(EV + file)
  const d = m ? JSON.parse(m) : null
  R.ok(label + ' · 区块在页上（scrollIntoView 命中）', scrolled !== null, scrolled)
  R.ok(label + ' · 卡都换成了 `a.pcard`（旧结构一个不剩）', !!d && d.cardCount > 0 && d.oldCardLeft === 0, d && { cards: d.cardCount, oldLeft: d.oldCardLeft })
  R.ok(label + ' · 图 4/3', !!d && !!d.imgRatio && Math.abs(Number(d.imgRatio) - 1.333) < 0.02, d && d.imgRatio)
  R.ok(label + ' · 有 1px 细框 + 8px 圆角 + 白底 + 零阴影',
    !!d && d.style.border === '1px|rgb(221, 226, 229)' && d.style.radius === '8px' && d.style.bg === 'rgb(255, 255, 255)' && d.style.shadow === 'none',
    d && d.style)
  R.ok(label + ' · 有加购预留件', !!d && d.hasBuy === true)
  R.ok(label + ' · 元信息行左侧是店名', !!d && !!d.shopName, d && d.shopName)
  R.ok(label + ' · 元信息行不含 `·`（避开 AI 默认样）', !!d && !/·/.test(d.text || ''), d && d.text)
  R.ok(label + ' · 跳转 query 是 7 个参数（T-guard-6）', !!d && d.qKeys.length === 7, d && d.qKeys)
  R.ok(label + ' · 页面上没有「已售 0 件」', !!d && d.zeroSoldOnPage === 0, d && { sold: d.soldOnPage, zero: d.zeroSoldOnPage })
  console.log('--- ' + label + ' ---')
  console.log('  style     :', d && JSON.stringify(d.style))
  console.log('  imgRatio  :', d && d.imgRatio)
  console.log('  qKeys     :', d && d.qKeys.join(','))
  console.log('  shop      :', d && d.shopName, '| sold:', d && d.sold)
  console.log('  text      :', d && d.text)
  console.log('  cls       :', d && d.classList.join(' > '))
  console.log('  soldOnPage:', d && d.soldOnPage, '| zeroSold:', d && d.zeroSoldOnPage)
  return d
}

try {
  // ① HomePage「为你推荐」
  const home = await shoot('http://localhost:8080/#/', 3500, '.rec', 'after-home-cards.png', '① HomePage 为你推荐')
  R.ok('① 有卡带真销量（`dRD3gqtbFr` 那家的一件在首页也出现）', !!home && home.soldOnPage > 0, home && home.soldOnPage)

  // ② ShopHome「本店商品」
  const shop = await shoot(`http://localhost:8080/#/shop?MerchantId=${SHOP}`, 3500, '.cards', 'after-shop-cards.png', '② ShopHome 本店商品')

  // ③ ProductPage「猜你喜欢」
  const prod = await shoot(PRODUCT, 4000, '.reco', 'after-product-cards.png', '③ ProductPage 猜你喜欢')

  // ④ 三处两两同形 —— 本笔的**主判据**
  const same = (a, c) => !!a && !!c &&
    a.style.border === c.style.border && a.style.radius === c.style.radius &&
    a.style.bg === c.style.bg && a.style.shadow === c.style.shadow && a.imgRatio === c.imgRatio
  R.ok('④ 主页 × 店铺主页 同形', same(home, shop), { home: home && home.style, shop: shop && shop.style })
  R.ok('④ 主页 × 商品页 同形', same(home, prod), { home: home && home.style, prod: prod && prod.style })
  R.ok('④ 店铺主页 × 商品页 同形', same(shop, prod), null)
} finally {
  R.dump()
  await b.close()
}
