<template>
  <div class="cart-page">
    <!-- ⚠️ 稿外**活**控件（全页唯一一枚）：商家壳「订单中心 ⇄ 购物车」两态开关。
         客户宿主 `/customer/cart` 传 `props:{showSwitch:false}`（router/index.js:61）⇒ 不渲染
         （稿 ShoppingCart-customer.html:14-15 明写「客户侧不画」）；商家宿主
         `/merchant/order/cart` 走 OrderPane.vue:3 的 `v-else` 那支，靠它切回订单面 ——
         **删掉它 = 商家没有回程**，故照旧保留（稿没画它，登记见 slot-registry.md §三）。 -->
    <div v-if="showSwitch" class="switch-wrapper">
      <div
        class="switch-container"
        :class="{ 'switch-left': orderTab === 'order', 'switch-right': orderTab === 'cart' }"
        @click="handleToggle"
      >
        <div class="switch-slider">
          <span class="slider-text left">📋</span>
          <span class="slider-text right">🛒</span>
        </div>
      </div>
    </div>

    <div class="wrap">
      <!-- 面包屑：与全部内页同款（分隔符与文字同色 `--ink-3`，稿 :308-312）。
           商家宿主多一级「订单中心」（OrderCenter-merchant.html:755）。
           ⚠️ 稿里是 `<a>`（无 href、不可点）；本页不落成控件，用 `<span>` —— 不画死控件。 -->
      <nav class="crumb">
        <span>首页</span>
        <span class="sp">/</span>
        <template v-if="showSwitch">
          <span>订单中心</span>
          <span class="sp">/</span>
        </template>
        <span class="cur">购物车</span>
      </nav>

      <!-- 标题行：标题在左，「编辑」在右（稿 :314-322） -->
      <div class="h1row">
        <h1 class="page-h">购物车 <span class="n">{{ orderList.length }} 件商品</span></h1>
        <!-- ⚠️ 「编辑」**只在「有商品」态渲染**：稿的空视图（`:572-574`）的 `.h1row` 里
             只有 `.page-h`、没有这枚按钮 —— 稿 :126 也写着「空视图 slots 计数为 **0**，
             check-drafts 报的 4 全部来自『有商品』视图」。摆错一处，对账就多一条。
             ⚠️ 它的 `data-slot` **只挂客户宿主**：商家 pane（OrderCenter-merchant.html:763-764）
             同值但**不挂**，理由见那页 :760 与 :783 ——「同一个缺口不在两处各挂一次角标」。
             data-future 两稿都不落（角标不是 Vue 的东西）。
             ⚠️ title 取客户稿 :515 逐字；商家稿 :764 是同一句、措辞微异，差异记在台账。 -->
        <button v-if="orderList.length > 0" class="edit" disabled
                :data-slot="showSwitch ? null : '预留 · 编辑（动现有代码 4 处 · 超档）'"
                title="待 C3〈购物车〉——批量删 / 移入收藏均无端点。4 处 = 购物车的共同基线（mall_management_sql.sql 建 cart 表 + CartController.java + endpoints.js + ShoppingCart.vue），**超档**，因清单点名而画出">编辑</button>
      </div>

      <div v-if="loading" class="empty">
        <div class="p">加载中…</div>
      </div>

      <!-- 空态：稿 :565-583 —— 保留面包屑与标题，把列表区换成空态，**并摘掉承诺条与吸底结算栏**
           （这两块都以「有商品」为前提，稿 :575-578 与 Q3 第 ⑨ 条）。 -->
      <div v-else-if="orderList.length === 0" class="empty">
        <div class="big">购物车还是空的</div>
        <div class="p">逛逛商品，把想买的先放进来。</div>
        <button class="go2" disabled title="待 C3〈购物车〉——搜索页已有，但本页未落路由跳转">去挑商品</button>
      </div>

      <!-- ── 有商品 ──────────────────────────────────────────────────────── -->
      <template v-else>
        <!-- ⚠️ `.shops` 是稿的**预留位**（稿 :519-521：店铺分组 / 商品行 / 店铺小计，N=4 超档），
             稿里由 JS 塞 mock（SHOPS / REC 两个常量）。本仓**没有购物车表**，页面上真实存在的
             是 `order.*` 的**订单行**（`selectActiveByCustomer`，SQL 见 OrderDao.java:116）⇒
             这里是「购物车表 + 订单行」的**混合形态**（用户 2026-10-07 裁决）。

             缺失项一律**占位、不删**（用户 2026-10-07 口径：「有些内容比较缺失，是因为没做
             （数据库缺列，功能流程不完善），你先占位，我 C3 后续要回过来完善流程补上」）：
               · **数量 / 小计**两列照稿画，值落 `—` —— `order` 表**没有 Quantity 列**
                 （建表语句 mall_management_sql.sql:141-155，SELECT 列表 OrderDao.java:116 也印证）
                 ⇒ 行小计 = 单价 × 数量 无从计算。整块缺口已由本 section 的 `data-slot` 登记。
               · **店铺分组头 / 店铺小计**照稿画：分组键 `MerchantId` 在行上（OrderDao.java:116），
                 店名无来源（无店铺表，稿 :92 自记 N≥5）⇒ 头里显示的是**商家号**（与
                 HistoricalOrders.vue:17-21 的「商家ID」同一既有做法），「进店逛逛」与「本店小计」占位。 -->
        <section class="shops" data-slot="预留 · 店铺分组 / 商品行 / 店铺小计（动现有代码 4 处 · 超档）"
                 title="待 C3〈购物车〉——整块 4 处 = 共同基线（mall_management_sql.sql 建 cart 表 + CartController.java + endpoints.js + ShoppingCart.vue）。分组键用 goods.MerchantId（**已有**，不用加列）；店铺名与「进店逛逛」另有缺口：无店铺表 ⇒ ≥5 处。**超档**，因清单点名而画出">
          <div class="list-head">
            <span></span><span></span><span>商品</span>
            <span class="c-num">单价</span><span class="c-num">数量</span>
            <span class="c-sub">小计</span><span class="c-op">操作</span>
          </div>

          <div v-for="group in shopGroups" :key="group.id" class="shop">
            <!-- 店铺头：稿 :327-331（与 ProductPage 的商家卡、OrderCenter 的店铺头同源）。
                 店名栏落**商家号**，因为名称没有来源；「进店逛逛」是死控件（disabled + title）。 -->
            <div class="shop-head">
              <button class="ck on" disabled
                      title="待 C3〈购物车〉——全选本店无端点（共同基线 4 处，超档）"></button>
              <span class="shop-nm">商家 {{ group.id }}<span class="badge">{{ group.rows.length }} 单</span></span>
              <button class="visit" disabled
                      title="待 C3〈店铺〉——无店铺表，无落点（≥5 处，超档；与 ProductPage.html 的商家卡同一缺口）">进店逛逛 ›</button>
            </div>

            <div v-for="order in group.rows" :key="order.OrderId" class="row">
              <!-- 勾选框：稿 :344/:619 一样**不挂 data-slot**、一律 disabled + title。
                   ⚠️ 它是「购物车」那一半的控件，挂在订单行上语义并不成立（订单不可勾选结算）；
                   照稿落是因为整块 `.shops` 本就是预留位。 -->
              <button class="ck on" disabled
                      title="待 C3〈购物车〉——勾选无端点（共同基线 4 处，超档）"></button>
              <div class="thumb"><img v-if="order.Images" :src="order.Images" alt="商品图片" /></div>
              <div class="nm">
                <div class="t">{{ order.ProductInformation }}</div>
                <!-- ⚠️ 订单号**保留**（稿 :21-24 的「★ v4 新删」不采纳）：那条的依据是
                     「购物车里没有订单号 —— 还没下单哪来的订单号」，前提是本页**就是**购物车；
                     用户已裁定本页是「购物车 + 订单列表」混合形态 ⇒ 前提不成立，且删它是删信息。 -->
                <div class="spec">订单号 {{ order.OrderId }}</div>
              </div>
              <div class="unit">¥{{ formatPrice(order.Price) }}</div>
              <!-- 数量 / 小计：**占位**（`order` 表无 Quantity 列 ⇒ 两个数都算不出来） -->
              <div class="qty">
                <button class="b" disabled title="待 C3〈购物车〉——数量递减无端点，且 order 表无 Quantity 列（mall_management_sql.sql:141-155）">−</button>
                <span class="v">—</span>
                <button class="b" disabled title="待 C3〈购物车〉——数量递增无端点，且 order 表无 Quantity 列（mall_management_sql.sql:141-155）">+</button>
              </div>
              <div class="sub" title="待 C3〈购物车〉——小计 = 单价 × 数量，而 order 表无 Quantity 列 ⇒ 无从计算">—</div>
              <!-- 操作列 = 用户 2026-10-07 给的状态机「用户视角操作」那一列：
                   待付款 → 付款；待发货 → 等（不可点）；待收货 → 收货。
                   ⚠️ 「打印」（已完成那一态）**不落**：本页 SQL 是 `ReceiveIf != 1`
                   （OrderDao.java:117），已完成的单根本不在这里，且全前端**无**打印实现
                   （grep「打印」= 0 命中）⇒ 落它只能是死控件。缺口登记 `C3〈订单〉`。
                   ⚠️ 两个按钮的 `:disabled` / `@click` / `:class` **逐字保留原实现**
                   —— 本笔不改行为，只有样式块换了。 -->
              <div class="op">
                <button
                  class="pay-btn"
                  :disabled="order.PayIf === 1"
                  :class="{ disabled: order.PayIf === 1 }"
                  @click="handlePay(order)"
                >
                  付款
                </button>
                <button
                  class="receive-btn"
                  :disabled="order.PayIf !== 1 || order.ReceiveIf === 1"
                  :class="{ disabled: order.PayIf !== 1 || order.ReceiveIf === 1 }"
                  @click="handleReceive(order)"
                >
                  收货
                </button>
              </div>
              <!-- 第二带：4 步进度 + 创建·更改日期 —— 稿把它们列为「迁走（订单的职责）」，
                   用户裁定留页内 ⇒ 保留原实现，只是从卡片下半块变成表行的跨列带。 -->
              <div class="row-b">
                <div class="progress-steps">
                  <span>已付款</span>
                  <span>等待商家发货</span>
                  <span>已发货</span>
                  <span>已收货</span>
                </div>
                <div class="progress-bar-bg">
                  <div class="progress-fill" :style="{ width: getProgressWidth(order) }"></div>
                </div>
                <div class="date-row">
                  <span class="create-date">创建订单日期：{{ formatDateTime(order.CreateTime) }}</span>
                  <span class="update-date">订单更改日期：{{ formatDateTime(order.SettlementDate) }}</span>
                </div>
              </div>
            </div>

            <!-- 店铺小计：占位（行小计都还算不出来） -->
            <div class="shop-foot">
              本店小计
              <span class="v" title="待 C3〈购物车〉——本店小计 = Σ 行小计，而行小计依赖 order 表没有的 Quantity 列">—</span>
            </div>
          </div>
        </section>

        <!-- 服务承诺条（跨页共性组件，文案与 ProductPage 逐字一致，稿 :107-109）。
             ⚠️ 同「编辑」：`data-slot` **只挂客户宿主**（商家稿 :788 同块不挂，理由见 :783-785）。 -->
        <div class="pledge"
             :data-slot="showSwitch ? null : '预留 · 服务承诺条（动现有代码 ≥4 处 · 超档）'"
             title="待 C3〈商品〉——goods 无承诺列，选了什么存不下来：mall_management_sql.sql + GoodsDao.java + GoodsController.java + 上架页 = ≥4 处，**超档**。⚠️ 文案必须与 ProductPage.html 的承诺条逐字一致（跨页共性组件）">
          <span>正品保障</span><span>7 天无理由</span><span>极速退款</span><span>运费险</span>
        </div>
      </template>

      <!-- 猜你喜欢：稿 :536-543 登记为**真功能**（`goodsSelectHome` 现成、0 处改动）。
           ⚠️ 本笔**占位、未接线** —— D 笔功能量恒为 0，且本页原本没有这个数据源。
           ⚠️ 商家宿主**不画**：商家稿 :786-787 明写「仍未复画猜你喜欢……留作已知差异，不偷偷补齐」。 -->
      <section v-if="!showSwitch" class="rec">
        <div class="rec-h">
          <h2>猜你喜欢</h2>
          <span class="note">goodsSelectHome · 现有端点，0 处改动</span>
        </div>
        <p class="empty-tip">预留 · 猜你喜欢 —— 数据源（goodsSelectHome）现成，本笔未接线。</p>
      </section>
    </div>

    <!-- 吸底结算栏：稿 :546-561。⚠️ 空购物车态**不画**（稿 :575-578）。
         ⚠️ 整条是**预留位**（`data-slot` + 去结算 disabled）：稿的「已选 N 件 / 共 N 件」改成
            本仓能说真话的「共 N 单」—— 订单行没有「选中」这回事；「运费 免运费」也无来源，不落。
         ⚠️ 「合计」按 `order.Price` 逐单累加：这是**订单金额**（改前 `.order-price`、
            HistoricalOrders.vue 亦如此读），不是行小计 —— 行小计要 Quantity 列，那是上面那处占位。
            故 `title` 里写明口径，避免与 `—` 的行小计读成矛盾。 -->
    <div v-if="orderList.length > 0" class="paybar"
         data-slot="预留 · 吸底结算栏（动现有代码 4 处 · 超档）"
         title="待 C3〈购物车〉——结算 / 支付流程均无端点。4 处 = 共同基线（mall_management_sql.sql 建 cart 表 + CartController.java + endpoints.js + ShoppingCart.vue），**超档**，因清单点名而画出">
      <div class="paybar-in">
        <span class="sel">
          <button class="ck on" disabled title="待 C3〈购物车〉——全选无端点"></button>
          全选
          <span class="cnt">共 {{ orderList.length }} 单</span>
        </span>
        <span class="sums">
          <span class="d">商品总额 ¥{{ formatPrice(cartTotal) }}</span>
          <span class="total" title="口径：Σ order.Price（订单金额逐单累加）。行小计另需 Quantity 列，见商品行那处占位。">
            <span class="k">合计</span>
            <span class="v"><i>¥</i>{{ formatPrice(cartTotal) }}</span>
          </span>
        </span>
        <button class="go" disabled title="待 C3〈购物车〉——结算流程无端点">去结算（{{ orderList.length }} 件）</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */
import { ref, onMounted, computed } from 'vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { resolveBuyerId } from '../api/auth'

defineProps({
  orderTab: { type: String, default: 'cart' },
  showSwitch: { type: Boolean, default: true }
})
const emit = defineEmits(['toggle'])

const handleToggle = () => emit('toggle')

const orderList = ref([])
const loading = ref(false)

// 下面两个 computed 是本笔**仅有**的脚本改动（连同上面 import 里多出来的 `computed`）：
// ① 按 `MerchantId` 归并 —— 稿 `.shops` 的店铺分组（稿 :89「分组键 goods.MerchantId 有」）。
const shopGroups = computed(() => {
  const map = new Map()
  for (const o of orderList.value) {
    const key = o.MerchantId || '未知商家'
    if (!map.has(key)) map.set(key, { id: key, rows: [] })
    map.get(key).rows.push(o)
  }
  return [...map.values()]
})

// ② 吸底条的「合计」由 orderList **现算**，不写死 —— 与稿 :653-656 同一条规矩：
//    「页面上凡是『另一个数算得出来』的数，都不许手写」。
const cartTotal = computed(() => orderList.value.reduce((s, o) => s + Number(o.Price || 0), 0))

// 降级链已收敛到 src/api/auth.js:resolveBuyerId()（与 HistoricalOrders 逐字相同）。
const getBuyerId = () => resolveBuyerId()

const loadOrders = async () => {
  const customerId = getBuyerId()
  if (!customerId) {
    console.warn('未登录，无法获取订单')
    return
  }
  loading.value = true
  try {
    const params = new URLSearchParams()
    params.append('customerId', customerId)
    const res = await http.post(endpoints.order.orderSelect, params.toString())
    const data = await res.json()
    if (data.success) {
      orderList.value = data.list
    } else {
      console.error('获取订单失败', data.msg)
    }
  } catch (err) {
    handleApiError(err, '请求出错')
  } finally {
    loading.value = false
  }
}

const formatPrice = (price) => Number(price).toFixed(2)

// 修改为完整日期时间格式
const formatDateTime = (dateStr) => {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

const getProgressWidth = (order) => {
  const { PayIf, DeliveryIf, ReceiveIf } = order
  if (PayIf === 1 && DeliveryIf === 1 && ReceiveIf === 0) return '75%'
  if (PayIf === 1 && DeliveryIf === 0 && ReceiveIf === 0) return '25%'
  return '0%'
}

const handlePay = async (order) => {
  if (order.PayIf === 1) {
    alert('订单已付款，无需重复付款')
    return
  }
  try {
    const params = new URLSearchParams()
    params.append('orderId', order.OrderId)
    const res = await http.post(endpoints.order.orderPayIfUp, params.toString())
    const result = await res.json()
    if (result.success) {
      alert('付款成功！')
      await loadOrders()
    } else {
      alert('付款失败：' + (result.msg || '未知错误'))
    }
  } catch (err) {
    console.error('付款请求出错', err)
    alert('网络错误，请稍后重试')
  }
}

const handleReceive = async (order) => {
  if (order.PayIf !== 1) {
    alert('请先付款再收货')
    return
  }
  if (order.ReceiveIf === 1) {
    alert('订单已收货，不可重复操作')
    return
  }
  if (order.DeliveryIf !== 1) {
    alert('商家还在备货，不允许现在收货')
    return
  }
  try {
    const params = new URLSearchParams()
    params.append('orderId', order.OrderId)
    const res = await http.post(endpoints.order.orderReceiveIfUp, params.toString())
    const result = await res.json()
    if (result.success) {
      alert('收货成功！')
      await loadOrders()
    } else {
      alert('收货失败：' + (result.msg || '未知错误'))
    }
  } catch (err) {
    console.error('收货请求出错', err)
    alert('网络错误，请稍后重试')
  }
}

onMounted(() => {
  loadOrders()
})
/* eslint-enable no-undef */
</script>

<style scoped>
/* ══ 来源：docs/美化/pages/ShoppingCart-customer.html（v5，716 行）════════════════
   ⚠️ 稿的 `.bar`（预览条）/ `body.slots *`（角标可视化）/ `.nav-bar` `.brand`
      `.nav-right` `.dot`（顶栏，归 AppShell.vue）—— **一律不搬**，搬进来就是悬空类名。
   ⚠️ 稿的 `.page` / `.page.on`（两视图切换）不搬：Vue 侧用 v-if / v-else。
   ⚠️ 稿本页 **1 处 @media**（1120px，稿 :467-488）；本块照搬该断点，列定义按本页实际列数改写。
   ⚠️ 未复画的稿规则：`.grid` `.card*` `.thumb2`（猜你喜欢卡 —— 本笔占位未接线）。
   ⚠️ 稿的 `.glaze*` / `.dots` 之类不属本页；`.cs` 亦无。 */

/* ── 页根：壳的 `.cart-container`（AppShell.vue:299-305）是 `height:100%;overflow:hidden`
      ⇒ 滚动盒必须由本页提供（改前 `.shopping-cart-page` 就是干这个的）。
      ⚠️ `position:relative` **不能删**：`.switch-wrapper` 是它的 absolute 子元素，而
      AppShell.vue:295-298 记着「`.cart-container` 与 `.order-container` 两类名不合并」的
      依据正是「两页都有 absolute 子元素」（OrderCenter.vue:200/:235 与 ShoppingCart.vue 侧）——
      把这里改成常规流 = 那条依据失效。 */
.cart-page {
  height: 100%;
  position: relative;
  background: var(--bg);
  overflow-y: auto;
}

/* ── 商家壳的两态开关（稿外活控件，样式取原实现、换 token）────────────────── */
.switch-wrapper {
  position: absolute;
  top: 20px;
  right: 30px;
  z-index: 10;
}
.switch-container {
  width: 120px;
  height: 36px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 36px;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
}
.switch-slider {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  position: relative;
}
.slider-text {
  flex: 1;
  text-align: center;
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  z-index: 2;
  transition: color 0.3s;
  color: var(--ink-3);
  letter-spacing: 2px;
}
.switch-container::before {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 54px;
  height: 28px;
  background: var(--surface);
  border-radius: 30px;
  transition: transform 0.3s ease;
  z-index: 1;
}
.switch-container.switch-left::before {
  transform: translateX(0);
}
.switch-container.switch-right::before {
  transform: translateX(60px);
}
.switch-container.switch-left .slider-text.left {
  color: var(--brand-ink);
}
.switch-container.switch-right .slider-text.right {
  color: var(--brand-ink);
}

/* ── 主区：稿 `.wrap`（`:304`）──────────────────────────────────────────────
   ⚠️ 稿的 `padding-bottom:var(--s8)` 保留：吸底条是 `sticky`（不是 `fixed`），
      它到达自然位置后落回文档流，这段净空是它下面那口「气」。 */
.wrap {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s7) var(--s5) var(--s8);
}

/* 面包屑：分隔符**与文字同色**（同为 `--ink-3`）—— 用 `--line-2` 时对比度只有 1.47:1。 */
.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--ink-3); }
.crumb .sp { font-style: normal; padding: 0 var(--s2); }
.crumb .cur { color: var(--ink-2); }

/* 标题行：标题在左，「编辑」在右 —— 编辑是页面级动作，不是列表级动作。 */
.h1row { margin-top: var(--s4); display: flex; align-items: baseline; gap: var(--s5); }
/* ⚠️ 稿写的是 `font-size: var(--d2)`，而 `--d2 = calc(var(--fs-2xl) * 1.00)`（稿 :244 在
   `:root` 里定义）。scoped 块设不了 `:root` ⇒ 直接写 `var(--fs-2xl)`（36px，值相同）。 */
.page-h {
  display: flex;
  align-items: baseline;
  gap: var(--s4);
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-med);
  line-height: 1.15;
  letter-spacing: -.02em;
}
.page-h .n { font-family: var(--font); font-size: var(--fs-xs); color: var(--ink-3); letter-spacing: 0; }
.edit { margin-left: auto; font-size: var(--fs-sm); color: var(--ink-2); padding: 0 var(--s1); background: none; border: 0; cursor: pointer; }
.edit:hover { color: var(--brand-ink); }
.edit[disabled] { color: var(--ink-3); cursor: not-allowed; }

/* ── 店铺分组（稿 :324-335）──────────────────────────────────────────────────
   分组键 `MerchantId` 在订单行上（OrderDao.java:116）；店名无来源，头里落的是商家号。 */
.shops { margin-top: var(--s6); }
.shop { border-top: 1px solid var(--line-2); }
.shop-head { display: flex; align-items: center; gap: var(--s3); padding: var(--s4) 0 var(--s3); }
.shop-nm { font-size: var(--fs-sm); font-weight: var(--fw-med); color: var(--ink); }
.shop-nm .badge { margin-left: var(--s2); font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3); }
.visit { margin-left: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); background: none; border: 0; padding: 0; cursor: pointer; }
.visit[disabled] { cursor: not-allowed; }
.shop-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s4);
  padding: var(--s3) 0 var(--s5);
  border-top: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.shop-foot .v { font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-med); color: var(--ink-3); font-variant-numeric: tabular-nums; }

/* ── 列头 + 表行共用同一套列宽 ⇒ 天然对齐（稿 :337-342）──────────────────────
   ⚠️ 名称列 `minmax(0,1fr)` 吃掉全部余量（稿 Q3 第 ① 条）：否则余量会被平摊到各列、数字列左右乱飘。
   ⚠️ 骨架取稿的 7 列（28 / 64 / 商品 / 88 / 104 / 112 / 48），只把**操作列 48 → 152**
      加宽 —— 稿那里只放一枚「删除」文字链，本页放付款 + 收货两枚按钮。 */
.list-head, .row {
  display: grid;
  grid-template-columns: 28px 64px minmax(0, 1fr) 88px 104px 112px 152px;
  align-items: center;
  gap: var(--s4);
}
.list-head { padding-bottom: var(--s3); font-size: var(--fs-xs); color: var(--ink-3); }
.list-head .c-num, .list-head .c-sub, .list-head .c-op { text-align: right; }
/* 表行是**发丝线**，不是卡片流（稿 :39-40「v2 已经画对的部分逐字保留」） */
.row { padding: var(--s4) 0; border-bottom: 1px solid var(--line); row-gap: var(--s3); }
/* 第二带（4 步进度 + 日期）跨全列 —— 稿把这三件列在「迁走」里，用户裁定留页内 */
.row-b { grid-column: 1 / -1; }

/* 勾选框：目标版是真实控件，后端零端点 ⇒ 一律 disabled + title，不挂 data-slot（稿 :344）。
   勾用两条边框转 45° 画，不用 `✓` 字形（自托管子集里没有该码位，会回落成小点）。 */
.ck {
  position: relative;
  width: 18px;
  height: 18px;
  flex: none;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
}
.ck[disabled] { cursor: not-allowed; }
.ck.on { border-color: var(--brand); background: var(--brand); }
.ck.on::after {
  content: "";
  position: absolute;
  left: 5px;
  top: 1px;
  width: 5px;
  height: 9px;
  border-right: 2px solid var(--bg);
  border-bottom: 2px solid var(--bg);
  transform: rotate(45deg);
}
.list-head .ck { justify-self: start; }

/* ① 图片 64px 方图 —— 最小那一级 */
.thumb { width: 64px; aspect-ratio: 1/1; border-radius: var(--r1); background: var(--img); border: 1px solid var(--line); overflow: hidden; }
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }

/* ② 商品名 —— 最重那一级；订单号次之。 */
.nm { min-width: 0; }
.nm .t {
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  line-height: 1.35;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nm .spec { margin-top: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }

/* ③ 单价：最轻的数字 —— 承重的那一次留给吸底条的「合计」。 */
.unit { text-align: right; font-size: var(--fs-sm); color: var(--ink-3); font-variant-numeric: tabular-nums; }

/* ④ 数量（占位）/ ⑤ 小计（占位）—— `order` 表无 Quantity 列，两个数都算不出来，
      故一律走 `--ink-3` 弱化，与「有值」的 `.unit` 分开。 */
.qty { display: flex; align-items: center; justify-content: flex-end; gap: var(--s2); }
.qty .b {
  width: 26px;
  height: 26px;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  line-height: 1;
  cursor: pointer;
}
.qty .b[disabled] { cursor: not-allowed; }
.qty .v { min-width: 24px; text-align: center; font-size: var(--fs-sm); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.sub {
  text-align: right;
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

/* ⑥ 操作：付款 / 收货。⚠️ 两个按钮的 disabled 语义**照原实现**（付款后禁付款、未发货禁收货）。 */
.op { display: flex; align-items: center; justify-content: flex-end; gap: var(--s2); }
.op button {
  height: 32px;
  padding: 0 var(--s4);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  white-space: nowrap;
  cursor: pointer;
}
.op button:hover:not([disabled]) { border-color: var(--brand); color: var(--brand-ink); }
.op button[disabled] { color: var(--ink-3); cursor: not-allowed; }

/* 第二带：4 步进度 + 日期（原 `.order-bottom`，换 token、压进表行） */
.progress-steps { display: flex; justify-content: space-between; font-size: var(--fs-xs); color: var(--ink-3); }
/* ⚠️ 槽色用 `--line-2` 而不是 `--surface-3`：原实现是 `#e0e0e0`，而 `--surface-3`(#ebeef0)
   在白底上对比度低到看不见 —— 0% 那行（待付款）整条进度槽会整个消失。`--line-2`(#d0d6d9) 最接近原值。 */
.progress-bar-bg { margin-top: var(--s1); height: 4px; border-radius: 2px; background: var(--line-2); overflow: hidden; }
.progress-fill { height: 100%; background: var(--brand); transition: width 0.3s; }
.date-row { display: flex; justify-content: space-between; margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 服务承诺条（跨页共性组件，文案与 ProductPage 逐字一致）───────────────────
   稿 :387-390：承诺条是**购物车的尾**，不是推荐区的头 ⇒ 上紧下松
   （`margin-top:var(--s2)`；下距由 `.rec{margin-top:var(--s7)}` 给）。 */
.pledge {
  margin-top: var(--s2);
  margin-bottom: var(--s2);
  padding: var(--s4) 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: var(--s8);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.pledge span::before { content: "·"; color: var(--brand); margin-right: var(--s2); font-weight: var(--fw-semi); }

/* ── 猜你喜欢（稿 :396-400；`.grid` / `.card*` 未复画 —— 占位未接线）────────── */
.rec { margin-top: var(--s7); }
.rec-h { display: flex; align-items: baseline; gap: var(--s4); margin-bottom: var(--s5); }
.rec-h h2 { font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-reg); }
.rec-h .note { font-size: var(--fs-xs); color: var(--ink-3); margin-left: auto; }

/* 预留位的一行说明（本仓既有写法，HomePage 同款） */
.empty-tip { padding: var(--s6) 0; text-align: center; font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 吸底结算栏（稿 :414-454）───────────────────────────────────────────────
   ⚠️ 稿 v5 把底色从 `--surface` 改成 **`--glaze-deep`** 并删掉 `border-top`：全页唯一的「面」，
      与登录页品牌栏、个人页身份卡同一枚色 ⇒「每页一个釉面主块，只用一次」。照搬。
   ⚠️ `sticky;bottom:0` 而**不是** `fixed`（稿 Q3 第 ② 条）：fixed 要额外给正文补 `padding-bottom`，
      否则最后一行会被永久盖住；sticky 到自然位置就落回文档流。参照物是上面的 `.cart-page` 滚动盒。
   ⚠️ 稿的 `.go[disabled]` **推翻**了「disabled 涂灰盒」，改回 `--brand` 实底（稿 :443-454）——
      理由是「房规：逐行重复控件只 disabled + title，全批 [disabled] 几乎都只改 cursor」。
      照搬（本笔的 `.ck` / 行内按钮 / `.edit` 亦同此规矩，只有 `.go` 一处实底）。 */
.paybar { position: sticky; bottom: 0; z-index: 20; background: var(--glaze-deep); }
.paybar-in { max-width: 1180px; margin: 0 auto; padding: var(--s4) var(--s5); display: flex; align-items: center; gap: var(--s5); }
.paybar .sel { display: flex; align-items: center; gap: var(--s3); font-size: var(--fs-sm); color: var(--ink-2); }
.paybar .sel .cnt { color: var(--ink-3); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.paybar .sums { margin-left: auto; display: flex; align-items: baseline; gap: var(--s4); }
.paybar .sums .d { font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.total { display: flex; align-items: baseline; gap: var(--s2); }
.total .k { font-size: var(--fs-sm); color: var(--ink-2); }
.total .v {
  font-family: var(--font-display);
  font-size: var(--fs-xl);
  font-weight: var(--fw-med);
  line-height: 1;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.total .v i { font-style: normal; font-size: var(--fs-sm); margin-right: 2px; }
.go {
  height: 48px;
  padding: 0 var(--s7);
  border: 0;
  border-radius: var(--r2);
  background: var(--brand);
  color: var(--bg);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  white-space: nowrap;
  cursor: pointer;
}
.go[disabled] { background: var(--brand); color: var(--bg); cursor: not-allowed; }

/* ── 空态（稿 :456-461）───────────────────────────────────────────────────── */
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
  background: none;
  color: var(--ink-2);
  font-size: var(--fs-sm);
  cursor: pointer;
}
.empty .go2[disabled] { cursor: not-allowed; }

/* ── 窄屏（稿 :463-488）：列头隐藏 ⇒ 各字段自带小标签；吸底条显式折两行 ────────
   ⚠️ 四列 × 三行**显式定位**，不用 `grid-template-areas`：列头一没，四件元信息若还各占一整行，
      末尾的「操作」会被推得很远、中间空出一大片。两两一行（单价|操作 / 数量|小计）把这一带收在名字下方。
   ⚠️ 吸底条**不靠 flex-wrap**（折行位置随视口变，稿 :166-167 记 GoodsUp 踩过）。 */
@media (max-width: 1120px) {
  .list-head { display: none; }
  .row {
    grid-template-columns: 28px 64px minmax(0, 1fr) auto;
    column-gap: var(--s5);
    row-gap: var(--s4);
  }
  .row .ck { grid-area: 1/1/2/2; align-self: start; margin-top: 2px; }
  .row .thumb { grid-area: 1/2/2/3; }
  .row .nm { grid-area: 1/3/2/5; }
  .row .unit { grid-area: 2/3/3/4; text-align: left; font-size: var(--fs-xs); }
  .row .op { grid-area: 2/4/3/5; }
  .row .qty { grid-area: 3/3/4/4; justify-content: flex-start; }
  .row .sub { grid-area: 3/4/4/5; }
  .row .row-b { grid-area: 4/1/5/5; }
  /* 列头没了，所以这几个数各自带一个前缀标签 —— 只在窄屏出现，不是装饰 */
  .row .unit::before { content: "单价 "; }
  .row .qty::before { content: "数量"; color: var(--ink-3); font-size: var(--fs-xs); }
  .row .sub::before { content: "小计 "; font-family: var(--font); font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3); }
  .pledge { gap: var(--s5); }
  .paybar-in { flex-direction: column; align-items: stretch; gap: var(--s3); }
  .paybar .sums { margin-left: 0; justify-content: space-between; }
  .go { width: 100%; padding: 0; }
}
</style>
