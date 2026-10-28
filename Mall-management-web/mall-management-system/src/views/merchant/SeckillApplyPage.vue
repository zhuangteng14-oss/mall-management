<template>
  <main class="skl-page">
    <header class="skl-head">
      <div>
        <h1 v-if="!embedded" class="skl-h1">秒杀报名</h1>
        <p class="skl-sub">
          自己挑一件在架商品报名参加秒杀（<code>路径 B</code>：商家发起 → 管理端审核）。<template v-if="!embedded">业务号 <b class="skl-mid">{{ mid || '（未取到）' }}</b></template>
        </p>
      </div>
    </header>

    <p v-if="flash.text" class="skl-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── 报名表单 ──────────────────────────────────────────────────────────── -->
    <form class="skl-form" @submit.prevent="submit">
      <div class="skl-field">
        <span class="skl-lbl">商品</span>
        <select id="tour-seckill-select" v-model="goodsId" class="skl-input" :disabled="loadingGoods">
          <option value="">（请选择本店在架商品）</option>
          <option v-for="g in goodsList" :key="g.Id" :value="String(g.Id)">
            {{ g.GoodName }}（原价 ¥{{ money(g.Price) }}）
          </option>
        </select>
      </div>

      <div class="skl-field">
        <span class="skl-lbl">秒杀价</span>
        <input v-model.trim="price" class="skl-input" type="text" inputmode="decimal" placeholder="如 99.00" />
      </div>

      <div class="skl-field">
        <span class="skl-lbl">限量</span>
        <input v-model.trim="quantity" class="skl-input" type="text" inputmode="numeric" placeholder="如 20" />
      </div>

      <button id="tour-seckill-submit" class="skl-btn" type="submit" :disabled="submitting || !goodsId">
        {{ submitting ? '提交中…' : '提交报名' }}
      </button>

      <!-- ⚠️ 这一行是**提示**不是闸：真正的判据在服务端（`SeckillService.apply`）——
           原价取库里的值、`checkPrice` 判区间、`countPendingApplications` 判重复。
           前端只是把已知规则先说给商家听（用户 2026-10-12 口径：前端仅提示、后端拒）。 -->
      <p class="skl-hint" :class="{ bad: hintBad }">
        <template v-if="picked">
          原价 ¥{{ money(picked.Price) }} ⇒ 秒杀价须<b>大于 0 且低于原价</b>；限量须是<b>非负整数</b>。
        </template>
        <template v-else>先选一件商品，这里会显示它的原价与允许区间。</template>
        报名<b>不带时间</b>：开始 / 结束时间由管理端在审核通过时确认。
      </p>
    </form>

    <!-- ── 我的报名 ──────────────────────────────────────────────────────────── -->
    <h2 class="skl-h2">我的报名</h2>

    <p v-if="loadingList" class="skl-empty">正在读取…</p>
    <p v-else-if="!rows.length" class="skl-empty">
      还没提交过秒杀申请。选一件在架商品、填报价与限量，提交后等管理端审核。
    </p>

    <table v-else class="skl-table">
      <thead>
        <tr>
          <th>商品</th><th class="num">原价</th><th class="num">秒杀价</th>
          <th class="num">限量</th><th>状态</th><th>提交时间</th><th>备注</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="r.Id">
          <td class="nm">{{ r.GoodName || '（商品 #' + r.GoodsId + '）' }}</td>
          <td class="num">¥{{ money(r.OriginalPrice) }}</td>
          <td class="num strong">¥{{ money(r.SeckillPrice) }}</td>
          <td class="num">{{ r.Quantity }}</td>
          <td><span class="skl-st" :class="statusKind(r.Status)">{{ statusLabel(r.Status) }}</span></td>
          <td class="tm">{{ r.created_at || '—' }}</td>
          <td class="rs">{{ r.Reason || '—' }}</td>
        </tr>
      </tbody>
    </table>

    <!-- ⚠️ 「收到邀请」那条路（路径 A）**不在这里**：它走 `/marketing/seckill/respond`，
         入口在消息中心的通知里（`views/NotifyInbox.vue`）—— 同一个域、两个方向，别并成一处。 -->
  </main>
</template>

<script setup>
// src/views/merchant/SeckillApplyPage.vue —— **商家主动报名秒杀**（C3 块 5 · commit 4 补笔，2026-10-12）。
//
// ⚠️ 为什么有这个页面：`/marketing/seckill/apply`（`SeckillController.java:143`）与它的前端封装
//    `seckillApply()`（`api/marketing.js:488`）在 commit 4 就写好了，但**一个调用方都没有** ——
//    商家侧当时唯一接通的是消息中心里的「应答邀请」（`NotifyInbox.vue:363`，路径 A）。
//    本页补的是路径 B 的**发起端**：让商家能自己挑商品报名。
//
// ⚠️ 本页**不新增端点、不新增封装、不动后端** —— 三条现成通道：
//    · `POST /goods/goodsSelect`（`{MerchantId}` ⇒ 本店在架商品，含 `Id` / `GoodName` / `Price`；
//      调用写法照 `GoodsUp.vue:416` 那一条）
//    · `POST /marketing/seckill/apply`（`seckillApply`）
//    · `POST /marketing/seckill/applications`（`seckillApplications`）
//    故 `endpoints.js` 的条数**不变**（仍是 119 条），块内文档一律不动。
//
// ⚠️ 服务端才是判据（`SeckillService.apply:265-314`，逐条对齐本页的提示文案）：
//    缺参 / 商品不存在或已删除 / 不属于当前商家 / 原价异常 / 价格区间（`checkPrice`）/
//    限量非负整数（`!Number.isInteger(qty)` 也算拒；**2026-10-12 订正**：原文写的是 `Math.rint`，那是 Java 的）/ **同一商品已有待审申请**（`countPendingApplications`）。
//    前端只提示、不拦 —— 与折扣那条（前端仅提示、后端拒）同一口径。
//
// ⚠️ 挂载位置：`router/index.js` 文件尾 `addRoute('merchantUser', { path: '/merchant/seckill', … })`
//    ⇒ 渲染在**个人中心壳**右栏（同 `StatsPage.vue`）⇒ 外壳三件交还 `.user-page`，本页只管 `flex:1`。
import { computed, onMounted, ref, watch } from 'vue'
import { http } from '../../api/http'
import { endpoints } from '../../api/endpoints'
import { getMerchantId } from '../../api/auth'
import { seckillApply, seckillApplications } from '../../api/marketing'; import { resumeTourFlow } from '../../composables/useTour'

/**
 * `embedded` —— 本页被**当子组件**用时的开关（C3 块 5 · 2026-10-12 用户裁定 ④）。
 *
 * 消费方只有一处：`components/PlatformActivityPanel.vue`（店铺主页的「平台活动」面板，
 * 见 `views/ShopHome.vue`）—— 那种位置已经有自己的栏题与上下文，页内再出现一个
 * `<h1>秒杀报名</h1>` 就是同一句话写两遍。
 *
 * ⚠️ **只隐两处**：`<h1>` 与「业务号 …」那半句（那个号在店主自己的店里是已知量，
 *    说给自己听）。`路径 B` 那句说明**留着** —— 它讲的是这个表单在做什么，嵌到哪都需要。
 * ⚠️ 缺省 `false` ⇒ 走 `#/merchant/seckill` 深链时与加这个 prop 之前**逐字同貌**。
 * ⚠️ 不写成 `const props = …`：本文件只在模板里用它（模板能直接认 `defineProps` 的键名），
 *    赋了值而脚本里不引用会吃 `no-unused-vars`。
 */
// eslint-disable-next-line no-undef
defineProps({ embedded: { type: Boolean, default: false } })

const mid = ref(getMerchantId() || '')

const goodsList = ref([])
const rows = ref([])
const loadingGoods = ref(false)
const loadingList = ref(false)
const submitting = ref(false)

const goodsId = ref('')
const price = ref('')
const quantity = ref('')

const flash = ref({ text: '', kind: 'ok' })
const say = (text, kind) => { flash.value = { text, kind: kind || 'ok' } }

/** 选中的那件商品（下列表里按 `Id` 找）—— `Price` 就是后端会拿去比的原价。 */
const picked = computed(() => goodsList.value.find((g) => String(g.Id) === String(goodsId.value)) || null)

/** 提示行**只说已知的两条**（区间 / 整数），不预判「已有待审申请」—— 那条只有服务端知道。 */
const hintBad = computed(() => {
  const p = picked.value
  if (!p) return false
  const orig = Number(p.Price)
  const v = Number(price.value)
  const q = Number(quantity.value)
  const priceBad = price.value !== '' && (!isFinite(v) || v <= 0 || (isFinite(orig) && orig > 0 && v >= orig))
  const qtyBad = quantity.value !== '' && (!isFinite(q) || q < 0 || !Number.isInteger(q))   // ⚠️ 原写 `q !== Math.rint(q)` —— **`Math.rint` 是 Java 的、JS 没有**（同批误用，见 `ProductPage.vue:699`；① 甲案 −16 行后）：`isFinite(q) || q < 0` 两个短路支**都是假**时才轮到它 ⇒ **商家一填限量就 `TypeError`**，红字提示整条渲染中断。2026-10-12 同行修正，判据 `Number.isInteger`（原意就是「q 不是整数」）。
  return priceBad || qtyBad
})

const money = (v) => {
  const n = Number(v)
  return isFinite(n) ? n.toFixed(2) : '—'
}

const STATUS = {
  submitted: { label: '待审核', kind: 'wait' },
  approved: { label: '已通过', kind: 'ok' },
  rejected: { label: '已驳回', kind: 'no' }
}
const statusLabel = (s) => (STATUS[s] ? STATUS[s].label : (s || '—'))
const statusKind = (s) => (STATUS[s] ? STATUS[s].kind : 'wait')

/** 本店在架商品（`/goods/goodsSelect` 只回 `ShelfState = 1 AND DeletedAt IS NULL` 的行）。 */
const loadGoods = async () => {
  loadingGoods.value = true
  try {
    const res = await http.post(endpoints.goods.goodsSelect, new URLSearchParams({ MerchantId: mid.value }))
    const data = await res.json()
    goodsList.value = data.success ? (data.list || []) : []
    if (!data.success) say(data.msg || '本店商品读取失败', 'bad')
  } catch (e) {
    goodsList.value = []
    say('本店商品读取失败：' + (e && e.message ? e.message : e), 'bad')
  } finally {
    loadingGoods.value = false
  }
}

const loadList = async () => {
  loadingList.value = true
  try {
    const d = await seckillApplications(mid.value, { limit: 20 })
    rows.value = d && d.success ? (d.list || []) : []
  } catch (e) {
    rows.value = []
    say('报名记录读取失败：' + (e && e.message ? e.message : e), 'bad')
  } finally {
    loadingList.value = false
  }
}

/** 选商品 ⇒ 把秒杀价预填成八折（**只是草稿**，商家可以改；后端仍按自己的区间判）。 */
const preset = () => {
  const p = picked.value
  const orig = p ? Number(p.Price) : NaN
  price.value = isFinite(orig) && orig > 0 ? (Math.round(orig * 0.8 * 100) / 100).toFixed(2) : ''
  quantity.value = ''
}
// ⚠️ 用 `watch` 而不是 `@change`：`@change` 在「选回同一项」时不触发，而 `v-model` 每次赋值都触发。
//    `immediate` 不需要 —— 初始 `goodsId` 是空串，没有可预填的原价。
watch(goodsId, preset)

const submit = async () => {
  submitting.value = true
  say('')
  try {
    const d = await seckillApply(mid.value, Number(goodsId.value), price.value, quantity.value)
    if (d && d.success) {
      say(d.msg || '已提交秒杀申请，等待管理端审核', 'ok')
      goodsId.value = ''
      price.value = ''
      quantity.value = ''
      await loadList()
    } else {
      say((d && d.msg) || '报名失败', 'bad')
    }
  } catch (e) {
    say('报名失败：' + (e && e.message ? e.message : e), 'bad')
  } finally {
    submitting.value = false
  }
}

onMounted(() => { loadGoods(); loadList(); resumeTourFlow() })
</script>

<style scoped>
/* ⚠️ 本页渲染在个人中心壳（`AppShell.vue` 的 `.user-page` 那个 flex 行）的右栏槽位里
   —— 同槽位先例 = `views/merchant/StatsPage.vue` 的 `.ms-page`。故：① 本元素是 flex 行
   的直接子项 ⇒ `flex:1; min-width:0`（`min-width:0` 防宽表把右栏顶宽）；② 外层 chrome
   （`max-width` / `margin:0 auto` / 内边距）交还 `.user-page`，本页不重复一套。 */
.skl-page {
  flex: 1;
  min-width: 0;
}

.skl-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s4); flex-wrap: wrap; }
.skl-h1 { font-size: var(--fs-xl); font-weight: var(--fw-med); letter-spacing: -.01em; color: var(--ink); }
.skl-sub { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); line-height: 1.6; max-width: 62ch; }
.skl-sub code { font-family: var(--font-mono); background: var(--surface-2); padding: 1px var(--s1); border-radius: var(--r1); }
.skl-mid { color: var(--ink-2); font-weight: var(--fw-med); }

/* 提示条：成功用品牌色、失败用功能色 —— 不用阴影，靠 1px 发丝线与底色的明度台阶。 */
.skl-flash {
  margin-top: var(--s4); padding: var(--s3) var(--s4);
  font-size: var(--fs-sm); border: 1px solid var(--line); border-radius: var(--r2);
  background: var(--surface-2); color: var(--ink-2);
}
.skl-flash.ok { background: var(--brand-tint); border-color: var(--brand-soft); color: var(--brand-ink); }
.skl-flash.bad { background: var(--danger-ground); border-color: var(--cin-200); color: var(--danger-deep); }

/* 表单：一行铺开（宽屏）/ 自动换行（窄屏），单选一列 —— 表单只有一层，不套卡。 */
.skl-form {
  margin-top: var(--s5); padding: var(--s5);
  display: flex; flex-wrap: wrap; align-items: flex-end; gap: var(--s4);
  border: 1px solid var(--line); border-radius: var(--r2); background: var(--surface);
}
.skl-field { display: flex; flex-direction: column; gap: var(--s2); min-width: 0; }
.skl-field:nth-child(1) { flex: 2 1 320px; }
.skl-field:nth-child(2), .skl-field:nth-child(3) { flex: 1 1 150px; }
.skl-lbl { font-size: var(--fs-xs); font-weight: var(--fw-semi); color: var(--ink-3); }
.skl-input {
  height: 38px; padding: 0 var(--s3); font-family: var(--font); font-size: var(--fs-sm); color: var(--ink);
  background: var(--bg); border: 1px solid var(--line-2); border-radius: var(--r1);
}
.skl-input:focus { outline: none; border-color: var(--brand); }
.skl-input:disabled { background: var(--surface-2); color: var(--ink-3); }
.skl-btn {
  height: 38px; padding: 0 var(--s5); font-family: var(--font); font-size: var(--fs-sm); font-weight: var(--fw-med);
  color: var(--bg); background: var(--brand); border: 1px solid var(--brand); border-radius: var(--r1); cursor: pointer;
}
.skl-btn:hover:not(:disabled) { background: var(--brand-hover); border-color: var(--brand-hover); }
.skl-btn:disabled { background: var(--surface-3); border-color: var(--line); color: var(--ink-3); cursor: not-allowed; }

/* ⚠️ 提示行占满整行（`flex-basis:100%`）⇒ 它落在按钮**下一行**，不把按钮挤歪。 */
.skl-hint {
  flex: 0 0 100%; margin-top: var(--s1);
  font-size: var(--fs-xs); line-height: 1.7; color: var(--ink-3);
}
.skl-hint b { color: var(--ink-2); font-weight: var(--fw-semi); }
.skl-hint.bad, .skl-hint.bad b { color: var(--accent); }

.skl-h2 { margin-top: var(--s7); font-size: var(--fs-md); font-weight: var(--fw-med); color: var(--ink); }
.skl-empty { margin-top: var(--s4); font-size: var(--fs-sm); color: var(--ink-3); line-height: 1.7; }

.skl-table { margin-top: var(--s4); width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.skl-table th {
  text-align: left; padding: var(--s2) var(--s3);
  font-size: var(--fs-xs); font-weight: var(--fw-semi); color: var(--ink-3);
  border-bottom: 1px solid var(--line);
}
.skl-table td { padding: var(--s3); color: var(--ink-2); border-bottom: 1px solid var(--line); }
/* 数字列一律等宽数字 —— 价格与限量纵向对得齐（token 用法规矩那条）。 */
.skl-table .num { text-align: right; font-variant-numeric: tabular-nums; }
.skl-table .strong { color: var(--accent); font-weight: var(--fw-med); }
.skl-table .nm { color: var(--ink); }
.skl-table .tm, .skl-table .rs { font-size: var(--fs-xs); color: var(--ink-3); }

.skl-st { font-size: var(--fs-xs); font-weight: var(--fw-med); padding: 1px var(--s2); border-radius: var(--r1); }
.skl-st.wait { color: var(--warning-deep); background: var(--warning-ground); }
.skl-st.ok { color: var(--success-deep); background: var(--success-ground); }
.skl-st.no { color: var(--danger-deep); background: var(--danger-ground); }
</style>
