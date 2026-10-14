<template>
  <section class="a-el">
    <!-- ── ① 状态条（与 `MetricsPanel.vue` / `OrderFlow.vue` 逐字同源，不另立一套）────────── -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ fmtInt(events.length) }}</b>
        <span>窗口内事件（最多 {{ EVENT_CAP }} 条）</span>
      </div>
      <div class="a-stat">
        <b>{{ fmtRate(rate) }}</b>
        <span>本拍速率（条/秒）</span>
      </div>
      <div class="a-stat">
        <b>{{ fmtInt(traceList.length) }}</b>
        <span>窗口内请求链路（最多 {{ TRACE_CAP }} 次）</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-live">{{ phaseText }}</span>
        <span>第 <b>{{ seq }}</b> 拍 · 每秒 1 拍</span>
        <span class="a-tz-off">{{ lastTickAt }}</span>
        <!-- ⚠️ MQ 桥接的**上屏位置**（用户 2026-10-14 批文；8c 已接通，见 `<script>` 的 `mqConnected`）。
             值来自帧里的 `mq` 键（后端每 5 秒实探一次）⇒ 最多滞后 5 秒，不是「猜」的。
             黄点用 `--warning`：它不是错误（错误红留给严重度），是「这台此刻没把事件交给队列」。 -->
        <span class="a-el-mq" :class="mqConnected ? 'is-on' : 'is-off'">
          <i class="a-el-mqdot" aria-hidden="true"></i>MQ {{ mqConnected ? '已连接' : '未连接' }}
        </span>
      </div>
    </header>

    <p v-if="errMsg" class="a-flash bad">
      实时日志流中断：{{ errMsg }}
      <button type="button" class="a-btn" @click="connect">重新连接</button>
    </p>
    <p v-if="!mqConnected" class="a-flash warn">
      MQ 未连接：八环的事件都只在<b>进程内存</b>里（每环 512 条覆盖式环），<b>不落库</b> —— 这台服务一重启，这些事件就没了。
      <span class="a-el-flashnote">（后端每 5 秒实探一次；连上后本行自动消失，检测中请稍候）</span>
    </p>

    <!-- ══ ② 8 类图例（兼筛选）═══════════════════════════════════════════════════════
         ⚠️ 8 类**永远全在场**（哪怕计数为 0）：图例要回答的是「有哪 8 类」，不是「现在有几类」。
            它们同时也是**筛选器**（点一下把它关掉 / 再点开回来）—— 上方那句 `a-h2n` 写明了。
         ⚠️ 链上的数是**本页自己攒的窗口内累计**，不是帧里那个 `counts`：后端那份是**本拍**的
            （见 `EventStreamService` 类注释「三个汇总都是本拍的」），拿它当图例数字会在每次
            静默的一拍上全体归零。窗口的上限见 `EVENT_CAP`（会覆盖旧的 ⇒ 数是「窗口内」不是「历史总」）。 -->
    <h2 class="a-h2">
      事件分类
      <span class="a-h2n">点一下筛选 · 数字 = 本页窗口内累计（帧里的 counts 是「本拍」的，不用它）</span>
    </h2>
    <div class="a-card a-el-kinds">
      <button
        v-for="k in KINDS"
        :key="k"
        type="button"
        class="a-el-kind"
        :class="{ 'is-off': !activeKinds.has(k) }"
        :style="{ '--el-k': kindColor(k) }"
        :aria-pressed="activeKinds.has(k) ? 'true' : 'false'"
        :title="`${kindLabel(k)}：窗口内 ${sessionByKind[k] || 0} 条（点一下${activeKinds.has(k) ? '关掉' : '打开'}这一类）`"
        @click="toggleKind(k)"
      >
        <svg class="a-el-ic" viewBox="0 0 16 16" aria-hidden="true">
          <path v-for="(d, i) in kindIcon(k)" :key="i" :d="d" />
        </svg>
        <span class="a-el-kindn">{{ kindLabel(k) }}</span>
        <span class="a-el-kc">{{ fmtInt(sessionByKind[k] || 0) }}</span>
      </button>
      <span class="a-el-ksep" aria-hidden="true"></span>
      <button
        v-for="s in SEVERITIES"
        :key="s"
        type="button"
        class="a-el-kind a-el-sevchip"
        :class="{ 'is-off': !activeSevs.has(s) }"
        :style="{ '--el-k': severityColor(s) }"
        :aria-pressed="activeSevs.has(s) ? 'true' : 'false'"
        :title="`严重度 ${severityLabel(s)}：窗口内 ${sessionBySev[s] || 0} 条`"
        @click="toggleSev(s)"
      >
        <i class="a-el-dot" aria-hidden="true"></i>
        <span class="a-el-kindn">{{ severityLabel(s) }}</span>
        <span class="a-el-kc">{{ fmtInt(sessionBySev[s] || 0) }}</span>
      </button>
      <button type="button" class="a-btn a-el-reset" :disabled="isAllOn" @click="resetFilters">全部打开</button>
    </div>

    <!-- ══ ③ 事件卡片流（点卡片 → 这次请求的完整链路）════════════════════════════════
         ⚠️ 排序口径：**按到达批次倒序（新的在上）**，同一拍内部按墙钟 `ts` 倒序、同刻按 `seq` 倒序。
            不做**全局**重排 —— 那会让用户正在看的行在两拍之间跳走（同 `OrderFlow.vue` 的列表口径）。
            诚实的代价：一拍内跨环的事件（同毫秒）在帧里是**按环分组**的（`poll()` 一环一环并），
            卡片的先后因此是「环序」而不是「真时间序」；真时间序只有 `ts` 能还原，故卡片上把它写到毫秒。
         ⚠️ **点卡片开抽屉的判据是「有没有 `traceId`」**，不是「点了没反应」：非请求线程上发生的事件
            （定时任务、SSE 流线程自己）**没有号**，那种卡片不给 `role`/`tabindex`，也不可点。 -->
    <h2 class="a-h2">
      事件流
      <span class="a-h2n">
        窗口内 {{ fmtInt(filtered.length) }} / {{ fmtInt(events.length) }} 条 · 每页 {{ pageSize }} 条 ·
        点一条看「这一次请求的完整链路」（没有 traceId 的不给点）
      </span>
    </h2>
    <div class="a-card">
      <p v-if="!events.length" class="a-empty">
        {{ first ? '还没收到第一拍。' : '本会话还没收到任何事件 —— 这台服务起来之后八个环一直没有写入点被触发。' }}
      </p>
      <p v-else-if="!filtered.length" class="a-empty">
        当前筛选下一条也没有（{{ fmtInt(events.length) }} 条全被上面那排开关挡在外面）。点「全部打开」还原。
      </p>
      <ul v-else class="a-el-list">
        <li
          v-for="ev in pageRows"
          :key="ev.seq"
          class="a-el-row"
          :class="['is-' + ev.severity, { 'is-openable': !!ev.traceId }]"
          :style="{ '--el-k': kindColor(ev.kind), '--el-s': severityColor(ev.severity) }"
          :role="ev.traceId ? 'button' : undefined"
          :tabindex="ev.traceId ? 0 : undefined"
          :title="ev.traceId ? '点开看这一次请求的完整链路' : '这条不在请求线程上（没有 traceId），看不到链路'"
          @click="openTrace(ev)"
          @keydown.enter.prevent="openTrace(ev)"
          @keydown.space.prevent="openTrace(ev)"
        >
          <span class="a-el-bar" aria-hidden="true"></span>
          <svg class="a-el-ic" viewBox="0 0 16 16" aria-hidden="true">
            <path v-for="(d, i) in kindIcon(ev.kind)" :key="i" :d="d" />
          </svg>
          <span class="a-el-sev">{{ severityLabel(ev.severity) }}</span>
          <span class="a-el-t">{{ hmsms(ev.ts) }}</span>
          <span class="a-el-main">
            <b class="a-el-title">{{ ev.title }}</b>
            <span class="a-el-kindn">{{ kindLabel(ev.kind) }}</span>
            <span v-if="objText(ev)" class="a-el-obj">{{ objText(ev) }}</span>
            <span class="a-el-detail">{{ ev.detail }}</span>
          </span>
          <span class="a-el-trace" :class="{ 'is-none': !ev.traceId }">{{ ev.traceId || '无' }}</span>
        </li>
      </ul>

      <Pager
        :total="filtered.length"
        :limit="pageSize"
        :offset="offset"
        :sizes="PAGE_SIZES"
        unit="条"
        label="事件流"
        @change="onPage"
      />
    </div>

    <!-- ══ ④ 这次请求的完整链路（点卡片开）═══════════════════════════════════════════
         ⚠️ 数据源**只有两处**：本页窗口内捕获的**带同一个 traceId 的事件** + 后端帧里那条
            `traces` 记录（请求的起止与总耗时）。**没有**「按 traceId 查历史」的端点，也不该加
            —— 那要么落库（今天的口径正是「不落库」），要么把它做成一个全长连接。
         ⚠️ 起止那两行**推不出**：事件只说「某事发生了」，说不了「请求何时结束」⇒ 后端补了
            `TraceLog` 一对起止（用户 2026-10-14 批文 a1，「加开始/结束事件」）。 -->
    <div v-if="openedTrace" class="a-el-modal" @click.self="closeTrace">
      <div class="a-el-modal-box" role="dialog" aria-modal="true" :aria-label="`请求 ${openedTrace} 的链路`">
        <header class="a-el-modal-hd">
          <b class="a-el-modalt">这一次请求的完整链路</b>
          <span v-if="openedTraceRec" class="a-el-st" :class="statusClass(openedTraceRec.status)">{{ openedTraceRec.status }}</span>
          <button type="button" class="a-btn a-el-close" @click="closeTrace">关闭</button>
        </header>

        <p class="a-el-modal-sub">
          <span class="a-el-kv">traceId</span><code class="a-el-mono">{{ openedTrace }}</code>
          <template v-if="openedTraceRec">
            <span class="a-el-kv">{{ openedTraceRec.method }}</span><code class="a-el-mono">{{ openedTraceRec.path }}</code>
            <span class="a-el-kv">总耗时</span><b>{{ openedTraceRec.ms }} ms</b>
          </template>
        </p>

        <ol class="a-el-chain">
          <!-- 起点：只可能在 `traces` 记录里（事件流里没有「请求开始」这种事件）。 -->
          <li v-if="openedTraceRec" class="a-el-chain-i is-ends">
            <span class="a-el-chain-t">{{ hmsms(openedTraceRec.startMs) }}</span>
            <span class="a-el-chain-d">请求开始（{{ openedTraceRec.method }} {{ openedTraceRec.path }}）</span>
          </li>
          <li v-else class="a-el-chain-i is-miss">
            <span class="a-el-chain-t">—</span>
            <span class="a-el-chain-d">这一次请求的「起止」还没到：它的收尾记录在请求返回时才写（长连接与流线程不记）</span>
          </li>

          <li v-for="ev in openedEvents" :key="ev.seq" class="a-el-chain-i" :style="{ '--el-k': kindColor(ev.kind) }">
            <span class="a-el-chain-t">{{ hmsms(ev.ts) }}</span>
            <span class="a-el-chain-d">
              <svg class="a-el-ic a-el-ic-s" viewBox="0 0 16 16" aria-hidden="true">
                <path v-for="(d, i) in kindIcon(ev.kind)" :key="i" :d="d" />
              </svg>
              <b>{{ kindLabel(ev.kind) }}</b> · {{ ev.title }}<template v-if="objText(ev)">（{{ objText(ev) }}）</template>
            </span>
          </li>
          <li v-if="openedTraceRec && !openedEvents.length" class="a-el-chain-i is-miss">
            <span class="a-el-chain-t">{{ hmsms(openedTraceRec.startMs) }}</span>
            <span class="a-el-chain-d">这一次请求<b>没有产生任何事件</b>（它没碰到八个环中的任何一个）</span>
          </li>

          <!-- 终点：同起点，只在 `traces` 记录里。 -->
          <li v-if="openedTraceRec" class="a-el-chain-i is-ends">
            <span class="a-el-chain-t">{{ hmsms(openedTraceRec.endMs) }}</span>
            <span class="a-el-chain-d">请求结束 · 总耗时 <b>{{ openedTraceRec.ms }} ms</b></span>
          </li>
        </ol>

        <p class="a-el-note-s">
          <template v-if="!openedEvents.length">中间一段是空的 —— 两次可能：这次请求真没碰任何环，或它的事件已经被环形缓冲覆盖（见下方「覆盖与边界」）。</template>
          <template v-else>中间 {{ openedEvents.length }} 条 = 本页窗口内<b>带这个 traceId</b> 的事件，按墙钟升序（链路方向）。更早发生、已被窗口覆盖的不在内。</template>
        </p>
      </div>
    </div>

    <!-- ══ ⑤ 覆盖与边界 ═════════════════════════════════════════════════════════════ -->
    <h2 class="a-h2">覆盖与边界</h2>
    <div class="a-card a-el-note">
      <p>· <b>事件是内存里的、有界且会被覆盖</b>：八个源环各留最近 512 条，合并环再留最近 512 条；本页自己留最近 {{ EVENT_CAP }} 条。⇒ 「窗口内」是本页能看到的全部，<b>不是历史总量</b>。</p>
      <p>· <b>首帧可能很大</b>：冷启动时八个环可能各自攒了一屏，首帧会把合并环留得下的最后 512 条一次送出（页面按 {{ EVENT_CAP }} 条封顶）。之后每拍只送真正新发生的（正常是个位数）。</p>
      <p>· <b>规则表在后端</b>：严重度那三档（info / warn / error）由后端 severityOf 一处判定，本页只负责把 tag 画成人话 —— 前端不重推一遍判据。</p>
      <p>· <b>对象列八类不是同一种东西</b>：锁/缓存/通知取各自的键、订单取订单号、库存取 SKU、审计取对象类型 + #Id、敏感词取<b>命中的词</b>、身份取<b>请求路径</b>（逐类口径见 config/labels/observe-labels.js 的 OBJECT_FIELD_LABEL）。</p>
      <p>· <b>中文标签 commit 9 已全部收敛</b>：本页 + 指标 / 状态流转 / 攻击模拟器四页的中文表**只在** config/labels/ 下那一处（可观测域是本目录的 observe-labels.js）；页面只负责把后端帧里的 tag 画成人话，不重推任何判据。</p>
    </div>
  </section>
</template>

<!--
  管理端「实时日志」页（C3 块 7 · commit 8，2026-10-14）。

  ⚠️ **本笔不是零后端改动**（与 commit 7 相反）：本页要的三样东西后端原本都没有，本笔一并补了 ——
     ① `ObservedEvent` 由 7 字段扩到 10 字段（`objectType` / `objectId` / `traceId`），八处
        `ingestXxx` 逐环给值（用户 2026-10-14 批文 D 项 + 三环补字段）；
     ② 帧里多一条 `traces` 数组（`TraceLog` 请求台账的增量）—— 抽屉中间那两行「起止」就靠它；
     ③ 跨域补挂两处审计（`/goods/goodsDe`、`/comment/delete`）。
     改动面逐文件记在收笔报告里，不在这里重复。

  ⚠️ **数据源 = `POST /admin/observe/events/stream`**（commit 3 就备好的 SSE 流，本笔才第一次有页面消费）：
     一帧 = 本拍增量 `events` + 三个汇总（`counts` 按类 / `severity` 按严重度 / `rate` 速率）
     + 本拍新走完的 `traces`。契约与帧字段见 `api/observe.js` 的 `openEventStream` 注释。

  ⚠️ **页面卸载必须 `close()`**（`onBeforeUnmount`）：SSE 的 `StreamingResponseBody` 在服务端占
     **一条异步线程**，不断开就是一条常驻线程 —— 与 `MetricsPanel.vue` / `OrderFlow.vue` 同一条代价。

  ⚠️ **本页不用 ECharts**：这一页要的是「一屏能读的卡片流」，不是图。三个数字走 `a-stat`，
     8 类/严重度的分布**不用环图**（那是 `MetricsPanel.vue` 的活，它已经画了）——
     同一屏上再画一遍不会多出信息，只会挤掉卡片。⇒ 本笔**没有**新引图表、没有复用 `BaseChart`。
-->

<script setup>
/* eslint-disable no-undef */ /* 理由见 `ui/KpiCard.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { openEventStream } from '../../../api/observe'
import Pager from '../../../components/Pager.vue'
import {
  KINDS, SEVERITIES,
  kindLabel, kindColor, kindIcon,
  severityLabel, severityColor,
  objectFieldLabel,
} from '../../../config/labels/observe-labels'
// commit 9：审计那一类的 `objectType` 是英文域前缀（`goods` / `comment_image`…）⇒ 复用审计域那张表。
import { targetTypeLabel } from '../../../config/labels/audit-labels'

/** 本页自己累积的事件上限。⚠️ 必须有：SSE 每秒推若干条，不封顶跑一晚上就是几万条。 */
const EVENT_CAP = 1000
/** 本页自己累积的 `traces`（请求起止）上限。一次请求一行，比事件稀得多。 */
const TRACE_CAP = 300
/** 可选的每页条数（`Pager` 的入参）。⚠️ 用户 2026-10-14 口径：日志页**建议 100 条/页**
 *  （`Pager.vue` 头注释里举的「审计日志 100」就是这一档），故缺省取 100。 */
const PAGE_SIZES = [50, 100, 200]
const PAGE_DEFAULT = 100

/**
 * MQ 桥接状态 —— **已接通**（块 7 · commit 8c，2026-10-14）。用户批文原话：「commit 8 留的黄点
 * （恒 false）→ 本笔接通（MQ_CONNECTED 从 broker 实探，或消费端心跳）」。
 *
 * 数据源 = 实时日志帧的第 10 个键 `mq`（`EventStreamService.frame()` 里 `publisher.connected()`）。
 * 后端侧的实探点：`RabbitEventPublisher` 有一条 daemon 探针线程，每 5 秒握一次手。它记日志的
 * 口径是「**连上只记翻转**（INFO）+ **断开记两档**（翻转 WARN / 稳态按 `mall.observe.mq.warn-interval-ms`
 * 周期 WARN，默认 5 分钟）」—— 故这里的值最多滞后 5 秒，与后端日志同源、同一个判据。
 * ⚠️ 别把「日志里有几条 WARN」当成「断了多久」：稳态是**每 5 分钟**一条，不是每 5 秒一条。
 *
 * ⚠️ 两处口径必须知道：
 *   ① 它**只用于上屏**，不参与任何业务判定（后端接口注释里写死了这一条）。红/黄点表达的是
 *      「这台服务此刻有没有把事件交给队列」，不是「事件有没有问题」（严重度是另一套配色）。
 *   ② `false` 的含义是「**事件只在进程内存里**（每环 512 条覆盖式环），不落库」—— 这仍然是
 *      **事实描摹**：`RabbitEventPublisher.publish` 在已知连不上时**一次网络都不碰**，直接返回。
 *      所以下面那条 warn 条在 `false` 时说得出口。
 */
const mqConnected = ref(false)

/* ── 页面状态 ───────────────────────────────────────────────────────────────── */
const phase = ref('connecting')            // connecting | live | stopped
const errMsg = ref('')
const seq = ref(0)
const rate = ref(0)
const ts = ref(0)
const events = ref([])                     // 本页窗口内的事件（新的在前；封顶 EVENT_CAP）
const traceList = ref([])                  // 本页窗口内的请求（新的在前；封顶 TRACE_CAP）
const activeKinds = ref(new Set(KINDS))    // 开着的类（空集 = 一条也不显示，是**允许**的状态）
const activeSevs = ref(new Set(SEVERITIES))// 开着的严重度
const openedTrace = ref('')                // 抽屉打开的 traceId
const pageSize = ref(PAGE_DEFAULT)
const offset = ref(0)

let stream = null
/** 已见过的合并环 seq。⚠️ 必须有：重连/重挂时后端按**它自己**的游标续送，页面这边若被重放了
 *  已经画过的帧，会把同一批卡片画两遍。`seq` 是**全局单调、永不复用**的（`getAndIncrement`）
 *  ⇒ 拿它当去重键是安全的（换比它更弱的键，比如 `(kind,title,ts)`，同刻同环的两条会被误并）。 */
const seenSeq = new Set()
/** `traces` 按 traceId 索引（抽屉起止要用），与 `traceList` 是同一批数据的两种取法。 */
const tracesById = reactive(new Map())

/* ── 小工具（本页自备：与 `OrderFlow.vue` 的口径同源，不抽公共模块）────────────── */
const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')
const fmtRate = (v) => num(v).toFixed(1)
function pad(n, w) {
  return String(n).padStart(w, '0')
}
/** `HH:mm:ss.SSS`。⚠️ 卡片上写到**毫秒**是刻意的：一拍内跨环的事件墙钟常常同毫秒，
 *  只到秒的话抽屉里的链路会看起来像「什么都没发生」。 */
function hmsms(v) {
  const n = num(v)
  if (!n) return '—'
  const d = new Date(n)
  if (Number.isNaN(d.getTime())) return '—'
  return `${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}.${pad(d.getMilliseconds(), 3)}`
}

/** 对象列那一格：`说明词：值`。八类的说明词不同（见 `observe-labels.js` 的 `OBJECT_FIELD_LABEL`）。
 *  ⚠️ `audit` 那一类把 `objectType` 也带上（`商品:#20`）—— 它那一格的「是什么」由类型说，
 *  只有 `TargetId` 看不出动的是商品还是订单；其余七类的类型是**类名本身**（`sku` / `identity`…），
 *  再写一遍是重复。⚠️ commit 9 起 `objectType` 走 `targetTypeLabel()`（原先是英文域前缀直出）。 */
function objText(ev) {
  const id = ev.objectId
  if (id === null || id === undefined || id === '') return ''
  const label = objectFieldLabel(ev.kind)
  const type = ev.kind === 'audit' && ev.objectType ? `${targetTypeLabel(ev.objectType)}:#` : ''
  return `${label} ${type}${id}`
}

/** 抽屉上那枚状态码的色：2xx 中性、4xx 黄、5xx 红。⚠️ 只影响那一个 chip，不重画卡片。 */
function statusClass(status) {
  const s = num(status)
  if (s >= 500) return 'is-bad'
  if (s >= 400) return 'is-warn'
  return 'is-ok'
}

/* ── 派生量 ─────────────────────────────────────────────────────────────────── */
const first = computed(() => seq.value === 0 && !errMsg.value)
const phaseText = computed(() => (phase.value === 'live' ? '已连接' : (phase.value === 'stopped' ? '已断开' : '连接中')))
const lastTickAt = computed(() => (ts.value ? hmsms(ts.value) : '—'))
const isAllOn = computed(() => activeKinds.value.size === KINDS.length && activeSevs.value.size === SEVERITIES.length)

/** 窗口内按类计数（图例上的数字）。⚠️ **本页自己攒的**，不是帧里那个本拍的 `counts`。 */
const sessionByKind = computed(() => {
  const m = {}
  for (const e of events.value) m[e.kind] = (m[e.kind] || 0) + 1
  return m
})
const sessionBySev = computed(() => {
  const m = {}
  for (const e of events.value) m[e.severity] = (m[e.severity] || 0) + 1
  return m
})

const filtered = computed(() =>
  events.value.filter((e) => activeKinds.value.has(e.kind) && activeSevs.value.has(e.severity))
)
const pageRows = computed(() => filtered.value.slice(offset.value, offset.value + pageSize.value))

const openedTraceRec = computed(() => (openedTrace.value ? tracesById.get(openedTrace.value) || null : null))
/** 抽屉中间那一段：带这个 traceId 的事件，按墙钟**升序**（链路方向 = 时间方向）。
 *  ⚠️ 与列表的降序**刻意不同**：列表要「最新的在最上面」，链路要「先发生的在最上面」。 */
const openedEvents = computed(() =>
  openedTrace.value
    ? events.value.filter((e) => e.traceId === openedTrace.value)
        .slice()
        .sort((a, b) => a.ts - b.ts || a.seq - b.seq)
    : []
)

/* ── 筛选 / 翻页 ────────────────────────────────────────────────────────────── */
function toggleKind(k) {
  const next = new Set(activeKinds.value)
  if (next.has(k)) next.delete(k)
  else next.add(k)
  activeKinds.value = next
  offset.value = 0        // ⚠️ 筛选一变就回第 1 页：停在旧 offset 上会看到「第 9 页 / 共 1 页」
}
function toggleSev(s) {
  const next = new Set(activeSevs.value)
  if (next.has(s)) next.delete(s)
  else next.add(s)
  activeSevs.value = next
  offset.value = 0
}
function resetFilters() {
  activeKinds.value = new Set(KINDS)
  activeSevs.value = new Set(SEVERITIES)
  offset.value = 0
}
function onPage({ limit, offset: off }) {
  pageSize.value = limit
  offset.value = off
}

/* ── 抽屉 ───────────────────────────────────────────────────────────────────── */
function openTrace(ev) {
  // ⚠️ 没有 traceId 的卡片点了不做事（模板也没给它 `role`/`tabindex`）—— 见模板里那条注释。
  if (!ev || !ev.traceId) return
  openedTrace.value = ev.traceId
}
function closeTrace() {
  openedTrace.value = ''
}

/* ── 收帧 ──────────────────────────────────────────────────────────────────── */
/**
 * 收一拍的 `events`：去重（按 `seq`）→ 本拍内按 `ts`/`seq` **倒序** → 整批**前插** → 封顶。
 * ⚠️ 「整批前插、不全局重排」是刻意的：后端一拍内的事件是**按环分组**的（`poll()` 一环一环并），
 *    照 `ts` 做全局重排会让用户正在看的行在两拍之间跳走（同 `OrderFlow.vue` 的列表口径）。
 *    本拍内部排一次，正是为了把那个「按环分组」的假顺序在**卡片这一层**纠正掉。
 */
function absorbEvents(batch) {
  const fresh = []
  for (const raw of batch) {
    const s = num(raw.seq)
    if (seenSeq.has(s)) continue
    seenSeq.add(s)
    fresh.push({
      seq: s,
      ts: num(raw.ts),
      kind: raw.kind,
      severity: raw.severity,
      title: raw.title,
      detail: raw.detail,
      key: raw.key,
      objectType: raw.objectType,
      objectId: raw.objectId,
      traceId: raw.traceId === null || raw.traceId === undefined ? '' : String(raw.traceId),
    })
  }
  if (!fresh.length) return
  fresh.sort((a, b) => b.ts - a.ts || b.seq - a.seq)
  // 封顶：丢掉的是**最早**的那些（数组尾部）。同时把它们的 seq 从去重集里摘掉 ——
  // ⚠️ 不摘的话这个 Set 会随会话无界增长（它是本页唯一不封顶的东西）。
  const dropped = events.value.slice(Math.max(0, EVENT_CAP - fresh.length))
  events.value = fresh.concat(events.value.slice(0, Math.max(0, EVENT_CAP - fresh.length)))
  for (const d of dropped) seenSeq.delete(d.seq)
}

/** 收一拍的 `traces`：按 traceId 更新索引 + 前插列表（同一次请求只会出现在一拍里）。 */
function absorbTraces(batch) {
  const fresh = []
  for (const raw of batch) {
    const id = raw.traceId === null || raw.traceId === undefined ? '' : String(raw.traceId)
    if (!id) continue
    const row = {
      traceId: id,
      method: raw.method,
      path: raw.path,
      status: num(raw.status),
      startMs: num(raw.startMs),
      endMs: num(raw.endMs),
      ms: num(raw.ms),
    }
    tracesById.set(id, row)
    fresh.push(row)
  }
  if (!fresh.length) return
  fresh.sort((a, b) => b.endMs - a.endMs)
  // ⚠️ 被挤出去的 = **合并后超出上限的那一截**（`merged` 是「新在前」，故那一截一定是最旧的）。
  //    不能拿 `old.concat(fresh)` 去截 —— 那是另一个数组、截出来的不是被丢掉的那些。
  const merged = fresh.concat(traceList.value)
  const gone = merged.slice(TRACE_CAP)
  const kept = merged.slice(0, TRACE_CAP)
  traceList.value = kept
  // ⚠️ 索引里的旧行要摘掉，否则 `tracesById` 随会话无界增长（`traceList` 会封顶，它不会）。
  for (const g of gone) {
    if (!kept.some((k) => k.traceId === g.traceId)) tracesById.delete(g.traceId)
  }
}

function onFrame(f) {
  seq.value = num(f.seq)
  ts.value = num(f.ts) || Date.now()
  rate.value = num(f.rate)
  // ⚠️ `f.mq === true` 而不是 `!!f.mq`：帧里缺这个键的后端（本笔之前的提交）会让 `!!undefined`
  //    变成 false ⇒ 与「恒假常量」逐字同行为，页面不会因为升级错位而闪一个「已连接」。
  mqConnected.value = f.mq === true
  absorbEvents(Array.isArray(f.events) ? f.events : [])
  absorbTraces(Array.isArray(f.traces) ? f.traces : [])
}

function connect() {
  closeStream()
  errMsg.value = ''
  phase.value = 'connecting'
  stream = openEventStream({
    onOpen() { phase.value = 'live' },
    onFrame,
    onEnd(err) {
      // `onEnd(null)` = 服务端正常收尾 —— 不是故障，但流确实断了，故也置 stopped。
      phase.value = 'stopped'
      if (err) errMsg.value = (err && err.message) || '连接中断'
    },
  })
}

function closeStream() {
  if (stream) {
    stream.close()
    stream = null
  }
}

onMounted(connect)
onBeforeUnmount(closeStream)
</script>

<style scoped>
.a-el {
  max-width: 1120px;
}

/* ── ① 状态条（逐字照 `OrderFlow.vue`，那里又同源于 `MetricsPanel.vue`）────────── */
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

/* ── MQ 黄点（8c 起有数据源：帧里的 `mq` 键；见 `<script>` 的 `mqConnected`）─── */
.a-el-mq {
  display: inline-flex;
  align-items: center;
  gap: var(--s1);
  font-size: var(--fs-xs);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.a-el-mq.is-off {
  color: var(--warning);
  background: var(--warning-ground);
}
.a-el-mq.is-on {
  color: var(--success);
  background: var(--success-ground);
}
.a-el-mqdot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

/* ── 通用件（同 `OrderFlow.vue` 的复刻口径）─────────────────────────────────── */
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
/* 新增（本页独有）：`warn` 那一档 —— MQ 未连接不是错误，是「还没接上」。 */
.a-flash.warn {
  color: var(--warning);
  background: var(--warning-ground);
}
.a-el-flashnote {
  color: var(--ink-3);
  font-size: var(--fs-xs);
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
.a-btn:hover:not([disabled]) {
  color: var(--ink);
  background: var(--surface-3);
}
.a-btn[disabled] {
  color: var(--ink-3);
  cursor: not-allowed;
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
.a-el > .a-h2:first-of-type {
  margin-top: 0;
}
.a-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
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

/* ── 8 类图标（内联 SVG；`--el-k` 就是本枚的类色，见 `observe-labels.js`）──────── */
.a-el-ic {
  width: 16px;
  height: 16px;
  flex: none;
  color: var(--el-k, var(--ink-3));
  fill: none;
  stroke: currentColor;
  stroke-width: 1.3;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.a-el-ic-s {
  width: 13px;
  height: 13px;
  vertical-align: -2px;
  margin-right: 2px;
}

/* ── ② 图例（兼筛选）───────────────────────────────────────────────────────── */
.a-el-kinds {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s2);
}
.a-el-kind {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s3);
  cursor: pointer;
}
.a-el-kind:hover {
  background: var(--surface-3);
}
/* 关掉的那一类：**整枚压暗并去掉类色**（`--el-k` 落回灰）——
   留一点微弱的色反而会被读成「还在显示」。 */
.a-el-kind.is-off {
  opacity: 0.45;
  --el-k: var(--ink-3);
}
.a-el-kind.is-off .a-el-kc {
  color: var(--ink-3);
}
.a-el-kindn {
  font-size: var(--fs-xs);
}
.a-el-kc {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}
.a-el-ksep {
  width: 1px;
  align-self: stretch;
  background: var(--line-2);
  margin: 0 var(--s1);
}
/* 严重度那三枚用圆点而不是图标：它们不是「类」，是三档。 */
.a-el-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-k);
  flex: none;
}
.a-el-reset {
  margin-left: auto;
}

/* ── ③ 事件卡片流 ──────────────────────────────────────────────────────────── */
.a-el-list {
  list-style: none;
  display: flex;
  flex-direction: column;
}
.a-el-row {
  display: grid;
  grid-template-columns: 3px 16px 44px 92px minmax(0, 1fr) 152px;
  align-items: center;
  gap: var(--s3);
  padding: var(--s2) var(--s3) var(--s2) 0;
  border-bottom: 1px solid var(--line);
  border-radius: var(--r2);
}
.a-el-row:last-child {
  border-bottom: 0;
}
/* 左侧色条：类色。`is-*` 三档只决定**它有多显眼**，颜色仍归 `--el-k`。 */
.a-el-bar {
  align-self: stretch;
  min-height: 22px;
  background: var(--el-k);
  border-radius: var(--r1);
  opacity: 0.28;
}
.a-el-row.is-warn .a-el-bar {
  opacity: 0.6;
}
.a-el-row.is-error .a-el-bar {
  opacity: 1;
}
.a-el-row.is-openable {
  cursor: pointer;
}
.a-el-row.is-openable:hover,
.a-el-row.is-openable:focus-visible {
  background: var(--surface-2);
  outline: none;
}
.a-el-sev {
  font-size: var(--fs-xs);
  color: var(--el-s);
  white-space: nowrap;
}
.a-el-t {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.a-el-main {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  min-width: 0;
  flex-wrap: wrap;
}
.a-el-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-el-obj {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}
.a-el-detail {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1 1 100%;
}
.a-el-trace {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}
.a-el-trace.is-none {
  color: var(--ink-3);
}
@media (max-width: 900px) {
  .a-el-row {
    grid-template-columns: 3px 16px 40px minmax(0, 1fr) 110px;
  }
  .a-el-t {
    display: none;
  }
}

/* ── ④ 链路抽屉 ────────────────────────────────────────────────────────────── */
.a-el-modal {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s5);
}
.a-el-modal-box {
  width: min(620px, 100%);
  max-height: 80vh;
  overflow: auto;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
}
.a-el-modal-hd {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line-2);
}
.a-el-modalt {
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-el-close {
  margin-left: auto;
}
.a-el-st {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.a-el-st.is-ok {
  color: var(--success);
  background: var(--success-ground);
}
.a-el-st.is-warn {
  color: var(--warning);
  background: var(--warning-ground);
}
.a-el-st.is-bad {
  color: var(--danger);
  background: var(--danger-ground);
}
.a-el-modal-sub {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s2);
  font-size: var(--fs-sm);
  color: var(--ink);
  margin: var(--s3) 0;
}
.a-el-kv {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-el-mono {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 4px;
}
/* 链路：一条竖线 + 每行一个节点（`├` / `└` 那套用 CSS 画，不是字符画 ——
   字符画在等宽字体缺失时会错位，而本仓的字体是自托管的，不能假定某个 box-drawing 字形存在）。 */
.a-el-chain {
  list-style: none;
  margin: var(--s3) 0;
  padding-left: var(--s3);
  border-left: 2px solid var(--line-2);
}
.a-el-chain-i {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: var(--s3);
  padding: var(--s1) 0 var(--s1) var(--s4);
  position: relative;
  font-size: var(--fs-sm);
  color: var(--ink);
}
.a-el-chain-i::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: var(--s3);
  height: 2px;
  background: var(--line-2);
}
.a-el-chain-i.is-ends::before {
  background: var(--brand-soft);
}
.a-el-chain-i.is-miss {
  color: var(--ink-3);
}
.a-el-chain-t {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.a-el-chain-d b {
  color: var(--ink);
  font-weight: var(--fw-semi);
}
.a-el-chain-i.is-ends .a-el-chain-d b {
  color: var(--brand-ink);
}
.a-el-note-s {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  padding-top: var(--s3);
  border-top: 1px dashed var(--line);
}

/* ── ⑤ 覆盖与边界 ──────────────────────────────────────────────────────────── */
.a-el-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.9;
}
.a-el-note b {
  color: var(--ink-2);
  font-weight: var(--fw-semi);
}
</style>
