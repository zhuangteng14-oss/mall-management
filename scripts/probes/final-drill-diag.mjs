// 临时诊断：确认三页 Vue 状态可读、SSE 已连、管理员会话存在（不入库）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }

const b = await open({ w: 1600, h: 1000, headed: false })
try {
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
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }
  const cookie = await b.evalJS('document.cookie')
  const hash = await b.evalJS('location.hash')

  async function dump(page, sel, expr) {
    await b.evalJS(`location.hash = '#/admin/observe/${page}'`)
    let val = null
    for (let i = 0; i < 40; i++) {
      await sleep(500)
      val = await b.evalJS(`(() => {
        const el = document.querySelector(${JSON.stringify(sel)})
        if (!el || !el.__vueParentComponent) return null
        const st = el.__vueParentComponent.setupState
        try { return (${expr})(st) } catch (e) { return { err: String(e) } }
      })()`)
      if (val && !val.err && !(val.notReady)) break
    }
    return val
  }

  const metrics = await dump('metrics', '.a-mp', `(st) => {
    const row = st.latestRow
    const s = st.streamState || st.sseState || null
    return { keys: Object.keys(st).slice(0, 80), row: row ? {
      count: row.count, qps: row.qps, hit: row.hit, miss: row.miss, hitRate: row.hitRate,
      p99: row.p99, mean: row.mean, errors4xx: row.errors4xx, errors5xx: row.errors5xx
    } : null, health: st.healthView || st.health || null, mqUp: st.mqUp ?? null,
      notReady: !row }
  }`)
  const logs = await dump('logs', '.a-el', `(st) => {
    return { keys: Object.keys(st).slice(0, 80), mqConnected: st.mqConnected, phaseText: st.phaseText,
      events: (st.rows || st.events || []).slice(0, 2), notReady: false }
  }`)
  const orders = await dump('timeline', '.a-of', `(st) => {
    return { keys: Object.keys(st).slice(0, 80), counts: st.counts || st.stateCounts || null,
      total: st.total, step: st.stepView || st.steps || null, notReady: false }
  }`)

  console.log(JSON.stringify({ login, hash, cookieHasSession: /JSESSIONID=/.test(cookie), metrics, logs, orders }, null, 2))
} finally { await b.close().catch(() => {}) }
