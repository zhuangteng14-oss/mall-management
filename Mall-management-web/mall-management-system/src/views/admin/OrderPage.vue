<template>
  <section class="a-ord">
    <!-- ── ① 状态条（与 `AccountsPage.vue` / `AuditPage.vue` 同款，不另立一套）────────
         三个大数 + 时区口径。⚠️ 「库内总单数」取自 `/admin/order/list` 的 `total`
         —— 那是**全表** `COUNT(*)`，**不是**本次筛选数（后端刻意分开回报，见 `AdminOrderService.list`）。
         本页所有时间列都是**无时区**的库内文本（`CreateTime` 是 `varchar(50)`，不是 `datetime`）
         ⇒ 唯一「错也看不出来」的地方就是时区偏移，摆在台面上即可一眼核。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ total }}</b>
        <span>库内总单数（全表，非本次筛选）</span>
      </div>
      <div class="a-stat">
        <b>{{ rows.length }}</b>
        <span>本次显示{{ truncated ? '（已达上限）' : '' }}</span>
      </div>
      <div class="a-stat">
        <!-- ⚠️ 监控拉失败时这两个数显示 `—` 而**不是 `0`** —— 「真的是 0」与「没查出来」
             长得一模一样是最坏的一种错（同 `AdminOrderService.list` 对未知状态的处置口径）。 -->
        <b>{{ monitorFailed ? '—' : monitor.abnormalState }}</b>
        <span>异常态（未付款却已发货/收货）</span>
      </div>
      <div class="a-stat">
        <b>{{ monitorFailed ? '—' : monitor.unfinished }}</b>
        <span>未完成（未收货）</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">UTC</span>
        <span>库内时钟 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── 页内 tab ─────────────────────────────────────────────────────────────
         两块（`docs/agent/c3-modules.md` §五：「子项页含多块 ⇒ 页内 tab，tab 数 2–4 个」）。
         ⚠️ **「介入」不是第三个 tab**（用户口径是「全量视图 + 异常监控 + 介入」三件事）——
         介入是**行级动作**：它必须出现在**那一行的旁边**，拆成独立 tab 就得让管理员先记住单号
         再切页面。⇒ 它留在「订单全量」的卡片里，是**第三件事、不是第三页**。 -->
    <nav class="a-tabs">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        class="a-tab"
        :class="{ on: tab === t.key }"
        @click="tab = t.key"
      >
        {{ t.label }}<span class="a-tabn">{{ t.note() }}</span>
      </button>
    </nav>

    <!-- ══ ② 订单全量（tab 一）══════════════════════════════════════════════════ -->
    <template v-if="tab === 'list'">
      <h2 class="a-h2">订单全量 <span class="a-h2n">跨用户、跨商家（无归属约束）—— 这是管理端与订单域的分界</span></h2>

      <form class="a-filters" @submit.prevent="loadList">
        <label class="a-f">
          <span>买家</span>
          <input v-model.trim="f.CustomerId" type="text" placeholder="CustomerId" />
        </label>
        <label class="a-f">
          <span>商家</span>
          <input v-model.trim="f.MerchantId" type="text" placeholder="MerchantId" />
        </label>
        <label class="a-f">
          <span>状态</span>
          <select v-model="f.status">
            <option value="">全部</option>
            <option value="unpaid">未付款</option>
            <option value="paid">已付款未发货</option>
            <option value="shipped">已发货未收货</option>
            <option value="completed">已完成</option>
            <option value="abnormal">异常态</option>
          </select>
        </label>
        <!-- ⚠️ 时间筛**必须是文本框、不能换 `datetime-local`**：`CreateTime` 是字符串列，
             后端做的是**字典序**比较（`AdminOrderDao.selectOrders` 的注释写明），而
             `datetime-local` 产出的值带 `T` 分隔符（`2026-10-10T12:00`）—— `'T'(0x54)` 大于
             `' '(0x20)` ⇒ 「同一天的 12:00」会比「同一天的 12:00 整」**大**，筛选静默失准。
             格式依赖是 ISO 式零填充（`YYYY-MM-DD HH:MM:SS`），跟库里的写入格式**逐字**对齐。 -->
        <label class="a-f a-f-wide">
          <span>起始（含）</span>
          <input v-model.trim="f.from" type="text" placeholder="YYYY-MM-DD HH:MM:SS（须零填充）" />
        </label>
        <label class="a-f a-f-wide">
          <span>结束（含）</span>
          <input v-model.trim="f.to" type="text" placeholder="YYYY-MM-DD HH:MM:SS（须零填充）" />
        </label>
        <label class="a-f a-f-narrow">
          <span>上限</span>
          <input v-model.number="f.limit" type="number" min="1" max="500" />
        </label>
        <div class="a-f-actions">
          <button type="submit" class="a-btn a-btn-on" :disabled="loading"><IconGlyph name="ok" />查询</button>
          <button type="button" class="a-btn" :disabled="loading" @click="resetFilters">清空</button>
        </div>
      </form>

      <p class="a-hint">
        未知的状态词**不当成「不筛」**：后端回 <code>success:false</code> 并把原词念回来
        —— 「筛错了一个词」与「这个状态确实没数据」在画面上都是空表，把前者说成后者是最坏的一种错。
        上限（默认 200 / 最大 <code>500</code>）只做**截断**不做真分页：<code>order</code> 表没有分页键。
      </p>
      <p v-if="truncated" class="a-note">
        本次结果已达取回上限：仅取回 {{ rows.length }} 条（按时间倒序），后面可能还有。
      </p>

      <!-- ── 状态分布 · GMV 趋势 · 发货时效（可视化段 commit 3）──────────────────
           ⚠️ 三张图的数据源都是**本次已取回的 `rows`**（受筛选与上限 200/500 约束），
              不是全库。`truncated` 为真时它们只覆盖按时间倒序的最近那一段 —— 标题里
              一律带「本次 N 条」，不冒充全量口径。 -->
      <h2 class="a-h2">
        状态分布 × 时间
        <span class="a-h2n">
          每天各状态的订单数（堆叠）· 本次 {{ rows.length }} 条 · 五态互斥（异常态已从「未付款」里剥出）
        </span>
      </h2>
      <div class="a-card">
        <BarChart
          :categories="gmv.days"
          :series="statusByDay"
          :stacked="true"
          :height="'260px'"
          value-formatter="{value} 单"
          :max-bar-width="26"
          :loading="loading"
          empty-text="本次订单没有可归日的下单时刻"
        />
      </div>

      <h2 class="a-h2">
        GMV / 成单数趋势
        <span class="a-h2n">
          按本地下单日汇总 · 缺日补 0 · **左轴 ¥ = 当日 GMV、右轴 单 = 当日成单数**
          <span v-if="truncated"> · ⚠️ 本次已截断，曲线只覆盖最近一段</span>
        </span>
      </h2>
      <div class="a-card">
        <LineChart
          :categories="gmv.days"
          :series="[
            { name: '当日 GMV', data: gmv.sums, area: true },
            { name: '当日成单数', data: gmv.counts, axis: 'right' },
          ]"
          :height="'260px'"
          y-formatter="¥{value}"
          y2-formatter="{value} 单"
          :loading="loading"
          empty-text="本次订单没有可归日的下单时刻"
        />
      </div>

      <h2 class="a-h2">
        发货时效
        <span class="a-h2n">
          `ShippedAt` − `CreateTime` · 本次可比 {{ shipLag.dated }} 单
          <template v-if="shipLag.nodate"> · ⚠️ {{ shipLag.nodate }} 单已发货但无发货时刻（老单）</template>
          <template v-if="shipLag.undeliv"> · {{ shipLag.undeliv }} 单未发货</template>
          <template v-if="shipLag.bad"> · ⚠️ {{ shipLag.bad }} 单发货早于下单（数据异常）</template>
        </span>
      </h2>
      <div class="a-card">
        <BarChart
          :categories="shipLag.cats"
          :series="[{ name: '单数', data: shipLag.counts }]"
          :height="'260px'"
          value-formatter="{value} 单"
          :max-bar-width="28"
          :loading="loading"
          empty-text="本次没有「已发货且两个时刻齐全」的订单 —— 补丁之前发的单没有 ShippedAt"
        />
      </div>

      <p v-if="!rows.length" class="a-empty">{{ loading ? '加载中…' : '没有符合条件的订单。' }}</p>

      <ol v-else class="a-stream">
        <li v-for="r in rows" :key="r.OrderId" class="a-card" :class="{ 'is-abnormal': isAbnormal(r) }">
          <div class="a-card-l1">
            <b class="a-strong a-mono">{{ dash(r.OrderId) }}</b>
            <span class="a-badge" :class="statusBadge(r)"><IconGlyph :name="STATUS_GLYPH[statusBadge(r)] || 'wait'" />{{ statusLabel(r) }}</span>
            <span v-if="isAbnormal(r)" class="a-badge rejected">异常态</span>
            <span class="a-tag">¥{{ dash(r.Price) }}</span>
            <span class="a-tag">×{{ dash(r.Quantity) }}</span>
            <span class="a-grow"></span>
            <span class="a-dim">{{ toLocal(r.CreateTime) }}</span>
          </div>

          <div class="a-kv">
            <div><dt>买家</dt><dd class="a-mono">{{ dash(r.CustomerId) }}</dd></div>
            <div><dt>商家</dt><dd class="a-mono">{{ dash(r.MerchantId) }}</dd></div>
            <div><dt>商品</dt><dd>{{ dash(r.ProductInformation) }}</dd></div>
            <div><dt>结单</dt><dd>{{ toLocal(r.SettlementDate) }}</dd></div>
          </div>

          <!-- 介入 = **沿既有三条 CAS 边**（`pay` / `deliver` / `receive`），不是另一套写规则。
               ⚠️ 这里的 `actionsFor()` 只是**画几个按钮**，**不是闸** —— 真正的守卫是后端
               `OrderStateMachine`（CAS 失败就把原话回显在下面）。所以别把它当权限判据，
               也别因为这里没画就以为「后端做不到」。 -->
          <div class="a-acts">
            <template v-for="a in actionsFor(r)" :key="a">
              <button
                type="button"
                class="a-btn"
                :class="{ 'a-btn-danger': armed === r.OrderId + ':' + a }"
                :disabled="busyId !== null"
                @click="intervene(r, a)"
              >
                <!-- 字形回答的是「这一下按下去是推进还是确认」（2026-10-11 补图标那一笔）：
                     常态 = 推一条边（`ok`）；上膛态 = 再点一次就不可逆了（`no`）—— 与同一行
                     `:class` 里那颗 `a-btn-danger` **同进同出**，不再另给「付款/发货/收货」各配一枚
                     （本仓没有 ¥ 这类字形，硬配就是装饰）。 -->
                <IconGlyph :name="armed === r.OrderId + ':' + a ? 'no' : 'ok'" />{{ armed === r.OrderId + ':' + a ? '再点一次确认：' + ACT_LABEL[a] : ACT_LABEL[a] }}
              </button>
            </template>
            <span v-if="!actionsFor(r).length" class="a-dim">已完成，无可推的边</span>
            <span class="a-grow"></span>
            <button type="button" class="a-btn" @click="toggleDetail(r)">
              {{ openId === r.OrderId ? '收起详情 ↑' : '详情与流转 ↓' }}
            </button>
          </div>

          <template v-if="openId === r.OrderId">
            <p v-if="detailLoading" class="a-empty">详情加载中…</p>
            <template v-else-if="detail && detail.row">
              <div class="a-kv">
                <div><dt>主键 Id</dt><dd class="a-mono">{{ dash(detail.row.Id) }}</dd></div>
                <div><dt>付款</dt><dd class="a-mono">{{ PAY_LABEL(detail.row) }}</dd></div>
                <div><dt>发货</dt><dd class="a-mono">{{ detail.row.DeliveryIf }}</dd></div>
                <div><dt>收货</dt><dd class="a-mono">{{ detail.row.ReceiveIf }}</dd></div>
              </div>
              <!-- 支付 / 物流明细（C3 块4 补丁新增的 7 列）。⚠️ 全列 `dash()` 兜 `null` ——
                   补丁之前落的单这些列**都是 NULL**（不是空串），画成 `—` 而不是空白。 -->
              <div class="a-kv">
                <div><dt>支付方式</dt><dd class="a-mono">{{ dash(detail.row.PaymentMethod) }}</dd></div>
                <div><dt>交易号</dt><dd class="a-mono">{{ dash(detail.row.TradeNo) }}</dd></div>
                <div><dt>付款时间</dt><dd class="a-mono">{{ toLocal(detail.row.PaidAt) }}</dd></div>
                <div><dt>快递公司</dt><dd class="a-mono">{{ dash(detail.row.ExpressCompany) }}</dd></div>
                <div><dt>运单号</dt><dd class="a-mono">{{ dash(detail.row.TrackingNo) }}</dd></div>
                <div><dt>发货时间</dt><dd class="a-mono">{{ toLocal(detail.row.ShippedAt) }}</dd></div>
                <div><dt>收货时间</dt><dd class="a-mono">{{ toLocal(detail.row.ReceivedAt) }}</dd></div>
              </div>
              <h3 class="a-h3">
                状态流转时间线<span class="a-h3n">
                  源：<code>order_state_transition</code> · 按 **OccurredAt** 升序（不是 Id —— 同单的
                  几条是并发 INSERT，Id 不保证与发生顺序一致）
                </span>
              </h3>
              <p v-if="!detail.timeline || !detail.timeline.length" class="a-empty">
                该单没有流转记录（早于 B3 起写的那批，或本单尚未发生任何 CAS 转移）。
              </p>
              <ol v-else class="a-tl">
                <li v-for="(e, i) in detail.timeline" :key="i">
                  <span class="a-mono a-dim">{{ toLocal(e.OccurredAt) }}</span>
                  <span class="a-tag">{{ dash(e.Action) }}</span>
                  <span class="a-mono">{{ dash(e.FromState) }} → {{ dash(e.ToState) }}</span>
                </li>
              </ol>
              <!-- ⚠️ 第二条线 = **模拟物流**（`order_shipping_track`），与上一条**并列不合并**：
                   上一条记「状态怎么变」（CAS 副产品，含 `pay`/`deliver`/`receive` 事件），
                   本一条记「快递走到哪」（四节点**全**由后台任务按 `ShippedAt` 偏移补 —— 2026-10-10 起发货时不再写首条）。
                   两者**末条不对应**：物流「已签收」≠ 订单「已收货」（后者要买家点【收货】）。 -->
              <h3 class="a-h3">
                物流轨迹<span class="a-h3n">
                  源：<code>order_shipping_track</code> · 按 **TrackedAt** 升序 ·
                  ⚠️ 模拟推进（本仓无真实网关），末条「已签收」**不等于**订单已收货
                </span>
              </h3>
              <p v-if="!detail.shippingTrack || !detail.shippingTrack.length" class="a-empty">
                该单没有物流轨迹（尚未发货，或早于本补丁落的单 —— 那批的明细列全 NULL）。
              </p>
              <ol v-else class="a-tl">
                <li v-for="(t, i) in detail.shippingTrack" :key="i">
                  <span class="a-mono a-dim">{{ toLocal(t.TrackedAt) }}</span>
                  <span class="a-tag">{{ dash(t.Status) }}</span>
                  <span>{{ dash(t.Description) }}</span>
                </li>
              </ol>
            </template>
            <p v-else class="a-empty">{{ detailMsg || '详情加载失败。' }}</p>
          </template>
        </li>
      </ol>
    </template>

    <!-- ══ ③ 异常监控（tab 二）═════════════════════════════════════════════════ -->
    <template v-else>
      <h2 class="a-h2">异常监控 <span class="a-h2n">**只读识别** —— 本页没有任何一个数字会让库发生改变</span></h2>

      <p class="a-hint">
        两个超时阈值（{{ monitorH.unpaidHours }}h 未付款 / {{ monitorH.undeliveredHours }}h 已付款未发货）
        是**拍出来的数**、**待业务裁**（本仓没有任何 SLA / 时效配置）—— 所以阈值**随接口回传**，
        本页不写死，改了后端这里自动跟着变。
      </p>

      <div class="a-grid">
        <div class="a-mcard">
          <b>{{ mv(monitor.unpaidTimeout) }}</b>
          <span>超 {{ monitorH.unpaidHours }}h 未付款</span>
          <button type="button" class="a-btn" @click="drill('unpaid')">筛未付款单 →</button>
          <small>⚠️ 该筛**比这个数宽**：含「还没超时的未付款单」，不是同一集合。</small>
        </div>
        <div class="a-mcard">
          <b>{{ mv(monitor.undeliveredTimeout) }}</b>
          <span>超 {{ monitorH.undeliveredHours }}h 已付款未发货</span>
          <button type="button" class="a-btn" @click="drill('paid')">筛已付款未发货 →</button>
          <small>⚠️ 同上：**比这个数宽**，含未超时的。</small>
        </div>
        <div class="a-mcard">
          <b>{{ mv(monitor.abnormalState) }}</b>
          <span>异常态（未付款却已发货/收货）</span>
          <button type="button" class="a-btn a-btn-on" @click="drill('abnormal')">筛异常态单 →</button>
          <small>✅ 这个筛与计数**同一条谓词**（`PayIf=0 AND (DeliveryIf=1 OR ReceiveIf=1)`），是同一集合。</small>
        </div>
        <div class="a-mcard">
          <b>{{ mv(monitor.unfinished) }}</b>
          <span>未完成（未收货）／ 库内共 {{ monitor.total }}</span>
          <button type="button" class="a-btn" @click="drill('')">看全部单 →</button>
          <small>全量视图默认按时间倒序。</small>
        </div>
      </div>

      <p class="a-note">
        ⚠️ 异常态**只识别、不自动处置**：本页不会替谁推进任何一单。要处置就得在「订单全量」里
        对**具体那一条**点介入（点两次确认），并且介入成功后**买卖双方都会被通知**。
      </p>
    </template>
  </section>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { orderAdminList, orderAdminDetail, orderAdminMonitor, orderAdminIntervene } from '../../api/order'
// 图表（可视化段 commit 3；2026-10-11 复合图整改）—— 本页三张：状态分布×时间堆叠柱 /
// GMV·成单数双轴折线 / 发货时效柱。⚠️ 状态分布原先是环图、GMV 原先是单系列折线，
// 整改后 DonutChart 本页不再用 ⇒ **import 同步删掉**（留着就是 `no-unused-vars`）。
import LineChart from '../../components/charts/LineChart.vue'
import BarChart from '../../components/charts/BarChart.vue'; import IconGlyph from '../../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移（本文件被 `slot-registry.md:4551` 引 `:192`，C1 那几笔已经推过一次，别再推第二次）

// ── 时区口径（照 `AccountsPage.vue`，逐字同源）──────────────────────────────
const tzLabel = computed(() => {
  const pad = (n) => String(n).padStart(2, '0')
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

/** 库内文本（`'YYYY-MM-DD HH:MM:SS'`，无时区）→ 浏览器本地时间。解析不了就原样回，不崩、不空白。 */
function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

const dash = (v) => (v === null || v === undefined || String(v).trim() === '' ? '—' : String(v))

// ── 反馈 ─────────────────────────────────────────────────────────────────────
const flash = reactive({ kind: 'ok', text: '' })
function say(kind, text) {
  flash.kind = kind
  flash.text = text
}
/** 后端 `{success:false,msg}` 或异常 → 一句人话。⚠️ **别覆盖 `msg`**：介入失败的三种文案各不相同
 *  （未实现 / 不认识 / CAS 原话），合并成一句「操作失败」就丢掉了唯一的区分信息。 */
function failOf(e) {
  if (e && e.status) return `HTTP ${e.status}`
  return (e && e.message) || String(e)
}

// ── tab（`note()` 写成函数：`rows` / `monitor` 在下面才声明，`const` 有 TDZ）──────
const tab = ref('list')
const TABS = [
  { key: 'list', label: '订单全量', note: () => `${rows.value.length} 行` },
  { key: 'mon', label: '异常监控', note: () => `${monitor.value.abnormalState} 异常态` },
]

// ── 全量视图 ─────────────────────────────────────────────────────────────────
const f = reactive({ CustomerId: '', MerchantId: '', status: '', from: '', to: '', limit: 200 })
const rows = ref([])
const total = ref(0)
/** ⚠️ 截断由**后端**判（`rows.size() >= 生效上限`），前端**不**拿全表 `total` 反推 ——
 *  筛 status 时 `total` **不随筛**，拿它比会**谎报「已截断」**（2026-10-10 修）。 */
const truncated = ref(false)
const loading = ref(false)
/** 正在处理的单（防重复点）。`null` = 空闲。 */
const busyId = ref(null)

async function loadList() {
  loading.value = true
  try {
    const data = await orderAdminList(f)
    if (!data || !data.success) {
      rows.value = []
      total.value = 0
      truncated.value = false
      say('bad', (data && data.msg) || '订单加载失败')
      return
    }
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
    truncated.value = !!data.truncated
  } catch (e) {
    rows.value = []
    total.value = 0
    truncated.value = false
    say('bad', `订单加载失败：${failOf(e)}`)
    console.error('admin orders', e)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  f.CustomerId = ''
  f.MerchantId = ''
  f.status = ''
  f.from = ''
  f.to = ''
  f.limit = 200
  loadList()
}

// ── 异常监控 ─────────────────────────────────────────────────────────────────
/** ⚠️ 阈值从响应里取，**不写死 24 / 48** —— 后端把它们当待裁常量回传（见 `AdminOrderDao`）。 */
const monitor = ref({ unpaidTimeout: 0, undeliveredTimeout: 0, abnormalState: 0, unfinished: 0, total: 0 })
const monitorH = ref({ unpaidHours: 24, undeliveredHours: 48 })
/** 载入失败时置 true —— 状态条上的两个数这时**是 0 但不可信**，须明说（0 与「查不到」长得一样）。 */
const monitorFailed = ref(false)
/** 监控数取值：失败时回 `—` 而不是 `0`（「事实是 0」与「没查出来」必须分得开）。 */
const mv = (v) => (monitorFailed.value ? '—' : v)

async function loadMonitor() {
  try {
    const data = await orderAdminMonitor()
    if (!data || !data.success) {
      monitorFailed.value = true
      say('bad', (data && data.msg) || '异常监控加载失败')
      return
    }
    monitorFailed.value = false
    monitor.value = data.row || monitor.value
    if (data.unpaidHours != null) monitorH.value.unpaidHours = data.unpaidHours
    if (data.undeliveredHours != null) monitorH.value.undeliveredHours = data.undeliveredHours
  } catch (e) {
    monitorFailed.value = true
    say('bad', `异常监控加载失败：${failOf(e)}`)
    console.error('admin order monitor', e)
  }
}

/** 从监控卡跳进全量视图并带上筛选 —— 复用**同一套**筛选状态，不另起一份。 */
function drill(status) {
  f.status = status
  tab.value = 'list'
  loadList()
}

// ── 状态位 → 人话 ────────────────────────────────────────────────────────────
/** 三位状态机的 5 个合法态 + 1 个非法态（与 `AdminOrderDao.statusWhere` 同一套判据，别另编）。 */
function isAbnormal(r) {
  return Number(r.PayIf) === 0 && (Number(r.DeliveryIf) === 1 || Number(r.ReceiveIf) === 1)
}
function statusLabel(r) {
  if (isAbnormal(r)) return '异常态'
  if (Number(r.ReceiveIf) === 1) return '已完成'
  if (Number(r.DeliveryIf) === 1) return '已发货未收货'
  if (Number(r.PayIf) === 1) return '已付款未发货'
  return '未付款'
}
function statusBadge(r) {
  if (isAbnormal(r)) return 'rejected'
  if (Number(r.ReceiveIf) === 1) return 'approved'
  return 'pending'
}
/**
 * 徽章左侧那枚字形（2026-10-11 补图标那一笔）。
 * ⚠️ **键直接取 `statusBadge` 的三档**，不复刻 `statusLabel` 的五态 —— 五态里
 *    「已发货未收货」「已付款未发货」「未付款」在**颜色**上本来就是同一档（`pending`），
 *    图标跟着颜色走，读者才不会看到「同一片黄底上两个不同符号」。
 * ⚠️ `|| 'wait'` 与 `AccountsPage` 的 `statusGlyph` 同口径：认不出的档按「还没处理」看。
 */
const STATUS_GLYPH = { approved: 'ok', rejected: 'no', pending: 'wait' }
const PAY_LABEL = (row) => (Number(row.PayIf) === 1 ? '已付款' : '未付款')

// ── 状态分布 / GMV 趋势 / 发货时效（可视化段 commit 3）──────────────────────────
/**
 * 库内时间 → **UTC 轴毫秒**。与 `toLocal` **同一套解析**（补 `Z`），只是这里要的是数不是字。
 *
 * ⚠️ 两种来路都要吃：`CreateTime` 是 `varchar(50)` 文本（`'YYYY-MM-DD HH:MM:SS'`），而
 *    `ShippedAt` 是**真 `datetime` 列**（`AdminOrderDao:49` 原样投影，未走 `DATE_FORMAT`），
 *    由 Jackson 序列化成 `'YYYY-MM-DDTHH:MM:SS'`。`replace(' ', 'T')` 对后者是空操作
 *    ⇒ 一条解析吃两种形态（页面别处的 `toLocal` 靠的就是同一招）。
 * ⚠️ 解析不了 / 空 ⇒ **`NaN`，不是 0**。调用方必须自己判 —— 把 `NaN` 当 0 用等于凭空
 *    造一个「1970-01-01 发货」的时刻，算出来的时效会是 2 万天。
 */
function msOf(sqlTime) {
  if (!sqlTime) return NaN
  return new Date(`${String(sqlTime).replace(' ', 'T')}Z`).getTime()
}

/** 五态的**固定画序**（与 `statusLabel` 的判据链同序）—— 不按票数排，两次渲染间不许换位。 */
const STATUS_ORDER = ['未付款', '已付款未发货', '已发货未收货', '已完成', '异常态']

/**
 * 本次 `rows` 的**状态分布 × 时间**（每天各状态的订单数，堆叠柱）。
 *
 * <p>2026-10-11 由「状态构成环图」改来（用户 C1 裁决）：环图只给**区间合计**，看不出
 * 流转节奏 —— 同样的「已发货 30 单」，是集中在某两天爆发还是每天稳定几单，环图上是同一张图。
 *
 * <p>⚠️ 五态互斥（`statusLabel` 是一条 `if` 链，每行只落一格）⇒ 每根柱子的高度恒等于
 * 当日的订单数、柱内各段之和也恒等于它，与 `gmv.counts` 同源可对照。
 * <p>⚠️ x 轴**复用 `gmv.days`**（同一套「本地下单日 + 缺日补 0」口径）—— 两张图并排看时
 * 日期必须逐格对齐，各算一套补 0 就会错位。
 * <p>⚠️ 全 0 的状态**整条不画**（图例里挂一条永远贴着轴的状态是噪声），但**画序固定**
 * 为 `STATUS_ORDER`，两次渲染之间不许换位。
 */
const statusByDay = computed(() => {
  const days = gmv.value.days
  const dayIdx = new Map(days.map((d, i) => [d, i]))
  const rowsByStatus = STATUS_ORDER.map(() => days.map(() => 0))
  const at = new Map(STATUS_ORDER.map((k, i) => [k, i]))
  for (const r of rows.value) {
    const i = dayIdx.get(toLocal(r.CreateTime).slice(0, 10))
    if (i === undefined) continue
    rowsByStatus[at.get(statusLabel(r))][i] += 1
  }
  return STATUS_ORDER.map((k, i) => ({ name: k, data: rowsByStatus[i] }))
    .filter((s) => s.data.some((v) => v > 0))
})

/**
 * 本次 `rows` 按**本地下单日**汇总 `Price`（GMV 趋势）。
 *
 * ⚠️ 分桶沿 `toLocal` 再取前 10 字（**本地**日历日），不是直接切 `CreateTime` 前 10 字 ——
 *    后者是 UTC 日，东八区凌晨下的单会被算进前一天，与卡片上显示的下单时刻对不上。
 * ⚠️ 首尾之间缺的日**补 0**：折线的 x 轴必须等距，否则「10-01 直接连到 10-05」会被读成
 *    那几天一直有成交额。跨度 > 180 天不补（那不是趋势，是点阵）。
 * ⚠️ `Price` 是**订单小计**（不是单价×本次数量）—— 与列表卡片上那个 `¥` 同源。
 *    求和后按 2 位小数收一下：浮点累加会漂出 `…9999` 这种尾巴。
 */
const gmv = computed(() => {
  const byDay = new Map()
  const cntByDay = new Map()
  for (const r of rows.value) {
    const day = toLocal(r.CreateTime).slice(0, 10)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) continue
    byDay.set(day, (byDay.get(day) || 0) + (Number(r.Price) || 0))
    cntByDay.set(day, (cntByDay.get(day) || 0) + 1)
  }
  const keys = [...byDay.keys()].sort()
  if (!keys.length) return { days: [], sums: [], counts: [] }
  const start = new Date(`${keys[0]}T00:00:00Z`)
  const span = Math.round((new Date(`${keys[keys.length - 1]}T00:00:00Z`) - start) / 86400000)
  const days = []
  if (span > 180) {
    days.push(...keys)
  } else {
    for (let i = 0; i <= span; i += 1) {
      days.push(new Date(start.getTime() + i * 86400000).toISOString().slice(0, 10))
    }
  }
  return {
    days,
    sums: days.map((d) => Math.round((byDay.get(d) || 0) * 100) / 100),
    // 当日**成单数**（2026-10-11 双 Y 轴整改）：与 `sums` 同一批日、同一次遍历出来的，
    // 不是另扫一遍 —— 两条序列的 x 轴因此天然逐格对齐（见 `statusByDay` 的同一条约束）。
    counts: days.map((d) => cntByDay.get(d) || 0),
  }
})

/** 发货时效的分桶上界（小时）。`Infinity` 兜住最后一档。 */
const SHIP_BUCKETS = [
  { name: '< 6 小时', max: 6 },
  { name: '6–24 小时', max: 24 },
  { name: '24–48 小时', max: 48 },
  { name: '48–72 小时', max: 72 },
  { name: '≥ 72 小时', max: Infinity },
]

/**
 * 本次 `rows` 的发货时效分布 —— `ShippedAt − CreateTime` 落进哪个小时档。
 *
 * ⚠️ 四种「算不出来」的行**一律不塞进桶里**，但各自单独计数、在标题里如实报出来 ——
 *    把它们混进「< 6 小时」会让时效看起来比实际好：
 *    · `undeliv` —— 本次里**根本没发货**的（`DeliveryIf != 1`），谈不上时效；
 *    · `nodate`  —— 已发货但 **`ShippedAt` 为 NULL**（C3 块4 补丁**之前**发的单，
 *                   那一列是新补的、**不回填**）⇒ 这些单的时效**不可考**；
 *    · `bad`     —— `ShippedAt` **早于** `CreateTime`（负数）⇒ 数据异常，不是「很快」。
 * ⚠️ 两个时刻同为库时钟 UTC ⇒ 差值天然正确，与浏览器时区无关；这里只是把它**显示**成
 *    本地日口径（`toLocal` 那一处），差值本身不参与本地化。
 */
const shipLag = computed(() => {
  const counts = SHIP_BUCKETS.map(() => 0)
  let dated = 0
  let nodate = 0
  let undeliv = 0
  let bad = 0
  for (const r of rows.value) {
    if (Number(r.DeliveryIf) !== 1) { undeliv += 1; continue }
    const s = msOf(r.ShippedAt)
    const c = msOf(r.CreateTime)
    if (Number.isNaN(s) || Number.isNaN(c)) { nodate += 1; continue }
    const h = (s - c) / 3600000
    if (h < 0) { bad += 1; continue }
    dated += 1
    const i = SHIP_BUCKETS.findIndex((b) => h < b.max)
    counts[i < 0 ? counts.length - 1 : i] += 1
  }
  return { cats: SHIP_BUCKETS.map((b) => b.name), counts, dated, nodate, undeliv, bad }
})

// ── 介入：沿既有三条 CAS 边（`pay` / `deliver` / `receive`）────────────────────
const ACT_LABEL = { pay: '强制付款', deliver: '强制发货', receive: '强制完成' }

/**
 * 按当前状态位**建议**可推的边。⚠️ **这只是画几个按钮，不是闸** ——
 * 守卫在后端 `OrderStateMachine`（CAS），这里漏画或误画都不会让非法转移通过；
 * 反过来，这里没画也不代表后端做不到（三个动作后端都接）。
 *   · `PayIf=0` ⇒ 可推 `pay`（异常态也走这支：付款是把它拉回合法态的那条边）；
 *   · 已付款未发货 ⇒ 可推 `deliver`；已发货未收货 ⇒ 可推 `receive`；已完成 ⇒ 无边可推。
 */
function actionsFor(r) {
  if (Number(r.PayIf) === 0) return ['pay']
  if (Number(r.DeliveryIf) === 0) return ['deliver']
  if (Number(r.ReceiveIf) === 0) return ['receive']
  return []
}

/** 已「上膛」的那颗按钮（`OrderId:action`）。**点了第一次不会发请求** —— 见 `intervene`。 */
const armed = ref(null)

/**
 * 介入。**两次点击**才算数（第一次上膛、第二次执行）—— 理由是这条链会**改状态 + 通知双方**，
 * 是不可逆的对外动作；而本仓管理页的既有惯例里**没有** `confirm()`/`alert()`
 * （`eslintConfig` 的 `env` 里没有 `browser`，用浏览器全局会触发 `no-undef`），
 * 提示一律走页顶的 flash 条 ⇒ 二次确认就落在按钮自己身上。
 *
 * ⚠️ 成功后**重新拉列表**（状态位变了，卡片上的按钮集也会变）；失败时把后端 `msg`
 * **原样**显示 —— 三种失败（未实现 / 不认识 / CAS 原话）必须分得出来。
 */
async function intervene(r, action) {
  const key = r.OrderId + ':' + action
  if (armed.value !== key) {
    armed.value = key
    say('ok', `再点一次「${ACT_LABEL[action]}」以确认：订单 ${r.OrderId}（成功后会通知买卖双方）`)
    return
  }
  armed.value = null
  busyId.value = r.OrderId
  try {
    const data = await orderAdminIntervene({ OrderId: r.OrderId, Action: action })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '介入失败')
      return
    }
    say('ok', `已${ACT_LABEL[action]}：订单 ${r.OrderId}。通知已投递（见下条口径）。`)
    await loadList()
    if (openId.value === r.OrderId) await loadDetail(r.OrderId)
  } catch (e) {
    say('bad', `介入失败：${failOf(e)}`)
    console.error('admin order intervene', e)
  } finally {
    busyId.value = null
  }
}

// ── 详情 + 时间线（按需加载，一次一条）────────────────────────────────────────
const openId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const detailMsg = ref('')

async function loadDetail(orderId) {
  detailLoading.value = true
  detailMsg.value = ''
  try {
    const data = await orderAdminDetail(orderId)
    if (!data || !data.success) {
      detail.value = null
      detailMsg.value = (data && data.msg) || '详情加载失败'
      return
    }
    detail.value = data
  } catch (e) {
    detail.value = null
    detailMsg.value = `详情加载失败：${failOf(e)}`
    console.error('admin order detail', e)
  } finally {
    detailLoading.value = false
  }
}

function toggleDetail(r) {
  if (openId.value === r.OrderId) {
    openId.value = null
    return
  }
  openId.value = r.OrderId
  detail.value = null
  loadDetail(r.OrderId)
}

onMounted(() => {
  loadList()
  loadMonitor()
})
</script>

<style scoped>
.a-ord {
  max-width: 1120px;
}

/* ── ① 状态条（与 `AccountsPage.vue` / `AuditPage.vue` 同款）──────────────── */
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

/* ── 反馈条 ──────────────────────────────────────────────────────────────── */
.a-flash {
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
  white-space: pre-wrap;
}
.a-flash.ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.a-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}

/* ── 分段标题 ─────────────────────────────────────────────────────────────── */
.a-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin: var(--s7) 0 var(--s4);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.a-ord > .a-h2:first-of-type {
  margin-top: 0;
}
.a-h3 {
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
  margin: var(--s5) 0 var(--s3);
}
.a-h3n {
  margin-left: var(--s2);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}

/* ── 页内 tab（照 `AccountsPage.vue`，逐字同源 —— `c3-modules.md` §五 要求全管理端一套） */
.a-tabs {
  display: flex;
  gap: var(--s2);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--s5);
}
.a-tab {
  appearance: none;
  border: 0;
  background: none;
  cursor: pointer;
  padding: var(--s3) var(--s4);
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.a-tab:hover {
  color: var(--ink);
}
.a-tab.on {
  color: var(--ink);
  font-weight: var(--fw-semi);
  border-bottom-color: var(--brand-ink);
}
.a-tabn {
  margin-left: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-tab.on .a-tabn {
  color: var(--brand-ink);
}

/* ── 筛选条（照 `AccountsPage.vue` / `AuditPage.vue`）────────────────────── */
.a-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  padding: var(--s4) var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
}
.a-f {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-f-narrow {
  max-width: 88px;
}
.a-f-wide {
  flex: 1 1 260px;
  min-width: 200px;
}
.a-f select,
.a-f input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-f-actions {
  display: flex;
  gap: var(--s2);
  margin-left: auto;
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
.a-btn-on {
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
/* 「上膛」态 = 危险动作（同 `AccountsPage.vue` 的 `.a-btn-danger`）—— 与普通按钮在颜色上立刻分得开。 */
.a-btn-danger {
  color: var(--danger);
  background: var(--danger-ground);
  border-color: var(--danger);
}
.a-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.a-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  margin: var(--s2) 0 var(--s5);
}
.a-hint code,
.a-h3n code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.a-note {
  font-size: var(--fs-xs);
  color: var(--warning);
  background: var(--warning-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-top: var(--s3);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6);
  text-align: center;
}

/* ── 卡片式列表 ──────────────────────────────────────────────────────────── */
.a-stream {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  margin-top: var(--s3);
}
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
}
/* 异常态的行：左侧一道 `--danger`。⚠️ 是**边框**不是「侧边强调条」——
   `tokens.css` 那条房规禁的是「选中态用侧边条」，这里是**状态语义**（同 `.a-card.banned`）。 */
.a-card.is-abnormal {
  border-left: 3px solid var(--danger);
}
.a-card-l1 {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
}
.a-strong {
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.a-grow {
  flex: 1;
}
.a-tag {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border-radius: var(--r1);
  padding: 2px 6px;
}
.a-badge {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.a-badge.pending,
.a-badge.warn {
  color: var(--warning-deep);
  background: var(--warning-ground);
  border-color: transparent;
}
.a-badge.approved {
  color: var(--success-deep);
  background: var(--success-ground);
  border-color: transparent;
}
.a-badge.rejected {
  color: var(--danger);
  background: var(--danger-ground);
  border-color: transparent;
}
.a-dim {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-mono {
  font-family: var(--font-mono);
}

/* 字段清单：`dt` 标签 + `dd` 值，两列流式排布（宽窄都能读，不像宽表那样在窄档塌掉）。 */
.a-kv {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--s2) var(--s5);
  margin: var(--s3) 0 0;
}
.a-kv > div {
  display: flex;
  gap: var(--s2);
  min-width: 0;
  font-size: var(--fs-xs);
}
.a-kv dt {
  flex: none;
  color: var(--ink-3);
  min-width: 64px;
}
.a-kv dd {
  margin: 0;
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.a-acts {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  margin-top: var(--s3);
}

/* ── 状态流转时间线 ───────────────────────────────────────────────────────── */
.a-tl {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  margin: 0;
}
.a-tl li {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  background: var(--surface-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}

/* ── 异常监控四卡 ─────────────────────────────────────────────────────────── */
.a-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--s4);
}
.a-mcard {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
}
.a-mcard b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semi);
  line-height: 1.1;
  color: var(--ink);
}
.a-mcard span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-mcard small {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.6;
}
</style>
