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
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { http, handleApiError, urlOf } from '../api/http'
import { endpoints } from '../api/endpoints'

// ⚠️ 这个 name 是**两个壳**里 `<KeepAlive :include="['HomeTab', 'GoodsSelect']">` 的
//    **唯一**匹配依据。改名（含改文件名）会让缓存**静默失效** —— 不报错，只是切回来
//    重新挂载（搜索态丢失）。改名前先 grep `include`。下一行那条 `eslint-disable-next-line` 是**必需**的
//    （本仓 `eslint-plugin-vue` 8.7.1 的 `vue3-essential` **不带** `vue/setup-compiler-macros`
//    宏环境 ⇒ 宏被判 `no-undef`）—— 块级写法先例见 `ShoppingCart.vue:73` 的 `/* eslint-disable no-undef */`
//    （R2 前此处引的是本文件自己的 `defineExpose`，那个宏已退役，见文件末按语）。
// eslint-disable-next-line no-undef
defineOptions({ name: 'GoodsSelect' })

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
      url = urlOf(endpoints.goods.goodsSelectHome)
      body = ''  // 该接口可能不需要参数，如果必须可传 'action=getAll' 等，根据后端调整
    } else {
      // 有关键词：调用搜索接口
      url = urlOf(endpoints.goods.productSearch)
      body = `keyword=${encodeURIComponent(kw)}`
    }
    const res = await http.post(url, body)
    const data = await res.json()
    if (data.success) {
      goodsList.value = data.list || []
      saveState()
    }
  } catch (e) {
    handleApiError(e, '请求失败')
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

// ── R1（2026-10-05）：客户壳路由化后，搜索关键词改由 URL 承载；R2 起**两壳同一套** ────────
// 两壳的首页搜索框/分类点击 ⇒ `/customer/search?kw=xxx` / `/merchant/search?kw=xxx`
// （`views/HomeTab.vue` 适配器推的，`base` 由路由记录给）⇒ 本条 watch 触发。
// `immediate: true` 覆盖「首挂载就带 kw」（含**直接输 URL** 与**刷新**）。
//
// ⚠️ **R2 起这是唯一的搜索入口通道**（R1 时它只是「纯增量的第二条通道」）：
//    R1 的商家侧走组件 `ref`（`MerchantPage.vue` 直挂 + `defineExpose`），R2 路由化后那条
//    通道整条退役（见文件末的按语）⇒ `searchByKeyword` 仍必须保留（本 watch 在调用它）。
// ⚠️ 下面的 `onMounted`（`back=search` ⇒ `loadState()`）**不能**改成 `onActivated`：
//    理由**已换**（结论不变）—— R2 起商家侧**也有** `<KeepAlive>` 了，但**离开壳会连缓存一起销毁**
//    （`/product` 是 `/merchant` / `/customer` 的**兄弟**路由 ⇒ 壳卸载）⇒ 回到搜索页必**重新挂载**
//    ⇒ `onMounted` 必触发。（R1 写的理由「商家侧直挂、无 KeepAlive」已失效，见 §5-X (3) 末按语。）
watch(() => route.query.kw, (kw) => {
  if (kw) searchByKeyword(kw)
}, { immediate: true })

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

// ── R2（2026-10-05）：`defineExpose({ searchByKeyword })` **已退役**（用户裁决 5）─────────────
// 它 R1 前的唯一调用方是商家壳的 `ref` 通道（`MerchantPage.vue` 的 `goSearch()` / `handlePendingSearch()`
// 通过 `goodsSelectRef.value.searchByKeyword(...)` 直接调）—— R2 把商家壳也路由化后，
// `<router-view>` 里拿不到子组件 ref，那条通道整条消失（关键词改由 `?kw=` 承载，见上一条 watch）。
// ⇒ 删掉「暴露」这件事本身；`searchByKeyword` 函数**保留**（上面的 watch 在调用它）。
// ⚠️ 与 R1 的差别只在**暴露**：R1 时它是「纯增量的第二条通道」，R2 起它是**唯一**通道。
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