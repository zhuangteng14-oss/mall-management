// src/api/endpoints.js —— 全仓**唯一**允许出现后端路径字面量的文件
//
// 为什么单列一张表：验收判据因此可以从「localhost 归零」升级成
// 「路径字面量只允许出现在本文件」—— 后者强得多，且同样可 grep 自动化（方案 Q8）。
//
// 端点来源：14 个 view 里 **42 处 fetch + 6 处 URL 赋值**，逐条抽取
// （2026-10-04，切换自 `9add994`）共 **31 个唯一端点**，按 M5 的四域分组（+2 条 `user.me` / `user.logout`：会话恢复笔与翻开关笔 2026-10-05 加，+1 条 `user.token`：多账户隔离笔 2026-10-06 加 —— 三条均**非** view 抽取 ⇒ 加上 C3 批 A 的 3 条 `admin.audit*`（2026-10-08）与 C3 块 1·I2 的 8 条（`admin.notify*` 4 + `notification.*` 4，2026-10-09，同样**非** view 抽取），再加 C3 块 2〈用户块〉的 11 条（`user.updateProfile` 1 + `merchant.*` 2 + `admin.accounts*` 8，2026-10-09，同样**非** view 抽取），本表实为 **56** 条；再加 C3 块 3〈商品块〉的 14 条（`goodsB3` 2 + `catalog` 1 + `catalogAdmin` 11，2026-10-10，同样**非** view 抽取）；再经同日〈模板表通用化〉就地改造（**删** `catalog` 1 + `catalogAdmin` 4 + `admin.notifyTemplate*` 2 = 7 条，**加** `template` 2 + `templateAdmin` 4 = 6 条），现为 **69** 条；再加 C3 块 4〈订单块〉的 9 条（`cart` 5 + `orderAdmin` 4，2026-10-10，同样**非** view 抽取 —— 唯一调用方是新建的 `api/order.js`），现为 **78** 条；再加 C3 块 4 补丁〈订单块〉的 1 条（`order.orderDetail`，2026-10-10，同样**非** view 抽取），现为 **79** 条；再加可视化段（2026-10-11）的 4 条（`goods.skuList` 1〔与 `goodsProductCodeSelect` 同行追加〕+ `stats.*` 3〔末尾新增组〕，同样**非** view 抽取），现为 **83** 条）；再加 C3 块 5〈营销块〉commit 1（券，2026-10-11）的 10 条（`marketingCoupon` 7 + `marketing` 3，同样**非** view 抽取 —— 唯一调用方是新建的 `api/marketing.js`），现为 **93** 条；再加同日〈券的信息架构重排〉的 1 条（`marketing.claimBatch` = `/marketing/coupon/claimBatch`，2026-10-11，同样**非** view 抽取 —— 仍是 `api/marketing.js` 的调用方），现为 **94** 条；再加 C3 块 5〈营销块〉B 组 + 续笔（店铺域，2026-10-12）的 4 条（`shop` 组：`follow` / `unfollow` / `followState` / `profile`，同样**非** view 抽取 —— 新建的 `api/shop.js`），现为 **98** 条；再加 C3 块 5〈营销块〉commit 3（国补，2026-10-12）的 9 条（`marketingSubsidy` 6 + `subsidy` 3，同样**非** view 抽取 —— 管理端六条走 `api/marketing.js`，商家侧三条里的两条给 `views/NotifyInbox.vue`、第三条给详情页 / 搜索页的国补标签），现为 **107** 条；再加 C3 块 5〈营销块〉commit 4（秒杀，2026-10-12）的 12 条（`marketingSeckill` 6 + `seckill` 6，同样**非** view 抽取 —— 管理端六条与商家侧四条经 `api/marketing.js` 包一层，`live` 给主页秒杀模块），现为 **119** 条；再加 C3 块 6〈内容块〉commit 1（评论，2026-10-12）的 5 条（`comment` 组，同样**非** view 抽取 —— 商品页 `ProductPage.vue` 直调 + 新建的 `api/comment.js`），现为 **124** 条；再加 C3 块 6〈内容块〉commit 2（问答，2026-10-12）的 1 条（`comment.accept`，同样**非** view 抽取 —— 仍是 `api/comment.js` 的调用方），现为 **125** 条；再加 C3 块 6 整改 · commit 4（举报 + 图片审核，2026-10-13）的 4 条（`comment.imageReview` 1 + 新组 `report` 3，同样**非** view 抽取 —— `imageReview` 经 `api/comment.js`、三条 report 经新建的 `api/report.js`），现为 **129** 条；再加 C3 块 6 整改 · commit 5（敏感词，2026-10-13）的 3 条（`sensitive` 组，同样**非** view 抽取 —— 经新建的 `api/sensitive.js`，其唯一调用方是管理端「敏感词库」tab），现为 **132** 条；再加 C3 块 6〈内容块〉整改补笔（管理端全站列表，2026-10-13）的 1 条（`comment.adminList`，同样**非** view 抽取 —— 仍是 `api/comment.js` 的调用方，其唯一调用方是管理端「评论管理 / 问答管理」两栏），现为 **133** 条；再加 C3 块 7〈可观测域〉· commit 3（3 条 **SSE** 流端点，2026-10-13）的 3 条（`observe` 组：`metrics` / `orders` / `events`，同样**非** view 抽取 —— 经新建的 `api/observe.js` 包一层，调用方是块 7 的三块面板〔commit 5–8〕；再加 C3 块 7〈可观测域〉· commit 4（**攻击引擎**，2026-10-14）的 3 条（同属 `observe` 组：`attackScenarios` / `attackRun` / `attackRecords`，同样**非** view 抽取 —— 仍是 `api/observe.js` 的调用方，其唯一调用方是块 7 commit 5 的攻击模拟器页；⚠️ 这三条是 **POST + 表单串**，与同组上面三条 SSE 的 `streamPost` 不同路），现为 **139** 条；再加 C3 块 7〈可观测域〉· commit 8b（**事件台账 + 清理**，2026-10-14）的 1 条（同属 `observe` 组：`attackCleanup` = `/admin/observe/attack/cleanup`，同样**非** view 抽取 —— 仍是 `api/observe.js` 的调用方，其唯一调用方是块 7 commit 5 的攻击模拟器页〔⚠️ **本笔只登记路径 + 备好 `api/observe.js#cleanupAttack` 这一层**，页面上那枚按钮**不在本笔范围**〕；⚠️ 它是本表**第一条破坏性端点**〔后端真 DELETE，按 `SIM-%` 前缀 OR `Source='attack'` 两种谓词删〕，调用点必须二次确认）；再加 C3 块 7〈可观测域〉· commit 10（**攻击引擎 v2**，2026-10-15）的 1 条（同属 `observe` 组：`attackStop` = `/admin/observe/attack/stop`，同样**非** view 抽取 —— 仍是 `api/observe.js` 的调用方；⚠️ 它是「暂停一轮演练」的**唯一**出口：后端只置令旗 + 排空在途，**不落审计**〔一轮没跑完就没有可落的记录〕）；再加 C3 块 7〈可观测域〉· **块 7 收尾**（**实时进度**，2026-10-15）的 1 条（同属 `observe` 组：`attackStatus` = `/admin/observe/attack/status`，同样**非** view 抽取 —— 仍是 `api/observe.js` 的调用方，其唯一调用方是 `AttackConsole.vue` 的辅助条〔`running` 期间 1 秒一次轮询〕；⚠️ 本表**第一条「只读但仍走 POST」**的管理端端点，与本域另外五条逐字同形），现为 **142** 条；再加 C3 块 7〈可观测域〉· **块 7 收尾**（**演练回放**，2026-10-15）的 1 条（同属 `observe` 组：`replay` = `/admin/observe/replay`，同样**非** view 抽取 —— 仍是 `api/observe.js` 的调用方，其唯一调用方是 `AttackConsole.vue` 演练记录那一行的〔回放〕钮；⚠️ 它是本表**第一条路径不落在既有控制器映射之下**的 `observe` 端点 —— 路径挂在 `/admin/observe`（根），后端是新建的 `ReplayController`，**不是** `AttackController` 的第七条），现为 **144** 条，其中 2026-10-17 新增 `metricsHistory` = `/admin/observe/metrics/history`（指标页首屏历史，只读 POST）；再加 2026-10-18 的 `attackReset` = `/admin/observe/attack/reset`（复位演练环境，普通 POST、无入参、改种子行故写审计），现为 **148** 条（2026-10-19 批四第二批 · 会员 VIP 追加 `memberB4` 3 条：`/user/member/open`、`/admin/accounts/user/member`、`/marketing/coupon/daily`）。
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
  // ⚠️ C3 块 5（营销块 · commit 1 券，2026-10-11）：管理端**券治理** 7 条
  //    （`@RequestMapping("/admin/marketing/coupon")`）。同 `orderAdmin` / `accounts` / `catalogAdmin`
  //    各款（**非** view 抽取；唯一调用方 `views/admin/MarketingPage.vue`，经 `api/marketing.js` 包一层）。
  //    ⚠️ **单列一组而不是塞进别的组**：往任何组里插行都会把本文件下面的条目整体下移，
  //    而「新增行落在被引行之后」是本仓唯一的零漂移手法（同 `accounts` / `goodsB3` / `template`）。
  //    ⚠️ 同样**不给角色闸留位置** —— 后端这 7 条也没有角色判据（TD-18：管理员 cookie 铸不出票）。
  //    ⚠️ 只有 `create` / `stop` / `activate` / `grant` 是写端点 ⇒ 只有它们写 `admin_audit`；
  //    `list` / `grants` / `stats` 是读，不留痕。
  marketingCoupon: {
    list: '/admin/marketing/coupon/list',
    create: '/admin/marketing/coupon/create',
    stop: '/admin/marketing/coupon/stop',
    activate: '/admin/marketing/coupon/activate',
    grants: '/admin/marketing/coupon/grants',
    grant: '/admin/marketing/coupon/grant',
    stats: '/admin/marketing/coupon/stats',
  },
  // ⚠️ C3 块 5（营销块 · commit 1 券，2026-10-11）：**用户端**领券 3 条（`@RequestMapping("/marketing")`）。
  //    ⚠️ 与上一组**同前缀不同族**（`/admin/...` vs `/marketing/...`）⇒ 分组名不能也叫 `marketingCoupon`。
  //    ⚠️ 三条**每条都跑** `IdentityGuard.requireSelfOrAdmin(request, Account)`
  //    ⇒ 传**别人的** `Account` 是 **403**（不是「查不到」），与 `notification` / `cart` 同形态。
  marketing: {
    coupons: '/marketing/coupons',
    myCoupons: '/marketing/myCoupons',
    claim: '/marketing/coupon/claim',
    // ⚠️ 一键领取「本周全部」—— **不吃 CouponId**：集合由服务端定（「本周」是服务端语义，
    //    `CouponDao.THIS_WEEK`）⇒ 前端不传 ID 列表，也就没有「把定义抄到前端」的机会。
    claimBatch: '/marketing/coupon/claimBatch',
  },
  // ⚠️ C3 块 5（营销块 · B 组 · commit C + 续笔，2026-10-12）：**店铺域** 4 条
  //    （`ShopFollowController` 三条 + `ShopProfileController` 一条，都是 `@RequestMapping("/shop")`）。
  //    ⚠️ **单列一组、追加在对象尾** —— 同 `stats` / `marketingCoupon` / `marketing` 各款（往某个组里
  //    插行会把本文件下面的条目整体下移，而「新增行落在被引行之后」是本仓唯一的零漂移手法）。
  //    唯一调用方 = 新建的 `api/shop.js`（**非** view 抽取）。
  //    ⚠️ 四条**全是 POST**：后端两个 Controller 的四个方法都是 `@PostMapping`。
  //    ⚠️ **关注三条**各自跑 `IdentityGuard.requireSelfOrAdmin(request, CustomerId)`
  //    ⇒ 传**别人的** `CustomerId` 是 **403**（不是「查不到」），与 `marketing` / `cart` / `notification` 同形态。
  //    ⚠️ 关注三条**不在** `WebAuthConfig.AUTH_WHITELIST` 里 ⇒ 匿名调它必 401。调用方
  //    （`ShopHome.vue`）因此**只在已登录时才发**这三条 —— 判据写在 `api/shop.js` 文件头。
  //    ⚠️ 续笔（2026-10-12）加的第 4 条 `profile` 是**例外**：它是**公开面**，已进白名单
  //    （**12 → 13 条**），未登录也能打、也**不跑** `IdentityGuard`。理由见 `api/shop.js` 文件头。
  shop: {
    follow: '/shop/follow',
    unfollow: '/shop/unfollow',
    followState: '/shop/followState',
    // 店铺公开档案（登录名 / 注册时刻 / 入驻审核状态）。⚠️ 与上三条不同族：**公开、无闸**。
    profile: '/shop/profile',
  },
  // ⚠️ C3 块 5（营销块 · commit 3 国补，2026-10-12）：管理端**国补治理** 6 条
  //    （`AdminSubsidyController`，`@RequestMapping("/admin/marketing/subsidy")`）。
  //    同 `marketingCoupon` 各款（**非** view 抽取；唯一调用方 `views/admin/MarketingPage.vue`，
  //    经 `api/marketing.js` 包一层）。**单列一组、追加在对象尾**（往任何组里插行都会把本文件
  //    下面的条目整体下移，而「新增行落在被引行之后」是本仓唯一的零漂移手法）。
  //    ⚠️ 同样**不给角色闸留位置** —— 后端这 6 条也没有角色判据（TD-18：管理员 cookie 铸不出票）。
  //    ⚠️ 只有 `create` / `stop` / `activate` / `nominate` 是写端点 ⇒ 只有它们写 `admin_audit`；
  //    `list` / `nominations` 是读，不留痕。
  marketingSubsidy: {
    list: '/admin/marketing/subsidy/list',
    create: '/admin/marketing/subsidy/create',
    stop: '/admin/marketing/subsidy/stop',
    activate: '/admin/marketing/subsidy/activate',
    nominate: '/admin/marketing/subsidy/nominate',
    nominations: '/admin/marketing/subsidy/nominations',
  },
  // ⚠️ C3 块 5（营销块 · commit 3 国补，2026-10-12）：**商家侧 + 用户侧** 3 条
  //    （`SubsidyController`，`@RequestMapping("/marketing")`）。
  //    ⚠️ 与 `marketing`（券）**同前缀、不同路径段**（`/marketing/subsidy/*`）⇒ 单列一组，
  //        组名不能也叫 `marketing`。四条券端点一行没动，本组是**纯追加**。
  //    ⚠️ 前两条各跑 `IdentityGuard.requireSelfOrAdmin(request, MerchantId)` ⇒ 传**别人的**
  //        `MerchantId` 是 **403**（不是「查不到」），与 `marketing` / `order` 同形态。
  //    ⚠️ 第三条 `goods` **不挂 IdentityGuard**，但它**也不在 `AUTH_WHITELIST` 里**
  //        ⇒ 未登录必 401（「登录可见、匿名不可见」）。判据与代价记在 `api/marketing.js` 文件头。
  subsidy: {
    invitations: '/marketing/subsidy/invitations',
    confirm: '/marketing/subsidy/confirm',
    goods: '/marketing/subsidy/goods',
  },
  // ⚠️ C3 块 5（营销块 · commit 4 秒杀，2026-10-12）：管理端**秒杀治理** 6 条
  //    （`AdminSeckillController`，`@RequestMapping("/admin/marketing/seckill")`）。
  //    同 `marketingCoupon` / `marketingSubsidy` 各款（**非** view 抽取；唯一调用方
  //    `views/admin/MarketingPage.vue`，经 `api/marketing.js` 包一层）。**单列一组、追加在对象尾**。
  //    ⚠️ 秒杀是**双向制**：本组 6 条是管理端那一半（邀请 / 审核），商家那一半在下一组。
  //    ⚠️ 同样**不给角色闸留位置** —— 后端这 6 条也没有角色判据（TD-18）。
  //    ⚠️ 只有 `invite` / `review` / `stop` 是写端点 ⇒ 只有它们写 `admin_audit`；
  //    `invitations` / `applications` / `activities` 是读，不留痕。
  marketingSeckill: {
    invitations: '/admin/marketing/seckill/invitations',
    invite: '/admin/marketing/seckill/invite',
    applications: '/admin/marketing/seckill/applications',
    review: '/admin/marketing/seckill/review',
    activities: '/admin/marketing/seckill/activities',
    stop: '/admin/marketing/seckill/stop',
  },
  // ⚠️ C3 块 5（营销块 · commit 4 秒杀，2026-10-12）：**商家侧 + 用户侧** 5 条
  //    （`SeckillController`，`@RequestMapping("/marketing")`）。
  //    ⚠️ 与 `marketing`（券）/ `subsidy` **同前缀、不同路径段**（`/marketing/seckill/*`）⇒ 单列一组。
  //    ⚠️ 前四条各跑 `IdentityGuard.requireSelfOrAdmin(request, MerchantAccount)` ⇒ 传**别人的**
  //        `MerchantAccount` 是 **403**（不是「查不到」），与 `marketing` / `subsidy` 同形态。
  //    ⚠️ 第五条 `live` **不挂 IdentityGuard**，但它**也不在 `AUTH_WHITELIST` 里**
  //        ⇒ 未登录必 401（「登录可见、匿名不可见」）—— 口径逐字同上一组的 `subsidy.goods`。
  seckill: {
    invitations: '/marketing/seckill/invitations',
    respond: '/marketing/seckill/respond',
    applications: '/marketing/seckill/applications',
    apply: '/marketing/seckill/apply',
    activities: '/marketing/seckill/activities',
    live: '/marketing/seckill/live',
  },
  // ⚠️ C3 块 6（内容块 · commit 1 评论 + commit 2 问答 + 整改 commit 4 图片审核 + 整改补笔
  //    管理端全站列表，2026-10-12/13）：`CommentController` 的 **8** 条（`@RequestMapping("/comment")`）。
  //    **非** view 抽取 —— 商品页 `ProductPage.vue` 直调这八条里的一条（`list`），
  //    其余经新建的 `api/comment.js` 包一层。**单列一组、追加在对象尾**。
  //    ⚠️ 本组是**内容域 13 条里的 7 条**（方案 A4）：`/report/*` 3 条见下方新组、
  //       `/sensitive/*` 3 条归 commit 5（敏感词）。
  //       ⚠️ **2026-10-13 追加第 8 条 `adminList` ⇒ 内容域 14 条**（用户批准的甲案，
  //       不在方案 A4 的 13 条里）—— 该条**仍不是**公开面，见下一条 ⚠️。
  //    ⚠️ **只有 `list` 在白名单里**（公开面 —— 商品页未登录也要看评价）：本组另七条
  //       未登录必 **401**（`adminList` 与 `imageReview` 亦然）。别因为「都在 `/comment` 下」
  //       就把八条一起当公开。
  //    ⚠️ 六条写端点走 `IdentityGuard.requireSelfOrAdminByUsername(request, Account)`
  //        —— 判据是**登录名**（`comment.AuthorId` 存的就是它），传**别人的** `Account` 是 **403**。
  //        ⚠️ 两个管理端端点（`imageReview` / `adminList`）**不收 `Account`**：身份不由入参声明。
  comment: {
    list: '/comment/list',
    add: '/comment/add',
    delete: '/comment/delete',
    // ⚠️ 采纳答案（问答，commit 2）。⚠️ **两个 Id 都要**：`Id` = 哪条提问、`ReplyId` = 采纳哪条回答；
    //    只传 `Id` 会被后端拒（「缺少参数：ReplyId」）。
    accept: '/comment/accept',
    like: '/comment/like',
    myOrders: '/comment/myOrders',
    // ⚠️ 图片审核（管理端，块 6 整改 commit 4；方案 §4.3）—— **一条端点、三个动作**，
    //    靠 `Action` 分派（`list` / `approve` / `reject`）。管理端「待审图片」整页走它。
    imageReview: '/comment/imageReview',
    // ⚠️ 管理端**全站列表**（块 6 整改补笔，2026-10-13；内容域第 14 条）—— 唯一调用方是
    //    管理端「评论管理 / 问答管理」两栏（`views/admin/ModerationPage.vue`）。
    //    `Kind` **必填**（`review` / `question` / `answer`）；`TargetType`+`TargetId` 可选**成对**
    //    收窄；`Solved` 仅 `Kind=question` 有意义。⚠️ **不在白名单**（未登录 401）。
    adminList: '/comment/adminList',
  },
  // ⚠️ C3 块 6 整改 · commit 4〈举报 + 图片审核 + 3 次警告封号〉（2026-10-13）：`ReportController`
  //    的 **3** 条（`@RequestMapping("/report")`）。**非** view 抽取 —— 商品页的「举报 / 提删」
  //    弹窗 + 管理端「举报中心」经新建的 `api/report.js` 包一层。**单列一组、追加在对象尾**。
  //    ⚠️ 本组是**内容域 13 条里的 3 条**（方案 A4）；内容域至此 10 条（comment 7 + report 3），
  //       余下 `/sensitive/*` 3 条归 commit 5。
  //    ⚠️ `add` 是登录面（`IdentityGuard.requireSelfOrAdminByUsername`，判据 = 举报人登录名）；
  //       `list` / `handle` 是**管理端**面 —— 按本仓一贯做法**不挂 `requireAdmin`**（靠
  //       `AuthInterceptor` + `app.auth.enabled` 两道闸，口径同 `admin/**Controller`）。
  report: {
    add: '/report/add',
    list: '/report/list',
    handle: '/report/handle',
  },
  // ⚠️ C3 块 6 整改 · commit 5〈敏感词〉（2026-10-13）：`SensitiveWordController` 的 **3** 条
  //    （`@RequestMapping("/sensitive")`）。**非** view 抽取 —— 唯一调用方是管理端「敏感词库」tab，
  //    经新建的 `api/sensitive.js` 包一层。**单列一组、追加在对象尾**。
  //    ⚠️ 内容域 13 条（方案 A4）至此**全部落地**：`comment` 7 + `report` 3 + `sensitive` 3。
  //    ⚠️ 三条**都是管理端**面 —— 按本仓一贯做法**不挂 `requireAdmin`**（靠 `AuthInterceptor`
  //       + `app.auth.enabled` 两道闸，口径同 `admin/**Controller`）。
  //    ⚠️ 与 `comment` / `report` 的写端点不同，本组**不收 `Account`**：词表没有作者列，
  //       加词 / 删词不需要声明身份（见 `SensitiveWordController` 类注释那条 ⚠️）。
  sensitive: {
    list: '/sensitive/list',
    add: '/sensitive/add',
    delete: '/sensitive/delete',
  },
  // ⚠️ C3 块 7〈可观测域〉· commit 3（2026-10-13）：**SSE 流**端点 **3** 条，分属三个 Controller
  //    （`MetricsStreamController` / `OrderStreamController` / `EventStreamController`，
  //    各 `@RequestMapping("/admin/observe/{metrics,orders,events}")` + `@PostMapping("/stream")`）。
  //    **非** view 抽取 —— 唯一调用方是新建的 `api/observe.js`。**单列一组、追加在对象尾**。
  //    ⚠️ 三条都是 **POST**（管理端读端点在本仓**全部**是 POST，口径同 `admin.stats` 那两条），
  //       **无 body**（调用方传空串；后端这三个方法没有 `@RequestBody`，body 不参与判定）。
  //    ⚠️ 响应 `Content-Type: text/event-stream`、1 秒 1 帧、**永不自然结束**（把
  //       `mall.observe.sse.max-ticks` 配成 0 以外的值才会停，那是测试口子）⇒ 调用方**不能**用
  //       `http.post`（它的 10s 超时会把流当场掐断），必须走 `http.streamPost`。
  //       ⚠️ 媒体类型是 Controller **手动**写上的 —— `@PostMapping(produces=...)` 对
  //       `StreamingResponseBody` 这种异步返回**不会**写响应头（块 7 commit 3 实测）。
  //    ⚠️ 三条**不在** `AUTH_WHITELIST`（未登录 401），也**不挂角色闸** —— 与本仓既有
  //       `admin/**` 读端点同口径（闸只有 `app.auth.enabled` + `AuthInterceptor` 两道）。
  observe: {
    metrics: '/admin/observe/metrics/stream',
    orders: '/admin/observe/orders/stream',
    events: '/admin/observe/events/stream',
    // ⚠️ C3 块 7〈可观测域〉· commit 4（攻击引擎，2026-10-14）：同一个 `AttackController`
    //    （`@RequestMapping("/admin/observe/attack")`）的 **3** 条。**非** view 抽取 ——
    //    仍是上面 `api/observe.js` 的调用方（块 7 commit 5 的攻击模拟器页）。
    //    ⚠️ 三条**都是 POST + `@RequestParam`**（表单串），所以**不能**沿用上面三条 SSE 的
    //       `streamPost`，要走 `http.post` —— 但 `attackRun` 必须**显式传长 timeout**：
    //       它同步跑完一轮演练（缺省 60 秒）才回，`http.post` 的默认 10s 会在演练途中掐断。
    //       子键名与后端形参**逐字一致**（`name` / `concurrency` / `durationSeconds` /
    //       `scenarios` / `limit` / `offset`），见 `AttackController` 类注释第 2 节。
    attackScenarios: '/admin/observe/attack/scenarios',
    attackRun: '/admin/observe/attack/run',
    attackRecords: '/admin/observe/attack/records',
    // ⚠️ C3 块 7〈可观测域〉· commit 8b（清理，2026-10-14）：同一个 `AttackController` 的**第 4** 条。
    //    **非** view 抽取 —— 仍是 `api/observe.js` 的调用方（同上那页；commit 8b **只到
    //    `api/observe.js#cleanupAttack` 这一层**，页面按钮是 commit 9 补的 —— 现已在
    //    `AttackConsole.vue` 第 ⑤ 区，两段式确认）。
    //    ⚠️ **无入参**（后端方法不收任何 `@RequestParam`）：清什么由**后端**按标记判
    //       （`SIM-%` 前缀 OR `Source='attack'`，两种谓词缺一会漏 —— 见 `ObservedEventCleanupDao`
    //       类注释），**不是**让前端挑范围。调用方传空串即可，别臆造参数名。
    //    ⚠️ 它**真 DELETE**（本表第一条破坏性端点）：调用点必须**二次确认**，并把响应里的
    //       `summary`（形如「共 10 行（comment 1 / order 3 / …）」）回显给用户 ——
    //       只 toast 一个「成功」等于把「删了几行」藏起来。
    attackCleanup: '/admin/observe/attack/cleanup',
    // ⚠️ 2026-10-18（块 7 · 用户批文「演练环境重置」）：同一个 `AttackController` 的**第 9** 条。
    //    压测把种子 SKU 库存打空 / 把父商品打下架 / 把商家冻住之后，`attackRun` 会稳定报
    //    「库里挑不到可演练的商品」—— 清场只删攻击写出的行，**删不回库存**。
    //    ⚠️ **无入参**（作用面由后端按种子标记判：`SEED-%` / `Source='seed'`）；
    //       响应 `{success, restored:{表名→命中行数}, total, summary}`，调用方回显 `summary`。
    //    ⚠️ 它与清场是**两件事**：清场按钮现在**先删后复位**（`/cleanup` 的响应同时带
    //       `deleted` 与 `restored`）；本端点只做复位，给「演练没跑起来」那个错误态用。
    attackReset: '/admin/observe/attack/reset',
    // ⚠️ C3 块 7〈可观测域〉· commit 10（**攻击引擎 v2**，2026-10-15）：同一个 `AttackController`
    //    的**第 5** 条，也**追加在文件尾**。**非** view 抽取 —— 仍是 `api/observe.js` 的调用方。
    //    ⚠️ 语义是「**停止派发** + 排空在途」，**不是**「掐断连接」：后端给引擎置一枚令旗，
    //       本轮立刻不再派新请求，已经在途的那批**跑完**（`GRACE_SECONDS` 内）再收口
    //       ⇒ 点了之后**几秒内**那次 `attackRun` 的响应就会回来（带着 `Stopped=true`），
    //       前端不需要为它换一条通路。见 `AttackController#stop` 的类注释。
    //    ⚠️ **无入参**（后端不收任何 `@RequestParam`）：停的是「当前这一轮」，而同时只可能有一轮
    //       （引擎里那把 `running` 锁）⇒ 传 `batchId` 之类都是臆造。调用方传空串即可。
    attackStop: '/admin/observe/attack/stop',
    // ⚠️ C3 块 7〈可观测域〉· 块 7 收尾（**实时进度**，2026-10-15）：同一个 `AttackController`
    //    的**第 6** 条，追加在文件尾。**非** view 抽取 —— 仍是 `api/observe.js` 的调用方，
    //    其唯一调用方是 `AttackConsole.vue` 的辅助条（`running` 期间 1 秒一次）。
    //    ⚠️ 它是**只读**端点（既不删也不改任何持久状态、不写审计），但**仍然是 POST** ——
    //       与本域另外五条**逐字同形**（理由见 `AttackController` 类注释：本仓管理端读端点一律
    //       POST，混一条 GET 只会给 CORS 预检添特例）。
    //    ⚠️ **无入参**：没有 `runId` —— 引擎同一时刻只认真实跑着的那一轮（`running` 锁）
    //       ⇒ 传 `batchId` 是臆造。调用方传空串即可。
    //    ⚠️ 超时用短的那一档（`http.post` 默认 10 秒）：这条**立刻回**，它不等演练跑完 ——
    //       与 `attackRun` 恰好相反，别顺手给它也加 `RUN_GRACE_MS`。
    attackStatus: '/admin/observe/attack/status',
    // ⚠️ C3 块 7〈可观测域〉· 块 7 收尾（**演练回放**，2026-10-15）：本组**第 9** 条，追加在文件尾。
    //    ⚠️ **它不是上面 `attack*` 那六条的第七条** —— 这是本表 `observe` 组里**第一条路径挂在
    //       `/admin/observe`（根）之下**的端点，后端是**新建的 `ReplayController`**。用户
    //       2026-10-15 裁「甲（新端点）」的头一条理由就是这个语义：回放读的是 `observed_event`
    //       （**事件台账**），不是 `drill_record`（**演练记录**）—— 两者是同一轮演练跑出来的
    //       两样东西，把回放塞进 `AttackController` 只会让「演练记录」这个类名越来越不准。
    //    ⚠️ 入参 `batchId`（**必填**，就是演练记录那一行的 `BatchId`）+ `limit`（可选，缺省
    //       **5000** —— 用户定的数据量保护：一个批次可能几万条〔实测某日 20,374〕，一次性
    //       JSON 化 + 前端一次收完的代价不该由「看一眼」来承担）。子键名与后端形参**逐字一致**。
    //    ⚠️ 与 `attackStatus` 同为**只读**端点、同样走 POST（本域口径，见上面那条注释）；
    //       但它**不是**轮询端点 —— 每次回放只打一发，超时用 `http.post` 默认的那一档。
    replay: '/admin/observe/replay',
    // ⚠️ 2026-10-17（块 7 · 用户报「指标页切页从 0 开始」）：**指标首屏历史** 1 条。
    //    路径挂在 `/admin/observe/metrics` 之下（后端 `MetricsHistoryController`），
    //    与上面那条 SSE `metrics` 同组不同类：它是**一次性 JSON**（最近 60 拍已算好的帧），
    //    页面挂载时先铺满图表，再接着消费 SSE —— 故走 `http.post`，**不是** `streamPost`。
    //    ⚠️ 入参 `limit`（可选；缺省窗口 = 60 拍）；返回 `{success,list,size}`，
    //       `list` 里每一帧与 SSE 帧**逐字同形**，前端不必写第二套解析。
    metricsHistory: '/admin/observe/metrics/history',
    // ⚠️ 2026-10-17（状态流转页高密度落地）：**订单分析面** 1 条。
    //    路径挂在 `/admin/observe/orders` 之下（后端 `OrderStreamController#insight`），
    //    与上面的 SSE `orders` 同组不同语义：它是**一次性 JSON**（趋势 / 直方图 / 账龄 /
    //    健康度 / 最慢 Top N / 全局明细），页面挂载时铺满、之后低频刷新 —— 故走 `http.post`。
    ordersInsight: '/admin/observe/orders/insight',
    // ⚠️ 2026-10-17（用户 P0：明细表数据打架）：**全局流转明细的服务端分页**。
    //    与 `ordersInsight` 的分别：insight 是「最近 1000 行样本」派生的聚合，明细表**不能**吃它
    //    （Tab 是全库 COUNT、表是 1000 行子集 ⇒ 点「待发货 2271」空表）。
    //    本端点把 `status` 传到 SQL 的 WHERE，`total` 与 `rows` 同源同谓词，支持 `offset/limit`。
    //    入参与后端形参逐字一致；`status` 留空 = 全部。
    ordersDetail: '/admin/observe/orders/detail',
    // ⚠️ 2026-10-18（D3 手验补全）：死信落库副本的**只读**出口（后端 `DlqController`）。
    //    POST + `@RequestParam`（`limit` / `offset`），响应 `{success,list,total,limit,offset}`。
    //    页面顶部「死信数量」卡片点开抽屉时调用；空表也是 200 + `list:[]`。
    dlqList: '/admin/observe/dlq/list',
    // ⚠️ 2026-10-18（D3 手修「mqDown 关掉横幅不消失」）：**同一路径 GET / POST**。
    //    POST 写共享开关（`protections` / `injections` 两个 JSON 数组串，`[]` = 全关，
    //    **不传** = 不动它）；GET 读当前快照（`no-store`）。攻击页的开关必须走这里，
    //    只改本地 `Set` 不会影响后端健康度的 `mall.observe.mq.up`。
    attackConfig: '/admin/observe/attack/config',
    // ⚠️ 2026-10-19〈应用层性能隔离〉：同一个 `AttackController` 的**第 10** 条。
    //    只读存储水位（行数 / 高水位 / 安全线 / 保留天数），页面挂载与每次清理后各拉一次。
    //    **无入参**、POST（本域读端点一律 POST，理由见 `attackStatus` 那条注释）。
    attackStorage: '/admin/observe/attack/storage',
    // ⚠️ 2026-10-19〈应用层性能隔离〉：同一个 `AttackController` 的**第 11** 条。
    //    【彻底清空可观测历史数据】—— 删**整张** `observed_event`，与 `attackCleanup`
    //    （只删演练标记命中的行）不是一件事。⚠️ 破坏性更强：调用点必须两段式确认。
    //    后端按批删（5000 行 + 100ms 间歇）⇒ 积压多时可能远超默认 10s，
    //    调用方（`api/observe.js#purgeAttackEvents`）必须显式传长 timeout。
    attackPurge: '/admin/observe/attack/purge',
  },
  // ⚠️ 2026-10-18（D3 手验补全）：用户治理「查看明文」1 条。
  //    单列一组并追加在**文件尾**（同本文件既有零漂移手法）—— 往 `accounts` 组里插行会把
  //    后面所有条目整体下移。后端 `AdminAccountsController#userDetail`，**读**动作但返回明文，
  //    每次调用写一条 `user.viewplaintext` 审计。
  accountsDetail: {
    userDetail: '/admin/accounts/user/detail',
  },
  // ⚠️ 2026-10-19（批四第二批 · 商品状态机 / 库存）：2 条。追加在**文件尾**（同本文件既有
  //    零漂移手法）—— 往 `goodsB3` 组里插行会把后面所有条目整体下移。
  //    · `applicationSubmit`：草稿 CAS `draft→pending`（`GoodsController#applicationSubmit`）
  //    · `skuStockUpdate`：按 SKU 改库存（`GoodsController#skuStockUpdate`），改完触发低库存告警
  goodsB4: {
    applicationSubmit: '/goods/application/submit',
    skuStockUpdate: '/goods/skuStockUpdate',
    // 批四第二批 commit 2：品牌专区（公开读，只列 user.IsBrand=1 商家的在架商品）。
    brandHome: '/goods/brandHome',
  },
  // ⚠️ 2026-10-19（批四第二批 · 品牌加盟）：商家自服务 1 条。追加在**文件尾**（同本文件既有
  //    零漂移手法）。品牌申请**不改身份**，通过只置 `user.IsBrand=1` —— 与 `merchant.apply` 分开。
  merchantB4: {
    brandApply: '/merchant/brandApply',
  },
  // ⚠️ 2026-10-19（批四第二批 · 会员 VIP）：3 条。追加在**文件尾**（同本文件既有零漂移手法）。
  //    · `open`：会话内自助开通（`UserController#memberOpen`），身份判据 `requireSelfOrAdminByUsername`
  //      —— 参数是**登录名**，不是业务号。
  //    · `adminSet`：管理员设置等级（`AdminAccountsController#member`），`level` 只收 0/1。
  //    · `dailyCoupon`：领取今日会员专属券（`CouponController#daily`），服务端按模板建券，
  //      前端**不传 CouponId**（「今日」是服务端语义）。
  memberB4: {
    open: '/user/member/open',
    adminSet: '/admin/accounts/user/member',
    dailyCoupon: '/marketing/coupon/daily',
  },
}
