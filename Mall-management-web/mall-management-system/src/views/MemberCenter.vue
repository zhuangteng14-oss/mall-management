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

    A 版线框复刻（2026-10-19）：照 `_work/ui-wireframe/a.html` 的 `pageMember` 重排 ——
    页头 / 左栏「黑金牌匾 · 积分怎么赚 · 积分存折」/ 右栏「今日专属券 · 余额 · 规则 · 权益」。
    只改模板与样式：全部现有按钮、禁用条件、接口调用与三态渲染一个没动。
    ⚠️ 客户 / 商家两个壳共用本组件（`router/index.js` 两条 addRoute 同组件）⇒ 页头两个出口
       用模板里的 `$route` 判壳，不往 `script` 段里加逻辑。
  -->
  <main class="uc-main">
    <section class="page-head">
      <div>
        <div class="kicker">BUYER / MEMBER</div>
        <h1>会员中心</h1>
      </div>
      <div class="head-actions">
        <router-link class="btn" :to="$route.path.startsWith('/merchant') ? '/merchant/order/cart' : '/customer/cart'">去使用积分</router-link>
        <router-link class="btn solid" :to="$route.path.startsWith('/merchant') ? '/merchant/user/info' : '/customer/user/info'">返回个人中心</router-link>
      </div>
    </section>

    <div class="member-layout">
      <div class="stack">
        <!-- 黑金会员卡：全页唯一的「重色块」，其余全部退到白面。
             外框黑底 + 内框金色发丝线（A 版 `.member-card` / `.member-card-inner` 的直角色块写法）。 -->
        <section class="member-card" :class="themeClass">
          <div class="member-card-inner">
            <div class="mc-top">
              <span>{{ cardKicker }}</span>
              <span class="mc-chip" :class="{ on: isVip }">{{ isVip ? '权益生效中' : '普通用户' }}</span>
            </div>

            <div class="mc-mid">
              <div class="level">
                <span class="mc-seal" aria-hidden="true">{{ levelInitial }}</span>
                <div class="mc-level-tx">
                  <b>{{ levelName }}</b>
                  <small>{{ levelCode }}<template v-if="benefitLine"> · {{ benefitLine }}</template></small>
                </div>
              </div>
              <div class="points">
                {{ pointsText }}
                <small>AVAILABLE POINTS / 可用积分</small>
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

            <div class="mc-bottom">
              <div class="assets">
                <div><b>¥{{ balanceText }}</b><small>账户余额</small></div>
                <div><b>{{ isVip ? levelCode : '未开通' }}</b><small>{{ isVip ? (benefitLine || '会员权益生效中') : '开通后享会员价' }}</small></div>
              </div>
              <div class="mc-actions">
                <button type="button" class="btn solid sm" :disabled="signing || signedToday || !customerId" @click="doSignin">
                  {{ signing ? '签到中…' : signedToday ? '今日已签到' : '签到 +5' }}
                </button>
                <button v-if="!isVip" type="button" class="btn sm" :disabled="opening" @click="doOpen">
                  {{ opening ? '开通中…' : '开通会员' }}
                </button>
              </div>
            </div>

            <p v-if="actionMsg" class="mc-msg" :class="{ bad: !actionOk }">{{ actionMsg }}</p>
            <p v-if="overviewErr" class="mc-msg bad">{{ overviewErr }}</p>
          </div>
        </section>

        <!-- 积分获取说明：只写后端真有的两条发放链（签到 / 收货返积分）+ 使用规则。 -->
        <section>
          <div class="section-title">
            <h2>积分怎么赚</h2>
            <span class="tiny">只展示已上线的发放链</span>
          </div>
          <div class="earn-grid">
            <article class="earn">
              <span class="e-no">01 / SIGN</span>
              <b>每日签到</b>
              <p>每天签到得 5 积分；连续签到满 7 天的那一天再加 20 积分。</p>
              <div class="e-val">+5 / 天 · 第 7 天 +20</div>
            </article>
            <article class="earn">
              <span class="e-no">02 / BUY</span>
              <b>购物返积分</b>
              <p>确认收货后按实付金额发放：每 ¥1 得 1–2.5 积分，倍率随会员等级提升。</p>
              <div class="e-val">1 元 = 1 分 × 等级倍率</div>
            </article>
            <article class="earn">
              <span class="e-no">03 / USE</span>
              <b>100 积分 = ¥1</b>
              <p>结算时直接抵扣，可与优惠券、会员价叠加。</p>
              <div class="e-val">单笔最多抵 20% · 365 天有效</div>
            </article>
          </div>
        </section>

        <!-- 积分流水：骨架 → 空态 → 列表 三态；时间由库内 UTC 文本转本地。 -->
        <section>
          <div class="section-title">
            <h2>积分明细</h2>
            <span v-if="logs.length" class="tiny">最近 {{ logs.length }} 条</span>
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

          <div v-else class="log-table-wrap">
            <table class="log-table">
              <thead>
                <tr><th>时间</th><th>类型</th><th>说明</th><th class="num">变动 / 余额</th></tr>
              </thead>
              <tbody>
                <tr v-for="row in logs" :key="row.id">
                  <td class="mono">{{ row.time }}</td>
                  <td><span class="log-glyph" :class="row.tone" aria-hidden="true">{{ row.glyph }}</span></td>
                  <td class="log-desc">
                    <b>{{ row.title }}</b>
                    <small v-if="row.remark">{{ row.remark }}</small>
                  </td>
                  <td class="num log-amt">
                    <b :class="row.tone">{{ row.sign }}{{ row.amount }}</b>
                    <small>余额 {{ row.balance }}</small>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <aside class="stack">
        <!-- 今日专属券：领取动作与禁用条件原样保留，只从卡内搬到右栏。 -->
        <section class="panel">
          <div class="panel-head">
            <div><div class="kicker">DAILY VIP COUPON</div><h2>今日专属券</h2></div>
            <span class="tag" :class="{ dark: isVip }">{{ isVip ? 'VIP' : '未开通' }}</span>
          </div>
          <div class="coupon-card">
            <div class="c-main"><b>满 ¥50 减 ¥5</b><small>仅黑金会员可领 · 当日有效</small></div>
            <div class="c-val">¥5</div>
          </div>
          <div class="panel-foot">
            <span class="small muted">{{ isVip ? '每日一张，领取后当日有效。' : '开通会员后可领取每日专属券。' }}</span>
            <button type="button" class="btn sm" :disabled="claiming || !isVip" @click="doClaimDaily">
              {{ claiming ? '领取中…' : '领取今日专属券' }}
            </button>
          </div>
        </section>

        <!-- 账户余额：余额与积分都来自既有接口读数。 -->
        <section class="panel">
          <div class="panel-head"><div><div class="kicker">BALANCE</div><h2>账户余额</h2></div><span class="tiny">¥</span></div>
          <div class="ledger">
            <div class="ledger-row">
              <div class="l"><b>可用余额</b><small>账户余额</small></div>
              <div class="v">¥{{ balanceText }}<small>结算时可抵扣</small></div>
            </div>
            <div class="ledger-row">
              <div class="l"><b>可用积分</b><small>100 积分 = ¥1</small></div>
              <div class="v">{{ pointsText }}<small>签到 / 购物返积分</small></div>
            </div>
          </div>
        </section>

        <!-- 使用规则（原 `.rules` 三项逐条保留，改成 A 版 `rule-list`）。 -->
        <section class="panel">
          <div class="panel-head"><div><div class="kicker">RULES</div><h2>使用规则</h2></div></div>
          <ul class="rule-list">
            <li><b>01</b><span>100 积分 = ¥1，结算时直接抵扣。</span></li>
            <li><b>02</b><span>单笔最多抵券后金额的 20%。</span></li>
            <li><b>03</b><span>365 天有效，过期按获取时间先进先出。</span></li>
          </ul>
        </section>

        <!-- 原有会员权益文案保留（这批没有改权益本身，只换了排版）。 -->
        <section class="panel">
          <div class="panel-head"><div><div class="kicker">MEMBER BENEFITS</div><h2>会员权益</h2></div><span class="tag">9 折</span></div>
          <ul class="rule-list benefit-list">
            <li><b>01</b><span><em>9 折</em>商品详情、购物车与结算价统一按会员价计算，优惠券在会员价基础上再减。</span></li>
            <li><b>02</b><span><em>每日专属券</em>每天可领一张会员专属券（满 50 减 5），当天限领一次。</span></li>
            <li><b>03</b><span><em>售后优先</em>平台售后队列按会员等级优先处理。</span></li>
          </ul>
        </section>
      </aside>
    </div>
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
/**
 * 牌匾配色按当前等级动态换（2026-10-19 用户令）：色卡来自 `member_level.Theme`
 * （`plain / silver / gold / platinum / diamond / black / supreme`），**不在这里另造等级表**。
 * 未取到概览时按会员与否兜底：非会员 = 白、会员 = 金；取到后以 `Theme` 为准。
 */
const THEME_CLASS = {
  plain: 'mc-theme-white',
  silver: 'mc-theme-silver',
  gold: 'mc-theme-gold',
  platinum: 'mc-theme-platinum',
  diamond: 'mc-theme-diamond',
  black: 'mc-theme-black',
  supreme: 'mc-theme-ink',
}
const THEME_KICKER = {
  plain: 'MEMBER CARD / 普通会员牌匾',
  silver: 'SILVER CARD / 银卡牌匾',
  gold: 'GOLD CARD / 金卡牌匾',
  platinum: 'PLATINUM CARD / 铂金牌匾',
  diamond: 'DIAMOND CARD / 钻石牌匾',
  black: 'BLACK CARD / 黑金牌匾',
  supreme: 'SUPREME CARD / 至尊牌匾',
}
const levelTheme = computed(() => {
  const lv = overview.value && overview.value.level
  const theme = String((lv && lv.Theme) || '').toLowerCase()
  return THEME_CLASS[theme] ? theme : (isVip.value ? 'gold' : 'plain')
})
const themeClass = computed(() => THEME_CLASS[levelTheme.value])
const cardKicker = computed(() => THEME_KICKER[levelTheme.value])
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
/* ══════════════════════════════════════════════════════════════════════════════
   A 版会员中心（2026-10-19 重排）。数值逐条取自 `_work/ui-wireframe/a.html`：
     · `.page-head` / `.head-actions`                    :110-113
     · `.member-layout` / `.member-card*`                :465-478
     · `.section-title` / `.earn-grid` / `.log-table`    :358-361 / 489-499
     · `.panel*` / `.coupon-card` / `.ledger` / `.rule-list` :118-124 / 187-194 / 407-409 / 500-503
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

/* ── 本页根（壳 `.user-page` 已给 1360 居中与内距）────────────────────────────── */
.uc-main {
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: 14px;
  color: var(--ink);
}

/* ── 基础小件 ─────────────────────────────────────────────────────────────── */
.kicker { font-family: var(--font-mono); font-size: 10px; letter-spacing: .12em; text-transform: uppercase; color: var(--ink-3); }
.mono { font-family: var(--font-mono); }
.small { font-size: 12px; }
.muted { color: var(--ink-3); }
.tiny { font-family: var(--font-mono); font-size: 10px; letter-spacing: .06em; color: var(--ink-3); }
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid var(--brand-ink);
  background: var(--surface);
  color: var(--brand-ink);
  padding: 10px 15px;
  font-size: 13px;
  line-height: 1.2;
  text-decoration: none;
  transition: background-color .12s linear, color .12s linear;
}
.btn:hover:not(:disabled) { background: var(--brand-ink); color: var(--surface); }
.btn.solid { background: var(--brand-ink); color: var(--surface); }
.btn.solid:hover:not(:disabled) { background: var(--surface); color: var(--brand-ink); }
.btn.sm { padding: 7px 10px; font-size: 12px; }
.btn:disabled {
  opacity: .4;
  pointer-events: none;
  background: var(--surface-2);
  border-color: var(--line-2);
  color: var(--ink-3);
}
.tag {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--line-2);
  padding: 2px 7px;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: .05em;
  color: var(--ink-3);
  line-height: 1.5;
  white-space: nowrap;
}
.tag.dark { background: var(--ink); color: var(--surface); border-color: var(--ink); }

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
.page-head p { max-width: 700px; margin: 0; color: var(--ink-3); font-size: 14px; line-height: 1.7; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; align-items: center; }

/* ── 两栏骨架 ─────────────────────────────────────────────────────────────── */
.member-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(300px, .8fr);
  gap: 36px;
  align-items: start;
}
.stack > * + * { margin-top: 24px; }

/* ── 会员牌匾（直角色块 + 发丝线；本页唯一重色块）──────────────────────────────
   2026-10-19 用户令：**黑金牌匾必须是真黑 + 金**（旧的 `--ink` 底被看成墨绿），
   且整张牌匾按当前等级换色（色名来自 `member_level.Theme`）：
   plain 白 / silver 银 / gold 金 / platinum 铂金 / diamond 钻蓝 /
   black 黑金 / supreme 墨绿。颜色全部收进 `--mc-*`，主题类只改这几条变量。 */
.member-card {
  --mc-bg: #101010;
  --mc-bg-solid: #101010;
  --mc-fg: #ffffff;
  --mc-accent: #e8c98a;
  --mc-accent-ink: #1a1409;
  --mc-line: rgba(255, 255, 255, .18);
  --mc-line-strong: rgba(232, 201, 138, .38);
  --mc-chip-line: rgba(255, 255, 255, .28);
  --mc-dim: rgba(255, 255, 255, .72);
  --mc-dim-2: rgba(255, 255, 255, .6);
  --mc-btn-line: rgba(255, 255, 255, .42);
  --mc-btn-dim: rgba(255, 255, 255, .45);
  --mc-bad: #ffb4a4;
  background: var(--mc-bg);
  color: var(--mc-fg);
  border: 1px solid var(--mc-bg-solid);
  padding: 9px;
}
/* 白：普通会员 */
.member-card.mc-theme-white {
  --mc-bg: #f3f1ea;
  --mc-bg-solid: #f3f1ea;
  --mc-fg: #1b1e20;
  --mc-accent: #275e4e;
  --mc-accent-ink: #ffffff;
  --mc-line: rgba(0, 0, 0, .14);
  --mc-line-strong: rgba(0, 0, 0, .22);
  --mc-chip-line: rgba(0, 0, 0, .24);
  --mc-dim: rgba(0, 0, 0, .62);
  --mc-dim-2: rgba(0, 0, 0, .5);
  --mc-btn-line: rgba(0, 0, 0, .32);
  --mc-btn-dim: rgba(0, 0, 0, .4);
  --mc-bad: #b3261e;
}
/* 银 */
.member-card.mc-theme-silver {
  --mc-bg: linear-gradient(135deg, #e8ebef, #c3c9d1 55%, #aeb6c0);
  --mc-bg-solid: #c3c9d1;
  --mc-fg: #1d232c;
  --mc-accent: #39424e;
  --mc-accent-ink: #f7f9fb;
  --mc-line: rgba(0, 0, 0, .16);
  --mc-line-strong: rgba(0, 0, 0, .24);
  --mc-chip-line: rgba(0, 0, 0, .26);
  --mc-dim: rgba(0, 0, 0, .6);
  --mc-dim-2: rgba(0, 0, 0, .48);
  --mc-btn-line: rgba(0, 0, 0, .34);
  --mc-btn-dim: rgba(0, 0, 0, .42);
  --mc-bad: #b3261e;
}
/* 金 */
.member-card.mc-theme-gold {
  --mc-bg: linear-gradient(135deg, #d8b452, #b8912c 60%, #9c7a1f);
  --mc-bg-solid: #b8912c;
  --mc-fg: #241b06;
  --mc-accent: #fff3cf;
  --mc-accent-ink: #2a2008;
  --mc-line: rgba(0, 0, 0, .2);
  --mc-line-strong: rgba(0, 0, 0, .3);
  --mc-chip-line: rgba(0, 0, 0, .3);
  --mc-dim: rgba(0, 0, 0, .62);
  --mc-dim-2: rgba(0, 0, 0, .5);
  --mc-btn-line: rgba(0, 0, 0, .38);
  --mc-btn-dim: rgba(0, 0, 0, .45);
  --mc-bad: #7a1712;
}
/* 铂金 */
.member-card.mc-theme-platinum {
  --mc-bg: linear-gradient(135deg, #eef3f8, #c2ccd8 55%, #9dabb9);
  --mc-bg-solid: #c2ccd8;
  --mc-fg: #182430;
  --mc-accent: #2f4a63;
  --mc-accent-ink: #f2f7fb;
  --mc-line: rgba(0, 0, 0, .16);
  --mc-line-strong: rgba(0, 0, 0, .26);
  --mc-chip-line: rgba(0, 0, 0, .26);
  --mc-dim: rgba(0, 0, 0, .6);
  --mc-dim-2: rgba(0, 0, 0, .48);
  --mc-btn-line: rgba(0, 0, 0, .34);
  --mc-btn-dim: rgba(0, 0, 0, .42);
  --mc-bad: #b3261e;
}
/* 钻石 */
.member-card.mc-theme-diamond {
  --mc-bg: linear-gradient(135deg, #e6f4fc, #a9d3ec 55%, #6fa9cf);
  --mc-bg-solid: #a9d3ec;
  --mc-fg: #102a3c;
  --mc-accent: #0f4c75;
  --mc-accent-ink: #f2f9ff;
  --mc-line: rgba(0, 0, 0, .15);
  --mc-line-strong: rgba(0, 0, 0, .24);
  --mc-chip-line: rgba(0, 0, 0, .24);
  --mc-dim: rgba(0, 0, 0, .58);
  --mc-dim-2: rgba(0, 0, 0, .46);
  --mc-btn-line: rgba(0, 0, 0, .32);
  --mc-btn-dim: rgba(0, 0, 0, .4);
  --mc-bad: #8c1d18;
}
/* 黑金（真黑 + 金） */
.member-card.mc-theme-black {
  --mc-bg: #101010;
  --mc-bg-solid: #101010;
  --mc-fg: #ffffff;
  --mc-accent: #e8c98a;
  --mc-accent-ink: #1a1409;
  --mc-line: rgba(255, 255, 255, .18);
  --mc-line-strong: rgba(232, 201, 138, .38);
  --mc-chip-line: rgba(255, 255, 255, .28);
  --mc-dim: rgba(255, 255, 255, .72);
  --mc-dim-2: rgba(255, 255, 255, .6);
  --mc-btn-line: rgba(255, 255, 255, .42);
  --mc-btn-dim: rgba(255, 255, 255, .45);
  --mc-bad: #ffb4a4;
}
/* 墨绿（仅最高等级） */
.member-card.mc-theme-ink {
  --mc-bg: linear-gradient(135deg, #1d3b33, #142e27 60%, #0d211c);
  --mc-bg-solid: #142e27;
  --mc-fg: #f3f1e7;
  --mc-accent: #d9c68c;
  --mc-accent-ink: #17140a;
  --mc-line: rgba(217, 198, 140, .26);
  --mc-line-strong: rgba(217, 198, 140, .44);
  --mc-chip-line: rgba(217, 198, 140, .36);
  --mc-dim: rgba(243, 241, 231, .74);
  --mc-dim-2: rgba(243, 241, 231, .6);
  --mc-btn-line: rgba(243, 241, 231, .42);
  --mc-btn-dim: rgba(243, 241, 231, .45);
  --mc-bad: #ffb4a4;
}
.member-card-inner {
  border: 1px solid var(--mc-line-strong);
  padding: 24px 26px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.mc-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: .1em;
  color: var(--mc-dim);
}
.mc-chip {
  flex: none;
  padding: 3px 8px;
  border: 1px solid var(--mc-chip-line);
  font-size: 9px;
  letter-spacing: .06em;
  color: var(--mc-dim-2);
  white-space: nowrap;
}
.mc-chip.on { border-color: var(--mc-line-strong); color: var(--mc-accent); }

.mc-mid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 26px;
  align-items: end;
}
.level { display: flex; align-items: center; gap: 14px; min-width: 0; }
.mc-seal {
  display: grid;
  place-items: center;
  flex: none;
  width: 44px;
  height: 44px;
  background: var(--mc-accent);
  color: var(--mc-accent-ink);
  font-size: 18px;
  font-weight: 700;
}
.mc-level-tx { min-width: 0; }
.mc-level-tx b {
  display: block;
  font-family: var(--font-display);
  font-size: 40px;
  line-height: 1;
  font-weight: 600;
  letter-spacing: -.02em;
}
.mc-level-tx small {
  display: block;
  margin-top: 8px;
  font-family: var(--font);
  font-size: 11px;
  color: var(--mc-dim);
}
.points {
  font-family: var(--font-mono);
  font-size: 48px;
  line-height: .9;
  text-align: right;
  color: var(--mc-accent);
  font-variant-numeric: tabular-nums;
}
.points small {
  display: block;
  margin-top: 7px;
  font-size: 9px;
  letter-spacing: .1em;
  color: var(--mc-dim);
}

.mc-growth { border-top: 1px solid var(--mc-line); padding-top: 14px; }
.mc-growth-hd {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 7px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--mc-dim);
}
.mc-bar { height: 4px; background: var(--mc-line); overflow: hidden; }
.mc-bar i { display: block; height: 100%; background: var(--mc-accent); transition: width .6s cubic-bezier(.22, .61, .36, 1); }
.mc-max { margin: 0; font-family: var(--font-mono); font-size: 10px; color: var(--mc-dim); }

.mc-bottom {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  border-top: 1px solid var(--mc-line);
  padding-top: 14px;
}
.assets { display: flex; gap: 30px; }
.assets b {
  display: block;
  font-family: var(--font-mono);
  font-size: 18px;
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}
.assets small { display: block; margin-top: 3px; font-size: 9px; color: var(--mc-dim-2); }
.mc-actions { display: flex; flex-wrap: wrap; gap: 8px; }

/* 牌匾内的按钮反色（通用 `.btn` 是白底青瓷绿，直接放牌匾上会看不见）。 */
.member-card .btn { border-color: var(--mc-btn-line); background: transparent; color: var(--mc-fg); }
.member-card .btn:hover:not(:disabled) { background: var(--mc-fg); color: var(--mc-bg-solid); border-color: var(--mc-fg); }
.member-card .btn.solid { background: var(--mc-accent); border-color: var(--mc-accent); color: var(--mc-accent-ink); }
.member-card .btn.solid:hover:not(:disabled) { background: transparent; color: var(--mc-accent); border-color: var(--mc-accent); }
.member-card .btn:disabled { opacity: .38; background: transparent; border-color: var(--mc-line); color: var(--mc-btn-dim); }
.mc-msg { margin: 0; font-size: 12px; color: var(--mc-accent); }
.mc-msg.bad { color: var(--mc-bad); }

/* ── 区块标题（A `.section-title`）──────────────────────────────────────────── */
.section-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--line);
  padding-bottom: 10px;
  margin-bottom: 14px;
}
.section-title h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -.02em;
}
.sec-retry {
  flex: none;
  padding-bottom: 1px;
  border-bottom: 1px solid var(--brand-ink);
  color: var(--brand-ink);
  font-size: 11px;
}

/* ── 积分怎么赚（A `.earn-grid`：三格直角色块）───────────────────────────────── */
.earn-grid { display: grid; grid-template-columns: 1.2fr .9fr 1fr; border: 1px solid var(--line); }
.earn { padding: 18px; border-right: 1px solid var(--line); }
.earn:last-child { border-right: 0; }
.e-no { font-family: var(--font-mono); font-size: 9px; letter-spacing: .08em; color: var(--ink-3); }
.earn b { display: block; margin: 7px 0 4px; font-size: 13px; font-weight: 600; }
.earn p { margin: 0; font-size: 10px; line-height: 1.6; color: var(--ink-3); }
.e-val {
  margin-top: 9px;
  padding-top: 7px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-2);
}

/* ── 积分明细（A `.log-table`：时间 / 类型 / 说明 / 变动·余额）──────────────────── */
.log-table-wrap { border: 1px solid var(--line); background: var(--surface); overflow-x: auto; }
.log-table { width: 100%; border-collapse: collapse; }
.log-table th {
  padding: 9px 12px;
  border-bottom: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 400;
  letter-spacing: .05em;
  color: var(--ink-3);
  text-align: left;
  white-space: nowrap;
}
.log-table td { padding: 11px 12px; border-bottom: 1px solid var(--line); font-size: 11px; vertical-align: middle; }
.log-table tbody tr:last-child td { border-bottom: 0; }
.log-table .num { text-align: right; }
.log-glyph {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 22px;
  padding: 0 5px;
  border: 1px solid var(--line-2);
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}
.log-glyph.in { border-color: var(--brand-ink); background: var(--brand-tint); color: var(--brand-ink); }
.log-glyph.out { border-color: var(--accent); color: var(--accent); }
.log-desc { min-width: 150px; }
.log-desc b { display: block; font-size: 11px; font-weight: 500; color: var(--ink); }
.log-desc small { display: block; margin-top: 2px; font-size: 9px; color: var(--ink-3); }
.log-amt b { display: block; font-family: var(--font-mono); font-size: 12px; font-weight: 500; font-variant-numeric: tabular-nums; }
.log-amt b.in { color: var(--brand-ink); }
.log-amt b.out { color: var(--accent); }
.log-amt small { display: block; margin-top: 2px; font-family: var(--font-mono); font-size: 9px; color: var(--ink-3); font-variant-numeric: tabular-nums; }

.log-empty {
  padding: 34px 20px 38px;
  text-align: center;
  background: var(--surface);
  border: 1px dashed var(--line-2);
}
.log-empty-ico {
  display: inline-grid;
  place-items: center;
  width: 40px;
  height: 40px;
  margin-bottom: 12px;
  border: 1px solid var(--line-2);
  color: var(--ink-3);
  font-family: var(--font-display);
  font-size: 16px;
}
.log-empty b { display: block; font-size: 13px; font-weight: 500; color: var(--ink-2); }
.log-empty p { margin: 6px 0 0; font-size: 11px; color: var(--ink-3); }

.log-sk { background: var(--surface); border: 1px solid var(--line); }
.log-sk-row { display: flex; align-items: center; gap: 14px; padding: 14px 12px; border-bottom: 1px solid var(--line); animation: mc-pulse 1.8s ease-in-out infinite; }
.log-sk-row:last-child { border-bottom: 0; }
.sk-dot { flex: none; width: 24px; height: 22px; background: var(--surface-2); }
.sk-lines { flex: 1; }
.sk-lines i { display: block; height: 10px; background: var(--surface-2); }
.sk-lines i.short { width: 38%; margin-top: 7px; }
.sk-amt { flex: none; width: 54px; height: 12px; background: var(--surface-2); }
@keyframes mc-pulse { 50% { opacity: .55; } }

/* ── 右栏面板 ─────────────────────────────────────────────────────────────── */
.panel { border: 1px solid var(--line); padding: 22px 24px; background: var(--surface); }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.panel-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 24px;
  line-height: 1.15;
  font-weight: 600;
  letter-spacing: -.02em;
}
.panel-head .kicker { margin-bottom: 5px; }
.panel-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

/* 今日专属券（A `.coupon-card`） */
.coupon-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 18px;
  border: 1px solid var(--line);
  padding: 18px;
}
.c-main b { display: block; font-family: var(--font-display); font-size: 22px; font-weight: 600; }
.c-main small { display: block; margin-top: 4px; font-family: var(--font-mono); font-size: 9px; color: var(--ink-3); }
.c-val { font-family: var(--font-mono); font-size: 28px; line-height: 1; color: var(--brand-ink); }

/* 账户余额（A `.ledger`） */
.ledger { border-top: 1px solid var(--line); }
.ledger-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.ledger-row:last-child { border-bottom: 0; }
.ledger-row .l { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.ledger-row .l b { font-size: 12px; font-weight: 500; }
.ledger-row .l small { font-family: var(--font-mono); font-size: 9px; color: var(--ink-3); white-space: nowrap; }
.ledger-row .v { text-align: right; font-family: var(--font-mono); font-size: 13px; font-variant-numeric: tabular-nums; }
.ledger-row .v small { display: block; font-size: 9px; color: var(--ink-3); }

/* 使用规则 / 会员权益（A `.rule-list`，文案沿用原页） */
.rule-list { border-top: 1px solid var(--line); }
.rule-list li {
  display: flex;
  gap: 8px;
  padding: 9px 0;
  border-bottom: 1px solid var(--line);
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-3);
}
.rule-list li:last-child { border-bottom: 0; }
.rule-list li b {
  flex: none;
  min-width: 18px;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 400;
  color: var(--ink);
}
.benefit-list em {
  display: block;
  margin-bottom: 2px;
  font-style: normal;
  font-weight: 600;
  color: var(--ink);
}

/* ── 断点（照 A 版 1080 / 860 / 600）────────────────────────────────────────── */
@media (max-width: 1080px) {
  .member-layout { grid-template-columns: 1fr; }
}
@media (max-width: 860px) {
  .page-head { grid-template-columns: 1fr; }
  .head-actions { justify-content: flex-start; }
  .page-head h1 { font-size: 38px; }
  .earn-grid { grid-template-columns: 1fr; }
  .earn { border-right: 0; border-bottom: 1px solid var(--line); }
  .earn:last-child { border-bottom: 0; }
}
@media (max-width: 600px) {
  .page-head h1 { font-size: 32px; }
  .member-card-inner { padding: 18px; }
  .mc-top { align-items: flex-start; }
  .mc-mid { grid-template-columns: 1fr; gap: 16px; align-items: start; }
  .points { text-align: left; }
  .mc-bottom { flex-direction: column; align-items: flex-start; gap: 14px; }
  .assets { gap: 24px; }
  .mc-actions { width: 100%; }
  .mc-actions .btn { flex: 1; }
  .panel { padding: 18px; }
  .panel-foot { align-items: flex-end; }
}
@media (prefers-reduced-motion: reduce) {
  .mc-bar i { transition: none; }
  .log-sk-row { animation: none; }
}
</style>
