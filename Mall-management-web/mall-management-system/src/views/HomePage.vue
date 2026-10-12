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
         ⚠️ 6 项**本仓都没有路由** ⇒ 原一律 `disabled` + `title`（`progress.md:1652` 判据①）。
         ⚠️ 已接通 **2 项**：**「领券中心」**（C3 块 5 · commit 1，2026-10-11，用户批《b5-placeholder-landing.md》§一 #2「接通（锚到券包区）」）⇒ 摘 `disabled`、锚到本页 `.tickets`；**「今日秒杀」**（本块 commit 5，2026-10-12）⇒ 摘 `disabled`、改 `<router-link to="/seckill">`（秒杀页 commit 4 已落地，**不再**是「预留位」；它**不走** `emit('go-search', …)` 那条路 —— 秒杀有自己的页）。**另 4 项仍是 `disabled`**（全部商品分类 / 品牌专区 / 会员中心 / 客户服务，归属仍为 `<待定>`）。开放剩余项属**功能**笔，不在 D 笔边界内。
         登记见 `docs/agent/slot-registry.md`「commit 3 · 稿外死控件」。 -->
    <nav class="subnav">
      <div class="inner">
        <button type="button" class="now" disabled title="预留 · 全部商品分类（无落点：分类检索现走搜索页 GoodsSelect 的左分类树）">全部商品分类</button>
        <router-link class="hot" to="/seckill" title="今日秒杀 —— 落到秒杀页（C3 块 5 · commit 5 接线；此前是「预留位」）">今日秒杀</router-link>
        <button type="button" class="hot" @click="scrollToTickets" title="领券中心 —— 锚到本页领券中心（C3 块 5 commit 1 接线；2026-10-11 信息架构重排后本页**只留领券中心**，券包已搬去用户中心弹窗）">领券中心</button>
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

      <!-- ── 功能入口条：**已删**（用户 2026-10-12 指令）──
           删前它是「我的订单 / 浏览记录 / 在线客服」三格 `disabled` 死控件（稿 `主页改造.html:787-804`，
           2026-10-07 `88093e3` 落的四格，第四格「优惠券」2026-10-11 已删）—— 剩下的三格落在
           `repeat(4, 1fr)` 里，**右边空一整格**（实测 `tailGap: 346px`）⇒ 看着像没做完的东西。
           ⚠️ **不要照稿把它加回来**：稿上有不等于本产品要，用户已明确不要。
           证据 `docs/agent/evidence/c3-block5/{before,after}-home-quickbar.png`。
           ⚠️ `scrollToTickets` **保留**（`:32` 二级导航「领券中心」仍在用）—— 删的是这一块，不是那条跳转。 -->

      <!-- ── 限时秒杀（**C3 块 5 · commit 4 转真**，2026-10-12；原为稿 `:807-814` 的预留位）──
           ⚠️ **只在拉到活动时才画**：`/marketing/seckill/live` **不在白名单**，匿名发它必 401、`handleUnauthorized` 会把公开页整体踢去登录页 ⇒ 未登录整块不出现，`data-slot="预留 · 秒杀位"` 随之撤掉（**收口时 `slot-registry.md` 那条要改判**）。倒计时读**后端的 `EndAt`**（`DATE_FORMAT` 出的是 **UTC** 文本 ⇒ 解析要补 `Z`；稿 `:966` 自注「不要写死」即此）—— `Stage` 由后端按**库时钟**现算：`live` 显秒杀价、`upcoming` 显「活动未开启」。 -->
      <section v-if="flashRows.length" class="flash" title="C3 块 5〈营销块〉commit 4：读 /marketing/seckill/live（封装 src/api/marketing.js 的 seckillLive）· 后端已挡「已结束 / 已停用 / 商品下架」· Stage 由后端按库时钟现算 ⇒ 原 data-slot「预留 · 秒杀位」退场">
        <div class="flash-h">
          <h2>限时秒杀</h2>
          <span class="cd">{{ flashCdLabel }} <b>{{ flashCd.h }}</b>:<b>{{ flashCd.m }}</b>:<b>{{ flashCd.s }}</b></span>
          <router-link class="more" to="/seckill">全部秒杀 →</router-link>
        </div>
        <div class="flash-g">
          <router-link v-for="r in flashRows" :key="r.ActivityId" class="flash-c" :to="flashLink(r)" @click="onCardNav">
            <img v-if="r.GoodImg" :src="r.GoodImg" :alt="r.Name || r.GoodName" />
            <span class="fc-nm">{{ r.Name || r.GoodName }}</span><span v-if="r.Stage === 'live'" class="fc-pr">¥{{ flashMoney(r.SeckillPrice) }}</span><span v-else class="fc-soon">活动未开启</span>
          </router-link>
        </div>
      </section>

      <!-- ── 领券中心（**块 5**；2026-10-11「券的信息架构重排」后本页**只剩这一栏**）──
           原为「预留 · 券包位」（稿 `:817` 的**空** section，3 张券由稿的 JS 塞 mock `TICKETS`）。
           commit 1 时这里是**两栏**（左「领券中心」+ 右「我的券包」）—— 用户 2026-10-11 重排后
           **右栏整块删掉**：券包是「我的东西」，归**用户中心**（`views/UserInfoPage.vue` 的「优惠券」格
           → `components/CouponWalletDialog.vue`），主页只回答「**现在有什么新券可以领**」。
           ⇒ 左栏收窄成「**本周新发布的券**」（`thisWeek=1`，后端 `CouponDao.THIS_WEEK`）。

           ⚠️ **「本周新」不是另一条查询**：它是**同一个 `SELECT` 加一个 `AND`**（用户裁定「甲」）
              ⇒ 主页这批天然是用户中心「可领」tab 的**子集**，不会有两个真相。
              见 `src/api/marketing.js` 里 `coupons()` 的第三参。
           ⚠️ **排序不在这里**：「最早更新最左」由后端 `CouponDao.selectClaimable` 的 `updated_at ASC`
              定好 ⇒ 本页**按数组原序渲染**，别自己再排一遍。
           ⚠️ `Claimed` / `SoldOut` / `IsNew` 的形态可能是 `true` 也可能是 `1`（Connector/J `tinyInt1isBit`）
              ⇒ 判据走 `truthy()`（已搬去 `config/coupon-format.js`），别写 `=== true`。
           ⚠️ **核销不在这里**：券 `unused → used` 发生在结算（`orderAdd` 带 `couponId`，块 5 commit 5）
              ⇒ 券包的四个状态在**用户中心那个弹窗**里看，本页不碰。 -->
      <section
        class="tickets"
        ref="ticketsEl"
        title="C3 块 5〈营销块〉（2026-10-11 信息架构重排）：本页**只**是「本周新券」的领券中心 —— /marketing/coupons?thisWeek=1、领券 /marketing/coupon/claim、一键领取本周全部 /marketing/coupon/claimBatch（封装 src/api/marketing.js）。我的券包已搬去**用户中心 · 优惠券**（弹窗，四态 tab）"
      >
        <!-- 领券中心（本周新券）—— 来自 `/marketing/coupons?thisWeek=1`，含「我已领 / 已领完 / 近 3 天新建」三个布尔。 -->
        <div class="tk-col">
          <div class="tk-h">
            <h3>领券中心</h3>
            <div class="tk-h-r">
              <!-- 区块级一行小字：**本周还剩几天**（用户 2026-10-11 裁定 ⑤：**整日粒度**、**区块级**，
                   不是每张卡的有效期 —— 那是弹窗里的「X 天后过期」）。
                   ⚠️ 数字来自**后端** `daysLeft`（`CouponDao.selectWeekWindow`，由**库时钟**算）——
                      前端**不碰浏览器钟**：`new Date()` 在这里没有判据地位（同 TD-32）。
                   ⚠️ 它是**周窗口**、与「还有几张可领」无关 ⇒ 0 张时也照显示（那就是「本周快过完了」）。 -->
              <span v-if="couponAccount && daysLeft !== null" class="tk-left">本周还剩 {{ daysLeft }} 天</span>
              <span class="tk-n">{{ couponAccount ? claimable.length + ' 张' : '未登录' }}</span>
              <!-- 「一键领取本周全部」（用户 2026-10-11 裁定 ②：**真做**，取代原来那颗 disabled 占位）。
                   ⚠️ **不传券 ID 列表** —— 领哪些由**服务端**定（「本周」是服务端语义，`CouponDao.THIS_WEEK`）
                      ⇒ 前端手里那份列表可能已经旧了，也不该把「本周」的定义抄到前端来。
                   ⚠️ **不弹确认**（同日裁决）—— 券「领了就有」、没有损失，直接领 + 汇总提示。
                   ⚠️ 只在**有得领**时可用（一张都没得领就没什么好「一键」的）⇒ 见 `tkClaimable`。
                   ⚠️ 汇总文案走 `.tk-msg`（**不是** `alert`）：顶层 `success:true` ≠ 全成功，
                      要按 `claimed`/`failed` 说人话（见 `api/marketing.js` 里 `claimBatch()` 的 ⚠️）。 -->
              <button
                v-if="couponAccount"
                type="button"
                class="tk-all"
                :disabled="batchBusy || tkClaimable === 0"
                :title="tkClaimable === 0 ? '本周没有可领的新券' : '一键领取本周新发布、且我还没领过的券（' + tkClaimable + ' 张）'"
                @click="doClaimBatch"
              >{{ batchBusy ? '领取中…' : '一键领取本周全部' }}</button>
              <!-- 「》」查看更多（用户 2026-10-11 手验 ①：**去掉细滚动条**，改成「越位渐隐 + 右侧 ›」）。
                   只在**真被裁掉**时出现（`> TK_VISIBLE` 张）—— 5 张以内它没有存在的理由。
                   点一下 = 展开全部（`.tk-strip-on` 把 `nowrap` 放开成 `wrap`），再点收起。
                   ⚠️ 与左边「一键领取」**并排**（用户 ②：两个都要在右侧按钮区），故同放在 `.tk-h-r` 里。 -->
              <button
                v-if="couponAccount && claimable.length > TK_VISIBLE"
                type="button"
                class="tk-more"
                :class="{ 'tk-more-on': tkExpanded }"
                :title="tkExpanded ? '收起（只留前 ' + TK_VISIBLE + ' 张）' : '展开看全部 ' + claimable.length + ' 张'"
                @click="tkExpanded = !tkExpanded"
              >{{ tkExpanded ? '《' : '》' }}</button>
            </div>
          </div>
          <p v-if="!couponAccount" class="empty-tip">登录后可领取优惠券</p>
          <p v-else-if="claimLoading" class="empty-tip">加载中…</p>
          <!-- 空态（用户裁定 ③ 的文案）：说清楚「本页只放本周新的」，并**指一条去路** ——
               用户中心的「优惠券 → 可领」tab（那是全量可领，含旧券）。空态最怕只报「没有」。 -->
          <p v-else-if="claimable.length === 0" class="empty-tip">本周暂无新券 —— 去用户中心的「优惠券 · 可领」看全部可领券</p>
          <!-- ③ 横向券条：从左到右铺开（顺序由后端 `CouponDao.selectClaimable` 按 `updated_at ASC` 定好
               ⇒ **本页不排序**，见 `src/api/marketing.js` 里 `coupons()` 的 ⚠️）。 -->
          <ul
            v-else
            class="tk-strip"
            :class="{ 'tk-strip-cut': claimable.length > TK_VISIBLE && !tkExpanded, 'tk-strip-on': tkExpanded }"
          >
            <li v-for="c in claimable" :key="c.CouponId" class="tk">
              <!-- ⚠️ 面额与 NEW 角标**包一层**（`.tk-top`）：券卡是竖排 `column` 的
                   （`.tk-strip > .tk`），不包的话角标会被顶到下一行、把卡撑高。 -->
              <span class="tk-top">
                <span class="tk-amt">{{ couponFace(c) }}</span>
                <!-- NEW 角标 = **近 3 天新建**（用户裁定 ⑥）。
                     ⚠️ 判据是**滚动 72 小时**（后端 `CouponDao.IS_NEW`）、**不是**「本周」——
                        两者会分叉（周一建的券到周五仍是「本周」但已不是「新」），别把它挂到 `thisWeek` 上。 -->
                <i v-if="truthy(c.IsNew)" class="tk-new">NEW</i>
              </span>
              <!-- ⚠️ 券名（A7 后半）——空串兜底在 `couponName()` 里，别在这里写 `{{ c.Name }}`：
                   库里 `Name` NOT NULL 但**空串合法**，直出会渲染成一条空白行。 -->
              <span class="tk-name">{{ couponName(c) }}</span>
              <!-- ⚠️ 类型 · 门槛合成一行走 `couponLine()`（**不是**裸 `couponCond`）：`couponCond`
                   对 `percent` / `none` 两档**已经含了类型词**（「折扣券」/「无门槛」），再拼一次类型标签
                   就是「无门槛 · 无门槛」—— 那张卡看着像坏了。合成规则在那个函数里。 -->
              <span class="tk-cond">{{ couponLine(c) }}</span>
              <button
                type="button"
                class="tk-get"
                :disabled="truthy(c.Claimed) || truthy(c.SoldOut) || claiming === c.CouponId"
                @click="doClaim(c)"
              >{{ truthy(c.Claimed) ? '已领取' : truthy(c.SoldOut) ? '已领完' : claiming === c.CouponId ? '领取中…' : '领取' }}</button>
            </li>
          </ul>
        </div>

        <p v-if="tkMsg" class="tk-msg">{{ tkMsg }}</p>
      </section>

      <!-- ── 推荐区（稿 `:820-826`）──
           真实数据 `goodsList`（`goodsSelectHome`）。
           ⚠️ 卡片在 **C3 块 5 · B 组续笔（2026-10-12）收成公共组件** `components/ProductCard.vue`
              （主页 / 店铺主页 / 商品页**三处共用**，`ShopHome` / `ProductPage` 也是这一枚）。
              外观与字段的逐条对照、以及**为什么三处能收成一件**（7 个 query 参数 vs 4 个、
              导航副作用、`<a>` 内不嵌 `<button>`）都写在那个组件的文件头。
           ⚠️ **本页原来那份卡的三件事已随组件接管**：4/3 有框白底、三段价（`¥` + 整数 + `.` 小数）、
              `disabled` 加购钮。本页**只留**「导航前清搜索缓存」这一条 ≠ 另两处的行为 ⇒ 走 `beforeNav`。
           ⚠️ 稿的 `.card-tags`（标签）与 `.card-price .was`（划线价）本仓**无对应字段**
              ⇒ 组件里也不落（搬进来就是悬空类名）。
           ⚠️ **元信息行改过**：原 `.card-meta` 画 `goods.GoodType`（分类），现让给「店名 + 已售 N 件」
              —— 用户本笔点名要的。分类的落点仍在（主页左侧分类导航 / 搜索页分类树 / `ShopHome` 筛选 chip）。 -->
      <section class="rec">
        <div class="rec-h">
          <h2>为你推荐</h2>
          <button type="button" class="more" disabled title="预留 · 查看全部商品（无落点：商品检索现走搜索页 GoodsSelect）">查看全部</button>
        </div>
        <p v-if="goodsList.length === 0" class="empty-tip">暂无商品</p>
        <div v-else class="grid">
          <ProductCard v-for="goods in goodsList" :key="goods.Id" :goods="goods" :before-nav="onCardNav" />
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'; import { getUsername } from '../api/auth'; import { coupons, claim, claimBatch, seckillLive } from '../api/marketing'; import { truthy, couponFace, couponName, couponLine } from '../config/coupon-format'; import ProductCard from '../components/ProductCard.vue'   // ⚠️ 四条 import **同行**（C3 块 5 · commit 1，2026-10-11；同日「券的信息架构重排」又加两处）—— 本文件 `:119` / `:242` / `:262` 等多处被按行号引，**能省一行就省一行**（同 `ProductPage.vue:426` 那条的做法）。⚠️ `myCoupons` 已随右栏券包一并**删掉**，格式化三件套改从 `config/coupon-format.js` 取（主页与券包弹窗**共享**，见那个文件头）。⚠️ **A 组 · A7 后半**（2026-10-12）：券卡原先只有「金额 / 门槛 / NEW」三样，用户点名**缺券名 + 券类型** ⇒ 追加 `couponName`（券名）与 `couponLine`（类型·门槛合成一行，内部拿 `couponCond` 拼），仍从同一份 `coupon-format.js` 取。⚠️ `couponCond` 随之**从本页 import 里去掉**（券包弹窗仍直接用）；本页若还要裸用它，`eslint no-unused-vars` 会当场红

// ── 未登录可逛公开面（2026-10-06 用户改判 2，登记见 progress.md §5-AG (13)）────────────
// 本页原有三条「未登录 ⇒ 弹登录提示」拦截（goSearch / searchByType / toGoodsDetail）与一条
// 顶部黄条 `.login-hint-bar` —— **全部拆除**。依据：搜索页 / 商品页都是**公开面**（后端
// AUTH_WHITELIST 的 goods* 5 条本就匿名可用；路由 meta.public 也已把 `/<role>/search` 与
// `/product` 判为公开），淘宝/京东同为「随便逛、关键操作才锁」⇒ 原「先登录再逛」是改判。
// **被锁的面一个不变**：购买（ProductPage 两处 customerId 守卫）、消息、用户中心、购物车
// （受保护路由 + navFor 未登录不渲染那三项）。匿名入口统一走顶栏「登录 / 注册」
// （config/nav.js 的 ANON_RIGHT）；登录后回原目标由路由守卫 + LoginPage.landingAfterLogin 承担。
// ⚠️ 本页因此**不再有匿名分支** ⇒ `isLoggedIn` 的 import 与 `isLogin` computed 一并删除（原 `:119` 保留为按语，供 `main.js:21` 的指向不悬空）。

// ⚠️ `const router = useRouter()` 已删（C3 块 5 · B 组续笔，2026-10-12）：本页**最后一处**
//    `router.push` 就在 `toGoodsDetail` 里，随卡片搬进 `ProductCard.vue` 的 `<router-link>` 了。
//    本页剩下的跳转全走 `emit('go-search')`（`searchByType` / 模板里的 `@click`）⇒ 壳层去路由。
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
      // ⚠️ C3 块 3（2026-10-10）：`/goods/goodstype` 的返回列多了 `ParentId`，类目**不再是平表**
      //    （二级子类目也在同一份 `list` 里）⇒ 首页这一排只取**一级**（`ParentId` 缺省 / 0）。
      //    ⚠️ 过滤收在**数据源**这一行（不是模板 `:46` 的 `v-for`）：上面 `:44` 的空态判据
      //    `goodTypeList.length === 0` 因此同样只数一级 —— 否则「只有二级类目」时会显示一个
      //    空 `<ul>`。⚠️ 传的仍是**类目名** `item.GoodType`（一级名在 `(0, GoodType)` 下唯一
      //    ⇒ 名字无歧义），**契约不变**。
      goodTypeList.value = data.list.filter(t => (t.ParentId || 0) === 0)
    }
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

// 主页进入商品页 —— **导航动作本身已搬进 `components/ProductCard.vue`**（三处共用那一枚），
// 本页只剩**自己独有的那一步**：清搜索缓存。挂在组件的 `beforeNav` 上。
//
// ⚠️ 为什么只剩两步（原 `toGoodsDetail` 还有第三个动作）：`router.push` 没了 —— 组件的
//    `<router-link>` 自己会跳，再 push 一次是同一目标的双跳。
// ⚠️ 两个 `sessionStorage` 动作逐字保留：
//    · `removeItem('SEARCH_KEYWORD')` —— 从主页点进商品，意味着「搜索」那一段重新开始
//      （`GoodsSelect.vue:174` 会读它还原上次搜索；不删就会把用户带回上一次的关键词）；
//    · `setItem('FROM_PAGE','home')` —— ⚠️ 全仓**只写不读**（`api/auth.js:10` 的清单里
//      `FROM_PAGE 1` 就是这一处）；**不删它**：删了属于别的笔的账，先照旧。
// ⚠️ 未登录放行（2026-10-06 改判 2，§5-AG (13)）：`/product` 已由路由 `meta.public` 判为公开面。
//    匿名进详情页**不会**触发 401：挂载期只打 goodsSelectHome / GoodsProductCodeSelect /
//    productCodePrice，三条都在白名单。
// ⚠️ **7 个 query 参数**（`Id/MerchantId/GoodName/Price/GoodImg/GoodType/ProductCode`）原样保留：
//    会话过期后 `orderAdd` 401 的回跳靠它们还原本页（T-guard-6 判据，`progress.md:1441`
//    明写「7 个 query 参数都要在」）⇒ 组件的 query 组装**必须**与这里逐字同序，别改成 4 个。
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

// ⚠️ `getPriceInt` / `getPriceDec` 已随卡片搬进 `components/ProductCard.vue`（本页只那儿用过）。

// ══════════════════════════════════════════════════════════════════════════════
// 券包 / 领券中心（C3 块 5 · commit 1，2026-10-11）—— 模板在 `tickets` section 那一段。
//
// ⚠️ **本段整块追加在 `<script setup>` 末尾**（而不是插在 `getHomeImgAndGoods` 旁边）：
//    本文件的 `:119` / `:242` / `:262` / `:303-304` 等被文档按行号引（⚠️ **改前值**）⇒ 每多一个
//    插入点就多一段集体漂移。收成**一个**插入点，`refs-scan` 的 before/after 只需解释一处偏移。
//    同理，本段的取数挂在**第二个** `onMounted` 上（Vue 允许注册多次）—— 挪去改既有的那个，
//    就又制造一个插入点。
//    ⚠️ 本笔的**实际偏移**（`grep -n` 实测）：券包那一段 +53 行 ⇒ 上述四个目标改后依次为
//    `:119`（未漂，在插入点之前）/ `:295` / `:315` / `:356-357`；本段 +107 行（模板 +53、样式 +95
//    另计）⇒ **`<script>` 段的闭合行**由 `:348` 漂 **`:508`**（全文件 994 → 1251 行）。**再经同日两轮手验修**（第一轮：⑤ 卡片降高 + 越位渐隐 + 「一键领取」占位；第二轮：① 去掉细滚动条、改**右侧**渐隐 + 表头「》」展开，② 「一键领取」与「》」**并列**在右侧按钮区，③ 券条改**横向**且定序移到后端 `CouponDao.selectClaimable` 的 `updated_at ASC`）累计又 **+140** 行 ⇒ 闭合行 **`:550`**、全文件 **1391** 行。**再经同日「券的信息架构重排」**（删右栏券包 + 快捷块那格「优惠券」、领券中心收窄成**本周新券**、「一键领取本周全部」真接、NEW 角标 + 倒计时 + 空态、`TK_VISIBLE` 3 → 5、三个格式化 helper 搬去 `config/coupon-format.js`）**+35** 行 ⇒ 闭合行 **`:589`**、全文件 **1426** 行。全表见 `_work/b5-repoint.md`（收口一次性重指）。⚠️ 这半句**故意不写那个闭合标签的字面量**：SFC 解析器在 `<script>` 段里一见到它就**当场截断本段**，真闭合行随即报 `x-invalid-end-tag`（`vue/no-parsing-error`）—— 实测踩过，别「顺手补全」。
//
// ⚠️ **身份**取 `getUsername()`（`api/auth.js` 是唯一允许读身份的地方），不碰 `sessionStorage` —— ⚠️ **2026-10-12 口径改动**：券包的「账号」由**客户业务号**（原 `getCustomerId()`）改成**登录名**，因为真库 13 个商家 `customerid` 全是 NULL、登录名才是客户 / 商家**都有**的那个键（`login` 47 行 ↔ `user` 47 行一一对应）；⚠️ 后端 `CouponController` 的身份判据已同笔换成比登录名（只改前端会让每个非管理员恒 403）。
//    本页 2026-10-06 起「已无匿名分支」（见 `:224-232`），券包**必须**有身份 ⇒ 这里**重新引入**
//    一条「有身份才取数」的分支（形状同 `ShoppingCart.vue:193-195`）。它**不是登录墙**：
//    页面照常逛，只是券包那一块显示「登录后可…」。
const couponAccount = ref('')
const claimable = ref([])
const claimLoading = ref(false)
const claiming = ref('')
const tkMsg = ref('')
const ticketsEl = ref(null)
/** 「本周还剩 N 天」—— 来自响应体 `daysLeft`（后端**库时钟**算）。`null` = 还没取到 ⇒ 不渲染那一行。 */
const daysLeft = ref(null)
/** 「一键领取本周全部」进行中（防连点）。 */
const batchBusy = ref(false)

// ⚠️ 本页**不再自带** `truthy` / `couponFace` / `couponCond` / `bucketLabel` —— 2026-10-11 起从
//    `config/coupon-format.js` 取。搬家的理由：券包弹窗（`components/CouponWalletDialog.vue`）要用**同一套**
//    口径，而这些函数的取值域（`Type` / `Bucket` / `Scope`）是**后端 SQL 里写死的**（`CouponDao`）——
//    抄两份，第二处抄错时**两边都不报错**。`bucketLabel` 本页已用不到（右栏券包删了），随弹窗留在那边。

const loadCoupons = async (account) => {
  claimLoading.value = true
  try {
    // ⚠️ `thisWeek = '1'`（第三参）⇒ **本周新发布**的（用户裁定：主页只放本周新券，后端 `CouponDao.THIS_WEEK`）。
    //    ⚠️ `limit` 从 6 提到 **10**：一行 5 张 ⇒ 点「》」展开后得有东西可看（6 张展开比收起多不出什么）。
    const data = await coupons(account, 10, '1')
    claimable.value = data && data.success && Array.isArray(data.list) ? data.list : []
    daysLeft.value = data && data.daysLeft !== undefined && data.daysLeft !== null ? data.daysLeft : null
  } catch (e) {
    handleApiError(e, '领券中心加载失败')
  } finally {
    claimLoading.value = false
  }
}

/**
 * 「**本周还剩 N 天** + 领哪几张」—— 一键领取只对**还领得动**的那几张有意义
 * （已领 / 已领完仍在列表里、但不算「可领」⇒ 按钮的 `disabled` 与 `title` 都看这个数）。
 */
const tkClaimable = computed(
  () => claimable.value.filter((c) => !truthy(c.Claimed) && !truthy(c.SoldOut)).length
)

/** 领券。⚠️ 只「领」——核销在结算（块 5 commit 5），本页不碰 `grant.Status`。 */
const doClaim = async (c) => {
  claiming.value = c.CouponId
  tkMsg.value = ''
  try {
    const data = await claim(couponAccount.value, c.CouponId)
    const ok = !!(data && data.success)
    // ⚠️ 文案里**不再提「右侧券包」** —— 右栏已删，券包在**用户中心**（2026-10-11 重排）。
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
 * 「**一键领取本周全部**」（用户裁定 ②）。
 * ⚠️ **不传券 ID 列表** —— 集合由**服务端**定（`CouponService.claimBatch` 自己跑 `THIS_WEEK` 那套筛选）。
 *    前端手里这份 `claimable` 是**上一次请求**的快照，拿它当集合就是把「本周」的定义抄到前端来
 *    （本仓最忌「同一口径有两处可抄错」）。
 * ⚠️ **不看顶层 `success`** —— 它说的是「批量动作**执行完毕**」，部分失败**刻意**不回落 `false`
 *    （理由三条写在 `CouponService.claimBatch`；与 `CartService.partialFailure` 方向相反是有意的）。
 *    ⇒ 汇总要按 `claimed` / `failed` 说人话，逐张原因在 `items[]` 里。
 * ⚠️ **不弹确认**（同日裁决）：券「领了就有」、没有损失。
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
    // ⚠️ 无论成败都重拉一次：成功的那几张要把按钮变「已领取」，**失败的那些也仍要在列表里**
    //    （服务端重算过的集合可能与本地这份不同，比如刚刚有人把某张领光了）。
    await loadCoupons(couponAccount.value)
  } catch (e) {
    handleApiError(e, '一键领取失败')
  } finally {
    batchBusy.value = false
  }
}

/** 部分失败时挑**第一条**原因附在汇总里（逐条明细不在主页展开 —— 那是弹窗的事）。 */
const firstFailReason = (data) => {
  const bad = (data.items || []).find((it) => !it.success)
  return (bad && bad.msg) || '详见券包'
}

/**
 * 「领券中心」横向券条的**可见张数 + 越位渐隐 + 「》」展开**（用户 2026-10-11 手验**第二轮** ①②③）。
 *
 * 口径（取代第一轮的「纵向限高 + 细滚动条 + 底部渐隐」那一版）：
 *   · **横向**：从左到右铺（③），左端 = 最早更新的那张（后端 `updated_at ASC` 定序）。
 *   · 超过 `TK_VISIBLE` 张 ⇒ 加 `.tk-strip-cut`，**右侧**一段 `mask` 渐隐 + 表头出「》」。
 *   · 点「》」⇒ `tkExpanded = true`，`wrap` 放开、全部铺开（渐隐同时撤掉）。
 * ⚠️ **没有滚动条了**（用户 ① 点名去掉细滚动条）⇒ 收起的那些张在展开前**确实够不着**，
 *    这是这次改动的**有意取舍**（用「渐隐 + 》」替代滚动条做「还有更多」的提示）。
 * ⚠️ 上一条 `tkAtBottom` / `onTkScroll` 一整套**已删**：不滚了就不需要「滚到底撤渐隐」。
 * ⚠️ **2026-10-11「券的信息架构重排」：`TK_VISIBLE` 3 → 5。** 右栏券包删掉后本栏**占满整页宽**
 *    （原来约 600px、现在约 1200px）⇒ 一行 5 张（用户裁定 ⑦：卡宽约 230px）。
 *    ⚠️ 它与样式里 `.tk-strip > .tk` 的基宽 `(100% - gap×(N-1)) / (N + 0.3)` 是**一对**，
 *       改一处必须成对改；那个 `+0.3` 是「第 N+1 张露头约 30%」，**露头宽又必须 ≤ mask 渐隐带**
 *       （`.tk-strip-cut` 的 88px）—— 三个数是一组，动了 N 要一起复核（探针里钉了这条不变量）。
 * ⚠️ 后端 `loadCoupons` 的 `limit` 已从 6 提到 **10** ⇒ 这里最多 10 张、`TK_VISIBLE = 5` 即「5 张以内不裁」。
 */
const TK_VISIBLE = 5
const tkExpanded = ref(false)

/** 「领券中心」（二级导航 `:32`）的落点 = 锚到本页领券中心。
 *  ⚠️ 原本还兼「优惠券」那格快捷块的落点 —— 那格 2026-10-11 已整格删掉，现在**只剩二级导航一个调用方**。 */
const scrollToTickets = () => {
  const el = ticketsEl.value
  if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

onMounted(async () => {
  couponAccount.value = getUsername() || ''
  if (!couponAccount.value) return
  // ⚠️ 只剩一个请求了 —— 「我的券包」已搬去用户中心（那边的入口自己拉摘要，弹窗开窗时再拉明细）。
  await loadCoupons(couponAccount.value)
})

// ── 限时秒杀（C3 块 5 · commit 4，2026-10-12）—— 模板在 `flash` section 那一段 ─────────
// ⚠️ 取数走**本段自己的** `onMounted(loadFlash)`（Vue 允许注册多次）—— 本段的**每一个新增行都落在
//    文件尾**，不动既有行号；本笔只让 `<script>` 闭合行与 `<style>` 整体后移，收口时按
//    `_work/b5-repoint.md` 一并重指。
// ⚠️ **未登录不发**：`/marketing/seckill/live` 不在白名单，匿名发它必 401，而 `http.js` 的
//    `handleUnauthorized` 会把公开页整体踢去登录页 —— 与 `loadCoupons` 同一条「有身份才取数」。
// ⚠️ **`signup` 那一档不画**（报名期对买家还不存在，后端 `SeckillDao.stage` 的注释同此）；
//    条数**自己截到 4**（`.flash-g` 是 `repeat(4, 1fr)`），向端点要 8 是为了扣掉 `signup` 后仍够 4。
const flashRows = ref([])
const flashLeft = ref(0)
let flashTimer = null
/** UTC 文本 → 毫秒。⚠️ `DATE_FORMAT` 出的是**库时钟（UTC）**的裸文本，不补 `Z` 会被当**本地时间**解析。 */
const flashTs = (s) => {
  const d = new Date(String(s == null ? '' : s).replace(' ', 'T') + 'Z')
  return isNaN(d.getTime()) ? null : d.getTime()
}
/** 倒计时瞄「**最近要跨过的那一刻**」：有进行中的就取最早 `EndAt`，否则取最早的 `StartAt`（未开启）。 */
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
/** 去商品页 —— `query` 四件套与 `ProductCard.vue` 逐字同序（那边是 4 个，`ProductPage` 的 7 个是另一回事）。 */
const flashLink = (r) => ({
  path: '/product',
  query: { MerchantId: r.MerchantAccount, GoodName: r.Name || r.GoodName, GoodType: r.GoodType, Id: r.GoodsId },
})
const tickFlash = () => {
  const t = flashTarget.value
  flashLeft.value = t ? t.at - Date.now() : 0
}
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
  if (flashRows.value.length) {
    tickFlash()
    flashTimer = setInterval(tickFlash, 1000)
  }
}
// ⚠️ 第二个 `onUnmounted`（同 `onMounted`：Vue 允许注册多次）—— 并进 `:391` 那个就又多一个插入点。
onMounted(loadFlash)
onUnmounted(() => {
  if (flashTimer) clearInterval(flashTimer)
})
</script>

<style scoped>
/* ⚠️ D 笔（2026-10-07）：全块按 `docs/美化/主页改造.html` 的 `<style>` 重写。
   类名一律取稿名（`wrap` / `hero` / `cats` / `glaze` / `dots` / `flash` / `tickets` /
   `rec` / `grid` / `trust` / `subnav` / `pts` …；⚠️ `quick` / `card` **已删**，见 `:91`），旧名（`search` 以外的
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
   （`:370-389`）—— 后三组对应的都是稿 JS 里的 mock 内容，本仓无数据源。⚠️ **2026-10-11（块 5 · commit 1）：`.ticket*` 那一组的「无数据源」不成立了**（券包接了真表 `coupon` / `coupon_grant`），但**仍不搬稿的类名** —— 本笔另起 `tk-` 前缀绑真字段，理由见下方券包那一节。
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
.subnav button, .subnav a {
  font-size: 13.5px;
  color: var(--nav-tx);
  transition: color 0.15s;
  white-space: nowrap;
  text-decoration: none;   /* `<router-link>` 落成 `<a>`，这行不给就带下划线（块 5 commit 5 起本栏有真链接） */
}
.subnav button:hover, .subnav a:hover {
  color: var(--nav-tx-hi);
}
.subnav button.now, .subnav a.now {
  color: var(--nav-now);
  font-weight: var(--fw-semi);
}
.subnav button.hot, .subnav a.hot {
  color: var(--nav-hot);
  font-weight: var(--fw-med);
}
/* 四项仍无落点（disabled）⇒ 鼠标形态要说实话，别留着 `pointer` 骗人。 */
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

/* ── 功能入口条：**样式随块一起删了**（2026-10-12）──
   删掉的是 `.quick` / `.quick .qi*` / `.quick .ic` / `.quick .tx*` 整段（含 `repeat(4, 1fr)`）。
   别只删模板留下这段 CSS —— 死样式比死标记更难发现。 */

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
/* ⚠️ `.flash-g .empty-tip`（空占位那行说明）**已删**：commit 4 转真后 `.flash-g` 里只有真卡片，
   那句「秒杀位预留 · 待接入活动接口后填充」随之退场。连规则一起删而不只删模板 —— 死样式比死标记更难发现。 */

/* ── 领券中心（**块 5**；2026-10-11「券的信息架构重排」后**只剩这一栏**；稿 `:632-658`）──
   ⚠️ 稿的 markup 里这条**本来就是空 section**（`:817`），3 张券由稿的 JS 塞 mock（`TICKETS`）
   ⇒ 稿的 `.ticket*` 规则本仓仍**不搬**（它们绑的是 mock 的类名与结构）。本笔另起一套 `tk-` 前缀、
   绑**真字段**（`Type` / `FaceValue` / `Threshold` / `IsNew` / `daysLeft`）—— 两套类名别互相套用。
   ⚠️ **commit 1 时这里是两栏**（领券中心 + 我的券包）；2026-10-11 用户重排后右栏整块删掉
   （券包搬去用户中心弹窗）⇒ **回到单栏**。留着 `grid` 是因为要在 `.tk-col` 下面另起一行放 `.tk-msg`
   （一键领取的汇总），单列网格正好；`repeat(2, 1fr)` 已改 `1fr`。 */
.tickets {
  margin-top: var(--s5);
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--s4);
}
.tickets .tk-msg {
  grid-column: 1 / -1;
}
.tk-col {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s4);
  /* ⚠️ 这一条是**结构性的**，别删 —— 但**理由已在 2026-10-11 换成新的**：
     原来是为了解开 `repeat(2, 1fr)` 那个 `auto` 下限（两栏等宽的旧问题，那个问题随单栏消失）。
     现在留着它是因为 `.tk-strip` 仍 `flex-wrap: nowrap` + `overflow: hidden`：券条的 min-content
     是「所有券各自 max-content 之和」⇒ 没有 `min-width: 0` 的网格项会**拒绝收缩**，
     整块横向溢出、把页面撑出横向滚动条。`probe-b5-claim-center.mjs` 钉着这条（`overflowX` 断言）。 */
  min-width: 0;
}
.tk-h {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: var(--s2);
  margin-bottom: var(--s3);
  border-bottom: 1px solid var(--line-2);
}
.tk-h h3 {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.tk-n {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
/* 表头右侧一组：「本周还剩 N 天」+ 张数 + 「一键领取本周全部」+「》」。 */
.tk-h-r {
  display: flex;
  align-items: center;
  gap: var(--s2);
}
/* 「本周还剩 N 天」（裁定 ⑤：**区块级**一行小字、**整日粒度**）。最浅的一档灰 ——
   它是背景信息，不该跟右边的张数与按钮抢注意力。 */
.tk-left {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
.tk-all {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border: 1px solid var(--brand-ink);
  border-radius: var(--r1);
  padding: 1px var(--s2);
  white-space: nowrap;
  cursor: pointer;
}
/* 不可用时（本周没有可领的 / 正在领）走全站统一的「灰掉 + 默认光标」。
   ⚠️ commit 1 那版这里还是**占位**按钮（接口未定，恒 `disabled`）；2026-10-11 起它是**真按钮**，
   只有上面那两个理由会让它变灰。 */
.tk-all[disabled] {
  color: var(--ink-3);
  background: var(--surface-3);
  border-color: var(--line-2);
  cursor: default;
}
/* 「》」查看更多 —— 表头右侧第二颗按钮，与「一键领取」并排（用户 ②）。 */
.tk-more {
  font: inherit;
  font-size: var(--fs-xs);
  line-height: 1;
  color: var(--brand-ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 2px var(--s2);
  white-space: nowrap;
  cursor: pointer;
}
.tk-more:hover {
  border-color: var(--brand-ink);
}
/* 展开态：底色提亮一档，与收起态区分（文案由模板在 `》` / `《` 之间切）。 */
.tk-more-on {
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
/* ── 领券中心：**横向**券条 + 越位渐隐（用户 2026-10-11 手验第二轮 ①③）──
   ① 不再滚：`overflow: hidden` + 右侧渐隐 + 表头「》」展开，**没有滚动条**。
   ③ 从左到右：左端 = 最早更新的那张（定序在后端 `CouponDao.selectClaimable`，本页不排）。 */
.tk-strip {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  gap: var(--s2);
  /* 不滚（用户 ① 点名去掉细滚动条）：被裁的那几张**停在**这里，由「》」展开去看。
     ⚠️ 它会把自己的 min-content 撑大 ⇒ 得靠 `.tk-col` 上那条 `min-width: 0` 解开（见那边注释）。 */
  overflow: hidden;
}
/* 展开态（点过「》」）：放开换行，全部铺开 ⇒ 渐隐同时撤掉。 */
.tk-strip-on {
  flex-wrap: wrap;
}
/* 越位渐隐。
   ⚠️ 渐隐带宽（**88px**，2026-10-11 由 56 改）必须**盖过**第 N+1 张露出来的那一角：
   只盖一半的话，露出那半张**边缘是实的**，看着就像「那张卡本来就画得窄」—— 而不是「右边还有」。
   露出宽 ≈ `0.30 × (行宽 / (N + 0.3))`：N=5、整页宽 ≈1200px ⇒ 卡宽 ≈ 215px、露头 ≈ 65px，
   88px 有富余；改 `TK_VISIBLE` 或基宽那个 `+0.3` 时**必须回来复核这三个数**（探针钉了不变量）。
   `-webkit-mask-image` 是给旧 WebKit 的，两条都要写。 */
.tk-strip-cut {
  mask-image: linear-gradient(to right, #000 calc(100% - 88px), transparent 100%);
  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 88px), transparent 100%);
}
/* 横条里的单张券：原来「金额 | 条件 | 领取」是一**行**，横过来后改成**竖排小卡**。
   ⚠️ 基宽是 `(100% - (N-1) 个 gap) / (N + 0.3)`（N = `TK_VISIBLE` = 5）—— 那个 **`+0.3` 不是笔误**：
     · 按整 N 算 ⇒ N 张正好铺满一行，第 N+1 张**整个落在裁切线之外、一点都露不出来**，
       右侧渐隐就没有东西可淡，「还有更多」这个提示当场失效；
     · 按 N+0.5 算（第一版是 `/3.5`）⇒ 第 N+1 张露出近一半，**看着像一张完整的卡**，同样不像「被裁」。
   `+0.3` 是「露出约 1/4 张」—— 一眼能看出是**被裁掉**的、又被上面那 88px 渐隐吃掉。
   ⚠️ 三个数（`TK_VISIBLE` / `+0.3` / mask 的 88px）是**一组**，与脚本里那个常量是一对，改一处要成对改。
   `.tk-cond` 的 `flex: 1` 到竖排里正好把「领取」顶到底 ⇒ 同排几张卡等高时按钮对得齐。 */
.tk-strip > .tk {
  flex: 0 0 calc((100% - var(--s2) * 4) / 5.3);
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s1);
}
.tk {
  display: flex;
  align-items: center;
  gap: var(--s2);
  /* 卡高再压一档（手验「缩小单个券卡片高度」）：上下 8px → 4px。 */
  padding: var(--s1) var(--s3);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.tk-amt {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--brand-ink);
  min-width: 4.5em;
}
.tk-cond {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  flex: 1;
}
/* 券名（A7 后半）——卡里**唯一**一行一级信息，故比 `.tk-cond` 重一档（`--fw-semi` / `--ink-2`）。
   ⚠️ 券名是**用户填的**（管理端建券表单不限长）⇒ 单行截断，别让它把卡撑宽、把「领取」顶出画面。
   ⚠️ 两个色档都是有据的：`--ink-2`（白底 8.0:1）、`--fs-xs` 同 `.tk-cond`（tokens.css）。 */
.tk-name {
  max-width: 100%;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  line-height: 1.2;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tk-get {
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border: 1px solid var(--brand-ink);
  border-radius: var(--r1);
  padding: 2px var(--s3);
  cursor: pointer;
}
.tk-get[disabled] {
  color: var(--ink-3);
  background: var(--surface-3);
  border-color: var(--line-2);
  cursor: default;
}
/* 「面额 + NEW 角标」一行。⚠️ 券卡是竖排（上面 `.tk-strip > .tk` 的 `column`），
   不包这一层的话角标会被 flex 顶到下一行、把卡撑高一档（同排几张就不齐了）。 */
.tk-top {
  display: flex;
  align-items: center;
  gap: var(--s1);
}
/* NEW 角标 = **近 3 天新建**（用户裁定 ⑥）。⚠️ 与「本周新券」**不是同一件事**：
   前者是滚动 72 小时（后端 `CouponDao.IS_NEW`），后者是自然周（`THIS_WEEK`）—— 会分叉。
   用品牌色小 chip，不抢面额那一行主数字的注意力。 */
.tk-new {
  font-style: normal;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  line-height: 1;
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-radius: var(--r1);
  padding: 1px 4px;
}
/* ⚠️ 原先这里还有「券包四态」那一组（`.tk-b` / `.tk-b-expiring` / `.tk-b-usable` / `.tk-b-used` /
   `.tk-b-expired`）—— 2026-10-11 右栏券包删掉后**全成死代码，已删**。四态的标签与配色改在
   `components/CouponWalletDialog.vue`（`w-tag*`），那才是唯一还用得到它们的地方。 */
.tk-msg {
  font-size: var(--fs-xs);
  color: var(--brand-ink);
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

/* 商品卡样式（`.card` / `.card-img` / `.card-tx` / `.card-name` / `.card-foot` / `.card-price` /
   `.card-buy` / `.card-meta`）**已整段搬进** `src/components/ProductCard.vue` 的 `<style scoped>`
   （C3 块 5 · B 组续笔，2026-10-12；三处共用那一枚）。
   ⚠️ 别在本页再留一份：`<style scoped>` 的作用域属性只打在子组件**根节点**上，本页写了也选不中卡**内部**，
   而「两页一致」正是靠「只有一份」保证的。栅格（`.grid` 的 `auto-fill minmax(190px,1fr)`）
   **不是卡的一部分**，仍留在本页 —— 那是版面，不是卡。 */

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

/* ── 限时秒杀模块里的卡片（C3 块 5 · commit 4 **转真**，2026-10-12）────────────────────
   ⚠️ 一律落 `<style>` **尾部**（理由同上一段：全仓引用本页的行号都在更前面）。
   ⚠️ 价格吃 `--accent`（朱砂只管价格 / 促销 / 未读，`tokens.css:34-42`）—— 本模块**没有**第二处
      抢这个色：条数、倒计时都是中性灰，「活动未开启」也只是灰字（它**不是**警示，是状态）。 */
.flash-h .more { text-decoration: none; } /* 它现在是 `<router-link>`（原是一颗 `disabled` 按钮）。 */
.flash-c { display: flex; flex-direction: column; gap: var(--s2); text-decoration: none; color: inherit; }
.flash-c img { width: 100%; aspect-ratio: 4 / 3; object-fit: cover; border-radius: var(--r1); background: var(--cin-100); }
.flash-c .fc-nm { font-size: var(--fs-xs); color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.flash-c .fc-pr { font-size: var(--fs-md); color: var(--accent); font-variant-numeric: tabular-nums; }
.flash-c .fc-soon { font-size: var(--fs-xs); color: var(--ink-3); }
</style>
