<template>
  <main class="pw-page">
    <!-- 余额区：左侧超大金额 + 充值，右侧本月统计与七日柱图 -->
    <section class="pw-hero">
      <div class="pw-balance">
        <span class="pw-watermark">¥</span>
        <p class="pw-kicker">我的钱包 · 零钱账户 · {{ sourceLabel }}</p>
        <p class="pw-amount"><span>¥</span>{{ balance.toFixed(2) }}</p>
        <div class="pw-balance-row">
          <button type="button" class="pw-recharge" @click="openRecharge">
            <PvIcon name="plus" :size="16" :stroke="2.2" />
            充值
          </button>
          <span class="pw-safe"><PvIcon name="lock" :size="12" />{{ live ? '资金由平台担保' : '资金由平台担保 · 演示环境' }}</span>
        </div>
        <nav class="pw-actions">
          <button type="button" @click="notify(live ? '提现暂未开放' : '静态预览：提现需先绑定银行卡')">
            <PvIcon name="upload" :size="16" />提现
          </button>
          <button type="button" @click="notify('静态预览：不会打开银行卡管理')">
            <PvIcon name="wallet" :size="16" />银行卡
          </button>
          <button type="button" @click="notify('静态预览：不会导出账单')">
            <PvIcon name="document" :size="16" />账单
          </button>
          <button type="button" @click="notify('静态预览：不会打开帮助中心')">
            <PvIcon name="headset" :size="16" />帮助
          </button>
        </nav>
      </div>

      <aside class="pw-month">
        <header class="pw-month-head">
          <h2>本月</h2>
          <span>{{ monthRange }}</span>
        </header>
        <dl class="pw-month-list">
          <div>
            <dt>收入</dt>
            <dd class="in">+¥{{ money(incomeTotal) }}</dd>
          </div>
          <div>
            <dt>支出</dt>
            <dd>-¥{{ money(expenseTotal) }}</dd>
          </div>
          <div>
            <dt>退款入账</dt>
            <dd class="in">+¥{{ money(refundTotal) }}</dd>
          </div>
        </dl>
        <div class="pw-chart">
          <div class="pw-chart-head">
            <span>近 7 天支出</span>
            <b>¥{{ money(weekExpense) }}</b>
          </div>
          <div class="pw-bars">
            <div v-for="(h, i) in bars" :key="i" class="pw-bar-col">
              <i :style="{ height: h + '%' }" :class="{ on: i === todayIndex }"></i>
              <span>{{ weekLabels[i] }}</span>
            </div>
          </div>
        </div>
      </aside>
    </section>

    <!-- 收支明细：整幅列表，左侧日期栏，右侧金额；筛选是文字页签 -->
    <section class="pw-ledger">
      <header class="pw-ledger-head">
        <div>
          <h2>收支明细</h2>
          <p>共 {{ filteredRows.length }} 笔 · 退款用绿色，消费用中性黑灰</p>
        </div>
        <div class="pw-filters" role="tablist" aria-label="流水筛选">
          <button
            v-for="f in filters"
            :key="f.id"
            type="button"
            role="tab"
            :aria-selected="filter === f.id"
            :class="{ on: filter === f.id }"
            @click="filter = f.id"
          >
            {{ f.label }}
          </button>
        </div>
      </header>

      <div class="pw-groups">
        <div v-for="group in groups" :key="group.date" class="pw-group">
          <div class="pw-group-head">
            <span>{{ group.date }}</span>
            <span>{{ group.rows.length }} 笔</span>
          </div>
          <div
            v-for="row in group.rows"
            :key="row.id"
            class="pw-row"
            :class="{ 'pw-row--in': row.amount > 0 }"
          >
            <span class="pw-row-ic" :class="row.kind">
              <PvIcon :name="iconOf(row.kind)" :size="17" />
            </span>
            <div class="pw-row-tx">
              <b>{{ row.title }}</b>
              <p>{{ row.meta }}</p>
            </div>
            <span class="pw-row-time">{{ row.time }}</span>
            <em class="pw-row-amt" :class="{ in: row.amount > 0 }">
              {{ row.amount > 0 ? '+' : '-' }}¥{{ money(Math.abs(row.amount)) }}
            </em>
          </div>
        </div>
        <p v-if="!groups.length" class="pw-empty">这一栏还没有记录。</p>
      </div>
    </section>

    <!-- 本月小结：深绿横幅 + 构成条，节奏与上方列表完全不同 -->
    <section class="pw-summary">
      <div class="pw-summary-lead">
        <span>本月小结</span>
        <h2>退款回到了零钱，<br />消费才真正花出去。</h2>
      </div>
      <div class="pw-summary-body">
        <div class="pw-summary-bar" aria-hidden="true">
          <i class="refund" :style="{ width: refundShare + '%' }"></i>
          <i class="spend" :style="{ width: 100 - refundShare + '%' }"></i>
        </div>
        <div class="pw-summary-legend">
          <div>
            <span class="dot refund"></span>
            <b>¥{{ money(refundTotal) }}</b>
            <em>退款入账</em>
          </div>
          <div>
            <span class="dot spend"></span>
            <b>¥{{ money(expenseTotal) }}</b>
            <em>商品消费</em>
          </div>
          <div>
            <span class="dot net"></span>
            <b>¥{{ money(netSpend) }}</b>
            <em>净支出</em>
          </div>
        </div>
        <p class="pw-summary-note">退款不计入收入，只冲抵当月支出；净支出 = 商品消费 − 退款。</p>
      </div>
    </section>

    <p class="pw-foot">
      <PvIcon name="shield" :size="14" />
      {{ live
        ? '余额与流水来自钱包账户（user_wallet / user_balance_log）；充值走沙箱通道，不接真实支付。'
        : '页面为纯静态演示：金额、订单号与时间均为 Mock；充值不会发起任何支付请求。' }}
    </p>

    <!-- 充值弹窗 -->
    <teleport to="body">
      <transition name="pw-fade">
        <div v-if="rechargeOpen" class="pw-modal-root">
          <div class="pw-mask" @click="rechargeOpen = false"></div>
          <div class="pw-modal" role="dialog" aria-label="充值">
            <header class="pw-modal-head">
              <div>
                <h3>充值到零钱</h3>
                <p>当前余额 ¥{{ balance.toFixed(2) }} · 无手续费</p>
              </div>
              <button type="button" class="pw-icon-btn" aria-label="关闭" @click="rechargeOpen = false">
                <PvIcon name="close" :size="17" />
              </button>
            </header>

            <div class="pw-modal-body">
              <span class="pw-field-label">充值金额</span>
              <div class="pw-amounts">
                <button
                  v-for="a in amountOptions"
                  :key="a"
                  type="button"
                  :class="{ on: amount === a }"
                  @click="amount = a"
                >
                  ¥{{ a }}
                </button>
              </div>
              <label class="pw-custom">
                <span>其他金额</span>
                <input v-model.number="amount" type="number" min="1" step="1" />
              </label>

              <span class="pw-field-label pw-field-label--gap">支付方式</span>
              <div class="pw-pays">
                <button
                  v-for="p in pays"
                  :key="p.id"
                  type="button"
                  :class="{ on: pay === p.id }"
                  @click="pay = p.id"
                >
                  <span class="pw-pay-ic" :class="p.id"><PvIcon :name="p.icon" :size="16" /></span>
                  <span>{{ p.label }}</span>
                  <i class="pw-radio"><i></i></i>
                </button>
              </div>

              <div class="pw-summary-box">
                <div><span>到账金额</span><b>¥{{ money(amount) }}</b></div>
                <div><span>手续费</span><b>¥0.00</b></div>
              </div>
            </div>

            <footer class="pw-modal-foot">
              <span>{{ live ? '沙箱充值 · 不接支付通道' : '演示环境 · 不接支付通道' }}</span>
              <button
                type="button"
                class="pw-submit"
                :disabled="!amount || amount <= 0 || recharging"
                @click="confirmRecharge"
              >
                {{ recharging ? '提交中…' : `确认充值 ¥${money(amount)}` }}
              </button>
            </footer>
          </div>
        </div>
      </transition>
    </teleport>

    <transition name="pw-toast">
      <div v-if="toast" class="pw-toast">{{ toast }}</div>
    </transition>
  </main>
</template>

<script setup>
// ⚠️ 数据源双轨：拿到登录客户号且 `/wallet/overview` 应答成功 ⇒ 走真实钱包；
//    否则保持原静态示例（预览路由是 public，匿名访问不能报错、也不能伪造余额）。
import { computed, onMounted, ref, watch } from 'vue'
import PvIcon from '../../components/preview/PvIcon.vue'
import { walletLogs, walletOverview, walletRecharge } from '../../api/wallet'
import { getCustomerId } from '../../api/auth'

const customerId = getCustomerId() || ''
const live = ref(false)
const sourceLabel = computed(() => (live.value ? '实时数据' : '示例数据'))
const balance = ref(128.5)
const filter = ref('all')
const rechargeOpen = ref(false)
const amount = ref(200)
const pay = ref('wechat')
const toast = ref('')
const recharging = ref(false)
// 充值的幂等键：同一笔重试必须复用；改金额/改支付方式 ⇒ 视为新的一笔
const rechargeRequestId = ref(null)
let timer = null
let nextId = 100

const filters = [
  { id: 'all', label: '全部' },
  { id: 'income', label: '收入' },
  { id: 'expense', label: '支出' },
]

const amountOptions = [100, 200, 500, 1000]
const pays = [
  { id: 'wechat', label: '微信支付', icon: 'phone' },
  { id: 'alipay', label: '支付宝', icon: 'wallet' },
  { id: 'card', label: '银行卡 · 尾号 8820', icon: 'document' },
]

const rows = ref([
  { id: 1, date: '今天 · 10-19', type: 'income', kind: 'refund', title: '退款入账', meta: '订单 SIM-SECKILL-80451 · 平台退款', amount: 1299.0, time: '10:42' },
  { id: 2, date: '今天 · 10-19', type: 'expense', kind: 'cart', title: '商品消费', meta: '无线降噪耳机 Air Pro · 1 件', amount: -99.0, time: '09:16' },
  { id: 3, date: '10-17', type: 'income', kind: 'plus', title: '余额充值', meta: '微信支付 · 尾号 8820', amount: 100.0, time: '20:08' },
  { id: 4, date: '10-17', type: 'expense', kind: 'cart', title: '商品消费', meta: '极简台灯 · 2 件', amount: -328.0, time: '18:22' },
  { id: 5, date: '10-12', type: 'income', kind: 'refund', title: '退款入账', meta: '订单 SIM-20261010-118 · 商家同意', amount: 59.9, time: '14:05' },
  { id: 6, date: '10-10', type: 'expense', kind: 'cart', title: '商品消费', meta: '27 英寸 2K 显示器 · 1 件', amount: -1299.0, time: '21:37' },
  { id: 7, date: '10-08', type: 'income', kind: 'plus', title: '余额充值', meta: '支付宝 · 尾号 4176', amount: 500.0, time: '11:20' },
  { id: 8, date: '10-05', type: 'expense', kind: 'cart', title: '商品消费', meta: '香氛补充装 · 3 件', amount: -42.5, time: '16:48' },
])

const weekLabels = ['一', '二', '三', '四', '五', '六', '日']
const DEMO_BARS = [24, 46, 18, 58, 34, 66, 44]
const DEMO_WEEK_EXPENSE = 1768.5
const todayIndex = computed(() => (new Date().getDay() + 6) % 7)
const monthRange = computed(() => {
  const now = new Date()
  const mm = String(now.getMonth() + 1).padStart(2, '0')
  return `${mm}-01 至 ${mm}-${String(now.getDate()).padStart(2, '0')}`
})

const filteredRows = computed(() => {
  if (filter.value === 'all') return rows.value
  return rows.value.filter((r) => r.type === filter.value)
})

const groups = computed(() => {
  const out = []
  for (const row of filteredRows.value) {
    let g = out.find((item) => item.date === row.date)
    if (!g) {
      g = { date: row.date, rows: [] }
      out.push(g)
    }
    g.rows.push(row)
  }
  return out
})

const incomeTotal = computed(() =>
  rows.value.filter((r) => r.amount > 0).reduce((sum, r) => sum + r.amount, 0)
)
const expenseTotal = computed(() =>
  rows.value.filter((r) => r.amount < 0).reduce((sum, r) => sum + Math.abs(r.amount), 0)
)
const refundTotal = computed(() =>
  rows.value.filter((r) => r.kind === 'refund').reduce((sum, r) => sum + r.amount, 0)
)
const netSpend = computed(() => Math.max(0, expenseTotal.value - refundTotal.value))
// 近 7 天支出：真实模式下按流水的日期聚合（只数出账），示例模式保留原图
const weekExpense = computed(() => {
  if (!live.value) return DEMO_WEEK_EXPENSE
  const from = Date.now() - 7 * 864e5
  return rows.value.reduce(
    (sum, r) => (r.amount < 0 && r.ts && r.ts >= from ? sum + Math.abs(r.amount) : sum),
    0
  )
})
const bars = computed(() => {
  if (!live.value) return DEMO_BARS
  const sums = [0, 0, 0, 0, 0, 0, 0]
  const from = Date.now() - 7 * 864e5
  rows.value.forEach((r) => {
    if (r.amount >= 0 || !r.ts || r.ts < from) return
    sums[(new Date(r.ts).getDay() + 6) % 7] += Math.abs(r.amount)
  })
  const max = Math.max(...sums, 1)
  return sums.map((v) => Math.round((v / max) * 100))
})
const refundShare = computed(() => {
  const total = refundTotal.value + expenseTotal.value
  return total ? Math.round((refundTotal.value / total) * 1000) / 10 : 0
})

function money(n) {
  const v = Number(n)
  if (!Number.isFinite(v)) return '0.00'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function iconOf(kind) {
  if (kind === 'refund') return 'refund'
  if (kind === 'plus') return 'plus'
  return 'cart'
}

function openRecharge() {
  rechargeOpen.value = true
}

const CHANGE_TITLE = {
  recharge: '余额充值',
  refund: '退款入账',
  consume: '商品消费',
  withdraw_settle: '提现结算',
}

/** 余额流水行 → 页面行；`Direction` 1 入账 / -1 出账，`Amount` 存的是绝对值。 */
function mapLog(row) {
  const signed = Number(row.Amount || 0) * (Number(row.Direction) >= 0 ? 1 : -1)
  const type = row.ChangeType || ''
  return {
    id: `log-${row.Id}`,
    ts: parseTs(row.created_at),
    date: dayLabel(row.created_at),
    time: hhmm(row.created_at),
    type: signed > 0 ? 'income' : 'expense',
    kind: type === 'refund' ? 'refund' : type === 'recharge' ? 'plus' : 'cart',
    title: CHANGE_TITLE[type] || type || (signed > 0 ? '入账' : '出账'),
    meta: row.Remark || row.Channel || row.BizType || '',
    amount: Math.round(signed * 100) / 100,
  }
}

function parseTs(value) {
  const s = String(value || '').replace(' ', 'T')
  const t = Date.parse(s)
  return Number.isFinite(t) ? t : 0
}

function dayLabel(value) {
  const m = String(value || '').match(/^(\d{4})-(\d{2})-(\d{2})/)
  if (!m) return '—'
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const day = `${m[1]}-${m[2]}-${m[3]}`
  return day === today ? `今天 · ${m[2]}-${m[3]}` : `${m[2]}-${m[3]}`
}

function hhmm(value) {
  const m = String(value || '').match(/(\d{2}):(\d{2})/)
  return m ? `${m[1]}:${m[2]}` : '—'
}

async function loadLiveData() {
  if (!customerId) return // 匿名预览：保持示例数据，不发请求
  try {
    const overview = await walletOverview(customerId)
    if (overview && overview.success) {
      live.value = true
      if (overview.wallet && overview.wallet.Available != null) {
        balance.value = Number(overview.wallet.Available)
      }
    }
  } catch (err) {
    console.warn('[WalletPreview] 钱包概览读取失败，保留示例数据', err)
  }
  try {
    const logs = await walletLogs({ customerId, limit: 20 })
    if (logs && logs.success && Array.isArray(logs.list)) {
      rows.value = logs.list.map(mapLog)
    }
  } catch (err) {
    console.warn('[WalletPreview] 流水读取失败，保留上一条数据源', err)
  }
}

// 改金额/改支付方式 ⇒ 这是一笔新充值，旧幂等键不能复用
watch([amount, pay], () => {
  rechargeRequestId.value = null
})

function newRequestId() {
  const c = window.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `wallet-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

async function confirmRecharge() {
  const value = Number(amount.value)
  if (!Number.isFinite(value) || value <= 0 || recharging.value) return

  if (!live.value) {
    // 示例模式：沿用原来的本地演示入账
    balance.value = Math.round((balance.value + value) * 100) / 100
    rows.value.unshift({
      id: nextId++,
      date: `今天 · ${monthRange.value.slice(-5)}`,
      type: 'income',
      kind: 'plus',
      title: '余额充值',
      meta: `${pays.find((p) => p.id === pay.value)?.label || '在线支付'} · 演示入账`,
      amount: value,
      time: '刚刚',
    })
    rechargeOpen.value = false
    notify(`充值 ¥${money(value)} 已到账（演示）`)
    return
  }

  if (!rechargeRequestId.value) rechargeRequestId.value = newRequestId()
  recharging.value = true
  try {
    const res = await walletRecharge({
      customerId,
      amount: value,
      requestId: rechargeRequestId.value,
    })
    if (res && res.success) {
      if (res.balanceAfter != null) balance.value = Number(res.balanceAfter)
      rechargeOpen.value = false
      rechargeRequestId.value = null
      notify(res.duplicate ? '该笔充值已入账（重复提交已忽略）' : `充值 ¥${money(value)} 已到账`)
      await loadLiveData()
    } else {
      notify((res && res.msg) || '充值失败，请稍后再试')
    }
  } catch (err) {
    console.warn('[WalletPreview] 充值失败', err)
    notify('充值失败，请稍后再试')
  } finally {
    recharging.value = false
  }
}

function notify(text) {
  toast.value = text
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { toast.value = '' }, 1800)
}

onMounted(loadLiveData)
</script>

<style scoped>
.pw-page {
  max-width: 1060px;
  margin: 0 auto;
  padding: 40px 38px 64px;
  color: #1a241e;
}

/* ── 余额区 ── */
.pw-hero {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 0;
  margin-bottom: 44px;
  background:
    radial-gradient(520px 260px at 6% 0%, rgba(47, 107, 88, .08), transparent 68%),
    #fff;
  border: 1px solid #e1e7e3;
}
.pw-balance {
  position: relative;
  padding: 30px 34px 24px;
  border-right: 1px solid #e7ece9;
  overflow: hidden;
}
.pw-watermark {
  position: absolute;
  right: 22px;
  top: -30px;
  font-family: var(--font-display);
  font-size: 170px;
  line-height: 1;
  color: rgba(47, 107, 88, .05);
  pointer-events: none;
}
.pw-kicker { font-size: 11px; letter-spacing: .08em; color: #7f8e86; }
.pw-amount {
  margin-top: 14px;
  font-size: clamp(52px, 7vw, 72px);
  font-weight: 500;
  line-height: .95;
  letter-spacing: -.045em;
  font-variant-numeric: tabular-nums;
  color: #17221c;
}
.pw-amount span { font-size: .46em; margin-right: 5px; font-weight: 400; color: #43564b; }
.pw-balance-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
  flex-wrap: wrap;
}
.pw-recharge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 42px;
  padding: 0 22px;
  border: 0;
  border-radius: 10px;
  background: linear-gradient(135deg, #237a5c, #155e46);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 10px 22px -10px rgba(24, 94, 70, .55);
  transition: transform .16s, box-shadow .16s;
}
.pw-recharge:hover { transform: translateY(-1px); box-shadow: 0 14px 26px -10px rgba(24, 94, 70, .6); }
.pw-safe { display: inline-flex; align-items: center; gap: 5px; font-size: 10px; color: #9aa69f; }
.pw-actions {
  display: flex;
  gap: 24px;
  margin-top: 26px;
  padding-top: 17px;
  border-top: 1px solid #eef2ef;
}
.pw-actions button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 11px;
  color: #5d7064;
  cursor: pointer;
}
.pw-actions button:hover { color: #1d2b24; }

.pw-month { padding: 26px 26px 20px; }
.pw-month-head { display: flex; align-items: baseline; justify-content: space-between; }
.pw-month-head h2 { font-family: var(--font-display); font-size: 20px; font-weight: 500; }
.pw-month-head span { font-family: var(--font-mono); font-size: 9px; color: #9aa69f; }
.pw-month-list { margin: 16px 0 0; }
.pw-month-list div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px dashed #edf1ee;
  font-size: 11px;
}
.pw-month-list dt { color: #8d9992; }
.pw-month-list dd { font-family: var(--font-mono); font-size: 12px; color: #35443c; }
.pw-month-list dd.in { color: #159258; }

.pw-chart { margin-top: 16px; }
.pw-chart-head { display: flex; align-items: baseline; justify-content: space-between; }
.pw-chart-head span { font-size: 9.5px; color: #9aa69f; }
.pw-chart-head b { font-family: var(--font-mono); font-size: 12px; font-weight: 500; color: #43564b; }
.pw-bars {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 7px;
  align-items: end;
  height: 64px;
  margin-top: 10px;
}
.pw-bar-col { display: flex; flex-direction: column; align-items: center; justify-content: flex-end; height: 100%; gap: 5px; }
.pw-bar-col i {
  display: block;
  width: 100%;
  max-width: 16px;
  min-height: 4px;
  border-radius: 3px 3px 1px 1px;
  background: #dfe7e3;
}
.pw-bar-col i.on { background: linear-gradient(180deg, #6fb99a, #2f6b58); }
.pw-bar-col span { font-size: 8px; color: #a3aea7; }

/* ── 明细 ── */
.pw-ledger {
  background: #fff;
  border: 1px solid #e1e7e3;
  margin-bottom: 44px;
}
.pw-ledger-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 26px 16px;
  border-bottom: 1px solid #edf1ee;
}
.pw-ledger-head h2 { font-family: var(--font-display); font-size: 22px; font-weight: 500; }
.pw-ledger-head p { margin-top: 5px; font-size: 10.5px; color: #8d9992; }
.pw-filters { display: flex; gap: 4px; padding: 3px; background: #f2f5f3; border-radius: 8px; }
.pw-filters button {
  height: 28px;
  padding: 0 13px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  font-size: 10.5px;
  color: #728078;
  cursor: pointer;
  transition: background .16s, color .16s;
}
.pw-filters button.on { background: #fff; color: #1d2b24; box-shadow: 0 1px 3px rgba(20, 40, 30, .1); }

.pw-groups { padding: 0 26px 8px; }
.pw-group-head {
  display: flex;
  justify-content: space-between;
  padding: 16px 0 6px;
  font-size: 9.5px;
  letter-spacing: .04em;
  color: #9aa69f;
}
.pw-row {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 13px 2px;
  border-top: 1px solid #eef2ef;
  transition: background .16s, padding .16s;
}
.pw-row:hover { background: #fbfcfb; padding-left: 6px; padding-right: 6px; }
.pw-row-ic {
  width: 38px; height: 38px;
  flex: none;
  display: grid; place-items: center;
  border-radius: 50%;
  color: #52675a;
  background: #f0f4f2;
}
.pw-row-ic.refund { color: #158a5c; background: #e7f7ee; }
.pw-row-ic.plus { color: #327a5b; background: #edf7f1; }
.pw-row-tx { flex: 1; min-width: 0; }
.pw-row-tx b { display: block; font-size: 12.5px; font-weight: 600; color: #2e3d34; }
.pw-row-tx p { margin-top: 3px; font-size: 10px; color: #98a39c; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pw-row-time { font-family: var(--font-mono); font-size: 9.5px; color: #a3aea7; }
.pw-row-amt {
  min-width: 108px;
  text-align: right;
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 16px;
  font-weight: 500;
  letter-spacing: -.03em;
  color: #313a40;
}
.pw-row-amt.in { color: #159258; }
.pw-empty { padding: 34px 0; text-align: center; font-size: 11px; color: #9aa69f; }

/* ── 本月小结 ── */
.pw-summary {
  display: grid;
  grid-template-columns: 3.4fr 8.6fr;
  gap: 32px;
  padding: 28px 30px;
  background:
    radial-gradient(420px 200px at 100% 0%, rgba(255, 255, 255, .07), transparent 70%),
    linear-gradient(135deg, #173d31, #0f2a22 70%);
  color: #dce9e2;
}
.pw-summary-lead > span { font-size: 10px; letter-spacing: .12em; color: #8fb3a2; }
.pw-summary-lead h2 {
  margin-top: 10px;
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 500;
  line-height: 1.4;
  color: #edf4ef;
}
.pw-summary-body { display: flex; flex-direction: column; justify-content: center; gap: 16px; }
.pw-summary-bar {
  display: flex;
  height: 9px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(255, 255, 255, .08);
}
.pw-summary-bar .refund { background: linear-gradient(90deg, #6fd0a3, #33a778); }
.pw-summary-bar .spend { background: rgba(233, 241, 237, .38); }
.pw-summary-legend { display: flex; gap: 34px; flex-wrap: wrap; }
.pw-summary-legend div { display: grid; grid-template-columns: 8px auto; column-gap: 7px; align-items: center; }
.pw-summary-legend .dot { width: 6px; height: 6px; border-radius: 50%; }
.dot.refund { background: #6fd0a3; }
.dot.spend { background: #b9c9c0; }
.dot.net { background: #e6c87d; }
.pw-summary-legend b {
  font-family: var(--font-mono);
  font-size: 18px;
  font-weight: 500;
  letter-spacing: -.04em;
  color: #edf4ef;
}
.pw-summary-legend em { grid-column: 2; font-style: normal; font-size: 9px; color: #8fb3a2; }
.pw-summary-note { font-size: 9.5px; line-height: 1.6; color: #7e9c8d; }

.pw-foot {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 20px;
  font-size: 10px;
  line-height: 1.6;
  color: #98a39c;
}

/* ── 充值弹窗 ── */
.pw-modal-root { position: fixed; inset: 0; z-index: 90; }
.pw-mask { position: absolute; inset: 0; background: rgba(16, 26, 21, .38); backdrop-filter: blur(2px); }
.pw-modal {
  position: absolute;
  top: 50%; left: 50%;
  width: min(480px, 92vw);
  max-height: 90vh;
  overflow-y: auto;
  transform: translate(-50%, -50%);
  background: #fff;
  border: 1px solid #e1e7e3;
  box-shadow: 0 30px 80px rgba(15, 30, 22, .24);
}
.pw-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px 16px;
  border-bottom: 1px solid #edf1ee;
}
.pw-modal-head h3 { font-family: var(--font-display); font-size: 22px; font-weight: 500; }
.pw-modal-head p { margin-top: 5px; font-size: 10.5px; color: #8d9992; }
.pw-icon-btn {
  width: 31px; height: 31px;
  display: grid; place-items: center;
  border: 1px solid #e1e7e3;
  border-radius: 50%;
  background: #fff;
  color: #5f6e66;
  cursor: pointer;
}
.pw-modal-body { padding: 18px 24px 10px; }
.pw-field-label { display: block; font-size: 10.5px; font-weight: 600; color: #4c5b53; }
.pw-field-label--gap { margin-top: 18px; }
.pw-amounts {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-top: 9px;
}
.pw-amounts button {
  height: 42px;
  border: 1px solid #dce4df;
  border-radius: 8px;
  background: #fbfcfb;
  font-family: var(--font-mono);
  font-size: 12px;
  color: #3b4b42;
  cursor: pointer;
  transition: border-color .16s, background .16s, color .16s;
}
.pw-amounts button:hover { border-color: #b9d3c5; }
.pw-amounts button.on { border-color: #2f6b58; background: #edf8f2; color: #1f5d47; box-shadow: inset 0 0 0 1px #2f6b58; }
.pw-custom {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 0 12px;
  border: 1px solid #dce4df;
  border-radius: 8px;
}
.pw-custom span { font-size: 10.5px; color: #849189; }
.pw-custom input {
  flex: 1;
  height: 40px;
  border: 0;
  outline: 0;
  background: transparent;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 15px;
  color: #26342c;
}
.pw-pays { margin-top: 9px; border-top: 1px solid #edf1ee; }
.pw-pays button {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 2px;
  border: 0;
  border-bottom: 1px solid #edf1ee;
  background: transparent;
  text-align: left;
  font-size: 11.5px;
  color: #43524a;
  cursor: pointer;
}
.pw-pay-ic {
  width: 28px; height: 28px;
  display: grid; place-items: center;
  border-radius: 7px;
  background: #edf7f1;
  color: #2f7a5b;
}
.pw-pay-ic.alipay { background: #eef4fd; color: #3d74b8; }
.pw-pay-ic.card { background: #f5f0e6; color: #8a6c2d; }
.pw-radio {
  width: 15px; height: 15px;
  margin-left: auto;
  border-radius: 50%;
  border: 1px solid #cbd7d1;
  display: grid;
  place-items: center;
}
.pw-pays button.on .pw-radio { border-color: #2f6b58; }
.pw-radio i { width: 7px; height: 7px; border-radius: 50%; background: transparent; }
.pw-pays button.on .pw-radio i { background: #2f6b58; }
.pw-summary-box {
  margin-top: 16px;
  padding: 12px 14px;
  background: #f6f9f7;
  border: 1px solid #e5ece8;
}
.pw-summary-box div { display: flex; justify-content: space-between; padding: 3px 0; font-size: 10.5px; color: #7d8a83; }
.pw-summary-box b { font-family: var(--font-mono); color: #26342c; font-weight: 500; }
.pw-modal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 24px;
  border-top: 1px solid #edf1ee;
  background: #fbfcfb;
}
.pw-modal-foot > span { font-size: 9.5px; color: #9aa69f; }
.pw-submit {
  height: 38px;
  padding: 0 18px;
  border: 0;
  border-radius: 8px;
  background: #1d2b24;
  color: #fff;
  font-size: 11.5px;
  cursor: pointer;
}
.pw-submit:hover { background: #2f6b58; }
.pw-submit:disabled { opacity: .45; cursor: not-allowed; }

.pw-fade-enter-active, .pw-fade-leave-active { transition: opacity .2s; }
.pw-fade-enter-from, .pw-fade-leave-to { opacity: 0; }
.pw-fade-enter-active .pw-modal, .pw-fade-leave-active .pw-modal { transition: transform .22s, opacity .22s; }
.pw-fade-enter-from .pw-modal, .pw-fade-leave-to .pw-modal { transform: translate(-50%, -46%); opacity: 0; }

.pw-toast {
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
.pw-toast-enter-active, .pw-toast-leave-active { transition: opacity .2s, transform .2s; }
.pw-toast-enter-from, .pw-toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }

@media (max-width: 900px) {
  .pw-page { padding: 30px 22px 52px; }
  .pw-hero { grid-template-columns: 1fr; }
  .pw-balance { border-right: 0; border-bottom: 1px solid #e7ece9; }
  .pw-summary { grid-template-columns: 1fr; gap: 22px; }
  .pw-row-amt { min-width: 92px; font-size: 14px; }
}
@media (max-width: 620px) {
  .pw-balance { padding: 24px 20px 20px; }
  .pw-month { padding: 20px 20px 18px; }
  .pw-actions { gap: 16px; flex-wrap: wrap; }
  .pw-ledger-head { flex-direction: column; align-items: flex-start; padding: 18px 18px 14px; }
  .pw-groups { padding: 0 18px 6px; }
  .pw-row { gap: 10px; }
  .pw-row-time { display: none; }
  .pw-row-amt { min-width: 84px; font-size: 13px; }
  .pw-summary { padding: 22px 20px; }
  .pw-summary-legend { gap: 20px; }
  .pw-amounts { grid-template-columns: 1fr 1fr; }
  .pw-modal-foot { flex-direction: column; align-items: stretch; }
  .pw-submit { width: 100%; }
}
</style>
