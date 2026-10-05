<template>
  <OrderCenter v-if="route.meta.orderTab === 'order'" orderTab="order" @toggle="toCart" />
  <ShoppingCart v-else orderTab="cart" @toggle="toOrder" />
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import OrderCenter from '../OrderCenter.vue'
import ShoppingCart from '../ShoppingCart.vue'

// ── R2（2026-10-05）：商家「订单中心 ⇄ 购物车」二态的**包装组件**（用户裁决 1 ⇒ (a) 全路由化）──
// 两态各自成路由（`/merchant/order` 与 `/merchant/order/cart`），靠路由记录的 `meta.orderTab` 区分。
//
// ⚠️ 为什么是**包装组件**而不是「两个路由直接指 `OrderCenter` / `ShoppingCart`、由壳传 props」：
//    `<router-view v-slot="{ Component }">` 上的 attrs 会**落到所有子页** —— `orderTab="order"`
//    会变成 HomeTab / ChatRoom 根元素上的野属性。要按 `meta` 条件 `v-bind` 才干净，
//    而包装组件就是这个条件分支**更直白的等价物**。
//
// ⚠️ 这样 **`OrderCenter.vue` / `ShoppingCart.vue` 一字不动** —— 两壳共用的
//    `orderTab` / `showSwitch` / `@toggle` 契约照旧（`showSwitch` 不传 ⇒ 取默认 `true`
//    ⇒ 开关显示，与商家壳 R2 前 `<OrderCenter :orderTab @toggle>` 的行为一致）。
//
// ⚠️ 切面 = **路由跳转** ⇒ 卸载重挂、各拉一次订单 —— 与 R2 前壳里 `v-if` / `v-else` 的
//    行为**逐字等价**（本笔前 `MerchantPage.vue:31-42` 就是 `v-if="orderTab === 'order'"`），
//    不是新引入的代价；换来的是「刷新 / 深链 / 后退**保留所在面**」。
//
// ⚠️ 路径 `order/cart` 是**单条多段子路径**，不嵌第二层 `children` —— 本组件自己渲染两面，
//    不需要 nested `<router-view>`。
const route = useRoute()
const router = useRouter()

// 开关语义与改前一致：在两态之间来回切。
const toCart = () => router.push('/merchant/order/cart')
const toOrder = () => router.push('/merchant/order')
</script>
