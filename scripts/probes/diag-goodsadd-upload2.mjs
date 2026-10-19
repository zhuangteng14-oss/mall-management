// diag-goodsadd-upload2.mjs —— 定位「点主图无反应」：input 是否存在 / click() 是否被调用 / 有无异常
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
  await b.goto(BASE + '/#/login'); await sleep(700)
  await b.evalJS(`
    const set = (sel, v) => { const el = document.querySelector(sel); const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })) };
    set('#acc', ${JSON.stringify(ACC)}); set('#pwd', ${JSON.stringify(PWD)});
    [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '登录' && x.className.includes('btn-block')).click(); 'ok'`)
  await sleep(2500)
  await b.evalJS(`location.hash = '#/merchant/user/add'; 'ok'`); await sleep(1800)
  R('input[type=file] count', await b.evalJS(`document.querySelectorAll('input[type=file]').length`))
  R('input outerHTML', await b.evalJS(`(document.querySelector('input[type=file]') || {}).outerHTML`))
  R('input display', await b.evalJS(`getComputedStyle(document.querySelector('input[type=file]')).display`))
  await b.evalJS(`
    window.__clicks = [];
    const proto = HTMLInputElement.prototype;
    const orig = proto.click;
    proto.click = function () { window.__clicks.push(this.type + ':' + this.className); try { return orig.apply(this, arguments) } catch (e) { window.__err = String(e); } };
    window.__vueErr = null;
    window.addEventListener('error', (e) => { window.__vueErr = String(e.message) });
    'patched'
  `)
  await b.evalJS(`document.querySelector('.picbox.main').click(); 'clicked'`)
  await sleep(800)
  R('input.click calls', await b.evalJS(`JSON.stringify(window.__clicks)`))
  R('page error', await b.evalJS(`JSON.stringify(window.__vueErr)`))
  R('button handlers?', await b.evalJS(`document.querySelector('.picbox.main').outerHTML.slice(0,120)`))
  await b.send('Page.setInterceptFileChooserDialog', { enabled: true })
  await b.evalJS(`document.querySelector('input[type=file]').click(); 'direct'`)
  await sleep(1000)
} finally { await b.close() }
