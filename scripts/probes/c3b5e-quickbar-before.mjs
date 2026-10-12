// c3b5e-quickbar-before.mjs —— 「主页三格服务条」删除**改前**实况（2026-10-12）。
// 目的：先查后改 —— 看它在真页面上**到底长什么样、占多宽**，再动手。
// 口径：真后端 8088 + 真库，**纯读**。
//
// ⚠️ **本探针是历史件，改后再跑必然全红**（那一块已经删了）—— 它记录的是删前那一刻的实况，
//    不是一条可复跑的回归判据。配套的「改后」判据在 `c3b5e-quickbar-after.mjs`。
// 用法（仅改前那一刻有效）：node scripts/probes/c3b5e-quickbar-before.mjs
import { open, sleep } from '../../docs/美化/_cdp.mjs'

const EV = 'C:/Users/86159/Desktop/软件工程/期末项目/docs/agent/evidence/c3-block5/'

// ⚠️ 交给 evalJS：不能有反引号 / ${。
const MEASURE = `(function(){
  var s = document.querySelector('section.quick');
  var out = { found: !!s };
  if (!s) return JSON.stringify(out);
  var cs = getComputedStyle(s);
  var r = s.getBoundingClientRect();
  out.cols = cs.gridTemplateColumns;
  out.bg = cs.backgroundColor;
  var items = Array.prototype.map.call(s.querySelectorAll('.qi'), function(b){
    var br = b.getBoundingClientRect();
    return { t: b.innerText.replace(/\\n+/g, ' / '), disabled: b.disabled, w: Math.round(br.width) };
  });
  out.items = items;
  out.box = { w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top + window.scrollY) };
  // 第 3 格右边到区块右边还有多少空白（4 列栅格只剩 3 格时会留一格）
  var last = s.querySelector('.qi:last-child');
  if (last) out.tailGap = Math.round(r.right - last.getBoundingClientRect().right);
  out.html = s.outerHTML.length;
  return JSON.stringify(out);
})()`

const b = await open({ w: 1440, h: 1000 })
const R = (await import('../../docs/美化/_cdp.mjs')).report('c3b5e-quickbar-before')
try {
  await b.goto('http://localhost:8080/#/', 3500)
  const m = await b.evalJS(MEASURE)
  const d = m ? JSON.parse(m) : null
  console.log('--- 主页 section.quick（改前）---')
  console.log(JSON.stringify(d, null, 2))
  R.ok('① 页面上确实有 `section.quick`', !!d && d.found === true, d && d.found)
  R.ok('② 只剩 3 格（第 4 格「优惠券」2026-10-11 已删）', !!d && d.items && d.items.length === 3, d && d.items && d.items.length)
  // ③ 的判据是**「右边真的空出一格」**，不是「样式表里写着 repeat(4,1fr)」——
  //    `getComputedStyle` 把栅格解析成像素串（实测 "322px 322px 322px 322px"），
  //    拿 `repeat` 去匹配必然落空。真正要看的是 `tailGap`（末格右边 → 区块右边）。
  R.ok('③ 4 列栅格只剩 3 格 ⇒ 右边空出一整格（tailGap ≈ 1 格宽）',
    !!d && d.tailGap > 250, d && { cols: d.cols, tailGap: d.tailGap, boxW: d.box && d.box.w })
  R.ok('④ 三格全是 `disabled` 死控件', !!d && (d.items || []).every(function (i) { return i.disabled }), d && d.items && d.items.map(function (i) { return i.disabled }))
  // 单独给这一条拍图（页面顶部已含它）
  await sleep(400)
  await b.shot(EV + 'before-home-quickbar.png')
} finally {
  R.dump()
  await b.close()
}
