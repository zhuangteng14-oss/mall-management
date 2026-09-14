<template>
  <div class="order-center-page">
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
      <div class="title-bar">
        <h2>📋 订单中心</h2>
        <select v-model="orderStatus" class="status-select" @change="loadOrders">
          <option value="active">首页</option>
          <option value="completed">历史订单</option>
        </select>
      </div>
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
            <div class="order-price"></div>
            <div class="order-buttons">
              <div v-if="orderStatus === 'completed'" class="completed-text">该订单已完成</div>
              <button 
                v-else
                class="deliver-btn" 
                :disabled="order.DeliveryIf === 1 || order.ReceiveIf === 1"
                :class="{ disabled: order.DeliveryIf === 1 || order.ReceiveIf === 1 }"
                @click="handleDeliver(order)"
              >
                发货
              </button>
            </div>
          </div>
          <div class="order-bottom">
            <template v-if="orderStatus === 'active'">
              <div class="progress-steps">
                <span>用户已付款</span>
                <span>已发货</span>
                <span>用户已收货</span>
              </div>
              <div class="progress-bar-bg">
                <div class="progress-fill" :style="{ width: getProgressWidth(order) }"></div>
              </div>
              <div class="date-row">
                <span class="create-date">创建订单日期：{{ formatDateTime(order.CreateTime) }}</span>
                <span class="update-date">订单更改日期：{{ formatDateTime(order.SettlementDate) }}</span>
              </div>
            </template>
            <template v-else>
              <div class="completed-date-row">
                <span class="update-date-right">订单更改日期：{{ formatDateTime(order.SettlementDate) }}</span>
              </div>
            </template>
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
  orderTab: { type: String, default: 'order' },
  showSwitch: { type: Boolean, default: true }
})
const emit = defineEmits(['toggle'])

const handleToggle = () => emit('toggle')

const orderList = ref([])
const loading = ref(false)
const orderStatus = ref('active')

const getMerchantId = () => {
  let merchantId = sessionStorage.getItem('merchantid')
  if (!merchantId || merchantId === 'null' || merchantId === 'undefined') {
    merchantId = sessionStorage.getItem('loginUsername')
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
  const merchantId = getMerchantId()
  if (!merchantId) {
    console.warn('未登录商家，无法获取订单')
    return
  }
  loading.value = true
  try {
    const params = new URLSearchParams()
    params.append('merchantId', merchantId)
    params.append('status', orderStatus.value)
    let url = 'http://localhost:8088/mysqlGoods/orderDeliveryIfSelect'
    if (orderStatus.value === 'completed') {
      url = 'http://localhost:8088/mysqlGoods/orderDeliverySelect'
    }
    const res = await fetch(url, {
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

const getProgressWidth = (order) => {
  const { PayIf, DeliveryIf, ReceiveIf } = order
  if (PayIf === 1 && DeliveryIf === 1 && ReceiveIf === 1) return '100%'
  if (PayIf === 1 && DeliveryIf === 1 && ReceiveIf === 0) return '50%'
  if (PayIf === 1 && DeliveryIf === 0 && ReceiveIf === 0) return '25%'
  return '0%'
}

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
    const res = await fetch('http://localhost:8088/mysqlGoods/orderDeliveryIfUp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
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
.order-center-page {
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
.title-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.title-bar h2 {
  margin: 0;
}
.status-select {
  padding: 6px 12px;
  font-size: 14px;
  border: 1px solid #ddd;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
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
  background: #ff9800;
  color: white;
  cursor: pointer;
  font-size: 14px;
}
.order-buttons button.disabled {
  background: #cccccc;
  cursor: not-allowed;
  opacity: 0.6;
}
.order-buttons button:hover:not(.disabled) {
  opacity: 0.9;
}
.completed-text {
  font-size: 14px;
  color: #52c41a;
  font-weight: 500;
  background: #f6ffed;
  padding: 6px 12px;
  border-radius: 6px;
  text-align: center;
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
  background: #ff9800;
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
.completed-date-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
  font-size: 12px;
  color: #888;
}
.update-date-right {
  font-size: 12px;
}
</style>