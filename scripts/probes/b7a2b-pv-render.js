/* ══════════════════════════════════════════════════════════════════════════════════
   指标页预览 · 运行时（甲/乙**共用**这一份；两版的差异**全部**挂在 window.PV.variant 上）

   为什么要有这份脚本：快照把「真页面的 DOM + 样式 + 每块图合并后的 option」搬了下来，
   但 `outerHTML` **搬不动 canvas 像素** ⇒ 图要按 option 重新渲染一遍。这不是「重画一张
   示意图」，是**用真页面当时那份 option 原样再渲一次**（甲版刻意一个键都不动）。

   甲版（variant = 'jia'）：option 原样 + 数字 0.3s 线性插值（照现状的 rAF 插值口径）。
   乙版（variant = 'yi'） ：多三件事 —— 维度 6 改图 / 维度 4 拆数字 / 维度 7 动效错开。
   ⚠️ 乙版的三处差量都写在**有标记的块**里（下面 `tuneChart` / `splitNum` / `stagger`），
      每块的头一行写着它对应哪个维度 —— 落地时**照着这三块改真代码**即可。
   ⚠️ 动效一律尊重 `prefers-reduced-motion: reduce`（本仓既有先例：`.a-bar-fill` 的 transition）。
   ══════════════════════════════════════════════════════════════════════════════════ */
(function () {
  var PV = window.PV || { variant: 'jia', opts: [] }
  var B = PV.variant === 'yi'
  var RM = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  var E = window.echarts
  var clone = function (o) { return JSON.parse(JSON.stringify(o)) }

  /* ── ① 图 ──────────────────────────────────────────────────────────────────────── */
  var roots = [].slice.call(document.querySelectorAll('[data-pv]'))

  /* ═══ 乙版差量 · 维度 6：网格做减法 + tooltip 升级（甲版 = 实线网格 + 方角浮层）═══
     ⚠️ 这三条**落在主题面**（`theme.js`），不是组件面：实测（`_work/3a2b/snapshot.json`）
        `yAxis[].splitLine` / `xAxis[].axisLine` / `tooltip.*` 的值**就是主题给的**
        （组件没写这些键）⇒ 改主题 = 四块图一起变。
     唯一**不**在主题面的是 `grid` 留白（每张图各自写了 left/right/bottom）与轴的
        formatter/min/max —— 那些要改组件，乙版**没动**（保持甲版的留白与刻度口径）。 */
  function tuneChart(o) {
    ;(o.xAxis || []).forEach(function (a) {
      a.axisLine = { show: false }                       // 甲：类目轴有一条贴底的轴线
    })
    ;(o.yAxis || []).forEach(function (a) {
      if (a.splitLine) {                                 // 甲：--grid-line 实线
        a.splitLine.lineStyle = { color: '#2b394f', type: 'dashed', width: 1 }
      }
    })
    ;(o.tooltip || []).forEach(function (t) {
      t.borderRadius = 8                                 // 甲：方角
      t.padding = [10, 14]                               // 甲：[8, 11]
    })
    ;(o.series || []).forEach(function (s) {
      if (s.type === 'line') s.symbolSize = 4            // 甲：6
    })
    /* 维度 7 的图表那一半：时长 1000 → 400、缓动 cubicInOut → cubicOut。
       ⚠️ 这两个键**不在主题的取值面**里（`getOption` 里那 1000/cubicInOut 是 ECharts 内置默认，
          本仓从没写过）⇒ 落地时写进 `BaseChart.vue` 的 option 合并处（一处，五张图共享）。 */
    o.animationDuration = 400
    o.animationEasing = 'cubicOut'
  }

  roots.forEach(function (r, i) {
    var opt = clone(PV.opts[i] || {})
    if (B) tuneChart(opt)
    r.innerHTML = ''                                     // 清掉快照里那张**没有像素**的 canvas
    var inst = E.init(r)
    var draw = function () { inst.setOption(opt) }
    /* ═══ 乙版差量 · 维度 7：四块图依次错开 60ms 起动画（甲版四块同时、ECharts 默认 1000ms
       cubicInOut）。⚠️ 时长/缓动是**主题/组件面**的键（`animationDuration` 默认 1000 是 ECharts
       内置值，本仓没写过）—— 乙版落地时把它写进 `BaseChart` 的 option 合并处。 */
    if (B && !RM) setTimeout(draw, i * 60)
    else draw()
  })

  /* ── ② KPI 数字：进场那一遍插值 ────────────────────────────────────────────────── */
  var cards = [].slice.call(document.querySelectorAll('.kpi'))

  /* ═══ 乙版差量 · 维度 4：数字分档（甲版 = 整串一个字号）═══════════════════════════
     甲版：`.kpi-n` 里是**一整串**（`10,100,856.00`）—— 整数部与小数部同号同色。
     乙版：整数部照旧（`--fs-lg`/600），小数部降一档（`.78em`/500/`--ink-2`），
           环比行左侧加一条 2px 状态色竖条。
     ⚠️ 落地要动 `KpiCard.vue`：`value` 现在是**格式化好的字符串**（组件不做格式化，这是
        既有分工）⇒ 只能加一个**可选** `#value` 插槽（不传就逐字保持现状，零调用点改动）。 */
  function splitNum(n) {
    var dec = n.querySelector('.n-dec')
    if (dec) return dec
    var t = n.textContent
    var m = /^(-?[\d,]+)(\..*)?$/.exec(t)
    if (!m) return null                                  // 「—」这类非数字：不拆、也不动
    n.textContent = ''
    var a = document.createElement('span'); a.className = 'n-int'; a.textContent = m[1]
    n.appendChild(a)
    if (m[2]) { var b = document.createElement('span'); b.className = 'n-dec'; b.textContent = m[2]; n.appendChild(b) }
    return n.querySelector('.n-dec')
  }
  if (B) {
    document.querySelectorAll('.kpi-d').forEach(function (d) {
      var arrow = d.querySelector('.kpi-arrow')
      if (!arrow) return
      var tone = /--good/.test(arrow.className) ? 'good' : /--bad/.test(arrow.className) ? 'bad' : 'flat'
      d.classList.add('kpi-d--' + tone)
    })
  }

  function fmt(v, dec) {
    var s = v.toFixed(dec)
    var p = s.split('.')
    p[0] = p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',')
    return p.join('.')
  }

  cards.forEach(function (card, ci) {
    var n = card.querySelector('.kpi-n')
    if (!n) return
    var target = n.textContent
    var m = /^(-?[\d,]*\.?\d+)$/.exec(target.trim())
    var decSpan = B ? splitNum(n) : null
    var intEl = n.querySelector ? n.querySelector('.n-int') : null

    /* ═══ 乙版差量 · 维度 7：数字插值 0.3s 线性 → 0.45s easeOutCubic + 4 张卡依次 40ms ═══ */
    var dur = B ? 450 : 300
    var delay = B ? ci * 40 : 0
    var ease = B ? function (p) { return 1 - Math.pow(1 - p, 3) } : function (p) { return p }

    if (!m || RM) return                                 // 非数字 / 关动效 ⇒ 直接停在终值
    var value = parseFloat(m[1].replace(/,/g, ''))
    var dec = (m[1].split('.')[1] || '').length
    var put = function (v) {
      var s = fmt(v, dec)
      if (decSpan) {
        var q = s.split('.')
        intEl.textContent = q[0]
        decSpan.textContent = q.length > 1 ? '.' + q[1] : ''
      } else n.textContent = s
    }
    var t0 = null
    var step = function (ts) {
      if (t0 === null) t0 = ts
      var p = Math.min(1, (ts - t0) / dur)
      put(value * ease(p))
      if (p < 1) requestAnimationFrame(step)
      else put(value)
    }
    put(0)
    setTimeout(function () { requestAnimationFrame(step) }, delay)
  })

  /* ═══ 乙版差量 · 维度 7：4 张 KPI 卡依次 40ms 淡入 + 上移 4px（甲版：无进场）═══════ */
  if (B && !RM) {
    document.documentElement.classList.add('pv-motion')
    cards.forEach(function (c, i) { setTimeout(function () { c.classList.add('is-in') }, i * 40) })
  }
})()
