// final-sweep-level-colors.mjs —— 等级标签 / 牌匾配色随 member_level.Theme（2026-10-19）
// 用法：PROBE_M_ACC / PROBE_M_PWD 从环境变量取；逐个等级切库后各跑一次。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const ACC = process.env.PROBE_M_ACC
const PWD = process.env.PROBE_M_PWD
if (!ACC || !PWD) { console.error('缺少 PROBE_M_ACC / PROBE_M_PWD'); process.exit(1) }

const R = (t, v) => console.log(t.padEnd(34) + JSON.stringify(v))

const b = await open({ w: 1440, h: 1000 })
try {
  await b.goto(BASE + '/#/login')
  await sleep(800)
  await b.evalJS(`
    const set = (sel, v) => { const el = document.querySelector(sel); const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) };
    set('#acc', ${JSON.stringify(ACC)}); set('#pwd', ${JSON.stringify(PWD)});
    [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录' && x.className.includes('btn-block')).click();
    'clicked'
  `)
  await sleep(2600)
  await b.evalJS(`location.hash = '#/merchant/user/info'; 'go'`)
  await sleep(3400)
  R('badge text', await b.evalJS(`(() => { const x = document.querySelector('.id-badge'); return x ? x.innerText : null })()`))
  R('badge class', await b.evalJS(`(document.querySelector('.id-badge') || {}).className`))
  R('badge fg/bg/border', await b.evalJS(`(() => { const x = document.querySelector('.id-badge'); if (!x) return null; const s = getComputedStyle(x); return { color: s.color, bg: s.backgroundColor, bgImg: s.backgroundImage, border: s.borderColor } })()`))
  R('plaque class', await b.evalJS(`(document.querySelector('.plaque') || {}).className`))
  R('plaque colors', await b.evalJS(`(() => {
    const g = (sel) => { const el = document.querySelector(sel); return el ? getComputedStyle(el).color : null };
    return { level: g('.plaque-mid .member-level'), balance: g('.plaque-mid .balance'), balanceSmall: g('.plaque-mid .balance small'), top: g('.plaque-top'), link: g('.plaque-link'), name: g('.plaque-bottom .name') }
  })()`))
  R('barcode bar color', await b.evalJS(`(() => { const x = document.querySelector('.barcode i'); return x ? getComputedStyle(x).backgroundColor : null })()`))
} finally {
  await b.close()
}
