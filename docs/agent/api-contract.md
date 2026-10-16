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
| `/order/orderAdd` | `ProductPage.vue:393`（⚠️ **旧行号 · 已知陈旧族，不修** —— 块 6 之前就对不上；2026-10-13 实测真调用点 = `ProductPage.vue:897`（① 甲案 −16 行后），重填归「文档漂移清理笔」，见附录 G 末条） |
| `/order/orderSelect` | `ShoppingCart.vue:113` |
| `/order/orderDeliveryIfSelect` | `OrderCenter.vue:125`※ |
| `/order/orderDeliverySelect` | `OrderCenter.vue:127`※ |
| `/order/historicalOrdersSelect` | `HistoricalOrders.vue:95` |
| `/order/orderPayIfUp` | `ShoppingCart.vue:156` |
| `/order/orderReceiveIfUp` | `ShoppingCart.vue:195` |
| `/order/orderDeliveryIfUp` | `OrderCenter.vue:171` |
| `/message/conversations` | `ChatRoom.vue:184`、`AppShell.vue:161`（**R6 合一重指**：两个壳的 `fetchUnreadCount()` —— `CustomerPage.vue:108` / `MerchantPage.vue:107` —— **已合并到本处**，两文件已随 R6 删除；其沿革：原记 `:97`（R1 时在 `HEAD` 已漂 3、实为 `:100`）⇒ R1/R2 各重指过一次） |
| `/message/history` | `ChatRoom.vue:213`、`ChatRoom.vue:279` |
| `/message/send` | `ChatRoom.vue:327`、`ProductPage.vue:459`（⚠️ **旧行号 · 已知陈旧族，不修** —— 同上；2026-10-13 实测真调用点 = `ProductPage.vue:1002`（① 甲案 −16 行后）） |
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

## 附录 G：C3 块 6〈内容+消息块〉契约增补（2026-10-12/13） {#api-app-g}

> **块 6 = 一个新域（内容域）+ 14 个新端点**（`comment` **8** + `report` 3 + `sensitive` 3）—— 前 13 条是方案 A4 的「十三端点」，第 **14** 条 `POST /comment/adminList` 是 2026-10-13 用户**另批**的契约改动（甲案，见本附录 **G.7**）；全部 `POST`、全部 `@RequestParam`（无 `@RequestBody`）、全部 `required=false` + Service 手判。
> 逐条坐标 = 该方法的 `@PostMapping` 所在行（**本文件口径**，与 `db-schema.md#db-top` 相反）。
> 实现落在**一个后端包** `comment/`（`controller` / `service` / `dao` / `job` 四层 + `CommentKinds`），三个 Controller 一个域。

### G.0 三个「端点总数」要分开读（口径注，别互抄） {#api-g-0}

| 口径 | 定义 | 块 6 前（=块 5 后） | 块 6 后（HEAD） | 出处 |
|---|---|---|---|---|
| **后端 method-level** | 全仓 `*Controller.java` 中 `^\s*@(Post\|Get\|Put\|Delete)Mapping` 行数（**不含**类级 `@RequestMapping`） | **120** | **134**（+14） | 2026-10-13 `grep -rhE "^\s*@(Post\|Get\|Put\|Delete)Mapping" --include=*Controller.java Mall-management-server/src/main/java \| wc -l` 实测：**补笔后的工作区 = 134**；⚠️ **提交态 HEAD（`392361e`）仍是 133**（那 13 条，不含 `adminList`）—— 两列之差 13→14 就是第 14 条 |
| **本文件 §2 的「36 个」** | 只数 **5 个核心 Controller**（`/user` 8 · `/message` 10 · `/goods` 9 · `/order` 8 · `/health` 1） | 36 | **36（一字未变）** | §2 标题 |
| **前端 `endpoints.js`** | `key: '/path'` 条目数（含 2 处**同行两条**） | 119 | **133** | 该文件头注记账链（… → 124 → 125 → 129 → 132 → **133**）；实测 `grep -cE "^[[:space:]]*[A-Za-z0-9_]+:[[:space:]]*'/" endpoints.js` = **131** 行、+2 处同行两条 = 133 |

⚠️ **块 6 的 14 个端点全部落在 §2 与 §3 的 5 个 Controller 之外** ⇒ §2 的「共 36 个」**不需要改**（它本来就不是全仓计数）；本附录就是那 14 条的契约面。
⚠️ **`comment` / `report` / `sensitive` 三域不进 §2 的清单**（同附录 C/D/E/F 的手法：零漂移，正文表不插入）—— 域标题只在**本附录**里出现。

### G.1 新域 `/comment/*` —— 内容域主域 8 条 {#api-g-1}

`CommentController.java`（`@RequestMapping("/comment")` @ `:58`）—— 返回一律 `Result<Map<String,Object>>`。

| 路径 | 入参（全部 `required=false`） | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/list` | `TargetType`（默认 `goods`）· `TargetId` · `Kind`(review/question) · `Sort`(hot/new) · `Solved` · `Limit` · `Offset` | 顶层评论 + 该楼回复 + 统计卡；观众只有**会话**一个来源 | **无身份闸** —— ⭐ **公开面，本块唯一进 `AUTH_WHITELIST` 的一条**（见 G.6） | `:83` |
| `/add` | `Account` · `TargetType` · `TargetId` · `ParentId` · `ReplyToId` · `Kind` · `Content` · `Rating` · `Anonymous` · `ProductCode` + **多 part `Files`** | 评论 / 回复 / 提问 / 回答**四形态共用一个端点**（靠 `ParentId` 与 `Kind` 区分，规则在 `CommentService.add`） | `requireSelfOrAdminByUsername` | `:114` |
| `/delete` | `Account` · `Id` | 逻辑删（`comment.Status='deleted'`）；**顶层删除连带该楼回复** | 同上（管理员可代删） | `:139` |
| `/accept` | `Account` · `Id`（**顶层提问** Id）· `ReplyId`（被采纳的**回复** Id） | 采纳：置顶 + `IsSolved=1` + 通知被采纳那条回答的作者 | 同上（「是不是提问者」判在 **Service**，管理员可代采纳） | `:162` |
| `/like` | `Account` · `Id` · `Type` ⚠️ **必填**（`like` / `dislike`） | 赞 / 踩；**再点同一种是「取消」**（显式传同一个值，不靠「省略 = 切换」） | 同上 | `:189` |
| `/imageReview` | `Action`(`list`/`approve`/`reject`) · `Id` · `Status`(pending/approved/rejected) · `Limit` · `Offset` | 图片审核：一条端点、三个动作，靠 `Action` 分派 | 管理端面 —— **刻意不挂 `requireAdmin`**（见下 ⚠️） | `:218` |
| `/myOrders` | `Account` | 可评论的历史商品（发表评论时「选对应商品」下拉） | `requireSelfOrAdminByUsername` | `:231` |
| `/adminList` | `Kind` ⚠️ **必填**（`review` / `question` / `answer`）· `TargetType` + `TargetId`（可选，**成对**收窄）· `Solved`（仅 `Kind=question` 有意义）· `Limit` · `Offset` | 管理端**全站**列表 —— 修「评论管理 / 问答管理两栏看不到全站待办」；行内带 `IsTimeout`（后端按 `QuestionTimeoutJob.TIMEOUT_MINUTES` **现算**，前端只渲染、**不重算**） | 管理端面 —— **刻意不挂 `requireAdmin`**，且**不在** `AUTH_WHITELIST`（逐字见 G.7） | `:156` |

⚠️ **`/imageReview` 是本仓「管理端页面上的端点」的又一例：不挂 `requireAdmin`** —— 闸开着时 `AuthInterceptor` 已把非管理员的 `role=3` cookie 挡在外面，闸关着时（`app.auth.enabled=false`）本就是本地演示场景。**别「顺手补一个 `requireAdmin`」**（`CommentController#imageReview` 的 javadoc 逐字写了这条）。
⚠️ **`/list` 的观众（`viewerUserId`）只有「会话」一个来源**，且给的只是**业务号**、「业务号 → 登录名」那一跳在 Service 里、**校验通过之后**才做 —— 提前查会把 smoke 切片里 `JdbcTemplate` 替身的参数校验用例打成 NPE（`CommentController` 类注释）。

#### G.1.1 第 14 条 `/comment/adminList` 的契约要点（2026-10-13 补笔） {#api-g-1-1}

- **`Kind` 必填**，且只认 `review` / `question` / `answer` 三个值 —— 缺 ⇒ `success:false` + 「缺少参数：Kind（只能是 review / question / answer）」；未知值另有一句。这与**公开面** `/list` 相反（那边 `Kind` 可省、默认取 `review`）：全站列表没有「默认看什么」可言，默认值只会让人误读「全站只有评价」。
- **`TargetType` / `TargetId` 要么都给、要么都不给**（都不给 = 全站；只给一个 ⇒ `success:false` + 「TargetType 与 TargetId 要么都给、要么都不给（都不给 = 全站）」）。半对参数在公开面 `list` 里是「缺 TargetId」的错；这里能是「全站」是**新语义**，故必须显式区分。
- **`Solved` 只对 `Kind=question` 有意义**：给别的 kind ⇒ **拒**（`success:false` + 「解决状态只对提问有意义（Kind=question），当前 Kind=review」），**不是**静默忽略 —— 静默忽略会让「我筛了已解决却还是看到全部」变成一个查不出的体验问题。
- **`IsTimeout` 由后端发**：判据与 `QuestionTimeoutJob` 的 `TIMEOUT_MINUTES` **同源**（同一个常量一处定义）。前端**只渲染**，页面**不持有**那个阈值 —— 曾经前端自己算过一版（`isOverdue`），补笔已删（见 `ModerationPage.vue` 的注释）。
- **不在 `AUTH_WHITELIST`**：白名单里 `/comment` 域只有 `/comment/list` 一条（公开面）。本端点走 `AuthInterceptor`（注册在 `/**`）⇒ 闸开着时匿名 **401**，与 `imageReview` / `report.*` / `sensitive.*` 同族。⚠️ **a3 profile（`app.auth.enabled=false`）下它匿名可达 —— 那是验证环境的既定形态（该 profile 下每一个管理端端点都如此），不是本端点引入的口子**，也不是放宽契约（读 `CommentController#adminList` 的 javadoc 与 G.7 那段）。
- **不收 `Account`、也不读会话**：与 `imageReview` 同处置（管理端端点靠「路由受保护 + 会话」识别处理人，不由入参声明）；与 `list` 相反，本列表没有「我」这个视角（`Mine` 恒 `false`）⇒ 不转发 `AuthSession`。
- **排序固定 `created_at DESC`**、**不收 `Sort`**：全站分页下按热度排序会让「翻页时同一行出现两次 / 一次都不出现」（热点行在查询间隙换位），而本列表的用途是**待办巡检**，不是「看热门」。
- **回体是扁平行**（无 `Replies`），且**顶层与回复都在里面**（管理要能看到回复 —— 一条回复同样可以违规；公开面那条刻意只要顶层）。匿名行照旧打码：`AuthorId` 为 `null`、`Author` 是 `1*****`（实测 2026-10-13：`adminList` 与公开面 `list` 对同一行 Id=114 给出**逐字相同**的这两个值）。

### G.2 新域 `/report/*` —— 举报 3 条 {#api-g-2}

`ReportController.java`（`@RequestMapping("/report")` @ `:49`）。

| 路径 | 入参 | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/add` | `Account` · `TargetType`(comment/goods/user) · `TargetId` · `Reason`(辱骂/广告/违法/其他) · `Description`（理由 = 其他 时必填，≤ 100 字） | 提交一条举报。**同内容同人不能二次举报**（靠 `uk_report_target_reporter`）；**驳回后再举报 = `UPDATE` 同一行复活**，不新增行 | `requireSelfOrAdminByUsername` | `:73` |
| `/list` | `Status` · `Limit` · `Offset` | 举报列表（管理端「举报中心」） | 管理端面 —— **不挂 `requireAdmin`** | `:97` |
| `/handle` | `Id` · `Approve`（`"1"`/`"true"` = 通过；其余含省略 = 驳回）· `Note` | 通过 ⇒ **删内容 + 警告 +（满 3 次）封号 + 通知双方**；驳回 ⇒ 留待再举报 | 管理端面 —— **不挂 `requireAdmin`** | `:122` |

⚠️ **`/handle` 的处理人取「会话身份」的 `userId`（`AuthSession.identify`，识别口径），不是入参** —— 用入参等于让人随便署别人的名；且必须用**识别**口径而非 `currentUserId`（授权口径）：后者会把 cookie 登录的管理员（`role=3`、`viaToken=false`）折成 `null` ⇒ 管理端正常用 cookie 登录时反而「查无处理人」（`ReportController#handle` 的 javadoc）。

### G.3 新域 `/sensitive/*` —— 敏感词库 3 条 {#api-g-3}

`SensitiveWordController.java`（`@RequestMapping("/sensitive")` @ `:44`）。

| 路径 | 入参 | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/list` | `Limit` · `Offset` | 词表（管理端「敏感词库」tab） | 管理端面 —— **不挂 `requireAdmin`** | `:59` |
| `/add` | `Word`（≤ 64 字符） | 加词 **并立刻扫一遍历史评论**；回体 `masked` = **真被改写**的行数（原本已打码的不算）；重复 ⇒ `success:false` +「该敏感词已存在」 | 同上 | `:76` |
| `/delete` | `Id` | 删一个词。⚠️ **历史评论里的 `***` 不回填**（原文没有第二份副本） | 同上 | `:91` |

⚠️ **本组三条都不收 `Account`** —— 词表 DDL 只有四列（无作者列），加词 / 删词不需要声明身份；造一个只为满足仪式感的入参，只会给下一个人「原来这里也该有身份」的错误信号（`SensitiveWordController` 类注释逐字）。
⚠️ **加词「一条都没命中」是正常结果，不是失败**（`masked=0`）—— 前端要如实报「已屏蔽 N 条历史评论」，别用 `success` 一个字段概括。

### G.4 通知事件 code 的契约改动（**+2 / −1**） {#api-g-4}

| 项 | 内容 |
|---|---|
| **新增 2 条** | `comment.accepted`（提问被采纳 → 触达**被采纳那条回答的作者**）· `question.timeout`（提问**超 30 分钟仍未解决** → 触达**管理端共享收件箱**，`ToType='admin'`、`ToId='*'`） |
| **摘除 1 条** | `comment.on.user`（「留言板被评论」）—— 块 1 建通知域时**预留**，块 6 基准文档 §二 A1 定稿的 6 个 code **不含**它，2026-10-13 用户裁定**摘除**（全仓零调用点，只删常量 + 前端两处标签，无行为回归） |
| 计数沿革 | 块 5 收口 **31** → 块 6 commit 2 补 2 条到 **33** → 同笔「契约纠偏」摘 1 条 ⇒ **32** |
| 同笔改的落点 | `NotificationEvents.java` 的常量 + 前端 `src/config/notify-events.js` 的 `EVENT_LABELS` / `EVENT_OPTIONS`（**三处必须同笔**） |
| ⚠️ 关键口径 | `NotificationService.send` **不校验 code**（未知 code 照样落库）—— 这是「加通知类型不改表、不改后端」成立的前提；代价是前端映射**必须兜底**（未知 code 原样显示，不许静默成空白） |

⚠️ **`question.timeout` 必须带 `TargetType`/`TargetId` 指回那条提问** —— `QuestionTimeoutJob` 的幂等判据正是 `existsDelivery(收件人, 本 code, "comment", 提问Id)`（那是个**待办**，不是结果；管理端据它去问答 tab 人工介入）。

### G.5 前端封装增补 {#api-g-5}

| 文件 | 增补 | 备注 |
|---|---|---|
| `src/api/endpoints.js` | `comment` **8** + `report` **3** + `sensitive` **3** = **14 条**（全**非** view 抽取，唯一调用方是新封装） | 头注「现为 N 条」**119 → 133**；⚠️ 组内 / 文件尾追加，**不插行** |
| `src/api/comment.js` | **新建**，**8 个导出**（`list` / `add` / `remove` / `accept` / `like` / `myOrders` / `imageReview` / **`adminList`**） | 八个端点一个模块；`list` 的唯一调用方是 `ProductPage.vue`，`adminList` 的唯一调用方是管理端「评论管理 / 问答管理」两栏 |
| `src/api/report.js` | **新建**，**3 个导出**（`add` / `list` / `handle`） | `add` 的调用方是 `ProductPage.vue`（评论 / 回复行的「举报」、商品行的「举报」、提问行的「提删」）；`list` / `handle` 给管理端 |
| `src/api/sensitive.js` | **新建**，**3 个导出**（`list` / `add` / `remove`） | 唯一调用方是管理端「敏感词库」tab |
| `src/views/ProductPage.vue` | **直调** `comment.*`（评价 / 问答两个子 tab：列表、发表、删、赞踩、选商品）+ `report.add`（举报 / 提删） | ⚠️ 本文件是块 6 改动量最大的一处（约 **+916 / −49**）⇒ 全仓指它的 `文件:行号` 引用集体后移，重指台账见 `slot-registry.md` §二十三 |
| `src/views/admin/ModerationPage.vue` | **新建**，管理端「消息与内容治理」页 —— **六个 tab**：评论管理 / 问答管理 / 待审图片 / 举报中心 / 敏感词库 / 消息管理。⚠️ 2026-10-13 补笔：评论管理 / 问答管理两栏各**多一段「全站列表」**（走第 14 条 `adminList`），原来的「按对象下钻」表单**保留在下面**（那条走公开面 `/comment/list`，仍强制 `TargetId`） | 比例走环图 / 柱图（`DonutChart` / `BarChart`），色值一律走 `chartTokens`；⚠️ 页面对「超时」**不再自己算**（`IsTimeout` 由后端发，前端只渲染） |
| `src/router/index.js` | `business/moderation` 记录由 `AdminStub` 占位 → **真页**（**同行替换**，照 `:455` 那一行的注释记账） | **不动**路由结构 |

### G.6 白名单的契约改动：`AUTH_WHITELIST` **13 → 14** {#api-g-6}

| 项 | 内容 |
|---|---|
| 新增条目 | `/comment/list`（**只放读**）—— 商品详情页「累计评价」区在未登录时照常加载，而 `/product` 挂 `meta.public` ⇒ 少这一条，未登录用户看到的是一格**空评价区**（外加 401 噪音） |
| 条数口径 | 业务 **9 → 10** 条（登录链 3 + 登录前后台页面 **6 → 7**），基础设施 4 条不变 ⇒ 总 **13 → 14** |
| ⚠️ **四处同改** | `WebAuthConfig.java:62`（javadoc「业务 9 条」→「10 条」）· 同文件 `:68`（「登录前后台页面 6 条」→「7 条」+ 补第 7 条那条 ⚠️）· 同文件清单本体（`:120` 加 `"/comment/list"`）· 同文件 `:175`（「13 条逐条直调」→ **14**）；**另加** `AuthGateSmokeTest.java`（javadoc 两处 + `hasSize(13)` → `14` + 断言文案「业务 9 + 基础 4」→「业务 10 + 基础 4」）与 `MerchantStatsController.java:19`（13 → 14，后半句「没有任何 `/merchant/**`」仍成立） |
| ⚠️ 别顺手搬 | 同域另 **4 个写端点**（`add` / `delete` / `like` / `myOrders`）**仍在身份面**；`/shop/follow` / `unfollow` / `followState` 三条同理（`WebAuthConfig` 那段 javadoc 逐字警告）。⚠️ 2026-10-13 补一笔：第 14 条 `adminList`（**管理端**全站列表）**同样不进白名单** —— 白名单的判据是「公开面」，管理端端点没有一条是公开面（`WebAuthConfig` 那两处文案已同步点名） |

### G.7 第 14 个端点：**图片仍不另开**，但全站列表**已批、已落** {#api-g-7}

⚠️ **图片依旧走 `/comment/add` 的同一个请求、多 part（`getFiles("Files")`），不另开端点** —— 这一条**不变**：评论文本与它的图本就是一次提交。用 `MultipartHttpServletRequest#getFiles` 手工取 part、而非 `@RequestParam List<MultipartFile>`（后者在**非 multipart** 请求〔= 一条纯文字的评论〕上的绑定行为随 Spring 版本而变；前者两种情况下都是「没有文件」）。

⚠️ **本节原先写的是「本块的硬线：不加第 14 个端点」—— 那条硬线已在 2026-10-13 被用户**单独批准**地开了一个口子**：内容域第 14 条 = `POST /comment/adminList`（管理端全站列表，见 **G.1.1**）。开这个口子的理由是**功能缺口**而不是口味：管理端「评论管理 / 问答管理」两栏此前只有「按对象下钻」一种手法（公开面 `list` 强制 `TargetId`），**看不到全站待办** —— 而另四栏（待审图片 / 举报中心 / 敏感词库 / 消息管理）都有全站列表，六栏里两栏缺，属**功能不一致**。甲案（新端点）胜出的另外两条候选：
- **改公开面语义**（让 `/comment/list` 的 `TargetId` 可省）：**否** —— 那会动公开面契约，而 `/comment/list` 是**白名单里的公开面**，放开等于把「全站评论」无条件暴露给未登录访客；
- **复用 `list` + 管理端开关**：**否** —— 同一个端点在两种身份下两种语义，是「参数长得一样、行为不一样」的形状，比多一条端点难维护得多。

⚠️ 因此本块的契约面计数**是 14 而不是 13**（G.0 / G.1 / G.5 三处已同步）；`/comment/*` 内部是 **8 条**（G.1）。

⚠️ **本文件（`api-contract.md`）的正文 §2 / §3 / §5 矩阵**本笔**未重测、未插入** —— 14 条新端点全部落在附录 G；正文坐标重填归「文档漂移清理笔」（同附录 E.7 的口径）。
⚠️ **已知的既有陈旧（不属本笔，登记不修）**：§2 的 C.1 表里 `/order/orderAdd` → `ProductPage.vue:393`、`/message/send` → `ProductPage.vue:459` 两条**在块 6 之前就与实际调用点对不上**（**不修**），故本笔的自动重指**刻意跳过**它们（行内已留「旧行号 · 不修」标记，属重指脚本的 GUARD 触发词）。
**实测真调用点（第一手，2026-10-13）**：`/order/orderAdd` = `ProductPage.vue:897`（`await orderAdd({`）、`/message/send` = `ProductPage.vue:1002`（`http.post(endpoints.message.send, …)`）—— 两值均已按 ① 甲案（`ProductPage.vue` −16 行）重指—— 正文坐标重填归「文档漂移清理笔」，台账见 `slot-registry.md` §二十三 的「已知误指族」。

## 附录 H：C3 块 7〈可观测域〉契约增补（2026-10-13/14） {#api-app-h}

> **块 7 = 一个新域（可观测域）+ 8 条端点**：`/admin/observe/attack/*` **5 条**（commit 4 前三条 + commit 8b 第四条 `cleanup` + commit 10c 第五条 `stop`）+ **三条 SSE**（`/admin/observe/{metrics,orders,events}/stream`，commit 3）。**全部 `POST`**、全部**不加角色闸**、全部**不在 `AUTH_WHITELIST`**。
> ⚠️ **2026-10-15「块 7 收尾」commit 1 再追加 2 条 ⇒ 本域共 10 条**：`/attack/status`（第六条出口，见 **H.1**）+ `/admin/observe/replay`（回放，见 **H.8**）。两条与上面 8 条**同口径**（`POST` / 不加角色闸 / 不在白名单）。⚠️ 上面那句「+ 8 条」是**块 7 主体**的口径，**不要**改成 10 —— 本附录同时挂着主体与收尾两笔的账，逐笔读。
> 逐条坐标 = 该方法的 `@PostMapping` 所在行（**本文件口径**，与 `db-schema.md#db-top` 相反）。
> 实现落在**一个后端包** `observe/`（`controller` / `service` / `dao` / `metrics` / `mq` / `job` 六层）。
> ⚠️ **与附录 C–G 的关键差别**：本域是**观测面** —— 它不改任何业务端点的入参 / 返回，对业务域的改动**只有两处**（出站头 `X-Drill-Batch`、台账表 `observed_event`），见 **H.6** / **H.7**。

### H.0 三个「端点总数」要分开读（口径注，别互抄） {#api-h-0}

| 口径 | 定义 | 块 7 前 | 块 7 后（HEAD） | 出处 |
|---|---|---|---|---|
| **后端 method-level** | 全仓 `*Controller.java` 中 `^\s*@(Post\|Get\|Put\|Delete)Mapping` 行数（**不含**类级 `@RequestMapping`） | **134** | **144**（+10） | 2026-10-15 实测：`grep -rhE "^\s*@(Post\|Get\|Put\|Delete)Mapping" --include=*Controller.java … \| wc -l` ⇒ **144**（原记 141 = +7，是 **commit 10c 的 `/attack/stop` 落地前**的读数；该端点补上后 +8 → 142；**块 7 收尾 commit 1** 再 +2（`/attack/status` · `/admin/observe/replay`）⇒ 144） |
| **本文件 §2 的「36 个」** | 只数 5 个核心 Controller（`/user` / `/message` / `/goods` / `/order` / `/health`） | 36 | **36（一字未变）** | §2 标题 |
| **前端 `endpoints.js`** | `key: '/path'` 条目数（含 2 处**同行两条**） | 133 | **143** | 该文件头注记账链（… → 133 → 141 → **143**）：块 7 commit 3 的 3 条 SSE + commit 4 的 3 条 + commit 8b 的 1 条 + commit 10c 的 1 条（`attackStop`）= 8 条，**块 7 收尾**再 +2（`observe.attackStatus` · `observe.replay`），**全落 `observe` 组**；实测 `grep -cE "^[[:space:]]*[A-Za-z0-9_]+:[[:space:]]*'/"` = **141 行**、+2 处同行两条 = 143 |

⚠️ **块 7 的 8 条全部落在 §2 与 §3 的 5 个 Controller 之外** ⇒ §2 的「共 36 个」**不需要改**；本附录就是那 8 条的契约面。
⚠️ **`observe` 域不进 §2 的清单**（同附录 C/D/E/F/G 的手法：零漂移，正文表不插入）—— 域标题只在**本附录**里出现。

### H.1 新域 `/admin/observe/attack/*` —— 攻击模拟器**八**出口 {#api-h-1}

`AttackController.java`（`@RequestMapping("/admin/observe/attack")` @ `:84`）—— 返回一律 `Result<Map<String,Object>>`（`/run`、`/records` 走 `ResponseEntity` 显式构造 4xx）。

> ⚠️ **2026-10-15 块 7 收尾 commit 1 重指**：标题原为「**五**出口」；本表坐标**逐条更新**。
> 三处漂移**原因不同**，故幅度不同（旧值一律注为「**原 N 行**」）：
> ① `@RequestMapping` 与 `/scenarios`、`/run`、`/stop` = **记录值比现值小 1 行**（现值 = 原值 + 1）——
>     多出的那 1 行是**新增的 import**（`observe.service.AttackEngine`，给 `AttackEngine.Progress` 用）；
> ② `/records`、`/cleanup` = 在 ① 的基础上**再 +44 行** —— 那是**新增的 `/status`** 顶出来的。
>
> ⚠️ **2026-10-16 阶段 3a.1（乙案）第二次重指**：标题「**六**出口」⇒ **「八」出口**
> （新增 `POST /config` 与 `GET /config` 两条，见下表的 `/config` 两行）；本表坐标**逐条更新**。
> 三处漂移**幅度不同**（旧值一律注为「**原 N 行**」）：
> ① `@RequestMapping` = **+17**（原 67）—— 顶出来的是类注释新增的「八个出口」一节；
> ② `/scenarios` = **+28**（原 92）、`/run` = **+26**（原 124）—— 在 ① 之上再加 import 与
>     `@param protections` / `@param injections` 两处说明改写后的净增；
> ③ `/stop` ~ `/cleanup` **四条** = **+124**（原 170 / 210 / 233 / 281）—— 在 ② 之上再顶出
>     **两条新端点**（`/config` 的 `POST` + `GET`，含各自的注释与 `view` / `given` 两个私有工具）。

| 路径 | 入参（全部 `required=false`，**一律 `String`**） | 返回要点 | 身份 | 坐标 |
|---|---|---|---|---|
| `/scenarios` | **无** | 8 个预置场景（`key` / 中文名 / 同时演示的防护 / 需要的人工动作 / 默认权重） | 无 | `:120`（原 92） |
| `/run` | **共 5 个**（全部可选）：`name`（缺省「自定义」，> 64 字符按列宽截断）· `concurrency`（缺省 **100**、上限 **4000** —— 含义已收紧成「**在途上限**」，不再等于线程数）· `durationSeconds`（缺省 **60**、上限 **600**）· `targetQps`（缺省 **0** = **不限速**；「档位 120 / 400 / 拉满」的载体，见下）· `scenarios`（**JSON 数组串**）。⚠️ **3a.1（乙案）起 `protections` / `injections` 不再是入参** —— 形参还留着，但**只用来拒收**：给了（非空白）就 **400 + 指路 `/attack/config`**，不静默忽略 | 跑完一轮 ⇒ `Id` / `Name` / `TotalRequests` / `BlockedRequests` / `FailedRequests` / `DurationMs` / `Result` | 会话（**只记谁点的**，不转发凭据） | `:150`（原 124） |
| `/config`（`POST`） | **2 个**（都可选，都是 **JSON 数组串**）：`protections`（缺省 = **不动这一项**）· `injections`（同上）。⚠️ 语义：`null`/空白 = **不改这一项**、`[]` = **关光这一项**（两者相反，别混） | 改完回**整份快照**：`Protections` / `Injections` / `IsDefault` / `Version` / `Catalog` | 无（**不写审计**：改的是进程内开关，不动任何数据） | `:213` |
| `/config`（`GET`） | **无** | 同上那份快照（**只读，且不写 `Version`**） | 无 | `:248` |
| `/stop` | **无** | `success` / `stopped`（**如实**：没在跑就是 `false` 并说清「没有在跑的演练」）/ `summary` | 无（**不写审计**，理由见下） | `:294`（原 170） |
| `/status` | **无** | **块 7 收尾 commit 1 第六条**：进程内快照 ⇒ `Running` / `BatchId` / `ElapsedMs` / `Sent` / `Completed` / `Blocked` / `Failed` / `Shed` / `Abandoned`。⚠️ `Running=false` 时其余**全为 0 而不是 `null`**（1 秒轮询在收尾那一拍必然撞上它，缺键会让前端把「跑完了」画成「读数丢了」）。⚠️ 是**过程近似值**，**最终账**看 `/records` 那条 `drill_record`。不写审计（与 `/stop` 同档：只读进程内状态） | 无（**不写审计**） | `:334`（原 210） |
| `/records` | `limit`（缺省 **20**、上限 **200**）· `offset`（负数归 0） | 时间倒序记录列表；行内 `Result` 长文本已**拆成字段**（解析失败 ⇒ 落 `ResultRaw` + `ResultParseError`，不静默）。⚠️ **每行新增 `BatchId`**（块 7 收尾 commit 1，用户 2026-10-15 裁「甲」）—— 界面上的〔回放〕按钮拿它当入参；键序 `[…, DurationMs, CreatedAt, BatchId, Result]` | 无 | `:357`（原 233） |
| `/cleanup` | **无**（清什么由**后端**按标记判，不让前端挑范围） | `success` / `deleted`（**只含真删了行的表**）/ `total`（各行数之和）/ `summary`（一句话，同一句进审计行） | 识别口径（`AuthSession.identify`），认不出记 NULL | `:405`（原 281） |

⚠️ **`/config` 的 `GET` 是本域**唯一**一条非 `POST`**（用户 2026-10-16 裁定：读配置用 `GET` 更符合直觉）。它与其余七条的两条已知差异**如实登记**：① 它是**可缓存**的动词 ⇒ 响应显式带 `Cache-Control: no-store`（不靠「反正没有中间缓存」这种没验过的前提）；② 本域的动词从此有两种。其余七条一字未动。

⚠️ **`/stop`（块 7 · commit 10c 追加）是五出口里的第五个，也是最轻的一个** —— 它**只翻一个进程内的标志位**（引擎侧：停止派发新请求，在途请求走 **3 秒宽限**排空，宽限用尽记 `Abandoned`），**不删数据、不改库、不改任何持久状态**。三条口径：
- **不写审计**（与 `/cleanup` 相反）：审计记的是「谁动了数据」，而本端点没动任何数据 ⇒ 代价如实登记 —— **「谁叫停的」只有引擎侧一行 INFO**。
- **`stopped=false` 不编造成 success**：没在跑就明说「当时没有正在跑的演练（跑完了 / 从没跑过），未做任何事」。
- **默认 profile 下匿名可达**（同 H.5：本域八条都不加角色闸）⇒ 一个匿名请求可以停掉管理员正在跑的演练。这是「a3 / dev 关闸」形态下的既定面，不是本端点引入的口子；**要不要加闸归 TD-64**（本域破坏性 / 长连端点无角色闸）。
- ⚠️ **叫停不是掐断**：`/stop` 返回之后，在途请求仍要跑满宽限才排空（宽限 = `AttackEngine.GRACE_SECONDS` = **3**，语出该常量；**归零耗时未单独实测** ⇒ 这里不给秒数）。判据只能是「`stopped=true` 之后 `active` 会回落」，**不是**「返回即回落」。

**8 个场景**（`key` → 中文名 → 默认权重）：

| key | 中文名 | 演示的防护 | 默认权重 |
|---|---|---|---|
| `seckill` | 秒杀抢购 | 库存扣减 + CAS + 唯一索引 + 锁 | 15 |
| `orderBurst` | 订单并发 | 出生闸 + 价格闸 + 冻结闸 + 锁 + CAS | 15 |
| `repeatSubmit` | 重复提交 | 幂等 + 唯一索引 + 锁 | 10 |
| `unpaidTimeout` | 超时不付款 | 超时取消 job + 库存释放 | 10 |
| `cacheCold` | 缓存冷启 | 单飞（sync=true）+ 负缓存 | 10 |
| `redisDown` | Redis 断开 | 冷却窗口 + fail-open | 10 |
| `bannedUser` | 封禁用户攻击 | 封禁闸 + 身份校验 | 15 |
| `sensitiveWord` | 敏感词攻击 | 两阈值（< 40% 打码 / ≥ 40% 拒收）+ 图片先审 | 15 |

（合计 **100**；缺省 = 全 8 个按上表权重。）

⚠️ **commit 10c 新增的三个「丁」入参 —— ⚠️ 3a.1（乙案）起只剩 `targetQps` 还挂在 `/run` 上，另两个搬去了 `/config`**：

| 入参 | 形状 | 缺省 | 语义 | 实现状态 |
|---|---|---|---|---|
| `targetQps` | 数值串 | `0` = **不限速** | 目标 QPS（`AttackEngine.Pacer` 按它节流，`AttackEngine:401`） | ✅ 已实现（**仍在 `/run` 上** —— 它描述的是「这一轮怎么发」） |
| `protections` | ~~JSON 数组串~~ ⇒ **`/config` 的入参** | **全开**（`rateLimit` / `circuitBreak` / `degrade` / `retry` / `poolIsolation`） | 面板上「防护开/关」的载体 | ✅ **已接闸**（**4 键 + 1 键记录位**，阶段 2b 起 —— 见 `#api-i-3`；只剩 `degrade` 在面板上**不可开关**）。⚠️ **3a.1 起它是共享配置**：`/run` 见到它就 **400 + 指路**（`AttackController:137`），读写走 `POST` / `GET /config` |
| `injections` | ~~JSON 数组串~~ ⇒ **`/config` 的入参** | **全关**（`redisDown` / `mqDown` / `dbSlow`） | 故障注入开关 | ⚠️ 只有 `redisDown` **真接通**（`AttackController:139`）。⚠️ 同上：3a.1 起走 `/config` |

⇒ `protections` **真的会改读数**：开着的生效、关掉的那几枚不生效。⚠️ 但**不是五枚都有闸** —— 只有 `degrade`（记录位）**拨了也没有对应行为**，故面板把它画成不可开关（见 `#api-i-4`）；「五枚全有闸」这句话**本段仍不成立**。`injections` 里仍只有 `redisDown` 会真起作用。这一条是**如实登记**。
⚠️ **3a.1（乙案）改掉的是「这两组开关住在哪里」，不是「它们生效吗」**：改动前甲案把它们挂在 `/run` 的入参上（开关只存在于**一次请求**里 —— 另一个页面看不到也改不了，想改只能重发 `/run`，而那会**再跑一轮**）；乙案提到 `/config` 这个**进程内共享配置**上，两个页面读同一份、写同一份。**跑完不复位**（用户 2026-10-16 裁定，见 `#api-i-3` 末两节）。
⚠️ **`targetQps` 与 `concurrency` 测的不是同一件事**：前者是**发射速率**（每秒发多少个），后者是**同时在途上限**（`permits = min(concurrency, in-flight-cap)`）。「档位 120 / 400 / 拉满」调的是前者。

⚠️ **八条端点的入参一律 `@RequestParam`（+ 一个 JSON 串参数），不用 `@RequestBody`** —— 三条理由任一条都成立：① 全仓管理端写端点**全部**收 `@RequestParam`、前端一律 `URLSearchParams`（`http.js#buildInit` 把 POST 钉成 urlencoded）；② 否则**前端发不出去**（`http.post` 没有自定义头的口子 ⇒ JSON body 到不了 `@RequestBody`）；③ 「勾选 + 权重」这种嵌套结构沿用 `orderAdd` 的既有先例（一个 JSON **数组串**参数，同 `Items`）。
⚠️ **数值参数也收 `String`**（不是 `Integer`）：交给 `AttackEngine.parse` 的 `clampInt` 校验并给**统一形状**的 400（`Result.fail`）。声明成 `Integer` 的话，「abc」会在 Spring 参数绑定阶段抛 `MethodArgumentTypeMismatchException`，而 `GlobalExceptionHandler` 的类级白名单只覆盖 Message / Goods ⇒ 那会变成默认错误页。
⚠️ **参数非法走 4xx，不是 200 + `success:false`** —— 全仓口径是「**业务**失败 200，入参**形状**失败 400」；权重写成负数 / 场景 key 不认识 / 并发数越界都是后者。用 `ResponseEntity` 显式构造、**不抛异常**（同上那条白名单）。
⚠️ **`/run` 会阻塞到演练跑完（几十秒）** —— 引擎同步跑完才回（这样「跑完就能看到数字」，不需要轮询 + 状态机）。代价写在明处：**调用方必须给这条请求一个远大于默认值的超时**（`http.post` 的默认 10s 会把 60 秒的演练当场掐断）—— 前端在 `api/observe.js#runAttack` 里带了 `timeout`。这也是**不**选「异步起任务 + 轮询」的理由：那要多一个「跑到哪一步了」的状态面，而本仓没有任务表。
⚠️ **打哪个地址：从当前请求推**（`scheme` / `serverName` / `serverPort`），**不引配置项** —— 块 7 的边界是「不改部署配置」，且现推出来的地址**永远指回打进来的那台**。只支持 http（本仓没有 TLS 终结层）。
⚠️ **`scenarios` 解析失败 ⇒ 直接 400（不回落）** —— 与 `OrderService` 的 `Items`（解析失败记 WARN + 回落）**取舍相反**：本端点的**全部语义**就是「按勾选的场景跑」，回落到「全 8 个」等于替调用者做了**另一个决定**，而页面上会显示一串他没勾的场景 —— 那不是降级，是失真。
⚠️ **`/run` 只把「谁来点的」记下来，不把「他的凭据」传下去** —— 引擎**自己给演练账号铸票**。转发发起人的 `Authorization` / `Cookie` 会让身份守卫走管理员豁免（场景 `bannedUser` 的 403 永远出不来）、并让「故意不带票」的那一路被管理员会话认成已登录（理由全文在 `AttackEngine` 类注释第二节）。
⚠️ **`/cleanup` 是本仓第一个破坏性管理端点**（真 DELETE、不可撤销）。三条护栏：① 真删之前**不**由任何定时任务调用（保留期 job 只碰 `observed_event`）；② 每次调用**写一条审计**（`observe.attack.cleanup`，含按表计数）；③ 响应把**删了哪几张表各多少行**原样回给前端，不是只回一个 `true`。**为什么敢做成无参**：判据是**标记**不是**时间** —— `SIM-%` 前缀 OR `Source='attack'`，这两样本仓**只有攻击引擎会写**（实测 2026-10-14：全库只有 `order` / `order_item` / `order_state_transition` / `comment` 四张表有，共 10 行）⇒ 「清掉攻击数据」与「清掉带标记的行」是**同一件事**。身份取**识别口径**（cookie 登录的管理员不该被记成匿名）；闸关着的 dev / a3 上返回 `null` ⇒ 审计行 `ActorId` 记 NULL，**不编造**。

### H.2 三条 SSE {#api-h-2}

| 路径 | 类 · 坐标 | 帧内容（`frame()` 的顶层键） | 会读库吗 |
|---|---|---|---|
| `POST /admin/observe/metrics/stream` | `MetricsStreamController.java` @ `:34`（方法 `:57`） | `seq` · `ts` · `intervalMs` · `health` · `requests` · `cache` · `gauges` · `locks` · `protections` · `stock` | **否**（全读 micrometer 注册表） |
| `POST /admin/observe/orders/stream` | `OrderStreamController.java` @ `:23`（方法 `:50`） | `seq` · `ts` · `counts` · `orders` · `moves` · `moveCount` · **`batchCounts`**（块 7 · commit 10 新增） | **是**（每拍 2~3 次只读查询：五态计数聚合 + 最近订单 + **本批次**五态计数） |
| `POST /admin/observe/events/stream` | `EventStreamController.java` @ `:23`（方法 `:40`） | `seq` · `ts` · `cursor` · `count` · `rate` · `counts`（按 9 类）· `severity`（按严重度）· `events`（本拍增量；每条 **14 键** —— 块 7 · 阶段 3a.3 起多了 `actor` / `durationUs` / `fields`）· `traces`（本拍走完的请求）· `mq`（MQ 桥接状态）· `dropped` · `droppedTotal` · `droppedIngest`（三者口径不同，见 `EventStreamService#frame`） | **否**（读内存合并环 + `TraceLog`） |

⚠️ **读端点为什么是 `POST`**：SSE 的惯例是 `GET`（`EventSource` 只支持 GET），本仓选 `POST`，理由与 `AdminStatsController` 那两条读端点**同一条** —— 管理端的读端点**全部**是 POST，单为这三条开一个 GET 面会让「读端点的动词」在本仓出现两种。前端**不**用 `EventSource`（它不能带 `Authorization` 头），改用 `fetch` + `ReadableStream` 自己解析 ⇒ POST 不构成任何实现障碍。
⚠️ **`Content-Type` 必须在方法体里手动设** —— `@PostMapping(produces=…)` **不会**替 `StreamingResponseBody` 写上响应头（`produces` 只在基于消息转换器的返回路径上被消费，而 `StreamingResponseBody` 走异步返回路径；实测取证：不加那一行时响应头只有 `Cache-Control`，见 `ObserveStreamSmokeTest` 对媒体类型的断言）。两条都留着：`produces` 管**路由与 Accept 协商**，那一行管**真实响应头**。
⚠️ **不加角色闸（TD-18 口径）**：管理员 cookie 铸不出 token ⇒ 加「须 `role=3`」会让**默认 profile 上管理员自己也 401**。真正把端点挡在门外的是 `app.auth.enabled` + `AuthInterceptor`（**本域八条都不在 `AUTH_WHITELIST` 里**）⇒ 未登录打过来就是 **401（流还没开就 401**，不是「开了流再断」）。
⚠️ **`events` 流读的是八环合并后的公共流**（`EventBroadcaster`）—— 它的「一拍只算一次」语义对本流尤其重要：日志页开着的时候攻击模拟器页并不读事件（它只发流量），但**同一页面的两次刷新**会短暂重叠 ⇒ 没有那层保护就会有一条流看着像死机。**⚠️ 也因此：`poll()` 的唯一调用点就是这条流**（见 H.7 的「已知边界」）。
⚠️ **`orders` 流是观测面里唯一会读库的一条**；它**不改任何库**。⚠️ 它的 `batchCounts`（块 7 · commit 10）**没批次号时是 `null`，不是 0** —— 页面上 `—`（没有批次）与 `0`（本批次一单都没有）是两回事；且它与 `counts`（**全表**）**并列而不互相覆盖**：一个说全库、一个说「这次演练落下的那批单各在哪个态」。
⚠️ **`metrics` 流的 P99 只收「能当一次请求处理时长看」的序列**（`isLatencyTimer`），计数那一路是**全量** —— SSE 也是真请求，只是它的耗时不是延迟。块 7 · commit 8b2 修的就是这一条：长连接（几百秒）混进延迟序列会打出「P99 = 26.8 秒」的**误红**。
⚠️ **同一把尺子还要剔掉「演练控制」的启动端点**（块 7 · commit 10c-2，`MetricsSnapshotService.DRILL_RUN_URI`）：`/admin/observe/attack/run` 是一条**同步**请求，挂满整场演练才返回（上限 600 秒）⇒ 实测它把自己的存活时长写进了 `http.server.requests` 的 **MAX = 136417 ms** 与末帧 **p99 = 120259 ms**（2026-10-15 阶段 2 第二轮梯子）—— **那两条数全部来自这一条请求**，真接口一点都不慢。判据是**全等**而非 `/admin/observe/attack/*` 前缀：`/attack/stop` 是毫秒级真请求，它的耗时是**真样本**，一刀切会把它悄悄丢掉。⚠️ 另：`/actuator/metrics/<timer>` **结构上不吐分位数**（只报 COUNT / TOTAL_TIME / MAX）⇒ 上面这两个数**只能**从本条流的 `requests` 帧读到，配套的「去 actuator 看 0.99」是走不通的路。
⚠️ **断连**：客户端关页面 / 刷新 ⇒ 写已关闭的输出流抛 `IOException` ⇒ `SseStreamWriter` 的 catch 收工（不重试、不刷 WARN）。服务端**没有**独立的「连接表」要清理：状态全在各单例 Service 里（无连接态）。

### H.3 帧的节流与可配项 {#api-h-3}

| 配置项 | 缺省 | 作用 |
|---|---|---|
| `mall.observe.sse.interval-ms` | `1000` | **每条连接**的发帧间隔（`SseStreamWriter` 循环里的 `Thread.sleep`；块 7 · commit 10b 的「每连接一份帧源」之后，不再有「同一拍内多次请求复用同一帧」那层） |
| `mall.observe.sse.max-ticks` | `0`（= 不停） | 只用于测试的「跑 N 拍即收」口子 |
| `mall.drill.engine.in-flight-cap` | `512` | **在途请求上限的硬顶**（`AttackEngine:653`【3a.1 重指；⚠️ 原文写的旧行号（608）在改动前就**已经指错了** —— 那一行是「`TraceIdFilter` 收进 MDC」那句，真 `@Value` 当时在 627；差值来自 D 段 / 阶段 2b 的净插入未重指】）：`permits = min(concurrency, in-flight-cap)` ⇒ 只有 `concurrency > 512` 时它才成为真约束（块 7 · commit 10c-2 把 `concurrency` 上限抬到 4000，正是为了让它可被触及）。⚠️ **本键在任何 `.properties` 里都不存在** —— 它靠运行时 CLI 覆盖（`--mall.drill.engine.in-flight-cap=4096`），不进仓、不是 A 档 |
| `mall.drill.engine.shed-wait-ms` | `50` | 拿不到 permit 时的**等待上限**；等不到就记 `Shed`（**背压**，不计入失败、也不计入 Blocked） |

⚠️ **合并环容量 2048 不是可配项**：它是 `EventBroadcaster.EVENT_CAPACITY` 常量（块 7 · commit 10 · 裁决 2 由 512 抬到 2048，理由与「丢的是最早的」的边界见该类注释）。改它要改代码，不是改配置。

⚠️ **`FramePacer` 已在块 7 · commit 10b 删除**（它治的是「两条连接共用一份帧源」的症状，病根随「每连接一份帧源」消失）⇒ 现在**每条连接各自按 `interval-ms` 发帧**，「间隔内复用上一帧」这层补偿不再存在（`FramePacer.java` / `FramePacerTest.java` 已不存在）。

### H.4 前端封装增补 {#api-h-4}

| 文件 | 增补 | 备注 |
|---|---|---|
| `src/api/endpoints.js` | `observe` 组 **8 条**（3 条 SSE + 5 条攻击出口，后者含 `cleanup`），**全非 view 抽取** | 头注「现为 N 条」**133 → 141**；**单列一组、追加在对象尾**，不插行。⚠️ `attackStop`（commit 10c）在 `:426`，是**追加在组尾**的第五条攻击出口 |
| `src/api/observe.js` | **新建**：3 个流开启器（`openMetricsStream` `:31` / `openOrderStream` `:36` / `openEventStream` `:41`）+ 5 个攻击出口（`attackScenarios` `:297` / `runAttack` `:331` / `stopAttack` `:364` / `attackRecords` `:380` / `cleanupAttack` `:410`） | ⚠️ **两条路不同**：三条 SSE 走自写的 `fetch` + `ReadableStream` 解析；五条攻击出口走 `http.post`（表单串）。⚠️ `runAttack` **必须显式传长 timeout**。⚠️ **commit 10c 追加两件**：`stopAttack()`（非破坏、无参）与 `openResilientStream(path, …)` `:86` —— 后者是三条 SSE 的**断线重连**包装（指数退避 + `onLost`）；`openStream` `:155` 仍是**不带重连**的底层。<br>⚠️ **3a.1（乙案）**：`runAttack` 里 `protections` / `injections` **两行已删**（不再是 `/run` 的入参 ⇒ 本文件也**不再**有对应封装函数；两个页面的开关栏一起做，排 3a.6）。⚠️ 本行其余坐标是**块 7 主体时点**的读数（`runAttack` 现址 `:332`、`replay` 现址 `:416`），与现值有漂移，**按现值读**别照抄本行 |
| `src/views/admin/observe/AttackConsole.vue` | 新建，攻击模拟器页（8 卡片 / 一键跑 / 自定义编排 / 演练记录 / **清场**） | ⚠️ 清场按钮走**两段式确认**（`armed` 态，第一次点只上膛、第二次才发请求），先例是 `OrderPage.vue` 的 `armed` 模式 |
| `src/views/admin/observe/MetricsPanel.vue` · `OrderFlow.vue` · `EventLog.vue` | 新建，指标面板 / 状态流转 / 实时日志 | 三页各开一条流 |
| `src/config/labels/` | **新建目录**（照 `config/notify-events.js` 惯例）：`observe-labels.js` / `report-labels.js` / `audit-labels.js` / `order-labels.js` / `user-labels.js` | **块 7 commit 9 的中文标签层**：英文 code → 中文；**兜底 = 认不出回 code 原文**（用 `hasOwnProperty` 判，不是 `TBL[k] \|\| k`） |

### H.5 白名单的契约改动：**零** {#api-h-5}

⚠️ **`AUTH_WHITELIST` 块 7 **维持 14 条不变** —— 可观测域**一条都不进**。判据是「公开面」：本域八条全是**管理端**端点，而管理端端点没有一条是公开面（同附录 G.6 那条 ⚠️）。
⚠️ **同理不加角色闸**（TD-18）：真正把端点挡在门外的是 `app.auth.enabled` + `AuthInterceptor`。
⚠️ **a3 / dev 两 profile 的 `app.auth.enabled=false` ⇒ 本域八条匿名可达** —— 那是验证环境的既定形态（该 profile 下**每一个**管理端端点都如此），不是本域引入的口子。

### H.6 跨线程透传的契约面：出站头 `X-Drill-Batch` {#api-h-6}

块 7 对**业务端点**的唯一契约改动不是一个入参，而是一个**出站请求头**：

| 头 | 常量 | 作用 |
|---|---|---|
| `X-Trace-Id` | `TraceIdFilter.HEADER`（`TraceIdFilter.java:67`） | 既有：链路号 |
| `X-Drill-Batch` | `TraceIdFilter.BATCH_HEADER`（同文件 `:79`） | **块 7 新增**：演练批次号 |

链路：`AttackEngine`（请求线程）`new ObserveContext(TraceIdFilter.current(), currentBatch, null, null)`（演练流量没有身份 ⇒ 后两个分量恒 `null`） → `ctx.run()` 绑到 `drill-<key>` 线程（线程名 `"drill-" + key`）→ 出站带 `X-Drill-Batch`（**刻意不带 `X-Trace-Id`** ⇒ 每条子请求各有自己的链路号）→ `TraceIdFilter.doFilterInternal` 入 MDC（键 `batchId`，常量 `MDC_BATCH_KEY` @ `:82`）→ 八环记录点读 MDC → `observed_event.BatchId`。

⚠️ **MDC 键名四个**（块 7 · 阶段 3a.3 由 2 个扩到 4 个）：`traceId`（`MDC_KEY` @ `:70`）、`batchId`（`MDC_BATCH_KEY` @ `:82`）、`actorId`（`MDC_ACTOR_ID_KEY` @ `:91`）、`actorRole`（`MDC_ACTOR_ROLE_KEY` @ `:101`）—— 四个都是「本线程当前上下文」的一部分（`TraceIdFilter.bind` @ `:189` 一次 `put` 四个）。
⚠️ **`ObscureContext` / `ObserveContext` 不生成任何号** —— 它只是把调用线程上已有的四个值（`traceId` / `batchId` / `actorId` / `actorRole`）**搬**到子线程；批次号在**发流量之前**由 `AttackEngine` 生成一次。
⚠️ **日常流量的 `BatchId` 是 `NULL`**（不是空串）—— 非请求上下文（定时任务等）产生的事件没有批次，这是**正常值**（建表时列注释就这么定的）。判据是「**该批次**一个不漏、不混别批次」，**不是**「全表都带批次」。

### H.7 与业务域 / 台账的关系（本域的两处「落点」） {#api-h-7}

⚠️ **本域只在业务侧留两处痕迹**：① 上面那个出站头（**不**改任何业务端点的入参 / 返回）；② 新建台账表 `observed_event`（DDL 见 `db-schema.md`）。块 7 的八环记录点**跨域补挂**了两条审计（`goods.delete` / `comment.delete`）—— 那是**既有审计清单**的补挂，不是新端点。
⚠️ **⚠️ 台账的已知边界（逐字摘自 `EventBroadcaster` 类注释）**：`poll()` 由 **SSE 流线程**每秒驱动，唯一调用点是 `EventStreamService:96`（**events 流**；⚠️ **2026-10-15 块 7 commit 10b 重指**：**原 81 行** → **96 行**；且该调用点此后落在**每连接一份**的帧源里（丙）⇒ **每秒调用次数 = 打开页面数**，不再恒为 1；「没人看就不落库」的结论不变）⇒ **没有人打开实时日志页时，合并环不动、也就没有事件落库**。台账是「**观看时的过程记录**」，不是「无人值守的全量审计流水」。
⚠️ **保留期 job**：`ObservedEventCleanupJob` @ `:63`，`@Scheduled(fixedDelayString = "${mall.observe.cleanup.fixed-delay-ms:3600000}")`（缺省 1 小时）；两条保留期 —— 攻击批次行 `mall.observe.cleanup.attack-retention-days`（缺省 **3** 天）、日常事件行 `…normal-retention-days`（缺省 **30** 天）。⚠️ 总闸 `mall.observe.cleanup.job.enabled`（dev / a3 关着）+ `@EnableScheduling` 的**载体类**另受 `mall.downgrade.job.enabled` 管（见 `tech-debt.md`）。
⚠️ **MQ 缺席**：`mall.observe.mq.warn-interval-ms`（缺省 **300000** ms = 5 分钟）—— 每次重连失败距上次 WARN 超阈值即打一条 `[EVENT]` WARN（翻转时另有一条，双保险）；MQ 不在时**事件仍上屏**（走内存环），页面顶上的点变黄。
⚠️ **台账本笔（块 7 · 阶段 3a.3）多了三列** `ActorId` / `ActorRole` / `DurationUs`（㉑-1..㉑-3，DDL 与实测见 `db-schema.md` §19）—— 三列都是**有界标量**。⚠️ 帧里那个 `fields`（结构化明细）**刻意不进台账**（口径「**有界标量进列，无界自由文本不进**」）⇒ **回放（H.8）拿得到「谁 / 多久」，拿不到 `fields` 的那些 kv**；这一点在回放页上要如实说给用户，别让人以为「回放缺了字段」。

⚠️ **本文件（`api-contract.md`）的正文 §2 / §3 / §5 矩阵**本笔**未重测、未插入** —— 8 条新端点全部落在附录 H；正文坐标重填归「文档漂移清理笔」（同附录 E.7 的口径）。

### H.8 新端点 `POST /admin/observe/replay` —— 演练回放（块 7 收尾 commit 1） {#api-h-8}

`ReplayController.java`（`@RequestMapping("/admin/observe")` @ `:42`，方法 `@PostMapping("/replay")` @ `:64`）—— 块 7 收尾 commit 1 新增；**用户 2026-10-15 在「甲（新端点）/ 乙（塞进现有面）」之间裁「甲」**。

| 项 | 口径 |
|---|---|
| 路径 | `POST /admin/observe/replay` —— ⚠️ **挂在 `/admin/observe`（根）**，**不是** `/admin/observe/attack/replay`：后端是**新建的 `ReplayController`**，不是 `AttackController` 的第七条。理由（两半）：① 它读的是**事件台账** `observed_event`，不是**演练记录** `drill_record`；② 另一条候选路是「再开一条 SSE 推回放」，那要多一条常驻流 + 回答「流断了怎么办」—— 改成一次性 JSON + 前端自己撒点，两个问题都不存在 |
| 入参 | `batchId`（**必填**；就是演练记录那一行的 `BatchId`，与 `observed_event.BatchId` **同一串值**）· `limit`（可选，缺省 **5000**、上限 **50000**；空白 / 0 / 负数 ⇒ 缺省，超上限收进上限，**非整数 ⇒ 400**） |
| 返回 | `{success, batchId, list:[…], total, limit}`。⚠️ `total` 是该批次**一共有多少条**（**不是**返回了几条）⇒ 前端才写得出「共 N 条，本次回放前 M 条」 |
| 排序 | `ORDER BY OccurredAt ASC, Id ASC`（`ObservedEventDao:158`）—— **两列都要**：少了第二列，同一毫秒内的顺序不确定 |
| 空批次 | 批次不存在 / 已被清理 job 删掉 ⇒ `list` 空表 + `total=0`，**仍然是 200**（「这个批次没有事件」是一个正常答案，不是错误） |
| 身份 / 闸 | **不加角色闸**、**不在 `AUTH_WHITELIST`**（靠 `AuthInterceptor` 拦）、**不写审计**（只读） |
| 与 H.1 各条的相同处 | 全 `POST`、`@RequestParam`（**不用** `@RequestBody`）、入参非法显式构 `ResponseEntity.badRequest()`（**不抛异常** —— `GlobalExceptionHandler` 的类级白名单不覆盖本类，抛出去会变成默认错误页） |

**前端**：`api/endpoints.js` 的 `observe.replay` @ `:449` → `api/observe.js` 的 `replay(batchId, limit)` @ `:416`（原 420 —— 3a.1 从 `runAttack` 里摘掉两行入参，其上方的 JSDoc 净减 5 行、函数体净增 1 行 ⇒ 这条随之下移到位）；唯一调用方是 `AttackConsole.vue` 演练记录那一行的〔回放〕钮。

⚠️ **播法由前端定**：拿到 JSON 后**按 `OccurredAt` 严格按原时间戳撒点** —— 不是一次性刷出来，也不是按数组下标均匀撒。
⚠️ **UI 上明说这句**：**「回放数据来自「当时开着日志页的演练」」** —— 因为台账的已知边界（见 **H.7** 那条：`poll()` 由 SSE 流线程驱动、**没人打开日志页就不落库**）⇒ 回放**只能**放得出「当时有人在看」的批次。这句是**如实告知**，不是免责声明。

## 附录 I：D 段〈防护加固〉契约增补（commit 2，2026-10-15） {#api-app-i}

⚠️ 本附录记的是**全站契约面**的变化 —— 与前七个附录不同，D 段**没有新端点**，它动的是
「**所有端点的外面那一层**」：一个新增的状态码（429）、一个新增的事件 kind（`protection`）、
以及一个**已有入参的语义从「只记录」变成「真生效」**。

### I.0 三个「数」要分开读（口径注，别互抄） {#api-i-0}

| 数 | 值 | 出处 |
|---|---|---|
| 新端点 | **0** | 本段一行 Controller 都没加（限流/熔断/分池都在既有点位**外面**） |
| 新增**入站状态码** | **1**（`429`） | I.1 |
| 事件 kind | **8 → 9**（第 9 类 `protection`） | I.2 |
| 真接闸的防护开关 | **3**（+1 键登记 TD +1 键记录位） | I.3 |

### I.1 全站新增 `429`：入站限流 {#api-i-1}

| 项 | 口径 |
|---|---|
| 谁答的 | `InboundRateLimitInterceptor`（挂 `/**`，`ProtectConfig` 注册，order = `Ordered.HIGHEST_PRECEDENCE`）—— **不是**任何 Controller |
| 什么时候 | 令牌桶空（阈值 **200 条 / 秒**，**等待 0** ⇒ 不排队、当场答）。⚠️ 「不排队」由 `timeoutDuration=0` 保证，不由方法名保证（`ProtectConfig.TIMEOUT`） |
| 响应 | `429` + `application/json;charset=UTF-8`，体 `{"success":false,"msg":"请求过于频繁：入站限流每 N 秒放行 M 条，这一拍没有余量，请稍后重试"}` —— **形状与 `Result.fail(msg)` 逐字同款**（前端 `http.js` 的失败分支读的就是 `success` / `msg`） |
| 白名单（**6 条**） | `/health/**` · `/actuator/**` · `/error` · `/img/**` · `/favicon.ico` · `/admin/observe/**` |
| ⚠️ 第 6 条为什么在 | 观测域整段豁免：面板的〔暂停〕打的是 `/admin/observe/attack/stop`，闸一开、演练一满档，这条控制请求会和演练自己的流量抢同一桶令牌 ⇒ **「演示现场按不动停止按钮」**。⚠️ 它**不放宽任何业务面**（八条业务端点一条都没进白名单） |
| 已知缺口（如实记） | ① **拦截器不覆盖「没匹配到 handler」的请求**（404 在 `DispatcherServlet` 找 handler 时就结束了）⇒ 拿不存在的路径刷量**不计数、不拦**；② **异步分派会让 `preHandle` 再跑一次** ⇒ 一条 SSE 流占两个令牌（三条流页均 3 条，数量级上不可见） |
| 前端 | **零改动** —— 429 走既有的失败分支（`http.js` 已把非 2xx 转成 `HttpError` 并带 `msg`） |

### I.2 第 9 类事件 kind：`protection` {#api-i-2}

| 项 | 口径 |
|---|---|
| 在哪儿 | SSE 帧 `counts` 里多一个键 `protection`（**8 → 9**）；`EventLog.vue` 的过滤类自动多一格（`v-for="k in KINDS"`） |
| 记什么 | **防护层自己做了什么**：限流拦下 / 熔断开路 / 熔断半开 / 熔断恢复 / 池挤出 / **死锁重试成功 · 重试又失败**（后两档阶段 2b 起） |
| 指标 | `mall.protection.events{protection=…, result=…}` —— `protection` 五值、`result` **七**档（`blocked` / `opened` / `half_open` / `closed` / `shed` / `retry_success` / `retry_failed`），**两列都有界**；⚠️ `scope` 与 `detail` **绝不进 tag**（`detail` 是自由文本，含路径与异常类名） |
| 明说不埋的 | **不埋「放行了多少」** —— 那是「到达量 − 拦下量」的派生量，到达量由 `http.server.requests` 回答（规范第 6 条：可派生的值不重复埋点） |
| 四处写入点 | `InboundRateLimitInterceptor`（429）· `ProtectionMetricsBinder`（订阅 `CircuitBreaker` 的状态事件流）· `ObservePoolConfig` 的拒绝策略（池挤出）· `MerchantDowngradeService` 的重试回调（`retry_success` / `retry_failed`，阶段 2b 起 —— 分母刻意只算「真重试过」的那些次） |
| ⚠️ 半开恢复**为什么有记录** | 因为它挂在 `getEventPublisher()` 上，而不是散在调用点上的日志 —— 三档翻转（打开 / 半开 / 恢复）**不是业务代码触发的**，可能由 r4j 自己的线程在**没有任何请求**时完成 |

### I.3 `protections` 开关：从「只记录」变成「真生效」（**4 键 + 1 键记录位**，阶段 2b 起；⚠️ 3a.1 起是**共享配置**，不再是 `/run` 入参） {#api-i-3}

| key | 本段状态 | 闸在哪 | 关掉它的后果 |
|---|---|---|---|
| `rateLimit` | ✅ 接闸 | 入站限流（I.1，200 QPS） | 没有闸：整站按后端能力吃满 |
| `circuitBreak` | ✅ 接闸 | 关键下游熔断（I.5） | 没有熔断：MySQL 慢/挂时请求照打下去 |
| `poolIsolation` | ✅ 接闸 | 观测池分池（有界 ↔ **无界**） | 回到无界池：观测面的任务不再被挤 |
| `retry` | ✅ **接闸**（阶段 2b 起） | 死锁重试（`MerchantDowngradeService.relist`，最多 3 次尝试 / 退避 40ms 起 ×2 封顶 200ms） | 一次死锁即抛 —— 同一探针 48 轮实测：**有重试 5xx = 0，无重试 5xx = 139**（死锁本身照旧在发生，只是每次都兜住）。⚠️ 原判「本段不落码」已被实测推翻，见 `tech-debt.md` 的〈TD-74 订正〉 |
| `degrade` | ➖ **只记录** | 无（系统固有 fail-open） | **没有后果** —— 缓存 L2 回落 / 锁 fail-open / MQ 缺席 / 审计 fail-open 本来就存在，不由这个开关管 |

⚠️ **口径（2026-10-15 · 阶段 2b 起）**：本段是 **4 键接闸 + 1 键记录位**。**不许**写成「5 键已接闸」。⚠️ 本条在 commit 2 那一刻写的是「3 键接闸 + 1 键登记 TD + 1 键记录位」并**明令不许**写「4 键已接闸」—— 那句当时是对的，`retry` 那时确实没有一行代码；阶段 2b 拿到死锁实测后**裁定接闸 3 → 4**，口径随之翻转。判据的唯一代码出处仍是 `ProtectionGate.WIRED_KEYS`。
⚠️ **写入时机（⚠️ 3a.1 起改写，旧文见下条）**：写点**只有** `POST /admin/observe/attack/config` 一处；演练开跑**不写**、跑完也**不复位**（用户 2026-10-16 裁定）。⚠️ 未列出的键一律**关掉**（`ProtectionGate.apply` 的语义是「按勾了哪些写」，不是「只打开勾了的」—— 后者会让上一轮的残留永远消不掉）。
⚠️ **「不复位」的代价与兜底**：不复位等于「上一轮把限流关掉」这件事**越过那一轮留在进程里**，而真实流量读的正是这些位。故兜底三条**必须同时成立**：`GET /config` 的 `IsDefault` 让「当前不是默认」**可读**；两个页面（攻击页 + 日志页）据此**常驻**「⚠ 当前非默认配置」提示 + 一键 [恢复默认]；`AttackSwitchConfig.update` 在非默认时记一行 **WARN**。⚠️ 兜底的边界如实写：它把「静默」变成「可见」，但**不阻止**有人拨完不管 —— **重启进程是唯一的自动恢复点**。
⚠️ **~~写入时机（甲案，已废）~~**：3a.1 之前由 `DrillRecordService` 在 `attackEngine.run(...)` **之前**写入、在 `finally` 里复位成**全开**。那段**整段删除** —— 保留此句只为让后来的人查得到翻转（`AttackSwitchConfig` 类注释里也记了同一处）。⚠️ `ProtectionGate.resetToDefault()` **方法本身留着**（新调用者是 `AttackSwitchConfig`），删的是演练收尾那次**自动**调用。
⚠️ **缺省口径的唯一换算点（3a.1 起搬到 `AttackSwitchConfig`）**：配置面的缺省是**全开**，而 `ProtectionGate.apply(null)` 的语义是**全关** ⇒ 换算收在 `AttackSwitchConfig.update` 一处（`protections == null` = **不动这一项**，与「给了 `[]` = 关光」是两件事）。3a.1 之前这个换算在 `DrillRecordService`（`protections == null ? ProtectionGate.KEYS : protections`）。
⚠️ **【调用点不变量 · 台账条目】**（用户 2026-10-16 裁定「(b) 静态不变量 —— 写进台账」）：上面「写点**只有** `POST /config` 一处」这句话**不能只靠测试守**。测试守住的是另外两个属性 —— `AttackSwitchConfigTest` 守「配置面**自己不**自动复位」（**自我属性**）、`smoke/AttackConfigNoResetSmokeTest` 守「跑一轮之后**逐字不变**」（**流程属性**）；两者都**盖不住**第三种失效：**多出一个写回调用点**（例如将来给日志页加个定时复位、或在某条冷路径上顺手写回）—— 它可能一条现有用例都不红。⇒ 补一条**可 grep 的静态不变量**，判据是**调用点数量**不是行为：

| 写方法 | `src/main` 里允许的调用点 | 2026-10-16 实测 |
|---|---|---|
| `AttackSwitchConfig.updateFromJson` | **只允许** `AttackController`（`POST /config`） | 1 处 |
| `AttackSwitchConfig.update(List,List)` | **无**（现在只被测试直调） | 0 处 |
| `AttackSwitchConfig.resetToDefault()` | **无** —— 方法**留着**给 3a.6 的 [恢复默认]，尚未接线 | 0 处 |
| `ProtectionGate.resetToDefault()` | **只允许** `AttackSwitchConfig`（代理，不是第二份真值） | 1 处 |

复核命令（**每笔收笔前跑一次**；判据是「条数与身份」，行号会随编辑漂移）：
```
grep -rn "updateFromJson(\|\.resetToDefault(" --include=*.java Mall-management-server/src/main
```
⚠️ 它现在输出**恰好 5 行**，逐行身份必须就是这 5 条 —— **多出任何一条都要停下来查**：
① `observe/controller/AttackController.java` 里 `view(switchConfig.updateFromJson(...))` = **唯一的真调用点**（表第 1 行）；
② `observe/service/AttackSwitchConfig.java` 里 `public State updateFromJson(...)` = 方法**定义**，不是调用点；
③ 同文件里 `gate.resetToDefault()` = **代理调用**（表第 4 行）；
④⑤ `observe/service/DrillRecordService.java` 的两行 javadoc = **注释文本**（写的是**已删除**的那次收尾调用、以及「方法留着」的说明），**不是调用点**。
⚠️ 三条判据互补：**自我属性**（配置面）、**流程属性**（跑一轮）、**调用点数量**（本条）。任何一格的现状变了，都要回到本表改，别只在测试里加断言。

### I.4 面板：**一枚**不可开关（不是死控件，也不是藏起来） {#api-i-4}

| 项 | 口径 |
|---|---|
| 哪一枚 | `degrade`（降级）—— 判据来源 `ProtectionGate.WIRED_KEYS` 的镜像 `PROTECTIONS_NOT_WIRED`。⚠️ 阶段 2b 前这里是**两枚**（`retry` / `degrade`）；`retry` 接闸后**必须移出**，否则那一枚会变成一个「能拨却什么都不发生」的死控件 |
| 画法 | **标灰**（`opacity .7`、虚线边、`cursor: default`）+ `disabled` + `aria-disabled="true"` + 一枚**可见的**「不可开关」角标 |
| 说明 | `title`（tooltip）**逐族不同**：不可开关的那一枚 = 「记录位，不可开关，系统固有 fail-open 设计」；接闸的那些 = 「…（点一下拨动）—— **已接闸，读数会变**」 |
| ⚠️ 为什么不是「藏起来」 | 藏起来会让「面板上少了一枚」与「这一笔忘了做」读起来一样 |
| ⚠️ 为什么不能是「能拨但没效果」 | 用户 2026-10-15 明令：**禁止死控件**。⇒ 模板 `disabled` + JS 里 `toggleProtection` 的守卫**两道都要有**（只画灰的话，模板一改就漏） |
| 证据 | 探针 `scripts/probes/probe-d2-locked-switches.mjs`（20 项，含「抠掉 `disabled` 再点仍不变」+「阶段 2b 新接闸的 `retry` 点一下**状态真变**」的对照）+ 截图 `docs/agent/evidence/c3-block7/d2-protection-switches-after-2b.png`（输出 `d2-locked-switches-after-2b.txt`；commit 2 那份 21 项的证据保留不动） |

### I.5 熔断：两个实例，**只挂 MySQL 这一条下游** {#api-i-5}

| 项 | 口径 |
|---|---|
| 实例 | `mallMysqlRead` / `mallMysqlWrite`（**按读 / 写分开**：演练灌的是读，「读被打开时写仍能过」是可演示的收益） |
| 落点 | `GoodsService` **5 处**（读 2：`goodsSelectHome` / `productSearch`；写 3：`goodsAdd` / `goodsDe` / `goodsUp`）+ `OrderService.orderAdd` **1 处** —— **跨域**（裁定禁止「只挂订单 / 支付」） |
| ⚠️ 为什么不挂 Controller | 本仓有结构断言钉死「Controller 不得被 AOP 代理」（`MallManagementApplicationTests#goodsControllerIsNotAopProxied`），且裁定明禁「把熔断挂到入站层」—— 两条指向同一件事：熔断要挂在**看得见下游**的那一层 |
| 阈值 | 错误率 **50%** · `TIME_BASED` 窗口 **30s** · 最小请求数 **20** · 半开 **放 5 个** · 打开后等 **30s** · **慢调用**（≥1s）也算失败（50%）· `IllegalArgumentException` 不计入 |
| 切面顺序 | r4j 的 `CircuitBreakerAspect` order = `2147483643`（= `Integer.MAX_VALUE - 4`）< 事务切面 `MAX` ⇒ **熔断在外、事务在内**：开路时连事务都不会开。⚠️ 这是**实测值**，不是「应该在外层」 |
| ⚠️ 为什么不挂 Redis / MQ | 那两条**本仓已有手写熔断**（`RabbitEventPublisher` 的 `connected` 闸 + 探测线程；`DistributedLockService` 的 cooldown + `L2HealthProbe`）⇒ 再叠一层是重复造。见 **TD-75** |
| 证据 | `ProtectionThresholdsTest`（阈值逐条）+ `CircuitBreakerWiringTest`（挂载点逐个）+ `ProtectionMetricsBinderTest`（真熔断器的三档翻转与「被拦」记录） |

### I.6 前端封装增补 {#api-i-6}

**零新增 API 封装**（没有新端点）。改的是三处**表**与一处**模板**：`observe-labels.js` 的
`KINDS` / `KIND_LABEL` / `KIND_COLOR` / `KIND_ICON` / `OBJECT_FIELD_LABEL`（各多一条 `protection`）
+ 新增 `PROTECTIONS_NOT_WIRED` / `PROTECTION_NOT_WIRED_TIP` / `protectionWired` / `protectionNotWiredTip`；
`AttackConsole.vue` 的防护开关那一排（I.4）。

⚠️ **色 token 用的是既有的 `--c9`**（`assets/tokens.css` 的浅色 `:201` / admin `:301` 早就定义了，两套主题同值）
⇒ **本笔没有动 `tokens.css`**（动了就会让那两处引用漂移）。
