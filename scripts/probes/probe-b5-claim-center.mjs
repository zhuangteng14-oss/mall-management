// probe-b5-claim-center.mjs —— 探针（2026-10-11，C3 块 5「券的信息架构重排」后**整份重写**）
//
// 为什么重写而不是改几行：几何与语义**同时**变了，旧断言大面积失效 ——
//   · 右栏「我的券包」整块删掉 ⇒ 不再有「两栏等宽」；现在**只有一栏**、占满整页宽；
//   · `TK_VISIBLE` 3 → 5，基宽 `(100% - s2*4)/5.3`，渐隐带 56 → **88px**；
//   · 「一键领取」从**恒 disabled 的占位**变成**真按钮**（`doClaimBatch`）⇒ 旧断言「disabled === true」直接反转；
//   · 列表改从 `/marketing/coupons` 的 `thisWeek=1` 取 ⇒ 新增「前端确实发了这个参数」；
//   · 新增 NEW 角标 / 「本周还剩 N 天」/ 新空态文案 / 一键领取的汇总文案。
//
// 做法：`_cdp.mjs` 的 `mock` 钩子把 `/marketing/coupons` 与 `/marketing/coupon/claimBatch` 顶掉，
//       其余 8088 请求一律答 `{}`。⚠️ **不碰真库** —— 本探针只问「前端画成什么样」；
//       真端点与真库的验收在 `docs/agent/evidence/c3-block5/b5-real-endpoints.md`。
// 身份：往 sessionStorage 塞 `customerid`（`api/auth.js` 的 `getCustomerId` 存储兜底）。
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

/** ⚠️ 与 `HomePage.vue` 的 `TK_VISIBLE` **必须一致**（改了那边要同步这里，否则边界断言全错）。 */
const TK_N = 5
/** ⚠️ 与 CSS `.tk-strip-cut` 的渐隐带宽一致（`mask-image: linear-gradient(… calc(100% - 88px) …)`）。 */
const MASK_PX = 88

/** mock 回来的 `daysLeft`（后端 `CouponDao.selectWeekWindow` 的产物，周一=7…周日=1）。 */
const DAYS_LEFT = 3

/**
 * `IsNew` 的六种形态。后端实测回的是**整数** `1`/`0`（`b5-real-endpoints.md`），
 * 但 `api/marketing.js` 明写形态可能是 `true` 也可能是 `'1'` ⇒ 用 `truthy()` 那套**宽容判据**把六种都过一遍。
 */
const IS_NEW_FORMS = [1, 0, true, '1', false, '0']
const NEW_EXPECT = IS_NEW_FORMS.map((v) => v === 1 || v === true || v === '1')

/** 造一张券。`FaceValue` **逐张 +1** ⇒ 靠 `¥10 / ¥11 / …` 认「DOM 顺序 == 数组顺序」。 */
const mkCoupon = (i) => ({
  Id: i + 1,
  CouponId: 'PROBE' + String(i).padStart(2, '0'),
  Name: '探针券 ' + (i + 1),
  Type: 'threshold',
  FaceValue: 10 + i,
  Threshold: 50,
  TotalQty: 0,
  ClaimedQty: 0,
  Scope: 'all',
  MerchantAccount: '',
  CategoryMatch: '',
  Status: 'active',
  StartAt: '2026-10-01 00:00:00',
  EndAt: '2026-12-31 00:00:00',
  IsNew: IS_NEW_FORMS[i % IS_NEW_FORMS.length],
})

/**
 * 量一遍券区。**所有横向坐标都相对券条左边**（`left - strip.left`）⇒ 与页面外距无关。
 * ⚠️ 本段是模板字面量，注释里**不能有反引号**。
 */
const MEASURE = `(() => {
  const cols = [...document.querySelectorAll('.tk-col')]
  const col = cols[0]
  if (!col) return { err: '没有 .tk-col' }
  const strip = col.querySelector('.tk-strip')
  const tip = col.querySelector('.empty-tip')
  const more = col.querySelector('.tk-more')
  const all = col.querySelector('.tk-all')
  const left = col.querySelector('.tk-left')
  const items = strip ? [...strip.children] : []
  const sr = strip ? strip.getBoundingClientRect() : null
  const cs = strip ? getComputedStyle(strip) : null
  const right = (el) => el.getBoundingClientRect().right - sr.left
  const txt = (el, sel) => { const n = el.querySelector(sel); return n ? n.textContent.trim() : null }
  const mask = cs ? String(cs.maskImage || cs.webkitMaskImage || '') : ''
  const maskPx = (mask.match(/([0-9.]+)px/) || [])[1]
  const get = (i) => items[i].querySelector('.tk-get')
  return {
    err: null,
    colCount: cols.length,
    colW: cols.map((el) => Math.round(el.getBoundingClientRect().width)),
    n: items.length,
    cls: strip ? strip.className : '',
    faces: items.map((el) => txt(el, '.tk-amt')),
    conds: items.map((el) => txt(el, '.tk-cond')),
    news: items.map((el) => !!el.querySelector('.tk-new')),
    getTexts: items.map((el) => { const b = el.querySelector('.tk-get'); return b ? b.textContent.trim() : null }),
    getDisabled: items.map((el) => { const b = el.querySelector('.tk-get'); return b ? b.disabled : null }),
    lefts: items.map((el) => Math.round(el.getBoundingClientRect().left - sr.left)),
    tops: items.map((el) => Math.round(el.getBoundingClientRect().top - sr.top)),
    visW: sr ? Math.round(sr.width) : -1,
    cardW: items.length ? Math.round(items[0].getBoundingClientRect().width) : -1,
    hiddenW: sr ? items.filter((el) => right(el) > sr.width + 1).length : -1,
    peekNext: sr && items.length > ${TK_N} ? Math.round(sr.width - (items[${TK_N}].getBoundingClientRect().left - sr.left)) : -1,
    overflowX: cs ? cs.overflowX : '',
    flexWrap: cs ? cs.flexWrap : '',
    moreText: more ? more.textContent.trim() : null,
    moreOn: more ? more.className.includes('tk-more-on') : null,
    allText: all ? all.textContent.trim() : '',
    allDisabled: all ? all.disabled : null,
    leftText: left ? left.textContent.trim() : null,
    tip: tip ? tip.innerText.trim() : null,
    maskPx: maskPx ? Number(maskPx) : null,
  }
})()`

const r = report('领券中心（本周新券 · 信息架构重排后）')

/** 收集每次请求的 body（`_cdp.mjs` 的 mock 钩子会把 postData 传进来）。 */
let seen = { coupons: [], batch: [], claim: [] }

/**
 * 跑一轮。
 * @param {number} n 券张数
 * @param {{shot?:string, clickMore?:boolean, claimedAt?:number[], soldOutAt?:number[], clickBatch?:boolean}} [opt]
 */
async function round(n, opt = {}) {
  seen = { coupons: [], batch: [], claim: [] }
  // 「领取」点过之后，服务端会把这批券里被领的那张标成已领 ⇒ 用这个开关模拟。
  let claimHappened = false
  const body = () => {
    const list = Array.from({ length: n }, (_, i) => {
      const c = mkCoupon(i)
      if ((opt.claimedAt || []).includes(i)) c.Claimed = 1
      if ((opt.soldOutAt || []).includes(i)) c.SoldOut = 1
      if (claimHappened) c.Claimed = 1
      return c
    })
    return JSON.stringify({ success: true, limit: 10, daysLeft: DAYS_LEFT, list })
  }

  const b = await open({
    w: 1280,
    h: 900,
    mock: (url, method, postData) => {
      if (url.includes('/marketing/coupon/claimBatch')) {
        seen.batch.push(postData || '')
        return { body: JSON.stringify({ success: true, msg: '已领取 2 张，1 张失败', total: 3, claimed: 2, failed: 1, daysLeft: DAYS_LEFT, items: [{ CouponId: 'PROBE00', Name: '探针券 1', success: true, msg: '领取成功' }, { CouponId: 'PROBE01', Name: '探针券 2', success: true, msg: '领取成功' }, { CouponId: 'PROBE02', Name: '探针券 3', success: false, msg: '您已领取过该券' }] }) }
      }
      if (url.includes('/marketing/coupon/claim')) {
        seen.claim.push(postData || '')
        claimHappened = true
        return { body: JSON.stringify({ success: true, msg: '领取成功' }) }
      }
      if (url.includes('/marketing/coupons')) {
        seen.coupons.push(postData || '')
        return { body: body() }
      }
      return { body: '{}' }
    },
  })
  try {
    await b.goto('http://localhost:8080/', 2500)
    // ⚠️ 必须**整页重载**：`Page.navigate` 到只差 hash 的 URL **不重载文档** ⇒ HomePage 的
    //    `onMounted` 早在塞 storage 之前就跑完了。`#/customer` 的默认子记录就是 HomePage（`router/index.js` 的 `path: ''`）。
    await b.evalJS("(() => { sessionStorage.setItem('customerid', 'probe_b5_tmp'); location.hash = '#/customer'; location.reload() })()")
    await sleep(5000)
    await b.evalJS("(() => { const el = document.querySelector('.tickets'); if (el) el.scrollIntoView({ block: 'center' }) })()")
    await sleep(800)

    const closed = await b.evalJS(MEASURE)
    if (opt.shot) await b.shot(opt.shot)

    let opened = null
    if (opt.clickMore && closed.moreText) {
      await b.evalJS("(() => { document.querySelectorAll('.tk-col')[0].querySelector('.tk-more').click() })()")
      await sleep(500)
      opened = await b.evalJS(MEASURE)
      if (opt.shot) await b.shot(opt.shot.replace(/\.png$/, '-open.png'))
      // 再点一下：得回到收起态（按钮是**开关**，不是单向放大）。
      await b.evalJS("(() => { document.querySelectorAll('.tk-col')[0].querySelector('.tk-more').click() })()")
      await sleep(500)
      opened.backToClosed = (await b.evalJS(MEASURE)).cls
    }

    // 一键领取：在同一次 eval 里先抓「正在领」（`batchBusy` ⇒ 文案/禁用），再等它落定抓汇总。
    let batch = null
    if (opt.clickBatch) {
      batch = await b.evalJS(`(async () => {
        const btn = document.querySelector('.tk-all')
        if (!btn) return { err: '没有 .tk-all' }
        btn.click()
        // Vue 的重渲染在微任务里 ⇒ 让出一次宏任务，抢在 mock 应答之前读「进行中」那一帧。
        await new Promise((res) => setTimeout(res, 0))
        const busy = { text: btn.textContent.trim(), disabled: btn.disabled }
        await new Promise((res) => setTimeout(res, 1200))
        const msg = document.querySelector('.tk-msg')
        return { err: null, busy, msg: msg ? msg.textContent.trim() : null,
                 after: (() => { const a = document.querySelector('.tk-all'); return a ? { text: a.textContent.trim(), disabled: a.disabled } : null })() }
      })()`)
      if (opt.shot) await b.shot(opt.shot.replace(/\.png$/, '-after-batch.png'))
    }

    return { closed, opened, batch, seen: JSON.parse(JSON.stringify(seen)) }
  } finally {
    await b.close()
  }
}

const run = (label, n, opt) =>
  round(n, opt).then((x) => (console.log('── ' + label + ' ──', JSON.stringify(x.closed)), x))

// ── 0 张：空态（用户裁定 ③ 的文案）+ 倒计时照显示 ─────────────────────────────────
const zero = await run('0 张券（空态）', 0)
r.ok('0 张时有 `.tk-col`、没有券条', zero.closed.err === null && zero.closed.n === 0, JSON.stringify(zero.closed.err))
r.ok("空态文案 ==「本周暂无新券 —— 去用户中心的「优惠券 · 可领」看全部可领券」",
  zero.closed.tip === '本周暂无新券 —— 去用户中心的「优惠券 · 可领」看全部可领券', zero.closed.tip)
r.ok('0 张时**仍**显示「本周还剩 N 天」（它是周窗口，与有几张无关）',
  zero.closed.leftText === '本周还剩 ' + DAYS_LEFT + ' 天', zero.closed.leftText)
r.ok('0 张时「一键领取本周全部」在且 disabled', zero.closed.allText === '一键领取本周全部' && zero.closed.allDisabled === true,
  zero.closed.allText + ' / disabled=' + zero.closed.allDisabled)
r.ok('0 张时**不**出「》」', zero.closed.moreText === null, zero.closed.moreText)

// ── 1 张：面额/门槛/NEW 角标/领取按钮 ────────────────────────────────────────────
const one = await run('1 张券', 1, { shot: 'scripts/probes/out-b5-claim-center-1.png' })
r.ok('1 张渲染出来了', one.closed.err === null && one.closed.n === 1, one.closed.err || 'ok')
r.ok('**只有一栏**（右栏券包已删）', one.closed.colCount === 1, JSON.stringify(one.closed.colW))
r.ok('面额走 `couponFace` ⇒ ¥10', one.closed.faces[0] === '¥10', one.closed.faces[0])
r.ok('门槛走 `couponCond` ⇒「满 50 可用」', one.closed.conds[0] === '满 50 可用', one.closed.conds[0])
r.ok('`IsNew=1` ⇒ 出 NEW 角标', one.closed.news[0] === true, JSON.stringify(one.closed.news))
r.ok('「本周还剩 N 天」渲染出来', one.closed.leftText === '本周还剩 ' + DAYS_LEFT + ' 天', one.closed.leftText)
r.ok('1 张时**不**出「》」', one.closed.moreText === null, one.closed.moreText)
r.ok('1 张时**不**加渐隐类', !one.closed.cls.includes('tk-strip-cut'), one.closed.cls)
r.ok('领取按钮可点（这张没领过、没领完）', one.closed.getTexts[0] === '领取' && one.closed.getDisabled[0] === false,
  one.closed.getTexts[0] + ' / disabled=' + one.closed.getDisabled[0])

// ── 5 张（= TK_VISIBLE 边界）：仍不到「更多」的门槛 ───────────────────────────────
const five = await run('5 张券（= TK_VISIBLE 边界）', 5, { shot: 'scripts/probes/out-b5-claim-center-5.png' })
r.ok('5 张时**不**出「》」（判据是 `>` 不是 `>=`）', five.closed.moreText === null, five.closed.moreText)
r.ok('5 张时**不**加渐隐类', !five.closed.cls.includes('tk-strip-cut'), five.closed.cls)
r.ok('5 张**一张都没被裁**', five.closed.hiddenW === 0, 'hiddenW=' + five.closed.hiddenW + ' visW=' + five.closed.visW)
r.ok('5 张同排铺开（一行，没换行）', new Set(five.closed.tops).size === 1, JSON.stringify(five.closed.tops))
r.ok('一行 5 张：每张卡宽约为券条的 1/5.3', five.closed.cardW > 0 && five.closed.cardW < five.closed.visW / 4.8,
  'cardW=' + five.closed.cardW + ' visW=' + five.closed.visW)

// ── 6 张：渐隐 + 「》」，点开=全部铺开 ────────────────────────────────────────────
const six = await run('6 张券', 6, {
  shot: 'scripts/probes/out-b5-claim-center-6.png',
  clickMore: true,
  clickBatch: true,
  claimedAt: [0],
  soldOutAt: [5],
})
const g = six.closed
console.log('── 6 张、展开后 ──', JSON.stringify(six.opened))
console.log('── 6 张、一键领取 ──', JSON.stringify(six.batch))
console.log('── 6 张、请求体 ──', JSON.stringify(six.seen))

r.ok('券条里有 6 张', g.n === 6, g.n)
r.ok('不滚：券条 `overflow-x: hidden`', g.overflowX === 'hidden', g.overflowX)
r.ok('6 张时加渐隐类', g.cls.includes('tk-strip-cut'), g.cls)
r.ok('收起态 `nowrap`（不换行）', g.flexWrap === 'nowrap', g.flexWrap)
r.ok('6 张时**只有 1 张**被裁到界外（TK_VISIBLE=5）', g.hiddenW === 1, 'hiddenW=' + g.hiddenW + ' visW=' + g.visW)
r.ok('第 6 张**露一角**（不是整张消失在裁切线外）', g.peekNext > 8 && g.peekNext < g.visW,
  '露出 ' + g.peekNext + 'px / 共 ' + g.visW + 'px')
r.ok('★ 不变量：露头宽 < 渐隐带宽（88px）⇒ 是被**淡掉**而不是被切掉',
  g.peekNext > 0 && g.maskPx !== null && g.peekNext < g.maskPx,
  '露 ' + g.peekNext + 'px < mask ' + g.maskPx + 'px')
r.ok('渐隐带实测就是 88px（与 CSS 一致）', g.maskPx === MASK_PX, String(g.maskPx))
r.ok('「》」按钮在', g.moreText === '》', g.moreText)
r.ok('「一键领取本周全部」与「》」**并存**（同一处按钮区）', g.allText === '一键领取本周全部', g.allText)
r.ok('同一排：6 张 `top` 全相同（一行铺开，没换行）', new Set(g.tops).size === 1, JSON.stringify(g.tops))
r.ok('**从左到右**：left 严格递增', g.lefts.every((v, i) => i === 0 || v > g.lefts[i - 1]), JSON.stringify(g.lefts))
r.ok('顺序 == 数组原序（¥10…¥15，前端没重排）',
  g.faces.join(',') === ['¥10', '¥11', '¥12', '¥13', '¥14', '¥15'].join(','), g.faces.join(','))
r.ok('NEW 角标只打在 `IsNew` 为真的卡上（六种形态 1/0/true/"1"/false/"0" 全过一遍）',
  JSON.stringify(g.news) === JSON.stringify(NEW_EXPECT), JSON.stringify(g.news))
r.ok('已领取 ⇒ 按钮「已领取」且禁用', g.getTexts[0] === '已领取' && g.getDisabled[0] === true,
  g.getTexts[0] + ' / ' + g.getDisabled[0])
r.ok('已领完 ⇒ 按钮「已领完」且禁用', g.getTexts[5] === '已领完' && g.getDisabled[5] === true,
  g.getTexts[5] + ' / ' + g.getDisabled[5])
r.ok('其余 4 张仍可点「领取」', g.getTexts.slice(1, 5).join(',') === '领取,领取,领取,领取', g.getTexts.join(','))

r.ok('点「》」后换成展开类', six.opened.cls.includes('tk-strip-on'), six.opened.cls)
r.ok('展开后渐隐类**撤掉**', !six.opened.cls.includes('tk-strip-cut'), six.opened.cls)
r.ok('展开后 `wrap` 换行', six.opened.flexWrap === 'wrap', six.opened.flexWrap)
r.ok('展开后 6 张全可见', six.opened.hiddenW === 0, six.opened.hiddenW)
r.ok('展开后不止一行（多行铺开）', new Set(six.opened.tops).size > 1, JSON.stringify(six.opened.tops))
r.ok('展开后按钮翻成「《」', six.opened.moreText === '《', six.opened.moreText)
r.ok('展开**不改列宽**（整块不跳）', six.opened.colW[0] === g.colW[0], g.colW[0] + ' → ' + six.opened.colW[0])
r.ok('再点一下回到收起态', six.opened.backToClosed.includes('tk-strip-cut') && !six.opened.backToClosed.includes('tk-strip-on'), six.opened.backToClosed)

// ── 前端确实发了 `thisWeek=1`（裁定「甲」的接线点）───────────────────────────────
r.ok('每次取券都带 `thisWeek=1`（主页只放本周新券）',
  six.seen.coupons.length > 0 && six.seen.coupons.every((s) => s.includes('thisWeek=1')),
  JSON.stringify(six.seen.coupons))
r.ok('取券的 `limit` 是 10（够「》」展开看）',
  six.seen.coupons.every((s) => s.includes('limit=10')), JSON.stringify(six.seen.coupons))

// ── 一键领取：服务端定集合 / busy 态 / 汇总文案 ───────────────────────────────────
const bt = six.batch
r.ok('点到了「一键领取本周全部」', bt && bt.err === null, bt ? bt.err : 'null')
r.ok('★ 批量请求**只传 Account**、不吃 CouponId（集合由服务端定）',
  six.seen.batch.length === 1 && !six.seen.batch[0].includes('CouponId'),
  JSON.stringify(six.seen.batch))
r.ok('点下去立刻进 busy：文案「领取中…」+ disabled',
  bt && bt.busy && bt.busy.text === '领取中…' && bt.busy.disabled === true,
  bt ? JSON.stringify(bt.busy) : 'null')
r.ok('落定后按 claimed/failed 汇总（顶层 success:true ≠ 全成功）',
  bt && bt.msg === '一键领取：成功 2 张，失败 1 张（您已领取过该券）', bt ? bt.msg : 'null')
r.ok('落定后按钮复位为「一键领取本周全部」', bt && bt.after && bt.after.text === '一键领取本周全部',
  bt && bt.after ? bt.after.text : 'null')

// ⚠️ 单张「领取」（`/marketing/coupon/claim`）**不在这里** —— 它的界面后果（那张卡变「已领取」）
//    是`CouponWalletDialog` 里那条路径，归 `probe-b5-coupon-wallet.mjs`。
//    主页这条只到「一键领取」为止（那才是本页独有的动作）。

const bad = r.dump()
process.exit(bad ? 1 : 0)
