// probe-login-shot.mjs —— ① 的目视证据：1280×720 两态各一张（含 760 / 900 对照）
import { open, sleep } from './_cdp.mjs'
const b = await open({ w: 1280, h: 720 })
try {
  for (const [w, h] of [[1280, 720], [1280, 760], [1280, 900]]) {
    await b.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false })
    await b.goto('http://localhost:8080/#/login', 800)
    await b.send('Page.reload', { ignoreCache: true }); await sleep(1600)
    await b.shot(`docs/美化/dlogin/login-${w}x${h}.png`)
    await b.evalJS(`(() => { const t = document.querySelector('section[data-page="login"] .swap button'); if (t) t.click(); return 1 })()`)
    await sleep(800)
    await b.shot(`docs/美化/dlogin/register-${w}x${h}.png`)
    console.log('✓', w + 'x' + h, ' docH =', await b.evalJS('document.documentElement.scrollHeight'))
  }
} finally { await b.close() }
