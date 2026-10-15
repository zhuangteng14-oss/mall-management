<template>
  <section class="a-of">
    <!-- ── ① 状态条（与 `MetricsPanel.vue` / `StatsPage.vue` 逐字同源，不另立一套）────────── -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ fmtInt(total) }}</b>
        <span>订单总数（库里此刻）</span>
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

    <!-- ══ ② 五态计数卡 + 状态分布环图 ═════════════════════════════════════════════
         ⚠️ 计数口径 = **后端聚合查询**（`OrderDao.countOrderStates()`），不是「前端把事件流累加」——
         事件环有界且只覆盖本进程起来之后，累加出来的数在演示着跑一会儿之后必然与库里对不上。
         卡片上的数**会实时变**：库里某单付了款，下一拍这个数就跳。
         ⚠️ 与 `MetricsPanel` 的 KPI 卡不同，这里**不给环比**：五态计数是「存量」，不是「速率」，
         拍与拍之间的差全是噪声（1 拍里 ±3 单说明不了任何事），所以 `delta` 恒为 null、
         副行改说「占总数几成」—— 那个数是同一条 SQL 里的 `total`，口径自洽。 -->
    <h2 class="a-h2">
      五态计数 <span class="a-h2n">后端聚合查询（非事件累加）· 每拍重算 · 判据链：取消 → 已完成 → 待收货 → 待发货 → 待付款</span>
    </h2>
    <div class="a-of-top">
      <div class="a-of-kpis">
        <KpiCard
          v-for="s in STATES"
          :key="s.key"
          :label="s.label"
          glyph="flow"
          :value="fmtInt(shown[s.key])"
          :hint="shareText(s.key)"
          :loading="first"
        />
      </div>
      <div class="a-card a-of-donut">
        <!-- ⚠️ 半径**两处一起收**（容器 400px + inner 46% / outer 58%，缺省是 58% / 78%）：
             环图的扇区名走 ECharts 的**图外标签**（`{b} {d}%`），标签从环边缘再向外
             延伸「折线 15px + 文字 ~70px」。缺省半径下左右两条（「已完成 41.9%」「待收货 30.6%」）
             会伸出容器被 canvas **裁掉**（2026-10-14 截图实测，左右两端都被裁）。
             摆得下的条件（容器内宽 368、高 240 ⇒ 半径基数 = min/2 = 120）：
               左边缘 184 − 120×outer% ≥ 15 + 70   ⇒  outer% ≤ 82% …单看这条很宽松，
             真正的约束是**环整体要居中**，故 outer 取 58%（半径 70px）留足两端。
             改这三个数里的任何一个之前，先按这条式子算一遍。 -->
        <DonutChart
          :data="donutData"
          height="240px"
          :show-legend="false"
          inner-ratio="42%"
          outer-ratio="54%"
          :center-value="fmtInt(total)"
          center-title="订单总数"
          :loading="first"
          :error="errMsg"
          empty-text="还没收到第一拍"
          @retry="connect"
        />
      </div>
    </div>

    <!-- ══ ③ 实时订单列表（每单一行 + 4 节点进度条）══════════════════════════════
         ⚠️ **这一段的条数是前端攒的**，不是后端给的：流的每一拍只推**最近 20 张单**
         （`OrderStreamService.RECENT_LIMIT`），不够翻页，也留不住「刚才那单现在到哪了」。
         故本页按 `orderId` 累积成本会话见过的一张表（封顶 `LIST_CAP` 条，见 `<script>` 注释），
         `Pager` 对它做**前端分页** —— 这与 `Pager.vue` 头注释里「分页走后端」的**惯用**场景不同，
         但组件本身是纯受控件（拿 total/limit/offset 画控件、emit change），调用方怎么响应 change
         是调用方的事：本页没有分页端点可发（后端唯一的分页读端点是订单治理页的 `POST /admin/order/list`，
         它按条件筛，不是按「最近流转」推），也不该为了让控件对口去加一个端点。 -->
    <h2 class="a-h2">
      实时订单列表
      <span class="a-h2n">本会话见过 {{ listRows.length }} 张 · 每拍推最近 {{ RECENT_LIMIT }} 张 · 进度 4 节点 = 待付款 → 待发货 → 待收货 → 已完成</span>
    </h2>
    <div class="a-card">
      <p v-if="!listRows.length" class="a-empty">
        {{ first ? '还没收到第一拍。' : '本会话还没收到任何订单（库里 `order` 表为空，或流的窗口内没有单）。' }}
      </p>
      <ul v-else class="a-of-list">
        <li
          v-for="r in pageRows"
          :key="r.orderId"
          class="a-of-row"
          :class="{ 'is-jump': jumpIds.has(r.orderId) }"
          :title="'点开看这一单的时间线'"
          tabindex="0"
          role="button"
          @click="openTimeline(r.orderId)"
          @keydown.enter.prevent="openTimeline(r.orderId)"
          @keydown.space.prevent="openTimeline(r.orderId)"
        >
          <span class="a-of-id">#{{ r.orderId }}</span>

          <!-- 4 节点进度条：`step` 由后端算好（0–4）⇒ 前端不重推一遍状态链（两处判据已经够多了）。
               ⚠️ `is-live`（「—」流动）的判据就两条：**没取消** 且 **没走完**（step < 4）。
                  已取消的单已经退出流程、已完成的单已经走完 —— 这两种还在流动是假信号
                  （commit 7 那版是两个都没判，所以任何单看着都是死的，见 `<style>` 里 `.a-of-seg` 注释）。 -->
          <span class="a-of-track" :class="{ 'is-dead': r.cancelled, 'is-live': !r.cancelled && r.step < 4 }" aria-hidden="true">
            <template v-for="(n, i) in 4" :key="n">
              <i v-if="i > 0" class="a-of-seg" :class="{ on: r.step > i }"></i>
              <i class="a-of-node" :class="{ on: r.step >= n, now: r.step === n }"></i>
            </template>
          </span>

          <span class="a-of-st" :class="'is-' + r.state">{{ stateLabel(r.state) }}</span>
          <span class="a-of-goods" :title="r.product">{{ r.product || '（无商品信息）' }}</span>
          <span class="a-of-amt">{{ money(r.amount) }}<template v-if="r.quantity > 1"> × {{ r.quantity }}</template></span>
        </li>
      </ul>

      <Pager
        :total="listRows.length"
        :limit="pageSize"
        :offset="offset"
        :sizes="PAGE_SIZES"
        unit="单"
        label="实时订单"
        @change="onPage"
      />
    </div>

    <!-- ══ ④ 状态机图（4 主节点 + 取消）═══════════════════════════════════════════
         ⚠️ 手写 SVG 而**不是** ECharts 的 `graph` 系列：本仓 `components/charts/` 里没有 graph 封装，
         为了这一张图去引一个新图表类型（连带它的布局算法与主题参数）不划算 —— 5 个节点 5 条边，
         SVG 的 `<rect>`/`<line>` 二十行就画完了，且主题色直接吃 CSS 变量。
         「走过的边亮绿」= 本拍 `moves` 里出现过的 `from>to`，亮 1.2 秒后自动熄（见 `flash()`）。 -->
    <h2 class="a-h2">
      状态机
      <span class="a-h2n">
        被本拍流转走过的边，短时高亮（每秒最多 {{ FLASH_HZ_MAX }} 次）· 落到哪个节点，哪个脉冲 ·
        框内角标 = <b>全表</b>多少单，框下小字 = <b>本批次</b>经过多少次
      </span>
    </h2>
    <div class="a-card a-of-fsm">
      <svg class="a-of-svg" viewBox="0 0 640 232" role="img" aria-label="订单状态机：待付款 → 待发货 → 待收货 → 已完成，以及待付款/待发货 → 已取消">
        <defs>
          <marker id="of-arrow" markerWidth="9" markerHeight="9" refX="8" refY="3.2" orient="auto">
            <path d="M0,0 L8,3.2 L0,6.4 z" fill="var(--line-2)" />
          </marker>
          <marker id="of-arrow-hi" markerWidth="9" markerHeight="9" refX="8" refY="3.2" orient="auto">
            <path d="M0,0 L8,3.2 L0,6.4 z" fill="var(--success)" />
          </marker>
        </defs>

        <!-- 主链：待付款 → 待发货 → 待收货 → 已完成（x 中心 80 / 240 / 400 / 560，框宽 100） -->
        <g class="a-of-edges">
          <line
            v-for="e in MAIN_EDGES" :key="e.id"
            :x1="e.x1" y1="70" :x2="e.x2" y2="70"
            class="a-of-edge" :class="{ on: activeEdges.has(e.id) }"
            :marker-end="activeEdges.has(e.id) ? 'url(#of-arrow-hi)' : 'url(#of-arrow)'"
          />
        </g>

        <!-- 取消两条腿（折线绕开主链，从框底下去） -->
        <g class="a-of-edges">
          <path
            d="M80 92 L80 130 L240 130 L240 152"
            class="a-of-edge" :class="{ on: activeEdges.has(CANCEL_FROM_PAY) }"
            :marker-end="activeEdges.has(CANCEL_FROM_PAY) ? 'url(#of-arrow-hi)' : 'url(#of-arrow)'"
          />
          <path
            d="M240 92 L240 152"
            class="a-of-edge" :class="{ on: activeEdges.has(CANCEL_FROM_SHIP) }"
            :marker-end="activeEdges.has(CANCEL_FROM_SHIP) ? 'url(#of-arrow-hi)' : 'url(#of-arrow)'"
          />
        </g>

        <!-- 每个节点**两行数**，口径不同、分行标清（Q3 裁定）：
             · 框内右上角标 = **全表**口径（帧里 `counts` 现成，`OrderDao.countOrderStates()` 无时间窗）
               ⇒ 演练时会显示 11558 这种总量，它说「库里此刻这个态有多少单」。
             · 框下小字 = **本批次**口径（帧里 `moves[].batchId` 与当前批次相符的、落到本节点的**次数**）
               ⇒ 它说「这次演练把多少条流转推进了这个节点」。没有批次号时画 `—`，
               不画 `+0`（「没有演练在跑」和「演练跑了但一次没到」是两件事）。 -->
        <g class="a-of-nodes">
          <g
            v-for="n in FSM_NODES" :key="n.key"
            :class="{ 'is-pulse': pulseNodes.has(n.key) }"
          >
            <rect :x="n.cx - 50" :y="n.cy - 22" width="100" height="44" rx="10" class="a-of-box" :class="'is-' + n.key" />
            <text :x="n.cx" :y="n.cy + 5" text-anchor="middle" class="a-of-box-t" :class="'is-' + n.key">{{ n.label }}</text>
            <text
              :x="n.cx + 40" :y="n.cy - 11"
              text-anchor="end" class="a-of-box-n"
            >{{ fmtInt(num(counts[n.key])) }}</text>
            <text
              :x="n.cx" :y="n.cy + 34"
              text-anchor="middle" class="a-of-box-b"
            >{{ batchText(n.key) }}</text>
          </g>
        </g>
      </svg>
      <p class="a-of-fsm-note">
        ⚠️ 已知边界（后端口径，不是本页漏画）：**超时取消不进流转流** ——
        `OrderTimeoutCancelJob` 只写 `CancelledAt`、不走状态机 ⇒ 取消那两条边**永远不亮**，
        取消量只能从上面「已取消」计数卡的跳变看出（见后端 `OrderStreamService` 类注释）。
        <template v-if="currentBatch">
          当前批次：<code>{{ currentBatch }}</code>。
        </template>
        <template v-if="batchCountsRows">
          <br />本批次单数（后端 `batchCounts`，**单数**口径，与上面小字的**次数**口径不同）：
          <template v-for="(r, i) in batchCountsRows" :key="r.key">{{ i ? ' · ' : '' }}{{ r.label }} {{ fmtInt(r.n) }}</template>。
        </template>
      </p>
    </div>

    <!-- ══ ⑤ 单条订单时间线（点列表某行弹出）═════════════════════════════════════
         ⚠️ 数据来源**只有两处**：订单行自带的 `createdAt`（建单时刻）+ 本会话在 `moves` 里捕获的流转。
         本页没有「查某单历史」的端点（后端也不该为这一页加一个）⇒ 页面打开**之前**已经走完的流转
         捕获不到。这条如实写在弹层里，不假装是一条完整时间线。 -->
    <div v-if="openedId" class="a-of-modal" @click.self="closeTimeline">
      <div class="a-of-modal-box" role="dialog" aria-modal="true" :aria-label="'订单 ' + openedId + ' 的时间线'">
        <header class="a-of-modal-hd">
          <b class="a-of-id">#{{ openedId }}</b>
          <span v-if="openedRow" class="a-of-st" :class="'is-' + openedRow.state">{{ stateLabel(openedRow.state) }}</span>
          <button type="button" class="a-btn a-of-close" @click="closeTimeline">关闭</button>
        </header>
        <p v-if="openedRow" class="a-of-modal-sub">
          {{ openedRow.product || '（无商品信息）' }} · {{ money(openedRow.amount) }}
          <template v-if="openedRow.quantity > 1"> × {{ openedRow.quantity }}</template>
        </p>

        <ol class="a-of-tl">
          <li v-if="openedRow && openedRow.createdAt" class="a-of-tl-i">
            <span class="a-of-tl-t">{{ hms(openedRow.createdAt) }}</span>
            <span class="a-of-tl-d">创建订单（待付款）</span>
          </li>
          <li v-for="(m, i) in openedSteps" :key="i" class="a-of-tl-i">
            <span class="a-of-tl-t">{{ hms(m.ts) }}</span>
            <span class="a-of-tl-d">
              {{ moveStateLabel(m.from) }} → <b>{{ moveStateLabel(m.to) }}</b>
            </span>
          </li>
        </ol>

        <p class="a-of-tl-note">
          <!-- ⚠️ 三条**互斥**分支，顺序即优先级（写成一包 `v-if/v-else-if/v-else`，
               不是三条并列的 `v-if` —— 并列会让「只捕获到建档」时同时命中一、两条，
               于是同一段话里既说「还没捕获到」又说「捕获到 N 次」，自相矛盾）。
               ⚠️ 「建档」那一步（`from` 为空的 create）**不进上面那个列表**：本段第一行
               「创建订单（待付款）」就是它，再列一遍等于每张单多一行废话
               （演练下 100% 的流转都是建档，见 `块7-commit10-阶段一核实.txt` §3b 实测 4670/4670）。 -->
          <template v-if="!openedMoves.length">
            本会话还没捕获到这张单的流转 —— 它可能在本页打开之前就走完了（本页不查历史，见组件头注释）。
          </template>
          <template v-else-if="!openedSteps.length">
            捕获到 {{ openedMoves.length }} 次流转，**全部是建档** —— 已由上面第一行「创建订单（待付款）」表达，不重复列。
          </template>
          <template v-else>
            时间线只含**本页打开之后**捕获到的流转；更早的那几步不在流里，故不显示。
          </template>
          <template v-if="openedRow && openedRow.cancelled">
            这张单是**已取消**：取消时刻后端不推（超时取消不走状态机，见状态机图下的说明）。
          </template>
        </p>
      </div>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { openOrderStream } from '../../../api/observe'
// 演示模式（块 7 收尾 ②）：`DemoModeToggle` 摆开关，`demo` 是被 `watch` 的那个状态，
// `openDemoStream` 是与 `openOrderStream` **同形**的假流 —— 见 `connect()` 里那处三元式。
import { demo, openDemoStream } from '../../../api/demo-stream'
import DemoModeToggle from '../../../components/observe/DemoModeToggle.vue'
import KpiCard from '../../../components/ui/KpiCard.vue'
import DonutChart from '../../../components/charts/DonutChart.vue'
import Pager from '../../../components/Pager.vue'
import { chartTokens } from '../../../components/charts/chartTokens'
import { orderFlowStateLabel } from '../../../config/labels/observe-labels'

/** 后端每拍推的「最近订单」条数（`OrderStreamService.RECENT_LIMIT`）。本页只用来写说明。 */
const RECENT_LIMIT = 20
/** 本页自己累积的订单表上限。⚠️ 必须有：SSE 每秒推 20 条，不封顶跑一晚上就是几万条 DOM。 */
const LIST_CAP = 200
/** 可选的每页条数（`Pager` 的入参；`Pager` 头注释：卡片大/行窄排布不同，不该一刀切）。 */
const PAGE_SIZES = [10, 20, 50]
/** 高亮持续时长（毫秒）—— 边/节点/行「刚动过」的短时标记（《块 7 参考》§3.3 的 0.5 秒起，取 1.2 秒让肉眼追得上）。 */
const FLASH_MS = 1200
/** 计数卡数字滚动时长（《块 7 参考》§3.3 的 0.3 秒口径）。 */
const TWEEN_MS = 300

/** 五态（顺序 = 上方计数卡与环图的显示顺序）。键名与后端 `counts` / `state` 逐字相同。 */
const STATES = [
  { key: 'waitPay', label: orderFlowStateLabel('waitPay') },
  { key: 'waitShip', label: orderFlowStateLabel('waitShip') },
  { key: 'waitReceive', label: orderFlowStateLabel('waitReceive') },
  { key: 'completed', label: orderFlowStateLabel('completed') },
  { key: 'cancelled', label: orderFlowStateLabel('cancelled') },
]
const STATE_LABEL = Object.fromEntries(STATES.map((s) => [s.key, s.label]))

/* ── 状态机图的静态几何（写死坐标，不做自动布局 —— 5 个节点，布局算法是负收益）──────── */
const FSM_NODES = [
  { key: 'waitPay', label: '待付款', cx: 80, cy: 70 },
  { key: 'waitShip', label: '待发货', cx: 240, cy: 70 },
  { key: 'waitReceive', label: '待收货', cx: 400, cy: 70 },
  { key: 'completed', label: '已完成', cx: 560, cy: 70 },
  { key: 'cancelled', label: '已取消', cx: 240, cy: 174 },
]
/** 主链三条边。`id` 就是 `from + '>' + to`，与后端 `moves` 的 `from`/`to` 拼出来的一模一样。 */
const MAIN_EDGES = [
  { id: 'waitPay>waitShip', x1: 130, x2: 190 },
  { id: 'waitShip>waitReceive', x1: 290, x2: 350 },
  { id: 'waitReceive>completed', x1: 450, x2: 510 },
]
/** 取消两条腿的边 id（几何在模板里写死，这里只留判据用的常量）。 */
const CANCEL_FROM_PAY = 'waitPay>cancelled'
const CANCEL_FROM_SHIP = 'waitShip>cancelled'
/** 本页**画出来了的**全部 5 条边 —— 用来判「这条边图上有吗」，见 `absorbMoves` 的守卫。 */
const KNOWN_EDGES = new Set([...MAIN_EDGES.map((e) => e.id), CANCEL_FROM_PAY, CANCEL_FROM_SHIP])

/* ── 后端 `OrderState` ↔ 本页五态：**唯一**的一层翻译（C3 块 7 · commit 10 补）────────────
   ⚠️ 为什么需要它：后端 `moves[].from` / `moves[].to` 是 **OrderState 枚举名**
      （`order/model/OrderState.java:44-72`），而本页的节点 key / 边 id 是 **DB 派生五态**
      （上面 `FSM_NODES` / `MAIN_EDGES`）—— 两套名字**没有一个对得上**
      （`CREATED ≠ waitPay`、`COMPLETED ≠ completed`，连大小写都不同）。
      ⇒ commit 7 那版 `flash(activeEdges, `${from}>${to}`)` 在 `MAIN_EDGES` 里永远查不到，
      `pulseNodes` 拿到的枚举名也不在 `FSM_NODES` 的 key 集合里，**两条路都亮不了**：
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

/** 框下那行小字。⚠️ 没有批次号时画 `—`，**不画 `+0`**：
 *  「没有演练在跑」和「演练跑了但这个节点一次没到」是两件事，用同一个数表示会读错。 */
function batchText(key) {
  return currentBatch.value ? `本批次 +${fmtInt(num(batchNodeHits[key]))}` : '本批次 —'
}

/** 框外那行「本批次单数」的数字行（五态全列，没批次时为 `null` ⇒ 模板整行不画）。 */
const batchCountsRows = computed(() => (batchCounts.value
  ? STATES.map((s) => ({ key: s.key, label: s.label, n: num(batchCounts.value[s.key]) }))
  : null))

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

const target = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })
const shown = reactive({ waitPay: 0, waitShip: 0, waitReceive: 0, completed: 0, cancelled: 0 })

let stream = null
let rafId = 0
const timers = new Set()                   // 所有短时标记的定时器 —— 卸页时必须逐个清（否则回调打到已卸载的组件上）

/* ── 小工具（本页自备：与 `MetricsPanel.vue` 的口径同源，不抽公共模块）────────────── */
const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')
function pad2(n) {
  return String(n).padStart(2, '0')
}
/** 统一成 `HH:mm:ss`。⚠️ `createdAt` 是后端 `CreateTime`（ISO 式**字符串**），
 *  `moves[].ts` 是 epoch 毫秒（**数字**）—— 两种都吃，别指望调用方自己分。 */
function hms(v) {
  if (v === null || v === undefined || v === '') return '—'
  const d = typeof v === 'number' ? new Date(v) : new Date(String(v).replace(' ', 'T'))
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
function flash(setRef, key) {
  const next = new Set(setRef.value)
  next.add(key)
  setRef.value = next
  const t = setTimeout(() => {
    timers.delete(t)
    const after = new Set(setRef.value)
    after.delete(key)
    setRef.value = after
  }, FLASH_MS)
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
/** 本会话捕获到的流转总条数。 */
const moveTotal = computed(() => {
  let n = 0
  for (const arr of movesById.values()) n += arr.length
  return n
})

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

/* ── 列表（本会话累积 + 前端分页）────────────────────────────────────────────── */
const listRows = computed(() => orderIds.value.map((id) => ordersById.get(id)).filter(Boolean))
const pageRows = computed(() => listRows.value.slice(offset.value, offset.value + pageSize.value))
const openedRow = computed(() => (openedId.value ? ordersById.get(openedId.value) || null : null))
const openedMoves = computed(() => (openedId.value ? movesById.get(openedId.value) || [] : []))
/** 时间线实际渲染的步数：**去掉建档那一步**（`action=create`、`from` 为空）。
 *  ⚠️ 为什么去掉：上面第一行「创建订单（待付款）」就是它 —— 演练下 100% 的流转都是建档
 *  （`块7-commit10-阶段一核实.txt` §3b 实测 4670/4670），不去掉就是每张单都多一行废话。
 *  判据用 `from` 空不空，而不是 `action === 'create'`：后者是英文 code，且本页**刻意不存** `action`
 *  （`movesById` 的注释里写了理由 —— `from → to` 已经把这一步说全了）。 */
const openedSteps = computed(() => openedMoves.value.filter((m) => stateKeyOf(m.from) !== ''))

function onPage({ limit, offset: off }) {
  pageSize.value = limit
  offset.value = off
}
function openTimeline(id) {
  if (ordersById.has(id)) openedId.value = id
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
    ordersById.set(idStr, {
      orderId: idStr,
      customerId: raw.customerId,
      merchantId: raw.merchantId,
      amount: raw.amount,
      quantity: raw.quantity,
      product: raw.product,
      createdAt: raw.createdAt,
      state: raw.state,
      step: num(raw.step),
      cancelled: !!raw.cancelled,
    })
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
  for (const m of moves) {
    // ① 批次号：先学（这一步与「这一单认不认得出来」无关，故放在最前面）。
    const batch = m.batchId === null || m.batchId === undefined ? '' : String(m.batchId)
    if (batch && batch !== currentBatch.value) {
      currentBatch.value = batch
      // 换批次 ⇒ 框下小字清零。⚠️ 不清就是把两轮演练的次数加在一起，那已经不是「本批次」。
      STATES.forEach((s) => { batchNodeHits[s.key] = 0 })
    }

    const id = m.orderId === null || m.orderId === undefined ? '' : String(m.orderId)
    if (!id) continue

    // ② 时间线
    const arr = movesById.get(id) || []
    arr.push({ from: m.from, to: m.to, ts: num(m.ts) })
    // 时间线不该无限长（同一张单被反复推同一拍）：同 (from,to,ts) 只留一条。
    const dedup = arr.filter((x, i) => i === arr.findIndex((y) => y.from === x.from && y.to === x.to && y.ts === x.ts))
    movesById.set(id, dedup)

    // ③ 图上高亮（枚举名 → 五态 key 之后才谈得上「查得到」）
    const toKey = stateKeyOf(m.to)
    if (!toKey) continue
    // ⚠️ 这里**再比一次** `batch === currentBatch` 不是多余：一帧里可能同时夹着上一批的尾巴与
    //    本批的头（① 刚把 currentBatch 改成新值），照单全收就会把上一批的次数算进「本批次」。
    if (batch && batch === currentBatch.value) batchNodeHits[toKey] += 1
    flashThrottled(pulseNodes, toKey)
    const fromKey = stateKeyOf(m.from)
    if (fromKey) {
      const edgeId = `${fromKey}>${toKey}`
      if (KNOWN_EDGES.has(edgeId)) flashThrottled(activeEdges, edgeId)
    }
  }
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

/* ── 收帧 ──────────────────────────────────────────────────────────────────── */
function onFrame(f) {
  seq.value = num(f.seq)
  ts.value = num(f.ts) || Date.now()
  counts.value = f.counts || {}
  // 本批次口径：后端没有批次号时回 `null`（不是回一份全 0）⇒ 原样透传，由模板决定画不画。
  batchCounts.value = f.batchCounts || null

  absorbOrders(Array.isArray(f.orders) ? f.orders : [])
  absorbMoves(Array.isArray(f.moves) ? f.moves : [])

  STATES.forEach((s) => { target[s.key] = num(counts.value[s.key]) })
  tweenTo({ ...target })
}

function connect() {
  closeStream()
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
  stream = demo.on ? openDemoStream('orders', handlers) : openOrderStream(handlers)
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

onMounted(connect)
// ⚠️ 切数据源（`正常` ⇄ `模拟日常`）⇒ 立刻重连（`connect()` 会先 `closeStream()`）。
watch(() => demo.on, connect)
onBeforeUnmount(closeStream)
</script>

<style scoped>
.a-of {
  max-width: 1120px;
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
  .a-of-nodes .is-pulse .a-of-box {
    animation: none;
  }
  /* 3a 的「—」流动也关掉 —— ⚠️ 只关 animation、**不关虚线本身**：
     虚线是「这一段走过了」的静态标识，关掉它等于顺手把状态也擦了。 */
  .a-of-track.is-live .a-of-seg.on {
    animation: none;
  }
  .a-of-node,
  .a-of-edge,
  .a-of-row.is-jump {
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
  gap: var(--s3);
  margin: var(--s4) 0;
}
.a-of-tl-i {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: var(--s3);
  font-size: var(--fs-sm);
  color: var(--ink);
}
.a-of-tl-t {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-of-tl-d b {
  color: var(--success-deep);
  font-weight: var(--fw-semi);
}
.a-of-tl-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  padding-top: var(--s3);
  border-top: 1px dashed var(--line);
}
</style>
