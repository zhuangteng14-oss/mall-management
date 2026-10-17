<template>
  <section class="a-of">
    <!-- 回放横幅（2026-10-17 全链路联动）：状态页与日志/指标页共用同一个会话时钟。 -->
    <ReplayBanner />
    <template v-if="!replaySession.active">
    <!-- ── ① 状态条（与 `MetricsPanel.vue` / `StatsPage.vue` 逐字同源，不另立一套）────────── -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ fmtInt(total) }}</b>
        <span>{{ demo.on ? '本场演示订单（假数据）' : '订单总数（库里此刻）' }}</span>
      </div>
      <div class="a-stat">
        <b>{{ fmtInt(moveTotal) }}</b>
        <span>本会话捕获的流转</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-live">{{ phaseText }}</span>
        <span>第 <b>{{ seq }}</b> 拍 · 每秒 1 拍</span>
        <span class="a-tz-off">{{ lastTickAt }}</span>
      </div>
      <!-- 口径开关：默认全量（含压测单与已取消单 = 最真实的压迫感）；开启后收窄到
           「健康业务分布」——排除演练数据（`SIM-%` OR `Source='attack'`）与已取消单。
           ⚠️ 实时流轨**永不过滤**：它的职责是「看见事件真的在动」，藏掉演练单等于把流弄假。 -->
      <label
        class="a-of-filter"
        title="默认全量（含压测单 / 已取消单）；开启后只保留真实、未取消的单，环形图与流转阶梯回到健康业务分布。实时流轨不受影响。"
      >
        <input v-model="filterHealthy" type="checkbox" role="switch" />
        <span class="a-of-switch" aria-hidden="true"><i></i></span>
        <span>剔除已取消 / 仅看真实数据</span>
      </label>
      <DemoModeToggle />
    </header>

    <!-- ⚠️ 断线条的判据是 `lost`、**不是** `errMsg`（块 7 · commit 10c）：
         服务端**正常收尾**（`onEnd(null)`）时 `errMsg` 一路为空，而那种情况下不提示
         就等于「五态计数与「最近订单」静静停在上一次连接的那一拍」—— 页面上一切正常、
         其实早就不更新了。 -->
    <p v-if="lost" class="a-flash bad">
      {{ lostText }}
      <button type="button" class="a-btn" @click="connect">立即重连</button>
    </p>

    <!-- 五态计数条（保留原口径）：点卡片 = 主从联动，筛选下面的全局明细。 -->
    <div class="a-of-kpis">
      <button
        v-for="s in STATES"
        :key="s.key"
        type="button"
        class="a-of-kpi"
        :class="{ on: filterState === s.key }"
        @click="selectState(s.key)"
      >
        <KpiCard
          :label="s.label"
          glyph="flow"
          :value="fmtInt(shown[s.key])"
          :hint="shareText(s.key)"
          :loading="first"
        />
      </button>
    </div>

    <!-- ══ ① 24h 积压趋势 + 健康度（高密度落地 · 2026-10-17）════════════════════
         数据来自 `orderInsight()`：趋势按生命周期时间戳逐整点重建；健康度由
         超时率 / 积压占比 / 平均停留三项扣分。SSE 仍负责每秒变的计数与 moves。 -->
    <div class="a-of-hero">
      <section class="a-card a-of-trend">
        <header class="a-of-chd">
          <div>
            <h2 class="a-of-ct">过去 24 小时积压趋势</h2>
            <p class="a-of-cs">{{ trendWindowText }} · 右侧实时尾窗每 3 秒推进一格 · 压测开始后线会抬升</p>
          </div>
          <span class="a-of-live" :class="{ 'is-anchored': trendAnchored }">
            {{ trendAnchored ? '窗口已锚定' : '实时窗口' }}
          </span>
        </header>
        <p v-if="trendAnchored" class="a-of-anchor">{{ trend.anchorHint }}</p>
        <p v-if="insightError" class="a-of-insight-err">{{ insightError }}（订单表仍由 SSE 实时更新）</p>
        <LineChart
          :categories="trendCategories"
          :series="trendSeries"
          height="250px"
          :smooth="false"
          :show-legend="true"
          :grid-right="44"
          :loading="insightLoading && !insight"
          empty-overlay
          empty-text="还没有可用于重建趋势的时间戳"
        />
      </section>

      <section class="a-card a-of-health">
        <header class="a-of-chd">
          <h2 class="a-of-ct">流转健康度</h2>
          <span class="a-of-cs">{{ healthView.grade }}</span>
        </header>
        <HealthGauge :score="healthView.score" :grade="healthView.grade" height="168px" />
        <dl class="a-of-health-grid">
          <div>
            <dt>超时率</dt>
            <dd>{{ healthView.timeoutRate }}%</dd>
            <small>{{ fmtInt(healthView.timeoutCount) }} / {{ fmtInt(healthView.activeCount) }} 单</small>
          </div>
          <div>
            <dt>积压占比</dt>
            <dd>{{ healthView.backlogShare }}%</dd>
            <small>在途 / 全表</small>
          </div>
          <div>
            <dt>平均停留</dt>
            <dd>{{ healthView.avgDwellHours }}h</dd>
            <small>待发货 / 待收货均值</small>
          </div>
          <div>
            <dt>分析面</dt>
            <dd class="a-of-mini">{{ insightAtText }}</dd>
            <small>{{ demo.on ? '模拟流开启，分析面仍取库内聚合' : '15 秒低频刷新' }}</small>
          </div>
        </dl>
      </section>
    </div>

    <!-- ══ ② 状态流转阶梯 + SSE 流轨 ══════════════════════════════════════════ -->
    <div class="a-of-main">
      <section class="a-card a-of-flow">
        <header class="a-of-chd">
          <div>
            <h2 class="a-of-ct">状态流转 · 积压阶梯</h2>
            <p class="a-of-cs">条宽 = 当前积压 · 底色轨道 = 该节点容量参照 · 点节点把下表筛到该状态</p>
          </div>
          <span class="a-of-cs">判据链：取消 → 已完成 → 待收货 → 待发货 → 待付款</span>
        </header>
        <div class="a-of-flow-body">
        <div class="a-of-ladder">
          <button
            v-for="n in flowRows"
            :key="n.key"
            type="button"
            class="a-of-step"
            :class="{ 'is-on': filterState === n.key, 'is-warn': n.timeout > 0, 'is-live': pulseNodes.has(n.key) }"
            @click="selectState(n.key)"
          >
            <span class="a-of-step-top">
              <span class="a-of-step-name">
                <i class="a-of-dot" :style="{ background: stateColor(n.key), color: stateColor(n.key) }"></i>{{ n.label }}
              </span>
              <span class="a-of-step-num">
                <b class="a-of-step-n" :class="{ 'is-bump': statePulse[n.key] }">{{ fmtInt(n.count) }}</b>
                <span
                  v-if="stateDeltas[n.key]"
                  class="a-of-delta"
                  :class="'is-' + stateDeltas[n.key].dir"
                >{{ stateDeltas[n.key].text }}</span>
              </span>
              <span class="a-of-step-qps">流入 {{ qpsText(n.inQps) }} · 流出 {{ qpsText(n.outQps) }}</span>
            </span>
            <span class="a-of-rail" aria-hidden="true">
              <i :style="{ width: n.width + '%', background: stateColor(n.key) }"></i>
            </span>
            <span class="a-of-step-meta">
              <span>累计 {{ fmtInt(n.reach) }}</span>
              <span>转化 {{ n.conversion.toFixed(1) }}%</span>
              <span>{{ n.avgLabel }} {{ minuteText(n.avg) }}</span>
              <span v-if="n.timeout" class="a-of-warnchip">超时 {{ fmtInt(n.timeout) }} · {{ n.threshold }}</span>
              <span v-else class="a-of-okchip">阈值 {{ n.threshold }}</span>
            </span>
          </button>

          <button
            type="button"
            class="a-of-step is-cancel"
            :class="{ 'is-on': filterState === 'cancelled' }"
            @click="selectState('cancelled')"
          >
            <span class="a-of-step-top">
              <span class="a-of-step-name"><i class="a-of-dot" :style="{ background: stateColor('cancelled'), color: stateColor('cancelled') }"></i>已取消（支线）</span>
              <span class="a-of-step-num">
                <b class="a-of-step-n" :class="{ 'is-bump': statePulse.cancelled }">{{ fmtInt(counts.cancelled) }}</b>
                <span
                  v-if="stateDeltas.cancelled"
                  class="a-of-delta"
                  :class="'is-' + stateDeltas.cancelled.dir"
                >{{ stateDeltas.cancelled.text }}</span>
              </span>
              <span class="a-of-step-qps">退出流程 · 不计入积压</span>
            </span>
            <span class="a-of-rail" aria-hidden="true">
              <i :style="{ width: Math.min(100, (num(counts.cancelled) / Math.max(1, total)) * 100) + '%', background: stateColor('cancelled') }"></i>
            </span>
            <span class="a-of-step-meta">
              <span>占全表 {{ total ? ((num(counts.cancelled) / total) * 100).toFixed(1) : '0.0' }}%</span>
              <span>取消流入 {{ qpsText(qpsIn.cancelled) }}</span>
              <span>点此筛选已取消</span>
            </span>
          </button>
        </div>

          <div class="a-of-flow-dist">
            <DonutChart
              :data="donutData"
              :min-angle="donutMinAngle"
              height="196px"
              :show-legend="false"
              inner-ratio="46%"
              outer-ratio="60%"
              :center-value="fmtInt(total)"
              center-title="全表单量"
              :loading="first"
              :error="errMsg"
              empty-text="还没收到第一拍"
              @retry="connect"
            />
            <p class="a-of-cs">扇区 = 库里此刻五态存量；中心 = 全表单量。</p>
          </div>
        </div>
      </section>

      <section class="a-card a-of-stream">
        <header class="a-of-chd">
          <div>
            <h2 class="a-of-ct">SSE 实时流轨</h2>
            <p class="a-of-cs">本拍第 {{ seq }} 拍 · 本会话捕获 {{ fmtInt(moveTotal) }} 条</p>
          </div>
          <span class="a-of-live">1 秒 / 拍</span>
        </header>
        <div class="a-of-stream-sum">
          <span>流入 <b>{{ qpsText(totalQpsIn) }}</b></span>
          <span>流出 <b>{{ qpsText(totalQpsOut) }}</b></span>
          <span class="a-of-mono">{{ lastTickAt }}</span>
        </div>
        <!-- 主链粒子轨道：SSE move = 一颗从左向右飞向 to 的发光粒子。
             ⚠️ 2026-10-17 用户纠偏：**静态拓扑线 ≠ 动态流转**。
             底层虚线永远是暗灰、**不动**；只有这条边真的发生过流转（`activeEdges` 亮起）
             时才高亮并向右流动。没有流转时轨道是「待命的路由图」，不是「一直在跑」。
             取消 = 支线：同样只在 `waitPay>cancelled` / `waitShip>cancelled` 真的亮起时变黄。 -->
        <div class="a-of-lane" aria-hidden="true">
          <i
            v-for="(e, i) in MAIN_EDGES"
            :key="e.id"
            class="a-of-lane-track"
            :class="{ 'is-on': activeEdges.has(e.id) }"
            :style="laneSegStyle(i)"
          ></i>
          <svg class="a-of-cancel-links" viewBox="0 0 100 100" preserveAspectRatio="none">
            <path
              d="M10,24 C 42,58 70,64 90,82"
              :class="{ on: activeEdges.has(CANCEL_FROM_PAY) }"
              vector-effect="non-scaling-stroke"
            />
            <path
              d="M37,24 C 55,54 74,66 90,82"
              :class="{ on: activeEdges.has(CANCEL_FROM_SHIP) }"
              vector-effect="non-scaling-stroke"
            />
          </svg>
          <span
            v-for="(n, i) in FLOW_NODES"
            :key="n.key"
            class="a-of-lane-node"
            :class="{ 'is-live': pulseNodes.has(n.key) }"
            :style="{ left: LANE_POS[i] + '%' }"
          >
            <i class="a-of-lane-dot" :style="{ background: stateColor(n.key) }"></i>
            <b>{{ n.label }}</b>
            <em>{{ fmtInt(counts[n.key]) }}</em>
          </span>
          <span
            class="a-of-lane-node a-of-lane-cancel"
            :class="{ 'is-live': pulseNodes.has('cancelled') }"
            :style="{ left: LANE_POS[3] + '%' }"
          >
            <i class="a-of-lane-dot" :style="{ background: stateColor('cancelled') }"></i>
            <b>已取消</b>
            <em>{{ fmtInt(counts.cancelled) }}</em>
          </span>
          <span
            v-for="p in laneParticles"
            :key="p.id"
            class="a-of-particle-wrap"
            :style="{ left: p.x1 + '%', width: p.w + '%', '--pcolor': p.color }"
          >
            <i class="a-of-particle"></i>
          </span>
          <i
            v-for="p in cancelParticles"
            :key="p.id"
            class="a-of-cancel-particle"
            :class="['from-' + p.from, { 'is-big': p.big }]"
            :style="{ '--pcolor': stateColor('cancelled'), '--pdelay': (p.delay || 0) + 'ms' }"
          ></i>
        </div>
        <ul v-if="streamRows.length" class="a-of-stream-list">
          <li v-for="m in streamRows" :key="m.orderId + '-' + m.ts" class="a-of-stream-i">
            <span class="a-of-stream-t">{{ hms(m.ts) }}</span>
            <!-- 汇总事件没有订单号：显示「批量取消 N 单」，不要渲染成一个空的 `#`。 -->
            <span class="a-of-id" :class="{ 'is-batch': m.batchCount }">{{ m.batchCount ? `批量取消 ${fmtInt(m.batchCount)} 单` : `#${m.orderId}` }}</span>
            <span class="a-of-stream-track" aria-hidden="true">
              <template v-for="n in 4" :key="n">
                <i class="a-of-dot-sm" :class="{ on: m.step >= n, now: m.step === n }"></i>
                <i v-if="n < 4" class="a-of-seg-sm" :class="{ on: m.step > n }"></i>
              </template>
            </span>
            <span class="a-of-stream-d">
              <template v-if="m.from">{{ moveStateLabel(m.from) }} → </template>
              <b>{{ moveStateLabel(m.to) }}</b>
            </span>
          </li>
        </ul>
        <div v-else class="a-of-stream-empty">
          <i class="a-of-stream-radar" aria-hidden="true"></i>
          <b>暂无实时流转</b>
          <span>压测开始后，粒子会沿上方轨道从当前节点飞向目标节点。</span>
        </div>
        <div v-if="streamRows.length" class="a-of-stream-foot">
          <span class="a-of-stream-radar" aria-hidden="true"></span>
          <span>轨道待命 · 最近 {{ fmtInt(streamRows.length) }} 条在本拍窗口 · 共捕获 {{ fmtInt(moveTotal) }} 条</span>
        </div>
      </section>
    </div>

    <!-- ══ ③ 耗时直方图 + 超时积压 ════════════════════════════════════════════ -->
    <h2 class="a-h2">
      耗时分布
      <span class="a-h2n">横轴 = 时间桶、纵轴 = 单数 · 虚线 = 精确均耗所在桶 · 灰阶柱与桶边界是结构，不是颜色分组</span>
    </h2>
    <div class="a-of-hists">
      <section v-for="g in durationGroups" :key="g.key" class="a-card a-of-hist">
        <HistogramChart
          :title="g.title"
          :bins="g.bins || []"
          :avg-minutes="g.avgMinutes || 0"
          :samples="g.samples || 0"
          height="226px"
        />
      </section>
    </div>

    <div class="a-of-warns">
      <section
        v-for="w in agingRows"
        :key="w.key"
        class="a-card a-of-warn"
        @click="openDrawer('', w.key)"
      >
        <header class="a-of-warn-hd">
          <div>
            <h3>{{ w.title }}</h3>
            <p>阈值 {{ w.threshold }} · 账龄按状态起点到现在计算</p>
          </div>
          <div class="a-of-warn-n">
            <b>{{ fmtInt(w.timeout) }}</b>
            <span>/ {{ fmtInt(w.count) }} 单</span>
          </div>
        </header>
        <div class="a-of-agebars">
          <div v-for="b in w.bins" :key="b.label" class="a-of-agebar">
            <span>{{ b.label }}</span>
            <i><em :style="{ width: ageWidth(w, b) + '%' }"></em></i>
            <b>{{ fmtInt(b.count) }}</b>
          </div>
        </div>
        <div class="a-of-top3">
          <span class="a-of-top3-t">最慢 Top 3 · 停留倒序</span>
          <button
            v-for="t in w.top"
            :key="t.orderId"
            type="button"
            class="a-of-top3-i"
            @click.stop="openDrawer(t.orderId, w.key)"
          >
            <span class="a-of-id">#{{ t.orderId }}</span>
            <span class="a-of-top3-g">{{ t.product || '（无商品信息）' }}</span>
            <b>{{ dwellText(t.dwellSeconds) }}</b>
          </button>
          <button type="button" class="a-btn a-of-top3-open" @click.stop="openDrawer('', w.key)">打开链路 →</button>
        </div>
      </section>
    </div>

    <!-- ══ ④ 全局流转明细（主从联动 + 前端分页）══════════════════════════════ -->
    <h2 class="a-h2">
      全局流转明细
      <span class="a-h2n">{{ filterLabel }} · {{ fmtInt(tableTotal) }} 单 · 最新在前 · 点行看链路</span>
    </h2>
    <div class="a-card a-of-table-card">
      <div class="a-of-filters">
        <button
          type="button"
          class="a-of-chip"
          :class="{ on: !filterState }"
          @click="filterState = ''; onlyTimeout = false"
        >全部 {{ fmtInt(allCount) }}</button>
        <button
          v-for="s in STATES"
          :key="s.key"
          type="button"
          class="a-of-chip"
          :class="{ on: filterState === s.key }"
          @click="selectState(s.key)"
        >{{ s.label }} {{ fmtInt(counts[s.key]) }}</button>
        <label class="a-of-timeout-toggle">
          <input v-model="onlyTimeout" type="checkbox" />
          仅超时
        </label>
        <span class="a-of-filter-note">
          数据源：{{ demo.on ? '模拟日常流（分析面仍为库内聚合）' : '后端聚合 + SSE' }}
          <template v-if="!demo.on"> · {{ (detailLoading && !pageRows.length) ? '明细加载中…' : (detailError || '服务端分页') }}</template>
        </span>
      </div>

      <div class="a-of-table-wrap">
        <table class="a-of-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>商品名</th>
              <th>当前状态</th>
              <th>已停留</th>
              <th>超时预警</th>
              <th>状态起点</th>
              <th class="a-of-r">金额</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in pageRows"
              :key="r.orderId"
              :class="{ 'is-jump': jumpIds.has(r.orderId), 'is-timeout': r.timeout }"
              @click="openTimeline(r.orderId)"
            >
              <td class="a-of-id">#{{ r.orderId }}</td>
              <td class="a-of-goods" :title="r.product">{{ r.product || '（无商品信息）' }}</td>
              <td>
                <span class="a-of-st" :class="'is-' + r.state">
                  <i class="a-of-dot" :style="{ background: stateColor(r.state) }"></i>{{ stateLabel(r.state) }}
                </span>
              </td>
              <td class="a-of-mono">{{ dwellText(r.dwellSeconds) }}</td>
              <td>
                <span v-if="r.timeout" class="a-of-badge-bad">超时 · 阈值 {{ r.timeoutThresholdHours }}h</span>
                <span v-else class="a-of-badge-ok">—</span>
              </td>
              <td class="a-of-mono">{{ dt(r.stateSince) }}</td>
              <td class="a-of-r a-of-mono">{{ money(r.amount) }}<template v-if="r.quantity > 1"> × {{ r.quantity }}</template></td>
              <td>
                <button type="button" class="a-btn a-of-op" @click.stop="openTimeline(r.orderId)">链路</button>
              </td>
            </tr>
            <tr v-if="!pageRows.length">
              <td colspan="8" class="a-empty">
                <template v-if="first">还没收到第一拍。</template>
                <template v-else-if="!demo.on && detailLoading && !detailRows.length">正在加载{{ filterLabel }}明细…</template>
                <template v-else-if="!demo.on && detailError">明细加载失败：{{ detailError }}</template>
                <template v-else>当前筛选下没有订单 —— 换个状态，或取消「仅超时」。</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <Pager
        :total="tableTotal"
        :limit="pageSize"
        :offset="offset"
        :sizes="PAGE_SIZES"
        unit="单"
        label="流转明细"
        @change="onPage"
      />
    </div>
    </template>

    <!-- 回放模式的状态条：不回显实时计数（实时流已切断），改说回放自己的两个口径。 -->
    <template v-else>
      <header class="a-status a-status--rp">
        <div class="a-stat">
          <b>{{ fmtInt(replayOrderTotal) }}</b>
          <span>回放已见订单（当前各态合计）</span>
        </div>
        <div class="a-stat">
          <b>{{ fmtInt(replayMoveCount) }}</b>
          <span>回放已捕获流转（只增）</span>
        </div>
        <div class="a-tz">
          <span class="a-chip-live">回放中（实时流已切断）</span>
          <span>回放时钟 <b>{{ replayClockText }}</b></span>
          <span>进度 <b>{{ fmtInt(replaySession.cursor) }}</b> / {{ fmtInt(replaySession.count) }}</span>
        </div>
      </header>
      <p class="a-of-rp-note">
        <b>回放 1:1，不做同类合并。</b>
        下面全部是实时页同一套高保真组件（折线 / 环形 / 阶梯 / 直方图），只是数据源换成
        `replaySession.stateChanges` 与回放时钟 —— 不再是另一套「简陋方块」。
        角标 = 回放时钟此刻各态订单数（会加会减）；阶梯小字 = 本批次经过次数（只增）。
      </p>
    </template>

    <template v-if="replaySession.active">
    <!-- ══ ④ 回放主视觉：与实时页同源的高保真组件 ═══════════════════════════════
         ⚠️ 2026-10-17 用户裁定：回放**必须复用真实图表组件**，不能另画一套方块。
         所有图表接 `is-replay`（角标）+ 回放数据（replayCounts / stateChanges 推算），
         不绑定 SSE；随回放时钟逐条重绘。 -->
    <div class="a-of-kpis">
      <div v-for="s in STATES" :key="s.key" class="a-of-kpi is-static">
        <KpiCard
          :label="s.label"
          glyph="flow"
          :value="fmtInt(replayCounts[s.key])"
          :hint="`回放此刻 · 经过 ${fmtInt(batchNodeHits[s.key] || 0)} 次`"
        />
      </div>
    </div>

    <div class="a-of-hero">
      <section class="a-card a-of-trend">
        <header class="a-of-chd">
          <div>
            <h2 class="a-of-ct">回放时钟 · 积压趋势</h2>
            <p class="a-of-cs">X 轴 = 台账原时间窗（{{ replayTrendSpanText }}）· 线随回放逐格长出 · 24 格刻度始终保留</p>
          </div>
          <span class="a-of-live">回放中</span>
        </header>
        <LineChart
          :categories="replayTrendCategories"
          :series="replayTrendSeries"
          height="250px"
          :smooth="false"
          :show-legend="true"
          :grid-right="44"
          is-replay
          empty-overlay
          empty-text="回放尚未产生可绘制的积压样本"
        />
      </section>

      <section class="a-card a-of-health">
        <header class="a-of-chd">
          <h2 class="a-of-ct">回放此刻 · 流转分布</h2>
          <span class="a-of-cs">随回放逐条重建</span>
        </header>
        <DonutChart
          :data="replayDonutData"
          :min-angle="replayDonutMinAngle"
          height="196px"
          :show-legend="false"
          inner-ratio="46%"
          outer-ratio="60%"
          :center-value="fmtInt(replayOrderTotal)"
          center-title="回放此刻订单"
          is-replay
          empty-text="回放尚未产生状态流转"
        />
        <p class="a-of-cs">扇区 = 回放时钟此刻五态订单数；中心 = 当前合计。</p>
      </section>
    </div>

    <div class="a-of-main">
      <section class="a-card a-of-flow">
        <header class="a-of-chd">
          <div>
            <h2 class="a-of-ct">状态流转 · 回放阶梯</h2>
            <p class="a-of-cs">与实时页同一套阶梯与配色 · 条宽 = 回放此刻积压 · 随时钟增减</p>
          </div>
          <span class="a-of-cs">判据链：取消 → 已完成 → 待收货 → 待发货 → 待付款</span>
        </header>
        <div class="a-of-flow-body">
          <div class="a-of-ladder">
            <div
              v-for="n in replayFlowRows"
              :key="n.key"
              class="a-of-step is-static"
              :class="{ 'is-live': pulseNodes.has(n.key) }"
            >
              <span class="a-of-step-top">
                <span class="a-of-step-name">
                  <i class="a-of-dot" :style="{ background: stateColor(n.key), color: stateColor(n.key) }"></i>{{ n.label }}
                </span>
                <span class="a-of-step-num">
                  <b class="a-of-step-n" :class="{ 'is-bump': statePulse[n.key] }">{{ fmtInt(n.count) }}</b>
                </span>
                <span class="a-of-step-qps">流入 {{ qpsText(replayQps.inflow[n.key]) }} · 流出 {{ qpsText(replayQps.outflow[n.key]) }} · 经过 {{ fmtInt(n.hits) }} 次</span>
              </span>
              <span class="a-of-rail" aria-hidden="true">
                <i :style="{ width: n.width + '%', background: stateColor(n.key) }"></i>
              </span>
              <span class="a-of-step-meta">
                <span>回放此刻存量</span>
                <span>{{ n.share }}%</span>
                <span>{{ n.avgLabel }} {{ minuteText(n.avg) }}</span>
              </span>
            </div>

            <div class="a-of-step is-cancel is-static" :class="{ 'is-live': pulseNodes.has('cancelled') }">
              <span class="a-of-step-top">
                <span class="a-of-step-name"><i class="a-of-dot" :style="{ background: stateColor('cancelled'), color: stateColor('cancelled') }"></i>已取消（支线）</span>
                <span class="a-of-step-num">
                  <b class="a-of-step-n" :class="{ 'is-bump': statePulse.cancelled }">{{ fmtInt(replayCounts.cancelled) }}</b>
                </span>
                <span class="a-of-step-qps">取消流入 {{ qpsText(replayQps.inflow.cancelled) }} · 经过 {{ fmtInt(batchNodeHits.cancelled || 0) }} 次</span>
              </span>
              <span class="a-of-rail" aria-hidden="true">
                <i :style="{ width: replayCancelWidth + '%', background: stateColor('cancelled') }"></i>
              </span>
              <span class="a-of-step-meta">
                <span>退出流程 · 不计入积压</span>
                <span>占回放此刻 {{ replayCancelShare }}%</span>
              </span>
            </div>
          </div>
        </div>
      </section>

      <section class="a-card a-of-stream">
        <header class="a-of-chd">
          <div>
            <h2 class="a-of-ct">回放流转 · 逐条经过</h2>
            <p class="a-of-cs">回放时钟 {{ replayClockText }} · 已捕获 {{ fmtInt(replayMoveCount) }} 条</p>
          </div>
          <span class="a-of-live">回放中</span>
        </header>
        <div class="a-of-stream-sum">
          <span>流入 <b>{{ qpsText(replayTotalQpsIn) }}</b></span>
          <span>流出 <b>{{ qpsText(replayTotalQpsOut) }}</b></span>
          <span>回放进度 <b>{{ fmtInt(replaySession.cursor) }}</b> / {{ fmtInt(replaySession.count) }}</span>
          <span class="a-of-mono">{{ replaySession.spanMs ? (replaySession.spanMs / 1000).toFixed(1) + 's' : '—' }}</span>
        </div>
        <!-- 回放流轨：与实时页**同一套**拓扑 / 粒子 / 取消支线，只是数据源换成
             `replaySession.stateChanges`。方向仍是左 → 右；只有真的发生流转时该边才亮。
             ⚠️ 汇总取消（`count=N`）走大号瀑布，逐单取消一颗粒子 —— 与实时页同一函数。 -->
        <div class="a-of-lane" aria-hidden="true">
          <i
            v-for="(e, i) in MAIN_EDGES"
            :key="e.id"
            class="a-of-lane-track"
            :class="{ 'is-on': activeEdges.has(e.id) }"
            :style="laneSegStyle(i)"
          ></i>
          <svg class="a-of-cancel-links" viewBox="0 0 100 100" preserveAspectRatio="none">
            <path
              d="M10,24 C 42,58 70,64 90,82"
              :class="{ on: activeEdges.has(CANCEL_FROM_PAY) }"
              vector-effect="non-scaling-stroke"
            />
            <path
              d="M37,24 C 55,54 74,66 90,82"
              :class="{ on: activeEdges.has(CANCEL_FROM_SHIP) }"
              vector-effect="non-scaling-stroke"
            />
          </svg>
          <span
            v-for="(n, i) in FLOW_NODES"
            :key="n.key"
            class="a-of-lane-node"
            :class="{ 'is-live': pulseNodes.has(n.key) }"
            :style="{ left: LANE_POS[i] + '%' }"
          >
            <i class="a-of-lane-dot" :style="{ background: stateColor(n.key) }"></i>
            <b>{{ n.label }}</b>
            <em>{{ fmtInt(replayCounts[n.key]) }}</em>
          </span>
          <span
            class="a-of-lane-node a-of-lane-cancel"
            :class="{ 'is-live': pulseNodes.has('cancelled') }"
            :style="{ left: LANE_POS[3] + '%' }"
          >
            <i class="a-of-lane-dot" :style="{ background: stateColor('cancelled') }"></i>
            <b>已取消</b>
            <em>{{ fmtInt(replayCounts.cancelled) }}</em>
          </span>
          <span
            v-for="p in laneParticles"
            :key="p.id"
            class="a-of-particle-wrap"
            :style="{ left: p.x1 + '%', width: p.w + '%', '--pcolor': p.color }"
          >
            <i class="a-of-particle"></i>
          </span>
          <i
            v-for="p in cancelParticles"
            :key="p.id"
            class="a-of-cancel-particle"
            :class="['from-' + p.from, { 'is-big': p.big }]"
            :style="{ '--pcolor': stateColor('cancelled'), '--pdelay': (p.delay || 0) + 'ms' }"
          ></i>
        </div>
        <ul v-if="replayRecentMoves.length" class="a-of-stream-list">
          <li v-for="m in replayRecentMoves.slice(0, 8)" :key="m.seq" class="a-of-stream-i">
            <span class="a-of-stream-t">{{ hms(m.ts) }}</span>
            <span class="a-of-id" :class="{ 'is-batch': m.batchCount }">{{ m.batchCount ? `批量取消 ${fmtInt(m.batchCount)} 单` : `#${m.orderId}` }}</span>
            <span class="a-of-stream-d">
              <template v-if="m.from">{{ moveStateLabel(m.from) }} → </template>
              <b>{{ moveStateLabel(m.to) }}</b>
            </span>
            <span v-if="m.result" class="a-of-rpm-r">{{ m.result }}</span>
          </li>
        </ul>
        <div v-else class="a-of-stream-empty">
          <i class="a-of-stream-radar" aria-hidden="true"></i>
          <b>回放尚未产生订单流转</b>
          <span>时钟仍在推进；本批台账里可能只包含非订单事件。</span>
        </div>
      </section>
    </div>

    <h2 class="a-h2">
      耗时分布
      <span class="a-h2n">由回放流转的相邻时间戳推算 · X 轴桶与实时页同一边界 · n=0 时保留坐标轴</span>
    </h2>
    <div class="a-of-hists">
      <section v-for="g in replayDurationGroups" :key="g.key" class="a-card a-of-hist">
        <HistogramChart
          :title="g.title"
          :bins="g.bins"
          :avg-minutes="g.avgMinutes"
          :samples="g.samples"
          height="226px"
          is-replay
        />
      </section>
    </div>

    <!-- ══ 回放流转明细（只读，按原台账时间戳倒序）════════════════════════════════
         ⚠️ 清单来自 `replaySession.stateChanges`（逐步重建时压进 `replayRecentMoves`），
            不是实时流的 `movesById` —— 后者在回放期间没有数据（SSE 已切断）。 -->
    <template v-if="replaySession.active">
      <h2 class="a-h2">
        回放流转明细
        <span class="a-h2n">最近 {{ replayRecentMoves.length }} 条 · 时间 = 原台账时间戳（不是墙钟）</span>
      </h2>
      <div class="a-card">
        <p v-if="!replayRecentMoves.length" class="a-empty">
          本批台账里还没有订单状态流转事件 —— 回放时钟仍在推进；可能是这一轮只跑了非订单场景，
          或台账只载入了前 {{ fmtInt(replaySession.limit || replaySession.count) }} 条。
        </p>
        <ul v-else class="a-of-rpm">
          <li v-for="m in replayRecentMoves" :key="m.seq" class="a-of-rpm-i">
            <span class="a-of-rpm-t">{{ hms(m.ts) }}</span>
            <span class="a-of-id" :class="{ 'is-batch': m.batchCount }">{{ m.batchCount ? `批量取消 ${fmtInt(m.batchCount)} 单` : `#${m.orderId}` }}</span>
            <span class="a-of-rpm-d">{{ moveStateLabel(m.from) }} → <b>{{ moveStateLabel(m.to) }}</b></span>
            <span v-if="m.result" class="a-of-rpm-r">{{ m.result }}</span>
          </li>
        </ul>
      </div>
    </template>
    </template>

    <!-- ══ ⑤ 链路抽屉（点表格行 / 预警卡 / Top N 打开）══════════════════════════════
         数据来源：本页累积的订单行（分析面种子 + SSE 最近 20）与生命周期时间戳。
         抽屉左列 = 当前筛选下按停留倒序的前 30 单；右列 = 选中单的完整生命周期。
         ⚠️ 页面不查「某单历史」端点；时间线只画订单行自带的五个时间戳 + 本会话 moves。 -->
    <div v-if="drawerOpen" class="a-of-mask" @click.self="closeDrawer">
      <aside class="a-of-drawer" role="dialog" aria-modal="true" aria-label="订单链路抽屉">
        <header class="a-of-drawer-hd">
          <div>
            <h3>链路抽屉 · {{ filterLabel }}</h3>
            <p v-if="drawerQuery.trim()">
              匹配 {{ fmtInt(drawerMatchedRows.length) }} 单 · 按已停留倒序（最多显示 50）· 点左列切换右上时间线
            </p>
            <p v-else>{{ fmtInt(drawerRows.length) }} 单 · 按已停留倒序（前 30，含当前选中）· 点左列切换右上时间线</p>
          </div>
          <button type="button" class="a-btn" @click="closeDrawer">关闭</button>
        </header>

        <div class="a-of-drawer-body">
          <div class="a-of-drawer-side">
            <div class="a-of-drawer-search">
              <input
                v-model.trim="drawerQuery"
                type="search"
                class="a-of-search"
                placeholder="搜订单号 / 商品名"
                aria-label="搜索订单号或商品名"
              >
              <span class="a-of-search-n">{{ fmtInt(drawerMatchedRows.length) }} 单</span>
            </div>
            <ul class="a-of-drawer-list">
              <li
                v-for="r in drawerRows"
                :key="r.orderId"
                class="a-of-drawer-i"
                :class="{ on: openedId === r.orderId, 'is-timeout': r.timeout }"
                tabindex="0"
                role="button"
                @click="pickRow(r.orderId)"
                @keydown.enter.prevent="pickRow(r.orderId)"
              >
                <span class="a-of-id">#{{ r.orderId }}</span>
                <span class="a-of-drawer-g" :title="r.product">{{ r.product || '（无商品信息）' }}</span>
                <span class="a-of-st" :class="'is-' + r.state">{{ stateLabel(r.state) }}</span>
                <b class="a-of-mono">{{ dwellText(r.dwellSeconds) }}</b>
                <span v-if="r.timeout" class="a-of-badge-bad">超时</span>
              </li>
              <li v-if="!drawerRows.length" class="a-empty">
                {{ drawerQuery ? '没有匹配的订单号 / 商品名。' : '当前筛选下没有订单。' }}
              </li>
            </ul>
          </div>

          <section class="a-of-drawer-detail">
            <template v-if="openedRow">
              <header class="a-of-drawer-oh">
                <span class="a-of-drawer-now">当前选中</span>
                <b class="a-of-id">#{{ openedRow.orderId }}</b>
                <span class="a-of-st" :class="'is-' + openedRow.state">{{ stateLabel(openedRow.state) }}</span>
                <span v-if="openedRow.timeout" class="a-of-badge-bad">超时 · 阈值 {{ openedRow.timeoutThresholdHours }}h</span>
              </header>
              <p class="a-of-drawer-sub">
                {{ openedRow.product || '（无商品信息）' }} · {{ money(openedRow.amount) }}
                <template v-if="openedRow.quantity > 1"> × {{ openedRow.quantity }}</template>
              </p>
              <dl class="a-of-drawer-kv">
                <div><dt>当前停留</dt><dd>{{ dwellText(openedRow.dwellSeconds) }}</dd></div>
                <div><dt>状态起点</dt><dd>{{ dt(openedRow.stateSince) }}</dd></div>
                <div><dt>建单时刻</dt><dd>{{ dt(openedRow.createdAt) }}</dd></div>
                <div><dt>是否取消</dt><dd>{{ openedRow.cancelled ? '是' : '否' }}</dd></div>
              </dl>

              <ol class="a-of-tl">
                <li
                  v-for="(m, i) in openedTimeline"
                  :key="m.key + '-' + i"
                  class="a-of-tl-i"
                  :class="{
                    'is-done': m.done,
                    'is-current': m.current,
                    'is-future': !m.done && !m.current,
                    'is-cancel': m.cancel,
                  }"
                >
                  <i class="a-of-tl-dot" :style="{ '--tl-color': stateColor(m.key) }"></i>
                  <span class="a-of-tl-t">{{ m.t ? dt(m.t) : '—' }}</span>
                  <span class="a-of-tl-d">
                    <b>{{ m.text }}</b>
                    <small v-if="m.current">当前状态</small>
                    <small v-else-if="!m.done">未到达</small>
                    <small v-else>已走过</small>
                  </span>
                </li>
              </ol>
              <p v-if="!openedTimeline.length" class="a-empty">这张单没有任何生命周期时间戳。</p>
              <p class="a-of-tl-note">
                时间线来自订单行自带的 CreateTime / PaidAt / ShippedAt / ReceivedAt / CancelledAt；
                页面打开之后才发生的流转另由 SSE moves 累积。超时取消只写 CancelledAt，不走状态机。
              </p>
            </template>
            <p v-else class="a-empty">
              从左列点一张单，这里会显示它的完整生命周期。左列已按「停留时长」倒序 ——
              最上面的就是卡得最久的。
            </p>
          </section>
        </div>
      </aside>
    </div>
  </section>
</template>

<!--
  管理端「状态流转」页（C3 块 7 · commit 7，2026-10-14）。

  ⚠️ **零后端改动**：本页消费的 `POST /admin/observe/orders/stream` 是 commit 3 就备好的
     （`OrderStreamController` + `OrderStreamService`），帧里恰好有本页 5 图要的全部数据：
     ① `counts` 五态计数（权威口径，来自 `OrderDao.countOrderStates()` 聚合查询）
     ② `orders` 最近 20 张单（每单已由后端算好 `state` / `step` / `cancelled`）
     ③ `moves` 本拍流转（`kind=transition` 的那些）。
     契约与帧字段见 `api/observe.js` 的 `openOrderStream` 注释 + 后端 `OrderStreamService.frame()`。

  ⚠️ **页面卸载必须 `close()`**（`onBeforeUnmount`）：SSE 的 `StreamingResponseBody` 在服务端占
     **一条异步线程**，不断开就是一条常驻线程 —— 与 `MetricsPanel.vue` 同一条代价。

  ⚠️ **中文标签 commit 9 已收敛**（原 `STATES` 里那 5 个中文串 → `config/labels/observe-labels.js`
     的 `ORDER_FLOW_STATE_LABEL`）：本页只留 key 的**展示顺序**（那是版面问题，仍留在 `STATES` 里），
     中文一律从那一张表取 ⇒ 本页不该再出现第二份五态中文。文案仍是 `UserInfoPage.vue:62` 用户逐字指定的
     「待付款 / 待发货 / 待收货 / 已完成」+ 后端第五态「已取消」。
-->

<script setup>
/* eslint-disable no-undef */ /* 理由见 `ui/KpiCard.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, nextTick, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { openOrderStream, orderDetail, orderInsight } from '../../../api/observe'
// 演示模式（块 7 收尾 ②）：`DemoModeToggle` 摆开关，`demo` 是被 `watch` 的那个状态，
// `openDemoStream` 是与 `openOrderStream` **同形**的假流 —— 见 `connect()` 里那处三元式。
import { demo, openDemoStream } from '../../../api/demo-stream'
import DemoModeToggle from '../../../components/observe/DemoModeToggle.vue'
import KpiCard from '../../../components/ui/KpiCard.vue'
import DonutChart from '../../../components/charts/DonutChart.vue'
import LineChart from '../../../components/charts/LineChart.vue'
import HistogramChart from '../../../components/charts/HistogramChart.vue'
import HealthGauge from '../../../components/charts/HealthGauge.vue'
import Pager from '../../../components/Pager.vue'
import { chartTokens } from '../../../components/charts/chartTokens'
import { orderFlowStateLabel } from '../../../config/labels/observe-labels'
// 全链路联动回放（2026-10-17）：与日志页 / 指标页共用同一个会话时钟。
// 状态页只消费 `replaySession.stateChanges`（订单流转），不另起一份计时器。
import ReplayBanner from '../../../components/observe/ReplayBanner.vue'
import { replaySession } from '../../../api/replay-session'

/** 本页自己累积的订单表上限。⚠️ 必须有：SSE 每秒推 20 条，不封顶跑一晚上就是几万条 DOM。
 *  取 1000 = 后端 `OrderInsightService.ROW_LIMIT` 的同一口径：分析面能给到的行全进表，
 *  「全部 / 按状态筛选」的条数与上方 KPI 对得上（200 时待发货只筛出 40/89，主从联动会缺一截）。
 *  DOM 只渲染当前页（10/20/50），1000 行活在 Map + 数组里，不在 DOM 里。 */
const LIST_CAP = 1000
/** 可选的每页条数（`Pager` 的入参；`Pager` 头注释：卡片大/行窄排布不同，不该一刀切）。 */
const PAGE_SIZES = [10, 20, 50]
/** 高亮持续时长（毫秒）—— 边/节点/行「刚动过」的短时标记（《块 7 参考》§3.3 的 0.5 秒起，取 1.2 秒让肉眼追得上）。 */
const FLASH_MS = 1200
/** 回放的高亮时长：回放 10×，1.2 秒墙钟会糊成一片 ⇒ 收到 400ms（仍看得见「亮一下再熄」）。 */
const REPLAY_FLASH_MS = 400
/** 「回放流转明细」清单最多留多少条（DOM 有界；更早的仍可从日志页查）。 */
const REPLAY_RECENT_CAP = 30
/** 计数卡数字滚动时长（《块 7 参考》§3.3 的 0.3 秒口径）。 */
const TWEEN_MS = 300

/* ── 会话状态持久化（2026-10-17「刷新不能从 0 重算」）──────────────────────────────
   两道保险，各管一段：
     · KeepAlive（`AdminShell.vue` 的 `include` + 本文件的 `name`）—— 路由切走再回来**不卸载**，
       内存里的 `counts / movesById / capturedTotal / liveTail` 原样还在；
     · sessionStorage（下面这几个函数）—— **浏览器刷新**会把内存全清掉，只能落盘再读回。
   两者缺一不可：只有 KeepAlive 挡不住 F5，只有 sessionStorage 会在每次切页时闪一下空表。
   ⚠️ 按数据源分键：演示流与后端真流的订单号/计数不是一套账，混存会让「切回真流」时
      先闪一屏假数据。 */
// v2（2026-10-17）：开关语义从「过滤演练单（默认开）」改成「剔除已取消 / 仅看真实数据
// （默认关）」。旧 v1 快照里的 `filterTest:true` 若被读回，会反着开开关 ⇒ 直接换前缀，
// 旧快照当不存在（它本来也只是本会话的增强项）。
const SESSION_KEY_PREFIX = 'mall:orderFlow:v2:'
const sessionKey = () => SESSION_KEY_PREFIX + (demo.on ? 'demo' : 'live')

/** 五态（顺序 = 上方计数卡与环图的显示顺序）。键名与后端 `counts` / `state` 逐字相同。 */
const STATES = [
  { key: 'waitPay', label: orderFlowStateLabel('waitPay') },
  { key: 'waitShip', label: orderFlowStateLabel('waitShip') },
  { key: 'waitReceive', label: orderFlowStateLabel('waitReceive') },
  { key: 'completed', label: orderFlowStateLabel('completed') },
  { key: 'cancelled', label: orderFlowStateLabel('cancelled') },
]
const STATE_LABEL = Object.fromEntries(STATES.map((s) => [s.key, s.label]))

/* ── 状态机的边（2026-10-17 回放改用真实图表后，SVG 方块已删除；这里只留判据用的边 id） */
/** 主链三条边。`id` 就是 `from + '>' + to`，与后端 `moves` 的 `from`/`to` 拼出来的一模一样。 */
const MAIN_EDGES = [
  { id: 'waitPay>waitShip' },
  { id: 'waitShip>waitReceive' },
  { id: 'waitReceive>completed' },
]
/** 取消两条腿的边 id（回放仍用它们给脉冲 / 轨道分支判定）。 */
const CANCEL_FROM_PAY = 'waitPay>cancelled'
const CANCEL_FROM_SHIP = 'waitShip>cancelled'
/** 本页**画出来了的**全部 5 条边 —— 用来判「这条边图上有吗」，见 `absorbMoves` 的守卫。 */
const KNOWN_EDGES = new Set([...MAIN_EDGES.map((e) => e.id), CANCEL_FROM_PAY, CANCEL_FROM_SHIP])

/* ── 后端 `OrderState` ↔ 本页五态：**唯一**的一层翻译（C3 块 7 · commit 10 补）────────────
   ⚠️ 为什么需要它：后端 `moves[].from` / `moves[].to` 是 **OrderState 枚举名**
      （`order/model/OrderState.java:44-72`），而本页的节点 key / 边 id 是 **DB 派生五态**
      （上面 `STATES` / `MAIN_EDGES`）—— 两套名字**没有一个对得上**
      （`CREATED ≠ waitPay`、`COMPLETED ≠ completed`，连大小写都不同）。
      ⇒ commit 7 那版 `flash(activeEdges, `${from}>${to}`)` 在 `MAIN_EDGES` 里永远查不到，
      `pulseNodes` 拿到的枚举名也不在 `STATES` 的 key 集合里，**两条路都亮不了**：
      机制件（`flash` / `FLASH_MS` / `.is-pulse` / `@keyframes`）全都在，断的是喂数据这一层
      （实测见 `块7-commit10-阶段一核实.txt` §3b「原因二」）。

   ⚠️ **这张表是按后端那条判据链推出来的，不是按名字猜的** ——
      每个枚举名后的三元组出自 `OrderState` 常量自己的 `(payIf, deliveryIf, receiveIf)`
      （同文件 `:44-72`），再逐条套 `OrderDao.countOrderStates` 的判据链
      （取消 → `ReceiveIf=1` 已完成 → `DeliveryIf=1` 待收货 → `PayIf=1` 待发货 → 其余待付款）。

   ⚠️ **三个 `ILLEGAL_*` 不另立一态**：库上这五态是**从三个布尔位派生**的，它根本不认识
      「非法」这个概念 ⇒ 按各自三元组落回既有五态（`(0,1,0)`→待收货、`(0,0,1)`/`(0,1,1)`→已完成）。
      照名字把它们标成「非法」，本页就会长出一个后端不存在的第六态。

   ⚠️ 后端若改了这套枚举或那条链，**这张表必须跟着改** —— 两处是同一件事的两个镜像。 */
const STATE_KEY_OF_ORDER_STATE = {
  CREATED: 'waitPay',                             // (0,0,0)
  PAID: 'waitShip',                               // (1,0,0)
  SHIPPED: 'waitReceive',                         // (1,1,0)
  RECEIVED_NODELIVERY: 'completed',               // (1,0,1) —— ReceiveIf=1，链上先于「已发货」判
  COMPLETED: 'completed',                         // (1,1,1)
  CANCELLED: 'cancelled',                         // 支线：不走三元组，直接落第五态
  ILLEGAL_DELIVERED_UNPAID: 'waitReceive',        // (0,1,0)
  ILLEGAL_RECEIVED_UNPAID: 'completed',           // (0,0,1)
  ILLEGAL_DELIVERED_RECEIVED_UNPAID: 'completed', // (0,1,1)
}

/**
 * `moves[].from` / `moves[].to` → 本页五态 key。**两套词汇都收**：
 *   ① `OrderState` 枚举名（`CREATED` / `PAID` / …）—— 真后端现在发的就是这一套（上面那张表）；
 *   ② 五态 key 本身（`waitPay` / `waitShip` / …）—— 已经是本页的词汇，原样放行。
 * 认不出来（后端新增了枚举、或值本身是 `null`）回 `''`。
 *
 * ⚠️ 为什么收第二套：本页的 mock 探针（`scripts/probes/probe-b7c7-order-flow.mjs`）按五态 key
 * 造帧，而真后端按枚举名发 —— 两套都收，这一层才对「上游用哪套词汇」不敏感；
 * 将来后端哪天归一成五态 key，本页**不用改**。
 *
 * ⚠️ 认不出来回 `''`，而不是「原样返回入参」：宁可这一笔不高亮，也不要把一个本页没有的 key
 * 塞进 `pulseNodes` / `activeEdges` —— 那样图上一个像素都不会变（`FSM_NODES` 里查不到），
 * 却让「为什么没亮」从「表里少了这一条」变成「什么都看不出来」。
 */
const stateKeyOf = (v) => {
  if (!v) return ''
  return STATE_KEY_OF_ORDER_STATE[v] || (STATE_LABEL[v] ? v : '')
}

/** 时间线一步显示什么：枚举名 → 五态中文。⚠️ 认不出来时**原样显示**（不画成 `—`）——
 *  那是「本页不认识它」，不是「这里没有状态」；原样显示至少能让人看见后端发来了什么。 */
const moveStateLabel = (enumName) => {
  const k = stateKeyOf(enumName)
  return k ? STATE_LABEL[k] : (enumName ? String(enumName) : '—')
}

/* ── 高亮节流（《块 7 参考》§3.3 / Q4 裁定）──────────────────────────────────────
   ⚠️ 为什么必须节流：演练下**同一对节点**每秒会被推 200+ 次
      （实测 4670 条 moves / 20 秒，`块7-commit10-阶段一核实.txt` §3b），
      而 `flash()` 每次都会把那个 1.2 秒的定时器**重开一遍**
      ⇒ 观感是「从连上起一直是绿的」，根本看不到「亮一下再熄」。
      限到每秒 `FLASH_HZ_MAX` 次后，人眼仍看成连续闪动，但熄灭看得见了。

   ⚠️ **节流只作用于「视觉高亮」，不作用于「计数」** —— 框下小字走的是另一条路
      （逐条累加，不经过这里）⇒ 节流**不丢信息**，只是不让动画糊成一片。 */
const FLASH_HZ_MAX = 4
/** key → 上次真亮起来的时刻（`performance.now()`）。⚠️ 与三个 Set 一样，卸页时随组件丢掉。 */
const lastFlashAt = new Map()

/**
 * 节流版 {@link flash}：同一个 key 每 `1000 / FLASH_HZ_MAX` 毫秒最多亮一次。
 *
 * <p>⚠️ 被节流掉的那次**不补**（不排队、不延时）—— 高亮说的是「**刚才**动过」，
 * 补一次延迟的闪烁反而是在说假话。
 */
function flashThrottled(setRef, key) {
  const now = performance.now()
  if (now - (lastFlashAt.get(key) || -Infinity) < 1000 / FLASH_HZ_MAX) return
  lastFlashAt.set(key, now)
  flash(setRef, key)
}

/* ── 「本批次」口径（Q4 的框下小字）────────────────────────────────────────────────
   ⚠️ 与上面的 `counts` 是**两个口径**，页面上分行标清（见模板状态机那一段）：
      · `counts`        = 库里此刻每态多少**单**（后端聚合，无时间窗）
      · `batchNodeHits` = 本页看到的、属于**当前批次**的流转，落到各节点的**次数**（前端累加）

   ⚠️ 是「次数」不是「单数」：本计数**不去重** —— 同一张单的同一对 (from,to) 被推两次就记两次。
      它回答的是「这个节点被经过了多少次」，与卡片的「多少单」不同名、不同义，故也不同数。 */
const currentBatch = ref('')
const batchNodeHits = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })

/** 帧里的 `batchCounts`（块 7 · commit 10 · 10b 接通后端）：**本批次**各态多少**单** ——
 *  `OrderDao.countOrderStatesByBatch` 的聚合值，权威口径。`null` = 当前没有批次号（后端回 null，不回 0）。
 *
 *  ⚠️ 它与 `batchNodeHits` **不是同一个量**，所以这两行**不是「同一事实的两处来源」**（核对两遍不算重复）：
 *      · `batchCounts`  = 本批次落的**单**此刻在库里各在哪个态（**去重**；含本页没看见的流转）
 *      · `batchNodeHits` = 本页看见的流转**次数**（**不去重**；只含本页开着的时候发生的）
 *  演练里两者会**不同数**：一张单「建档」时它在 `waitPay` 上是 1 单 / 1 次，随后付款，
 *  **单数**离开 `waitPay` 而**次数**留在那里（历史次数不会减少）。页面分行标清，不合并。 */
const batchCounts = ref(null)

/* ── 页面状态 ───────────────────────────────────────────────────────────────── */
const phase = ref('connecting')            // connecting | live | stopped
const errMsg = ref('')
/**
 * 断线状态：`null` = 没断；否则 `{ err, attempt, nextRetryInMs }`（`openResilientStream` 的
 * `onLost` 原样存下来，commit 10c）。
 * ⚠️ 它和 `errMsg` 是**两个判据**：`errMsg` 只装**真错误**（非 2xx / 帧解析失败 / 读失败），
 * 而「服务端正常收尾」那一格 `errMsg` 是空的 —— 断线条必须覆盖它（见模板里那条注释）。
 */
const lost = ref(null)
const seq = ref(0)
const ts = ref(0)
const counts = ref({})                     // 本拍五态计数（后端权威口径）
/** 本会话捕获的流转总条数。**只增**，与 `movesById` 的淘汰/去重脱钩 ——
 *  旧实现的 `moveTotal` 是 `movesById` 各数组长度之和，表满 1000 行后旧单被淘汰，
 *  这个数会**往回掉**（刷新后从 0 起更是用户直接报的「历史被清零」）。 */
const capturedTotal = ref(0)
/** 「剔除已取消 / 仅看真实数据」开关：只作用于计数、分析面（趋势/直方图/账龄/健康度/
 *  明细种子）与订单表；**不作用于 SSE moves** —— 流轨要看见演练单真的在动
 *  （后端 `/stream?healthy=` 只收窄 counts/orders）。缺省 false = 全量，压测冲击先上屏。 */
const filterHealthy = ref(false)
const ordersById = reactive(new Map())     // orderId → 订单行（值随拍刷新）
const orderIds = ref([])                   // orderId 的顺序（新出现的排前面；超 CAP 从尾部淘汰）
/** 本会话捕获的流转：orderId → `[{from, to, ts}]`（时间线弹层唯一的流转来源）。
 *  ⚠️ **不存后端的 `action`**：它是英文 code（`pay` / `ship` …），直接上屏就是一处
 *  「待中文标签层收敛」的英文，而 `from → to` 已经把这一步说全了（transition 的 action 与
 *  from/to 一一对应，留着纯冗余）。 */
const movesById = reactive(new Map())
const activeEdges = ref(new Set())         // 状态机图上正在高亮的边
const pulseNodes = ref(new Set())          // 正在脉冲的节点
const jumpIds = ref(new Set())             // 刚跳了状态的订单行（列表上闪一下）
const openedId = ref('')                   // 时间线弹层打开的订单
const pageSize = ref(20)
const offset = ref(0)

/* ── 高密度分析面（2026-10-17）──────────────────────────────────────────────
   `insight` 来自 `POST /admin/observe/orders/insight`（一次性 JSON），低频刷新；
   SSE 仍负责每秒变的那几个数。两者**不互相覆盖**：趋势/直方图/账龄是聚合面，
   计数/流轨/表格行是实时面。 */
const insight = ref(null)
const insightAt = ref(0)
const insightLoading = ref(false)
const insightError = ref('')
const filterState = ref('')          // 表格 / 抽屉的主从筛选（'' = 全部）
const onlyTimeout = ref(false)
const drawerOpen = ref(false)
const drawerQuery = ref('')          // 抽屉左列：订单号 / 商品名模糊搜索
/** 本会话最近捕获的流转（流轨渲染原料；有界，避免跑一晚上攒成几万条）。 */
const recentMoves = ref([])
const RECENT_MOVE_CAP = 300
/* ── 全局明细：服务端分页（2026-10-17 · 用户 P0）──────────────────────────────
   此前表格吃的是 `insight.orders`（最近 1000 行），前端再本地过滤 —— Tab 是全库 COUNT、
   表是 1000 行子集 ⇒ 点「待发货 2271」空表。现在真流走 `/orders/detail`：
   `status / timeout / offset / limit` 原样传给后端，`total` 是**该筛选下的真实行数**。
   ⚠️ 演示模式（`demo.on`）仍走本页内存表：假流有自己的世界，把它分页到真库上是两本账。 */
const detailRows = ref([])
const detailTotal = ref(0)
const detailLoading = ref(false)
const detailError = ref('')
/** 请求序号：只认最后一次发出的响应（连点筛选/翻页时旧响应后到不能覆盖新的）。 */
let detailSeq = 0
let detailTimer = 0
let detailDebounce = 0
/** QPS 的原料：**全量** moves 的加权事件（不经过渲染抽样）。
 *  `weight` = 汇总事件的 `count`（普通事件 1）—— 超时任务一条代表上千单，
 *  只数条数会把 1000 单读成 1 单。 */
let qpsEvents = []
const QPS_EVENT_CAP = 4000
/** 当前节点 QPS（滚动 5 秒窗口，单位：单/秒）。 */
const qpsIn = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })
const qpsOut = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })

const target = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })
const shown = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })

/* ── 动效状态（2026-10-17 视觉重构）────────────────────────────────────────────
   ① 趋势「滑动尾窗」：每 `LIVE_SAMPLE_MS` 从本拍 counts 追加一个实时点，
      只保留最近 `LIVE_TAIL_CAP` 个 ⇒ 折线右端肉眼可见地向右生长，随后整窗左移。
   ② 阶梯数字的变化量：每拍与上一拍比，只给**真变了的态**播一次 +N / -N；
      计时器统一进 `timers`，卸页 / 重连时由 `closeStream()` 收走。
   ③ 流轨粒子：每条 SSE move 从 `from` 节点飞向 `to` 节点；只画主链 4 节点
      （取消支线不在这条轨道上，硬画会误导成「取消也是主链的一步」）。 */
const LIVE_TAIL_CAP = 10
const LIVE_SAMPLE_MS = 3000
const liveTail = ref([])
let liveTailAt = 0
const stateDeltas = reactive({ waitPay: null, waitShip: null, waitReceive: null, completed: null, cancelled: null })
const statePulse = reactive({ waitPay: false, waitShip: false, waitReceive: false, completed: false, cancelled: false })
const deltaSeq = { waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 }
const laneParticles = ref([])
let particleSeq = 0
const LANE_POS = [10, 37, 64, 91]
const LANE_INDEX = { waitPay: 0, waitShip: 1, waitReceive: 2, completed: 3 }
/** 第 i 条主链边的轨道段：从第 i 个节点中心到第 i+1 个节点中心（%）。 */
function laneSegStyle(i) {
  const left = LANE_POS[i]
  const right = LANE_POS[i + 1]
  return { left: `${left}%`, width: `${right - left}%` }
}
/* 背压（2026-10-17 用户 P0）：压测一帧可能有数千条 moves。
   · 计数 / 批次 / 累计条数按**全量**算（账不能少）；
   · DOM 与动画只取**均匀抽样**的 ≤120 条，粒子每帧 ≤8 颗。
   这样主线程不会被几千次响应式写入 + setTimeout 打爆，流轨仍然「有东西在动」。 */
const MAX_MOVE_RENDER_PER_FRAME = 120
const MAX_PARTICLES_PER_FRAME = 8
/** 取消支线同时在场的粒子上限。2026-10-17 从 8 提到 18：汇总事件的瀑布一次最多 10 颗，
 *  再加上逐单取消的 8 颗，8 会把瀑布本身截断（只看到后半截）。 */
const CANCEL_PARTICLES_CAP = 18
let frameParticleBudget = 0
let cancelMovesInFrame = 0
const cancelParticles = ref([])
let cancelParticleSeq = 0

/* ── 回放态（2026-10-17 全链路联动）─────────────────────────────────────────────
   ⚠️ 与实时态**并行存在**、互不覆盖：回放期间模板走 `v-else` 分支，实时流的
      `counts / orders / movesById` 一概不画（SSE 也已切断）。退出回放后实时态原样恢复。
   ⚠️ 角标语义（用户 2026-10-17 裁定）：框内角标 = **回放此刻各态订单数**（会加会减），
      框下小字 = 本批次**经过次数**（只增）。两者各自独立累加，不合并成一个数。 */
const replayCounts = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })
/** orderId → 回放重建出的当前五态 key。只装台账里出现过的单，不凭空补。 */
const replayOrderState = new Map()
const replayRecentMoves = ref([])
let replayApplied = 0                    // 已消费到 `replaySession.stateChanges` 的第几条
const replayMoveCount = ref(0)           // 回放已捕获流转条数（只增）
const replayOrderTotal = computed(() => sumOf(replayCounts))
const replayClockText = computed(() => {
  const t = replaySession.clockTs
  if (!t) return '—'
  const d = new Date(t)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`
})

/* ── 回放高保真图表的推算层（2026-10-17 用户裁定：复用真实组件）────────────────────
   ⚠️ 这里只做「从回放流转推出图表要的形状」，不再另画一套 SVG 方块：
     · 趋势 = 按日志时间采样的五态存量（每 2 秒日志时间一个样本，最多 240 个）；
     · 耗时 = 同一订单相邻流转时间戳的差值（付款 / 发货 / 收货三段）；
     · 环形 / 阶梯 = 直接读 `replayCounts`（逐条重建的当前存量）。
   X 轴刻度按**整批台账的时间窗**固定切 24 格 —— 回放刚开始线是空的，但轴永远在。 */
const REPLAY_TREND_SLOT_COUNT = 24
const REPLAY_TREND_SAMPLE_MS = 2000
const REPLAY_TREND_CAP = 240
const REPLAY_DURATION_CAP = 5000
const replaySamples = ref([])
let replaySampleAt = 0
const replayDurations = reactive({ pay: [], ship: [], receive: [] })
const replayLife = new Map()

/** 回放时间窗的 25 个刻度（含两端）；极端单秒批次也铺满，避免轴塌陷。 */
const replayTrendSlotTs = computed(() => {
  const s = num(replaySession.startTs)
  const e = num(replaySession.endTs)
  if (!s || !e || e <= s) {
    const base = s || num(replaySession.clockTs) || Date.now()
    return Array.from({ length: REPLAY_TREND_SLOT_COUNT + 1 }, (_, i) => base + i * 1000)
  }
  const step = (e - s) / REPLAY_TREND_SLOT_COUNT
  return Array.from({ length: REPLAY_TREND_SLOT_COUNT + 1 }, (_, i) => s + i * step)
})
const replayTrendSpanText = computed(() => {
  const s = num(replaySession.startTs)
  const e = num(replaySession.endTs)
  if (!s || !e) return '—'
  return `${hms(s)} → ${hms(e)}`
})
const replayTrendCategories = computed(() => {
  const span = num(replaySession.endTs) - num(replaySession.startTs)
  // 跨度超过 6 小时时标签带日期，避免跨天重复成一片 HH:mm
  if (span > 6 * 3600 * 1000) {
    return replayTrendSlotTs.value.map((ts) => {
      const d = new Date(ts)
      return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:00`
    })
  }
  return replayTrendSlotTs.value.map((ts) => hms(ts))
})
const replayTrendSeries = computed(() => {
  const tsList = replayTrendSlotTs.value
  const samples = replaySamples.value
  const clock = num(replaySession.clockTs)
  const defs = [
    { key: 'waitPay', name: '待付款', colorIndex: 4 },
    { key: 'waitShip', name: '待发货', colorIndex: 1 },
    { key: 'waitReceive', name: '待收货', colorIndex: 6 },
  ]
  return defs.map((d) => {
    let seen = 0
    const data = tsList.map((ts) => {
      while (seen < samples.length && samples[seen].ts <= ts) seen += 1
      // 回放时钟还没走到 / 还没有样本 ⇒ null：画断点，不拿 0 谎称「积压为 0」
      if (!clock || ts > clock || seen === 0) return null
      return num(samples[seen - 1][d.key])
    })
    return { name: d.name, data, area: true, colorIndex: d.colorIndex }
  })
})
const replayDonutData = computed(() => {
  const t = chartTokens()
  const c = stateColors(t)
  return STATES.map((s) => ({
    name: s.label,
    value: num(replayCounts[s.key]),
    itemStyle: { color: c[s.key] },
  }))
})
const replayDonutMinAngle = computed(() => {
  const vals = replayDonutData.value.map((d) => num(d.value)).filter((v) => v > 0)
  if (vals.length < 2) return 0
  const sum = vals.reduce((a, b) => a + b, 0)
  return Math.max(...vals) / sum >= 0.95 ? 10 : 0
})
const REPLAY_AVG_KEY = { waitPay: 'pay', waitShip: 'ship', waitReceive: 'receive', completed: 'receive' }
const REPLAY_AVG_LABEL = { waitPay: '付款均耗', waitShip: '发货均耗', waitReceive: '收货均耗', completed: '全链路' }
/** 回放阶梯：与实时 `flowRows` 同一形态，只是数据源换成 `replayCounts`。 */
const replayFlowRows = computed(() => {
  const max = Math.max(1, ...FLOW_NODES.map((n) => num(replayCounts[n.key])))
  const tot = replayOrderTotal.value
  return FLOW_NODES.map((n) => {
    const count = num(replayCounts[n.key])
    return {
      ...n,
      count,
      width: Math.max(count ? 4 : 0, (count / max) * 100),
      hits: num(batchNodeHits[n.key]),
      share: tot ? ((count / tot) * 100).toFixed(1) : '0.0',
      avg: replayAvgMinutes(REPLAY_AVG_KEY[n.key]),
      avgLabel: REPLAY_AVG_LABEL[n.key],
    }
  })
})
const replayCancelWidth = computed(() => {
  const max = Math.max(1, ...FLOW_NODES.map((n) => num(replayCounts[n.key])), num(replayCounts.cancelled))
  return Math.max(num(replayCounts.cancelled) ? 4 : 0, (num(replayCounts.cancelled) / max) * 100)
})
const replayCancelShare = computed(() => {
  const tot = replayOrderTotal.value
  return tot ? ((num(replayCounts.cancelled) / tot) * 100).toFixed(1) : '0.0'
})
/**
 * 回放语境下的流入/流出 QPS：取**回放时钟前 5 秒日志时间**内的流转，按单数加权 ÷ 5。
 * ⚠️ 窗口边界用历史时间戳（`m.ts` 与 `clockTs`），不是 `Date.now()` —— 回放里「最近 5 秒」
 *    指的是录像里的 5 秒，墙钟早已过去。
 * ⚠️ 汇总事件按 `count` 加权：一条「批量取消 39 单」在取消节点上是 7.8/s，而不是 0.2/s。
 */
const replayQps = computed(() => {
  const inflow = {}
  const outflow = {}
  STATES.forEach((s) => { inflow[s.key] = 0; outflow[s.key] = 0 })
  const clock = num(replaySession.clockTs)
  if (clock) {
    for (const m of replaySession.stateChanges) {
      if (m.ts > clock || m.ts <= clock - 5000) continue
      const w = num(m.count) || 1
      const to = stateKeyOf(m.to)
      const from = stateKeyOf(m.from)
      if (to) inflow[to] += w
      if (from) outflow[from] += w
    }
  }
  STATES.forEach((s) => {
    inflow[s.key] /= 5
    outflow[s.key] /= 5
  })
  return { inflow, outflow }
})
const replayTotalQpsIn = computed(() => FLOW_NODES.reduce((a, n) => a + num(replayQps.value.inflow[n.key]), 0))
const replayTotalQpsOut = computed(() => FLOW_NODES.reduce((a, n) => a + num(replayQps.value.outflow[n.key]), 0))
function replayAvgMinutes(segment) {
  const arr = replayDurations[segment] || []
  if (!arr.length) return 0
  return Math.round((arr.reduce((a, b) => a + b, 0) / arr.length) * 10) / 10
}
/** 与后端 `OrderInsightService#duration` 同一套桶边界 —— 回放与实时的横轴可逐桶对照。 */
const REPLAY_DURATION_BUCKETS = {
  pay: {
    edges: [0, 5, 15, 60, 360, 1440],
    labels: ['0–5分', '5–15分', '15–60分', '1–6小时', '6–24小时', '24小时+'],
  },
  ship: {
    edges: [0, 60, 360, 1440, 4320, 10080],
    labels: ['0–1小时', '1–6小时', '6–24小时', '1–3天', '3–7天', '7天+'],
  },
  receive: {
    edges: [0, 360, 1440, 4320, 10080],
    labels: ['0–6小时', '6–24小时', '1–3天', '3–7天', '7天+'],
  },
}
function replayHistogram(segment) {
  const { edges, labels } = REPLAY_DURATION_BUCKETS[segment]
  const arr = replayDurations[segment] || []
  const counts = labels.map(() => 0)
  let max = 0
  for (const minutes of arr) {
    let idx = 0
    for (let i = edges.length - 1; i >= 0; i -= 1) {
      if (minutes >= edges[i]) { idx = i; break }
    }
    counts[idx] += 1
    if (minutes > max) max = minutes
  }
  return {
    bins: labels.map((label, i) => ({
      label,
      count: counts[i],
      fromMinutes: edges[i],
      toMinutes: i + 1 < edges.length ? edges[i + 1] : Math.max(max, edges[i] * 2),
    })),
    samples: arr.length,
    avgMinutes: replayAvgMinutes(segment),
  }
}
const replayDurationGroups = computed(() => ([
  { key: 'pay', title: '付款耗时 · 回放推算', ...replayHistogram('pay') },
  { key: 'ship', title: '发货耗时 · 回放推算', ...replayHistogram('ship') },
  { key: 'receive', title: '收货耗时 · 回放推算', ...replayHistogram('receive') },
]))

/** 记一个回放趋势样本（每 2 秒日志时间最多一个）。
 *  @param {boolean} force 没有样本时强制落一个起点
 *  @param {number} [tsOverride] 用**流转自己的历史时间戳**采样（拖拽重建时，
 *         所有流转是在同一拍里补 apply 的，若仍读 `clockTs` 会把整段历史压成目标时刻一个点） */
function noteReplaySample(force, tsOverride) {
  const ts = num(tsOverride) || num(replaySession.clockTs)
  if (!ts) return
  if (!force && ts - replaySampleAt < REPLAY_TREND_SAMPLE_MS) return
  replaySampleAt = ts
  replaySamples.value = [...replaySamples.value, {
    ts,
    waitPay: replayCounts.waitPay,
    waitShip: replayCounts.waitShip,
    waitReceive: replayCounts.waitReceive,
    completed: replayCounts.completed,
  }].slice(-REPLAY_TREND_CAP)
}
/** 从相邻流转时间戳推算一段耗时（分钟）。首见即中段（台账被截断）⇒ 该段不补 0 假样本。 */
function noteReplayLifecycle(m, toKey) {
  let life = replayLife.get(m.orderId)
  if (!life) {
    life = { created: 0, paid: 0, shipped: 0, completed: 0 }
    replayLife.set(m.orderId, life)
  }
  if (!life.created && toKey === 'waitPay') life.created = m.ts
  if (toKey === 'waitShip' && !life.paid) {
    life.paid = m.ts
    if (life.created && m.ts >= life.created) pushReplayDuration('pay', (m.ts - life.created) / 60000)
  } else if (toKey === 'waitReceive' && !life.shipped) {
    life.shipped = m.ts
    if (life.paid && m.ts >= life.paid) pushReplayDuration('ship', (m.ts - life.paid) / 60000)
  } else if (toKey === 'completed' && !life.completed) {
    life.completed = m.ts
    if (life.shipped && m.ts >= life.shipped) pushReplayDuration('receive', (m.ts - life.shipped) / 60000)
  }
}
function pushReplayDuration(segment, minutes) {
  if (!Number.isFinite(minutes) || minutes < 0) return
  const arr = replayDurations[segment]
  arr.push(minutes)
  if (arr.length > REPLAY_DURATION_CAP) arr.splice(0, arr.length - REPLAY_DURATION_CAP)
}

let stream = null
let rafId = 0
let insightTimer = 0                      // 分析面的低频刷新（15 秒；不是 SSE 的 1 秒）
const timers = new Set()                   // 所有短时标记的定时器 —— 卸页时必须逐个清（否则回调打到已卸载的组件上）

/* ── 小工具（本页自备：与 `MetricsPanel.vue` 的口径同源，不抽公共模块）────────────── */
const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
// ⚠️ 必须 `Math.round`：计数卡有 300ms 补间，取整前的值会是 `17.995` 这种小数
// （演示流每秒都变，截图正好抓到中间帧）。计数一律按整数显示。
const fmtInt = (v) => Math.round(Number(v) || 0).toLocaleString('zh-CN')
function pad2(n) {
  return String(n).padStart(2, '0')
}
/**
 * 数据库时间文本 → `Date`。
 * ⚠️ 后端生命周期列是**无时区 UTC 文本**（`@@system_time_zone=UTC`，见
 * `OrderInsightService` 类注释）—— 直接 `new Date('2026-10-17 11:55:24')` 会被当成本地时间，
 * 显示早 8 小时。没有显式时区的字符串一律补 `Z` 再解析；epoch 毫秒原样。
 */
function toDate(v) {
  if (typeof v === 'number') return new Date(v)
  let s = String(v).trim().replace(' ', 'T')
  if (!/([zZ]|[+-]\d{2}:?\d{2})$/.test(s)) s += 'Z'
  return new Date(s)
}

/** 统一成 `HH:mm:ss`。⚠️ `createdAt` 是后端 UTC 文本，`moves[].ts` 是 epoch 毫秒 —— 两种都吃。 */
function hms(v) {
  if (v === null || v === undefined || v === '') return '—'
  const d = toDate(v)
  if (Number.isNaN(d.getTime())) return String(v)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}
/** 金额：后端 `Price` 是数字（元）。空值画 `—` 而不是 `¥0.00`（那是两个不同的意思）。 */
function money(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? '¥' + n.toFixed(2) : '—'
}
const stateLabel = (k) => STATE_LABEL[k] || k || '—'

/* ── 一段短时标记：加进 Set、`FLASH_MS` 后自动移除 ───────────────────────────────
   ⚠️ 三个 Set 用同一个手法（边 / 节点 / 行），但**各自独立**：一条边亮了不该顺带点亮别的。 */
function flash(setRef, key, ms = FLASH_MS, keepIfOn = false) {
  // 回放用 `keepIfOn`：已经亮着的 key 不重新计时 —— 回放 10×、一帧可吐 200 条，
  // 每条都重开一个定时器既浪费又会让高亮「永远不熄」。熄了之后再来才重新亮。
  if (keepIfOn && setRef.value.has(key)) return
  const next = new Set(setRef.value)
  next.add(key)
  setRef.value = next
  const t = setTimeout(() => {
    timers.delete(t)
    const after = new Set(setRef.value)
    after.delete(key)
    setRef.value = after
  }, ms)
  timers.add(t)
}

/* ── 派生量 ─────────────────────────────────────────────────────────────────── */
const first = computed(() => seq.value === 0 && !errMsg.value)
const total = computed(() => num(counts.value.total) || sumOf(counts.value))
const phaseText = computed(() => (phase.value === 'live' ? '已连接' : (phase.value === 'stopped' ? '已断开' : '连接中')))

/**
 * 断线条那句话（commit 10c）。⚠️ 两种都要写全：真错误（`err`）与**服务端正常收尾**
 * （`err === null`，例如后端重启、中间层掐连接、`async.request-timeout` 到点）——
 * 后者不是故障，但页面上的五态计数同样停在断开前那一拍，不说清楚就回到「静默陈旧」。
 * ⚠️ `nextRetryInMs / 1000` 不必格式化：退避档位就是 500/1000/2000/4000/8000，
 * 除以 1000 恰好是 0.5 / 1 / 2 / 4 / 8。
 */
const lostText = computed(() => {
  const s = lost.value
  if (!s) return ''
  const head = s.err
    ? `状态流转流中断：${(s.err && s.err.message) || '连接中断'}`
    : '状态流转流已断开（服务端正常收尾）'
  return `${head} —— 上面的数字停在断开前最后一拍，${s.nextRetryInMs / 1000} 秒后自动重连（第 ${s.attempt} 次）`
})
const lastTickAt = computed(() => (ts.value ? hms(ts.value) : '—'))
function sumOf(c) {
  return STATES.reduce((a, s) => a + num(c && c[s.key]), 0)
}
/** 本会话捕获到的流转总条数（单调递增，见 `capturedTotal` 的注释）。 */
const moveTotal = computed(() => capturedTotal.value)

/** 计数卡的副行：「占总数几成」。分母为 0（空表）时不给数 —— 那是「还没数据」不是「0%」。 */
function shareText(key) {
  const t = total.value
  if (!t) return '总数还是 0'
  return `占 ${((num(counts.value[key]) / t) * 100).toFixed(1)}%`
}

/**
 * 环图数据。
 * ⚠️ 颜色**显式给每一项**（`itemStyle.color`）而不是靠 `DonutChart` 的自动色板顺序：
 *    自动色板会把「已完成」排到 `--c4`（琥珀）上 —— 语义上说不通，且一旦有人调整 5 态顺序，
 *    颜色就会跟着漂。这里把「哪个态是哪个色」写死，与 `<style>` 里卡片色点用同一批 CSS 变量。
 * ⚠️ 也正因如此，环图**关掉图例**（`:show-legend="false"`）：ECharts 的图例色块走的是
 *    自动色板、不认 `itemStyle.color`，留着它反倒与扇区颜色不一致；扇区自带的
 *    `{b} {d}%` 标签已经把「哪个色是什么」写在图上了。
 * ⚠️ 色值必须取**计算值**（`#f16400` 这种）：传 `'var(--c4)'` 给 canvas，ECharts 解析不了，
 *    会**静默**退回它自己的默认色板（见 `chartTokens.js` 头注释）。
 */
const donutData = computed(() => {
  const t = chartTokens()
  const c = stateColors(t)
  return STATES.map((s) => ({
    name: s.label,
    value: num(counts.value[s.key]),
    itemStyle: { color: c[s.key] },
  }))
})

/**
 * 环图最小扇区角度（2026-10-17）：压测后「已取消」常占 99%，其余四态会被挤成看不见的线。
 * 判据 = **有至少两态非零** 且 **最大一态占比 ≥ 95%** ⇒ 给每个扇区 10° 保底。
 * ⚠️ 正常分布时不传（0）：`minAngle` 会夸大真实占比，不能无脑常开。
 */
const donutMinAngle = computed(() => {
  const vals = donutData.value.map((d) => num(d.value)).filter((v) => v > 0)
  if (vals.length < 2) return 0
  const sum = vals.reduce((a, b) => a + b, 0)
  return Math.max(...vals) / sum >= 0.95 ? 10 : 0
})

/** 五态色：四个语义位取自图表色板（读的是**当前主题**的计算值），取消取中性灰。 */
function stateColors(t) {
  return {
    waitPay: t.series[3],      // --c4 琥珀：等待处理
    waitShip: t.series[0],     // --c1 蓝：已付款、待发货
    waitReceive: t.series[5],  // --c6 青：在途
    completed: t.series[2],    // --c3 绿：走完了
    cancelled: cssVar('--ink-3'), // 灰：退出了流程，但它不是「错误」
  }
}
function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/* ── 高密度分析面（2026-10-17）──────────────────────────────────────────────
   数据来自 `orderInsight()`；SSE 只覆盖「每拍会变」的计数与 moves。
   ⚠️ 这里**不再前端拼 SQL / 重算全表**：趋势、分桶、Top N 都由后端算好，
   页面只做「翻译成图表 option」和「按状态过滤」。 */
const FLOW_NODES = [
  { key: 'waitPay', label: '待付款', colorIndex: 4, threshold: '30 分钟' },
  { key: 'waitShip', label: '待发货', colorIndex: 1, threshold: '24 小时' },
  { key: 'waitReceive', label: '待收货', colorIndex: 6, threshold: '48 小时' },
  { key: 'completed', label: '已完成', colorIndex: 3, threshold: '—' },
]
const TREND_COLOR = { waitPay: 4, waitShip: 1, waitReceive: 6 }

const trend = computed(() => insight.value?.trend || { categories: [], series: [], anchoredToLatest: false })
/**
 * 趋势兜底刻度：分析面还没回来 / 后端时间戳全空时，仍铺满「最近 24 小时」的整点刻度。
 * ⚠️ 这是「图表不能变白板」的前端保底：X 轴即使没有一条线，也必须留在画布上。
 */
const fallbackTrendCategories = computed(() => {
  const now = new Date()
  now.setMinutes(0, 0, 0)
  const out = []
  for (let i = 24; i >= 0; i -= 1) {
    const d = new Date(now.getTime() - i * 3600 * 1000)
    out.push(`${pad2(d.getHours())}:00`)
  }
  return out
})
/** 24h 历史桶 + 右侧实时尾点。尾点是本页自己按本拍 counts 采的，不等 insight 的 15 秒刷新。 */
const trendCategories = computed(() => {
  const base = trend.value.categories || []
  const cats = base.concat(liveTail.value.map((t) => t.label))
  return cats.length ? cats : fallbackTrendCategories.value
})
const trendSeries = computed(() => {
  const cats = trendCategories.value
  const tail = liveTail.value
  return (trend.value.series || []).map((s) => {
    const base = s.data || []
    const data = base.concat(tail.map((t) => num(t[s.key])))
    const last = data.length ? data[data.length - 1] : null
    // 末点标出「此刻积压」—— 平线也能一眼读到当前值，不靠悬停。
    // ⚠️ 三条线在尾窗可能贴得很近（演示刚开始时两条同为 18）⇒ 标签必须分侧，
    //    否则三个数字会叠成一坨（2026-10-17 截帧实测）。
    const labelPos = s.key === 'waitPay' ? 'top' : (s.key === 'waitShip' ? 'bottom' : 'right')
    const markPoints = last !== null && last > 0 && cats.length
      ? [{ x: cats[cats.length - 1], y: last, text: String(last), position: labelPos }]
      : []
    return {
      name: s.name,
      data,
      area: true,
      colorIndex: TREND_COLOR[s.key] || 1,
      markPoints,
    }
  })
})
const trendAnchored = computed(() => !!trend.value.anchoredToLatest)
const trendWindowText = computed(() => {
  const t = trend.value
  if (!t.from) return '—'
  return `${t.from} → ${t.to}${t.anchoredToLatest ? '（锚到库内最后活动）' : ''}`
})

const durations = computed(() => insight.value?.durations || {})
/** 无分析面时的零样本桶：把边界与标签照常给出 ⇒ 直方图保留坐标轴，只覆盖空态胶囊。 */
function emptyDurationBins(segment) {
  const { edges, labels } = REPLAY_DURATION_BUCKETS[segment]
  return {
    bins: labels.map((label, i) => ({
      label,
      count: 0,
      fromMinutes: edges[i],
      toMinutes: i + 1 < edges.length ? edges[i + 1] : edges[i] * 2,
    })),
    samples: 0,
    avgMinutes: 0,
  }
}
const durationGroups = computed(() => ([
  { key: 'pay', title: '付款耗时 · CreateTime → PaidAt', ...(durations.value.pay || emptyDurationBins('pay')) },
  { key: 'ship', title: '发货耗时 · PaidAt → ShippedAt', ...(durations.value.ship || emptyDurationBins('ship')) },
  { key: 'receive', title: '收货耗时 · ShippedAt → ReceivedAt', ...(durations.value.receive || emptyDurationBins('receive')) },
]))
const slowestRows = computed(() => insight.value?.slowest || [])

const cumulative = computed(() => {
  const c = counts.value || {}
  const completed = num(c.completed)
  const waitReceive = num(c.waitReceive)
  const waitShip = num(c.waitShip)
  return {
    created: total.value,
    paid: waitShip + waitReceive + completed,
    shipped: waitReceive + completed,
    received: completed,
  }
})

const flowRows = computed(() => {
  const c = counts.value || {}
  const reach = cumulative.value
  const maxCount = Math.max(1, ...FLOW_NODES.map((n) => num(c[n.key])))
  const rows = [
    {
      ...FLOW_NODES[0],
      count: num(c.waitPay),
      reach: reach.created,
      conversion: 100,
      avg: durations.value.pay?.avgMinutes || 0,
      avgLabel: '付款均耗',
    },
    {
      ...FLOW_NODES[1],
      count: num(c.waitShip),
      reach: reach.paid,
      conversion: reach.created ? (reach.paid / reach.created) * 100 : 0,
      avg: durations.value.ship?.avgMinutes || 0,
      avgLabel: '发货均耗',
    },
    {
      ...FLOW_NODES[2],
      count: num(c.waitReceive),
      reach: reach.shipped,
      conversion: reach.paid ? (reach.shipped / reach.paid) * 100 : 0,
      avg: durations.value.receive?.avgMinutes || 0,
      avgLabel: '收货均耗',
    },
    {
      ...FLOW_NODES[3],
      count: num(c.completed),
      reach: reach.received,
      conversion: reach.shipped ? (reach.received / reach.shipped) * 100 : 0,
      avg: durations.value.receive?.avgMinutes || 0,
      avgLabel: '全链路',
    },
  ]
  return rows.map((r) => ({
    ...r,
    width: Math.max(r.count ? 4 : 0, (r.count / maxCount) * 100),
    timeout: timeoutOf(r.key),
    inQps: qpsIn[r.key] || 0,
    outQps: qpsOut[r.key] || 0,
  }))
})

const agingRows = computed(() => ([
  {
    key: 'waitShip',
    title: '待发货超时积压',
    threshold: '> 24 小时',
    ...(insight.value?.aging?.waitShip || { total: 0, timeout: 0, bins: [], avgHours: 0, maxHours: 0 }),
    count: num((counts.value || {}).waitShip),
    top: slowestRows.value.filter((r) => r.state === 'waitShip').slice(0, 3),
  },
  {
    key: 'waitReceive',
    title: '待收货超时积压',
    threshold: '> 48 小时',
    ...(insight.value?.aging?.waitReceive || { total: 0, timeout: 0, bins: [], avgHours: 0, maxHours: 0 }),
    count: num((counts.value || {}).waitReceive),
    top: slowestRows.value.filter((r) => r.state === 'waitReceive').slice(0, 3),
  },
]))

const healthView = computed(() => insight.value?.health || {
  score: 0, grade: '—', timeoutRate: 0, timeoutCount: 0, activeCount: 0,
  backlogShare: 0, avgDwellHours: 0,
})

const insightAtText = computed(() => (insightAt.value ? hms(insightAt.value) : '—'))

/** 拉一次分析面；不阻塞 SSE。失败时页面仍能用流里的订单行。
 *  ⚠️ 开关在请求途中变化时不能**静默丢掉**那一次：记一个 dirty 标记，
 *  本次收尾后再拉一遍（否则图表会停在旧口径，直到 15 秒后的定时刷新）。 */
let insightDirty = false
async function loadInsight() {
  if (insightLoading.value) {
    insightDirty = true
    return
  }
  insightLoading.value = true
  insightError.value = ''
  try {
    const data = await orderInsight(filterHealthy.value)
    insight.value = data
    insightAt.value = num(data.ts) || Date.now()
    // 明细不再来自 insight（见 `detailRows` 注释）；这里只把「最慢 Top N」记进订单表，
    // 否则点开 Top3 链路抽屉时 `openedRow` 查不到那一单、右栏空白。
    rememberRows(Array.isArray(data.slowest) ? data.slowest : [])
    // 第一拍 SSE 还没到时，顶部 Tab / 「全部 N」先用分析面的权威 counts 铺上，避免 0。
    if (!Object.keys(counts.value || {}).length && data.counts) {
      counts.value = data.counts
      STATES.forEach((s) => {
        target[s.key] = num(counts.value[s.key])
        shown[s.key] = target[s.key]
      })
    }
  } catch (e) {
    insightError.value = (e && e.message) || '分析面加载失败'
  } finally {
    insightLoading.value = false
    if (insightDirty) {
      insightDirty = false
      loadInsight()
    }
  }
}

/**
 * 明细页 / Top N → 本页订单表：**更新行、把新 id 插到最前**，不动已有顺序。
 * 表格本身渲染 `detailRows`（服务端那一页），这里维护 Map 只为抽屉 / 时间线
 * （`openedRow`）能查到单；「新出现的在前」与 SSE `absorbOrders` 同口径。
 */
function rememberRows(rows) {
  const fresh = []
  for (const raw of rows) {
    const id = raw && raw.orderId
    if (id === null || id === undefined) continue
    const idStr = String(id)
    if (!ordersById.has(idStr)) fresh.push(idStr)
    ordersById.set(idStr, toRow(raw))
  }
  if (!fresh.length) return
  const head = orderIds.value.filter((id) => !fresh.includes(id))
  orderIds.value = fresh.concat(head).slice(0, LIST_CAP)
}

/** 原始行 → 本页统一行形状（SSE 与分析面同形；这里只做防御性收口）。 */
function toRow(raw) {
  return {
    orderId: String(raw.orderId),
    customerId: raw.customerId,
    merchantId: raw.merchantId,
    amount: raw.amount,
    quantity: raw.quantity,
    product: raw.product,
    createdAt: raw.createdAt,
    paidAt: raw.paidAt,
    shippedAt: raw.shippedAt,
    receivedAt: raw.receivedAt,
    cancelledAt: raw.cancelledAt,
    state: raw.state,
    step: num(raw.step),
    cancelled: !!raw.cancelled,
    stateSince: raw.stateSince,
    dwellSeconds: raw.dwellSeconds === null || raw.dwellSeconds === undefined ? null : num(raw.dwellSeconds),
    timeout: !!raw.timeout,
    timeoutThresholdHours: raw.timeoutThresholdHours,
  }
}

/** 停留时长：秒 → 「53天0小时」「7小时12分」「28分」。 */
function dwellText(seconds) {
  if (seconds === null || seconds === undefined) return '—'
  const s = Math.max(0, num(seconds))
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d}天${h}小时`
  if (h > 0) return `${h}小时${m}分`
  return `${m}分`
}

/** 日期时间（`MM-dd HH:mm`）；时间轴弹层里用。 */
function dt(v) {
  if (v === null || v === undefined || v === '') return '—'
  const d = toDate(v)
  if (Number.isNaN(d.getTime())) return String(v)
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** 该节点有没有超时积压（只有待发货 / 待收货算）。 */
function timeoutOf(key) {
  return num((insight.value?.aging?.[key] || {}).timeout)
}

/** 主从联动：点节点（含「已取消」支线）切换筛选；再点一次取消。 */
function selectState(key) {
  if (!STATE_LABEL[key]) return
  filterState.value = filterState.value === key ? '' : key
  onlyTimeout.value = false
  offset.value = 0
}

/** 打开链路抽屉；带 state 时同时把表格筛到该状态。
 *  ⚠️ 没点名订单（点预警卡 / 「打开链路」）时**自动选最慢的一单** —— 否则右列是空的，
 *  用户看到的是「请从左列点一张单」，而这张卡的卖点正是「最慢的那单长什么样」。 */
/** 从表格 / 预警卡打开抽屉时，选中的单可能排在左列很靠后（按停留倒序，不是表格顺序）。
 *  打开后把左列滚到选中行，否则用户只看得到右列换了单、左列却没有高亮。 */
function revealDrawerSelection() {
  nextTick(() => {
    const list = document.querySelector('.a-of-drawer-list')
    const el = list && list.querySelector('.a-of-drawer-i.on')
    if (!list || !el) return
    const lr = list.getBoundingClientRect()
    const er = el.getBoundingClientRect()
    if (er.top < lr.top || er.bottom > lr.bottom) {
      list.scrollTop += er.top - lr.top - (list.clientHeight - el.clientHeight) / 2
    }
  })
}

function openDrawer(orderId, state) {
  drawerQuery.value = ''
  if (state) {
    filterState.value = state
    offset.value = 0
  }
  let id = orderId || ''
  if (!id) {
    const rows = listRows.value.filter((r) => !state || r.state === state)
    const slowest = rows.slice().sort((a, b) => num(b.dwellSeconds) - num(a.dwellSeconds))[0]
    if (slowest) id = slowest.orderId
  }
  drawerOpen.value = true
  openedId.value = id
  revealDrawerSelection()
}

function closeDrawer() {
  drawerOpen.value = false
  drawerQuery.value = ''
  closeTimeline()
}

/**
 * 滚动 5 秒窗口的 QPS（每秒流入 / 流出多少**单**）。
 *
 * <p>⚠️ 原料是 `qpsEvents`（**全量** moves，不经过渲染抽样），不是 `recentMoves`
 * （后者为背压只留 ≤120 条/帧）。逐条按 `weight` 加权：
 *   普通流转 = 1 单；超时取消的汇总事件 = `count` 单（一条代表上千单）。
 * 只数条数 ⇒ 一批 1000 单的超时取消在页面上只跳 0.2/s；加权后才是真实的 200/s。
 */
function computeQps() {
  const cutoff = Date.now() - 5000
  // 顺手裁掉窗口外的（数组有界，不会跑一晚上攒成十万条）。
  qpsEvents = qpsEvents.filter((e) => e.ts >= cutoff)
  STATES.forEach((s) => {
    qpsIn[s.key] = 0
    qpsOut[s.key] = 0
  })
  for (const e of qpsEvents) {
    if (e.toKey) qpsIn[e.toKey] += e.weight
    if (e.fromKey) qpsOut[e.fromKey] += e.weight
  }
  STATES.forEach((s) => {
    qpsIn[s.key] /= 5
    qpsOut[s.key] /= 5
  })
}

const qpsText = (v) => (num(v) > 0 ? `${num(v).toFixed(1)}/s` : '0/s')
const totalQpsIn = computed(() => FLOW_NODES.reduce((a, n) => a + num(qpsIn[n.key]), 0))
const totalQpsOut = computed(() => FLOW_NODES.reduce((a, n) => a + num(qpsOut[n.key]), 0))

const STATE_STEP = { waitPay: 1, waitShip: 2, waitReceive: 3, completed: 4 }
const stateColor = (key) => ({
  waitPay: 'var(--c4)',
  waitShip: 'var(--c1)',
  waitReceive: 'var(--c6)',
  completed: 'var(--c3)',
  cancelled: 'var(--ink-3)',
}[key] || 'var(--ink-3)')

/** 流轨：**只画本会话捕获的真实 moves**。
 *  ⚠️ 不再用「最近订单的当前节点」兜底：那是静态存量，不是「流转」，
 *  在压测没起来时会把一列静止文字伪装成实时流。没有 moves 就交给空态。 */
const streamRows = computed(() => recentMoves.value.slice(0, 10).map((m) => ({
  ...m,
  step: STATE_STEP[stateKeyOf(m.to)] || 0,
  live: true,
})))

/** 账龄条：按本组最大桶归一。 */
function ageWidth(row, bin) {
  const max = Math.max(1, ...(row.bins || []).map((b) => num(b.count)))
  return Math.max(num(bin.count) ? 3 : 0, (num(bin.count) / max) * 100)
}

/** 分钟 → 人读（用于阶梯行里的均耗）。 */
function minuteText(v) {
  return dwellText(num(v) * 60)
}

/* ── 列表 ──────────────────────────────────────────────────────────────────────
   真流：`/orders/detail` 的**服务端分页**（`detailRows` 已经是当前页）；
   演示流：本页内存表前端分页（假流有自己的世界，不能拿真库 COUNT 去分它）。
   ⚠️ 两条路只在这三个 computed 里分叉，模板与 Pager 不需要知道数据源。 */
const listRows = computed(() => orderIds.value.map((id) => ordersById.get(id)).filter(Boolean))
/** 演示流的本地过滤结果（真流不使用 —— 真流的筛选在 SQL 里）。 */
const filteredRows = computed(() => {
  let rows = listRows.value
  if (filterState.value) rows = rows.filter((r) => r.state === filterState.value)
  if (onlyTimeout.value) rows = rows.filter((r) => r.timeout)
  return rows
})
/** 当前页要渲染的行。 */
const pageRows = computed(() => (demo.on
  ? filteredRows.value.slice(offset.value, offset.value + pageSize.value)
  : detailRows.value))
/** 分页脚 / 「全部 N」用的总数：真流是服务端 COUNT，演示流是本地过滤结果。 */
const tableTotal = computed(() => (demo.on ? filteredRows.value.length : detailTotal.value))
/** 「全部」chip 的总量：真流取权威 counts.total（不受当前筛选影响），演示流取本地表。 */
const allCount = computed(() => (demo.on ? listRows.value.length : total.value))
const filterLabel = computed(() => (filterState.value ? (STATE_LABEL[filterState.value] || filterState.value) : '全部状态'))

/**
 * 拉当前筛选下的一页明细（真流）。展示层**不做任何本地过滤** ——
 * `status` / `timeout` 必须到 SQL 的 WHERE 里，否则又会回到「Tab 2271、表 0 条」。
 * 失败时保留上一页（`detailError` 显示在表头小字），不把表格清成空。
 */
async function loadDetail() {
  if (demo.on || replaySession.active || replaySession.loading) return
  const my = ++detailSeq
  detailLoading.value = true
  detailError.value = ''
  try {
    const data = await orderDetail({
      healthy: filterHealthy.value,
      status: filterState.value,
      timeout: onlyTimeout.value,
      offset: offset.value,
      limit: pageSize.value,
    })
    if (my !== detailSeq) return
    const total = num(data.total)
    detailTotal.value = total
    const rows = Array.isArray(data.rows) ? data.rows.map(toRow) : []
    detailRows.value = rows
    // 抽屉 / 时间线从 Map 里取单，页行也记进去。
    rememberRows(rows)
    // 删数据或翻过头：把页码收回到最后一页（触发 watch 再拉一次，避免停在空页）。
    if (!rows.length && total > 0 && offset.value > 0) {
      offset.value = Math.max(0, (Math.ceil(total / pageSize.value) - 1) * pageSize.value)
    }
  } catch (e) {
    if (my !== detailSeq) return
    detailError.value = (e && e.message) || '明细加载失败'
  } finally {
    if (my === detailSeq) detailLoading.value = false
  }
}

/** 筛选 / 翻页 / 数据源变化后**合并**成一次请求（连点筛选不会打一串并发）。 */
function scheduleDetail(delay = 80) {
  if (detailDebounce) clearTimeout(detailDebounce)
  detailDebounce = setTimeout(() => {
    detailDebounce = 0
    loadDetail()
  }, delay)
}
const openedRow = computed(() => (openedId.value ? ordersById.get(openedId.value) || null : null))
const openedMoves = computed(() => (openedId.value ? movesById.get(openedId.value) || [] : []))
/** 主链四步（时间线骨架）。**每一步都在**，不再只画「有时间的行」——
 *  只画有时间的行时，一条待收货单可能只剩「创建订单（待付款）」一行，
 *  看起来就像把初始状态硬编码给了所有订单。现在走过的亮、没到的灰，当前态带脉冲。 */
const CHAIN_STEPS = [
  { key: 'waitPay', text: '创建订单（待付款）', field: 'createdAt' },
  { key: 'waitShip', text: '付款 → 待发货', field: 'paidAt' },
  { key: 'waitReceive', text: '发货 → 待收货', field: 'shippedAt' },
  { key: 'completed', text: '收货 → 已完成', field: 'receivedAt' },
]
const CHAIN_INDEX = { waitPay: 0, waitShip: 1, waitReceive: 2, completed: 3 }

/**
 * 当前选中单的时间线。判据链：
 *   ① 状态来自订单行（`state` / `cancelled`），**不是**拿第一条时间戳当状态；
 *   ② 时间优先取生命周期列，列缺失时取本会话 SSE moves 里第一次到该态的 ts；
 *   ③ 没走过的步骤保留为 `future`（灰色），而不是从列表里删掉；
 *   ④ 取消单只画「已走过的段 + 取消」，不把取消后的主链画成还会继续。
 */
const openedTimeline = computed(() => {
  const r = openedRow.value
  if (!r) return []
  const moveTimes = {}
  for (const m of openedMoves.value) {
    const k = stateKeyOf(m.to)
    if (k && moveTimes[k] === undefined) moveTimes[k] = m.ts
  }
  const state = r.cancelled || r.state === 'cancelled' ? 'cancelled' : r.state
  if (state === 'cancelled') {
    const steps = [CHAIN_STEPS[0]]
    if (r.paidAt || moveTimes.waitShip) steps.push(CHAIN_STEPS[1])
    steps.push({ key: 'cancelled', text: '取消 → 已取消', field: 'cancelledAt' })
    return steps.map((s, i) => ({
      key: s.key,
      text: s.text,
      t: r[s.field] || moveTimes[s.key],
      done: true,
      current: i === steps.length - 1,
      cancel: s.key === 'cancelled',
    }))
  }
  const cur = CHAIN_INDEX[state]
  if (cur === undefined) return []
  return CHAIN_STEPS.map((s, i) => ({
    key: s.key,
    text: s.text,
    t: r[s.field] || moveTimes[s.key],
    done: i <= cur,
    current: i === cur,
    cancel: false,
  }))
})

/** 抽屉候选池：当前筛选下的全部订单，按停留倒序（DLC：先找出卡得最久的）。 */
const drawerPool = computed(() => {
  let rows = listRows.value
  if (filterState.value) rows = rows.filter((r) => r.state === filterState.value)
  return rows.slice().sort((a, b) => num(b.dwellSeconds) - num(a.dwellSeconds))
})

/** 模糊搜索必须打在**全部筛选结果**上，而不是先截前 30 再搜 ——
 *  否则搜一条排名 200 的单会得到「没有匹配」，抽屉像是坏的。
 *  订单号或商品名任一命中即可（大小写不敏感）。 */
const drawerMatchedRows = computed(() => {
  const q = drawerQuery.value.trim().toLowerCase()
  if (!q) return drawerPool.value
  return drawerPool.value.filter((r) => (
    String(r.orderId || '').toLowerCase().includes(q)
    || String(r.product || '').toLowerCase().includes(q)
  ))
})

/** 左列实际渲染：不搜索时取最慢 30；搜索时取命中里最慢 50（有界 + 提示条数）。
 *  ⚠️ 从表格行直接打开时，选中的单可能不在截断范围内 ⇒ 若它也命中当前搜索就补进末尾，
 *  否则左列没有 `on` 高亮、右列却显示着它，像点错了行。 */
const drawerRows = computed(() => {
  const matched = drawerMatchedRows.value
  const limit = drawerQuery.value.trim() ? 50 : 30
  const rows = matched.slice(0, limit)
  const selected = openedId.value ? ordersById.get(openedId.value) : null
  if (selected && matched.some((r) => r.orderId === selected.orderId)
    && !rows.some((r) => r.orderId === selected.orderId)) {
    rows.push(selected)
  }
  return rows
})

/** 搜索把列表缩短后，选中的单可能落到可视区外 ⇒ 重新滚到它（找不到就保持原位）。 */
watch(drawerQuery, revealDrawerSelection)

/** 抽屉内切换选中单（只换右列，不关抽屉、不改筛选）。 */
function pickRow(id) {
  const k = String(id)
  if (ordersById.has(k)) openedId.value = k
}

function onPage({ limit, offset: off }) {
  pageSize.value = limit
  offset.value = off
}
function openTimeline(id) {
  if (ordersById.has(id)) {
    openedId.value = id
    drawerOpen.value = true
    revealDrawerSelection()
  }
}
function closeTimeline() {
  openedId.value = ''
}

/**
 * 收一拍的 `orders`：**只更新已存在的行、不重排**，新出现的 id 插到最前。
 * ⚠️ 「不重排」是刻意的：后端每拍给的是「最近 20 张」，若照它的顺序重排，
 *    用户正在看的那一行会在两拍之间跳走 —— 列表的稳定性比「严格按时间倒序」值钱。
 * ⚠️ 「刚跳这」的判据 = **本行状态与上一次记的不一样**（不是「出现在 `moves` 里」）：
 *    `moves` 是事件环里的流转，与这一行的历史状态不一定对得上（重启/换进程后环是空的）。
 */
function absorbOrders(rows) {
  const fresh = []
  for (const raw of rows) {
    const id = raw.orderId
    if (id === null || id === undefined) continue
    const idStr = String(id)
    const prev = ordersById.get(idStr)
    ordersById.set(idStr, toRow(raw))
    if (!prev) {
      fresh.push(idStr)
    } else if (prev.state !== raw.state) {
      flash(jumpIds, idStr)
    }
  }
  if (!fresh.length) return

  // 新单插到最前（数组顺序按「新出现的在前」），并淘汰超出 CAP 的尾部。
  const next = fresh.concat(orderIds.value.filter((id) => !fresh.includes(id)))
  const kept = next.slice(0, LIST_CAP)
  const dropped = next.slice(LIST_CAP)
  for (const id of dropped) {
    ordersById.delete(id)
    movesById.delete(id)
  }
  orderIds.value = kept
}

/**
 * 收一拍的 `moves`：① 学批次号 ② 累积进时间线 ③ 点亮状态机图上对应的边与节点。
 *
 * ⚠️ **三条判据的口径各不相同，别合并**：
 *   · **批次号**取「最近见到的那个非空值」，**不取第一帧的** —— 本页可能是挂在上一轮演练的
 *     尾巴上连进来的，那第一帧带的是**上一批**的号；取最新的那条能自愈。
 *     换批次时框下小字**清零**，「本批次」才名副其实。
 *   · **节点脉冲**的判据是「`to` 认得出来」—— **建档（`from` 为空）也脉冲**。
 *     这正是 commit 7 那一版漏掉的一半：演练产生的流转 **100% 是建档**
 *     （`action=create`、`from=null`），老守卫 `if (m.from && m.to)` 把它们全挡在门外
 *     ⇒ 一次脉冲都不会发（实测见 `块7-commit10-阶段一核实.txt` §3b「原因一」）。
 *   · **边高亮**要求两端都认得出来、**且这条边本页画了**（`KNOWN_EDGES`）——
 *     后端状态机里有些流转本页没画边（如 `(1,0,1)` 未发货即收货：`CREATED → COMPLETED`
 *     一步跨过去），那种只脉冲落点、不亮边。
 */
function absorbMoves(moves) {
  if (!moves.length) return
  // 累计条数按**全量**记（背压只压渲染，不压账）。
  capturedTotal.value += moves.length
  // 超过上限时均匀抽样：保留首尾与中段，流轨不会只剩某一批。
  const step = Math.max(1, Math.ceil(moves.length / MAX_MOVE_RENDER_PER_FRAME))
  const now = Date.now()
  for (let i = 0; i < moves.length; i += 1) {
    const m = moves[i]
    // ① 批次号：先学（这一步与「这一单认不认得出来」无关，故放在最前面）。
    const batch = m.batchId === null || m.batchId === undefined ? '' : String(m.batchId)
    if (batch && batch !== currentBatch.value) {
      currentBatch.value = batch
      // 换批次 ⇒ 框下小字清零。⚠️ 不清就是把两轮演练的次数加在一起，那已经不是「本批次」。
      STATES.forEach((s) => { batchNodeHits[s.key] = 0 })
    }

    const id = m.orderId === null || m.orderId === undefined ? '' : String(m.orderId)
    const toKey = stateKeyOf(m.to)
    const fromKey = stateKeyOf(m.from)
    // 2026-10-17：超时取消的**汇总事件**（`count=N`、无订单号）。一条代表 N 单，
    // QPS / 动画都按 N 加权；它不是「哪一单」，故不进 `movesById`。
    const summary = num(m.count) > 0
    const weight = summary ? num(m.count) : 1
    const ts = num(m.ts) || now

    // ② 计数口径按全量走（批量取消也能触发支线动画）。
    if (toKey && batch && batch === currentBatch.value) batchNodeHits[toKey] += weight
    if (toKey === 'cancelled') cancelMovesInFrame += weight

    // ③ QPS 原料：**全量**（不经过下面的渲染抽样），汇总事件按 count 加权。
    qpsEvents.push({ ts, fromKey, toKey, weight })
    if (qpsEvents.length > QPS_EVENT_CAP) qpsEvents.splice(0, qpsEvents.length - QPS_EVENT_CAP)

    // ④ 以下才是 DOM / 动画：抽样执行，防止数千条 move 同帧写爆主线程。
    //    汇总事件**永远渲染**（它本身就是一条，抽掉就看不见「批量取消」了）。
    if (!summary && (!id || (i % step !== 0 && i !== moves.length - 1))) continue
    recentMoves.value = [{
      orderId: id,
      batchCount: summary ? weight : 0,
      from: m.from,
      to: m.to,
      ts,
      weight,
    }, ...recentMoves.value].slice(0, RECENT_MOVE_CAP)

    if (id) {
      const arr = movesById.get(id) || []
      arr.push({ from: m.from, to: m.to, ts })
      // 时间线不该无限长（同一张单被反复推同一拍）：同 (from,to,ts) 只留一条。
      const dedup = arr.filter((x, idx) => idx === arr.findIndex((y) => y.from === x.from && y.to === x.to && y.ts === x.ts))
      movesById.set(id, dedup)
    }

    if (!toKey) continue
    flashThrottled(pulseNodes, toKey)
    if (fromKey) {
      const edgeId = `${fromKey}>${toKey}`
      if (KNOWN_EDGES.has(edgeId)) flashThrottled(activeEdges, edgeId)
      // 取消 = 支线（向下）；主链 = 正向粒子。两者各自限流。
      if (toKey === 'cancelled') {
        // 汇总事件 ⇒ 大号瀑布（一次 3–10 颗，按单数加密度）；逐单 ⇒ 一颗普通粒子。
        if (summary) spawnCancelWaterfall(fromKey, weight)
        else spawnCancelParticle(fromKey)
      } else if (id) {
        spawnLaneParticle(fromKey, toKey, id)
      }
    }
  }
}

/**
 * 取消支线的一颗「下落」粒子：从待付款 / 待发货节点沿曲线落向已取消。
 *
 * @param {string} fromKey waitPay | waitShip
 * @param {boolean} [big] 汇总事件用的大号粒子：**不占**逐帧预算（否则瀑布会被
 *        抽样预算掐成一颗），尺寸与拖尾更强；数量由 {@link spawnCancelWaterfall} 按单数定。
 * @param {number} [delay] 依次落下的错峰延时（毫秒）
 */
function spawnCancelParticle(fromKey, big = false, delay = 0) {
  const origin = fromKey === 'waitShip' ? 'waitShip' : 'waitPay'
  if (!big) {
    if (frameParticleBudget <= 0) return
    frameParticleBudget -= 1
  }
  const p = { id: `c${++cancelParticleSeq}`, from: origin, big, delay }
  cancelParticles.value = cancelParticles.value.concat(p).slice(-CANCEL_PARTICLES_CAP)
  const t = setTimeout(() => {
    timers.delete(t)
    cancelParticles.value = cancelParticles.value.filter((x) => x.id !== p.id)
  }, 1600 + delay)
  timers.add(t)
}

/**
 * 超时取消的**汇总事件**（`count=N`）→ 一场大号瀑布。
 *
 * <p>为什么必须是「瀑布」而不是一颗光点：这一条代表几百上千单，画一颗等于把
 * 「批次取消」伪装成「一单取消」—— 用户要的是「肉眼能看出这里发生了一大坨」。
 * 数量随单数增长但有上限（3–10 颗），既保留量级感，又不会把 DOM 打爆。
 */
function spawnCancelWaterfall(fromKey, count) {
  const n = Math.min(10, Math.max(3, Math.ceil(num(count) / 300)))
  for (let i = 0; i < n; i += 1) spawnCancelParticle(fromKey, true, i * 90)
}

/**
 * counts.cancelled 的跳变 → 补画取消支线（扣掉本帧已由 moves 画过的那些，避免双份）。
 * ⚠️ `cancelMovesInFrame` 记的是**单数**（汇总事件按 count 加权），不是事件条数 ——
 *    否则一批 1000 单会先被汇总事件画一次瀑布、再被这里的差额补画一次。
 */
function noteCancelDelta(prev, next) {
  if (!prev) return
  const d = num(next.cancelled) - num(prev.cancelled) - cancelMovesInFrame
  if (d <= 0) return
  const n = Math.min(d, 3)
  for (let i = 0; i < n; i += 1) spawnCancelParticle(i % 2 ? 'waitPay' : 'waitShip')
}

/* ── 回放：逐步重建状态机（2026-10-17 全链路联动）────────────────────────────────
   数据源只有 `replaySession.stateChanges`（播放器从台账里摘出的 `kind=order` 事件，
   含原 `from` / `to` 枚举名）。**没有**任何一条从这里读实时 `counts` —— 回放期间
   SSE 已切断，实时口径在这几秒里本来就不存在，拿它会对不上时钟。 */
function resetReplayState() {
  STATES.forEach((s) => {
    replayCounts[s.key] = 0
    batchNodeHits[s.key] = 0
  })
  replayOrderState.clear()
  replayRecentMoves.value = []
  replayMoveCount.value = 0
  replayApplied = 0
  replaySamples.value = []
  replaySampleAt = 0
  replayLife.clear()
  replayDurations.pay = []
  replayDurations.ship = []
  replayDurations.receive = []
  currentBatch.value = ''
  counts.value = {}
  batchCounts.value = null
  activeEdges.value = new Set()
  pulseNodes.value = new Set()
  jumpIds.value = new Set()
  openedId.value = ''
  recentMoves.value = []
  statesForQpsReset()
}

/** QPS 归零（回放/切数据源时）。 */
function statesForQpsReset() {
  qpsEvents = []
  STATES.forEach((s) => {
    qpsIn[s.key] = 0
    qpsOut[s.key] = 0
  })
}

/**
 * 应用**一条**回放流转到状态机。
 *
 * 角标口径（会加会减）：
 *   · 首次见到这张单（台账里没有它的建单记录）⇒ 直接把它记在 `to`，**不**去减 `from`：
 *     那张单在本簿里根本没被 `+1` 过，减了就是偷别的单的数。
 *   · 之后按重建出的当前位置迁移：`tracked −1 / to +1`（下限 0，脏台账不画负数）。
 *   · 台账若重复推同一条流转（`tracked === to`）⇒ 不再动角标，避免一张单被算两次；
 *     但「经过次数」和明细仍照收（那是台账里真实存在的行）。
 * 口径 `batchNodeHits`（只增）：每一条落点都 +1，与角标是两本账。
 */
function applyReplayChange(m, animate) {
  const toKey = stateKeyOf(m.to)
  // 认不出的节点：不猜、不动角标（明细里仍原样显示枚举名，见 `moveStateLabel`）。
  if (!toKey) return
  const fromKey = stateKeyOf(m.from)
  // 2026-10-17 回放复核：超时取消的**汇总事件**（`count=N`、无订单号）必须按 N 加权。
  // 台账里它是唯一能证明「这一轮真取消了几十/几百单」的记录；按 1 条算会把一场批量
  // 取消缩成一颗小光点，已取消数字也只 +1。
  const count = num(m.count)
  const summary = count > 0
  const weight = summary ? count : 1

  if (summary) {
    // 汇总事件没有单号 ⇒ 不能走 `replayOrderState`（那是「哪一单在哪」）。
    // 按 `from` 扣、按 `to` 加；台账被截断时 `from` 存量可能不足，夹到 0。
    if (fromKey) replayCounts[fromKey] = Math.max(0, replayCounts[fromKey] - weight)
    replayCounts[toKey] += weight
  } else {
    const tracked = replayOrderState.get(m.orderId)
    if (tracked === undefined) {
      replayCounts[toKey] += 1
    } else if (tracked !== toKey) {
      replayCounts[tracked] = Math.max(0, replayCounts[tracked] - 1)
      replayCounts[toKey] += 1
    }
    replayOrderState.set(m.orderId, toKey)
    // 耗时样本只从**同一张单**的相邻时间戳推：汇总事件不是一张单，不能参与。
    noteReplayLifecycle(m, toKey)
  }

  batchNodeHits[toKey] += weight
  replayMoveCount.value += 1
  replayRecentMoves.value = [{
    seq: m.seq,
    ts: m.ts,
    orderId: m.orderId,
    batchCount: summary ? weight : 0,
    from: m.from,
    to: m.to,
    result: m.result,
  }, ...replayRecentMoves.value].slice(0, REPLAY_RECENT_CAP)

  if (!animate) return
  flash(pulseNodes, toKey, REPLAY_FLASH_MS, true)
  if (fromKey) {
    const edgeId = `${fromKey}>${toKey}`
    if (KNOWN_EDGES.has(edgeId)) flash(activeEdges, edgeId, REPLAY_FLASH_MS, true)
    if (toKey === 'cancelled') {
      // 汇总 ⇒ 大号瀑布（与实时页同一个函数、同一套粒子上限）；逐单 ⇒ 一颗。
      if (summary) spawnCancelWaterfall(fromKey, weight)
      else spawnCancelParticle(fromKey)
    } else if (m.orderId) {
      spawnLaneParticle(fromKey, toKey, m.orderId)
    }
  }
}

/**
 * 把 `replaySession.stateChanges` 里**新到的**部分消费掉。
 * `animate=false` 用于「回放中途才挂载本页」：把已经播过的流转一次性补建到当前时刻，
 * 但不补闪 —— 那些高亮发生在过去，补闪等于把历史说成「刚刚」。
 */
function syncReplayChanges(animate) {
  const list = replaySession.stateChanges
  // 会话被重置（新回放 / 退出）⇒ 从头重建。正常情况下 `stateChanges` 只增不减。
  if (replayApplied > list.length) {
    resetReplayState()
  }
  for (let i = replayApplied; i < list.length; i += 1) {
    applyReplayChange(list[i], animate)
    // 批量补建（拖拽定位 / 中途挂载）：按每条流转的原时间戳补趋势样本，
    // 否则 `clockTs` 已经是目标时刻，整段历史只会留下一个点。
    if (!animate) noteReplaySample(replaySamples.value.length === 0, list[i].ts)
  }
  replayApplied = list.length
  // 趋势采样跟着回放时钟走（每 2 秒日志时间一个点）；首帧强制落一个起点。
  noteReplaySample(replaySamples.value.length === 0)
  counts.value = { ...replayCounts, total: replayOrderTotal.value }
}

/** 进入回放视图：切断实时流、重置本页口径、补建当前时刻的状态。 */
function enterReplay() {
  closeStream()
  resetReplayState()
  resetMotion()
  currentBatch.value = replaySession.batchId || ''
  syncReplayChanges(false)
}

/* ── 数字滚动动画（0.3 秒）───────────────────────────────────────────────────
   ⚠️ 与 `MetricsPanel.vue` 同款：`KpiCard` 的 `value` 是**格式化好的字符串**，
   CSS transition 认不出 `1,234` → `1,256` 中间的帧 ⇒ 用 rAF 自己插值。
   `prefers-reduced-motion` 时直接落位。 */
const reduceMotion = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
  : false

function tweenTo(next) {
  const keys = Object.keys(next)
  if (reduceMotion) {
    keys.forEach((k) => { shown[k] = next[k] })
    return
  }
  const from = {}
  keys.forEach((k) => { from[k] = shown[k] })
  const t0 = performance.now()
  if (rafId) cancelAnimationFrame(rafId)
  const step = (now) => {
    const p = Math.min(1, (now - t0) / TWEEN_MS)
    const e = 1 - Math.pow(1 - p, 3)
    keys.forEach((k) => { shown[k] = from[k] + (next[k] - from[k]) * e })
    if (p < 1) {
      rafId = requestAnimationFrame(step)
    } else {
      rafId = 0
      keys.forEach((k) => { shown[k] = next[k] })
    }
  }
  rafId = requestAnimationFrame(step)
}

/* ── 动效的三条生产线 ─────────────────────────────────────────────────────── */
/** 两拍 counts 的差 → 阶梯数字旁的 `+N / -N`，并让数字弹一下。首帧没有上一拍，不播。 */
function noteCountDeltas(prev, next) {
  const hasPrev = STATES.some((s) => prev && prev[s.key] !== undefined)
  if (!hasPrev) return
  STATES.forEach((s) => {
    const d = num(next[s.key]) - num(prev[s.key])
    if (!d) return
    const token = ++deltaSeq[s.key]
    stateDeltas[s.key] = { text: `${d > 0 ? '+' : ''}${d}`, dir: d > 0 ? 'up' : 'down' }
    statePulse[s.key] = true
    const t = setTimeout(() => {
      timers.delete(t)
      if (deltaSeq[s.key] !== token) return
      stateDeltas[s.key] = null
      statePulse[s.key] = false
    }, 1200)
    timers.add(t)
  })
}

/** 追加一个实时尾点。滑动窗口只在**右端**生长，旧点从左边退出。 */
function sampleLiveTail() {
  const now = Date.now()
  if (now - liveTailAt < LIVE_SAMPLE_MS) return
  liveTailAt = now
  const d = new Date(now)
  liveTail.value = liveTail.value.concat({
    label: `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`,
    waitPay: num(counts.value.waitPay),
    waitShip: num(counts.value.waitShip),
    waitReceive: num(counts.value.waitReceive),
  }).slice(-LIVE_TAIL_CAP)
}

/** 一条 SSE move → 主链轨道上的一颗发光粒子。端点认不出来 / 不在主链上就不画。 */
function spawnLaneParticle(fromKey, toKey, orderId) {
  const a = LANE_INDEX[fromKey]
  const b = LANE_INDEX[toKey]
  if (a === undefined || b === undefined || b <= a) return
  // 背压：每帧最多 8 颗粒子（同帧几百条 move 时只画前 8 条，其余计数照收）。
  if (frameParticleBudget <= 0) return
  frameParticleBudget -= 1
  const p = {
    id: `${orderId}-${++particleSeq}`,
    x1: LANE_POS[a],
    w: LANE_POS[b] - LANE_POS[a],
    color: stateColor(toKey),
  }
  laneParticles.value = laneParticles.value.concat(p).slice(-10)
  const t = setTimeout(() => {
    timers.delete(t)
    laneParticles.value = laneParticles.value.filter((x) => x.id !== p.id)
  }, 1400)
  timers.add(t)
}

/**
 * 切数据源 / 重连时把三条动画线清零，避免上一场的粒子和尾点带进下一场。
 *
 * @param {boolean} keepTail true = **保留实时尾点**（重连用）。断线自动重连时
 *        趋势图的滑动窗口不该整段消失 —— 那正是用户报的「切页/重连图表被清零」；
 *        只有真的换数据源（`resetStreamHistory`）才把尾点也清掉。
 */
function resetMotion(keepTail = false) {
  laneParticles.value = []
  cancelParticles.value = []
  if (!keepTail) {
    liveTail.value = []
    liveTailAt = 0
  }
  STATES.forEach((s) => {
    stateDeltas[s.key] = null
    statePulse[s.key] = false
  })
}

/** 切数据源时必须清掉**上一场的事件账**（流轨 / QPS / 累加计数 / 实时尾点）。
 *  否则「模拟日常 → 后端真流」后，假数据流转会继续挂在流轨里，直到被新事件挤出窗口；
 *  真流若一直没 move，就会永远显示上一场的假数据。
 *  ⚠️ 与「刷新恢复」是两条相反的路：这里清空是因为**账本换了**，不是页面重开。 */
function resetStreamHistory() {
  recentMoves.value = []
  movesById.clear()
  capturedTotal.value = 0
  liveTail.value = []
  liveTailAt = 0
  counts.value = {}
  seq.value = 0
  ts.value = 0
  currentBatch.value = ''
  batchCounts.value = null
  statesForQpsReset()
}

/* ── 会话快照（刷新恢复）────────────────────────────────────────────────────── */
let saveAt = 0

/** 落盘用的最小快照。⚠️ 订单行刻意不存：明细已是服务端分页，刷新后 80ms 内重新拉到；
 *  为它撑爆 sessionStorage 配额反而会丢掉趋势/计数这些不可再生的。 */
function snapshot() {
  const moves = []
  for (const [id, arr] of movesById) {
    moves.push([id, arr.slice(-6)])
    if (moves.length >= 400) break
  }
  return {
    v: 1,
    capturedTotal: capturedTotal.value,
    seq: seq.value,
    ts: ts.value,
    counts: counts.value,
    batchCounts: batchCounts.value,
    currentBatch: currentBatch.value,
    recentMoves: recentMoves.value.slice(0, RECENT_MOVE_CAP),
    liveTail: liveTail.value,
    movesById: moves,
    filterHealthy: filterHealthy.value,
    insight: insight.value,
    insightAt: insightAt.value,
  }
}

/** 会话快照落盘。默认节流 2 秒（1 秒 1 帧，每帧都写会把主线程耗在 JSON 上）；
 *  `force = true` 用于 `pagehide` / KeepAlive 失活 —— 那些时刻没有「下一帧」来补写。 */
function saveSession(force = false) {
  const now = Date.now()
  if (!force && now - saveAt < 2000) return
  saveAt = now
  try {
    sessionStorage.setItem(sessionKey(), JSON.stringify(snapshot()))
  } catch (e) {
    // 隐私模式 / 配额满：持久化是增强项，不该让页面本身挂掉。
    return
  }
}

/**
 * 读回上一次会话。返回是否恢复成功。
 * ⚠️ **必须在 `watch` 注册之前调用**（见文件底部 `restoreSession()` 的位置）：
 *     否则恢复 `filterHealthy` 会触发一次多余的重新拉取。
 */
function restoreSession() {
  let p = null
  try {
    p = JSON.parse(sessionStorage.getItem(sessionKey()) || 'null')
  } catch (e) {
    p = null
  }
  if (!p || p.v !== 1) return false
  capturedTotal.value = num(p.capturedTotal)
  seq.value = num(p.seq)
  ts.value = num(p.ts)
  counts.value = p.counts && typeof p.counts === 'object' ? p.counts : {}
  batchCounts.value = p.batchCounts || null
  currentBatch.value = p.currentBatch || ''
  recentMoves.value = Array.isArray(p.recentMoves) ? p.recentMoves.slice(0, RECENT_MOVE_CAP) : []
  liveTail.value = Array.isArray(p.liveTail) ? p.liveTail.slice(-LIVE_TAIL_CAP) : []
  liveTailAt = Date.now()   // 下一拍不用立刻再补一个同刻的点
  movesById.clear()
  if (Array.isArray(p.movesById)) {
    for (const pair of p.movesById) {
      if (Array.isArray(pair) && pair[0] && Array.isArray(pair[1])) {
        movesById.set(String(pair[0]), pair[1])
      }
    }
  }
  // 缺省 false = 全量。只有快照里明确 `true` 才开启收窄。
  filterHealthy.value = p.filterHealthy === true
  insight.value = p.insight || null
  insightAt.value = num(p.insightAt)
  // 订单表不再从快照恢复（v1 快照本来也没存它）：挂载后 80ms 内由 `/orders/detail`
  // 拉回当前筛选页，SSE 的 `orders` 也会继续补最近 20 行。
  // 数字直接落位：刷新不是「从 0 长起来」，补间会让恢复后的第一眼反而像在造假。
  STATES.forEach((s) => {
    target[s.key] = num(counts.value[s.key])
    shown[s.key] = target[s.key]
  })
  computeQps()
  return true
}

/* ── 收帧 ──────────────────────────────────────────────────────────────────── */
function onFrame(f) {
  const prevCounts = counts.value && Object.keys(counts.value).length ? counts.value : null
  seq.value = num(f.seq)
  ts.value = num(f.ts) || Date.now()
  counts.value = f.counts || {}
  // 本批次口径：后端没有批次号时回 `null`（不是回一份全 0）⇒ 原样透传，由模板决定画不画。
  batchCounts.value = f.batchCounts || null

  // 每帧重置粒子预算 + 取消计数，背压对「这一帧」生效，不跨帧累积。
  frameParticleBudget = MAX_PARTICLES_PER_FRAME
  cancelMovesInFrame = 0
  absorbOrders(Array.isArray(f.orders) ? f.orders : [])
  absorbMoves(Array.isArray(f.moves) ? f.moves : [])
  noteCancelDelta(prevCounts, counts.value)
  computeQps()
  noteCountDeltas(prevCounts, counts.value)
  sampleLiveTail()

  STATES.forEach((s) => { target[s.key] = num(counts.value[s.key]) })
  tweenTo({ ...target })
  saveSession()
}

function connect() {
  // 回放（含装载中）期间不连实时流：播放器控制三页的时钟，旁边再跑一条 SSE 就是两种时间并存。
  // ⚠️ `loading` 也要拦：新回放启动时 `resetSession()` 会先把 `active` 置 false，
  //    若只判 `active`，这一瞬间会先连上实时流、回放装载完再切断（白闪一帧实时数据）。
  if (replaySession.active || replaySession.loading) return
  closeStream()
  // 重连保留实时尾点：趋势右端不该因为一次断线而清空（换数据源另有 `resetStreamHistory`）。
  resetMotion(true)
  errMsg.value = ''
  lost.value = null
  phase.value = 'connecting'
  // ⚠️ `openOrderStream` 从 commit 10c 起是**带自动重连**的那一层（`onEnd` → `onLost`）：
  //    `onLost` **每次**断开都报，含「服务端正常收尾」那一格 —— 这是本笔要治的那一格。
  // ⚠️ 演示模式（块 7 收尾 ②）：**只换数据源这一处**，三个回调两边共用。
  //    ⚠️ 演示流回的 `batchId` 一律 `null` ⇒ 本页那条「本批次单数」小字**不出现**
  //    （演示不是演练 —— 理由写在 `api/demo-stream.js` 文件头），别当成漏字段。
  const handlers = {
    onOpen() {
      phase.value = 'live'
      lost.value = null
      errMsg.value = ''
    },
    onFrame,
    onLost(s) {
      phase.value = 'stopped'
      lost.value = s
      // ⚠️ `errMsg` 只装**真错误**：它同时是「还没收到第一拍」那个加载闸的判据（见 `first`）。
      errMsg.value = s.err ? ((s.err && s.err.message) || '连接中断') : ''
    },
  }
  stream = demo.on
    ? openDemoStream('orders', handlers)
    : openOrderStream(handlers, filterHealthy.value)
}

function closeStream() {
  if (stream) {
    stream.close()
    stream = null
  }
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
  timers.forEach((t) => clearTimeout(t))
  timers.clear()
  // 节流表也清掉：重连后第一笔该立刻亮，不该被上一次连接的残留时刻压住（`flashThrottled` 的判据）。
  lastFlashAt.clear()
}

/** `pagehide` 是刷新/关标签页前**最后**一个可靠时机（`unload` 在移动端常不触发）。 */
function onPageHide() {
  saveSession(true)
}

// 刷新恢复必须早于任何 `watch` 注册（理由见 `restoreSession` 的注释）。
restoreSession()

onMounted(() => {
  // 分析面先铺满（趋势 / 直方图 / 账龄 / Top N / 表格种子行），再连 SSE。
  // ⚠️ 恢复过快照也照拉一次：快照里的趋势可能是几分钟前的，低频刷新本就该追平。
  loadInsight()
  insightTimer = setInterval(loadInsight, 15000)
  // 明细是服务端分页：首屏拉一次，之后每 5 秒追平（分页脚 total 也会跟着变）。
  scheduleDetail(0)
  detailTimer = setInterval(() => {
    if (!demo.on && !replaySession.active && !replaySession.loading) loadDetail()
  }, 5000)
  window.addEventListener('pagehide', onPageHide)
  // 本页可能在回放已经跑起来之后才挂载（KeepAlive 失活再回来 / 从别的页切过来）
  // ⇒ 先按 `stateChanges` 补建到当前时刻，不要再连实时流。
  if (replaySession.active) enterReplay()
  else connect()
})
// ⚠️ 切数据源（`正常` ⇄ `模拟日常`）⇒ 清掉上一场事件账 + 立刻重连（`connect()` 会先 `closeStream()`）。
watch(() => demo.on, () => {
  resetStreamHistory()
  connect()
  // 真流 ⇄ 演示流换的是**账本**：演示走本地表、真流走服务端分页。
  detailRows.value = []
  detailTotal.value = 0
  if (demo.on) {
    detailSeq += 1   // 让在途的真流响应作废，别把真库行塞进演示表
  } else {
    scheduleDetail()
  }
})
/**
 * 打开「剔除已取消 / 仅看真实数据」时，把已经在表里的不合格行摘掉：
 * 演练单（`SIM-` 前缀）与已取消单。判定与后端 `OrderDataScope.HEALTHY_SCOPE_SQL` 对齐
 * （订单行没有 `Source` 字段，但攻击引擎造的订单**同时**带 `SIM-` 前缀，前缀这一半就够）。
 * ⚠️ 只动**订单表**（`orderIds` / `ordersById`），不动 `movesById` / `recentMoves`：
 * 后者是流轨的账，本来就不过滤；删了反而会让「共捕获」与时间线对不上。
 */
function dropUnhealthyRows() {
  const kept = []
  for (const id of orderIds.value) {
    const row = ordersById.get(id)
    if (id.startsWith('SIM-') || (row && row.cancelled)) {
      ordersById.delete(id)
      if (openedId.value === id) closeTimeline()
    } else {
      kept.push(id)
    }
  }
  orderIds.value = kept
}

// 「剔除已取消 / 仅看真实数据」：分析面重拉 + **流重连**（healthy 是连接级参数，
// 见 `openOrderStream`）。关掉开关时不做反向补行：下一拍 SSE / 15 秒 insight 会把全量行带回来。
// 流轨本身仍全量（`moves` 不过滤）—— 藏掉演练单不等于把轨道的动作也停掉。
watch(filterHealthy, () => {
  saveSession(true)
  if (filterHealthy.value) dropUnhealthyRows()
  loadInsight()
  connect()
  scheduleDetail()
})
// 主从筛选一变，页码回到第一页（否则筛完只剩 3 行却停在第 9 页）。
watch([filterState, onlyTimeout], () => {
  offset.value = 0
  scheduleDetail()
})
// 翻页 / 改每页条数：真流重新向服务端要**这一页**（演示流由 pageRows 本地切）。
watch([offset, pageSize], () => scheduleDetail())
// 开始/结束回放：切流 + 重置本页口径。⚠️ 退出后 `connect()` 才恢复实时。
watch(() => replaySession.active, (on) => {
  if (on) enterReplay()
  else {
    resetReplayState()
    connect()
    scheduleDetail()
  }
})
// 装载失败（没进回放、`loading` 落回 false）⇒ 恢复实时流。
watch(() => replaySession.loading, (loading) => {
  if (!loading && !replaySession.active) connect()
})
// 播放器每吐一批流转就响一次；只消费新到的尾部（`replayApplied`）。
// ⚠️ 每批重置一次粒子预算：与实时 `onFrame` 同一条背压规则，回放也不会几千条 move 同帧堆 DOM。
watch(() => replaySession.stateChanges.length, () => {
  if (replaySession.active) {
    frameParticleBudget = MAX_PARTICLES_PER_FRAME
    // 拖拽定位 / 中途挂载：一次补建了几千条历史流转，不能逐条闪粒子/高亮
    // （那是「刚刚发生」的语义）。`rebuilding` 只影响动画，不影响状态重建本身。
    syncReplayChanges(!replaySession.rebuilding)
  }
})
// 「重新开始（重放）」：清掉本页重建的状态机 / 耗时样本 / 粒子，再从第一条重来。
watch(() => replaySession.restartSeq, () => {
  if (replaySession.active) enterReplay()
})
// 拖拽定位（Seek）：会话会把 `stateChanges` 清空后按目标索引重投 ⇒ 本页先归零，
// 等下一拍 `stateChanges.length` 变化时从 0 全量 apply（`replayApplied` 已在
// `enterReplay` 的 `resetReplayState` 里归零）。
watch(() => replaySession.seekSeq, () => {
  if (replaySession.active) enterReplay()
})
// KeepAlive 失活（路由切走）时**不卸载**：落一次盘就够了，内存状态原样留着。
onDeactivated(() => {
  saveSession(true)
})
onBeforeUnmount(() => {
  saveSession(true)
  window.removeEventListener('pagehide', onPageHide)
  closeStream()
  if (insightTimer) {
    clearInterval(insightTimer)
    insightTimer = 0
  }
  if (detailTimer) {
    clearInterval(detailTimer)
    detailTimer = 0
  }
  if (detailDebounce) {
    clearTimeout(detailDebounce)
    detailDebounce = 0
  }
})
</script>

<script>
// KeepAlive 的 `include` 按**组件 name** 匹配；Vue 3.2.13 不会从文件名推断 `__name`
// （那是 3.2.34+ 才有的行为）⇒ 必须显式声明，否则 `include="OrderFlow"` 会静默不生效：
// 路由切走即卸载，计数与捕获数全丢。`MetricsPanel` / `EventLog` 两页同样是这么写的。
export default { name: 'OrderFlow' }
</script>

<style scoped>
.a-of {
  /* 高密度大盘：比其它看板宽一档（1120 → 1320），让趋势 + 阶梯 + 明细表同屏。 */
  max-width: 1320px;
  position: relative;
  /* 雷达监控屏底纹：极淡网格 + 顶部冷光。网格只铺在内容区，不另建浮层。 */
  background-image:
    radial-gradient(circle at 50% 0%, color-mix(in srgb, var(--c1) 7%, transparent), transparent 46%),
    linear-gradient(to right, color-mix(in srgb, var(--line) 55%, transparent) 1px, transparent 1px),
    linear-gradient(to bottom, color-mix(in srgb, var(--line) 55%, transparent) 1px, transparent 1px);
  background-size: 100% 100%, 30px 30px, 30px 30px;
  background-position: 0 0, -1px -1px, -1px -1px;
}
/* 面板：细边界 + 一点内发光，别只是贴一块纯深色。 */
.a-of .a-card {
  position: relative;
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--ink) 6%, transparent),
    inset 0 0 26px color-mix(in srgb, var(--c1) 4%, transparent),
    0 0 0 1px color-mix(in srgb, var(--line) 55%, transparent);
}

/* ── ① 状态条（逐字照 `MetricsPanel.vue`，那里又同源于 `StatsPage.vue`）──────── */
.a-status {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
  margin-bottom: var(--s5);
}
.a-stat {
  display: flex;
  flex-direction: column;
  min-width: 96px;
}
.a-stat b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semi);
  line-height: 1.1;
  color: var(--ink);
}
.a-stat span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-tz {
  margin-left: auto;
  align-self: center;
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-chip-live {
  font-size: var(--fs-xs);
  color: var(--info);
  background: var(--info-ground);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.a-tz-off {
  font-family: var(--font-mono);
  color: var(--brand-ink);
}
/* 图表口径开关：与状态条同一行，不抢主数字的视觉权重。 */
.a-of-filter {
  position: relative;
  align-self: center;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}
/* 原生 checkbox 留作可访问/可点的那一层，视觉换成开关。 */
.a-of-filter input {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}
.a-of-switch {
  position: relative;
  flex: none;
  width: 34px;
  height: 18px;
  border-radius: 9px;
  background: var(--line-2);
  transition: background 160ms ease;
}
.a-of-switch i {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--surface);
  box-shadow: 0 1px 2px rgb(0 0 0 / 25%);
  transition: transform 160ms ease;
}
.a-of-filter input:checked + .a-of-switch {
  background: var(--brand);
}
.a-of-filter input:checked + .a-of-switch i {
  transform: translateX(16px);
}
.a-of-filter input:focus-visible + .a-of-switch {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
/* 回放状态条：用琥珀色边框把它和实时状态条分开（颜色 = 「这不是实时」）。 */
.a-status--rp {
  border-color: var(--c4);
  background: var(--c4-ground, var(--surface-2));
}
.a-status--rp .a-chip-live {
  color: var(--c4-ink, var(--brand-ink));
  background: var(--c4-ground, var(--info-ground));
}
.a-of-rp-note {
  margin: calc(-1 * var(--s2)) 0 var(--s4);
  padding: var(--s2) var(--s3);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-2);
  background: var(--surface-2);
  border-left: 3px solid var(--c4);
  border-radius: var(--r1);
}

/* ── 通用件 ───────────────────────────────────────────────────────────────── */
.a-flash {
  display: flex;
  align-items: center;
  gap: var(--s3);
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}
.a-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  cursor: pointer;
  white-space: nowrap;
}
.a-btn:hover {
  color: var(--ink);
  background: var(--surface-3);
}
.a-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin: var(--s6) 0 var(--s3);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.a-of > .a-h2:first-of-type {
  margin-top: 0;
}
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4);
  margin-bottom: var(--s4);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s4) 0;
}

/* ── 回放流转明细（只读清单）────────────────────────────────────────────────── */
.a-of-rpm {
  list-style: none;
  display: flex;
  flex-direction: column;
  max-height: 280px;
  overflow-y: auto;
}
.a-of-rpm-i {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  padding: var(--s2) 0;
  border-bottom: 1px dashed var(--line);
  font-size: var(--fs-sm);
}
.a-of-rpm-i:last-child {
  border-bottom: 0;
}
.a-of-rpm-t {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
.a-of-rpm-d {
  color: var(--ink-2);
}
.a-of-rpm-d b {
  color: var(--ink);
  font-weight: var(--fw-semi);
}
.a-of-rpm-r {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* ── ② 计数卡 + 环图（左卡右环；窄屏堆叠）──────────────────────────────────── */
.a-of-top {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 400px;
  gap: var(--s3);
  align-items: start;
}
@media (max-width: 900px) {
  .a-of-top {
    grid-template-columns: minmax(0, 1fr);
  }
}
/* ⚠️ 计数卡**固定 5 列**（不写 `auto-fit`）：这五个是一组、永远同时在场，
   交给 `auto-fit` 会在 1120px 容器里排成 4+1 —— 第 5 张「已取消」孤零零占一行，
   读起来像「还有一个别的指标」。窄屏才退回自动折行。 */
.a-of-kpis {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--s3);
}
@media (max-width: 1050px) {
  .a-of-kpis {
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  }
}
/* 计数卡本身是 `KpiCard` 的 div，这里用按钮包一层以获得点击/键盘语义。 */
.a-of-kpi {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  text-align: left;
  border-radius: var(--r3);
  cursor: pointer;
}
.a-of-kpi:hover {
  background: var(--surface-2);
}
.a-of-kpi:focus-visible {
  outline: 2px solid var(--c1);
  outline-offset: 2px;
}
.a-of-kpi.on .kpi {
  border-color: var(--c1);
  box-shadow: inset 0 0 0 1px var(--c1);
}
.a-of-donut {
  margin-bottom: 0;
}

/* ── ③ 实时订单列表 ───────────────────────────────────────────────────────── */
.a-of-list {
  list-style: none;
  display: flex;
  flex-direction: column;
}
.a-of-row {
  display: grid;
  grid-template-columns: 150px 120px 64px minmax(0, 1fr) 120px;
  align-items: center;
  gap: var(--s3);
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  border-radius: var(--r2);
}
.a-of-row:last-child {
  border-bottom: 0;
}
.a-of-row:hover,
.a-of-row:focus-visible {
  background: var(--surface-2);
  outline: none;
}
/* 「刚跳了状态」：底色短时闪一下（1.2 秒后由 JS 摘掉 class）。 */
.a-of-row.is-jump {
  background: var(--success-ground);
  transition: background 0.4s ease;
}
@media (max-width: 860px) {
  .a-of-row {
    grid-template-columns: 130px 100px 60px minmax(0, 1fr);
  }
  .a-of-amt {
    display: none;
  }
}
.a-of-id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-of-goods {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-of-amt {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 4 节点进度条（纯 DOM 画，不走 ECharts）──────────────────────────────────── */
.a-of-track {
  display: flex;
  align-items: center;
}
.a-of-seg {
  flex: 1;
  height: 2px;
  background: var(--line-2);
}
.a-of-seg.on {
  background: var(--success);
}
.a-of-node {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--surface);
  border: 2px solid var(--line-2);
  box-sizing: border-box;
  flex: none;
  transition: background 0.5s ease, border-color 0.5s ease;
}
.a-of-node.on {
  background: var(--success);
  border-color: var(--success);
}
/* 当前所在节点：外圈脉冲，读作「这一单停在这」。 */
.a-of-node.now {
  box-shadow: 0 0 0 0 var(--success-soft, var(--success-ground));
  animation: a-of-node-pulse 1.2s ease-out 2;
}
@keyframes a-of-node-pulse {
  0% { box-shadow: 0 0 0 0 var(--success); }
  100% { box-shadow: 0 0 0 7px transparent; }
}
/* 已取消：整条进度条压暗 —— 0 个节点点亮本身还不够，灰线更像「这条路断了」。 */
.a-of-track.is-dead {
  opacity: 0.45;
}
/* ── 3a「—」流动（《块 7 参考》§3.1 的稿）──────────────────────────────────────────
   ⚠️ 这一段是**纯 DOM 不是 SVG**（4 个 `<i class="a-of-node">` + 3 个 `<i class="a-of-seg">`），
      没有 `stroke-dasharray` / `stroke-dashoffset` 可改 —— 核实时给的那条「SVG 虚线推移」改法
      在本段**无处可施**。等价手法 = 拿虚线渐变当底 + 平移底图（`background-position` 动画），
      观感就是「虚线在往前走」。
   ⚠️ 只给 `.on`（已经走过的那几段）上虚线：没走过的段保持实心灰线，读作「还没到」；
      若整条都流动，就分不出走到哪了。
   ⚠️ 只在 `.is-live` 的行走动画（判据见模板 `a-of-track` 那两行）：
      取消的单已退出流程、step === 4 的单已走完 —— 这两种还在流动是**假信号**。
   ⚠️ 周期必须与 `background-position` 的位移量一致（都是 12px），否则动画会在帧之间跳一下。 */
@keyframes a-of-seg-flow {
  from { background-position: 0 0; }
  to { background-position: -12px 0; }
}
.a-of-track.is-live .a-of-seg.on {
  background-color: transparent;
  background-image: repeating-linear-gradient(
    90deg,
    var(--success) 0 6px,
    transparent 6px 12px
  );
  animation: a-of-seg-flow 0.9s linear infinite;
}

/* ── 状态标签（色点即字色；与环图扇区色同一批变量）────────────────────────────── */
.a-of-st {
  font-size: var(--fs-xs);
  white-space: nowrap;
}
.a-of-st.is-waitPay { color: var(--c4); }
.a-of-st.is-waitShip { color: var(--c1); }
.a-of-st.is-waitReceive { color: var(--c6); }
.a-of-st.is-completed { color: var(--c3); }
.a-of-st.is-cancelled { color: var(--ink-3); }

/* ── ④ 状态机图 ───────────────────────────────────────────────────────────── */
.a-of-svg {
  width: 100%;
  height: auto;
  display: block;
}
.a-of-edge {
  stroke: var(--line-2);
  stroke-width: 1.5;
  fill: none;
  transition: stroke 0.3s ease, stroke-width 0.3s ease;
}
.a-of-edge.on {
  stroke: var(--success);
  stroke-width: 2.5;
}
.a-of-box {
  fill: var(--surface-2);
  stroke: var(--line-2);
  stroke-width: 1.5;
  transition: stroke 0.3s ease;
}
.a-of-box.is-waitPay { fill: var(--surface-2); stroke: var(--c4); }
.a-of-box.is-waitShip { fill: var(--surface-2); stroke: var(--c1); }
.a-of-box.is-waitReceive { fill: var(--surface-2); stroke: var(--c6); }
.a-of-box.is-completed { fill: var(--surface-2); stroke: var(--c3); }
.a-of-box.is-cancelled { fill: var(--surface-2); stroke: var(--ink-3); }
.a-of-box-t {
  font-size: 13px;
  font-weight: var(--fw-med);
}
.a-of-box-t.is-waitPay { fill: var(--c4); }
.a-of-box-t.is-waitShip { fill: var(--c1); }
.a-of-box-t.is-waitReceive { fill: var(--c6); }
.a-of-box-t.is-completed { fill: var(--c3); }
.a-of-box-t.is-cancelled { fill: var(--ink-3); }
/* 框内右上角：**全表**口径的数（库里此刻这个态有多少单）。
   ⚠️ 中性色，**不用**节点自己的语义色 —— 同框里框边 + 标题已经上了两次这个色，
   第三个再上就糊成一片，而它本来就只是「背景信息」，比标题低一档才对。
   ⚠️ 走 tabular-nums：数位变化时不该把宽度撑得一跳一跳。 */
.a-of-box-n {
  font-size: 11px;
  fill: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
/* 框下：**本批次**口径的小字（这次演练把多少条流转推进了这个节点）。
   ⚠️ 与角标同为中性色、同字号，靠**位置**（框内 vs 框下）区分两个口径，不靠颜色 ——
   用颜色区分会让「两个口径」看起来像「两种状态」，那是另一回事。 */
.a-of-box-b {
  font-size: 11px;
  fill: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
/* 节点脉冲：本拍有流转落到它身上时，外圈呼吸一下。 */
.a-of-nodes .is-pulse .a-of-box {
  animation: a-of-node-glow 1.2s ease-out 2;
}
@keyframes a-of-node-glow {
  0% { stroke-width: 1.5; }
  40% { stroke-width: 4; }
  100% { stroke-width: 1.5; }
}
@media (prefers-reduced-motion: reduce) {
  .a-of-node.now,
  .a-of-nodes .is-pulse .a-of-box,
  .a-of-trend::after,
  .a-of-lane-track,
  .a-of-cancel-links path.on,
  .a-of-cancel-particle,
  .a-of-lane-dot::after,
  .a-of-lane-node.is-live .a-of-lane-dot,
  .a-of-dot-sm.now,
  .a-of-seg-sm.on,
  .a-of-step.is-live,
  .a-of-step.is-live .a-of-dot,
  .a-of-step-n.is-bump,
  .a-of-delta,
  .a-of-tl-i.is-current .a-of-tl-dot,
  .a-of-stream-radar::before,
  .a-of-stream-radar::after {
    animation: none;
  }
  /* 3a 的「—」流动也关掉 —— ⚠️ 只关 animation、**不关虚线本身**：
     虚线是「这一段走过了」的静态标识，关掉它等于顺手把状态也擦了。 */
  .a-of-track.is-live .a-of-seg.on {
    animation: none;
  }
  .a-of-node,
  .a-of-edge,
  .a-of-row.is-jump,
  .a-of-rail > i {
    transition: none;
  }
}
.a-of-fsm-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  margin-top: var(--s3);
  padding-top: var(--s3);
  border-top: 1px dashed var(--line);
}

/* ── ⑤ 时间线弹层 ─────────────────────────────────────────────────────────── */
.a-of-modal {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s5);
}
.a-of-modal-box {
  width: min(560px, 100%);
  max-height: 80vh;
  overflow: auto;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
}
.a-of-modal-hd {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line-2);
}
.a-of-close {
  margin-left: auto;
}
.a-of-modal-sub {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  margin: var(--s3) 0;
}
.a-of-tl {
  list-style: none;
  display: flex;
  flex-direction: column;
  margin: var(--s4) 0;
}
/* 时间线不是「只列有时间的行」：四步骨架都在，走过的亮、当前态脉冲、没到的灰。 */
.a-of-tl-i {
  position: relative;
  display: grid;
  grid-template-columns: 12px 76px minmax(0, 1fr);
  gap: 0 var(--s3);
  padding-bottom: var(--s3);
  font-size: var(--fs-sm);
  color: var(--ink);
}
.a-of-tl-i::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 14px;
  bottom: 0;
  width: 1px;
  background: var(--line-2);
}
.a-of-tl-i:last-child {
  padding-bottom: 0;
}
.a-of-tl-i:last-child::before {
  display: none;
}
.a-of-tl-dot {
  position: relative;
  z-index: 1;
  width: 11px;
  height: 11px;
  margin-top: 3px;
  border-radius: 50%;
  border: 1px solid var(--line-2);
  background: var(--surface);
  box-sizing: border-box;
}
.a-of-tl-i.is-done .a-of-tl-dot {
  background: var(--tl-color);
  border-color: var(--tl-color);
  box-shadow: 0 0 7px var(--tl-color);
}
.a-of-tl-i.is-current .a-of-tl-dot {
  animation: a-of-tl-pulse 1.8s ease-out infinite;
}
.a-of-tl-i.is-cancel .a-of-tl-dot {
  background: var(--danger);
  border-color: var(--danger);
  box-shadow: 0 0 7px var(--danger);
}
@keyframes a-of-tl-pulse {
  0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--tl-color) 55%, transparent), 0 0 7px var(--tl-color); }
  100% { box-shadow: 0 0 0 7px transparent, 0 0 3px transparent; }
}
.a-of-tl-t {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  padding-top: 2px;
}
.a-of-tl-d {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  min-width: 0;
}
.a-of-tl-d b {
  font-weight: var(--fw-semi);
}
.a-of-tl-i.is-done:not(.is-current) .a-of-tl-d b {
  color: var(--ink-2);
}
.a-of-tl-i.is-current .a-of-tl-d b {
  color: var(--ink);
}
.a-of-tl-i.is-future {
  opacity: 0.42;
}
.a-of-tl-i.is-future .a-of-tl-d b {
  color: var(--ink-3);
}
.a-of-tl-d small {
  font-size: 10px;
  color: var(--ink-3);
  white-space: nowrap;
}
.a-of-tl-i.is-current .a-of-tl-d small {
  color: var(--success);
}
.a-of-tl-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  padding-top: var(--s3);
  border-top: 1px dashed var(--line);
}

/* ══ 高密度实时大盘（2026-10-17）══════════════════════════════════════════════
   结构：趋势/健康 → 阶梯/流轨 → 直方图 → 账龄预警 → 全局明细。
   ⚠️ 这里刻意不再用「一排等宽卡片」：hero 是 1fr+300px，主区是 1.35fr+1fr，
      直方图 3 列、预警 2 列 —— 相邻区块的列数/轴向都不同（taste 的「相邻不共用布局」）。 */
.a-of-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--s3);
  align-items: stretch;
}
.a-of-hero > .a-card {
  margin-bottom: 0;
}
/* 趋势卡上的一道极淡扫描线：图在动之外，整块也有一点「雷达在扫」的生气。 */
.a-of-trend {
  position: relative;
  overflow: hidden;
}
.a-of-trend::after {
  content: '';
  position: absolute;
  top: 52px;
  bottom: 10px;
  left: -8%;
  width: 1px;
  background: linear-gradient(to bottom, transparent, var(--c1), transparent);
  opacity: 0.3;
  pointer-events: none;
  animation: a-of-scan 7s linear infinite;
}
@keyframes a-of-scan {
  0% { left: -8%; }
  100% { left: 108%; }
}
.a-of-chd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s3);
  margin-bottom: var(--s2);
}
.a-of-ct {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-of-cs {
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-3);
}
.a-of-live {
  flex: none;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--info);
  background: var(--info-ground);
  border-radius: var(--r1);
  padding: 2px 7px;
  white-space: nowrap;
}
.a-of-live.is-anchored {
  color: var(--c4);
  background: var(--surface-2);
  box-shadow: inset 0 0 0 1px var(--c4);
}
.a-of-anchor,
.a-of-insight-err {
  margin: 0 0 var(--s2);
  padding: var(--s2) var(--s3);
  font-size: 11px;
  line-height: 1.5;
  border-radius: var(--r1);
}
.a-of-anchor {
  color: var(--c4);
  background: var(--surface-2);
  border-left: 3px solid var(--c4);
}
.a-of-insight-err {
  color: var(--danger);
  background: var(--danger-ground);
}
.a-of-health-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s2) var(--s3);
  margin: var(--s2) 0 0;
  padding-top: var(--s2);
  border-top: 1px dashed var(--line);
}
.a-of-health-grid div {
  min-width: 0;
}
.a-of-health-grid dt {
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-health-grid dd {
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
  line-height: 1.25;
}
.a-of-health-grid small {
  display: block;
  font-size: 10px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-of-health-grid dd.a-of-mini {
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
}

.a-of-main {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: var(--s3);
  margin-top: var(--s3);
}
.a-of-main > .a-card {
  margin-bottom: 0;
}
/* 右栏是纵向 flex：列表按内容长，剩余高度交给空态 / 待命条，
   不再让「列表写死 286px」在数据少时留下死白。 */
.a-of-stream {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.a-of-flow-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 230px;
  gap: var(--s3);
  align-items: start;
}
.a-of-flow-dist {
  min-width: 0;
  padding-left: var(--s3);
  border-left: 1px dashed var(--line);
}
.a-of-flow-dist .a-of-cs {
  text-align: center;
}
.a-of-ladder {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
/* 阶梯的一级 = 一条**可点按钮**：上面是名字/单量/QPS，中间是粗轨道，下面是口径。
   ⚠️ 轨道底色恒在（没数据的态也画一条空心轨），否则「0 单」会读成「这一级不存在」。 */
.a-of-step {
  display: block;
  width: 100%;
  text-align: left;
  font: inherit;
  color: var(--ink);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
  cursor: pointer;
}
.a-of-step:hover,
.a-of-step:focus-visible {
  background: var(--surface-2);
  outline: none;
}
/* 回放视图里的阶梯只读：不响应 hover、不显示手型（避免误以为可筛选）。 */
.a-of-step.is-static,
.a-of-kpi.is-static {
  cursor: default;
}
.a-of-step.is-static:hover,
.a-of-kpi.is-static:hover {
  background: transparent;
}
.a-of-step.is-on {
  background: var(--surface-2);
  border-color: var(--c1);
}
.a-of-step.is-warn {
  box-shadow: inset 3px 0 0 var(--danger);
}
.a-of-step.is-cancel {
  margin-top: var(--s2);
  border-top: 1px dashed var(--line);
  border-radius: 0 0 var(--r2) var(--r2);
}
.a-of-step-top {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  min-width: 0;
}
.a-of-step-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--ink);
  white-space: nowrap;
}
.a-of-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  display: inline-block;
}
.a-of-step-num {
  position: relative;
  display: inline-flex;
  align-items: baseline;
}
.a-of-step-n {
  font-family: var(--font-mono);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  line-height: 1;
  transform-origin: left center;
}
.a-of-step-n.is-bump {
  animation: a-of-bump 0.55s cubic-bezier(0.34, 1.56, 0.64, 1);
}
/* +N / -N 从数字右侧浮起、淡出 —— 只在**真变了**的那一拍出现。 */
.a-of-delta {
  position: absolute;
  left: 100%;
  top: -2px;
  margin-left: 3px;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: var(--fw-semi);
  white-space: nowrap;
  pointer-events: none;
  animation: a-of-delta-float 1.2s ease-out forwards;
}
.a-of-delta.is-up { color: var(--success); }
.a-of-delta.is-down { color: var(--danger); }
@keyframes a-of-bump {
  0% { transform: scale(1); }
  35% { transform: scale(1.28); }
  100% { transform: scale(1); }
}
@keyframes a-of-delta-float {
  0% { opacity: 0; transform: translateY(4px) scale(0.8); }
  18% { opacity: 1; transform: translateY(0) scale(1); }
  70% { opacity: 1; }
  100% { opacity: 0; transform: translateY(-12px) scale(0.9); }
}
/* 有流转落到这个节点：左侧色条呼吸（雷达波），与状态机图的描边脉冲同一语义。 */
.a-of-step.is-live {
  animation: a-of-step-breathe 1.2s ease-out 2;
}
@keyframes a-of-step-breathe {
  0% { box-shadow: inset 3px 0 0 var(--success), 0 0 0 0 color-mix(in srgb, var(--success) 45%, transparent); }
  60% { box-shadow: inset 3px 0 0 var(--success), 0 0 0 5px transparent; }
  100% { box-shadow: inset 3px 0 0 transparent, 0 0 0 0 transparent; }
}
.a-of-step.is-live .a-of-dot {
  box-shadow: 0 0 0 0 currentColor;
  animation: a-of-dot-breathe 1.2s ease-out 2;
}
@keyframes a-of-dot-breathe {
  0% { box-shadow: 0 0 0 0 currentColor, 0 0 8px currentColor; }
  100% { box-shadow: 0 0 0 7px transparent, 0 0 0 transparent; }
}
.a-of-step-qps {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
  white-space: nowrap;
}
.a-of-rail {
  display: block;
  height: 14px;
  margin: var(--s2) 0;
  background: var(--surface-3, var(--surface-2));
  border-radius: var(--r1);
  overflow: hidden;
}
.a-of-rail > i {
  display: block;
  height: 100%;
  min-width: 0;
  border-radius: var(--r1);
  transition: width 0.5s ease;
}
.a-of-step-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px var(--s3);
  font-size: 10px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.a-of-warnchip,
.a-of-okchip {
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.a-of-warnchip {
  color: var(--danger);
  background: var(--danger-ground);
  font-weight: var(--fw-semi);
}
.a-of-okchip {
  color: var(--ink-3);
  background: var(--surface-2);
}

.a-of-stream-sum {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  padding: var(--s2) 0;
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.a-of-stream-sum b {
  font-family: var(--font-mono);
  color: var(--ink);
}
.a-of-stream-sum .a-of-mono {
  margin-left: auto;
}

/* ── 主链粒子轨道 ─────────────────────────────────────────────────────────── */
.a-of-lane {
  position: relative;
  /* 主链在上、取消支线在下：高度必须容得下第二条线（用户 2026-10-17 P0）。 */
  height: 128px;
  margin: var(--s2) 0 0;
  border-bottom: 1px solid var(--line);
}
/* 静态拓扑线：暗灰、**不动**。只有真实流转点亮那段（`.is-on`）才变蓝并向右流动。
   2026-10-17 用户纠偏：默认就画一条清晰流动的虚线 = 视觉欺骗。 */
.a-of-lane-track {
  position: absolute;
  top: 26px;
  height: 2px;
  background-image: repeating-linear-gradient(
    90deg,
    var(--line) 0 5px,
    transparent 5px 10px
  );
  opacity: 0.45;
  transition: opacity 0.2s ease;
}
.a-of-lane-track.is-on {
  background-image: repeating-linear-gradient(
    90deg,
    var(--info) 0 5px,
    transparent 5px 10px
  );
  opacity: 1;
  animation: a-of-lane-dash 1.2s linear infinite;
}
/* 方向 = 正向（左 → 右）：位移必须为正；负值会让点阵向左爬（2026-10-17 用户纠偏）。 */
@keyframes a-of-lane-dash {
  from { background-position: 0 0; }
  to { background-position: 10px 0; }
}
/* 取消支线：两条静态虚线弧（待付款 / 待发货 → 已取消），取消发生时变黄亮起。 */
.a-of-cancel-links {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.a-of-cancel-links path {
  fill: none;
  stroke: var(--line);
  stroke-width: 1;
  stroke-dasharray: 4 5;
  opacity: 0.3;
  transition: stroke 0.2s ease, opacity 0.2s ease;
}
/* 只有该分支真的发生取消（`activeEdges` 亮起）才高亮并沿弧线向右下流动。
   默认这只是一条「系统有这条路」的暗线，不是「正在取消」。 */
.a-of-cancel-links path.on {
  stroke: var(--warning);
  opacity: 1;
  animation: a-of-cancel-dash 0.8s linear infinite;
}
@keyframes a-of-cancel-dash {
  from { stroke-dashoffset: 0; }
  to { stroke-dashoffset: -9; }
}
/* ⚠️ 选择器必须带 `.a-of-lane`：`.a-of-lane-node` 定义在后面且同为单类选择器，
   只写 `.a-of-lane-cancel { top: 88px }` 会被它的 `top: 0` 覆盖 ⇒ 取消节点
   与「已完成」叠在同一位置（2026-10-17 实测）。 */
.a-of-lane .a-of-lane-cancel {
  top: 88px;
}
.a-of-lane-cancel .a-of-lane-dot::after {
  animation-duration: 3.2s;
}
/* 取消粒子：沿弧线向右下坠落（不是主链的正向直线）。 */
.a-of-cancel-particle {
  position: absolute;
  left: 10%;
  top: 30px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--pcolor);
  box-shadow: 0 0 6px var(--pcolor), 0 0 12px var(--warning);
  pointer-events: none;
}
.a-of-cancel-particle.from-waitPay {
  animation: a-of-cancel-fall-pay 1.5s cubic-bezier(0.3, 0.7, 0.4, 1) forwards;
}
.a-of-cancel-particle.from-waitShip {
  animation: a-of-cancel-fall-ship 1.5s cubic-bezier(0.3, 0.7, 0.4, 1) forwards;
}
/* 汇总事件的「大号光点」：看得见这一条代表一整批，而不是一单。
   必须排在上面两条 from-* 之后：两者同特异性，靠后者覆盖 animation 的 delay。 */
.a-of-cancel-particle.is-big {
  width: 11px;
  height: 11px;
  box-shadow: 0 0 10px var(--pcolor), 0 0 22px var(--warning), 0 0 34px var(--warning);
  animation-delay: var(--pdelay, 0ms);
}
@keyframes a-of-cancel-fall-pay {
  0% { left: 10%; top: 30px; opacity: 0; transform: scale(0.4); }
  12% { opacity: 1; transform: scale(1); }
  45% { left: 48%; top: 66px; }
  100% { left: 91%; top: 94px; opacity: 0; transform: scale(0.7); }
}
@keyframes a-of-cancel-fall-ship {
  0% { left: 37%; top: 30px; opacity: 0; transform: scale(0.4); }
  12% { opacity: 1; transform: scale(1); }
  45% { left: 62%; top: 64px; }
  100% { left: 91%; top: 94px; opacity: 0; transform: scale(0.7); }
}
.a-of-lane-node {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  width: 64px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  text-align: center;
  color: var(--ink-3);
  white-space: nowrap;
}
.a-of-lane-dot {
  position: relative;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  margin-bottom: 2px;
  box-shadow: 0 0 6px currentColor;
}
/* 雷达波：**只在节点真的有流转时**才扩散（`.is-live`）。
   2026-10-17 用户纠偏：静止时整条轨道不能有任何「在跑」的动效 —— 常驻雷达波也算假动作。 */
.a-of-lane-dot::after {
  content: '';
  position: absolute;
  inset: -4px;
  border: 1px solid currentColor;
  border-radius: 50%;
  opacity: 0.18;
}
.a-of-lane-node.is-live .a-of-lane-dot::after {
  animation: a-of-radar 2.4s ease-out infinite;
}
@keyframes a-of-radar {
  0% { transform: scale(0.7); opacity: 0.55; }
  100% { transform: scale(1.9); opacity: 0; }
}
.a-of-lane-node b {
  font-size: 10px;
  font-weight: var(--fw-med);
  color: var(--ink-2);
}
.a-of-lane-node em {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.a-of-lane-node.is-live .a-of-lane-dot {
  animation: a-of-lane-hit 1.2s ease-out 2;
}
@keyframes a-of-lane-hit {
  0% { box-shadow: 0 0 0 0 currentColor, 0 0 8px currentColor; }
  100% { box-shadow: 0 0 0 8px transparent, 0 0 2px transparent; }
}
.a-of-particle-wrap {
  position: absolute;
  top: 24px;
  height: 6px;
  pointer-events: none;
}
.a-of-particle {
  position: absolute;
  left: 0;
  top: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--pcolor);
  box-shadow: 0 0 6px var(--pcolor), 0 0 14px var(--pcolor);
  animation: a-of-fly 1.35s cubic-bezier(0.25, 0.8, 0.35, 1) forwards;
}
@keyframes a-of-fly {
  0% { left: 0; opacity: 0; transform: scale(0.4); }
  12% { opacity: 1; transform: scale(1); }
  82% { opacity: 1; transform: scale(1); }
  100% { left: calc(100% - 6px); opacity: 0; transform: scale(0.7); }
}

.a-of-stream-list {
  list-style: none;
  flex: 0 1 auto;
  min-height: 0;
  max-height: 320px;
  overflow-y: auto;
}
/* 没有 moves 时占满右栏剩余高度，给一个像样的空态，而不是一行灰字 + 大片空白。 */
.a-of-stream-empty {
  flex: 1;
  min-height: 170px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--s2);
  padding: var(--s4);
  text-align: center;
  border-top: 1px dashed var(--line);
  overflow: hidden;
}
.a-of-stream-empty b {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  font-weight: var(--fw-med);
}
.a-of-stream-empty span {
  max-width: 280px;
  font-size: 10px;
  line-height: 1.65;
  color: var(--ink-3);
}
/* 有数据时，列表下方的待命条也吃掉剩余高度 —— 面板不会在底部空一截。 */
.a-of-stream-foot {
  position: relative;
  flex: 1;
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--s2);
  margin-top: var(--s2);
  padding-top: var(--s2);
  border-top: 1px dashed var(--line);
  font-size: 10px;
  color: var(--ink-3);
  overflow: hidden;
}
.a-of-stream-radar {
  position: relative;
  flex: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 1px solid var(--info);
  box-sizing: border-box;
}
.a-of-stream-empty .a-of-stream-radar {
  width: 42px;
  height: 42px;
  margin-bottom: var(--s2);
  border-color: color-mix(in srgb, var(--info) 55%, transparent);
}
.a-of-stream-radar::before,
.a-of-stream-radar::after {
  content: '';
  position: absolute;
  inset: -1px;
  border: 1px solid var(--info);
  border-radius: 50%;
  opacity: 0;
  animation: a-of-stream-ring 2.4s ease-out infinite;
}
.a-of-stream-radar::after {
  animation-delay: 1.2s;
}
@keyframes a-of-stream-ring {
  0% { transform: scale(0.7); opacity: 0.65; }
  100% { transform: scale(2.6); opacity: 0; }
}
.a-of-stream-i {
  display: grid;
  /* 2026-10-17 用户纠偏：订单号列不再钉死 62px（`SIM-BU...` 被截断、右边却有大片空白）。
     改成按内容自适应、空间不足才省略；剩余宽度全给状态文本。 */
  grid-template-columns: 48px minmax(0, auto) 76px minmax(0, 1fr);
  align-items: center;
  gap: var(--s2);
  padding: 5px 0;
  border-bottom: 1px dashed var(--line);
  font-size: 11px;
}
.a-of-stream-i .a-of-id {
  min-width: 0;
}
.a-of-id.is-batch {
  color: var(--warning);
  font-weight: var(--fw-med);
}
.a-of-stream-i:last-child {
  border-bottom: 0;
}
.a-of-stream-t {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-stream-track {
  display: flex;
  align-items: center;
}
.a-of-dot-sm {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--surface);
  border: 1px solid var(--line-2);
  box-sizing: border-box;
  flex: none;
}
.a-of-dot-sm.on {
  background: var(--success);
  border-color: var(--success);
  box-shadow: 0 0 5px var(--success);
}
.a-of-dot-sm.now {
  animation: a-of-node-pulse 1.2s ease-out infinite;
}
/* 轨道改成**虚线路线**：走过的段用流动虚线，不再是一根实心灰线。 */
.a-of-seg-sm {
  flex: 1;
  height: 1px;
  background-image: repeating-linear-gradient(
    90deg,
    var(--line-2) 0 3px,
    transparent 3px 6px
  );
}
.a-of-seg-sm.on {
  height: 2px;
  background-image: repeating-linear-gradient(
    90deg,
    var(--success) 0 5px,
    transparent 5px 10px
  );
  animation: a-of-seg-sm-flow 0.9s linear infinite;
}
@keyframes a-of-seg-sm-flow {
  from { background-position: 0 0; }
  to { background-position: -10px 0; }
}
.a-of-stream-d {
  min-width: 0;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-of-stream-d b {
  color: var(--ink);
  font-weight: var(--fw-semi);
}
.a-of-stream-quiet {
  margin-left: 6px;
  font-size: 9px;
  color: var(--ink-3);
}

.a-of-hists {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3);
}
.a-of-hists > .a-card {
  margin-bottom: 0;
  padding: var(--s3) var(--s3) var(--s2);
}
.a-of-warns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3);
  margin-top: var(--s3);
}
.a-of-warn {
  margin-bottom: 0;
  cursor: pointer;
}
.a-of-warn:hover {
  border-color: var(--line-2);
}
.a-of-warn-hd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s3);
  margin-bottom: var(--s3);
}
.a-of-warn-hd h3 {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
}
.a-of-warn-hd p {
  margin: 2px 0 0;
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-warn-n {
  text-align: right;
  white-space: nowrap;
}
.a-of-warn-n b {
  font-family: var(--font-mono);
  font-size: var(--fs-xl);
  color: var(--danger);
  line-height: 1;
}
.a-of-warn-n span {
  margin-left: 4px;
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-agebars {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.a-of-agebar {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 32px;
  align-items: center;
  gap: var(--s2);
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-agebar > span {
  white-space: nowrap;
}
.a-of-agebar > i {
  display: block;
  height: 8px;
  background: var(--surface-3, var(--surface-2));
  border-radius: var(--r1);
  overflow: hidden;
}
.a-of-agebar > i > em {
  display: block;
  height: 100%;
  background: var(--danger);
  border-radius: var(--r1);
}
.a-of-agebar > b {
  font-family: var(--font-mono);
  font-weight: var(--fw-reg);
  text-align: right;
}
.a-of-top3 {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: var(--s3);
  padding-top: var(--s2);
  border-top: 1px dashed var(--line);
}
.a-of-top3-t {
  font-size: 10px;
  color: var(--ink-3);
  margin-bottom: 2px;
}
.a-of-top3-i {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s2);
  font: inherit;
  text-align: left;
  color: var(--ink-2);
  background: transparent;
  border: 0;
  border-radius: var(--r1);
  padding: 3px var(--s1);
  cursor: pointer;
}
.a-of-top3-i:hover {
  background: var(--surface-2);
}
.a-of-top3-g {
  font-size: 10px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-of-top3-i b {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--c4);
}
.a-of-top3-open {
  align-self: flex-end;
  margin-top: var(--s1);
  padding: 2px var(--s2);
  font-size: 10px;
}

.a-of-table-card {
  padding: 0;
  overflow: hidden;
}
.a-of-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s2);
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line);
}
.a-of-chip {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 3px 10px;
  cursor: pointer;
  white-space: nowrap;
}
.a-of-chip:hover {
  border-color: var(--line-2);
}
.a-of-chip.on {
  color: var(--info);
  background: var(--info-ground);
  border-color: var(--info);
}
.a-of-timeout-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  cursor: pointer;
  white-space: nowrap;
}
.a-of-filter-note {
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-table-wrap {
  max-height: 560px;
  overflow: auto;
}
.a-of-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}
.a-of-table th,
.a-of-table td {
  padding: 7px var(--s3);
  text-align: left;
  white-space: nowrap;
  border-bottom: 1px solid var(--line);
}
.a-of-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  font-weight: var(--fw-semi);
  font-size: 10px;
  color: var(--ink-3);
  background: var(--surface-2);
}
.a-of-table tbody tr {
  cursor: pointer;
}
.a-of-table tbody tr:hover {
  background: var(--surface-2);
}
.a-of-table tbody tr.is-jump {
  background: var(--success-ground);
}
.a-of-table tbody tr.is-timeout {
  box-shadow: inset 3px 0 0 var(--danger);
}
.a-of-table td.a-of-goods {
  max-width: 260px;
}
.a-of-table .a-of-r {
  text-align: right;
}
.a-of-mono {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-2);
}
.a-of-st {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.a-of-badge-bad,
.a-of-badge-ok {
  display: inline-block;
  border-radius: var(--r1);
  padding: 1px 6px;
  font-size: 10px;
  white-space: nowrap;
}
.a-of-badge-bad {
  color: var(--danger);
  background: var(--danger-ground);
  font-weight: var(--fw-semi);
}
.a-of-badge-ok {
  color: var(--ink-3);
}
.a-of-op {
  padding: 1px 8px;
  font-size: 10px;
}

/* ── 链路抽屉（右侧滑入；左列 Top 30，右列时间线）────────────────────────────── */
.a-of-mask {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  justify-content: flex-end;
  background: rgba(0, 0, 0, 0.34);
}
.a-of-drawer {
  display: flex;
  flex-direction: column;
  width: min(780px, 94vw);
  height: 100%;
  background: var(--surface);
  border-left: 1px solid var(--line);
  box-shadow: -12px 0 30px rgba(0, 0, 0, 0.18);
}
.a-of-drawer-hd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s3);
  padding: var(--s4);
  border-bottom: 1px solid var(--line-2);
}
.a-of-drawer-hd h3 {
  margin: 0;
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
}
.a-of-drawer-hd p {
  margin: 2px 0 0;
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-drawer-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
}
.a-of-drawer-side {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--line);
}
.a-of-drawer-search {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line-2);
  background: var(--surface-2);
}
.a-of-search {
  flex: 1;
  min-width: 0;
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 5px var(--s2);
  outline: none;
}
.a-of-search:focus {
  border-color: var(--info);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--info) 20%, transparent);
}
.a-of-search::placeholder {
  color: var(--ink-3);
}
.a-of-search-n {
  flex: none;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-drawer-list {
  flex: 1;
  min-height: 0;
  list-style: none;
  overflow-y: auto;
  padding: var(--s2) 0;
}
.a-of-drawer-i {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 2px var(--s2);
  align-items: center;
  padding: 7px var(--s4);
  border-bottom: 1px dashed var(--line);
  border-left: 3px solid transparent;
  cursor: pointer;
  font-size: 11px;
  transition: background 0.18s ease, border-color 0.18s ease;
}
.a-of-drawer-i:hover,
.a-of-drawer-i:focus-visible {
  background: var(--surface-2);
  outline: none;
}
/* 选中态必须一眼看得出来：深蓝底 + 左侧亮条，而不是只换一档浅色。 */
.a-of-drawer-i.on {
  background: color-mix(in srgb, var(--info) 22%, var(--surface));
  border-left-color: var(--info);
}
.a-of-drawer-i.on .a-of-id {
  color: var(--ink);
  font-weight: var(--fw-semi);
}
.a-of-drawer-i.is-timeout {
  box-shadow: inset 3px 0 0 var(--danger);
}
.a-of-drawer-g {
  font-size: 10px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-of-drawer-i .a-of-st {
  grid-column: 1;
}
.a-of-drawer-i > b {
  grid-column: 2;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: var(--fw-reg);
  color: var(--c4);
  text-align: right;
}
.a-of-drawer-i .a-of-badge-bad {
  grid-column: 2;
  justify-self: end;
}
.a-of-drawer-detail {
  overflow-y: auto;
  padding: var(--s4);
}
.a-of-drawer-oh {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s2);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
}
.a-of-drawer-oh .a-of-id {
  font-size: var(--fs-md);
  color: var(--ink);
}
.a-of-drawer-now {
  font-size: 10px;
  color: var(--info);
  background: var(--info-ground);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.a-of-drawer-sub {
  margin: var(--s2) 0 var(--s3);
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.a-of-drawer-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s2) var(--s3);
  margin: 0 0 var(--s3);
  padding: var(--s2) var(--s3);
  background: var(--surface-2);
  border-radius: var(--r2);
}
.a-of-drawer-kv dt {
  font-size: 10px;
  color: var(--ink-3);
}
.a-of-drawer-kv dd {
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
}
.a-of-drawer .a-of-tl {
  margin-top: var(--s3);
}

@media (max-width: 1100px) {
  .a-of-hero {
    grid-template-columns: minmax(0, 1fr) 260px;
  }
  .a-of-hists {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .a-of-hists > .a-card:last-child {
    grid-column: 1 / -1;
  }
}
@media (max-width: 900px) {
  .a-of-hero,
  .a-of-main,
  .a-of-warns,
  .a-of-hists {
    grid-template-columns: minmax(0, 1fr);
  }
  .a-of-hists > .a-card:last-child {
    grid-column: auto;
  }
  .a-of-step-qps {
    margin-left: 0;
  }
  .a-of-flow-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .a-of-flow-dist {
    padding-left: 0;
    padding-top: var(--s2);
    border-left: 0;
    border-top: 1px dashed var(--line);
  }
  .a-of-drawer {
    width: 100%;
  }
  .a-of-drawer-body {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(120px, 40%) minmax(0, 1fr);
  }
  .a-of-drawer-side {
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
}
</style>
