// multitab-ui.mjs —— 双标签页隔离的真浏览器验收（多账户隔离补丁，2026-10-31）。
//
// 口径：真前端(8080 dev) + 真后端(8088 dev) + 真库，走**真实 UI 登录 / 退出**（真鼠标点击）。
// 要回答三个问题：
//   ① 新标签页（Ctrl+T 认领指针）拿到的是不是**自己的**票（≠ 原页）；
//   ② 新标签页点「退出登录」后，原页的票是否还能打 /user/me 200；
//   ③ 新标签页**换号重新登录**后，原页是否还保持原账户（用户报的「显示未知账户」）。
//
// 用法：node scripts/probes/multitab-ui.mjs
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { createRequire } from 'node:module'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const WS_ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目/_work/probe-deps'
const WebSocket = createRequire(path.join(WS_ROOT, 'package.json'))('ws')
const WEB = 'http://localhost:8080'
const BE = 'http://127.0.0.1:8088'

// 口令只从本地文件读、不打印（仓纪律：明文口令不进报告）
const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/); if (m && m[1] !== 'UserName') CREDS[m[1]] = m[2]
}
const ACC_A = '1111'
const ACC_B = 'yunqi_digital'
for (const u of [ACC_A, ACC_B]) if (!CREDS[u]) { console.log('demo-pw.txt 缺账号 ' + u); process.exit(2) }

const getJson = (url) => new Promise((res, rej) => {
  http.get(url, (r) => { let d = ''; r.on('data', (c) => (d += c)); r.on('end', () => { try { res(JSON.parse(d)) } catch (e) { rej(e) } }) }).on('error', rej)
})
const be = async (p, token) => {
  const r = await fetch(BE + p, { method: 'POST', headers: token ? { Authorization: 'Bearer ' + token } : {} })
  let j = {}; try { j = await r.json() } catch (e) {}
  return { http: r.status, success: j.success, username: j.username || (j.user && j.user.UserName) }
}

/** 连到另一个标签页目标（_cdp.open 只管第一个页）。 */
async function attach(target) {
  const ws = new WebSocket(target.webSocketDebuggerUrl, { perMessageDeflate: false, maxPayload: 64 * 1024 * 1024 })
  await new Promise((r, j) => { ws.on('open', r); ws.on('error', j) })
  let id = 0; const pend = new Map()
  ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.id && pend.has(m.id)) { const { res, rej } = pend.get(m.id); pend.delete(m.id); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result) }
  })
  const send = (method, params = {}) => new Promise((res, rej) => { const i = ++id; pend.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method, params })) })
  await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  return {
    send,
    async evalJS(expr, userGesture = false) {
      const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true, userGesture })
      if (r.exceptionDetails) throw new Error('eval 失败：' + (r.exceptionDetails.exception && r.exceptionDetails.exception.description || JSON.stringify(r.exceptionDetails)))
      return r.result.value
    },
    close() { try { ws.close() } catch (e) {} }
  }
}

/** 等某页的 sessionStorage.authToken 出现（登录完成后 LoginPage 才写）。 */
async function waitToken(tab, ms = 12000) {
  for (let t = 0; t < ms; t += 250) {
    const v = await tab.evalJS(`sessionStorage.getItem('authToken') || ''`).catch(() => '')
    if (v) return v
    await sleep(250)
  }
  return ''
}

/** 等某页的票**换成了别人的**（≠ 原页的票）。
 *  ⚠️ 新标签页的 sessionStorage 一开始就带着复制/认领来的旧票 ⇒ `waitToken` 会在启动换票**之前**
 *  立刻返回旧票，把「修好了」误判成「没换」。必须等到票真的变了（或超时）。 */
async function waitTokenDiff(tab, other, ms = 12000) {
  for (let t = 0; t < ms; t += 250) {
    const v = await tab.evalJS(`sessionStorage.getItem('authToken') || ''`).catch(() => '')
    if (v && v !== other) return v
    await sleep(250)
  }
  return ''
}

async function realClick(tab, x, y) {
  await tab.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, buttons: 0 })
  await tab.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
  await tab.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
}

/** 演示账号 → 回填在 `user.phone` 的手机号（Migration ㉛）。 */
const PHONES = { '1111': '13800001111', 'yunqi_digital': '13500002222', 'chenguang_parts': '13600001111' }

/** 在某页里按文本找一枚可点按钮，返回屏幕坐标（真鼠标点）。 */
async function findButton(tab, scope, text) {
  const box = JSON.parse(await tab.evalJS(`(() => {
    const b = [...document.querySelectorAll(${JSON.stringify(scope)})].find((x) => x.textContent.trim().includes(${JSON.stringify(text)}) && !x.disabled)
    if (!b) return JSON.stringify({ found: false })
    const r = b.getBoundingClientRect()
    return JSON.stringify({ found: true, x: r.left + r.width / 2, y: r.top + r.height / 2 })
  })()`))
  if (!box.found) throw new Error('没找到可点的按钮：' + text)
  return box
}

/**
 * 真 UI 登录（表单输入 + 真鼠标点提交）。
 *
 * ⚠️ 2026-10-31〈上线前扫雷〉起**必须走手机号登录**：账号密码登录链加了图形验证码，
 *    码在 PNG 里、脚本读不出来（这正是不做 OCR 就能证明它「真」的理由）。
 *    手机号登录不要求图形码，演示短信码固定 123456（后端 `UserService.DEMO_SMS_CODE`），
 *    探针点「获取验证码」后页面自己会填进去 —— 仍是一条**真 UI** 路径，没有绕过控件。
 */
async function uiLogin(tab, user) {
  const phone = PHONES[user]
  if (!phone) throw new Error('PHONES 里没有账号 ' + user + ' 的手机号')
  await tab.evalJS(`location.hash = '#/login'; 1`)
  for (let t = 0; t < 8000; t += 250) {
    if (await tab.evalJS(`!!document.querySelector('#acc')`)) break
    await sleep(250)
  }
  // 切到「手机号登录」分段
  const seg = await findButton(tab, '.seg button', '手机号登录')
  await realClick(tab, seg.x, seg.y)
  for (let t = 0; t < 4000; t += 250) {
    if (await tab.evalJS(`!!document.querySelector('[data-page="phone"].on #ph')`)) break
    await sleep(250)
  }
  await tab.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('[data-page="phone"].on #ph'), ${JSON.stringify(phone)})
    return 1
  })()`)
  // 点「获取验证码」—— 页面把演示码 123456 填进 #sms
  const send = await findButton(tab, '[data-page="phone"].on button', '获取验证码')
  await realClick(tab, send.x, send.y)
  for (let t = 0; t < 4000; t += 250) {
    if (await tab.evalJS(`(document.querySelector('[data-page="phone"].on #sms') || {}).value === '123456'`)) break
    await sleep(250)
  }
  const submit = await findButton(tab, '[data-page="phone"].on button.btn.btn-primary.btn-block', '登录')
  await realClick(tab, submit.x, submit.y)
  return waitToken(tab)
}

/** 在个人中心页点身份卡里的「退出登录」（真鼠标）。 */
async function uiLogout(tab) {
  await tab.evalJS(`location.hash = '#/customer/user'; 1`)
  for (let t = 0; t < 8000; t += 250) {
    if (await tab.evalJS(`!!document.querySelector('.id-actions .btn.ghost')`)) break
    await sleep(250)
  }
  const b = JSON.parse(await tab.evalJS(`(() => {
    const el = document.querySelector('.id-actions .btn.ghost')
    if (!el) return JSON.stringify({ found: false })
    const r = el.getBoundingClientRect()
    return JSON.stringify({ found: true, x: r.left + r.width / 2, y: r.top + r.height / 2, text: el.textContent.trim() })
  })()`))
  if (!b.found) throw new Error('没找到退出登录按钮')
  await realClick(tab, b.x, b.y)
  for (let t = 0; t < 8000; t += 250) {
    const v = await tab.evalJS(`sessionStorage.getItem('authToken') || ''`).catch(() => '')
    if (!v) return true
    await sleep(250)
  }
  return false
}

const idName = (tab) => tab.evalJS(`(() => { const el = document.querySelector('.id-name'); return el ? el.textContent.trim() : '' })()`)
/** 个人中心页（身份卡所在页）—— 登录后落点不在这里，断言前必须先导航过去。 */
async function gotoUser(tab) {
  await tab.evalJS(`location.hash = '#/customer/user'; 1`)
  for (let t = 0; t < 8000; t += 250) {
    if (await tab.evalJS(`!!document.querySelector('.id-name')`)) return true
    await sleep(250)
  }
  return false
}
const tok = (tab) => tab.evalJS(`sessionStorage.getItem('authToken') || ''`)

/** 新开一个「Ctrl+T 式」标签页（无 opener ⇒ sessionStorage 为空）。 */
async function newFreshTab(port, hash = '#/customer/user') {
  const before = await getJson(`http://127.0.0.1:${port}/json/list`)
  const r = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(WEB + '/' + hash)}`, { method: 'PUT' })
  if (!r.ok) { // 老版本 Edge/Chrome 用 GET
    await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(WEB + '/' + hash)}`)
  }
  const seen = new Set(before.map((t) => t.id))
  let page = null
  for (let t = 0; t < 100; t++) {
    const list = await getJson(`http://127.0.0.1:${port}/json/list`)
    page = list.find((x) => x.type === 'page' && !seen.has(x.id) && x.url.startsWith(WEB))
    if (page) break
    await sleep(100)
  }
  if (!page) throw new Error('新标签页没出现')
  return attach(page)
}

const R = report('多标签页隔离（真 UI）')
let b = null
const extras = []
try {
  b = await open({ w: 1440, h: 1000 })
  await b.goto(WEB + '/#/login', 1500)

  // ── 0) 原页 A：真 UI 登录 1111 ──
  const ta = await uiLogin(b, ACC_A)
  R.ok('A 登录拿到票', !!ta, ta ? ta.slice(0, 8) + '…' : '无')
  const pointer0 = await b.evalJS(`localStorage.getItem('authToken') || ''`)
  R.ok('A 的票同时是「最近登录」指针', pointer0 === ta)
  R.ok('A /user/me 200 且是 ' + ACC_A, JSON.stringify(await be('/user/me', ta)))
  await gotoUser(b)
  R.ok('A 页面身份名 = ' + ACC_A, (await idName(b)).startsWith(ACC_A), await idName(b))

  // ── 1) Ctrl+T 式新标签页 D：sessionStorage 为空 ⇒ 认领指针 + 补自己的票 ──
  const d = await newFreshTab(b.port)
  extras.push(d)
  const td = await waitTokenDiff(d, ta)
  R.ok('D 恢复出身份（认领指针）', !!td, td ? td.slice(0, 8) + '…' : '无')
  R.ok('D 的票 ≠ A 的票（每页一票）', !!td && td !== ta, { A: ta.slice(0, 8) + '…', D: td ? td.slice(0, 8) + '…' : '无' })
  await sleep(1500)
  R.ok('D 页面身份名 = ' + ACC_A, (await idName(d)).startsWith(ACC_A), await idName(d))

  // D 退出 → A 不能掉线
  const dOut = await uiLogout(d)
  R.ok('D 退出后本页票已清', dOut)
  const aAfterD = await be('/user/me', ta)
  R.ok('D 退出后 A 仍 200', aAfterD.http === 200 && aAfterD.success === true, aAfterD)
  R.ok('D 退出后 A 仍是 ' + ACC_A, aAfterD.username === ACC_A, aAfterD.username)

  // ── 2) window.open 式新标签页 B：sessionStorage 被复制（同一张票）⇒ 启动时必须换票 ──
  // ⚠️ 按「新出现的目标」找，不能按 URL 找 —— A 自己也在 #/customer/user，按 URL 会连到 A。
  const listBeforeOpen = await getJson(`http://127.0.0.1:${b.port}/json/list`)
  const seenIds = new Set(listBeforeOpen.map((x) => x.id))
  const opened = await b.evalJS(`(() => { const w = window.open(${JSON.stringify(WEB + '/#/customer/user')}, 'tabB'); return !!w })()`, true)
  R.ok('window.open 成功（带用户手势）', opened)
  let targetB = null
  for (let t = 0; t < 100; t++) {
    const list = await getJson(`http://127.0.0.1:${b.port}/json/list`)
    targetB = list.find((x) => x.type === 'page' && !seenIds.has(x.id) && x.url.startsWith(WEB))
    if (targetB) break
    await sleep(100)
  }
  R.ok('找到 window.open 出来的第二个标签页', !!targetB, targetB ? targetB.id.slice(0, 6) : '无')
  const tb0 = targetB ? await attach(targetB) : null
  if (tb0) extras.push(tb0)
  const tb = tb0 ? await waitTokenDiff(tb0, ta) : ''
  R.ok('B 启动后有票', !!tb, tb ? tb.slice(0, 8) + '…' : '无')
  R.ok('B 的票 ≠ A 的票（复制来的票被换掉）', !!tb && tb !== ta, { A: ta.slice(0, 8) + '…', B: tb ? tb.slice(0, 8) + '…' : '无' })
  await sleep(1500)
  R.ok('B 页面身份名 = ' + ACC_A, tb0 ? (await idName(tb0)).startsWith(ACC_A) : false, tb0 ? await idName(tb0) : '无 B')

  // B 退出 → A 不能掉线
  const bOut = tb0 ? await uiLogout(tb0) : false
  R.ok('B 退出后本页票已清', bOut)
  const aAfterB = await be('/user/me', ta)
  R.ok('B 退出后 A 仍 200', aAfterB.http === 200 && aAfterB.success === true, aAfterB)
  R.ok('B 退出后 A 仍是 ' + ACC_A, aAfterB.username === ACC_A, aAfterB.username)

  // ── 3) B 换号重新登录（用户报的那一步）──
  const tc = tb0 ? await uiLogin(tb0, ACC_B) : ''
  R.ok('B 换号登录拿到新票', !!tc && tc !== ta, tc ? tc.slice(0, 8) + '…' : '无')
  await sleep(1000)
  const meC = await be('/user/me', tc)
  R.ok('B 新票身份 = ' + ACC_B, meC.success === true && meC.username === ACC_B, meC)
  const aFinal = await be('/user/me', ta)
  R.ok('B 换号后 A 仍 200', aFinal.http === 200 && aFinal.success === true, aFinal)
  R.ok('B 换号后 A 仍是 ' + ACC_A, aFinal.username === ACC_A, aFinal.username)

  await b.shot('_work/multitab-A.png')
  if (tb0) await tb0.send('Page.captureScreenshot', { format: 'png' }).then((r) => fs.writeFileSync('_work/multitab-B.png', Buffer.from(r.data, 'base64'))).catch(() => {})
} catch (e) {
  console.log('探针异常：', e && e.message || e)
  R.ok('探针未抛异常', false, e && e.message)
} finally {
  for (const x of extras) x.close()
  if (b) await b.close()
}
process.exit(R.dump() ? 1 : 0)
