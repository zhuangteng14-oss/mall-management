// diag-goodsadd-upload3.mjs —— 复测「视觉隐藏」后 input 是否仍不可唤起文件选择器（2026-10-19）
import { open, sleep } from '../../docs/美化/_cdp.mjs'

// 探针账号口令从环境变量取（2026-10-19）：仓库内不落明文口令。
// 运行：PROBE_M_ACC=... PROBE_M_PWD=... node scripts/probes/<本文件>
const ACC = process.env.PROBE_M_ACC
const PWD = process.env.PROBE_M_PWD
if (!ACC || !PWD) { console.error('缺少 PROBE_M_ACC / PROBE_M_PWD'); process.exit(1) }

const BASE = 'http://localhost:8080'
const R = (t, v) => console.log(t.padEnd(46) + JSON.stringify(v))

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
  await b.evalJS(`location.hash = '#/merchant/user/add'; 'go'`)
  await sleep(2000)

  R('login hash', await b.evalJS('location.hash'))
  R('computed', await b.evalJS(`(() => {
    const el = document.querySelector('input[type=file]');
    if (!el) return null;
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return { cls: el.className, display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
      position: cs.position, w: cs.width, h: cs.height, rect: [r.x, r.y, r.width, r.height],
      offsetParent: !!el.offsetParent, connected: el.isConnected };
  })()`))

  // 记录 input.click 是否被调用
  await b.evalJS(`window.__clicks = 0; const orig = HTMLInputElement.prototype.click; HTMLInputElement.prototype.click = function () { window.__clicks++; window.__lastClick = this.type; return orig.apply(this, arguments) }; 'patched'`)

  let backendNodeId = null
  b.ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.method === 'Page.fileChooserOpened') backendNodeId = m.params.backendNodeId
  })
  await b.send('Page.setInterceptFileChooserDialog', { enabled: true })

  await b.evalJS(`document.querySelector('.picbox.main').click(); 'clicked-picbox'`)
  await sleep(1200)
  R('input.click calls (picbox)', await b.evalJS('window.__clicks'))
  R('fileChooserOpened (picbox)', backendNodeId)

  await b.evalJS(`document.querySelector('input[type=file]').click(); 'clicked-direct'`)
  await sleep(1200)
  R('input.click calls (direct)', await b.evalJS('window.__clicks'))
  R('fileChooserOpened (direct)', backendNodeId)
} finally {
  await b.close()
}
