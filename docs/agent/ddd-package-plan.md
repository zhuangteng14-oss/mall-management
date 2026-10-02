# DDD 包结构规划（**定稿，未执行**）

> **状态**：2026-10-02 定稿。**本文件只描述规划，未改动任何代码、未推分支。**
> 执行时机另行安排；执行前请连同 `context-packs/three-layers.md` 一起读。
>
> **素材范围**：`Mall-management-server/src/` 全量 `.java`（主代码 31 + 测试 20）、`import` 语句、包声明、Spring 注解、`pom.xml`、`application*.properties`。
> **方法**：包级依赖图由脚本从**剥除注释与字符串字面量**后的源码提取（避免 javadoc 里的类名造出假边），每条可疑边再做 REAL-vs-javadoc 定向复核。无证据处标「待确认」。

---

## §0 前提修正：主代码是 **31** 个 `.java`，不是 28

`find main/java -name '*.java' | wc -l` = **31**。「28」是 PR #25 之前的计数，且未含 `MallManagementApplication`。

| 现包 | 文件数 |
|---|---|
| `com.minke` | 1 |
| `com.minke.mallmanagement_class` | 1 |
| `com.minke.mallmanagement_controller` | 4 |
| `com.minke.mallmanagement_server` | 10 |
| `com.minke.mallmanagement_service` | 15 |
| **合计** | **31** |

**测试源 20 个文件，三种顶层包名拼写**（这是 §8-R6 的一条风险）：

| 拼写 | 文件数 |
|---|---|
| `com.minke.mallmanagement_server` | 4 |
| `com.minke.mallmanagement_service` | 8 |
| `com.minke.mallmanagementserver` | 2 + `smoke/` 5 |

---

## §1 裁决定稿（2026-10-02）

七项归属裁决 + 其余确认。**理由栏为裁决原文要点的记录。**

| # | 项 | 裁决 | 理由 |
|---|---|---|---|
| A1 | `MallManagementGoodsController`（17 端点） | **拆**：`goods.controller`(9) + `order.controller`(8) | 一个类干两件事，是当前架构最重的债。**URL 与形参一字不变——HTTP 契约不动** |
| A2 | `MallManagementMessage`（10 端点） | **拆三**：消息 4 → `message.controller`；好友 5 → `message.controller`；`userSelect` 1 → `user.controller` | 好友属社交域，与消息同族；**不单开 `friend` 域**——好友与消息是同一社交域的两种投影 |
| A3 | `MallOrderStateMetrics` | **`order.metrics`**（非 `shared`） | 解 §5 的 `shared↔order` 环；语义属于 order（它 import `OrderAction`） |
| A4 | `FriendCacheService` | **`message`** | 与 A2 一致；**不归 user**——user 是账号/身份，好友是关系 |
| A5 | `LockSceneService` | **`shared.lock`** | 它是锁的场景跑批。生产零引用**不改变归属**；**不挪 test**——可能被未来 C3 攻击模拟器复用 |
| A6 | `HealthController` | **`shared.health`** | `@Profile("a3")` 的运维探针，是基础设施；**不单开 a3/infra 包**（1 个类的空包不值） |
| A7 | `info`（`mallmanagement_class`） | **删除** | 0 引用 + 死代码；**不保留为 `shared.dto`**——从未被用，保留只是留垃圾 |
| — | `LockName` 内嵌于 `DistributedLockService` | **保持内嵌** | 拆成各域自持会增加复杂度、收益不显。`shared.lock` 知道锁名是柔性违反依赖倒置，但实用；未来跨域耦合变重再拆 |
| — | 包名前缀 | `com.minke.mallmanagement.{order\|goods\|user\|message\|shared}` | 见 §6 |
| — | `shared` 四项 | `cache / lock / metrics / upload` | 见 §3 |
| — | 域内子包 | **等该域三层齐再开** | 见 §7 |
| — | 迁移顺序 | 纯移动先于拆分，底座先于域 | 见 §9 |

---

## §2 最终版 DDD 包结构（31 文件定稿）

**落地时机**列的含义：`分域笔` = 分域重构这一笔就能到位；`三层齐后` = 等该域三层重构完成再开子包（见 §7）。

| # | 类 | 现在 | **目标（定稿）** | 落地时机 |
|---|---|---|---|---|
| 1 | `MallManagementApplication` | `com.minke` | **不动** | — |
| 2 | `info` | `mallmanagement_class` | **删除** | 分域笔 |
| 3 | `CacheConfig` | `mallmanagement_server` | `shared.cache` | 分域笔 |
| 4 | `TwoLevelCache` | `mallmanagement_server` | `shared.cache` | 分域笔 |
| 5 | `TwoLevelCacheManager` | `mallmanagement_server` | `shared.cache` | 分域笔 |
| 6 | `L2HealthProbe` | `mallmanagement_server` | `shared.cache` | 分域笔 |
| 7 | `L2ProbeConfig` | `mallmanagement_server` | `shared.cache` | 分域笔 |
| 8 | `DistributedLockService` | `mallmanagement_service` | `shared.lock` | 分域笔 |
| 9 | `LockSceneService` | `mallmanagement_service` | `shared.lock` | 分域笔 |
| 10 | `MallCacheMetrics` | `mallmanagement_server` | `shared.metrics` | 分域笔 |
| 11 | `MallLockMetrics` | `mallmanagement_server` | `shared.metrics` | 分域笔 |
| 12 | `UploadStorage` | `mallmanagement_service` | `shared.upload` | 分域笔 |
| 13 | `ImgPath` | `mallmanagement_server` | `shared.upload` | 分域笔 |
| 14 | `ImgConfig` | `mallmanagement_server` | `shared.upload` | 分域笔 |
| 15 | `HealthController` | `mallmanagement_controller` | `shared.health` | 分域笔 |
| 16 | `MallOrderStateMetrics` | `mallmanagement_server` | **`order.metrics`** | 分域笔 |
| 17 | `OrderService` | `mallmanagement_service` | `order.service` | 分域笔（order 三层已完成） |
| 18 | `OrderDao` | `mallmanagement_service` | `order.dao` | 分域笔（同上） |
| 19 | `OrderAction` | `mallmanagement_service` | `order.model` | 分域笔 |
| 20 | `OrderState` | `mallmanagement_service` | `order.model` | 分域笔 |
| 21 | `OrderStateMachine` | `mallmanagement_service` | `order.model` | 分域笔 |
| 22 | `OrderStateExecutor` | `mallmanagement_service` | `order.model` | 分域笔 |
| 23 | `OrderTimelineWriter` | `mallmanagement_service` | `order.model` | 分域笔 |
| 24 | `OrderTransitionRecorder` | `mallmanagement_service` | `order.model` | 分域笔 |
| 25 | `GoodsCacheService` | `mallmanagement_service` | `goods.service` | 三层齐后 |
| 26 | `GoodsTypeCacheService` | `mallmanagement_service` | `goods.service` | 三层齐后 |
| 27 | `MallManagementGoodsController` | `mallmanagement_controller` | **拆分**：商品 9 → `goods.controller`；订单 8 → `order.controller` | 分域笔 |
| 28 | `MallManagementUserController` | `mallmanagement_controller` | `user.controller`（并收编 §A2 移来的 `userSelect`） | 分域笔 |
| 29 | `MallManagementMessage` | `mallmanagement_controller` | `message.controller`（消息 4 + 好友 5，共 9） | 分域笔 |
| 30 | `UserCacheService` | `mallmanagement_service` | `user.service` | 三层齐后 |
| 31 | `FriendCacheService` | `mallmanagement_service` | `message.service` | 三层齐后 |

**计数核验**：`1(不动) + 1(删) + 5 + 2 + 2 + 3 + 1 + 1 + 1 + 1 + 6 + 2 + 1(GoodsController) + 1(UserController) + 1(Message) + 1(UserCacheService) + 1(FriendCacheService) = 31` ✅

### 终态目录树

```
com.minke
├── MallManagementApplication.java            ← 不动（硬约束见 §8-R3）
└── mallmanagement/
    ├── shared/
    │   ├── cache/     CacheConfig · TwoLevelCache · TwoLevelCacheManager · L2HealthProbe · L2ProbeConfig
    │   ├── lock/      DistributedLockService(含内嵌 LockName) · LockSceneService
    │   ├── metrics/   MallCacheMetrics · MallLockMetrics
    │   ├── upload/    UploadStorage · ImgPath · ImgConfig
    │   └── health/    HealthController                      ← @Profile("a3")
    ├── order/
    │   ├── controller/  OrderController          （从 GoodsController 拆出 8 端点）
    │   ├── service/     OrderService
    │   ├── dao/         OrderDao
    │   ├── metrics/     MallOrderStateMetrics
    │   └── model/       OrderAction · OrderState · OrderStateMachine
    │                    OrderStateExecutor · OrderTimelineWriter · OrderTransitionRecorder
    ├── goods/
    │   ├── controller/  GoodsController          （从 GoodsController 拆出 9 端点）
    │   └── service/     GoodsCacheService · GoodsTypeCacheService
    ├── user/
    │   ├── controller/  UserController           （原 MallManagementUserController + userSelect）
    │   └── service/     UserCacheService
    └── message/
        ├── controller/  MessageController        （消息 4 + 好友 5）
        └── service/     FriendCacheService
```

### 端点拆分明细（HTTP 契约不变，仅换承载类）

| 源类 | 端点数 | 去向 | 明细 |
|---|---|---|---|
| `MallManagementGoodsController` `/mysqlGoods` | 17 | 商品 **9** → `goods.controller` | `goodsAdd` · `goodsSelect` · `goodsSelectHome` · `goodsDe` · `productCodePrice` · `goodsUp` · `GoodsProductCodeSelect` · `productSearch` · **`goodstype`**（`@RequestMapping(GET,POST)`，非 `@PostMapping`） |
| | | 订单 **8** → `order.controller` | `orderAdd` · `orderSelect` · `orderDeliveryIfSelect` · `orderDeliverySelect` · `historicalOrdersSelect` · `orderPayIfUp` · `orderReceiveIfUp` · `orderDeliveryIfUp` |
| `MallManagementMessage` `/message` | 10 | 消息 **4** → `message.controller` | `conversations` · `history` · `send` · `getOrCreateConversation` |
| | | 好友 **5** → `message.controller` | `friendAdd` · `selectFriend` · `friendUp` · `friendDe` · `friendSelect` |
| | | 用户 **1** → `user.controller` | `userSelect` |
| `MallManagementUserController` `/mysqlLogin` | 5 | `user.controller` | `loginSelect` · `loginAdd` · `getUserInfo` · `updateAvatar` · `updateUserInfo` |
| `HealthController` `/health` | 1 | `shared.health` | `db`（`@Profile("a3")`） |

---

## §3 `shared` 包收哪些

| 子包 | 收 | 类数 |
|---|---|---|
| `shared.cache` | `CacheConfig`、`TwoLevelCache`、`TwoLevelCacheManager`、`L2HealthProbe`、`L2ProbeConfig` | 5 |
| `shared.lock` | `DistributedLockService`（含内嵌 `public enum LockName`）、`LockSceneService` | 2 |
| `shared.metrics` | `MallCacheMetrics`、`MallLockMetrics` | 2 |
| `shared.upload` | `UploadStorage`、`ImgPath`、`ImgConfig` | 3 |
| `shared.health` | `HealthController` | 1 |
| `shared.config` | **（不设）** | 0 |

**两条排除理由**：
- `MallOrderStateMetrics` **不进** `shared.metrics` —— 它 import `mallmanagement_service.OrderAction`（`MallOrderStateMetrics.java:3`，实测唯一一条域类型 import），放进 `shared` 会立造 `shared → order` 反向边（§5）。归 `order.metrics`。
- `shared.config` **不设** —— 实测唯一的配置类 `ImgConfig` 属上传语义，1 个类的空包不值。

---

## §4 跨域依赖图（定稿后）

按 §2 落位后，只剩**域 → shared** 与**域 → 域**两类边：

```
                    ┌────────────────────────────┐
                    │  shared                     │
                    │  cache · lock · metrics     │
                    │  upload · health            │
                    └──────┬──────────┬───────────┘
                           │          │
              ┌────────────┼──────────┼────────────┐
              │            │          │            │
        ┌─────▼─────┐ ┌────▼────┐ ┌───▼─────┐ ┌────▼────┐
        │   order   │ │  goods  │ │ message │ │  user   │
        └───────────┘ └─────────┘ └────┬────┘ └─────────┘
                                       │
                                       │  message → user
                                       │  （userSelect / FriendCacheService 查 user）
                                       ▼
```

| 提问 | 实测答案 |
|---|---|
| **order 引 user？** | **不引**。order 域零条指向 user 的边。 |
| **goods 引 order？** | **定稿后不引**。今天引是因为 `GoodsController` 把订单端点也挂在 `/mysqlGoods`；**拆分后此边消失**。 |
| **goods 引 user？** | **不引**。（早前一张图里的 `GoodsCacheService→UserCacheService` 是 javadoc 假边，定向复核为 0 处代码引用。） |
| **message 引 user？** | **引** —— `userSelect` 端点与 `FriendCacheService` 的用户信息查询。方向正确、无环，保留。 |

---

## §5 循环依赖

**现状（迁移前）主代码恰好 1 个包环**：`mallmanagement_service ↔ mallmanagement_server`，**唯一闭合边只有一条**：

```
OrderStateExecutor / OrderTimelineWriter / OrderTransitionRecorder
        │  （均在 mallmanagement_service）
        ▼
MallOrderStateMetrics                     （mallmanagement_server）
        │  MallOrderStateMetrics.java:3
        │  import com.minke.mallmanagement_service.OrderAction;
        ▼
OrderAction                               （mallmanagement_service）  ← 闭环
```

> 实测旁证：`MallLockMetrics` 与 `MallCacheMetrics` **零条**指向 service 包的 import，故不是环的另一半；`DistributedLockService → MallLockMetrics` 是单向的。

**定稿后：环消失。** 因为 A3 把 `MallOrderStateMetrics` 放进 `order.metrics` ⇒ `order → order.metrics` 是**域内**边，`order.* → shared.*` 是**域 → 底座**边，不存在 `shared → order` 回望。

> ⚠️ 这正是 A3 的判据：若照最初设想把 `MallOrderStateMetrics` 放进 `shared.metrics`，环会从「包环」升级为 **`shared ↔ order` 架构环** —— shared 是底座，依赖任何域都是最该禁止的一类。

---

## §6 包名前缀

**定稿：`com.minke.mallmanagement.{order|goods|user|message|shared}`**

理由：
1. **零配置改动** —— `@SpringBootApplication` 在 `com.minke`，域包在其下仍被扫描。实测：仓库内 `.properties`/`.yml`/`.xml` 引用这三个现包名的文件数为 **0**，重命名不会断任何 bean 扫描或配置。
2. **修掉 `_` 下划线** —— `mallmanagement_service` 这类含下划线的包名违反 Java 包名规范，改成分段后顺手解决。
3. **保留 `mallmanagement` 一层** —— 表达「这些域同属一个应用」，未来加 `admin` 域是同级扩展，不污染 `com.minke` 根。

**备选（未采纳）**：把 `MallManagementApplication` 也移进 `com.minke.mallmanagement`，包根更统一——代价是动启动类本体，收益只是少一层，不值。

**未来扩展**：`admin`（管理端日志）预留为**同级第 6 个域** `com.minke.mallmanagement.admin`，**现在不建空包**。

---

## §7 域内三层怎么分

**终态**：`{domain}.controller / {domain}.service / {domain}.dao / {domain}.model`（+ `order.metrics`）。

**落地节奏**：**子包等该域三层齐再开**，不预先挖空坑。

- `order` 域三层**已完成**（PR #25 落 `OrderDao` + `OrderService`）⇒ `controller/service/dao/model/metrics` **随分域笔一次到位**。
- `goods` / `user` / `message` 三层**未完成** ⇒ 分域笔只落顶层域包（如 `com.minke.mallmanagement.goods`），`service` / `dao` 子包等各自的三层笔再开。
  → 因此**过渡期会出现「order 有子包、goods 没有」的不一致态**，这是 §7 节奏的**预期结果**，不是遗漏。

**⚠️ 硬约束（实测）——以下 5 个类的 `*Test` 必须与类同包，否则编译不过**（它们用到了包私有成员）：

| 类 | 包私有成员 | 同包测试 |
|---|---|---|
| `MallCacheMetrics` | 11 个 `static final String` 常量 | `MallCacheMetricsTest` |
| `MallLockMetrics` | `ACQUIRES` / `WAIT` / `HOLDS` / `UP` | `MallLockMetricsTest` |
| `MallOrderStateMetrics` | `TRANSITIONS` / `ILLEGAL` / `TIMELINE_WRITES` / `ASYNC_*` | `MallOrderStateMetricsTest` |
| `LockSceneService` | `MAX_THREADS` | `LockSceneServiceTest` |
| `OrderTimelineWriter` | `TABLE` / `INSERT_SQL` / `PARAM_COUNT` | `OrderTimelineWriterTest` |

**好消息**：这些包私有成员**没有一条被跨包生产代码使用**（实测），因此细分子包**不会破生产代码**，只会破测试包的落位——迁移时这 5 对必须**成对搬**。另注：三个 Metrics 类的嵌套枚举/record 均为 `public`（`Outcome`/`Hold`/`Result`/`RejectReason`/`L2Result`/`EvictResult` 等），故 `DistributedLockService` 等**跨包**消费它们不受影响。

---

## §8 迁移风险（R1–R10）

| R | 风险 | 实测依据 | 严重度 |
|---|---|---|---|
| R1 | 5 组「包私有成员 ↔ 同包测试」细分后编译失败 | §7 表 | 中（机械，漏了就红） |
| R2 | **两个 Controller 是「拆分」不是「移动」** | Goods 17 端点（商品 9 / 订单 8）；Message 10 端点（消息 4 / 好友 5 / userSelect 1） | **高** |
| R3 | `@SpringBootApplication` 在 `com.minke` 且**无 `scanBasePackages`** | 扫描 `com.minke.**` ⇒ 域包必须留在其下 | 高（做错 = 全应用起不来） |
| R4 | `MallOrderStateMetrics → OrderAction` 反向边 | `MallOrderStateMetrics.java:3` | 中（§5 已给解：归 `order.metrics`） |
| R5 | `LockName` 内嵌于 `DistributedLockService` | `DistributedLockService.java:519` | 低-中（**已裁决保持内嵌**，见 §1） |
| R6 | 测试包名三种拼写不一致 | `mallmanagement_server`(4) / `mallmanagement_service`(8) / `mallmanagementserver`(2+smoke 5) | 中（迁移时顺手统一） |
| R7 | `info` 是孤儿类 | 0 处真实引用（早前假边来自 `log.info(` 误匹配） | 低（**已裁决删除**） |
| R8 | `LockSceneService` 生产零引用，仅 2 个测试引用 | 全仓 grep | 低（**已裁决留 `shared.lock`**） |
| R9 | A3 门禁与 `HealthController(@Profile("a3"))` 耦合 | P5/P6/P7 探针 | 中（搬家不能改 bean 名/路径） |
| R10 | 文档锚点会再次集体漂移 | `three-layers.md` / `concurrency-test.md` / `cache-layer.md` 等已因 PR #25 漂过一轮 | 低（已知，可批量修） |

---

## §9 迁移顺序（**只列，不执行**）

1. **先做纯移动、且域内三层已完成的**：`order`（12 个类）→ `order.*`
2. **再做底座**：`shared.cache / shared.lock / shared.metrics / shared.upload / shared.health`（13 个类）
3. **然后做单域移动**：`user`、`goods`（缓存类）、`message`（`FriendCacheService`）
4. **最后做两个拆分**：`GoodsController`（商品 / 订单）、`Message`（消息 / 好友 / 用户）
5. **每步之后**：全量测试（当前基线 `130 / 0 / 0 / 8`）+ A3 **27 门禁**全绿，再进下一步
6. **文档锚点修**：与代码同笔或紧邻一笔，避免第三轮漂移

> **顺序原则**：纯移动先于拆分；底座先于依赖它的域；已完成的域先于未重构的域——每步失败面最小。

---

## §10 未覆盖 / 待确认

- **执行时机未定**（本文件只定规划）。
- 拆分出的新类**类名**（`OrderController` / `GoodsController` / `MessageController` / `UserController` / `HealthController`）为**建议命名**，执行时可再定；**URL 与形参不变**是硬约束。
- 测试包的**统一拼写**（R6）具体选哪种，执行时定。
- `MallManagementApplication` 是否移入 `com.minke.mallmanagement`——**未采纳**（§6 备选）。
- 本文件**未覆盖前端**：前端 14 个页面里若有硬编码后端包名/类名，理论上不受影响（HTTP 契约不变），**未核**。
