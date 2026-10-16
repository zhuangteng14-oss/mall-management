/* ══════════════════════════════════════════════════════════════════════════════
   _struct-wire.js —— 3a.2b（结构重做）三版结构草图的**共用真数据 + 灰阶图示意**

   ⚠️ 数据是真的，不是我编的。
   **来源：`_work/3a2b/frames.ndjson`** —— 2026-10-16 对 dev 后端真跑的一场负载里，
   `probe-b7a2b-capture-frames.mjs` 从 SSE 流上**逐字抓下来的 60 帧**（后端以
   `--mall.observe.sse.max-ticks=60` 推满 60 拍后**正常收尾**，不是中途掐断）。
   DATA 块由 `scripts/probes/probe-b7a2b-wire-data.mjs` **生成**（标记之间勿手改）。

   ⚠️ 这一版**整体换掉了**上一版的数据（上一版是 `_work/3a2b/snapshot.json` 里的 7 拍
   假流 DOM 快照）。换的不只是「多了几张图」，而是**每一条序列**都换了 —— 所以共享 x 轴
   这类老断言必须重跑，不能只验新图。三处可直接看出来的差别：
     · 锁名从假流的 3 把（`goods_sku` / `order_no` / `cart_item`）变成真的 **2 把**
       （`order-add` / `order-pay`）—— 热力图行数因此是 2；
     · x 轴从 7 个点变成 **60 个点**（真窗口）；
     · 有些字段在真健康窗口里**恒 0**（`errors5xx` / `l2Degraded` / `locks[].timeouts` …）
       ⇒ 硬门禁把它们拦在图外，**不是前端漏了**。逐字段的「非零帧数 / 60」见
       `docs/agent/evidence/c3-block7/b7a2b-coverage.txt`。

   ⚠️ 数据的限度：这是一场**健康窗口** —— 没有 5xx、没有锁超时、`health.level` 60 拍恒 `ok`。
   所以「没发生」是结论，不是缺失；但也**不能**据此说「系统在任何负载下都健康」。

   ⚠️ 画法：内联 SVG 灰阶示意图。**没有** ECharts、**没有** CDN、**没有**主题 token ——
   所以双击即开、断网也开。样式一概不画（无品牌色 / 无圆角 / 无过渡 / 无动效）。
   ══════════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict'

  /* @@WIRE-DATA:BEGIN —— 由 scripts/probes/probe-b7a2b-wire-data.mjs 从真帧生成，勿手改 @@ */
  var DATA = {
    /* 状态带 = 本窗最后一拍 */
    header: { health: "健康", err5xx: "0.0%", err4xx: "0.0%",
      phase: "已连接", seq: 59, win: 60, tick: "17:10:05",
      source: "正常（后端真流）", sourceAlt: "模拟日常" },
    /* 判据：健康态也可能非空（后端填的是正面理由）*/
    reasons: ["L1 命中率 100.0%"],
    /* 4 张 KPI。delta 为 '' = 分母为 0 ⇒ 不画箭头、退回 hint（锁等待恒 0 走的就是这支）*/
    kpis: [
      {"key":"qps","label":"QPS","value":"43.7","unit":"次/秒","delta":"17.2%","dl":"较 60 秒均值","hint":"本拍请求 44 次","series":"qps"},
      {"key":"p99","label":"P99 延迟","value":"83","unit":"ms","delta":"10.7%","dl":"较 60 秒均值","hint":"全站分位数的上界近似（各序列取最大）","series":null},
      {"key":"hit","label":"缓存命中率","value":"100.0","unit":"%","delta":"11.9%","dl":"较 60 秒均值","hint":"L1 命中 / (命中 + 未命中)","series":"hit"},
      {"key":"lock","label":"锁等待次数","value":"0","unit":"次","delta":"","dl":"较 60 秒均值","hint":"全部锁名本拍 timeouts 之和","series":null}
    ],

    /* ── 60 拍共用 x 轴 + 各序列 ─────────────────────────────────────── */
    x: ["17:09:05","17:09:06","17:09:07","17:09:08","17:09:09","17:09:10","17:09:11","17:09:12","17:09:13","17:09:14","17:09:15","17:09:16","17:09:17","17:09:18","17:09:19","17:09:20","17:09:21","17:09:22","17:09:23","17:09:24","17:09:25","17:09:26","17:09:27","17:09:29","17:09:30","17:09:31","17:09:32","17:09:33","17:09:34","17:09:35","17:09:36","17:09:37","17:09:38","17:09:39","17:09:40","17:09:41","17:09:42","17:09:43","17:09:44","17:09:45","17:09:46","17:09:47","17:09:48","17:09:49","17:09:50","17:09:51","17:09:52","17:09:53","17:09:54","17:09:55","17:09:56","17:09:57","17:09:58","17:09:59","17:10:00","17:10:01","17:10:02","17:10:03","17:10:04","17:10:05"],
    /* 原图纵轴（QPS）；KPI 与微条用这一条 */
    qps: [0,47.517,40.589,34.479,35.501,35.517,32.883,43.35,41.512,40.223,29.541,37.653,37.567,28.613,43.383,51.175,32.594,37.397,31.901,32.478,39.866,44.474,30.879,53.751,35.735,31.939,30.842,39.448,30.811,50.775,42.858,34.699,36.433,30.584,35.474,39.858,42.695,42.876,37.89,31.946,34.601,32.72,33.894,40.516,52.371,32.499,31.528,33.69,36.575,37.859,47.526,35.528,46.224,36.473,30.909,31.647,36.871,36.674,47.896,43.698],
    /* QPS 堆叠三段：成功 / 4xx / 5xx —— 三段同单位（次/秒）⇒ 加起来 = 原图总高。
       ⚠️ qps5xx 整窗 0 ⇒ **不画**（硬门禁：全零字段不上图，画出来就是贴地假线）*/
    qpsOk: [0,47.52,36.63,34.48,35.5,34.52,31.87,43.35,41.5,36.31,29.53,37.66,33.6,28.6,43.39,48.23,32.61,36.42,31.9,32.48,39.88,41.5,30.88,49.75,35.75,30.94,30.85,39.45,30.82,48.8,42.87,34.69,34.45,30.57,33.5,39.84,42.7,42.87,36.89,31.94,32.61,32.71,31.9,40.51,52.37,32.51,31.53,29.73,36.56,33.86,47.52,35.54,46.21,36.49,30.91,31.65,33.9,36.67,43.91,43.69],
    qps4xx: [0,0,3.96,0,0,0.99,1,0,0,3.93,0,0,3.95,0,0,2.95,0,0.98,0,0,0,2.96,0,3.98,0,1,0,0,0,1.99,0,0,1.97,0,1.97,0,0,0,1,0,1.98,0,1.99,0,0,0,0,3.96,0,3.98,0,0,0,0,0,0,2.99,0,3.99,0],
    qps5xx: [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
    /* 缓存：命中率（0-100，null = 本拍无请求 ⇒ 断线）+ 命中/未命中**计数**（双轴堆叠柱用）*/
    hit: [null,76.9,80,88.5,80,83.3,91.3,100,100,84.6,100,75,87.5,100,100,76,100,75,100,100,73.3,100,100,77.8,80.8,100,95.2,69,100,81.5,100,84,83.3,100,100,72.4,95.2,100,70.4,100,90.9,91.3,100,100,70,91.3,100,100,74.1,95.5,75,79.2,80,81.5,100,100,82.6,77.8,100,100],
    hitN: [0,20,20,23,20,20,21,20,21,22,20,21,21,18,23,19,22,21,20,23,22,19,21,21,21,21,20,20,21,22,20,21,20,21,23,21,20,21,19,22,20,21,21,19,21,21,22,20,20,21,21,19,20,22,19,23,19,21,22,21],
    missN: [0,6,5,3,5,4,2,0,0,4,0,7,3,0,0,6,0,7,0,0,8,0,0,6,5,0,1,9,0,5,0,4,4,0,0,8,1,0,8,0,2,2,0,0,9,2,0,0,7,1,7,5,5,5,0,0,4,6,0,0],
    /* 平均延迟：**逐拍真值**（后端用差分算，见 TD-77）。null = 本拍无延迟样本 */
    meanMs: [null,11.685,1.843,1.603,1.848,2.374,1.513,7.247,10.99,2.117,1.541,1.999,1.711,1.721,9.707,14.437,1.738,1.98,1.998,1.466,1.923,7.94,1.798,18.008,2.028,1.603,2.808,2.31,1.621,10.883,14.048,2.035,1.754,1.526,1.686,2.028,8.368,15.377,1.999,1.374,1.919,1.915,2.117,11.483,10.57,2.606,1.519,2.386,1.821,1.716,11.187,2.769,15.883,1.595,1.814,1.217,2.023,2.811,10.337,16.769],
    /* P99：**不是逐拍**（Micrometer 衰减窗口分位）—— 只喂 KPI 卡（原页面口径），不上折线 */
    p99: [null,59.769,59.769,59.769,59.769,59.769,59.769,59.769,59.769,59.769,59.769,59.769,59.769,59.769,59.769,63.963,63.963,63.963,63.963,63.963,63.963,63.963,63.963,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838,82.838],

    /* ── 锁 ────────────────────────────────────────────────────────── */
    /* 行名 = 窗口内出现过的锁（顺序按首次出现，与页面同口径）*/
    lockNames: ["order-pay","order-add"],
    /* 热力格子 = 该锁该拍的**超时数**；0 不发射 ⇒ 整窗全 0 就是 [] + 整块最浅。
       本窗两把锁的 timeouts 都是 0 ⇒ 这里必然是 []，见 DATA.notes.heat */
    heatMax: 1,
    heat: [],
    /* 锁等待 = 每把锁一条 waitMeanMs 折线。⚠️ 无等待样本的拍是 null ⇒ **断开**，不是 0 */
    wait: { names: ["order-pay","order-add"], series: [[null,2.568,null,null,null,null,null,null,2.123,null,null,null,null,null,null,0.877,null,null,null,null,null,null,null,1.561,null,null,null,null,null,null,1.848,null,null,null,null,null,null,1.756,null,null,null,null,null,null,1.243,null,null,null,null,null,null,null,3.419,null,null,null,null,null,null,2.263],[null,null,null,null,null,null,null,1.141,null,null,null,null,null,null,1.616,null,null,null,null,null,null,0.984,null,null,null,null,null,null,null,1.375,null,null,null,null,null,null,1.736,null,null,null,null,null,null,2.722,null,null,null,null,null,null,2.36,null,null,null,null,null,null,null,1.997,null]] },

    /* ── 依赖状态点（#6）───────────────────────────────────────────── */
    /* 恒 1 是**实测**（nz 是整窗命中帧数），图上是状态点不是趋势 */
    state: {"l2Up":1,"lockUp":1,"nz":{"l2Up":60,"lockUp":60},"n":60},

    /* ── 无时间维两块（本窗最后一拍）──────────────────────────────── */
    prot: { labels: ["缓存命中","锁等待超时","订单流转被拒","库存不足（防超卖）","审计写入失败","通知发送失败","敏感词拦下","身份拒绝"],
      values: [21,0,0,0,0,0,0,0], max: 21 },
    stock: [{"name":"oppo12","sku":"asdsad · 珍珠白 / S","stock":"0","baseline":"—","ratio":null},{"name":"oppo12","sku":"sadasd · 珍珠白 / M","stock":"0","baseline":"—","ratio":null},{"name":"机械键盘 87 键 茶轴","sku":"SEED-GD-104-7045 · 默认规格","stock":"60","baseline":"—","ratio":null},{"name":"oppo12","sku":"dddddd · 珍珠白 / L","stock":"100","baseline":"—","ratio":null},{"name":"oppo12","sku":"aaaaaa · 珍珠白 / XL","stock":"100","baseline":"—","ratio":null},{"name":"27 英寸 2K 显示器 锐视 S27","sku":"SEED-GD-103-5196 · 默认规格","stock":"120","baseline":"—","ratio":null},{"name":"无线蓝牙耳机 声谷 Pro 降噪","sku":"SEED-GD-102-AF27 · 默认规格","stock":"180","baseline":"—","ratio":null},{"name":"无线蓝牙耳机 声谷标准版","sku":"SEED-GD-101-9552 · 默认规格","stock":"240","baseline":"—","ratio":null}],

    /* 图头要标的数：都从本窗口现算，不写死 */
    notes: {
      qps5xx: "5xx 段待 D 段接通（mqDown/dbSlow）后补",
      qps4xx: "1 秒桶 + 成簇 ⇒ 单拍峰值可达 ~11.8%，整窗均值 2.3%",
      heat: "本窗 60 拍锁超时数全为 0 ⇒ 整块最浅（不是缺数据）",
      p99: "P99 卡是衰减窗口分位、不是逐拍 —— 别拿它跟「平均延迟」折线对照",
      state: "本窗 60/60 拍恒 1（常量，不是趋势）",
      stock: "8 行，基线（BaselineStock）全为空 ⇒ 水位算不出，画满格灰条",
    }
  }
  /* @@WIRE-DATA:END 勿手改 @@ */

  /* ── SVG 灰阶原语 ─────────────────────────────────────────────────────────── */
  var VBW = 600                     /* viewBox 宽（实际宽度由 CSS 拉伸到容器）*/
  var NS = 'http://www.w3.org/2000/svg'
  var G = { line: '#8a8a8a', line2: '#565656', grid: '#e8e8e8', fill: '#dcdcdc', bar: '#c8c8c8', axis: '#b9b9b9' }

  /* ── 共享 x 轴的**几何口径**（不是设计选择，是「对齐」成不成立的前提）────────────
     所有时序图共用同一条左内边距 PAD_L / 右内边距 PAD_R（viewBox 单位），
     且 SVG 一律**占满容器宽**（`preserveAspectRatio: none` 横向拉伸）。
     于是「第 i 列」在任何一张图上的像素 x = 容器宽 × (PAD_L + i/(n-1)×(VBW−PAD_L−PAD_R)) / VBW
     —— 与是哪张图无关 ⇒ 竖向真的对齐。

     ⚠️ 上一版的 bug（本笔修的就是它）：热力图的行名走一个 **62px 的 HTML 左列**，
     而它的 SVG 只占剩下的宽度、内部 `pl` 又是 4 ⇒ 它的绘图区左沿在 **66px 处**，
     折线图却在 **30/600 的 viewBox 位置**。两者都「看起来」有个左边距，**但从没对齐过** ——
     而 S1/S2 的脚注当时正写着「三张图共用同一根 x 轴」。60 拍下这个 36px 的错位比 7 拍更显眼。

     修法：把行名槽改为**叠在 SVG 上的绝对定位条**，宽度 = PAD_L/VBW（= 11%），
     SVG 仍然占满容器 ⇒ 左沿恒在 PAD_L/VBW 处，与折线图逐像素相同。
     探针量的是**画出来的** `.plot-edge`（每张图都画的一条绘图区底边），不是重算公式。 */
  var PAD_L = 66, PAD_R = 10
  /* LAB_W = 行名槽宽，以 SVG 宽度的百分比给出 —— 这样 CSS 与 JS 不可能各说各话 */
  var LAB_W = (PAD_L / VBW * 100) + '%'

  function svg(w, h) {
    var s = document.createElementNS(NS, 'svg')
    s.setAttribute('viewBox', '0 0 ' + w + ' ' + h)
    s.setAttribute('preserveAspectRatio', 'none')   /* 横向拉伸到容器宽 */
    s.setAttribute('height', h)
    s.style.height = h + 'px'
    return s
  }
  function el(tag, attrs) {
    var n = document.createElementNS(NS, tag)
    for (var k in attrs) n.setAttribute(k, attrs[k])
    /* 关键：横向被拉伸时保持描边 1px，不然线会被压成发丝或拉成粗带 */
    if (tag === 'path' || tag === 'line' || tag === 'rect') n.setAttribute('vector-effect', 'non-scaling-stroke')
    return n
  }

  /** 折线/面积示意图。`o = { h, data, yMin, yMax, area, xAxis, mark }`
   *  ⚠️ 左右内边距是**模块级常量**（PAD_L / PAD_R），不是本函数的局部选择 ——
   *     各图各定一个 `pl` 正是上一版「以为对齐其实没对齐」的成因。 */
  function lineChart(host, o) {
    var h = o.h, pl = PAD_L, pr = PAD_R, pt = 10, pb = o.xAxis ? 18 : 6
    var iw = VBW - pl - pr, ih = h - pt - pb
    var vals = o.data.filter(function (v) { return v !== null && v !== undefined })
    var lo = o.yMin !== undefined ? o.yMin : Math.min.apply(null, vals)
    var hi = o.yMax !== undefined ? o.yMax : Math.max.apply(null, vals)
    if (hi === lo) hi = lo + 1
    var n = o.data.length
    var X = function (i) { return n < 2 ? pl + iw / 2 : pl + (i / (n - 1)) * iw }
    var Y = function (v) { return pt + ih - ((v - lo) / (hi - lo)) * ih }

    var s = svg(VBW, h)
    /* 横向网格（5 档）—— 只是标尺示意，不代表真页面的网格样式 */
    for (var g = 0; g <= 4; g++) {
      var gy = pt + (ih * g) / 4
      s.appendChild(el('line', { x1: pl, y1: gy, x2: VBW - pr, y2: gy, stroke: G.grid, 'stroke-width': 1 }))
    }
    /* 面积（可选）*/
    var seg = [], segs = []
    o.data.forEach(function (v, i) {
      if (v === null || v === undefined) { if (seg.length) segs.push(seg); seg = [] }
      else seg.push([X(i), Y(v)])
    })
    if (seg.length) segs.push(seg)
    if (o.area) {
      segs.forEach(function (sg) {
        if (sg.length < 2) return
        var d = 'M' + sg[0][0] + ',' + (pt + ih) + ' L' + sg.map(function (p) { return p[0] + ',' + p[1] }).join(' L') +
                ' L' + sg[sg.length - 1][0] + ',' + (pt + ih) + ' Z'
        s.appendChild(el('path', { d: d, fill: G.fill, stroke: 'none' }))
      })
    }
    /* 折线（null 断成多段 —— 那条口径必须看得见）*/
    segs.forEach(function (sg) {
      var d = 'M' + sg.map(function (p) { return p[0] + ',' + p[1] }).join(' L')
      s.appendChild(el('path', { d: d, fill: 'none', stroke: G.line, 'stroke-width': 1.5 }))
      sg.forEach(function (p) {
        s.appendChild(el('rect', { x: p[0] - 2, y: p[1] - 2, width: 4, height: 4, fill: G.line }))
      })
    })
    /* 绘图区底边：**每张图都画**（它是绘图区的界，也是探针量「x 轴对齐」的抓手）。
       ⚠️ 与「刻度」是两件事：刻度（下面那排短竖线）**只有**共享轴组的最后一张画。 */
    s.appendChild(el('line', { class: 'plot-edge', x1: pl, y1: pt + ih, x2: VBW - pr, y2: pt + ih, stroke: G.axis, 'stroke-width': 1 }))
    /* x 刻度：**只有**共享轴组的最后一张才画位置（这是「共享」看得见的地方）*/
    if (o.xAxis) {
      o.data.forEach(function (v, i) {
        s.appendChild(el('line', { x1: X(i), y1: pt + ih, x2: X(i), y2: pt + ih + 3, stroke: G.axis, 'stroke-width': 1 }))
      })
    }
    host.appendChild(s)
  }

  /** 热力示意图：行 = 锁名，列 = 拍。灰阶深浅 = 值（无彩色）。
   *  ⚠️ 两处几何是**对齐的关键**，改之前先读上面 PAD_L 那段注释：
   *    · SVG 占满容器宽、用**同一个 viewBox**（不给自己留 HTML 左列去挤宽度）；
   *    · 第 i 列的中心落在 X(i)（与折线图的第 i 个点**同一个公式**），
   *      单元格向两侧各摊半格 ⇒ 首尾两格各悬出半个 pitch。悬出量 < PAD_L / PAD_R，不会溢出。
   *  行名走**叠在 SVG 上的绝对定位条**（不是同级 flex 列）：SVG 被横向拉伸，里面写字会变形，
   *  但行名条是 HTML ⇒ 不变形；它只占左边距那一槽，与格子不重叠。 */
  function heatChart(host, o) {
    var rows = o.rows.length, cols = o.x.length
    var h = o.h, pt = 6, pb = o.xAxis ? 18 : 6
    var iw = VBW - PAD_L - PAD_R, ih = h - pt - pb
    var pitch = cols > 1 ? iw / (cols - 1) : iw
    var ch = ih / rows
    var s = svg(VBW, h)
    var max = o.max || 1
    /* 稀疏 cells → 查表，别在 60×2 的双循环里再扫一遍数组 */
    var grid = {}
    o.cells.forEach(function (c) { grid[c[0] + ':' + c[1]] = c[2] })
    for (var y = 0; y < rows; y++) {
      for (var x = 0; x < cols; x++) {
        var v = grid[x + ':' + y] || 0
        var t = v / max
        /* 灰阶：值越大越深。0 保留一个极浅的底，免得看起来是「没有这一格」 */
        var shade = v === 0 ? '#f6f6f6' : 'rgba(60,60,60,' + (0.15 + 0.75 * t).toFixed(2) + ')'
        var cx = PAD_L + (cols > 1 ? (x / (cols - 1)) * iw : iw / 2)
        s.appendChild(el('rect', {
          x: cx - pitch / 2 + 1, y: pt + y * ch + 1,
          width: Math.max(1, pitch - 2), height: Math.max(1, ch - 2),
          fill: shade, stroke: '#fff', 'stroke-width': 1,
        }))
      }
    }
    s.appendChild(el('line', { class: 'plot-edge', x1: PAD_L, y1: pt + ih, x2: VBW - PAD_R, y2: pt + ih, stroke: G.axis, 'stroke-width': 1 }))
    if (o.xAxis) {
      for (var i = 0; i < cols; i++) {
        var tx = PAD_L + (cols > 1 ? (i / (cols - 1)) * iw : iw / 2)
        s.appendChild(el('line', { x1: tx, y1: pt + ih, x2: tx, y2: pt + ih + 3, stroke: G.axis, 'stroke-width': 1 }))
      }
    }

    var wrap = document.createElement('div')
    wrap.className = 'heat-wrap'
    var labels = document.createElement('div')
    labels.className = 'heat-labels'
    labels.style.width = LAB_W               /* 与绘图区左沿同一个位置（PAD_L/VBW）*/
    labels.style.paddingTop = pt + 'px'
    labels.style.paddingBottom = pb + 'px'
    o.rows.forEach(function (n) {
      var sp = document.createElement('span')
      sp.textContent = n
      labels.appendChild(sp)
    })
    wrap.appendChild(s)
    wrap.appendChild(labels)                 /* SVG 先入、行名条后入 ⇒ 绝对定位叠在上面 */
    host.appendChild(wrap)
    /* 左上角被行名槽占掉了 ⇒ 热力的「占位」小签挪到右上（见 CSS `.tag.right`）*/
    var tg = host.querySelector('.tag')
    if (tg) tg.classList.add('right')
  }

  /* ── 共享 x 轴的时间图：统一骨架 ────────────────────────────────────────────
     下面几种图（堆叠柱 / 双轴 / 折线）**都调 frame()**，于是「同一列 = 同一像素 x」
     不靠各自算对，而是只剩一条路可走。 */
  function frame(host, o) {
    var h = o.h, pt = 10, pb = o.xAxis ? 18 : 6
    var iw = VBW - PAD_L - PAD_R, ih = h - pt - pb
    var n = DATA.x.length
    var X = function (i) { return n < 2 ? PAD_L + iw / 2 : PAD_L + (i / (n - 1)) * iw }
    var s = svg(VBW, h)
    for (var g = 0; g <= 4; g++) {
      var gy = pt + (ih * g) / 4
      s.appendChild(el('line', { x1: PAD_L, y1: gy, x2: VBW - PAD_R, y2: gy, stroke: G.grid, 'stroke-width': 1 }))
    }
    s.appendChild(el('line', { class: 'plot-edge', x1: PAD_L, y1: pt + ih, x2: VBW - PAD_R, y2: pt + ih, stroke: G.axis, 'stroke-width': 1 }))
    if (o.xAxis) {
      for (var i = 0; i < n; i++) {
        s.appendChild(el('line', { x1: X(i), y1: pt + ih, x2: X(i), y2: pt + ih + 3, stroke: G.axis, 'stroke-width': 1 }))
      }
    }
    host.appendChild(s)
    return {
      s: s, iw: iw, ih: ih, pt: pt, X: X,
      /** 线性 y 映射。**每张图自己给 lo/hi** —— 双轴图有两套，不能共用一把尺 */
      Y: function (v, lo, hi) { return pt + ih - ((v - lo) / (hi - lo)) * ih },
      /** 柱宽 = 一格 pitch 的 62%（柱间留缝，不然 60 拍会连成一块实心）*/
      bw: Math.max(1, (n > 1 ? iw / (n - 1) : iw) * 0.62),
    }
  }

  /** 堆叠柱：`segs = [{data, shade}]`，**按数组顺序自下往上**摞。
   *  ⚠️ 计数类没有「无样本」语义 ⇒ 空值一律当 0（与 `hit` 那种 null=断线**不是**一回事）。*/
  function stackBars(f, segs, hi) {
    var n = DATA.x.length
    var base = DATA.x.map(function () { return 0 })
    segs.forEach(function (g) {
      for (var i = 0; i < n; i++) {
        var v = g.data[i] || 0
        if (!v) continue
        var y0 = f.Y(base[i], 0, hi), y1 = f.Y(base[i] + v, 0, hi)
        f.s.appendChild(el('rect', {
          x: f.X(i) - f.bw / 2, y: y1, width: f.bw, height: Math.max(0.4, y0 - y1),
          fill: g.shade, stroke: '#fff', 'stroke-width': 0.5,
        }))
      }
      for (var j = 0; j < n; j++) base[j] += g.data[j] || 0
    })
  }

  /** 折线：`null` = **断开**（不连成 0）。孤立点也画 —— 锁等待正是这种形状（多数拍没人等）。*/
  function polyline(f, data, lo, hi, stroke, dashed) {
    var seg = [], segs = []
    data.forEach(function (v, i) {
      if (v === null || v === undefined) { if (seg.length) segs.push(seg); seg = [] }
      else seg.push([f.X(i), f.Y(v, lo, hi)])
    })
    if (seg.length) segs.push(seg)
    segs.forEach(function (sg) {
      for (var i = 1; i < sg.length; i++) {
        var a = { x1: sg[i - 1][0], y1: sg[i - 1][1], x2: sg[i][0], y2: sg[i][1], stroke: stroke, 'stroke-width': dashed ? 1.1 : 1.4 }
        if (dashed) a['stroke-dasharray'] = '4 3'
        f.s.appendChild(el('line', a))
      }
      sg.forEach(function (p) {
        f.s.appendChild(el('rect', { x: p[0] - 1.5, y: p[1] - 1.5, width: 3, height: 3, fill: stroke }))
      })
    })
  }

  /** #1 QPS 堆叠：**成功 + 4xx 两段**（5xx 整窗 0 ⇒ 不画，见 DATA.notes.qps5xx）。
   *  三段同单位（次/秒）⇒ 加起来 = 原图的总高，不是「另外画了两条」。*/
  function qpsStack(host, o) {
    var f = frame(host, o)
    var tot = DATA.x.map(function (_, i) { return (DATA.qpsOk[i] || 0) + (DATA.qps4xx[i] || 0) })
    stackBars(f, [{ data: DATA.qpsOk, shade: G.fill }, { data: DATA.qps4xx, shade: G.bar }],
      Math.max(1, Math.max.apply(null, tot)))
  }

  /** #2 命中率图：**双轴** —— 柱 = 命中/未命中**计数**（0..max），虚线 = 命中率 **%**（0..100）。
   *  理由：只放一个 95% 会盖住「miss 上万」，只放计数又看不出比例。*/
  function hitBars(host, o) {
    var f = frame(host, o)
    var tot = DATA.x.map(function (_, i) { return (DATA.hitN[i] || 0) + (DATA.missN[i] || 0) })
    stackBars(f, [{ data: DATA.hitN, shade: G.fill }, { data: DATA.missN, shade: G.bar }],
      Math.max(1, Math.max.apply(null, tot)))
    polyline(f, DATA.hit, 0, 100, G.line2, true)
  }

  /** #3 平均延迟 / #4 锁等待：同一套折线画法，`o.series` 决定画几条。
   *  ⚠️ `yMin` 默认 **0**（不截断）—— 截断纵轴会把 1.2–18ms 的抖动放大成「剧烈波动」。*/
  function seriesChart(host, o) {
    var f = frame(host, o)
    var vals = []
    o.series.forEach(function (s) {
      s.data.forEach(function (v) { if (v !== null && v !== undefined) vals.push(v) })
    })
    var lo = o.yMin === undefined ? 0 : o.yMin
    var hi = vals.length ? Math.max.apply(null, vals) : lo + 1
    if (hi === lo) hi = lo + 1
    o.series.forEach(function (s) { polyline(f, s.data, lo, hi, s.stroke || G.line, !!s.dashed) })
  }

  /** #6 依赖状态点：`l2Up` / `lockUp` 是**常量**（本窗 60/60 拍恒 1）。
   *  画成**点**、不画成线 —— 常量画成折线就是「一条贴在顶上的假趋势」。 */
  function dots(host) {
    if (!host) return
    host.innerHTML = ''
    var box = document.createElement('div')
    box.className = 'dots'
    ;[['L2 缓存', DATA.state.l2Up], ['锁管理器', DATA.state.lockUp]].forEach(function (it) {
      var sp = document.createElement('span')
      sp.className = 'dot' + (it[1] === 1 ? '' : ' off')
      var b = document.createElement('i')
      sp.appendChild(b)
      sp.appendChild(document.createTextNode(it[0] + ' '))
      var c = document.createElement('code')
      c.textContent = it[1] === 1 ? 'up' : 'down'
      sp.appendChild(c)
      box.appendChild(sp)
    })
    host.appendChild(box)
    var note = document.createElement('div')
    note.className = 'dots-note'
    note.textContent = DATA.notes.state
    host.appendChild(note)
  }

  /* ── 把图渲染进容器：`WF.chart(el, kind, opts)` ──────────────────────────── */
  function chart(host, kind, o) {
    if (!host) return
    o = o || {}
    /* ── 新落地的四种（3a.2b 判定的图）────────────────────────────────────── */
    if (kind === 'qpsStack') qpsStack(host, { h: o.h || 200, xAxis: !!o.xAxis })
    else if (kind === 'hitBars') hitBars(host, { h: o.h || 170, xAxis: !!o.xAxis })
    else if (kind === 'mean') seriesChart(host, { h: o.h || 160, xAxis: !!o.xAxis, series: [{ data: DATA.meanMs, stroke: G.line }] })
    else if (kind === 'wait') seriesChart(host, {
      h: o.h || 160, xAxis: !!o.xAxis,
      series: DATA.wait.names.map(function (n, i) {
        return { data: DATA.wait.series[i], stroke: i === 0 ? G.line : G.line2, dashed: i === 1 }
      }),
    })
    /* ── 上一版就有的三种形态（S2 与对照用；`qps`/`hit` 是**单序列**画法）────── */
    else if (kind === 'qps') lineChart(host, { h: o.h || 200, data: DATA.qps, area: true, xAxis: !!o.xAxis })
    else if (kind === 'hit') lineChart(host, { h: o.h || 170, data: DATA.hit, yMin: 0, yMax: 100, xAxis: !!o.xAxis })
    else if (kind === 'heat') heatChart(host, { h: o.h || 190, rows: DATA.lockNames, x: DATA.x, cells: DATA.heat, max: DATA.heatMax, xAxis: !!o.xAxis })
    else if (kind === 'protV') {
      /* 纵向柱（现状形态）—— 三版都没采用，保留以备对照 */
      var vals = DATA.prot.values, mx = Math.max(1, Math.max.apply(null, vals))
      var h = o.h || 160, pl = 26, pr = 8, pt = 8, pb = 14
      var iw = VBW - pl - pr, ih = h - pt - pb, bw = iw / vals.length
      var s = svg(VBW, h)
      vals.forEach(function (v, i) {
        var bh = (v / mx) * ih
        s.appendChild(el('rect', { x: pl + i * bw + bw * 0.2, y: pt + ih - bh, width: bw * 0.6, height: Math.max(0.5, bh), fill: G.bar }))
      })
      s.appendChild(el('line', { x1: pl, y1: pt + ih, x2: VBW - pr, y2: pt + ih, stroke: G.axis, 'stroke-width': 1 }))
      host.appendChild(s)
    }
    return host
  }

  /* ── 横向条：`WF.hbar(el, items, max, unit)`（HTML 画，不用 SVG —— 要写字）──── */
  function hbar(host, items, max, unit) {
    if (!host) return
    max = max || Math.max.apply(null, items.map(function (i) { return i.value })) || 1
    host.innerHTML = ''
    items.forEach(function (it) {
      var r = document.createElement('div')
      r.className = 'hbar'
      var pct = it.value / max * 100
      r.innerHTML = '<span class="name"></span>' +
        '<span class="track"><span class="fill" style="display:block;width:' + pct.toFixed(1) + '%"></span></span>' +
        '<span class="num"></span>'
      r.querySelector('.name').textContent = it.label
      r.querySelector('.num').textContent = String(it.value) + (unit || '')
      host.appendChild(r)
    })
  }

  /** 库存水位 3 行（真查询，会独立失败；这里展示的是成功那一态）*/
  function stock(host, narrow) {
    if (!host) return
    host.innerHTML = ''
    DATA.stock.forEach(function (r) {
      var d = document.createElement('div')
      d.className = 'stock-row' + (narrow ? ' narrow' : '')
      var w = r.ratio === null ? 100 : Math.max(2, Math.min(100, r.ratio * 100))
      d.innerHTML = '<div><div class="nm"></div><div class="sk"></div></div>' +
        '<div class="track"><div class="fill" style="width:' + w.toFixed(1) + '%;height:8px"></div></div>' +
        '<div class="num"><b></b> <s></s></div>'
      d.querySelector('.nm').textContent = r.name
      d.querySelector('.sk').textContent = r.sku
      d.querySelector('.num b').textContent = r.stock
      d.querySelector('.num s').textContent = '/ ' + r.baseline
      host.appendChild(d)
    })
  }

  /** 极简微条（开关 C：只给**有序列**的指标画 —— QPS / 缓存命中率；P99 与锁等待没有序列）*/
  function spark(host, key, w, h) {
    if (!host) return
    var data = key === 'qps' ? DATA.qps : DATA.hit
    var s = svg(w || 90, h || 22)
    s.style.width = (w || 90) + 'px'
    s.style.height = (h || 22) + 'px'
    s.setAttribute('viewBox', '0 0 ' + (w || 90) + ' ' + (h || 22))
    var vals = data.filter(function (v) { return v !== null })
    var lo = Math.min.apply(null, vals), hi = Math.max.apply(null, vals)
    if (hi === lo) hi = lo + 1
    var n = data.length, iw = (w || 90) - 4, ih = (h || 22) - 4
    var X = function (i) { return 2 + (n < 2 ? iw / 2 : (i / (n - 1)) * iw) }
    var Y = function (v) { return 2 + ih - ((v - lo) / (hi - lo)) * ih }
    var seg = [], segs = []
    data.forEach(function (v, i) { if (v === null) { if (seg.length) segs.push(seg); seg = [] } else seg.push([X(i), Y(v)]) })
    if (seg.length) segs.push(seg)
    segs.forEach(function (sg) {
      if (sg.length < 2) return
      s.appendChild(el('path', { d: 'M' + sg.map(function (p) { return p[0] + ',' + p[1] }).join(' L'), fill: 'none', stroke: G.line, 'stroke-width': 1 }))
    })
    host.appendChild(s)
  }

  window.WF = {
    DATA: DATA,
    chart: chart,
    hbar: hbar,
    stock: stock,
    spark: spark,
    dots: dots,
    /** 共享 x 轴的几何常量 —— 探针拿它做**独立**的对照（量出来的 vs 算出来的）*/
    PAD_L: PAD_L,
    PAD_R: PAD_R,
    VBW: VBW,
    /** 把 DATA.kpis 渲染成 `.kpi` 卡片 HTML（`withSpark` = 给有序列的挂微条）*/
    kpiHTML: function (k, withSpark) {
      var d = k.delta
        ? '<span class="d">' + k.delta + ' ' + k.dl + '</span>'
        : '<span class="d">' + (k.hint || '') + '</span>'
      return '<div class="k"><span class="kname">' + k.label + '</span></div>' +
        '<div class="v">' + k.value + '<u>' + k.unit + '</u></div>' + d
    }
  }
})()
