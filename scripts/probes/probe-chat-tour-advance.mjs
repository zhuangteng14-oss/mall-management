// probe-chat-tour-advance.mjs —— 「自动推进 + 具身飞行」真链验收（2026-10-22）
//
// 验三件事（模块二 / 三）：
//   ① 用户**真实点击高亮元素** ⇒ 路由跳转后，下一步高亮**自动**亮起（不点「下一步」）；
//   ② 切步时小助手从右下角**滑过去**：0.8s 内采样到多个中间位置，且飞行期间没有遮罩；
//   ③ 用户关掉漫游后，监听被摘掉：点同一个入口只做普通跳转，不会自动再亮高亮。
//
// 真 dev server + 真后端 + 真模型，不 mock 任何请求。
// ⚠️ 证据是**帧序列 + 逐帧坐标采样**，不是视频（本机无 ffmpeg）。
// 用法：node scripts/probes/probe-chat-tour-advance.mjs
import fs from 'node:fs'
import path from 'node:path'
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/chat-tour-advance'
fs.rmSync(OUT, { recursive: true, force: true })
fs.mkdirSync(OUT, { recursive: true })

const rows = []
const ok = (name, pass, detail = '') => {
  rows.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + JSON.stringify(detail) : ''}`)
}

const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/)
  if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900, headed: false })
const pageErrors = []
b.ws.on('message', (raw) => {
  let m
  try { m = JSON.parse(raw) } catch { return }
  if (m.method === 'Page.javascriptDialogOpening') {
    b.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
  if (m.method === 'Runtime.exceptionThrown') {
    pageErrors.push(m.params?.exceptionDetails?.text || 'exception')
  }
})
await b.send('Runtime.enable', {}).catch(() => {})

async function waitFor(expr, timeout = 15000, step = 100) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await b.evalJS(`(() => { try { return !!(${expr}) } catch (e) { return false } })()`)) return true
    await sleep(step)
  }
  return false
}

async function freshLogin(acc, pwd) {
  await b.goto(BASE + '/#/login', 800)
  await b.evalJS(`(() => { try { localStorage.clear() } catch (e) {}; try { sessionStorage.clear() } catch (e) {} })()`)
  await b.goto(BASE + '/#/login', 1500)
  await waitFor(`document.querySelector('#acc') && document.querySelector('#pwd')`, 15000)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), ${JSON.stringify(acc)})
    set(document.querySelector('#pwd'), ${JSON.stringify(pwd)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录' && !e.disabled)
    btn?.click(); return !!btn
  })()`)
  for (let i = 0; i < 24; i++) {
    await sleep(500)
    const hash = await b.evalJS('location.hash')
    if (/^#\/(merchant|customer)/.test(hash)) return hash
  }
  return null
}

async function goHash(hash) {
  await b.evalJS(`location.hash = ${JSON.stringify(hash)}`)
  await sleep(700)
}

async function openPanel() {
  if (await b.evalJS(`!!document.querySelector('.chat-root .panel')`)) return true
  await b.evalJS(`document.querySelector('.chat-root .ball')?.click()`)
  const up = await waitFor(`document.querySelector('.chat-root .panel')`, 5000)
  await sleep(300)
  return up
}

/** 发一句话，返回最后一条 bot 气泡的文本（用于确认动作按钮已渲染）。 */
async function sendChat(text) {
  await openPanel()
  const before = await b.evalJS(`document.querySelectorAll('.bubble.bot').length`)
  await b.evalJS(`(() => {
    const el = document.querySelector('.composer textarea')
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    set.call(el, ${JSON.stringify(text)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })()`)
  await sleep(120)
  await b.evalJS(`document.querySelector('.composer').requestSubmit()`)
  const done = await waitFor(
    `document.querySelectorAll('.bubble.bot').length > ${before} && !document.querySelector('.bubble.bot .dots')`,
    20000,
  )
  // 打字机没走完时动作按钮还没渲染 ⇒ 点最后一条气泡跳过动画。
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1]?.click() })()`)
  await sleep(350)
  return done
}

async function clickAction() {
  const hit = await waitFor(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; const last = bots[bots.length - 1]; return !!last?.querySelector('.tour-btn') })()`, 8000)
  if (!hit) return { clicked: false, label: '' }
  const label = await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; return bots[bots.length - 1].querySelector('.tour-btn').textContent.trim() })()`)
  await b.evalJS(`(() => { const bots = [...document.querySelectorAll('.bubble.bot')]; bots[bots.length - 1].querySelector('.tour-btn')?.click() })()`)
  return { clicked: true, label }
}

const popoverShown = `(() => { const p = document.querySelector('.driver-popover'); return !!p && getComputedStyle(p).display !== 'none' })()`
const active = (sel) => `document.querySelector(${JSON.stringify(sel)})?.classList.contains('driver-active-element')`
const progress = `document.querySelector('.driver-popover-progress-text')?.textContent?.trim() || ''`
const flowState = `sessionStorage.getItem('mall.chat.tourFlow.v1')`

async function waitStep(sel, progressText, timeout = 15000) {
  const hit = await waitFor(`${active(sel)} && (${progress}) === ${JSON.stringify(progressText)}`, timeout)
  return { hit, progress: await b.evalJS(progress), hash: await b.evalJS('location.hash') }
}

async function clickNext() {
  await b.evalJS(`document.querySelector('.driver-popover-next-btn')?.click()`)
}

/** 真·鼠标点击：CDP 发在元素中心，走原生事件链（不是 el.click()）。 */
async function realClick(sel) {
  const point = await b.evalJS(`(() => {
    const el = document.querySelector(${JSON.stringify(sel)})
    if (!el) return null
    const r = el.getBoundingClientRect()
    if (r.width < 1 || r.height < 1) return null
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }
  })()`)
  if (!point) return false
  await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y, button: 'none' })
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 })
  return point
}

/** 读精灵的当前 translate 与飞行元数据（不在飞行时返回 null 字段，不抛）。 */
const spriteProbe = `(() => {
  const sp = document.querySelector('.tour-sprite')
  if (!sp) return { exists: false }
  const t = getComputedStyle(sp).transform
  const m = t && t.startsWith('matrix') ? t.match(/matrix\\(([^)]+)\\)/) : null
  const parts = m ? m[1].split(',').map(Number) : []
  return {
    exists: true,
    visible: sp.classList.contains('is-visible'),
    x: parts.length >= 6 ? Math.round(parts[4]) : null,
    y: parts.length >= 6 ? Math.round(parts[5]) : null,
    from: sp.dataset.flightFrom || '',
    to: sp.dataset.flightTo || '',
    ms: sp.dataset.flightMs || '',
    popover: (() => { const p = document.querySelector('.driver-popover'); return !!p && getComputedStyle(p).display !== 'none' })(),
  }
})()`

try {
  const h = await freshLogin('chenguang_parts', CREDS['chenguang_parts'])
  ok('商家登录落 /merchant', h === '#/merchant', { hash: h })
  await waitFor(`document.querySelector('#tour-entry-merchant') && document.querySelector('.chat-root .ball')`, 12000)

  // ── 开一条真·发布引导（7 步，第 1 步高亮当前页的用户中心入口） ────────────
  const done = await sendChat('怎么上架商品')
  const clicked = await clickAction()
  const s1 = await waitStep('#tour-entry-merchant', '第 1 步 / 共 7 步')
  ok('引导已开到第 1 步（高亮首页「用户中心」）',
    done && clicked.clicked && s1.hit && s1.hash === '#/merchant', { clicked, ...s1 })
  await b.shot(path.join(OUT, 'adv-01-step1-home.png'))

  // ── ① 自动推进：真实点击高亮入口，不点「下一步」 ────────────────────────
  const clickAt = Date.now()
  const point = await realClick('#tour-entry-merchant')
  const autoHit = await waitFor(
    `location.hash === '#/merchant/user' && ${active('#tour-menu-add')} && (${progress}) === '第 2 步 / 共 7 步'`,
    12000, 80,
  )
  const autoMs = Date.now() - clickAt
  const stateAfter = await b.evalJS(flowState)
  ok('① 点击高亮「用户中心」⇒ 页面跳转后自动高亮「上架商品」（没点下一步）',
    !!point && autoHit && autoMs < 12000,
    { point, autoMs, state: stateAfter, hash: await b.evalJS('location.hash'), progress: await b.evalJS(progress) })
  await b.shot(path.join(OUT, 'adv-02-auto-step2.png'))

  // ── ② 飞行：跨页切步时逐 60ms 采样（这条同时验「飞行中无遮罩」） ────────
  await clickNext()
  const samples = []
  const t0 = Date.now()
  for (let i = 0; i < 20; i++) {
    const s = await b.evalJS(spriteProbe)
    samples.push({ t: Date.now() - t0, ...s })
    if (s.popover && i > 2) break
    await sleep(60)
  }
  const moved = samples.filter((s) => s.exists && s.visible && Number.isFinite(s.x))
  const distinct = new Set(moved.map((s) => `${s.x},${s.y}`))
  // ⚠️ data-flight-* 是从上一次飞行残留到这次的：必须取**落位后**那一帧，
  //    取首帧会把上一段的终点（82,560）当成本次目标。
  const flight = moved[moved.length - 1] || {}
  const to = (flight.to || '').split(',').map(Number)
  const midSamples = moved.filter((s) => s.t > 90 && s.t < 750)
  const overlayDuringFlight = samples.filter((s) => s.t < 600 && s.popover)
  const finalPopover = samples[samples.length - 1]?.popover
  ok('② 切步时小助手是「滑过去」的：0.8s 内采到多个中间位置',
    moved.length >= 3 && distinct.size >= 3 && flight.ms === '800'
      && midSamples.length >= 2,
    { samples: moved.map((s) => ({ t: s.t, x: s.x, y: s.y, ms: s.ms })), distinct: distinct.size })
  ok('② 飞行 0.6s 内没有遮罩 / 弹窗，落位后才亮高亮',
    overlayDuringFlight.length === 0 && finalPopover === true,
    { overlayDuringFlight, finalPopover, to })
  const landed = moved[moved.length - 1] || {}
  // 独立对照：落位后直接量目标元素，按实现口径算期望落点（不只看实现自己写的 dataset）。
  const rect = await b.evalJS(`(() => {
    const el = document.querySelector('#tour-publish-image')
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { left: Math.round(r.left), top: Math.round(r.top) }
  })()`)
  const expect = rect
    ? { x: Math.max(8, Math.min(rect.left, 1440 - 56)), y: Math.max(8, rect.top - 60) }
    : null
  const landErr = expect ? Math.hypot(landed.x - expect.x, landed.y - expect.y) : 999
  ok('② 落点 = 目标元素上方 60px（对照实测 rect）',
    !!expect && landErr <= 2 && to[0] === expect.x && to[1] === expect.y,
    { landed: { x: landed.x, y: landed.y }, to, rect, expect, landErr })
  const s3 = await waitStep('#tour-publish-image', '第 3 步 / 共 7 步')
  ok('② 飞到发布页后第 3 步高亮「商品主图」',
    s3.hit && s3.hash === '#/merchant/user/add', s3)

  // ── ③ 帧序列：同页切步（第 3 → 4 步），逐帧留图 + 坐标 ─────────────────
  const frames = []
  await clickNext()
  const f0 = Date.now()
  for (let i = 0; i < 6; i++) {
    const s = await b.evalJS(spriteProbe)
    frames.push({ t: Date.now() - f0, x: s.x, y: s.y, ms: s.ms, popover: s.popover })
    await b.shot(path.join(OUT, `adv-03-flight-frame${i + 1}.png`))
    await sleep(40)
  }
  const framePos = new Set(frames.filter((f) => Number.isFinite(f.x)).map((f) => `${f.x},${f.y}`))
  fs.writeFileSync(path.join(OUT, 'flight-frames.txt'),
    frames.map((f) => `t=${f.t}ms x=${f.x} y=${f.y} flightMs=${f.ms} popover=${f.popover}`).join('\n') + '\n')
  ok('③ 帧序列里精灵位置逐帧变化（不是瞬间出现）',
    framePos.size >= 3, { frames, distinct: framePos.size })

  // ── ④ 负向：用户关掉漫游后，点击同一个入口不该再自动开高亮 ──────────────
  await b.evalJS(`document.querySelector('.driver-popover-close-btn')?.click()`)
  await sleep(400)
  const cleared = await b.evalJS(flowState)
  await goHash('#/merchant')
  const p2 = await realClick('#tour-entry-merchant')
  const navOk = await waitFor(`location.hash === '#/merchant/user'`, 6000)
  await sleep(2500)
  const popAfter = await b.evalJS(popoverShown)
  ok('④ 关掉漫游后监听被摘掉：点击照常跳转，2.5s 内不再自动高亮',
    cleared === null && !!p2 && navOk && popAfter === false,
    { cleared, navOk, popAfter, hash: await b.evalJS('location.hash') })

  ok('页面无未捕获异常', pageErrors.length === 0, { pageErrors })
} catch (e) {
  ok('探针异常', false, { error: String(e && e.message ? e.message : e) })
} finally {
  const passed = rows.filter((r) => r.pass).length
  console.log(`\nadvance: ${passed}/${rows.length} PASS`)
  fs.writeFileSync(path.join(OUT, 'probe-advance.txt'),
    rows.map((r) => `${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  ${JSON.stringify(r.detail)}`).join('\n') + '\n'
    + `\nadvance: ${passed}/${rows.length} PASS\n`)
  await sleep(200)
  process.exit(passed === rows.length ? 0 : 1)
}
