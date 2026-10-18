// 2026-10-19〈应用层性能隔离〉现场探针（临时产物）：真实后端、500 并发 × 60 秒，
// 从攻击页 UI 发起（与用户操作同一路径），记录页面请求耗时 / 演练期间页面读数 / 截图。
// ⚠️ 不 mock 任何请求（与 `_cdp.mjs` 默认一致）；登录口令只从 PROBE_ADMIN_PWD 读。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const OUT = process.env.OUT_DIR || '_work/perf-isolation-shots'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }
fs.mkdirSync(OUT, { recursive: true })

const b = await open({ w: 1680, h: 1050, headed: false })
const result = { startedAt: new Date().toISOString() }
try {
  await b.goto(BASE + '/admin.html', 2500)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    const acc = document.querySelector('#acc'), pwd = document.querySelector('#pwd')
    // ⚠️ 别用「文本=登录」的裸选择器：页签「登录」排在最前，点了只会切页签、不会提交。
    const submit = [...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x => x.textContent.trim() === '登录' && !x.disabled)
    if (acc && pwd && submit) { set(acc, 'admin'); set(pwd, ${JSON.stringify(PWD)}); submit.click() }
  })()`)
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    if (/^#\/admin/.test(await b.evalJS('location.hash'))) break
  }

  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  for (let i = 0; i < 60; i++) {
    await sleep(400)
    const ready = await b.evalJS(`(() => ({
      presets: document.querySelectorAll('.a-preset').length,
      storage: document.body.innerText.includes('可观测事件台账存储'),
      err: (document.querySelector('.a-err') || {}).textContent || '',
    }))()`)
    if (ready.presets > 0 && ready.storage) break
  }

  // ① 开跑前：页头 + 存储水位卡
  await b.evalJS(`window.scrollTo(0, 0)`)
  await sleep(500)
  await b.shot(`${OUT}/01-before-top.png`)
  await b.evalJS(`(() => {
    const h = [...document.querySelectorAll('.a-h2')].find(x => x.textContent.includes('可观测事件台账存储'))
    if (h) h.scrollIntoView({ block: 'start' })
    return !!h
  })()`)
  await sleep(700)
  const storageBefore = await b.evalJS(`(() => {
    const h = [...document.querySelectorAll('.a-h2')].find(x => x.textContent.includes('可观测事件台账存储'))
    const card = h && h.nextElementSibling
    return card ? card.innerText.replace(/\\s+/g, ' ').slice(0, 260) : ''
  })()`)
  await b.shot(`${OUT}/02-before-storage.png`)
  result.storageBefore = storageBefore

  // ② 设 500 并发 / 60 秒 / 拉满，点「开始」
  const setup = await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    const inputs = [...document.querySelectorAll('.a-run input[type=number]')]
    if (inputs.length < 2) return { ok: false, why: '找不到并发/秒数输入框' }
    set(inputs[0], '500'); set(inputs[1], '60')
    const tier = [...document.querySelectorAll('.a-tier')].find(x => x.textContent.includes('拉满'))
    if (!tier) return { ok: false, why: '找不到拉满档' }
    tier.click()
    return { ok: true, concurrency: inputs[0].value, seconds: inputs[1].value }
  })()`)
  result.setup = setup
  if (!setup.ok) throw new Error(setup.why)
  // 开跑面板置于视口内：中段截图要能看见「演练中」与实时读数，而不是停在存储卡。
  await b.evalJS(`document.querySelector('.a-run').scrollIntoView({ block: 'center' })`)
  await sleep(500)
  await b.evalJS(`document.querySelector('.a-btn-on').click()`)
  result.runClickedAt = new Date().toISOString()

  // ③ 演练期间：每秒读一次页面状态，20 秒处截一张
  let midShot = false
  let storageShot = false
  let wasRunning = false
  let finalFlash = ''
  const evalLatency = []
  for (let i = 0; i < 170; i++) {
    await sleep(1000)
    const tEval = Date.now()
    const st = await b.evalJS(`(() => {
      const run = document.querySelector('.a-btn-on')
      const flash = document.querySelector('.a-flash')
      const prog = [...document.querySelectorAll('.a-prog-t')].map(x => x.innerText.replace(/\\s+/g, ' '))
      return {
        running: !!run && run.innerText.includes('演练中'),
        flash: flash ? flash.innerText.replace(/\\s+/g, ' ') : '',
        prog,
      }
    })()`)
    evalLatency.push(Date.now() - tEval)
    if (st.running) wasRunning = true
    if (st.flash) finalFlash = st.flash
    if (!midShot && wasRunning && i >= 15) {
      midShot = true
      result.midProg = st.prog
      // 视口保持开跑面板：截图要证明「演练中」而不是只剩一个跑完的横幅。
      await b.shot(`${OUT}/03-during-run.png`)
    }
    if (!storageShot && wasRunning && i >= 30) {
      storageShot = true
      await b.evalJS(`(() => { const h = [...document.querySelectorAll('.a-h2')].find(x => x.textContent.includes('可观测事件台账存储')); if (h) h.scrollIntoView({ block: 'start' }) })()`)
      await sleep(400)
      await b.shot(`${OUT}/04-during-storage.png`)
    }
    if (wasRunning && !st.running && st.flash) break
    if (i > 10 && !wasRunning && st.flash.includes('演练没跑起来')) break
  }
  await sleep(1200)
  result.flash = finalFlash
  // 每秒一次 CDP evalJS 的往返耗时：页面若被前端重建卡死，这个数会先炸。
  result.evalLatency = {
    n: evalLatency.length,
    maxMs: Math.max(0, ...evalLatency),
    meanMs: Math.round(evalLatency.reduce((a, x) => a + x, 0) / Math.max(1, evalLatency.length)),
    over1000: evalLatency.filter(x => x >= 1000).length,
  }

  // ④ 跑后：页头 + 存储卡（水位应随这一轮上涨）
  await b.evalJS(`window.scrollTo(0, 0)`)
  await sleep(400)
  await b.shot(`${OUT}/05-after-top.png`)
  await b.evalJS(`(() => { const h = [...document.querySelectorAll('.a-h2')].find(x => x.textContent.includes('可观测事件台账存储')); if (h) h.scrollIntoView({ block: 'start' }) })()`)
  await sleep(700)
  result.storageAfter = await b.evalJS(`(() => {
    const h = [...document.querySelectorAll('.a-h2')].find(x => x.textContent.includes('可观测事件台账存储'))
    const card = h && h.nextElementSibling
    return card ? card.innerText.replace(/\\s+/g, ' ').slice(0, 260) : ''
  })()`)
  await b.shot(`${OUT}/06-after-storage.png`)

  // ⑤ 这一页发出的 8088 请求耗时（Resource Timing）——「页面还超不超时」的直接证据
  result.pageRequests = await b.evalJS(`(() => {
    const rs = performance.getEntriesByType('resource')
      .filter(e => e.name.includes(':8088'))
      .map(e => ({ url: e.name.split('/admin/observe/')[1] || e.name, ms: Math.round(e.duration), status: e.responseStatus || 0 }))
    const slow = rs.filter(r => r.ms >= 1000)
    return {
      total: rs.length,
      maxMs: rs.reduce((a, r) => Math.max(a, r.ms), 0),
      over10s: rs.filter(r => r.ms >= 10000).length,
      slowTop: slow.sort((a, b) => b.ms - a.ms).slice(0, 12),
    }
  })()`)
  result.finishedAt = new Date().toISOString()
} catch (e) {
  result.error = String(e)
  try { await b.shot(`${OUT}/99-error.png`) } catch (e2) {}
} finally {
  console.log(JSON.stringify(result, null, 2))
  await b.close()
}
