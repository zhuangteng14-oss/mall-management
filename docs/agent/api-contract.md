# 接口契约 — Mall-management {#api-top}

> **证据来源**：5 个 Controller 全文。缩写：`U`=`UserController.java`、`M`=`MessageController.java`、`G`=`GoodsController.java`、`O`=`OrderController.java`、`H`=`HealthController.java`（数字为源码行号）。
> **行号口径（2026-09-30 实测）**：`证据`列 = 该方法 **route 注解所在行**（`@PostMapping` / `@GetMapping` / `@RequestMapping`），**不是**方法签名行。全表行号已于 2026-09-30 对 HEAD 逐条复核并替换 —— 此前一版坐标系统性偏旧（偏 10~35 行，个别为负偏移），成因是各笔新代码插入后未同步。
> **2026-10-02 复测（URL 规范化 + 订单端点拆分后）**：`/mysqlGoods` 一分为二 —— 商品 9 端点留 `GoodsController`（前缀 `/goods`）；订单 8 端点迁入新文件 `OrderController`（前缀 `/order`，记 `O:`）。`U:` / `M:` / `H:` 三列**原样保持**（那两个类只改类名与类级前缀，行数未变，行号零漂移；2026-10-02 复测仍**精确命中**）。⚠️ **但 `U:` 那一栏已于 2026-10-02 三层第三笔（user 域，`0c07e96`）后作废** —— `UserController` 已重写为 107 行（只剩路由，无 SQL、无 `@Transactional`），旧 `U:<n>`（34/56/124/176/241…）全部越界。`/user` 段 5 行已就地改为 `UserController.<方法>:<行>`，行号**沿用本表口径 = `@PostMapping` 行**（**不是**方法声明行，与 `db-schema.md#db-top` 相反，勿互抄）。⚠️ **`M:` 那一栏同理，已于 2026-10-03 三层第四笔（message 域，`8ed5fc1`）后作废** —— `MessageController` 已重写为 **127 行**（只剩路由，无 SQL、无 `@Transactional`、无 `@RequestParam` 默认值），旧 `M:<n>`（34/37/89/112/137/181/224/279/294/327/361…）全部越界。`/message` 段 10 行已就地改为 `MessageController.<方法>:<行>`，行号**沿用本表口径 = `@PostMapping` 行**，方法体与 SQL 的现址见该表后的「业务体现址」。⚠️ **`H:` 那一栏未动**（`HealthController` 本笔未碰）。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-03 实测**；重构后须重测。
> ⚠️ **同版本复核（对账上一版的「`G:` 行号整体 −9」）**：以 `git show 8c5385e:…goods/controller/GoodsController.java`（**笔 2 后**状态）逐条实测，其 route 行为 **50 / 72 / 173 / 187 / 197 / 245 / 280 / 359 / 369** —— 与上一版表内 `G:` 值**逐条相同** ⇒ 那一栏在笔 2 时是**准的**，不是陈旧值。但「**整体 −9**」只是粗略说法：实测相对 2026-09-30 基线（`148e77b`，`:59 / :81 / :181 / :195 / :205 / :249 / :264 / :336 / :346`）是 `goodstype`/`goodsAdd` **−9**、`goodsSelect` 一族 **−8**、`productCodePrice` **−4**、`goodsUp` 之后反而 **+16 / +23 / +23**（因笔 2 在该段加了说明注释）⇒ 引用时**不要拿 −9 去换算全表**。
> **2026-10-02 三层第二笔（商品域）又整体前移**：9 个端点的**路由仍在 `GoodsController`**，**业务体（SQL / try-catch / 响应组装）下沉到 `GoodsService`，SQL 出口收进 `GoodsDao`** ⇒ `G:` 现值 = **51 / 72 / 92 / 101 / 109 / 127 / 142 / 158 / 171**（实测，已重填），且它**只保证「路由还在哪一行」**；方法体位置另见 `/goods` 表下的「业务体现址」注。
> **「幂等」判定标准**：*相同入参重复调用是否产生额外副作用（不重复写入）*。非 HTTP 语义幂等。
> ~~所有接口均无鉴权~~ ⚠️ **2026-10-04 订正（鉴权笔 1/2）**：鉴权闸（`AuthInterceptor` + `WebAuthConfig`）已落地；⚠️ **2026-10-05 翻开关笔：`app.auth.enabled` 已由 `false` 翻成 `true`** ⇒ 该闸自今日起**真正生效**（白名单外无会话 = 401；原「**默认关闭**（`app.auth.enabled=false`）⇒ 行为仍是全部放行」已成史料）；但 `/user/loginSelect`（认证成功后写会话）、`/user/me`、`/user/logout` 三个端点的**会话侧**已就位。无统一返回体、无全局异常处理；~~5 个 Controller 均标 `@CrossOrigin`~~ ⚠️ **2026-10-04 订正：那 5 处 `@CrossOrigin` 已由 PR #34 `23f5361` 全部删除** ⇒ CORS 唯一出处 = `ImgConfig` 的全局 `CorsFilter`。入参均为 `@RequestParam`（query/form），无 `@RequestBody`。

## 1. 通用约定 {#api-1}

- 全部路径无版本号、无 RESTful 语义，**全部 `POST`**，仅 **2 个 `GET`**：业务侧 `/goods/goodstype`、运维侧 `/health/db`（`@Profile("a3")`，`HealthController:43`，仅 a3 profile 下注册 —— 原记只列了 `goodstype`，漏了它）。
- 失败返回不统一：`loginSelect` 返回 `0`；`getUserInfo` 返回 `null`（HTTP 200 空体）；`/user/me` 无会话返回 **HTTP 401** + `{success:false,msg:"未登录"}`（与闸的 401 **逐字节相同**，共享常量 `AuthInterceptor.BODY_NOT_LOGGED_IN`）；`/goods` 与 `/order` 系列返回 `{success:false, msg}`。
- 无分页参数，列表接口全量返回。
- ⚠️ **「读接口」≠「无副作用」**：本表按**方法语义**分类，不按 HTTP 方法。`/message/history` 名字是查询，**实为写** —— 除 SELECT 外**无条件** upsert `user_conversation_read.last_read_time = NOW()`（SQL 在 `MessageDao.upsertLastReadTime:206-209`，由 `MessageService.getMessageHistory:200` 调用）⇒ 统计「读接口」或做只读性验证时**不得**把它算进去。

## 2. 接口清单（共 36 个） {#api-2}

> ⚠️ **35 → 36（2026-10-06，多账户隔离笔）**：`/user` 段再新增 1 个会话侧端点 —— `POST /user/token`
> （铸 43 字符访问 token，`AuthTokenStore.mint`）。`### /user` 表 7 行 → **8 行**；本表 `UserController.<方法>:<行>`
> 一并按 2026-10-06 实测**全部重填**（成因：本笔在 `UserController` 内插入 `token` 端点与类 javadoc）。
> ⚠️ **33 → 35（2026-10-04，鉴权笔 2）**：`/user` 段新增 2 个会话侧端点（`me` / `logout`）。
> `Result` 类注释里"33 个端点 / 33 处响应 Map"两处口径**在本笔之后都已过时**（无论按哪个口径数）——
> 本笔**不改 `Result.java`**，只在 `progress.md §5-Q` 登记（避免夹带）。

### /user — 8 个 {#api-user}

> ⚠️ **行号口径**：`UserController.<方法>:<行>` = 该方法的 `@PostMapping` 所在行（**不是**方法声明行）。
> ⚠️ **2026-10-06（多账户隔离笔）本表 7 行全部重测**：`92 / 114 / 136 / 145 / 154 / 191 / 222`
> → **`96 / 121 / 143 / 152 / 161 / 213 / 294`**（+`token` 行 `:263`）。全部 7 个旧值在 **HEAD 时是准的**
> （已 `git show HEAD:…UserController.java` 逐条实测），**位移是 2026-10-06 多账户隔离笔造成的**
> —— 该笔在 `loginSelect` 体、类 javadoc 处加行，并在 `me` 与 `logout` 之间插入 `token` 端点。
> ⚠️ **2026-10-04（笔 2）本表 5 行全部重测**：旧值（`52 / 61 / 80 / 89 / 98`）已陈旧 **40~56 行**
> —— 成因是三层第三笔之后该文件又经多次改动（`Result` 包装、类 javadoc 增行、本笔加形参与新端点），
> 而本表只在 2026-10-02/03 对过账。

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/user/loginSelect` | username, password | `int`：1=客户 2=商家 0=失败 | 是 | `UserController.loginSelect:96` |
| POST | `/user/loginAdd` | image(默认 default_user.png), username, usertype, userid, customerid(默认"暂无数据"), merchantid(默认"暂无数据"), password, age, gender | `String`：`"0"`=已存在 / `"添加成功！"` | 是 ⚠️ | `UserController.loginAdd:121` |
| POST | `/user/getUserInfo` | username, password | `user` 全字段 + `image`；失败返回 `null` | 是 | `UserController.getUserInfo:143` |
| POST | `/user/updateAvatar` | username, file(MultipartFile) | `{success, msg?, imageUrl?}` | 是 | `UserController.updateAvatar:152` |
| POST | `/user/updateUserInfo` | username, email, address, selfintroduction | `{success, msg}` | 是 | `UserController.updateUserInfo:161` |
| POST | `/user/me` | **无**（凭会话 Cookie；**无 `@RequestParam`**） | 有会话：`user` 表**整行** + 头像 `image` + **`role`**（多账户隔离笔新增）+ `success`（顶层平铺；P2.6 起与 `/user/getUserInfo` 共用同一实现 `UserService.profileOf`，⚠️ 但多账户隔离笔起 `/user/me` 会先复制该 Map 再 `put("role")` ⇒ **两者不再是同一张 Map**，见 §5-AC／§5-AF）；⚠️ **cookie 会话的管理员降级作答：200 + `{success:true,role:3}`**（只给角色、不给资料）；**无会话 ⇒ 401** + `{success:false,msg:"未登录"}`；查无此人 ⇒ 200 + `{success:false,msg:"用户不存在"}` | 是 | `UserController.me:213` |
| POST | `/user/token` | **无**（凭会话 Cookie；**无 `@RequestParam`**） | **授权口径**拿不到身份 ⇒ **401**（⚠️ 这一档**含**「cookie 会话是管理员」—— 管理员票**不能**由 cookie 换发）；拿得到 ⇒ `{success:true, token:"<43 字符 Base64URL>"}` | **否** ⚠️（每次铸**新**票，不回发既有票） | `UserController.token:263` |
| POST | `/user/logout` | **无**（凭会话 Cookie） | `{success:true, msg:"已退出"}`；无会话时同样答成功（no-op，**不建会话**）；⚠️ **闸开（2026-10-05）后**：无会话者在**闸处**即 401，进不到本方法（「答成功」只对**有会话**的调用方成立） | 是 | `UserController.logout:294` |

> **会话侧三端点（2026-10-04 鉴权笔 2 新增/改造）**：
> - `loginSelect` 在**认证成功且拿到业务号**时把**业务号**（`user.customerid` / `user.merchantid`）写进会话；
>   **失败的登录不发会话**（形参取 `HttpServletRequest`，只在成功路径上 `getSession()`）。
>   ⚠️ HTTP 契约**一字未动**（返回仍是裸 `int`，例外 1/5）。
> - `me` / `logout` 与 `getUserInfo` **并存不替代**：那个是**凭据驱动**，这两个是**会话驱动**。
> - ⚠️ **前端本笔不动** ⇒ 这两个新端点当日**无调用方**（接入是笔 4 / `P2.6`）。⚠️ **P2.6（2026-10-05）起 `/user/me` 已有调用方**（`main.js:28` 启动探针 + 3 个资料读点 = 4 处）；`/user/logout` 于**翻开关笔（2026-10-05）**起 **1 处**（`UserCenter.vue:64` 退出时销服务端会话），见 §5 对账。
> - 开关 `app.auth.enabled` **已于 2026-10-05 翻 `true`**（翻开关笔）⇒ `me` 的 401 分支今日**由闸先发**（端点侧那一份响应体逐字节相同，见 `UserController.me`）；测试与 A3 各由 profile 显式 pin `false` 保住。
> - ⚠️ **第 4 个会话侧端点 `token`（2026-10-06 多账户隔离笔）**：本标题的「三端点」是 2026-10-04 的口径，
>   今日会话侧共 **4** 个。`token` 与上述三个不同的是：它**登在闸后**（不进 `AUTH_WHITELIST`）、
>   也**不挂** `IdentityGuard`（没有「代表别人」的入参），且**非幂等**（每次铸新票）。
>   ⚠️ 它与 `me` 在**口径**上分工：`token` 用**授权**口径（cookie 里的管理员换不到票），`me` 用**识别**口径（认得管理员但降级作答）。

### /message — 10 个 {#api-message}

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/message/conversations` | currentId, currentType | `{success, list[{other_id,other_type,last_time,unread}]}` | 是 | `MessageController.getConversations:55` |
| POST | `/message/history` | currentId, currentType, otherId, otherType | `{success, list[...]}` | **否** ⚠️**写**（见 §1） | `MessageController.getMessageHistory:61` |
| POST | `/message/send` | fromId, fromType, toId, toType, content | `{success, msg}` | **否** | `MessageController.sendMessage:69` |
| POST | `/message/getOrCreateConversation` | currentId, currentType, otherId, otherType | `{success, otherUser, messages}` | 是 | `MessageController.getOrCreateConversation:78` |
| POST | `/message/userSelect` | userId | `{success, user{id,username,usertype,gender,image:null}}` | 是 | `MessageController.userSelect:90` |
| POST | `/message/friendAdd` | customerId, friendId | `{success, msg}` | 是 ⚠️ | `MessageController.friendAdd:96` |
| POST | `/message/selectFriend` | friendId | `{success, list}` | 是 | `MessageController.selectFriend:103` |
| POST | `/message/friendUp` | customerId, friendId | `{success, msg}` | 是 | `MessageController.friendUp:109` |
| POST | `/message/friendDe` | customerId, friendId | `{success, msg}` | 是 | `MessageController.friendDe:116` |
| POST | `/message/friendSelect` | customerId | `{success, list}` | 是 | `MessageController.friendSelect:123` |

**业务体现址**（2026-10-03 三层第四笔后 —— 上表 `MessageController.<方法>:<行>` 只是**路由行**，方法体已不在 Controller）：

| 端点 | 路由（`MessageController`） | 业务体（`MessageService`，方法声明行） | SQL 出口（`MessageDao`，定义行-执行行） |
|---|---|---|---|
| `conversations` | `:55-59` | `getConversations` `:144-183` | `selectConversations` `:112-120`、`selectLastReadTime` `:131-133` |
| `history` | `:61-67` | `getMessageHistory` `:194-206` | `selectMessagesBetween` `:149-152`、`upsertLastReadTime` `:206-209` |
| `send` | `:69-76` | `sendMessage` `:213-230` | `insertMessage` `:214-216` |
| `getOrCreateConversation` | `:78-85` | `getOrCreateConversation` `:240-266` | `selectMessagesBetween` `:149-152`（+ 经私有 `getUserInfoById` 的 3 条，见下） |
| `userSelect` | `:90-93` | `userSelect` `:278-311` | —（读路径走 `UserCacheService.publicInfo()`） |
| `friendAdd` | `:96-100` | `friendAdd` `:322-326` → 临界区 `friendAddLocked` `:342-381` | `selectFriendPair` `:233-234`、`insertFriendRequest` `:243-244` |
| `selectFriend` | `:103-106` | `selectFriend` `:386-397` | —（读路径走 `FriendCacheService.listRequests()`） |
| `friendUp` | `:109-113` | `friendUp` `:409-435` | `acceptFriendRequest` `:254-255`、`insertMutualFriend` `:275-276` |
| `friendDe` | `:116-120` | `friendDe` `:441-461` | `deleteFriendRequest` `:281-282` |
| `friendSelect` | `:123-126` | `friendSelect` `:472-484` | —（读路径走 `FriendCacheService.listFriends()`） |

⚠️ **本域不是「数据出口收成一处」**：上表 `MessageDao` 之外还有 **2 个跨域缓存读路径**（`UserCacheService.publicInfo` / `FriendCacheService.listFriends`+`listRequests`，共 6 条语句）——按 `three-layers.md` 的 DAO 边界裁决（DAO 只收 Controller 内联 SQL）**不进** `MessageDao`。`getOrCreateConversation` 走的是 `MessageService` 里**另一份**私有 `getUserInfoById`（`:497-533`，`MessageDao.selectUserPublicInfo:174` + 两张 image 表 `:184`/`:190`），与 `FriendCacheService.userInfoById` 同名同逻辑但**保留 catch** —— 两份并存是**有意的**，不是漏删。

### /goods — 9 个（商品域，原 `/mysqlGoods` 的商品部分） {#api-goods}

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| GET\|POST | `/goods/goodstype` | GET 无参；POST: GoodType, GoodTypeNew | GET `{success,list}`；POST `{success,msg}` | GET 是 / POST 是 ⚠️ | `G:51` |
| POST | `/goods/goodsAdd` | MerchantId, GoodName, Price, file(MultipartFile), GoodType, ProductCode, GoodTypeNew | `{success, msg, GoodImg}` | **否** | `G:72` |
| POST | `/goods/goodsSelect` | MerchantId | `{success, list}` | 是 | `G:92` |
| POST | `/goods/goodsSelectHome` | 无 | `{success, list}`（每行附 `UserName`） | 是 | `G:101` |
| POST | `/goods/goodsDe` | MerchantId, GoodName, ProductCode | `{success, msg}` | 是 | `G:109` |
| POST | `/goods/productCodePrice` | MerchantId, GoodName, ProductCode | `{success, price, goodImg}` | 是 | `G:127` |
| POST | `/goods/goodsUp` | MerchantId, GoodName, Price, GoodType, ProductCode, file(可选) | `{success, msg}` | 是 | `G:142` |
| POST | `/goods/GoodsProductCodeSelect` | MerchantId, GoodName | `{success, list[String]}` | 是 | `G:158` |
| POST | `/goods/productSearch` | keyword（**未用 `@RequestParam` 显式命名**） | `{success, list}` | 是 | `G:171` |

**业务体现址**（2026-10-02 三层第二笔后 —— 上表 `G:` 只是**路由行**，方法体已不在 Controller）：

| 端点 | 路由（`GoodsController`） | 业务体（`GoodsService`） | SQL 出口（`GoodsDao`） |
|---|---|---|---|
| `goodstype` | `G:51-65` | ⚠️ **未下沉**（分支留 Controller，`GoodsTypeCacheService` 承接读写） | — |
| `goodsAdd` | `G:72-83` | `GoodsService.goodsAdd` `:83-148` | `insertGoods` `:77-85`、`lastInsertId` `:103-105`、`insertProductsales` `:113-118` |
| `goodsSelect` | `G:92-97` | `GoodsService.goodsSelect` `:159-161` | —（读路径走 `GoodsCacheService`） |
| `goodsSelectHome` | `G:101-104` | `GoodsService.goodsSelectHome` `:164-166` | —（读路径走 `GoodsCacheService`） |
| `goodsDe` | `G:109-116` | `GoodsService.goodsDe` `:211-238` | `deleteGoods` `:128-132`、`deleteProductsales` `:135-139` |
| `productCodePrice` | `G:127-134` | `GoodsService.productCodePrice` `:184-206` | —（读路径走 `GoodsCacheService`） |
| `goodsUp` | `G:142-152` | `GoodsService.goodsUp` `:252-306` | `updateGoods` `:154-165`、`updateProductsalesGoodName` `:168-172` |
| `GoodsProductCodeSelect` | `G:158-164` | `GoodsService.GoodsProductCodeSelect` `:173-175` | —（读路径走 `GoodsCacheService`） |
| `productSearch` | `G:171-174` | `GoodsService.productSearch` `:316-331` | `searchByKeyword` `:193-207` |

### /order — 8 个（订单域，2026-10-02 从 `GoodsController` 拆出） {#api-order}

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/order/orderAdd` | CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf | `{success, msg}` | **否** | `O:42` |
| POST | `/order/orderSelect` | customerId | `{success, list}` | 是 | `O:61` |
| POST | `/order/orderDeliveryIfSelect` | merchantId | `{success, list}` | 是 | `O:69` |
| POST | `/order/orderDeliverySelect` | merchantId, status(默认 `"active"`) | `{success, list}` | 是 | `O:77` |
| POST | `/order/historicalOrdersSelect` | customerId | `{success, list}` | 是 | `O:88` |
| POST | `/order/orderPayIfUp` | orderId | `{success, msg}` | **是（CAS）** | `O:100` |
| POST | `/order/orderReceiveIfUp` | orderId | `{success, msg}` | **是（CAS）** | `O:107` |
| POST | `/order/orderDeliveryIfUp` | orderId | `{success, msg}` | **是（CAS）** | `O:114` |

### /health — 1 个 {#api-health}

> ⚠️ **本段是运维接口，不是业务接口**：`@Profile("a3")` ⇒ **只在 a3 profile 下注册**；消费者是 A3 并发脚本（P5 就绪探测 / P6 库身份 / P7 阴性对照），**前端 0 调用**。它被刻意设计为**不带任何可缓存数据**（防缓存污染），见 `cache-layer.md §11`。

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| GET | `/health/db` | 无 | `{success, serverUuid, hostname, database, port, version}` | 是 | `H:43` |

## 3. 逐条说明 {#api-3}

- ⚠️ `UserController.loginAdd:61`：靠「先 SELECT 查重再 INSERT」实现重复拦截（`UserDao.existsUserName:119` → `UserDao.insertUserCustomer:170`），存在 TOCTOU 竞态（见 `concurrency-map.md`）。第二参数 `customerid`/`merchantid` 会被 `userid` 覆盖（`UserService.loginAdd:136`、`:144`）。
- ⚠️ `MessageController.friendAdd:96`（业务体 `MessageService.friendAdd:322`）：同为「先查后插」（查重 `MessageDao.selectFriendPair:233` → 插入 `MessageDao.insertFriendRequest:243`），存在 TOCTOU 竞态（B2 的 `runLocked` 已在 `MessageService.friendAdd:323` 把二者合成原子）。
- ⚠️ `G:51 goodstype` POST：同为「先查后插」（**已搬**：`GoodsTypeCacheService:62`→`:71`）。返回 `{success:false,msg:"类型已存在"}` 而非 `"0"`，与 `loginAdd` 风格不一致。
- `MessageController.getMessageHistory:61` 非幂等：除查询外还会 upsert `user_conversation_read.last_read_time = NOW()`（`MessageDao.upsertLastReadTime:206-209`，调用点 `MessageService.getMessageHistory:200`），每次调用都刷新时间戳。
- `MessageController.sendMessage:69` 非幂等：每次调用插入一条新 `message`（`MessageDao.insertMessage:214`，调用点 `MessageService.sendMessage:217`）。
- `O:42 orderAdd` **仍非幂等**：每次调用插入一条新 `order`。⚠️ **原文**写「`OrderId` 由调用方提供且**无唯一约束**，可重复下单号」—— **2026-09-30 S1a（`1e2fcfa`）已加 UNIQUE `uk_order_orderId`**，同单号第二次调用会被 DB 拒（`orderAdd` 把它吞成 **HTTP 200 + `msg="数据库错误…"`**，见 TD-14 / `A-S2a-1c`）。**但「非幂等」这条没变**：拦的只是「同单号重复插入」，不同单号照旧每调一次插一行 ⇒ 幂等性仍不由接口保证。
- `O:42 orderAdd` **新增价格闸（B#1，2026-10-04 `fix/price-validation`）**：闸在 `OrderService.orderAdd`、且在 `runLocked` **之前** —— 接受 ⟺ `Float.isFinite(Price) && Price >= 0 && Price <= 10^7`；不通过 ⇒ **HTTP 200 + `{success:false,msg:"价格不合法"}`**（**不取锁、不触库**）。⚠️ **本行是对外契约的实质变更，且是「失败模式新增」**：`Price` 一列从此**有值域约束**（下界 `>= 0` 且**闭**、上界 `<= 10^7` 且**闭**）；`NaN` / `±Infinity` / 负值 / `>10^7` 四类入参**改后一律被拒**。⚠️ **改前**这四类里只有**负值**与 `>10^7` 是**确定能落库**的（`order.Price` 是 `float NOT NULL`、无任何值域约束，实测锚点见下）；`NaN` / `±Infinity` 改前**究竟落库还是报错未验**（取决于 JDBC 驱动与 MySQL 对特殊浮点值的处理）—— 本行**不声称**它们改前一定能落库。合法入参的行为**零变化**（闸对合法值透明）。⚠️ **它不是资损修复** —— 「1 元的电视」（合法但有界）仍挡不住，**真值源/支付侧**另挂账。上界 `10^7` 的技术锚 = `order.Price` 是 `float`、整数精确上限 2^24；**业务上界待裁**。依据 = `three-layers.md` Class A #1 / §G、`defect-survey.md` 病 B 同条。
- `O:42 orderAdd` **新增出生状态闸（C#6，2026-10-04 `fix/order-state-guard`）**：闸在 `OrderService.orderAdd`、在 `runLocked` **之前**，且**排在价格闸之后** —— 接受 ⟺ `(PayIf,DeliveryIf,ReceiveIf) ∈ {(0,0,0),(1,0,0)}`；不通过 ⇒ **HTTP 200 + `{success:false,msg:"订单状态不合法"}`**（**不取锁、不触库**，同时记一次 `mall.order.state.birth.rejected{shape}` + 一行 WARN）。
  ⚠️ **本行是对外契约的实质变更**：三个状态位`PayIf`/`DeliveryIf`/`ReceiveIf` 从此**不再是客户端自选** —— 它们是「新订单的出生态」，只允许是 `(0,0,0)` 或 `(1,0,0)`。
  ⚠️ **为什么白名单里必须有 `(1,0,0)`（`PayIf=1`、未发货、未收货）—— 这是本节最要紧的一句**：**生产前端就是这么下单的**。`ProductPage.vue:354` 把「已付款」直接写进创建请求（`PayIf: payIf ? 1 : 0`），同页 `:342-343` 固定传 `DeliveryIf:0` / `ReceiveIf:0`，`fetch` 调用点在 `:369`（见 §5）⇒ **生产实际只发 `(0,0,0)` 与 `(1,0,0)` 两种，恰等于白名单**。**据此可断言：本闸对生产流量零影响**；反过来，**若有人把 `(1,0,0)` 从白名单里去掉，下单功能当场损坏**。改这条规则前**必读** `OrderState.isInitial` 的 javadoc（那里写着同一个理由）。
  ⚠️ **`(1,1,0)` / `(1,0,1)` / `(1,1,1)` 三条**合法但非初始**态被拒是刻意的**：`(1,1,1)` 正是 TD-17 正文举的「出生即完成」。⇒ 本闸判据比「合法态」**更严**，它**不是**「不变式前移」—— 后者对这三个组合答「合法」，闸若照它做会**原样放行**。
  ⚠️ **两闸的先后顺序是契约的一部分、不许对调**：价格闸未通过时对外必须答 `msg="价格不合法"`（上一行已发布的承诺），把状态闸挪前面 = **静默收窄一条已发布契约**。固定为**先价格、后状态**，由 `OrderAddStateGuardSmokeTest.priceGateRunsFirst_whenBothGatesWouldReject` 钉死。
  ⚠️ **与 D#1 无关**：本闸不碰 `@Transactional`（在锁外，本就不在任何事务边界内）。⚠️ **本行不是资损修复** —— 关掉的是「伪造终态订单」的入口，**存量脏行**与「1 元的电视」仍另挂账（见 `tech-debt.md` TD-17 / `three-layers.md` §C Class B #6 与 §G）。依据 = `three-layers.md` §C Class B #6 / §G。
- `G:72 goodsAdd` / `G:109 goodsDe` / `G:142 goodsUp`：均同时写 `goods` 与 `productsales` 两张表，**✅ 已有事务保护**（**2026-10-04 C#2 `fix/goods-write-tx`**：三处各加 `@Transactional` + catch 内 `setRollbackOnly`；见 `concurrency-map.md`）。⚠️ **对外契约不变** —— 第二步失败仍是 **HTTP 200 + `{success:false,msg}`**（不是 500），变的只是「第一步要不要回滚」。⚠️ 2026-10-02 三层第二笔后，三者的**方法体**在 `GoodsService`：**2026-10-04 声明行** `goodsAdd:118` / `goodsDe:301` / `goodsUp:350`；本行原记的体范围 `83-148` / `211-238` / `252-306` **已因中间多次插入而失效**，引用请用符号名或声明行，别再拿旧范围换算。
- `O:100/107/114` 三个订单状态接口是全项目唯一带并发防护的写接口，详见 `concurrency-map.md` 正面案例。
- `G:171 productSearch`：`keyword` 未加 `@RequestParam` 显式命名，依赖参数名保留（编译需 `-parameters`）。关键词被逐字符拼进 SQL 字符串（**2026-10-02 起在 `GoodsDao:194-200`**，原 `G:380`），因拆分粒度为单字符**无法构造多字符注入**，但含 `'` 会使 SQL 语法错误；⚠️ **后果不止于此**：`_` / `%` 未转义 ⇒ `'%_%'` 命中任何非空 `GoodName` ⇒ **关键词含下划线或百分号时返回全表**（2026-09-30 实测），见 `defect-survey.md` 病 B 同条。

## 4. 待确认 {#api-4}

- ~~前端实际调用的接口子集与参数拼装方式 —— 前端源码未读。~~ **✅ 已关闭（2026-09-30）**：前端源码已读，矩阵见 **§5**。
- ~~`ImgPath.java` / `ImgConfig.java` 是否额外暴露静态资源路由（影响 `http://localhost:8088/img/` 前缀的可用性）~~ **已关闭**：`ImgPath`（`@Configuration implements WebMvcConfigurer`）注册了 `/img/**` → `app.upload-dir` 的资源映射，故 `http://localhost:8088/img/<文件名>` 前缀可用；`ImgConfig` 只注册全局 `CorsFilter`，不涉及静态资源路由。证据（**2026-10-02 实测**；原记 `:15-16` / `:30-35` 已失效）：`ImgPath.java:17`（`@Value` 读 `app.upload-dir`）、`:45-64`（`@PostConstruct initUploadDir()`）、`:66-80`（`addResourceHandlers`，注册调用在 `:78`）；`ImgConfig.java:55-56` + `:67-68`（`@Configuration` + `@Bean corsFilter`；**原记 `:9-12`** —— ⚠️ **2026-10-06 CORS 收窄笔**新增 3 个 import + 类 javadoc ⇒ 其后行号整体下移，口径见 `progress.md` §5-AH）。实证：A2 的 `ImgMappingSmokeTest` 已断言 `GET /img/default_user.png` → 200。

## 5. 前端调用方矩阵（2026-09-30 实测，首次） {#api-5}

> **本节关闭 §4 原第 1 条**（「前端实际调用的接口子集与参数拼装方式 —— 前端源码未读」）。
> **口径**：`亲读`（Python 括号配平解析 42 处 `fetch(` + 逐行读 4 个 Controller 签名）。
> **范围**：`Mall-management-web/mall-management-system/src` 全部 `.js` / `.vue`；下表文件均位于 `src/views/`。
> **行号**：指**该 `fetch(` 调用的所在行**；标 ※ 者见下方「变量 URL」段。

**四项对账**（须自洽）：前端 `fetch(` 调用点 **42** 处 ／ 不同路径 **31** 条 ／ 本文件路由 **35** 条 ／ 差集 **35 − 31 = 4**（即表末四行「无调用方」）。 ⚠️ **基期 vs 现状**：上列 **42 / 31 / 4 是 2026-10-03 的基期值**（本文件与 `progress.md` §6 其余「42」引用处同为基期口径）；**现状调用点 = 44** —— 会话恢复笔的 `main.js:28` 探针 + 翻开关笔的 `UserCenter.vue:64` 退出销会话**均未计入基期**（本笔实测 `grep -rn "http\.\(post\|get\)(" src/` = **44**）；**不同路径 = 32**（`/user/me` 进、`/user/getUserInfo` 出，互为抵消；翻开关笔又**进**一条 `/user/logout`）⇒ **差集 4 → 3**（`getOrCreateConversation` / `GET /health/db` / `/user/getUserInfo`；原第 4 条 `/user/logout` 已销账）。
> ⚠️ **2026-10-04（鉴权笔 2）**：路由 **33 → 35**（新增 `/user/me`、`/user/logout`），
> 前端 **42 / 31 两条不变**（本笔不动前端）⇒ 差集 **2 → 4**。**这个 4 是预期的，不是漏接** ——
> 两个新端点的前端接入归笔 4 / `P2.6`。⚠️ **P2.6（2026-10-05）后**：`/user/me` 由「无调用方」变为 3 个读点、`/user/getUserInfo` 反向**归零** ⇒ 差集**仍 4**、成员互换（见 `:201-204`）；`main.js:28` 的探针那 1 处**不在**本口径的 42 内（口径基期早于会话恢复笔）。⚠️ **再现状（翻开关笔，2026-10-05）**：`/user/logout` 亦得 1 处（`UserCenter.vue:64`，退出时销服务端会话）⇒ 差集 **4 → 3**（见 `:161`）。

⚠️ **前端无 HTTP 框架**：`axios` / `$http` / `XMLHttpRequest` / `EventSource` / `baseURL` 在全 `src` **0 命中**；全部走原生 `fetch(`（且**唯一一处 `fetch(` 在 `src/api/http.js`**）；`vue.config.js` **无 `proxy`**；⚠️ **2026-10-05 订正（鉴权笔 4）**：`main.js` 原先「无拦截器、无全局配置」**已不成立** —— 它现在注册 **401 出口**（`setUnauthorizedHandler(router.push('/login'))` + `router.afterEach` 复位闩锁），但**仍无 HTTP 拦截器**；URL 全部硬编码 `http://localhost:8088/...`（⚠️ **此半句自 `#48` 起已过时** —— URL 已由 `VUE_APP_API_BASE` 提供，见 `progress.md §5-S`；**本笔不修**）。

| 端点 | 调用点 |
|---|---|
| `/user/loginSelect` | `LoginPage.vue:131` |
| `/user/me`（**P2.6** 起；原 `/user/getUserInfo` 的 3 个调用点**全迁来**，该端点今日 **0 调用方**） | `LoginPage.vue:144`、`GoodsUp.vue:153`、`UserInfoPage.vue:99`（另 `main.js:28` 启动探针，未计入本表 42 口径） |
| `/user/loginAdd` | `LoginPage.vue:216` |
| `/user/updateAvatar` | `UserInfoPage.vue:130` |
| `/user/updateUserInfo` | `UserInfoPage.vue:149` |
| `/goods/goodstype` | `GoodsAdd.vue:135`（**裸 GET**，全文唯一非 POST 调用）、`GoodsAdd.vue:166`、`HomePage.vue:154` |
| `/goods/goodsAdd` | `GoodsAdd.vue:217` |
| `/goods/goodsSelect` | `GoodsUp.vue:170` |
| `/goods/goodsSelectHome` | `HomePage.vue:121`、`ProductPage.vue:170`、`GoodsSelect.vue:85`※（R2 重指；R1 记 `:84`、原记 `:74`） |
| `/goods/goodsDe` | `GoodsUp.vue:215` |
| `/goods/goodsUp` | `GoodsUp.vue:327` |
| `/goods/productCodePrice` | `GoodsUp.vue:275`、`ProductPage.vue:193`、`ProductPage.vue:245` |
| `/goods/GoodsProductCodeSelect` | `ProductPage.vue:184`、`ProductPage.vue:236` |
| `/goods/productSearch` | `GoodsSelect.vue:89`※（R2 重指；R1 记 `:88`、原记 `:78`） |
| `/order/orderAdd` | `ProductPage.vue:393` |
| `/order/orderSelect` | `ShoppingCart.vue:113` |
| `/order/orderDeliveryIfSelect` | `OrderCenter.vue:125`※ |
| `/order/orderDeliverySelect` | `OrderCenter.vue:127`※ |
| `/order/historicalOrdersSelect` | `HistoricalOrders.vue:95` |
| `/order/orderPayIfUp` | `ShoppingCart.vue:156` |
| `/order/orderReceiveIfUp` | `ShoppingCart.vue:195` |
| `/order/orderDeliveryIfUp` | `OrderCenter.vue:171` |
| `/message/conversations` | `ChatRoom.vue:184`、`AppShell.vue:161`（**R6 合一重指**：两个壳的 `fetchUnreadCount()` —— `CustomerPage.vue:108` / `MerchantPage.vue:107` —— **已合并到本处**，两文件已随 R6 删除；其沿革：原记 `:97`（R1 时在 `HEAD` 已漂 3、实为 `:100`）⇒ R1/R2 各重指过一次） |
| `/message/history` | `ChatRoom.vue:213`、`ChatRoom.vue:279` |
| `/message/send` | `ChatRoom.vue:327`、`ProductPage.vue:459` |
| `/message/userSelect` | `ChatRoom.vue:164`、`FriendRoom.vue:132` |
| `/message/friendSelect` | `FriendRoom.vue:107` |
| `/message/friendAdd` | `FriendRoom.vue:159` |
| `/message/selectFriend` | `FriendRoom.vue:194` |
| `/message/friendUp` | `FriendRoom.vue:226`※ |
| `/message/friendDe` | `FriendRoom.vue:228`※ |
| ~~`/message/getOrCreateConversation`~~ | **无调用方**（前端 0 命中；`cache-layer.md` 记为**有意保留**的孤儿读接口） |
| ~~`GET /health/db`~~ | **无前端调用方**（运维接口，消费者 = A3 脚本 P5/P6/P7） |
| ~~`/user/getUserInfo`~~ | **无调用方**（**P2.6**，2026-10-05 起：3 个调用点全改走 `/user/me`；端点保留的理由见 `UserService.getUserInfo` 的 javadoc） |
| `/user/logout` | `UserCenter.vue:64`（**翻开关笔**，2026-10-05 起：退出时销服务端会话，`allowUnauthorized: true` + fail-open）—— 此前（2026-10-04 笔 2 起至翻开关笔前）**无调用方** |

### 5.1 三处「变量 URL」（静态 grep 看不到，须读分支） {#api-5-1}

| 文件 | 判定条件 | 两端取值 |
|---|---|---|
| `GoodsSelect.vue:83-91`（R2 重指；R1 记 `:82-90`、原记 `:71-80`） | `kw === ''` | 空 → `goodsSelectHome`；非空 → `productSearch`（body = `keyword=<encodeURIComponent(kw)>`） |
| `OrderCenter.vue:122-129` | `orderStatus.value === 'completed'` | `orderDeliverySelect`；否则 `orderDeliveryIfSelect`（两分支同传 `merchantId` + `status`） |
| `FriendRoom.vue:224-229` | `action === 'agree'` | `friendUp`；否则 `friendDe` |

> ※ 这三处的 `fetch(` 本身分别为 `GoodsSelect.vue:92`（R2 重指；R1 记 `:91`、原记 `:81`）/ `OrderCenter.vue:129` / `FriendRoom.vue:231` —— 三者的 URL 都是变量，故上表标的是 **URL 赋值行**。

### 5.2 未验区域（2026-09-30 边界） {#api-5-2}

- **15 个写接口全部未验**：`loginAdd`、`updateAvatar`、`updateUserInfo`、`goodsAdd`、`goodsDe`、`goodsUp`、`orderAdd`、`orderPayIfUp`、`orderReceiveIfUp`、`orderDeliveryIfUp`、`/message/send`、`friendAdd`、`friendUp`、`friendDe`、**`/message/history`（名为读、实为写，见 §1）**。
- **已验的 17 个读端点**：全部 **HTTP 200**，**0 次 5xx、0 次超时**（2026-09-30，最小构造入参）。
- **参数名级不匹配：0 条**。字段/语义级问题见 `defect-survey.md`。
---

## 附录 C：C3 块 4〈订单块〉契约增补（2026-10-10） {#api-app-c}

> **本附录与正文的关系**：正文 §1 的 `O:` 一栏是 **2026-10-03 / 10-04 的旧坐标**；块 4 让 `OrderController` 由 65 行长到 **156 行**（+91，逐处见 `slot-registry.md §二十`）⇒ **全部 `O:` 值已陈旧**。按 §十五 15-5「已陈旧者只登记、不修」，本笔**就地不动正文**，把新坐标与新端点登记在此；重填 `O:` 归「文档飘移清理笔」。
> **判据档位**：本附录所有行号均为 **2026-10-10 实测**（`grep -n '@PostMapping'`，口径 = **`@PostMapping` 行**，与正文一致、与 `db-schema.md#db-top` 相反）。

### C.1 正文 `O:` 一栏的新坐标（2026-10-10 实测） {#api-c-1}

| 端点 | 正文旧值 | **新行号** | 证据 |
|---|---|---|---|
| `/order/orderAdd` | `O:42`（正文 `:125`） | **`:95`** | `OrderController.java:95` |
| `/order/orderSelect` | — | **`:121`** | `:121` |
| `/order/orderDeliveryIfSelect` | — | **`:134`** | `:134` |
| `/order/orderDeliverySelect` | — | **`:149`** | `:149` |
| `/order/historicalOrdersSelect` | — | **`:165`** | `:165` |
| `/order/orderPayIfUp` | — | **`:184`** | `:184` |
| `/order/orderReceiveIfUp` | — | **`:197`** | `:197` |
| `/order/orderDeliveryIfUp` | — | **`:215`** | `:215` |

⚠️ 「正文旧值」只填了能逐字读到的 `:125` 那一条；其余 7 行我**没有逐条回读正文原值**，故留 `—` —— 不猜（错一个值比留空更坏）。
⚠️ 同类陈旧的还有正文 `:196` 的**前端调用点**：`/order/orderAdd` 现由 `ProductPage.vue:638` 发起（`handlePay` 的 'buy' 支，实测），正文写的 `ProductPage.vue:369` 自 **2026-10-08 六条改动**起即已陈旧（**不是块 4 打破的**）⇒ 同按「只登记、不修」处理。

### C.2 `/order/orderAdd` 改动（入参 9 → 10） {#api-c-2}

| 项 | 内容 |
|---|---|
| 新增入参 | `Quantity` —— `@RequestParam(value="Quantity", required=false, defaultValue="1") int`（`OrderController.java:105`） |
| 兼容性 | **向后兼容**：老调用方不传 ⇒ 落 `1`，与块 4 前语义逐字一致 |
| 新增守卫 | 方法第一行 `IdentityGuard.requireSelfOrAdmin(request, CustomerId)`（`:110-113`）⇒ 越权访问 **403**（**不是** 200+`success:false`，本仓唯一例外） |
| ⚠️ 判的是谁 | **`CustomerId`（买家自己）**，不是 `MerchantId`（对端方，当然要填别人的）。改这行前先读该处注释 |
| 三道既有闸 | 价格闸 / 出生状态闸 / `runLocked` **顺序与语义零变化**（正文 `:150`/`:151` 仍有效） |

### C.3 新端点组：`/cart/*`（`CartController.java` @ `:40`，121 行） {#api-c-3}

> 契约要点：**5 个端点全部 `POST`**、入参全为 `@RequestParam`、**每个都先过 `IdentityGuard.requireSelfOrAdmin(request, CustomerId)`**（越权 403）；`CustomerId` 一律**必填**，缺失 ⇒ `{success:false,msg:"缺少参数：CustomerId"}`。
> ⚠️ 与正文口径一致：失败 = **HTTP 200 + `success:false`**（唯一例外是上面那个 403）。**无 `@Transactional`**（本仓订单链路的结构性约束）。

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/cart/add` | CustomerId, MerchantId, ProductCode, `Quantity?`（缺省 1） | `{success, msg}` | **否** —— 同 `(CustomerId, ProductCode)` 已在车里 ⇒ **合并数量、不新开行**（靠 `uk_cart_customer_product`） | `:50` |
| POST | `/cart/update` | CustomerId, ProductCode, `Quantity?` | `{success, msg}` | **是**（绝对赋值，置值语义） | `:67` |
| POST | `/cart/remove` | CustomerId, ProductCode | `{success, msg}` | **是** | `:81` |
| POST | `/cart/list` | CustomerId | `{success, list}` | **是**（纯读） | `:94` |
| POST | `/cart/checkout` | CustomerId, `ProductCode?`（不传 ⇒ **整车**） | `{success, msg, list}` | **否** | `:110` |

**`/cart/checkout` 逐字口径**（`CartService.java:160-198`）：

- 逐行建订单 ⇒ 每行走 **既有** `OrderService.orderAdd(...)`（锁 + 三道闸一个不绕），出生状态**恒 `(PayIf,DeliveryIf,ReceiveIf) = (0,0,0)`** ⇒ 结算完的单在「我的订单」tab 里是**未付款**。
- 行金额 = `(float)(unit × qty)`，`unit` 取 **`PriceSnapshot`**，为空时回 **`goods.Price`（`PriceNow`）** ⇒ 这就是 A1「存快照」的落点（车里的价 = 落库的价）。
- `list` 元素 = `{OrderId, ProductCode, Quantity, Price}`（`:189-192`）；`OrderId` 由后端 `newOrderId()` 现生成，**不由前端提供**。
- ⚠️ **清行顺序**：先 `orderAdd` 成功、**再** `cartDao.remove` —— 「订单真的落库了才清行」，顺序反了就是「钱没了、车空了」（`:185-187` 注释）。
- ⚠️ **部分失败不滚**：中途某行失败 ⇒ 返回 `{success:false, msg, list:[已成功行]}`，**已建订单不回滚**（无事务），**失败行留在车里**（`:170-174` 注释：商品已被删 / 下架时「不动这一行」并中止）。
- ⚠️ `/cart/remove` 是**物理 DELETE** —— 全仓对「规则 v2：逻辑废弃、不物理删」的**唯一破例**（用户明确批准）。理由与代价见 `CartDao` 类注释 + `tech-debt.md`。

### C.4 新端点组：`/admin/order/*`（`AdminOrderController.java` @ `:37`，95 行） {#api-c-4}

> ⚠️ **TD-18 硬线**：本组**不得加角色闸**（不加 `requireAdmin`、前端也不得加角色判断）—— 与其余 `/admin/*` 同款。**只 `/intervene` 挂审计**，其余 3 个是读。
> 参数一律 `required=false` + Service 手判（本仓约束异常会逃逸成 400 空体）。
> 返回体是 `Result<Map<String,Object>>`，而 **`Result` 是 `HashMap` 子类、`Result.of(src)` 逐键平铺**（`shared/web/Result.java:95`）——**没有 `data` 包裹层**。与 `/cart/*`、`/order/*` 的 `ResponseEntity<Object>` 形状不同，前端封装已按此分家（见 §C.5）。

| 方法 | 路径 | 入参 | 返回（**平铺**） | 副作用 | 证据 |
|---|---|---|---|---|---|
| POST | `/admin/order/list` | CustomerId?, MerchantId?, status?, from?, to?, limit?（Service 侧 clamp 上限 **500**，`limit<=0` ⇒ 200） | `{success, list, total, truncated}` ⚠️ **两字段刻意分流**：`truncated = rows.size() >= 生效上限`（**只表「本次已达上限、后面可能还有」**，与筛选无关）；`total` = **全表** `COUNT(*)`（供「库内共 N 单」），**不随筛**、**不是**截断判据 | 无 | `:49` |
| POST | `/admin/order/detail` | OrderId? | `{success, row, timeline}` | 无 | `:62` |
| POST | `/admin/order/monitor` | — | `{success, row:{unpaidTimeout, undeliveredTimeout, abnormalState, unfinished, total}, unpaidHours, undeliveredHours}` ⚠️ **五个计数在 `row` 里，两个阈值在顶层** | 无 | `:68` |
| POST | `/admin/order/intervene` | OrderId?, Action? | 委托 `OrderService.{payIfUp\|deliveryIfUp\|receiveIfUp}` 的返回体（`{success, msg}` 家族） | **写 `order`（沿既有 CAS 边强制推进）+ 记 1 行 `admin_audit` + 双向通知** | `:79` |

- `Action` 白名单 = **`pay` / `deliver` / `receive`**（= 既有三条 CAS 边，**不新增状态机边**）；`Action = refund` 被 **Service 层明确拒绝**（不是「未知动作」）—— `order` 表**没有资金态**，强行回滚会破坏三位单调性 ⇒ 记为「已知但未实现」。
- 阈值 `unpaidHours` / `undeliveredHours` **随响应回传**（24 / 48，`AdminOrderDao` 常量），前端**不得写死**。⚠️ 本仓**无任何 SLA 配置** ⇒ 这两个数是「拍的」，已登记待裁。
- 归属校验（A3 连带）落在这三条 CAS 端点：`orderDeliverySelect` 的守卫在 `OrderController.java:156`（其余两条同款）。
- ⚠️ **通知是 fail-soft**：`notifyBoth` 任何异常静默返回 ⇒ 「通知丢了」与「通知成功」**在响应体上同形**。验收必须**回库读 `notification` 表**，不能看接口的 `success`（`AdminOrderService.java:183-187` 注释）。
- ⚠️ **强制发货 / 强制完成各让「该轮被推进的对端」收两条通知**：一条「对端通知」（`OrderService` 里用户自己操作也走的那条）+ 一条「管理员介入订单」——**发货轮买家 2 条、收货轮商家 2 条**，另一端各 1 条。**这是有意的**，已登记 `tech-debt.md` TD-24。
- ⚠️ 三条 CAS 端点各自的**业务闸**（如 `payIfUp` 的前置态检查）**不由本组复制**，全部复用 `OrderService` 原路径 ⇒ 契约与用户端点击同一条边。

### C.5 前端封装（供对账） {#api-c-5}

新增 `src/api/order.js`（**233 行**）：`q()`（丢空值）/ `build()`（全传）/ `post()`（剥 `Response`）三个内部件 + **8 个 `/order/*`** + **5 个 `/cart/*`** + **4 个 `/admin/order/*`** 导出。
`src/api/endpoints.js` 的 `orderAdmin: {list, detail, monitor, intervene}` 见 `:194-198`；`endpoints.js` 头部「现为 N 条」计数已**同行改**（**未新增行**）。

## 附录 D：C3 块 4 **补丁**〈付款 / 发货 模拟完整流程〉契约增补（2026-10-10） {#api-app-d}

> **与附录 C 的关系**：C 记的是块 4 本体（`OrderController` 65 → 156 行）；本补丁又让它长到 **252 行**（+96）⇒ **C.1 那张表的新坐标已再次陈旧**。按同一条口径（只登记、不修），补丁的坐标登记在此；重填 `O:` 与 C.1 一律归「文档飘移清理笔」。
> **判据档位**：本附录行号均为 **2026-10-10 实测**（`grep -n '@PostMapping'`，口径 = **`@PostMapping` 行**，与正文 / 附录 C 一致）。

### D.1 `OrderController` 端点新坐标（2026-10-10 实测，含 C.1 的再一次偏移） {#api-d-1}

| 端点 | C.1 值 | **补丁后** | 变化来源 |
|---|---|---|---|
| `/order/orderAdd` | `:95` | **`:95`** | 不变（在其之前的只有 import / 字段） |
| `/order/orderSelect` | `:121` | **`:121`** | 不变 |
| `/order/orderDeliveryIfSelect` | `:134` | **`:134`** | 不变 |
| `/order/orderDeliverySelect` | `:149` | **`:149`** | 不变 |
| `/order/historicalOrdersSelect` | `:165` | **`:165`** | 不变 |
| `/order/orderPayIfUp` | `:184` | **`:184`** | 不变（**只加了一行 `@RequestParam`，方法体行未前移**） |
| `/order/orderReceiveIfUp` | `:197` | **`:199`** | +2（付款方法多了 1 行入参 + 1 行调用改写） |
| `/order/orderDeliveryIfUp` | `:215` | **`:217`** | +2（收货方法多了 1 行审计块重排） |
| `/order/orderDetail` | —— | **`:245`** | **补丁新增（A5）** |

⚠️ **`OrderController` 的「不动」是刻意保全的**：三个 CAS 端点的**锁 tag / 文案 / 锁编排**一行未改，只有入参多了 `@RequestParam(required=false)` 与方法体里的一行调用改写（见 D.3）。

### D.2 新端点：`POST /order/orderDetail`（买家订单详情，A5 —— 用户 2026-10-10 批） {#api-d-2}

| 项 | 内容 |
|---|---|
| 路径 | `/order/orderDetail`（`@RequestMapping("/order")` 之下） |
| 入参 | `orderId`（**必填**，`@RequestParam("orderId") String`） |
| 返回 | `{success, row, shippingTrack}` —— ⚠️ **平铺**（`Result<Map>`），**没有 `data` 包裹层**、**没有 `timeline`**（那是管理端 `/admin/order/detail` 才有的状态流转线） |
| `row` | 订单行，**显式列清单**含补丁的 7 个明细列（`TradeNo` / `PaymentMethod` / `PaidAt` / `ExpressCompany` / `TrackingNo` / `ShippedAt` / `ReceivedAt`）；存量行这些列全 `null` |
| `shippingTrack` | 数组，一行 = `{Status, Description, TrackedAt}`，**按 `TrackedAt` 升序**（排序键不是 `Id`） |
| 归属 | 第一行 `isOwner(request, orderId, false)`（**买家**）⇒ 看别人的单 **403**；**单不存在放行**、由 Service 出 `success:false` +「订单不存在」（**不是** 403，理由同 `isOwner` 三条回退口径） |
| 副作用 | **纯读**，不写审计、不改库 |
| 证据 | `OrderController.java:245` · `OrderService.orderDetail` · `OrderDao.selectByOrderId` + `ShippingTrackDao.selectByOrderId` |

### D.3 三个 CAS 端点的入参扩展（**全部向后兼容**） {#api-d-3}

| 端点 | 新增入参 | 兼容性 | 副作用 |
|---|---|---|---|
| `/order/orderPayIfUp` | `paymentMethod?`（`@RequestParam(value="paymentMethod", required=false)`） | **兼容**：不传 ⇒ 落 `NULL`（**不是**空串） | 成功后 Service **生成 `TradeNo`**（`PAY+yyyyMMdd+6位随机`）并在**响应体**回传 `tradeNo`；同笔写 `order` 的 3 个支付列 |
| `/order/orderReceiveIfUp` | 无 | 不变 | 成功后写 `order.ReceivedAt` **且**保证 `delivered` 轨迹行存在（`NOT EXISTS` 幂等） |
| `/order/orderDeliveryIfUp` | `expressCompany?` / `trackingNo?` | **兼容**：都不传 ⇒ 两列 `NULL`（管理员代发走的 1 参重载即是此路） | 成功后写 `order` 的 3 个物流列；**不写轨迹**（2026-10-10 起四节点全移交调度器，见 §D.4） |

⚠️ **`intervene` 零改**：`AdminOrderService.intervene` 调的是 **1 参重载** `payIfUp(orderId)` / `deliveryIfUp(orderId)` / `receiveIfUp(orderId)` —— 三个重载**逐字保留** ⇒ 管理端介入的行为与契约**一个字符没变**（只是它落库时也会写明细列 / 轨迹，那对调用方不可见）。

### D.4 轨迹写入契约（`order_shipping_track`） {#api-d-4}

| 写入方 | 节点 | 时刻 | 幂等 |
|---|---|---|---|
| `OrderService.deliveryIfUp` | **不再写轨迹**（2026-10-10 首条 `picked_up` 移交调度器） | — | — |
| `OrderService.receiveIfUp` | `delivered`（「已签收」）**兜底补写** | `now` | `ShippingTrackDao.exists(orderId, 'delivered')` |
| `ShippingTrackAdvanceJob`（@Scheduled） | `in_transit`（运输中）/ `picked_up`（驿站已揽收）/ `out_for_delivery`（驿站派送中）/ `delivered`（已签收） | `ShippedAt + 阈值` | 同上（每节点各判一次） |

- ⚠️ **调度器只 INSERT 轨迹，绝不 UPDATE `order` 的状态位**（用户 2026-10-10 批准的红线）⇒ **物流「已签收」≠ 订单「已收货」**（后者仍由买家点【收货】触发 CAS），前端分两词表述。
- ⚠️ **阈值走 properties**（`mall.order.shipping.track.{transit,picked-up,delivery,signed}-ms`），**演示默认 15 / 30 / 45 / 60 秒**（2026-10-10 由 30/60/120/240 秒收紧）；生产口径改配置即可。⚠️ **2026-10-11 起时序是「运输中 → 驿站已揽收 → 驿站派送中 → 已签收」**（前两步对调 ⇒ `in_transit` 拿 15s、`picked_up` 拿 30s）。
- ⚠️ **a3 / smoke 两个 profile 显式 `mall.order.shipping.track.enabled=false`**（同 `MerchantDowngradeJob` 款）⇒ 手验调度推进要在**默认 profile** 下做。

### D.5 前端封装增补 {#api-d-5}

- `src/api/endpoints.js`：`order` 组加 `orderDetail`（**与 `historicalOrdersSelect` 同行追加**，`:56` / `:58` 两个被引行**不漂**）；头部「现为 N 条」计数 **78 → 79**（同行改）。
- `src/api/order.js`：新增 `orderDetail(orderId)`（**④ 节，文件末** —— 与 ① 组的「搬家 8 条」在语义上分开）；`orderPay(orderId, paymentMethod?)` 与 `orderDeliver(orderId, expressCompany?, trackingNo?)` 扩参，**空值不发**（与 `build()` 的「全传」相反，此处刻意用 `if` 筛）。
- `OrderMine.vue`：**收银台弹窗**（`PAY_METHODS` 三选一 → `orderPay(orderId, method)`；成功回显 `tradeNo`）+ **物流详情弹窗**（`orderDetail` 的 `shippingTrack` 画时间线）。
- `OrderCenter.vue`：**发货弹窗**（快递公司 / 运单号，**可留空**）；原 `handleDeliver` 的三道守卫**逐字保留**、只从「点击即判」挪到「开窗前判」。
- `views/admin/OrderPage.vue`：详情区加**支付 / 物流 7 列** + **第二条时间线**（`detail.shippingTrack`，**与 `detail.timeline` 并列不合并**）。

## 附录 E：可视化段契约增补（commit 0 / 0b / 2 / 3 / 4，2026-10-11） {#api-app-e}

> **范围**：`feat(fullstack)` 的 5 笔 —— commit 0〈`order_item` + `orderAdd` 可选 `Items`〉、commit 0b〈购物车**按商家分组**结算〉、commit 2〈经营数据看板 3 端点〉、commit 3〈补丁：承诺位 / `SkuSpec` / `skuList` / `login.CreateTime` / `/user/me` 补 `username`〉、commit 4〈`Source` 标记列，**无契约面**〉。
> **判据档位**：本文 `:<行>` 一律 **2026-10-11 实测**（`grep -n '@PostMapping'`，口径 = **`@PostMapping` 行**，与正文 / 附录 C / D 一致）。
> ⚠️ **新增端点只有 4 个**：`/goods/skuList` · `/admin/stats` · `/admin/stats/rebuild` · `/merchant/stats`。**没有任何旧端点被删、被改名、被改必填性**（硬线「禁止删除旧接口、旧字段」「不能阻碍旧功能」）。

### E.1 新端点组 `/admin/stats` + `/merchant/stats`（commit 2） {#api-e-1}

| 动词 | 路径 | 入参（全 `@RequestParam`，全 `required=false`） | 返回 | 副作用 | 坐标 |
|---|---|---|---|---|---|
| POST | `/admin/stats` | `from?` · `to?` · `days?` | `{success, builtRows, range, days[], totals, prevTotals, merchants[]}` | **纯读** | `AdminStatsController.java:56` |
| POST | `/admin/stats/rebuild` | — | `{success, deleted, siteRows, merchantRows, totalRows}` | **写**：`stats_daily` 全清 + 从 `order` 重算 upsert | `AdminStatsController.java:71` |
| POST | `/merchant/stats` | `MerchantId?` · `from?` · `to?` · `days?` · `goodsLimit?` | 同全站形状 **+ `MerchantId` + `topGoods[]`** | **纯读** | `MerchantStatsController.java:52` |

- ⚠️ **参数只认查询串**：全 `@RequestParam`、类中无 `@RequestBody` ⇒ 必须 `POST /admin/stats?days=30`；**把 `days` 放进 body 不会被解析**（这是最容易踩的一条）。`days` 给了就按「最近 N 天」（相对**库时钟**，也是**唯一能给环比**的形态）；否则 `from`/`to`；都没给 ⇒ 全部已聚合日。
- ⚠️ **错误一律 HTTP 200 + `{success:false, msg:"经营数据读取失败：…"}`**（重建失败是 `"重建失败：…"`）—— 同全仓口径：**没有 4xx/5xx、没有全局异常处理**。前端**必须**读 `success`，不能只看 HTTP 码。
- ⚠️ `days[]` 每行 = `{StatDate, MerchantId, Orders, Gmv, Items, Buyers, aov}`（全站行的 `MerchantId` 是空串）；`totals` / `prevTotals` 同形状。
- ⚠️ **`aov` 是读时现除**（`Gmv / Orders`，`Orders=0` 时给 0/—），**刻意不落列**（理由见 `db-schema.md §13.2`）。
- ⚠️ **`Buyers` 是「人日」，不是人数**：逐日 `COUNT(DISTINCT CustomerId)` ⇒ **跨日相加会重复计人**；且 `site` 与 `merchant` 两口径**不可互推**（同一人当天在两店各下一单，全站算 1、两店相加算 2）。
- ⚠️ **`topGoods` 上线即空**：`order_item` **不回填存量单**（用户裁定）⇒ 历史单没有明细行。**不是缺陷**（见 `db-schema.md §13.1`）。
- ⚠️ **3 个端点都不加角色闸**：与其余 `/admin/*` 同款（TD-18 例外）—— 真正把它们挡在门外的是 `app.auth.enabled`（默认 profile 开）+ `AuthInterceptor`；加「须 role=3」会让**默认 profile 上管理员自己也 401**。⚠️ 别「顺手」补 `requireAdmin`。
- ⚠️ **`rebuild` 不挂审计**（与 `/admin/order/intervene` 的唯一区别）：`admin_audit.TargetId` 是 NOT NULL，而 rebuild 没有业务对象可指、也不改任何对象**状态**，只重建一份派生表。
- ⚠️ 端点**没进** `AdminApp` 的路由白名单？—— 是**有**的：`/admin/stats` 由 `views/admin/StatsPage.vue`（同 commit 2）调用；商家端由 `views/merchant/StatsPage.vue` 调 `/merchant/stats`。
- ⚠️ **入参动词选 POST 不是 GET**（方案没写动词）：本仓管理端的读端点**全部**是 POST（`/admin/order/list` · `/admin/audit/list` · `/admin/catalog/*` …）⇒ 按**同域一致**办。**别照 REST 习惯改成 GET**（理由逐字在 `AdminStatsController` 块首）。

### E.2 商品侧：新端点 `POST /goods/skuList` + `goodsAdd` 扩参（commit 0 补丁） {#api-e-2}

| 项 | 内容 |
|---|---|
| 路径 | `/goods/skuList`（`@RequestMapping("/goods")` 之下）—— `GoodsController.java:244` |
| 入参 | `MerchantId`（**必填**，`@NotBlank`）· `GoodName`（**必填**，`@NotBlank`） |
| 返回 | `{success, list}`。**空 `list` = 该商品没有多规格** ⇒ 前端回落到既有的「单型号 `ProductCode`」路径 |
| 归属 | **不做身份闸**（与 `productCodePrice` 同款）：读的是公开可见的商品信息，加闸会让未登录浏览路径拿不到规格 |
| 证据 | `GoodsService.skusByMerchantGoodName`（`goods_sku` 按 `(MerchantId, GoodName)` 取、按 `Sort` 升序） |
| ⚠️ 与下单链路的接口 | `goods_sku.SkuCode` 在**加入购物车时作为 `ProductCode` 上报** ⇒ `/cart/add` 与 `/order/orderAdd` 的契约**一个字没改**（`db-schema.md §13.3`） |
| ⚠️ `Stock` **只存不扣** | 本端点答的是商家填的库存**陈列值**，**不是**可售余量；下单**不会**减它（用户已同意单独一轮） |

- **`POST /goods/goodsAdd` 扩参 11 → 16**（`GoodsController.java:122`）：**尾部追加** `SkuSpec` + `PromiseGenuine` / `PromiseReturn7` / `PromiseFastRefund` / `PromiseFreightIns`，**全部 `required=false`**。
  - ⚠️ **向后兼容的判据不是「不报错」，而是「与改前同效」**：4 个承诺位缺失（`null`）时由 `GoodsAuditService.boolOrTrue` 兜成 **「真」** ⇒ 老前端不传，详情页仍显示四条承诺（改前是**每件**商品无条件显示）⇒ **旧行为不变**。新的 `goods` 行因为 `DEFAULT 1` 也仍是四条。
  - ⚠️ 既有的 11 个形参**一名不动、一位不挪**（规则 7 的「加在表末」同款口径）。
  - ⚠️ 真库用例的连带：`GoodsAuditTxAtomicityTest` / `GoodsWriteTxAtomicityTest` 的 4 个调用点已同步补 5 个 `null`（**maven 增量编译会复用陈旧 test class 把签名破坏遮住 ⇒ 只有 `clean test` 是真判据**）。

### E.3 订单侧：`/order/orderAdd` 扩参（commit 0）+ 结算口径（commit 0b） {#api-e-3}

| 项 | 内容 |
|---|---|
| 路径 | `/order/orderAdd` —— `OrderController.java:95`（**坐标未动**） |
| 扩参 | 10 → **11**：尾部追加 `Items?`（`@RequestParam(value="Items", required=false, defaultValue="")`，**JSON 数组串**） |
| 语义 | `Items` 非空 ⇒ 逐条落 `order_item`（`{ProductCode, GoodName, Price, Quantity}`）；**空/缺省 ⇒ 完全回落到改前的路径**（把 `ProductInformation` 当唯一明细） |
| 兼容性 | 既有的 10 个形参**一名不动、一位不挪**；`defaultValue=""` 与前端 `order.js` 的 `build()` 把 `undefined` 转空串**同效** ⇒ 服务端一律按 `isBlank` 判空 |
| ⚠️ 多品单**只能挂一个商家** | `order.MerchantId` 是全单**唯一**字段 ⇒ 跨商家的一车要被拆成多张单（见下条），本端点**不**负责拆 |
| 证据 | `OrderService.resolveItems`（解析 + 校验）、`OrderService.addLocked` 的 `if (rows > 0)` 分支（明细与主单在同一把 order-add 锁内；本仓订单链**禁 `@Transactional`**） |

- ⚠️ **`POST /cart/checkout` 的结算口径改了（commit 0b）：逐行建单 → 按商家分组建单**（`CartController.java:110`）。**响应形状未变**（仍是 `{success, msg, …}` 家族），但**同样的车得到的主单张数变了**：改前 N 行 ⇒ **N 张单**，现在 N 行 ⇒ **M 张单**（M = 车主涉及的商家数）。`ProductCode` 传了 ⇒ 只结算那一行（那一行自然只属一个商家，**行为与改前一致**）。
  - ⚠️ 这是本笔**唯一一处调用方可见的语义变化**（不是端点/字段增删）—— 前端 `ShoppingCart.vue` 的文案与提示随之改。

### E.4 `POST /user/me`：管理员降级作答补 `username`（commit 3，**用户裁决「甲」**） {#api-e-4}

| 出口 | 改前 | **改后** |
|---|---|---|
| cookie 会话 + `role=3`（管理员） | `200 {success:true, role:3}` | `200 {success:true, role:3, username:"…"}` |
| 其余三出口（无身份 / 非管理员 / 业务号查不到） | 不变 | **不变** |

- ⚠️ **授权口径一字未动**：`customerid` / `merchantid` / `usertype` / `gender` / `image` / 资料**仍不给**；`authorize` 仍把 cookie 来的 `role=3` 折成 `null`。补的**只是「你是哪个登录名」**；`username` 取自 `publicInfo`（按**业务号**查、带缓存），**查不到就只答 `role`**（不编造、不报错）。
- ⚠️ **为什么必须补**：前端 `api/auth.js` 的 `isLoggedIn()` 判据是 `!!(username || merchantid)` ⇒ 只答 `role` 时，管理端在**新标签页 / 重开浏览器**（cookie 在、token 不在）恒判「未登录」⇒ 被登录守卫弹回 `/login`（实测「管理端进不去」）。
- ⚠️ **同笔修掉的一个真缺陷（问题①）**：`/user/me` 答的 `role` 是 **JSON 数字 3**，而全站**一律按字符串比**（`router/index.js` 的 `meta.roles`、`ROLE_HOME` 键、`roleKey()`、`AdminShell.vue` 的角色名表）⇒ 恢复路径上每个角色比较**恒 false** ⇒ `ROLE_HOME[3] = undefined` ⇒ 重定向成环 ⇒ `router.isReady()` reject ⇒ `app.mount('#app')` **永不执行**（整页深色、无内容）。
  **修法选在收端**：`api/auth.js` 里 `if (info.role) next.role = String(info.role)` —— **服务端契约不变**（`role` 仍是数字），只在客户端归一化（同 `LoginPage` 走 `res.text()` 本来就是字符串的形态）。
- ⚠️ **测试判据已随之改写**（不是改代码去迁就测试）：`AuthTokenSmokeTest` 原断言「`publicInfo` 一次不调」**已不成立**（那一跳现在必须走）⇒ 改为「键集**恰好**是 `{success, role, username}`」+「`publicInfo` **恰好 1 次**」+「资料跳 / 头像跳**各 0 次**」。⚠️ 只断言「键缺失」是**弱判据**（「查了整行但没查到」也会变绿）。

### E.5 `/admin/accounts/users`：行新增 `CreatedAt`（commit 3） {#api-e-5}

| 项 | 内容 |
|---|---|
| 坐标 | `AdminAccountsController.java:73`（`@RequestMapping("/admin/accounts")` 之下，**路径与入参一字未改**） |
| 变更 | 每行**多一个键** `CreatedAt` = `DATE_FORMAT(l.CreateTime, '%Y-%m-%d %H:%i:%s')`（**只加键，不改旧键**） |
| ⚠️ 必须 `LEFT JOIN` | **不是** `INNER JOIN`：`login` 里没有对应行的账号**仍要出现**（`INNER` 会让这类账号整行消失 —— 那是「删数据」不是「加列」） |
| ⚠️ **存量行恒 `null`** | `login.CreateTime` **不回填**（用户裁定）⇒ 老账号答 `null`，前端画 `—` |
| 证据 | `UserGovernanceDao.java:57-70` |

### E.6 前端封装增补 {#api-e-6}

- `src/api/endpoints.js`：`goods` 组加 `skuList`（**与 `goodsProductCodeSelect` 同行追加** —— 本组下方各条被 view 与 `slot-registry.md` 按行号引，插行会整体下移）；**新增 `stats` 组 3 条**（`admin` / `adminRebuild` / `merchant`，**追加在文件尾**）；头部「现为 N 条」计数 **79 → 83**（同行改）. ⚠️ 本文件里**不出现**任何后端路径字面量以外的逻辑，故「4 个新端点是否都登记」可用 `grep` 直接判。
- `src/api/stats.js`：新封装 3 条（`q()` 组装 `URLSearchParams`、`post()` 拆 `Response` 外壳，形状与 `api/admin.js` 有意一致）。⚠️ **没抽公共模块是刻意的**（本仓不建 `src/utils/`，同 `api/order.js` 的日期格式化多份副本）。
- `src/api/auth.js`：`String(info.role)` 归一化（见 E.4）—— ⚠️ 该行**与前后三行同行压缩是故意的**（保住 `:108` / `:118` / `:146` 三个被引行号）。
- **commit 1〈可视化体系〉与 commit 3 的图标、`chartTokens` / `LineChart` / `KpiCard` / 四页 admin / nav / router 改动**：**纯前端，零端点增减** —— 只改渲染与图标（`components/ui/glyphs.js`），故不在本契约文件另立条目。⚠️ 其中「四页 admin + nav + router 的图标行」与 commit 3 的后端改动**在同一文件里同行**（`git add -p` 在本环境不可用），这是 commit 3 / 4 切分时把 C1 折进 commit 3 的直接原因。
- ⚠️ `docs/美化/看板规范.md §2 规矩 5` 的字形清单**已于 2026-10-11 文档收口订正**（原 11 枚 ⇒ 19 枚，见该节「订正记录」）—— 这条不属本附录的契约面，只作坐标登记。

### E.7 坐标漂移登记（只登记、不修） {#api-e-7}

⚠️ **附录 D.1 的 `OrderController` 坐标已**再次陈旧】 —— commit 0 在 `orderAdd` 尾部加了 1 个入参（+注释），其后各行集体 **+6**（`orderAdd` 本身 `:95` **未动**）：

| 端点 | D.1 值（2026-10-10） | **本笔实测（2026-10-11）** |
|---|---|---|
| `/order/orderAdd` | `:95` | **`:95`**（不变） |
| `/order/orderReceiveIfUp` | `:199` | **`:205`** |
| `/order/orderDeliveryIfUp` | `:217` | **`:223`** |
| `/order/orderDetail` | `:245` | **`:251`** |

⚠️ 同理，`GoodsController` 的 `G:` 一族坐标（正文 §2）本笔**未重测**、`goodsAdd` 之后的端点已因扩参整体后移（`goodsAdd` 在 `:122`、`skuList` 在 `:244`）—— 按本文件既有口径，**重填归「文档飘移清理笔」**，本附录只登记。

## 附录 F：C3 块 5〈营销块〉契约增补（2026-10-11/12） {#api-app-f}

> **块 5 = 两个新域 + 36 个新端点**，全部 `POST`、全部 `@RequestParam`（无 `@RequestBody`）、全部 `required=false` + Service 手判（除下表标「必填」的那几个）。
> 逐条坐标 = 该方法的 `@PostMapping` 所在行（**本文件口径**，与 `db-schema.md#db-top` 相反）。

### F.0 三个「端点总数」要分开读（口径注，别互抄） {#api-f-0}

| 口径 | 定义 | 块 5 前（`main`） | 块 5 后（HEAD） | 出处 |
|---|---|---|---|---|
| **后端 method-level** | 全仓 `*Controller.java` 中 `^\s*@(Post\|Get\|Put\|Delete)Mapping` 行数（**不含**类级 `@RequestMapping`） | **84** | **120**（+36） | 2026-10-12 实测（`git grep … main` / HEAD） |
| **本文件 §2 的「36 个」** | 只数 **5 个核心 Controller**（`/user` 8 · `/message` 10 · `/goods` 9 · `/order` 8 · `/health` 1） | 36 | **36（一字未变）** | §2 标题 |
| **前端 `endpoints.js`** | `key: '/path'` 条目数（含 2 处**同行两条**） | 83 | **119** | 该文件头注记账链（… → 107 → 119） |

⚠️ **块 5 的 36 个端点全部落在 §2 与 §3 的 5 个 Controller 之外** ⇒ §2 的「共 36 个」**不需要改**（它本来就不是全仓计数）；本附录就是那 36 条的契约面。
⚠️ **`marketing` / `shop` 两域不进 §2 的清单**（同附录 C/D/E 的手法：零漂移，正文表不插入）—— 域标题只在**本附录**里出现。

### F.1 新域 `/admin/marketing/coupon/*` —— 管理端券 7 条 {#api-f-1}

`AdminCouponController.java`（`@RequestMapping("/admin/marketing/coupon")` @ `:38`）—— 返回一律 `Result<Map<String,Object>>`。

| 路径 | 入参（全部 `required=false`） | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/list` | `Status`（走白名单，未知值报错**不静默当「不筛」**）· `keyword` · `limit` | `{success, list:[…], total}` | 闸后（**无角色闸**，见下） | `:50` |
| `/create` | `Name` · `Type`(threshold/percent/none) · `FaceValue` · `Threshold` · `TotalQty` · `Scope`(all/merchant) · `MerchantAccount`（空串 = 平台券）· `CategoryMatch` · `StartAt` · `EndAt` | `{success, msg?, CouponId}`（成功回带业务号） | 同上 | `:60` |
| `/stop` | `CouponId` | `{success, msg?, before}`（`before` = 改前状态快照） | 同上 | `:86` |
| `/activate` | `CouponId` | 同 `/stop` 形状 | 同上 | `:95` |
| `/grants` | `CouponId` · `limit` | `{success, coupon:{…}, list:[…]}`（券定义 + 领取明细并排） | 同上（**读**，不留痕） | `:104` |
| `/grant` | `CouponId` · `ToAccount` ⚠️ **= 登录名 `login.UserName`**（2026-10-12 用户裁定「乙」，非 `user.customerid`） | `{success, msg?}` | 同上 | `:121` |
| `/stats` | **无参** | `{success, claimTrend:[…], usedTrend:[…], typeShare:[…]}`（三件齐里的「图」） | 同上（读） | `:139` |

- ⚠️ **不给 `/admin/**` 设角色闸（TD-18）**：管理员 cookie **铸不出 token** ⇒ 加「须 `role=3`」会让**默认 profile 上管理员自己 401**，而放进白名单是放宽。**别顺手补 `requireAdmin`**（类 javadoc `:21-24`）。
- ⚠️ **四个写端点挂审计**（`create` / `stop` / `activate` / `grant`），三个读端点（`list` / `grants` / `stats`）**不挂**。`TargetId` 一律传 `CouponId`（业务号）—— `admin_audit.TargetId` 是 NOT NULL，传 null 会在留痕处炸掉整条链路（TD-20）。
- ⚠️ **`grant` 的逐条判据与用户端自助领取同一条路径**（`CouponService.doClaim`）：券存在 / 未停用 / 未过期 / 有余量 / 这个没领过 / **不是他自己发的**。本端点**不加自己的闸**。

### F.2 新域 `/marketing` —— 用户端券 4 条 {#api-f-2}

`CouponController.java`（`@RequestMapping("/marketing")` @ `:55`）—— 返回 `ResponseEntity<Object>`。

| 路径 | 入参 | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/coupons` | `Account` **必填** · `limit` · `thisWeek`（`"1"` ⇒ 只回本周新发布）· `MerchantAccount`（非空 ⇒ 只回该商家的商家券） | 领券中心：可领的券，每张带「我已领」「已领完」两个布尔（供按钮画灰） | `requireSelfOrAdminByUsername(request, Account)` | `:83` |
| `/myCoupons` | `Account` **必填** | 券包（我的券）：排序与分桶在**后端**做，每行带 `Bucket`；回体另带 `redDot` / `expiringCount` | 同上 | `:103` |
| `/coupon/claim` | `Account` **必填** · `CouponId` **必填** | `{success, msg?}` | 同上 | `:119` |
| `/coupon/claimBatch` | `Account` **必填** | **一键领取本周全部**；**不吃 `CouponId`**（「本周」是服务端语义）；逐张走单张路径，返回每张成败；**部分失败仍回 `success:true`** | 同上 | `:138` |

- ⚠️ **两个可选参数都向后兼容**（`thisWeek` / `MerchantAccount` 是**加筛不是收窄**）：既有调用方不传 ⇒ 行为与改动前一致（`ProductPage.vue` 的领券块就是这一类）。
- ⚠️ **核销**（`unused → used`）**不在这四条里** —— 它发生在结算，由 `/order/orderAdd` 带 `CouponId` 触发（见 F.7）。

### F.3 新域 `/admin/marketing/subsidy/*` —— 管理端国补 6 条 {#api-f-3}

`AdminSubsidyController.java`（`@RequestMapping("/admin/marketing/subsidy")` @ `:44`）—— 返回 `Result<Map<String,Object>>`。

| 路径 | 入参 | 返回要点 | 坐标 |
|---|---|---|---|
| `/list` | `Status` · `limit` | 规则列表 | `:62` |
| `/create` | `Name` · `CategoryMatch` · `Ratio` · `Cap`（`0` = 不封顶） · `StartAt` · `EndAt` | `{success, msg?, RuleId}` | `:71` |
| `/stop` | `RuleId` | `{success, msg?}` | `:91` |
| `/activate` | `RuleId` | 同 `/stop` | `:100` |
| `/nominate` | `RuleId` · `GoodsId` | ⚠️ 本域**唯一**会触达商家的写端点（提名 ⇒ 商家侧出一条「待应答」） | `:114` |
| `/nominations` | `Status` · `MerchantId` · `limit` | 提名明细（含商品快照） | `:150` |

### F.4 新域 `/marketing` —— 商家 / 用户端国补 3 条 {#api-f-4}

`SubsidyController.java`（`@RequestMapping("/marketing")` @ `:60`）。

| 路径 | 入参 | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/subsidy/invitations` | `MerchantId` **必填** · `Status`（省略 ⇒ 全部） · `limit` · `OnlyOpen` | 发给我的国补提名（页面自己分「待处理 / 已处理」） | `requireSelfOrAdmin` | `:83` |
| `/subsidy/confirm` | `MerchantId` **必填** · `Id` **必填** · `Approve` · `Reason` | `{success, msg?}` | 同上 | `:104` |
| `/subsidy/goods` | `GoodType` · `limit` | 国补商品列表（**用户端标签 / 筛选的数据源**），每行带现算的 `SubsidyAmount` | **无身份闸**（闸后读面，不进白名单 ⇒ 需登录） | `:140` |

- ⚠️ **`/subsidy/goods` 是入口**：主页 / 搜索页 / 详情页的「国补」绿标都读它（`marketingSubsidy` 组的读端）。

### F.5 新域 `/admin/marketing/seckill/*` —— 管理端秒杀 6 条 {#api-f-5}

`AdminSeckillController.java`（`@RequestMapping("/admin/marketing/seckill")` @ `:54`）—— 返回 `Result<Map<String,Object>>`；**邀请 / 审核 / 停用三条挂审计**。

| 路径 | 入参 | 返回要点 | 坐标 |
|---|---|---|---|
| `/invitations` | `Status` · `MerchantAccount` · `limit` | 平台发出的邀请（读） | `:72` |
| `/invite` | `MerchantAccount` · `GoodsId` · `SeckillPrice` · `Quantity` · `ApplyEndAt` · `StartAt` · `EndAt` | 邀请商家上秒杀 —— **本笔触达商家的两个写端点之一** | `:87` |
| `/applications` | `Status` · `MerchantAccount` · `limit` | 商家报名单（读） | `:133` |
| `/review` | `Id` · `Approve` · `Reason` · `Name` · `ApplyEndAt` · `StartAt` · `EndAt` | **通过时这一跳直接建 `seckill_activity`**（审核 = 成立） | `:149` |
| `/activities` | `Status` · `MerchantAccount` · `limit` | 已成立的秒杀（读） | `:191` |
| `/stop` | `ActivityId` | 停用一场活动（逻辑废弃） | `:206` |

### F.6 新域 `/marketing` —— 商家 / 用户端秒杀 6 条 {#api-f-6}

`SeckillController.java`（`@RequestMapping("/marketing")` @ `:56`）。

| 路径 | 入参 | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/seckill/invitations` | `MerchantAccount` **必填** · `Status` · `limit` | 发给我的邀请 | `requireSelfOrAdmin` | `:73` |
| `/seckill/respond` | `MerchantAccount` **必填** · `Id` **必填** · `Accept` · `Reason` | **同意 ⇒ 直接建 `seckill_activity`**（邀请式走这条） | 同上 | `:92` |
| `/seckill/applications` | `MerchantAccount` **必填** · `Status` · `limit` | 我提交的报名 | 同上 | `:124` |
| `/seckill/apply` | `MerchantAccount` **必填** · `GoodsId` · `SeckillPrice` · `Quantity` | 商家报名秒杀（**路径 B 的发起端**，等管理端 `/review`） | 同上 | `:143` |
| `/seckill/activities` | `MerchantAccount` **必填** · `Status` · `limit` | 我的秒杀 | 同上 | `:168` |
| `/seckill/live` | `limit` | **用户端**：进行中 / 未开启的秒杀，每行带 `Stage` 与现算的 `SoldOut` | **无身份闸**（闸后读面） | `:186` |

- ⚠️ **`/seckill/live` 是主页「今日秒杀」模块与 `/seckill` 秒杀页的**唯一**数据源**；`seckill_activity` 是**用户端读侧的唯一表**（邀请 / 申请是流程表，见 `db-schema.md §14.1`）。
- ⚠️ **已售完在秒杀页照显**（用户 ② 口径，2026-10-12）：共享面的过滤（`sharedList`）**不作用**于本端点。

### F.7 新域 `/shop/*` —— 店铺 4 条（**唯一含公开面的一组**） {#api-f-7}

| 路径 | 类 · 坐标 | 入参 | 返回要点 | 身份 |
|---|---|---|---|---|
| `/shop/follow` | `ShopFollowController` `:65` | `CustomerId` · `MerchantId` | `{success, msg?}`；**幂等**（已关注再点仍是 `success:true`，表里仍 1 行） | `requireSelfOrAdmin(request, CustomerId)` |
| `/shop/unfollow` | 同 `:81` | 同 | **物理 `DELETE` 行**（用户 2026-10-12 裁定 8）；本来就没关注 ⇒ 也回 `success:true` | 同上 |
| `/shop/followState` | 同 `:97` | 同 | 关注状态 + 粉丝数（`ShopHome` 头卡一次请求） | 同上 |
| `/shop/profile` | `ShopProfileController` `:56` | `MerchantId` | `{success, MerchantId, UserName, RegisteredAt, CertStatus}` —— 「**这家店本身是谁**」（不要身份） | ⚠️ **无**（**有意公开**） |

- ⚠️ **`/shop/profile` 是块 5 唯一进白名单的端点**：`AUTH_WHITELIST` **12 → 13 条**（`WebAuthConfig.java:116`，加在「业务：登录前后台页面」组末尾）。**四处文案同笔改**：`WebAuthConfig.java:68`（5 → 6 条 + 补「店铺主页」）、`AuthGateSmokeTest.java:180`（`hasSize(12)` → `13`、「业务 8 + 基础设施 4」→「业务 9 + 基础设施 4」）、`AuthGateSmokeTest.java:57`（javadoc 12 → 13）、`MerchantStatsController.java:19`（12 → 13，后半句「没有任何 `/merchant/**`」仍成立）。
- ⚠️ **`followState` 与 `profile` 分工别混**：前者回「**我和这家店的关系**」（要身份、要 `CustomerId`），后者回「**这家店本身是谁**」（不要身份、只要 `MerchantId`）。
- ⚠️ `CertStatus` **查不到就回 `''`，不编成 `pending`**；`RegisteredAt` 源自 `login.CreateTime`，**存量不回填** ⇒ 回 `null`（同 E.5 口径）。前端对两者都是「**没有就不画**」。

### F.8 结算面的契约改动（`/order/orderAdd`，块 5 commit 5） {#api-f-8}

⚠️ **这一条改的是既有端点，不增端点**（故不在 F.1–F.7 的 36 条里）：

| 项 | 内容 |
|---|---|
| 端点 | `POST /order/orderAdd`（`OrderController.java`，坐标见附录 E.7 的 `:95`） |
| 新增入参 | `CouponId`（可选，`required=false` + `defaultValue=""`）—— 非空 ⇒ 走**用券核销**（`coupon_grant.unused → used` 的 **CAS**，带 `used` 判据，防并发双花） |
| 新增落库列 | `order.CouponId` · `order.SubsidyAmount` · `order.PayAmount`（三列同笔加，见 `db-schema.md §14.2`） |
| ⚠️ 口径 | **`order.Price` 保留 = 原价合计**；`PayAmount` = 实付额快照 ⇒ **别拿 `Price` 当实付** |
| ⚠️ 返回面 | 券**真的核销掉**时，回体里**多** `CouponId` / `Discount` 两个键（不传券 / 券没核销掉 ⇒ **两个键都不出现**）⇒ 前端判据是「**有没有 `Discount`**」，不是「`Discount` 是不是 0」 |
| ⚠️ 留痕 | 券核销的审计在**这一跳**挂（此前管理端 `grant` 没有 `OrderId` 可写，见 F.1 末句） |
| 证据 | `CouponService` 的核销腿 + `OrderService` 的结算腿（同笔） |

### F.9 前端封装增补 {#api-f-9}

| 文件 | 增补 | 备注 |
|---|---|---|
| `src/api/endpoints.js` | `marketingCoupon` 7 · `marketing` 4 · `marketingSubsidy` 6 · `subsidy` 3 · `marketingSeckill` 6 · `seckill` 6 · `shop` 4 = **36 条**（全非 view 抽取，唯一调用方是新封装） | 头注「现为 N 条」**83 → 119**；⚠️ 组内 / 文件尾追加，**不插行** |
| `src/api/marketing.js` | **32 个导出**（实测）：管理端券 7 + 用户端券 4 + 管理端国补 6 + 商家侧国补 **3** + 管理端秒杀 6 + 商家侧秒杀 **6** | 一个文件覆盖「营销」侧全部管理端与商家端；⚠️ 六组与上面六个 `endpoints.js` 组一一对应（**36 个端点里** 32 个走本文件，剩 4 条 = `shop` 组） |
| `src/api/order.js` | `orderAdd` 增 `couponId` 透传 | 结算用券 |
| `src/api/shop.js` | **新建**，4 条（`follow` / `unfollow` / `followState` / `profile`）；⚠️ 文件头「三条都不在 `AUTH_WHITELIST`」那句已订正为「**只有 `/profile` 是公开面**，另三条不是」 | 唯一公开面在组内 |

⚠️ **本文件（`api-contract.md`）的正文 §2 / §3 / §5 矩阵**本笔**未重测、未插入** —— 36 条新端点全部落在附录 F；正文坐标重填归「文档漂移清理笔」（同附录 E.7 的口径）。
