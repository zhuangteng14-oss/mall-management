/* eslint-disable */
<template>
  <div class="home-page">
    <!-- ══════════════════════════════════════════════════════════════════════
         商城主页 · A 版公域前台（2026-10-19 全量重写）
         ──────────────────────────────────────────────────────────────────────
         旧结构「左侧全高分类栏 + 中间 Banner + 右侧推荐位」已整体废弃。新结构：
           ① 全宽沉浸主视觉：轮播满幅铺开，文案叠在图上（左下），编号 + 指示条在右下；
           ② 分类 / 快捷入口：悬浮在主视觉底部的毛玻璃条（分类 chip + 5 个快捷入口）；
           ③ 限时秒杀：整块墨底，左侧大号倒计时，右侧横向商品卡（带真实已抢进度条）；
           ④ 领券中心：撕纸券卡（左面额 / 右门槛，中间虚线 + 上下缺口）；
           ⑤ 为你推荐：12 栏错落网格，按 6-3-3 / 4-4-4 / 6-3-3 的节奏每 9 件循环一次。
         功能一个不少：分类跳转、查看全部、秒杀全部、领券（单张 / 一键 / 展开）、
         商品卡、无限滚动、信任条、页脚，全部保留原来的数据与事件。
         ⚠️ 搜索框仍在壳顶栏（`AppShell.vue` 的 `.nav-search`），本页没有页内搜索框。 -->
    <!-- ── ① 主视觉 ─────────────────────────────────────────────────────── -->
    <section class="stage" aria-label="本期主推">
      <div v-if="homeImgList.length" class="stage-media">
        <router-link
          v-for="(img, index) in homeImgList"
          :key="index"
          :to="productLink(img)"
          class="stage-slide"
          :class="{ on: index === currentIndex }"
          :aria-hidden="index === currentIndex ? null : 'true'"
          @click="onCardNav"
        >
          <img :src="img.GoodImg" :alt="img.GoodName || '商品主图'" />
        </router-link>
      </div>
      <div class="stage-veil" aria-hidden="true"></div>

      <div v-if="homeImgList.length === 0" class="stage-empty">
        <span class="stage-empty-mark">MALL</span>
        <p>商品正在上架，稍后再来逛逛。</p>
      </div>

      <div class="stage-inner">
        <div v-if="featured" class="stage-copy">
          <p class="stage-kicker">
            <span>FEATURED</span><i></i><span>本期主推</span>
          </p>
          <h1 class="stage-title">{{ featured.GoodName }}</h1>
          <p class="stage-meta">
            <span v-if="featured.GoodType" class="stage-cat">{{ featured.GoodType }}</span>
            <span class="stage-price"><i>¥</i>{{ flashMoney(featured.Price) }}</span>
            <span v-if="featured.UserName" class="stage-shop">{{ featured.UserName }}</span>
          </p>
          <router-link class="stage-cta" :to="productLink(featured)" @click="onCardNav">
            <span>查看商品</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h15M13 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </router-link>
        </div>

        <div v-if="homeImgList.length > 1" class="stage-count" aria-hidden="true">
          <b>{{ pad2(currentIndex + 1) }}</b><span>/ {{ pad2(homeImgList.length) }}</span>
        </div>
        <div v-if="homeImgList.length > 1" class="stage-dots">
          <button
            v-for="(item, index) in homeImgList"
            :key="index"
            type="button"
            :class="{ on: index === currentIndex }"
            :aria-label="'切换到第 ' + (index + 1) + ' 张主图'"
            @click="goToSlide(index)"
          ></button>
        </div>
      </div>

      <!-- ② 毛玻璃分类 / 快捷入口：悬浮在主视觉下沿，向下压住下一屏 -->
      <div class="stage-dock">
        <div class="dock-cats" aria-label="商品分类">
          <button type="button" class="dock-all" @click="searchByType('')" title="全部商品分类 —— 进入搜索页的分类树">
            <span class="dock-mono">全</span><span>全部商品分类</span>
          </button>
          <button
            v-for="item in goodTypeList"
            :key="item.Id"
            type="button"
            class="dock-cat"
            :title="'按「' + item.GoodType + '」浏览'"
            @click="searchByType(item.GoodType)"
          >
            <span class="dock-mono">{{ firstChar(item.GoodType) }}</span><span>{{ item.GoodType }}</span>
          </button>
        </div>
        <span class="dock-div" aria-hidden="true"></span>
        <nav class="dock-links" aria-label="快捷入口">
          <router-link class="dock-link" to="/seckill" title="今日秒杀 —— 落到秒杀页">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M13 2 4.5 13.5H10L9 22l8.5-11.5H12z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/></svg><span>今日秒杀</span>
          </router-link>
          <button type="button" class="dock-link" title="领券中心 —— 锚到本页领券中心" @click="scrollToTickets">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 8h18v3a2.5 2.5 0 0 0 0 5v3H3v-3a2.5 2.5 0 0 0 0-5z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M15 8v11" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2 3"/></svg><span>领券中心</span>
          </button>
          <router-link class="dock-link" to="/brand" title="品牌专区 —— 只列品牌加盟商家的商品">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="9" r="5" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="m8.5 13-1.5 8 5-2.6 5 2.6-1.5-8" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/></svg><span>品牌专区</span>
          </router-link>
          <router-link class="dock-link" to="/customer/user/member" title="会员中心 —— 9 折 / 每日专属券 / 开通与余额（需登录）">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 8.5 7 12l5-7 5 7 4-3.5-2 10.5H5z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/></svg><span>会员中心</span>
          </router-link>
          <router-link class="dock-link" to="/coming-soon/service" title="客户服务 —— 敬请期待">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 13v-1a8 8 0 0 1 16 0v1" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><rect x="3" y="12.5" width="4" height="6" rx="1.4" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="17" y="12.5" width="4" height="6" rx="1.4" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M19 18.5v.5a2.5 2.5 0 0 1-2.5 2.5H13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg><span>客户服务</span>
          </router-link>
        </nav>
      </div>
    </section>

    <div class="wrap">
      <!-- ── ③ 限时秒杀（墨底 / 大倒计时 / 横向卡片 / 真实已抢进度）──────────────
           ⚠️ 只在登录后拉到活动时画；`flashLoaded` 区分「没登录」与「登录了但 0 场」。
           倒计时仍读后端 `EndAt`（UTC 文本补 `Z`），进度条读 `SoldStock / TotalStock`，
           `TotalStock = 0` 时不画百分比（不拿假数据凑进度）。 -->
      <section
        v-if="flashRows.length"
        class="sec flash"
        title="C3 块 5〈营销块〉：读 /marketing/seckill/live · 后端已挡「已结束 / 已停用 / 商品下架」· Stage 由后端按库时钟现算"
      >
        <div class="flash-side">
          <p class="sec-kicker sec-kicker-light">01 / FLASH SALE</p>
          <h2 class="flash-title">限时秒杀</h2>
          <p class="flash-sub">进行中的场次 · {{ flashRows.length }} 件好物</p>
          <p class="flash-clock-label">{{ flashCdLabel }}</p>
          <div class="flash-clock" aria-live="off">
            <span class="clock-cell"><b>{{ flashCd.h }}</b><i>HRS</i></span>
            <span class="clock-colon">:</span>
            <span class="clock-cell"><b>{{ flashCd.m }}</b><i>MIN</i></span>
            <span class="clock-colon">:</span>
            <span class="clock-cell"><b>{{ flashCd.s }}</b><i>SEC</i></span>
          </div>
          <router-link class="flash-all" to="/seckill">
            <span>查看全部场次</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h15M13 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </router-link>
        </div>
        <div class="flash-rail">
          <router-link v-for="r in flashRows" :key="r.ActivityId" class="flash-card" :to="flashLink(r)" @click="onCardNav">
            <div class="flash-pic">
              <img v-if="r.GoodImg" :src="r.GoodImg" :alt="r.Name || r.GoodName" />
              <span v-else class="flash-pic-none">暂无图片</span>
            </div>
            <div class="flash-body">
              <div class="fc-head">
                <span class="fc-nm">{{ r.Name || r.GoodName }}</span>
                <span v-if="r.Stage === 'live'" class="fc-flag">抢购中</span>
                <span v-else class="fc-flag fc-flag-soon">未开始</span>
              </div>
              <span v-if="r.Stage === 'live'" class="fc-pr"><i>¥</i>{{ flashMoney(r.SeckillPrice) }}</span>
              <span v-else class="fc-soon">活动未开启</span>
              <div v-if="soldPct(r) !== null" class="fc-bar" :aria-label="'已抢 ' + soldPct(r) + '%'">
                <i :style="{ width: soldPct(r) + '%' }"></i>
              </div>
              <span v-if="soldPct(r) !== null" class="fc-pct">
                已抢 {{ soldPct(r) }}%<em v-if="soldLeft(r) !== null && soldLeft(r) > 0"> · 仅剩 {{ soldLeft(r) }} 件</em>
              </span>
              <span v-else class="fc-pct">限量发售</span>
            </div>
          </router-link>
        </div>
      </section>

      <!-- 秒杀整块空态：登录了、也问过后端了，就是一场都没有 -->
      <section
        v-else-if="flashLoaded"
        class="sec flash-flat"
        title="C3 块 5〈营销块〉：登录后读到 0 场进行中 / 即将开始的秒杀"
      >
        <div class="flash-empty">
          <svg viewBox="0 0 48 48" aria-hidden="true"><circle cx="24" cy="24" r="17" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M24 14v10l7 4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square"/></svg>
          <div class="flash-empty-tx">
            <b>暂无进行中的秒杀</b>
            <p>有新场次会在这里出现；也可以先看看完整日程。</p>
          </div>
          <router-link class="flash-all flash-all-dark" to="/seckill">查看全部场次<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h15M13 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></router-link>
        </div>
      </section>

      <!-- ── ④ 领券中心（撕纸券卡）─────────────────────────────────────────────
           ⚠️ 「本周新券」不是另一条查询：同一个 SELECT + AND（后端 CouponDao.THIS_WEEK）。
           ⚠️ 排序由后端 `updated_at ASC` 定好，本页按数组原序渲染。
           ⚠️ `Claimed` / `SoldOut` / `IsNew` 可能是 true 也可能是 1 ⇒ 判据走 `truthy()`。
           ⚠️ `TK_VISIBLE` / flex 基宽 `/5.3` / 渐隐 88px 三个数是一组，改一处要成对改。 -->
      <section
        class="sec tickets"
        ref="ticketsEl"
        title="领券中心：/marketing/coupons?thisWeek=1 · 领券 /marketing/coupon/claim · 一键领取 /marketing/coupon/claimBatch。我的券包在用户中心「优惠券」弹窗"
      >
        <header class="sec-head">
          <div>
            <p class="sec-kicker">02 / COUPONS</p>
            <h2 class="sec-title">领券中心</h2>
          </div>
          <div class="tk-h-r">
            <span v-if="couponAccount && daysLeft !== null" class="tk-left">本周还剩 {{ daysLeft }} 天</span>
            <span class="tk-n">{{ couponAccount ? claimable.length + ' 张' : '未登录' }}</span>
            <button
              v-if="couponAccount"
              type="button"
              class="tk-all"
              :disabled="batchBusy || tkClaimable === 0"
              :title="tkClaimable === 0 ? '本周没有可领的新券' : '一键领取本周新发布、且我还没领过的券（' + tkClaimable + ' 张）'"
              @click="doClaimBatch"
            >{{ batchBusy ? '领取中…' : '一键领取本周全部' }}</button>
            <button
              v-if="couponAccount && claimable.length > TK_VISIBLE"
              type="button"
              class="tk-more"
              :class="{ 'tk-more-on': tkExpanded }"
              :title="tkExpanded ? '收起（只留前 ' + TK_VISIBLE + ' 张）' : '展开看全部 ' + claimable.length + ' 张'"
              @click="tkExpanded = !tkExpanded"
            >{{ tkExpanded ? '《' : '》' }}</button>
          </div>
        </header>

        <p v-if="!couponAccount" class="empty-tip">登录后可领取优惠券</p>
        <p v-else-if="claimLoading" class="empty-tip">加载中…</p>
        <div v-else-if="claimable.length === 0" class="tk-empty">
          <svg viewBox="0 0 48 48" aria-hidden="true"><path d="M7 15h34v6a3 3 0 000 6v6H7v-6a3 3 0 000-6z" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M20 15v18" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2 3"/></svg>
          <div><b>本周暂无新券</b><span>去用户中心的「优惠券 · 可领」看全部可领券。</span></div>
        </div>
        <ul
          v-else
          class="tk-strip"
          :class="{ 'tk-strip-cut': claimable.length > TK_VISIBLE && !tkExpanded, 'tk-strip-on': tkExpanded }"
        >
          <li v-for="c in claimable" :key="c.CouponId" class="tk">
            <div class="tk-l">
              <span class="tk-top">
                <span class="tk-amt">{{ couponFace(c) }}</span>
                <i v-if="truthy(c.IsNew)" class="tk-new">NEW</i>
              </span>
              <span class="tk-name">{{ couponName(c) }}</span>
            </div>
            <div class="tk-r">
              <span class="tk-cond">{{ couponLine(c) }}</span>
              <button
                type="button"
                class="tk-get"
                :disabled="truthy(c.Claimed) || truthy(c.SoldOut) || claiming === c.CouponId"
                @click="doClaim(c)"
              >{{ truthy(c.Claimed) ? '已领取' : truthy(c.SoldOut) ? '已领完' : claiming === c.CouponId ? '领取中…' : '立即领取' }}</button>
            </div>
          </li>
        </ul>

        <p v-if="tkMsg" class="tk-msg">{{ tkMsg }}</p>
      </section>

      <!-- ── ⑤ 为你推荐（12 栏错落网格）────────────────────────────────────────
           ⚠️ 卡片仍是公共组件 `components/ProductCard.vue`（7 个 query 参数契约、
              导航前副作用走 `onCardNav`、加购逻辑都在组件里）。
           ⚠️ 错落只改**父级栅格**：6-3-3 / 4-4-4 / 6-3-3 每 9 件一循环。 -->
      <section class="sec rec">
        <header class="sec-head">
          <div>
            <p class="sec-kicker">03 / FOR YOU</p>
            <h2 class="sec-title">为你推荐</h2>
          </div>
          <button type="button" class="sec-more" @click="searchByType('')" title="查看全部商品 —— 进入搜索页">
            <span>查看全部商品</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h15M13 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </header>
        <p v-if="homeErrMsg" class="empty-tip">
          商品加载失败：{{ homeErrMsg }}
          <button type="button" class="more" @click="retryHome">重试</button>
        </p>
        <p v-else-if="goodsList.length === 0 && !homeLoading" class="empty-tip">暂无商品</p>
        <div v-else class="grid">
          <ProductCard v-for="goods in goodsList" :key="goods.Id" :goods="goods" :before-nav="onCardNav" />
        </div>
        <!-- 无限滚动哨兵：留在文档流里，`useInfiniteList` 靠 IntersectionObserver 观察它 -->
        <div
          v-if="goodsList.length"
          ref="homeSentinel"
          class="rec-more"
          :class="{ 'rec-more--loading': homeLoading, 'rec-more--done': homeDone }"
          @click="!homeDone && retryHome"
        >
          <span v-if="homeLoading">正在加载更多…</span>
          <span v-else-if="homeErrMsg">加载失败，滚动或点击重试</span>
          <span v-else-if="homeDone">已经到底了</span>
          <span v-else>下拉加载更多</span>
        </div>
      </section>

      <!-- ── 信任条（4 项静态承诺文案）────────────────────────────────────── -->
      <section class="trust">
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

    <!-- ── 页脚 ──────────────────────────────────────────────────────────── -->
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
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useInfiniteList } from '../composables/useInfiniteList'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'; import { getUsername } from '../api/auth'; import { coupons, claim, claimBatch, seckillLive } from '../api/marketing'; import { truthy, couponFace, couponName, couponLine } from '../config/coupon-format'; import ProductCard from '../components/ProductCard.vue'; import { sharedList } from '../config/goods-flags'; import { productLink } from '../config/product-link'   // ⚠️ 本行保持**单行**（本文件多处在文档里按行号被引，能省一行就省一行；同 `ProductPage.vue` 的做法）

// ── 未登录可逛公开面（2026-10-06 用户改判 2）──────────────────────────────────
// 本页没有登录墙：搜索 / 商品详情都是公开面，匿名入口统一走顶栏「登录 / 注册」。
// 购买、消息、用户中心、购物车仍由受保护路由与 navFor 拦截。

// eslint-disable-next-line no-undef
const emit = defineEmits(['go-search'])

const goodTypeList = ref([])
const homeImgList = ref([])
const goodsList = ref([])
const currentIndex = ref(0)

let timer = null

// 首页「为你推荐」：真分页 + 无限滚动（每次 24 条）。
// 轮播图只取第一页的前 5 张 —— 它是图的轮换、不是商品列表。
const HOME_PAGE_SIZE = 24
const homeFeed = useInfiniteList(async (offset, limit) => {
  const res = await http.post(endpoints.goods.goodsSelectHome,
    new URLSearchParams({ limit: String(limit), offset: String(offset) }))
  const data = await res.json()
  if (!data.success) throw new Error(data.msg || '请求失败')
  const rows = data.list || []
  if (offset === 0) homeImgList.value = rows.slice(0, 5)
  return { rows }
}, HOME_PAGE_SIZE)
const homeSentinel = homeFeed.sentinel
const homeLoading = homeFeed.loading
const homeDone = homeFeed.done
const homeErrMsg = homeFeed.errMsg
const retryHome = homeFeed.retry
watch(homeFeed.items, (rows) => {
  // 共享面那一份 = 去重 + 滤已售完（判据唯一定义处：`config/goods-flags.js`）。
  // 每追加一页都对累积列表重算一次：同码两行可能被分到两页。
  goodsList.value = sharedList(rows)
})

const getHomeImgAndGoods = async () => {
  await homeFeed.loadNext()
  if (homeFeed.errMsg.value) {
    handleApiError(new Error(homeFeed.errMsg.value), '请求失败')
  }
}

// 根据类型搜索（未登录放行：落在公开的 `/<role>/search`）
const searchByType = (typeName) => {
  emit('go-search', typeName)
}

const getGoodType = async () => {
  try {
    const res = await http.post(endpoints.goods.goodstype)
    const data = await res.json()
    if (data.success) {
      // 只取一级类目（`ParentId` 缺省 / 0）；过滤收在数据源这一行。
      goodTypeList.value = data.list.filter(t => (t.ParentId || 0) === 0)
    }
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

// 主页进入商品页的**本页专属副作用**（导航本身由 `ProductCard` 的 `<router-link>` 完成）：
//   · 清 `SEARCH_KEYWORD` —— 从主页点进商品意味着「搜索」那一段重新开始；
//   · 写 `FROM_PAGE=home` —— 全仓只写不读，但属于既有账，照旧。
const onCardNav = () => {
  sessionStorage.setItem('FROM_PAGE', 'home')
  sessionStorage.removeItem('SEARCH_KEYWORD')
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

// ── 主视觉的展示辅助 ─────────────────────────────────────────────────────────
/** 当前轮播那一件商品 —— 文案叠层与「查看商品」都读它。 */
const featured = computed(() => homeImgList.value[currentIndex.value] || null)
const pad2 = (n) => String(n).padStart(2, '0')
/** 分类 chip 的小方字：取类目名第一个字（空名兜底成一个点，不画空白方块）。 */
const firstChar = (s) => {
  const t = String(s == null ? '' : s).trim()
  return t ? t.slice(0, 1) : '·'
}

// ══════════════════════════════════════════════════════════════════════════════
// 领券中心（C3 块 5）
//
// ⚠️ 身份取 `getUsername()`（`api/auth.js` 是唯一允许读身份的地方）：券的账号口径是
//    **登录名**，不是客户业务号（真库 13 个商家 `customerid` 全是 NULL）。
//    没有身份就不取数 —— 这不是登录墙：页面照常逛，只是券区显示「登录后可…」。
const couponAccount = ref('')
const claimable = ref([])
const claimLoading = ref(false)
const claiming = ref('')
const tkMsg = ref('')
const ticketsEl = ref(null)
/** 「本周还剩 N 天」—— 来自响应体 `daysLeft`（后端库时钟算）。`null` = 还没取到。 */
const daysLeft = ref(null)
/** 「一键领取本周全部」进行中（防连点）。 */
const batchBusy = ref(false)

const loadCoupons = async (account) => {
  claimLoading.value = true
  try {
    // `thisWeek = '1'` ⇒ 本周新发布的券；limit 10 是为「》」展开后还有东西可看。
    const data = await coupons(account, 10, '1')
    claimable.value = data && data.success && Array.isArray(data.list) ? data.list : []
    daysLeft.value = data && data.daysLeft !== undefined && data.daysLeft !== null ? data.daysLeft : null
  } catch (e) {
    handleApiError(e, '领券中心加载失败')
  } finally {
    claimLoading.value = false
  }
}

/** 一键领取只对「还领得动」的那几张有意义（已领 / 已领完仍在列表里，但不算可领）。 */
const tkClaimable = computed(
  () => claimable.value.filter((c) => !truthy(c.Claimed) && !truthy(c.SoldOut)).length
)

/** 领券。⚠️ 只「领」——核销在结算，本页不碰 `grant.Status`。 */
const doClaim = async (c) => {
  claiming.value = c.CouponId
  tkMsg.value = ''
  try {
    const data = await claim(couponAccount.value, c.CouponId)
    const ok = !!(data && data.success)
    tkMsg.value = ok
      ? '已领取「' + c.Name + '」，可在用户中心「优惠券」里查看'
      : '领取失败：' + ((data && data.msg) || '未知原因')
    if (ok) await loadCoupons(couponAccount.value)
  } catch (e) {
    handleApiError(e, '领取失败')
  } finally {
    claiming.value = ''
  }
}

/**
 * 「一键领取本周全部」。
 * ⚠️ 不传券 ID 列表 —— 集合由服务端定（`CouponService.claimBatch` 自己跑 `THIS_WEEK`）。
 * ⚠️ 不看顶层 `success` —— 它说「批量动作执行完毕」，部分失败刻意不回落 `false`；
 *    汇总按 `claimed` / `failed` 说人话。
 */
const doClaimBatch = async () => {
  if (batchBusy.value) return
  batchBusy.value = true
  tkMsg.value = ''
  try {
    const data = await claimBatch(couponAccount.value)
    if (!data || !data.success) {
      tkMsg.value = '一键领取失败：' + ((data && data.msg) || '未知原因')
      return
    }
    const claimed = Number(data.claimed || 0)
    const failed = Number(data.failed || 0)
    tkMsg.value = data.total === 0
      ? '本周没有可领的新券'
      : '一键领取：成功 ' + claimed + ' 张' + (failed > 0 ? '，失败 ' + failed + ' 张（' + firstFailReason(data) + '）' : '')
    await loadCoupons(couponAccount.value)
  } catch (e) {
    handleApiError(e, '一键领取失败')
  } finally {
    batchBusy.value = false
  }
}

const firstFailReason = (data) => {
  const bad = (data.items || []).find((it) => !it.success)
  return (bad && bad.msg) || '详见券包'
}

/**
 * 券条一次可见 5 张 + 越位渐隐 + 「》」展开。
 * ⚠️ `TK_VISIBLE` / 样式里 `(100% - gap×4) / 5.3` / 渐隐 88px 是一组，改一处要成对改。
 */
const TK_VISIBLE = 5
const tkExpanded = ref(false)

/** 二级入口「领券中心」的落点 = 锚到本页领券中心。 */
const scrollToTickets = () => {
  const el = ticketsEl.value
  if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

onMounted(async () => {
  couponAccount.value = getUsername() || ''
  if (!couponAccount.value) return
  await loadCoupons(couponAccount.value)
})

// ── 限时秒杀（C3 块 5）────────────────────────────────────────────────────────
// ⚠️ 未登录不发：`/marketing/seckill/live` 不在白名单，匿名发必 401，
//    而 `http.js` 的 `handleUnauthorized` 会把公开页整体踢去登录页。
const flashRows = ref([])
const flashLeft = ref(0)
let flashTimer = null
/** UTC 文本 → 毫秒。`DATE_FORMAT` 出的是库时钟（UTC）裸文本，不补 `Z` 会被当本地时间。 */
const flashTs = (s) => {
  const d = new Date(String(s == null ? '' : s).replace(' ', 'T') + 'Z')
  return isNaN(d.getTime()) ? null : d.getTime()
}
/** 倒计时瞄「最近要跨过的那一刻」：有进行中的取最早 `EndAt`，否则取最早的 `StartAt`。 */
const flashTarget = computed(() => {
  const live = flashRows.value.filter((r) => r.Stage === 'live')
  const key = live.length ? 'EndAt' : 'StartAt'
  const pick = live.length ? live : flashRows.value.filter((r) => r.Stage === 'upcoming')
  const ts = pick.map((r) => flashTs(r[key])).filter((n) => n !== null)
  return ts.length ? { at: Math.min(...ts), label: live.length ? '距结束' : '距开始' } : null
})
const flashCdLabel = computed(() => (flashTarget.value ? flashTarget.value.label : '距结束'))
const flashCd = computed(() => {
  const t = Math.max(0, Math.round(flashLeft.value / 1000))
  const p = (n) => String(n).padStart(2, '0')
  return { h: p(Math.floor(t / 3600)), m: p(Math.floor((t % 3600) / 60)), s: p(t % 60) }
})
const flashMoney = (v) => Number(v || 0).toFixed(2)
/** 已抢百分比 —— 只认真实 `SoldStock / TotalStock`；`TotalStock = 0` 返回 null（不画进度）。 */
const soldPct = (r) => {
  const total = Number(r.TotalStock) || 0
  const sold = Number(r.SoldStock) || 0
  if (total <= 0) return null
  return Math.max(0, Math.min(100, Math.round((sold / total) * 100)))
}
/** 剩余件数（后端 `SoldLeft`）；`TotalStock = 0` 时为 null。 */
const soldLeft = (r) => {
  if (r.SoldLeft === null || r.SoldLeft === undefined) return null
  const n = Number(r.SoldLeft)
  return isNaN(n) ? null : n
}
/** 去商品页 —— `query` 四件套与 `ProductCard.vue` 逐字同序。 */
const flashLink = (r) => ({
  path: '/product',
  query: { MerchantId: r.MerchantAccount, GoodName: r.Name || r.GoodName, GoodType: r.GoodType, Id: r.GoodsId },
})
const tickFlash = () => {
  const t = flashTarget.value
  flashLeft.value = t ? t.at - Date.now() : 0
}
/** `flashLoaded` 区分「没登录」与「登录了但一场都没有」。 */
const flashLoaded = ref(false)
const loadFlash = async () => {
  if (!couponAccount.value) return
  try {
    const data = await seckillLive({ limit: 8 })
    flashRows.value =
      data && data.success && Array.isArray(data.list)
        ? data.list.filter((r) => r.Stage !== 'signup').slice(0, 4)
        : []
  } catch (e) {
    flashRows.value = []
  }
  flashLoaded.value = true
  if (flashRows.value.length) {
    tickFlash()
    flashTimer = setInterval(tickFlash, 1000)
  }
}
onMounted(loadFlash)
onUnmounted(() => {
  if (flashTimer) clearInterval(flashTimer)
})
</script>

<style scoped>
/* ═══════════════════════════════════════════════════════════════════════════
   商城主页 · A 版公域前台样式（2026-10-19 全量重写）

   设计口径：
   · 结构：全宽主视觉 → 悬浮毛玻璃分类条 → 墨底秒杀 → 撕纸券卡 → 12 栏错落网格；
     **不再有**「左分类栏 / 中 Banner / 右推荐位」的三段式。
   · 语言：黑白灰为底，朱砂（`--accent`）只给价格 / 倒计时 / 促销进度；
     主视觉文案用 `--font-display`（楷体族）拉出书卷感，数字一律 `--font-mono`。
   · 交互：卡片悬浮抬升 + 阴影、图片缓推、按钮反色，全部 0.2–0.3s 缓动。
   · 保留：`prefers-reduced-motion` 下撤掉位移与图片缓推。
   ═══════════════════════════════════════════════════════════════════════════ */
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

/* ── ① 主视觉：满宽沉浸 ─────────────────────────────────────────────── */
.stage {
  position: relative;
  height: clamp(500px, 66vh, 700px);
  background: #14171a;
  color: #fff;
  isolation: isolate;
}
.stage-media {
  position: absolute;
  inset: 0;
  overflow: hidden;
}
.stage-slide {
  position: absolute;
  inset: 0;
  display: block;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.9s ease;
}
.stage-slide.on {
  opacity: 1;
  pointer-events: auto;
}
.stage-slide img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.01);
  transition: transform 7s linear;
}
.stage-slide.on img {
  transform: scale(1.07);
}
.stage-veil {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(10, 12, 14, 0.34) 0%, rgba(10, 12, 14, 0) 32%, rgba(10, 12, 14, 0.34) 64%, rgba(10, 12, 14, 0.86) 100%),
    linear-gradient(90deg, rgba(10, 12, 14, 0.6) 0%, rgba(10, 12, 14, 0.16) 48%, rgba(10, 12, 14, 0) 72%);
  pointer-events: none;
}

/* 空态：没有商品图时仍是一块有气质的深色面 */
.stage-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  background: radial-gradient(120% 100% at 20% 0%, #2a3034 0%, #14171a 62%);
  color: rgba(255, 255, 255, 0.62);
  font-size: 13px;
}
.stage-empty-mark {
  font-family: var(--font-display);
  font-size: clamp(64px, 10vw, 128px);
  line-height: 1;
  letter-spacing: 0.12em;
  color: rgba(255, 255, 255, 0.14);
}

.stage-inner {
  position: relative;
  z-index: 2;
  height: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 0 var(--s6) 156px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  pointer-events: none;
}
.stage-copy {
  max-width: 640px;
  pointer-events: auto;
  animation: stage-rise 0.8s cubic-bezier(0.2, 0.7, 0.2, 1) both;
}
@keyframes stage-rise {
  from { opacity: 0; transform: translateY(18px); }
  to { opacity: 1; transform: translateY(0); }
}
.stage-kicker {
  display: flex;
  align-items: center;
  gap: 12px;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.3em;
  color: rgba(255, 255, 255, 0.72);
}
.stage-kicker i {
  width: 34px;
  height: 1px;
  background: rgba(255, 255, 255, 0.45);
}
.stage-title {
  margin: 16px 0 0;
  font-family: var(--font-display);
  font-weight: var(--fw-med);
  font-size: clamp(36px, 4.6vw, 62px);
  line-height: 1.08;
  letter-spacing: -0.01em;
  text-shadow: 0 2px 28px rgba(0, 0, 0, 0.28);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.stage-meta {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px 20px;
  margin-top: 20px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 13px;
}
.stage-cat {
  padding: 3px 10px;
  border: 1px solid rgba(255, 255, 255, 0.36);
  letter-spacing: 0.08em;
}
.stage-price {
  font-family: var(--font-mono);
  font-size: 26px;
  color: #fff;
  font-variant-numeric: tabular-nums;
}
.stage-price i {
  font-style: normal;
  font-size: 15px;
  margin-right: 2px;
}
.stage-shop {
  color: rgba(255, 255, 255, 0.62);
}
.stage-cta {
  margin-top: 28px;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  align-self: flex-start;
  padding: 13px 22px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  letter-spacing: 0.08em;
  text-decoration: none;
  transition: background 0.22s ease, color 0.22s ease, gap 0.22s ease;
}
.stage-cta svg {
  width: 18px;
  height: 18px;
  transition: transform 0.22s ease;
}
.stage-cta:hover {
  background: var(--ink);
  color: #fff;
  gap: 18px;
}
.stage-cta:hover svg {
  transform: translateX(2px);
}
.stage-count {
  position: absolute;
  right: var(--s6);
  bottom: 136px;
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.56);
  pointer-events: none;
}
.stage-count b {
  font-size: 28px;
  font-weight: var(--fw-reg);
  color: #fff;
  margin-right: 6px;
}
.stage-dots {
  position: absolute;
  right: var(--s6);
  bottom: 112px;
  display: flex;
  gap: 8px;
  pointer-events: auto;
}
.stage-dots button {
  width: 30px;
  height: 3px;
  padding: 0;
  border: 0;
  background: rgba(255, 255, 255, 0.3);
  cursor: pointer;
  transition: background 0.2s ease;
}
.stage-dots button.on {
  background: #fff;
}
.stage-dots button:hover {
  background: rgba(255, 255, 255, 0.7);
}

/* ── ② 悬浮毛玻璃分类 / 快捷入口 ───────────────────────────────────── */
.stage-dock {
  position: absolute;
  z-index: 3;
  left: 50%;
  bottom: -36px;
  transform: translateX(-50%);
  width: min(1440px, calc(100% - 48px));
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 12px 18px;
  background: rgba(255, 255, 255, 0.84);
  backdrop-filter: blur(22px) saturate(1.7);
  -webkit-backdrop-filter: blur(22px) saturate(1.7);
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: 0 24px 60px rgba(16, 20, 24, 0.18);
  border-radius: 16px;
  color: var(--ink);
}
.dock-cats {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 2px;
  overflow-x: auto;
  scrollbar-width: none;
}
.dock-cats::-webkit-scrollbar {
  display: none;
}
.dock-all,
.dock-cat {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-family: var(--font);
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}
.dock-all {
  color: var(--ink);
  font-weight: var(--fw-med);
}
.dock-all:hover,
.dock-cat:hover {
  background: rgba(27, 30, 32, 0.06);
  color: var(--ink);
}
.dock-mono {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line-2);
  font-family: var(--font-display);
  font-size: 12px;
  line-height: 1;
  color: var(--ink-3);
  transition: background 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}
.dock-all .dock-mono {
  background: var(--ink);
  border-color: var(--ink);
  color: #fff;
}
.dock-cat:hover .dock-mono {
  border-color: var(--ink);
  color: var(--ink);
}
.dock-div {
  flex: none;
  width: 1px;
  height: 26px;
  background: var(--line);
}
.dock-links {
  flex: none;
  display: flex;
  align-items: center;
  gap: 2px;
}
.dock-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-family: var(--font);
  font-size: 12.5px;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease;
}
.dock-link svg {
  width: 15px;
  height: 15px;
  flex: none;
}
.dock-link:hover {
  color: var(--ink);
  background: rgba(27, 30, 32, 0.06);
}

/* ── 内容区 ─────────────────────────────────────────────────────────── */
.wrap {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 108px var(--s6) 0;
}
.sec {
  margin-top: 84px;
}
.sec:first-child {
  margin-top: 0;
}
.sec-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}
.sec-kicker {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.26em;
  color: var(--ink-3);
}
.sec-kicker-light {
  color: rgba(255, 255, 255, 0.52);
}
.sec-title {
  margin-top: 8px;
  font-family: var(--font-display);
  font-weight: var(--fw-med);
  font-size: 30px;
  line-height: 1.1;
  color: var(--ink);
}
.sec-more {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 0 4px;
  border: 0;
  border-bottom: 1px solid var(--line-2);
  background: none;
  color: var(--ink-2);
  font-family: var(--font);
  font-size: 13px;
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, gap 0.18s ease;
}
.sec-more svg {
  width: 16px;
  height: 16px;
}
.sec-more:hover {
  color: var(--accent);
  border-color: var(--accent);
  gap: 12px;
}

/* ── ③ 限时秒杀：墨底 + 大倒计时 + 横向卡片 ─────────────────────────── */
.flash {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  background: var(--ink);
  color: #fff;
  border-radius: 18px;
  overflow: hidden;
}
.flash-side {
  display: flex;
  flex-direction: column;
  padding: 30px 28px 28px;
  background: radial-gradient(130% 100% at 0% 0%, #2d3337 0%, #1b1e20 62%);
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}
.flash-title {
  margin-top: 8px;
  font-family: var(--font-display);
  font-weight: var(--fw-med);
  font-size: 32px;
  line-height: 1.1;
}
.flash-sub {
  margin-top: 6px;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.58);
}
.flash-clock-label {
  margin-top: 30px;
  font-size: 11px;
  letter-spacing: 0.16em;
  color: rgba(255, 255, 255, 0.52);
}
.flash-clock {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 8px;
}
.clock-cell {
  width: 52px;
  height: 58px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--accent);
  color: #fff;
}
.clock-cell b {
  font-family: var(--font-mono);
  font-size: 24px;
  font-weight: var(--fw-med);
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.clock-cell i {
  margin-top: 5px;
  font-size: 9px;
  font-style: normal;
  letter-spacing: 0.18em;
  opacity: 0.72;
}
.clock-colon {
  font-family: var(--font-mono);
  font-size: 22px;
  line-height: 58px;
  color: rgba(255, 255, 255, 0.4);
}
.flash-all {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  align-self: flex-start;
  margin-top: 24px;
  padding-bottom: 4px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.38);
  color: #fff;
  font-size: 12.5px;
  text-decoration: none;
  transition: gap 0.18s ease, border-color 0.18s ease;
}
.flash-all svg {
  width: 16px;
  height: 16px;
}
.flash-all:hover {
  gap: 12px;
  border-color: #fff;
}
.flash-rail {
  display: flex;
  overflow-x: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.25) transparent;
  scroll-snap-type: x mandatory;
}
.flash-rail::-webkit-scrollbar {
  height: 6px;
}
.flash-rail::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.24);
}
.flash-card {
  /* 少场次时也要把横向轨道填满：2 场 ⇒ 两张各占一半，4 场 ⇒ 各占 1/4。
     `flex-basis: 25%` 保证四场以上仍按 1/4 起排，`flex-grow: 1` 只吃剩余空间。 */
  flex: 1 0 25%;
  min-width: 218px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 26px 22px;
  color: #fff;
  text-decoration: none;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  scroll-snap-align: start;
  transition: background 0.22s ease;
}
.flash-card:last-child {
  border-right: 0;
}
.flash-card:hover {
  background: rgba(255, 255, 255, 0.055);
}
.flash-pic {
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: #2a2f33;
  display: flex;
  align-items: center;
  justify-content: center;
}
.flash-pic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s ease;
}
.flash-card:hover .flash-pic img {
  transform: scale(1.05);
}
.flash-pic-none {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.35);
}
.flash-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.fc-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.fc-nm {
  min-width: 0;
  font-size: 13.5px;
  line-height: 1.4;
  color: rgba(255, 255, 255, 0.88);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.fc-flag {
  flex: none;
  padding: 1px 6px;
  font-size: 10px;
  letter-spacing: 0.06em;
  color: #fff;
  background: var(--accent);
}
.fc-flag-soon {
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.72);
}
.fc-pr {
  font-family: var(--font-mono);
  font-size: 22px;
  line-height: 1.1;
  color: #fff;
  font-variant-numeric: tabular-nums;
}
.fc-pr i {
  font-style: normal;
  font-size: 13px;
  margin-right: 2px;
}
.fc-soon {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}
.fc-bar {
  height: 3px;
  margin-top: 2px;
  background: rgba(255, 255, 255, 0.14);
  overflow: hidden;
}
.fc-bar i {
  display: block;
  height: 100%;
  background: var(--accent);
  transition: width 0.6s ease;
}
.fc-pct {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.58);
  font-variant-numeric: tabular-nums;
}
.fc-pct em {
  font-style: normal;
  color: rgba(255, 255, 255, 0.42);
}

/* 秒杀空态：仍保留一块安静的黑面，不让版面塌掉 */
.flash-flat {
  background: var(--ink);
  color: #fff;
  border-radius: 18px;
}
.flash-empty {
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 38px 32px;
}
.flash-empty > svg {
  width: 46px;
  height: 46px;
  flex: none;
  color: rgba(255, 255, 255, 0.42);
}
.flash-empty-tx {
  flex: 1;
  min-width: 0;
}
.flash-empty-tx b {
  display: block;
  font-size: 15px;
  font-weight: var(--fw-med);
  color: #fff;
}
.flash-empty-tx p {
  margin-top: 4px;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.55);
}
.flash-all-dark {
  margin-top: 0;
  flex: none;
}

/* ── ④ 领券中心：撕纸券卡 ───────────────────────────────────────────── */
.tickets {
  margin-top: 84px;
  padding: 28px 30px 30px;
  background: var(--zone);
  border: 1px solid var(--line);
  border-radius: 18px;
}
.tk-h-r {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
}
.tk-left,
.tk-n {
  font-size: 12px;
  color: var(--ink-3);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.tk-all {
  padding: 8px 14px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: #fff;
  font-family: var(--font);
  font-size: 12.5px;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, opacity 0.2s ease;
}
.tk-all:hover:not([disabled]) {
  background: transparent;
  color: var(--ink);
}
.tk-all[disabled] {
  opacity: 0.36;
  cursor: not-allowed;
}
.tk-more {
  width: 30px;
  height: 30px;
  border: 1px solid var(--line-2);
  background: transparent;
  color: var(--ink-2);
  font-family: var(--font);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease, border-color 0.18s ease;
}
.tk-more:hover,
.tk-more-on {
  background: var(--ink);
  border-color: var(--ink);
  color: #fff;
}
.tk-strip {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  gap: var(--s3);
  margin-top: 22px;
  padding: 0;
  list-style: none;
  overflow: hidden;
}
.tk-strip-on {
  flex-wrap: wrap;
  overflow: visible;
}
/* 越位渐隐 88px 必须盖过第 6 张露头（约 1/4 张），三个数是一组，见脚本 `TK_VISIBLE`。 */
.tk-strip-cut {
  mask-image: linear-gradient(to right, #000 calc(100% - 88px), transparent 100%);
  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 88px), transparent 100%);
}
.tk-strip > .tk {
  flex: 0 0 calc((100% - var(--s3) * 4) / 5.3);
}
.tk {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.06fr) minmax(0, 0.94fr);
  min-height: 132px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow: hidden;
  transition: transform 0.24s cubic-bezier(0.2, 0.7, 0.2, 1), box-shadow 0.24s ease, border-color 0.24s ease;
}
.tk:hover {
  transform: translateY(-4px);
  border-color: var(--line-2);
  box-shadow: 0 20px 36px rgba(16, 20, 24, 0.12);
}
/* 撕纸缺口：上下两枚与区域同色的圆，压在左右两栏的接缝上 */
.tk::before,
.tk::after {
  content: '';
  position: absolute;
  left: 53%;
  z-index: 1;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--zone);
  border: 1px solid var(--line);
  transform: translateX(-50%);
}
.tk::before {
  top: -8px;
}
.tk::after {
  bottom: -8px;
}
.tk-l {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px 16px 16px;
  min-width: 0;
}
.tk-r {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 14px 16px;
  border-left: 1px dashed var(--line-2);
  min-width: 0;
}
.tk-top {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}
.tk-amt {
  font-family: var(--font-mono);
  font-size: 28px;
  font-weight: var(--fw-med);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.tk-new {
  flex: none;
  margin-top: 2px;
  padding: 1px 5px;
  font-style: normal;
  font-size: 9.5px;
  letter-spacing: 0.08em;
  line-height: 1.4;
  color: #fff;
  background: var(--ink);
}
.tk-name {
  max-width: 100%;
  font-size: 12.5px;
  font-weight: var(--fw-med);
  line-height: 1.35;
  color: var(--ink-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.tk-cond {
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--ink-3);
}
.tk-get {
  align-self: stretch;
  padding: 8px 10px;
  border: 1px solid var(--ink);
  background: transparent;
  color: var(--ink);
  font-family: var(--font);
  font-size: 12px;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, border-color 0.2s ease;
}
.tk-get:hover:not([disabled]) {
  background: var(--ink);
  color: #fff;
}
.tk-get[disabled] {
  border-color: var(--line-2);
  color: var(--ink-3);
  cursor: default;
}
.tk-empty {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 22px;
  padding: 26px;
  background: var(--surface);
  border: 1px dashed var(--line-2);
  color: var(--ink-3);
}
.tk-empty svg {
  width: 42px;
  height: 42px;
  flex: none;
}
.tk-empty b {
  display: block;
  font-size: 13px;
  font-weight: var(--fw-med);
  color: var(--ink-2);
}
.tk-empty span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
}
.tk-msg {
  margin-top: 16px;
  font-size: 12px;
  color: var(--brand-ink);
}

/* ── ⑤ 为你推荐：12 栏错落网格 ───────────────────────────────────────── */
.grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 30px 20px;
  margin-top: 28px;
  /* 错落：一行里 6/3/3 与 4/4/4 的卡**不拉齐**。拉齐会把 3 栏矮卡的白色卡面一直
     拉到和大卡同高，中间空出一大截（实测 461px）；按各自内容高度收边反而更像画册。 */
  align-items: start;
}
.grid > * {
  min-width: 0;
  transition: transform 0.3s cubic-bezier(0.2, 0.7, 0.2, 1);
}
.grid > *:hover {
  transform: translateY(-6px);
}
/* 节奏：6-3-3 / 4-4-4 / 6-3-3（每 9 件一循环） */
.grid > *:nth-child(9n + 1),
.grid > *:nth-child(9n + 7) {
  grid-column: span 6;
}
.grid > *:nth-child(9n + 2),
.grid > *:nth-child(9n + 3),
.grid > *:nth-child(9n + 8),
.grid > *:nth-child(9n + 9) {
  grid-column: span 3;
}
.grid > *:nth-child(9n + 4),
.grid > *:nth-child(9n + 5),
.grid > *:nth-child(9n + 6) {
  grid-column: span 4;
}
/* 卡片本体（ProductCard 的根就是 `.pcard`）—— 大面、轻阴影、悬浮抬升。
   ⚠️ 必须写 `.grid > *.pcard`，**不能**写 `.grid > * :deep(.pcard)`：`ProductCard` 的根
   就是 `.pcard` 自己，` :deep()` 后是**后代**选择器，匹配不到根。父组件的 scope 属性会
   落在子组件根上，所以 `.grid > *[data-v]` 能直接命中它。 */
.grid > *.pcard {
  /* 卡片自己按内容收边；文字区仍用纵向 flex，保证价格贴底（内容高度自然时即为紧贴）。 */
  display: flex;
  flex-direction: column;
  border-color: transparent;
  border-radius: 14px;
  box-shadow: 0 1px 0 rgba(16, 20, 24, 0.04), 0 10px 24px rgba(16, 20, 24, 0.05);
  transition: box-shadow 0.3s ease, border-color 0.24s ease;
}
.grid > * :deep(.pcard-img) {
  flex: none;
  /* ⚠️ `min-height: 0` 不能省：`.pcard` 改成纵向 flex 后，图区成了 flex item，
     其 `min-height: auto` 会取**图片原始比例**的 min-content 高度，把
     `aspect-ratio: 16/10 | 3/2` 顶掉（实测方图被撑成 1:1、大卡 669px 高）。 */
  min-height: 0;
}
.grid > * :deep(.pcard-tx) {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.grid > * :deep(.pcard-foot) {
  margin-top: auto;
}
.grid > *.pcard:hover {
  box-shadow: 0 30px 60px rgba(16, 20, 24, 0.17);
}
.grid > * :deep(.pcard-img img) {
  transition: transform 0.55s cubic-bezier(0.2, 0.7, 0.2, 1);
}
.grid > *:hover :deep(.pcard-img img) {
  transform: scale(1.045);
}
/* 大卡（6 栏）：更宽的画幅 + 更大的字阶，形成视觉重心 */
.grid > *:nth-child(9n + 1) :deep(.pcard-img),
.grid > *:nth-child(9n + 7) :deep(.pcard-img) {
  aspect-ratio: 16 / 10;
}
.grid > *:nth-child(9n + 1) :deep(.pcard-tx),
.grid > *:nth-child(9n + 7) :deep(.pcard-tx) {
  padding: 18px 20px 20px;
}
.grid > *:nth-child(9n + 1) :deep(.pcard-name),
.grid > *:nth-child(9n + 7) :deep(.pcard-name) {
  font-size: var(--fs-md);
  -webkit-line-clamp: 1;
  min-height: 1.45em;
}
.grid > *:nth-child(9n + 1) :deep(.pcard-price .int),
.grid > *:nth-child(9n + 7) :deep(.pcard-price .int) {
  font-size: 30px;
}
.grid > *:nth-child(9n + 1) :deep(.pcard-price .sym),
.grid > *:nth-child(9n + 7) :deep(.pcard-price .sym) {
  font-size: 16px;
}
.grid > *:nth-child(9n + 1) :deep(.pcard-price .dec),
.grid > *:nth-child(9n + 7) :deep(.pcard-price .dec) {
  font-size: 18px;
}
/* 中卡（4 栏）：介于大小之间 */
.grid > *:nth-child(9n + 4) :deep(.pcard-img),
.grid > *:nth-child(9n + 5) :deep(.pcard-img),
.grid > *:nth-child(9n + 6) :deep(.pcard-img) {
  aspect-ratio: 3 / 2;
}

/* 无限滚动哨兵 */
.rec-more {
  position: relative;
  margin-top: 44px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  letter-spacing: 0.04em;
  color: var(--ink-3);
  cursor: pointer;
}
.rec-more::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--line), transparent);
}
.rec-more--done {
  cursor: default;
  color: var(--ink-3);
}
.rec-more--loading {
  color: var(--ink-2);
}

/* ── 信任条 ─────────────────────────────────────────────────────────── */
.trust {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 88px;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.trust > div {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 26px 20px;
  border-left: 1px solid var(--line);
}
.trust > div:first-child {
  border-left: 0;
}
.trust .ic {
  width: 38px;
  height: 38px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line-2);
  color: var(--ink);
}
.trust b {
  display: block;
  font-size: 13px;
  font-weight: var(--fw-med);
  color: var(--ink);
  line-height: 1.4;
}
.trust span span {
  display: block;
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ink-3);
}

/* ── 页脚 ───────────────────────────────────────────────────────────── */
.home-foot {
  margin-top: 72px;
  background: var(--ink);
  color: rgba(255, 255, 255, 0.62);
}
.home-foot .inner {
  max-width: 1440px;
  margin: 0 auto;
  padding: 38px var(--s6);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.home-foot .pts {
  display: flex;
  gap: 28px;
  font-size: 12.5px;
  letter-spacing: 0.06em;
}
.home-foot .pts span {
  position: relative;
}
.home-foot .pts span + span::before {
  content: '';
  position: absolute;
  left: -14px;
  top: 50%;
  width: 3px;
  height: 3px;
  background: rgba(255, 255, 255, 0.28);
  transform: translateY(-50%);
}

/* ── 通用空态 ───────────────────────────────────────────────────────── */
.empty-tip {
  padding: 22px 0;
  color: var(--ink-3);
  font-size: 13px;
}
.empty-tip .more {
  margin-left: 10px;
  border: 0;
  border-bottom: 1px solid currentColor;
  background: none;
  color: var(--accent);
  font-family: var(--font);
  font-size: 12.5px;
  cursor: pointer;
}

/* ── 断点 ───────────────────────────────────────────────────────────── */
@media (max-width: 1180px) {
  .dock-link span {
    display: none;
  }
  .dock-link {
    padding: 8px 8px;
  }
  .flash {
    grid-template-columns: 260px minmax(0, 1fr);
  }
}
@media (max-width: 1080px) {
  .stage {
    height: 580px;
  }
  .stage-inner {
    padding-bottom: 170px;
  }
  .stage-dock {
    flex-wrap: wrap;
    gap: 8px 18px;
    width: calc(100% - 32px);
  }
  .dock-cats {
    order: 1;
    flex-basis: 100%;
  }
  .dock-div {
    display: none;
  }
  .dock-links {
    order: 2;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .dock-links::-webkit-scrollbar {
    display: none;
  }
  .dock-link span {
    display: inline;
  }
  .wrap {
    padding-top: 132px;
  }
  .flash {
    grid-template-columns: 1fr;
  }
  .flash-side {
    border-right: 0;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  }
  .flash-clock {
    margin-top: 6px;
  }
  .flash-all {
    margin-top: 18px;
  }
  .flash-card {
    flex-basis: 40%;
  }
  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .grid > * {
    grid-column: span 1 !important;
  }
  .grid > * :deep(.pcard-img) {
    aspect-ratio: 4 / 3 !important;
  }
  .grid > *:nth-child(2n) :deep(.pcard-img) {
    aspect-ratio: 3 / 2 !important;
  }
}
@media (max-width: 720px) {
  .stage {
    height: 500px;
  }
  .stage-inner {
    padding: 0 var(--s4) 160px;
  }
  .stage-title {
    font-size: 32px;
  }
  .stage-count,
  .stage-dots {
    right: var(--s4);
  }
  .stage-count {
    bottom: 132px;
  }
  .stage-dots {
    bottom: 108px;
  }
  .wrap {
    padding-left: var(--s4);
    padding-right: var(--s4);
  }
  .sec-title {
    font-size: 25px;
  }
  .sec-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
  .tk-h-r {
    width: 100%;
  }
  .flash-card {
    flex-basis: 78%;
  }
  .tk-strip > .tk {
    flex-basis: 86%;
  }
  .grid {
    grid-template-columns: 1fr;
  }
  .trust {
    grid-template-columns: 1fr 1fr;
  }
  .trust > div:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--line);
  }
  .trust > div:nth-child(4) {
    border-top: 1px solid var(--line);
  }
  .home-foot .inner {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }
  .home-foot .pts {
    flex-wrap: wrap;
    gap: 12px 24px;
  }
}

/* ── 降低动态偏好 ───────────────────────────────────────────────────── */
@media (prefers-reduced-motion: reduce) {
  .stage-slide,
  .stage-slide img,
  .stage-copy,
  .grid > *,
  .grid > * :deep(.pcard),
  .grid > * :deep(.pcard-img img),
  .tk,
  .flash-card,
  .flash-pic img {
    transition: none;
    animation: none;
  }
}
</style>
