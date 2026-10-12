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
      </div>
    </section>

    <div class="wrap">
      <h2 class="sec-h">本店商品</h2>

      <p v-if="state === 'loading'" class="note">正在载入店铺…</p>
      <p v-else-if="state === 'error'" class="note note-err">
        {{ errMsg }}
        <button type="button" class="retry" @click="loadShop">重试</button>
      </p>
      <div v-else-if="goods.length === 0" class="note note-empty">
        <p>这家店还没有上架商品。</p>
        <router-link class="retry" :to="getMerchantId() ? '/merchant' : '/customer'">先去逛逛别的</router-link>
      </div>
      <div v-else class="cards">
        <ProductCard v-for="g in goods" :key="g.Id" :goods="g" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getCustomerId, isLoggedIn } from '../api/auth'
import { follow, unfollow, followState } from '../api/shop'; import ProductCard from '../components/ProductCard.vue'   // ⚠️ 同行追加：本文件 `:262` / `:272` 等处被自身注释按行号引，能省一行就省一行（同 `HomePage.vue` 的 import 段）

const route = useRoute()
const router = useRouter()

// 看的是哪家店 —— **只认路由参数**。两个入口都只给这一个值：
// `ProductPage.vue` 的「进店逛逛」（`/shop?MerchantId=<卖家>`）与用户中心「店铺信息」格
// （`/shop?MerchantId=<自己的商家号>`）。⚠️ 店名不在这里 —— 它从商品行里的 `UserName` 取，见 `loadShop`。
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

const goods = ref([])
const shopName = ref('')
const following = ref(false)
// `null` = **还不知道**（未登录 ⇒ 拿不到）⇒ 头卡**不渲染**粉丝数那一段。
// ⚠️ 别拿 `0` 当「不知道」：那是「一个粉丝都没有」，一句假话。
const followers = ref(null)
const busy = ref(false)
const state = ref('loading')   // 'loading' | 'ready' | 'error'
const errMsg = ref('')

// 一次拉全量、前端按 `MerchantId` 过滤 —— 与主页 / 详情页**同一个端点、同一手法**（零后端改动）。
// ⚠️ 端点 `goodsSelectHome` 在 `AUTH_WHITELIST` 内 ⇒ 匿名进本页**不会**触发 401（同 `HomePage`）。
const loadShop = async () => {
  state.value = 'loading'
  errMsg.value = ''
  try {
    const res = await http.post(endpoints.goods.goodsSelectHome)
    const data = await res.json()
    if (!data.success) {
      state.value = 'error'
      errMsg.value = '店铺信息没取到：' + (data.msg || '服务端没有返回店铺商品')
      return
    }
    const mine = (data.list || []).filter((g) => String(g.MerchantId) === shopId)
    goods.value = mine
    // 店名**只有一个来源**：`goodsSelectHome` 每行的 `UserName`（照 `ProductPage.vue` 的
    // `currentItem.UserName` 同一来源）。⚠️ 一家**一件商品都没有**的店，本页拿不到它的名字
    // —— 那时店名留空、头卡退化成「店铺」/「商」，**不编一个**（`/shop` 只带 `MerchantId`，
    // 仓里没有第二个按商家号问店名的公开端点）。
    shopName.value = mine.length ? mine[0].UserName || '' : ''
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
/* 「关注」= 全页**唯一**一处实心块 —— skill 的「把胆量花在一个地方、其余保持安静」。
   未关注 = 实心 `--brand` + 白字（同 `FriendRoom.vue` 的 `.btn-solid`）；
   ⚠️ 「已关注」**退回描边**、不保持实心：它是个**状态**，不再是一次邀请 ——
   一个常亮的实心按钮会一直喊「点我」，而这里已经没有可做的动作。
   ⚠️ 两态都**不吃** `--accent`（朱砂在本仓只负责价格 / 促销 / 未读）。 */
.act-on { background: var(--brand); border-color: var(--brand); color: #fff; }
.act-on:hover:not(:disabled) { background: var(--brand-hover); border-color: var(--brand-hover); color: #fff; }

.wrap { max-width: 1180px; margin: 0 auto; padding: var(--s6) var(--s5) var(--s8); }
.sec-h { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med); line-height: 1.3; }

/* 三态（载入 / 报错 / 空）—— 空态是**一次邀请**（给一个真能走的去处），不是一句情绪；
   报错说清是什么没成、并把重试放在手边，不道歉也不含糊。 */
.note { margin-top: var(--s5); font-size: var(--fs-sm); color: var(--ink-3); }
.note-err { color: var(--danger); }
.note-empty p { margin-bottom: var(--s3); }
.retry { display: inline-block; height: 32px; line-height: 30px; padding: 0 var(--s3);
         border: 1px solid var(--line-2); border-radius: var(--r1); font-size: var(--fs-xs);
         color: var(--ink-2); text-decoration: none; vertical-align: middle; }
.retry:hover { border-color: var(--ink-3); color: var(--ink); }
.note-err .retry { margin-left: var(--s2); }

/* 商品格 —— `.cards` 是**栅格**（版面），`.card` 本体已搬进 `src/components/ProductCard.vue`
   （C3 块 5 · B 组续笔，2026-10-12）。
   ⚠️ 改前本段的注释写着「与 `ProductPage.vue` 的 `.card` 同一套形制」—— **那是错的**（改前实测：
   本页卡 1/1 图、无框、整值价；主页卡 4/3 图、有框、三段价），对照图见
   `docs/agent/evidence/c3-block5/out-b5d-cards-before.png`。收成一件之后那句话才成立 ——
   而且现在是**结构**保证的，不靠注释声明。 */
.cards { margin-top: var(--s5); display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s5); }
</style>
