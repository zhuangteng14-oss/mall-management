/* eslint-disable */
<template>
  <div class="home-page">
    <!-- 原未登录黄条（.login-hint-bar）已删 —— 2026-10-06 改判 2（§5-AG (13)）：入口改由顶栏「登录 / 注册」承担；理由见 <script setup> 顶部 9 行 -->

    <!-- ── 搜索条 ──
         稿 `主页改造.html:728-734` 把搜索并进了顶栏 `.hdr`（稿 `:189-190` 自注「原来搜索独占 20vh
         垂直空间悬在中部，现在并进顶栏，省下整屏高度」）。但 `.hdr` 是**壳**的（`AppShell.vue`），
         而「壳内搜索笔」本笔 **不开** ⇒ 本笔保留页内搜索条，只把**样式**按稿的 `.search`
         （`主页改造.html:212-240`：带边框的整条容器 + 内嵌小胶囊按钮）落，不再用旧的
         「输入框拼按钮 + 20vh」。⚠️ 壳内搜索笔落地时，本块连同 .search-* 四条规则一并删除。 -->
    <div class="search-section">
      <div class="search-wrapper">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6">
          <circle cx="7" cy="7" r="4.6" /><path d="M10.5 10.5 14 14" stroke-linecap="round" />
        </svg>
        <input v-model="searchKeyword" type="text" class="search-input" placeholder="搜索商品、品牌、分类" />
        <button class="search-btn" @click="goSearch">搜索</button>
      </div>
    </div>

    <!-- ── 二级导航（稿 `:744-753`）──
         ⚠️ 壳里**没有** `.subnav`：`AppShell.vue:225-226` 自注「稿的 `.subnav a` 属**二级导航**、
         不属顶栏」⇒ C 笔既然把它从顶栏里摘出去了，就没人落过它 ⇒ 它是**页级**的，本笔落在首页顶部。
         ⚠️ 6 项在本仓**都没有落点**（无路由、无对应页面）⇒ 一律 `disabled` + `title`
         （`progress.md:1652` 判据①）。开放它们属**功能**笔，不在 D 笔边界内。
         登记见 `docs/agent/slot-registry.md`「commit 3 · 稿外死控件」。 -->
    <nav class="subnav">
      <div class="inner">
        <button type="button" class="now" disabled title="预留 · 全部商品分类（无落点：分类检索现走搜索页 GoodsSelect 的左分类树）">全部商品分类</button>
        <button type="button" class="hot" disabled title="预留 · 今日秒杀（无落点：本页秒杀位是预留位）">今日秒杀</button>
        <button type="button" class="hot" disabled title="预留 · 领券中心（无落点：本页券包位是预留位）">领券中心</button>
        <button type="button" disabled title="预留 · 品牌专区（无落点：无品牌数据模型）">品牌专区</button>
        <button type="button" disabled title="预留 · 会员中心（无落点：会员体系未落）">会员中心</button>
        <button type="button" disabled title="预留 · 客户服务（无落点：在线客服是预留位）">客户服务</button>
      </div>
    </nav>

    <div class="wrap">
      <!-- ── 英雄区：分类栏 + 主视觉（稿 `:758-784`）── -->
      <section class="hero">
        <aside class="cats">
          <div class="cats-h">全部分类</div>
          <div v-if="goodTypeList.length === 0" class="empty-tip">暂无分类</div>
          <ul v-else>
            <li v-for="item in goodTypeList" :key="item.Id" @click="searchByType(item.GoodType)">{{ item.GoodType }}</li>
          </ul>
          <!-- ⚠️ 稿 `:769` 的「查看全部 →」在稿里就是**死控件**（`cursor:pointer`、无处理函数、
               也没挂 `data-slot`）⇒ 照判据①落 `disabled` + `title`。**不**自作主张接
               `emit('go-search','')` —— 「无关键词进搜索页」是稿没画的行为，属功能笔。 -->
          <button type="button" class="cats-more" disabled title="预留 · 查看全部分类（无落点：分类检索现走搜索页 GoodsSelect 的左分类树）">查看全部 →</button>
        </aside>

        <div class="hero-main">
          <!-- 稿这里是一块「釉」底 + **主图占位**（`:772-781`：`轮播位 / 主图占位 / 建议 1600×640`）。
               本仓有真图（`homeImgList`）⇒ 保留 `<img>`（不为了照稿丢真功能），稿那三行**设计说明**
               性质的占位文案不落，空态改出「暂无商品」。
               ⚠️ `.glaze` 本体**不裁剪**（稿 `:340-341` 明令不写 `overflow:hidden`）⇒ 裁图另开一层
               `.glaze-media`；`.glaze-frame` / `.glaze-mark` 在 DOM 里排在它**后面** —— 同为绝对
               定位、`z-index:auto`，靠后即压在上层 ⇒ 换成真图后「框和竖排标记还留着」（稿 `:337`）。 -->
          <div class="glaze">
            <div v-if="homeImgList.length > 0" class="glaze-media">
              <img
                v-for="(img, index) in homeImgList"
                :key="index"
                :src="img.GoodImg"
                :class="{ on: index === currentIndex }"
                alt="商品主图"
              />
            </div>
            <span class="glaze-frame"></span>
            <span class="glaze-mark">天青</span>
            <div v-if="homeImgList.length === 0" class="glaze-ct">
              <p class="glaze-b">暂无商品</p>
            </div>
          </div>
          <!-- ⚠️ 指示点在稿里是**装饰**（`:782` 三个静态 `<i>`，不可点）；本仓它本来就可点
               （`goToSlide`）⇒ 保留事件、只按稿落样式（未选中 6px 圆点 / 选中拉成 20px 胶囊），
               并补 `cursor:pointer`（稿的装饰点不需要，本仓的可点元素需要）。 -->
          <div v-if="homeImgList.length > 0" class="dots">
            <i
              v-for="(item, index) in homeImgList"
              :key="index"
              :class="{ on: index === currentIndex }"
              @click="goToSlide(index)"
            ></i>
          </div>
        </div>
      </section>

      <!-- ── 功能入口（预留位，稿 `:787-804`）──
           2026-10-07 裁决「**一律落**」：UI 照落。内层四项一律 `disabled` + `title`（角标只挂
           区块级 ⇒ 不在每项重复 `data-slot`）。四个 SVG 逐字取稿。
           ⚠️ 稿的副标题带三个 **mock 数字**（待收货 2 / 3 张可用 / 最近 18 件）—— 照落会在真产品里
              显示假数字 ⇒ 只落**描述**、不落数字（与 ChatRoom 不写死「今天」同一条理由）；
              「在线客服 9:00 – 21:00」是服务时段陈述、不是 mock ⇒ 逐字落。 -->
      <section class="quick" data-slot="预留 · 功能入口" title="预留 · 功能入口（老稿 主页改造.html:787）· 归属待定 C3〈待定〉">
        <button type="button" class="qi" disabled title="预留 · 我的订单（待 C3〈订单〉）">
          <span class="ic"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="2" width="14" height="16" rx="2"/><path d="M7 7h6M7 11h6M7 15h3" stroke-linecap="round"/></svg></span>
          <span class="tx"><b>我的订单</b><span>待付款 · 待收货</span></span>
        </button>
        <button type="button" class="qi" disabled title="预留 · 优惠券（待 C3〈促销〉）">
          <span class="ic"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z"/><path d="M2 7l8 5 8-5"/></svg></span>
          <span class="tx"><b>优惠券</b><span>我的券包</span></span>
        </button>
        <button type="button" class="qi" disabled title="预留 · 浏览记录（待 C3〈个人中心〉）">
          <span class="ic"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="10" cy="10" r="7.5"/><path d="M10 6v4.5l3 1.8" stroke-linecap="round"/></svg></span>
          <span class="tx"><b>浏览记录</b><span>最近看过的商品</span></span>
        </button>
        <button type="button" class="qi" disabled title="预留 · 在线客服（待 C3〈消息〉）">
          <span class="ic"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M17 12.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 12.5v-6A1.5 1.5 0 0 1 4.5 5h11A1.5 1.5 0 0 1 17 6.5z"/><path d="M6.5 5v10" stroke-linecap="round"/></svg></span>
          <span class="tx"><b>在线客服</b><span>9:00 – 21:00</span></span>
        </button>
      </section>

      <!-- ── 限时秒杀（预留位，稿 `:807-814`）──
           ⚠️ 倒计时**不写死**：稿 `:966` 自己就注明了「真接进项目时应该用接口给的活动结束时间，
              **不要在这里写死**」⇒ 三格落成 `--`，说明写进本区块的 `title`。
           ⚠️ `.flash-g` 是稿的 JS 挂载点（`:813`，稿 markup 里就是**空 div**），本仓无活动接口
              ⇒ 保持空容器 + 一行占位说明（空容器高度为 0，会「落了却看不见」）。 -->
      <section class="flash" data-slot="预留 · 秒杀位" title="预留 · 秒杀位（老稿 主页改造.html:807）· 无活动接口，倒计时不写死（稿 :966 自注）· 归属待定 C3〈待定〉">
        <div class="flash-h">
          <h2>限时秒杀</h2>
          <span class="cd">距结束 <b>--</b>:<b>--</b>:<b>--</b></span>
          <button type="button" class="more" disabled title="预留 · 全部秒杀（无落点：秒杀位是预留位）">全部秒杀 →</button>
        </div>
        <div class="flash-g">
          <p class="empty-tip">秒杀位预留 · 待接入活动接口后填充</p>
        </div>
      </section>

      <!-- ── 券包（预留位，稿 `:817`）──
           稿的 markup 里这条**本来就是空 section**，3 张券由 JS 塞（`TICKETS`）⇒ 券是 mock、
           `.ticket*` 规则本仓无数据源、不搬。空容器照落 + 一行占位说明。 -->
      <section class="tickets" data-slot="预留 · 券包位" title="预留 · 券包位（老稿 主页改造.html:817）· 无券数据源 · 归属待定 C3〈待定〉">
        <p class="empty-tip">券包位预留 · 待接入优惠券接口后填充</p>
      </section>

      <!-- ── 推荐区（稿 `:820-826`）──
           真实数据 `goodsList`（`goodsSelectHome`）。卡结构照稿（`.card > .card-img + .card-tx >
           .card-name + .card-foot > .card-price + .card-buy`）。
           ⚠️ 稿的 `.card-tags`（标签）与 `.card-price .was`（划线价）本仓**无对应字段**
              ⇒ 不落结构、也不搬这两条规则（搬进来就是悬空类名）。
           ⚠️ `.card-meta` 稿写的是「店铺 · 已售」（`CARD` 的 `g.shop · g.sold`），本仓无店铺 / 销量
              字段 ⇒ 改落**真实存在**的 `goods.GoodType`（分类），不编数据。 -->
      <section class="rec">
        <div class="rec-h">
          <h2>为你推荐</h2>
          <button type="button" class="more" disabled title="预留 · 查看全部商品（无落点：商品检索现走搜索页 GoodsSelect）">查看全部</button>
        </div>
        <p v-if="goodsList.length === 0" class="empty-tip">暂无商品</p>
        <div v-else class="grid">
          <article v-for="goods in goodsList" :key="goods.Id" class="card" @click="toGoodsDetail(goods)">
            <div class="card-img">
              <img :src="goods.GoodImg" alt="商品图" />
            </div>
            <div class="card-tx">
              <div class="card-name">{{ goods.GoodName }}</div>
              <div class="card-foot">
                <div class="card-price">
                  <span class="sym">¥</span>
                  <span class="int">{{ getPriceInt(goods.Price) }}</span>
                  <span class="dec">.{{ getPriceDec(goods.Price) }}</span>
                </div>
                <!-- ⚠️ 稿的 `.card-buy` 是加购按钮（稿 `:917`，带 `CART_SVG`）。本仓本页的卡片**只进详情**
                     （`toGoodsDetail`），加购要等「完整购物车」笔 ⇒ 落 `disabled` + `title`。
                     ⚠️ 不挂 `data-slot`：稿没给这一枚 `data-slot`，挂了会让「Vue 里 data-slot 条数」
                     与「稿里 4 条」对不上；它登记在 slot-registry.md「稿外死控件」一节。 -->
                <button type="button" class="card-buy" disabled title="预留 · 加入购物车（待 C3〈购物车〉；本页卡片现只进详情页）">
                  <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 5h10l-1 8.4a1 1 0 0 1-1 .9H5a1 1 0 0 1-1-.9z" stroke-linejoin="round"/><path d="M6 5V3.9a2 2 0 1 1 4 0V5" stroke-linecap="round"/></svg>
                </button>
              </div>
              <div class="card-meta">{{ goods.GoodType }}</div>
            </div>
          </article>
        </div>
      </section>

      <!-- ── 信任条（预留位，稿 `:829-846`）──
           4 项都是静态承诺文案（无可点元素 ⇒ 无需 disabled），整块落 `data-slot` + `title`。 -->
      <section class="trust" data-slot="预留 · 信任位" title="预留 · 信任位（老稿 主页改造.html:829）· 4 项为静态承诺文案 · 归属待定 C3〈待定〉">
        <div>
          <span class="ic"><svg width="19" height="19" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M10 2.2 3.5 5v4.6c0 3.9 2.7 7.4 6.5 8.2 3.8-.8 6.5-4.3 6.5-8.2V5z" stroke-linejoin="round"/><path d="m7.2 9.9 2 2 3.6-3.9" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
          <span><b>正品保障</b><span>厂家直供，假一赔十</span></span>
        </div>
        <div>
          <span class="ic"><svg width="19" height="19" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3.5 6.5A2 2 0 0 1 5.5 4.5h6a2 2 0 0 1 2 2v1.5" stroke-linecap="round"/><path d="M13.5 8h2l1.5 2.6v2.4a1 1 0 0 1-1 1h-1" stroke-linejoin="round"/><circle cx="7" cy="14.5" r="1.6"/><circle cx="13.5" cy="14.5" r="1.6"/></svg></span>
          <span><b>次日达</b><span>当日 18 点前下单</span></span>
        </div>
        <div>
          <span class="ic"><svg width="19" height="19" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 10a6 6 0 1 0 1.9-4.4" stroke-linecap="round"/><path d="M4 3.6V6h2.4" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
          <span><b>七天无理由</b><span>不影响二次销售</span></span>
        </div>
        <div>
          <span class="ic"><svg width="19" height="19" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="5" width="15" height="10" rx="1.6"/><path d="M2.5 9h15" stroke-linecap="round"/></svg></span>
          <span><b>极速退款</b><span>审核通过秒到账</span></span>
        </div>
      </section>
    </div>

    <!-- ── 页脚：稿 `:850-857` 把原来那条 10vh 亮青横条收成安静的细线区 ── -->
    <footer class="home-foot">
      <div class="inner">
        <div class="pts">
          <span>轻松购物</span>
          <span>极速配送</span>
          <span>精致服务</span>
          <span>畅选无忧</span>
        </div>
        <span>超市管理平台 · 商城</span>
      </div>
    </footer>

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
/* ⚠️ D 笔（2026-10-07）：全块按 `docs/美化/主页改造.html` 的 `<style>` 重写。
   类名一律取稿名（`wrap` / `hero` / `cats` / `glaze` / `dots` / `quick` / `flash` /
   `tickets` / `rec` / `grid` / `card` / `trust` / `subnav` / `pts` …），旧名（`search` 以外的
   `banner-section` / `good-type-list` / `type-item` / `space` / `swiper-box` / `swiper-container` /
   `swiper-img` / `goods-list-section` / `goods-grid` / `goods-card` / `goods-img-box` /
   `goods-info` / `goods-name` / `goods-price` / `price-symbol` / `price-int` / `price-dec` /
   `footer-section` / `footer-content`）全部退役。
   ⚠️ 稿里属于**壳**的三块**不进本文件**：`.bar`（稿自己的预览条 `:165-187`）、`.hdr`（顶栏，
   C 笔已落进 `AppShell.vue`）、`.nav`（顶栏右侧导航，同属壳）。
   ⚠️ 稿的 `body.slots [data-slot]` 虚线框 / `::after` 角标规则（`:697-709`）**不进 Vue** ——
   那是稿「显示预留位」按钮的预览产物（同 slot-registry.md 口径 1）。
   ⚠️ 稿里**没有元素用到**的规则一律不搬，否则落进来就是悬空类名：
   `.promo`（`:435-453`，稿自己也没用在 body 里）、`.card-tags` / `.card-price .was`（本仓无标签 /
   划线价字段）、`.fl-*`（`:609-627`）与 `.ticket*`（`:633-658`）与 `.glaze-k` / `.glaze-s`
   （`:370-389`）—— 后三组对应的都是稿 JS 里的 mock 内容，本仓无数据源。
   ⚠️ 稿本页**没有 @media**（全文件 0 处）⇒ 本块也不加断点：`.grid` 用 auto-fill 自适应，
   其余栅格（4/4/3/4 列）在窄屏会挤但不会破版，与稿一致。 */

/* 基础重置：稿的**全局** `button` / `input` 规则搬进 scoped（只作用本组件的元素）。
   ⚠️ 本页把稿的 `<a>` 落成 `<button>`（死控件必须 `disabled` —— 裸 `<a>` 无 href 既不可聚焦
   也不可禁用）⇒ 下面凡是稿的 `.x a` 选择器，逐条对应到 `.x button` 或具名类。 */
button,
input {
  font: inherit;
  color: inherit;
}
button {
  background: none;
  border: 0;
  cursor: pointer;
}

/* 本页根：稿的 `body{background:var(--bg);color:var(--ink);font-size:var(--fs-sm);line-height:1.6}`
   四条落到根上。`flex:1` + `min-width:0` —— 在客户 / 商家壳里本组件是页面容器的直接 flex 子项。 */
.home-page {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

/* ── 搜索条：稿的 `.search`（`:213-240`），位置仍在**页内**（壳内搜索笔未开）────── */
.search-section {
  padding: var(--s5) var(--s6);
}
.search-wrapper {
  max-width: 760px;
  margin: 0 auto;
  height: 40px;
  display: flex;
  align-items: center;
  gap: var(--s2);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: 0 4px 0 14px;
  transition: border-color 0.15s, background 0.15s;
}
.search-wrapper:focus-within {
  border-color: var(--brand);
  background: var(--surface);
}
.search-wrapper svg {
  flex: none;
  color: var(--ink-3);
}
.search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
}
.search-input::placeholder {
  color: var(--ink-3);
}
.search-btn {
  flex: none;
  height: 32px;
  padding: 0 18px;
  border-radius: var(--r1);
  background: var(--brand);
  color: #fff;
  font-size: 13px;
  font-weight: var(--fw-med);
  transition: background 0.15s;
}
.search-btn:hover {
  background: var(--brand-hover);
}

/* ── 二级导航：稿 `.subnav`（`:558-569`），三态（`now` / `hot`）照落 ── */
.subnav {
  background: var(--nav-bg);
}
.subnav .inner {
  max-width: 1400px;
  margin: 0 auto;
  padding: 0 var(--s6);
  height: 46px;
  display: flex;
  align-items: center;
  gap: var(--s6);
}
.subnav button {
  font-size: 13.5px;
  color: var(--nav-tx);
  transition: color 0.15s;
  white-space: nowrap;
}
.subnav button:hover {
  color: var(--nav-tx-hi);
}
.subnav button.now {
  color: var(--nav-now);
  font-weight: var(--fw-semi);
}
.subnav button.hot {
  color: var(--nav-hot);
  font-weight: var(--fw-med);
}
/* 六项都无落点（disabled）⇒ 鼠标形态要说实话，别留着 `pointer` 骗人。 */
.subnav button[disabled] {
  cursor: not-allowed;
}

/* ── 主区：稿 `.wrap`（`:265`）──
   ⚠️ `width:100%` **不能省**：本页根是 flex 列，`.wrap` 是它的 **flex 子项** —— 子项一旦有
   横向 `auto` 外边距，`align-items:stretch` 就被取消、盒宽退化成 fit-content（实测只剩
   ~640px，栅格全挤在一起）。稿那边 `.wrap` 处在普通块流里，没这个问题。 */
.wrap {
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  padding: var(--s5) var(--s6) 0;
}

/* ── 英雄区：分类栏 + 主视觉（稿 `:277-285`）──
   底色放在 `.hero` 上而不是左右各一份 —— 中间那道 32px 间距也落在同一块底上。 */
.hero {
  display: grid;
  grid-template-columns: 196px 1fr;
  gap: var(--s6);
  min-height: 368px;
  background: var(--zone);
  border-radius: var(--r2);
  padding: var(--s5);
}

/* 分类栏做成一条「目录」而不是卡片盒子：上下发丝线 + 逐项分隔线。 */
.cats {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--line-2);
}
.cats-h {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  letter-spacing: 1.4px;
  color: var(--ink-3);
  padding: var(--s3) 0 var(--s4);
}
.cats ul {
  list-style: none;
  display: flex;
  flex-direction: column;
}
.cats li {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  transition: color 0.14s;
  /* ⚠️ 刻意不用左边竖条 —— 那是 AI 生成界面最容易被认出的特征（稿 `:311` 原注）。 */
}
.cats li:last-child {
  border-bottom: none;
}
.cats li:hover {
  color: var(--brand-ink);
}
/* 稿的 `.cats-more` 是 `<div>`；本仓落成 `<button disabled>` ⇒ 补 `text-align:left`
   （按钮默认居中），并去掉 `cursor:pointer`（`:315-324` 那条随 `disabled` 一起失效）。 */
.cats-more {
  margin-top: auto;
  text-align: left;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  padding: var(--s3) 0 var(--s4);
  border-top: 1px solid var(--line-2);
  transition: color 0.15s;
}
.cats-more:hover {
  color: var(--brand-ink);
}
.cats-more[disabled] {
  cursor: not-allowed;
}

/* ── 主视觉：不是灰底占位，是一块「釉」（稿 `:342-351`）──
   指示点挂在 `.hero-main` 上而不是 `.glaze` 里（稿 `:326-328`：带 `overflow:hidden` 的裁剪
   容器里塞绝对定位子元素会被检测器报 clipped-overflow-container）。 */
.hero-main {
  position: relative;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.glaze {
  flex: 1;
  min-height: 0;
  position: relative;
  border-radius: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(130% 120% at 6% 4%, var(--glaze-deep) 0%, rgba(255, 255, 255, 0) 58%),
    radial-gradient(120% 130% at 98% 100%, var(--glaze-deep) 0%, rgba(255, 255, 255, 0) 55%),
    linear-gradient(168deg, var(--zone) 0%, var(--glaze-pale) 100%);
  /* ⚠️ `overflow:hidden` 有意不写（稿 `:340-341`）：圆角由 background 自己裁。 */
}
/* 裁图层 —— 本仓新加的一层（稿那块是**空占位**，没有 `<img>`，所以稿不需要它）。
   绝对定位 + 排在 `.glaze-frame` / `.glaze-mark` **之前**，靠后即压在上层。 */
.glaze-media {
  position: absolute;
  inset: 0;
  overflow: hidden;
  border-radius: 2px;
}
.glaze-media img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.6s ease;
}
.glaze-media img.on {
  opacity: 1;
}
.glaze-frame {
  position: absolute;
  inset: 14px;
  border: 1px solid rgba(0, 0, 0, 0.07);
  pointer-events: none;
}
/* 竖排小字。`--brand-hover` 而非 `--brand`：它在左侧釉色最浓处只有 4.4:1（稿 `:366` 原注）。 */
.glaze-mark {
  position: absolute;
  left: 30px;
  top: 50%;
  transform: translateY(-50%);
  writing-mode: vertical-rl;
  font-family: var(--font-display);
  font-size: 15px;
  letter-spacing: 0.34em;
  color: var(--brand-hover);
}
.glaze-ct {
  text-align: center;
}
.glaze-b {
  font-family: var(--font-display);
  font-size: 40px;
  font-weight: var(--fw-med);
  letter-spacing: 0.12em;
  line-height: 1.25;
  color: var(--brand-hover);
}
.dots {
  position: absolute;
  left: 30px;
  bottom: 26px;
  display: flex;
  gap: 5px;
  z-index: 2;
}
/* ⚠️ **不** `transition width` —— 那是布局属性，会触发 layout thrash（稿 `:394-395`）。
   指示点这种小东西，宽度直接跳，只过渡颜色。
   ⚠️ 底色 `rgba(28,31,30,.24)` 是照稿（稿 `:398`：釉底是浅色，浅灰点在上面等于没有）。
   本仓镜头换成真图后，深色图上这枚暗点会偏弱 —— 但改颜色就是**改稿的取色**，
   不属 D 笔能自裁的范畴，原样落、在此登记。 */
.dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(28, 31, 30, 0.24);
  transition: background 0.25s;
  /* 稿的指示点是装饰（`:782`），本仓可点（`goToSlide`）⇒ 补 `cursor`。 */
  cursor: pointer;
}
.dots i.on {
  width: 20px;
  border-radius: 3px;
  background: var(--brand);
}

/* ── 功能入口条（预留位，稿 `:408-430`）── */
.quick {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin-top: var(--s6);
  background: var(--zone);
  border-radius: var(--r2);
  /* 有底色就必须补内边距：没有它，「我的订单」的图标会顶到框上（稿 `:414-416`）。 */
  padding: 0 var(--s5);
}
/* 稿的选择器是 `.quick a`；本仓的「项」是**死控件** ⇒ 必须是 `<button class="qi">`。
   `.qi` 是本笔新加的类名，只为让 `<button>` 拿到稿给 `<a>` 的那套盒模型。 */
.quick .qi {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s5) var(--s4);
  text-align: left;
  border-right: 1px solid var(--line);
}
.quick .qi:first-child {
  padding-left: 0;
}
.quick .qi:last-child {
  border-right: none;
  padding-right: 0;
}
.quick .ic {
  color: var(--ink-3);
  flex: none;
  display: flex;
  transition: color 0.15s;
}
.quick .qi:hover .ic {
  color: var(--brand);
}
.quick .tx b {
  display: block;
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.quick .tx span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.quick .qi[disabled] {
  cursor: not-allowed;
}

/* ── 限时秒杀（预留位，稿 `:578-627`）──
   全页唯一的暖底：它表示时间压力，不是装饰（稿 `:571-576`）。 */
.flash {
  margin-top: var(--s6);
  background: var(--cin-100); /* 页底调浅后 cin-50 与它同明度，粉带会消失 ⇒ 压深一档（稿 `:580`）。 */
  border: 1px solid var(--cin-200);
  border-radius: var(--r2); /* 稿 `:582`：立着的面统一用 --r2，四角才有同一套圆度。 */
  padding: var(--s5) var(--s5) var(--s6);
}
.flash-h {
  display: flex;
  align-items: baseline;
  gap: var(--s4);
  padding-bottom: var(--s4);
  margin-bottom: var(--s5);
  border-bottom: 1px solid var(--cin-200);
}
.flash-h h2 {
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--cin-700);
  letter-spacing: 1.5px;
}
.flash-h .cd {
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
/* ⚠️ 稿写死 `ui-monospace, Consolas, monospace`（`:597`）；本仓同义量是 `--font-mono`
   （链条已含 `ui-monospace … Consolas, monospace`）⇒ 用变量，值等价。 */
.flash-h .cd b {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: var(--fw-semi);
  color: #fff;
  background: var(--cin-600);
  padding: 2px 7px;
  border-radius: var(--r1);
  margin: 0 1px;
  letter-spacing: 0.5px;
}
.flash-h .more {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--cin-700);
}
.flash-h .more:hover {
  text-decoration: underline;
}
.flash-g {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--s5);
}
/* 空容器（本仓无活动接口）里那行说明要铺满整栅格，不然它只占第 1 列。 */
.flash-g .empty-tip {
  grid-column: 1 / -1;
}

/* ── 券包（预留位，稿 `:632-658`）──
   ⚠️ 稿的 markup 里这条**本来就是空 section**（`:817`），3 张券由 JS 塞 mock ⇒ 本仓 0 条
   `.ticket*` 规则（无数据源）。空 `display:grid` 高度为 0 ⇒ 会「落了却看不见」⇒ 出一行说明。 */
.tickets {
  margin-top: var(--s5);
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--s4);
}
.tickets .empty-tip {
  grid-column: 1 / -1;
}

/* ── 推荐区（稿 `:455-548`）── */
.rec {
  margin-top: var(--s7);
}
.rec-h {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line-2);
  margin-bottom: var(--s5);
}
.rec-h h2 {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  letter-spacing: 0.5px;
}
/* 稿是 `.rec-h a`；本仓是 `<button class="more">`。 */
.rec-h .more {
  font-size: var(--fs-xs);
  color: var(--brand-ink);
}
.rec-h .more:hover {
  text-decoration: underline;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: var(--s4);
}

/* 商品卡：容器感来自**明度台阶**，不来自圆角和阴影（稿 `:477-484`）。
   页底 / 卡片白面 / 图占位 = 0 / 13.8% / 25.5% 三级。 */
.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.16s;
}
.card:hover {
  border-color: var(--brand);
}
.card-img {
  aspect-ratio: 4 / 3;
  background: var(--img);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.card:hover .card-img {
  background: var(--brand-soft);
}
/* 稿的 `.card-img` 是**空图框**（稿 JS 里只有「商品图」三个字）；本仓有真图 ⇒ 保留 `<img>`，
   与 GoodsSelect `.thumb` / ChatRoom `.pc .th` 同一处理（不为了照稿丢真功能）。 */
.card-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.card-tx {
  padding: var(--s3) var(--s4) var(--s4);
}
.card-name {
  font-size: var(--fs-sm);
  font-weight: var(--fw-reg);
  color: var(--ink);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.9em;
}
.card-foot {
  margin-top: var(--s2);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
}
.card-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  color: var(--accent);
}
.card-price .sym {
  font-size: 13px;
  font-weight: var(--fw-med);
}
.card-price .int {
  font-size: 22px;
  font-weight: var(--fw-semi);
  letter-spacing: -0.5px;
}
.card-price .dec {
  font-size: 14px;
  font-weight: var(--fw-med);
}
/* 加购：平时幽灵圆钮，**卡片 hover** 才转实心（稿 `:536-547`：12 个实心按钮会把货架变成按钮墙）。
   ⚠️ 稿还有一条 `.card-buy:hover`（`:545`），本仓这枚恒 `disabled` ⇒ 那条不搬，只留卡片级那条。 */
.card-buy {
  flex: none;
  width: 30px;
  height: 30px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: var(--ink-3);
  border: 1px solid var(--line-2);
  border-radius: 50%;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.card:hover .card-buy {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.card-buy[disabled] {
  cursor: not-allowed;
}
.card-meta {
  margin-top: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* ── 信任条（预留位，稿 `:663-678`）── */
.trust {
  margin-top: var(--s8);
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.trust > div {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s5) var(--s4);
  border-right: 1px solid var(--line);
}
.trust > div:first-child {
  padding-left: 0;
}
.trust > div:last-child {
  border-right: none;
}
.trust .ic {
  color: var(--brand);
  flex: none;
  display: flex;
}
.trust b {
  display: block;
  font-size: 13.5px;
  font-weight: var(--fw-med);
  color: var(--ink);
}
.trust span {
  font-size: 12px;
  color: var(--ink-3);
}

/* ── 页脚：稿 `:681-692` 把原来那条 10vh 亮青横条收成安静的细线区 ──
   ⚠️ `margin-top` 由稿的 `var(--s8)` 改成 `auto`：本页根是 flex 列，页短时把页脚压到底
   （与改前 `.footer-section{margin-top:auto}` 同义）；页长时 `auto` 退化为 0，
   间隔由 `.trust` 自己的 `--s8` 承担 —— 两种情况下视觉与稿一致。 */
.home-foot {
  margin-top: auto;
  border-top: 1px solid var(--line);
  padding: var(--s6) 0 var(--s7);
}
.home-foot .inner {
  max-width: 1400px;
  margin: 0 auto;
  padding: 0 var(--s6);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.home-foot .pts {
  display: flex;
  gap: var(--s6);
}
.home-foot .pts span {
  letter-spacing: 0.5px;
}

.empty-tip {
  color: var(--ink-3);
}
</style>
