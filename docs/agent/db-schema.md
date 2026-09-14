# 数据库结构 — mall_management_sql

> **证据来源**：`mall_management_sql.sql` 全部 CREATE TABLE 段（行号为该文件行号）、`application.properties`、`pom.xml`。
> 表数量：11。全部为 `ENGINE=InnoDB`、`utf8mb4_0900_ai_ci`。

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
| `order` | 订单 | `Id` (自增) | **`OrderId`**(业务单号), MerchantId, CustomerId, Price, ProductInformation, Images, CreateTime, SettlementDate | **`PayIf`/`DeliveryIf`/`ReceiveIf` 三状态位**（CAS 更新）；`SettlementDate` 被三处操作覆写 | 仅主键 | `:141-155` |
| `productsales` | 商品销量统计 | `Id` (自增) | ProductCode, MerchantId, GoodName | **`OrderVolume`：恒为 0，全项目从未更新** | 仅主键 | `:165-172` |
| `user` | 用户档案 | `username` | usertype, customerid, merchantid, gender, age, email, selfintroduction, address | — | 仅主键 | `:182-193` |
| `user_conversation_read` | 会话最后已读时间 | **复合** `(user_id, user_type, other_id, other_type)` | last_read_time | 复合主键天然支撑 `ON DUPLICATE KEY UPDATE` upsert | 主键即索引 | `:203-210` |

种子数据交叉证据：`:176` 中 5 条 `productsales` 的 `OrderVolume` 全为 `0`，而 `:159` 的 `order` 表已有 6 条历史订单。

## 2. 索引现状

**11 张表中仅 `message` 有二级索引**（2 个）。其余 10 张表除主键外无任何索引。已知高频查询列缺索引：

| 查询位置 | SQL 条件 | 涉及表 | 现况 |
|---|---|---|---|
| `G:188` | `login WHERE id = ?` | `login` | `Id` 无索引；`goodsSelectHome` 中对每个商品执行一次 |
| `G:160` | `goods WHERE MerchantId = ?` | `goods` | `MerchantId` 无索引 |
| `G:434` | `order WHERE CustomerId = ?` | `order` | `CustomerId` 无索引 |
| `G:452` | `order WHERE MerchantId = ?` | `order` | `MerchantId` 无索引 |
| `M:331` | `user WHERE customerid = ? OR merchantid = ?` | `user` | 两列均无索引；且 `OR` 无法走单列索引 |

另：`login.Password` 为明文存储（`application.properties` 中无加密配置，Controller 中无哈希逻辑）。

## 3. ⚠️ ddl-auto 与手写 DDL 的冲突

| 项 | 内容 | 证据 |
|---|---|---|
| 配置 | `spring.jpa.hibernate.ddl-auto=update` | `application.properties:12` |
| 依赖 | `spring-boot-starter-data-jpa` 已引入 | `pom.xml:51` |
| 手写 DDL | `mall_management_sql.sql`，11 张表完整 CREATE TABLE | 全文件 |

**当前实际生效的是手写 `mall_management_sql.sql`。**
原因：全项目**不存在任何 `@Entity`**——`mallmanagement_class/info.java` 是全项目唯一实体类，仅有 `@Data`/`@AllArgsConstructor`/`@NoArgsConstructor` 三个 lombok 注解。Hibernate 无实体可映射，`ddl-auto=update` 当前是**空转**，不会修改任何表结构。

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
| 付款 | `SET PayIf = 1, SettlementDate = NOW()` | `G:524` |
| 收货 | `SET ReceiveIf = 1, SettlementDate = NOW()` | `G:546` |
| 发货 | `SET DeliveryIf = 1, SettlementDate = NOW()` | `G:568` |

### 4.2 覆盖行为

三个操作**写的是同一列**，后发生者覆盖先发生者。正常流转（付款→发货→收货）下该列最终值 = **收货时间**；只付款未发货时 = **付款时间**。因此该列**无法稳定表示任何一个业务时间点**，取值取决于订单停在哪个状态。

另：该列在 `orderSelect`、`orderDeliveryIfSelect`、`orderDeliverySelect` 中**均未被查询**；仅 `historicalOrdersSelect`（`G:502`）取出用于历史订单展示，并按 `SettlementDate DESC` 排序（`G:505`）。

### 4.3 三种可选方案（**待业务确认**）

| 方案 | 做法 | 代价 |
|---|---|---|
| **A 不改** | 维持现状，将语义解释为「最后状态变更时间」 | 零改动；但列名与语义不符，历史订单排序含义模糊 |
| **B 只付款写** | 三处中仅付款保留赋值，发货/收货不再写该列 | 改动小；若业务需要发货/收货时间，仍需另加字段 |
| **C 拆三字段** | 拆为 `PayTime` / `DeliveryTime` / `ReceiveTime` 三列 | 语义最清晰；需改 DDL + 三处 UPDATE + 相关查询，改动最大 |

**决策依据应由业务方给出**：该列当前实际承担的是「历史订单展示与排序」（`G:502`、`G:505`），需先确认业务上到底要展示哪个时间点。

## 5. 待确认（其他）

- 全库无 CHECK 约束、无外键约束（`mall_management_sql.sql` 导入时关闭 `FOREIGN_KEY_CHECKS`，且 DDL 未定义外键）—— 引用完整性完全由应用层维护。
