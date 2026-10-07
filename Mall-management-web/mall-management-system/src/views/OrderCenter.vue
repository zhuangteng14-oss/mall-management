<template>
  <div class="order-center-page">
    <!-- ⚠️ 稿外**活**控件（全页唯一一枚）：商家壳「订单中心 ⇄ 购物车」两态开关。
          稿 OrderCenter-merchant.html 的两个 pane（:668 / :749）都没画它 —— 它是
          `/merchant/order/cart` 的回程（OrderPane.vue:3 的 v-else 那支），删掉商家就切不回订单面。
          与 ShoppingCart.vue:8-19 是**同款同源**的一条契约（orderTab / showSwitch / @toggle），
          本笔一字未动。登记见 slot-registry.md §三「稿外活控件」。 -->
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
      <!-- 面包屑（稿 :674）。稿里「首页」是 `<a>`（无 href、不可点）⇒ 用 `<span>`，不画死控件。 -->
      <nav class="crumb">
        <span>首页</span>
        <span class="sp">/</span>
        <span class="cur">订单中心</span>
      </nav>

      <!-- 标题行（稿 :675 的 h1 + `.n`）。
           ⚠️ 稿把 h1 **直接**挂在 `.crumb` 之后、不用 `.h1row`（那页 :352 自注
              「本规则在订单中心区域零消费」）—— 本页用 `.h1row` 的唯一原因是它要装下面这枚下拉。
           用户裁决 A：现有 `<select>`（首页 / 历史订单）**原样保留**，不迁不删 ——
              稿 :53 的 ★ 是「收紧成 6 个标签页」，但 6 个 tab 全部 disabled，
              真把 select 拿掉 ⇒「已完成」这一视图在页面上**不可达**（现有端点仍在，属功能回退）。
           代价 = 标题行比稿低 16px（`.h1row{margin-top:var(--s4)}`），登记见 §一 commit 5。 -->
      <div class="h1row">
        <h1 class="page-h">订单中心 <span class="n">共 {{ orderList.length }} 单 · 待处理 {{ orderList.filter(o => canSend(o)).length }} 单</span></h1>
        <select v-model="orderStatus" class="status-select" @change="loadOrders">
          <option value="active">首页</option>
          <option value="completed">历史订单</option>
        </select>
      </div>

      <div v-if="loading" class="empty">
        <div class="p">加载中…</div>
      </div>

      <!-- 空态（稿 :811-825）。⚠️ 概览 / 标签页 / 筛选 / 批量 / 分页**五块全部不画** ——
           稿那份 pane 里它们都不存在 ⇒ **空态 slots 计数 = 0**（对账用，见 §五）。
           ⚠️ 文案照稿：「还没有订单」（现有 :26 是「暂无订单」，稿 :47-49 明写换字不换态）。 -->
      <div v-else-if="orderList.length === 0" class="empty">
        <div class="big">还没有订单</div>
        <div class="p">买家下单后会出现在这里。</div>
        <button class="go2" disabled title="待 C3〈订单〉——商品管理路由已有，但本笔功能量恒为 0，不接线">去商品管理</button>
      </div>

      <template v-else>
        <!-- ① 数据概览（稿 :677-687）。⚠️ 四张卡**全部是 `—`**：它们是**预留位**（无聚合端点），
             而现有两次请求各自只取一个口径（active = `ReceiveIf != 1`）⇒ 四个数**都算不出来**。
             稿的四个数是从它自带的 6 行静态数据集推出来的，本页没有那个数据集，
             照稿「凡是另一个数算得出来的数都不许手写」（稿 :881-885）的反面 ⇒ **不手写、留 `—`**。
             稿 :684-686 记：第 5 个合法态「已收货·未发货」不设卡，它在表格里有自己的红色 chip。 -->
        <section class="stats" data-slot="预留 · 数据概览（动现有代码 4 处 · 超档）"
                 title="待 C3〈订单〉——无聚合端点。4 处 = OrderDao（一条 GROUP BY 的 count 查询）+ OrderService + OrderController + OrderCenter.vue，**超档**，因清单点名而画出">
          <div class="stat"><div class="k">未付款</div><div class="v">—</div></div>
          <div class="stat"><div class="k">待发货</div><div class="v">—</div></div>
          <div class="stat"><div class="k">已发货</div><div class="v">—</div></div>
          <div class="stat"><div class="k">已完成</div><div class="v">—</div></div>
        </section>

        <!-- ② 标签页（稿 :689-702）。整块是预留位；6 枚标签逐枚 disabled（稿外死控件，不挂 data-slot）。
             ⚠️ 稿 :692 自己把 N 拆成三档（全部/待发货/已发货 N=1 在档、已完成 N=0、退款中/售后 N≥6 超档）
                —— 但本笔功能量恒为 0 ⇒ 一档都不落，全部 disabled。
             ⚠️ 计数：前四个是 `—`（同概览的理由，算不出来）；后两个照稿写 `0` ——
                稿 :697-699 明写「恒为 0，**不是『暂无数据』而是『这个态不存在』**」。 -->
        <nav class="tabs" data-slot="预留 · 标签页（全部/待发货/已发货 = 1 处 · 在档；退款中/售后 = ≥6 · 超档）"
             title="待 C3〈订单〉——现有 status 是布尔（OrderService:461 `&quot;completed&quot;.equals(status)`），只有「未完成 / 已完成」两个口径。⚠️ 拆一下：全部/待发货/已发货 **N=1**（三个状态位 selectByMerchant 已全 SELECT 出来、两条 SQL 又都没有 LIMIT ⇒ 全量在前端，纯前端筛即可，只改 OrderCenter.vue）；已完成 **N=0**（现有端点）；退款中/售后 **N≥6**（OrderState 三位已用满 8 组且全具名 ⇒ 要加第 4 位：mall_management_sql.sql + OrderState + OrderStateMachine + OrderDao + OrderService + OrderController + OrderCenter.vue）。⚠️ N=1 的保质期 = 「没有分页」；一上分页四个 tab 全得搬后端">
          <button class="tab on" disabled title="待 C3〈订单〉——纯前端筛（N=1），本笔功能量恒为 0">全部<span class="c">—</span></button>
          <button class="tab" disabled title="待 C3〈订单〉——纯前端筛（N=1），本笔功能量恒为 0">待发货<span class="c">—</span></button>
          <button class="tab" disabled title="待 C3〈订单〉——纯前端筛（N=1），本笔功能量恒为 0">已发货<span class="c">—</span></button>
          <button class="tab" disabled title="待 C3〈订单〉——现有端点即可（N=0），本笔功能量恒为 0">已完成<span class="c">—</span></button>
          <button class="tab" disabled title="待 C3〈订单〉——状态机缺口（N≥6，超档）：OrderState 无此态">退款中<span class="c">0</span></button>
          <button class="tab" disabled title="待 C3〈订单〉——状态机缺口（N≥6，超档）：OrderState 无此态">售后<span class="c">0</span></button>
        </nav>

        <!-- ③ 筛选栏（稿 :704-715）。整块是预留位；输入框与两枚按钮逐枚 disabled + title（稿里都带 title）。 -->
        <div class="filter" data-slot="预留 · 筛选栏（动现有代码 4 处 · 超档）"
             title="待 C3〈订单〉——现有只有一个二选一下拉（OrderCenter.vue:20-23），无任何搜索。4 处 = OrderDao（SQL 加 WHERE）+ OrderService + OrderController + OrderCenter.vue，**超档**，因清单点名而画出">
          <span class="fld"><label>订单号</label><input type="text" placeholder="ZHJC53759811" disabled title="待 C3〈订单〉——按订单号搜索无端点（4 处，超档）"></span>
          <span class="fld"><label>买家</label><input type="text" placeholder="用户名 / 客户号" disabled title="待 C3〈订单〉——按买家搜索无端点（4 处，超档）"></span>
          <span class="fld"><label>商品名</label><input type="text" placeholder="商品关键字" disabled title="待 C3〈订单〉——按商品名搜索无端点（4 处，超档）"></span>
          <span class="fld"><label>下单时间</label><input type="text" placeholder="2026-10-01" disabled title="待 C3〈订单〉——时间区间无端点（4 处，超档）"><i class="til">–</i><input type="text" placeholder="2026-10-06" disabled title="同左"></span>
          <span class="acts">
            <button class="btn" disabled title="待 C3〈订单〉——重置无端点">重置</button>
            <button class="btn pri" disabled title="待 C3〈订单〉——查询无端点">查询</button>
          </span>
        </div>

        <!-- ④ 批量操作条（稿 :717-726）。「已选 0 单」照稿写死 —— 与空框自洽（稿 :438-439 的规矩）。 -->
        <div class="bulk" data-slot="预留 · 批量操作条（动现有代码 ≥4 处 · 超档）"
             title="待 C3〈订单〉——无批量端点：批量发货要事务 + 逐单走状态机（≥4 处：OrderDao + OrderService + OrderController + OrderCenter.vue），导出 CSV 还要一个新的响应类型。**超档**，因清单点名而画出">
          <button class="ck" disabled title="待 C3〈订单〉——全选无端点（≥4 处，超档）"></button>
          <span>全选</span>
          <span class="sel">已选 <b>0</b> 单</span>
          <span class="sp"></span>
          <button class="btn" disabled title="待 C3〈订单〉——批量发货无端点（≥4 处，超档）">批量发货</button>
          <button class="btn" disabled title="待 C3〈订单〉——导出无端点（≥4 处，超档）">导出 CSV</button>
        </div>

        <!-- 表格：9 列。⚠️ **不挂 data-slot** —— 稿 :728 原话「9 列全是真功能（状态由三个状态位推出、
             金额/订单号/时间/商品/买家 id 都在响应里）⇒ 不挂角标」。 -->
        <section class="tbl">
          <div class="thead">
            <span></span><span></span><span>商品</span><span>订单号</span><span>买家</span>
            <span class="r">金额</span><span class="cen">状态</span><span>下单时间</span><span class="r">操作</span>
          </div>
          <div v-for="order in orderList" :key="order.OrderId" class="trow">
            <button class="ck" disabled title="待 C3〈订单〉——行勾选无端点（≥4 处，超档）"></button>
            <div class="thumb"><img v-if="order.Images" :src="order.Images" alt="商品图片" /></div>
            <div class="pn" :title="order.ProductInformation">{{ order.ProductInformation }}</div>
            <div class="oid">{{ order.OrderId }}</div>
            <!-- ⚠️ 买家列画的是 `CustomerId`（响应里只有它）。昵称要 join 用户表（N=2）⇒ 归 C3〈订单〉，
                 不写死、也不假造。 -->
            <div class="buyer" title="待 C3〈订单〉——买家昵称需关联用户表（N=2）；此处显示的是响应里的 CustomerId">{{ order.CustomerId }}</div>
            <div class="money">¥{{ Number(order.Price || 0).toFixed(2) }}</div>
            <div class="cen"><span class="st" :class="stateOf(order)[0]">{{ stateOf(order)[1] }}</span></div>
            <!-- 时间列（稿 :863-865）。第一行 = 下单时间；「已完成」视图该列为空 —— 既有缺陷，
                 详见下面那枚 `.dash` 的 title。第二行 = 更改日期，见 `.tcell` 上的说明。 -->
            <div class="tcell">
              <span v-if="order.CreateTime" class="time" title="下单时间（CreateTime）">{{ formatDateTime(order.CreateTime) }}</span>
              <span v-else class="dash" title="既有缺陷：OrderDao.selectByMerchant 的两条分支都不 SELECT CreateTime（OrderDao.java:138 / :141）⇒「已完成」视图这一列在库里就没取出来 —— 不是「这一单没有时间」。修它 = 改响应字段集，属另一笔">—</span>
              <span v-if="order.SettlementDate" class="time upd" title="订单更改日期（SettlementDate）—— ⚠️ 稿的 A 列对照（OrderCenter-merchant.html:44-54）逐条登记了现有 411 行的去留，却**没有**这一条（既不在「保留并重画」也不在「删」）。本笔按「不删既有已显示字段」保留，并入同一格第二行；是否照稿撤掉待用户裁。">{{ formatDateTime(order.SettlementDate) }}</span>
            </div>
            <!-- 操作列（稿 :875-877）。⚠️ 真功能：`order.orderDeliveryIfUp`（endpoints.js:58）——
                 本页**唯一**不禁用的按钮；`:disabled` / `:class` / `@click` **逐字保留原实现**。
                 ⚠️ 稿的判据是 `canSend`（= 待发货），未付款行画 `—` 而不是按钮 ⇒ 今天「点了弹
                 『用户未付款，暂不能发货』」这条路径在 UI 上消失；`handleDeliver()` 的三道守卫
                 **逐字保留**（纵深防御），且状态列已具名「未付款」。登记见 §一 commit 5。 -->
            <div class="op r">
              <button v-if="canSend(order)" class="send" :disabled="order.DeliveryIf === 1 || order.ReceiveIf === 1" :class="{ disabled: order.DeliveryIf === 1 || order.ReceiveIf === 1 }" title="现有端点 order.orderDeliveryIfUp（endpoints.js:58）——本页唯一不禁用的按钮" @click="handleDeliver(order)">发货</button>
              <span v-else class="dash">—</span>
            </div>
          </div>
        </section>

        <!-- ⑤ 分页（稿 :737-744）。⚠️ `.pg.on`「1」照稿**不 disabled**（稿 :741 带 `title="当前页"`、
             无 disabled）；它无 @click ⇒ 点击无副作用。禁用反而会被 `.pager .pg[disabled]` 的同特异性
             规则压掉 `.pg.on` 的描边色，把「当前页」画成「不可用」。登记见 §三。 -->
        <nav class="pager" data-slot="预留 · 分页（动现有代码 4 处 · 超档）"
             title="待 C3〈订单〉——两条商户 SQL 都没有 LIMIT，全量返回。4 处 = OrderDao（加 LIMIT + 一条 count）+ OrderService + OrderController + OrderCenter.vue。**超档**，因清单点名而画出。⚠️ 与标签页互锁：先上分页，则「待发货/已发货/全部」的 N 从 1 变 4">
          <button class="pg" disabled title="待 C3〈订单〉——上一页无端点">‹</button>
          <button class="pg on" title="当前页">1</button>
          <button class="pg" disabled title="待 C3〈订单〉——下一页无端点">›</button>
          <span class="tot" title="⚠️ 两条商户 SQL 都没有 LIMIT ⇒ 全部行一次返回，没有「页」。「每页 20」是稿的目标版文案（稿 :743 自注：稿的数据集 6 单所以是 1 页；71 单 / 每页 20 下是 4 页）。">共 {{ orderList.length }} 单 · 每页 20</span>
        </nav>
      </template>
    </div>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */
import { ref, onMounted } from 'vue'
import { http, handleApiError, urlOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getUsername } from '../api/auth'

defineProps({
  orderTab: { type: String, default: 'order' },
  showSwitch: { type: Boolean, default: true }
})
const emit = defineEmits(['toggle'])

const handleToggle = () => emit('toggle')

const orderList = ref([])
const loading = ref(false)
const orderStatus = ref('active')

// ⚠️ 本地函数**不能**叫 getMerchantId —— 会遮蔽从 auth.js 导入的同名函数，
//    使下面这行变成自我递归（本笔迁移时实际踩到，改名解决）。
const currentMerchantId = () => {
  let merchantId = getMerchantId()
  if (!merchantId || merchantId === 'null' || merchantId === 'undefined') {
    merchantId = getUsername()
  }
  return merchantId || null
}

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

const loadOrders = async () => {
  const merchantId = currentMerchantId()
  if (!merchantId) {
    console.warn('未登录商家，无法获取订单')
    return
  }
  loading.value = true
  try {
    const params = new URLSearchParams()
    params.append('merchantId', merchantId)
    params.append('status', orderStatus.value)
    let url = urlOf(endpoints.order.orderDeliveryIfSelect)
    if (orderStatus.value === 'completed') {
      url = urlOf(endpoints.order.orderDeliverySelect)
    }
    const res = await http.post(url, params.toString())
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

// ⚠️ 三位 → 状态：与 `OrderState.java` 的 5 个具名常量一一对应，一步不多（稿 :846-853 逐字取）。
//    状态是三个 int 位**推出来**的，`selectByMerchant` 已把三位全 SELECT 出来 ⇒ 状态列是
//    零改动的真功能（稿 :832-833）。
const STATE_OF = {
  '0,0,0': ['draft', '未付款'],
  '1,0,0': ['warn', '待发货'],
  '1,1,0': ['info', '已发货'],
  '1,0,1': ['err', '已收货·未发货'],
  '1,1,1': ['ok', '已完成']
}
const stateOf = (order) => {
  const key = [order.PayIf, order.DeliveryIf, order.ReceiveIf].join(',')
  return STATE_OF[key] || ['draft', '未知状态']
}
// 稿 :875 的 `canSend`（= 待发货）：只有这一态渲染可点的【发货】。
const canSend = (order) => order.PayIf === 1 && order.DeliveryIf === 0 && order.ReceiveIf === 0

const handleDeliver = async (order) => {
  if (order.DeliveryIf === 1) {
    alert('订单已发货，不可重复操作')
    return
  }
  if (order.ReceiveIf === 1) {
    alert('订单已收货，无法发货')
    return
  }
  if (order.PayIf !== 1) {
    alert('用户未付款，暂不能发货')
    return
  }
  try {
    const params = new URLSearchParams()
    params.append('orderId', order.OrderId)
    const res = await http.post(endpoints.order.orderDeliveryIfUp, params.toString())
    const result = await res.json()
    if (result.success) {
      alert('发货成功！')
      await loadOrders()
    } else {
      alert('发货失败：' + (result.msg || '未知错误'))
    }
  } catch (err) {
    console.error('发货请求出错', err)
    alert('网络错误，请稍后重试')
  }
}

onMounted(() => {
  loadOrders()
})
/* eslint-enable no-undef */
</script>

<style scoped>
/* 全块按稿 OrderCenter-merchant.html:321-653 重写、token 化。
   稿的 `.bar` / `body.slots *`（预览条与角标可视化）/ `.nav-bar` 一组（顶栏，归壳）/
   `.page{display:none}` 与 `.page.on`（一稿两 pane 的切页机制）/ 商家购物车 pane 那一整段
   （`.shops` / `.crow` / `.pledge` / `.paybar` …，已由 ShoppingCart.vue 的 commit 4 落）一律不搬。
   ⚠️ `@media` 里那两条 `.list-head`/`.crow` 的 1080 规则同理不搬。 */

/* ⚠️ 滚动盒必须由本页提供（壳的 `.order-container` 是 height:100%;overflow:hidden，
   AppShell.vue:306-313），且 **position:relative 不能删** —— `.switch-wrapper` 是它的 absolute
   子元素，正是 AppShell.vue:295-298「.cart-container / .order-container 两类名不合并」那条依据。 */
.order-center-page {
  height: 100%;
  position: relative;
  background: var(--bg);
  overflow-y: auto;
}
.switch-wrapper {
  position: absolute;
  top: 20px;
  right: 30px;
  z-index: 10;
}
.switch-container {
  width: 120px;
  height: 36px;
  background: #f0f0f0;
  border-radius: 36px;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  box-shadow: inset 0 1px 3px rgba(0,0,0,0.1);
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
  font-size: 14px;
  font-weight: 500;
  z-index: 2;
  transition: color 0.3s;
  color: #666;
  letter-spacing: 2px;
}
.switch-container::before {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 54px;
  height: 30px;
  background: #fff;
  border-radius: 30px;
  transition: transform 0.3s ease;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
  z-index: 1;
}
.switch-container.switch-left::before {
  transform: translateX(0);
}
.switch-container.switch-right::before {
  transform: translateX(60px);
}
.switch-container.switch-left .slider-text.left {
  color: var(--brand);
}
.switch-container.switch-right .slider-text.right {
  color: var(--brand);
}

.wrap {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s7) var(--s5) var(--s8);
}

.crumb {
  display: flex;
  align-items: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.crumb .sp {
  font-style: normal;
  padding: 0 var(--s2);
}
.crumb .cur {
  color: var(--ink-2);
}

/* 标题行（稿 :344-348）。⚠️ `.h1row` 是稿的固有规则，但**商家订单面零消费**（稿 :352）——
   本页消费它只为装那枚下拉（用户裁决 A），代价 = 标题行比稿低 `--s4`。 */
.h1row {
  margin-top: var(--s4);
  display: flex;
  align-items: baseline;
  gap: var(--s5);
}
.page-h {
  display: flex;
  align-items: baseline;
  gap: var(--s4);
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-med);
  line-height: 1.15;
  letter-spacing: -0.02em;
}
.page-h .n {
  font-family: var(--font);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  letter-spacing: 0;
}
/* 稿外**活**控件（`/merchant/order` 的视图切换）—— 上一轮的 `.title-bar` + `<h2>` 换成
   `.h1row` + `.page-h`，控件本身原样。 */
.status-select {
  margin-left: auto;
  height: 32px;
  padding: 0 var(--s3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  cursor: pointer;
}

/* ① 数据概览：四格。稿 :360-394 —— 本页唯一的「面」（`--glaze-deep`），四条线一并删掉。 */
.stats {
  margin-top: var(--s7);
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s6);
  padding: var(--s5) var(--s6);
  background: var(--glaze-deep);
  border-radius: var(--r3);
}
.stat {
  min-width: 0;
}
.stat .k {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.stat .v {
  margin-top: var(--s2);
  font-family: var(--font-display);
  font-size: var(--fs-xl);
  font-weight: var(--fw-med);
  line-height: 1;
  font-variant-numeric: tabular-nums;
  color: var(--brand-ink);
}

/* ② 标签页：文字 tab，选中靠字重 + 2px 下划线，不靠底色（稿 :396-405）。 */
.tabs {
  margin-top: var(--s6);
  display: flex;
  gap: var(--s7);
  border-bottom: 1px solid var(--line-2);
}
.tab {
  padding: 0 0 var(--s3);
  font-size: var(--fs-sm);
  color: var(--ink-3);
  background: none;
  border: 0;
  cursor: pointer;
  position: relative;
  white-space: nowrap;
}
.tab.on {
  color: var(--ink);
  font-weight: var(--fw-semi);
}
.tab.on::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--brand);
}
.tab .c {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-left: var(--s2);
  font-variant-numeric: tabular-nums;
}
.tab.on .c {
  color: var(--ink-2);
}
/* ⚠️ 6 枚 tab 逐枚 disabled（本笔功能量恒为 0）—— 稿没给 disabled 态，这条是本笔补的：
   不加它，`<button disabled>` 仍吃 `cursor:pointer`，光标会撒谎。
   ⚠️ `.tab.on`（特异性 0,2,0）在 `.tab[disabled]`（0,2,0）**之前** ⇒ 由 `:not(.on)` 挡住，
   否则「全部」的选中色会被禁用的灰压掉（GoodsSelect.html 的 `.chip.on` 踩过同一个坑）。 */
.tab[disabled]:not(.on) {
  cursor: not-allowed;
}

/* ③ 筛选栏（稿 :407-419）：输入框无底色、只描边。 */
.filter {
  margin-top: var(--s5);
  display: flex;
  align-items: center;
  gap: var(--s5);
  flex-wrap: wrap;
  padding-bottom: var(--s4);
}
.fld {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.fld input {
  width: 126px;
  height: 32px;
  padding: 0 var(--s3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink);
  font-size: var(--fs-sm);
}
.fld input::placeholder {
  color: var(--ink-3);
}
.fld input[disabled] {
  cursor: not-allowed;
}
.fld .til {
  font-style: normal;
  color: var(--ink-3);
}
.acts {
  margin-left: auto;
  display: flex;
  gap: var(--s3);
}
.btn {
  padding: 0 var(--s4);
  height: 32px;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: none;
  cursor: pointer;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.btn.pri {
  background: var(--brand);
  border-color: var(--brand);
  color: var(--bg);
}
.btn[disabled] {
  cursor: not-allowed;
}

/* ④ 批量操作条（稿 :421-427）：与筛选栏同一行带状语义，靠一条发丝线隔开。 */
.bulk {
  margin-top: var(--s4);
  display: flex;
  align-items: center;
  gap: var(--s5);
  padding: var(--s3) 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.bulk .sel {
  color: var(--ink-2);
}
.bulk .sel b {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}
.bulk .sp {
  margin-left: auto;
}

/* 勾选框（稿 :429-443）：与 ShoppingCart-customer.html 的 `.ck` 同尺寸（跨页共性组件）。
   ⚠️ 本页的勾选**全是空框**（「已选 0 单」）⇒ 不落 `.ck.on`（稿 :436-439 的方式：只改购物车视图）。 */
.ck {
  position: relative;
  width: 18px;
  height: 18px;
  flex: none;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
}
.ck[disabled] {
  cursor: not-allowed;
}

/* 表格：9 列（稿 :445-467）。⚠️ 列宽是量出来的：1440 下 wrap 内容宽 1132，8 道 gap×16 = 128。
   ⚠️ 三列相对稿改了宽度（本笔唯一的列宽改动，全部有实测出处，**不是因为「统一」**）：
      · 时间列 128 → **170**：本机实测单个时间戳宽 **137px**（稿量的是 115.2px，字体链不同），
        再加第二行「更改 」前缀 31px = 168 ⇒ 170。
      · 状态列 92 → **100**：「已收货·未发货」（第 5 个合法态）chip 实测 100px，92 会被压折。
      · 订单号列 132 → **112**：`ZHJC53759811` 是 12 个 mono 字符，实测约 103px，112 仍宽裕。
   固定列合计 762 ⇒ 商品列得 1132 − 762 − 128 = **242px**（稿是 272）。 */
.tbl {
  margin-top: var(--s4);
}
.thead,
.trow {
  display: grid;
  grid-template-columns: 28px 56px minmax(0, 1fr) 112px 88px 92px 100px 170px 116px;
  align-items: center;
  gap: var(--s4);
}
.thead {
  padding-bottom: var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border-bottom: 1px solid var(--line-2);
}
.trow {
  padding: var(--s4) 0;
  border-bottom: 1px solid var(--line);
}
.r {
  text-align: right;
}
.cen {
  text-align: center;
}
.thumb {
  width: 56px;
  aspect-ratio: 1/1;
  border-radius: var(--r1);
  background: var(--img);
  border: 1px solid var(--line);
  overflow: hidden;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.pn {
  min-width: 0;
  font-size: var(--fs-sm);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oid {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}
.buyer {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.money {
  text-align: right;
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  font-variant-numeric: tabular-nums;
}
/* 时间格：两行。稿 :466-467 只有一行「下单时间」；第二行「更改日期」是本笔保留的既有字段
   （稿的 A 列对照 :44-54 **漏登记**了 `SettlementDate`）—— 见上文那枚 `.time.upd` 的 title。
   ⚠️ 前缀走 `::before` 而不写进文本，是为了在 1080 断点能把 2 个字去掉、稳稳装进 116px。 */
.tcell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.time {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.time.upd::before {
  content: "更改 ";
}
.dash {
  color: var(--ink-3);
}
.send {
  background: none;
  border: 0;
  padding: 0;
  cursor: pointer;
  font-size: var(--fs-sm);
  color: var(--brand-ink);
  font-weight: var(--fw-med);
}
.send:hover {
  color: var(--brand);
}

/* 状态 chip：五态一套语言 —— 同字号 / 同内距 / 同圆角，只有色相不同（稿 :469-478）。 */
.st {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--r1);
  font-size: var(--fs-xs);
  line-height: 1.6;
  white-space: nowrap;
}
.st.draft {
  background: var(--surface-3);
  color: var(--ink-2);
}
.st.warn {
  background: var(--warning-ground);
  color: var(--warning-deep);
}
.st.info {
  background: var(--info-ground);
  color: var(--info-deep);
}
.st.err {
  background: var(--danger-ground);
  color: var(--danger-deep);
}
.st.ok {
  background: var(--success-ground);
  color: var(--success-deep);
}

/* ⑤ 分页（稿 :488-495）。 */
.pager {
  margin-top: var(--s5);
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.pager .pg {
  min-width: 32px;
  height: 32px;
  line-height: 30px;
  padding: 0 var(--s2);
  text-align: center;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: none;
  cursor: pointer;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}
.pager .pg.on {
  border-color: var(--brand);
  color: var(--brand-ink);
  font-weight: var(--fw-semi);
}
.pager .pg[disabled] {
  color: var(--ink-3);
  cursor: not-allowed;
}
.pager .tot {
  margin-left: auto;
}

/* 空态（稿 :597-602）。 */
.empty {
  padding: var(--s8) 0;
  text-align: center;
}
.empty .big {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  color: var(--ink-2);
}
.empty .p {
  margin-top: var(--s3);
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.empty .go2 {
  display: inline-block;
  margin-top: var(--s5);
  height: 40px;
  line-height: 40px;
  padding: 0 var(--s6);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: none;
  cursor: pointer;
  color: var(--ink-2);
  font-size: var(--fs-sm);
}
.empty .go2[disabled] {
  cursor: not-allowed;
}

/* 窄屏（稿 :604-624）：**只收紧、不改结构** —— 列不变、表头不隐、不加前缀（稿 :610-619 量过）。 */
@media (max-width: 1120px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  /* ⚠️ 稿 :607-609 这三行是 v5 的**残留**：稿 :371-373 明写四条线已删（「同级用间距」），
     这里却又给第 3/4 格补 `border-top`。照稿落、不改稿 —— 作为稿子自身的「表里不一」
     登记在 slot-registry.md §三，与 `子页面改造.html` 那两条同款处理。 */
  .stat:nth-child(3),
  .stat:nth-child(4) {
    border-top: 1px solid var(--line);
    margin-top: 0;
  }
  .stat:nth-child(3) {
    border-left: 0;
    padding-left: 0;
  }
  .stat:nth-child(3),
  .stat:nth-child(4) {
    padding-top: var(--s4);
  }
  /* 窄屏列宽（稿 :620 是 116px 的时间列）。本机实测该时间戳宽 137px（稿量的是 115.2px，
     字体链不同）⇒ 时间列 116 → 140、状态列保持稿的 104（「已收货·未发货」chip 实测 100px）。
     列**结构不变**（稿 :610-619 的规矩），只是固定列变宽、商品列被 1fr 自动让出 —— 252px 仍宽裕。 */
  .thead,
  .trow {
    grid-template-columns: 24px 56px minmax(0, 1fr) 104px 72px 92px 104px 140px 92px;
    gap: var(--s3);
  }
  .money {
    font-size: var(--fs-sm);
  }
  .filter {
    gap: var(--s3);
  }
  .fld input {
    width: 112px;
  }
  /* 窄屏去掉时间格第二行的「更改 」前缀（132px 装不下 142px）—— 剩 115.2px，稳。
     ⚠️ 这只是**同一条信息在窄屏少一个前缀**，字段本身不消失（title 仍在）。 */
  .time.upd::before {
    content: "";
  }
}
</style>
