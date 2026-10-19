<template>
  <!--
    C3 块 4〈订单块〉—— **购物车块容器**（两壳共用）。

    块内两个 tab（用户 2026-10-10 裁决）：
      · 「购物车」  = 我放进车里、**还没成交**的东西（`cart` 表）      → `views/ShoppingCart.vue`
      · 「我的订单」= 我买的单，未成交 + 已成交（`order` 表）          → `views/OrderMine.vue`

    ⚠️ 与块 4 之前的同名文件是**两份不同的东西**：改前它是「订单中心 ⇄ 购物车」的两态包装
       （靠 `meta.orderTab` 分面、把 `OrderCenter` 也包进来）。改后「订单中心」（我卖的）
       由路由**直接**指向 `OrderCenter.vue`，本容器只管购物车块 —— 分面判据也从
       `meta.orderTab` 换成了 **`route.query.tab`**（用户裁决：tab 走 query，一个路由一个入口）。
  -->
  <div class="pane-page">
    <!-- tab 栏：仍是**稿外活控件**（两份稿都没画购物车块），位置与块 4 之前的 `.switch-wrapper`
         逐字相同（`.pane-page` 的右上角）。
         ⚠️ 一条**行为变化**（有意）：块 4 之前它挂在滚动盒 `ShoppingCart.vue` **里面**，会随内容
            滚走；现在它挂在**不滚动的容器**上 ⇒ 常驻。顶上那个 z-index 的旧实测（用户 2026-10-08
            第 2 条：「取 10 = 与顶栏同级、滚动时盖到顶栏上」）因此**不再可能发生**，但值不动。 -->
    <div class="tabbar" role="tablist">
      <button class="tab" :class="{ on: tab === 'cart' }" role="tab"
              :aria-selected="tab === 'cart'" @click="go('cart')">购物车</button>
      <button class="tab" :class="{ on: tab === 'orders' }" role="tab"
              :aria-selected="tab === 'orders'" @click="go('orders')">我的订单</button>
    </div>

    <ShoppingCart v-if="tab === 'cart'" />
    <OrderMine v-else />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ShoppingCart from '../ShoppingCart.vue'
import OrderMine from '../OrderMine.vue'

const route = useRoute()
const router = useRouter()

/**
 * 当前 tab。**只认 `?tab=orders`**，其余（缺省 / `?tab=cart` / 手敲错值）一律落 `cart`
 * —— 默认面是购物车（与顶栏「购物车」这个入口名一致）。
 * ⚠️ 不用 `route.name` / `route.meta`：本容器挂在**两条不同记录**下
 *    （`/customer/cart` 与 `/merchant/order/cart`），query 是两壳唯一共通的判据。
 */
const tab = computed(() => (route.query.tab === 'orders' ? 'orders' : 'cart'))

/**
 * 换 tab：购物车仍是**只改 query**（不重挂壳、不丢滚动位置）；
 * 「我的订单」自 2026-10-19 起改为**独立全宽页**（`/customer/orders` / `/merchant/orders`）——
 * 用户令「订单页布局统一、无侧栏」，旧 `?tab=orders` 深链仍可打开（下方 `v-else` 保留）。
 */
const go = (next) => {
  if (next === 'orders') {
    router.push(route.path.startsWith('/merchant') ? '/merchant/orders' : '/customer/orders')
    return
  }
  if (next === tab.value) return
  router.push({ path: route.path, query: { tab: next } })
}
</script>

<style scoped>
/* ── 容器根：壳的 `.cart-container` / `.order-container` 都是 `height:100%;overflow:hidden`
      ⇒ 滚动由两个子页各自提供。
      ⚠️ `position:relative` **必需**：上面的 `.tabbar` 是它的 absolute 子元素。
          这也是两壳都能用同一个容器的原因 —— 客户壳的 `.cart-container` **没有**
          `position:relative`（`AppShell.vue` 样式块那条按语的当事人是 `.order-container`），
          若 tab 栏挂到那一层就会跑到视口上去。 */
.pane-page {
  height: 100%;
  position: relative;
}

/* ── tab 栏（稿外活控件）—— 样式语言沿用块 4 之前那枚两态开关：`--surface-2` 轨道 +
      滑动白块 + 选中态 `--brand-ink`。只把 📋/🛒 两个 emoji 换成了**文字**：
      内容从「两个页面」变成「一个块里的两个 tab」，emoji 说不清哪边是哪边。 */
.tabbar {
  position: absolute;
  top: 20px;
  right: 30px;
  z-index: 5;
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 36px;
}
.tab {
  height: 28px;
  padding: 0 var(--s4);
  border: 0;
  border-radius: 30px;
  background: none;
  color: var(--ink-3);
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.3s ease, color 0.3s ease;
}
.tab:hover { color: var(--ink-2); }
.tab.on { background: var(--surface); color: var(--brand-ink); }

@media (max-width: 1120px) {
  .tabbar { right: var(--s5); }
}
</style>
