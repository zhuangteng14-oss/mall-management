// admin-login-verify.mjs —— 管理端 `admin` 演示账号能登录（2026-10-31〈上线前扫雷〉第 ② 条验收）。
//
// 口径：真前端(8080 dev) + 真后端(8088 dev)，走 `#/admin/login` 真表单 + 真鼠标点提交。
// 口令**只从 `_work/admin-pass` 读**，不打印、不进仓（[Credential Leakage] 钩子）。
//
// 为什么另写一个而不复用 `admin-login-split.mjs`：那一份的前半段用「账号 + 密码」先登一个
// **客户**（#acc/#pwd），而 2026-10-31 起该路径要求图形验证码 ⇒ 它会在第 ② 步就红。
// 本脚本只回答「管理员这一条能进」，与其他断言无关。
//
// 用法：node scripts/probes/admin-login-verify.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const WEB = 'http://localhost:8080'
const PWD = fs.readFileSync(path.join(ROOT, '_work/admin-pass'), 'utf8').replace(/[\r\n]/g, '')
if (!PWD) { console.error('_work/admin-pass 为空'); process.exit(2) }

const R = report('管理端登录（admin）')
const b = await open({ w: 1440, h: 900 })
try {
  await b.goto(WEB + '/index.html', 1200)
  await b.evalJS(`location.hash = '#/admin/login'`)
  await sleep(1500)

  const setInput = (sel, v) => b.evalJS(`(() => {
    const el = document.querySelector(${JSON.stringify(sel)})
    if (!el) return false
    const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    s.call(el, ${JSON.stringify(v)}); el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)

  R.ok('管理员登录页可进（表单三件套在）',
    await b.evalJS(`!!document.querySelector('input[name="adminAccount"]') && !!document.querySelector('input[name="adminPassword"]') && !!document.querySelector('button.al-submit')`))

  await setInput('input[name="adminAccount"]', 'admin')
  await setInput('input[name="adminPassword"]', PWD)

  const box = JSON.parse(await b.evalJS(`(() => {
    const el = document.querySelector('button.al-submit')
    const r = el.getBoundingClientRect()
    return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2 })
  })()`))
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y, buttons: 0 })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })

  let hash = ''
  for (let t = 0; t < 12000; t += 300) {
    hash = await b.evalJS('location.hash')
    if (/^#\/admin$/.test(hash)) break
    await sleep(300)
  }
  const store = await b.evalJS(`(() => ({ u: sessionStorage.getItem('adminUsername'), s: sessionStorage.getItem('adminSession'), t: sessionStorage.getItem('adminAuthToken') || '' }))()`)
  const err = await b.evalJS(`(document.querySelector('.al-err') || {}).textContent || ''`)

  R.ok('提交后进入管理后台', /^#\/admin$/.test(hash), { hash, err })
  R.ok('管理员独立会话已写（adminUsername / adminSession）', store.u === 'admin' && store.s === '1', { u: store.u, s: store.s })
  R.ok('管理端票已签（token 长度 = 43）', store.t.length === 43, store.t.length)
  await b.shot(path.join(ROOT, '_work/admin-login-ok.png'))
} finally {
  await b.close()
}
process.exit(R.dump() === 0 ? 0 : 1)
