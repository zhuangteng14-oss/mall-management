// diag-member-center.mjs —— 会员中心身份诊断（2026-10-19）
// 真实后端 8088 + dist 静态服务 8080：走真实登录，读 sessionStorage / 页面文案 / 控制台。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const R = (t, v) => console.log(t.padEnd(42) + JSON.stringify(v))

const b = await open({ w: 1440, h: 900 })
try {
  await b.goto(BASE + '/#/login')
  await sleep(800)
  await b.evalJS(`
    const set = (sel, v) => { const el = document.querySelector(sel); const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) };
    set('#acc', 'pmprobe1019b'); set('#pwd', 'pmprobe1019b');
    [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录' && x.className.includes('btn-block')).click();
    'clicked'
  `)
  await sleep(3000)
  R('hash after login = ', await b.evalJS('location.hash'))
  R('storage = ', await b.evalJS(`JSON.stringify({ loginUsername: sessionStorage.getItem('loginUsername'), role: sessionStorage.getItem('role'), customerid: sessionStorage.getItem('customerid'), merchantid: sessionStorage.getItem('merchantid'), token: !!sessionStorage.getItem('authToken') })`))

  await b.evalJS(`location.hash = '#/customer/user/member'; 'go'`)
  await sleep(2500)
  R('hash on member = ', await b.evalJS('location.hash'))
  R('member text = ', await b.evalJS(`(document.body.innerText || '').replace(/\\s+/g, ' ').slice(0, 500)`))
  R('storage on member = ', await b.evalJS(`JSON.stringify({ customerid: sessionStorage.getItem('customerid'), loginUsername: sessionStorage.getItem('loginUsername') })`))
} finally {
  await b.close()
}
