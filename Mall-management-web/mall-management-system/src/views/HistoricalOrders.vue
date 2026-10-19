<template>
  <!--
    A 版线框复刻（2026-10-19）—— 历史购买记录。

    参考 `_work/ui-wireframe/a.html`：
      · `pageHistory`（`:3250-3282`）的 `.page-head`（kicker / h1 / 说明 / 右侧出口）与历史订单表；
      · `pageOrders` / `pageOrderDetail`（`:2163` / `:2205`）的 `.order-card` / `.order-head` /
        `.order-body` / `.order-foot` / `.refund-strip`（六态）写法；
      · 已复刻的 `views/OrderMine.vue` / `views/OrderCenter.vue` 的 A 版类名与间距。

    ⚠️ 本笔**只动模板结构与 `<style scoped>`**，`<script setup>` 一行未改（含 API、分页逻辑、
       六态映射、`canApply` 按钮判据、退款弹窗状态与全部数据字段）。
    ⚠️ 订单数据源仍为 `/order/historicalOrdersSelect`（`OrderDao.selectHistoryByCustomer`），
       字段面：OrderId / MerchantId / ProductInformation / Images / SettlementDate / Quantity /
       Price / RefundStatus / RefundRetryCount / RefundReason / RefundAmount / RefundHandledAt。
       ⇒ 卡片里的「处理人」只在后端回 `RefundHandler` 时才有值；本接口当前不回，故按「—」如实展示，
         不另调接口、不编数据。
    ⚠️ `Price` 是**订单实付金额**（`order` 表按单存），不是单价 ⇒ 卡片在商品行用
       `Price ÷ Quantity` 折算「单价」并注明口径，右侧另列「订单实付」。

    保留清单（逐项对照旧模板）：
      · 商品图 `v-if="order.Images"`（空图不画破图图标）、商品名、商家 ID、结单日期、订单号；
      · 退款六态：`refundStatusText` + `.rf-chip` / 六态轨道高亮（0-5 全保留）；
      · 「申请退货 / 再次申请」按钮：仍由 `canApply(order)` 判（0/2 且重试 < 2），点击仍走 `openRefund`；
      · 1/3/4/5 的只读提示文案（待商家审核 / 平台处理中 / 平台已拒绝 / 已退款）原样保留；
      · 「复制商家 ID」真事件 `copyMerchantId` 原样保留；
      · 退款原因 / 处理时间 / 处理人（`RefundReason` / `RefundHandledAt` / `RefundHandler`）；
      · 退款申请弹窗（`refundDialog.*`、`closeRefund` / `submitRefund`、busy 禁用、错误文案）；
      · 加载态（`loading`）与空态（`暂无历史订单`）；失败路径仍由 `handleApiError` 处理（未改）。
  -->
  <main class="uc-main">

    <!-- ── A 版页头（稿 `.page-head`）────────────────────────────────────── -->
    <section class="page-head">
      <div class="ph-main">
        <div class="kicker">BUYER / ORDER HISTORY</div>
        <h1 class="page-h">
          历史购买
          <!-- 稿 `pageHistory` 的条数放在说明里；这里沿用旧页「共 N 笔」的位置与 `v-if="!loading"`
               —— 加载中不显示「共 0 笔」这个假陈述。 -->
          <span v-if="!loading" class="ph-n">共 {{ orderList.length }} 笔</span>
        </h1>
        <p>已完成收货的历史订单集中在这里。售后进度按六态展示：申请、商家审核、平台处理、拒绝与退款完成，每一步都能看到落在哪个状态。</p>
      </div>
      <div class="head-actions">
        <!-- 两壳共用本组件 ⇒ 出口用模板里的 `$route` 判壳（同 `MemberCenter.vue` 先例），
             不往 `<script setup>` 里加逻辑。 -->
        <router-link
          class="btn"
          :to="$route.path.startsWith('/merchant') ? '/merchant/order/cart?tab=orders' : '/customer/cart?tab=orders'"
        >返回进行中订单</router-link>
        <router-link
          class="btn solid"
          :to="$route.path.startsWith('/merchant') ? '/merchant' : '/customer'"
        >继续购物</router-link>
      </div>
    </section>

    <!-- ── 加载态（原 `loading` 判据不变）────────────────────────────────── -->
    <div v-if="loading" class="empty">
      <div class="big">加载中…</div>
      <div class="p">正在读取历史购买记录。</div>
    </div>

    <!-- ── 空态（原文案「暂无历史订单」保留）────────────────────────────── -->
    <div v-else-if="orderList.length === 0" class="empty">
      <div class="big">暂无历史订单</div>
      <div class="p">确认收货后的订单会出现在这里；进行中的订单可在订单中心查看。</div>
      <router-link
        class="go2"
        :to="$route.path.startsWith('/merchant') ? '/merchant/order/cart?tab=orders' : '/customer/cart?tab=orders'"
      >返回进行中订单</router-link>
    </div>

    <!-- ── 历史订单卡（A 版 `.order-card`）────────────────────────────────── -->
    <div v-else class="order-list">
      <article v-for="order in orderList" :key="order.OrderId" class="order-card">

        <div class="order-head">
          <span class="oid num" title="订单号">{{ order.OrderId }}</span>
          <span class="date num" title="结单日期">{{ formatDateTime(order.SettlementDate) }}</span>
          <span v-if="order.MerchantId" class="merchant">商家 <span class="num">{{ order.MerchantId }}</span></span>
          <!-- 六态文案仍走 `refundStatusText`（REFUND_TEXT 未改），0 态保留 ✓ 图标。 -->
          <span class="rf-chip" :class="`s-${Number(order.RefundStatus) || 0}`">
            <IconGlyph v-if="(Number(order.RefundStatus) || 0) === 0" name="ok" />{{ refundStatusText(order.RefundStatus) }}
          </span>
        </div>

        <div class="order-body">
          <div class="order-items">
            <div class="order-item">
              <!-- `v-if="order.Images"` 保留：空 src 会画破图图标，稿的 `.thumb` 无图露灰底。 -->
              <div class="thumb"><img v-if="order.Images" :src="order.Images" alt="商品图片" /></div>
              <div class="oi-tx">
                <div class="nm" :title="order.ProductInformation">{{ order.ProductInformation }}</div>
                <div class="meta">
                  <span>数量 {{ Number(order.Quantity) || 1 }} 件</span>
                  <span title="单价 = 订单实付 ÷ 件数">单价 ¥{{ (Number(order.Price || 0) / (Number(order.Quantity) || 1)).toFixed(2) }}</span>
                </div>
              </div>
            </div>
          </div>
          <div class="order-total">
            <b>¥{{ Number(order.Price || 0).toFixed(2) }}</b>
            <small>订单实付</small>
          </div>
        </div>

        <!-- 退款六态轨道（稿 `refundStrip`）：文案全部取自既有 `refundStatusText`，
             不复制第二份字典；当前态 `on`，4/5 标 `terminal`（同稿的终态下划线）。 -->
        <div class="refund-strip" aria-label="退款状态六态，当前状态高亮">
          <div class="refund-cell" :class="{ on: (Number(order.RefundStatus) || 0) === 0 }"><b>00</b>{{ refundStatusText(0) }}</div>
          <div class="refund-cell" :class="{ on: (Number(order.RefundStatus) || 0) === 1 }"><b>01</b>{{ refundStatusText(1) }}</div>
          <div class="refund-cell" :class="{ on: (Number(order.RefundStatus) || 0) === 2 }"><b>02</b>{{ refundStatusText(2) }}</div>
          <div class="refund-cell" :class="{ on: (Number(order.RefundStatus) || 0) === 3 }"><b>03</b>{{ refundStatusText(3) }}</div>
          <div class="refund-cell terminal" :class="{ on: (Number(order.RefundStatus) || 0) === 4 }"><b>04</b>{{ refundStatusText(4) }}</div>
          <div class="refund-cell terminal" :class="{ on: (Number(order.RefundStatus) || 0) === 5 }"><b>05</b>{{ refundStatusText(5) }}</div>
        </div>

        <!-- 退款原因 / 处理时间 / 处理人：三列都来自既有字段；缺失一律「—」，不编数据。 -->
        <dl class="refund-meta">
          <div>
            <dt>退款原因</dt>
            <dd>{{ order.RefundReason || '—' }}</dd>
          </div>
          <div>
            <dt>处理时间</dt>
            <dd class="num">{{ order.RefundHandledAt ? formatDateTime(order.RefundHandledAt) : '—' }}</dd>
          </div>
          <div>
            <dt>处理人</dt>
            <dd>{{ order.RefundHandler || '—' }}</dd>
          </div>
        </dl>

        <div class="order-foot">
          <span class="foot-note">
            售后申请 {{ Number(order.RefundRetryCount) || 0 }} 次 · 上限 2 次
          </span>
          <div class="order-actions">
            <!-- 判据与旧模板逐字一致：`canApply(order)`（0 首申 / 2 重申，且 retry < 2）。 -->
            <button v-if="canApply(order)" class="act solid" @click="openRefund(order)">
              {{ (Number(order.RefundStatus) || 0) === 2 ? '再次申请' : '申请退货' }}
            </button>
            <span v-else-if="(Number(order.RefundStatus) || 0) === 1" class="act-hint">待商家审核</span>
            <span v-else-if="(Number(order.RefundStatus) || 0) === 3" class="act-hint">平台处理中</span>
            <span v-else-if="(Number(order.RefundStatus) || 0) === 4" class="act-hint">平台已拒绝</span>
            <span v-else-if="(Number(order.RefundStatus) || 0) === 5" class="act-hint">已退款</span>
            <!-- 真事件（`copyMerchantId`）原样保留；文案照稿「复制商家 ID」。 -->
            <button class="act" @click="copyMerchantId(order.MerchantId)">复制商家 ID</button>
          </div>
        </div>

      </article>
    </div>

    <!-- 售后申请弹窗（批四第二批 commit 4）。金额**只读展示**（后端取 order.Price 快照），
         前端不传金额 —— 见 api/order.js ⑤ 组头注释。绑定与禁用条件一个未动。 -->
    <div v-if="refundDialog.open" class="rf-mask" @click.self="closeRefund">
      <div class="rf-box">
        <div class="rf-head">
          <h2 class="rf-title">申请退货</h2>
          <span class="rf-kicker">AFTER-SALES</span>
        </div>
        <p class="rf-sub">
          订单 <span class="num">{{ refundDialog.order && refundDialog.order.OrderId }}</span>
          · 可退金额 ¥{{ Number((refundDialog.order && refundDialog.order.Price) || 0).toFixed(2) }}
        </p>
        <textarea
          v-model="refundDialog.reason"
          class="rf-input"
          rows="4"
          maxlength="255"
          placeholder="请填写售后原因（必填，最多 255 字）"
        ></textarea>
        <p v-if="refundDialog.error" class="rf-err">{{ refundDialog.error }}</p>
        <div class="rf-actions">
          <button class="btn-text" :disabled="refundDialog.busy" @click="closeRefund">取消</button>
          <button class="rf-submit" :disabled="refundDialog.busy" @click="submitRefund">
            {{ refundDialog.busy ? '提交中…' : '提交申请' }}
          </button>
        </div>
      </div>
    </div>

  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { orderRefundApply } from '../api/order'
import { resolveBuyerId } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移

const orderList = ref([])
const loading = ref(false)

// 降级链已收敛到 src/api/auth.js:resolveBuyerId()（与 ShoppingCart 逐字相同）。
const getBuyerId = () => resolveBuyerId()

const formatDateTime = (dateStr) => {   // ⚠️ **2026-10-10（问题 2，即时区：用户报「轨迹 22:27:51 vs 订单列表 14:29:16」差 8 小时）**：后端时间列是**库时钟**、库时钟是 **UTC**（VM / a3 实测 `@@system_time_zone=UTC`，见 `context-packs/c3-block-1-infra.md#pack-c3-block-1-infra-frontend-nav`）⇒ 交回 `'YYYY-MM-DD HH:mm:ss'` **无时区文本**；少补末尾那个 `Z`，`new Date()` 按**浏览器本地时区**解释它 ⇒ 整整差一个偏移且**错得很安静**。手法照 `views/admin/AuditPage.vue:236` 的 `toLocal`（原 190 行 —— 2026-10-11 收口按 refs-scan 重指；本页此前漏了）。
  if (!dateStr) return '未知'
  const date = new Date(`${String(dateStr).replace(' ', 'T')}Z`)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 复制回退方案
const fallbackCopy = (text) => {
  const textarea = document.createElement('textarea')
  textarea.value = text
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
  alert('复制成功：' + text)
}

const copyMerchantId = (merchantId) => {
  if (!merchantId) {
    alert('商家ID为空')
    return
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(merchantId).then(() => {
      alert('复制成功：' + merchantId)
    }).catch(() => {
      fallbackCopy(merchantId)
    })
  } else {
    fallbackCopy(merchantId)
  }
}

// ── 售后状态机（批四第二批 commit 4）────────────────────────────────────────────
// 文案与后端六态一一对应：0 无 / 1 待商家 / 2 商家拒（可重申）/ 3 待平台 / 4 平台拒（终态）/ 5 已退款。
const REFUND_TEXT = { 0: '已经结单', 1: '待商家审核', 2: '商家已拒绝', 3: '平台处理中', 4: '平台已拒绝', 5: '已退款' }
const refundStatusText = (s) => REFUND_TEXT[Number(s) || 0] || '已经结单'
// 可申请：0 首申 / 2 重申；且次数未用尽（上限 2）。其余状态后端也会拒，这里只是不展示按钮。
const canApply = (order) => {
  const status = Number(order.RefundStatus) || 0
  const retry = Number(order.RefundRetryCount) || 0
  return (status === 0 || status === 2) && retry < 2
}

const refundDialog = ref({ open: false, order: null, reason: '', busy: false, error: '' })

const openRefund = (order) => {
  refundDialog.value = { open: true, order, reason: '', busy: false, error: '' }
}
const closeRefund = () => {
  refundDialog.value.open = false
}
const submitRefund = async () => {
  const d = refundDialog.value
  const reason = String(d.reason || '').trim()
  if (!reason) {
    d.error = '请填写售后原因'
    return
  }
  const customerId = getBuyerId()
  if (!customerId) {
    d.error = '未登录，无法申请售后'
    return
  }
  d.busy = true
  d.error = ''
  try {
    const res = await orderRefundApply({ CustomerId: customerId, OrderId: d.order.OrderId, Reason: reason })
    if (res.success) {
      d.open = false
      alert(res.msg || '售后申请已提交')
      await loadHistoricalOrders()
    } else {
      // 非本人 / 未收货 / 次数上限 / 已退款：后端文案直接展示，不合并成一句「失败」
      d.error = res.msg || '提交失败'
    }
  } catch (err) {
    handleApiError(err, '提交失败')
  } finally {
    d.busy = false
  }
}

const loadHistoricalOrders = async () => {
  const customerId = getBuyerId()
  if (!customerId) {
    console.warn('未登录，无法获取历史订单')
    return
  }
  loading.value = true
  try {
    const params = new URLSearchParams()
    params.append('customerId', customerId)
    const res = await http.post(endpoints.order.historicalOrdersSelect, params.toString())
    const data = await res.json()
    if (data.success) {
      orderList.value = data.list
    } else {
      console.error('获取历史订单失败', data.msg)
    }
  } catch (err) {
    handleApiError(err, '请求出错')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadHistoricalOrders()
})
</script>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════════════
   A 版线框复刻（2026-10-19）

   视觉基线：
     `_work/ui-wireframe/a.html` `pageHistory`（`:3250-3282`）的 `.page-head`；
     `pageOrders`（`:2163`）的 `.order-card` / `.order-head` / `.order-body` /
     `.order-item` / `.order-total` / `.order-foot`；
     `refundStrip`（`:1695`）的 `.refund-strip` / `.refund-cell`（六态 + `on` + `terminal`）。
   已落同款：`views/OrderMine.vue`（`.page-head` / `.kicker` / `.head-actions` / `.order-card` 族）。

   ⚠️ 稿的 `.uc` / `.uc-menu` **不在这里** —— 左菜单在 `UserCenter.vue`、外框在 `AppShell.vue`
      的 `.user-page`（A 版已全宽居中）。本页只承载右栏内容。
   ⚠️ `button` 重设必须留在 scoped 里：稿的动作按钮是裸 `<button>`，不重设会带 UA 的
      灰底 / outset 边框 / Arial 字号（`UserInfoPage.vue` 实踩过）。
   ⚠️ `box-sizing` 不重复 —— `tokens.css` 的 `*` 重设已是全局。
   ══════════════════════════════════════════════════════════════════════════════ */

/* ── 稿的 `button{font:inherit;color:inherit;background:none;border:0;cursor:pointer}`，限定本页 ── */
button {
  font: inherit;
  color: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}

/* ── 本页根（与同壳子页同槽位）────────────────────────────────────────────── */
.uc-main {
  flex: 1;
  width: 100%;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头（稿 `.page-head` `:110-112`）────────────────────────────────────── */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s5);
  padding-bottom: var(--s4);
  border-bottom: 1px solid var(--line-2);
  margin-bottom: var(--s5);
}
.kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .16em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.page-head .page-h {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  margin-top: 6px;
  font-family: var(--font-display);
  font-size: var(--fs-xl);
  font-weight: var(--fw-med);
  line-height: 1.15;
  letter-spacing: -.02em;
}
.page-h .ph-n {
  font-family: var(--font);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  letter-spacing: 0;
  color: var(--ink-3);
}
.page-head p {
  margin-top: 8px;
  max-width: 62ch;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-2);
}
.head-actions { display: flex; gap: var(--s2); flex: 0 0 auto; }
.head-actions .btn {
  display: inline-flex;
  align-items: center;
  height: 36px;
  padding: 0 var(--s5);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  text-decoration: none;
  background: var(--surface);
  white-space: nowrap;
}
.head-actions .btn:hover { border-color: var(--brand-ink); color: var(--brand-ink); }
.head-actions .btn.solid {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.head-actions .btn.solid:hover {
  background: var(--brand-ink);
  border-color: var(--brand-ink);
  color: #fff;
}

/* ── 订单卡（稿 `.order-card` `:310-336`）─────────────────────────────────── */
.order-list { display: flex; flex-direction: column; gap: var(--s4); }
.order-card {
  border: 1px solid var(--line);
  border-radius: var(--r2);
  background: var(--surface);
  overflow: hidden;
}
.order-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
  padding: var(--s3) var(--s5);
  border-bottom: 1px solid var(--line);
  background: var(--surface-2);
  font-size: var(--fs-xs);
}
.oid { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink); }
.date { font-family: var(--font-mono); font-size: 11px; color: var(--ink-3); }
.merchant { color: var(--ink-3); }
.merchant .num { color: var(--ink-2); }
.rf-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 2px 8px;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  color: var(--ink-3);
  white-space: nowrap;
}
/* 六态配色沿用旧页口径：1/3 进行中 = 品牌色；5 已退款 = 品牌色 + 区块底；2/4 中性。 */
.rf-chip.s-1, .rf-chip.s-3 { color: var(--brand-ink); border-color: var(--brand); }
.rf-chip.s-5 { color: var(--brand-ink); border-color: var(--brand); background: var(--zone); }

.order-body { display: flex; align-items: center; gap: var(--s5); padding: var(--s4) var(--s5); }
.order-items { flex: 1; min-width: 0; }
.order-item { display: flex; align-items: center; gap: var(--s4); min-width: 0; }
.thumb {
  width: 56px;
  height: 68px;
  flex: none;
  border-radius: var(--r1);
  background: var(--img);
  overflow: hidden;
}
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.oi-tx { min-width: 0; }
.oi-tx .nm {
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  line-height: 1.35;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.oi-tx .meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s3);
  margin-top: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.order-total {
  flex: 0 0 auto;
  padding-left: var(--s5);
  border-left: 1px solid var(--line);
  text-align: right;
}
.order-total b {
  display: block;
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.order-total small { display: block; margin-top: 2px; font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 退款六态轨道（稿 `.refund-strip` / `.refund-cell` `:344-354`）──────────── */
.refund-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  margin: 0 var(--s5);
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.refund-cell {
  min-width: 0;
  padding: 8px;
  border-right: 1px solid var(--line);
  font-size: 10px;
  line-height: 1.45;
  color: var(--ink-3);
  overflow-wrap: anywhere;
}
.refund-cell:last-child { border-right: 0; }
.refund-cell b {
  display: block;
  margin-bottom: 2px;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.refund-cell.on { background: var(--ink); color: #fff; }
.refund-cell.on b { color: #c9c9c9; }
.refund-cell.terminal { border-bottom: 2px solid var(--ink-2); }

/* ── 退款原因 / 处理时间 / 处理人 ──────────────────────────────────────────── */
.refund-meta {
  display: grid;
  grid-template-columns: 1.4fr 1fr .8fr;
  margin: 0 var(--s5);
  border-bottom: 1px solid var(--line);
}
.refund-meta > div { min-width: 0; padding: var(--s3) var(--s3) var(--s3) 0; }
.refund-meta > div + div { padding-left: var(--s3); border-left: 1px solid var(--line); }
.refund-meta dt {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: .06em;
  color: var(--ink-3);
}
.refund-meta dd {
  margin-top: 3px;
  font-size: var(--fs-xs);
  line-height: 1.5;
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

/* ── 卡片底栏与动作（稿 `.order-foot` / `.order-actions`）──────────────────── */
.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s4);
  flex-wrap: wrap;
  padding: var(--s3) var(--s5);
}
.foot-note { font-size: var(--fs-xs); color: var(--ink-3); }
.order-actions { display: flex; align-items: center; gap: var(--s2); flex-wrap: wrap; }
.act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
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
  text-decoration: none;
}
.act:hover { border-color: var(--brand); }
.act.solid { background: var(--brand); border-color: var(--brand); color: #fff; }
.act.solid:hover { background: var(--brand-ink); border-color: var(--brand-ink); }
.act-hint { font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 数字列 ──────────────────────────────────────────────────────────────── */
.num { font-variant-numeric: tabular-nums; }

/* ── 加载 / 空态（稿 `.empty`；文案沿用既有实现）──────────────────────────── */
.empty {
  padding: var(--s8) var(--s6);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  background: var(--surface);
  text-align: center;
}
.empty .big {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  color: var(--ink-2);
}
.empty .p {
  margin-top: var(--s3);
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-3);
}
.empty .go2 {
  display: inline-flex;
  align-items: center;
  height: 36px;
  margin-top: var(--s5);
  padding: 0 var(--s5);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  text-decoration: none;
}
.empty .go2:hover { border-color: var(--brand-ink); color: var(--brand-ink); }

/* ── 退款申请弹窗（绑定与禁用条件未改，仅视觉对齐 A 版发丝线卡片）───────────── */
.rf-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, .32);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 60;
}
.rf-box {
  width: min(440px, calc(100vw - 32px));
  padding: var(--s6);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  background: var(--bg);
  box-shadow: 0 12px 40px rgba(0, 0, 0, .18);
}
.rf-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--s3); }
.rf-kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .16em;
  color: var(--ink-3);
}
.rf-title {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
}
.rf-sub { margin-top: var(--s2); margin-bottom: var(--s4); font-size: var(--fs-sm); color: var(--ink-3); }
.rf-input {
  width: 100%;
  resize: vertical;
  padding: var(--s3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  font: inherit;
  color: var(--ink);
  background: var(--bg);
}
.rf-input:focus { outline: none; border-color: var(--brand); }
.rf-err { margin-top: var(--s2); font-size: var(--fs-xs); color: #c0392b; }
.rf-actions { display: flex; align-items: center; justify-content: flex-end; gap: var(--s4); margin-top: var(--s4); }
.btn-text {
  padding: 2px 0;
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-sm);
  color: var(--ink-3);
  transition: color .14s, border-color .14s;
}
.btn-text:hover { color: var(--brand-ink); border-color: var(--brand); }
.rf-submit {
  padding: 7px 16px;
  border: 1px solid var(--brand);
  border-radius: var(--r1);
  font-size: var(--fs-sm);
  color: #fff;
  background: var(--brand);
}
.rf-submit:hover { background: var(--brand-ink); border-color: var(--brand-ink); }
.rf-submit:disabled, .btn-text:disabled { opacity: .55; cursor: default; }

/* ── 窄档：页头/卡体/退款三列纵向堆叠，六态轨道改 3×2 ─────────────────────── */
@media (max-width: 760px) {
  .page-head { flex-direction: column; align-items: flex-start; }
  .order-body { flex-direction: column; align-items: stretch; gap: var(--s3); }
  .order-total { padding-left: 0; border-left: 0; text-align: left; }
  .order-foot { flex-direction: column; align-items: flex-start; }
  .rf-chip { margin-left: 0; }

  .refund-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .refund-cell { border-bottom: 1px solid var(--line); }
  .refund-cell:nth-child(3n) { border-right: 0; }
  .refund-cell:nth-child(n+4) { border-bottom: 0; }
  .refund-cell.terminal { border-bottom-width: 2px; }

  .refund-meta { grid-template-columns: 1fr; }
  .refund-meta > div { padding-right: 0; }
  .refund-meta > div + div { padding-left: 0; border-left: 0; border-top: 1px solid var(--line); }
}
</style>
