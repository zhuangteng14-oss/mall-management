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
          <!-- 预留位 #26（老稿 `:633`）—— 稿里这格**完全空**（无表头文字）⇒ 照落空格子 + `data-slot` + `title`。
               ⚠️ 表体同列是「复制商家 ID」（稿自身表里不一，见文件头 ①）。N 稿未标（未计）。 -->
          <th
            class="c-act"
            data-slot="预留 · 申请退货"
            title="预留 · 申请退货（老稿 子页面改造.html:633）· 归属 C3〈订单〉——稿注：需求 4.2 有「订单售后 / 退货」（顾客发起退货申请），这是顾客侧动作，天然属于订单行，落在这一列"
          ></th>
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
          <td class="c-st"><span class="state">已经结单</span></td>
          <!-- 稿 `:754` 是 `<button class="btn-text">复制商家 ID</button>`（稿外死控件：裸控件、无 data-slot）。
               ⚠️ **本页不是死控件** —— 原 Vue `:20` 的「复制」已是**真事件**（`copyMerchantId`）⇒ 保留实事件，
               **不挂 `disabled`、不进 §三**；文案照稿由「复制」改为「复制商家 ID」。 -->
          <td class="c-act">
            <button class="btn-text" @click="copyMerchantId(order.MerchantId)">复制商家 ID</button>
          </td>
        </tr>
      </tbody>
    </table>

  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { resolveBuyerId } from '../api/auth'

const orderList = ref([])
const loading = ref(false)

// 降级链已收敛到 src/api/auth.js:resolveBuyerId()（与 ShoppingCart 逐字相同）。
const getBuyerId = () => resolveBuyerId()

const formatDateTime = (dateStr) => {
  if (!dateStr) return '未知'
  const date = new Date(dateStr)
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

/* ── 加载 / 空态（稿 `.empty` `:432`；稿的 `ho` 面无空态 ⇒ 文案照既有实现保留）──── */
.empty {
  background: var(--zone); border-radius: var(--r2);
  padding: var(--s8) var(--s6); text-align: center;
  font-size: var(--fs-sm); color: var(--ink-3);
}

/* ── 窄档：稿 `:438-454` 的 `@media (max-width:1080px)` 只改 `.uc` 栅格与 `.uc-menu`
   （两者都不在本组件）⇒ 本页无对应声明。表格列宽是百分比，随右栏自适应。 ───────── */
</style>
