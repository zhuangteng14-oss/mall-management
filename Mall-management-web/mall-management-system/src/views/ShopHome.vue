<template>
  <div class="shop-page">
    <!-- ── 自带顶栏 ───────────────────────────────────────────────────────────────
         本页路由带 `meta.public: true` ⇒ **不进壳**：没有壳的左菜单，也没有壳顶栏（与
         `ProductPage.vue` 同款做法）。
         ⚠️ **与那一页的顶栏有一处有意不同：本页没有搜索框**。那页的框不是装饰 ——
         `ProductPage.handleSearch` 写的 `PENDING_SEARCH_KEYWORD` 由 `AppShell.vue` 消费，
         它是「壳内搜索流」在那一页的**唯一入口**（删它 = 砍掉一条真功能的入口，那一页的头注记过）。
         本页**没有**那条流 ⇒ 放一个点了会跳走的框 = 画一个假控件。 -->
    <nav class="nav-bar">
      <button type="button" class="nav-back" @click="goBack">‹ 返回</button>
      <router-link class="brand" :to="getMerchantId() ? '/merchant' : '/customer'">商城</router-link>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div>
    </nav>

    <!-- ── 店铺头卡：**一条整幅的横带**，不是一张卡片 ──────────────────────────────
         整页只有这一处「敢用块面」，三个「不做」每条都对着一条 AI 默认样：
         · **不做**白底圆角卡 + 阴影 —— 「每块都长一样、同一个圆角、同一个 `rgba(0,0,0,.1)` 阴影」
           是通用后台卡墙的签名；且 `tokens.css:35` 明写「禁 · 嵌套卡片」。
         · **不做**标题上方那行全大写 eyebrow —— 那是 template chrome，与卖什么无关。
         · **不做**「A · B · C」中圆点拼接的元信息串 —— 下面两个计数**各带各的量词**，不拼串。
         横带的底用的是 `--zone`：`tokens.css` 自己的**区块底**（dark 主题那句写着「并排看得出
         是一块区域」）⇒ 它说的是「这一页归这家店」，是信息不是装饰。 -->
    <section class="band">
      <div class="band-in">
        <nav class="crumb">
          <router-link :to="getMerchantId() ? '/merchant' : '/customer'">首页</router-link>
          <i class="sp">/</i><span>{{ shopName ? shopName + '小店' : '店铺' }}</span>
        </nav>
        <div class="hd">
          <span class="hd-mark">{{ (shopName || '商')[0] }}</span>
          <span class="hd-id">
            <span class="hd-nm">{{ shopName ? shopName + '小店' : '店铺' }}</span>
            <span class="hd-sub">
              <b>{{ goods.length }}</b> 件商品<template v-if="followers !== null">，<b>{{ followers }}</b> 位粉丝</template>
            </span>
          </span>
          <span class="hd-ops">
            <!-- 关注按钮：**自己的店不画**（后端 `ShopFollowService.isSelf` 那道闸的前端另一半）。
                 粉丝数照显 —— 别人关注自己的店，自己更要看得到（后端为此刻意没在 `followState` 上判 403）。 -->
            <button
              v-if="!isSelf"
              type="button"
              class="act"
              :class="{ 'act-on': following }"
              :disabled="busy"
              :title="following ? '取关' : '关注这家店'"
              @click="toggleFollow"
            >{{ following ? '已关注' : '关注' }}</button>
            <button type="button" class="act" title="真功能：message.send（与商品页「客服」同形）" @click="consultShop">客服</button>
          </span>
        </div>
        <!-- ── 头卡事实行（认证徽章 / 主营 / 开店 / 累计售出）────────────────────────
             ⚠️ 本行**永远渲染**（C3 块 5 · ShopHome 布局授权笔，2026-10-12 改）—— 改前是
                「一项都凑不齐就整行不画」，用户裁定改为「**数据不足 → 占位，不静默消失**」。
                四项全空时画一句「暂无店铺资料」。
             ⚠️ **每项各有各的判据**（见 `<script>` 的 `certified` / `facts`）：
                · 认证徽章：**只有 `CertStatus === 'approved'` 才画**。`pending` / `rejected` /
                  一条申请都没有（后端回**空串**）⇒ 都不画。
                  ⚠️ **它没有占位，是本次唯一的一处例外**：空串的含义是「查不到」而不是「没认证」
                  —— 实测 15 条 `merchant_application.Username` 与 13 家店名的**交集是空集**，
                  更像两边键对不上。画一句「未认证」= 可能的一句**假否定**。**留人裁**。
                · 主营 / 开店 / 累计售出：三个各自为空就不进 `facts`。
             ⚠️ 各项**不拼成一句**：没有 `·`、也没有顿号 —— 那是「`·` 拼接的元信息串」这条
                AI 默认样。靠**间距**分组，每项自带单位（「件」「个月」）。 -->
        <div class="facts">
          <span v-if="certified" class="fact-cert" title="已通过平台入驻审核（读自 /shop/profile 的 CertStatus = approved）">
            <BadgeCheck class="fact-ic" aria-hidden="true" />已认证
          </span>
          <span v-for="f in facts" :key="f.k" class="fact"><i>{{ f.k }}</i><b>{{ f.v }}</b></span>
          <span v-if="!certified && !facts.length" class="fact-none">暂无店铺资料</span>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════════════════════════════════════════
         版面 = **丙方案定稿**（用户 2026-10-12 第二次授权，逐条对照）：
           一 · 头卡（不动）  →  二 · 本店券  →  三 · Tab [热销 | 全部 | 折扣]  →  四 · 猜你喜欢
         用户的四条要点（原文）：
           · 「头卡 + 本店券 固定在上（不随 tab 切）」
           · 「Tab 只切中间内容区」
           · 「猜你喜欢在三个 tab 内容下方 —— 不管切哪个 tab 都显示（或至少默认在『全部』下方）」
           · 「相对简洁，不像『纵向堆 6 模块』」
         ⚠️ 本方案**替掉上一版的纵向三块**（热销 / 商品 / 折扣各占一块）：那三块是**互斥地看**的
            （你一次只关心一件事），纵向堆起来却要三倍滚动，还得各配一条 `<h2>`。
         ⚠️ 猜你喜欢取的是**「不管切哪个 tab 都显示」**那一支（用户给了「或至少…」的退路，
            但硬约束是「必须在页面最底」，一直显示最不可能违反它）。 -->
    <div class="wrap"><nav v-if="isSelf" class="pact-tabs" role="tablist"><button v-for="t in SUBTABS" :key="t.k" type="button" role="tab" class="pact-tab" :class="{ 'pact-tab-on': topTab === t.k }" :aria-selected="topTab === t.k ? 'true' : 'false'" @click="topTab = t.k">{{ t.label }}</button></nav><!-- 一级 tab（C3 块 5 · 2026-10-12 用户裁定 ④）：**只有店主看自己的店**才画第二格（那面板里全是商家侧动作，别人看没有意义，后端两个端点本也 403）；默认停「本店商品」。只切**中间内容区** —— 头卡与本店券在两边都显示（沿用丙方案「头卡 + 本店券 固定在上」）。面板本体见 `components/PlatformActivityPanel.vue` 的文件头。⚠️ 本行与上面那行都压成一行是刻意的：本文件被 `refs-scan` 扫出 8 条 token（`:19/:266/:270/:271/:306/:477/:872/:875`），新增行会让它们集体顶漂。 -->
      <!-- ── 二 · 本店优惠券（**不随 tab 切**）────────────────────────────────────
           数据 = `POST /marketing/coupons` 的第 4 参 `MerchantAccount` 传**本店**（`shopId`）——
           与 `ProductPage.vue` 的「领券」行**同一个口径、同一个 `api/marketing.js` 封装**。
           ⚠️ 券包（我已领的）**不在这里** —— 它在用户中心 / 商品页的 `CouponWalletDialog`。
              本块只回答「**这家店现在有什么券可以领**」。
           ⚠️ `Account` 传的是**登录名**（`getUsername()`），不是客户号 —— 2026-10-12 券改账号口径。
           ⚠️ 三种「没有券可显示」**各说各的话**（用户：「数据不足 → 占位，不静默消失」）：
              未登录 ⇒ 「登录后可领取本店优惠券」+ 一个真出口；已登录但 0 张 ⇒ 「本店暂时没有
              可领的优惠券」；还在读 ⇒ 「正在读取…」。**三句都不声称本店有没有券**，除非真查过。 -->
      <section class="blk">
        <h2 class="sec-h">本店优惠券</h2>
        <ul v-if="shopCoupons.length" class="cpns">
          <li v-for="c in shopCoupons" :key="c.CouponId" class="cpn">
            <span class="cpn-face">{{ couponFace(c) }}</span>
            <span class="cpn-tx">
              <span class="cpn-name">{{ couponName(c) }}</span>
              <span class="cpn-cond">{{ couponLine(c) }}</span>
            </span>
            <button
              type="button"
              class="cpn-get"
              :disabled="truthy(c.Claimed) || truthy(c.SoldOut) || claiming === c.CouponId"
              :title="truthy(c.Claimed) ? '已领取' : '领取优惠券'"
              @click="doClaimShop(c)"
            >{{ truthy(c.Claimed) ? '已领取' : truthy(c.SoldOut) ? '已领完' : claiming === c.CouponId ? '领取中…' : '领取' }}</button>
          </li>
        </ul>
        <p v-else-if="!couponAccount" class="ph">
          登录后可领取本店优惠券<router-link class="ph-link" :to="{ path: '/login', query: { redirect: route.fullPath } }">去登录</router-link>
        </p>
        <p v-else class="ph">{{ couponsReady ? '本店暂时没有可领的优惠券' : '正在读取…' }}</p>
      </section><PlatformActivityPanel v-if="topTab === 'active'" :merchant-id="shopId" />

      <!-- ── 三 · Tab 区（**只有这一块随 tab 切**）──────────────────────────────────
           ⚠️ 栏题是「本店商品」而不是「本店热销」：三个 tab 都在讲同一件事的三种切法
              （这家店卖什么 / 卖得最好的是哪几件 / 哪几件在打折），栏题要覆盖得住三个。
           ⚠️ `role="tablist"` + `role="tab"` + `aria-selected`：这三枚是**真 tab**
              （各带一块互斥内容），不是「看起来像 tab 的链接」。用原生 `<button>`，
              键盘可达性不靠额外代码。 -->
      <section v-if="topTab === 'shop'" class="blk">
        <h2 class="sec-h">本店商品</h2>
        <div class="tabs" role="tablist">
          <button
            v-for="t in TABS"
            :key="t.k"
            type="button"
            role="tab"
            class="tab"
            :class="{ 'tab-on': activeTab === t.k }"
            :aria-selected="activeTab === t.k ? 'true' : 'false'"
            @click="activeTab = t.k"
          >{{ t.label }}</button>
        </div>

        <!-- 三-a · 热销：**2-1-3 山峰式**竖直柱状图（Top3）──────────────────────────
             用户原话：「只显示 Top3」·「竖直柱状图，居中排布」·「2-1-3 山峰式
             （1 名居中、2 名左、3 名右）」·「图片圆头」·「点柱 → 进商品」·
             「柱底共基线、数值骑柱顶」· 数据不足 → 占位「暂无销售数据」。
             ⚠️ **视觉顺序 = 2、1、3，但每根柱脚底下写的是它自己的名次** —— 排序是**版式**
                （把最高的那根放中间才成「山峰」），名次是**事实**（不随版式动）。两者必须分开，
                否则「左边第一根写着 2」会被读成「左起第一根 = 第 2 名」。
             ⚠️ 柱底共一条 0 基线、同一比例尺（都除以榜首）⇒ 中间的峰**是数据长的**，不是画上去的
                台阶。一根柱都没有 ⇒ 占位，**不画一个空坐标轴**（空轴比一句话更假）。
             ⚠️ 整列 = 一个 `<router-link>`（点柱进商品）：`<a>` 里**不得**嵌交互内容 ⇒
                图 / 柱 / 数值 / 名次全部是 `<span>`。 -->
        <div v-if="activeTab === 'hot'" class="pane">
          <div v-if="hotCols.length" class="chart">
            <router-link
              v-for="c in hotCols"
              :key="c.g.Id"
              class="col"
              :to="productLink(c.g)"
              :title="c.g.GoodName"
            >
              <span class="col-plot">
                <span class="col-val">{{ soldOf(c.g) }}</span>
                <span class="col-cap"><img v-if="c.g.GoodImg" :src="c.g.GoodImg" :alt="c.g.GoodName" /></span>
                <span class="col-bar" :class="{ 'col-bar-hero': c.rank === 1 }" :style="{ height: barPct(c.g) + '%' }"></span>
              </span>
              <span class="col-foot">
                <span class="col-no">{{ c.rank }}</span>
                <span class="col-nm">{{ c.g.GoodName }}</span>
              </span>
            </router-link>
          </div>
          <p v-else-if="phHot" class="ph">{{ phHot }}</p>
        </div>

        <!-- 三-b · 全部：商品网格（**新品靠前**）+ 分类 chip ─────────────────────────
             ⚠️ **不按 `MerchantId` 再请求一次**：`goodsSelectHome` 一次拉全量、前端过滤 ——
                与主页 / 详情页同一个端点、同一手法（零后端改动，见 `loadShop`）。
             ⚠️ 分类 chip **本店有 ≥2 个类目才画**：本笔从改前的「> 1 类」放宽过、
                又**收窄回 ≥2** —— 因为 Tab 里已经有一个叫「全部」的东西，本店只有一类时
                chip 行会画成「全部 5 | 电子用品 5」，**两个名字、同一个集合**，纯冗余。
                多类目时它才是真筛选。 -->
        <div v-else-if="activeTab === 'all'" class="pane">
          <div v-if="categories.length > 1" class="chips">
            <button
              type="button"
              class="chip"
              :class="{ 'chip-on': activeType === '' }"
              @click="activeType = ''"
            >全部 <i class="chip-n">{{ goods.length }}</i></button>
            <button
              v-for="t in categories"
              :key="t"
              type="button"
              class="chip"
              :class="{ 'chip-on': activeType === t }"
              @click="activeType = t"
            >{{ t }} <i class="chip-n">{{ countOfType(t) }}</i></button>
          </div>

          <p v-if="state === 'loading'" class="note">正在载入店铺…</p>
          <p v-else-if="state === 'error'" class="note note-err">
            {{ errMsg }}
            <button type="button" class="retry" @click="loadShop">重试</button>
          </p>
          <div v-else-if="goods.length === 0" class="note note-empty">
            <p>这家店还没有上架商品。</p>
            <router-link class="retry" :to="getMerchantId() ? '/merchant' : '/customer'">先去逛逛别的</router-link>
          </div>
          <!-- 「这个分类下没有商品」是**筛选**的结果，不是这家店空 —— 给一条一键回全的路，
               不写「没有商品」（那是另一句话，上面那支已经说过了）。 -->
          <div v-else-if="filteredGoods.length === 0" class="note note-empty">
            <p>本店「{{ activeType }}」下暂时没有商品。</p>
            <button type="button" class="retry" @click="activeType = ''">看全部 {{ goods.length }} 件</button>
          </div>
          <div v-else class="cards">
            <ProductCard
              v-for="g in shelfGoods"
              :key="g.Id"
              :goods="g"
              :tag="isNew(g) ? '新品' : ''"
            />
          </div>
        </div>

        <!-- 三-c · 折扣：折扣商品网格（`OriginalPrice` 有值才算）────────────────────
             判据 = `Number(OriginalPrice) > 0`。
             ⚠️ 用户写的是「OriginalPrice **非空**」，本笔落的是 `> 0` —— **不是同一条**：
                全库 43 行 `OriginalPrice` 都是 `0.0`（列早在、值全是 0），按「非空」判会把
                **43 件全算成折扣**。`0.0` 在库里是「没填」的默认值，不是「打 0 折」。
             ⚠️ 所以这一格**今天必然是占位**（`本店暂无折扣商品`）—— 代码在、数据没有；
                占位句是**用户定稿里逐字给的**，不是本笔另写的。 -->
        <div v-else class="pane">
          <div v-if="discountGoods.length" class="cards">
            <ProductCard
              v-for="g in discountGoods"
              :key="g.Id"
              :goods="g"
              :tag="isNew(g) ? '新品' : ''"
            />
          </div>
          <p v-else-if="phDiscount" class="ph">{{ phDiscount }}</p>
        </div>
      </section>

      <!-- ── 四 · 猜你喜欢（别家同类的）—— **不随 tab 切，且必须在页面最底** ──────────
           ⚠️ 用户给了「不管切哪个 tab 都显示（或至少默认在『全部』下方）」两条路，本笔取**前者**
              —— 硬约束是「必须在页面最底」，一直显示最不可能违反它。
           数据 = 全量 `goodsSelectHome` 里 `MerchantId !== 本店` **且** `GoodType === 本店主类目`
           的，取 4 条（**全宽铺开**：4 列铺满 `.wrap` 的 1180px）。
           ⚠️ 这是全页**唯一**取别家数据的地方，也是「逛完这家还有别家」的出口；一条都取不到
              （本店主类目全站独一份 / 本店一件商品都没有）⇒ **占位**，不静默消失。 -->
      <section v-if="topTab === 'shop'" class="blk">
        <h2 class="sec-h">猜你喜欢<i class="sec-note">别家的{{ mainCategory }}</i></h2>
        <div v-if="recommend.length" class="cards">
          <ProductCard v-for="g in recommend" :key="g.Id" :goods="g" />
        </div>
        <p v-else-if="phRecommend" class="ph">{{ phRecommend }}</p>
      </section>
    </div>
  </div>
</template>

<script setup>
// ⚠️ 改前的这 6 条 import 是**挤在同一行**的（先例 `HomePage.vue:284` / `ProductPage.vue:459`）。
//    那手法的用途写得很清楚：本文件 `:19` 被 `scripts/refs-scan.sh` 扫出一条真引用、
//    必须保住行号 ⇒「能省一行就省一行」。本笔是**整段重写**（758 → 935 行），行号本来就全变，
//    没有要保的行号 ⇒ 拆成多行（可读性 > 省行）。
// ⚠️ 但**前 23 行仍一字未动**（头注释 + 自带顶栏）：`ShopHome.vue:19` 那一行写着 `tokens.css:35`
//    （「禁 · 嵌套卡片」）这条引用，`docs/agent/evidence/c3-block5/block5-f-shophome-modules.md:137`
//    按行号钉着它、`refs-scan` 也扫得出来 ⇒ 整段照搬，那一条的**行号零漂移**。
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId, getUsername, isLoggedIn } from '../api/auth'
import { follow, unfollow, followState, profile } from '../api/shop'; import { dedupeGoods, sharedList } from '../config/goods-flags'   // ⚠️ 〈Bug 1/2〉落码（2026-10-12）把这两件**并进这一行**（不新增行）—— 本文件 `:266` / `:270` / `:306` 三处注释按行号引别的文件，多一行就集体顶漂。`dedupeGoods` / `sharedList` 都在 `config/goods-flags.js`，那是**列表侧共用判据的唯一定义处**（本页原来自己那份 `dedupeByCode` 已退役，见下面墓碑）
import { coupons, claim } from '../api/marketing'
import { truthy, couponFace, couponName, couponLine } from '../config/coupon-format'
import { BadgeCheck } from '@lucide/vue'
import ProductCard from '../components/ProductCard.vue'; import PlatformActivityPanel from '../components/PlatformActivityPanel.vue'   // ⚠️ 两条 import **同行**（同 `:151` / `:266` 那几条的手法）：本文件有 8 条 refs token，多一行就集体顶漂。`PlatformActivityPanel` = 一级 tab「平台活动」的面板（2026-10-12 用户裁定 ④）。
import { productLink } from '../config/product-link'

const route = useRoute()
const router = useRouter()

// 看的是哪家店 —— **只认路由参数**。两个入口都只给这一个值：
// `ProductPage.vue` 的「进店逛逛」（`/shop?MerchantId=<卖家>`）与用户中心「店铺信息」格
// （`/shop?MerchantId=<自己的商家号>`）。
const shopId = String(route.query.MerchantId || '')

// 「我」是谁：与 `ProductPage.vue` 的 `customerId` **同一条降级链**（customerid → merchantid）。
// ⚠️ 必须同链：后端 `ShopFollowService.isSelf` 判的就是「这个值 == MerchantId」——
//    两条链分叉的话，「商家逛自己的店」会在前端画出关注按钮、点下去才被后端拒。
let me = getCustomerId()
if (!me) me = getMerchantId()
const buyerId = me || ''

const isLogin = computed(() => isLoggedIn())
// 自己的店 ⇒ 不画关注按钮（**但粉丝数照请求照显示**，见 `readFollowState` 的注释）。
// ⚠️ 大小写不敏感，与后端那道闸用同一个判据（库排序规则 `utf8mb4_0900_ai_ci` 本就是 CI 的）。
const isSelf = computed(() => !!buyerId && buyerId.toLowerCase() === shopId.toLowerCase())

// 券包 / 领券的**账号**（2026-10-12 起 = **登录名**，不是客户号）。
// ⚠️ 与 `ProductPage.vue:1015` / `HomePage.vue:413` 取值**逐字同款**（`getUsername()`）——
//    三处必须一起改，`api/auth.js` 是唯一允许读身份的地方。
const couponAccount = getUsername() || ''

/**
 * 三个 tab。
 * ⚠️ 顺序 = 用户定稿的顺序（`[热销] [全部] [折扣]`），**默认停在第一个** —— 「先给结论
 *    （卖得最好的是哪几件），再给全量」。
 * ⚠️ `activeTab` 存的是 `k` 而不是中文：中文是**标签**（会改），`k` 是**键**（不该改），
 *    判等写在键上 ⇒ 改文案不会把 tab 状态打散。
 */
const TABS = [
  { k: 'hot', label: '热销' },
  { k: 'all', label: '全部' },
  { k: 'discount', label: '折扣' }
]
const activeTab = ref('hot'); const SUBTABS = [ { k: 'shop', label: '本店商品' }, { k: 'active', label: '平台活动' } ]; const topTab = ref('shop')   // ⚠️ 三条**同行**（理由同 `:322` 上面那条）。**一级** tab（2026-10-12 用户裁定 ④）：`shop` = 下面那三个二级 tab + 猜你喜欢；`active` = 平台邀请应答 + 秒杀报名（`components/PlatformActivityPanel.vue`）。⚠️ 键仍是**键**（`shop`/`active`）、标签才是中文，与 `TABS` 同一条规矩。⚠️ 默认 `shop` 是用户逐条裁定的第 3 条。

/**
 * 热销榜取几根柱 —— 用户定稿「**只显示 Top3**」。
 * ⚠️ 改前是 5（图表规范推荐的 5–8 根）。定稿把它压到 3：3 根才摆得出「2-1-3」这座山峰。
 */
const HOT_LIMIT = 3
/**
 * 最高那根柱占**图区高度**的百分比。
 * ⚠️ 为什么不是 100：柱顶上面还要站「数值 + 圆形商品图」（见 `.col-plot` 那条注释），
 *    柱子铺满就没地方放了。60% 实测刚好：204 + 130 ≤ 340。
 * ⚠️ 所有柱共用**同一个**比例尺（都除以最大值）⇒ 柱间高度比是真的，只是整体没顶到框顶。
 */
const BAR_MAX_PCT = 60
/** 「新品」红签最多几件。见 `newCount` 那条注释。 */
const NEW_MAX = 3

const goods = ref([])
/** 全量（不过滤商家）—— 只为「猜你喜欢」那一块留着（要从**别家**取）。 */
const allGoods = ref([])
const following = ref(false)
// `null` = **还不知道**（未登录 ⇒ 拿不到）⇒ 头卡**不渲染**粉丝数那一段。
// ⚠️ 别拿 `0` 当「不知道」：那是「一个粉丝都没有」，一句假话。
const followers = ref(null)
const busy = ref(false)
const state = ref('loading')   // 'loading' | 'ready' | 'error'
const errMsg = ref('')
/** `/shop/profile` 的载荷；**取不到就留 `null`** ⇒ 头卡那几项各判各的（不把整页打成 error）。 */
const info = ref(null)
const shopCoupons = ref([])
/** 券**问过了**（不管问出几张）—— 用来区分「还在读」与「读了是空的」。见 二 那块的三句话。 */
const couponsReady = ref(false)
const claiming = ref('')
/** 分类筛选的当前值；`''` = 全部。⚠️ 只筛「全部」那个 tab 的网格，不动热销 / 折扣 / 猜你喜欢。 */
const activeType = ref('')

/**
 * 店名 —— **两个来源，有主有备**：
 *   ① `/shop/profile` 的 `UserName`（**主**，新端点给的；一件商品都没有的店也拿得到名字）；
 *   ② 商品行里的 `UserName`（**备**，与 `ProductPage.vue` 的 `currentItem.UserName` 同源）。
 * ⚠️ 两个来源是**同一列**（`login.UserName`，`GoodsCacheService` 那条 `SELECT UserName FROM login`），
 *    不会打架；都不行时留空，头卡退化成「店铺」/「商」，**不编一个**。
 */
const shopName = computed(() => {
  const fromProfile = info.value && info.value.UserName ? String(info.value.UserName) : ''
  if (fromProfile) return fromProfile
  return goods.value.length ? goods.value[0].UserName || '' : ''
})

/** 本店的商品种类（去重 + 排序）。⚠️ 排序只为让 chip 行每次都长一样，不是业务序。 */
const categories = computed(() => {
  const set = new Set()
  goods.value.forEach((g) => {
    const t = String(g.GoodType || '')
    if (t) set.add(t)
  })
  return Array.from(set).sort()
})

/** 某一类有几件（chip 上的小数字）。 */
const countOfType = (t) => goods.value.filter((g) => String(g.GoodType || '') === t).length

/** 过筛后的本店商品。`''` = 不过筛。 */
const filteredGoods = computed(() =>
  activeType.value
    ? goods.value.filter((g) => String(g.GoodType || '') === activeType.value)
    : goods.value
)

/** 某一行的销量（缺键 ⇒ 0；老响应里没有 `SoldQty` 也不炸）。 */
const soldOf = (g) => Number(g.SoldQty) || 0

/**
 * 〈Bug 1〉退役（2026-10-12）：这里原来有一份**只按 `ProductCode`** 去重的 `dedupeByCode`。
 *   · **为什么退役**：同一个判据 `GoodsSelect` / `HomePage` / `ProductPage` 三处也要用，
 *     各留一份就成了「同一件事两个真相」；而且**只按码**在**跨店**列表上本来就是错的
 *     —— 不同店用同一个 `ProductCode` 是两件**不同**的货（各自的编号体系），会误并掉一件。
 *   · **现在在哪**：`config/goods-flags.js` 的 `dedupeGoods`，键 = `MerchantId|ProductCode`，
 *     同键留 **`Id` 最小**那一行 —— 与详情页 `ORDER BY Id ASC LIMIT 1` **刻意同判据**
 *     （列表里选中的那行，与点进去读到的那行，必须是同一行）。
 *   · **本页四个调用方**都改调它：`totalSold` / `hotGoods` / `newKeys` / `recommend`。
 *     前三个是**单店**（`goods` 里全是 `MerchantId === shopId` 的）⇒ 多出的 `MerchantId`
 *     这一维**不改变任何一行**的去留；`recommend` 是**跨店**取的，那里反而修掉了一个真错
 *     （别家同码的两件货原先会被并成一件）。
 *   · **原注释的两条按语仍成立**：**空码不并**（`ProductCode=''` = 「没登记编码」，多行空码
 *     是**不同**商品，后端聚合 SQL 也写着 `WHERE ProductCode <> ''`）、**留哪一行**（`Id` 最小）。
 *     两条都搬进 `dedupeGoods` 的文档注释，此处不再复述。
 *   · ⚠️ **本块行数刻意补满 19 行**（原 6 行注释 + 13 行函数）：本文件 `:1050` 那条注释按
 *     行号引 `:872/:875`，本块少一行，它俩连同下面所有按行号的引用就集体顶漂；也**别**把
 *     它改成一个一行转发（名字「by code」就成了假话，且两个名字一件事仍留在仓里）。
 */

/**
 * 主营类目 = 本店商品的 **`GoodType` 众数**。
 * ⚠️ **并列时按类目名升序取第一** —— `Array.from(...).sort()` 是确定序（同一份数据每次同一答案），
 *    再配 `>`（不是 `>=`）⇒ 名字靠前那个先到先得。判据写死在这里，可复核，不随遍历顺序飘。
 */
const mainCategory = computed(() => {
  const tally = new Map()
  goods.value.forEach((g) => {
    const t = String(g.GoodType || '')
    if (t) tally.set(t, (tally.get(t) || 0) + 1)
  })
  let best = ''
  let bestN = 0
  Array.from(tally.keys()).sort().forEach((t) => {
    if (tally.get(t) > bestN) { best = t; bestN = tally.get(t) }
  })
  return best
})

/**
 * 累计销量 = Σ 本店商品的 `SoldQty`，**先去重**（同码两行会被 `SoldQty` 算两遍 ——
 * 聚合本身是按 `ProductCode` 分组的，一件货只该计一次）。
 */
const totalSold = computed(() => dedupeGoods(goods.value).reduce((n, g) => n + soldOf(g), 0))

/** 热销榜：去重 → 只留有销量的 → 降序 → 取 `HOT_LIMIT` 根柱。 */
const hotGoods = computed(() =>
  dedupeGoods(goods.value)
    .filter((g) => soldOf(g) > 0)
    .sort((a, b) => soldOf(b) - soldOf(a))
    .slice(0, HOT_LIMIT)
)

/** 榜首那根的销量（比例尺的分母）。空榜 ⇒ 0。 */
const hotMax = computed(() => (hotGoods.value.length ? soldOf(hotGoods.value[0]) : 0))

/**
 * 柱的**渲染序** = 「2-1-3 山峰式」（用户定稿：1 名居中、2 名左、3 名右）。
 * ⚠️ **只重排版式，不动名次**：每根柱自带 `rank`（1 起），柱脚底下写的是 `rank` 而不是下标
 *    —— 两者混了的话，「左边第一根」会被读成「第 1 名」。
 * ⚠️ **不足 3 根不摆山峰**（1 根 / 2 根时「中间那根」没有定义）⇒ 按名次原序排。
 *    判据写死（`length === 3`），不是「大概齐」。
 */
const hotCols = computed(() => {
  const base = hotGoods.value.map((g, i) => ({ g, rank: i + 1 }))
  if (base.length !== 3) return base
  return [base[1], base[0], base[2]]
})

/**
 * 某根柱的高度百分比 = 自己 ÷ 榜首 × `BAR_MAX_PCT`。
 * ⚠️ 比例尺**从 0 起**（图表规范：截断的轴会撒谎）⇒ 这根柱一定是榜首那根的几分之几，
 *    不是「在最小值和最大值之间插值」那种会把 1 件和 10 件画得差不多的做法。
 */
const barPct = (g) => {
  const max = hotMax.value
  if (!max) return 0
  return Math.round((soldOf(g) / max) * BAR_MAX_PCT)
}

/**
 * 「新品」红签**最多盖几件** = `min(3, ⌊本店商品数 ÷ 3⌋)`。
 * ⚠️ 这条分母**不是随手取的**：`goods` 表**没有时间列**（基线 8 列 + 3 处迁移加列，2026-10-12
 *    全查过），唯一可复核的代理是 `Id` —— 它是 `AUTO_INCREMENT`（`mall_management_sql.sql:55`）
 *    ⇒ **同一家店内 `Id` 越大 = 越晚入库**。
 *    ⚠️ 用户定稿这一行写的是「**created_at 近 3 天优先**」—— **那个字段不存在**，
 *       本笔落的是这条 `Id` 代理，并登记为待裁（要真上架时刻得给 `goods` 加时间列、
 *       且不回填存量，那是另一笔 = 建表硬线，须用户点头）。
 * ⚠️ 但「最新的 N 件」若 N 固定，5 件货的店会有 3 件挂着「新品」—— 那枚签就没有信息量了。
 *    ÷3 保证它**永远是少数**：5 件 ⇒ 1 件，9 件 ⇒ 3 件，43 件 ⇒ 3 件（封顶）。
 */
const newCount = computed(() => Math.min(NEW_MAX, Math.floor(goods.value.length / 3)))

/** 被盖上「新品」的那几件的键：有码用码、无码用 `id:` 前缀（同 `dedupeGoods` 的空码口径）。 */
const newKeys = computed(() => {
  const key = (g) => String(g.ProductCode || '') || 'id:' + g.Id
  const top = dedupeGoods(goods.value)
    .slice()
    .sort((a, b) => Number(b.Id) - Number(a.Id))
    .slice(0, newCount.value)
  return new Set(top.map(key))
})

/** 这一件是不是「新品」（判据、上限、理由全在上面两段注释里）。 */
const isNew = (g) => {
  if (newCount.value <= 0) return false
  return newKeys.value.has(String(g.ProductCode || '') || 'id:' + g.Id)
}

/**
 * 「全部」tab 的**摆法**：新品靠前（用户定稿「新品靠前排序」），其余保持原序。
 * ⚠️ 用**稳定分区**而不是 `sort`：JS 的 `sort` 从 ES2019 起才是稳定的，但这里根本不需要
 *    比较函数 —— 「是不是新品」是个布尔，分成两堆再拼起来，**组内原顺序天然不动**，
 *    比写一个 `(a, b) => isNew(b) - isNew(a)` 更好读，也不可能因为比较函数写错而乱掉。
 * ⚠️ 判据（`isNew`）来自**全店**商品，不随当前分类 chip 变 —— 换 chip 不该改变一件货的
 *    「新品」身份。
 */
const shelfGoods = computed(() => {
  const list = filteredGoods.value
  if (!newCount.value) return list
  const fresh = []
  const rest = []
  list.forEach((g) => (isNew(g) ? fresh : rest).push(g))
  return fresh.concat(rest)
})

/** 折扣 tab：有划线价（`OriginalPrice > 0`）的。⚠️ 当前库 0 行（见模板 三-c 的注释）。 */
const discountGoods = computed(() => goods.value.filter((g) => Number(g.OriginalPrice) > 0))

/**
 * 猜你喜欢：别家的、与本店主类目相同的，取 4 条 —— **整支走 `sharedList`**（C3 块 5 ·〈Bug 1/2〉落码，2026-10-12）。⚠️ 它 = **同码去重**（键 `MerchantId|ProductCode`；别家同码是**两件不同的货**，只按码并会误删一件）+ **滤掉已售完**。这一格是**共享面**（取别家的货）⇒ 已售完的别家商品不该在这里出现（用户裁定：已售完只在自己店铺页显示）；定义在 `config/goods-flags.js`，与主页「为你推荐」/ 搜索页 / 详情页「猜你喜欢」三处**同源** —— 同一个函数，不是「照抄的四份」。
 * ⚠️ 顺序「先去重、后滤售完」：去重后一件货只剩一行，而那一行正是详情页会读到的那一行（两边都取 `Id` 最小），列表藏了它、详情页也说「已售完」，不打架。本店「全部 / 热销 / 折扣」三个 tab **不过这一层**（本店要显示含已售完；灰态 + 红斜标由 `ProductCard` 自己按 `IsSoldOut` 画）。⚠️ 本条注释**刻意压成 4 行**：本文件那段「`:872/:875`」自引是按行号说的，注释多一行下面就集体顶漂。
 */
const recommend = computed(() => {
  if (!mainCategory.value) return []
  return sharedList(
    allGoods.value.filter(
      (g) => String(g.MerchantId) !== shopId && String(g.GoodType || '') === mainCategory.value
    )
  ).slice(0, 4)
})

/** 认证徽章 —— **只有 `approved` 才画**（`pending` / `rejected` / 空串一律不画，见模板头注）。 */
const certified = computed(() => !!(info.value && info.value.CertStatus === 'approved'))

/**
 * 开店时长 —— `null` = **不知道**（`RegisteredAt` 为空 / 解析不了）⇒ 头卡不画这一项。
 * ⚠️ **本页唯一一处用浏览器钟**（`Date.now()`）：后端 `RegisteredAt` 只给**时刻**，
 *    没有给「已开店 N 天」这种算好的字段，想要时长就只能自己减。
 *    代价可接受：粒度是**天 / 月**，浏览器钟偏几分钟不影响结论；而**偏出一天**时最坏也只是
 *    把「30 天」说成「1 个月」—— 同一件事的两种说法，不是假话。
 *    先例反着来的是券的「本周还剩 N 天」：**那个**后端给了 `daysLeft`，所以那边不碰浏览器钟。
 * ⚠️ 库里时间列是 **UTC 的文本**（同 `config/coupon-format.js` 里 `toLocal` 的口径）⇒ 补 `Z`；
 *    不补就按本地时区解析，整差一个偏移，且**错得很安静**。
 * ⚠️ 算出**负数**（库钟晚于浏览器钟）⇒ 回 `null` **不画**，别写「-1 天」。
 */
const opened = computed(() => {
  const at = info.value && info.value.RegisteredAt
  if (!at) return null
  const t = new Date(String(at).replace(' ', 'T') + 'Z')
  if (Number.isNaN(t.getTime())) return null
  const days = Math.floor((Date.now() - t.getTime()) / 86400000)
  if (days < 0) return null
  if (days < 1) return '今天'
  if (days < 30) return days + ' 天'
  return Math.floor(days / 30) + ' 个月'
})

/** 头卡事实行**除徽章外**的三项（各自为空就不进数组 ⇒ 不画那一项）。 */
const facts = computed(() => {
  const out = []
  if (mainCategory.value) out.push({ k: '主营', v: mainCategory.value })
  if (opened.value !== null) out.push({ k: '开店', v: opened.value })
  if (totalSold.value > 0) out.push({ k: '累计售出', v: totalSold.value + ' 件' })
  return out
})

/**
 * 空块那一行话的**统一出口**（载入中 / 就绪但空 / 出错三种）。
 * ⚠️ 出错时回**空串**（⇒ 那一块整行不渲染）：主块（「全部」那一格）已经把错误 + 重试按钮
 *    放在手边了，每一块再喊一遍「没取到」只是噪音。
 * ⚠️ 载入中不冒充「没有」：`loading` 时说「正在读取…」—— 这也正是用户那条
 *    「占位 ≠ 造假」的边界。
 */
const phText = (has, text) => {
  if (state.value === 'loading') return '正在读取…'
  if (state.value !== 'ready') return ''
  return has ? '' : text
}

// ⚠️ 热销 / 折扣两句是**用户定稿里逐字给的**（「暂无销售数据」/「本店暂无折扣商品」），
//    不是本笔另写的。
const phHot = computed(() => phText(hotGoods.value.length > 0, '暂无销售数据'))
const phDiscount = computed(() => phText(discountGoods.value.length > 0, '本店暂无折扣商品'))
const phRecommend = computed(() => phText(recommend.value.length > 0, '暂时没有可推荐的同类商品'))

/**
 * 店铺档案（`/shop/profile`）—— 头卡的店名 / 开店时长 / 认证徽章。
 * ⚠️ **失败不抛**：那几项是「锦上添花」，取不到就少画几项，商品列表照旧（**不把整页打成 error 态**）。
 * ⚠️ **不弹窗**（同 `readFollowState` 的取舍）：这里没有用户发起的动作，弹一次是打扰。
 */
const loadProfile = async () => {
  if (!shopId) return
  try {
    const r = await profile(shopId)
    if (r && r.success) info.value = r
  } catch (e) {
    console.error('店铺档案读取失败', e)
  }
}

/**
 * 本店可领券 —— `coupons(account, limit, thisWeek, merchantAccount)` 的第 4 参就是本店。
 * ⚠️ 第 3 参（`thisWeek`）**不传**：本块要的是「本店现在能领的**全部**」（含旧券），
 *    不是主页那块「本周新发布」；`q()` 见空值不发这个键 ⇒ 后端不按周过滤。
 * ⚠️ 未登录（无 `couponAccount`）⇒ **一个请求都不发**（端点不在白名单，发出去只会 401）；
 *    那一格的占位句由模板直接给（「登录后可领取本店优惠券」）。
 * ⚠️ 无论成功失败都把 `couponsReady` 置真 —— 否则出过一次错，那一格会永远停在「正在读取…」。
 */
const loadShopCoupons = async () => {
  if (!couponAccount || !shopId) return
  try {
    const r = await coupons(couponAccount, 3, undefined, shopId)
    shopCoupons.value = r && r.success && Array.isArray(r.list) ? r.list : []
  } catch (e) {
    console.error('本店优惠券读取失败', e)
  } finally {
    couponsReady.value = true
  }
}

/** 领券（落 `coupon_grant`）。⚠️ 与 `ProductPage.doClaimCoupon` 同款，领完**回读**列表（不自己删那一张）。 */
const doClaimShop = async (c) => {
  claiming.value = c.CouponId
  try {
    const r = await claim(couponAccount, c.CouponId)
    if (r && r.success) {
      await loadShopCoupons()
      alert('已领取「' + couponName(c) + '」，可在「我的券包」查看')
    } else {
      // ⚠️ 这里会原样显示后端那句话 —— 其中包括「**不能领自己发的券**」（店主自己逛本店时的正常拒绝，
      //    2026-10-12 加的闸）；列表侧已经把自家券筛掉了，走到这一句多半是并发抢光了。
      alert('领取失败：' + ((r && r.msg) || '未知原因'))
    }
  } catch (e) {
    handleApiError(e, '领取失败')
  } finally {
    claiming.value = ''
  }
}

// 一次拉全量、前端按 `MerchantId` 过滤 —— 与主页 / 详情页**同一个端点、同一手法**（零后端改动）。
// ⚠️ 端点 `goodsSelectHome` 在 `AUTH_WHITELIST` 内 ⇒ 匿名进本页**不会**触发 401（同 `HomePage`）。
// ⚠️ 三件事**并发**（`Promise.all`）：商品列表 / 店铺档案 / 本店券。三者互不依赖，串行只是白白多等两轮。
//    其中后两件**自带 try/catch 不抛**（`loadProfile` / `loadShopCoupons`），所以 `Promise.all`
//    不会因为「档案取不到」而把商品列表一起打掉 —— 那正是这一块最要紧的取舍。
const loadShop = async () => {
  state.value = 'loading'
  errMsg.value = ''
  try {
    const [res] = await Promise.all([
      http.post(endpoints.goods.goodsSelectHome),
      loadProfile(),
      loadShopCoupons()
    ])
    const data = await res.json()
    if (!data.success) {
      state.value = 'error'
      errMsg.value = '店铺信息没取到：' + (data.msg || '服务端没有返回店铺商品')
      return
    }
    const all = data.list || []
    allGoods.value = all
    goods.value = dedupeGoods(all.filter((g) => String(g.MerchantId) === shopId))   // ⚠️ 〈Bug 1〉落码（2026-10-12）：本店网格也**同码去重**（判据 = `MerchantId|ProductCode`，留 `Id` 最小 —— 与详情页同一条）。理由：本店 `dRD3gqtbFr` 的 `GD-101-9552` 在库里两行（`Id` 76 / 120，同名同价），不去重则**同一件货在店铺页画两张卡**、且两张都点进同一个详情页（详情页只认 `Id` 最小那一行）。⚠️ **只去重、不过滤已售完**：本页三个 tab 是「自己店铺页」，用户 ② 裁定要**显示**已售完（灰态 + 红斜标由 `ProductCard` 画）。⚠️ `totalSold` / `hotGoods` / `newKeys` 仍然各自调 `dedupeGoods`（对本已是去重的列表是幂等的）—— 不靠「上游已经去过重」这种隐式前提。
    state.value = 'ready'
    await readFollowState()
  } catch (e) {
    state.value = 'error'
    errMsg.value = '店铺信息没取到：网络或服务暂时没响应'
    handleApiError(e, '店铺信息加载失败')
  }
}

// 关注状态 + 粉丝数**都回读**（不自己 ±1 —— 后端 `followState` 是一次真查库，两个人同时关注时
// 算术增减会各自丢一笔）。
// ⚠️ **未登录不请求**：三条端点都要 `CustomerId`，匿名的每一次出去都是白跑 —— 401（不在白名单）
//    或 `success:false`「缺少参数」，两种都拿不到粉丝数，只多一条控制台噪音。
//    ⇒ 拉不到就**不显示**，而不是显示一个 0。
const readFollowState = async () => {
  if (!buyerId) return
  try {
    const st = await followState(buyerId, shopId)
    if (st.success) {
      following.value = !!st.following
      followers.value = Number(st.followers) || 0
    }
  } catch (e) {
    // ⚠️ **不弹窗**：少了关注状态只是**少一条信息**，弹一次是打扰（同 `handleApiError` 对
    //    `UnauthorizedError` 的取舍）。⚠️ 401 仍会走全局出口（`http.js`），这里不重复处理。
    console.error('关注状态读取失败', e)
  }
}

const toggleFollow = async () => {
  if (!buyerId) {
    // 未登录的出口：照 `ProductPage.consultProduct` 的**既有形态**逐字（含那句只在
    // `!isLogin` 时的跳转 —— 已登录但取不到业务号的账号不该被踢去登录页）。
    alert('请先登录'); if (!isLogin.value) router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (busy.value) return
  busy.value = true
  try {
    const r = following.value ? await unfollow(buyerId, shopId) : await follow(buyerId, shopId)
    if (!r.success) {
      alert(r.msg || (following.value ? '取关失败' : '关注失败'))
    } else {
      // 写侧也**回读后端答的那一份**（三个端点都回 `following` / `followers`），不自己翻布尔。
      following.value = !!r.following
      followers.value = Number(r.followers) || 0
    }
  } catch (e) {
    handleApiError(e, '关注操作失败')
  } finally {
    busy.value = false
  }
}

// 客服 —— 与 `ProductPage.consultProduct` **同一形态**（同一端点、同一套参数、同一落点、同一
// 两道守卫），只有 `content` 不同：那一页手里有「当前选中的型号」可报，本页是**店铺级**入口，
// 没有商品可报 ⇒ 发一句**素话**。⚠️ 别为了「像」而伪造一个 `[PROD]` 前缀：`ChatRoom.vue` 认这个
// 前缀会去 `JSON.parse` 后面那段并渲染成商品卡 —— 本页编一个，只会让店家点开看到一件不存在的商品。
const consultShop = async () => {
  if (!buyerId) {
    alert('请先登录'); if (!isLogin.value) router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (buyerId.toLowerCase() === shopId.toLowerCase()) {
    alert('不能咨询自己的店铺')
    return
  }
  const params = new URLSearchParams()
  params.append('fromId', buyerId)
  params.append('fromType', getCustomerId() ? 'customer' : 'merchant')
  params.append('toId', shopId)
  params.append('toType', 'merchant')
  params.append('content', '你好，我想咨询一下')
  try {
    const res = await http.post(endpoints.message.send, params.toString())
    const data = await res.json()
    if (data.success) {
      const basePath = getMerchantId() ? '/merchant' : '/customer'
      router.push({
        path: basePath,
        query: { page: 'chat', otherId: shopId, otherType: 'merchant', _t: Date.now() }
      })
    } else {
      alert('咨询发送失败：' + data.msg)
    }
  } catch (err) {
    console.error(err)
    alert('网络错误，请稍后重试')
  }
}

// ⚠️ 比 `ProductPage.goBack` **少一支**：那一支是 `route.query.from === 'search'` 的回程，
// 本页没有搜索入口 ⇒ 那条 `from` 永远不会有值。
const goBack = () => { router.push(getMerchantId() ? '/merchant' : '/customer') }

const goUserCenter = () => {
  const isMerchant = getMerchantId() && getMerchantId() !== 'null'
  const isCustomer = getCustomerId() && getCustomerId() !== 'null'
  if (isMerchant) {
    router.push('/merchant?page=user')
  } else if (isCustomer) {
    router.push('/customer?page=user')
  } else {
    router.push('/login')
  }
}

onMounted(loadShop)
</script>

<style scoped>
.shop-page {
  width: 100%;
  min-height: 100vh;
  background: var(--bg);
  box-sizing: border-box;
  font-family: var(--font);
}
/* 控件一律继承页面字体 —— `tokens.css` **刻意不设** `body` 的 `font-family`，
   而浏览器 UA 样式表会给 `button` / `input` 打上自己的 `font` ⇒ 不写这两行，全页按钮会掉出 `--font`。
   落法同 `ProductPage.vue` / `GoodsUp.vue` / `GoodsAdd.vue` 三处先例。 */
button, input, select { font: inherit; color: inherit; }
button { background: none; border: 0; cursor: pointer; }

/* ── 自带顶栏（与 `ProductPage.vue` **逐字同款**，只少那个搜索框，理由见模板头注）────────── */
.nav-bar { display: flex; align-items: center; gap: var(--s4); height: 60px; padding: 0 var(--s7);
           background: var(--surface); border-bottom: 1px solid var(--line); }
.nav-back { font-size: var(--fs-md); color: var(--ink-2); padding: 0 var(--s2); white-space: nowrap; }
.brand { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-semi);
         color: var(--brand-ink); white-space: nowrap;
         /* 全仓没有全局 `a` 规则 ⇒ `<router-link>` 渲染出的 `<a>` 默认带下划线，必须自报 */
         text-decoration: none; }
.nav-right { margin-left: auto; color: var(--nav-tx); font-weight: var(--fw-med); cursor: pointer; white-space: nowrap; }

/* ── 店铺头卡：整幅横带 ─────────────────────────────────────────────────────── */
.band { background: var(--zone); border-bottom: 1px solid var(--line); }
.band-in { max-width: 1180px; margin: 0 auto; padding: var(--s4) var(--s5) var(--s5); }
.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--ink-3); }
.crumb a { color: inherit; text-decoration: none; }
.crumb a:hover { color: var(--brand-ink); }
.crumb .sp { font-style: normal; padding: 0 var(--s2); }

.hd { margin-top: var(--s3); display: flex; align-items: center; gap: var(--s4); }
.hd-mark { width: 56px; height: 56px; flex: 0 0 auto; border-radius: var(--r2); background: var(--brand-soft);
           color: var(--brand-ink); font-family: var(--font-display); font-size: var(--fs-lg);
           display: flex; align-items: center; justify-content: center; }
.hd-id { min-width: 0; }
/* ⚠️ 用 display 字族的元素一律**显式给 `line-height`**：`--font-display` 的 LXGW 楷体在本仓
   **刻意没有 `@font-face`**（见 tokens 那块），字族一换、行高就跟着变 ⇒ 不写就会与旁边
   同号的 `--font` 文本差 3px 左右，而这类差只有量出来才看得见。 */
.hd-nm { display: block; font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med);
         line-height: 1.3; }
.hd-sub { display: block; margin-top: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }
.hd-sub b { color: var(--ink-2); font-weight: var(--fw-med); font-variant-numeric: tabular-nums; }
.hd-ops { margin-left: auto; display: flex; gap: var(--s2); flex: 0 0 auto; }

.act { height: 34px; padding: 0 var(--s4); border: 1px solid var(--line-2); border-radius: var(--r1);
       font-size: var(--fs-xs); color: var(--ink-2); white-space: nowrap;
       transition: background .15s, border-color .15s, color .15s; }
.act:hover:not(:disabled) { border-color: var(--ink-3); color: var(--ink); }
.act:disabled { color: var(--ink-3); cursor: default; }
/* 「关注」= 头卡里唯一一处实心块 —— 「把胆量花在一个地方、其余保持安静」。
   未关注 = 实心 `--brand` + 白字（同 `FriendRoom.vue` 的 `.btn-solid`）；
   ⚠️ 「已关注」**退回描边**、不保持实心：它是个**状态**，不再是一次邀请 ——
   一个常亮的实心按钮会一直喊「点我」，而这里已经没有可做的动作。
   ⚠️ 两态都**不吃** `--accent`（朱砂在本仓只负责价格 / 促销 / 未读）。
   ⚠️ 「全页唯一实心块」那句**已收窄为「头卡里唯一」**：热销榜**榜首那根柱**也用实心
      `--brand`（见 `.col-bar-hero`）。两者不冲突的理由写在那一处 —— 柱是**数据标记**，
      没有任何可点的暗示；而这条纪律要管的是**控件**之间抢注意力。 */
.act-on { background: var(--brand); border-color: var(--brand); color: #fff; }
.act-on:hover:not(:disabled) { background: var(--brand-hover); border-color: var(--brand-hover); color: #fff; }

/* ── 头卡事实行（认证 / 主营 / 开店 / 累计售出）───────────────────────────────
   ⚠️ 靠**间距**分组，不用 `·` 也不用竖线分隔：竖线是另一种「拼串」，同样会把四件不相干的事
   说成一句。每项自带标签（`i`）与值（`b`）两个色档 ⇒ 扫一眼能分出「哪一半是标签」。 */
.facts { margin-top: var(--s4); padding-top: var(--s3); border-top: 1px solid var(--line);
         display: flex; flex-wrap: wrap; align-items: center; gap: var(--s2) var(--s5); }
.fact { font-size: var(--fs-xs); white-space: nowrap; }
.fact i { font-style: normal; color: var(--ink-3); margin-right: var(--s1); }
.fact b { color: var(--ink-2); font-weight: var(--fw-med); font-variant-numeric: tabular-nums; }
/* 四项全空时的那一句（用户新规：数据不足 → 占位，不静默消失）。比照实项再浅一档 ——
   它说的是「这里本该有东西」，不是信息本身。 */
.fact-none { font-size: var(--fs-xs); color: var(--ink-3); }
/* 认证徽章 —— 「已核验」的一枚标记，不是装饰。
   ⚠️ 只在 `CertStatus === 'approved'` 时才会被渲染出来（判据在 `<script>` 的 `certified`）。 */
.fact-cert { display: inline-flex; align-items: center; gap: var(--s1); height: 22px; padding: 0 var(--s3);
             border-radius: var(--r1); background: var(--success-ground); color: var(--success);
             font-size: var(--fs-xs); font-weight: var(--fw-med); white-space: nowrap; }
.fact-ic { width: 14px; height: 14px; }

.wrap { max-width: 1180px; margin: 0 auto; padding: var(--s6) var(--s5) var(--s8); }
.sec-h { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med); line-height: 1.3; }
/* 栏题右侧那半句说明（「别家的电子用品」）—— 它给的是这一块的**判据**，不是装饰 ⇒
   用最浅一档灰、字号降到 `--fs-xs`，别跟栏题抢。 */
.sec-note { margin-left: var(--s2); font-style: normal; font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3); }

/* ── 分块间距 ────────────────────────────────────────────────────────────────
   ⚠️ 用**相邻兄弟**（`+`）而不是 `.blk { margin-top }`：第一块不该在 `.wrap` 的顶内边距之上
   再叠一层（那样首块会比设计稿低 48px）。块间距（48）> 块内间距（24）⇒ 一眼能看出分了几块。
   ⚠️ 丙方案下只剩三块（券 / Tab 区 / 猜你喜欢）⇒ 相邻关系只有两处。 */
.blk + .blk { margin-top: var(--s7); }

/* ── 三 · Tab 条 ─────────────────────────────────────────────────────────────
   ⚠️ 形制选的是**下划线式**，不是胶囊 / 分段控件：
     · 胶囊（药丸圆角）落在 `tokens.css:36` 那几条「不做」的射程内（统一大圆角）；
     · 分段控件要在外面再套一层带底的容器 ⇒ 又一种「容器套容器」，
       而这一页刚刚才把「纵向堆三块、各配一条栏题」的容器感减下去，不该在这里加回来。
   ⚠️ 指示条是**底边**那 2px（贴着 tab 自己的左右缘），不是侧边强调条 —— `tokens.css:37` 禁的是
      「卡片 / 提示左缘那根竖条」，不是 tab 的选中态。
   ⚠️ 整条横线（`border-bottom`）铺满内容宽、选中的那一格把自身的 2px 压在它上面
      （`bottom: -1px`）⇒ 选中态是「接上了这条线」，不是「浮在线上」。 */
.tabs { margin-top: var(--s4); display: flex; gap: var(--s6); border-bottom: 1px solid var(--line); }
.tab { position: relative; padding: var(--s3) 0; font-size: var(--fs-sm); color: var(--ink-3);
       white-space: nowrap; transition: color .15s; }
.tab:hover { color: var(--ink-2); }
.tab-on { color: var(--ink); font-weight: var(--fw-med); }
.tab-on::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px;
                 height: 2px; background: var(--brand); }

/* ── 占位（用户新规：数据不足 → 占位，不静默消失）─────────────────────────────
   ⚠️ 一句**素话**，不套框、不配色、不加图标：占位是「这里本该有东西」的**说明**，
   不是内容本身 —— 给它一个卡框，就等于把「没有」画成了「有」。
   ⚠️ 文案一律**据实**（见 `<script>` 的 `phText` 与模板三处占位）：
   「正在读取…」≠「没有」，「登录后可领」≠「本店没有券」。 */
.ph { margin-top: var(--s5); font-size: var(--fs-sm); color: var(--ink-3); }
.ph-link { margin-left: var(--s3); color: var(--brand-ink); text-decoration: none; }
.ph-link:hover { text-decoration: underline; }

/* ── 二 · 本店优惠券 ─────────────────────────────────────────────────────────
   与主页「领券中心」的 `.tk` 同一个视觉语言（`--surface` 底 + 1px `--line` + `--r2`），
   但**不共用那份 CSS**：那边是「横向条 + 越位渐隐 + `》` 展开」的版式，这里最多 3 张、
   且是本店专属 ⇒ 直接换行铺开，不需要裁切那套。 */
.cpns { margin-top: var(--s5); display: flex; flex-wrap: wrap; gap: var(--s3); list-style: none; }
/* ⚠️ `flex: 1 1 260px` —— 260 是「面额 + 券名 + 门槛 + 领取」四段不换行的最小宽（实测本页券名
   最长那几张）；`flex-grow: 1` 让三张铺满 1180px 的版面、不足三张也不留一条空尾巴。
   ⚠️ **`max-width: 380px` 不能省** —— 只有 1 张券时（演示店就是这样）不加它这张卡会**拉满整行
   1180px**：面额与券名挤在左端、领取按钮被顶到 1100px 外，中间一大片空。380 × 3 + 2 个 `--s3`
   = 1164 ≤ 1180 ⇒ 满三张时仍然铺得下。 */
.cpn { flex: 1 1 260px; max-width: 380px; min-width: 0; display: flex; align-items: center; gap: var(--s3);
       padding: var(--s3) var(--s4); background: var(--surface); border: 1px solid var(--line);
       border-radius: var(--r2); }
/* 面额 —— 券卡上唯一的主数字，用 `--font-mono` + `--accent`（朱砂在本仓负责价格 / 促销，
   券的面额就是促销）。⚠️ 显式 `line-height`：`--font-mono` 与本页正文不同族（同 `.hd-nm` 那条）。 */
.cpn-face { flex: 0 0 auto; min-width: 4.5em; font-family: var(--font-mono); font-size: var(--fs-md);
            font-weight: var(--fw-semi); line-height: 1.3; color: var(--accent);
            font-variant-numeric: tabular-nums; }
.cpn-tx { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
/* 券名是**用户填的**（管理端建券表单不限长）⇒ 单行截断，别把卡撑宽、把「领取」顶出画面。 */
.cpn-name { font-size: var(--fs-xs); font-weight: var(--fw-semi); line-height: 1.2; color: var(--ink-2);
            overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cpn-cond { font-size: var(--fs-xs); line-height: 1.2; color: var(--ink-3);
            overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cpn-get { flex: 0 0 auto; height: 28px; padding: 0 var(--s4); border: 1px solid var(--brand-ink);
           border-radius: var(--r1); font-size: var(--fs-xs); color: var(--brand-ink);
           background: var(--brand-soft); white-space: nowrap; }
.cpn-get:disabled { color: var(--ink-3); background: var(--surface-3); border-color: var(--line-2); cursor: default; }

/* ══ 三-a 热销：**2-1-3 山峰式**竖直柱状图 ═══════════════════════════════════
   用户定稿：「只显示 Top3」·「竖直柱状图，居中排布」·「2-1-3 山峰式」·「图片圆头」·
   「点柱 → 进商品」·「柱底共基线、数值骑柱顶」。
   竖向柱：**X 轴 = 商品、Y 轴 = 销量**（横条是「横向长度代表销量」，那是另一种图）。

   ⚠️ 每一列的**自下而上**是：柱 → 圆形商品图 → 数值。柱底**共一条 0 基线**，柱高同一个
      比例尺 ⇒「山峰」是**数据长的**，不是画上去的台阶。

   ⚠️ **图表规范的取材**（本笔联网查的，逐条对上，来源见交付说明）：
      · 「Always start the value axis at zero」⇒ 比例尺从 0 起（`barPct` 除以榜首，不是插值）
      · 「Columns → value on the cap」⇒ 数值在**柱顶上方**，不写在柱子正中
      · 「slight rounding on top only」4px、基线方角 ⇒ `--r1 --r1 0 0`
      · 「Rounding the top of a bar **suggests the value is rounded**」⇒ 圆的只有**商品图**，
        柱子本身仍是直边；数字给的是精确值，两者不冲突
      · 「Order bars by value」⇒ 降序（本笔再按用户的 2-1-3 重排**版式**，名次不动）
      · 「reserving a highlight color for a hero bar」+「text should never wear the data color」
        ⇒ 榜首那根走 `--brand`，其余 `--surface-3`；数值一律 `--ink-2`
      · 轴 / 标签走既有 token：基线 `--axis-line`、名次 `--ink-3`、名 `--ink-2`

   ⚠️ `.chart` 用 `fit-content` **不是**铺满 1180，再加 `margin: … auto` **居中**（用户
      「居中排布」）：三根柱铺满 1180 会让柱间空得能跑马，而居中的一峰两肩本来就该窄。
      `grid-auto-columns: 184px` 固定列宽 ⇒ 柱与柱的间距**不随柱数变化**。
      ⚠️ 184 这个数是**量出来的、不是拍的**：`.col-foot` 的内容宽 = 184 − 2×`--s3`(12) = 160，
        再让给名次（`--fs-xs` 的「1」≈ 8px）+ `--s2` 间距 ⇒ 商品名拿到约 **146px**，
        够「无线蓝牙耳机 声谷标准版」这种最长的名字**两行排完**且不把「标准版」切成「标/准版」。
        （168 + `--s4` 内边距时名宽只有 ~122px ⇒ 截图里「声谷标准版」与「65W 双口」
        都被断在半个词上。）3 根柱时 3×184 + 2×24 = 600 ≤ 1180，居中后左右各留一片空。 */
.chart { --chart-h: 340px; margin: var(--s5) auto 0; position: relative; width: fit-content;
         display: grid; grid-auto-flow: column; grid-auto-columns: 184px; column-gap: var(--s5); }
/* 0 基线：**贯穿整行**的一条 1px。⚠️ 不能逐列各画一段 —— 列间有 `column-gap`，逐列画会在
   gap 处断成虚线，而「所有柱从同一条 0 线长出来」正是这张图要说的第一件事。
   位置 = 图区高度：`.col-plot` 的 border-box 高就是 `--chart-h` ⇒ 基线正好压在它底边上。 */
.chart::after { content: ''; position: absolute; left: 0; right: 0; top: var(--chart-h);
                height: 1px; background: var(--axis-line); }
/* 整列 = 一个链接（`<a>` 内不得嵌交互内容 ⇒ 里面全是 `<span>`，见模板注释）。 */
.col { display: flex; flex-direction: column; color: inherit; text-decoration: none; }
/* 图区：**底对齐**的弹性列 ⇒ 整组（数值 + 圆图 + 柱）贴着基线往上长。
   ⚠️ `--chart-h` 必须是**确定高度**：柱的 `height: N%` 是按它算的（百分比要有确定的父高）。 */
.col-plot { height: var(--chart-h); display: flex; flex-direction: column;
            justify-content: flex-end; align-items: center; padding: 0 var(--s4); }
/* 数值 —— 骑在柱顶。⚠️ `--font-mono` + `tabular-nums`：三根柱的数字要能纵向对得上；
   ⚠️ 颜色是 `--ink-2` **不是**柱色：图表规范写明「文字不该穿数据的颜色」。
   ⚠️ 显式 `line-height`：等宽族与本页正文不同族（同 `.hd-nm` / `.cpn-face` 那两处）。 */
.col-val { flex: none; margin-bottom: var(--s2); font-family: var(--font-mono); font-size: var(--fs-sm);
           font-weight: var(--fw-med); line-height: 1.2; color: var(--ink-2);
           font-variant-numeric: tabular-nums; }
/* 圆形商品图（用户素材里的「圆图 / 图片圆头」）—— 直径 = 柱宽 ⇒ 它读起来是**这根柱的圆头**，
   而不像一张浮在旁边的缩略图。⚠️ 1px 发丝边不能省：图缺失时圆是 `--img` 灰底，
   而柱子是 `--surface-3`，两个灰挨着会糊成一块。 */
.col-cap { flex: none; margin-bottom: var(--s2); width: 96px; height: 96px; border-radius: 50%;
           overflow: hidden; background: var(--img); border: 1px solid var(--line); }
.col-cap img { width: 100%; height: 100%; object-fit: cover; }
/* 柱 —— 顶部 4px 圆角、**基线方角**（图表规范的「slight rounding on top only」）。
   高度由 JS 按比例尺算好（`barPct`），圆心对齐基线。 */
.col-bar { flex: none; width: 96px; border-radius: var(--r1) var(--r1) 0 0; background: var(--surface-3);
           transition: background .15s; }
/* 榜首那根 —— 全页**第二处**实心 `--brand`（第一处是头卡的「关注」按钮）。
   ⚠️ 判据是 `rank === 1` **不是下标 0**：2-1-3 山峰式下，名次 1 的那根**在中间**
      （见 `<script>` 的 `hotCols`）。按下标写会把 hero 色画到「左边第一根」上。
   ⚠️ 为什么不算破坏「一个实心块」那条纪律：那条管的是**控件**之间抢注意力（按钮喊「点我」），
      柱是**数据标记**、没有任何可点的暗示；而图表规范本身也建议给「hero bar」一个高亮色。
      两者在截图里是否真的互相压住，是**量出来的**（`--brand` = `#3b7966` 低饱和墨绿、
      「关注」是右上角 34px 高的描边小按钮）—— 实测**不打架**。 */
.col-bar-hero { background: var(--brand); }
.col:hover .col-bar { background: var(--line-2); }
.col:hover .col-bar-hero { background: var(--brand-hover); }
/* 轴下方：名次 + 商品名。
   ⚠️ 左内边距 `--s3` **故意比 `.col-plot` 的 `--s4` 小一档**（不是为了对齐，是为了给名字让宽）：
   列宽固定 184 ⇒ 每一像素都是从商品名身上拿的，而轴下这行是全图**唯一需要排中文**的地方。
   两行截断（`.col-nm`）兜住更长的名字 —— 名字不换行 / 不省略号，因为「标准版」被吃掉
   会让人分不清是哪一件（那件 299 的耳机有两行同码记录，名字正是唯一的区分）。 */
.col-foot { display: flex; align-items: baseline; gap: var(--s2); padding: var(--s3) var(--s3) 0; }
/* 名次 —— 一枚最轻的数字。⚠️ 不吃 `--accent`：朱砂管价格 / 促销 / 未读，名次不是那三类。
   ⚠️ 写的是 `rank`（事实），不是渲染序下标（版式）—— 见 `hotCols` 那条注释。 */
.col-no { flex: none; font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-3);
          font-variant-numeric: tabular-nums; }
.col-nm { font-size: var(--fs-xs); line-height: 1.4; color: var(--ink-2);
          display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.col:hover .col-nm { color: var(--ink); }

/* ── 三-b 分类筛选 chip（**只在 ≥2 个类目时渲染**，理由见模板那条注释）────────────────
   选中态走 `--brand-soft` + `--brand-ink` **不是**实心 `--brand` —— 实心留给了「关注」与
   榜首那根柱（见那两处注释）；chip 再抢一个，那两处就没力了。 */
.chips { margin-bottom: var(--s5); display: flex; flex-wrap: wrap; gap: var(--s2); }
.chip { height: 30px; padding: 0 var(--s4); border: 1px solid var(--line); border-radius: var(--r1);
        background: var(--surface); font-size: var(--fs-xs); color: var(--ink-2);
        white-space: nowrap; transition: border-color .15s, background .15s, color .15s; }
.chip:hover { border-color: var(--line-2); color: var(--ink); }
.chip-on { background: var(--brand-soft); border-color: var(--brand-ink); color: var(--brand-ink); font-weight: var(--fw-med); }
/* chip 上的件数 —— 比 chip 文案再浅一档（它是量词，不是类别本身）。 */
.chip-n { font-style: normal; color: var(--ink-3); font-variant-numeric: tabular-nums; }
.chip-on .chip-n { color: var(--brand-ink); }

/* ── 三-b 三态（载入 / 报错 / 空）────────────────────────────────────────────
   空态是**一次邀请**（给一个真能走的去处），不是一句情绪；报错说清是什么没成、
   并把重试放在手边，不道歉也不含糊。 */
.note { margin-top: var(--s5); font-size: var(--fs-sm); color: var(--ink-3); }
.note-err { color: var(--danger); }
.note-empty p { margin-bottom: var(--s3); }
.retry { display: inline-block; height: 32px; line-height: 30px; padding: 0 var(--s3);
         border: 1px solid var(--line-2); border-radius: var(--r1); font-size: var(--fs-xs);
         color: var(--ink-2); text-decoration: none; vertical-align: middle; }
.retry:hover { border-color: var(--ink-3); color: var(--ink); }
.note-err .retry { margin-left: var(--s2); }

/* ── 三-b / 三-c / 四 商品格 —— `.cards` 是**栅格**（版面），`.card` 本体已搬进
   `src/components/ProductCard.vue`。
   ⚠️ 自「商品卡收成一个组件」那一笔起这一段的注释不再说「与 `ProductPage.vue` 的 `.card`
   同一套形制」—— 那句话改前是**错的**（改前实测：本页卡 1/1 图、无框、整值价；主页卡 4/3 图、
   有框、三段价），收成一件之后「三处一致」是**结构**保证的，不靠注释声明。
   ⚠️ 猜你喜欢「**全宽铺开**」= 这一条栅格（4 列铺满 `.wrap` 的 1180px），不是铺到视口边。 */
.cards { margin-top: var(--s5); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s5); }
/* ⚠️ chip 行在时它自己已经带了 `margin-bottom`，栅格的上边距就不该再叠一层（否则第一排
   商品被顶低 24px）。用**相邻兄弟**判，不靠「有没有 chip」的 if。 */
.chips + .cards, .chips + .note { margin-top: 0; }
/* tab 面板本身**不设边距** —— 让里面各块自己的 `margin-top` 说话（`.cards` / `.chart` / `.ph`
   各有一套），少一层容器就少一处要对齐的间距。 */

/* ── 一级 tab（`本店商品` / `平台活动`，2026-10-12 用户裁定 ④）────────────────────
   ⚠️ 规则写在**文件尾**是**刻意的**：本文件有 8 条 refs token，其中 `:872/:875` 就在下面
      这段 `<style>` 里 ⇒ 新规则落在**最大被引行之后**，一个都不漂（同上一条注释的同一笔账）。
   ⚠️ 形制与二级 `.tabs` **有意不同**：那一套是 `--fs-sm` + **整条 `border-bottom`**（选中格把
      自己的 2px 压在那条线上）；这一套升到栏题字号（`--fs-lg` + `--font-display`）、**不画通栏横线**，
      选中态是**自己脚下那 2px**。两级 tab 若长得一样，读起来会是「六个平级的 tab」。
   ⚠️ 下边距取 `--s7`（48）：与 `.blk + .blk` 的块间距同值 ⇒ 一级 tab 之下仍是一套块节奏。 */
.pact-tabs { display: flex; gap: var(--s6); margin-bottom: var(--s7); }
.pact-tab {
  position: relative;
  padding: 0 0 var(--s3);
  border: 0;
  background: none;
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  line-height: 1.3;
  color: var(--ink-3);
  cursor: pointer;
}
.pact-tab:hover { color: var(--ink-2); }
.pact-tab-on { color: var(--ink); }
.pact-tab-on::after { content: ""; position: absolute; left: 0; right: 0; bottom: 0; height: 2px; background: var(--brand); }
</style>
