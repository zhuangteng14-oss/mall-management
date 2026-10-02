# 项目地图 — Mall-management

> **素材范围（原始快照，2026-09-14）**：3 个 Controller 全文、`mall_management_sql.sql` 全部 CREATE TABLE、`pom.xml` 的 `<dependencies>` 段、`application.properties`、目录树（已排除 `node_modules`/`dist`/`target`）。
> 前端源码、Service/Mapper 层**未读**。无证据处标「待确认」。
>
> ⚠️ **2026-10-02 现状**：上述范围**均已扩读**，本文件凡再出现「未读」「3 个 Controller」字样，一律指**上面那天**，不代表现状 —— 仓内现有 **5 个 Controller**（4 业务 + `HealthController`，见 §3 末与 `api-contract.md`）；前端 `src/` 与各 Service / Dao 层**均已读**，成果分别是 `api-contract.md §5`（前端调用矩阵，2026-09-30 关闭）与 §2 目录树 / `three-layers.md`。

## 1. 定位与技术栈

| 项 | 值 | 证据 |
|---|---|---|
| 形态 | 前后端分离单体，客户 / 商家双端 | `user.usertype`、`login.Role` |
| 后端框架 | Spring Boot 3.4.5 | `pom.xml:8` |
| JDK | Java 17 | `pom.xml:30` |
| 服务端口 | 8088 | `application.properties:3` |
| 数据库 | 开发库 MySQL 8.0.32，库 `mall_management_sql`；A3 另有独立 VM 实例 8.4.11（库名**相同**，见 `environment.md`） | `mall_management_sql.sql:3` |
| 数据访问 | `JdbcTemplate` 直接写裸 SQL 字符串（DAO 层用 `JdbcClient.create(JdbcTemplate)` 包一层） | ⚠️ **原记「3 个 Controller 均 `@Autowired JdbcTemplate`」现已不成立**：2026-10-02 时点，**订单与商品两域**的数据访问已分别收进 `OrderDao` / `GoodsDao`，两个 Controller 不再持有 `JdbcTemplate`；仍持有的只有 `MessageController`（`MessagejdbcTemplate`）与 `UserController`（`LoginjdbcTemplate`），另 `HealthController` 持有一个（探针用） |
| 前端 | Vue 3 CLI 工程（Vue CLI 5 / webpack 5，`vue.config.js`、`babel.config.js`） | `package.json:12`（`vue: ^3.2.13`）、`:13`（`vue-router: ^4.6.4`，Vue Router 4 仅支持 Vue 3）、`:20`（`@vue/cli-service: ~5.0.0`） |

依赖实测（`pom.xml:32-122`，`<dependencies>` 段现 **13 项**）：`spring-boot-starter`、`-jdbc`、`-data-jpa`、`-web`、`-test`、`mysql-connector-java:8.0.33`、`lombok:1.18.46`，外加 B1 / B2 引入的 `spring-boot-starter-cache`（`:72`）、`com.github.ben-manes.caffeine:caffeine`（`:78`）、`spring-boot-starter-data-redis`（`:84`）、`org.redisson:redisson`（`:119`）。
**仍未引入**：仅剩「AOP 增强包」（`spring-boot-starter-aop`）。⚠️ 原记「**未引入**：Redis、Spring Cache、Caffeine、Redisson、AOP 增强包」—— **前四者已先后落地**（B1 自 2026-09-27 起，见 `context-packs/cache-layer.md`；Redisson 于 B2，见 `concurrency-map.md §1`），该行只剩末一项成立。

## 2. 目录结构

```
期末项目/
├── agent.md                                项目开发 Agent 规范（253 行）
├── mall_management_sql.sql                 12 张表 DDL + 种子数据（11 张原始 + B3 于 2026-09-29 追加的 order_state_transition）
├── scripts/concurrency/                    A3 并发测试脚本（任务上下文见 context-packs/concurrency-test.md）
│   ├── ConcurrencyRunner.java              单文件：8 段负载（S1 / S2a / S2b / S3 / S4a / S4b / B2 / C）+ 断言 + preflight P0–P7 + JSON 报告（2795 行）
│   ├── run.sh                              两步编译（javac -encoding UTF-8 → java -cp）+ 驱动解析（Maven 回退）
│   └── reports/                            运行日志与 JSON 报告（已 .gitignore，不入库）
├── docs/agent/                             项目文档 14 份（含 db-migration.md、business-decisions.md、ddd-package-plan.md、selection-status.md 等），另有 context-packs/ 与 archive/
├── .github/workflows/ci.yml                GitHub Actions CI（3 job：backend 全量测试 + frontend 构建 + concurrency 并发脚本）
├── Mall-management-server/                 Spring Boot 后端
│   ├── pom.xml  mvnw  mvnw.cmd  HELP.md
│   ├── .idea/                              已被 Mall-management-server/.gitignore 忽略
│   └── src/
│       ├── main/java/com/minke/
│       │   ├── MallManagementApplication.java          启动类
│       │   └── mallmanagement/                         按域分层（2026-10-02 笔 1 挪包；原扁平 mallmanagement_* 4 包 + info 孤儿类已删除）
│       │       ├── goods/{controller,service,dao}/      GoodsController（/goods 9 端点）+ GoodsService（8 方法，2026-10-02 三层第二笔）+ GoodsDao（8 条内联 SQL）+ GoodsCacheService / GoodsTypeCacheService（缓存读与 goodstype）
│       │       ├── order/{controller,service,dao,model,metrics}/  OrderController（/order 8 端点）+ OrderService + OrderDao + OrderStateMachine 等
│       │       ├── message/{controller,service}/       MessageController（/message 10 端点）+ FriendCacheService
│       │       ├── user/{controller,service}/          UserController（/user 5 端点）+ UserCacheService
│       │       └── shared/{cache,health,lock,upload}/  两级缓存 / HealthController / 分布式锁 / 上传（原 mallmanagement_server 的 ImgConfig、ImgPath 在此）
│       ├── main/resources/application.properties
│       ├── main/resources/application-a3.properties    A3 profile：指向独立 MySQL 实例（同名库，TD-13）；含明文默认口令
│       ├── main/resources/static/img/                  8 张图片
│       ├── test/java/com/minke/
│       │   ├── MallManagementApplicationTests.java      全上下文冒烟 + 真查库（`A3_ENABLED=true` 才跑，见 TD-10）
│       │   └── mallmanagement/smoke/
│       │       ├── AbstractSmokeTest.java               smoke 基类（@WebMvcTest + 鉴权预留口子；自身无 @Test）
│       │       ├── LoginSelectSmokeTest.java            /user/loginSelect（只读）
│       │       ├── OrderPayIfUpSmokeTest.java           /order/orderPayIfUp（CAS 两分支）
│       │       ├── ImgMappingSmokeTest.java             /img/** 静态资源映射
│       │       ├── GoodsAddUploadFailSmokeTest.java     /goods/goodsAdd 落盘失败：显式 success:false 且不写库（TD-07 收尾；自带 @TestPropertySource 指到 pom.xml/nope-img/）
│       │       └── PriceTripleFailSmokeTest.java        /goods/productCodePrice 响应契约：裸行 / null / 抛异常三态仍为 200 + {success:false}
│       └── test/resources/application-smoke.properties  smoke profile 测试属性
├── Mall-management-web/
│   ├── package.json                        外层空壳 npm 根，与内层重复
│   └── mall-management-system/             前端工程（Vue 3）
│       ├── public/index.html  favicon.ico
│       └── src/
│           ├── main.js  App.vue
│           ├── router/index.js
│           ├── components/HelloWorld.vue
│           └── views/                      14 个页面
└── 5122512025027 + 庄腾 + 项目部署说明文档.docx    非代码文件，不纳入管理
```

## 3. 后端架构（**B1 之前的原貌快照**）

> ⚠️ **本节的计数与「无分层」判断是 B1 缓存笔之前的状态，此后未随各笔同步** —— 仓内现已有 Service 层、按域分层（见 §2 目录树与 `three-layers.md`）。本节保留以记录**起点**；引用主源/测试源数量或分层判断时，请以 §2 与 `api-contract.md` 为准。

**原貌**：主源 7 个 `.java`（含启动类）、测试源 5 个。32 个 HTTP 接口全部内联在 3 个 Controller 中，SQL 以字符串字面量形式写在方法体内。

- **原貌**：不存在 Service 层、Mapper/DAO 层、MyBatis XML、DTO/VO 层。
- **不存在** 任何 `@Entity` —— 原 `mallmanagement_class/info.java`（全项目唯一 POJO，仅 1 个 `imgName` 字段，未被任何类引用）已于 **2026-10-02 笔 1 挪包时删除**。
- 无统一返回体：`/order` 系列返回 `Map{success,...}`，`/user/loginSelect` 返回裸 `int`，`getUserInfo` 失败时返回 `null`（HTTP 200 空体）。
- 无鉴权、无拦截器、无全局异常处理；**4 个业务 Controller**（`GoodsController` / `OrderController` / `MessageController` / `UserController`）均标 `@CrossOrigin`，运维口 `HealthController` 亦有。

接口分布（2026-10-02 笔 2 后）：`/user` 5 个、`/message` 10 个、`/goods` 9 个、`/order` 8 个（另 `/health/db` 1 个运维接口），详见 `api-contract.md`。

## 4. 前端结构

Vue 3 CLI 工程，14 个页面视图 + 1 个组件 + 1 个路由表。页面按角色/场景划分（登录、首页、商品、购物车、订单中心、聊天、好友、用户信息等）。**路由与页面内部实现已读**（2026-09-30，原记「未读，待确认」已关闭）—— 路由表 `router/index.js` 共 7 条路由（hash 模式），各页面的接口调用与参数拼装见 `api-contract.md §5`。

`Mall-management-web/package.json` 与 `Mall-management-web/mall-management-system/package.json` 并存，前者疑为空壳，实际工程根为后者。

## 5. 关键结论

| 结论 | 详见 |
|---|---|
| 全项目仅 1 处事务边界，多处多表写无事务保护 | `concurrency-map.md` |
| 订单三状态位用条件 UPDATE 实现 CAS，是全项目唯一正确的并发控制 | `concurrency-map.md` |
| `productsales.OrderVolume` 从未被更新，订单量统计功能失效 | `concurrency-map.md` |
| 全项目零缓存（**2026-09-14 时**；B1 自 2026-09-27 起逐笔落地，现行见右栏第二项） | `cache-map.md`（顶部有时效标注）/ `context-packs/cache-layer.md` |
| `ddl-auto=update` 与手写 DDL 脚本并存 | `db-schema.md` |
| **12** 张表中 **3 张**有二级索引（共 4 个）：`message` 2 + `order_state_transition` 1 + `order` 1（`uk_order_orderId`） | `db-schema.md` §5 |
| 测试：**19** 个测试类 / **135** 个 `@Test` 用例（2026-10-02 实测，见下方口径注）；另有抽象基类 `smoke/AbstractSmokeTest`（自身**无** `@Test`）；`MallManagementApplicationTests` 的 **5** 个用例在未设 `A3_ENABLED` 时**全部 skip**，设 `true` 时真连库 | `tech-debt.md` TD-10（已关闭） |

> ⚠️ 时效口径：本表**逐行反映当前结论**；凡单元格内标注「（**…时**）」的，才是**该时刻**的值 —— 只有上面「零缓存」一行属于此列，其余各行**未受缓存迁移影响**（B1 只搬读路径，未动 DDL / 事务 / 订单逻辑），不必整体打折读。
>
> 上表「测试」行的原计数（6 / 2）是 **2026-09-20**（`e3a9ada` 写入，再上一版 `23dfaab` 2026-09-18 记 4 / 5）的值；B1 期间新增 `L2HealthProbeConcurrencyTest`（3 个用例）与 `messageControllerIsNotAopProxied`（1 个）等 ⇒ 2026-09-29 曾就地改为 **11 / 4**。**2026-10-02 再次实测并改为 19 / 135**（原先只写类数不写口径，是那版数错的成因）。
>
> ⚠️ **计数口径（2026-10-02 定，防再数错）**：`@Test` 用例数**不能用** `grep -c '@Test'` 数 —— `@TestPropertySource`（本仓 3 处）与注释里的字面 `@Test`（`AbstractSmokeTest:69` 一处）都会被算进去，裸数得 **138**，实为 **135**。用 `grep -rEho '@Test([^A-Za-z]|$)'`（或 `^\s*@Test\s*$`）得 135，**与 surefire 的 `Tests run: 135` 逐字吻合**（135 含 9 个 skip：`MallManagementApplicationTests` 5 + `LockSceneRedisTest` 4）。同理「19 个测试类」= 含**真** `@Test` 注解的文件数 = surefire 报告份数（`AbstractSmokeTest` 是无 `@Test` 的抽象基类，不计）。
>
> 该行不在缓存主题内，与缓存笔合并只因**同一张表**：留旧数会让上一段「本表逐行反映当前结论」变成假话。

## 6. N+1 待优化清单（优先级：**高**）

以下接口在循环内逐条发起查询，**单次请求的 SQL 次数随数据量线性增长**。此处仅记录，**未改代码**。

行号约定（与 `cache-map.md §6` 一致）：`G:` = `GoodsController.java`（2026-10-02 前名 `MallManagementGoodsController.java`；本表 `G:` 值均为**搬迁前**坐标，见行内注），`M:` = `MessageController.java`（前名 `MallManagementMessage.java`）；查询记「SQL 定义行 → 执行行」区间，方法调用记调用行。

| 接口 | 文件行号 | 涉及表 | 优先级 |
|---|---|---|---|
| `POST /goods/goodsSelectHome` | 全量查 `G:184-185`，循环 `G:188-208`，内层查询 `G:194-200`（**均为搬迁前坐标**；现整段在 `GoodsCacheService.listHome()`） | goods → login | 高 |
| `POST /message/conversations` | 外层查 `M:33`，循环 `M:35-66`，内层查询 `M:48-50` | message → user_conversation_read | 高 |
| `POST /message/friendSelect` | 外层查 `M:320-322`，循环 `M:324-330`，内层调用 `M:326`（**均为搬迁前坐标**；2026-09-29 起整段在 `FriendCacheService.listFriends()`） | friend → user → customerimage/merchantimage | 高 |
| `POST /message/selectFriend` | 外层查 `M:249-251`，循环 `M:253-259`，内层调用 `M:255`（**均为搬迁前坐标**；2026-09-29 起整段在 `FriendCacheService.listRequests()`） | friend → user → customerimage/merchantimage | 高 |

注：后两行内层调用的 `getUserInfoById`（搬迁前 `M:341-380`）自身含 2 条 SQL（`M:342-344` 查 user，`M:360-361` / `M:364-365` 按 `usertype` 二选一查 `customerimage` / `merchantimage`），故每轮循环实际产生 2 条查询，复杂度为 **1 + 2N**。搬迁后该方法在 `FriendCacheService.userInfoById()`。

> 2026-09-29 校正（两件，勿混淆）：① **上表后两行的 `M:` 坐标原记 `M:309-311` / `M:238-240`（`getUserInfoById` 原记 `M:330-369`、`M:331-333`、`M:349-350`、`M:353-354`），实测**四组各漂移 +11**——本表已换成搬迁前真实值 `M:320-322` / `M:249-251`。证据：以 `git show 549f74f:MallManagementMessage.java` 逐行核对（该 commit 即本笔改动前状态），非推算。② 同日起这两处的读路径**已搬到 `FriendCacheService`**（B1 缓存笔），Controller 只剩路由。⚠️ **缓存把 1 + 2N 摊薄为「每 TTL 一次」，是掩盖而非修复** —— 与 `cache-layer.md` §11 附 `goodsHome` 行同款注记；解 N+1 只能改 SQL（JOIN / IN 批量），仍归 `fix-n-plus-one`。
>
> ⚠️ **2026-10-02 订正：本条①的「四组各漂移 +11」结论已作废。** 以 `git show 549f74f` 逐锚点比对，漂移**不是任何单一常数** —— 同一文件内 `friendAdd` 方法头 **+18**、其查重 SELECT **+25**、`selectFriend` 方法头 **+33**、`getUserInfoById` 方法头 **+36**、`friendSelect` 方法头 **+45** 并存。成因是两个方向相反的改动叠加：B1 把两个读接口的方法体**搬走**（该减）、B2 给 `friendAdd` 外包 `runLocked` + 补注释（该增）。⇒ 凡引用这两族 `M:` 坐标，一律**读到即重测**，不做任何加减法换算。②（读路径已搬到 `FriendCacheService`）**不受影响**。详见 `cache-layer.md §11 ㊷`。

> 2026-09-27 校正：`goodsSelectHome` 原记 `G:182-202`（内层 `G:188`）有误——`G:188` 是 `for` 循环头不是查询，循环体在 `G:208` 闭合。其余三行原记的循环范围无误。缓存选型侧的同名清单见 `cache-map.md §6`（两节职责不同，**重叠 4 行**：`goodsSelectHome` / `conversations` / `friendSelect` / `selectFriend`；原记「3 行」漏了 2026-09-27 才补进 `cache-map.md §6` 的 `selectFriend` —— 2026-09-29 订正，与 `cache-map.md §6` 同句同改）。

## 7. 未覆盖 / 待确认

- ~~前端各页面的接口调用关系、14 个页面的实际路由路径~~ **已关闭（2026-09-30）**：前端源码已读，接口矩阵见 `api-contract.md §5`；路由路径见 `src/router/index.js`（7 条）。仍未定：前端**未**引入 Vuex/Pinia（`package.json` 无依赖、`src/` 无 `store/`），状态靠各页面的 `sessionStorage` + 组件内 `ref`。
- ~~`ImgPath.java` / `ImgConfig.java` 的静态资源映射规则~~ **已关闭**：两者均为 `@Configuration`；`ImgPath implements WebMvcConfigurer`，在 `addResourceHandlers` 中把 `/img/**` 映射到 `app.upload-dir`，并由 `@PostConstruct` 用 `Files.createDirectories` 自动建目录；`ImgConfig` 仅注册全局 `CorsFilter`。实测 `@WebMvcTest` 可加载 `ImgPath`（A2 的 `ImgMappingSmokeTest` 依赖此行为）。证据：`ImgPath.java:17`（`@Value` 读 `app.upload-dir`）、`:45-64`（`@PostConstruct initUploadDir()`，含 `Files.createDirectories`）、`:66-80`（`addResourceHandlers`，`:78` 为 `registry.addResourceHandler("/img/**")`）；`ImgConfig.java:9-12`。（**2026-10-02 实测**；原记 `:15-16` / `:23-27` / `:30-35` 已失效 —— `ImgPath` 于按域分层后从 `mallmanagement_server` 挪进 `shared/upload/` 并补了大段注释。）
