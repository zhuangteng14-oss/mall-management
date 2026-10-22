// probe-chat-product-aftersales.mjs —— 裸页（商品详情）问「售后」的跨页自动拼接（2026-10-22）
//
// 用户裁决的场景：人在 `/product`（商品详情，壳外裸页），问售后 ——
// 这一页没有任何售后入口，但**不能**回答「当前页没有入口」；NavGraph 必须先把人带回商城首页：
//
//   商品详情 →（左上角「‹ 返回」）→ 商家首页 →（用户中心）→ 个人中心 →（售后工作台）→ 售后工作台
//
// 本探针验：
//   ① 点动作按钮后**仍停在 /product**，第 1 步高亮的就是 `.nav-back`（返回首页），不是瞬移；
//   ② 三跳之后真的到达 /merchant/after-sales，进度文字与实际步数一致；
//   ③ 全程只有右下角那一颗悬浮球在飞（不新建飞行 DOM），结束时飞回原位；
//   ④ BFS 本身对 /product 有解（纯函数断言，不依赖模型）。
//
// 真 dev server + 真后端 + 真模型，不 mock 任何请求。
// 用法：node scripts/probes/probe-chat-product-aftersales.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-product-aftersales'
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

async function openPanel() {
  if (await b.evalJS(`!!document.querySelector('.chat-root .panel')`)) return true
  await b.evalJS(`document.querySelector('.chat-root .ball')?.click()`)
  const up = await waitFor(`document.querySelector('.chat-root .panel')`, 5000)
  await sleep(300)
  return up
}

/** 发一句话，返回最后一次 /chat/ask 的响应体。 */
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
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1]?.click() })()`)
  await sleep(350)
  return { done, body: api.get('last')?.body || null }
}

async function clickAction() {
  const hit = await waitFor(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; const last = bots[bots.length - 1]; return !!last?.querySelector('.tour-btn') })()`, 8000)
  if (!hit) return { clicked: false, label: '' }
  const label = await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; return bots[bots.length - 1].querySelector('.tour-btn').textContent.trim() })()`)
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1].querySelector('.tour-btn')?.click() })()`)
  return { clicked: true, label }
}

const active = (sel) => `document.querySelector(${JSON.stringify(sel)})?.classList.contains('driver-active-element')`
const progress = `document.querySelector('.tour-bar-step')?.textContent?.trim() || ''`
const title = `document.querySelector('.tour-bar-title')?.textContent?.trim() || ''`
const flowState = `sessionStorage.getItem('mall.chat.tourFlow.v1')`

async function stepAt(sel, progressText, timeout = 12000) {
  const hit = await waitFor(`${active(sel)} && (${progress}) === ${JSON.stringify(progressText)}`, timeout)
  return {
    hit,
    progress: await b.evalJS(progress),
    title: await b.evalJS(title),
    hash: await b.evalJS('location.hash'),
  }
}

async function clickNext() {
  await b.evalJS(`document.querySelector('.tour-bar-next')?.click()`)
  await sleep(250)
}

try {
  // ── 登录商家 → 从首页真实点进一件商品 ────────────────────────────────────
  const h = await freshLogin('chenguang_parts', CREDS['chenguang_parts'])
  ok('商家登录落 /merchant', h === '#/merchant', { hash: h })

  // ── ⓪ BFS 纯函数对照（页面已加载，走 Vite 的源模块） ─────────────────────
  const bfs = await b.evalJS(`(async () => {
    const m = await import('/src/config/navGraph.js')
    const hops = m.findShortestPath('/product', '/merchant/after-sales')
    const steps = m.buildNavSteps('/product', '/merchant/after-sales')
    return {
      hops: hops && hops.map((h) => h.from + ' → ' + h.to),
      selectors: steps && steps.map((s) => s.element),
      titles: steps && steps.map((s) => s.title),
    }
  })()`)
  ok('BFS：/product → /merchant/after-sales 有解，且第一跳是「‹ 返回」',
    !!bfs?.hops && bfs.hops[0] === '/product → /merchant' && bfs.selectors?.[0] === '.nav-back',
    bfs)
  const cardReady = await waitFor(`document.querySelector('a[href^="#/product?"]') && document.querySelector('.chat-root .ball')`, 15000)
  const productHref = await b.evalJS(`document.querySelector('a[href^="#/product?"]')?.getAttribute('href') || ''`)
  ok('首页有真实商品卡（带 7 个 query 的详情链接）', cardReady && productHref.includes('Id='), { productHref })
  if (!productHref) throw new Error('首页没有商品卡片，无法进入详情页')

  await b.evalJS(`document.querySelector('a[href^="#/product?"]')?.click()`)
  const onProduct = await waitFor(
    `location.hash.startsWith('#/product?') && document.querySelector('.goods-detail-page') && document.querySelector('.nav-back')`,
    15000,
  )
  await sleep(700)
  ok('已进入商品详情页（壳外裸页，聊天球仍在）',
    onProduct && !!(await b.evalJS(`document.querySelector('.chat-root .ball')`)),
    { hash: await b.evalJS('location.hash') })
  await b.shot(path.join(OUT, 'after-00-product.png'))

  // ── ① 在详情页问售后；点动作按钮后仍停在详情页、第一步指向返回 ──────────
  const { done, body } = await sendChat('我要处理售后')
  const action = body?.action || null
  ok('详情页问「售后」→ 后端给出 GUIDE_NAVIGATION（不回答「当前页没有入口」）',
    done && body?.intent === 'GUIDE_NAVIGATION' && action?.type === 'tour' && !!action?.targetPath,
    { intent: body?.intent, action, reply: (body?.reply || '').slice(0, 80) })

  const clicked = await clickAction()
  const s1 = await stepAt('.nav-back', '第 1 步 / 共 4 步')
  ok('① 点按钮后仍在 /product，第一步高亮左上角「‹ 返回」',
    clicked.clicked && s1.hit && s1.hash.startsWith('#/product?')
      && s1.title.includes('返回商城首页'),
    { clicked, ...s1 })
  await b.shot(path.join(OUT, 'after-01-step1-nav-back.png'))

  // ── ② 三跳：返回首页 → 用户中心 → 售后工作台 ─────────────────────────────
  await clickNext()
  const s2 = await stepAt('#tour-entry-merchant', '第 2 步 / 共 4 步')
  ok('② 第 2 步：点「返回」回到商家首页，高亮顶栏「用户中心」',
    s2.hit && s2.hash === '#/merchant', { ...s2 })
  await b.shot(path.join(OUT, 'after-02-step2-home.png'))

  await clickNext()
  const s3 = await stepAt('[data-nav-to="/merchant/after-sales"]', '第 3 步 / 共 4 步')
  ok('② 第 3 步：进个人中心，高亮「售后工作台」入口',
    s3.hit && s3.hash === '#/merchant/user', { ...s3 })
  await b.shot(path.join(OUT, 'after-03-step3-user-center.png'))

  await clickNext()
  const s4 = await stepAt('.order-center-page', '第 4 步 / 共 4 步')
  ok('② 第 4 步：真的到达 /merchant/after-sales（拼接成功，不是「没有入口」）',
    s4.hit && s4.hash === '#/merchant/after-sales' && s4.title.includes('售后工作台'),
    { ...s4 })
  await b.shot(path.join(OUT, 'after-04-arrived-after-sales.png'))

  // ── ③ 全屏只有一颗球；结束后飞回右下角 ───────────────────────────────────
  const ball = await b.evalJS(`(() => {
    const de = document.documentElement
    const all = [...document.querySelectorAll('.chat-root .ball')]
    const sprite = document.querySelectorAll('.tour-sprite').length
    const r = all[0]?.getBoundingClientRect()
    return {
      balls: all.length,
      sprites: sprite,
      left: r ? Math.round(r.left) : null,
      wantLeft: de.clientWidth - 24 - 56,
      wantTop: de.clientHeight - 24 - 56,
      top: r ? Math.round(r.top) : null,
    }
  })()`)
  ok('③ 漫游中全屏只有一颗绿球（0 个 .tour-sprite）',
    ball.balls === 1 && ball.sprites === 0, ball)

  await b.evalJS(`document.querySelector('.tour-bar-skip')?.click()`)
  await sleep(1100)
  const home = await b.evalJS(`(() => {
    const de = document.documentElement
    const sp = document.querySelector('.chat-root .ball')
    const r = sp.getBoundingClientRect()
    return {
      x: Math.round(r.left), y: Math.round(r.top),
      wantX: de.clientWidth - 24 - 56, wantY: de.clientHeight - 24 - 56,
      tourMode: document.querySelector('.chat-root').classList.contains('tour-mode'),
    }
  })()`)
  ok('③ 结束后同一颗球飞回右下角（transform 清空、tour-mode 摘掉）',
    home.tourMode === false && Math.hypot(home.x - home.wantX, home.y - home.wantY) <= 2,
    { home, flow: await b.evalJS(flowState) })

  ok('页面无未捕获异常', pageErrors.length === 0, { pageErrors })
} catch (e) {
  ok('探针异常', false, { error: String(e && e.message ? e.message : e) })
} finally {
  const passed = rows.filter((r) => r.pass).length
  console.log(`\nproduct-aftersales: ${passed}/${rows.length} PASS`)
  fs.writeFileSync(path.join(OUT, 'probe-product-aftersales.txt'),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  ${JSON.stringify(r.detail)}`).join('\n') + '\n'
    + `\nproduct-aftersales: ${passed}/${rows.length} PASS\n`)
  await sleep(200)
  process.exit(passed === rows.length ? 0 : 1)
}
