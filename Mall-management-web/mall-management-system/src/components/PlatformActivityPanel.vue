<template>
  <section class="pact">
    <h2 class="pact-h">平台活动<i class="pact-note">平台发给本店的邀请，与本店自己报名的秒杀</i></h2>

    <!-- ── 待应答 ─────────────────────────────────────────────────────────────
         「待办」= 两路邀请里**还没答、且还来得及答**的那些。两条路的判据各按各的：
           · 秒杀：后端 `SeckillDao.stage()` 在**库时钟**上算出的 `Stage === 'signup'`
             （报名截止未到）。⚠️ 前端**不再自己比时间** —— 库里存 UTC、浏览器按本地时区解析，
             自己比会差一个时区（本仓为这个坑写过好几处注释）。
           · 国补：`OnlyOpen: 'true'` 交给后端过滤（`SubsidyDao.NOT_EXPIRED`，那条注释里
             逐字标了它是「替代量」）。
         两路都另加一个 `Status`：秒杀 `invited`、国补 `pending` —— 已经答过的当场从表里消失。 -->
    <h3 class="pact-sub">待应答</h3>

    <p v-if="state === 'loading'" class="pact-ph">正在读取…</p>
    <p v-else-if="state === 'error'" class="pact-ph pact-ph-err">
      {{ err }}<button type="button" class="pact-retry" @click="load">重试</button>
    </p>
    <table v-else-if="todos.length" class="pact-table">
      <thead>
        <tr><th>来源</th><th>商品</th><th>条件</th><th class="num">操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in todos" :key="t.key">
          <td><span class="pact-tag" :class="'pact-tag-' + t.kind">{{ t.from }}</span></td>
          <td class="nm">{{ t.goodName }}</td>
          <td class="cond"><span v-for="(s, i) in t.cond" :key="i" class="pact-it">{{ s }}</span></td>
          <td class="ops">
            <button type="button" class="pact-btn" :disabled="busy" @click="answer(t, true)">同意</button>
            <button type="button" class="pact-btn pact-btn-no" :disabled="busy" @click="answer(t, false)">拒绝</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else class="pact-ph">暂无待应答的邀请。平台邀请本店参加活动后，会出现在这里。</p>

    <p v-if="flash.text" class="pact-flash" :class="'pact-' + flash.kind">{{ flash.text }}</p>

    <!-- ── 本店报名（路径 B 的发起端）────────────────────────────────────────────
         直接复用 `views/merchant/SeckillApplyPage.vue`，不抄第二份表单：
         那条链有 5 条服务端校验（原价区间 / 限量非负整数 / 同商品不许重复报名…），
         抄一份 ⇒ 第二处抄错时**两边都不报错**。
         ⚠️ `embedded` 为真 ⇒ 隐掉它自己的 `<h1>秒杀报名</h1>` 与「业务号 …」那半句
         （这里的栏题已经说过一次了）。`#/merchant/seckill` 深链仍走同一组件、走缺省值。 -->
    <h3 class="pact-sub">本店报名秒杀</h3>
    <SeckillApplyPage embedded />
  </section>
</template>

<script setup>
// src/components/PlatformActivityPanel.vue —— **店铺主页的「平台活动」面板**（C3 块 5 · 2026-10-12 用户裁定 ④）。
//
// 只在一处渲染：`views/ShopHome.vue` 的一级 tab `active` 格，且**只有店主看自己的店**时
// 那个 tab 才画（见那一页 `isSelf` 那段）。所以本组件拿到 `merchantId` 就是**自己的**业务号 ——
// 两个邀请端点都吃它、传别人的必 403（`IdentityGuard.requireSelfOrAdmin`）。
//
// ⚠️ **不新增端点**：四条通道都是现成的 ——
//   `POST /marketing/seckill/invitations`（`seckillInvitations`）
//   `POST /marketing/subsidy/invitations`（`subsidyInvitations`）
//   `POST /marketing/seckill/respond`（`seckillRespond`）
//   `POST /marketing/subsidy/confirm`（`subsidyConfirm`）
//   后两条与消息中心（`views/NotifyInbox.vue` 的 `submitDecision`）**调的是同一对端点**，
//   不是两条实现 —— 那边处理的是「通知」，这边处理的是「待办」，同一件事的两个视角。
//   故 `endpoints.js` 的条数不变，块内文档一律不动。
//
// ⚠️ **拒绝理由用 `window.prompt`**：后端对拒绝分支**收必填 `Reason`**（Service 手判），
//    而本仓房规是「不造控件」（同页 `ShoppingCart.vue` 用 `window.confirm` / `alert` 那条先例）。
//    空串 / 取消 ⇒ **直接返回、不发请求**（服务端必然拒，省一次往返）。
//
// ⚠️ **服务端才是判据**：能不能同意由后端定（秒杀那条还要过「时段重叠」两道闸）——
//    这里只把回体的 `msg` 原样说给用户听，不预判。
import { onMounted, ref } from 'vue'
import { subsidyInvitations, subsidyConfirm, seckillInvitations, seckillRespond } from '../api/marketing'
import SeckillApplyPage from '../views/merchant/SeckillApplyPage.vue'

// eslint-disable-next-line no-undef
const props = defineProps({
  /** 本店业务号（`goods.MerchantId` = `login.Id`）。传别人的 ⇒ 两个端点都回 403。 */
  merchantId: { type: String, default: '' }
})

const state = ref('loading')   // loading / ready / error
const err = ref('')
const todos = ref([])
const busy = ref(false)
const flash = ref({ text: '', kind: 'ok' })

const money = (v) => Number(v || 0).toFixed(2)
/** 补贴比例：库里存**小数**（`0.1500` = 15%），页面上按百分数读（同 `MarketingPage.fmtRatio`）。 */
const pct = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? `${Number((n * 100).toFixed(2))}%` : '—'
}

/**
 * 拉两路邀请、拼成一张待办表。
 *
 * ⚠️ **并发**（`Promise.all`）而不是串行：两路互不依赖，串行会把首屏等待翻倍。
 * ⚠️ 任一路失败 ⇒ **整块进 error 态**（而不是「显示另一路、假装这路没有」）——
 *    「查不到」与「没有待办」在画面上必须能分开，后者会让店主以为没事要办。
 */
const load = async () => {
  const mid = String(props.merchantId || '')
  if (!mid) { todos.value = []; state.value = 'ready'; return }
  state.value = 'loading'
  err.value = ''
  try {
    const [sek, sub] = await Promise.all([
      seckillInvitations(mid, { Status: 'invited', limit: 50 }),
      subsidyInvitations(mid, { Status: 'pending', OnlyOpen: 'true', limit: 50 })
    ])
    const out = []
    const sekList = sek && sek.success && Array.isArray(sek.list) ? sek.list : []
    for (const r of sekList) {
      // ⚠️ `Stage` 由后端在库里算（`SeckillDao.stage()`），这里只读结果、不做任何日期运算。
      if (String(r.Stage || '') !== 'signup') continue
      out.push({
        key: 'sek-' + r.Id, kind: 'sek', id: r.Id, from: '秒杀',
        goodName: r.GoodName || '（商品 #' + r.GoodsId + '）',
        cond: [
          `秒杀价 ¥${money(r.SeckillPrice)}`,
          `原价 ¥${money(r.OriginalPrice)}`,
          Number(r.Quantity) > 0 ? `限量 ${r.Quantity} 件` : '不限量'
        ]
      })
    }
    const subList = sub && sub.success && Array.isArray(sub.list) ? sub.list : []
    for (const r of subList) {
      out.push({
        key: 'sub-' + r.Id, kind: 'sub', id: r.Id, from: '国补',
        goodName: r.GoodName || '（商品 #' + r.GoodsId + '）',
        cond: [
          `${r.RuleName || '国补规则'} 补 ${pct(r.Ratio)}`,
          Number(r.Cap) > 0 ? `单笔封顶 ¥${money(r.Cap)}` : '不封顶',
          r.RuleEndAt ? `规则到 ${r.RuleEndAt}` : '规则不限时'
        ]
      })
    }
    todos.value = out
    state.value = 'ready'
  } catch (e) {
    console.warn('平台活动读取失败', e)
    err.value = '读取平台活动失败'
    state.value = 'error'
  }
}

/**
 * 同意 / 拒绝一条邀请。
 *
 * ⚠️ `data.outcome === 'already-handled'` **不是失败**（后端 CAS 落空、`success` 仍是 `true`）
 *    —— 那说明这行刚在别处处理过。照常报成功、重拉列表让它从表里消失。
 *    （同 `NotifyInbox.submitDecision` 那条注释；两处对同一个回体必须同一读法。）
 * ⚠️ 成功后**重拉**（与消息中心那边的「不重拉」有意不同）：这里表上那一行本来就该走了，
 *    留着它会让人以为还没答。
 */
const answer = async (t, ok) => {
  if (busy.value) return
  let reason = ''
  if (!ok) {
    reason = window.prompt('拒绝理由（必填）') || ''
    if (!reason.trim()) return
  }
  busy.value = true
  flash.value = { text: '', kind: 'ok' }
  try {
    const mid = String(props.merchantId || '')
    const data = t.kind === 'sek'
      ? await seckillRespond(mid, t.id, ok, reason)
      : await subsidyConfirm(mid, t.id, ok, reason)
    if (!data || !data.success) {
      flash.value = { text: (data && data.msg) || '操作失败', kind: 'bad' }
      return
    }
    flash.value = { text: ok ? '已同意' : '已拒绝', kind: 'ok' }
    await load()
  } catch (e) {
    console.warn('平台活动应答失败', e)
    flash.value = { text: '操作失败，请稍后重试', kind: 'bad' }
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 栏题形制照 `views/ShopHome.vue` 的 `.sec-h` / `.sec-note`（本组件的宿主页）——
   组件是 `scoped` 的，那边那两条规则**不流进来**，故按同一套值重写一遍，不引用不存在的类。 */
.pact-h { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med); line-height: 1.3; color: var(--ink); }
.pact-note { margin-left: var(--s2); font-style: normal; font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3); }
/* 二级栏题（待应答 / 本店报名秒杀）：比一级轻一档，比表头重一档。 */
.pact-sub { margin-top: var(--s6); font-size: var(--fs-md); font-weight: var(--fw-med); color: var(--ink); }

.pact-ph { margin-top: var(--s5); font-size: var(--fs-sm); color: var(--ink-3); }
.pact-ph-err { color: var(--danger); }
.pact-retry {
  margin-left: var(--s2); height: 32px; padding: 0 var(--s3);
  border: 1px solid var(--line-2); border-radius: var(--r1);
  background: none; color: var(--ink-2); font-size: var(--fs-xs); cursor: pointer;
}
.pact-retry:hover { border-color: var(--ink-3); color: var(--ink); }

/* ── 待办表：发丝线 + 无斑马（同本仓表格的既有形制）────────────────────────────
   ⚠️ 「条件」列是**若干段**（价 / 限量 / 截止），不是一句 `·` 拼起来的元信息串
      —— 段间靠 `gap` 分开、各段自带量词，这是本仓点名的 AI 默认样的反面。 */
.pact-table { margin-top: var(--s5); width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.pact-table th {
  padding: 0 0 var(--s3); border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3); text-align: left;
}
.pact-table td { padding: var(--s4) var(--s4) var(--s4) 0; border-bottom: 1px solid var(--line); vertical-align: middle; }
.pact-table td:last-child { padding-right: 0; }
.pact-table .num { text-align: right; }
.pact-table .nm { color: var(--ink); }
/* ⚠️ 段间用 `margin-right` 而**不是**给 `<td>` 加 `display:flex`：把表格单元格改成 flex 容器
   会让它**退出表格布局算法** ⇒ 列宽不再与表头对齐（是本仓 `.list-head, .row` 那套栅格刻意避开的坑）。
   段落本身 `display:inline-block`，各段自带量词、靠间距分开，不拼成一句。 */
.pact-table .cond { color: var(--ink-3); font-size: var(--fs-xs); }
.pact-it { display: inline-block; margin-right: var(--s4); white-space: nowrap; }
.pact-it:last-child { margin-right: 0; }
.pact-table .ops { white-space: nowrap; text-align: right; }

/* 来源签：两路各一枚色，与全站「国补 = 品牌绿 / 秒杀 = 朱砂」同一对应
   （同 `GoodsUp.vue` 的 `.kind.subsidy` / `.kind.seckill`）。 */
.pact-tag { display: inline-block; padding: 1px var(--s2); border-radius: var(--r1); font-size: var(--fs-xs); white-space: nowrap; }
.pact-tag-sek { background: var(--cin-100); color: var(--cin-700); }
.pact-tag-sub { background: var(--brand-tint); color: var(--brand-ink); }

.pact-btn {
  height: 32px; padding: 0 var(--s4); margin-left: var(--s2);
  border: 1px solid var(--line-2); border-radius: var(--r1);
  background: var(--surface); color: var(--ink-2); font-size: var(--fs-xs); cursor: pointer;
}
.pact-btn:hover:not([disabled]) { border-color: var(--brand); color: var(--brand-ink); }
/* 「拒绝」是**破坏性**的那一枚 ⇒ 悬停走功能色，与「同意」分开。 */
.pact-btn-no:hover:not([disabled]) { border-color: var(--accent); color: var(--accent); }
.pact-btn[disabled] { color: var(--ink-3); cursor: not-allowed; }

/* 结果条：1px 发丝线 + 明度台阶，不用阴影（同 `SeckillApplyPage` 的 `.skl-flash`）。 */
.pact-flash { margin-top: var(--s4); padding: var(--s3) var(--s4); border: 1px solid var(--line); border-radius: var(--r2); font-size: var(--fs-sm); background: var(--surface-2); color: var(--ink-2); }
.pact-flash.pact-ok { background: var(--brand-tint); border-color: var(--brand-soft); color: var(--brand-ink); }
.pact-flash.pact-bad { background: var(--danger-ground); border-color: var(--cin-200); color: var(--danger-deep); }

/* 窄屏：条件列允许折行、操作列不折（两枚按钮仍成对）。 */
@media (max-width: 900px) {
  .pact-table th:nth-child(3), .pact-table td:nth-child(3) { display: none; }
}
</style>
