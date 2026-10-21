// vite-migration-check.mjs —— Vite 迁移后的补充验收（2026-10-20）
// 目的：探针 admin-login-split.mjs 只覆盖「登录/身份隔离」，本脚本补三块：
//   ① admin.html 直开（data-theme、无 hash 定到 #/admin）；
//   ② 管理端路由逐个走一遍，抓 HTTP ≥400 / console error / 异常（含 SSE 页）；
//   ③ 客户侧首页 → #/customer：图片解码、字体分片、CORS 报错。
// 用法：PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/env/admin-pass)" node scripts/probes/vite-migration-check.mjs
// 只输出 PASS/FAIL 行，产物写 _work/。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? '  ' + JSON.stringify(detail) : ''}`)
}

function attach(b) {
  const errors = []
  const bad = []
  b.ws.on('message', (raw) => {
    let m
    try { m = JSON.parse(raw) } catch { return }
    if (m.method === 'Network.responseReceived') {
      const r = m.params.response
      if (r.status >= 400) bad.push({ status: r.status, url: r.url })
    } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      errors.push(m.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
    } else if (m.method === 'Runtime.exceptionThrown') {
      errors.push('EXC ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text))
    }
  })
  b.send('Network.enable')
  return { errors, bad }
}

const setInput = (sel, v) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)})
  if (!el) return false
  const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  s.call(el, ${JSON.stringify(v)}); el.dispatchEvent(new Event('input', { bubbles: true }))
  return true
})()`

async function loginAdmin(b, user, pwd) {
  await b.evalJS(`location.hash = '#/admin/login'`)
  await sleep(1200)
  await b.evalJS(setInput('input[name="adminAccount"]', user))
  await b.evalJS(setInput('input[name="adminPassword"]', pwd))
  await b.evalJS(`document.querySelector('button.al-submit').click()`)
  for (let i = 0; i < 40; i++) {
    if (/^#\/admin$/.test(await b.evalJS('location.hash'))) return true
    await sleep(300)
  }
  return false
}

const b = await open({ w: 1440, h: 900, headed: false })
try {
  const net = attach(b)

  // ① admin.html 直接打开（不走 index.html 的 hash 跳转）
  await b.goto(BASE + '/admin.html', 2200)
  const ent = await b.evalJS(`(() => ({
    theme: document.documentElement.dataset.theme || '',
    hash: location.hash,
    shell: !!document.querySelector('#app') && document.querySelector('#app').children.length > 0,
    app: (document.querySelector('#app') || {}).textContent.slice(0, 30)
  }))()`)
  // ⚠️ 未登录时不该停在 #/admin：守卫把它送去普通登录页，`redirect=/admin` 正是内联脚本
  //    先写进 hash 的目标 —— **这就是「无 hash 直开 ⇒ 先定到 #/admin」的实测证据**。
  ok('空 hash 直开 admin.html ⇒ 内联脚本定到 #/admin 后守卫送登录', ent.hash === '#/login?redirect=/admin', ent)
  // /login 不是管理端路由 ⇒ afterEach 摘掉 data-theme（router/index.js:736-737 的既定口径）。
  ok('登录页上 data-theme 被摘掉（既定）', ent.theme === '', ent.theme)
  ok('admin.html 能挂载 Vue', ent.shell, ent.app)

  // ② 管理端逐个路由
  ok('管理端登录', await loginAdmin(b, 'admin', PWD))
  const themeAdmin = await b.evalJS(`document.documentElement.dataset.theme || ''`)
  ok('进入管理端后 data-theme=admin', themeAdmin === 'admin', themeAdmin)
  // 登录前的 `/user/me` 401 是「无会话探测」的正常答复；从那之后不该再出现任何 ≥400。
  const badBefore = net.bad.length
  const routes = ['/admin', '/admin/business/accounts', '/admin/business/catalog', '/admin/observe/metrics', '/admin/observe/logs', '/admin/observe/audit', '/admin/stats']
  for (const route of routes) {
    await b.evalJS(`location.hash = '#${route}'`)
    await sleep(route.includes('/observe/') ? 3000 : 1800)
    const st = await b.evalJS(`(() => ({
      hash: location.hash,
      mounted: !!document.querySelector('.a-main, .a-shell, #app'),
      text: (document.querySelector('#app') || {}).textContent.trim().length
    }))()`)
    ok(`${route} 可渲染`, st.hash === '#' + route && st.text > 200, { hash: st.hash, chars: st.text })
  }

  // SSE：管理端两页应各有一条 /admin/observe/*/stream 的 200
  const badAfter = net.bad.slice(badBefore)
  ok('登录后无 HTTP ≥400', badAfter.length === 0, badAfter.slice(0, 8))
  ok('登录前的 401 只有会话探测 /user/me', net.bad.slice(0, badBefore).every((x) => x.status === 401 && /\/user\/me$/.test(x.url)), net.bad.slice(0, badBefore))
  ok('管理端无 console error / 异常', net.errors.length === 0, net.errors.slice(0, 8))

  const sseReqs = await b.evalJS(`performance.getEntriesByType('resource')
    .filter(e => /\\/admin\\/observe\\/.*\\/stream/.test(e.name))
    .map(e => ({ name: e.name.replace(location.origin, ''), status: e.responseStatus, dur: Math.round(e.duration) }))`)
  ok('SSE 流已发出（经 Vite 代理）', Array.isArray(sseReqs) && sseReqs.length >= 1, sseReqs)

  // ③ 客户侧：首页 → #/customer
  const b2 = await open({ w: 1440, h: 900, headed: false })
  try {
    const net2 = attach(b2)
    await b2.goto(BASE + '/index.html', 2500)
    const root = await b2.evalJS(`(() => ({
      theme: document.documentElement.dataset.theme || '(空)',
      mounted: document.querySelector('#app').children.length > 0,
      chars: document.querySelector('#app').textContent.trim().length
    }))()`)
    ok('index.html 直开且非管理端外壳', root.theme !== 'admin' && root.mounted && root.chars > 200, root)

    await b2.evalJS(`location.hash = '#/login'`)
    await sleep(1400)
    await b2.evalJS(setInput('#acc', '123'))
    await b2.evalJS(setInput('#pwd', '123456'))
    await b2.evalJS(`document.querySelector('button.btn.btn-primary.btn-block:not([disabled])').click()`)
    let logged = false
    for (let i = 0; i < 40; i++) {
      if (/^#\/customer/.test(await b2.evalJS('location.hash'))) { logged = true; break }
      await sleep(300)
    }
    ok('客户登录并进入 #/customer', logged, await b2.evalJS('location.hash'))
    const bad2Before = net2.bad.length

    await b2.evalJS(`location.hash = '#/customer/cart'`)
    await sleep(2000)
    await b2.evalJS(`location.hash = '#/customer'`)
    await sleep(2000)

    const imgs = await b2.evalJS(`(() => {
      const all = [...document.images]
      return { total: all.length, broken: all.filter(i => i.complete && i.naturalWidth === 0).map(i => i.currentSrc || i.src).slice(0, 6) }
    })()`)
    ok('图片全部解码成功', imgs.broken.length === 0, { total: imgs.total, broken: imgs.broken })

    const fonts = await b2.evalJS(`(() => {
      const f = performance.getEntriesByType('resource').filter(e => /\\.woff2/.test(e.name))
      return { n: f.length, okN: f.filter(e => !e.responseStatus || e.responseStatus < 400).length, sample: f.slice(0, 2).map(e => e.name.split('/').pop()) }
    })()`)
    ok('字体分片已加载', fonts.n > 0 && fonts.okN === fonts.n, fonts)

    const cors = net2.errors.filter((e) => /CORS|Access-Control|cross-origin|Failed to fetch/i.test(e))
    ok('客户侧无 CORS / 网络 console error', cors.length === 0, cors.slice(0, 5))
    ok('登录后无 HTTP ≥400', net2.bad.slice(bad2Before).length === 0, net2.bad.slice(bad2Before).slice(0, 8))
  } finally {
    await b2.close()
  }
} finally {
  await b.close()
}

const bad = rows.filter((r) => !r.pass)
console.log(`\n${rows.length - bad.length}/${rows.length} PASS`)
process.exit(bad.length ? 1 : 0)
