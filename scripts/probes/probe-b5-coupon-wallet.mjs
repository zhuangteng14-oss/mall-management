// probe-b5-coupon-wallet.mjs —— 探针（2026-10-11，C3 块 5「券的信息架构重排」**新增**）
//
// 问的是「**我的券包**搬进用户中心弹窗之后画成什么样」。要答的九件事：
//   ① 用户中心「优惠券」那一格变成**入口**：真券数 + 快过期红点；
//   ② 点开 = 弹窗（`.cscrim`），`z-index` 是既有的「弹窗叠弹窗」档 300；
//   ③ 4 个 tab `[可领] [未使用] [已使用] [已过期]`，默认落「可领」；
//   ④ 分组正确：未使用 = `expiring` ∪ `usable`（两个桶**同一件事的两档紧迫度**）、且快过期在最上；
//   ⑤ 「X 天后过期」**只**出现在 `expiring` 那档（其余档说了是噪音）；
//   ⑥ 点一行展开详情（面额/门槛/适用范围/有效期/券编号）、再点收起；
//   ⑦ 「可领」tab 的「领取」按钮：领完那张变「已领取」、按钮消失；
//   ⑧ 各 tab 空态（四条文案各不同，「已使用」那条要说明**为什么**空 —— 核销在结算，块 5 commit 5）；
//   ⑨ 点遮罩关窗。
//
// 做法：`_cdp.mjs` 的 `mock` 钩子占掉 `/user/me`（回**客户**身份）+ `/marketing/coupons` + `/marketing/myCoupons`，
//       其余 8088 请求答 `{}`。⚠️ **不碰真库**（券包的真端点验收见
//       `docs/agent/evidence/c3-block5/b5-real-endpoints.md` 的 [5]）。
// 身份：`/user/me` mock 回 `{role:'1', customerid}`；sessionStorage 另塞 `loginUsername`（`isLoggedIn()` 的判据）。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const ACCT = 'probeWalletAcct'

/** 可领券（`/marketing/coupons`，**不传 thisWeek** ⇒ 全量可领 = 裁定「甲」的「可领」）。 */
const AVAIL = [
  { CouponId: 'PROBECL01', Name: '可领券一', Type: 'threshold', FaceValue: 20, Threshold: 100, Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '2026-10-01 00:00:00', EndAt: '2026-12-31 00:00:00', Claimed: 0, SoldOut: 0, IsNew: 1 },
  { CouponId: 'PROBECL02', Name: '可领券二', Type: 'percent', FaceValue: 0.85, Threshold: 0, Scope: 'merchant', MerchantAccount: 'mrc0001', CategoryMatch: '水果', StartAt: '2026-10-01 00:00:00', EndAt: '2026-12-31 00:00:00', Claimed: 1, SoldOut: 0, IsNew: 0 },
  { CouponId: 'PROBECL03', Name: '可领券三', Type: 'none', FaceValue: 5, Threshold: 0, Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '2026-10-01 00:00:00', EndAt: '2026-12-31 00:00:00', Claimed: 0, SoldOut: 1, IsNew: 0 },
]

/** 券包（`/marketing/myCoupons`）—— 四个 `Bucket` 各一条，**次序就是后端排好的次序**（前端不重排）。 */
const MINE = [
  { GrantId: 'g1', CouponId: 'PROBEEXP', Name: '快过期券', Type: 'threshold', FaceValue: 20, Threshold: 100, Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '2026-10-01 00:00:00', EndAt: '2026-10-12 12:00:00', GrantedAt: '2026-10-05 09:00:00', EndDaysLeft: 0, Bucket: 'expiring' },
  { GrantId: 'g2', CouponId: 'PROBEUSE', Name: '未使用券', Type: 'percent', FaceValue: 0.9, Threshold: 0, Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '2026-10-01 00:00:00', EndAt: '2026-10-25 12:00:00', GrantedAt: '2026-10-05 09:00:00', EndDaysLeft: 12, Bucket: 'usable' },
  { GrantId: 'g3', CouponId: 'PROBEDONE', Name: '已使用券', Type: 'threshold', FaceValue: 30, Threshold: 200, Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '2026-10-01 00:00:00', EndAt: '2026-11-01 12:00:00', GrantedAt: '2026-10-02 09:00:00', UsedAt: '2026-10-10 12:00:00', OrderId: 'ORD20261010001', EndDaysLeft: 20, Bucket: 'used' },
  { GrantId: 'g4', CouponId: 'PROBEOUT', Name: '已过期券', Type: 'threshold', FaceValue: 8, Threshold: 80, Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '2026-09-01 00:00:00', EndAt: '2026-10-08 12:00:00', GrantedAt: '2026-09-02 09:00:00', EndDaysLeft: -3, Bucket: 'expired' },
]

/** 四条空态文案（与 `CouponWalletDialog.vue` 的 `EMPTY` **逐字**一致）。 */
const EMPTY = {
  claim: '暂时没有可领的券。新券发布后会出现在这里，也会出现在主页的领券中心。',
  unused: '还没有可用的券，去主页领券中心看看本周新券。',
  used: '还没有用掉的券。结算时选一张券抵扣，用掉后会出现在这里（券的核销在结算流程里）。',
  expired: '没有过期的券 —— 过期未用的券会留在这里。',
}

const r = report('券包弹窗（用户中心 · 优惠券）')

/** 量一遍入口 + 弹窗。⚠️ 本段是模板字面量，注释里**不能有反引号**。 */
const MEASURE = `(() => {
  const strip = (el) => { const c = el.cloneNode(true); c.querySelectorAll('.w-cnt, .w-dot').forEach((n) => n.remove()); return c.textContent.trim() }
  const entry = document.querySelector('.asset-entry')
  const scrim = document.querySelector('.cscrim')
  const out = {
    entryExists: !!entry,
    // ⚠️ 红点那颗 i.wallet-dot（一个圆点字符）**在 .v 里面**（就挂在数字右边）
    //    ⇒ 直接读 textContent 会得到 "2●"。先把红点摘掉再读，否则这条断言永远差一个字符。
    //    ⚠️ 本段整体是模板字面量 ⇒ 注释里**不能有反引号**（写了就会当场截断，报 SyntaxError）。
    entryVal: entry ? (() => { const v = entry.querySelector('.v').cloneNode(true); v.querySelectorAll('.wallet-dot').forEach((n) => n.remove()); return v.textContent.trim() })() : null,
    entryDot: entry ? !!entry.querySelector('.wallet-dot') : null,
    entryTitle: entry ? entry.getAttribute('title') : null,
    scrim: !!scrim,
    scrimZ: scrim ? getComputedStyle(scrim).zIndex : null,
  }
  if (!scrim) return out
  const tabs = [...scrim.querySelectorAll('.w-tab')]
  const rows = [...scrim.querySelectorAll('.w-row')]
  const txt = (el, sel) => { const n = el.querySelector(sel); return n ? n.textContent.trim() : null }
  Object.assign(out, {
    tabs: tabs.map((t) => strip(t)),
    tabCns: tabs.map((t) => { const n = t.querySelector('.w-cnt'); return n ? n.textContent.trim() : null }),
    tabActive: tabs.map((t) => t.className.includes('w-tab-on')),
    tabDot: tabs.map((t) => !!t.querySelector('.w-dot')),
    nRows: rows.length,
    names: rows.map((el) => txt(el, '.w-mid b')),
    tagTexts: rows.map((el) => txt(el, '.w-tag')),
    tagCls: rows.map((el) => { const n = el.querySelector('.w-tag'); return n ? n.className : null }),
    amts: rows.map((el) => txt(el, '.w-amt')),
    subs: rows.map((el) => txt(el, '.w-sub')),
    dates: rows.map((el) => txt(el, '.w-date')),
    news: rows.map((el) => !!el.querySelector('.w-new')),
    getCount: scrim.querySelectorAll('.w-get').length,
    getTexts: [...scrim.querySelectorAll('.w-get')].map((b) => b.textContent.trim()),
    detail: !!scrim.querySelector('.w-detail'),
    detailDts: [...scrim.querySelectorAll('.w-detail dt')].map((n) => n.textContent.trim()),
    detailKj: (() => { const d = [...scrim.querySelectorAll('.w-detail > div')].find((x) => x.querySelector('dt') && x.querySelector('dt').textContent.trim() === '门槛'); return d ? d.querySelector('dd').textContent.trim() : null })(),
    tip: txt(scrim, '.empty-tip'),
    msg: txt(scrim, '.w-msg'),
  })
  return out
})()`

/** 开一个受控浏览器并把 mock 装好。`mode` = 'full' | 'empty'。 */
async function boot(mode) {
  // 领过一次之后「可领券一」变已领 —— 用来验「按钮消失 + 标签翻成已领取」。
  let claimed = false
  return open({
    w: 1440,
    h: 1000,
    mock: (url, method, postData) => {
      if (url.includes('/user/me')) {
        return { body: JSON.stringify({ username: 'probeWallet', role: '1', customerid: ACCT, merchantid: '', usertype: '客户' }) }
      }
      if (url.includes('/marketing/coupon/claim')) {
        claimed = true
        return { body: JSON.stringify({ success: true, msg: '领取成功' }) }
      }
      if (url.includes('/marketing/coupons')) {
        const list = mode === 'empty' ? [] : AVAIL.map((c) => (claimed && c.CouponId === 'PROBECL01' ? Object.assign({}, c, { Claimed: 1 }) : c))
        return { body: JSON.stringify({ success: true, limit: 100, daysLeft: 3, list }) }
      }
      if (url.includes('/marketing/myCoupons')) {
        const list = mode === 'empty' ? [] : MINE
        const redDot = list.some((x) => x.Bucket === 'expiring')
        return { body: JSON.stringify({ success: true, redDot, expiringCount: list.filter((x) => x.Bucket === 'expiring').length, list }) }
      }
      return { body: '{}' }
    },
  })
}

/** 进用户中心并等「优惠券」入口那一格渲染出来。 */
async function visit(b) {
  await b.goto('http://localhost:8080/', 2500)
  // ⚠️ 必须**整页重载**：`Page.navigate` 到只差 hash 的 URL 不重载文档 ⇒ `onMounted` 早在塞 storage 之前跑完了。
  //    `roles: ['1','3']` 在 `/customer` 上 ⇒ `role` 也要塞，否则守卫不放行。
  await b.evalJS("(() => { sessionStorage.setItem('loginUsername', 'probeWallet'); sessionStorage.setItem('customerid', '" + ACCT + "'); sessionStorage.setItem('role', '1'); location.hash = '#/customer/user'; location.reload() })()")
  await sleep(5000)
}

const clickSel = (sel, idx) => `(() => { const n = document.querySelectorAll('${sel}')[${idx || 0}]; if (n) n.click(); return !!n })()`
const clickTab = (idx) => `(() => { const n = document.querySelectorAll('.cscrim .w-tab')[${idx}]; if (n) n.click(); return !!n })()`

// ══ 第一轮：四桶齐 ═══════════════════════════════════════════════════════════════
const b1 = await boot('full')
let res = {}
try {
  await visit(b1)
  res.entry = await b1.evalJS(MEASURE)
  console.log('── 入口 ──', JSON.stringify(res.entry))
  r.ok('客户壳里「优惠券」那一格是入口（`.asset-entry`）', res.entry.entryExists === true)
  r.ok('入口显示**真券数** = 未使用 2 张（expiring ∪ usable，不是总张数 4）', res.entry.entryVal === '2', res.entry.entryVal)
  r.ok('有快过期的券 ⇒ 入口亮红点', res.entry.entryDot === true)
  r.ok('入口 title 说清那个数字是什么', (res.entry.entryTitle || '').includes('未使用 2 张 / 共 4 张'), res.entry.entryTitle)

  await b1.evalJS(clickSel('.asset-entry'))
  await sleep(1400)
  res.opened = await b1.evalJS(MEASURE)
  console.log('── 弹窗 · 可领 tab ──', JSON.stringify(res.opened))
  r.ok('点入口 ⇒ 出弹窗（`.cscrim`）', res.opened.scrim === true)
  r.ok('弹窗 `z-index` = 300（既有的「弹窗叠弹窗」档）', res.opened.scrimZ === '300', res.opened.scrimZ)
  r.ok('4 个 tab、文案逐字 == [可领][未使用][已使用][已过期]',
    JSON.stringify(res.opened.tabs) === JSON.stringify(['可领', '未使用', '已使用', '已过期']), JSON.stringify(res.opened.tabs))
  r.ok('默认落第 1 个 tab「可领」', res.opened.tabActive[0] === true && res.opened.tabActive.slice(1).every((v) => !v), JSON.stringify(res.opened.tabActive))
  r.ok('「可领」tab 列出 3 张（已领 / 已领完**照样列出**）', res.opened.nRows === 3, res.opened.nRows)
  r.ok('标签分别是 可领 / 已领取 / 已领完', JSON.stringify(res.opened.tagTexts) === JSON.stringify(['可领', '已领取', '已领完']), JSON.stringify(res.opened.tagTexts))
  r.ok('「可领」角标只数还领得动的 = 1', res.opened.tabCns[0] === '1', res.opened.tabCns[0])
  r.ok('只有 1 颗「领取」按钮（已领/已领完那两张没有）', res.opened.getCount === 1, res.opened.getCount)
  r.ok('「未使用」tab 上有快过期红点（`.w-dot`）', res.opened.tabDot[1] === true, JSON.stringify(res.opened.tabDot))
  r.ok('面额走 `couponFace`：满减 ¥20 / 折扣 8.5 折 / 无门槛 ¥5',
    JSON.stringify(res.opened.amts) === JSON.stringify(['¥20', '8.5 折', '¥5']), JSON.stringify(res.opened.amts))
  r.ok('`IsNew` 为真的那张带 NEW 角标', JSON.stringify(res.opened.news) === JSON.stringify([true, false, false]), JSON.stringify(res.opened.news))

  // 展开详情
  await b1.evalJS(clickSel('.cscrim .w-main', 0))
  await sleep(400)
  const det = await b1.evalJS(MEASURE)
  console.log('── 展开详情 ──', JSON.stringify({ detail: det.detail, dts: det.detailDts, kj: det.detailKj }))
  await b1.shot('scripts/probes/out-b5-coupon-wallet-full.png')
  r.ok('点一行 ⇒ 展开详情', det.detail === true)
  r.ok('详情含 面额/门槛/适用范围/有效期/券编号',
    ['面额', '门槛', '适用范围', '有效期', '券编号'].every((k) => det.detailDts.includes(k)), JSON.stringify(det.detailDts))
  r.ok('门槛走 `couponCond` ⇒「满 100 可用」', det.detailKj === '满 100 可用', det.detailKj)
  await b1.evalJS(clickSel('.cscrim .w-main', 0))
  await sleep(400)
  r.ok('再点一下 ⇒ 收起详情', (await b1.evalJS(MEASURE)).detail === false)

  // 未使用 tab：expiring 在前 + 「X 天后过期」只在这一档
  await b1.evalJS(clickTab(1))
  await sleep(500)
  const unused = await b1.evalJS(MEASURE)
  console.log('── 未使用 tab ──', JSON.stringify({ names: unused.names, subs: unused.subs, tags: unused.tagTexts, cls: unused.tagCls }))
  r.ok('未使用 = expiring ∪ usable（两张同 tab）', unused.nRows === 2, unused.nRows)
  r.ok('快过期那张**在最上**', JSON.stringify(unused.names) === JSON.stringify(['快过期券', '未使用券']), JSON.stringify(unused.names))
  r.ok('快过期那张标「今天过期」（`EndDaysLeft=0` ⇒ 不是「0 天后过期」）', (unused.subs[0] || '').includes('今天过期'), unused.subs[0])
  r.ok('**只有**快过期那档带「X 天后过期」', (unused.subs[1] || '').includes('天后过期') === false, unused.subs[1])
  r.ok('快过期那行用暖色标签（`w-tag-warn`）', (unused.tagCls[0] || '').includes('w-tag-warn'), unused.tagCls[0])
  r.ok('另一张是普通可用色（`w-tag-on`）', (unused.tagCls[1] || '').includes('w-tag-on'), unused.tagCls[1])

  // 已使用 / 已过期
  await b1.evalJS(clickTab(2))
  await sleep(500)
  const used = await b1.evalJS(MEASURE)
  console.log('── 已使用 tab ──', JSON.stringify({ names: used.names, tags: used.tagTexts }))
  r.ok('已使用 tab 只列 `Bucket=used` 那张', used.nRows === 1 && used.names[0] === '已使用券', JSON.stringify(used.names))
  r.ok('标签 =「已使用」', used.tagTexts[0] === '已使用', used.tagTexts[0])

  await b1.evalJS(clickTab(3))
  await sleep(500)
  const exp = await b1.evalJS(MEASURE)
  console.log('── 已过期 tab ──', JSON.stringify({ names: exp.names, tags: exp.tagTexts }))
  r.ok('已过期 tab 只列 `Bucket=expired` 那张', exp.nRows === 1 && exp.names[0] === '已过期券', JSON.stringify(exp.names))
  r.ok('标签 =「已过期」', exp.tagTexts[0] === '已过期', exp.tagTexts[0])

  // 领取
  await b1.evalJS(clickTab(0))
  await sleep(500)
  await b1.evalJS(clickSel('.cscrim .w-get', 0))
  await sleep(1600)
  const after = await b1.evalJS(MEASURE)
  console.log('── 领完之后 ──', JSON.stringify({ msg: after.msg, tags: after.tagTexts, getCount: after.getCount }))
  r.ok('点「领取」⇒ 提示「已领取「可领券一」」', after.msg === '已领取「可领券一」', after.msg)
  r.ok('领过那张标签翻成「已领取」', after.tagTexts[0] === '已领取', after.tagTexts[0])
  r.ok('领过那张的「领取」按钮消失（`canClaim` 转假）', after.getCount === 0, after.getCount)
  r.ok('「可领」角标随之归零（不再有可领的）', after.tabCns[0] === null, String(after.tabCns[0]))

  // 关窗
  await b1.evalJS(clickSel('.cscrim'))
  await sleep(500)
  r.ok('点遮罩关窗（`@click.self`）', (await b1.evalJS(MEASURE)).scrim === false)
} finally {
  await b1.close()
}

// ══ 第二轮：全空 ⇒ 四条空态 ═══════════════════════════════════════════════════════
const b2 = await boot('empty')
try {
  await visit(b2)
  const e0 = await b2.evalJS(MEASURE)
  console.log('── 空券包入口 ──', JSON.stringify({ val: e0.entryVal, dot: e0.entryDot }))
  r.ok('一张券都没有 ⇒ 入口显示 0（不是「—」：0 是**真取到了**的 0）', e0.entryVal === '0', e0.entryVal)
  r.ok('无快过期 ⇒ 不亮红点', e0.entryDot === false)

  await b2.evalJS(clickSel('.asset-entry'))
  await sleep(1400)
  const tips = []
  tips.push((await b2.evalJS(MEASURE)).tip)
  for (const i of [1, 2, 3]) {
    await b2.evalJS(clickTab(i))
    await sleep(400)
    tips.push((await b2.evalJS(MEASURE)).tip)
  }
  console.log('── 四条空态 ──', JSON.stringify(tips))
  r.ok('「可领」空态文案', tips[0] === EMPTY.claim, tips[0])
  r.ok('「未使用」空态文案', tips[1] === EMPTY.unused, tips[1])
  r.ok('「已使用」空态文案（要**如实说明为什么空** —— 核销在结算）', tips[2] === EMPTY.used, tips[2])
  r.ok('「已过期」空态文案', tips[3] === EMPTY.expired, tips[3])
  const eTab = await b2.evalJS("(() => ({ cn: document.querySelectorAll('.cscrim .w-cnt').length, dot: document.querySelectorAll('.cscrim .w-dot').length }))()")
  r.ok('全空时 tab 上不挂张数角标、也不挂红点', eTab.cn === 0 && eTab.dot === 0, JSON.stringify(eTab))
  await b2.shot('scripts/probes/out-b5-coupon-wallet-empty.png')
} finally {
  await b2.close()
}

const bad = r.dump()
process.exit(bad ? 1 : 0)
