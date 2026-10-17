// b7-metrics-stock-shot.mjs —— 指标页「库存水位」修复前后取证（2026-10-17）
//
// 用途：真 dev server（8080）+ 真后端（8088）登录管理员 → 打开指标页 →
//      等库存段落地 → 截全页 + 把库存段滚进视口再截一张，并打印判据（黄条在不在、
//      每行的 tag / 条宽 / 分母）。修复前跑一次、修复后复跑同一份脚本，产物成对。
//
// 凭据：**只**从环境变量 PROBE_ADMIN_PWD 读（本地来源 `_work/admin-pass`，先 tr -d '\r\n'）。
//      本文件不写任何口令字面量，也不回显口令。
//
// 用法：
//   PROBE_ADMIN_PWD="$(tr -d '\r\n' < _work/admin-pass)" \
//     node scripts/probes/b7-metrics-stock-shot.mjs <out-prefix>
//   产物：<out-prefix>-full.png、<out-prefix>-stock.png
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const stamp = process.argv[2] || 'b7-metrics-stock'
const OUT = 'docs/agent/evidence/c3-block7'
const BASE = process.env.PROBE_BASE || 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) {
  console.error('缺少 PROBE_ADMIN_PWD（不要写进命令行字面量，从 _work/admin-pass 读）')
  process.exit(2)
}
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true })

const b = await open({ w: 1600, h: 1000, headed: false })
const out = { stamp, base: BASE, steps: [] }
try {
  await b.goto(BASE + '/admin.html', 2500)

  // ── 登录（管理员入口是 admin.html；未登录会被守卫送到 #/login）──────────────
  const login = await b.evalJS(`(() => {
    const set = (el, v) => {
      const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return { ok: false, hash: location.hash, inputs: [...document.querySelectorAll('input')].map(e => e.id || e.type) }
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    // ⚠️ 页头也有一个文字为「登录」的 tab ⇒ 必须挑表单提交键（btn-primary btn-block），
    //    否则点的是导航 tab，什么都不会发生（2026-10-17 实测踩到）。
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')]
      .find(x => x.textContent.trim() === '登录')
      || [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录')
    if (!btn) return { ok: false, buttons: [...document.querySelectorAll('button')].map(x => x.textContent.trim()) }
    btn.click()
    return { ok: true, pwdLen: pwd.value.length, btnClass: btn.className }
  })()`)
  out.steps.push({ login })
  if (!login.ok) throw new Error('登录页输入框/按钮没找到：' + JSON.stringify(login))

  let hash = ''
  let loginState = null
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    hash = await b.evalJS('location.hash')
    if (/^#\/admin/.test(hash)) break
  }
  loginState = await b.evalJS(`(() => ({
    hash: location.hash,
    body: document.body.innerText.slice(0, 400),
    warn: (document.querySelector('.login-warn, .warn, .err') || {}).innerText || null
  }))()`)
  out.steps.push({ afterLoginHash: hash, loginState })
  if (!/^#\/admin/.test(hash)) throw new Error('登录后没有落进 #/admin，当前 ' + hash + ' / ' + JSON.stringify(loginState))

  // ── 指标页 ────────────────────────────────────────────────────────────────
  await b.evalJS(`location.hash = '#/admin/observe/metrics'`)
  let diag = null
  for (let i = 0; i < 60; i++) {
    await sleep(500)
    diag = await b.evalJS(`(() => {
      const q = (s) => document.querySelector(s)
      const txt = (s) => (q(s) ? q(s).innerText.trim() : null)
      const rows = [...document.querySelectorAll('.mp-stock-row')].map(li => ({
        name: txt('.mp-stock-name') || ((li.querySelector('.mp-stock-name') || {}).innerText || '').trim(),
        n: ((li.querySelector('.mp-stock-n') || {}).innerText || '').trim(),
        tag: ((li.querySelector('.mp-stock-tag') || {}).innerText || '').trim(),
        tagClass: (li.querySelector('.mp-stock-tag') || {}).className || '',
        width: (li.querySelector('.mp-stock-fill') || {}).style ? li.querySelector('.mp-stock-fill').style.width : null,
        tone: (li.querySelector('.mp-stock-fill') || {}).className || '',
        title: (li.querySelector('.mp-stock-track') || {}).title || ''
      }))
      return {
        hash: location.hash,
        hasPanel: !!q('.mp-stock'),
        hasWarn: !!q('.mp-stock-warn'),
        warnText: txt('.mp-stock-warn'),
        stockNote: txt('.mp-stock-note'),
        header: txt('.mp-rail-h'),
        empty: txt('.mp-stock .mp-empty'),
        available: !q('.mp-stock > .mp-empty'),
        rowCount: rows.length,
        rows,
        bodyLen: document.body.innerText.length
      }
    })()`)
    if (diag.hasPanel && (diag.rowCount > 0 || diag.empty)) break
  }
  out.diag = diag
  console.log(JSON.stringify(out, null, 2))

  // 全页 + 库存段两张
  const full = `${OUT}/${stamp}-full.png`
  await b.shotFull(full)
  const scrolled = await b.evalJS(`(() => {
    const el = document.querySelector('.mp-stock')
    if (!el) return false
    el.scrollIntoView({ block: 'start' })
    return true
  })()`)
  await sleep(800)
  const stockShot = `${OUT}/${stamp}-stock.png`
  await b.shot(stockShot)
  console.log('SHOT ' + full)
  console.log('SHOT ' + stockShot + ' scrolled=' + scrolled)
} catch (e) {
  console.log('FAILED ' + (e && e.message))
  console.log(JSON.stringify(out, null, 2))
  process.exitCode = 1
} finally {
  await b.close().catch(() => {})
}
