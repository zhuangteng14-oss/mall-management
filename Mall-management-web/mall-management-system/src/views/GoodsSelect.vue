<template>
  <div class="goods-select-view">
    <div class="search-section">
      <div class="search-wrapper">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="请输入搜索内容"
          @keyup.enter="doSearch"
        />
        <button class="search-btn" @click="doSearch">搜索</button>
      </div>
    </div>

    <div class="goods-wrap">
      <div v-if="goodsList.length === 0" class="empty-tip">暂无搜索结果</div>
      <div class="goods-grid">
        <div
          v-for="goods in goodsList"
          :key="goods.Id"
          class="goods-card"
          @click="toGoodsDetail(goods)"
        >
          <div class="goods-img-box">
            <img :src="goods.GoodImg" alt="商品图" />
          </div>
          <div class="goods-info">
            <div class="goods-name">
              {{ goods.GoodName }}：{{ goods.ProductCode }}
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const searchKeyword = ref('')
const goodsList = ref([])

// 保存搜索状态到 sessionStorage
const saveState = () => {
  sessionStorage.setItem('SEARCH_KEYWORD', searchKeyword.value)
  sessionStorage.setItem('SEARCH_RESULTS', JSON.stringify(goodsList.value))
}

// 从 sessionStorage 加载搜索状态
const loadState = () => {
  const keyword = sessionStorage.getItem('SEARCH_KEYWORD')
  const results = sessionStorage.getItem('SEARCH_RESULTS')
  if (keyword !== null) searchKeyword.value = keyword
  if (results) goodsList.value = JSON.parse(results)
}

// 执行搜索：根据关键词是否为空调用不同接口
const doSearch = async () => {
  const kw = searchKeyword.value.trim()
  try {
    let url, body;
    if (kw === '') {
      // 空搜索：调用获取全部商品接口
      url = 'http://localhost:8088/goods/goodsSelectHome'
      body = ''  // 该接口可能不需要参数，如果必须可传 'action=getAll' 等，根据后端调整
    } else {
      // 有关键词：调用搜索接口
      url = 'http://localhost:8088/goods/productSearch'
      body = `keyword=${encodeURIComponent(kw)}`
    }
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    })
    const data = await res.json()
    if (data.success) {
      goodsList.value = data.list || []
      saveState()
    }
  } catch (e) {
    console.error('请求失败', e)
  }
}

// 供父组件调用（主页搜索触发）
const searchByKeyword = async (kw) => {
  // 新搜索前清除旧缓存（注意：空搜索也会清除旧的搜索结果缓存）
  sessionStorage.removeItem('SEARCH_KEYWORD')
  sessionStorage.removeItem('SEARCH_RESULTS')
  searchKeyword.value = kw
  await doSearch()
}

// 跳转到商品详情页，携带 from=search 标记，并保存当前状态
const toGoodsDetail = (goods) => {
  saveState()  // 跳转前保存
  router.push({
    path: '/product',
    query: {
      Id: goods.Id,
      MerchantId: goods.MerchantId,
      GoodName: goods.GoodName,
      Price: goods.Price,
      GoodImg: goods.GoodImg,
      GoodType: goods.GoodType,
      ProductCode: goods.ProductCode,
      from: 'search'
    }
  })
}

// 辅助方法：价格格式化
const getPriceInt = (price) => {
  if (!price) return '0'
  return parseInt(price) || '0'
}
const getPriceDec = (price) => {
  if (!price) return '00'
  const s = String(price)
  const arr = s.split('.')
  return arr[1] ? arr[1].padEnd(2, '0').slice(0, 2) : '00'
}

// 组件挂载时，如果是返回场景（URL 带有 back=search），则恢复数据
onMounted(() => {
  if (route.query.back === 'search') {
    loadState()
  }
})

// 暴露方法给父组件
// eslint-disable-next-line no-undef
defineExpose({ searchByKeyword })
</script>



<style scoped>
.goods-select-view {
  width: 100%;
  height: 100%;
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
.goods-wrap {
  width: 80%;
  margin: 5vh auto 0;
  background: #fff;
  border-radius: 12px;
  padding: 15px;
  box-sizing: border-box;
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
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.1);
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
  font-size: 20px;
  font-weight: bold;
  margin-bottom: 10px;
  color: #333;
}
.goods-price {
  color: #ff4d4f;
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.price-symbol {
  font-size: 15px;
  font-weight: bold;
}
.price-int {
  font-size: 40px;
  font-weight: bold;
}
.price-dec {
  font-size: 15px;
  font-weight: bold;
}
.empty-tip {
  color: #999;
  font-size: 16px;
  text-align: center;
  padding: 40px 0;
}
</style>