<template>
  <div class="historical-orders-page">
    <h2>📦 历史订单</h2>
    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="orderList.length === 0" class="empty-orders">暂无历史订单</div>
    <div v-else class="orders-container">
      <div v-for="order in orderList" :key="order.OrderId" class="order-card">
        <div class="order-img">
          <img :src="order.Images" alt="商品图片" />
          <div class="img-overlay">
            <span>已经结单</span>
          </div>
        </div>
        <div class="order-info">
          <div class="product-info">{{ order.ProductInformation }}</div>
          <div class="order-id">旧订单号：{{ order.OrderId }}</div>
          <div class="merchant-id-row">
            <span class="merchant-id-label">商家ID：</span>
            <span class="merchant-id-value">{{ order.MerchantId }}</span>
            <button class="copy-btn" @click="copyMerchantId(order.MerchantId)">复制</button>
          </div>
          <div class="settlement-date">结单日期：{{ formatDateTime(order.SettlementDate) }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

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

const formatDateTime = (dateStr) => {
  if (!dateStr) return '未知'
  const date = new Date(dateStr)
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
    const res = await fetch('http://localhost:8088/mysqlGoods/historicalOrdersSelect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    })
    const data = await res.json()
    if (data.success) {
      orderList.value = data.list
    } else {
      console.error('获取历史订单失败', data.msg)
    }
  } catch (err) {
    console.error('请求出错', err)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadHistoricalOrders()
})
</script>

<style scoped>
.historical-orders-page {
width: 90vw;
display: flex;
flex-direction: column;
gap: 20px;
background: transparent;
}
h2 {
margin-top: 0;
margin-bottom: 0;
font-size: 24px;
color: #333;
}
.loading, .empty-orders {
text-align: center;
padding: 40px;
color: #999;
background: #fff;
border-radius: 18px;
box-shadow: 0 6px 20px rgba(0,0,0,0.06);
}
.orders-container {
display: flex;
flex-direction: column;
gap: 20px;
}
.order-card {
width: 100%;
background: #fff;
border-radius: 18px;
box-shadow: 0 6px 20px rgba(0,0,0,0.06);
display: flex;
overflow: hidden;
height: 200px;
transition: border 0.2s ease;
border: 2px solid transparent;
box-sizing: border-box;
}
.order-card:hover {
border-color: #028096;
}
.order-img {
width: 45%;
position: relative;
overflow: hidden;
}
.order-img img {
width: 100%;
height: 100%;
object-fit: cover;
display: block;
}
.img-overlay {
position: absolute;
bottom: 0;
left: 0;
width: 100%;
height: 35%;
background: rgba(128, 128, 128, 0.75);
display: flex;
align-items: center;
justify-content: center;
color: white;
font-size: 16px;
font-weight: bold;
letter-spacing: 2px;
}
.order-info {
width: 55%;
display: flex;
flex-direction: column;
justify-content: center;
padding: 16px 20px;
box-sizing: border-box;
}
.product-info {
font-size: 18px;
font-weight: 600;
margin-bottom: 12px;
color: #222;
word-break: break-word;
}
.order-id, .settlement-date {
font-size: 14px;
color: #666;
margin-bottom: 8px;
}
.merchant-id-row {
font-size: 14px;
color: #666;
margin-bottom: 8px;
display: flex;
align-items: center;
gap: 8px;
}
.merchant-id-label {
font-weight: 500;
}
.merchant-id-value {
color: #333;
}
.copy-btn {
background: #e6f7ff;
border: 1px solid #91d5ff;
border-radius: 4px;
padding: 2px 8px;
font-size: 12px;
cursor: pointer;
color: #007bff;
transition: 0.2s;
}
.copy-btn:hover {
background: #bae7ff;
}
.settlement-date {
margin-bottom: 0;
}
</style>