// probe-b7c11-metrics-layout.mjs —— 块 7 · 3a.4 收尾〈指标页布局返工〉的**渲染实测**。
//
// 查：① 无浏览器全局滚动条（doc.scrollH === clientH）
//     ② 左右两栏各自 overflow-y:auto、底边对齐
//     ③ 滚动条宽度 ≤8px（scrollbar-color 继承会让 ::-webkit-scrollbar 失效）
//     ④ 库存水位列表不再自己滚（只随右栏滚）
//     ⑤ 热力图 y 轴锁名走中文短名、画布高度固定
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7c11-metrics-layout.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7c11-'
const out = (l, v) => console.log(l, typeof v === 'string' ? v : JSON.stringify(v))
const b = await open({ w: 1440, h: 900, headed: false })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `window.__errs=[];(function(){const e=console.error,w=console.warn;console.error=function(...a){try{window.__errs.push('ERROR '+a.map(String).join(' '))}catch(x){};return e.apply(console,a)};console.warn=function(...a){try{window.__errs.push('WARN '+a.map(String).join(' '))}catch(x){};return w.apply(console,a)}})();window.addEventListener('error',e=>{try{window.__errs.push('WIN '+e.message)}catch(x){}});`,
  })
  await b.goto(`${BASE}#/login`, 2500)
  await b.evalJS(`(() => {
    const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('#acc'),'admin'); set(document.querySelector('#pwd'),'123@admin')
    const btn=[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x=>x.textContent.trim()==='登录'); btn.click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }
  await b.goto(`${BASE}#/admin/observe/metrics`, 9000)
  await sleep(4000)
  const r = await b.evalJS(`(() => {
    const q=s=>document.querySelector(s)
    const box=s=>{const e=q(s); if(!e) return null; const x=e.getBoundingClientRect(); const cs=getComputedStyle(e); return {
      t:Math.round(x.top),b:Math.round(x.bottom),h:Math.round(x.height),w:Math.round(x.width),
      ovY:cs.overflowY, sbw:e.offsetWidth-e.clientWidth, sh:e.scrollHeight, ch:e.clientHeight}}
    const se=document.scrollingElement
    const heat=[...document.querySelectorAll('.mp-chart')].find(e=>e.textContent.includes('锁竞争热力图'))
    return {
      vh:innerHeight, doc:{scrollH:se.scrollHeight, clientH:se.clientHeight, scrollW:se.scrollWidth, clientW:se.clientWidth},
      amp:box('.a-mp'), body:box('.mp-body'), grid:box('.mp-grid'), stack:box('.mp-stack'), rail:box('.mp-rail'),
      stockList:box('.mp-stock-list'),
      heat:heat?{h:Math.round(heat.getBoundingClientRect().height),canvasH:(()=>{const c=heat.querySelector('canvas');return c?Math.round(c.getBoundingClientRect().height):null})()}:null,
      legend:[...document.querySelectorAll('.mp-chart')].find(e=>e.textContent.includes('锁等待'))?.querySelector('.mp-legend')?.innerText||'',
      errs:(window.__errs||[]).slice(0,10),
    }
  })()`)
  out('METRICS', r)
  await b.shot(`${OUT}metrics-layout.png`)

  // 切到演示源，等几秒：热力图应有的锁名全部映射成中文短名
  const demo = await b.evalJS(`(() => {
    const btns=[...document.querySelectorAll('.a-dm .a-seg-b')]
    const b2=btns.find(e=>/模拟日常/.test(e.textContent))
    if (!b2) return {clicked:false,n:btns.length}
    b2.click(); return {clicked:true,txt:b2.textContent.trim()}
  })()`)
  out('DEMO_CLICK', demo)
  await sleep(7000)
  const r2 = await b.evalJS(`(() => {
    const heat=[...document.querySelectorAll('.mp-chart')].find(e=>e.textContent.includes('锁竞争热力图'))
    const wait=[...document.querySelectorAll('.mp-chart')].find(e=>e.textContent.includes('锁等待'))
    return {
      legend: wait?.querySelector('.mp-legend')?.innerText||'',
      legendTitles: [...(wait?.querySelectorAll('.mp-legend span')||[])].map(s=>s.getAttribute('title')),
      heatRows: heat?.querySelector('canvas') ? true : false,
      errs:(window.__errs||[]).slice(0,10),
    }
  })()`)
  out('METRICS_DEMO', r2)
  await b.shot(`${OUT}metrics-demo.png`)
} finally { await b.close() }
