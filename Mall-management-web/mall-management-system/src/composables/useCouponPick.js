// src/composables/useCouponPick.js —— **结算选券的共用一处**（C3 块 5 · commit 5 续笔，2026-10-12）。
//
// 为什么要抽：同一条逻辑有两个消费方 ——
//   · 购物车吸底结算栏（`views/ShoppingCart.vue`，整车）
//   · 商品详情页「立即购买」的购入弹窗（`views/ProductPage.vue`）
// 抄两份 ⇒ 第二处抄错时**两边都不报错**，只是某一处的「合计」慢慢算成另一个数。
// 房内先例：`config/coupon-format.js`（券的字段 → 人话，主页与券包弹窗共用）。
//
// ⚠️ 这里算出来的是**预估值**，唯一权威在后端 `CouponService.redeem`（真正的抢券 CAS 在那一步）。
//    同 `ProductPage.popupTotal` 那条先例：显示归显示，**落库的数以服务端回体里的 `Discount` 为准**。
// ⚠️ **不按商家筛券**：券的适用档 `Scope='merchant'` 限制的是**商品种类**（`CategoryMatch`），
//    而 `MerchantAccount` 是**发券那家商家**的业务号（`''` = 平台券）—— 拿商家筛会把平台券与
//    别家发的券一起藏掉。后端 `redeem` 同笔也不判，理由与证据见那里的方法体注释。
import { computed, ref, unref, watch } from 'vue'
import { myCoupons } from '../api/marketing'
import { couponName, couponLine } from '../config/coupon-format'

/** 折扣商品挡下满减 / 折扣券时那句提示 —— **两处消费方共用同一句**（改一处即两边同改）。 */
const DISCOUNT_BLOCK_HINT = '折扣商品不可用此券'

/**
 * @param {object} opt
 * @param {string|import('vue').Ref<string>} opt.account  领券账户 = **登录名**
 *        （`coupon_grant.ToAccount` 存的就是它，2026-10-12 起）
 * @param {import('vue').Ref<number>} opt.orderTotal       本单**原价合计**（门槛比它、折扣按它算）
 * @param {import('vue').Ref<boolean>} opt.enabled          这一单能不能用券
 *        （多商家整车 / 空单 ⇒ `false`）
 * @param {string|import('vue').Ref<string>} [opt.blockedHint]  `enabled` 为 false 时那句 `title` 文案。
 *        ⚠️ **给 ref 不要给当场求值的字符串** —— 购物车那句要带上「含几个商家」，
 *        写成字符串就冻结在 `setup` 那一刻（那时整车还没拉回来 ⇒ 恒是「含 0 个商家」）。
 * @param {boolean|import('vue').Ref<boolean>} [opt.hasDiscount]  本单**含不含折扣商品**
 *        （`goods.OriginalPrice > 0` 的任一件）。缺省 = `false`（与加它之前**逐字节同行为**）。
 *        ⚠️ **两个消费方的取数口径不同**（这是本轮唯一没法完全共用的地方）：
 *          · 购物车整车 ⇒ 行里的 `OriginalPrice`（`/cart/list` 那一列，`CartDao.GOODS_COLS` 2026-10-12 加的）
 *          · 商品页「立即购买」⇒ 弹窗行里已有的 `originalPrice`（`/goods/productCodePrice` 自带的）
 *        两处都传 `computed` ⇒ 不冻结。
 */
export function useCouponPick({ account, orderTotal, enabled, blockedHint, hasDiscount }) {
  const couponId = ref('')
  const grants = ref([])

  const couponPickable = computed(() => !!unref(enabled))

  /** 可选的券 = 券包里**未使用**的那两桶（`expiring` ∪ `usable`，`loadCoupons` 已滤过）。
   *  ⚠️ 排序由后端定（`expiring` 在前）⇒ **本处不重排**（同 `myCoupons` 的 javadoc）。 */
  const usableCoupons = computed(() => grants.value)

  const couponHint = computed(() => {
    if (!couponPickable.value) return unref(blockedHint) || '本单暂不可使用优惠券'
    if (!usableCoupons.value.length) return '券包里暂时没有可用的券'
    // ⚠️ 这句是**整栏的**提示（`title`），故只说事实、不宣称「都被灰了」——
    //    本单含折扣商品时，被灰的只是满减 / 折扣两类，无门槛券照常可选。
    if (anyCouponBlocked.value) return '本单含折扣商品：满减 / 折扣券不可用，无门槛券可用'
    return '选一张券抵本单；不选则按原价结算'
  })

  const couponLabel = (c) => couponName(c) + '（' + couponLine(c) + '）'

  /** 本单含折扣商品（`unref` 过的布尔；缺省 `false`）。 */
  const hasDiscountItem = computed(() => !!unref(hasDiscount))

  /**
   * 这张券**为什么被灰掉** —— `''` = 可用。判据与后端 `CouponService.redeem` 那条闸**逐字对齐**
   * （2026-10-12 用户 A 档批准）：
   *   本单含折扣商品（`goods.OriginalPrice > 0` 的任一件） && 券 `Type ∈ {threshold 满减,
   *   percent 折扣}` ⇒ 不可用；`none`（无门槛）放行。
   * ⚠️ 判据是**整单**级（一张券抵的是本单合计），**不是**逐商品 —— 「只让折扣商品那几件不吃券、
   *   其余照吃」这种部分应用规则稿里没有（理由见后端那条 javadoc）。
   * ⚠️ 前端只**灰掉 + 说明**，真正的拒在服务端（同「前端仅提示、后端拒」的全仓口径）。
   */
  const couponBlockedReason = (c) => {
    if (!c) return ''
    if (hasDiscountItem.value && (c.Type === 'threshold' || c.Type === 'percent')) {
      return DISCOUNT_BLOCK_HINT
    }
    return ''
  }

  const anyCouponBlocked = computed(
    () => usableCoupons.value.some((c) => couponBlockedReason(c) !== '')
  )

  const selectedCoupon = computed(
    () => usableCoupons.value.find((c) => String(c.CouponId) === String(couponId.value)) || null
  )

  /**
   * 选中券的**预估**减免额（元）。三档判据逐条对齐后端 `CouponService.redeem`：
   * `threshold` 未达门槛 ⇒ 0（`none` 型后端强制 `Threshold = 0`）；`percent` 的 `FaceValue` 是
   * **折扣率**（0.85 = 减 15%）；其余按面值减。再按后端那两行 `Math.max(0, Math.min(d, total))`
   * ＋ 两位四舍五入收口 —— 下限 0 挡「`percent` 的率被配成 > 1 ⇒ 反而加价」，上限锁死本单金额。
   */
  const discount = computed(() => {
    const c = selectedCoupon.value
    if (!c) return 0
    // ⚠️ 本单不可用券时**恒 0**，哪怕 `couponId` 还留着上一次的选择：商品页那个弹窗
    //    「关闭 ≠ 清数据」（用户 2026-10-08 六条【二】）⇒ 在「立即购买」里选过券、关掉、
    //    再从「加入购物车」开同一个窗，`couponId` 会**原样留着**。少了这一行，加入购物车那一支
    //    的「合计」会显示一个**根本不会发生**的折后价（cart 支不带券，落库的是原价）。
    if (!couponPickable.value) return 0
    // ⚠️ 被灰掉的券**恒 0**（同上面那条 `!couponPickable` 的理由）：本单含折扣商品时若 `couponId`
    //    还留着上一张满减券，少了这一行「实付」会显示一个**服务端一定会拒**的折后价。
    if (couponBlockedReason(c) !== '') return 0
    const total = Number(unref(orderTotal) || 0)
    const face = Number(c.FaceValue || 0)
    if (c.Type === 'threshold' && total < Number(c.Threshold || 0)) return 0
    const raw = c.Type === 'percent' ? total * (1 - face) : face
    return Math.round(Math.max(0, Math.min(raw, total)) * 100) / 100
  })

  /** 实付 = 原价合计 − 减免。未选券时与加券之前**逐字同值**（`discount` 恒 0）。 */
  const payTotal = computed(() => Math.max(0, Number(unref(orderTotal) || 0) - discount.value))

  /**
   * 拉券包，只留**未使用**的两桶。**失败不抛** —— 券包挂了不该把购物车 / 商品页也画坏，
   * 只是这一栏不可选（同 `CouponWalletDialog.load` 的「两个请求各自成败」）。
   */
  const loadCoupons = async () => {
    const who = unref(account)
    if (!who) { grants.value = []; return }
    try {
      const data = await myCoupons(who)
      const list = data && data.success && Array.isArray(data.list) ? data.list : []
      grants.value = list.filter((c) => c.Bucket === 'usable' || c.Bucket === 'expiring')
    } catch (err) {
      console.warn('券包加载失败，结算用券不可选', err)
      grants.value = []
    }
  }

  /** 清掉选择（结算成功后由调用方调）。是否顺带重拉券包由调用方决定 —— 见各调用点的注释。 */
  const resetCoupon = () => { couponId.value = '' }

  // ⚠️ 选中的券**变成被灰掉的**时把它清掉 —— `<select>` 的 `option[disabled]` 只是不让**新选**，
  //    已经选上的那一项浏览器**照样显示**（用户会看到「券还选着、合计却按原价」，两句自相矛盾）。
  //    触发点有两类：① 本单商品变了（购物车删行 / 商品页弹窗换了商品）；② 券包重拉后换了张券
  //    （`couponId` 只在同一处被写，故只需盯这两条）。
  watch([couponId, hasDiscountItem], () => {
    if (couponBlockedReason(selectedCoupon.value) !== '') couponId.value = ''
  })

  return {
    couponId, usableCoupons, couponPickable, couponHint, couponLabel,
    selectedCoupon, discount, payTotal, loadCoupons, resetCoupon,
    hasDiscountItem, couponBlockedReason, anyCouponBlocked
  }
}
