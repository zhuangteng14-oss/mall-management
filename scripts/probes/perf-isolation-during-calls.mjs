// 2026-10-19〈应用层性能隔离〉对照探针（临时产物）：500 并发「拉满」期间，
// 从**页面上下文**发三条普通后台请求（不是 Node 侧），量它们在浏览器里真正等了多久。
// 判据：三条都必须在 `api/http.js` 的 10s 超时之内回来；否则「页面不再超时」不成立。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const PWD = process.env.PROBE_ADMIN_PWD
const BASE = 'http://localhost:8088'
const b = await open({ w: 1680, h: 1050, headed: false })
const out = { calls: [], evalLatency: [] }

try {
  await b.goto('http://localhost:8080/admin.html', 2500)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), 'admin')
    set(document.querySelector('#pwd'), ${JSON.stringify(PWD)})
    ;[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x => x.textContent.trim() === '登录' && !x.disabled).click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }
  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  for (let i = 0; i < 60; i++) { await sleep(400); if ((await b.evalJS(`document.querySelectorAll('.a-preset').length`)) > 0) break }

  const setup = await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    const inputs = [...document.querySelectorAll('.a-run input[type=number]')]
    set(inputs[0], '500'); set(inputs[1], '60')
    ;[...document.querySelectorAll('.a-tier')].find(x => x.textContent.includes('拉满')).click()
    return { concurrency: inputs[0].value, seconds: inputs[1].value }
  })()`)
  out.setup = setup
  await b.evalJS(`document.querySelector('.a-btn-on').click()`)

  const call = (path, body = '') => b.evalJS(`(async () => {
    const t0 = performance.now()
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 12000)
    try {
      const r = await fetch(${JSON.stringify(BASE)} + ${JSON.stringify(path)}, {
        method: 'POST', credentials: 'include', signal: ctl.signal,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: ${JSON.stringify(body)},
      })
      const text = await r.text()
      return { status: r.status, ms: Math.round(performance.now() - t0), len: text.length, head: text.slice(0, 80) }
    } catch (e) {
      return { status: -1, ms: Math.round(performance.now() - t0), head: String(e) }
    } finally { clearTimeout(timer) }
  })()`)

  const probeAt = async (label) => {
    const storage = await call('/admin/observe/attack/storage')
    const records = await call('/admin/observe/attack/records', 'limit=20&offset=0')
    const scenarios = await call('/admin/observe/attack/scenarios')
    out.calls.push({ label, storage, records, scenarios })
  }

  let running = false
  for (let i = 0; i < 160; i++) {
    await sleep(1000)
    const t = Date.now()
    const st = await b.evalJS(`(() => {
      const run = document.querySelector('.a-btn-on')
      const flash = document.querySelector('.a-flash')
      return { running: !!run && run.innerText.includes('演练中'), flash: flash ? flash.innerText.replace(/\\s+/g, ' ') : '' }
    })()`)
    out.evalLatency.push(Date.now() - t)
    if (st.running) running = true
    if ([15, 30, 45].includes(i) && st.running) await probeAt(i + 's')
    if (running && !st.running) { out.flash = st.flash; break }
  }
  out.evalLatency = {
    n: out.evalLatency.length,
    maxMs: Math.max(0, ...out.evalLatency),
    meanMs: Math.round(out.evalLatency.reduce((a, x) => a + x, 0) / Math.max(1, out.evalLatency.length)),
    over1000: out.evalLatency.filter(x => x >= 1000).length,
  }
} catch (e) {
  out.error = String(e)
} finally {
  console.log(JSON.stringify(out, null, 2))
  await b.close()
}
