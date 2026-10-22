// probe-chat-tour.mjs —— 智能客服 + 跨页漫游的界面验收（2026-10-21）
//
// 只验**前端**：`/chat/ask` 由 CDP 就地伪造（与既有页面探针同口径，不需要起后端）；
// 后端规则另有 `ChatIntentRecognizerTest` / 全量 `mvnw test` 兜底。
//
// 覆盖面：
//   ① /login 上**不出现**客服球（P0-3）；
//   ② /customer 上球存在 → 打开面板 → 查订单 → 打字机结束出订单卡（P0-2 的最近 3 笔）；
//   ③ 点「怎么开店」→ 出现「一键带我去开店」→ 点击后跨页到申请页并自动开 driver.js；
//   ④ 两步高亮（店铺名称 / 提交按钮）各自截图；
//   ⑤ 二次进入（Q10）：关掉漫游 → 回首页 → 再触发 → 漫游重新开启；
//   ⑥ N2 六态话术逐条渲染；N7 未知 routeKey 胶囊按 label 发送（不出现 [object Object]）；
//   ⑦ N3 商家问「我要报名秒杀」→ 胶囊导航到 /merchant/seckill。
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

// N2 六态话术（文案与后端 ChatAgentService#identitySentence 逐字一致）。
// 探针只验前端渲染；文案本身的生成由 ChatAgentServiceTest / IdentityResolverTest 兜底。
const STATE_REPLIES = {
  '身份-匿名': {
    success: true, intent: 'OPEN_STORE', reply: '您当前还未登录。',
    suggestions: ['查我的订单'],
  },
  '身份-无申请': {
    success: true, intent: 'OPEN_STORE',
    reply: '系统显示您当前是普通客户，还没有提交过入驻申请。',
    suggestions: ['查我的订单'],
  },
  '身份-审核中': {
    success: true, intent: 'OPEN_STORE',
    reply: '系统显示您当前是「入驻审核中」。店铺「米可数码店」于 2026-10-20 10:00:00，正在等平台审核。',
    suggestions: ['查入驻进度'],
  },
  '身份-未通过': {
    success: true, intent: 'OPEN_STORE',
    reply: '系统显示您当前是「入驻未通过」。原因：资料不全。',
    suggestions: ['重新申请'],
  },
  '身份-商家': {
    success: true, intent: 'OPEN_STORE',
    reply: '系统显示您当前是商家。商家号：m1。',
    suggestions: ['查我的订单'],
  },
  '身份-品牌': {
    success: true, intent: 'OPEN_STORE',
    reply: '系统显示您当前是品牌商家。品牌：米可，有效期至 2099-01-01 00:00:00。',
    suggestions: ['查我的订单'],
  },
}

// N7：未知 routeKey 的胶囊。点击后 ChatWidget 必须回退到 label 当普通消息发送。
const UNKNOWN_ROUTE_REPLY = {
  success: true, intent: 'UNKNOWN',
  reply: '我暂时听不懂这句话。你可以问我：查订单 / 查售后 / 怎么开店。',
  suggestions: [{ label: '测试未知路由', routeKey: 'unknown' }],
}
const ECHO_LABEL_REPLY = {
  success: true, intent: 'UNKNOWN', reply: '收到：测试未知路由', suggestions: [],
}

// N3：商家报名秒杀 —— 只给导航胶囊，不猜商品与价格。
const SECKILL_REPLY = {
  success: true, intent: 'ADD_TO_SECKILL',
  reply: '系统显示您当前是商家。带你去秒杀报名页，到了填商品和价格就行。',
  suggestions: [{ label: '去秒杀报名', routeKey: 'seckill' }],
}

function mockFor(postData) {
  let msg = ''
  try {
    msg = new URLSearchParams(postData || '').get('Message') || ''
  } catch (e) { /* 非表单 body */ }
  for (const [trigger, body] of Object.entries(STATE_REPLIES)) {
    if (msg.includes(trigger)) return body
  }
  if (msg.includes('未知路由')) return UNKNOWN_ROUTE_REPLY
  if (msg.includes('测试未知路由')) return ECHO_LABEL_REPLY
  if (msg.includes('秒杀')) return SECKILL_REPLY
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

/** 面板可能已被上一步 close()；只在真的关着时点球，避免把刚开的面板又关掉。 */
async function openPanel() {
  if (await b.evalJS(`!!document.querySelector('.panel')`)) return true
  await b.evalJS(`document.querySelector('.ball')?.click()`)
  const up = await waitFor(`document.querySelector('.panel')`, 4000)
  await sleep(300)
  return up
}

/**
 * 发一句话并等回复落地。
 * ⚠️ 不能以「bot 气泡数 +1」为完成信号：send() 会**先**压入 pending 占位气泡，
 *    那个判定会立刻为真，下一句就撞在 busy=true 上被静默丢掉。
 *    完成信号取输入框解除 disabled（finally 里置 busy=false），再点气泡跳过打字机。
 */
async function sendChat(text) {
  await openPanel()
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(150)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const done = await waitFor(
    `document.querySelector('.composer textarea') && !document.querySelector('.composer textarea').disabled`,
    10000,
  )
  await b.evalJS(`(() => { const l = document.querySelectorAll('.bubble.bot'); l[l.length - 1]?.click(); return true })()`)
  await sleep(200)
  return done
}

async function lastBotText() {
  return await b.evalJS(`[...document.querySelectorAll('.bubble.bot .txt')].pop()?.textContent || ''`)
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

  // ③b Q10 二次进入：关掉漫游 → 回客户首页 → 再点一次「怎么开店」
  await b.evalJS(`document.querySelector('.driver-popover-close-btn')?.click()`)
  await sleep(500)
  await b.evalJS(`location.hash = '#/customer'`)
  await sleep(2600)
  await openPanel()
  const welcomeChip = await b.evalJS(`[...document.querySelectorAll('.chip')].some(x => x.textContent.includes('怎么开店'))`)
  ok('二次进入：欢迎胶囊仍在', welcomeChip)
  await b.evalJS(`[...document.querySelectorAll('.chip')].find(x => x.textContent.includes('怎么开店')).click()`)
  ok('二次进入：再次出现「一键带我去开店」', await waitFor(`document.querySelector('.tour-btn')`, 10000))
  await sleep(2600)
  await b.evalJS(`document.querySelector('.tour-btn').click()`)
  const reTour = await waitFor(`document.querySelector('.driver-popover.chat-tour-popover')`, 12000)
  const reStep = await b.evalJS(`document.querySelector('.driver-popover-title')?.textContent || ''`)
  ok('二次进入：漫游重新开启且停在第 1 步', reTour && reStep.includes('店铺'), { reStep })
  await sleep(500)
  await b.shot(path.join(OUT, 'tour-reentry.png'))

  // 关掉漫游，回客户首页做六态与兜底断言
  await b.evalJS(`document.querySelector('.driver-popover-close-btn')?.click()`)
  await sleep(500)
  await b.evalJS(`location.hash = '#/customer'`)
  await sleep(2500)

  // ④ N2：六态话术逐条渲染（文案与后端逐字一致）
  for (const [trigger, body] of Object.entries(STATE_REPLIES)) {
    const answered = await sendChat(trigger)
    const text = await lastBotText()
    ok(`6 态话术：${trigger.replace('身份-', '')}`, answered && text.includes(body.reply), { text: text.slice(0, 32) })
    if (trigger === '身份-审核中') {
      await sleep(300)
      await b.shot(path.join(OUT, 'chat-identity-pending.png'))
    }
  }

  // ⑤ N7：未知 routeKey 必须按 label 当普通消息发送
  await sendChat('未知路由')
  ok('N7 未知 routeKey：胶囊出现', await b.evalJS(
    `[...document.querySelectorAll('.chip')].some(x => x.textContent.includes('测试未知路由'))`))
  await sleep(300)
  await b.shot(path.join(OUT, 'chat-unknown-route.png'))
  await b.evalJS(`[...document.querySelectorAll('.chip')].find(x => x.textContent.includes('测试未知路由')).click()`)
  ok('N7 未知 routeKey：点击后按 label 发消息', await waitFor(
    `[...document.querySelectorAll('.bubble.user .txt')].some(x => x.textContent === '测试未知路由')`, 8000))
  await waitFor(`[...document.querySelectorAll('.bubble.bot .txt')].some(x => x.textContent.includes('收到：测试未知路由'))`, 8000)
  ok('N7 未知 routeKey：消息区不出现 [object Object]', await b.evalJS(
    `!document.querySelector('.list')?.textContent.includes('[object Object]')`))

  // ⑥ N3：商家问秒杀 → 导航到秒杀报名页
  await b.evalJS(`
    sessionStorage.setItem('loginUsername', 'demo_merchant');
    sessionStorage.setItem('role', '2');
    sessionStorage.setItem('merchantid', 'm1');
    sessionStorage.removeItem('customerid');
  `)
  await b.evalJS(`location.hash = '#/merchant'`)
  await sleep(2800)
  const merchantHome = await b.evalJS(`location.hash`)
  ok('商家身份进入商家端', merchantHome.includes('/merchant'), { merchantHome })
  await sendChat('我要报名秒杀')
  ok('N3 秒杀：商家看到「去秒杀报名」', await waitFor(
    `[...document.querySelectorAll('.chip')].some(x => x.textContent.includes('去秒杀报名'))`, 8000))
  await sleep(300)
  await b.shot(path.join(OUT, 'chat-seckill.png'))
  await b.evalJS(`[...document.querySelectorAll('.chip')].find(x => x.textContent.includes('去秒杀报名')).click()`)
  const onSeckill = await waitFor(`location.hash.includes('/merchant/seckill')`, 10000)
  ok('N3 秒杀：导航到 /merchant/seckill', onSeckill, { hash: await b.evalJS(`location.hash`) })

  const failed = rows.filter((r) => !r.pass)
  fs.writeFileSync(path.join(OUT, 'probe-output.txt'),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  ' + JSON.stringify(r.detail) : ''}`).join('\n') + `\n\n${rows.length - failed.length}/${rows.length} PASS\n`,
    'utf8')
  console.log(`\n${rows.length - failed.length}/${rows.length} PASS`)
  process.exitCode = failed.length ? 1 : 0
} finally {
  await b.close?.()
}
