// probe-chat-llm.mjs —— 阶段一（LLM 意图识别）的**真链**验收（2026-10-22）
//
// 与 probe-chat-tour.mjs 的分工：那一份 mock `/chat/ask` 只验前端渲染；本份**不 mock 任何请求**，
// 真 dev server（8080）+ 真后端（8088，dev profile）+ 真 GLM-4-Flash（或断网降级）：
//   ① 商家登录（chenguang_parts，role=2）；
//   ② 复杂语义「我的新商品要上架秒杀页，还没上架呢」⇒ 后端回 need_publish 引导 +
//      【去发布商品】【去秒杀报名】两枚胶囊；
//   ③ 点「去发布商品」⇒ 留在首页起步，第 1 步高亮「用户中心」（2026-10-22 纠偏：不自动换页）；
//   ④ 断网模式（PROBE_MODE=offline，后端以不可达 CHAT_LLM_URL 启动）⇒ 同一句话仍能回，
//      走规则引擎（不许 500 / 不许白屏）。
//
// 用法：
//   node scripts/probes/probe-chat-llm.mjs                    # 在线
//   PROBE_MODE=offline node scripts/probes/probe-chat-llm.mjs  # 断网降级
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const MODE = process.env.PROBE_MODE === 'offline' ? 'offline' : 'online'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-llm'
fs.mkdirSync(OUT, { recursive: true })

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

// 演示账号口令不硬编码、不回显（与 probe-c3-block7-accept.mjs 同口径）。
const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900, headed: false })
b.ws.on('message', (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method === 'Page.javascriptDialogOpening') {
    b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
})

async function waitFor(expr, timeout = 15000, step = 200) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function login(acc, pwd) {
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(`document.querySelector('#acc') && document.querySelector('#pwd')`, 15000)
  for (let attempt = 0; attempt < 3; attempt++) {
    await b.evalJS(`(() => {
      const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
      set(document.querySelector('#acc'), ${JSON.stringify(acc)})
      set(document.querySelector('#pwd'), ${JSON.stringify(pwd)})
      const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录' && !e.disabled)
      btn?.click()
      return !!btn
    })()`)
    for (let i = 0; i < 24; i++) {
      await sleep(500)
      const hash = await b.evalJS(`location.hash`)
      if (/^#\/merchant/.test(hash)) return true
    }
  }
  return false
}

async function openPanel() {
  if (await b.evalJS(`!!document.querySelector('.panel')`)) return true
  await b.evalJS(`document.querySelector('.ball')?.click()`)
  const up = await waitFor(`document.querySelector('.panel')`, 5000)
  await sleep(300)
  return up
}

/** 发一句话并等回复落地（完成信号 = 输入框解除 disabled，见 probe-chat-tour.mjs 的注释）。 */
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
  const started = Date.now()
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const done = await waitFor(
    `document.querySelector('.composer textarea') && !document.querySelector('.composer textarea').disabled`,
    15000,
  )
  const elapsed = Date.now() - started
  await b.evalJS(`(() => { const l = document.querySelectorAll('.bubble.bot'); l[l.length - 1]?.click(); return true })()`)
  await sleep(300)
  return { done, elapsed }
}

async function lastBotText() {
  return await b.evalJS(`[...document.querySelectorAll('.bubble.bot .txt')].pop()?.textContent || ''`)
}

/** 只看**最后一条**客服气泡的胶囊：上一轮的胶囊还留在 DOM 里，整页取会把两轮混在一起。 */
async function chips() {
  return await b.evalJS(`(() => {
    const bots = [...document.querySelectorAll('.bubble.bot')]
    const last = bots[bots.length - 1]
    return last ? [...last.querySelectorAll('.chip')].map(e => e.textContent.trim()) : []
  })()`)
}

try {
  ok('商家登录落 /merchant', await login('chenguang_parts', CREDS['chenguang_parts']))
  await b.shot(path.join(OUT, `${MODE}-01-merchant.png`))

  const complex = '我的新商品要上架秒杀页，还没上架呢'
  const first = await sendChat(complex)
  const reply1 = await lastBotText()
  const chips1 = await chips()
  await b.shot(path.join(OUT, `${MODE}-02-complex.png`))

  if (MODE === 'online') {
    ok('复杂语义：LLM 路径回「商品还没上架」引导', first.done
      && reply1.includes('商品还没上架') && reply1.includes('上架商品'), { elapsedMs: first.elapsed, reply: reply1.slice(0, 80) })
    ok('两枚胶囊：去发布商品 / 去秒杀报名',
      chips1.some((x) => x.includes('去发布商品')) && chips1.some((x) => x.includes('去秒杀报名')), { chips: chips1 })

    // 点「去发布商品」⇒ **留在当前页**起步（2026-10-22 纠偏：未经用户点「下一步」不换页），
    // 第 1 步高亮顶栏「用户中心」；换页发生在后续步骤里。
    await b.evalJS(`[...document.querySelectorAll('.chip')].find(e => e.textContent.includes('去发布商品'))?.click()`)
    const started = await waitFor(
      `location.hash === '#/merchant' && document.querySelector('.driver-popover')
        && (document.querySelector('.driver-popover-progress-text')?.textContent || '').includes('第 1 步 / 共 7 步')`,
      8000,
    )
    await sleep(500)
    const step1 = await b.evalJS(`(() => ({
      hash: location.hash,
      progress: document.querySelector('.driver-popover-progress-text')?.textContent?.trim() || '',
      active: document.querySelector('#tour-entry-merchant')?.classList.contains('driver-active-element') || false,
    }))()`)
    ok('点「去发布商品」留在首页起步（第 1 步高亮用户中心，不瞬移）',
      started && step1.hash === '#/merchant' && step1.active, step1)
    await b.shot(path.join(OUT, `${MODE}-03-publish.png`))
  } else {
    // 断网降级：规则引擎照样答；「还没上架」这个前置条件由句面补，不依赖模型槽位。
    // 第二句没有这个前提，因此只剩一枚「去秒杀报名」。
    const second = await sendChat('我要报名秒杀')
    const reply2 = await lastBotText()
    const chips2 = await chips()
    await b.shot(path.join(OUT, `${MODE}-04-fallback.png`))
    ok('断网降级：同句仍返回业务回复（不 500 / 不挂起）',
      first.done && reply1.length > 0, { elapsedMs: first.elapsed, reply: reply1.slice(0, 80) })
    ok('断网降级：规则引擎路径（回「带你去秒杀报名页」）',
      second.done && reply2.includes('带你去秒杀报名页'), { elapsedMs: second.elapsed, reply: reply2.slice(0, 80) })
    ok('断网降级：只剩一枚「去秒杀报名」胶囊（无 need_publish 槽位）',
      chips2.some((x) => x.includes('去秒杀报名')) && !chips2.some((x) => x.includes('去发布商品')), { chips: chips2 })
  }
} catch (e) {
  ok('探针异常', false, { error: String(e && e.message ? e.message : e) })
} finally {
  const passed = rows.filter((r) => r.pass).length
  console.log(`\n${MODE}: ${passed}/${rows.length} PASS`)
  fs.writeFileSync(path.join(OUT, `probe-${MODE}.txt`),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  ${JSON.stringify(r.detail)}`).join('\n') + '\n'
    + `\n${MODE}: ${passed}/${rows.length} PASS\n`)
  await sleep(200)
  process.exit(passed === rows.length ? 0 : 1)
}
