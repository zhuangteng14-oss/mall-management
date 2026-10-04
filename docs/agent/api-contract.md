# 接口契约 — Mall-management

> **证据来源**：5 个 Controller 全文。缩写：`U`=`UserController.java`、`M`=`MessageController.java`、`G`=`GoodsController.java`、`O`=`OrderController.java`、`H`=`HealthController.java`（数字为源码行号）。
> **行号口径（2026-09-30 实测）**：`证据`列 = 该方法 **route 注解所在行**（`@PostMapping` / `@GetMapping` / `@RequestMapping`），**不是**方法签名行。全表行号已于 2026-09-30 对 HEAD 逐条复核并替换 —— 此前一版坐标系统性偏旧（偏 10~35 行，个别为负偏移），成因是各笔新代码插入后未同步。
> **2026-10-02 复测（URL 规范化 + 订单端点拆分后）**：`/mysqlGoods` 一分为二 —— 商品 9 端点留 `GoodsController`（前缀 `/goods`）；订单 8 端点迁入新文件 `OrderController`（前缀 `/order`，记 `O:`）。`U:` / `M:` / `H:` 三列**原样保持**（那两个类只改类名与类级前缀，行数未变，行号零漂移；2026-10-02 复测仍**精确命中**）。⚠️ **但 `U:` 那一栏已于 2026-10-02 三层第三笔（user 域，`0c07e96`）后作废** —— `UserController` 已重写为 107 行（只剩路由，无 SQL、无 `@Transactional`），旧 `U:<n>`（34/56/124/176/241…）全部越界。`/user` 段 5 行已就地改为 `UserController.<方法>:<行>`，行号**沿用本表口径 = `@PostMapping` 行**（**不是**方法声明行，与 `db-schema.md:5` 相反，勿互抄）。⚠️ **`M:` 那一栏同理，已于 2026-10-03 三层第四笔（message 域，`8ed5fc1`）后作废** —— `MessageController` 已重写为 **127 行**（只剩路由，无 SQL、无 `@Transactional`、无 `@RequestParam` 默认值），旧 `M:<n>`（34/37/89/112/137/181/224/279/294/327/361…）全部越界。`/message` 段 10 行已就地改为 `MessageController.<方法>:<行>`，行号**沿用本表口径 = `@PostMapping` 行**，方法体与 SQL 的现址见该表后的「业务体现址」。⚠️ **`H:` 那一栏未动**（`HealthController` 本笔未碰）。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-03 实测**；重构后须重测。
> ⚠️ **同版本复核（对账上一版的「`G:` 行号整体 −9」）**：以 `git show 8c5385e:…goods/controller/GoodsController.java`（**笔 2 后**状态）逐条实测，其 route 行为 **50 / 72 / 173 / 187 / 197 / 245 / 280 / 359 / 369** —— 与上一版表内 `G:` 值**逐条相同** ⇒ 那一栏在笔 2 时是**准的**，不是陈旧值。但「**整体 −9**」只是粗略说法：实测相对 2026-09-30 基线（`148e77b`，`:59 / :81 / :181 / :195 / :205 / :249 / :264 / :336 / :346`）是 `goodstype`/`goodsAdd` **−9**、`goodsSelect` 一族 **−8**、`productCodePrice` **−4**、`goodsUp` 之后反而 **+16 / +23 / +23**（因笔 2 在该段加了说明注释）⇒ 引用时**不要拿 −9 去换算全表**。
> **2026-10-02 三层第二笔（商品域）又整体前移**：9 个端点的**路由仍在 `GoodsController`**，**业务体（SQL / try-catch / 响应组装）下沉到 `GoodsService`，SQL 出口收进 `GoodsDao`** ⇒ `G:` 现值 = **51 / 72 / 92 / 101 / 109 / 127 / 142 / 158 / 171**（实测，已重填），且它**只保证「路由还在哪一行」**；方法体位置另见 `/goods` 表下的「业务体现址」注。
> **「幂等」判定标准**：*相同入参重复调用是否产生额外副作用（不重复写入）*。非 HTTP 语义幂等。
> 所有接口均无鉴权、无统一返回体、无全局异常处理；5 个 Controller 均标 `@CrossOrigin`。入参均为 `@RequestParam`（query/form），无 `@RequestBody`。

## 1. 通用约定

- 全部路径无版本号、无 RESTful 语义，**全部 `POST`**，仅 **2 个 `GET`**：业务侧 `/goods/goodstype`、运维侧 `/health/db`（`@Profile("a3")`，`HealthController:43`，仅 a3 profile 下注册 —— 原记只列了 `goodstype`，漏了它）。
- 失败返回不统一：`loginSelect` 返回 `0`；`getUserInfo` 返回 `null`（HTTP 200 空体）；`/goods` 与 `/order` 系列返回 `{success:false, msg}`。
- 无分页参数，列表接口全量返回。
- ⚠️ **「读接口」≠「无副作用」**：本表按**方法语义**分类，不按 HTTP 方法。`/message/history` 名字是查询，**实为写** —— 除 SELECT 外**无条件** upsert `user_conversation_read.last_read_time = NOW()`（SQL 在 `MessageDao.upsertLastReadTime:206-209`，由 `MessageService.getMessageHistory:200` 调用）⇒ 统计「读接口」或做只读性验证时**不得**把它算进去。

## 2. 接口清单（共 33 个）

### /user — 5 个

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/user/loginSelect` | username, password | `int`：1=客户 2=商家 0=失败 | 是 | `UserController.loginSelect:52` |
| POST | `/user/loginAdd` | image(默认 default_user.png), username, usertype, userid, customerid(默认"暂无数据"), merchantid(默认"暂无数据"), password, age, gender | `String`：`"0"`=已存在 / `"添加成功！"` | 是 ⚠️ | `UserController.loginAdd:61` |
| POST | `/user/getUserInfo` | username, password | `user` 全字段 + `image`；失败返回 `null` | 是 | `UserController.getUserInfo:80` |
| POST | `/user/updateAvatar` | username, file(MultipartFile) | `{success, msg?, imageUrl?}` | 是 | `UserController.updateAvatar:89` |
| POST | `/user/updateUserInfo` | username, email, address, selfintroduction | `{success, msg}` | 是 | `UserController.updateUserInfo:98` |

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
- `G:72 goodsAdd` / `G:109 goodsDe` / `G:142 goodsUp`：均同时写 `goods` 与 `productsales` 两张表，**✅ 已有事务保护**（**2026-10-04 C#2 `fix/goods-write-tx`**：三处各加 `@Transactional` + catch 内 `setRollbackOnly`；见 `concurrency-map.md`）。⚠️ **对外契约不变** —— 第二步失败仍是 **HTTP 200 + `{success:false,msg}`**（不是 500），变的只是「第一步要不要回滚」。⚠️ 2026-10-02 三层第二笔后，三者的**方法体**在 `GoodsService`：**2026-10-04 声明行** `goodsAdd:118` / `goodsDe:301` / `goodsUp:350`；本行原记的体范围 `83-148` / `211-238` / `252-306` **已因中间多次插入而失效**，引用请用符号名或声明行，别再拿旧范围换算。
- `O:100/107/114` 三个订单状态接口是全项目唯一带并发防护的写接口，详见 `concurrency-map.md` 正面案例。
- `G:171 productSearch`：`keyword` 未加 `@RequestParam` 显式命名，依赖参数名保留（编译需 `-parameters`）。关键词被逐字符拼进 SQL 字符串（**2026-10-02 起在 `GoodsDao:194-200`**，原 `G:380`），因拆分粒度为单字符**无法构造多字符注入**，但含 `'` 会使 SQL 语法错误；⚠️ **后果不止于此**：`_` / `%` 未转义 ⇒ `'%_%'` 命中任何非空 `GoodName` ⇒ **关键词含下划线或百分号时返回全表**（2026-09-30 实测），见 `defect-survey.md` 病 B 同条。

## 4. 待确认

- ~~前端实际调用的接口子集与参数拼装方式 —— 前端源码未读。~~ **✅ 已关闭（2026-09-30）**：前端源码已读，矩阵见 **§5**。
- ~~`ImgPath.java` / `ImgConfig.java` 是否额外暴露静态资源路由（影响 `http://localhost:8088/img/` 前缀的可用性）~~ **已关闭**：`ImgPath`（`@Configuration implements WebMvcConfigurer`）注册了 `/img/**` → `app.upload-dir` 的资源映射，故 `http://localhost:8088/img/<文件名>` 前缀可用；`ImgConfig` 只注册全局 `CorsFilter`，不涉及静态资源路由。证据（**2026-10-02 实测**；原记 `:15-16` / `:30-35` 已失效）：`ImgPath.java:17`（`@Value` 读 `app.upload-dir`）、`:45-64`（`@PostConstruct initUploadDir()`）、`:66-80`（`addResourceHandlers`，注册调用在 `:78`）；`ImgConfig.java:9-12`（`@Configuration` + `CorsFilter` bean）。实证：A2 的 `ImgMappingSmokeTest` 已断言 `GET /img/default_user.png` → 200。

## 5. 前端调用方矩阵（2026-09-30 实测，首次）

> **本节关闭 §4 原第 1 条**（「前端实际调用的接口子集与参数拼装方式 —— 前端源码未读」）。
> **口径**：`亲读`（Python 括号配平解析 42 处 `fetch(` + 逐行读 4 个 Controller 签名）。
> **范围**：`Mall-management-web/mall-management-system/src` 全部 `.js` / `.vue`；下表文件均位于 `src/views/`。
> **行号**：指**该 `fetch(` 调用的所在行**；标 ※ 者见下方「变量 URL」段。

**四项对账**（须自洽）：前端 `fetch(` 调用点 **42** 处 ／ 不同路径 **31** 条 ／ 本文件路由 **33** 条 ／ 差集 **33 − 31 = 2**（即表末两行「无调用方」）。

⚠️ **前端无 HTTP 框架**：`axios` / `$http` / `XMLHttpRequest` / `EventSource` / `baseURL` 在全 `src` **0 命中**；全部走原生 `fetch(`；`vue.config.js` **无 `proxy`**；`main.js` 无拦截器、无全局配置；URL 全部硬编码 `http://localhost:8088/...`。

| 端点 | 调用点 |
|---|---|
| `/user/loginSelect` | `LoginPage.vue:131` |
| `/user/getUserInfo` | `LoginPage.vue:142`、`GoodsUp.vue:150`、`UserInfoPage.vue:96` |
| `/user/loginAdd` | `LoginPage.vue:216` |
| `/user/updateAvatar` | `UserInfoPage.vue:130` |
| `/user/updateUserInfo` | `UserInfoPage.vue:149` |
| `/goods/goodstype` | `GoodsAdd.vue:135`（**裸 GET**，全文唯一非 POST 调用）、`GoodsAdd.vue:166`、`HomePage.vue:154` |
| `/goods/goodsAdd` | `GoodsAdd.vue:217` |
| `/goods/goodsSelect` | `GoodsUp.vue:170` |
| `/goods/goodsSelectHome` | `HomePage.vue:121`、`ProductPage.vue:170`、`GoodsSelect.vue:74`※ |
| `/goods/goodsDe` | `GoodsUp.vue:215` |
| `/goods/goodsUp` | `GoodsUp.vue:327` |
| `/goods/productCodePrice` | `GoodsUp.vue:275`、`ProductPage.vue:193`、`ProductPage.vue:245` |
| `/goods/GoodsProductCodeSelect` | `ProductPage.vue:184`、`ProductPage.vue:236` |
| `/goods/productSearch` | `GoodsSelect.vue:78`※ |
| `/order/orderAdd` | `ProductPage.vue:369` |
| `/order/orderSelect` | `ShoppingCart.vue:108` |
| `/order/orderDeliveryIfSelect` | `OrderCenter.vue:125`※ |
| `/order/orderDeliverySelect` | `OrderCenter.vue:127`※ |
| `/order/historicalOrdersSelect` | `HistoricalOrders.vue:95` |
| `/order/orderPayIfUp` | `ShoppingCart.vue:156` |
| `/order/orderReceiveIfUp` | `ShoppingCart.vue:190` |
| `/order/orderDeliveryIfUp` | `OrderCenter.vue:171` |
| `/message/conversations` | `ChatRoom.vue:184`、`CustomerPage.vue:97`、`MerchantPage.vue:113` |
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

### 5.1 三处「变量 URL」（静态 grep 看不到，须读分支）

| 文件 | 判定条件 | 两端取值 |
|---|---|---|
| `GoodsSelect.vue:71-80` | `kw === ''` | 空 → `goodsSelectHome`；非空 → `productSearch`（body = `keyword=<encodeURIComponent(kw)>`） |
| `OrderCenter.vue:122-129` | `orderStatus.value === 'completed'` | `orderDeliverySelect`；否则 `orderDeliveryIfSelect`（两分支同传 `merchantId` + `status`） |
| `FriendRoom.vue:224-229` | `action === 'agree'` | `friendUp`；否则 `friendDe` |

> ※ 这三处的 `fetch(` 本身分别为 `GoodsSelect.vue:81` / `OrderCenter.vue:129` / `FriendRoom.vue:231` —— 三者的 URL 都是变量，故上表标的是 **URL 赋值行**。

### 5.2 未验区域（2026-09-30 边界）

- **15 个写接口全部未验**：`loginAdd`、`updateAvatar`、`updateUserInfo`、`goodsAdd`、`goodsDe`、`goodsUp`、`orderAdd`、`orderPayIfUp`、`orderReceiveIfUp`、`orderDeliveryIfUp`、`/message/send`、`friendAdd`、`friendUp`、`friendDe`、**`/message/history`（名为读、实为写，见 §1）**。
- **已验的 17 个读端点**：全部 **HTTP 200**，**0 次 5xx、0 次超时**（2026-09-30，最小构造入参）。
- **参数名级不匹配：0 条**。字段/语义级问题见 `defect-survey.md`。
