// probe-chat-100.mjs —— 100 题全场景极限压测（真实前端 UI，2026-10-26）
//
// 每条消息都从客服面板的 textarea 输入 + form.requestSubmit 发出；只包一层 window.fetch
// 旁路读页面自己收到的 /chat/ask 响应（不 mock、不改响应、不直接调接口）。
//
// 账号：买家 1111/1111，商家 2222/2222（dev profile，鉴权关；默认 profile 另跑摸排）。
// 产物：docs/agent/evidence/chat-100/result.json + report.txt
//       高光 7 条与失败项的截图同目录。
//       PROBE_OUT=docs/agent/evidence/chat-100-default 可改输出目录（默认 profile 摸排用）。
//
// 用法：node scripts/probes/probe-chat-100.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const ROOT = 'C:/Users/86159/Desktop/软件工程/期末项目'
const BASE = 'http://localhost:8080'
const OUT = process.env.PROBE_OUT
  ? path.resolve(ROOT, process.env.PROBE_OUT)
  : path.join(ROOT, 'docs/agent/evidence/chat-100')
const ALL_CASES = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/probes/chat-100-cases.json'), 'utf8')).cases
const ONLY = (process.env.PROBE_IDS || '').split(',').map(s => s.trim()).filter(Boolean)
const CASES = ONLY.length ? ALL_CASES.filter(c => ONLY.includes(c.id)) : ALL_CASES
const TAG = process.env.PROBE_TAG || ''
const ACCOUNTS = { 1: ['1111', '1111'], 2: ['2222', '2222'] }
const HIGHLIGHTS = new Set(['S4-01', 'S1-13', 'S3-15', 'S3-01', 'S5-02', 'S6-01', 'S2-03'])
fs.mkdirSync(OUT, { recursive: true })

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
          window.__chatProbe.responses.push({ status: res.status, body })
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
  const ready = await waitFor(b, `document.querySelector('.composer textarea') && !document.querySelector('.composer textarea').disabled`, 30000)
  if (!ready) return false
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
  const arrived = await waitFor(b, `document.querySelectorAll('.bubble.bot').length > ${before}`, 120000, 300)
  if (arrived) {
    await waitFor(b, `(() => { const x = [...document.querySelectorAll('.bubble.bot')].pop(); return x && x.querySelector('.txt') })()`, 120000)
    await b.evalJS(`[...document.querySelectorAll('.bubble.bot')].pop()?.click()`)
    await waitFor(b, `(() => { const x = [...document.querySelectorAll('.bubble.bot')].pop(); return x && x.querySelector('.txt') && !x.querySelector('.caret') })()`, 30000, 150)
  }
  await sleep(250)
  return arrived
}

async function readUi(b) {
  return await b.evalJS(`(() => {
    const bubble = [...document.querySelectorAll('.bubble.bot')].pop()
    if (!bubble) return { text: '', chips: [], action: null, actionRaw: null }
    return {
      text: bubble.querySelector('.txt')?.textContent || '',
      chips: [...bubble.querySelectorAll('.chip')].map(e => e.textContent.trim().replace(/→$/, '').trim()),
      action: bubble.querySelector('.tour-btn')?.textContent.trim().replace(/^↗\\s*/, '') || null,
      actionRaw: bubble.querySelector('.tour-btn')?.textContent.trim() || null,
    }
  })()`)
}

function evaluate(c, body, ui, arrived) {
  const errs = []
  if (!arrived) errs.push('no new bot bubble')
  const text = (body?.reply || ui.text || '')
  const low = text.toLowerCase()
  if (!text.trim()) errs.push('empty reply')
  const action = body?.action?.targetPath || null
  if (c.intents && !c.intents.includes(body?.intent)) {
    errs.push(`intent=${body?.intent} not in ${JSON.stringify(c.intents)}`)
  }
  if (Object.prototype.hasOwnProperty.call(c, 'actionPath')) {
    const want = c.actionPath
    if (want === null && action) errs.push(`unexpected action=${action}`)
    else if (want !== null && action !== want) errs.push(`action=${action} want ${want}`)
  }
  if (c.actionPathPrefix && !String(action || '').startsWith(c.actionPathPrefix)) {
    errs.push(`action=${action} not prefix ${c.actionPathPrefix}`)
  }
  if (c.actionPathIn && !c.actionPathIn.includes(action)) {
    errs.push(`action=${action} not in ${JSON.stringify(c.actionPathIn)}`)
  }
  if (c.chipsAny && !c.chipsAny.some(s => ui.chips.some(ch => ch.includes(s)))) {
    errs.push(`chips missing ${JSON.stringify(c.chipsAny)} (got ${JSON.stringify(ui.chips)})`)
  }
  if (c.replyAny && !c.replyAny.some(s => low.includes(s.toLowerCase()))) {
    errs.push(`reply missing any of ${JSON.stringify(c.replyAny)}`)
  }
  if (c.replyAll && !c.replyAll.every(s => low.includes(s.toLowerCase()))) {
    errs.push(`reply missing all of ${JSON.stringify(c.replyAll)}`)
  }
  if (c.replyNone && c.replyNone.some(s => low.includes(s.toLowerCase()))) {
    errs.push(`reply contains forbidden ${JSON.stringify(c.replyNone)}`)
  }
  if (c.replyRegexAny && !c.replyRegexAny.some(p => new RegExp(p).test(text))) {
    errs.push(`reply matches none of ${JSON.stringify(c.replyRegexAny)}`)
  }
  // 回复里说「点下面按钮」时，UI 必须真的渲染出动作按钮或胶囊。
  if (/下方按钮|下面按钮/.test(text) && !ui.action && !ui.chips.length) {
    errs.push('reply promises a button but UI has neither action nor chip')
  }
  return errs
}

async function runRole(role) {
  const cases = CASES.filter(c => c.role === role)
  if (!cases.length) return []
  const [acc, pwd] = ACCOUNTS[role]
  const b = await open({ w: 1440, h: 900, headed: false })
  const rows = []
  try {
    const prefix = role === 2 ? 'merchant' : 'customer'
    if (!await login(b, acc, pwd, prefix)) throw new Error(`登录失败 role=${role} acc=${acc}`)
    await sleep(800)
    await installObserver(b)
    for (const c of cases) {
      const before = await b.evalJS(`window.__chatProbe.responses.length`)
      const arrived = await sendChat(b, c.q)
      const ui = await readUi(b)
      const after = await b.evalJS(`window.__chatProbe.responses.length`)
      const entry = await b.evalJS(`window.__chatProbe.responses.length ? window.__chatProbe.responses[window.__chatProbe.responses.length - 1] : null`)
      const body = entry?.body || null
      const errs = evaluate(c, body, ui, arrived)
      const pass = errs.length === 0
      let shot = null
      if (!pass || HIGHLIGHTS.has(c.id)) {
        shot = `${c.id}-${role === 2 ? 'merchant' : 'buyer'}.png`
        try { await b.shot(path.join(OUT, shot)) } catch (e) { shot = null }
      }
      rows.push({
        id: c.id, cat: c.cat, role, q: c.q, pass, errs,
        hash: await b.evalJS('location.hash'),
        ui: { text: ui.text, chips: ui.chips, action: ui.action },
        api: {
          status: entry?.status ?? null,
          intent: body?.intent ?? null,
          action: body?.action ?? null,
          reply: body?.reply ?? null,
          suggestions: body?.suggestions ?? null,
        },
        responses: after - before,
        screenshot: shot,
      })
      console.log(`${pass ? 'PASS' : 'FAIL'} ${c.id} ${body?.intent || '-'} ${ui.action || '-'} ${errs.join('; ')}`)
    }
  } finally {
    await b.close()
  }
  return rows
}

const rows = []
try {
  rows.push(...await runRole(1))
  rows.push(...await runRole(2))
} catch (e) {
  console.error('PROBE ABORTED:', e.message)
  process.exitCode = 2
}

rows.sort((a, b) => a.id.localeCompare(b.id))
fs.writeFileSync(path.join(OUT, `result${TAG}.json`), JSON.stringify(rows, null, 2), 'utf8')

const passed = rows.filter(r => r.pass).length
const byCat = {}
for (const r of rows) {
  byCat[r.cat] = byCat[r.cat] || { total: 0, pass: 0 }
  byCat[r.cat].total++
  if (r.pass) byCat[r.cat].pass++
}
const lines = []
lines.push(`100 题 UI 压测（真实前端，逐条输入）  ${new Date().toISOString()}`)
lines.push(`通过 ${passed}/${rows.length}`)
lines.push('')
for (const [cat, v] of Object.entries(byCat)) lines.push(`${cat}\t${v.pass}/${v.total}`)
lines.push('')
for (const r of rows) {
  lines.push(`[${r.pass ? 'PASS' : 'FAIL'}] ${r.id}  ${r.q}`)
  lines.push(`  意图=${r.api.intent}  动作=${r.api.action?.targetPath || '—'}  按钮=${r.ui.action || '—'}  胶囊=${r.ui.chips.join(' / ') || '—'}`)
  if (!r.pass) lines.push(`  失败：${r.errs.join('；')}`)
  lines.push(`  回复：${r.api.reply || r.ui.text}`)
  lines.push('')
}
fs.writeFileSync(path.join(OUT, `report${TAG}.txt`), lines.join('\n'), 'utf8')
console.log(`\n通过 ${passed}/${rows.length}`)
for (const [cat, v] of Object.entries(byCat)) console.log(`${cat}: ${v.pass}/${v.total}`)
if (passed !== rows.length) process.exitCode = 1
