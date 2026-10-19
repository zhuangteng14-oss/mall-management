// b7-manual-attack-timeout.mjs —— 2026-10-19 手验排查：攻击页三条读接口 10 秒超时。
// 真后端（8088）+ 真前端（8080，admin.html 入口），不 mock。
// 只做取证：登录态就位后进攻击页，收集可见报错、页内真实请求耗时、资源时序、截图。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const API = 'http://localhost:8088'
const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'

const b = await open({ w: 1600, h: 1200, headed: false })
try {
  // 先到同源页，写入登录态（路由守卫只读 sessionStorage），再进攻击页
  await b.goto(WEB + '/admin.html#/login', 2500)
  await b.evalJS(`(() => {
    sessionStorage.setItem('loginUsername', 'admin')
    sessionStorage.setItem('role', '3')
    return Object.fromEntries(Object.keys(sessionStorage).map(k => [k, sessionStorage.getItem(k)]))
  })()`).then((r) => console.log('SESSION', JSON.stringify(r)))

  const t0 = Date.now()
  await b.goto(WEB + '/admin.html#/admin/observe/attack-sim', 12000)
  console.log('LOAD_MS', Date.now() - t0)

  const page = await b.evalJS(`(() => ({
    url: location.href,
    theme: document.documentElement.getAttribute('data-theme'),
    bodyText: (document.body.innerText || '').slice(0, 2500)
  }))()`)
  console.log('PAGE', JSON.stringify(page, null, 2))

  const res = await b.evalJS(`(() => performance.getEntriesByType('resource')
    .filter(e => e.name.includes(':8088'))
    .map(e => ({ path: e.name.replace(${JSON.stringify(API)}, ''), dur: Math.round(e.duration), status: e.responseStatus ?? null, size: e.transferSize })))()`)
  console.log('RESOURCES', JSON.stringify(res, null, 2))

  const direct = await b.evalJS(`(async () => {
    const one = async (path) => {
      const t = performance.now()
      try {
        const r = await fetch(${JSON.stringify(API)} + path, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
          body: ''
        })
        const txt = await r.text()
        return { path, status: r.status, ms: Math.round(performance.now() - t), len: txt.length }
      } catch (e) {
        return { path, error: String(e), ms: Math.round(performance.now() - t) }
      }
    }
    return Promise.all([
      one('/admin/observe/attack/scenarios'),
      one('/admin/observe/attack/records'),
      one('/admin/observe/attack/storage')
    ])
  })()`)
  console.log('DIRECT_AFTER_LOAD', JSON.stringify(direct, null, 2))

  await b.shotFull(EV + 'manual-1019-attack-page-full.png')
  console.log('SHOT', EV + 'manual-1019-attack-page-full.png')

  // 保持页面打开 10 秒，供外部 netstat 观察连接数
  await sleep(10000)
} finally {
  await b.close()
}
