// 任务 A UI 验收：演练失败 → 出现「一键重置演练商品库存」→ 点它走**真**复位端点 → 自动重跑上一轮参数。
// 失败只注入 `/attack/run` 一处（CDP Fetch 拦成 500），复位端点不拦、打真后端。
// 目的：证明「失败态按钮存在 + 文案 + 自动重试」这三件事由前端真实代码路径驱动，而不是靠改库造出来的。
import { open, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080/admin.html'
const OUT = 'docs/agent/evidence/c3-block7/b7a2-reset'
const ADMIN_ACC = process.env.PROBE_ADMIN_ACC || 'admin'
const ADMIN_PWD = process.env.PROBE_ADMIN_PWD || ''

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const r = report('任务A 复位入口')

if (!ADMIN_PWD) {
  console.error('缺少 PROBE_ADMIN_PWD（用法：PROBE_ADMIN_PWD="$(cat _work/admin-pass)" node scripts/probes/probe-b7a2-reset.mjs）')
  process.exit(2)
}

const b = await open({ w: 1440, h: 1000, headed: false })
try {
  // 登录（dev 闸关，但前端仍走正常登录页）
  await b.goto(`${BASE}#/login`, 2500)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), ${JSON.stringify(ADMIN_ACC)})
    set(document.querySelector('#pwd'), ${JSON.stringify(ADMIN_PWD)})
    const btn = document.querySelector('button.btn.btn-primary.btn-block')
    if (btn) btn.click()
  })()`)
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
  }
  console.log('LOGIN', await b.evalJS('location.hash'))

  await b.goto(`${BASE}#/admin/observe/attack-sim`, 3000)
  // 等场景清单到（按钮出现）
  let preset = null
  for (let i = 0; i < 40; i++) {
    preset = await b.evalJS(`(() => {
      const el = document.querySelector('.a-preset')
      return el ? el.textContent.trim() : null
    })()`)
    if (preset) break
    await sleep(250)
  }
  r.ok('场景清单已渲染', !!preset, preset)

  // ── 只拦 /attack/run：OPTIONS 放行预检，POST 回 500 ──
  await b.send('Fetch.enable', { patterns: [{ urlPattern: '*admin/observe/attack/run*' }] })
  let runAttempts = 0
  let blocked = true
  b.ws.on('message', async (raw) => {
    let m
    try { m = JSON.parse(raw) } catch (e) { return }
    if (m.method !== 'Fetch.requestPaused') return
    const { requestId, request } = m.params
    const baseHeaders = [
      { name: 'Access-Control-Allow-Origin', value: 'http://localhost:8080' },
      { name: 'Access-Control-Allow-Credentials', value: 'true' },
      { name: 'Access-Control-Allow-Headers', value: 'Authorization, Content-Type' },
      { name: 'Access-Control-Allow-Methods', value: 'GET,POST,OPTIONS' },
      { name: 'Content-Type', value: 'application/json' }
    ]
    try {
      if (request.method === 'OPTIONS') {
        await b.send('Fetch.fulfillRequest', { requestId, responseCode: 204, responseHeaders: baseHeaders, body: '' })
        return
      }
      // 真后端一轮演练要跑几秒；这里留 1200ms，让「已复位：…」先上屏（否则重跑失败会在同一帧覆盖它）
      await sleep(1200)
      const body = Buffer.from(JSON.stringify({
        code: 500,
        message: '库里挑不到可演练的商品（探针注入，用于验证复位入口）'
      })).toString('base64')
      runAttempts += 1
      const code = blocked ? 500 : 200
      await b.send('Fetch.fulfillRequest', { requestId, responseCode: code, responseHeaders: baseHeaders, body })
    } catch (e) { /* 导航中 */ }
  })

  // 点第一个预置场景（列表可能因后台刷新短暂重渲染 ⇒ 重试）
  let clicked = false
  for (let i = 0; i < 20 && !clicked; i++) {
    clicked = await b.evalJS(`(() => {
      const el = document.querySelector('.a-preset')
      if (!el) return false
      el.click()
      return true
    })()`)
    if (!clicked) await sleep(250)
  }
  r.ok('已点击预置场景', clicked)
  // 等失败态
  let fail = null
  for (let i = 0; i < 60; i++) {
    fail = await b.evalJS(`(() => {
      const rec = document.querySelector('.a-recover')
      const flash = document.querySelector('.a-flash')
      return {
        hasRecover: !!rec,
        recoverText: rec ? rec.textContent.replace(/\\s+/g, ' ').trim() : '',
        flash: flash ? flash.textContent.trim() : '',
        btn: rec ? (rec.querySelector('button') || {}).textContent : ''
      }
    })()`)
    if (fail.hasRecover) break
    await sleep(250)
  }
  r.ok('失败后出现复位横幅', fail && fail.hasRecover, fail && fail.recoverText.slice(0, 60))
  r.ok('错误提示是「演练没跑起来」', !!fail && fail.flash.includes('演练没跑起来'), fail && fail.flash)
  r.ok('按钮文案 = 一键重置演练商品库存', !!fail && /一键重置演练商品库存/.test(fail.btn), fail && fail.btn)
  console.log('FAIL_STATE', JSON.stringify(fail))
  await b.shot(`${OUT}/01-失败态-复位入口.png`)

  // ── 点复位：真调 /reset（不拦）→ 成功后自动重跑（仍被拦，验证「重试确实发生」）──
  // 「已复位」提示会被紧接着的重跑失败覆盖 ⇒ 用 MutationObserver 逐条记下 flash 文案，别靠采样。
  await b.evalJS(`(() => {
    window.__flashLog = []
    const rec = () => {
      const f = document.querySelector('.a-flash')
      if (!f) return
      const t = f.textContent.trim()
      if (t && window.__flashLog[window.__flashLog.length - 1] !== t) window.__flashLog.push(t)
    }
    rec()
    window.__flashObs = new MutationObserver(rec)
    window.__flashObs.observe(document.body, { childList: true, subtree: true, characterData: true })
    return true
  })()`)
  // 网络侧留痕：复位端点到底调没调、回什么码
  await b.send('Network.enable')
  const netLog = []
  b.ws.on('message', (raw) => {
    let m
    try { m = JSON.parse(raw) } catch (e) { return }
    if (m.method === 'Network.responseReceived' && /\/admin\/observe\/attack\//.test(m.params.response.url)) {
      netLog.push({ url: m.params.response.url.replace(/^.*\/admin\/observe\/attack\//, ''), status: m.params.response.status })
    }
  })
  const before = runAttempts
  await b.evalJS(`(() => {
    const rec = document.querySelector('.a-recover')
    const btn = rec && rec.querySelector('button')
    if (btn) btn.click()
    return !!btn
  })()`)
  let flashes = []
  let retried = 0
  let shotTaken = false
  for (let i = 0; i < 120; i++) {
    const cur = await b.evalJS(`(() => { const f = document.querySelector('.a-flash'); return f ? f.textContent.trim() : '' })()`)
    flashes = await b.evalJS('window.__flashLog || []')
    // 「已复位」只在重跑的 500 回来之前可见 ⇒ 一看到当前文案就是它，立刻截屏
    if (!shotTaken && /已复位/.test(cur)) {
      await b.shot(`${OUT}/02-复位成功并自动重跑.png`)
      console.log('SHOT2_DOM', JSON.stringify({ flash: cur.slice(0, 70), hasRecover: await b.evalJS(`!!document.querySelector('.a-recover')`) }))
      shotTaken = true
    }
    retried = runAttempts - before
    if (flashes.some((t) => /已复位/.test(t)) && retried > 0) break
    await sleep(80)
  }
  if (!shotTaken) {
    await b.shot(`${OUT}/02-复位成功并自动重跑.png`)
    console.log('SHOT2_DOM', JSON.stringify({ flash: '(没抓到已复位上屏)', hasRecover: false }))
  }
  const okFlash = flashes.find((t) => /已复位/.test(t)) || ''
  console.log('NET', JSON.stringify(netLog))
  r.ok('复位成功提示来自真后端', !!okFlash, okFlash.slice(0, 90))
  r.ok('复位后自动重跑上一轮（/run 再次被调用）', retried > 0, { retried, runAttempts })
  console.log('FLASHES', JSON.stringify(flashes))

  const bad = r.dump()
  console.log('VERDICT', JSON.stringify({ okFlash: /已复位/.test(okFlash), retried }))
  process.exitCode = bad ? 1 : 0
} finally {
  await b.close()
}
