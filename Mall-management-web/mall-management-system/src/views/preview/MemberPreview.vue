<template>
  <main class="pm-page">
    <!-- 黑金大卡：整块深色渐变，左侧身份、右侧资产，下面横贯成长进度 -->
    <section class="pm-hero">
      <div class="pm-card">
        <span class="pm-watermark">{{ currentLevelCode }}</span>

        <header class="pm-card-top">
          <span class="pm-club">Mall 会员俱乐部</span>
          <span class="pm-tier"><PvIcon name="crown" :size="13" />{{ currentLevelName }}会员</span>
        </header>

        <div class="pm-card-main">
          <div class="pm-id">
            <div class="pm-avatar">{{ memberInitial }}</div>
            <div class="pm-id-tx">
              <h1>{{ memberName }}</h1>
              <p>{{ memberNoLine }}</p>
              <div class="pm-id-tags">
                <span>实名已认证</span>
                <span>{{ live ? '实时账户' : '连续在会 2 年' }}</span>
              </div>
            </div>
          </div>

          <div class="pm-assets">
            <div>
              <b>{{ points.toLocaleString('zh-CN') }}</b>
              <span>可用积分</span>
            </div>
            <div>
              <b>{{ live ? '—' : '6' }}</b>
              <span>待用券</span>
            </div>
            <div>
              <b>{{ live ? '—' : '¥428.60' }}</b>
              <span>今年已省</span>
            </div>
          </div>
        </div>

        <div class="pm-growth">
          <div class="pm-growth-head">
            <span>成长值 <b>{{ growth.toLocaleString('zh-CN') }}</b> · {{ growthPct.toFixed(1) }}%</span>
            <span>{{ nextGoalText }}</span>
          </div>
          <div class="pm-bar">
            <i :style="{ width: growthPct + '%' }"></i>
          </div>
          <div class="pm-growth-foot">
            <span>{{ nextGapText }}</span>
            <span>{{ live ? '成长值来自订单实付、评价与签到' : '按近 30 天消费节奏，预计 2 个月达成' }}</span>
          </div>
        </div>

        <footer class="pm-card-foot">
          <div class="pm-perks">
            <span><PvIcon name="ticket" :size="13" />{{ benefitDiscountText }}</span>
            <span><PvIcon name="coin" :size="13" />积分 {{ benefitPointsText }}</span>
            <span><PvIcon name="gift" :size="13" />每月 {{ benefitCouponsText }}</span>
            <span><PvIcon name="headset" :size="13" />{{ benefitSupportText }}</span>
          </div>
          <div class="pm-card-actions">
            <button type="button" class="pm-btn pm-btn--gold" @click="notify('静态预览：不会进入积分兑换')">
              积分兑换
            </button>
            <button type="button" class="pm-btn pm-btn--ghost" @click="notify('静态预览：不会打开权益说明')">
              权益说明
            </button>
          </div>
        </footer>

        <div class="pm-chip-row" aria-hidden="true">
          <span class="pm-chip"></span>
          <span class="pm-wave"><i></i><i></i><i></i></span>
        </div>
      </div>
    </section>

    <!-- 权益矩阵：4×1 横排，共享一个外框，第一格更宽 -->
    <section class="pm-benefits">
      <header class="pm-sec-head">
        <div>
          <h2>会员权益</h2>
          <p>{{ currentLevelCode }} {{ currentLevelName }}已生效 4 项，升级后额度还会再涨。</p>
        </div>
        <span class="pm-sec-side">当前等级 · {{ currentLevelName }}</span>
      </header>

      <div class="pm-benefit-grid">
        <article class="pm-benefit pm-benefit--wide">
          <span class="pm-benefit-ic"><PvIcon name="ticket" :size="19" /></span>
          <b>{{ benefitDiscountText }}</b>
          <h3>全场会员价</h3>
          <p>下单自动按 {{ benefitDiscountText }} 计算，可与优惠券叠加，不挑商品。</p>
          <span class="pm-benefit-foot">{{ live ? '以订单实付为准' : '本月已省 ¥86.40' }}</span>
        </article>
        <article class="pm-benefit">
          <span class="pm-benefit-ic"><PvIcon name="coin" :size="19" /></span>
          <b>{{ benefitPointsText }}</b>
          <h3>积分加速</h3>
          <p>每消费 1 元得 {{ benefitPointsText }} 积分，退款按原路扣回。</p>
          <span class="pm-benefit-foot">{{ live ? '以积分流水为准' : '本月 +312 积分' }}</span>
        </article>
        <article class="pm-benefit">
          <span class="pm-benefit-ic"><PvIcon name="gift" :size="19" /></span>
          <b>{{ benefitCouponsText }}</b>
          <h3>每月专属券</h3>
          <p>每月按等级发放专属券，仅会员可领。</p>
          <span class="pm-benefit-foot">{{ live ? '以券包为准' : '本月还剩 1 张' }}</span>
        </article>
        <article class="pm-benefit">
          <span class="pm-benefit-ic"><PvIcon name="headset" :size="19" /></span>
          <b>{{ benefitSupportText }}</b>
          <h3>售后通道</h3>
          <p>退款与售后申请按等级权重进入队列。</p>
          <span class="pm-benefit-foot">{{ live ? '以售后队列为准' : '本月 2 次 · 均已处理' }}</span>
        </article>
      </div>
    </section>

    <!-- 签到 + 积分：8 / 4 分栏，左边是横向签到轨，右边是纵向流水 -->
    <section class="pm-daily">
      <div class="pm-checkin">
        <header class="pm-checkin-head">
          <div>
            <h2>连续签到</h2>
            <p>已连签 <b>{{ streakText }}</b> 天，今天签到得 {{ signinBasePoints }} 积分。</p>
          </div>
          <span class="pm-checkin-total">{{ live ? '实时签到状态' : '本月累计 14 天' }}</span>
        </header>

        <div class="pm-week">
          <div
            v-for="day in week"
            :key="day.label"
            class="pm-day"
            :class="{ done: day.done, today: day.today, reward: day.reward }"
          >
            <span class="pm-day-label">{{ day.label }}</span>
            <span class="pm-day-dot">
              <PvIcon v-if="day.done" name="check" :size="11" :stroke="2.6" />
              <PvIcon v-else-if="day.reward" name="gift" :size="12" />
              <i v-else></i>
            </span>
            <span class="pm-day-gain">{{ day.reward ? '+20' : '+10' }}</span>
            <span v-if="day.today" class="pm-day-now">今天</span>
          </div>
        </div>

        <div class="pm-rewards">
          <span class="pm-rewards-label">连签奖励</span>
          <div v-for="r in rewards" :key="r.day">
            <i>{{ r.day }}</i>
            <b>{{ r.prize }}</b>
            <em :class="{ on: r.on }">{{ r.state }}</em>
          </div>
        </div>

        <div class="pm-checkin-foot">
          <div class="pm-checkin-note">
            <PvIcon name="spark" :size="14" />
            <span>连续 7 天额外 +20 积分；中断后从第 1 天重新计算。</span>
          </div>
          <button
            type="button"
            class="pm-btn pm-btn--dark"
            :disabled="checkedToday || signingIn"
            @click="doCheckin"
          >
            {{ checkedToday ? '今日已签到' : (signingIn ? '签到中…' : `签到 +${signinBasePoints} 积分`) }}
          </button>
        </div>
      </div>

      <aside class="pm-points">
        <header class="pm-points-head">
          <div>
            <h2>积分明细</h2>
            <p>{{ live ? `最近 ${pointRows.length} 条` : '最近 7 天' }}</p>
          </div>
          <b>{{ points.toLocaleString('zh-CN') }}</b>
        </header>
        <ul class="pm-points-list">
          <li v-for="row in pointRows" :key="row.id">
            <span class="pm-points-ic" :class="row.tone"><PvIcon :name="row.icon" :size="13" /></span>
            <div>
              <b>{{ row.title }}</b>
              <span>{{ row.date }}</span>
            </div>
            <em :class="{ minus: row.delta < 0 }">{{ row.delta > 0 ? '+' : '' }}{{ row.delta }}</em>
          </li>
        </ul>
        <button type="button" class="pm-points-more" @click="notify('静态预览：不会打开完整积分记录')">
          查看全部积分记录
          <PvIcon name="arrow" :size="14" />
        </button>
      </aside>
    </section>

    <!-- 等级阶梯：五列高低错落，当前 V3 高亮 -->
    <section class="pm-ladder-sec">
      <header class="pm-sec-head">
        <div>
          <h2>等级路径</h2>
          <p>成长值来自订单实付、评价与签到，退款会扣回。</p>
        </div>
        <span class="pm-sec-side">你现在在 {{ currentLevelCode }}</span>
      </header>

      <div class="pm-ladder" :style="{ gridTemplateColumns: `repeat(${levels.length}, minmax(0, 1fr))` }">
        <article
          v-for="lv in levels"
          :key="lv.code"
          class="pm-step"
          :class="{ current: lv.current }"
        >
          <div class="pm-step-tx">
            <span class="pm-step-code">{{ lv.code }}</span>
            <h3>{{ lv.name }}</h3>
            <p class="pm-step-need">{{ lv.need }}</p>
            <ul>
              <li v-for="perk in lv.perks" :key="perk">{{ perk }}</li>
            </ul>
          </div>
          <div class="pm-step-bar" :style="{ height: lv.height + 'px' }">
            <span v-if="lv.current">当前</span>
            <em>{{ lv.current ? '成长值 ' + growth.toLocaleString('zh-CN') : lv.state }}</em>
          </div>
        </article>
      </div>

      <p class="pm-ladder-note">
        <PvIcon name="alert" :size="14" />
        {{ live
          ? '等级与成长值来自会员账户；折扣率与积分倍率以等级矩阵为准。'
          : '本页数值为演示数据，不代表真实账户；登录后自动切换为实时数据。' }}
      </p>
    </section>

    <transition name="pm-toast">
      <div v-if="toast" class="pm-toast">{{ toast }}</div>
    </transition>
  </main>
</template>

<script setup>
// ⚠️ 2026-10-19（第三批㉘ · 真实数据接入）：本页仍是高保真预览页（`/preview/member`），
//    版式与布局一字未改；数据来源按登录态分两档：
//      · 有 `customerid` ⇒ `POST /member/{levels,overview,points/logs,signin}`
//        （等级矩阵 / 积分账户 / 流水 / 真实签到）；
//      · 匿名 / 未连接后端 ⇒ 下面这套示例数据（`live=false`，判据见各 computed 的兜底）。
//    ⚠️ 签到按钮在实时档下**真的会写库**（`user_signin` + 积分流水，唯一键防重）。
import { computed, onMounted, ref } from 'vue'
import PvIcon from '../../components/preview/PvIcon.vue'
import { memberLevels, memberOverview, memberPointLogs, memberSignin } from '../../api/member'
import { getCustomerId, getUsername } from '../../api/auth'

const customerId = getCustomerId()
const live = ref(false)
const overviewData = ref(null)
const levelRows = ref(null)
const liveLogs = ref([])
const signingIn = ref(false)

const checkedToday = ref(false)
const growth = ref(5240)
const points = ref(1250)
const streak = ref(null)
const toast = ref('')
let timer = null

// ── 身份 / 等级 ────────────────────────────────────────────────────────────────
const currentLevel = computed(() => (live.value && overviewData.value && overviewData.value.level) || null)
const nextLevel = computed(() => (live.value && overviewData.value && overviewData.value.nextLevel) || null)
const currentLevelCode = computed(() => (currentLevel.value && currentLevel.value.Code) || 'V3')
const currentLevelName = computed(() => (currentLevel.value && currentLevel.value.Name) || '铂金')
const memberName = computed(() => getUsername() || '林晚')
const memberInitial = computed(() => String(memberName.value).slice(0, 1) || '晚')
const memberNoLine = computed(() => (live.value
  ? `会员号 ${customerId || '—'} · 实时账户`
  : '会员号 8820 4176 · 2024 年 3 月加入'))
const streakText = computed(() => (live.value
  ? (streak.value == null ? '—' : streak.value)
  : (checkedToday.value ? 4 : 3)))
const signinBasePoints = computed(() => (live.value ? 5 : 10))

const growthPct = computed(() => {
  const next = nextLevel.value
  if (live.value && next) {
    const floor = Number((currentLevel.value && currentLevel.value.MinGrowth) || 0)
    const ceiling = Number(next.MinGrowth) || 0
    const span = Math.max(1, ceiling - floor)
    return Math.min(100, Math.max(0, ((growth.value - floor) / span) * 100))
  }
  return Math.min(100, (growth.value / 10000) * 100)
})
const nextGoalText = computed(() => {
  if (!live.value) return 'V4 黑金卡需 10,000'
  const next = nextLevel.value
  return next
    ? `${next.Code} ${next.Name}需 ${Number(next.MinGrowth || 0).toLocaleString('zh-CN')}`
    : '已是最高等级'
})
const nextGapText = computed(() => {
  if (!live.value) return `距 V4 还差 ${Math.max(0, 10000 - growth.value).toLocaleString('zh-CN')} 成长值`
  const next = nextLevel.value
  if (!next) return '已达最高等级'
  const gap = Number((overviewData.value && overviewData.value.growthToNext) || 0)
  return `距 ${next.Code} 还差 ${gap.toLocaleString('zh-CN')} 成长值`
})

// ── 权益文案（全部由等级行推导；匿名档用示例值）────────────────────────────────
function rateText(rate) {
  const r = Number(rate)
  if (!Number.isFinite(r) || r <= 0) return null
  if (r >= 1) return '原价'
  return `${Number((r * 10).toFixed(1))} 折`
}
const benefitDiscountText = computed(() => rateText(currentLevel.value && currentLevel.value.DiscountRate) || '9 折')
const benefitPointsText = computed(() => {
  const p = Number(currentLevel.value && currentLevel.value.PointsRate)
  return Number.isFinite(p) && p > 0 ? `${Number(p.toFixed(2))}×` : '1.5×'
})
const benefitCouponsText = computed(() => {
  const c = currentLevel.value && currentLevel.value.MonthlyCoupons
  return c == null ? '2 张' : `${Number(c)} 张`
})
const benefitSupportText = computed(() => {
  if (!live.value) return '售后优先'
  return Number(currentLevel.value && currentLevel.value.PriorityWeight) > 0 ? '售后优先' : '标准客服'
})

// ── 签到轨 / 连签奖励 ─────────────────────────────────────────────────────────
const week = computed(() => {
  const base = [
    { label: '周一', done: true },
    { label: '周二', done: true },
    { label: '周三', done: true },
    { label: '今天', done: false, today: true },
    { label: '周五', done: false },
    { label: '周六', done: false },
    { label: '周日', done: false, reward: true },
  ]
  if (checkedToday.value) base[3].done = true
  return base
})

const demoRewards = [
  { day: '3 天', prize: '+10 积分', state: '已达成', on: true },
  { day: '7 天', prize: '+20 积分', state: '还差 4 天' },
  { day: '14 天', prize: '专属券', state: '还差 11 天' },
]
const rewards = computed(() => {
  if (!live.value) return demoRewards
  const n = Number(streak.value || 0)
  const row = (day, prize) => ({
    day: `${day} 天`,
    prize,
    state: n >= day ? '已达成' : `还差 ${day - n} 天`,
    on: n >= day,
  })
  return [row(7, '+20 积分'), row(14, '再 +20 积分'), row(30, '专属券')]
})

// ── 积分明细 ──────────────────────────────────────────────────────────────────
const demoPointRows = [
  { id: 1, title: '每日签到', date: '10-18 08:12', delta: 10, icon: 'calendar', tone: 'green' },
  { id: 2, title: '订单奖励 · 极简台灯', date: '10-16 21:40', delta: 128, icon: 'bag', tone: 'green' },
  { id: 3, title: '兑换 9 折券', date: '10-14 13:05', delta: -200, icon: 'ticket', tone: 'warm' },
  { id: 4, title: '每日签到', date: '10-12 07:58', delta: 10, icon: 'calendar', tone: 'green' },
  { id: 5, title: '晒单评价奖励', date: '10-10 19:22', delta: 20, icon: 'star', tone: 'green' },
]
const BIZ_LABEL = {
  signin: '每日签到',
  order_earn: '订单奖励',
  order_spend: '订单积分抵扣',
  expire: '积分过期',
  refund: '退款回退',
}
const BIZ_ICON = { signin: 'calendar', order_earn: 'bag', order_spend: 'ticket', expire: 'alert' }
const pointRows = computed(() => {
  if (!live.value) return demoPointRows
  return liveLogs.value.map((row) => {
    const delta = Number(row.ChangeAmount) || 0
    return {
      id: row.Id,
      title: row.Remark || BIZ_LABEL[row.BizType] || row.BizType || '积分变动',
      date: shortTime(row.created_at),
      delta,
      icon: BIZ_ICON[row.BizType] || 'coin',
      tone: delta >= 0 ? 'green' : 'warm',
    }
  })
})

// ── 等级阶梯 ──────────────────────────────────────────────────────────────────
const demoLevels = [
  { code: 'V1', name: '普通', need: '注册即得', perks: ['基础积分'], height: 44, state: '已达成' },
  { code: 'V2', name: '银卡', need: '成长值 1,000', perks: ['9.5 折', '生日券'], height: 74, state: '已达成' },
  {
    code: 'V3', name: '铂金', need: '成长值 5,000',
    perks: ['9 折', '1.5× 积分', '每月 2 张券', '售后优先'],
    height: 118, current: true, state: '当前',
  },
  { code: 'V4', name: '黑金', need: '成长值 10,000', perks: ['8.5 折', '2× 积分', '专属客服'], height: 156, state: '未解锁' },
  { code: 'V5', name: '黑钻', need: '成长值 30,000', perks: ['8 折', '3× 积分', '新品优先'], height: 196, state: '未解锁' },
]
const levels = computed(() => {
  if (!live.value || !Array.isArray(levelRows.value) || !levelRows.value.length) return demoLevels
  const currentNo = Number((currentLevel.value && currentLevel.value.LevelNo) || 0)
  return levelRows.value.map((row, index) => {
    const no = Number(row.LevelNo) || 0
    return {
      code: row.Code || `V${no}`,
      name: row.Name || `等级 ${no}`,
      need: no === 0 ? '注册即得' : `成长值 ${Number(row.MinGrowth || 0).toLocaleString('zh-CN')}`,
      perks: perksOf(row),
      height: 44 + index * 24,
      current: no === currentNo,
      state: no < currentNo ? '已达成' : '未解锁',
    }
  })
})

/** `BenefitsJson`（`{discount,points,coupons,support}`）→ 三/四行短文案；坏 JSON 回落列值。 */
function perksOf(row) {
  try {
    const b = JSON.parse(row.BenefitsJson || '{}')
    const list = [b.discount, b.points, b.coupons, b.support].filter(Boolean)
    if (list.length) return list
  } catch (e) { /* 不是 JSON ⇒ 走下面 */ }
  return [rateText(row.DiscountRate) || '无折扣', `${Number(row.PointsRate || 1)}× 积分`,
    `${Number(row.MonthlyCoupons || 0)} 张券/月`]
}

// ── 取数 ──────────────────────────────────────────────────────────────────────
onMounted(loadAll)

async function loadAll() {
  try {
    const res = await memberLevels()
    if (res && res.success && Array.isArray(res.list)) levelRows.value = res.list
  } catch (e) {
    console.warn('[MemberPreview] 等级矩阵读取失败，沿用示例等级', e)
  }
  if (!customerId) return
  await loadOverview()
  await loadLogs()
}

async function loadOverview() {
  try {
    const res = await memberOverview(customerId)
    if (!res || !res.success) return
    overviewData.value = res
    live.value = true
    const account = res.account || {}
    points.value = Number(account.Balance) || 0
    growth.value = Number(account.GrowthValue) || 0
    checkedToday.value = !!res.signedToday
  } catch (e) {
    console.warn('[MemberPreview] 会员概览读取失败，沿用示例数据', e)
  }
}

async function loadLogs() {
  try {
    const res = await memberPointLogs({ customerId, limit: 5 })
    if (res && res.success && Array.isArray(res.list)) liveLogs.value = res.list
  } catch (e) {
    console.warn('[MemberPreview] 积分流水读取失败', e)
  }
}

async function doCheckin() {
  if (checkedToday.value || signingIn.value) return
  if (!live.value) {
    // 匿名档：保持原静态预览行为（不写库）。
    checkedToday.value = true
    points.value += 10
    growth.value += 10
    notify('已签到，+10 积分（演示）')
    return
  }
  signingIn.value = true
  try {
    const res = await memberSignin(customerId)
    if (res && res.success) {
      checkedToday.value = true
      if (res.balance != null) points.value = Number(res.balance)
      if (res.streak != null) streak.value = Number(res.streak)
      await loadOverview()
      await loadLogs()
      notify(`已签到，+${Number(res.points) || 0} 积分`)
    } else {
      if (res && res.signedToday) checkedToday.value = true
      notify((res && res.msg) || '签到失败，请稍后再试')
    }
  } catch (e) {
    console.warn('[MemberPreview] 签到失败', e)
    notify('签到失败，请稍后再试')
  } finally {
    signingIn.value = false
  }
}

/** `2026-10-19 09:24:00` → `10-19 09:24`。 */
function shortTime(value) {
  const m = String(value || '').match(/^\d{4}-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/)
  return m ? `${m[1]}-${m[2]} ${m[3]}:${m[4]}` : String(value || '')
}

function notify(text) {
  toast.value = text
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { toast.value = '' }, 1800)
}
</script>

<style scoped>
.pm-page {
  max-width: 1060px;
  margin: 0 auto;
  padding: 40px 38px 68px;
  color: #1a241e;
}

/* ── 黑金大卡 ── */
.pm-hero { margin-bottom: 50px; }
.pm-card {
  position: relative;
  overflow: hidden;
  border-radius: 20px;
  padding: 26px 32px 24px;
  border: 1px solid #a98a43;
  background:
    radial-gradient(560px 300px at 88% -10%, rgba(212, 175, 55, .2), transparent 66%),
    radial-gradient(420px 260px at 8% 110%, rgba(212, 175, 55, .08), transparent 70%),
    linear-gradient(132deg, #201c14 0%, #100f0c 46%, #1b1710 100%);
  box-shadow:
    0 30px 70px -34px rgba(20, 16, 8, .75),
    inset 0 1px 0 rgba(255, 240, 200, .1);
  color: #f0ecdf;
}
.pm-card::before {
  content: '';
  position: absolute;
  inset: 9px;
  border: 1px solid rgba(212, 175, 55, .22);
  border-radius: 13px;
  pointer-events: none;
}
.pm-card::after {
  content: '';
  position: absolute;
  top: -60%;
  left: -20%;
  width: 70%;
  height: 220%;
  transform: rotate(18deg);
  background: linear-gradient(90deg, transparent, rgba(255, 246, 214, .045), transparent);
  pointer-events: none;
}
.pm-watermark {
  position: absolute;
  right: 22px;
  top: -18px;
  font-family: var(--font-display);
  font-size: 168px;
  line-height: 1;
  color: rgba(212, 175, 55, .045);
  letter-spacing: -.06em;
  pointer-events: none;
}
.pm-card-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.pm-club { font-size: 11px; letter-spacing: .14em; color: #a99a78; }
.pm-tier {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid rgba(212, 175, 55, .45);
  color: #e6c87d;
  font-size: 10.5px;
  background: rgba(212, 175, 55, .07);
  border-radius: 999px;
}

.pm-card-main {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  margin-top: 26px;
}
.pm-id { display: flex; align-items: center; gap: 15px; }
.pm-avatar {
  width: 62px;
  height: 62px;
  flex: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 26px;
  color: #2a210f;
  background: linear-gradient(145deg, #f3dfa5, #c9a24a 62%, #8d6c25);
  border: 1px solid rgba(255, 240, 200, .55);
  box-shadow: 0 6px 18px rgba(0, 0, 0, .32);
}
.pm-id-tx h1 {
  font-family: var(--font-display);
  font-size: 30px;
  font-weight: 500;
  line-height: 1.1;
  color: #f6f2e5;
}
.pm-id-tx p { margin-top: 5px; font-size: 10.5px; color: #9c927b; letter-spacing: .02em; }
.pm-id-tags { display: flex; gap: 6px; margin-top: 9px; }
.pm-id-tags span {
  font-size: 9px;
  color: #b9ac8b;
  border: 1px solid rgba(212, 175, 55, .22);
  padding: 2px 7px;
  border-radius: 999px;
}

.pm-assets { display: flex; gap: 30px; text-align: right; }
.pm-assets b {
  display: block;
  font-family: var(--font-mono);
  font-size: 22px;
  font-weight: 500;
  color: #e9cd8a;
  letter-spacing: -.04em;
}
.pm-assets span { display: block; margin-top: 4px; font-size: 9.5px; color: #968c74; }

.pm-growth { position: relative; z-index: 1; margin-top: 26px; }
.pm-growth-head {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  font-size: 10px;
  color: #9c927b;
}
.pm-growth-head b { color: #e2cd94; font-family: var(--font-mono); font-weight: 500; }
.pm-bar {
  position: relative;
  height: 6px;
  margin-top: 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, .09);
  overflow: visible;
}
.pm-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #8d6c25, #d4af37 60%, #f4dfa4);
  box-shadow: 0 0 14px rgba(212, 175, 55, .38);
  transition: width .5s ease;
}
.pm-growth-foot {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin-top: 9px;
  font-size: 9.5px;
  color: #8e856f;
}

.pm-card-foot {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 20px;
  padding-top: 17px;
  border-top: 1px solid rgba(212, 175, 55, .16);
}
.pm-perks { display: flex; gap: 8px; flex-wrap: wrap; }
.pm-perks span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  color: #c9bb98;
  padding: 4px 9px;
  border: 1px solid rgba(212, 175, 55, .18);
  background: rgba(255, 255, 255, .025);
  border-radius: 6px;
}
.pm-card-actions { display: flex; gap: 9px; flex: none; }
.pm-btn {
  height: 36px;
  padding: 0 16px;
  border-radius: 7px;
  font-size: 11.5px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background .18s, border-color .18s, color .18s, opacity .18s;
}
.pm-btn--gold { background: linear-gradient(135deg, #efd896, #c9a24a); color: #241d0d; font-weight: 600; }
.pm-btn--gold:hover { background: linear-gradient(135deg, #f7e6b8, #d8b665); }
.pm-btn--ghost { background: transparent; border-color: rgba(233, 205, 138, .32); color: #d8c79c; }
.pm-btn--ghost:hover { background: rgba(233, 205, 138, .08); }
.pm-btn--dark { background: #1d2b24; color: #fff; }
.pm-btn--dark:hover { background: #2f6b58; }
.pm-btn:disabled { opacity: .5; cursor: default; }

.pm-chip-row {
  position: absolute;
  right: 34px;
  bottom: 84px;
  display: flex;
  align-items: center;
  gap: 12px;
  opacity: .5;
}
.pm-chip {
  width: 28px; height: 21px;
  border-radius: 4px;
  border: 1px solid rgba(233, 205, 138, .5);
  background: linear-gradient(135deg, rgba(233, 205, 138, .22), rgba(233, 205, 138, .05));
}
.pm-wave { display: flex; align-items: center; gap: 2px; transform: rotate(90deg); }
.pm-wave i { display: block; width: 1px; height: 5px; background: rgba(233, 205, 138, .55); border-radius: 2px; }
.pm-wave i:nth-child(2) { height: 10px; }
.pm-wave i:nth-child(3) { height: 7px; }

/* ── 区块标题 ── */
.pm-sec-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
}
.pm-sec-head h2 { font-family: var(--font-display); font-size: 23px; font-weight: 500; }
.pm-sec-head p { margin-top: 5px; font-size: 11px; color: #8d9992; }
.pm-sec-side { font-size: 10.5px; color: #8d9992; }

/* ── 权益矩阵 ── */
.pm-benefits { margin-bottom: 50px; }
.pm-benefit-grid {
  display: grid;
  grid-template-columns: 1.35fr 1fr 1fr 1fr;
  border-top: 1px solid #d8e0dc;
  border-bottom: 1px solid #d8e0dc;
}
.pm-benefit {
  position: relative;
  padding: 22px 20px 20px;
  border-right: 1px solid #e2e8e4;
}
.pm-benefit:last-child { border-right: 0; }
.pm-benefit--wide { padding-left: 22px; background: rgba(255, 255, 255, .55); }
.pm-benefit-ic {
  width: 34px; height: 34px;
  display: grid; place-items: center;
  border: 1px solid #d9e3de;
  border-radius: 9px;
  color: #4d6a5b;
  background: #fff;
}
.pm-benefit b {
  display: block;
  margin-top: 13px;
  font-family: var(--font-mono);
  font-size: 27px;
  font-weight: 500;
  line-height: 1;
  letter-spacing: -.05em;
  color: #1d2b24;
}
.pm-benefit h3 { margin-top: 5px; font-size: 12.5px; font-weight: 600; color: #3a4a41; }
.pm-benefit p { margin-top: 7px; font-size: 10.5px; line-height: 1.7; color: #87938c; }
.pm-benefit-foot {
  display: block;
  margin-top: 12px;
  padding-top: 9px;
  border-top: 1px dashed #e2e8e4;
  font-size: 9.5px;
  color: #6b8878;
}
.pm-benefit--wide .pm-benefit-foot { color: #2f6b58; }

/* ── 签到 + 积分 ── */
.pm-daily {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 20px;
  align-items: stretch;
  margin-bottom: 50px;
}
.pm-checkin {
  grid-column: span 8;
  background: #fff;
  border: 1px solid #e1e7e3;
  padding: 22px 24px 20px;
}
.pm-checkin-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.pm-checkin-head h2 { font-family: var(--font-display); font-size: 21px; font-weight: 500; }
.pm-checkin-head p { margin-top: 5px; font-size: 11px; color: #8d9992; }
.pm-checkin-head p b { color: #2f6b58; font-family: var(--font-mono); }
.pm-checkin-total { font-size: 9.5px; color: #9aa69f; border: 1px solid #e3e9e5; padding: 3px 8px; border-radius: 999px; }

.pm-week {
  position: relative;
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  margin-top: 24px;
}
.pm-week::before {
  content: '';
  position: absolute;
  left: 6%;
  right: 6%;
  top: 35px;
  height: 1px;
  background: #e2e8e4;
}
.pm-day {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 0 2px 6px;
}
.pm-day-label { font-size: 9.5px; color: #9aa69f; }
.pm-day-dot {
  width: 30px; height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #fff;
  border: 1px solid #dfe6e2;
  color: #a3aea7;
}
.pm-day-dot i { width: 4px; height: 4px; border-radius: 50%; background: #c3ccc7; }
.pm-day.done .pm-day-dot { background: #2f6b58; border-color: #2f6b58; color: #fff; }
.pm-day.today .pm-day-dot {
  border-color: #c9a24a;
  color: #8b6a1f;
  box-shadow: 0 0 0 4px rgba(201, 162, 74, .14);
}
.pm-day.today.done .pm-day-dot { background: #2f6b58; border-color: #2f6b58; color: #fff; }
.pm-day.reward .pm-day-dot { border-style: dashed; color: #a47c2a; }
.pm-day-gain { font-family: var(--font-mono); font-size: 9px; color: #8e9a93; }
.pm-day.done .pm-day-gain { color: #2f6b58; }
.pm-day-now {
  position: absolute;
  bottom: -9px;
  font-size: 8.5px;
  color: #8b6a1f;
  background: #fbf3df;
  border: 1px solid #f0e0b8;
  padding: 1px 6px;
  border-radius: 999px;
}
.pm-rewards {
  display: grid;
  grid-template-columns: auto repeat(3, 1fr);
  gap: 1px;
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid #eef2ef;
}
.pm-rewards-label { align-self: center; padding-right: 12px; font-size: 9.5px; color: #9aa69f; }
.pm-rewards > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 10px;
  border-left: 1px dashed #e5ebe7;
}
.pm-rewards i { font-style: normal; font-size: 9px; color: #9aa69f; }
.pm-rewards b { font-size: 10.5px; font-weight: 600; color: #3d4d44; }
.pm-rewards em { font-style: normal; font-size: 8.5px; color: #a3aea7; }
.pm-rewards em.on { color: #2f6b58; }

.pm-checkin-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 22px;
  padding-top: 14px;
  border-top: 1px solid #eef2ef;
}
.pm-checkin-note { display: flex; align-items: flex-start; gap: 7px; font-size: 10px; line-height: 1.6; color: #929e97; }
.pm-checkin-note svg { color: #b28d3d; margin-top: 1px; }

.pm-points {
  grid-column: span 4;
  background: #fff;
  border: 1px solid #e1e7e3;
  padding: 22px 20px 16px;
  display: flex;
  flex-direction: column;
}
.pm-points-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.pm-points-head h2 { font-family: var(--font-display); font-size: 19px; font-weight: 500; }
.pm-points-head p { margin-top: 4px; font-size: 10px; color: #9aa69f; }
.pm-points-head > b {
  font-family: var(--font-mono);
  font-size: 22px;
  font-weight: 500;
  color: #2f6b58;
  letter-spacing: -.04em;
}
.pm-points-list { flex: 1; margin: 14px 0 0; padding: 0; list-style: none; }
.pm-points-list li {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 0;
  border-top: 1px solid #eef2ef;
}
.pm-points-ic {
  width: 26px; height: 26px;
  flex: none;
  display: grid; place-items: center;
  border-radius: 7px;
  color: #3f8061;
  background: #edf7f1;
}
.pm-points-ic.warm { color: #9a7425; background: #fbf4e2; }
.pm-points-list li > div { min-width: 0; flex: 1; }
.pm-points-list b { display: block; font-size: 10.5px; font-weight: 600; color: #3d4d44; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pm-points-list span { display: block; margin-top: 2px; font-size: 8.5px; color: #a0aaa4; }
.pm-points-list em {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: #1f8a5c;
}
.pm-points-list em.minus { color: #8a9590; }
.pm-points-more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 100%;
  margin-top: 10px;
  padding-top: 12px;
  border: 0;
  border-top: 1px solid #eef2ef;
  background: transparent;
  font-size: 10px;
  color: #5e7a6c;
  cursor: pointer;
}

/* ── 等级阶梯 ── */
.pm-ladder-sec { margin-bottom: 12px; }
.pm-ladder {
  display: grid;
  grid-template-columns: 1fr 1fr 1.35fr 1fr 1fr;
  gap: 12px;
  align-items: end;
  padding: 18px 18px 0;
  border: 1px solid #e1e7e3;
  background:
    linear-gradient(180deg, #fff, #fafcfb);
}
.pm-step {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  min-height: 268px;
}
.pm-step-tx { padding: 0 4px 12px; }
.pm-step-code {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 26px;
  height: 20px;
  padding: 0 6px;
  border-radius: 5px;
  border: 1px solid #dfe6e2;
  font-family: var(--font-mono);
  font-size: 9px;
  color: #7d8a83;
  background: #fff;
}
.pm-step-tx h3 { margin-top: 8px; font-size: 12.5px; font-weight: 600; color: #3b4a41; }
.pm-step-need { margin-top: 4px; font-size: 9px; color: #98a39c; }
.pm-step-tx ul { margin: 9px 0 0; padding: 0; list-style: none; }
.pm-step-tx li { font-size: 9.5px; line-height: 1.8; color: #87938c; }
.pm-step-bar {
  position: relative;
  border-radius: 6px 6px 0 0;
  background: linear-gradient(180deg, #e8eeeb, #dbe4df);
  border: 1px solid #d6e0da;
  border-bottom: 0;
}
.pm-step-bar span {
  position: absolute;
  top: -22px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 9px;
  color: #8b6a1f;
  background: #fbf3df;
  border: 1px solid #f0e0b8;
  padding: 2px 8px;
  border-radius: 999px;
  white-space: nowrap;
}
.pm-step.current .pm-step-code { background: #1d2b24; border-color: #1d2b24; color: #e9cd8a; }
.pm-step.current h3 { color: #1d2b24; }
.pm-step.current .pm-step-bar {
  background: linear-gradient(180deg, #efd896, #c9a24a);
  border-color: #b99b4d;
  box-shadow: 0 10px 24px -12px rgba(176, 140, 52, .55);
}
.pm-step-bar em {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 8px;
  text-align: center;
  font-style: normal;
  font-size: 8.5px;
  color: #8e9a93;
}
.pm-step.current .pm-step-bar em {
  font-family: var(--font-mono);
  font-size: 9px;
  color: #57430f;
}
.pm-ladder-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 14px;
  font-size: 9.5px;
  line-height: 1.6;
  color: #98a39c;
}

.pm-toast {
  position: fixed;
  left: 50%;
  bottom: 40px;
  z-index: 120;
  transform: translateX(-50%);
  padding: 10px 16px;
  border-radius: 8px;
  background: rgba(23, 34, 28, .93);
  color: #fff;
  font-size: 12px;
}
.pm-toast-enter-active, .pm-toast-leave-active { transition: opacity .2s, transform .2s; }
.pm-toast-enter-from, .pm-toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }

@media (max-width: 950px) {
  .pm-page { padding: 30px 22px 52px; }
  .pm-card-main { flex-direction: column; align-items: flex-start; }
  .pm-assets { text-align: left; }
  .pm-card-foot { flex-direction: column; align-items: flex-start; }
  .pm-benefit-grid { grid-template-columns: 1fr 1fr; }
  .pm-benefit:nth-child(2) { border-right: 0; }
  .pm-benefit:nth-child(n+3) { border-top: 1px solid #e2e8e4; }
  .pm-checkin, .pm-points { grid-column: 1 / -1; }
  .pm-ladder { overflow-x: auto; grid-template-columns: repeat(5, minmax(120px, 1fr)); }
}
@media (max-width: 620px) {
  .pm-card { padding: 20px 20px 20px; }
  .pm-card::before { inset: 6px; }
  .pm-card-top { flex-direction: column; align-items: flex-start; gap: 10px; }
  .pm-watermark { font-size: 120px; }
  .pm-assets { gap: 18px; flex-wrap: wrap; }
  .pm-assets b { font-size: 18px; }
  .pm-id-tx h1 { font-size: 24px; }
  .pm-chip-row { display: none; }
  .pm-growth-foot { flex-direction: column; gap: 4px; }
  .pm-perks { gap: 5px; }
  .pm-card-actions { width: 100%; }
  .pm-card-actions .pm-btn { flex: 1; }
  .pm-benefit-grid { grid-template-columns: 1fr; }
  .pm-benefit { border-right: 0; }
  .pm-benefit + .pm-benefit { border-top: 1px solid #e2e8e4; }
  .pm-week { gap: 2px; }
  .pm-day-dot { width: 26px; height: 26px; }
  .pm-week::before { top: 33px; }
  .pm-checkin-foot { flex-direction: column; align-items: stretch; }
  .pm-checkin-foot .pm-btn { width: 100%; }
}
</style>
