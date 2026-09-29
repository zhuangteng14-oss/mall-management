# 项目地图 — Mall-management

> **素材范围**：3 个 Controller 全文、`mall_management_sql.sql` 全部 CREATE TABLE、`pom.xml` 的 `<dependencies>` 段、`application.properties`、目录树（已排除 `node_modules`/`dist`/`target`）。
> 前端源码、Service/Mapper 层**未读**。无证据处标「待确认」。

## 1. 定位与技术栈

| 项 | 值 | 证据 |
|---|---|---|
| 形态 | 前后端分离单体，客户 / 商家双端 | `user.usertype`、`login.Role` |
| 后端框架 | Spring Boot 3.4.5 | `pom.xml:8` |
| JDK | Java 17 | `pom.xml:30` |
| 服务端口 | 8088 | `application.properties:3` |
| 数据库 | 开发库 MySQL 8.0.32，库 `mall_management_sql`；A3 另有独立 VM 实例 8.4.11（库名**相同**，见 `environment.md`） | `mall_management_sql.sql:3` |
| 数据访问 | `JdbcTemplate` 直接写裸 SQL 字符串 | 3 个 Controller 均 `@Autowired JdbcTemplate` |
| 前端 | Vue 3 CLI 工程（Vue CLI 5 / webpack 5，`vue.config.js`、`babel.config.js`） | `package.json:12`（`vue: ^3.2.13`）、`:13`（`vue-router: ^4.6.4`，Vue Router 4 仅支持 Vue 3）、`:20`（`@vue/cli-service: ~5.0.0`） |

依赖实测（`pom.xml:32-68`）：`spring-boot-starter`、`-jdbc`、`-data-jpa`、`-web`、`-test`、`mysql-connector-java:8.0.33`、`lombok:1.18.46`。
**未引入**：Redis、Spring Cache、Caffeine、Redisson、AOP 增强包。

## 2. 目录结构

```
期末项目/
├── agent.md                                项目开发 Agent 规范（224 行）
├── mall_management_sql.sql                 11 张表 DDL + 种子数据
├── scripts/concurrency/                    A3 并发测试脚本（任务上下文见 context-packs/concurrency-test.md）
│   ├── ConcurrencyRunner.java              单文件：6 场景负载 + 断言 + preflight P0–P7 + JSON 报告（2270 行）
│   ├── run.sh                              两步编译（javac -encoding UTF-8 → java -cp）+ 驱动解析（Maven 回退）
│   └── reports/                            运行日志与 JSON 报告（已 .gitignore，不入库）
├── docs/agent/                             项目文档 9 份（含 db-migration.md、business-decisions.md），另有 context-packs/ 与 archive/
├── .github/workflows/ci.yml                GitHub Actions CI（3 job：backend 全量测试 + frontend 构建 + concurrency 并发脚本）
├── Mall-management-server/                 Spring Boot 后端
│   ├── pom.xml  mvnw  mvnw.cmd  HELP.md
│   ├── .idea/                              已被 Mall-management-server/.gitignore 忽略
│   └── src/
│       ├── main/java/com/minke/
│       │   ├── MallManagementApplication.java          启动类
│       │   ├── mallmanagement_class/info.java          唯一 POJO，未被任何类引用
│       │   ├── mallmanagement_server/ImgConfig.java    CORS 全局过滤器（注册 CorsFilter Bean）
│       │   ├── mallmanagement_server/ImgPath.java      /img/** → 本地 static/img/（ImgPath:15）
│       │   └── mallmanagement_controller/
│       │       ├── MallManagementUserController.java   /mysqlLogin
│       │       ├── MallManagementMessage.java          /message
│       │       └── MallManagementGoodsController.java  /mysqlGoods
│       ├── main/resources/application.properties
│       ├── main/resources/application-a3.properties    A3 profile：指向独立 MySQL 实例（同名库，TD-13）；含明文默认口令
│       ├── main/resources/static/img/                  8 张图片
│       ├── test/java/com/minke/mallmanagementserver/
│       │   ├── MallManagementApplicationTests.java      全上下文冒烟 + 真查库（`A3_ENABLED=true` 才跑，见 TD-10）
│       │   └── smoke/
│       │       ├── AbstractSmokeTest.java               smoke 基类（@WebMvcTest + 鉴权预留口子）
│       │       ├── LoginSelectSmokeTest.java            /mysqlLogin/loginSelect（只读）
│       │       ├── OrderPayIfUpSmokeTest.java           /mysqlGoods/orderPayIfUp（CAS 两分支）
│       │       └── ImgMappingSmokeTest.java             /img/** 静态资源映射
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

## 3. 后端架构：无分层

**主源 7 个 `.java`**（含启动类），**测试源 5 个**，共 12 个。32 个 HTTP 接口全部内联在 3 个 Controller 中，SQL 以字符串字面量形式写在方法体内。

- **不存在** Service 层、Mapper/DAO 层、MyBatis XML、DTO/VO 层。
- **不存在** 任何 `@Entity`——`mallmanagement_class/info.java` 是全项目唯一实体类，仅含 1 个 `imgName` 字段，且**未被任何类引用**（死代码）。
- 无统一返回体：`/mysqlGoods` 的 order 系列返回 `Map{success,...}`，`/mysqlLogin/loginSelect` 返回裸 `int`，`getUserInfo` 失败时返回 `null`（HTTP 200 空体）。
- 无鉴权、无拦截器、无全局异常处理；3 个 Controller 均标 `@CrossOrigin`。

接口分布：`/mysqlLogin` 5 个、`/message` 10 个、`/mysqlGoods` 17 个，详见 `api-contract.md`。

## 4. 前端结构

Vue 3 CLI 工程，14 个页面视图 + 1 个组件 + 1 个路由表。页面按角色/场景划分（登录、首页、商品、购物车、订单中心、聊天、好友、用户信息等）。**路由与页面内部实现未读，待确认。**

`Mall-management-web/package.json` 与 `Mall-management-web/mall-management-system/package.json` 并存，前者疑为空壳，实际工程根为后者。

## 5. 关键结论

| 结论 | 详见 |
|---|---|
| 全项目仅 1 处事务边界，多处多表写无事务保护 | `concurrency-map.md` |
| 订单三状态位用条件 UPDATE 实现 CAS，是全项目唯一正确的并发控制 | `concurrency-map.md` |
| `productsales.OrderVolume` 从未被更新，订单量统计功能失效 | `concurrency-map.md` |
| 全项目零缓存（**2026-09-14 时**；B1 自 2026-09-27 起逐笔落地，现行见右栏第二项） | `cache-map.md`（顶部有时效标注）/ `context-packs/cache-layer.md` |
| `ddl-auto=update` 与手写 DDL 脚本并存 | `db-schema.md` |
| 11 张表中仅 `message` 有二级索引 | `db-schema.md` |
| 测试：5 个可执行测试类（+1 抽象基类 `smoke/AbstractSmokeTest`）/ **11** 个用例（2026-09-29 实测）；`MallManagementApplicationTests` 的 **4** 个用例在未设 `A3_ENABLED` 时不执行，设 `true` 时真连库 | `tech-debt.md` TD-10（已关闭） |

> ⚠️ 时效口径：本表**逐行反映当前结论**；凡单元格内标注「（**…时**）」的，才是**该时刻**的值 —— 只有上面「零缓存」一行属于此列，其余各行**未受缓存迁移影响**（B1 只搬读路径，未动 DDL / 事务 / 订单逻辑），不必整体打折读。
>
> 上表「测试」行的原计数（6 / 2）是 **2026-09-20**（`e3a9ada` 写入，再上一版 `23dfaab` 2026-09-18 记 4 / 5）的值；B1 期间新增 `L2HealthProbeConcurrencyTest`（3 个用例）与 `messageControllerIsNotAopProxied`（1 个）等 ⇒ 现值已**就地改为 11 / 4**（2026-09-29 实测，与回归输出「Tests run 11」一致）。该行不在缓存主题内，与本笔合并只因**同一张表**：留旧数会让上一段「本表逐行反映当前结论」变成假话。

## 6. N+1 待优化清单（优先级：**高**）

以下接口在循环内逐条发起查询，**单次请求的 SQL 次数随数据量线性增长**。此处仅记录，**未改代码**。

行号约定（与 `cache-map.md §6` 一致）：`G:` = `MallManagementGoodsController.java`，`M:` = `MallManagementMessage.java`；查询记「SQL 定义行 → 执行行」区间，方法调用记调用行。

| 接口 | 文件行号 | 涉及表 | 优先级 |
|---|---|---|---|
| `POST /mysqlGoods/goodsSelectHome` | 全量查 `G:184-185`，循环 `G:188-208`，内层查询 `G:194-200` | goods → login | 高 |
| `POST /message/conversations` | 外层查 `M:33`，循环 `M:35-66`，内层查询 `M:48-50` | message → user_conversation_read | 高 |
| `POST /message/friendSelect` | 外层查 `M:320-322`，循环 `M:324-330`，内层调用 `M:326`（**均为搬迁前坐标**；2026-09-29 起整段在 `FriendCacheService.listFriends()`） | friend → user → customerimage/merchantimage | 高 |
| `POST /message/selectFriend` | 外层查 `M:249-251`，循环 `M:253-259`，内层调用 `M:255`（**均为搬迁前坐标**；2026-09-29 起整段在 `FriendCacheService.listRequests()`） | friend → user → customerimage/merchantimage | 高 |

注：后两行内层调用的 `getUserInfoById`（搬迁前 `M:341-380`）自身含 2 条 SQL（`M:342-344` 查 user，`M:360-361` / `M:364-365` 按 `usertype` 二选一查 `customerimage` / `merchantimage`），故每轮循环实际产生 2 条查询，复杂度为 **1 + 2N**。搬迁后该方法在 `FriendCacheService.userInfoById()`。

> 2026-09-29 校正（两件，勿混淆）：① **上表后两行的 `M:` 坐标原记 `M:309-311` / `M:238-240`（`getUserInfoById` 原记 `M:330-369`、`M:331-333`、`M:349-350`、`M:353-354`），实测**四组各漂移 +11**——本表已换成搬迁前真实值 `M:320-322` / `M:249-251`。证据：以 `git show 549f74f:MallManagementMessage.java` 逐行核对（该 commit 即本笔改动前状态），非推算。② 同日起这两处的读路径**已搬到 `FriendCacheService`**（B1 缓存笔），Controller 只剩路由。⚠️ **缓存把 1 + 2N 摊薄为「每 TTL 一次」，是掩盖而非修复** —— 与 `cache-layer.md` §11 附 `goodsHome` 行同款注记；解 N+1 只能改 SQL（JOIN / IN 批量），仍归 `fix-n-plus-one`。

> 2026-09-27 校正：`goodsSelectHome` 原记 `G:182-202`（内层 `G:188`）有误——`G:188` 是 `for` 循环头不是查询，循环体在 `G:208` 闭合。其余三行原记的循环范围无误。缓存选型侧的同名清单见 `cache-map.md §6`（两节职责不同，**重叠 4 行**：`goodsSelectHome` / `conversations` / `friendSelect` / `selectFriend`；原记「3 行」漏了 2026-09-27 才补进 `cache-map.md §6` 的 `selectFriend` —— 2026-09-29 订正，与 `cache-map.md §6` 同句同改）。

## 7. 未覆盖 / 待确认

- 前端各页面的接口调用关系、状态管理（是否用 Vuex/Pinia）、14 个页面的实际路由路径 —— 前端源码未读。
- ~~`ImgPath.java` / `ImgConfig.java` 的静态资源映射规则~~ **已关闭**：两者均为 `@Configuration`；`ImgPath implements WebMvcConfigurer`，在 `addResourceHandlers` 中把 `/img/**` 映射到 `app.upload-dir`，并由 `@PostConstruct` 用 `Files.createDirectories` 自动建目录；`ImgConfig` 仅注册全局 `CorsFilter`。实测 `@WebMvcTest` 可加载 `ImgPath`（A2 的 `ImgMappingSmokeTest` 依赖此行为）。证据：`ImgPath.java:15-16`、`:23-27`、`:30-35`；`ImgConfig.java:9-12`。
