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

          <!-- ①【原价 / 折扣】`data-slot` 按稿挂**整个 `.priceline`**，而**现价是真的**
               （`allItems[].price` ⇒ `currentItem.price`）⇒ 这一块是**一块两档**：
               块级（`<div>` 不是控件）⇒ 只挂 `data-slot` + `title`、**不 `disabled`**，
               真价照常渲染。稿里 `.was` / `.off` 是**块内 mock** ⇒ 逐字保留（同 GoodsAdd §三）。
               ⚠️ 稿的 `.dec`（`.00`）**不落**：本仓价格没有固定小数位，硬拆会凭空空造两位。
               ⚠️ 本条 title 稿里**不带**「待 C3〈商品〉」前缀（它的 C3 归属写在另一个属性
                  `data-future` 上，而 `data-future` 按 `ShoppingCart.vue:43` 先例不落）⇒
                  归属只进台账，**不往 title 里塞 C3**（同 commit 11 #37 的处理）。 -->
          <div
            class="priceline"
            data-slot="预留 · 原价 / 折扣（动现有代码 5 处 · 超档）"
            title="goods 表无原价列（只有 Price 一列）：mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + ProductPage.vue = 5 处，**超档**，因清单点名而画出（见 README 挂账表）"
          >
            <span class="price"><span class="sym">¥</span><span class="int">{{ currentItem.price }}</span></span>
            <span class="was">¥199.00</span>
            <span class="off">6.5 折</span>
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
                :class="{ on: idx === currentIndex }"
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
        <button type="button" :class="{ on: activeTab === 'rev' }" @click="activeTab = 'rev'">累计评价<span class="cnt">—</span></button>
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

      <!-- Tab ③ 累计评价。⚠️ **评论区张力**（稿头注里自己写明）：用户边界「不做评论区」，
           而 v4 清单第 3 条点名要这个 Tab ⇒ 稿的裁法是「**画出来、但整块按占位处理**」：
           块级 `data-slot` + 内部可点件逐件 `disabled`。分页那 5 枚稿没给 title ⇒ 本笔补。 -->
      <div class="panel" v-if="activeTab === 'rev'">
        <div
          class="rev"
          data-slot="预留 · 累计评价（动现有代码 ≥4 处 · 超档）"
          title="无评价端点：endpoints.js + GoodsController.java（或新评价 Controller）+ ProductPage.vue + 订单页「去评价」入口 = ≥4 处，**超档**。⚠️ 本块按用户边界「不做评论区」**只画形态、不实现**，是清单点名与边界打架时的显式例外。"
        >
          <div class="score">
            <div class="n">4.8</div>
            <div class="of">共 12,043 条评价</div>
            <div class="bars">
              <div class="b"><i>5 星</i><span class="track"><span class="fill" style="width:86%"></span></span></div>
              <div class="b"><i>4 星</i><span class="track"><span class="fill" style="width:9%"></span></span></div>
              <div class="b"><i>3 星</i><span class="track"><span class="fill" style="width:3%"></span></span></div>
              <div class="b"><i>2 星</i><span class="track"><span class="fill" style="width:1%"></span></span></div>
              <div class="b"><i>1 星</i><span class="track"><span class="fill" style="width:1%"></span></span></div>
            </div>
            <div class="shots">
              <span class="s"></span><span class="s"></span><span class="s"></span><span class="s"></span>
            </div>
          </div>
          <div>
            <div class="rlist">
              <div class="rc">
                <div class="h"><span class="who">阿***</span>
                  <span class="stars">★★★★★</span>
                  <span class="when">2026-09-28</span></div>
                <p class="txt">降噪比想象中好，通勤地铁上基本听不到报站声，做工也扎实，充电盒盖的阻尼感对得起这个价。</p>
                <div class="sku">型号：降噪款</div>
              </div>
              <div class="rc">
                <div class="h"><span class="who">拾***</span>
                  <span class="stars">★★★★<span class="e">★</span></span>
                  <span class="when">2026-09-21</span></div>
                <p class="txt">续航实测约 28 小时，比标称的 32 小时少一点；不过日常两天一充，够用。</p>
                <div class="sku">型号：标准款</div>
              </div>
              <div class="rc">
                <div class="h"><span class="who">沈***</span>
                  <span class="stars">★★★★★</span>
                  <span class="when">2026-09-15</span></div>
                <p class="txt">第二次买了，第一副掉在出租车上。送的收纳盒很实用，这回直接又配了一副。</p>
                <div class="sku">型号：旗舰款</div>
              </div>
            </div>
            <div class="pager">
              <button type="button" class="on" disabled title="评价分页无端点（≥4 处 · 超档），本笔只画形态">1</button>
              <button type="button" disabled title="同上：评价分页无端点">2</button>
              <button type="button" disabled title="同上：评价分页无端点">3</button>
              <span class="gap">…</span>
              <button type="button" disabled title="同上：评价分页无端点">602</button>
              <button type="button" disabled title="同上：评价分页无端点">下一页</button>
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

      <!-- ── 三、推荐区（猜你喜欢）——**真功能**（`goods.goodsSelectHome` ⇒ `goodsWithUser`）──
           ⚠️ 稿的 4 张卡是 mock（声谷 Lite / 空气传导 / 数据线 / 蓝牙音箱），本区**有真数据**
              ⇒ 落真卡：`goodsWithUser` 取前 4 条（与稿的 4 栏栅格对齐）。
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
        <p v-if="goodsWithUser.length === 0" class="none">暂无推荐</p>
        <div v-else class="cards">
          <ProductCard v-for="goods in goodsWithUser.slice(0, 4)" :key="goods.Id" :goods="goods" />
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
                  class="md" :class="{ on: idx === popupCurrentIndex }" @click="popupCurrentIndex = idx">{{ item.productCode }}</button></div>
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
            <div class="p-sum-r"><span class="k">商品件数</span><span class="v">{{ popupCount }}</span></div><div class="p-sum-r"><span class="k">商品总额</span><span class="v">¥{{ Number(popupTotal).toFixed(2) }}</span></div>
            <div class="p-sum-r"><span class="k">运费</span><span class="v">免运费</span></div><div class="p-sum-t"><span class="k">合计</span><span class="v"><i>¥</i>{{ Number(popupTotal).toFixed(2) }}</span></div>
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
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch, onBeforeUnmount } from 'vue'   // ⚠️ **2026-10-08 用户五条【1】**新增 `watch` / `onBeforeUnmount`（body 锁那一处用）—— **同行追加、不新增行**。
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'; import { orderAdd, cartAdd } from '../api/order'   // ⚠️ C3 块 4：**同行追加**（不新增行 —— 本文件 `:794`/`:803` 被 `slot-registry.md:1994-1995` 按行号引（〈Bug 1〉本笔 +19 行前为 `:775`/`:784`），此处加行会顶漂它们）。两个函数就是原先那两串 `URLSearchParams` + `http.post` + `res.json()` 的收口
import CashierDialog from '../components/CashierDialog.vue'; import ProductCard from '../components/ProductCard.vue'   // ⚠️ 2026-10-10（Bug 1）：收银台与「我的订单」**共用同一个组件** —— 本行**确实**新增了一行；本笔在 `ProductPage.vue` 净 +19 行（`git diff` 实测：模板 `:412` 起 +5、import +1、`handlePay` 段 +13）⇒ 下面两处 `:775`/`:784` 漂到 `:794`/`:803`，随本笔一并重指（见 `slot-registry.md:1994-1995`）
import { getMerchantId, getCustomerId, isLoggedIn } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'; import { coupons, claim } from '../api/marketing'; import { couponName } from '../config/coupon-format'   // ⚠️ 四条 import **同行**（第 2、3 条：2026-10-11 补图标笔 + C3 块 5 · commit 1；第 4 条 `couponName`：块 5 · A 组 · A7 后半）—— 合并到既有行 = 不新增行号漂移（本文件 `:794`/`:803` 被 `slot-registry.md:1994-1995` 按行号引，上面 `:424` 那条注释记过同一件事）。⚠️ **块 5 · commit 1 仍整体 +90 行**（模板 +17 / `<script setup>` 尾 +70 / `<style>` +3）⇒ `slot-registry.md:1994` 引的 `:794` 漂 **`:881`**、`:1995` 引的 `:803` 漂 **`:890`**（两值均为 `grep -n` 实测，见 `_work/b5-repoint.md`）。

const route = useRoute()
const router = useRouter()

// 从路由参数获取商品所属商家ID (卖家)
const { MerchantId, GoodName, GoodType, Id } = route.query
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
const goodsWithUser = ref([])

const start = ref(0)
const showImages = computed(() => allItems.value.slice(start.value, start.value + 3))
const realIdx = (i) => start.value + i

const scroll = (d) => {
  const m = allItems.value.length - 3
  if (m <= 0) return
  start.value = Math.max(0, Math.min(m, start.value + d))
}

// 加载商家信息
const loadGoodsWithUser = async () => {
  try {
    const res = await http.post(endpoints.goods.goodsSelectHome)
    const data = await res.json()
    if (data.success) goodsWithUser.value = data.list
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
          goodImg: d2.goodImg,
          UserName: ''
        })
      }
    }
    const matchGood = goodsWithUser.value.find(
      (item) => item.MerchantId === sellerId && item.GoodName === goodName
    )
    if (matchGood) items.forEach((it) => { it.UserName = matchGood.UserName })
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
      price: i.price,
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
      PayIf: 0, DeliveryIf: d.DeliveryIf, ReceiveIf: d.ReceiveIf,
      Quantity: popupCount.value, Items: JSON.stringify(popupBuyList.value.map((i) => ({ code: i.productCode, name: goodName, price: i.price, qty: i.quantity }))),   // C3 块 4：本单**总件数**（A2 甲新列；一个 OrderId 一单 ⇒ 不带型号维度）＋ ⚠️ **可视化段 commit 0 同行追加 `Items`**（显式商品明细 ⇒ 服务端写 `order_item`；格式是 JSON 数组串，理由见 `OrderService.resolveItems`：商品名未禁 `:`/`；`，自造分隔符会错位）。⚠️ **同行追加、不新增行** —— 本文件 775 / 784 两行在下面，新增行会顶漂它们。⚠️ `name` 取页级 `goodName`：弹窗里全是**同一商品**的型号（`popupAllItems`），故一件一名的前提成立；`price` 是**单价**（`popupTotal` 是 `Σ price × quantity`，:572）
    })

    if (result.success) {
      // ⚠️ **不**在这里 `alert('订单提交成功！')`、也**不**跳页：单已经建好（待付款），
      //    下一步是付款 ⇒ 把收银台摆上来。跳页与关窗都由付完/关掉收银台之后决定（见 `afterCashier`）。⚠️ **2026-10-10（Bug 1 二次修；用户手验：「收银台弹出，但被白色结算弹窗挡在后面」）**：`showPopup.value = false` 管的正是**购入模块弹窗**（`.popup-mask`，`:1109` 那条 `z-index:150`）—— 改前它一直挂着、而收银台 `.cscrim` 只有 `60` ⇒ 收银台**真在它下面**；结算确认弹窗（`showPayConfirm`，`.scrim` `:1036` = `200`）在 `:624` 已关，本行补的是同一条链上的**第二层**（两层都要关，只关一层另一层照样盖住）。层级自身另在 `CashierDialog.vue:103` 提档。
      showPopup.value = false; payFor.value = { OrderId: d.OrderId, Price: d.Price }
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
//    「客户号为空则退回商家号」的降级链 —— 那是给 `orderAdd` 用的（商家也能下单）。券不行：
//    `/marketing/*` 的 `Account` 语义是 **`user.customerid`**（见 `CouponController` 类注释），
//    拿商家的 `merchantid` 去领券会落一条**永远核销不掉**的 grant。
//    ⇒ 券只认客户号；没有就整块不出券（与 `HomePage.vue` 券包同一个判据）。
const couponAccount = getCustomerId() || ''
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

/* 2026-10-10〈模板表通用化〉三段快照文本（`goods.Detail` / `Spec` / `AfterSale`）—— 全部写在**同一物理行**（新增行会顶漂下面 `slot-registry.md:1994-1995` 引的 `:794`/`:803`）。⚠️ 取值**从 `route.query` 现取**、不用 setup 期解构的 `sellerId`/`goodName`（`:433-434` 是普通 `const`，同路径只换 query 时组件被复用、它们**不更新**，理由同 `openPopup` 那条）。 */ const detailText = computed(() => { const q = route.query; const g = goodsWithUser.value.find((x) => x.MerchantId === q.MerchantId && x.GoodName === q.GoodName); return (g && g.Detail) || '' }); const specText = computed(() => { const q = route.query; const g = goodsWithUser.value.find((x) => x.MerchantId === q.MerchantId && x.GoodName === q.GoodName); return (g && g.Spec) || '' }); const afterSaleText = computed(() => { const q = route.query; const g = goodsWithUser.value.find((x) => x.MerchantId === q.MerchantId && x.GoodName === q.GoodName); return (g && g.AfterSale) || '' }); /* `Spec` 的行内 `键=值` 约定：一行一条，`=` 只切**第一处**（值里允许再有 `=`）；没有 `=` 的行**整行进 `.v`、`.k` 留空**，而不是丢掉它。 */ const specRows = computed(() => specText.value.split('\n').map((s) => s.trim()).filter((s) => s !== '').map((s) => { const i = s.indexOf('='); return i < 0 ? { k: '', v: s } : { k: s.slice(0, i).trim(), v: s.slice(i + 1).trim() } })); const promiseLabels = computed(() => { const q = route.query; const g = goodsWithUser.value.find((x) => x.MerchantId === q.MerchantId && x.GoodName === q.GoodName); const ALL = ['正品保障', '7 天无理由', '极速退款', '运费险']; const KEYS = ['PromiseGenuine', 'PromiseReturn7', 'PromiseFastRefund', 'PromiseFreightIns']; if (!g) return ALL; if (!KEYS.some((k) => g[k] !== undefined && g[k] !== null)) return ALL; return ALL.filter((_, i) => Number(g[KEYS[i]])) }); const mergeSkus = async (baseItems) => { try { const rs = await http.post(endpoints.goods.skuList, `MerchantId=${sellerId}&GoodName=${goodName}`); const ds = await rs.json(); const skus = (ds && ds.success && ds.list) || []; if (!skus.length) return baseItems; const img = baseItems.length ? baseItems[0].goodImg : ''; const merged = skus.map((s) => ({ productCode: String(s.SkuCode || ''), price: s.Price, goodImg: img })).filter((s) => s.productCode); return merged.length ? merged : baseItems } catch (e) { return baseItems } }; const isLogin = computed(() => isLoggedIn()); const activeTab = ref('desc')   // 结构二次调整（2026-10-07）：Tab 条当前面，UI 展示状态（不计功能量）；⚠️ **同行追加、不新增行** —— 上面那条「新增行落在最大被引行之后」的约束在本笔**不成立**（`slot-registry.md:1994-1995` 引的 `:794`/`:803` 都在下面），新增行会顶漂它们。
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
.shop .ops { margin-left: auto; display: flex; gap: var(--s2); flex: 0 0 auto; }
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
</style>
