<!-- CashierDialog.vue —— 收银台弹窗（C3 块 4 补丁；**两个入口共用**）。

  ⚠️ 为什么抽成组件：收银台原先只内联在 `views/OrderMine.vue`（「我的订单」的【付款】）——
     商品详情页的【立即购买】于是绕开了它、直接建了一张**已付款**的单（用户 2026-10-10 报的 Bug 1）。
     两个入口要**走同一个收银台**，就把「选支付方式 → 确认支付」这段收在这里，两边都渲染它。

  ⚠️ 本仓无组件库，这份 `<style scoped>` 是从 `OrderMine.vue` **整段搬过来**的（含 `.scrim` / `.dlg` /
     `.act` 等与弹窗共用的类）——之所以要搬全：`<style scoped>` 只把作用域属性打在**子组件根节点**上，
     根节点**以内**的元素拿不到父页面的样式 ⇒ 组件必须自带完整一份。

  ⚠️ 这是**模拟支付**：本仓没有支付网关，`orderPay` 只把「选了哪种方式」记进 `order.PaymentMethod`
     并回一个生成的 `TradeNo`。文案必须说清楚，别让用户以为真扣了钱。

  用法：
    <CashierDialog :order="payFor" @close="payFor = null" @paid="onPaid" />
  `order` 为 null ⇒ 不渲染（开/关都靠它）。
-->
<template>
  <div v-if="order" class="cscrim" @click.self="close">
    <div class="cdlg" @click.stop>
      <div class="cdlg-h">
        <span>收银台</span>
        <button class="cx" @click="close" aria-label="关闭">×</button>
      </div>
      <div class="cdlg-b">
        <p class="pay-amt">应付金额 <b>¥{{ formatPrice(order.Price) }}</b></p><p class="pay-sub" v-if="Number(order.MemberDiscount) > 0">会员优惠 −¥{{ formatPrice(order.MemberDiscount) }}<span v-if="Number(order.CouponDiscount) > 0"> · 优惠券 −¥{{ formatPrice(order.CouponDiscount) }}</span></p>
        <p class="pay-oid">订单号 <span class="mono">{{ order.OrderId }}</span></p>
        <div class="pay-methods">
          <label v-for="m in PAY_METHODS" :key="m" class="pm" :class="{ on: method === m }">
            <input type="radio" name="paymethod" :value="m" v-model="method" />
            <span>{{ m }}</span>
          </label>
        </div>
        <p class="pay-note">⚠️ 演示环境的**模拟收银台**：本仓没有支付网关，点「确认支付」只会把所选方式与一个演示流水号记进订单，不会真的扣款。</p>
      </div>
      <div class="cdlg-f">
        <button class="cact" @click="close">取消</button>
        <button class="cact primary" :disabled="paying" @click="confirm">{{ paying ? '支付中…' : '确认支付' }}</button>
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
import { ref, watch } from 'vue'
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

<!-- ⚠️ 类名统一加 `c` 前缀（`cscrim` / `cdlg` / `cact`）**不是**排版偏好：
     若这里也叫 `.scrim`，父页面（OrderMine）同名的 `scoped` 规则会**同时命中本组件根节点**
     （Vue 会把父作用域属性打在子组件根上）⇒ 两份规则叠加、改一处不知另一处在生效。
     全仓弹窗都叫 `.scrim/.dlg` ⇒ 撞名迟早发生，组件内自成一套前缀最省事。 -->
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
  width: min(520px, 100%);
  max-height: 86vh;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.22);
}
.cdlg-h {
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
.cdlg-h .cx {
  border: 0;
  background: transparent;
  font-size: var(--fs-lg);
  line-height: 1;
  color: var(--ink-3);
  cursor: pointer;
}
.cdlg-b { padding: var(--s5); }
.cdlg-f {
  display: flex;
  justify-content: flex-end;
  gap: var(--s3);
  padding: var(--s4) var(--s5);
  border-top: 1px solid var(--line);
}

.pay-amt { margin: 0; font-size: var(--fs-sm); color: var(--ink-2); }
.pay-amt b {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.pay-oid { margin: var(--s2) 0 0; font-size: var(--fs-xs); color: var(--ink-3); }
.mono { font-family: var(--font-mono); color: var(--ink-2); }
.pay-note {
  margin: var(--s4) 0 0;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-3);
}

.pay-methods { display: flex; flex-wrap: wrap; gap: var(--s3); margin-top: var(--s4); }
.pm {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  height: 34px;
  padding: 0 var(--s4);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  font-size: var(--fs-sm);
  color: var(--ink-2);
  cursor: pointer;
}
.pm.on { border-color: var(--brand); color: var(--brand-ink); background: var(--surface-3); }
.pm input { accent-color: var(--brand); }

.cact {
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
.cact:hover { border-color: var(--brand); }
.cact.primary {
  border-color: var(--brand);
  background: var(--brand);
  color: #fff;
}
.cact.primary[disabled] { opacity: 0.6; cursor: default; }

/* 会员/券的减额行（批四第二批 commit 3）。⚠️ 只有调用方把 `MemberDiscount` 传进 `order` 时才画 ——
   「我的订单」付款那一路的 `order` 来自列表接口（`Price` 已是实付），没有这个键 ⇒ 逐字同改前。 */
.pay-sub { margin: 2px 0 0; font-size: var(--fs-xs); color: var(--brand-ink); }
</style>
