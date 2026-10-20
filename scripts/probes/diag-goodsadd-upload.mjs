// diag-goodsadd-upload.mjs —— 上架商品封面图上传诊断（2026-10-19）
// 真后端 8088 + dist 8080；用 CDP 文件选择器拦截走完「点主图 → 选文件 → change → 预览」全链。
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
  R('hash after login', await b.evalJS('location.hash'))
  R('storage', await b.evalJS(`JSON.stringify({ merchantid: sessionStorage.getItem('merchantid'), role: sessionStorage.getItem('role') })`))

  await b.evalJS(`location.hash = '#/merchant/user/add'; 'go'`)
  await sleep(2000)
  R('hash on add', await b.evalJS('location.hash'))
  R('main picbox exists', await b.evalJS(`!!document.querySelector('.picbox.main')`))

  // 拦截文件选择器：点主图后 CDP 会发 Page.fileChooserOpened
  let backendNodeId = null
  b.ws.on('message', (raw) => {
    const m = JSON.parse(raw)
    if (m.method === 'Page.fileChooserOpened') backendNodeId = m.params.backendNodeId
  })
  await b.send('Page.setInterceptFileChooserDialog', { enabled: true })
  await b.evalJS(`document.querySelector('.picbox.main').click(); 'clicked'`)
  await sleep(1200)
  R('fileChooserOpened', backendNodeId)

  if (backendNodeId) {
    await b.send('DOM.setFileInputFiles', { files: [process.cwd() + '/_work/probe-cover.png'], backendNodeId })
    await sleep(1200)
  }
  R('preview imgs', await b.evalJS(`document.querySelectorAll('.pics img.picbox-img').length`))
  R('main text', await b.evalJS(`(document.querySelector('.picbox.main') || {}).innerText`))
  R('main img src head', await b.evalJS(`((document.querySelector('.picbox.main img') || {}).src || '').slice(0, 40)`))
} finally {
  await b.close()
}
