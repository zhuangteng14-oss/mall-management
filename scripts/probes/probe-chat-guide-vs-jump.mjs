// probe-chat-guide-vs-jump.mjs —— 「默认引导 + 跳过直达」真链验收（2026-10-22）
//
// 验的是用户裁决的交互流：
//   ① 问「怎么申请品牌」⇒ 对话里给【开始引导】胶囊（不是直接跳页）；
//   ② 点【开始引导】⇒ **仍停在当前页**、悬浮球飞过去高亮真实入口，`.tour-bar` 里
//      除【下一步】还有弱化文字按钮【跳过引导，直接前往】；
//   ③ 点【跳过引导，直接前往】⇒ 漫游立刻消失（无 `.driver-active` / `.tour-bar`），
//      并且真的落在最终路径（入驻申请页）；
//   ④ 问「直接带我去申请品牌」⇒ 给【一键直达】胶囊；点一下瞬间到申请页，**全程不触发漫游**。
//
// 真 dev server + 真后端 + 真模型，不 mock 任何请求。
// ⚠️ 点按钮一律用 CDP 真鼠标（`el.click()` 绕过命中测试；driver 的 `.driver-active *`
//    会把 body 级控件的指针事件关掉，探针曾经因此假绿）。
// 用法：node scripts/probes/probe-chat-guide-vs-jump.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-guide-vs-jump'
fs.rmSync(OUT, { recursive: true, force: true })
fs.mkdirSync(OUT, { recursive: true })

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900, headed: false })
const pageErrors = []
const api = new Map()
b.ws.on('message', async (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method === 'Page.javascriptDialogOpening') {
    b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
  if (m.method === 'Runtime.exceptionThrown') {
    pageErrors.push(m.params?.exceptionDetails?.text || 'exception')
  }
  if (m.method === 'Network.responseReceived' && m.params.response.url.includes('/chat/ask')) {
    api.set(m.params.requestId, { url: m.params.response.url })
  }
  if (m.method === 'Network.loadingFinished' && api.has(m.params.requestId)) {
    const entry = api.get(m.params.requestId)
    api.delete(m.params.requestId)
    try {
      const { body } = await b.send('Network.getResponseBody', { requestId: m.params.requestId })
      entry.body = JSON.parse(body)
    } catch (e) { entry.body = null }
    api.set('last', entry)
  }
})
await b.send('Runtime.enable', {}).catch(() => {})
await b.send('Network.enable', {}).catch(() => {})

async function waitFor(expr, timeout = 15000, step = 150) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function freshLogin(acc, pwd) {
  await b.goto(BASE + '/#/login', 800)
  await b.evalJS(`(() => { try { localStorage.clear() } catch (e) {}; try { sessionStorage.clear() } catch (e) {} })()`)
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(`document.querySelector('#acc') && document.querySelector('#pwd')`, 15000)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), ${JSON.stringify(acc)})
    set(document.querySelector('#pwd'), ${JSON.stringify(pwd)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录' && !e.disabled)
    btn?.click(); return !!btn
  })()`)
  for (let i = 0; i < 24; i++) {
    await sleep(500)
    const hash = await b.evalJS('location.hash')
    if (/^#\/(merchant|customer)/.test(hash)) return hash
  }
  return null
}

async function goHash(hash) {
  await b.evalJS(`location.hash = ${JSON.stringify(hash)}`)
  await sleep(800)
}

async function openPanel() {
  if (await b.evalJS(`!!document.querySelector('.chat-root .panel')`)) return true
  const point = await centerOf('.chat-root .ball')
  if (!point) return false
  await clickPoint(point)
  const up = await waitFor(`document.querySelector('.chat-root .panel')`, 5000)
  await sleep(300)
  return up
}

/** 目标元素中心点（视口语义）。 */
async function centerOf(selector, lastBubbleBtn = false) {
  const expr = lastBubbleBtn
    ? `(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; const el = bots[bots.length - 1]?.querySelector('.tour-btn'); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height } })()`
    : `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height } })()`
  return b.evalJS(expr)
}

/**
 * **真鼠标**点击（memory：`el.click()` 绕过命中测试）。
 * `button:'left'` 不能省 —— 缺省的 dispatchMouseEvent 不带按键，driver 的命中测试不认。
 */
async function clickPoint(p) {
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x, y: p.y, buttons: 0 })
  await sleep(80)
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button: 'left', buttons: 1, clickCount: 1 })
  await sleep(60)
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button: 'left', buttons: 0, clickCount: 1 })
  await sleep(150)
}

async function sendChat(text) {
  await openPanel()
  const before = await b.evalJS(`document.querySelectorAll('.bubble.bot').length`)
  api.delete('last')
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })()`)
  await sleep(120)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const done = await waitFor(
    `document.querySelectorAll('.bubble.bot').length > ${before} && !document.querySelector('.bubble.bot .dots')`,
    20000,
  )
  // 打字机没走完时动作按钮还没渲染 ⇒ 点最后一条气泡跳过动画（这一步是页面内的，不涉及命中测试）。
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1]?.click() })()`)
  await sleep(400)
  return { done, body: api.get('last')?.body || null }
}

const noTour = `!document.querySelector('.tour-bar') && !document.body.classList.contains('driver-active')`

try {
  const h = await freshLogin('1111', CREDS['1111'])
  ok('客户登录落 /customer', h === '#/customer', { hash: h })
  await waitFor(`document.querySelector('.chat-root .ball')`, 12000)

  // ── ① 默认「怎么去」⇒【开始引导】 ──────────────────────────────────────────
  await goHash('#/customer')
  const g = await sendChat('怎么申请品牌')
  const gAction = g.body?.action || null
  ok('「怎么申请品牌」⇒ 默认 GUIDE_NAVIGATION / OPEN_STORE，给【开始引导】',
    g.done && ['GUIDE_NAVIGATION', 'OPEN_STORE'].includes(g.body?.intent)
      && gAction?.type === 'tour'
      && gAction?.targetPath === '/customer/user/apply'
      && gAction?.label === '开始引导',
    { intent: g.body?.intent, action: gAction })
  ok('引导话术：好的，我来一步步教您。点击下方按钮开始。',
    String(g.body?.reply || '').includes('好的，我来一步步教您。点击下方按钮开始。'),
    { reply: String(g.body?.reply || '').slice(0, 80) })

  const from = await b.evalJS('location.hash')
  const actionPoint = await centerOf('.', true)
  ok('【开始引导】胶囊可点（有实际尺寸）', !!actionPoint && actionPoint.w > 0, actionPoint)
  await clickPoint(actionPoint)

  const barUp = await waitFor(`document.querySelector('.tour-bar .tour-bar-skip')`, 15000)
  await sleep(300)
  const barPoint = await centerOf('.tour-bar-skip')
  const barHash = await b.evalJS('location.hash')
  const barTitle = await b.evalJS(`document.querySelector('.tour-bar-title')?.textContent?.trim() || ''`)
  const skipText = await b.evalJS(`document.querySelector('.tour-bar-skip')?.textContent?.trim() || ''`)
  ok('点【开始引导】后仍停在当前页，飞行小助手弹出控制条',
    barUp && barHash === from && !!barTitle, { from, barHash, barTitle })
  ok('控制条里有弱化文字按钮【跳过引导，直接前往】',
    skipText === '跳过引导，直接前往' && !!barPoint, { skipText, barPoint })
  await b.shot(path.join(OUT, 'guide-01-tour-bar.png'))

  // ── ② 点【跳过引导，直接前往】⇒ 漫游消失 + 直达最终页 ────────────────────
  await clickPoint(barPoint)
  const arrived = await waitFor(`location.hash.startsWith('#/customer/user/apply')`, 8000)
  const tourGone = await waitFor(noTour, 4000)
  ok('点【跳过引导】⇒ 瞬间落在入驻申请页', arrived, { hash: await b.evalJS('location.hash') })
  ok('点【跳过引导】⇒ 漫游消失（无 .tour-bar / .driver-active）', tourGone,
    { bar: await b.evalJS(`!!document.querySelector('.tour-bar')`), driver: await b.evalJS(`document.body.classList.contains('driver-active')`) })
  await sleep(600)
  await b.shot(path.join(OUT, 'guide-02-after-skip.png'))

  // ── ③ 明说「直接去」⇒【一键直达】，不触发漫游 ───────────────────────────
  await goHash('#/customer')
  await sleep(500)
  const d = await sendChat('直接带我去申请品牌')
  const dAction = d.body?.action || null
  ok('「直接带我去申请品牌」⇒ DIRECT 口令，给【一键直达】',
    d.done && dAction?.type === 'jump'
      && dAction?.targetPath === '/customer/user/apply'
      && dAction?.label === '一键直达',
    { intent: d.body?.intent, action: dAction })
  ok('直达话术：已为您准备好直达入口，点击即可前往。',
    String(d.body?.reply || '').includes('已为您准备好直达入口，点击即可前往。'),
    { reply: String(d.body?.reply || '').slice(0, 80) })

  const jumpPoint = await centerOf('.', true)
  ok('【一键直达】胶囊可点（有实际尺寸）', !!jumpPoint && jumpPoint.w > 0, jumpPoint)
  const t0 = Date.now()
  await clickPoint(jumpPoint)
  const jumped = await waitFor(`location.hash.startsWith('#/customer/user/apply')`, 5000, 80)
  const elapsed = Date.now() - t0
  ok('点【一键直达】⇒ 瞬间到申请页（≤ 3s，无逐步引导）', jumped && elapsed <= 3000, { elapsed, hash: await b.evalJS('location.hash') })
  // 至少观察 1s：若漫游会被触发，这段时间里 `.tour-bar` 一定已经出现。
  await sleep(1000)
  const stillNoTour = await b.evalJS(noTour)
  ok('直达全程不触发漫游（1s 内无 .tour-bar / .driver-active）', stillNoTour,
    { bar: await b.evalJS(`!!document.querySelector('.tour-bar')`), driver: await b.evalJS(`document.body.classList.contains('driver-active')`) })
  await b.shot(path.join(OUT, 'jump-01-arrived.png'))

  ok('全程无 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3))
} catch (e) {
  ok('探针异常中止', false, String(e && e.stack || e))
} finally {
  await b.close()
}

const failed = rows.filter((r) => !r.pass).length
const lines = rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  ' + JSON.stringify(r.detail) : ''}`)
lines.push('', `合计 ${rows.length} 项 / ${failed} 失败`, `页面截图：${OUT}`)
fs.writeFileSync(path.join(OUT, 'guide-vs-jump-report.txt'), lines.join('\r\n') + '\r\n', 'utf8')
console.log('\n' + lines.join('\n'))
process.exit(failed ? 1 : 0)
