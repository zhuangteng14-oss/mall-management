// 临时诊断 2：读原始帧形状与订单状态计数（不入库）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
const b = await open({ w: 1600, h: 1000, headed: false })
try {
  await b.goto(BASE + '/admin.html', 2500)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), 'admin'); set(document.querySelector('#pwd'), ${JSON.stringify(PWD)})
    ;[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x => x.textContent.trim() === '登录').click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }

  await b.evalJS(`location.hash = '#/admin/observe/metrics'`)
  await sleep(4000)
  const metrics = await b.evalJS(`(() => { const st = document.querySelector('.a-mp').__vueParentComponent.setupState; return {
    healthLevel: st.healthLevel, healthLabel: st.healthLabel, healthReasons: JSON.parse(JSON.stringify(st.healthReasons || [])),
    phase: st.phase, phaseText: st.phaseText, latest: JSON.parse(JSON.stringify(st.latest || null)),
    shown: JSON.parse(JSON.stringify(st.shown || null)), row: st.latestRow ? JSON.parse(JSON.stringify(st.latestRow)) : null
  } })()`)

  await b.evalJS(`location.hash = '#/admin/observe/timeline'`)
  await sleep(4000)
  const orders = await b.evalJS(`(() => { const st = document.querySelector('.a-of').__vueParentComponent.setupState; return {
    phase: st.phase, counts: JSON.parse(JSON.stringify(st.counts || {})), total: st.total,
    STATES: JSON.parse(JSON.stringify(st.STATES || [])), capturedTotal: st.capturedTotal,
    recentMoves: JSON.parse(JSON.stringify((st.recentMoves || []).slice(0,3)))
  } })()`)

  const cookies = (await b.send('Network.getAllCookies')).cookies.filter(c => /8088|JSESSIONID/.test(c.domain + c.name)).map(c => ({ name: c.name, domain: c.domain, httpOnly: c.httpOnly, valueLen: (c.value||'').length }))
  console.log(JSON.stringify({ metrics, orders, cookies }, null, 2))
} finally { await b.close().catch(() => {}) }
