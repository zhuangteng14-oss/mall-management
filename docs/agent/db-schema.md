# 数据库结构 — mall_management_sql

> **证据来源**：`mall_management_sql.sql` 全部 CREATE TABLE 段（行号为该文件行号）、`application.properties`、`pom.xml`。**`.sql` 是唯一权威源，本文件只做记录，不反向约束。**
> **行号约定**：本文件全部 `:N` 均为 `.sql` 的**文件真实行号（LF 计数）**，不是任何工具归一化行尾后的行号。（该文件行尾为 CRLF，`tr '\r' '\n'` 之类的读取会把行号翻倍 —— 已实证对撞：真实 `146` 与归一化 `292` 同为 `` `Price` float NOT NULL,``。）
> **Java 坐标约定（2026-09-30 补）**：`G:` = `GoodsController.java`（前 `MallManagementGoodsController.java`）、`M:` = `MessageController.java`（前 `MallManagementMessage.java`）、`U:` = `UserController.java`（前 `MallManagementUserController.java`）的**语句行**（**不是** route 注解行，与 `api-contract.md` 的口径**相反**，两文件不要互抄）。B1 / B2 / B3 之后若干读写路径已搬到 `mallmanagement_service` 下的 Service ⇒ 本文件出现 `GoodsCacheService:` / `UserCacheService:` / `OrderStateMachine:` 等前缀，那是**跨文件搬迁**、不是行号漂移。**全文件 Java 坐标已于 2026-09-30 对 HEAD 逐条复核并替换** —— 原一版系统性偏旧（成因同 `api-contract.md:4`：各笔新代码插入后未同步）。
> ⚠️ **2026-10-02 笔 2 后**：订单端点的 SQL 已随 `OrderController` 拆出，迁至 `order/dao/OrderDao.java`；`GoodsController` 的商品行整体 **−9**（⚠️ 该「−9」只是**首两行**的口径：以 2026-09-30 基线 `148e77b` 逐条实测，`goodstype`/`goodsAdd` 为 −9、`goodsSelect` 一族 −8、`productCodePrice` −4、`goodsUp` 之后 +16/+23/+23，**不要拿 −9 换算全表**；详见 `api-contract.md:6`）。下表**订单相关行的 `G:` 已改指 `OrderDao`**，其余 `G:` 值**尚未重测** —— 引用前请以源码为准。
> ⚠️ **2026-10-02 三层重构第二笔（商品域）后**：商品 **8 条内联 SQL** 从 `GoodsController` 下沉到 `goods/dao/GoodsDao.java`，写/读方法体进 `goods/service/GoodsService.java` —— 本表原 `G:` 的**商品行**据此改指 `GoodsDao.searchByKeyword`。这四个走缓存的读路径（`GoodsCacheService:81` / `:146` / `priceByTriple`）**未受影响**（它们的 SQL 本就在 Service，2026-09-30 已复核）。新增前缀：`S:` = `GoodsService.java`、`D:` = `GoodsDao.java`。
> 表数量：**12** —— 11 张原始表 + `order_state_transition`（B3 于 2026-09-29 追加在 `.sql` 末尾的 Migration 块，`:221-235`）。
> `.sql` 末尾另有一段 S1a 追加的 Migration 块（`:236-245`，2026-09-30），**只含一条 `CREATE UNIQUE INDEX`，不建表** —— 故表数仍为 12。
> 全部为 `ENGINE=InnoDB`、`utf8mb4_0900_ai_ci`。
>
> ⚠️ 该 `.sql` 行尾是 CRLF（245 个 `\n` / 246 个 `\r`，含 1 个裸 CR —— 2026-09-30 S1a 追加后**重新实测**，原为 235 / 236）。用会把行尾归一化的编辑器改它，diff 会变成整份文件重写 —— 改前先确认 diff 只有预期行。
>
> **完整字段清单 / 索引基线 / 外键关系见 §6–§9**（2026-09-30 补齐，逐列逐索引以 `.sql` 为准）。

## 1. 表清单

| 表名 | 用途 | 主键 | 关键字段 | 并发相关字段 | 索引 | 证据 |
|---|---|---|---|---|---|---|
| `customerimage` | 客户头像 | `UserName` | CustomerId, image | — | 仅主键 | `:23-28` |
| `friend` | 好友关系（含待处理申请） | `id` (自增) | customerid, friendid, if_friend | **`if_friend` 状态位**：0=待处理 1=已通过 | 仅主键 | `:38-44` |
| `goods` | 商品（按型号一行） | `Id` (自增) | MerchantId, GoodName, Price, GoodImg, GoodType, ProductCode, GoodTypeNew | — | 仅主键 | `:54-64` |
| `goodstype` | 商品类型字典 | `Id` (自增) | GoodTypeNew, GoodType | — | 仅主键 | `:74-79` |
| `login` | 登录凭证与角色 | `UserName` | Password, **Role**(1=客户 2=商家), Id | — | 仅主键 | `:89-95` |
| `merchantimage` | 商家头像 | `UserName` | MerchantId, image | — | 仅主键 | `:105-110` |
| `message` | 聊天消息 | `id` (自增) | from_id, from_type, to_id, to_type, content, create_time | — | 主键 + **`idx_from_to(from_id,to_id)`** + **`idx_create_time(create_time)`** | `:120-131` |
| `order` | 订单 | `Id` (自增) | **`OrderId`**(业务单号), MerchantId, CustomerId, Price, ProductInformation, Images, CreateTime, SettlementDate | **`PayIf`/`DeliveryIf`/`ReceiveIf` 三状态位**（CAS 更新）；`SettlementDate` 被三处操作覆写 | 主键 + **`uk_order_orderId(OrderId)`**（UNIQUE，S1a `:245`） | `:141-155` |
| `productsales` | 商品销量统计 | `Id` (自增) | ProductCode, MerchantId, GoodName | **`OrderVolume`：恒为 0，全项目从未更新** | 仅主键 | `:165-172` |
| `user` | 用户档案 | `username` | usertype, customerid, merchantid, gender, age, email, selfintroduction, address | — | 仅主键 | `:182-193` |
| `user_conversation_read` | 会话最后已读时间 | **复合** `(user_id, user_type, other_id, other_type)` | last_read_time | 复合主键天然支撑 `ON DUPLICATE KEY UPDATE` upsert | 主键即索引 | `:203-210` |
| `order_state_transition` | 订单状态流转时间线（B3 新增；C3 时间线视图的数据源） | `Id`（自增，**bigint**） | OrderId, Action, FromState, ToState, OccurredAt, created_at, updated_at | **append-only**；同单内排序用 `OccurredAt`，**不是 `Id`**（落库是 4 线程并发 INSERT，`Id` 在 INSERT 时才分配）；`updated_at` 恒等于 `created_at` | 主键 + **`idx_order_state_transition_order(OrderId, OccurredAt)`** | `:224-235` |

种子数据交叉证据：`:176` 中 5 条 `productsales` 的 `OrderVolume` 全为 `0`，而 `:159` 的 `order` 表已有 6 条历史订单。

## 2. 索引现状

**12 张表中 3 张有二级索引（共 4 个）**：`message` 2 个（`idx_from_to`、`idx_create_time`）、`order_state_transition` 1 个（`idx_order_state_transition_order`）、**`order` 1 个（`uk_order_orderId`，UNIQUE）**。其余 **9 张表除主键外无任何索引**；全库**除主键外 1 个 UNIQUE**（`uk_order_orderId`，2026-09-30 S1a `:236-245` 新增；完整基线见 §7）。已知高频查询列缺索引：

| 查询位置 | SQL 条件 | 涉及表 | 现况 |
|---|---|---|---|
| `GoodsCacheService:81` | `login WHERE id = ?` | `login` | `Id` 无索引；`goodsSelectHome` 中对每个商品执行一次（读路径 B1 已搬到该 Service；原记 `G:188`） |
| `GoodsCacheService:146` | `goods WHERE MerchantId = ?` | `goods` | `MerchantId` 无索引（读路径 B1 已搬到该 Service；原记 `G:160`） |
| `OrderDao:117` | `order WHERE CustomerId = ?` | `order` | `CustomerId` 无索引（原记 `G:507`，再前 `G:434`；2026-10-02 随订单端点迁至 `OrderDao`） |
| `OrderDao:124` | `order WHERE MerchantId = ?` | `order` | `MerchantId` 无索引（原记 `G:525`，再前 `G:452`；同上） |
| `UserCacheService:81` | `user WHERE customerid = ? OR merchantid = ?` | `user` | 两列均无索引；且 `OR` 无法走单列索引（`userSelect` 读路径已搬到该 Service；原记 `M:331`）。⚠️ HEAD 中**同形 SQL 共 3 处**：另有 `FriendCacheService:151`（`friendList` 的逐行 enrich）与 `M:378`（`MessageController.getUserInfoById()`，私有辅助） |
| `orderPayIfUp` / `orderDeliveryIfUp` / `orderReceiveIfUp` + `OrderStateMachine.selectBitsSql()` | `order WHERE OrderId = ?` | `order` | ✅ **已消除（2026-09-30 S1a，`1e2fcfa`）** —— `uk_order_orderId` 一次解决索引与唯一性两面。**原状**：`OrderId` 无索引，且无 UNIQUE → 三个 CAS 端点与转移后读回每次都全表扫描。已登记 **TD-14**（`tech-debt.md:172`，含 2026-09-21 定性修正：本地 120 QPS 不可达属环境属性，索引代价须**同环境**对照举证）。**曾是** B2 fail-open + B3 并发热点的根：唯一性无约束、索引无兜底，一旦锁回落直行（`tech-debt.md:280` 三路 fail-open），就只剩应用层「先查后写」在守。⚠️ 唯一性现已由 DB 兜底，但**失败响应仍被 `orderAddLocked` 的 catch 吞成 200 + msg**（故 `A-S2a-1c` 数的是 msg 而非 5xx） |
| `GoodsCacheService.priceByTriple` 回源 | `goods WHERE MerchantId = ? AND GoodName = ? AND ProductCode = ?` | `goods` | 三元组无索引（B1 缓存回源路径，命中缺失即全表扫） |
| `GoodsDao.searchByKeyword`（`:193-207`；2026-10-02 由 `GoodsController.productSearch` 搬入） | `goods WHERE GoodType = ? AND (… OR GoodName LIKE '%x%')` | `goods` | `GoodType` 无索引；且 `LIKE '%x%'` 本身也无法走索引。⚠️ 该 SQL 还**无 schema 前缀**（`GoodsDao` 8 条里唯一一条），但这只影响可移植性，不改变「走不了索引」的结论 |
| 销量维护 | `productsales WHERE ProductCode = ? AND MerchantId = ?` | `productsales` | 无索引；亦无 `(ProductCode, MerchantId)` UNIQUE → 可写重复行 |
| 头像读取 | `customerimage WHERE CustomerId = ?` / `merchantimage WHERE MerchantId = ?` | `customerimage` / `merchantimage` | 两列均无索引 |
| 好友列表 | `friend WHERE customerid = ? OR friendid = ?` | `friend` | 两列均无索引；且 `OR` 无法走单列索引 |

另：`login.Password` 为明文存储（`application.properties` 中无加密配置，Controller 中无哈希逻辑）。

## 3. ⚠️ ddl-auto 与手写 DDL 的冲突

| 项 | 内容 | 证据 |
|---|---|---|
| 配置 | `spring.jpa.hibernate.ddl-auto=update` | `application.properties:12` |
| 依赖 | `spring-boot-starter-data-jpa` 已引入 | `pom.xml:51` |
| 手写 DDL | `mall_management_sql.sql`，**12** 张表完整 CREATE TABLE（11 原始 + B3 追加 1） | 全文件 |

**当前实际生效的是手写 `mall_management_sql.sql`。**
原因：全项目**不存在任何 `@Entity`**——既有的唯一一个 POJO 曾是 `mallmanagement_class/info.java`（仅 `@Data`/`@AllArgsConstructor`/`@NoArgsConstructor` 三个 lombok 注解、无 JPA 注解、0 引用），**该类已于 2026-10-02 笔 1 挪包时删除**（`01df702`）。Hibernate 无实体可映射，`ddl-auto=update` 当前是**空转**，不会修改任何表结构。⚠️ **结论不变但依据换了**：从「有一个无注解的实体类」变成「**一个实体类都没有**」——条文原写「info.java 是唯一实体类」，笔 1 之后该句本身已不成立（详见 `project-map.md` 与 `tech-debt.md` TD-01）。

**这是隐患而非当前故障**，风险在于：

1. **表结构漂移**：一旦有人给某个类加上 `@Entity`，Hibernate 启动时会按实体定义反向 `ALTER TABLE`。`update` 模式**只增不减**——会擅自加列但不删列，静默产生与 `mall_management_sql.sql` 不一致的表结构，且无任何报错。
2. **命名策略冲突**：本表结构用 `UserName`/`MerchantId` 等大驼峰列名，与 Hibernate 默认的 snake_case 命名策略不一致，一旦映射实体会产生大量非预期的列名。
3. **脚本失去权威性**：两份定义并存时，无法判断线上库结构以哪份为准。

**建议**：改为 `spring.jpa.hibernate.ddl-auto=validate`（或 `none`），由 `mall_management_sql.sql` 统一管理表结构。

> 关于 `spring-boot-starter-data-jpa` 依赖本身的去留，属技术债而非表结构事实，已移入 `tech-debt.md`（TD-01）。

## 4. `order.SettlementDate` 语义 —— **待业务确认**

> ⚠️ **该列变更会影响 `historicalOrdersSelect` 的排序结果，修改前必须回归验证历史订单页面。**

> 本节只记录事实与可选方案，**不代为裁决**。语义归属是业务决定。业务侧决策单见 `business-decisions.md`（BD-01）。

### 4.1 三处写入位置

| 操作 | 写入语句 | 证据 |
|---|---|---|
| 付款 | `SET PayIf = 1, SettlementDate = NOW()` | `OrderStateMachine:113` |
| 收货 | `SET ReceiveIf = 1, SettlementDate = NOW()` | `OrderStateMachine:115` |
| 发货 | `SET DeliveryIf = 1, SettlementDate = NOW()` | `OrderStateMachine:117` |

> **2026-09-30 坐标更正**：这三行原先硬编码在 `MallManagementGoodsController`（旧坐标 `G:524` / `G:546` / `G:568`）。B2 起三处 UPDATE 改由 `OrderStateMachine.updateSql()` **生成**（`SET` 子句来自 `setClause()`，`OrderStateMachine:125-127`；`setClause` 本体在 `:130-133`），上表行号即 `:113-118` javadoc 里逐条写明的三个字面量。⚠️ 该 javadoc 自己还记着**更早一版**的坐标（`原 G:556` / `G:584` / `G:612`）—— 两套旧坐标都不要再用。

### 4.2 覆盖行为

三个操作**写的是同一列**，后发生者覆盖先发生者。正常流转（付款→发货→收货）下该列最终值 = **收货时间**；只付款未发货时 = **付款时间**。因此该列**无法稳定表示任何一个业务时间点**，取值取决于订单停在哪个状态。

另：该列在 `orderSelect`、`orderDeliveryIfSelect`、`orderDeliverySelect` 中**均未被查询**；仅 `historicalOrdersSelect`（路由 ~~`G:567`~~ → **`OrderController:88`**，2026-10-02 笔 2）取出用于历史订单展示（SQL ~~`G:575`~~ → **`OrderDao.selectHistoryByCustomer:148-152`**），并按 `SettlementDate DESC` 排序（~~`G:578`~~ → **`OrderDao:152`**）。

### 4.3 三种可选方案（**待业务确认**）

| 方案 | 做法 | 代价 |
|---|---|---|
| **A 不改** | 维持现状，将语义解释为「最后状态变更时间」 | 零改动；但列名与语义不符，历史订单排序含义模糊 |
| **B 只付款写** | 三处中仅付款保留赋值，发货/收货不再写该列 | 改动小；若业务需要发货/收货时间，仍需另加字段 |
| **C 拆三字段** | 拆为 `PayTime` / `DeliveryTime` / `ReceiveTime` 三列 | 语义最清晰；需改 DDL + 三处 UPDATE + 相关查询，改动最大 |

**决策依据应由业务方给出**：该列当前实际承担的是「历史订单展示与排序」（**`OrderDao:149`（SELECT 列）、`:152`（ORDER BY）**；原记 `G:575`、`G:578`），需先确认业务上到底要展示哪个时间点。

## 5. 待确认（其他）

- 全库无 CHECK 约束、无外键约束（`mall_management_sql.sql` 导入时关闭 `FOREIGN_KEY_CHECKS`，且 DDL 未定义外键）—— 引用完整性完全由应用层维护。

---

## 6. 完整字段清单（12 张表，逐列以 `.sql` 为准）

> 行号 = `mall_management_sql.sql` 真实行号。`约束` 列照抄 DDL；`备注` 列出 COMMENT（本库仅 `order_state_transition` 有列注释）与已核实的语义。
> 列级 `CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci` 在部分列上显式声明（下表标「显式」）、部分列省略 —— 因表默认值相同，**功能等价**，属声明噪音。

### 6.0 表级属性与种子数据基线

| 表 | ENGINE | CHARSET | COLLATE | `AUTO_INCREMENT` | 种子行数 | P4 判据 |
|---|---|---|---|---|---|---|
| `customerimage` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 2 | — |
| `friend` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 6 | 4 | ✅ 期望 4 |
| `goods` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 26 | 5 | ✅ 期望 5 |
| `goodstype` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 12 | 5 | — |
| `login` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 5 | ✅ 期望 5 |
| `merchantimage` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 3 | — |
| `message` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 36 | 29 | — |
| `order` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 24 | 6 | ✅ 期望 6 |
| `productsales` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | 28 | 5 | — |
| `user` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 5 | ✅ 期望 5 |
| `user_conversation_read` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 4 | — |
| `order_state_transition` | InnoDB | utf8mb4 | utf8mb4_0900_ai_ci | — | 0 | — |

> **12 张表的 ENGINE / CHARSET / COLLATE 完全一致**，且逐字等于库级默认值（`:15` `CREATE DATABASE ... DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`）⇒ **没有任何表声明自己的字符集**，全套吃库级默认。
> `AUTO_INCREMENT` = DDL 中的现值（导入基线）。
> **种子行数即 A3 的 P4 判据基线**：`ConcurrencyRunner.java:2421-2444`（2026-09-30 修正：原写 `:2404-2427`，因同日那笔 C 组升门禁的注释重写使本段整体下移 17 行）只查 `order/goods/login/user/friend` 五张，期望 `6/5/5/5/4`，与本表**逐字一致**（其注释即写「`mall_management_sql.sql` 逐行计数」）。P4 的用途是「确认是刚导入过的干净实例，而非被上一轮污染过的库」⇒ 种子行数是**门禁输入而非装饰**，改种子数据会先让 P4 变红。

### 6.1 `customerimage`（`:23-28`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `UserName` | char(20) 显式 | **PK**, NOT NULL | 应用层用户名 |
| `CustomerId` | char(20) 显式 | NOT NULL | 客户业务号 |
| `image` | varchar(500) 显式 | NOT NULL | 头像 URL（含硬编码 `http://localhost:8088/img/` 前缀） |

### 6.2 `friend`（`:38-44`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=6` |
| `customerid` | varchar(50) | NOT NULL | 发起方业务号（**宽度与 `user.customerid` 的 char(20) 不同**） |
| `friendid` | varchar(50) | NOT NULL | 对方业务号 |
| `if_friend` | int | NOT NULL | 0=待处理 1=已通过。**语义只写在本文件，DDL 无 CHECK/默认值** |

### 6.3 `goods`（`:54-64`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=26` |
| `MerchantId` | char(20) | NOT NULL | |
| `GoodName` | char(40) | NOT NULL | 比 `productsales.GoodName` 宽一倍 |
| `Price` | **double** | NOT NULL | 与 `order.Price` 的 float 不同型 |
| `GoodImg` | char(100) 显式 | NOT NULL | |
| `GoodType` | char(20) | NULL, DEFAULT NULL | 类型名（文本，非外键） |
| `ProductCode` | char(40) | NULL, DEFAULT NULL | |
| `GoodTypeNew` | int | NULL, DEFAULT NULL | 类型 ID；**与 `goodstype.GoodTypeNew` 的 `NOT NULL DEFAULT '0'` 不一致** |

### 6.4 `goodstype`（`:74-79`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=12` |
| `GoodTypeNew` | int | **NOT NULL DEFAULT '0'** | 与 `goods.GoodTypeNew` 可空不同 |
| `GoodType` | char(20) | NOT NULL | **无唯一约束 → 类型名可重复** |

### 6.5 `login`（`:89-95`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `UserName` | char(20) 显式 | **PK**, NOT NULL | 应用层账号 |
| `Password` | char(20) 显式 | NOT NULL | **明文**（种子 5 条见 `:99`） |
| `Role` | int | NOT NULL | 1=客户 2=商家 |
| `Id` | char(20) 显式 | NOT NULL | 客户/商家业务号。**无唯一约束** → 同一业务号可对多条账号 |

> 本表**无自增列**。`login.Id` 与 `user.customerid` / `user.merchantid` 的对应关系**由应用层按 `Role` 择一**，DB 无约束（见 §8）。

### 6.6 `merchantimage`（`:105-110`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `UserName` | char(20) 显式 | **PK**, NOT NULL | |
| `MerchantId` | char(20) 显式 | NOT NULL | |
| `image` | varchar(500) 显式 | NOT NULL | |

### 6.7 `message`（`:120-131`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=36` |
| `from_id` | varchar(50) | NOT NULL | |
| `from_type` | varchar(10) | NOT NULL | merchant / customer |
| `to_id` | varchar(50) | NOT NULL | |
| `to_type` | varchar(10) | NOT NULL | |
| `content` | text | NOT NULL | **无长度约束** |
| `create_time` | datetime | NOT NULL | 与 `user_conversation_read.last_read_time` 同型，可直接比较 |

### 6.8 `order`（`:141-155`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=24`（内部主键，业务不使用） |
| `MerchantId` | **varchar(40)** | NOT NULL | 比 `goods.MerchantId` 的 char(20) 宽 |
| `CustomerId` | **varchar(40)** | NOT NULL | 比 `user.customerid` 的 char(20) 宽 |
| `OrderId` | varchar(40) | NOT NULL | **业务单号**。原为「既无唯一约束也无索引」（前提 A 的出处）—— **2026-09-30 S1a 已加 UNIQUE `uk_order_orderId`**（`:245`），前提 A 的 DB 面由此消解。与 `order_state_transition.OrderId` 逐字同型 |
| `Price` | **float** | NOT NULL | 与 `goods.Price` 的 double 不同型 |
| `ProductInformation` | varchar(500) | NOT NULL | 冗余快照文本（如 `222:IJ-555；222:IF-55`） |
| `Images` | varchar(100) | NOT NULL | |
| `PayIf` | int | NOT NULL | CAS 状态位 0/1，**无 CHECK**（可被 `orderAdd` 传入任意 int） |
| `DeliveryIf` | int | NOT NULL | 同上 |
| `ReceiveIf` | int | NOT NULL | 同上 |
| `CreateTime` | **varchar(50)** | NOT NULL | 时间以字符串存储 |
| `SettlementDate` | **varchar(50)** | NULL, DEFAULT NULL | 被付款/发货/收货三处覆写（BD-01，见 §4） |

### 6.9 `productsales`（`:165-172`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | int | **PK**, NOT NULL AUTO_INCREMENT | `AUTO_INCREMENT=28` |
| `ProductCode` | char(40) | NOT NULL | 与 `goods.ProductCode` 同型 ✓ |
| `MerchantId` | char(20) | NOT NULL | 与 `goods.MerchantId` 同型 ✓ |
| `GoodName` | **char(20)** | NOT NULL | **比 `goods.GoodName` 的 char(40) 窄 20** → 21–40 字符名写此表报 **Error 1406 (Data too long)**，**不是静默截断**（本机 8.0.32 实测 `@@sql_mode` 含 `STRICT_TRANS_TABLES`；CI 用 `mysql:8.4` 未覆写 sql_mode，默认同样是严格）。⚠️ 但 `.sql` 导入会话是**例外**：`:13` 把整个 sql_mode 覆写为 `NO_AUTO_VALUE_ON_ZERO`（**非严格**），至 `:216` 才恢复 ⇒ 若种子数据本身超长会**静默截断**。**TD 候选，尚未登记** |
| `OrderVolume` | int | NULL, DEFAULT NULL | 种子数据恒为 0（`:176`），全项目从未 UPDATE |

### 6.10 `user`（`:182-193`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `username` | char(20) 显式 | **PK**, NOT NULL | |
| `usertype` | char(20) 显式 | NULL, DEFAULT NULL | 中文取值「客户」/「商家」（与 `login.Role` 的 1/2 是两套编码） |
| `customerid` | char(20) 显式 | NULL, DEFAULT NULL | |
| `merchantid` | char(20) 显式 | NULL, DEFAULT NULL | |
| `gender` | char(20) 显式 | NOT NULL | **无默认值** → INSERT 必须给 |
| `age` | int | NOT NULL | **无默认值** |
| `email` | char(40) | NULL, DEFAULT NULL | |
| `selfintroduction` | varchar(100) | NULL, DEFAULT NULL | |
| `address` | varchar(20) | NULL, DEFAULT NULL | **宽度 20 偏窄**（种子数据已达 20 字符） |

> 本表**无自增列**。`customerid` / `merchantid` **均无唯一约束**。

### 6.11 `user_conversation_read`（`:203-210`）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `user_id` | varchar(50) | **复合 PK(1/4)**, NOT NULL | |
| `user_type` | varchar(10) | **复合 PK(2/4)**, NOT NULL | |
| `other_id` | varchar(50) | **复合 PK(3/4)**, NOT NULL | |
| `other_type` | varchar(10) | **复合 PK(4/4)**, NOT NULL | |
| `last_read_time` | datetime | NOT NULL | |

> 全库**唯一**的复合主键。PK 即索引，无其他索引。**无自增列**。

### 6.12 `order_state_transition`（`:224-235`，B3 新增）
| 字段 | 类型 | 约束 | 备注 |
|---|---|---|---|
| `Id` | **bigint** | **PK**, NOT NULL AUTO_INCREMENT | COMMENT：不保证同单内与发生顺序一致（4 线程并发 INSERT，`Id` 在 INSERT 时才分配）——同单排序请用 `OccurredAt` |
| `OrderId` | varchar(40) | NOT NULL | COMMENT：对应 `order`.OrderId（**无 FK**，见 §8） |
| `Action` | varchar(16) | NOT NULL | COMMENT：动作 tag `create/pay/deliver/receive` |
| `FromState` | varchar(40) | NOT NULL | COMMENT：出生为 `NONE`；越界取值写 `UNCLASSIFIED` |
| `ToState` | varchar(40) | NOT NULL | COMMENT：转移后状态名（最长 `ILLEGAL_DELIVERED_RECEIVED_UNPAID`，33 字符） |
| `OccurredAt` | **datetime(3)** | NOT NULL | COMMENT：在订单锁临界区内取；与 `created_at` 之差即队列延迟 |
| `created_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP | COMMENT：行落库时刻 |
| `updated_at` | datetime | NOT NULL DEFAULT CURRENT_TIMESTAMP **ON UPDATE CURRENT_TIMESTAMP** | 本表 append-only，恒等于 `created_at`；为规则 8 保留 |

> 全库**唯一**带列 COMMENT 的表；也是唯一用 `datetime(3)`（毫秒精度）的列。

---

## 7. 索引完整基线

| 表 | 主键 | 二级索引 | 非主键 UNIQUE |
|---|---|---|---|
| `customerimage` | `UserName` | 无 | **无** |
| `friend` | `id` | 无 | **无** |
| `goods` | `Id` | 无 | **无** |
| `goodstype` | `Id` | 无 | **无** |
| `login` | `UserName` | 无 | **无**（`Id` 非唯一） |
| `merchantimage` | `UserName` | 无 | **无** |
| `message` | `id` | `idx_from_to(from_id,to_id)`、`idx_create_time(create_time)` | **无** |
| `order` | `Id` | **`uk_order_orderId(OrderId)`** | **有 1 个**（`OrderId`，UNIQUE，S1a） |
| `productsales` | `Id` | 无 | **无** |
| `user` | `username` | 无 | **无**（`customerid`/`merchantid` 非唯一） |
| `user_conversation_read` | 复合 4 列 | 无 | **无** |
| `order_state_transition` | `Id` | `idx_order_state_transition_order(OrderId, OccurredAt)` | **无** |

**汇总**：12 张表；主键 12 个（1 个复合）；二级索引 **4 个**（分布 3 张表）；**全库除主键外 1 个 UNIQUE 约束**（`order.uk_order_orderId`，2026-09-30 S1a）。
唯一约束只此 1 个，也不是遗漏 —— 它是 `defect-survey.md §二 前提清单`里 A/B/C 三条的出处，到 2026-09-30 为止只有**前提 A 的 DB 面**被认领（S1a），B / B′ / C 仍全靠应用层保证。（原文此处引「§4「前提清单」」，但本文件 §4 是 `SettlementDate` 语义，前提清单实际在 `defect-survey.md` —— 一并更正。）

---

## 8. 外键与引用完整性

**结论：全库 0 个 `FOREIGN KEY`、0 个 `CHECK`。** 导入脚本一开始就关掉检查（`:12` `FOREIGN_KEY_CHECKS=0`）并在末尾恢复（`:217`）；`SQL_MODE` 亦置为 `NO_AUTO_VALUE_ON_ZERO`（`:13`，末尾 `:216` 恢复）。引用完整性**完全由应用层维护**。

逻辑引用关系（DDL 中不存在，仅记录事实）：

| 子表.列 | 指向 | 说明 |
|---|---|---|
| `login.Id` | `user.customerid` **或** `user.merchantid` | **按 `Role` 择一**，一个 `char(20)` 列指向两个不同列 —— 无法用单个 FK 表达 |
| `goods.MerchantId` | `user.merchantid` / `login.Id` | |
| `order.MerchantId` | 同上 | 类型不同（varchar(40) vs char(20)），见 §9 |
| `order.CustomerId` | `user.customerid` / `login.Id` | 类型不同（varchar(40) vs char(20)） |
| `order_state_transition.OrderId` | `order.OrderId` | ⚠️ **未建 FK**（原文「当前无法加 FK」）—— 阻塞点已于 **2026-09-30 S1a（`1e2fcfa`）消失**：`order.OrderId` 现有 UNIQUE `uk_order_orderId`，同样满足「被引用列有索引」。**建得起来，但迄今未建**（规则 v2 把新增 FK 列为「有条件允许」）。详见下方 🔄 |
| `productsales.(ProductCode, MerchantId, GoodName)` | `goods` 三元组 | 冗余派生表 |
| `message.(from_id,to_id)` | 用户业务号 | 类型 varchar(50)，与 char(20) 不同 |
| `friend.(customerid,friendid)` | 用户业务号 | 同上 |
| `customerimage.CustomerId` | `user.customerid` | |
| `merchantimage.MerchantId` | `user.merchantid` | |

> ⚠️ **（S1a 之前）`order_state_transition.OrderId → order.OrderId` 这个 FK 建不起来 —— 不是「没建」，是「建不了」**（**现况见本块末尾 🔄**）：MySQL/InnoDB 要求**被引用列上有索引**（该列须为该索引的最左前缀列），而 `order.OrderId` **当时无任何索引**。故要建 FK 必须先给 `order.OrderId` 建索引。
>
> ✅ **本机实测（8.0.32，用完即 DROP 并复核删净）**：
> - 父列**无索引** → `ERROR 1822 (HY000): Failed to add the foreign key constraint. Missing index for constraint 'fk1' in the referenced table 'p'`
> - 父列**仅有一个非唯一索引** → **ACCEPTED**（且真生效：插孤儿行被拒 `ERROR 1452`）
>
> ⇒ 精确表述是「**要求有索引，不要求唯一索引或主键**」（InnoDB 甚至不强制被引用键唯一，这是它对 SQL 标准的一处偏离）。**注意**：因此这条 FK 的阻塞点**只有「无索引」这一个**，与 `OrderId` 不保证唯一是**两件事**（后者属前提 A，影响的是时间线能否指向不存在的订单，而非 FK 能否创建）。
>
> 🔄 **2026-09-30 更新（S1a）**：`uk_order_orderId` 落地后，阻塞点**已消失** —— 该 FK 现在**建得起来**（唯一索引同样满足「被引用列有索引」）。但**本笔未建 FK**：规则 v2 把新增 FK 列为「**有条件允许**」（四项前置），是否建按 `selection-status.md` 的触发条件。另：`order.OrderId` 现在唯一了，但**时间线 → 订单的引用完整性仍只能靠应用层** —— 唯一索引只管 `order` 自己那一侧，`order_state_transition` 上仍无 FK，其中仍可存在指向不存在订单的 `OrderId`。

---

## 9. 跨表类型 / 宽度不一致基线

同一业务语义在多张表用不同类型/宽度 —— 无 FK 兜底，join 与比较语义随之漂移：

| 语义 | 各处类型 | 是否一致 |
|---|---|---|
| 商家业务号 | `goods.MerchantId` char(20)、`productsales.MerchantId` char(20)、`merchantimage.MerchantId` char(20)、`user.merchantid` char(20)、`login.Id` char(20) | ✅ 一致 |
| ↳ 同上 | **`order.MerchantId` varchar(40)** | ❌ 宽一倍且定长/变长不同 |
| 客户业务号 | `customerimage.CustomerId` char(20)、`user.customerid` char(20) | ✅ 一致 |
| ↳ 同上 | **`order.CustomerId` varchar(40)** | ❌ |
| ↳ 用户业务号（消息/好友） | `friend.customerid`/`friendid` varchar(50)、`message.from_id`/`to_id` varchar(50)、`user_conversation_read.user_id`/`other_id` varchar(50) | ✅ 内部一致 |
| ↳ 与上面两组 | char(20) vs varchar(40) vs varchar(50) | ❌ 三种宽度 |
| 用户账号 | `login.UserName` char(20)、`user.username` char(20) | ✅ 一致 |
| 类型名 | `goodstype.GoodType` char(20)、`goods.GoodType` char(20) | ✅ |
| 类型 ID | `goodstype.GoodTypeNew` **NOT NULL DEFAULT '0'**、`goods.GoodTypeNew` **NULL DEFAULT NULL** | ❌ 可空性不同 |
| 商品名 | `goods.GoodName` char(40) | — |
| ↳ 同上 | **`productsales.GoodName` char(20)** | ❌ **窄 20 → 21–40 字符名报 Error 1406**（严格模式，已实测；非静默截断） |
| 商品型号 | `goods.ProductCode` char(40)、`productsales.ProductCode` char(40) | ✅ |
| 价格 | `goods.Price` **double** | — |
| ↳ 同上 | **`order.Price` float** | ❌ 精度不同 |
| 会话类型 | `message.from_type`/`to_type` varchar(10)、`user_conversation_read.user_type`/`other_type` varchar(10) | ✅ |
| 交易标识 | `order.OrderId` varchar(40)、`order_state_transition.OrderId` varchar(40) | ✅（B3 刻意对齐） |

> `char`（定长，比较时按补空格规整）与 `varchar`（变长）在 `utf8mb4_0900_ai_ci` 下比较行为不同。跨表按业务号 join 时，类型不一致会带来尾空格与大小写语义差异，**且无 FK 能在写入时拦下不匹配**。

---

## 10. ⚠️ 全库除主键外只有 **1 个 UNIQUE** —— 唯一性仍基本靠应用层

> 本节单独成节，因为它是整份 schema 最重的一条：**业务主键几乎全是约定，不是约束。**（2026-09-30 S1a 之前是「全是」；`order.OrderId` 是第一个例外。）

### 10.1 事实
12 张表 → 12 个主键（其中 1 个复合）→ 4 个二级索引 → **非主键 UNIQUE：1 个**（`order.uk_order_orderId`，2026-09-30 S1a 新增）。

### 10.2 本应是业务键、实际只是「约定」的列

| 业务键 | 位置 | 现状 | 后果 | 登记 / 前提 |
|---|---|---|---|---|
| `order.OrderId` | `order` | ✅ **有 UNIQUE**（`uk_order_orderId`，2026-09-30 S1a） | 重复单号写不进去（`A-S2a-1b`/`1c` 实测）；但 `order_state_transition.OrderId` **仍可**指向不存在的订单（无 FK，见 §8） | **TD-14**（已修）；前提 A（DB 面已消解） |
| `goods (MerchantId, GoodName, ProductCode)` | `goods` | 无 | 三元组重复 → `queryForMap` 多行即抛 → **失败被当值缓存满 TTL** | 前提 B（B1 `priceByTriple`） |
| `goods (MerchantId, ProductCode)` | `goods` | 无 | 与三元组并存且**互相冲突** | 前提 B′ |
| `login.Id` | `login` | 无 | 同一业务号可对多条账号 | 前提 C |
| `user.customerid` / `user.merchantid` | `user` | 无 | 同上；且 `login.Id` 按 `Role` **择一**指向它们 | 前提 A 同族 |
| `friend (customerid, friendid)` | `friend` | 无 | 重复好友对可写入 → `friendList::<cid>` 与 `friendRequests::<fid>` 两个缓存都会重复列出 | **新前提候选**（原普查清单未列，见下注） |
| `productsales (ProductCode, MerchantId)` | `productsales` | 无 | 重复销量行 | — |
| `goodstype.GoodType` | `goodstype` | 无 | 类型名可重复 | — |
| `login.UserName` | `login` | **PK ✓** | 两个主键级唯一性之一（另一个是 `user.username`）；`order.OrderId` 于 2026-09-30 成为**第三个**真正被约束的唯一性（唯一索引，非主键） | — |

> ⚠️ **编号提醒**：本次收到「`friend` 无唯一约束 → 前提 B′」，但普查报告里 **B′ 是 `goods (MerchantId, ProductCode)`**；`friend` 这条在原清单中**没有**，属**新前提**。上表按「新前提候选」记，**不并入 B′**，以免串号 —— 若你的 B′ 另有所指请更正。

### 10.3 这解释了 B1/B2/B3 为何都围绕「查重 + 锁」打转

唯一性既然不是**约束**，就必须在应用层用「先查后写 + 分布式锁」模拟出来 —— 这正是三笔工作的共同重心，且三笔各补一角：

| 笔 | 对策 | 覆盖到的 | 覆盖不到的 |
|---|---|---|---|
| B1 | **查重 + 缓存**（`priceByTriple` 先查后缓存） | 命中唯一时 | 唯一性未约束 ⇒ 查重会命中多行并抛，而**失败又被当值缓存**（前提 B 的传导链） |
| B2 | **分布式锁**（`order-add`/`order-pay`/`order-receive`/`order-delivery`/`friend-add` 五落点） | 锁可用时 | 锁是**三路 fail-open**（`tech-debt.md:280`）⇒ 锁不可用即回落到无锁原路径 |
| B3 | **谓词守卫（CAS）** | 同一动作只成功一次（守卫互斥 + InnoDB 行锁） | 保证不了 `OrderId` 唯一（**该点 2026-09-30 由 S1a 的 UNIQUE 认领**）；也保证不了时间线的 `from`（要读回，且读回必须在锁内） |

⇒ **三笔都在替数据库补唯一性，而三笔的补法没有一笔覆盖「锁不可用」这一档。** 这是「局部推理极强、组合推理缺席」的结构性后果：
每一笔在自己的边界内都正确，而**边界本身（唯一性不是约束）从未被任何一笔认领**。

> 🔄 **2026-09-30 S1a 是第一次有笔认领这条边界**：`order.OrderId` 的 UNIQUE 由 DB 自己执行 —— 锁**全部 fail-open 也拦得住重复单号**（这正是 `A-S2a-1b` 在兜的那一层）。但**只认领了一条**（前提 A 的 DB 面）：B / B′ / C 与 `order_state_transition → order` 的引用完整性至今仍无约束。
