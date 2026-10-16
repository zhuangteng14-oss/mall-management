/* ══════════════════════════════════════════════════════════════════════════════
   _struct-wire-ev.js —— 3a.3 日志页结构草图（甲 / 丙）的**渲染层**

   一、它是什么
     把真抓的一窗事件帧（60 拍）画成两版**纯结构**草图。整篇只有灰阶、**不动效**
     （`_struct-wire-ev.css` 里没有一条 transition / animation）。

   二、数据从哪来
     `DATA` 块由 `scripts/probes/probe-b7a3-wire-data.mjs` 从
     `_work/3a3/frames-events.ndjson` 生成 —— 那是 2026-10-16 对 dev 后端真跑一场负载、
     从 `POST /admin/observe/events/stream` 上逐字抓的 **60 帧**。
     ⚠️ 本块是**产物**：注释里不写「文件:行号」（产物写行号 = 埋一批永不更新的引用），
        要定位请用**符号名**。

   三、`render(variant)` 只做一件事：按版式拼 DOM
     'jia'  甲 · 三栏驾驶舱 —— 左「选」②、中「看」③、右「量」④，⑤⑥ 全宽压在底部
     'bing' 丙 · 上下分区 —— 左栏贯穿，右上「仪表带」（折线 / 9 类计数 / 严重度），右下 ③ 占满

   四、⚠️ 两处**刻意**的结构差异（不是随手画的）
     ① 9 类计数：**甲**放在 ② 过滤栏的行尾；**丙**放在 ④ 仪表带里、② 只留类名与开关状态
        —— 丙的这条改法是为了避开「同一份数字在屏幕上出现两次」。
     ② 卡片列数：**甲**的中区被左右两栏夹到 ≈590px ⇒ 用 `.narrow`（少两列）；
        **丙**的中区最宽 ⇒ 用满列。
   ══════════════════════════════════════════════════════════════════════════════ */

/* @@EV-DATA:BEGIN —— 由 scripts/probes/probe-b7a3-wire-data.mjs 从真帧生成，勿手改 @@ */
var DATA = {
  /* 来源："60 拍事件帧 · 17:52:33–17:53:34" */
  meta: { ticks: 60, winEvents: 3152, capturedOn: "2026-10-16",
    note: "窗口内累计按页面口径（absorbEvents/absorbTraces 重放），不是帧里本拍的 counts" },
  clock: {"seq":59,"tick":"17:53:34","phase":"已连接","mq":true,"rate":4.938,"fresh":5,"freshTrace":8},
  stats: {"events":1000,"traces":300,"cap":1000,"traceCap":300,"droppedTotal":0,"demoFed":0},
  rate: [0,34.98,35.033,41.2,56.467,42.854,38.831,22.312,20.721,42.373,45.935,84.326,21.968,34.519,28.237,18.208,20.698,74.058,60.693,19.541,24.679,55.062,28.638,19.393,60.367,70.855,21.724,43.501,35.945,27.752,38.802,69.134,76.062,20.428,51.317,21.096,20.812,26.564,106.117,64.036,34.864,34.63,19.766,52.32,31.073,94.741,83.777,20.067,46.432,36.822,18.854,20.753,101.511,73.463,21.381,18.699,48.436,30.152,22.016,4.938],
  kinds: [{"k":"cache","label":"缓存","n":655},{"k":"lock","label":"锁","n":143},{"k":"order","label":"订单","n":72},{"k":"stock","label":"库存","n":0},{"k":"audit","label":"审计","n":0},{"k":"notify","label":"通知","n":0},{"k":"sensitive","label":"敏感词","n":0},{"k":"identity","label":"身份","n":130},{"k":"protection","label":"防护","n":0}],
  sevs: [{"s":"info","label":"信息","n":1000},{"s":"warn","label":"警告","n":0},{"s":"error","label":"错误","n":0}],
  rail: { allOn: true, total: 1000 },
  cards: [
    {"seq":3151,"ts":"17:53:33.641","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2137","obj":"键 all-v3","trace":"6e9dc8e4-1b95-43d9-bf8f-0810af01f288","anim":0},
    {"seq":3150,"ts":"17:53:33.625","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2136","obj":"键 all-v3","trace":"dc308e43-eb42-4803-bfef-d1e5d15e0624","anim":0},
    {"seq":3149,"ts":"17:53:33.579","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2135","obj":"键 all-v3","trace":"1a313c5c-be76-4a41-b69f-74b8dd2cee30","anim":0},
    {"seq":3148,"ts":"17:53:33.530","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2134","obj":"键 all","trace":"76a3d1f0-ad9a-48a8-9f2b-b049d99799ca","anim":0},
    {"seq":3147,"ts":"17:53:33.410","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2133","obj":"键 all-v3","trace":"4c992bad-d526-4b53-bf7f-a2196c1192f6","anim":0},
    {"seq":3146,"ts":"17:53:33.376","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2132","obj":"键 all","trace":"12581219-8a7c-42c9-bd8b-4efc861802bd","anim":0},
    {"seq":3145,"ts":"17:53:33.363","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2131","obj":"键 all","trace":"c355a9e4-cfbb-4c60-8773-781885ae5b4c","anim":0},
    {"seq":3144,"ts":"17:53:33.250","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2130","obj":"键 all","trace":"b6e06f60-f9fc-4042-85d4-0a67d17f7db2","anim":0},
    {"seq":3143,"ts":"17:53:33.224","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2129","obj":"键 all-v3","trace":"ecec37b2-a4ef-49c8-a4d2-78e217f8cadd","anim":0},
    {"seq":3142,"ts":"17:53:33.222","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2128","obj":"键 all-v3","trace":"3d6ebb96-4f5e-4143-88c0-06568dcb4f1b","anim":0},
    {"seq":3141,"ts":"17:53:33.126","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2127","obj":"键 all","trace":"0411f19d-5593-49ba-a1cf-9190f9471864","anim":0},
    {"seq":3140,"ts":"17:53:33.099","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2126","obj":"键 all-v3","trace":"199d7dbe-1a6c-4f20-97c4-e71be207faa4","anim":0},
    {"seq":3139,"ts":"17:53:33.019","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2125","obj":"键 all-v3","trace":"03befbda-5e36-4815-ac29-184458fd9609","anim":0},
    {"seq":3138,"ts":"17:53:32.938","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2124","obj":"键 all","trace":"9ce07857-b35d-4d28-8969-0eb761ae1f68","anim":0},
    {"seq":3137,"ts":"17:53:32.908","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2123","obj":"键 all","trace":"5aa781ff-9049-40d7-a1e6-1cc22d4e0eb0","anim":0},
    {"seq":3136,"ts":"17:53:32.876","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2122","obj":"键 all","trace":"b2ab9452-2392-45af-a3a8-b69ab58d91f1","anim":0},
    {"seq":3135,"ts":"17:53:32.783","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2121","obj":"键 all-v3","trace":"d382c346-a485-4a48-b12e-0c8b461b73b0","anim":0},
    {"seq":3134,"ts":"17:53:32.772","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2120","obj":"键 all-v3","trace":"74246499-caf6-4ae6-a9c1-f4f1a96dc08e","anim":0},
    {"seq":3133,"ts":"17:53:32.769","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2119","obj":"键 all-v3","trace":"d0923f94-517d-4b48-b9f7-022288cbe43b","anim":0},
    {"seq":3132,"ts":"17:53:32.769","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2118","obj":"键 all","trace":"81a1db36-503b-465e-b4d8-6908a52333fe","anim":0},
    {"seq":3131,"ts":"17:53:32.649","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2117","obj":"键 all-v3","trace":"60a7b987-d062-465b-abf5-df059a39a922","anim":0},
    {"seq":3130,"ts":"17:53:32.537","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2116","obj":"键 all","trace":"d20a472e-e35a-4a22-86ec-6b2c81398fca","anim":0},
    {"seq":3129,"ts":"17:53:32.536","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2115","obj":"键 all","trace":"f85481ab-c869-4ae4-9b07-5321cfaa9b63","anim":0},
    {"seq":3128,"ts":"17:53:32.536","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2114","obj":"键 all","trace":"b3aa6aa0-8de9-4889-ae9c-1f3d07daf1b8","anim":0},
    {"seq":3127,"ts":"17:53:32.424","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2113","obj":"键 all-v3","trace":"1d71ea00-babd-4d93-81b3-e1cde4e1fc40","anim":0},
    {"seq":3126,"ts":"17:53:32.410","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2112","obj":"键 all-v3","trace":"e53ef1ae-3ce0-4dd6-88e3-adc2a25d5a99","anim":0},
    {"seq":3125,"ts":"17:53:32.380","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2111","obj":"键 all","trace":"313f725a-ad80-4d28-83c0-ed65c590b93d","anim":0},
    {"seq":3124,"ts":"17:53:32.379","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2110","obj":"键 all-v3","trace":"a2be83d6-d2be-4e7a-b584-3a812b9ec58e","anim":0},
    {"seq":3120,"ts":"17:53:32.274","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2109","obj":"键 all-v3","trace":"f798d040-1d27-4f58-bbc5-f81abd831d7c","anim":0},
    {"seq":3119,"ts":"17:53:32.147","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2108","obj":"键 all","trace":"fc4500f0-d337-4590-bebd-feb0330f29f0","anim":0},
    {"seq":3118,"ts":"17:53:32.146","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2107","obj":"键 all","trace":"87176e60-697a-4621-84fe-b5ba4a36061b","anim":0},
    {"seq":3117,"ts":"17:53:32.098","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2106","obj":"键 all","trace":"cdb08e65-371d-44e2-8630-570ce6f080a7","anim":0},
    {"seq":3116,"ts":"17:53:32.021","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2105","obj":"键 all-v3","trace":"682401e4-da38-49d3-942d-35194b0156ba","anim":0},
    {"seq":3115,"ts":"17:53:32.008","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2104","obj":"键 all","trace":"16379435-df2a-407b-9e18-8ca047d05d76","anim":0},
    {"seq":3114,"ts":"17:53:32.005","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2103","obj":"键 all-v3","trace":"bf9b4096-7b8d-4e18-871d-847879a51491","anim":0},
    {"seq":3113,"ts":"17:53:31.990","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2102","obj":"键 all-v3","trace":"e94d2569-4370-4c06-bbd6-b316c29a5e09","anim":0},
    {"seq":3112,"ts":"17:53:31.913","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2101","obj":"键 all-v3","trace":"dcefd4c9-88cf-4413-b87c-e5a04830437f","anim":0},
    {"seq":3111,"ts":"17:53:31.774","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2100","obj":"键 all","trace":"3987850a-2a42-4378-b6ab-13af1193f393","anim":0},
    {"seq":3110,"ts":"17:53:31.725","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2099","obj":"键 all","trace":"fde4aaff-d6a8-46f9-bcea-f07a0aca6f2f","anim":0},
    {"seq":3109,"ts":"17:53:31.711","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2098","obj":"键 all","trace":"be71a7e7-4e1c-4734-8ef1-e6d6f4741e52","anim":0},
    {"seq":3108,"ts":"17:53:31.664","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2097","obj":"键 all-v3","trace":"55c924ac-f116-4424-a953-228145cae356","anim":0},
    {"seq":3107,"ts":"17:53:31.626","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2096","obj":"键 all-v3","trace":"b953fd35-5f2c-4237-be6b-535adab0c5dc","anim":0},
    {"seq":3106,"ts":"17:53:31.619","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"回源查库","detail":"cache=goodsByMerchant result=null key=B7CAPS-sgu0g-215 src=cache#2095","obj":"键 B7CAPS-sgu0g-215","trace":"19d78a4e-46d6-4dd1-acd2-efa53c7bcbe7","anim":6},
    {"seq":3105,"ts":"17:53:31.619","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"L2 未命中","detail":"cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-215 src=cache#2094","obj":"键 B7CAPS-sgu0g-215","trace":"19d78a4e-46d6-4dd1-acd2-efa53c7bcbe7","anim":0},
    {"seq":3123,"ts":"17:53:31.616","kind":"identity","kindLabel":"身份","sevLabel":"信息","title":"身份放行","detail":"subject=B7CAPS-sgu0g-215 src=identity#400","obj":"请求路径 /goods/goodsSelect","trace":"19d78a4e-46d6-4dd1-acd2-efa53c7bcbe7","anim":0},
    {"seq":3104,"ts":"17:53:31.616","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodstype result=hit key=all src=cache#2093","obj":"键 all","trace":"ad4f1eed-7b36-4bb7-b67e-30218237d893","anim":0},
    {"seq":3103,"ts":"17:53:31.616","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"L1 未命中","detail":"cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-215 src=cache#2092","obj":"键 B7CAPS-sgu0g-215","trace":"19d78a4e-46d6-4dd1-acd2-efa53c7bcbe7","anim":0},
    {"seq":3102,"ts":"17:53:31.552","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"缓存命中（L1）","detail":"cache=goodsHome result=hit key=all-v3 src=cache#2091","obj":"键 all-v3","trace":"c6b8f029-1bb7-4edf-8cb1-70b921a180a3","anim":0},
    {"seq":3101,"ts":"17:53:31.539","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"回源查库","detail":"cache=goodsByMerchant result=null key=B7CAPS-sgu0g-214 src=cache#2090","obj":"键 B7CAPS-sgu0g-214","trace":"f3cfc836-bd8d-4061-adaa-99fe0e7cfa31","anim":0},
    {"seq":3100,"ts":"17:53:31.539","kind":"cache","kindLabel":"缓存","sevLabel":"信息","title":"L2 未命中","detail":"cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-214 src=cache#2089","obj":"键 B7CAPS-sgu0g-214","trace":"f3cfc836-bd8d-4061-adaa-99fe0e7cfa31","anim":0}
  ],
  drawer: {
    traceId: "19d78a4e-46d6-4dd1-acd2-efa53c7bcbe7", method: "POST", path: "/goods/goodsSelect",
    status: 200, ms: 9,
    start: "17:53:31.616", end: "17:53:31.625",
    chain: [{"ts":"17:53:31.616","kindLabel":"缓存","title":"L1 未命中","obj":"键 B7CAPS-sgu0g-215"},{"ts":"17:53:31.616","kindLabel":"身份","title":"身份放行","obj":"请求路径 /goods/goodsSelect"},{"ts":"17:53:31.619","kindLabel":"缓存","title":"L2 未命中","obj":"键 B7CAPS-sgu0g-215"},{"ts":"17:53:31.619","kindLabel":"缓存","title":"回源查库","obj":"键 B7CAPS-sgu0g-215"}] },
  judge: { jia: "<b>结构判据（本页不是设计稿，是拿来比构图的）</b><ul><li><b>C1 相邻区块不同构</b>：① 一排读数 ／ ② 竖排清单 ／ ③ 滚动卡片 ／ ④ 横条＋读数 ／ ⑤ 有序链 ／ ⑥ 开关排 —— 六块六种形态，没有两块共用布局。</li><li><b>C3 区块间距 &gt; 区块内间距</b>：区块之间 <code>--gap-block</code> = <b>48px</b>（= <code>--s7</code>）， 区块内部 <code>--gap-in</code> = <b>12px</b>（= <code>--s3</code>）。 <b>两轴都要成立</b>（C3 原话不区分横竖）：状态条本笔原样保留 ⇒ 它内部那排读数之间的 <code>--s5</code> 24px 是页面里绕不开的区内间距，取 20 会倒挂、取 24 会持平（3a.2b 指标页就栽在 19 &lt; 26）。 曾取 32px，但探针按「盒间空白 + 两侧内边距」量出来是 <b>40px</b>：状态条最右那块 <code>.tz</code> 带 <code>border-left</code> 与 <code>padding-left:16</code>，与它左边那个读数之间的视觉白带是 24 + 16 = 40。 那不是判据错，是<b>真数</b> ⇒ 不去掉那 16px 内边距来凑，直接把区块间距再抬一档到 48px， 这样<b>两种口径</b>（只算 gap 的 24 / 算白带的 40）下都严格成立。 这是<b>设计值</b>；实数由探针两轴各量一遍，不在文字里下结论。</li><li><b>各区宽度</b>：<b>实数由探针在 1440 视口量</b>（主区可用宽 = 视口 − 壳侧栏 232 − 主区左右各 32 − 滚动条； 滚动条宽实测，不写常数）。</li><li><b>8 处动效落点</b>：页面上带 <code>[data-anim]</code> 角标的元素即为落点，逐个数得出 8 个。</li><li><b>三样易漏项</b>：⑤ 的「起止两行只来自 traces ⇒ 模拟日常下恒空」写在 ⑤ 的注里； ③ 的「只留 200 条、被淘汰的 seq 要从 seenSeq 摘掉」写在底部哨兵那条里； 「图例 9 类、数字是<b>本页窗口内累计</b>不是帧里 counts」写在 ② 的脚注与 ④ 的标题里。</li></ul><b>本窗实测（真帧 60 拍 · 抓帧期间共 3152 条事件 · 0 条被覆盖丢失）</b><ul><li>抓帧期间（整窗）：缓存 2138 · 锁 370 · 订单 185 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 401 · 防护 58</li><li>页面窗口内（最近 1000 条 · <b>图例读的就是这一行</b>）：缓存 655 · 锁 143 · 订单 72 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 130 · 防护 0</li><li><b>两者对不上的地方就是这页的结构性后果</b>：防护整窗 <b>58 条</b>、页面窗口 <b>0 条</b>；警告整窗 <b>112 条</b>、页面窗口 <b>0 条</b> ——缓存一类占了大头，稀有的类会被 <b>1000 条</b>的封顶<b>挤出窗口</b>，页面上从此看不见。（如实显示，<b>不补数</b>；这是本步发现的真问题，处置留给你裁。）</li><li>9 类里页面窗口<b>有量 </b>4 类、<b>0 条 </b>5 类：库存 · 审计 · 通知 · 敏感词 · 防护 —— 是负载没走到那几条路径、还是那几环没接通，本步<b>不下结论</b>。</li><li>严重度（页面窗口）：信息 1000 · 警告 0 · 错误 0 ⇒ <b>error 0 条</b>，「error 卡片闪红」这一处本窗没有可演示的卡片（落点在，见 ③）。</li></ul><b>这一版（甲 · 三栏驾驶舱）的结构主张</b><ul><li>左「选」／中「看」／右「量」，一栏一句话说得清。</li><li>折线是<b>中区顶部的一条横幅</b>（③ 的一部分），不另立一栏。</li><li>9 类计数在 <b>② 过滤栏行尾</b>（图例兼筛选，数字就在开关旁边）。</li><li>代价：中区被左右两栏夹住 ⇒ 卡片用<b>窄列版</b>（隐去严重度列与 traceId 列）。</li></ul>",
    bing: "<b>结构判据（本页不是设计稿，是拿来比构图的）</b><ul><li><b>C1 相邻区块不同构</b>：① 一排读数 ／ ② 竖排清单 ／ ③ 滚动卡片 ／ ④ 横条＋读数 ／ ⑤ 有序链 ／ ⑥ 开关排 —— 六块六种形态，没有两块共用布局。</li><li><b>C3 区块间距 &gt; 区块内间距</b>：区块之间 <code>--gap-block</code> = <b>48px</b>（= <code>--s7</code>）， 区块内部 <code>--gap-in</code> = <b>12px</b>（= <code>--s3</code>）。 <b>两轴都要成立</b>（C3 原话不区分横竖）：状态条本笔原样保留 ⇒ 它内部那排读数之间的 <code>--s5</code> 24px 是页面里绕不开的区内间距，取 20 会倒挂、取 24 会持平（3a.2b 指标页就栽在 19 &lt; 26）。 曾取 32px，但探针按「盒间空白 + 两侧内边距」量出来是 <b>40px</b>：状态条最右那块 <code>.tz</code> 带 <code>border-left</code> 与 <code>padding-left:16</code>，与它左边那个读数之间的视觉白带是 24 + 16 = 40。 那不是判据错，是<b>真数</b> ⇒ 不去掉那 16px 内边距来凑，直接把区块间距再抬一档到 48px， 这样<b>两种口径</b>（只算 gap 的 24 / 算白带的 40）下都严格成立。 这是<b>设计值</b>；实数由探针两轴各量一遍，不在文字里下结论。</li><li><b>各区宽度</b>：<b>实数由探针在 1440 视口量</b>（主区可用宽 = 视口 − 壳侧栏 232 − 主区左右各 32 − 滚动条； 滚动条宽实测，不写常数）。</li><li><b>8 处动效落点</b>：页面上带 <code>[data-anim]</code> 角标的元素即为落点，逐个数得出 8 个。</li><li><b>三样易漏项</b>：⑤ 的「起止两行只来自 traces ⇒ 模拟日常下恒空」写在 ⑤ 的注里； ③ 的「只留 200 条、被淘汰的 seq 要从 seenSeq 摘掉」写在底部哨兵那条里； 「图例 9 类、数字是<b>本页窗口内累计</b>不是帧里 counts」写在 ② 的脚注与 ④ 的标题里。</li></ul><b>本窗实测（真帧 60 拍 · 抓帧期间共 3152 条事件 · 0 条被覆盖丢失）</b><ul><li>抓帧期间（整窗）：缓存 2138 · 锁 370 · 订单 185 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 401 · 防护 58</li><li>页面窗口内（最近 1000 条 · <b>图例读的就是这一行</b>）：缓存 655 · 锁 143 · 订单 72 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 130 · 防护 0</li><li><b>两者对不上的地方就是这页的结构性后果</b>：防护整窗 <b>58 条</b>、页面窗口 <b>0 条</b>；警告整窗 <b>112 条</b>、页面窗口 <b>0 条</b> ——缓存一类占了大头，稀有的类会被 <b>1000 条</b>的封顶<b>挤出窗口</b>，页面上从此看不见。（如实显示，<b>不补数</b>；这是本步发现的真问题，处置留给你裁。）</li><li>9 类里页面窗口<b>有量 </b>4 类、<b>0 条 </b>5 类：库存 · 审计 · 通知 · 敏感词 · 防护 —— 是负载没走到那几条路径、还是那几环没接通，本步<b>不下结论</b>。</li><li>严重度（页面窗口）：信息 1000 · 警告 0 · 错误 0 ⇒ <b>error 0 条</b>，「error 卡片闪红」这一处本窗没有可演示的卡片（落点在，见 ③）。</li></ul><b>这一版（丙 · 上下分区）的结构主张</b><ul><li>上半「仪表带」看数、下半「事件流」看流，各自拿满宽。</li><li>仪表带里三格<b>不同宽、不同形态</b>：折线（最宽）／ 9 类计数条 ／ 严重度分布＋读数。</li><li>9 类计数<b>只出现在仪表带</b>；② 过滤栏因此只留类名与开关状态（避免同一份数字在屏幕上出现两次）。</li><li>收益：中区<b>最宽</b>，卡片用满列（时间戳到毫秒、traceId 可见），tail -f 最舒服。</li></ul>" }
}
/* @@EV-DATA:END 勿手改 @@ */

/* ── 小工具 ────────────────────────────────────────────────────────────────── */
var esc = function (s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
var num = function (v) { return Number(v || 0).toLocaleString('zh-CN') }
var shortId = function (s) { return s ? String(s).slice(0, 8) : '无' }

/* ── ① 状态条（两版相同）──────────────────────────────────────────────────── */
function statusBar() {
  var s = DATA.stats, c = DATA.clock
  return '<header class="ev-status" data-blk="status">' +
    '<div class="cell"><b>' + num(s.events) + '</b><span>窗口内事件（最多 ' + num(s.cap) + ' 条）</span></div>' +
    '<div class="cell"><b>' + c.rate.toFixed(1) + '</b><span>本拍速率（条/秒）</span></div>' +
    '<div class="cell"><b>' + num(s.traces) + '</b><span>窗口内请求链路（最多 ' + num(s.traceCap) + ' 次）</span></div>' +
    '<div class="tz">' +
      '<span>' + esc(c.phase) + ' · 第 <b>' + c.seq + '</b> 拍 · 每秒 1 拍</span>' +
      '<span>' + esc(c.tick) + '</span>' +
      '<span><i class="mqdot' + (c.mq ? '' : ' off') + '"></i>MQ ' + (c.mq ? '已连接' : '未连接') + '</span>' +
    '</div>' +
    /* ⚠️ 数据源：用户 2026-10-16 裁定 —— **留在页头右上**，且「已放 N / 500 条」**常驻**。 */
    '<div class="ev-src">' +
      '<span class="ev-seg"><span class="on">正常</span><span>模拟日常</span></span>' +
      '<span class="cnt">已放 ' + num(s.demoFed) + ' / 500 条（模拟日常未开）</span>' +
    '</div>' +
  '</header>'
}

/* ── 条件块：断线条（判据是 `lost`，不是 `errMsg`）────────────────────────────
   本窗**未触发**（后端跑满 60 拍自己正常收尾，但那是抓帧脚本主动结束，不是页面断线）
   ⇒ 画成虚线「条件块」。⚠️ 它在结构上**必须留位置**：没有它，「数字静静停住」就没人说。 */
function flashSlot() {
  return '<div class="slot top" data-blk="flash" data-anim="8" data-anim-lbl="动8">' +
    '条件块 · 断线条（判据 <code>lost</code>）：本窗未触发。出现时本行脉冲一次，右侧带「立即重连」。' +
    '</div>'
}

/* ── ② 过滤栏 ──────────────────────────────────────────────────────────────── */
/* counts = true（甲）在行尾给数字；counts = false（丙）只留类名与开关 —— 数字归 ④ 仪表带。
   ⚠️ 动效落点 4（计数数字滚动）与 7（图例计数闪一下）**落在同一处**，这是刻意的：
   它俩本来就是同一个数字上的两个效果（位数滚动 + 变化时闪一下），拆成两个元素反而是假结构。
   ⇒ 标注写成逗号列表 `data-anim="4,7"`，探针按逗号拆。 */
function rail(counts) {
  var h = '<div class="ev-rail" data-blk="rail"' +
    (counts ? ' data-anim="4,7" data-anim-lbl="动4·7"' : '') + '>'
  h += '<div class="row all"><span class="sw' + (DATA.rail.allOn ? '' : ' off') + '"></span>' +
       '<span class="k">全部</span><span class="c">' + num(DATA.rail.total) + '</span></div>'
  DATA.kinds.forEach(function (k) {
    h += '<div class="row' + (k.n ? '' : ' off') + '">' +
      '<span class="sw"></span><span class="k">' + esc(k.label) + '</span>' +
      '<span class="c"' + (counts ? '' : ' data-hidden="1"') + '>' + (counts ? num(k.n) : '—') + '</span></div>'
  })
  /* ⚠️ 口径必须写在这里（图例旁边），不是写在别处：图例数字是**本页自己攒的窗口内累计**，
     帧里那个 `counts` 是「本拍」的 —— 拿它当图例数字，每次静默的一拍上会全体归零。
     3a.2b 的 S1/S3 就是漏了这一条（把「单序列」当成唯一的口径问题）。 */
  h += '<div class="sub">严重度 · 类计数 = 本页<b>窗口内累计</b>（帧里的 counts 是「本拍」的，不用它）' +
       (counts ? '' : ' · 数字在右侧仪表带') + '</div>'
  h += '<div class="sev">'
  DATA.sevs.forEach(function (v) { h += '<span>' + esc(v.label) + (counts ? ' ' + num(v.n) : '') + '</span>' })
  h += '</div>'
  h += '<div class="foot">全部打开</div>'
  return h + '</div>'
}

/* ── ③ 事件流 ──────────────────────────────────────────────────────────────── */
function card(ev, narrow) {
  var anim = ev.anim === 6 ? ' data-anim="6" data-anim-lbl="动6"' : ''
  return '<div class="ev-card' + (narrow ? ' narrow' : '') + '"' + anim + '>' +
    '<span class="bar"></span>' +
    '<span class="ic"></span>' +
    '<span class="sev">' + esc(ev.sevLabel) + '</span>' +
    '<span class="tm">' + esc(ev.ts) + '</span>' +
    '<span class="main"><b>' + esc(ev.title) + '</b>' +
      '<span class="kd">' + esc(ev.kindLabel) + '</span>' +
      (ev.obj ? '<span class="ob">' + esc(ev.obj) + '</span>' : '') +
      '<span class="dt">' + esc(ev.detail) + '</span></span>' +
    '<span class="tr' + (ev.trace ? '' : ' none') + '">' + esc(ev.trace ? shortId(ev.trace) : '无）') + '</span>' +
  '</div>'
}
/* blk = true 时把「区块」标记打在这一层（丙：③ 自成一块）；
   甲 的 ③ 是 `.col3`（折线横幅 + 列表同属一块），标记打在外层 ⇒ 这里不重复打。 */
function stream(narrow, blk) {
  var h = '<div class="ev-block"' + (blk ? ' data-blk="stream"' : '') + '>'
  h += '<div class="ev-h2"><b>事件流</b><span class="n">' +
       '窗口内 ' + num(DATA.stats.events) + ' 条 · <b>无分页</b> · 自动滚到底 · 只留最近 200 条（卡片，不是 seq）· ' +
       '点一条看「这一次请求的完整链路」（没有 traceId 的不给点）</span></div>'
  /* 动效落点 1（新卡片顶部滑入）/ 2（老的滚出）落在**列表容器**上 */
  h += '<div class="ev-list" data-anim="1" data-anim-lbl="动1">'
  DATA.cards.forEach(function (e) { h += card(e, narrow) })
  h += '</div>'
  /* 动效落点 2：底部哨兵（滚到这里就淘汰最老的一张） */
  h += '<div class="slot" data-anim="2" data-anim-lbl="动2">' +
       '底部哨兵 · 自动滚到底：卡片数超过 200 就从顶部淘汰最老的（<b>被淘汰的 seq 也要从 seenSeq 摘掉</b>，' +
       '否则新事件会被误判成重复、页面看着不动）</div>'
  /* 动效落点 5：error 卡片闪红 —— 本窗 error 0 条，落点在、可演示的卡片不在 */
  h += '<div class="slot" data-anim="5" data-anim-lbl="动5">' +
       '动效落点 · error 卡片闪红：本窗 <b>error 0 条</b>（严重度只有 info / warn）⇒ 这一处本窗没有可演示的卡片</div>'
  return h + '</div>'
}

/* ── ③ 顶部 / ④ 里的速率折线（tail -f 的心跳图）─────────────────────────── */
function spark() {
  var r = DATA.rate, mx = Math.max.apply(null, r) || 1
  var pts = r.map(function (v, i) {
    var x = (i / (r.length - 1)) * 100
    var y = 100 - (v / mx) * 92
    return x.toFixed(2) + ',' + y.toFixed(2)
  }).join(' ')
  var svg = '<svg viewBox="0 0 100 100" preserveAspectRatio="none" height="78" role="img" aria-label="每秒事件速率折线">' +
    '<rect x="0" y="0" width="100" height="100" fill="none" stroke="#e2e2e2" stroke-width="0.4"/>' +
    '<polyline points="' + pts + '" fill="none" stroke="#7a7a7a" stroke-width="0.9" vector-effect="non-scaling-stroke"/>' +
    '</svg>'
  /* 动效落点 3：折线随新事件推进 */
  return '<div class="ev-spark" data-anim="3" data-anim-lbl="动3">' + svg +
    '<div class="cap">每秒事件速率（本窗 60 拍 · 峰值 ' + mx.toFixed(1) + ' 条/秒 · 首拍 ' +
    r[0].toFixed(1) + '）—— 尾部随每一拍新事件右推</div></div>'
}

/* ── ④ 严重度分布（横条，不是环图）──────────────────────────────────────── */
function sevBars() {
  var mx = Math.max.apply(null, DATA.sevs.map(function (v) { return v.n })) || 1
  var h = '<div class="ev-bars">'
  DATA.sevs.forEach(function (v) {
    var w = (v.n / mx) * 100
    h += '<div class="ev-bar"><span class="nm">' + esc(v.label) + '</span>' +
      '<span class="track"><span class="fill" style="width:' + w.toFixed(1) + '%"></span></span>' +
      '<span class="num">' + num(v.n) + '</span></div>'
  })
  return h + '</div>'
}
/* ── ④ 9 类计数（横条）──────────────────────────────────────────────────── */
function kindBars() {
  var mx = Math.max.apply(null, DATA.kinds.map(function (k) { return k.n })) || 1
  var h = '<div class="ev-bars" data-anim="4,7" data-anim-lbl="动4·7">'
  DATA.kinds.forEach(function (k) {
    var w = (k.n / mx) * 100
    h += '<div class="ev-bar"><span class="nm">' + esc(k.label) + '</span>' +
      '<span class="track"><span class="fill" style="width:' + w.toFixed(1) + '%"></span></span>' +
      '<span class="num">' + num(k.n) + '</span></div>'
  })
  return h + '</div>'
}
function readout() {
  return '<div class="ev-read">' +
    '<span>本拍事件<i>' + num(DATA.clock.fresh) + '</i></span>' +
    '<span>本拍链路<i>' + num(DATA.clock.freshTrace) + '</i></span>' +
    '<span>窗口合计<i>' + num(DATA.stats.events) + '</i></span>' +
    '<span>丢帧<i>' + num(DATA.stats.droppedTotal) + '</i></span>' +
  '</div>'
}

/* ── ⑤ 链路展开 ────────────────────────────────────────────────────────────── */
function drawer() {
  var d = DATA.drawer
  var h = '<div class="ev-drawer" data-blk="drawer">'
  h += '<div class="hd"><b>链路展开</b>' +
    '<span class="mono">默认塌成一条 48px：最近 trace ' + esc(shortId(d.traceId)) + ' · ' +
    d.chain.length + ' 事件 · 点一下拉起</span>' +
    '<span class="st">' + d.status + '</span>' +
    '<span class="mono">' + esc(d.method) + ' ' + esc(d.path) + ' · 总耗时 <b>' + d.ms + ' ms</b></span></div>'
  h += '<ol class="ev-chain">'
  h += '<li class="ends"><span class="tm">' + esc(d.start) + '</span>请求开始（' + esc(d.method) + ' ' + esc(d.path) + '）</li>'
  d.chain.forEach(function (e) {
    h += '<li><span class="tm">' + esc(e.ts) + '</span><b>' + esc(e.kindLabel) + '</b> · ' +
      esc(e.title) + (e.obj ? '（' + esc(e.obj) + '）' : '') + '</li>'
  })
  h += '<li class="ends"><span class="tm">' + esc(d.end) + '</span>请求结束 · 总耗时 <b>' + d.ms + ' ms</b></li>'
  h += '</ol>'
  /* ⚠️ 易漏项 1：起止两行**只来自帧的 traces**，不来自事件 ⇒ 演示模式下 traces 恒空 */
  h += '<div class="note">⚠️ 中间 ' + d.chain.length + ' 条 = 本页窗口内带这个 traceId 的事件（按墙钟升序）。' +
    '起止两行<b>只来自帧里的 traces、不来自事件</b> ⇒ <b>「模拟日常」下 traces 恒为空</b>，' +
    '那两行会显示「这一次请求的起止还没到」。<b>不许因为它空就把 ⑤ 删掉</b> —— 空态也是这一块要说的内容。</div>'
  return h + '</div>'
}

/* ── ⑥ 开关栏（3a.6 乙案的防护 / 注入开关；本页只占位）──────────────────── */
function switchBar() {
  var P = [['限流', 1], ['熔断', 1], ['降级', 0], ['重试', 1], ['分池', 1]]
  var I = [['Redis 断开', 1], ['MQ 断开', 0], ['慢 SQL', 0]]
  function grp(lab, arr) {
    var s = '<div class="grp"><span class="lab">' + lab + '</span>'
    arr.forEach(function (x) {
      s += '<span class="sw2' + (x[1] ? '' : ' dead') + '">' + esc(x[0]) + (x[1] ? '' : ' · 不可开关') + '</span>'
    })
    return s + '</div>'
  }
  return '<div class="ev-switch" data-blk="switch">' +
    '<div class="ev-h2"><b>开关栏</b><span class="n">3a.6 乙案引入 · 本页只占位（本轮不接行为）· ' +
    '不可开关的键 = 后端 WIRED 清单里没有它</span></div>' +
    grp('防护闸', P) + grp('注入', I) +
  '</div>'
}

/* ── 两版装配 ─────────────────────────────────────────────────────────────── */
function renderJia() {
  return '<section class="ev-jia">' +
    statusBar() + flashSlot() +
    '<div class="mid">' +
      rail(true) +
      '<div class="col3" data-blk="stream">' + spark() + stream(true) + '</div>' +
      '<div class="live" data-blk="live"><div class="ev-h2"><b>实时统计</b><span class="n">严重度分布（横条）</span></div>' +
        '<div class="ev-block">' + sevBars() + '<div style="height:var(--gap-in)"></div>' + readout() + '</div></div>' +
    '</div>' +
    '<div class="ev-full">' + drawer() + '</div>' +
    '<div class="ev-full">' + switchBar() + '</div>' +
  '</section>'
}
function renderBing() {
  return '<section class="ev-bing">' +
    statusBar() + flashSlot() +
    '<div class="mid">' +
      rail(false) +
      '<div class="righter">' +
        '<div class="band" data-blk="band">' +
          '<div class="cell"><span class="ttl">每秒事件速率</span>' + spark() + '</div>' +
          '<div class="cell"><span class="ttl">9 类计数（本页窗口内累计）</span>' + kindBars() + '</div>' +
          '<div class="cell"><span class="ttl">严重度分布</span>' + sevBars() + readout() + '</div>' +
        '</div>' +
        stream(false, true) +
      '</div>' +
    '</div>' +
    '<div class="ev-full">' + drawer() + '</div>' +
    '<div class="ev-full">' + switchBar() + '</div>' +
  '</section>'
}

function render(variant) {
  if (!DATA) { document.body.insertAdjacentHTML('afterbegin', '<p style="padding:16px">DATA 未生成</p>'); return }
  document.getElementById('app').innerHTML = (variant === 'bing' ? renderBing() : renderJia())
  /* 判定条：给验收看的（不是页面的一部分）。两版各一份，数字由数据脚本现算。 */
  document.getElementById('judge').innerHTML = DATA.judge[variant === 'bing' ? 'bing' : 'jia']
}

window.render = render
