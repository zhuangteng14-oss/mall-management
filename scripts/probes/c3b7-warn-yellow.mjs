// c3b7-warn-yellow.mjs —— 块 7 · 2026-10-17「黄灯 = 可降级提示」验收探针。
//
// 用户交付验收 ①：压测时日志页应该**紫色（PROTECT）+ 黄色（WARN）**为主，红色极少或 0。
//   · 紫色来自封禁用户攻击 → 身份拒绝；
//   · 黄色来自 redisDown 阶段 B：L2 读取失败 ⇒ `fromCache` 记 WARN（回落 DB），
//     这是「系统能提示 / 能降级」的典型，不是业务失败。
//
// 判据：
//   ① warn > 0 且 protect > 0（两色同屏）；
//   ② error = 0（红色极少或 0）；
//   ③ 页面业务失败率 (fail) = 0.0%（只统计 error），warn / protect 单列；
//   ④ 截图落 `3a4f-warn-yellow-log.png`。
//
// ⚠️ 收尾把 injections 复原成开跑前的值（配置跑完不复位；不还原会留在进程里）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const API = 'http://localhost:8088'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const out = { at: new Date().toISOString(), checks: {}, samples: {} }
const fail = []
function check(name, ok, detail) {
  out.checks[name] = { ok: !!ok, detail }
  if (!ok) fail.push(name + ': ' + JSON.stringify(detail))
}

async function post(path, body) {
  const res = await fetch(API + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: body || '',
    signal: AbortSignal.timeout(180000),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) { /* 留原文 */ }
  return { status: res.status, json, text }
}

const b = await open({ w: 1440, h: 900 })
let run = null
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => { try {
      sessionStorage.setItem('loginUsername', 'ops_duan')
      sessionStorage.setItem('customerid', 'qa7PmpUojO')
      sessionStorage.setItem('role', '3')
    } catch (e) {} })()`,
  })

  // 记录原 injections（收尾还原）。
  const cfg0 = await (await fetch(API + '/admin/observe/attack/config', { signal: AbortSignal.timeout(8000) })).json()
  const inj0 = (cfg0 && cfg0.Injections) || []
  out.samples.injectionsBefore = inj0

  // 开日志页（事件流），再打开 redisDown 注入。
  await b.goto(`${WEB}/admin.html?warn=${Date.now()}#/admin/observe/logs`, 6000)
  await sleep(2000)
  check('admin-shell-sentinel', (await b.evalJS(`document.documentElement.getAttribute('data-theme')`)) === 'admin')

  const setInj = await post('/admin/observe/attack/config', new URLSearchParams({
    injections: JSON.stringify(['redisDown']),
  }).toString())
  out.samples.injectionsSet = { status: setInj.status, json: setInj.json && setInj.json.Injections }
  check('redisdown-injection-on', setInj.status === 200 && (setInj.json.Injections || []).includes('redisDown'), out.samples.injectionsSet)

  // 阶段 A：封禁用户（紫）；阶段 B：redisDown（黄）。10s + 5s。
  run = post('/admin/observe/attack/run', new URLSearchParams({
    name: 'probe-warn-yellow-20261017',
    concurrency: '128',
    durationSeconds: '10',
    targetQps: '200',
    scenarios: JSON.stringify([{ key: 'bannedUser', weight: 100 }]),
  }).toString()).catch((e) => ({ err: String(e) }))
  await run
  await sleep(1500)

  const snap = await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    if (!s) return null
    return {
      n: s.rawWindow.n, sev: { ...s.rawWindow.bySev },
      failRate: s.failRate, warnRate: s.warnRate, protectRate: s.protectRate,
      domFail: ((document.querySelector('.cell.err b') || {}).innerText || '').trim(),
      domWarn: ((document.querySelector('.cell.err .warn') || {}).innerText || '').trim(),
      domProt: ((document.querySelector('.cell.err .prot') || {}).innerText || '').trim(),
    }
  })()`)
  out.samples.logSnapshot = snap
  check('window-has-data', snap && snap.n > 0, snap)

  // 冻结窗口，保证截图与读数同源。
  await b.evalJS(`(() => {
    const el = document.querySelector('.ev-frame')
    const s = el && el.__vueParentComponent && el.__vueParentComponent.setupState
    if (s) s.paused = true
    const list = document.querySelector('#ev-list')
    if (list) list.scrollTop = 0
    return true
  })()`)
  await sleep(800)
  await b.evalJS(`(() => {
    const b = document.body
    b.style.zoom = '1.0001'; void b.offsetHeight; b.style.zoom = ''; void b.offsetHeight
    return true
  })()`)
  await sleep(400)
  await b.shot(EV + '3a4f-warn-yellow-log.png')
  out.samples.afterShot = {
    fail: ((await b.evalJS(`(document.querySelector('.cell.err b') || {}).innerText || ''`)).trim()),
    warn: ((await b.evalJS(`(document.querySelector('.cell.err .warn') || {}).innerText || ''`)).trim()),
    prot: ((await b.evalJS(`(document.querySelector('.cell.err .prot') || {}).innerText || ''`)).trim()),
  }

  check('warn-present', (snap.sev.warn || 0) > 0, snap.sev)
  check('protect-present', (snap.sev.protect || 0) > 0, snap.sev)
  check('error-zero', (snap.sev.error || 0) === 0, snap.sev)
  check('fail-rate-error-only', snap.failRate === '0.0' && snap.domFail.replace(/\s/g, '') === '0.0%',
    { failRate: snap.failRate, domFail: snap.domFail, formula: `error ${snap.sev.error || 0} / ${snap.n}` })
  check('warn-rate-displayed', snap.warnRate === ((snap.sev.warn / snap.n) * 100).toFixed(1)
    && snap.domWarn.includes(snap.warnRate + '%'), { warnRate: snap.warnRate, domWarn: snap.domWarn, warn: snap.sev.warn, n: snap.n })

  out.ok = fail.length === 0
  out.fail = fail
  console.log(JSON.stringify(out, null, 2))
  if (fail.length) process.exitCode = 1
} finally {
  try { await post('/admin/observe/attack/stop', '') } catch (e) { /* 收尾 */ }
  try { await run } catch (e) { /* 收尾 */ }
  try {
    await post('/admin/observe/attack/config', new URLSearchParams({
      injections: JSON.stringify(inj0 || []),
    }).toString())
  } catch (e) { /* 收尾 */ }
  await b.close()
}
