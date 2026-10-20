// final-sweep-search.mjs —— 搜索页营销标签与「秒杀 / 国补 / 品牌」关键词（2026-10-19）
// 前提：前端 dist 已由 8080 服务、后端 dev profile 在 8088。
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const BASE = 'http://localhost:8080'
const b = await open({ w: 1440, h: 1000 })
try {
  for (const kw of ['手环', '秒杀', '国补', '品牌']) {
    await b.goto(BASE + '/#/customer/search?kw=' + encodeURIComponent(kw))
    await sleep(3200)
    const cards = await b.evalJS(`document.querySelectorAll('.pcard').length`)
    const names = await b.evalJS(`JSON.stringify([...document.querySelectorAll('.pcard-name')].map(x => x.innerText))`)
    const kinds = await b.evalJS(`JSON.stringify([...document.querySelectorAll('.pcard-kind:not(.ghost)')].map(x => x.innerText))`)
    console.log(kw.padEnd(4) + ' cards=' + cards + ' names=' + names + ' kinds=' + kinds)
  }
} finally {
  await b.close()
}
