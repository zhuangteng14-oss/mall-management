/* eslint-disable */
<template>
  <div class="home-page">
    <div v-if="!isLogin" class="lock-mask" @click="showLoginModal = true"></div>

    <div class="search-section">
      <div class="search-wrapper">
        <input 
          v-model="searchKeyword" 
          type="text" 
          class="search-input" 
          placeholder="请输入搜索内容" 
        />
        <button class="search-btn" @click="goSearch">搜索</button>
      </div>
    </div>

    <div class="banner-section">
      <div class="good-type-list">
        <div v-if="goodTypeList.length === 0" class="empty-tip">暂无分类</div>
        <div 
          v-else 
          class="type-item" 
          v-for="item in goodTypeList" 
          :key="item.Id"
          @click="searchByType(item.GoodType)"
        >
          {{ item.GoodType }}
        </div>
      </div>
      <div class="space"></div>

      <div class="swiper-box">
        <div v-if="homeImgList.length === 0" class="empty-tip">暂无商品</div>
        <div v-else class="swiper-container">
          <img
            v-for="(img, index) in homeImgList"
            :key="index"
            :src="img.GoodImg"
            class="swiper-img"
            :class="{ active: index === currentIndex }"
          />
        </div>
        <div v-if="homeImgList.length > 0" class="dots">
          <span
            v-for="(item, index) in homeImgList"
            :key="index"
            class="dot"
            :class="{ active: index === currentIndex }"
            @click="goToSlide(index)"
          ></span>
        </div>
      </div>
    </div>

    <div class="goods-list-section">
      <div v-if="goodsList.length === 0" class="empty-tip">暂无商品</div>
      <div class="goods-grid">
        <div v-for="goods in goodsList" :key="goods.Id" class="goods-card" @click="toGoodsDetail(goods)" >
          <div class="goods-img-box">
            <img :src="goods.GoodImg" alt="商品图" />
          </div>
          <div class="goods-info">
            <div class="goods-name">
              {{ goods.GoodName }}
            </div>
            <div class="goods-price">
              <span class="price-symbol">¥</span>
              <span class="price-int">{{ getPriceInt(goods.Price) }}</span>
              <span class="price-dec">.{{ getPriceDec(goods.Price) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="footer-section">
      <div class="footer-content">
        <span>轻松购物</span>
        <span>极速配送</span>
        <span>精致服务</span>
        <span>畅选无忧</span>
      </div>
    </div>

    <div v-if="showLoginModal" class="login-modal" @click.self="showLoginModal = false">
      <div class="modal-content">
        <div class="modal-text">请先登录以使用功能</div>
        <div class="modal-buttons">
          <button class="login-btn" @click="router.push('/login')">去登录</button>
          <button class="close-btn" @click="showLoginModal = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
// eslint-disable-next-line no-undef
const emit = defineEmits(['go-search'])

const showLoginModal = ref(false)
const goodTypeList = ref([])
const homeImgList = ref([])
const goodsList = ref([])
const currentIndex = ref(0)
const searchKeyword = ref('')

let timer = null

const isLogin = computed(() => {
  return !!sessionStorage.getItem('loginUsername') || !!sessionStorage.getItem('merchantid')
})

const getHomeImgAndGoods = async () => {
  try {
    const res = await fetch('http://localhost:8088/mysqlGoods/goodsSelectHome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    const data = await res.json()
    if (data.success) {
      goodsList.value = data.list
      homeImgList.value = data.list
    }
  } catch (e) {
    console.error('请求失败', e)
  }
}

const goSearch = () => {
  if (!isLogin.value) {
    showLoginModal.value = true
    return
  }
  emit('go-search', searchKeyword.value)
}

// 根据类型搜索
const searchByType = (typeName) => {
  if (!isLogin.value) {
    showLoginModal.value = true
    return
  }
  emit('go-search', typeName)
}

const getGoodType = async () => {
  try {
    const res = await fetch('http://localhost:8088/mysqlGoods/goodstype', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    const data = await res.json()
    if (data.success) {
      goodTypeList.value = data.list
    }
  } catch (e) {
    console.error('请求失败', e)
  }
}

// 主页进入商品页
const toGoodsDetail = (goods) => {
  if (!isLogin.value) {
    showLoginModal.value = true
    return
  }
  sessionStorage.setItem('FROM_PAGE', 'home')
  sessionStorage.removeItem('SEARCH_KEYWORD')

  router.push({
    path: '/product',
    query: {
      Id: goods.Id,
      MerchantId: goods.MerchantId,
      GoodName: goods.GoodName,
      Price: goods.Price,
      GoodImg: goods.GoodImg,
      GoodType: goods.GoodType,
      ProductCode: goods.ProductCode
    }
  })
}

onMounted(async () => {
  await getGoodType()
  await getHomeImgAndGoods()
  timer = setInterval(() => {
    if (homeImgList.value.length === 0) return
    currentIndex.value = (currentIndex.value + 1) % homeImgList.value.length
  }, 5000)
})

onUnmounted(() => {
  clearInterval(timer)
})

const goToSlide = (index) => {
  currentIndex.value = index
}

const getPriceInt = (price) => {
  if (!price) return '0'
  return parseInt(price) || '0'
}

const getPriceDec = (price) => {
  if (!price) return '00'
  const s = String(price)
  const dot = s.split('.')[1]
  return dot ? dot.padEnd(2, '0').slice(0, 2) : '00'
}
</script>

<style scoped>
.home-page {
  width: 100vw;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
  position: relative;
}
.lock-mask {
  position: absolute;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100%;
  z-index: 99;
  cursor: not-allowed;
}
.search-section {
  width: 100%;
  height: 20vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
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
}
.search-btn {
  width: 100px;
  height: 42px;
  border: none;
  background: #00bcd4;
  color: #fff;
  border-radius: 0 6px 6px 0;
  cursor: pointer;
}
.banner-section {
  width: 80%;
  height: 40vh;
  background: #fff;
  border-radius: 12px;
  display: flex;
  padding: 15px;
  box-sizing: border-box;
  margin: 10px auto;
  position: relative;
}
.good-type-list {
  width: 40%;
  padding-left: 15px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.type-item {
  cursor: pointer;
  transition: color 0.2s;
}
.type-item:hover {
  color: #028fa8;
}
.space { width: 2%; }
.swiper-box {
  width: 58%;
  position: relative;
  background: #f9f9f9;
  border-radius: 8px;
  overflow: hidden;
}
.swiper-container {
  width: 100%;
  height: 100%;
  position: relative;
}
.swiper-img {
  position: absolute;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.6s ease;
}
.swiper-img.active {
  opacity: 1;
}
.dots {
  position: absolute;
  left: 12px;
  bottom: 10px;
  display: flex;
  gap: 6px;
  z-index: 10;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
  cursor: pointer;
}
.dot.active {
  background: #fff;
}
.goods-list-section {
  width: 80%;
  background: #fff;
  border-radius: 12px;
  padding: 15px;
  box-sizing: border-box;
  margin: 20px auto;
}
.goods-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.goods-card {
  width: calc(16.666% - 10px);
  height: 300px;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 1px 5px #00000010;
  cursor: pointer;
}
.goods-img-box {
  height: 60%;
}
.goods-img-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.goods-info {
  height: 40%;
  padding: 10px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.goods-name {
  font-size: 14px;
  font-weight: bold;
  margin-bottom: 6px;
  color: #333;
}
.goods-price {
  color: #ff4d4f;
  font-size: 16px;
}
.price-symbol {
  font-size: 14px;
}
.price-int {
  font-weight: bold;
}
.footer-section {
  width: 100%;
  height: 10vh;
  background: #00bcd4;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  margin-top: auto;
}
.footer-content {
  width: 80%;
  display: flex;
  justify-content: space-between;
}
.empty-tip { color: #999; }
.login-modal {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999;
}
.modal-content {
  background: #fff;
  padding: 30px;
  border-radius: 10px;
  text-align: center;
  width: 380px;
}
.modal-text {
  font-size: 18px;
  margin-bottom: 20px;
}
.modal-buttons {
  display: flex;
  gap: 15px;
  justify-content: center;
}
.login-btn {
  padding: 10px 30px;
  background: #00bcd4;
  color: #fff;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 15px;
}
.close-btn {
  padding: 10px 30px;
  background: #ccc;
  color: #333;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 15px;
}
</style>