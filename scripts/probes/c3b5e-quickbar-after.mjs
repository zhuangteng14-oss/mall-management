// c3b5e-quickbar-after.mjs —— 「主页三格服务条」删除**改后**自检（2026-10-12）。
// 与 `c3b5e-quickbar-before.mjs` **同一个页面、同一个宽度、同一种量法**，判据反过来。
// 口径：真后端 8088 + 真库，**纯读**。
// 用法：node scripts/probes/c3b5e-quickbar-after.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'

// ⚠️ 交给 evalJS：不能有反引号 / ${。
const MEASURE = `(function(){
  var out = {};
  out.quickLeft = document.querySelectorAll('section.quick, .quick, button.qi').length;
  out.txMatches = (document.body.innerText.match(/我的订单|浏览记录|在线客服/g) || []).length;
  // 别的区块必须还在（删一块不能连坐）
  out.kept = {
    hero: !!document.querySelector('section.hero'),
    flash: !!document.querySelector('section.flash'),
    rec: !!document.querySelector('section.rec'),
    trust: !!document.querySelector('section.trust'),
    cards: document.querySelectorAll('a.pcard').length
  };
  // 删掉那一块之后，hero 与下一个区块之间的间距还剩多少（不能塌成 0）
  var hero = document.querySelector('section.hero');
  var nxt = hero && hero.nextElementSibling;
  out.nextSection = nxt ? (nxt.className || nxt.tagName) : null;
  out.gap = nxt ? Math.round(nxt.getBoundingClientRect().top - hero.getBoundingClientRect().bottom) : null;
  out.sections = Array.prototype.map.call(document.querySelectorAll('.wrap > section, section'), function(s){ return s.className }).slice(0, 8);
  return JSON.stringify(out);
})()`

const b = await open({ w: 1440, h: 1000 })
const R = (await import('../../docs/美化/_cdp.mjs')).report('c3b5e-quickbar-after')
try {
  await b.goto('http://localhost:8080/#/', 3500)
  const m = await b.evalJS(MEASURE)
  const d = m ? JSON.parse(m) : null
  console.log('--- 主页（改后）---')
  console.log(JSON.stringify(d, null, 2))
  R.ok('① `section.quick` / `.quick` / `button.qi` 一个不剩', !!d && d.quickLeft === 0, d && d.quickLeft)
  R.ok('② 页面上不再出现那三格文字', !!d && d.txMatches === 0, d && d.txMatches)
  R.ok('③ 别的区块一个没连坐（hero / 闪购 / 推荐 / 信任条）', !!d && d.kept.hero && d.kept.flash && d.kept.rec && d.kept.trust, d && d.kept)
  R.ok('④ 商品卡照旧在（43 张）', !!d && d.kept.cards === 43, d && d.kept.cards)
  R.ok('⑤ 删块之后区块间距没塌（> 0）', !!d && d.gap !== null && d.gap > 0, d && { next: d.nextSection, gap: d.gap })
  await sleep(400)
  await b.shot(EV + 'after-home-quickbar.png')
} finally {
  R.dump()
  await b.close()
}
