# DDD 包结构规划（**已执行**）

> **状态**：2026-10-02 定稿，**同日已执行**（笔 1 挪包 `01df702`、笔 2 拆 Controller + URL 规范化，同一 PR）。本文件保留为**规划原稿**——正文的包结构、计数、URL 均为**规划当时**的状态。
> ⚠️ **URL / 类名口径**：正文里的 `/mysqlLogin`、`/mysqlGoods` 是**笔 2 之前**的前缀；笔 2 已规范化为 `/user`（5）、`/goods`（商品 9）、`/order`（订单 8）。类名 `MallManagementGoodsController` / `MallManagementMessage` / `MallManagementUserController` 亦已分别改为 `GoodsController` / `MessageController` / `UserController`。故 A1 行「URL 一字不变」**已不成立**（现行契约见 `api-contract.md`）。
> 执行前请连同 `context-packs/three-layers.md` 一起读。
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
| A1 | `MallManagementGoodsController`（17 端点） | **拆**：`goods.controller`(9) + `order.controller`(8) | 一个类干两件事，是当前架构最重的债。**~~URL 与形参一字不变——HTTP 契约不动~~**（⚠️ 2026-10-02 笔 2 已推翻：URL 前缀已规范化，见文首注） |
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
| 10 | `MallCacheMetrics` | `mallmanagement_server` | `shared.cache` | 分域笔 |
| 11 | `MallLockMetrics` | `mallmanagement_server` | `shared.lock` | 分域笔 |
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
| 28 | `MallManagementUserController` | `mallmanagement_controller` | `user.controller`（**仅 `/mysqlLogin` 5 端点**；`userSelect` 不并入，见 §11 裁决①） | 分域笔 |
| 29 | `MallManagementMessage` | `mallmanagement_controller` | `message.controller`（消息 4 + 好友 5 + `userSelect` 1，共 10） | 分域笔 |
| 30 | `UserCacheService` | `mallmanagement_service` | `user.service` | 三层齐后 |
| 31 | `FriendCacheService` | `mallmanagement_service` | `message.service` | 三层齐后 |

**计数核验**：`1(不动) + 1(删) + 6(shared.cache) + 3(shared.lock) + 3(shared.upload) + 1(shared.health) + 1(order.metrics) + 1(OrderService) + 1(OrderDao) + 6(order.model) + 2(goods.service) + 1(GoodsController) + 1(UserController) + 1(Message) + 1(UserCacheService) + 1(FriendCacheService) = 31` ✅

### 终态目录树

```
com.minke
├── MallManagementApplication.java            ← 不动（硬约束见 §8-R3）
└── mallmanagement/
    ├── shared/
    │   ├── cache/     CacheConfig · TwoLevelCache · TwoLevelCacheManager · L2HealthProbe · L2ProbeConfig
    │   │              MallCacheMetrics                      ← 不可再拆，见 §7
    │   ├── lock/      DistributedLockService(含内嵌 LockName) · LockSceneService · MallLockMetrics
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
    │   ├── controller/  UserController           （原 MallManagementUserController，仅 /mysqlLogin）
    │   └── service/     UserCacheService
    └── message/
        ├── controller/  MessageController        （消息 4 + 好友 5 + userSelect 1；三层后另有 MessageService / MessageDao）
        └── service/     FriendCacheService
```

### 端点拆分明细（HTTP 契约不变，仅换承载类）

| 源类 | 端点数 | 去向 | 明细 |
|---|---|---|---|
| `MallManagementGoodsController` `/mysqlGoods` | 17 | 商品 **9** → `goods.controller` | `goodsAdd` · `goodsSelect` · `goodsSelectHome` · `goodsDe` · `productCodePrice` · `goodsUp` · `GoodsProductCodeSelect` · `productSearch` · **`goodstype`**（`@RequestMapping(GET,POST)`，非 `@PostMapping`） |
| | | 订单 **8** → `order.controller` | `orderAdd` · `orderSelect` · `orderDeliveryIfSelect` · `orderDeliverySelect` · `historicalOrdersSelect` · `orderPayIfUp` · `orderReceiveIfUp` · `orderDeliveryIfUp` |
| `MallManagementMessage` `/message` | 10 | 消息 **4** → `message.controller` | `conversations` · `history` · `send` · `getOrCreateConversation` |
| | | 好友 **5** → `message.controller` | `friendAdd` · `selectFriend` · `friendUp` · `friendDe` · `friendSelect` |
| | | 用户 **1** → `message.controller`（**留在本域**） | `userSelect` —— URL `/message/userSelect` 与其前缀同域；实现改为委托 `user.service`，见 §11 裁决① |
| `MallManagementUserController` `/mysqlLogin` | 5 | `user.controller` | `loginSelect` · `loginAdd` · `getUserInfo` · `updateAvatar` · `updateUserInfo` |
| `HealthController` `/health` | 1 | `shared.health` | `db`（`@Profile("a3")`） |

---

## §3 `shared` 包收哪些

| 子包 | 收 | 类数 |
|---|---|---|
| `shared.cache` | `CacheConfig`、`TwoLevelCache`、`TwoLevelCacheManager`、`L2HealthProbe`、`L2ProbeConfig`、`MallCacheMetrics` | 6 |
| `shared.lock` | `DistributedLockService`（含内嵌 `public enum LockName`）、`LockSceneService`、`MallLockMetrics` | 3 |
| `shared.upload` | `UploadStorage`、`ImgPath`、`ImgConfig` | 3 |
| `shared.health` | `HealthController` | 1 |
| `shared.config` | **（不设）** | 0 |
| ~~`shared.metrics`~~ | **（已取消，见 §11 裁决 B）** | 0 |

**三条排除理由**：
- `MallOrderStateMetrics` **不进** `shared` —— 它 import `mallmanagement_service.OrderAction`（`MallOrderStateMetrics.java:3`，实测唯一一条域类型 import），放进 `shared` 会立造 `shared → order` 反向边（§5）。归 `order.metrics`。
- `shared.metrics` **不设** —— 两个 Metrics 类各自与消费它的子系统存在**包私有构造器**耦合（`MallCacheMetricsTest` 需与 `MallCacheMetrics`、`TwoLevelCache`、`L2HealthProbe` 三者同包），`metrics` 一旦独立，该约束无法同时满足。裁决 B：`MallCacheMetrics` 随 `shared.cache`、`MallLockMetrics` 随 `shared.lock`（§11）。
- `shared.config` **不设** —— 实测唯一的配置类 `ImgConfig` 属上传语义，1 个类的空包不值。

---

## §4 跨域依赖图（定稿后）

按 §2 落位后，只剩**域 → shared** 与**域 → 域**两类边：

```
                    ┌────────────────────────────┐
                    │  shared                     │
                    │  cache · lock               │
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
>
> 补记（笔 1 裁决 B）：`shared.metrics` 这个包已**整体取消**——`MallCacheMetrics` 归 `shared.cache`、`MallLockMetrics` 归 `shared.lock`。上面这条推断所指向的落点（`order.metrics`）不变，只是「对照组」`shared.metrics` 不再存在。见 §11。

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

**⚠️ 硬约束之二（笔 1 实测补入）——包私有成员的第二类：`shared` 内部的主代码 ↔ 主代码耦合**

上面那张表全是「主代码 ↔ 测试」。实测还存在一条**主代码 ↔ 主代码**的包私有耦合，它比测试耦合更硬（破的是生产代码，不是测试）：

| 常量 | 声明 | 消费方 | 消费方所在 |
|---|---|---|---|
| `L2HealthProbe.PROBE_INFLIGHT` | `L2HealthProbe.java:72`（`static final Duration`，**包私有**） | `L2ProbeConfig.java:77`（构造入参）、`:151`（校验）、`:154`（报错文案） | 主代码 |

⇒ **`shared.cache` 不可再拆**。`L2ProbeConfig` 读 `L2HealthProbe.PROBE_INFLIGHT` 靠的是同包可见性；把两者分到不同子包（如 `shared.cache.probe` / `shared.cache.config`）会直接编译失败，且**没有任何测试会提前发现**——它挂在编译期，不在测试期。

**⚠️ 硬约束之三（同上）——包私有构造器**

全仓实测**只有 2 个类**有包私有构造器（枚举构造器为隐式 `private`，不属此类，且无外部使用）：

| 类 | 包私有构造器 | 谁需要同包 |
|---|---|---|
| `L2HealthProbe` | `L2HealthProbe.java:109` `L2HealthProbe(RedisConnectionFactory, Duration, ...)` | `MallCacheMetricsTest:409` |
| `TwoLevelCache` | `TwoLevelCache.java:59` `TwoLevelCache(String, Cache, Cache, ...)` | `MallCacheMetricsTest:390` |

这正是裁决 B 的**成因**：`MallCacheMetricsTest` 一个测试类同时需要与 `MallCacheMetrics`（11 个包私有常量）、`TwoLevelCache`（包私有构造器）、`L2HealthProbe`（包私有构造器）三者同包。若 `shared.metrics` 独立存在，这个测试无处安放——**三选二，必然有一个编译不过**。取消 `shared.metrics` 是唯一不需要放宽可见性的解（见 §11 裁决 B）。

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
2. **再做底座**：`shared.cache / shared.lock / shared.upload / shared.health`（13 个类）
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

---

## §11 修订记录：笔 1（纯挪包）执行回填 · 2026-10-02

本节由**用户裁决**改动本文件前述章节，逐条留痕。笔 1 = 纯挪包（49 搬运 + 1 删除），**不含** Controller 拆分（R2，留给笔 2）。

### 裁决① —— `userSelect` 不拆，留 `message.controller`（§2 已改）

原定把 `MallManagementMessage` 的 `userSelect` 端点到 `user.controller`。**改定：不拆**——`userSelect` 的 URL 是 `/message/userSelect`，与所在域前缀一致；域归属由 **Service** 表达（`MallManagementMessage.userSelect` 委托给 `user.service.UserService.getPublicInfo()`），Controller 只是 HTTP 适配器。

⇒ 规划文档两处随之修正：`user.controller` **只 1 个类**（`UserController`，仅 `/mysqlLogin` 5 端点）；`userSelect` 归 `message.controller`（消息 4 + 好友 5 + `userSelect` 1 = 10）。文件数因此 **+1 降到 +0**。

> 附带效果：`UserCacheService` 改名/定位为 `UserService` 一事**留给 user 域三层笔**，不在本规划内。

### 裁决② 与 ③ —— 笔 2 的边界（**不含**注释；`MallManagementApplicationTests` 归 `com.minke`）

- **②笔 2 含断言、不含注释**：`AbstractSmokeTest` 里「全部 3 个 Controller」那句**注释**不进笔 2（它是纯注释，另列第 3 笔或后续）；`MallManagementApplicationTests` 新增 `orderControllerIsNotAopProxied` **进**笔 2（防回归）。⇒ 笔 2 = 4 源文件 + 1 断言 = **5 文件**，正好贴 ≤5 判据。
- **③测试包落点**：`MallManagementApplicationTests` → `com.minke`（贴启动类，Spring Boot 惯例）。**接受**——不追求全仓单一前缀。笔 1 已按此落位。

### 裁决 B —— 取消 `shared.metrics`（§2 / §3 / §4 / §9 已改）

原定 `MallCacheMetrics`、`MallLockMetrics` 同归 `shared.metrics`。执行时实测发现 `MallCacheMetricsTest` 需**同时**与 `MallCacheMetrics`（包私有常量）、`TwoLevelCache`、`L2HealthProbe`（后两者为包私有构造器）同包，`shared.metrics` 独立后无处安放该测试。

三个候选处置：**A** 最小改动保留 `shared.metrics`；**B** 取消 `shared.metrics`，两类各随其子系统；**C** 放宽那 4 个成员的可见性（会**作废内容哈希证据**——可见性属被改动的代码行）。**用户裁决：B。**

| 类 | 原定 | **改定** |
|---|---|---|
| `MallCacheMetrics` | `shared.metrics` | **`shared.cache`** |
| `MallLockMetrics` | `shared.metrics` | **`shared.lock`** |
| `MallCacheMetricsTest` | `shared.metrics` | **`shared.cache`** |
| `MallLockMetricsTest` | `shared.metrics` | **`shared.lock`** |

### 裁决④ —— `L2HealthProbe.PROBE_INFLIGHT` 回补（已写入 §7）

补记为一类此前**未分析**的耦合：包私有**构造器**（此前 §7 只分析了字段/常量），以及一条**主代码 ↔ 主代码**的包私有耦合（`L2ProbeConfig` → `L2HealthProbe.PROBE_INFLIGHT`），并据此钉下「**`shared.cache` 不可再拆**」。

### 执行结果（笔 1）

- **范围**：49 文件换路径 + `info.java` 删除 + `MallManagementApplication` 原位不动 = 旧树 51 → 新树 50。
- **验证**：`./mvnw -B clean test` → **130 通过 / 0 失败 / 0 错误 / 8 跳过**（与基线逐项一致）；`npm run build` → Build complete。
- **内容哈希**：剥 `package`/`import` 行后逐文件比对，**46/49 逐字节相同**；3 个差异全部在 javadoc 注释内的包名引用（`{@link}` 3 处 + `{@code}` 散文 1 处），**零执行代码变化**。
- **未完成**：Controller 拆分（R2）= 笔 2，待笔 1 合并后单独进行。

---

## §12 后续追记：三层重构对目录树的影响 · 2026-10-03

> ⚠️ **本文件 §3「终态目录树」（`:98-124`）是 `01df702` 笔 1 挪包时的快照，不是现行清单。** 此后各域三层笔（Controller → Service → Dao）在每个域下**新增了 `dao/` 子包**，并把「域逻辑」从 Controller 搬进各自的 `service/` 类 —— 树里未逐一反映，引用前请以源码为准。

| 域 | 三层笔 commit | `service/` 新增 | `dao/` 新增 | 树中已反映？ |
|---|---|---|---|---|
| order | 第一笔 PR #25 `28194b4` | `OrderService` | `OrderDao` | ✅ 树 `:110-111` 已有（order 是唯一在笔 1 当时就带 `dao/` 的域） |
| goods | 第二笔 2026-10-02 | `GoodsService` | `GoodsDao` | ❌ 树 `:117` 只列 `GoodsCacheService · GoodsTypeCacheService` |
| user | 第三笔 PR #32 `0c07e96` | `UserService` | `UserDao` | ❌ 树 `:120` 只列 `UserCacheService`；`:119` 的「仅 /mysqlLogin」亦已陈旧 |
| message | 第四笔 `8ed5fc1` | `MessageService` | `MessageDao` | ❌ 树 `:122` 只列 `MessageController`（**已就地补注**，见该行括号） |

**修法说明**：本文件 `:42` / `:185` / `:318-322` 三处锚点被新源码 javadoc 直接引用（`MessageService:40/:61/:68`、`MessageDao:161`、`MessageController:19`、`DistributedLockServiceTest:395`），故 §3 的改动一律**行数中性（1 行 → 1 行）**，本节**只追加在文件末尾**，确保上述锚点不漂。逐域现址见 `three-layers.md` 与 `progress.md §3`。
