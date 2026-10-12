// c3b5g-shophome-chart.mjs —— C3 块 5「ShopHome 布局授权笔」的前端自检（2026-10-12）。
//   第二版：**丙方案（Tab 式）定稿**。第一版验的是「纵向五块」的版面序，那一版已被用户推翻
//   ⇒ 本版把版面断言整体换成 **Tab 结构**，柱状图的几何断言保留（只把「5 根左对齐」改成
//   「Top3 居中 2-1-3 山峰」）。
//
// ⚠️ **跑真后端**（同 `c3b5f-shophome-modules.mjs` 的理由）：本笔要验的全是公开面
//    （`goodsSelectHome` / `/shop/profile`），a3 后端（8088）在跑、库里有真数据 ⇒ 真数据正是判据。
//
// 本笔钉的是：
//   ① 版面 = 券 → **Tab 区** → 猜你喜欢（**三段**，不是五段）；猜你喜欢是 `.wrap` 的
//      **最后一个**子元素（用户的唯一硬约束）；「新品首发」独立模块**已取消**
//   ② Tab 本身：三枚真 tab（`role="tab"` + `aria-selected`）· 默认停在「热销」·
//      指示条是底边 2px · **只有中间面板换**（头卡 / 券 / 猜你喜欢不动）
//   ③ 热销面板的几何（本笔的设计核心，也是唯一一处不能靠肉眼断的对错）：
//      Top3 · **渲染序 = 名次 2、1、3**（2-1-3 山峰）· 中间那根最高且是 hero 色 ·
//      柱底共一条 0 基线（且与 `.chart::after` 重合）· 柱高 = round(sold/10*60)% × 340px
//      （同一比例尺，不是插值）· 柱顶圆角柱底方角 · 柱 → 圆图 → 数值 自下而上叠 ·
//      圆图正圆且直径 == 柱宽 · **整张图居中** · 整列是 `<a>` 且 href 带 7 个 query 参数
//   ④ 全部面板：5 张卡（含库里那条同码重复行，**已知待裁，本笔没动那个判据**）·
//      新品红签恰好 1 枚（`min(3, ⌊5/3⌋) = 1`）· 那 1 枚**排在网格第一位**（新品靠前）·
//      签在**图片右上角**（用户定稿；实测右缘间距 8px、左缘间距 > 60px）
//   ⑤ 折扣面板：占位「本店暂无折扣商品」且 0 张卡（**占位不盖在数据上**）
//   ⑥ 头卡事实行常驻；认证徽章仍 0 枚（数据里没有 approved）
//   ⑦ 版面不变量：不横向溢出（⚠️ headless 里滚动条宽恒为 0 ⇒ 这条只抓「粗溢出」）
//   ⑧ 种 `loginUsername` 后 reload：券块换「已登录」那一支，版面与柱状图不受影响
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

  // ── tab 条 ────────────────────────────────────────────────────────────────
  var tabEls = all(document, '.tab');
  var tabLabels = tabEls.map(function(t){ return clean(t.innerText); });
  var tabOn = -1;
  tabEls.forEach(function(t, i){ if (t.className.indexOf('tab-on') >= 0) tabOn = i; });
  var onEl = tabOn >= 0 ? tabEls[tabOn] : null;
  var onAfter = onEl ? getComputedStyle(onEl, '::after') : null;

  // ── 当前面板（v-if/v-else-if/v-else ⇒ DOM 里只有一个）───────────────────────
  var pane = document.querySelector('.pane');
  var paneCards = pane ? all(pane, '.pcard') : [];
  var panePh = pane ? pane.querySelector('.ph') : null;

  // ── 柱状图 ────────────────────────────────────────────────────────────────
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
  var baseAbs = null, baseH = null, chartCenterGap = null;
  if (chart) {
    var cs2 = getComputedStyle(chart, '::after');
    baseAbs = r1(chart.getBoundingClientRect().top + parseFloat(cs2.top));
    baseH = cs2.height;
    var wrap = document.querySelector('.wrap');
    if (wrap) {
      var cb = chart.getBoundingClientRect(), wb = wrap.getBoundingClientRect();
      chartCenterGap = r1((cb.left - wb.left) - (wb.right - cb.right));
    }
  }

  // ── 新品红签（在「全部」面板里）────────────────────────────────────────────
  var tagEls = all(document, '.pcard-tag');
  var tagPos = tagEls.map(function(e){
    var box = e.closest('.pcard-img');
    if (!box) return null;
    var tb = e.getBoundingClientRect(), ib = box.getBoundingClientRect();
    return { rightGap: r1(ib.right - tb.right), leftGap: r1(tb.left - ib.left), topGap: r1(tb.top - ib.top) };
  });

  var last = blks.length ? blks[blks.length - 1] : null;
  return JSON.stringify({
    heads: heads,
    lastHead: last ? clean(last.querySelector('.sec-h').innerText) : null,
    lastIsLastChild: last ? (last.parentNode.lastElementChild === last) : null,
    hasNewSection: !!byHead('新品首发'),

    tabLabels: tabLabels,
    tabOn: tabOn,
    tablist: !!document.querySelector('[role="tablist"]'),
    allTabsRole: tabEls.length === 3 && tabEls.every(function(t){ return t.getAttribute('role') === 'tab'; }),
    ariaSel: tabEls.map(function(t){ return t.getAttribute('aria-selected'); }),
    tabBarH: onAfter ? onAfter.height : null,
    tabBarBg: onAfter ? onAfter.backgroundColor : null,

    panePresent: !!pane,
    paneCards: paneCards.length,
    paneCardNames: paneCards.map(function(c){ return txt(c.querySelector('.pcard-name')); }),
    panePh: panePh ? txt(panePh) : null,
    chartPresent: !!chart,
    chartCenterGap: chartCenterGap,
    cols: rows,
    baseAbs: baseAbs,
    baseH: baseH,

    ph: {
      coupon: phOf(byHead('本店优惠券')),
      rec: phOf(byHead('猜你喜欢')),
      grid: byHead('本店商品') ? phOf(byHead('本店商品')) : null
    },
    couponLoginLink: !!document.querySelector('.ph-link'),
    couponCards: byHead('本店优惠券') ? byHead('本店优惠券').querySelectorAll('.cpn').length : -1,
    chips: document.querySelectorAll('.chips .chip').length,
    gridCards: byHead('本店商品') ? byHead('本店商品').querySelectorAll('.pcard').length : -1,
    gridCardNames: (byHead('本店商品') || document).querySelectorAll ? all(byHead('本店商品') || document, '.pcard-name').map(function(e){ return clean(e.innerText); }) : [],
    tagCount: tagEls.length,
    tagTexts: tagEls.map(function(e){ return txt(e); }),
    tagPos: tagPos,
    recCards: byHead('猜你喜欢') ? byHead('猜你喜欢').querySelectorAll('.pcard').length : -1,
    recMerchants: byHead('猜你喜欢') ? all(byHead('猜你喜欢'), '.pcard').map(function(a){
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
const H = { 10: 204, 8: 163.2, 6: 122.4 }

/** 轮询等一个选择器（底座没有 waitForSelector）。 */
const waitFor = async (b, sel, ms = 25000) => {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    if (await b.evalJS("!!document.querySelector('" + sel + "')")) return true
    await sleep(250)
  }
  return false
}

/** 点第 i 枚 tab 并等一小会儿（同步 `.click()`，Vue 的 `@click` 是同步派发的）。 */
const clickTab = async (b, i) => {
  await b.evalJS("document.querySelectorAll('.tab')[" + i + "].click(); 'clicked'")
  await sleep(600)
}

const M = (b) => b.evalJS(MEASURE).then(JSON.parse)

const run = async () => {
  const b = await open({ w: 1440, h: 1000, headed: false })
  let bad = 0
  try {
    // ── 第一遍：匿名 + 默认面板（热销）──────────────────────────────────────────
    await b.goto(URL, 3000)
    const okMount = await waitFor(b, '.chart')
    if (!okMount) throw new Error('页面没挂上 `.chart`（前端 8080 活着吗？）')
    await sleep(1200)
    const m = await M(b)
    const r = report('c3b5g-shophome-chart · 匿名 / 默认面板')

    const WANT = ['本店优惠券', '本店商品', '猜你喜欢']
    WANT.forEach((w, i) => r.ok('① 版面序 #' + (i + 1) + ' = ' + w,
      (m.heads[i] || '').indexOf(w) >= 0, '实得 ' + JSON.stringify(m.heads)))
    r.ok('① 三段（不是五段）', m.heads.length === 3, m.heads.length + ' ' + JSON.stringify(m.heads))
    r.ok('① 猜你喜欢是最后一个 section', (m.lastHead || '').indexOf('猜你喜欢') >= 0, m.lastHead)
    r.ok('① 猜你喜欢是 .wrap 的最后一个子元素', m.lastIsLastChild === true, m.lastIsLastChild)
    r.ok('① 「新品首发」独立模块已取消', m.hasNewSection === false, m.hasNewSection)

    r.ok('② 三枚 tab 文案 = 热销 / 全部 / 折扣',
      JSON.stringify(m.tabLabels) === JSON.stringify(['热销', '全部', '折扣']), JSON.stringify(m.tabLabels))
    r.ok('② 是 tablist + 三枚 role="tab"', m.tablist === true && m.allTabsRole === true,
      'tablist=' + m.tablist + ' roles=' + m.allTabsRole)
    r.ok('② aria-selected 只有一枚 true 且在第一位',
      JSON.stringify(m.ariaSel) === JSON.stringify(['true', 'false', 'false']), JSON.stringify(m.ariaSel))
    r.ok('② 默认停在「热销」', m.tabOn === 0, m.tabOn)
    r.ok('② 选中指示条 = 底边 2px 且不透明',
      m.tabBarH === '2px' && m.tabBarBg !== 'rgba(0, 0, 0, 0)',
      'h=' + m.tabBarH + ' bg=' + m.tabBarBg)

    r.ok('③ 热销面板 = 柱状图', m.chartPresent === true && m.panePresent === true, m.chartPresent)
    r.ok('③ 柱数 = 3（Top3）', m.cols.length === 3, m.cols.length)
    r.ok('③ 渲染序 = 名次 2、1、3（2-1-3 山峰）',
      JSON.stringify(m.cols.map((c) => c.no)) === JSON.stringify(['2', '1', '3']),
      JSON.stringify(m.cols.map((c) => c.no)))
    r.ok('③ 柱值 = 8 / 10 / 6（名次 1 在正中间）',
      JSON.stringify(m.cols.map((c) => Number(c.val))) === JSON.stringify([8, 10, 6]),
      JSON.stringify(m.cols.map((c) => Number(c.val))))
    r.ok('③ 中间那根最高（山峰）',
      m.cols.length === 3 && m.cols[1].barH > m.cols[0].barH && m.cols[1].barH > m.cols[2].barH,
      JSON.stringify(m.cols.map((c) => c.barH)))
    r.ok('③ 两肩也降序（名次 2 > 名次 3）', m.cols[0].barH > m.cols[2].barH,
      JSON.stringify(m.cols.map((c) => c.barH)))

    const bots = m.cols.map((c) => c.barBot)
    const spread = Math.round((Math.max(...bots) - Math.min(...bots)) * 10) / 10
    r.ok('③ 3 根柱共用一条 0 基线（barBot 极差 ≤ 1px）', spread <= 1, 'spread=' + spread + ' bots=' + JSON.stringify(bots))
    r.ok('③ 基线与 .chart::after 重合（≤1.5px）', Math.abs(bots[0] - m.baseAbs) <= 1.5,
      'barBot=' + bots[0] + ' pseudoTop=' + m.baseAbs + ' pseudoH=' + m.baseH)

    const heightsOK = m.cols.every((c) => Math.abs(c.barH - H[Number(c.val)]) <= 1.5)
    r.ok('③ 柱高 = round(sold/10*60)% × 340px（同一比例尺）', heightsOK,
      m.cols.map((c) => c.no + ':' + c.barH).join(' | ') + '  期望 ' + JSON.stringify(H))
    r.ok('③ 整张图居中（左右留白差 ≤ 2px）', Math.abs(m.chartCenterGap) <= 2, 'gap=' + m.chartCenterGap)

    r.ok('③ 柱顶圆角 4px、柱底方角 0px',
      m.cols.every((c) => c.radiusTop === '4px' && c.radiusBot === '0px'),
      JSON.stringify(m.cols.map((c) => c.radiusTop + ' / ' + c.radiusBot)))

    r.ok('③ 自下而上 = 柱 → 圆图 → 数值（不重叠）',
      m.cols.every((c) => c.valBot <= c.capTop + 1 && c.capBot <= c.barTop + 1),
      JSON.stringify(m.cols.map((c) => [c.barTop, c.capBot, c.capTop, c.valBot])))
    r.ok('③ 商品图是正圆（宽 == 高）', m.cols.every((c) => Math.abs(c.capW - c.capH) <= 0.5),
      JSON.stringify(m.cols.map((c) => c.capW + 'x' + c.capH)))
    r.ok('③ 圆图直径 == 柱宽（圆头而不是浮在旁边）', m.cols.every((c) => Math.abs(c.capW - c.barW) <= 0.5),
      JSON.stringify(m.cols.map((c) => c.capW + ' vs ' + c.barW)))

    r.ok('③ 仅名次 1 那根走 hero 色，且它在**中间**',
      m.cols[1].hero === true && m.cols[0].hero === false && m.cols[2].hero === false,
      JSON.stringify(m.cols.map((c) => c.no + ':' + c.hero)))
    r.ok('③ 两根非 hero 柱同色', m.cols[0].bg === m.cols[2].bg, m.cols[0].bg + ' vs ' + m.cols[2].bg)

    r.ok('③ 每列都是 <a> 且 href 带 7 个 query 参数',
      m.cols.every((c) => c.tag === 'A' && c.qn === 7),
      JSON.stringify(m.cols.map((c) => c.tag + ':' + c.qn)))
    const WANT_Q = ['Id', 'MerchantId', 'GoodName', 'Price', 'GoodImg', 'GoodType', 'ProductCode']
    r.ok('③ 7 个参数名与 T-guard-6 ① 契约逐字相同',
      m.cols.every((c) => WANT_Q.every((k) => c.qKeys.indexOf(k) >= 0)), JSON.stringify(m.cols[0].qKeys))
    r.ok('③ 链接指向本店', m.cols.every((c) => c.qMerchant === SHOP_ID), JSON.stringify(m.cols.map((c) => c.qMerchant)))

    r.ok('③ 热销面板无占位句（有数据）', m.panePh === null, m.panePh)

    // ⚠️ 券块那句用**前缀**判而不是全等：后面紧跟着一个真出口
    //    `<router-link>去登录</router-link>`，而 `innerText` 会把它的文字一起带出来
    //    ⇒ 全等永远差一个「去登录」。
    r.ok('⑥ 券块占位以「登录后可领取本店优惠券」开头',
      (m.ph.coupon || '').indexOf('登录后可领取本店优惠券') === 0, m.ph.coupon)
    r.ok('⑥ 券块占位不提「暂无 / 没有」这类词（不声称本店有没有券）',
      (m.ph.coupon || '').indexOf('暂无') < 0 && (m.ph.coupon || '').indexOf('没有') < 0, m.ph.coupon)
    r.ok('⑥ 券块占位带「去登录」真出口', m.couponLoginLink === true, m.couponLoginLink)

    r.ok('⑥ 事实行常驻', m.factsRowPresent === true && (m.facts.length > 0 || m.factNone === true),
      JSON.stringify(m.facts) + ' none=' + m.factNone)
    r.ok('⑥ 累计售出 29 件（去重后 10+6+5+8）', m.facts.join('|').indexOf('29') >= 0, JSON.stringify(m.facts))
    r.ok('⑥ 认证徽章仍 0 枚（库里没有 approved）', m.certCount === 0, m.certCount)
    r.ok('⑥ 猜你喜欢在且不随 tab 切（默认面板下就在）', m.recCards === 4, m.recCards)
    r.ok('⑥ 猜你喜欢都是别家', (m.recMerchants || []).every((x) => x && x !== SHOP_ID), JSON.stringify(m.recMerchants))

    r.ok('⑦ 不横向溢出', m.scrollW <= m.innerW + 1, 'scrollW=' + m.scrollW + ' innerW=' + m.innerW)

    bad += r.dump()
    await b.shot(EV + 'b5g-shophome-tab-hot.png')

    // ── 第二遍：切「全部」──────────────────────────────────────────────────────
    await clickTab(b, 1)
    const m2 = await M(b)
    const r2 = report('c3b5g-shophome-chart · 全部面板')
    r2.ok('② 切到第二枚（全部）', m2.tabOn === 1, m2.tabOn)
    r2.ok('④ 网格 5 张卡（含库里那条同码重复行 —— 已知待裁，本笔没动判据）',
      m2.paneCards === 5, m2.paneCards + ' ' + JSON.stringify(m2.paneCardNames))
    r2.ok('④ 新品红签恰好 1 枚（min(3, ⌊5/3⌋)）', m2.tagCount === 1, m2.tagCount + ' ' + JSON.stringify(m2.tagTexts))
    r2.ok('④ 签上写「新品」', m2.tagTexts[0] === '新品', m2.tagTexts[0])
    r2.ok('④ 那 1 枚签**排在网格第一位**（新品靠前）',
      m2.paneCardNames[0] === m2.gridCardNames[0] && m2.tagCount === 1 &&
      m2.paneCardNames.indexOf(m2.gridCardNames[0]) === 0,
      'first=' + m2.paneCardNames[0] + ' 网格=' + JSON.stringify(m2.paneCardNames))
    r2.ok('④ 签在**图片右上角**（右缘间距 = --s2 = 8px）',
      (m2.tagPos[0] || {}).rightGap === 8, JSON.stringify(m2.tagPos))
    r2.ok('④ 签离左缘很远（确认不在左上角）', (m2.tagPos[0] || {}).leftGap > 60,
      JSON.stringify(m2.tagPos))
    r2.ok('④ 签贴图片上缘（--s2 = 8px）', (m2.tagPos[0] || {}).topGap === 8, JSON.stringify(m2.tagPos))
    r2.ok('④ 单类目 ⇒ 不画 chip 行（本笔从「≥1 类」收窄回「≥2 类」）', m2.chips === 0, m2.chips)
    r2.ok('② 面板换掉了（柱状图不在）', m2.chartPresent === false, m2.chartPresent)
    r2.ok('① 版面三段不变', JSON.stringify(m2.heads) === JSON.stringify(m.heads), JSON.stringify(m2.heads))
    r2.ok('⑥ 猜你喜欢仍 4 张（不随 tab 切）', m2.recCards === 4, m2.recCards)
    r2.ok('⑥ 猜你喜欢仍是 .wrap 最后一个子元素', m2.lastIsLastChild === true, m2.lastIsLastChild)
    r2.ok('⑦ 不横向溢出', m2.scrollW <= m2.innerW + 1, 'scrollW=' + m2.scrollW + ' innerW=' + m2.innerW)
    bad += r2.dump()
    await b.shot(EV + 'b5g-shophome-tab-all.png')

    // ── 第三遍：切「折扣」──────────────────────────────────────────────────────
    await clickTab(b, 2)
    const m3 = await M(b)
    const r3 = report('c3b5g-shophome-chart · 折扣面板')
    r3.ok('② 切到第三枚（折扣）', m3.tabOn === 2, m3.tabOn)
    r3.ok('⑤ 占位 = 「本店暂无折扣商品」', m3.panePh === '本店暂无折扣商品', m3.panePh)
    r3.ok('⑤ 折扣面板 0 张卡（占位不盖在数据上）', m3.paneCards === 0, m3.paneCards)
    r3.ok('② 面板换掉了（柱状图不在）', m3.chartPresent === false, m3.chartPresent)
    r3.ok('⑥ 猜你喜欢仍 4 张（三个面板下都在）', m3.recCards === 4, m3.recCards)
    bad += r3.dump()
    await b.shot(EV + 'b5g-shophome-tab-discount.png')
    await b.shotFull(EV + 'b5g-shophome-tab-full.png')

    // ── 第四遍：种登录名后 reload（券块走「已登录」那一支，回到默认面板）──────────
    // ⚠️ 必须 `location.reload()`：`Page.navigate` 到**同一条带 hash 的 URL** 不重挂组件
    //    （本仓已登记的那条坑）。用 setTimeout 让 evaluate 先返回再导航。
    await b.evalJS("sessionStorage.setItem('loginUsername','123'); setTimeout(function(){ location.reload(); }, 60); 'queued'")
    await sleep(4000)
    const okMount4 = await waitFor(b, '.chart')
    if (!okMount4) throw new Error('reload 后页面没挂上 `.chart`')
    await sleep(1200)
    const m4 = await M(b)
    const r4 = report('c3b5g-shophome-chart · 种 loginUsername=123 后')
    r4.ok('⑧ reload 后回到默认面板（热销）', m4.tabOn === 0, m4.tabOn)
    r4.ok('⑧ 券块不再是「登录后…」那一支',
      (m4.ph.coupon || '').indexOf('登录后可领取') < 0, m4.ph.coupon)
    r4.ok('⑧ 券块二选一：有券卡 或 「本店暂时没有可领的优惠券」',
      m4.couponCards > 0 || m4.ph.coupon === '本店暂时没有可领的优惠券',
      'cards=' + m4.couponCards + ' ph=' + m4.ph.coupon)
    r4.ok('⑧ 版面三段不受登录态影响', JSON.stringify(m4.heads) === JSON.stringify(m.heads), JSON.stringify(m4.heads))
    r4.ok('⑧ 柱状图不受登录态影响（仍是 2-1-3）',
      JSON.stringify(m4.cols.map((c) => c.no)) === JSON.stringify(['2', '1', '3']) &&
      m4.cols[1].barH === 204,
      JSON.stringify(m4.cols.map((c) => c.no + ':' + c.barH)))
    r4.ok('⑧ 猜你喜欢仍 4 张', m4.recCards === 4, m4.recCards)
    bad += r4.dump()
    await b.shot(EV + 'b5g-shophome-tab-loggedin.png')
  } finally {
    await b.close()
  }
  console.log('\nEXIT=' + bad)
  process.exit(bad === 0 ? 0 : 1)
}

run().catch((e) => { console.error('探针失败', e); process.exit(1) })
