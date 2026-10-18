<template>
  <section class="a-mp">
    <!-- 回放横幅（2026-10-17 全链路联动重做）：回放期间本页**停掉实时流**，
         改用 `replaySession.metricTicks`（由事件台账按日志时间逐秒推算）动态重绘。
         ⚠️ 实时态与回放态是**两套视图**：实时视图原样保留（含用户明令保留的 5xx/P99 红色警告），
            回放视图不伪造健康度、缓存命中率、锁等待 —— 台账里没有这些字段。 -->
    <ReplayBanner />
    <template v-if="!replaySession.active">
    <!-- ── ① 状态带：健康度由后端算（`MetricsSnapshotService.health()`），本页不重算 ───── -->
    <header class="a-status">
      <div class="a-stat">
        <b class="a-hv" :class="'a-hv--' + healthLevel">
          <span class="a-dot" aria-hidden="true"></span>{{ healthLabel }}
        </b>
        <span>健康度（后端算）</span>
      </div>
      <div class="a-stat">
        <b>{{ errRate === null ? '—' : (errRate * 100).toFixed(1) + '%' }}</b>
        <span>5xx 占比（本拍）</span>
      </div>
      <div class="a-stat">
        <b>{{ clientErrRate === null ? '—' : (clientErrRate * 100).toFixed(1) + '%' }}</b>
        <span>4xx 占比（本拍）</span>
      </div>
      <!-- A4 / D3-2：MQ 消费重试彻底失败、落 DLQ 的累计条数 + 本拍增量 + 同库副本已落库行数。
           D3 手验补全：整张卡可点 ⇒ 右侧死信详情抽屉；0 条置灰、>0 红框高亮（警告语义）。 -->
      <button
        type="button"
        class="a-stat a-stat--dlq"
        :class="{
          'is-zero': dlqTotal === 0,
          'is-alert': dlqTotal !== null && dlqTotal > 0,
          'is-unknown': dlqTotal === null,
        }"
        aria-label="死信数量：点击查看死信详情"
        @click="openDlqDrawer"
      >
        <b :class="{ 'a-bad': dlqTotal > 0 }">{{ dlqTotal === null ? '—' : fmtInt(dlqTotal) }}</b>
        <span>死信数量（累计 · 本拍 {{ dlqDelta === null ? '—' : '+' + fmtInt(dlqDelta) }} · 已落库 {{ dlqPersisted === null ? '—' : fmtInt(dlqPersisted) }}）· <em>查看详情 ›</em></span>
      </button>
      <div class="a-tz">
        <span class="a-chip-utc">{{ phaseText }}</span>
        <span>第 <b>{{ seq }}</b> 拍 · 窗口 {{ WINDOW }} 秒</span>
        <span class="a-tz-off">{{ lastTickAt }}</span>
      </div>
      <DemoModeToggle />
    </header>

    <p v-if="lost" class="a-flash" :class="lost.err ? 'bad' : 'warn'">
      {{ lostText }}
      <button type="button" class="a-btn" @click="connect">立即重连</button>
    </p>

    <ul v-if="healthReasons.length" class="a-reasons" :class="'a-reasons--' + healthLevel">
      <li v-for="(r, i) in healthReasons" :key="i">{{ r }}</li>
    </ul>

    <!-- ── ② 主体：左时间栈 / 右实体栏 ───────────────────────────────────────────
         ⚠️ 滚动容器（`.mp-body`）与两栏网格（`.mp-grid`）**必须分开**：若让同一个元素既是
         `overflow-y:auto` 又是 `display:grid`，固定高度下右栏会比左栏高、溢出部分不计入
         `scrollHeight` ⇒ 库存区底部会被裁掉且滚不到（3a.4 实测）。网格高度交给内容撑。 -->
    <div class="mp-body">
      <div class="mp-grid">
      <div class="mp-stack">
        <!-- QPS 堆叠 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>QPS · 堆叠</h3>
            <span class="mp-chart-n">成功 / 4xx / 5xx · 每秒</span>
            <span class="mp-legend" aria-hidden="true">
              <i class="lg-ok"></i>成功
              <i class="lg-4xx"></i>4xx
              <i class="lg-5xx"></i>5xx
            </span>
          </div>
          <BarChart
            :categories="xLabels" :series="qpsChartSeries"
            height="200px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            value-formatter="{value}" :tooltip-formatter="qpsTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="还没收到第一拍" empty-overlay @retry="connect"
          />
        </section>

        <!-- 命中率：命中/未命中柱 + 命中率线 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>缓存命中率</h3>
            <span class="mp-chart-n">柱 = 命中/未命中计数（左轴）· 虚线 = 命中率（右轴 0–100）</span>
            <span class="mp-legend" aria-hidden="true">
              <i class="lg-hit"></i>命中
              <i class="lg-miss"></i>未命中
              <i class="lg-rate"></i>命中率
            </span>
          </div>
          <LineChart
            :categories="xLabels" :series="hitChartSeries"
            height="170px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            boundary-gap y-min="0" y2-min="0" y2-max="100"
            y-formatter="{value}" y2-formatter="{value}%"
            :tooltip-formatter="hitTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="还没收到第一拍" empty-overlay @retry="connect"
          />
        </section>

        <!-- 平均延迟 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>平均延迟</h3>
            <span class="mp-chart-n">逐拍均值 · 无样本的拍断开（不是 0）· 纵轴从 0 起</span>
          </div>
          <LineChart
            :categories="xLabels" :series="meanChartSeries"
            height="160px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            boundary-gap y-min="0" y-formatter="{value} ms"
            :tooltip-formatter="meanTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="还没收到第一拍" empty-overlay @retry="connect"
          />
        </section>

        <!-- 锁等待 -->
        <section class="mp-chart">
          <div class="mp-chart-h">
            <h3>锁等待</h3>
            <span class="mp-chart-n">每把锁一条 · 无等待的拍断开（不是 0）</span>
            <span v-if="lockNames.length" class="mp-legend" aria-hidden="true">
              <template v-for="(n, i) in lockNames" :key="n">
                <i :style="{ background: 'var(--c' + ((i % 10) + 1) + ')' }"></i><span :title="n">{{ lockLabel(n) }}</span>
              </template>
            </span>
          </div>
          <LineChart
            :categories="xLabels" :series="waitSeriesWithMarks"
            height="160px" :show-legend="false" :show-x-axis="false"
            :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
            boundary-gap y-min="0" y-formatter="{value} ms"
            :tooltip-formatter="waitTooltip" :animation="false"
            :loading="first" :error="errMsg" empty-text="窗口内没有任何锁活动" empty-overlay @retry="connect"
          />
        </section>

        <!-- 锁竞争热力图 -->
        <section class="mp-chart mp-chart--last">
          <div class="mp-chart-h">
            <h3>锁竞争热力图</h3>
            <span class="mp-chart-n">行 = 锁名 · 列 = 最近 {{ WINDOW }} 拍 · 越深 = 本拍超时越多</span>
          </div>
          <HeatmapChart
            :x-labels="xLabels" :y-labels="lockAxisLabels" :data="heatData" value-name="超时数"
            height="190px" :legend-text="['超时多', '超时少']" :x-label-interval="heatInterval"
            :grid-left="64" :grid-right="56" :tooltip-formatter="heatTooltip" :animation="false"
            :loading="first" :error="errMsg"
            empty-text="窗口内没有任何锁活动" @retry="connect"
          />
          <p class="mp-axis-note">
            ⇡ 以上五张图共用同一根 x 轴：前四张不画刻度，只在最底这张画一次。
          </p>
        </section>
      </div>

      <!-- ══ 右栏：实体域 ═════════════════════════════════════════════════════ -->
      <aside class="mp-rail">
        <!-- 本拍指标 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">本拍指标 <span>每秒 1 拍 · 缺值显示 —</span></h3>
          <div class="mp-kpis">
            <div class="mp-kpi">
              <span class="mp-kpi-k">QPS</span>
              <span class="mp-kpi-v"><b>{{ first ? '—' : fmt1(shown.qps) }}</b><em>次/秒</em></span>
              <span class="mp-kpi-d">本拍请求 {{ fmtInt(latestRow ? latestRow.count : 0) }} 次</span>
              <span class="mp-spark" aria-hidden="true">
                <i v-for="(b, i) in qpsSpark" :key="i" :class="{ off: b.off }" :style="{ height: b.h + '%' }"></i>
              </span>
            </div>
            <div class="mp-kpi">
              <span class="mp-kpi-k">平均延迟</span>
              <span class="mp-kpi-v"><b>{{ meanNow === null ? '—' : fmtInt(Math.round(shown.mean)) }}</b><em>ms</em></span>
              <span class="mp-kpi-d">P99（衰减窗口，非本拍）{{ p99Now === null ? '—' : fmtInt(Math.round(p99Now)) }} ms</span>
              <span class="mp-kpi-nospark">无序列</span>
            </div>
            <div class="mp-kpi">
              <span class="mp-kpi-k">缓存命中率</span>
              <span class="mp-kpi-v"><b>{{ hitRateNow === null ? '—' : fmt1(shown.hitRate * 100) }}</b><em>%</em></span>
              <span class="mp-kpi-d">命中 {{ fmtInt(latestRow ? latestRow.hit : 0) }} · 未命中 {{ fmtInt(latestRow ? latestRow.miss : 0) }}</span>
              <span class="mp-spark" aria-hidden="true">
                <i v-for="(b, i) in hitSpark" :key="i" :class="{ off: b.off }" :style="{ height: b.h + '%' }"></i>
              </span>
            </div>
            <div class="mp-kpi">
              <span class="mp-kpi-k">锁等待次数</span>
              <span class="mp-kpi-v"><b>{{ first ? '—' : fmtInt(Math.round(shown.lockWait)) }}</b><em>次</em></span>
              <span class="mp-kpi-d">全部锁名本拍 timeouts 之和</span>
              <span class="mp-kpi-nospark">无序列</span>
            </div>
          </div>
        </section>

        <!-- 设施 3 类 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">依赖状态 <span>常量 · 画点不画线</span></h3>
          <ul class="mp-facs">
            <li v-for="f in facilities" :key="f.key" class="mp-fac">
              <component :is="f.icon" :size="15" :style="{ color: f.color }" />
              <span class="mp-fac-k">{{ f.label }}</span>
              <i class="mp-fac-dot" :class="'is-' + f.tone"></i>
              <span class="mp-fac-s">{{ f.text }}</span>
            </li>
          </ul>
        </section>

        <!-- 严重度分布 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">严重度分布 <span>最近 {{ rows.length || 0 }} 拍 health.level</span></h3>
          <div class="mp-sev-bar" aria-hidden="true">
            <i
              v-for="s in SEV_META" :key="s.key"
              :style="{ flexGrow: sevCounts[s.key] || 0, background: s.color }"
            ></i>
          </div>
          <ul class="mp-sevs">
            <li v-for="s in SEV_META" :key="s.key">
              <component :is="s.icon" :size="14" :style="{ color: s.color }" />
              <span>{{ s.label }}</span>
              <b>{{ sevCounts[s.key] }}</b>
              <em>{{ sevPct(s.key) }}</em>
            </li>
          </ul>
        </section>

        <!-- 防护命中：行数由后端帧决定（D3 起 12 类），标题跟着走，不写死 -->
        <section class="mp-rail-sec">
          <h3 class="mp-rail-h">防护命中 · {{ protRows.length || '—' }} 类 <span>本拍增量</span></h3>
          <ul v-if="protRows.length" class="mp-prots">
            <li v-for="p in protRows" :key="p.tag" class="mp-prot">
              <span class="mp-prot-k">
                <component :is="PROT_ICON[p.tag] || ShieldCheck" :size="14" :style="{ color: protColor(p.tag) }" />
                <span>{{ p.label }}</span>
              </span>
              <span class="mp-prot-track">
                <i :style="{ width: protPct(p.value), background: protColor(p.tag) }"></i>
              </span>
              <b>{{ fmtInt(p.value) }}</b>
            </li>
          </ul>
          <p v-else class="mp-empty">还没收到第一拍。</p>
        </section>

        <!-- 库存水位 -->
        <section class="mp-rail-sec mp-stock">
          <h3 class="mp-rail-h">
            <Droplets :size="16" :style="{ color: 'var(--c4)' }" />
            库存水位 <span>{{ stockRows.length }} 条 · 最低库存前 20</span>
          </h3>

          <p v-if="!stockAvailable" class="mp-empty">
            {{ first ? '还没收到第一拍。' : '本拍库存水位读取失败（后端已标注为不可用，流仍在推）—— 不是「库存为 0」。' }}
          </p>
          <template v-else>
            <div class="mp-stock-tools">
              <input
                v-model.trim="stockQuery" type="search" class="mp-stock-search"
                placeholder="搜 SKU 名 / 编码 / 规格" aria-label="搜索库存"
              />
              <div class="mp-stock-filters">
                <button
                  v-for="f in STOCK_FILTERS" :key="f.key" type="button"
                  class="mp-chip" :class="{ on: stockFilter === f.key }"
                  @click="stockFilter = f.key"
                >{{ f.label }}</button>
              </div>
              <p class="mp-stock-note">
                仅在最低库存 20 条内搜索 / 筛选
                <span v-if="stockFallbackAny"> · 基线缺失，按当前库存最高值 {{ fmtInt(stockFallbackMax) }} 兜底（*）</span>
              </p>
            </div>

            <p v-if="!stockRows.length" class="mp-empty">库存查询没有返回任何 SKU。</p>
            <p v-else-if="!stockFiltered.length" class="mp-empty">没有匹配的 SKU。</p>
            <template v-else>
              <ul class="mp-stock-list">
                <li v-for="r in stockFiltered" :key="r.sku" class="mp-stock-row">
                  <div class="mp-stock-top">
                    <span class="mp-stock-name" :title="r.goodName || r.sku">{{ r.goodName || '（无商品名）' }}</span>
                    <span class="mp-stock-n">
                      <b>{{ r.stock === null || r.stock === undefined ? '—' : fmtInt(Math.round(displayOf(r))) }}</b>
                      <span>/ {{ fmtInt(effectiveBaseline(r)) }}{{ stockFallback(r) ? '*' : '' }}</span>
                    </span>
                  </div>
                  <div class="mp-stock-sub">
                    <code>{{ r.sku }}</code>
                    <span v-if="r.axisName"> · {{ r.axisName }}</span>
                    <em class="mp-stock-tag" :class="'is-' + stockStatus(r)">{{ stockStatusLabel(r) }}</em>
                  </div>
                  <div class="mp-stock-track" :title="stockTitle(r)">
                    <div class="mp-stock-fill" :class="barTone(r)" :style="{ width: barWidth(r) }"></div>
                  </div>
                </li>
              </ul>
            </template>
          </template>
        </section>
      </aside>
      </div>
    </div>
    </template>

    <template v-else>
      <!-- ══ 回放视图：由 `replaySession.metricTicks` 驱动，随回放时钟动态重绘 ══════════
           ⚠️ 这不是实时框架图的「冻结快照」，是一套**独立的、可推算的**回放指标：
              QPS（事件推算）/ 错误事件占比 / 延迟样本 / 类别分布。
           ⚠️ 缓存命中率、锁等待、HTTP 4xx/5xx 拆分在台账里没有字段 ⇒ 回放视图不画、不猜；
              右栏底部明确写「不可推算」。 -->
      <header class="a-status a-status--rp">
        <div class="a-stat">
          <b class="a-hv a-hv--warn"><span class="a-dot" aria-hidden="true"></span>回放</b>
          <span>指标由事件台账推算</span>
        </div>
        <div class="a-stat">
          <b>{{ replayNow ? fmt1(replayNow.qps) : '—' }}</b>
          <span>QPS（事件推算）</span>
        </div>
        <!-- 业务失败率（左大字）= **error only** ÷ 本拍事件数；警告率（黄）与防护拦截率（紫）单列。
             ⚠️ 三口径并排但绝不相加 —— 大拦截量 / 大降级量下 fail 必须仍然很低。 -->
        <div class="a-stat a-stat--fail">
          <div class="rp-fail">
            <b>{{ replayFailText }}<i>%</i></b>
            <em class="rp-warn">警告率 (warn) {{ replayWarnText }}%</em>
            <em class="rp-prot">防护拦截率 (protect) {{ replayProtectText }}%</em>
          </div>
          <span>业务失败率 (fail)</span>
        </div>
        <div class="a-stat">
          <b>{{ replayP99Text }}</b>
          <span>P99 延迟（样本内）</span>
        </div>
        <div class="a-tz">
          <span class="a-chip-utc">回放中（实时流已切断）</span>
          <span>指标拍 <b>{{ replayRows.length }}</b> · 窗口 {{ WINDOW }} 秒</span>
          <span class="a-tz-off">{{ replayClockText }}</span>
        </div>
      </header>

      <p class="mp-rp-warn">
        <b>回放指标由事件台账推算，非 HTTP 原始指标。</b>
        QPS = 该秒日志时间内吐出的事件条数（不是后端 `http.server.requests` 的请求量）；
        没有 4xx/5xx 拆分，也没有缓存命中 / 锁等待字段。延迟只统计带 `DurationUs` 的事件，
        无样本时画断点而不是 0。业务失败率 = <b>错误（error）÷ 本拍事件数</b>；
        <b>警告（warn）与防护拦截（protect）各自单列，不计入失败率，也不参与健康度判定。</b>
      </p>

      <div class="mp-body">
        <div class="mp-grid">
          <div class="mp-stack">
            <!-- ① QPS（事件推算）· 按事件类别堆叠 -->
            <section class="mp-chart">
              <div class="mp-chart-h">
                <h3>QPS（事件推算）</h3>
                <span class="mp-chart-n">按事件类别堆叠 · 每 1 秒日志时间一拍</span>
                <span class="mp-legend" aria-hidden="true">
                  <template v-for="s in replayQpsSeries" :key="s.name">
                    <i :style="{ background: 'var(--c' + s.colorIndex + ')' }"></i>{{ s.name }}
                  </template>
                </span>
              </div>
              <BarChart
                :categories="replayXLabels" :series="replayQpsSeries"
                height="200px" :show-legend="false" :show-x-axis="false"
                :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
                value-formatter="{value}" :tooltip-formatter="replayQpsTooltip" :animation="false"
                :loading="!replayRows.length && !replaySession.done"
                is-replay empty-overlay
                empty-text="本批台账里还没有可推算的事件" @retry="connect"
              />
            </section>

            <!-- ② 业务失败率（红，只数 error）vs 警告率（黄）vs 防护拦截率（紫）-->
            <section class="mp-chart">
              <div class="mp-chart-h">
                <h3>业务失败率 vs 警告率 vs 防护拦截率</h3>
                <span class="mp-chart-n">fail = error ÷ 本拍事件数 · warn / protect 单列，绝不进 fail 分子</span>
                <span class="mp-legend" aria-hidden="true">
                  <i class="lg-fail"></i>业务失败率
                  <i class="lg-warn"></i>警告率
                  <i class="lg-protect"></i>防护拦截率
                </span>
              </div>
              <LineChart
                :categories="replayXLabels" :series="replayFailSeries"
                height="160px" :show-legend="false" :show-x-axis="false"
                :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
                boundary-gap y-min="0" y-max="100" y-formatter="{value}%"
                :tooltip-formatter="replayFailTooltip" :animation="false"
                :loading="!replayRows.length && !replaySession.done"
                is-replay empty-overlay
                empty-text="本批台账里还没有可推算的事件" @retry="connect"
              />
            </section>

            <!-- ③ 延迟（只统计带 DurationUs 的样本） -->
            <section class="mp-chart">
              <div class="mp-chart-h">
                <h3>延迟（DurationUs 样本）</h3>
                <span class="mp-chart-n">均值 / P99 · 无样本的拍断开（不是 0）</span>
                <span class="mp-legend" aria-hidden="true">
                  <i class="lg-mean"></i>均值
                  <i class="lg-p99"></i>P99
                </span>
              </div>
              <LineChart
                :categories="replayXLabels" :series="replayLatencySeries"
                height="160px" :show-legend="false" :show-x-axis="false"
                :grid-left="64" :grid-right="56" :grid-top="24" :grid-bottom="14"
                boundary-gap y-min="0" y-formatter="{value} ms"
                :tooltip-formatter="replayLatencyTooltip" :animation="false"
                :loading="!replayRows.length && !replaySession.done"
                is-replay empty-overlay
                empty-text="本批台账里还没有带 DurationUs 的事件" @retry="connect"
              />
            </section>

            <!-- ④ 事件类别分布（当前窗口累计）—— 用**同一个** BarChart 组件画横向条，
                 不是另写一套方块列表（用户 2026-10-17：回放禁止简化版图表）。 -->
            <section class="mp-chart">
              <div class="mp-chart-h">
                <h3>事件类别分布</h3>
                <span class="mp-chart-n">当前窗口累计条数 · 按条数倒序 · 与实时页同一个横向柱组件</span>
              </div>
              <BarChart
                :categories="replayKindDistLabels" :series="replayKindDistSeries"
                height="180px" horizontal :show-legend="false" label-truncate="10"
                value-formatter="{value}" :tooltip-formatter="replayKindDistTooltip" :animation="false"
                :loading="!replayRows.length && !replaySession.done"
                is-replay empty-overlay
                empty-text="当前窗口没有事件 —— 回放时钟在走，但这 1 秒台账里没有事件。" @retry="connect"
              />
            </section>

            <!-- ⑤ 事件类别 × 时间热力图 —— 复用实时页的 HeatmapChart。
                 数据全部来自台账（Kind × 日志秒），不是拿 0 伪造锁等待。 -->
            <section class="mp-chart mp-chart--last">
              <div class="mp-chart-h">
                <h3>事件类别 × 时间热力图</h3>
                <span class="mp-chart-n">行 = 事件类别 · 列 = 最近 {{ WINDOW }} 拍（1 秒日志时间）· 越深 = 本拍条数越多</span>
              </div>
              <HeatmapChart
                :x-labels="replayXLabels" :y-labels="replayHeatLabels" :data="replayHeatData"
                value-name="事件数" height="190px" :legend-text="['多', '少']"
                :x-label-interval="replayHeatInterval" :grid-left="64" :grid-right="56"
                :tooltip-formatter="replayHeatTooltip" :animation="false"
                is-replay empty-text="本批台账里还没有可推算的事件" @retry="connect"
              />
              <p class="mp-axis-note">
                ⇡ 上面三张时间序列图与这张热力图共用同一根日志时间 x 轴；纵向柱状分布图按类别聚合。
              </p>
            </section>
          </div>

          <aside class="mp-rail">
            <section class="mp-rail-sec">
              <h3 class="mp-rail-h">回放本拍 <span>1 秒日志时间 · 缺值显示 —</span></h3>
              <div class="mp-kpis">
                <div class="mp-kpi">
                  <span class="mp-kpi-k">本拍事件数</span>
                  <span class="mp-kpi-v"><b>{{ replayNow ? fmtInt(replayNow.count) : '—' }}</b><em>条</em></span>
                  <span class="mp-kpi-d">其中 protect {{ replayNow ? fmtInt(replayNow.protects) : '—' }} · warn {{ replayNow ? fmtInt(replayNow.warns) : '—' }} · error {{ replayNow ? fmtInt(replayNow.errors) : '—' }}</span>
                  <span class="mp-kpi-nospark">每 1 秒日志时间</span>
                </div>
                <div class="mp-kpi">
                  <span class="mp-kpi-k">业务失败率 (fail)</span>
                  <span class="mp-kpi-v"><b>{{ replayFailText }}</b><em>%</em></span>
                  <span class="mp-kpi-d">error {{ replayNow ? fmtInt(replayNow.fails) : '—' }} / 事件 {{ replayNow ? fmtInt(replayNow.count) : '—' }}</span>
                  <span class="mp-kpi-nospark">警告率 (warn) {{ replayWarnText }}% · 防护拦截率 (protect) {{ replayProtectText }}%（均不计入 fail）</span>
                </div>
                <div class="mp-kpi">
                  <span class="mp-kpi-k">P99 延迟</span>
                  <span class="mp-kpi-v"><b>{{ replayP99Text }}</b><em>ms</em></span>
                  <span class="mp-kpi-d">均值 {{ replayNow && replayNow.meanMs !== null ? fmt1(replayNow.meanMs) : '—' }} ms · 样本 {{ replayNow ? fmtInt(replayNow.latencyCount) : '—' }} 条</span>
                  <span class="mp-kpi-nospark">仅 DurationUs</span>
                </div>
                <div class="mp-kpi">
                  <span class="mp-kpi-k">已播事件</span>
                  <span class="mp-kpi-v"><b>{{ fmtInt(replaySession.cursor) }}</b><em>/ {{ fmtInt(replaySession.count) }}</em></span>
                  <span class="mp-kpi-d">回放倍率 {{ replaySession.speed }}× · 本帧 ≤{{ replaySession.maxPerFrame }} 条</span>
                  <span class="mp-kpi-nospark">实吐 {{ fmtInt(replaySession.effectiveRate) }} 条/秒</span>
                </div>
              </div>
            </section>

            <section class="mp-rail-sec">
              <h3 class="mp-rail-h">本轮回放 <span>记录级聚合 · 非时间序列</span></h3>
              <ul class="mp-rp-facts">
                <li>时间跨度 <b>{{ replaySpanText }}</b></li>
                <li>回放时钟 <b>{{ replayClockText }}</b></li>
                <li>指标拍 <b>{{ fmtInt(replaySession.metricSeq) }}</b> 个（滚动保留最近 600）</li>
                <li v-if="replaySession.recordSnapshot">
                  记录总量 <b>{{ fmtInt(replaySession.recordSnapshot.totalRequests) }}</b> 请求 ·
                  拦截 {{ fmtInt(replaySession.recordSnapshot.blockedRequests) }} ·
                  失败 {{ fmtInt(replaySession.recordSnapshot.failedRequests) }} ·
                  耗时 {{ fmt1((replaySession.recordSnapshot.durationMs || 0) / 1000) }} 秒
                </li>
                <li v-else class="mp-rp-facts-miss">这条演练记录没有可用的聚合快照。</li>
              </ul>
            </section>

            <section class="mp-rail-sec">
              <h3 class="mp-rail-h">不可推算的指标 <span>台账无字段 · 不伪造</span></h3>
              <p class="mp-rp-note">
                事件台账没有 HTTP 状态码、缓存命中字段、锁等待耗时 ⇒
                <b>缓存命中率、锁等待、4xx/5xx 拆分</b>在回放视图中不展示，也不拿 0 或估算值填充。
                要看这些指标，请退出回放回到实时视图。
              </p>
            </section>
          </aside>
        </div>
      </div>
    </template>

    <!-- ── 死信详情抽屉（D3 手验补全）──────────────────────────────────────────
         数据源：`POST /admin/observe/dlq/list`（同库副本 `observed_event_dlq`，不是 broker）。
         交互：点行展开 `RawBody`（连 JSON 都解不开的那种才会落这一列）；空态逐字写
         「当前没有处理失败的死信消息」—— 与 `null`（读不到）区分，后者由加载/错误态表达。 -->
    <RightDrawer
      v-if="dlqOpen"
      title="死信详情"
      :subtitle="dlqSubtitle"
      aria-label="死信详情抽屉"
      @close="closeDlqDrawer"
    >
      <div class="a-dlq">
        <div class="a-dlq-bar">
          <span v-if="dlqLoading">读取中…</span>
          <span v-else>同库副本 · 最近 100 条（按落库时间倒序）</span>
          <button type="button" class="a-btn" :disabled="dlqLoading" @click="loadDlq">刷新</button>
        </div>
        <p v-if="dlqError" class="a-dlq-tip is-bad">
          {{ dlqError }}
          <button type="button" class="a-btn" @click="loadDlq">重试</button>
        </p>
        <p v-else-if="dlqLoading && !dlqRows.length" class="a-empty">正在读取死信…</p>
        <p v-else-if="!dlqRows.length" class="a-empty">当前没有处理失败的死信消息</p>
        <ul v-else class="a-dlq-list">
          <li class="a-dlq-cols" aria-hidden="true">
            <span>发生时间</span><span>TraceId</span><span>错误类型</span><span></span><span>错误详情</span>
          </li>
          <li v-for="r in dlqRows" :key="r.Id" class="a-dlq-i" :class="{ on: dlqOpenedId === r.Id }">
            <button type="button" class="a-dlq-row" @click="toggleDlqRow(r.Id)">
              <span class="a-dlq-time">{{ dlqTime(r) }}</span>
              <span class="a-dlq-trace" :title="r.TraceId || '—'">{{ r.TraceId || '—' }}</span>
              <span class="a-dlq-err" :title="r.ErrorClass || r.FailedStage || '—'">{{ dlqErrorCategory(r).label }}</span>
              <span class="a-dlq-toggle">{{ dlqOpenedId === r.Id ? '收起' : '原始体' }}</span>
              <span class="a-dlq-text" :title="r.ErrorText || ''">{{ dlqErrorText(r) }}</span>
            </button>
            <pre v-if="dlqOpenedId === r.Id" class="a-dlq-raw">{{ dlqRawText(r) }}</pre>
          </li>
        </ul>
      </div>
    </RightDrawer>
  </section>
</template>

<!--
  管理端「指标面板」页（C3 块 7 · 3a.4 重做为 S1「时间栈 + 实体栏」）。

  ⚠️ 数据源仍是一条 SSE 流（`POST /admin/observe/metrics/stream`，1 秒 1 帧），
     本页自己维护 60 拍滚动窗口（`rows`）；后端每帧只给本拍增量。
  ⚠️ 五张时间图共用 `xLabels` / `grid-left=64` / `grid-right=56`，前四张 `show-x-axis=false`，
     只由最底的锁竞争热力图画一次刻度。柱与折线都 `boundaryGap=true`，点/柱/热力格中心
     才会落在同一条竖线上。
  ⚠️ 延迟图用 `requests.meanMs`（逐拍真值）；`p95Ms/p99Ms/maxMs` 是 Micrometer 衰减窗口
     分位，不是本拍，只能放 KPI 的次要小字，不能进时间轴（TD-77）。
  ⚠️ 库存水位是全页唯一次真查询（后端 `stock()`）；`BaselineStock` 缺失时用本拍
     当前库存最高值兜底算条宽（页内标注 `*`），不再画一排灰斜纹「未测」；有基线的行
     仍按真基线算。演示源里才有真 baseline。
-->

<script setup>
/* eslint-disable no-undef */ /* 理由见 `BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import {
  AlertCircle, AlertOctagon, AlertTriangle, Bell, Clipboard, Database,
  Droplets, Gauge, Info, Layers, Lock, Package, RotateCcw, Server, Shield, ShieldCheck,
  ShoppingCart, Zap,
} from '@lucide/vue'
import { dlqList, metricsHistory, openMetricsStream } from '../../../api/observe'
import { demo, openDemoStream } from '../../../api/demo-stream'
// 回放（2026-10-17 重做）：本页在回放期间**停实时**（后端没有按时间戳的指标帧）。
import { replaySession } from '../../../api/replay-session'
import { KIND_COLOR, KIND_LABEL, KINDS } from '../../../config/labels/observe-labels'
// 死信错误中文化（D3 手验）：库里保留英文原文，只在渲染层翻译；旧行一并生效。
import { dlqErrorCategory, dlqErrorText } from './dlq-error'
import DemoModeToggle from '../../../components/observe/DemoModeToggle.vue'
import ReplayBanner from '../../../components/observe/ReplayBanner.vue'
import RightDrawer from '../../../components/observe/RightDrawer.vue'
import LineChart from '../../../components/charts/LineChart.vue'
import BarChart from '../../../components/charts/BarChart.vue'
import HeatmapChart from '../../../components/charts/HeatmapChart.vue'

/** 滚动窗口长度（拍）。热力图列数就是它。 */
const WINDOW = 60
/** KPI 数字滚动时长（与旧页一致的 0.3 秒）。 */
const TWEEN_MS = 300
/** 库存水位数字/条宽的推进时长：1 秒一帧，取 0.6 秒留出停顿。 */
const STOCK_TWEEN_MS = 600

/* ── 十二类防护的图标与环色（与日志页 9 环同一套 `KIND_COLOR`）────────────────
   D3（2026-10-18）追加后 4 类（rateLimit / circuitBreak / poolIsolation / retry）：
   前 8 类各归自己的事件环；后 4 类是**防护开关本身**的读数，统一归 `protection` 环
   （紫）—— 它们不是「某个业务环出的事」，而是「防护层挡下了多少」。 */
const PROT_ICON = {
  cacheHit: Database,
  lockTimeout: Lock,
  orderRejected: ShoppingCart,
  stockInsufficient: Package,
  auditFailed: Clipboard,
  notifyFailed: Bell,
  sensitiveHit: AlertTriangle,
  identityRejected: Shield,
  rateLimit: Gauge,
  circuitBreak: Zap,
  poolIsolation: Layers,
  retry: RotateCcw,
}
const PROT_RING = {
  cacheHit: 'cache',
  lockTimeout: 'lock',
  orderRejected: 'order',
  stockInsufficient: 'stock',
  auditFailed: 'audit',
  notifyFailed: 'notify',
  sensitiveHit: 'sensitive',
  identityRejected: 'identity',
  rateLimit: 'protection',
  circuitBreak: 'protection',
  poolIsolation: 'protection',
  retry: 'protection',
}
function protColor(tag) {
  const ring = PROT_RING[tag]
  return (ring && KIND_COLOR[ring]) || 'var(--ink-3)'
}

const SEV_META = [
  { key: 'ok', label: '健康', color: 'var(--success)', icon: Info },
  { key: 'warn', label: '警告', color: 'var(--warning)', icon: AlertCircle },
  { key: 'error', label: '错误', color: 'var(--danger)', icon: AlertOctagon },
]

const STOCK_FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'ok', label: '充足' },
  { key: 'low', label: '偏低' },
  { key: 'out', label: '缺货' },
  { key: 'unknown', label: '未测' },
]

/* ── 基础状态 ─────────────────────────────────────────────────────────────── */
const phase = ref('connecting')       // connecting | live | stopped
const errMsg = ref('')
const lost = ref(null)
const latest = ref(null)              // 最近一帧的非数字派生量（health / protections / stock）
const rows = ref([])                  // 滚动窗口：每拍一条
/** KeepAlive 是否在屏上：失活时**只收数据、不画**（ECharts / KPI 补间全部暂停）。 */
const pageActive = ref(true)
const stockQuery = ref('')
const stockFilter = ref('all')

let stream = null
let rafId = 0
let stockRaf = 0

const reduceMotion = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
  : false

/* ── 小工具 ───────────────────────────────────────────────────────────────── */
const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')
const fmt1 = (v) => Number(v || 0).toFixed(1)
function hms(ts) {
  const d = new Date(Number(ts) || 0)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
function esc(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/* ── 状态带 ───────────────────────────────────────────────────────────────── */
const first = computed(() => latest.value === null && !errMsg.value)
const seq = computed(() => (latest.value && latest.value.seq) || 0)
const lastTickAt = computed(() => (latest.value ? hms(latest.value.ts) : '—'))
const healthLevel = computed(() => (latest.value && latest.value.health && latest.value.health.level) || 'ok')
const healthLabel = computed(() => (latest.value && latest.value.health && latest.value.health.label) || '连接中')
const healthReasons = computed(() => (latest.value && latest.value.health && latest.value.health.reasons) || [])
const errRate = computed(() => (latest.value && latest.value.errorRate !== undefined ? latest.value.errorRate : null))
const clientErrRate = computed(() => (latest.value && latest.value.clientErrorRate !== undefined ? latest.value.clientErrorRate : null))
const phaseText = computed(() => (phase.value === 'live' ? '已连接' : (phase.value === 'stopped' ? '已断开' : '连接中')))
const lostText = computed(() => {
  const s = lost.value
  if (!s) return ''
  const head = s.err
    ? `指标流中断：${(s.err && s.err.message) || '连接中断'}`
    : '指标流已断开（服务端正常收尾）'
  return `${head} —— 上面的数字停在断开前最后一拍，${s.nextRetryInMs / 1000} 秒后自动重连（第 ${s.attempt} 次）`
})

/* ── 最近一拍与 KPI ───────────────────────────────────────────────────────── */
const latestRow = computed(() => (rows.value.length ? rows.value[rows.value.length - 1] : null))
const meanNow = computed(() => (latestRow.value ? latestRow.value.meanMs : null))
const hitRateNow = computed(() => (latestRow.value ? latestRow.value.hitRate : null))
const p99Now = computed(() => (latestRow.value ? latestRow.value.p99 : null))
/**
 * A4 / D3-2：死信三读数。⚠️ `num(null)` 会回 0（见上面 `num` 的定义）⇒ 这里**逐个显式判 null**：
 * 「计数器没有这个读数」「副本表读不到」「确实一条都没有」在页面上必须是三种状态，
 * 而前两种都只能显示 `—`（用户 D3 第 6 条裁定：不可用显示横杠，不显示 0）。
 */
function finiteOrNull(v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
const dlqTotal = computed(() => (latest.value && latest.value.dlq ? finiteOrNull(latest.value.dlq.total) : null))
const dlqDelta = computed(() => (latest.value && latest.value.dlq ? finiteOrNull(latest.value.dlq.delta) : null))
const dlqPersisted = computed(() => (latest.value && latest.value.dlq ? finiteOrNull(latest.value.dlq.persisted) : null))

/* ── 死信详情抽屉（D3 手验补全）────────────────────────────────────────────
   ⚠️ 点开才拉、且**不跟随 SSE 自动刷新**：死信是低频审计数据，每拍重拉会在用户展开
      RawBody 读到一半时把行冲掉；要新数据点抽屉里的「刷新」。
   ⚠️ 空列表 ≠ 读不到：`list:[]` 才显示空态文案；请求失败显示错误态（绝不写成「0 条」）。 */
const dlqOpen = ref(false)
const dlqRows = ref([])
const dlqAllTotal = ref(null)
const dlqLoading = ref(false)
const dlqError = ref('')
const dlqOpenedId = ref(null)
let dlqSeq = 0

const dlqSubtitle = computed(() => {
  if (dlqLoading.value && !dlqRows.value.length) return '正在读取…'
  if (dlqError.value && !dlqRows.value.length) return '读取失败'
  if (dlqAllTotal.value === null) return ''
  return `共 ${fmtInt(dlqAllTotal.value)} 条 · 按落库时间倒序（本页 ${fmtInt(dlqRows.value.length)} 条）`
})

function openDlqDrawer() {
  dlqOpen.value = true
  loadDlq()
}

function closeDlqDrawer() {
  dlqOpen.value = false
  dlqOpenedId.value = null
}

async function loadDlq() {
  const my = ++dlqSeq
  dlqLoading.value = true
  dlqError.value = ''
  try {
    const data = await dlqList({ limit: 100, offset: 0 })
    if (my !== dlqSeq) return
    if (data && data.success === false) {
      dlqError.value = data.msg || '死信列表读取失败'
      return
    }
    dlqRows.value = Array.isArray(data && data.list) ? data.list : []
    dlqAllTotal.value = finiteOrNull(data && data.total)
  } catch (e) {
    if (my !== dlqSeq) return
    dlqError.value = (e && e.message) || '死信列表读取失败（网络或服务不可用）'
  } finally {
    if (my === dlqSeq) dlqLoading.value = false
  }
}

function toggleDlqRow(id) {
  dlqOpenedId.value = dlqOpenedId.value === id ? null : id
}

/** 发生时间优先 `OccurredAt`；非法 JSON 的原始消息没有事件时刻 ⇒ 退回落库时刻 `CreatedAt`。 */
function dlqTime(row) {
  return row.OccurredAt || row.CreatedAt || '—'
}

/** 展开内容只展示 `RawBody`：能解析的事件体不冒充「原始消息体」，如实说明这一列为空的原因。 */
function dlqRawText(row) {
  if (row.RawBody) return row.RawBody
  return '（该条死信没有原始消息体：事件本身可解析，失败发生在落库/投递阶段）'
}

const shown = reactive({ qps: 0, mean: 0, hitRate: 0, lockWait: 0 })

/* ── 回放指标（全链路联动 · 2026-10-17）───────────────────────────────────────
   ⚠️ 数据源是 `replaySession.metricTicks`（`api/replay-metrics.js` 按日志时间 1 秒一拍
      从**已吐出的事件**算出来的），不是实时 SSE，也不是冻结快照。
   ⚠️ 页面只读，不自己起定时器：回放时钟在 `replay-session.js` 里唯一一份。 */
/** 指标图窗口：与实时视图同为 60 拍（60 秒日志时间）。 */
const replayRows = computed(() => replaySession.metricTicks.slice(-WINDOW))
const replayXLabels = computed(() => replayRows.value.map((r) => hms(r.ts)))
const replayNow = computed(() => (replayRows.value.length ? replayRows.value[replayRows.value.length - 1] : null))
/** 业务失败率 = **error only** ÷ 本拍事件数。⚠️ warn / protect 都不在分子里。 */
const replayFailText = computed(() => (replayNow.value && replayNow.value.failRate !== null
  ? fmt1(replayNow.value.failRate * 100)
  : '—'))
/** 警告率 = warn ÷ 本拍事件数（降级 / 重试中，单列展示，不进失败率）。 */
const replayWarnText = computed(() => (replayNow.value && replayNow.value.warnRate !== null
  ? fmt1(replayNow.value.warnRate * 100)
  : '—'))
/** 防护拦截率 = protect ÷ 本拍事件数（只展示，不进 failRate / 健康度）。 */
const replayProtectText = computed(() => (replayNow.value && replayNow.value.protectRate !== null
  ? fmt1(replayNow.value.protectRate * 100)
  : '—'))
const replayP99Text = computed(() => (replayNow.value && replayNow.value.p99Ms !== null
  ? fmtInt(Math.round(replayNow.value.p99Ms))
  : '—'))
/** 回放时钟（带毫秒）：与横幅同一口径，切页时三页读到同一个值。 */
const replayClockText = computed(() => {
  const ms = replaySession.clockTs
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`
})
const replaySpanText = computed(() => {
  const sec = (replaySession.spanMs || 0) / 1000
  return `${sec.toFixed(sec >= 10 ? 0 : 1)} 秒`
})

/** 窗口内出现过的类别（按声明序 → 颜色稳定；全 0 的类别不上图）。 */
const replayKindsPresent = computed(() => KINDS.filter((k) => replayRows.value.some(
  (r) => num(r.byKind && r.byKind[k]) > 0
)))
/** 堆叠 QPS：窗口长度 1 秒，所以「本拍条数」就是「条/秒」。 */
const replayQpsSeries = computed(() => replayKindsPresent.value.map((k) => ({
  name: KIND_LABEL[k] || k,
  data: replayRows.value.map((r) => num(r.byKind && r.byKind[k])),
  colorIndex: KINDS.indexOf(k) + 1,
  stack: 'rp-kind',
})))
const replayFailSeries = computed(() => [
  {
    name: '业务失败率',
    data: replayRows.value.map((r) => (r.failRate === null ? null : r.failRate * 100)),
    colorIndex: 7, // --c7 红
    area: true,
  },
  {
    name: '警告率',
    data: replayRows.value.map((r) => (r.warnRate === null ? null : r.warnRate * 100)),
    colorIndex: 4, // --c4 黄（降级 / 重试中）
    dashed: true,
  },
  {
    name: '防护拦截率',
    data: replayRows.value.map((r) => (r.protectRate === null ? null : r.protectRate * 100)),
    colorIndex: 5, // --c5 紫
    dashed: true,
  },
])
const replayLatencySeries = computed(() => [
  {
    name: '均值',
    data: replayRows.value.map((r) => r.meanMs),
    colorIndex: 1,
    area: true,
  },
  {
    name: 'P99',
    data: replayRows.value.map((r) => r.p99Ms),
    colorIndex: 4,
    dashed: true,
  },
])

/** 类别分布：窗口累计条数（不是累计 QPS），按条数倒序；宽度按最大项归一。 */
const replayKindRows = computed(() => {
  const totals = KINDS.map((k) => ({
    key: k,
    label: KIND_LABEL[k] || k,
    color: `var(--c${KINDS.indexOf(k) + 1})`,
    n: replayRows.value.reduce((a, r) => a + num(r.byKind && r.byKind[k]), 0),
  })).filter((k) => k.n > 0).sort((a, b) => b.n - a.n)
  const max = totals.length ? totals[0].n : 0
  const all = totals.reduce((a, k) => a + k.n, 0)
  return totals.map((k) => ({
    ...k,
    pct: max ? Math.max(2, Math.round((k.n / max) * 100)) : 0,
    share: all ? ((k.n / all) * 100).toFixed(1) : '0.0',
  }))
})

/* ④ 横向柱：复用 BarChart（`horizontal`），类别与条数都来自同一份 `replayKindRows`。 */
const replayKindDistLabels = computed(() => replayKindRows.value.map((k) => k.label))
const replayKindDistSeries = computed(() => [{
  name: '窗口累计',
  data: replayKindRows.value.map((k) => k.n),
  colorIndex: 1,
}])
function replayKindDistTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const k = replayKindRows.value[i]
  if (!k) return ''
  return `<b>${k.label}</b><br/>当前窗口累计 ${fmtInt(k.n)} 条（占 ${k.share}%）`
}

/* ⑤ 热力图：行 = 窗口内出现过的事件类别，列 = 回放指标拍（日志时间）。
   ⚠️ 数据源全部是台账里真实存在的 `Kind` 与 `OccurredAt` 落点 —— 不拿锁等待字段伪造。 */
const replayHeatKinds = computed(() => KINDS.filter((k) => replayRows.value.some(
  (r) => num(r.byKind && r.byKind[k]) > 0
)))
const replayHeatLabels = computed(() => replayHeatKinds.value.map((k) => KIND_LABEL[k] || k))
const replayHeatData = computed(() => {
  const ys = replayHeatKinds.value
  const out = []
  replayRows.value.forEach((r, x) => {
    ys.forEach((k, y) => {
      const v = num(r.byKind && r.byKind[k])
      if (v > 0) out.push([x, y, v])
    })
  })
  return out
})
/** 60 列时每 10 拍一个刻度，避免标签挤成一团（与实时页热力图同款规则）。 */
const replayHeatInterval = computed(() => (replayRows.value.length > 30 ? 9 : 0))
function replayHeatTooltip(p) {
  const v = p && p.value
  if (!v) return ''
  const [x, y] = v
  const k = replayHeatKinds.value[y]
  return `<b>${replayXLabels.value[x] || ''}</b><br/>${KIND_LABEL[k] || k || ''}：${fmtInt(v[2])} 条`
}

function replayQpsTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = replayRows.value[i]
  if (!r) return ''
  const parts = KINDS
    .filter((k) => num(r.byKind && r.byKind[k]) > 0)
    .map((k) => `${KIND_LABEL[k] || k} ${fmtInt(r.byKind[k])}`)
    .join(' · ')
  return `<b>${replayXLabels.value[i]}</b><br/>事件 ${fmtInt(r.count)} 条（${fmt1(r.qps)} 条/秒）`
    + `<br/>${parts || '本秒没有事件'}`
}
function replayFailTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = replayRows.value[i]
  if (!r) return ''
  return `<b>${replayXLabels.value[i]}</b><br/>事件 ${fmtInt(r.count)} 条`
    + `<br/>error ${fmtInt(r.errors)} = fail ${fmtInt(r.fails)}（只数红色）`
    + `<br/>业务失败率 ${r.failRate === null ? '—' : fmt1(r.failRate * 100) + '%'}`
    + `<br/>warn ${fmtInt(r.warns)} · 警告率 ${r.warnRate === null ? '—' : fmt1(r.warnRate * 100) + '%'}（不计入 fail）`
    + `<br/>防护拦截 ${fmtInt(r.protects)} · protect 率 ${r.protectRate === null ? '—' : fmt1(r.protectRate * 100) + '%'}（不计入 fail）`
}
function replayLatencyTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = replayRows.value[i]
  if (!r) return ''
  return `<b>${replayXLabels.value[i]}</b><br/>均值 ${r.meanMs === null ? '本拍无 DurationUs 样本' : fmt1(r.meanMs) + ' ms'}`
    + `<br/>P99 ${r.p99Ms === null ? '—' : fmt1(r.p99Ms) + ' ms'} · 样本 ${fmtInt(r.latencyCount)} 条`
}

/* ── 共用的 x 轴与各图序列 ────────────────────────────────────────────────── */
/**
 * 共用 x 轴：有拍时逐拍取时间；**一拍都没有时仍铺满 WINDOW 个秒刻度**。
 * ⚠️ 这是「图表不能变白板」的最后一道保底：加载/空态浮层是透明胶囊（`empty-overlay`），
 *    坐标轴与网格线必须始终在画布上，不能因为 rows 为空就整块塌掉。
 */
const xLabels = computed(() => {
  if (rows.value.length) return rows.value.map((r) => hms(r.ts))
  const now = Date.now()
  const out = []
  for (let i = WINDOW - 1; i >= 0; i -= 1) out.push(hms(now - i * 1000))
  return out
})

/** `count` / `errors4xx` / `errors5xx` 是每拍计数；堆叠 QPS 统一换算成「每秒」。 */
const qps4xxSeries = computed(() => rows.value.map((r) => (r.err4xx / r.intervalSec)))
const qps5xxSeries = computed(() => rows.value.map((r) => (r.err5xx / r.intervalSec)))
const qpsOkSeries = computed(() => rows.value.map((r, i) => Math.max(
  0, r.qps - qps4xxSeries.value[i] - qps5xxSeries.value[i]
)))
const qpsTotalSeries = computed(() => rows.value.map((r) => r.qps))
const qpsMarkPoints = computed(() => markPointsOf(qpsTotalSeries.value, fmt1, ' 次/秒'))
const qpsChartSeries = computed(() => [
  { name: '成功', data: qpsOkSeries.value, colorIndex: 3, stack: 'qps' },
  { name: '4xx', data: qps4xxSeries.value, colorIndex: 4, stack: 'qps' },
  { name: '5xx', data: qps5xxSeries.value, colorIndex: 7, stack: 'qps', markPoints: qpsMarkPoints.value },
])

const hitBarSeries = computed(() => rows.value.map((r) => r.hit))
const missBarSeries = computed(() => rows.value.map((r) => r.miss))
/** 命中率转成 0–100；`null` 原样保留 —— ECharts 画成断点，不能连成 0。 */
const hitRateSeries = computed(() => rows.value.map((r) => (r.hitRate === null ? null : r.hitRate * 100)))
const hitMarkPoints = computed(() => markPointsOf(hitRateSeries.value, fmt1, '%'))
const hitChartSeries = computed(() => [
  { name: '命中', data: hitBarSeries.value, type: 'bar', colorIndex: 3, barMaxWidth: 5, stack: 'cache' },
  { name: '未命中', data: missBarSeries.value, type: 'bar', colorIndex: 4, barMaxWidth: 5, stack: 'cache' },
  {
    name: '命中率', data: hitRateSeries.value, type: 'line', axis: 'right',
    colorIndex: 1, dashed: true, markPoints: hitMarkPoints.value,
  },
])

const meanSeries = computed(() => rows.value.map((r) => r.meanMs))
const meanMarkPoints = computed(() => markPointsOf(meanSeries.value, (v) => fmtInt(Math.round(v)), ' ms'))
const meanChartSeries = computed(() => [
  { name: '平均延迟', data: meanSeries.value, area: true, colorIndex: 1, markPoints: meanMarkPoints.value },
])

/** 行 = 窗口内出现过的锁名；某锁 60 拍没动静就从行里退出。 */
const lockNames = computed(() => {
  const seen = []
  for (const r of rows.value) {
    for (const k of Object.keys(r.locks)) {
      if (!seen.includes(k)) seen.push(k)
    }
  }
  return seen
})
/**
 * 锁名 → 中文短名。**只用于显示**：`lockNames` 仍是后端原键，`heatData` / tooltip 取数一律
 * 走原键（改数据键会把图打空）。
 * ⚠️ 为什么需要它：五图共享 `grid-left=64`，y 轴文字可用宽度只有 56px，`order-add` 会被
 *    `overflow:'truncate'` 切成 `order-a...`（2026-10-17 用户实测）。中文三字 ≈36px，放得下；
 *    原键仍留在图例的 `title` 与 tooltip 里，`代码 ↔ 界面` 的对应关系不丢。
 * ⚠️ 表里没有的键原样显示（后端将来加锁时不会变成空白）。
 * ⚠️ `stock-deduct` / `coupon-claim` 只出现在「模拟日常」的假指标帧里（`api/demo-stream.js`
 *    的 metrics 段），后端真锁枚举里没有它们；一并给中文短名，免得演示模式下 y 轴又出现截断。
 */
const LOCK_LABELS = {
  'order-add': '下单锁',
  'order-pay': '支付锁',
  'order-receive': '收货锁',
  'order-delivery': '发货锁',
  'friend-add': '好友锁',
  'stock-deduct': '扣库存锁',
  'coupon-claim': '领券锁',
}
const lockLabel = (name) => LOCK_LABELS[name] || name
/** 热力图 y 轴用中文短名；顺序与 `lockNames` 一一对应（`heatData` 的 y 下标靠这个顺序）。 */
const lockAxisLabels = computed(() => lockNames.value.map(lockLabel))
const waitChartSeries = computed(() => lockNames.value.map((name, i) => ({
  name,
  data: rows.value.map((r) => (r.locks[name] ? r.locks[name].waitMeanMs : null)),
  colorIndex: (i % 10) + 1,
})))
const waitMarkPoints = computed(() => {
  if (!waitChartSeries.value.length) return []
  return globalMarkPoints(
    waitChartSeries.value.filter((s) => s.data.some((v) => v !== null && v !== undefined)),
    (v) => fmt1(v), ' ms',
  )
})
/** 峰值/谷值标注只挂到第一条序列上（标注的坐标可以落在别条线的值上）。 */
const waitSeriesWithMarks = computed(() => waitChartSeries.value.map((s, i) => (
  i === 0 ? { ...s, markPoints: waitMarkPoints.value } : s
)))

const heatData = computed(() => {
  const names = lockNames.value
  const out = []
  rows.value.forEach((r, x) => {
    names.forEach((n, y) => {
      const l = r.locks[n]
      if (l !== undefined) out.push([x, y, l.timeouts])
    })
  })
  return out
})
const heatInterval = computed(() => Math.max(0, Math.ceil(Math.max(1, rows.value.length) / 10) - 1))

/* ── 峰值 / 谷值标注 ──────────────────────────────────────────────────────── */
function markPointsOf(values, fmt, unit) {
  const pts = []
  for (let i = 0; i < values.length; i++) {
    const v = values[i]
    if (v !== null && v !== undefined && Number.isFinite(Number(v))) pts.push({ i, v: Number(v) })
  }
  if (!pts.length) return []
  let max = pts[0]
  let min = pts[0]
  for (const p of pts) {
    if (p.v > max.v) max = p
    if (p.v < min.v) min = p
  }
  if (max.v === min.v) {
    return [{ x: xLabels.value[max.i], y: max.v, text: `持平 ${fmt(max.v)}${unit}`, position: 'top' }]
  }
  return [
    { x: xLabels.value[max.i], y: max.v, text: `峰 ${fmt(max.v)}${unit}`, position: 'top' },
    // 谷值落在 0 时 `bottom` 会掉出绘图区（QPS 第一拍就是 0）⇒ 改标在 0 线上方。
    { x: xLabels.value[min.i], y: min.v, text: `谷 ${fmt(min.v)}${unit}`, position: min.v === 0 ? 'top' : 'bottom' },
  ]
}

/** 多序列（锁等待）里找全局峰/谷；文字带锁名，避免读者以为那是第一条线的点。 */
function globalMarkPoints(series, fmt, unit) {
  const pts = []
  for (const s of series) {
    s.data.forEach((v, i) => {
      if (v !== null && v !== undefined && Number.isFinite(Number(v))) {
        pts.push({ i, v: Number(v), name: s.name })
      }
    })
  }
  if (!pts.length) return []
  let max = pts[0]
  let min = pts[0]
  for (const p of pts) {
    if (p.v > max.v) max = p
    if (p.v < min.v) min = p
  }
  if (max.v === min.v) {
    return [{ x: xLabels.value[max.i], y: max.v, text: `持平 ${fmt(max.v)}${unit}`, position: 'top' }]
  }
  return [
    { x: xLabels.value[max.i], y: max.v, text: `峰 ${max.name} ${fmt(max.v)}${unit}`, position: 'top' },
    { x: xLabels.value[min.i], y: min.v, text: `谷 ${min.name} ${fmt(min.v)}${unit}`, position: min.v === 0 ? 'top' : 'bottom' },
  ]
}

/* ── tooltip ──────────────────────────────────────────────────────────────── */
function qpsTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  return `<b>${xLabels.value[i]}</b><br/>成功 ${fmt1(qpsOkSeries.value[i])} 次/秒`
    + `<br/>4xx ${fmt1(qps4xxSeries.value[i])} 次/秒 · 5xx ${fmt1(qps5xxSeries.value[i])} 次/秒`
    + `<br/>本拍请求 ${fmtInt(r.count)} 次`
}
function hitTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  return `<b>${xLabels.value[i]}</b><br/>命中 ${fmtInt(r.hit)} · 未命中 ${fmtInt(r.miss)}`
    + `<br/>命中率 ${r.hitRate === null ? '—' : fmt1(r.hitRate * 100) + '%'}`
}
function meanTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  return `<b>${xLabels.value[i]}</b><br/>平均延迟 ${r.meanMs === null ? '本拍无延迟样本' : fmt1(r.meanMs) + ' ms'}`
}
function waitTooltip(params) {
  const i = params && params[0] ? params[0].dataIndex : -1
  const r = rows.value[i]
  if (!r) return ''
  const parts = []
  for (const [name, l] of Object.entries(r.locks)) {
    if (l.waitMeanMs === null || l.waitMeanMs === undefined) continue
    parts.push(`${esc(lockLabel(name))}（${esc(name)}） ${fmt1(l.waitMeanMs)} ms（超时 ${fmtInt(l.timeouts)} 次）`)
  }
  return `<b>${xLabels.value[i]}</b><br/>${parts.length ? parts.join('<br/>') : '本拍无锁等待样本'}`
}
function heatTooltip(p) {
  const v = p && (Array.isArray(p.value) ? p.value : p.data)
  if (!v) return ''
  const x = Number(v[0])
  const y = Number(v[1])
  const val = Number(v[2])
  const name = lockNames.value[y] || ''
  const r = rows.value[x]
  const l = r && r.locks[name]
  let s = `<b>${esc(lockLabel(name))}</b>（${esc(name)}） · 第 ${x + 1} 拍`
    + `${xLabels.value[x] ? '（' + xLabels.value[x] + '）' : ''} · 超时 ${fmtInt(val)} 次`
  if (l) {
    s += `<br/>取锁 ${fmtInt(l.acquires)} · 成功 ${fmtInt(l.acquired)}`
      + ` · 降级 ${fmtInt(l.degraded)} · 错误 ${fmtInt(l.errors)}`
  }
  return s
}

/* ── 微条（K1 QPS / K3 命中率）───────────────────────────────────────────── */
function sparkBars(values, bins = 20) {
  const arr = values.slice(-bins)
  const nums = arr.filter((v) => v !== null && v !== undefined && Number.isFinite(Number(v)))
  const max = Math.max(1, ...nums.map(Number))
  return arr.map((v) => {
    if (v === null || v === undefined || !Number.isFinite(Number(v))) return { h: 0, off: true }
    return { h: Math.max(4, Math.round((Number(v) / max) * 100)), off: false }
  })
}
const qpsSpark = computed(() => sparkBars(qpsTotalSeries.value))
const hitSpark = computed(() => sparkBars(hitRateSeries.value))

/* ── 设施 3 类 ────────────────────────────────────────────────────────────── */
const facilities = computed(() => {
  const l2 = latest.value ? latest.value.l2Up : null
  const l2Deg = latest.value ? latest.value.l2Degraded : null
  const lock = latest.value ? latest.value.lockUp : null
  const db = latest.value ? !!(latest.value.stock && latest.value.stock.available) : null

  let l2s
  if (l2 === null || l2 === undefined) l2s = { text: '未上报', tone: 'unknown' }
  else if (!l2) l2s = { text: '不可用', tone: 'bad' }
  else if (l2Deg) l2s = { text: '降级', tone: 'warn' }
  else l2s = { text: '正常', tone: 'ok' }

  let locks
  if (lock === null || lock === undefined) locks = { text: '未上报', tone: 'unknown' }
  else if (lock) locks = { text: '正常', tone: 'ok' }
  else locks = { text: '不可用（fail-open）', tone: 'bad' }

  let dbs
  if (db === null) dbs = { text: '未上报', tone: 'unknown' }
  else if (db) dbs = { text: '正常', tone: 'ok' }
  else dbs = { text: '查询失败', tone: 'bad' }

  return [
    { key: 'l2', label: 'L2 缓存', icon: Database, color: 'var(--c6)', ...l2s },
    { key: 'lock', label: '锁客户端', icon: Lock, color: 'var(--c2)', ...locks },
    { key: 'db', label: '商品库', icon: Server, color: 'var(--c4)', ...dbs },
  ]
})

/* ── 严重度分布（health.level 的 60 拍计数）──────────────────────────────── */
const sevCounts = computed(() => {
  const c = { ok: 0, warn: 0, error: 0 }
  for (const r of rows.value) {
    if (c[r.health] !== undefined) c[r.health] += 1
  }
  return c
})
const sevTotal = computed(() => Math.max(1, rows.value.length))
function sevPct(key) {
  if (!rows.value.length) return '—'
  return `${Math.round((sevCounts.value[key] / sevTotal.value) * 100)}%`
}

/* ── 防护 12 类（前 8 业务环 + 后 4 防护开关；行数以后端帧为准）────────────── */
const protRows = computed(() => (latest.value && latest.value.protections) || [])
const protMax = computed(() => Math.max(1, ...protRows.value.map((p) => num(p.value))))
function protPct(v) {
  return Math.max(2, Math.min(100, (num(v) / protMax.value) * 100)) + '%'
}

/* ── 库存水位 ─────────────────────────────────────────────────────────────── */
const stockAvailable = computed(() => !!(latest.value && latest.value.stock && latest.value.stock.available))
const stockRows = computed(() => ((latest.value && latest.value.stock && latest.value.stock.rows) || []))
/** 基线缺失的兜底分母：本拍返回的这 20 条里**当前库存**的最大值。
 *  没有基线 ≠ 水位不可画；用当前库存最高值归一，条宽仍有相对含义（最低的短、批内最高的满）。
 *  只在 `BaselineStock` 缺失的行上生效，有基线的行仍按真基线算。 */
const stockFallbackMax = computed(() => {
  let max = 0
  for (const r of stockRows.value) {
    const v = Number(r.stock)
    if (Number.isFinite(v) && v > max) max = v
  }
  return max
})
const stockFallbackAny = computed(() => stockRows.value.some((r) => stockFallback(r)))
function stockFallback(r) {
  return r.baseline === null || r.baseline === undefined
}
/** 本行实际用于算水位的分母：真基线优先，缺失时落本批当前库存最高值。 */
function effectiveBaseline(r) {
  return stockFallback(r) ? stockFallbackMax.value : Number(r.baseline)
}
function effectiveRatio(r) {
  if (Number(r.stock) <= 0) return 0
  const b = effectiveBaseline(r)
  if (!Number.isFinite(b) || b <= 0) return null
  return Number(r.stock) / b
}
const stockFiltered = computed(() => {
  const q = stockQuery.value.trim().toLowerCase()
  return stockRows.value.filter((r) => {
    if (stockFilter.value !== 'all' && stockStatus(r) !== stockFilter.value) return false
    if (!q) return true
    return [r.goodName, r.sku, r.axisName]
      .some((x) => String(x || '').toLowerCase().includes(q))
  })
})

/** 显示值：由 rAF 从上一帧推进到目标 stock；无动画偏好时直接落位。 */
const displayStock = reactive({})
function animateStock(list) {
  const from = {}
  const to = {}
  for (const r of list) {
    const key = String(r.sku)
    const targetValue = r.stock === null || r.stock === undefined ? 0 : Number(r.stock)
    if (!Number.isFinite(targetValue)) continue
    const cur = displayStock[key]
    from[key] = Number.isFinite(Number(cur)) ? Number(cur) : 0
    to[key] = targetValue
    if (cur === undefined) displayStock[key] = 0
  }
  if (reduceMotion) {
    for (const k of Object.keys(to)) displayStock[k] = to[k]
    return
  }
  if (stockRaf) cancelAnimationFrame(stockRaf)
  const t0 = performance.now()
  const step = (now) => {
    const p = Math.min(1, (now - t0) / STOCK_TWEEN_MS)
    const e = 1 - Math.pow(1 - p, 3)          // easeOutCubic：起步快、收尾稳
    for (const k of Object.keys(to)) {
      displayStock[k] = from[k] + (to[k] - from[k]) * e
    }
    if (p < 1) {
      stockRaf = requestAnimationFrame(step)
    } else {
      stockRaf = 0
      for (const k of Object.keys(to)) displayStock[k] = to[k]
    }
  }
  stockRaf = requestAnimationFrame(step)
}
function displayOf(r) {
  const v = displayStock[String(r.sku)]
  return Number.isFinite(Number(v)) ? Number(v) : num(r.stock)
}
function stockStatus(r) {
  // 库存为 0 是**观测到的事实**，优先于「没有基线算不出比例」：真库现在就有
  // `stock:0, baseline:null` 的 SKU，不能因为未设基线就把它归进「未测」。
  if (Number(r.stock) <= 0) return 'out'
  // 后端 ratio 只在「库存 + 基线都是数」时给值；基线缺失时用本批当前库存最高值兜底，
  // 所以这里不再把 baseline:null 一律打成「未测」（那会让整列灰斜纹、页面像坏了）。
  const ratio = (r.ratio === null || r.ratio === undefined) ? effectiveRatio(r) : Number(r.ratio)
  if (ratio === null || !Number.isFinite(ratio)) return 'unknown'
  return ratio < 0.5 ? 'low' : 'ok'
}
function stockStatusLabel(r) {
  return { ok: '充足', low: '偏低', out: '缺货', unknown: '未测' }[stockStatus(r)]
}
function barWidth(r) {
  // 0 库存是确定的「缺货」，哪怕没有基线也不该画成满条。
  if (Number(r.stock) <= 0) return '2%'
  // 基线缺失时用本批当前库存最高值兜底；分母 <= 0（整批都是 0，理论上已被上一行拦掉）
  // 才退回 2% —— 不再画「永远 100%」的假水位条。
  const b = effectiveBaseline(r)
  if (!Number.isFinite(b) || b <= 0) return '2%'
  const pct = Math.max(0, Math.min(100, (displayOf(r) / b) * 100))
  return Math.max(2, pct) + '%'
}
function barTone(r) {
  const s = stockStatus(r)
  if (s === 'unknown') return 'is-unknown'
  if (s === 'out' || s === 'low') return 'is-low'
  return 'is-ok'
}
function stockTitle(r) {
  const b = effectiveBaseline(r)
  const pct = effectiveRatio(r)
  const pctText = pct === null ? '—' : (pct * 100).toFixed(1) + '%'
  if (stockFallback(r)) {
    return `基线缺失，按当前库存最高值 ${fmtInt(b)} 兜底 · 库存 ${fmtInt(r.stock)} / ${fmtInt(b)} · 水位 ${pctText}`
  }
  return `水位 ${pctText} · 库存 ${fmtInt(r.stock)} / 基线 ${fmtInt(b)}`
}

/* ── KPI 数字滚动 ─────────────────────────────────────────────────────────── */
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

/* ── 收帧 ─────────────────────────────────────────────────────────────────── */
/**
 * 一帧（流帧或历史帧，形状逐字相同）→ 窗口里的一行。
 * ⚠️ 抽出来是因为**首屏历史**（`loadHistory`）与 SSE 必须走同一条解析：
 *    两份实现迟早在某个派生量上漂（那正是本仓反复吃的亏）。
 */
function toRow(f) {
  const req = f.requests || {}
  const cache = f.cache || {}
  const intervalMs = num(f.intervalMs) || 1000
  const intervalSec = Math.max(0.001, intervalMs / 1000)
  const locks = Array.isArray(f.locks) ? f.locks : []

  const lockMap = {}
  let lockTimeouts = 0
  for (const l of locks) {
    const name = String(l.lock)
    const row = {
      acquires: num(l.acquires),
      acquired: num(l.acquired),
      timeouts: num(l.timeouts),
      degraded: num(l.degraded),
      errors: num(l.errors),
      waitMeanMs: l.waitMeanMs === null || l.waitMeanMs === undefined ? null : num(l.waitMeanMs),
    }
    lockMap[name] = row
    lockTimeouts += row.timeouts
  }

  const count = num(req.count)
  const qps = req.qps === null || req.qps === undefined ? count / intervalSec : num(req.qps)
  return {
    ts: num(f.ts) || Date.now(),
    seq: num(f.seq),
    intervalSec,
    count,
    qps,
    err4xx: num(req.errors4xx),
    err5xx: num(req.errors5xx),
    meanMs: req.meanMs === null || req.meanMs === undefined ? null : num(req.meanMs),
    p99: req.p99Ms === null || req.p99Ms === undefined ? null : num(req.p99Ms),
    hit: num(cache.hit),
    miss: num(cache.miss),
    hitRate: cache.hitRate === null || cache.hitRate === undefined ? null : num(cache.hitRate),
    health: (f.health && f.health.level) || 'ok',
    locks: lockMap,
    lockTimeouts,
  }
}

/** 「最近一帧」那组非时间序列的派生量（KPI 卡 / 健康度 / 库存 / 防护）。 */
function applyLatest(f) {
  const req = f.requests || {}
  const cache = f.cache || {}
  const gauge = f.gauges || {}
  latest.value = {
    seq: num(f.seq),
    ts: num(f.ts) || Date.now(),
    health: f.health || null,
    protections: f.protections || [],
    stock: f.stock || null,
    errorRate: req.errorRate === undefined ? null : req.errorRate,
    clientErrorRate: req.clientErrorRate === undefined ? null : req.clientErrorRate,
    l2Up: gauge.l2Up === undefined ? null : gauge.l2Up,
    lockUp: gauge.lockUp === undefined ? null : gauge.lockUp,
    l2Degraded: cache.l2Degraded === undefined ? null : cache.l2Degraded,
    // A4 / D3-2：死信由后端 `MetricsSnapshotService.dlq()` 汇总
    // （计数器 ⇒ `{total, delta, persisted}`）。⚠️ 旧后端没有这个键 ⇒ 保留 null（页面显示 `—`），
    // **不要**补 `{total:0, delta:0}` —— 「后端没给」与「一条都没有」是两件事。
    // `persisted` 为 null 同样表示「副本表读不到 / 这个后端没有副本」，不是 0 条。
    dlq: f.dlq || null,
  }
}

function onFrame(f) {
  const row = toRow(f)
  // ⚠️ 每条连接的**首帧是差分预热拍**（`intervalMs=0`、全部差分量 0，见
  //    `MetricsSnapshotService.Session#frame`）：把它画进窗口会在首屏历史之后插一根
  //    假的 0 尖峰。跳过这一行，下一拍就是真增量（上一拍读数已被这一帧记住）。
  if (num(f.intervalMs) > 0) {
    rows.value.push(row)
    if (rows.value.length > WINDOW) rows.value.shift()
  }

  applyLatest(f)
  if (pageActive.value) {
    animateStock((f.stock && f.stock.rows) || [])
    if (num(f.intervalMs) > 0) {
      tweenTo({
        qps: row.qps,
        mean: row.meanMs === null ? 0 : row.meanMs,
        hitRate: row.hitRate === null ? 0 : row.hitRate,
        lockWait: row.lockTimeouts,
      })
    }
  }
}

/**
 * 首屏历史（块 7 · 2026-10-17 新增）：拿服务端最近 60 拍已算好的帧，铺到实时帧**前面**。
 *
 * ⚠️ 只在「正常」源加载；切到模拟日常时历史必须清掉（否则真流历史会混进演示画面）。
 * ⚠️ 只填**比现有第一行更早**的那一段：历史请求与 SSE 首帧是并发发出的，回来的顺序不定；
 *    用 `ts` 截断就不会把同一拍画两次。请求失败**不报错**：历史是增强项，实时流照常。
 */
let historyToken = 0
async function loadHistory() {
  const token = ++historyToken
  try {
    const data = await metricsHistory(WINDOW)
    // 期间用户切了数据源 / 又发了一次历史请求 ⇒ 这次结果作废。
    if (token !== historyToken || demo.on) return
    const list = (data && data.list) || []
    // 预热拍（intervalMs=0）不是真实的一拍 —— 与 `onFrame` 同一条过滤。
    const hist = list.filter((f) => num(f.intervalMs) > 0).map(toRow)
    if (!hist.length) return
    const cutoff = rows.value.length ? rows.value[0].ts : Infinity
    const older = hist.filter((r) => r.ts < cutoff)
    if (older.length) rows.value = older.concat(rows.value).slice(-WINDOW)
    // 实时帧还没到时，用历史最后一拍兜住 KPI / 健康 / 库存（已有实时值则不动）。
    if (!latest.value) applyLatest(list[list.length - 1])
  } catch (e) {
    // 历史读失败不改变页面状态：SSE 照常推，页面上只是少了首屏的那 60 拍。
    // ⚠️ 但要在 console 留一条 —— 否则「为什么首屏还是 0」只能靠猜。
    console.warn('[metrics] 首屏历史读取失败（实时流不受影响）：', e && (e.message || e))
  }
}

/* ── 连接 ─────────────────────────────────────────────────────────────────── */
function connect() {
  // 回放期间不建实时流：`connect()` 还会被「重新连接」、切数据源、退出回放的 watch 调到，
  // 任何一条都不该在回放里复活 SSE（用户 2026-10-17：指标页回放期间禁用实时刷新）。
  if (replaySession.active) {
    closeStream()
    return
  }
  closeStream()
  errMsg.value = ''
  lost.value = null
  phase.value = 'connecting'
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
      errMsg.value = s.err ? ((s.err && s.err.message) || '连接中断') : ''
    },
  }
  // 正常源：先把服务端最近 60 拍铺上（首屏不再是 0），再接着收 SSE 增量。
  // ⚠️ 历史与流**并发**发出：谁先回来都不会画重（见 `loadHistory` 里的 `ts` 截断）。
  if (!demo.on) loadHistory()
  stream = demo.on ? openDemoStream('metrics', handlers) : openMetricsStream(handlers)
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
  if (stockRaf) {
    cancelAnimationFrame(stockRaf)
    stockRaf = 0
  }
}

onMounted(connect)
// ⚠️ KeepAlive 失活 ≠ 卸载：流继续收（切回来不重置），但**画面全部停住** ——
//    ECharts 的 `setOption` 由 `BaseChart.vue` 的 onDeactivated 门控，这里停的是页面自己的
//    KPI / 库存补间（它们每帧改 reactive 值，会连带触发五张图的 option 重算）。
onDeactivated(() => {
  pageActive.value = false
  if (rafId) { cancelAnimationFrame(rafId); rafId = 0 }
  if (stockRaf) { cancelAnimationFrame(stockRaf); stockRaf = 0 }
})
onActivated(() => {
  pageActive.value = true
  // 回放期间实时面板整块隐藏（见模板），补间动画也不要空转。
  if (replaySession.active) return
  const lastRow = rows.value[rows.value.length - 1]
  if (lastRow) {
    animateStock((latest.value && latest.value.stock && latest.value.stock.rows) || [])
    tweenTo({
      qps: lastRow.qps,
      mean: lastRow.meanMs === null ? 0 : lastRow.meanMs,
      hitRate: lastRow.hitRate === null ? 0 : lastRow.hitRate,
      lockWait: lastRow.lockTimeouts,
    })
  }
})
// 切数据源 = 换一套世界：窗口必须清空（否则真流历史会混进演示画面），然后再连。
// ⚠️ 路由切换**不会**走到这里 —— 本组件由 `AdminShell.vue` 的 KeepAlive 缓存，
//    切到日志页再回来时流与窗口都原样还在（用户 2026-10-17 报的「切页从 0 开始」）。
watch(() => demo.on, () => {
  historyToken += 1
  rows.value = []
  latest.value = null
  connect()
})
// 回放开关 ⇒ 停 / 恢复实时。
// ⚠️ 进入时清空实时窗口：回放视图读的是 `metricTicks`，留着旧实时行既不可见、也会在
//    退出回放时和历史帧拼出两段不连续的时间轴。退出时同样清空，再让 `loadHistory()`
//    重新铺最近 60 拍 —— 用户 2026-10-17 的「不要白板」由回放视图自己的图表满足，
//    不再是「冻结实时面板 + 遮罩」。
watch(() => replaySession.active, (on) => {
  historyToken += 1
  closeStream()
  errMsg.value = ''
  lost.value = null
  rows.value = []
  latest.value = null
  phase.value = on ? 'stopped' : 'connecting'
  if (on) {
    if (rafId) { cancelAnimationFrame(rafId); rafId = 0 }
    if (stockRaf) { cancelAnimationFrame(stockRaf); stockRaf = 0 }
  } else {
    connect()
  }
})
onBeforeUnmount(closeStream)
</script>

<style scoped>
/* ── 页高：**一屏**（与日志页同一条 `calc(100vh - 顶部高度)`）────────────────
   ⚠️ 2026-10-17 修「双滚动条 + 左右两栏底部不齐」：原来的 `140vh` 让页面本身高于视口
      ⇒ ① 浏览器右侧出现全局长条；② `.mp-body` 里再滚一次；③ 右栏比左栏高出来的部分
      把整页撑长，首屏看不全。现在外层只负责占满一屏并裁掉溢出，滚动**下放给两栏各自**。 */
.a-mp {
  max-width: 1280px;
  height: calc(100vh - 124px);
  min-height: 360px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ── 状态带（与其它可观测页同源，只把字号收进 12/15/19 三档）───────────────── */
.a-status {
  flex: 0 0 auto;
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
  margin-bottom: var(--s3);
}
.a-stat {
  display: flex;
  flex-direction: column;
  min-width: 96px;
}
.a-stat b {
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  line-height: 1.15;
  color: var(--ink);
}
.a-stat span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
/* 死信卡（D3 手验补全）：0 条置灰、>0 红框高亮、读不到（null）虚线中性。
   卡从 div 变成 button ⇒ 必须显式重置浏览器默认的字体/底色/内边距。 */
.a-stat--dlq {
  font: inherit;
  text-align: left;
  background: transparent;
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s2);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}
.a-stat--dlq span em {
  font-style: normal;
  color: var(--info);
}
.a-stat--dlq.is-zero {
  border-color: var(--line);
  opacity: 0.72;
}
.a-stat--dlq.is-alert {
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger) 8%, transparent);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--danger) 35%, transparent);
}
.a-stat--dlq.is-unknown {
  border-style: dashed;
  opacity: 0.66;
}
.a-stat--dlq:hover,
.a-stat--dlq:focus-visible {
  border-color: var(--info);
  opacity: 1;
}
.a-stat--dlq:focus-visible {
  outline: 2px solid var(--info);
  outline-offset: 2px;
}

/* ── 死信详情抽屉 ─────────────────────────────────────────────────────────── */
.a-dlq {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.a-dlq-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  padding: var(--s2) var(--s4);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border-bottom: 1px solid var(--line-2);
  background: var(--surface-2);
}
.a-dlq-tip {
  margin: var(--s3) var(--s4) 0;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.a-dlq-tip.is-bad { color: var(--danger); }
.a-dlq-list {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: var(--s2) var(--s4) var(--s4);
  list-style: none;
  overflow: auto;
}
.a-dlq-cols {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr) 170px 52px;
  gap: var(--s2);
  padding: var(--s2) var(--s3) 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-dlq-cols span:last-child { grid-column: 1 / -1; }
.a-dlq-i {
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  margin-top: var(--s2);
  background: var(--surface);
}
.a-dlq-i.on { border-color: var(--info); }
.a-dlq-row {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr) 170px 52px;
  gap: var(--s2);
  width: 100%;
  font: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  padding: var(--s2) var(--s3);
  cursor: pointer;
}
.a-dlq-row:hover { background: var(--surface-2); }
.a-dlq-time {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}
.a-dlq-trace,
.a-dlq-err {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono, monospace);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.a-dlq-err { color: var(--danger); }
.a-dlq-toggle {
  font-size: var(--fs-xs);
  color: var(--info);
  text-align: right;
}
.a-dlq-text {
  grid-column: 1 / -1;
  font-size: var(--fs-xs);
  color: var(--ink);
  line-height: 1.5;
  word-break: break-all;
}
.a-dlq-raw {
  margin: 0;
  padding: var(--s3);
  border-top: 1px solid var(--line-2);
  background: var(--surface-2);
  font-family: var(--font-mono, monospace);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}
.a-empty {
  padding: var(--s4);
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.a-hv {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
}
.a-hv--ok { color: var(--success-deep); }
.a-hv--warn { color: var(--warning-deep); }
.a-hv--error { color: var(--danger); }
.a-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: currentColor;
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
.a-chip-utc {
  font-family: var(--font-mono);
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
.a-reasons {
  flex: 0 0 auto;
  list-style: none;
  font-size: var(--fs-sm);
  line-height: 1.6;
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  margin-bottom: var(--s3);
}
.a-reasons--ok { color: var(--success-deep); background: var(--success-ground); }
.a-reasons--warn { color: var(--warning-deep); background: var(--warning-ground); }
.a-reasons--error { color: var(--danger); background: var(--danger-ground); }
.a-flash {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--s3);
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s3);
}
.a-flash.bad { color: var(--danger); background: var(--danger-ground); }
/* 服务端正常收尾（`onEnd(null)`）不是故障：给琥珀色提示，别用红条冒充连接错误。 */
.a-flash.warn { color: var(--warning-deep); background: var(--warning-ground); }
.a-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s3);
  cursor: pointer;
  white-space: nowrap;
}
.a-btn:hover { color: var(--ink); background: var(--surface-3); }

/* ── 主体两栏 ─────────────────────────────────────────────────────────────── */
/* 外层 `.mp-body` **不滚**：它只把剩余一屏高度交给网格；两栏各自 `overflow-y:auto`。
   ⚠️ 这样左右两栏的**可视区底边等价**（同一行网格、同一高度），左栏短、右栏长也不会
      把页面撑长；滚动条只出现在各自栏内，不会再有「全局长条 + 内部条」两条。 */
.mp-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.mp-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: var(--s4);
  align-items: stretch;
  /* 用 flex 撑满（不靠 `height:100%`）：父级的“确定高度”来自 flex 分配，百分比高度
     在部分外壳形态下解析不出来（本仓 `ael-height-does-not-resolve` 那条实测的教训）。 */
  flex: 1 1 auto;
  min-height: 0;
}
/* 两栏独立滚动 + 等高。`min-height:0` 是让 flex/grid 子项真能收缩的那一刀；
   没有它内容会把栏撑高，`overflow` 永远轮不到生效。 */
.mp-stack,
.mp-rail {
  min-height: 0;
  overflow-y: auto;
  padding-right: 2px;
}
.mp-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  min-width: 0;
}
.mp-chart {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s2) var(--s3) 0;
  min-width: 0;
}
.mp-chart--last { padding-bottom: var(--s2); }
.mp-chart-h {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  flex-wrap: wrap;
  min-height: 24px;
  padding: 0 var(--s1);
}
.mp-chart-h h3 {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.mp-chart-n {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-legend {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
.mp-legend i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-left: var(--s1);
}
.mp-legend i:first-child { margin-left: 0; }
.lg-ok { background: var(--c3); }
.lg-4xx { background: var(--c4); }
.lg-5xx { background: var(--c7); }
.lg-hit { background: var(--c3); }
.lg-miss { background: var(--c4); }
.lg-rate { background: var(--c1); }
.mp-axis-note {
  margin: var(--s1) var(--s1) 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* ── 右栏 ─────────────────────────────────────────────────────────────────── */
.mp-rail {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}
.mp-rail-sec {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s3);
  min-width: 0;
}
.mp-rail-h {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin: 0 0 var(--s2);
  padding-bottom: var(--s1);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.mp-rail-h span {
  margin-left: auto;
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
  text-align: right;
}

/* 4 张读数卡：K1/K3 挂微条，K2/K4 无序列 */
.mp-kpis {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.mp-kpi {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 64px;
  grid-template-rows: auto auto auto;
  column-gap: var(--s2);
  align-items: center;
  padding: var(--s1) 0;
  border-bottom: 1px solid var(--line);
}
.mp-kpi:last-child { border-bottom: none; }
.mp-kpi-k {
  grid-column: 1;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-kpi-v {
  grid-column: 1;
  display: flex;
  align-items: baseline;
  gap: var(--s1);
}
.mp-kpi-v b {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.mp-kpi-v em {
  font-style: normal;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-kpi-d {
  grid-column: 1;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mp-spark {
  grid-column: 2;
  grid-row: 1 / span 3;
  height: 30px;
  display: flex;
  align-items: flex-end;
  gap: 1px;
}
.mp-spark i {
  flex: 1 1 0;
  min-width: 0;
  background: var(--c1);
  border-radius: 1px 1px 0 0;
}
.mp-spark i.off { background: var(--line-2); opacity: 0.5; }
.mp-kpi:nth-child(3) .mp-spark i { background: var(--c3); }
.mp-kpi-nospark {
  grid-column: 2;
  grid-row: 1 / span 3;
  align-self: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  text-align: center;
}

/* 设施 3 类 */
.mp-facs, .mp-sevs, .mp-prots {
  list-style: none;
  display: flex;
  flex-direction: column;
}
.mp-facs { gap: var(--s2); }
.mp-fac {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
}
.mp-fac-k { color: var(--ink-2); }
.mp-fac-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-left: auto;
  background: var(--ink-3);
}
.mp-fac-dot.is-ok { background: var(--success); }
.mp-fac-dot.is-warn { background: var(--warning); }
.mp-fac-dot.is-bad { background: var(--danger); }
.mp-fac-dot.is-unknown { background: var(--ink-3); }
.mp-fac-s {
  min-width: 92px;
  text-align: right;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* 严重度分布 */
.mp-sev-bar {
  display: flex;
  height: 8px;
  overflow: hidden;
  border-radius: var(--r1);
  background: var(--surface-2);
  margin-bottom: var(--s2);
}
.mp-sev-bar i { min-width: 0; }
.mp-sevs { gap: var(--s1); }
.mp-sevs li {
  display: grid;
  grid-template-columns: 16px 1fr auto 42px;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.mp-sevs b {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}
.mp-sevs em {
  font-style: normal;
  text-align: right;
  color: var(--ink-3);
}

/* 防护 12 类 */
.mp-prots { gap: 5px; }
.mp-prot {
  display: grid;
  grid-template-columns: 118px minmax(0, 1fr) 32px;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
}
.mp-prot-k {
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-width: 0;
  color: var(--ink-2);
}
.mp-prot-k span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mp-prot-track {
  height: 6px;
  background: var(--surface-2);
  border-radius: var(--r1);
  overflow: hidden;
}
.mp-prot-track i {
  display: block;
  height: 100%;
  border-radius: var(--r1);
}
.mp-prot b {
  text-align: right;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}

/* 库存水位 */
.mp-stock-tools {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.mp-stock-search {
  width: 100%;
  box-sizing: border-box;
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s2);
}
.mp-stock-search::placeholder { color: var(--ink-3); }
.mp-stock-search:focus {
  outline: none;
  border-color: var(--brand);
}
.mp-stock-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s1);
}
.mp-chip {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 8px;
  cursor: pointer;
}
.mp-chip.on {
  color: var(--ink);
  border-color: var(--brand);
  background: var(--brand-tint);
}
.mp-stock-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
/* ⚠️ 故意**不再**是 220px 的内嵌滚动区：右栏现在整体滚，内层再滚就是「栏内双滚动条」。
   库存列表按内容自然增高，滚的是 `.mp-rail` 这一根。 */
.mp-stock-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.mp-stock-row {
  flex: 0 0 auto;
  padding-bottom: var(--s1);
  border-bottom: 1px solid var(--line);
}
.mp-stock-top {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
}
.mp-stock-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
  color: var(--ink);
}
.mp-stock-n {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.mp-stock-n b {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--ink);
}
.mp-stock-sub {
  display: flex;
  align-items: center;
  gap: var(--s1);
  min-width: 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mp-stock-sub code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mp-stock-tag {
  margin-left: auto;
  flex: 0 0 auto;
  font-style: normal;
  font-size: var(--fs-xs);
  padding: 0 5px;
  border-radius: var(--r1);
}
.mp-stock-tag.is-ok { color: var(--success); background: var(--success-ground); }
.mp-stock-tag.is-low { color: var(--warning); background: var(--warning-ground); }
.mp-stock-tag.is-out { color: var(--danger); background: var(--danger-ground); }
.mp-stock-tag.is-unknown { color: var(--ink-3); background: var(--surface-2); }
.mp-stock-track {
  height: 7px;
  margin-top: var(--s1);
  background: var(--surface-2);
  border-radius: var(--r1);
  overflow: hidden;
}
.mp-stock-fill {
  height: 100%;
  border-radius: var(--r1);
}
.mp-stock-fill.is-ok {
  background: linear-gradient(90deg, var(--success), var(--success-deep));
}
.mp-stock-fill.is-low {
  background: linear-gradient(90deg, var(--warning), var(--danger));
}
.mp-stock-fill.is-unknown {
  background: repeating-linear-gradient(
    45deg,
    var(--surface-3),
    var(--surface-3) 4px,
    var(--surface-2) 4px,
    var(--surface-2) 8px
  );
}
.mp-empty {
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
  padding: var(--s2) 0;
}

/* 滚动条：7px（≤8px）+ `--line-2` 深色滑块，口径与日志页 `.ev-list` 逐条一致。
   ⚠️ 只写在**真正滚的两个栏**上；`.mp-stock-list` 已不是滚动容器。
   ⚠️ **绝不能**在祖先（`.mp-body` / 本页任何元素）上写 `scrollbar-width` / `scrollbar-color`：
      Chrome ≥121 只要元素上的这两个属性非 `auto`，整段 `::-webkit-scrollbar` 就被**整块忽略**；
      `scrollbar-color` 还会**继承**到 `.mp-stack` / `.mp-rail` ⇒ 实测这两栏退回默认 15px
      （2026-10-17 探针：`offsetWidth-clientWidth = 15`）。本页原来就有一条第 `.mp-body,
      .mp-stock-list` 的 `scrollbar-width: thin`（6px 规则同时被它作废）—— 已删。 */
.mp-stack::-webkit-scrollbar,
.mp-rail::-webkit-scrollbar {
  width: 7px;
  height: 7px;
}
.mp-stack::-webkit-scrollbar-thumb,
.mp-rail::-webkit-scrollbar-thumb {
  background: var(--line-2);
  border-radius: 4px;
}
.mp-stack::-webkit-scrollbar-thumb:hover,
.mp-rail::-webkit-scrollbar-thumb:hover {
  background: var(--ink-3);
}
.mp-stack::-webkit-scrollbar-track,
.mp-rail::-webkit-scrollbar-track {
  background: transparent;
}

/* ── 回放视图（全链路联动 · 2026-10-17）──────────────────────────────────── */
.lg-mean { background: var(--c1); }
.lg-p99 { background: var(--c4); }
.lg-fail { background: var(--c7); }
.lg-warn { background: var(--c4); }
.lg-protect { background: var(--c5); }
/* 业务失败率（左大字，红）+ 警告率（黄）+ 防护拦截率（紫）—— 三个口径并排但绝不相加 */
.a-stat--fail { min-width: 300px; }
.a-stat--fail .rp-fail {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
}
.a-stat--fail .rp-warn {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-style: normal;
  color: var(--warning);
  white-space: nowrap;
}
.a-stat--fail .rp-fail b i {
  font-size: var(--fs-xs);
  font-style: normal;
  color: var(--ink-3);
}
.a-stat--fail .rp-prot {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-style: normal;
  color: var(--protect);
  white-space: nowrap;
}
.a-status--rp { border-color: var(--warning); }
.mp-rp-warn {
  flex: 0 0 auto;
  margin: 0 0 var(--s3);
  padding: var(--s2) var(--s3);
  font-size: var(--fs-sm);
  line-height: 1.65;
  color: var(--warning-deep);
  background: var(--warning-ground);
  border: 1px solid var(--warning);
  border-radius: var(--r2);
}
.mp-rp-warn b { font-weight: var(--fw-semi); }
.mp-rp-kinds {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.mp-rp-kind {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) 56px 48px;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.mp-rp-kind-k {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.mp-rp-kind-k i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex: 0 0 auto;
}
.mp-rp-kind-track {
  height: 8px;
  border-radius: 4px;
  background: var(--surface-2);
  overflow: hidden;
}
.mp-rp-kind-track i {
  display: block;
  height: 100%;
  border-radius: 4px;
  transition: width 160ms linear;
}
.mp-rp-kind b {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}
.mp-rp-kind em {
  text-align: right;
  font-style: normal;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.mp-rp-facts {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-2);
}
.mp-rp-facts b {
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.mp-rp-facts-miss { color: var(--ink-3); }
.mp-rp-note {
  margin: 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-2);
}
.mp-rp-note b { color: var(--ink); }

@media (max-width: 1100px) {
  .a-mp { height: auto; min-height: 0; overflow: visible; }
  .mp-body { overflow: visible; }
  .mp-grid { grid-template-columns: minmax(0, 1fr); height: auto; }
  .mp-stack,
  .mp-rail { overflow: visible; }
  .mp-stock-list { height: 260px; overflow-y: auto; }
}
</style>

<script>
// ⚠️ 这个名字是 `AdminShell.vue` 里 `<keep-alive include="MetricsPanel,EventLog">` 的锚点。
//    为什么不用文件名自动推断：本仓的 Vue 是 **3.2.13**，按文件名推 `__name` 是 3.2.34 才有的。
//    ⇒ 名字写在这里，**别删**；改名必须两处一起改（否则 KeepAlive 静默不生效，
//    表现就是用户报过的「切到日志页再回来，指标又从 0 开始」）。
export default { name: 'MetricsPanel' }
</script>
