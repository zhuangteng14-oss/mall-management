// probe-b7c11-heatmap-locks.mjs —— 块 7 · 3a.4 收尾〈锁热力图自适应〉的**视觉实测**。
//
// 查：演示源下多把锁同时活动时，热力图是否仍只占固定 190px、
//     y 轴锁名是否完整显示中文短名（不再 order-a...）。
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7c11-heatmap-locks.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7c11-'
const out = (l, v) => console.log(l, JSON.stringify(v))
const b = await open({ w: 1440, h: 900, headed: false })
try {
  await b.goto(`${BASE}#/login`, 2500)
  await b.evalJS(`(() => {
    const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('#acc'),'admin'); set(document.querySelector('#pwd'),'123@admin')
    const btn=[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x=>x.textContent.trim()==='登录'); btn.click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }
  await b.goto(`${BASE}#/admin/observe/metrics`, 8000)
  await sleep(2000)
  await b.evalJS(`(() => { const x=[...document.querySelectorAll('.a-dm .a-seg-b')].find(e=>/模拟日常/.test(e.textContent)); if(x) x.click() })()`)
  await sleep(9000)
  // 左栏滚到最底（热力图）
  await b.evalJS(`(() => { const s=document.querySelector('.mp-stack'); s.scrollTop=s.scrollHeight })()`)
  await sleep(800)
  const info = await b.evalJS(`(() => {
    const s=document.querySelector('.mp-stack')
    const heat=[...document.querySelectorAll('.mp-chart')].find(e=>e.textContent.includes('锁竞争热力图'))
    const hx=heat.getBoundingClientRect()
    return { stackTop:s.scrollTop, stackSh:s.scrollHeight, heat:{t:Math.round(hx.top),b:Math.round(hx.bottom),h:Math.round(hx.height)},
      legend:[...document.querySelectorAll('.mp-legend')].map(e=>e.innerText.replace(/\\n/g,'|')),
      sbw:s.offsetWidth-s.clientWidth, docH:document.scrollingElement.scrollHeight, vh:innerHeight }
  })()`)
  out('HEAT_VIEW', info)
  await b.shot(`${OUT}heatmap-demo.png`)
} finally { await b.close() }
