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
     ② 仪表（折线 / 9 类计数 / 严重度）的位置：**甲**把折线放在 ③ 顶部、其余在右栏 ④；
        **丙**三样都在 ④ 仪表带里、③ 只留事件流。
     ⚠️ 事件流本身（③）**两版一模一样**：本笔把卡片改成控制台行之后，列构成是定死的
        （前导 / 时刻 / 图标 / 等级 / 类 / 消息），不再有「窄版少两列」那种写法 ——
        两版差别只剩消息列**可用多宽**（甲 ≈330px、丙 ≈830px），甲 更容易吃省略号。

   五、③ 的行是有**行为**的（不是静态图）
     `window.__evStream` = { append(n) / replay() / pause() / resume() / bottom() / clear()
       / select(tr) / state() / opts }，`state()` 里能读到 DOM 行数、scrollTop、
       滚动条实测宽、动画时长最后一次实测值 —— 第 ⑥ 步细化时这套行为要**原样搬过去**。
   ══════════════════════════════════════════════════════════════════════════════ */

/* @@EV-DATA:BEGIN —— 由 scripts/probes/probe-b7a3-wire-data.mjs 从真帧生成，勿手改 @@ */
var DATA = {
  /* 来源："60 拍事件帧 · 17:52:33–17:53:34" */
  meta: { ticks: 60, winEvents: 3152, capturedOn: "2026-10-16",
    note: "窗口内累计按页面口径（absorbEvents/absorbTraces 重放），不是帧里本拍的 counts" },
  clock: {"seq":59,"tick":"17:53:34","phase":"已连接","mq":true,"rate":4.938,"fresh":5,"freshTrace":8},
  rowMax: 200,
  stats: {"events":1000,"traces":300,"cap":1000,"traceCap":300,"droppedTotal":0,"demoFed":0},
  rate: [0,34.98,35.033,41.2,56.467,42.854,38.831,22.312,20.721,42.373,45.935,84.326,21.968,34.519,28.237,18.208,20.698,74.058,60.693,19.541,24.679,55.062,28.638,19.393,60.367,70.855,21.724,43.501,35.945,27.752,38.802,69.134,76.062,20.428,51.317,21.096,20.812,26.564,106.117,64.036,34.864,34.63,19.766,52.32,31.073,94.741,83.777,20.067,46.432,36.822,18.854,20.753,101.511,73.463,21.381,18.699,48.436,30.152,22.016,4.938],
  /* 类：业务 9（grp=biz，行里的 k 用这套下标）＋ 设施 3（grp=fac，本窗 0 条）*/
  kinds: [{"k":"cache","label":"缓存","n":655,"grp":"biz"},{"k":"lock","label":"锁","n":143,"grp":"biz"},{"k":"order","label":"订单","n":72,"grp":"biz"},{"k":"stock","label":"库存","n":0,"grp":"biz"},{"k":"audit","label":"审计","n":0,"grp":"biz"},{"k":"notify","label":"通知","n":0,"grp":"biz"},{"k":"sensitive","label":"敏感词","n":0,"grp":"biz"},{"k":"identity","label":"身份","n":130,"grp":"biz"},{"k":"protection","label":"防护","n":0,"grp":"biz"},{"k":"slow_sql","label":"慢 SQL","n":0,"grp":"fac"},{"k":"mq","label":"MQ","n":0,"grp":"fac"},{"k":"scheduler","label":"调度","n":0,"grp":"fac"}],
  /* 「源」= 2 项（裁定：慢 SQL / MQ / 调度 归入「类」，不单列成源）*/
  sources: [{"k":"events","label":"事件流","n":1000,"on":true},{"k":"drill","label":"演练轮询","n":0,"on":true}],
  sevs: [{"s":"info","label":"信息","n":1000},{"s":"warn","label":"警告","n":0},{"s":"error","label":"错误","n":0}],
  /* 错误率与延迟分位：都由本窗真数据现算（错误率 = (warn+error)/窗口内事件）*/
  sevAgg: {"info":1000,"warn":0,"error":0,"total":1000},
  lat: {"n":212,"p50":2,"p95":62,"p99":76,"max":79,"flat":false},
  /* Top-N 榜三档：对象 / 慢请求 / 错误（错误榜本窗恒空，不塞假数据）*/
  top: {"obj":[["键 all-v3",223],["键 all",222],["请求路径 /goods/goodsSelect",70],["请求路径 /order/orderPayIfUp",36],["请求路径 /order/orderAdd",24]],"slow":[["POST /order/orderPayIfUp",79,12],["POST /order/orderAdd",45,12],["POST /goods/goodsSelect",18,21],["GET /goods/goodstype",5,82],["POST /goods/goodsSelectHome",4,85]],"err":[]},
  /* 时刻列口径：rows 已按「同拍内按组首时刻稳定排序」排过（跨拍不动）*/
  order: { rule: "同拍内按时刻局部排，跨拍按环序", blockMoved: 10, blocks: 865, barrier: 1, firstMoved: 685, invInTick: 28, invCross: 1, brokenGroups: 29, brokenRows: 174, grade: "组级（不是行级）" },
  rail: { allOn: true, total: 1000 },
  /* 九类的真图标（真页面 `KIND_ICON` 原样搬来）：草图走 currentColor，不上类色 */
  icons: {"cache":["M3.2 4.4h9.6v2.2H3.2z","M3.2 9.4h9.6v2.2H3.2z"],"lock":["M4.2 7.4h7.6v5.2H4.2z","M6 7.4V5.6a2 2 0 0 1 4 0v1.8"],"order":["M4.2 2.6h5.2L12 5.2v8.2H4.2z","M9.4 2.6v2.6h2.6","M6.2 8.6h3.6","M6.2 10.8h3.6"],"stock":["M2.6 5.4 8 2.8l5.4 2.6v5.2L8 13.2l-5.4-2.6z","M2.6 5.4 8 8l5.4-2.6","M8 8v5.2"],"audit":["M8 3v9.2","M2.8 5h10.4","M2.8 5v2.2","M13.2 5v2.2","M1.4 7.2h2.8L2.8 9.6z","M11.8 7.2h2.8L13.2 9.6z","M5.2 12.2h5.6"],"notify":["M3 4.6h10v6.8H3z","M3 4.6l5 3.6 5-3.6"],"sensitive":["M2.4 8c1.6-2.1 3.4-3.2 5.6-3.2s4 1.1 5.6 3.2c-1.6 2.1-3.4 3.2-5.6 3.2S4 10.1 2.4 8z","M6.9 8a1.1 1.1 0 1 0 2.2 0 1.1 1.1 0 1 0-2.2 0z","M3.4 13.2 12.6 2.8"],"identity":["M8 4.2a2.1 2.1 0 1 0 0 4.2 2.1 2.1 0 0 0 0-4.2z","M3.8 13.4c0-2.3 1.9-3.6 4.2-3.6s4.2 1.3 4.2 3.6"],"protection":["M8 2.4 12.9 4.3v3.7c0 2.7-1.9 4.8-4.9 5.6-3-.8-4.9-2.9-4.9-5.6V4.3z","M8 5.6v6.6"]},
  /* 行里那截 traceId 的 8 位前缀表（行里只存下标，省一半字符） */
  trIdx: ["0c16ae9c","248e65b7","5a2b1e6f","f1fcb262","b99be0a2","def74f9f","74129f3b","acc88e96","8ed1fe5d","c8f6f51d","7bcfa542","c4c94776","e8a836ed","39d27965","afeab1a4","b57d2dd5","7cde9a46","1fa19d54","810c0af1","912a7542","1d1c6842","8b64e286","af60b0e5","435e825b","6ef45166","b450333c","dc427d6a","8626ed48","ca5264ca","3a251747","0c136a1b","c88507c2","56bfb699","5a4957d5","ebbd0591","cd115722","3d16adcf","a2863f0d","a2ffa0b4","9a701d35","cc291191","5f1e2c7e","a2572c1d","066314bf","48962c2e","d9adb6b1","49f25393","d0200486","248b1fb3","eeabfc90","d6552011","e204e198","89de348f","e420c721","cb8e0e0e","f713cf32","e10df6a1","8bbcd1e3","0c7609da","11a3595c","078f5f43","8941188d","e39eddc9","e9aaf2a8","d3fa0f36","39f9a901","f09faa1f","629417b2","1f2b7657","ab02030a","0c7962db","d8923d11","ae5393ae","9d8fded3","ae9cb3fc","111235f1","ece9f00d","98f8d0da","0fe3c0f4","7513acdd","02cbc0cf","77adba07","3c4d6f7e","4dd250bd","3d298669","1345e8bb","6c9765b6","6ea8ba74","183dea62","82803c87","df60c8b9","7f738600","4030d646","8d71a528","fbed6f28","9c4e2f69","f8747d55","3242a44b","0c31ca25","7db431b0","52bfd0f6","51f721bd","d22c3fea","85e3fc87","b33e4007","29dfe5d9","6e6ae2e3","2cbf0be8","eabe42f5","e61e2af5","2d9465e2","7c56f683","2f59a498","391b2bcc","89b324c7","45c85689","5be47812","edb663b7","d97273d1","c5083cc6","6ec2a1ca","a93f2de8","93c4c912","9f34262d","8d2c7d81","0b80c04a","875c49c8","4c87535e","0beb8a22","904b11ce","a497d18f","aa6b8f08","b3f0096b","ca985891","bbb197f8","cb265c46","f9dc1a96","35cbfd90","0a9234eb","ba7d92a8","31253fd2","a8cdef78","a85d2e3a","39100fb9","84a73d0d","f6dcdfd4","cfc48a87","cdc17d73","fad0a199","1e19a0f1","fa3b0821","834c5bb6","2a1f5e16","2115da81","46c5187c","a64ccd16","dd0fbdde","94db2cac","d2ef4ff3","c850db68","5292cef1","87778e05","c0f010d4","65894cb3","6424d8b7","1720dab0","a73aa965","db16c260","960ba516","02e752d7","4b2055af","48851942","eb8cfff5","3e222029","67fb4662","6440d114","9432d134","8fcdfaa4","606e4bb1","5ed464c0","0a5f1acf","1f219aa2","6a045fd9","3d1ec2f0","52699852","0a5756f5","efc900a4","0f193533","7d5a06ed","4ae92069","492ea068","b7362e66","0dd49e67","e5bd4cca","87ff4387","a02d9980","824be7b9","5a162f11","0a651bd9","c069b510","0622cc13","937dfc83","03444fde","832d2b9c","04bf796e","a75993f3","588bcdc2","cad4c477","7016f9a7","856a3b5a","70948d87","af2bfb68","215f0db0","451e792a","51a1cb64","c5b8bf05","3ca9171f","c5dc4311","92f939c7","f2a834f0","2b54240b","f957ef59","1ae4959f","afd89227","9df600b0","75679f54","1de66663","ebf293be","027131cc","c85b6bd1","b8778b06","0387417f","b9c50c0c","fe703751","bab6f564","a8d5868a","43466f2a","361d7551","729feb1f","c0321268","1a956ec3","934dd0f0","5d0d49eb","fcd22170","46378833","3eaeaa28","9e46cc3e","3c9e5191","5b3617b5","334293d9","e042b099","e209eae1","48fe8e32","7e8e7b6a","ac156cb9","12c4240e","4aedea04","943ca86c","4f9a05e9","ff28df1a","94bc20b6","4e873628","91d3e8d8","cc428afd","9bba464d","9956a4ed","68d4b520","e3e59bc2","e70de1a7","bf0d56ed","d28ba735","9cf97892","b4e87222","c5dac641","dd076337","0bbbd2fa","ad795cf4","bc6305e4","70d3d200","b048338f","b0c6dfc0","a9d19bc7","51deefb5","8e5fcd20","451167fb","989502f6","09de6fad","1971a961","504d75e0","d64fd717","1d5510bc","3a8bca4f","1af5eca9","13986a45","604ba97f","d7c4ab02","da6588ee","fbcc81d0","9a789d2d","5676a66d","36c58de8","494a2903","c09d1c32","c18c916e","9f0d9c4a","f0e78baf","d150502a","51b3e7b6","b175bf3a","4946d704","a173245b","f5caa5a9","f18d9364","467a1a4d","3ba8908e","d36320a9","d74833fb","3720a844","29c20394","fe182fd4","bd038f96","c18f4c82","77fde35b","e1caad0b","7ac44b97","6ae44de6","cc469045","a95cce9e","db0620c1","b9d0646f","41dfc677","8ee3800b","a5515e4c","0f7a35f7","a1bc3bc8","0f10dd87","848fe610","b46a363c","4572dd8c","1c41705b","12f0a9eb","03fa1198","27c3c046","1449cee7","f4f9f4c4","2055d40a","81b0f09b","49846688","4c36dec7","dc737636","e2d187e1","042400f7","288d2f90","3bbb646f","1d51e5be","88e5eaae","3600caf4","479e5612","d1a44fc4","47ed1048","4f6df234","060263f6","7fe57212","44097c54","408f044d","912f07a1","03cb9ca5","1b2a4691","4d8b5a28","5ce6db63","20b389a3","cb5f6a1a","0f6cc887","3141661b","44d8bb5d","6a543274","edd2ff10","5094fd44","ae32299b","6cce15bd","5ea1329e","dab40c1b","ac32089c","d1dffe4c","f672d8da","adaad5f7","85ba8d98","926c07dc","d014dac8","76a7c714","975014be","a6e7a930","e9b66e5c","6dd325d7","da5d4dc7","5b185ff4","d37d5d6f","41d8460e","ac95b219","85b1990c","a3dedf04","ca10637a","f043275f","2502da3a","f0a2472e","b9e9240b","1c97e045","f26e5a55","55a821dc","c8939335","549ee0db","38fd3ec0","d781b817","2e265784","493898a5","760dfdf3","4b29acda","ddf99f4b","90191703","7efaa6d4","9aa1fb49","eff79374","33d78fe7","ce55e118","3a991969","047aef92","86f052dd","f05bb39a","468e909f","aeea7400","4b005929","2a47dbb0","1d4e845c","fa9f8c31","98010c8e","86bb7c5b","77248d7e","adc37817","0f766b92","3871a260","27952256","001098f7","f1bf5ca0","047bd9c1","defd5f7a","c0f102f3","6310545c","0625879e","872b0b94","8b8f7700","a85d35d3","1a03ab00","31089188","2786278e","bcd9ef79","70d92dd4","27b7bb5e","03250b6b","708c8515","6afeeac4","a177b260","0f97a127","8b7c6fe4","20cc27ef","bacaf519","1b1dc4e5","0d49414b","9e9d8909","594960b0","268ca5ff","136a5dbf","6bfdbbe2","d69d1944","d5818fbe","4df164d1","10568729","c909ff9e","88513c1b","3dab7b4b","ee6053f2","1048123b","1a4992d4","991797fa","50a66aaa","013c2185","27535295","51b4e7bd","e99f2e18","7c76f613","4280fdff","e8912f47","1e4ddbfb","563acf56","881db1f4","61b2f396","af621b3b","441fcb3f","c337000c","f22d4b85","4e5bdf6e","70062230","52aaab5e","fd9efc5c","84f015c2","43a64371","5fe0fee9","c68afd10","4a5ab4cf","01502e97","e409edc2","bb89a96e","2c33ef72","aed2f352","aefb91db","11e84104","265dbabf","c2d633cf","314e5233","8f90f181","2416d93b","2dc23eca","7478cdca","9987159e","41604a74","f0ea82e7","0f44ac5b","e4a529fe","58f20448","47e0b602","60a16065","32014b36","c3a91746","5fae2155","16e990c9","82433e9e","94dee078","37631f2d","b278c1a7","6be90c14","f3cfc836","c6b8f029","19d78a4e","ad4f1eed","b953fd35","55c924ac","be71a7e7","fde4aaff","3987850a","dcefd4c9","e94d2569","bf9b4096","16379435","682401e4","cdb08e65","87176e60","fc4500f0","f798d040","a2be83d6","313f725a","e53ef1ae","1d71ea00","b3aa6aa0","f85481ab","d20a472e","60a7b987","81a1db36","d0923f94","74246499","d382c346","b2ab9452","5aa781ff","9ce07857","03befbda","199d7dbe","0411f19d","3d6ebb96","ecec37b2","b6e06f60","c355a9e4","12581219","4c992bad","76a3d1f0","1a313c5c","dc308e43","6e9dc8e4"],
  /* 紧随 trIdx 下标的 traces 记录：[方法, 路径, 耗时ms, 状态, 起, 止]；null = 这一号在本窗查不到记录 */
  trRec: [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,["POST","/goods/goodsSelectHome",3,200,"17:53:25.440","17:53:25.443"],["POST","/goods/goodsSelectHome",2,200,"17:53:25.501","17:53:25.503"],["POST","/goods/goodsSelectHome",3,200,"17:53:25.534","17:53:25.537"],["GET","/goods/goodstype",1,200,"17:53:25.543","17:53:25.544"],["POST","/goods/goodsSelectHome",2,200,"17:53:25.627","17:53:25.629"],["GET","/goods/goodstype",2,200,"17:53:25.659","17:53:25.661"],["GET","/goods/goodstype",1,200,"17:53:25.673","17:53:25.674"],["GET","/goods/goodstype",2,200,"17:53:25.752","17:53:25.754"],["POST","/goods/goodsSelectHome",1,200,"17:53:25.814","17:53:25.815"],["GET","/goods/goodstype",1,200,"17:53:25.922","17:53:25.923"],["POST","/goods/goodsSelectHome",2,200,"17:53:25.923","17:53:25.925"],["POST","/goods/goodsSelectHome",2,200,"17:53:25.954","17:53:25.956"],["POST","/goods/goodsSelectHome",2,200,"17:53:26.017","17:53:26.019"],["GET","/goods/goodstype",1,200,"17:53:26.021","17:53:26.022"],["GET","/goods/goodstype",2,200,"17:53:26.109","17:53:26.111"],["POST","/goods/goodsSelect",18,200,"17:53:26.156","17:53:26.174"],["GET","/goods/goodstype",2,200,"17:53:26.169","17:53:26.171"],["POST","/goods/goodsSelectHome",2,200,"17:53:26.186","17:53:26.188"],["POST","/goods/goodsSelectHome",2,200,"17:53:26.249","17:53:26.251"],["POST","/goods/goodsSelect",8,200,"17:53:26.279","17:53:26.287"],["GET","/goods/goodstype",1,200,"17:53:26.288","17:53:26.289"],["POST","/goods/goodsSelect",8,200,"17:53:26.357","17:53:26.365"],["GET","/goods/goodstype",1,200,"17:53:26.358","17:53:26.359"],["POST","/order/orderAdd",29,200,"17:53:26.370","17:53:26.399"],["POST","/order/orderAdd",32,200,"17:53:26.370","17:53:26.402"],["POST","/order/orderAdd",32,200,"17:53:26.370","17:53:26.402"],["POST","/order/orderAdd",28,200,"17:53:26.371","17:53:26.399"],["POST","/order/orderAdd",30,200,"17:53:26.371","17:53:26.401"],["POST","/order/orderAdd",31,200,"17:53:26.371","17:53:26.402"],["POST","/order/orderAdd",27,200,"17:53:26.371","17:53:26.398"],["POST","/order/orderAdd",35,200,"17:53:26.371","17:53:26.406"],["POST","/order/orderAdd",31,200,"17:53:26.371","17:53:26.402"],["POST","/order/orderAdd",27,200,"17:53:26.373","17:53:26.400"],["POST","/order/orderAdd",26,200,"17:53:26.373","17:53:26.399"],["POST","/order/orderAdd",45,200,"17:53:26.371","17:53:26.416"],["POST","/goods/goodsSelectHome",2,200,"17:53:26.387","17:53:26.389"],["POST","/goods/goodsSelectHome",2,200,"17:53:26.465","17:53:26.467"],["GET","/goods/goodstype",2,200,"17:53:26.497","17:53:26.499"],["POST","/goods/goodsSelect",5,200,"17:53:26.506","17:53:26.511"],["GET","/goods/goodstype",1,200,"17:53:26.591","17:53:26.592"],["POST","/goods/goodsSelectHome",2,200,"17:53:26.591","17:53:26.593"],["POST","/goods/goodsSelect",9,200,"17:53:26.606","17:53:26.615"],["POST","/goods/goodsSelectHome",2,200,"17:53:26.617","17:53:26.619"],["POST","/goods/goodsSelect",10,200,"17:53:26.698","17:53:26.708"],["GET","/goods/goodstype",5,200,"17:53:26.747","17:53:26.752"],["GET","/goods/goodstype",1,200,"17:53:26.750","17:53:26.751"],["POST","/goods/goodsSelectHome",3,200,"17:53:26.780","17:53:26.783"],["POST","/goods/goodsSelectHome",4,200,"17:53:26.827","17:53:26.831"],["POST","/goods/goodsSelect",7,200,"17:53:26.857","17:53:26.864"],["GET","/goods/goodstype",1,200,"17:53:26.905","17:53:26.906"],["POST","/goods/goodsSelect",4,200,"17:53:26.950","17:53:26.954"],["GET","/goods/goodstype",1,200,"17:53:26.980","17:53:26.981"],["POST","/goods/goodsSelectHome",1,200,"17:53:26.980","17:53:26.981"],["POST","/goods/goodsSelectHome",1,200,"17:53:27.060","17:53:27.061"],["GET","/goods/goodstype",1,200,"17:53:27.092","17:53:27.093"],["POST","/goods/goodsSelect",8,200,"17:53:27.093","17:53:27.101"],["GET","/goods/goodstype",1,200,"17:53:27.184","17:53:27.185"],["POST","/goods/goodsSelectHome",1,200,"17:53:27.200","17:53:27.201"],["POST","/goods/goodsSelect",9,200,"17:53:27.229","17:53:27.238"],["POST","/goods/goodsSelectHome",2,200,"17:53:27.295","17:53:27.297"],["GET","/goods/goodstype",1,200,"17:53:27.342","17:53:27.343"],["POST","/goods/goodsSelect",8,200,"17:53:27.373","17:53:27.381"],["POST","/goods/goodsSelectHome",3,200,"17:53:27.374","17:53:27.377"],["GET","/goods/goodstype",3,200,"17:53:27.419","17:53:27.422"],["POST","/goods/goodsSelectHome",2,200,"17:53:27.466","17:53:27.468"],["GET","/goods/goodstype",1,200,"17:53:27.495","17:53:27.496"],["GET","/goods/goodstype",1,200,"17:53:27.583","17:53:27.584"],["POST","/order/orderPayIfUp",62,200,"17:53:27.626","17:53:27.688"],["POST","/order/orderPayIfUp",66,200,"17:53:27.626","17:53:27.692"],["POST","/order/orderPayIfUp",67,200,"17:53:27.626","17:53:27.693"],["POST","/order/orderPayIfUp",68,200,"17:53:27.627","17:53:27.695"],["POST","/goods/goodsSelectHome",2,200,"17:53:27.630","17:53:27.632"],["POST","/order/orderPayIfUp",66,200,"17:53:27.628","17:53:27.694"],["POST","/order/orderPayIfUp",61,200,"17:53:27.627","17:53:27.688"],["POST","/order/orderPayIfUp",71,200,"17:53:27.627","17:53:27.698"],["POST","/order/orderPayIfUp",74,200,"17:53:27.628","17:53:27.702"],["POST","/order/orderPayIfUp",79,200,"17:53:27.626","17:53:27.705"],["POST","/order/orderPayIfUp",76,200,"17:53:27.628","17:53:27.704"],["POST","/order/orderPayIfUp",64,200,"17:53:27.626","17:53:27.690"],["POST","/order/orderPayIfUp",78,200,"17:53:27.628","17:53:27.706"],["POST","/goods/goodsSelectHome",1,200,"17:53:27.744","17:53:27.745"],["GET","/goods/goodstype",1,200,"17:53:27.758","17:53:27.759"],["POST","/goods/goodsSelectHome",2,200,"17:53:27.826","17:53:27.828"],["POST","/goods/goodsSelectHome",2,200,"17:53:27.830","17:53:27.832"],["GET","/goods/goodstype",2,200,"17:53:27.871","17:53:27.873"],["GET","/goods/goodstype",2,200,"17:53:27.932","17:53:27.934"],["GET","/goods/goodstype",1,200,"17:53:27.950","17:53:27.951"],["POST","/goods/goodsSelectHome",1,200,"17:53:28.004","17:53:28.005"],["POST","/goods/goodsSelectHome",4,200,"17:53:28.123","17:53:28.127"],["GET","/goods/goodstype",1,200,"17:53:28.137","17:53:28.138"],["POST","/goods/goodsSelectHome",2,200,"17:53:28.215","17:53:28.217"],["POST","/goods/goodsSelectHome",2,200,"17:53:28.216","17:53:28.218"],["GET","/goods/goodstype",1,200,"17:53:28.237","17:53:28.238"],["GET","/goods/goodstype",1,200,"17:53:28.323","17:53:28.324"],["GET","/goods/goodstype",2,200,"17:53:28.373","17:53:28.375"],["POST","/goods/goodsSelectHome",4,200,"17:53:28.404","17:53:28.408"],["POST","/goods/goodsSelectHome",4,200,"17:53:28.499","17:53:28.503"],["GET","/goods/goodstype",1,200,"17:53:28.547","17:53:28.548"],["POST","/goods/goodsSelectHome",2,200,"17:53:28.559","17:53:28.561"],["POST","/goods/goodsSelectHome",3,200,"17:53:28.608","17:53:28.611"],["GET","/goods/goodstype",3,200,"17:53:28.639","17:53:28.642"],["GET","/goods/goodstype",1,200,"17:53:28.670","17:53:28.671"],["GET","/goods/goodstype",2,200,"17:53:28.733","17:53:28.735"],["POST","/goods/goodsSelectHome",4,200,"17:53:28.794","17:53:28.798"],["POST","/goods/goodsSelectHome",2,200,"17:53:28.888","17:53:28.890"],["POST","/goods/goodsSelectHome",2,200,"17:53:28.904","17:53:28.906"],["GET","/goods/goodstype",2,200,"17:53:28.908","17:53:28.910"],["POST","/goods/goodsSelectHome",2,200,"17:53:28.999","17:53:29.001"],["GET","/goods/goodstype",1,200,"17:53:29.028","17:53:29.029"],["GET","/goods/goodstype",1,200,"17:53:29.061","17:53:29.062"],["POST","/goods/goodsSelectHome",2,200,"17:53:29.140","17:53:29.142"],["GET","/goods/goodstype",1,200,"17:53:29.141","17:53:29.142"],["GET","/goods/goodstype",1,200,"17:53:29.246","17:53:29.247"],["POST","/goods/goodsSelectHome",4,200,"17:53:29.279","17:53:29.283"],["POST","/goods/goodsSelectHome",3,200,"17:53:29.283","17:53:29.286"],["GET","/goods/goodstype",1,200,"17:53:29.415","17:53:29.416"],["GET","/goods/goodstype",2,200,"17:53:29.430","17:53:29.432"],["POST","/goods/goodsSelectHome",3,200,"17:53:29.448","17:53:29.451"],["POST","/goods/goodsSelectHome",4,200,"17:53:29.491","17:53:29.495"],["GET","/goods/goodstype",1,200,"17:53:29.574","17:53:29.575"],["GET","/goods/goodstype",1,200,"17:53:29.633","17:53:29.634"],["POST","/goods/goodsSelectHome",3,200,"17:53:29.695","17:53:29.698"],["POST","/goods/goodsSelectHome",2,200,"17:53:29.727","17:53:29.729"],["GET","/goods/goodstype",1,200,"17:53:29.836","17:53:29.837"],["GET","/goods/goodstype",3,200,"17:53:29.853","17:53:29.856"],["POST","/goods/goodsSelectHome",2,200,"17:53:29.856","17:53:29.858"],["POST","/goods/goodsSelectHome",2,200,"17:53:29.916","17:53:29.918"],["GET","/goods/goodstype",1,200,"17:53:30.011","17:53:30.012"],["GET","/goods/goodstype",2,200,"17:53:30.043","17:53:30.045"],["POST","/goods/goodsSelectHome",3,200,"17:53:30.074","17:53:30.077"],["POST","/goods/goodsSelectHome",2,200,"17:53:30.089","17:53:30.091"],["GET","/goods/goodstype",1,200,"17:53:30.201","17:53:30.202"],["GET","/goods/goodstype",1,200,"17:53:30.207","17:53:30.208"],["POST","/goods/goodsSelectHome",2,200,"17:53:30.293","17:53:30.295"],["POST","/goods/goodsSelectHome",2,200,"17:53:30.339","17:53:30.341"],["GET","/goods/goodstype",1,200,"17:53:30.431","17:53:30.432"],["POST","/goods/goodsSelectHome",3,200,"17:53:30.463","17:53:30.466"],["GET","/goods/goodstype",1,200,"17:53:30.465","17:53:30.466"],["POST","/goods/goodsSelectHome",2,200,"17:53:30.479","17:53:30.481"],["POST","/goods/goodsSelect",8,200,"17:53:30.511","17:53:30.519"],["GET","/goods/goodstype",1,200,"17:53:30.587","17:53:30.588"],["GET","/goods/goodstype",2,200,"17:53:30.588","17:53:30.590"],["POST","/goods/goodsSelect",7,200,"17:53:30.632","17:53:30.639"],["POST","/goods/goodsSelectHome",1,200,"17:53:30.670","17:53:30.671"],["POST","/goods/goodsSelectHome",2,200,"17:53:30.727","17:53:30.729"],["POST","/goods/goodsSelect",6,200,"17:53:30.790","17:53:30.796"],["GET","/goods/goodstype",0,200,"17:53:30.797","17:53:30.797"],["POST","/goods/goodsSelectHome",2,200,"17:53:30.819","17:53:30.821"],["POST","/goods/goodsSelectHome",2,200,"17:53:30.850","17:53:30.852"],["GET","/goods/goodstype",2,200,"17:53:30.881","17:53:30.883"],["POST","/goods/goodsSelect",8,200,"17:53:30.957","17:53:30.965"],["GET","/goods/goodstype",1,200,"17:53:30.959","17:53:30.960"],["GET","/goods/goodstype",1,200,"17:53:30.973","17:53:30.974"],["POST","/goods/goodsSelect",5,200,"17:53:31.054","17:53:31.059"],["POST","/goods/goodsSelectHome",2,200,"17:53:31.099","17:53:31.101"],["POST","/goods/goodsSelectHome",2,200,"17:53:31.118","17:53:31.120"],["POST","/goods/goodsSelect",6,200,"17:53:31.164","17:53:31.170"],["GET","/goods/goodstype",2,200,"17:53:31.241","17:53:31.243"],["POST","/goods/goodsSelectHome",3,200,"17:53:31.242","17:53:31.245"],["POST","/goods/goodsSelectHome",2,200,"17:53:31.247","17:53:31.249"],["GET","/goods/goodstype",1,200,"17:53:31.248","17:53:31.249"],["POST","/goods/goodsSelect",9,200,"17:53:31.272","17:53:31.281"],["GET","/goods/goodstype",1,200,"17:53:31.357","17:53:31.358"],["GET","/goods/goodstype",1,200,"17:53:31.381","17:53:31.382"],["POST","/goods/goodsSelect",10,200,"17:53:31.397","17:53:31.407"],["POST","/goods/goodsSelectHome",3,200,"17:53:31.506","17:53:31.509"],["POST","/goods/goodsSelect",5,200,"17:53:31.537","17:53:31.542"],["POST","/goods/goodsSelectHome",3,200,"17:53:31.551","17:53:31.554"],["POST","/goods/goodsSelect",9,200,"17:53:31.616","17:53:31.625"],["GET","/goods/goodstype",2,200,"17:53:31.615","17:53:31.617"],["POST","/goods/goodsSelectHome",1,200,"17:53:31.626","17:53:31.627"],["POST","/goods/goodsSelectHome",2,200,"17:53:31.664","17:53:31.666"],["GET","/goods/goodstype",2,200,"17:53:31.710","17:53:31.712"],["GET","/goods/goodstype",1,200,"17:53:31.724","17:53:31.725"],["GET","/goods/goodstype",0,200,"17:53:31.774","17:53:31.774"],["POST","/goods/goodsSelectHome",3,200,"17:53:31.912","17:53:31.915"],["POST","/goods/goodsSelectHome",4,200,"17:53:31.989","17:53:31.993"],["POST","/goods/goodsSelectHome",1,200,"17:53:32.005","17:53:32.006"],["GET","/goods/goodstype",1,200,"17:53:32.008","17:53:32.009"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.020","17:53:32.022"],["GET","/goods/goodstype",2,200,"17:53:32.098","17:53:32.100"],["GET","/goods/goodstype",2,200,"17:53:32.146","17:53:32.148"],["GET","/goods/goodstype",1,200,"17:53:32.147","17:53:32.148"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.272","17:53:32.274"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.379","17:53:32.381"],["GET","/goods/goodstype",1,200,"17:53:32.379","17:53:32.380"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.409","17:53:32.411"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.424","17:53:32.426"],["GET","/goods/goodstype",1,200,"17:53:32.535","17:53:32.536"],["GET","/goods/goodstype",1,200,"17:53:32.536","17:53:32.537"],["GET","/goods/goodstype",1,200,"17:53:32.536","17:53:32.537"],["POST","/goods/goodsSelectHome",1,200,"17:53:32.649","17:53:32.650"],["GET","/goods/goodstype",1,200,"17:53:32.768","17:53:32.769"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.769","17:53:32.771"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.771","17:53:32.773"],["POST","/goods/goodsSelectHome",2,200,"17:53:32.783","17:53:32.785"],["GET","/goods/goodstype",1,200,"17:53:32.875","17:53:32.876"],["GET","/goods/goodstype",1,200,"17:53:32.908","17:53:32.909"],["GET","/goods/goodstype",2,200,"17:53:32.938","17:53:32.940"],["POST","/goods/goodsSelectHome",2,200,"17:53:33.018","17:53:33.020"],["POST","/goods/goodsSelectHome",4,200,"17:53:33.097","17:53:33.101"],["GET","/goods/goodstype",0,200,"17:53:33.126","17:53:33.126"],["POST","/goods/goodsSelectHome",3,200,"17:53:33.221","17:53:33.224"],["POST","/goods/goodsSelectHome",2,200,"17:53:33.224","17:53:33.226"],["GET","/goods/goodstype",1,200,"17:53:33.249","17:53:33.250"],["GET","/goods/goodstype",1,200,"17:53:33.362","17:53:33.363"],["GET","/goods/goodstype",1,200,"17:53:33.376","17:53:33.377"],["POST","/goods/goodsSelectHome",2,200,"17:53:33.410","17:53:33.412"],["GET","/goods/goodstype",0,200,"17:53:33.530","17:53:33.530"],["POST","/goods/goodsSelectHome",1,200,"17:53:33.578","17:53:33.579"],["POST","/goods/goodsSelectHome",2,200,"17:53:33.625","17:53:33.627"],["POST","/goods/goodsSelectHome",2,200,"17:53:33.640","17:53:33.642"]],
  /* 落点 6 标在第几行（⑤ 默认选中的那一号所在组的第一行） */
  anim6: 1041,
  sel: { tr: 541, traceId: "19d78a4e-46d6-4dd1-acd2-efa53c7bcbe7" },
  /* 重放切段：cuts[t] = 「拍序 ≤ t」的行数（每拍喂真帧那一拍的条数）*/
  cuts: [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,79,149,187,223,244,297,329,426,520,542,589,628,648,671,814,920,943,962,1025,1062,1085,1090],
  /* 重放起跳拍：前 38 拍本窗 0 条 ⇒ 从第 39 拍起喂 */
  replayHead: 38,
  /* ③ 的行清单：[前导符, 时刻, 严重度(i/w/e), 类下标(-1=req), 标题, 对象, 明细, trace下标, 拍序] */
  rows: [
    ["├─","17:53:11.692","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1483",0,38],
    ["├─","17:53:11.693","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-3","lock=order-add result=acquired key=B7CAP-sgu0g-r7-3 src=lock#227",1,38],
    ["├─","17:53:11.693","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-4","lock=order-add result=acquired key=B7CAP-sgu0g-r7-4 src=lock#228",2,38],
    ["├─","17:53:11.693","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-2","lock=order-add result=acquired key=B7CAP-sgu0g-r7-2 src=lock#229",3,38],
    ["├─","17:53:11.693","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-7","lock=order-add result=acquired key=B7CAP-sgu0g-r7-7 src=lock#230",4,38],
    ["├─","17:53:11.694","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-0","lock=order-add result=acquired key=B7CAP-sgu0g-r7-0 src=lock#231",5,38],
    ["├─","17:53:11.694","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-8","lock=order-add result=acquired key=B7CAP-sgu0g-r7-8 src=lock#232",6,38],
    ["├─","17:53:11.696","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-5","lock=order-add result=acquired key=B7CAP-sgu0g-r7-5 src=lock#233",7,38],
    ["├─","17:53:11.696","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-10","lock=order-add result=acquired key=B7CAP-sgu0g-r7-10 src=lock#234",8,38],
    ["├─","17:53:11.696","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-9","lock=order-add result=acquired key=B7CAP-sgu0g-r7-9 src=lock#235",9,38],
    ["├─","17:53:11.697","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-11","lock=order-add result=acquired key=B7CAP-sgu0g-r7-11 src=lock#236",10,38],
    ["├─","17:53:11.703","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-6","lock=order-add result=acquired key=B7CAP-sgu0g-r7-6 src=lock#237",11,38],
    ["├─","17:53:11.709","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1484",12,38],
    ["├─","17:53:11.715","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-1","action=create result=applied order#B7CAP-sgu0g-r7-1 src=order#113",13,38],
    ["├─","17:53:11.717","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-1","lock=order-add result=released key=B7CAP-sgu0g-r7-1 src=lock#238",13,38],
    ["├─","17:53:11.719","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-2","action=create result=applied order#B7CAP-sgu0g-r7-2 src=order#114",3,38],
    ["├─","17:53:11.719","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-8","action=create result=applied order#B7CAP-sgu0g-r7-8 src=order#115",6,38],
    ["├─","17:53:11.719","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-10","action=create result=applied order#B7CAP-sgu0g-r7-10 src=order#116",8,38],
    ["├─","17:53:11.720","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-7","action=create result=applied order#B7CAP-sgu0g-r7-7 src=order#117",4,38],
    ["├─","17:53:11.720","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-0","action=create result=applied order#B7CAP-sgu0g-r7-0 src=order#118",5,38],
    ["├─","17:53:11.720","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-4","action=create result=applied order#B7CAP-sgu0g-r7-4 src=order#119",2,38],
    ["├─","17:53:11.722","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-0","lock=order-add result=released key=B7CAP-sgu0g-r7-0 src=lock#239",5,38],
    ["├─","17:53:11.722","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-8","lock=order-add result=released key=B7CAP-sgu0g-r7-8 src=lock#240",6,38],
    ["├─","17:53:11.722","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-4","lock=order-add result=released key=B7CAP-sgu0g-r7-4 src=lock#241",2,38],
    ["├─","17:53:11.723","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-7","lock=order-add result=released key=B7CAP-sgu0g-r7-7 src=lock#242",4,38],
    ["├─","17:53:11.723","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-10","lock=order-add result=released key=B7CAP-sgu0g-r7-10 src=lock#243",8,38],
    ["├─","17:53:11.723","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-9","action=create result=applied order#B7CAP-sgu0g-r7-9 src=order#120",9,38],
    ["├─","17:53:11.723","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-5","action=create result=applied order#B7CAP-sgu0g-r7-5 src=order#121",7,38],
    ["├─","17:53:11.724","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-3","action=create result=applied order#B7CAP-sgu0g-r7-3 src=order#122",1,38],
    ["├─","17:53:11.725","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-2","lock=order-add result=released key=B7CAP-sgu0g-r7-2 src=lock#244",3,38],
    ["├─","17:53:11.726","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-5","lock=order-add result=released key=B7CAP-sgu0g-r7-5 src=lock#245",7,38],
    ["├─","17:53:11.726","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-9","lock=order-add result=released key=B7CAP-sgu0g-r7-9 src=lock#246",9,38],
    ["├─","17:53:11.727","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-3","lock=order-add result=released key=B7CAP-sgu0g-r7-3 src=lock#247",1,38],
    ["├─","17:53:11.727","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-6","action=create result=applied order#B7CAP-sgu0g-r7-6 src=order#123",11,38],
    ["├─","17:53:11.727","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r7-11","action=create result=applied order#B7CAP-sgu0g-r7-11 src=order#124",10,38],
    ["├─","17:53:11.729","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-6","lock=order-add result=released key=B7CAP-sgu0g-r7-6 src=lock#248",11,38],
    ["├─","17:53:11.729","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-11","lock=order-add result=released key=B7CAP-sgu0g-r7-11 src=lock#249",10,38],
    ["├─","17:53:11.755","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1485",14,38],
    ["├─","17:53:11.770","i",0,"L1 未命中","键 B7CAPS-sgu0g-146","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-146 src=cache#1486",15,38],
    ["├─","17:53:11.770","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-146 src=identity#271",15,38],
    ["├─","17:53:11.772","i",0,"L2 未命中","键 B7CAPS-sgu0g-146","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-146 src=cache#1487",15,38],
    ["├─","17:53:11.772","i",0,"回源查库","键 B7CAPS-sgu0g-146","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-146 src=cache#1488",15,38],
    ["├─","17:53:11.819","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1489",16,38],
    ["├─","17:53:11.866","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1490",17,38],
    ["├─","17:53:11.896","i",0,"L1 未命中","键 B7CAPS-sgu0g-147","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-147 src=cache#1491",18,38],
    ["├─","17:53:11.896","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-147 src=identity#272",18,38],
    ["├─","17:53:11.899","i",0,"L2 未命中","键 B7CAPS-sgu0g-147","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-147 src=cache#1492",18,38],
    ["├─","17:53:11.899","i",0,"回源查库","键 B7CAPS-sgu0g-147","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-147 src=cache#1493",18,38],
    ["├─","17:53:11.943","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1494",19,38],
    ["├─","17:53:11.956","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1495",20,38],
    ["├─","17:53:11.988","i",0,"L1 未命中","键 B7CAPS-sgu0g-148","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-148 src=cache#1496",21,38],
    ["├─","17:53:11.988","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-148 src=identity#273",21,38],
    ["├─","17:53:11.989","i",0,"L2 未命中","键 B7CAPS-sgu0g-148","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-148 src=cache#1497",21,38],
    ["├─","17:53:11.989","i",0,"回源查库","键 B7CAPS-sgu0g-148","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-148 src=cache#1498",21,38],
    ["├─","17:53:12.006","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1499",22,38],
    ["├─","17:53:12.084","i",0,"L1 未命中","键 B7CAPS-sgu0g-149","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-149 src=cache#1500",23,38],
    ["├─","17:53:12.084","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-149 src=identity#274",23,38],
    ["├─","17:53:12.089","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1501",24,38],
    ["├─","17:53:12.089","i",0,"L2 未命中","键 B7CAPS-sgu0g-149","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-149 src=cache#1502",23,38],
    ["├─","17:53:12.089","i",0,"回源查库","键 B7CAPS-sgu0g-149","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-149 src=cache#1503",23,38],
    ["├─","17:53:12.114","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1504",25,38],
    ["├─","17:53:12.116","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1505",26,38],
    ["├─","17:53:12.194","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1506",27,38],
    ["├─","17:53:12.196","i",0,"L1 未命中","键 B7CAPS-sgu0g-150","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-150 src=cache#1507",28,38],
    ["├─","17:53:12.196","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-150 src=identity#275",28,38],
    ["├─","17:53:12.198","i",0,"L2 未命中","键 B7CAPS-sgu0g-150","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-150 src=cache#1508",28,38],
    ["├─","17:53:12.198","i",0,"回源查库","键 B7CAPS-sgu0g-150","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-150 src=cache#1509",28,38],
    ["├─","17:53:12.236","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1510",29,38],
    ["├─","17:53:12.300","i",0,"L1 未命中","键 B7CAPS-sgu0g-151","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-151 src=cache#1511",30,38],
    ["├─","17:53:12.300","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-151 src=identity#276",30,38],
    ["├─","17:53:12.302","i",0,"L2 未命中","键 B7CAPS-sgu0g-151","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-151 src=cache#1512",30,38],
    ["├─","17:53:12.302","i",0,"回源查库","键 B7CAPS-sgu0g-151","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-151 src=cache#1513",30,38],
    ["├─","17:53:12.309","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1514",31,38],
    ["├─","17:53:12.348","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1515",32,38],
    ["├─","17:53:12.379","i",0,"L1 未命中","键 B7CAPS-sgu0g-152","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-152 src=cache#1516",33,38],
    ["├─","17:53:12.379","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-152 src=identity#277",33,38],
    ["├─","17:53:12.383","i",0,"L2 未命中","键 B7CAPS-sgu0g-152","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-152 src=cache#1517",33,38],
    ["├─","17:53:12.383","i",0,"回源查库","键 B7CAPS-sgu0g-152","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-152 src=cache#1518",33,38],
    ["├─","17:53:12.389","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1519",34,38],
    ["├─","17:53:12.455","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1520",35,39],
    ["├─","17:53:12.486","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1521",36,39],
    ["├─","17:53:12.532","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1522",37,39],
    ["├─","17:53:12.564","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1523",38,39],
    ["├─","17:53:12.612","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1524",39,39],
    ["├─","17:53:12.676","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1525",40,39],
    ["├─","17:53:12.769","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1526",41,39],
    ["├─","17:53:12.820","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1527",42,39],
    ["├─","17:53:12.878","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1528",43,39],
    ["├─","17:53:12.925","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1529",44,39],
    ["├─","17:53:12.925","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1530",45,39],
    ["├─","17:53:12.947","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#278",46,39],
    ["├─","17:53:12.948","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#279",47,39],
    ["├─","17:53:12.949","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-0","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-0 src=lock#250",47,39],
    ["├─","17:53:12.949","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#280",48,39],
    ["├─","17:53:12.950","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-2","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-2 src=lock#251",46,39],
    ["├─","17:53:12.950","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#281",49,39],
    ["├─","17:53:12.951","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-6","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-6 src=lock#252",48,39],
    ["├─","17:53:12.951","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#282",50,39],
    ["├─","17:53:12.955","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-5","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-5 src=lock#253",49,39],
    ["├─","17:53:12.955","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#283",51,39],
    ["├─","17:53:12.955","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#284",52,39],
    ["├─","17:53:12.966","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#285",53,39],
    ["├─","17:53:12.966","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#286",54,39],
    ["├─","17:53:12.967","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-9","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-9 src=lock#254",51,39],
    ["├─","17:53:12.967","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-11","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-11 src=lock#255",52,39],
    ["├─","17:53:12.967","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-10","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-10 src=lock#256",50,39],
    ["├─","17:53:12.968","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-4","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-4 src=lock#257",54,39],
    ["├─","17:53:12.969","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-1","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-1 src=lock#258",53,39],
    ["├─","17:53:12.972","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1531",55,39],
    ["├─","17:53:12.975","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#287",56,39],
    ["├─","17:53:12.975","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#288",57,39],
    ["├─","17:53:12.976","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#289",58,39],
    ["├─","17:53:12.977","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-8","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-8 src=lock#259",58,39],
    ["├─","17:53:12.978","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-7","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-7 src=lock#260",57,39],
    ["├─","17:53:12.979","i",1,"取锁成功","锁键 B7CAP-sgu0g-r7-3","lock=order-pay result=acquired key=B7CAP-sgu0g-r7-3 src=lock#261",56,39],
    ["├─","17:53:13.002","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1532",59,39],
    ["├─","17:53:13.011","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-1","action=pay result=applied order#B7CAP-sgu0g-r7-1 src=order#125",53,39],
    ["├─","17:53:13.012","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-0","action=pay result=applied order#B7CAP-sgu0g-r7-0 src=order#126",47,39],
    ["├─","17:53:13.014","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-1","lock=order-pay result=released key=B7CAP-sgu0g-r7-1 src=lock#262",53,39],
    ["├─","17:53:13.015","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-0","lock=order-pay result=released key=B7CAP-sgu0g-r7-0 src=lock#263",47,39],
    ["├─","17:53:13.015","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-8","action=pay result=applied order#B7CAP-sgu0g-r7-8 src=order#127",58,39],
    ["├─","17:53:13.016","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-10","action=pay result=applied order#B7CAP-sgu0g-r7-10 src=order#128",50,39],
    ["├─","17:53:13.016","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-2","action=pay result=applied order#B7CAP-sgu0g-r7-2 src=order#129",46,39],
    ["├─","17:53:13.016","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-11","action=pay result=applied order#B7CAP-sgu0g-r7-11 src=order#130",52,39],
    ["├─","17:53:13.018","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-8","lock=order-pay result=released key=B7CAP-sgu0g-r7-8 src=lock#264",58,39],
    ["├─","17:53:13.018","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-4","action=pay result=applied order#B7CAP-sgu0g-r7-4 src=order#131",54,39],
    ["├─","17:53:13.018","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-5","action=pay result=applied order#B7CAP-sgu0g-r7-5 src=order#132",49,39],
    ["├─","17:53:13.019","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-9","action=pay result=applied order#B7CAP-sgu0g-r7-9 src=order#133",51,39],
    ["├─","17:53:13.020","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-10","lock=order-pay result=released key=B7CAP-sgu0g-r7-10 src=lock#265",50,39],
    ["├─","17:53:13.020","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-11","lock=order-pay result=released key=B7CAP-sgu0g-r7-11 src=lock#266",52,39],
    ["├─","17:53:13.020","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-2","lock=order-pay result=released key=B7CAP-sgu0g-r7-2 src=lock#267",46,39],
    ["├─","17:53:13.021","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-4","lock=order-pay result=released key=B7CAP-sgu0g-r7-4 src=lock#268",54,39],
    ["├─","17:53:13.021","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-5","lock=order-pay result=released key=B7CAP-sgu0g-r7-5 src=lock#269",49,39],
    ["├─","17:53:13.021","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-3","action=pay result=applied order#B7CAP-sgu0g-r7-3 src=order#134",56,39],
    ["├─","17:53:13.022","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-9","lock=order-pay result=released key=B7CAP-sgu0g-r7-9 src=lock#270",51,39],
    ["├─","17:53:13.023","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-3","lock=order-pay result=released key=B7CAP-sgu0g-r7-3 src=lock#271",56,39],
    ["├─","17:53:13.027","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-6","action=pay result=applied order#B7CAP-sgu0g-r7-6 src=order#135",48,39],
    ["├─","17:53:13.032","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r7-7","action=pay result=applied order#B7CAP-sgu0g-r7-7 src=order#136",57,39],
    ["├─","17:53:13.034","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-6","lock=order-pay result=released key=B7CAP-sgu0g-r7-6 src=lock#272",48,39],
    ["├─","17:53:13.035","i",1,"释放锁","锁键 B7CAP-sgu0g-r7-7","lock=order-pay result=released key=B7CAP-sgu0g-r7-7 src=lock#273",57,39],
    ["├─","17:53:13.081","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1533",60,39],
    ["├─","17:53:13.178","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1534",61,39],
    ["├─","17:53:13.191","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1535",62,39],
    ["├─","17:53:13.284","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1536",63,39],
    ["├─","17:53:13.300","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1537",64,39],
    ["├─","17:53:13.316","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1538",65,39],
    ["├─","17:53:13.348","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1539",66,39],
    ["├─","17:53:13.427","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1540",67,39],
    ["├─","17:53:13.471","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1541",68,39],
    ["├─","17:53:13.563","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1542",69,40],
    ["├─","17:53:13.598","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1543",70,40],
    ["├─","17:53:13.690","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1544",71,40],
    ["├─","17:53:13.690","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1545",72,40],
    ["├─","17:53:13.753","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1546",73,40],
    ["├─","17:53:13.754","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1547",74,40],
    ["├─","17:53:13.831","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1548",75,40],
    ["├─","17:53:13.877","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1549",76,40],
    ["├─","17:53:13.909","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1550",77,40],
    ["├─","17:53:13.985","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1551",78,40],
    ["├─","17:53:14.037","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1552",79,40],
    ["├─","17:53:14.098","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1553",80,40],
    ["├─","17:53:14.111","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1554",81,40],
    ["├─","17:53:14.175","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1555",82,40],
    ["├─","17:53:14.222","i",0,"L1 未命中","键 B7CAPS-sgu0g-153","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-153 src=cache#1556",83,40],
    ["├─","17:53:14.222","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-153 src=identity#290",83,40],
    ["├─","17:53:14.224","i",0,"L2 未命中","键 B7CAPS-sgu0g-153","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-153 src=cache#1557",83,40],
    ["├─","17:53:14.224","i",0,"回源查库","键 B7CAPS-sgu0g-153","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-153 src=cache#1558",83,40],
    ["├─","17:53:14.234","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1559",84,40],
    ["├─","17:53:14.314","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1560",85,40],
    ["├─","17:53:14.331","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1561",86,40],
    ["├─","17:53:14.361","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-154 src=identity#291",87,40],
    ["├─","17:53:14.362","i",0,"L1 未命中","键 B7CAPS-sgu0g-154","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-154 src=cache#1562",87,40],
    ["├─","17:53:14.364","i",0,"L2 未命中","键 B7CAPS-sgu0g-154","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-154 src=cache#1563",87,40],
    ["├─","17:53:14.364","i",0,"回源查库","键 B7CAPS-sgu0g-154","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-154 src=cache#1564",87,40],
    ["├─","17:53:14.422","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1565",88,40],
    ["├─","17:53:14.483","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1566",89,40],
    ["├─","17:53:14.485","i",0,"L1 未命中","键 B7CAPS-sgu0g-155","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-155 src=cache#1567",90,40],
    ["├─","17:53:14.485","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-155 src=identity#292",90,40],
    ["├─","17:53:14.486","i",0,"L2 未命中","键 B7CAPS-sgu0g-155","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-155 src=cache#1568",90,40],
    ["├─","17:53:14.486","i",0,"回源查库","键 B7CAPS-sgu0g-155","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-155 src=cache#1569",90,40],
    ["├─","17:53:14.531","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1570",91,40],
    ["├─","17:53:14.547","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1571",92,40],
    ["├─","17:53:14.548","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1572",93,40],
    ["├─","17:53:14.609","i",0,"L1 未命中","键 B7CAPS-sgu0g-156","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-156 src=cache#1573",94,40],
    ["├─","17:53:14.609","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-156 src=identity#293",94,40],
    ["├─","17:53:14.611","i",0,"L2 未命中","键 B7CAPS-sgu0g-156","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-156 src=cache#1574",94,40],
    ["├─","17:53:14.611","i",0,"回源查库","键 B7CAPS-sgu0g-156","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-156 src=cache#1575",94,40],
    ["├─","17:53:14.654","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1576",95,41],
    ["├─","17:53:14.687","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1577",96,41],
    ["├─","17:53:14.732","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1578",97,41],
    ["├─","17:53:14.764","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-157 src=identity#294",98,41],
    ["├─","17:53:14.765","i",0,"L1 未命中","键 B7CAPS-sgu0g-157","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-157 src=cache#1579",98,41],
    ["├─","17:53:14.765","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1580",99,41],
    ["├─","17:53:14.768","i",0,"L2 未命中","键 B7CAPS-sgu0g-157","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-157 src=cache#1581",98,41],
    ["├─","17:53:14.768","i",0,"回源查库","键 B7CAPS-sgu0g-157","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-157 src=cache#1582",98,41],
    ["├─","17:53:14.838","i",0,"L1 未命中","键 B7CAPS-sgu0g-158","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-158 src=cache#1583",100,41],
    ["├─","17:53:14.838","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-158 src=identity#295",100,41],
    ["├─","17:53:14.840","i",0,"L2 未命中","键 B7CAPS-sgu0g-158","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-158 src=cache#1584",100,41],
    ["├─","17:53:14.840","i",0,"回源查库","键 B7CAPS-sgu0g-158","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-158 src=cache#1585",100,41],
    ["├─","17:53:14.856","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1586",101,41],
    ["├─","17:53:14.924","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1587",102,41],
    ["├─","17:53:14.924","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1588",103,41],
    ["├─","17:53:14.934","i",0,"L1 未命中","键 B7CAPS-sgu0g-159","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-159 src=cache#1589",104,41],
    ["├─","17:53:14.934","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-159 src=identity#296",104,41],
    ["├─","17:53:14.936","i",0,"L2 未命中","键 B7CAPS-sgu0g-159","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-159 src=cache#1590",104,41],
    ["├─","17:53:14.936","i",0,"回源查库","键 B7CAPS-sgu0g-159","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-159 src=cache#1591",104,41],
    ["├─","17:53:14.983","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1592",105,41],
    ["├─","17:53:15.030","i",0,"L1 未命中","键 B7CAPS-sgu0g-160","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-160 src=cache#1593",106,41],
    ["├─","17:53:15.030","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-160 src=identity#297",106,41],
    ["├─","17:53:15.034","i",0,"L2 未命中","键 B7CAPS-sgu0g-160","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-160 src=cache#1594",106,41],
    ["├─","17:53:15.034","i",0,"回源查库","键 B7CAPS-sgu0g-160","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-160 src=cache#1595",106,41],
    ["├─","17:53:15.039","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1596",107,41],
    ["├─","17:53:15.121","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1597",108,41],
    ["├─","17:53:15.153","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1598",109,41],
    ["├─","17:53:15.173","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1599",110,41],
    ["├─","17:53:15.284","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1600",111,41],
    ["├─","17:53:15.306","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1601",112,41],
    ["├─","17:53:15.306","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1602",113,41],
    ["├─","17:53:15.421","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1603",114,41],
    ["├─","17:53:15.445","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1604",115,41],
    ["├─","17:53:15.539","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1605",116,41],
    ["├─","17:53:15.554","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1606",117,41],
    ["├─","17:53:15.571","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1607",118,41],
    ["├─","17:53:15.665","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1608",119,42],
    ["├─","17:53:15.678","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1609",120,42],
    ["├─","17:53:15.756","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1610",121,42],
    ["├─","17:53:15.851","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1611",122,42],
    ["├─","17:53:15.882","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1612",123,42],
    ["├─","17:53:15.912","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1613",124,42],
    ["├─","17:53:15.944","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1614",125,42],
    ["├─","17:53:15.976","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1615",126,42],
    ["├─","17:53:16.038","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1616",127,42],
    ["├─","17:53:16.069","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1617",128,42],
    ["├─","17:53:16.197","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1618",129,42],
    ["├─","17:53:16.241","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1619",130,42],
    ["├─","17:53:16.242","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1620",131,42],
    ["├─","17:53:16.335","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1621",132,42],
    ["├─","17:53:16.350","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1622",133,42],
    ["├─","17:53:16.366","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1623",134,42],
    ["├─","17:53:16.382","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1624",135,42],
    ["├─","17:53:16.521","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1625",136,42],
    ["├─","17:53:16.569","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1626",137,42],
    ["├─","17:53:16.650","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1627",138,42],
    ["├─","17:53:16.653","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1628",139,42],
    ["├─","17:53:16.727","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1629",140,43],
    ["├─","17:53:16.773","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1630",141,43],
    ["├─","17:53:16.804","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1631",142,43],
    ["├─","17:53:16.805","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1632",143,43],
    ["├─","17:53:16.901","i",0,"L1 未命中","键 B7CAPS-sgu0g-161","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-161 src=cache#1633",144,43],
    ["├─","17:53:16.901","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-161 src=identity#298",144,43],
    ["├─","17:53:16.903","i",0,"L2 未命中","键 B7CAPS-sgu0g-161","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-161 src=cache#1634",144,43],
    ["├─","17:53:16.903","i",0,"回源查库","键 B7CAPS-sgu0g-161","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-161 src=cache#1635",144,43],
    ["├─","17:53:16.931","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1636",145,43],
    ["├─","17:53:16.994","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1637",146,43],
    ["├─","17:53:17.009","i",0,"L1 未命中","键 B7CAPS-sgu0g-162","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-162 src=cache#1638",147,43],
    ["├─","17:53:17.009","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-162 src=identity#299",147,43],
    ["├─","17:53:17.011","i",0,"L2 未命中","键 B7CAPS-sgu0g-162","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-162 src=cache#1639",147,43],
    ["├─","17:53:17.011","i",0,"回源查库","键 B7CAPS-sgu0g-162","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-162 src=cache#1640",147,43],
    ["├─","17:53:17.024","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1641",148,43],
    ["├─","17:53:17.079","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1642",149,43],
    ["├─","17:53:17.104","i",0,"L1 未命中","键 B7CAPS-sgu0g-163","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-163 src=cache#1643",150,43],
    ["├─","17:53:17.104","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-163 src=identity#300",150,43],
    ["├─","17:53:17.105","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1644",151,43],
    ["├─","17:53:17.107","i",0,"L2 未命中","键 B7CAPS-sgu0g-163","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-163 src=cache#1645",150,43],
    ["├─","17:53:17.107","i",0,"回源查库","键 B7CAPS-sgu0g-163","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-163 src=cache#1646",150,43],
    ["├─","17:53:17.148","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1647",152,43],
    ["├─","17:53:17.179","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1648",153,43],
    ["├─","17:53:17.242","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1649",154,43],
    ["├─","17:53:17.243","i",0,"L1 未命中","键 B7CAPS-sgu0g-164","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-164 src=cache#1650",155,43],
    ["├─","17:53:17.243","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-164 src=identity#301",155,43],
    ["├─","17:53:17.244","i",0,"L2 未命中","键 B7CAPS-sgu0g-164","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-164 src=cache#1651",155,43],
    ["├─","17:53:17.244","i",0,"回源查库","键 B7CAPS-sgu0g-164","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-164 src=cache#1652",155,43],
    ["├─","17:53:17.335","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1653",156,43],
    ["├─","17:53:17.336","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1654",157,43],
    ["├─","17:53:17.350","i",0,"L1 未命中","键 B7CAPS-sgu0g-165","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-165 src=cache#1655",158,43],
    ["├─","17:53:17.350","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-165 src=identity#302",158,43],
    ["├─","17:53:17.352","i",0,"L2 未命中","键 B7CAPS-sgu0g-165","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-165 src=cache#1656",158,43],
    ["├─","17:53:17.352","i",0,"回源查库","键 B7CAPS-sgu0g-165","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-165 src=cache#1657",158,43],
    ["├─","17:53:17.429","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1658",159,43],
    ["├─","17:53:17.462","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1659",160,43],
    ["├─","17:53:17.465","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1660",161,43],
    ["├─","17:53:17.474","i",0,"L1 未命中","键 B7CAPS-sgu0g-166","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-166 src=cache#1661",162,43],
    ["├─","17:53:17.474","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-166 src=identity#303",162,43],
    ["├─","17:53:17.476","i",0,"L2 未命中","键 B7CAPS-sgu0g-166","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-166 src=cache#1662",162,43],
    ["├─","17:53:17.476","i",0,"回源查库","键 B7CAPS-sgu0g-166","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-166 src=cache#1663",162,43],
    ["├─","17:53:17.571","i",0,"L1 未命中","键 B7CAPS-sgu0g-167","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-167 src=cache#1664",163,43],
    ["├─","17:53:17.571","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1665",164,43],
    ["├─","17:53:17.571","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-167 src=identity#304",163,43],
    ["├─","17:53:17.573","i",0,"L2 未命中","键 B7CAPS-sgu0g-167","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-167 src=cache#1666",163,43],
    ["├─","17:53:17.573","i",0,"回源查库","键 B7CAPS-sgu0g-167","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-167 src=cache#1667",163,43],
    ["├─","17:53:17.599","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1668",165,43],
    ["├─","17:53:17.632","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1669",166,43],
    ["├─","17:53:17.679","i",0,"L1 未命中","键 B7CAPS-sgu0g-168","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-168 src=cache#1670",167,43],
    ["├─","17:53:17.679","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-168 src=identity#305",167,43],
    ["├─","17:53:17.681","i",0,"L2 未命中","键 B7CAPS-sgu0g-168","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-168 src=cache#1671",167,43],
    ["├─","17:53:17.681","i",0,"回源查库","键 B7CAPS-sgu0g-168","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-168 src=cache#1672",167,43],
    ["├─","17:53:17.682","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1673",168,43],
    ["├─","17:53:17.769","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1674",169,44],
    ["├─","17:53:17.785","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-169 src=identity#306",170,44],
    ["├─","17:53:17.786","i",0,"L1 未命中","键 B7CAPS-sgu0g-169","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-169 src=cache#1675",170,44],
    ["├─","17:53:17.787","i",0,"L2 未命中","键 B7CAPS-sgu0g-169","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-169 src=cache#1676",170,44],
    ["├─","17:53:17.787","i",0,"回源查库","键 B7CAPS-sgu0g-169","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-169 src=cache#1677",170,44],
    ["├─","17:53:17.818","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1678",171,44],
    ["├─","17:53:17.867","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1679",172,44],
    ["├─","17:53:17.880","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1680",173,44],
    ["├─","17:53:17.913","i",0,"L1 未命中","键 B7CAPS-sgu0g-170","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-170 src=cache#1681",174,44],
    ["├─","17:53:17.913","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-170 src=identity#307",174,44],
    ["├─","17:53:17.915","i",0,"L2 未命中","键 B7CAPS-sgu0g-170","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-170 src=cache#1682",174,44],
    ["├─","17:53:17.915","i",0,"回源查库","键 B7CAPS-sgu0g-170","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-170 src=cache#1683",174,44],
    ["├─","17:53:17.988","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1684",175,44],
    ["├─","17:53:18.006","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1685",176,44],
    ["├─","17:53:18.015","i",0,"L1 未命中","键 B7CAPS-sgu0g-171","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-171 src=cache#1686",177,44],
    ["├─","17:53:18.015","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1687",178,44],
    ["├─","17:53:18.015","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-171 src=identity#308",177,44],
    ["├─","17:53:18.018","i",0,"L2 未命中","键 B7CAPS-sgu0g-171","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-171 src=cache#1688",177,44],
    ["├─","17:53:18.018","i",0,"回源查库","键 B7CAPS-sgu0g-171","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-171 src=cache#1689",177,44],
    ["├─","17:53:18.090","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1690",179,44],
    ["├─","17:53:18.116","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1691",180,44],
    ["├─","17:53:18.229","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1692",181,44],
    ["├─","17:53:18.230","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1693",182,44],
    ["├─","17:53:18.288","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1694",183,44],
    ["├─","17:53:18.358","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1695",184,44],
    ["├─","17:53:18.381","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1696",185,44],
    ["├─","17:53:18.427","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1697",186,44],
    ["├─","17:53:18.489","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1698",187,44],
    ["├─","17:53:18.502","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1699",188,44],
    ["├─","17:53:18.613","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1700",189,44],
    ["├─","17:53:18.628","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1701",190,44],
    ["├─","17:53:18.706","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1702",191,44],
    ["├─","17:53:18.767","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1703",192,45],
    ["├─","17:53:18.784","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1704",193,45],
    ["├─","17:53:18.862","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1705",194,45],
    ["├─","17:53:18.911","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1706",195,45],
    ["├─","17:53:18.911","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1707",196,45],
    ["├─","17:53:19.019","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1708",197,45],
    ["├─","17:53:19.044","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#309",198,45],
    ["├─","17:53:19.045","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#310",199,45],
    ["├─","17:53:19.045","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#311",200,45],
    ["├─","17:53:19.045","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#312",201,45],
    ["├─","17:53:19.045","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#313",202,45],
    ["├─","17:53:19.045","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#314",203,45],
    ["├─","17:53:19.045","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#315",204,45],
    ["├─","17:53:19.046","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#316",205,45],
    ["├─","17:53:19.046","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#317",206,45],
    ["├─","17:53:19.046","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#318",207,45],
    ["├─","17:53:19.047","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#319",208,45],
    ["├─","17:53:19.047","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#320",209,45],
    ["├─","17:53:19.049","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-0","lock=order-add result=acquired key=B7CAP-sgu0g-r8-0 src=lock#274",198,45],
    ["├─","17:53:19.049","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-1","lock=order-add result=acquired key=B7CAP-sgu0g-r8-1 src=lock#275",199,45],
    ["├─","17:53:19.050","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-3","lock=order-add result=acquired key=B7CAP-sgu0g-r8-3 src=lock#276",201,45],
    ["├─","17:53:19.051","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-2","lock=order-add result=acquired key=B7CAP-sgu0g-r8-2 src=lock#277",200,45],
    ["├─","17:53:19.051","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-4","lock=order-add result=acquired key=B7CAP-sgu0g-r8-4 src=lock#278",202,45],
    ["├─","17:53:19.053","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-10","lock=order-add result=acquired key=B7CAP-sgu0g-r8-10 src=lock#279",208,45],
    ["├─","17:53:19.053","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-6","lock=order-add result=acquired key=B7CAP-sgu0g-r8-6 src=lock#280",204,45],
    ["├─","17:53:19.053","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-11","lock=order-add result=acquired key=B7CAP-sgu0g-r8-11 src=lock#281",209,45],
    ["├─","17:53:19.054","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-5","lock=order-add result=acquired key=B7CAP-sgu0g-r8-5 src=lock#282",203,45],
    ["├─","17:53:19.055","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-8","lock=order-add result=acquired key=B7CAP-sgu0g-r8-8 src=lock#283",206,45],
    ["├─","17:53:19.058","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-9","lock=order-add result=acquired key=B7CAP-sgu0g-r8-9 src=lock#284",207,45],
    ["├─","17:53:19.060","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-7","lock=order-add result=acquired key=B7CAP-sgu0g-r8-7 src=lock#285",205,45],
    ["├─","17:53:19.064","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1709",210,45],
    ["├─","17:53:19.066","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-2","action=create result=applied order#B7CAP-sgu0g-r8-2 src=order#137",200,45],
    ["├─","17:53:19.069","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-2","lock=order-add result=released key=B7CAP-sgu0g-r8-2 src=lock#286",200,45],
    ["├─","17:53:19.070","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-5","action=create result=applied order#B7CAP-sgu0g-r8-5 src=order#138",203,45],
    ["├─","17:53:19.070","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-4","action=create result=applied order#B7CAP-sgu0g-r8-4 src=order#139",202,45],
    ["├─","17:53:19.070","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-1","action=create result=applied order#B7CAP-sgu0g-r8-1 src=order#140",199,45],
    ["├─","17:53:19.070","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-3","action=create result=applied order#B7CAP-sgu0g-r8-3 src=order#141",201,45],
    ["├─","17:53:19.070","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-0","action=create result=applied order#B7CAP-sgu0g-r8-0 src=order#142",198,45],
    ["├─","17:53:19.070","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-6","action=create result=applied order#B7CAP-sgu0g-r8-6 src=order#143",204,45],
    ["├─","17:53:19.070","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-10","action=create result=applied order#B7CAP-sgu0g-r8-10 src=order#144",208,45],
    ["├─","17:53:19.074","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-5","lock=order-add result=released key=B7CAP-sgu0g-r8-5 src=lock#287",203,45],
    ["├─","17:53:19.074","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-4","lock=order-add result=released key=B7CAP-sgu0g-r8-4 src=lock#288",202,45],
    ["├─","17:53:19.074","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-11","action=create result=applied order#B7CAP-sgu0g-r8-11 src=order#145",209,45],
    ["├─","17:53:19.075","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-6","lock=order-add result=released key=B7CAP-sgu0g-r8-6 src=lock#289",204,45],
    ["├─","17:53:19.076","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-3","lock=order-add result=released key=B7CAP-sgu0g-r8-3 src=lock#290",201,45],
    ["├─","17:53:19.077","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1710",211,45],
    ["├─","17:53:19.078","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-10","lock=order-add result=released key=B7CAP-sgu0g-r8-10 src=lock#291",208,45],
    ["├─","17:53:19.078","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-1","lock=order-add result=released key=B7CAP-sgu0g-r8-1 src=lock#292",199,45],
    ["├─","17:53:19.078","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-0","lock=order-add result=released key=B7CAP-sgu0g-r8-0 src=lock#293",198,45],
    ["├─","17:53:19.081","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-11","lock=order-add result=released key=B7CAP-sgu0g-r8-11 src=lock#294",209,45],
    ["├─","17:53:19.081","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-8","action=create result=applied order#B7CAP-sgu0g-r8-8 src=order#146",206,45],
    ["├─","17:53:19.082","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-9","action=create result=applied order#B7CAP-sgu0g-r8-9 src=order#147",207,45],
    ["├─","17:53:19.082","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r8-7","action=create result=applied order#B7CAP-sgu0g-r8-7 src=order#148",205,45],
    ["├─","17:53:19.084","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-7","lock=order-add result=released key=B7CAP-sgu0g-r8-7 src=lock#295",205,45],
    ["├─","17:53:19.084","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-8","lock=order-add result=released key=B7CAP-sgu0g-r8-8 src=lock#296",206,45],
    ["├─","17:53:19.084","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-9","lock=order-add result=released key=B7CAP-sgu0g-r8-9 src=lock#297",207,45],
    ["├─","17:53:19.111","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-172 src=identity#321",212,45],
    ["├─","17:53:19.112","i",0,"L1 未命中","键 B7CAPS-sgu0g-172","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-172 src=cache#1711",212,45],
    ["├─","17:53:19.113","i",0,"L2 未命中","键 B7CAPS-sgu0g-172","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-172 src=cache#1712",212,45],
    ["├─","17:53:19.113","i",0,"回源查库","键 B7CAPS-sgu0g-172","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-172 src=cache#1713",212,45],
    ["├─","17:53:19.117","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1714",213,45],
    ["├─","17:53:19.173","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1715",214,45],
    ["├─","17:53:19.189","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1716",215,45],
    ["├─","17:53:19.251","i",0,"L1 未命中","键 B7CAPS-sgu0g-173","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-173 src=cache#1717",216,45],
    ["├─","17:53:19.251","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-173 src=identity#322",216,45],
    ["├─","17:53:19.252","i",0,"L2 未命中","键 B7CAPS-sgu0g-173","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-173 src=cache#1718",216,45],
    ["├─","17:53:19.252","i",0,"回源查库","键 B7CAPS-sgu0g-173","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-173 src=cache#1719",216,45],
    ["├─","17:53:19.330","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1720",217,45],
    ["├─","17:53:19.344","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-174 src=identity#323",218,45],
    ["├─","17:53:19.345","i",0,"L1 未命中","键 B7CAPS-sgu0g-174","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-174 src=cache#1721",218,45],
    ["├─","17:53:19.346","i",0,"L2 未命中","键 B7CAPS-sgu0g-174","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-174 src=cache#1722",218,45],
    ["├─","17:53:19.346","i",0,"回源查库","键 B7CAPS-sgu0g-174","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-174 src=cache#1723",218,45],
    ["├─","17:53:19.358","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1724",219,45],
    ["├─","17:53:19.405","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1725",220,45],
    ["├─","17:53:19.441","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-175 src=identity#324",221,45],
    ["├─","17:53:19.442","i",0,"L1 未命中","键 B7CAPS-sgu0g-175","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-175 src=cache#1726",221,45],
    ["├─","17:53:19.443","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1727",222,45],
    ["├─","17:53:19.443","i",0,"L2 未命中","键 B7CAPS-sgu0g-175","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-175 src=cache#1728",221,45],
    ["├─","17:53:19.443","i",0,"回源查库","键 B7CAPS-sgu0g-175","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-175 src=cache#1729",221,45],
    ["├─","17:53:19.515","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1730",223,45],
    ["├─","17:53:19.561","i",0,"L1 未命中","键 B7CAPS-sgu0g-176","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-176 src=cache#1731",224,45],
    ["├─","17:53:19.561","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-176 src=identity#325",224,45],
    ["├─","17:53:19.562","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1732",225,45],
    ["├─","17:53:19.563","i",0,"L2 未命中","键 B7CAPS-sgu0g-176","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-176 src=cache#1733",224,45],
    ["├─","17:53:19.563","i",0,"回源查库","键 B7CAPS-sgu0g-176","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-176 src=cache#1734",224,45],
    ["├─","17:53:19.591","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1735",226,45],
    ["├─","17:53:19.595","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1736",227,45],
    ["├─","17:53:19.668","i",0,"L1 未命中","键 B7CAPS-sgu0g-177","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-177 src=cache#1737",228,45],
    ["├─","17:53:19.668","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-177 src=identity#326",228,45],
    ["├─","17:53:19.670","i",0,"L2 未命中","键 B7CAPS-sgu0g-177","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-177 src=cache#1738",228,45],
    ["├─","17:53:19.670","i",0,"回源查库","键 B7CAPS-sgu0g-177","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-177 src=cache#1739",228,45],
    ["├─","17:53:19.733","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1740",229,45],
    ["├─","17:53:19.737","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1741",230,45],
    ["├─","17:53:19.761","i",0,"L1 未命中","键 B7CAPS-sgu0g-178","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-178 src=cache#1742",231,45],
    ["├─","17:53:19.761","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-178 src=identity#327",231,45],
    ["├─","17:53:19.763","i",0,"L2 未命中","键 B7CAPS-sgu0g-178","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-178 src=cache#1743",231,45],
    ["├─","17:53:19.763","i",0,"回源查库","键 B7CAPS-sgu0g-178","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-178 src=cache#1744",231,45],
    ["├─","17:53:19.823","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1745",232,46],
    ["├─","17:53:19.854","i",0,"L1 未命中","键 all","cache=goodstype result=miss key=all src=cache#1746",233,46],
    ["├─","17:53:19.870","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1747",234,46],
    ["├─","17:53:19.879","i",0,"缓存命中（L2）","键 all","cache=goodstype result=hit key=all src=cache#1748",233,46],
    ["├─","17:53:19.880","i",0,"L1 未命中","键 B7CAPS-sgu0g-179","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-179 src=cache#1749",235,46],
    ["├─","17:53:19.880","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-179 src=identity#328",235,46],
    ["├─","17:53:19.882","i",0,"L2 未命中","键 B7CAPS-sgu0g-179","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-179 src=cache#1750",235,46],
    ["├─","17:53:19.882","i",0,"回源查库","键 B7CAPS-sgu0g-179","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-179 src=cache#1751",235,46],
    ["├─","17:53:19.980","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1752",236,46],
    ["├─","17:53:19.981","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1753",237,46],
    ["├─","17:53:19.997","i",0,"L1 未命中","键 B7CAPS-sgu0g-180","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-180 src=cache#1754",238,46],
    ["├─","17:53:19.997","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-180 src=identity#329",238,46],
    ["├─","17:53:19.998","i",0,"L1 未命中","键 all-v3","cache=goodsHome result=miss key=all-v3 src=cache#1755",239,46],
    ["├─","17:53:20.000","i",0,"L2 未命中","键 B7CAPS-sgu0g-180","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-180 src=cache#1756",238,46],
    ["├─","17:53:20.000","i",0,"回源查库","键 B7CAPS-sgu0g-180","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-180 src=cache#1757",238,46],
    ["├─","17:53:20.020","i",0,"缓存命中（L2）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1758",239,46],
    ["├─","17:53:20.091","i",0,"L1 未命中","键 B7CAPS-sgu0g-181","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-181 src=cache#1759",240,46],
    ["├─","17:53:20.091","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-181 src=identity#330",240,46],
    ["├─","17:53:20.092","i",0,"L2 未命中","键 B7CAPS-sgu0g-181","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-181 src=cache#1760",240,46],
    ["├─","17:53:20.092","i",0,"回源查库","键 B7CAPS-sgu0g-181","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-181 src=cache#1761",240,46],
    ["├─","17:53:20.152","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1762",241,46],
    ["├─","17:53:20.155","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1763",242,46],
    ["├─","17:53:20.246","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1764",243,46],
    ["├─","17:53:20.247","i",0,"L1 未命中","键 B7CAPS-sgu0g-182","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-182 src=cache#1765",244,46],
    ["├─","17:53:20.247","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-182 src=identity#331",244,46],
    ["├─","17:53:20.249","i",0,"L2 未命中","键 B7CAPS-sgu0g-182","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-182 src=cache#1766",244,46],
    ["├─","17:53:20.249","i",0,"回源查库","键 B7CAPS-sgu0g-182","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-182 src=cache#1767",244,46],
    ["├─","17:53:20.257","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1768",245,46],
    ["├─","17:53:20.258","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1769",246,46],
    ["├─","17:53:20.300","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#332",247,46],
    ["├─","17:53:20.300","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#333",248,46],
    ["├─","17:53:20.300","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#334",249,46],
    ["├─","17:53:20.300","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#335",250,46],
    ["├─","17:53:20.300","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#336",251,46],
    ["├─","17:53:20.301","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#337",252,46],
    ["├─","17:53:20.305","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-3","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-3 src=lock#298",249,46],
    ["├─","17:53:20.305","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-2","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-2 src=lock#299",248,46],
    ["├─","17:53:20.305","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-0","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-0 src=lock#300",247,46],
    ["├─","17:53:20.305","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-4","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-4 src=lock#301",252,46],
    ["├─","17:53:20.305","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-1","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-1 src=lock#302",251,46],
    ["├─","17:53:20.305","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-7","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-7 src=lock#303",250,46],
    ["├─","17:53:20.305","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#338",253,46],
    ["├─","17:53:20.305","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#339",254,46],
    ["├─","17:53:20.305","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#340",255,46],
    ["├─","17:53:20.305","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#341",256,46],
    ["├─","17:53:20.305","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#342",257,46],
    ["├─","17:53:20.308","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#343",258,46],
    ["├─","17:53:20.309","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-8","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-8 src=lock#304",257,46],
    ["├─","17:53:20.309","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-11","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-11 src=lock#305",253,46],
    ["├─","17:53:20.309","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-9","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-9 src=lock#306",256,46],
    ["├─","17:53:20.309","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-5","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-5 src=lock#307",255,46],
    ["├─","17:53:20.309","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-6","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-6 src=lock#308",254,46],
    ["├─","17:53:20.312","i",1,"取锁成功","锁键 B7CAP-sgu0g-r8-10","lock=order-pay result=acquired key=B7CAP-sgu0g-r8-10 src=lock#309",258,46],
    ["├─","17:53:20.332","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-0","action=pay result=applied order#B7CAP-sgu0g-r8-0 src=order#149",247,46],
    ["├─","17:53:20.332","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-11","action=pay result=applied order#B7CAP-sgu0g-r8-11 src=order#150",253,46],
    ["├─","17:53:20.334","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-8","action=pay result=applied order#B7CAP-sgu0g-r8-8 src=order#151",257,46],
    ["├─","17:53:20.334","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-4","action=pay result=applied order#B7CAP-sgu0g-r8-4 src=order#152",252,46],
    ["├─","17:53:20.336","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-11","lock=order-pay result=released key=B7CAP-sgu0g-r8-11 src=lock#310",253,46],
    ["├─","17:53:20.336","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-0","lock=order-pay result=released key=B7CAP-sgu0g-r8-0 src=lock#311",247,46],
    ["├─","17:53:20.336","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-2","action=pay result=applied order#B7CAP-sgu0g-r8-2 src=order#153",248,46],
    ["├─","17:53:20.338","i",0,"L1 未命中","键 B7CAPS-sgu0g-183","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-183 src=cache#1770",259,46],
    ["├─","17:53:20.338","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-183 src=identity#344",259,46],
    ["├─","17:53:20.340","i",0,"L2 未命中","键 B7CAPS-sgu0g-183","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-183 src=cache#1771",259,46],
    ["├─","17:53:20.340","i",0,"回源查库","键 B7CAPS-sgu0g-183","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-183 src=cache#1772",259,46],
    ["├─","17:53:20.340","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-4","lock=order-pay result=released key=B7CAP-sgu0g-r8-4 src=lock#312",252,46],
    ["├─","17:53:20.340","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-8","lock=order-pay result=released key=B7CAP-sgu0g-r8-8 src=lock#313",257,46],
    ["├─","17:53:20.342","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-2","lock=order-pay result=released key=B7CAP-sgu0g-r8-2 src=lock#314",248,46],
    ["├─","17:53:20.346","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-10","action=pay result=applied order#B7CAP-sgu0g-r8-10 src=order#154",258,46],
    ["├─","17:53:20.347","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-9","action=pay result=applied order#B7CAP-sgu0g-r8-9 src=order#155",256,46],
    ["├─","17:53:20.347","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-1","action=pay result=applied order#B7CAP-sgu0g-r8-1 src=order#156",251,46],
    ["├─","17:53:20.347","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-3","action=pay result=applied order#B7CAP-sgu0g-r8-3 src=order#157",249,46],
    ["├─","17:53:20.347","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-7","action=pay result=applied order#B7CAP-sgu0g-r8-7 src=order#158",250,46],
    ["├─","17:53:20.352","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-1","lock=order-pay result=released key=B7CAP-sgu0g-r8-1 src=lock#315",251,46],
    ["├─","17:53:20.352","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-7","lock=order-pay result=released key=B7CAP-sgu0g-r8-7 src=lock#316",250,46],
    ["├─","17:53:20.352","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-9","lock=order-pay result=released key=B7CAP-sgu0g-r8-9 src=lock#317",256,46],
    ["├─","17:53:20.352","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-10","lock=order-pay result=released key=B7CAP-sgu0g-r8-10 src=lock#318",258,46],
    ["├─","17:53:20.353","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-3","lock=order-pay result=released key=B7CAP-sgu0g-r8-3 src=lock#319",249,46],
    ["├─","17:53:20.356","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-5","action=pay result=applied order#B7CAP-sgu0g-r8-5 src=order#159",255,46],
    ["├─","17:53:20.356","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r8-6","action=pay result=applied order#B7CAP-sgu0g-r8-6 src=order#160",254,46],
    ["├─","17:53:20.359","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-6","lock=order-pay result=released key=B7CAP-sgu0g-r8-6 src=lock#320",254,46],
    ["├─","17:53:20.359","i",1,"释放锁","锁键 B7CAP-sgu0g-r8-5","lock=order-pay result=released key=B7CAP-sgu0g-r8-5 src=lock#321",255,46],
    ["├─","17:53:20.361","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1773",260,46],
    ["├─","17:53:20.386","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1774",261,46],
    ["├─","17:53:20.387","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1775",262,46],
    ["├─","17:53:20.495","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1776",263,46],
    ["├─","17:53:20.510","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1777",264,46],
    ["├─","17:53:20.609","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1778",265,46],
    ["├─","17:53:20.650","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1779",266,46],
    ["├─","17:53:20.681","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1780",267,46],
    ["├─","17:53:20.743","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1781",268,46],
    ["├─","17:53:20.806","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1782",269,46],
    ["├─","17:53:20.838","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1783",270,46],
    ["├─","17:53:20.870","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1784",271,46],
    ["├─","17:53:20.899","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1785",272,46],
    ["├─","17:53:21.022","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1786",273,47],
    ["├─","17:53:21.040","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1787",274,47],
    ["├─","17:53:21.102","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1788",275,47],
    ["├─","17:53:21.135","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1789",276,47],
    ["├─","17:53:21.198","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1790",277,47],
    ["├─","17:53:21.200","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1791",278,47],
    ["├─","17:53:21.262","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1792",279,47],
    ["├─","17:53:21.275","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1793",280,47],
    ["├─","17:53:21.376","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1794",281,47],
    ["├─","17:53:21.430","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1795",282,47],
    ["├─","17:53:21.432","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1796",283,47],
    ["├─","17:53:21.493","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1797",284,47],
    ["├─","17:53:21.589","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1798",285,47],
    ["├─","17:53:21.589","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1799",286,47],
    ["├─","17:53:21.637","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1800",287,47],
    ["├─","17:53:21.653","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1801",288,47],
    ["├─","17:53:21.779","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1802",289,47],
    ["├─","17:53:21.838","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1803",290,47],
    ["├─","17:53:21.918","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1804",291,47],
    ["├─","17:53:21.918","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1805",292,47],
    ["├─","17:53:21.966","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1806",293,47],
    ["├─","17:53:21.996","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1807",294,47],
    ["├─","17:53:22.026","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1808",295,48],
    ["├─","17:53:22.041","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1809",296,48],
    ["├─","17:53:22.152","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1810",297,48],
    ["├─","17:53:22.230","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1811",298,48],
    ["├─","17:53:22.308","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1812",299,48],
    ["├─","17:53:22.322","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1813",300,48],
    ["├─","17:53:22.323","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1814",301,48],
    ["├─","17:53:22.373","i",0,"L1 未命中","键 B7CAPS-sgu0g-184","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-184 src=cache#1815",302,48],
    ["├─","17:53:22.373","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-184 src=identity#345",302,48],
    ["├─","17:53:22.375","i",0,"L2 未命中","键 B7CAPS-sgu0g-184","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-184 src=cache#1816",302,48],
    ["├─","17:53:22.375","i",0,"回源查库","键 B7CAPS-sgu0g-184","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-184 src=cache#1817",302,48],
    ["├─","17:53:22.403","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1818",303,48],
    ["├─","17:53:22.463","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1819",304,48],
    ["├─","17:53:22.463","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1820",305,48],
    ["├─","17:53:22.526","i",0,"L1 未命中","键 B7CAPS-sgu0g-185","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-185 src=cache#1821",306,48],
    ["├─","17:53:22.526","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-185 src=identity#346",306,48],
    ["├─","17:53:22.528","i",0,"L2 未命中","键 B7CAPS-sgu0g-185","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-185 src=cache#1822",306,48],
    ["├─","17:53:22.528","i",0,"回源查库","键 B7CAPS-sgu0g-185","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-185 src=cache#1823",306,48],
    ["├─","17:53:22.573","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1824",307,48],
    ["├─","17:53:22.604","i",0,"L1 未命中","键 B7CAPS-sgu0g-186","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-186 src=cache#1825",308,48],
    ["├─","17:53:22.604","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-186 src=identity#347",308,48],
    ["├─","17:53:22.605","i",0,"L2 未命中","键 B7CAPS-sgu0g-186","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-186 src=cache#1826",308,48],
    ["├─","17:53:22.605","i",0,"回源查库","键 B7CAPS-sgu0g-186","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-186 src=cache#1827",308,48],
    ["├─","17:53:22.611","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1828",309,48],
    ["├─","17:53:22.704","i",0,"L1 未命中","键 B7CAPS-sgu0g-187","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-187 src=cache#1829",310,48],
    ["├─","17:53:22.704","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-187 src=identity#348",310,48],
    ["├─","17:53:22.705","i",0,"L2 未命中","键 B7CAPS-sgu0g-187","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-187 src=cache#1830",310,48],
    ["├─","17:53:22.705","i",0,"回源查库","键 B7CAPS-sgu0g-187","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-187 src=cache#1831",310,48],
    ["├─","17:53:22.729","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1832",311,48],
    ["├─","17:53:22.743","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1833",312,48],
    ["├─","17:53:22.759","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1834",313,48],
    ["├─","17:53:22.806","i",0,"L1 未命中","键 B7CAPS-sgu0g-188","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-188 src=cache#1835",314,48],
    ["├─","17:53:22.806","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-188 src=identity#349",314,48],
    ["├─","17:53:22.808","i",0,"L2 未命中","键 B7CAPS-sgu0g-188","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-188 src=cache#1836",314,48],
    ["├─","17:53:22.808","i",0,"回源查库","键 B7CAPS-sgu0g-188","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-188 src=cache#1837",314,48],
    ["├─","17:53:22.838","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1838",315,48],
    ["├─","17:53:22.838","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1839",316,48],
    ["├─","17:53:22.869","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1840",317,48],
    ["├─","17:53:22.882","i",0,"L1 未命中","键 B7CAPS-sgu0g-189","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-189 src=cache#1841",318,48],
    ["├─","17:53:22.882","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-189 src=identity#350",318,48],
    ["├─","17:53:22.884","i",0,"L2 未命中","键 B7CAPS-sgu0g-189","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-189 src=cache#1842",318,48],
    ["├─","17:53:22.884","i",0,"回源查库","键 B7CAPS-sgu0g-189","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-189 src=cache#1843",318,48],
    ["├─","17:53:22.993","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1844",319,48],
    ["├─","17:53:22.998","i",0,"L1 未命中","键 B7CAPS-sgu0g-190","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-190 src=cache#1845",320,48],
    ["├─","17:53:22.998","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-190 src=identity#351",320,48],
    ["├─","17:53:23.001","i",0,"L2 未命中","键 B7CAPS-sgu0g-190","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-190 src=cache#1846",320,48],
    ["├─","17:53:23.001","i",0,"回源查库","键 B7CAPS-sgu0g-190","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-190 src=cache#1847",320,48],
    ["├─","17:53:23.056","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1848",321,49],
    ["├─","17:53:23.101","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1849",322,49],
    ["├─","17:53:23.119","i",0,"L1 未命中","键 B7CAPS-sgu0g-191","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-191 src=cache#1850",323,49],
    ["├─","17:53:23.119","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-191 src=identity#352",323,49],
    ["├─","17:53:23.120","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1851",324,49],
    ["├─","17:53:23.122","i",0,"L2 未命中","键 B7CAPS-sgu0g-191","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-191 src=cache#1852",323,49],
    ["├─","17:53:23.122","i",0,"回源查库","键 B7CAPS-sgu0g-191","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-191 src=cache#1853",323,49],
    ["├─","17:53:23.214","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1854",325,49],
    ["├─","17:53:23.226","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1855",326,49],
    ["├─","17:53:23.227","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1856",327,49],
    ["├─","17:53:23.277","i",0,"L1 未命中","键 B7CAPS-sgu0g-192","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-192 src=cache#1857",328,49],
    ["├─","17:53:23.277","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-192 src=identity#353",328,49],
    ["├─","17:53:23.279","i",0,"L2 未命中","键 B7CAPS-sgu0g-192","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-192 src=cache#1858",328,49],
    ["├─","17:53:23.279","i",0,"回源查库","键 B7CAPS-sgu0g-192","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-192 src=cache#1859",328,49],
    ["├─","17:53:23.284","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1860",329,49],
    ["├─","17:53:23.366","i",0,"L1 未命中","键 B7CAPS-sgu0g-193","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-193 src=cache#1861",330,49],
    ["├─","17:53:23.366","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-193 src=identity#354",330,49],
    ["├─","17:53:23.369","i",0,"L2 未命中","键 B7CAPS-sgu0g-193","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-193 src=cache#1862",330,49],
    ["├─","17:53:23.369","i",0,"回源查库","键 B7CAPS-sgu0g-193","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-193 src=cache#1863",330,49],
    ["├─","17:53:23.381","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1864",331,49],
    ["├─","17:53:23.445","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1865",332,49],
    ["├─","17:53:23.474","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1866",333,49],
    ["├─","17:53:23.490","i",0,"L1 未命中","键 B7CAPS-sgu0g-194","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-194 src=cache#1867",334,49],
    ["├─","17:53:23.490","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-194 src=identity#355",334,49],
    ["├─","17:53:23.491","i",0,"L2 未命中","键 B7CAPS-sgu0g-194","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-194 src=cache#1868",334,49],
    ["├─","17:53:23.491","i",0,"回源查库","键 B7CAPS-sgu0g-194","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-194 src=cache#1869",334,49],
    ["├─","17:53:23.499","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1870",335,49],
    ["├─","17:53:23.569","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1871",336,49],
    ["├─","17:53:23.599","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1872",337,49],
    ["├─","17:53:23.599","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1873",338,49],
    ["├─","17:53:23.601","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1874",339,49],
    ["├─","17:53:23.759","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1875",340,49],
    ["├─","17:53:23.867","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1876",341,49],
    ["├─","17:53:23.873","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1877",342,49],
    ["├─","17:53:23.898","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1878",343,49],
    ["├─","17:53:23.975","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1879",344,49],
    ["├─","17:53:24.006","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1880",345,49],
    ["├─","17:53:24.007","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1881",346,49],
    ["├─","17:53:24.052","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1882",347,49],
    ["├─","17:53:24.158","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1883",348,50],
    ["├─","17:53:24.255","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1884",349,50],
    ["├─","17:53:24.300","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1885",350,50],
    ["├─","17:53:24.302","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1886",351,50],
    ["├─","17:53:24.394","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1887",352,50],
    ["├─","17:53:24.426","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1888",353,50],
    ["├─","17:53:24.427","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1889",354,50],
    ["├─","17:53:24.458","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1890",355,50],
    ["├─","17:53:24.542","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1891",356,50],
    ["├─","17:53:24.693","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1892",357,50],
    ["├─","17:53:24.702","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1893",358,50],
    ["├─","17:53:24.723","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1894",359,50],
    ["├─","17:53:24.819","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1895",360,50],
    ["├─","17:53:24.848","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1896",361,50],
    ["├─","17:53:24.849","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1897",362,50],
    ["├─","17:53:24.862","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1898",363,50],
    ["├─","17:53:24.959","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1899",364,50],
    ["├─","17:53:25.095","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1900",365,50],
    ["├─","17:53:25.113","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1901",366,50],
    ["├─","17:53:25.113","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1902",367,50],
    ["├─","17:53:25.206","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1903",368,51],
    ["├─","17:53:25.222","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1904",369,51],
    ["├─","17:53:25.254","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1905",370,51],
    ["├─","17:53:25.254","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1906",371,51],
    ["├─","17:53:25.351","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1907",372,51],
    ["├─","17:53:25.440","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1908",373,51],
    ["├─","17:53:25.502","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1909",374,51],
    ["├─","17:53:25.534","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1910",375,51],
    ["├─","17:53:25.543","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1911",376,51],
    ["├─","17:53:25.627","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1912",377,51],
    ["├─","17:53:25.660","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1913",378,51],
    ["├─","17:53:25.673","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1914",379,51],
    ["├─","17:53:25.753","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1915",380,51],
    ["├─","17:53:25.814","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1916",381,51],
    ["├─","17:53:25.923","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1917",382,51],
    ["├─","17:53:25.924","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1918",383,51],
    ["├─","17:53:25.955","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1919",384,51],
    ["├─","17:53:26.017","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1920",385,51],
    ["├─","17:53:26.021","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1921",386,51],
    ["├─","17:53:26.110","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1922",387,51],
    ["├─","17:53:26.156","",-1,"请求开始 POST /goods/goodsSelect","","",388,51],
    ["│  ├─","17:53:26.156","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-195 src=identity#356",388,51],
    ["│  ├─","17:53:26.157","i",0,"L1 未命中","键 B7CAPS-sgu0g-195","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-195 src=cache#1923",388,52],
    ["│  ├─","17:53:26.159","i",0,"L2 未命中","键 B7CAPS-sgu0g-195","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-195 src=cache#1924",388,52],
    ["│  ├─","17:53:26.159","i",0,"回源查库","键 B7CAPS-sgu0g-195","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-195 src=cache#1925",388,52],
    ["│  └─","17:53:26.174","",-1,"请求结束 · 总耗时 18 ms","","",388,51],
    ["├─","17:53:26.171","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1926",389,52],
    ["├─","17:53:26.187","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1927",390,52],
    ["├─","17:53:26.250","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1928",391,52],
    ["├─","17:53:26.279","",-1,"请求开始 POST /goods/goodsSelect","","",392,52],
    ["│  ├─","17:53:26.279","i",0,"L1 未命中","键 B7CAPS-sgu0g-196","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-196 src=cache#1929",392,52],
    ["│  ├─","17:53:26.279","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-196 src=identity#357",392,52],
    ["│  ├─","17:53:26.281","i",0,"L2 未命中","键 B7CAPS-sgu0g-196","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-196 src=cache#1930",392,52],
    ["│  ├─","17:53:26.281","i",0,"回源查库","键 B7CAPS-sgu0g-196","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-196 src=cache#1931",392,52],
    ["│  └─","17:53:26.287","",-1,"请求结束 · 总耗时 8 ms","","",392,52],
    ["├─","17:53:26.288","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1932",393,52],
    ["├─","17:53:26.357","",-1,"请求开始 POST /goods/goodsSelect","","",394,52],
    ["│  ├─","17:53:26.358","i",0,"L1 未命中","键 B7CAPS-sgu0g-197","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-197 src=cache#1933",394,52],
    ["│  ├─","17:53:26.358","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-197 src=identity#358",394,52],
    ["│  ├─","17:53:26.360","i",0,"L2 未命中","键 B7CAPS-sgu0g-197","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-197 src=cache#1935",394,52],
    ["│  ├─","17:53:26.360","i",0,"回源查库","键 B7CAPS-sgu0g-197","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-197 src=cache#1936",394,52],
    ["│  └─","17:53:26.365","",-1,"请求结束 · 总耗时 8 ms","","",394,52],
    ["├─","17:53:26.358","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1934",395,52],
    ["├─","17:53:26.370","",-1,"请求开始 POST /order/orderAdd","","",396,52],
    ["│  ├─","17:53:26.371","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#359",396,52],
    ["│  ├─","17:53:26.376","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-0","lock=order-add result=acquired key=B7CAP-sgu0g-r9-0 src=lock#323",396,52],
    ["│  ├─","17:53:26.395","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-0","action=create result=applied order#B7CAP-sgu0g-r9-0 src=order#166",396,52],
    ["│  ├─","17:53:26.399","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-0","lock=order-add result=released key=B7CAP-sgu0g-r9-0 src=lock#334",396,52],
    ["│  └─","17:53:26.399","",-1,"请求结束 · 总耗时 29 ms","","",396,52],
    ["├─","17:53:26.370","",-1,"请求开始 POST /order/orderAdd","","",397,52],
    ["│  ├─","17:53:26.371","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#360",397,52],
    ["│  ├─","17:53:26.376","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-2","lock=order-add result=acquired key=B7CAP-sgu0g-r9-2 src=lock#324",397,52],
    ["│  ├─","17:53:26.396","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-2","action=create result=applied order#B7CAP-sgu0g-r9-2 src=order#169",397,52],
    ["│  ├─","17:53:26.402","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-2","lock=order-add result=released key=B7CAP-sgu0g-r9-2 src=lock#340",397,52],
    ["│  └─","17:53:26.402","",-1,"请求结束 · 总耗时 32 ms","","",397,52],
    ["├─","17:53:26.370","",-1,"请求开始 POST /order/orderAdd","","",398,52],
    ["│  ├─","17:53:26.371","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#361",398,52],
    ["│  ├─","17:53:26.376","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-1","lock=order-add result=acquired key=B7CAP-sgu0g-r9-1 src=lock#322",398,52],
    ["│  ├─","17:53:26.394","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-1","action=create result=applied order#B7CAP-sgu0g-r9-1 src=order#162",398,52],
    ["│  ├─","17:53:26.402","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-1","lock=order-add result=released key=B7CAP-sgu0g-r9-1 src=lock#339",398,52],
    ["│  └─","17:53:26.402","",-1,"请求结束 · 总耗时 32 ms","","",398,52],
    ["├─","17:53:26.371","",-1,"请求开始 POST /order/orderAdd","","",399,52],
    ["│  ├─","17:53:26.371","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#362",399,52],
    ["│  ├─","17:53:26.379","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-3","lock=order-add result=acquired key=B7CAP-sgu0g-r9-3 src=lock#327",399,52],
    ["│  ├─","17:53:26.395","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-3","action=create result=applied order#B7CAP-sgu0g-r9-3 src=order#167",399,52],
    ["│  ├─","17:53:26.399","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-3","lock=order-add result=released key=B7CAP-sgu0g-r9-3 src=lock#335",399,52],
    ["│  └─","17:53:26.399","",-1,"请求结束 · 总耗时 28 ms","","",399,52],
    ["├─","17:53:26.371","",-1,"请求开始 POST /order/orderAdd","","",400,52],
    ["│  ├─","17:53:26.371","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#363",400,52],
    ["│  ├─","17:53:26.377","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-6","lock=order-add result=acquired key=B7CAP-sgu0g-r9-6 src=lock#325",400,52],
    ["│  ├─","17:53:26.396","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-6","action=create result=applied order#B7CAP-sgu0g-r9-6 src=order#168",400,52],
    ["│  ├─","17:53:26.401","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-6","lock=order-add result=released key=B7CAP-sgu0g-r9-6 src=lock#338",400,52],
    ["│  └─","17:53:26.401","",-1,"请求结束 · 总耗时 30 ms","","",400,52],
    ["├─","17:53:26.371","",-1,"请求开始 POST /order/orderAdd","","",401,52],
    ["│  ├─","17:53:26.371","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#364",401,52],
    ["│  ├─","17:53:26.379","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-4","lock=order-add result=acquired key=B7CAP-sgu0g-r9-4 src=lock#329",401,52],
    ["│  ├─","17:53:26.396","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-4","action=create result=applied order#B7CAP-sgu0g-r9-4 src=order#170",401,52],
    ["│  ├─","17:53:26.402","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-4","lock=order-add result=released key=B7CAP-sgu0g-r9-4 src=lock#342",401,52],
    ["│  └─","17:53:26.402","",-1,"请求结束 · 总耗时 31 ms","","",401,52],
    ["├─","17:53:26.371","",-1,"请求开始 POST /order/orderAdd","","",402,52],
    ["│  ├─","17:53:26.373","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#365",402,52],
    ["│  ├─","17:53:26.379","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-7","lock=order-add result=acquired key=B7CAP-sgu0g-r9-7 src=lock#328",402,52],
    ["│  ├─","17:53:26.390","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-7","action=create result=applied order#B7CAP-sgu0g-r9-7 src=order#161",402,52],
    ["│  ├─","17:53:26.398","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-7","lock=order-add result=released key=B7CAP-sgu0g-r9-7 src=lock#333",402,52],
    ["│  └─","17:53:26.398","",-1,"请求结束 · 总耗时 27 ms","","",402,52],
    ["├─","17:53:26.371","",-1,"请求开始 POST /order/orderAdd","","",403,52],
    ["│  ├─","17:53:26.373","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#366",403,52],
    ["│  ├─","17:53:26.384","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-8","lock=order-add result=acquired key=B7CAP-sgu0g-r9-8 src=lock#332",403,52],
    ["│  ├─","17:53:26.404","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-8","action=create result=applied order#B7CAP-sgu0g-r9-8 src=order#171",403,52],
    ["│  ├─","17:53:26.406","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-8","lock=order-add result=released key=B7CAP-sgu0g-r9-8 src=lock#344",403,52],
    ["│  └─","17:53:26.406","",-1,"请求结束 · 总耗时 35 ms","","",403,52],
    ["├─","17:53:26.371","",-1,"请求开始 POST /order/orderAdd","","",404,52],
    ["│  ├─","17:53:26.373","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#367",404,52],
    ["│  ├─","17:53:26.377","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-9","lock=order-add result=acquired key=B7CAP-sgu0g-r9-9 src=lock#326",404,52],
    ["│  ├─","17:53:26.394","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-9","action=create result=applied order#B7CAP-sgu0g-r9-9 src=order#163",404,52],
    ["│  ├─","17:53:26.402","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-9","lock=order-add result=released key=B7CAP-sgu0g-r9-9 src=lock#341",404,52],
    ["│  └─","17:53:26.402","",-1,"请求结束 · 总耗时 31 ms","","",404,52],
    ["├─","17:53:26.371","",-1,"请求开始 POST /order/orderAdd","","",407,52],
    ["│  ├─","17:53:26.386","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#370",407,52],
    ["│  ├─","17:53:26.405","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-5","lock=order-add result=acquired key=B7CAP-sgu0g-r9-5 src=lock#343",407,52],
    ["│  ├─","17:53:26.415","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-5","action=create result=applied order#B7CAP-sgu0g-r9-5 src=order#172",407,52],
    ["│  ├─","17:53:26.416","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-5","lock=order-add result=released key=B7CAP-sgu0g-r9-5 src=lock#345",407,52],
    ["│  └─","17:53:26.416","",-1,"请求结束 · 总耗时 45 ms","","",407,52],
    ["├─","17:53:26.373","",-1,"请求开始 POST /order/orderAdd","","",405,52],
    ["│  ├─","17:53:26.373","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#368",405,52],
    ["│  ├─","17:53:26.380","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-10","lock=order-add result=acquired key=B7CAP-sgu0g-r9-10 src=lock#331",405,52],
    ["│  ├─","17:53:26.394","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-10","action=create result=applied order#B7CAP-sgu0g-r9-10 src=order#164",405,52],
    ["│  ├─","17:53:26.399","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-10","lock=order-add result=released key=B7CAP-sgu0g-r9-10 src=lock#337",405,52],
    ["│  └─","17:53:26.400","",-1,"请求结束 · 总耗时 27 ms","","",405,52],
    ["├─","17:53:26.373","",-1,"请求开始 POST /order/orderAdd","","",406,52],
    ["│  ├─","17:53:26.374","i",7,"身份放行","请求路径 /order/orderAdd","subject=B7CAPC-sgu0g src=identity#369",406,52],
    ["│  ├─","17:53:26.380","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-11","lock=order-add result=acquired key=B7CAP-sgu0g-r9-11 src=lock#330",406,52],
    ["│  ├─","17:53:26.394","i",2,"状态流转 null → CREATED","订单 B7CAP-sgu0g-r9-11","action=create result=applied order#B7CAP-sgu0g-r9-11 src=order#165",406,52],
    ["│  ├─","17:53:26.399","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-11","lock=order-add result=released key=B7CAP-sgu0g-r9-11 src=lock#336",406,52],
    ["│  └─","17:53:26.399","",-1,"请求结束 · 总耗时 26 ms","","",406,52],
    ["├─","17:53:26.387","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1937",408,52],
    ["├─","17:53:26.466","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1938",409,52],
    ["├─","17:53:26.497","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1939",410,52],
    ["├─","17:53:26.506","",-1,"请求开始 POST /goods/goodsSelect","","",411,52],
    ["│  ├─","17:53:26.507","i",0,"L1 未命中","键 B7CAPS-sgu0g-198","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-198 src=cache#1940",411,52],
    ["│  ├─","17:53:26.507","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-198 src=identity#371",411,52],
    ["│  ├─","17:53:26.508","i",0,"L2 未命中","键 B7CAPS-sgu0g-198","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-198 src=cache#1941",411,52],
    ["│  ├─","17:53:26.508","i",0,"回源查库","键 B7CAPS-sgu0g-198","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-198 src=cache#1942",411,52],
    ["│  └─","17:53:26.511","",-1,"请求结束 · 总耗时 5 ms","","",411,52],
    ["├─","17:53:26.591","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1943",412,52],
    ["├─","17:53:26.592","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1944",413,52],
    ["├─","17:53:26.606","",-1,"请求开始 POST /goods/goodsSelect","","",414,52],
    ["│  ├─","17:53:26.607","i",0,"L1 未命中","键 B7CAPS-sgu0g-199","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-199 src=cache#1945",414,52],
    ["│  ├─","17:53:26.607","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-199 src=identity#372",414,52],
    ["│  ├─","17:53:26.609","i",0,"L2 未命中","键 B7CAPS-sgu0g-199","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-199 src=cache#1946",414,52],
    ["│  ├─","17:53:26.609","i",0,"回源查库","键 B7CAPS-sgu0g-199","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-199 src=cache#1947",414,52],
    ["│  └─","17:53:26.615","",-1,"请求结束 · 总耗时 9 ms","","",414,52],
    ["├─","17:53:26.617","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1948",415,52],
    ["├─","17:53:26.698","",-1,"请求开始 POST /goods/goodsSelect","","",416,52],
    ["│  ├─","17:53:26.700","i",0,"L1 未命中","键 B7CAPS-sgu0g-200","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-200 src=cache#1949",416,52],
    ["│  ├─","17:53:26.700","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-200 src=identity#373",416,52],
    ["│  ├─","17:53:26.702","i",0,"L2 未命中","键 B7CAPS-sgu0g-200","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-200 src=cache#1950",416,52],
    ["│  ├─","17:53:26.702","i",0,"回源查库","键 B7CAPS-sgu0g-200","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-200 src=cache#1951",416,52],
    ["│  └─","17:53:26.708","",-1,"请求结束 · 总耗时 10 ms","","",416,52],
    ["├─","17:53:26.747","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1952",417,52],
    ["├─","17:53:26.750","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1953",418,52],
    ["├─","17:53:26.780","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1954",419,52],
    ["├─","17:53:26.827","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1955",420,52],
    ["├─","17:53:26.857","",-1,"请求开始 POST /goods/goodsSelect","","",421,52],
    ["│  ├─","17:53:26.858","i",0,"L1 未命中","键 B7CAPS-sgu0g-201","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-201 src=cache#1956",421,52],
    ["│  ├─","17:53:26.858","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-201 src=identity#374",421,52],
    ["│  ├─","17:53:26.861","i",0,"L2 未命中","键 B7CAPS-sgu0g-201","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-201 src=cache#1957",421,52],
    ["│  ├─","17:53:26.861","i",0,"回源查库","键 B7CAPS-sgu0g-201","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-201 src=cache#1958",421,52],
    ["│  └─","17:53:26.864","",-1,"请求结束 · 总耗时 7 ms","","",421,52],
    ["├─","17:53:26.906","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1959",422,52],
    ["├─","17:53:26.950","",-1,"请求开始 POST /goods/goodsSelect","","",423,52],
    ["│  ├─","17:53:26.950","i",0,"L1 未命中","键 B7CAPS-sgu0g-202","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-202 src=cache#1960",423,52],
    ["│  ├─","17:53:26.950","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-202 src=identity#375",423,52],
    ["│  ├─","17:53:26.952","i",0,"L2 未命中","键 B7CAPS-sgu0g-202","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-202 src=cache#1961",423,52],
    ["│  ├─","17:53:26.952","i",0,"回源查库","键 B7CAPS-sgu0g-202","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-202 src=cache#1962",423,52],
    ["│  └─","17:53:26.954","",-1,"请求结束 · 总耗时 4 ms","","",423,52],
    ["├─","17:53:26.980","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1963",424,52],
    ["├─","17:53:26.980","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1964",425,52],
    ["├─","17:53:27.060","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1965",426,52],
    ["├─","17:53:27.093","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1966",427,52],
    ["├─","17:53:27.093","",-1,"请求开始 POST /goods/goodsSelect","","",428,52],
    ["│  ├─","17:53:27.094","i",0,"L1 未命中","键 B7CAPS-sgu0g-203","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-203 src=cache#1967",428,52],
    ["│  ├─","17:53:27.094","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-203 src=identity#376",428,52],
    ["│  ├─","17:53:27.096","i",0,"L2 未命中","键 B7CAPS-sgu0g-203","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-203 src=cache#1968",428,52],
    ["│  ├─","17:53:27.096","i",0,"回源查库","键 B7CAPS-sgu0g-203","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-203 src=cache#1969",428,52],
    ["│  └─","17:53:27.101","",-1,"请求结束 · 总耗时 8 ms","","",428,52],
    ["├─","17:53:27.184","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1970",429,53],
    ["├─","17:53:27.200","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1971",430,53],
    ["├─","17:53:27.229","",-1,"请求开始 POST /goods/goodsSelect","","",431,53],
    ["│  ├─","17:53:27.230","i",0,"L1 未命中","键 B7CAPS-sgu0g-204","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-204 src=cache#1972",431,53],
    ["│  ├─","17:53:27.230","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-204 src=identity#377",431,53],
    ["│  ├─","17:53:27.233","i",0,"L2 未命中","键 B7CAPS-sgu0g-204","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-204 src=cache#1973",431,53],
    ["│  ├─","17:53:27.233","i",0,"回源查库","键 B7CAPS-sgu0g-204","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-204 src=cache#1974",431,53],
    ["│  └─","17:53:27.238","",-1,"请求结束 · 总耗时 9 ms","","",431,53],
    ["├─","17:53:27.295","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1975",432,53],
    ["├─","17:53:27.343","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1976",433,53],
    ["├─","17:53:27.373","",-1,"请求开始 POST /goods/goodsSelect","","",434,53],
    ["│  ├─","17:53:27.374","i",0,"L1 未命中","键 B7CAPS-sgu0g-205","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-205 src=cache#1977",434,53],
    ["│  ├─","17:53:27.374","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-205 src=identity#378",434,53],
    ["│  ├─","17:53:27.376","i",0,"L2 未命中","键 B7CAPS-sgu0g-205","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-205 src=cache#1979",434,53],
    ["│  ├─","17:53:27.376","i",0,"回源查库","键 B7CAPS-sgu0g-205","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-205 src=cache#1980",434,53],
    ["│  └─","17:53:27.381","",-1,"请求结束 · 总耗时 8 ms","","",434,53],
    ["├─","17:53:27.375","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1978",435,53],
    ["├─","17:53:27.421","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1981",436,53],
    ["├─","17:53:27.466","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1982",437,53],
    ["├─","17:53:27.496","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1983",438,53],
    ["├─","17:53:27.584","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1984",439,53],
    ["├─","17:53:27.626","",-1,"请求开始 POST /order/orderPayIfUp","","",440,53],
    ["│  ├─","17:53:27.629","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#379",440,53],
    ["│  ├─","17:53:27.634","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-3","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-3 src=lock#346",440,53],
    ["│  ├─","17:53:27.684","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-3","action=pay result=applied order#B7CAP-sgu0g-r9-3 src=order#173",440,53],
    ["│  ├─","17:53:27.688","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-3","lock=order-pay result=released key=B7CAP-sgu0g-r9-3 src=lock#358",440,53],
    ["│  └─","17:53:27.688","",-1,"请求结束 · 总耗时 62 ms","","",440,53],
    ["├─","17:53:27.626","",-1,"请求开始 POST /order/orderPayIfUp","","",441,53],
    ["│  ├─","17:53:27.629","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#380",441,53],
    ["│  ├─","17:53:27.634","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-0","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-0 src=lock#349",441,53],
    ["│  ├─","17:53:27.688","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-0","action=pay result=applied order#B7CAP-sgu0g-r9-0 src=order#176",441,53],
    ["│  ├─","17:53:27.692","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-0","lock=order-pay result=released key=B7CAP-sgu0g-r9-0 src=lock#361",441,53],
    ["│  └─","17:53:27.692","",-1,"请求结束 · 总耗时 66 ms","","",441,53],
    ["├─","17:53:27.626","",-1,"请求开始 POST /order/orderPayIfUp","","",442,53],
    ["│  ├─","17:53:27.629","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#381",442,53],
    ["│  ├─","17:53:27.634","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-2","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-2 src=lock#351",442,53],
    ["│  ├─","17:53:27.690","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-2","action=pay result=applied order#B7CAP-sgu0g-r9-2 src=order#179",442,53],
    ["│  ├─","17:53:27.693","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-2","lock=order-pay result=released key=B7CAP-sgu0g-r9-2 src=lock#362",442,53],
    ["│  └─","17:53:27.693","",-1,"请求结束 · 总耗时 67 ms","","",442,53],
    ["├─","17:53:27.626","",-1,"请求开始 POST /order/orderPayIfUp","","",449,53],
    ["│  ├─","17:53:27.634","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#387",449,53],
    ["│  ├─","17:53:27.641","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-4","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-4 src=lock#356",449,53],
    ["│  ├─","17:53:27.702","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-4","action=pay result=applied order#B7CAP-sgu0g-r9-4 src=order#183",449,53],
    ["│  ├─","17:53:27.705","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-4","lock=order-pay result=released key=B7CAP-sgu0g-r9-4 src=lock#368",449,53],
    ["│  └─","17:53:27.705","",-1,"请求结束 · 总耗时 79 ms","","",449,53],
    ["├─","17:53:27.626","",-1,"请求开始 POST /order/orderPayIfUp","","",451,53],
    ["│  ├─","17:53:27.636","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#389",451,53],
    ["│  ├─","17:53:27.637","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-1","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-1 src=lock#355",451,53],
    ["│  ├─","17:53:27.685","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-1","action=pay result=applied order#B7CAP-sgu0g-r9-1 src=order#175",451,53],
    ["│  ├─","17:53:27.690","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-1","lock=order-pay result=released key=B7CAP-sgu0g-r9-1 src=lock#360",451,53],
    ["│  └─","17:53:27.690","",-1,"请求结束 · 总耗时 64 ms","","",451,53],
    ["├─","17:53:27.627","",-1,"请求开始 POST /order/orderPayIfUp","","",443,53],
    ["│  ├─","17:53:27.629","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#382",443,53],
    ["│  ├─","17:53:27.634","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-5","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-5 src=lock#350",443,53],
    ["│  ├─","17:53:27.690","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-5","action=pay result=applied order#B7CAP-sgu0g-r9-5 src=order#177",443,53],
    ["│  ├─","17:53:27.694","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-5","lock=order-pay result=released key=B7CAP-sgu0g-r9-5 src=lock#364",443,53],
    ["│  └─","17:53:27.695","",-1,"请求结束 · 总耗时 68 ms","","",443,53],
    ["├─","17:53:27.627","",-1,"请求开始 POST /order/orderPayIfUp","","",446,53],
    ["│  ├─","17:53:27.631","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#384",446,53],
    ["│  ├─","17:53:27.634","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-6","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-6 src=lock#347",446,53],
    ["│  ├─","17:53:27.684","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-6","action=pay result=applied order#B7CAP-sgu0g-r9-6 src=order#174",446,53],
    ["│  ├─","17:53:27.688","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-6","lock=order-pay result=released key=B7CAP-sgu0g-r9-6 src=lock#359",446,53],
    ["│  └─","17:53:27.688","",-1,"请求结束 · 总耗时 61 ms","","",446,53],
    ["├─","17:53:27.627","",-1,"请求开始 POST /order/orderPayIfUp","","",447,53],
    ["│  ├─","17:53:27.634","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#385",447,53],
    ["│  ├─","17:53:27.637","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-7","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-7 src=lock#353",447,53],
    ["│  ├─","17:53:27.696","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-7","action=pay result=applied order#B7CAP-sgu0g-r9-7 src=order#180",447,53],
    ["│  ├─","17:53:27.698","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-7","lock=order-pay result=released key=B7CAP-sgu0g-r9-7 src=lock#365",447,53],
    ["│  └─","17:53:27.698","",-1,"请求结束 · 总耗时 71 ms","","",447,53],
    ["├─","17:53:27.628","",-1,"请求开始 POST /order/orderPayIfUp","","",445,53],
    ["│  ├─","17:53:27.631","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#383",445,53],
    ["│  ├─","17:53:27.634","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-11","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-11 src=lock#348",445,53],
    ["│  ├─","17:53:27.690","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-11","action=pay result=applied order#B7CAP-sgu0g-r9-11 src=order#178",445,53],
    ["│  ├─","17:53:27.694","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-11","lock=order-pay result=released key=B7CAP-sgu0g-r9-11 src=lock#363",445,53],
    ["│  └─","17:53:27.694","",-1,"请求结束 · 总耗时 66 ms","","",445,53],
    ["├─","17:53:27.628","",-1,"请求开始 POST /order/orderPayIfUp","","",448,53],
    ["│  ├─","17:53:27.634","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#386",448,53],
    ["│  ├─","17:53:27.637","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-8","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-8 src=lock#354",448,53],
    ["│  ├─","17:53:27.700","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-8","action=pay result=applied order#B7CAP-sgu0g-r9-8 src=order#181",448,53],
    ["│  ├─","17:53:27.702","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-8","lock=order-pay result=released key=B7CAP-sgu0g-r9-8 src=lock#366",448,53],
    ["│  └─","17:53:27.702","",-1,"请求结束 · 总耗时 74 ms","","",448,53],
    ["├─","17:53:27.628","",-1,"请求开始 POST /order/orderPayIfUp","","",450,53],
    ["│  ├─","17:53:27.634","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#388",450,53],
    ["│  ├─","17:53:27.635","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-10","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-10 src=lock#352",450,53],
    ["│  ├─","17:53:27.701","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-10","action=pay result=applied order#B7CAP-sgu0g-r9-10 src=order#182",450,53],
    ["│  ├─","17:53:27.703","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-10","lock=order-pay result=released key=B7CAP-sgu0g-r9-10 src=lock#367",450,53],
    ["│  └─","17:53:27.704","",-1,"请求结束 · 总耗时 76 ms","","",450,53],
    ["├─","17:53:27.628","",-1,"请求开始 POST /order/orderPayIfUp","","",452,53],
    ["│  ├─","17:53:27.637","i",7,"身份放行","请求路径 /order/orderPayIfUp","subject=B7CAPC-sgu0g src=identity#390",452,53],
    ["│  ├─","17:53:27.642","i",1,"取锁成功","锁键 B7CAP-sgu0g-r9-9","lock=order-pay result=acquired key=B7CAP-sgu0g-r9-9 src=lock#357",452,53],
    ["│  ├─","17:53:27.703","i",2,"状态流转 CREATED → PAID","订单 B7CAP-sgu0g-r9-9","action=pay result=applied order#B7CAP-sgu0g-r9-9 src=order#184",452,53],
    ["│  ├─","17:53:27.706","i",1,"释放锁","锁键 B7CAP-sgu0g-r9-9","lock=order-pay result=released key=B7CAP-sgu0g-r9-9 src=lock#369",452,53],
    ["│  └─","17:53:27.706","",-1,"请求结束 · 总耗时 78 ms","","",452,53],
    ["├─","17:53:27.631","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1985",444,53],
    ["├─","17:53:27.744","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1986",453,53],
    ["├─","17:53:27.759","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1987",454,53],
    ["├─","17:53:27.826","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1988",455,53],
    ["├─","17:53:27.830","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1989",456,53],
    ["├─","17:53:27.871","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1990",457,53],
    ["├─","17:53:27.934","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1991",458,53],
    ["├─","17:53:27.950","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1992",459,53],
    ["├─","17:53:28.005","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1993",460,53],
    ["├─","17:53:28.124","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1994",461,53],
    ["├─","17:53:28.137","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1995",462,53],
    ["├─","17:53:28.216","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1996",463,53],
    ["├─","17:53:28.216","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#1997",464,53],
    ["├─","17:53:28.238","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1998",465,54],
    ["├─","17:53:28.323","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#1999",466,54],
    ["├─","17:53:28.374","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2000",467,54],
    ["├─","17:53:28.405","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2001",468,54],
    ["├─","17:53:28.500","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2002",469,54],
    ["├─","17:53:28.547","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2003",470,54],
    ["├─","17:53:28.560","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2004",471,54],
    ["├─","17:53:28.608","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2005",472,54],
    ["├─","17:53:28.639","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2006",473,54],
    ["├─","17:53:28.671","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2007",474,54],
    ["├─","17:53:28.734","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2008",475,54],
    ["├─","17:53:28.794","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2009",476,54],
    ["├─","17:53:28.889","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2010",477,54],
    ["├─","17:53:28.905","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2011",478,54],
    ["├─","17:53:28.909","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2012",479,54],
    ["├─","17:53:29.000","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2013",480,54],
    ["├─","17:53:29.028","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2014",481,54],
    ["├─","17:53:29.062","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2015",482,54],
    ["├─","17:53:29.141","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2016",483,54],
    ["├─","17:53:29.142","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2017",484,54],
    ["├─","17:53:29.247","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2018",485,54],
    ["├─","17:53:29.280","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2019",486,54],
    ["├─","17:53:29.284","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2020",487,54],
    ["├─","17:53:29.415","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2021",488,55],
    ["├─","17:53:29.431","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2022",489,55],
    ["├─","17:53:29.449","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2023",490,55],
    ["├─","17:53:29.493","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2024",491,55],
    ["├─","17:53:29.574","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2025",492,55],
    ["├─","17:53:29.633","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2026",493,55],
    ["├─","17:53:29.696","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2027",494,55],
    ["├─","17:53:29.728","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2028",495,55],
    ["├─","17:53:29.837","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2029",496,55],
    ["├─","17:53:29.853","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2030",497,55],
    ["├─","17:53:29.857","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2031",498,55],
    ["├─","17:53:29.916","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2032",499,55],
    ["├─","17:53:30.011","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2033",500,55],
    ["├─","17:53:30.044","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2034",501,55],
    ["├─","17:53:30.075","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2035",502,55],
    ["├─","17:53:30.089","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2036",503,55],
    ["├─","17:53:30.201","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2037",504,55],
    ["├─","17:53:30.208","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2038",505,55],
    ["├─","17:53:30.293","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2039",506,55],
    ["├─","17:53:30.339","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2040",507,56],
    ["├─","17:53:30.431","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2041",508,56],
    ["├─","17:53:30.464","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2042",509,56],
    ["├─","17:53:30.465","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2043",510,56],
    ["├─","17:53:30.479","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2044",511,56],
    ["├─","17:53:30.511","",-1,"请求开始 POST /goods/goodsSelect","","",512,56],
    ["│  ├─","17:53:30.512","i",0,"L1 未命中","键 B7CAPS-sgu0g-206","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-206 src=cache#2045",512,56],
    ["│  ├─","17:53:30.512","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-206 src=identity#391",512,56],
    ["│  ├─","17:53:30.514","i",0,"L2 未命中","键 B7CAPS-sgu0g-206","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-206 src=cache#2046",512,56],
    ["│  ├─","17:53:30.514","i",0,"回源查库","键 B7CAPS-sgu0g-206","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-206 src=cache#2047",512,56],
    ["│  └─","17:53:30.519","",-1,"请求结束 · 总耗时 8 ms","","",512,56],
    ["├─","17:53:30.588","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2048",513,56],
    ["├─","17:53:30.588","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2049",514,56],
    ["├─","17:53:30.632","",-1,"请求开始 POST /goods/goodsSelect","","",515,56],
    ["│  ├─","17:53:30.633","i",0,"L1 未命中","键 B7CAPS-sgu0g-207","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-207 src=cache#2050",515,56],
    ["│  ├─","17:53:30.633","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-207 src=identity#392",515,56],
    ["│  ├─","17:53:30.635","i",0,"L2 未命中","键 B7CAPS-sgu0g-207","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-207 src=cache#2051",515,56],
    ["│  ├─","17:53:30.635","i",0,"回源查库","键 B7CAPS-sgu0g-207","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-207 src=cache#2052",515,56],
    ["│  └─","17:53:30.639","",-1,"请求结束 · 总耗时 7 ms","","",515,56],
    ["├─","17:53:30.670","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2053",516,56],
    ["├─","17:53:30.727","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2054",517,56],
    ["├─","17:53:30.790","",-1,"请求开始 POST /goods/goodsSelect","","",518,56],
    ["│  ├─","17:53:30.791","i",0,"L1 未命中","键 B7CAPS-sgu0g-208","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-208 src=cache#2055",518,56],
    ["│  ├─","17:53:30.791","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-208 src=identity#393",518,56],
    ["│  ├─","17:53:30.793","i",0,"L2 未命中","键 B7CAPS-sgu0g-208","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-208 src=cache#2056",518,56],
    ["│  ├─","17:53:30.793","i",0,"回源查库","键 B7CAPS-sgu0g-208","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-208 src=cache#2057",518,56],
    ["│  └─","17:53:30.796","",-1,"请求结束 · 总耗时 6 ms","","",518,56],
    ["├─","17:53:30.797","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2058",519,56],
    ["├─","17:53:30.819","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2059",520,56],
    ["├─","17:53:30.851","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2060",521,56],
    ["├─","17:53:30.882","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2061",522,56],
    ["├─","17:53:30.957","",-1,"请求开始 POST /goods/goodsSelect","","",523,56],
    ["│  ├─","17:53:30.957","i",0,"L1 未命中","键 B7CAPS-sgu0g-209","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-209 src=cache#2062",523,56],
    ["│  ├─","17:53:30.957","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-209 src=identity#394",523,56],
    ["│  ├─","17:53:30.959","i",0,"L2 未命中","键 B7CAPS-sgu0g-209","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-209 src=cache#2063",523,56],
    ["│  ├─","17:53:30.959","i",0,"回源查库","键 B7CAPS-sgu0g-209","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-209 src=cache#2064",523,56],
    ["│  └─","17:53:30.965","",-1,"请求结束 · 总耗时 8 ms","","",523,56],
    ["├─","17:53:30.960","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2065",524,56],
    ["├─","17:53:30.973","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2066",525,56],
    ["├─","17:53:31.054","",-1,"请求开始 POST /goods/goodsSelect","","",526,56],
    ["│  ├─","17:53:31.054","i",0,"L1 未命中","键 B7CAPS-sgu0g-210","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-210 src=cache#2067",526,56],
    ["│  ├─","17:53:31.054","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-210 src=identity#395",526,56],
    ["│  ├─","17:53:31.056","i",0,"L2 未命中","键 B7CAPS-sgu0g-210","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-210 src=cache#2068",526,56],
    ["│  ├─","17:53:31.056","i",0,"回源查库","键 B7CAPS-sgu0g-210","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-210 src=cache#2069",526,56],
    ["│  └─","17:53:31.059","",-1,"请求结束 · 总耗时 5 ms","","",526,56],
    ["├─","17:53:31.100","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2070",527,56],
    ["├─","17:53:31.118","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2071",528,56],
    ["├─","17:53:31.164","",-1,"请求开始 POST /goods/goodsSelect","","",529,56],
    ["│  ├─","17:53:31.164","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-211 src=identity#396",529,56],
    ["│  ├─","17:53:31.165","i",0,"L1 未命中","键 B7CAPS-sgu0g-211","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-211 src=cache#2072",529,56],
    ["│  ├─","17:53:31.167","i",0,"L2 未命中","键 B7CAPS-sgu0g-211","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-211 src=cache#2073",529,56],
    ["│  ├─","17:53:31.167","i",0,"回源查库","键 B7CAPS-sgu0g-211","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-211 src=cache#2074",529,56],
    ["│  └─","17:53:31.170","",-1,"请求结束 · 总耗时 6 ms","","",529,56],
    ["├─","17:53:31.242","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2075",530,56],
    ["├─","17:53:31.244","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2076",531,56],
    ["├─","17:53:31.248","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2077",532,56],
    ["├─","17:53:31.248","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2078",533,56],
    ["├─","17:53:31.272","",-1,"请求开始 POST /goods/goodsSelect","","",534,56],
    ["│  ├─","17:53:31.272","i",0,"L1 未命中","键 B7CAPS-sgu0g-212","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-212 src=cache#2079",534,56],
    ["│  ├─","17:53:31.272","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-212 src=identity#397",534,56],
    ["│  ├─","17:53:31.275","i",0,"L2 未命中","键 B7CAPS-sgu0g-212","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-212 src=cache#2080",534,56],
    ["│  ├─","17:53:31.275","i",0,"回源查库","键 B7CAPS-sgu0g-212","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-212 src=cache#2081",534,56],
    ["│  └─","17:53:31.281","",-1,"请求结束 · 总耗时 9 ms","","",534,56],
    ["├─","17:53:31.358","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2082",535,57],
    ["├─","17:53:31.381","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2083",536,57],
    ["├─","17:53:31.397","",-1,"请求开始 POST /goods/goodsSelect","","",537,57],
    ["│  ├─","17:53:31.398","i",0,"L1 未命中","键 B7CAPS-sgu0g-213","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-213 src=cache#2084",537,57],
    ["│  ├─","17:53:31.398","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-213 src=identity#398",537,57],
    ["│  ├─","17:53:31.402","i",0,"L2 未命中","键 B7CAPS-sgu0g-213","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-213 src=cache#2085",537,57],
    ["│  ├─","17:53:31.402","i",0,"回源查库","键 B7CAPS-sgu0g-213","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-213 src=cache#2086",537,57],
    ["│  └─","17:53:31.407","",-1,"请求结束 · 总耗时 10 ms","","",537,57],
    ["├─","17:53:31.507","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2087",538,57],
    ["├─","17:53:31.537","",-1,"请求开始 POST /goods/goodsSelect","","",539,57],
    ["│  ├─","17:53:31.537","i",0,"L1 未命中","键 B7CAPS-sgu0g-214","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-214 src=cache#2088",539,57],
    ["│  ├─","17:53:31.537","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-214 src=identity#399",539,57],
    ["│  ├─","17:53:31.539","i",0,"L2 未命中","键 B7CAPS-sgu0g-214","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-214 src=cache#2089",539,57],
    ["│  ├─","17:53:31.539","i",0,"回源查库","键 B7CAPS-sgu0g-214","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-214 src=cache#2090",539,57],
    ["│  └─","17:53:31.542","",-1,"请求结束 · 总耗时 5 ms","","",539,57],
    ["├─","17:53:31.552","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2091",540,57],
    ["├─","17:53:31.616","",-1,"请求开始 POST /goods/goodsSelect","","",541,57],
    ["│  ├─","17:53:31.616","i",0,"L1 未命中","键 B7CAPS-sgu0g-215","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-215 src=cache#2092",541,57],
    ["│  ├─","17:53:31.616","i",7,"身份放行","请求路径 /goods/goodsSelect","subject=B7CAPS-sgu0g-215 src=identity#400",541,57],
    ["│  ├─","17:53:31.619","i",0,"L2 未命中","键 B7CAPS-sgu0g-215","cache=goodsByMerchant result=miss key=B7CAPS-sgu0g-215 src=cache#2094",541,57],
    ["│  ├─","17:53:31.619","i",0,"回源查库","键 B7CAPS-sgu0g-215","cache=goodsByMerchant result=null key=B7CAPS-sgu0g-215 src=cache#2095",541,57],
    ["│  └─","17:53:31.625","",-1,"请求结束 · 总耗时 9 ms","","",541,57],
    ["├─","17:53:31.616","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2093",542,57],
    ["├─","17:53:31.626","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2096",543,57],
    ["├─","17:53:31.664","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2097",544,57],
    ["├─","17:53:31.711","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2098",545,57],
    ["├─","17:53:31.725","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2099",546,57],
    ["├─","17:53:31.774","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2100",547,57],
    ["├─","17:53:31.913","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2101",548,57],
    ["├─","17:53:31.990","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2102",549,57],
    ["├─","17:53:32.005","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2103",550,57],
    ["├─","17:53:32.008","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2104",551,57],
    ["├─","17:53:32.021","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2105",552,57],
    ["├─","17:53:32.098","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2106",553,57],
    ["├─","17:53:32.146","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2107",554,57],
    ["├─","17:53:32.147","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2108",555,57],
    ["├─","17:53:32.274","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2109",556,57],
    ["├─","17:53:32.379","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2110",557,58],
    ["├─","17:53:32.380","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2111",558,58],
    ["├─","17:53:32.410","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2112",559,58],
    ["├─","17:53:32.424","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2113",560,58],
    ["├─","17:53:32.536","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2114",561,58],
    ["├─","17:53:32.536","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2115",562,58],
    ["├─","17:53:32.537","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2116",563,58],
    ["├─","17:53:32.649","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2117",564,58],
    ["├─","17:53:32.769","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2118",565,58],
    ["├─","17:53:32.769","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2119",566,58],
    ["├─","17:53:32.772","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2120",567,58],
    ["├─","17:53:32.783","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2121",568,58],
    ["├─","17:53:32.876","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2122",569,58],
    ["├─","17:53:32.908","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2123",570,58],
    ["├─","17:53:32.938","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2124",571,58],
    ["├─","17:53:33.019","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2125",572,58],
    ["├─","17:53:33.099","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2126",573,58],
    ["├─","17:53:33.126","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2127",574,58],
    ["├─","17:53:33.222","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2128",575,58],
    ["├─","17:53:33.224","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2129",576,58],
    ["├─","17:53:33.250","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2130",577,58],
    ["├─","17:53:33.363","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2131",578,58],
    ["├─","17:53:33.376","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2132",579,58],
    ["├─","17:53:33.410","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2133",580,59],
    ["├─","17:53:33.530","i",0,"缓存命中（L1）","键 all","cache=goodstype result=hit key=all src=cache#2134",581,59],
    ["├─","17:53:33.579","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2135",582,59],
    ["├─","17:53:33.625","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2136",583,59],
    ["└─","17:53:33.641","i",0,"缓存命中（L1）","键 all-v3","cache=goodsHome result=hit key=all-v3 src=cache#2137",584,59]
  ],
  judge: { jia: "<b>结构判据（本页不是设计稿，是拿来比构图的）</b><ul><li><b>〇 窗口化（本轮的页高口径已改）</b>：整页高度是<b>一个定值</b>（= 视口 × 1.32，1440×900 下  <b>1188px</b>），内容再多也<b>不涨</b>；六区全落在<b>页内</b>，区与区只用一条 1px 分隔线、不留空白带； <b>事件流在框内自己滚</b>。 ⚠️ 上一轮的口径是「六区全落在<b>首个视口</b>内、整页不滚」；本轮你裁定<b>页高留 60–80px 余量</b>（上限是视口 × 1.4 = 1260px，取 1188px ⇒ 留 <b>72px</b>），代价是 <b>⑤⑥ 要往下滚一次才看得到</b> —— 这是<b>批准过的</b>取舍，不是回归。 「不随数据长」这条不是靠说的：探针往事件流里<b>临时塞 200 行</b>再比三个数 —— 文档高不变、 事件流可视高不变、事件流的 scrollHeight 变大；只量「有没有滚动条」是不够的（页面自己滚起来也有滚动条）。</li><li><b>③ 事件流 = 控制台行</b>（你 2026-10-16 逐条裁定的主改，本笔的核心）： 每条<b>一行</b>（不是卡片、不是两行）；<b>无复选框、无左侧竖线、无右侧独立列</b> —— traceId 併进 msg 行尾； 等级用<b>文字</b> INFO / WARN / ERROR（不是色块；本窗 error 0 条 ⇒ 只有 INFO 看得见）； 时刻 / 等级 / 类名三列<b>定宽</b>、msg 吃掉剩下的宽；行间 <code>gap = 0</code>，hover 整行变色。 同 trace 用<b>前导符</b>归组（<code>├─</code> / <code>│  ├─</code> / <code>│  └─</code> / <code>└─</code>， 等宽、占 2–3 字），点某行 ⇒ <b>同 trace 整组背景微染</b>（只改背景，不加边框、不加色块）。 滚动条 <b>6–8px</b>（宽度由探针实测，不写常数）。新事件<b>从底部进、旧内容平滑上移</b>： <code>cubic-out</code> 的 rAF 插值 <code>scrollTop</code>、<b>不用 top/height</b>（那会 reflow 掉帧）； 高频追加按 60ms 合成一次动画；手动滚即打断、滚回底部 8px 内自动恢复。 ⚠️ 这里有两处是<b>本版改法</b>不是现状：真页面是「新的在上 + <code>pageRows</code> 分页表」且每条两行； 本版改成时间正序、从底部追加、每条一行、DOM 只留最近 <code>200</code> 行。</li><li><b>③ 时刻列：同拍内按时刻局部排，跨拍按环序</b>（你 2026-10-16 裁定「乙」）。 口径三条：只在<b>同一拍内</b>排 · <b>同 ts 保环序</b>（稳定排序）· <b>跨拍顺序一字不动</b>； 后端 <code>EventBroadcaster</code> 的合并环口径（同拍内是<b>环序</b>、不是时刻序）也一字不动。 ⚠️ 排序的<b>单位是「组」不是「行」</b>，这是量出来的：行级排序能把逆序修成 0， 但会<b>打散 29 个组、牵动 174 行</b>（组内本来就递增，逆序全来自「上一组的尾行 vs 下一组的组首」 这种<b>并发重叠</b>）⇒ 与「同 trace 用前导符归组」直接冲突。 落地为<b>组级稳定排序</b>：实测修掉 5 处、位移 10 / 865 个组槽位，剩下的 23 处是并发重叠的固有代价，<b>如实记账、不假装修掉了</b>。</li><li><b>③ 行内三种交互（不占列）</b>：hover 整行时浮出「复制本行 / 只看这条 trace / 暂停跟随」三键（<b>绝对定位</b>，不占栅格列 —— 占了列就不是 6 列了）；<b>对象可点</b>（点对象 ⇒ 把它填进 ② 的对象筛选并真过滤）； 点行内「展开」⇒ 在<b>本行下面插一行</b>原始字段（插行期间锁住自动跟随，再点收起）。 ⚠️ 展开行跟着 ③ 内部滚、<b>不动页高</b>（探针实测）。</li><li><b>① 两排 + 就近（你裁定「不合，但要就近」）</b>：上排读数（健康灯 / 事件 / 速率 / 链路 / <b>错误率</b> / P99 /  严重度 mini 三色条），下排控件（时间范围 / 导出 CSV·JSON / 密度两档 / MQ 状态点），数据源仍在<b>页头右上</b>、 「已放 N / 500 条」常驻。错误率与严重度分布<b>物理相邻</b>（同排紧挨着）—— 这是那句「可以有个联动」的落点： 调一下距离，L1/L2 的视线路径就是短的。</li><li><b>② 类清单分两组（业务 9 + 设施 3）+ 「源」缩成 2 项</b>：同一套筛选路径、只多一条 1px 分组线 —— 慢 SQL / MQ / 调度是<b>类</b>、不是源（你的裁定）。丙 版 ② <b>去掉了计数列</b>（数字只在右栏出现一次， 不留「—」占位）。⚠️ 设施 3 类本窗 <b>0 条是实话</b>（那 3 个面还没落地，见下一条）—— 与既有 9 类里那 5 个 0 条的类<b>同样对待</b>：如实显示 0，<b>不补数、也不置灰</b></li><li><b>④ 加了 Top-N 榜与两个读数</b>：榜三档可切（对象 / 慢请求 / 错误，<b>错误榜恒空</b>，空态写明）； 读数给出错误率与 P50/P95/P99（本窗实测 P50 2 / P95 62 / P99 76 ms，n=212）—— 这一轮裁的就是「榜 + 读数」，<b>趋势线不在裁定范围内</b>（不是画不出来）。 联动三档：<b>L1 必做</b>（点三色条某档 ⇒ 勾 ② 并过滤 ③）、<b>L2 必做</b>（错误率 &gt; 0 ⇒ 脉冲 + 变红）、 <b>L3 可选做了</b>（悬停某档 ⇒ ③ 里该档的行短暂高亮）。 ⚠️ 本窗 warn/error <b>都是 0</b> ⇒ L1/L2 <b>天然不可达</b>：探针先<b>注入</b>一条 error 再量， 不注入就是假绿（你点名的验收前提）。</li><li><b>⑤ 加了「关联跳转」行</b>（64 → 88px）：从选中号的 traces 记录里取路径推断跳转目标（订单 / 商品 / 用户），取不到就写「没有 traces 记录 ⇒ 跳转目标取不到」。 ⚠️ ⑤ <b>有选中 / 没选中两种状态下高度相同</b>（探针实测）—— ⑤ 一变高，③ 就被压矮、页底就跳。</li><li><b>设施 3 类背后要补 3 个后端面（A 档，本轮只画结构）</b>：<code>SlowSqlListener</code> 加环形缓冲 + 事件化（现在是 <code>log.warn</code> 一行）· MQ 的 publish / consume / DLQ 事件化 · 7 个 <code>@Scheduled</code> 入口各包一层记录器。 两条口径照你的裁定：慢 SQL <b>同模板 5 秒去重 + 每秒封顶 20 条</b>；调度<b>只在有动作 / 失败时记</b>（不是每轮都记， 那两个 15s 的 job 每轮一条就是恒定噪声）。⚠️ 与此同批登记为「升 A 档要处理的前提」： A1 后端聚合端点（本轮仍走前端合并）、A5 后端时序保证、A6 解开单例 <code>sentSeq</code> / <code>FramePacer</code>。</li><li><b>C1 相邻区块不同构</b>：① 一排读数 ／ ② 竖排清单 ／ ③ 滚动日志行 ／ ④ 横条＋读数 ／ ⑤ 有序链 ／ ⑥ 开关排 —— 六块六种形态，没有两块共用布局。⚠️ 窗口化之后区块<b>直接相接</b>（间距 0，只隔一条分隔线）， 于是「相接就算相邻」——这一轮实测 甲 <b>10 对</b>／丙 <b>9 对</b>相邻。 ⚠️ 上一轮是几对<b>没有取证</b>（旧版草图的 DOM 与这版不同，旧判据量不到同一件事）， 所以这里<b>不写「从 N 对升到」</b>，只讲为什么更严：相接的每一对都要查，容不下「隔远一点就不算邻居」。 指纹里还必须含<b>直接子元素标签计数</b>， 只用「标签集」时两块不同构的区块会双双算出同一枚指纹而被误判同构。</li><li><b>C3 区块间距 &gt; 区块内间距 —— 本轮撤掉</b>。它有它成立的前提：上一轮区块之间是 <code>--gap-block</code> 的<b>空白带</b>，所以「区块之间留白 &gt; 区块之内留白」是一句关于<b>分组</b>的话。 窗口化之后分组由<b>外框</b>表达、区与区之间是一条 <code>1px</code> 分隔线而不是空白， 「间距」这个量在窗口形态里没有对应物 —— 判据不是变松了，是<b>前提没了</b>。 撤掉而不是把 48px 改小凑一个数，是因为留着它只会得到一条永真或永假的断言。 （上一轮为它抬过一次 <code>--gap-block</code> 32→48：探针按「盒间空白 + 两侧内边距」量出来是 40px， 而按只算 gap 的口径是 24px。那个 40 是<b>真数</b>，不是量错 —— 记在这里，免得下次有人以为它被量错了。）</li><li><b>各区宽度</b>：<b>实数由探针在 1440 视口量</b>（主区可用宽 = 客户区宽 − 壳侧栏 232 − 主区左右各 32； 滚动条宽实测，不写常数）。⚠️ 这一轮整页不再滚 ⇒ <b>页面级滚动条可能根本不存在</b>（实测 0px）， 滚动条改由<b>事件流自己</b>吃掉 —— 宽度账因此与上一轮不同，这正是不许写常数的原因。</li><li><b>13 处动效落点</b>：页面上带 <code>[data-anim]</code> 角标的元素即为落点，逐个数得出 13 个。 其中 1（新行从底部进）/ 2（旧内容上移）/ 5（error 闪红）/ 11（上下文展开）落在<b>同一个列表容器</b>上， 4（计数滚动）/ 7（图例闪）落在<b>同一个数字</b>上 —— 合成逗号列表是刻意的，拆成两个元素反而是假结构。 6（同号高亮）的<b>效果</b>落在 ③ 的行上（微染）、<b>触发</b>在 ⑤，标记留在 ⑤ 那一处。 ⚠️ 上一轮是 8 处，本轮加了 5 处、全部来自你这次的裁定： 9（联动 L1：点严重度档 ⇒ 勾上 ② 的档位并<b>真过滤</b> ③）、10（联动 L2：错误率 &gt; 0 ⇒ 读数脉冲两次 + 变红）、 11（上下文展开）、12（④ 榜单切档重排）、13（⑤ 关联跳转）。 一元素一标号，这样「数一数是不是 13 个」才数得清。</li><li><b>三样易漏项</b>：⑤ 的「起止两行只来自 traces ⇒ 模拟日常下恒空」写在 ⑤ 的注里； ③ 的「淘汰卡片时要<b>把它的 seq 也从 seenSeq 摘掉</b>」（现页面 <code>EventLog.vue</code> 里真有这行） 写在 ③ 的表头；「图例 9 类、数字是<b>本页窗口内累计</b>不是帧里 counts」写在 ② 的脚注与 ④ 的标题里。</li></ul><b>本窗实测（真帧 60 拍 · 抓帧期间共 3152 条事件 · 0 条被覆盖丢失）</b><ul><li>抓帧期间（整窗）：缓存 2138 · 锁 370 · 订单 185 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 401 · 防护 58</li><li>页面窗口内（最近 1000 条 · <b>图例读的就是这一行</b>）：缓存 655 · 锁 143 · 订单 72 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 130 · 防护 0</li><li><b>两者对不上的地方就是这页的结构性后果</b>：防护整窗 <b>58 条</b>、页面窗口 <b>0 条</b>；警告整窗 <b>112 条</b>、页面窗口 <b>0 条</b> ——缓存一类占了大头，稀有的类会被 <b>1000 条</b>的封顶<b>挤出窗口</b>，页面上从此看不见。（如实显示，<b>不补数</b>；这是本步发现的真问题，处置留给你裁。）</li><li>业务 9 类里页面窗口<b>有量 </b>4 类、<b>0 条 </b>5 类：库存 · 审计 · 通知 · 敏感词 · 防护 —— 是负载没走到那几条路径、还是那几环没接通，本步<b>不下结论</b>。（设施 3 类整类为 0，原因不同：那 3 个后端面还没落地）</li><li>严重度（页面窗口）：信息 1000 · 警告 0 · 错误 0 ⇒ <b>error 0 条</b>，「error 卡片闪红」这一处本窗没有可演示的卡片（落点在，见 ③）。</li></ul><b>这一版（甲 · 三栏驾驶舱）的结构主张</b><ul><li>左「选」／中「看」／右「量」，一栏一句话说得清。</li><li>折线是<b>中区顶部的一条横幅</b>（③ 的一部分），不另立一栏。</li><li>12 类计数在 <b>② 过滤栏行尾</b>（图例兼筛选，数字就在开关旁边），并按<b>业务 9 / 设施 3</b> 分组。</li><li>④ 右栏自上而下：<b>Top-N 榜</b>（对象 / 慢请求 / 错误 三档可切）→ 12 类计数 → 严重度分布 → 读数。</li><li>代价：中区被左右两栏夹住 ⇒ 消息列是三版里最窄的（明细先让位、traceId 永不收缩）。</li></ul>",
    bing: "<b>结构判据（本页不是设计稿，是拿来比构图的）</b><ul><li><b>〇 窗口化（本轮的页高口径已改）</b>：整页高度是<b>一个定值</b>（= 视口 × 1.32，1440×900 下  <b>1188px</b>），内容再多也<b>不涨</b>；六区全落在<b>页内</b>，区与区只用一条 1px 分隔线、不留空白带； <b>事件流在框内自己滚</b>。 ⚠️ 上一轮的口径是「六区全落在<b>首个视口</b>内、整页不滚」；本轮你裁定<b>页高留 60–80px 余量</b>（上限是视口 × 1.4 = 1260px，取 1188px ⇒ 留 <b>72px</b>），代价是 <b>⑤⑥ 要往下滚一次才看得到</b> —— 这是<b>批准过的</b>取舍，不是回归。 「不随数据长」这条不是靠说的：探针往事件流里<b>临时塞 200 行</b>再比三个数 —— 文档高不变、 事件流可视高不变、事件流的 scrollHeight 变大；只量「有没有滚动条」是不够的（页面自己滚起来也有滚动条）。</li><li><b>③ 事件流 = 控制台行</b>（你 2026-10-16 逐条裁定的主改，本笔的核心）： 每条<b>一行</b>（不是卡片、不是两行）；<b>无复选框、无左侧竖线、无右侧独立列</b> —— traceId 併进 msg 行尾； 等级用<b>文字</b> INFO / WARN / ERROR（不是色块；本窗 error 0 条 ⇒ 只有 INFO 看得见）； 时刻 / 等级 / 类名三列<b>定宽</b>、msg 吃掉剩下的宽；行间 <code>gap = 0</code>，hover 整行变色。 同 trace 用<b>前导符</b>归组（<code>├─</code> / <code>│  ├─</code> / <code>│  └─</code> / <code>└─</code>， 等宽、占 2–3 字），点某行 ⇒ <b>同 trace 整组背景微染</b>（只改背景，不加边框、不加色块）。 滚动条 <b>6–8px</b>（宽度由探针实测，不写常数）。新事件<b>从底部进、旧内容平滑上移</b>： <code>cubic-out</code> 的 rAF 插值 <code>scrollTop</code>、<b>不用 top/height</b>（那会 reflow 掉帧）； 高频追加按 60ms 合成一次动画；手动滚即打断、滚回底部 8px 内自动恢复。 ⚠️ 这里有两处是<b>本版改法</b>不是现状：真页面是「新的在上 + <code>pageRows</code> 分页表」且每条两行； 本版改成时间正序、从底部追加、每条一行、DOM 只留最近 <code>200</code> 行。</li><li><b>③ 时刻列：同拍内按时刻局部排，跨拍按环序</b>（你 2026-10-16 裁定「乙」）。 口径三条：只在<b>同一拍内</b>排 · <b>同 ts 保环序</b>（稳定排序）· <b>跨拍顺序一字不动</b>； 后端 <code>EventBroadcaster</code> 的合并环口径（同拍内是<b>环序</b>、不是时刻序）也一字不动。 ⚠️ 排序的<b>单位是「组」不是「行」</b>，这是量出来的：行级排序能把逆序修成 0， 但会<b>打散 29 个组、牵动 174 行</b>（组内本来就递增，逆序全来自「上一组的尾行 vs 下一组的组首」 这种<b>并发重叠</b>）⇒ 与「同 trace 用前导符归组」直接冲突。 落地为<b>组级稳定排序</b>：实测修掉 5 处、位移 10 / 865 个组槽位，剩下的 23 处是并发重叠的固有代价，<b>如实记账、不假装修掉了</b>。</li><li><b>③ 行内三种交互（不占列）</b>：hover 整行时浮出「复制本行 / 只看这条 trace / 暂停跟随」三键（<b>绝对定位</b>，不占栅格列 —— 占了列就不是 6 列了）；<b>对象可点</b>（点对象 ⇒ 把它填进 ② 的对象筛选并真过滤）； 点行内「展开」⇒ 在<b>本行下面插一行</b>原始字段（插行期间锁住自动跟随，再点收起）。 ⚠️ 展开行跟着 ③ 内部滚、<b>不动页高</b>（探针实测）。</li><li><b>① 两排 + 就近（你裁定「不合，但要就近」）</b>：上排读数（健康灯 / 事件 / 速率 / 链路 / <b>错误率</b> / P99 /  严重度 mini 三色条），下排控件（时间范围 / 导出 CSV·JSON / 密度两档 / MQ 状态点），数据源仍在<b>页头右上</b>、 「已放 N / 500 条」常驻。错误率与严重度分布<b>物理相邻</b>（同排紧挨着）—— 这是那句「可以有个联动」的落点： 调一下距离，L1/L2 的视线路径就是短的。</li><li><b>② 类清单分两组（业务 9 + 设施 3）+ 「源」缩成 2 项</b>：同一套筛选路径、只多一条 1px 分组线 —— 慢 SQL / MQ / 调度是<b>类</b>、不是源（你的裁定）。丙 版 ② <b>去掉了计数列</b>（数字只在右栏出现一次， 不留「—」占位）。⚠️ 设施 3 类本窗 <b>0 条是实话</b>（那 3 个面还没落地，见下一条）—— 与既有 9 类里那 5 个 0 条的类<b>同样对待</b>：如实显示 0，<b>不补数、也不置灰</b></li><li><b>④ 加了 Top-N 榜与两个读数</b>：榜三档可切（对象 / 慢请求 / 错误，<b>错误榜恒空</b>，空态写明）； 读数给出错误率与 P50/P95/P99（本窗实测 P50 2 / P95 62 / P99 76 ms，n=212）—— 这一轮裁的就是「榜 + 读数」，<b>趋势线不在裁定范围内</b>（不是画不出来）。 联动三档：<b>L1 必做</b>（点三色条某档 ⇒ 勾 ② 并过滤 ③）、<b>L2 必做</b>（错误率 &gt; 0 ⇒ 脉冲 + 变红）、 <b>L3 可选做了</b>（悬停某档 ⇒ ③ 里该档的行短暂高亮）。 ⚠️ 本窗 warn/error <b>都是 0</b> ⇒ L1/L2 <b>天然不可达</b>：探针先<b>注入</b>一条 error 再量， 不注入就是假绿（你点名的验收前提）。</li><li><b>⑤ 加了「关联跳转」行</b>（64 → 88px）：从选中号的 traces 记录里取路径推断跳转目标（订单 / 商品 / 用户），取不到就写「没有 traces 记录 ⇒ 跳转目标取不到」。 ⚠️ ⑤ <b>有选中 / 没选中两种状态下高度相同</b>（探针实测）—— ⑤ 一变高，③ 就被压矮、页底就跳。</li><li><b>设施 3 类背后要补 3 个后端面（A 档，本轮只画结构）</b>：<code>SlowSqlListener</code> 加环形缓冲 + 事件化（现在是 <code>log.warn</code> 一行）· MQ 的 publish / consume / DLQ 事件化 · 7 个 <code>@Scheduled</code> 入口各包一层记录器。 两条口径照你的裁定：慢 SQL <b>同模板 5 秒去重 + 每秒封顶 20 条</b>；调度<b>只在有动作 / 失败时记</b>（不是每轮都记， 那两个 15s 的 job 每轮一条就是恒定噪声）。⚠️ 与此同批登记为「升 A 档要处理的前提」： A1 后端聚合端点（本轮仍走前端合并）、A5 后端时序保证、A6 解开单例 <code>sentSeq</code> / <code>FramePacer</code>。</li><li><b>C1 相邻区块不同构</b>：① 一排读数 ／ ② 竖排清单 ／ ③ 滚动日志行 ／ ④ 横条＋读数 ／ ⑤ 有序链 ／ ⑥ 开关排 —— 六块六种形态，没有两块共用布局。⚠️ 窗口化之后区块<b>直接相接</b>（间距 0，只隔一条分隔线）， 于是「相接就算相邻」——这一轮实测 甲 <b>10 对</b>／丙 <b>9 对</b>相邻。 ⚠️ 上一轮是几对<b>没有取证</b>（旧版草图的 DOM 与这版不同，旧判据量不到同一件事）， 所以这里<b>不写「从 N 对升到」</b>，只讲为什么更严：相接的每一对都要查，容不下「隔远一点就不算邻居」。 指纹里还必须含<b>直接子元素标签计数</b>， 只用「标签集」时两块不同构的区块会双双算出同一枚指纹而被误判同构。</li><li><b>C3 区块间距 &gt; 区块内间距 —— 本轮撤掉</b>。它有它成立的前提：上一轮区块之间是 <code>--gap-block</code> 的<b>空白带</b>，所以「区块之间留白 &gt; 区块之内留白」是一句关于<b>分组</b>的话。 窗口化之后分组由<b>外框</b>表达、区与区之间是一条 <code>1px</code> 分隔线而不是空白， 「间距」这个量在窗口形态里没有对应物 —— 判据不是变松了，是<b>前提没了</b>。 撤掉而不是把 48px 改小凑一个数，是因为留着它只会得到一条永真或永假的断言。 （上一轮为它抬过一次 <code>--gap-block</code> 32→48：探针按「盒间空白 + 两侧内边距」量出来是 40px， 而按只算 gap 的口径是 24px。那个 40 是<b>真数</b>，不是量错 —— 记在这里，免得下次有人以为它被量错了。）</li><li><b>各区宽度</b>：<b>实数由探针在 1440 视口量</b>（主区可用宽 = 客户区宽 − 壳侧栏 232 − 主区左右各 32； 滚动条宽实测，不写常数）。⚠️ 这一轮整页不再滚 ⇒ <b>页面级滚动条可能根本不存在</b>（实测 0px）， 滚动条改由<b>事件流自己</b>吃掉 —— 宽度账因此与上一轮不同，这正是不许写常数的原因。</li><li><b>13 处动效落点</b>：页面上带 <code>[data-anim]</code> 角标的元素即为落点，逐个数得出 13 个。 其中 1（新行从底部进）/ 2（旧内容上移）/ 5（error 闪红）/ 11（上下文展开）落在<b>同一个列表容器</b>上， 4（计数滚动）/ 7（图例闪）落在<b>同一个数字</b>上 —— 合成逗号列表是刻意的，拆成两个元素反而是假结构。 6（同号高亮）的<b>效果</b>落在 ③ 的行上（微染）、<b>触发</b>在 ⑤，标记留在 ⑤ 那一处。 ⚠️ 上一轮是 8 处，本轮加了 5 处、全部来自你这次的裁定： 9（联动 L1：点严重度档 ⇒ 勾上 ② 的档位并<b>真过滤</b> ③）、10（联动 L2：错误率 &gt; 0 ⇒ 读数脉冲两次 + 变红）、 11（上下文展开）、12（④ 榜单切档重排）、13（⑤ 关联跳转）。 一元素一标号，这样「数一数是不是 13 个」才数得清。</li><li><b>三样易漏项</b>：⑤ 的「起止两行只来自 traces ⇒ 模拟日常下恒空」写在 ⑤ 的注里； ③ 的「淘汰卡片时要<b>把它的 seq 也从 seenSeq 摘掉</b>」（现页面 <code>EventLog.vue</code> 里真有这行） 写在 ③ 的表头；「图例 9 类、数字是<b>本页窗口内累计</b>不是帧里 counts」写在 ② 的脚注与 ④ 的标题里。</li></ul><b>本窗实测（真帧 60 拍 · 抓帧期间共 3152 条事件 · 0 条被覆盖丢失）</b><ul><li>抓帧期间（整窗）：缓存 2138 · 锁 370 · 订单 185 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 401 · 防护 58</li><li>页面窗口内（最近 1000 条 · <b>图例读的就是这一行</b>）：缓存 655 · 锁 143 · 订单 72 · 库存 0 · 审计 0 · 通知 0 · 敏感词 0 · 身份 130 · 防护 0</li><li><b>两者对不上的地方就是这页的结构性后果</b>：防护整窗 <b>58 条</b>、页面窗口 <b>0 条</b>；警告整窗 <b>112 条</b>、页面窗口 <b>0 条</b> ——缓存一类占了大头，稀有的类会被 <b>1000 条</b>的封顶<b>挤出窗口</b>，页面上从此看不见。（如实显示，<b>不补数</b>；这是本步发现的真问题，处置留给你裁。）</li><li>业务 9 类里页面窗口<b>有量 </b>4 类、<b>0 条 </b>5 类：库存 · 审计 · 通知 · 敏感词 · 防护 —— 是负载没走到那几条路径、还是那几环没接通，本步<b>不下结论</b>。（设施 3 类整类为 0，原因不同：那 3 个后端面还没落地）</li><li>严重度（页面窗口）：信息 1000 · 警告 0 · 错误 0 ⇒ <b>error 0 条</b>，「error 卡片闪红」这一处本窗没有可演示的卡片（落点在，见 ③）。</li></ul><b>这一版（丙 · 上下分区）的结构主张</b><ul><li>上半「仪表带」看数、下半「事件流」看流，各自拿满宽。</li><li>仪表带四格<b>不同宽、不同形态</b>：折线（最宽）／ 12 类计数条 ／ 严重度分布＋读数 ／ Top-N 榜。</li><li>12 类计数<b>只出现在仪表带</b>；② 过滤栏因此只留类名与开关状态 —— <b>连计数列都没有</b> <b>（不是画个「—」占位）</b>。<b>同一份数字不在屏幕上出现两次</b>（「严重度 mini 三色条」是<b>比例条</b>， 不是数字，那是 ① 与 ④ 相就近的联动落点，不算重复计数）。</li><li>收益：消息列<b>最宽</b>（时间戳到毫秒、traceId 可见），tail -f 最舒服。</li></ul>" }
}
/* @@EV-DATA:END 勿手改 @@ */

/* ── 小工具 ────────────────────────────────────────────────────────────────── */
var esc = function (s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
var num = function (v) { return Number(v || 0).toLocaleString('zh-CN') }

/* ── ① 状态条（两版相同；**两排**：上排读数 / 下排控件）───────────────────────
   用户 2026-10-16 裁定「错误率与严重度分布**不合，但要就近**」+「因为这俩可以有个联动」
   ⇒ 上排里「错误率」与「严重度 mini 三色条」**紧挨着放**，两块共用一条联动线：
     · L1（点三色条某一档 ⇒ 勾上 ② 的档位并**真过滤** ③）—— 落点 9 在条上；
     · L2（错误率 > 0 ⇒ 读数脉冲两次 + 变红）—— 落点 10 在读数上。
   ⚠️ 本窗 warn/error **都是 0** ⇒ 这条线**天然不可达**（不注入就是假绿）：
     三色条里 0 条的那两档画成 **3px 发丝线**（0 宽就点不着 ⇒ L1 永不可达）；
     探针先注入一条 error 再量这条线，量完复位。
   ⚠️ 全篇灰阶是这一版的纪律 ⇒ L2 的「变红」在这里只表现为**字重 + 方框 + 角标**，
     真页面才是红；这不是漏画，是不许在结构草图里上颜色。
   ⚠️ 下排的时间范围 / 导出 / 密度是**结构占位**（本轮不接行为，页面上写明了）；
     「已连接 · 第 N 拍 · 时刻 · MQ」是**读数**不是控件，放在下排右端（一行看得完）。 */
function statusBar() {
  var s = DATA.stats, c = DATA.clock, ag = DATA.sevAgg
  var bad = ag.warn + ag.error
  var rate = ag.total ? (bad / ag.total) * 100 : 0
  var mx = Math.max(ag.info, ag.warn, ag.error) || 1
  var sevN = [ag.info, ag.warn, ag.error]
  var mini = DATA.sevs.map(function (v, i) {
    return '<i class="s' + i + '" data-sev="' + i + '" style="flex:' + v.n + ' 0 auto" title="' +
      esc(v.label) + ' ' + num(v.n) + ' 条（点一下 ⇒ 只看这一档 · 联动 L1）"></i>'
  }).join('')
  return '<header class="ev-status" data-blk="status">' +
    /* ── 上排：读数 ── */
    '<div class="r1">' +
      '<span class="idx">① 状态条</span>' +
      '<span class="hl"><i class="led' + (c.mq ? '' : ' off') + '"></i>' + esc(c.phase) + '</span>' +
      '<div class="cell"><b>' + num(s.events) + '</b><span>窗口内事件（上限 ' + num(s.cap) + '）</span></div>' +
      '<div class="cell"><b>' + c.rate.toFixed(1) + '</b><span>本拍速率（条/秒）</span></div>' +
      '<div class="cell"><b>' + num(s.traces) + '</b><span>窗口内链路（上限 ' + num(s.traceCap) + '）</span></div>' +
      /* 错误率 —— 与右边的三色条紧挨着（就近），同时是联动 L2 的落点 */
      '<div class="cell err' + (bad ? ' alert' : '') + '" data-anim="10" data-anim-lbl="动10">' +
        '<b>' + rate.toFixed(1) + '<i>%</i></b><span>错误率 warn+error</span></div>' +
      /* ── 严重度 mini 三色条：与错误率相邻 = 「就近」；点某一档 = 联动 L1 ── */
      '<span class="sevmini" data-anim="9" data-anim-lbl="动9" data-role="sevmini"' +
        ' title="严重度占比（本页窗口内累计）：' + DATA.sevs.map(function (v) { return v.label + ' ' + num(v.n) }).join(' · ') + '">' +
        mini + '</span>' +
      '<div class="cell"><b>' + DATA.lat.p99 + '<i>ms</i></b><span>P99 耗时（n=' + DATA.lat.n + '）</span></div>' +
      /* ⚠️ 数据源：用户 2026-10-16 裁定 —— **留在页头右上**，且「已放 N / 500 条」**常驻**。 */
      '<div class="ev-src">' +
        '<span class="ev-seg"><span class="on">正常</span><span>模拟日常</span></span>' +
        '<span class="cnt">已放 ' + num(s.demoFed) + ' / 500 条（模拟日常未开）</span>' +
      '</div>' +
    '</div>' +
    /* ── 下排：控件（时间范围 / 导出 / 密度 = 结构占位）+ 右端读数 ── */
    '<div class="r2">' +
      '<span class="grp"><span class="lab">时间范围</span>' +
        '<span class="ev-seg sm"><span class="on">近 5 分</span><span>近 30 分</span><span>自定义</span></span></span>' +
      '<span class="grp"><span class="lab">导出</span><span class="chip">CSV</span><span class="chip">JSON</span></span>' +
      '<span class="grp"><span class="lab">密度</span>' +
        '<span class="ev-seg sm"><span class="on">紧凑</span><span>舒适</span></span></span>' +
      '<span class="note">（时间范围 / 导出 / 密度 本轮只画结构，不接行为）</span>' +
      /* ⚠️ 这里**必须是两个 span**（不是一段内联文本）：`.tz` 是 `flex-direction: column`
         的容器，内联文本的每个片段（`第` / `<b>59</b>` / `拍 · …` / `<i>` / `MQ 已连接`）
         都会各自变成一个 flex item、各占一行 —— 实测撑成 71px 高（本意两行 ≈ 31px）。
         拆成两个 span ⇒ 两行，① 少 40px，那 40px 直接给 ③（多两行事件）。 */
      '<span class="tz"><span>第 <b>' + c.seq + '</b> 拍 · 每秒 1 拍 · ' + esc(c.tick) + '</span>' +
        '<span><i class="mqdot' + (c.mq ? '' : ' off') + '"></i>MQ ' + (c.mq ? '已连接' : '未连接') + '</span></span>' +
    '</div>' +
  '</header>'
}

/* ── 条件块：断线条（判据是 `lost`，不是 `errMsg`）────────────────────────────
   本窗**未触发**（后端跑满 60 拍自己正常收尾，但那是抓帧脚本主动结束，不是页面断线）
   ⇒ 画成虚线「条件块」。⚠️ 它在结构上**必须留位置**：没有它，「数字静静停住」就没人说。 */
/* ⚠️ 窗口化之后它是外框里的**一条细带**（不是浮在区块之间的空条）：未触发时真页面里
   用 `v-if` **不占位**，这里画出来只为了给动效落点 8 一个家 —— 所以左缘留虚线、并写明这件事。 */
function flashSlot() {
  return '<div class="ev-cond" data-blk="flash" data-anim="8" data-anim-lbl="动8">' +
    '条件块 · 断线条（判据 <code>lost</code>）：本窗未触发；真页面未触发时本行<b>不占位</b>。' +
    '出现时本行脉冲一次，右侧带「立即重连」。' +
    '</div>'
}

/* ── ② 过滤栏 ──────────────────────────────────────────────────────────────── */
/* counts = true（甲）在行尾给数字；counts = false（丙）只留类名与开关 —— 数字归 ④ 仪表带。
   ⚠️ 动效落点 4（计数数字滚动）与 7（图例计数闪一下）**落在同一处**，这是刻意的：
   它俩本来就是同一个数字上的两个效果（位数滚动 + 变化时闪一下），拆成两个元素反而是假结构。
   ⇒ 标注写成逗号列表 `data-anim="4,7"`，探针按逗号拆。 */
function rail(counts) {
  var h = '<div class="ev-rail" data-blk="rail">'
  h += '<div class="rl"><span class="idx">②</span> 过滤栏（图例兼筛选）</div>'
  /* ── 控件（本轮**只有**「类 / 严重度≥WARN / 对象」这三样真过滤；见下面的句末说明）──
     ⚠️ 搜索框与对象框画成**矩形**、不是 `<input>`：这是结构草图的纪律（不上真控件），
        真页面落地时才是 input —— 画成 input 的话，截图上会多出一个「可以打字」的假承诺。 */
  h += '<div class="ctl">' +
    '<span class="ln"><span class="lab">搜索</span><span class="box">标题 / 明细 / traceId…</span></span>' +
    '<span class="ln"><span class="lab">源</span>' +
      DATA.sources.map(function (x) {
        return '<span class="chip' + (x.on ? ' on' : '') + '" data-src="' + esc(x.k) + '" title="' +
          esc(x.label) + ' ' + num(x.n) + ' 条">' + esc(x.label) + '</span>'
      }).join('') + '</span>' +
    '<span class="ln"><span class="lab">批次</span><span class="chip">全部</span>' +
      '<span class="chip" data-f="warnup" data-role="warnup">≥ WARN</span></span>' +
    '<span class="ln"><span class="lab">对象</span><span class="box" data-role="objbox">点 ③ 里的对象 ⇒ 填到这里</span></span>' +
  '</div>'
  /* ⚠️ 只有**行清单**滚（`.rows`）；口径 / 严重度 / 「全部打开」钉在栏底不滚 ——
     窗口化之后栏只有 ~600px 高，口径要是跟着滚走了，图例就变成了没有出处的数字。 */
  h += '<div class="rows"' + (counts ? ' data-anim="4,7" data-anim-lbl="动4·7"' : '') + '>'
  h += '<div class="row all" data-f="all" data-role="allrow"><span class="sw' + (DATA.rail.allOn ? '' : ' off') + '"></span>' +
       '<span class="k">全部</span>' + (counts ? '<span class="c">' + num(DATA.rail.total) + '</span>' : '') + '</div>'
  /* ── 类清单**分两组**（用户 2026-10-16 裁定）：业务 9（既有、真数据）+ 设施 3（慢 SQL / MQ / 调度）。
     两组**同构**（同一套行形态、同一个开关、同一条筛选路径），只多一条分组线 + 一行组名 ——
     「慢 SQL / MQ / 调度 是**类**、不是源」。 */
  var grpOf = function (g) { return DATA.kinds.filter(function (k) { return k.grp === g }) }
  var rowOf = function (k) {
    return '<div class="row' + (k.n ? '' : ' off') + '" data-f="kind" data-k="' + esc(k.k) + '">' +
      '<span class="sw"></span><span class="k">' + esc(k.label) + '</span>' +
      (counts ? '<span class="c">' + num(k.n) + '</span>' : '') + '</div>'
  }
  h += grpOf('biz').map(rowOf).join('')
  h += '<div class="row gh">设施 ' + grpOf('fac').length + ' 类（后端面待落地：慢 SQL 事件化 / MQ 事件化 / 调度记录器）</div>'
  h += grpOf('fac').map(rowOf).join('')
  h += '</div>'
  /* ⚠️ 口径必须写在这里（图例旁边），不是写在别处：图例数字是**本页自己攒的窗口内累计**，
     帧里那个 `counts` 是「本拍」的 —— 拿它当图例数字，每次静默的一拍上会全体归零。
     3a.2b 的 S1/S3 就是漏了这一条（把「单序列」当成唯一的口径问题）。 */
  h += '<div class="sub">严重度 · 类计数 = 本页<b>窗口内累计</b>（帧里的 counts 是「本拍」的，不用它）' +
       (counts ? '' : ' · <b>本版 ② 不给数字</b>（数字只在右侧仪表带出现一次，不用「—」占位）') +
       ' · 本轮只有<b>类 / ≥ WARN / 对象</b>三样真过滤，其余是结构占位</div>'
  h += '<div class="sev">'
  DATA.sevs.forEach(function (v, i) {
    h += '<span data-f="sev" data-s="' + i + '" data-role="sevchip"' + (counts ? ' data-withn="1"' : '') +
      ' title="点一下 ⇒ 只看这一档（联动 L1）">' +
      esc(v.label) + (counts ? ' ' + num(v.n) : '') + '</span>'
  })
  h += '</div>'
  h += '<div class="foot" data-role="clear" title="清掉所有筛选（含 ③ 过滤）">全部打开 / 清筛选</div>'
  return h + '</div>'
}

/* ── ③ 事件流：控制台行 ──────────────────────────────────────────────────────
   用户 2026-10-16 逐条裁定（原话见桌面报告）：
     · 每条**一行**（不是卡片、不是两行）；删除复选框 / 左侧竖线 / 右侧独立列，
       traceId **併进消息行尾**；
     · 等级用**文字** INFO / WARN / ERROR（不是色块）；保留 lucide 图标（小、在等级前）；
     · 时刻 / 等级 / 类名三列**定宽**，消息吃掉剩下的宽；行间 gap = 0；hover 整行变色；
     · 同 trace 用**前导符**归组（├─ / │  ├─ / │  └─ / └─），点某行 ⇒ 同 trace 整组背景微染。
   ⚠️ 与真页面**三处不同，都是主张不是现状**：真页面是「新的在上」+ `pageRows` 分页表 +
   每条两行（标题/类/对象一行、明细一行，外加左色条与右侧 traceId 列）。
   本版 = 时间正序 + 从底部追加 + 每条一行 + DOM 只留最近 `DATA.rowMax` 行。
   ⚠️ 归组规则（为什么不是「每个 trace 都补一对起止」）：本窗 1000 条事件里有 585 个不同
   traceId；真帧 60 拍累计看，1938 个 trace 里 **1476 个只有 1 条事件**。每个都补起止的话
   请求行会占屏幕一半 ⇒ 只有「事件数 ≥2 且 traces 里有记录」才包组，单条的走顶格行。 */
var LV_TXT = { i: 'INFO', w: 'WARN', e: 'ERROR' }
/* req 行（请求开始 / 请求结束）的记号：真 `KIND_ICON` 里**没有**「请求」这一类，
   这条短线是**本版自造**的，不是从真页面搬来的 —— 别当成真图标看。 */
var REQ_ICON = ['M2.6 8h10.8']
var modLabel = function (k) { return k < 0 ? '请求' : (DATA.kinds[k] ? DATA.kinds[k].label : '?') }
function iconSvg(k) {
  var key = k < 0 ? '' : (DATA.kinds[k] || {}).k
  var paths = k < 0 ? REQ_ICON : (DATA.icons[key] || [])
  return '<svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true" focusable="false">' +
    paths.map(function (d) {
      return '<path d="' + d + '" fill="none" stroke="currentColor" stroke-width="1.3" ' +
        'stroke-linecap="round" stroke-linejoin="round"/>'
    }).join('') + '</svg>'
}
/* 一行 = 6 格：前导符 / 时刻 / 图标 / 等级 / 类 / 消息。
   ⚠️ 格子名避开共用文件里的 `.main`（那是给页面外壳的 `padding:32px`）——
   上一轮就栽在这里：卡片内那格也叫 `.main`，行高被顶成 83px、一屏只看得到 3 条。 */
function logLine(r) {
  var pre = r[0], ts = r[1], lv = r[2], k = r[3], title = r[4], obj = r[5], detail = r[6], tr = r[7]
  var kk = k < 0 ? 'req' : ((DATA.kinds[k] || {}).k || '')
  /* 层级从数据行自带的前导符里读：`├─` = 0 层、`│  ├─` = 1 层、`│  │  ├─` = 2 层……
     （`├`/`└` 前面每 3 个字符 = 一层）。⚠️ 数据里那串字符**只用来定层级**，
     分叉画 `├─` 还是 `└─`、祖先画不画竖线，一律由 decorate() 按**窗口内**的树现算。 */
  var bi = Math.max(pre.indexOf('├'), pre.indexOf('└'))
  var dep = bi > 0 ? bi / 3 : 0
  return '<div class="log-line' + (k < 0 ? ' req' : '') + (lv ? ' lv-' + lv : '') +
    '" data-d="' + dep + '" data-trace="' + tr + '" data-t="' + r[8] +
    '" data-k="' + esc(kk) + '" data-obj="' + esc(obj) + '">' +
    '<span class="pre">' + esc(pre) + '</span>' +
    '<span class="tm">' + esc(ts) + '</span>' +
    '<span class="ic">' + iconSvg(k) + '</span>' +
    '<span class="lv">' + esc(LV_TXT[lv] || '') + '</span>' +
    '<span class="md">' + esc(modLabel(k)) + '</span>' +
    '<span class="ms"><b>' + esc(title) + '</b>' +
      (obj ? '<span class="ob p" data-xact="obj" title="点一下 ⇒ 按这个对象过滤 ③">' + esc(obj) + '</span>' : '') +
      (detail ? '<span class="dt">' + esc(detail) + '</span>' : '') +
      (tr >= 0 ? '<span class="tr">trace ' + esc(DATA.trIdx[tr] || '') + '</span>' : '') +
    '</span>' +
    /* ── 行内三键 + 展开（**绝对定位，不占栅格列** —— 占了列就不是 6 列了）──────────
       用户 2026-10-16 裁定的「hover 浮层不占列」。⚠️ 取舍：浮出来时压在行尾 traceId 上，
       行尾 8px 内那块被三键盖住 —— 这是「不占列」的代价，写在报告里。 */
    '<span class="acts">' +
      '<i data-xact="copy" title="复制这一行的字段">复制本行</i>' +
      '<i data-xact="trace" title="只看这一条 trace 的行">只看这条 trace</i>' +
      '<i data-xact="expand" title="在本行下面插一行，摊开这一条的原始字段">展开</i>' +
      '<i data-xact="pause" title="暂停/继续自动跟随">暂停跟随</i>' +
    '</span>' +
  '</div>'
}
/* 列名行（控制台的表头）：和行同一个栅格，所以三列定宽是**量得出来**的 */
function logHead() {
  return '<div class="log-head ev-fix">' +
    '<span class="pre">前导</span><span class="tm">时刻</span><span class="ic"></span>' +
    '<span class="lv">等级</span><span class="md">类</span>' +
    '<span class="ms">消息 · 对象 · 明细 · trace（traceId 併在行尾，不再单占一列）</span></div>'
}
/* 演示开关：①② 那两条（自动跟随 / 暂停）真页面里靠**滚动手势**自动判定，
   这里给按钮只是为了让你在草图上能亲手试 ④（上移流畅 / 打断 / 恢复）。 */
function streamCtrl() {
  return '<span class="ctrl">' +
    '<button type="button" data-act="replay">▶ 重放</button>' +
    '<button type="button" data-act="pause" data-role="pausetxt">⏸ 暂停</button>' +
    '<button type="button" data-act="bottom">↓ 回到底部</button>' +
    '<span class="st" data-role="auto">自动跟随：开</span>' +
    '<span class="st" data-role="rows">行数 —</span>' +
  '</span>'
}
/* ③ 的**体**：列名 ＋ 可滚的行清单。
   ⚠️ 动效落点 1（新行从底部进）/ 2（旧内容上移）/ 5（error 闪红）落在**同一个列表容器**上：
   它们本来就是同一个容器上的三件事，拆成三个元素反而是假结构。 */
function stream() {
  var h = '<div class="ev-h2 ev-fix"><b><span class="idx">③</span> 事件流</b>' +
    '<span class="n">窗口内 ' + num(DATA.stats.events) + ' 条 · 草图画最近 ' + DATA.rowMax + ' 行</span>' +
    streamCtrl() + '</div>'
  h += '<div class="log-note ev-fix">' +
    '<b>本版改法</b>：时间正序、从底部追加（现页面是<b>新的在上 + <code>pageRows</code> 分页表</b>）· 每条一行 · ' +
    'DOM 只留最近 ' + DATA.rowMax + ' 行 · 淘汰行时要把它的 <code>seq</code> 从 <code>seenSeq</code> 摘掉，' +
    '否则新事件会被误判成重复、页面看着不动 · 视窗顶部可能是<b>半个组</b>（上面的行被淘汰了）· ' +
    'error <b>0 条</b> ⇒ 「闪红」本窗无行可演示 · ' +
    '▶ 重放走 <b>60 拍真帧的拍序</b>（每拍 = 真帧那一拍的条数）：本窗<b>前 ' +
    (DATA.replayHead || 0) + ' 拍真的一拍 0 条</b>（采集开始时应用还没热），所以重放<b>从第 ' +
    ((DATA.replayHead || 0) + 1) + ' 拍起</b> —— 不然点一次要空等 ' + (DATA.replayHead || 0) + ' 秒<br>' +
    /* ⚠️ 这句是用户 2026-10-16 裁定「乙」时点名要补进 ③ 的说明行的原话。 */
    '<b>时刻列</b>：<b>同拍内按时刻局部排，跨拍按环序</b>（同 ts 保环序；后端合并环口径不动）。' +
    '⚠️ 排序的单位是<b>组</b>不是行（实测位移 ' + DATA.order.blockMoved + ' / ' + DATA.order.blocks +
    ' 个组槽位）：本窗同拍内仍有 <b>' + DATA.order.invInTick + '</b> 处行级逆序、跨拍 <b>' +
    DATA.order.invCross + '</b> 处 —— 同拍那 ' + DATA.order.invInTick + ' 处全是<b>并发重叠</b>' +
    '（上一组的尾行 vs 下一组的组首），改用行级排序能修逆序但会打散 <b>' + DATA.order.brokenGroups +
    ' 个组 / 牵动 ' + DATA.order.brokenRows + ' 行</b>、与「同 trace 用前导符归组」冲突 ⇒ ' +
    '<b>不修、如实记账</b>。<br>' +
    '<b>行内交互（不占列）</b>：hover 任一行浮出四键 —— <b>复制本行</b>（草图在 <code>file://</code> 下' +
    '拿不到剪贴板权限，会当场把失败原因写出来，落地接 clipboard）· <b>只看这条 trace</b>（真过滤）· ' +
    '<b>展开</b>（在本行下面插一行原始字段；<b>插行期间自动跟随锁住</b>，再点收起）· <b>暂停跟随</b>。' +
    '点消息里<b>虚线下划线</b>的<b>对象</b> ⇒ 按这个对象过滤 ③（真过滤）。' +
    '⚠️ 浮层压在行尾 traceId 上（行尾 8px 内）—— 这是「不占列」的取舍。</div>'
  h += logHead()
  h += '<div class="ev-list ev-pane" id="ev-list" data-anim="1,2,5,11" data-anim-lbl="动1·2·5·11"' +
    ' tabindex="0" role="log" aria-label="事件流"></div>'
  return h
}

/* ── ③ 的行为：追加 / 平滑上移 / 打断 / 暂停 ─────────────────────────────────
   这一段不是动效装饰，它要证的是「上移不生硬」。做法照你 2026-10-16 的原话：
     · `requestAnimationFrame` 插值 `scrollTop`，250ms、`cubic-out` ——
       **不用 top/height**（改 top/height 会 reflow、掉帧）；
     · 高频追加按 60ms 合并成一次动画（一次最多喂 12 行）；
     · 手动滚 → 立刻打断自动跟随；滚回底部 8px 内 → 自动恢复；
     · 暂停 ⇒ 队列也停（一行都不动）。
   ⚠️ 为什么不能只写「追加完滚到底」：DOM 封顶 200 行之后，追加 N 行会**同时淘汰 N 行**，
   scrollHeight 不变 ⇒ 「滚到底」是零位移，内容会**瞬移**一格。所以先按新行的高度把
   scrollTop **回退** delta 像素（这一步和追加在同一个任务里做完 ⇒ 浏览器一次绘制里只看到
   动画、看不到那一跳），动画再从那儿滚回底部 —— 这才是「旧内容平滑上移、新行从底部进」。 */
var EV = (function () {
  var DUR = 250, CHUNK = 12, COALESCE = 60, NEAR = 8
  var list = null
  var S = {
    at: 0, queue: [], auto: true, paused: false, sel: DATA.sel.tr,
    raf: 0, animTo: null, flushT: 0, tickT: 0, tick: -1, lastFlush: 0,
    fed: 0, hit: 0, anims: 0, starts: 0, lastMs: 0,
    /* ③ 的筛选态（用户裁定：只有「类 / 严重度 / ≥WARN / 对象 / 只看这条 trace」五样真过滤）*/
    flt: { sev: -1, warnUp: false, kind: null, obj: null, onlyTr: null },
    fltShown: 0, fltPainted: false,
    inj: null,          // 探针夹具：注入的 warn/error 条数（不落进 ③，不污染截图）
    topTab: null,       // ④ 榜单当前档（在 TOP_TAB 里）
    lastCopy: '',       // 复制本行的结果（成功 or 失败原因），探针据此断言「不假装成功」
    hlSev: -1,          // L3：当前悬停的档位
    xpLock: false       // 展开行开着 ⇒ 跟随锁死（滚动锚定会把 auto 顶回 true，见 onScroll）
  }
  var ease = function (t) { return 1 - Math.pow(1 - t, 3) }      // cubic-out
  var maxTop = function () { return Math.max(0, list.scrollHeight - list.clientHeight) }
  var nearBottom = function () { return maxTop() - list.scrollTop <= NEAR }
  var barPx = function () { return list.offsetWidth - list.clientWidth }

  function scrollTo_(to, dur) {
    if (S.raf) { cancelAnimationFrame(S.raf); S.raf = 0 }
    var from = list.scrollTop, dist = to - from
    if (!dur || Math.abs(dist) < 1) { list.scrollTop = to; S.animTo = null; return }
    var t0 = performance.now()
    S.animTo = to
    S.starts++
    var step = function (now) {
      var p = Math.min(1, (now - t0) / dur)
      list.scrollTop = from + dist * ease(p)
      if (p < 1) { S.raf = requestAnimationFrame(step) }
      else { S.raf = 0; S.animTo = null; S.anims++; S.lastMs = Math.round(performance.now() - t0) }
    }
    S.raf = requestAnimationFrame(step)
  }
  /* 把包层拆掉：DOM 保持扁平（子树深了选行、量高都会变慢）。拆之前先量高度。 */
  function unwrap(wrap) {
    while (wrap.firstChild) list.insertBefore(wrap.firstChild, wrap)
    list.removeChild(wrap)
  }
  /* 画出来的**真行**数（不含 `[data-xp]` 展开行：它是插进去的明细，不是事件）*/
  function drawnCount() {
    var ks = list.children, n = 0
    for (var i = 0; i < ks.length; i++) if (!ks[i].getAttribute('data-xp')) n++
    return n
  }
  /* ══ 前导符：按**窗口内**的树现算，不照抄数据里那串字 ══════════════════════════
     规则（用户 2026-10-16 裁定「tree 形态」）：
       ① `│` = 该祖先在这一窗里还有后续兄弟（竖向连接线）
       ② 缩进 = 层级 × 3 字符 ⇒ 本列宽 = (3×层级 + 2) 字符，**后面的列跟着右移**
       ③ 本层最后一个 ⇒ `└─`，否则 `├─`
     ⚠️ 为什么必须现算：数据里那串 `└─` 是**手写**在表尾的，滑窗一滚就不对了
        —— 一行是不是「本层最后一个」，取决于**窗里后面还有没有同级/同父的行**。
     ⚠️ 祖先已滚出窗口时**照样画竖线**：否则每来一批新行，窗口顶上那几行的竖线会
        闪断（读起来像「树断了」）。竖线连到窗口外，语义上是对的：父行在更上面。
     ⚠️ 深度只从数据读；`├─`/`└─`/竖线一律现算 —— 探针会独立重算一遍逐行比对。 */
  function decorate() {
    var ks = list.children, i, j, t
    var rows = [], dep = [], par = [], last = []
    for (i = 0; i < ks.length; i++) {
      if (ks[i].getAttribute('data-xp')) continue      // 明细行不是事件，不进树
      rows.push(ks[i]); dep.push(Number(ks[i].getAttribute('data-d') || 0))
    }
    var m = rows.length
    var stack = []
    for (i = 0; i < m; i++) {
      stack.length = dep[i]
      stack[dep[i]] = i
      par.push(dep[i] > 0 && stack[dep[i] - 1] !== undefined ? stack[dep[i] - 1] : -1)
    }
    /* 「本层最后一个」：从后往前扫，同一个父节点最后被见到的那个孩子就是末位 */
    var seen = {}
    for (i = m - 1; i >= 0; i--) if (seen[par[i]] === undefined) seen[par[i]] = i
    for (i = 0; i < m; i++) {
      last[i] = seen[par[i]] === i
      var s = ''
      /* 逐层画祖先竖线。⚠️ 左起**第 j 组**字符管的是**第 j 层**的祖先（`│  │  ├─` 里
         最左那组管根、第二组管父）—— 所以是往上走 `dep[i] - j` 次，不是 `j + 1` 次：
         写成 `j + 1` 在 1 层时看不出差别（只有一组），2 层起两组竖线就**左右颠倒**。
         （合成序列的验算见 `_work/3a3/tree-shape-check.mjs`：A 错 2 处、B 全对。） */
      for (j = 0; j < dep[i]; j++) {
        var a = i
        for (t = 0; t < dep[i] - j; t++) a = a < 0 ? -1 : par[a]
        s += (a < 0 || !last[a]) ? '│  ' : '   '
      }
      s += last[i] ? '└─' : '├─'
      rows[i].querySelector('.pre').textContent = s
      /* 列宽 = 层级 × 3 字符 + 分叉 2 字符；`ch` 跟着等宽字体走，不写死 px */
      rows[i].style.setProperty('--pc', 'calc(' + (3 * dep[i] + 2) + 'ch + 8px)')
    }
  }
  function appendSlice(slice) {
    var wrap = document.createElement('div')
    wrap.className = 'log-add'
    var html = ''
    for (var i = 0; i < slice.length; i++) html += logLine(slice[i])
    wrap.innerHTML = html
    var t0 = list.scrollTop, h0 = list.scrollHeight
    list.appendChild(wrap)
    if (fltOn()) fltMark(wrap)         // 先过筛再量高（见 fltMark 的注释）
    var add = wrap.offsetHeight
    /* ⚠️ 先拆包层再封顶：`children.length` 把包层也算一个孩子，
       反过来的话每喂一批就少淘汰 `批大小 − 1` 行（12 行一批 ⇒ 泄漏 11 行）。 */
    unwrap(wrap)
    while (drawnCount() > DATA.rowMax) list.removeChild(list.firstElementChild)
    decorate()                         // ⚠️ 必须在封顶淘汰**之后**：末位判定看的是窗里还有谁
    S.fed += slice.length
    /* ⚠️ 顺序：**先改字、再量几何**。「共喂 N 行」这类字会改 ③ 表头的行数、
       表头一多折一行事件流就被压矮 ⇒ 底部跟着下移；先量再改的话，动画目标与
       起点都是旧布局上的数，第一帧就会被浏览器夹回去（实测第一帧比起点还低 40px）。 */
    sync()
    if (fltOn()) applyFlt()            // 整表重算一遍（含计数与 ② 的联动态）
    if (!(S.auto && !S.paused)) {
      /* 没跟随：淘汰掉的高度要补回去，否则「手动滚上去看旧行」会被新行顶走 */
      var gone = h0 + add - list.scrollHeight
      if (gone > 0) list.scrollTop = Math.max(0, t0 - gone)
      return
    }
    var end = maxTop(), start = Math.max(0, end - add)
    list.scrollTop = start                 // ← 回退 delta（与追加同一个任务 ⇒ 不会画出这一跳）
    scrollTo_(end, DUR)
  }
  function flush() {
    S.flushT = 0
    if (S.paused || !S.queue.length) return
    S.lastFlush = performance.now()
    appendSlice(S.queue.splice(0, CHUNK))
    if (S.queue.length) schedule()
  }
  function schedule() { if (!S.flushT) S.flushT = setTimeout(flush, COALESCE) }
  function push(rows) {
    for (var i = 0; i < rows.length; i++) S.queue.push(rows[i])
    if (!S.flushT && performance.now() - S.lastFlush >= COALESCE) flush()
    else schedule()
  }
  var nextRow = function () { var r = DATA.rows[S.at % DATA.rows.length]; S.at++; return r }

  /* ── 选中：点某行 ⇒ 同 trace 整组微染（只改背景，不加边框、不加色块）──────── */
  function paint() {
    var kids = list.children, hit = 0
    for (var i = 0; i < kids.length; i++) {
      var on = S.sel >= 0 && Number(kids[i].getAttribute('data-trace')) === S.sel
      kids[i].classList.toggle('sel', on)
      if (on) hit++
    }
    S.hit = hit
    paintDrawer()
    sync()          // ⚠️ ⑤ 的内容与「同号 N 行」都是**会改布局的字** ⇒ 改完再贴底
    return hit
  }
  function select(tr) { S.sel = (tr === S.sel ? -1 : tr); return paint() }

  /* ── ⑤ 链路展开：跟着选中的号走（有 traces 记录才画得出起止两行）─────────── */
  /* ── ⑤ 的「关联跳转」行（落点 13）───────────────────────────────────────
     ⚠️ **两种状态下都要在**：有选中 / 没选中各一行、且都只有一行高 —— ⑤ 一变高，③ 就被压矮，
        整页底部会跳（这条例子上上轮踩过：点一下「取消选中」③ 的可视高从 296 变 321）。
     ⚠️ 跳转目标由**选中号的 traces 记录里的路径**推断（/order/ ⇒ 订单、/goods/ ⇒ 商品、
        /login|user|auth/ ⇒ 用户）；查不到记录就写「取不到」——**不猜**。 */
  function jumpRow(rec) {
    var out = '<div class="jr" data-anim="13" data-anim-lbl="动13"><span class="lab">关联跳转</span>'
    if (!rec) {
      out += '<span class="jm dead">没有 traces 记录 ⇒ 路径取不到，跳转目标也就取不到（不猜）</span>'
    } else {
      var p = rec[1] || ''
      var hits = []
      if (p.indexOf('/order/') >= 0) hits.push(['订单详情', p])
      if (p.indexOf('/goods/') >= 0) hits.push(['商品详情', p])
      if (/\/(login|user|auth|identity)/.test(p)) hits.push(['用户详情', p])
      if (!hits.length) hits.push(['原始路径 ' + p, p])
      out += hits.slice(0, 3).map(function (x) {
        return '<span class="jm" data-jump="' + esc(x[1]) + '" title="由选中号的路径推断：' + esc(x[1]) + '">' +
          esc(x[0]) + ' ↗</span>'
      }).join('')
    }
    return out + '<span class="hint">由选中号的 traces 记录推断 · 本轮只画结构、不真跳</span></div>'
  }
  function paintDrawer() {
    var host = document.getElementById('ev-drawer')
    if (!host) return
    host.setAttribute('data-seltr', S.sel)
    var rec0 = S.sel < 0 ? null : (DATA.trRec[S.sel] || null)
    var hd
    if (S.sel < 0) {
      hd = '<div class="hd"><b><span class="idx">⑤</span> 链路展开</b>' +
        '<span class="mono">当前没选中任何一行 —— 点 ③ 里任一行，看它的链路</span></div>'
    } else {
      var rec = rec0
      var mine = [], kids = []
      for (var i = 0; i < DATA.rows.length; i++) if (DATA.rows[i][7] === S.sel) mine.push(DATA.rows[i])
      for (var j = 0; j < mine.length; j++) if (mine[j][3] >= 0) kids.push(mine[j])
      var pv
      if (rec) {
        pv = esc(rec[4]) + ' 请求开始 ▸ ' + kids.map(function (r) { return esc(r[4]) }).join(' ▸ ') +
          ' ▸ ' + esc(rec[5]) + ' 请求结束'
      } else {
        /* ⚠️ 易漏项 1 的另一面：起止两行**只来自帧里的 traces**。traces 窗口 300 ≤ 事件窗口 1000，
           老号早被挤出 traces 表 ⇒ 大多数号点开都画不出起止。这不是丢数据，是那条口径的后果。 */
        pv = '— 这一号在本窗<b>查不到 traces 记录</b>（traces 窗口 ' + DATA.stats.traceCap +
          ' ≪ 事件窗口 ' + DATA.stats.cap + '）⇒ 起止两行画不出来；中间 ' +
          kids.length + ' 条 = 本页窗口内带这个 traceId 的事件'
      }
      hd = '<div class="hd"><b><span class="idx">⑤</span> 链路展开</b>' +
        '<span class="mono">选中 <b>' + esc(DATA.trIdx[S.sel] || '—') + '</b> · 同号 ' + mine.length + ' 行已微染</span>' +
        '<span class="st">' + (rec ? esc(rec[3]) : '—') + '</span>' +
        '<span class="mono">' + (rec ? esc(rec[0]) + ' ' + esc(rec[1]) + ' · <b>' + rec[2] + ' ms</b>'
          : '没有 traces 记录 ⇒ 方法 / 路径 / 耗时取不到') + '</span>' +
        '<span class="pv">' + pv + '</span></div>'
    }
    host.innerHTML = hd + jumpRow(rec0) + '<div class="note">⚠️ 起止两行<b>只来自帧里的 traces、不来自事件</b> ⇒ ' +
      '<b>「模拟日常」下 traces 恒为空</b>，那两行会显示「这一次请求的起止还没到」；' +
      '<b>不许因为它空就把 ⑤ 删掉</b> —— 空态也是这一块要说的内容。' +
      '中间那几条 = 本页窗口内带这个 traceId 的事件（按墙钟升序）；' +
      '拉起后 ③ 里同号的行整组微染（动效落点 6）。' +
      '「关联跳转」的目标**由选中号的路径推断**（订单 / 商品 / 用户三选，最多 3 个）；' +
      '查不到 traces 记录就写「取不到」—— <b>不猜</b>。</div>'
  }

  /* ── 交互：手一动就打断自动跟随，回到离底 8px 内自动恢复 ─────────────────── */
  function onManual() { S.auto = false; sync() }
  function onScroll() {
    if (S.animTo !== null) return                    // 动画自己滚出来的事件不算「手动」
    /* ⚠️ `S.xpLock`：展开行开着的时候**跟随锁死**。原因不是洁癖 ——
       插行会让 Chrome 的滚动锚定（scroll anchoring）把 scrollTop 往上顶 73px 以保持画面不动，
       这一顶会触发 scroll 事件 ⇒ 回到 `nearBottom()` ⇒ auto 当场又变回 true，
       「插行期间锁住跟随」就成了句空话（实测：按老写法插完 auto 仍是 true）。 */
    S.auto = !S.xpLock && nearBottom()
    sync()
  }
  function onKey(e) {
    if (e.key === 'End' || e.key === 'PageDown') { setTimeout(onScroll, 0); return }
    onManual()
  }
  function onClick(e) {
    var el = e.target.closest ? e.target.closest('.log-line') : null
    if (!el) return
    /* ── 行内浮层四键 / 可点对象（都**不占列**：浮层绝对定位，对象是行内一个 span）── */
    var xa = e.target.closest ? e.target.closest('[data-xact]') : null
    if (xa) {
      var a = xa.getAttribute('data-xact')
      if (a === 'obj') { setFlt({ obj: el.getAttribute('data-obj') || null }); return }
      if (a === 'trace') { setFlt({ onlyTr: Number(el.getAttribute('data-trace')) }); return }
      if (a === 'expand') { toggleXp(el); return }
      if (a === 'pause') { if (S.paused) resume(); else pause(); return }
      if (a === 'copy') { copyLine(el, xa); return }
    }
    select(Number(el.getAttribute('data-trace')))
  }

  /* ══ ③ 的筛选（用户裁定：L1「点三色条某档 ⇒ 过滤事件流」必须真过滤）══════════
     ⚠️ 筛掉的行是**藏起来**（`display:none`）、不是从 DOM 删掉：DOM 仍封顶
        `DATA.rowMax` 行，翻回来不用重建。DOM 计数因此**不随筛选变**（探针按这条量）。
     ⚠️ 只有「类 / 严重度 / ≥ WARN / 对象 / 只看这条 trace」五样真过滤；
        时间范围 / 源 / 搜索 / 批次是结构占位（② 的脚注写明了）。 */
  var SEV_L = DATA.sevs.map(function (v) { return v.s.charAt(0) })
  var KIND_N = {}
  DATA.kinds.forEach(function (k) { KIND_N[k.k] = k.n })
  function fltOn() {
    var f = S.flt
    return f.sev >= 0 || f.warnUp || !!f.kind || !!f.obj || f.onlyTr !== null
  }
  function fltDesc() {
    var f = S.flt, out = []
    if (f.sev >= 0) out.push('严重度=' + f.sev)
    if (f.warnUp) out.push('≥WARN')
    if (f.kind) out.push('类=' + f.kind)
    if (f.obj) out.push('对象=' + f.obj)
    if (f.onlyTr !== null) out.push('trace=' + f.onlyTr)
    return out.length ? out.join(' ') : '无'
  }
  function passRow(el) {
    var f = S.flt
    if (f.sev >= 0 && !el.classList.contains('lv-' + SEV_L[f.sev])) return false
    if (f.warnUp && el.classList.contains('lv-i')) return false
    if (f.kind && el.getAttribute('data-k') !== f.kind) return false
    if (f.obj && el.getAttribute('data-obj') !== f.obj) return false
    if (f.onlyTr !== null && Number(el.getAttribute('data-trace')) !== f.onlyTr) return false
    return true
  }
  function applyFlt() {
    var on = fltOn()
    if (!on && !S.fltPainted) return
    var kids = list.children, shown = 0
    for (var i = 0; i < kids.length; i++) {
      var el = kids[i]
      if (el.getAttribute('data-xp')) { el.style.display = on ? 'none' : ''; continue }
      var show = passRow(el)
      el.style.display = show ? '' : 'none'
      if (show) shown++
    }
    S.fltShown = shown
    S.fltPainted = on
    syncFltUI()
    /* ⚠️ 走 `sync()` 而不是只 `settle()`：sync 里那句「· 筛中 N 行」也是**筛选的读数**，
       少了它，界面上就只剩「行数 200 / 200」——看着像筛选根本没生效。 */
    sync()
  }
  /* 新追加的那**一批**也要过筛。⚠️ 必须在包层里就藏掉：一折包再藏，
     `wrap.offsetHeight` 量到的就是没筛过的高，滚动动画的目标会偏。 */
  function fltMark(root) {
    var ks = root.children
    for (var i = 0; i < ks.length; i++) ks[i].style.display = passRow(ks[i]) ? '' : 'none'
  }
  function setFlt(patch) {
    for (var k in patch) if (patch.hasOwnProperty(k)) S.flt[k] = patch[k]
    applyFlt()
    return fltDesc()
  }
  function clearFlt() {
    S.flt = { sev: -1, warnUp: false, kind: null, obj: null, onlyTr: null }
    /* ⚠️ 先 `fltPainted = true` 再重算：applyFlt 有一条「没筛选、也没画过就早退」的快路径，
       清筛选如果不走这条全表重算，②/①/④ 的选中态会**留在界面上**（状态清了、画面没清）。 */
    S.fltPainted = true
    applyFlt()
    return fltDesc()
  }
  /* 三处共用一份状态：② 的档位 chips、① 的 mini 三色条、④ 的三条横条 ——
     「联动」的具体含义就是**它们三个永远说同一件事**（点任意一处，另外两处跟着变）。 */
  function syncFltUI() {
    var f = S.flt, i
    var chips = document.querySelectorAll('[data-role="sevchip"]')
    for (i = 0; i < chips.length; i++) chips[i].classList.toggle('on', Number(chips[i].getAttribute('data-s')) === f.sev)
    var segs = document.querySelectorAll('[data-role="sevmini"] i')
    for (i = 0; i < segs.length; i++) segs[i].classList.toggle('pick', Number(segs[i].getAttribute('data-sev')) === f.sev)
    var bars = document.querySelectorAll('[data-role="sevbars"] .ev-bar')
    for (i = 0; i < bars.length; i++) bars[i].classList.toggle('pick', Number(bars[i].getAttribute('data-sev')) === f.sev)
    var wu = document.querySelector('[data-role="warnup"]')
    if (wu) wu.classList.toggle('on', !!f.warnUp)
    var krows = document.querySelectorAll('.ev-rail .row[data-f="kind"]')
    for (i = 0; i < krows.length; i++) {
      var k = krows[i].getAttribute('data-k')
      var sw = krows[i].querySelector('.sw')
      if (sw) sw.classList.toggle('off', f.kind ? k !== f.kind : !KIND_N[k])
    }
    var all = document.querySelector('[data-role="allrow"] .sw')
    if (all) all.classList.toggle('off', !!(f.kind || f.obj || f.onlyTr !== null))
    var ob = document.querySelector('[data-role="objbox"]')
    if (ob) {
      ob.textContent = f.obj ? f.obj
        : (f.onlyTr !== null ? '只看 trace ' + (DATA.trIdx[f.onlyTr] || '') : '点 ③ 里的对象 ⇒ 填到这里')
    }
    var host = document.getElementById('ev-list')
    if (host) host.setAttribute('data-flt', fltDesc())
  }
  /* ══ ③ 上下文展开（落点 11）：在**本行下面插一行**原始字段 ═══════════════════
     ⚠️ 插行 = 改了列表高度 ⇒ 先**锁住自动跟随**（否则新事件一到，插行当场被顶走看不见）。
        收起时按「离底 8px 内自动恢复」的老规矩还回去。 */
  function toggleXp(line) {
    if (line.getAttribute('data-xp')) {          // 点展开行**本身** ⇒ 收起（不然会再插一行）
      list.removeChild(line)
      S.xpLock = false
      S.auto = nearBottom()
      sync()
      return 0
    }
    var nx = line.nextElementSibling
    if (nx && nx.getAttribute('data-xp')) {
      list.removeChild(nx)
      S.xpLock = false
      S.auto = nearBottom()
      sync()
      return 0
    }
    var old = list.querySelector('[data-xp]')
    if (old) list.removeChild(old)
    var el = document.createElement('div')
    el.className = 'log-line xp'
    el.setAttribute('data-xp', '1')
    el.setAttribute('data-xact', 'expand')
    el.setAttribute('title', '再点一下收起')
    /* 明细行跟着**父行**缩进（它既不是事件、也不参与树，但左边不该跳到顶上） */
    el.style.setProperty('--pc', line.style.getPropertyValue('--pc') || 'calc(2ch + 8px)')
    el.innerHTML = xpText(line)
    line.insertAdjacentElement('afterend', el)
    S.auto = false                       // 插行期间锁住跟随
    S.xpLock = true
    sync()
    return 1
  }
  function xpText(line) {
    var g = function (s) { var e = line.querySelector(s); return e ? e.textContent : '' }
    return '<div class="xr"><span class="lab">原始字段</span>' +
      '<span class="kv">时刻<b class="mono">' + esc(g('.tm')) + '</b></span>' +
      '<span class="kv">等级<b>' + (g('.lv') || '（空 · req 行）') + '</b></span>' +
      '<span class="kv">类<b>' + esc(g('.md')) + '</b></span>' +
      '<span class="kv">拍序<b>' + esc(line.getAttribute('data-t')) + '</b></span>' +
      '<span class="kv">前导<b class="mono">' + esc(g('.pre')) + '</b></span>' +
      '<span class="kv">traceId<b>' + esc(g('.tr') || '—') + '</b></span></div>' +
      '<div class="xr"><span class="kv">标题<b>' + esc(g('.ms b')) + '</b></span>' +
      '<span class="kv">对象<b>' + esc(g('.ob') || '—') + '</b></span>' +
      '<span class="kv">明细<b>' + esc(g('.dt') || '—') + '</b></span>' +
      '<span class="lab">（这些都从本行 DOM 读出来，不另造数据）</span></div>'
  }
  /* ══ 复制本行：**真去写剪贴板**，写不成就把原因写在按钮上 ══════════════════
     ⚠️ 草图是 `file://` 打开的 ⇒ 多半拿不到剪贴板权限。这里不假装成功：
        失败时按钮变「复制失败」、`title` 写清原因（探针据此断言「要么成功、要么写明原因」）。 */
  function copyLine(line, btn) {
    var txt = ['.tm', '.lv', '.md', '.ms b', '.ob', '.dt', '.tr'].map(function (s) {
      var e = line.querySelector(s); return e ? e.textContent : ''
    }).filter(Boolean).join('  ')
    var back = function (ok, why) {
      S.lastCopy = (ok ? 'ok: ' : 'fail: ') + why
      btn.textContent = ok ? '已复制' : '复制失败（见 title）'
      btn.setAttribute('title', why)
      setTimeout(function () { btn.textContent = '复制本行'; btn.setAttribute('title', '复制这一行的字段') }, 1800)
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(txt).then(function () { back(true, txt) },
        function (er) { back(false, '草图在 file:// 下拿不到剪贴板权限（' + ((er && er.name) || 'NotAllowedError') + '）—— 落地接 clipboard') })
    } else back(false, '本环境没有 navigator.clipboard')
  }
  /* ══ ④ 榜单切档（落点 12）+ ①/④ 的严重度入口 + 联动 L2 的夹具 ═══════════════ */
  function topTab(kind) {
    var host = document.getElementById('ev-top-lst')
    if (!host) return kind
    host.innerHTML = topRows(kind)
    var tabs = document.querySelectorAll('[data-role="toptabs"] .tb')
    for (var i = 0; i < tabs.length; i++) tabs[i].classList.toggle('on', tabs[i].getAttribute('data-top') === kind)
    S.topTab = kind
    return kind
  }
  /* L2：错误率 > 0 ⇒ 读数脉冲两次 + 变红。本窗 warn/error 全 0 ⇒ **天然不可达**，
     所以给探针一个夹具：注入「额外来了 w 条 warn、e 条 error」，量完 `reset()` 复位。
     ⚠️ 这是**探针夹具**，不是页面功能：注入的是**计数**，不往 ③ 里塞假行（塞了截图就假了）。 */
  function sevAggNow() {
    var b = DATA.sevAgg, inj = S.inj
    if (!inj) return b
    return { info: b.info, warn: b.warn + inj.w, error: b.error + inj.e, total: b.total + inj.w + inj.e }
  }
  function paintSev() {
    var ag = sevAggNow(), bad = ag.warn + ag.error
    var rate = ag.total ? (bad / ag.total) * 100 : 0
    var cell = document.querySelector('.ev-status .cell.err')
    if (cell) {
      cell.classList.toggle('alert', bad > 0)
      var b = cell.querySelector('b')
      if (b) b.innerHTML = rate.toFixed(1) + '<i>%</i>'
      var sp = cell.querySelector('span')
      if (sp) sp.textContent = bad > 0 ? '错误率 warn+error（' + ag.warn + ' / ' + ag.error + '）' : '错误率 warn+error'
    }
    var segs = document.querySelectorAll('[data-role="sevmini"] i')
    var n = [ag.info, ag.warn, ag.error]
    for (var i = 0; i < segs.length; i++) segs[i].style.flex = n[i] + ' 0 auto'
    var chips = document.querySelectorAll('[data-role="sevchip"]')
    for (var j = 0; j < chips.length; j++) {
      var lab = DATA.sevs[j] ? DATA.sevs[j].label : ''
      // 甲 的 chips 带数字、丙 的不带（数字只在仪表带出现一次）
      chips[j].textContent = (chips[j].getAttribute('data-withn') === '1') ? lab + ' ' + num(n[j]) : lab
    }
    var bars = document.querySelectorAll('[data-role="sevbars"] .ev-bar')
    var mx = Math.max(n[0], n[1], n[2]) || 1
    for (var k = 0; k < bars.length; k++) {
      var numEl = bars[k].querySelector('.num'), fill = bars[k].querySelector('.fill')
      if (numEl) numEl.textContent = num(n[k])
      if (fill) fill.style.width = ((n[k] / mx) * 100).toFixed(1) + '%'
    }
    return { rate: rate, bad: bad, alert: bad > 0 }
  }

  function stopRaf() { if (S.raf) { cancelAnimationFrame(S.raf); S.raf = 0 } S.animTo = null }
  function pause() { S.paused = true; stopRaf(); sync() }
  /* ⚠️ 显式点「继续」= 用户的明确动作 ⇒ 连展开行的跟随锁一起解开（锁是给插行用的，
     不是给用户上的镣铐）。 */
  function resume() { S.paused = false; S.xpLock = false; S.auto = true; scrollTo_(maxTop(), DUR); sync() }
  function bottom() { S.auto = true; S.paused = false; scrollTo_(maxTop(), DUR); sync() }
  function append(n) { var s = []; for (var i = 0; i < n; i++) s.push(nextRow()); push(s); return s.length }
  function replay() {
    stopRaf()
    if (S.tickT) { clearInterval(S.tickT); S.tickT = 0 }
    list.innerHTML = ''
    /* ⚠️ 从**第一拍有事件**处起跳（不写死 0）：本窗实测前 38 拍真的一拍 0 条
       （采集开始时应用还没热），老实从第 0 拍起 ⇒ 点一次 ▶ 要空等 38 秒才见第一行。
       跳过的都是**空拍**，一行真数据都不少；这件事写在 ③ 的说明里。 */
    var head = DATA.replayHead || 0
    S.at = 0; S.tick = head - 1; S.fed = 0; S.queue = []; S.paused = false; S.auto = true
    S.tickT = setInterval(tickOnce, 1000)
    tickOnce()
  }
  function tickOnce() {
    if (S.paused) return
    S.tick++
    var end = DATA.cuts[S.tick]
    if (end == null) { if (S.tickT) { clearInterval(S.tickT); S.tickT = 0 } sync(); return }
    var s = DATA.rows.slice(S.at, end)
    S.at = end
    if (s.length) push(s)
    sync()
  }
  function clear() {
    if (S.tickT) { clearInterval(S.tickT); S.tickT = 0 }
    stopRaf(); list.innerHTML = ''; S.at = 0; S.tick = -1; S.fed = 0; S.queue = []; S.paused = false; sync()
  }
  /* 初始态：直接把**末 200 行**铺好并停在底部（= 你会看到的那一屏）。
     ⚠️ 顺序要紧：先 `paint()`（⑤ 落内容、会把事件流压矮）**再**贴底 ——
     反过来会停在离底一百多像素的地方（③ 到 ⑤ 之间那一段）。 */
  /* 复位成「刚打开」那一屏（末 N 行 + 默认选中 + 贴底）。探针收尾用：
     ▶重放 会起 setInterval 一直喂，不复位的话截图会拍到「重放中」的半截画面。 */
  function reset() {
    clear()
    S.auto = true; S.sel = DATA.sel.tr
    S.inj = null; paintSev()                                   // L2 的注入读数复位
    S.flt = { sev: -1, warnUp: false, kind: null, obj: null, onlyTr: null }
    S.fltPainted = true        // 强制走一遍全表重算 ⇒ ②/①/④ 的选中态也一并刷回去
    renderLast(DATA.rowMax)
    clearFlt()
    S.topTab = null; paintSev(); topTab('obj')                 // ④ 回到默认档
  }
  function renderLast(n) {
    var s = DATA.rows.slice(Math.max(0, DATA.rows.length - n))
    list.innerHTML = s.map(logLine).join('')
    decorate()                                   // 首屏也要按窗口算一遍前导符与缩进
    S.at = DATA.rows.length; S.fed = s.length
    paint()                      // 内含 paintDrawer + sync + settle
    list.scrollTop = maxTop()    // 再钉一次：⑤ 刚落地，这一次是最终布局
  }
  function el_(role) { return document.querySelector('[data-role="' + role + '"]') }
  function sync() {
    var a = el_('auto'); if (a) a.textContent = '自动跟随：' + (S.auto && !S.paused ? '开' : '关')
    var p = el_('pausetxt'); if (p) p.textContent = S.paused ? '▶ 继续' : '⏸ 暂停'
    var r = el_('rows')
    if (r) {
      /* ⚠️ 「行数」数的是**真行**（不含展开行），且**不随筛选变** ——
         筛掉的只是藏起来，DOM 里还是那些行、封顶也还是 200。筛选命中数单独报。 */
      r.textContent = '行数 ' + drawnCount() + ' / ' + DATA.rowMax +
        '（共喂 ' + num(S.fed) + ' 行）· 同号 ' + S.hit + ' 行' +
        (fltOn() ? ' · 筛中 ' + S.fltShown + ' 行' : '')
    }
    settle()
  }
  /* ⚠️ 贴底必须排在**改完字之后**：上面那三处字一变，③ 表头就可能多折一行、
     ⑤ 也可能换成一行的链接预览 ⇒ 事件流被压矮、**底部跟着下移**。
     反过来的话「刚打开」就停在离底二十几像素的地方，随后追加时 nearBottom() 判 false、
     自动跟随当场失效（甲 上真踩过，丙 不折行所以没露）。幂等：已经在底就什么都不做。 */
  function settle() {
    if (S.auto && S.animTo === null && maxTop() - list.scrollTop > 0.5) list.scrollTop = maxTop()
  }
  function state() {
    var last = null, ks = list.children
    for (var i = ks.length - 1; i >= 0; i--) if (!ks[i].getAttribute('data-xp')) { last = ks[i]; break }
    return {
      drawn: drawnCount(), cap: DATA.rowMax, fed: S.fed, at: S.at, queued: S.queue.length,
      auto: S.auto, paused: S.paused, animating: S.raf !== 0,
      anims: S.anims, starts: S.starts, lastMs: S.lastMs,
      scrollTop: Math.round(list.scrollTop), scrollHeight: list.scrollHeight,
      clientHeight: list.clientHeight, maxTop: maxTop(), bar: barPx(), dur: DUR,
      chunk: CHUNK, coalesce: COALESCE, sel: S.sel, hit: S.hit, tick: S.tick,
      flt: fltDesc(), fltOn: fltOn(), shown: S.fltShown, dom: ks.length,
      xp: list.querySelectorAll('[data-xp]').length,
      topTab: S.topTab, inj: S.inj, lastCopy: S.lastCopy,
      rowH: last ? Math.round(last.offsetHeight) : 0
    }
  }
  /* ── 文档级交互（② 的筛选、④ 的三个入口、悬停 L3）──────────────────────── */
  function onDocClick(e) {
    var t = e.target
    var cl = function (sel) { return t.closest ? t.closest(sel) : null }, f = S.flt
    var row = cl('.ev-rail .row')
    if (row) {
      var rf = row.getAttribute('data-f')
      if (rf === 'all') { clearFlt() }
      else if (rf === 'kind') { var k = row.getAttribute('data-k'); setFlt({ kind: f.kind === k ? null : k }) }
      return
    }
    var chip = cl('[data-role="sevchip"]')
    if (chip) { var i = Number(chip.getAttribute('data-s')); setFlt({ sev: f.sev === i ? -1 : i }); return }
    if (cl('[data-role="warnup"]')) { setFlt({ warnUp: !f.warnUp }); return }
    if (cl('[data-role="clear"]')) { clearFlt(); return }
    /* ① 的 mini 三色条 与 ④ 的三条横条 —— 同一条豁免链上的两个入口（L1）*/
    var seg = cl('[data-role="sevmini"] i') || cl('[data-role="sevbars"] .ev-bar')
    if (seg) {
      var si = Number(seg.getAttribute('data-sev'))
      setFlt({ sev: f.sev === si ? -1 : si })
      return
    }
    var tb = cl('[data-role="toptabs"] .tb')
    if (tb) { topTab(tb.getAttribute('data-top')); return }
  }
  /* L3（可选档，已加）：悬停某档 ⇒ ③ 里同档的行短暂高亮。只改背景、不加边框。 */
  function onHover(e) {
    var t = e.target
    var seg = t.closest ? (t.closest('[data-role="sevmini"] i') || t.closest('[data-role="sevbars"] .ev-bar')) : null
    var sev = seg ? Number(seg.getAttribute('data-sev')) : -1
    if (sev === S.hlSev) return
    if (S.hlSev >= 0) list.classList.remove('hl-' + SEV_L[S.hlSev])
    S.hlSev = sev
    if (sev >= 0) list.classList.add('hl-' + SEV_L[sev])
  }
  function mount() {
    list = document.getElementById('ev-list')
    if (!list) return
    list.addEventListener('click', onClick)
    list.addEventListener('wheel', onManual, { passive: true })
    list.addEventListener('touchstart', onManual, { passive: true })
    list.addEventListener('keydown', onKey)
    list.addEventListener('scroll', onScroll)
    document.addEventListener('click', onDocClick)
    document.addEventListener('mouseover', onHover)
    var bs = document.querySelectorAll('[data-act]')
    for (var i = 0; i < bs.length; i++) {
      bs[i].addEventListener('click', (function (b) {
        return function () {
          var a = b.getAttribute('data-act')
          if (a === 'replay') replay()
          else if (a === 'pause') { if (S.paused) resume(); else pause() }
          else if (a === 'bottom') bottom()
        }
      })(bs[i]))
    }
    renderLast(DATA.rowMax)
  }
  return {
    mount: mount, append: append, replay: replay, pause: pause, resume: resume,
    bottom: bottom, clear: clear, select: select, state: state, paint: paint, reset: reset,
    /* 探针用的手 */
    setFlt: setFlt, clearFlt: clearFlt, applyFlt: applyFlt, fltDesc: fltDesc, fltOn: fltOn,
    toggleXp: toggleXp, topTab: topTab, topTabNow: function () { return S.topTab },
    /* L2 的夹具：注入的是**读数**，不往 ③ 里塞假行（塞了截图就假了）*/
    injectSev: function (w, e) { S.inj = { w: w || 0, e: e || 0 }; return paintSev() },
    clearInj: function () { S.inj = null; return paintSev() },
    sevNow: sevAggNow, sevPainted: paintSev,
    opts: { DUR: DUR, CHUNK: CHUNK, COALESCE: COALESCE, NEAR: NEAR }
  }
})()


/* ── ③ 顶部 / ④ 里的速率折线（tail -f 的心跳图）─────────────────────────── */
function spark() {
  var r = DATA.rate, mx = Math.max.apply(null, r) || 1
  var pts = r.map(function (v, i) {
    var x = (i / (r.length - 1)) * 100
    var y = 100 - (v / mx) * 92
    return x.toFixed(2) + ',' + y.toFixed(2)
  }).join(' ')
  var svg = '<svg viewBox="0 0 100 100" preserveAspectRatio="none" height="38" role="img" aria-label="每秒事件速率折线">' +
    '<rect x="0" y="0" width="100" height="100" fill="none" stroke="#e2e2e2" stroke-width="0.4"/>' +
    '<polyline points="' + pts + '" fill="none" stroke="#7a7a7a" stroke-width="0.9" vector-effect="non-scaling-stroke"/>' +
    '</svg>'
  /* 动效落点 3：折线随新事件推进 */
  return '<div class="ev-spark" data-anim="3" data-anim-lbl="动3">' + svg +
    '<div class="cap">每秒事件速率（本窗 60 拍 · 峰值 ' + mx.toFixed(1) + ' 条/秒 · 首拍 ' +
    r[0].toFixed(1) + '）—— 尾部随每一拍新事件右推</div></div>'
}

/* ── ④ 严重度分布（横条，不是环图）────────────────────────────────────────
   ⚠️ 这三条**同时是联动 L1 的入口**（点某一档 ⇒ 勾上 ② 的同档 + 真过滤 ③）和 L3 的入口
      （悬停某一档 ⇒ ③ 里同档的行短暂高亮）。① 的 mini 三色条是同一件事的紧凑版 ——
      「就近」要的不是重复，是**视线短**：调了 ① 的条，眼睛不用跑远就知道 ③ 变成了什么。 */
function sevBars() {
  var mx = Math.max.apply(null, DATA.sevs.map(function (v) { return v.n })) || 1
  var h = '<div class="ev-bars sev" data-role="sevbars">'
  DATA.sevs.forEach(function (v, i) {
    var w = (v.n / mx) * 100
    h += '<div class="ev-bar" data-sev="' + i + '" title="点一下 ⇒ 只看这一档（联动 L1）· 悬停 ⇒ ③ 同档高亮（L3）">' +
      '<span class="nm">' + esc(v.label) + '</span>' +
      '<span class="track"><span class="fill" style="width:' + w.toFixed(1) + '%"></span></span>' +
      '<span class="num">' + num(v.n) + '</span></div>'
  })
  return h + '</div>'
}
/* ── ④ 类计数（横条）—— **分两组**：业务 9 / 设施 3，与 ② 的清单同序同分组 ────── */
function kindBars() {
  var mx = Math.max.apply(null, DATA.kinds.map(function (k) { return k.n })) || 1
  var row = function (k) {
    var w = (k.n / mx) * 100
    return '<div class="ev-bar" title="' + esc(k.label) + ' ' + num(k.n) + ' 条' +
      (k.grp === 'fac' ? '（后端面待落地）' : '') + '">' +
      '<span class="nm">' + esc(k.label) + '</span>' +
      '<span class="track"><span class="fill" style="width:' + w.toFixed(1) + '%"></span></span>' +
      '<span class="num">' + num(k.n) + '</span></div>'
  }
  var biz = DATA.kinds.filter(function (k) { return k.grp === 'biz' })
  var fac = DATA.kinds.filter(function (k) { return k.grp === 'fac' })
  return '<div class="ev-bars" data-anim="4,7" data-anim-lbl="动4·7">' +
    biz.map(row).join('') +
    '<div class="sep"><span>设施 ' + fac.length + ' 类（后端面待落地）</span></div>' +
    fac.map(row).join('') + '</div>'
}
/* ── ④ 读数 ────────────────────────────────────────────────────────────────
   ⚠️ 错误率**不在这里**：它的家在 ①（与严重度三色条相邻 = 就近），④ 不再重复一遍。
      这里给的是 ④ 自己的四个计数 + 耗时分位（P50/P95/P99 都从本窗 traces 现算）。 */
function readout() {
  var l = DATA.lat
  return '<div class="ev-read">' +
    '<span>本拍事件<i>' + num(DATA.clock.fresh) + '</i></span>' +
    '<span>本拍链路<i>' + num(DATA.clock.freshTrace) + '</i></span>' +
    '<span>窗口合计<i>' + num(DATA.stats.events) + '</i></span>' +
    '<span>丢帧<i>' + num(DATA.stats.droppedTotal) + '</i></span>' +
    '<span>耗时 P50 / P95 / P99<i>' + l.p50 + ' / ' + l.p95 + ' / ' + l.p99 + ' ms</i></span>' +
  '</div>'
}
/* ── ④ Top-N 榜（三档可切：对象 / 慢请求 / 错误）────────────────────────────
   ⚠️ 错误榜本窗**恒空**（error 0 条）⇒ 给的是**空态**，不是假数据：
      「本窗 error 0 条 ⇒ 榜单为空」这句话本身就是这一块要说的内容。
   ⚠️ 切档是**真行为**（落点 12）：换档会把列表重排 —— 不是三个静态块并排。 */
var TOP_TAB = [['obj', '对象'], ['slow', '慢请求'], ['err', '错误']]
function topRows(kind) {
  var t = DATA.top
  if (kind === 'err') {
    if (!t.err.length) {
      /* ⚠️ 说清「注入不补榜单」：验证 L2 用的注入只改 ① 的错误率**读数**，
         不往这个榜里塞一行假记录（塞了这张截图就假了）。 */
      return '<div class="empty">本窗 <b>error 0 条</b> ⇒ 榜单为空（不给假数据）。' +
        '落地后接了 warn/error 才有序；本文验 L2 的<b>注入只改 ① 的读数</b>，不往本榜塞假行。</div>'
    }
    return t.err.map(function (x) {
      return '<div class="tr1"><span class="nm">' + esc(x[0]) + '</span><span class="v">' + esc(x[1]) + '</span><span class="v">1</span></div>'
    }).join('')
  }
  if (kind === 'slow') {
    return t.slow.map(function (x) {
      return '<div class="tr1"><span class="nm" title="' + esc(x[0]) + '">' + esc(x[0]) + '</span>' +
        '<span class="v">' + x[1] + ' ms</span><span class="v">' + x[2] + '</span></div>'
    }).join('')
  }
  return t.obj.map(function (x) {
    return '<div class="tr1"><span class="nm" title="' + esc(x[0]) + '">' + esc(x[0]) + '</span>' +
      '<span class="v">' + num(x[1]) + ' 条</span><span class="v"></span></div>'
  }).join('')
}
function topList() {
  return '<div class="ev-top" data-anim="12" data-anim-lbl="动12">' +
    '<div class="tabs" data-role="toptabs">' +
      TOP_TAB.map(function (x, i) {
        return '<span class="tb' + (i === 0 ? ' on' : '') + '" data-top="' + x[0] + '">' + x[1] + '</span>'
      }).join('') +
    '</div>' +
    '<div class="lst" id="ev-top-lst">' + topRows('obj') + '</div>' +
    '<div class="cap">榜按<b>本页窗口内累计</b>算；慢请求取 traces 记录里的最大耗时（同路径聚合）</div>' +
  '</div>'
}

/* ── ⑤ 链路展开（**塌成一条**，同 txt 线框图：默认 48px，点一下才拉起）────────
   窗口化之后它不能再默认展开 —— 一整条链竖向排开就要 150px，六区一屏就装不下。
   链的内容改成**行内预览**（起点 ▸ 中间各跳 ▸ 终点），既保留「这是一条链」的结构主张，
   又只占一行。
   ⚠️ 这一轮起 ⑤ 是**活的**：内容跟着 ③ 里选中的号走（EV 的 `paintDrawer` 现算），
   这里只出一个**宿主**（`[data-anim="6"]` 落在宿主上，内容由 EV 填）——
   宿主是稳定的，选一次换一次内容，标记不会跟着内容被换掉。
   ⚠️ 落点 6 的**效果**在 ③ 的行上（整组微染），**触发**在 ⑤；标记留在这里，
   一个元素一个标号 —— 这样「数一数是不是 8 个」才数得清。 */
function drawerHost() {
  return '<div class="ev-drawer" id="ev-drawer" data-blk="drawer" data-anim="6" data-anim-lbl="动6"' +
    ' data-seltr="' + DATA.sel.tr + '"></div>'
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
  /* 窗口化：压成**一行**（标题 + 两组开关 + 一行小字）。原来那个两行标题在这里放不下。 */
  return '<div class="ev-switch" data-blk="switch">' +
    '<b><span class="idx">⑥</span> 开关栏</b>' + grp('防护闸', P) + grp('注入', I) +
    '<span class="n">3a.6 乙案引入 · 本页只占位（本轮不接行为）· 虚线 = 后端 WIRED 清单里没有它</span>' +
  '</div>'
}

/* ── 两版装配 ───────────────────────────────────────────────────────────────
   两版的**外壳完全相同**：`.ev-frame`（一个大框）> ① / 条件块 / `.ev-body` / ⑤ / ⑥。
   差别只在 `.ev-body` 里：甲 三列（②③④），丙 两列（②｜右列：④ 仪表带 + ③ 事件流）。
   ⚠️ 中段是**唯一伸缩的一行**（`.ev-body { flex:1 }`），它一伸一缩，整页高不变。 */
function renderJia() {
  return '<section class="ev-jia"><div class="ev-frame">' +
    statusBar() + flashSlot() +
    '<div class="ev-body">' +
      rail(true) +
      '<div class="ev-col" data-blk="stream">' + spark() + stream() + '</div>' +
      '<div class="ev-col" data-blk="live">' +
        '<div class="ev-h2 ev-fix"><b><span class="idx">④</span> 实时统计</b>' +
          '<span class="n">Top-N 榜 / 类计数 / 严重度 / 读数</span></div>' +
        '<div class="ev-live-body">' +
          '<div class="ev-sub">Top-N 榜（三档可切）</div>' + topList() +
          '<div class="ev-sub">类计数 · 业务 9 + 设施 3（本页窗口内累计）</div>' + kindBars() +
          '<div class="ev-sub">严重度分布（点某一档 ⇒ 过滤 ③；悬停 ⇒ ③ 同档高亮）</div>' + sevBars() +
          '<div class="ev-sub">读数（错误率在 ①，这里不重复）</div>' + readout() +
        '</div>' +
      '</div>' +
    '</div>' +
    drawerHost() + switchBar() +
  '</div></section>'
}
function renderBing() {
  return '<section class="ev-bing"><div class="ev-frame">' +
    statusBar() + flashSlot() +
    '<div class="ev-body">' +
      rail(false) +
      '<div class="righter">' +
        '<div class="band" data-blk="band">' +
          '<div class="cell"><span class="ttl"><span class="idx">④</span> 仪表带 · 每秒事件速率</span>' + spark() + '</div>' +
          '<div class="cell"><span class="ttl">类计数 · 业务 9 + 设施 3（窗口内累计）</span>' + kindBars() + '</div>' +
          '<div class="cell"><span class="ttl">严重度分布 · 读数</span>' + sevBars() + readout() + '</div>' +
          '<div class="cell"><span class="ttl">Top-N 榜（三档可切）</span>' + topList() + '</div>' +
        '</div>' +
        '<div class="ev-col" data-blk="stream">' + stream() + '</div>' +
      '</div>' +
    '</div>' +
    drawerHost() + switchBar() +
  '</div></section>'
}

function render(variant) {
  if (!DATA) { document.body.insertAdjacentHTML('afterbegin', '<p style="padding:16px">DATA 未生成</p>'); return }
  document.getElementById('app').innerHTML = (variant === 'bing' ? renderBing() : renderJia())
  /* 铺 ③ 的行、接上行为（追加 / 平滑上移 / 打断 / 暂停）。**
     必须在 innerHTML 之后** —— 那一刻才有 #ev-list 这个节点。 */
  EV.mount()
  /* 判据列表进页脚 `<details>`（**在 100vh 之内**，折叠时只有一行高 ⇒ 整页仍不滚）。
     它**不是页面的一部分**，是给验收看的；数字由数据脚本现算。 */
  var f = document.getElementById('judge')
  if (f) f.innerHTML = DATA.judge[variant === 'bing' ? 'bing' : 'jia']
}

window.render = render
/* 行为契约：第 ⑥ 步细化正式 UI 时，③ 这几条行为要原样搬过去（不是重画）。
   探针也用它 —— 「上移流不流畅」「手动滚打不打断」这类事只能**跑起来量**，看静态图看不出来。 */
window.__evStream = EV
window.__evWired = { kinds: DATA.kinds.length, rows: DATA.rows.length, rowMax: DATA.rowMax, sel: DATA.sel.tr }

