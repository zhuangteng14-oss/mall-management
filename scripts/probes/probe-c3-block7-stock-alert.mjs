// probe-c3-block7-stock-alert.mjs —— 低库存告警的商家站内信取证（2026-10-19）。
// 前置：已把 GoodsId=78 的库存改到 <10（告警已落库），之后又恢复；本探针只读站内信。
import fs from 'node:fs'
import { open, sleep, report } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const SHOT = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block7/'
const R = report('c3-block7-stock-alert')
const CREDS = {}
for (const line of fs.readFileSync('C:/Users/86159/Desktop/软件工程/期末项目/_work/demo-pw.txt', 'utf8').split(/\r?\n/)) {
  const m = line.match(/^([^|\s]+)\s*\|\s*(.+?)\s*$/); if (m && m[1] !== 'UserName' && m[1] !== '==') CREDS[m[1]] = m[2]
}

const b = await open({ w: 1440, h: 900 })
try {
  await b.goto(BASE + '/#/login', 1800)
  for (let i = 0; i < 40; i++) {
    const ok = await b.evalJS(`(() => { const a = document.querySelector('#acc'), p = document.querySelector('#pwd'); return !!(a && p) })()`)
    if (ok) break
    await sleep(300)
  }
  await b.evalJS(`(() => {
    const set = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) }
    set(document.querySelector('#acc'), 'chenguang_parts'); set(document.querySelector('#pwd'), ${JSON.stringify(CREDS['chenguang_parts'])})
    ;[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(e => e.textContent.trim() === '登录').click()
  })()`)
  for (let i = 0; i < 30; i++) { const h = await b.evalJS('location.hash'); if (/^#\/merchant/.test(h)) break; await sleep(500) }

  await b.goto(BASE + '/#/merchant/msg?tab=notify', 2000)
  let found = null
  for (let i = 0; i < 30; i++) {
    found = await b.evalJS(`(() => {
      const t = document.body.innerText
      const k = t.indexOf('库存')
      return { has: k >= 0, snippet: k >= 0 ? t.slice(Math.max(0, k - 120), k + 160) : t.slice(0, 200) }
    })()`)
    if (found.has) break
    await sleep(500)
  }
  R.ok('商家站内信出现低库存告警', found.has, found && found.snippet)
  await b.shot(SHOT + 'b7-06-stock-alert.png')
} finally { await b.close() }
process.exit(R.dump() ? 1 : 0)
