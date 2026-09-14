# 技术债清单 — Mall-management

> 本文件只收录**技术层面**的建议与欠账，不收表结构事实、不收业务决策。
> 相关文档：表结构事实 → `db-schema.md`；业务待裁决 → `business-decisions.md`；并发问题与改造优先级 → `concurrency-map.md` §7；缓存 → `cache-map.md`。

## TD-01 移除未使用的 `spring-boot-starter-data-jpa` 依赖

| 项 | 内容 |
|---|---|
| 现状 | `pom.xml:51` 引入 `spring-boot-starter-data-jpa` |
| 实际数据访问 | 全部走 `JdbcTemplate`（`spring-boot-starter-jdbc`，`pom.xml:40`） |
| 证据 | 全项目**不存在任何 `@Entity`**；`mallmanagement_class/info.java` 是唯一实体类，仅含 lombok 注解，无 JPA 注解，且未被任何类引用 |
| 问题 | 该依赖引入了 Hibernate 并激活了 `ddl-auto` 配置（`application.properties:12`），但项目没有任何 JPA 实体可供映射，依赖处于空转状态 |
| 影响 | ① 增大构建产物、延长启动时间；② 保留了 `ddl-auto=update` 的生效条件，一旦有人误加 `@Entity` 即会反向 `ALTER TABLE`（详见 `db-schema.md` §3） |
| 建议 | 移除该依赖；保留 JPA 但仅置 `ddl-auto=validate`/`none` 也可，两者是独立的处置手段，可只做其一 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-02 `.idea/` 与 Git 忽略规则

| 项 | 内容 |
|---|---|
| 现状 | `.idea/` 存在于 `Mall-management-server/.idea/`；**本目录不是 Git 仓库**，不存在索引 |
| 问题 | 根目录无 `.gitignore`；子项目 `.gitignore:17` 已包含 `.idea/`，忽略规则本身正确 |
| 建议 | 若启用 Git，在根目录新建 `.gitignore` |
| 优先级 | 低 |
| 状态 | 待确认 |

## TD-03 `agent.md` 回滚规则自相矛盾

| 项 | 内容 |
|---|---|
| 现状 | `agent.md` 数据库管理规则同时要求：① 每次变更登记回滚 SQL；② 禁止 `DROP COLUMN` / `MODIFY COLUMN` |
| 问题 | 对「新增字段」类变更，回滚必须 `DROP COLUMN`，与规则 ② 直接冲突。当前 `db-migration.md` 第 28 行采用折中方案（保留列 + 置 NULL），但这是**临时的，非正式规则** |
| 建议 | 修改 `agent.md` 规则：允许为「新增字段」类变更编写 `DROP COLUMN` 回滚 SQL，但限制其只在人工确认后执行；或明确「回滚 = 逻辑废弃 + 置 NULL」，不物理删除 |
| 优先级 | 中（当前无数据库变更，暂不阻塞） |
| 状态 | 待确认 |

## TD-04 CORS 重复配置与通配风险

| 项 | 内容 |
|---|---|
| 现状 | `ImgConfig.java` 注册全局 `CorsFilter`（`addAllowedOriginPattern("*")`），同时 3 个 Controller 各自标注 `@CrossOrigin` |
| 证据 | `ImgConfig:11-22`（全局 `CorsFilter`）；3 个 Controller 类级 `@CrossOrigin` |
| 问题 | ① 全局 CORS 与局部 CORS 重复配置；② `allowedOriginPattern("*")` + `allowCredentials(true)` 组合在生产环境有安全风险 |
| 建议 | 保留全局 `CorsFilter`，移除 3 个 Controller 的 `@CrossOrigin`；生产环境将 `allowedOriginPattern` 收敛为具体域名 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-05 静态资源路径依赖 `user.dir`，jar 部署时会失效

| 项 | 内容 |
|---|---|
| 现状 | `ImgPath.java:13` 和上传接口（`U:166`、`G:82`、`G:283`）均使用 `System.getProperty("user.dir") + "\src\main\resources\static\img\"` |
| 问题 | 开发期 `user.dir` = 项目根，路径有效；打成 jar 后 `user.dir` = 运行目录，`src/main/resources/static/img/` 不存在 |
| 影响 | ① 图片上传失败 ② `/img/**` 映射失效 ③ 部署到生产环境或换机运行立即暴露 |
| 建议 | 改为可配置路径（`application.properties` 配 `app.upload-dir`），或改用 `classpath:/static/img/` + 外部上传目录分离 |
| 优先级 | 高（部署级阻塞） |
| 状态 | 待确认 |
