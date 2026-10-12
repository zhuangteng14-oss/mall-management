<template>
  <div class="sk-page">
    <!-- ── 自带顶栏 ───────────────────────────────────────────────────────────────
         本页路由带 `meta.public: true` ⇒ **不进壳**：没有壳的左菜单，也没有壳顶栏
         （与 `ProductPage.vue` / `ShopHome.vue` 同款做法）。
         ⚠️ **与商品详情页的顶栏有一处有意不同：本页没有搜索框**。那一页的框是
         `PENDING_SEARCH_KEYWORD` 那条「壳内搜索流」的入口，本页没有那条流 ⇒
         放一个点了会跳走的框 = 画一个假控件（判据与 `ShopHome.vue` 的顶栏注释逐字相同）。 -->
    <nav class="nav-bar">
      <button type="button" class="nav-back" @click="goBack">‹ 返回</button>
      <router-link class="brand" :to="homePath">商城</router-link>
      <div class="nav-right" @click="goUserCenter">{{ isLogin ? '用户中心' : '登录 / 注册' }}</div>
    </nav>

    <!-- ── 页头：一条整幅横带（**本页唯一「敢用块面」的地方**）─────────────────────────
         ⚠️ 三个「不做」各对着一条 AI 默认样（判据见 `tokens.css` 用法规矩与本仓历笔登记的
            「AI 默认样」清单）：不做白底圆角卡墙、不做全大写 eyebrow、不做「A · B · C」中圆点串。
         底换 `--cin-600`（朱砂**实底**，2026-10-12 用户配色令）：秒杀页 = 朱砂阶梯**深**档，与首页模块的 `--cin-100` **浅**面刻意不同色阶（硬约束：首页红底 ≠ 秒杀页红底）。 -->
    <section class="band">
      <div class="band-in">
        <nav class="crumb">
          <router-link :to="homePath">首页</router-link>
          <i class="sp">/</i><span>限时秒杀</span>
        </nav>
        <div class="hd">
          <span class="hd-nm">限时秒杀</span>
          <span class="hd-sub">
            共 <b>{{ activities.length }}</b> 场<template v-if="liveCount">，其中 <b>{{ liveCount }}</b> 场进行中</template>
          </span>
        </div>
      </div>
    </section>

    <main class="wrap">
      <!-- ⚠️ 2026-10-12 补：点「已售完 / 未开始」那些行时给一句**红字**。
           与卡上那枚斜向贴图是**同一件事的两个面** —— 贴图说「现在不行」，这句说「为什么点了没动」
           （卡是 `<router-link>`，售完时点击被拦下，没有这句就只剩「点了没反应」）。
           ⚠️ **必须落在这条 `v-if / v-else` 链之外**（放链中间会把下面的 `<ul v-else>` 挂到它身上，
              那时整张列表会变成「有点击提示才显示」）。
           一次性、不堆叠：每次点击**替换**内容，3 秒后自己消失（定时器在卸载时清掉）。 -->
      <p v-if="notice" class="notice">{{ notice }}</p>

      <!-- ⚠️ **加载中 / 未登录 / 空 三态分开**，不合成一句「暂无数据」：
           未登录是「你看不到」、空是「现在没有」—— 同一句话说两件事是错的（本仓既有口径）。 -->
      <p v-if="!isLogin" class="tip">
        登录后即可查看进行中的秒杀活动 ——
        <router-link class="tip-a" to="/login">去登录</router-link>
      </p>
      <p v-else-if="loading" class="tip">正在加载秒杀活动…</p>
      <p v-else-if="!activities.length" class="tip">当前没有进行中或即将开始的秒杀活动。</p>

      <ul v-else class="list">
        <li v-for="row in activities" :key="row.ActivityId" class="item" :class="{ sold: row.SoldOut }">
          <router-link class="item-in" :to="productLink(row)" @click="onItemClick($event, row)">
            <span class="ph">
              <img v-if="row.GoodImg" :src="imgSrc(row.GoodImg)" :alt="row.Name || row.GoodName" />
              <span v-else class="ph-none">无图</span>
            </span>
            <span class="tx">
              <span class="nm">{{ row.Name || row.GoodName }}</span>
              <span class="shop">{{ row.MerchantAccount }}</span>
              <span class="meta">
                <!-- ⚠️ 阶段（`Stage`）由**后端按库时钟现算**，前端不自己比时间 —— 浏览器钟
                     与库钟差一个时区，自己算会整整错 8 小时且**不报错**（后端 `SeckillDao.stage`）。 -->
                <span class="chip" :class="stageClass(row.Stage)">{{ stageLabel(row.Stage) }}</span>
                <span v-if="row.SoldOut" class="chip soldout">已售完</span>
                <span v-else-if="row.SoldLeft !== null && row.SoldLeft !== undefined" class="left">
                  剩余 <b>{{ row.SoldLeft }}</b> 件
                </span>
                <span v-else class="left">不限量</span>
              </span>
            </span>
            <span class="pr">
              <b class="now">¥{{ money(row.SeckillPrice) }}</b>
              <s class="was">¥{{ money(row.OriginalPrice) }}</s>
            </span>
          </router-link>
          <!-- ⚠️ 售完那枚**斜向贴图**：压在整卡上、不接收事件（`pointer-events: none`）——
               它只是「这张卡现在不能成交」的可视化，点击该给红字的那条路由仍归 `.item-in` / `.grab`。 -->
          <span v-if="row.SoldOut" class="stamp" aria-hidden="true">已售完</span>
          <!-- ⚠️ **抢购这一层**（2026-10-12 补）：此前每一行整卡就是一个 `<router-link>`，
               全仓**没有**「抢购」按钮。「真扣减（库存 / 名额）留块 7」是既定安排 ⇒ 这里打通的是
               **「能点到成交入口」**这一层：`live` + 未售完 ⇒ 落商品详情页（那里有「立即购买」的
               真链路）；`upcoming` / 售完 ⇒ **有意不可成交**，点了给红字说明（不做点了没反应的死钮）。 -->
          <button
            type="button"
            class="grab"
            :class="{ off: row.Stage !== 'live' || row.SoldOut }"
            :title="grabTitle(row)"
            @click="onGrab(row)"
          >{{ grabLabel(row) }}</button>
        </li>
      </ul>
    </main>
  </div>
</template>

<script setup>
// src/views/SeckillPage.vue —— 用户端「限时秒杀」（C3 块 5 · commit 4，2026-10-12）。
//
// ⚠️ **本页与主页的秒杀模块同源**：都读 `/marketing/seckill/live`（`SeckillDao.selectLive`）。
//    后端已经把**已结束 / 已停用 / 商品下架**的行挡掉了 ⇒ 见到一行就等于「这一场此刻仍然挂着」，
//    本页**不做任何二次过滤**（在前端再筛一遍 = 造第二个真源）。
//
// ⚠️ **阶段（`Stage`）与售罄（`SoldOut`）都由后端说了算**：
//    · `Stage` 是 SQL 里按**库时钟 `NOW()`** 现算的（`signup` / `upcoming` / `live` / `ended`）；
//    · `SoldOut` 是 Java 侧拿两个**数字**比出来的（不是 SQL 里的 `0/1` —— 那个在 JS 里
//      非空字符串 `'0'` 是**真值**，会满屏「已售完」）。
//    本页两者都**只读不算**。
//
// ⚠️ **登录才发请求**：`/marketing/seckill/live` **不在** `AUTH_WHITELIST` 内（后端刻意没放宽
//    公开面，口径同 `/marketing/subsidy/goods`）⇒ 匿名发它必 401，而 `http.js` 的
//    `handleUnauthorized` 会**把公开页踢回登录页**。故先判 `isLoggedIn()`（同 `ProductPage` 的领券块）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { seckillLive } from '../api/marketing'
import { getMerchantId, isLoggedIn } from '../api/auth'

const router = useRouter()

const rows = ref([])
const loading = ref(false)

const isLogin = computed(() => isLoggedIn())
/** 未登录时返回落点按角色分（同本仓其它公开页的「首页」链接）。 */
const homePath = computed(() => (getMerchantId() ? '/merchant' : '/customer'))

/**
 * ⚠️ **`signup` 那一档不显示**：报名期的活动对**买家**还不存在（`SeckillDao.stage` 的注释：
 *    主页不显示）—— 本页与主页同口径，只留 `live` 与 `upcoming`。
 */
const activities = computed(() => rows.value.filter((r) => r.Stage !== 'signup'))
const liveCount = computed(() => activities.value.filter((r) => r.Stage === 'live').length)

const STAGE_LABELS = { upcoming: '即将开始', live: '进行中', ended: '已结束' }
const stageLabel = (s) => STAGE_LABELS[s] || '进行中'
const stageClass = (s) => (s === 'upcoming' ? 'soon' : 'on')

const money = (v) => Number(v || 0).toFixed(2)

/** 图片：本仓商品图存的是相对路径，与 `ProductPage` / `HomePage` 同一处理（裸路径直接当 src）。 */
const imgSrc = (u) => String(u)

/** 去商品详情页 —— `query` 四件套与三处商品卡**逐字相同**（那边是 `ProductCard.vue` 的口径）。 */
function productLink(row) {
  return {
    path: '/product',
    query: {
      MerchantId: row.MerchantAccount,
      GoodName: row.Name || row.GoodName,
      GoodType: row.GoodType,
      Id: row.GoodsId,
    },
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push(homePath.value)
}

// ── 抢购这一层 + 红字提示（C3 块 5 · commit 5 续笔，2026-10-12）────────────────────────
// ⚠️ **真扣减（扣 `SoldStock` / 扣名额）留块 7** —— 本段**不碰任何库存**，只把「能点到成交入口」
//    这一层补上：`live` + 未售完 ⇒ 落商品详情页（那里有唯一的真成交链路
//    `openPopup → confirmPurchase → handlePay → order.orderAdd`）。
// ⚠️ **售完 / 未开始时不给「点了没反应」的死钮**：按钮看着是灰的，点下去给一句**红字**
//    （`.notice`）说明为什么 —— 不成交是**有意**的（后端 `SoldOut` 就是最终判据），
//    但「有意」不等于「不说」。
const notice = ref('')
let noticeTimer = null
const say = (text) => {
  notice.value = text
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = ''; noticeTimer = null }, 3000)
}
onBeforeUnmount(() => { if (noticeTimer) clearTimeout(noticeTimer) })

const grabLabel = (row) => (row.SoldOut ? '已售完' : row.Stage !== 'live' ? '未开始' : '立即抢购')
const grabTitle = (row) => {
  if (row.SoldOut) return '这一场已经抢完 —— 库存 / 名额由后端 SoldOut 判定（真扣减留块 7）'
  if (row.Stage !== 'live') return '这场还没开始（阶段由后端按库时钟现算）'
  return '去商品详情页下单（本页不扣库存；真扣减留块 7）'
}

/** 整卡点击：**售完那一档拦下跳转**并给红字（`.item-in` 是 `<router-link>`，不拦就跳走了）。 */
function onItemClick(e, row) {
  if (row.SoldOut) {
    e.preventDefault()
    say('这件已经抢完了，看看别的场次吧')
  }
}

function onGrab(row) {
  if (row.SoldOut) { say('这件已经抢完了，看看别的场次吧'); return }
  if (row.Stage !== 'live') { say('这场还没开始，到点再来'); return }
  router.push(productLink(row))
}

function goUserCenter() {
  router.push(isLoggedIn() ? (getMerchantId() ? '/merchant/user' : '/customer/user') : '/login')
}

async function loadLive() {
  if (!isLoggedIn()) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const data = await seckillLive({ limit: 100 })
    rows.value = data && data.success && Array.isArray(data.list) ? data.list : []
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadLive)
</script>

<style scoped>
.sk-page {
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
}

/* ── 顶栏（与 `ShopHome.vue` / `ProductPage.vue` 同形制：一条 60px 高的横条，左侧返回）── */
.nav-bar { display: flex; align-items: center; gap: var(--s4); height: 60px; padding: 0 var(--s7);
           background: var(--nav-bg); border-bottom: 1px solid var(--line); }
.nav-back { font-size: var(--fs-md); color: var(--ink-2); padding: 0 var(--s2); white-space: nowrap; }
.brand { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-semi);
         color: var(--brand-ink); white-space: nowrap; text-decoration: none; }
.nav-right { margin-left: auto; color: var(--nav-tx); font-weight: var(--fw-med); cursor: pointer; white-space: nowrap; }

/* ── 页头横带（本页唯一一处块面；底取 `--cin-600` 朱砂实底，理由见模板注释）── */
.band { background: var(--cin-600); border-bottom: 1px solid var(--cin-700); }   /* ⚠️ 2026-10-12 用户配色令：实底红（白字 5.66:1 / `--cin-50` 5.24:1）；与首页模块的 `--cin-100` 浅面**不同色阶**（硬约束） */
.band-in { max-width: 1180px; margin: 0 auto; padding: var(--s4) var(--s5) var(--s5); }
.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--cin-50); }
.crumb a { color: inherit; text-decoration: none; }
.crumb a:hover { color: var(--bg); }   /* ⚠️ 底是朱砂实底 ⇒ hover 取白；原来那条 `--brand-ink`（青瓷绿深）压红底只有 1.5:1 左右 */
.crumb .sp { font-style: normal; padding: 0 var(--s2); }
.hd { margin-top: var(--s3); display: flex; align-items: baseline; gap: var(--s4); }
.hd-nm { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med); color: var(--bg); }
.hd-sub { font-size: var(--fs-xs); color: var(--cin-50); }
.hd-sub b { color: var(--bg); font-weight: var(--fw-med); font-variant-numeric: tabular-nums; }

.wrap { max-width: 1180px; margin: 0 auto; padding: var(--s6) var(--s5) var(--s8); }
.tip { font-size: var(--fs-sm); color: var(--ink-3); padding: var(--s6) 0; }
.tip-a { color: var(--brand-ink); }

.list { list-style: none; display: grid; gap: var(--s4); }
.item { border: 1px solid var(--line); border-radius: var(--r2); background: var(--surface); }   /* ⚠️ 2026-10-12（补笔）改 `--card` → `--surface`：`--card` **全仓没有定义**（`grep -rn -- '--card:' src/` 零命中）⇒ 该声明在 computed-value 期被判无效、`background` 退回 `transparent`（白底页上看不出来，所以一直没被发现）。同类还有下面 `.chip.soldout` 的 `color` 与本页 `.grab` 的 `color`，三处一并订正 */
.item-in { display: flex; align-items: center; gap: var(--s4); padding: var(--s4); text-decoration: none; color: inherit; }
.item-in:hover .nm { color: var(--brand-ink); }

.ph { flex: 0 0 auto; width: 96px; height: 72px; border-radius: var(--r1); overflow: hidden;
      background: var(--zone); display: flex; align-items: center; justify-content: center; }
.ph img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ph-none { font-size: var(--fs-xs); color: var(--ink-3); }

.tx { min-width: 0; display: flex; flex-direction: column; gap: var(--s1); }
.nm { font-size: var(--fs-sm); font-weight: var(--fw-med); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shop { font-size: var(--fs-xs); color: var(--ink-3); }
.meta { display: flex; align-items: center; gap: var(--s3); margin-top: var(--s1); }

/* ⚠️ 阶段与售罄**都不吃 `--accent`** —— 朱砂在本仓只负责促销 / 未读 / **秒杀标识**（`tokens.css:34-42`），
   2026-10-12 用户配色令起**秒杀价**改用青瓷绿（`--brand-ink`）⇒ 同一张卡里「绿价 + 红标识」各司其职。 */
.chip { font-size: var(--fs-xs); padding: 0 var(--s2); border-radius: var(--r1); white-space: nowrap; }
.chip.on { background: var(--brand-soft); color: var(--brand-ink); }
.chip.soon { background: var(--zone); color: var(--ink-2); }
.chip.soldout { background: var(--ink-3); color: var(--bg); }   /* ⚠️ 2026-10-12（补笔）：原 `var(--card)` 同 `.item` 那条 —— 未定义 ⇒ `color` 退回继承（`.chip` 的父级是 `--ink-2` 档）⇒ 深灰字压在 `--ink-3` 灰底上、对比度掉到 2 左右。改成 `--bg`（白），与 `GoodsUp.vue:779` / `GoodsSelect.vue:473` 那批「实底按钮上的字」同一口径 */
.left { font-size: var(--fs-xs); color: var(--ink-2); }
.left b { font-variant-numeric: tabular-nums; }

.pr { margin-left: auto; flex: 0 0 auto; text-align: right; display: flex; flex-direction: column; gap: var(--s1); }
.now { color: var(--brand-ink); font-size: var(--fs-lg); font-variant-numeric: tabular-nums; }   /* ⚠️ 2026-10-12 用户配色令：**秒杀价 = 青瓷绿**（与普通商品价格色对调）；白卡上 7.51:1 */
.was { color: var(--ink-3); font-size: var(--fs-xs); }

/* ══ 2026-10-12〈售完态 + 抢购这一层〉════════════════════════════════════════════════
   ⚠️ 本段四件是**一件事的四层**，逐层对着用户清单那一项：「灰色 + 红斜『已售完』贴图 + 禁点 + 点击红字」。
      少任何一层都会变成半句话：只灰不拦 = 点进去还能下单；只拦不说 = 点了没反应。 */

/* ① 行化为「卡片 + 抢购钮」两段式 —— `.item` 从纯容器变成 flex 行（`.item-in` 吃掉余量）。 */
.item { position: relative; display: flex; align-items: center; }
.item-in { flex: 1 1 auto; min-width: 0; }

/* ② **整卡置灰**：不吃 `filter: grayscale()`（会把商品图也一起洗成灰，图是商家资产、不该被页面改色），
      走**降不透明度 + 底色压暗**这两条只作用于本页自己的漆面。 */
.item.sold { background: var(--zone); }
.item.sold .item-in { opacity: .55; }
/* ⚠️ 售完时**不禁用** `<router-link>`（`:to` 仍在），拦的是 `onItemClick` 里的 `preventDefault`
      —— 用 `disabled` / 拿掉 `:to` 都做不到「点击给红字」（一个不触发 click、一个连光标语义都丢了）。 */
.item.sold .item-in { cursor: not-allowed; }

/* ③ **斜向「已售完」贴图**：压整卡、`pointer-events: none`（它只是漆面，不参与点击路由）。
      ⚠️ 用**文字 + 边框**而不是图片：本仓自托管字体子集里没有贴图，画一张 PNG 属于新增二进制资产；
        而这一枚的全部信息就是「已售完」三个字。⚠️ 旋转用 `transform` 而不是把字斜着排（`skew` 会把
        字形也压扁）。⚠️ 颜色取 `--accent`（朱砂）—— 「售完」在本页是**状态**不是价格，但
        `tokens.css:34-42` 里 `--accent` 的用法域含「促销」，而这一层要说的正是「促销没了」，
        同色反而是最省的表达（另起一个红会与下方 `.was` 的灰打架）。 */
.stamp { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%) rotate(-14deg);
         pointer-events: none; user-select: none; white-space: nowrap;
         padding: 2px var(--s4); border: 2px solid var(--accent); border-radius: var(--r1);
         color: var(--accent); font-size: var(--fs-md); font-weight: var(--fw-semi); letter-spacing: .12em; }

/* ④ **抢购钮**（`.grab`）—— 本页唯一的成交入口按钮。`.off` = 售完 / 未开始：**看着是灰的、
      仍然可点**（点了给红字，理由见模板注释）。⚠️ 与 `.chip` 一族同尺寸档，但它是**动作**不是标记
      ⇒ 有边框与底色，读起来是按钮。 */
.grab { flex: 0 0 auto; margin-right: var(--s4); height: 34px; padding: 0 var(--s5);
        border: 1px solid var(--accent); border-radius: var(--r1); background: var(--accent);
        color: var(--bg); font-size: var(--fs-sm); font-weight: var(--fw-med); cursor: pointer; }   /* ⚠️ 实底按钮上的字取 `--bg`（白）—— 与 `GoodsUp.vue:839` 那批同口径；写成 `--card` 会退回继承（`--ink` 近黑压朱砂，对比度 ~2.5:1） */
.grab:hover { filter: brightness(1.06); }
.grab.off { border-color: var(--line-2); background: var(--surface); color: var(--ink-3); cursor: not-allowed; }
.grab.off:hover { filter: none; }

/* ⑤ **点击红字**（`.notice`）—— 3 秒自消（定时器在 `onBeforeUnmount` 清掉）。
      ⚠️ 这里**可以**吃 `--accent`：它是一次性的动作反馈，不与卡上任何常驻标记同屏争强调。 */
.notice { margin-bottom: var(--s4); padding: var(--s2) var(--s4); border-radius: var(--r1);
          background: var(--accent-soft); color: var(--accent); font-size: var(--fs-sm); }
</style>
