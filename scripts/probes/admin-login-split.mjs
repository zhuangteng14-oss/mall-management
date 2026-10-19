// admin-login-split.mjs —— 管理员独立登录页验证（2026-10-19）
// 不截图、不写文档；只输出 PASS/FAIL 行。
// 用法：PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" node scripts/probes/admin-login-split.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

function waitHash(b, re, tries = 50) {
  return (async () => {
    for (let i = 0; i < tries; i++) {
      const h = await b.evalJS('location.hash')
      if (re.test(h)) return h
      await sleep(400)
    }
    return ''
  })()
}

const setInput = (sel, v) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  if (!el) return false
  const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  s.call(el, ${JSON.stringify(v)}); el.dispatchEvent(new Event('input', { bubbles: true }))
  return true
})()`

async function loginNormal(b, user, pwd) {
  await b.evalJS(`location.hash = '#/login'`)
  await sleep(1200)
  await b.evalJS(setInput('#acc', user))
  await b.evalJS(setInput('#pwd', pwd))
  await b.evalJS(`(() => {
    const btn = document.querySelector('button.btn.btn-primary.btn-block:not([disabled])')
    if (!btn) return false
    btn.click(); return true
  })()`)
}

async function loginAdmin(b, user, pwd) {
  await b.evalJS(`location.hash = '#/admin/login'`)
  await sleep(1200)
  await b.evalJS(setInput('input[name="adminAccount"]', user))
  await b.evalJS(setInput('input[name="adminPassword"]', pwd))
  return b.evalJS(`(() => {
    const btn = document.querySelector('button.al-submit')
    if (!btn) return { ok: false, why: 'submit-missing' }
    btn.click(); return { ok: true }
  })()`)
}

const storage = () => `(() => {
  const keys = ['adminUsername','adminSession','loginUsername','role','authToken','merchantid','customerid']
  const out = {}; keys.forEach(k => { out[k] = sessionStorage.getItem(k) })
  return out
})()`

const b = await open({ w: 1440, h: 900, headed: false })
try {
  // ① 普通登录页右上角入口
  await b.goto(BASE + '/index.html', 1200)
  await b.evalJS(`location.hash = '#/login'`)
  await sleep(1600)
  const entry = await b.evalJS(`(() => {
    const a = document.querySelector('a.admin-entry')
    return a ? { text: a.textContent.trim(), href: a.getAttribute('href') } : null
  })()`)
  ok('普通登录页存在管理员入口', !!entry && /Admin Portal/.test(entry.text), entry)

  await b.evalJS(`document.querySelector('a.admin-entry').click()`)
  const ah = await waitHash(b, /^#\/admin\/login/)
  ok('入口跳转 #/admin/login', !!ah, ah)

  const dark = await b.evalJS(`(() => {
    const el = document.querySelector('.admin-login')
    if (!el) return null
    const cs = getComputedStyle(el)
    return { bg: cs.backgroundColor, img: cs.backgroundImage.slice(0, 40), card: !!document.querySelector('.al-card'), side: !!document.querySelector('.a-side') }
  })()`)
  ok('管理员页为深色且无管理端侧栏', !!dark && dark.bg === 'rgb(16, 24, 40)' && dark.card && !dark.side, dark)

  // ② 客户先登录，保存其身份状态
  await loginNormal(b, '123', '123456')
  const ch = await waitHash(b, /^#\/customer/)
  ok('客户先行登录', !!ch, ch)
  const before = await b.evalJS(storage())
  ok('客户身份已落存储', before.loginUsername === '123' && before.role === '1', before)

  // ③ 管理员独立登录
  const clickAdmin = await loginAdmin(b, 'admin', PWD)
  ok('管理员提交', clickAdmin.ok === true, clickAdmin)
  const mh = await waitHash(b, /^#\/admin$/)
  ok('管理员进入后台', !!mh, mh)

  const after = await b.evalJS(storage())
  ok('管理员独立会话已写', after.adminUsername === 'admin' && after.adminSession === '1', after)
  ok('客户身份未被覆盖', after.loginUsername === before.loginUsername && after.role === before.role && after.authToken === before.authToken && after.customerid === before.customerid, { before, after })

  const shell = await b.evalJS(`(() => ({ side: !!document.querySelector('.a-side'), who: (document.querySelector('.a-who b') || {}).textContent || '' }))()`)
  ok('后台外壳显示管理员身份', shell.side && shell.who === 'admin', shell)

  // ④ 管理员退出
  await b.evalJS(`document.querySelector('button.a-out').click()`)
  const lh = await waitHash(b, /^#\/admin\/login/)
  ok('管理员退出回到管理员登录页', !!lh, lh)
  const out = await b.evalJS(storage())
  ok('管理员会话已清', !out.adminUsername && !out.adminSession, out)
  ok('退出后客户身份仍在', out.loginUsername === before.loginUsername && out.role === before.role && out.authToken === before.authToken && out.customerid === before.customerid, out)

  // ⑤ 客户身份仍可用
  await b.evalJS(`location.hash = '#/customer'`)
  await sleep(1600)
  const still = await b.evalJS(`(() => ({ hash: location.hash, shell: !!document.querySelector('.app-shell') }))()`)
  ok('退出管理端后客户壳仍可进', /^#\/customer/.test(still.hash) && still.shell, still)

  // ⑥ 非管理员账号被拒
  const c2 = await loginAdmin(b, '123', '123456')
  ok('非管理员提交', c2.ok === true, c2)
  await sleep(1200)
  const rejected = await b.evalJS(`(() => ({ hash: location.hash, err: (document.querySelector('.al-err') || {}).textContent || '' }))()`)
  ok('客户账号进不了管理员页', /^#\/admin\/login/.test(rejected.hash) && /不是管理员/.test(rejected.err), rejected)
} finally {
  await b.close()
}

// ── 商家端：独立浏览器会话（同一标签页内换号会撞既有的「票优先」问题，与本次改动无关）──
const b2 = await open({ w: 1440, h: 900, headed: false })
try {
  await b2.goto(BASE + '/index.html', 1200)
  await loginNormal(b2, '1', '1')
  const mch = await waitHash(b2, /^#\/merchant/)
  ok('商家登录', !!mch, mch)
  const mBefore = await b2.evalJS(storage())
  ok('商家身份已落存储', mBefore.loginUsername === '1' && mBefore.role === '2', mBefore)

  await loginAdmin(b2, 'admin', PWD)
  const mah = await waitHash(b2, /^#\/admin$/)
  ok('管理员从商家会话进入后台', !!mah, mah)
  const mAfter = await b2.evalJS(storage())
  ok('商家身份未被覆盖', mAfter.loginUsername === mBefore.loginUsername && mAfter.role === mBefore.role && mAfter.authToken === mBefore.authToken && mAfter.merchantid === mBefore.merchantid, { before: mBefore, after: mAfter })

  await b2.evalJS(`document.querySelector('button.a-out').click()`)
  await waitHash(b2, /^#\/admin\/login/)
  const mOut = await b2.evalJS(storage())
  ok('退出管理员后商家身份仍在', mOut.loginUsername === mBefore.loginUsername && mOut.role === mBefore.role && mOut.authToken === mBefore.authToken && mOut.merchantid === mBefore.merchantid, mOut)

  await b2.evalJS(`location.hash = '#/merchant'`)
  await sleep(1600)
  const mStill = await b2.evalJS(`(() => ({ hash: location.hash, shell: !!document.querySelector('.app-shell') }))()`)
  ok('退出管理端后商家壳仍可进', /^#\/merchant/.test(mStill.hash) && mStill.shell, mStill)
} finally {
  await b2.close()
}

const bad = rows.filter(r => !r.pass)
console.log(`\n${rows.length - bad.length}/${rows.length} PASS`)
process.exit(bad.length ? 1 : 0)
