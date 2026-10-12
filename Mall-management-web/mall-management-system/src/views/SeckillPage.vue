<template>
  <div class="sk-page">
    <!-- ── 自带顶栏 ───────────────────────────────────────────────────────────────
         本页路由带 `meta.public: true` ⇒ **不进壳**：没有壳的左菜单，也没有壳顶栏
         （与 `ProductPage.vue` / `ShopHome.vue` 同款做法）。
         ⚠️ **与商品详情页的顶栏有一处有意不同：本页没有搜索框**。那一页的框是
         `PENDING_SEARCH_KEYWORD` 那条「壳内搜索流」的入口，本页没有那条流 ⇒
         放一个点了会跳走的框 = 画一个假控件（判据与 `ShopHome.vue` 的顶栏注释逐字相同）。 -->
    <nav class="nav-bar">
      <button type="button" class="nav-back" @click="goBack">‹ 返回</button>
      <router-link class="brand" :to="homePath">商城</router-link>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div>
    </nav>

    <!-- ── 页头：一条整幅横带（**本页唯一「敢用块面」的地方**）─────────────────────────
         ⚠️ 三个「不做」各对着一条 AI 默认样（判据见 `tokens.css` 用法规矩与本仓历笔登记的
            「AI 默认样」清单）：不做白底圆角卡墙、不做全大写 eyebrow、不做「A · B · C」中圆点串。
         底用 `--zone`（`tokens.css` 自己的**区块底**）：它说的是「这一页讲一件事」。 -->
    <section class="band">
      <div class="band-in">
        <nav class="crumb">
          <router-link :to="homePath">首页</router-link>
          <i class="sp">/</i><span>限时秒杀</span>
        </nav>
        <div class="hd">
          <span class="hd-nm">限时秒杀</span>
          <span class="hd-sub">
            共 <b>{{ activities.length }}</b> 场<template v-if="liveCount">，其中 <b>{{ liveCount }}</b> 场进行中</template>
          </span>
        </div>
      </div>
    </section>

    <main class="wrap">
      <!-- ⚠️ **加载中 / 未登录 / 空 三态分开**，不合成一句「暂无数据」：
           未登录是「你看不到」、空是「现在没有」—— 同一句话说两件事是错的（本仓既有口径）。 -->
      <p v-if="!isLogin" class="tip">
        登录后即可查看进行中的秒杀活动 ——
        <router-link class="tip-a" to="/login">去登录</router-link>
      </p>
      <p v-else-if="loading" class="tip">正在加载秒杀活动…</p>
      <p v-else-if="!activities.length" class="tip">当前没有进行中或即将开始的秒杀活动。</p>

      <ul v-else class="list">
        <li v-for="row in activities" :key="row.ActivityId" class="item">
          <router-link class="item-in" :to="productLink(row)">
            <span class="ph">
              <img v-if="row.GoodImg" :src="imgSrc(row.GoodImg)" :alt="row.Name || row.GoodName" />
              <span v-else class="ph-none">无图</span>
            </span>
            <span class="tx">
              <span class="nm">{{ row.Name || row.GoodName }}</span>
              <span class="shop">{{ row.MerchantAccount }}</span>
              <span class="meta">
                <!-- ⚠️ 阶段（`Stage`）由**后端按库时钟现算**，前端不自己比时间 —— 浏览器钟
                     与库钟差一个时区，自己算会整整错 8 小时且**不报错**（后端 `SeckillDao.stage`）。 -->
                <span class="chip" :class="stageClass(row.Stage)">{{ stageLabel(row.Stage) }}</span>
                <span v-if="row.SoldOut" class="chip soldout">已售完</span>
                <span v-else-if="row.SoldLeft !== null && row.SoldLeft !== undefined" class="left">
                  剩余 <b>{{ row.SoldLeft }}</b> 件
                </span>
                <span v-else class="left">不限量</span>
              </span>
            </span>
            <span class="pr">
              <b class="now">¥{{ money(row.SeckillPrice) }}</b>
              <s class="was">¥{{ money(row.OriginalPrice) }}</s>
            </span>
          </router-link>
        </li>
      </ul>
    </main>
  </div>
</template>

<script setup>
// src/views/SeckillPage.vue —— 用户端「限时秒杀」（C3 块 5 · commit 4，2026-10-12）。
//
// ⚠️ **本页与主页的秒杀模块同源**：都读 `/marketing/seckill/live`（`SeckillDao.selectLive`）。
//    后端已经把**已结束 / 已停用 / 商品下架**的行挡掉了 ⇒ 见到一行就等于「这一场此刻仍然挂着」，
//    本页**不做任何二次过滤**（在前端再筛一遍 = 造第二个真源）。
//
// ⚠️ **阶段（`Stage`）与售罄（`SoldOut`）都由后端说了算**：
//    · `Stage` 是 SQL 里按**库时钟 `NOW()`** 现算的（`signup` / `upcoming` / `live` / `ended`）；
//    · `SoldOut` 是 Java 侧拿两个**数字**比出来的（不是 SQL 里的 `0/1` —— 那个在 JS 里
//      非空字符串 `'0'` 是**真值**，会满屏「已售完」）。
//    本页两者都**只读不算**。
//
// ⚠️ **登录才发请求**：`/marketing/seckill/live` **不在** `AUTH_WHITELIST` 内（后端刻意没放宽
//    公开面，口径同 `/marketing/subsidy/goods`）⇒ 匿名发它必 401，而 `http.js` 的
//    `handleUnauthorized` 会**把公开页踢回登录页**。故先判 `isLoggedIn()`（同 `ProductPage` 的领券块）。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { seckillLive } from '../api/marketing'
import { getMerchantId, isLoggedIn } from '../api/auth'

const router = useRouter()

const rows = ref([])
const loading = ref(false)

const isLogin = computed(() => isLoggedIn())
/** 未登录时返回落点按角色分（同本仓其它公开页的「首页」链接）。 */
const homePath = computed(() => (getMerchantId() ? '/merchant' : '/customer'))

/**
 * ⚠️ **`signup` 那一档不显示**：报名期的活动对**买家**还不存在（`SeckillDao.stage` 的注释：
 *    主页不显示）—— 本页与主页同口径，只留 `live` 与 `upcoming`。
 */
const activities = computed(() => rows.value.filter((r) => r.Stage !== 'signup'))
const liveCount = computed(() => activities.value.filter((r) => r.Stage === 'live').length)

const STAGE_LABELS = { upcoming: '即将开始', live: '进行中', ended: '已结束' }
const stageLabel = (s) => STAGE_LABELS[s] || '进行中'
const stageClass = (s) => (s === 'upcoming' ? 'soon' : 'on')

const money = (v) => Number(v || 0).toFixed(2)

/** 图片：本仓商品图存的是相对路径，与 `ProductPage` / `HomePage` 同一处理（裸路径直接当 src）。 */
const imgSrc = (u) => String(u)

/** 去商品详情页 —— `query` 四件套与三处商品卡**逐字相同**（那边是 `ProductCard.vue` 的口径）。 */
function productLink(row) {
  return {
    path: '/product',
    query: {
      MerchantId: row.MerchantAccount,
      GoodName: row.Name || row.GoodName,
      GoodType: row.GoodType,
      Id: row.GoodsId,
    },
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push(homePath.value)
}

function goUserCenter() {
  router.push(isLoggedIn() ? (getMerchantId() ? '/merchant/user' : '/customer/user') : '/login')
}

async function loadLive() {
  if (!isLoggedIn()) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const data = await seckillLive({ limit: 100 })
    rows.value = data && data.success && Array.isArray(data.list) ? data.list : []
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadLive)
</script>

<style scoped>
.sk-page {
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
}

/* ── 顶栏（与 `ShopHome.vue` / `ProductPage.vue` 同形制：一条 60px 高的横条，左侧返回）── */
.nav-bar { display: flex; align-items: center; gap: var(--s4); height: 60px; padding: 0 var(--s7);
           background: var(--nav-bg); border-bottom: 1px solid var(--line); }
.nav-back { font-size: var(--fs-md); color: var(--ink-2); padding: 0 var(--s2); white-space: nowrap; }
.brand { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-semi);
         color: var(--brand-ink); white-space: nowrap; text-decoration: none; }
.nav-right { margin-left: auto; color: var(--nav-tx); font-weight: var(--fw-med); cursor: pointer; white-space: nowrap; }

/* ── 页头横带（本页唯一一处块面；理由见模板注释）── */
.band { background: var(--zone); border-bottom: 1px solid var(--line); }
.band-in { max-width: 1180px; margin: 0 auto; padding: var(--s4) var(--s5) var(--s5); }
.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--ink-3); }
.crumb a { color: inherit; text-decoration: none; }
.crumb a:hover { color: var(--brand-ink); }
.crumb .sp { font-style: normal; padding: 0 var(--s2); }
.hd { margin-top: var(--s3); display: flex; align-items: baseline; gap: var(--s4); }
.hd-nm { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med); }
.hd-sub { font-size: var(--fs-xs); color: var(--ink-3); }
.hd-sub b { color: var(--ink-2); font-weight: var(--fw-med); font-variant-numeric: tabular-nums; }

.wrap { max-width: 1180px; margin: 0 auto; padding: var(--s6) var(--s5) var(--s8); }
.tip { font-size: var(--fs-sm); color: var(--ink-3); padding: var(--s6) 0; }
.tip-a { color: var(--brand-ink); }

.list { list-style: none; display: grid; gap: var(--s4); }
.item { border: 1px solid var(--line); border-radius: var(--r2); background: var(--card); }
.item-in { display: flex; align-items: center; gap: var(--s4); padding: var(--s4); text-decoration: none; color: inherit; }
.item-in:hover .nm { color: var(--brand-ink); }

.ph { flex: 0 0 auto; width: 96px; height: 72px; border-radius: var(--r1); overflow: hidden;
      background: var(--zone); display: flex; align-items: center; justify-content: center; }
.ph img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ph-none { font-size: var(--fs-xs); color: var(--ink-3); }

.tx { min-width: 0; display: flex; flex-direction: column; gap: var(--s1); }
.nm { font-size: var(--fs-sm); font-weight: var(--fw-med); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shop { font-size: var(--fs-xs); color: var(--ink-3); }
.meta { display: flex; align-items: center; gap: var(--s3); margin-top: var(--s1); }

/* ⚠️ 阶段与售罄**都不吃 `--accent`** —— 朱砂在本仓只负责价格 / 促销 / 未读（`tokens.css:34-42`），
   而下面 `.now` 那条价格已经把它用掉了；同一张卡里两处吃同一个强调色 = 谁都强调不了。 */
.chip { font-size: var(--fs-xs); padding: 0 var(--s2); border-radius: var(--r1); white-space: nowrap; }
.chip.on { background: var(--brand-soft); color: var(--brand-ink); }
.chip.soon { background: var(--zone); color: var(--ink-2); }
.chip.soldout { background: var(--ink-3); color: var(--card); }
.left { font-size: var(--fs-xs); color: var(--ink-2); }
.left b { font-variant-numeric: tabular-nums; }

.pr { margin-left: auto; flex: 0 0 auto; text-align: right; display: flex; flex-direction: column; gap: var(--s1); }
.now { color: var(--accent); font-size: var(--fs-lg); font-variant-numeric: tabular-nums; }
.was { color: var(--ink-3); font-size: var(--fs-xs); }
</style>
