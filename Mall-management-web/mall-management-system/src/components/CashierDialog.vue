<!-- CashierDialog.vue —— 收银台弹窗（C3 块 4 补丁；**两个入口共用**）。

  ⚠️ 为什么抽成组件：收银台原先只内联在 `views/OrderMine.vue`（「我的订单」的【付款】）——
     商品详情页的【立即购买】于是绕开了它、直接建了一张**已付款**的单（用户 2026-10-10 报的 Bug 1）。
     两个入口要**走同一个收银台**，就把「选支付方式 → 确认支付」这段收在这里，两边都渲染它。

  ⚠️ 本仓无组件库，这份 `<style scoped>` 是从 `OrderMine.vue` **整段搬过来**的（含 `.scrim` / `.dlg` /
     `.act` 等与弹窗共用的类）——之所以要搬全：`<style scoped>` 只把作用域属性打在**子组件根节点**上，
     根节点**以内**的元素拿不到父页面的样式 ⇒ 组件必须自带完整一份。

  ⚠️ 这是**模拟支付**：本仓没有支付网关，`orderPay` 只把「选了哪种方式」记进 `order.PaymentMethod`
     并回一个生成的 `TradeNo`。文案必须说清楚，别让用户以为真扣了钱。

  ⚠️ 2026-10-19 视觉重排：弹窗改成 A 版 `pageCheckout`（确认订单）的「左清单 / 右汇总」语言。
     **只动模板与 scoped 样式**；`orderPay`、支付方式、loading、错误提示、成功回调一字未改。
     商品清单只画调用方真的传进来的数据（`Items` / `ProductInformation` / `Images` / `Quantity`），
     没有地址、备注、选券、积分这些字段 —— 本组件也**不造**（见下方模板里那条注释）。

  用法：
    <CashierDialog :order="payFor" @close="payFor = null" @paid="onPaid" />
  `order` 为 null ⇒ 不渲染（开/关都靠它）。
-->
<template>
  <div v-if="order" class="cscrim" @click.self="close">
    <div class="cdlg" role="dialog" aria-modal="true" aria-label="收银台" @click.stop>
      <!-- ── 页头：A 版 `.page-head` 的语言（kicker + 大标题 + 一句说明）── -->
      <header class="cdlg-h">
        <div class="cdlg-title">
          <div class="kicker">BUYER / CHECKOUT</div>
          <h1>收银台</h1>
          <p>确认订单信息，选择支付方式后完成模拟支付。</p>
        </div>
        <button class="cx" @click="close" aria-label="关闭">×</button>
      </header>

      <div class="checkout-layout">
        <!-- ══ 左列：商品清单 / 支付方式 / 订单号与演示说明（A 版左栏）════════════
             ⚠️ 地址、备注、选券、积分这几块在**本组件的数据里不存在**：
                收银台拿到的是已经建好的订单（券与会员价在建单时就算完了），
                没有可编辑的地址 / 备注字段，也没有券包 / 积分余额的数据源。
                ⇒ 这里不画空壳、不塞假数据；能画的是订单真的带回来的清单与支付方式。 -->
        <div class="co-main">
          <section class="panel flush">
            <div class="section-title">
              <h2>商品清单</h2>
              <span class="tiny">{{ itemSummary }}</span>
            </div>
            <div v-if="displayItems.length" class="mini-items">
              <div v-for="it in displayItems" :key="it.key" class="mini-item">
                <div class="thumb">
                  <img v-if="it.img" :src="it.img" :alt="it.name" />
                  <span v-else aria-hidden="true">{{ it.thumb }}</span>
                </div>
                <div class="mi-main">
                  <div class="nm">{{ it.name }}</div>
                  <div class="meta">
                    <span v-if="it.spec">规格 {{ it.spec }}</span>
                    <span v-if="it.qty">数量 {{ it.qty }}</span>
                  </div>
                </div>
                <div class="amt">
                  <template v-if="it.amount != null">¥{{ formatPrice(it.amount) }}</template>
                  <span v-else class="unknown" title="该行未随单返回成交价（订单列表只回商品名与编号）；合计见右栏">—</span>
                </div>
              </div>
            </div>
            <p v-else class="co-empty">本笔订单未随单返回商品明细，可在订单详情中查看。</p>
          </section>

          <section class="panel flush co-pay">
            <div class="section-title">
              <h2>支付方式</h2>
              <span class="tiny">演示环境不发起真实支付</span>
            </div>
            <div class="pay-methods">
              <label v-for="m in PAY_METHODS" :key="m" class="pay-method" :class="{ on: method === m }">
                <input type="radio" name="paymethod" :value="m" v-model="method" />
                <b>{{ m }}</b>
                <small>模拟支付 · 不扣款</small>
              </label>
            </div>
          </section>

          <div class="cart-note">
            <span>订单号 <span class="mono">{{ order.OrderId }}</span></span>
            <span>待付款 · 支付成功后由父页面刷新状态</span>
          </div>

          <p class="pay-note">⚠️ 演示环境的<b>模拟收银台</b>：本仓没有支付网关，点「确认支付」只会把所选方式与一个演示流水号记进订单，不会真的扣款。</p>
        </div>

        <!-- ══ 右列：订单汇总 + 提交（A 版 `.checkout-desk`）════════════════════ -->
        <aside class="checkout-desk">
          <div class="cd-top"><strong>订单汇总</strong><span>CHECKOUT / 01</span></div>
          <div class="cd-body">
            <div class="sum-row" :title="order.OriginalTotal == null && order.MemberDiscount == null && order.CouponDiscount == null ? '该单未返回原价与优惠拆分，商品小计按实付金额显示' : null"><span>商品小计</span><b>¥{{ formatPrice(subtotal) }}</b></div>
            <div class="sum-row">
              <span>会员优惠</span>
              <b :class="{ neg: Number(order.MemberDiscount) > 0 }">{{ Number(order.MemberDiscount) > 0 ? '−¥' + formatPrice(order.MemberDiscount) : '—' }}</b>
            </div>
            <div class="sum-row">
              <span>优惠券</span>
              <b :class="{ neg: Number(order.CouponDiscount) > 0 }">{{ order.CouponDiscount == null ? '—' : (Number(order.CouponDiscount) > 0 ? '−¥' + formatPrice(order.CouponDiscount) : '未使用') }}</b>
            </div>
            <div class="coupon-row">
              <!-- ⚠️ 订单列表那一路**不回** `CouponDiscount` 键（`OrderDao.selectActiveByCustomer` 没这一列）；
                   此时只能画「—」，不能把「没返回」说成「未使用」—— `order.Price` 已经是实付价。 -->
              <span>{{ order.CouponDiscount == null ? '优惠券抵扣额未随单返回' : (Number(order.CouponDiscount) > 0 ? '已使用优惠券' : '未使用优惠券') }}</span>
              <span>支付方式 {{ method }}</span>
            </div>
            <div class="sum-row total"><span>应付合计</span><b><i>¥</i>{{ formatPrice(order.Price) }}</b></div>
            <p class="cd-note" v-if="Number(order.Quantity) > 0">共 {{ order.Quantity }} 件商品</p>
          </div>
          <div class="pay-actions">
            <button class="go" :disabled="paying" @click="confirm">{{ paying ? '支付中…' : '确认支付 · ¥' + formatPrice(order.Price) }}</button>
            <button class="go-ghost" :disabled="paying" @click="close">取消</button>
          </div>
          <div class="desk-note">演示环境，不发起真实支付；实付金额以服务端返回为准。</div>
        </aside>
      </div>
    </div>
  </div>
</template>

<script setup>
/* ⚠️ 这条 `eslint-disable` 是**必需**的，不是偷懒：本仓 `eslint-plugin-vue` 是 **8.7.1**，
   其 `vue3-essential` **不带** `vue/setup-compiler-macros` 宏环境 ⇒ `defineProps` / `defineEmits`
   一律被判 `no-undef`、`npm run build` 直接红。房内先例：`components/TemplatePicker.vue:44-49`
   （那里把原因写全了）、`OrderCenter.vue` / `ShoppingCart.vue` 的块级同款。
   ⇒ 本仓**没有任何 .vue 用了这些宏却没加 disable**，别把这条删掉「试试看」。 */
/* eslint-disable no-undef */
import { computed, ref, watch } from 'vue'
import { orderPay } from '../api/order'

/* ⚠️ 支付方式只有这三种（演示），后端 `PaymentMethod` 是自由文本、无字典表约束（见 tech-debt TD-29）。 */
const PAY_METHODS = ['支付宝', '微信支付', '银行卡']

const props = defineProps({
  /** 要付款的那一单；**至少要有 `OrderId`**（`Price` 只用于显示）。null ⇒ 关窗。 */
  order: { type: Object, default: null }
})
const emit = defineEmits(['close', 'paid'])

const method = ref(PAY_METHODS[0])
const paying = ref(false)

/* 「选了方式」与「付了款」是两步 ── 并成一步（点【付款】即支付）就没法传 `paymentMethod` 了。
   ⚠️ 每次**开窗**复位到第一种：`order` 由 null 变非 null 就是一次新的开窗（原 `openPay` 的行为）。 */
watch(() => props.order, (o) => { if (o) method.value = PAY_METHODS[0] })

const formatPrice = (price) => Number(price || 0).toFixed(2)

/* ── 纯展示 computed（2026-10-19 A 版视觉重排；不碰任何请求 / 状态）─────────────
   `Items` 是 `orderAdd` 的显式明细（JSON 数组串，`{code,name,price,qty}`）；订单列表那一路
   目前只回 `ProductInformation`（`名:码；名:码`）与 `Quantity`。两条都**只读真数据**：
   没有 `Items` 就退回按 `ProductInformation` 逐段画，拿不到的单行金额画「—」，不补假数。 */

/* 商品小计：有 `OriginalTotal`（立即购买那一路）就用它；没有时按「实付 + 已减掉的会员/券」
   反推 —— 与 `OrderService` 的 `Price = 会员价 − 券` 同序，非会员/无券时逐字等于 `Price`。 */
const subtotal = computed(() => {
  const o = props.order
  if (!o) return 0
  if (o.OriginalTotal != null && Number.isFinite(Number(o.OriginalTotal))) return Number(o.OriginalTotal)
  return Number(o.Price || 0) + Number(o.MemberDiscount || 0) + Number(o.CouponDiscount || 0)
})

const displayItems = computed(() => {
  const o = props.order
  if (!o) return []
  const img = Array.isArray(o.Images) ? (o.Images[0] || '') : (typeof o.Images === 'string' ? o.Images : '')

  if (typeof o.Items === 'string' && o.Items.trim()) {
    try {
      const rows = JSON.parse(o.Items)
      if (Array.isArray(rows) && rows.length) {
        return rows
          .filter((r) => r && (r.name || r.code))
          .map((r, i) => {
            const name = String(r.name || r.code || '商品')
            const qty = Number(r.qty) > 0 ? Number(r.qty) : null
            const price = Number(r.price)
            return {
              key: `${r.code || i}-${i}`,
              name,
              spec: r.code || '',
              qty,
              amount: Number.isFinite(price) && qty ? price * qty : null,
              img: i === 0 ? img : '',
              thumb: name.slice(0, 1)
            }
          })
      }
    } catch {
      /* 解析失败就退回 `ProductInformation`（同 OrderService.resolveItems 的 fail-open 口径） */
    }
  }

  const info = typeof o.ProductInformation === 'string' ? o.ProductInformation : ''
  if (!info.trim()) return []
  const rows = info.split('；').filter((seg) => seg.trim()).map((seg, i) => {
    const text = seg.trim()
    const at = text.lastIndexOf(':')
    const name = at > 0 ? text.slice(0, at) : text
    const code = at > 0 ? text.slice(at + 1) : ''
    return {
      key: `${code || i}-${i}`,
      name: name || code || '商品',
      spec: code,
      qty: null,
      amount: null,
      img: i === 0 ? img : '',
      thumb: (name || code || '商').slice(0, 1)
    }
  })
  /* 单行订单的 `Quantity` 就是这一行的件数、`subtotal` 就是这一行的小计 —— 这两条是**等式**，
     不是补数；多行时无法把总数/总价摊到各行，仍画「—」（右栏合计照常可见）。 */
  if (rows.length === 1) {
    const qty = Number(o.Quantity)
    if (qty > 0) rows[0].qty = qty
    rows[0].amount = subtotal.value
  }
  return rows
})

const itemSummary = computed(() => {
  const count = displayItems.value.length
  const qty = Number(props.order && props.order.Quantity)
  if (count && qty > 0) return `${count} 个条目 / 共 ${qty} 件`
  if (count) return `${count} 个条目`
  if (qty > 0) return `共 ${qty} 件`
  return '明细未随单返回'
})

const close = () => emit('close')

async function confirm() {
  if (!props.order || paying.value) return
  paying.value = true
  try {
    const result = await orderPay(props.order.OrderId, method.value)
    if (result.success) {
      // ⚠️ `TradeNo` 是后端生成的**演示流水号**，成功时才回 —— 拿不到就退回一句无号的文案。
      alert(result.tradeNo ? `支付成功！演示流水号 ${result.tradeNo}` : '支付成功！')
      emit('paid', result)          // 关窗与「付完要做什么」（刷新列表 / 跳页）**交给父页面**
    } else {
      alert('付款失败：' + (result.msg || '未知错误'))
    }
  } catch (err) {
    console.error('付款请求出错', err)
    alert('网络错误，请稍后重试')
  } finally {
    paying.value = false
  }
}
</script>

<!-- ⚠️ 类名统一加 `c` 前缀（`cscrim` / `cdlg`）**不是**排版偏好：
     若这里也叫 `.scrim`，父页面（OrderMine）同名的 `scoped` 规则会**同时命中本组件根节点**
     （Vue 会把父作用域属性打在子组件根上）⇒ 两份规则叠加、改一处不知另一处在生效。
     全仓弹窗都叫 `.scrim/.dlg` ⇒ 撞名迟早发生，组件内自成一套前缀最省事。
     内部的 `.panel / .pay-method / .checkout-desk …` 只在本组件的 scoped 作用域里生效。 -->
<style scoped>
.cscrim {
  position: fixed;
  inset: 0;
  z-index: 300;   /* ⚠️ **2026-10-10（Bug 1 二次修）：`60` 是错的，已改 `300`。** 改前本组件开在**商品详情页**时会被那两层面板压住 —— 该页 `.popup-mask`（购入模块弹窗）`z-index:150`、`.scrim`（结算确认 / 各 tab 弹窗）`200`，而收银台只有 `60` ⇒ 用户看到的正是「收银台弹出、却被白色弹窗挡在后面」（父页面那两处已在各自入口**先关**，本档是**第二道保险**：即便将来多一个忘了关的面板，收银台也不会被盖）。`300` 不在本组件手里凭空取 —— 它是全仓既有的「**弹窗叠弹窗**」档，出处 `views/TemplatePicker.vue:105`（它当年也是为压过 `GoodsUp.vue` 的 `200` 取 300，同样写了注释）：`60`（`OrderMine`/`OrderCenter` 的页内弹窗）< `150`（商品页购入模块）< `200`（`GoodsUp`/商品页大弹窗）< **`300`（压在别的弹窗之上的弹窗）**。⚠️ 别顺手把它降到 100/200 的「顺眼值」：那会重新埋回这个 bug，而且**错得很安静**（弹窗照常渲染，只是看不见）。⚠️ **本档买到的是「第二道保险」，不是本次修复的**主因** —— 2026-10-10 实测（`scripts/probes/c3b4p-bug1b-stack.mjs` 的突变对照）：① **只**把本行打回 `60`、不动 `ProductPage.vue:654` 的「先关下层弹窗」⇒ 探针**仍全过**（遮挡物已撤，没东西能盖住它）；② **两半一起**退回 ⇒ 该探针 ②③ 双红（`elementFromPoint` 命中 `.p-buy`）。⇒ 真正修好用户那个现象的是**关窗那半**，本档只负责「将来有人再加一个忘了关的面板时不再复现」。 */
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s5);
}
.cdlg {
  width: min(980px, 100%);
  max-height: 90vh;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.22);
}

/* ── 弹窗页头（A 版 `.page-head`）────────────────────────────────────────── */
.cdlg-h {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s5);
  padding: var(--s5) var(--s6);
  border-bottom: 1px solid var(--line);
}
.cdlg-title { min-width: 0; }
.kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .16em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.cdlg-h h1 {
  margin: 6px 0 0;
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  line-height: 1.05;
  font-weight: var(--fw-semi);
  letter-spacing: -.02em;
  color: var(--ink);
}
.cdlg-h p {
  margin: 6px 0 0;
  max-width: 58ch;
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--ink-2);
}
.cdlg-h .cx {
  flex: none;
  width: 34px;
  height: 34px;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  font-size: var(--fs-md);
  line-height: 1;
  color: var(--ink-2);
  cursor: pointer;
}
.cdlg-h .cx:hover { border-color: var(--brand-ink); color: var(--brand-ink); }

/* ── 左清单 / 右汇总（A 版 `.checkout-layout`）────────────────────────────── */
.checkout-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: var(--s6);
  align-items: start;
  padding: var(--s5) var(--s6) var(--s6);
}
.co-main { min-width: 0; }

/* A 版 `.panel.flush`：无外框、只留一条上边线。 */
.panel.flush {
  border: 0;
  border-top: 1px solid var(--line);
  padding: var(--s4) 0 0;
}
.panel.flush + .panel.flush { margin-top: var(--s5); }

.section-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s4);
  border-bottom: 1px solid var(--line);
  padding-bottom: var(--s3);
  margin-bottom: var(--s4);
}
.section-title h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--fs-md);
  line-height: 1.1;
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.section-title .tiny { font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 商品清单（A 版 `.mini-items` / `.mini-item`）────────────────────────── */
.mini-items { border-top: 1px solid var(--line); }
.mini-item {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  gap: var(--s3);
  align-items: center;
  padding: var(--s3) 0;
  border-bottom: 1px solid var(--line);
}
.mini-item .thumb {
  width: 52px;
  height: 62px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: var(--r1);
  background: var(--surface-2);
  color: var(--brand-ink);
  font-family: var(--font-display);
  font-size: var(--fs-md);
}
.mini-item .thumb img { width: 100%; height: 100%; object-fit: cover; }
.mini-item .nm { font-size: var(--fs-sm); color: var(--ink); overflow-wrap: anywhere; }
.mini-item .meta { margin-top: 4px; font-size: var(--fs-xs); color: var(--ink-3); }
.mini-item .meta span + span { margin-left: var(--s2); padding-left: var(--s2); border-left: 1px solid var(--line-2); }
.mini-item .amt {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--ink);
  text-align: right;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.mini-item .amt .unknown { color: var(--ink-3); }
.co-empty { margin: var(--s4) 0 0; font-size: var(--fs-sm); color: var(--ink-3); }

/* ── 支付方式（A 版 `.pay-methods` / `.pay-method`）──────────────────────── */
.pay-methods {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--line);
  border-radius: var(--r1);
  overflow: hidden;
}
.pay-method {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: var(--s4);
  border-right: 1px solid var(--line);
  text-align: left;
  background: var(--surface);
  cursor: pointer;
  user-select: none;
}
.pay-method:last-child { border-right: 0; }
.pay-method:hover { background: var(--surface-2); }
.pay-method.on { background: var(--ink); color: var(--surface); }
.pay-method.on:hover { background: var(--ink); }
.pay-method input { position: absolute; opacity: 0; width: 0; height: 0; }
.pay-method:focus-within { outline: 2px solid var(--brand); outline-offset: -2px; }
.pay-method b { font-size: var(--fs-sm); font-weight: var(--fw-med); }
.pay-method small { font-size: var(--fs-xs); color: var(--ink-3); }
.pay-method.on small { color: var(--surface-2); }

/* ── 订单号 / 演示说明（A 版 `.cart-note`）───────────────────────────────── */
.cart-note {
  display: flex;
  justify-content: space-between;
  gap: var(--s4);
  flex-wrap: wrap;
  margin-top: var(--s5);
  padding: var(--s3) var(--s4);
  border: 1px dashed var(--line-2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.mono { font-family: var(--font-mono); color: var(--ink-2); }
.pay-note {
  margin: var(--s4) 0 0;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
}

/* ── 右栏：订单汇总（A 版 `.checkout-desk`）──────────────────────────────── */
.checkout-desk {
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  overflow: hidden;
}
.cd-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s3);
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--line);
  background: var(--surface-2);
}
.cd-top strong { font-size: var(--fs-md); font-weight: var(--fw-med); color: var(--ink); }
.cd-top span {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  letter-spacing: .08em;
}
.cd-body { padding: var(--s5); display: flex; flex-direction: column; gap: var(--s3); }
.sum-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s3);
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.sum-row b {
  font-weight: var(--fw-med);
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.sum-row .neg { color: var(--accent); }
.sum-row.total {
  margin-top: var(--s1);
  padding-top: var(--s4);
  border-top: 1px solid var(--line);
  font-size: var(--fs-md);
  color: var(--ink);
}
.sum-row.total b {
  font-family: var(--font-display);
  font-size: var(--fs-lg);   /* A 版 `.sum-row.total b` = 24px */
  line-height: 1;
  color: var(--accent);
}
.sum-row.total b i { font-style: normal; font-size: var(--fs-sm); margin-right: 2px; }

.coupon-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  padding: var(--s2) var(--s3);
  border: 1px dashed var(--line-2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.cd-note { margin: 0; font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 提交区：主按钮沿用 A 版 `.pay-actions`，取消保留原功能 ───────────────── */
.pay-actions {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--s5);
  padding-top: 0;
}
.go {
  width: 100%;
  height: 46px;
  border: 0;
  border-radius: var(--r1);
  background: var(--brand);
  color: #fff;
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  cursor: pointer;
}
.go:hover { background: var(--brand-hover); }
.go[disabled] { opacity: .6; cursor: default; }
.go-ghost {
  width: 100%;
  height: 36px;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  cursor: pointer;
}
.go-ghost:hover { border-color: var(--brand-ink); color: var(--brand-ink); }
.go-ghost[disabled] { opacity: .6; cursor: default; }
.desk-note {
  padding: 0 var(--s5) var(--s5);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
}

/* ── 窄屏：左右两栏改上下堆叠，支付方式改一列（与 A 版 :691-699 同口径）── */
@media (max-width: 860px) {
  .checkout-layout { grid-template-columns: minmax(0, 1fr); gap: var(--s5); }
  .pay-methods { grid-template-columns: minmax(0, 1fr); }
  .pay-method { border-right: 0; border-bottom: 1px solid var(--line); }
  .pay-method:last-child { border-bottom: 0; }
}
@media (max-width: 560px) {
  .cscrim { padding: var(--s3); }
  .cdlg-h { padding: var(--s4) var(--s5); }
  .checkout-layout { padding: var(--s4) var(--s5) var(--s5); }
  .cdlg-h h1 { font-size: var(--fs-md); }
}
</style>
