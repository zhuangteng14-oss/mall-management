<template>
  <!--
    D 笔 commit 8 —— 本页承载老稿 `子页面改造.html` 的 `data-page="ho"`（历史订单）那一支。
    基线：`docs/美化/子页面改造.html`
        · 模板 `:615-638`（`.page[data-page=ho]`）
        · 样式 `:293-319`（`.ords` / `.c-*` / `.state`）+ 全局件 `:57`（`.num`）`:159-165`（`.ph`）`:179-184`（`.btn-text`）
    ⚠️ 基线口径 v2（`slot-registry.md` 口径 6）：`pages/` 里**没有**历史订单的新稿 ⇒ **回到老稿**；
       本段 `:615-638` **有效**（`.phs` 同段的 `:538/:560/:593` 属 UserInfoPage 面，已弃用、不在本笔）。
    ⚠️ 稿的 `.uc` / `.uc-menu` / `.stage` 三件**不进本组件** —— 它们的落点是 `UserCenter.vue`
       （左菜单）与 `AppShell.vue` 的 `.user-page`（那一行的 padding / max-width）。
       本页只承载稿里的 `<main class="uc-main">` 右栏那一支（与 `UserInfoPage.vue` 同槽位）。
    ⚠️ `doc 稿自身的两处「表里不一」，照稿落、不改稿`：
       ① `:633` 表头列 `class="c-act"` 挂的 data-slot 是「**申请退货**」，而表体 `:754` 同一列
          渲染的是「**复制商家 ID**」按钮 —— 表头说退货、表体说复制。**照稿**：表头落退货预留位，
          表体现有实现（复制商家 ID）原样保留。
       ② `:630` 的状态列：稿注 `:627-629` 自写「`historicalOrdersSelect` 目前只回已结单的 ⇒
          这一列现在恒为同一个值」 ⇒ 表体那句 `已经结单` 是**照既有实现**（原 Vue `:11` 同款，只是
          原先是压在图片上的浮层，现按稿移进状态列）。
  -->
  <main class="uc-main">

    <div class="page-h">
      <h1 class="page-h-t">历史订单</h1>
      <!-- 稿 `:618` 的 `#ho-n` 由脚本填 `共 ${ORDERS.length} 笔` —— 这里落**真**条数（`orderList.length`）。
           ⚠️ 加载中挂 `v-if="!loading"`：否则会先显示「共 0 笔」这个**假陈述**（稿无此态，属落地补偿）。 -->
      <span v-if="!loading" class="page-h-n">共 {{ orderList.length }} 笔</span>
    </div>

    <div v-if="loading" class="empty">加载中...</div>
    <div v-else-if="orderList.length === 0" class="empty">暂无历史订单</div>

    <table v-else class="ords">
      <thead>
        <tr>
          <th class="c-prod">商品</th>
          <th class="c-num">旧订单号</th>
          <th class="c-nd">商家 ID</th>
          <th class="c-date">结单日期</th>
          <!-- 预留位 #25（老稿 `:630`）—— 块级：`<th>` 本身不是控件 ⇒ 只挂 `data-slot` + `title`（无 `disabled`）。
               N 稿未标（未计）。 -->
          <th
            class="c-st"
            data-slot="预留 · 状态枚举"
            title="预留 · 状态枚举（老稿 子页面改造.html:630）· 归属 C3〈订单〉——稿注：数据字典 6(3) 把订单状态定成枚举「待付款 / 已付款 / 已完成」，但 historicalOrdersSelect 目前只回已结单的 ⇒ 这一列现恒为同一个值；接口一旦回多状态，这一列直接生效，不用改布局"
          >状态</th>
          <!-- 老稿 `:633` 的「申请退货」预留位已在批四第二批 commit 4 落地（售后状态机）：
               表头从空格子填成「售后 / 操作」，表体同列的「复制商家 ID」原样保留（稿自身表里不一，见文件头 ①），
               新动作与它上下叠放。 -->
          <th class="c-act">售后</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="order in orderList" :key="order.OrderId">
          <td class="c-prod">
            <div class="in">
              <!-- 稿 `:749` 的 `.thumb` 是灰色占位方块（`background:var(--img)`）——那是它的 mock。
                   本页保留**既有真图**（原 Vue `:9` 的 `<img :src="order.Images">`，取值路径一字未改）
                   ⇒ 尺寸从「撑满 45% 宽 × 200px 高」收到稿的 48×48。
                   ⚠️ 补 `v-if="order.Images"`（**原实现没有这一条**）：`Images` 为空时 `<img src="">`
                   画出来的是浏览器的**破图图标**，而稿的 `.thumb` 无图就露灰底 ⇒ 加 `v-if` 正好收回
                   稿的样子。与 `OrderCenter.vue:119` 的 `<img v-if="order.Images">` 是**同一行写法**
                   （commit 5 先例），非新发明。 -->
              <div class="thumb"><img v-if="order.Images" :src="order.Images" alt="商品图片" /></div>
              <span class="nm">{{ order.ProductInformation }}</span>
            </div>
          </td>
          <td class="c-num num">{{ order.OrderId }}</td>
          <td class="c-nd num">{{ order.MerchantId }}</td>
          <td class="c-date num">{{ formatDateTime(order.SettlementDate) }}</td>
          <td class="c-st">
            <span class="state" :class="`s-${Number(order.RefundStatus) || 0}`">
              <IconGlyph v-if="(Number(order.RefundStatus) || 0) === 0" name="ok" />{{ refundStatusText(order.RefundStatus) }}
            </span>
          </td>
          <!-- 稿 `:754` 是 `<button class="btn-text">复制商家 ID</button>`（稿外死控件：裸控件、无 data-slot）。
               ⚠️ **本页不是死控件** —— 原 Vue `:20` 的「复制」已是**真事件**（`copyMerchantId`）⇒ 保留实事件，
               **不挂 `disabled`、不进 §三**；文案照稿由「复制」改为「复制商家 ID」。
               售后按钮（批四第二批 commit 4）：`RefundStatus` 0 首申 / 2 重申 / 1·3·4·5 只读文案。 -->
          <td class="c-act">
            <div class="acts">
              <button v-if="canApply(order)" class="btn-text act-refund" @click="openRefund(order)">
                {{ (Number(order.RefundStatus) || 0) === 2 ? '再次申请' : '申请退货' }}
              </button>
              <span v-else-if="(Number(order.RefundStatus) || 0) === 1" class="act-hint">待商家审核</span>
              <span v-else-if="(Number(order.RefundStatus) || 0) === 3" class="act-hint">平台处理中</span>
              <span v-else-if="(Number(order.RefundStatus) || 0) === 4" class="act-hint">平台已拒绝</span>
              <span v-else-if="(Number(order.RefundStatus) || 0) === 5" class="act-hint">已退款</span>
              <button class="btn-text" @click="copyMerchantId(order.MerchantId)">复制商家 ID</button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 售后申请弹窗（批四第二批 commit 4）。金额**只读展示**（后端取 order.Price 快照），
         前端不传金额 —— 见 api/order.js ⑤ 组头注释。 -->
    <div v-if="refundDialog.open" class="rf-mask" @click.self="closeRefund">
      <div class="rf-box">
        <h2 class="rf-title">申请退货</h2>
        <p class="rf-sub">
          订单 <span class="num">{{ refundDialog.order && refundDialog.order.OrderId }}</span>
          · 可退金额 ¥{{ Number((refundDialog.order && refundDialog.order.Price) || 0).toFixed(2) }}
        </p>
        <textarea
          v-model="refundDialog.reason"
          class="rf-input"
          rows="4"
          maxlength="255"
          placeholder="请填写售后原因（必填，最多 255 字）"
        ></textarea>
        <p v-if="refundDialog.error" class="rf-err">{{ refundDialog.error }}</p>
        <div class="rf-actions">
          <button class="btn-text" :disabled="refundDialog.busy" @click="closeRefund">取消</button>
          <button class="rf-submit" :disabled="refundDialog.busy" @click="submitRefund">
            {{ refundDialog.busy ? '提交中…' : '提交申请' }}
          </button>
        </div>
      </div>
    </div>

  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { orderRefundApply } from '../api/order'
import { resolveBuyerId } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移

const orderList = ref([])
const loading = ref(false)

// 降级链已收敛到 src/api/auth.js:resolveBuyerId()（与 ShoppingCart 逐字相同）。
const getBuyerId = () => resolveBuyerId()

const formatDateTime = (dateStr) => {   // ⚠️ **2026-10-10（问题 2，即时区：用户报「轨迹 22:27:51 vs 订单列表 14:29:16」差 8 小时）**：后端时间列是**库时钟**、库时钟是 **UTC**（VM / a3 实测 `@@system_time_zone=UTC`，见 `context-packs/c3-block-1-infra.md#pack-c3-block-1-infra-frontend-nav`）⇒ 交回 `'YYYY-MM-DD HH:mm:ss'` **无时区文本**；少补末尾那个 `Z`，`new Date()` 按**浏览器本地时区**解释它 ⇒ 整整差一个偏移且**错得很安静**。手法照 `views/admin/AuditPage.vue:236` 的 `toLocal`（原 190 行 —— 2026-10-11 收口按 refs-scan 重指；本页此前漏了）。
  if (!dateStr) return '未知'
  const date = new Date(`${String(dateStr).replace(' ', 'T')}Z`)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 复制回退方案
const fallbackCopy = (text) => {
  const textarea = document.createElement('textarea')
  textarea.value = text
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
  alert('复制成功：' + text)
}

const copyMerchantId = (merchantId) => {
  if (!merchantId) {
    alert('商家ID为空')
    return
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(merchantId).then(() => {
      alert('复制成功：' + merchantId)
    }).catch(() => {
      fallbackCopy(merchantId)
    })
  } else {
    fallbackCopy(merchantId)
  }
}

// ── 售后状态机（批四第二批 commit 4）────────────────────────────────────────────
// 文案与后端六态一一对应：0 无 / 1 待商家 / 2 商家拒（可重申）/ 3 待平台 / 4 平台拒（终态）/ 5 已退款。
const REFUND_TEXT = { 0: '已经结单', 1: '待商家审核', 2: '商家已拒绝', 3: '平台处理中', 4: '平台已拒绝', 5: '已退款' }
const refundStatusText = (s) => REFUND_TEXT[Number(s) || 0] || '已经结单'
// 可申请：0 首申 / 2 重申；且次数未用尽（上限 2）。其余状态后端也会拒，这里只是不展示按钮。
const canApply = (order) => {
  const status = Number(order.RefundStatus) || 0
  const retry = Number(order.RefundRetryCount) || 0
  return (status === 0 || status === 2) && retry < 2
}

const refundDialog = ref({ open: false, order: null, reason: '', busy: false, error: '' })

const openRefund = (order) => {
  refundDialog.value = { open: true, order, reason: '', busy: false, error: '' }
}
const closeRefund = () => {
  refundDialog.value.open = false
}
const submitRefund = async () => {
  const d = refundDialog.value
  const reason = String(d.reason || '').trim()
  if (!reason) {
    d.error = '请填写售后原因'
    return
  }
  const customerId = getBuyerId()
  if (!customerId) {
    d.error = '未登录，无法申请售后'
    return
  }
  d.busy = true
  d.error = ''
  try {
    const res = await orderRefundApply({ CustomerId: customerId, OrderId: d.order.OrderId, Reason: reason })
    if (res.success) {
      d.open = false
      alert(res.msg || '售后申请已提交')
      await loadHistoricalOrders()
    } else {
      // 非本人 / 未收货 / 次数上限 / 已退款：后端文案直接展示，不合并成一句「失败」
      d.error = res.msg || '提交失败'
    }
  } catch (err) {
    handleApiError(err, '提交失败')
  } finally {
    d.busy = false
  }
}

const loadHistoricalOrders = async () => {
  const customerId = getBuyerId()
  if (!customerId) {
    console.warn('未登录，无法获取历史订单')
    return
  }
  loading.value = true
  try {
    const params = new URLSearchParams()
    params.append('customerId', customerId)
    const res = await http.post(endpoints.order.historicalOrdersSelect, params.toString())
    const data = await res.json()
    if (data.success) {
      orderList.value = data.list
    } else {
      console.error('获取历史订单失败', data.msg)
    }
  } catch (err) {
    handleApiError(err, '请求出错')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadHistoricalOrders()
})
</script>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════════════
   取自 `docs/美化/子页面改造.html`：
     `:293-319`（`.ords` / `.c-prod|.c-num|.c-nd|.c-date|.c-st|.c-act` / `.thumb` / `.state`）
     `:57`（`.num`）`:159-165`（`.ph`）`:179-184`（`.btn-text`）`:281-287`（`.inp`，仅取 `--r2` 一件）
     `:430-436`（`.empty`，用于加载 / 空态两处）

   本次「从稿到 Vue」的**适配**，逐条登记：
     ① 稿的全局重设 `body{}` / `a{}` / `button{}` / `input,textarea{}`（稿 `:118-122`）里的
        `button{font:inherit;color:inherit;background:none;border:0;cursor:pointer}`
        **必须搬进 scoped 且限定本页** —— 稿的「复制商家 ID」是裸 `<button>`，不重设就会带上
        UA 的 `background: rgb(240,240,240)` / `border: 2px outset` / `font-family: Arial`
        / `font-size: 13.33px`（`UserInfoPage.vue` 的 commit 7 实踩过同一个坑）。
     ② 稿的 `body{font-family:var(--font);font-size:var(--fs-sm);color:var(--ink)}` 落到本页根
        `.uc-main` 上；`background` **不搬** —— 壳 `.app-shell` 已经铺了 `var(--bg)`，
        在 `main.uc-main` 上再铺一次会把右栏画成一块与左菜单不同的底（同 `UserInfoPage.vue`）。
     ③ 页头用具名类 `.page-h` / `.page-h-t` / `.page-h-n`，**不是**稿的 `.ph` / `.ph h1` / `.ph-n`：
        `UserInfoPage.vue` 已用 `.page-h` 占住同一个视觉位（壳内右栏的页标题）⇒ 同壳三页沿用
        一套类名，便于横向对读。⚠️ **字号这一条跨页不同，照实记**：本页取稿 `:162` 的
        `var(--fs-xl)`，但**同一个 token 在两侧的值不一样** —— 稿自己的 `:root`（`:14-15`）是
        **子页面档**（`--fs-sm/md/lg/xl` = 14/16/20/**28**），本仓 `tokens.css:12` 的「字号档冲突」
        条**统一采了主页档**（15/19/24/**32**）⇒ 落到本页标题是 **32px**，比稿的 28px 大 4px。
        `UserInfoPage.vue` 那页的标题是 `var(--d2)` = `--fs-2xl` = **36px** ⇒ **同壳两页标题差 4px**。
        这是「两份基线 + 一次字号统一」的必然产物，本笔不擅自拉平，已登记
        `slot-registry.md` §一 commit 8 的「⚠️ 跨页标题字号 36 vs 32」条，待用户裁。
     ④ 稿的 `.uc` / `.uc-menu` / `.stage` **不进本组件**（落点在 `UserCenter.vue` / `AppShell.vue`）⇒ 未搬。
     ⑤ 稿 `.num{font-variant-numeric:tabular-nums}` 是 `.ords` 三个号码列要的那一件 ⇒ 平铺成本页 `.num`。
        ⚠️ 只取这一条，稿 `:56` 的「不上 monospace 字体」是**设计约束注释**，不是声明。
     ⑥ `.thumb` 的 `background: var(--img)` 保留（无图时的兜底底色），并补 `img{object-fit:cover}`。
   ⚠️ `box-sizing` **不在此重复** —— `tokens.css` 的 `*` 重设已是全局。
   ══════════════════════════════════════════════════════════════════════════════ */

/* ── 稿 `:121` 的 `button{...}` 重设，限定本页 ───────────────────────────────── */
button {
  font: inherit;
  color: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}

/* ── 本页根 ────────────────────────────────────────────────────────────────── */
.uc-main {
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头（稿 `.ph` `:159-165`）────────────────────────────────────────────── */
.page-h { display: flex; align-items: baseline; gap: var(--s4); margin-bottom: var(--s5); }
.page-h-t {
  font-family: var(--font-display);
  font-size: var(--fs-xl); font-weight: var(--fw-semi); letter-spacing: .5px; line-height: 1.2;
}
.page-h-n { font-size: var(--fs-sm); color: var(--ink-3); }

/* ── 表格（稿 `:298-319`）──────────────────────────────────────────────────── */
.ords { width: 100%; border-collapse: collapse; }
.ords th {
  text-align: left; font-weight: var(--fw-reg); font-size: var(--fs-xs); color: var(--ink-3);
  padding: 0 var(--s4) 10px 0; border-bottom: 1px solid var(--line-2); white-space: nowrap;
}
.ords td {
  padding: 16px var(--s4) 16px 0; border-bottom: 1px solid var(--line);   /* ⚠️ 第 4 条修复（用户 2026-10-08）：12px → 16px。48×48 的缩略图夹在 12px 的行内距里，**上下留白只有 12.5px**，hover 时行底色（`--zone`）的上下边看起来正压在图片边上 = 用户报的「边界与背景行重叠」。加到 16px ⇒ 留白 16.5px，图与底色边不再贴着。 */
  vertical-align: middle; font-size: var(--fs-sm); color: var(--ink-2);
}
.ords th:last-child, .ords td:last-child { padding-right: var(--s4); }   /* ⚠️ 第 4 条修复：原是 `0` ⇒ 末列（`.c-act`，右对齐的「复制商家 ID」）的右边缘**与表格右边缘重合**（实测 btn 右沿 1266 = 表格右沿 1266），看起来像被裁掉一截。给回一个 `--s4`，按钮离表格右边 16px；行底色仍然铺满整格 ⇒ hover 条不会被切。 */
/* 行高亮是**扫读**需要，跟「每张卡片都加 hover 位移」不是一回事：只换底色，
   不做过渡、不做位移、不加投影（稿 `:308-309` 原注）。 */
.ords tbody tr:hover td { background: var(--zone); }

.c-prod { width: 30%; } .c-num { width: 15%; } .c-nd { width: 14%; }
.c-date { width: 19%; } .c-st { width: 11%; } .c-act { width: 11%; text-align: right; white-space: nowrap; }   /* ⚠️ 第 4 条修复：补 `white-space:nowrap` —— 「复制商家 ID」是一枚**不可断**的按钮文字，列宽是百分比（11%），窄档下会折成两行、把行顶高，与本列「一个动作一行」的语义不符。nowrap 让它只可能横向顶，绝不折行。 */

.c-prod .in { display: flex; align-items: center; gap: var(--s3); min-width: 0; }   /* ⚠️ 第 4 条修复：`min-width:0` 解开 flex 项的自动最小尺寸 —— 没有它，商品名一长，「缩略图 + 文字」这一组就**顶不出** td 的百分比宽，横向溢出到相邻列。 */
.thumb {
  width: 48px; height: 48px; flex: none; border-radius: var(--r1);
  background: var(--img); overflow: hidden;
}
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.c-prod .nm { font-size: var(--fs-md); color: var(--ink); font-weight: var(--fw-med); line-height: 1.35; min-width: 0; overflow-wrap: anywhere; }   /* ⚠️ 第 4 条修复（overflow 约束）：`min-width:0` 允许本项被压到列宽以下，`overflow-wrap:anywhere` 让**超长且不可断**的商品名（如测试数据里的 `叠法双方:dfffff`）在列内就地折行，而不是横向顶出去。正常长度的名字**外观零变化**。 */

.num { font-variant-numeric: tabular-nums; }

.state {
  display: inline-block; font-size: var(--fs-xs); color: var(--ink-3);
  border: 1px solid var(--line-2); border-radius: var(--r1); padding: 1px 8px;
}

/* ── 文字钮（稿 `.btn-text` `:179-184`）—— 只给行内动作 ────────────────────── */
.btn-text {
  font-size: var(--fs-xs); color: var(--ink-3);
  padding: 2px 0; border-bottom: 1px solid var(--line-2);
  transition: color .14s, border-color .14s;
}
.btn-text:hover { color: var(--brand-ink); border-color: var(--brand); }

/* ── 售后（批四第二批 commit 4）────────────────────────────────────────────── */
/* 状态 chip 的六态配色：0/5 与「完结」同色系；1/3 用品牌色（有进行中的事）；2/4 中性灰（已否定）。 */
.state.s-1, .state.s-3 { color: var(--brand-ink); border-color: var(--brand); }
.state.s-5 { color: var(--brand-ink); border-color: var(--brand); background: var(--zone); }
.c-act .acts { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.act-refund { color: var(--brand-ink); border-color: var(--brand); }
.act-hint { font-size: var(--fs-xs); color: var(--ink-3); }

.rf-mask {
  position: fixed; inset: 0; background: rgba(0, 0, 0, .32);
  display: flex; align-items: center; justify-content: center; z-index: 60;
}
.rf-box {
  width: min(440px, calc(100vw - 32px)); background: var(--bg);
  border-radius: var(--r2); padding: var(--s6); box-shadow: 0 12px 40px rgba(0, 0, 0, .18);
}
.rf-title { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-semi); margin-bottom: var(--s3); }
.rf-sub { font-size: var(--fs-sm); color: var(--ink-3); margin-bottom: var(--s4); }
.rf-input {
  width: 100%; resize: vertical; padding: var(--s3);
  border: 1px solid var(--line-2); border-radius: var(--r1);
  font: inherit; color: var(--ink); background: var(--bg);
}
.rf-input:focus { outline: none; border-color: var(--brand); }
.rf-err { margin-top: var(--s2); font-size: var(--fs-xs); color: #c0392b; }
.rf-actions { display: flex; justify-content: flex-end; align-items: center; gap: var(--s4); margin-top: var(--s4); }
.rf-submit {
  font-size: var(--fs-sm); color: #fff; background: var(--brand);
  border-radius: var(--r1); padding: 7px 16px; border: 1px solid var(--brand);
}
.rf-submit:disabled, .btn-text:disabled { opacity: .55; cursor: default; }

/* ── 加载 / 空态（稿 `.empty` `:432`；稿的 `ho` 面无空态 ⇒ 文案照既有实现保留）──── */
.empty {
  background: var(--zone); border-radius: var(--r2);
  padding: var(--s8) var(--s6); text-align: center;
  font-size: var(--fs-sm); color: var(--ink-3);
}

/* ── 窄档：稿 `:438-454` 的 `@media (max-width:1080px)` 只改 `.uc` 栅格与 `.uc-menu`
   （两者都不在本组件）⇒ 本页无对应声明。表格列宽是百分比，随右栏自适应。 ───────── */
</style>
