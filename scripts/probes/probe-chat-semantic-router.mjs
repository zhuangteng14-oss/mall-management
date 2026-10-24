// probe-chat-semantic-router.mjs —— 语义路由真链验收（2026-10-22）
//
// 验的是用户点名的三句话 + 两条边界：
//   ① 「怎么看我上架的商品」⇒ intent=GOODS_MANAGE，落 /merchant/user/list；
//      回复只有引导语，**不含**「收藏的商品」这类幽灵前缀；
//   ② 「怎么前往商家工作台」⇒ intent=MERCHANT_DASHBOARD，落 /merchant/user，不说「听不懂」；
//   ③ 「我花出去的钱去哪了」⇒ intent=ASSET_QUERY，落会员中心（余额 / 流水）；
//   ④ 「今天天气不错」⇒ 相似度 < 0.75，intent=CLARIFY，反问而不硬猜；
//   ⑤ 「直接带我去管理商品」⇒ 仍是 GOODS_MANAGE，但动作必须是 jump，点击不触发漫游。
//
// 真 dev server + 真后端 + 真 Embedding，不 mock 任何请求。
// ⚠️ 点按钮一律用 CDP 真鼠标（memory：`el.click()` 绕过命中测试）。
// 用法：node scripts/probes/probe-chat-semantic-router.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-semantic-router'
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
const MERCHANT = 'chenguang_parts'

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

async function centerOf(selector, lastBubbleBtn = false) {
  const expr = lastBubbleBtn
    ? `(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; const el = bots[bots.length - 1]?.querySelector('.tour-btn'); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height } })()`
    : `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height } })()`
  return b.evalJS(expr)
}

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
    25000,
  )
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1]?.click() })()`)
  await sleep(400)
  return { done, body: api.get('last')?.body || null }
}

const noTour = `!document.querySelector('.tour-bar') && !document.body.classList.contains('driver-active')`

try {
  const h = await freshLogin(MERCHANT, CREDS[MERCHANT])
  ok('商家登录落 /merchant', h === '#/merchant', { hash: h })
  await waitFor(`document.querySelector('.chat-root .ball')`, 12000)

  // ── ① GOODS_MANAGE ────────────────────────────────────────────────────────
  await goHash('#/merchant')
  const goods = await sendChat('怎么看我上架的商品')
  const goodsAction = goods.body?.action || null
  ok('「怎么看我上架的商品」⇒ GOODS_MANAGE + /merchant/user/list',
    goods.done && goods.body?.intent === 'GOODS_MANAGE'
      && goodsAction?.type === 'tour'
      && goodsAction?.targetPath === '/merchant/user/list',
    { intent: goods.body?.intent, action: goodsAction })
  ok('回复只有引导语，无「收藏的商品」幽灵前缀',
    String(goods.body?.reply || '') === '好的，我来一步步教您。点击下方按钮开始。'
      && !String(goods.body?.reply || '').includes('收藏'),
    { reply: String(goods.body?.reply || '').slice(0, 100) })
  await b.shot(path.join(OUT, 'semantic-01-goods-manage.png'))

  // 点【开始引导】⇒ 真漫游；跳过 ⇒ 真落到商品管理页。
  const gp = await centerOf('.', true)
  ok('GOODS_MANAGE 的【开始引导】胶囊可点', !!gp && gp.w > 0, gp)
  await clickPoint(gp)
  const bar = await waitFor(`document.querySelector('.tour-bar')`, 15000)
  ok('点【开始引导】后出现漫游控制条', bar)
  const skip = await centerOf('.tour-bar-skip')
  await clickPoint(skip)
  const landed = await waitFor(`location.hash === '#/merchant/user/list'`, 8000)
  const gone = await waitFor(noTour, 4000)
  ok('跳过引导 ⇒ 真落到 /merchant/user/list，漫游消失', landed && gone,
    { hash: await b.evalJS('location.hash'), bar: await b.evalJS(`!!document.querySelector('.tour-bar')`) })

  // ── ② MERCHANT_DASHBOARD ──────────────────────────────────────────────────
  await goHash('#/merchant')
  const dash = await sendChat('怎么前往商家工作台')
  const dashAction = dash.body?.action || null
  ok('「怎么前往商家工作台」⇒ MERCHANT_DASHBOARD + /merchant/user',
    dash.done && dash.body?.intent === 'MERCHANT_DASHBOARD'
      && dashAction?.type === 'tour'
      && dashAction?.targetPath === '/merchant/user',
    { intent: dash.body?.intent, action: dashAction })
  ok('不再答「听不懂」', !String(dash.body?.reply || '').includes('听不懂'),
    { reply: String(dash.body?.reply || '').slice(0, 100) })
  await b.shot(path.join(OUT, 'semantic-02-dashboard.png'))

  // ── ③ ASSET_QUERY ─────────────────────────────────────────────────────────
  const asset = await sendChat('我花出去的钱去哪了')
  const assetAction = asset.body?.action || null
  ok('「我花出去的钱去哪了」⇒ ASSET_QUERY + /merchant/user/member',
    asset.done && asset.body?.intent === 'ASSET_QUERY'
      && assetAction?.targetPath === '/merchant/user/member'
      && assetAction?.type === 'tour',
    { intent: asset.body?.intent, action: assetAction })
  await b.shot(path.join(OUT, 'semantic-03-asset.png'))

  // ── ④ 低于 0.75 ⇒ 澄清 ────────────────────────────────────────────────────
  const vague = await sendChat('今天天气不错')
  ok('「今天天气不错」⇒ CLARIFY（相似度 < 0.75 不硬猜）',
    vague.done && vague.body?.intent === 'CLARIFY'
      && String(vague.body?.reply || '').includes('请问您是想查订单'),
    { intent: vague.body?.intent, reply: String(vague.body?.reply || '').slice(0, 100) })
  ok('澄清时不返回 action（不擅自导航）', !vague.body?.action, { action: vague.body?.action ?? null })
  await b.shot(path.join(OUT, 'semantic-04-clarify.png'))

  // ── ⑤ 直接口令：GOODS_MANAGE + jump ───────────────────────────────────────
  await goHash('#/merchant')
  const direct = await sendChat('直接带我去管理商品')
  const directAction = direct.body?.action || null
  ok('「直接带我去管理商品」⇒ GOODS_MANAGE，但动作是 jump',
    direct.done && direct.body?.intent === 'GOODS_MANAGE'
      && directAction?.type === 'jump'
      && directAction?.targetPath === '/merchant/user/list'
      && directAction?.label === '一键直达',
    { intent: direct.body?.intent, action: directAction })
  const dp = await centerOf('.', true)
  const t0 = Date.now()
  await clickPoint(dp)
  const jumped = await waitFor(`location.hash === '#/merchant/user/list'`, 5000, 80)
  const elapsed = Date.now() - t0
  await sleep(1000)
  const stillNoTour = await b.evalJS(noTour)
  ok('点【一键直达】⇒ ≤3s 到商品管理页，且全程无漫游',
    jumped && elapsed <= 3000 && stillNoTour,
    { elapsed, hash: await b.evalJS('location.hash') })
  await b.shot(path.join(OUT, 'semantic-05-direct.png'))

  ok('全程无 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3))
} catch (e) {
  ok('探针异常中止', false, String(e && e.stack || e))
} finally {
  await b.close()
}

const failed = rows.filter((r) => !r.pass).length
const lines = rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  ' + JSON.stringify(r.detail) : ''}`)
lines.push('', `合计 ${rows.length} 项 / ${failed} 失败`, `页面截图：${OUT}`)
fs.writeFileSync(path.join(OUT, 'semantic-router-report.txt'), lines.join('\r\n') + '\r\n', 'utf8')
console.log('\n' + lines.join('\n'))
process.exit(failed ? 1 : 0)
