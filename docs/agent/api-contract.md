# 接口契约 — Mall-management

> **证据来源**：5 个 Controller 全文。缩写：`U`=`UserController.java`、`M`=`MessageController.java`、`G`=`GoodsController.java`、`O`=`OrderController.java`、`H`=`HealthController.java`（数字为源码行号）。
> **行号口径（2026-09-30 实测）**：`证据`列 = 该方法 **route 注解所在行**（`@PostMapping` / `@GetMapping` / `@RequestMapping`），**不是**方法签名行。全表行号已于 2026-09-30 对 HEAD 逐条复核并替换 —— 此前一版坐标系统性偏旧（偏 10~35 行，个别为负偏移），成因是各笔新代码插入后未同步。
> **2026-10-02 复测（URL 规范化 + 订单端点拆分后）**：`/mysqlGoods` 一分为二 —— 商品 9 端点留 `GoodsController`（前缀 `/goods`）；订单 8 端点迁入新文件 `OrderController`（前缀 `/order`，记 `O:`）。`U:` / `M:` / `H:` 三列**原样保持**（那两个类只改类名与类级前缀，行数未变，行号零漂移；2026-10-02 复测仍**精确命中**）。⚠️ **但 `U:` 那一栏已于 2026-10-02 三层第三笔（user 域，`0c07e96`）后作废** —— `UserController` 已重写为 107 行（只剩路由，无 SQL、无 `@Transactional`），旧 `U:<n>`（34/56/124/176/241…）全部越界。`/user` 段 5 行已就地改为 `UserController.<方法>:<行>`，行号**沿用本表口径 = `@PostMapping` 行**（**不是**方法声明行，与 `db-schema.md:5` 相反，勿互抄）。⚠️ **`M:` 那一栏同理，已于 2026-10-03 三层第四笔（message 域，`8ed5fc1`）后作废** —— `MessageController` 已重写为 **127 行**（只剩路由，无 SQL、无 `@Transactional`、无 `@RequestParam` 默认值），旧 `M:<n>`（34/37/89/112/137/181/224/279/294/327/361…）全部越界。`/message` 段 10 行已就地改为 `MessageController.<方法>:<行>`，行号**沿用本表口径 = `@PostMapping` 行**，方法体与 SQL 的现址见该表后的「业务体现址」。⚠️ **`H:` 那一栏未动**（`HealthController` 本笔未碰）。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-03 实测**；重构后须重测。
> ⚠️ **同版本复核（对账上一版的「`G:` 行号整体 −9」）**：以 `git show 8c5385e:…goods/controller/GoodsController.java`（**笔 2 后**状态）逐条实测，其 route 行为 **50 / 72 / 173 / 187 / 197 / 245 / 280 / 359 / 369** —— 与上一版表内 `G:` 值**逐条相同** ⇒ 那一栏在笔 2 时是**准的**，不是陈旧值。但「**整体 −9**」只是粗略说法：实测相对 2026-09-30 基线（`148e77b`，`:59 / :81 / :181 / :195 / :205 / :249 / :264 / :336 / :346`）是 `goodstype`/`goodsAdd` **−9**、`goodsSelect` 一族 **−8**、`productCodePrice` **−4**、`goodsUp` 之后反而 **+16 / +23 / +23**（因笔 2 在该段加了说明注释）⇒ 引用时**不要拿 −9 去换算全表**。
> **2026-10-02 三层第二笔（商品域）又整体前移**：9 个端点的**路由仍在 `GoodsController`**，**业务体（SQL / try-catch / 响应组装）下沉到 `GoodsService`，SQL 出口收进 `GoodsDao`** ⇒ `G:` 现值 = **51 / 72 / 92 / 101 / 109 / 127 / 142 / 158 / 171**（实测，已重填），且它**只保证「路由还在哪一行」**；方法体位置另见 `/goods` 表下的「业务体现址」注。
> **「幂等」判定标准**：*相同入参重复调用是否产生额外副作用（不重复写入）*。非 HTTP 语义幂等。
> ~~所有接口均无鉴权~~ ⚠️ **2026-10-04 订正（鉴权笔 1/2）**：鉴权闸（`AuthInterceptor` + `WebAuthConfig`）已落地；⚠️ **2026-10-05 翻开关笔：`app.auth.enabled` 已由 `false` 翻成 `true`** ⇒ 该闸自今日起**真正生效**（白名单外无会话 = 401；原「**默认关闭**（`app.auth.enabled=false`）⇒ 行为仍是全部放行」已成史料）；但 `/user/loginSelect`（认证成功后写会话）、`/user/me`、`/user/logout` 三个端点的**会话侧**已就位。无统一返回体、无全局异常处理；~~5 个 Controller 均标 `@CrossOrigin`~~ ⚠️ **2026-10-04 订正：那 5 处 `@CrossOrigin` 已由 PR #34 `23f5361` 全部删除** ⇒ CORS 唯一出处 = `ImgConfig` 的全局 `CorsFilter`。入参均为 `@RequestParam`（query/form），无 `@RequestBody`。

## 1. 通用约定

- 全部路径无版本号、无 RESTful 语义，**全部 `POST`**，仅 **2 个 `GET`**：业务侧 `/goods/goodstype`、运维侧 `/health/db`（`@Profile("a3")`，`HealthController:43`，仅 a3 profile 下注册 —— 原记只列了 `goodstype`，漏了它）。
- 失败返回不统一：`loginSelect` 返回 `0`；`getUserInfo` 返回 `null`（HTTP 200 空体）；`/user/me` 无会话返回 **HTTP 401** + `{success:false,msg:"未登录"}`（与闸的 401 **逐字节相同**，共享常量 `AuthInterceptor.BODY_NOT_LOGGED_IN`）；`/goods` 与 `/order` 系列返回 `{success:false, msg}`。
- 无分页参数，列表接口全量返回。
- ⚠️ **「读接口」≠「无副作用」**：本表按**方法语义**分类，不按 HTTP 方法。`/message/history` 名字是查询，**实为写** —— 除 SELECT 外**无条件** upsert `user_conversation_read.last_read_time = NOW()`（SQL 在 `MessageDao.upsertLastReadTime:206-209`，由 `MessageService.getMessageHistory:200` 调用）⇒ 统计「读接口」或做只读性验证时**不得**把它算进去。

## 2. 接口清单（共 36 个）

> ⚠️ **35 → 36（2026-10-06，多账户隔离笔）**：`/user` 段再新增 1 个会话侧端点 —— `POST /user/token`
> （铸 43 字符访问 token，`AuthTokenStore.mint`）。`### /user` 表 7 行 → **8 行**；本表 `UserController.<方法>:<行>`
> 一并按 2026-10-06 实测**全部重填**（成因：本笔在 `UserController` 内插入 `token` 端点与类 javadoc）。
> ⚠️ **33 → 35（2026-10-04，鉴权笔 2）**：`/user` 段新增 2 个会话侧端点（`me` / `logout`）。
> `Result` 类注释里"33 个端点 / 33 处响应 Map"两处口径**在本笔之后都已过时**（无论按哪个口径数）——
> 本笔**不改 `Result.java`**，只在 `progress.md §5-Q` 登记（避免夹带）。

### /user — 8 个

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

### /message — 10 个

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

### /goods — 9 个（商品域，原 `/mysqlGoods` 的商品部分）

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

### /order — 8 个（订单域，2026-10-02 从 `GoodsController` 拆出）

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

### /health — 1 个

> ⚠️ **本段是运维接口，不是业务接口**：`@Profile("a3")` ⇒ **只在 a3 profile 下注册**；消费者是 A3 并发脚本（P5 就绪探测 / P6 库身份 / P7 阴性对照），**前端 0 调用**。它被刻意设计为**不带任何可缓存数据**（防缓存污染），见 `cache-layer.md §11`。

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| GET | `/health/db` | 无 | `{success, serverUuid, hostname, database, port, version}` | 是 | `H:43` |

## 3. 逐条说明

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

## 4. 待确认

- ~~前端实际调用的接口子集与参数拼装方式 —— 前端源码未读。~~ **✅ 已关闭（2026-09-30）**：前端源码已读，矩阵见 **§5**。
- ~~`ImgPath.java` / `ImgConfig.java` 是否额外暴露静态资源路由（影响 `http://localhost:8088/img/` 前缀的可用性）~~ **已关闭**：`ImgPath`（`@Configuration implements WebMvcConfigurer`）注册了 `/img/**` → `app.upload-dir` 的资源映射，故 `http://localhost:8088/img/<文件名>` 前缀可用；`ImgConfig` 只注册全局 `CorsFilter`，不涉及静态资源路由。证据（**2026-10-02 实测**；原记 `:15-16` / `:30-35` 已失效）：`ImgPath.java:17`（`@Value` 读 `app.upload-dir`）、`:45-64`（`@PostConstruct initUploadDir()`）、`:66-80`（`addResourceHandlers`，注册调用在 `:78`）；`ImgConfig.java:55-56` + `:67-68`（`@Configuration` + `@Bean corsFilter`；**原记 `:9-12`** —— ⚠️ **2026-10-06 CORS 收窄笔**新增 3 个 import + 类 javadoc ⇒ 其后行号整体下移，口径见 `progress.md` §5-AH）。实证：A2 的 `ImgMappingSmokeTest` 已断言 `GET /img/default_user.png` → 200。

## 5. 前端调用方矩阵（2026-09-30 实测，首次）

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
| `/order/orderAdd` | `ProductPage.vue:369` |
| `/order/orderSelect` | `ShoppingCart.vue:108` |
| `/order/orderDeliveryIfSelect` | `OrderCenter.vue:125`※ |
| `/order/orderDeliverySelect` | `OrderCenter.vue:127`※ |
| `/order/historicalOrdersSelect` | `HistoricalOrders.vue:95` |
| `/order/orderPayIfUp` | `ShoppingCart.vue:156` |
| `/order/orderReceiveIfUp` | `ShoppingCart.vue:190` |
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

### 5.1 三处「变量 URL」（静态 grep 看不到，须读分支）

| 文件 | 判定条件 | 两端取值 |
|---|---|---|
| `GoodsSelect.vue:83-91`（R2 重指；R1 记 `:82-90`、原记 `:71-80`） | `kw === ''` | 空 → `goodsSelectHome`；非空 → `productSearch`（body = `keyword=<encodeURIComponent(kw)>`） |
| `OrderCenter.vue:122-129` | `orderStatus.value === 'completed'` | `orderDeliverySelect`；否则 `orderDeliveryIfSelect`（两分支同传 `merchantId` + `status`） |
| `FriendRoom.vue:224-229` | `action === 'agree'` | `friendUp`；否则 `friendDe` |

> ※ 这三处的 `fetch(` 本身分别为 `GoodsSelect.vue:92`（R2 重指；R1 记 `:91`、原记 `:81`）/ `OrderCenter.vue:129` / `FriendRoom.vue:231` —— 三者的 URL 都是变量，故上表标的是 **URL 赋值行**。

### 5.2 未验区域（2026-09-30 边界）

- **15 个写接口全部未验**：`loginAdd`、`updateAvatar`、`updateUserInfo`、`goodsAdd`、`goodsDe`、`goodsUp`、`orderAdd`、`orderPayIfUp`、`orderReceiveIfUp`、`orderDeliveryIfUp`、`/message/send`、`friendAdd`、`friendUp`、`friendDe`、**`/message/history`（名为读、实为写，见 §1）**。
- **已验的 17 个读端点**：全部 **HTTP 200**，**0 次 5xx、0 次超时**（2026-09-30，最小构造入参）。
- **参数名级不匹配：0 条**。字段/语义级问题见 `defect-survey.md`。
---

## 附录 C：C3 块 4〈订单块〉契约增补（2026-10-10）

> **本附录与正文的关系**：正文 §1 的 `O:` 一栏是 **2026-10-03 / 10-04 的旧坐标**；块 4 让 `OrderController` 由 65 行长到 **156 行**（+91，逐处见 `slot-registry.md §二十`）⇒ **全部 `O:` 值已陈旧**。按 §十五 15-5「已陈旧者只登记、不修」，本笔**就地不动正文**，把新坐标与新端点登记在此；重填 `O:` 归「文档飘移清理笔」。
> **判据档位**：本附录所有行号均为 **2026-10-10 实测**（`grep -n '@PostMapping'`，口径 = **`@PostMapping` 行**，与正文一致、与 `db-schema.md:5` 相反）。

### C.1 正文 `O:` 一栏的新坐标（2026-10-10 实测）

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

### C.2 `/order/orderAdd` 改动（入参 9 → 10）

| 项 | 内容 |
|---|---|
| 新增入参 | `Quantity` —— `@RequestParam(value="Quantity", required=false, defaultValue="1") int`（`OrderController.java:105`） |
| 兼容性 | **向后兼容**：老调用方不传 ⇒ 落 `1`，与块 4 前语义逐字一致 |
| 新增守卫 | 方法第一行 `IdentityGuard.requireSelfOrAdmin(request, CustomerId)`（`:110-113`）⇒ 越权访问 **403**（**不是** 200+`success:false`，本仓唯一例外） |
| ⚠️ 判的是谁 | **`CustomerId`（买家自己）**，不是 `MerchantId`（对端方，当然要填别人的）。改这行前先读该处注释 |
| 三道既有闸 | 价格闸 / 出生状态闸 / `runLocked` **顺序与语义零变化**（正文 `:150`/`:151` 仍有效） |

### C.3 新端点组：`/cart/*`（`CartController.java` @ `:40`，121 行）

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

### C.4 新端点组：`/admin/order/*`（`AdminOrderController.java` @ `:37`，95 行）

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

### C.5 前端封装（供对账）

新增 `src/api/order.js`（**233 行**）：`q()`（丢空值）/ `build()`（全传）/ `post()`（剥 `Response`）三个内部件 + **8 个 `/order/*`** + **5 个 `/cart/*`** + **4 个 `/admin/order/*`** 导出。
`src/api/endpoints.js` 的 `orderAdmin: {list, detail, monitor, intervene}` 见 `:194-198`；`endpoints.js` 头部「现为 N 条」计数已**同行改**（**未新增行**）。

## 附录 D：C3 块 4 **补丁**〈付款 / 发货 模拟完整流程〉契约增补（2026-10-10）

> **与附录 C 的关系**：C 记的是块 4 本体（`OrderController` 65 → 156 行）；本补丁又让它长到 **252 行**（+96）⇒ **C.1 那张表的新坐标已再次陈旧**。按同一条口径（只登记、不修），补丁的坐标登记在此；重填 `O:` 与 C.1 一律归「文档飘移清理笔」。
> **判据档位**：本附录行号均为 **2026-10-10 实测**（`grep -n '@PostMapping'`，口径 = **`@PostMapping` 行**，与正文 / 附录 C 一致）。

### D.1 `OrderController` 端点新坐标（2026-10-10 实测，含 C.1 的再一次偏移）

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

### D.2 新端点：`POST /order/orderDetail`（买家订单详情，A5 —— 用户 2026-10-10 批）

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

### D.3 三个 CAS 端点的入参扩展（**全部向后兼容**）

| 端点 | 新增入参 | 兼容性 | 副作用 |
|---|---|---|---|
| `/order/orderPayIfUp` | `paymentMethod?`（`@RequestParam(value="paymentMethod", required=false)`） | **兼容**：不传 ⇒ 落 `NULL`（**不是**空串） | 成功后 Service **生成 `TradeNo`**（`PAY+yyyyMMdd+6位随机`）并在**响应体**回传 `tradeNo`；同笔写 `order` 的 3 个支付列 |
| `/order/orderReceiveIfUp` | 无 | 不变 | 成功后写 `order.ReceivedAt` **且**保证 `delivered` 轨迹行存在（`NOT EXISTS` 幂等） |
| `/order/orderDeliveryIfUp` | `expressCompany?` / `trackingNo?` | **兼容**：都不传 ⇒ 两列 `NULL`（管理员代发走的 1 参重载即是此路） | 成功后写 `order` 的 3 个物流列；**不写轨迹**（2026-10-10 起四节点全移交调度器，见 §D.4） |

⚠️ **`intervene` 零改**：`AdminOrderService.intervene` 调的是 **1 参重载** `payIfUp(orderId)` / `deliveryIfUp(orderId)` / `receiveIfUp(orderId)` —— 三个重载**逐字保留** ⇒ 管理端介入的行为与契约**一个字符没变**（只是它落库时也会写明细列 / 轨迹，那对调用方不可见）。

### D.4 轨迹写入契约（`order_shipping_track`）

| 写入方 | 节点 | 时刻 | 幂等 |
|---|---|---|---|
| `OrderService.deliveryIfUp` | **不再写轨迹**（2026-10-10 首条 `picked_up` 移交调度器） | — | — |
| `OrderService.receiveIfUp` | `delivered`（「已签收」）**兜底补写** | `now` | `ShippingTrackDao.exists(orderId, 'delivered')` |
| `ShippingTrackAdvanceJob`（@Scheduled） | `in_transit`（运输中）/ `picked_up`（驿站已揽收）/ `out_for_delivery`（驿站派送中）/ `delivered`（已签收） | `ShippedAt + 阈值` | 同上（每节点各判一次） |

- ⚠️ **调度器只 INSERT 轨迹，绝不 UPDATE `order` 的状态位**（用户 2026-10-10 批准的红线）⇒ **物流「已签收」≠ 订单「已收货」**（后者仍由买家点【收货】触发 CAS），前端分两词表述。
- ⚠️ **阈值走 properties**（`mall.order.shipping.track.{transit,picked-up,delivery,signed}-ms`），**演示默认 15 / 30 / 45 / 60 秒**（2026-10-10 由 30/60/120/240 秒收紧）；生产口径改配置即可。⚠️ **2026-10-11 起时序是「运输中 → 驿站已揽收 → 驿站派送中 → 已签收」**（前两步对调 ⇒ `in_transit` 拿 15s、`picked_up` 拿 30s）。
- ⚠️ **a3 / smoke 两个 profile 显式 `mall.order.shipping.track.enabled=false`**（同 `MerchantDowngradeJob` 款）⇒ 手验调度推进要在**默认 profile** 下做。

### D.5 前端封装增补

- `src/api/endpoints.js`：`order` 组加 `orderDetail`（**与 `historicalOrdersSelect` 同行追加**，`:56` / `:58` 两个被引行**不漂**）；头部「现为 N 条」计数 **78 → 79**（同行改）。
- `src/api/order.js`：新增 `orderDetail(orderId)`（**④ 节，文件末** —— 与 ① 组的「搬家 8 条」在语义上分开）；`orderPay(orderId, paymentMethod?)` 与 `orderDeliver(orderId, expressCompany?, trackingNo?)` 扩参，**空值不发**（与 `build()` 的「全传」相反，此处刻意用 `if` 筛）。
- `OrderMine.vue`：**收银台弹窗**（`PAY_METHODS` 三选一 → `orderPay(orderId, method)`；成功回显 `tradeNo`）+ **物流详情弹窗**（`orderDetail` 的 `shippingTrack` 画时间线）。
- `OrderCenter.vue`：**发货弹窗**（快递公司 / 运单号，**可留空**）；原 `handleDeliver` 的三道守卫**逐字保留**、只从「点击即判」挪到「开窗前判」。
- `views/admin/OrderPage.vue`：详情区加**支付 / 物流 7 列** + **第二条时间线**（`detail.shippingTrack`，**与 `detail.timeline` 并列不合并**）。
