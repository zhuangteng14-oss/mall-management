<!-- CouponWalletDialog.vue —— **我的券包**弹窗（C3 块 5 · 券的信息架构重排；**唯一入口**）。

  ⚠️ 为什么券包搬来这里：用户 2026-10-11 定了「**主页不显示券包**」—— 券包是「我的东西」，
     归**用户中心**。主页 `.tickets` 右栏整块与快捷块那格「优惠券 / 我的券包」都已删，
     用户中心「优惠券」格是**唯一入口**（`views/UserInfoPage.vue`）。

  ⚠️ 为什么抽成组件（而不是内联进 UserInfoPage）：那页的券包入口**两支壳都要有一颗**，组件化才只有一份实现
     （同 `CashierDialog` 的取舍）。⚠️ **2026-10-12 起客户 / 商家两支壳都 mount 本组件**（用户：「两端券功能一字不差」）
     —— 它挂在那页 `<main>` 尾部、两支模板**之外**，所以商家壳天然也拿得到；`:account` 一律传**登录名**。

  ⚠️ 本仓无组件库，这份 `<style scoped>` 自备完整一份：`<style scoped>` 只把作用域属性打在
     **子组件根节点**上，根节点**以内**的元素拿不到父页面的样式。
     类名一律 `c` 前缀（`cscrim` / `cdlg` / …）+ 本组件自己的 `w-` 前缀 —— 父页面同名的 `scoped`
     规则会**同时命中本组件根节点**（Vue 会把父作用域属性打在子组件根上）⇒ 撞名就是两份规则叠加。

  数据**自己拉**（同 `CashierDialog` 自己调 `orderPay`，父页保持薄）：
    · 「可领」     ← `coupons(account)`  **不传 `thisWeek`** ⇒ 全量可领（含旧券）＝ 裁定「甲」的「可领」
    · 另三个 tab   ← `myCoupons(account)` 经 `Bucket` 分组，**不重排**（排序全在后端 SQL）
  ⚠️ `account` = **登录名**（`login.UserName`，2026-10-12 由 `user.customerid` 改口径）—— 客户与商家同一把钥匙，本组件**不为商家做任何特殊化**；空账号 ⇒ `<p v-if="!account">` 那句空态，**不造假**。
  用法：
    <CouponWalletDialog :open="walletOpen" :account="couponAccount"
                        @close="walletOpen = false" @changed="loadWallet" />
  `open` 为 false ⇒ 不渲染（开/关都靠它）。
-->
<template>
  <div v-if="open" class="cscrim" @click.self="close">
    <aside class="cdlg" role="dialog" aria-modal="true" aria-label="我的券包" @click.stop>
      <header class="cdlg-h">
        <div class="cdlg-title">
          <div class="w-kicker">BUYER / COUPON WALLET</div>
          <h2>我的券包</h2>
        </div>
        <button class="cx" type="button" @click="close" aria-label="关闭">×</button>
      </header>

      <!-- 4 个 tab。⚠️ 张数角标只数**这个 tab 真有几条**：(可领) 那档排除已领/已领完。 -->
      <nav class="w-tabs" aria-label="券包分类">
        <button
          v-for="t in TABS"
          :key="t.key"
          type="button"
          class="w-tab"
          :class="{ 'w-tab-on': tab === t.key }"
          @click="pickTab(t.key)"
        >
          <span>{{ t.label }}</span>
          <i v-if="counts[t.key] > 0" class="w-cnt">{{ counts[t.key] }}</i>
          <i v-if="t.key === 'unused' && hasExpiring" class="w-dot" title="有券快过期">●</i>
        </button>
      </nav>

      <div class="cdlg-b">
        <p v-if="!account" class="empty-tip">登录后可查看我的券包</p>
        <p v-else-if="loading" class="empty-tip">加载中…</p>
        <p v-else-if="loadError" class="empty-tip w-empty-error">{{ loadError }}</p>
        <p v-else-if="rows.length === 0" class="empty-tip">{{ EMPTY[tab] }}</p>

        <ul v-else class="w-list">
          <li
            v-for="c in rows"
            :key="key(c)"
            class="w-row"
            :class="{ 'w-row-off': tagCls(c) === 'w-tag-off' }"
          >
            <div class="w-head">
              <!-- ⚠️ 这层是 `div[role=button]` 而**不是** `<button>`：里面还要并排一颗真 `<button>`
                   （领取）。按钮套按钮是非法 HTML，浏览器会把它拆开、样式跟着崩。 -->
              <div
                class="w-main"
                role="button"
                tabindex="0"
                :aria-expanded="expandedId === key(c) ? 'true' : 'false'"
                @click="toggle(c)"
                @keydown.enter.prevent="toggle(c)"
                @keydown.space.prevent="toggle(c)"
              >
                <span class="w-cv">
                  <b class="w-amt">{{ couponFace(c) }}</b>
                  <small>{{ couponTypeLabel(c) }}</small>
                </span>
                <span class="w-mid">
                  <b class="w-name"><i v-if="truthy(c.IsNew)" class="w-new">NEW</i>{{ couponName(c) }}</b>
                  <small class="w-sub">{{ subText(c) }}</small>
                  <span class="w-date">有效期 {{ periodLabel(c.StartAt, c.EndAt) }}</span>
                </span>
                <span class="w-tags">
                  <span v-if="Number(c.MemberLevel) > 0" class="w-tag w-tag-vip">VIP 专享</span>
                  <span class="w-tag" :class="tagCls(c)">{{ tagText(c) }}</span>
                </span>
                <span class="w-caret" :class="{ 'w-caret-on': expandedId === key(c) }">›</span>
              </div>
              <button
                v-if="canClaim(c)"
                type="button"
                class="w-get"
                :disabled="claiming === c.CouponId"
                @click="doClaim(c)"
              >{{ claiming === c.CouponId ? '领取中…' : '领取' }}</button>
            </div>

            <!-- 点击展开详情（一次只展开一条）。 -->
            <dl v-if="expandedId === key(c)" class="w-detail">
              <div><dt>面额</dt><dd class="w-mono">{{ couponFace(c) }}</dd></div>
              <div><dt>券类型</dt><dd>{{ couponTypeLabel(c) }}</dd></div>
              <div><dt>门槛</dt><dd>{{ couponCond(c) }}</dd></div>
              <div><dt>适用范围</dt><dd>{{ scopeLabel(c) }}</dd></div>
              <div><dt>有效期</dt><dd class="w-mono">{{ periodLabel(c.StartAt, c.EndAt) }}</dd></div>
              <div v-if="c.GrantedAt"><dt>领取时间</dt><dd class="w-mono">{{ toLocal(c.GrantedAt) }}</dd></div>
              <div v-if="c.UsedAt"><dt>使用时间</dt><dd class="w-mono">{{ toLocal(c.UsedAt) }}</dd></div>
              <div v-if="c.OrderId"><dt>订单号</dt><dd class="w-mono">{{ c.OrderId }}</dd></div>
              <div><dt>券编号</dt><dd class="w-mono">{{ c.CouponId }}</dd></div>
            </dl>
          </li>
        </ul>

        <p v-if="msg" class="w-msg">{{ msg }}</p>
      </div>

      <footer class="cdlg-f">
        <button class="cact" type="button" @click="close">关闭</button>
      </footer>
    </aside>
  </div>
</template>

<script setup>
/* ⚠️ 这条 `eslint-disable` 是**必需**的，不是偷懒：本仓 `eslint-plugin-vue` 是 **8.7.1**，
   其 `vue3-essential` **不带** `vue/setup-compiler-macros` 宏环境 ⇒ `defineProps` / `defineEmits`
   一律被判 `no-undef`、`npm run build` 直接红（同 `components/CashierDialog.vue:45-50`）。 */
/* eslint-disable no-undef */
import { computed, ref, watch } from 'vue'
import { handleApiError } from '../api/http'
import { claim, coupons, myCoupons } from '../api/marketing'
import {
  bucketLabel, couponCond, couponFace, couponName, couponTypeLabel, endLabel, periodLabel, scopeLabel, toLocal, truthy,
} from '../config/coupon-format'

const props = defineProps({
  /** 开/关（父页一个 `ref` 的事）；false ⇒ 不渲染。 */
  open: { type: Boolean, default: false },
  /** 用户业务号（`user.customerid`）。空 ⇒ 不取数、显示「登录后可查看」。 */
  account: { type: String, default: '' }
})
const emit = defineEmits(['close', 'changed'])

/**
 * 4 个 tab。取值与用户裁定逐字一致：`[可领] [未使用] [已使用] [已过期]`。
 * ⚠️ 「未使用」= 后端两个桶之和（`expiring` ∪ `usable`）—— 那是**同一件事的两档紧迫度**，
 *    不是两种东西；「快过期」只在标签与红点上体现（用户 #4 ②）。
 */
const TABS = [
  { key: 'claim', label: '可领' },
  { key: 'unused', label: '未使用' },
  { key: 'used', label: '已使用' },
  { key: 'expired', label: '已过期' },
]

/** 各 tab 的空态。⚠️「已使用」那条要**如实说明为什么空** —— 核销在结算（块 5 commit 5），现在还到不了这里。 */
const EMPTY = {
  claim: '暂时没有可领的券。新券发布后会出现在这里，也会出现在主页的领券中心。',
  unused: '还没有可用的券，去主页领券中心看看本周新券。',
  used: '还没有用掉的券。结算时选一张券抵扣，用掉后会出现在这里（券的核销在结算流程里）。',
  expired: '没有过期的券 —— 过期未用的券会留在这里。',
}

const tab = ref('claim')
const claimable = ref([])
const mine = ref([])
const loading = ref(false)
const loadError = ref('')
const claiming = ref('')
const expandedId = ref('')
const msg = ref('')

/** 行标识：券包行有 `GrantId`、可领行只有 `CouponId` ⇒ 两者取其一（同一张券在同一 tab 内唯一）。 */
const key = (c) => String(c.GrantId || c.CouponId || '')

/** 当前 tab 的行 —— ⚠️ **直接切，不重排**：桶内次序由 `CouponDao.selectMyGrants` 排好（见 `api/marketing.js`）。 */
const rows = computed(() => {
  if (tab.value === 'claim') return claimable.value
  if (tab.value === 'unused') return mine.value.filter((r) => r.Bucket === 'expiring' || r.Bucket === 'usable')
  if (tab.value === 'used') return mine.value.filter((r) => r.Bucket === 'used')
  return mine.value.filter((r) => r.Bucket === 'expired')
})

/** 「可领」角标只数**还领得动**的（已领/已领完仍列出来、但不算「可领」）。 */
const canClaim = (c) => tab.value === 'claim' && !truthy(c.Claimed) && !truthy(c.SoldOut)

const counts = computed(() => ({
  claim: claimable.value.filter((c) => !truthy(c.Claimed) && !truthy(c.SoldOut)).length,
  unused: mine.value.filter((r) => r.Bucket === 'expiring' || r.Bucket === 'usable').length,
  used: mine.value.filter((r) => r.Bucket === 'used').length,
  expired: mine.value.filter((r) => r.Bucket === 'expired').length,
}))

/** 快过期的券（未使用里那一档）—— 决定 tab 上那枚红点，与用户中心入口的红点**同一个判据**。 */
const hasExpiring = computed(() => mine.value.some((r) => r.Bucket === 'expiring'))

const subText = (c) => {
  const parts = [couponCond(c), scopeLabel(c)]
  // 「X 天后过期」只在**快过期**那档显示（其余档说了是噪音；无到期日的 `endLabel` 回空串）。
  if (c.Bucket === 'expiring') parts.push(endLabel(c.EndDaysLeft))
  return parts.filter(Boolean).join(' · ')
}

const tagText = (c) => {
  if (tab.value === 'claim') return truthy(c.Claimed) ? '已领取' : truthy(c.SoldOut) ? '已领完' : '可领'
  if (tab.value === 'unused') return '未使用'
  return bucketLabel(c.Bucket)
}

/** 暖色只给「快过期」—— 四个状态里唯一一件要人**现在动手**的事（同 `HomePage.vue` 那张表的取舍）。 */
const tagCls = (c) => {
  if (tab.value === 'claim') return truthy(c.Claimed) || truthy(c.SoldOut) ? 'w-tag-off' : 'w-tag-on'
  if (tab.value === 'unused') return c.Bucket === 'expiring' ? 'w-tag-warn' : 'w-tag-on'
  return 'w-tag-off'
}

const toggle = (c) => { expandedId.value = expandedId.value === key(c) ? '' : key(c) }

const pickTab = (k) => { tab.value = k; expandedId.value = ''; msg.value = '' }

const close = () => emit('close')

/**
 * 并发拉两个列表。
 * ⚠️ `coupons` **不传第三参** ⇒ 省略 `thisWeek` ⇒ 全量可领（裁定「甲」的「可领」含旧券）；
 *    `myCoupons` 不分页（后端给全量）。
 * ⚠️ 两个请求**各自成败**：券包挂了不该把「可领」也画空 ⇒ 分别判 `success`。
 */
async function load() {
  if (!props.account) return
  loading.value = true
  loadError.value = ''
  try {
    const [avail, owned] = await Promise.all([coupons(props.account), myCoupons(props.account)])
    claimable.value = avail && avail.success && Array.isArray(avail.list) ? avail.list : []
    mine.value = owned && owned.success && Array.isArray(owned.list) ? owned.list : []
  } catch (e) {
    loadError.value = '券包加载失败'
    handleApiError(e, '券包加载失败')
  } finally {
    loading.value = false
  }
}

/** 领一张（复用既有单张端点）。⚠️ **核销不在这里** —— 核销在结算，块 5 commit 5。 */
async function doClaim(c) {
  if (claiming.value) return
  claiming.value = c.CouponId
  msg.value = ''
  try {
    const data = await claim(props.account, c.CouponId)
    const ok = !!(data && data.success)
    msg.value = ok ? '已领取「' + c.Name + '」' : '领取失败：' + ((data && data.msg) || '未知原因')
    if (ok) {
      await load()
      emit('changed')          // 父页据此刷新入口上的券数/红点（父页**不**自己再拉一次）
    }
  } catch (e) {
    handleApiError(e, '领取失败')
  } finally {
    claiming.value = ''
  }
}

/* ⚠️ 每次**开窗**重置（回「可领」、收起详情、清掉上次的提示）—— 但**不在这里拉数据**：
   靠 `load()` 一处取数，避免「开窗拉一次、切 tab 又拉一次」。
   `account` 后到（父页先开窗、`customerid` 可能还在路上）⇒ 也补看一眼。 */
watch(() => props.open, (o) => {
  if (o) {
    tab.value = 'claim'
    expandedId.value = ''
    msg.value = ''
    load()
  }
})
watch(() => props.account, (a) => { if (a && props.open) load() })
</script>

<!-- ⚠️ 类名统一 `c` 前缀（壳）与 `w` 前缀（本弹窗自己的）**不是**排版偏好：
     父页（UserInfoPage）同名的 `scoped` 规则会**同时命中本组件根节点**
     （Vue 会把父作用域属性打在子组件根上）⇒ 两份规则叠加、改一处不知另一处在生效。
     `.w-main` / `.w-tag` / `.w-amt` 这些主页面也有同名的 `.tk-amt` 之类，撞名迟早发生。 -->
<style scoped>
/* ── 遮罩 + 右侧抽屉壳（A 版 `drawer` / `drawer-mask` 语言）─────────────── */
.cscrim {
  position: fixed;
  inset: 0;
  /* ⚠️ `300` 是全仓既有的「**弹窗叠弹窗**」档（出处 `views/TemplatePicker.vue:105`）：
     `60`（页内弹窗）< `150`（商品页购入模块）< `200`（商品页大弹窗）< **`300`**。
     用户中心没有更上层的弹窗，但档位照取 —— 别顺手降到 100/200 的「顺眼值」：
     那会重新埋回「弹窗被压住且**错得很安静**」那个 bug（`CashierDialog.vue:103` 记着全过程）。 */
  z-index: 300;
  background: rgba(0, 0, 0, 0.26);
}
.cdlg {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(540px, 94vw);
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-left: 1px solid var(--line);
  box-shadow: -12px 0 32px rgba(0, 0, 0, 0.1);
  animation: w-drawer-in 0.22s cubic-bezier(0.2, 0.7, 0.2, 1);
}
@keyframes w-drawer-in {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}
.cdlg-h {
  flex: none;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s5);
  padding: 20px 22px;
  border-bottom: 1px solid var(--line);
}
.w-kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-3);
}
.cdlg-h h2 {
  margin-top: 5px;
  font-family: var(--font-display);
  font-size: 27px;
  line-height: 1.1;
  font-weight: var(--fw-semi);
  letter-spacing: -0.02em;
  color: var(--ink);
}
.cdlg-h .cx {
  flex: none;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  transition: background-color 0.12s linear, color 0.12s linear;
}
.cdlg-h .cx:hover { background: var(--ink); color: var(--surface); }
.cdlg-h .cx:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }

/* ── 4 个 tab —— A 版直角色块页签（`msg-tabs` 语言），选中项反白 ──────── */
.w-tabs {
  flex: none;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-bottom: 1px solid var(--ink);
}
.w-tab {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-width: 0;
  padding: 10px 6px;
  border: 0;
  border-right: 1px solid var(--line);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink-2);
  font: inherit;
  font-size: var(--fs-xs);
  cursor: pointer;
  transition: background-color 0.12s linear, color 0.12s linear;
}
.w-tab:last-child { border-right: 0; }
.w-tab:hover { background: var(--surface-2); }
.w-tab-on,
.w-tab-on:hover {
  background: var(--ink);
  border-right-color: var(--ink);
  color: var(--surface);
  font-weight: var(--fw-med);
}
.w-tab:focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }
.w-cnt {
  flex: none;
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 14px;
  border: 1px solid currentColor;
  padding: 0 4px;
}
.w-dot {
  flex: none;
  font-style: normal;
  font-size: 8px;
  line-height: 1;
  color: var(--danger);
}
.w-tab-on .w-dot { color: var(--cin-300); }

/* ── 抽屉正文：空态 / 加载 / 错误一律是虚线直角面板 ───────────────────── */
.cdlg-b {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 22px;
  background: var(--surface);
}
.empty-tip {
  margin: 0;
  padding: 40px 20px;
  border: 1px dashed var(--line-2);
  background: var(--surface);
  color: var(--ink-3);
  font-size: var(--fs-xs);
  line-height: 1.7;
  text-align: center;
}
.empty-tip.w-empty-error {
  border-style: solid;
  border-color: var(--danger);
  background: var(--danger-ground);
  color: var(--danger-deep);
}

/* ── 券行：A 版 `coupon-item` 的三段式（面额块 / 信息 / 状态与动作）──────
   `.w-main` 仍是 `div[role=button]`，领取按钮留在它外面（按钮套按钮非法）。 */
.w-list { display: flex; flex-direction: column; gap: 12px; margin: 0; padding: 0; list-style: none; }
.w-row {
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--surface);
  overflow: hidden;
}
.w-row:hover { border-color: var(--line-2); }
.w-row-off { background: var(--surface-2); }
.w-head { display: flex; align-items: stretch; }
.w-main {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) auto 18px;
  align-items: center;
  cursor: pointer;
}
.w-main:focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }
/* 面额走 A 版左侧独立数块：浅底 + 右发丝线，数字用 mono（等宽、tabular）。 */
.w-cv {
  align-self: stretch;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 12px 8px;
  border-right: 1px solid var(--line);
  background: var(--surface-2);
}
.w-amt {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 21px;
  font-weight: var(--fw-reg);
  line-height: 1;
  color: var(--ink);
  white-space: nowrap;
}
.w-cv small {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.04em;
  color: var(--ink-3);
  white-space: nowrap;
}
.w-row-off .w-cv { background: var(--surface-3); }
.w-row-off .w-amt { color: var(--ink-3); }
.w-mid {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px;
}
.w-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.w-row-off .w-name { color: var(--ink-3); }
.w-new {
  display: inline-block;
  margin-right: 6px;
  padding: 0 5px;
  border-radius: 0;
  background: var(--ink);
  color: var(--surface);
  font-family: var(--font-mono);
  font-size: 9px;
  font-style: normal;
  font-weight: var(--fw-reg);
  line-height: 15px;
  letter-spacing: 0.06em;
  vertical-align: 1px;
}
.w-sub,
.w-date { font-size: var(--fs-xs); line-height: 1.45; color: var(--ink-3); }
.w-tags {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  padding: 0 8px;
}
.w-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 7px;
  border: 1px solid var(--line-2);
  border-radius: 0;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.04em;
  line-height: 1.5;
  white-space: nowrap;
}
.w-tag-on { color: var(--success-deep); background: var(--success-ground); border-color: var(--success); }
.w-tag-warn { color: var(--warning-deep); background: var(--warning-ground); border-color: var(--warning); }
.w-tag-off { color: var(--ink-3); background: var(--surface-3); }
/* VIP 专享券（批四第二批 commit 3）：`coupon.MemberLevel > 0` 的券加一枚标签。
   ⚠️ 与状态标签 `.w-tag` 同形，但颜色取品牌色 —— 它是**身份标签**，不与「可领取/已使用」抢语义。 */
.w-tag-vip { color: var(--brand-ink); background: var(--brand-soft); border-color: var(--brand); }
.w-caret {
  padding-right: 10px;
  color: var(--ink-3);
  font-family: var(--font-mono);
  font-size: 16px;
  line-height: 1;
  text-align: center;
  transition: transform 0.15s linear;
}
.w-caret-on { transform: rotate(90deg); }

/* 领取按钮：A 版实心块（`btn solid`），禁用态退回浅底。 */
.w-get {
  flex: none;
  align-self: center;
  min-width: 54px;
  height: 30px;
  margin: 0 12px;
  padding: 0 12px;
  border: 1px solid var(--ink);
  border-radius: 0;
  background: var(--ink);
  color: var(--surface);
  font: inherit;
  font-size: var(--fs-xs);
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.12s linear, color 0.12s linear;
}
.w-get:hover { background: var(--surface); color: var(--ink); }
.w-get:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
.w-get[disabled] {
  background: var(--surface-3);
  border-color: var(--line-2);
  color: var(--ink-3);
  cursor: default;
}

/* 展开详情：A 版 ledger（发丝线行 + 右侧数值），字段一个不少。 */
.w-detail {
  margin: 0;
  padding: 4px 16px 6px;
  border-top: 1px solid var(--line);
  background: var(--surface);
}
.w-row-off .w-detail { background: var(--surface-2); }
.w-detail > div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-xs);
}
.w-detail > div:last-child { border-bottom: 0; }
.w-detail dt {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.04em;
  color: var(--ink-3);
  white-space: nowrap;
}
.w-detail dd {
  margin: 0;
  color: var(--ink-2);
  text-align: right;
  overflow-wrap: anywhere;
}
.w-mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }

/* 领取反馈：直角提示块（品牌色，不与错误状态混淆）。 */
.w-msg {
  margin: var(--s3) 0 0;
  padding: 9px 12px;
  border: 1px solid var(--brand);
  background: var(--brand-tint);
  color: var(--brand-ink);
  font-size: var(--fs-xs);
}

/* ── 抽屉页脚 ─────────────────────────────────────────────────────────── */
.cdlg-f {
  flex: none;
  display: flex;
  justify-content: flex-end;
  gap: var(--s2);
  padding: 14px 22px;
  border-top: 1px solid var(--line);
  background: var(--surface-2);
}
.cact {
  height: 32px;
  padding: 0 16px;
  border: 1px solid var(--ink);
  border-radius: 0;
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.12s linear, color 0.12s linear;
}
.cact:hover { background: var(--ink); color: var(--surface); }
.cact:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }

@media (max-width: 560px) {
  .cdlg { width: 100vw; }
  .cdlg-h { padding: 16px; }
  .cdlg-b { padding: 16px; }
  .w-main { grid-template-columns: 82px minmax(0, 1fr) auto; }
  .w-caret { display: none; }
  .w-mid { padding: 10px; }
  .w-cv { padding: 10px 4px; }
  .w-amt { font-size: 18px; }
  .w-get { min-width: 48px; margin: 0 8px; padding: 0 10px; }
}
@media (prefers-reduced-motion: reduce) {
  .cdlg { animation: none; }
}
</style>
