/* eslint-disable */
<template>
  <div class="home-page">
    <!-- 原未登录黄条（.login-hint-bar）已删 —— 2026-10-06 改判 2（§5-AG (13)）：入口改由顶栏「登录 / 注册」承担；理由见 <script setup> 顶部 9 行 -->

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

    <!-- 原「登录提示」弹窗（.login-modal 系）已删 —— 三条拦截拆除后它没有调用方，文案随之作废；理由见 <script setup> 顶部 -->
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'

// ── 未登录可逛公开面（2026-10-06 用户改判 2，登记见 progress.md §5-AG (13)）────────────
// 本页原有三条「未登录 ⇒ 弹登录提示」拦截（goSearch / searchByType / toGoodsDetail）与一条
// 顶部黄条 `.login-hint-bar` —— **全部拆除**。依据：搜索页 / 商品页都是**公开面**（后端
// AUTH_WHITELIST 的 goods* 5 条本就匿名可用；路由 meta.public 也已把 `/<role>/search` 与
// `/product` 判为公开），淘宝/京东同为「随便逛、关键操作才锁」⇒ 原「先登录再逛」是改判。
// **被锁的面一个不变**：购买（ProductPage 两处 customerId 守卫）、消息、用户中心、购物车
// （受保护路由 + navFor 未登录不渲染那三项）。匿名入口统一走顶栏「登录 / 注册」
// （config/nav.js 的 ANON_RIGHT）；登录后回原目标由路由守卫 + LoginPage.landingAfterLogin 承担。
// ⚠️ 本页因此**不再有匿名分支** ⇒ `isLoggedIn` 的 import 与 `isLogin` computed 一并删除（原 `:119` 保留为按语，供 `main.js:21` 的指向不悬空）。

const router = useRouter()
// eslint-disable-next-line no-undef
const emit = defineEmits(['go-search'])

const goodTypeList = ref([])
const homeImgList = ref([])
const goodsList = ref([])
const currentIndex = ref(0)
const searchKeyword = ref('')

let timer = null

// ⚠️ 此处原有「是否登录」的**唯一**判据 `const isLogin = computed(() => isLoggedIn())`（鉴权笔的落点），
//   2026-10-06 随改判 2 删除（本页已无匿名分支）；保留本行占位，使 `main.js:21` 的 `HomePage.vue:119` 不悬空。

const getHomeImgAndGoods = async () => {
  try {
    const res = await http.post(endpoints.goods.goodsSelectHome)
    const data = await res.json()
    if (data.success) {
      goodsList.value = data.list
      homeImgList.value = data.list
    }
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

const goSearch = () => {
  // 未登录放行（2026-10-06 改判 2，§5-AG (13)）：落地页 `/<role>/search` 是公开面（见本节顶部）。
  // 这 4 行原是一道「弹登录提示 + return」的拦截，现已删除；匿名入口只剩顶栏「登录 / 注册」。
  // ⚠️ 停在这里的**不是**「忘了判登录态」：搜索页本来就不需要身份，后端 goods* 5 条也在白名单里。
  // 本函数不再读登录态 ⇒ 无分支（`isLogin` 已随之删除）。
  emit('go-search', searchKeyword.value)
}

// 根据类型搜索
const searchByType = (typeName) => {
  // 未登录放行（2026-10-06 改判 2，§5-AG (13)）：同 goSearch —— 分类搜索也落在公开的 `/<role>/search`。
  // 这 4 行原是一道「弹登录提示 + return」的拦截，现已删除。
  // 触发入口是首页分类块（模板里的 `@click="searchByType(...)"`），走的是同一个 `emit('go-search')`。
  // 本函数不再读登录态 ⇒ 无分支（`isLogin` 已随之删除）。
  emit('go-search', typeName)
}

const getGoodType = async () => {
  try {
    const res = await http.post(endpoints.goods.goodstype)
    const data = await res.json()
    if (data.success) {
      goodTypeList.value = data.list
    }
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

// 主页进入商品页
const toGoodsDetail = (goods) => {
  // 未登录放行（2026-10-06 改判 2，§5-AG (13)）：`/product` 已由路由 `meta.public` 判为公开面。
  // 这 4 行原是一道「弹登录提示 + return」的拦截，现已删除；购买动作仍锁在 ProductPage 的两处守卫里。
  // ⚠️ 匿名进详情页**不会**触发 401：挂载期只打 goodsSelectHome / GoodsProductCodeSelect / productCodePrice，三条都在白名单。
  // ⚠️ 下面 7 个 query 参数原样保留：会话过期后 `orderAdd` 401 的回跳靠它们还原本页（T-guard-6 判据）。
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
/* 原 .login-hint-bar（未登录黄条，:225-233）与文末 .login-modal 系 6 个块均已删 —— 2026-10-06 改判 2，见 §5-AG (13) */
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
</style>