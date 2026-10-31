// captcha-login-verify.mjs —— 图形验证码 + 手机号字段（2026-10-31〈上线前扫雷〉）的真 UI 验收。
//
// 口径：真前端(8080 dev) + 真后端(8088 dev) + 真库。**不 OCR、不读后端内存**：
//   验证码由人（本笔的执行者）看截图读出来，写进 `_work/captcha-answer.txt`，脚本轮询到之后
//   才继续 —— 这一步本身就是「图上那 4 个字符真能被人读出来」的证据。
//
// 断言串：
//   ① 进页自动取码，<img> 的 src 是 data URL（不是占位文字）；
//   ② 填错 → 提示「验证码错误或已过期」+ 自动换了一张（src 变了）；
//   ③ 点「换一张」→ src 再变（刷新链路通）；
//   ④ 填入正确码 → 登录成功（落到 /customer 且拿到本标签页票）；
//   ⑤ 登录后 /user/me 的手机号 / 邮箱是**掩码**（不是明文）。
//
// 用法：node scripts/probes/captcha-login-verify.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const WEB = 'http://localhost:8080'
const BE = 'http://127.0.0.1:8088'
const SHOT = path.join(ROOT, '_work/captcha-shot.png')
const ANSWER = path.join(ROOT, '_work/captcha-answer.txt')

const CREDS = {}
for (const line of fs.readFileSync(path.join(ROOT, '_work/demo-pw.txt'), 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/); if (m && m[1] !== 'UserName') CREDS[m[1]] = m[2]
}
const USER = '1111'

const R = report('图形验证码 / 手机号 / 脱敏（真 UI）')
const b = await open({ w: 1440, h: 1000 })
try {
  await b.goto(WEB + '/#/login', 2000)

  // ── ① 进页自动取码 ──
  let src = ''
  for (let t = 0; t < 12000; t += 250) {
    src = await b.evalJS(`(document.querySelector('img.capfig') || {}).src || ''`)
    if (src.startsWith('data:image/png;base64,')) break
    await sleep(250)
  }
  R.ok('① 进页自动取到图形验证码（data URL PNG）', src.startsWith('data:image/png;base64,'), src.slice(0, 28) + '…')

  const setInput = (sel, v) => b.evalJS(`(() => {
    const el = document.querySelector(${JSON.stringify(sel)})
    if (!el) return false
    const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    s.call(el, ${JSON.stringify(v)}); el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  const clickText = async (scope, text) => {
    const box = JSON.parse(await b.evalJS(`(() => {
      const el = [...document.querySelectorAll(${JSON.stringify(scope)})].find((x) => x.textContent.trim().includes(${JSON.stringify(text)}) && !x.disabled)
      if (!el) return JSON.stringify({ found: false })
      const r = el.getBoundingClientRect()
      return JSON.stringify({ found: true, x: r.left + r.width / 2, y: r.top + r.height / 2 })
    })()`))
    if (!box.found) throw new Error('没找到按钮：' + text)
    await b.moveTo(box.x, box.y)
    await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  }

  await setInput('#acc', USER)
  await setInput('#pwd', CREDS[USER])
  await setInput('#cap', '0000')
  await clickText('button.btn.btn-primary.btn-block', '登录')

  // ── ② 错码 ⇒ 明确提示 + 自动换新图 ──
  let warn = ''
  for (let t = 0; t < 8000; t += 250) {
    warn = await b.evalJS(`document.querySelector('.warn') ? document.querySelector('.warn').textContent.trim() : ''`)
    if (warn.includes('验证码')) break
    await sleep(250)
  }
  R.ok('② 填错码被拦下并提示', warn.includes('验证码错误或已过期'), warn)
  const srcAfterWrong = await b.evalJS(`(document.querySelector('img.capfig') || {}).src || ''`)
  R.ok('② 错一次后自动换了一张', srcAfterWrong && srcAfterWrong !== src)

  // ── ③ 点「换一张」再变一次 ──
  const beforeRefresh = srcAfterWrong
  await clickText('.caprow button', '换一张')
  let srcRefreshed = beforeRefresh
  for (let t = 0; t < 6000; t += 250) {
    srcRefreshed = await b.evalJS(`(document.querySelector('img.capfig') || {}).src || ''`)
    if (srcRefreshed && srcRefreshed !== beforeRefresh) break
    await sleep(250)
  }
  R.ok('③ 点「换一张」图表确实换了', srcRefreshed !== beforeRefresh)

  // ── ④ 截图 → 人读码 → 登录 ──
  const rect = JSON.parse(await b.evalJS(`(() => { const r = document.querySelector('img.capfig').getBoundingClientRect(); return JSON.stringify({ x: r.left, y: r.top, w: r.width, h: r.height }) })()`))
  const shot = await b.send('Page.captureScreenshot', {
    format: 'png',
    clip: { x: rect.x, y: rect.y, width: rect.w, height: rect.h, scale: 6 }
  })
  fs.writeFileSync(SHOT, Buffer.from(shot.data, 'base64'))
  try { fs.unlinkSync(ANSWER) } catch (e) {}
  console.log(`\n[等待] 请读 ${SHOT} 里的 4 个字符，写入 ${ANSWER}（脚本最多等 180 秒）\n`)

  let answer = ''
  for (let t = 0; t < 180000 && !answer; t += 500) {
    if (fs.existsSync(ANSWER)) answer = fs.readFileSync(ANSWER, 'utf8').replace(/[^0-9A-Za-z]/g, '').toUpperCase()
    if (!answer) await sleep(500)
  }
  if (!answer) throw new Error('没等到人工读数（_work/captcha-answer.txt）')

  await setInput('#cap', answer)
  await clickText('button.btn.btn-primary.btn-block', '登录')

  let token = ''
  for (let t = 0; t < 12000; t += 250) {
    token = await b.evalJS(`sessionStorage.getItem('authToken') || ''`)
    if (token) break
    await sleep(250)
  }
  const hash = await b.evalJS(`location.hash`)
  R.ok('④ 正确验证码 → 登录成功并落到客户首页', !!token && hash.startsWith('#/customer'), { hash, token: token ? token.slice(0, 8) + '…' : '无' })

  // ── ⑤ /user/me 脱敏 ──
  const me = await fetch(BE + '/user/me', { method: 'POST', headers: { Authorization: 'Bearer ' + token } })
  const meJson = await me.json().catch(() => ({}))
  R.ok('⑤ /user/me 手机号是掩码', /^\d{3}\*{4}\d{4}$/.test(meJson.phone || ''), meJson.phone)
  R.ok('⑤ /user/me 邮箱是掩码', /^.\*{3}@/.test(meJson.email || ''), meJson.email)
  R.ok('⑤ /user/me 地址是掩码', typeof meJson.address === 'string' && meJson.address.endsWith('***'), meJson.address)

  await b.shot(path.join(ROOT, '_work/captcha-login-after.png'))
} finally {
  await b.close()
}
process.exit(R.dump() === 0 ? 0 : 1)
