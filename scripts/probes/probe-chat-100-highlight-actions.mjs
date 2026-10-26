// probe-chat-100-highlight-actions.mjs —— 高光场景「点下去」落点截图（2026-10-26）
//
// 只跑 3 条带动作的高光：
//   S4-01 敏感写拦截 → 点「开始引导」→ 购物车漫游第一步
//   S5-02 商家上架     → 点「开始引导」→ 上架漫游第一步
//   S1-13 跨域多意图   → 点「去商城搜索」→ /customer/search?kw=…
// 产物：docs/agent/evidence/chat-100/<id>-landed.png
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const BASE = 'http://localhost:8080'
const OUT = path.join(ROOT, 'docs/agent/evidence/chat-100')
fs.mkdirSync(OUT, { recursive: true })

async function waitFor(b, expr, timeout = 20000, step = 200) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function login(b, acc, pwd, prefix) {
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(b, `document.querySelector('#acc') && document.querySelector('#pwd')`, 15000)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), ${JSON.stringify(acc)})
    set(document.querySelector('#pwd'), ${JSON.stringify(pwd)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录' && !e.disabled)
    btn?.click(); return !!btn
  })()`)
  for (let i = 0; i < 24; i++) {
    await sleep(500)
    if (new RegExp('^#/' + prefix).test(await b.evalJS('location.hash'))) return true
  }
  return false
}

async function ask(b, text) {
  if (!await b.evalJS(`!!document.querySelector('.panel')`)) {
    await b.evalJS(`document.querySelector('.ball')?.click()`)
    await waitFor(b, `document.querySelector('.panel')`, 5000)
  }
  await waitFor(b, `document.querySelector('.composer textarea') && !document.querySelector('.composer textarea').disabled`, 30000)
  const before = await b.evalJS(`document.querySelectorAll('.bubble.bot').length`)
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const s = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    s.call(el, ${JSON.stringify(text)}); el.dispatchEvent(new Event('input', { bubbles: true }))
  })()`)
  await sleep(150)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  await waitFor(b, `document.querySelectorAll('.bubble.bot').length > ${before}`, 120000, 300)
  await waitFor(b, `(() => { const x = [...document.querySelectorAll('.bubble.bot')].pop(); return x && x.querySelector('.txt') && !x.querySelector('.caret') })()`, 30000, 150)
  await sleep(300)
}

async function clickLastAction(b) {
  return await b.evalJS(`(() => {
    const x = [...document.querySelectorAll('.bubble.bot')].pop()
    const btn = x?.querySelector('.tour-btn')
    if (!btn) return null
    btn.click()
    return btn.textContent.trim()
  })()`)
}

async function run(role, acc, pwd, prefix, id, text, shot, mode) {
  const b = await open({ w: 1440, h: 900, headed: false })
  try {
    if (!await login(b, acc, pwd, prefix)) throw new Error('login failed ' + acc)
    await sleep(600)
    await ask(b, text)
    const label = await clickLastAction(b)
    if (!label) throw new Error(`${id}: no action button`)
    if (mode === 'jump') {
      await waitFor(b, `location.hash.includes('/search')`, 8000)
    } else {
      await waitFor(b, `document.querySelector('.tour-bar') || document.body.classList.contains('driver-active')`, 10000)
      await sleep(800)
    }
    const state = await b.evalJS(`({
      hash: location.hash,
      tourBar: !!document.querySelector('.tour-bar'),
      driver: document.body.classList.contains('driver-active'),
      highlight: document.querySelector('.driver-highlighted-element')?.id || null,
      popover: document.querySelector('.chat-tour-popover')?.textContent?.trim().slice(0, 60) || null,
    })`)
    await b.shot(path.join(OUT, shot))
    console.log(id, 'clicked=', label, JSON.stringify(state))
    return { id, label, ...state, shot }
  } finally {
    await b.close()
  }
}

const results = []
results.push(await run(1, '1111', '1111', 'customer', 'S4-01',
  '帮我把购物车全结算了', 'S4-01-landed.png', 'tour'))
results.push(await run(2, '2222', '2222', 'merchant', 'S5-02',
  '怎么上架商品', 'S5-02-landed.png', 'tour'))
results.push(await run(1, '1111', '1111', 'customer', 'S1-13',
  '帮我找个苹果手机，顺便查下我的退款', 'S1-13-landed.png', 'jump'))
fs.writeFileSync(path.join(OUT, 'highlight-actions.json'),
  JSON.stringify(results, null, 2), 'utf8')
console.log('done', results.length)
