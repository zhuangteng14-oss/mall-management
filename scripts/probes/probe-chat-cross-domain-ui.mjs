// probe-chat-cross-domain-ui.mjs —— 跨域购物 + 澄清防复读的**真实界面**验收（2026-10-24）
//
// 真 dev server（8080）+ 真后端（8088，dev profile）+ 真 DeepSeek / SiliconFlow：
//   ① 买家登录 1111，输入「oppo12这商品在哪购买？」⇒ 回复带【去商城搜索】，
//      点击后落到 /customer/search?kw=oppo12；
//   ② 买家随机乱码连发两次 ⇒ 第二次换话术，胶囊不出现商家选项；
//   ③ 商家登录 chenguang_parts，输入「护膝在哪买」⇒ 回复带【去商城搜索】，
//      点击后落到 /merchant/search?kw=护膝，回复不出现「管理商品」。
//
// 凭据只从 _work/demo-pw.txt 读，不打印。
// 用法：node scripts/probes/probe-chat-cross-domain-ui.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const BASE = 'http://localhost:8080'
const OUT = path.join(ROOT, 'docs/agent/evidence/chat-cross-domain')
fs.mkdirSync(OUT, { recursive: true })

const CREDS = {}
for (const line of fs.readFileSync(path.join(ROOT, '_work/demo-pw.txt'), 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

async function waitFor(b, expr, timeout = 20000, step = 200) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function login(b, acc, pwd, expectedPrefix) {
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(b, `document.querySelector('#acc') && document.querySelector('#pwd')`, 15000)
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
      const hash = await b.evalJS('location.hash')
      if (new RegExp('^#/' + expectedPrefix).test(hash)) return true
    }
  }
  return false
}

async function openPanel(b) {
  if (await b.evalJS(`!!document.querySelector('.panel')`)) return true
  await b.evalJS(`document.querySelector('.ball')?.click()`)
  const up = await waitFor(b, `document.querySelector('.panel')`, 5000)
  await sleep(400)
  return up
}

async function sendChat(b, text) {
  await openPanel(b)
  await waitFor(b, `document.querySelector('.composer textarea') && !document.querySelector('.composer textarea').disabled`, 30000)
  const before = await b.evalJS(`document.querySelectorAll('.bubble.bot').length`)
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(200)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const arrived = await waitFor(b, `document.querySelectorAll('.bubble.bot').length > ${before}`, 30000, 300)
  // 新气泡先是「正在输入」三点（没有 .txt）；等正文落地再点一下跳过打字机，
  // 否则最后一条可能是 pending 状态，.txt 为空且没有 .caret，判据会假通过。
  if (arrived) {
    await waitFor(b, `(() => { const x = [...document.querySelectorAll('.bubble.bot')].pop(); return x && x.querySelector('.txt') })()`, 60000)
    await b.evalJS(`[...document.querySelectorAll('.bubble.bot')].pop()?.click()`)
    await waitFor(b, `(() => { const x = [...document.querySelectorAll('.bubble.bot')].pop(); return x && x.querySelector('.txt') && !x.querySelector('.caret') })()`, 8000, 150)
  }
  await sleep(400)
  return arrived
}

async function lastBot(b) {
  return await b.evalJS(`(() => {
    const bubble = [...document.querySelectorAll('.bubble.bot')].pop()
    if (!bubble) return { text: '', chips: [], action: null }
    return {
      text: bubble.querySelector('.txt')?.textContent || '',
      chips: [...bubble.querySelectorAll('.chip')].map(e => e.textContent.trim().replace(/→$/, '').trim()),
      action: bubble.querySelector('.tour-btn')?.textContent.trim().replace(/^↗\s*/, '') || null,
    }
  })()`)
}

async function clickAction(b) {
  return await b.evalJS(`(() => {
    const btn = [...document.querySelectorAll('.bubble.bot .tour-btn')].pop()
    if (!btn) return null
    const label = btn.textContent.trim().replace(/^↗\s*/, '')
    btn.click()
    return label
  })()`)
}

async function waitHash(b, needle, timeout = 8000) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    const hash = await b.evalJS('location.hash')
    if (decodeURIComponent(hash).includes(needle)) return hash
    await sleep(250)
  }
  return await b.evalJS('location.hash')
}

const buyer = await open({ w: 1440, h: 900, headed: false })
try {
  if (!await login(buyer, '1111', CREDS['1111'], 'customer')) throw new Error('买家登录失败')
  await sleep(1000)

  // ── ① 买家：oppo12 在哪购买 ─────────────────────────────────────────────
  await sendChat(buyer, 'oppo12这商品在哪购买？')
  const b01 = await lastBot(buyer)
  await buyer.shot(path.join(OUT, 'buyer-01-oppo12-chat.png'))
  ok('买家「oppo12这商品在哪购买？」回复带【去商城搜索】', b01.action === '去商城搜索', b01)
  ok('买家回复不出现「管理商品」', !b01.text.includes('管理商品'), { text: b01.text.slice(0, 120) })
  ok('买家胶囊含「去商城搜索」', b01.chips.includes('去商城搜索'), { chips: b01.chips })

  const actionLabel = await clickAction(buyer)
  const buyerHash = await waitHash(buyer, '/customer/search?kw=oppo12')
  await sleep(1000)
  await buyer.shot(path.join(OUT, 'buyer-02-search-page.png'))
  ok('点击后落到 /customer/search?kw=oppo12',
    actionLabel === '去商城搜索' && decodeURIComponent(buyerHash).includes('/customer/search?kw=oppo12'),
    { actionLabel, hash: buyerHash })

  // ── ② 买家：随机乱码连发两次 ─────────────────────────────────────────────
  await buyer.evalJS(`location.hash = '#/customer'`)
  await sleep(1200)
  // 先重置上下文，避免上一次会话残留的 CLARIFY 干扰「第二次」判据。
  await sendChat(buyer, '查我的订单')
  await sendChat(buyer, 'qzxvbnmplkjhgf')
  const c1 = await lastBot(buyer)
  await sendChat(buyer, 'wrtpomsdfghjkl')
  const c2 = await lastBot(buyer)
  await buyer.shot(path.join(OUT, 'buyer-03-clarify-second.png'))
  ok('第一次乱码 ⇒ CLARIFY 通用话术', c1.text.includes('没太听懂'), { text: c1.text })
  ok('第二次乱码换话术（不是复读）',
    c2.text !== c1.text && c2.text.includes('我还是没太听懂'), { first: c1.text, second: c2.text })
  ok('第二次乱码胶囊不含商家选项',
    !c2.chips.join('').match(/管理商品|工作台|查我的订单/), { chips: c2.chips })
} finally {
  await buyer.close()
}

const merchant = await open({ w: 1440, h: 900, headed: false })
try {
  if (!await login(merchant, 'chenguang_parts', CREDS['chenguang_parts'], 'merchant')) {
    throw new Error('商家登录失败')
  }
  await sleep(1000)

  // ── ③ 商家：护膝在哪买 ──────────────────────────────────────────────────
  await sendChat(merchant, '护膝在哪买')
  const m01 = await lastBot(merchant)
  await merchant.shot(path.join(OUT, 'merchant-01-hujia-chat.png'))
  ok('商家「护膝在哪买」回复带【去商城搜索】', m01.action === '去商城搜索', m01)
  ok('商家回复不出现「管理商品」', !m01.text.includes('管理商品'), { text: m01.text.slice(0, 120) })
  ok('商家胶囊含「去商城搜索」', m01.chips.includes('去商城搜索'), { chips: m01.chips })

  const mLabel = await clickAction(merchant)
  const merchantHash = await waitHash(merchant, '/merchant/search?kw=护膝')
  await sleep(1000)
  await merchant.shot(path.join(OUT, 'merchant-02-search-page.png'))
  ok('商家点击后落到 /merchant/search?kw=护膝',
    mLabel === '去商城搜索' && decodeURIComponent(merchantHash).includes('/merchant/search?kw=护膝'),
    { actionLabel: mLabel, hash: merchantHash })
} finally {
  await merchant.close()
}

const passed = rows.filter(r => r.pass).length
fs.writeFileSync(path.join(OUT, 'ui-probe.txt'),
  rows.map(r => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}\n  ${JSON.stringify(r.detail)}`).join('\n') + '\n',
  'utf8')
fs.writeFileSync(path.join(OUT, 'ui-probe.json'),
  JSON.stringify({ total: rows.length, passed, failed: rows.length - passed, rows }, null, 2), 'utf8')
console.log(`合计 ${rows.length} 项 / ${passed} 通过 / ${rows.length - passed} 失败`)
if (passed !== rows.length) process.exitCode = 1
