// src/api/endpoints.js —— 全仓**唯一**允许出现后端路径字面量的文件
//
// 为什么单列一张表：验收判据因此可以从「localhost 归零」升级成
// 「路径字面量只允许出现在本文件」—— 后者强得多，且同样可 grep 自动化（方案 Q8）。
//
// 端点来源：14 个 view 里 **42 处 fetch + 6 处 URL 赋值**，逐条抽取
// （2026-10-04，切换自 `9add994`）共 **31 个唯一端点**，按 M5 的四域分组（+1 条 `user.me`：会话恢复笔 2026-10-05 加、**非** view 抽取 ⇒ 本表实为 **32** 条）。
//
// ⚠️ 路径里的 `GoodsProductCodeSelect` 是**大写开头**，与后端 `@PostMapping` 逐字一致，
//    不是笔误 —— 改名会 404。
export const endpoints = {
  goods: {
    goodstype: '/goods/goodstype',
    goodsAdd: '/goods/goodsAdd',
    goodsSelect: '/goods/goodsSelect',
    goodsSelectHome: '/goods/goodsSelectHome',
    goodsDe: '/goods/goodsDe',
    goodsUp: '/goods/goodsUp',
    productCodePrice: '/goods/productCodePrice',
    productSearch: '/goods/productSearch',
    goodsProductCodeSelect: '/goods/GoodsProductCodeSelect',
  },
  user: {
    loginSelect: '/user/loginSelect',
    loginAdd: '/user/loginAdd',
    getUserInfo: '/user/getUserInfo',
    updateAvatar: '/user/updateAvatar',
    updateUserInfo: '/user/updateUserInfo',
    // ⚠️ 2026-10-05（会话恢复笔）：启动探针读 Cookie 会话用。**非** view 抽取 ⇒
    //    不影响上面那句「31 个唯一端点」的抽取口径（那句指的是 view 里抽出来的那些）。
    me: '/user/me',
  },
  message: {
    userSelect: '/message/userSelect',
    conversations: '/message/conversations',
    history: '/message/history',
    send: '/message/send',
    friendSelect: '/message/friendSelect',
    friendAdd: '/message/friendAdd',
    selectFriend: '/message/selectFriend',
    friendUp: '/message/friendUp',
    friendDe: '/message/friendDe',
  },
  order: {
    orderAdd: '/order/orderAdd',
    orderSelect: '/order/orderSelect',
    orderPayIfUp: '/order/orderPayIfUp',
    orderReceiveIfUp: '/order/orderReceiveIfUp',
    orderDeliveryIfUp: '/order/orderDeliveryIfUp',
    orderDeliveryIfSelect: '/order/orderDeliveryIfSelect',
    orderDeliverySelect: '/order/orderDeliverySelect',
    historicalOrdersSelect: '/order/historicalOrdersSelect',
  },
}
