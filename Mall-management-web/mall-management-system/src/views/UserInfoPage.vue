<template>
  <!-- A 版 `pageProfile` 一比一重写（2026-10-19）：
       · 无左身份树（路由 `noRail`）、无横向菜单（路由 `noMenu`），整页 1360 居中。
       · 结构逐段对应 `a.html:1828-2038`：页头 / 品牌加盟（商家）· 入驻横幅（买家）/
         双身份桥 / 四格资产入口 / 两栏（黑金牌匾 · 今日工作台 · 成长路径 ｜ 账户资产 ·
         品牌资质 · 积分动态 · 最近订单）/ 四格快捷入口 / 页脚。
       · 旧版真功能一个不丢：头像更换、账号设置、资料完善、券包、商家认证 / 经营等级、
         店铺信息、降级状态条、两枚弹窗全部保留（账号类表单收进「编辑资料」弹窗，
         以免在 A 版骨架上多出 A 没有的页面模块）。
       · 无端点可读的值一律 `—`，不编数。 -->
  <main class="uc-main">

    <section class="page-head">
      <div>
        <div class="kicker">REIMAGINE / LAYOUT · IDENTITY OVERVIEW</div>
        <h1>个人中心</h1>
      </div>
      <div class="head-actions">
        <router-link class="btn solid" :to="cartPath">打开购物车 <span class="mono">{{ cartCount === null ? '—' : pad2(cartCount) }}</span></router-link>
        <router-link v-if="isMerchantShell" class="btn" to="/merchant/after-sales">售后工作台</router-link>
        <router-link v-else class="btn" :to="applyPath">商家入驻申请</router-link>
        <button class="btn" @click="editOpen = true">编辑资料</button>
      </div>
    </section>

    <!-- 身份卡（2026-10-19 用户令：头像 / 用户名 / 会员等级 / 资料 / 退出登录必须可见）。
         数据全部来自 `/user/me`（`userInfo`），取不到的字段按本仓口径显示 `—`，不编数。 -->
    <section class="id-card" aria-label="账户身份">
      <img class="id-avatar" :src="userInfo.image || assetOf('/img/default_user.png')" alt="头像" />
      <div class="id-main">
        <div class="id-name">
          {{ userInfo.username || '未知用户' }}
          <span class="id-badge" :class="'lv-' + memberTheme" :title="plaqueLevelName">{{ memberLevelLabel }}</span>
          <span v-if="isMerchantShell" class="id-badge merchant">商家</span>
        </div>
        <div class="id-meta">
          <span v-if="userInfo.customerid">客户号 <b>{{ userInfo.customerid }}</b></span>
          <span v-if="userInfo.merchantid">商家号 <b>{{ userInfo.merchantid }}</b></span>
          <span>性别 <b>{{ userInfo.gender || '未填写' }}</b></span>
          <span>年龄 <b>{{ userInfo.age === null || userInfo.age === undefined || userInfo.age === '' ? '—' : userInfo.age }}</b></span>
          <span>邮箱 <b>{{ userInfo.email || '未填写' }}</b></span>
          <span>地址 <b>{{ userInfo.address || '未填写' }}</b></span>
          <span>简介 <b>{{ userInfo.selfintroduction || '未填写' }}</b></span>
        </div>
        <p class="id-bio">{{ userInfo.selfintroduction || '暂无自我介绍' }}</p>
      </div>
      <div class="id-actions">
        <button class="btn ghost" type="button" @click="toLogin">退出登录</button>
      </div>
    </section>

    <!-- 降级状态条（C3 块 2）：A 版没有对应模块，但它只在一类异常态出现，保留原语义。 -->
    <p v-if="dg" class="dg-note" :class="dg.state"><b class="dg-tag">{{ dg.tag }}</b>{{ dg.text }}<span class="dg-why">原因：{{ dg.reason }}</span></p>

    <!-- 商家：品牌加盟入口（A `brand-cta`） -->
    <section v-if="isMerchantShell" class="brand-cta">
      <div>
        <div class="kicker">BRAND FRANCHISE / 品牌加盟入口</div>
        <h2>申请品牌加盟</h2>
        <p>品牌是商家之上的进一步认证。加盟通过后，商品进入品牌专区、商品卡带品牌标，并可参加品牌专属活动。这是商家工作台首页的固定入口，不需要翻资质页。</p>
      </div>
      <div class="cta-side">
        <router-link class="btn" :to="{ path: applyPath, query: { type: 'brand' } }">申请品牌加盟</router-link>
        <router-link class="btn" :to="{ path: applyPath, query: { type: 'brand', drawer: 'brand-help' } }">先看加盟说明</router-link>
        <small>当前状态：{{ brandLabel }}<br>审核 1–3 个工作日 · 不影响商家身份</small>
      </div>
    </section>

    <!-- 买家：入驻升级横幅（A `join-banner`） -->
    <section v-else class="join-banner">
      <div>
        <div class="kicker">MERCHANT ONBOARDING / 升级入口</div>
        <h2>想卖东西？<br>先把买家身份升一级。</h2>
        <p>你当前是普通买家。点击右侧填写入驻表单，提交后由平台审核；通过后身份升级为商家，此后顶部、侧边栏与个人中心才会出现商家工作台入口。</p>
      </div>
      <div class="jb-actions">
        <router-link class="btn solid" :to="applyPath">填写入驻申请</router-link>
        <router-link class="btn" :to="historyPath">查看历史购买记录</router-link>
        <span class="jb-meta">BUYER → MERCHANT</span>
      </div>
    </section>

    <!-- 双身份桥（A `bridge`） -->
    <section class="bridge" aria-label="双身份切换">
      <router-link class="bridge-side" :class="{ active: !isMerchantShell }" :to="selfPath">
        <span class="b-kicker">BUYER / 基础身份 · 常驻</span>
        <strong>买家</strong>
        <dl>
          <div><dt>购物车</dt><dd>{{ cartCount === null ? '—' : `${cartCount} 件` }}</dd></div>
          <div><dt>待收货</dt><dd>—</dd></div>
          <div><dt>可用积分</dt><dd>—</dd></div>
          <div><dt>优惠券</dt><dd>{{ walletCount === null ? '—' : `${walletCount} 张` }}</dd></div>
        </dl>
        <span class="b-go">进入买家中心 →</span>
      </router-link>

      <div class="bridge-core">
        <span class="kicker">SAME ACCOUNT</span>
        <div class="bridge-spine"><i></i><i></i><i></i></div>
        <p>同一账户<br>同一资产<br>{{ isMerchantShell ? '即时切换' : '升级后保留' }}</p>
        <button v-if="isMerchantShell" type="button" class="btn sm solid" @click="scrollToWorkbench">进入商家工作台</button>
        <router-link v-else class="btn sm solid" :to="applyPath">申请入驻</router-link>
      </div>

      <router-link
        class="bridge-side"
        :class="isMerchantShell ? { active: true } : { locked: true }"
        :to="isMerchantShell ? selfPath : applyPath"
      >
        <span class="b-kicker">MERCHANT / 升级身份 · {{ isMerchantShell ? '已开通' : '未开通' }}</span>
        <strong>商家</strong>
        <dl v-if="isMerchantShell">
          <div><dt>在售商品</dt><dd>—</dd></div>
          <div><dt>待发货</dt><dd>—</dd></div>
          <div><dt>售后待办</dt><dd>—</dd></div>
          <div><dt>本月成交</dt><dd>—</dd></div>
        </dl>
        <dl v-else>
          <div><dt>入驻状态</dt><dd>{{ certLabel === '—' ? '未申请' : certLabel }}</dd></div>
          <div><dt>店铺名称</dt><dd>待填写</dd></div>
          <div><dt>审核方式</dt><dd>平台审核</dd></div>
          <div><dt>升级成本</dt><dd>0</dd></div>
        </dl>
        <span class="b-go">{{ isMerchantShell ? '进入商家工作台 →' : '填写入驻申请 →' }}</span>
      </router-link>
    </section>

    <!-- 买家资产入口（A `entry-grid`） -->
    <div class="entry-grid section-gap" aria-label="买家资产入口">
      <router-link class="entry-cell entry-link" :to="favoritesPath" title="打开我的收藏（真端点 /favorite/list）">
        <span class="ek">01 / FAVORITES</span><b>我的收藏</b><span class="ev">{{ favCount === null ? '—' : favCount }}</span>
        <small>看中的器物，先放进收藏夹</small><span class="ego">查看收藏 →</span>
      </router-link>
      <div
        class="entry-cell entry-link"
        role="button"
        tabindex="0"
        :title="walletTitle"
        @click="openWallet"
        @keydown.enter.prevent="openWallet"
        @keydown.space.prevent="openWallet"
      >
        <span class="ek">02 / COUPONS</span><b>我的优惠券</b>
        <span class="ev">{{ walletCount === null ? '—' : walletCount }}<i v-if="walletRedDot" class="wallet-dot" title="有券快过期">●</i></span>
        <small>未使用 {{ walletUseful }} 张 · 共 {{ walletTotal }} 张</small><span class="ego">去使用 →</span>
      </div>
      <router-link class="entry-cell" :to="friendPath">
        <span class="ek">03 / FRIENDS &amp; MESSAGES</span><b>好友 / 消息</b><span class="ev">—</span>
        <small>打开好友与私信，继续沟通</small><span class="ego">打开消息 →</span>
      </router-link>
      <div
        class="entry-cell entry-link"
        role="button"
        tabindex="0"
        title="模拟充值（沙箱）"
        @click="openRecharge"
        @keydown.enter.prevent="openRecharge"
        @keydown.space.prevent="openRecharge"
      >
        <span class="ek">04 / BALANCE</span><b>账户余额</b><span class="ev">¥{{ money(userInfo.Balance) }}</span>
        <small>退款入账 · 点击模拟充值</small><span class="ego">充值 →</span>
      </div>
    </div>

    <div class="two-col section-gap">
      <div class="stack">
        <!-- 等级牌匾（A `plaque`）—— 2026-10-19 用户令：颜色随当前等级动态切换 -->
        <section class="plaque" :class="plaqueClass">
          <div class="plaque-inner">
            <div class="plaque-top"><span>{{ plaqueKicker }}</span><span>NO. 8800 {{ idValue || '—' }}</span></div>
            <div class="plaque-mid">
              <div class="member-level">{{ plaqueLevelName }}<small>会员权益：全场 9 折 / 每日专属券 / 售后优先</small></div>
              <div class="plaque-num">{{ plaquePoints }}<small>AVAILABLE POINTS</small></div>
              <button type="button" class="balance balance-btn" title="模拟充值（沙箱）" @click="openRecharge"><small>账户余额 · 点击充值</small>¥{{ money(userInfo.Balance) }}</button>
            </div>
            <div class="plaque-bottom">
              <span class="name">{{ userInfo.username || '未知用户' }}</span>
              <div class="barcode" aria-hidden="true"><i v-for="n in 22" :key="n" :style="barcodeStyle(n)"></i></div>
              <button class="plaque-link" @click="editOpen = true">编辑资料 →</button>
              <router-link class="plaque-link" :to="memberPath">进入会员中心 →</router-link>
            </div>
          </div>
        </section>

        <!-- 今日工作台（A `task-list`） -->
        <section id="today-workbench" class="panel">
          <div class="panel-head">
            <div><div class="kicker">TODAY / {{ todayText }}</div><h2>今日工作台</h2></div>
            <span class="tag">{{ isMerchantShell ? '商家视角' : '普通买家' }}</span>
          </div>
          <div class="task-list">
            <div class="task">
              <span class="k">买家待办</span>
              <div class="t">购物车 {{ cartCount === null ? '—' : cartCount }} 件待结算<small>券包未使用 {{ walletUseful }} 张，结算时可选择</small></div>
              <div class="a"><span class="tag dark">可结算</span><router-link class="btn xs" :to="cartPath">去结算</router-link></div>
            </div>
            <div class="task">
              <span class="k">售后</span>
              <div class="t">查看历史购买与退货进度<small>已完成订单与售后状态都在这里</small></div>
              <div class="a"><span class="tag">记录</span><router-link class="btn xs" :to="historyPath">看进度</router-link></div>
            </div>
            <div v-if="isMerchantShell" class="task">
              <span class="k">发货</span>
              <div class="t">订单中心处理发货与收货<small>新订单与待处理事项集中在订单中心</small></div>
              <div class="a"><span class="tag outline">待发货</span><router-link class="btn xs" :to="orderCenterPath">去发货</router-link></div>
            </div>
            <div v-else class="task">
              <span class="k">身份</span>
              <div class="t">尚未开通商家身份<small>提交入驻申请，审核通过后解锁工作台</small></div>
              <div class="a"><span class="tag">未入驻</span><router-link class="btn xs solid" :to="applyPath">去申请</router-link></div>
            </div>
            <div class="task">
              <span class="k">场次</span>
              <div class="t">限时秒杀场次<small>查看进行中与即将开始的场次</small></div>
              <div class="a"><span class="tag">秒杀</span><router-link class="btn xs" to="/seckill">看日程</router-link></div>
            </div>
          </div>
          <div class="panel-foot">
            <span class="small muted">{{ isMerchantShell ? '两种身份的待办在同一个工作台里出现，不需要退出登录。' : '入驻之前，这里只显示买家待办；升级后不会丢失任何一项。' }}</span>
            <router-link class="link" :to="isMerchantShell ? orderCenterPath : applyPath">{{ isMerchantShell ? '查看商家工作台 →' : '了解商家入驻 →' }}</router-link>
          </div>
        </section>

        <!-- 身份成长路径（A `growth`） -->
        <section class="panel flush">
          <div class="panel-head"><div><div class="kicker">GROWTH PATH</div><h2>身份成长路径</h2></div><span class="tiny">买家 → 商家 → 品牌</span></div>
          <div class="growth">
            <div class="growth-step done">
              <span class="g-no">01 / BUYER</span><strong>买家</strong>
              <small>注册即获得，永久保留。购物车、订单、积分、余额都在这一层。</small>
              <router-link :to="cartPath">继续购物 →</router-link>
            </div>
            <div v-if="isMerchantShell" class="growth-step current">
              <span class="g-no">02 / MERCHANT</span><strong>商家 · 已开通</strong>
              <small>在买家身份上叠加经营能力：发布商品、处理订单与售后。</small>
              <button type="button" class="g-link" @click="scrollToWorkbench">进入工作台 →</button>
            </div>
            <div v-else class="growth-step">
              <span class="g-no">02 / MERCHANT</span><strong>商家 · 待开通</strong>
              <small>提交入驻申请并通过审核后，才会出现商家工作台入口。</small>
              <router-link :to="applyPath">申请入驻 →</router-link>
            </div>
            <div v-if="isMerchantShell" class="growth-step" :class="{ current: brandKey === 'approved' }">
              <span class="g-no">03 / BRAND</span><strong>品牌 · {{ brandLabel }}</strong>
              <small>品牌是商家的进一步认证。认证后进入品牌专区，商品带品牌标。</small>
              <router-link :to="{ path: applyPath, query: { type: 'brand' } }">{{ brandKey === 'none' ? '申请品牌 →' : '查看资质 →' }}</router-link>
            </div>
            <div v-else class="growth-step">
              <span class="g-no">03 / BRAND</span><strong>品牌 · 未解锁</strong>
              <small>先成为商家，才能申请品牌加盟认证。</small>
              <router-link :to="applyPath">先成为商家 →</router-link>
            </div>
          </div>
        </section>
      </div>

      <aside class="stack">
        <!-- 账户资产（A `ledger`） -->
        <section class="panel">
          <div class="panel-head"><div><div class="kicker">ASSETS</div><h2>账户资产</h2></div><router-link class="tiny" :to="memberPath">明细 →</router-link></div>
          <div class="ledger">
            <div class="ledger-row entry-link" role="button" tabindex="0" title="模拟充值（沙箱）" @click="openRecharge" @keydown.enter.prevent="openRecharge" @keydown.space.prevent="openRecharge"><div class="l"><b>账户余额</b><small>退款入账</small></div><div class="v">¥{{ money(userInfo.Balance) }}<small>可用 · 点击充值</small></div></div>
            <router-link class="ledger-row" :to="memberPath"><div class="l"><b>可用积分</b><small>100 积分 = ¥1</small></div><div class="v">{{ plaquePoints }}<small>查看积分明细</small></div></router-link>
            <div class="ledger-row entry-link" role="button" tabindex="0" :title="walletTitle" @click="openWallet" @keydown.enter.prevent="openWallet" @keydown.space.prevent="openWallet"><div class="l"><b>优惠券</b><small>券包弹窗</small></div><div class="v">{{ walletCount === null ? '—' : `${walletCount} 张` }}<small>去使用</small></div></div>
            <router-link class="ledger-row" :to="friendPath"><div class="l"><b>好友 / 消息</b><small>打开私信</small></div><div class="v">{{ unreadTotal === null ? '—' : unreadTotal }}<small>未读</small></div></router-link>
            <div v-if="isMerchantShell" class="ledger-row entry-link" role="button" tabindex="0" title="去我自己的店铺主页" @click="enterMyShop" @keydown.enter.prevent="enterMyShop" @keydown.space.prevent="enterMyShop"><div class="l"><b>店铺信息</b><small>我的店铺主页</small></div><div class="v">进店<small>查看</small></div></div>
            <div v-if="isMerchantShell" class="ledger-row entry-link" role="button" tabindex="0" :title="certTitle" @click="openCert" @keydown.enter.prevent="openCert" @keydown.space.prevent="openCert"><div class="l"><b>商家认证</b><small>入驻申请提审状态</small></div><div class="v">{{ certLabel }}<small>详情</small></div></div>
            <div v-if="isMerchantShell" class="ledger-row entry-link" role="button" tabindex="0" :title="plaqueLevelName" @click="openLevel" @keydown.enter.prevent="openLevel" @keydown.space.prevent="openLevel"><div class="l"><b>经营等级</b><small>按会员成长值</small></div><div class="v">{{ plaqueLevelName }}<small>说明</small></div></div>
          </div>
        </section>

        <!-- 品牌资质（商家）/ 品牌加盟（买家） -->
        <section v-if="isMerchantShell" class="panel">
          <div class="panel-head">
            <div><div class="kicker">BRAND QUALIFICATION</div><h2>品牌资质</h2></div>
            <span class="tag" :class="{ dark: brandKey === 'approved' }">{{ brandLabel }}</span>
          </div>
          <div class="brand-states">
            <router-link
              v-for="s in BRAND_STATES"
              :key="s.key"
              class="brand-state"
              :class="{ on: brandKey === s.key }"
              :to="{ path: applyPath, query: { type: 'brand', state: s.key } }"
            ><b>{{ s.no }}</b>{{ s.label }}</router-link>
          </div>
          <div class="panel-foot">
            <span class="small muted">{{ brandKey === 'approved' ? '当前账号已开通品牌，商品会带品牌标。' : '品牌认证是商家之上的进一步认证。' }}</span>
            <router-link class="link" :to="{ path: applyPath, query: { type: 'brand' } }">{{ brandKey === 'none' ? '申请品牌 →' : '查看资质 →' }}</router-link>
          </div>
        </section>
        <section v-else class="panel">
          <div class="panel-head"><div><div class="kicker">BRAND QUALIFICATION</div><h2>品牌加盟</h2></div><span class="tag">未解锁</span></div>
          <p class="small muted brand-locked-note">品牌加盟是商家身份的进一步认证。当前账号还未入驻商家，因此「申请品牌加盟」入口暂不开放。</p>
          <div class="brand-locked-actions">
            <router-link class="btn solid" :to="applyPath">先申请商家入驻</router-link>
            <router-link class="btn ghost" to="/brand">看品牌专区</router-link>
          </div>
        </section>

        <!-- 积分动态（A `RECENT POINTS`，2026-10-19 补充令：接真端点 `/member/points/logs`） -->
        <section class="panel">
          <div class="panel-head"><div><div class="kicker">RECENT POINTS</div><h2>积分动态</h2></div><router-link class="tiny" :to="memberPath">全部 →</router-link></div>
          <div class="ledger">
            <router-link v-for="lg in pointLogs.slice(0, 3)" :key="lg.Id" class="ledger-row" :to="memberPath"><div class="l"><b>{{ lg.Remark || lg.BizType || '积分变动' }}</b><small>{{ lg.created_at || '' }}</small></div><div class="v">{{ Number(lg.ChangeAmount) > 0 ? '+' : '' }}{{ lg.ChangeAmount }}<small>积分</small></div></router-link><p v-if="!pointLogs.length" class="empty-line">暂无积分记录。</p>
          </div>
        </section>

        <!-- 最近订单（A `RECENT ORDERS`，接真历史订单） -->
        <section class="panel flush">
          <div class="panel-head"><div><div class="kicker">RECENT ORDERS</div><h2>最近订单</h2></div><router-link class="tiny" :to="historyPath">历史购买 →</router-link></div>
          <div class="ledger">
            <router-link
              v-for="o in recentOrders.slice(0, 2)"
              :key="o.OrderId"
              class="ledger-row"
              :to="historyPath"
            ><div class="l"><b>{{ o.ProductInformation || '订单 ' + o.OrderId }}</b><small>{{ o.OrderId }}</small></div><div class="v">¥{{ money(o.Price) }}<small>{{ o.SettlementDate || '已完成' }}</small></div></router-link>
            <p v-if="!recentOrders.length" class="empty-line">暂无已完成订单。</p>
          </div>
        </section>
      </aside>
    </div>

    <!-- 四格快捷入口（A `shortcut-grid`） -->
    <section class="section-gap">
      <div class="shortcut-grid">
        <router-link class="shortcut" :to="myOrdersPath"><span class="s-no">01 / ORDERS</span><strong>我的订单</strong><small>按状态筛选，查看每单的流程节点与售后进度。</small><span class="s-go">查看订单 →</span></router-link>
        <router-link class="shortcut" :to="historyPath"><span class="s-no">02 / HISTORY</span><strong>历史购买记录</strong><small>{{ recentOrders.length }} 笔已完成订单，支持再次购买。</small><span class="s-go">查看记录 →</span></router-link>
        <router-link class="shortcut" :to="friendPath"><span class="s-no">03 / MESSAGES</span><strong>好友 / 消息</strong><small>打开好友与私信，继续沟通。</small><span class="s-go">打开消息 →</span></router-link>
        <router-link v-if="isMerchantShell" class="shortcut" :to="orderCenterPath"><span class="s-no">04 / SHIP</span><strong>商家订单处理</strong><small>进入订单中心处理发货与售后。</small><span class="s-go">去发货 →</span></router-link>
        <router-link v-else class="shortcut" :to="applyPath"><span class="s-no">04 / MERCHANT</span><strong>商家入驻申请</strong><small>提交表单，审核通过后解锁工作台。</small><span class="s-go">去申请 →</span></router-link>
        <router-link id="tour-menu-add" v-if="isMerchantShell" class="shortcut" :to="addGoodsPath"><span class="s-no">05 / LISTING</span><strong>上架商品</strong><small>新建商品、保存草稿并提交审核。</small><span class="s-go">去上架 →</span></router-link>
        <router-link v-if="isMerchantShell" class="shortcut" :to="listGoodsPath"><span class="s-no">06 / CATALOG</span><strong>查看商品</strong><small>管理在售、待审与驳回商品及 SKU。</small><span class="s-go">查看商品 →</span></router-link>
        <router-link v-if="isMerchantShell" class="shortcut" :to="statsPath"><span class="s-no">07 / STATS</span><strong>经营数据</strong><small>查看销售、订单与库存的经营看板。</small><span class="s-go">查看数据 →</span></router-link>
      </div>
    </section>

    <div class="demo-note"><span>器物商城 · 个人中心</span><span>IDENTITY OVERVIEW / A</span></div>

    <!-- 编辑资料弹窗：承载旧版「账号设置 / 资料完善 / 更换头像」三项真功能 -->
    <div v-if="editOpen" class="mc-scrim" @click.self="editOpen = false">
      <div class="mc-dlg edit-dlg" role="dialog" aria-modal="true">
        <div class="mc-dlg-h"><span>编辑个人资料</span><button class="mc-x" aria-label="关闭" @click="editOpen = false">×</button></div>
        <div class="mc-dlg-b">
          <div class="edit-avatar">
            <img class="avatar" :src="userInfo.image || assetOf('/img/default_user.png')" alt="头像" />
            <button class="btn xs" @click="chooseImage">更换头像照片</button>
            <input ref="fileInput" type="file" accept="image/*" class="file-hidden" @change="doUpload">
          </div>
          <div class="form2">
            <div class="f"><label for="mail">邮箱</label><input id="mail" type="text" v-model="userInfo.email" placeholder="暂未填写邮箱"></div>
            <div class="f"><label for="addr">常用地址</label><input id="addr" type="text" v-model="userInfo.address" placeholder="暂未填写地址"></div>
          </div>
          <div class="f"><label for="bio">自我介绍</label><textarea id="bio" v-model="userInfo.selfintroduction" placeholder="暂无自我介绍"></textarea></div>
          <div class="actions"><button class="btn btn-primary" @click="saveUserInfo"><IconGlyph name="ok" />保存个人信息</button></div>
          <div class="form2">
            <div class="f"><label for="gender">性别</label><select id="gender" v-model="userInfo.gender"><option value="">未填写</option><option value="男">男</option><option value="女">女</option></select></div>
            <div class="f"><label for="age">年龄</label><input id="age" type="number" min="0" max="150" v-model.number="userInfo.age" placeholder="暂未填写年龄"></div>
          </div>
          <div class="actions">
            <button class="btn btn-primary" @click="saveProfile"><IconGlyph name="ok" />保存资料完善</button>
            <span class="dp-hint">留空 = 清空该列（后端把「没填」当「没有」）</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 模拟充值（沙箱）—— 2026-10-19 用户令：余额可点、能真实入账并写流水。
         后端 `/wallet/recharge` 的幂等键是 `RequestId`（前端生成、重试复用）。 -->
    <div v-if="rechargeOpen" class="mc-scrim" @click.self="rechargeOpen = false">
      <div class="mc-dlg recharge-dlg" role="dialog" aria-modal="true" aria-label="模拟充值">
        <div class="mc-dlg-h"><span>模拟充值 · 沙箱</span><button class="mc-x" aria-label="关闭" @click="rechargeOpen = false">×</button></div>
        <div class="mc-dlg-b">
          <p class="mc-why">当前余额 ¥{{ money(userInfo.Balance) }}。单笔 10–1000 元；充值即时入账并写入钱包流水。</p>
          <div class="recharge-row">
            <button v-for="amt in RECHARGE_PRESETS" :key="amt" type="button" class="btn xs" :class="{ solid: Number(rechargeAmount) === amt }" @click="rechargeAmount = amt">¥{{ amt }}</button>
          </div>
          <label class="f"><span>充值金额（元）</span><input v-model.number="rechargeAmount" type="number" min="10" max="1000" step="1"></label>
          <p v-if="rechargeMsg" class="mc-why" :class="{ 'recharge-bad': !rechargeOk }">{{ rechargeMsg }}</p>
          <div class="actions">
            <button type="button" class="btn btn-primary" :disabled="rechargeBusy" @click="doRecharge">{{ rechargeBusy ? '充值中…' : '确认充值' }}</button>
            <button type="button" class="btn" @click="rechargeOpen = false">关闭</button>
          </div>
          <div v-if="rechargeLogs.length" class="recharge-logs">
            <div class="mc-kicker">最近流水</div>
            <div v-for="l in rechargeLogs" :key="l.Id || l.id" class="recharge-log"><span>{{ l.Remark || l.ChangeType || '充值' }}</span><b>+¥{{ money(l.Amount) }}</b><small>余额 ¥{{ money(l.BalanceAfter) }}</small></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 券包弹窗（原样保留） -->
    <CouponWalletDialog :open="walletOpen" :account="couponAccount" @close="walletOpen = false" @changed="loadWallet" />

    <!-- 商家认证 / 经营等级遮罩（原样保留） -->
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
          <!-- 申请资料与证件图（2026-10-19 用户令）：读自 `certLast` = `/merchant/myApplications` 最新一条。 -->
          <dl v-if="cardPanel === 'cert' && certLast" class="mc-kv">
            <div><dt>申请类型</dt><dd>{{ certLast.ApplicationType || (Number(certLast.IsBrand) === 1 ? '品牌加盟' : '商家入驻') }}</dd></div>
            <div><dt>店铺名称</dt><dd>{{ certLast.ShopName || '—' }}</dd></div>
            <div><dt>营业执照号</dt><dd>{{ certLast.LicenseNo || '—' }}</dd></div>
            <div><dt>联系人</dt><dd>{{ certLast.ContactName || '—' }}</dd></div>
            <div><dt>联系电话</dt><dd>{{ certLast.ContactPhone || '—' }}</dd></div>
            <div><dt>店铺地址</dt><dd>{{ certLast.ShopAddress || '—' }}</dd></div>
            <div><dt>提交时间</dt><dd>{{ certLast.SubmittedAt || '—' }}</dd></div>
            <div><dt>审核时间</dt><dd>{{ certLast.ReviewedAt || '—' }}</dd></div>
          </dl>
          <figure v-if="cardPanel === 'cert' && certLast && certLast.LicenseImage" class="mc-license">
            <img :src="assetOf(certLast.LicenseImage)" alt="营业执照" />
            <figcaption>营业执照 / 证件图</figcaption>
          </figure>
          <p v-if="cardPanel === 'cert'" class="mc-why"><router-link class="link" :to="applyPath">查看入驻申请 →</router-link></p>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { http, handleApiError, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getUsername, getCustomerId, getMerchantId, getMemberId, getRole, setBalance, isLoggedIn, clearCredentials, clearMerchantId, clearToken } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移（`api-contract.md` 按 `:99` 钉住本文件）。`getCustomerId` 供余额充值弹窗（2026-10-19）；`getMemberId` 供收藏数（2026-10-19 终局清道夫第 7 条，商家 customerid 为 NULL ⇒ 三键兜底）。
import { myCoupons } from '../api/marketing'; import CouponWalletDialog from '../components/CouponWalletDialog.vue'   // ⚠️ 两条 import **同行**（C3 块 5 · 券的信息架构重排，2026-10-11）—— 同上一行的理由：本文件多处被按行号引，**能省一行就省一行**
import { useRouter } from 'vue-router'; import { merchantMyApplications } from '../api/merchant'   // ⚠️ 两条 import **同行**（C3 块 5 · B 组，2026-10-12）—— 理由同上：`useRouter` 给「店铺信息」格跳 `/shop`用（那条路由属 commit C），`merchantMyApplications` 给「商家认证」格读本人提审历史（`/merchant/myApplications`，`api/merchant.js` 已有封装，**没有新增端点、没有动 `endpoints.js`**）
import { brandOverview } from '../api/brand'; import { cartList, orderHistory } from '../api/order'   // A 版个人中心（2026-10-19）：品牌四态 / 购物车件数 / 最近订单。两个 api 模块都是既有封装，未新增端点。
import { memberOverview, memberPointLogs } from '../api/member'; import { walletOverview, walletRecharge } from '../api/wallet'   // 2026-10-19 用户令：等级牌匾动态分色 + 账户余额模拟充值（钱包四条端点早已落地，这里只接 UI）。
import { favoriteList } from '../api/favorite'   // 2026-10-19 终局清道夫第 7 条：收藏数读真端点（/favorite/list 的 total），不再写死「—」。
// 收藏数：`null` = 还没读到（显示 —）；0 = 真没收藏（显示 0）。取不到身份时保持 `null`，不编 0。
const favCount = ref(null); const loadFavoriteCount = async () => { const id = getMemberId(); if (!id) return; try { const d = await favoriteList(id, 1); if (d && d.success) favCount.value = Number(d.total) || 0 } catch (e) { favCount.value = null } }
// 好友 / 消息未读数（2026-10-19 补充令）：与 `AppShell.fetchUnreadCount` **同一端点、同一判据** ——
// `/message/conversations` 里 `unread === true` 的会话数。身份用**会话角色**选号（`getRole()`），
// 不用 `userInfo.role`：两者并发加载，后者可能还没回来。`null` = 读取失败（显示 —），0 = 真没有未读。
const unreadTotal = ref(null); const loadUnreadCount = async () => { if (!isLoggedIn()) { unreadTotal.value = 0; return } const mid = getMerchantId(); const cid = getCustomerId(); const isM = String(getRole()) === '2'; const id = isM ? (mid || cid) : (cid || mid); if (!id) { unreadTotal.value = 0; return } const usp = new URLSearchParams(); usp.append('currentId', id); usp.append('currentType', isM ? 'merchant' : 'customer'); try { const res = await http.post(endpoints.message.conversations, usp.toString()); const d = await res.json(); if (d && d.success) unreadTotal.value = (d.list || []).filter((c) => c.unread === true).length; else unreadTotal.value = null } catch (e) { unreadTotal.value = null } }

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

// ── A 版个人中心（2026-10-19）：路径 / 展示辅助 / 三路真实读数 ────────────────────
const vip = computed(() => Number(userInfo.value.MemberLevel) > 0)
const money = (v) => Number(v || 0).toFixed(2)

// ── 等级牌匾动态分色（2026-10-19 用户令）──────────────────────────────────
// 等级色卡来自 `member_level.Theme`（`MemberController./member/overview`），本页只在启动时读一次；
// 取不到时按「会员=金 / 普通=白」兜底。浅底主题一律用青瓷绿文字（用户口令：保证对比度）。
const memberOv = ref(null)
const PLAQUE_THEME = { plain: 1, silver: 1, gold: 1, platinum: 1, diamond: 1, black: 1, supreme: 1 }
const memberTheme = computed(() => {
  const lv = memberOv.value && memberOv.value.level
  const t = String((lv && lv.Theme) || '').toLowerCase()
  return PLAQUE_THEME[t] ? t : (vip.value ? 'gold' : 'plain')
})
const PLAQUE_CLASS = { plain: 'pl-white', silver: 'pl-silver', gold: 'pl-gold', platinum: 'pl-platinum', diamond: 'pl-diamond', black: 'pl-black', supreme: 'pl-ink' }
const PLAQUE_NAME = { plain: '普通会员', silver: '银卡会员', gold: '金卡会员', platinum: '铂金会员', diamond: '钻石会员', black: '黑金会员', supreme: '青瓷至尊' }
const PLAQUE_KICKER = { plain: 'MEMBER CARD / 普通会员牌匾', silver: 'SILVER CARD / 银卡牌匾', gold: 'GOLD CARD / 金卡牌匾', platinum: 'PLATINUM CARD / 铂金牌匾', diamond: 'DIAMOND CARD / 钻石牌匾', black: 'BLACK CARD / 黑金卡牌匾', supreme: 'CELADON CARD / 青瓷至尊牌匾' }
const plaqueClass = computed(() => PLAQUE_CLASS[memberTheme.value])
const plaqueKicker = computed(() => PLAQUE_KICKER[memberTheme.value])
const plaqueLevelName = computed(() => {
  const lv = memberOv.value && memberOv.value.level
  return `${(lv && lv.Name) || PLAQUE_NAME[memberTheme.value]} · ${(lv && lv.Code) || (vip.value ? 'V1' : 'V0')}`
}); /* 2026-10-19 用户令：头像旁标签不再写死「黑金会员 / 普通会员」—— 直接绑 `member_level.Name`（银卡就写银卡）。同行追加、不新增行。 */ const memberLevelLabel = computed(() => { const lv = memberOv.value && memberOv.value.level; return (lv && lv.Name) || PLAQUE_NAME[memberTheme.value] })
const plaquePoints = computed(() => {
  const acc = memberOv.value && memberOv.value.account
  return acc ? String(Number(acc.Balance || 0)) : '—'
})
const loadMemberOverview = async () => {
  const cid = getMemberId()   // 2026-10-19：商家 customerid 为 NULL ⇒ 三键兜底（后端 PointsDao 同口径），否则商家牌匾/积分永远 —。
  if (!cid) return
  try {
    const data = await memberOverview(cid)
    if (data && data.success) memberOv.value = data
  } catch (e) { /* 概览失败 ⇒ 牌匾走 VIP / 普通兜底，不打断本页 */ }
}

// ── 模拟充值（沙箱）────────────────────────────────────────────────────────
const RECHARGE_PRESETS = [50, 100, 300, 500]
const rechargeOpen = ref(false)
const rechargeAmount = ref(100)
const rechargeBusy = ref(false)
const rechargeMsg = ref('')
const rechargeOk = ref(false)
const rechargeLogs = ref([])

const openRecharge = async () => {
  rechargeMsg.value = ''
  rechargeOpen.value = true
  // 2026-10-19：商家 customerid 为 NULL ⇒ 与会员中心同口径（customerid → merchantid → 登录名；后端 WalletDao 同款三键），否则商家必现「未识别到客户账号」。
  const cid = getMemberId(); if (!cid) { rechargeOk.value = false; rechargeMsg.value = '未识别到登录账号，请重新登录后再充值'; return }
  try {
    const data = await walletOverview(cid)
    if (data && data.success) rechargeLogs.value = (data.logs || []).slice(0, 5)
  } catch (e) { /* 流水读取失败不影响充值 */ }
}

const doRecharge = async () => {
  const cid = getMemberId()   // 2026-10-19：三键兜底，商家 customerid 为 NULL（见 openRecharge）
  if (!cid) { rechargeOk.value = false; rechargeMsg.value = '未识别到客户账号，请重新登录'; return }
  const amount = Number(rechargeAmount.value)
  if (!Number.isFinite(amount) || amount < 10 || amount > 1000) {
    rechargeOk.value = false; rechargeMsg.value = '单笔充值金额需在 10–1000 元之间'; return
  }
  rechargeBusy.value = true
  rechargeMsg.value = ''
  try {
    const data = await walletRecharge({
      customerId: cid,
      amount,
      // 幂等键：同一次点击重试复用；这里每次点击生成一条新的（重复点击由按钮 disabled 挡住）。
      requestId: 'UI' + Date.now() + Math.random().toString(36).slice(2, 8)
    })
    if (data && data.success) {
      const after = data.balanceAfter != null ? Number(data.balanceAfter) : Number(userInfo.value.Balance || 0) + amount
      userInfo.value.Balance = after
      setBalance(after)
      rechargeOk.value = true
      rechargeMsg.value = data.duplicate ? '该笔充值已处理过（幂等），余额未重复增加' : `充值成功 ¥${amount.toFixed(2)}，当前余额 ¥${after.toFixed(2)}`
      const ov = await walletOverview(cid)
      if (ov && ov.success) rechargeLogs.value = (ov.logs || []).slice(0, 5)
    } else {
      rechargeOk.value = false
      rechargeMsg.value = (data && data.msg) || '充值失败'
    }
  } catch (e) {
    rechargeOk.value = false
    rechargeMsg.value = '充值失败：' + (e && e.message ? e.message : '网络错误')
  } finally {
    rechargeBusy.value = false
  }
}
const pad2 = (n) => String(n ?? 0).padStart(2, '0')
const todayText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}.${pad2(d.getMonth() + 1)}.${pad2(d.getDate())}`
})
/** 黑金牌匾的装饰条码：高度/宽度按 A 版 `:1894` 的同款取模规则。 */
const barcodeStyle = (i) => ({
  height: `${i % 4 === 0 ? 18 : i % 3 === 0 ? 12 : 8}px`,
  width: `${i % 5 === 0 ? 3 : 2}px`,
})

const selfPath = computed(() => (isMerchantShell.value ? '/merchant/user/info' : '/customer/user/info'))
const cartPath = computed(() => (isMerchantShell.value ? '/merchant/order/cart' : '/customer/cart'))
const orderCenterPath = computed(() => (isMerchantShell.value ? '/merchant/order' : '/customer/cart'))
const historyPath = computed(() => (isMerchantShell.value ? '/merchant/user/history' : '/customer/user/history'))
// 独立「我的订单」页（2026-10-19 用户令：中转站点卡片 ⇒ 全宽独立页）。
const myOrdersPath = computed(() => (isMerchantShell.value ? '/merchant/orders' : '/customer/orders'))
// 商家工作台三入口：菜单层退役后，必须由个人中心卡片承接，否则功能失去入口。
const addGoodsPath = '/merchant/user/add'
const listGoodsPath = '/merchant/user/list'
const statsPath = '/merchant/stats'
const friendPath = computed(() => (isMerchantShell.value ? '/merchant/user/friend' : '/customer/user/friend'))
const memberPath = computed(() => (isMerchantShell.value ? '/merchant/user/member' : '/customer/user/member'))
const applyPath = computed(() => (isMerchantShell.value ? '/merchant/user/apply' : '/customer/user/apply')); const favoritesPath = computed(() => (isMerchantShell.value ? '/merchant/user/favorites' : '/customer/user/favorites'))   // 2026-10-19 终局清道夫第 7 条：收藏列表独立页（页面在 router 文件尾追加，不移动既有行号）。

/** 品牌四态（A 版 `BRAND_STATES` 的序号 / 键 / 文案逐字照抄）。 */
const BRAND_STATES = [
  { no: '01', key: 'none', label: '未申请' },
  { no: '02', key: 'pending', label: '审核中' },
  { no: '03', key: 'rejected', label: '已驳回' },
  { no: '04', key: 'approved', label: '已通过' },
]
/** 品牌态：`brandOverview` 的 `active` 映射成 A 版的 `approved`；取不到时 `''`（显示 —）。 */
const brandState = ref('')
const brandKey = computed(() => (brandState.value === 'active' ? 'approved' : brandState.value))
const brandLabel = computed(() => {
  const hit = BRAND_STATES.find((s) => s.key === brandKey.value)
  return hit ? hit.label : '—'
})
const loadBrand = async () => {
  const username = getUsername()
  if (!username) return
  try {
    const data = await brandOverview(username)
    if (data && data.success) brandState.value = String(data.state || 'none')
  } catch (e) {
    brandState.value = ''
  }
}

/** 购物车件数（A 版页头 / 桥 / 工作台三处共用）。取不到 = `null` → 画 `—`，不编 0。 */
const cartCount = ref(null)
const loadCart = async (customerId) => {
  if (!customerId) { cartCount.value = null; return }
  try {
    const data = await cartList(customerId)
    const list = Array.isArray(data && data.list) ? data.list : []
    cartCount.value = list.reduce((n, r) => n + (Number(r.Quantity) || 0), 0)
  } catch (e) {
    cartCount.value = null
  }
}

/** 最近已完成订单（A 版「最近订单」两行）：`/order/historicalOrdersSelect`，只取前两条。 */
const recentOrders = ref([])
const loadRecentOrders = async (customerId) => {
  if (!customerId) { recentOrders.value = []; return }
  try {
    const data = await orderHistory(customerId)
    recentOrders.value = Array.isArray(data && data.list) ? data.list : []
  } catch (e) {
    recentOrders.value = []
  }
}

/** 「编辑资料」弹窗（承载旧版账号设置 / 资料完善 / 更换头像三项真功能）。 */
const editOpen = ref(false)

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
  loadMemberOverview(); loadFavoriteCount(); loadUnreadCount(); loadPointLogs()
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

    // ⚠️ C3 块 5（2026-10-11）：券包的 `Account` 当时是**客户号**（`user.customerid`）。
    //    **2026-10-12 用户裁定「乙」—— 券改「账号口径」**：`Account` = **登录名**（`login.UserName`，
    //    后端 `CouponController` / `CouponDao` 同笔改）。理由：VM 库 14 个商家 `customerid` **全空**
    //    （种子商家直接建号、没走过客户阶段）⇒ 旧口径下商家在券包上**没有账号可挂**，那正是漏点。
    //    ⚠️ 因此这一行**不再读 `data.customerid`**，改读登录名（`getUsername()`，与两处 `openWallet`
    //       上方那颗 `div` 的判据同源）；`!isMerchantShell.value` 那道守卫在 B 组已废，这里也不再补。
    //    ⚠️ 兜底不变：取不到账号 ⇒ `loadWallet` 直接回 `null`（券格显 `—`），**不编 0**。
    //    ⚠️ 与 `HomePage.vue` / `ProductPage.vue` 是同一条判据（都 `getUsername()`）—— 三处要一起改。
    couponAccount.value = getUsername() || ''
    if (couponAccount.value) loadWallet(couponAccount.value)
    // 「商家认证」那一格要一个**真状态**（不是 `—`）⇒ 与券包同一次资料加载里问一次，且**只在商家壳问**。
    if (isMerchantShell.value) { loadCert(); loadBrand() }
    // A 版个人中心三处真实读数（2026-10-19）：购物车件数 / 最近订单 / 品牌四态。
    // 前两项按 `customerid` 取；商家号没保留客户号时后端查空 ⇒ 画空态，不编数。
    loadCart(data.customerid)
    loadRecentOrders(data.customerid)
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

/** 券包 `Account`（**登录名**，`login.UserName`，2026-10-12 起）—— 弹窗 `:account` 与摘要查询都用它。 */
const couponAccount = ref('')

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
  const acct = account || couponAccount.value
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

// 退出登录（2026-10-19 用户令）：本页 `noRail` + `noMenu` 把黑树与浅色菜单两处退出入口都遮掉了，这里补回。
// 口径镜像 `UserCenter.vue`（`:52-63`）：清本地 → 跳登录 → 异步销服务端会话（allowUnauthorized 防退出时 401）→ 票在 finally 里清。
const toLogin = () => {
  clearCredentials()
  if (isMerchantShell.value) clearMerchantId()
  router.push('/login')
  http.post(endpoints.user.logout, null, { allowUnauthorized: true }).catch(() => {}).finally(() => clearToken())
}

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

/** 状态 → 卡片里那行短文案（`③ 卡片式统一`之后是卡片第三段 = 值位，`.assets span`）。 */
const certLabel = computed(() => {
  const map = { approved: '已认证', pending: '审核中', rejected: '已驳回', none: '未提交' }
  return map[certState.value] || '—'
})

/** 卡片那格的 `title`：点开前先把状态说清楚（与券包那格的 `walletTitle` 同一种做法）。 */
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
  router.push({ path: '/shop', query: { MerchantId: mid, from: 'user' } })
}

/** 「商家认证」格：开弹窗（状态已在 `getUserData()` 那次问过）。 */
const openCert = () => { cardPanel.value = 'cert' }

/** 本页「进入工作台」类按钮：平滑滚到同页「今日工作台」，不跳走（2026-10-19 用户令）。 */
const scrollToWorkbench = () => {
  const el = typeof document !== 'undefined' ? document.getElementById('today-workbench') : null
  if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 「经营等级」格：开弹窗。规则未定（用户 2026-10-12 裁定 ④「先占位，不做」）⇒ 面板是固定文案。 */
const openLevel = () => { cardPanel.value = 'level' }; /* 2026-10-19 补充令：积分动态接真端点（`/member/points/logs`）—— 与会员中心同一条流水，身份走 `getMemberId()` 三键兜底（商家 customerid 为 NULL）。**同行追加、不新增行**：本文件 `:983`/`:1098` 等被文档按行号钉住。 */ const pointLogs = ref([]); const loadPointLogs = async () => { const id = getMemberId(); if (!id) return; try { const d = await memberPointLogs({ customerId: id, limit: 3 }); pointLogs.value = d && d.success && Array.isArray(d.list) ? d.list : [] } catch (e) { pointLogs.value = [] } }
</script>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════════════
   A 版个人中心样式（2026-10-19 重写）。
   数值逐条取自 `_work/ui-wireframe/a.html`：
     · `.page` / `.page-head` / `.head-actions`          :109-113
     · `.bridge*`                                         :131-149
     · `.plaque*`                                         :157-170
     · `.task*` / `.growth*` / `.ledger*`                 :172-198
     · `.brand-states` / `.shortcut-grid`                 :200-215
     · `.join-banner` / `.entry-grid` / `.brand-cta`      :806-855
     · `.demo-note`                                       :680
   颜色映射：`--paper` → `--surface`、`--paper-2` → `--surface-2`、
   `--line-3` → `--line`、`--ink-4` → `--ink-3`。
   ══════════════════════════════════════════════════════════════════════════════ */

button,
input,
textarea,
select {
  font: inherit;
  color: inherit;
}
button {
  background: none;
  border: 0;
  cursor: pointer;
}

.uc-main {
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: 15px;
  color: var(--ink);
}

/* ── 基础小件 ─────────────────────────────────────────────────────────────── */
.kicker { font-family: var(--font-mono); font-size: 10px; letter-spacing: .12em; text-transform: uppercase; color: var(--ink-3); }
.mono { font-family: var(--font-mono); }
.small { font-size: 12px; }
.muted { color: var(--ink-3); }
.tiny { font-family: var(--font-mono); font-size: 10px; letter-spacing: .06em; color: var(--ink-3); }
.link { display: inline-flex; align-items: center; gap: 6px; border-bottom: 1px solid var(--brand-ink); padding-bottom: 1px; font-size: 13px; color: var(--brand-ink); text-decoration: none; }
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  border: 1px solid var(--brand-ink); background: var(--surface); color: var(--brand-ink);
  padding: 10px 15px; font-size: 13px; line-height: 1.2; text-decoration: none;
  transition: background-color .12s linear, color .12s linear;
}
.btn:hover { background: var(--brand-ink); color: var(--surface); }
.btn.solid { background: var(--brand-ink); color: var(--surface); }
.btn.solid:hover { background: var(--surface); color: var(--brand-ink); }
.btn.ghost { background: transparent; }
.btn.xs { padding: 5px 8px; font-size: 11px; }
.btn.sm { padding: 6px 10px; font-size: 11px; }
.btn.btn-primary { background: var(--brand-ink); color: var(--surface); }
.btn.btn-primary:hover { background: var(--surface); color: var(--brand-ink); }
.tag {
  display: inline-flex; align-items: center; border: 1px solid var(--line-2);
  padding: 2px 7px; font-family: var(--font-mono); font-size: 9px;
  letter-spacing: .05em; color: var(--ink-3); line-height: 1.5; white-space: nowrap;
}
.tag.dark { background: var(--brand-ink); color: var(--surface); border-color: var(--brand-ink); }
.tag.outline { border-color: var(--brand-ink); color: var(--brand-ink); }

/* ── 页头 ─────────────────────────────────────────────────────────────────── */
.page-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 32px;
  align-items: end;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 38px;
}
.page-head h1 {
  font-family: var(--font-display);
  font-size: 48px;
  line-height: 1.02;
  font-weight: 600;
  letter-spacing: -.035em;
  margin: 10px 0 12px;
}
.page-head h1 .sep { color: var(--ink-3); margin: 0 .08em; }
.page-head p { max-width: 700px; color: var(--ink-3); font-size: 14px; line-height: 1.7; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; align-items: center; }

/* ── 降级状态条（保留旧语义） ───────────────────────────────────────────────── */
.dg-note {
  display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 12px;
  margin: 0 0 24px; padding: 12px 16px; border-left: 3px solid var(--brand-ink);
  background: var(--surface-2); font-size: 13px; line-height: 1.7; color: var(--ink-2);
}
.dg-note.frozen { border-left-color: var(--warning); background: var(--warning-ground); color: var(--warning-deep); }
.dg-note .dg-tag { flex: none; font-weight: 600; }
.dg-note .dg-why { color: inherit; opacity: .75; }

/* ── 品牌加盟（商家）/ 入驻横幅（买家） ─────────────────────────────────────── */
.brand-cta {
  display: grid; grid-template-columns: minmax(0, 1fr) 270px; gap: 30px;
  align-items: center; border: 1px solid var(--brand-ink); background: var(--brand-ink);
  color: var(--surface); padding: 32px 36px; margin-bottom: 24px;
}
.brand-cta .kicker { color: rgba(255, 255, 255, 0.72); }
.brand-cta h2 {
  font-family: var(--font-display); font-size: 44px; line-height: 1;
  letter-spacing: -.045em; margin: 9px 0 12px; font-weight: 600;
}
.brand-cta p { font-size: 12px; line-height: 1.75; color: rgba(255, 255, 255, 0.78); max-width: 640px; }
.brand-cta .cta-side {
  display: flex; flex-direction: column; gap: 10px;
  border-left: 1px solid rgba(255, 255, 255, 0.24); padding-left: 26px;
}
.brand-cta .cta-side .btn {
  background: var(--surface); color: var(--ink); border-color: var(--surface);
  padding: 16px 18px; font-size: 14px; justify-content: center;
}
.brand-cta .cta-side .btn:hover { background: transparent; color: var(--surface); }
.brand-cta .cta-side small { font-family: var(--font-mono); font-size: 9px; line-height: 1.7; color: rgba(255, 255, 255, 0.72); }

.join-banner {
  display: grid; grid-template-columns: minmax(0, 1.45fr) 290px; gap: 34px;
  align-items: center; border: 1px solid var(--brand-ink); background: var(--brand-ink);
  color: var(--surface); padding: 30px 34px; margin-bottom: 24px;
}
.join-banner .kicker { color: rgba(255, 255, 255, 0.72); }
.join-banner h2 {
  font-family: var(--font-display); font-size: 36px; line-height: 1.05;
  letter-spacing: -.035em; margin: 8px 0 10px; font-weight: 600;
}
.join-banner p { font-size: 12px; line-height: 1.75; color: rgba(255, 255, 255, 0.78); max-width: 650px; }
.join-banner .jb-actions { display: flex; flex-direction: column; gap: 8px; }
.join-banner .jb-actions .btn { border-color: var(--surface); background: transparent; color: var(--surface); padding: 13px 16px; font-size: 13px; }
.join-banner .jb-actions .btn:hover { background: var(--surface); color: var(--ink); }
.join-banner .jb-actions .btn.solid { background: var(--surface); color: var(--ink); }
.join-banner .jb-actions .btn.solid:hover { background: transparent; color: var(--surface); }
.join-banner .jb-meta { font-family: var(--font-mono); font-size: 9px; letter-spacing: .05em; color: rgba(255, 255, 255, 0.6); text-align: right; margin-top: 4px; }

/* ── 双身份桥 ─────────────────────────────────────────────────────────────── */
.bridge {
  display: grid; grid-template-columns: minmax(0, 1fr) 128px minmax(0, 1fr);
  border: 1px solid var(--line); background: var(--surface);
}
.bridge-side {
  display: flex; flex-direction: column; gap: 18px; padding: 26px 28px; min-width: 0;
  color: inherit; text-decoration: none;
}
.bridge-side:hover { background: var(--surface-2); }
.bridge-side.active { background: var(--brand-ink); color: var(--surface); }
.bridge-side.active:hover { background: var(--brand-ink); }
.bridge-side .b-kicker { font-family: var(--font-mono); font-size: 9px; letter-spacing: .1em; color: var(--ink-3); }
.bridge-side.active .b-kicker { color: var(--surface); }
.bridge-side strong { font-family: var(--font-display); font-size: 34px; line-height: 1; font-weight: 600; letter-spacing: -.03em; }
.bridge-side dl { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 18px; }
.bridge-side dl div { border-top: 1px solid var(--line); padding-top: 7px; }
.bridge-side.active dl div { border-color: rgba(255, 255, 255, 0.28); }
.bridge-side dt { font-size: 10px; color: var(--ink-3); }
.bridge-side.active dt { color: var(--surface); }
.bridge-side dd { font-family: var(--font-mono); font-size: 13px; margin-top: 2px; }
.bridge-side .b-go { font-size: 11px; border-bottom: 1px solid currentColor; width: max-content; padding-bottom: 1px; margin-top: auto; }
.bridge-side.locked { color: var(--ink-3); }
.bridge-core {
  border-left: 1px solid var(--line); border-right: 1px solid var(--line);
  display: flex; flex-direction: column; justify-content: center; align-items: center;
  text-align: center; padding: 14px 10px; gap: 11px; background: var(--surface-2);
}
.bridge-spine { display: flex; flex-direction: column; align-items: center; gap: 4px; margin: 2px 0; }
.bridge-spine i { display: block; width: 5px; height: 5px; border: 1px solid var(--brand-ink); background: var(--surface); }
.bridge-spine i:nth-child(2) { background: var(--brand-ink); }
.bridge-core p { font-size: 10px; line-height: 1.55; color: var(--ink-3); }
.bridge-core .btn { font-size: 10px; padding: 6px 8px; width: 100%; }

/* ── 四格资产入口 ─────────────────────────────────────────────────────────── */
.section-gap { margin-top: 38px; }
.entry-grid {
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--line); margin-bottom: 22px;
}
.entry-cell {
  display: flex; flex-direction: column; gap: 5px; padding: 18px 18px 16px;
  border-right: 1px solid var(--line); min-width: 0; background: var(--surface);
  color: inherit; text-decoration: none;
}
.entry-cell:last-child { border-right: 0; }
.entry-cell:hover { background: var(--surface-2); }
.entry-cell .ek { font-family: var(--font-mono); font-size: 9px; letter-spacing: .08em; color: var(--ink-3); }
.entry-cell b { font-family: var(--font-display); font-size: 20px; line-height: 1.15; font-weight: 600; }
.entry-cell .ev { font-family: var(--font-mono); font-size: 24px; line-height: 1; margin-top: 5px; }
.entry-cell small { font-size: 10px; color: var(--ink-3); line-height: 1.5; }
.entry-cell .ego { font-size: 10px; border-bottom: 1px solid currentColor; width: max-content; margin-top: auto; padding-top: 10px; }
.entry-cell.locked { background: var(--surface-2); color: var(--ink-3); cursor: not-allowed; }
.entry-cell.locked .ev { color: var(--ink-3); }
.entry-link { cursor: pointer; }

/* ── 两栏 ─────────────────────────────────────────────────────────────────── */
.two-col { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(300px, .82fr); gap: 40px; align-items: start; }
.stack > * + * { margin-top: 24px; }

/* ── 黑金牌匾 ─────────────────────────────────────────────────────────────── */
/* 2026-10-19 用户令：黑金牌匾必须是**真黑 + 金**，不能是青瓷墨绿（旧版用了 `--brand-ink`）。 */
.plaque { --pl-fg: #ffffff; --pl-fg-soft: rgba(255, 255, 255, .72); --pl-line: rgba(255, 255, 255, .24); --pl-code: rgba(255, 255, 255, .42); background: #101010; color: var(--pl-fg); border: 1px solid #101010; padding: 9px; position: relative; overflow: hidden; }
.plaque-inner { border: 1px solid rgba(232, 201, 138, .38); padding: 22px 24px; display: flex; flex-direction: column; gap: 24px; }
.plaque-top { display: flex; justify-content: space-between; gap: 16px; font-family: var(--font-mono); font-size: 9px; letter-spacing: .1em; color: var(--pl-fg-soft); }
.plaque-mid { display: grid; grid-template-columns: 1.1fr auto .9fr; gap: 24px; align-items: end; }
.plaque-mid .member-level { font-family: var(--font-display); font-size: 30px; line-height: 1.05; letter-spacing: -.02em; color: #e8c98a; }
.plaque-mid .member-level small { display: block; font-family: var(--font); font-size: 11px; letter-spacing: 0; color: var(--pl-fg-soft); margin-top: 6px; }
.plaque-num { font-family: var(--font-mono); font-size: 58px; line-height: .9; letter-spacing: -.07em; }
.plaque-num small { display: block; font-size: 9px; letter-spacing: .12em; color: var(--pl-fg-soft); margin-top: 7px; text-align: right; }
/* 等级牌匾动态分色（2026-10-19 用户令）：普通 / 银 / 金 / 黑金 / 青瓷绿。
   浅色底一律用青瓷绿文字（`#275e4e`）保证对比度；黑金是真黑 + 香槟金；顶级是青瓷绿底。 */
.plaque.pl-white { --pl-fg: #275e4e; --pl-fg-soft: rgba(39, 94, 78, .78); --pl-line: rgba(39, 94, 78, .22); --pl-code: rgba(39, 94, 78, .26); background: #f3f1ea; border-color: #f3f1ea; color: var(--pl-fg); }
.plaque.pl-white .plaque-inner { border-color: rgba(39, 94, 78, .28); }
.plaque.pl-white .plaque-top, .plaque.pl-white .plaque-mid .member-level small,
.plaque.pl-white .plaque-num small, .plaque.pl-white .plaque-bottom { color: rgba(39, 94, 78, .72); }
.plaque.pl-white .plaque-mid .member-level, .plaque.pl-white .balance { color: #275e4e; }
.plaque.pl-silver { --pl-fg: #275e4e; --pl-fg-soft: rgba(39, 94, 78, .78); --pl-line: rgba(39, 94, 78, .22); --pl-code: rgba(39, 94, 78, .28); background: linear-gradient(135deg, #e8ebef, #c3c9d1 60%, #aeb6c0); border-color: #aeb6c0; color: var(--pl-fg); }
.plaque.pl-silver .plaque-inner { border-color: rgba(39, 94, 78, .26); }
.plaque.pl-silver .plaque-top, .plaque.pl-silver .plaque-mid .member-level small,
.plaque.pl-silver .plaque-num small, .plaque.pl-silver .plaque-bottom { color: rgba(39, 94, 78, .72); }
.plaque.pl-silver .plaque-mid .member-level, .plaque.pl-silver .balance { color: #275e4e; }
/* 2026-10-19 用户令：金卡牌匾 = 深金底 + **白字**（用户明列金-白色文字）。 */
.plaque.pl-gold { --pl-fg: #ffffff; --pl-fg-soft: rgba(255, 255, 255, .8); --pl-line: rgba(255, 255, 255, .28); --pl-code: rgba(255, 255, 255, .46); background: linear-gradient(135deg, #9c7a1f, #7d5e14 60%, #634a0f); border-color: #634a0f; color: var(--pl-fg); }
.plaque.pl-gold .plaque-inner { border-color: rgba(255, 255, 255, .34); }
.plaque.pl-gold .plaque-top, .plaque.pl-gold .plaque-mid .member-level small,
.plaque.pl-gold .plaque-num small, .plaque.pl-gold .plaque-bottom { color: rgba(255, 255, 255, .8); }
.plaque.pl-gold .plaque-mid .member-level, .plaque.pl-gold .balance { color: #ffffff; }
.plaque.pl-platinum { --pl-fg: #275e4e; --pl-fg-soft: rgba(39, 94, 78, .78); --pl-line: rgba(39, 94, 78, .22); --pl-code: rgba(39, 94, 78, .28); background: linear-gradient(135deg, #eef3f8, #c2ccd8 60%, #9dabb9); border-color: #9dabb9; color: var(--pl-fg); }
.plaque.pl-platinum .plaque-inner { border-color: rgba(39, 94, 78, .26); }
.plaque.pl-platinum .plaque-top, .plaque.pl-platinum .plaque-mid .member-level small,
.plaque.pl-platinum .plaque-num small, .plaque.pl-platinum .plaque-bottom { color: rgba(39, 94, 78, .72); }
.plaque.pl-platinum .plaque-mid .member-level, .plaque.pl-platinum .balance { color: #275e4e; }
.plaque.pl-diamond { --pl-fg: #275e4e; --pl-fg-soft: rgba(39, 94, 78, .78); --pl-line: rgba(39, 94, 78, .22); --pl-code: rgba(39, 94, 78, .28); background: linear-gradient(135deg, #e6f4fc, #a9d3ec 60%, #6fa9cf); border-color: #6fa9cf; color: var(--pl-fg); }
.plaque.pl-diamond .plaque-inner { border-color: rgba(39, 94, 78, .3); }
.plaque.pl-diamond .plaque-top, .plaque.pl-diamond .plaque-mid .member-level small,
.plaque.pl-diamond .plaque-num small, .plaque.pl-diamond .plaque-bottom { color: rgba(39, 94, 78, .74); }
.plaque.pl-diamond .plaque-mid .member-level, .plaque.pl-diamond .balance { color: #275e4e; }
/* 2026-10-19 用户令：黑金牌匾 = 真黑底 + **全金文字**（不再只把等级名染金）。 */
.plaque.pl-black { --pl-fg: #e8c98a; --pl-fg-soft: rgba(232, 201, 138, .76); --pl-line: rgba(232, 201, 138, .32); --pl-code: rgba(232, 201, 138, .5); background: #101010; border-color: #101010; color: var(--pl-fg); }
.plaque.pl-black .plaque-inner { border-color: rgba(232, 201, 138, .38); }
.plaque.pl-black .plaque-mid .member-level { color: #e8c98a; }
/* 青瓷至尊牌匾 = 青瓷绿底 + **金文字**。 */
.plaque.pl-ink { --pl-fg: #d9c68c; --pl-fg-soft: rgba(217, 198, 140, .78); --pl-line: rgba(217, 198, 140, .3); --pl-code: rgba(217, 198, 140, .5); background: linear-gradient(135deg, #3b7966, #275e4e 60%, #1c4739); border-color: #1c4739; color: var(--pl-fg); }
.plaque.pl-ink .plaque-inner { border-color: rgba(217, 198, 140, .4); }
.plaque.pl-ink .plaque-mid .member-level { color: #d9c68c; }
.balance-btn { display: block; margin-left: auto; padding: 0; background: none; border: 0; font: inherit; cursor: pointer; text-align: right; }
.balance-btn:hover { text-decoration: underline; }
/* 充值弹窗 */
.recharge-dlg { max-width: 460px; }
.recharge-row { display: flex; gap: 8px; flex-wrap: wrap; margin: 10px 0; }
.recharge-bad { color: #b3261e; }
.recharge-logs { margin-top: 14px; border-top: 1px solid var(--line); padding-top: 10px; }
.recharge-log { display: flex; align-items: baseline; gap: 10px; padding: 6px 0; border-bottom: 1px dashed var(--line); font-size: 12px; }
.recharge-log b { margin-left: auto; color: var(--brand-ink); }
.recharge-log small { color: var(--ink-3); }
.plaque-mid .balance { text-align: right; font-family: var(--font-mono); font-size: 19px; }
.plaque-mid .balance small { display: block; font-family: var(--font); font-size: 10px; color: var(--pl-fg-soft); margin-bottom: 4px; }
.plaque-bottom { display: flex; align-items: center; justify-content: space-between; gap: 16px; border-top: 1px solid var(--pl-line); padding-top: 13px; }
.plaque-bottom .name { font-size: 12px; letter-spacing: .15em; color: var(--pl-fg); }
.barcode { display: flex; align-items: flex-end; gap: 2px; height: 18px; opacity: .75; }
.barcode i { display: block; width: 2px; background: var(--pl-code); }
.plaque-link { font-size: 11px; color: var(--pl-fg); border-bottom: 1px solid var(--pl-line); padding-bottom: 1px; text-decoration: none; }
.plaque-link:hover { color: var(--pl-fg); border-color: var(--pl-fg); }

/* ── 面板 ─────────────────────────────────────────────────────────────────── */
.panel { border: 1px solid var(--line); padding: 22px 24px; background: var(--surface); }
.panel.flush { border: 0; border-top: 1px solid var(--line); padding: 18px 0 0; background: transparent; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.panel-head h2 { font-family: var(--font-display); font-size: 24px; line-height: 1.15; font-weight: 600; letter-spacing: -.02em; }
.panel-head .kicker { margin-bottom: 5px; }
.panel-foot { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--line); }

/* ── 今日工作台 ───────────────────────────────────────────────────────────── */
.task-list { border-top: 1px solid var(--line); }
.task { display: grid; grid-template-columns: 72px minmax(0, 1fr) auto; gap: 14px; align-items: center; padding: 13px 0; border-bottom: 1px solid var(--line); }
.task:last-child { border-bottom: 0; }
.task .k { font-family: var(--font-mono); font-size: 9px; color: var(--ink-3); letter-spacing: .05em; }
.task .t { font-size: 13px; }
.task .t small { display: block; font-size: 10px; color: var(--ink-3); margin-top: 2px; }
.task .a { display: flex; align-items: center; gap: 10px; }
.task .a .tag { white-space: nowrap; }

/* ── 成长路径 ─────────────────────────────────────────────────────────────── */
.growth { display: grid; grid-template-columns: .9fr 1.15fr 1.1fr; border: 1px solid var(--line); }
.growth-step { padding: 18px; border-right: 1px solid var(--line); display: flex; flex-direction: column; gap: 7px; min-height: 104px; }
.growth-step:last-child { border-right: 0; }
.growth-step .g-no { font-family: var(--font-mono); font-size: 9px; color: var(--ink-3); }
.growth-step strong { font-size: 14px; }
.growth-step small { font-size: 10px; color: var(--ink-3); line-height: 1.5; }
.growth-step.done { background: var(--surface-2); }
.growth-step.current { background: var(--brand-ink); color: var(--surface); }
.growth-step.current .g-no, .growth-step.current small { color: rgba(255, 255, 255, 0.78); }
.growth-step a, .growth-step .g-link { margin-top: auto; font-size: 10px; border-bottom: 1px solid currentColor; width: max-content; padding: 0 0 1px; color: inherit; text-decoration: none; }

/* ── 账户资产 / 最近订单 ──────────────────────────────────────────────────── */
.ledger { border-top: 1px solid var(--line); padding-bottom: 24px; }
.ledger-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--line); align-items: center; color: inherit; text-decoration: none; }
.ledger-row:last-child { border-bottom: 0; }
.ledger-row .l { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.ledger-row .l b { font-weight: 500; font-size: 12px; }
.ledger-row .l small { font-family: var(--font-mono); font-size: 9px; color: var(--ink-3); white-space: nowrap; }
.ledger-row .v { font-family: var(--font-mono); font-size: 13px; text-align: right; }
.ledger-row .v small { display: block; font-size: 9px; color: var(--ink-3); }
.ledger-row.locked { color: var(--ink-3); cursor: not-allowed; }
.ledger-row.entry-link:hover { background: var(--surface-2); }
.empty-line { padding: 14px 0; font-size: 11px; color: var(--ink-3); }

/* ── 品牌资质 ─────────────────────────────────────────────────────────────── */
.brand-states { display: grid; grid-template-columns: .8fr 1fr 1fr 1.05fr; border: 1px solid var(--line); }
.brand-state { padding: 11px 10px; border-right: 1px solid var(--line); text-align: center; font-size: 10px; color: var(--ink-3); text-decoration: none; }
.brand-state:last-child { border-right: 0; }
.brand-state b { display: block; font-family: var(--font-mono); font-size: 9px; margin-bottom: 3px; color: var(--ink-3); }
.brand-state.on { background: var(--brand-ink); color: var(--surface); }
.brand-state.on b { color: rgba(255, 255, 255, 0.78); }
.brand-locked-note { line-height: 1.75; }
.brand-locked-actions { display: grid; gap: 7px; margin-top: 14px; }

/* ── 四格快捷入口 ─────────────────────────────────────────────────────────── */
.shortcut-grid { display: grid; grid-template-columns: 1.25fr .85fr 1fr .9fr; border: 1px solid var(--line); }
.shortcut { display: flex; flex-direction: column; gap: 9px; padding: 20px; border-right: 1px solid var(--line); min-height: 126px; color: inherit; text-decoration: none; }
.shortcut:last-child { border-right: 0; }
.shortcut:hover { background: var(--surface-2); }
.shortcut .s-no { font-family: var(--font-mono); font-size: 9px; color: var(--ink-3); }
.shortcut strong { font-family: var(--font-display); font-size: 19px; font-weight: 600; line-height: 1.15; }
.shortcut small { font-size: 10px; color: var(--ink-3); line-height: 1.5; }
.shortcut .s-go { margin-top: auto; font-family: var(--font-mono); font-size: 9px; letter-spacing: .05em; }
.shortcut.locked { color: var(--ink-3); cursor: not-allowed; }
.shortcut.locked:hover { background: transparent; }

/* ── 页脚 ─────────────────────────────────────────────────────────────────── */
.demo-note {
  margin-top: 44px; padding-top: 14px; border-top: 1px solid var(--line);
  font-family: var(--font-mono); font-size: 9px; color: var(--ink-3);
  display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap;
}

/* ── 编辑资料 / 券包 / 认证弹窗 ────────────────────────────────────────────── */
/* 2026-10-19 终局清道夫第 5 条：**不能用 `display:none`** —— 未渲染的 file input 无法被
   `input.click()` 唤起（Chromium/Edge 静默拒绝，实测）。1px 视觉隐藏但保留在渲染树里。 */
.file-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }
.edit-dlg { width: min(640px, 100%); }
.edit-avatar { display: flex; align-items: center; gap: 16px; margin-bottom: 18px; }
.avatar { width: 64px; height: 64px; flex: none; display: block; border-radius: 50%; background: var(--brand-tint); object-fit: cover; }
.form2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 14px; }
.f { display: flex; flex-direction: column; gap: 6px; margin-bottom: 14px; }
.f label { font-size: 11px; color: var(--ink-3); }
.f input, .f textarea, .f select {
  width: 100%; padding: 9px 10px; border: 1px solid var(--line-2);
  background: var(--surface); outline: none; font-size: 13px;
}
.f input:focus, .f textarea:focus, .f select:focus { border-color: var(--brand-ink); }
.f textarea { min-height: 76px; resize: vertical; }
.actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 18px; }
.dp-hint { font-size: 10px; color: var(--ink-3); }
.wallet-dot { font-style: normal; font-size: 1em; color: var(--danger); margin-left: 4px; }

.mc-scrim {
  position: fixed; inset: 0; z-index: 300; background: rgba(0, 0, 0, 0.42);
  display: flex; align-items: center; justify-content: center; padding: 20px;
}
.mc-dlg { width: min(560px, 100%); background: var(--surface); border: 1px solid var(--line-2); border-radius: var(--r2); box-shadow: 0 12px 48px rgba(0, 0, 0, 0.22); }
.mc-dlg-h {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px; border-bottom: 1px solid var(--line);
  font-family: var(--font-display); font-size: 19px; font-weight: 500;
}
.mc-x { font-size: 24px; line-height: 1; color: var(--ink-3); }
.mc-dlg-b { padding: 20px; }
.mc-state { font-family: var(--font-display); font-size: 32px; font-weight: 500; line-height: 1.1; color: var(--ink); }
.mc-why { margin-top: 16px; font-size: 15px; color: var(--ink-2); line-height: 1.7; }
.mc-kv { margin-top: 16px; border-top: 1px solid var(--line); }
.mc-kv div { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--line); align-items: baseline; }
.mc-kv dt { font-size: 11px; color: var(--ink-3); }
.mc-kv dd { margin: 0; font-size: 13px; color: var(--ink-2); word-break: break-all; }
.mc-license { margin-top: 16px; }
.mc-license img { display: block; width: 100%; max-width: 320px; border: 1px solid var(--line-2); background: var(--surface-2); }
.mc-license figcaption { margin-top: 6px; font-family: var(--font-mono); font-size: 10px; color: var(--ink-3); }

/* ── 身份卡（2026-10-19 用户令）────────────────────────────────────────────── */
.id-card {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) auto;
  gap: 24px;
  align-items: center;
  padding: 24px 26px;
  border: 1px solid var(--line);
  background: var(--surface);
  margin-bottom: 38px;
}
.id-avatar {
  width: 88px; height: 88px;
  object-fit: cover;
  border-radius: 50%;
  border: 1px solid var(--line-2);
  background: var(--surface-2);
}
.id-main { min-width: 0; }
.id-name {
  display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px;
  font-family: var(--font-display); font-size: 24px; font-weight: 600; color: #1c1c1a;
  line-height: 1.25; letter-spacing: -.01em;
}
.id-badge {
  font-family: var(--font); font-size: 11px; font-weight: 500; letter-spacing: .02em;
  padding: 2px 9px; border: 1px solid #e2dfd7; border-radius: 999px; color: #4a4a45; background: #f6f4ef;
  line-height: 1.5; white-space: nowrap;
}
/* 2026-10-19 用户令：头像旁等级标签的文字颜色跟牌匾同一套色卡 —— 普通/银/铂/钻 = 青瓷绿，金 = 白，黑金 = 金，青瓷至尊 = 金（深底保证对比度）。 */
.id-badge.lv-plain, .id-badge.lv-silver, .id-badge.lv-platinum, .id-badge.lv-diamond { color: #275e4e; background: #f4f2ec; border-color: rgba(39, 94, 78, .24); }
.id-badge.lv-gold { color: #fff; background: linear-gradient(135deg, #9c7a1f, #7d5e14); border-color: #7d5e14; }
.id-badge.lv-black { color: #e8c98a; background: #101010; border-color: #101010; }
.id-badge.lv-supreme { color: #d9c68c; background: linear-gradient(135deg, #3b7966, #275e4e 60%, #1c4739); border-color: #1c4739; }
.id-badge.merchant { background: transparent; border-color: #d8d4ca; color: #55554f; }
.id-meta {
  display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 24px;
  margin-top: 12px; font-size: 12px; line-height: 1.6; color: #8a8a82;
}
.id-meta b { font-family: var(--font-mono); font-weight: 500; color: #30302c; }
.id-bio { margin-top: 10px; font-size: 12.5px; line-height: 1.7; color: #55554f; }
.id-actions { display: flex; gap: 10px; justify-content: flex-end; }

/* ── 断点（照 A 版 1080 / 860 / 600） ─────────────────────────────────────── */
@media (max-width: 1080px) {
  .two-col { grid-template-columns: 1fr; }
}
@media (max-width: 860px) {
  .page-head { grid-template-columns: 1fr; }
  .head-actions { justify-content: flex-start; }
  .page-head h1 { font-size: 38px; }
  .bridge { grid-template-columns: 1fr; }
  .bridge-core {
    border-left: 0; border-right: 0; border-top: 1px solid var(--line);
    border-bottom: 1px solid var(--line); flex-direction: row; flex-wrap: wrap;
    justify-content: space-between; text-align: left;
  }
  .bridge-spine { flex-direction: row; }
  .bridge-core p { display: none; }
  .bridge-core .btn { width: auto; }
  .plaque-mid { grid-template-columns: 1fr auto; }
  .plaque-mid .balance { grid-column: 1 / -1; text-align: left; }
  .shortcut-grid { grid-template-columns: 1fr 1fr; }
  .shortcut:nth-child(2) { border-right: 0; }
  .shortcut:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .entry-grid { grid-template-columns: 1fr 1fr; }
  .entry-cell:nth-child(2) { border-right: 0; }
  .entry-cell:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .brand-cta, .join-banner { grid-template-columns: 1fr; }
  .brand-cta .cta-side { border-left: 0; border-top: 1px solid rgba(255, 255, 255, 0.24); padding-left: 0; padding-top: 18px; }
}
@media (max-width: 600px) {
  .page-head h1 { font-size: 32px; }
  .id-card { grid-template-columns: 1fr; }
  .id-avatar { width: 64px; height: 64px; }
  .id-actions { justify-content: flex-start; }
  .entry-grid { grid-template-columns: 1fr; }
  .entry-cell { border-right: 0; border-bottom: 1px solid var(--line); }
  .entry-cell:last-child { border-bottom: 0; }
  .growth { grid-template-columns: 1fr; }
  .growth-step { border-right: 0; border-bottom: 1px solid var(--line); }
  .growth-step:last-child { border-bottom: 0; }
  .shortcut-grid { grid-template-columns: 1fr; }
  .shortcut { border-right: 0; border-bottom: 1px solid var(--line); }
  .shortcut:last-child { border-bottom: 0; }
  .form2 { grid-template-columns: 1fr; }
  .plaque-bottom { flex-wrap: wrap; }
}
</style>
