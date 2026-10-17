// probe-b7c11-replay-pacing.mjs —— 块 7 · 3a.4 收尾〈回放一键全显〉的**交互实测**。
//
// 查：① 点「回放」后列表按时间推进逐批增长（不是一次全出现）
//     ② 顶部「正在回放（进度 X / Y）」状态条 + 进度百分比 + 回放时钟
//     ③ 「退出回放」能收掉面板
//
// 跑法：① `npm run serve`（8080）+ 后端 8088（要有演练记录）② `node scripts/probes/probe-b7c11-replay-pacing.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7c11-'
const out = (l, v) => console.log(l, typeof v === 'string' ? v : JSON.stringify(v))
const b = await open({ w: 1440, h: 900, headed: false })
const sample = () => b.evalJS(`(() => {
  const live=document.querySelector('.a-rp-live')
  const lis=document.querySelectorAll('.a-rp-list li')
  const btn=[...document.querySelectorAll('.a-rp-live button')].map(x=>x.textContent.trim())
  return {
    live: live?live.innerText.replace(/\\s+/g,' ').trim():null,
    pct: live?live.querySelector('.a-rp-live-bar i')?.style.width:null,
    n: lis.length,
    first: lis[0]?lis[0].innerText.replace(/\\s+/g,' ').slice(0,60):null,
    last: lis.length?lis[lis.length-1].innerText.replace(/\\s+/g,' ').slice(0,60):null,
    buttons: btn,
    stat: (document.querySelector('.a-rp-stat')||{}).innerText?.replace(/\\s+/g,' ').slice(0,140)||'',
  }
})()`)
try {
  await b.goto(`${BASE}#/login`, 2500)
  await b.evalJS(`(() => {
    const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('#acc'),'admin'); set(document.querySelector('#pwd'),'123@admin')
    const btn=[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x=>x.textContent.trim()==='登录'); btn.click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }
  await b.goto(`${BASE}#/admin/observe/attack-sim`, 5000)
  await sleep(500)

  const opened = await b.evalJS(`(async () => {
    const heads=[...document.querySelectorAll('.a-ev-head')]
    if (heads.length<3) return {ok:false,heads:heads.length}
    heads[2].click()
    await new Promise(r=>setTimeout(r,500))
    const txt=heads[2].innerText.replace(/\\s+/g,' ').slice(0,120)
    const btn=[...document.querySelectorAll('.a-rp-in button')].find(x=>x.textContent.includes('回放')&&!x.disabled)
    if(!btn) return {ok:false,txt,btns:[...document.querySelectorAll('.a-ev-body button')].map(x=>x.textContent.trim()).slice(0,10)}
    btn.click()
    return {ok:true,txt,batch:(document.querySelector('.a-rp-in .a-tag')||{}).textContent||''}
  })()`)
  out('OPEN', opened)
  await sleep(300)
  out('T0.3', await sample())
  await sleep(700)
  out('T1.0', await sample())
  await sleep(1000)
  out('T2.0', await sample())
  await sleep(1200)
  out('T3.2', await sample())
  await b.evalJS(`(() => { const x=document.querySelector('.a-rp-live'); if(x) x.scrollIntoView({block:'start'}) })()`)
  await sleep(400)
  await b.shot(`${OUT}replay-pacing.png`)

  const exited = await b.evalJS(`(() => {
    const x=[...document.querySelectorAll('.a-rp-live button')].find(b=>/退出回放/.test(b.textContent))
    if(!x) return {ok:false}
    x.click(); return {ok:true}
  })()`)
  await sleep(400)
  const after = await b.evalJS(`(() => ({panel:!!document.querySelector('.a-rp'),live:!!document.querySelector('.a-rp-live')}))()`)
  out('EXIT', { ...exited, after })
} finally { await b.close() }
