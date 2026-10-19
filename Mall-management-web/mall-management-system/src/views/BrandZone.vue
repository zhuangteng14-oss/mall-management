<template>
  <div class="bz-page">
    <!-- 壳外公开页（`meta.public`），自带顶栏 —— 形制照 `SeckillPage.vue` / `ShopHome.vue`。
         ⚠️ 不放搜索框：本页没有「壳内搜索流」这条入口（判据同 SeckillPage 顶栏注释）。 -->
    <nav class="nav-bar">
      <button type="button" class="nav-back" @click="goBack">‹ 返回</button>
      <router-link class="brand" :to="homePath">商城</router-link>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div>
    </nav>

    <!-- 头图：深空灰 → 高级黑，右下角一层香槟金辉光；毛玻璃说明卡压在同一块渐变上。
         文案只说服务端真正在筛的东西（品牌加盟审核 + 在架可售），不写「每一件都可追溯」这类
         本页拿不出证据的话。 -->
    <header class="hero">
      <div class="hero-mesh" aria-hidden="true"></div>
      <div class="hero-in">
        <div class="hero-copy">
          <nav class="crumb">
            <router-link :to="homePath">首页</router-link>
            <i class="sp">/</i><span>品牌专区</span>
          </nav>
          <p class="hero-kicker">平台直审 · 品牌加盟</p>
          <h1 class="hero-title">品牌专区</h1>
          <p class="hero-lede">只陈列通过品牌加盟审核的商家在架商品；售完与下架即移出本页。</p>
          <span class="hero-rule" aria-hidden="true"></span>
        </div>

        <aside class="hero-glass" aria-label="专区说明">
          <div class="glass-hd">
            <span class="glass-seal" aria-hidden="true">品</span>
            <span class="glass-title">专区说明</span>
          </div>
          <dl class="glass-list">
            <div class="glass-row"><dt>入驻门槛</dt><dd>平台审核通过</dd></div>
            <div class="glass-row"><dt>在架口径</dt><dd>上架且未售完</dd></div>
            <div class="glass-row"><dt>会员权益</dt><dd>会员价同享</dd></div>
          </dl>
        </aside>
      </div>
    </header>

    <main class="wrap">
      <p v-if="errMsg" class="tip">
        {{ errMsg }}
        <button type="button" class="tip-a" @click="retry">重试</button>
      </p>

      <!-- 首屏骨架：只占「还没拿到第一页」这一段，翻页时下面的哨兵会说「加载中」。 -->
      <div v-else-if="loading && items.length === 0" class="sk-shelf" aria-hidden="true">
        <div v-for="n in 4" :key="n" class="sk-card">
          <div class="sk-img"></div>
          <div class="sk-line"></div>
          <div class="sk-line short"></div>
        </div>
      </div>

      <div v-else-if="!items.length" class="empty">
        <span class="empty-mark" aria-hidden="true">品</span>
        <h2 class="empty-title">暂无品牌商品</h2>
        <p class="empty-sub">品牌加盟审核通过后，本店在架商品会出现在这里。</p>
        <router-link class="empty-cta" :to="homePath">去商城逛逛</router-link>
      </div>

      <template v-else>
        <!-- 品牌橱窗：左一占满两行的品牌大图卡，右侧四枚小卡。手机端整体堆叠。 -->
        <section v-if="showShowcase" class="showcase" aria-label="品牌精选">
          <div class="sec-hd">
            <h2 class="sec-title">本季精选</h2>
            <span class="sec-sub">来自品牌加盟商家的在架单品</span>
          </div>
          <div class="show-grid">
            <router-link class="show-card show-feature" :to="linkOf(feature)">
              <img v-if="feature.GoodImg" :src="feature.GoodImg" :alt="feature.GoodName" loading="lazy" />
              <span class="show-badge"><i class="mono">{{ brandInitial(feature) }}</i><b>{{ brandName(feature) }}</b></span>
              <div class="show-copy">
                <span class="show-kicker">精选</span>
                <h3 class="show-name">{{ feature.GoodName }}</h3>
                <span class="show-price">¥{{ fmt(feature.Price) }}</span>
              </div>
              <span class="show-cta">查看详情 <i aria-hidden="true">→</i></span>
            </router-link>

            <router-link v-for="g in minis" :key="g.Id || g.ProductCode" class="show-card show-mini" :to="linkOf(g)">
              <img v-if="g.GoodImg" :src="g.GoodImg" :alt="g.GoodName" loading="lazy" />
              <span class="mini-badge"><i class="mono">{{ brandInitial(g) }}</i><b>{{ brandName(g) }}</b></span>
              <div class="mini-copy">
                <span class="mini-name">{{ g.GoodName }}</span>
                <span class="mini-price">¥{{ fmt(g.Price) }}</span>
              </div>
            </router-link>
          </div>
        </section>

        <!-- 货架：六列栅格，常规卡跨 2 列（一行三枚）、每五枚里后两枚跨 3 列（一行两枚）——
             3×2 + 2×3 = 12 列正好铺满两行，不靠 `dense` 重排，顺序仍是服务端的 `Id DESC`。 -->
        <section class="shelf" aria-label="全部品牌商品">
          <div class="sec-hd">
            <h2 class="sec-title">全部在架</h2>
            <span class="sec-sub">已加载 {{ shelfCount }} 件</span>
          </div>
          <div class="grid">
            <ProductCard v-for="(g, i) in list" :key="g.Id || g.ProductCode || i" :goods="g" :class="{ wide: i % 5 >= 3 }" />
          </div>
        </section>
      </template>

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
//
// 第二步 UI 升维（2026-10-19）：深空灰头图 + 毛玻璃说明卡 + 左大右小的品牌橱窗 + 错落货架。
// 视觉只在本页的 `<style scoped>` 里成立，**不改** `ProductCard.vue` 的通用形制（卡上品牌标
// 已在组件里单独升成黑金，那是全局品牌语义）。
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, isLoggedIn } from '../api/auth'
import { useInfiniteList } from '../composables/useInfiniteList'
import { sharedList } from '../config/goods-flags'
import { productLink } from '../config/product-link'
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

// 橱窗取前五件：1 枚大图 + 4 枚小图。不足 3 件时整块不画 —— 一枚大图配一枚小图不成阵型，
// 不如直接落到货架。
const showcase = computed(() => list.value.slice(0, 5))
const feature = computed(() => showcase.value[0] || null)
const minis = computed(() => showcase.value.slice(1))
const showShowcase = computed(() => list.value.length >= 3)
const shelfCount = computed(() => list.value.length)

const fmt = (v) => Number(v || 0).toFixed(2)
const linkOf = (g) => productLink(g)
// `brandHome` 只投影了商家登录名（`UserName`），没有独立的品牌名 / Logo 字段。
// 徽章用「店名首字」做字母标 + 店名 —— 这是数据里真有的东西，不 P 一个假 Logo 上去。
const brandName = (g) => (g && g.UserName ? String(g.UserName) : '品牌商家')
const brandInitial = (g) => brandName(g).trim().slice(0, 1) || '品'

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
/* ── 本页色板（只在本组件生效）────────────────────────────────────────────────────
   深空灰 / 高级黑打底，香槟金只做「品牌」这一件事的强调（眉题、徽章、价格、发丝线）。
   ⚠️ 这是用户第二步口令明确授权的越族配色（品牌语义），不回流到 tokens.css，
      秒杀仍朱砂、国补仍青瓷绿。 */
.bz-page {
  --bz-black: #0c0d0f;
  --bz-graphite: #17191c;
  --bz-gold: #c9a25a;
  --bz-gold-2: #e8c98a;
  --bz-cream: #f4f1ea;
  --bz-line: rgba(255, 255, 255, .1);
  --bz-ease: cubic-bezier(.22, .61, .36, 1);
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
  font-size: var(--fs-sm);
}

/* ── 顶栏：与头图连成一块深色，滚动时毛玻璃。 ─────────────────────────────────── */
.nav-bar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: var(--s4);
  padding: var(--s3) var(--s5);
  background: rgba(12, 13, 15, .92);
  border-bottom: 1px solid var(--bz-line);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  color: var(--bz-cream);
}
.nav-back {
  border: 0;
  background: transparent;
  color: rgba(244, 241, 234, .72);
  cursor: pointer;
  font-size: var(--fs-sm);
  transition: color .2s;
}
.nav-back:hover { color: var(--bz-gold-2); }
.brand {
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: .16em;
  color: var(--bz-cream);
  text-decoration: none;
}
.nav-right {
  margin-left: auto;
  color: rgba(244, 241, 234, .72);
  cursor: pointer;
  transition: color .2s;
}
.nav-right:hover { color: var(--bz-gold-2); }
.nav-back:focus-visible,
.nav-right:focus-visible,
.brand:focus-visible {
  outline: 2px solid var(--bz-gold);
  outline-offset: 4px;
  border-radius: var(--r1);
}

/* ── 头图 ─────────────────────────────────────────────────────────────────────── */
.hero {
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(900px 420px at 82% -12%, rgba(201, 162, 90, .22), transparent 68%),
    radial-gradient(700px 380px at 8% 112%, rgba(79, 96, 104, .22), transparent 70%),
    linear-gradient(138deg, #16181b 0%, #0c0d0f 56%, #17130d 100%);
  color: var(--bz-cream);
}
/* 细网格 + 向下淡出：给「深空」一点尺度感，不做整屏纹理。 */
.hero-mesh {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, .04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, .04) 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: radial-gradient(120% 90% at 30% 0%, #000 20%, transparent 78%);
  -webkit-mask-image: radial-gradient(120% 90% at 30% 0%, #000 20%, transparent 78%);
  pointer-events: none;
}
.hero-in {
  position: relative;
  z-index: 1;
  max-width: 1180px;
  margin: 0 auto;
  padding: 34px var(--s5) 54px;
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(300px, .85fr);
  gap: 48px;
  align-items: end;
}
.crumb {
  font-size: var(--fs-xs);
  color: rgba(244, 241, 234, .5);
  margin-bottom: 34px;
}
.crumb a {
  color: rgba(244, 241, 234, .5);
  text-decoration: none;
  transition: color .2s;
}
.crumb a:hover { color: var(--bz-gold-2); }
.crumb .sp {
  margin: 0 6px;
  font-style: normal;
}
.hero-kicker {
  margin: 0 0 14px;
  font-size: 12px;
  letter-spacing: .26em;
  color: var(--bz-gold);
}
.hero-title {
  margin: 0;
  font-size: clamp(42px, 6vw, 68px);
  font-weight: 600;
  line-height: 1.04;
  letter-spacing: .05em;
  color: var(--bz-cream);
}
.hero-lede {
  max-width: 34em;
  margin: 20px 0 0;
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: rgba(244, 241, 234, .62);
}
.hero-rule {
  display: block;
  width: 72px;
  height: 1px;
  margin-top: 30px;
  background: linear-gradient(90deg, var(--bz-gold), rgba(201, 162, 90, 0));
}

/* 毛玻璃说明卡：半透明底 + 14px 模糊 + 内高光；它压在同一块金色辉光上才「像玻璃」。 */
.hero-glass {
  position: relative;
  padding: 22px 22px 18px;
  border-radius: 16px;
  background: linear-gradient(145deg, rgba(255, 255, 255, .09), rgba(255, 255, 255, .035));
  border: 1px solid rgba(255, 255, 255, .14);
  box-shadow: 0 24px 60px -30px rgba(0, 0, 0, .9), inset 0 1px 0 rgba(255, 255, 255, .16);
  backdrop-filter: blur(14px) saturate(115%);
  -webkit-backdrop-filter: blur(14px) saturate(115%);
}
.glass-hd {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--bz-line);
}
.glass-seal {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--bz-gold-2), #a97f3c);
  color: #1a1409;
  font-size: 15px;
  font-weight: 700;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .45);
}
.glass-title {
  font-size: 13px;
  letter-spacing: .14em;
  color: rgba(244, 241, 234, .8);
}
.glass-list { margin: 6px 0 0; }
.glass-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 18px;
  padding: 10px 0;
  border-bottom: 1px solid rgba(255, 255, 255, .06);
}
.glass-row:last-child { border-bottom: 0; padding-bottom: 2px; }
.glass-row dt {
  font-size: 12px;
  letter-spacing: .08em;
  color: rgba(244, 241, 234, .45);
}
.glass-row dd {
  margin: 0;
  font-size: 13px;
  color: var(--bz-gold-2);
  letter-spacing: .04em;
}

/* ── 内容区 ───────────────────────────────────────────────────────────────────── */
.wrap {
  max-width: 1180px;
  margin: 0 auto;
  padding: 40px var(--s5) var(--s8);
}
.sec-hd {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s4);
  margin-bottom: 18px;
}
.sec-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: .04em;
  color: var(--ink);
}
.sec-sub {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.showcase { margin-bottom: 52px; }

/* 左一占两行 + 右四枚小卡。 */
.show-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr) minmax(0, 1fr);
  grid-template-rows: 218px 218px;
  gap: var(--s4);
}
.show-card {
  position: relative;
  display: block;
  overflow: hidden;
  border-radius: 14px;
  background: #101113;
  color: var(--bz-cream);
  text-decoration: none;
  isolation: isolate;
  transition: box-shadow .45s var(--bz-ease), transform .45s var(--bz-ease);
}
.show-card::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 1;
  background: linear-gradient(180deg, rgba(8, 8, 9, .04) 32%, rgba(8, 8, 9, .8) 100%);
  transition: background .45s var(--bz-ease);
  pointer-events: none;
}
.show-card img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform .65s var(--bz-ease);
}
.show-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 30px 64px -28px rgba(10, 10, 12, .72);
}
.show-card:hover img { transform: scale(1.05); }
.show-card:hover::after {
  background: linear-gradient(180deg, rgba(8, 8, 9, 0) 18%, rgba(8, 8, 9, .88) 100%);
}
.show-card:focus-visible {
  outline: 2px solid var(--bz-gold);
  outline-offset: 3px;
}

.show-feature {
  grid-column: 1;
  grid-row: 1 / span 2;
}
.show-badge,
.mini-badge {
  position: absolute;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border-radius: 999px;
  background: rgba(12, 12, 14, .55);
  border: 1px solid rgba(232, 201, 138, .42);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: var(--bz-gold-2);
  max-width: calc(100% - 36px);
}
.show-badge {
  top: 18px;
  left: 18px;
  padding: 5px 14px 5px 5px;
  font-size: 12px;
}
.mini-badge {
  top: 10px;
  left: 10px;
  padding: 3px 10px 3px 3px;
  font-size: 11px;
}
.show-badge b,
.mini-badge b {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mono {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--bz-gold-2), #a97f3c);
  color: #1a1409;
  font-style: normal;
  font-size: 12px;
  font-weight: 700;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .45);
}
.mini-badge .mono { width: 20px; height: 20px; font-size: 10px; }

.show-copy {
  position: absolute;
  z-index: 2;
  left: 26px;
  right: 150px;
  bottom: 24px;
}
.show-kicker {
  display: block;
  margin-bottom: 8px;
  font-size: 11px;
  letter-spacing: .32em;
  color: var(--bz-gold);
}
.show-name {
  margin: 0 0 12px;
  font-size: clamp(21px, 2.2vw, 28px);
  font-weight: 600;
  line-height: 1.3;
  color: var(--bz-cream);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.show-price {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
  color: var(--bz-gold-2);
}
.show-cta {
  position: absolute;
  z-index: 2;
  right: 22px;
  bottom: 24px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(244, 241, 234, .1);
  border: 1px solid rgba(232, 201, 138, .38);
  color: var(--bz-gold-2);
  font-size: 12px;
  white-space: nowrap;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity .35s var(--bz-ease), transform .35s var(--bz-ease);
}
.show-cta i { font-style: normal; transition: transform .35s var(--bz-ease); }
.show-feature:hover .show-cta { opacity: 1; transform: none; }
.show-feature:hover .show-cta i { transform: translateX(2px); }

.mini-copy {
  position: absolute;
  z-index: 2;
  left: 14px;
  right: 14px;
  bottom: 13px;
}
.mini-name {
  display: -webkit-box;
  margin-bottom: 6px;
  font-size: 13px;
  line-height: 1.4;
  color: rgba(244, 241, 234, .92);
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.mini-price {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--bz-gold-2);
}

/* ── 货架：六列错落（跨 2 / 跨 3），不靠 dense 重排。 ─────────────────────────── */
.grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--s4);
}
.grid :deep(.pcard) {
  grid-column: span 2;
  transition: transform .4s var(--bz-ease), box-shadow .4s var(--bz-ease), border-color .3s;
}
.grid :deep(.pcard.wide) { grid-column: span 3; }
.grid :deep(.pcard-img) { overflow: hidden; }
.grid :deep(.pcard-img img) { transition: transform .6s var(--bz-ease); }
.grid :deep(.pcard:hover) {
  transform: translateY(-3px);
  border-color: rgba(201, 162, 90, .72);
  box-shadow: 0 24px 46px -26px rgba(20, 22, 24, .45);
}
.grid :deep(.pcard:hover .pcard-img) { background: #efe9dc; }
.grid :deep(.pcard:hover .pcard-img img) { transform: scale(1.045); }
/* 宽卡把图换成更扁的横幅比例，避免整卡被一张 4/3 大图撑得过高。 */
.grid :deep(.pcard.wide .pcard-img) { aspect-ratio: 21 / 9; }

/* ── 加载 / 空态 / 提示 ──────────────────────────────────────────────────────── */
.sk-shelf {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s4);
}
.sk-card {
  border: 1px solid var(--line);
  border-radius: var(--r2);
  overflow: hidden;
  background: var(--surface);
  animation: bz-pulse 1.8s ease-in-out infinite;
}
.sk-img { aspect-ratio: 4 / 3; background: var(--zone); }
.sk-line {
  height: 12px;
  margin: 14px 16px 0;
  border-radius: 4px;
  background: var(--zone);
}
.sk-line.short { width: 46%; margin-bottom: 16px; }
@keyframes bz-pulse { 50% { opacity: .55; } }

.empty {
  padding: 72px 0 56px;
  text-align: center;
}
.empty-mark {
  display: inline-grid;
  place-items: center;
  width: 64px;
  height: 64px;
  margin-bottom: 20px;
  border-radius: 18px;
  background: linear-gradient(135deg, #2b2418, #14110c);
  border: 1px solid rgba(201, 162, 90, .5);
  color: var(--bz-gold-2);
  font-size: 24px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .12);
}
.empty-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
}
.empty-sub {
  margin: 0 0 22px;
  color: var(--ink-3);
}
.empty-cta {
  display: inline-block;
  padding: 9px 22px;
  border-radius: 999px;
  background: var(--ink);
  color: var(--bg);
  font-size: var(--fs-sm);
  text-decoration: none;
}
.empty-cta:hover { background: var(--brand-ink); }

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

@media (max-width: 980px) {
  .hero-in {
    grid-template-columns: minmax(0, 1fr);
    gap: 30px;
    padding-bottom: 40px;
  }
  .hero-glass { max-width: 460px; }
  .show-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: 280px 190px 190px;
  }
  .show-feature { grid-column: 1 / -1; grid-row: 1; }
  .show-copy { right: 26px; }
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .grid :deep(.pcard),
  .grid :deep(.pcard.wide) { grid-column: span 1; }
  .sk-shelf { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 620px) {
  .show-grid { grid-template-columns: minmax(0, 1fr); grid-template-rows: 300px repeat(4, 200px); }
  .show-feature { grid-row: 1; }
  .grid { grid-template-columns: minmax(0, 1fr); }
  .sec-hd { align-items: flex-start; flex-direction: column; gap: 4px; }
}
@media (prefers-reduced-motion: reduce) {
  .show-card,
  .show-card img,
  .show-card::after,
  .show-cta,
  .show-cta i,
  .grid :deep(.pcard),
  .grid :deep(.pcard-img img) { transition: none; }
  .sk-card { animation: none; }
  .show-card:hover { transform: none; }
  .show-card:hover img,
  .grid :deep(.pcard:hover .pcard-img img) { transform: none; }
}
</style>
