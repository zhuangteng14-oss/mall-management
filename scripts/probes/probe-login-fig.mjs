// scripts/probes/probe-login-fig.mjs —— 登录页「品牌大图」两态取证（2026-10-09）
//
// 【要回答的两个问题，一条都不许靠猜】
//   ① 图**请求**通不通？—— 页面内 `fetch()` 读 8088 的状态码（8080 在 CORS 白名单里，能读到真状态），
//      外加 `performance.getEntriesByType('resource')` 看浏览器**有没有真去要**这张图。
//   ② 图**盒子**在不在、多大？—— `.brand-fig` 的 `getBoundingClientRect()` 与计算样式。
//      判据：若登录态 rect 高 = 0 或极小而注册态正常 ⇒ **布局回归**（B1 那条 `flex:0 1 auto; min-height:0`
//      把它压没了）；若两态 rect 一样、只是图 404 ⇒ **图没了**（不是渲染条件被改）。
//
// 用法：node scripts/probes/probe-login-fig.mjs   （只需 dev server 8080；本探针不 mock 8088）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const OUT = 'docs/agent/evidence/c3-block2/login-fig'
const b = await open({ w: 1440, h: 900, headed: false })

const probeTab = async (label) => {
  const r = await b.evalJS(`(() => {
    const el = document.querySelector('.brand-fig')
    if (!el) return { missing: true }
    const box = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    return {
      cls: el.className,
      w: Math.round(box.width), h: Math.round(box.height),
      top: Math.round(box.top), left: Math.round(box.left),
      bgImage: cs.backgroundImage,
      bgColor: cs.backgroundColor,
      aspectRatio: cs.aspectRatio,
      flex: cs.flex, minHeight: cs.minHeight,
      brandH: Math.round(document.querySelector('.brand').getBoundingClientRect().height),
      paneH: Math.round(document.querySelector('.pane').getBoundingClientRect().height),
      docH: document.documentElement.scrollHeight,
    }
  })()`)
  console.log(`\n── ${label} ──`)
  console.log(JSON.stringify(r, null, 2))
  await b.shot(`${OUT}/${label}.png`)
  return r
}

try {
  await b.goto('http://localhost:8080/#/login', 2500)

  // ① 页面内实测两张图的状态码（8080 → 8088 跨源；白名单里才读得到 status）
  const http = await b.evalJS(`(async () => {
    const out = {}
    for (const n of [1, 2]) {
      const u = 'http://localhost:8088/img/Mall-Section-' + n + '.jpg'
      try { const r = await fetch(u, { method: 'GET' }); out['sec' + n] = { status: r.status, type: r.headers.get('content-type') } }
      catch (e) { out['sec' + n] = { err: String(e) } }
    }
    return out
  })()`)
  console.log('① fetch 状态：', JSON.stringify(http))

  // ② 浏览器有没有真去要过这两张图
  const res = await b.evalJS(`JSON.stringify(performance.getEntriesByType('resource')
      .filter(e => /Mall-Section/.test(e.name))
      .map(e => ({ name: e.name, size: e.transferSize, dur: Math.round(e.duration), initiator: e.initiatorType })))`)
  console.log('② resource 条目：', res)

  // ③ 两态的盒子与计算样式（登录 tab = 默认态）
  const login = await probeTab('login-1440x900')
  await b.evalJS(`(() => { const btns = document.querySelectorAll('.ctabs button'); btns[1].click(); return 1 })()`)
  await sleep(900)
  const reg = await probeTab('register-1440x900')

  // ④ 短视口再测一次（B1 那条 `min-height:0` 只在短视口才可能把它压到 0）
  await b.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 640, deviceScaleFactor: 1, mobile: false })
  await sleep(600)
  // ⚠️ 上面那一步把 tab 切到了「注册」，视口缩小**不会**把它切回来 —— 不点回「登录」就量下去，
  //    量到的是注册态（`cls="brand-fig reg"` + `Mall-Section-1.jpg`）却标成 `loginShort`，
  //    截图与摘要全是**错标**的证据（2026-10-09 首跑踩到：三份短视口产物全带 `reg`）。
  await b.evalJS(`(() => { document.querySelectorAll('.ctabs button')[0].click(); return 1 })()`)
  await sleep(900)
  const loginShort = await probeTab('login-1280x640')
  await b.evalJS(`(() => { const btns = document.querySelectorAll('.ctabs button'); btns[1].click(); return 1 })()`)
  await sleep(900)
  const regShort = await probeTab('register-1280x640')

  console.log('\n══ 判定用摘要 ══')
  for (const [k, v] of Object.entries({ login, reg, loginShort, regShort })) {
    console.log(`${k.padEnd(14)} fig=${v.w}x${v.h}  brand=${v.brandH}  pane=${v.paneH}  docH=${v.docH}  cls="${v.cls}"`)
  }
  console.log('\n截图：' + OUT)
} finally {
  await b.close()
}
