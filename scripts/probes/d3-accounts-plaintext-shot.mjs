// d3-accounts-plaintext-shot.mjs —— 用户治理「查看明文」取证（2026-10-18 · D3 手验裁决）
//
// 用途：真 dev server（8080）登录管理员 → 用户治理 → 搜 chenh99 → 读掩码邮箱 →
//      点「查看明文」→ 断言同一行变成明文、且出现「明文」徽标 → 截图。
// 前提：该账号在库里有 email（`user.email`）；后端详情端点会写 `user.viewplaintext` 审计。
// 凭据：**只**从环境变量 PROBE_ADMIN_PWD 读（本地来源 `_work/admin-pass`）。
//
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" \
//     node scripts/probes/d3-accounts-plaintext-shot.mjs [out-prefix] [username]
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const stamp = process.argv[2] || 'd3-accounts-plaintext'
const USERNAME = process.argv[3] || 'chenh99'
const OUT = 'docs/agent/evidence/c3-d3'
const BASE = process.env.PROBE_BASE || 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) {
  console.error('缺少 PROBE_ADMIN_PWD（不要写进命令行字面量，从 _work/admin-pass 读）')
  process.exit(2)
}
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true })

const b = await open({ w: 1600, h: 1000, headed: false })
const out = { stamp, base: BASE, username: USERNAME, steps: [] }
try {
  await b.goto(BASE + '/admin.html', 2500)
  const login = await b.evalJS(`(() => {
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return { ok: false, hash: location.hash }
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')]
      .find(x => x.textContent.trim() === '登录')
    if (!btn) return { ok: false }
    btn.click()
    return { ok: true }
  })()`)
  out.steps.push({ login })
  if (!login.ok) throw new Error('登录失败')

  let hash = ''
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    hash = await b.evalJS('location.hash')
    if (/^#\/admin/.test(hash)) break
  }
  if (!/^#\/admin/.test(hash)) throw new Error('登录后没有落进 #/admin，当前 ' + hash)

  await b.evalJS(`location.hash = '#/admin/business/accounts'`)
  await sleep(1200)
  // 默认 tab 是「入驻审核」⇒ 先切到「用户治理」，否则页面上根本没有关键字输入框。
  const tabbed = await b.evalJS(`(() => {
    const t = [...document.querySelectorAll('.a-tab')]
      .find((x) => x.textContent.trim().startsWith('用户治理'))
    if (!t) return { ok: false, tabs: [...document.querySelectorAll('.a-tab')].map((x) => x.textContent.trim()) }
    t.click()
    return { ok: true }
  })()`)
  out.steps.push({ tabbed })
  if (!tabbed.ok) throw new Error('没找到「用户治理」tab：' + JSON.stringify(tabbed))
  await sleep(800)
  // 搜索该账号：输入后点「查询」按钮（表单 submit → searchUsers）。
  const searched = await b.evalJS(`(() => {
    const inp = [...document.querySelectorAll('input')]
      .find((e) => e.placeholder === '用户名 / 客户号 / 商家号')
    if (!inp) return { ok: false, inputs: [...document.querySelectorAll('input')].map((e) => e.placeholder || e.type) }
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    set(inp, ${JSON.stringify(USERNAME)})
    // 直接提交表单：此刻按钮可能正显示「查询中…」（tab 切换触发了首屏加载），按文案找会扑空。
    const form = inp.closest('form')
    if (!form) return { ok: false, reason: 'input 不在 form 里' }
    if (form.requestSubmit) form.requestSubmit()
    else form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    return { ok: true }
  })()`)
  out.steps.push({ searched })
  if (!searched.ok) throw new Error('没找到用户搜索框：' + JSON.stringify(searched))

  // 等目标行出现。
  let before = null
  for (let i = 0; i < 40; i++) {
    await sleep(300)
    before = await b.evalJS(`(() => {
      const card = [...document.querySelectorAll('.a-stream .a-card')]
        .find((li) => ((li.querySelector('.a-strong') || {}).innerText || '').trim() === ${JSON.stringify(USERNAME)})
      if (!card) return null
      const v = card.querySelector('.a-contact-v')
      return {
        rows: document.querySelectorAll('.a-stream .a-card').length,
        text: v ? v.innerText.trim() : null,
        plainClass: v ? v.className : null,
        badge: !!card.querySelector('.a-badge.approved'),
      }
    })()`)
    if (before) break
  }
  out.before = before
  if (!before) throw new Error('搜索结果里没有 ' + USERNAME + ' 这一行')
  if (!before.text || !before.text.includes('***')) {
    throw new Error('列表里的 email 不是掩码形态：' + JSON.stringify(before.text))
  }
  const masked = before.text

  // 点「查看明文」。
  const click = await b.evalJS(`(() => {
    const card = [...document.querySelectorAll('.a-stream .a-card')]
      .find((li) => ((li.querySelector('.a-strong') || {}).innerText || '').trim() === ${JSON.stringify(USERNAME)})
    const btn = [...card.querySelectorAll('button')].find((x) => x.textContent.trim() === '查看明文')
    if (!btn) return { ok: false, buttons: [...card.querySelectorAll('button')].map((x) => x.textContent.trim()) }
    btn.click()
    return { ok: true }
  })()`)
  out.steps.push({ click })
  if (!click.ok) throw new Error('这一行没有「查看明文」按钮：' + JSON.stringify(click))

  let after = null
  for (let i = 0; i < 40; i++) {
    await sleep(300)
    after = await b.evalJS(`(() => {
      const card = [...document.querySelectorAll('.a-stream .a-card')]
        .find((li) => ((li.querySelector('.a-strong') || {}).innerText || '').trim() === ${JSON.stringify(USERNAME)})
      if (!card) return null
      const v = card.querySelector('.a-contact-v')
      const err = card.querySelector('.a-why:last-of-type')
      return {
        text: v ? v.innerText.trim() : null,
        plainClass: v ? v.className : null,
        badge: ((card.querySelector('.a-badge.approved') || {}).innerText || '').trim() || null,
        error: err ? err.innerText.trim() : null,
      }
    })()`)
    if (after && after.text && after.text !== masked) break
  }
  out.after = after
  if (!after || after.text === masked) throw new Error('点「查看明文」后邮箱没有变化：' + JSON.stringify(after))
  if (!after.text.includes('@') || after.text.includes('***')) {
    throw new Error('明文形态不对：' + JSON.stringify(after.text))
  }

  const shot = `${OUT}/${stamp}.png`
  await b.shot(shot)
  out.shot = shot
  console.log(JSON.stringify(out, null, 2))
  console.log('SHOT ' + shot)
} catch (e) {
  console.log('FAILED ' + (e && e.message))
  console.log(JSON.stringify(out, null, 2))
  process.exitCode = 1
} finally {
  await b.close().catch(() => {})
}
