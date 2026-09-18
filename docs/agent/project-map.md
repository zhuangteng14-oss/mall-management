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
| 数据库 | MySQL 8.0.32，库 `mall_management_sql` | `mall_management_sql.sql:3` |
| 数据访问 | `JdbcTemplate` 直接写裸 SQL 字符串 | 3 个 Controller 均 `@Autowired JdbcTemplate` |
| 前端 | Vue 3 CLI 工程（Vue CLI 5 / webpack 5，`vue.config.js`、`babel.config.js`） | `package.json:12`（`vue: ^3.2.13`）、`:13`（`vue-router: ^4.6.4`，Vue Router 4 仅支持 Vue 3）、`:20`（`@vue/cli-service: ~5.0.0`） |

依赖实测（`pom.xml:32-68`）：`spring-boot-starter`、`-jdbc`、`-data-jpa`、`-web`、`-test`、`mysql-connector-java:8.0.33`、`lombok:1.18.46`。
**未引入**：Redis、Spring Cache、Caffeine、Redisson、AOP 增强包。

## 2. 目录结构

```
期末项目/
├── agent.md                                项目开发 Agent 规范（224 行）
├── mall_management_sql.sql                 11 张表 DDL + 种子数据
├── scripts/                                ← 预留，后续放验证脚本
├── docs/agent/                             项目文档 9 份（含 db-migration.md、business-decisions.md），另有 context-packs/ 与 archive/
├── .github/workflows/ci.yml                GitHub Actions CI（后端全量测试 + 前端构建）
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
│       ├── main/resources/static/img/                  8 张图片
│       ├── test/java/com/minke/mallmanagementserver/
│       │   ├── MallManagementApplicationTests.java      全上下文冒烟测试（@Disabled，见 TD-10）
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
| 全项目零缓存 | `cache-map.md` |
| `ddl-auto=update` 与手写 DDL 脚本并存 | `db-schema.md` |
| 11 张表中仅 `message` 有二级索引 | `db-schema.md` |
| 测试：4 个测试类 / 5 个用例，其中 1 个因缺 MySQL 被 `@Disabled` | `tech-debt.md` TD-10 |

## 6. N+1 待优化清单（优先级：**高**）

以下接口在循环内逐条发起查询，**单次请求的 SQL 次数随数据量线性增长**。此处仅记录，**未改代码**。

| 接口 | 文件行号 | 涉及表 | 优先级 |
|---|---|---|---|
| `POST /mysqlGoods/goodsSelectHome` | `G:182-202`（内层 `G:188`） | goods → login | 高 |
| `POST /message/conversations` | `M:35-66`（内层 `M:48-50`） | message → user_conversation_read | 高 |
| `POST /message/friendSelect` | `M:313-319` | friend → user → customerimage/merchantimage | 高 |
| `POST /message/selectFriend` | `M:242-248` | friend → user → customerimage/merchantimage | 高 |

注：后两行内层调用的 `getUserInfoById`（`M:330-369`）自身含 2 条 SQL（`M:331` 查 user、`M:349`/`M:353` 查头像），故每轮循环实际产生 2 条查询。

## 7. 未覆盖 / 待确认

- 前端各页面的接口调用关系、状态管理（是否用 Vuex/Pinia）、14 个页面的实际路由路径 —— 前端源码未读。
- ~~`ImgPath.java` / `ImgConfig.java` 的静态资源映射规则~~ **已关闭**：两者均为 `@Configuration`；`ImgPath implements WebMvcConfigurer`，在 `addResourceHandlers` 中把 `/img/**` 映射到 `app.upload-dir`，并由 `@PostConstruct` 用 `Files.createDirectories` 自动建目录；`ImgConfig` 仅注册全局 `CorsFilter`。实测 `@WebMvcTest` 可加载 `ImgPath`（A2 的 `ImgMappingSmokeTest` 依赖此行为）。证据：`ImgPath.java:15-16`、`:23-27`、`:30-35`；`ImgConfig.java:9-12`。
