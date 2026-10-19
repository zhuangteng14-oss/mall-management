<template>
  <!--
    会员中心（批四第二批 commit 3，2026-10-19）—— 路由 `/customer/user/member`。
    挂在「用户中心」壳下（`customerUser` 的子记录，`meta.sub = 'member'`）⇒ 左菜单在场，
    本页只承载右栏（与 `UserInfoPage.vue` 同形）。

    ⚠️ 等级/余额**只读本地会话恢复态**（`api/auth.js`），本页启动**不再打** `/user/me`：
    `main.js` 已经打过一次。开通成功后等级改为本地 ref，提示「刷新后全站按会员价」——
    其他页面要重新进站 / 刷新才会拿到新档位（身份恢复只在启动时跑一次）。

    第二步 UI 升维（2026-10-19）：黑金会员卡 + 积分获取说明 + 积分流水（骨架 / 空态）。
    页面上每一个数字都来自 `MemberController` 的概览 / 流水接口，**不编获取方式**：
    后端只有「签到」「收货返积分」两条发放链，所以「带图评价」这类没有落地的途径不写。
  -->
  <main class="uc-main">
    <header class="mc-hd">
      <h1 class="page-h">会员中心</h1>
      <p class="page-h-sub">积分、等级与专属权益都在这里；会员价可与优惠券叠加。</p>
    </header>

    <!-- 黑金会员卡：全页唯一的「重色块」，其余全部退到白面。 -->
    <section class="mc-card">
      <span class="mc-mesh" aria-hidden="true"></span>
      <div class="mc-top">
        <div class="mc-ident">
          <span class="mc-seal" aria-hidden="true">{{ levelInitial }}</span>
          <div class="mc-ident-tx">
            <b class="mc-level">{{ levelName }}</b>
            <span class="mc-level-sub">{{ levelCode }}<template v-if="benefitLine"> · {{ benefitLine }}</template></span>
          </div>
        </div>
        <span class="mc-chip" :class="{ on: isVip }">{{ isVip ? '权益生效中' : '普通用户' }}</span>
      </div>

      <div class="mc-metrics">
        <div class="mc-points">
          <span class="mc-k">可用积分</span>
          <span class="mc-v">{{ pointsText }}</span>
          <span class="mc-u">积分</span>
        </div>
        <div class="mc-balance">
          <span class="mc-k">账户余额</span>
          <span class="mc-b">¥{{ balanceText }}</span>
        </div>
      </div>

      <div v-if="nextLevel" class="mc-growth">
        <div class="mc-growth-hd">
          <span>距 {{ nextLevel.Name }}</span>
          <span>还差 {{ growthToNext }} 成长值</span>
        </div>
        <div class="mc-bar"><i :style="{ width: growthPct + '%' }"></i></div>
      </div>
      <p v-else-if="overview" class="mc-max">已是最高等级</p>

      <div class="mc-actions">
        <button type="button" class="mc-sign" :disabled="signing || signedToday || !customerId" @click="doSignin">
          {{ signing ? '签到中…' : signedToday ? '今日已签到' : '签到 +5' }}
        </button>
        <button type="button" class="mc-ghost" :disabled="claiming || !isVip" @click="doClaimDaily">
          {{ claiming ? '领取中…' : '领取今日专属券' }}
        </button>
        <button v-if="!isVip" type="button" class="mc-ghost" :disabled="opening" @click="doOpen">
          {{ opening ? '开通中…' : '开通会员' }}
        </button>
      </div>
      <p v-if="actionMsg" class="mc-msg" :class="{ bad: !actionOk }">{{ actionMsg }}</p>
      <p v-if="overviewErr" class="mc-msg bad">{{ overviewErr }}</p>
    </section>

    <!-- 积分获取说明：只写后端真有的两条发放链（签到 / 收货返积分）。 -->
    <section class="mc-sec">
      <div class="sec-hd">
        <h2 class="sec-h">积分怎么赚</h2>
        <span class="sec-sub">已上线的获取方式</span>
      </div>
      <div class="earn-grid">
        <article class="earn">
          <span class="earn-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3.5" y="5" width="17" height="15.5" rx="2.5" />
              <path d="M3.5 9.5h17M8 3.5v3M16 3.5v3M8.5 14l2.2 2.2 4.3-4.4" />
            </svg>
          </span>
          <div class="earn-tx">
            <div class="earn-hd"><h3>每日签到</h3><span class="earn-tag">+5 / 天</span></div>
            <p>每天签到得 5 积分；连续签到满 7 天的那一天再加 20 积分。</p>
          </div>
        </article>
        <article class="earn">
          <span class="earn-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M5 8h14l-1.2 11.1a1.5 1.5 0 0 1-1.5 1.4H7.7a1.5 1.5 0 0 1-1.5-1.4z" />
              <path d="M8.5 8V6a3.5 3.5 0 0 1 7 0v2" />
            </svg>
          </span>
          <div class="earn-tx">
            <div class="earn-hd"><h3>购物返积分</h3><span class="earn-tag">按等级 1–2.5 倍</span></div>
            <p>确认收货后按实付金额发放：每 ¥1 得 1–2.5 积分，倍率随会员等级提升。</p>
          </div>
        </article>
      </div>
      <ul class="rules">
        <li><b>100 积分 = ¥1</b><span>结算时直接抵扣</span></li>
        <li><b>单笔最多抵 20%</b><span>按券后金额算上限</span></li>
        <li><b>365 天有效</b><span>过期按获取时间先进先出</span></li>
      </ul>
    </section>

    <!-- 积分流水：骨架 → 空态 → 列表 三态；时间由库内 UTC 文本转本地。 -->
    <section class="mc-sec">
      <div class="sec-hd">
        <h2 class="sec-h">积分明细</h2>
        <span v-if="logs.length" class="sec-sub">最近 {{ logs.length }} 条</span>
        <button v-if="logsErr" type="button" class="sec-retry" @click="loadLogs">重新加载</button>
      </div>

      <div v-if="logsLoading && !logs.length" class="log-sk" aria-hidden="true">
        <div v-for="n in 3" :key="n" class="log-sk-row">
          <span class="sk-dot"></span>
          <span class="sk-lines"><i></i><i class="short"></i></span>
          <span class="sk-amt"></span>
        </div>
      </div>

      <div v-else-if="!logs.length" class="log-empty">
        <span class="log-empty-ico" aria-hidden="true">积</span>
        <b>还没有积分记录</b>
        <p>{{ logsErr || '签到或确认收货后，获取与消耗明细会出现在这里。' }}</p>
      </div>

      <ul v-else class="log-list">
        <li v-for="row in logs" :key="row.id" class="log-row">
          <span class="log-glyph" :class="row.tone" aria-hidden="true">{{ row.glyph }}</span>
          <div class="log-main">
            <b>{{ row.title }}</b>
            <span>{{ row.time }}<template v-if="row.remark"> · {{ row.remark }}</template></span>
          </div>
          <div class="log-amt">
            <b :class="row.tone">{{ row.sign }}{{ row.amount }}</b>
            <span>余额 {{ row.balance }}</span>
          </div>
        </li>
      </ul>
    </section>

    <!-- 原有会员权益文案保留（这批没有改权益本身，只换了排版）。 -->
    <section class="mc-sec">
      <div class="sec-hd"><h2 class="sec-h">会员权益</h2></div>
      <ul class="bf-list">
        <li><b>9 折</b><span>商品详情、购物车与结算价统一按会员价计算，优惠券在会员价基础上再减。</span></li>
        <li><b>每日专属券</b><span>每天可领一张会员专属券（满 50 减 5），当天限领一次。</span></li>
        <li><b>售后优先</b><span>平台售后队列按会员等级优先处理。</span></li>
      </ul>
    </section>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getBalance, getCustomerId, getMemberLevel, getUsername, setMemberLevel } from '../api/auth'
import { openMember, claimDailyCoupon, memberOverview, memberSignin, memberPointLogs } from '../api/member'

// 等级用本地 ref：开通成功后立即切换页面展示；跨页面同步靠下一次 `/user/me`（刷新 / 新标签页）。
const level = ref(Number(getMemberLevel()) || 0)
const isVip = computed(() => level.value > 0)
const balance = ref(Number(getBalance()) || 0)
const balanceText = computed(() => balance.value.toFixed(2))

// ── 会员概览 / 签到 / 积分流水（第三批㉘㉙，2026-10-19）────────────────────────────
// ⚠️ 这一组端点收的是**客户业务号**（`user.customerid`），不是登录名 —— 与上面的
//    `openMember` / `claimDailyCoupon` 不是同一套身份判据（见 `api/member.js` 分段注释）。
const customerId = getCustomerId() || ''
const overview = ref(null)
const account = computed(() => (overview.value && overview.value.account) || null)
const pointsLoading = ref(false)
const overviewErr = ref('')
const signedToday = computed(() => !!(overview.value && overview.value.signedToday))
const pointsBalance = computed(() => (account.value ? Number(account.value.Balance || 0) : 0))
const pointsText = computed(() => (pointsLoading.value && !overview.value ? '—' : String(pointsBalance.value)))

const levelName = computed(() => {
  const lv = overview.value && overview.value.level
  if (lv && lv.Name) return lv.Name
  return isVip.value ? 'VIP 会员' : '普通会员'
})
const levelCode = computed(() => {
  const lv = overview.value && overview.value.level
  return (lv && lv.Code) || (isVip.value ? 'V1' : 'V0')
})
const levelInitial = computed(() => String(levelName.value).slice(0, 1) || '会')
const nextLevel = computed(() => (overview.value && overview.value.nextLevel) || null)
const growthToNext = computed(() => Number(overview.value && overview.value.growthToNext) || 0)
const growthPct = computed(() => {
  const lv = overview.value && overview.value.level
  const nx = nextLevel.value
  if (!lv || !nx) return 100
  const from = Number(lv.MinGrowth || 0)
  const to = Number(nx.MinGrowth || 0)
  const now = Number((account.value && account.value.GrowthValue) || 0)
  if (to <= from) return 100
  return Math.max(0, Math.min(100, Math.round((now - from) * 100 / (to - from))))
})
const benefitLine = computed(() => {
  const lv = overview.value && overview.value.level
  if (!lv) return ''
  const rate = Number(lv.DiscountRate)
  const discount = Number.isFinite(rate) && rate > 0 && rate < 1
    ? `${Math.round(rate * 1000) / 100} 折`
    : '无折扣'
  const pointsRate = Number(lv.PointsRate)
  const rateText = Number.isFinite(pointsRate) && pointsRate > 0 ? `积分 ${pointsRate} 倍` : ''
  return [discount, rateText].filter(Boolean).join(' · ')
})

const logs = ref([])
const logsLoading = ref(false)
const logsErr = ref('')

async function loadOverview() {
  if (!customerId) {
    overviewErr.value = '未识别到客户账号，积分信息暂不可用'
    return
  }
  pointsLoading.value = true
  overviewErr.value = ''
  try {
    const data = await memberOverview(customerId)
    if (data && data.success) overview.value = data
    else overviewErr.value = (data && data.msg) || '积分信息读取失败'
  } catch (e) {
    overviewErr.value = '积分信息读取失败：' + (e && e.message ? e.message : '网络错误')
  } finally {
    pointsLoading.value = false
  }
}

async function loadLogs() {
  if (!customerId) return
  logsLoading.value = true
  logsErr.value = ''
  try {
    const data = await memberPointLogs({ customerId, limit: 20 })
    if (data && data.success) logs.value = (data.list || []).map(mapLog)
    else logsErr.value = (data && data.msg) || '积分明细读取失败'
  } catch (e) {
    logsErr.value = '积分明细读取失败：' + (e && e.message ? e.message : '网络错误')
  } finally {
    logsLoading.value = false
  }
}

const BIZ_TITLE = { signin: '签到奖励', order_earn: '购物返积分', order_spend: '积分抵扣', order_refund: '退款返还', expire: '积分过期' }
const BIZ_GLYPH = { signin: '签', order_earn: '购', order_spend: '抵', order_refund: '退', expire: '期' }

function mapLog(row) {
  const n = Number(row.ChangeAmount || 0)
  const type = String(row.BizType || '')
  const base = BIZ_TITLE[type] || ''
  return {
    id: row.Id,
    title: base || row.Remark || type || '积分变动',
    glyph: BIZ_GLYPH[type] || '分',
    tone: n >= 0 ? 'in' : 'out',
    sign: n > 0 ? '+' : n < 0 ? '−' : '',
    amount: Math.abs(n),
    balance: Number(row.BalanceAfter || 0),
    time: fmtTime(row.created_at),
    remark: row.Remark && row.Remark !== base ? row.Remark : ''
  }
}

/** 库内是 UTC 文本（`DATE_FORMAT` 不带时区）⇒ 补 `Z` 再交给本地时区显示。 */
function fmtTime(value) {
  const raw = String(value || '')
  if (!raw) return '—'
  const iso = raw.replace(' ', 'T')
  const zoned = /[zZ]$|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : iso + 'Z'
  const d = new Date(zoned)
  if (Number.isNaN(d.getTime())) return raw
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// ── 三个动作共用一条提示位（哪个动作的结果都落在卡内同一处，不各占一行）──────────
const actionMsg = ref('')
const actionOk = ref(false)
function say(ok, msg) { actionOk.value = ok; actionMsg.value = msg }

const signing = ref(false)
async function doSignin() {
  if (!customerId) { say(false, '未识别到客户账号，请重新登录'); return }
  signing.value = true
  actionMsg.value = ''
  try {
    const data = await memberSignin(customerId)
    if (data && data.success) {
      say(true, data.points > 0 ? `签到成功，+${data.points} 积分` : (data.msg || '签到成功'))
      await Promise.all([loadOverview(), loadLogs()])
    } else {
      say(false, (data && data.msg) || '签到失败')
      if (data && data.signedToday) await loadOverview()
    }
  } catch (e) {
    say(false, '签到失败：' + (e && e.message ? e.message : '网络错误'))
  } finally {
    signing.value = false
  }
}

const opening = ref(false)
async function doOpen() {
  const username = getUsername()
  if (!username) { say(false, '未识别到登录账号，请重新登录'); return }
  opening.value = true
  actionMsg.value = ''
  try {
    const data = await openMember(username)
    if (data.success) {
      setMemberLevel(Number(data.memberLevel) || 1); level.value = Number(getMemberLevel()) || 1   // ⚠️ 同步写 `sessionStorage` ⇒ 刷新后仍是 VIP（`main.js` 对已登录页不再打 `/user/me`）
      say(true, '会员开通成功 —— 刷新页面后，商品详情 / 购物车 / 结算都会按会员价计价')
      await loadOverview()
    } else {
      say(false, data.msg || '开通失败')
    }
  } catch (e) {
    say(false, '开通失败：' + (e && e.message ? e.message : '网络错误'))
  } finally {
    opening.value = false
  }
}

const claiming = ref(false)
async function doClaimDaily() {
  const username = getUsername()
  if (!username) { say(false, '未识别到登录账号，请重新登录'); return }
  claiming.value = true
  actionMsg.value = ''
  try {
    const data = await claimDailyCoupon(username)
    // ⚠️ 后端 `msg` 已区分「领取成功 / 您已领取过该券」，这里**不再**加前缀，
    //    否则会出现「领取失败：领取失败：…」那类双层文案（与领券块同一条教训）。
    say(!!data.success, data.msg || (data.success ? '领取成功' : '领取失败'))
  } catch (e) {
    say(false, '领取失败：' + (e && e.message ? e.message : '网络错误'))
  } finally {
    claiming.value = false
  }
}

onMounted(() => { loadOverview(); loadLogs() })
</script>

<style scoped>
.uc-main { padding: var(--s5); }
.mc-hd { margin-bottom: var(--s5); }
.page-h { font-family: var(--font); font-size: var(--fs-xl); font-weight: 600; letter-spacing: .04em; color: var(--ink); margin: 0 0 var(--s2); }
.page-h-sub { color: var(--ink-2); font-size: var(--fs-sm); margin: 0; }

/* ── 黑金会员卡（全页唯一重色块；其余全部白面）────────────────────────────────── */
.mc-card {
  position: relative;
  overflow: hidden;
  margin-bottom: var(--s5);
  padding: 24px 26px 20px;
  border-radius: 18px;
  color: #f4f1ea;
  background:
    radial-gradient(560px 240px at 88% -30%, rgba(201, 162, 90, .3), transparent 70%),
    linear-gradient(135deg, #181a1d 0%, #0c0d0f 58%, #1a150d 100%);
  box-shadow: 0 26px 60px -34px rgba(12, 13, 15, .75);
}
.mc-mesh {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, .04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, .04) 1px, transparent 1px);
  background-size: 52px 52px;
  mask-image: radial-gradient(110% 100% at 18% 0%, #000 12%, transparent 76%);
  -webkit-mask-image: radial-gradient(110% 100% at 18% 0%, #000 12%, transparent 76%);
  pointer-events: none;
}
.mc-top { position: relative; display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s4); }
.mc-ident { display: flex; align-items: center; gap: 12px; min-width: 0; }
.mc-seal {
  display: grid;
  place-items: center;
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, #e8c98a, #a97f3c);
  color: #1a1409;
  font-size: 18px;
  font-weight: 700;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .45);
}
.mc-ident-tx { min-width: 0; }
.mc-level { display: block; font-size: 17px; font-weight: 600; letter-spacing: .04em; }
.mc-level-sub { display: block; margin-top: 3px; font-size: 11px; letter-spacing: .08em; color: rgba(244, 241, 234, .5); }
.mc-chip {
  flex: none;
  padding: 4px 11px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, .16);
  color: rgba(244, 241, 234, .6);
  font-size: 11px;
  letter-spacing: .08em;
}
.mc-chip.on { border-color: rgba(232, 201, 138, .5); color: #e8c98a; background: rgba(201, 162, 90, .1); }

.mc-metrics { position: relative; display: flex; align-items: flex-end; gap: 46px; margin-top: 26px; }
.mc-points { display: flex; align-items: baseline; gap: 7px; }
.mc-k { display: block; font-size: 11px; letter-spacing: .12em; color: rgba(244, 241, 234, .48); }
.mc-points .mc-k { align-self: flex-start; margin-top: 10px; }
.mc-v {
  font-size: 46px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  color: #e8c98a;
}
.mc-u { font-size: 12px; color: rgba(244, 241, 234, .5); }
.mc-balance { padding-bottom: 4px; }
.mc-balance .mc-k { margin-bottom: 5px; }
.mc-b { font-size: 17px; font-variant-numeric: tabular-nums; color: rgba(244, 241, 234, .86); }

.mc-growth { position: relative; margin-top: 20px; }
.mc-growth-hd { display: flex; justify-content: space-between; gap: var(--s3); margin-bottom: 7px; font-size: 11px; color: rgba(244, 241, 234, .5); }
.mc-bar { height: 4px; border-radius: 999px; background: rgba(255, 255, 255, .1); overflow: hidden; }
.mc-bar i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #a97f3c, #e8c98a); transition: width .6s cubic-bezier(.22, .61, .36, 1); }
.mc-max { margin: 16px 0 0; font-size: 11px; color: rgba(244, 241, 234, .5); }

.mc-actions { position: relative; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 22px; padding-top: 18px; border-top: 1px solid rgba(255, 255, 255, .09); }
.mc-sign {
  padding: 9px 22px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #e8c98a, #c9a25a);
  color: #1a1409;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: filter .2s, opacity .2s;
}
.mc-sign:hover:not(:disabled) { filter: brightness(1.06); }
.mc-sign:disabled { opacity: .45; cursor: not-allowed; }
.mc-ghost {
  padding: 8px 16px;
  border: 1px solid rgba(255, 255, 255, .18);
  border-radius: 999px;
  background: transparent;
  color: rgba(244, 241, 234, .78);
  font-size: 12px;
  cursor: pointer;
  transition: border-color .2s, color .2s;
}
.mc-ghost:hover:not(:disabled) { border-color: rgba(232, 201, 138, .55); color: #e8c98a; }
.mc-ghost:disabled { opacity: .38; cursor: not-allowed; }
.mc-sign:focus-visible, .mc-ghost:focus-visible { outline: 2px solid #e8c98a; outline-offset: 3px; }
.mc-msg { position: relative; margin: 12px 0 0; font-size: 12px; color: #e8c98a; }
.mc-msg.bad { color: #ffb4a4; }

/* ── 区块与积分获取说明 ───────────────────────────────────────────────────────── */
.mc-sec { margin-bottom: var(--s5); }
.sec-hd { display: flex; align-items: baseline; gap: var(--s3); margin-bottom: 14px; }
.sec-h { margin: 0; font-size: var(--fs-md); font-weight: 600; color: var(--ink); }
.sec-sub { font-size: var(--fs-xs); color: var(--ink-3); }
.sec-retry { margin-left: auto; border: 0; background: transparent; color: var(--brand-ink); font-size: var(--fs-xs); cursor: pointer; text-decoration: underline; }
.earn-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--s4); }
.earn {
  display: flex;
  gap: 14px;
  padding: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.earn-ico {
  display: grid;
  place-items: center;
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2b2418, #14110c);
  border: 1px solid rgba(201, 162, 90, .45);
  color: #e8c98a;
}
.earn-ico svg { width: 22px; height: 22px; }
.earn-tx { min-width: 0; }
.earn-hd { display: flex; align-items: baseline; justify-content: space-between; gap: var(--s2); }
.earn-hd h3 { margin: 0; font-size: var(--fs-sm); font-weight: 600; color: var(--ink); }
.earn-tag { flex: none; font-size: 11px; color: var(--brand-ink); font-variant-numeric: tabular-nums; }
.earn p { margin: 7px 0 0; font-size: var(--fs-xs); line-height: 1.7; color: var(--ink-2); }

.rules { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--s3); margin: 14px 0 0; padding: 0; list-style: none; }
.rules li { padding: 14px 16px; background: var(--zone); border-radius: var(--r2); }
.rules b { display: block; font-size: var(--fs-sm); color: var(--ink); }
.rules span { display: block; margin-top: 3px; font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 积分流水 ─────────────────────────────────────────────────────────────────── */
.log-list { margin: 0; padding: 0; list-style: none; background: var(--surface); border: 1px solid var(--line); border-radius: var(--r2); overflow: hidden; }
.log-row { display: flex; align-items: center; gap: 14px; padding: 15px 18px; border-top: 1px solid var(--line); }
.log-row:first-child { border-top: 0; }
.log-glyph {
  display: grid;
  place-items: center;
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 600;
}
.log-glyph.in { background: var(--brand-soft); color: var(--brand-ink); }
.log-glyph.out { background: var(--cin-100, #f6e7e2); color: var(--accent); }
.log-main { flex: 1; min-width: 0; }
.log-main b { display: block; font-size: var(--fs-sm); font-weight: 500; color: var(--ink); }
.log-main span { display: block; margin-top: 3px; font-size: var(--fs-xs); color: var(--ink-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.log-amt { text-align: right; flex: none; }
.log-amt b { display: block; font-size: var(--fs-sm); font-weight: 600; font-variant-numeric: tabular-nums; }
.log-amt b.in { color: var(--brand-ink); }
.log-amt b.out { color: var(--accent); }
.log-amt span { display: block; margin-top: 3px; font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }

.log-empty { padding: 34px 20px 38px; text-align: center; background: var(--surface); border: 1px dashed var(--line-2); border-radius: var(--r2); }
.log-empty-ico {
  display: inline-grid;
  place-items: center;
  width: 46px;
  height: 46px;
  margin-bottom: 12px;
  border-radius: 14px;
  background: var(--zone);
  color: var(--ink-3);
  font-size: 17px;
}
.log-empty b { display: block; font-size: var(--fs-sm); color: var(--ink-2); font-weight: 500; }
.log-empty p { margin: 6px 0 0; font-size: var(--fs-xs); color: var(--ink-3); }

.log-sk { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r2); overflow: hidden; }
.log-sk-row { display: flex; align-items: center; gap: 14px; padding: 15px 18px; border-top: 1px solid var(--line); animation: mc-pulse 1.8s ease-in-out infinite; }
.log-sk-row:first-child { border-top: 0; }
.sk-dot { flex: none; width: 34px; height: 34px; border-radius: 50%; background: var(--zone); }
.sk-lines { flex: 1; }
.sk-lines i { display: block; height: 10px; border-radius: 4px; background: var(--zone); }
.sk-lines i.short { width: 38%; margin-top: 7px; }
.sk-amt { flex: none; width: 54px; height: 12px; border-radius: 4px; background: var(--zone); }
@keyframes mc-pulse { 50% { opacity: .55; } }

/* 原有权益列表（文案未改，只压紧行距） */
.bf-list { margin: 0; padding: 0; list-style: none; }
.bf-list li { display: flex; gap: var(--s3); padding: 11px 0; border-top: 1px solid var(--line); color: var(--ink-2); font-size: var(--fs-sm); }
.bf-list li:first-child { border-top: 0; }
.bf-list b { flex: none; width: 84px; color: var(--brand-ink); }

@media (max-width: 860px) {
  .earn-grid { grid-template-columns: minmax(0, 1fr); }
  .rules { grid-template-columns: minmax(0, 1fr); }
  .mc-metrics { gap: 30px; }
  .mc-v { font-size: 38px; }
}
@media (max-width: 520px) {
  .mc-card { padding: 20px 18px 16px; }
  .mc-top { flex-direction: column; gap: 12px; }
  .mc-metrics { flex-direction: column; align-items: flex-start; gap: 16px; }
  .mc-actions > button { flex: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .mc-bar i { transition: none; }
  .log-sk-row { animation: none; }
}
</style>
