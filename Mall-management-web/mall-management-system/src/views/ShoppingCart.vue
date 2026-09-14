<template>
  <div class="shopping-cart-page">
    <!-- 开关 -->
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

    <div class="page-content">
      <h2>🛒 购物车</h2>
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="orderList.length === 0" class="empty-orders">暂无订单</div>
      <div v-else class="orders-container">
        <div v-for="order in orderList" :key="order.OrderId" class="order-card">
          <div class="order-top">
            <div class="order-img">
              <img :src="order.Images" alt="商品图片" />
            </div>
            <div class="order-info">
              <div class="product-info">{{ order.ProductInformation }}</div>
              <div class="order-id">订单号：{{ order.OrderId }}</div>
            </div>
            <div class="order-price">¥{{ formatPrice(order.Price) }}</div>
            <div class="order-buttons">
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
          </div>
          <div class="order-bottom">
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
      </div>
    </div>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */
import { ref, onMounted } from 'vue'

defineProps({
  orderTab: { type: String, default: 'cart' },
  showSwitch: { type: Boolean, default: true }
})
const emit = defineEmits(['toggle'])

const handleToggle = () => emit('toggle')

const orderList = ref([])
const loading = ref(false)

const getBuyerId = () => {
  let buyerId = sessionStorage.getItem('customerid')
  if (!buyerId || buyerId === 'null' || buyerId === 'undefined') {
    buyerId = sessionStorage.getItem('merchantid')
  }
  if (!buyerId || buyerId === 'null' || buyerId === 'undefined') {
    buyerId = sessionStorage.getItem('loginUsername')
  }
  return buyerId || null
}

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
    const res = await fetch('http://localhost:8088/mysqlGoods/orderSelect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
    const data = await res.json()
    if (data.success) {
      orderList.value = data.list
    } else {
      console.error('获取订单失败', data.msg)
    }
  } catch (err) {
    console.error('请求出错', err)
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
    const res = await fetch('http://localhost:8088/mysqlGoods/orderPayIfUp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
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
    const res = await fetch('http://localhost:8088/mysqlGoods/orderReceiveIfUp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
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
.shopping-cart-page {
  height: 100%;
  position: relative;
  background: #fff;
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
  color: #007bff;
}
.switch-container.switch-right .slider-text.right {
  color: #007bff;
}
.page-content {
  padding: 80px 24px 24px 24px;
}
h2 {
  margin-top: 0;
  margin-bottom: 20px;
}
.loading, .empty-orders {
  text-align: center;
  padding: 40px;
  color: #999;
}
.orders-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
  align-items: center;
}
.order-card {
  width: 95%;
  height: auto;
  background: #fafafa;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.order-top {
  height: 70%;
  display: flex;
  align-items: center;
  padding: 12px;
  box-sizing: border-box;
}
.order-img {
  width: 30%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.order-img img {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid #ddd;
}
.order-info {
  width: 30%;
  padding-left: 10px;
}
.product-info {
  font-size: 20px;
  font-weight: 500;
  margin-bottom: 8px;
  word-break: break-word;
}
.order-id {
  font-size: 14px;
  color: #666;
}
.order-price {
  width: 20%;
  text-align: center;
  color: red;
  font-size: 15px;
  font-weight: bold;
}
.order-buttons {
  width: 20%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}
.order-buttons button {
  width: 80%;
  padding: 6px 0;
  border: none;
  border-radius: 6px;
  background: #007bff;
  color: white;
  cursor: pointer;
  font-size: 14px;
}
.order-buttons button.disabled {
  background: #cccccc;
  cursor: not-allowed;
  opacity: 0.6;
}
.order-buttons button.receive-btn {
  background: #28a745;
}
.order-buttons button.receive-btn.disabled {
  background: #cccccc;
}
.order-buttons button:hover:not(.disabled) {
  opacity: 0.9;
}
.order-bottom {
  padding: 8px 16px;
  box-sizing: border-box;
}
.progress-steps {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 12px;
  color: #666;
}
.progress-bar-bg {
  background: #e0e0e0;
  border-radius: 10px;
  height: 8px;
  width: 100%;
  overflow: hidden;
}
.progress-fill {
  background: #007bff;
  height: 100%;
  width: 0%;
  transition: width 0.3s;
}
.date-row {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: #888;
}
</style>