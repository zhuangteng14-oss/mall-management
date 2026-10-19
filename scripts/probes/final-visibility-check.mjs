// 临时探针（不入库）：验证「页面隐藏 ⇒ SSE 主动让出连接，重新可见 ⇒ 自动接回」。
//
// 背景：浏览器对同一 origin 的 HTTP/1.1 连接上限是 6。攻击页的实时预览占 3 条 SSE，
// 日志/指标/状态页各占 1 条 —— 四个标签页同时开 = 6 条常驻流，普通请求（/attack/scenarios）
// 拿不到连接，10 秒后被 http.js abort 成「请求超时（10000ms）」。
//
// 本探针不靠「开四个标签页」这种不稳定现场，而是：
//   ① 在页面加载前包住 fetch 与 ReadableStreamDefaultReader，统计 SSE 连接数；
//   ② 进攻击页，等 3 条流全开；
//   ③ 在页面上下文里改写 document.visibilityState 并派发 visibilitychange（事件路径与真隐藏一致）；
//   ④ 断言：隐藏后活动 reader=0、且不再新开流；恢复可见后重新开到 3 条。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }

/** 注入到每个新文档：只统计 SSE 请求与流 reader，不改任何业务行为。 */
function initSource() {
  window.__sse = { opened: 0, active: 0, cancelled: 0, done: 0, urls: [] }
  const origFetch = window.fetch
  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url) || ''
    const p = origFetch.apply(this, arguments)
    if (/\/admin\/observe\/(metrics|orders|events)\/stream/.test(url)) {
      window.__sse.opened++
      window.__sse.urls.push(url)
      // ⚠️ 只包**这一条 SSE 响应**的 body reader：全局包 ReadableStream 会把其它
      //    普通流（sourcemap/HMR 等）也算进来，活动数会虚高（实测 3 条流报成 5）。
      p.then((res) => {
        if (!res || !res.body) return
        const origGetReader = res.body.getReader.bind(res.body)
        res.body.getReader = function (...a) {
          const reader = origGetReader(...a)
          window.__sse.active++
          let counted = true
          const release = () => {
            if (!counted) return
            counted = false
            window.__sse.active--
          }
          const origCancel = reader.cancel.bind(reader)
          reader.cancel = (...x) => { window.__sse.cancelled++; release(); return origCancel(...x) }
          const origRead = reader.read.bind(reader)
          reader.read = async (...x) => {
            const r = await origRead(...x)
            if (r.done) { window.__sse.done++; release() }
            return r
          }
          return reader
        }
      }, () => {})
    }
    return p
  }
}

const b = await open({ w: 1600, h: 1000, headed: false })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', { source: '(' + initSource.toString() + ')()' })
  await b.goto(BASE + '/admin.html', 2500)

  const login = await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    if (!acc || !pwd) return { ok: false, hash: location.hash }
    set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)})
    const btn = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x => x.textContent.trim() === '登录')
    if (!btn) return { ok: false, why: 'no btn' }
    btn.click(); return { ok: true }
  })()`)
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
  }

  // 进攻击页（LivePreview 在 mounted 时打开 metrics/orders/events 三条流）。
  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  let opened = null
  for (let i = 0; i < 60; i++) {
    await sleep(300)
    const s = await b.evalJS('window.__sse && ({ opened: window.__sse.opened, active: window.__sse.active })')
    opened = s && s.opened
    if (s && s.opened >= 3 && s.active >= 3) break
  }
  const beforeHide = await b.evalJS('({ ...window.__sse, state: document.visibilityState })')

  // 隐藏：改写 getter（恢复时会换回 visible），派发与真实切换同一个事件。
  const afterHide = await b.evalJS(`(async () => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    await new Promise(r => setTimeout(r, 800))
    return { ...window.__sse, state: document.visibilityState }
  })()`)

  // 恢复可见：应立刻重新打开三条流（opened 从 3 → 6）。后端正被队列排空占着时，
  // 某一条流的响应头可能晚一点回来 —— 轮询到 3 条 reader 都在，不拿固定 1.5 秒赌。
  const afterShow = await b.evalJS(`(async () => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
    for (let i = 0; i < 20 && window.__sse.active < 3; i++) {
      await new Promise(r => setTimeout(r, 300))
    }
    return { ...window.__sse, state: document.visibilityState }
  })()`)

  const verdict = {
    login,
    beforeHide,
    afterHide,
    afterShow,
    pass: {
      openedBeforeHide: beforeHide.opened >= 3,
      streamsReleasedWhileHidden: afterHide.active === 0,
      noNewStreamWhileHidden: afterHide.opened === beforeHide.opened,
      reconnectedWhenVisible: afterShow.active === 3 && afterShow.opened === beforeHide.opened + 3,
    },
  }
  console.log(JSON.stringify(verdict, null, 2))
  if (!Object.values(verdict.pass).every(Boolean)) process.exitCode = 1
} finally {
  await b.close().catch(() => {})
}
