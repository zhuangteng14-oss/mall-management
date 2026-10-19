<template>
  <div class="bz-page">
    <!-- 壳外公开页（`meta.public`），自带顶栏 —— 形制照 `SeckillPage.vue` / `ShopHome.vue`。
         ⚠️ 不放搜索框：本页没有「壳内搜索流」这条入口（判据同 SeckillPage 顶栏注释）。 -->
    <nav class="nav-bar">
      <button type="button" class="nav-back" @click="goBack">‹ 返回</button>
      <router-link class="brand" :to="homePath">商城</router-link>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div>
    </nav>

    <section class="band">
      <div class="band-in">
        <nav class="crumb">
          <router-link :to="homePath">首页</router-link>
          <i class="sp">/</i><span>品牌专区</span>
        </nav>
        <div class="hd">
          <span class="hd-nm">品牌专区</span>
          <span class="hd-sub">品牌加盟商家的在架商品</span>
        </div>
      </div>
    </section>

    <main class="wrap">
      <p v-if="errMsg" class="tip">
        {{ errMsg }}
        <button type="button" class="tip-a" @click="retry">重试</button>
      </p>
      <p v-else-if="loading && items.length === 0" class="tip">正在加载品牌商品…</p>
      <p v-else-if="!items.length" class="tip">暂无品牌商品 —— 品牌加盟审核通过后，本店商品会出现在这里。</p>

      <div v-else class="grid">
        <ProductCard v-for="g in list" :key="g.Id" :goods="g" />
      </div>

      <!-- 哨兵：滚到这里就再要一页（`useInfiniteList` 的 IO 挂点）。 -->
      <div ref="sentinel" class="sentinel">
        <span v-if="loading" class="dim">加载中…</span>
        <span v-else-if="done && items.length" class="dim">没有更多了</span>
      </div>
    </main>
  </div>
</template>

<script setup>
// 品牌专区（批四第二批 commit 2，2026-10-19）—— 路由 `/brand`（壳外、`meta.public`）。
//
// 判据全部在服务端：`/goods/brandHome` 只回 `ShelfState=1 AND DeletedAt IS NULL AND Status=2`
// **且**商家 `user.IsBrand=1` 的商品（见 `GoodsCacheService.brandZone`）。本页不自己筛品牌，
// 也不从 `goodsSelectHome` 里二次过滤 —— 那会把「品牌」的判据抄成第二份。
//
// 列表侧共用「去重 + 滤已售完」口径（`config/goods-flags.js` 的 `sharedList`，与首页一致）：
// 品牌专区是**共享面**，售完商品不该占位。
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, isLoggedIn } from '../api/auth'
import { useInfiniteList } from '../composables/useInfiniteList'
import { sharedList } from '../config/goods-flags'
import ProductCard from '../components/ProductCard.vue'

const router = useRouter()
const isLogin = computed(() => isLoggedIn())
const homePath = computed(() => (getMerchantId() ? '/merchant' : '/customer'))

const PAGE_SIZE = 24
const feed = useInfiniteList(async (offset, limit) => {
  const res = await http.post(endpoints.goodsB4.brandHome,
    new URLSearchParams({ limit: String(limit), offset: String(offset) }))
  const data = await res.json()
  if (!data.success) throw new Error(data.msg || '请求失败')
  return { rows: data.list || [] }
}, PAGE_SIZE)

const { items, loading, done, errMsg, sentinel, retry } = feed
const list = computed(() => sharedList(items.value))

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push(homePath.value)
}
function goUserCenter() {
  router.push(isLogin.value ? `${homePath.value}/user/info` : '/login')
}

onMounted(() => { feed.loadNext() })
</script>

<style scoped>
.bz-page {
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
  font-size: var(--fs-sm);
}
.nav-bar {
  display: flex;
  align-items: center;
  gap: var(--s4);
  padding: var(--s3) var(--s5);
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}
.nav-back {
  border: 0;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  font-size: var(--fs-sm);
}
.brand {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  color: var(--ink);
  text-decoration: none;
}
.nav-right {
  margin-left: auto;
  color: var(--ink-2);
  cursor: pointer;
}
.band {
  background: var(--brand-soft);
  border-bottom: 1px solid var(--line);
}
.band-in {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s4) var(--s5) var(--s5);
}
.crumb {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-bottom: var(--s2);
}
.crumb a {
  color: var(--ink-3);
  text-decoration: none;
}
.crumb .sp {
  margin: 0 6px;
  font-style: normal;
}
.hd {
  display: flex;
  align-items: baseline;
  gap: var(--s4);
}
.hd-nm {
  font-family: var(--font-display);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
}
.hd-sub {
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.wrap {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s6) var(--s5) var(--s8);
}
.grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s4);
}
@media (max-width: 980px) {
  .grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
.tip {
  color: var(--ink-3);
  padding: var(--s6) 0;
}
.tip-a {
  border: 0;
  background: transparent;
  color: var(--brand-ink);
  cursor: pointer;
  text-decoration: underline;
}
.sentinel {
  display: flex;
  justify-content: center;
  padding: var(--s5) 0 var(--s2);
}
.dim {
  color: var(--ink-3);
  font-size: var(--fs-xs);
}
</style>
