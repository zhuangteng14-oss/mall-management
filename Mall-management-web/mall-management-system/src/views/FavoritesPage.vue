<template>
  <!--
    我的收藏（2026-10-19 终局清道夫第 7 条）—— 路由 `/customer/user/favorites` 与
    `/merchant/user/favorites`（客户 / 商家两个壳共用本组件，页面在 `router/index.js`
    文件尾追加，不移动既有行号）。

    数据全部来自真端点 `POST /favorite/list`（`FavoriteController` → `FavoriteDao`）：
    · 身份键 `UserId` 走 `getMemberId()`（customerid → merchantid → 登录名兜底）；
    · 列表左连 `goods`，商品被删后收藏行仍在 —— 这类行显示「商品已下架」，不给进详情；
    · 取消收藏走 `POST /favorite/remove`（幂等），成功后原地移除该行，不整页重拉。

    ⚠️ 页头出口用 `$route.path` 判壳（与 `MemberCenter.vue` 同款）：两个壳共用同一组件，
       不在 `script` 里再加一份「我在哪个壳」的判据。
  -->
  <main class="uc-main fav-page">
    <section class="page-head">
      <div>
        <div class="kicker">BUYER / FAVORITES</div>
        <h1>我的收藏 <span class="fav-n">共 {{ total }} 件</span></h1>
        <p>看中的器物先放这里；点卡片回到详情页，或直接取消收藏。</p>
      </div>
      <div class="head-actions">
        <router-link class="btn" :to="$route.path.startsWith('/merchant') ? '/merchant/user/info' : '/customer/user/info'">返回个人中心</router-link>
      </div>
    </section>

    <div v-if="loading" class="fav-empty">加载中…</div>

    <div v-else-if="!rows.length" class="fav-empty">
      <b>还没有收藏</b>
      <p>在商品详情页点「❤ 收藏」，商品就会出现在这里。</p>
      <router-link class="btn solid" to="/">去逛逛</router-link>
    </div>

    <div v-else class="fav-grid">
      <article v-for="row in rows" :key="row.Id" class="fav-card">
        <router-link
          v-if="!row.DeletedAt"
          class="fav-thumb"
          :to="linkOf(row)"
          :title="row.GoodName || '商品'"
        >
          <img v-if="row.GoodImg" :src="String(row.GoodImg)" :alt="row.GoodName || '商品'" />
          <span v-else class="fav-ph">无图</span>
        </router-link>
        <div v-else class="fav-thumb sold">
          <img v-if="row.GoodImg" :src="String(row.GoodImg)" :alt="row.GoodName || '商品'" />
          <span v-else class="fav-ph">无图</span>
          <span class="fav-sold">已下架</span>
        </div>

        <div class="fav-body">
          <router-link v-if="!row.DeletedAt" class="fav-name" :to="linkOf(row)">{{ row.GoodName || '未命名商品' }}</router-link>
          <span v-else class="fav-name muted">{{ row.GoodName || '未命名商品' }}</span>
          <div class="fav-meta">
            <span>{{ row.GoodType || '—' }}</span>
            <span>收藏于 {{ row.CreatedAt || '—' }}</span>
          </div>
          <div class="fav-foot">
            <span class="fav-price">¥{{ money(row.Price) }}</span>
            <button type="button" class="btn xs" :disabled="busyId === row.GoodsId" @click="remove(row)">
              {{ busyId === row.GoodsId ? '取消中…' : '取消收藏' }}
            </button>
          </div>
        </div>
      </article>
    </div>

    <p v-if="err" class="fav-err">{{ err }}</p>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { getMemberId } from '../api/auth'
import { favoriteList, favoriteRemove } from '../api/favorite'
import { productLink } from '../config/product-link'

const rows = ref([])
const total = ref(0)
const loading = ref(true)
const err = ref('')
const busyId = ref(null)

const userId = getMemberId()

function money(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '—'
}

/** 收藏行 → 详情页链接。`FavoriteDao.list` 回的是 `GoodsId`，链接契约要 `Id`。 */
function linkOf(row) {
  return productLink({
    Id: row.GoodsId,
    MerchantId: row.MerchantId,
    GoodName: row.GoodName,
    Price: row.Price,
    GoodImg: row.GoodImg,
    GoodType: row.GoodType,
    ProductCode: row.ProductCode,
  })
}

async function load() {
  if (!userId) {
    err.value = '未识别到登录账号，请重新登录后查看收藏'
    loading.value = false
    return
  }
  loading.value = true
  err.value = ''
  try {
    const data = await favoriteList(userId)
    if (data && data.success) {
      rows.value = data.list || []
      total.value = Number(data.total) || rows.value.length
    } else {
      err.value = (data && data.msg) || '收藏列表读取失败'
    }
  } catch (e) {
    err.value = '收藏列表读取失败：' + (e && e.message ? e.message : '网络错误')
  } finally {
    loading.value = false
  }
}

async function remove(row) {
  if (!userId || busyId.value !== null) return
  busyId.value = row.GoodsId
  err.value = ''
  try {
    const data = await favoriteRemove(userId, row.GoodsId)
    if (data && data.success) {
      rows.value = rows.value.filter((r) => r.Id !== row.Id)
      total.value = Math.max(0, total.value - 1)
    } else {
      err.value = (data && data.msg) || '取消收藏失败'
    }
  } catch (e) {
    err.value = '取消收藏失败：' + (e && e.message ? e.message : '网络错误')
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.fav-page { padding-bottom: var(--s8); }
.fav-n {
  font-family: var(--font);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-left: var(--s3);
}
.fav-empty {
  margin-top: var(--s6);
  padding: var(--s8) var(--s5);
  border: 1px dashed var(--line-2);
  text-align: center;
  color: var(--ink-3);
}
.fav-empty b { display: block; font-size: var(--fs-md); color: var(--ink); margin-bottom: var(--s2); }
.fav-empty p { margin: 0 0 var(--s4); font-size: var(--fs-sm); }
.fav-grid {
  margin-top: var(--s5);
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--s4);
}
.fav-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  background: var(--surface);
  overflow: hidden;
}
.fav-thumb {
  position: relative;
  display: block;
  aspect-ratio: 4 / 3;
  background: var(--bg-2);
}
.fav-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.fav-ph {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.fav-sold {
  position: absolute;
  left: 0;
  top: 0;
  padding: 2px 8px;
  background: rgba(0, 0, 0, .62);
  color: #fff;
  font-size: var(--fs-xs);
}
.fav-body { padding: var(--s3) var(--s3) var(--s4); display: flex; flex-direction: column; gap: var(--s2); }
.fav-name { font-size: var(--fs-sm); color: var(--ink); text-decoration: none; }
.fav-name:hover { text-decoration: underline; }
.fav-name.muted { color: var(--ink-3); }
.fav-meta { display: flex; flex-direction: column; gap: 2px; font-size: var(--fs-xs); color: var(--ink-3); }
.fav-foot { display: flex; align-items: center; justify-content: space-between; margin-top: var(--s1); }
.fav-price { font-family: var(--font-display); font-size: var(--fs-md); color: var(--ink); }
.fav-err { margin-top: var(--s4); font-size: var(--fs-sm); color: var(--danger, #b42318); }
</style>
