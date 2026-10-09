// src/api/endpoints.js —— 全仓**唯一**允许出现后端路径字面量的文件
//
// 为什么单列一张表：验收判据因此可以从「localhost 归零」升级成
// 「路径字面量只允许出现在本文件」—— 后者强得多，且同样可 grep 自动化（方案 Q8）。
//
// 端点来源：14 个 view 里 **42 处 fetch + 6 处 URL 赋值**，逐条抽取
// （2026-10-04，切换自 `9add994`）共 **31 个唯一端点**，按 M5 的四域分组（+2 条 `user.me` / `user.logout`：会话恢复笔与翻开关笔 2026-10-05 加，+1 条 `user.token`：多账户隔离笔 2026-10-06 加 —— 三条均**非** view 抽取 ⇒ 加上 C3 批 A 的 3 条 `admin.audit*`（2026-10-08）与 C3 块 1·I2 的 8 条（`admin.notify*` 4 + `notification.*` 4，2026-10-09，同样**非** view 抽取），本表实为 **45** 条）。
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
    // ⚠️ 2026-10-05（翻开关笔）：退出时销**服务端会话**用（闸开之后才有意义 —— 闸关时
    //    它一直被 `loginSelect` 写、从未被销）。同样**非** view 抽取（唯一调用方是
    //    `UserCenter.vue:52` 的 `toLogin`）。**不在白名单** ⇒ 未登录时调它**必 401**
    //    ⇒ 调用点必须带 `allowUnauthorized: true`。
    logout: '/user/logout',
    // ⚠️ 2026-10-06（多账户隔离笔）：铸票。**非** view 抽取。两个调用点：`LoginPage.vue` 登录成功后、
    //    `main.js` 的 cookie 恢复补票。⚠️ 只在**授权口径**拿得到身份时可用 —— 管理员 cookie 换不到票
    //    （后端 `UserController.token` 判的就是这一条），故调用点一律带 `allowUnauthorized: true` 自兜。
    token: '/user/token',
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
  // ⚠️ 2026-10-08（C3 批 A · 权限与审计）：3 条**管理端只读**端点。**非** view 抽取
  //    （唯一调用方是 `views/admin/AuditPage.vue`，经 `api/admin.js` 包一层）——
  //    与 `user.me` / `user.logout` / `user.token` 同款，故不计入上面那句 view 抽取口径。
  // ⚠️ 后端前缀 `/admin` 与前端路由 `/admin/observe/audit` **不同名，也不该同名**：
  //    前端路由说「页面在哪」，后端前缀说「资源在哪」（见 `AdminAuditController` 块首）。
  // ⚠️ 本段**追加在文件尾**：文件头那句「本表实为 N 条」是同行改的，且 `endpoints.js:26`
  //    （`user.getUserInfo`）被 `progress.md:572` / `:622` 按行号引用 ⇒ **上半部一行都不能动**。
  admin: {
    auditList: '/admin/audit/list',
    auditDetail: '/admin/audit/detail',
    auditFacets: '/admin/audit/facets',
    // ⚠️ 2026-10-09（C3 块 1 · I2 · 系统通知）：管理端**发送 / 历史 / 模板** 4 条。
    //    与上面 `admin.audit*` 同款（**非** view 抽取；唯一调用方是 `views/admin/NotifyPage.vue`，
    //    经 `api/admin.js` 包一层）。⚠️ 同样**不给角色闸留位置** —— 后端这 4 条也没有角色判据
    //    （TD-18，见 `AdminNotificationController` 块首）。
    notifySend: '/admin/notify/send',
    notifyHistory: '/admin/notify/history',
    notifyTemplateList: '/admin/notify/template/list',
    notifyTemplateSave: '/admin/notify/template/save',
  },
  // ⚠️ 2026-10-09（C3 块 1 · I2 · 系统通知）：**接收**侧 4 条 —— **三端共用**（管理端没有收件箱，
  //    但客户 / 商家两壳走同一套端点），故与 `admin` 组**分开**：前缀是 `/notification`（**公共层**，
  //    无 `/admin`），分组名也就不能叫 `admin`。调用方 = `api/notify.js`（两端壳 + `NotifyInbox.vue`）。
  // ⚠️ 后端这 4 条**带 `IdentityGuard`**（`requireSelfOrAdmin`）⇒ 传别人的 `toId` 是 403，
  //    而**不是**「查不到」—— 与 `message` 域那几条同形态。
  notification: {
    list: '/notification/list',
    unreadCount: '/notification/unreadCount',
    read: '/notification/read',
    readAll: '/notification/readAll',
  },
}
