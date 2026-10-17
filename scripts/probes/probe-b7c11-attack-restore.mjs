// probe-b7c11-attack-restore.mjs —— 块 7 · 3a.4 收尾〈攻击页切页重置〉的**交互实测**。
//
// 查：① 表单（并发 / 时长 / 档位 / 权重）跨路由保持
//     ② 运行中切走再切回：恢复进度条 / 已发条数 / QPS / 停止按钮 / 轮询
//     ③ 点「停止」后收口，回到开始态并清掉 RUN_KEY
//
// 跑法：① `npm run serve`（8080）+ 后端 8088 ② `node scripts/probes/probe-b7c11-attack-restore.mjs`
// 依赖：`docs/美化/_cdp.mjs`（headless Edge + CDP）。
import { open, sleep } from '../../docs/美化/_cdp.mjs'
const BASE = 'http://localhost:8080/admin.html'
const OUT = 'C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7c11-'
const out = (l, v) => console.log(l, typeof v === 'string' ? v : JSON.stringify(v))
const b = await open({ w: 1440, h: 900, headed: false })
const read = () => b.evalJS(`(() => ({
  conc: [...document.querySelectorAll('.a-run input[type=number]')].map(i=>i.value),
  tier: [...document.querySelectorAll('.a-tier')].map(t=>({txt:t.textContent.replace(/\\s+/g,' ').trim().slice(0,20), on:t.classList.contains('is-on')})),
  ranges: [...document.querySelectorAll('input[type=range]')].map(i=>i.value),
  runBtn: [...document.querySelectorAll('.a-run .a-btn')].map(x=>({t:x.textContent.trim(), disabled:x.disabled, show:!!x.offsetParent})),
  prog: (()=>{const p=document.querySelector('.a-prog'); if(!p) return null; return {text:p.innerText.replace(/\\s+/g,' ').slice(0,180), w:p.querySelector('.a-prog-bar i')?.style.width}})(),
  flash: (document.querySelector('.a-flash')||{}).innerText||'',
  runMeta: (()=>{try{return sessionStorage.getItem('mall.observe.attack.run.v1')}catch(e){return null}})(),
  formMeta: (()=>{try{return sessionStorage.getItem('mall.observe.attack.form.v1')}catch(e){return null}})(),
}))()`)
try {
  await b.goto(`${BASE}#/login`, 2500)
  await b.evalJS(`(() => {
    const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    set(document.querySelector('#acc'),'admin'); set(document.querySelector('#pwd'),'123@admin')
    const btn=[...document.querySelectorAll('button.btn.btn-primary.btn-block')].find(x=>x.textContent.trim()==='登录'); btn.click()
  })()`)
  for (let i = 0; i < 40; i++) { await sleep(500); if (/^#\/admin/.test(await b.evalJS('location.hash'))) break }
  await b.goto(`${BASE}#/admin/observe/attack-sim`, 4500)
  await sleep(500)

  // ① 表单跨路由
  const set = await b.evalJS(`(() => {
    const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    const ins=[...document.querySelectorAll('.a-run input[type=number]')]
    if(ins[0]) set(ins[0],333); if(ins[1]) set(ins[1],2)
    const t=[...document.querySelectorAll('.a-tier')].find(x=>/400/.test(x.textContent)); if(t) t.click()
    const sl=document.querySelector('input[type=range]'); if(sl) set(sl,42)
    return {ins:ins.length, tier:!!t, sl:!!sl}
  })()`)
  out('SET', set)
  await sleep(400)
  out('BEFORE_NAV', await read())
  await b.goto(`${BASE}#/admin/observe/metrics`, 1800)
  await b.goto(`${BASE}#/admin/observe/attack-sim`, 3000)
  await sleep(500)
  out('AFTER_NAV', await read())

  // ② 起一轮（轻量参数：并发 8 / 10 秒 / 120 档），中途切走再切回
  await b.evalJS(`(() => {
    const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}))}
    const ins=[...document.querySelectorAll('.a-run input[type=number]')]
    if(ins[0]) set(ins[0],8); if(ins[1]) set(ins[1],10)
    const t=[...document.querySelectorAll('.a-tier')].find(x=>/120/.test(x.textContent)); if(t) t.click()
  })()`)
  await sleep(300)
  const started = await b.evalJS(`(() => {
    const p=[...document.querySelectorAll('.a-preset')].find(x=>!x.disabled)
    if(!p) return {ok:false,n:document.querySelectorAll('.a-preset').length}
    p.click(); return {ok:true,name:p.innerText.replace(/\\s+/g,' ').slice(0,60)}
  })()`)
  out('START', started)
  await sleep(500)
  out('RUNNING', await read())

  await b.goto(`${BASE}#/admin/observe/metrics`, 600)
  await sleep(300)
  await b.goto(`${BASE}#/admin/observe/attack-sim`, 1500)
  await sleep(600)
  out('RESTORED', await read())
  await b.evalJS(`(() => { const p=document.querySelector('.a-prog'); if(p) p.scrollIntoView({block:'center'}) })()`)
  await sleep(400)
  await b.shot(`${OUT}attack-restored.png`)

  // ③ 点「停止」把这一轮收掉（恢复态下按钮走同一个 /attack/stop）
  const stopped = await b.evalJS(`(() => { const x=[...document.querySelectorAll('.a-run .a-btn')].find(e=>/停止/.test(e.textContent)); if(!x) return false; x.click(); return true })()`)
  out('CLICK_STOP', stopped)

  // ④ 等它收口
  let done = null
  for (let i = 0; i < 25; i++) {
    await sleep(1000)
    done = await read()
    if (!done.prog) break
  }
  out('FINISHED', done)
  await b.shot(`${OUT}attack-finished.png`)
} finally { await b.close() }
