// login-rollback-verify.mjs —— 登录页回滚后的三端登录 / 退出验证（2026-10-19）
// 不截图、不写文档；只输出 PASS/FAIL 行。
// 用法：PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" node scripts/probes/login-rollback-verify.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

function waitHash(b, re, tries = 40) {
  return (async () => {
    for (let i = 0; i < tries; i++) {
      const h = await b.evalJS('location.hash')
      if (re.test(h)) return h
      await sleep(500)
    }
    return ''
  })()
}

async function login(b, entry, user, pwd) {
  await b.goto(BASE + entry, 1500)
  await b.evalJS(`sessionStorage.clear(); localStorage.clear(); location.hash = '#/login'; 'ok'`)
  await sleep(1000)
  return b.evalJS(`(() => {
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return { ok: false, why: 'inputs-missing', hash: location.hash }
    set(acc, ${JSON.stringify(user)}); set(pwd, ${JSON.stringify(pwd)})
    // ⚠️ 文本「登录」在页签上也有一个（:48），必须先命中提交按钮本体。
    const btn = document.querySelector('button.btn.btn-primary.btn-block:not([disabled])')
    if (!btn || btn.textContent.trim() !== '登录') return { ok: false, why: 'button-missing' }
    btn.click()
    return { ok: true }
  })()`)
}

const b = await open({ w: 1360, h: 900, headed: false })
try {
  // ① 管理端
  const a = await login(b, '/admin.html', 'admin', PWD)
  ok('管理端登录提交', a.ok === true, a)
  const ah = await waitHash(b, /^#\/admin/)
  ok('管理端进入后台', !!ah, ah)

  // ② 客户
  const c = await login(b, '/index.html', '123', '123456')
  ok('客户登录提交', c.ok === true, c)
  const ch = await waitHash(b, /^#\/customer/)
  ok('客户进入买家壳', !!ch, ch)

  // ③ 客户退出登录（个人中心身份卡）
  if (ch) {
    await b.evalJS(`location.hash = '#/customer/user/info'`)
    await sleep(1800)
    const out = await b.evalJS(`(() => {
      const btn = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '退出登录')
      if (!btn) return { ok: false, why: 'logout-missing' }
      btn.click(); return { ok: true }
    })()`)
    ok('客户退出按钮存在', out.ok === true, out)
    const lh = await waitHash(b, /^#\/login/, 30)
    ok('退出后回到登录页', !!lh, lh)
    const cred = await b.evalJS(`sessionStorage.getItem('token') || sessionStorage.getItem('username') || ''`)
    ok('退出后本地凭据已清', !cred, cred ? 'still-present' : '')
  }

  // ④ 商家
  const m = await login(b, '/index.html', '1', '1')
  ok('商家登录提交', m.ok === true, m)
  const mh = await waitHash(b, /^#\/merchant/)
  ok('商家进入商家壳', !!mh, mh)
} finally {
  await b.close()
}

const bad = rows.filter(r => !r.pass)
console.log(`\n${rows.length - bad.length}/${rows.length} PASS`)
process.exit(bad.length ? 1 : 0)
