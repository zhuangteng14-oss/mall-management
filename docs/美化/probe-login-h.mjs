// probe-login-h.mjs —— 2026-10-08 用户 ① 复测：「任何视口不可滑」到底守到哪里。
// 口径：登录页（独立路由 `#/login`，不在壳里）**两个面**（登录 / 注册）× 一串视口高，
//   读 文档可滚量（docH − innerH）与两栏的**真实内部高**（scrollHeight），以及左栏那张图的实高
//   ⇒ 判断「地板」在哪一栏、还差多少、以及左栏还能不能压。
import { open, sleep } from './_cdp.mjs'

const b = await open({ w: 1440, h: 900 })
const M = `(() => {
  const g = (s) => { const e = document.querySelector(s); return e ? { h: Math.round(e.getBoundingClientRect().height), sh: e.scrollHeight, ch: e.clientHeight } : null }
  const slide = document.querySelector('section[data-page="register"]')
  const last = (sel) => { const e = document.querySelector(sel); if (!e) return null; const r = e.getBoundingClientRect(); return { top: Math.round(r.top), bot: Math.round(r.bottom), h: Math.round(r.height) } }
  return JSON.stringify({
    face: slide && slide.classList.contains('on') ? '注册' : '登录',
    docH: document.documentElement.scrollHeight, innerH: innerHeight,
    over: document.documentElement.scrollHeight - innerHeight,
    auth: g('.auth'), brand: g('.brand'), fig: g('.brand-fig'), facts: g('.facts'),
    pane: g('.pane'), shell: g('.form-shell'), paneTop: last('.pane-top'), page: last('.page.on'),
    swap: last('.page.on .swap'), btn: last('.page.on .btn-block'), f1: last('.page.on .f:nth-of-type(1)'), flast: last('.page.on .f:last-of-type')
  })
})()`
try {
  for (const [w, h] of [[1440, 900], [1440, 800], [1440, 780], [1440, 768], [1440, 761], [1440, 760], [1366, 768], [1440, 720], [1280, 720], [1280, 700], [1280, 667], [1280, 650], [1280, 640], [1280, 600]]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false })
    await b.goto('http://localhost:8080/#/login', 900)
    await b.send('Page.reload', { ignoreCache: true }); await sleep(1500)   // ⚠️ 必须**重挂**：hash 相同 ⇒ 组件复用，`isSlide` 会带着上一轮的态（2026-10-08 踩过）
    for (const face of ['登录', '注册']) {
      if (face === '注册') { await b.evalJS(`(() => { const t = document.querySelector('section[data-page="login"] .swap button'); if (t) t.click(); return 1 })()`); await sleep(700) }
      const m = JSON.parse(await b.evalJS(M))
      console.log(`${String(w)}×${String(h)}  ${m.face}  可滚 ${String(m.over).padStart(4)}  docH ${String(m.docH).padStart(4)} / innerH ${String(m.innerH).padStart(4)}  左栏 ${m.brand && m.brand.h}(图 ${m.fig && m.fig.h})  右栏 ${m.pane && m.pane.h}  表单 ${m.shell && m.shell.h}  paneTop ${m.paneTop && m.paneTop.h}  面 ${m.page && m.page.h}  swap ${m.swap && (m.swap.bot + '-' + m.swap.h)}  按钮 ${m.btn && (m.btn.bot + '-' + m.btn.h)}`.replace(/undefined/g, '?'))
    }
  }
} finally { await b.close() }
