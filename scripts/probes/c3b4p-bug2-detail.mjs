// c3b4p-bug2-detail.mjs —— 复现「物流轨迹弹窗卡在『加载中…』」。
//
// 口径：**真后端**（localhost:8088，用户正跑的那个）+ **真实 a3 库**（VM）。
//   不 mock —— 要看的就是真链路上到底发生了什么。只驱动 UI + 采样 DOM 文案 + 记录 alert。
//
// 用法：node scripts/probes/c3b4p-bug2-detail.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const CUSTOMER = 'pIzTWafaGp'          // 实测：VM 库里有 DeliveryIf=1 的单（VGJB50421020 / NHOB029438564024 …）
const ORDER_HINT = 'VGJB50421020'

const b = await open({ w: 1440, h: 900 })
try {
  await b.goto('http://localhost:8080/#/login', 1200)
  await b.evalJS(`sessionStorage.setItem('loginUsername','probeC');sessionStorage.setItem('role','1');sessionStorage.setItem('customerid','${CUSTOMER}');1`)
  // ⚠️ alert 会**阻塞**渲染进程 ⇒ 换成记录器，否则「一直转」永远看不到（alert 弹着，UI 冻住）。
  await b.evalJS(`window.__alerts=[];window.alert=(m)=>{window.__alerts.push(String(m))};1`)
  await b.goto(`http://localhost:8080/#/customer/cart?tab=orders`, 3000)

  const list = await b.evalJS(`JSON.stringify({
    行数: document.querySelectorAll('.trow').length,
    物流按钮: [...document.querySelectorAll('.trow .act')].filter(x=>x.textContent.trim()==='物流').length,
    首行单号: (document.querySelector('.trow .oid')||{}).textContent
  })`)
  console.log('列表态：', list)

  const clicked = await b.evalJS(`(() => {
    const rows=[...document.querySelectorAll('.trow')]
    const r=rows.find(x=>((x.querySelector('.oid')||{}).textContent||'').includes('${ORDER_HINT}'))||rows[0]
    if(!r) return 'NO_ROW'
    const btn=[...r.querySelectorAll('.act')].find(x=>x.textContent.trim()==='物流')
    if(!btn) return 'NO_BTN'
    btn.click(); return 'OK'
  })()`)
  console.log('点击物流：', clicked)

  const sample = async (tag) => {
    const s = await b.evalJS(`JSON.stringify({
      t: ${'`'}${tag}${'`'},
      弹窗: !!document.querySelector('.scrim .dlg'),
      正文: (document.querySelector('.scrim .dlg-b')||{}).innerText,
      alerts: window.__alerts
    })`)
    console.log(s)
  }
  await sleep(300); await sample('+0.3s')
  await sleep(1200); await sample('+1.5s')
  await sleep(3000); await sample('+4.5s')
  await sleep(7000); await sample('+11.5s（已过 http.js 的 10s 超时）')

  await b.shot('scripts/probes/out-c3b4p-bug2.png')
} finally {
  await b.close()
}
