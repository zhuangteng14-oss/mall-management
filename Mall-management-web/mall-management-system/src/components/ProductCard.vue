<!-- ProductCard.vue —— 商品卡（C3 块 5 · B 组续笔，2026-10-12）。**三处共用**。

  ⚠️ 为什么抽成组件：改前实测（`scripts/probes/c3b5d-cards-before.mjs`，2026-10-12，两遍 16 项 / 0 失败；
     对照图 `docs/agent/evidence/c3-block5/out-b5d-cards-before.png`）三处**根本不是一个形状**：
       · `HomePage.vue`「为你推荐」  图 4/3 · 有框（1px `--line` + `--r2` + 白底）· 三段价 · 有加购钮 · 有元信息行
       · `ShopHome.vue`「本店商品」  图 **1/1** · **无框**（border 0 / bg transparent）· 整值价 · 无加购钮 · 无元信息行
       · `ProductPage.vue`「猜你喜欢」 同 `ShopHome`（那两处本就逐字同形）
     用户裁定**甲案 + 接 3 处** ⇒「两页一致」由**结构**保证，不靠人对齐。统一到 `HomePage` 那一套。

  ⚠️ 三处的**差异不止外观**，抽组件时必须逐条处置（改前逐行读过，不是推断）：
    ① **跳转 query 不一样**：`HomePage` 传 **7 个**（`Id/MerchantId/GoodName/Price/GoodImg/GoodType/ProductCode`），
       另两处只传 **4 个**（`MerchantId/GoodName/GoodType/Id`）。
       ⇒ 本组件一律按 **7 个** 组装。理由：`HomePage.vue:400` 的注释把 7 个参数写成契约 ——
       T-guard-6 ①（`progress.md:1441`）**要求「7 个 query 参数都要在」**，少传就是削弱一条既有判据；
       另两处**多收 3 个**参数是无害的（`ProductPage.vue:668` 只解构 4 个，多出来的不读；① 甲案 −16 行后）。
    ② **副作用不一样**：`HomePage` 的 `toGoodsDetail` 还写 `sessionStorage`（清搜索缓存）
       ⇒ 落成**可选 prop `beforeNav`**，`HomePage` 传自己的那两行；另两处不传。
    ③ **元素不一样**：`HomePage` 是 `<article @click>` + `router.push`，另两处是 `<router-link>`。
       ⇒ 一律用 `<router-link>`（中键 / 新标签页能开，比 `@click` 强），副作用走 ②。
    ④ **HTML 合法性**：`<a>` 内**不得**嵌交互内容 ⇒ 加购钮用 `<span class="pcard-buy" role="button" tabindex="0">`（批四起可点；键盘与 `aria-label` 自补、点击 `.stop.prevent` 防跳详情）。
       ⚠️ 这一条是**对 `HomePage` 原写法的修正**，不是照抄 —— 原写法三处里只有它踩了这个。
       ⚠️ 不用原生 `<button>` 是因为它在 `<a>` 里非法，不是因为它不可点。
    ⑤ **空图守卫**：`ShopHome` / `ProductPage` 两处有 `v-if="…GoodImg"`，`HomePage` 没有。
       ⇒ **保留**守卫。⚠️ 这不是「三取二数多数票」：`GoodImg` 为空时裸 `<img src="">` 画的是
       浏览器的**破图图标**，而空图该露 `.pcard-img` 的灰底（稿 `.ph` 就是灰方块）——
       少写这一条是**漏**，不是口味。先例 `HistoricalOrders.vue:66`（其处注明「与
       `OrderCenter.vue:119` 是同一行写法，commit 5 先例，非新发明」）。
       ⚠️ 本仓当前 43 行 `GoodImg` **全非空**（`_work/_probe/home.json` 实测）⇒ 这条今天不触发，
       是**防御**不是现状；`v-if` 掉不掉，今天的截图一模一样。

  ⚠️ 元信息行**改过了**，不是照抄 `HomePage`：原 `.card-meta` 画的是 `goods.GoodType`（分类名）。
     本笔把它让给「**店名 + 已售 N 件**」（用户点名要的）。分类的落点仍在（主页左侧分类导航 /
     搜索页分类树 / `ShopHome` 的筛选 chip），卡上这一个是最弱的一处 ⇒ 登记为偏离。

  ⚠️ **不显**两条（与全块「数据不足不显、不造假」同一条口径）：
     · `SoldQty > 0` **才**画「已售 N 件」。43 行里卖不动的占多数，满屏「已售 0 件」是噪音。
     · `goods.UserName` 为空 ⇒ 左边**留空**，**不写「商家」**这种编出来的占位词。

  ⚠️ **标签行等高占位**（用户 ①，2026-10-12）：「一条不显」与「整行不画」是**两件事** —— 上面那两条
     不画的是**行内的一段**（行还在），而类别标签行改前是 `v-if="kinds.length"` ⇒ 普通商品**连行
     都不画** ⇒ 无标签的卡比有标签的卡**矮一行**，栅格里撑得一行行参差。现在**恒渲染**这一行：
     一枚标签都不命中时画一枚**隐形签**（`.pcard-kind.ghost`，`visibility: hidden`）。
     ⚠️ 为什么用「隐形同形签」而不是给 `.pcard-kinds` 写死一个 `min-height`：前者的等高是
     **构造保证**（同一个元素、同一套 class、同一个 `font-size`/`padding`，高度不可能不同），
     后者要拿一个 px 值去追字体度量，换个字体就错。⚠️ 占位签**沿用真签的类名与文案**
     （不是空文本、也不是 `&nbsp;`）：4 个 CJK 字才会选到与真签**同一个**字体回退 ——
     空白字符可能落到另一套 ascent/descent 上，height 就白占错了。⚠️ 它带 `aria-hidden`：
     `visibility: hidden` 本就把节点移出无障碍树，这一条是**明写**给人看的（别以为它读得出来）。

  ⚠️ `tag`（C3 块 5 · ShopHome 布局授权笔，2026-10-12）：图上角的一枚小签，**默认空串 = 不画**。
     本笔**唯一**的消费方是 `ShopHome.vue` 的「本店商品」网格 —— 用户把原先那个独立的
     「新品首发」模块改成了**卡上的红标签**（用户素材原话即「本店商品（含新品红标签）」），
     顺手解掉了上一笔登记的「新品首发与本店商品内容重叠」那条待裁项。
     ⚠️ 「哪几件算新品」的判据**在调用方**（`ShopHome` 的 `isNew`），**不在**本组件 ——
     本组件只管把那枚签画出来，判据只有一处，不会两边各判一套。

  ⚠️ **类别标签与已售完**（C3 块 5 ·〈Bug 2/3〉落码，2026-10-12）：卡上新增两样，判据都来自后端
     投影列（`MarketingGoodsFlags.java` 那条 SQL 是唯一真相，本组件只读不算）：
     · **类别标签**（名字与价格之间那一行）：`IsSeckill` ⇒ 红「秒杀商品」、`IsSubsidy` ⇒ 绿「国补」；
       两个都命中就**都画**（两个独立标记，不是二选一），一个都不命中 ⇒ **一行都不渲染**（普通商品
       既不画红也不画绿，同「数据不足不显」）。
     · **已售完**（`IsSoldOut`）：整卡置灰（底色压暗 + 内容降不透明度，见 `<style>` 里那条「为什么
       不用 `grayscale`」）+ 图上一枚**红斜「已售完」**贴图。⚠️ **共享页看不到这一态** —— 它们在
       渲染前就把这类行滤掉了（`HomePage` / `GoodsSelect` / 两处「猜你喜欢」四处过滤**同源**，
       都取 `config/goods-flags.js`）；画出来的只有店铺页本店商品 / 热销 / 折扣那几个 tab。

  ⚠️ **落码时去掉了 `showKinds` 这个 prop**（用户 ⑤ 原话是「props = 商品对象 + showKinds」）：四个
     消费点用户都点名要显标签 ⇒ 一个**恒为真**的开关就是没人拧的旋钮，留着只会让人以为「关掉会
     怎样」。真出现「某处不显标签」的需求时再加回它（判据仍只有一处）。
  ⚠️ **为什么要 `from` 这个 prop**（⑤ 没预料到的那个）：`GoodsSelect.vue` 原来的卡片跳转带着
     `from: 'search'`，而 `ProductPage.goBack` 逐字按 `route.query.from === 'search'` 决定
     回程去 `/customer?back=search`（那一跳会让搜索页 `onMounted` 走 `loadState()` 恢复结果）。
     把那一处换成本组件时**不带上它，就等于悄悄删掉一条既有行为** ⇒ 卡片收 `from`（空串不传键），
     `GoodsSelect` 传 `from="search"`，另三处不传。
  ⚠️ **已售完不拦点**：本卡不是成交入口（它只进详情页），售完该说的话由**详情页**说
     （`ProductPage.vue` 的秒杀区在 `SoldOut` 时写「已售完」）。`SeckillPage.vue` 那处 `preventDefault`
     + 红字提示是**那一页**的裁定（它自己是成交入口），不在本组件里复制一份。

  用法：
    <ProductCard :goods="g" />
    <ProductCard :goods="g" :before-nav="onCardNav" />   ← 需要「导航前副作用」的页面才传第二个
    <ProductCard :goods="g" tag="新品" />                 ← 图上角的小标签（本笔只有 ShopHome 传）
    <ProductCard :goods="g" from="search" />              ← 搜索页才传（详情页靠它回搜索页并恢复结果）
-->
<template>
  <router-link class="pcard" :class="{ sold: soldOut }" :to="to" @click="onNav">
    <div class="pcard-img">
      <!-- ⚠️ `v-if` 不能省 —— 见文件头 ⑤（破图图标 vs 灰底；先例 `HistoricalOrders.vue:66`）。 -->
      <img v-if="goods.GoodImg" :src="goods.GoodImg" :alt="goods.GoodName" />
      <!-- 图上角的小签（当前只有「新品」一种，见文件头）。空串 ⇒ 一个节点都不渲染。 -->
      <span v-if="tag" class="pcard-tag">{{ tag }}</span>
      <!-- 已售完那枚**斜向贴图**：压在图面正中、不接收事件（见文件头〈类别标签与已售完〉）。 -->
      <span v-if="soldOut" class="pcard-stamp" aria-hidden="true">已售完</span>
    </div>
    <div class="pcard-tx">
      <div class="pcard-name">{{ goods.GoodName }}</div>
      <!-- 类别标签（秒杀 / 国补）：**独立标记、可以同屏**，也可以一枚都没有（普通商品）。
           ⚠️ 整行**恒渲染**（不写 `v-if`）—— 一枚都没有时画一枚隐形签占住这一行的高度，
              否则「有标签的卡高、没标签的卡矮」，栅格参差。见文件头〈标签行等高占位〉。 -->
      <div class="pcard-kinds">
        <i
          v-for="k in rowKinds"
          :key="k.key"
          class="pcard-kind"
          :class="[k.key, { ghost: k.ghost }]"
          :aria-hidden="k.ghost ? 'true' : null"
        >{{ k.text }}</i>
      </div>
      <div class="pcard-foot">
        <div class="pcard-price">
          <span class="sym">¥</span><span class="int">{{ priceInt }}</span><span class="dec">.{{ priceDec }}</span><span v-if="isVipMember && memberText" class="m-price">会员 ¥{{ memberText }}</span>
        </div>
        <!-- ⚠️ 批四起是**真加购**：走 `/cart/add`，`.stop.prevent` 防冒泡到外层 `<a>`。
             用 <span role="button"> 不用 <button>：见文件头 ④（<a> 内不得嵌交互内容）。 -->
        <span class="pcard-buy" :class="{ busy: buyState === 'busy', done: buyState === 'done' }" role="button" tabindex="0" :aria-label="buyLabel" :title="buyLabel" @click.stop.prevent="onAddToCart" @keydown.enter.stop.prevent="onAddToCart" @keydown.space.stop.prevent="onAddToCart">
          <svg v-if="buyState !== 'done'" width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 5h10l-1 8.4a1 1 0 0 1-1 .9H5a1 1 0 0 1-1-.9z" stroke-linejoin="round"/><path d="M6 5V3.9a2 2 0 1 1 4 0V5" stroke-linecap="round"/></svg><svg v-else width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m3.5 8.4 3 3 6-6.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </span>
      </div>
      <div class="pcard-meta">
        <span class="shop">{{ goods.UserName }}</span>
        <span v-if="soldQty > 0" class="sold">已售 {{ soldQty }} 件</span>
      </div>
    </div>
  </router-link>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'; import { useRoute, useRouter } from 'vue-router'; import { cartAdd } from '../api/order'; import { getCustomerId, getMerchantId, getMemberLevel } from '../api/auth'; import { memberPriceOf } from '../composables/useMember'   // 批四第二批 commit 3（**同行追加、不新增行**）：首页/搜索卡片只做 VIP 会员价展示
import { productLink } from '../config/product-link'
// ⚠️ 三个类别 / 售完判据一律从 `config/goods-flags.js` 取（**本文件不重写一遍**）—— 见文件头
//    〈类别标签与已售完〉与被 import 那个模块的头注。
import { isSeckill, isSubsidy, isSoldOut } from '../config/goods-flags'

// eslint-disable-next-line no-undef
const props = defineProps({
  goods: { type: Object, required: true },
  // 导航前的副作用（可空）。收 `goods` 参数以便调用方按行决策；见文件头 ②。
  beforeNav: { type: Function, default: null },
  // 图上角的小签（可空）。空串 ⇒ 不画。见文件头。
  tag: { type: String, default: '' },
  // 来源标记（可空）。空串 ⇒ **一个键都不加**（URL 逐字同改前）。见文件头〈为什么要 `from` 这个 prop〉。
  from: { type: String, default: '' }
})

// 7 个 query 参数**不再本文件自拼**，走 `config/product-link.js` 那一份共用契约
// （本笔新增柱状图那处消费方 ⇒ 两处必须同源，见文件头 ① 与那个模块的头注）。
// ⚠️ `from` **追加在末尾**（那个模块头注的约定：后加的参数一律追加、不许插中间）—— 它**不在**
//    那 7 个里，是调用方按需带上的第 8 个；不传时 `query` 与改前逐字相同。
const to = computed(() => {
  const t = productLink(props.goods)
  if (props.from) t.query.from = props.from
  return t
})

const onNav = () => {
  if (props.beforeNav) props.beforeNav(props.goods)
}

// ⚠️ 从 `HomePage.vue` 原样搬来（那里只有旧卡片一处消费，现已删）。小数位补齐两位：
//    `299` → `299.00`，`299.5` → `299.50`。⚠️ 没有固定小数位的数据源 ⇒ 两位是**显示口径**，不是精度。
const priceInt = computed(() => {
  if (!props.goods.Price) return '0'
  return parseInt(props.goods.Price) || '0'
})
const priceDec = computed(() => {
  if (!props.goods.Price) return '00'
  const dot = String(props.goods.Price).split('.')[1]
  return dot ? dot.padEnd(2, '0').slice(0, 2) : '00'
}); const isVipMember = computed(() => Number(getMemberLevel()) > 0); const memberText = computed(() => { const p = Number(props.goods.Price); return p > 0 ? Number(memberPriceOf(p)).toFixed(2) : '' })   // 批四第二批 commit 3（**同行追加**）：列表响应不带 `memberPrice`（那是详情端点对 VIP 的加性键）⇒ 卡片只按同一公式展示，不冒充服务端口径

// `SoldQty` 由 `GoodsCacheService.listHome` 的 LEFT JOIN 补出（本笔同批后端改动）。
// 老响应里没有这个键 ⇒ `undefined || 0` = 0 ⇒ 不画那一段（不画 ≠ 画 0）。
const soldQty = computed(() => Number(props.goods.SoldQty) || 0); /* 批四（2026-10-18）：直接加购 —— 走既有 `/cart/add`，不新增端点。⚠️ 同行追加、不新增行（本文件多处在文档里按行号被引）。加购以 `ProductCode` 计行 ⇒ 卡上没有型号码时**不猜**，请用户进详情页选型号。 */ const router = useRouter(); const route = useRoute(); const buyState = ref('idle'); let buyDoneTimer = 0; const buyLabel = computed(() => buyState.value === 'done' ? '已加入购物车' : soldOut.value ? '该商品已售完' : buyState.value === 'busy' ? '正在加入…' : '加入购物车'); function onAddToCart() { if (buyState.value === 'busy') return; if (soldOut.value) { alert('该商品已售完'); return } const buyer = getCustomerId() || getMerchantId(); if (!buyer) { alert('请先登录再购买商品'); router.push({ path: '/login', query: { redirect: route.fullPath } }); return } const code = props.goods && props.goods.ProductCode; if (!code) { alert('该商品暂不支持直接加购，请进详情页选择型号'); return } const merchantId = props.goods.MerchantId; if (!merchantId) { alert('该商品缺少商家信息，暂时无法加购'); return } buyState.value = 'busy'; cartAdd({ CustomerId: buyer, MerchantId: merchantId, ProductCode: code, Quantity: 1 }).then((r) => { if (r && r.success) { buyState.value = 'done'; if (buyDoneTimer) clearTimeout(buyDoneTimer); buyDoneTimer = setTimeout(() => { buyState.value = 'idle' }, 1600) } else { buyState.value = 'idle'; alert((r && r.msg) || '加入购物车失败') } }).catch(() => { buyState.value = 'idle'; alert('网络错误，请稍后重试') }) } onBeforeUnmount(() => { if (buyDoneTimer) clearTimeout(buyDoneTimer) })

// ── 类别标签与已售完（C3 块 5 ·〈Bug 2/3〉落码，2026-10-12）────────────────────────────
// ⚠️ 三个标记都是**后端算好的**（`goodsSelectHome` / `productSearch` 投影里的 `IsSeckill` /
//    `IsSoldOut` / `IsSubsidy`，谓词唯一真相在 `MarketingGoodsFlags.java`）—— 本组件只读不算：
//    「在不在期 / 售没售完」要按**库时钟**判，浏览器钟差 8 小时且错得很安静。
const soldOut = computed(() => isSoldOut(props.goods))
/** 标签**有序**（秒杀在前、国补在后）且**没有就不画** —— 普通商品既不画红也不画绿。
 *  ⚠️ 「不画」不 = 「行不占位」：这一支只决定**画几枚**，占位由下面的 `rowKinds` 兜底。 */
// 批四第二批（2026-10-19）：品牌标 —— 后端投影 `IsBrand`（`user.IsBrand=1` 的商家）。
// `tinyint(1)` 到 JSON 可能是 boolean / 1 / "1"，三种都认（同 MerchantApplyPage 的 isBrandRow）。
const isBrand = computed(() => {
  const v = props.goods && props.goods.IsBrand
  return v === true || v === 1 || String(v) === '1'
})
const kinds = computed(() => {
  const out = []
  if (isBrand.value) out.push({ key: 'brand', text: '品牌' })
  if (isSeckill(props.goods)) out.push({ key: 'seckill', text: '秒杀商品' })
  if (isSubsidy(props.goods)) out.push({ key: 'subsidy', text: '国补' })
  return out
})

/** 这一行**实际渲染**的签：有标签就是标签本人，没有则给一枚**隐形签**（`ghost: true`）占高度。
 *  ⚠️ 文案与类名**故意**沿用真签那一套（`秒杀商品` / `seckill`）—— 见文件头〈标签行等高占位〉：
 *     等高的保证来自「同一个元素 + 同一套 class + 同一个字体」，换一套写法就得自己去算高度了。 */
const rowKinds = computed(() => (
  kinds.value.length ? kinds.value : [{ key: 'seckill', text: '秒杀商品', ghost: true }]
))
</script>

<style scoped>
/* 容器感来自**明度台阶**（页底 / 卡片白面 / 图占位 = 0 / 13.8% / 25.5% 三级），
   不来自圆角和阴影 —— 同 `HomePage.vue` 改前那条注释与 `tokens.css:35-37` 的禁令。 */
.pcard {
  display: block;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.16s;
  /* 全仓没有全局 `a` 规则 ⇒ `<router-link>` 渲染出的 `<a>` 默认带下划线，必须自报 */
  color: inherit;
  text-decoration: none;
}
.pcard:hover {
  border-color: var(--brand);
}
/* ── 已售完态（C3 块 5 ·〈Bug 2〉落码，2026-10-12）────────────────────────────────────
   ⚠️ **只有店铺页会看到这一态**：共享页（主页为你推荐 / 搜索 / 两处「猜你喜欢」）在**渲染前**
      就把 `IsSoldOut` 的行滤掉了（`config/goods-flags.js`）⇒ 那边这张卡根本不会挂 `.sold`。
   ⚠️ **不**用 `filter: grayscale()` 置灰 —— 那会把**商品图**也一起洗成灰，而图是商家的资产、
      不该被页面改色（同 `SeckillPage.vue:289` 那条判据逐字）。走「底色压暗 + 内容降不透明度」
      这两条只作用于本组件自己漆面的做法。⚠️ 降透明度的对象是**图与文字**两段，**不含那枚
      斜向贴图** —— 贴图是「这张卡现在不能成交」的**结论**，它要是也跟着淡下去就白贴了。 */
.pcard.sold {
  background: var(--zone);
}
.pcard.sold .pcard-img img,
.pcard.sold .pcard-tx {
  opacity: 0.55;
}
.pcard-img {
  aspect-ratio: 4 / 3;
  background: var(--img);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  /* ⚠️ 只给 `tag` 那枚小签定位用（它是图上角的一个绝对定位块）。 */
  position: relative;
}
.pcard:hover .pcard-img {
  background: var(--brand-soft);
}
/* ⚠️ `object-fit: cover` + 4/3：三处的图源不保证同比例，不裁就会把格子撑歪。 */
.pcard-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
/* 图上角的小签 —— 朱砂族（`--accent` / `--accent-soft`）。`tokens.css:123` 把朱砂的职责
   写死成「价格 / 促销 / 未读」三样，「新品」属于**促销**那一类，所以它**可以**用这一族，
   而「名次」「分类」那类标记**不可以**（见 `ShopHome.vue` 柱状图的注释）。
   ⚠️ 用 `--accent-soft` 底 + `--accent` 字（不是实心红底白字）：实心红块会把整张商品的
   注意力从「图 + 价」上拽走，而它只是一枚**提示**。同形先例：`ShopHome.vue` 的 `.fact-cert`
   （`--success-ground` + `--success`）。 */
.pcard-tag {
  position: absolute;
  top: var(--s2);
  /* ⚠️ **右上角**（丙方案定稿，2026-10-12 从 `left` 改过来）：用户原话「新品红标签在图片
     右上角」。右下角是价钱那侧、左上角与读图动线的起点撞在一起，右上角是这枚签最不挡货的地方。 */
  right: var(--s2);
  height: 20px;
  line-height: 20px;
  padding: 0 var(--s2);
  border-radius: var(--r1);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  white-space: nowrap;
}
/* 已售完那枚斜向贴图 —— 形制照抄 `SeckillPage.vue:303` 的 `.stamp`（同一件事的第二处用法，
   不另发明一种「售完」的样子）。⚠️ 字号从 `--fs-md` 收到 `--fs-sm`：那边是 96px 宽的图，
   这里是 4/3 的网格卡（≈190px 宽），`--fs-md` 配 `.12em` 字距会把三个字撑到接近图宽。
   ⚠️ 颜色取 `--accent`（朱砂）—— 同 `tokens.css:123` 给它的职责（价格 / 促销 / 未读），
   而「售完」说的正是「促销没了」。 */
.pcard-stamp {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%) rotate(-14deg);
  pointer-events: none;
  user-select: none;
  white-space: nowrap;
  padding: 2px var(--s4);
  border: 2px solid var(--accent);
  border-radius: var(--r1);
  color: var(--accent);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  letter-spacing: 0.12em;
}
.pcard-tx {
  padding: var(--s3) var(--s4) var(--s4);
}
.pcard-name {
  font-size: var(--fs-sm);
  font-weight: var(--fw-reg);
  color: var(--ink);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  /* 两行截断 ⇒ 定高，一行标题与两行标题的卡**同高**（栅格里参差最显眼）。 */
  min-height: 2.9em;
}
/* ── 类别标签行（秒杀 / 国补）—— C3 块 5 ·〈Bug 3〉落码，2026-10-12 ────────────────────
   ⚠️ **位置取原稿 `.card-tags`**（`docs/美化/主页改造.html:515`，名字与价格之间）：那一块此前一直
      「无对应字段」（`HomePage.vue:257` 的按语），本笔字段有了，落回原位。
   ⚠️ 配色**照抄 `GoodsUp.vue:1037-1038` 的 `.kind` 两枚**（商家端「我的商品」早就在画这两个类别）：
      秒杀 = `--cin-100`/`--cin-700`（朱砂族）、国补 = `--brand-tint`/`--brand-ink`（青瓷绿族）——
      **不另造颜色**：用户口令「秒杀红 / 国补绿」与这两族逐字对上，全仓同一个词也就同一种色。
   ⚠️ 与图上角那枚 `tag`（新品）**不是一件事**：那枚在**图**上、判据由调用方给；这两枚在**文字区**、
      判据是后端标记 ⇒ 一张卡可以同时有角签 + 两个类别标签（三枚共存合法）。 */
.pcard-kinds {
  margin-top: var(--s2);
  display: flex;
  gap: var(--s1);
}
.pcard-kind {
  font-style: normal;
  padding: 1px var(--s2);
  border-radius: var(--r1);
  font-size: var(--fs-xs);
  white-space: nowrap;
}
.pcard-kind.seckill {
  background: var(--cin-100);
  color: var(--cin-700);
}
.pcard-kind.subsidy {
  background: var(--brand-tint);
  color: var(--brand-ink);
}
/* 品牌标（批四第二批；2026-10-19 第二步升维）—— 用户明令「去掉浅绿色标签，用香槟金 / 黑金」：
   墨黑底 + 香槟金字 + 发丝金边。⚠️ 这是**唯一**一处越过 tokens 青瓷绿族的颜色，按用户口令落在
   品牌语义上，不扩用到其它标签（秒杀仍朱砂、国补仍青瓷绿）。 */
.pcard-kind.brand {
  background: linear-gradient(135deg, #2b2418 0%, #14110c 100%);
  color: #e8c98a;
  border: 1px solid rgba(201, 162, 90, 0.55);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.12);
}
/* ── 隐形占位签（用户 ①，2026-10-12）────────────────────────────────────────────────
   普通商品（两枚类别标签都不命中）在这一行画的那一枚：**只占高度、不显示**。
   ⚠️ 用 `visibility: hidden` 而不是 `display: none` —— 后者会把盒子整个从版面撤掉，
     高度又没了，等于没修（`opacity: 0` 也能占位，但那个仍留在无障碍树 / 命中测试里）。
   ⚠️ 别的什么都不改（字号 / padding / 文案 / 类名都跟真签一模一样）—— 等高正是从这里来的。 */
.pcard-kind.ghost {
  visibility: hidden;
}
.pcard-foot {
  margin-top: var(--s2);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
}
.pcard-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  color: var(--accent);
  /* 数字列对齐：一列价格左对齐时，`¥` 后面的整数位不同会让小数点跳来跳去（同 `tokens.css:39`）。 */
  font-variant-numeric: tabular-nums;
}
.pcard-price .sym {
  font-size: 13px;
  font-weight: var(--fw-med);
}
.pcard-price .int {
  font-size: 22px;
  font-weight: var(--fw-semi);
  letter-spacing: -0.5px;
}
.pcard-price .dec {
  font-size: 14px;
  font-weight: var(--fw-med);
}
/* 加购：平时幽灵圆钮，**卡片 hover** 才转实心（12 个实心按钮会把货架变成按钮墙）。 */
.pcard-buy {
  flex: none;
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: var(--ink-3);
  border: 1px solid var(--line-2);
  border-radius: 50%;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.pcard:hover .pcard-buy {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.pcard-buy.busy { opacity: .6; cursor: progress; }
.pcard-buy.done,
.pcard:hover .pcard-buy.done {
  background: var(--success);
  border-color: var(--success);
  color: #fff;
}
/* 元信息行：**两端**（左店名 / 右销量）。
   ⚠️ 刻意**不**用 `·` 把两段拼成一句 —— 那是 `frontend-design` SKILL 点名的 AI 默认样
   （「`·` 拼接的元信息串」）；两端对齐天然避开它，且店名是身份、销量是量级，本就是两类信息。 */
.pcard-meta {
  margin-top: var(--s2);
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.pcard-meta .shop {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pcard-meta .sold {
  flex: none;
  font-variant-numeric: tabular-nums;
}
/* 会员价（批四第二批 commit 3）：与整数价同基线的小字，只在本机恢复态是 VIP 时渲染。 */
.pcard-price .m-price { margin-left: 6px; color: var(--brand-ink); font-size: var(--fs-xs); font-weight: var(--fw-med); white-space: nowrap; }
</style>
