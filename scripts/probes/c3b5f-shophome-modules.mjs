// c3b5f-shophome-modules.mjs —— C3 块 5「ShopHome 补八个模块」的**前端自检**（2026-10-12）。
//
// ⚠️ **本探针跑真后端**（与 `c3b5e-coupon-account.mjs` 那次 mock 相反）：本笔要验的三块
//    （`goodsSelectHome` 的 `SoldQty` 列 / 新公开端点 `/shop/profile` / 前端筛选与去重）
//    **全部是公开面**，a3 后端（8088）就在跑、库里就有真数据 ⇒ 没有伪造的必要，
//    也没有伪造的好处：真数据正是判据（同码重复行、原价全 0、13 家店只有 1 类目……）。
//
// 唯一**验不到**的是「本店优惠券」那一块 —— `/marketing/coupons` 要登录态（不在白名单），
// 而 a3 的闸是关的 ⇒ `getUsername()` 拿不到登录名 ⇒ `couponAccount` 为空 ⇒ 那一块
// **按设计不渲染**。故本探针钉的是**它的不渲染**，不是它渲染出来长什么样。
//
// 演示店 `dRD3gqtbFr`（登录名 `yunqi_digital`）2026-10-12 实测：
//   5 行商品（全「电子用品」）· 其中 `GD-101-9552` **同码两行**（Id=76 / Id=120，都 SoldQty=10）
//   · `OriginalPrice` 全 0 · `/shop/profile` 回 `CertStatus=""`、`RegisteredAt=2026-09-05 18:12:13`
//   ⇒ 期望：去重后累计销量 = 10+6+5+8 = **29**、开店 **1 个月**、**无**认证徽章、**无**折扣专区、
//     **无**分类 chip（只有一类）、热销前三 = 10 / 8 / 6。
//
// 用法（**前端 8080 + 后端 8088 都要活着**）：
//     node scripts/probes/c3b5f-shophome-modules.mjs
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const SHOP_ID = 'dRD3gqtbFr'

// ⚠️ 整段交给 evalJS —— 里面不能有反引号、不能有 ${（否则模板提前闭合，报错会指向注释行）。
const MEASURE = `(function(){
  var txt = function(e){ return e ? e.innerText.replace(/\\s+/g, ' ').trim() : null; };
  var blks = Array.prototype.slice.call(document.querySelectorAll('.wrap > section.blk'));
  var heads = blks.map(function(s){
    var h = s.querySelector('.sec-h');
    return h ? h.innerText.replace(/\\s+/g, ' ').trim() : '?';
  });
  var byHead = function(kw){
    for (var i = 0; i < blks.length; i++) { if (heads[i].indexOf(kw) >= 0) return blks[i]; }
    return null;
  };
  var cardsIn = function(s){ return s ? s.querySelectorAll('.pcard').length : -1; };
  var hot = byHead('本店热销');
  var rk = hot ? Array.prototype.map.call(hot.querySelectorAll('.rk'), function(e){ return e.innerText.trim(); }) : [];
  // 热销三张卡的**商品名**（去重判据：同款耳机只该出现一次）
  var hotNames = hot ? Array.prototype.map.call(hot.querySelectorAll('.pcard-name'), function(e){ return e.innerText.trim(); }) : [];
  var rec = byHead('猜你喜欢');
  // 猜你喜欢必须**全是别家**：卡上的 href 带 MerchantId，逐张读出来比对
  var recMerchants = rec ? Array.prototype.map.call(rec.querySelectorAll('.pcard'), function(a){
    var m = /MerchantId=([^&]*)/.exec(a.getAttribute('href') || '');
    return m ? decodeURIComponent(m[1]) : null;
  }) : [];
  var facts = Array.prototype.map.call(document.querySelectorAll('.facts .fact'), function(e){
    return e.innerText.replace(/\\s+/g, ' ').trim();
  });
  var newSec = byHead('新品首发');
  return JSON.stringify({
    heads: heads,
    bandName: txt(document.querySelector('.hd-nm')),
    bandSub: txt(document.querySelector('.hd-sub')),
    facts: facts,
    certCount: document.querySelectorAll('.fact-cert').length,
    chips: document.querySelectorAll('.chips .chip').length,
    gridCount: cardsIn(byHead('本店商品')),
    hotCount: cardsIn(hot),
    hotRk: rk,
    hotNames: hotNames,
    discountPresent: !!byHead('本店折扣'),
    newCount: cardsIn(newSec),
    recCount: cardsIn(rec),
    recMerchants: recMerchants,
    couponPresent: !!byHead('本店优惠券'),
    // 版面不变量：整页不该横向溢出（.cards = repeat(4,1fr) + .cpn 的 flex-basis 都可能顶破）
    scrollW: document.documentElement.scrollWidth,
    innerW: window.innerWidth,
    stateNote: txt(document.querySelector('.wrap .note'))
  });
})()`

// ── 第二遍：把登录态塞进 sessionStorage 再看一遍「本店优惠券」那一块 ────────────────────
// ⚠️ `getUsername()` 读的就是 `sessionStorage['username']`（`api/auth.js` 是全仓唯一允许碰它的地方）
//    ⇒ 直接种一个真值即等于「已登录」，**不用 mock**：a3 的闸是关的，`/marketing/coupons`
//    匿名也能打（这就是为什么这一遍还是能验**真数据**）。
// 客户 `123`（`customerid=pIzTWafaGp`）对本店实测能领 **1 张**：`CPf65c03d8-…`「探针商品券 GD-101-9552」，
// 满减 `threshold` / 面额 15 / 门槛 100 —— 期望卡面读作「¥15 ｜ 券名 ｜ 满减 · 满 100 可用 ｜ 领取」。
const MEASURE_COUPON = `(function(){
  var box = document.querySelector('.cpns');
  var first = box ? box.querySelector('.cpn') : null;
  var txt = function(e){ return e ? e.innerText.replace(/\\s+/g, ' ').trim() : null; };
  var blks = Array.prototype.slice.call(document.querySelectorAll('.wrap > section.blk'));
  var heads = blks.map(function(s){
    var h = s.querySelector('.sec-h');
    return h ? h.innerText.replace(/\\s+/g, ' ').trim() : '?';
  });
  return JSON.stringify({
    present: !!box,
    count: box ? box.querySelectorAll('.cpn').length : 0,
    face: txt(first ? first.querySelector('.cpn-face') : null),
    name: txt(first ? first.querySelector('.cpn-name') : null),
    cond: txt(first ? first.querySelector('.cpn-cond') : null),
    get: txt(first ? first.querySelector('.cpn-get') : null),
    firstHead: heads[0] || null,
    heads: heads
  });
})()`

const b = await open({ w: 1440, h: 1200 })
const R = report('c3b5f-shophome-modules')

try {
  await b.goto('http://localhost:8080/#/shop?MerchantId=' + SHOP_ID, 6000)
  await sleep(1500)
  const m = JSON.parse(await b.evalJS(MEASURE))
  await b.shot(EV + 'b5f-shophome-full.png')
  await b.shotFull(EV + 'b5f-shophome-fullpage.png')

  // ── ⓪ 头卡 ────────────────────────────────────────────────────────────────────
  R.ok('⓪ 店名来自 `/shop/profile` 的 UserName（新端点接了）',
    m.bandName === 'yunqi_digital小店', m.bandName)
  R.ok('⓪ 头卡计数在（5 件商品）', (m.bandSub || '').indexOf('5') >= 0, m.bandSub)

  // ── 头卡四事实项：三项显、徽章不显（CertStatus="" 不是 approved）────────────────
  R.ok('① 事实行**三项**：主营 / 开店 / 累计售出',
    m.facts.length === 3, m.facts)
  R.ok('① 主营 = 电子用品（本店 5 行全是这一类 ⇒ 众数没有歧义）',
    (m.facts[0] || '').indexOf('电子用品') >= 0, m.facts[0])
  R.ok('① 开店 = 1 个月（RegisteredAt 2026-09-05 → 今天 37 天 ⇒ 进「月」档）',
    (m.facts[1] || '').indexOf('1 个月') >= 0, m.facts[1])
  R.ok('① 累计售出 = 29 件（同码两行**只算一次**：10+6+5+8，按 ProductCode 去重）',
    (m.facts[2] || '').indexOf('29') >= 0, m.facts[2])
  R.ok('① 认证徽章**不显**（`CertStatus=""`，只有 approved 才画 —— 数据没有就不画）',
    m.certCount === 0, m.certCount)

  // ── ② 分类 chip：本店只有 1 类 ⇒ 整行不渲染 ────────────────────────────────────
  R.ok('② 分类 chip 行**不渲染**（本店 5 行同一类目，一个 chip 没有信息量）',
    m.chips === 0, m.chips)
  R.ok('② 本店商品网格 5 张卡（同码那两行**照实**都画 —— 去重只作用于热销与累计销量）',
    m.gridCount === 5, m.gridCount)

  // ── ③ 本店热销 ────────────────────────────────────────────────────────────────
  R.ok('③ 热销 3 张卡', m.hotCount === 3, m.hotCount)
  R.ok('③ 名次是 1 / 2 / 3', JSON.stringify(m.hotRk) === JSON.stringify(['1', '2', '3']), m.hotRk)
  R.ok('③ **同款耳机只出现一次**（`GD-101-9552` 同码两行已去重；不去重会占掉两个名次）',
    m.hotNames.filter((n) => n.indexOf('耳机') >= 0).length === 1, m.hotNames)
  R.ok('③ 第一名是销量 10 的耳机（降序生效）', (m.hotNames[0] || '').indexOf('耳机') >= 0, m.hotNames)

  // ── ④ 折扣专区：原价全 0 ⇒ 整块不渲染 ─────────────────────────────────────────
  R.ok('④ 折扣专区**不渲染**（全库 43 行 OriginalPrice 都是 0 —— 数据没有，不是代码没接）',
    m.discountPresent === false, m.discountPresent)

  // ── ⑤ 新品首发 ────────────────────────────────────────────────────────────────
  R.ok('⑤ 新品首发 4 张卡（`Id DESC` 取 4）', m.newCount === 4, m.newCount)

  // ── ⑥ 猜你喜欢：别家同类目 ────────────────────────────────────────────────────
  R.ok('⑥ 猜你喜欢 4 张卡（全量 12 件电子用品 − 本店 5 件 = 别家 7 件，取 4）',
    m.recCount === 4, m.recCount)
  R.ok('⑥ 猜你喜欢的 4 张**没有一张是本店**（`MerchantId` 逐张读出来比对）',
    m.recMerchants.length === 4 && m.recMerchants.every((x) => x && x !== SHOP_ID), m.recMerchants)

  // ── 优惠券块：未登录 ⇒ 按设计不渲染（不拿「暂无券」冒充）──────────────────────
  R.ok('⑦ 本店优惠券**不渲染**（未登录 ⇒ 一个请求都不发，也不画空态）',
    m.couponPresent === false, m.couponPresent)

  // ── 版面不变量 ────────────────────────────────────────────────────────────────
  R.ok('⑧ 整页无横向溢出（`.cards` 与 `.cpn` 的 flex-basis 都没顶破 1180px 的栅格）',
    m.scrollW <= m.innerW + 1, { scrollW: m.scrollW, innerW: m.innerW })
  R.ok('⑧ 页面上没有错误态（`.note-err` 不存在 ⇒ 商品列表拉到了）',
    m.stateNote === null || (m.stateNote || '').indexOf('没取到') < 0, m.stateNote)

  console.log('--- 版面 ---')
  console.log('  分区（自上而下）：' + JSON.stringify(m.heads))
  console.log('  头卡：' + m.bandName + ' ｜ ' + m.bandSub)
  console.log('  事实行：' + JSON.stringify(m.facts) + '  认证徽章数=' + m.certCount)
  console.log('  张数：本店商品=' + m.gridCount + ' 热销=' + m.hotCount + ' 新品=' + m.newCount
    + ' 猜你喜欢=' + m.recCount + ' 折扣=' + m.discountPresent + ' 券块=' + m.couponPresent)
  console.log('  热销名次=' + JSON.stringify(m.hotRk) + ' 名称=' + JSON.stringify(m.hotNames))
  console.log('  猜你喜欢的商家号=' + JSON.stringify(m.recMerchants))

  // ══ 第二遍：已登录（种 sessionStorage）⇒ 本店优惠券块应当出现 ══════════════════════
  // ⚠️ 必须 `location.reload()`，**不能**再 `goto` 同一个 URL —— 同一个带 hash 的地址
  //    在 CDP 那边算「同文档导航」，组件不会重新挂载 ⇒ `couponAccount` 还是空的那一份。
  await b.evalJS("sessionStorage.setItem('loginUsername','123'); sessionStorage.setItem('customerid','pIzTWafaGp'); location.reload(); 'ok'")
  await sleep(5000)
  const c = JSON.parse(await b.evalJS(MEASURE_COUPON))
  await b.shot(EV + 'b5f-shophome-coupon.png')

  R.ok('⑨ 已登录 ⇒ 本店优惠券块**出现了**', c.present === true, c)
  R.ok('⑨ 券块排在最前（`MerchantAccount` 筛本店 ⇒ 这一块回答的是「这家店有什么券」）',
    (c.firstHead || '').indexOf('本店优惠券') >= 0, c.heads)
  R.ok('⑨ 只列出本店那 1 张（不是全站 11 张）', c.count === 1, c.count)
  R.ok('⑨ 面额读作 ¥15（`couponFace` 走的是共用那份 `config/coupon-format.js`）',
    c.face === '¥15', c.face)
  R.ok('⑨ 门槛读作「满减 · 满 100 可用」（`couponLine` 合成，不重复说两遍类型）',
    c.cond === '满减 · 满 100 可用', c.cond)
  R.ok('⑨ 按钮初始是「领取」（我还没领过这张）', c.get === '领取', c.get)

  console.log('--- 已登录那一遍 ---')
  console.log('  分区：' + JSON.stringify(c.heads))
  console.log('  券卡：' + JSON.stringify({ face: c.face, name: c.name, cond: c.cond, get: c.get, n: c.count }))
} finally {
  const bad = R.dump()
  await b.close()
  process.exitCode = bad === 0 ? 0 : 1
}
