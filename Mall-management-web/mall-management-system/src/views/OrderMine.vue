<template>
  <!--
    C3 块 4〈订单块〉「购物车块」的第 2 个 tab：**我的订单**（我买的单：未成交 + 已成交）。

    ⚠️ 本页**不是**壳的子路由页面 —— 它的宿主是 `views/merchant/OrderPane.vue`（购物车块容器，
    按 `route.query.tab === 'orders'` 渲染本组件）。故本组件**不画 tab 头**（容器画）。

    ⚠️ 两个数据源的字段面**不一样，这是本页分两块渲染的唯一理由**（不是排版喜好）：
      · 「进行中」= `/order/orderSelect`（`OrderDao.selectActiveByCustomer`）
        —— 有 `Price` / 三个状态位 / `CreateTime` ⇒ 能画金额、状态、付款/收货。
      · 「已完成」= `/order/historicalOrdersSelect`（`OrderDao.selectHistoryByCustomer`）
        —— 批四第二批 commit 4 起**加性**多回 `Price` / `RefundStatus` / `RefundRetryCount` /
        `RefundReason` / `RefundAmount` / `RefundHandledAt`（旧六列一个不删）；仍**没有**三位状态位
        与 `CreateTime`。
    把两块并成一张表 = 已完成那一半的状态位列只能写 `—` 或假造 ⇒ 本页**不并**，
    各画各的列。已完成块本笔只加一列**只读**售后 chip；金额展示仍归「历史订单」页
    （两页的分工不变，避免同一事实两处各画一遍）。
  -->
  <div class="ordermine-page">
    <div class="wrap">
      <nav class="crumb">
        <span>首页</span>
        <span class="sp">/</span>
        <span>购物车</span>
        <span class="sp">/</span>
        <span class="cur">我的订单</span>
      </nav>

      <div class="h1row">
        <h1 class="page-h">我的订单 <span class="n">共 {{ totalCount }} 单 · 进行中 {{ activeOrders.length }} 单</span></h1>
      </div>

      <div v-if="loading" class="empty">
        <div class="p">加载中…</div>
      </div>

      <div v-else-if="totalCount === 0" class="empty">
        <div class="big">还没有订单</div>
        <div class="p">在商品详情页点「立即购买」，或用购物车结算后就会出现在这里。</div>
        <router-link class="go2" :to="cartTabPath">去购物车看看</router-link>
      </div>

      <template v-else>
        <!-- ── ① 进行中（未成交）──────────────────────────────────────────────── -->
        <section v-if="activeOrders.length > 0" class="block">
          <h2 class="block-h">进行中 <span class="n">{{ activeOrders.length }}</span></h2>

          <div class="thead">
            <span></span><span>商品</span><span>订单号</span>
            <span class="r">数量</span><span class="r">金额</span><span class="cen">状态</span><span class="r">操作</span>
          </div>
          <div v-for="order in activeOrders" :key="order.OrderId" class="trow">
            <div class="thumb"><img v-if="order.Images" :src="order.Images" alt="商品图片" /></div>
            <div class="pn" :title="order.ProductInformation">{{ order.ProductInformation }}</div>
            <div class="oid">{{ order.OrderId }}</div>
            <div class="num">{{ quantityOf(order) }}</div>
            <div class="money">¥{{ formatPrice(order.Price) }}</div>
            <div class="cen"><span class="st" :class="stateOf(order)[0]"><IconGlyph :name="STATE_GLYPH[stateOf(order)[0]] || 'wait'" />{{ stateOf(order)[1] }}</span></div>
            <div class="op">
              <button
                v-if="order.PayIf !== 1 && !order.CancelledAt"
                class="act"
                @click="openPay(order)"
              ><IconGlyph name="ok" />付款</button>
              <button
                v-if="canReceive(order)"
                class="act"
                @click="handleReceive(order)"
              ><IconGlyph name="ok" />收货</button>
              <!-- ⚠️ C3 块4 补丁：**发货之后**才有物流可看（`DeliveryIf === 1`）——
                   在「待发货」上画它只会弹一个空时间线。收货后仍保留（`ReceiveIf === 1` 也是 `DeliveryIf === 1`）。 -->
              <button
                v-if="order.DeliveryIf === 1"
                class="act"
                @click="openDetail(order)"
              ><IconGlyph name="orders" />物流</button>
              <span v-if="order.PayIf === 1 && !canReceive(order) && order.DeliveryIf !== 1" class="dash">等待商家发货</span>
            </div>
          </div>
          <p class="hint">⚠️ 数量列显示的是下单时记下的件数；`Quantity` 列是 C3 块 4 新加的，此前落库的行一律回填成 <b>1</b>。</p>
        </section>

        <!-- ── ② 已完成 ──────────────────────────────────────────────────────── -->
        <section v-if="historyOrders.length > 0" class="block">
          <h2 class="block-h">已完成 <span class="n">{{ historyOrders.length }}</span></h2>

          <div class="thead done">
            <span></span><span>商品</span><span>订单号</span><span>商家 ID</span>
            <span class="r">数量</span><span>结单日期</span><span class="cen">售后</span>
          </div>
          <div v-for="order in historyOrders" :key="order.OrderId" class="trow done">
            <div class="thumb"><img v-if="order.Images" :src="order.Images" alt="商品图片" /></div>
            <div class="pn" :title="order.ProductInformation">{{ order.ProductInformation }}</div>
            <div class="oid">{{ order.OrderId }}</div>
            <div class="mid">{{ order.MerchantId }}</div>
            <div class="num">{{ quantityOf(order) }}</div>
            <div class="time">{{ formatDateTime(order.SettlementDate) }}</div>
            <div class="cen">
              <!-- 只读 chip：申请 / 处理都在「历史订单」页（那里有原因弹窗与重试判据）。 -->
              <span v-if="refundText(order)" class="rf-chip" :class="`s${Number(order.RefundStatus) || 0}`">{{ refundText(order) }}</span>
              <span v-else class="dash">—</span>
            </div>
          </div>
          <p class="hint">⚠️ 这一块**仍不画金额列**：`historicalOrdersSelect` 本笔已加性回 `Price`，但金额展示归「历史订单」页；本页只加只读售后 chip，两页分工不变。</p>
        </section>
      </template>
    </div>

    <!-- ══ 收银台弹窗（C3 块4 补丁）══════════════════════════════════════════════
         ⚠️ 2026-10-10 抽出共享组件 `components/CashierDialog.vue`：商品详情页的【立即购买】也要走**同一个**
         收银台（Bug 1）。本页只负责「哪一单」(`payFor`) 与「付完做什么」(`onPaid`)，弹窗与支付逻辑在组件里。 -->
    <CashierDialog :order="payFor" @close="payFor = null" @paid="onPaid" />

    <!-- ══ 物流详情弹窗（C3 块4 补丁）═══════════════════════════════════════════
         ⚠️ 标题写「物流轨迹」而**不是**「订单状态」：`shippingTrack` 是**模拟物流**，
         末条「已签收」说的是**快递到了**，不是**订单已收货**（那要买家自己点【收货】）。 -->
    <div v-if="detailFor" class="scrim" @click="detailFor = null">
      <div class="dlg" @click.stop>
        <div class="dlg-h">
          <span>物流轨迹</span>
          <button class="x" @click="detailFor = null" aria-label="关闭">×</button>
        </div>
        <div class="dlg-b">
          <p class="pay-oid">订单号 <span class="mono">{{ detailFor.OrderId }}</span></p>
          <!-- ⚠️ 这两列**只在数据回来之后**才画：请求失败时画「— / —」等于**谎报「这单没有快递信息」**
               （用户 2026-10-10 就是这么读的：「可能是数据没拿到，或字段名取错」）⇒ 失败时整行不出现，
               由下面的错误态说话。 -->
          <p v-if="detailRow.OrderId" class="pay-oid">
            承运 / 单号
            <span class="mono">{{ detailRow.ExpressCompany || '—' }} / {{ detailRow.TrackingNo || '—' }}</span>
            <span class="dash">（未填快递信息的单为空 —— 如管理员强制发货，见 tech-debt TD-31）</span>
          </p>
          <div v-if="detailLoading" class="tl-empty">加载中…</div>
          <!-- ⚠️ 失败**必须有自己的态**（用户 2026-10-10 报的 Bug 2）：改前只有 `alert` + 回落成
               「暂无物流轨迹。」⇒ 「请求失败」与「确实没有轨迹」在界面上**不可区分**，而 alert 弹着时
               它身后的弹窗还停在「加载中…」（alert 阻塞渲染，`finally` 里的 `detailLoading = false` 要等用户点掉
               才生效）—— 看起来就是「一直转」。 ⇒ 改成**就地报错 + 重试**，一个阻塞式弹框都不发。 -->
          <div v-else-if="detailError" class="tl-empty tl-err">
            物流加载失败：{{ detailError }}
            <button class="act" @click="openDetail(detailFor)">重试</button>
          </div>
          <ol v-else-if="detailTrack.length" class="tl">
            <li v-for="(t, i) in detailTrack" :key="i" :class="{ last: i === detailTrack.length - 1 }">
              <span class="tl-dot"></span>
              <div class="tl-main">
                <span class="tl-desc">{{ t.Description || t.Status }}</span>
                <span class="tl-time mono">{{ formatDateTime(t.TrackedAt) }}</span>
              </div>
            </li>
          </ol>
          <div v-else class="tl-empty">暂无物流轨迹。</div>
          <p class="pay-note">⚠️ 轨迹由后台任务按发货时间**模拟推进**（秒级演示阈值 15/30/45/60 秒），末条「已签收」表示快递送达，与「订单收货」是两件事。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/* ⚠️ 这对 `eslint-disable/enable no-undef` 是**必需**的，不是偷懒 —— 本仓 `eslint-plugin-vue` 是
   **8.7.1**（`package.json`），它不认识 `<script setup>` 的编译宏（`defineProps` / `defineEmits`），
   且 `package.json` 的 `eslintConfig.env` 只有 `node: true` ⇒ `alert` 这类浏览器全局也被判
   `no-undef`、`npm run build` 直接红。先例是**两个同胞页**：`OrderCenter.vue:162` 与
   `ShoppingCart.vue:220`（`GoodsSelect.vue:156` / `HomeTab.vue:15` 也引这条）。 */
/* eslint-disable no-undef */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { handleApiError } from '../api/http'
import { orderSelect, orderHistory, orderReceive, orderDetail } from '../api/order'
import { resolveBuyerId } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移（`slot-registry.md:4551` 按 `:146` 钉住本文件）
import CashierDialog from '../components/CashierDialog.vue'   // ⚠️ 2026-10-10：收银台抽成共享组件（商品详情页【立即购买】也要走同一个）`orderPay` 随之**从本页的 import 里退场**

// 购物车 tab 的地址 —— 两壳路径不同（`/customer/cart` vs `/merchant/order/cart`），不写死。
// ⚠️ 判据取 `route.path` 前缀而**不是** `route.name` / `route.meta`：本页由容器（`OrderPane.vue`）
//     渲染，`route` 是**宿主路由**那条记录，两壳各一条 ⇒ 路径前缀是唯一稳定的判据。
const route = useRoute()

const activeOrders = ref([])
const historyOrders = ref([])
const loading = ref(false)

const totalCount = computed(() => activeOrders.value.length + historyOrders.value.length)
const cartTabPath = computed(() =>
  route.path.startsWith('/merchant') ? '/merchant/order/cart?tab=cart' : '/customer/cart?tab=cart'
)

const getBuyerId = () => resolveBuyerId()

const formatPrice = (price) => Number(price || 0).toFixed(2)

const formatDateTime = (dateStr) => {   // ⚠️ **2026-10-10（用户报：同一条单「轨迹 22:27:51 vs 订单列表 14:29:16」，差约 8 小时）**：后端**全部**时间列（`SettlementDate` 由 SQL `NOW()`、`TrackedAt` 由库时钟 `NOW(3)`/`DATE_ADD`）都是**库时钟**，而库时钟是 **UTC**（VM / a3 实测 `@@system_time_zone=UTC`，记录见 `context-packs/c3-block-1-infra.md#pack-c3-block-1-infra-frontend-nav`）⇒ 交回的是 `'YYYY-MM-DD HH:mm:ss'` 这种**无时区文本**。少补末尾那个 `Z`，`new Date()` 就按**浏览器本地时区**解释它 —— 等于把 UTC 值当成本地值，整整差一个偏移，而且**错得很安静**（格式完全正常，肉眼看不出来）。手法逐字照管理端的 `views/admin/AuditPage.vue:236` 的 `toLocal`（原 190 行 —— 2026-10-11 收口按 refs-scan 重指）（那 5 个管理页都有，**本页此前漏了这一步**）。
  if (!dateStr) return ''
  const date = new Date(`${String(dateStr).replace(' ', 'T')}Z`)
  if (Number.isNaN(date.getTime())) return String(dateStr)
  const p = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} `
    + `${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`
}

// ⚠️ 老行（块 4 之前落的）`Quantity` 是回填默认值 1，**不是真实件数** —— 这一点在页面上
//    用一行 hint 明说，不让「1」冒充事实（判据同 `CartService.checkout` 的注释）。
const quantityOf = (order) => {
  const n = Number(order.Quantity)
  return Number.isFinite(n) && n > 0 ? n : 1
}

// 三位 → 状态（与 `OrderState.java` 的 5 个具名常量一一对应；同 `OrderCenter.vue` 那份表）。
// ⚠️ C3 块4 补丁·第 2 笔：**先判 `CancelledAt`** —— 取消的单三位仍是 `(0,0,0)`，
//    与「未付款」**一模一样**，光看三位分不出来。这是「第 4 态」在前端的唯一入口。
const STATE_OF = {
  '0,0,0': ['draft', '未付款'],
  '1,0,0': ['warn', '待发货'],
  '1,1,0': ['info', '已发货'],
  '1,0,1': ['err', '已收货·未发货'],
  '1,1,1': ['ok', '已完成']
}
/** 状态徽章左侧那枚字形（2026-10-11 补图标那一笔）。
 *  ⚠️ 键取的是 **`STATE_OF` 的类名档**（`draft/warn/info/err/ok`），不是 5 个具名态 —— 每档一枚，
 *     与 `.st.<档>` 的底色**一一对应**（读者不会看到同一片底色上两个不同符号）。
 *     `draft` 与 `warn` 同为 `wait` 但底色不同（灰 / 黄）：「未付款」与「待发货」都是**还没成交**，
 *     差别在「谁该动」而不在「完没完」，那是文字的事，图标只回答后者。 */
const STATE_GLYPH = { ok: 'ok', err: 'no', info: 'orders', warn: 'wait', draft: 'wait' }
const stateOf = (order) => {
  if (order.CancelledAt) return ['err', '已取消']
  return STATE_OF[[order.PayIf, order.DeliveryIf, order.ReceiveIf].join(',')]
    || ['draft', '未知状态']
}

const canReceive = (order) => order.PayIf === 1 && order.DeliveryIf === 1 && order.ReceiveIf !== 1

// 已完成单的只读售后态（批四第二批 commit 4）；0 = 没申请过 ⇒ 不画 chip。
const REFUND_TEXT = { 1: '待商家审核', 2: '商家已拒绝', 3: '平台处理中', 4: '平台已拒绝', 5: '已退款' }
const refundText = (order) => REFUND_TEXT[Number(order.RefundStatus) || 0] || ''

const load = async () => {
  const customerId = getBuyerId()
  if (!customerId) {
    console.warn('未登录，无法获取订单')
    return
  }
  loading.value = true
  try {
    // ⚠️ 两路并发：后端是**两条 SQL**（未成交 / 已成交），没有「一次取全量」的端点。
    const [active, history] = await Promise.all([
      orderSelect(customerId),
      orderHistory(customerId)
    ])
    activeOrders.value = active.success ? (active.list || []) : (console.error('获取订单失败', active.msg), [])
    historyOrders.value = history.success ? (history.list || []) : (console.error('获取历史订单失败', history.msg), [])
  } catch (err) {
    handleApiError(err, '请求出错')
  } finally {
    loading.value = false
  }
}

/* ── 收银台（C3 块4 补丁；2026-10-10 抽成共享组件）─────────────────────────────
   ⚠️ 弹窗本体（支付方式三选一 + `orderPay` + 成功回显 `TradeNo`）已搬进
   `components/CashierDialog.vue` —— 商品详情页的【立即购买】也要用它（Bug 1）。
   本页只剩两件事：**哪一单**（`payFor`）与**付完做什么**（`onPaid` 刷新列表）。 */
const payFor = ref(null)          // 非 null ⇒ 收银台开着，值 = 那一单

const openPay = (order) => { payFor.value = order }

/** 支付成功回调：关窗 + 重载列表（这单从「待付款」挪进「已完成」或换状态位）。 */
const onPaid = async () => {
  payFor.value = null
  await load()
}

const handleReceive = async (order) => {
  try {
    const result = await orderReceive(order.OrderId)
    if (result.success) {
      alert('收货成功！')
      await load()
    } else {
      alert('收货失败：' + (result.msg || '未知错误'))
    }
  } catch (err) {
    console.error('收货请求出错', err)
    alert('网络错误，请稍后重试')
  }
}

/* ── 物流详情（C3 块4 补丁 · A5）─────────────────────────────────────────────
   ⚠️ 数据来自 `/order/orderDetail`（**买家侧**，只有 `row` + `shippingTrack`，**没有**
   `timeline`）—— 买家看的是物流，不是后端的状态机轨迹。 */
const detailFor = ref(null)
const detailRow = ref({})
const detailTrack = ref([])
const detailLoading = ref(false)
const detailError = ref('')       // 非空 ⇒ 显示错误态（**不再**用 alert：alert 会阻塞渲染，让弹窗卡在「加载中…」）

const openDetail = async (order) => {
  detailFor.value = order
  detailRow.value = {}
  detailTrack.value = []
  detailError.value = ''
  detailLoading.value = true
  try {
    const result = await orderDetail(order.OrderId)
    if (result.success) {
      detailRow.value = result.row || {}
      detailTrack.value = result.shippingTrack || []
    } else {
      detailError.value = result.msg || '未知错误'
    }
  } catch (err) {
    console.error('物流详情请求出错', err)
    // ⚠️ 传输层错误（超时 / 404 / 网断）到这里：把**类型**也写进界面，别一律说「网络错误」
    //    —— `HttpError` 带 `status`，404 = 后端没有这个端点（补丁未部署），与「网断了」不是一回事。
    detailError.value = err && err.status ? `请求失败（HTTP ${err.status}）` : '网络错误'
  } finally {
    detailLoading.value = false
  }
}

onMounted(load)
/* eslint-enable no-undef */
</script>

<style scoped>
/* 滚动盒由本页提供（壳的 `.cart-container` / `.order-container` 都是 `height:100%;overflow:hidden`
   —— 与 `ShoppingCart.vue` / `OrderCenter.vue` 同一条既有依据）。
   ⚠️ 本页**没有** absolute 子元素 ⇒ 不需要 `position:relative`。 */
.ordermine-page {
  height: 100%;
  overflow-y: auto;
  background: var(--bg);
}
.wrap {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s6) var(--s5) var(--s8);
}

.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--ink-3); }
.crumb .sp { font-style: normal; padding: 0 var(--s2); }
.crumb .cur { color: var(--ink-2); }

.h1row { margin-top: var(--s4); }
.page-h {
  display: flex;
  align-items: baseline;
  gap: var(--s4);
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--fs-xl);
  font-weight: var(--fw-med);
  line-height: 1.15;
}
.page-h .n { font-family: var(--font); font-size: var(--fs-xs); color: var(--ink-3); }

.block { margin-top: var(--s6); }
.block-h {
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  color: var(--ink);
  margin: 0 0 var(--s3);
}
.block-h .n { font-family: var(--font); font-size: var(--fs-xs); color: var(--ink-3); margin-left: var(--s2); }
.hint { margin-top: var(--s3); font-size: var(--fs-xs); color: var(--ink-3); line-height: 1.6; }

/* 列宽与 `OrderCenter.vue` 的表格同一套语言（表头/表体共用一条 grid ⇒ 天然对齐）。
   ⚠️ 两块的列数**不同**（进行中 7 列 / 已完成 6 列）⇒ 两条 grid 规则，各自表头表体共用。 */
.thead, .trow {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 118px 56px 92px 100px 152px;
  align-items: center;
  gap: var(--s4);
}
.thead.done, .trow.done {
  grid-template-columns: 56px minmax(0, 1fr) 118px 88px 56px 160px 88px;
}
.thead {
  padding-bottom: var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border-bottom: 1px solid var(--line-2);
}
.trow { padding: var(--s4) 0; border-bottom: 1px solid var(--line); }

.r { text-align: right; }
.cen { text-align: center; }

.thumb {
  width: 56px;
  aspect-ratio: 1/1;
  border-radius: var(--r1);
  background: var(--img);
  border: 1px solid var(--line);
  overflow: hidden;
}
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }

.pn {
  min-width: 0;
  font-size: var(--fs-sm);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oid { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-2); }
.mid { font-size: var(--fs-xs); color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.num, .time { font-size: var(--fs-xs); color: var(--ink-2); font-variant-numeric: tabular-nums; }
.time { font-family: var(--font-mono); color: var(--ink-3); white-space: nowrap; }
.money {
  text-align: right;
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  font-variant-numeric: tabular-nums;
}
.dash { color: var(--ink-3); font-size: var(--fs-xs); }

/* 状态 chip：与 `OrderCenter.vue` 同一套语言（5 态、同字号/内距/圆角，只差色相）。 */
.st {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--r1);
  font-size: var(--fs-xs);
  line-height: 1.6;
  white-space: nowrap;
}
.st.draft { background: var(--surface-3); color: var(--ink-2); }
.st.warn { background: var(--warning-ground); color: var(--warning-deep); }
.st.info { background: var(--info-ground); color: var(--info-deep); }
.st.err { background: var(--danger-ground); color: var(--danger-deep); }
.st.ok { background: var(--success-ground); color: var(--success-deep); }

/* 已完成块的只读售后 chip（批四第二批 commit 4）：与状态 chip 同形状，色相按「要不要人处理」分。 */
.rf-chip {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--r1);
  font-size: var(--fs-xs);
  line-height: 1.6;
  white-space: nowrap;
}
.rf-chip.s1, .rf-chip.s3 { background: var(--info-ground); color: var(--info-deep); }
.rf-chip.s2, .rf-chip.s4 { background: var(--surface-3); color: var(--ink-2); }
.rf-chip.s5 { background: var(--success-ground); color: var(--success-deep); }

.op { display: flex; align-items: center; justify-content: flex-end; gap: var(--s3); }
.act {
  height: 30px;
  padding: 0 var(--s4);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--brand-ink);
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  white-space: nowrap;
  cursor: pointer;
}
.act:hover { border-color: var(--brand); }

.empty { padding: var(--s8) 0; text-align: center; }
.empty .big { font-family: var(--font-display); font-size: var(--fs-lg); color: var(--ink-2); }
.empty .p { margin-top: var(--s3); font-size: var(--fs-sm); color: var(--ink-3); }
.empty .go2 {
  display: inline-block;
  margin-top: var(--s5);
  height: 40px;
  line-height: 40px;
  padding: 0 var(--s6);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  text-decoration: none;
}

/* 窄屏：只收紧列宽，**不改结构**（同 `OrderCenter.vue` 的口径）。 */
@media (max-width: 1120px) {
  .thead, .trow { grid-template-columns: 48px minmax(0, 1fr) 104px 44px 80px 92px 132px; gap: var(--s3); }
  .thead.done, .trow.done { grid-template-columns: 48px minmax(0, 1fr) 104px 80px 44px 132px 80px; }
}

/* ══ 收银台 / 物流弹窗（C3 块4 补丁）═══════════════════════════════════════════
   ⚠️ 用 `position: fixed` 而不是 absolute：`.ordermine-page` 是滚动盒、**没有** position:relative
   ⇒ absolute 会锚到视口外；fixed 直接锚视口，与本页既有的「无 absolute 子元素」口径不冲突。 */
.scrim {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s5);
}
.dlg {
  width: min(520px, 100%);
  max-height: 86vh;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.22);
}
.dlg-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--line);
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.dlg-h .x {
  border: 0;
  background: transparent;
  font-size: var(--fs-lg);
  line-height: 1;
  color: var(--ink-3);
  cursor: pointer;
}
.dlg-b { padding: var(--s5); }

/* ⚠️ 收银台专用的那几条样式（`.pay-amt` / `.pay-methods` / `.pm` / `.dlg-f` / `.act.primary`）
   已随弹窗一起搬进 `components/CashierDialog.vue`（2026-10-10），本页**不再保留** ——
   留着就是**永远不命中的死规则**，而 `.dlg-f` 之类还容易让人以为本页有第二个带页脚的弹窗。
   下面这套是**本页仍要用的**：物流弹窗（`.pay-oid` / `.pay-note` / `.mono`）与全页公用件。 */
.pay-oid { margin: var(--s2) 0 0; font-size: var(--fs-xs); color: var(--ink-3); }
.mono { font-family: var(--font-mono); color: var(--ink-2); }
.pay-note {
  margin: var(--s4) 0 0;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
}

/* 物流时间线：竖线 + 圆点，纯 CSS（不引图标库 —— 本仓无组件库，惯例是逐页 scoped）。 */
.tl { list-style: none; margin: var(--s4) 0 0; padding: 0; }
.tl li { position: relative; display: flex; gap: var(--s4); padding: 0 0 var(--s4) var(--s2); }
.tl li::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 14px;
  bottom: 0;
  width: 1px;
  background: var(--line-2);
}
.tl li.last::before { display: none; }
.tl-dot {
  flex: 0 0 auto;
  width: 9px;
  height: 9px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--brand);
}
.tl-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.tl-desc { font-size: var(--fs-sm); color: var(--ink); }
.tl-time { font-size: var(--fs-xs); color: var(--ink-3); }
.tl-empty { padding: var(--s4) 0; font-size: var(--fs-sm); color: var(--ink-3); }
/* 物流失败态（Bug 2）：失败**要说出来**，不能与「确实没有轨迹」同脸。 */
.tl-err { display: flex; align-items: center; gap: var(--s3); color: var(--danger-deep); }
.tl-err .act { flex: 0 0 auto; }
</style>
