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

    <main class="wrap">
      <!-- A 版线框（BUYER / SECKILL SCHEDULE）：页头 + 下一场倒计时 + 时间轴。
           数据链、阶段判据、售罄拦截、抢购落点全部照旧，只换呈现（2026-10-19 极速 UI 重构）。 -->
      <section class="page-head">
        <div>
          <div class="kicker">BUYER / SECKILL SCHEDULE</div>
          <h1>秒杀日程</h1>
          <p>时间、场次、库存与抢购动作落在同一条时间线上。阶段与售罄由后端按库时钟判定，本页只读不算。</p>
        </div>
        <div class="head-actions">
          <router-link class="btn" :to="homePath">继续逛逛</router-link>
        </div>
      </section>

      <!-- 下一场 / 进行中：A 版 sk-clock 双栏。没有任何活动时整块不画。 -->
      <section v-if="hero" class="sk-clock">
        <div class="clock-main">
          <div class="kicker">{{ hero.Stage === 'live' ? 'LIVE NOW / 正在抢购' : 'NEXT DROP / 即将开抢' }}</div>
          <h2>{{ hero.Name || hero.GoodName }}</h2>
          <p class="clock-sub">
            {{ timeOf(hero) }} 场 · {{ hero.MerchantAccount }} ·
            <template v-if="hero.SoldLeft !== null && hero.SoldLeft !== undefined">剩余 {{ hero.SoldLeft }} 件</template>
            <template v-else>不限量</template>
          </p>
          <div class="clock-num" v-if="countdown">
            <div><b>{{ countdown.h }}</b><small>HOURS</small></div>
            <div><b>{{ countdown.m }}</b><small>MIN</small></div>
            <div><b>{{ countdown.s }}</b><small>SEC</small></div>
          </div>
          <p class="clock-note">
            {{ hero.Stage === 'live' ? '本场进行中，售罄即止。' : '倒计时结束即开抢；提醒入口在消息中心。' }}
          </p>
        </div>
        <div class="clock-side">
          <div class="cap"><span>{{ hero.Stage === 'live' ? 'SOLD / 已抢' : 'STOCK / 库存' }}</span><span>{{ stockCap(hero) }}</span></div>
          <div class="bar" v-if="pctOf(hero) !== null"><i :style="{ width: pctOf(hero) + '%' }"></i></div>
          <div class="remain">{{ pctOf(hero) === null ? '—' : pctOf(hero) + '%' }}</div>
          <div class="hero-price"><b>¥{{ money(hero.SeckillPrice) }}</b><s>¥{{ money(hero.OriginalPrice) }}</s></div>
          <button type="button" class="btn-hero" :class="{ off: hero.Stage !== 'live' || hero.SoldOut }" @click="onGrab(hero)">
            {{ grabLabel(hero) }}
          </button>
        </div>
      </section>

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

      <div v-else class="sk-timeline">
        <div class="sk-now">
          <div class="n-time">NOW</div>
          <div class="n-body">
            共 <b>{{ activities.length }}</b> 场 · 进行中 <b>{{ liveCount }}</b> 场 · 按开始时间升序
          </div>
        </div>
        <div v-for="row in activities" :key="row.ActivityId" class="sk-row" :class="[row.Stage, { sold: row.SoldOut }]">
          <div class="sk-time">
            <b>{{ timeOf(row) }}</b>
            <span>{{ stageLabel(row.Stage) }}<template v-if="endOf(row)"> · {{ endOf(row) }} 结束</template></span>
          </div>
          <div class="sk-body">
            <router-link class="thumb" :to="productLink(row)" @click="onItemClick($event, row)">
              <img v-if="row.GoodImg" :src="imgSrc(row.GoodImg)" :alt="row.Name || row.GoodName" />
              <span v-else class="ph-none">无图</span>
            </router-link>
            <div class="info">
              <router-link class="nm" :to="productLink(row)" @click="onItemClick($event, row)">{{ row.Name || row.GoodName }}</router-link>
              <div class="meta">{{ row.MerchantAccount }}</div>
              <div class="price"><b>¥{{ money(row.SeckillPrice) }}</b><s>¥{{ money(row.OriginalPrice) }}</s></div>
              <template v-if="pctOf(row) !== null">
                <div class="bar"><i :style="{ width: pctOf(row) + '%' }"></i></div>
                <div class="bar-meta"><span>{{ stockCap(row) }}</span><span>{{ pctOf(row) }}%</span></div>
              </template>
              <div class="bar-meta" v-else>
                <span>{{ row.SoldOut ? '已售完' : '不限量' }}</span><span>—</span>
              </div>
            </div>
            <div class="act">
              <span class="chip" :class="row.SoldOut ? 'soldout' : stageClass(row.Stage)">
                {{ row.SoldOut ? '已售完' : stageLabel(row.Stage) }}
              </span>
              <button
                type="button"
                class="grab"
                :class="{ off: row.Stage !== 'live' || row.SoldOut }"
                :title="grabTitle(row)"
                @click="onGrab(row)"
              >{{ grabLabel(row) }}</button>
            </div>
          </div>
          <!-- ⚠️ 售完那枚**斜向贴图**：压在整行上、不接收事件（`pointer-events: none`）——
               它只是「这张卡现在不能成交」的可视化，点击该给红字的那条路由仍归 `.thumb` / `.nm` / `.grab`。 -->
          <span v-if="row.SoldOut" class="stamp" aria-hidden="true">已售完</span>
        </div>
      </div>
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

/* ── 时间轴（A 版日程表）────────────────────────────────────────────────────
   ⚠️ `StartAt / EndAt` 是库时钟（UTC）裸文本（`DATE_FORMAT` 输出、不带时区）
   ⇒ 必须补 `Z` 再解析，否则会被当成本地时间、整整错 8 小时（与 `HomePage.flashTs` 同口径）。
   ⚠️ 时间轴上的时刻**只用于显示**；「现在是不是 live」仍由后端 `Stage` 说了算。 */
const tsOf = (s) => {
  if (!s) return null
  const d = new Date(String(s).replace(' ', 'T') + 'Z')
  return isNaN(d.getTime()) ? null : d.getTime()
}
const hhmm = (s) => (s ? String(s).slice(11, 16) : '—')
const timeOf = (row) => hhmm(row.StartAt)
const endOf = (row) => hhmm(row.EndAt)

/** 下一场倒计时：进行中的瞄 `EndAt`，否则瞄最近的 `StartAt`。 */
const now = ref(Date.now())
let ticker = null
const hero = computed(
  () => activities.value.find((r) => r.Stage === 'live') || activities.value.find((r) => r.Stage === 'upcoming') || null
)
const heroTarget = computed(() => {
  if (!hero.value) return null
  return tsOf(hero.value.Stage === 'live' ? hero.value.EndAt : hero.value.StartAt)
})
const countdown = computed(() => {
  const t = heroTarget.value
  if (!t) return null
  const left = Math.max(0, Math.floor((t - now.value) / 1000))
  const p = (n) => String(n).padStart(2, '0')
  return { h: p(Math.floor(left / 3600)), m: p(Math.floor((left % 3600) / 60)), s: p(left % 60) }
})

/** 已抢百分比 —— 只认真实 `SoldStock / TotalStock`；`TotalStock = 0` 返回 null（不画进度）。 */
const pctOf = (row) => {
  const total = Number(row && row.TotalStock) || 0
  const sold = Number(row && row.SoldStock) || 0
  if (total <= 0) return null
  return Math.max(0, Math.min(100, Math.round((sold / total) * 100)))
}
const stockCap = (row) => {
  const total = Number(row && row.TotalStock) || 0
  const sold = Number(row && row.SoldStock) || 0
  if (total <= 0) return '不限量'
  return `已抢 ${sold} / ${total}`
}

/** 去商品详情页 —— `query` 四件套与三处商品卡**逐字相同**（那边是 `ProductCard.vue` 的口径），**外加第五个键 `ActivityId`**（C3 块 7 · commit 2c，2026-10-14：秒杀页**独有** —— 商品详情页拿它当「这单是秒杀单」的声明，下单时转手发给 `/order/orderAdd` 去扣 `SoldStock`；四件套是**所有入口**都有的，只有这一个键区分得出「从秒杀页来的」）。 */
function productLink(row) {
  return {
    path: '/product',
    query: {
      MerchantId: row.MerchantAccount,
      GoodName: row.Name || row.GoodName,
      GoodType: row.GoodType,
      Id: row.GoodsId, ActivityId: row.ActivityId,
    },
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push(homePath.value)
}

// ── 抢购这一层 + 红字提示（C3 块 5 · commit 5 续笔，2026-10-12）────────────────────────
// ⚠️ **真扣减（扣 `SoldStock` / 扣名额）在订单侧**（`OrderService.addLocked` 的秒杀段，块 7 commit 2c，
//    2026-10-14 接线）—— 本段**仍不碰任何库存**，只是把活动的 `ActivityId` 塞进 `productLink` 的 query
//    带过去；「扣动没扣动」由后端在订单锁临界区内 CAS 说了算。
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

const grabLabel = (row) => (row.SoldOut ? '已售完' : row.Stage !== 'live' ? '未开始' : '立即抢购')
const grabTitle = (row) => {
  if (row.SoldOut) return '这一场已经抢完 —— 库存 / 名额由后端 SoldOut 判定（真扣减在订单侧：块 7 commit 2c 已接线）'
  if (row.Stage !== 'live') return '这场还没开始（阶段由后端按库时钟现算）'
  return '去商品详情页下单（本页不扣库存；真扣减在订单侧 —— 块 7 commit 2c 已接线，本页只把 ActivityId 带过去）'
}

/** 整卡点击：**售完那一档拦下跳转**并给红字（`.thumb` / `.nm` 是 `<router-link>`，不拦就跳走了）。 */
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

onMounted(() => {
  loadLive()
  ticker = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  if (noticeTimer) clearTimeout(noticeTimer)
  if (ticker) clearInterval(ticker)
})
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

.wrap { max-width: 1180px; margin: 0 auto; padding: var(--s6) var(--s5) var(--s8); }

/* ── A 版页头 ─────────────────────────────────────────────────────────────── */
.page-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: var(--s5); margin-bottom: var(--s5);
}
.kicker {
  font-family: var(--font-mono); font-size: 10px; letter-spacing: 0.16em;
  text-transform: uppercase; color: var(--ink-3);
}
.page-head h1 {
  margin-top: 6px; font-family: var(--font-display); font-size: var(--fs-2xl);
  font-weight: 600; line-height: 1.1; letter-spacing: -0.02em; color: var(--ink);
}
.page-head p {
  margin-top: 8px; max-width: 58ch; font-size: var(--fs-sm);
  line-height: 1.7; color: var(--ink-2);
}
.head-actions { display: flex; gap: var(--s2); flex: 0 0 auto; }
.head-actions .btn {
  display: inline-flex; align-items: center; height: 36px; padding: 0 var(--s5);
  border: 1px solid var(--line-2); border-radius: var(--r1);
  font-size: var(--fs-sm); color: var(--ink-2); text-decoration: none;
  background: var(--surface);
}
.head-actions .btn:hover { border-color: var(--cin-600); color: var(--cin-600); }

/* ── 下一场 / 进行中（A 版 sk-clock）────────────────────────────────────────── */
.sk-clock {
  display: grid; grid-template-columns: minmax(0, 1fr) 330px;
  border: 1px solid var(--cin-700); margin-bottom: var(--s5); background: var(--surface);
}
.clock-main { padding: 26px 30px; border-right: 1px solid var(--cin-700); }
.clock-main h2 {
  font-family: var(--font-display); font-size: 34px; line-height: 1.05;
  letter-spacing: -0.035em; margin: 8px 0; font-weight: 600;
}
.clock-sub { font-size: var(--fs-xs); color: var(--ink-3); }
.clock-num { display: flex; gap: 8px; margin-top: 18px; }
.clock-num div {
  border: 1px solid var(--cin-300); min-width: 78px; padding: 10px 12px; text-align: center;
}
.clock-num b {
  display: block; font-family: var(--font-mono); font-size: 34px; line-height: 1;
  color: var(--cin-700);
  font-weight: 400; letter-spacing: -0.05em; font-variant-numeric: tabular-nums;
}
.clock-num small {
  display: block; font-family: var(--font-mono); font-size: 8px; color: var(--ink-3);
  margin-top: 5px; letter-spacing: 0.08em;
}
.clock-note { margin-top: 12px; font-size: 11px; color: var(--ink-3); }
.clock-side {
  padding: 22px 24px; background: var(--zone);
  display: flex; flex-direction: column; justify-content: center; gap: 10px;
}
.clock-side .cap {
  display: flex; justify-content: space-between;
  font-family: var(--font-mono); font-size: 9px; color: var(--ink-3);
}
.clock-side .bar { height: 8px; border: 1px solid var(--ink); background: var(--surface); }
.clock-side .bar i { display: block; height: 100%; background: var(--cin-600); }
.clock-side .remain {
  font-family: var(--font-mono); font-size: 22px; line-height: 1;
  margin: 4px 0 2px; font-variant-numeric: tabular-nums;
}
.hero-price { display: flex; align-items: baseline; gap: var(--s2); }
.hero-price b {
  font-family: var(--font-mono); font-size: var(--fs-xl);
  color: var(--cin-600); font-variant-numeric: tabular-nums;
}
.hero-price s { font-size: var(--fs-xs); color: var(--ink-3); }
.btn-hero {
  align-self: flex-start; height: 34px; padding: 0 var(--s5); border-radius: var(--r1);
  border: 1px solid var(--cin-600); background: var(--cin-600); color: var(--bg);
  font-size: var(--fs-sm); font-weight: var(--fw-med); cursor: pointer;
}
.btn-hero:hover { filter: brightness(1.06); }
.btn-hero.off { border-color: var(--line-2); background: var(--surface); color: var(--ink-3); cursor: not-allowed; }
.btn-hero.off:hover { filter: none; }

.tip { font-size: var(--fs-sm); color: var(--ink-3); padding: var(--s6) 0; }
.tip-a { color: var(--cin-600); }

/* ── 时间轴（A 版 sk-timeline）─────────────────────────────────────────────── */
.sk-timeline { border-top: 1px solid var(--line); }
.sk-now {
  display: grid; grid-template-columns: 142px minmax(0, 1fr);
  background: var(--cin-800); color: var(--bg);
}
.sk-now .n-time {
  padding: 14px 20px; font-family: var(--font-mono); font-size: 10px;
  letter-spacing: 0.12em; border-right: 1px solid var(--ink-3);
}
.sk-now .n-body {
  padding: 14px 26px; font-family: var(--font-mono); font-size: 9px;
  letter-spacing: 0.08em; color: rgba(255, 255, 255, 0.66);
}
.sk-now .n-body b { color: var(--bg); font-weight: 400; }

.sk-row {
  display: grid; grid-template-columns: 142px minmax(0, 1fr);
  border-bottom: 1px solid var(--line); position: relative;
}
.sk-time {
  padding: 22px 20px 22px 0; border-right: 1px solid var(--line); position: relative;
}
.sk-time b {
  display: block; font-family: var(--font-mono); font-size: 38px; line-height: 0.9;
  letter-spacing: -0.06em; font-weight: 400; font-variant-numeric: tabular-nums;
}
.sk-time span {
  display: block; font-family: var(--font-mono); font-size: 8px; color: var(--ink-3);
  margin-top: 8px; letter-spacing: 0.05em;
}
.sk-time::after {
  content: ""; position: absolute; right: -5px; top: 30px;
  width: 9px; height: 9px; background: var(--surface);
  border: 1px solid var(--ink); z-index: 1;
}
.sk-row.live .sk-time::after,
.sk-row.upcoming .sk-time::after { background: var(--ink); }
.sk-row.live .sk-time b { text-decoration: underline; text-underline-offset: 4px; }
.sk-body {
  padding: 18px 0 18px 26px; min-width: 0;
  display: grid; grid-template-columns: 74px minmax(0, 1fr) 160px;
  gap: 18px; align-items: center;
}
.sk-body .thumb {
  width: 74px; height: 92px; border-radius: var(--r1); overflow: hidden;
  background: var(--zone); display: flex; align-items: center; justify-content: center;
}
.sk-body .thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ph-none { font-size: var(--fs-xs); color: var(--ink-3); }
.nm {
  font-family: var(--font-display); font-size: 20px; line-height: 1.15;
  font-weight: 600; color: inherit; text-decoration: none;
}
.nm:hover { color: var(--cin-600); }
.info { min-width: 0; }
.meta { font-size: 10px; color: var(--ink-3); margin-top: 4px; }
.price { font-family: var(--font-mono); font-size: 18px; margin-top: 6px; font-variant-numeric: tabular-nums; }
.price b { color: var(--cin-600); font-weight: 400; }
.price s { font-size: 10px; margin-left: 6px; color: var(--ink-3); }
.bar { height: 7px; border: 1px solid var(--ink); background: var(--surface); margin-top: 9px; }
.bar i { display: block; height: 100%; background: var(--cin-600); }
.bar-meta {
  display: flex; justify-content: space-between;
  font-family: var(--font-mono); font-size: 8px; color: var(--ink-3); margin-top: 4px;
}
.act { display: flex; flex-direction: column; align-items: flex-end; gap: 7px; text-align: right; }

/* ⚠️ 2026-10-19 用户令：秒杀页主色调**必须红**（推翻 2026-10-12「秒杀价用青瓷绿」那版），
   价格 / 进度 / 按钮 / 进行中带全部改走朱砂 ramp；青瓷绿只留给站名等全局品牌位。 */
.chip {
  font-size: var(--fs-xs); padding: 0 var(--s2); border-radius: var(--r1);
  white-space: nowrap; border: 1px solid transparent;
}
.chip.on { background: var(--cin-50); color: var(--cin-700); border-color: var(--cin-300); }
.chip.soon { background: var(--zone); color: var(--ink-2); border-color: var(--line-2); }
.chip.soldout { background: var(--ink-3); color: var(--bg); }

/* 售完：整行降不透明度（不吃 `filter: grayscale()`，商品图是商家资产、不该被页面改色）。 */
.sk-row.live { background: var(--cin-50); }
.sk-row.live .sk-time b { color: var(--cin-700); }
.sk-row.sold { background: var(--zone); }
.sk-row.sold .thumb,
.sk-row.sold .info { opacity: 0.55; }
.sk-row.sold .thumb,
.sk-row.sold .nm { cursor: not-allowed; }

/* 斜向「已售完」贴图：压整行、`pointer-events: none`（它只是漆面，不参与点击路由）。 */
.stamp {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%) rotate(-14deg);
  pointer-events: none; user-select: none; white-space: nowrap;
  padding: 2px var(--s4); border: 2px solid var(--accent); border-radius: var(--r1);
  color: var(--accent); font-size: var(--fs-md); font-weight: var(--fw-semi); letter-spacing: 0.12em;
}

.grab {
  flex: 0 0 auto; height: 34px; padding: 0 var(--s5);
  border: 1px solid var(--accent); border-radius: var(--r1); background: var(--accent);
  color: var(--bg); font-size: var(--fs-sm); font-weight: var(--fw-med); cursor: pointer;
}
.grab:hover { filter: brightness(1.06); }
.grab.off { border-color: var(--line-2); background: var(--surface); color: var(--ink-3); cursor: not-allowed; }
.grab.off:hover { filter: none; }

/* 点击红字：3 秒自消（定时器在 `onBeforeUnmount` 清掉）。 */
.notice {
  margin-bottom: var(--s4); padding: var(--s2) var(--s4); border-radius: var(--r1);
  background: var(--accent-soft); color: var(--accent); font-size: var(--fs-sm);
}

@media (max-width: 900px) {
  .sk-clock { grid-template-columns: 1fr; }
  .clock-main { border-right: 0; border-bottom: 1px solid var(--ink); }
  .sk-row { grid-template-columns: 96px minmax(0, 1fr); }
  .sk-body { grid-template-columns: 74px minmax(0, 1fr); }
  .act { grid-column: 1 / -1; flex-direction: row; align-items: center; justify-content: flex-end; }
  .sk-now { grid-template-columns: 96px minmax(0, 1fr); }
}
@media (max-width: 620px) {
  .page-head { flex-direction: column; align-items: flex-start; }
  .wrap { padding: var(--s5) var(--s4) var(--s8); }
  .sk-row { grid-template-columns: 1fr; }
  .sk-time { border-right: 0; padding: 16px 0 0; }
  .sk-time::after { display: none; }
  .sk-body { padding: 12px 0 18px; }
  .sk-now { display: none; }
}
</style>
