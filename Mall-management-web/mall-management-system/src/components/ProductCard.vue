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
       另两处**多收 3 个**参数是无害的（`ProductPage.vue:465` 只解构 4 个，多出来的不读）。
    ② **副作用不一样**：`HomePage` 的 `toGoodsDetail` 还写 `sessionStorage`（清搜索缓存）
       ⇒ 落成**可选 prop `beforeNav`**，`HomePage` 传自己的那两行；另两处不传。
    ③ **元素不一样**：`HomePage` 是 `<article @click>` + `router.push`，另两处是 `<router-link>`。
       ⇒ 一律用 `<router-link>`（中键 / 新标签页能开，比 `@click` 强），副作用走 ②。
    ④ **HTML 合法性**：`<a>` 内**不得**嵌交互内容，而加购钮是个 `<button>`
       ⇒ 本组件用 `<span class="pcard-buy">`（**恒 `disabled` 的预留件**，本就不是可点物）。
       ⚠️ 这一条是**对 `HomePage` 原写法的修正**，不是照抄 —— 原写法三处里只有它踩了这个。
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

  ⚠️ `tag`（C3 块 5 · ShopHome 布局授权笔，2026-10-12）：图上角的一枚小签，**默认空串 = 不画**。
     本笔**唯一**的消费方是 `ShopHome.vue` 的「本店商品」网格 —— 用户把原先那个独立的
     「新品首发」模块改成了**卡上的红标签**（用户素材原话即「本店商品（含新品红标签）」），
     顺手解掉了上一笔登记的「新品首发与本店商品内容重叠」那条待裁项。
     ⚠️ 「哪几件算新品」的判据**在调用方**（`ShopHome` 的 `isNew`），**不在**本组件 ——
     本组件只管把那枚签画出来，判据只有一处，不会两边各判一套。

  用法：
    <ProductCard :goods="g" />
    <ProductCard :goods="g" :before-nav="onCardNav" />   ← 需要「导航前副作用」的页面才传第二个
    <ProductCard :goods="g" tag="新品" />                 ← 图上角的小标签（本笔只有 ShopHome 传）
-->
<template>
  <router-link class="pcard" :to="to" @click="onNav">
    <div class="pcard-img">
      <!-- ⚠️ `v-if` 不能省 —— 见文件头 ⑤（破图图标 vs 灰底；先例 `HistoricalOrders.vue:66`）。 -->
      <img v-if="goods.GoodImg" :src="goods.GoodImg" :alt="goods.GoodName" />
      <!-- 图上角的小签（当前只有「新品」一种，见文件头）。空串 ⇒ 一个节点都不渲染。 -->
      <span v-if="tag" class="pcard-tag">{{ tag }}</span>
    </div>
    <div class="pcard-tx">
      <div class="pcard-name">{{ goods.GoodName }}</div>
      <div class="pcard-foot">
        <div class="pcard-price">
          <span class="sym">¥</span><span class="int">{{ priceInt }}</span><span class="dec">.{{ priceDec }}</span>
        </div>
        <!-- ⚠️ 预留件（恒不可点）：加购要等 C3〈购物车〉笔；本卡现只进详情页。
             用 <span> 不用 <button>：见文件头 ④（<a> 内不得嵌交互内容）。 -->
        <span class="pcard-buy" title="预留 · 加入购物车（待 C3〈购物车〉；本卡现只进详情页）">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 5h10l-1 8.4a1 1 0 0 1-1 .9H5a1 1 0 0 1-1-.9z" stroke-linejoin="round"/><path d="M6 5V3.9a2 2 0 1 1 4 0V5" stroke-linecap="round"/></svg>
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
import { computed } from 'vue'
import { productLink } from '../config/product-link'

// eslint-disable-next-line no-undef
const props = defineProps({
  goods: { type: Object, required: true },
  // 导航前的副作用（可空）。收 `goods` 参数以便调用方按行决策；见文件头 ②。
  beforeNav: { type: Function, default: null },
  // 图上角的小签（可空）。空串 ⇒ 不画。见文件头。
  tag: { type: String, default: '' }
})

// 7 个 query 参数**不再本文件自拼**，走 `config/product-link.js` 那一份共用契约
// （本笔新增柱状图那处消费方 ⇒ 两处必须同源，见文件头 ① 与那个模块的头注）。
const to = computed(() => productLink(props.goods))

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
})

// `SoldQty` 由 `GoodsCacheService.listHome` 的 LEFT JOIN 补出（本笔同批后端改动）。
// 老响应里没有这个键 ⇒ `undefined || 0` = 0 ⇒ 不画那一段（不画 ≠ 画 0）。
const soldQty = computed(() => Number(props.goods.SoldQty) || 0)
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
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.pcard:hover .pcard-buy {
  background: var(--brand);
  border-color: var(--brand);
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
</style>
