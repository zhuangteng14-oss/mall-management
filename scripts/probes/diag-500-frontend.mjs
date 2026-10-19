// 临时诊断（不入库）：500 并发期间前端主线程/渲染/请求耗时实录。
// 目的：判断「页面完全卡死」是主线程被 LivePreview 重绘占满，还是后端/连接层拖慢。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const OUT = '_work/diag-500'
const PWD = process.env.PROBE_ADMIN_PWD
if (!PWD) { console.error('缺少 PROBE_ADMIN_PWD'); process.exit(2) }
fs.mkdirSync(OUT, { recursive: true })

const b = await open({ w: 1680, h: 1050, headed: false })
try {
  await b.goto(BASE + '/admin.html', 2500)
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), 'admin'); set(document.querySelector('#pwd'), ${JSON.stringify(PWD)})
    ;[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x => x.textContent.trim() === '登录').click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }

  await b.evalJS(`location.hash = '#/admin/observe/attack-sim'`)
  let ready = null
  for (let i = 0; i < 60; i++) {
    await sleep(400)
    ready = await b.evalJS(`(() => ({
      err: (document.querySelector('.a-err') || {}).textContent || '',
      live: [...document.querySelectorAll('.lp-conn')].map(x => x.textContent.trim()),
      records: document.querySelectorAll('.a-ev').length,
    }))()`)
    if (ready.live.filter(x => x === '实时').length === 3 && ready.records > 0) break
  }

  // 设置并发 500 / 15 秒（Vue v-model 需要 input 事件）
  const form = await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    const nums = [...document.querySelectorAll('.a-run input[type=number]')]
    if (nums.length < 2) return { ok: false, nums: nums.length }
    set(nums[0], '500'); set(nums[1], '15')
    return { ok: true, concurrency: nums[0].value, duration: nums[1].value }
  })()`)
  await sleep(300)

  // ── 主线程/渲染/请求探针 ───────────────────────────────────────────────
  await b.evalJS(`(() => {
    window.__pf = { long: [], raf: [], mut: 0, fetches: [], t0: performance.now() }
    new PerformanceObserver((l) => { for (const e of l.getEntries()) __pf.long.push([e.startTime, e.duration]) })
      .observe({ type: 'longtask', buffered: true })
    let last = performance.now()
    ;(function loop(t) { __pf.raf.push(t - last); last = t; requestAnimationFrame(loop) })(last)
    const lp = document.querySelector('.lp')
    new MutationObserver((ms) => {
      let n = 0
      for (const m of ms) n += m.addedNodes.length + m.removedNodes.length
      __pf.mut += n
    }).observe(lp, { childList: true, subtree: true, characterData: true })
    const of = window.fetch
    window.fetch = function (...a) {
      const t = performance.now()
      const url = String((a[0] && a[0].url) || a[0])
      return of.apply(this, a).then((r) => {
        __pf.fetches.push({ url, ms: Math.round(performance.now() - t), status: r.status })
        return r
      }).catch((e) => {
        __pf.fetches.push({ url, ms: Math.round(performance.now() - t), err: String(e && e.name || e) })
        throw e
      })
    }
    return true
  })()`)

  await b.send('Profiler.enable')
  await b.send('Profiler.setSamplingInterval', { interval: 1000 })
  await b.send('Profiler.start')

  // 从页面点「开始」：走用户真实路径（fire → runAttack + status 轮询）
  const clicked = await b.evalJS(`(() => {
    const btn = document.querySelector('.a-run .a-btn-on')
    if (!btn) return { ok: false }
    btn.click()
    return { ok: true, text: btn.textContent.trim() }
  })()`)

  const samples = []
  let fetchSeen = 0
  for (let i = 0; i < 34; i++) {
    await sleep(1000)
    let snap = null
    try {
      snap = await b.evalJS(`(() => {
        const now = performance.now()
        const secAgo = now - 1000
        const longs = __pf.long.filter(x => x[0] >= secAgo)
        const rafs = __pf.raf.splice(0)
        const recent = __pf.fetches.slice(${fetchSeen})
        const lt = longs.map(x => x[1])
        const heap = performance.memory ? performance.memory.usedJSHeapSize : null
        return {
          t: Math.round(now - __pf.t0),
          longCount: longs.length,
          longMaxMs: lt.length ? Math.round(Math.max(...lt)) : 0,
          longTotalMs: lt.length ? Math.round(lt.reduce((a, b) => a + b, 0)) : 0,
          rafCount: rafs.length,
          rafMaxMs: rafs.length ? Math.round(Math.max(...rafs)) : 0,
          rafOver50: rafs.filter(x => x > 50).length,
          mut: __pf.mut,
          heapMB: heap ? Math.round(heap / 1048576) : null,
          lpMoves: document.querySelectorAll('.lp-move').length,
          lpLogs: document.querySelectorAll('.lp-list li').length,
          records: document.querySelectorAll('.a-ev').length,
          btn: (document.querySelector('.a-run .a-btn-on') || {}).textContent,
          running: !!document.querySelector('.a-run .a-btn-on[disabled]'),
          err: ((document.querySelector('.a-err') || {}).textContent || '').slice(0, 200),
          flash: ((document.querySelector('.a-flash, .a-note') || {}).textContent || '').slice(0, 200),
          fetched: recent,
        }
      })()`)
      // __pf.mut 是累计值 ⇒ 差分
      if (samples.length) snap.mutDelta = snap.mut - samples[samples.length - 1].mut
      fetchSeen += snap.fetched.length
      samples.push(snap)
    } catch (e) {
      samples.push({ t: i, error: String(e).slice(0, 300) })
    }
    if (i === 5 || i === 14) await b.shot(`${OUT}/frontend-t${i}.png`).catch(() => {})
  }

  const prof = await b.send('Profiler.stop').catch(() => null)
  if (prof && prof.profile) fs.writeFileSync(`${OUT}/frontend-profile.cpuprofile`, JSON.stringify(prof.profile))
  fs.writeFileSync(`${OUT}/frontend-samples.json`, JSON.stringify({ ready, form, clicked, samples }, null, 1))
  console.log(JSON.stringify({ ready, form, clicked }, null, 1))
  console.log('samples:', samples.length, '->', `${OUT}/frontend-samples.json`)
} finally {
  await b.close().catch(() => {})
}
