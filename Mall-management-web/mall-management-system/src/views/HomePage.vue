/* eslint-disable */
<template>
  <div class="home-page">
    <!-- 原未登录黄条（.login-hint-bar）已删 —— 2026-10-06 改判 2（§5-AG (13)）：入口改由顶栏「登录 / 注册」承担；理由见 <script setup> 顶部 9 行 -->

    <!-- ── 搜索条：**已随用户 2026-10-08 第 1 条移进顶栏** ──────────────────────────
         用户口径：顶栏只留**一行** = 商城主页 | 搜索框 | 登录/注册（或登录后的三项）。
         搜索框新的家在 `AppShell.vue` 的 `.nav-search`（`<form>` + `@submit.prevent`
         → `${base}/search?kw=`，与壳里既有的 `handlePendingSearch` 同路径、同 query 键）。
         本页从此**没有**搜索输入框：`searchKeyword`（`:242`）与 `goSearch`（`:262`）
         两个符号随之作废，各自原处留了按语。原块 `:6-20` 引的两处基线（`主页改造.html:189-190` / `主页改造.html:212-240`）随块删除，**留档于此以免丢引**（第 5 条的「可回退」口径）。 -->
    <div class="head-sticky">
      <!-- ⚠️ 本 div **留着** —— 它现在只包下面那行分类导航（`nav.subnav`）；原 `.search-section`
           那一层已随搜索条一起删除。
           ⚠️ 用户给的口径是「分类导航是另一行，可跟顶栏一起 sticky，也可独立，你判」⇒ 判
           **独立**：顶栏（`top:0` / `z-index:10`）与它（`top:60px` / `z-index:9`）本来就是
           两层，合并不改代码、也改不了这层关系；且它吸附的包含块是 `.home-page`（内容高
           1881），与壳同效。
           ⚠️ 原搜索条占的高度**不补**：用户要的是「顶栏那一行多一个搜索框」，不是挪位置。
           ⚠️ 收口：本 div 现在只有一个子元素（`nav.subnav`），闭合于 `:37` 的 `</nav></div>`。 -->

    <!-- ── 二级导航（稿 `:744-753`）──
         ⚠️ 壳里**没有** `.subnav`：`AppShell.vue:298-299` 自注「稿的 `.subnav a` 属**二级导航**、
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
    </nav></div>

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
               定位、`z-index:auto`，靠后即压在上层 ⇒ 换成真图后「框和竖排标记还留着」（稿 `:337`）。 ⚠️ **2026-10-08 用户报 bug 后重判**：上面这条判据在**本仓不成立** —— 稿的 `.glaze` 是**空占位**（釉印压自己的底），本仓 `.glaze-media` 有真图 ⇒ 釉印恒压**图面**。故 `.glaze-mark`（竖排「天青」）**已删**（模板那行现为墓碑注释，`.glaze-mark` 的 CSS 规则一并删除），只留 `.glaze-frame`。 -->
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
            <!-- ⚠️ **稿 `:775` 的竖排「天青」釉印本行原样落过，2026-10-08 用户报 bug 后删除**：稿那块是**空占位**（没有 `<img>`），釉印压在自己的底上没问题；本仓 `.glaze-media` 有**真图**且 `.glaze-mark` 排在其后（绝对定位、`z-index:auto`）⇒ 恒压在**图面**上。取证：`美化/probe-glaze.mjs before` 实测 `.glaze-mark` 文本 =「天青」、色 `rgb(39,94,78)`、盒 `[327,294,24,40]` 落在 `.glaze` `[297,154,1060,320]` 之内，而同行 `.glaze-media img` 已 `opacity:1`。⇒ 保留 `.glaze-frame`（细框，稿 `:337` 明示「框和指示点会留着」），只删这一枚印。 -->
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
// ⚠️ 原 `const searchKeyword = ref('')` 已删（用户 2026-10-08 第 1 条）：页内搜索条搬进壳顶栏（`AppShell.vue` 的 `.nav-search`）。本行留作按语**占住 `:242`** —— 模板块 `:10` 等多处互引指这一行，删行会让其后引用整体上移。

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

// ⚠️ 原 `const goSearch = () => { … emit('go-search', searchKeyword.value) }`（7 行）已删 ——
//   用户 2026-10-08 第 1 条：页内搜索条搬进壳顶栏（`AppShell.vue` 的 `.nav-search`，
//   落点 `${base}/search?kw=`）。本页从此没有搜索输入框 ⇒ 本函数没有调用方。
//   ⚠️ **`emit('go-search')` 这条通路本身没死**：下面的 `searchByType` 仍在用（首页分类块
//      点一下就它）⇒ `HomeTab.vue` 的 `@go-search` 适配器、`defineEmits(['go-search'])` 都保留。
//      删掉的只是「输入框 → 这个函数」那一头。
//   本段留作按语、占住原来的 7 行 —— 理由同 `:242`（全仓 `文件:行号` 互引，删行会让其后整体上移）。

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

/* ── 搜索条（**已删**，用户 2026-10-08 第 1 条）─────────────────────────────────────
   本处原有 4 条规则 —— `.search-section` / `.search-wrapper` / `.search-input` / `.search-btn`
   （含 `:focus-within` 与 `::placeholder` 两个变体，合计 49 行，即原 `:391-439`）。
   它们随**页内搜索条**一起删除：用户裁决「顶栏只留**一行**」，搜索框搬进壳顶栏。

   ▸ **搜索框的新家**：`AppShell.vue` 样式块末尾的 `.nav-search` / `.search-input` / `.search-btn`。
     数值来源 = `ProductPage.vue` 顶栏**已有的那一组**（用户指定「参考商品详情页的顶栏结构，
     那个是对的」）⇒ 壳里那个不是新画的形制。
   ▸ **落点**：`<form class="nav-search" @submit.prevent>` → `${base}/search?kw=` —— 与壳里
     既有的 `handlePendingSearch`（`AppShell.vue` 消费 `PENDING_SEARCH_KEYWORD` 那一段）
     **同一条路径、同一个 query 键** ⇒ 两条入口不会分歧。
   ▸ **本页随之作废的两个符号**：`searchKeyword`（原 `:242`）与 `goSearch`（原 `:262`），
     各自原处已换成按语 —— 那两处**不是**「忘了删」，理由见末段。

   ⚠️ **为什么整块删，不用 `display:none` 藏起来**：留一个隐藏输入框 = 留一个绑着 `v-model`、
     却永远读不到的 `ref`；再加上壳顶栏里那个真框，下一个人的第一个问题必然是「两个搜索框
     哪个生效」。删除是唯一自洽的收口。

   ⚠️ **原 4 条规则的形状**（存档，供回退时逐条还原）：
     · `.search-section` —— 外层容器，`padding: var(--s5) var(--s6)`。
     · `.search-wrapper` —— 760px 居中、`height:40px`、`display:flex`、`gap:var(--s2)`、
       `border:1px solid var(--line)`、`border-radius:var(--r2)`、左内距 14px 给图标让位，
       另加一条 `transition: border-color/background .15s` 与一个 `:focus-within` 变体。
     · `.search-wrapper svg` —— 图标，`flex:none` + `color:var(--ink-3)`。
     · `.search-input` —— `flex:1` / `min-width:0` / 无边框 / 透明底，`::placeholder` 用 `--ink-3`。
     · `.search-btn` —— `height:32px`、右内距 18px、`--brand` 实底、`--r1` 圆角、13px 字，
       外加一个 `:hover` 换 `--brand-hover`。
     全仓 grep 过：这 4 个类名**只被本页这一块**用到（`ProductPage.vue` / `LoginPage.vue` 里的
     同名字符串在各自 `scoped` 样式内，互不相干）⇒ 随块删除**不留孤儿规则**。

   ⚠️ **`.head-sticky` 不受影响**：它是包裹层，去掉 `.search-section` 后只包 `nav.subnav`
     那一行；`top:60px` / `z-index:9` / `background:var(--bg)` 三条**一字未动**，它自己那条
     注释也照旧成立（吸附的包含块是 `.home-page` 的内容高，不是 `.home-container` 的定高盒）。

   ⚠️ **高度不补**：原搜索条让本页顶部多出约 88px 垂直空间。用户要的是「顶栏那一行多一个
     搜索框」，不是把搜索挪个位置 ⇒ 不补占位、hero 随笔上移。页面比改前短一截，这是**预期**。

   ⚠️ **回退方法**（用户口径：收口目标「可复用、可回退」）：① 删掉 `AppShell.vue` 模板里那行
     `<form class="nav-search">` 与样式块末尾的三条规则；② 把本文件 `:6-20` 的按语换回
     `<div class="head-sticky"><div class="search-section">` + `.search-wrapper` 结构；
     ③ 本块按上面那段的描述写回 4 条规则。三处都在本笔 diff 里，逐块可认。

   ⚠️ **为什么用注释占掉这几十行，而不是让文件短一截**：本仓全篇用「`文件:行号`」互引，
     本文件的入站引用最远到 `:986`（`scripts/refs-scan.sh` 扫得到）。删行会让其后**每一条**
     引用整体上移 ⇒ 本块按**等行数**换成注释，是本仓既有的做法（同笔的 `searchKeyword` /
     `goSearch` 两处按语同理）。

   ⚠️ 原文（存档）：「稿 `主页改造.html:728-734` 把搜索并进了顶栏 `.hdr` ……而『壳内搜索笔』
     本笔 **不开** ⇒ 本笔保留页内搜索条」—— 那句「不开」指的就是**现在这一笔**。 */

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
/* ⚠️ `.glaze-mark`（竖排「天青」釉印）**2026-10-08 删除** —— 见模板里那行墓碑注释：稿的印是给空占位用的，
   本仓有真图 ⇒ 它恒压图面（用户报的就是这一枚）。原规则 11 行（`position:absolute; left:30px; top:50%;
   translateY(-50%); writing-mode:vertical-rl; --font-display; 15px; letter-spacing .34em; --brand-hover`）随之作废，
   不是漏删。回退 = 复原该规则 + 模板里那把 `<span>`。 */
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

/* ── 吸顶带（A2 2026-10-07；用户 2026-10-08 第 1 条后只剩一行）──────────────────────
   治的病（用户实测 图4）：搜索栏随页面滚走。诉求 = **滚到底也一直贴在顶栏下面**。
   A2 的形态 = 用户给定的「搜索栏 + 分类导航包一层 sticky 容器（`top` = 顶栏高 60px）」。
   ⚠️ 本笔改动（用户 2026-10-08 第 1 条）：搜索框整个搬进**壳顶栏**（`AppShell.vue` 的
   `.nav-search`）⇒ 本容器现在只包 `.subnav`（`:28`）**一行**，原 `:12` 的 `.search-section`
   已删（连同它的 4 条样式，见样式块那段按语）。本容器自己的三条数值**一字未动**。
   ⚠️ 新增行只能落在最大被引行之后：全仓引用本页的最大行号是 `HomePage.vue:597`（`slot-registry.md:829` / `:2096`）⇒ 本规则落在
   `<style>` 尾部。`top: 60px` **必须**与壳顶栏高锁步：
   `AppShell.vue:287-288` 的 `height: 60px`（同处 `:241-243` 记着「高度必须与 `.home-container`
   的 `top`/`height` 锁步」的既有口径）。`z-index: 9` **故意**低于顶栏的 `10`（`AppShell.vue`
   的 `.top-nav`，同处 `:225-226`）：吸顶带从顶栏**下面**穿过去，写 ≥10 就把「页」压在「壳」上。
   ⚠️ **顶栏自己是另一条 sticky**，它靠**壳能长高**才全程有效 —— 本笔之前 `.home-container`
   是 `absolute` ⇒ 壳恒 900px ⇒ 首页滚过 840px 顶栏就释放；本笔已把它改回流内（见
   `AppShell.vue` 那条 `.home-container` 按语）。⚠️ `background: var(--bg)` **不能省**：吸附时
   本带是**盖**在商品区上的，透明会让商品从字缝里透出来（`--bg` 与 `--surface` 同值 `#ffffff`，
   见 `assets/tokens.css:83-84`）。⚠️ 吸附的上下限 = **包含块**（`.home-page`，内容高且无
   `overflow` ⇒ 全页有效；哪天给它加上 `overflow:hidden`，本带会在 840px **提前释放**），不是 `.home-container` 的盒（`AppShell.vue:430-440`）。 */
.head-sticky {
  position: sticky;
  top: 60px;
  z-index: 9;
  background: var(--bg);
}
</style>
