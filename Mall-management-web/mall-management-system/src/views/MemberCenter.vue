<template>
  <!--
    会员中心（批四第二批 commit 3，2026-10-19）—— 路由 `/customer/user/member`。
    挂在「用户中心」壳下（`customerUser` 的子记录，`meta.sub = 'member'`）⇒ 左菜单在场，
    本页只承载右栏（与 `UserInfoPage.vue` 同形）。

    ⚠️ 等级/余额**只读本地会话恢复态**（`api/auth.js`），本页启动**不再打** `/user/me`：
    `main.js` 已经打过一次。开通成功后等级改为本地 ref，提示「刷新后全站按会员价」——
    其他页面要重新进站 / 刷新才会拿到新档位（身份恢复只在启动时跑一次）。
  -->
  <main class="uc-main">
    <h1 class="page-h">会员中心</h1>
    <p class="page-h-sub">全场商品 9 折，可与优惠券叠加；每天还能领一张会员专属券。</p>

    <section class="card lvl">
      <div class="lvl-main">
        <span class="lvl-badge" :class="{ vip: isVip }">{{ isVip ? 'VIP 会员' : '普通用户' }}</span>
        <p class="lvl-tip" v-if="isVip">已享受全场 9 折 · 每日专属券 · 售后优先处理</p>
        <p class="lvl-tip" v-else>开通会员即可享受全场 9 折与每日专属券（演示环境免开通）</p>
      </div>
      <div class="lvl-side">
        <span class="k">账户余额</span>
        <span class="v">¥{{ balanceText }}</span>
      </div>
    </section>

    <section class="card benefits">
      <h2 class="sec-h">会员权益</h2>
      <ul class="bf-list">
        <li><b>9 折</b><span>商品详情、购物车与结算价统一按会员价计算，优惠券在会员价基础上再减。</span></li>
        <li><b>每日专属券</b><span>每天可领一张会员专属券（满 50 减 5），当天限领一次。</span></li>
        <li><b>售后优先</b><span>平台售后队列按会员等级优先处理。</span></li>
      </ul>
    </section>

    <section class="card actions">
      <div class="act">
        <button v-if="!isVip" type="button" class="btn btn-primary" :disabled="opening" @click="doOpen">
          {{ opening ? '开通中…' : '立即开通会员' }}
        </button>
        <span v-else class="act-ok">会员已生效</span>
        <p v-if="openMsg" class="msg" :class="{ bad: !openOk }">{{ openMsg }}</p>
      </div>
      <div class="act">
        <button type="button" class="btn" :disabled="claiming || !isVip" @click="doClaimDaily">
          {{ claiming ? '领取中…' : '领取今日专属券' }}
        </button>
        <p v-if="claimMsg" class="msg" :class="{ bad: !claimOk }">{{ claimMsg }}</p>
      </div>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { getBalance, getMemberLevel, getUsername, setMemberLevel } from '../api/auth'
import { openMember, claimDailyCoupon } from '../api/member'

// 等级用本地 ref：开通成功后立即切换页面展示；跨页面同步靠下一次 `/user/me`（刷新 / 新标签页）。
const level = ref(Number(getMemberLevel()) || 0)
const isVip = computed(() => level.value > 0)
const balance = ref(Number(getBalance()) || 0)
const balanceText = computed(() => balance.value.toFixed(2))

const opening = ref(false)
const openMsg = ref('')
const openOk = ref(false)

const claiming = ref(false)
const claimMsg = ref('')
const claimOk = ref(false)

async function doOpen() {
  const username = getUsername()
  if (!username) {
    openOk.value = false
    openMsg.value = '未识别到登录账号，请重新登录'
    return
  }
  opening.value = true
  openMsg.value = ''
  try {
    const data = await openMember(username)
    openOk.value = !!data.success
    openMsg.value = data.success
      ? '会员开通成功 —— 刷新页面后，商品详情 / 购物车 / 结算都会按 9 折计价'
      : (data.msg || '开通失败')
    if (data.success) { setMemberLevel(Number(data.memberLevel) || 1); level.value = Number(getMemberLevel()) || 1 }   // ⚠️ 同步写 `sessionStorage` ⇒ 刷新后仍是 VIP（`main.js` 对已登录页不再打 `/user/me`）
  } catch (e) {
    openOk.value = false
    openMsg.value = '开通失败：' + (e && e.message ? e.message : '网络错误')
  } finally {
    opening.value = false
  }
}

async function doClaimDaily() {
  const username = getUsername()
  if (!username) {
    claimOk.value = false
    claimMsg.value = '未识别到登录账号，请重新登录'
    return
  }
  claiming.value = true
  claimMsg.value = ''
  try {
    const data = await claimDailyCoupon(username)
    claimOk.value = !!data.success
    // ⚠️ 后端 `msg` 已区分「领取成功 / 您已领取过该券」，这里**不再**加前缀，
    //    否则会出现「领取失败：领取失败：…」那类双层文案（与领券块同一条教训）。
    claimMsg.value = data.msg || (data.success ? '领取成功' : '领取失败')
  } catch (e) {
    claimOk.value = false
    claimMsg.value = '领取失败：' + (e && e.message ? e.message : '网络错误')
  } finally {
    claiming.value = false
  }
}
</script>

<style scoped>
.uc-main { padding: var(--s5); }
.page-h { font-family: var(--font-display); font-size: var(--fs-xl); color: var(--ink); margin: 0 0 var(--s2); }
.page-h-sub { color: var(--ink-2); font-size: var(--fs-sm); margin: 0 0 var(--s5); }
.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s4) var(--s5);
  margin-bottom: var(--s4);
}
.lvl { display: flex; align-items: center; gap: var(--s5); }
.lvl-main { flex: 1; }
.lvl-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--r1);
  border: 1px solid var(--line);
  color: var(--ink-2);
  font-weight: var(--fw-med);
}
.lvl-badge.vip {
  border-color: var(--brand-ink);
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.lvl-tip { margin: var(--s2) 0 0; color: var(--ink-2); font-size: var(--fs-sm); }
.lvl-side { text-align: right; }
.lvl-side .k { display: block; color: var(--ink-2); font-size: var(--fs-xs); }
.lvl-side .v { font-family: var(--font-mono); font-size: var(--fs-lg); color: var(--ink); }
.sec-h { font-size: var(--fs-md); margin: 0 0 var(--s3); color: var(--ink); }
.bf-list { list-style: none; margin: 0; padding: 0; }
.bf-list li { display: flex; gap: var(--s3); padding: var(--s2) 0; border-top: 1px solid var(--line); color: var(--ink-2); font-size: var(--fs-sm); }
.bf-list li:first-child { border-top: 0; }
.bf-list b { flex: none; width: 88px; color: var(--brand-ink); }
.actions { display: flex; gap: var(--s5); flex-wrap: wrap; }
.act { flex: 1; min-width: 240px; }
.act .btn { min-width: 160px; }
.act-ok { color: var(--ink-2); font-size: var(--fs-sm); }
.msg { margin: var(--s2) 0 0; font-size: var(--fs-sm); color: var(--brand-ink); }
.msg.bad { color: var(--danger); }
</style>
