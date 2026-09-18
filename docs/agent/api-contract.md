# 接口契约 — Mall-management

> **证据来源**：3 个 Controller 全文。缩写：`U`=`MallManagementUserController.java`、`M`=`MallManagementMessage.java`、`G`=`MallManagementGoodsController.java`（数字为源码行号）。
> **「幂等」判定标准**：*相同入参重复调用是否产生额外副作用（不重复写入）*。非 HTTP 语义幂等。
> 所有接口均无鉴权、无统一返回体、无全局异常处理；3 个 Controller 均标 `@CrossOrigin`。入参均为 `@RequestParam`（query/form），无 `@RequestBody`。

## 1. 通用约定

- 全部路径无版本号、无 RESTful 语义，除 `/mysqlGoods/goodstype` 外全部为 `POST`。
- 失败返回不统一：`loginSelect` 返回 `0`；`getUserInfo` 返回 `null`（HTTP 200 空体）；`/mysqlGoods` 系列返回 `{success:false, msg}`。
- 无分页参数，列表接口全量返回。

## 2. 接口清单（共 32 个）

### /mysqlLogin — 5 个

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/mysqlLogin/loginSelect` | username, password | `int`：1=客户 2=商家 0=失败 | 是 | `U:24` |
| POST | `/mysqlLogin/loginAdd` | image(默认 default_user.png), username, usertype, userid, customerid(默认"暂无数据"), merchantid(默认"暂无数据"), password, age, gender | `String`：`"0"`=已存在 / `"添加成功！"` | 是 ⚠️ | `U:46` |
| POST | `/mysqlLogin/getUserInfo` | username, password | `user` 全字段 + `image`；失败返回 `null` | 是 | `U:105` |
| POST | `/mysqlLogin/updateAvatar` | username, file(MultipartFile) | `{success, msg?, imageUrl?}` | 是 | `U:157` |
| POST | `/mysqlLogin/updateUserInfo` | username, email, address, selfintroduction | `{success, msg}` | 是 | `U:222` |

### /message — 10 个

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| POST | `/message/conversations` | currentId, currentType | `{success, list[{other_id,other_type,last_time,unread}]}` | 是 | `M:21` |
| POST | `/message/history` | currentId, currentType, otherId, otherType | `{success, list[...]}` | **否** | `M:73` |
| POST | `/message/send` | fromId, fromType, toId, toType, content | `{success, msg}` | **否** | `M:96` |
| POST | `/message/getOrCreateConversation` | currentId, currentType, otherId, otherType | `{success, otherUser, messages}` | 是 | `M:121` |
| POST | `/message/userSelect` | userId | `{success, user{id,username,usertype,gender,image:null}}` | 是 | `M:158` |
| POST | `/message/friendAdd` | customerId, friendId | `{success, msg}` | 是 ⚠️ | `M:195` |
| POST | `/message/selectFriend` | friendId | `{success, list}` | 是 | `M:235` |
| POST | `/message/friendUp` | customerId, friendId | `{success, msg}` | 是 | `M:259` |
| POST | `/message/friendDe` | customerId, friendId | `{success, msg}` | 是 | `M:283` |
| POST | `/message/friendSelect` | customerId | `{success, list}` | 是 | `M:305` |

### /mysqlGoods — 17 个

| 方法 | 路径 | 入参 | 返回 | 幂等 | 证据 |
|---|---|---|---|---|---|
| GET\|POST | `/mysqlGoods/goodstype` | GET 无参；POST: GoodType, GoodTypeNew | GET `{success,list}`；POST `{success,msg}` | GET 是 / POST 是 ⚠️ | `G:24` |
| POST | `/mysqlGoods/goodsAdd` | MerchantId, GoodName, Price, file(MultipartFile), GoodType, ProductCode, GoodTypeNew | `{success, msg, GoodImg}` | **否** | `G:67` |
| POST | `/mysqlGoods/goodsSelect` | MerchantId | `{success, list}` | 是 | `G:154` |
| POST | `/mysqlGoods/goodsSelectHome` | 无 | `{success, list}`（每行附 `UserName`） | 是 | `G:173` |
| POST | `/mysqlGoods/goodsDe` | MerchantId, GoodName, ProductCode | `{success, msg}` | 是 | `G:215` |
| POST | `/mysqlGoods/productCodePrice` | MerchantId, GoodName, ProductCode | `{success, price, goodImg}` | 是 | `G:245` |
| POST | `/mysqlGoods/goodsUp` | MerchantId, GoodName, Price, GoodType, ProductCode, file(可选) | `{success, msg}` | 是 | `G:269` |
| POST | `/mysqlGoods/GoodsProductCodeSelect` | MerchantId, GoodName | `{success, list[String]}` | 是 | `G:327` |
| POST | `/mysqlGoods/productSearch` | keyword（**未用 `@RequestParam` 显式命名**） | `{success, list}` | 是 | `G:350` |
| POST | `/mysqlGoods/orderAdd` | CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf | `{success, msg}` | **否** | `G:384` |
| POST | `/mysqlGoods/orderSelect` | customerId | `{success, list}` | 是 | `G:428` |
| POST | `/mysqlGoods/orderDeliveryIfSelect` | merchantId | `{success, list}` | 是 | `G:448` |
| POST | `/mysqlGoods/orderDeliverySelect` | merchantId, status(默认 `"active"`) | `{success, list}` | 是 | `G:466` |
| POST | `/mysqlGoods/historicalOrdersSelect` | customerId | `{success, list}` | 是 | `G:494` |
| POST | `/mysqlGoods/orderPayIfUp` | orderId | `{success, msg}` | **是（CAS）** | `G:521` |
| POST | `/mysqlGoods/orderReceiveIfUp` | orderId | `{success, msg}` | **是（CAS）** | `G:543` |
| POST | `/mysqlGoods/orderDeliveryIfUp` | orderId | `{success, msg}` | **是（CAS）** | `G:565` |

## 3. 逐条说明

- ⚠️ `U:46 loginAdd`：靠「先 SELECT 查重再 INSERT」实现重复拦截（`U:61`→`U:81`），存在 TOCTOU 竞态（见 `concurrency-map.md`）。第二参数 `customerid`/`merchantid` 会被 `userid` 覆盖（`U:69`、`U:86`）。
- ⚠️ `M:195 friendAdd`：同为「先查后插」（`M:205`→`M:219`），存在 TOCTOU 竞态。
- ⚠️ `G:24 goodstype` POST：同为「先查后插」（`G:42`→`G:51`）。返回 `{success:false,msg:"类型已存在"}` 而非 `"0"`，与 `loginAdd` 风格不一致。
- `M:73 history` 非幂等：除查询外还会 upsert `user_conversation_read.last_read_time = NOW()`（`M:85-88`），每次调用都刷新时间戳。
- `M:96 send` 非幂等：每次调用插入一条新 `message`（`M:103`）。
- `G:384 orderAdd` 非幂等：每次调用插入一条新 `order`；`OrderId` 由调用方提供且**无唯一约束**，可重复下单号。
- `G:67 goodsAdd` / `G:215 goodsDe` / `G:269 goodsUp`：均同时写 `goods` 与 `productsales` 两张表，**无事务保护**（见 `concurrency-map.md`）。
- `G:521/543/565` 三个订单状态接口是全项目唯一带并发防护的写接口，详见 `concurrency-map.md` 正面案例。
- `G:350 productSearch`：`keyword` 未加 `@RequestParam` 显式命名，依赖参数名保留（编译需 `-parameters`）。关键词被逐字符拼进 SQL 字符串（`G:361`），因拆分粒度为单字符**无法构造多字符注入**，但含 `'` 会使 SQL 语法错误。

## 4. 待确认

- 前端实际调用的接口子集与参数拼装方式 —— 前端源码未读。
- ~~`ImgPath.java` / `ImgConfig.java` 是否额外暴露静态资源路由（影响 `http://localhost:8088/img/` 前缀的可用性）~~ **已关闭**：`ImgPath`（`@Configuration implements WebMvcConfigurer`）注册了 `/img/**` → `app.upload-dir` 的资源映射，故 `http://localhost:8088/img/<文件名>` 前缀可用；`ImgConfig` 只注册全局 `CorsFilter`，不涉及静态资源路由。证据：`ImgPath.java:15-16`、`:30-35`；`ImgConfig.java:9-12`。实证：A2 的 `ImgMappingSmokeTest` 已断言 `GET /img/default_user.png` → 200。
