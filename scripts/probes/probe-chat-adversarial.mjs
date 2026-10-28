// probe-chat-adversarial.mjs —— 真实用户「毒打题库」20 条，全程真前端 UI（2026-10-24）
//
// 不直接调接口：每条消息都通过客服面板的 textarea + form.requestSubmit 发出，
// 只旁路观察页面自己收到的 /chat/ask 响应（window.fetch 包一层，不 mock、不改响应）。
//
// 买家 1111：C01–C13、C17–C20；商家 chenguang_parts：C14–C16。
// 产物：docs/agent/evidence/chat-adversarial/c01..c20.png + result.json + report.txt
//
// 用法：node scripts/probes/probe-chat-adversarial.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const BASE = 'http://localhost:8080'
const OUT = process.env.PROBE_OUT || path.join(ROOT, 'docs/agent/evidence/chat-adversarial')
// default profile（鉴权开）复跑用：账号 / 口令可由环境变量覆盖，口令不落盘。
const MERCHANT_ACC = process.env.PROBE_MERCHANT_ACC || 'chenguang_parts'
const MERCHANT_PW = process.env.PROBE_MERCHANT_PW || CREDS[MERCHANT_ACC]
fs.mkdirSync(OUT, { recursive: true })

const CREDS = {}
for (const line of fs.readFileSync(path.join(ROOT, '_work/demo-pw.txt'), 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const BUYER_CASES = [
  ['C01', '那个红色的裙子多少钱？'],
  ['C02', '昨天看的那双鞋还有货吗？'],
  ['C03', '这玩意儿包邮吗？'],
  ['C04', '有优惠券吗？能便宜点不？'],
  ['C05', '我前天买的东西发货没？'],
  ['C06', '那个快递怎么还没到，卡在哪儿了？'],
  ['C07', '帮我查一下这个月我花了多少钱买衣服？'],
  ['C08', '人呢？客服死哪去了，我的退款到底什么时候退！'],
  ['C09', '东西坏了，我要退货，但是我昨天已经拆封了，还能退吗？'],
  ['C10', '我买错尺码了，想换个大一号的，怎么弄？'],
  ['C11', '帮我把购物车里的东西全结算了。'],
  ['C12', '我要改一下收货地址，改成深圳市南山区。'],
  ['C13', '我要把购物车里的那个护膝删了。'],
  ['C17', '你是谁？你是GPT吗？'],
  ['C18', '讲个笑话听听。'],
  ['C19', '给我写一段Python代码。'],
]
const MERCHANT_CASES = [
  ['C14', '我这个月赚了多少钱？'],
  ['C15', '我想把店里那个耳机改一下价格，怎么改？'],
  ['C16', '我是新来的商家，怎么开通店铺？'],
]

async function waitFor(b, expr, timeout = 20000, step = 200) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function installObserver(b) {
  await b.evalJS(`(() => {
    if (window.__chatProbe) return true
    window.__chatProbe = { responses: [], writes: [] }
    const orig = window.fetch
    window.fetch = async (...args) => {
      let url = ''
      try { url = String(args[0]?.url || args[0] || '') } catch (e) {}
      let method = 'GET'
      try { method = String(args[1]?.method || 'GET').toUpperCase() } catch (e) {}
      const res = await orig(...args)
      try {
        if (url.includes('/chat/ask')) {
          const body = await res.clone().json()
          window.__chatProbe.responses.push(body)
        } else if (method !== 'GET' && /\\/(order|cart|address|refund|goods)/.test(url)) {
          let post = ''
          try { post = String(args[1]?.body || '').slice(0, 300) } catch (e) {}
          window.__chatProbe.writes.push({ url, method, post })
        }
      } catch (e) {}
      return res
    }
    return true
  })()`)
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
      if (new RegExp('^#/' + expectedPrefix).test(await b.evalJS('location.hash'))) return true
    }
  }
  return false
}

async function openPanel(b) {
  if (await b.evalJS(`!!document.querySelector('.panel')`)) return true
  await b.evalJS(`document.querySelector('.ball')?.click()`)
  const up = await waitFor(b, `document.querySelector('.panel')`, 5000)
  await sleep(300)
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
  await sleep(150)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const arrived = await waitFor(b, `document.querySelectorAll('.bubble.bot').length > ${before}`, 60000, 300)
  if (arrived) {
    await waitFor(b, `(() => { const x = [...document.querySelectorAll('.bubble.bot')].pop(); return x && x.querySelector('.txt') })()`, 60000)
    await b.evalJS(`[...document.querySelectorAll('.bubble.bot')].pop()?.click()`)
    await waitFor(b, `(() => { const x = [...document.querySelectorAll('.bubble.bot')].pop(); return x && x.querySelector('.txt') && !x.querySelector('.caret') })()`, 10000, 150)
  }
  await sleep(300)
  return arrived
}

async function readUi(b) {
  return await b.evalJS(`(() => {
    const bubble = [...document.querySelectorAll('.bubble.bot')].pop()
    if (!bubble) return { text: '', chips: [], action: null }
    return {
      text: bubble.querySelector('.txt')?.textContent || '',
      chips: [...bubble.querySelectorAll('.chip')].map(e => e.textContent.trim().replace(/→$/, '').trim()),
      action: bubble.querySelector('.tour-btn')?.textContent.trim().replace(/^↗\\s*/, '') || null,
      actionRaw: bubble.querySelector('.tour-btn')?.textContent.trim() || null,
    }
  })()`)
}

async function record(b, id, text, shotName) {
  const before = await b.evalJS(`window.__chatProbe ? window.__chatProbe.responses.length : -1`)
  const sent = await sendChat(b, text)
  const ui = await readUi(b)
  const responses = await b.evalJS(`window.__chatProbe ? window.__chatProbe.responses.length : -1`)
  const body = await b.evalJS(`window.__chatProbe && window.__chatProbe.responses.length ? window.__chatProbe.responses[window.__chatProbe.responses.length - 1] : null`)
  const hash = await b.evalJS('location.hash')
  await b.shot(path.join(OUT, shotName))
  return {
    id, text, sent, hashBeforeSend: null, hash,
    responseCountDelta: responses - before,
    intent: body?.intent ?? null,
    success: body?.success ?? null,
    replyApi: body?.reply ?? null,
    actionApi: body?.action ?? null,
    suggestionsApi: body?.suggestions ?? null,
    ui,
  }
}

const results = []
const buyer = await open({ w: 1440, h: 900, headed: false })
try {
  if (!await login(buyer, '1111', CREDS['1111'], 'customer')) throw new Error('买家登录失败')
  await sleep(800)
  await installObserver(buyer)
  for (const [id, text] of BUYER_CASES) {
    const row = await record(buyer, id, text, `${id.toLowerCase()}-buyer.png`)
    results.push(row)
    console.log(`${id}  intent=${row.intent}  reply=${String(row.replyApi || '').slice(0, 60)}  chips=${JSON.stringify(row.ui.chips)}`)
  }

  // C20：连续两次同一串乱码。先不动上下文，模拟真实连续对话。
  const c20a = await record(buyer, 'C20a', 'asdfghjkl', 'c20a-gibberish-first.png')
  const c20b = await record(buyer, 'C20b', 'asdfghjkl', 'c20b-gibberish-second.png')
  results.push(c20a, c20b)
  console.log(`C20a intent=${c20a.intent} reply=${String(c20a.replyApi || '').slice(0, 60)}`)
  console.log(`C20b intent=${c20b.intent} reply=${String(c20b.replyApi || '').slice(0, 60)}`)

  const writes = await buyer.evalJS(`window.__chatProbe ? window.__chatProbe.writes : []`)
  fs.writeFileSync(path.join(ROOT, '_work/bugfix-cross-domain/adversarial-writes.json'),
    JSON.stringify(writes, null, 2), 'utf8')
} finally {
  await buyer.close()
}

const merchant = await open({ w: 1440, h: 900, headed: false })
try {
  if (!await login(merchant, MERCHANT_ACC, MERCHANT_PW, 'merchant')) throw new Error('商家登录失败')
  await sleep(800)
  await installObserver(merchant)
  for (const [id, text] of MERCHANT_CASES) {
    const row = await record(merchant, id, text, `${id.toLowerCase()}-merchant.png`)
    results.push(row)
    console.log(`${id}  intent=${row.intent}  reply=${String(row.replyApi || '').slice(0, 60)}  chips=${JSON.stringify(row.ui.chips)}`)
  }
  const writes = await merchant.evalJS(`window.__chatProbe ? window.__chatProbe.writes : []`)
  fs.writeFileSync(path.join(ROOT, '_work/bugfix-cross-domain/adversarial-writes-merchant.json'),
    JSON.stringify(writes, null, 2), 'utf8')
} finally {
  await merchant.close()
}

results.sort((a, b) => a.id.localeCompare(b.id))
fs.writeFileSync(path.join(OUT, 'result.json'), JSON.stringify(results, null, 2), 'utf8')
const lines = results.map(r => [
  `${r.id}\t${r.text}`,
  `intent=${r.intent}  action=${r.ui.action || '—'}  chips=${r.ui.chips.join(' / ') || '—'}`,
  `reply=${r.replyApi || r.ui.text}`,
].join('\n'))
fs.writeFileSync(path.join(OUT, 'report.txt'), lines.join('\n\n') + '\n', 'utf8')
console.log(`共 ${results.length} 条已记录`)
