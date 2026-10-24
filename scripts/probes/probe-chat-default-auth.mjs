// probe-chat-default-auth.mjs —— default profile（app.auth.enabled=true）匿名客服链路
//
// 只验 UI 这一条：匿名在公开首页点客服球发消息 ⇒
//   ① /chat/ask 真实返回 401（不 mock）；
//   ② 气泡显示 ChatWidget 的「登录后才能查询…」；
//   ③ 不发生全局 401 跳转（#/login 不出现，allowUnauthorized 生效）。
//
// 另外三条（匿名 curl 401、客户订单 200、商家工作台 targetPath）由
// scripts/probes/probe-chat-default-auth.py 取证。
//
// 用法：node scripts/probes/probe-chat-default-auth.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-default-auth'
fs.mkdirSync(OUT, { recursive: true })
fs.mkdirSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/default-profile', { recursive: true })

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

const b = await open({ w: 1440, h: 900, headed: false })
const pageErrors = []
const chatResponses = []
b.ws.on('message', (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method === 'Runtime.exceptionThrown') {
    pageErrors.push(m.params?.exceptionDetails?.text || 'exception')
  }
  if (m.method === 'Network.responseReceived' && m.params.response.url.includes('/chat/ask')) {
    chatResponses.push({ requestId: m.params.requestId, status: m.params.response.status, url: m.params.response.url })
  }
})
await b.send('Network.enable', {})

async function waitFor(expr, timeout = 15000, step = 150) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function centerOf(selector) {
  return b.evalJS(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)})
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height }
  })()`)
}

async function clickPoint(p) {
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x, y: p.y })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button: 'left', clickCount: 1 })
}

await b.goto(BASE + '/#/', 2500)
const beforeHash = await b.evalJS('location.hash')
const ball = await waitFor(`document.querySelector('.chat-root .ball')`, 20000)
ok('匿名首页出现客服球', ball, { hash: beforeHash })
if (!ball) {
  await b.shot(path.join(OUT, 'anonymous-ui-no-ball.png'))
  await b.close()
  process.exit(1)
}

const ballPoint = await centerOf('.chat-root .ball')
await clickPoint(ballPoint)
const panel = await waitFor(`document.querySelector('.chat-root .panel')`, 5000)
ok('点击客服球打开面板', panel, { point: ballPoint })

const beforeBots = await b.evalJS(`document.querySelectorAll('.bubble.bot').length`)
await b.evalJS(`(() => {
  const el = document.querySelector('.composer textarea')
  const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
  set.call(el, '我的订单到哪了')
  el.dispatchEvent(new Event('input', { bubbles: true }))
})()`)
await sleep(150)
await b.evalJS(`document.querySelector('.composer').requestSubmit()`)

const replied = await waitFor(
  `document.querySelectorAll('.bubble.bot').length > ${beforeBots}
   && !document.querySelector('.bubble.bot .dots')`,
  20000,
)
await sleep(400)
const afterHash = await b.evalJS('location.hash')
const lastBot = await b.evalJS(`(() => {
  const bots = [...document.querySelectorAll('.bubble.bot')]
  return bots.length ? bots[bots.length - 1].textContent.trim() : ''
})()`)
const loginFormVisible = await b.evalJS(`!!document.querySelector('#acc') && !!document.querySelector('#pwd')`)
const resp = chatResponses[chatResponses.length - 1] || null

ok('匿名发送后拿到回复', replied)
ok('/chat/ask 真实返回 401', resp?.status === 401, resp)
ok('气泡显示「登录后才能查询」', String(lastBot).includes('登录后才能查询'), { lastBot })
ok('不跳全局登录页', !/#\/login/.test(String(afterHash)) && !loginFormVisible,
  { beforeHash, afterHash, loginFormVisible })
ok('页面无未捕获异常', pageErrors.length === 0, { pageErrors })

await b.shot(path.join(OUT, 'anonymous-ui-401.png'))
const report = rows.map((r) =>
  `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  ' + JSON.stringify(r.detail) : ''}`).join('\n')
fs.writeFileSync(path.join(OUT, 'anonymous-ui-report.txt'),
  report + `\n\n${rows.filter((r) => r.pass).length}/${rows.length} PASS\n`, 'utf-8')
await b.close()
const failed = rows.filter((r) => !r.pass).length
console.log(`\n${rows.length - failed}/${rows.length} PASS`)
process.exit(failed ? 1 : 0)
