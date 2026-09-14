import { createRouter, createWebHashHistory } from 'vue-router'
import HomePage from '../views/HomePage.vue'
import LoginPage from '../views/LoginPage.vue'
import CustomerPage from '../views/CustomerPage.vue'
import MerchantPage from '../views/MerchantPage.vue'
import ProductPage from '../views/ProductPage.vue'
import ShoppingCart from '../views/ShoppingCart.vue'
// 👇 引入你新的搜索页面
import GoodsSelect from '../views/GoodsSelect.vue'

const routes = [
  { path: '/', component: HomePage },
  { path: '/login', component: LoginPage },
  { path: '/customer', component: CustomerPage },
  { path: '/merchant', component: MerchantPage },
  { path: '/product', component: ProductPage },
  { path: '/shoppingCart', component: ShoppingCart },
  // 👇 新增搜索页面路由
  { path: '/goodsSelect', component: GoodsSelect }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router