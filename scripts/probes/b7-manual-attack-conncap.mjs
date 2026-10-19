// b7-manual-attack-conncap.mjs —— 确定性复现「6 条 SSE 占满浏览器同源连接池」。
// 在同一个页面里开 6 条 SSE 长连接，再打普通 POST，观察是否稳定等满 10 秒；
// 释放 1 条后重打，观察是否立刻恢复。真后端 8088，不 mock。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const API = 'http://localhost:8088'

const b = await open({ w: 1200, h: 800, headed: false })
try {
  await b.goto(WEB + '/admin.html#/login', 2500)

  const out = await b.evalJS(`(async () => {
    const post = (path) => fetch(${JSON.stringify(API)} + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: ''
    })
    const paths = [
      '/admin/observe/metrics/stream',
      '/admin/observe/orders/stream',
      '/admin/observe/events/stream',
      '/admin/observe/metrics/stream',
      '/admin/observe/orders/stream',
      '/admin/observe/events/stream'
    ]
    const held = await Promise.all(paths.map(p => post(p).catch(e => ({ error: String(e) }))))
    await new Promise(r => setTimeout(r, 800))
    const openCount = held.filter(r => r && r.body).length

    const timed = async () => {
      const t = performance.now()
      const ctrl = new AbortController()
      const timer = setTimeout(() => ctrl.abort(), 10000)
      try {
        const r = await fetch(${JSON.stringify(API)} + '/admin/observe/attack/scenarios', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
          body: '', signal: ctrl.signal
        })
        const body = await r.text()
        return { ok: true, status: r.status, ms: Math.round(performance.now() - t), len: body.length }
      } catch (e) {
        return { ok: false, error: e.name + ': ' + e.message, ms: Math.round(performance.now() - t) }
      } finally { clearTimeout(timer) }
    }

    const whileSaturated = await timed()
    // 释放一条流
    try { await held[0].body.cancel() } catch (e) {}
    await new Promise(r => setTimeout(r, 500))
    const afterFree = await timed()
    // 收掉剩余，避免留下服务端线程
    for (const r of held.slice(1)) { try { await r.body.cancel() } catch (e) {} }
    return { openCount, whileSaturated, afterFree }
  })()`)

  console.log(JSON.stringify(out, null, 2))
  await sleep(1000)
} finally {
  await b.close()
}
