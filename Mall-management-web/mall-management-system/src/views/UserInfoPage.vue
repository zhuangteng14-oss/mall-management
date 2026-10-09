<template>
  <!--
    D 笔 commit 7 —— 本页承载的是稿里的 `<main class="uc-main">` 那一支。
    基线：`docs/美化/pages/UserCenter-customer.html`（455 行，① – ⑥ 在 `:302-393`）
        + `docs/美化/pages/UserCenter-merchant.html`（434 行，① – ⑥ 在 `:290-374`）。
    ⚠️ 稿是**两份**（客户 / 商家），但左菜单那一支（`.uc-menu`，稿 `:291` / `:277`）**已由 C 笔
       落进 `UserCenter.vue`**（该文件 `:77-86` 引稿 `:141-151` / `:134-145`）⇒ 本页**只承载右栏**。
       `UserCenter.vue` 是多根 fragment（`.user-menu` + `<router-view>`），`AppShell.vue:361-365`
       的 `.user-page` 是 flex 行 ⇒ `<main class="uc-main">` 就是那一行的第二个 flex 子项。
    角色分支**只写在模板里**（判据与 `getUserData()` 的分支逐字同构）⇒ `<script setup>` 零改动。
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
    </section>

    <!-- ── ②–⑤ 客户 / 商家两版（稿分两份）──────────────────────────────────────────
         分支判据 `!customerid && merchantid` 与 `getUserData()` 里 `if (data.customerid) … else if
         (data.merchantid) …` **同一套**；未取到资料时（两个都空）落客户版（占比更高的一侧）。 -->
    <template v-if="!userInfo.customerid && userInfo.merchantid">

      <!-- ── ② 经营数据（商家版）—— 间距分隔（无线）+ 数字用色。稿 `:311-317` ─────────
           ⚠️ 稿的四个数（18 件 / 3 单 / 26 单 / 5 条）是它的示意值，无聚合端点 ⇒ **落 `—`**
              （用户裁决 F1 = 甲，与 commit 5 的 KPI 同一口径）。单位 `<small>` 随之不落 —— `—` 后面
              挂「件 / 单 / 条」没有意义。标签逐字保留。 -->
      <section
        class="assets"
        data-slot="预留 · 经营数据（待 C3〈商家中心〉）"
        title="待 C3〈商家中心〉——无聚合端点（N=4）；数字为示意；⚠️ 稿的 4 个数字为稿自注的示意值，本笔落 —（同 OrderCenter KPI 口径）"
      >
        <div><div class="v">—</div><div class="k">在售商品</div></div>
        <div><div class="v">—</div><div class="k">待发货</div></div>
        <div><div class="v">—</div><div class="k">本周订单</div></div>
        <div><div class="v">—</div><div class="k">未读消息</div></div>
      </section>

      <!-- ── ③ 待处理订单（商家版）—— 四格浅底色块。稿 `:320-333` ───────────────── -->
      <section
        class="sec"
        data-slot="预留 · 待处理订单四入口（待 C3〈订单〉）"
        title="待 C3〈订单〉——OrderDao.selectByMerchant 的 SELECT 不取状态筛选参数，无按状态分列端点（N=4）"
      >
        <div class="sec-h">
          <h2>待处理订单</h2>
          <button class="more" disabled title="订单中心在顶栏（/merchant/order），此处不做重复入口">去订单中心</button>
        </div>
        <!-- ⚠️ 角标只挂区块级：四格一律 disabled + title，不逐格加 data-slot（稿 `:326` 原话）。 -->
        <div class="tiles">
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待发货</b><span>买家已付款待处理</span></button>
          <button disabled title="待 C3〈订单〉——无按状态筛选端点"><b>待收货</b><span>已发出在途</span></button>
          <button disabled title="待 C3〈订单〉——OrderState 无退款位，加它是第 4 个状态位（≥6 处）"><b>退款申请</b><span>买家发起的售后</span></button>
          <button disabled title="待 C3〈订单〉——无评价模块端点"><b>待评价</b><span>已完成待买家评价</span></button>
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

      <!-- ── ② 我的资产（客户版）—— 间距分隔（无线）+ 数字用色。稿 `:323-329` ───────
           ⚠️ 与商家版同一条：稿那 4 个数（1,280 / 3 张 / ¥286.50 / 12 件）是示意值，
              无聚合端点 ⇒ **落 `—`**（F1 = 甲）。单位 `<small>张/¥/件</small>` 随之不落。 -->
      <section
        class="assets"
        data-slot="预留 · 我的资产（待 C3〈个人中心〉）"
        title="待 C3〈个人中心〉——积分/优惠券/余额均无聚合端点（N=4）；数字为示意；⚠️ 稿的 4 个数字为稿自注的示意值，本笔落 —（同 OrderCenter KPI 口径）"
      >
        <div><div class="v">—</div><div class="k">积分</div></div>
        <div><div class="v">—</div><div class="k">优惠券</div></div>
        <div><div class="v">—</div><div class="k">余额</div></div>
        <div><div class="v">—</div><div class="k">我的收藏</div></div>
      </section>

      <!-- ── ③ 我的订单（客户版）—— 四格浅底色块。稿 `:332-345` ─────────────────── -->
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
        <button class="btn btn-primary" @click="saveUserInfo">保存个人信息</button>
      </div>
    </section>

  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getUsername, isLoggedIn } from '../api/auth'

const userInfo = ref({})
const idType = ref('')
const idValue = ref('')
const fileInput = ref(null)

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

    if (data.customerid) {
      idType.value = '客户 ID'
      idValue.value = data.customerid
    } else if (data.merchantid) {
      idType.value = '商家 ID'
      idValue.value = data.merchantid
    }
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
        与 `AppShell.vue:361-365`）⇒ 未搬。
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
   ⚠️ **本仓没有稿那条全局 `body{}`**（`AppShell.vue:257-260` 明写：全仓一条字体声明都没有、
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
.f input, .f textarea { width: 100%; padding: 0 var(--s3); border: 1px solid var(--line-2); border-radius: var(--r2);
                        background: var(--surface); transition: border-color .15s, box-shadow .15s; }
.f input { height: 46px; }
.f textarea { min-height: 96px; padding: var(--s3); resize: vertical; line-height: 1.7; }
.f input:focus, .f textarea:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
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
</style>
