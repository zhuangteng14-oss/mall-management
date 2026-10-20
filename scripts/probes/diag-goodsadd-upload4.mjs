// diag-goodsadd-upload4.mjs —— 用**受信任的鼠标事件**（而非 JS .click()）验证文件选择器 + 预览（2026-10-19）
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

  let chooser = null
  b.ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.method === 'Page.fileChooserOpened') chooser = m.params
  })
  await b.send('Page.setInterceptFileChooserDialog', { enabled: true })

  await b.evalJS(`document.querySelector('.picbox.main').scrollIntoView({ block: 'center' }); 'scrolled'`)
  await sleep(500)
  const box = await b.evalJS(`(() => { const r = document.querySelector('.picbox.main').getBoundingClientRect(); const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2); return { x: r.x + r.width / 2, y: r.y + r.height / 2, rect: [r.x, r.y, r.width, r.height], hit: hit && (hit.className || hit.tagName) } })()`)
  R('click box', box)
  await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await sleep(1200)
  R('fileChooserOpened (trusted)', chooser && { mode: chooser.mode, backendNodeId: chooser.backendNodeId })

  if (chooser && chooser.backendNodeId) {
    await b.send('DOM.setFileInputFiles', {
      files: [process.cwd().replace(/\\/g, '/') + '/_work/probe-cover.png'],
      backendNodeId: chooser.backendNodeId,
    })
    await sleep(1500)
  }
  R('preview imgs', await b.evalJS(`document.querySelectorAll('.pics img.picbox-img').length`))
  R('main img src head', await b.evalJS(`((document.querySelector('.picbox.main img') || {}).src || '').slice(0, 48)`))
  R('file name', await b.evalJS(`(document.querySelector('input[type=file]') || {}).value`))
} finally {
  await b.close()
}
