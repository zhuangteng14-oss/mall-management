<template>
  <div class="goods-detail-page">
    <div class="top-navbar">
      <div class="nav-left" @click="goBack">&lt;</div>
      <div class="search-wrapper">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="请输入搜索内容"
          @keyup.enter="handleSearch"
        />
        <button class="search-btn" @click="handleSearch">搜索</button>
      </div>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div><!-- 只改文案：点击逻辑（goUserCenter）一字不动，见文件末按语 -->
    </div>

    <div class="white-box">
      <div class="top-section">
        <div class="left-carousel">
          <div class="big-swiper">
            <img
              v-for="(item, idx) in allItems"
              :key="idx"
              :src="item.goodImg"
              class="big-img"
              :class="{ active: idx === currentIndex }"
            />
          </div>
          <div class="thumb-scroll-wrapper">
            <button class="arrow-btn" @click="scroll(-1)" :disabled="start === 0">《</button>
            <div class="thumb-list">
              <div
                v-for="(item, idx) in showImages"
                :key="idx"
                class="thumb-item"
                :class="{ active: realIdx(idx) === currentIndex }"
                @click="currentIndex = realIdx(idx)"
              >
                <img :src="item.goodImg" />
              </div>
            </div>
            <button class="arrow-btn" @click="scroll(1)" :disabled="start + 3 >= allItems.length">》</button>
          </div>
        </div>

        <div class="right-info">
          <div class="good-name">{{ goodName }}</div>
          <div class="info-item"><label>商家：</label><div class="info-box">{{ currentItem.UserName }}小店</div></div>
          <div class="info-item"><label>商品类型：</label><div class="info-box">{{ goodType }}</div></div>
          <div class="info-item"><label>型号：</label><div class="code-buttons">
            <button
              v-for="(item, idx) in allItems"
              :key="idx"
              class="code-btn"
              :class="{ active: idx === currentIndex }"
              @click="currentIndex = idx"
            >
              {{ item.productCode }}
            </button>
          </div></div>
          <div class="info-item price-row">
            <label>单价：</label>
            <div class="info-box price-box">¥{{ currentItem.price }}</div>
            <button class="consult-btn" @click="consultProduct">咨询商品？</button>
          </div>
        </div>
      </div>
      <div class="buy-btn-wrap"><button class="buy-btn" @click="openPopup">购入</button></div>
    </div>

    <!-- 购入弹窗 -->
    <div class="popup-mask" v-if="showPopup" @click="showPopup=false">
      <div class="popup-content" @click.stop>
        <div class="p-top"></div>
        <div class="p-middle">
          <div class="p-img"><img :src="popupCurrentItem.goodImg" /></div>
          <div class="p-info">
            <div class="p-price">单价：¥{{ popupCurrentItem.price }}</div>
            <div class="p-codes">
              <button
                v-for="(item, idx) in popupAllItems"
                :key="idx"
                class="p-code-btn"
                :class="{ active: idx === popupCurrentIndex }"
                @click="popupCurrentIndex = idx"
              >
                {{ item.productCode }}
              </button>
            </div>
            <div class="p-num">
              <button @click="setPopQty(-1)">-</button>
              <input
                type="number"
                :value="popupQty[popupCurrentItem.productCode] || 0"
                @input="setPopQtyInput($event.target.value)"
                min="0"
                max="100"
              />
              <button @click="setPopQty(1)">+</button>
            </div>
          </div>
        </div>
        <div class="p-bottom">
          <div class="p-list">
            <div class="p-item" v-for="(it, idx) in popupBuyList" :key="idx">
              <span>{{ goodName }}：{{ it.productCode }}：{{ it.quantity }}</span>
              <button class="p-del" @click="delPopItem(it.productCode)">-</button>
            </div>
          </div>
          <div class="p-total">合计：¥{{ popupTotal }}</div>
          <button class="p-confirm" @click="confirmPurchase">确认购入</button>
        </div>
      </div>
    </div>

    <!-- 付款确认弹窗 -->
    <div class="pay-confirm-mask" v-if="showPayConfirm" @click="showPayConfirm=false">
      <div class="pay-confirm-dialog" @click.stop>
        <div class="dialog-title">是否现在付款？</div>
        <div class="dialog-buttons">
          <button class="dialog-btn yes-btn" @click="handlePay(true)">是</button>
          <button class="dialog-btn no-btn" @click="handlePay(false)">否</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId, isLoggedIn } from '../api/auth'

const route = useRoute()
const router = useRouter()

// 从路由参数获取商品所属商家ID (卖家)
const { MerchantId, GoodName, GoodType, Id } = route.query
const sellerId = MerchantId
const goodName = GoodName
const goodType = GoodType
const productId = Id

// 获取当前登录用户的ID（买家）
let buyerId = getCustomerId()
if (!buyerId) {
  buyerId = getMerchantId()
}
const customerId = buyerId || ''

// 数据定义
const allItems = ref([])
const currentIndex = ref(0)
const currentItem = computed(() => allItems.value[currentIndex.value] || {})
const goodsWithUser = ref([])

const start = ref(0)
const showImages = computed(() => allItems.value.slice(start.value, start.value + 3))
const realIdx = (i) => start.value + i

const scroll = (d) => {
  const m = allItems.value.length - 3
  if (m <= 0) return
  start.value = Math.max(0, Math.min(m, start.value + d))
}

// 加载商家信息
const loadGoodsWithUser = async () => {
  try {
    const res = await http.post(endpoints.goods.goodsSelectHome)
    const data = await res.json()
    if (data.success) goodsWithUser.value = data.list
  } catch (e) {
    handleApiError(e, '获取商家名失败：')
  }
}

// 加载所有型号的商品详情
const loadAllItems = async () => {
  try {
    const r1 = await http.post(endpoints.goods.goodsProductCodeSelect, `MerchantId=${sellerId}&GoodName=${goodName}`)
    const d1 = await r1.json()
    const codes = d1.list || []
    const items = []
    for (let code of codes) {
      const r2 = await http.post(endpoints.goods.productCodePrice, `MerchantId=${sellerId}&GoodName=${goodName}&ProductCode=${code}`)
      const d2 = await r2.json()
      if (d2.success) {
        items.push({
          productCode: code,
          price: d2.price,
          goodImg: d2.goodImg,
          UserName: ''
        })
      }
    }
    const matchGood = goodsWithUser.value.find(
      (item) => item.MerchantId === sellerId && item.GoodName === goodName
    )
    if (matchGood) items.forEach((it) => { it.UserName = matchGood.UserName })
    allItems.value = items
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

// 弹窗相关状态
const showPopup = ref(false)
const popupAllItems = ref([])
const popupCurrentIndex = ref(0)
const popupCurrentItem = computed(() => popupAllItems.value[popupCurrentIndex.value] || { productCode: '' })
const popupQty = ref({})

const openPopup = async () => {
  await loadPopupItems()
  const q = {}
  popupAllItems.value.forEach((i) => (q[i.productCode] = 0))
  popupQty.value = q
  popupCurrentIndex.value = 0
  showPopup.value = true
}

const loadPopupItems = async () => {
  try {
    const r1 = await http.post(endpoints.goods.goodsProductCodeSelect, `MerchantId=${sellerId}&GoodName=${goodName}`)
    const d1 = await r1.json()
    const codes = d1.list || []
    const items = []
    for (let code of codes) {
      const r2 = await http.post(endpoints.goods.productCodePrice, `MerchantId=${sellerId}&GoodName=${goodName}&ProductCode=${code}`)
      const d2 = await r2.json()
      if (d2.success) {
        items.push({
          productCode: code,
          price: d2.price,
          goodImg: d2.goodImg
        })
      }
    }
    popupAllItems.value = items
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

const setPopQty = (step) => {
  const code = popupCurrentItem.value.productCode
  if (!code) return
  const current = popupQty.value[code] || 0
  let num = current + step
  num = Math.max(0, Math.min(100, num))
  popupQty.value = { ...popupQty.value, [code]: num }
}

const setPopQtyInput = (val) => {
  const code = popupCurrentItem.value.productCode
  if (!code) return
  let num = parseInt(val) || 0
  num = Math.max(0, Math.min(100, num))
  popupQty.value = { ...popupQty.value, [code]: num }
}

const delPopItem = (code) => {
  popupQty.value = { ...popupQty.value, [code]: 0 }
}

const popupBuyList = computed(() => {
  return popupAllItems.value
    .filter((i) => (popupQty.value[i.productCode] || 0) > 0)
    .map((i) => ({
      productCode: i.productCode,
      quantity: popupQty.value[i.productCode],
      price: i.price,
      goodImg: i.goodImg
    }))
})

const popupTotal = computed(() => {
  return popupBuyList.value.reduce((sum, item) => sum + item.price * item.quantity, 0)
})

// 订单提交
const showPayConfirm = ref(false)
let pendingOrderData = null

const generateOrderId = () => {
  const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
  let randStr = ''
  for (let i = 0; i < 4; i++) {
    randStr += letters.charAt(Math.floor(Math.random() * letters.length))
  }
  const numbers = Math.floor(Math.random() * 100000000).toString().padStart(8, '0')
  return randStr + numbers
}

const buildProductInformation = () => {
  return popupBuyList.value.map((item) => `${goodName}:${item.productCode}`).join('；')
}

const getFirstImage = () => {
  if (popupBuyList.value.length === 0) return ''
  const firstCode = popupBuyList.value[0].productCode
  const target = popupAllItems.value.find((i) => i.productCode === firstCode)
  return target ? target.goodImg : ''
}

const confirmPurchase = () => {
  if (!customerId) {
    alert('请先登录再购买商品'); if (!isLogin.value) router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (popupBuyList.value.length === 0) {
    alert('请至少选择一件商品')
    return
  }
  pendingOrderData = {
    CustomerId: customerId,
    MerchantId: sellerId,
    OrderId: generateOrderId(),
    Price: popupTotal.value,
    ProductInformation: buildProductInformation(),
    Images: getFirstImage(),
    DeliveryIf: 0,
    ReceiveIf: 0
  }
  showPayConfirm.value = true
}

const handlePay = async (payIf) => {
  showPayConfirm.value = false
  if (!pendingOrderData) return

  const orderData = {
    ...pendingOrderData,
    PayIf: payIf ? 1 : 0
  }

  try {
    const params = new URLSearchParams()
    params.append('CustomerId', orderData.CustomerId)
    params.append('MerchantId', orderData.MerchantId)
    params.append('OrderId', orderData.OrderId)
    params.append('Price', orderData.Price)
    params.append('ProductInformation', orderData.ProductInformation)
    params.append('Images', orderData.Images)
    params.append('PayIf', orderData.PayIf)
    params.append('DeliveryIf', orderData.DeliveryIf)
    params.append('ReceiveIf', orderData.ReceiveIf)

    const res = await http.post(endpoints.order.orderAdd, params.toString())
    const result = await res.json()
    if (result.success) {
      alert('订单提交成功！')
      showPopup.value = false
      popupQty.value = {}
      const isMerchant = !!getMerchantId()
      if (isMerchant) {
        router.push('/merchant?page=order')
      } else {
        router.push('/customer?page=cart')
      }
    } else {
      alert('订单提交失败：' + (result.msg || '未知错误'))
    }
  } catch (error) {
    console.error('提交订单出错：', error)
    alert('网络错误，请稍后重试')
  } finally {
    pendingOrderData = null
  }
}

// 搜索功能
const searchKeyword = ref('')
const handleSearch = () => {
  const keyword = searchKeyword.value.trim()
  sessionStorage.setItem('PENDING_SEARCH_KEYWORD', keyword)
  const isMerchant = !!getMerchantId()
  router.push(isMerchant ? '/merchant' : '/customer')
}

const goBack = () => {
  const isMerchant = !!getMerchantId()
  if (route.query.from === 'search') {
    router.push(isMerchant ? '/merchant?back=search' : '/customer?back=search')
  } else {
    router.push(isMerchant ? '/merchant' : '/customer')
  }
}

const goUserCenter = () => {
  const isMerchant = getMerchantId() && getMerchantId() !== 'null'
  const isCustomer = getCustomerId() && getCustomerId() !== 'null'
  if (isMerchant) {
    router.push('/merchant?page=user')
  } else if (isCustomer) {
    router.push('/customer?page=user')
  } else {
    router.push('/login')
  }
}

// ========== 咨询商品功能 ==========
const consultProduct = async () => {
  if (!customerId) {
    alert('请先登录'); if (!isLogin.value) router.push({ path: '/login', query: { redirect: route.fullPath } })
    return;
  }
  if (customerId === sellerId) {
    alert('不能咨询自己的商品');
    return;
  }
  const selectedProduct = currentItem.value;
  if (!selectedProduct.productCode) {
    alert('请选择商品型号');
    return;
  }
  const productInfo = {
    id: productId,
    merchantId: sellerId,
    goodName: goodName,
    price: selectedProduct.price,
    goodImg: selectedProduct.goodImg,
    goodType: goodType,
    productCode: selectedProduct.productCode
  };
  const messageContent = `[PROD]${JSON.stringify(productInfo)}`;
  const fromType = getCustomerId() ? 'customer' : 'merchant';
  const params = new URLSearchParams();
  params.append('fromId', customerId);
  params.append('fromType', fromType);
  params.append('toId', sellerId);
  params.append('toType', 'merchant');
  params.append('content', messageContent);
  try {
    const res = await http.post(endpoints.message.send, params.toString());
    const data = await res.json();
    if (data.success) {
      const isMerchant = !!getMerchantId();
      const basePath = isMerchant ? '/merchant' : '/customer';
      router.push({
        path: basePath,
        query: {
          page: 'chat',
          otherId: sellerId,
          otherType: 'merchant',
          _t: Date.now()
        }
      });
    } else {
      alert('咨询发送失败：' + data.msg);
    }
  } catch (err) {
    console.error(err);
    alert('网络错误，请稍后重试');
  }
};

onMounted(async () => {
  await loadGoodsWithUser();
  loadAllItems();
});
// ── 未登录 UX 闭环（2026-10-06 收口笔，登记见 progress.md §5-AG (15)）：本页**不是**「进壳」────────
// R5（商品详情页进壳）仍不做；本笔只改页内三处未登录 UX —— 顶栏标签（:15，未登录显示「登录 / 注册」，
// 与 AppShell 的 ANON_RIGHT 同形态）、购买（:312）与咨询（:409）两处拦截的出口。
// ⚠️ 两处拦截的判据仍是 `!customerId`，**不动**：改成 `!isLogin` 会让**已登录商家**带着空 CustomerId
//    走到 `orderAdd`（拆真检查）；`isLogin` 只决定**要不要跳登录** ⇒ 已登录商家原地不动（= 既有行为）。
// ⚠️ redirect 取 `route.fullPath`（含本页 7 个 query），对象形式与 `main.js:49` 同款；登录后由
//    `LoginPage.landingAfterLogin` 送回本页（T-guard-6/-10）。放文件末尾是**刻意的**：`WebAuthConfig.java:70` 引了 `:462-465`，新增行落在最大被引行之后，上面 24 个被引目标才一个都不漂。
const isLogin = computed(() => isLoggedIn())
</script>

<style scoped>
.goods-detail-page {
  width: 100vw;
  height: 100vh;
  background: #f5f5f5;
  box-sizing: border-box;
}
.top-navbar {
  position: relative;
  width: 100%;
  height: 50px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 15px;
  box-shadow: 0 0 5px rgba(0,0,0,0.1);
  z-index: 999;
}
.nav-left { font-size: 20px; cursor: pointer; }
.nav-right { cursor: pointer; white-space: nowrap; }
.search-wrapper {
  width: 55%;
  display: flex;
}
.search-input {
  flex: 1;
  height: 42px;
  padding: 0 15px;
  border: 1px solid #ddd;
  border-radius: 6px 0 0 6px;
  outline: none;
  font-size: 14px;
}
.search-btn {
  width: 100px;
  height: 42px;
  border: none;
  background: #00bcd4;
  color: #fff;
  border-radius: 0 6px 6px 0;
  cursor: pointer;
  font-size: 14px;
}
.white-box {
  width: 80%;
  height: calc(100% - 50px);
  background: #fff;
  margin: 0 auto;
  position: relative;
  display: flex;
  flex-direction: column;
}
.top-section {
  height: 50%;
  display: flex;
  padding: 20px;
  box-sizing: border-box;
}
.left-carousel {
  width: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.big-swiper {
  width: 100%;
  height: 80%;
  position: relative;
}
.big-img {
  position: absolute;
  width: 100%;
  height: 100%;
  object-fit: contain;
  opacity: 0;
  transition: 0.4s;
}
.big-img.active { opacity: 1; }
.thumb-scroll-wrapper {
  display: flex;
  align-items: center;
  gap: 6px;
}
.arrow-btn {
  font-size: 22px;
  padding: 0 8px;
  border: 1px solid #ccc;
  background: #fff;
  cursor: pointer;
  border-radius: 4px;
}
.arrow-btn:disabled { opacity: 0.3; }
.thumb-list { display: flex; gap: 8px; }
.thumb-item {
  width: 60px;
  height: 60px;
  border: 2px solid transparent;
  cursor: pointer;
}
.thumb-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumb-item.active { border-color: #028fa8; }
.right-info {
  width: 50%;
  padding-left: 30px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  justify-content: center;
}
.good-name { font-size: 30px; font-weight: bold; }
.info-item { display: flex; align-items: center; gap: 10px; }
.info-box {
  border: 1px solid #ccc;
  padding: 8px 14px;
  border-radius: 6px;
  background: #f9f9f9;
}
.price-row {
  flex-wrap: wrap;
}
.consult-btn {
  background-color: #ff9800;
  border: none;
  border-radius: 6px;
  padding: 6px 12px;
  color: white;
  cursor: pointer;
  font-size: 14px;
  white-space: nowrap;
}
.consult-btn:hover {
  background-color: #fb8c00;
}
.code-buttons { display: flex; gap: 10px; flex-wrap: wrap; }
.code-btn {
  padding: 8px 14px;
  border: 1px solid #ccc;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
}
.code-btn.active { background: #028fa8; color: #fff; }
.price-box { color: red; font-size: 18px; }
.buy-btn-wrap {
  position: absolute;
  right: 30px;
  bottom: 30px;
}
.buy-btn {
  padding: 10px 20px;
  background: #028fa8;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.popup-mask {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: flex-end;
  z-index: 1000;
}
.popup-content {
  width: 100%;
  height: 80%;
  background: #fff;
  border-radius: 12px 12px 0 0;
  display: flex;
  flex-direction: column;
}
.p-top { height: 30%; }
.p-middle {
  height: 40%;
  display: flex;
  padding: 15px;
  box-sizing: border-box;
}
.p-img {
  width: 30%;
  height: 100%;
}
.p-img img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.p-info {
  width: 70%;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 15px;
}
.p-price {
  font-size: 22px;
  color: red;
  font-weight: bold;
}
.p-codes { display: flex; gap: 10px; flex-wrap: wrap; }
.p-code-btn {
  padding: 8px 14px;
  border: 1px solid #ccc;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
}
.p-code-btn.active { background: #028fa8; color: #fff; }
.p-num {
  display: flex;
  align-items: center;
  gap: 10px;
}
.p-num button {
  width: 30px;
  height: 30px;
  border: 1px solid #ccc;
  background: #fff;
  cursor: pointer;
}
.p-num input {
  width: 60px;
  height: 30px;
  text-align: center;
  border: 1px solid #ccc;
}
.p-bottom {
  height: 30%;
  padding: 15px;
  box-sizing: border-box;
  border-top: 1px solid #eee;
  display: flex;
  flex-direction: column;
}
.p-list { flex: 1; overflow-y: auto; }
.p-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f5f5f5;
}
.p-del {
  width: 24px;
  height: 24px;
  border: 1px solid #ccc;
  background: #fff;
  border-radius: 50%;
  cursor: pointer;
}
.p-total {
  text-align: right;
  padding: 10px 0;
  font-weight: bold;
}
.p-confirm {
  align-self: flex-end;
  padding: 10px 20px;
  background: #028fa8;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.pay-confirm-mask {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}
.pay-confirm-dialog {
  width: 300px;
  background: white;
  border-radius: 12px;
  padding: 24px 20px 20px;
  text-align: center;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
}
.dialog-title {
  font-size: 18px;
  font-weight: 500;
  margin-bottom: 24px;
  color: #333;
}
.dialog-buttons {
  display: flex;
  gap: 20px;
  justify-content: center;
}
.dialog-btn {
  width: 80px;
  padding: 8px 0;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
  transition: 0.2s;
}
.yes-btn {
  background: #ff4d4f;
  color: white;
}
.yes-btn:hover {
  background: #ff7875;
}
.no-btn {
  background: #52c41a;
  color: white;
}
.no-btn:hover {
  background: #73d13d;
}
</style>