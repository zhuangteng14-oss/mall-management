// probe-acct.mjs —— 2026-10-08 第 3 条：多账户切换「历史订单 403」复现。
// 口径：mock **按后端真规则答**——`IdentityGuard.requireSelfOrAdmin`（IdentityGuard.java:71-80）：
//   票里的 userId ≠ 入参 id 且调用者不是管理员(role=3) ⇒ 403 + `{success:false,msg:'无权操作该账户'}`。
//   票的 userId 来源见 UserController.loginSelect:113（会话里存的是**业务号**：商家=merchantid、客户=customerid）。
// 走法 = 用户报的那串：商家登录 → 换用户 → 换回商家 → 打开「历史购买记录」。
import { open, sleep } from './_cdp.mjs'

const ACCOUNTS = {
  mer1: { role: '2', me: { success: true, username: 'mer1', merchantid: 'M9001', customerid: null, role: '2' } },
  cus1: { role: '1', me: { success: true, username: 'cus1', merchantid: null, customerid: 'C5001', role: '1' } }
}
let sessionUser = null            // 服务端「会话」：只有业务号（与 AuthSession 同形状）
const log = []                    // 每次历史订单请求：谁带的 id、票是谁的、答了什么

const MOCK = (url, method, postData, headers) => {
  const send = (status, obj) => ({ status, body: JSON.stringify(obj) })
  const q = new URLSearchParams(postData || '')
  if (/loginSelect/.test(url)) {
    const u = q.get('username'); const acc = ACCOUNTS[u]
    if (!acc) { sessionUser = null; return { status: 200, ctype: 'text/plain', body: '0' } }
    sessionUser = acc.role === '2' ? acc.me.merchantid : acc.me.customerid
    return { status: 200, ctype: 'text/plain', body: acc.role }
  }
  if (/\/user\/me/.test(url)) {
    if (!sessionUser) return send(401, { success: false, msg: '未登录' })
    const acc = Object.values(ACCOUNTS).find((a) => (a.me.merchantid || a.me.customerid) === sessionUser)
    return send(200, acc.me)
  }
  if (/\/user\/token/.test(url)) return send(200, { success: true, token: 'T-' + sessionUser })
  if (/historicalOrdersSelect/.test(url)) {
    const paramId = q.get('customerId')
    const tok = /Bearer\s+(\S+)/.exec(headers?.Authorization || '')?.[1] || null
    const tokenUser = tok ? tok.replace(/^T-/, '') : null
    const role = Object.values(ACCOUNTS).find((a) => (a.me.merchantid || a.me.customerid) === tokenUser)?.role
    const pass = (tokenUser === null) || (paramId === tokenUser) || (role === '3')
    log.push({ paramId, tokenUser, role, status: pass ? 200 : 403 })
    if (!pass) return send(403, { success: false, msg: '无权操作该账户' })   // IdentityGuard.forbidden()
    return send(200, { success: true, list: [{ OrderId: 'ORD-1', ProductInformation: 'x', MerchantId: 'M9001', SettlementDate: '2026-10-01 10:00:00' }] })
  }
  return send(200, { success: true, list: [], msg: '' })
}

const b = await open({ w: 1440, h: 900, mock: MOCK })
const dialogs = []
b.ws.on('message', (raw) => {
  const m = JSON.parse(raw)
  if (m.method === 'Page.javascriptDialogOpening') { dialogs.push(m.params.message); b.send('Page.handleJavaScriptDialog', { accept: true }) }
})
const login = async (user) => {
  await b.goto('http://localhost:8080/#/login', 1200)
  await b.evalJS(`(() => { const set = (sel, v) => { const el = document.querySelector(sel); el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })) }
    set('#acc', ${JSON.stringify(user)}); set('#pwd', 'pw-1234'); return 1 })()`)
  await b.evalJS(`document.querySelector('section[data-page="login"] .btn-primary').click(); 1`)
  await sleep(1800)
  return b.evalJS('location.hash')
}
const SNAP = `JSON.stringify({ user: sessionStorage.getItem('loginUsername'), customerid: sessionStorage.getItem('customerid'), merchantid: sessionStorage.getItem('merchantid'), role: sessionStorage.getItem('role'), token: sessionStorage.getItem('authToken') })`
try {
  console.log('① 商家登录 →', await login('mer1'))
  console.log('   sessionStorage:', await b.evalJS(SNAP))

  console.log('② 换用户登录 →', await login('cus1'))
  console.log('   sessionStorage:', await b.evalJS(SNAP))

  console.log('③ 换回商家登录 →', await login('mer1'))
  const st = await b.evalJS(SNAP)
  console.log('   sessionStorage:', st, '  ← 注意 customerid 是谁的')

  await b.goto('http://localhost:8080/#/merchant/user/history', 2200)
  const view = await b.evalJS(`(() => { const e = document.querySelector('.empty'); const l = document.querySelector('.loading'); return { emptyBox: e ? e.textContent.trim() : null, loadingBox: l ? l.textContent.trim() : null, rows: document.querySelectorAll('.ords tbody tr').length, bodyText: document.body.innerText.replace(/\\s+/g,' ').slice(0, 240) } })()`)
  console.log('④ 历史购买记录页：', JSON.stringify(view, null, 0))
  console.log('   弹窗:', JSON.stringify(dialogs))
  console.log('   服务端收到的请求：', JSON.stringify(log))
  await b.shot('docs/美化/dtop4/acct-403.png')
} finally { await b.close() }
