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
                **`AppShell.vue:165`** 消费 ⇒ 这个框是「壳内搜索流」在**本页的唯一入口**；
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

          <!-- ②【促销 / 优惠券】整块级 ⇒ 只挂 `data-slot` + `title`；券框里的「领取」是**控件** ⇒
               逐枚 `disabled` + 逐字取稿的 title。同样：本条 title 稿里不带 C3 前缀（同上）。 -->
          <div
            class="promo"
            data-slot="预留 · 促销 / 优惠券（动现有代码 5 处 · 超档）"
            title="无促销与优惠券字段：本页这一块的落码成本 = endpoints.js + ProductPage.vue = 2 处，**但券无法凭空存在** —— 跨页那套（PDP 领券 / 购物车可用券 / 个人中心我的券 / 结算已用券 / 首页领券中心）合计 5 处，**超档**，因清单点名而画出（见 README 挂账表）"
          >
            <div class="r"><span class="tag">促销</span><span class="cut">满 200 减 30&emsp;满 500 减 80</span></div>
            <div class="r"><span class="tag">领券</span>
              <span class="coupons">
                <span class="coupon"><span class="amt"><i>¥</i>20</span>
                  <span class="cond">满 199 可用</span>
                  <button type="button" class="get" disabled title="领取优惠券（无端点）">领取</button></span>
                <span class="coupon"><span class="amt"><i>¥</i>50</span>
                  <span class="cond">满 499 可用</span>
                  <button type="button" class="get" disabled title="领取优惠券（无端点）">领取</button></span>
              </span>
            </div>
          </div>

          <!-- 型号：稿画的是 3 个 mock 型号（标准款 / 降噪款 / 旗舰款），本页**真值就在手边**
               （`allItems[].productCode`）⇒ 落真码，机制（`currentIndex = idx`）与旧模板逐字同。
               `goodsName` 那行同理已落在 `.pname` 上。 -->
          <div class="pick">
            <div class="lbl">型号</div>
            <div class="row">
              <button
                v-for="(item, idx) in allItems"
                :key="idx"
                type="button"
                class="md"
                :class="{ on: idx === currentIndex }"
                @click="currentIndex = idx"
              >{{ item.productCode }}</button>
            </div>
          </div>

          <!-- 数量：稿 `:967-975` 画的是**活控件**，但本页主区**没有对应状态**（数量只在购入
               弹窗里，`popupQty`），而 D 笔不动 `script` ⇒ 接不上线 ⇒ 整组 `disabled` +
               **逐字取稿的 title**（稿这三条 title 正好逐字描述了「现有实现」，直接取用）。
               登记进台账 §三「稿内但本笔无法接线的控件」。 -->
          <div class="qty">
            <span class="lbl">数量</span>
            <span class="stepper">
              <button type="button" disabled title="减一（现有实现：number 输入 min=0）">−</button>
              <input type="text" value="1" disabled title="现有实现：number 输入，min=0 / max=100" />
              <button type="button" disabled title="加一（现有实现：number 输入 max=100）">+</button>
            </span>
            <span class="stock">现货，下单后 48 小时内发出</span>
          </div>

          <div class="acts">
            <!-- ③【加入购物车】本页**唯一一条在档（N=3）**的占位，也是**控件档** ⇒ 三件齐：
                 块级不适用，这里 `disabled` + `data-slot` + 逐字 title 一个不缺。 -->
            <button
              type="button"
              class="b ghost"
              disabled
              data-slot="预留 · 加入购物车（动现有代码 3 处）"
              title="待 C3〈购物车〉——需新建购物车表 + 加端点（endpoints.js 1 处）+ ProductPage.vue 按钮与数量联动（1 处）+ 购物车页渲染（1 处）= 3 处"
            >加入购物车</button>
            <!-- 【立即购买】既有真功能 `openPopup`（弹窗内选型号 / 数量 → `order.orderAdd`）。
                 ⚠️ 稿 `:981` 的 title 逐字带「（ProductPage.vue:69 → order.orderAdd）」——
                    那是**稿引本仓行号**，而本笔正是重写这个文件的那一笔 ⇒ 当场失效
                    ⇒ 划掉行号、其余逐字取稿（同 commit 11 的先例）。 -->
            <button
              type="button"
              class="b primary"
              title="现有功能：openPopup（→ order.orderAdd）"
              @click="openPopup"
            >立即购买</button>
          </div>

          <div class="promise">
            <span>正品保障</span><span>7 天无理由</span><span>极速退款</span><span>运费险</span>
          </div>

          <!-- ④【店铺主页】`data-slot` 按稿挂**整张商家卡**（⚠️ 不挂「进店逛逛」那个按钮：
               角标要 250px、而按钮只有 90px，挂上去会横跨到左边的「客服」和店名；
               这是「角标只挂区块级」的第三次应验）。标题里的 C3 前缀稿里**有**，逐字保留。
               ⚠️「客服」是**真功能**（`consultProduct` → `message.send`）⇒ 不 `disabled`；
                  只有「进店逛逛」无落点 ⇒ `disabled` + 逐字取稿的 title。
               ⚠️ 店名与 `.sub` 后半句落真值（`currentItem.UserName` / `goodType`），
                  摘掉稿里 mock 的「声谷小店」/「数码配件 ·」。 -->
          <div
            class="shop"
            data-slot="预留 · 店铺主页（动现有代码 4 处 · 超档）"
            title="待 C3〈店铺〉——「进店逛逛」无落点：router/index.js（加路由）+ ProductPage.vue（跳转）+ 商家侧装修入口 + 商家卡数据来源 = 4 处，**超档**，因清单点名而画出（见 README 挂账表）；店铺页本身是新建文件，不计入。⚠️ 「客服」是真功能（message.send），不在此占位内。"
          >
            <span class="mark">{{ (currentItem.UserName || '商')[0] }}</span>
            <span>
              <span class="nm">{{ currentItem.UserName }}小店</span>
              <span class="sub">商品类型：{{ goodType }}</span>
            </span>
            <span class="ops">
              <button type="button" title="现有功能 consultProduct" @click="consultProduct">客服</button>
              <button type="button" disabled title="待 C3〈店铺〉——无落点，4 处改动（超档），见商家卡角标">进店逛逛</button>
            </span>
          </div>
        </div>
      </div>

      <!-- ── 二、Tab 条 + 四个面板 ────────────────────────────────────────────
           ⚠️ **一处待裁示的落法**：切 Tab 要新增页内状态（`activeTab`）⇒ 超出 D 笔边界
              （`script` 段一字不动）⇒ 四枚按钮**逐枚 `disabled`**（稿没给 title，本笔补），
              四个面板**依次全部渲染**、不做切换。取「覆盖率优先」：三个非首屏面板里挂着
              两条超档占位（`.spec` / `.rev`）和一条真功能（售后保障），砍掉任一个都掉覆盖率。
           ⚠️ 四枚按钮上那个 `on` 按稿的**默认切面**保留：稿的 `renderFull('desc')` 会给
              `desc` 那一枚补上 `class="on"`（`ProductPage.html:1011-1012`）⇒ 截图对照时两边一致。
              它在这里**只表示「稿的默认面是它」**，不表示「只有它在显示」——
              四个面板是同时渲染的。 -->
      <nav class="tabs">
        <button type="button" class="on" disabled title="切 Tab 需新增页内状态，超出 D 笔边界（`script` 段不动）；四个面板已依次全部渲染">商品详情</button>
        <button type="button" disabled title="同上：切 Tab 需新增页内状态，超出 D 笔边界">规格参数</button>
        <button type="button" disabled title="同上：切 Tab 需新增页内状态，超出 D 笔边界">累计评价<span class="cnt">—</span></button>
        <button type="button" disabled title="同上：切 Tab 需新增页内状态，超出 D 笔边界">售后保障</button>
      </nav>

      <!-- Tab ① 商品详情。⚠️ 两段介绍文是**稿的演示文案**（本仓 goods 表没有详情字段），
           按「`data-slot` 块内的稿 mock 逐字保留」处理 ⇒ 照留；`data-slot` 按稿只挂 `.longimg`。 -->
      <div class="panel">
        <p class="para">声谷标准版是这一代的主力款：单耳 4.8g，入耳处用液态硅胶做了弧度，
          长时间佩戴不压耳廓。蓝牙 5.3 双连，手机与笔记本之间切换不用重新配对。</p>
        <p class="para">充电盒支持 10 分钟快充补 3 小时，通勤路上临时补电够用到下班。</p>
        <div
          class="longimg"
          data-slot="预留 · 详情富文本（动现有代码 6 处 · 超档）"
          title="待 C3〈商品〉——goods 表无详情字段：mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsAdd.vue（录入）+ ProductPage.vue（展示）= 6 处，**超档**，因清单点名而画出（见 README 挂账表）"
        >商品详情长图&emsp;1200 × 400</div>
      </div>

      <!-- Tab ② 规格参数。整块级占位（`<div>`）⇒ 只挂 `data-slot` + `title`；表内 8 行是**块内
           mock** ⇒ 逐字保留。 -->
      <div class="panel">
        <div
          class="spec"
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
      <div class="panel">
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

      <!-- Tab ④ 售后保障：稿判「真功能 N=0」（纯静态政策文案）⇒ 不标 slot、不 `disabled` -->
      <div class="panel">
        <div class="pol">
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
              ⇒ 落真卡：`goodsWithUser` 取前 4 条（与稿的 4 栏栅格对齐），每张是**真
              `<router-link>`** 指向既有路由 `/product`，参数用本页已有的 4 个 query 名。
           ⚠️ 稿的 `.pr` 是三段拆字（`¥` / `89` / `.00`）⇒ 只落前两段，`<em>` 那两位不落
              （数据没有固定小数位，与主区 `.price` 同一口径）。
           ⚠️ `.ph` 稿是灰方块 ⇒ 保留既有真图并补 `v-if`（`goodImg` 为空时裸 `<img src="">`
              画的是破图图标 —— 同 `HistoricalOrders.vue:66` 先例）。 -->
      <section class="reco">
        <h2 title="真功能：现有端点 goods.goodsSelectHome，本区不需要新端点">猜你喜欢</h2>
        <p v-if="goodsWithUser.length === 0" class="none">暂无推荐</p>
        <div v-else class="cards">
          <router-link
            v-for="goods in goodsWithUser.slice(0, 4)"
            :key="goods.Id"
            class="card"
            :to="{ path: '/product', query: { MerchantId: goods.MerchantId, GoodName: goods.GoodName, GoodType: goods.GoodType, Id: goods.Id } }"
          >
            <div class="ph"><img v-if="goods.GoodImg" :src="goods.GoodImg" /></div>
            <div class="nm">{{ goods.GoodName }}</div>
            <div class="pr"><i>¥</i><b>{{ goods.Price }}</b></div>
          </router-link>
        </div>
      </section>
    </div>

    <!-- 购入弹窗 —— ⚠️ **本笔不碰**：稿的 `dlg1` 面（`ProductPage.html:706-772`）就是用户登记的
         「**购入抽屉**」（B 笔判「未达标」、归后续笔；D 笔边界一栏明文列在「一律不碰」里）
         ⇒ 下面的标记与它那一整段 `.popup-*` / `.p-*` 样式**一字未动**。 -->
    <div class="popup-mask" v-if="showPopup" @click="showPopup=false">
      <div class="popup-content" @click.stop>
        <div class="p-top"></div>
        <div class="p-middle">
          <div class="p-img"><img :src="popupCurrentItem.goodImg" /></div>
          <div class="p-info">
            <div class="p-price">单价：¥{{ popupCurrentItem.price }}</div>
            <div class="p-codes">
              <button
                v-for="(item, idx) in popupAllItems"
                :key="idx"
                class="p-code-btn"
                :class="{ active: idx === popupCurrentIndex }"
                @click="popupCurrentIndex = idx"
              >
                {{ item.productCode }}
              </button>
            </div>
            <div class="p-num">
              <button @click="setPopQty(-1)">-</button>
              <input
                type="number"
                :value="popupQty[popupCurrentItem.productCode] || 0"
                @input="setPopQtyInput($event.target.value)"
                min="0"
                max="100"
              />
              <button @click="setPopQty(1)">+</button>
            </div>
          </div>
        </div>
        <div class="p-bottom">
          <div class="p-list">
            <div class="p-item" v-for="(it, idx) in popupBuyList" :key="idx">
              <span>{{ goodName }}：{{ it.productCode }}：{{ it.quantity }}</span>
              <button class="p-del" @click="delPopItem(it.productCode)">-</button>
            </div>
          </div>
          <div class="p-total">合计：¥{{ popupTotal }}</div>
          <button class="p-confirm" @click="confirmPurchase">确认购入</button>
        </div>
      </div>
    </div>

    <!-- 付款确认弹窗 —— 稿的 `dlg2` 面（`ProductPage.html:769-789`），**既有真功能**
         （`handlePay` → `order.orderAdd`）⇒ 按「稿里画了一律落」照落。
         ⚠️ 稿是「否」在前、「是」在后，与既有实现相反 ⇒ 本笔照稿，属**有意改动**（登记）。
         旧 `.pay-confirm-*` / `.dialog-*` / `.yes-btn` / `.no-btn` 一并退役。 -->
    <div class="scrim" v-if="showPayConfirm" @click.self="showPayConfirm=false">
      <div class="dlg dlg-narrow">
        <div class="dlg-h">是否现在付款？</div>
        <div class="dlg-sub">确认购入后将生成订单（现有端点 order.orderAdd）。</div>
        <div class="dacts">
          <button type="button" class="b no" @click="handlePay(false)">否</button>
          <button type="button" class="b ok" @click="handlePay(true)">是</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId, isLoggedIn } from '../api/auth'

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
const showPopup = ref(false)
const popupAllItems = ref([])
const popupCurrentIndex = ref(0)
const popupCurrentItem = computed(() => popupAllItems.value[popupCurrentIndex.value] || { productCode: '' })
const popupQty = ref({})

const openPopup = async () => {
  await loadPopupItems()
  const q = {}
  popupAllItems.value.forEach((i) => (q[i.productCode] = 0))
  popupQty.value = q
  popupCurrentIndex.value = 0
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
    popupAllItems.value = items
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
}

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
})

// 订单提交
const showPayConfirm = ref(false)
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
    OrderId: generateOrderId(),
    Price: popupTotal.value,
    ProductInformation: buildProductInformation(),
    Images: getFirstImage(),
    DeliveryIf: 0,
    ReceiveIf: 0
  }
  showPayConfirm.value = true
}

const handlePay = async (payIf) => {
  showPayConfirm.value = false
  if (!pendingOrderData) return

  const orderData = {
    ...pendingOrderData,
    PayIf: payIf ? 1 : 0
  }

  try {
    const params = new URLSearchParams()
    params.append('CustomerId', orderData.CustomerId)
    params.append('MerchantId', orderData.MerchantId)
    params.append('OrderId', orderData.OrderId)
    params.append('Price', orderData.Price)
    params.append('ProductInformation', orderData.ProductInformation)
    params.append('Images', orderData.Images)
    params.append('PayIf', orderData.PayIf)
    params.append('DeliveryIf', orderData.DeliveryIf)
    params.append('ReceiveIf', orderData.ReceiveIf)

    const res = await http.post(endpoints.order.orderAdd, params.toString())
    const result = await res.json()
    if (result.success) {
      alert('订单提交成功！')
      showPopup.value = false
      popupQty.value = {}
      const isMerchant = !!getMerchantId()
      if (isMerchant) {
        router.push('/merchant?page=order')
      } else {
        router.push('/customer?page=cart')
      }
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
//    `LoginPage.landingAfterLogin` 送回本页（T-guard-6/-10）。放文件末尾是**刻意的**：`WebAuthConfig.java:70` 引了 `:462-465`，新增行落在最大被引行之后，上面 24 个被引目标才一个都不漂。
const isLogin = computed(() => isLoggedIn())
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
     落法同 `UserInfoPage.vue:393` / `GoodsAdd.vue:510` 两处先例。 */
  --d-title: var(--fs-lg);
  --d-price: var(--fs-2xl);
}
/* 控件一律继承页面字体 —— `tokens.css` **刻意不设** `body` 的 `font-family`，
   而浏览器 UA 样式表会给 `button` / `input` 打上自己的 `font` ⇒ 不写这两行，
   全页按钮会掉出 `--font`。落法同 `GoodsUp.vue:549` / `GoodsAdd.vue:505`。 */
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
.coupons { display: flex; gap: var(--s3); flex-wrap: wrap; align-items: center; }
.coupon { flex: 0 0 auto; display: flex; align-items: center; gap: var(--s3);
          padding: var(--s2) var(--s3); border: 1px dashed var(--accent); border-radius: var(--r1); }
.coupon .amt { font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-med);
               color: var(--accent); line-height: 1; font-variant-numeric: tabular-nums; white-space: nowrap; }
.coupon .amt i { font-style: normal; font-size: var(--fs-xs); margin-right: 1px; }
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

/* 动作：两级权重 —— 实心（立即购买）/ 描边（加入购物车，占位） */
.acts { margin-top: var(--s6); display: flex; gap: var(--s3); }
.acts .b { height: 52px; border-radius: var(--r2); font-size: var(--fs-md); font-weight: var(--fw-med); }
.acts .primary { flex: 1 1 auto; background: var(--brand); color: var(--bg); }
.acts .primary:hover { background: var(--brand-hover); }
.acts .ghost { flex: 0 0 176px; border: 1px solid var(--line-2); color: var(--ink-3); }
.acts .ghost:disabled { cursor: not-allowed; }

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
.tabs button:disabled { cursor: not-allowed; }
/* 按稿的默认切面保留（见 `<template>` 里的说明），四个面板同时渲染时它只是「稿的默认面」 */
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
.cards { margin-top: var(--s5); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s5); }
.card { display: block; color: inherit; text-decoration: none; }
.card .ph { aspect-ratio: 1/1; border: 1px solid var(--line); border-radius: var(--r2);
            background: var(--img); overflow: hidden; }
.card .ph img { width: 100%; height: 100%; object-fit: cover; }
.card .nm { margin-top: var(--s3); font-size: var(--fs-sm); line-height: 1.5; min-height: 3em;
            display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card .pr { margin-top: var(--s2); display: flex; align-items: baseline; color: var(--accent);
            font-family: var(--font-display); font-variant-numeric: tabular-nums; }
.card .pr i { font-style: normal; font-size: var(--fs-xs); margin-right: 1px; }
.card .pr b { font-size: var(--fs-md); font-weight: var(--fw-med); line-height: 1; }

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

/* ══ 购入弹窗 —— **本笔不碰** ═══════════════════════════════════════════════
   稿的 `dlg1` 面（`ProductPage.html:706-772`）就是用户 2026-10-07 登记的「**购入抽屉**」：
   B 笔判它「未达标」、归后续笔，D 笔边界一栏把它明文列在「一律不碰」（手滑碰到 = PR 打回）。
   ⇒ 下面这 112 行是**从 HEAD 逐字搬过来的**，一个字都没动。 */
.popup-mask {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: flex-end;
  z-index: 1000;
}
.popup-content {
  width: 100%;
  height: 80%;
  background: #fff;
  border-radius: 12px 12px 0 0;
  display: flex;
  flex-direction: column;
}
.p-top { height: 30%; }
.p-middle {
  height: 40%;
  display: flex;
  padding: 15px;
  box-sizing: border-box;
}
.p-img {
  width: 30%;
  height: 100%;
}
.p-img img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.p-info {
  width: 70%;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 15px;
}
.p-price {
  font-size: 22px;
  color: red;
  font-weight: bold;
}
.p-codes { display: flex; gap: 10px; flex-wrap: wrap; }
.p-code-btn {
  padding: 8px 14px;
  border: 1px solid #ccc;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
}
.p-code-btn.active { background: #028fa8; color: #fff; }
.p-num {
  display: flex;
  align-items: center;
  gap: 10px;
}
.p-num button {
  width: 30px;
  height: 30px;
  border: 1px solid #ccc;
  background: #fff;
  cursor: pointer;
}
.p-num input {
  width: 60px;
  height: 30px;
  text-align: center;
  border: 1px solid #ccc;
}
.p-bottom {
  height: 30%;
  padding: 15px;
  box-sizing: border-box;
  border-top: 1px solid #eee;
  display: flex;
  flex-direction: column;
}
.p-list { flex: 1; overflow-y: auto; }
.p-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f5f5f5;
}
.p-del {
  width: 24px;
  height: 24px;
  border: 1px solid #ccc;
  background: #fff;
  border-radius: 50%;
  cursor: pointer;
}
.p-total {
  text-align: right;
  padding: 10px 0;
  font-weight: bold;
}
.p-confirm {
  align-self: flex-end;
  padding: 10px 20px;
  background: #028fa8;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
</style>
