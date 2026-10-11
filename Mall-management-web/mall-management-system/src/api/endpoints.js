// src/api/endpoints.js —— 全仓**唯一**允许出现后端路径字面量的文件
//
// 为什么单列一张表：验收判据因此可以从「localhost 归零」升级成
// 「路径字面量只允许出现在本文件」—— 后者强得多，且同样可 grep 自动化（方案 Q8）。
//
// 端点来源：14 个 view 里 **42 处 fetch + 6 处 URL 赋值**，逐条抽取
// （2026-10-04，切换自 `9add994`）共 **31 个唯一端点**，按 M5 的四域分组（+2 条 `user.me` / `user.logout`：会话恢复笔与翻开关笔 2026-10-05 加，+1 条 `user.token`：多账户隔离笔 2026-10-06 加 —— 三条均**非** view 抽取 ⇒ 加上 C3 批 A 的 3 条 `admin.audit*`（2026-10-08）与 C3 块 1·I2 的 8 条（`admin.notify*` 4 + `notification.*` 4，2026-10-09，同样**非** view 抽取），再加 C3 块 2〈用户块〉的 11 条（`user.updateProfile` 1 + `merchant.*` 2 + `admin.accounts*` 8，2026-10-09，同样**非** view 抽取），本表实为 **56** 条；再加 C3 块 3〈商品块〉的 14 条（`goodsB3` 2 + `catalog` 1 + `catalogAdmin` 11，2026-10-10，同样**非** view 抽取）；再经同日〈模板表通用化〉就地改造（**删** `catalog` 1 + `catalogAdmin` 4 + `admin.notifyTemplate*` 2 = 7 条，**加** `template` 2 + `templateAdmin` 4 = 6 条），现为 **69** 条；再加 C3 块 4〈订单块〉的 9 条（`cart` 5 + `orderAdmin` 4，2026-10-10，同样**非** view 抽取 —— 唯一调用方是新建的 `api/order.js`），现为 **78** 条；再加 C3 块 4 补丁〈订单块〉的 1 条（`order.orderDetail`，2026-10-10，同样**非** view 抽取），现为 **79** 条；再加可视化段（2026-10-11）的 4 条（`goods.skuList` 1〔与 `goodsProductCodeSelect` 同行追加〕+ `stats.*` 3〔末尾新增组〕，同样**非** view 抽取），现为 **83** 条）。
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
    goodsProductCodeSelect: '/goods/GoodsProductCodeSelect', skuList: '/goods/skuList',   // ⚠️ 2026-10-11（可视化段补丁）`skuList` **同行追加**，不新插一行 —— 本组下方各条被 view 与 `slot-registry.md` 按行号引，插行会整体下移。
  },
  user: {
    loginSelect: '/user/loginSelect',
    loginAdd: '/user/loginAdd',
    getUserInfo: '/user/getUserInfo',
    updateAvatar: '/user/updateAvatar',
    updateUserInfo: '/user/updateUserInfo', updateProfile: '/user/updateProfile',   // ⚠️ 2026-10-09（C3 块 2〈用户块〉）：**同行**追加，不新插一行 —— 本组下面那两条 `order.*`（`:56` / `:58`）被 `views/OrderCenter.vue` 与 `docs/agent/slot-registry.md` 按行号引，在本组内插行会让它们整体下移（文件头 `:68` 那条「上半部一行都不能动」是同一件事）。⚠️ `updateProfile` 与 `updateUserInfo` 是**两个端点**（前者改 `gender`/`age`，后者改 `email`/`address`/`selfintroduction`），**别合并**
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
    orderDetail: '/order/orderDetail',   // ⚠️ 2026-10-10（C3 块 4 补丁，买家订单详情 A5）：**同行**追加在既有末行之后，不新插一行 —— 本组 `:56` / `:58` 被 `views/OrderCenter.vue` 与 `docs/agent/slot-registry.md` 按行号引（同 `:28` 的 `updateProfile` 那条口径）。
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
    // ⚠️ 2026-10-09（C3 块 1 · I2 · 系统通知）：管理端**发送 / 历史** 2 条。
    //    与上面 `admin.audit*` 同款（**非** view 抽取；唯一调用方是 `views/admin/NotifyPage.vue`，
    //    经 `api/admin.js` 包一层）。⚠️ 同样**不给角色闸留位置** —— 后端这 2 条也没有角色判据
    //    （TD-18，见 `AdminNotificationController` 块首）。
    // ⚠️ 原有第三条「模板」`notifyTemplateList` / `notifyTemplateSave`（原 80-81 行）**已删** ——
    //    2026-10-10 模板表通用化：通知模板并进 `template` 表（`Type = notification.<原 TemplateKey>`），
    //    存取改走本文件尾的 `template` / `templateAdmin` 两组；后端 `/admin/notify/template/*` 同时下线。
    notifySend: '/admin/notify/send',
    notifyHistory: '/admin/notify/history',
  },
  // ⚠️ 2026-10-09（C3 块 1 · I2 · 系统通知）：**接收**侧 4 条 —— **三端共用**（⚠️ 原文写「管理端没有收件箱」—— **已被 C3 块 2〈用户块〉改判**：管理端也有一个共享收件箱，
  //    收 `ToId='*'` / `ToType='admin'` 的入驻申请通知，调用点是 `views/admin/AccountsPage.vue`），故与 `admin` 组**分开**：前缀是 `/notification`（**公共层**，
  //    无 `/admin`），分组名也就不能叫 `admin`。调用方 = `api/notify.js`（两端壳 + `NotifyInbox.vue`）。
  // ⚠️ 后端这 4 条**带 `IdentityGuard`**（`requireSelfOrAdmin`）⇒ 传别人的 `toId` 是 403，
  //    而**不是**「查不到」—— 与 `message` 域那几条同形态。
  notification: {
    list: '/notification/list',
    unreadCount: '/notification/unreadCount',
    read: '/notification/read',
    readAll: '/notification/readAll',
  },
  // ⚠️ 2026-10-09（C3 块 2 · 用户块）：**商家自服务** 2 条。调用方 = `api/merchant.js`，
  //    唯一页面 `views/MerchantApplyPage.vue`（`/customer/user/apply`）。
  //    ⚠️ 它挂在**用户端**壳下、**不在**商家端：提交入驻申请的人**还没有** `merchantid`
  //    （`MerchantApplicationController` 块首那条）⇒ 放进商家壳等于要求「先有商家身份才能申请当商家」。
  //    ⚠️ 后端两条都跑 `IdentityGuard.requireSelfOrAdmin(request, username)`（方法体第一行）
  //    ⇒ 传**别人的** `username` 是 **403**（不是「查不到」）。
  merchant: {
    apply: '/merchant/apply',
    myApplications: '/merchant/myApplications',
  },
  // ⚠️ 2026-10-09（C3 块 2 · 用户块）：管理端**用户治理 / 商家审核 / 降级控制** 8 条。
  //    ⚠️ 与上面 `admin` 组**同前缀**（`/admin/accounts/*`）、同款（**非** view 抽取；唯一调用方
  //    `views/admin/AccountsPage.vue`，经 `api/accounts.js` 包一层）。
  //    ⚠️ **单列一组而不是塞进 `admin` 组**：往 `admin` 组里插行会把本文件下面的条目整体下移，
  //    而「新增行落在被引行之后」是本仓唯一的零漂移手法（`admin` / `notification` 两组当年
  //    也是这么追加的）。⇒ 追加在**文件尾**。
  //    ⚠️ 同样**不给角色闸留位置** —— 后端这 8 条也没有角色判据（TD-18：管理员 cookie 铸不出票，
  //    见 `AdminAccountsController` 块首与 `slot-registry.md` §十三 13-5）。
  accounts: {
    users: '/admin/accounts/users',
    ban: '/admin/accounts/user/ban',
    unban: '/admin/accounts/user/unban',
    applications: '/admin/accounts/applications',
    audit: '/admin/accounts/application/audit',
    downgradeSchedule: '/admin/accounts/downgrade/schedule',
    downgradeCancel: '/admin/accounts/downgrade/cancel',
    downgradeRelist: '/admin/accounts/downgrade/relist',
  },
  // ⚠️ 2026-10-10（C3 块 3 · 商品块）：**商品**域补 2 条。⚠️ **不塞进上面的 `goods` 组** ——
  //    那组被 `docs/agent/slot-registry.md` 按行号引，在组内插行会整体下移。⇒ 追加在**文件尾**。
  //    ⚠️ 两条都不是「改既有端点的参数面」：`categoryGoods` 是**新端点**（二级筛选按 `TypeId`），
  //    `myApplications` 也是新的（商家查自己的提审历史）⇒ 既有调用方一个都不受影响。
  goodsB3: {
    categoryGoods: '/goods/categoryGoods',
    myApplications: '/goods/myApplications',
  },
  // ⚠️ 2026-10-10（C3 块 3 · 商品块）：**模板库公共读** 1 条 —— 挂 `/catalog`（新域）。
  //    调用方横跨三端：`GoodsAdd.vue`（上架选模板）、`ProductPage.vue`（详情页渲染）。
  //    ⚠️ **类目树不在这里** —— 类目树的读沿用既有 `/goods/goodstype`（见 `goods.goodstype`）。
  // ⚠️ 2026-10-10 模板表通用化：上面那组 `catalog: { templates: '/catalog/templates' }` **已整组删**
  //    （后端 `CatalogController` 一并下线）—— 公共读改走本文件尾的 `template` 组。
  // ⚠️ 2026-10-10（C3 块 3 · 商品块）：管理端**商品审核 / 类目 / 违规** 7 条
  //    （`@RequestMapping("/admin/catalog")`）。同 `admin` / `accounts` 两款（**非** view 抽取；
  //    唯一调用方 `views/admin/CatalogPage.vue`，经 `api/admin.js` 包一层）。
  //    ⚠️ 同样**不给角色闸留位置** —— 后端这 7 条也没有角色判据（TD-18）。
  //    ⚠️ 原有第 4 项「模板」`templateList/Create/Update/Delete`（原 146-149 行）**已删** ——
  //       通用化后改走本文件尾的 `templateAdmin` 组；后端 `/admin/catalog/template/*` 同时下线。
  catalogAdmin: {
    applications: '/admin/catalog/applications',
    audit: '/admin/catalog/application/audit',
    categoryCreate: '/admin/catalog/category/create',
    categoryUpdate: '/admin/catalog/category/update',
    categoryDelete: '/admin/catalog/category/delete',
    violationDelist: '/admin/catalog/violation/delist',
    violationDelete: '/admin/catalog/violation/delete',
  },
  // ⚠️ 2026-10-10（模板表通用化）：**通用模板**公共读 2 条 —— `Type` 区分用途
  //    （`goods.detail` / `goods.spec` / `goods.afterSale` / `goods.reject` / `merchant.reject` /
  //    `merchant.approve` / `user.ban` / `notification.*` / `comment.reply`）。**非** view 抽取。
  //    调用方横跨三端：`TemplatePicker.vue`（各输入框旁「选模板」弹窗）、
  //    `ProductPage.vue`（详情页渲染快照文本）、`GoodsAdd.vue`（上架选模板）。
  //    ⚠️ **类目树不在这里** —— 类目树的读沿用既有 `/goods/goodstype`（见 `goods.goodstype`）。
  //    ⚠️ 后端这两条**不校验未知 `Type`**：未知 type 回空列表（`success:true`），不是错误。
  template: {
    list: '/template/list',
    get: '/template/get',
  },
  // ⚠️ 2026-10-10（模板表通用化）：管理端**模板 CRUD** 4 条（`@RequestMapping("/admin/template")`）。
  //    同 `admin` / `catalogAdmin` 两款（**非** view 抽取；调用方 `views/admin/CatalogPage.vue` 与
  //    `views/admin/NotifyPage.vue`，经 `api/template.js` 包一层）。⚠️ 同样**不给角色闸留位置**
  //    （TD-18）。⚠️ `update` **不收 `Type`** —— 模板的 type 落库即定，不允许改（改了等于换了一个模板）。
  templateAdmin: {
    list: '/admin/template/list',
    create: '/admin/template/create',
    update: '/admin/template/update',
    delete: '/admin/template/delete',
  },
  // ⚠️ 2026-10-10（C3 块 4 · 订单块）：**购物车域** 5 条（`@RequestMapping("/cart")`，A1 甲新建的
  //    `cart` 表）。**非** view 抽取（唯一调用方是新建的 `api/order.js`，再经
  //    `views/ShoppingCart.vue` 与 `views/ProductPage.vue`）。
  //    ⚠️ 这**不是**「既有 8 个 `order.*` 端点的替代品」—— 「我的订单」tab 用的仍是那 8 条里的
  //    `orderSelect` / `historicalOrdersSelect`；本组只管**真购物车**那张表。
  //    ⚠️ 后端 5 条**每条都跑** `IdentityGuard.requireSelfOrAdmin(request, CustomerId)`
  //    ⇒ 传**别人的** `CustomerId` 是 **403**（不是「查不到」），与 `notification` 那 4 条同形态。
  cart: {
    add: '/cart/add',
    update: '/cart/update',
    remove: '/cart/remove',
    list: '/cart/list',
    checkout: '/cart/checkout',
  },
  // ⚠️ 2026-10-10（C3 块 4 · 订单块）：管理端**订单治理** 4 条（`@RequestMapping("/admin/order")`）。
  //    同 `admin` / `accounts` / `catalogAdmin` / `templateAdmin` 各款（**非** view 抽取；唯一调用方
  //    `views/admin/OrderPage.vue`，经 `api/order.js` 包一层）。
  //    ⚠️ **单列一组而不是塞进 `order` 组**：往 `order` 组里插行会把本文件下面的条目整体下移，
  //    而「新增行落在被引行之后」是本仓唯一的零漂移手法（同 `accounts` / `goodsB3` / `template`）。
  //    ⚠️ 同样**不给角色闸留位置** —— 后端这 4 条也没有角色判据（TD-18：管理员 cookie 铸不出票，
  //    见 `AdminOrderController` 块首）。
  //    ⚠️ 只有 `intervene` 是写端点 ⇒ 只有它写 `admin_audit`；另三条是读，不留痕。
  orderAdmin: {
    list: '/admin/order/list',
    detail: '/admin/order/detail',
    monitor: '/admin/order/monitor',
    intervene: '/admin/order/intervene',
  },
  // ⚠️ 可视化段 commit 2（2026-10-11）：**经营数据看板** 3 条
  //    （`AdminStatsController` = `/admin/stats` ×2；`MerchantStatsController` = `/merchant/stats`）。
  //    单列一组、**追加在文件尾**（同 `orderAdmin` 的理由：往别的组里插行会把本文件下面的条目整体下移，
  //    而「新增行落在被引行之后」是本仓唯一的零漂移手法）。
  //    ⚠️ 三条**全是 POST**：方案 §五 只给了路径、没给动词，落码时按「本仓管理端读端点**全是** POST」
  //    统一（`/admin/order/list` · `/admin/audit/list` · `/admin/catalog/*` …）——
  //    理由见 `AdminStatsController` 块首「动词选 POST」那节。**别照 REST 习惯改成 GET。**
  stats: {
    admin: '/admin/stats',
    adminRebuild: '/admin/stats/rebuild',
    merchant: '/merchant/stats',
  },
}
