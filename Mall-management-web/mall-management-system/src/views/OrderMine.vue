<template>
  <!--
    C3 块 4〈订单块〉「购物车块」的第 2 个 tab：**我的订单**（我买的单：未成交 + 已成交）。

    ⚠️ 本页**不是**壳的子路由页面 —— 它的宿主是 `views/merchant/OrderPane.vue`（购物车块容器，
    按 `route.query.tab === 'orders'` 渲染本组件）。故本组件**不画 tab 头**（容器画）。

    ⚠️ 两个数据源的字段面**不一样，这是本页分两块渲染的唯一理由**（不是排版喜好）：
      · 「进行中」= `/order/orderSelect`（`OrderDao.selectActiveByCustomer`）
        —— 有 `Price` / 三个状态位 / `CreateTime` ⇒ 能画金额、状态、付款/收货。
      · 「已完成」= `/order/historicalOrdersSelect`（`OrderDao.selectHistoryByCustomer`）
        —— **只 SELECT 六列**：`OrderId, MerchantId, ProductInformation, Images,
        SettlementDate, Quantity`，**没有 `Price`、没有状态位、没有 `CreateTime`**。
    把两块并成一张表 = 已完成那一半的「金额」「状态」列只能写 `—` 或假造 ⇒ 本页**不并**，
    各画各的列。⚠️ 想补齐字段面 = 改 `selectHistoryByCustomer` 的 SELECT 列 = **改契约**
    （硬线，须先问），不在本笔内。
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
            <div class="cen"><span class="st" :class="stateOf(order)[0]">{{ stateOf(order)[1] }}</span></div>
            <div class="op">
              <button
                v-if="order.PayIf !== 1"
                class="act"
                @click="handlePay(order)"
              >付款</button>
              <button
                v-if="canReceive(order)"
                class="act"
                @click="handleReceive(order)"
              >收货</button>
              <span v-if="order.PayIf === 1 && !canReceive(order)" class="dash">等待商家发货</span>
            </div>
          </div>
          <p class="hint">⚠️ 数量列显示的是下单时记下的件数；`Quantity` 列是 C3 块 4 新加的，此前落库的行一律回填成 <b>1</b>。</p>
        </section>

        <!-- ── ② 已完成 ──────────────────────────────────────────────────────── -->
        <section v-if="historyOrders.length > 0" class="block">
          <h2 class="block-h">已完成 <span class="n">{{ historyOrders.length }}</span></h2>

          <div class="thead done">
            <span></span><span>商品</span><span>订单号</span><span>商家 ID</span>
            <span class="r">数量</span><span>结单日期</span>
          </div>
          <div v-for="order in historyOrders" :key="order.OrderId" class="trow done">
            <div class="thumb"><img v-if="order.Images" :src="order.Images" alt="商品图片" /></div>
            <div class="pn" :title="order.ProductInformation">{{ order.ProductInformation }}</div>
            <div class="oid">{{ order.OrderId }}</div>
            <div class="mid">{{ order.MerchantId }}</div>
            <div class="num">{{ quantityOf(order) }}</div>
            <div class="time">{{ formatDateTime(order.SettlementDate) }}</div>
          </div>
          <p class="hint">⚠️ 这一块**没有金额列**：`historicalOrdersSelect` 的 SELECT 里就没有 `Price`（见本文件头）。要补 = 改契约，没在本笔里顺手动。</p>
        </section>
      </template>
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
import { orderSelect, orderHistory, orderPay, orderReceive } from '../api/order'
import { resolveBuyerId } from '../api/auth'

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

const formatDateTime = (dateStr) => {
  if (!dateStr) return ''
  const date = new Date(dateStr)
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
const STATE_OF = {
  '0,0,0': ['draft', '未付款'],
  '1,0,0': ['warn', '待发货'],
  '1,1,0': ['info', '已发货'],
  '1,0,1': ['err', '已收货·未发货'],
  '1,1,1': ['ok', '已完成']
}
const stateOf = (order) => STATE_OF[[order.PayIf, order.DeliveryIf, order.ReceiveIf].join(',')]
  || ['draft', '未知状态']

const canReceive = (order) => order.PayIf === 1 && order.DeliveryIf === 1 && order.ReceiveIf !== 1

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

const handlePay = async (order) => {
  try {
    const result = await orderPay(order.OrderId)
    if (result.success) {
      alert('付款成功！')
      await load()
    } else {
      alert('付款失败：' + (result.msg || '未知错误'))
    }
  } catch (err) {
    console.error('付款请求出错', err)
    alert('网络错误，请稍后重试')
  }
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
  grid-template-columns: 56px minmax(0, 1fr) 118px 88px 56px 160px;
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
  .thead.done, .trow.done { grid-template-columns: 48px minmax(0, 1fr) 104px 80px 44px 132px; }
}
</style>
