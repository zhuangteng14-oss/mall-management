// probe-chat-tour.mjs —— 智能客服 + 跨页漫游的界面验收（2026-10-21）
//
// 只验**前端**：`/chat/ask` 由 CDP 就地伪造（与既有页面探针同口径，不需要起后端）；
// 后端规则另有 `ChatIntentRecognizerTest` / 全量 `mvnw test` 兜底。
//
// 覆盖面：
//   ① /login 上**不出现**客服球（P0-3）；
//   ② /customer 上球存在 → 打开面板 → 查订单 → 打字机结束出订单卡（P0-2 的最近 3 笔）；
//   ③ 点「怎么开店」→ 出现「一键带我去开店」→ 点击后跨页到申请页并自动开 driver.js；
//   ④ 两步高亮（店铺名称 / 提交按钮）各自截图。
//
// 用法：先 `pnpm dev`（8080），再
//   node scripts/probes/probe-chat-tour.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-agent-tour'
fs.mkdirSync(OUT, { recursive: true })

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

const ORDER_REPLY = {
  success: true,
  intent: 'ORDER_QUERY',
  reply: '你最近有 3 笔订单，最新一笔是「无线蓝牙耳机 声谷标准版（单号 r2vfwK6z3E）」，实付 ¥299.00，下单时间 2026-10-18 14:22:10。当前状态：已发货（待收货）。',
  data: {
    total: 3,
    orders: [
      { orderId: 'r2vfwK6z3E', product: '无线蓝牙耳机 声谷标准版', quantity: 1, amount: 299, state: 'SHIPPED', stateLabel: '已发货（待收货）', createTime: '2026-10-18 14:22:10', cancelled: false },
      { orderId: 'dRD3gqtbFr', product: '零基础 Java 实战课', quantity: 1, amount: 199, state: 'PAID', stateLabel: '已付款（待发货）', createTime: '2026-10-16 09:03:41', cancelled: false },
      { orderId: 'OKWapbkaxx', product: '纯棉圆领 T 恤 三件装', quantity: 2, amount: 258, state: 'COMPLETED', stateLabel: '已完成', createTime: '2026-10-12 20:15:02', cancelled: false },
    ],
  },
  suggestions: [{ label: '查看全部订单', routeKey: 'orders' }, '查售后进度', '怎么开店'],
}

const OPEN_STORE_REPLY = {
  success: true,
  intent: 'OPEN_STORE',
  reply: '开店三步：① 填写店铺名称与营业执照号；② 提交入驻申请；③ 平台审核通过后，账号自动升级为商家。点下面的按钮，我直接带你到申请页，并高亮要填的两处。',
  action: { type: 'tour', tourId: 'open-store', routeKey: 'apply', label: '一键带我去开店' },
  suggestions: ['查我的订单', '查售后进度'],
}

function mockFor(postData) {
  let msg = ''
  try {
    msg = new URLSearchParams(postData || '').get('Message') || ''
  } catch (e) { /* 非表单 body */ }
  return msg.includes('开店') ? OPEN_STORE_REPLY : ORDER_REPLY
}

const b = await open({ w: 1440, h: 900, headed: false })

// ⚠️ 本探针**不起后端**：首页 / 申请页的既有接口经 Vite 代理打到空端口 → http.js 的
//    `handleError` 会 `alert(...)`。原生 alert 会把渲染主线程挂住，CDP 的
//    Runtime.evaluate / 截图全部排队超时（实测：不处理时 35s 一次都不回）。
//    ⇒ 一律自动接受，继续跑后面的断言。
b.ws.on('message', (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method === 'Page.javascriptDialogOpening') {
    b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
})

// ── 只拦 /chat/ask；其余请求（页面、静态资源、代理失败的业务接口）一律放行 ──
await b.send('Fetch.enable', { patterns: [{ urlPattern: '*chat/ask*' }] })
b.ws.on('message', async (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method !== 'Fetch.requestPaused') return
  const { requestId, request } = m.params
  try {
    const body = Buffer.from(JSON.stringify(mockFor(request.postData))).toString('base64')
    await b.send('Fetch.fulfillRequest', {
      requestId,
      responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
      body,
    })
  } catch (e) { /* 页面导航中 */ }
})

async function waitFor(expr, timeout = 12000, step = 200) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

try {
  // ① 登录页：球必须不在（P0-3）
  await b.goto(`${BASE}/#/login`, 2500)
  const onLogin = await b.evalJS(`({ hash: location.hash, ball: !!document.querySelector('.chat-root') })`)
  ok('P0-3 /login 上无客服球', onLogin.ball === false && onLogin.hash.startsWith('#/login'), onLogin)

  // 种一个客户身份（跳过真登录；商品/申请数据接口有意不 mock，表单与外壳不依赖它们）
  await b.evalJS(`
    sessionStorage.setItem('loginUsername', 'demo_customer');
    sessionStorage.setItem('role', '1');
    sessionStorage.setItem('customerid', '66021');
    sessionStorage.removeItem('merchantid');
  `)
  await b.evalJS(`location.hash = '#/customer'`)
  await sleep(2600)
  const onHome = await b.evalJS(`({ hash: location.hash, ball: !!document.querySelector('.ball') })`)
  ok('/customer 上出现客服球', onHome.ball === true, onHome)
  await b.shot(path.join(OUT, 'chat-ball.png'))

  // ② 打开面板 → 查订单
  await b.evalJS(`document.querySelector('.ball').click()`)
  ok('面板打开', await waitFor(`document.querySelector('.panel')`, 4000))
  await sleep(400)
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, '查我的订单')
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(150)   // 等 Vue 把 v-model 写回、按钮解除 disabled
  // ⚠️ 用 requestSubmit 而不是 `.send.click()`：按钮的 disabled 是下一帧才更新，
  //    同一帧内 click 会落在仍 disabled 的按钮上（实测：消息压根没发出去）。
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const gotOrders = await waitFor(`document.querySelectorAll('.order-card').length === 3`, 10000)
  const replyText = await b.evalJS(`[...document.querySelectorAll('.bubble.bot .txt')].pop()?.textContent || ''`)
  ok('客服回复带「最近有 N 笔」', gotOrders && /你最近有 3 笔订单/.test(replyText), { replyText: replyText.slice(0, 60) })
  ok('渲染最近 3 笔订单卡', gotOrders)
  ok('建议里有「查看全部订单」', await b.evalJS(`[...document.querySelectorAll('.chip')].some(x => x.textContent.includes('查看全部订单'))`))
  await sleep(500)
  await b.shot(path.join(OUT, 'chat-order-reply.png'))

  // ③ 怎么开店 → 一键带我去开店
  await b.evalJS(`[...document.querySelectorAll('.chip')].find(x => x.textContent.includes('怎么开店')).click()`)
  ok('出现「一键带我去开店」', await waitFor(`document.querySelector('.tour-btn')`, 10000))
  await sleep(2600)   // 等打字机走完再截
  await b.shot(path.join(OUT, 'chat-open-store.png'))

  await b.evalJS(`document.querySelector('.tour-btn').click()`)
  ok('跨页到申请页', await waitFor(`location.hash.includes('/user/apply')`, 12000))
  const tourUp = await waitFor(`document.querySelector('.driver-popover.chat-tour-popover')`, 12000)
  const step1 = await b.evalJS(`document.querySelector('.driver-popover-title')?.textContent || ''`)
  ok('自动开启漫游 · 第 1 步高亮店铺名称', tourUp && step1.includes('店铺'), { step1 })
  const hl1 = await b.evalJS(`document.querySelector('.driver-active-element')?.id || ''`)
  ok('第 1 步落点是 #tour-shop-name', hl1 === 'tour-shop-name', { hl1 })
  await sleep(500)
  await b.shot(path.join(OUT, 'tour-step1.png'))

  await b.evalJS(`document.querySelector('.driver-popover-next-btn').click()`)
  await sleep(600)
  const step2 = await b.evalJS(`document.querySelector('.driver-popover-title')?.textContent || ''`)
  const hl2 = await b.evalJS(`document.querySelector('.driver-active-element')?.id || ''`)
  ok('第 2 步高亮提交按钮', step2.includes('提交') && hl2 === 'tour-submit', { step2, hl2 })
  await b.shot(path.join(OUT, 'tour-step2.png'))

  const failed = rows.filter((r) => !r.pass)
  fs.writeFileSync(path.join(OUT, 'probe-output.txt'),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  ' + JSON.stringify(r.detail) : ''}`).join('\n') + `\n\n${rows.length - failed.length}/${rows.length} PASS\n`,
    'utf8')
  console.log(`\n${rows.length - failed.length}/${rows.length} PASS`)
  process.exitCode = failed.length ? 1 : 0
} finally {
  await b.close?.()
}
