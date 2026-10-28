// probe-chat-nav-graph.mjs —— 全局路由图 + 自动寻路真链验收（2026-10-22）
//
// 验的是「模型只说去哪，前端自己算怎么走」：
//   ① 模型返回的 action = { type:'navigate', targetPath }，**不返回步骤**；
//   ② 点按钮后画面**留在当前页**高亮真实入口，用户点「下一步」才换页；
//   ③ 每一步的锚点都是页面上真实可点的 DOM（顶栏 / 个人中心快捷入口）；
//   ④ 冷问题落到真实页面，没有的页面（钱包）如实说明；
//   ⑤ 评论不再导航：走 COMMENT_QUERY 真查 comment 表（2026-10-22 工具挂载）。
//
// 真 dev server + 真后端 + 真模型，不 mock 任何请求。
// ⚠️ 模型有 3s 超时降级：超时时后端用 RouteCatalog 的「用户说」说法兜底，落点不变。
//    探针按**接口返回的 targetPath** 断言，不按耗时断言。
// 用法：node scripts/probes/probe-chat-nav-graph.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-nav-graph'
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
  await sleep(700)
}

async function openPanel() {
  if (await b.evalJS(`!!document.querySelector('.chat-root .panel')`)) return true
  await b.evalJS(`document.querySelector('.chat-root .ball')?.click()`)
  const up = await waitFor(`document.querySelector('.chat-root .panel')`, 5000)
  await sleep(300)
  return up
}

/** 发一句话，返回最后一次 /chat/ask 的响应体（含 intent / action）。 */
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
  // 打字机没走完时动作按钮还没渲染 ⇒ 点最后一条气泡跳过动画。
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1]?.click() })()`)
  await sleep(350)
  return { done, body: api.get('last')?.body || null }
}

/** 点最后一条客服消息里的动作按钮。 */
async function clickAction() {
  const hit = await waitFor(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; const last = bots[bots.length - 1]; return !!last?.querySelector('.tour-btn') })()`, 8000)
  if (!hit) return { clicked: false, label: '' }
  const label = await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; return bots[bots.length - 1].querySelector('.tour-btn').textContent.trim() })()`)
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1].querySelector('.tour-btn')?.click() })()`)
  return { clicked: true, label }
}

// 2026-10-22：driver 自带 popover 已隐藏，控件全部在 `.tour-bar`（贴着飞过去的悬浮球）。
const barGone = `!document.querySelector('.tour-bar')`
const active = (sel) => `document.querySelector(${JSON.stringify(sel)})?.classList.contains('driver-active-element')`
const progress = `document.querySelector('.tour-bar-step')?.textContent?.trim() || ''`
const title = `document.querySelector('.tour-bar-title')?.textContent?.trim() || ''`

async function clickNext() {
  await b.evalJS(`document.querySelector('.tour-bar-next')?.click()`)
  await sleep(300)
}

async function waitStep(sel, progressText, timeout = 12000) {
  const hit = await waitFor(`${active(sel)} && (${progress}) === ${JSON.stringify(progressText)}`, timeout)
  return {
    hit,
    progress: await b.evalJS(progress),
    title: await b.evalJS(title),
    hash: await b.evalJS('location.hash'),
  }
}

async function closeTour() {
  if (!(await b.evalJS(barGone))) {
    await b.evalJS(`document.querySelector('.tour-bar-skip')?.click()`)
    // 跳过 ⇒ 球滑回右下角（0.8s），期间 `.ball` 仍 pointer-events:none。
    await sleep(950)
  }
  await b.evalJS(`document.querySelector('.chat-root .ball')?.click()`)
  await sleep(250)
}

/**
 * 一段「问 → 点按钮 → 走完 BFS 步」的标准验收。
 * @param {string} question 用户原话
 * @param {string} target   期望 targetPath
 * @param {{hops:number, anchors:string[], arrived:string, shot:string, replyHas?:string}} spec
 */
async function navCase(question, target, spec) {
  const from = await b.evalJS('location.hash')
  const { done, body } = await sendChat(question)
  const action = body?.action || null
  // 语义路由上线后，部分问句不再落到 GUIDE_NAVIGATION，而是更精确的业务意图
  // （改商品价 ⇒ GOODS_MANAGE，管钱 ⇒ ASSET_QUERY）。落点与「不给步骤」的契约不变。
  const wantIntent = spec.intent || 'GUIDE_NAVIGATION'
  ok(`「${question}」→ ${target}（模型给 target_path，不给步骤）`,
    done && body?.intent === wantIntent && action?.type === 'tour'
      && action?.targetPath === target && !Array.isArray(body?.steps),
    { intent: body?.intent, action, steps: body?.steps ?? null })

  if (spec.replyHas) {
    const reply = await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; return bots[bots.length - 1]?.querySelector('.txt')?.textContent || '' })()`)
    ok(`「${question}」回复如实说明：${spec.replyHas}`, reply.includes(spec.replyHas), { reply: reply.slice(0, 120) })
  }

  const clicked = await clickAction()
  const total = spec.hops + 1
  const s1 = await waitStep(spec.anchors[0], `第 1 步 / 共 ${total} 步`)
  ok(`「${question}」点按钮后仍停在 ${from}，高亮真实入口`,
    clicked.clicked && s1.hit && s1.hash === from,
    { clicked, ...s1 })

  let step = s1
  for (let i = 1; i <= spec.hops; i++) {
    await clickNext()
    const expectHash = i === spec.hops ? `#${target}` : null
    const prev = `第 ${i + 1} 步 / 共 ${total} 步`
    step = await waitStep(spec.anchors[i] || spec.arrived, prev)
    ok(`「${question}」第 ${i + 1} 步：${expectHash ? `到达 ${target}` : '按图换页'}（${spec.anchors[i] || spec.arrived}）`,
      step.hit && (!expectHash || step.hash === expectHash),
      { ...step, expectHash })
  }

  await b.shot(path.join(OUT, spec.shot))
  await closeTour()
  return { body, action }
}

try {
  const h = await freshLogin('chenguang_parts', CREDS['chenguang_parts'])
  ok('商家登录落 /merchant', h === '#/merchant', { hash: h })
  await waitFor(`document.querySelector('#tour-entry-merchant') && document.querySelector('.chat-root .ball')`, 12000)

  // ── ① 冷问题：店铺数据 → 经营数据 ────────────────────────────────────────
  await navCase('怎么查看店铺数据', '/merchant/stats', {
    hops: 2,
    anchors: ['#tour-entry-merchant', '[data-nav-to="/merchant/stats"]', '.ms-page'],
    arrived: '.ms-page',
    shot: 'nav-01-stats-arrived.png',
  })

  // ── ② 退款：商家 → 售后工作台 ───────────────────────────────────────────
  await goHash('#/merchant')
  await navCase('怎么处理退款', '/merchant/after-sales', {
    hops: 2,
    anchors: ['#tour-entry-merchant', '[data-nav-to="/merchant/after-sales"]', '.order-center-page'],
    arrived: '.order-center-page',
    shot: 'nav-02-refund-arrived.png',
  })

  // ── ③ 改价：模型推理到「查看商品」而不是编一个编辑页 ────────────────────
  await goHash('#/merchant')
  await navCase('我想改一下那个 299 元耳机的价格', '/merchant/user/list', {
    intent: 'GOODS_MANAGE',
    hops: 2,
    anchors: ['#tour-entry-merchant', '[data-nav-to="/merchant/user/list"]', '.uc-main'],
    arrived: '.uc-main',
    shot: 'nav-03-price-list-arrived.png',
  })

  // ── ④ 评论：不再是导航，而是真查 comment 表（2026-10-22 工具挂载） ───────
  // 换成有真实评论的商家账号：chenguang_parts 名下 0 条，用他验不出「播报真数据」。
  {
    const hm = await freshLogin('yunqi_digital', CREDS['yunqi_digital'])
    ok('有评论的商家登录落 /merchant', hm === '#/merchant', { hash: hm })
    await waitFor(`document.querySelector('.chat-root .ball')`, 12000)
    const { done, body } = await sendChat('我要看评论')
    const reply = await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; return bots[bots.length - 1]?.querySelector('.txt')?.textContent || '' })()`)
    const data = body?.data || {}
    ok('「我要看评论」⇒ COMMENT_QUERY（查真实评论，不返回 navigate）',
      done && body?.intent === 'COMMENT_QUERY' && !body?.action,
      { intent: body?.intent, action: body?.action ?? null })
    ok('商家评论播报来自数据库：总数 / 最近一条 / 回复状态',
      done && data.scope === 'merchant' && data.total > 0
        && Array.isArray(data.comments) && data.comments.length > 0
        && data.comments[0].content && typeof data.comments[0].merchantReplied === 'boolean'
        && reply.includes(String(data.total)),
      { total: data.total, unanswered: data.unanswered, first: data.comments?.[0], reply: reply.slice(0, 160) })
    await b.shot(path.join(OUT, 'nav-04-merchant-comments.png'))
    await closeTour()
    // 回到原账号，后续用例的锚点与身份不变。
    await freshLogin('chenguang_parts', CREDS['chenguang_parts'])
    await waitFor(`document.querySelector('#tour-entry-merchant') && document.querySelector('.chat-root .ball')`, 12000)
  }

  // ── ⑤ 管理钱：没有钱包页 ⇒ 会员中心 ─────────────────────────────────────
  await goHash('#/merchant')
  await navCase('我要管理我的钱', '/merchant/user/member', {
    intent: 'ASSET_QUERY',
    hops: 2,
    anchors: ['#tour-entry-merchant', '[data-nav-to="/merchant/user/member"]', '.uc-main'],
    arrived: '.uc-main',
    shot: 'nav-05-wallet-member.png',
    replyHas: '钱包',
  })

  // ── ⑥ 上架商品：模型只给 /merchant/user/add，前端接已有的 7 步表单流 ─────
  await goHash('#/merchant')
  {
    const from = await b.evalJS('location.hash')
    const { done, body } = await sendChat('怎么上架商品')
    ok('「怎么上架商品」→ /merchant/user/add',
      done && body?.intent === 'GUIDE_NAVIGATION' && body?.action?.type === 'tour'
        && body?.action?.targetPath === '/merchant/user/add',
      { intent: body?.intent, action: body?.action })
    const clicked = await clickAction()
    const s1 = await waitStep('#tour-entry-merchant', '第 1 步 / 共 7 步')
    ok('发布流：点按钮后仍停在当前页，第 1 步高亮「用户中心」',
      clicked.clicked && s1.hit && s1.hash === from, { clicked, ...s1 })
    await b.shot(path.join(OUT, 'nav-06-publish-entry.png'))
    await closeTour()
  }

  // ── ⑦ 客户壳：钱包 / 退款 / 评论的最近真实页 ────────────────────────────
  const hc = await freshLogin('1111', CREDS['1111'])
  ok('客户登录落 /customer', hc === '#/customer', { hash: hc })
  await waitFor(`document.querySelector('#tour-entry-merchant') && document.querySelector('.chat-root .ball')`, 12000)

  await goHash('#/customer')
  await navCase('我要管理我的钱', '/customer/user/member', {
    intent: 'ASSET_QUERY',
    hops: 2,
    anchors: ['#tour-entry-merchant', '[data-nav-to="/customer/user/member"]', '.uc-main'],
    arrived: '.uc-main',
    shot: 'nav-07-customer-wallet.png',
    replyHas: '钱包',
  })

  await goHash('#/customer')
  await navCase('怎么处理退款', '/customer/orders', {
    hops: 2,
    anchors: ['#tour-entry-merchant', '[data-nav-to="/customer/orders"]', '.ordermine-page'],
    arrived: '.ordermine-page',
    shot: 'nav-08-customer-refund.png',
  })

  // 客户「看评论」同样查真实数据：1111 账号名下有大量历史评论。
  await goHash('#/customer')
  {
    const { done, body } = await sendChat('我要看评论')
    const reply = await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; return bots[bots.length - 1]?.querySelector('.txt')?.textContent || '' })()`)
    const data = body?.data || {}
    ok('客户「我要看评论」⇒ COMMENT_QUERY，按登录名查自己的评论',
      done && body?.intent === 'COMMENT_QUERY' && data.scope === 'customer' && data.total > 0
        && Array.isArray(data.comments) && data.comments.length > 0 && !body?.action,
      { intent: body?.intent, total: data.total, first: data.comments?.[0], reply: reply.slice(0, 160) })
    ok('客户评论播报带真实条数与商家回复状态',
      done && reply.includes(String(data.total))
        && Array.isArray(data.comments) && typeof data.comments[0]?.merchantReplied === 'boolean',
      { reply: reply.slice(0, 160) })
    await b.shot(path.join(OUT, 'nav-09-customer-comments.png'))
    await closeTour()
  }

  ok('页面无未捕获异常', pageErrors.length === 0, { pageErrors })
} catch (e) {
  ok('探针异常', false, { error: String(e && e.message ? e.message : e) })
} finally {
  const passed = rows.filter((r) => r.pass).length
  console.log(`\nnav: ${passed}/${rows.length} PASS`)
  fs.writeFileSync(path.join(OUT, 'probe-nav.txt'),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  ${JSON.stringify(r.detail)}`).join('\n') + '\n'
    + `\nnav: ${passed}/${rows.length} PASS\n`)
  await sleep(200)
  process.exit(passed === rows.length ? 0 : 1)
}
