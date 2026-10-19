<template>
  <div class="bz-page">
    <!-- 壳外公开页（`meta.public`），自带顶栏 —— 形制照 `SeckillPage.vue` / `ShopHome.vue`。
         ⚠️ 不放搜索框：本页没有「壳内搜索流」这条入口（判据同 SeckillPage 顶栏注释）。 -->
    <nav class="nav-bar">
      <button type="button" class="nav-back" @click="goBack">‹ 返回</button>
      <router-link class="brand" :to="homePath">商城</router-link>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div>
    </nav>

    <main class="wrap">
      <!-- A 版页头（BUYER / BRAND ZONE）：眉题 + 大标题 + 说明 + 右侧动作。
           动作全部落在既有地址上：入驻申请页 / 商城首页 / 秒杀日程。 -->
      <section class="page-head">
        <div>
          <div class="kicker">BUYER / BRAND ZONE</div>
          <h1>品牌专区</h1>
          <p>只陈列通过品牌加盟审核的商家在架商品；售完与下架即移出本页。</p>
        </div>
        <div class="head-actions">
          <router-link class="btn solid" :to="applyPath">{{ applyLabel }}</router-link>
          <router-link class="btn" :to="homePath">回商城首页</router-link>
          <router-link class="btn" to="/seckill">秒杀日程</router-link>
        </div>
      </section>

      <!-- 品牌说明（A 版 brand-hero）：左侧大字与说明，右侧品牌认证发丝线表。
           说明文案沿用改前头图里的口径；右侧四行是服务端真正在筛的东西。
           ⚠️ 放在三态链**之外**：加载 / 空 / 错误时这份专区说明仍要在（改前头图也是常驻）。 -->
      <section class="brand-hero" aria-label="专区说明">
        <div class="brand-copy">
          <div class="kicker">品牌认证 / BRAND CERTIFIED</div>
          <h2>平台直审，<br>只留通过审核的在架品牌。</h2>
          <p>只陈列通过品牌加盟审核的商家在架商品；售完与下架即移出本页。</p>
          <div class="bh-actions">
            <router-link class="btn solid" :to="applyPath">{{ applyLabel }}</router-link>
            <router-link class="btn" to="/seckill">秒杀日程</router-link>
          </div>
        </div>
        <dl class="cert">
          <div class="cert-line"><dt>入驻门槛</dt><dd>平台审核通过</dd></div>
          <div class="cert-line"><dt>在架口径</dt><dd>上架且未售完</dd></div>
          <div class="cert-line"><dt>会员权益</dt><dd>会员价同享</dd></div>
          <div class="cert-line"><dt>当前在架</dt><dd>{{ shelfCount }} 件</dd></div>
        </dl>
      </section>

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
        <!-- 品牌橱窗：左一占满两行的品牌大图卡，右侧四枚小卡。手机端整体堆叠。
             字段与改前一致：图 / 品牌首字 + 店名 / 商品名 / 价格 / 详情跳转。 -->
        <section v-if="showShowcase" class="showcase" aria-label="品牌精选">
          <div class="reco-head">
            <div>
              <div class="kicker">FEATURED / 精选</div>
              <h2 class="sec-title">本季精选</h2>
            </div>
            <span class="sec-sub">来自品牌加盟商家的在架单品</span>
          </div>
          <div class="show-grid">
            <router-link class="show-card show-feature" :to="linkOf(feature)">
              <div class="show-media">
                <img v-if="feature.GoodImg" :src="feature.GoodImg" :alt="feature.GoodName" loading="lazy" />
                <span class="show-badge"><i class="mono">{{ brandInitial(feature) }}</i><b>{{ brandName(feature) }}</b></span>
              </div>
              <div class="show-copy">
                <span class="show-kicker">精选</span>
                <h3 class="show-name">{{ feature.GoodName }}</h3>
                <div class="show-foot">
                  <span class="show-price">¥{{ fmt(feature.Price) }}</span>
                  <span class="show-cta">查看详情 <i aria-hidden="true">→</i></span>
                </div>
              </div>
            </router-link>

            <router-link v-for="g in minis" :key="g.Id || g.ProductCode" class="show-card show-mini" :to="linkOf(g)">
              <div class="show-media">
                <img v-if="g.GoodImg" :src="g.GoodImg" :alt="g.GoodName" loading="lazy" />
                <span class="mini-badge"><i class="mono">{{ brandInitial(g) }}</i><b>{{ brandName(g) }}</b></span>
              </div>
              <div class="mini-copy">
                <span class="mini-name">{{ g.GoodName }}</span>
                <span class="mini-price">¥{{ fmt(g.Price) }}</span>
              </div>
            </router-link>
          </div>
        </section>

        <!-- 货架：12 列错落（7 / 5 / 4 / 4 / 4 一轮 = 两行正好铺满），不靠 dense 重排，
             顺序仍是服务端的 `Id DESC`。商品卡本身（图 / 名 / 价 / 品牌标 / 加购 / 跳转）
             由 `ProductCard.vue` 原样提供，本页只覆写直角与错落跨度。 -->
        <section class="shelf" aria-label="全部品牌商品">
          <div class="shelf-bar">
            <div>
              <div class="kicker">ALL IN STOCK</div>
              <h2 class="sec-title">全部在架</h2>
            </div>
            <span class="brand-count">已加载 {{ shelfCount }} 件</span>
          </div>
          <div class="grid">
            <ProductCard v-for="(g, i) in list" :key="g.Id || g.ProductCode || i" :goods="g" :class="'w' + [7, 5, 4, 4, 4][i % 5]" />
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
// A 版线框复刻（2026-10-19）：页头 / brand-hero / 发丝线直角卡片 / 12 列错落货架。
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
// 纯展示 computed：两条入驻申请地址都是既有路由（客户侧 /customer/user/apply，
// 商家侧 /merchant/user/apply）。商家身份才带 `?type=brand` 直达品牌加盟表单。
const applyPath = computed(() => (homePath.value === '/merchant' ? '/merchant/user/apply?type=brand' : '/customer/user/apply'))
const applyLabel = computed(() => (homePath.value === '/merchant' ? '申请品牌加盟' : '申请商家入驻'))

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
   A 版线框：白纸 / 墨色 / 发丝线 / 直角。香槟金只留在「品牌徽章」这一件事上
   （品牌语义），不铺到正文与卡片底，避免把品牌专区洗成另一套页面主题。 */
.bz-page {
  --bz-gold-2: #e8c98a;
  --bz-ease: cubic-bezier(.22, .61, .36, 1);
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
  font-size: var(--fs-sm);
}

/* ── 顶栏：A 版白纸横条，与页面共用发丝线。 ─────────────────────────────────── */
.nav-bar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: var(--s4);
  height: 60px;
  padding: 0 var(--s7);
  background: rgba(255, 255, 255, .94);
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.nav-back {
  border: 0;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  font-size: var(--fs-sm);
  padding: 0 var(--s2);
  white-space: nowrap;
  transition: color .16s;
}
.nav-back:hover { color: var(--ink); }
.brand {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  letter-spacing: .02em;
  color: var(--ink);
  text-decoration: none;
  white-space: nowrap;
}
.nav-right {
  margin-left: auto;
  color: var(--ink-2);
  font-weight: var(--fw-med);
  cursor: pointer;
  white-space: nowrap;
  transition: color .16s;
}
.nav-right:hover { color: var(--ink); }
.nav-back:focus-visible,
.nav-right:focus-visible,
.brand:focus-visible {
  outline: 2px solid var(--ink);
  outline-offset: 3px;
}

/* ── A 版页头 ────────────────────────────────────────────────────────────────── */
.wrap {
  max-width: 1220px;
  margin: 0 auto;
  padding: 40px var(--s5) var(--s8);
}
.page-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 32px;
  align-items: end;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--ink);
  margin-bottom: 32px;
}
.kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .12em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.page-head h1 {
  margin: 10px 0 12px;
  font-family: var(--font-display);
  font-size: clamp(38px, 5vw, 48px);
  line-height: 1.02;
  font-weight: var(--fw-semi);
  letter-spacing: -.035em;
  color: var(--ink);
}
.page-head p {
  max-width: 700px;
  color: var(--ink-3);
  font-size: 14px;
  line-height: 1.7;
}
.head-actions {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
}
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  padding: 0 16px;
  border: 1px solid var(--ink);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink);
  font-size: 13px;
  line-height: 1.2;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color .12s linear, color .12s linear;
}
.btn:hover { background: var(--ink); color: var(--bg); }
.btn.solid { background: var(--ink); color: var(--bg); }
.btn.solid:hover { background: var(--surface); color: var(--ink); }
.btn:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }

/* ── brand-hero：左侧大字说明，右侧认证发丝线表。 ───────────────────────────── */
.brand-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(280px, .65fr);
  gap: 40px;
  align-items: end;
  padding: 34px 36px;
  border: 1px solid var(--line-2);
  background: var(--surface);
  margin-bottom: 28px;
}
.brand-hero h2 {
  margin: 12px 0 16px;
  font-family: var(--font-display);
  font-size: clamp(38px, 4.6vw, 58px);
  line-height: .98;
  font-weight: var(--fw-semi);
  letter-spacing: -.05em;
  color: var(--ink);
}
.brand-hero p {
  max-width: 640px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--ink-3);
}
.bh-actions {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
  margin-top: 18px;
}
.cert {
  margin: 0;
  padding-left: 26px;
  border-left: 1px solid var(--line);
}
.cert-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.cert-line:last-child { border-bottom: 0; }
.cert-line dt { color: var(--ink-3); }
.cert-line dd {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink);
  text-align: right;
}

/* ── 本季精选：左一占两行 + 右四枚小卡，图上一行、文字一行的直角卡。 ───────── */
.showcase { margin-bottom: 32px; }
.reco-head,
.shelf-bar {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--s4);
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 16px;
}
.sec-title {
  margin: 4px 0 0;
  font-family: var(--font-display);
  font-size: 24px;
  line-height: 1.15;
  font-weight: var(--fw-semi);
  letter-spacing: -.02em;
  color: var(--ink);
}
.sec-sub,
.brand-count {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
  white-space: nowrap;
}
.show-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr) minmax(0, 1fr);
  grid-template-rows: 240px 240px;
  gap: var(--s4);
}
.show-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--line-2);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink);
  text-decoration: none;
  transition: border-color .18s linear;
}
.show-card:hover { border-color: var(--ink); }
.show-card:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
.show-media {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: var(--img);
}
.show-media img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform .55s var(--bz-ease);
}
.show-card:hover .show-media img { transform: scale(1.04); }
.show-feature { grid-column: 1; grid-row: 1 / span 2; }
.show-badge,
.mini-badge {
  position: absolute;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: calc(100% - 20px);
  padding: 3px 8px 3px 3px;
  border: 1px solid rgba(201, 162, 90, .6);
  border-radius: 0;
  background: rgba(20, 17, 12, .9);
  color: var(--bz-gold-2);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: .04em;
}
.show-badge { top: 12px; left: 12px; }
.mini-badge { top: 8px; left: 8px; padding: 2px 6px 2px 2px; font-size: 8px; }
.show-badge b,
.mini-badge b {
  min-width: 0;
  overflow: hidden;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mono {
  display: grid;
  place-items: center;
  flex: none;
  width: 18px;
  height: 18px;
  background: linear-gradient(135deg, var(--bz-gold-2), #a97f3c);
  color: #1a1409;
  font-style: normal;
  font-size: 10px;
  font-weight: 700;
}
.mini-badge .mono { width: 14px; height: 14px; font-size: 8px; }
.show-copy {
  flex: none;
  padding: 16px 18px 18px;
  border-top: 1px solid var(--line-2);
}
.show-kicker {
  display: block;
  margin-bottom: 6px;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: .14em;
  color: var(--ink-3);
  text-transform: uppercase;
}
.show-name {
  display: -webkit-box;
  overflow: hidden;
  margin: 0 0 10px;
  font-family: var(--font-display);
  font-size: clamp(21px, 2.2vw, 28px);
  line-height: 1.2;
  font-weight: var(--fw-semi);
  letter-spacing: -.02em;
  color: var(--ink);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.show-foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s3);
}
.show-price {
  font-family: var(--font-mono);
  font-size: 17px;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.show-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding-bottom: 1px;
  border-bottom: 1px solid var(--ink);
  font-size: 11px;
  color: var(--ink);
  white-space: nowrap;
}
.show-cta i { font-style: normal; transition: transform .2s ease; }
.show-card:hover .show-cta i { transform: translateX(2px); }
.mini-copy {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 11px 12px 12px;
  border-top: 1px solid var(--line-2);
}
.mini-name {
  display: -webkit-box;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.4;
  color: var(--ink);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.mini-price {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}

/* ── 货架：12 列错落（7 / 5 / 4 / 4 / 4），直角 + 发丝线，不用 dense 重排。 ─── */
.shelf { margin-top: 8px; }
.grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: var(--s4);
}
.grid :deep(.pcard) {
  grid-column: span 4;
  border-color: var(--line-2);
  border-radius: 0;
  transition: transform .35s var(--bz-ease), border-color .18s linear, box-shadow .35s var(--bz-ease);
}
.grid :deep(.pcard.w7) { grid-column: span 7; }
.grid :deep(.pcard.w5) { grid-column: span 5; }
.grid :deep(.pcard:hover) {
  transform: translateY(-2px);
  border-color: var(--ink);
  box-shadow: 0 20px 38px -30px rgba(20, 22, 24, .5);
}
.grid :deep(.pcard-img) { overflow: hidden; }
.grid :deep(.pcard-img img) { transition: transform .55s var(--bz-ease); }
.grid :deep(.pcard:hover .pcard-img img) { transform: scale(1.04); }
/* 宽卡用固定图高，让同一行的 7 / 5 两枚卡在底部对齐（不靠 aspect-ratio 各算各的）。 */
.grid :deep(.pcard.w7 .pcard-img),
.grid :deep(.pcard.w5 .pcard-img) { height: 300px; }
.grid :deep(.pcard-name) {
  font-family: var(--font-display);
  font-size: 17px;
  line-height: 1.35;
  min-height: 2.7em;
}
.grid :deep(.pcard.w7 .pcard-name),
.grid :deep(.pcard.w5 .pcard-name) { font-size: 21px; }
.grid :deep(.pcard-kind),
.grid :deep(.pcard-tag) { border-radius: 0; }
.grid :deep(.pcard-buy) { border-radius: 0; }

/* ── 加载 / 空态 / 提示 ──────────────────────────────────────────────────────── */
.sk-shelf {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s4);
}
.sk-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--surface);
  animation: bz-pulse 1.8s ease-in-out infinite;
}
.sk-img { aspect-ratio: 4 / 3; background: var(--img); }
.sk-line {
  height: 12px;
  margin: 14px 16px 0;
  background: var(--surface-3);
}
.sk-line.short { width: 46%; margin-bottom: 16px; }
@keyframes bz-pulse { 50% { opacity: .55; } }

.empty {
  padding: 72px 24px 56px;
  border: 1px solid var(--line);
  background: var(--surface);
  text-align: center;
}
.empty-mark {
  display: inline-grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: 20px;
  border: 1px solid var(--ink);
  border-radius: 0;
  background: var(--ink);
  color: var(--bz-gold-2);
  font-family: var(--font-display);
  font-size: 22px;
}
.empty-title {
  margin: 0 0 8px;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.empty-sub {
  margin: 0 0 22px;
  color: var(--ink-3);
  font-size: 13px;
}
.empty-cta {
  display: inline-flex;
  align-items: center;
  height: 38px;
  padding: 0 20px;
  border: 1px solid var(--ink);
  border-radius: 0;
  background: var(--ink);
  color: var(--bg);
  font-size: 13px;
  text-decoration: none;
  transition: background-color .12s linear, color .12s linear;
}
.empty-cta:hover { background: var(--surface); color: var(--ink); }

.tip {
  padding: 20px 0;
  color: var(--ink-3);
}
.tip-a {
  border: 0;
  background: transparent;
  color: var(--ink);
  cursor: pointer;
  font: inherit;
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
  .nav-bar { padding: 0 var(--s5); }
  .page-head {
    grid-template-columns: minmax(0, 1fr);
    gap: 20px;
  }
  .head-actions { justify-content: flex-start; }
  .brand-hero {
    grid-template-columns: minmax(0, 1fr);
    gap: 26px;
    padding: 26px 24px;
  }
  .brand-hero h2 { font-size: clamp(34px, 6vw, 46px); }
  .cert {
    padding-left: 0;
    padding-top: 16px;
    border-left: 0;
    border-top: 1px solid var(--line);
  }
  .show-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: 300px 200px 200px;
  }
  .show-feature { grid-column: 1 / -1; grid-row: 1; }
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .grid :deep(.pcard),
  .grid :deep(.pcard.w7),
  .grid :deep(.pcard.w5) { grid-column: span 1; }
  .grid :deep(.pcard.w7 .pcard-img),
  .grid :deep(.pcard.w5 .pcard-img) { height: auto; aspect-ratio: 4 / 3; }
  .grid :deep(.pcard.w7 .pcard-name),
  .grid :deep(.pcard.w5 .pcard-name) { font-size: 17px; }
  .sk-shelf { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 620px) {
  .nav-bar { height: 54px; padding: 0 var(--s4); gap: var(--s2); }
  .wrap { padding: 24px var(--s4) var(--s7); }
  .page-head h1 { font-size: 36px; }
  .brand-hero { padding: 22px 18px; }
  .brand-hero h2 { font-size: 32px; }
  .show-grid {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: 320px repeat(4, 220px);
  }
  .show-feature { grid-row: 1; }
  .grid { grid-template-columns: minmax(0, 1fr); }
  .reco-head,
  .shelf-bar { align-items: flex-start; flex-direction: column; gap: 4px; }
}
@media (prefers-reduced-motion: reduce) {
  .show-card,
  .show-media img,
  .show-cta i,
  .grid :deep(.pcard),
  .grid :deep(.pcard-img img),
  .empty-cta { transition: none; }
  .sk-card { animation: none; }
  .show-card:hover .show-media img,
  .grid :deep(.pcard:hover .pcard-img img) { transform: none; }
  .grid :deep(.pcard:hover) { transform: none; }
}
</style>
