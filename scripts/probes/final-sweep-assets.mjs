// final-sweep-assets.mjs —— 收藏后个人中心计数 + 券包张数（2026-10-19）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

// 探针账号口令从环境变量取（2026-10-19）：仓库内不落明文口令。
// 运行：PROBE_M_ACC=... PROBE_M_PWD=... node scripts/probes/<本文件>
const ACC = process.env.PROBE_M_ACC
const PWD = process.env.PROBE_M_PWD
if (!ACC || !PWD) { console.error('缺少 PROBE_M_ACC / PROBE_M_PWD'); process.exit(1) }
const BASE = 'http://localhost:8080'
const R = (t, v) => console.log(t.padEnd(36) + JSON.stringify(v))
const b = await open({ w: 1440, h: 900 })
try {
  await b.goto(BASE + '/#/login')
  await sleep(800)
  await b.evalJS(`
    const set = (sel, v) => { const el = document.querySelector(sel); const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) };
    set('#acc', ${JSON.stringify(ACC)}); set('#pwd', ${JSON.stringify(PWD)});
    [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录' && x.className.includes('btn-block')).click();
    'clicked'
  `)
  await sleep(2500)
  await b.evalJS(`location.hash = '#/merchant/user/info'; 'go'`)
  await sleep(3200)
  R('fav entry cell', await b.evalJS(`(() => { const c = [...document.querySelectorAll('.entry-cell')].find(x => x.innerText.includes('我的收藏')); return c ? c.querySelector('.ev').innerText : null })()`))
  R('wallet row', await b.evalJS(`(() => { const r = [...document.querySelectorAll('.ledger-row')].find(x => x.innerText.includes('优惠券')); return r ? r.innerText.replace(/\\n+/g, ' / ') : null })()`))
  R('points row', await b.evalJS(`(() => { const r = [...document.querySelectorAll('.ledger-row')].find(x => x.innerText.includes('可用积分')); return r ? r.innerText.replace(/\\n+/g, ' / ') : null })()`))
} finally { await b.close() }
