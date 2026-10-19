// b7-sse-count-before.mjs —— 复现：单标签页内依次访问 指标→日志→状态→攻击。
// 三个 observe 页被 AdminShell 的 <keep-alive> 缓存，切走不卸载 ⇒ SSE 不 close。
// 浏览器打开期间从外部 netstat 统计 8088 的 ESTABLISHED 数。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const WEB = 'http://localhost:8080'
const b = await open({ w: 1400, h: 900, headed: false })
const step = async (name, hash, wait) => {
  await b.goto(WEB + '/admin.html#/admin/observe/' + hash, wait)
  console.log('STEP', name, 'at', new Date().toISOString())
}
try {
  await b.goto(WEB + '/admin.html#/login', 2500)
  await b.evalJS(`(() => {
    sessionStorage.setItem('loginUsername', 'admin')
    sessionStorage.setItem('role', '3')
    return true
  })()`)
  await step('metrics', 'metrics', 3000)
  await step('logs', 'logs', 3000)
  await step('timeline', 'timeline', 3000)
  await step('attack', 'attack-sim', 4000)
  console.log('HOLD_START', new Date().toISOString())
  await sleep(15000)
  console.log('HOLD_END', new Date().toISOString())
} finally {
  await b.close()
}
