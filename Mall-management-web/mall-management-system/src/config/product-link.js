// src/config/product-link.js —— 商品详情页的**链接契约**（C3 块 5 · B 组续笔，2026-10-12）。
//
// 为什么单开一个模块（而不是两处各拼一份 `query`）：
//   这 7 个参数**是一条被断言过的契约**，不是随手拼的跳转 —— `progress.md:1441` 的
//   T-guard-6 ① 逐字写着「7 个 query 参数都要在」，`HomePage.vue:400` 的注释是它的出处。
//   两处各拼一份 ⇒ 第二处漏一个参数**两边都不报错**，只是详情页悄悄少了一条既有判据。
//   房内先例：`src/config/coupon-format.js`（同为「共享的非组件逻辑」）、`config/notify-events.js`。
//
// ⚠️ 本笔（块 5 · ShopHome 布局授权笔）是**第二处消费方**：本店热销的柱状图整列是一个
//   `<router-link>`，它**不走** `components/ProductCard.vue`（那是卡片形制，柱状图不是卡片）
//   ⇒ 链接逻辑必须共用，否则「整列可点」与「卡片可点」会慢慢走成两条。
//
// ⚠️ 参数**顺序**与 `HomePage.vue` 改前那一份**逐字相同**（URL 逐字可比，便于对照）。
//   以后加参数一律**追加在末尾**，别插在中间 —— 顺序一变，所有既有 URL 就全变了。

/**
 * 商品 → 详情页的 `router-link` 目标。
 *
 * @param {Object} goods `goodsSelectHome` 的一行，需有 `Id` / `MerchantId` / `GoodName` /
 *   `Price` / `GoodImg` / `GoodType` / `ProductCode` 七个键
 * @returns {{path: string, query: Object}} 直接交给 `<router-link :to="…">`
 */
export function productLink(goods) {
  return {
    path: '/product',
    query: {
      Id: goods.Id,
      MerchantId: goods.MerchantId,
      GoodName: goods.GoodName,
      Price: goods.Price,
      GoodImg: goods.GoodImg,
      GoodType: goods.GoodType,
      ProductCode: goods.ProductCode
    }
  }
}
