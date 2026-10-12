<template>
  <!--
    D 笔 commit 7 —— 本页承载的是稿里的 `<main class="uc-main">` 那一支。
    基线：`docs/美化/pages/UserCenter-customer.html`（455 行，① – ⑥ 在 `:302-393`）
        + `docs/美化/pages/UserCenter-merchant.html`（434 行，① – ⑥ 在 `:290-374`）。
    ⚠️ 稿是**两份**（客户 / 商家），但左菜单那一支（`.uc-menu`，稿 `:291` / `:277`）**已由 C 笔
       落进 `UserCenter.vue`**（该文件 `:77-86` 引稿 `:141-151` / `:134-145`）⇒ 本页**只承载右栏**。
       `UserCenter.vue` 是多根 fragment（`.user-menu` + `<router-view>`），`AppShell.vue:440-444`
       的 `.user-page` 是 flex 行 ⇒ `<main class="uc-main">` 就是那一行的第二个 flex 子项。
    角色分支**只写在模板里**（判据的**唯一真源** = `/user/me` 的 `role`，落成 `<script setup>` 里一个叫 `isMerchantShell` 的 computed；D 笔当年那句「与 `getUserData()` 的分支逐字同构」**已被 C3 块 2〈用户块〉作废**，三条理由见该 computed。C3 块 2 另给本页加「降级状态条」与 ⑦「资料完善」，两处都不改本页原有四个函数的语义）。
  -->
  <main class="uc-main">

    <h1 class="page-h">个人信息</h1>
    <!-- ⚠️ 用户裁决 F3 = 甲：稿原文是「…在 ChatRoom.html 里展开。」—— 稿文件名进**可见正文**
         是残渣，改指「消息页」（`Person`/「消息」菜单项指向的真路由，稿 `:297` / `:286`）。 -->
    <p class="page-h-sub">历史购买记录、好友与消息在消息页里展开。</p>

    <!-- ── ① 身份卡 —— 釉面（稿全页唯一强面）。两稿逐字同构，不分支 ──────────────────
         稿 `:308-320`（客户，字母圆牌「声」）/ `:296-308`（商家，「商」）。
         ⚠️ 稿的 `.avatar` 是 `<div>声</div>` 那种**字母圆牌**，那是它的 mock —— 本页保留
            **既有真头像** `<img>`（`:src` 走 `userInfo.image`，兜底 `assetOf('/img/default_user.png')`，
            与原实现逐字一致）⇒ 稿那条规则里的 `font-*` / `color` 三条对 `<img>` 不起作用，
            未落；补 `object-fit:cover`（原实现就有）。「更换头像照片」按钮是真事件，保持不变。 -->
    <section class="ident">
      <img
        class="avatar"
        :src="userInfo.image || assetOf('/img/default_user.png')"
        alt="头像"
      />
      <div class="ident-who">
        <div class="ident-name">{{ userInfo.username || '未知用户' }}</div>
        <!-- 稿 `:312` 是「客户 ID plzTWafaGp」（编的业务号）；本页落**真**的
             `<idType> <idValue>`（`getUserData()` 按 `customerid` / `merchantid` 分支填）。 -->
        <div class="ident-id">{{ idType }} {{ idValue }}</div>
      </div>
      <dl class="ident-meta">
        <div><dt class="k">用户类型</dt><dd class="v">{{ userInfo.usertype || '—' }}</dd></div>
        <div><dt class="k">性别</dt><dd class="v">{{ userInfo.gender || '—' }}</dd></div>
        <div><dt class="k">年龄</dt><dd class="v">{{ userInfo.age || '—' }}</dd></div>
      </dl>
      <button class="btn btn-plain ident-btn" @click="chooseImage">更换头像照片</button>
      <input
        ref="fileInput"
        type="file"
        accept="image/*"
        class="file-hidden"
        @change="doUpload"
      >
    </section><!-- ⚠️ C3 块 2〈用户块〉**降级状态条**：同行追加、不新插一行 —— 本文件第 55 行（分支判据那行）被 `context-packs/c3-block-2-user.md` 按行号引，而它在本行之后 ⇒ 插行会让那条引用当场失效。位置选在 ① 身份卡之后、两支模板之前：`scheduled`（还没降级，人在商家壳）与 `frozen`（已降级，`login.Role` 已被改回 1 ⇒ 人在客户壳）画的正是两支不同模板，状态条必须在两边都看得见。文案里**不给**「申诉 / 恢复」控件：恢复走管理端 `downgrade/relist`，商家侧本块没有自助端点；`DowngradeReason` 本身就是要给商家看的原因（`/user/me` 那 5 列）。 --><p v-if="dg" class="dg-note" :class="dg.state"><b class="dg-tag">{{ dg.tag }}</b>{{ dg.text }}<span class="dg-why">原因：{{ dg.reason }}</span></p>

    <!-- ── ②–⑤ 客户 / 商家两版（稿分两份）—— ⚠️ 分支判据 C3 块 2〈用户块〉**已换**：旧 `!customerid && merchantid`
         （与 `getUserData()` 里 `if (data.customerid) … else if (data.merchantid)` 同构）**已废** ⇒ 现为 `isMerchantShell`。
         旧的为什么必须废、新的为什么只能是它：三条理由逐条写在 `<script setup>` 里那个同名 `computed` 上。 -->
    <template v-if="isMerchantShell">

      <!-- ── ② 我的订单（商家版）—— 四格浅底色块。稿 `:320-333` ─────────────────────
           ⚠️ C3 块 5 · B 组（2026-10-12）**本块从 ③ 上移到 ②**（用户定稿「我的订单（4 项）← 上」），
              与用户端**逐字同序**：订单在上、无名框在下。
           ⚠️ 四格文案按用户 2026-10-12 裁定 2「**按用户写的**……与用户端逐字一致（含四格的副文案）」——
              原来的「待发货 / 待收货 / 退款申请 / 待评价」（按处理状态分列）换成
              「待付款 / 待发货 / 待收货 / 已完成」。**这是用户逐字指定的文案 + 用户给的项名，不是此处自裁。**
           ⚠️ 右侧那枚 `disabled`「去订单中心」**保持现状**（方案 §4.1：本块只换四格文案与块序）。 -->
      <section
        class="sec"
        data-slot="预留 · 我的订单四入口（待 C3〈订单〉）"
        title="待 C3〈订单〉——OrderDao.selectByMerchant 的 SELECT 不取状态筛选参数，无按状态分列端点（N=4）"
      >
        <div class="sec-h">
          <h2>我的订单</h2>
          <button class="more" disabled title="订单中心在顶栏（/merchant/order），此处不做重复入口">去订单中心</button>
        </div>
        <!-- ⚠️ 角标只挂区块级：四格一律 disabled + title，不逐格加 data-slot（稿 `:326` 原话）。 -->
        <div class="tiles">
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待付款</b><span>下单后未完成支付</span></button>
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待发货</b><span>商家尚未发出</span></button>
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待收货</b><span>商品在途</span></button>
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>已完成</b><span>确认收货的订单</span></button>
        </div>
      </section>

      <!-- ── ③【无名框】7 项（商家版）—— **无块标题**（用户 2026-10-12 定稿「无名框，无块标题」）──
           一格的形状与用户端那 4 格**逐条同款**：`.v`（大字号，--brand-ink）+ `.k`（小字标签）。
           布局规则（用户定稿「布局规则」段，逐条落）：一行 4 块 · 超出换行 · 第 2 行不足 4 块**左对齐**（甲）
           · **容器级纵向滚动**（乙）· 隐藏原生滚动条 · **下边缘渐隐**（「与领券中心统一」⇒ 78% 停点）。
           ⇒ 多出来的 `.assets-scroll` **只挂这一份**：用户端那 4 项一行放得下 ⇒ 不滚、不渐隐（样式见 <style>）。
           ⚠️ 七项里**只有「优惠券」复用客户版那颗 `div[role=button]`**（用户 2026-10-12 两处待定 ①：
              「券包是『我这个人』的券，不分商家 / 用户视角」）—— 同一组件、同一数据源（`customerId`）。
           ⚠️ **边界 2（用户 2026-10-12 要求给明确态）**：`user.customerid` 为空时券包**没有账号可挂**
              ⇒ 那一格落 `—` + **不可点**（裸 `div` + `title` 说明原因），**不弹一个空账号的券包**。
              ⚠️ 现实性（VM 库实测）：**14 个 `role=2` 商家、14 个 `customerid` 为空、0 个非空**（种子商家
              是直接建号、没走过客户阶段；只有 `promoteToMerchant` 那条路才「刻意保留 customerid」）
              ⇒ **手验时商家端这一格必然看到 `—`**；真入口要在「先注册成客户、再入驻通过」的账号上才看得到。
           ⚠️ 「店铺信息」跳 `/shop?MerchantId=<自己的商家号>` —— **那条路由属 commit C**（本 commit 里
              点它只有 vue-router 的控制台告警、不跳转），这是**按批准的三 commit 切分**排的，不是遗漏。 -->
      <section
        class="assets assets-scroll"
        data-slot="预留 · 商家资产与等级（待 C3〈商家中心〉）"
        title="待 C3〈商家中心〉——积分 / 余额 / 我的收藏三格无聚合端点（N=3），经营等级规则先占位（裁定 ④）；⚠️ 「优惠券」格已于 C3 块 5 接通（复用用户中心券包弹窗）、「店铺信息」「商家认证」两格已接通（跳店铺主页 / 读 /merchant/myApplications 最新一条的状态）"
      >
        <div><div class="v">—</div><div class="k">积分</div></div>
        <div
          v-if="customerId"
          class="asset-entry"
          role="button"
          tabindex="0"
          :title="walletTitle"
          @click="openWallet"
          @keydown.enter.prevent="openWallet"
          @keydown.space.prevent="openWallet"
        >
          <div class="v">{{ walletCount === null ? '—' : walletCount }}<i v-if="walletRedDot" class="wallet-dot" title="有券快过期">●</i></div>
          <div class="k">优惠券</div>
        </div>
        <!-- 边界 2 的空态：**不可点**的裸 `div`（与用户端另外三格同形），`title` 说明为什么没有。 -->
        <div
          v-else
          title="本账号没有客户号（user.customerid 为空）—— 券包挂在客户号上，这一格因此没有券可看。入驻时若保留过客户号，它会自动变成真券包入口。"
        >
          <div class="v">—</div><div class="k">优惠券</div>
        </div>
        <div><div class="v">—</div><div class="k">余额</div></div>
        <div><div class="v">—</div><div class="k">我的收藏</div></div>
        <div
          class="asset-entry"
          role="button"
          tabindex="0"
          title="去我自己的店铺主页"
          @click="enterMyShop"
          @keydown.enter.prevent="enterMyShop"
          @keydown.space.prevent="enterMyShop"
        >
          <div class="v">进店</div><div class="k">店铺信息</div>
        </div>
        <div
          class="asset-entry"
          role="button"
          tabindex="0"
          :title="certTitle"
          @click="openCert"
          @keydown.enter.prevent="openCert"
          @keydown.space.prevent="openCert"
        >
          <div class="v">{{ certLabel }}</div><div class="k">商家认证</div>
        </div>
        <div
          class="asset-entry"
          role="button"
          tabindex="0"
          title="经营等级规则先占位（用户 2026-10-12 裁定 ④）—— 点开看说明"
          @click="openLevel"
          @keydown.enter.prevent="openLevel"
          @keydown.space.prevent="openLevel"
        >
          <div class="v">—</div><div class="k">经营等级</div>
        </div>
      </section>

      <!-- ── ④ 最近上架（商家版）—— 卡片 + hover。稿 `:336-349` ───────────────────
           ⚠️ **本页唯一一条真 `<router-link>`**：「查看全部」指向 `/merchant/user/list`
              （`router/index.js:117`，路由**已存在**）⇒ 按用户裁决 F2 = 甲，走真 router-link。
              `router-link` **不是功能增量**（不调 API、不改状态、不动 script），「功能量恒为 0」
              管的是 API / 状态机 / 逻辑，不管 UI 导航。
           ⚠️ 卡片里的名 / 价都是稿编的 ⇒ **落 `—`**（同 F1 口径）。稿的 6 张卡结构照落。 -->
      <section class="sec">
        <div class="sec-h">
          <h2>最近上架</h2>
          <!-- ⚠️ `title` 逐字取稿（口径 4），含稿写错的组件名 —— 稿 `:339` 原文写
               「（查看商品，GoodsSelect）」，而 `/merchant/user/list` 在 `router/index.js:117`
               映射的其实是 **`GoodsUp`**。照稿逐字落 + 登记，与本笔另两处同口径。 -->
          <router-link
            class="more"
            to="/merchant/user/list"
            title="真路由 —— /merchant/user/list（查看商品，GoodsSelect）"
          >查看全部</router-link>
        </div>
        <div class="rail">
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
        </div>
      </section>

      <!-- ── ⑤ 服务（商家版）—— 四列文字（间距，无框无线）。稿 `:352-361` ───────────
           ⚠️ 稿是 `<a title="无端点（N=4）">`（无 href）⇒ 落成 `<button disabled>` + 同 `title`，
              **不画死控件**（先例 `ShoppingCart.vue:25-33` / `OrderCenter.vue` 的「首页」面包屑）。 -->
      <section
        class="sec"
        data-slot="预留 · 服务（待 C3〈商家中心〉）"
        title="待 C3〈商家中心〉——资质 / 结算均无端点（N=4）"
      >
        <div class="sec-h"><h2>服务</h2></div>
        <div class="svc">
          <button disabled title="无端点（N=4）"><b>资质认证</b><span>营业执照与经营许可</span></button>
          <button disabled title="无端点（N=4）"><b>结算账户</b><span>收款账户与提现记录</span></button>
          <button disabled title="无端点（N=4）"><b>经营规则</b><span>发布规范与违规记录</span></button>
          <button disabled title="无端点（N=4）"><b>商家帮助</b><span>常见问题与专属客服</span></button>
        </div>
      </section>

    </template>

    <template v-else>

      <!-- ── ② 我的订单（客户版）—— 四格浅底色块。稿 `:332-345` ─────────────────────
           ⚠️ C3 块 5 · B 组（2026-10-12）**本块从 ③ 上移到 ②**（用户定稿「我的订单（4 项）← 上」）。
              本块**内容一字未动**（四格文案与各 `title` 照旧）—— 只换了它在页里的位置，
              商家端那 4 格才是照本节文案改的（裁定 2：商家端「与用户端逐字一致」）。 -->
      <section
        class="sec"
        data-slot="预留 · 我的订单四入口（待 C3〈订单〉）"
        title="待 C3〈订单〉——客户侧只有一个整单列表页 /customer/cart，无按状态筛选端点（N=4）"
      >
        <div class="sec-h">
          <h2>我的订单</h2>
          <button class="more" disabled title="待 C3〈订单〉——无「全部订单」列表端点">全部订单</button>
        </div>
        <div class="tiles">
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待付款</b><span>下单后未完成支付</span></button>
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待发货</b><span>商家尚未发出</span></button>
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待收货</b><span>商品在途</span></button>
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>已完成</b><span>确认收货的订单</span></button>
        </div>
      </section>

      <!-- ── ③【无名框】4 项（客户版）—— **无块标题**，间距分隔（无线）+ 数字用色。稿 `:323-329` ────
           ⚠️ C3 块 5 · B 组（2026-10-12）**本块从 ② 下移到 ③**（用户定稿「[无名框]（4 项）← 下」）。
           ⚠️ 4 项一行放得下 ⇒ **不滚、不渐隐**：本节的 `class` **不加** `.assets-scroll`（那一条只挂商家版
              7 项那一份）。这是两壳在样式上的唯一分叉，判据是「项数放不放得下」，不是「谁是商家」。
           ⚠️ 与商家版同一条：稿那 4 个数（1,280 / 3 张 / ¥286.50 / 12 件）是示意值，
              **其余三格**无聚合端点 ⇒ **落 `—`**（F1 = 甲）。单位 `<small>张/¥/件</small>` 随之不落。
           ⚠️ **2026-10-11（块 5 · 券的信息架构重排）：「优惠券」一格已接通** —— 它是「我的券包」的
              **唯一入口**（用户定「主页不显示券包」，主页那两处入口已删）⇒ 从裸 `div` 改成
              可点元素 + 真券数 + 快过期红点，点开是 `components/CouponWalletDialog.vue`。
              另三格**不动**（仍是 `—`）。 ⇒ 本节 `title` 里「均无聚合端点（N=4）」那一句已不成立，随之改掉。
           ⚠️ **本节与商家版那 7 项里的「优惠券」格是同一颗 `div[role=button]`、同一个 `customerId`、
              同一个弹窗**（块 5 B 组两处待定 ①：「券包是『我这个人』的券，不分视角」）—— 当年那句
              「商家看自己的券在管理端，不在这个壳里」**已废**，别再按它推断商家壳里没有券包入口。 -->
      <section
        class="assets"
        data-slot="预留 · 我的资产（待 C3〈个人中心〉）"
        title="待 C3〈个人中心〉——积分/余额/收藏三格无聚合端点（N=3）；⚠️ 「优惠券」一格已于 C3 块 5 接通（真券数 + 券包弹窗入口）；稿的 4 个数字为稿自注的示意值，未接通的仍落 —（同 OrderCenter KPI 口径）"
      >
        <div><div class="v">—</div><div class="k">积分</div></div>
        <!-- ⚠️ `div[role=button]` 而**不是** `<button>`：里面的 `.v` / `.k` 是块级 `div`（`.assets .k`
             的 `margin-top` 要靠块级才生效），而 `<button>` 的内容模型只收**短语内容**。
             要键盘可达就得自己补 `tabindex` + `keydown`（点开 = 打开券包弹窗）。 -->
        <div
          class="asset-entry"
          role="button"
          tabindex="0"
          :title="walletTitle"
          @click="openWallet"
          @keydown.enter.prevent="openWallet"
          @keydown.space.prevent="openWallet"
        >
          <div class="v">{{ walletCount === null ? '—' : walletCount }}<i v-if="walletRedDot" class="wallet-dot" title="有券快过期">●</i></div>
          <div class="k">优惠券</div>
        </div>
        <div><div class="v">—</div><div class="k">余额</div></div>
        <div><div class="v">—</div><div class="k">我的收藏</div></div>
      </section>

      <!-- ── ④ 收藏 / 浏览足迹（客户版）—— 卡片 + hover。稿 `:348-368` ─────────────
           ⚠️ 稿的三枚「更多」全是禁用（无端点）；卡片名 / 价同样是稿编的 ⇒ 落 `—`。
              稿 `:350` / `:360` 那两枚 `.more` **稿里连 `title` 都没有**（裸 `disabled`）
              ⇒ 本笔**补** `title`（判据①：可点元素要么绑事件、要么 disabled + tooltip + 登记归属）。 -->
      <section
        class="sec"
        data-slot="预留 · 收藏 / 浏览足迹（待 C3〈个人中心〉）"
        title="待 C3〈个人中心〉——收藏与浏览足迹均无端点（N=3）；⚠️ 卡片的名 / 价均为稿的示意值，本笔落 —（同 F1 口径）"
      >
        <div class="sec-h">
          <h2>我的收藏</h2>
          <button class="more" disabled title="待 C3〈个人中心〉——无收藏列表端点（N=3）">查看全部</button>
        </div>
        <div class="rail">
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
        </div>

        <div class="sec-h sec-h-2nd">
          <h2>浏览足迹</h2>
          <button class="more" disabled title="待 C3〈个人中心〉——无浏览足迹端点（N=3）">清空足迹</button>
        </div>
        <div class="rail rail-sm">
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
          <div class="pcard"><div class="pthumb"></div><div class="pname">—</div><div class="pprice">—</div></div>
        </div>
      </section>

      <!-- ── ⑤ 服务（客户版）—— 四列文字。稿 `:371-380`。处置同商家版（`<a>` → `disabled` 按钮）── -->
      <section
        class="sec"
        data-slot="预留 · 服务（待 C3〈个人中心〉）"
        title="待 C3〈个人中心〉——收货地址 / 账号安全均无端点（N=4）"
      >
        <div class="sec-h"><h2>服务</h2></div>
        <div class="svc">
          <button disabled title="无端点（N=4）"><b>收货地址</b><span>维护常用收货信息</span></button>
          <button disabled title="无端点（N=4）"><b>账号与安全</b><span>密码、手机号、登录设备</span></button>
          <button disabled title="无端点（N=4）"><b>退款 / 售后</b><span>退货退款进度与记录</span></button>
          <button disabled title="无端点（N=4）"><b>帮助中心</b><span>常见问题与联系客服</span></button>
        </div>
      </section>

    </template>

    <!-- ── ⑥ 账号设置 —— 线（全页只有这一块用线）。两稿逐字同构（稿 `:383-391` / `:364-372`）──
         ⚠️ 本块是**既有真功能**：三个 `v-model` + `saveUserInfo()` 发 `POST /user/updateUserInfo`
            的路径**逐字保留**（`getUserData` / `saveUserInfo` / `doUpload` / `chooseImage` 一行未动）。 -->
    <section class="sec">
      <div class="sec-h"><h2>账号设置</h2></div>
      <div class="form2">
        <div class="f">
          <label for="mail">邮箱</label>
          <input id="mail" type="text" v-model="userInfo.email" placeholder="暂未填写邮箱">
        </div>
        <div class="f">
          <label for="addr">常用地址</label>
          <input id="addr" type="text" v-model="userInfo.address" placeholder="暂未填写地址">
        </div>
      </div>
      <div class="f">
        <label for="bio">自我介绍</label>
        <textarea id="bio" v-model="userInfo.selfintroduction" placeholder="暂无自我介绍"></textarea>
      </div>
      <div class="actions">
        <button class="btn btn-primary" @click="saveUserInfo"><IconGlyph name="ok" />保存个人信息</button>
      </div>
    </section>

    <!-- ── ⑦ 资料完善（C3 块 2〈用户块〉**新增**）─────────────────────────────────────
         ⚠️ 与 ① 身份卡的分工：卡是**只读展示**（`usertype` / `gender` / `age`），本块才是**编辑**入口；
            `gender` / `age` 在本笔**之前没有任何前端入口**（`/user/updateProfile` 是新端点）。
         ⚠️ 它走 `POST /user/updateProfile`（**不是**上面那个 `/user/updateUserInfo`）—— 两个端点收的
            字段集**不相交**，**别合并成一个「保存」**：合并会要求一次请求改两处，而任一处失败时
            「另一处算不算存下」没有答案（后端也是两个 `@PostMapping`）。
         ⚠️ 空值语义：留空并保存 = **清空该列**（后端把「没填」当「没有」，不当「保持原值」）。 -->
    <section class="sec">
      <div class="sec-h"><h2>资料完善</h2></div>
      <div class="form2">
        <div class="f">
          <label for="gender">性别</label>
          <select id="gender" v-model="userInfo.gender">
            <option value="">未填写</option>
            <option value="男">男</option>
            <option value="女">女</option>
          </select>
        </div>
        <div class="f">
          <label for="age">年龄</label>
          <input id="age" type="number" min="0" max="150" v-model.number="userInfo.age" placeholder="暂未填写年龄">
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" @click="saveProfile"><IconGlyph name="ok" />保存资料完善</button>
        <span class="dp-hint">留空 = 清空该列（后端把「没填」当「没有」）</span>
      </div>
    </section>


    <!-- ⚠️ 两枚弹窗都挂在**两支模板之外、`</main>` 之前**（与 ⑥⑦ 同层）⇒ 两支壳都 mount 得到。
         ⚠️ 这里原来写着「券包弹窗挂在**客户分支内**（`<template v-else>` 里）／商家壳根本不 mount 它」
            —— **注释与事实不符**：`<template v-else>` 在 ⑥ 之前就闭合了，本组件一直挂在两支之外
            （块 5 B 组 2026-10-12 按方案 §4.3 复核时发现并订正：行为早就对，注释写反了）。
            连带地，`getUserData()` 里那句「只给客户壳发券包请求」也一并改掉（见那处注释）。
         ⚠️ 两枚都是 `position: fixed` 的遮罩，放在 `<main>` 里不影响布局。 -->
    <CouponWalletDialog
      :open="walletOpen"
      :account="customerId"
      @close="walletOpen = false"
      @changed="loadWallet"
    />

    <!-- ⚠️ 商家端【无名框】里「商家认证」「经营等级」两格共用的**一枚**遮罩（用户 2026-10-12
         裁定 ③「不跳新页」）—— 面板由 `cardPanel` 决定画哪一份。**不另建组件**：两格在同一格里
         挨着，开两枚遮罩只会多一份要同步的 z-index 与关闭逻辑。
         ⚠️ 视觉逐条对齐 `CouponWalletDialog.vue` 的遮罩（`.cscrim` 的 z-index 300 档、`.cdlg` 的
            宽 / 圆角 / 边 / 阴影）—— 同一页里的两枚遮罩必须像一套；`.mc-*` 前缀避开它那套 `c-`/`w-`。 -->
    <div v-if="cardPanel" class="mc-scrim" @click.self="cardPanel = null">
      <div class="mc-dlg" role="dialog" aria-modal="true">
        <div class="mc-dlg-h">
          <span>{{ cardPanel === 'cert' ? '商家认证' : '经营等级' }}</span>
          <button class="mc-x" aria-label="关闭" @click="cardPanel = null">×</button>
        </div>
        <div class="mc-dlg-b">
          <p class="mc-state">{{ cardPanel === 'cert' ? certLabel : '—' }}</p>
          <p v-if="cardPanel === 'cert'" class="mc-why">{{ certWhy }}</p>
          <p v-else class="mc-why">经营等级规则尚未落地（敬请期待）—— 功能开发中。</p>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { http, handleApiError, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getUsername, isLoggedIn } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移（`api-contract.md` 按 `:99` 钉住本文件）
import { myCoupons } from '../api/marketing'; import CouponWalletDialog from '../components/CouponWalletDialog.vue'   // ⚠️ 两条 import **同行**（C3 块 5 · 券的信息架构重排，2026-10-11）—— 同上一行的理由：本文件多处被按行号引，**能省一行就省一行**
import { useRouter } from 'vue-router'; import { merchantMyApplications } from '../api/merchant'   // ⚠️ 两条 import **同行**（C3 块 5 · B 组，2026-10-12）—— 理由同上：`useRouter` 给「店铺信息」格跳 `/shop`用（那条路由属 commit C），`merchantMyApplications` 给「商家认证」格读本人提审历史（`/merchant/myApplications`，`api/merchant.js` 已有封装，**没有新增端点、没有动 `endpoints.js`**）

const userInfo = ref({})
const idType = ref('')
const idValue = ref('')
const fileInput = ref(null)

// ══════════════════════════════════════════════════════════════════════════════
// C3 块 2〈用户块〉新增三处：① 壳判据 `isMerchantShell` ② 降级状态条 `dg`
//                            ③ 资料完善的保存 `saveProfile`（在文件下方）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 「此刻该画商家版还是客户版」—— **判据只有 `role` 一个**。`role` 来自 `/user/me`
 * （后端把 `identity.role()` 直接塞进 payload），**就是路由守卫 `router/index.js` 匹配路由用的那个数**。
 *
 * 三条理由，都是实测出来的、不是口味：
 *  ① 旧判据 `!customerid && merchantid` **已废** —— `UserGovernanceDao.promoteToMerchant` 在入驻
 *     通过时**刻意保留 `customerid`**（老客户号不回收）⇒ 「有 `merchantid`」不再等价于「是商家」，
 *     旧判据会把**商家**判成客户版（左菜单长出客户项的错版）。
 *  ② 改判 `usertype === '商家'` 同样错、而且更坏 —— `MerchantDowngradeService.applyFreeze` 降级冻结时
 *     **刻意不改 `usertype`**（仍是「商家」），只把 `login.Role` 改回 1 ⇒ 会画出**商家版**、连带一条
 *     `/merchant/user/list` 链接，而角色守卫当场把它弹回客户壳首页（**死链**）。`usertype` 还是中文
 *     魔法串，判它会把「降级中的商家」和「真商家」混成一类。
 *  ③ ⇒ 唯一与「用户此刻站在哪个壳里」**同源**的判据就是 `role`。`String(...)` 是必需的：库里
 *     `login.Role` 是 `varchar`、`/user/me` 答的是字符串 `"2"`，而 `Map<String,Object>` 反序列化在
 *     别的路径上给过数字 ⇒ 两侧都规约成字符串再比，**不比类型**。
 *
 * ⚠️ 取不到资料时（`userInfo` 还是空 `{}`）⇒ `role` 为 `undefined` ⇒ 落**客户版**（占比更高的一侧，
 *    与本页 `getUserData()` 两个号都为空时的兜底同向）。
 */
const isMerchantShell = computed(() => String(userInfo.value.role) === '2')

/**
 * 降级状态条（`null` = 不渲染）。读数全部来自 `/user/me` 那 5 列 —— 它们是 `user` 表的整行。
 *
 * `DowngradeState` 三态：`none`（默认）/ `scheduled`（已排定、通知期内**照常经营**）/
 * `frozen`（已降级冻结）。⚠️ 后端 `MerchantDowngradeService` 是**五阶段**：排定 → 通知期 → 冻结 →
 * （30 天窗口内重新申请并再次通过 = `approved-restored` 自动恢复）/ 管理端人工 `relist`。
 * 本页**只显示状态、不给控件** —— 商家侧本块**没有**自助恢复端点，恢复在管理端。
 */
const dg = computed(() => {
  const s = userInfo.value.DowngradeState
  if (s !== 'scheduled' && s !== 'frozen') return null
  return {
    state: s,
    tag: s === 'scheduled' ? '降级已排定' : '账号已降级冻结',
    text: s === 'scheduled'
      ? `生效时刻 ${userInfo.value.ScheduledDowngradeAt || '—'}（通知期内仍可正常经营）`
      : `冻结于 ${userInfo.value.FrozenAt || '—'}，保留至 ${userInfo.value.FreezeUntil || '—'} —— 冻结期内重新提交入驻申请并通过，即自动恢复`,
    reason: userInfo.value.DowngradeReason || '—',
  }
})

onMounted(() => {
  getUserData()
})

const getUserData = async () => {
  if (!isLoggedIn()) return   // P2.6：判据由「有 username」改成「有登录态」（新标签页恢复来的身份也可用）
  // ⚠️ 不再读密码（`getPassword` 已随 P2.6 退场）：本页只作资料展示，不做凭据校验。

  try {
    // P2.6：改走**会话驱动**的 /user/me（无参）；`allowUnauthorized` 让 401 照抛给下面的 catch
    const res = await http.post(endpoints.user.me, null, { allowUnauthorized: true })
    const data = await res.json()
    userInfo.value = data

    // ⚠️ C3 块 2〈用户块〉：判据必须与 `isMerchantShell` **同一个** —— 原来只看号且 `customerid` 优先，
    //    而 `promoteToMerchant` 刻意保留 `customerid` ⇒ 入驻通过的**商家**会被标成「客户 ID XXXX」，
    //    跟他眼前正画着的**商家版**自相矛盾。改判 `role`（= 他在哪个壳）；降级冻结后 `role` 回 1
    //    ⇒ 又变回「客户 ID」，与「商品已冻结、人在客户壳」一致。
    if (String(data.role) === '2') {
      idType.value = '商家 ID'
      idValue.value = data.merchantid || '—'
    } else if (data.customerid) {
      idType.value = '客户 ID'
      idValue.value = data.customerid
    } else if (data.merchantid) {
      idType.value = '商家 ID'
      idValue.value = data.merchantid
    }

    // ⚠️ C3 块 5（2026-10-11）：券包的 `Account` 是**客户号**（`user.customerid`）。
    //    **块 5 B 组（2026-10-12）改了这一行**：券包入口现在**两支壳都有** —— 商家端【无名框】的第 2 格
    //    复用同一颗 `div[role=button]` + 同一个弹窗（用户「两处待定 ①」：券包是「我这个人」的券，不分
    //    商家 / 用户视角）⇒ 原来的 `!isMerchantShell.value` 守卫**已废**，判据只剩「有没有客户号」。
    //    ⚠️ 这条守卫的**现实效果别读错**：VM 库里 14 个商家**全都没有** `customerid`（种子商家直接建号、
    //       没走过客户阶段）⇒ 他们在这一行就返回，券格落「边界 2」的空态；真发出请求的只有
    //       `promoteToMerchant` 那条路来的商家（老客户号被**刻意保留**）。
    //    ⚠️ `isMerchantShell` 此刻已按刚赋的 `userInfo.value` 算好（它就是上面那两处判据的同源量）。
    customerId.value = data.customerid || ''
    if (customerId.value) loadWallet(customerId.value)
    // 「商家认证」那一格要一个**真状态**（不是 `—`）⇒ 与券包同一次资料加载里问一次，且**只在商家壳问**。
    if (isMerchantShell.value) loadCert()
  } catch (e) {
    handleApiError(e, '获取用户信息失败')
  }
}

const chooseImage = () => {
  fileInput.value.click()
}

const doUpload = async (e) => {
  const file = e.target.files[0]
  if (!file) return

  const username = getUsername()
  const formData = new FormData()
  formData.append('username', username)
  formData.append('file', file)

  try {
    const res = await http.post(endpoints.user.updateAvatar, formData)
    const data = await res.json()
    if (data.success) {
      alert('头像更新成功')
      userInfo.value.image = data.imageUrl
    } else {
      alert('失败：' + data.msg)
    }
  } catch (err) {
    alert('上传失败')
  }
}

const saveUserInfo = async () => {
  const username = getUsername()
  try {
    const res = await http.post(endpoints.user.updateUserInfo, new URLSearchParams({
        username: username,
        email: userInfo.value.email || '',
        address: userInfo.value.address || '',
        selfintroduction: userInfo.value.selfintroduction || ''
      }))
    const data = await res.json()
    if (data.success) {
      alert('个人信息保存成功')
      getUserData() // 刷新最新数据
    } else {
      alert('保存失败：' + data.msg)
    }
  } catch (err) {
    alert('保存请求异常')
  }
}

/**
 * 保存「资料完善」（`gender` / `age`）—— ⑦ 那一块。
 *
 * ⚠️ 与上面 `saveUserInfo` 是**两个端点、两次请求**，**不合并**：`/user/updateProfile` 收
 *    `gender` / `age`，`/user/updateUserInfo` 收 `email` / `address` / `selfintroduction`，
 *    字段集不相交；合并了就没法回答「一半失败算不算存下」。
 * ⚠️ 两个空值都**照发**（`''`）—— 后端把「没填」当「没有」⇒ 留空并保存 = **清空该列**。
 *    这里刻意**不走** `api/*.js` 里那个「空值不发」的 `q()`（那是管理端几份副本的口径）：
 *    本页原生的 `saveUserInfo` 也照发空串，两处**同款**，别只为这一处改口径。
 */
const saveProfile = async () => {
  const username = getUsername()
  if (!username) {
    alert('未登录')
    return
  }
  try {
    const res = await http.post(endpoints.user.updateProfile, new URLSearchParams({
      username: username,
      gender: userInfo.value.gender || '',
      age: userInfo.value.age === null || userInfo.value.age === undefined ? '' : String(userInfo.value.age),
    }))
    const data = await res.json()
    if (data.success) {
      alert('资料完善已保存')
      getUserData()
    } else {
      alert('保存失败：' + data.msg)
    }
  } catch (err) {
    alert('保存请求异常')
  }
}

// ── 券包入口（C3 块 5 · 券的信息架构重排，2026-10-11）───────────────────────────────
// 用户定「**主页不显示券包**」⇒ 本页「优惠券」那一格是「我的券包」的**唯一入口**。
// 本段只管三件事：有几张 / 有没有快过期 / 点开弹窗；券的明细全在
// `components/CouponWalletDialog.vue` 里（它自己拉数据）。

/** 客户号（`user.customerid`）—— 券包 `Account` 与弹窗 `:account` 都用它。 */
const customerId = ref('')

/** 弹窗开关。 */
const walletOpen = ref(false)

/** `null` = 还没取到（渲染「—」）；数字 = **未使用**的张数（`expiring` ∪ `usable`）。 */
const walletCount = ref(null)

/** 有快过期的券 ⇒ 入口那枚红点（用户裁定 #4 ①）。 */
const walletRedDot = ref(false)

/** 未使用 / 总张数 —— 只用于 `title`（点开前先说清楚那个数字是什么）。 */
const walletUseful = ref(0)
const walletTotal = ref(0)

const walletTitle = computed(() =>
  walletCount.value === null
    ? '我的券包 —— 暂时取不到券数，点开仍可查看'
    : `我的券包：未使用 ${walletUseful.value} 张 / 共 ${walletTotal.value} 张（含已使用 / 已过期）—— 点开查看`
)

const openWallet = () => { walletOpen.value = true }

/**
 * 取券包的「摘要」（未使用张数 + 红点）。
 * ⚠️ 只拉 `myCoupons` 一次，**不**在这里拉明细：明细归弹窗（它开窗那一刻再拉一次，
 *    因为本函数跑在页面加载时，而弹窗要的是**点开时**的）。
 * ⚠️ 取不到就回 `null`（渲染「—」）而**不是** `0` —— `0` 会被读成「你真的一张券都没有」。
 */
const loadWallet = async (account) => {
  const acct = account || customerId.value
  if (!acct) { walletCount.value = null; walletRedDot.value = false; return }
  try {
    const data = await myCoupons(acct)
    if (!data || !data.success) { walletCount.value = null; walletRedDot.value = false; return }
    const list = Array.isArray(data.list) ? data.list : []
    walletUseful.value = list.filter((r) => r.Bucket === 'expiring' || r.Bucket === 'usable').length
    walletTotal.value = list.length
    walletCount.value = walletUseful.value
    // ⚠️ 红点用**后端**给的 `redDot`（它按 `CouponDao.EXPIRING_THRESHOLD` 算），不在这里按 `Bucket` 再算一遍 ——
    //    两处判据必须是**同一个表达式**，否则「快过期的标」与「红点」会打架（见那个常量的 ⚠️）。
    walletRedDot.value = !!data.redDot || data.redDot === 1 || data.redDot === '1'
  } catch (e) {
    walletCount.value = null
    walletRedDot.value = false
    handleApiError(e, '券包加载失败')
  }
}

// ── 商家端【无名框】的「店铺信息 / 商家认证 / 经营等级」三格（C3 块 5 · B 组，2026-10-12）────────
// 三格各自一行逻辑，够短，**不另建组件**：两枚弹窗共用上面模板里那一枚遮罩（`cardPanel` 决定画哪份）。

/** 路由 —— 「店铺信息」格跳 `/shop`。⚠️ 那条路由属 **commit C**（本 commit 里点它只有控制台告警）。 */
const router = useRouter()

/** 遮罩开着时画哪一份面板：`null` 关 / `'cert'` 商家认证 / `'level'` 经营等级。 */
const cardPanel = ref(null)

/**
 * 「商家认证」格的归一化状态 —— 五值：`''`（还没问）/ `'none'`（没提交过）/ `'pending'` /
 * `'approved'` / `'rejected'` / `'error'`（取不到）。
 * ⚠️ 前四个直接是 `merchant_application.Status` 的取值（`MerchantApplicationDao`），**不在这里另造一套词**
 *    —— 只在渲染时映射成给用户看的字（`certLabel`），两个地方各留一份映射才是漂移源。
 */
const certState = ref('')

/** 最新一条申请（`SubmittedAt` 倒序取 `[0]`）；`null` = 没有记录。弹窗里要拿它的提交时刻与驳回理由。 */
const certLast = ref(null)

/** 取认证状态失败的原因（只在弹窗正文里说，**不弹 `alert`** —— 这里是展示格，不是一次用户发起的动作）。 */
const certWhyErr = ref('')

/** 状态 → 大字格里那行短文案（与 `.assets .v` 同款位）。 */
const certLabel = computed(() => {
  const map = { approved: '已认证', pending: '审核中', rejected: '已驳回', none: '未提交' }
  return map[certState.value] || '—'
})

/** 大字格的 `title`：点开前先把状态说清楚（与券包那格的 `walletTitle` 同一种做法）。 */
const certTitle = computed(() => {
  if (certState.value === 'error') return `商家认证 —— ${certWhyErr.value || '取不到提审记录'}`
  if (certState.value === '') return '商家认证 —— 正在读取提审记录'
  return `商家认证：${certLabel.value} —— 点开看详情（状态读自 /merchant/myApplications 最新一条）`
})

/**
 * 弹窗正文：状态 + 依据（哪一次提交 / 驳回理由 / 为什么取不到）。
 * ⚠️ 正文是**给商家看的**，所以这里不写端点名（`/merchant/myApplications` 只出现在 `certTitle` 与
 *    代码注释里）—— 「这一格的数据从哪来」是**归属问题**，该写在 `title` 里，不该念给用户听。
 */
const certWhy = computed(() => {
  const at = certLast.value && certLast.value.SubmittedAt ? `（提交于 ${certLast.value.SubmittedAt}）` : ''
  switch (certState.value) {
    case 'approved':
      return `管理端已通过你最近一次入驻申请${at}。`
    case 'pending':
      return `你最近一次入驻申请${at}正在审核中；通过后本格会变成「已认证」。`
    case 'rejected': {
      const r = certLast.value && certLast.value.RejectReason ? `驳回理由：${certLast.value.RejectReason}。` : '（这一条没有记驳回理由。）'
      return `你最近一次入驻申请${at}被驳回。${r}可以改好资料后重新提交。`
    }
    case 'none':
      return '没有查到你的入驻申请记录 —— 这一格读的是**你本人**提交过的入驻申请（提审历史）。'
    case 'error':
      return `取认证状态失败：${certWhyErr.value || '未知原因'}（不影响本页其它部分）。`
    default:
      return '正在读取提审记录……'
  }
})

/**
 * 读「商家认证」状态 —— `POST /merchant/myApplications`（`api/merchant.js` 已有封装，**零契约改动**）。
 * ⚠️ 只在**商家壳**调（`getUserData()` 里那一行守卫）：客户壳根本没有这一格，问了也没处放。
 * ⚠️ 失败**不抛**给页面（不 `alert`）：认证状态是**一格展示**，取不到就画 `—` + 在弹窗里说明原因 ——
 *    与券包那格「取不到就 `null`（画 `—`）而不是 `0`」同一种取舍（`loadWallet` 的注释）。
 */
const loadCert = async () => {
  const username = getUsername()
  if (!username) {
    certState.value = 'error'
    certWhyErr.value = '没有登录态，取不到提审记录'
    return
  }
  try {
    const data = await merchantMyApplications({ username, limit: 1 })
    if (!data || !data.success) {
      certState.value = 'error'
      certWhyErr.value = (data && data.msg) || '接口返回失败'
      return
    }
    const list = Array.isArray(data.list) ? data.list : []
    const last = list[0] || null
    certLast.value = last
    certState.value = last ? String(last.Status || 'none') : 'none'
  } catch (e) {
    certState.value = 'error'
    certWhyErr.value = '请求异常'
    handleApiError(e, '取商家认证状态失败')
  }
}

/** 「店铺信息」格：跳自己的店铺主页。⚠️ `/shop` 那条路由由 **commit C** 建（本 commit 里跳不到）。 */
const enterMyShop = () => {
  const mid = userInfo.value.merchantid
  if (!mid) { alert('取不到你的商家号，进不了店铺主页'); return }
  router.push({ path: '/shop', query: { MerchantId: mid } })
}

/** 「商家认证」格：开弹窗（状态已在 `getUserData()` 那次问过）。 */
const openCert = () => { cardPanel.value = 'cert' }

/** 「经营等级」格：开弹窗。规则未定（用户 2026-10-12 裁定 ④「先占位，不做」）⇒ 面板是固定文案。 */
const openLevel = () => { cardPanel.value = 'level' }
</script>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════════════
   取自 `pages/UserCenter-customer.html:87-261` 与 `pages/UserCenter-merchant.html:85-247`
   的**同一段 CSS**（两稿除注释外逐字相同；唯一实质差异是商家版 `.btn-plain` 用
   `color:var(--ink)` 而非 `--ink-2` ⇒ 见下第 ⑪ 组，本页取商家版那一支）。

   本次「从稿到 Vue」的**四处适配**，逐条登记：
     ① 稿的 `:root{--d2: calc(var(--fs-2xl) * 1.00)}` → 落到本组件根 `.uc-main`（scoped 场景
        没有 `:root` 的等价物；`calc(x * 1.00)` ≡ `x`，故直接写 `var(--fs-2xl)`）。
     ② 稿的全局重设 `body{}` / `a{}` / `button{}` / `input,textarea{}`（稿 `:118-122` / `:114-118`）
        → 本页只做后两项、且**限定在 `.uc-main` 之内**（scoped 块里写裸 `button{}` 会波及全组件，
        虽然本组件只有这些按钮，仍明确收窄）。`a{text-decoration:none}` 只对**一条**
        `<router-link>` 起作用 ⇒ 直接写进 `.sec-h .more`（`tokens.css` 与 `App.vue` 都没有
        `a` 重设，实测确认）。
     ③ 稿的 `.svc a` → `.svc button`：稿画的是无 `href` 的 `<a title="无端点（N=4）">`，
        Vue 落成 `<button disabled>`（不画死控件）⇒ 选择器随之改，并**补**
        `.svc button[disabled]{cursor:not-allowed}`（稿的全局 `button{cursor:pointer}` 不带
        `:not([disabled])`，照抄会把禁用项画成可点）。
     ④ 稿里 `.rail` 的两个容器 `.uc` / `.uc-menu` **不进本组件**（它们的落点是 `UserCenter.vue`
        与 `AppShell.vue:440-444`）⇒ 未搬。
   ⚠️ `box-sizing` **不在此重复** —— `tokens.css:52-56` 的 `*` 重设已是全局（`:55`）。
   ══════════════════════════════════════════════════════════════════════════════ */

/* ── 稿的两条**全局**重设搬进 scoped（只作用本组件的元素）────────────────────
   ⚠️ 稿 `:121` 的 `button{font:inherit;color:inherit;background:none;border:0;cursor:pointer}`
      **不可省**：本页把稿里 6 枚 `<a>`（`.svc a` ×4）与 3 枚 `.more` 落成 `<button>`
      ⇒ 不重设就会带上 UA 的 `background: rgb(240,240,240)` / `border: 2px outset` /
      `font-family: Arial` / `font-size: 13.33px`。**这是本笔第一版真踩到的坑**
      （`cmp7.mjs` 量出来的：`.svc > *` 在稿里 `backgroundColor: rgba(0,0,0,0)`、
      Vue 里 `rgba(239,239,239,0.3)` + `borderTopStyle: outset`），登记进 commit message。
   ⚠️ 稿 `:122` 的 `input,textarea{font:inherit;color:inherit}` 同理（否则输入框走 UA 的
      13.33px 系统字体，而不是 `--font` / `--fs-sm`）。 */
button,
input,
textarea {
  font: inherit;
  color: inherit;
}
button {
  background: none;
  border: 0;
  cursor: pointer;
}

/* ── 本页根：稿 `:118-119` 的 `body{font-family:var(--font);font-size:var(--fs-sm);
   color:var(--ink);background:var(--bg);-webkit-font-smoothing:antialiased}`
   落到根上（`background` 不搬 —— 壳 `.app-shell:188-194` 已经铺了 `var(--bg)`，
   在 `main.uc-main` 上再铺一次会把右栏画成一块与左菜单不同的底）。
   `flex:1` + `min-width:0` —— 本组件是 `AppShell.vue` 的 `.user-page` 那个 flex 行的直接子项。
   ⚠️ **本仓没有稿那条全局 `body{}`**（`AppShell.vue:287-290` 明写：全仓一条字体声明都没有、
     壳只声明在「顶栏 / 左菜单」三件上，**页面主体留给 D 笔**；`HomePage.vue:40-49` 是同一套
     落法的先例）⇒ 这三条必须在每个页面根上各自声明，否则正文走**浏览器默认 16px + 本机
     解析到的 `"Noto Sans SC"`**（本机实测），而稿是 `--fs-sm`（15px）+ `var(--font)`。
   ⚠️ 稿的 `body` **没有** `line-height`（与 `主页改造.html` 的 `line-height:1.6` 不同）
     ⇒ 本页也不加，保持稿的 `normal`。 */
.uc-main {
  --d2: var(--fs-2xl);
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头 ─────────────────────────────────────────────────────────────────── */
.page-h { font-family: var(--font-display); font-size: var(--d2); font-weight: var(--fw-med);
          line-height: 1.15; letter-spacing: -.02em; }
.page-h-sub { margin-top: var(--s3); font-size: var(--fs-sm); color: var(--ink-3); }

/* ── ① 身份卡（主视觉）：--glaze-deep 整块釉面 ────────────────────────────── */
.ident { margin-top: var(--s6); display: flex; align-items: center; gap: var(--s6);
         padding: var(--s7) var(--s6); background: var(--glaze-deep); border-radius: var(--r3); }
/* ⚠️ 稿 `:164-166` 的 `display:flex` / `align-items` / `justify-content` / `color` / `font-*`
   那一组是给**字母圆牌**（`<div>声</div>`）用的，对 `<img>` 不起作用 ⇒ 未落；
   补 `object-fit:cover`（原实现既有）与 `display:block`（免掉行内元素基线缝隙）。 */
.avatar { width: 88px; height: 88px; flex: none; display: block;
          border-radius: 50%; background: var(--brand-tint); object-fit: cover; }
.ident-who { min-width: 0; }
.ident-name { font-family: var(--font-display); font-size: var(--d2); font-weight: var(--fw-med);
              line-height: 1.12; letter-spacing: -.03em; }
.ident-id { margin-top: var(--s2); font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-2); }
.ident-meta { margin-left: auto; display: flex; gap: var(--s7); }
.ident-meta .k { font-size: var(--fs-xs); color: var(--ink-2); }
.ident-meta .v { margin-top: var(--s2); font-size: var(--fs-md); color: var(--ink); }
.ident-btn { margin-left: var(--s6); }
.file-hidden { display: none; }

/* ── ② 我的资产 / 经营数据：**不画线**，靠间距分隔；数字用色（--brand-ink 大衬线）─── */
.assets { margin-top: calc(var(--s8) * 1.2); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s6); }
.assets .v { font-family: var(--font-display); font-size: var(--d2); font-weight: var(--fw-med);
             line-height: 1; letter-spacing: -.02em; color: var(--brand-ink);
             font-variant-numeric: tabular-nums; }
.assets .k { margin-top: var(--s3); font-size: var(--fs-sm); color: var(--ink-2); }
/* ⚠️ C3 块 5：「优惠券」那格从裸 `div` 变成**券包入口**（`div[role=button]`）——
   这里只补「可点」的三个视觉线索（光标 / hover / 键盘焦点环），**不改尺寸**：
   它得与另外三格在同一行上逐像素对齐（就是 `.assets` 的 grid 项，宽高本来就由网格定）。 */
.asset-entry { cursor: pointer; border-radius: var(--r1); }
.asset-entry:hover .k,
.asset-entry:focus-visible .k { color: var(--brand-ink); }
.asset-entry:focus-visible { outline: 2px solid var(--brand); outline-offset: var(--s2); }
/* 快过期红点 —— 挂在数字右侧（`.v` 是 `line-height: 1` 的大字，红点跟着基线走，不撑行高）。 */
.wallet-dot { font-style: normal; font-size: var(--fs-sm); color: var(--danger); margin-left: var(--s1); }
/* ⚠️ 稿 `:181-182` / `:174-175` 的 `.assets .v small` **本页不落** —— 4 个值全落 `—`，
   后面再挂「件 / 单 / 条 / 张 / ¥」是自相矛盾（见 §一 commit 7 的有意偏离第 2 条）。 */

/* ── ③ 我的订单 / 待处理订单：四格**浅底色块**（格间留缝、无边框）──────────── */
.tiles { margin-top: var(--s4); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s3); }
.tiles button { display: block; width: 100%; text-align: left; padding: var(--s4);
                background: var(--brand-tint); border-radius: var(--r2); transition: background .15s; }
.tiles button:hover:not([disabled]) { background: var(--brand-soft); }
.tiles b { display: block; font-size: var(--fs-md); font-weight: var(--fw-med); color: var(--brand-ink); }
.tiles span { display: block; margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-2); }
.tiles button[disabled] { cursor: not-allowed; }

/* ── 通用区块 ─────────────────────────────────────────────────────────────── */
.sec { margin-top: calc(var(--s8) * 1.4); }
.sec-h { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: var(--s4); }
/* ⚠️ 稿 `:360`（客户版「浏览足迹」那行）用的是**行内** `style="margin-top:var(--s6)"`
   ⇒ 本页改成一个类（同上，scoped 场景不写行内样式），值逐字相同。 */
.sec-h-2nd { margin-top: var(--s6); }
.sec-h h2 { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med); }
/* ⚠️ `a{text-decoration:none}` 由稿的**全局**重设提供（稿 `:120`）；`tokens.css` 与 `App.vue`
   都没有这条，而本页右上角那枚「查看全部」是 `<router-link>`（渲染成 `<a>`）⇒ 补在此处。 */
.sec-h .more { font-size: var(--fs-xs); color: var(--ink-3); text-decoration: none; }
.sec-h .more:hover { color: var(--brand-ink); }
.sec-h .more[disabled] { cursor: not-allowed; }
/* ⚠️ 商家版「去订单中心」是禁用的、「查看全部」是真链接 —— 两者默认色都是 --ink-3，
   肉眼分不出哪个能按（稿 `:194-196` 的自注）⇒ 能按的用 --brand-ink，禁用的留在 --ink-3。 */
.sec-h .more:not([disabled]) { color: var(--brand-ink); }
.sec-h .more:not([disabled]):hover { text-decoration: underline; }

/* ── ④ 收藏 / 足迹 / 最近上架：卡片（无边框，hover 给 --surface-2）─────────── */
.rail { display: flex; gap: var(--s4); overflow-x: auto; padding-bottom: var(--s3); }
.pcard { flex: none; width: 158px; padding: var(--s3); border-radius: var(--r2); transition: background .15s; }
.pcard:hover { background: var(--surface-2); }
.pthumb { height: 132px; border-radius: var(--r1); background: var(--img); }
.pname { margin-top: var(--s3); min-height: 3em; font-size: var(--fs-sm); line-height: 1.5; color: var(--ink-2);
         display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pprice { margin-top: var(--s2); color: var(--accent); font-weight: var(--fw-semi);
          font-variant-numeric: tabular-nums; }
.rail-sm .pcard { width: 128px; }
.rail-sm .pthumb { height: 104px; }

/* ── ⑤ 服务：四列文字，无框无线（间距分隔）───────────────────────────────── */
.svc { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s6); }
.svc button { display: block; width: 100%; text-align: left; }
.svc b { display: block; font-size: var(--fs-sm); font-weight: var(--fw-med); color: var(--ink); }
.svc button:hover:not([disabled]) b { color: var(--brand-ink); }
.svc span { display: block; margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); line-height: 1.6; }
.svc button[disabled] { cursor: not-allowed; }

/* ── ⑥ 账号设置：**线**（全页只有这一块用线）─────────────────────────────── */
.form2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s5); }
.f { margin-top: var(--s5); }
.f > label { display: block; margin-bottom: var(--s2); font-size: var(--fs-xs); color: var(--ink-2); }
.f input, .f textarea, .f select { width: 100%; padding: 0 var(--s3); border: 1px solid var(--line-2); border-radius: var(--r2);
                        background: var(--surface); transition: border-color .15s, box-shadow .15s; }
.f input { height: 46px; } .f select { height: 46px; padding: 0 var(--s2); cursor: pointer; }
.f textarea { min-height: 96px; padding: var(--s3); resize: vertical; line-height: 1.7; }
.f input:focus, .f textarea:focus, .f select:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
.actions { margin-top: var(--s6); }

/* ── 按钮（稿的全局 `button{font:inherit;color:inherit;background:none;border:0}` 的收窄版）──
   ⚠️ 收在 `.uc-main` 之内，理由见文件头「适配 ②」。 */
.btn { display: inline-flex; align-items: center; justify-content: center; gap: var(--s2); height: 44px;
       padding: 0 var(--s6); border-radius: var(--r2); font-weight: var(--fw-med); }
/* ⚠️ 两稿在这里**唯一**的实质差异：客户版 `color:var(--ink-2)`，商家版 `color:var(--ink)`
   （商家稿 `:229`；客户稿 `:243`）。本页取**商家版那一支** —— 身份卡是同一块 --glaze-deep 釉面，
   --ink-2 描边按钮压在上面时对比度只有 1.4:1（客户稿 `:429-432` 的 Q3 自查亲口记了这条）
   ⇒ 取能读得清的那一支。⚠️ 登记为**跨两稿的唯一取一舍一**，见 §一 commit 7。 */
.btn-plain { border: 1px solid var(--line-2); color: var(--ink); height: 38px; padding: 0 var(--s5); font-size: var(--fs-xs); }
.btn-plain:hover { border-color: var(--brand); color: var(--brand-ink); }
.btn-primary { background: var(--brand); color: var(--bg); }
.btn-primary:hover { background: var(--brand-hover); }

/* ── ⑦ 资料完善 + 降级状态条（C3 块 2〈用户块〉新增）───────────────────────────
   ⚠️ ⑦ **复用 ⑥ 那一套**（`.form2` / `.f` / `.actions`）—— 这不是「省事」：两块都是
      「栅格 + 标签 + 控件 + 保存」的同一形态，另起一套只会让窄屏那条 `@media` 里
      `.form2{grid-template-columns:1fr}` 少覆盖一块。`.f select` 的样式已折进上面 ⑥ 的三条规则
      （规则选择器里加一个 `.f select`），**不另起**。
   ⚠️ 色一律走**语义族**（`--info-*` / `--warning-*`，tokens.css `:141` / `:139`，两套主题各有一份）
      —— 不碰 `--c1`-`--c10`：那是图表色板，且 `probe-c3-admin-preview.mjs` 按像素量过 `--c6`。 */
.dp-hint { margin-left: var(--s4); font-size: var(--fs-xs); color: var(--ink-3); }

/* 降级状态条 —— 挂在 ① 身份卡与两支模板之间（模板里那条「同行追加」的 `<p>`）。
   ⚠️ `scheduled` 用 `--info-*`（通知期内**照常经营**，是提示不是警告）、`frozen` 用 `--warning-*`
      （商品已冻结，是既成事实）—— 两态**只用底色区分**，文字在 `.dg-tag` 里另有明说
      （色不能是唯一判据，色盲用户拿不到「已排定 / 已冻结」的区别）。 */
.dg-note { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--s2) var(--s3);
           margin-top: var(--s5); padding: var(--s4) var(--s5); border-left: 3px solid var(--info);
           border-radius: var(--r2); background: var(--info-ground); color: var(--info-deep);
           font-size: var(--fs-sm); line-height: 1.7; }
.dg-note.frozen { border-left-color: var(--warning); background: var(--warning-ground); color: var(--warning-deep); }
.dg-note .dg-tag { flex: none; font-weight: var(--fw-med); }
.dg-note .dg-why { color: inherit; opacity: .75; }

@media (max-width: 1120px) {
  /* ⚠️ 窄屏顺序：DOM 是 头像 → 名字 → 三项 → 按钮。直接 flex-wrap 会让按钮独占第三行、靠左，
     卡片右下角空一大块（稿 `:251-253` 自注「1080 截图里实测到」）。
     ⇒ 用 order 把按钮提到第一行末尾（margin-left:auto 靠右），三项独占第二行铺满。 */
  .ident { flex-wrap: wrap; gap: var(--s5); }
  .ident-btn { order: 2; margin-left: auto; }
  .ident-meta { order: 3; margin-left: 0; width: 100%; gap: var(--s6); }
  .assets, .tiles { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .svc { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .form2 { grid-template-columns: 1fr; }
}

/* ══════════════════════════════════════════════════════════════════════════════
   块 5 · B 组（2026-10-12）新增两组样式 —— 均**追加在文件尾**（本页多处被按行号引，
   上面的行一律不动；这也是本仓对 `router/index.js` / `endpoints.js` 的同一手法）。
   ══════════════════════════════════════════════════════════════════════════════ */

/* ── 一、商家端【无名框】7 项的**滚动容器 + 下边缘渐隐** ──────────────────────────
   用户定稿的布局规则：「容器级纵向滚动（乙）· 隐藏原生滚动条 · 下边缘渐隐（与领券中心统一）」。
   **只挂商家那一份** `.assets`（7 项放不下）；用户端 4 项一行放得下 ⇒ 不加此类、不滚不渐隐。
   判据是「项数放不放得下」，不是「谁是商家」—— 换言之这不是角色样式，是**溢出样式**。
   ⚠️ 类**加在 `.assets` 同一个元素上**（`class="assets assets-scroll"`），**不是**外面再套一层 wrapper：
      套一层会让 `.assets` 那条 `margin-top: calc(var(--s8) * 1.2)`（76.8px）落进滚动内容里
      —— 首屏先把 76.8px 空白滚出去，第一行直接被顶出可视区。同类元素两份类 ⇒ 网格与间距全部沿用既有值。
   ⚠️ 高度**用既有 token 算**（`--assets-row` 三项与 `.assets .v` / `.k` 的取值同源：大字 --d2
      + `.k` 的上边距 --s3 + 一行字 ≈ `--fs-sm × 1.4`），**不拍一个裸 px**：
      「≈ 一行」的判据是**它不随项数长高**（7 项和 70 项都一样高），不是像素级等于一行。
      再留 `--s6 + --s5`（一个行间距 + 一段）⇒ 露出第 2 行顶部**约三分之一行高**：渐隐要有东西可渐，
      否则渐的是空白、等于没做；三分之一这一档也是「与领券中心统一」那一句的落点
      （`ProductPage.vue` 的领券行就是「一张 + 半张」）。⇒ 这是本节唯一一处**刻意不是一行整**的地方，
      两者相权：**滚动条已隐藏**，若第 2 行一点不露，页面上就没有任何「还能往下滚」的线索。
   ⚠️ 隐藏滚动条两条与 `ProductPage.vue:1330-1331` 同款；渐隐 78% 停点与领券中心
      （`ProductPage.vue:1032` `.coupons-cut`）逐字相同，`mask-image` / `-webkit-mask-image` **两条都写**。 */
.assets-scroll {
  --assets-row: calc(var(--d2) + var(--s3) + var(--fs-sm) * 1.4);
  max-height: calc(var(--assets-row) + var(--s6) + var(--s5));
  overflow-y: auto;
  scrollbar-width: none;
  mask-image: linear-gradient(to bottom, #000 78%, transparent);
  -webkit-mask-image: linear-gradient(to bottom, #000 78%, transparent);
}
.assets-scroll::-webkit-scrollbar { display: none; }

/* ── 二、商家端两枚弹窗（商家认证 / 经营等级）的遮罩 + 面板 ────────────────────────
   值逐条抄 `components/CouponWalletDialog.vue` 的 `.cscrim` / `.cdlg`：z-index **300 档**
   （「弹窗叠弹窗」档，出处 `views/TemplatePicker.vue:105`，别顺手降到 100/200 的顺眼值）、
   遮罩 `rgba(0,0,0,.42)`、面板 `min(560px, 100%)` / `--r2` / `--line-2` 边 /
   `0 12px 48px rgba(0,0,0,.22)` 阴影 —— 同一页里两枚遮罩必须像一套。
   `.mc-*` 前缀避开它那套 `c-`/`w-`（那是子组件自己的 scoped 块，两边互不覆盖）。
   ⚠️ 只用 `--fs-xl`（32px）而不是 `.assets .v` 的 `--d2`（36px）：弹窗正文里 36px 会把
      「已认证」顶成标题、把下面的说明挤掉一行。 */
.mc-scrim {
  position: fixed;
  inset: 0;
  z-index: 300;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s5);
}
.mc-dlg {
  width: min(560px, 100%);
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.22);
}
.mc-dlg-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--line);
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
}
/* 关按钮：位置 / 字号 / 色与 `.cdlg-h .cx` 同款（`button` 的 `background`/`border` 已由上面那两条全局重设清掉）。 */
.mc-x { font-size: var(--fs-lg); line-height: 1; color: var(--ink-3); }
.mc-dlg-b { padding: var(--s5); }
.mc-state { font-family: var(--font-display); font-size: var(--fs-xl); font-weight: var(--fw-med);
            line-height: 1.1; color: var(--brand-ink); }
.mc-why { margin-top: var(--s4); font-size: var(--fs-sm); color: var(--ink-2); line-height: 1.7; }
</style>
