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
    <div class="cdlg" @click.stop>
      <div class="cdlg-h">
        <span>我的券包</span>
        <button class="cx" @click="close" aria-label="关闭">×</button>
      </div>

      <!-- 4 个 tab。⚠️ 张数角标只数**这个 tab 真有几条**：(可领) 那档排除已领/已领完。 -->
      <div class="w-tabs">
        <button
          v-for="t in TABS"
          :key="t.key"
          type="button"
          class="w-tab"
          :class="{ 'w-tab-on': tab === t.key }"
          @click="pickTab(t.key)"
        >
          {{ t.label }}<i v-if="counts[t.key] > 0" class="w-cnt">{{ counts[t.key] }}</i>
          <i v-if="t.key === 'unused' && hasExpiring" class="w-dot" title="有券快过期">●</i>
        </button>
      </div>

      <div class="cdlg-b">
        <p v-if="!account" class="empty-tip">登录后可查看我的券包</p>
        <p v-else-if="loading" class="empty-tip">加载中…</p>
        <p v-else-if="loadError" class="empty-tip">{{ loadError }}</p>
        <p v-else-if="rows.length === 0" class="empty-tip">{{ EMPTY[tab] }}</p>

        <ul v-else class="w-list">
          <li v-for="c in rows" :key="key(c)" class="w-row">
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
                <span class="w-amt">{{ couponFace(c) }}</span>
                <span class="w-mid">
                  <b><i v-if="truthy(c.IsNew)" class="w-new">NEW</i>{{ couponName(c) }}</b>
                  <span class="w-sub">{{ subText(c) }}</span>
                  <span class="w-date">有效期 {{ periodLabel(c.StartAt, c.EndAt) }}</span>
                </span>
                <span class="w-tag" :class="tagCls(c)">{{ tagText(c) }}</span>
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
              <div><dt>面额</dt><dd>{{ couponFace(c) }}</dd></div>
              <div><dt>券类型</dt><dd>{{ couponTypeLabel(c) }}</dd></div>
              <div><dt>门槛</dt><dd>{{ couponCond(c) }}</dd></div>
              <div><dt>适用范围</dt><dd>{{ scopeLabel(c) }}</dd></div>
              <div><dt>有效期</dt><dd>{{ periodLabel(c.StartAt, c.EndAt) }}</dd></div>
              <div v-if="c.GrantedAt"><dt>领取时间</dt><dd>{{ toLocal(c.GrantedAt) }}</dd></div>
              <div v-if="c.UsedAt"><dt>使用时间</dt><dd>{{ toLocal(c.UsedAt) }}</dd></div>
              <div v-if="c.OrderId"><dt>订单号</dt><dd class="w-mono">{{ c.OrderId }}</dd></div>
              <div><dt>券编号</dt><dd class="w-mono">{{ c.CouponId }}</dd></div>
            </dl>
          </li>
        </ul>

        <p v-if="msg" class="w-msg">{{ msg }}</p>
      </div>

      <div class="cdlg-f">
        <button class="cact" @click="close">关闭</button>
      </div>
    </div>
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
.cscrim {
  position: fixed;
  inset: 0;
  /* ⚠️ `300` 是全仓既有的「**弹窗叠弹窗**」档（出处 `views/TemplatePicker.vue:105`）：
     `60`（页内弹窗）< `150`（商品页购入模块）< `200`（商品页大弹窗）< **`300`**。
     用户中心没有更上层的弹窗，但档位照取 —— 别顺手降到 100/200 的「顺眼值」：
     那会重新埋回「弹窗被压住且**错得很安静**」那个 bug（`CashierDialog.vue:103` 记着全过程）。 */
  z-index: 300;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s5);
}
.cdlg {
  width: min(560px, 100%);
  max-height: 86vh;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.22);
}
.cdlg-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--line);
  font-family: var(--font-display);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.cdlg-h .cx {
  border: 0;
  background: transparent;
  font-size: var(--fs-lg);
  line-height: 1;
  color: var(--ink-3);
  cursor: pointer;
}
.cdlg-b { padding: var(--s5); }
.cdlg-f {
  display: flex;
  justify-content: flex-end;
  gap: var(--s3);
  padding: var(--s4) var(--s5);
  border-top: 1px solid var(--line);
}
.cact {
  height: 30px;
  padding: 0 var(--s4);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--brand-ink);
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  white-space: nowrap;
  cursor: pointer;
}
.cact:hover { border-color: var(--brand); }

/* 4 个 tab —— 下划线式，避免与弹窗边框叠出双线。 */
.w-tabs {
  display: flex;
  gap: var(--s2);
  padding: 0 var(--s5);
  border-bottom: 1px solid var(--line);
}
.w-tab {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  padding: var(--s3) var(--s2);
  cursor: pointer;
}
.w-tab-on { color: var(--brand-ink); border-bottom-color: var(--brand-ink); font-weight: var(--fw-med); }
.w-cnt {
  font-style: normal;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-left: var(--s1);
}
.w-dot { font-style: normal; font-size: var(--fs-xs); color: var(--danger); margin-left: var(--s1); }

.w-list { display: flex; flex-direction: column; gap: var(--s2); margin: 0; padding: 0; list-style: none; }
.w-row {
  border: 1px solid var(--line);
  border-radius: var(--r2);
  background: var(--surface-2);
}
.w-head { display: flex; align-items: center; gap: var(--s2); }
.w-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3);
  cursor: pointer;
}
.w-main:focus-visible { outline: 2px solid var(--brand); outline-offset: -2px; }
/* 面额走**大数字**（用户：每条券展示「面额大数字」）—— 与主页面那张小卡上的 `.tk-amt` 分开定尺寸。 */
.w-amt {
  font-family: var(--font-mono);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  color: var(--brand-ink);
  white-space: nowrap;
}
.w-mid { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.w-mid b {
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.w-new {
  font-style: normal;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-radius: var(--r1);
  padding: 0 4px;
  margin-right: var(--s1);
}
.w-sub,
.w-date { font-size: var(--fs-xs); color: var(--ink-3); }
.w-tag {
  font-size: var(--fs-xs);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.w-tag-on { color: var(--success-deep); background: var(--success-ground); }
.w-tag-warn { color: var(--warning-deep); background: var(--warning-ground); }
.w-tag-off { color: var(--ink-3); background: var(--surface-3); }
.w-caret { color: var(--ink-3); transition: transform 0.15s; }
.w-caret-on { transform: rotate(90deg); }

.w-get {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border: 1px solid var(--brand-ink);
  border-radius: var(--r1);
  padding: 2px var(--s3);
  margin-right: var(--s3);
  white-space: nowrap;
  cursor: pointer;
}
.w-get[disabled] { color: var(--ink-3); background: var(--surface-3); border-color: var(--line-2); cursor: default; }

.w-detail {
  margin: 0;
  padding: var(--s3) var(--s3) var(--s3) var(--s6);
  border-top: 1px dashed var(--line-2);
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--s2) var(--s4);
}
.w-detail > div { display: flex; gap: var(--s2); font-size: var(--fs-xs); }
.w-detail dt { color: var(--ink-3); white-space: nowrap; }
.w-detail dd { margin: 0; color: var(--ink-2); }
.w-mono { font-family: var(--font-mono); }
.w-msg { margin: var(--s3) 0 0; font-size: var(--fs-xs); color: var(--brand-ink); }
</style>
