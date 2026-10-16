import { open } from '../../docs/美化/_cdp.mjs'
const b = await open({ w: 1000, h: 270 })
try {
  await b.goto('file:///C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/b7a2b-zoom.html', 1500)
  await b.shot('C:/Users/86159/Desktop/软件工程/期末项目/scripts/probes/out-b7a2b-zoom-kpi-top.png')
} finally { await b.close() }
