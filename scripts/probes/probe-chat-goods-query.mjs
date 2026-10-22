// probe-chat-goods-query.mjs —— 商品查询修复的真链验收（2026-10-22）
//
// 真 dev server（8080）+ 真后端（8088，dev profile）+ 真 GLM-4-Flash：
//   ① 买家登录（1111，口令读 _work/demo-pw.txt，不打印）；
//   ② 「怎么看 222:ij-555 商品的详细数据」⇒ 未找到商品（不出现 pong / 订单话术）；
//   ③ 「oppo12 多少钱」⇒ 真实价格 / 编号 / 在售状态；
//   ④ 两轮都截图落 docs/agent/evidence/chat-goods-query/。
//
// 用法：node scripts/probes/probe-chat-goods-query.mjs
import fs from 'node:fs'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-goods-query'
fs.mkdirSync(OUT, { recursive: true })

const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900, headed: false })

async function waitFor(expr, timeout = 20000, step = 200) {
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
      if (/^#\/customer/.test(hash)) return true
    }
  }
  return false
}

async function openPanel() {
  if (await b.evalJS(`!!document.querySelector('.panel')`)) return true
  await b.evalJS(`document.querySelector('.ball')?.click()`)
  const up = await waitFor(`document.querySelector('.panel')`, 5000)
  await sleep(400)
  return up
}

async function sendChat(text) {
  await openPanel()
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(200)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const done = await waitFor(
    `document.querySelector('.composer textarea') && !document.querySelector('.composer textarea').disabled`,
    20000,
  )
  await sleep(500)
  return done
}

async function lastBotText() {
  return await b.evalJS(`[...document.querySelectorAll('.bubble.bot .txt')].pop()?.textContent || ''`)
}

try {
  if (!await login('1111', CREDS['1111'])) throw new Error('买家登录失败')
  await sleep(1200)

  const missingText = []
  const foundText = []

  await sendChat('怎么看 222:ij-555 商品的详细数据')
  await sleep(600)
  missingText.push(await lastBotText())
  await b.shot(OUT + '/shot-goods-not-found.png')

  await sendChat('oppo12 多少钱')
  await sleep(600)
  foundText.push(await lastBotText())
  await b.shot(OUT + '/shot-goods-found.png')

  const missing = missingText.join('\n')
  const found = foundText.join('\n')
  const rows = [
    ['未找到：明确说未找到', /未找到商品/.test(missing) && /222:ij-555/.test(missing), missing],
    ['未找到：不出现 pong', !/pong/.test(missing), missing],
    ['未找到：不出现订单话术', !/订单号|最近有.*笔订单/.test(missing), missing],
    ['存在：回真实商品名', /oppo12/.test(found), found],
    ['存在：回价格 / 库存 / 状态', /¥/.test(found) && /库存/.test(found) && /在售|热卖/.test(found), found],
    ['存在：不泄露店铺号 / 内部编号', !/5N1k5K2guh/.test(found) && !/OP2/.test(found), found],
  ]
  for (const [name, pass, got] of rows) {
    console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}  ${JSON.stringify(got)}`)
  }
  fs.writeFileSync(OUT + '/ui-probe.txt', rows
    .map(([n, p, g]) => `${p ? 'PASS' : 'FAIL'}  ${n}\n  ${g}`).join('\n') + '\n', 'utf8')
  if (rows.some(([, p]) => !p)) process.exitCode = 1
} finally {
  await b.close()
}
