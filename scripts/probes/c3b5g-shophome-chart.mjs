// c3b5g-shophome-chart.mjs —— C3 块 5「ShopHome 布局授权笔」的前端自检（2026-10-12）。
//
// ⚠️ **跑真后端**（同上一笔 `c3b5f-shophome-modules.mjs` 的理由）：本笔要验的全是公开面
//    （`goodsSelectHome` / `/shop/profile`），a3 后端（8088）在跑、库里有真数据 ⇒ 真数据正是判据。
//
// 本笔验的是**上一笔没验过的那些**。上一笔那 26 项钉的是「八个模块有没有出来」；
// 本笔钉的是**版面顺序 / 柱状图的几何 / 占位句在不在**：
//   ① 版面序 = 券 → 热销 → 商品 → 折扣 → 猜你喜欢，且猜你喜欢是 `.wrap` 的**最后一个**
//      子元素（用户的唯一硬约束）；「新品首发」独立模块**已取消**
//   ② 柱状图的几何（本笔的设计核心，也是唯一一处不能靠肉眼断的对错）：
//      柱底共一条 0 基线（且与 `.chart::after` 重合）· 柱高 = round(sold/10*60)% × 340px
//      （同一比例尺，不是插值）· 降序 · 柱顶圆角柱底方角 · 柱 → 圆图 → 数值 自下而上叠
//      · 圆图正圆且直径 == 柱宽 · 仅榜首走 `--brand` · 整列是 `<a>` 且 href 带 7 个 query 参数
//   ③ 占位句（用户新规「数据不足 → 占位，不静默消失」）—— 且**不得盖在真数据上**
//   ④ 新品红签恰好 1 枚（`min(3, ⌊5/3⌋) = 1`）
//   ⑤ 头卡事实行常驻；认证徽章仍 0 枚（数据里没有 approved）
//   ⑥ 版面不变量：不横向溢出（⚠️ headless 里滚动条宽恒为 0 ⇒ 这条只抓「粗溢出」）
//   ⑦ 种 `loginUsername` 后 reload：券块换「已登录」那一支，版面与柱状图不受影响
//
// 用法（**前端 8080 + 后端 8088 都要活着**）：
//     node scripts/probes/c3b5g-shophome-chart.mjs
//
// ⚠️ 探针不自己起后端（同仓惯例）：本探针只读公开面，a3 后端在跑即可。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const SHOP_ID = 'dRD3gqtbFr'
const URL = 'http://localhost:8080/#/shop?MerchantId=' + SHOP_ID

// ⚠️ 整段交给 evalJS —— 里面不能有反引号、不能有 ${（否则模板提前闭合，报错会指向注释行）。
const MEASURE = `(function(){
  var r1 = function(n){ return Math.round(n * 10) / 10; };
  var clean = function(s){ return s.replace(/\\s+/g, ' ').trim(); };
  var txt = function(e){ return e ? clean(e.innerText) : null; };
  var all = function(root, sel){ return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
  var blks = all(document, '.wrap > section.blk');
  var heads = blks.map(function(s){ var h = s.querySelector('.sec-h'); return h ? clean(h.innerText) : '?'; });
  var byHead = function(kw){
    for (var i = 0; i < blks.length; i++) { if (heads[i].indexOf(kw) >= 0) return blks[i]; }
    return null;
  };
  var phOf = function(s){ var e = s ? s.querySelector('.ph') : null; return e ? txt(e) : null; };
  var cardsIn = function(s){ return s ? s.querySelectorAll('.pcard').length : -1; };
  var chart = document.querySelector('.chart');
  var cols = chart ? all(chart, '.col') : [];
  var rows = cols.map(function(c, i){
    var bar = c.querySelector('.col-bar');
    var cap = c.querySelector('.col-cap');
    var val = c.querySelector('.col-val');
    var br = bar.getBoundingClientRect(), cr = cap.getBoundingClientRect(), vr = val.getBoundingClientRect();
    var href = c.getAttribute('href') || '';
    var q = (href.split('?')[1] || '').split('&').filter(function(s){ return s.length > 0; });
    var cs = getComputedStyle(bar);
    var mm = /(?:^|&)MerchantId=([^&]*)/.exec(href);
    return {
      i: i,
      no: txt(c.querySelector('.col-no')),
      nm: txt(c.querySelector('.col-nm')),
      val: txt(val),
      tag: c.tagName,
      barH: r1(br.height), barW: r1(br.width), barBot: r1(br.bottom), barTop: r1(br.top),
      capTop: r1(cr.top), capBot: r1(cr.bottom), capW: r1(cr.width), capH: r1(cr.height),
      valBot: r1(vr.bottom),
      radiusTop: cs.borderTopLeftRadius, radiusBot: cs.borderBottomLeftRadius,
      bg: cs.backgroundColor,
      hero: bar.className.indexOf('col-bar-hero') >= 0,
      qn: q.length,
      qKeys: q.map(function(s){ return decodeURIComponent(s.split('=')[0]); }),
      qMerchant: mm ? decodeURIComponent(mm[1]) : null
    };
  });
  var baseAbs = null, baseH = null;
  if (chart) {
    var cs2 = getComputedStyle(chart, '::after');
    baseAbs = r1(chart.getBoundingClientRect().top + parseFloat(cs2.top));
    baseH = cs2.height;
  }
  var tagEls = all(document, '.pcard-tag');
  var last = blks.length ? blks[blks.length - 1] : null;
  var rec = byHead('猜你喜欢');
  return JSON.stringify({
    heads: heads,
    lastHead: last ? clean(last.querySelector('.sec-h').innerText) : null,
    lastIsLastChild: last ? (last.parentNode.lastElementChild === last) : null,
    hasNewSection: !!byHead('新品首发'),
    chartPresent: !!chart,
    cols: rows,
    baseAbs: baseAbs,
    baseH: baseH,
    ph: {
      coupon: phOf(byHead('本店优惠券')),
      hot: phOf(byHead('本店热销')),
      goods: phOf(byHead('本店商品')),
      discount: phOf(byHead('本店折扣')),
      rec: phOf(rec)
    },
    couponLoginLink: !!document.querySelector('.ph-link'),
    couponCards: byHead('本店优惠券') ? byHead('本店优惠券').querySelectorAll('.cpn').length : -1,
    chips: document.querySelectorAll('.chips .chip').length,
    gridCards: cardsIn(byHead('本店商品')),
    cardNames: all(byHead('本店商品') || document, '.pcard-name').map(function(e){ return clean(e.innerText); }),
    tagCount: tagEls.length,
    tagTexts: tagEls.map(function(e){ return txt(e); }),
    tagNames: tagEls.map(function(e){
      var card = e.closest('.pcard');
      return card ? txt(card.querySelector('.pcard-name')) : null;
    }),
    tagSections: tagEls.map(function(e){
      var s = e.closest('section.blk');
      return s ? clean(s.querySelector('.sec-h').innerText) : null;
    }),
    discountCards: cardsIn(byHead('本店折扣')),
    recCards: cardsIn(rec),
    recMerchants: rec ? all(rec, '.pcard').map(function(a){
      var m = /MerchantId=([^&]*)/.exec(a.getAttribute('href') || '');
      return m ? decodeURIComponent(m[1]) : null;
    }) : [],
    bandName: txt(document.querySelector('.hd-nm')),
    bandSub: txt(document.querySelector('.hd-sub')),
    facts: all(document, '.facts .fact').map(function(e){ return clean(e.innerText); }),
    factsRowPresent: !!document.querySelector('.facts'),
    certCount: document.querySelectorAll('.fact-cert').length,
    factNone: !!document.querySelector('.fact-none'),
    scrollW: document.documentElement.scrollWidth,
    innerW: window.innerWidth
  });
})()`

// 期望的柱高（px）= round(sold / 10 * 60)% × 340。10 = 该店最高销量（耳机），
// 340 = `.chart` 的 `--chart-h`。两个数在源码里都有出处（`HOT_LIMIT` 段 / `.chart` 那条注释）。
const H = { 10: 204, 8: 163.2, 6: 122.4, 5: 102 }

/** 轮询等一个选择器（底座没有 waitForSelector）。 */
const waitFor = async (b, sel, ms = 25000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    if (await b.evalJS("!!document.querySelector('" + sel + "')")) return true
    await sleep(250)
  }
  return false
}

const run = async () => {
  const b = await open({ w: 1440, h: 1000, headed: false })
  let bad = 0
  try {
    // ── 第一遍：匿名（a3 闸关 ⇒ 拿不到登录名 ⇒ 券块走「未登录」那一支）────────────────
    await b.goto(URL, 3000)
    const okMount = await waitFor(b, '.chart')
    if (!okMount) throw new Error('页面没挂上 `.chart`（前端 8080 活着吗？）')
    await sleep(1200)
    const m = JSON.parse(await b.evalJS(MEASURE))
    const r = report('c3b5g-shophome-chart · 匿名')

    const WANT = ['本店优惠券', '本店热销', '本店商品', '本店折扣', '猜你喜欢']
    WANT.forEach((w, i) => r.ok('① 版面序 #' + (i + 1) + ' = ' + w,
      (m.heads[i] || '').indexOf(w) >= 0, '实得 ' + JSON.stringify(m.heads)))
    r.ok('① 猜你喜欢是最后一个 section', (m.lastHead || '').indexOf('猜你喜欢') >= 0, m.lastHead)
    r.ok('① 猜你喜欢是 .wrap 的最后一个子元素', m.lastIsLastChild === true, m.lastIsLastChild)
    r.ok('① 「新品首发」独立模块已取消', m.hasNewSection === false, m.hasNewSection)

    r.ok('② 柱状图存在', m.chartPresent === true, m.chartPresent)
    r.ok('② 柱数 = 4（去重后 4 个码）', m.cols.length === 4, m.cols.length)
    const sold = m.cols.map((c) => Number(c.val))
    r.ok('② 柱值是销量且降序 10/8/6/5', JSON.stringify(sold) === JSON.stringify([10, 8, 6, 5]), JSON.stringify(sold))
    r.ok('② 名次 = 1..4', JSON.stringify(m.cols.map((c) => c.no)) === JSON.stringify(['1', '2', '3', '4']),
      JSON.stringify(m.cols.map((c) => c.no)))
    r.ok('② 榜首是最贵的那件（耳机）', (m.cols[0].nm || '').indexOf('耳机') >= 0, m.cols[0].nm)

    const bots = m.cols.map((c) => c.barBot)
    const spread = Math.round((Math.max(...bots) - Math.min(...bots)) * 10) / 10
    r.ok('② 4 根柱共用一条 0 基线（barBot 极差 ≤ 1px）', spread <= 1, 'spread=' + spread + ' bots=' + JSON.stringify(bots))
    r.ok('② 基线与 .chart::after 重合（≤1.5px）', Math.abs(bots[0] - m.baseAbs) <= 1.5,
      'barBot=' + bots[0] + ' pseudoTop=' + m.baseAbs + ' pseudoH=' + m.baseH)

    const heightsOK = m.cols.every((c) => Math.abs(c.barH - H[Number(c.val)]) <= 1.5)
    r.ok('② 柱高 = round(sold/10*60)% × 340px（同一比例尺）', heightsOK,
      m.cols.map((c) => c.nm + ':' + c.barH).join(' | ') + '  期望 ' + JSON.stringify(H))
    r.ok('② 柱高严格降序', m.cols.every((c, i) => i === 0 || c.barH < m.cols[i - 1].barH),
      JSON.stringify(m.cols.map((c) => c.barH)))

    r.ok('② 柱顶圆角 4px、柱底方角 0px',
      m.cols.every((c) => c.radiusTop === '4px' && c.radiusBot === '0px'),
      JSON.stringify(m.cols.map((c) => c.radiusTop + ' / ' + c.radiusBot)))

    r.ok('② 自下而上 = 柱 → 圆图 → 数值（不重叠）',
      m.cols.every((c) => c.valBot <= c.capTop + 1 && c.capBot <= c.barTop + 1),
      JSON.stringify(m.cols.map((c) => [c.barTop, c.capBot, c.capTop, c.valBot])))
    r.ok('② 商品图是正圆（宽 == 高）', m.cols.every((c) => Math.abs(c.capW - c.capH) <= 0.5),
      JSON.stringify(m.cols.map((c) => c.capW + 'x' + c.capH)))
    r.ok('② 圆图直径 == 柱宽（圆头而不是浮在旁边）', m.cols.every((c) => Math.abs(c.capW - c.barW) <= 0.5),
      JSON.stringify(m.cols.map((c) => c.capW + ' vs ' + c.barW)))

    r.ok('② 仅榜首那根走 hero 色', m.cols[0].hero === true && m.cols.slice(1).every((c) => !c.hero),
      JSON.stringify(m.cols.map((c) => c.hero)))
    r.ok('② 非 hero 柱同色', new Set(m.cols.slice(1).map((c) => c.bg)).size === 1,
      JSON.stringify(m.cols.map((c) => c.bg)))

    r.ok('② 每列都是 <a> 且 href 带 7 个 query 参数',
      m.cols.every((c) => c.tag === 'A' && c.qn === 7),
      JSON.stringify(m.cols.map((c) => c.tag + ':' + c.qn)))
    const WANT_Q = ['Id', 'MerchantId', 'GoodName', 'Price', 'GoodImg', 'GoodType', 'ProductCode']
    r.ok('② 7 个参数名与 T-guard-6 ① 契约逐字相同',
      m.cols.every((c) => WANT_Q.every((k) => c.qKeys.indexOf(k) >= 0)), JSON.stringify(m.cols[0].qKeys))
    r.ok('② 链接指向本店', m.cols.every((c) => c.qMerchant === SHOP_ID), JSON.stringify(m.cols.map((c) => c.qMerchant)))

    r.ok('③ 折扣块占位 = 「本店暂时没有折扣商品」', m.ph.discount === '本店暂时没有折扣商品', m.ph.discount)
    r.ok('③ 折扣块 0 张卡（占位不盖在数据上）', m.discountCards === 0, m.discountCards)
    // ⚠️ 这一格用**前缀**判而不是全等：那句占位文字后面紧跟着一个真出口
    //    `<router-link>去登录</router-link>`，而 `innerText` 会把它的文字一起带出来
    //    ⇒ 全等永远差一个「去登录」。（第一版就是这么写错的，41 项里红了 1 项。）
    r.ok('③ 券块占位以「登录后可领取本店优惠券」开头',
      (m.ph.coupon || '').indexOf('登录后可领取本店优惠券') === 0, m.ph.coupon)
    r.ok('③ 券块占位不提「暂无 / 没有」这类词（不声称本店有没有券）',
      (m.ph.coupon || '').indexOf('暂无') < 0 && (m.ph.coupon || '').indexOf('没有') < 0, m.ph.coupon)
    r.ok('③ 券块占位带「去登录」真出口', m.couponLoginLink === true, m.couponLoginLink)
    r.ok('③ 热销有数据 ⇒ 不带占位句', m.ph.hot === null, m.ph.hot)
    r.ok('③ 猜你喜欢有数据 ⇒ 不带占位句', m.ph.rec === null, m.ph.rec)
    r.ok('③ 本店商品有数据 ⇒ 不带占位句', m.ph.goods === null, m.ph.goods)

    r.ok('④ 新品红签恰好 1 枚（min(3, ⌊5/3⌋)）', m.tagCount === 1, m.tagCount + ' ' + JSON.stringify(m.tagTexts))
    r.ok('④ 签上写「新品」', m.tagTexts[0] === '新品', m.tagTexts[0])
    r.ok('④ 签只落在「本店商品」网格里', JSON.stringify(m.tagSections) === JSON.stringify(['本店商品']),
      JSON.stringify(m.tagSections))
    r.ok('④ 签的那张卡确实在网格中', !!m.tagNames[0] && m.cardNames.indexOf(m.tagNames[0]) >= 0,
      '签在《' + m.tagNames[0] + '》 · 网格 ' + JSON.stringify(m.cardNames))

    r.ok('⑤ 事实行常驻', m.factsRowPresent === true && (m.facts.length > 0 || m.factNone === true),
      JSON.stringify(m.facts) + ' none=' + m.factNone)
    r.ok('⑤ 累计售出 29 件（去重后 10+6+5+8）', m.facts.join('|').indexOf('29') >= 0, JSON.stringify(m.facts))
    r.ok('⑤ 认证徽章仍 0 枚（库里没有 approved）', m.certCount === 0, m.certCount)

    r.ok('⑥ 不横向溢出', m.scrollW <= m.innerW + 1, 'scrollW=' + m.scrollW + ' innerW=' + m.innerW)

    bad += r.dump()
    await b.shot(EV + 'b5g-shophome-chart.png')
    await b.shotFull(EV + 'b5g-shophome-chart-full.png')

    // ── 第二遍：种登录名后 reload（券块走「已登录」那一支）────────────────────────────
    // ⚠️ 必须 `location.reload()`：`Page.navigate` 到**同一条带 hash 的 URL** 不重挂组件
    //    （本仓已登记的那条坑）。用 setTimeout 让 evaluate 先返回再导航。
    await b.evalJS("sessionStorage.setItem('loginUsername','123'); setTimeout(function(){ location.reload(); }, 60); 'queued'")
    await sleep(4000)
    const okMount2 = await waitFor(b, '.chart')
    if (!okMount2) throw new Error('reload 后页面没挂上 `.chart`')
    await sleep(1200)
    const m2 = JSON.parse(await b.evalJS(MEASURE))
    const r2 = report('c3b5g-shophome-chart · 种 loginUsername=123 后')
    r2.ok('⑦ 券块不再是「登录后…」那一支',
      (m2.ph.coupon || '').indexOf('登录后可领取') < 0, m2.ph.coupon)
    r2.ok('⑦ 券块二选一：有券卡 或 「本店暂时没有可领的优惠券」',
      m2.couponCards > 0 || m2.ph.coupon === '本店暂时没有可领的优惠券',
      'cards=' + m2.couponCards + ' ph=' + m2.ph.coupon)
    r2.ok('⑦ 版面顺序不受登录态影响', JSON.stringify(m2.heads) === JSON.stringify(m.heads), JSON.stringify(m2.heads))
    r2.ok('⑦ 柱状图不受登录态影响（4 根 / 等高）',
      m2.cols.length === 4 && m2.cols[0].barH === m.cols[0].barH,
      m2.cols.length + ' / ' + (m2.cols[0] || {}).barH)
    r2.ok('⑦ 新品红签仍恰好 1 枚', m2.tagCount === 1, m2.tagCount)
    bad += r2.dump()
    await b.shot(EV + 'b5g-shophome-chart-loggedin.png')
  } finally {
    await b.close()
  }
  console.log('\nEXIT=' + bad)
  process.exit(bad === 0 ? 0 : 1)
}

run().catch((e) => { console.error('探针失败', e); process.exit(1) })
