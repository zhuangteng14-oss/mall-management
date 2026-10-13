<template>
  <!--
    C3 块 4〈订单块〉——「购物车块」的第 1 个 tab：**购物车**（真购物车，`cart` 表）。

    ⚠️ 本页**不是**壳的子路由页面 —— 宿主是 `views/merchant/OrderPane.vue`（购物车块容器）。
    故本组件**不画 tab 头、也不再有 📋/🛒 两态开关**（那是块 4 之前「本页 = 订单行伪购物车」
    时代的产物；开关已升格成容器的真 tab 栏，`showSwitch` / `orderTab` / `@toggle` 三个 prop
    随之退役 —— 登记见 `docs/agent/tech-debt.md`）。

    ⚠️ 与块 4 之前的本页是**两份不同的东西**（同名文件、同名类名，但数据源换了）：
      · 改前：读 `/order/orderSelect`（**订单行**）当购物车画，数量/小计两列落 `—`
        —— 因为 `order` 表当时**没有** `Quantity` 列。
      · 改后：读 `/cart/list`（**`cart` 表**）—— 数量、单价、小计、结算全是真值。
    「未成交订单」的那一半（含付款/收货）**已迁走**到 `views/OrderMine.vue`
    （用户 2026-10-10 裁决：付款/收货全归「我的订单」tab，本 tab 只留真购物车）。
  -->
  <div class="cart-page">
    <div class="wrap">
      <!-- 面包屑：与全部内页同款。⚠️ 稿里是 `<a>`（无 href、不可点）；本页用 `<span>`，不画死控件。 -->
      <nav class="crumb">
        <span>首页</span>
        <span class="sp">/</span>
        <span class="cur">购物车</span>
      </nav>

      <div class="h1row">
        <h1 class="page-h">购物车 <span class="n">{{ totalCount }} 件商品</span></h1>
        <!-- ⚠️ 「编辑」（批量删 / 移入收藏）**仍无端点** ⇒ 保持 disabled + title，不画死控件。
             （块 4 的 5 个 `/cart/*` 端点里没有批量口。） -->
        <button v-if="rows.length > 0" class="edit" disabled
                title="待后续笔——批量删 / 移入收藏均无端点（`/cart/*` 5 条里只有单行 remove）">编辑</button>
      </div>

      <div v-if="loading" class="empty">
        <div class="p">加载中…</div>
      </div>

      <!-- 空态：保留面包屑与标题，把列表区换成空态，并摘掉承诺条与吸底结算栏（两者都以「有商品」为前提）。 -->
      <div v-else-if="rows.length === 0" class="empty">
        <div class="big">购物车还是空的</div>
        <div class="p">逛逛商品，把想买的先放进来。</div>
        <router-link class="go2" :to="searchPath">去挑商品</router-link>
      </div>

      <!-- ── 有商品 ──────────────────────────────────────────────────────── -->
      <template v-else>
        <section class="shops">
          <div class="list-head">
            <span></span><span>商品</span>
            <span class="c-num">单价</span><span class="c-num">数量</span>
            <span class="c-sub">小计</span><span class="c-op">操作</span>
          </div>

          <div v-for="group in shopGroups" :key="group.id" class="shop">
            <!-- 店铺头：分组键 `MerchantId` 在 cart 行上（`CartDao.selectByCustomer` 的 SELECT 列）。
                 店名无来源（无店铺表）⇒ 落**商家号**，与 `HistoricalOrders.vue` 的「商家ID」同一既有做法。 -->
            <div class="shop-head">
              <span class="shop-nm">商家 {{ group.id }}<span class="badge">{{ group.rows.length }} 件</span></span>
              <button class="visit" disabled
                      title="待后续笔——无店铺表，无落点（与 ProductPage 的商家卡同一缺口）">进店逛逛 ›</button>
            </div>

            <div v-for="row in group.rows" :key="row.ProductCode" class="row">
              <div class="thumb">
                <img v-if="row.GoodImg" :src="row.GoodImg" :alt="row.GoodName || row.ProductCode" />
              </div>
              <div class="nm">
                <!-- ⚠️ 商品被删 / 下架 ⇒ LEFT JOIN 没配上，`GoodName` 为 `null`。
                     这一行**照画不跳过**（行还在），但标成「已下架」并禁掉结算 —— 后端
                     `CartService.checkout` 遇到这种行会整单中止（`商品已不存在，无法结算：…`），
                     前端先说，比让用户点了才弹错要好。 -->
                <div class="t" :class="{ gone: !row.GoodName }">{{ row.GoodName || '商品已下架' }}</div>
                <!-- ⚠️ 型号名（`row.AxisName`）—— **2026-10-12 用户五处挂账裁定 ④-a**：多型号商品的几个
                     SKU 共享同一个 `GoodName` ⇒ 车里几行长得一模一样，看不出买的是哪个型号。后端
                     `/cart/list` 自本笔起多回一列 `AxisName`（`CartDao.GOODS_COLS`）。非型号件为 `null`
                     ⇒ 这半句**整段不画**（不写「型号 —」）。间隔用 `&emsp;` **实体**、不直接敲全角空格（`eslint` 的
                     `no-irregular-whitespace` 不认模板文本里的 U+3000；先例 `ProductPage.vue:289`）。**不用 `·` 拼接** —— 那是仓里点名的 AI 默认样。 -->
                <div class="spec"><template v-if="row.AxisName">型号 {{ row.AxisName }}&emsp;</template>商品编号 {{ row.ProductCode }}<span v-if="Number(row.OriginalPrice) > 0" class="dsc" title="这件是折扣商品（划线原价 &gt; 0）⇒ 本单的满减 / 折扣券不可用（无门槛券仍可用）。判据见 useCouponPick.couponBlockedReason，与后端 CouponService.redeem 那条闸逐字对齐。">折扣商品</span></div>
              </div>
              <div class="unit">
                <span class="v">¥{{ formatPrice(unitPrice(row)) }}</span>
                <!-- ⚠️ 成交价（`PriceSnapshot`，加购时记的）与现价（`PriceNow`）**两个都画**：
                     「购物车里 ¥10、点进去 ¥12」这件事要在画面上看得见。结算用的是**快照价**。 -->
                <span v-if="priceChanged(row)" class="chg" title="加入购物车之后，该商品在店铺里改了价。结算按加入时的价。">
                  现价 ¥{{ formatPrice(row.PriceNow) }}
                </span>
              </div>
              <div class="qty">
                <button class="b" :disabled="busy || !row.GoodName || Number(row.Quantity) <= 1"
                        @click="step(row, -1)">−</button>
                <span class="v">{{ row.Quantity }}</span>
                <button class="b" :disabled="busy || !row.GoodName || Number(row.Quantity) >= MAX_QTY"
                        :title="Number(row.Quantity) >= MAX_QTY ? `数量最多 ${MAX_QTY} 件` : null"
                        @click="step(row, 1)">+</button>
              </div>
              <div class="sub">¥{{ formatPrice(subtotal(row)) }}</div>
              <div class="op">
                <button class="pay-btn" :disabled="busy || !row.GoodName"
                        :title="row.GoodName ? '只结算这一件' : '商品已下架，无法结算'"
                        @click="checkout(row.ProductCode)"><IconGlyph name="ok" />结算</button>
                <button class="del-btn" :disabled="busy" @click="remove(row)"><IconGlyph name="no" />删除</button>
              </div>
            </div>

            <div class="shop-foot">
              本店小计
              <span class="v">¥{{ formatPrice(groupTotal(group)) }}</span>
            </div>
          </div>
        </section>

        <!-- 服务承诺条（跨页共性组件，文案与 ProductPage 逐字一致）。
             ⚠️ **仍是占位**：`goods` 没有承诺列，选了什么存不下来 ⇒ 保持纯展示，不挂控件。 -->
        <div class="pledge"
             title="待 C3〈商品〉——goods 无承诺列，选了什么存不下来。⚠️ 文案必须与 ProductPage 的承诺条逐字一致（跨页共性组件）">
          <span>正品保障</span><span>7 天无理由</span><span>极速退款</span><span>运费险</span>
        </div>
      </template>
    </div>

    <!-- 吸底结算栏：整车结算（`/cart/checkout` 不带 `ProductCode`）。
         ⚠️ 「已选 N 件」不落 —— 本页**没有**勾选这回事（逐件结算走行内那枚按钮），
             写「全选」就是不存在的状态 ⇒ 口径换成能说真话的「共 N 件」。 -->
    <div v-if="rows.length > 0" class="paybar">
      <div class="paybar-in">
        <span class="sel">共 {{ totalCount }} 件商品</span>
        <span class="cp" :class="{ off: !couponPickable }" :title="couponHint"><i>券</i><select v-model="couponId" :disabled="busy || !couponPickable"><option value="">{{ usableCoupons.length ? '不使用优惠券' : '暂无可用券' }}</option><option v-for="c in usableCoupons" :key="c.CouponId" :value="c.CouponId" :disabled="!!couponBlockedReason(c)">{{ couponLabel(c) }}{{ couponBlockedReason(c) ? '（' + couponBlockedReason(c) + '）' : '' }}</option></select><b v-if="discount > 0" class="save">−¥{{ formatPrice(discount) }}</b></span>
        <span class="sums">
          <span class="d">商品总额 ¥{{ formatPrice(cartTotal) }}</span>
          <span class="total" title="口径：Σ（成交价快照 × 数量）− 该券的减免；未选券时与加券之前逐字同值。">
            <span class="k">合计</span><span class="v"><i>¥</i>{{ formatPrice(payTotal) }}</span>
          </span>
        </span>
        <button class="go" :disabled="busy || !canCheckoutAll" @click="checkout()">
          <IconGlyph name="ok" />{{ busy ? '处理中…' : `去结算（${totalCount} 件）` }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
/* ⚠️ 这对 `eslint-disable/enable no-undef` 是**必需**的，不是偷懒 —— 本仓 `eslint-plugin-vue`
   是 **8.7.1**（`package.json`），它不认识 `<script setup>` 的编译宏（`defineProps` /
   `defineEmits`），且 `package.json` 的 `eslintConfig.env` 只有 `node: true` ⇒ `alert` 这类
   浏览器全局也被判 `no-undef`、`npm run build` 直接红。先例：`OrderCenter.vue` /
   `GoodsSelect.vue:156` / `HomeTab.vue:15`。 */
/* eslint-disable no-undef */
import { ref, computed, onMounted } from 'vue'
import { handleApiError } from '../api/http'
import { cartList, cartUpdate, cartRemove, cartCheckout } from '../api/order'; import { useCouponPick } from '../composables/useCouponPick'   // ⚠️ 两条 import **同行**（2026-10-12 结算用券那一笔）—— 本文件被 `api-contract.md` 按 `:108/156/190`、`slot-registry.md` 按 `:46/77/173/198` 等钉住，**多一行就是十几处集体顶漂**（同 `HomePage.vue:284` 那条的做法）
import { resolveBuyerId, getUsername } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移（`api-contract.md` 按 `:108/156/190`、`slot-registry.md` 按 `:46/77/173/198` 钉住本文件）

// 上界与后端 `CartService.MAX_QUANTITY` **同值**（999）。前端拿它当 `+` 的闸，
// 后端仍是权威（超了回「数量最多 999 件」）—— 两处都留，免得只在一边拦。
const MAX_QTY = 999

const rows = ref([])
const loading = ref(false)
const busy = ref(false)

const getBuyerId = () => resolveBuyerId()

// 分组键 `MerchantId` 在 cart 行上。⚠️ 不再按 `order.MerchantId` —— 那是改前的数据源。
const shopGroups = computed(() => {
  const map = new Map()
  for (const r of rows.value) {
    const key = r.MerchantId || '未知商家'
    if (!map.has(key)) map.set(key, { id: key, rows: [] })
    map.get(key).rows.push(r)
  }
  return [...map.values()]
})

const formatPrice = (price) => Number(price || 0).toFixed(2)

// 单价 = 成交价快照；快照为空（历史行 / 异常行）才回落到现价 —— 与
// `CartService.checkout` 里那段 `unit == null ? PriceNow : PriceSnapshot` **同一口径**。
const unitPrice = (row) => (row.PriceSnapshot != null ? row.PriceSnapshot : row.PriceNow)

const priceChanged = (row) => {
  if (row.PriceNow == null || row.PriceSnapshot == null) return false
  return Number(row.PriceNow) !== Number(row.PriceSnapshot)
}

const subtotal = (row) => Number(unitPrice(row) || 0) * Number(row.Quantity || 0)
const groupTotal = (group) => group.rows.reduce((s, r) => s + subtotal(r), 0)

// ⚠️ 「凡是另一个数算得出来的，都不许手写」—— 件数与合计都由 `rows` 现算。
const totalCount = computed(() => rows.value.reduce((s, r) => s + Number(r.Quantity || 0), 0))
const cartTotal = computed(() => rows.value.reduce((s, r) => s + subtotal(r), 0))
// 有下架商品时整车结算**必然**中止（后端遇到就整单停）⇒ 先把按钮禁掉，并说清原因。
const canCheckoutAll = computed(() => rows.value.every((r) => r.GoodName))

const searchPath = computed(() => (window.location.hash.startsWith('#/merchant') ? '/merchant/search' : '/customer/search'))

const load = async () => {
  const customerId = getBuyerId()
  if (!customerId) {
    console.warn('未登录，无法获取购物车')
    return
  }
  loading.value = true
  try {
    const data = await cartList(customerId)
    if (data.success) {
      rows.value = data.list || []
    } else {
      console.error('获取购物车失败', data.msg)
    }
  } catch (err) {
    handleApiError(err, '请求出错')
  } finally {
    loading.value = false
  }
}

/** 改量：**绝对赋值**（后端语义），不是增量。`Quantity < 1` 会被拒 ⇒ 减到 0 之前先禁用。 */
const step = async (row, delta) => {
  const next = Number(row.Quantity || 0) + delta
  if (next < 1 || next > MAX_QTY) return
  busy.value = true
  try {
    const res = await cartUpdate({ CustomerId: getBuyerId(), ProductCode: row.ProductCode, Quantity: next })
    if (res.success) {
      row.Quantity = next   // 就地改，不整页重拉 —— 重拉会把列表顺序（按 updated_at 倒序）打乱
    } else {
      alert('修改数量失败：' + (res.msg || '未知错误'))
    }
  } catch (err) {
    console.error('修改数量出错', err)
    alert('网络错误，请稍后重试')
  } finally {
    busy.value = false
  }
}

const remove = async (row) => {
  if (!window.confirm(`确定要从购物车移除「${row.GoodName || row.ProductCode}」吗？`)) return
  busy.value = true
  try {
    const res = await cartRemove({ CustomerId: getBuyerId(), ProductCode: row.ProductCode })
    if (res.success) {
      await load()
    } else {
      alert('移除失败：' + (res.msg || '未知错误'))
      await load()
    }
  } catch (err) {
    console.error('移除出错', err)
    alert('网络错误，请稍后重试')
  } finally {
    busy.value = false
  }
}

// ==================== 结算用券（C3 块 5 · commit 5）====================
// ⚠️ 本节**不自带算法** —— 与商品页「立即购买」的购入弹窗共用 `composables/useCouponPick.js`
//    那一处（两条链各抄一份 ⇒ 抄错时**两边都不报错**，只是某一处的「合计」慢慢算成另一个数）。
//    里面算的仍是**预估值**，唯一权威在后端 `CouponService.redeem`（真正的抢券 CAS 在那一步）——
//    同 `popupTotal` 那条先例：显示归显示，**落库的数以服务端回体里的 `Discount` 为准**。

/** 整车只含**一个商家**时才允许用券 —— 一张券只抵一笔订单（后端同判，见 `CartService.checkout`）。 */
const groupCount = computed(() => shopGroups.value.length); const hasDiscount = computed(() => rows.value.some((r) => Number(r.OriginalPrice) > 0))   // ⚠️ 两条**同行**（同 `:151` 那对 import 的理由：本文件被 `slot-registry.md` 按 `:369/372/446/465`、`api-contract.md` 按 `:108/156/190` 钉住 ⇒ **多一行就集体顶漂**）。`hasDiscount` = 车里**任一行**的 `OriginalPrice > 0`（`CartDao.GOODS_COLS` 2026-10-12 起回这一列）；⚠️ 判据是**整单**级、不是逐行（见 `useCouponPick.couponBlockedReason`）。

const {
  couponId, usableCoupons, couponPickable, couponHint, couponLabel, couponBlockedReason,
  discount, payTotal, loadCoupons, resetCoupon
} = useCouponPick({
  // 领券账户 = **登录名**（`coupon_grant.ToAccount` 存的就是 `login.UserName`，2026-10-12 起）
  account: getUsername() || '',
  orderTotal: cartTotal,
  enabled: computed(() => rows.value.length > 0 && groupCount.value === 1),
  // ⚠️ 传 computed 不传字符串：整车是**异步**拉回来的，字符串会冻结在 setup 那一刻（恒「含 0 个商家」）
  blockedHint: computed(() => `本车含 ${groupCount.value} 个商家，优惠券只能抵一笔订单 —— 请按商家分单结算`), hasDiscount
})

/**
 * 结算。不传 `productCode` = 整车；传了 = 只结算那一件。
 *
 * ⚠️ 后端的失败响应里**可能带着已经生成的订单**（`CartService` 类注释：结算不是一笔原子操作，
 *    因为订单链禁 `@Transactional`）⇒ 这里必须把 `list` 里的几笔**显示出来**，
 *    否则用户会把「已下单 2 笔」读成「什么都没发生」而重复下单。
 */
const checkout = async (productCode) => {
  if (!productCode && !window.confirm(`确定结算购物车里的全部 ${totalCount.value} 件商品吗？`)) return
  busy.value = true
  // ⚠️ 券只随**整车且单商家**那一笔走。逐行结算**不带券** —— 免得「选了券却按原价成交」
  //    这种静默；空串会被 `api/order.js` 的 `q()` 丢掉 ⇒ 后端看到的就是「没用券」。
  const useCoupon = !productCode && couponPickable.value ? couponId.value : ''
  try {
    const res = await cartCheckout({ CustomerId: getBuyerId(), ProductCode: productCode, CouponId: useCoupon })
    if (res.success) {
      // 券已核销 ⇒ 清掉选择并重拉券包（那张券不会再出现在可选项里）。
      // ⚠️ 只在**成功**这一支重置：失败时后端**没**烧券（`addLocked` 里 CAS 抢不到就不建单），
      //    留着选择让用户直接重试。
      resetCoupon()
      await loadCoupons()
      alert(res.msg || '结算成功')
      await load()
      goOrders()
    } else {
      const made = (res.list || []).map((o) => o.OrderId).join('、')
      alert((res.msg || '结算失败') + (made ? `\n\n（以下订单已生成，可在「我的订单」里付款：${made}）` : ''))
      await load()
    }
  } catch (err) {
    console.error('结算出错', err)
    alert('网络错误，请稍后重试')
  } finally {
    busy.value = false
  }
}

/** 结算完落到「我的订单」tab —— 由**容器**认 `?tab=`；这里只改 query，不换路由。 */
const goOrders = () => {
  const path = window.location.hash.replace(/^#/, '').split('?')[0]
  window.location.hash = `${path}?tab=orders`
}

onMounted(load)
// 券包与购物车**并发**拉（不串行等）—— 两处各自成败，谁挂了都不影响另一边。
onMounted(loadCoupons)
/* eslint-enable no-undef */
</script>

<style scoped>
/* ══ 来源：docs/美化/pages/ShoppingCart-customer.html（v5，716 行）════════════════
   ⚠️ 稿的 `.bar`（预览条）/ `body.slots *`（角标可视化）/ `.nav-bar` `.brand`
      `.nav-right` `.dot`（顶栏，归 AppShell.vue）—— **一律不搬**，搬进来就是悬空类名。
   ⚠️ 稿的 `.page` / `.page.on`（两视图切换）不搬：Vue 侧用 v-if / v-else。
   ⚠️ 块 4 起：稿的 `.switch-*` 一族**已迁走**到宿主 `merchant/OrderPane.vue`（它才是画 tab 栏的人）。
   ⚠️ 未复画的稿规则：`.grid` `.card*` `.thumb2`（猜你喜欢卡 —— 仍未接线）。 */

/* ── 页根：壳的 `.cart-container` / `.order-container`（AppShell.vue）是 `height:100%;overflow:hidden`
      ⇒ 滚动盒必须由本页提供。
      ⚠️ 宿主 `OrderPane.vue` 的根是 `height:100%`，本页 `height:100%` 取的就是它的高。
      ⚠️ `position:relative` **本页不再需要**（absolute 子元素随开关一起迁走了）；
          容器那边的 tab 栏靠**容器自己**的 `position:relative` 定位，与本页无关。 */
.cart-page {
  height: 100%;
  background: var(--bg);
  overflow-y: auto;
}

/* ── 主区：稿 `.wrap`（`:304`）
   ⚠️ 稿的 `padding-bottom:var(--s8)` 保留：吸底条是 `sticky`（不是 `fixed`），
      它到达自然位置后落回文档流，这段净空是它下面那口「气」。 */
.wrap {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s7) var(--s5) var(--s8);
}

/* 面包屑：分隔符**与文字同色**（同为 `--ink-3`）—— 用 `--line-2` 时对比度只有 1.47:1。 */
.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--ink-3); }
.crumb .sp { font-style: normal; padding: 0 var(--s2); }
.crumb .cur { color: var(--ink-2); }

/* 标题行：标题在左，「编辑」在右 —— 编辑是页面级动作，不是列表级动作。 */
.h1row { margin-top: var(--s4); display: flex; align-items: baseline; gap: var(--s5); }
/* ⚠️ 稿写的是 `font-size: var(--d2)`，而 `--d2 = calc(var(--fs-2xl) * 1.00)`（稿 :244 在
   `:root` 里定义）。scoped 块设不了 `:root` ⇒ 直接写 `var(--fs-2xl)`（36px，值相同）。 */
.page-h {
  display: flex;
  align-items: baseline;
  gap: var(--s4);
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-med);
  line-height: 1.15;
  letter-spacing: -.02em;
}
.page-h .n { font-family: var(--font); font-size: var(--fs-xs); color: var(--ink-3); letter-spacing: 0; }
.edit { margin-left: auto; font-size: var(--fs-sm); color: var(--ink-2); padding: 0 var(--s1); background: none; border: 0; cursor: pointer; }
.edit:hover { color: var(--brand-ink); }
.edit[disabled] { color: var(--ink-3); cursor: not-allowed; }

/* ── 店铺分组（稿 :324-335）── 分组键 = cart 行的 `MerchantId`；店名无来源，头里落的是商家号。 */
.shops { margin-top: var(--s6); }
.shop { border-top: 1px solid var(--line-2); }
.shop-head { display: flex; align-items: center; gap: var(--s3); padding: var(--s4) 0 var(--s3); }
.shop-nm { font-size: var(--fs-sm); font-weight: var(--fw-med); color: var(--ink); }
.shop-nm .badge { margin-left: var(--s2); font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3); }
.visit { margin-left: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); background: none; border: 0; padding: 0; cursor: pointer; }
.visit[disabled] { cursor: not-allowed; }
.shop-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s4);
  padding: var(--s3) 0 var(--s5);
  border-top: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.shop-foot .v { font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-med); color: var(--ink-2); font-variant-numeric: tabular-nums; }

/* ── 列头 + 表行共用同一套列宽 ⇒ 天然对齐（稿 :337-342）
   ⚠️ 名称列 `minmax(0,1fr)` 吃掉全部余量（稿 Q3 第 ① 条）：否则余量会被平摊到各列、数字列左右乱飘。
   ⚠️ 骨架取稿的 7 列（28 / 64 / 商品 / 88 / 104 / 112 / 48），**去掉勾选列**
       —— 本页逐件结算走行内按钮，没有「选中」这个状态（块 4 口径，见模板注释）；
       操作列 48 → 132（稿那里只有一枚「删除」文字链，本页放结算 + 删除两枚）。 */
.list-head, .row {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 112px 104px 112px 132px;
  align-items: center;
  gap: var(--s4);
}
.list-head { padding-bottom: var(--s3); font-size: var(--fs-xs); color: var(--ink-3); }
.list-head .c-num, .list-head .c-sub, .list-head .c-op { text-align: right; }
/* 表行是**发丝线**，不是卡片流（稿 :39-40「v2 已经画对的部分逐字保留」） */
.row { padding: var(--s4) 0; border-bottom: 1px solid var(--line); }

/* ① 图片 64px 方图 —— 最小那一级 */
.thumb { width: 64px; aspect-ratio: 1/1; border-radius: var(--r1); background: var(--img); border: 1px solid var(--line); overflow: hidden; }
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }

/* ② 商品名 —— 最重那一级；编号次之。 */
.nm { min-width: 0; }
.nm .t {
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  line-height: 1.35;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 下架行：名字位置换成「商品已下架」，弱化 + 斜体，与真名字区分开。 */
.nm .t.gone { color: var(--ink-3); font-style: italic; font-weight: var(--fw-reg); }
.nm .spec { margin-top: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }

/* ③ 单价：最轻的数字 —— 承重的那一次留给吸底条的「合计」。 */
.unit { text-align: right; font-size: var(--fs-sm); color: var(--ink-3); font-variant-numeric: tabular-nums; }
.unit .v { display: block; }
.unit .chg { display: block; margin-top: 2px; font-size: var(--fs-xs); color: var(--accent); }

/* ④ 数量步进 / ⑤ 小计 */
.qty { display: flex; align-items: center; justify-content: flex-end; gap: var(--s2); }
.qty .b {
  width: 26px;
  height: 26px;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
  line-height: 1;
  cursor: pointer;
}
.qty .b:hover:not([disabled]) { border-color: var(--brand); color: var(--brand-ink); }
.qty .b[disabled] { color: var(--ink-3); cursor: not-allowed; }
.qty .v { min-width: 24px; text-align: center; font-size: var(--fs-sm); color: var(--ink); font-variant-numeric: tabular-nums; }
.sub {
  text-align: right;
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

/* ⑥ 操作：结算（单件）/ 删除。 */
.op { display: flex; align-items: center; justify-content: flex-end; gap: var(--s2); }
.op button {
  height: 32px;
  padding: 0 var(--s4);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  white-space: nowrap;
  cursor: pointer;
}
.op button:hover:not([disabled]) { border-color: var(--brand); color: var(--brand-ink); }
.op button[disabled] { color: var(--ink-3); cursor: not-allowed; }
.op .del-btn:hover:not([disabled]) { border-color: var(--accent); color: var(--accent); }

/* ── 服务承诺条（跨页共性组件，文案与 ProductPage 逐字一致）
   稿 :387-390：承诺条是**购物车的尾**，不是推荐区的头 ⇒ 上紧下松。 */
.pledge {
  margin-top: var(--s2);
  margin-bottom: var(--s2);
  padding: var(--s4) 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: var(--s8);
  flex-wrap: wrap;
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.pledge span::before { content: "·"; color: var(--brand); margin-right: var(--s2); font-weight: var(--fw-semi); }

/* ── 吸底结算栏（稿 :414-454）
   ⚠️ 稿 v5 把底色从 `--surface` 改成 **`--glaze-deep`** 并删掉 `border-top`：全页唯一的「面」，
      与登录页品牌栏、个人页身份卡同一枚色 ⇒「每页一个釉面主块，只用一次」。照搬。
   ⚠️ `sticky;bottom:0` 而**不是** `fixed`（稿 Q3 第 ② 条）：fixed 要额外给正文补 `padding-bottom`，
      否则最后一行会被永久盖住；sticky 到自然位置就落回文档流。参照物是上面的 `.cart-page` 滚动盒。
   ⚠️ `z-index:20`：**必须高于顶栏 `.top-nav` 的 `10`** —— 本元素在 DOM 里靠后、与顶栏同处一个
      层叠上下文 ⇒ 取 10 或以下就会在滚动时盖到顶栏上（用户 2026-10-08 第 2 条，同一 bug 的同一处）。 */
.paybar { position: sticky; bottom: 0; z-index: 20; background: var(--glaze-deep); }
.paybar-in { max-width: 1180px; margin: 0 auto; padding: var(--s4) var(--s5); display: flex; align-items: center; gap: var(--s5); }
.paybar .sel { font-size: var(--fs-sm); color: var(--ink-2); }
.paybar .sums { margin-left: auto; display: flex; align-items: baseline; gap: var(--s4); }
.paybar .sums .d { font-size: var(--fs-xs); color: var(--ink-3); font-variant-numeric: tabular-nums; }

/* ── 结算用券（C3 块 5 · commit 5）── 「券」小签 + **原生** `<select>`。
   ⚠️ 用原生 select（同本页 `step`/`remove` 用 `window.confirm`/`alert` 那条「不造控件」的房规）：
      可选项短、且原生控件天然可键盘操作。控件形制照 `OrderCenter.vue:461` 的 `.status-select`。
   ⚠️ `.off` = 「本车用不了券」（多商家 / 无可用券）—— 只**压明度**，不隐藏：
      隐藏会让「这里为什么没有券」变成无从问起（同 `.total` 挂 `title` 说口径那条）。 */
.paybar .cp { display: inline-flex; align-items: center; gap: var(--s2); }
.paybar .cp > i {
  font-style: normal;
  font-size: var(--fs-xs);
  line-height: 16px;
  padding: 0 4px;
  color: var(--ink-2);
  border: 1px solid var(--line);
  border-radius: var(--r1);
}
.paybar .cp select {
  max-width: 220px;
  height: 32px;
  padding: 0 var(--s2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  cursor: pointer;
}
.paybar .cp.off { opacity: .5; }
.paybar .cp select[disabled] { cursor: not-allowed; }
.paybar .cp .save { font-size: var(--fs-sm); color: var(--accent); font-variant-numeric: tabular-nums; }
.total { display: flex; align-items: baseline; gap: var(--s2); }
.total .k { font-size: var(--fs-sm); color: var(--ink-2); }
.total .v {
  font-family: var(--font-display);
  font-size: var(--fs-xl);
  font-weight: var(--fw-med);
  line-height: 1;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.total .v i { font-style: normal; font-size: var(--fs-sm); margin-right: 2px; }
.go {
  height: 48px;
  padding: 0 var(--s7);
  border: 0;
  border-radius: var(--r2);
  background: var(--brand);
  color: var(--bg);
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  white-space: nowrap;
  cursor: pointer;
}
/* ⚠️ 稿的 `.go[disabled]` **推翻**了「disabled 涂灰盒」，改回 `--brand` 实底（稿 :443-454）——
   理由是「房规：逐行重复控件只 disabled + title，全批 [disabled] 几乎都只改 cursor」。照搬。 */
.go[disabled] { background: var(--brand); color: var(--bg); cursor: not-allowed; }

/* ── 空态（稿 :456-461）── 「去挑商品」块 4 起是**真链接**（此前是死控件）。 */
.empty { padding: var(--s8) 0; text-align: center; }
.empty .big { font-family: var(--font-display); font-size: var(--fs-lg); color: var(--ink-2); }
.empty .p { margin-top: var(--s3); font-size: var(--fs-sm); color: var(--ink-3); }
.empty .go2 {
  display: inline-block;
  margin-top: var(--s5);
  height: 40px;
  line-height: 40px;
  padding: 0 var(--s6);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: none;
  color: var(--ink-2);
  font-size: var(--fs-sm);
  text-decoration: none;
}

/* ── 窄屏（稿 :463-488）：列头隐藏 ⇒ 各字段自带小标签
   ⚠️ 块 4 起**不再用显式 `grid-area` 定位**：勾选列没了、行里只剩 6 件（图 / 名 / 单价 / 数量 / 小计 / 操作），
      两两成行用 `grid-template-areas` 表达即可，比原来那串 `grid-area: n/m/...` 好读，也不会因列数变化而失准。
   ⚠️ 吸底条**不靠 flex-wrap**（折行位置随视口变，稿 :166-167 记 GoodsUp 踩过）。 */
@media (max-width: 1120px) {
  .list-head { display: none; }
  .row {
    grid-template-columns: 64px minmax(0, 1fr);
    grid-template-areas:
      "thumb nm"
      "unit  op"
      "qty   sub";
    column-gap: var(--s5);
    row-gap: var(--s4);
  }
  .row .thumb { grid-area: thumb; }
  .row .nm { grid-area: nm; }
  .row .unit { grid-area: unit; text-align: left; font-size: var(--fs-xs); }
  .row .op { grid-area: op; }
  .row .qty { grid-area: qty; justify-content: flex-start; }
  .row .sub { grid-area: sub; }
  /* 列头没了，这几个数各自带前缀标签 —— 只在窄屏出现，不是装饰 */
  .row .unit::before { content: "单价 "; }
  .row .qty::before { content: "数量"; color: var(--ink-3); font-size: var(--fs-xs); }
  .row .sub::before { content: "小计 "; font-family: var(--font); font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3); }
  .pledge { gap: var(--s5); }
  .paybar-in { flex-direction: column; align-items: stretch; gap: var(--s3); }
  .paybar .sums { margin-left: 0; justify-content: space-between; }
  /* 窄屏列排后把券选择器拉到满宽（`.cp` 是 flex 行 ⇒ 给 select 吃掉余量，不写死宽度） */
  .paybar .cp { width: 100%; }
  .paybar .cp select { flex: 1; max-width: none; }
  .go { width: 100%; padding: 0; }
}

/* ── 折扣商品小标（C3 块 5 · 用券规则那一笔，2026-10-12）
   ⚠️ 规则写在**文件尾**是**刻意的**：本文件被 `slot-registry.md` 按 `:369/372/446/465` 钉住，
      插到 `.nm .spec`（`:415` 附近）旁边会让 `:446/:465` 集体顶漂 ⇒ 规则落在**最大被引行之后**，
      零漂移。选色与 `.unit .chg`（同页既有的「价格类」强调）同一枚 `--accent`。
   ⚠️ 只是**说明**，不是闸 —— 真正的拒在服务端（同全仓「前端仅提示、后端拒」口径）。 */
.nm .spec .dsc {
  margin-left: var(--s2);
  padding: 0 var(--s2);
  font-size: var(--fs-xs);
  line-height: 16px;
  display: inline-block;
  color: var(--accent);
  border: 1px solid currentColor;
  border-radius: var(--r1);
}
</style>
