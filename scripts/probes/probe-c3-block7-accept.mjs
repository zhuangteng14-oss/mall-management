// probe-c3-block7-accept.mjs —— 批四第二批手工验收取证（2026-10-19）。
//
// 真 dev server（8080）+ 真后端（8088，dev profile）：
//   ① 商家「查看商品」并表：草稿 / 待审 / 已上架三种状态同屏；
//   ② 首页入口 → /brand 品牌专区：品牌商家商品卡带「品牌」标；
//   ③ VIP 商品详情：原价删除线 + 会员价（9 折）；加购后购物车同样两栏；
//   ④ 历史订单「申请退货」→ 填原因 → 状态「待商家审核」。
//
// ⚠️ 不 mock 任何请求 —— 本份的全部价值就是走真链。
// ⚠️ 头像是 headless Edge（`--headless=new`）；截图落 docs/agent/evidence/c3-block7/。
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const SHOT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const R = report('c3-block7-accept')

// 演示账号口令**不硬编码、不回显**：由 _work/demo-pw.txt（QueryDb 产物）就地读入。
// 文件形如 `1111 | <pwd>`；探针只把账号名放进报告，口令永不出现在日志。
const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const read = async (b, expr) => b.evalJS(expr)
process.on('uncaughtException', (e) => { console.error('UNCAUGHT:', e && e.message ? e.message : e); process.exit(R.dump() ? 1 : 0) })

async function login(b, acc, pwd, landRe) {
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(b, `(() => { const a = document.querySelector('#acc'), p = document.querySelector('#pwd'); return a && p ? true : null })()`, 30, 300)
  let filled = null
  let state = null
  for (let attempt = 0; attempt < 3; attempt++) {
    filled = await read(b, `(() => {
      const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
      const a = document.querySelector('#acc'), p = document.querySelector('#pwd')
      if (!a || !p) return { ok: false, inputs: [...document.querySelectorAll('input')].map(e => e.id || e.type) }
      set(a, ${JSON.stringify(acc)}); set(p, ${JSON.stringify(pwd)})
      const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录')
      if (!btn) return { ok: false, btns: [...document.querySelectorAll('button')].map(e => e.textContent.trim()).slice(0, 12) }
      btn.click(); return { ok: true }
    })()`)
    for (let i = 0; i < 24; i++) {
      await sleep(500)
      state = await read(b, `({ hash: location.hash, role: sessionStorage.getItem('role'), user: sessionStorage.getItem('loginUsername'), customerid: sessionStorage.getItem('customerid'), merchantid: sessionStorage.getItem('merchantid'), member: sessionStorage.getItem('memberLevel'), token: !!sessionStorage.getItem('authToken'), warn: (document.querySelector('.err, .login-err, [class*=err]') || {}).innerText || '', tail: document.body.innerText.slice(-120) })`)
      if (landRe.test(state.hash)) return { filled, state }
    }
  }
  return { filled, state }
}

async function waitFor(b, expr, tries = 40, gap = 500) {
  let v = null
  for (let i = 0; i < tries; i++) {
    v = await read(b, expr)
    if (v) return v
    await sleep(gap)
  }
  return v
}

// ── ① 商家：商品状态并表 ───────────────────────────────────────────────────
const bm = await open({ w: 1440, h: 900 })
bm.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method === 'Page.javascriptDialogOpening') bm.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
})
try {
  const lg = await login(bm, 'chenguang_parts', CREDS['chenguang_parts'], /^#\/merchant/)
  R.ok('① 商家登录落 /merchant（role=2）', lg.filled.ok && lg.state.role === '2' && /^#\/merchant/.test(lg.state.hash), lg.state)
  await bm.goto(BASE + '/#/merchant/user/list', 1200)
  const goods = await waitFor(bm, `(() => {
    const states = [...document.querySelectorAll('.state')].map(e => e.textContent.trim())
    if (!states.length) return null
    return { states, drafts: states.filter(s => s === '草稿').length, pendings: states.filter(s => s === '待审').length, onshelf: states.filter(s => s === '已上架').length }
  })()`)
  R.ok('① 并表含草稿行', goods && goods.drafts >= 1, goods)
  R.ok('① 并表含待审行', goods && goods.pendings >= 1, goods)
  R.ok('① 并表含已上架行', goods && goods.onshelf >= 1, goods)
  await bm.shot(SHOT + 'b7-01-goods-states.png')

  // ── ② 品牌专区 ─────────────────────────────────────────────────────────
  await bm.goto(BASE + '/#/brand', 1500)
  const brand = await waitFor(bm, `(() => {
    const cards = [...document.querySelectorAll('.grid .pcard')]
    if (!cards.length) return null
    return { cards: cards.length, badges: [...document.querySelectorAll('.pcard-kind')].map(e => e.textContent.trim()), names: cards.map(c => c.innerText.split('\\n').filter(Boolean).slice(0, 3)) }
  })()`)
  R.ok('② /brand 有商品卡', brand && brand.cards > 0, brand && brand.cards)
  R.ok('② 商品卡带「品牌」标', brand && brand.badges.includes('品牌'), brand && brand.badges)
  await bm.shot(SHOT + 'b7-02-brand-zone.png')
} finally {
  await bm.close()
}

// ── ③④ 客户：会员价 / 购物车 / 售后申请 ──────────────────────────────────
const bc = await open({ w: 1440, h: 900 })
bc.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method === 'Page.javascriptDialogOpening') bc.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
})
try {
  const lg = await login(bc, '1111', CREDS['1111'], /^#\/customer/)
  R.ok('③ 客户登录落 /customer（role=1，MemberLevel=1）', lg.filled.ok && lg.state.role === '1' && lg.state.member === '1' && lg.state.token, lg.state)

  await bc.goto(BASE + '/#/brand', 1500)
  const opened = await waitFor(bc, `(() => { const c = document.querySelector('.grid .pcard'); if (!c) return null; c.click(); return true })()`)
  R.ok('③ 从品牌专区点进商品详情', opened === true)
  const price = await waitFor(bc, `(() => {
    const p = document.querySelector('.priceline .price.vip')
    const tag = document.querySelector('.priceline .vip-tag')
    const was = document.querySelector('.priceline .was')
    if (!p) return null
    return { member: p.innerText.replace(/\\s+/g, ' ').trim(), was: was ? was.innerText.trim() : '', tag: tag ? tag.innerText.trim() : '', hash: location.hash, name: (document.querySelector('.p-name, .p-title, h1') || {}).innerText || '' }
  })()`)
  R.ok('③ 详情页显示会员价标签', !!(price && price.tag === '会员价'), price)
  R.ok('③ 详情页原价删除线在场', !!(price && /^¥\s?\d/.test(price.was)), price && price.was)
  await bc.shot(SHOT + 'b7-03-member-price.png')

  // 加购：详情页【加入购物车】→ 弹窗（默认首型号 1 件）→ 确认
  const act = await read(bc, `(() => {
    const btns = [...document.querySelectorAll('.acts button')]
    const b = btns.find(e => e.textContent.includes('加入购物车'))
    if (!b) return { ok: false, btns: btns.map(e => e.textContent.trim()) }
    b.click(); return { ok: true }
  })()`)
  R.ok('③ 详情页有「加入购物车」按钮', act.ok === true, act)
  const popup = await waitFor(bc, `(() => { const p = document.querySelector('.popup-mask'); return p ? { rows: document.querySelectorAll('.p-row').length, cta: (document.querySelector('.p-confirm') || {}).textContent || '' } : null })()`)
  R.ok('③ 加购弹窗打开（默认 1 件）', !!(popup && popup.rows > 0), popup)
  const confirm = await read(bc, `(() => { const b = document.querySelector('.p-confirm'); if (!b) return { ok: false }; const text = b.textContent.trim(); b.click(); return { ok: true, text } })()`)
  const dacts = await waitFor(bc, `(() => { const d = document.querySelector('.dacts .ok'); return d ? true : null })()`, 20, 500)
  const afterConfirm = await read(bc, `({ hash: location.hash, mask: !!document.querySelector('.rf-mask'), dacts: !!document.querySelector('.dacts .ok'), cta: ${JSON.stringify(confirm && confirm.text)}, body: document.body.innerText.slice(0, 160) })`)
  R.ok('③ 点「加入购物车」后弹确认框', dacts === true && confirm.ok, { confirm, afterConfirm })
  if (dacts === true) await read(bc, `document.querySelector('.dacts .ok').click()`)
  let cartState = null
  for (let i = 0; i < 30; i++) {
    await sleep(500)
    cartState = await read(bc, `({ hash: location.hash, was: [...document.querySelectorAll('.m-was')].map(e => e.textContent.trim()), price: [...document.querySelectorAll('.m-price')].map(e => e.textContent.trim()), total: (document.querySelector('.total .v') || {}).innerText || '' })`)
    if (/cart/.test(cartState.hash) && cartState.price.length) break
  }
  R.ok('③ 加购成功并落购物车页', /cart/.test(cartState.hash), cartState.hash)
  R.ok('③ 购物车显示原价删除线 + 会员价', cartState.was.length > 0 && cartState.price.length > 0, cartState)
  await bc.shot(SHOT + 'b7-04-member-cart.png')

  // ── ④ 售后申请 ─────────────────────────────────────────────────────────
  await bc.goto(BASE + '/#/customer/user/history', 1500)
  const applied = await read(bc, `(() => {
    const b = document.querySelector('.act-refund')
    if (!b) return { ok: false, rows: document.querySelectorAll('tbody tr').length }
    const row = b.closest('tr')
    b.click(); return { ok: true, order: row ? row.innerText.split('\\n').slice(0, 3) : [] }
  })()`)
  R.ok('④ 历史订单有「申请退货」按钮', applied.ok === true, applied)
  await waitFor(bc, `(() => !!document.querySelector('.rf-mask'))()`)
  const filledReason = await read(bc, `(() => {
    const t = document.querySelector('.rf-input'); if (!t) return { ok: false }
    const s = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set; s.call(t, 'B7 acceptance: item not as described')
    t.dispatchEvent(new Event('input', { bubbles: true }))
    return { ok: true, v: t.value }
  })()`)
  R.ok('④ 售后原因可填写', filledReason.ok === true && filledReason.v.length > 0, filledReason)
  await read(bc, `document.querySelector('.rf-submit').click()`)
  let refundState = null
  for (let i = 0; i < 30; i++) {
    await sleep(500)
    refundState = await read(bc, `({ open: !!document.querySelector('.rf-mask'), err: (document.querySelector('.rf-err') || {}).innerText || '', hints: [...document.querySelectorAll('.act-hint')].map(e => e.textContent.trim()), states: [...document.querySelectorAll('td.c-st .state')].map(e => e.textContent.trim()).slice(0, 5) })`)
    if (!refundState.open && (refundState.hints.includes('待商家审核') || refundState.states.includes('待商家审核'))) break
  }
  R.ok('④ 提交后状态为「待商家审核」', refundState.hints.includes('待商家审核') || refundState.states.includes('待商家审核'), refundState)
  await bc.shot(SHOT + 'b7-05-refund-applied.png')
} finally {
  await bc.close()
}

process.exit(R.dump() ? 1 : 0)
