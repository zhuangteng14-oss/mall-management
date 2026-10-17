// probe-b7c11-log-click.mjs —— 块 7 · 3a.4 收尾〈日志页行点不动〉的**交互实测**。
//
// 查：① 可见行全部 is-openable、cursor:pointer、role=button（含无 traceId 的行）
//     ② 无 traceId 的行点开后走「事件详情」单条抽屉，而不是没反应
//     ③ 无重复 key / Vue key 警告
//
// 跑法：① `npm run serve`（8080）② `node scripts/probes/probe-b7c11-log-click.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7c11-'
const out = (l, v) => console.log(l, typeof v === 'string' ? v : JSON.stringify(v))
const b = await open({ w: 1440, h: 900, headed: false })
try {
  await b.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `window.__errs=[];(function(){const e=console.error,w=console.warn;console.error=function(...a){try{window.__errs.push('ERROR '+a.map(String).join(' '))}catch(x){};return e.apply(console,a)};console.warn=function(...a){try{window.__errs.push('WARN '+a.map(String).join(' '))}catch(x){};return w.apply(console,a)}})();`,
  })
  await b.goto(`${BASE}#/login`, 2500)
  await b.evalJS(`(() => {
    const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('#acc'),'admin'); set(document.querySelector('#pwd'),'123@admin')
    const btn=[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x=>x.textContent.trim()==='登录'); btn.click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }
  await b.goto(`${BASE}#/admin/observe/logs`, 7000)
  await sleep(1000)
  await b.evalJS(`(() => { const x=[...document.querySelectorAll('.a-dm .a-seg-b')].find(e=>/模拟日常/.test(e.textContent)); if(x) x.click() })()`)
  await sleep(7000)

  const scan = await b.evalJS(`(() => {
    const list=document.querySelector('#ev-list'); const lr=list.getBoundingClientRect()
    const rows=[...list.querySelectorAll('.log-line')]
    const vis=rows.filter(r=>{const x=r.getBoundingClientRect();return x.bottom>lr.top+2&&x.top<lr.bottom-2})
    return {
      total:rows.length, visible:vis.length,
      noTraceVisible:vis.filter(r=>!/点开看这一次请求的完整链路/.test(r.getAttribute('title')||'')).length,
      noTraceSamples:vis.filter(r=>!/点开看这一次请求的完整链路/.test(r.getAttribute('title')||'')).slice(0,4).map(r=>r.innerText.slice(0,60)),
      allOpenable:vis.every(r=>r.classList.contains('is-openable')),
      cursors:[...new Set(vis.map(r=>getComputedStyle(r).cursor))],
      roles:[...new Set(vis.map(r=>r.getAttribute('role')))],
      errs:(window.__errs||[]).filter(s=>/Duplicate|key|重复/i.test(s)).slice(0,5),
    }
  })()`)
  out('LOG_SCAN', scan)

  // 优先点一条无 traceId 的行（在列表裁剪区内）
  const target = await b.evalJS(`(() => {
    const list=document.querySelector('#ev-list'); const lr=list.getBoundingClientRect()
    const rows=[...list.querySelectorAll('.log-line')].filter(r=>{
      const x=r.getBoundingClientRect(); return x.top>=lr.top+4&&x.bottom<=lr.bottom-4
    })
    const noTrace=rows.find(r=>!/点开看这一次请求的完整链路/.test(r.getAttribute('title')||''))
    const r=noTrace||rows[0]
    if(!r) return null
    const x=r.getBoundingClientRect()
    return {x:Math.round(x.left+180),y:Math.round(x.top+x.height/2),noTrace:!!noTrace,text:r.innerText.slice(0,70)}
  })()`)
  out('TARGET', target)
  if (target) {
    await b.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: target.x, y: target.y, buttons: 0 })
    await b.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: target.x, y: target.y, button: 'left', clickCount: 1 })
    await b.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: target.x, y: target.y, button: 'left', clickCount: 1 })
    await sleep(500)
    const after = await b.evalJS(`(() => {
      const d=document.querySelector('.ev-drawer')
      return { open:!!(d&&d.classList.contains('is-open')), header:(d?.querySelector('.hd')?.innerText||'').replace(/\\s+/g,' ').slice(0,200),
        chain:[...(d?.querySelectorAll('.chain .chain-i')||[])].map(li=>li.innerText.replace(/\\s+/g,' ').slice(0,120)),
        sel:document.querySelectorAll('.log-line.sel').length }
    })()`)
    out('AFTER_CLICK', after)
    await b.shot(`${OUT}log-single-event.png`)
  }
} finally { await b.close() }
