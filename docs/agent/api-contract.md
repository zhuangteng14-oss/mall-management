# 接口契约 — Mall-management

> **证据来源**：5 个 Controller 全文。缩写：`U`=`UserController.java`、`M`=`MessageController.java`、`G`=`GoodsController.java`、`O`=`OrderController.java`、`H`=`HealthController.java`（数字为源码行号）。
> **行号口径（2026-09-30 实测）**：`证据`列 = 该方法 **route 注解所在行**（`@PostMapping` / `@GetMapping` / `@RequestMapping`），**不是**方法签名行。全表行号已于 2026-09-30 对 HEAD 逐条复核并替换 —— 此前一版坐标系统性偏旧（偏 10~35 行，个别为负偏移），成因是各笔新代码插入后未同步。
> **2026-10-02 复测（URL 规范化 + 订单端点拆分后）**：`/mysqlGoods` 一分为二 —— 商品 9 端点留 `GoodsController`（前缀 `/goods`，`G:` 行号整体 **−9**，因类 javadoc 与 import 调整）；订单 8 端点迁入新文件 `OrderController`（前缀 `/order`，记 `O:`）。`U:` / `M:` / `H:` 三列**原样保持**（那两个类只改类名与类级前缀，行数未变，行号零漂移）。
> **「幂等」判定标准**：*相同入参重复调用是否产生额外副作用（不重复写入）*。非 HTTP 语义幂等。
> 所有接口均无鉴权、无统一返回体、无全局异常处理；5 个 Controller 均标 `@CrossOrigin`。入参均为 `@RequestParam`（query/form），无 `@RequestBody`。

## 1. 通用约定

- 全部路径无版本号、无 RESTful 语义，除 `/goods/goodstype` 外全部为 `POST`。
- 失败返回不统一：`loginSelect` 返回 `0`；`getUserInfo` 返回 `null`（HTTP 200 空体）；`/goods` 与 `/order` 系列返回 `{success:false, msg}`。
- 无分页参数，列表接口全量返回。
- ⚠️ **「读接口」≠「无副作用」**：本表按**方法语义**分类，不按 HTTP 方法。`/message/history` 名字是查询，**实为写** —— 除 SELECT 外**无条件** upsert `user_conversation_read.last_read_time = NOW()`（`M:101-104`）⇒ 统计「读接口」或做只读性验证时**不得**把它算进去。

## 2. 接口清单（共 33 个）

### /user — 5 个

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/user/loginSelect` | username, password | `int`：1=客户 2=商家 0=失败 | 是 | `U:34` |
| POST | `/user/loginAdd` | image(默认 default_user.png), username, usertype, userid, customerid(默认"暂无数据"), merchantid(默认"暂无数据"), password, age, gender | `String`：`"0"`=已存在 / `"添加成功！"` | 是 ⚠️ | `U:56` |
| POST | `/user/getUserInfo` | username, password | `user` 全字段 + `image`；失败返回 `null` | 是 | `U:124` |
| POST | `/user/updateAvatar` | username, file(MultipartFile) | `{success, msg?, imageUrl?}` | 是 | `U:176` |
| POST | `/user/updateUserInfo` | username, email, address, selfintroduction | `{success, msg}` | 是 | `U:241` |

### /message — 10 个

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/message/conversations` | currentId, currentType | `{success, list[{other_id,other_type,last_time,unread}]}` | 是 | `M:37` |
| POST | `/message/history` | currentId, currentType, otherId, otherType | `{success, list[...]}` | **否** ⚠️**写**（见 §1） | `M:89` |
| POST | `/message/send` | fromId, fromType, toId, toType, content | `{success, msg}` | **否** | `M:112` |
| POST | `/message/getOrCreateConversation` | currentId, currentType, otherId, otherType | `{success, otherUser, messages}` | 是 | `M:137` |
| POST | `/message/userSelect` | userId | `{success, user{id,username,usertype,gender,image:null}}` | 是 | `M:181` |
| POST | `/message/friendAdd` | customerId, friendId | `{success, msg}` | 是 ⚠️ | `M:224` |
| POST | `/message/selectFriend` | friendId | `{success, list}` | 是 | `M:279` |
| POST | `/message/friendUp` | customerId, friendId | `{success, msg}` | 是 | `M:294` |
| POST | `/message/friendDe` | customerId, friendId | `{success, msg}` | 是 | `M:327` |
| POST | `/message/friendSelect` | customerId | `{success, list}` | 是 | `M:361` |

### /goods — 9 个（商品域，原 `/mysqlGoods` 的商品部分）

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| GET\|POST | `/goods/goodstype` | GET 无参；POST: GoodType, GoodTypeNew | GET `{success,list}`；POST `{success,msg}` | GET 是 / POST 是 ⚠️ | `G:50` |
| POST | `/goods/goodsAdd` | MerchantId, GoodName, Price, file(MultipartFile), GoodType, ProductCode, GoodTypeNew | `{success, msg, GoodImg}` | **否** | `G:72` |
| POST | `/goods/goodsSelect` | MerchantId | `{success, list}` | 是 | `G:173` |
| POST | `/goods/goodsSelectHome` | 无 | `{success, list}`（每行附 `UserName`） | 是 | `G:187` |
| POST | `/goods/goodsDe` | MerchantId, GoodName, ProductCode | `{success, msg}` | 是 | `G:197` |
| POST | `/goods/productCodePrice` | MerchantId, GoodName, ProductCode | `{success, price, goodImg}` | 是 | `G:245` |
| POST | `/goods/goodsUp` | MerchantId, GoodName, Price, GoodType, ProductCode, file(可选) | `{success, msg}` | 是 | `G:280` |
| POST | `/goods/GoodsProductCodeSelect` | MerchantId, GoodName | `{success, list[String]}` | 是 | `G:359` |
| POST | `/goods/productSearch` | keyword（**未用 `@RequestParam` 显式命名**） | `{success, list}` | 是 | `G:369` |

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

- ⚠️ `U:56 loginAdd`：靠「先 SELECT 查重再 INSERT」实现重复拦截（`U:70`→`U:80`），存在 TOCTOU 竞态（见 `concurrency-map.md`）。第二参数 `customerid`/`merchantid` 会被 `userid` 覆盖（`U:79`、`U:96`）。
- ⚠️ `M:224 friendAdd`：同为「先查后插」（`M:240`→`M:253`），存在 TOCTOU 竞态。
- ⚠️ `G:50 goodstype` POST：同为「先查后插」（**已搬**：`GoodsTypeCacheService:62`→`:71`）。返回 `{success:false,msg:"类型已存在"}` 而非 `"0"`，与 `loginAdd` 风格不一致。
- `M:89 history` 非幂等：除查询外还会 upsert `user_conversation_read.last_read_time = NOW()`（`M:101-104`），每次调用都刷新时间戳。
- `M:112 send` 非幂等：每次调用插入一条新 `message`（`M:119`）。
- `O:42 orderAdd` **仍非幂等**：每次调用插入一条新 `order`。⚠️ **原文**写「`OrderId` 由调用方提供且**无唯一约束**，可重复下单号」—— **2026-09-30 S1a（`1e2fcfa`）已加 UNIQUE `uk_order_orderId`**，同单号第二次调用会被 DB 拒（`orderAdd` 把它吞成 **HTTP 200 + `msg="数据库错误…"`**，见 TD-14 / `A-S2a-1c`）。**但「非幂等」这条没变**：拦的只是「同单号重复插入」，不同单号照旧每调一次插一行 ⇒ 幂等性仍不由接口保证。
- `G:72 goodsAdd` / `G:197 goodsDe` / `G:280 goodsUp`：均同时写 `goods` 与 `productsales` 两张表，**无事务保护**（见 `concurrency-map.md`）。
- `O:100/107/114` 三个订单状态接口是全项目唯一带并发防护的写接口，详见 `concurrency-map.md` 正面案例。
- `G:369 productSearch`：`keyword` 未加 `@RequestParam` 显式命名，依赖参数名保留（编译需 `-parameters`）。关键词被逐字符拼进 SQL 字符串（`G:380`），因拆分粒度为单字符**无法构造多字符注入**，但含 `'` 会使 SQL 语法错误；⚠️ **后果不止于此**：`_` / `%` 未转义 ⇒ `'%_%'` 命中任何非空 `GoodName` ⇒ **关键词含下划线或百分号时返回全表**（2026-09-30 实测），见 `defect-survey.md` 病 B 同条。

## 4. 待确认

- ~~前端实际调用的接口子集与参数拼装方式 —— 前端源码未读。~~ **✅ 已关闭（2026-09-30）**：前端源码已读，矩阵见 **§5**。
- ~~`ImgPath.java` / `ImgConfig.java` 是否额外暴露静态资源路由（影响 `http://localhost:8088/img/` 前缀的可用性）~~ **已关闭**：`ImgPath`（`@Configuration implements WebMvcConfigurer`）注册了 `/img/**` → `app.upload-dir` 的资源映射，故 `http://localhost:8088/img/<文件名>` 前缀可用；`ImgConfig` 只注册全局 `CorsFilter`，不涉及静态资源路由。证据：`ImgPath.java:15-16`、`:30-35`；`ImgConfig.java:9-12`。实证：A2 的 `ImgMappingSmokeTest` 已断言 `GET /img/default_user.png` → 200。

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
