/* ══════════════════════════════════════════════════════════════════════════════
   _struct-wire.js —— 3a.2b（结构重做）三版结构草图的**共用真数据 + 灰阶图示意**

   ⚠️ 数据是真的，不是我编的。
   来源：`_work/3a2b/snapshot.json`（2026-10-16T07:07:12.294Z 从**真运行页面**抓的快照，
   抓法见 `scripts/probes/probe-b7a2b-metrics-snapshot.mjs`）—— 快照把「DOM + 每块图合并后
   的 option」落成 JSON，再由 `probe-b7a2b-mk-preview.mjs` 拼成 `指标页-甲版.html`。
   下面每一个数字/标签都是从那份快照里**逐字抄出来**的，抄录处标了 `[快照]`。

   ⚠️ 数据的**限度**（别读成更强的结论）：快照那一刻页面里只有 **7 拍**
   （假流推了 7 帧就停），不是真跑满的 60 拍窗口。所以三张时序图的 x 轴是 7 个点。
   这不影响判结构（摆在哪、谁和谁共享轴、谁主谁次都与拍数无关），但**不要**拿它估密度。

   ⚠️ 画法：内联 SVG 灰阶示意图。**没有** ECharts、**没有** CDN、**没有**主题 token ——
   所以双击即开、断网也开。样式一概不画（无品牌色 / 无圆角 / 无过渡 / 无动效）。
   ══════════════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict'

  /* ── 真数据（[快照] = 逐字抄自 snapshot.json 派生出的 `指标页-甲版.html`）───────── */
  var DATA = {
    /* 状态带 [快照] */
    header: {
      health: '健康', err5xx: '1.2%', err4xx: '0.0%',
      phase: '已连接', seq: 6, win: 60, tick: '16:45:09',
      source: '正常（后端真流）', sourceAlt: '模拟日常'
    },
    /* 判据列表 [快照]：健康态也**不是空的** —— 后端填的是正面理由 */
    reasons: ['L1 命中率 60.0%'],

    /* 4 张 KPI [快照]。⚠️ `delta` 与 `hint` 二选一（KpiCard.vue:18-24）——
       快照时刻四张都有 delta ⇒ 四张的 hint 一律**不显示**，不是我漏抄。 */
    kpis: [
      { key: 'qps', label: 'QPS', value: '23.0', unit: '次/秒', delta: '▲ 39.4%', dl: '较 60 秒均值',
        hint: '本拍请求 N 次', series: 'qps' },
      { key: 'p99', label: 'P99 延迟', value: '24', unit: 'ms', delta: '▲ 38.3%', dl: '较 60 秒均值',
        hint: '全站分位数的上界近似（各序列取最大）', series: null },
      { key: 'hit', label: '缓存命中率', value: '60.0', unit: '%', delta: '0.0%', dl: '较 60 秒均值',
        hint: 'L1 命中 / (命中 + 未命中)', series: 'hit' },
      { key: 'lock', label: '锁等待次数', value: '2', unit: '次', delta: '▲ 180.0%', dl: '较 60 秒均值',
        hint: '全部锁名本拍 timeouts 之和', series: null }
    ],

    /* 三条时间轴共用的 x 轴 [快照]（真页面就是**同一个** `xLabels` 数组）*/
    x: ['16:45:03', '16:45:04', '16:45:05', '16:45:06', '16:45:07', '16:45:08', '16:45:09'],
    qps: [0, 15.5, 17, 18.5, 20, 21.5, 23],                 /* [快照] series[0].data */
    /* ⚠️ 第 0 个点是 null —— 那不是「0 命中」，是「这一秒没有请求」。
       ECharts 把 null 画成**断点**；连成 0 会把口径读错（MetricsPanel.vue:270-273）。 */
    hit: [null, 60, 60, 60, 60, 60, 60],                     /* [快照] series[0].data */

    /* 锁竞争热力：行 = 锁名 [快照] yAxis[0].data，列 = 上面那 7 拍；值 = 本拍超时数 */
    lockNames: ['goods_sku', 'order_no', 'cart_item'],
    heatMax: 2,                                              /* [快照] visualMap.max */
    heat: [[3, 0, 2], [4, 2, 1], [6, 0, 2]],                 /* [快照] 非 0 的格子（其余为 0）*/

    /* 防护命中 8 类（本拍增量）[快照] */
    prot: {
      labels: ['缓存命中', '锁等待超时', '订单流转被拒', '库存不足（防超卖）',
               '审计写入失败', '通知发送失败', '敏感词拦下', '身份拒绝'],
      values: [5, 0, 1, 0, 0, 0, 2, 0]
    },

    /* 库存水位（全页唯一一次真查询）[快照] DOM `.a-bars` */
    stock: [
      { name: 'oppo12', sku: 'asdsad · 珍珠白 / S', stock: '84', baseline: '100', ratio: 0.9 },
      { name: 'oppo12', sku: 'sadasd · 珍珠白 / M', stock: '16', baseline: '100', ratio: 0.1 },
      /* ⚠️ 无基线：画满一格的**灰条**，读作「有这行但算不出水位」，不是「库存满」 */
      { name: 'oppo12', sku: 'dddddd · 珍珠白 / L', stock: '100', baseline: '—', ratio: null }
    ]
  }

  /* ── SVG 灰阶原语 ─────────────────────────────────────────────────────────── */
  var VBW = 600                     /* viewBox 宽（实际宽度由 CSS 拉伸到容器）*/
  var NS = 'http://www.w3.org/2000/svg'
  var G = { line: '#8a8a8a', grid: '#e8e8e8', fill: '#dcdcdc', bar: '#c8c8c8', axis: '#b9b9b9' }

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

  /** 折线/面积示意图。`o = { h, data, yMin, yMax, area, xAxis, mark }` */
  function lineChart(host, o) {
    var h = o.h, pl = 30, pr = 10, pt = 10, pb = o.xAxis ? 18 : 6
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
    /* x 轴：**只有**共享轴组的最后一张才画刻度位置（这是「共享」看得见的地方）*/
    if (o.xAxis) {
      s.appendChild(el('line', { x1: pl, y1: pt + ih, x2: VBW - pr, y2: pt + ih, stroke: G.axis, 'stroke-width': 1 }))
      o.data.forEach(function (v, i) {
        s.appendChild(el('line', { x1: X(i), y1: pt + ih, x2: X(i), y2: pt + ih + 3, stroke: G.axis, 'stroke-width': 1 }))
      })
    }
    host.appendChild(s)
  }

  /** 热力示意图：行 = 锁名，列 = 拍。灰阶深浅 = 值（无彩色）。
   *  ⚠️ 行名走 **HTML 左列**（SVG 被横向拉伸，里面写字会变形），并且上下留白与 SVG 的
   *     `pt` / `pb` 对齐 —— 否则行名会与格子的行错位，「行 = 锁名」就读不出来。*/
  function heatChart(host, o) {
    var rows = o.rows.length, cols = o.x.length
    var h = o.h, pl = 4, pr = 4, pt = 6, pb = o.xAxis ? 18 : 6
    var iw = VBW - pl - pr, ih = h - pt - pb
    var cw = iw / cols, ch = ih / rows
    var s = svg(VBW, h)
    var max = o.max || 1
    for (var y = 0; y < rows; y++) {
      for (var x = 0; x < cols; x++) {
        var v = 0
        o.cells.forEach(function (c) { if (c[0] === x && c[1] === y) v = c[2] })
        var t = v / max
        /* 灰阶：值越大越深。0 保留一个极浅的底，免得看起来是「没有这一格」 */
        var shade = v === 0 ? '#f6f6f6' : 'rgba(60,60,60,' + (0.15 + 0.75 * t).toFixed(2) + ')'
        s.appendChild(el('rect', { x: pl + x * cw + 1, y: pt + y * ch + 1, width: Math.max(1, cw - 2), height: Math.max(1, ch - 2), fill: shade, stroke: '#fff', 'stroke-width': 1 }))
      }
    }
    if (o.xAxis) {
      s.appendChild(el('line', { x1: pl, y1: pt + ih, x2: VBW - pr, y2: pt + ih, stroke: G.axis, 'stroke-width': 1 }))
    }

    var wrap = document.createElement('div')
    wrap.className = 'heat-wrap'
    var labels = document.createElement('div')
    labels.className = 'heat-labels'
    labels.style.paddingTop = pt + 'px'
    labels.style.paddingBottom = pb + 'px'
    o.rows.forEach(function (n) {
      var sp = document.createElement('span')
      sp.textContent = n
      labels.appendChild(sp)
    })
    wrap.appendChild(labels)
    wrap.appendChild(s)
    host.appendChild(wrap)
    /* 左上角被行名列占掉了 ⇒ 热力的「占位」小签挪到右上（见 CSS `.tag.right`）*/
    var tg = host.querySelector('.tag')
    if (tg) tg.classList.add('right')
  }

  /* ── 把图渲染进容器：`WF.chart(el, kind, opts)` ──────────────────────────── */
  function chart(host, kind, o) {
    if (!host) return
    o = o || {}
    if (kind === 'qps') lineChart(host, { h: o.h || 200, data: DATA.qps, area: true, xAxis: !!o.xAxis })
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
