// probe-b5c-shophome.mjs —— C3 块 5 · B 组 · commit C 的页面探针（2026-10-12）。
//
// 查的是**新建的店铺主页** `views/ShopHome.vue`，外加「进店逛逛」那条入口。
// 后端**不参与**：`_cdp.mjs` 的 mock 在页面内拦截 `*8088*`，所以**只需要前端 dev server（8080）**。
//   ⇒ 唯一一处例外是「载入态」那一节：它**故意不 mock**，让 `goodsSelectHome` 真走一趟网络
//     （只有真往返才抓得住那一帧 —— mock 是就地兑现的，请求与响应之间没有时间窗）。
//     即便如此那一帧也可能短到抓不到，所以那一节另外用 CDP **人为加了 900ms 网络延迟**。
//
// 用法：node docs/美化/probe-b5c-shophome.mjs
//
// ⚠️ 本文件里的 `MEASURE` 是**一整段模板字符串**（交给 `bb.evalJS` 执行）——
//    它内部**不能出现反引号，也不能出现 `${`**，否则模板提前闭合、报出来的错还指向注释行。
//    （这个坑在本仓踩过三次；长说明一律挪到 `MEASURE` 外面或本文件头部。）
import { open, sleep, report } from './_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'
const SHOP = '5N1k5K2guh'        // 声谷（本店）
const OTHER = 'ZZother1234'      // 别家店 —— 用来证明「过滤真的发生了」
const CUSTOMER = 'uDopHnCeLS'

const IMG = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='200' height='200' fill='%23cdd8d4'/><rect x='40' y='60' width='120' height='80' fill='%23a9bdb6'/></svg>"
const IMG2 = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='200' height='200' fill='%23d6d2c8'/><circle cx='100' cy='100' r='54' fill='%23b8ae99'/></svg>"

const SHOP_GOODS = [
  { Id: 'g1', MerchantId: SHOP, GoodName: '声谷 Lite 无线耳机', GoodType: '数码配件', GoodImg: IMG, Price: 89, UserName: '声谷' },
  { Id: 'g2', MerchantId: SHOP, GoodName: '声谷 Pro 降噪耳机', GoodType: '数码配件', GoodImg: IMG2, Price: 249, UserName: '声谷' },
  { Id: 'g3', MerchantId: SHOP, GoodName: '声谷 空气传导耳机', GoodType: '数码配件', GoodImg: IMG, Price: 199, UserName: '声谷' },
  { Id: 'g9', MerchantId: OTHER, GoodName: '别家店的蓝牙音箱', GoodType: '数码配件', GoodImg: IMG2, Price: 129, UserName: '别家' },
  { Id: 'g8', MerchantId: OTHER, GoodName: '别家店的数据线', GoodType: '数码配件', GoodImg: IMG, Price: 19, UserName: '别家' }
]
const SHOP_NAMES = SHOP_GOODS.filter((g) => g.MerchantId === SHOP).map((g) => g.GoodName)
const OTHER_NAMES = SHOP_GOODS.filter((g) => g.MerchantId === OTHER).map((g) => g.GoodName)

/** 三个身份档的 `/user/me` 应答（与 `sessionStorage` 保持一致，免得「恢复态」与「存储」打架）。 */
const ME_ANON = { success: false, msg: '未登录' }
const ME_BUYER = { success: true, msg: 'ok', role: 1, username: '1111', customerid: CUSTOMER, merchantid: '', usertype: '客户' }
const ME_MERCHANT = { success: true, msg: 'ok', role: 2, username: '2222', customerid: '', merchantid: SHOP, usertype: '商家' }

/** 造一份 mock。`S` 是可变的后端状态、`calls` 数请求 —— 两者都由调用方持有，便于逐档重置。 */
function makeMock(meBody, S, calls, goodsBody) {
  return (url) => {
    if (url.includes('/user/me')) return { body: JSON.stringify(meBody) }
    if (url.includes('/user/token')) return { body: JSON.stringify({ success: true, msg: 'ok', token: 't' }) }
    if (url.includes('/goods/goodsSelectHome')) {
      calls.goods++
      return { body: JSON.stringify(goodsBody || { success: true, list: SHOP_GOODS }) }
    }
    // 商品详情页（第 ⑦ 节）要用的两条 —— 排在通配兜底之前。
    if (/GoodsProductCodeSelect/i.test(url)) return { body: JSON.stringify({ success: true, list: ['P1'] }) }
    if (url.includes('/goods/productCodePrice')) return { body: JSON.stringify({ success: true, price: 89, goodImg: IMG }) }
    // ⚠️ 顺序要紧：`/shop/followState` **包含** `/shop/follow` 这个子串 ⇒ 它必须排前面，
    //    否则一次回读会被记成一次关注（这个坑就埋在这一行，别调顺序）。
    if (url.includes('/shop/followState')) { calls.state++; return { body: JSON.stringify({ success: true, msg: 'ok', following: S.following, followers: S.followers }) } }
    if (url.includes('/shop/unfollow')) { calls.unfollow++; S.following = false; S.followers = S.followers - 1; return { body: JSON.stringify({ success: true, msg: '已取消关注', following: false, followers: S.followers }) } }
    if (url.includes('/shop/follow')) { calls.follow++; S.following = true; S.followers = S.followers + 1; return { body: JSON.stringify({ success: true, msg: '已关注', following: true, followers: S.followers }) } }
    if (url.includes('/message/send')) { calls.send++; return { body: JSON.stringify({ success: true, msg: 'ok' }) } }
    return { body: JSON.stringify({ success: true, list: [], total: 0, limit: 0 }) }
  }
}

// ⚠️ 这段是**模板字符串**：注释里不要写反引号、不要写 `${`（见文件头）。
const MEASURE = `(() => {
  const cs = (el) => el ? getComputedStyle(el) : null
  const t = (el) => el ? el.textContent.replace(/\\s+/g, ' ').trim() : null
  const band = document.querySelector('.band')
  const mark = document.querySelector('.hd-mark')
  const nm = document.querySelector('.hd-nm')
  const sub = document.querySelector('.hd-sub')
  const ops = [...document.querySelectorAll('.hd-ops button')]
  const cards = [...document.querySelectorAll('.cards .card')]
  const ph = document.querySelector('.cards .card .ph')
  const pr = document.querySelector('.cards .card .pr')
  const note = document.querySelector('.note')
  const empty = document.querySelector('.note-empty .retry')
  return {
    hasPage: !!document.querySelector('.shop-page'),
    hash: location.hash,
    bandBg: band ? cs(band).backgroundColor : null,
    bandRadius: band ? cs(band).borderRadius : null,
    bandShadow: band ? cs(band).boxShadow : null,
    bandW: band ? +band.getBoundingClientRect().width.toFixed(1) : null,
    viewW: innerWidth,
    markBg: mark ? cs(mark).backgroundColor : null,
    markText: t(mark),
    nmText: t(nm),
    nmFont: nm ? cs(nm).fontFamily : null,
    nmLh: nm ? cs(nm).lineHeight : null,
    subText: t(sub),
    ops: ops.map((b) => ({ t: t(b), bg: cs(b).backgroundColor, bd: cs(b).borderTopColor, color: cs(b).color, shadow: cs(b).boxShadow, dis: !!b.disabled })),
    cardCount: cards.length,
    cardNames: cards.map((c) => t(c.querySelector('.nm'))),
    phBorder: ph ? cs(ph).borderTopWidth + ' ' + cs(ph).borderTopStyle + ' ' + cs(ph).borderTopColor : null,
    phRadius: ph ? cs(ph).borderRadius : null,
    prColor: pr ? cs(pr).color : null,
    secH: t(document.querySelector('.sec-h')),
    note: t(note),
    noteErr: !!(note && note.classList.contains('note-err')),
    emptyLink: t(empty),
    bodyShadow: [...document.querySelectorAll('.shop-page *')].filter((e) => cs(e).boxShadow !== 'none').length,
    alerts: window.__alerts || []
  }
})()`

const R = report('probe-b5c-shophome')
const bad = []
const ck = (l, c, g) => { R.ok(l, c, g); if (!c) bad.push(l) }
const newCalls = () => ({ goods: 0, state: 0, follow: 0, unfollow: 0, send: 0 })

/** 起一张页：设好 `sessionStorage` 身份 → 落到 `#/shop?...`。 */
async function openShop({ me, S, calls, goodsBody, merchantId = SHOP, wait = 2600 }) {
  const bb = await open({ w: 1440, h: 900, headed: false, mock: makeMock(me, S, calls, goodsBody) })
  await bb.goto('http://localhost:8080/', 2400)
  await bb.evalJS(`(() => { sessionStorage.clear();
    sessionStorage.setItem('loginUsername', ${JSON.stringify(String(me.username || ''))});
    sessionStorage.setItem('customerid', ${JSON.stringify(String(me.customerid || ''))});
    sessionStorage.setItem('merchantid', ${JSON.stringify(String(me.merchantid || ''))});
    sessionStorage.setItem('role', ${JSON.stringify(String(me.role || ''))});
    window.__alerts = []; window.alert = (m) => { window.__alerts.push(String(m)) };
    return 1 })()`)
  await bb.goto(`http://localhost:8080/#/shop?MerchantId=${merchantId}`, wait)
  await sleep(600)
  return bb
}

// ══════════════════════════════════════════════════════════════════════════════════════════
// ① 买家视角（已登录客户）—— 过滤 / 头卡 / 关注 · 取关 · 回读
// ══════════════════════════════════════════════════════════════════════════════════════════
{
  const S = { following: false, followers: 7 }
  const calls = newCalls()
  const bb = await openShop({ me: ME_BUYER, S, calls })
  try {
    let m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c-shophome-buyer.png')

    ck('页面挂上了（.shop-page 在）', m.hasPage, m.hasPage)
    ck('头卡是**整幅横带**：底色 == --zone (242,246,247)', m.bandBg === 'rgb(242, 246, 247)', m.bandBg)
    ck('头卡**不是卡片**：圆角 0px', m.bandRadius === '0px', m.bandRadius)
    ck('头卡**不是卡片**：无阴影', m.bandShadow === 'none', m.bandShadow)
    ck('横带**通栏**（宽 == 视口宽）', m.bandW === m.viewW, [m.bandW, m.viewW])
    ck('字母圆牌底色 == --brand-soft (212,248,235)', m.markBg === 'rgb(212, 248, 235)', m.markBg)
    ck('字母圆牌取店名首字「声」', m.markText === '声', m.markText)
    ck('店名 == 声谷小店', m.nmText === '声谷小店', m.nmText)
    ck('店名用 display 字族（非正文族）', /LXGW|Kai/i.test(m.nmFont || ''), m.nmFont)
    ck('店名有**显式** line-height（LXGW 未注册，不给就与旁边差 3px）', m.nmLh !== 'normal', m.nmLh)
    ck('计数带量词：含「3 件商品」', (m.subText || '').includes('3 件商品'), m.subText)
    ck('已登录 ⇒ 粉丝数**显出来**：含「7 位粉丝」', (m.subText || '').includes('7 位粉丝'), m.subText)
    ck('⚠️ 计数不拼「·」（skill 点名的 AI 味）', !(m.subText || '').includes('·'), m.subText)

    ck('商品格**只剩本店 3 件**（全量 5 件、别家 2 件被滤掉）', m.cardCount === 3, m.cardCount)
    ck('3 件全是本店的', m.cardNames.every((n) => SHOP_NAMES.includes(n)), m.cardNames)
    ck('别家的**一件都没漏进来**', !m.cardNames.some((n) => OTHER_NAMES.includes(n)), m.cardNames)
    ck('卡片图框 == 1px --line (221,226,229)（与 ProductPage 同一套）', m.phBorder === '1px solid rgb(221, 226, 229)', m.phBorder)
    ck('卡片圆角 == --r2 (8px)', m.phRadius === '8px', m.phRadius)
    ck('价格色 == --accent (178,66,47)', m.prColor === 'rgb(178, 66, 47)', m.prColor)
    ck('区块标题 == 本店商品（真标题，不是 eyebrow）', m.secH === '本店商品', m.secH)
    ck('全页零 box-shadow（只用发丝线）', m.bodyShadow === 0, m.bodyShadow)

    ck('头卡两颗按钮：关注 + 客服', m.ops.length === 2, m.ops.map((o) => o.t))
    ck('未关注 ⇒ 「关注」', m.ops[0] && m.ops[0].t === '关注', m.ops[0] && m.ops[0].t)
    ck('未关注 ⇒ **不是实心**（无底色）', m.ops[0] && m.ops[0].bg === 'rgba(0, 0, 0, 0)', m.ops[0] && m.ops[0].bg)
    ck('未关注 ⇒ 描边 == --line-2 (208,214,217)', m.ops[0] && m.ops[0].bd === 'rgb(208, 214, 217)', m.ops[0] && m.ops[0].bd)
    ck('按钮上无阴影（全页零 box-shadow 的同一句）', m.ops.every((o) => o.shadow === 'none'), m.ops.map((o) => o.shadow))
    ck('第二颗 == 客服', m.ops[1] && m.ops[1].t === '客服', m.ops[1] && m.ops[1].t)

    await bb.evalJS(`document.querySelectorAll('.hd-ops button')[0].click(); 1`)
    await sleep(800)
    m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c-shophome-buyer-followed.png')
    ck('点关注 ⇒ 文案变「已关注」', m.ops[0] && m.ops[0].t === '已关注', m.ops[0] && m.ops[0].t)
    ck('点关注 ⇒ 变**实心** --brand (59,121,102)', m.ops[0] && m.ops[0].bg === 'rgb(59, 121, 102)', m.ops[0] && m.ops[0].bg)
    ck('点关注 ⇒ 字色白', m.ops[0] && m.ops[0].color === 'rgb(255, 255, 255)', m.ops[0] && m.ops[0].color)
    ck('粉丝数**回读**成 8（不是本地 +1 猜的）', (m.subText || '').includes('8 位粉丝'), m.subText)
    ck('发了一次 /shop/follow', calls.follow === 1, calls.follow)
    ck('未登录提示**没被触发**', (m.alerts || []).length === 0, m.alerts)

    await bb.evalJS(`document.querySelectorAll('.hd-ops button')[0].click(); 1`)
    await sleep(800)
    m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c-shophome-buyer-unfollowed.png')
    ck('再点 ⇒ 回到「关注」', m.ops[0] && m.ops[0].t === '关注', m.ops[0] && m.ops[0].t)
    ck('再点 ⇒ 粉丝数回落到 7', (m.subText || '').includes('7 位粉丝'), m.subText)
    ck('发了一次 /shop/unfollow', calls.unfollow === 1, calls.unfollow)
    ck('关注状态**回读过**（followState 至少 1 次）', calls.state >= 1, calls.state)

    await bb.evalJS(`document.querySelectorAll('.hd-ops button')[1].click(); 1`)
    await sleep(1000)
    const after = await bb.evalJS(`({ hash: location.hash, alerts: window.__alerts })`)
    ck('点客服 ⇒ 发了 /message/send', calls.send === 1, calls.send)
    // ⚠️ 落点是 `/customer/msg`，**不是** `?page=chat`：`router/index.js:157` 的 `LEGACY_TAB`
    //    在 `beforeEach` 里把 `{page:'chat'}` 换成路径并带走其余 query（与 ProductPage 逐字同路）。
    ck('点客服 ⇒ 落到消息页 /customer/msg', after.hash.startsWith('#/customer/msg'), after.hash)
    // ⚠️ 而且 URL 上**不该**留 `otherId` 残迹 —— 它被 ChatRoom 认掉、开完会话就 `router.replace`
    //    清走了（`ChatRoom.vue:444`）。「参数没了」在这里恰恰是「会话真开了」的证据。
    ck('点客服 ⇒ 会话参数被 ChatRoom 消费掉（URL 不留 otherId）', !after.hash.includes('otherId'), after.hash)
    ck('点客服 ⇒ 没弹登录提示（已登录）', after.alerts.length === 0, after.alerts)
  } finally { await bb.close() }
}

// ══════════════════════════════════════════════════════════════════════════════════════════
// ② 未登录 —— 关注与客服各自都要有出口（照 `consultProduct` 的既有形态：先说一句、再送登录页）
// ══════════════════════════════════════════════════════════════════════════════════════════
{
  const S = { following: false, followers: 7 }
  const calls = newCalls()
  const bb = await openShop({ me: ME_ANON, S, calls })
  try {
    let m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c-shophome-anon.png')
    ck('未登录也**进得来**（裸页 / meta.public）', m.hasPage, m.hasPage)
    ck('未登录：商品照常铺出来（3 件）', m.cardCount === 3, m.cardCount)
    ck('未登录：**不请求** followState（三条端点都要 CustomerId，白跑）', calls.state === 0, calls.state)
    ck('未登录：粉丝数那一段**不渲染**（而不是显示 0）', (m.subText || '').includes('3 件商品') && !(m.subText || '').includes('粉丝'), m.subText)
    ck('未登录：关注按钮**照画**（它是入口，不是状态）', m.ops.length === 2, m.ops.map((o) => o.t))

    await bb.evalJS(`document.querySelectorAll('.hd-ops button')[0].click(); 1`)
    await sleep(600)
    m = await bb.evalJS(MEASURE)
    let here = await bb.evalJS(`location.hash`)
    ck('未登录点关注 ⇒ alert「请先登录」', (m.alerts || []).includes('请先登录'), m.alerts)
    ck('未登录点关注 ⇒ **一个请求都没发**', calls.follow === 0, calls.follow)
    ck('未登录点关注 ⇒ 送到登录页（有出口，不是干瞪眼）', here.includes('/login'), here)

    // 回到店铺页（SPA 同文档导航 ⇒ alert 钩子还在，但重装一遍更稳），再试客服那颗。
    await bb.goto(`http://localhost:8080/#/shop?MerchantId=${SHOP}`, 2400)
    await bb.evalJS(`window.__alerts = []; window.alert = (m) => { window.__alerts.push(String(m)) }; 1`)
    await bb.evalJS(`document.querySelectorAll('.hd-ops button')[1].click(); 1`)
    await sleep(600)
    m = await bb.evalJS(MEASURE)
    here = await bb.evalJS(`location.hash`)
    ck('未登录点客服 ⇒ alert「请先登录」', (m.alerts || []).includes('请先登录'), m.alerts)
    ck('未登录点客服 ⇒ **一个请求都没发**', calls.send === 0, calls.send)
    ck('未登录点客服 ⇒ 也送到登录页', here.includes('/login'), here)
  } finally { await bb.close() }
}

// ══════════════════════════════════════════════════════════════════════════════════════════
// ③ 商家看**自己的店** —— 关注按钮隐藏，粉丝数照显（后端 `followState` 刻意不判 403）
// ══════════════════════════════════════════════════════════════════════════════════════════
{
  const S = { following: false, followers: 23 }
  const calls = newCalls()
  const bb = await openShop({ me: ME_MERCHANT, S, calls })
  try {
    const m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c-shophome-self.png')
    ck('自己的店：**不画关注按钮**（只剩客服）', m.ops.length === 1 && m.ops[0].t === '客服', m.ops.map((o) => o.t))
    ck('自己的店：**粉丝数照显**（23 位粉丝）', (m.subText || '').includes('23 位粉丝'), m.subText)
    ck('自己的店：followState **照发**（后端为此刻意没在它上面加 403 闸）', calls.state >= 1, calls.state)
    ck('自己的店：商品照铺（3 件）', m.cardCount === 3, m.cardCount)
  } finally { await bb.close() }
}

// ══════════════════════════════════════════════════════════════════════════════════════════
// ④ 空态（这家店一件商品都没有）
// ══════════════════════════════════════════════════════════════════════════════════════════
{
  const S = { following: false, followers: 0 }
  const calls = newCalls()
  const bb = await openShop({ me: ME_BUYER, S, calls, goodsBody: { success: true, list: SHOP_GOODS.filter((g) => g.MerchantId === OTHER) } })
  try {
    const m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c-shophome-empty.png')
    ck('空态：文案说清「还没有上架商品」', (m.note || '').includes('这家店还没有上架商品'), m.note)
    ck('空态：**没有**商品格', m.cardCount === 0, m.cardCount)
    ck('空态：店名退化成「店铺」而不是编一个', m.nmText === '店铺', m.nmText)
    ck('空态：圆牌退化成「商」', m.markText === '商', m.markText)
    ck('空态：给了**真能走的去处**（一只可点的链接）', m.emptyLink === '先去逛逛别的', m.emptyLink)
  } finally { await bb.close() }
}

// ══════════════════════════════════════════════════════════════════════════════════════════
// ⑤ 报错态（后端答 success:false）—— 说清是什么没成，并把重试放在手边
// ══════════════════════════════════════════════════════════════════════════════════════════
{
  const S = { following: false, followers: 0 }
  const calls = newCalls()
  const bb = await openShop({ me: ME_BUYER, S, calls, goodsBody: { success: false, msg: '库连不上，炸了' } })
  try {
    const m = await bb.evalJS(MEASURE)
    await bb.shot(EV + 'out-b5c-shophome-error.png')
    ck('报错态：把后端 msg **原样**带出来（不换一句含糊话）', (m.note || '').includes('库连不上，炸了'), m.note)
    ck('报错态：用了 .note-err（危险色）', m.noteErr, m.noteErr)
    ck('报错态：没有商品格', m.cardCount === 0, m.cardCount)
    ck('报错态：跟着一颗「重试」', (m.note || '').includes('重试'), m.note)
  } finally { await bb.close() }
}

// ══════════════════════════════════════════════════════════════════════════════════════════
// ⑥ 载入态 —— **不 mock**（真走 8088），另外人为加 900ms 延迟，才抓得住那一帧
// ══════════════════════════════════════════════════════════════════════════════════════════
{
  const bb = await open({ w: 1440, h: 900, headed: false })
  try {
    await bb.goto('http://localhost:8080/', 2600)
    await bb.send('Network.enable')
    await bb.send('Network.emulateNetworkConditions', { offline: false, latency: 900, downloadThroughput: -1, uploadThroughput: -1 })
    await bb.send('Runtime.evaluate', { expression: `location.hash = '#/shop?MerchantId=${SHOP}'` })
    // ⚠️ 轮询要**带 sleep**：延迟是加在**所有**请求上的 ⇒ 连 ShopHome 那个懒加载 chunk 都要等
    //    900ms 才到，组件在那之前根本没 mount、`.note` 是 `null`。首版不带 sleep 连打 30 次
    //    （几十毫秒跑完）⇒ 全落在 mount 之前，误报成「载入态不存在」。
    let hit = null
    for (let i = 0; i < 60; i++) {
      const s = await bb.evalJS(`(() => { const n = document.querySelector('.note'); return n ? n.textContent.replace(/\\s+/g, ' ').trim() : null })()`)
      if (s) { hit = s; break }
      await sleep(100)
    }
    await bb.shot(EV + 'out-b5c-shophome-loading.png')
    ck('载入态：那一帧的字是「正在载入店铺…」', (hit || '').includes('正在载入店铺'), hit)
    // 放掉延迟，等它落到有下文的状态（真后端在跑；有格子或有说明都算「有下文」）。
    await bb.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 })
    await sleep(2600)
    const after = await bb.evalJS(`(() => ({ note: (document.querySelector('.note') || {}).textContent || null, cards: document.querySelectorAll('.cards .card').length }))()`)
    ck('载入态**不是终态**：延迟放掉后页面有了下文', after.cards > 0 || !!after.note, after)
  } finally { await bb.close() }
}

// ══════════════════════════════════════════════════════════════════════════════════════════
// ⑦ 「进店逛逛」—— ProductPage 那颗按钮真能把人送到本页
// ══════════════════════════════════════════════════════════════════════════════════════════
{
  const S = { following: false, followers: 7 }
  const calls = newCalls()
  const bb = await open({ w: 1440, h: 900, headed: false, mock: makeMock(ME_BUYER, S, calls) })
  try {
    await bb.goto('http://localhost:8080/', 2600)
    await bb.evalJS(`(() => { window.__alerts = []; window.alert = (m) => { window.__alerts.push(String(m)) }; return 1 })()`)
    await bb.goto(`http://localhost:8080/#/product?MerchantId=${SHOP}&GoodName=${encodeURIComponent('声谷 Lite 无线耳机')}&GoodType=${encodeURIComponent('数码配件')}&Id=g1`, 3600)
    const before = await bb.evalJS(`(() => {
      const b = document.querySelectorAll('.shop .ops button')
      return { n: b.length, texts: [...b].map((x) => x.textContent.trim()), disabled: [...b].map((x) => !!x.disabled), title: b[1] ? b[1].title : null, shopNm: (document.querySelector('.shop .nm') || {}).textContent || null }
    })()`)
    await bb.shot(EV + 'out-b5c-productpage-enter.png')
    ck('ProductPage：`.shop` 块两颗按钮都在', before.n === 2, before.texts)
    ck('「进店逛逛」**不再是** disabled（原来那颗裸 disabled 已撤）', before.disabled[1] === false, before.disabled)
    ck('「进店逛逛」的 title 已改成真落点（不再写占位话）', !(before.title || '').includes('待 C3') && !(before.title || '').includes('超档'), before.title)

    await bb.evalJS(`document.querySelectorAll('.shop .ops button')[1].click(); 1`)
    await sleep(1500)
    const after = await bb.evalJS(`({ hash: location.hash, page: !!document.querySelector('.shop-page'), nm: (document.querySelector('.hd-nm') || {}).textContent || null })`)
    await bb.shot(EV + 'out-b5c-productpage-entered.png')
    ck('点「进店逛逛」⇒ 落在 /shop，且带对了商家号', after.hash.includes('/shop') && after.hash.includes(`MerchantId=${SHOP}`), after.hash)
    ck('落到的**就是**店铺主页（.shop-page 在）', after.page, after.page)
    ck('落到的店就是刚才那家（店名 == 声谷小店）', after.nm === '声谷小店', after.nm)
  } finally { await bb.close() }
}

const n = R.dump()
if (bad.length) console.log('\n失败项：\n - ' + bad.join('\n - '))
process.exit(n ? 1 : 0)
