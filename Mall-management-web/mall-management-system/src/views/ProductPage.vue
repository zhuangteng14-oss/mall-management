<template>
  <div class="goods-detail-page">
    <!-- ── 自带顶栏 ───────────────────────────────────────────────────────────────
         本页路由带 `meta.public: true` ⇒ **不进壳**：没有壳的左菜单，也没有壳顶栏。 -->
    <nav class="nav-bar">
      <button type="button" class="nav-back" @click="goBack">‹ 返回</button>
      <router-link class="brand" :to="getMerchantId() ? '/merchant' : '/customer'">商城</router-link>
      <!-- ⚠️ **与稿有意不同（报请裁示）**：稿的顶栏（`ProductPage.html:262-263`）逐字写
           「‹ 返回 + 品牌名 + 右侧一项（**无搜索框**——用户裁决）」，本笔**保留**了搜索框。
           三条理由都是量出来的，不是偏好：
             ① 本页 `handleSearch`（见 `script` 段）写的 `PENDING_SEARCH_KEYWORD` 由
                **`AppShell.vue:231`** 消费 ⇒ 这个框是「壳内搜索流」在**本页的唯一入口**；
                删它 = 砍掉一条跨页真功能的入口，而「壳内搜索笔」正在本笔的**不碰**名单里。
             ② `handleSearch` 的引用**只在模板里**（重写前那两处 `@keyup.enter` / `@click`）⇒
                删掉框之后它立刻成为 `no-unused-vars`（`eslint:recommended`，本地探针实测报
                error）⇒ 本笔 `npx eslint` 必红、本笔 DoD 直接破。
             ③ 先例：commit 11 那一稿也有一处「撤掉真入口」，当时落法是**保留 + 登记**
                「藏起来 = 把真功能藏了」。
           ⇒ 顶栏的形制按稿重画，只**多**这一个既有真控件。删它 = 4 行，随时候裁。 -->
      <div class="search-wrapper">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="请输入搜索内容"
          @keyup.enter="handleSearch"
        />
        <button type="button" class="search-btn" @click="handleSearch">搜索</button>
      </div>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div><!-- 只改文案：点击逻辑（goUserCenter）一字不动，见文件末按语 -->
    </nav>

    <div class="wrap">
      <!-- 面包屑。⚠️ 分隔符**与文字同色**（同为 `--ink-3`）—— README 里记的对比度事故
           （原先用 `--line-2`，只有 1.47:1）。
           ⚠️ 稿的中间那一节是「数码配件」（分类），本页只有 `goodType`（商品类型，route query）
              ⇒ 落真值；「首页」指向**既有路由**（两个壳首页）⇒ 真 `<router-link>`。
           ⚠️ 全仓没有全局 `a{text-decoration:none}` ⇒ 本页的每一个 `<a>` 都要在 scoped 里自报。 -->
      <nav class="crumb">
        <router-link :to="getMerchantId() ? '/merchant' : '/customer'">首页</router-link>
        <span class="sp">/</span>
        <span>{{ goodType }}</span>
        <span class="sp">/</span>
        <span>{{ goodName }}</span>
      </nav>

      <!-- ── 一、主区：图集 56 / 信息 44 ─────────────────────────────────────── -->
      <div class="pd">
        <div class="gal">
          <!-- `.stage` 是全页**唯一**一处 `overflow:hidden`（放大镜裁切）—— 它里面没有任何角标 -->
          <div class="stage">
            <div class="zoom">商品主图 800 × 800</div>
            <img
              v-for="(item, idx) in allItems"
              :key="idx"
              :src="item.goodImg"
              class="big-img"
              :class="{ active: idx === currentIndex }"
            />
            <span class="lens">悬停放大</span>
            <button type="button" class="arw l" title="上一张（现有实现 scroll(-1)）" :disabled="start === 0" @click="scroll(-1)">‹</button>
            <button type="button" class="arw r" title="下一张（现有实现 scroll(+1)）" :disabled="start + 3 >= allItems.length" @click="scroll(1)">›</button>
          </div>
          <!-- 缩略图：既有真图集（`showImages` + `realIdx`），机制逐字未动；稿的 `.thb` 是灰方块 -->
          <div class="thumbs">
            <div
              v-for="(item, idx) in showImages"
              :key="idx"
              class="thb"
              :class="{ on: realIdx(idx) === currentIndex }"
              title="现有多图集，点缩略图切换"
              @click="currentIndex = realIdx(idx)"
            >
              <img :src="item.goodImg" />
            </div>
          </div>
        </div>

        <!-- 右信息区 = 本页的**一个釉面主块**（v5 由 `--glaze-deep` 换成 `--glaze-mid`）。
             层级：商品名 24 衬线 / 现价 36 衬线朱砂 / 其余 12–19 无衬线。 -->
        <div class="info">
          <h1 class="pname">{{ goodName }}</h1>

          <!-- ①【原价 / 折扣】—— C3 块 5〈折扣〉**落地**（2026-10-12 用户 A 档批准）。
               改前：`.was`（`¥199.00`）与 `.off`（`6.5 折`）是**稿内 mock 的写死字面量**，
               整块挂 `data-slot="预留 · 原价 / 折扣（动现有代码 5 处 · 超档）"`。
               现在：两枚都由 `originalPrice` 真算 —— `originalPrice > 0` 才画，否则**一枚都不画**
               （数据不足不显，不拿假划线价冒充）；`data-slot` 随之摘掉。
               ⚠️ 折数**读侧现算**（`price / originalPrice × 10`），库里没有折率列 ——
                  这样改了现价，角标自动跟着变，不会留下一个算不出来的陈旧折率。
               ⚠️ 稿的 `.dec`（`.00`）**不落**：本仓价格没有固定小数位，硬拆会凭空空造两位。 -->
          <div class="priceline">
            <span class="price"><span class="sym">¥</span><span class="int">{{ currentItem.price }}</span></span>
            <span v-if="hasDiscount" class="was">¥{{ currentItem.originalPrice }}</span>
            <span v-if="hasDiscount" class="off">{{ discountRate }} 折</span>
            <!-- ①-补【国家补贴】—— C3 块 5〈国补〉**落地**（2026-10-12）。
                 ⚠️ 只在「这件商品通过了国补、规则在期、商品在架」时才有个数 —— 后端 `/marketing/subsidy/goods`
                    三处 JOIN 一起挡（商家点头 + 规则在期 + 商品在架）。**没有就不画**，不拿「暂无补贴」占位。
                 ⚠️ 金额**后端现算**（`SubsidyAmount`），本页只显示。
                 ⚠️ **2026-10-12 补**：除「−¥N」外**同时**摆出**补贴后价**（`PayAmount`，后端 `withAmounts`
                    已经算好并返回）—— 只给一个减额，用户还得自己做减法才知道要付多少。`title` 里把
                    「原价 → 到手」两个数都写全（悬停可复核），不另占版面。 -->
            <span v-if="subsidy" class="subsidy" :title="'原价 ¥' + r2(currentItem.price) + ' → 补贴后 ¥' + r2(subsidy.PayAmount)">国补 −¥{{ r2(subsidy.SubsidyAmount) }}<i class="pay">到手 ¥{{ r2(subsidy.PayAmount) }}</i></span><!-- ①-补【秒杀价】—— C3 块 5〈秒杀〉**落地**（2026-10-12）：与上面两枚同一条口径「没有就不画」。⚠️ 命中判据是 **`ProductCode`**（`seckill` computed），且**只有 `Stage === 'live'` 才算命中** —— `upcoming`（活动未开启）/`signup`（报名期）那一档后端也会回，但那句话归秒杀页 `/seckill` 说，本页不替它说。⚠️ 已售完时改说「已售完」：`SoldOut` 是后端在 **Java 侧**拿两个数字比出来的（SQL 里写 `0/1` 会被驱动退成字符串，`'0'` 在 JS 里是真值）。 --></div><!-- ⚠️ **上面这一枚是修构建时补的**（2026-10-12）：同批把「秒杀价」徽标换成下面那个 `.sk-zone` 块时，**新块的 `</div>` 借走了原来关 `.priceline` 的那一枚** ⇒ `.priceline` 一直开着、错误一路冒到根 `div`（`VueCompilerError: Element is missing end tag. at :2:3`）。`.sk-zone` 是 `.priceline` 的**兄弟**（它是满行卡片、自带 `margin-top`），不是 flex 子项 —— 所以这一枚补在**价格行**末尾、新块之前。**同行追加以保零漂移**（下面 `<style>` 段被文档按行号引）。 -->

          <!-- ①-补【秒杀区】—— C3 块 5〈秒杀〉**落地**（2026-10-12；同日用户配色令改版）。
               ⚠️ **原方案是价格行上一枚 `.sk-tag` 徽标**（与 `.off` / `.subsidy` 同套色）——
                  用户 2026-10-12 裁定**乙案**：**删徽标**，秒杀信息只在这**一个新块**里说一次
                  （这里有它没有的三件：划线价 / 阶段 / 剩余件数）。整条 `.sk-tag` 规则随之退场。
               ⚠️ 命中判据仍是 **`ProductCode`**（`seckill` computed），且**只有 `Stage === 'live'` 才算命中**
                  —— `upcoming`（活动未开启）/`signup`（报名期）那一档后端也会回，但那句话归秒杀页
                  `/seckill` 说，本页不替它说。**没有就不画**（不拿「暂无秒杀」占位）。
               ⚠️ `SoldOut` / `SoldLeft` 由后端在 **Java 侧**算（SQL 里写 `0/1` 会被驱动退成字符串，
                  `'0'` 在 JS 里是真值）—— 本页只读不算（不限量那档的语义已经在后端定好了）。 -->
          <div v-if="seckill" class="sk-zone">
            <div class="sk-card" :class="{ sold: seckill.SoldOut }">
              <div class="sk-hd">
                <span class="sk-mark">秒杀</span>
                <span class="sk-left">{{ seckill.SoldOut ? '已售完' : (seckill.SoldLeft !== null && seckill.SoldLeft !== undefined ? '剩 ' + seckill.SoldLeft + ' 件' : '不限量') }}</span>
              </div>
              <div class="sk-pr">
                <b class="sk-now">¥{{ seckill.SeckillPrice }}</b>
                <s class="sk-was">¥{{ r2(currentItem.price) }}</s>
              </div>
            </div>
          </div>

          <!-- ②【促销 / 优惠券】—— **C3 块 5〈营销块〉已接线**（2026-10-11）。
               ⚠️ 数据 = **领券中心的前 6 张**（`/marketing/coupons`，封装 `src/api/marketing.js`）：
                 · 「促销」行 = 其中 `Type='threshold'`（满减）的券，画成「满 A 减 B」；
                 · 「领券」行 = **全部**可领的券 + 领取按钮（「领取」落 `coupon_grant`）。
               🔴 **不按商品 / 类目过滤** —— 后端 `CouponDao.selectClaimable` **不接商品维度** ⇒ 这一块
                  展示的是**全站可领的券**，别读成「这件商品可用的券」（真做过滤要动后端，超本笔边界）。
               ⚠️ 未登录（无 `couponAccount`）时不出券、只出一行提示 —— 领券要有身份。 -->
          <div
            class="promo"
            title="C3 块 5〈营销块〉已接线（2026-10-11）：促销行取满减类券、领券行取全站可领券，数据源 /marketing/coupons（封装 src/api/marketing.js）⇒ 原 data-slot「预留 · 促销 / 优惠券（动现有代码 5 处 · 超档）」退场"
          >
            <div class="r"><span class="tag">促销</span>
              <span v-if="promoCuts.length" class="cut">{{ promoCuts.join('\u3000') }}</span>
              <span v-else class="cut cut-off">暂无满减活动</span>
            </div>
            <div class="r r-coupons"><span class="tag">领券</span>
              <span
                v-if="claimList.length"
                class="coupons"
                :class="{ 'coupons-cut': claimList.length > CP_VISIBLE && !cpExpanded, 'coupons-on': cpExpanded }"
              >
                <span v-for="c in claimList" :key="c.CouponId" class="coupon">
                  <span class="amt"><i v-if="c.Type !== 'percent'">¥</i>{{ faceNum(c) }}</span>
                  <span class="nm">{{ couponName(c) }}</span>
                  <span class="cond">{{ faceCond(c) }}</span>
                  <button
                    type="button"
                    class="get"
                    :disabled="claimed(c) || claiming === c.CouponId"
                    :title="claimed(c) ? '已领取' : '领取优惠券'"
                    @click="doClaimCoupon(c)"
                  >{{ claimed(c) ? '已领取' : claiming === c.CouponId ? '领取中' : '领取' }}</button>
                </span>
              </span>
              <span v-else class="coupons"><span class="cond cut-off">{{ couponAccount ? '暂无可用券' : '登录后可领券' }}</span></span>
              <!-- 「》」展开（用户 A4：裁切线外还有券时才出现）。
                   ⚠️ 与主页 `.tk-more` 是**同一个手势**（点一下铺开、再点收起），但**不共用 CSS** ——
                      主页那份钉在 `.tk-h-r` 里（表头右侧），这一颗在 `.r` 的第 3 列。 -->
              <button
                v-if="couponAccount && claimList.length > CP_VISIBLE"
                type="button"
                class="cp-more"
                :class="{ 'cp-more-on': cpExpanded }"
                :title="cpExpanded ? '收起（只留前 ' + CP_VISIBLE + ' 张）' : '展开看全部 ' + claimList.length + ' 张'"
                @click="cpExpanded = !cpExpanded"
              >{{ cpExpanded ? '《' : '》' }}</button>
            </div>
          </div>

          <!-- 型号：稿画的是 3 个 mock 型号（标准款 / 降噪款 / 旗舰款），本页**真值就在手边**
               （`allItems[].productCode`）⇒ 落真码，机制（`currentIndex = idx`）与旧模板逐字同。
               ⚠️ **C 笔 C1**（2026-10-08）「只显示前 4 个、超出不展开」⇒ **同行**加 `slice(0, 4)`。 -->
          <div class="pick">
            <div class="lbl">型号</div>
            <div class="row">
              <button
                v-for="(item, idx) in allItems.slice(0, 4)"
                :key="idx"
                type="button"
                class="md"
                :class="{ on: idx === currentIndex, sub: skuKind(item.productCode) === 'subsidy', sec: skuKind(item.productCode) === 'seckill' }"
                @click="currentIndex = idx"
              >{{ item.productCode }}</button>
            </div>
          </div>

          <!-- 数量选择栏 —— **C 笔 C1 下线**（2026-10-08）：用户裁定浏览态「去掉数量选择栏」。
               原块 = 3 件 `disabled` 步进器（稿 `:967-975` 画的是**活控件**，而本页主区没有对应
               状态 —— 数量只在购入模块里 ⇒ 这三件曾以占位形态登记在台账 §三「稿内但本笔无法
               接线的控件」的 15 件里）+ `.stock`「现货，下单后 48 小时内发出」。
               C1 之后**数量选择只出现在购入模块顶部**（真控件，绑 `popupQty`）。
               ⚠️ 连带下线：「现货，…」那半句 —— 它在 `.qty` 块内，而 C1 的保留清单（图片 / 价格 /
                  促销 / 领券 / 型号 / 加入购物车 / 立即购买）里没有它；要留则复位到 `.acts` 上方。
               ⚠️ 回退点：本块整块还原即可（`.qty` / `.stock` 两条 CSS 留在样式表里**未随块删**）；
                  台账 §三 那 3 件的原文保留、只补一条「C1 已下线」的注（计数 15 → 12）。
               ⚠️ 稿的 `.qty` 是**浏览态与购入模块共用**的一套样式；本笔只摘掉浏览态这一份实例，
                  购入模块顶部那件真步进器（`.p-qty`）仍是同一形态、同一模块语义。
               ⚠️ 一致性：探针 `shot13.mjs` 的「§三 = 15 件」「步进器 3 件」两条断言随之失效，
                  C 笔改用 `shotc.mjs`（新基线：§三 12 件、购入模块改底部弹窗）。 -->

          <div class="acts">
            <!-- ③【加入购物车】—— C3 块 4 起**真的写购物车表**了（`cart.cartAdd` ⇒ `/cart/add`）。
                 块 4 之前它落到与「立即购买」**同一条** `order.orderAdd`（只差 `PayIf`）—— 那正是用户
                 点名的 bug；现在两支分家：本支写 `cart` 表、落「购物车」tab，右支写 `order` 表、落「我的订单」tab。 -->
            <button
              type="button"
              class="b ghost"
              title="现有功能：openPopup('cart')（→ cart.cartAdd，逐型号写 cart 表）"
              @click="openPopup('cart')"
            ><IconGlyph name="cart" />加入购物车</button>
            <!-- 【立即购买】既有真功能 `openPopup`（弹窗内选型号 / 数量 → `order.orderAdd`）。
                 ⚠️ 稿 `:981` 的 title 逐字带「（ProductPage.vue:69 → order.orderAdd）」——
                    那是**稿引本仓行号**，而本笔正是重写这个文件的那一笔 ⇒ 当场失效
                    ⇒ 划掉行号、其余逐字取稿（同 commit 11 的先例）。 -->
            <button
              type="button"
              class="b primary"
              title="现有功能：openPopup('buy')（→ order.orderAdd）"
              @click="openPopup('buy')"
            ><IconGlyph name="ok" />立即购买</button>
          </div>

          <div class="promise" v-if="promiseLabels.length">
            <span v-for="p in promiseLabels" :key="p">{{ p }}</span>
          </div>

          <!-- ④【店铺主页】—— **C3 块 5 B 组（2026-10-12）已落**：店铺主页 = 新建 `views/ShopHome.vue`，
               路由 `/shop` 追加在 `router/index.js` **文件尾**。⛔ 本块原先挂着 `data-slot="预留 · 店铺主页…"`
               与说明「进店逛逛无落点 ⇒ 四处改动超档」的 `title`，**两条随真落点一并撤掉** ——
               它们描述的病已经治好，留着就是假话。
               ⚠️ 原注里「按稿挂在**整张商家卡**上、**不挂**『进店逛逛』那个按钮」那条判据**不受本笔影响**
               （角标要 250px、按钮只有 90px，挂上去会横跨到左边的「客服」和店名 —— 这是「角标只挂
               区块级」的第三次应验）：本块现已无 `data-slot`，但那条讲的是**角标该挂在哪一层**，仍然有效。
               ⚠️「客服」从来不在占位里（它一直是 `consultProduct` 真功能）⇒ 不 `disabled`；
               本笔改的只有「进店逛逛」：它现在是**真按钮**（`enterShop` → `/shop`）。
               ⚠️ 店名与 `.sub` 后半句落真值（`currentItem.UserName` / `goodType`），
               摘掉稿里 mock 的「声谷小店」/「数码配件 ·」—— 与上述撤销无关，依旧有效。 -->
          <div class="shop">
            <span class="mark">{{ (currentItem.UserName || '商')[0] }}</span>
            <span>
              <span class="nm">{{ currentItem.UserName }}小店</span>
              <span class="sub">商品类型：{{ goodType }}</span>
            </span>
            <span class="ops">
              <button type="button" title="现有功能 consultProduct" @click="consultProduct">客服</button>
              <button type="button" title="真跳转：/shop 店铺主页（新建页 ShopHome，卖家 = 本页 route 的 MerchantId）" @click="enterShop">进店逛逛</button>
              <!-- 举报入口（方案 §4.4「可举报：评论 / 回复 / 商品 / 用户」）—— 商品还剩这一条：
                   对象 = 本商品的 `goods.Id`。⚠️ **「商家」那一条已不在本页**（2026-10-13 用户裁定 ①
                   搬到店铺主页 `ShopHome.vue` 的 `.hd-ops` —— 举报的是**店**，落点在店上更顺）。
                   ⚠️ 未登录不给（举报要有身份）：与服务端 `IdentityGuard` 同判据。 -->
              <button type="button" v-if="revAccount" @click="openReport('goods', Id, '商品')">举报商品</button>
            </span>
          </div>
        </div>
      </div>

      <!-- ── 二、Tab 条 + 四个面板 ────────────────────────────────────────────
           ⚠️ **结构二次调整（2026-10-07）**：四枚按钮原先**逐枚 `disabled`**、四个面板
              **依次全部渲染**（D 笔 commit 13 的「覆盖率优先」落法）—— 用户已改判为**错**：
              「叠着放不是落法 —— 它把多个内容挤一起，语义错」。现按稿的 `renderFull` 口径**真切**：
              新增页内状态 `activeTab`（UI 展示状态，不计功能量），四面板 `v-if` 各显其一；
              三条 `data-slot` 占位（`.longimg` / `.spec` / `.rev`）与售后保障块**一个都不删**。
           ⚠️ 四枚按钮的 `on` 仍按稿的**默认切面** `desc`（`ProductPage.html:1011-1012`），
              但它现在表示「**当前面**」；键名 `desc/spec/rev/pol` 与四个面板一一对应，
              切 Tab 只换面板 ⇒ 覆盖率与切 Tab 从此不再互相牵制。 -->
      <nav class="tabs">
        <button type="button" :class="{ on: activeTab === 'desc' }" @click="activeTab = 'desc'">商品详情</button>
        <button type="button" :class="{ on: activeTab === 'spec' }" @click="activeTab = 'spec'">规格参数</button>
        <button type="button" :class="{ on: activeTab === 'rev' }" @click="activeTab = 'rev'">累计评价<span class="cnt">{{ revTotalLabel }}</span></button>
        <button type="button" :class="{ on: activeTab === 'pol' }" @click="activeTab = 'pol'">售后保障</button>
      </nav>

      <!-- Tab ① 商品详情。⚠️ 两段介绍文是**稿的演示文案**（本仓 goods 表没有详情字段），
           按「`data-slot` 块内的稿 mock 逐字保留」处理 ⇒ 照留；`data-slot` 按稿只挂 `.longimg`。⚠️ **2026-10-10〈模板表通用化〉**：goods 新增 `Detail` 快照列 ⇒ 三件都加 `v-if="!detailText"`（有真内容时不再并排显示稿的演示文案——那是给「没有详情字段」这个前提配的图）。⚠️ 本页 `data-slot` 渲染数从此**随数据浮动**，见 §十九。 -->
      <div class="panel" v-if="activeTab === 'desc'">
        <p v-if="detailText" class="para pre">{{ detailText }}</p>
        <p v-if="!detailText" class="para">声谷标准版是这一代的主力款：单耳 4.8g，入耳处用液态硅胶做了弧度，
          长时间佩戴不压耳廓。蓝牙 5.3 双连，手机与笔记本之间切换不用重新配对。</p>
        <p v-if="!detailText" class="para">充电盒支持 10 分钟快充补 3 小时，通勤路上临时补电够用到下班。</p>
        <div v-if="!detailText" class="longimg"
          data-slot="预留 · 详情富文本（动现有代码 6 处 · 超档）"
          title="待 C3〈商品〉——goods 表无详情字段：mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsAdd.vue（录入）+ ProductPage.vue（展示）= 6 处，**超档**，因清单点名而画出（见 README 挂账表）"
        >商品详情长图&emsp;1200 × 400</div>
      </div>

      <!-- Tab ② 规格参数。整块级占位（`<div>`）⇒ 只挂 `data-slot` + `title`；表内 8 行是**块内
           mock** ⇒ 逐字保留。⚠️ **2026-10-10〈模板表通用化〉**：goods 新增 `Spec` 快照列（行内 `键=值`、一行一条）⇒ 有真内容时渲染真表（**复用**同一套 `.spec`/`.r`/`.k`/`.v` 样式，不是新样式），占位块整体 `v-if="!specText"`（理由同 Tab ①）。 -->
      <div class="panel" v-if="activeTab === 'spec'">
        <div v-if="specText" class="spec"><div class="r" v-for="(r, i) in specRows" :key="i"><span class="k">{{ r.k }}</span><span class="v">{{ r.v }}</span></div></div>
        <div v-if="!specText" class="spec"
          data-slot="预留 · 规格参数（动现有代码 6 处 · 超档）"
          title="待 C3〈商品〉——goods 表无参数列（品牌 / 材质 / 产地 / 尺寸各一列）：mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsAdd.vue + ProductPage.vue = 6 处，**超档**，因清单点名而画出（见 README 挂账表）"
        >
          <div class="r"><span class="k">品牌</span><span class="v">声谷 SG</span></div>
          <div class="r"><span class="k">型号</span><span class="v">SG-A2</span></div>
          <div class="r"><span class="k">材质</span><span class="v">ABS + 液态硅胶</span></div>
          <div class="r"><span class="k">产地</span><span class="v">广东东莞</span></div>
          <div class="r"><span class="k">尺寸</span><span class="v">62 × 42 × 24 mm（充电盒）</span></div>
          <div class="r"><span class="k">重量</span><span class="v">单耳 4.8 g / 整机 48 g</span></div>
          <div class="r"><span class="k">蓝牙</span><span class="v">5.3，双设备连接</span></div>
          <div class="r"><span class="k">续航</span><span class="v">单次 8 小时 / 含盒 32 小时</span></div>
        </div>
      </div>

      <!-- Tab ③ 累计评价。⚠️ **2026-10-12（C3 块 6 · commit 1「评论」）起是真功能** —— 原先
           那一整块 `data-slot="预留 · 累计评价（动现有代码 ≥4 处 · 超档）"` 占位（稿的裁法 =
           「画出来、但整块按占位处理」，用户边界「不做评论区」与清单点名打架时的显式例外）
           **已退役**：块 6 新增的 5 个端点（`endpoints.comment.*`）把它落实了。
           ⚠️ 稿的演示态（`4.8` / `12,043 条评价` / 三条假评论 / 5 枚 `disabled` 分页）**一个都不留**：
           它们与「详情页 mock 逐字保留」那类**不同** —— 那些是给「表里没这一列」的前提配的图，
           而这几个数是**纯假数据**；真列表来了还并排画 12,043 条，等于把两个互斥的事实摆在同一块里。
           ⚠️ 数据、判定、排序**全在后端**（`CommentService`）：前端这里没有「买过没有」「能不能重复评」
           的第二份判据 —— 抄一份就等着两边慢慢分叉。 -->
      <div class="panel" v-if="activeTab === 'rev'">
        <div class="rev">
          <div class="score">
            <!-- 评论子 tab：均分 + 5→1 星分布 + 实拍图墙 -->
            <template v-if="!revIsQuestion">
              <div class="n">{{ revScore }}</div>
              <div class="of">共 {{ revTotalLabel }} 条评价</div>
              <div class="bars">
                <div class="b" v-for="b in revBars" :key="b.star"><i>{{ b.star }} 星</i><span class="track"><span class="fill" :style="{ width: b.pct + '%' }"></span></span></div>
              </div>
              <div class="shots" v-if="revShots.length">
                <span class="s" v-for="(u, i) in revShots" :key="i"><img :src="u" alt="买家实拍"></span>
              </div>
            </template>
            <!-- 问答子 tab：已解决 / 未解决比例（方案 §4.2「左圆比例图」）。
                 ⚠️ 那条「一条都没有 ⇒ 显 `—`」的判据在 `revQuestionNumbers` 里，不在模板里：
                 模板只负责画，`0` 与「没有」的分野是计算的事。
                 ⚠️ 用**环图**而不是横向比例条：§4.2 逐字写着「**左圆**比例图」（commit 2 落成了
                 一根横条，本次整改拉回原方案）。图形一律走 `charts/` 那套既有组件 —— 与
                 `views/admin/AccountsPage.vue` 的「封禁分布」同款（`DonutChart` + 洞里的那组数）。 -->
            <template v-else>
              <div class="n">{{ revQuestionNumbers.label }}</div>
              <div class="of">共 {{ revTotalLabel }} 个提问</div>
              <div class="qdonut">
                <DonutChart
                  :data="revQaMix"
                  :height="'200px'"
                  :center-value="revQuestionNumbers.solved"
                  center-title="已解决"
                  :loading="revLoading"
                  empty-text="还没有提问"
                />
              </div>
              <p class="qtip">商家与买过的人都能回答；提问者采纳后标记为已解决。</p>
            </template>
          </div>
          <div>
            <!-- 子 tab（评论 / 问答）。⚠️ 切换要**同时**清列表 / 统计 / 错误并关掉两个发表框，
                 逐条理由写在 `setRevKind` 的注释里 —— 别只改 `revKind` 就完事。 -->
            <div class="subtabs">
              <button type="button" :class="{ on: !revIsQuestion }" @click="setRevKind('review')">商品评论</button>
              <button type="button" :class="{ on: revIsQuestion }" @click="setRevKind('question')">问答</button>
            </div>
            <div class="rbar">
              <button type="button" :class="{ on: revSort === 'new' }" @click="setRevSort('new')">最新</button>
              <button type="button" :class="{ on: revSort === 'hot' }" @click="setRevSort('hot')">最热</button>
              <span class="sp"></span>
              <button type="button" class="wr" v-if="revAccount && !revIsQuestion && !revCompose" :disabled="!revCanReview" :title="revCanReview ? '' : '购买后可评价'" @click="openRevCompose">写评价</button>
              <button type="button" class="wr" v-if="revAccount && revIsQuestion && !askCompose" @click="openAskCompose">我要提问</button>
              <span class="hint" v-if="!revAccount">{{ revIsQuestion ? '登录后可以提问' : '登录后可发表评价' }}</span>
            </div>

            <!-- 提问框（问答子 tab）。⚠️ 只有正文一个输入 —— 为什么不问型号 / 不要星级 / 不给匿名勾，
                 那四条理由写在 `askDraft` 上方那段注释里。 -->
            <div class="wbox" v-if="askCompose && revIsQuestion">
              <div class="wrow">
                <span class="wk">提问</span>
                <span class="wk2">实名提问（问答区不支持匿名）；本商品的提问对所有型号共享</span>
              </div>
              <textarea v-model="askDraft" class="wt" maxlength="1000"
                        placeholder="想了解这件商品的什么？（最多 1000 字）"></textarea>
              <div class="wact">
                <b class="sp"></b>
                <span class="wmsg" v-if="askMsg">{{ askMsg }}</span>
                <button type="button" class="wr" :disabled="askSubmitting" @click="submitAsk">{{ askSubmitting ? '提交中…' : '发表提问' }}</button>
                <button type="button" class="cl" @click="closeAskCompose">取消</button>
              </div>
            </div>

            <!-- 发表框（顶层评价）。⚠️ 只在**有可评价商品**时出现（资格由后端 `myOrders` + `add` 双重判，
                 前端这份列表只是让它别白填一段字才被拒）。 -->
            <div class="wbox" v-if="revCompose">
              <div class="wrow">
                <span class="wk">商品</span>
                <select v-model="revGoodsId" class="wg" v-if="revOrders.length">
                  <option v-for="o in revOrders" :key="o.GoodsId" :value="String(o.GoodsId)">{{ o.GoodName }}（{{ o.ProductCode }}）</option>
                </select>
                <span class="wk2" v-else>{{ revOrderMsg || '暂无可评价的商品（须已确认收货、且未评价过）' }}</span>
                <span class="wk2 warn" v-if="revOtherGoods">⚠️ 选的是「另一件」商品 —— 发表后不会出现在本页这个列表里</span>
              </div>
              <div class="wrow">
                <span class="wk">评分</span>
                <span class="starpick">
                  <button type="button" v-for="n in 5" :key="n" :class="{ e: n > revDraft.rating }"
                          :disabled="revDraft.anonymous" @click="revDraft.rating = n">★</button>
                </span>
                <label class="an"><input type="checkbox" v-model="revDraft.anonymous"> 匿名（评分固定 5 星、昵称只留首字）</label>
              </div>
              <textarea v-model="revDraft.content" class="wt" maxlength="1000"
                        placeholder="说说这件商品的实际体验…（最多 1000 字）"></textarea>
              <div class="wact">
                <input type="file" accept="image/*" multiple @change="onRevFiles">
                <span class="wk2">最多 {{ REV_MAX_IMAGES }} 张 · {{ revFileNote }}</span>
                <b class="sp"></b>
                <span class="wmsg" v-if="revMsg">{{ revMsg }}</span>
                <button type="button" class="wr" :disabled="revSubmitting" @click="submitReview">{{ revSubmitting ? '提交中…' : '发表' }}</button>
                <button type="button" class="cl" @click="closeRevCompose">取消</button>
              </div>
            </div>

            <div class="rlist" v-if="revRows.length">
              <div class="rc" v-for="r in revRows" :key="r.Id">
                <div class="h">
                  <span class="who">{{ r.Author }}</span>
                  <span class="stars" v-if="r.Rating">{{ revStars(r.Rating) }}<span class="e">{{ revStars(5 - r.Rating) }}</span></span>
                  <!-- 问答的两种结局。⚠️ 判据是 `IsSolved`（后端那列），不是「有没有 AcceptedReplyId」——
                       两列由同一次采纳一起写，但 `IsSolved` 才是方案 §4.2 说的那个「已解决」。
                       ⚠️ 用 `Number(...) === 1` 而不是 `v-if="r.IsSolved"`：JSON 里它可能是 `"1"`（字符串，
                       truthy）也可能是 `0`（数字，falsy）—— 但**字符串 `"0"` 是 truthy** ⇒ 裸判会把
                       「未解决」画成「已解决」。 -->
                  <span class="tag solved" v-if="revIsQuestion && Number(r.IsSolved) === 1">已解决</span>
                  <span class="tag" v-else-if="revIsQuestion">未解决</span>
                  <span class="when">{{ r.CreatedAt }}</span>
                </div>
                <p class="txt">{{ r.Content }}</p>
                <div class="rshots" v-if="r.Images && r.Images.length">
                  <template v-for="(im, i) in r.Images" :key="i">
                    <span class="s" v-if="im.Status === 'approved' && im.Url"><img :src="im.Url" alt="买家实拍"></span>
                    <span class="s wait" v-else-if="im.Status === 'pending'" title="图片审核中 —— 通过后显示">图片审核中</span>
                    <span class="s bad" v-else title="图片经审核判定违规，已删除">图片违规<br>已删除</span>
                  </template>
                </div>
                <div class="sku" v-if="r.ProductCode">型号：{{ r.ProductCode }}</div>
                <div class="ract">
                  <button type="button" :class="{ on: r.MyLike === 'like' }" @click="likeRev(r, 'like')">赞 {{ r.LikeCount || 0 }}</button>
                  <button type="button" :class="{ on: r.MyLike === 'dislike' }" @click="likeRev(r, 'dislike')">踩 {{ r.DislikeCount || 0 }}</button>
                  <button type="button" @click="openReply(r, null)">回复</button>
                  <!-- 举报 / 提删（方案 §4.4、§4.2）—— 自己的内容不给（服务端「不能举报自己」同判据）。
                       提问上商家看到的措辞是**提删**（§4.2「商家可提删 → 管理端裁判」），
                       走的**是同一条**举报通道（对象 = 这条提问），只是按钮名不同。 -->
                  <button type="button" v-if="revAccount && !r.Mine"
                          @click="openReport('comment', r.Id, revIsQuestion ? (revIsMerchant ? '提删该提问' : '举报提问') : '举报评价')">{{ revIsQuestion && revIsMerchant ? '提删' : '举报' }}</button>
                  <!-- ⚠️ 顶层**提问**不给「删除」：方案 §4.2「提问者不能删」（后端 `CommentService.delete`
                       同一判据）。提问被删会连带毁掉下面全部回答 ⇒ 只有管理端能直接删，
                       商家的诉求走「提删」（举报通道，管理端裁决）。 -->
                  <button type="button" v-if="r.Mine && !revIsQuestion" @click="removeRev(r)">删除</button>
                </div>

                <!-- 回复框：挂在**被回复的那一层**（顶层回复挂在顶层行下、回复某条回复挂在回复块下）
                     —— 与后端「两层扁平 + `ReplyToId` 指人」的结构同形。 -->
                <div class="wbox rep" v-if="repDraft && repDraft.parentId === r.Id && !repDraft.replyToId">
                  <div class="wrow"><span class="wk">{{ revIsQuestion ? '回答' : '回复' }}</span><span class="wk2">@ {{ r.Author }}</span>
                    <!-- ⚠️ 匿名这一格**随子 tab 换**：评价的回复是「匿名可选」（勾选框），
                         而回答是「只能匿名（商家除外）」—— 后端会按形态覆盖入参（`resolveAnonymous`），
                         所以这里**不给勾选框**：给了就是一个点了没用的控件。 -->
                    <label class="an" v-if="!revIsQuestion"><input type="checkbox" v-model="repDraft.anonymous"> 匿名</label>
                    <span class="wk2" v-else>回答默认匿名（商家回答实名）</span></div>
                  <textarea v-model="repDraft.content" class="wt" maxlength="1000"
                            :placeholder="revIsQuestion ? '写下你的回答…' : '回复…'"></textarea>
                  <div class="wact">
                    <span class="wmsg" v-if="repMsg">{{ repMsg }}</span>
                    <button type="button" class="wr" :disabled="repSubmitting" @click="submitReply">{{ repSubmitting ? '提交中…' : (revIsQuestion ? '回答' : '回复') }}</button>
                    <button type="button" class="cl" @click="repDraft = null">取消</button>
                  </div>
                </div>

                <div class="rreps" v-if="r.Replies && r.Replies.length">
                  <div class="rep" v-for="p in revShownReplies(r)" :key="p.Id">
                    <div class="h">
                      <span class="who">{{ p.Author }}</span>
                      <span class="tag" v-if="p.IsMerchantFirst">商家首复</span>
                      <span class="tag" v-else-if="p.AuthorRole === 2">商家</span>
                      <span class="when">{{ p.CreatedAt }}</span>
                    </div>
                    <!-- ⚠️「回复 @X」前缀：`ReplyToName` 由后端 `selectReplies` 的关联子查询给出
                         （方案 §4.1「楼中楼要能看出回的是谁」）。**匿名回复者恒为 NULL** —— 后端刻意
                         不把匿名的 `AuthorId` 吐出来，否则这一行就成了匿名去匿名化的后门。 -->
                    <p class="txt"><span class="rt" v-if="p.ReplyToName">回复 @{{ p.ReplyToName }}：</span>{{ p.Content }}</p>
                    <div class="ract">
                      <!-- 被采纳的那条打个标（`Accepted` 由后端在 `Replies` 的稳定排序里给出，
                           且它一定排在第一 —— 见 `CommentService.list`）。 -->
                      <span class="tag solved" v-if="p.Accepted">已采纳</span>
                      <button type="button" :class="{ on: p.MyLike === 'like' }" @click="likeRev(p, 'like')">赞 {{ p.LikeCount || 0 }}</button>
                      <button type="button" :class="{ on: p.MyLike === 'dislike' }" @click="likeRev(p, 'dislike')">踩 {{ p.DislikeCount || 0 }}</button>
                      <button type="button" @click="openReply(r, p)">{{ revIsQuestion ? '回答' : '回复' }}</button>
                      <!-- 采纳：**只有提问者本人**、且这条提问**还没被采纳过**时才出现。
                           ⚠️ 不绑「我是不是那条回答的作者」—— 采纳自己的回答是允许的（方案没禁）。 -->
                      <button type="button" class="acc" v-if="revIsQuestion && r.Mine && Number(r.IsSolved) !== 1"
                              @click="acceptAnswer(r, p)">采纳</button>
                      <button type="button" v-if="p.Mine" @click="removeRev(p)">删除</button>
                      <button type="button" v-if="revAccount && !p.Mine" @click="openReport('comment', p.Id, '该回复')">举报</button>
                    </div>
                    <div class="wbox rep" v-if="repDraft && repDraft.parentId === r.Id && repDraft.replyToId === p.Id">
                      <div class="wrow"><span class="wk">{{ revIsQuestion ? '回答' : '回复' }}</span><span class="wk2">@ {{ p.Author }}</span>
                        <label class="an" v-if="!revIsQuestion"><input type="checkbox" v-model="repDraft.anonymous"> 匿名</label>
                        <span class="wk2" v-else>回答默认匿名（商家回答实名）</span></div>
                      <textarea v-model="repDraft.content" class="wt" maxlength="1000"
                                :placeholder="revIsQuestion ? '写下你的回答…' : '回复…'"></textarea>
                      <div class="wact">
                        <span class="wmsg" v-if="repMsg">{{ repMsg }}</span>
                        <button type="button" class="wr" :disabled="repSubmitting" @click="submitReply">{{ repSubmitting ? '提交中…' : (revIsQuestion ? '回答' : '回复') }}</button>
                        <button type="button" class="cl" @click="repDraft = null">取消</button>
                      </div>
                    </div>
                  </div>
                  <!-- 折叠（方案 §4.1「回复多了要收起来，默认只看前 3 条」）。⚠️ **只在评价 tab 折叠**：
                       问答 tab 的回答是主内容、不是附属（§4.2 左图右列的核心），收起来等于藏正文。 -->
                  <button type="button" class="rmore" v-if="!revIsQuestion && r.Replies.length > REV_REPLY_VISIBLE"
                          @click="revToggleReplies(r)">
                    {{ revExpanded[r.Id] ? '收起' : `共 ${r.Replies.length} 条回复 · 展开` }}
                  </button>
                </div>
              </div>
            </div>
            <p class="none" v-else-if="revError">{{ revError }}</p>
            <!-- 空态**按种类分话术**：问答区不该说「买过并确认收货后就能评价」——
                 提问**不需要买过**（见 `CommentService.add` 只对 `review` 判买过）。 -->
            <p class="none" v-else-if="!revLoading">{{ revIsQuestion ? '还没有提问。登录后可以在这里提问。' : '还没有评价。买过并确认收货后就能在这里评价。' }}</p>

            <div class="pager" v-if="revHasMore">
              <button type="button" :disabled="revLoading" @click="moreReviews">{{ revLoading ? '加载中…' : '加载更多' }}</button>
              <span class="gap">已展示 {{ revRows.length }} / {{ revTotal }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab ④ 售后保障：稿判「真功能 N=0」（纯静态政策文案）⇒ 不标 slot、不 `disabled`。⚠️ **2026-10-10〈模板表通用化〉**：goods 新增 `AfterSale` 快照列 ⇒ 商家填了这段文字，本 Tab 就显示**它**（那四条平台默认政策 `v-if="!afterSaleText"` 让位）——即「商家自己的售后说明」优先于「平台通用政策」。 -->
      <div class="panel" v-if="activeTab === 'pol'">
        <p v-if="afterSaleText" class="para pre">{{ afterSaleText }}</p><div v-if="!afterSaleText" class="pol">
          <div class="r"><span class="k">7 天无理由</span>
            <span class="v">签收次日起 7 天内，商品及包装保持完好、不影响二次销售的，可申请无理由退货。</span></div>
          <div class="r"><span class="k">极速退款</span>
            <span class="v">退货申请通过后，货款先行退回原支付账户，后续物流由平台跟进。</span></div>
          <div class="r"><span class="k">运费险</span>
            <span class="v">本商品默认投保运费险，退货产生的运费按保单赔付，最高 20 元。</span></div>
          <div class="r"><span class="k">正品保障</span>
            <span class="v">平台自营或品牌授权商家发货，假一赔十；保修按品牌官方政策执行。</span></div>
        </div>
      </div>

      <!-- ── 三、推荐区（猜你喜欢）——**真功能**（`goods.goodsSelectHome` ⇒ `recFeed`）──
           ⚠️ 稿的 4 张卡是 mock（声谷 Lite / 空气传导 / 数据线 / 蓝牙音箱），本区**有真数据**
              ⇒ 落真卡。块 7 收尾 · P1 起改为真分页 + 下拉加载更多（每页 24；栅格仍是 4 栏）。
           ⚠️ 卡在 **C3 块 5 · B 组续笔（2026-10-12）收成公共组件** `components/ProductCard.vue`
              （本页 / 主页 / 店铺主页**三处共用**）—— 本区改前那份手写卡（1/1 图、整值价、
              4 个 query 参数）随之作废。组件与**主页**那一枚是同一份结构，差异只有本区不传
              `beforeNav`（本页进商品页不需要清搜索缓存）。
           ⚠️ 副作用逐条交代：组件统一按 **7 个 query 参数**跳（本区原为 4 个）——
              多出来的 `Price/GoodImg/ProductCode` 本页**不读**（`:465` 只解构 4 个）⇒ 无害；
              而少传会让主页那条 T-guard-6 判据（「7 个参数都要在」）失效，故取并集。
           ⚠️ **改前实测（不是推断）**：三处卡形的逐条对照（图比例 / 有无框 / 价格段数 / 元信息行）见
              `docs/agent/evidence/c3-block5/out-b5d-cards-before.png`（探针 `scripts/probes/c3b5d-cards-before.mjs`，两遍 16 项 / 0 失败）。
           ⚠️ 原卡有**两处兜底**，组件里没有照搬，理由各一：
              · `<img>` 上的 `v-if="goods.GoodImg"`（防 `src=''` 画破图）—— 实测 43/43 行都有
                `GoodImg`，且组件那一枚 `<img>` 是**无条件**渲染的（空 `src` 是同一个破图）；
              · 无 `alt` —— 组件补了 `:alt="goods.GoodName"`（43 张一样的「商品图」对读屏零信息）。
           ⚠️ 未抽组件前，本区与店铺主页的「像不像」靠手写对齐；收成一件之后由**结构**保证。 -->
      <section class="reco">
        <h2 title="真功能：现有端点 goods.goodsSelectHome，本区不需要新端点">猜你喜欢</h2>
        <p v-if="recommendGoods.length === 0 && !recLoading" class="none">暂无推荐</p>
        <div v-else class="cards">
          <ProductCard v-for="goods in recommendGoods" :key="goods.Id" :goods="goods" />
        </div>
        <!-- 块 7 收尾 · P1：本区由「只取前 4 条」改为真分页 + 下拉加载更多（每页 24）。 -->
        <div v-if="recommendGoods.length" ref="recSentinel" class="reco-more"
             :class="{ 'reco-more--loading': recLoading, 'reco-more--done': recDone }"
             @click="!recDone && recRetry">
          <span v-if="recLoading">正在加载更多…</span>
          <span v-else-if="recErrMsg">加载失败，点击重试</span>
          <span v-else-if="recDone">已经到底了</span>
          <span v-else>下拉加载更多</span>
        </div>
      </section>
    </div>

    <!-- ══ 购入模块 —— **C 笔 C2 重做**（2026-10-08） ═══════════════════════════════════
         形态 = **底部弹窗（bottom sheet）**：稿 `dlg1`（`ProductPage.html:706-772`）画的是**居中** `.dlg`（760px）
         ⇒ 按用户明文「非居中 modal」**有意偏离稿**（登记台账 §八）。顶部（`.p-sku`）= 图 + 价 + 促销 + 领券 + 型号 + 数量
         —— 用户判旧 30%/40%/30% 三段硬分割「丑」⇒ 按主区那套 token 重排：价 `--accent` + display 字族、
         券同主区 `.coupon`、型号同主区 `.md`、数量沿用 `.stepper`。底部 = **图三布局**（微型购物车 = 商品行 + 结算栏）：
         六列取 `ShoppingCart-customer.html:338`（去勾选列、留删除列）；行内「数量」是**只读镜像**（选择权在顶部那件活步进器）；
         金额三处一律 `toFixed(2)`（图三那份稿单价/小计/合计都是两位，原实现小计与合计落**整数** 129 ⇒ 同排两种位数，
         属补一致；展示层，不动上报值）。CTA 按入口分：加入购物车 →「加入购物车」／立即购买 →「结算」（`popupEntry`）。
         ⚠️ C3 口径：两入口都走既有 `order.orderAdd`（`confirmPurchase` → `handlePay`），不新建接口、不新增功能；
         旧 `.p-top/.p-middle/.p-bottom` 三段式一族退役。⚠️ **2026-10-08 用户「六条改动」（结构二次调整，不受 D 笔边界约束）**：① 宽度**满占 100%**（原居中 1000px 上限）+ 排版随满占重排（**结构不动**：顶部信息区 / 商品列表 / 右侧结算栏）；② 列表超出即滚、**滚动条隐藏**；③ 右上角 **X 关闭**；④ **关闭 ≠ 清数据**（清只两条：刷新页面 / 商品 code 变）；⑤ 确认框文案随入口（`popupEntry`）+ **「否」不发任何请求**（旧实现两支都掉 `order.orderAdd`）。逐条登记见下方「购入模块」CSS 段头注释。 -->
    <div class="popup-mask" v-if="showPopup" @click="showPopup=false">
      <div class="popup-content" @click.stop><button type="button" class="p-close" title="关闭（保留已选数据）" aria-label="关闭" @click="showPopup = false">×</button>
        <div class="p-sku">
          <div class="p-media"><div class="thumb"><img v-if="popupCurrentItem.goodImg" :src="popupCurrentItem.goodImg" /></div><div class="p-thumbs"><button type="button" v-for="(item, idx) in popupAllItems" :key="idx" class="pt" :class="{ on: idx === popupCurrentIndex }" :title="item.productCode" @click="popupCurrentIndex = idx"><img v-if="item.goodImg" :src="item.goodImg" /></button></div></div>   <!-- ⚠️ **2026-10-08 用户五条【3】**：大图下面加一排型号缩略图（点 = 切 `popupCurrentIndex`，与详情页 `.thumbs`→`currentIndex` 同形）。⚠️ 加了 `.p-media` 一层**包装**（大图 + 缩略图同属「图片列」）—— 属「补元素」不是重排。⚠️ **2026-10-08 用户复核后改判（本条推翻原文的 `v-if="popupAllItems.length > 1"`）**：小图排**不挂 `v-if`** —— **单型号时也要显示那一枚**（用户逐字：「小图在只有一个型号时也应有小图。之前是只有一个型号时干脆不显示」）。⇒ 大图侧本来就不挂 `v-if`（行为与改前一致），小图侧现在也一律渲染，两个分支的差别只剩「有几枚」。同轮把 `:title` 由「切换到型号 X」改成**只写型号本身**（单型号时没有「切换」可言）。⚠️ **净零**：本行前后各只换这一行。 -->
          <div class="p-buy">
            <div class="p-price"><i>¥</i><b>{{ popupCurrentItem.price }}</b></div>
            <!-- 促销 / 领券：与主区 `:104-127` 那一块**同一份数据、同一对处理函数**
                 （`promoCuts` / `claimList` / `doClaimCoupon`）—— 弹窗不是第二个实现，别各自拉一遍。 -->
            <div class="p-promo"><span class="tag">促销</span>
              <span v-if="promoCuts.length" class="cut">{{ promoCuts.join('\u3000') }}</span>
              <span v-else class="cut cut-off">暂无满减活动</span></div>
            <div class="p-coupons"><span class="tag">领券</span>
              <span v-for="c in claimList" :key="c.CouponId" class="coupon"><span class="amt"><i v-if="c.Type !== 'percent'">¥</i>{{ faceNum(c) }}</span><span class="cond">{{ faceCond(c) }}</span><button type="button" class="get" :disabled="claimed(c) || claiming === c.CouponId" :title="claimed(c) ? '已领取' : '领取优惠券'" @click="doClaimCoupon(c)">{{ claimed(c) ? '已领取' : claiming === c.CouponId ? '领取中' : '领取' }}</button></span>
              <span v-if="!claimList.length" class="cond cut-off">{{ couponAccount ? '暂无可用券' : '登录后可领券' }}</span></div>
            <div class="p-opt"><span class="lbl">型号</span><button v-for="(item, idx) in popupAllItems" :key="idx" type="button"
                  class="md" :class="{ on: idx === popupCurrentIndex, sub: skuKind(item.productCode) === 'subsidy', sec: skuKind(item.productCode) === 'seckill' }" @click="popupCurrentIndex = idx">{{ item.productCode }}</button></div>
            <div class="p-qty"><span class="lbl">数量</span><span class="stepper">
              <button type="button" title="减一（现有实现 min=0）" @click="setPopQty(-1)">−</button>
              <input type="text" :value="popupQty[popupCurrentItem.productCode] || 0" @input="setPopQtyInput($event.target.value)" />
              <button type="button" title="加一（现有实现 max=100）" @click="setPopQty(1)">+</button></span></div>
          </div>
        </div>
        <div class="p-body"><div class="p-head"><span>商品</span><span>单价</span><span>数量</span><span>小计</span><span>操作</span></div>
          <div class="p-rows">
            <div class="p-row" v-for="(it, idx) in popupBuyList" :key="idx">
              <div class="thumb"><img v-if="it.goodImg" :src="it.goodImg" /></div>
              <div class="nm"><div class="t">{{ goodName }}</div><div class="sku">规格：{{ it.productCode }}</div><div class="sku">订单号 {{ popupOrderId }}</div></div>
              <div class="unit">¥{{ Number(it.price).toFixed(2) }}</div>
              <div class="num"><button type="button" class="pm" title="减一（`setPopQtyByCode`，沿用 min 0）" @click="setPopQtyByCode(it.productCode, -1)">−</button><span class="n">{{ it.quantity }}</span><button type="button" class="pm" title="加一（`setPopQtyByCode`，沿用 max 100）" @click="setPopQtyByCode(it.productCode, 1)">+</button></div>
              <div class="sub">¥{{ (it.price * it.quantity).toFixed(2) }}</div>
              <button type="button" class="del" title="移出本行（现有实现 `delPopItem`）" @click="delPopItem(it.productCode)">删除</button>
            </div>
          </div>
          <aside class="p-sum"><div class="p-sum-h">结算</div>
            <div class="p-sum-r"><span class="k">商品件数</span><span class="v">{{ popupCount }}</span></div><div class="p-sum-r"><span class="k">商品总额</span><span class="v">¥{{ Number(popupTotal).toFixed(2) }}</span></div><div class="p-sum-r" v-if="popupEntry === 'buy'"><span class="k">优惠券</span><span class="v"><span class="cp" :class="{ off: !couponPickable }" :title="couponHint"><select v-model="couponId" :disabled="!couponPickable"><option value="">{{ usableCoupons.length ? '不使用优惠券' : '暂无可用券' }}</option><option v-for="c in usableCoupons" :key="c.CouponId" :value="c.CouponId" :disabled="!!couponBlockedReason(c)">{{ couponLabel(c) }}{{ couponBlockedReason(c) ? '（' + couponBlockedReason(c) + '）' : '' }}</option></select><b v-if="discount > 0" class="save">−¥{{ Number(discount).toFixed(2) }}</b></span></span></div>
            <div class="p-sum-r"><span class="k">运费</span><span class="v">免运费</span></div><div class="p-sum-t"><span class="k">合计</span><span class="v"><i>¥</i>{{ Number(payTotal).toFixed(2) }}</span></div>
            <button type="button" class="p-confirm" @click="confirmPurchase">{{ popupEntry === 'cart' ? '加入购物车' : '结算' }}</button>
          </aside></div>
      </div>
    </div>

    <!-- 付款确认弹窗 —— 稿的 `dlg2` 面（`ProductPage.html:769-789`），**既有真功能**
         （`handlePay` → 'buy' 支 `order.orderAdd` / 'cart' 支 `cart.cartAdd`）⇒ 按「稿里画了一律落」照落。
         ⚠️ 稿是「否」在前、「是」在后，与既有实现相反 ⇒ 本笔照稿，属**有意改动**（登记）。
         旧 `.pay-confirm-*` / `.dialog-*` / `.yes-btn` / `.no-btn` 一并退役。⚠️ **2026-10-08 用户六条【三】**：文案按入口分（'cart' →「是否加入购物车？」／'buy' →「是否现在付款？」）；**「否」= 关框 + 保留弹窗与已选数据、一个请求都不发**（旧实现「否」也往下走 ⇒ 两支都掉 `order.orderAdd`，用户点名的 bug）；⚠️ **C3 块 4 改判**：`PayIf` 不再由入口现算 —— 两支已彻底分家（cart 支根本不碰 `order` 表）；⚠️ **2026-10-10（Bug 1）**：'buy' 支的 `PayIf` 由 `1` 改回 **`0`**（先建待付款单），**接着弹共享收银台** —— 改前那枚 `1` 让这单「没付款就成了已付款，且三列支付明细永远为空」。 -->
    <div class="scrim" v-if="showPayConfirm" @click.self="showPayConfirm=false">
      <div class="dlg dlg-narrow">
        <div class="dlg-h">{{ popupEntry === 'cart' ? '是否加入购物车？' : '是否现在付款？' }}</div>
        <div class="dlg-sub">{{ popupEntry === 'cart' ? '确认后写入购物车（端点 cart.cartAdd），可在「购物车」tab 改量 / 结算。' : '确认后生成**待付款**订单（端点 order.orderAdd），并打开收银台选择支付方式。' }}</div>
        <div class="dacts">
          <button type="button" class="b no" @click="handlePay(false)">否</button>
          <button type="button" class="b ok" @click="handlePay(true)">是</button>
        </div>
      </div>
    </div>

    <!-- 收银台（2026-10-10，Bug 1）—— 与「我的订单」的【付款】**同一个组件**：
         订单建成（待付款）后立刻摆上来，不再「绕过收银台直接成已付款单」。
         ⚠️ 付完与关掉都走 `afterCashier`（落「我的订单」），区别只在付没付。 -->
    <CashierDialog :order="payFor" @close="afterCashier" @paid="afterCashier" />

    <!-- 举报弹窗（方案 §4.4）—— 四个理由单选 + 「其他」的 100 字文本框；提交走 `/report/add`。
         对象是 商品 / 商家 / 评价 / 回复 / 提问 **同一个弹窗**，靠 `rptTarget` 带过去。
         ⚠️ **2026-10-13 抽成共享组件** `components/ReportDialog.vue`：店铺主页（`ShopHome.vue`）的
         「举报商家」要用同一段 ⇒ 按仓规「同一件事不抄第二份」收进组件，两个页面都渲染它。
         本页只剩「开着没、举报谁」两个状态（原 23 行内联模板换成了下面这一行）。 -->
    <ReportDialog :open="rptOpen" :target="rptTarget" @close="closeReport" />
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch, onBeforeUnmount } from 'vue'   // ⚠️ **2026-10-08 用户五条【1】**新增 `watch` / `onBeforeUnmount`（body 锁那一处用）—— **同行追加、不新增行**。
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'; import { orderAdd, cartAdd } from '../api/order'   // ⚠️ C3 块 4：**同行追加**（不新增行 —— 本文件 `:794`/`:803` 被 `slot-registry.md:1994-1995` 按行号引（〈Bug 1〉本笔 +19 行前为 `:775`/`:784`），此处加行会顶漂它们）。两个函数就是原先那两串 `URLSearchParams` + `http.post` + `res.json()` 的收口
import CashierDialog from '../components/CashierDialog.vue'; import ProductCard from '../components/ProductCard.vue'; import ReportDialog from '../components/ReportDialog.vue'; import { sharedList } from '../config/goods-flags'; import { useInfiniteList } from '../composables/useInfiniteList'   // ⚠️ **同行追加** `sharedList`（C3 块 5 ·〈Bug 2/3〉落码，2026-10-12）：本页只用它算「猜你喜欢」（见 `:522` 那条按语）。本行已有一条「本笔确实新增了一行」的记账（2026-10-10 Bug 1），**这一笔不再加行** —— 本文件下面 `:571` / `:638` / `:794` / `:1032` / `:1330` 等处被文档按行号钉着   // ⚠️ 2026-10-10（Bug 1）：收银台与「我的订单」**共用同一个组件** —— 本行**确实**新增了一行；本笔在 `ProductPage.vue` 净 +19 行（`git diff` 实测：模板 `:412` 起 +5、import +1、`handlePay` 段 +13）⇒ 下面两处 `:775`/`:784` 漂到 `:794`/`:803`，随本笔一并重指（见 `slot-registry.md:1994-1995`）
import { getMerchantId, getCustomerId, getUsername, isLoggedIn, getRole } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'; import { coupons, claim, subsidyGoods, seckillLive } from '../api/marketing'; import { couponName } from '../config/coupon-format'; import { useCouponPick } from '../composables/useCouponPick'; import { list as commentList, add as commentAdd, remove as commentRemove, accept as commentAccept, like as commentLike, myOrders as commentOrders } from '../api/comment'; import DonutChart from '../components/charts/DonutChart.vue'   // ⚠️ **块 6 整改 commit 2〈问答漏项补齐〉同行追加** `DonutChart`（§4.2「左圆比例图」，与 `views/admin/AccountsPage.vue` 的「封禁分布」同款）—— **不新增行**，故本行行号不动。⚠️ **整改 commit 4〈举报〉曾同行追加** `reportAdd`（`/report/add`；本页的「举报 / 提删」弹窗，§4.4 与 §4.2 的商家提删走同一条）—— **2026-10-13 撤出**：举报弹窗抽成 `components/ReportDialog.vue` 后本页不再自己发 `/report/add`，该 import 随弹窗搬进组件（本行仍**不新增行**）。⚠️ 六条 import **同行**（第 6 条 `../api/comment` 的六个别名：块 6 · commit 1「评论」+ commit 2「问答」的 `accept`，2026-10-12 —— 别名是必需的，`list`/`add` 这种裸名在本文件里跟 `allItems` 那些局部名撞脸，读不出是谁的）（第 5 条 `useCouponPick`：块 5 · commit 5 续笔「立即购买」也能用券，2026-10-12；与 `ShoppingCart.vue` 共用同一处，不抄第二份）（第 2、3 条：2026-10-11 补图标笔 + C3 块 5 · commit 1；第 4 条 `couponName`：块 5 · A 组 · A7 后半；`getUsername`：块 5 券口径改登录名，2026-10-12）—— 合并到既有行 = 不新增行号漂移（本文件 `:794`/`:803` 被 `slot-registry.md:1994-1995` 按行号引，上面 `:424` 那条注释记过同一件事）。⚠️ **块 5 · commit 1 仍整体 +90 行**（模板 +17 / `<script setup>` 尾 +70 / `<style>` +3）⇒ `slot-registry.md:1994` 引的 `:794` 漂 **`:881`**、`:1995` 引的 `:803` 漂 **`:890`**（两值均为 `grep -n` 实测，见 `_work/b5-repoint.md`）。⚠️ **块 6 · commit 1 又整体 +N 行**、**commit 2（问答）再 +M 行**，漂移台账见 `_work/b6-commit1-repoint.md` 与 `_work/b6-commit2-repoint.md`（收口 commit 逐处重指）。

const route = useRoute()
const router = useRouter()

// 从路由参数获取商品所属商家ID (卖家)
const { MerchantId, GoodName, GoodType, Id, ActivityId: activityId } = route.query   // ⚠️ C3 块 7 · commit 2c：`ActivityId` **只在「从秒杀页点进来」时才有**（`SeckillPage.productLink` 带过来的第五个 query）；其余入口（首页 / 商品卡 / 直接输 URL）是 `undefined` ⇒ 下单时发空串、后端 `required=false` ⇒ 那些入口与本笔之前**逐字同行为**。⚠️ 改名成小写 `activityId` 是为了与其它四个 query 键的「原样解出」区分开（它是本页**唯一**一个「有条件」的键）。**同行追加、不新增行**（本文件多处行号被文档按行号引）。
const sellerId = MerchantId
const goodName = GoodName
const goodType = GoodType
const productId = Id

// 获取当前登录用户的ID（买家）
let buyerId = getCustomerId()
if (!buyerId) {
  buyerId = getMerchantId()
}
const customerId = buyerId || ''

// 数据定义
const allItems = ref([])
const currentIndex = ref(0)
const currentItem = computed(() => allItems.value[currentIndex.value] || {})
// ── 块 5〈折扣〉：划线价 + 折扣角标（读侧现算，见模板 ① 的注释）────────────────
// `originalPrice` 由 `/goods/productCodePrice` 透出（本笔给那条 SELECT 加的列，响应键加性）。
// ⚠️ 老响应没有这个键 ⇒ `undefined` ⇒ `hasDiscount` 为假 ⇒ **一枚都不画**（不画 ≠ 画假的）。
const hasDiscount = computed(() => {
  const o = Number(currentItem.value.originalPrice)
  const p = Number(currentItem.value.price)
  return o > 0 && p > 0 && o > p
})
const discountRate = computed(() => {
  const o = Number(currentItem.value.originalPrice)
  const p = Number(currentItem.value.price)
  if (!(o > 0) || !(p > 0)) return ''
  const r = Math.round((p / o) * 10 * 100) / 100
  return Number.isInteger(r) ? String(Math.trunc(r)) : String(r)   // ⚠️ **原写 `Math.rint(r)`**（C3 块 5〈折扣〉落码时的口误）—— `Math.rint` 是 **Java 的**，JS 的 `Math` 上没有这个函数 ⇒ 一求值就 `TypeError: Math.rint is not a function`，**整支 render 函数抛出** ⇒ 本页停在「上一次成功的渲染」上（`allItems` 还空着的那一帧）⇒ 用户看到的就是**标题在（它来自 `route.query.GoodName`）、价格只剩 `¥`、型号空、图空**。⚠️ 它**只在 `discountRate` 被求值时**才炸，而模板只在 `hasDiscount` 为真时读它 ⇒ **全库 43 件在架商品里只有 2 件（有划线价的）中招**，另外 41 件照常 —— 这正是它藏了一整轮的原因。判据改成 `Number.isInteger`（`v === Math.rint(v)` 的原意就是「v 是整数」）。2026-10-12 修，同行替换、不新增行。
})
// ── 块 7 收尾 · P1：`goodsSelectHome` 已改为真分页 ──────────────────────────
// 本页原先靠「拉全量再 find」拿两样东西：① 当前商品的店名（`UserName`）；② 猜你喜欢。
// 端点分页后**当前商品不一定落在第一页** ⇒ 店名单独按 `MerchantId` 取一小页来 find；
// 猜你喜欢改成 `useInfiniteList`（滚到底再要一页，与首页同一份实现）。
const sellerUserName = ref('')
// 当前这件商品的整行（`Detail` / `Spec` / `AfterSale` / 服务承诺位都从它读）。
// 端点分页后不能再去「全量列表」里 find ⇒ 由按 `MerchantId` 取的那一页兜住。
const currentGood = ref(null)
const REC_PAGE_SIZE = 24
const recFeed = useInfiniteList(async (offset, limit) => {
  const res = await http.post(endpoints.goods.goodsSelectHome,
    new URLSearchParams({ limit: String(limit), offset: String(offset) }))
  const data = await res.json()
  if (!data.success) throw new Error(data.msg || '请求失败')
  return { rows: data.list || [] }
}, REC_PAGE_SIZE)
// 推荐位**不推当前这件**：按「商家 + 品名」滤掉（与 `loadAllItems` 认货的判据同一条）。
const recommendGoods = computed(() => sharedList(recFeed.items.value).filter(
  (g) => !(String(g.MerchantId) === String(sellerId) && g.GoodName === goodName)
))
const recSentinel = recFeed.sentinel
const recLoading = recFeed.loading
const recDone = recFeed.done
const recErrMsg = recFeed.errMsg
const recRetry = recFeed.retry
// 旧注释（2026-10-12）说「`goodsWithUser` 不能被 `sharedList` 换掉，因为 `loadAllItems` 还要
// 靠它 find 店名」—— 那个约束随本笔一起消失：店名改由 `sellerUserName` 单独承载（见上）。

const start = ref(0)
const showImages = computed(() => allItems.value.slice(start.value, start.value + 3))
const realIdx = (i) => start.value + i

const scroll = (d) => {
  const m = allItems.value.length - 3
  if (m <= 0) return
  start.value = Math.max(0, Math.min(m, start.value + d))
}

// 加载商家信息（店名）+ 猜你喜欢第一页
const loadGoodsWithUser = async () => {
  try {
    const tasks = [recFeed.loadNext()]
    // 店名：按 `MerchantId` 取一页（上限 100）来 find，不依赖「当前商品在全量第一页」。
    if (sellerId) {
      tasks.push(http.post(endpoints.goods.goodsSelectHome,
        new URLSearchParams({ MerchantId: String(sellerId), limit: '100', offset: '0' }))
        .then((r) => r.json())
        .then((d) => {
          if (!d.success) return
          const hit = (d.list || []).find(
            (item) => String(item.MerchantId) === String(sellerId) && item.GoodName === goodName
          )
          if (hit) {
            currentGood.value = hit
            sellerUserName.value = hit.UserName
          }
        }))
    }
    await Promise.all(tasks)
    if (recFeed.errMsg.value) handleApiError(new Error(recFeed.errMsg.value), '获取商家名失败：')
  } catch (e) {
    handleApiError(e, '获取商家名失败：')
  }
}

// 加载所有型号的商品详情
const loadAllItems = async () => {
  try {
    const r1 = await http.post(endpoints.goods.goodsProductCodeSelect, `MerchantId=${sellerId}&GoodName=${goodName}`)
    const d1 = await r1.json()
    const codes = d1.list || []
    const items = []
    for (let code of codes) {
      const r2 = await http.post(endpoints.goods.productCodePrice, `MerchantId=${sellerId}&GoodName=${goodName}&ProductCode=${code}`)
      const d2 = await r2.json()
      if (d2.success) {
        items.push({
          productCode: code,
          price: d2.price,
          originalPrice: d2.originalPrice, // 块 5〈折扣〉：划线价（0/undefined = 不打折）
          goodImg: d2.goodImg,
          UserName: ''
        })
      }
    }
    if (sellerUserName.value) items.forEach((it) => { it.UserName = sellerUserName.value })
    allItems.value = items
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

// 弹窗相关状态
const showPopup = ref(false); const popupEntry = ref('buy')   // **C 笔 C2**：购入模块的**入口标记**（'buy' = 立即购买 / 'cart' = 加入购物车）⇒ 底部弹窗那枚 CTA（`.p-confirm`）按它取文案。⚠️ **同行追加、不新增行** —— `slot-registry.md:1994-1995` 引的 `:794`/`:803`（〈Bug 1〉前为 `:775`/`:784`）都在下面，新增行会顶漂它们。
const popupAllItems = ref([])
const popupCurrentIndex = ref(0)
const popupCurrentItem = computed(() => popupAllItems.value[popupCurrentIndex.value] || { productCode: '' })
const popupQty = ref({}); const popupOrderId = ref(''); const popupKey = ref('')   // **用户 2026-10-08「补充项」**：本单号 —— 弹窗一开就生成，**行内「订单号」显示的是这一枚，`confirmPurchase` 上报的也是同一枚**（所见即所报，不是另造的预览号）。⚠️ 一单一号（`order.orderAdd` 每次提交只带一行 `OrderId`）⇒ **各行同号**，与 `图3.jpg` 每行不同号**有意不同**。⚠️ **2026-10-08 用户六条【二】在同一行尾补 `popupKey`**（= 这份弹窗数据**归属哪件商品**）：开窗时只比这一个键 —— **同键 ⇒ 整块数据原样留着**（这就是「关闭 ≠ 清数据」：点空白 / 点 X 只置 `showPopup=false`，一行都不动），**异键 ⇒ 重置**。⇒ 清数据的路**只剩两条**：① 刷新页面（组件重建、refs 归零，`popupKey` 也归空）② 商品身份变了。⚠️ 键取**当前商品**（`MerchantId|GoodName|Id`）、**不取商品页大类**（用户逐字：「「该商品页面」= 当前这件商品，不是商品页大类」）。

const openPopup = async (entry = 'buy') => { popupEntry.value = entry   // **C 笔 C2**：入口标记落在开头（同样**同行**写法，理由同上）；`@click` 两处必须显式传参 —— 直接写 `@click="openPopup"` 会把事件对象当 `entry` 传进来。⚠️ **2026-10-08 用户六条【二】**：开窗**不再无条件重置** —— 先按 `route.query` **现取**商品身份（**不**用 setup 期解构的 `sellerId`/`goodName`：同路径只换 query 时组件被复用、那两个 const **不会更新**）。
  const pk = `${route.query.MerchantId}|${route.query.GoodName}|${route.query.Id}`; const sameGood = pk === popupKey.value && popupAllItems.value.length > 0; await loadPopupItems()   // **2026-10-08 六条【二】**：`sameGood` 为真 ⇒ 下面那一整段重置**整段跳过**（`popupQty` / `popupOrderId` / `popupCurrentIndex` 全留着）⇒「关掉再开，数据还在」。`popupAllItems` 仍每次都重取（价格 / 图刷新），但**只换型号表、不碰已选数量**（数量按 `productCode` 存，不受换对象影响）。
  if (!sameGood) {   // 只在「首次开窗」或「换了商品」时跑；刷新页面 = 组件重建 ⇒ `popupKey` 归空（`''`）⇒ 也走这一支
    const q = {}; popupAllItems.value.forEach((i) => (q[i.productCode] = 0)); popupQty.value = q
    popupCurrentIndex.value = 0; popupOrderId.value = generateOrderId(); const firstCode = popupAllItems.value[0] && popupAllItems.value[0].productCode; if (firstCode) popupQty.value = { ...popupQty.value, [firstCode]: 1 }; popupKey.value = pk   // **用户 2026-10-08「补充项」**：开窗即选中首个型号 1 件（旧代码全置 0 ⇒ 弹窗一开下半只有表头 + 空结算栏，正是用户看到的那张「共 0 件」）。⚠️ `generateOrderId` 是同 `<script setup>` 里的 `const` 箭头函数（定义在本函数**之后**）—— 本函数只在点击时跑，那时已初始化，无 TDZ 问题。
  }
  showPopup.value = true
}

const loadPopupItems = async () => {
  try {
    const r1 = await http.post(endpoints.goods.goodsProductCodeSelect, `MerchantId=${sellerId}&GoodName=${goodName}`)
    const d1 = await r1.json()
    const codes = d1.list || []
    const items = []
    for (let code of codes) {
      const r2 = await http.post(endpoints.goods.productCodePrice, `MerchantId=${sellerId}&GoodName=${goodName}&ProductCode=${code}`)
      const d2 = await r2.json()
      if (d2.success) {
        items.push({
          productCode: code,
          price: d2.price,
          goodImg: d2.goodImg
        })
      }
    }
    popupAllItems.value = await mergeSkus(items)   // 2026-10-11：有 SKU ⇒ 型号表换成 SKU 行（`mergeSkus` 定义在下方 `specRows` 那一行尾）；无 SKU ⇒ 原样返回 `items`（与改前逐字同）。⚠️ 同行替换，**不新增行**（行号互引约束同 `:794`/`:803`）
  } catch (e) {
    handleApiError(e, '请求失败')
  }
}

const setPopQty = (step) => {
  const code = popupCurrentItem.value.productCode
  if (!code) return
  const current = popupQty.value[code] || 0
  let num = current + step
  num = Math.max(0, Math.min(100, num))
  popupQty.value = { ...popupQty.value, [code]: num }
}

const setPopQtyInput = (val) => {
  const code = popupCurrentItem.value.productCode
  if (!code) return
  let num = parseInt(val) || 0
  num = Math.max(0, Math.min(100, num))
  popupQty.value = { ...popupQty.value, [code]: num }
}

const delPopItem = (code) => {
  popupQty.value = { ...popupQty.value, [code]: 0 }
}; const setPopQtyByCode = (code, step) => { const cur = popupQty.value[code] || 0; popupQty.value = { ...popupQty.value, [code]: Math.max(0, Math.min(100, cur + step)) } }   // **用户 2026-10-08「补充项」**：per-code 的 ±（行内那对「− N +」用它）—— 与 `setPopQty` **同一套钳位**（min 0 / max 100），唯一差别是键取入参而不是「当前型号」。⚠️ 上一版注释说「做成活的要新增 per-code setter ⇒ 属新增功能、C3 未开」—— **本笔按用户明文开了这一件**（用户逐字要「± 数量」落在行内）。

const popupBuyList = computed(() => {
  return popupAllItems.value
    .filter((i) => (popupQty.value[i.productCode] || 0) > 0)
    .map((i) => ({
      productCode: i.productCode,
      quantity: popupQty.value[i.productCode],
      price: i.price, originalPrice: i.originalPrice,   // ⚠️ **同行追加**（本文件 `:794`/`:803` 被引，且 `:1032` 那类 `<style>` 引用都在下面 ⇒ 多一行就顶漂）。块 5〈用券规则〉2026-10-12 要用它算「本单含不含折扣商品」；`popupBuyList` 原来是 `productCode/quantity/price/goodImg` 四件，`originalPrice` 只有 `popupAllItems` 有（`:559`）。
      goodImg: i.goodImg
    }))
})

const popupTotal = computed(() => {
  return popupBuyList.value.reduce((sum, item) => sum + item.price * item.quantity, 0)
}); const popupCount = computed(() => popupBuyList.value.reduce((sum, it) => sum + it.quantity, 0))   // **商品件数** = Σ 数量（`图3.jpg` 那份 1+2+3 = 6）。⚠️ 旧 `.p-foot` 写的是 `popupBuyList.length` = **行数**（三行读成 3 件）⇒ 本笔按用户「商品件数」的口径改成数量之和。

// 订单提交
const showPayConfirm = ref(false); const lockScroll = (on) => { document.body.style.overflow = on ? 'hidden' : '' }; watch(showPopup, lockScroll); onBeforeUnmount(() => lockScroll(false))   // ⚠️ **2026-10-08 用户五条【1】**：弹窗开 = **锁 body 滚动**、关 = 解锁（用户逐字：「满占 = 弹窗 + 背后页面一起锁」）。⚠️ 用 `watch` 一处收口、**不在 `openPopup` 与各关闭点各写一行** —— 关闭有两条路（点旁边空白 / 点 X）都只置 `showPopup=false`。⚠️ `onBeforeUnmount` 是**兜底**：`/product` 是两壳的**兄弟**路由 ⇒ 切走即卸载，不兜底会把全站锁死。⚠️ **只写 `<body>` 就够**（探针 ⑪ 有对照：开窗时把这一行撤掉、同一发真滚轮立刻能滚）—— `document.scrollingElement` 虽是 `<html>`，但 `overflow:hidden` 会从 body **传给视口**。⚠️ **同行追加、不新增行**（本文件 775 / 784 两行在下面，新增行会顶漂它们）。
let pendingOrderData = null

const generateOrderId = () => {
  const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
  let randStr = ''
  for (let i = 0; i < 4; i++) {
    randStr += letters.charAt(Math.floor(Math.random() * letters.length))
  }
  const numbers = Math.floor(Math.random() * 100000000).toString().padStart(8, '0')
  return randStr + numbers
}

const buildProductInformation = () => {
  return popupBuyList.value.map((item) => `${goodName}:${item.productCode}`).join('；')
}

const getFirstImage = () => {
  if (popupBuyList.value.length === 0) return ''
  const firstCode = popupBuyList.value[0].productCode
  const target = popupAllItems.value.find((i) => i.productCode === firstCode)
  return target ? target.goodImg : ''
}

const confirmPurchase = () => {
  if (!customerId) {
    alert('请先登录再购买商品'); if (!isLogin.value) router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (popupBuyList.value.length === 0) {
    alert('请至少选择一件商品')
    return
  }
  pendingOrderData = {
    CustomerId: customerId,
    MerchantId: sellerId,
    OrderId: popupOrderId.value || generateOrderId(),   // 上报的就是行内「订单号」显示的那一枚（`popupOrderId` 为空时才另生成 —— 兜底，正常路径走不到）
    Price: popupTotal.value,
    ProductInformation: buildProductInformation(),
    Images: getFirstImage(),
    DeliveryIf: 0,
    ReceiveIf: 0
  }
  showPayConfirm.value = true
}

const handlePay = async (ok) => {   // **C3 块 4 改判 + 2026-10-10 补丁改判**：'cart' 支**根本不碰 order 表**（逐型号写 `/cart/add`）；'buy' 支改发 **`PayIf: 0`** —— 先建**待付款**单，再**弹共享收银台**（Bug 1：改前 `PayIf: 1` ⇒ 商家还没发货、这单却是「已付款」，且 `TradeNo/PaymentMethod/PaidAt` 三列永远为空）。⚠️ 块 4 前两支都掉 `order.orderAdd`，那正是用户点名的 bug（「结算取消时顺手把商品加进了购物车」）。
  showPayConfirm.value = false
  if (!ok || !pendingOrderData) { pendingOrderData = null; return }   // ⚠️ **「否」在此就返回** ⇒ 一个请求都不发、弹窗与已选数据**原样保留**（用户表：「不加入购物车也不付款，回弹窗保留数据」）。

  const d = pendingOrderData   // 下面两支共用这一份快照（块 4 前它叫 `orderData` 并多带一个 `PayIf`）
  if (popupEntry.value === 'cart') {   // ① 加入购物车 ⇒ 写 `cart` 表。⚠️ 车**按 ProductCode 计行** ⇒ 逐个型号发一次（同一件商品的多个型号在车里是多行）
    try {
      const fails = []
      for (const item of popupBuyList.value) {
        const r = await cartAdd({ CustomerId: d.CustomerId, MerchantId: d.MerchantId, ProductCode: item.productCode, Quantity: item.quantity })
        if (!r.success) fails.push(item.productCode + '：' + (r.msg || '未知错误'))
      }
      if (fails.length === 0) {
        alert('已加入购物车！'); showPopup.value = false; popupQty.value = {}; router.push(getMerchantId() ? '/merchant/order/cart?tab=cart' : '/customer/cart?tab=cart')
      } else alert('部分型号未能加入购物车：\n' + fails.join('\n'))   // ⚠️ 部分成功要**如实报**：失败的那几件没进车、成功的那几件**已经在车里**了
    } catch (error) { console.error('加入购物车出错：', error); alert('网络错误，请稍后重试') } finally { pendingOrderData = null }
    return
  }

  // ② 立即购买 ⇒ 原链路（`order.orderAdd` ⇒ 写入 `order` 表）+ **就地弹收银台**（与「我的订单」同一份组件）
  try {
    const result = await orderAdd({
      CustomerId: d.CustomerId, MerchantId: d.MerchantId, OrderId: d.OrderId,
      Price: d.Price, ProductInformation: d.ProductInformation, Images: d.Images,
      PayIf: 0, DeliveryIf: d.DeliveryIf, ReceiveIf: d.ReceiveIf, ActivityId: activityId || '',   // C3 块 7 · commit 2c：**「这单是秒杀单」的唯一声明**（只有从秒杀页点进来这一路非空）。⚠️ 传了它 ⇒ 后端在**订单锁临界区内**按活动 CAS 扣 `seckill_activity.SoldStock`，**扣不到就不建单**（回 `success:false` + `秒杀活动已售完`）—— 这是本仓**唯一**一条 fail-closed 的下单路径（与库存扣减「0 行不挡单」刻意相反，理由见 `OrderService.addLocked` 秒杀段）；不传 ⇒ **一次库读都不发**。⚠️ `''` 是显式的（`build()` 原样发每一条，空串与不传后端同判 `isBlank`）。**同行追加、不新增行**。
      CouponId: popupEntry.value === 'buy' ? couponId.value : '',   // C3 块 5 · commit 5 续笔：券随本单走。⚠️ `Price` 仍是**原价合计**（`d.Price` = `popupTotal`）—— 后端 `orderAdd` 拿原价算折后额、落库 `order.Price` 的是**折后价**，折了多少看回体里的 `Discount`。⚠️ 空串 = 不用券（`api/order.js` 的 `build()` **原样发每一条**，空串后端当 `""` 处理，逐字同加券之前）。
      Quantity: popupCount.value, Items: JSON.stringify(popupBuyList.value.map((i) => ({ code: i.productCode, name: goodName, price: i.price, qty: i.quantity }))),   // C3 块 4：本单**总件数**（A2 甲新列；一个 OrderId 一单 ⇒ 不带型号维度）＋ ⚠️ **可视化段 commit 0 同行追加 `Items`**（显式商品明细 ⇒ 服务端写 `order_item`；格式是 JSON 数组串，理由见 `OrderService.resolveItems`：商品名未禁 `:`/`；`，自造分隔符会错位）。⚠️ **同行追加、不新增行** —— 本文件 775 / 784 两行在下面，新增行会顶漂它们。⚠️ `name` 取页级 `goodName`：弹窗里全是**同一商品**的型号（`popupAllItems`），故一件一名的前提成立；`price` 是**单价**（`popupTotal` 是 `Σ price × quantity`，:572）
    })

    if (result.success) {
      // ⚠️ **不**在这里 `alert('订单提交成功！')`、也**不**跳页：单已经建好（待付款），
      //    下一步是付款 ⇒ 把收银台摆上来。跳页与关窗都由付完/关掉收银台之后决定（见 `afterCashier`）。⚠️ **2026-10-10（Bug 1 二次修；用户手验：「收银台弹出，但被白色结算弹窗挡在后面」）**：`showPopup.value = false` 管的正是**购入模块弹窗**（`.popup-mask`，`:1109` 那条 `z-index:150`）—— 改前它一直挂着、而收银台 `.cscrim` 只有 `60` ⇒ 收银台**真在它下面**；结算确认弹窗（`showPayConfirm`，`.scrim` `:1036` = `200`）在 `:624` 已关，本行补的是同一条链上的**第二层**（两层都要关，只关一层另一层照样盖住）。层级自身另在 `CashierDialog.vue:103` 提档。
      // ⚠️ 收银台显示的是**实付**（`d.Price` − 券的 `Discount`）—— 落库的 `order.Price` 已是折后价，
      //    收银台再按原价收钱就是「页面说便宜了、付款还是原价」。没券时 `Discount` 缺省 0 ⇒ 逐字同改前。
      // ⚠️ 只在**成功**这一支清券选择并重拉券包：失败时后端**没**烧券（CAS 抢不到就不建单，见
      //    `OrderService.addLocked`），留着选择让用户直接重试（同购物车页那条）。
      resetCoupon(); loadCoupons()
      showPopup.value = false; payFor.value = { OrderId: d.OrderId, Price: Math.max(0, Number(d.Price) - Number(result.Discount || 0)) }
    } else {
      alert('订单提交失败：' + (result.msg || '未知错误'))
    }
  } catch (error) {
    console.error('提交订单出错：', error)
    alert('网络错误，请稍后重试')
  } finally {
    pendingOrderData = null
  }
}

/* ── 收银台（2026-10-10，Bug 1）─────────────────────────────────────────────────
   商品详情页的【立即购买】与「我的订单」的【付款】**共用** `components/CashierDialog.vue`。
   ⚠️ 付款成功与「关掉不付」都要**落到「我的订单」**：那条单已经存在（待付款），
      留在商品页会让它变成一个看不见的孤儿单（这一页没有任何入口能再找到它）。 */
const payFor = ref(null)          // 非 null ⇒ 收银台开着，值 = 刚建的那一单

/** 收银台付完 / 关掉之后：收摊 + 回「我的订单」（待付款的单排在那一页，带【付款】按钮）。 */
const afterCashier = () => {
  payFor.value = null
  showPopup.value = false
  popupQty.value = {}
  router.push(getMerchantId() ? '/merchant/order/cart?tab=orders' : '/customer/cart?tab=orders')
}

// 搜索功能
const searchKeyword = ref('')
const handleSearch = () => {
  const keyword = searchKeyword.value.trim()
  sessionStorage.setItem('PENDING_SEARCH_KEYWORD', keyword)
  const isMerchant = !!getMerchantId()
  router.push(isMerchant ? '/merchant' : '/customer')
}

const goBack = () => {
  const isMerchant = !!getMerchantId()
  if (route.query.from === 'search') {
    router.push(isMerchant ? '/merchant?back=search' : '/customer?back=search')
  } else {
    router.push(isMerchant ? '/merchant' : '/customer')
  }
}

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

// ========== 咨询商品功能 ==========
const consultProduct = async () => {
  if (!customerId) {
    alert('请先登录'); if (!isLogin.value) router.push({ path: '/login', query: { redirect: route.fullPath } })
    return;
  }
  if (customerId === sellerId) {
    alert('不能咨询自己的商品');
    return;
  }
  const selectedProduct = currentItem.value;
  if (!selectedProduct.productCode) {
    alert('请选择商品型号');
    return;
  }
  const productInfo = {
    id: productId,
    merchantId: sellerId,
    goodName: goodName,
    price: selectedProduct.price,
    goodImg: selectedProduct.goodImg,
    goodType: goodType,
    productCode: selectedProduct.productCode
  };
  const messageContent = `[PROD]${JSON.stringify(productInfo)}`;
  const fromType = getCustomerId() ? 'customer' : 'merchant';
  const params = new URLSearchParams();
  params.append('fromId', customerId);
  params.append('fromType', fromType);
  params.append('toId', sellerId);
  params.append('toType', 'merchant');
  params.append('content', messageContent);
  try {
    const res = await http.post(endpoints.message.send, params.toString());
    const data = await res.json();
    if (data.success) {
      const isMerchant = !!getMerchantId();
      const basePath = isMerchant ? '/merchant' : '/customer';
      router.push({
        path: basePath,
        query: {
          page: 'chat',
          otherId: sellerId,
          otherType: 'merchant',
          _t: Date.now()
        }
      });
    } else {
      alert('咨询发送失败：' + data.msg);
    }
  } catch (err) {
    console.error(err);
    alert('网络错误，请稍后重试');
  }
};
const enterShop = () => router.push({ path: '/shop', query: { MerchantId: sellerId } })   // ⚠️ C3 块 5 B 组（2026-10-12）：「进店逛逛」的落点，只有这一处调用方（模板里那颗按钮）。⚠️ 本行**占的**是 `consultProduct` 与 `onMounted` 之间那个**空行**（空行已撤）—— 本文件 `:1032` / `:1107` / `:1330` 等被 `docs/` 按行号引（`slot-registry.md` 引 `:881`/`:890` 那两条同理），任何新增行都会顶漂它们 ⇒ **借一个空行放函数、净零**（同 `:592` 那条 `}; const setPopQtyByCode = …` 的手法）。⚠️ 传的只有 `MerchantId`：`ShopHome` 的店名从商品行的 `UserName` 取，本页不作第二个来源（理由见那一页的 `loadShop`）。
onMounted(async () => {
  await loadGoodsWithUser();
  loadAllItems();
});
// ── 未登录 UX 闭环（2026-10-06 收口笔，登记见 progress.md §5-AG (15)）：本页**不是**「进壳」────────
// R5（商品详情页进壳）仍不做；本笔只改页内三处未登录 UX —— 顶栏标签（:15，未登录显示「登录 / 注册」，
// 与 AppShell 的 ANON_RIGHT 同形态）、购买（:312）与咨询（:409）两处拦截的出口。
// ⚠️ 两处拦截的判据仍是 `!customerId`，**不动**：改成 `!isLogin` 会让**已登录商家**带着空 CustomerId
//    走到 `orderAdd`（拆真检查）；`isLogin` 只决定**要不要跳登录** ⇒ 已登录商家原地不动（= 既有行为）。
// ⚠️ redirect 取 `route.fullPath`（含本页 7 个 query），对象形式与 `main.js:49` 同款；登录后由
//    `LoginPage.landingAfterLogin` 送回本页（T-guard-6/-10）。放文件末尾是**刻意的**：`WebAuthConfig.java:71` 引了本文件的 `onMounted` 段（那里原先写的 `:462-465` 是**陈旧值**，本品已就地更正为 `:766`），新增行落在被引行之后，上面 24 个被引目标才一个都不漂。⚠️ **2026-10-10〈Bug 1 · 立即购买补接收银台〉本笔破了这条约束**：+19 行落在 `:412`/`:425`/`:666`（均在 `:766` 之前）⇒ `:794`/`:803` 两处被引行随本笔重指（见 `slot-registry.md:1994-1995`），并已回写 `slot-registry.md:1992/1994/1995/2445`。

// ── 优惠券（C3 块 5 · commit 1，2026-10-11）─────────────────────────────────────
// 主区 `:104` 与购入弹窗两处「促销 / 领券」**共用这一段**（一份数据、一对处理函数 —— 弹窗不是
// 第二个实现）。
//
// ⚠️ **身份用 `couponAccount`，不是本页既有的 `customerId`**：`customerId`（`:443`）自带一条
//    「客户号为空则退回商家号」的降级链 —— 那是给 `orderAdd` 用的（商家也能下单）。券不走那套：
//    `/marketing/*` 的 `Account` 语义是 **登录名**（`login.UserName`）—— 2026-10-12 用户裁定「乙」，
//    **由 `user.customerid` 改过来**（见 `CouponController` / `CouponDao` 类注释）。对商家来说
//    `customerid` 全是 NULL ⇒ 统一挂登录名，两端一份券包；没有登录名就整块不出券（同 `HomePage`）。
const couponAccount = getUsername() || ''
const claimList = ref([])
const claiming = ref('')

// ⚠️ **C3 块 5 · A 组 · A4（2026-10-12）**：领券行「几张算该收起」= **1**。
//    ⚠️ **手验后改**：原为 `2`（用户逐字「两张半 + 第三张截断」），但实测本版面券区只有 **315.9px** ⇒
//    两张半 = 每张 **132.1px**，而卡内内容最小 **197px**（溢出 65px）—— 二者互斥，用户裁定取可读：
//    `(315.9−12)/1.3 = **233.8px**`、第 2 张露 **70.1px（三成张）**。样式那三个 `1.3` / `78%` 要成对改。
const CP_VISIBLE = 1
/** 领券行的展开态（点「》」置真 ⇒ 样式 `.coupons-on` 放开 `nowrap`、渐隐同撤）。 */
const cpExpanded = ref(false)

/** `Claimed` 宽容判据 —— Connector/J 的 `tinyInt1isBit` 下可能是 `true`，也可能是 `1`。 */
const claimed = (c) => c.Claimed === true || c.Claimed === 1 || c.Claimed === '1'

const r2 = (v) => Math.round(Number(v || 0) * 100) / 100

/** 面值的**数字部分**。`¥` 号在模板里是单独一个 `<i>`，且折扣券**不出 `¥`**（模板里那个 `v-if`）。 */
const faceNum = (c) =>
  c.Type === 'percent' ? (Number(c.FaceValue || 0) * 10).toFixed(1) + '折' : String(r2(c.FaceValue))

/** 门槛的人话。`Threshold` 对 `none` 型由后端**强制**置 0 ⇒ 「无门槛」是数据说的，不是我猜的。 */
const faceCond = (c) => {
  if (c.Type === 'percent') return '折扣券'
  const t = Number(c.Threshold || 0)
  return t > 0 ? '满 ' + t + ' 可用' : '无门槛'
}

/** 「促销」行 = 满减类券拼成的「满 A 减 B」—— ⚠️ 这是本页**唯一**拿得到的促销数据（无独立促销表）。 */
const promoCuts = computed(() =>
  claimList.value
    .filter((c) => c.Type === 'threshold')
    .map((c) => '满 ' + Number(c.Threshold || 0) + ' 减 ' + r2(c.FaceValue))
)

const loadClaimable = async () => {
  if (!couponAccount) return
  try {
    // ⚠️ 第 4 参 `sellerId` = **本页在卖的那家店**（`route.query.MerchantId`）⇒ 后端只回
    //    `Scope='merchant'` 且 `MerchantAccount` 命中的券（A4：不再列全站平台券）。
    //    `sellerId` 为空时 `q()` 不发这个键 ⇒ 退回改动前的「全站可领」——**不是**「什么都看不到」。
    const data = await coupons(couponAccount, 6, undefined, sellerId)
    claimList.value = data && data.success && Array.isArray(data.list) ? data.list : []
  } catch (e) {
    handleApiError(e, '优惠券加载失败')
  }
}

/** 领券（落 `coupon_grant`）。⚠️ 与 `HomePage.vue` 的 `doClaim` 同款，但**不重拉券包**（本页无券包）。 */
const doClaimCoupon = async (c) => {
  claiming.value = c.CouponId
  try {
    const data = await claim(couponAccount, c.CouponId)
    if (data && data.success) {
      await loadClaimable()
      alert('已领取「' + c.Name + '」，可在「我的券包」查看')
    } else {
      alert('领取失败：' + ((data && data.msg) || '未知原因'))
    }
  } catch (e) {
    handleApiError(e, '领取失败')
  } finally {
    claiming.value = ''
  }
}

// ⚠️ 这是本文件**第二个** `onMounted`（Vue 允许注册多次）。不并进 `:783` 那个，是为了让本笔
//    在 `<script setup>` 里**只有一个插入点** —— 本文件所有 `文件:行号` 互引都在下面（`:794`/`:803`），
//    插入点越少，收口时的重指表越短。
onMounted(loadClaimable)

// ── 块 5〈国补〉：国家补贴标签（C3 块 5 · commit 3，2026-10-12）─────────────────────
// ⚠️ 与上面券块**同一个先例、同一个插入点**：`/marketing/subsidy/goods` 不挂闸、但**也不在白名单**
//    ⇒ 匿名 401 ⇒ 必须**先判登录**再发，否则 `http.js` 的 `handleUnauthorized` 会把商品页踢回登录页。
// ⚠️ 金额**后端现算**（`SubsidyAmount` / `PayAmount`，`Cap=0` = 不封顶），本页只显示，
//    **不复刻** `min(price × ratio, cap)` —— 复刻出来的第二份一定会在某次改动里跑偏。
// ⚠️ 只在**当前选中型号**的 `productCode` 命中时才画：同一 `GoodName` 下各型号价不同，
//    拿别的型号的补贴额画在这一屏上就是假话（与 ① 折扣角标同一条口径）。
const subsidyRows = ref([])
const subsidy = computed(() => {
  const code = String(currentItem.value.productCode || '')
  if (!code) return null
  return subsidyRows.value.find((r) => String(r.ProductCode || '') === code) || null
})
const loadSubsidy = async () => {
  if (!isLoggedIn()) return
  try {
    const data = await subsidyGoods({ limit: 100 })
    subsidyRows.value = data && data.success && Array.isArray(data.list) ? data.list : []
  } catch (e) {
    subsidyRows.value = []
  }
}
onMounted(loadSubsidy)

/* 2026-10-10〈模板表通用化〉三段快照文本（`goods.Detail` / `Spec` / `AfterSale`）—— 全部写在**同一物理行**（新增行会顶漂下面 `slot-registry.md:1994-1995` 引的 `:794`/`:803`）。⚠️ 取值**从 `route.query` 现取**、不用 setup 期解构的 `sellerId`/`goodName`（`:433-434` 是普通 `const`，同路径只换 query 时组件被复用、它们**不更新**，理由同 `openPopup` 那条）。 */ const detailText = computed(() => { const g = currentGood.value; return (g && g.Detail) || '' }); const specText = computed(() => { const g = currentGood.value; return (g && g.Spec) || '' }); const afterSaleText = computed(() => { const g = currentGood.value; return (g && g.AfterSale) || '' }); /* `Spec` 的行内 `键=值` 约定：一行一条，`=` 只切**第一处**（值里允许再有 `=`）；没有 `=` 的行**整行进 `.v`、`.k` 留空**，而不是丢掉它。 */ const specRows = computed(() => specText.value.split('\n').map((s) => s.trim()).filter((s) => s !== '').map((s) => { const i = s.indexOf('='); return i < 0 ? { k: '', v: s } : { k: s.slice(0, i).trim(), v: s.slice(i + 1).trim() } })); const promiseLabels = computed(() => { const g = currentGood.value; const ALL = ['正品保障', '7 天无理由', '极速退款', '运费险']; const KEYS = ['PromiseGenuine', 'PromiseReturn7', 'PromiseFastRefund', 'PromiseFreightIns']; if (!g) return ALL; if (!KEYS.some((k) => g[k] !== undefined && g[k] !== null)) return ALL; return ALL.filter((_, i) => Number(g[KEYS[i]])) }); const mergeSkus = async (baseItems) => { try { const rs = await http.post(endpoints.goods.skuList, `MerchantId=${sellerId}&GoodName=${goodName}`); const ds = await rs.json(); const skus = (ds && ds.success && ds.list) || []; if (!skus.length) return baseItems; const img = baseItems.length ? baseItems[0].goodImg : ''; const merged = skus.map((s) => ({ productCode: String(s.SkuCode || ''), price: s.Price, goodImg: img })).filter((s) => s.productCode); return merged.length ? merged : baseItems } catch (e) { return baseItems } }; const isLogin = computed(() => isLoggedIn()); const activeTab = ref('desc')   // 结构二次调整（2026-10-07）：Tab 条当前面，UI 展示状态（不计功能量）；⚠️ **同行追加、不新增行** —— 上面那条「新增行落在最大被引行之后」的约束在本笔**不成立**（`slot-registry.md:1994-1995` 引的 `:794`/`:803` 都在下面），新增行会顶漂它们。

// ── 块 5〈秒杀〉：当前型号的秒杀价（C3 块 5 · commit 4，2026-10-12）─────────────────────
// 本段落在 `<script setup>` 的**最末**（上面那行物理长行之后）：本文件的行号被
// `slot-registry.md` / `progress.md` / `OrderService.java` 多处按行号引（`:297-299` / `:311-317`
// / `:794` / `:803` / `<style>` 的 `:1032` 等）⇒ 新增行只有落在**最大被引行之后**，那些引用才一个都不漂。
// ⚠️ 与上面券块 / 国补块**同一个先例**：`/marketing/seckill/live` 不挂闸、**也不在白名单**
//    ⇒ 匿名 401 ⇒ 必须**先判登录**再发，否则 `http.js` 的 `handleUnauthorized` 会把商品页踢回登录页。
// ⚠️ 命中判据用 **`ProductCode`**（不是 `GoodName`）：同一 `GoodName` 下各型号价不同 —— 拿别的型号的
//    秒杀价画在这一屏上就是假话（与 ① 折扣角标、② 国补标签同一条口径）。
// ⚠️ 价与「售罄」都由**后端**说了算：`SeckillPrice` 直接读，`SoldOut` / `SoldLeft` **只读不算** ——
//    不限量那档（`TotalStock = 0`）的语义已经在 `SoldOut` 里定好了，别拿 `TotalStock` / `SoldStock` 自己比。
// ⚠️ `Stage` 由后端按**库时钟**现算（`SeckillDao.stage`）：只有 `live` 才画；`upcoming`（未开启）
//    与 `signup`（报名期）本页**都不画**（那句话归秒杀页 `/seckill` 说）。
const seckillRows = ref([])
const seckill = computed(() => {
  const code = String(currentItem.value.productCode || '')
  if (!code) return null
  const hit = seckillRows.value.find((r) => String(r.ProductCode || '') === code)
  return hit && hit.Stage === 'live' ? hit : null
})
const loadSeckill = async () => {
  if (!isLoggedIn()) return
  try {
    const data = await seckillLive({ limit: 100 })
    seckillRows.value = data && data.success && Array.isArray(data.list) ? data.list : []
  } catch (e) {
    seckillRows.value = []
  }
}
// ⚠️ 本文件**第三个** `onMounted`（Vue 允许注册多次）；不并进上面两个，理由同 `:903-905` 那条。
onMounted(loadSeckill)

// ── 块 5〈结算用券〉：购入弹窗里选券（C3 块 5 · commit 5 续笔，2026-10-12）────────────────
// ⚠️ 本段落在 `<script setup>` 的**最末**（同上面「秒杀」块那条理由）：本文件被 `slot-registry.md`
//    / `progress.md` 多处按行号引（`:297-299` / `:794` / `:803` / `<style>` 的 `:1032` 等）⇒
//    新增行只有落在**最大被引行之后**，那些引用才一个都不漂。
// ⚠️ 券逻辑**不自带算法** —— 与购物车吸底结算栏共用 `composables/useCouponPick.js` 那一处
//    （两条链各抄一份 ⇒ 抄错时两边都不报错，只是某一处的「合计」慢慢算成另一个数）。
// ⚠️ 只在**「立即购买」**入口可用（`popupEntry === 'buy'`）：「加入购物车」那一支**不带券**
//    —— 车上选券归购物车页（`CouponId` 是 `CartService.checkout` 的入参，不是 `cartAdd` 的）。
// ⚠️ `account` 用 `couponAccount`（`:837`）而不是本页 `customerId`：券包的键是**登录名**，
//    与上面领券行同一个身份口径（理由见 `:832-836` 那段）。
const {
  couponId, usableCoupons, couponPickable, couponHint, couponLabel, couponBlockedReason,
  discount, payTotal, loadCoupons, resetCoupon
} = useCouponPick({
  account: couponAccount,
  orderTotal: popupTotal,   // 本单**原价**合计：门槛比它、折扣按它算（后端 `redeem` 收的也是原价）
  enabled: computed(() => popupEntry.value === 'buy' && popupBuyList.value.length > 0),
  blockedHint: '请先选择至少一件商品', hasDiscount: computed(() => popupBuyList.value.some((i) => Number(i.originalPrice) > 0))   // ⚠️ **同行追加**（理由同上面那条）。本单含折扣商品 = 弹窗里**要买的那几行**（`popupBuyList`）任一行 `originalPrice > 0`；⚠️ 判据是**整单**级、不是逐行（见 `useCouponPick.couponBlockedReason`）。取 `popupBuyList` 而非 `popupAllItems`：后者是**全部型号**，没选中的型号不该影响本单 —— 该商品的划线价虽通常各型号同值，但按**本单实际行**判才与后端一致。
})

// 弹窗一开（且是「立即购买」）就重拉一次券包：用户可能刚在本页领券行领过一张 —— 不重拉的话
// 那张券要等刷新页面才出现在选择器里。⚠️ 落在这里而不是 `openPopup` 里：本文件的行号互引都在上面，
// 往 `openPopup` 加行会让它们集体顶漂（同上面那两条注释的理由）。
watch(showPopup, (on) => { if (on && popupEntry.value === 'buy') loadCoupons() })

// ── 块 5〈型号按钮类别〉：每个型号按**自己**的类别上色（2026-10-12 用户五处挂账裁定 ④-c）────
// ⚠️ 本段落在 `<script setup>` 的**最末**（同上面「秒杀」/「结算用券」两块那条理由）：本文件被
//    `slot-registry.md` / `progress.md` / `OrderService.java` 多处按行号引 ⇒ 新增行只有落在
//    **最大被引行之后**，那些引用才一个都不漂。
// 判据：该型号的 `productCode` 在不在「国补命中集」/「进行中秒杀集」里 —— 两个集合本页**早就在手上**
//    （`subsidyRows` / `seckillRows`，各自 load 一次、limit 200）⇒ **不新发请求**。
// ⚠️ 国补判据与 `subsidy` computed（:946-950）**同一口径**（只比 `ProductCode`）；秒杀判据与
//    `seckill` computed（:977-982）同一口径（必须 `Stage === 'live'`，`upcoming`/`signup` 不算）。
// ⚠️ **两者都命中时取秒杀**：秒杀是**限时**的，且同屏价格行上已有一整块 `.sk-zone` 在说它 ——
//    按钮上取国补会与那块自相矛盾。这是**裁量点**：用户若要反过来，只改下面 if 的先后。
const skuKind = (code) => {
  const c = String(code || '')
  if (!c) return 'normal'
  if (seckillRows.value.some((r) => String(r.ProductCode || '') === c && r.Stage === 'live')) return 'seckill'
  if (subsidyRows.value.some((r) => String(r.ProductCode || '') === c)) return 'subsidy'
  return 'normal'
}

// ── 累计评价（C3 块 6 · commit 1「评论」，2026-10-12）──────────────────────────────
// ⚠️ 本段落在 `<script setup>` 的**最末**（同上面「秒杀」/「结算用券」/「型号按钮类别」三块那条理由）：
//    本文件被 `slot-registry.md` / `progress.md` / `OrderService.java` 多处按行号引 ⇒ 新增行只有落在
//    **最大被引行之后**，那些引用才一个都不漂。
// ⚠️ 数据、资格（买过没有 / 是不是重复评价）、排序、匿名与评分口径**全在后端**（`CommentService`）：
//    这里**没有第二份判据** —— 抄一份就等着两边慢慢分叉。前端唯一「提前拦一下」的是空正文与超长，
//    那是为了别白跑一趟网络，不是权威。
// ⚠️ 身份 = **登录名**（与 `couponAccount` 同一条），**不是**本页 `customerId`：`comment.AuthorId`
//    存的就是登录名（见 `CommentDao` 类注释）。另起第二条「我是谁」= 两处慢慢分叉。
const revAccount = couponAccount
const REV_PAGE = 20
const REV_MAX_IMAGES = 9

const revRows = ref([])
const revStats = ref(null)
const revTotal = ref(0)
const revSort = ref('new')
const revLoading = ref(false)
const revError = ref('')
/**
 * 当前子 tab —— `review`（商品评论）/ `question`（问答）。
 *
 * ⚠️ **它是要发给后端的**（`kind`），不是纯前端过滤：两个子 tab 各是一次 `list` 往返，
 * 各自有各自的 `total` / `stats` / 分页游标。不这么做的话得先把全量拉回来再在内存里切，
 * 而「每页 20 条里混着两类」会让页大小变得没有意义。
 */
const revKind = ref('review')

const revIsQuestion = computed(() => revKind.value === 'question')
const revTotalLabel = computed(() => (revTotal.value > 0 ? String(revTotal.value) : '—'))
/** 平均分。⚠️ 一条评价都没有时是 `—` **不是 `0.0`** —— 「零条评价」与「零分」是两件事。 */
const revScore = computed(() => {
  const s = revStats.value
  return s && Number(s.Total) > 0 ? String(s.AvgRating) : '—'
})
/** 5→1 星的占比条。分母取 `Total`（后端已 `COALESCE` 过，缺项是 0 不是 null）。 */
const revBars = computed(() => {
  const s = revStats.value || {}
  const total = Number(s.Total) || 0
  return [5, 4, 3, 2, 1].map((star) => {
    const n = Number(s['S' + star]) || 0
    return { star, pct: total > 0 ? Math.round((n / total) * 1000) / 10 : 0 }
  })
})

// ── 问答：已解决 / 未解决比例（方案 §4.2「左圆比例图」）──────────────────────
/**
 * 已解决占比（0–100，整数）。⚠️ **一条提问都没有时是 `0`，但呈现上要显 `—`**
 * —— 用 {@link revQuestionNumbers} 渲染，别把 `0%` 画成「全都未解决」（那是两种不同的事实：
 * 一条都没有 vs 有 N 条全是未解决）。这个坑与 `revScore` 的 `—` vs `0.0` 同源。
 */
const revSolvedPct = computed(() => {
  const s = revStats.value || {}
  const total = Number(s.Total) || 0
  return total > 0 ? Math.round((Number(s.Solved) || 0) / total * 100) : 0
})
const revQuestionNumbers = computed(() => {
  const s = revStats.value || {}
  const total = Number(s.Total) || 0
  if (total === 0) return { solved: '—', unsolved: '—', label: '—' }
  return {
    solved: String(Number(s.Solved) || 0),
    unsolved: String(Number(s.Unsolved) || 0),
    label: revSolvedPct.value + '%',
  }
})
/**
 * 环图的两片（方案 §4.2「左圆比例图」的输入）。`{name, value}` 是 `DonutChart` 的契形。
 *
 * ⚠️ **一条提问都没有 ⇒ 空数组**（不是两片 0）—— `DonutChart` 见 `data.length === 0` 就落
 * 「还没有提问」的空态，而两片 0 在 echarts 里会画出一个**空环**（看着像「全都 0%」而不是
 * 「没有数据」）。这与 {@link revQuestionNumbers} 把 `—` 与 `0` 分开是同一条口径。
 */
const revQaMix = computed(() => {
  const s = revStats.value || {}
  if ((Number(s.Total) || 0) === 0) return []
  return [
    { name: '已解决', value: Number(s.Solved) || 0 },
    { name: '未解决', value: Number(s.Unsolved) || 0 },
  ]
})

/**
 * 实拍图墙：只收**已过审**的图（`Status === 'approved'`）。
 * ⚠️ `pending` 的图后端**不给 `Url`**（见 `CommentService.imagesFor`），`rejected` 也不给 ⇒
 * 这里只需再确认一次状态即可 —— 但**别顺手改成「有 Url 就画」**：那会把这条判据从状态退化成
 * 「后端有没有给值」，而「先审后显」正是要钉住状态本身。
 */
const revShots = computed(() => {
  const out = []
  revRows.value.forEach((r) => (r.Images || []).forEach((im) => {
    if (im.Status === 'approved' && im.Url && out.length < 6) out.push(im.Url)
  }))
  return out
})
const revHasMore = computed(() => revRows.value.length < revTotal.value)

/** 星级串（`Rating` 已在后端夹进 1..5；这里再夹一次只为防手改数据）。 */
const revStars = (n) => '★'.repeat(Math.max(0, Math.min(5, Number(n) || 0)))

/** 5 个端点里**只有 `list` 是公开面**（未登录能读）—— 四个写动作一律先过这道「先去登录」。 */
const revNeedLogin = () => {
  alert('请先登录')
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

/**
 * 拉一页**当前子 tab** 的顶层行（每条自带 `Replies` / `Images` / `MyLike` / `Mine`
 * —— 一次拿全，不另发往返）。
 *
 * ⚠️ `kind` **必须显式传**：不传 = `review` 与 `question` 混着回（后端 `CommentDao.topLevelWhere`
 * 那条筛选是可选的，`api/comment.js` 的注释也写明了）。混着回的后果是「累计评价」里冒出提问、
 * 而 `stats` 又只按**评价**统计（`selectReviewStats` 认 `Kind='review'`）—— 列表与统计卡对不上。
 */
const loadRev = async (append) => {
  if (!productId) { revError.value = '缺少商品 ID，无法加载评价'; return }
  revLoading.value = true
  revError.value = ''
  const kind = revKind.value
  try {
    const d = await commentList({
      targetType: 'goods',
      targetId: productId,
      kind,                      // ⚠️ 见上：两个子 tab 各是各的往返，不是前端过滤
      sort: revSort.value,
      limit: REV_PAGE,
      offset: append ? revRows.value.length : 0,
    })
    // ⚠️ 回来时先确认「还是当前那个 tab」—— 两次 `list` 竞态时慢的那次会覆盖快的
    //    （用户点了「问答」，上一次「评论」的响应后到，列表就串了）。
    if (kind !== revKind.value) return
    if (!d || !d.success) { revError.value = (d && d.msg) || '加载失败'; return }
    revRows.value = append ? revRows.value.concat(d.list || []) : (d.list || [])
    revStats.value = d.stats || null
    revTotal.value = Number(d.total) || 0
  } catch (e) {
    handleApiError(e, '获取内容失败：')
    revError.value = '加载失败'
  } finally {
    if (kind === revKind.value) revLoading.value = false
  }
}

const setRevSort = (s) => { if (revSort.value !== s) { revSort.value = s; loadRev(false) } }
const moreReviews = () => { if (!revLoading.value) loadRev(true) }

/**
 * 切子 tab（评论 ⇄ 问答）。
 *
 * ⚠️ 三件事必须**一起**做，漏一件就是一个可见的错位：
 * <ol>
 *   <li>清 `revRows` —— 否则新 tab 的第一帧会显示**上一个 tab 的行**（`loadRev` 是异步的）；
 *       连 `revTotal` / `revStats` 一起清，不然「共 N 条评价」在问答 tab 上闪一下；</li>
 *   <li>关掉两个发表框 —— 它们各自绑着 `kind`（评价 vs 提问），跨 tab 留着会发错类型；</li>
 *   <li>清 `revError` —— 上一个 tab 的失败提示留在新 tab 上会让人以为新 tab 也坏了；</li>
 *   <li>清 `revExpanded` —— 展开态是按行 `Id` 记的，而两 tab 的行不是同一批（`Id` 空间不同），
 *       留着就是「切回来发现某行莫名是展开的」。</li>
 * </ol>
 */
const setRevKind = (k) => {
  if (revKind.value === k) return
  revKind.value = k
  revRows.value = []
  revTotal.value = 0
  revStats.value = null
  revError.value = ''
  revCompose.value = false
  askCompose.value = false
  repDraft.value = null
  revExpanded.value = {}
  loadRev(false)
}

// ── 发表框（顶层评价）─────────────────────────────────────────────────────────
const revCompose = ref(false)
const revOrders = ref([])        // 「可评价的商品」= 后端 `myOrders`（已确认收货 + 未评价过）
const revOrderMsg = ref('')
const revGoodsId = ref('')
const revDraft = ref({ content: '', rating: 5, anonymous: false })
const revFiles = ref([])
const revSubmitting = ref(false)
const revMsg = ref('')

const revOtherGoods = computed(() => !!revGoodsId.value && String(revGoodsId.value) !== String(productId))
/**
 * 这一页「能不能写评价」= 后端 `myOrders` 回没回东西（方案 §4.1「未购买 → 评论按钮 disabled +
 * tooltip『购买后可评价』」）。⚠️ **它只是提前拦一下**（权威在后端 `checkReviewEligible`）；
 * 「一件都没回」的三种来源——真没买过 / 全是未收货 / 全评过了——在这一格上**同形**，
 * 那句话（`revOrderMsg`）留给发表框去说清具体是哪种。⚠️ 未加载完时 `revOrders` 是空数组
 * ⇒ 按钮**短暂置灰**，加载回来即放开（第四个 `onMounted` 里预拉，见那一处）。
 */
const revCanReview = computed(() => revOrders.value.length > 0)

// ── 举报 / 提删（块 6 整改 commit 4，方案 §4.4；提问的「商家提删」见 §4.2）──────────
/**
 * 本页登录者是不是**商家**。只影响一处措辞：在**提问**行上，商家看到的按钮叫「提删」
 * 而不是「举报」（方案 §4.2「商家可提删 → 管理端裁判」）。
 *
 * ⚠️ 二者走的是**同一条**举报通道（`/report/add`，对象 = 这条提问）—— 不另开端点、不另开表：
 * 「提删」在数据上就是一条针对该提问的举报单，由管理端裁决。
 */
const revIsMerchant = computed(() => getRole() === '2')

// ⚠️ 这一组的变量一律带 `rpt` 前缀（report），**不是** `rep` —— 本文件下方「回复」那一节
//    （`openReply` / `submitReply`）已经占了 `repDraft` / `repSubmitting` / `repMsg` 三个名字。
//    `<script setup>` 是**同一个平铺作用域**，两边叫同名 = 重复声明，整个 SFC 编译不过。
//
// ⚠️ **2026-10-13：弹窗本体已抽成 `components/ReportDialog.vue`**（店铺主页也要用同一段，见模板
//    那条按语）。留在这里的只剩「**开着没**」「**举报谁**」这两个状态：理由 / 说明 / 提交 / 回执
//    都搬进了组件 ⇒ 本页不再需要 `REP_REASONS` / `rptReason` / `rptDesc` / `rptSubmitting` /
//    `rptMsg` / `submitReport` / `rptAccountCheck`，也不再需要 `api/report` 的 `add`。
//    理由与说明的重置随组件走（组件在 `open` false→true 时重置）。
const rptOpen = ref(false)
const rptTarget = ref(null)     // `{ targetType, targetId, label }` —— 原样转交组件，本页不解读

/** 打开举报弹窗。⚠️ 重置由组件负责 —— 上一单填的「其他」正文不该带到下一单。 */
const openReport = (targetType, targetId, label) => {
  rptTarget.value = { targetType, targetId, label }
  rptOpen.value = true
}
const closeReport = () => { rptOpen.value = false }

// ── 回复区折叠（方案 §4.1 展示元素的「└ 共 N 条回复 [展开]」）─────────────────────
/**
 * 每个顶层行只先铺 {@link REV_REPLY_VISIBLE} 条回复，其余的收在「共 N 条回复 · 展开」后面。
 *
 * <p>⚠️ **只在评价（`review`）这一侧生效，问答区不折**：问答的「回答」是主体内容（方案 §4.2
 * 「右问答列表」），折起来等于把答案藏了；而 §4.1 那句「共 N 条回复 [展开]」写在**评论区**的
 * 展示元素里。判据落在 {@link revShownReplies} 一处。
 *
 * <p>⚠️ 被采纳的那条**永远可见**：后端 `CommentService.list` 已把它稳定排序到 `Replies` 最前，
 * 而这里只 `slice(0, N)`（N ≥ 3）⇒ 它必在前 N 条里。别改成「先过滤再取前 N」。
 *
 * <p>展开态是**每楼各自**的（`{ [顶层 Id]: true }`），不是一个全局开关 —— 一楼展开不该把别楼也铺开。
 */
const REV_REPLY_VISIBLE = 3
const revExpanded = ref({})
const revShownReplies = (row) => {
  const all = row.Replies || []
  if (revIsQuestion.value || revExpanded.value[row.Id] || all.length <= REV_REPLY_VISIBLE) return all
  return all.slice(0, REV_REPLY_VISIBLE)
}
const revToggleReplies = (row) => {
  revExpanded.value = { ...revExpanded.value, [row.Id]: !revExpanded.value[row.Id] }
}
const revFileNote = computed(() => (revFiles.value.length
  ? `已选 ${revFiles.value.length} 张，图片先审后显`
  : '图片先审后显'))

// ── 提问框（问答子 tab，块 6 · commit 2）──────────────────────────────────────
/**
 * ⚠️ 提问与评价**不是一个框**，尽管后端是同一个 `add` 端点。四处不同，任一处抄错都是 bug：
 * <ul>
 *   <li><b>不要选商品</b>：方案 §4.2「谁都能提问（不限购买）」⇒ 不查 `myOrders`、不问型号；</li>
 *   <li><b>没有星级</b>：`Rating` 对问答恒 0（表列注释），后端不吃这个入参；</li>
 *   <li><b>没有匿名的选择</b>：方案 §4.2「发问人不允许匿名」⇒ 后端恒实名，勾了也不算
 *       ⇒ 前端**不给这个框**（给一个点了没用的勾选框比不给更糟）；</li>
 *   <li><b>不传 `ProductCode`</b>：它随「选商品」一起，提问不选商品。</li>
 * </ul>
 * 共用的只有「`kind` 由这一份决定」与「正文长度」两条。
 */
const askCompose = ref(false)
const askDraft = ref('')
const askSubmitting = ref(false)
const askMsg = ref('')

const openAskCompose = () => {
  if (!revAccount) { revNeedLogin(); return }
  askMsg.value = ''
  askDraft.value = ''
  askCompose.value = true
}
const closeAskCompose = () => { askCompose.value = false; askMsg.value = '' }

const submitAsk = async () => {
  if (!revAccount) { revNeedLogin(); return }
  const text = (askDraft.value || '').trim()
  if (!text) { askMsg.value = '请先写下你的问题'; return }
  if (text.length > 1000) { askMsg.value = '问题最多 1000 字'; return }
  askSubmitting.value = true
  askMsg.value = ''
  try {
    const d = await commentAdd({
      account: revAccount,
      targetType: 'goods',
      targetId: productId,
      kind: 'question',          // ⚠️ 顶层 `kind` 由前端定（回复的 `kind` 由后端从父行推，见 `CommentService`）
      content: text,
      // ⚠️ 不传 Rating / Anonymous / ProductCode —— 见上面那段「四处不同」
    })
    if (!d || !d.success) { askMsg.value = (d && d.msg) || '提问失败'; return }
    askCompose.value = false
    askDraft.value = ''
    await loadRev(false)
  } catch (e) {
    handleApiError(e, '提问失败：')
  } finally {
    askSubmitting.value = false
  }
}

/**
 * 采纳一条回答（方案 §4.2）。只有**提问者本人**看得到这个按钮（`r.Mine` 且未解决时）。
 *
 * ⚠️ 成功后**整段重拉**而不是本地把那条挪到最前：置顶次序是后端 `Replies` 数组的顺序
 *    （`Accepted` 为真的那条在最前，见 `CommentService.list` 的稳定排序）—— 本地再排一遍
 *    就是第二份次序判据，两处迟早分叉。另：后端会顺手给回答者发一条通知，前端不用管。
 */
const acceptAnswer = async (question, reply) => {
  if (!revAccount) { revNeedLogin(); return }
  if (!window.confirm('采纳后不能更改，确定采纳这条回答？')) return
  try {
    const d = await commentAccept(revAccount, question.Id, reply.Id)
    if (!d || !d.success) { alert((d && d.msg) || '采纳失败'); return }
    await loadRev(false)
  } catch (e) {
    handleApiError(e, '采纳失败：')
  }
}


/**
 * 拉「可评价的商品」并**默认选本页这件**（本页就是它的详情页，绝大多数情况用户就是要评它）。
 *
 * ⚠️ 商家 / 管理员调它必失败（`该账号没有历史订单（只有客户有购买记录）`）—— 那是**业务规则**、
 *    不是故障：这类账号本来就没有「买过」这个前提，发表框据此显示原因而不是一个空下拉。
 */
const loadRevOrders = async () => {
  if (!revAccount) return
  try {
    const d = await commentOrders(revAccount)
    if (d && d.success) {
      revOrders.value = d.list || []
      revOrderMsg.value = ''
      const hit = revOrders.value.find((o) => String(o.GoodsId) === String(productId))
      revGoodsId.value = hit ? String(hit.GoodsId) : (revOrders.value[0] ? String(revOrders.value[0].GoodsId) : '')
    } else {
      revOrders.value = []
      revOrderMsg.value = (d && d.msg) || '暂无可评价的商品'
    }
  } catch (e) {
    revOrders.value = []
    revOrderMsg.value = '暂无可评价的商品（须已确认收货、且未评价过）'
  }
}

const openRevCompose = () => {
  revMsg.value = ''
  revFiles.value = []
  revDraft.value = { content: '', rating: 5, anonymous: false }
  revCompose.value = true
  loadRevOrders()
}
const closeRevCompose = () => { revCompose.value = false; revMsg.value = '' }

/** 选图。⚠️ 超量只**截前 N 张**并如实说清（不静默丢），`e.target.value` 清空以允许重选同一个文件。 */
const onRevFiles = (e) => {
  const fs = Array.from(e.target.files || [])
  if (fs.length > REV_MAX_IMAGES) revMsg.value = `最多 ${REV_MAX_IMAGES} 张图（已选 ${fs.length} 张，只取前 ${REV_MAX_IMAGES} 张）`
  else revMsg.value = ''
  revFiles.value = fs.slice(0, REV_MAX_IMAGES)
  e.target.value = ''
}

const submitReview = async () => {
  if (!revAccount) { revNeedLogin(); return }
  const text = (revDraft.value.content || '').trim()
  if (!text) { revMsg.value = '请先写点内容'; return }
  if (text.length > 1000) { revMsg.value = '评论最多 1000 字'; return }
  if (!revGoodsId.value) { revMsg.value = revOrderMsg.value || '暂无可评价的商品'; return }
  revSubmitting.value = true
  revMsg.value = ''
  try {
    const d = await commentAdd({
      account: revAccount,
      targetType: 'goods',
      targetId: revGoodsId.value,
      kind: 'review',
      content: text,
      rating: revDraft.value.rating,
      anonymous: revDraft.value.anonymous,
      // ⚠️ 方案 §4.1「评论时选'对应商品'（从历史订单里选，`ProductCode` 落库）」——
      //    不传这一格后端 `comment.ProductCode` 就落 NULL，行内「型号：」那一行永远空
      //    （展示侧早在 commit 1 就位，断的只是这里）。取的是**当前选中那笔订单**的货号。
      productCode: (revOrders.value.find((o) => String(o.GoodsId) === String(revGoodsId.value)) || {}).ProductCode,
      files: revFiles.value,
    })
    if (!d || !d.success) { revMsg.value = (d && d.msg) || '发表失败'; return }
    // ⚠️ `success:true` 也可能是「评论进了、图没进全」—— 如实报出来，别只报一个 success。
    revMsg.value = d.imageFailed > 0
      ? `已发表，但有 ${d.imageFailed} 张图没传上去`
      : '已发表'
    revFiles.value = []
    revDraft.value = { content: '', rating: 5, anonymous: false }
    revCompose.value = false
    await loadRev(false)
    // ⚠️ 在「最热」排序下，一条刚发的 0 赞评价多半不在第一页 ⇒ 得说清楚，别让用户以为没发出去。
    if (!revRows.value.some((r) => r.Id === d.Id)) {
      revMsg.value = '已发表（当前排序下不在第一页，切到「最新」可见）'
    }
    loadRevOrders()          // 刚评过的那件从「可评价」里退出
  } catch (e) {
    handleApiError(e, '发表评价失败：')
  } finally {
    revSubmitting.value = false
  }
}

// ── 回复 ────────────────────────────────────────────────────────────────────
// ⚠️ 两层扁平：`parentId` 恒指**顶层**行，@ 谁走 `replyToId`（后端判它与父行同楼，不同 ⇒ 拒）。
const repDraft = ref(null)      // { parentId, replyToId, content, anonymous }
const repSubmitting = ref(false)
const repMsg = ref('')

const openReply = (top, rep) => {
  if (!revAccount) { revNeedLogin(); return }
  repMsg.value = ''
  repDraft.value = { parentId: top.Id, replyToId: rep ? rep.Id : null, content: '', anonymous: false }
}

const submitReply = async () => {
  const d0 = repDraft.value
  if (!d0) return
  const text = (d0.content || '').trim()
  if (!text) { repMsg.value = '请先写点内容'; return }
  repSubmitting.value = true
  repMsg.value = ''
  try {
    const d = await commentAdd({
      account: revAccount,
      parentId: d0.parentId,
      replyToId: d0.replyToId || undefined,   // 空值不发（`api/comment.js` 的 `q()` 会跳过）
      content: text,
      anonymous: d0.anonymous,
      // ⚠️ 不传 TargetType / TargetId / Kind：回复的这三个**由后端从父行推**
      // ⚠️ `anonymous` 在问答子 tab 下**恒为 `false`**（提问框不给勾选框，`repDraft` 也这么初始化）
      //    —— 别以为这是漏传：后端 `resolveAnonymous` 对回答形态**强制匿名**（商家回答除外），
      //    传什么都不影响入库值。这里留着它是为了评价那一路（匿名可选）走同一个函数。
    })
    if (!d || !d.success) { repMsg.value = (d && d.msg) || '回复失败'; return }
    repDraft.value = null
    await loadRev(false)
  } catch (e) {
    handleApiError(e, '回复失败：')
  } finally {
    repSubmitting.value = false
  }
}

// ── 赞踩 / 删除 ──────────────────────────────────────────────────────────────
/**
 * 赞 / 踩。**再点同一种 = 取消**（后端据 `comment_like` 现有行翻转，见 `CommentService.like`）。
 *
 * ⚠️ 三个计数**都是后端回读的当前真值**（不是「原值 ± 1」）⇒ 直接覆盖本地，别自己再加一次；
 *    这也顺手把「两个标签页各点一次」的错位修回来。
 */
const likeRev = async (row, type) => {
  if (!revAccount) { revNeedLogin(); return }
  try {
    const d = await commentLike(revAccount, row.Id, type)
    if (!d || !d.success) return
    row.LikeCount = Number(d.LikeCount) || 0
    row.DislikeCount = Number(d.DislikeCount) || 0
    row.MyLike = d.MyLike || null
  } catch (e) {
    // ⚠️ **刻意不弹窗**：赞踩是个轻动作，一次失败弹一个 alert 比失败本身更烦（与另几处的取舍相反，
    //    因为那几处是**写业务数据**，失败必须让用户知道）。控制台留痕，用户再点一次即可。
    console.error('点赞失败：', e)
  }
}

/** 删一条。⚠️ 删**顶层**连带它下面的回复（后端 `softDeleteRepliesUnder`）⇒ 确认文案要说出条数。 */
const removeRev = async (row) => {
  const n = (row.Replies || []).length
  const what = row.ParentId
    ? '这条回复'
    : (n > 0 ? `这条评价，连同它下面的 ${n} 条回复` : '这条评价')
  if (!window.confirm(`确定删除${what}？`)) return
  try {
    const d = await commentRemove(revAccount, row.Id)
    if (!d || !d.success) { alert((d && d.msg) || '删除失败'); return }
    await loadRev(false)
  } catch (e) {
    handleApiError(e, '删除失败：')
  }
}

// ⚠️ 本文件**第四个** `onMounted`（Vue 允许注册多次）；不并进上面三个，理由同 `:992` 那条。
//    评价区**未登录也照常加载**（商品详情页是浏览面，`/comment/list` 已进 `AUTH_WHITELIST`）——
//    故这里**没有** `if (!isLoggedIn()) return`，与上面 `loadSeckill` 那一条**刻意不同**。
onMounted(() => { loadRev(false); loadRevOrders() })   // ⚠️ 同批预拉「可评价商品」—— 否则「未购买 ⇒ 写评价 disabled + tooltip『购买后可评价』」（方案 §4.1）在用户点开之前判不出来（`revCanReview` 恒假）。两个请求各自 fail-soft，互不阻塞。
</script>

<style scoped>
/* ── 根 ───────────────────────────────────────────────────────────────────────
   ⚠️ 原 `width:100vw; height:100vh` 退役：`100vw` **含竖向滚动条宽**，而本页
      `meta.public: true`、**不进壳**、页面自己滚 ⇒ 只要出现竖向滚动条就必然横向溢出
      一条滚动条宽。只改本页这一份 —— 壳上那对（`.app-shell` / `.home-container`，
      commit 0 已登记）D 笔不动。`height:100vh` → `min-height`：内容比视口高时
      `100vh` 会截断。 */
.goods-detail-page {
  width: 100%;
  min-height: 100vh;
  background: var(--bg);
  box-sizing: border-box;
  font-family: var(--font);
  /* ⚠️ **`--d2` 静默失效的第三次应验**：稿的 `--d-title` / `--d-price` 只定义在**稿自己的**
     `:root`（`ProductPage.html:225-226`），而 `tokens.css` 里只有 `--fs-*`。
     不在这里补定义，`var()` 会在**计算值阶段**判无效 ⇒ **整条 `font-size` 声明被丢弃** ⇒
     标题静默回落 `--fs-sm`(15px)。eslint / build / 模板扫**都报不出来**，只有**算出值**看得见。
     落法同 `UserInfoPage.vue:393` / `GoodsAdd.vue:617` 两处先例。 */
  --d-title: var(--fs-lg);
  --d-price: var(--fs-2xl);
}
/* 控件一律继承页面字体 —— `tokens.css` **刻意不设** `body` 的 `font-family`，
   而浏览器 UA 样式表会给 `button` / `input` 打上自己的 `font` ⇒ 不写这两行，
   全页按钮会掉出 `--font`。落法同 `GoodsUp.vue:637` / `GoodsAdd.vue:612`。 */
button, input, select { font: inherit; color: inherit; }
button { background: none; border: 0; cursor: pointer; }

/* ── 自带顶栏 ─────────────────────────────────────────────────────────────── */
.nav-bar { display: flex; align-items: center; gap: var(--s4); height: 60px; padding: 0 var(--s7);
           background: var(--surface); border-bottom: 1px solid var(--line); }
.nav-back { font-size: var(--fs-md); color: var(--ink-2); padding: 0 var(--s2); white-space: nowrap; }
.brand { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-semi);
         color: var(--brand-ink); white-space: nowrap;
         /* 全仓没有全局 `a` 规则 ⇒ `<router-link>` 渲染出的 `<a>` 默认带下划线，必须自报 */
         text-decoration: none; }
/* ⚠️ 搜索框**保留**（理由见 `<template>` 头注三条）；形制按稿的顶栏重画 */
.search-wrapper { flex: 1 1 auto; max-width: 420px; margin: 0 var(--s5);
                  display: flex; align-items: center; gap: var(--s2); }
.search-input { flex: 1 1 auto; height: 36px; padding: 0 var(--s4);
                border: 1px solid var(--line-2); border-radius: var(--r1);
                background: var(--surface-2); font-size: var(--fs-sm); }
.search-input:focus { outline: none; border-color: var(--brand); background: var(--surface); }
.search-btn { flex: 0 0 auto; height: 36px; padding: 0 var(--s4); border: 1px solid var(--line-2);
              border-radius: var(--r1); color: var(--ink-2); font-size: var(--fs-sm); white-space: nowrap; }
.search-btn:hover { border-color: var(--ink-3); color: var(--ink); }
.nav-right { margin-left: auto; display: flex; align-items: center; gap: var(--s5);
             color: var(--nav-tx); font-weight: var(--fw-med); cursor: pointer; white-space: nowrap; }

.wrap { max-width: 1180px; margin: 0 auto; padding: var(--s5) var(--s5) var(--s8); }

/* 面包屑：分隔符**与文字同色**（同为 `--ink-3`）—— README 里记的对比度事故，
   原先用 `--line-2` 时只有 1.47:1，正是 `check-contrast.mjs` 要抓的「根本看不见」 */
.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--ink-3); }
.crumb a { color: inherit; text-decoration: none; }
.crumb a:hover { color: var(--brand-ink); }
.crumb .sp { font-style: normal; padding: 0 var(--s2); }

/* ── 两栏：图集 56 / 信息 44 ─────────────────────────────────────────────── */
.pd { margin-top: var(--s5); display: grid; grid-template-columns: minmax(0, 56fr) minmax(0, 44fr);
      gap: var(--s8); align-items: start; }

/* 图集 —— `.stage` 是**全页唯一**一处 `overflow:hidden`，用于放大镜裁切；里面没有任何角标 */
.stage { position: relative; aspect-ratio: 1/1; border: 1px solid var(--line); border-radius: var(--r2);
         background: var(--img); overflow: hidden; }
.zoom { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
        color: var(--ink-3); font-size: var(--fs-xs); letter-spacing: .08em;
        transition: transform .25s ease; }
.stage:hover .zoom { transform: scale(1.6); }
/* ⚠️ 放大镜提示**常显**（不是 `:hover` 才出现）：截图是静态的，藏在 hover 里等于这份稿
   没有放大镜这一项。悬停时只加深一档颜色，表示可交互 */
.lens { position: absolute; right: var(--s4); bottom: var(--s4); display: flex; align-items: center; gap: var(--s1);
        height: 30px; padding: 0 var(--s3); border-radius: var(--r1); border: 1px solid var(--line-2);
        background: var(--surface); color: var(--ink-3); font-size: var(--fs-xs); transition: color .2s; }
.stage:hover .lens { color: var(--ink); }
.arw { position: absolute; top: 50%; transform: translateY(-50%); width: 38px; height: 38px; border-radius: 50%;
       border: 1px solid var(--line-2); background: var(--surface); color: var(--ink-2); line-height: 1;
       display: flex; align-items: center; justify-content: center; }
.arw:hover { border-color: var(--ink-3); color: var(--ink); }
/* 到头的方向按既有实现压暗（旧 `.arrow-btn:disabled` 的行为逐字保留） */
.arw:disabled { opacity: .3; cursor: not-allowed; }
.arw.l { left: var(--s4); }
.arw.r { right: var(--s4); }
/* 大图：既有真图集，按 `currentIndex` 淡入淡出（机制逐字未动，只换了容器） */
.big-img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain;
           opacity: 0; transition: opacity .4s; }
.big-img.active { opacity: 1; }
.thumbs { margin-top: var(--s4); display: flex; gap: var(--s3); }
.thb { width: 72px; aspect-ratio: 1/1; border: 1px solid var(--line); border-radius: var(--r1);
       background: var(--img); overflow: hidden; cursor: pointer; }
.thb.on { border-color: var(--ink); }
.thb img { width: 100%; height: 100%; object-fit: cover; }

/* ── 右信息区：本页的**一个釉面主块** ─────────────────────────────────────── */
.info { background: var(--glaze-mid); border-radius: var(--r2);
        padding: var(--s6) var(--s5); margin-left: calc(var(--s5) * -1); }
.pname { font-family: var(--font-display); font-size: var(--d-title); font-weight: var(--fw-med);
         line-height: 1.35; letter-spacing: -.01em; }

/* 价格：一主色（`--brand` 给动作）+ 一锐色（`--accent` **只给价格和促销**，`tokens.css:123`） */
.priceline { margin-top: var(--s5); display: flex; align-items: baseline; gap: var(--s3); flex-wrap: wrap; }
.price { display: flex; align-items: baseline; color: var(--accent);
         font-family: var(--font-display); font-variant-numeric: tabular-nums; }
.price .sym { font-size: var(--fs-md); }
.price .int { font-size: var(--d-price); font-weight: var(--fw-med); line-height: 1; }
.was { font-size: var(--fs-sm); color: var(--ink-3); text-decoration: line-through;
       font-variant-numeric: tabular-nums; }
.off { padding: 1px var(--s2); border-radius: var(--r1); background: var(--accent-soft);
       color: var(--accent); font-size: var(--fs-xs); font-weight: var(--fw-med);
       font-variant-numeric: tabular-nums; }

/* 促销行：标签 + 券框。**不铺底色条**（tokens.css 禁侧边强调条 / 禁阴影铺满）。
   ⚠️ v5 删掉了外圈 `border` + `border-radius`：`--line`(#dde2e5) 对釉面只差蓝通道 7/255
   ⇒ 铺上去整根消失。 */
.promo { margin-top: var(--s5); display: flex; flex-direction: column; gap: var(--s3); padding: var(--s4); }
.promo .r { display: grid; grid-template-columns: 36px minmax(0, 1fr); align-items: center;
            gap: var(--s4); font-size: var(--fs-sm); }
.promo .tag { font-size: var(--fs-xs); color: var(--ink-3); }
.promo .cut { color: var(--ink-2); }
/* 空态文案（「暂无满减活动」/「登录后可领券」）—— C3 块 5 · commit 1 新增：它占的是**内容位**而不是
   标签位，故与 `.promo .cut` 的 `--ink-2` 分开一档（降到 `--ink-3`，与 `.tag` 同色）。 */
.promo .cut-off { color: var(--ink-3); }
/* —— 领券行：**商家券**横向铺开（C3 块 5 · A 组 · A4）——
   用户口径「两张半 + 第三张截断 + 渐隐 + `》`」⚠️ **2026-10-12 手验后按实测收成「一张半」**（券区只
   315.9px，两张半 ⇒ 132.1px/张装不下内容；见 `CP_VISIBLE` 那条）。与主页 `.tk-strip` **同一套路**（不滚、
   裁切、渐隐、`》`），但**不共用那份 CSS**（主页那条钉死在 `.tickets` 的 5 张版式里）。⚠️ 三个数是一组：`CP_VISIBLE`(1) / 基宽那个 `+0.3` / 渐隐带 `78%` —— 改一处要成对改。 */
.coupons { display: flex; gap: var(--s3); flex-wrap: nowrap; align-items: center; overflow: hidden; }
/* 展开态（点过 `》`）：放开换行 ⇒ 全部铺开（每行 2 张），渐隐同时撤掉。 */
.coupons-on { flex-wrap: wrap; }
/* 基宽 = `(100% - 1 个 gap) / (N + 0.3)`（N = `CP_VISIBLE` = 1）。那个 **`+0.3` 不是笔误**：
   按整 1 张算 ⇒ 第 2 张**整个落在裁切线之外、一点都露不出来**，右侧渐隐就没东西可淡（「还有更多」当场失效）；
   按 `+0.5` ⇒ 第 2 张露出近一半，**看着像一张完整的窄卡**、也不像「被裁」。`+0.3` = 露出约三成张。
   ⚠️ `min-width: 0` **必须写**（flex item 自动最小尺寸 = min-content，实测 197px；不写就被顶成 275.1/345.9/345.9 那种内容宽）—— 只作用**领券行**：`.coupon` 还给弹窗 `p-coupons` 用。 */
.coupons > .coupon { flex: 0 0 calc((100% - var(--s3)) / 1.3); min-width: 0; }
/* 越位渐隐。⚠️ 这里是**百分比**（78% 起）而**不是**主页那种固定 `88px`：商品页这一栏没有固定宽度
   （`.promo .r` 是 `1fr`，随视口变），固定 px 在窄屏会盖过头、宽屏又盖不住那一角。百分比自带缩放：
   第 2 张的露头恒在容器约 78% 处起（`((100%-g)/1.3 + g) / 100%`，g=12px ≈ 78%）⇒ 78% 起淡正好。
   `-webkit-mask-image` 是给旧 WebKit 的，两条都要写。 */
.coupons-cut {
  mask-image: linear-gradient(to right, #000 78%, transparent 100%);
  -webkit-mask-image: linear-gradient(to right, #000 78%, transparent 100%);
}
/* 「领券」行比「促销」行多一列（右侧 `》`）。⚠️ **只给这一行加第三列** —— 改公共的 `.promo .r` 会让
   上面「促销」行白留一格 `--s4` 的 gutter（那个 grid 是共用的，见上面那条注释）。 */
.promo .r-coupons { grid-template-columns: 36px minmax(0, 1fr) auto; }
/* 「》」查看更多 —— 「领券」行右侧那颗（`grid` 第 3 列）。样式对齐主页 `.tk-more`（同一手势、同一外观）。 */
.cp-more { flex: 0 0 auto; align-self: center; font: inherit; font-size: var(--fs-xs); line-height: 1;
           color: var(--brand-ink); background: var(--surface-2); border: 1px solid var(--line-2);
           border-radius: var(--r1); padding: 2px var(--s2); white-space: nowrap; cursor: pointer; }
.cp-more:hover { border-color: var(--brand-ink); }
/* 展开态：底色提亮一档，与收起态区分（文案由模板在 `》` / `《` 之间切）。 */
.cp-more-on { background: var(--brand-soft); }
.coupon { flex: 0 0 auto; display: flex; align-items: center; gap: var(--s3);
          padding: var(--s2) var(--s3); border: 1px dashed var(--accent); border-radius: var(--r1); }
.coupon .amt { font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-med);
               color: var(--accent); line-height: 1; font-variant-numeric: tabular-nums; white-space: nowrap; }
.coupon .amt i { font-style: normal; font-size: var(--fs-xs); margin-right: 1px; }
/* 券名（A7 后半，与主页 `.tk-name` 同口径）：卡里唯一一行一级信息。
   ⚠️ `flex: 1 1 auto` + `min-width: 0` 才**截得断** —— 卡被钉死在 `(100% - g)/1.3` 的窄框里，
      券名是用户填的、不限长，不截断就会把「领取」顶出可视区（同主页那条的 `max-width: 100%`）。 */
.coupon .nm { flex: 1 1 auto; min-width: 0; font-size: var(--fs-xs); font-weight: var(--fw-semi);
              color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.coupon .cond { font-size: var(--fs-xs); color: var(--ink-3); white-space: nowrap; }
.coupon .get { flex: 0 0 auto; height: 26px; padding: 0 var(--s3); border: 1px solid var(--line-2);
               border-radius: var(--r1); font-size: var(--fs-xs); color: var(--ink-2); white-space: nowrap; }
.coupon .get:disabled { color: var(--ink-3); cursor: not-allowed; }

/* 型号：发丝线描边，选中只换描边色 + 字色，**不给底色** */
.pick { margin-top: var(--s6); }
.pick .lbl { font-size: var(--fs-xs); color: var(--ink-3); }
.pick .row { margin-top: var(--s3); display: flex; gap: var(--s3); flex-wrap: wrap; }
.md { height: 36px; padding: 0 var(--s5); border: 1px solid var(--line-2); border-radius: var(--r1);
      color: var(--ink-2); font-size: var(--fs-sm); }
.md:hover { border-color: var(--ink-3); }
.md.on { border-color: var(--brand); color: var(--brand-ink); font-weight: var(--fw-med); }
/* ── 2026-10-12〈型号按钮按类别上色〉（用户五处挂账裁定 ④-c）──────────────────────────
   三态：普通 = 白底 → 选中变绿；国补 = 天青底 → 选中外嵌绿框；秒杀 = 红底 → 选中外嵌绿框。
   ⚠️ 绿框用 `outline` + `outline-offset`（**不占位、不推挤邻项**），**不是**左侧色条 ——
      后者是 `tokens.css:34-42` 明令禁止的「侧边强调条」。⚠️ 底色**只标类别、不标选中**：
      选中信号在普通件上是底色、在国补/秒杀件上是那道绿框（底色被类别占了，不能再兼任）。 */
.md.sub { background: var(--brand-tint); }
.md.sec { background: var(--cin-100); }
.md.on:not(.sub):not(.sec) { background: var(--brand-tint); }
.md.sub.on, .md.sec.on { outline: 2px solid var(--brand); outline-offset: 2px; }

/* 数量：与购入弹窗里的那件同款步进器（同一模块同一形态）。
   ⚠️ 主区这一件本笔**接不上线**（`script` 段不动）⇒ 整组 `disabled`、压暗、禁点，
   与稿的「活控件」形态**有意不同**（登记）。 */
.qty { margin-top: var(--s6); display: flex; align-items: center; gap: var(--s4); }
.qty .lbl { font-size: var(--fs-xs); color: var(--ink-3); }
.stepper { display: flex; align-items: center; border: 1px solid var(--line-2); border-radius: var(--r1); }
.stepper button { width: 36px; height: 36px; color: var(--ink-2); line-height: 1; }
.stepper button:hover { color: var(--brand-ink); }
.stepper input { width: 56px; height: 36px; border: 0; border-left: 1px solid var(--line);
                 border-right: 1px solid var(--line); text-align: center; background: var(--surface-2);
                 font-variant-numeric: tabular-nums; }
.stepper input:focus { outline: none; background: var(--surface); }
.stepper button:disabled, .stepper input:disabled { color: var(--ink-3); cursor: not-allowed; }
.qty .stock { font-size: var(--fs-xs); color: var(--ink-3); }

/* 动作：两级权重 —— 实心（立即购买）/ 描边（加入购物车）。C 笔 C2 起**两枚都是真入口**（后者开弹窗并标 'cart'）⇒ 描边那枚从 `--ink-3` 提到 `--ink-2`、`:disabled` 规则随其下线退役、补一条 hover。 */
.acts { margin-top: var(--s6); display: flex; gap: var(--s3); }
.acts .b { height: 52px; border-radius: var(--r2); font-size: var(--fs-md); font-weight: var(--fw-med); }
.acts .primary { flex: 1 1 auto; background: var(--brand); color: var(--bg); }
.acts .primary:hover { background: var(--brand-hover); }
.acts .ghost { flex: 0 0 176px; border: 1px solid var(--line-2); color: var(--ink-2); }
.acts .ghost:hover { border-color: var(--ink-3); color: var(--ink); }

/* 服务承诺条：横排四格，等宽等重，靠**间距**分组；每格一个 brand 小圆点
   （同 `ShoppingCart` 的 `.pledge span::before`） */
.promise { margin-top: var(--s6); display: grid; grid-template-columns: repeat(4, 1fr); }
.promise span { padding: var(--s3) 0; text-align: center; font-size: var(--fs-xs); color: var(--ink-2); }
.promise span::before { content: "·"; color: var(--brand); margin-right: var(--s2); font-weight: var(--fw-semi); }

/* 商家卡 */
.shop { margin-top: var(--s6); display: flex; align-items: center; gap: var(--s4); padding: var(--s4); }
.shop .mark { width: 44px; height: 44px; flex: 0 0 auto; border-radius: var(--r2); background: var(--brand-soft);
              color: var(--brand-ink); font-family: var(--font-display); font-size: var(--fs-lg);
              display: flex; align-items: center; justify-content: center; }
.shop .nm { display: block; font-size: var(--fs-sm); font-weight: var(--fw-med); }
.shop .sub { display: block; margin-top: 2px; font-size: var(--fs-xs); color: var(--ink-3); }
.shop .ops { margin-left: auto; display: flex; gap: var(--s2); flex: 0 0 auto; flex-wrap: wrap; justify-content: flex-end; }
.shop .ops button { height: 32px; padding: 0 var(--s3); border: 1px solid var(--line-2);
                    border-radius: var(--r1); font-size: var(--fs-xs); color: var(--ink-2); white-space: nowrap; }
.shop .ops button:not(:disabled):hover { border-color: var(--ink-3); color: var(--ink); }
.shop .ops button:disabled { color: var(--ink-3); cursor: not-allowed; }

/* ── Tab 条 + 四个面板 ──────────────────────────────────────────────────── */
.tabs { margin-top: var(--s8); display: flex; gap: var(--s6); border-bottom: 1px solid var(--line-2); }
.tabs button { padding: var(--s4) 0; font-size: var(--fs-sm); color: var(--ink-3);
               border-bottom: 2px solid transparent; margin-bottom: -1px; }
/* ⚠️ 结构二次调整（2026-10-07）：原 `.tabs button:disabled{cursor:not-allowed}` 随四枚按钮的 `disabled` 一并退役；
   `.on` 现在表示**当前面**（`activeTab`）—— `:disabled` 态在本页 Tab 条已不存在，故不再给死规则。 */
.tabs button.on { color: var(--brand-ink); font-weight: var(--fw-med); border-bottom-color: var(--brand); }
.tabs .cnt { margin-left: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }
.panel { margin-top: var(--s6); }

.para { max-width: 68ch; font-size: var(--fs-sm); line-height: 1.9; color: var(--ink-2); }
.para + .para { margin-top: var(--s4); }
.longimg { margin-top: var(--s5); aspect-ratio: 3/1; border: 1px solid var(--line); border-radius: var(--r2);
           background: var(--img); display: flex; align-items: center; justify-content: center;
           color: var(--ink-3); font-size: var(--fs-xs); letter-spacing: .08em; }

/* 规格参数表 */
.spec { max-width: 640px; font-size: var(--fs-sm); }
.spec .r { display: flex; padding: var(--s3) 0; border-bottom: 1px solid var(--line); }
.spec .k { width: 110px; flex: 0 0 auto; padding-top: 2px; font-size: var(--fs-xs); color: var(--ink-3); }
.spec .v { color: var(--ink-2); }

/* 累计评价 */
.rev { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: var(--s8); align-items: start; }
.score .n { font-family: var(--font-display); font-size: var(--fs-xl); font-weight: var(--fw-med);
            line-height: 1; font-variant-numeric: tabular-nums; }
.score .of { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); }
.stars { color: var(--ink); letter-spacing: 2px; }
.stars .e { color: var(--ink-3); }
.bars { margin-top: var(--s5); display: flex; flex-direction: column; gap: var(--s2); }
.bars .b { display: flex; align-items: center; gap: var(--s3); font-size: var(--fs-xs); color: var(--ink-3); }
.bars .b i { font-style: normal; width: 34px; flex: 0 0 auto; font-variant-numeric: tabular-nums; }
.bars .track { flex: 1 1 auto; height: 6px; border-radius: 3px; background: var(--surface-3); overflow: hidden; }
.bars .fill { display: block; height: 100%; background: var(--ink-2); }
.shots { margin-top: var(--s5); display: flex; gap: var(--s2); }
.shots .s { width: 64px; aspect-ratio: 1/1; border: 1px solid var(--line); border-radius: var(--r1); background: var(--img); }
.rlist { margin-top: var(--s1); }
.rc { padding: var(--s5) 0; border-bottom: 1px solid var(--line); }
.rc .h { display: flex; align-items: center; gap: var(--s3); font-size: var(--fs-sm); }
.rc .who { font-weight: var(--fw-med); }
.rc .when { margin-left: auto; font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.rc .txt { margin-top: var(--s3); max-width: 64ch; font-size: var(--fs-sm); line-height: 1.8; color: var(--ink-2); }
.rc .sku { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); }
.pager { margin-top: var(--s5); display: flex; align-items: center; gap: var(--s2); }
.pager button { min-width: 32px; height: 32px; padding: 0 var(--s2); border: 1px solid var(--line-2);
                border-radius: var(--r1); font-size: var(--fs-xs); color: var(--ink-2); }
.pager button:disabled { color: var(--ink-3); cursor: not-allowed; }
.pager .gap { padding: 0 var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }

/* 售后保障 */
.pol { max-width: 72ch; }
.pol .r { display: flex; gap: var(--s5); padding: var(--s5) 0; border-bottom: 1px solid var(--line); }
.pol .k { width: 112px; flex: 0 0 auto; font-size: var(--fs-sm); font-weight: var(--fw-med); }
.pol .v { font-size: var(--fs-sm); line-height: 1.9; color: var(--ink-2); }

/* ── 推荐区（猜你喜欢）—— 卡与搜索页 / 购物车 / 个人中心同款 ─────────────── */
.reco { margin-top: var(--s8); padding-top: var(--s7); border-top: 1px solid var(--line-2); }
.reco h2 { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med);
           letter-spacing: -.01em; }
.reco .none { margin-top: var(--s5); font-size: var(--fs-sm); color: var(--ink-3); }
/* ⚠️ 卡本体（`.card` / `.ph` / `.nm` / `.pr` 及其 `i` / `b`）**已整段搬进**
   `src/components/ProductCard.vue`（C3 块 5 · B 组续笔，2026-10-12）。`.cards` 是**栅格**、
   不是卡的一部分 ⇒ 留在本页。⚠️ 搬走的那一份里 `.pr` 带着 `font-family: var(--font-display)`
   （楷体数字）—— 统一到主页那一套后**不再用**，价格随主页走同一款数字（登记为偏离）。 */
.cards { margin-top: var(--s5); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s5); }
/* 下拉加载更多（块 7 收尾 · P1）：渐变条 + 加载中转圈；到底后淡出但仍占位，
   避免列表末尾跳动。与 `HomePage.vue` / `GoodsSelect.vue` 的 `.rec-more` / `.list-more` 同形。 */
.reco-more { position: relative; margin-top: var(--s5); padding: var(--s4) 0 var(--s2);
             text-align: center; font-size: var(--fs-xs); color: var(--ink-3); cursor: pointer;
             transition: opacity .24s ease; }
.reco-more::before { content: ''; position: absolute; left: 0; right: 0; top: calc(-1 * var(--s5));
                     height: var(--s5); pointer-events: none;
                     background: linear-gradient(to bottom, transparent, var(--bg)); }
.reco-more--done { opacity: 0; cursor: default; }
.reco-more--loading::after { content: ''; display: inline-block; width: 12px; height: 12px;
                             margin-left: var(--s2); vertical-align: -1px; border-radius: 50%;
                             border: 2px solid var(--line-2); border-top-color: var(--ink-3);
                             animation: reco-spin .8s linear infinite; }
@keyframes reco-spin { to { transform: rotate(360deg); } }

/* ── 付款确认弹窗（稿 `dlg2`）────────────────────────────────────────────
   ⚠️ 居中用子元素 `margin:auto`，**不用** `align-items:center` —— 后者在内容比视口高时
   会把两端一起裁掉且滚不到（GoodsUp 的弹窗踩过）。容器给 `overflow:auto`。
   底色不用 `rgba()`：README 第 2 条「颜色只有 token，一个 `#hex` / `rgb()` 都不许有」。 */
.scrim { position: fixed; inset: 0; z-index: 200; display: flex; padding: var(--s5); overflow: auto;
         background: color-mix(in srgb, var(--ink) 34%, transparent); }
.dlg { margin: auto; width: 100%; max-width: 520px; background: var(--surface);
       border: 1px solid var(--line-2); border-radius: var(--r3); padding: var(--s7); }
.dlg-narrow { max-width: 420px; }
.dlg-h { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med);
         letter-spacing: -.01em; }
.dlg-sub { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); }
.dacts { margin-top: var(--s6); display: flex; gap: var(--s3); }
.dacts .b { height: 46px; border-radius: var(--r2); font-size: var(--fs-md); font-weight: var(--fw-med); }
.dacts .ok { flex: 1 1 auto; background: var(--brand); color: var(--bg); }
.dacts .ok:hover { background: var(--brand-hover); }
.dacts .no { flex: 0 0 120px; border: 1px solid var(--line-2); color: var(--ink-2); }
.dacts .no:hover { border-color: var(--ink-3); }

/* ── 举报弹窗的样式**已随弹窗搬走** ──────────────────────────────────────
   2026-10-13：`.reasons` / `.rdesc` / `.rmsg` 三条连同弹窗本体一起进了
   `components/ReportDialog.vue`（那边叫 `.rreasons` / `.rdesc` / `.rmsg`，`r` 前缀的理由见其文件头）。
   ⚠️ **上面「付款确认弹窗」那套 `.scrim` / `.dlg` / `.dacts` 留在这里不能删** —— 本页的付款确认
   弹窗（模板 `:632` 起）仍在用它。 */

/* ── 窄屏 ──────────────────────────────────────────────────────────────── */
@media (max-width: 1080px) {
  .pd { grid-template-columns: minmax(0, 44fr) minmax(0, 56fr); gap: var(--s7); }
  .thb { width: 60px; }
  .acts .ghost { flex: 1 1 0; }
  .rev { grid-template-columns: 160px minmax(0, 1fr); gap: var(--s7); }
  .cards { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 820px) {
  .pd { grid-template-columns: 1fr; gap: var(--s7); }
  .gal { max-width: 520px; }
  .rev { grid-template-columns: 1fr; gap: var(--s7); }
  .promise { grid-template-columns: repeat(2, 1fr); }
  /* 单列断点下右信息栏**不再左扩**：`.info` 的负 `margin-left` 是双列时为了把釉面长进栅格
     间隙写的；折成单列后它会把面板推到 `.wrap` 的 padding 边、左右各差 24px */
  .info { margin-left: 0; }
}

/* ══ 购入模块（**C 笔 C2 重做**，2026-10-08）—— 底部弹窗 + 图三式商品行 ═══════════════════
   稿的 `dlg1` 面（`ProductPage.html:706-772`）画的是**居中** `.dlg`（`max-width:760px`，那个宽度是
   稿自己算出来的：购物车式商品行五列排得下）—— C2 明文「**底部弹窗（bottom sheet），非居中
   modal**」⇒ 本段是**有意偏离稿**的那一处（登记台账 §八）。⚠️ 宽度**原**取稿的 760px（同一条算法）—— **用户 2026-10-08「补充项」把下半改成「列表 + 右侧结算栏」两栏 ⇒ 加宽到 1000px**（760 只够那五列列表，排不下结算栏；`.popup-content` 那行的现值即 1000px）。
   ⚠️ 旧 `30% / 40% / 30%` 三段式一族随 C2 **退役**：`.p-top` / `.p-middle` / `.p-bottom` /
      `.p-img` / `.p-info` / `.p-codes` / `.p-code-btn` / `.p-num` / `.p-list` / `.p-item` / `.p-del`；
      顶部改由 `.p-sku` 承担、底部改由 `.p-head` + `.p-rows` + `.p-sum` 承担（⚠️ 原写 `.p-foot`，**该块于用户 2026-10-08「补充项」并入 `.p-body` + `.p-sum`**）。`.p-price` 与
      `.p-confirm` **沿用旧类名**（减少引用漂移），旧值「22px 纯红 700 / 实心底」一并重画。
   ⚠️ 同笔修一处**先前缺陷**：旧 `.popup-mask` 是 `z-index:1000`、而付款确认 `.scrim` 只有 `200`
      ⇒ `confirmPurchase` 之后确认框被购入模块**整块盖住**（`d13/vue-payconfirm.png` 实测：只有购入
      模块、没有确认框；shot13 只断言 DOM 存在 ⇒ 报绿）⇒ 下单链路点不到「是/否」。本笔把底部弹窗
      沉到 `150`（< `.scrim` 的 200）⇒ 确认框回到最上层。
   底色不用 `rgba()`（README 第 2 条：颜色只有 token；旧 `.popup-mask` 的 `rgba(0,0,0,.5)` 与
   `.popup-content` 的 `#fff` 就是越界的那两处）—— 与 `.scrim` 同款 `color-mix` + `--surface`。
   响应式覆盖一律沉到这里（样式表末尾 —— 稿 `LoginPage.html:375-390` 的硬规矩）。

   ══ **2026-10-08 用户「六条改动」逐条登记（结构二次调整，不受 D 笔边界约束）** ═════════════════════
   【一-1 满占】`.popup-content` 由「居中一块（`max-width:1000px`）」改成 **左右满占 100%**：本行原值
     `max-width: 1000px` 整条删除；`.popup-mask` 的左右内边距 `var(--s4)` → `0`（否则「满占」被 16px
     的槽扣掉）。⚠️ 用户逐字：「「自行调整结构」= 在左右 100% 满占的前提下，调整间距 / 补元素，尽可能
     保留原来的布局。**不是重做布局**」⇒ **结构一字未动**（顶部信息区 `.p-sku` + 商品列表 `.p-head/.p-rows`
     + 右侧结算栏 `.p-sum` 仍在，`.p-body` 仍是那条 grid），改的全是**尺寸**：
       ① 边距：`.p-sku` / `.p-body` 左右 padding `--s6`(32) → `--s7`(48)（两者必须同值 —— 列头与顶部区左
          沿对齐靠它）；顶部区右 padding 加宽到 `--s8`(64) 给 X 让位。
       ② 列宽：结算栏 `232 → 260`（`--s6` 的列间距）；六轨 `56/76/96/96/52` → `72/96/120/120/68`
          （缩略图 56 → 72 跟着走），列间距 `--s3`(12) 不变。
       ③ 行高 / 留白：`.p-row` 上下 `--s4` → `--s5`，`.p-head` 同步；缩略图 112 → 128；步进器 32 → 36；
          CTA 高 46 → 56。
       ④ 字号：行名 `.p-row .t` `--fs-sm`(15) → `--fs-md`(19)、`删除` 12 → 15（其余不动 —— 单价 15 / 小计
          24 / 合计 32 的层级本来就能撑满宽）。⚠️ 窄屏两档各有一条回退（见文件末尾那两条 `@media`）。
     ⚠️ **两栏 / 叠一栏的分界从 960 抬到 1100**：轨道加宽后，960–1100 这段两栏会挤掉名字列（满占后可用
        宽度 = 视口 − 2×48 = 视口 − 96；1100 宽时列表列 728px、扣六轨 536px 只剩 192px 给名字）⇒ 按上一条
        的老判据（名字列留不住就该叠）抬档。960 那个数**作废**，不是笔误。
   【一-2 滚动条隐藏】`.p-rows` 加 `scrollbar-width: none` + 一条 `::-webkit-scrollbar { display: none }`。
     触发条件（用户逐字「列表高度 > 弹窗可用高度」）**本来就是**这条 grid 给的：`.popup-content` 的
     `max-height` + `.p-body` 的 `minmax(0,1fr)` 那一行 ⇒ 超出只在 `.p-rows` 里滚，列头不跟着滚。
   【一-3 X 关闭】`.p-close` 落在 `.popup-content` 右上角（`position: absolute`，`.popup-content` 补
     `position: relative`）。点它 = 只置 `showPopup = false` —— 与点旁边空白**同一条路**。
   【二 关闭 ≠ 清数据】样式层无涉，逻辑全在 `openPopup` 的 `popupKey` 那一段（`:506` / `:508-515`）。
   【三 确认框分流】同前，见模板 `:402`。 */
.popup-mask { position: fixed; inset: 0; z-index: 150; display: flex; align-items: flex-end;
              justify-content: center; padding: var(--s4) 0 0;   /* ⚠️ 六条【一-1】：左右 `var(--s4)` → `0`（满占的槽） */
              background: color-mix(in srgb, var(--ink) 42%, transparent); }
.popup-content { position: relative; width: 100%; max-height: calc(100vh - var(--s4));
                 /* ⚠️ 六条【一-1】：原来这里还有一条 `max-width: 1000px`（居中一块的根因）—— 已删；
                    另补 `position: relative` 给右上角 `.p-close` 当定位父。 */
                 display: flex; flex-direction: column; background: var(--surface);
                 border-radius: var(--r3) var(--r3) 0 0; overflow: hidden; }
/* ⚠️ 六条【一-3】：右上角 X。点它 / 点旁边空白**同一条路**（只置 `showPopup = false`，不碰数据）。 */
.p-close { position: absolute; top: var(--s4); right: var(--s4); z-index: 1;
           width: 40px; height: 40px; display: flex; align-items: center; justify-content: center;
           border: 1px solid var(--line-2); border-radius: var(--r2); background: var(--surface);
           font-size: var(--fs-md); line-height: 1; color: var(--ink-2); }
.p-close:hover { border-color: var(--ink-3); background: var(--surface-2); color: var(--ink); }

/* ① 顶部：图 + 价 + 促销 + 领券 + 型号 + 数量 —— **整块重排**（旧的是一段 30% 空白 + 40% 中段） */
.p-sku { flex: 0 0 auto; display: grid; grid-template-columns: 232px minmax(0, 1fr); gap: var(--s6);
         padding: var(--s7) var(--s8) var(--s6) var(--s7); border-bottom: 1px solid var(--line-2); }
         /* ⚠️ 六条【一-1】：轨道 112 → 128、间距 `--s5` → `--s6`、padding `32/32/24` → `48/64/32/48`
            （左 48 与 `.p-body` 同值 ⇒ 顶部区与列表左沿对齐；右 64 = 给 `.p-close` 让位）。
            ⚠️ **2026-10-08 用户五条【3】**：轨道 128 → **232** —— 图片区由「一张小图」升级为「大图 + 型号缩略图排」，
            232 = 一排放得下 4 枚 48px 缩略图（4×48 + 3×8 = 216 ≤ 232）。 */
.p-sku .thumb { width: 232px; aspect-ratio: 1/1; border: 1px solid var(--line); border-radius: var(--r2);
                background: var(--img); overflow: hidden; }
.p-sku .thumb img { width: 100%; height: 100%; object-fit: contain; }   /* ⚠️ 五条【3】：`cover` → `contain` —— 128px 缩略图裁一裁无所谓，232px 大图再裁就吃内容了；与详情页 `.big-img`（同为 `contain`）取齐。 */
/* ⚠️ **2026-10-08 用户五条【3】**：图片列 = 大图 + 一排型号缩略图（本仓新增的一层包装）。
   只有这一列是纵向堆的 flex；`.p-buy` 那一列不受影响。 */
.p-media { display: flex; flex-direction: column; gap: var(--s3); min-width: 0; }
.p-thumbs { display: flex; flex-wrap: wrap; gap: var(--s2); }
.pt { width: 48px; height: 48px; padding: 0; border: 1px solid var(--line); border-radius: var(--r1);
      background: var(--img); overflow: hidden; cursor: pointer; }
.pt.on { border-color: var(--ink); }
.pt img { width: 100%; height: 100%; object-fit: cover; }
.p-buy { min-width: 0; display: flex; flex-direction: column; gap: var(--s4); }   /* ⚠️ 六条【一-1】：`--s3` → `--s4` */
/* 价：与主区 `.price` 同口径（`--accent` + display 字族 + tabular-nums），字号降一档（弹窗里不是主视觉） */
.p-price { display: flex; align-items: baseline; gap: 2px; color: var(--accent);
           font-family: var(--font-display); font-variant-numeric: tabular-nums; }
.p-price i { font-style: normal; font-size: var(--fs-sm); }
.p-price b { font-size: var(--fs-xl); font-weight: var(--fw-med); line-height: 1; }
/* 促销 / 领券：标签列固定 36px 与主区 `.promo .r` 同宽；券框本体复用主区 `.coupon` 规则（同名同类，不重复写） */
.p-promo, .p-coupons { display: flex; align-items: center; gap: var(--s3); flex-wrap: wrap;
                       font-size: var(--fs-sm); }
.p-promo .tag, .p-coupons .tag { flex: 0 0 36px; font-size: var(--fs-xs); color: var(--ink-3); }
.p-promo .cut { color: var(--ink-2); }
/* 型号 / 数量：与主区 `.pick` / `.qty` 同形（`.md` 与 `.stepper` 规则直接复用，此处只调尺寸） */
.p-opt, .p-qty { display: flex; align-items: center; gap: var(--s3); flex-wrap: wrap; }
.p-opt .lbl, .p-qty .lbl { flex: 0 0 36px; font-size: var(--fs-xs); color: var(--ink-3); }
.p-opt .md { height: 36px; padding: 0 var(--s5); font-size: var(--fs-sm); }   /* ⚠️ 六条【一-1】：32 → 36 高、`--s4` → `--s5` 横内边距、字号 12 → 15 */
.p-qty .stepper button { width: 36px; height: 36px; }
.p-qty .stepper input { width: 56px; height: 36px; }
/* ② 底部 = 图三布局（**完整购物车列表**）：列头与商品行**共用同一条 grid 规则**（同稿 `dlg1` 的写法 ⇒ 天然对齐），
   列宽取 `ShoppingCart-customer.html:338` 的 `28/64/1fr/88/104/112/48` **去掉勾选列** = 六轨（与 `图3.jpg` 同构；为让出右侧结算栏，三处定宽压窄 88→76 / 104→96 / 112→96，缩略图 64→56）。
   ⚠️ **用户 2026-10-08「补充项」把这一半从「两行摘要」升级为整份购物车列表**：表头补第 5 列「操作」、行内补「规格 / 订单号」与 ±「数量」、右侧补结算栏；
      其中行内数量**不再是只读镜像** —— 本笔补了 `setPopQtyByCode`（per-code setter）⇒ 上一版那句「做成活的 = 新增功能、C3 未开」**被用户本笔的要求作废**；顶部那件步进器**保留**（两处同源同步）。 */
.p-head, .p-row { display: grid; grid-template-columns: 72px minmax(0, 1fr) 96px 120px 120px 68px;
                  gap: var(--s3); align-items: center; }
                  /* ⚠️ 六条【一-1】：`56/76/96/96/52` → `72/96/120/120/68`（缩略图轨 56 → 72 跟着走）。 */

/* ③ 两栏（**用户 2026-10-08「补充项」**）：左 = 列表（列头 + 商品行），右 = 结算栏（`图3.jpg` 右栏那套）。
   ⚠️ `.p-body` 是那条 grid 容器：列头落第 1 行、`.p-rows` 落第 2 行（都钉在第 1 列）、`.p-sum` 跨两行占第 2 列
      ⇒ **只有 `.p-rows` 滚**、列头不跟着滚；`minmax(0,1fr)` 那一行是关键（不加就撑破 sheet 的高度上限）。 */
.p-body { flex: 1 1 auto; min-height: 0; display: grid;
          grid-template-columns: minmax(0, 1fr) 260px; grid-template-rows: auto minmax(0, 1fr);
          column-gap: var(--s4); padding: 0 var(--s7); }
          /* ⚠️ 六条【一-1】：结算栏 232 → 260、列间距 `--s5` → `--s4`、左右 padding `--s6` → `--s7`
             （与 `.p-sku` 左 padding 同值 ⇒ 列头「商品」与顶部区左沿对齐）。 */
.p-head { grid-column: 1; grid-row: 1; padding: var(--s5) 0 var(--s4); border-bottom: 1px solid var(--line-2);
          font-size: var(--fs-sm); color: var(--ink-3); }   /* ⚠️ 六条【一-1】：上下 `--s4`/`--s3` → `--s5`/`--s4`，字号 12 → 15 */
.p-head > span { text-align: right; }
.p-head > span:first-child { grid-column: 1 / 3; text-align: left; }
.p-rows { grid-column: 1; grid-row: 2; overflow-y: auto; scrollbar-width: none; }
.p-rows::-webkit-scrollbar { display: none; }   /* ⚠️ 六条【一-2】：超出即滚、滚动条隐藏（含上面那行 `scrollbar-width`）。触发条件靠 `.popup-content` 的 `max-height` + 本行所在的 `minmax(0,1fr)` 轨 —— 只有这里滚，列头不跟着滚。 */
.p-row { padding: var(--s5) 0; border-bottom: 1px solid var(--line); }   /* ⚠️ 六条【一-1】：`--s4` → `--s5` */
.p-row .thumb { width: 72px; aspect-ratio: 1/1; border: 1px solid var(--line); border-radius: var(--r1);
                background: var(--img); overflow: hidden; }   /* ⚠️ 六条【一-1】：56 → 72（= 第 1 轨宽） */
.p-row .thumb img { width: 100%; height: 100%; object-fit: cover; }
.p-row .nm { min-width: 0; }
.p-row .t { font-size: var(--fs-md); font-weight: var(--fw-med); line-height: 1.5; color: var(--ink); }   /* ⚠️ 六条【一-1】：15 → 19 */
.p-row .sku { margin-top: 2px; font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.p-row .unit { text-align: right; font-size: var(--fs-sm); color: var(--ink-3);
               font-variant-numeric: tabular-nums; }
/* 行内「数量」= **活控件**（− N +，`setPopQtyByCode`）：与顶部 `.p-qty .stepper` 同源同钳位，尺寸再小一档 */
.p-row .num { display: flex; align-items: center; justify-content: center; gap: var(--s2);
              font-size: var(--fs-sm); color: var(--ink-2); font-variant-numeric: tabular-nums; }
.p-row .num .n { min-width: 22px; text-align: center; }   /* ⚠️ 六条【一-1】：16 → 22 */
.p-row .num .pm { width: 28px; height: 28px; border: 1px solid var(--line-2); border-radius: var(--r1);
                  background: var(--surface); font-size: var(--fs-sm); color: var(--ink-2); line-height: 1; }   /* ⚠️ 六条【一-1】：24 → 28 */
.p-row .num .pm:hover { border-color: var(--ink-3); color: var(--ink); }
.p-row .sub { text-align: right; font-family: var(--font-display); font-size: var(--fs-lg);
              font-weight: var(--fw-med); font-variant-numeric: tabular-nums; }
.p-row .del { justify-self: end; font-size: var(--fs-sm); color: var(--ink-3); }   /* ⚠️ 六条【一-1】：12 → 15 */
.p-row .del:hover { color: var(--accent); }

/* ④ 结算栏（`图3.jpg` 右栏）：标题「结算」+ 商品件数 / 商品总额 / 运费 / 合计 + CTA（文案按 `popupEntry` 取）。
   ⚠️「运费 免运费」在 `ShoppingCart.vue` 那边是**不落的**（那页记的口径是「运费无来源」—— 讲的是订单面无运费字段）；
      本页弹窗**本来就写着**「共 N 件 · 免运费」（旧 `.p-foot`）⇒ 按用户本笔明文落，值仍写死「免运费」。登记见台账 §十。 */
.p-sum { grid-column: 2; grid-row: 1 / 3; display: flex; flex-direction: column; gap: var(--s4);
         padding: var(--s5) 0 var(--s6) var(--s6); border-left: 1px solid var(--line-2); }
         /* ⚠️ 六条【一-1】：`--s3` → `--s4` 行距、padding `16/0/24/24` → `24/0/32/32`。 */
.p-sum-h { font-size: var(--fs-sm); color: var(--ink-3); }   /* ⚠️ 六条【一-1】：12 → 15 */
.p-sum-r { display: flex; align-items: baseline; justify-content: space-between; gap: var(--s3);
           font-size: var(--fs-sm); color: var(--ink-2); font-variant-numeric: tabular-nums; }
.p-sum-r .v { color: var(--ink); }
.p-sum-t { margin-top: var(--s2); padding-top: var(--s4); border-top: 1px solid var(--line);
           display: flex; align-items: baseline; justify-content: space-between; gap: var(--s3); }
.p-sum-t .k { font-size: var(--fs-sm); color: var(--ink-2); }
.p-sum-t .v { font-family: var(--font-display); font-size: var(--fs-xl); font-weight: var(--fw-med);
              color: var(--accent); font-variant-numeric: tabular-nums; }
.p-sum-t .v i { font-style: normal; font-size: var(--fs-sm); margin-right: 2px; }
.p-confirm { margin-top: var(--s5); width: 100%; height: 56px; padding: 0 var(--s5); border-radius: var(--r2);
             /* ⚠️ 六条【一-1】：上边距 `--s4` → `--s5`、高 46 → 56。 */
             background: var(--brand); color: var(--bg); font-size: var(--fs-md); font-weight: var(--fw-med);
             white-space: nowrap; }
.p-confirm:hover { background: var(--brand-hover); }

/* 宽屏档（≥1101，与下面那条 1100 正好互补）：**顶部信息区改两列摆** —— 满占后它右半会空出一大块，
   与用户「各处间距按满占后的空间重新分配（不留大块空白）」相冲 ⇒ 左列「价 + 促销」、右列「券 + 型号 +
   数量」。⚠️ **DOM 一个节点没动**：`.p-buy` 用 `display: contents` 把五个子项交给 `.p-sku` 这条 grid 排
   （只改摆放，不改结构 —— 用户「不是重做布局」那条）。回退 = 删掉这一个 `@media`。 */
@media (min-width: 1101px) {
  /* ⚠️ **2026-10-08 用户五条**：本条**保留**（用户【2】明示「不删」）。两处随【3】改：
     ① 第 1 轨 128 → **232**（与大图同值）；
     ② `.p-buy` 由 `display: contents` 改成**跨第 2、3 轨的真容器** —— 大图变高（232 + 8 + 48 = 288）后，
        原「价/促销 ‖ 券/型号/数量」两列里**必有一列留空**（这正是用户报的「大片空白」）；改成
        「容器吃满高度 + 内部 `align-content: space-between`」把余量摊成**行距**，两列外形与原来一致、不再留空。
     ⚠️ **DOM 一个节点没动**（只是 `.p-buy` 自己从「透明」变回「容器」）。 */
  .p-sku { grid-template-columns: 232px minmax(0, 1fr) minmax(0, 1fr); column-gap: var(--s7);
           row-gap: var(--s3); align-items: stretch; }
  .p-media { grid-column: 1; grid-row: 1; }
  .p-buy { grid-column: 2 / 4; grid-row: 1; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
           column-gap: var(--s7); row-gap: var(--s4); align-content: space-between; }
  .p-price, .p-promo, .p-coupons { grid-column: 1; }
  .p-opt, .p-qty { grid-column: 2; }
}
/* 窄屏第一档（≤1100）：**两栏叠成一栏**（结算栏落到列表下面）—— 判据还是名字列：满占后可用宽度 =
   视口 − 2×48 = 视口 − 96，六轨加宽到 536px 后，两栏要 1101px 才留得住 ~190px 的名字列。
   ⚠️ 六条【一-1】：本档原为 **≤960**（那个数按旧的 1000px 上限 + 窄轨道算出来：「sheet 要 936px 才留得住
      180px 名字列」）—— **轨道加宽 + 满占 ⇒ 判据重算，档位抬到 1100**；旧数作废，不是笔误。 */
@media (max-width: 1100px) {
  .p-body { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr) auto; }
  .p-sum { grid-column: 1; grid-row: 3; border-left: 0; border-top: 1px solid var(--line-2);
           padding: var(--s5) 0 0; }
}
/* 窄屏第二档（≤820，与 `.pd` 折单列同一档）：顶部图缩到 88px；列表六轨压成「48 + 名字 + 64 + 88 + 80 + 44」 */
@media (max-width: 820px) {
  .p-sku { grid-template-columns: 88px minmax(0, 1fr); gap: var(--s4); padding: var(--s5); }
  .p-sku .thumb { width: 88px; }
  .pt { width: 32px; height: 32px; }   /* ⚠️ 五条【3】的回退：88px 轨里一排只放得下 2 枚（2×32 + 8 = 72 ≤ 88）。 */
  .p-body { padding: 0 var(--s5); }
  .p-head, .p-row { grid-template-columns: 48px minmax(0, 1fr) 64px 88px 80px 44px; gap: var(--s2); }
  /* ⚠️ 六条【一-1】的**回退**：满占后加的尺寸在窄屏会挤爆窄轨 ⇒ 这一档把它们压回去。
     ⚠️ `.p-row .thumb` 那条同时修一处**先前缺陷**：旧版基座 56px 但本档第 1 轨只有 48px ⇒ 缩略图本来就
        越出轨道 8px（旧探针只断言六格在、没量缩略图，所以一直报绿）。 */
  .p-row .thumb { width: 48px; }
  .p-row .t { font-size: var(--fs-sm); }
  .p-head, .p-sum-h, .p-row .del { font-size: var(--fs-xs); }
  .p-row .num .pm { width: 24px; height: 24px; }
}
/* ── 2026-10-10〈模板表通用化〉：快照文本按**原文换行**渲染 ─────────────────────
   商家在 `GoodsAdd` 的多行 textarea 里写的段落（`Detail` / `AfterSale`）落到库里是带 `\n`
   的纯文本 ⇒ 不写 `pre-wrap` 会被 HTML 压成一行。⚠️ 还要 `break-word`：一段没有空格的
   模板正文会把 `.para` 的 `max-width:68ch` 撑破、横向溢出整页。 */
.para.pre { white-space: pre-wrap; word-break: break-word; }
/* ── 2026-10-12〈国补〉：价格行上的「国补 −¥N」徽标 ────────────────────────────
   ⚠️ 与 `.off`（折扣角标）**同一套色**（`--accent-soft` / `--accent`）—— 两者都是**价格类**标记，
      `tokens.css:34-42` 把 `--accent` 的用法定为「价格 / 促销 / 未读」，这里正是那一档；
      区分靠**文字**（「6.5 折」vs「国补 −¥15」），不靠再造一个颜色（调色板里没有第三个促销色）。
   ⚠️ **不能**与 `.off` 合成一条规则：语义不同（一个是商家填的原价、一个是平台配的补率），
      将来任一侧单独改口径时，合并的规则会逼着对方一起动。 */
.subsidy { padding: 1px var(--s2); border-radius: var(--r1); background: var(--accent-soft);
           color: var(--accent); font-size: var(--fs-xs); font-weight: var(--fw-med);
           font-variant-numeric: tabular-nums; }
/* 「到手价」那半 —— 同一枚徽标内的**第二段**（2026-10-12 补）：比减额那半**更弱**一档
   （`--fw-reg` 正文重 + 左侧一条分隔线），靠**字重与分隔**分主次，不另起一枚徽标
   （两枚同色徽标并排会被读成两个独立信息）。 */
.subsidy .pay { font-style: normal; font-weight: var(--fw-reg); margin-left: var(--s2);
                padding-left: var(--s2); border-left: 1px solid var(--accent); }
/* ── 2026-10-12〈秒杀〉：详情页**秒杀区**（红底套白卡，用户配色令）──────────────────
   ⚠️ 本块**有意**违反 `tokens.css:34-42`「禁 · 嵌套卡片（卡片只有一层）」的一条 —— 用户点名
      要的「套层感」。做到最小：外层只当**框**（8px 内距、不放正文），外层圆角 `--r2` **大于**
      内层 `--r1`（几何上才不别扭），**不**再叠第二套边框与阴影。
   ⚠️ 色取朱砂阶梯**最深**一档 `--cin-700`：外层上只放标记、一律白字（8.31:1），正文全在白卡里。
   ⚠️ 秒杀价用**青瓷绿** `--brand-ink`（2026-10-12 用户令：秒杀价与普通商品价格色**对调**），
      白卡上 7.51:1；「秒杀」二字走朱砂（`--cin-600` 实底 + 白字 5.66:1）。⚠️ 原方案那枚
      价格行徽标 `.sk-tag` 已随用户「乙案」退场（全文只在新块说一次）。
   ⚠️ 秒杀价**不覆盖**上面那条 `.price`（现价仍是 `price` 列那个值）—— 本仓的成交价在结算时由
      `orderAdd` 定，本页只把「这场活动此刻的价」摆出来，不假装站点价已经变了。 */
.sk-zone { margin-top: var(--s4); padding: var(--s2); background: var(--cin-700); border-radius: var(--r2); }
.sk-card { padding: var(--s3) var(--s4); background: var(--surface); border: 1px solid var(--cin-200); border-radius: var(--r1); }
.sk-hd { display: flex; align-items: baseline; gap: var(--s3); }
.sk-mark { padding: 1px var(--s2); border-radius: var(--r1); background: var(--cin-600);
           color: var(--bg); font-size: var(--fs-xs); font-weight: var(--fw-semi); }
.sk-left { font-size: var(--fs-xs); color: var(--cin-700); font-variant-numeric: tabular-nums; }
.sk-pr { margin-top: var(--s2); display: flex; align-items: baseline; gap: var(--s3); }
.sk-now { font-family: var(--font-display); font-size: var(--fs-lg); color: var(--brand-ink);
          font-weight: var(--fw-med); font-variant-numeric: tabular-nums; }
.sk-was { font-size: var(--fs-xs); color: var(--ink-3); }
/* ⚠️ **售完 ⇒ 秒杀价退出强调色**（2026-10-12）：一件抢不到的商品，价格还亮着绿，等于页面在替它
   喊「快买」。「已售完」三个字归 `.sk-left`（不另起置灰层，同 `SeckillPage.vue` 那套的反面取舍）。 */
.sk-card.sold .sk-now { color: var(--ink-3); }
.sk-card.sold .sk-left { color: var(--accent); font-weight: var(--fw-med); }
/* ── 2026-10-12〈结算用券 · commit 5 续笔〉：购入弹窗「结算」栏里的券选择器 ──────────────
   ⚠️ 与 `ShoppingCart.vue` 的 `.paybar .cp` **是同一枚控件**（同一套形制：券小签 + 原生
      `<select>` + 折后额）；那边的作用域是吸底结算栏，这边是弹窗右栏，父级不同 ⇒ 各写一份
      scoped 规则。⚠️ 本仓 `scoped` 下**没有**跨组件复用样式的口子（没有公共控件样式表，
      只有 `tokens.css` 那层变量）⇒ 这份重复是**有意的**，但两处改一处要一起看。
   ⚠️ `select` 高度照 `OrderCenter.vue` 的 `.status-select` 那一档（32px / `--line-2` 边框）；
       `background: var(--surface)` 必须显式写 —— 原生 `<select>` 在暗色主题下默认是白底黑字。
   ⚠️ `.off`（本单不可用券）只**降透明度**，不禁用整行：`title` 里的 `couponHint` 要说得出原因。 */
.p-sum .cp { display: inline-flex; align-items: center; gap: var(--s2); }
.p-sum .cp select {
  max-width: 190px;
  height: 32px;
  padding: 0 var(--s2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  cursor: pointer;
}
.p-sum .cp.off { opacity: .5; }
.p-sum .cp select[disabled] { cursor: not-allowed; }
.p-sum .cp .save { font-size: var(--fs-sm); color: var(--accent); font-variant-numeric: tabular-nums; }

/* ── 2026-10-12〈C3 块 6 · commit 1「评论」〉：评价区的真功能样式 ─────────────────────────
   ⚠️ 上面 `:1287-1312` 那一段 `.rev` / `.score` / `.bars` / `.shots` / `.rlist` / `.rc` / `.pager`
      **原样复用**（它们是稿的形态，真数据进同一套壳）—— 本块只**补**真功能多出来的那些件，
      并就地推翻两处稿的假设：① `.rlist` 现在要限高内滚；② `.shots .s` 由「灰底方块」变成装
      `<img>` 的马口铁。⚠️ 补在 `<style>` **最末**：本文件多处被按行号引，新增行只有落在最大被引行
      之后才不顶漂（同 `<script setup>` 那几块的理由）。 */
.rev .rbar { display: flex; align-items: center; gap: var(--s2); margin-bottom: var(--s4); }
.rev .rbar button { height: 28px; padding: 0 var(--s4); border: 1px solid var(--line-2);
                    border-radius: var(--r1); font-size: var(--fs-xs); color: var(--ink-2); }
.rev .rbar button.on { border-color: var(--ink-3); color: var(--ink); font-weight: var(--fw-med); }
.rev .rbar button.wr { margin-left: auto; border-color: var(--brand-ink); color: var(--brand-ink); }
/* 「未购买 ⇒ 写评价 置灰 + tooltip」（方案 §4.1）。⚠️ 必须显式写：`button.wr` 那条把颜色钉在
   `--brand-ink` 上，不覆盖的话禁用态**看起来跟能点的一模一样**（`.rbar button` 的通用禁用规则
   优先级低于 `button.wr`）。 */
.rev .rbar button.wr:disabled { border-color: var(--line-2); color: var(--ink-3); cursor: not-allowed; }
.rev .rbar .sp { flex: 1 1 auto; }        /* `写评价` 出现时由它占位，按钮不吃 `auto` */
.rev .rbar .hint { margin-left: auto; font-size: var(--fs-xs); color: var(--ink-3); }
/* ⚠️ 列表限高 + 内部滚动（方案 §4.1）。`max-height` 只在更长时才生效 ⇒ 评价少的商品形态不变。
   右内边距给滚动条留位，免得末行文字贴着它。 */
.rev .rlist { max-height: 560px; overflow-y: auto; padding-right: var(--s3); }

/* 实拍图墙 / 评论内图 —— 同一套「小方块」：已过审画图、`pending` 画字、`rejected` 画字（见模板）。
   ⚠️ `.shots .s` 原样式自带 `background: var(--img)`（灰底）⇒ 装 `<img>` 时必须 `overflow: hidden`
   把图裁进圆角，否则直角图会顶破 4px 圆角。 */
.shots .s, .rshots .s { overflow: hidden; }
.shots .s img, .rshots .s img { display: block; width: 100%; height: 100%; object-fit: cover; }
.rshots { margin-top: var(--s3); display: flex; flex-wrap: wrap; gap: var(--s2); }
.rshots .s { width: 72px; height: 72px; border: 1px solid var(--line); border-radius: var(--r1);
             display: flex; align-items: center; justify-content: center; text-align: center;
             font-size: var(--fs-xs); line-height: 1.4; color: var(--ink-3); background: var(--surface-3); }
.rshots .s.bad { color: var(--accent); }

/* 赞踩 / 回复 / 删除 —— 一排极轻的按钮，`on` 表示「我这一票已投」。 */
.ract { margin-top: var(--s3); display: flex; align-items: center; gap: var(--s2); }
.ract button { height: 26px; padding: 0 var(--s3); border: 1px solid transparent; border-radius: var(--r1);
               font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.ract button:hover { color: var(--ink-2); border-color: var(--line-2); }
.ract button.on { color: var(--brand-ink); font-weight: var(--fw-med); }

/* 回复区：缩进一层 + 左细线（两层扁平的**唯一**视觉线索 —— 回复里不再有回复）。 */
.rreps { margin-top: var(--s4); padding-left: var(--s5); border-left: 2px solid var(--line); }
.rep { padding: var(--s3) 0; }
.rep .h { display: flex; align-items: center; gap: var(--s3); font-size: var(--fs-sm); }
.rep .who { font-weight: var(--fw-med); }
.rep .when { margin-left: auto; font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.rep .txt { margin-top: var(--s2); max-width: 60ch; font-size: var(--fs-sm); line-height: 1.8; color: var(--ink-2); }
.rep .tag { padding: 1px var(--s2); border-radius: var(--r1); background: var(--surface-3);
            font-size: var(--fs-xs); color: var(--ink-3); }
/* 「回复 @X：」前缀 —— 比正文浅一档、不加粗，读到它就知道这条是回给谁的。 */
.rep .txt .rt { color: var(--brand-ink); }
/* 「共 N 条回复 · 展开 / 收起」—— 同 `.ract button` 那一档的轻按钮，但自成一行。 */
.rmore { margin-top: var(--s2); height: 26px; padding: 0 var(--s3); border: 1px solid transparent;
         border-radius: var(--r1); font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.rmore:hover { color: var(--ink-2); border-color: var(--line-2); }

/* 发表 / 回复框 */
.wbox { margin: var(--s4) 0; padding: var(--s4); border: 1px solid var(--line-2); border-radius: var(--r2); }
.wbox.rep { margin: var(--s3) 0 0; padding: var(--s3); }
.wrow { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s3); font-size: var(--fs-sm); }
.wrow + .wrow { margin-top: var(--s3); }
.wk { width: 40px; flex: 0 0 auto; font-size: var(--fs-xs); color: var(--ink-3); }
.wk2 { font-size: var(--fs-xs); color: var(--ink-3); }
.wk2.warn { color: var(--accent); }
.wg { max-width: 320px; height: 32px; padding: 0 var(--s2); border: 1px solid var(--line-2);
      border-radius: var(--r1); background: var(--surface); color: var(--ink-2); font-size: var(--fs-sm); }
.an { display: inline-flex; align-items: center; gap: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }
.starpick button { padding: 0 1px; font-size: var(--fs-lg); line-height: 1; color: var(--ink); }
.starpick button.e { color: var(--ink-3); }
.starpick button:disabled { cursor: not-allowed; }
.wt { display: block; width: 100%; box-sizing: border-box; min-height: 88px; margin-top: var(--s3);
      padding: var(--s3); border: 1px solid var(--line-2); border-radius: var(--r1);
      background: var(--surface); color: var(--ink-2); font-family: inherit; font-size: var(--fs-sm);
      line-height: 1.8; resize: vertical; }
.wact { margin-top: var(--s3); display: flex; align-items: center; flex-wrap: wrap; gap: var(--s3); font-size: var(--fs-xs); }
.wact .sp { flex: 1 1 auto; }        /* 把「发表」推到右边 */
.wmsg { color: var(--ink-3); }
.wact button { height: 30px; padding: 0 var(--s5); border: 1px solid var(--line-2);
               border-radius: var(--r1); font-size: var(--fs-sm); color: var(--ink-2); }
.wact button.wr { border-color: var(--brand-ink); color: var(--brand-ink); }
.wact button:disabled { color: var(--ink-3); cursor: not-allowed; }

/* 空态 / 失败态（`.reco .none` 是推荐区那一份，作用域不同 ⇒ 各写一份） */
.rev .none { margin-top: var(--s5); font-size: var(--fs-sm); color: var(--ink-3); }

/* ══════════════════════════════════════════════════════════════════════════════
   C3 块 6（内容+消息块）· commit 2「问答」新增 —— 全部落在 `<style>` **最末**。
   ⚠️ 为什么一律追加在末尾：本文件多处被按「文件:行号」引用（`slot-registry.md` 等），
   新增行只有落在**最大被引行之后**才不让其后的引用集体顶漂（同上面 `.rev .rbar`
   那段的理由，块 6 commit 1 也是这么落的）。
   ══════════════════════════════════════════════════════════════════════════════ */

/* 子 tab（商品评论 / 问答）。⚠️ 与页签 `.tabs` **不是一套、别复用**：`.tabs` 改了会换整个
   面板（简介 / 规格 / 累计评价），这是页签**内部**的第二层筛（改了只换 `list` 的 `Kind` 入参）
   ⇒ 视觉上刻意压低一档（无下划线、字号更小），免得两层看上去同权。 */
.subtabs { display: flex; align-items: center; gap: var(--s2); margin-bottom: var(--s3); }
.subtabs button { height: 26px; padding: 0 var(--s3); border: 1px solid var(--line-2);
                  border-radius: var(--r1); font-size: var(--fs-xs); color: var(--ink-3); }
.subtabs button.on { border-color: var(--ink-3); color: var(--ink); font-weight: var(--fw-med); }

/* 左侧「解决率」（方案 §4.2 的「左圆比例图」）：一圈环 + 洞里的已解决数 + 自带图例。
   ⚠️ 图形本体由 `charts/DonutChart.vue` 画（`echarts` 按需注册在 `charts/echarts.js`），
   这里只负责**留位**：左右各留一点边距，免得扇区标签贴到面板边。（原先那根横比例条
   `.qbar` / 自绘图例 `.qlegend` 已随本次整改退役 —— §4.2 要的是「圆比例图」。） */
.qdonut { margin-top: var(--s4); padding: 0 var(--s2); }
.qtip { margin-top: var(--s4); font-size: var(--fs-xs); line-height: 1.8; color: var(--ink-3); }

/* 结局标签（已解决 / 未解决 / 已采纳）。⚠️ `.rep .tag` 那份已在上面（`2224` 上下）定义，
   这里只补 `.rc` 这一半 + 两处的 `solved` 变体 —— 本仓 `scoped` 下没有跨组件复用样式的
   口子，选择器不同名就各管各的（与 `.rev .none` 那条同理）。 */
.rc .tag { padding: 1px var(--s2); border-radius: var(--r1); background: var(--surface-3);
           font-size: var(--fs-xs); color: var(--ink-3); }
/* 「已解决」/「已采纳」用**实底**而不是描边：它是这一行里唯一的肯定性结论，
   要与同在 `.h` / `.ract` 里的中性标签（商家 / 商家首复 / 未解决）一眼分开。 */
.rc .tag.solved, .rep .tag.solved { background: var(--brand-ink); color: var(--surface); }

/* 采纳按钮。⚠️ 与赞踩同排但**刻意不共色**：赞踩是轻动作（灰、点了可再点），采纳是
   **不可撤销**的一步 ⇒ 用品牌绿 + 加粗，让「点下去就改不了」在按下之前就有颜色提示
   （第二道提示是 `acceptAnswer` 里的 `window.confirm`）。 */
.ract button.acc { border-color: var(--brand-ink); color: var(--brand-ink); font-weight: var(--fw-med); }
/* ⚠️ 单独覆盖 `:hover`：上面 `.ract button:hover` 那条（`2214`）会把边框刷回 `--line-2`，
   正好在**悬停时**把这道「危险动作」的颜色提示抹掉 —— 那是反的。 */
.ract button.acc:hover { border-color: var(--brand-ink); color: var(--brand-ink); }
</style>
