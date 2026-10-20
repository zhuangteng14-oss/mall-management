<template>
  <main class="ps-page">
    <!-- 头部：左标题，右侧今日场次竖排；不做整屏红底大横幅 -->
    <section class="ps-head">
      <div class="ps-head-tx">
        <p class="ps-kicker">限时秒杀</p>
        <h1>整点开抢，<br />过时不候。</h1>
        <p class="ps-lede">
          每天 10:00 / 14:00 / 18:00 三场，每场 2 小时。这里只把真正值得等的价格放上来，
          卖完即止，不补货。
        </p>
        <div class="ps-head-stats">
          <span><b>{{ headStats.count }}</b>件今日商品</span>
          <span><b>{{ headStats.sold }}</b>件已抢</span>
          <span><b>{{ headStats.discount }}</b>折平均力度</span>
        </div>
      </div>

      <aside class="ps-schedule">
        <header>
          <h2>今日场次</h2>
          <span>{{ todayLabel }}</span>
        </header>
        <div
          v-for="(s, i) in slots"
          :key="i"
          class="ps-slot"
          :class="{ 'is-live': s.live }"
        >
          <span class="ps-slot-time">{{ s.time }}</span>
          <b>{{ s.label }}</b>
          <em>{{ s.note }}</em>
        </div>
        <p class="ps-slot-note">每场开始前 10 分钟可设提醒。</p>
      </aside>
    </section>

    <!-- 正在进行：一张大场次卡；左侧主推 1 件，右侧 2×2 小商品 -->
    <section v-if="featureView" class="ps-session">
      <header class="ps-session-head">
        <div class="ps-session-title">
          <span class="ps-live-chip"><i></i>抢购中</span>
          <h2>{{ featureView.startLabel }} 场</h2>
          <p>{{ featureView.window }}</p>
        </div>
        <div class="ps-clock">
          <span>距本场结束</span>
          <div class="ps-digits">
            <b>{{ pad(Math.floor(liveLeft / 3600)) }}</b><i>:</i>
            <b>{{ pad(Math.floor((liveLeft % 3600) / 60)) }}</b><i>:</i>
            <b>{{ pad(liveLeft % 60) }}</b>
          </div>
        </div>
      </header>

      <div class="ps-live-body">
        <article class="ps-feature">
          <div class="ps-img ps-img--big tone-a">
            <PvIcon :name="featureView.icon" :size="82" :stroke="1.4" />
            <span class="ps-badge">{{ featureView.badge }}</span>
            <span class="ps-img-word">{{ live ? 'SECKILL' : 'SOUNDCORE' }}</span>
            <span class="ps-img-note">{{ live ? '限时秒杀 · 售完不补' : 'SoundCore · 声谷' }}</span>
            <span class="ps-img-index">{{ featureView.index }}</span>
          </div>
          <div class="ps-feature-info">
            <div class="ps-feature-top">
              <span class="ps-brand-tag">{{ live ? '限时' : '品牌' }}</span>
              <span class="ps-sold">{{ featureView.soldText }}</span>
            </div>
            <h3>{{ featureView.name }}</h3>
            <p class="ps-sub">{{ featureView.sub }}</p>
            <div class="ps-progress">
              <span><i :style="{ width: featureView.pct + '%' }"></i></span>
              <em>已抢 {{ featureView.pct }}%</em>
            </div>
            <div class="ps-buy-row">
              <div class="ps-price">
                <b><span>¥</span>{{ featureView.price }}</b>
                <s>¥{{ featureView.was }}</s>
                <em v-if="featureView.save > 0">省 {{ featureView.save }}</em>
              </div>
              <button
                type="button"
                class="ps-buy"
                @click="notify(live ? '请从正式秒杀页进入商品详情下单' : '静态预览：不会进入抢购流程')"
              >
                马上抢
              </button>
            </div>
          </div>
        </article>

        <div class="ps-grid4">
          <article v-for="p in liveProducts" :key="p.id" class="ps-product">
            <div class="ps-img tone-b" :class="p.tone">
              <PvIcon :name="p.icon" :size="30" :stroke="1.6" />
              <span v-if="p.badge" class="ps-badge ps-badge--sm">{{ p.badge }}</span>
            </div>
            <div class="ps-product-info">
              <h4>{{ p.name }}</h4>
              <p>{{ p.sub }}</p>
              <div class="ps-progress ps-progress--sm">
                <span><i :style="{ width: p.pct + '%' }"></i></span>
                <em :class="{ hot: p.pct >= 90 }">{{ p.pct >= 95 ? '仅剩少量' : '已抢 ' + p.pct + '%' }}</em>
              </div>
              <div class="ps-buy-row">
                <div class="ps-price ps-price--sm">
                  <b><span>¥</span>{{ p.price }}</b>
                  <s>¥{{ p.was }}</s>
                </div>
                <button
                  type="button"
                  class="ps-buy ps-buy--sm"
                  @click="notify(live ? '请从正式秒杀页进入商品详情下单' : '静态预览：不会进入抢购流程')"
                >
                  抢
                </button>
              </div>
            </div>
          </article>
        </div>
      </div>

      <footer class="ps-session-foot">
        <span><PvIcon name="flame" :size="14" />{{ sessionFoot }}</span>
        <span>{{ live ? '库存由系统实时扣减，售完即止' : '本场品牌：声谷 / 极造 / 山雾' }}</span>
      </footer>
    </section>

    <!-- 即将开始：横向条带，商品横排，与上方左右拼贴不同 -->
    <section v-if="upcoming.length" class="ps-upcoming">
      <header class="ps-upcoming-head">
        <div>
          <span class="ps-soon-chip">{{ upcomingSlotLabel }}</span>
          <h2>还有 {{ fmt(nextLeft) }}</h2>
          <p>开场前可先设提醒，开抢时站内信通知。</p>
        </div>
        <button type="button" class="ps-remind-all" @click="notify('静态预览：不会设置提醒')">
          <PvIcon name="clock" :size="14" />
          全部提醒
        </button>
      </header>

      <div class="ps-upcoming-list">
        <article v-for="p in upcoming" :key="p.id" class="ps-up-item">
          <div class="ps-img ps-up-img" :class="p.tone">
            <PvIcon :name="p.icon" :size="42" :stroke="1.4" />
            <span v-if="p.badge" class="ps-badge ps-badge--sm">{{ p.badge }}</span>
          </div>
          <div class="ps-up-info">
            <span class="ps-up-brand">{{ p.brand }}</span>
            <h3>{{ p.name }}</h3>
            <p>{{ p.sub }}</p>
            <div class="ps-up-price">
              <b><span>¥</span>{{ p.price }}</b>
              <s>¥{{ p.was }}</s>
              <em>{{ p.discount }}</em>
            </div>
          </div>
          <button type="button" class="ps-remind" @click="notify('静态预览：不会设置提醒')">
            提醒我
          </button>
        </article>
      </div>
    </section>

    <!-- 18:00 预告：紧凑行列表，缩略图在左，价格在右 -->
    <section v-if="later.length" class="ps-later">
      <header class="ps-later-head">
        <div>
          <span class="ps-soon-chip ps-soon-chip--grey">18:00 场</span>
          <h2>今晚预告</h2>
        </div>
        <span>{{ later.length }} 件商品 · 18:00 公布价格</span>
      </header>
      <div class="ps-later-list">
        <div v-for="p in later" :key="p.id" class="ps-later-row">
          <div class="ps-up-img ps-later-img" :class="p.tone">
            <PvIcon :name="p.icon" :size="22" :stroke="1.6" />
          </div>
          <div class="ps-later-tx">
            <b>{{ p.name }}</b>
            <span>{{ p.sub }}</span>
          </div>
          <span class="ps-later-hint">{{ p.hint }}</span>
          <button type="button" class="ps-later-btn" @click="notify('静态预览：不会加入提醒')">
            先加购
          </button>
        </div>
      </div>
    </section>

    <!-- 秒杀规则：横向四格，收尾 -->
    <section class="ps-rules">
      <h2>秒杀规则</h2>
      <div class="ps-rule-grid">
        <div v-for="rule in rules" :key="rule.title">
          <span class="ps-rule-ic"><PvIcon :name="rule.icon" :size="17" /></span>
          <b>{{ rule.title }}</b>
          <p>{{ rule.desc }}</p>
        </div>
      </div>
    </section>

    <p class="ps-foot">
      <PvIcon name="alert" :size="14" />
      {{ live
        ? '活动、价格与库存来自 /marketing/seckill/live；倒计时以库时钟为基准，下单扣减以商品详情页为准。'
        : '版式预览：价格、库存、倒计时均为 Mock；倒计时会随页面运行递减，不代表真实活动。' }}
    </p>

    <transition name="ps-toast">
      <div v-if="toast" class="ps-toast">{{ toast }}</div>
    </transition>
  </main>
</template>

<script setup>
// ⚠️ 数据源双轨：`/marketing/seckill/live` **不在白名单** ⇒ 匿名发必 401，`http.js` 会把公开页
//    踢去登录页；所以只在 `isLoggedIn()` 时请求，匿名预览保留示例数据（与本页其余四张预览同口径）。
// ⚠️ 时间：`StartAt / EndAt` 是 `DATE_FORMAT` 出来的**库内 UTC 裸文本**（同 HomePage.flashTs）
//    ⇒ 解析补 `Z`；倒计时零点优先用后端 `serverTimestamp`（库时钟 epoch 毫秒），缺失才退回本机钟。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import PvIcon from '../../components/preview/PvIcon.vue'
import { seckillLive } from '../../api/marketing'
import { isLoggedIn } from '../../api/auth'

const live = ref(false)
const rows = ref([])
const serverOffset = ref(0)
const nowTick = ref(Date.now())
const demoLiveLeft = ref(2 * 3600 + 18 * 60 + 36)
const demoNextLeft = ref(1 * 3600 + 12 * 60 + 8)
const toast = ref('')
let timer = null
let toastTimer = null
let lastReloadAt = 0

const pad = (n) => String(n).padStart(2, '0')
const fmt = (s) => `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
/** 库内 UTC 裸文本 → 毫秒（不补 `Z` 会按浏览器本地时区解析，差一个偏移且不报错）。 */
const ts = (s) => {
  const d = new Date(String(s == null ? '' : s).replace(' ', 'T') + 'Z')
  return Number.isNaN(d.getTime()) ? null : d.getTime()
}
/** UTC 时刻 → 浏览器本地 `HH:mm`。 */
const hhmm = (s) => {
  const t = ts(s)
  if (t === null) return '—'
  const d = new Date(t)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}
const dayOf = (s) => {
  const t = ts(s)
  if (t === null) return '—'
  const d = new Date(t)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const liveRows = computed(() => rows.value.filter((r) => r.Stage === 'live'))
const upcomingRows = computed(() => rows.value.filter((r) => r.Stage === 'upcoming'))
const liveEndAt = computed(() => {
  const list = liveRows.value.map((r) => ts(r.EndAt)).filter((n) => n !== null)
  return list.length ? Math.min(...list) : null
})
const nextStartAt = computed(() => {
  const list = upcomingRows.value.map((r) => ts(r.StartAt)).filter((n) => n !== null)
  return list.length ? Math.min(...list) : null
})
const secondsTo = (target) =>
  target === null ? 0 : Math.max(0, Math.round((target - nowTick.value) / 1000))
const liveLeft = computed(() => (live.value ? secondsTo(liveEndAt.value) : demoLiveLeft.value))
const nextLeft = computed(() => (live.value ? secondsTo(nextStartAt.value) : demoNextLeft.value))

const todayLabel = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} 周${week}`
})

const TONES = ['tone-b', 'tone-c', 'tone-d', 'tone-e']
const pctOf = (r) => {
  const total = Number(r.TotalStock || 0)
  const sold = Number(r.SoldStock || 0)
  return total > 0 ? Math.min(100, Math.round((sold / total) * 100)) : 0
}
const discountOf = (r) => {
  const was = Number(r.OriginalPrice || 0)
  const now = Number(r.SeckillPrice || 0)
  if (!was || !now) return ''
  return `${Math.round((now / was) * 100) / 10} 折`
}

const DEMO_FEATURE = {
  icon: 'headphones',
  name: 'SoundCore 无线降噪耳机 Air Pro',
  sub: '40dB 主动降噪 · 38 小时续航 · 双设备连接',
  price: 299,
  was: 599,
  save: 300,
  pct: 80,
  sold: 1600,
  total: 2000,
  soldText: '已抢 1,600 / 2,000 件',
  badge: '直降 300',
  index: '01 / 05',
  startLabel: '10:00',
  window: '10:00 – 12:00 · 5 件商品 · 抢完不补',
}

const featureView = computed(() => {
  if (!live.value) return DEMO_FEATURE
  const r = liveRows.value[0]
  if (!r) return null
  const was = Number(r.OriginalPrice || 0)
  const price = Number(r.SeckillPrice || 0)
  const sold = Number(r.SoldStock || 0)
  const total = Number(r.TotalStock || 0)
  return {
    icon: 'flame',
    name: r.Name || r.GoodName,
    sub: [r.GoodType, '售完不补'].filter(Boolean).join(' · '),
    price,
    was,
    save: Math.max(0, Math.round(was - price)),
    pct: pctOf(r),
    sold,
    total,
    soldText: total > 0 ? `已抢 ${sold.toLocaleString('zh-CN')} / ${total.toLocaleString('zh-CN')} 件` : `已抢 ${sold} 件`,
    badge: was > price ? `直降 ${Math.round(was - price)}` : '限时秒杀',
    index: `01 / ${pad(liveRows.value.length)}`,
    startLabel: hhmm(r.StartAt),
    window: `${hhmm(r.StartAt)} – ${hhmm(r.EndAt)} · ${liveRows.value.length} 件商品 · 抢完不补`,
  }
})

const DEMO_LIVE_PRODUCTS = [
  { id: 1, name: '极造 智能手表 S2', sub: '血氧 / 睡眠 / 14 天续航', price: 399, was: 799, pct: 65, icon: 'watch', tone: 'tone-c', badge: '直降 400' },
  { id: 2, name: '声谷 蓝牙音箱 Mini', sub: '360° 环绕 · IPX7 防水', price: 159, was: 299, pct: 92, icon: 'speaker', tone: 'tone-b' },
  { id: 3, name: '极造 机械键盘 K87', sub: '热插拔 · 三模连接', price: 229, was: 459, pct: 48, icon: 'keyboard', tone: 'tone-d' },
  { id: 4, name: '山雾 桌面香氛机', sub: '静音雾化 · 三档定时', price: 189, was: 329, pct: 76, icon: 'spark', tone: 'tone-e' },
]

const liveProducts = computed(() => {
  if (!live.value) return DEMO_LIVE_PRODUCTS
  return liveRows.value.slice(1, 5).map((r) => {
    const was = Number(r.OriginalPrice || 0)
    const price = Number(r.SeckillPrice || 0)
    return {
      id: r.Id,
      name: r.Name || r.GoodName,
      sub: [r.GoodType, r.ProductCode].filter(Boolean).join(' · ') || '限时秒杀',
      price,
      was,
      pct: pctOf(r),
      icon: 'flame',
      tone: TONES[(Number(r.Id) || 0) % TONES.length],
      badge: was > price ? `直降 ${Math.round(was - price)}` : '',
    }
  })
})

const DEMO_UPCOMING = [
  {
    id: 1, brand: '声谷', name: 'SoundCore 头戴式耳机 Q30',
    sub: '混合降噪 · 60 小时续航', price: 349, was: 699, discount: '5 折',
    icon: 'headphones', tone: 'tone-a', badge: '限量 500',
  },
  {
    id: 2, brand: '锐视', name: '27 英寸 2K 显示器 S27',
    sub: '165Hz · IPS 广色域', price: 899, was: 1499, discount: '6 折',
    icon: 'laptop', tone: 'tone-c',
  },
  {
    id: 3, brand: '山雾', name: '无火香薰礼盒 12 件',
    sub: '白茶 / 雪松 / 无花果', price: 99, was: 219, discount: '4.5 折',
    icon: 'gift', tone: 'tone-b', badge: '礼盒装',
  },
]

const upcoming = computed(() => {
  if (!live.value) return DEMO_UPCOMING
  return upcomingRows.value.slice(0, 3).map((r) => {
    const was = Number(r.OriginalPrice || 0)
    const price = Number(r.SeckillPrice || 0)
    const total = Number(r.TotalStock || 0)
    return {
      id: r.Id,
      brand: `${dayOf(r.StartAt)} ${hhmm(r.StartAt)}`,
      name: r.Name || r.GoodName,
      sub: [r.GoodType, r.ProductCode].filter(Boolean).join(' · ') || '限时秒杀',
      price,
      was,
      discount: discountOf(r),
      icon: 'flame',
      tone: TONES[(Number(r.Id) || 0) % TONES.length],
      badge: total > 0 ? `限量 ${total}` : '',
    }
  })
})

const upcomingSlotLabel = computed(() => {
  if (!live.value) return '14:00 场'
  const first = upcomingRows.value[0]
  return first ? `${hhmm(first.StartAt)} 场` : '待定场'
})

const DEMO_LATER = [
  { id: 1, name: '声谷 真无线耳机 Earbuds X', sub: '入耳式 · 30 小时续航', hint: '18:00 公布', icon: 'earbuds', tone: 'tone-a' },
  { id: 2, name: '极造 便携 4K 摄像头', sub: '自动对焦 · 双麦克风', hint: '18:00 公布', icon: 'camera', tone: 'tone-d' },
  { id: 3, name: '锐视 便携显示器 15.6"', sub: '1080P · Type-C 一线连', hint: '18:00 公布', icon: 'laptop', tone: 'tone-c' },
]

// 真实接口只回 live / upcoming（signup 对买家不可见）⇒ 真实模式下没有「稍后预告」这一档。
const later = computed(() => (live.value ? [] : DEMO_LATER))

const headStats = computed(() => {
  if (!live.value) return { count: 17, sold: '1,284', discount: '5.2' }
  const sold = rows.value.reduce((sum, r) => sum + Number(r.SoldStock || 0), 0)
  const rates = rows.value
    .map((r) => {
      const was = Number(r.OriginalPrice || 0)
      const now = Number(r.SeckillPrice || 0)
      return was > 0 && now > 0 ? (now / was) * 10 : null
    })
    .filter((n) => n !== null)
  const avg = rates.length ? rates.reduce((a, b) => a + b, 0) / rates.length : null
  return {
    count: rows.value.length,
    sold: sold.toLocaleString('zh-CN'),
    discount: avg === null ? '—' : avg.toFixed(1),
  }
})

const slots = computed(() => {
  if (!live.value) {
    return [
      { time: '10:00', label: '抢购中', note: fmt(liveLeft.value), live: true },
      { time: '14:00', label: '即将开始', note: `${fmt(nextLeft.value)} 后`, live: false },
      { time: '18:00', label: '预告', note: '6 件商品', live: false },
    ]
  }
  return [...liveRows.value, ...upcomingRows.value]
    .map((r) => ({ r, at: ts(r.StartAt) }))
    .filter((x) => x.at !== null)
    .sort((a, b) => a.at - b.at)
    .slice(0, 3)
    .map(({ r }) => ({
      time: hhmm(r.StartAt),
      label: r.Stage === 'live' ? '抢购中' : '即将开始',
      note: r.Stage === 'live' ? fmt(liveLeft.value) : `${fmt(nextLeft.value)} 后`,
      live: r.Stage === 'live',
    }))
})

const sessionFoot = computed(() =>
  live.value ? `${headStats.value.sold} 件已售 · 售完不补` : '本场已有 1,284 人下单，库存每 30 秒同步一次。'
)

const rules = [
  { icon: 'clock', title: '整点开抢', desc: '每场 2 小时，开场后价格立即生效，售完即止。' },
  { icon: 'flame', title: '限量库存', desc: '库存每 30 秒同步一次，以提交订单时为准。' },
  { icon: 'ticket', title: '优惠不叠加', desc: '秒杀价已是最低价，不再叠加会员折扣与优惠券。' },
  { icon: 'shield', title: '正品保障', desc: '全部商品由平台自营或品牌旗舰店发货。' },
]

function tick() {
  nowTick.value = Date.now() + serverOffset.value
  if (!live.value) {
    if (demoLiveLeft.value > 0) demoLiveLeft.value -= 1
    if (demoNextLeft.value > 0) demoNextLeft.value -= 1
    return
  }
  // 倒计时归零 ⇒ 场次状态已变，缓慢重拉一次（30s 节流，避免零点附近反复请求）。
  if ((liveLeft.value === 0 && liveEndAt.value !== null) || (nextLeft.value === 0 && nextStartAt.value !== null)) {
    if (Date.now() - lastReloadAt > 30000) {
      lastReloadAt = Date.now()
      loadLive()
    }
  }
}

async function loadLive() {
  if (!isLoggedIn()) return // 匿名预览：保持示例数据，不发必 401 的请求
  try {
    const data = await seckillLive({ limit: 20 })
    if (data && data.success && Array.isArray(data.list)) {
      const stamp = Number(data.serverTimestamp)
      serverOffset.value = Number.isFinite(stamp) && stamp > 0 ? stamp - Date.now() : 0
      rows.value = data.list.filter((r) => r.Stage === 'live' || r.Stage === 'upcoming')
      live.value = true
      nowTick.value = Date.now() + serverOffset.value
    }
  } catch (err) {
    console.warn('[SeckillPreview] 秒杀活动读取失败，保留示例数据', err)
  }
}

function notify(text) {
  toast.value = text
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 1800)
}

onMounted(() => {
  timer = setInterval(tick, 1000)
  loadLive()
})

onBeforeUnmount(() => {
  clearInterval(timer)
  if (toastTimer) clearTimeout(toastTimer)
})
</script>

<style scoped>
.ps-page {
  max-width: 980px;
  margin: 0 auto;
  padding: 40px 32px 64px;
  color: #1f2329;
}

/* ── 头部 ── */
.ps-head {
  display: grid;
  grid-template-columns: 1.5fr 1fr;
  gap: 34px;
  align-items: end;
  padding-bottom: 34px;
  margin-bottom: 26px;
  border-bottom: 1px solid #e4e5ea;
}
.ps-kicker { font-size: 11px; letter-spacing: .12em; color: #8a9099; margin-bottom: 16px; }
.ps-head h1 {
  font-family: var(--font-display);
  font-size: clamp(36px, 4.8vw, 54px);
  font-weight: 500;
  line-height: 1.06;
  letter-spacing: -.03em;
  color: #1b1e23;
}
.ps-lede {
  margin-top: 18px;
  max-width: 430px;
  font-size: 12.5px;
  line-height: 1.85;
  color: #777d86;
}
.ps-head-stats { display: flex; gap: 24px; margin-top: 22px; }
.ps-head-stats span { font-size: 10.5px; color: #8a9099; }
.ps-head-stats b {
  font-family: var(--font-mono);
  font-size: 16px;
  font-weight: 500;
  color: #2a2e34;
  margin-right: 4px;
}

.ps-schedule {
  background: #fff;
  border: 1px solid #e8e9ee;
  padding: 16px 18px 12px;
}
.ps-schedule header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: 9px;
  border-bottom: 1px solid #eef0f3;
}
.ps-schedule h2 { font-size: 12.5px; font-weight: 600; color: #34383f; }
.ps-schedule header span { font-family: var(--font-mono); font-size: 9px; color: #9aa0a8; }
.ps-slot {
  display: grid;
  grid-template-columns: 46px 1fr auto;
  gap: 8px;
  align-items: center;
  padding: 9px 0;
  border-bottom: 1px dashed #eef0f3;
}
.ps-slot:last-of-type { border-bottom: 0; }
.ps-slot-time { font-family: var(--font-mono); font-size: 12px; color: #555b64; }
.ps-slot b { font-size: 10px; font-weight: 500; color: #9aa0a8; }
.ps-slot em { font-style: normal; font-family: var(--font-mono); font-size: 9px; color: #a8adb4; }
.ps-slot.is-live .ps-slot-time { color: #e04537; }
.ps-slot.is-live b { color: #e04537; }
.ps-slot.is-live em { color: #e04537; }
.ps-slot-note { margin-top: 6px; font-size: 8.5px; color: #b0b4ba; }

/* ── 通用商品 ── */
.ps-session {
  background: #fff;
  border: 1px solid #e8e9ee;
  padding: 20px 22px 0;
  margin-bottom: 22px;
}
.ps-session-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid #eef0f3;
}
.ps-session-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ps-live-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 9.5px;
  color: #e04537;
  background: #fdefed;
  border: 1px solid #f8d9d4;
  padding: 3px 9px;
  border-radius: 999px;
}
.ps-live-chip i {
  width: 5px; height: 5px;
  border-radius: 50%;
  background: #e04537;
  box-shadow: 0 0 0 3px rgba(224, 69, 55, .14);
}
.ps-session-title h2 { font-family: var(--font-display); font-size: 22px; font-weight: 500; }
.ps-session-title p { font-size: 9.5px; color: #9aa0a8; width: 100%; margin-top: -4px; }
.ps-clock { text-align: right; }
.ps-clock > span { display: block; font-size: 9px; color: #9aa0a8; margin-bottom: 5px; }
.ps-digits { display: flex; align-items: center; gap: 3px; }
.ps-digits b {
  min-width: 27px;
  padding: 4px 3px;
  border-radius: 4px;
  background: #fdf0ee;
  border: 1px solid #f7dcd7;
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 500;
  color: #d94335;
  text-align: center;
  letter-spacing: -.02em;
}
.ps-digits i { font-style: normal; color: #d94335; font-size: 12px; }

.ps-live-body {
  display: grid;
  grid-template-columns: 5fr 7fr;
  gap: 16px;
  padding: 18px 0;
}
.ps-feature {
  border: 1px solid #eef0f3;
  background: #fdfdfe;
  display: flex;
  flex-direction: column;
}
.ps-img {
  position: relative;
  display: grid;
  place-items: center;
  overflow: hidden;
}
.ps-img::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(60% 55% at 50% 42%, rgba(255, 255, 255, .85), transparent 72%);
}
.ps-img::after {
  content: '';
  position: absolute;
  width: 58%;
  aspect-ratio: 1;
  border-radius: 50%;
  border: 1px solid currentColor;
  opacity: .16;
}
.ps-img svg { position: relative; z-index: 1; }
.ps-img-word {
  position: absolute;
  top: 12px;
  right: 14px;
  z-index: 2;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: .18em;
  color: currentColor;
  opacity: .55;
}
.ps-img-index {
  position: absolute;
  right: 13px;
  bottom: 10px;
  z-index: 2;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: .06em;
  color: currentColor;
  opacity: .45;
}
.ps-img--big { height: 224px; }
.tone-a { background: linear-gradient(145deg, #edf2f8, #e2eaf4); color: #557494; }
.tone-b { background: linear-gradient(145deg, #f8f2e9, #f0e6d7); color: #97733f; }
.tone-c { background: linear-gradient(145deg, #ecf5f0, #e0eee7); color: #4d7a62; }
.tone-d { background: linear-gradient(145deg, #f9efef, #f3e3e2); color: #a2605c; }
.tone-e { background: linear-gradient(145deg, #f2f3f6, #e8eaef); color: #5d6572; }
.ps-badge {
  position: absolute;
  left: 10px;
  top: 10px;
  z-index: 2;
  font-size: 9px;
  color: #fff;
  background: linear-gradient(135deg, #ff7a45, #e54636);
  padding: 3px 8px;
  border-radius: 4px;
  letter-spacing: .02em;
}
.ps-badge--sm { left: 7px; top: 7px; font-size: 8px; padding: 2px 6px; }
.ps-img-note {
  position: absolute;
  left: 12px;
  bottom: 10px;
  z-index: 2;
  font-size: 8.5px;
  letter-spacing: .06em;
  color: #7d8792;
}
.ps-feature-info { padding: 14px 16px 15px; display: flex; flex-direction: column; flex: 1; }
.ps-feature-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ps-brand-tag {
  font-size: 8.5px;
  color: #8b6a1f;
  border: 1px solid #ecd9ad;
  background: #fdf7e8;
  padding: 2px 6px;
  border-radius: 3px;
}
.ps-sold { font-size: 8.5px; color: #9aa0a8; }
.ps-feature-info h3 { margin-top: 8px; font-size: 14.5px; font-weight: 600; line-height: 1.45; color: #24272d; }
.ps-sub { margin-top: 5px; font-size: 9.5px; line-height: 1.6; color: #8d929a; }

.ps-progress { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.ps-progress > span {
  flex: 1;
  height: 5px;
  border-radius: 999px;
  background: #f0f1f4;
  overflow: hidden;
}
.ps-progress i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #ff9a5a, #e54636);
}
.ps-progress em { font-style: normal; font-size: 8.5px; color: #9aa0a8; white-space: nowrap; }
.ps-progress em.hot { color: #d94335; }
.ps-progress--sm { margin-top: 8px; }
.ps-progress--sm > span { height: 4px; }

.ps-buy-row { display: flex; align-items: flex-end; justify-content: space-between; gap: 10px; margin-top: auto; padding-top: 12px; }
.ps-price { display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; }
.ps-price b {
  font-family: var(--font-mono);
  font-size: 27px;
  font-weight: 500;
  line-height: 1;
  letter-spacing: -.05em;
  color: #e04537;
}
.ps-price b span { font-size: .52em; margin-right: 1px; }
.ps-price s { font-size: 9.5px; color: #b0b4ba; }
.ps-price em { font-style: normal; font-size: 8.5px; color: #e04537; background: #fdf0ee; padding: 2px 5px; border-radius: 3px; }
.ps-buy {
  height: 34px;
  padding: 0 18px;
  border: 0;
  border-radius: 17px;
  background: linear-gradient(135deg, #ff7a45, #e54636);
  color: #fff;
  font-size: 11.5px;
  cursor: pointer;
  box-shadow: 0 8px 18px -8px rgba(229, 70, 54, .6);
  transition: transform .15s, box-shadow .15s;
}
.ps-buy:hover { transform: translateY(-1px); box-shadow: 0 12px 22px -8px rgba(229, 70, 54, .66); }
.ps-buy--sm { height: 28px; padding: 0 14px; font-size: 10.5px; border-radius: 14px; }
.ps-price--sm b { font-size: 18px; }

.ps-grid4 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 12px;
}
.ps-product {
  display: grid;
  grid-template-columns: 92px 1fr;
  border: 1px solid #eef0f3;
  background: #fff;
  min-height: 0;
}
.ps-product .ps-img { height: 100%; min-height: 92px; }
.ps-product-info { padding: 9px 11px 9px; display: flex; flex-direction: column; min-width: 0; }
.ps-product-info h4 {
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
  color: #2b2f35;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ps-product-info p {
  margin-top: 3px;
  font-size: 8.5px;
  color: #9aa0a8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ps-session-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 0;
  border-top: 1px solid #eef0f3;
  font-size: 8.5px;
  color: #9aa0a8;
}
.ps-session-foot span { display: inline-flex; align-items: center; gap: 5px; }
.ps-session-foot svg { color: #e04537; }

/* ── 即将开始 ── */
.ps-upcoming {
  background: #fff;
  border: 1px solid #e8e9ee;
  padding: 18px 22px 20px;
  margin-bottom: 22px;
}
.ps-upcoming-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid #eef0f3;
}
.ps-soon-chip {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: #b06b1e;
  background: #fdf4e8;
  border: 1px solid #f4e2c8;
  padding: 3px 8px;
  border-radius: 4px;
}
.ps-soon-chip--grey { color: #6f7680; background: #f2f3f6; border-color: #e6e8ec; }
.ps-upcoming-head h2 { margin-top: 8px; font-family: var(--font-display); font-size: 20px; font-weight: 500; }
.ps-upcoming-head p { margin-top: 4px; font-size: 9.5px; color: #9aa0a8; }
.ps-remind-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 13px;
  border: 1px solid #e2e4e9;
  border-radius: 16px;
  background: #fff;
  font-size: 10px;
  color: #5d636c;
  cursor: pointer;
}
.ps-remind-all:hover { border-color: #cfd3da; background: #fafbfc; }

.ps-upcoming-list {
  display: grid;
  grid-template-columns: 1.25fr 1fr 1fr;
  gap: 12px;
  padding-top: 14px;
}
.ps-up-item {
  display: flex;
  flex-direction: column;
  border: 1px solid #eef0f3;
  background: #fdfdfe;
}
.ps-up-img { height: 118px; }
.ps-upcoming-list .ps-up-item:first-child .ps-up-img { height: 150px; }
.ps-up-info { padding: 10px 12px 12px; display: flex; flex-direction: column; flex: 1; }
.ps-up-brand { font-size: 8.5px; color: #9aa0a8; letter-spacing: .04em; }
.ps-up-info h3 {
  margin-top: 3px;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.45;
  color: #2b2f35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ps-up-info p { margin-top: 4px; font-size: 8.5px; color: #9aa0a8; }
.ps-up-price { display: flex; align-items: baseline; gap: 5px; margin-top: auto; padding-top: 9px; flex-wrap: wrap; }
.ps-up-price b {
  font-family: var(--font-mono);
  font-size: 18px;
  font-weight: 500;
  color: #e04537;
  letter-spacing: -.04em;
}
.ps-up-price b span { font-size: .55em; }
.ps-up-price s { font-size: 9px; color: #b0b4ba; }
.ps-up-price em { font-style: normal; font-size: 8.5px; color: #b06b1e; }
.ps-remind {
  margin: 0 12px 12px;
  height: 29px;
  border: 1px solid #f0d9d4;
  border-radius: 15px;
  background: #fdf5f3;
  color: #cf4a3b;
  font-size: 10px;
  cursor: pointer;
}
.ps-remind:hover { background: #fcece8; }

/* ── 18:00 预告 ── */
.ps-later {
  background: #fff;
  border: 1px solid #e8e9ee;
  padding: 18px 22px 8px;
  margin-bottom: 24px;
}
.ps-later-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eef0f3;
}
.ps-later-head h2 { margin-top: 7px; font-family: var(--font-display); font-size: 19px; font-weight: 500; }
.ps-later-head > span { font-size: 9px; color: #9aa0a8; }
.ps-later-row {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 11px 0;
  border-bottom: 1px solid #f1f2f5;
}
.ps-later-row:last-child { border-bottom: 0; }
.ps-later-img { width: 44px; height: 44px; border-radius: 8px; flex: none; }
.ps-later-tx { flex: 1; min-width: 0; }
.ps-later-tx b { display: block; font-size: 11.5px; font-weight: 600; color: #33373d; }
.ps-later-tx span { display: block; margin-top: 3px; font-size: 9px; color: #9aa0a8; }
.ps-later-hint { font-family: var(--font-mono); font-size: 9px; color: #a8adb4; }
.ps-later-btn {
  height: 27px;
  padding: 0 12px;
  border: 1px solid #e2e4e9;
  border-radius: 14px;
  background: #fff;
  font-size: 9.5px;
  color: #5d636c;
  cursor: pointer;
}
.ps-later-btn:hover { border-color: #cfd3da; background: #fafbfc; }

/* ── 规则 ── */
.ps-rules {
  padding: 20px 22px 18px;
  border-top: 1px solid #e4e5ea;
  border-bottom: 1px solid #e4e5ea;
  background: rgba(255, 255, 255, .5);
  margin-bottom: 18px;
}
.ps-rules h2 { font-family: var(--font-display); font-size: 18px; font-weight: 500; margin-bottom: 14px; }
.ps-rule-grid { display: grid; grid-template-columns: 1.15fr 1fr 1fr 1fr; gap: 18px; }
.ps-rule-grid > div { padding-right: 16px; border-right: 1px solid #e9eaee; }
.ps-rule-grid > div:last-child { border-right: 0; padding-right: 0; }
.ps-rule-ic {
  width: 30px; height: 30px;
  display: grid; place-items: center;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e8e9ee;
  color: #6b727c;
}
.ps-rule-grid b { display: block; margin-top: 9px; font-size: 11px; font-weight: 600; color: #3a3e45; }
.ps-rule-grid p { margin-top: 4px; font-size: 9px; line-height: 1.65; color: #9aa0a8; }

.ps-foot {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 9px;
  line-height: 1.6;
  color: #a3a8af;
}

.ps-toast {
  position: fixed;
  left: 50%;
  bottom: 40px;
  z-index: 120;
  transform: translateX(-50%);
  padding: 10px 16px;
  border-radius: 8px;
  background: rgba(31, 35, 41, .94);
  color: #fff;
  font-size: 12px;
}
.ps-toast-enter-active, .ps-toast-leave-active { transition: opacity .2s, transform .2s; }
.ps-toast-enter-from, .ps-toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }

@media (max-width: 900px) {
  .ps-page { padding: 30px 22px 52px; }
  .ps-head { grid-template-columns: 1fr; gap: 22px; }
  .ps-schedule { max-width: 420px; }
  .ps-live-body { grid-template-columns: 1fr; }
  .ps-upcoming-list { grid-template-columns: 1fr 1fr; }
  .ps-upcoming-list .ps-up-item:first-child { grid-column: 1 / -1; }
  .ps-upcoming-list .ps-up-item:first-child .ps-up-img { height: 160px; }
  .ps-rule-grid { grid-template-columns: 1fr 1fr; }
  .ps-rule-grid > div:nth-child(2) { border-right: 0; }
}
@media (max-width: 620px) {
  .ps-session, .ps-upcoming, .ps-later { padding-left: 15px; padding-right: 15px; }
  .ps-session-head { flex-direction: column; gap: 12px; }
  .ps-clock { text-align: left; }
  .ps-grid4 { grid-template-columns: 1fr; grid-template-rows: auto; }
  .ps-product { grid-template-columns: 88px 1fr; }
  .ps-upcoming-list { grid-template-columns: 1fr; }
  .ps-upcoming-list .ps-up-item:first-child { grid-column: auto; }
  .ps-upcoming-head, .ps-later-head { flex-direction: column; align-items: flex-start; }
  .ps-later-hint { display: none; }
  .ps-rule-grid { grid-template-columns: 1fr; }
  .ps-rule-grid > div { border-right: 0; border-bottom: 1px solid #e9eaee; padding-bottom: 12px; }
  .ps-rule-grid > div:last-child { border-bottom: 0; }
  .ps-session-foot { flex-direction: column; align-items: flex-start; gap: 5px; }
}
</style>
