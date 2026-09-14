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
| 验证步骤 | 1. 开发期不配 app.upload-dir，调 /mysqlLogin/updateAvatar 上传，确认落盘位置与改动前一致<br>2. 访问 http://localhost:8088/img/<文件名>，期望 200<br>3. mvn package 打 jar，在非项目根目录 java -jar 启动，指定 --app.upload-dir=<jar 外可写目录><br>4. 再次上传，确认文件落在配置目录且返回 URL 可访问<br>5. 重启进程后再次访问同一 URL<br>6. 回归 /mysqlLogin/getUserInfo，确认 image 字段格式不变<br>7. 回归 /mysqlGoods/goodsAdd、/mysqlGoods/goodsUp，确认 GoodImg 格式不变 |
| 优先级 | 高（部署级阻塞） |
| 状态 | 读侧已修复、写侧开发期已验证（1421a0c、c5d74b4），jar 场景待验证 |

## TD-06 图片 URL 前缀硬编码为 localhost:8088

| 项 | 内容 |
|---|---|
| 现状 | `U:186`、`G:105`、`G:294` 三处硬编码 `"http://localhost:8088/img/" + 文件名` |
| 问题 | 换机、容器化、走反向代理或改端口后，落库的 URL 全部不可用；且前缀散落三处，改一处必漏两处 |
| 建议 | 外置为 `app.upload-url-prefix`，三处统一注入 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-07 文件上传失败被静默吞掉

| 项 | 内容 |
|---|---|
| 现状 | `U:181-184` 与 `G:96-102` 用 `catch (Exception)` 捕获后仅 `System.out.println`，接口仍返回 `success:true` |
| 问题 | 目录不可写、磁盘满、路径不存在时，用户看到「上传成功」但文件未落盘，故障极难定位。**注意 `G:290-292`（`goodsUp`）未加 catch，异常会向上抛**——三处上传点行为不一致 |
| 建议 | 捕获后返回 `success:false` + 明确 `msg`，或至少改为 ERROR 级日志 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-08 开发期上传目录受 Git 跟踪

| 项 | 内容 |
|---|---|
| 现状 | 开发期上传目录 `src/main/resources/static/img/` 受 Git 跟踪，新上传图片会成为未跟踪文件。**已实测发生**：写侧验证上传头像后，`git status` 出现未跟踪文件（后经人工清理） |
| 问题 | 每轮开发验证都会污染 `git status`，易与真实代码改动混淆；且上传的用户图片被当作源码入库 |
| 建议 | 改用 `.gitignore` 白名单例外（先忽略 `*.jpg/*.png` 再 `!` 放行初始 8 张），或开发期上传目录与生产一致改为 jar 外 |
| 优先级 | 低 |
| 状态 | 实测发生，待处理方案 |

## TD-09 静态资源修复的 jar 部署场景待验证

| 项 | 内容 |
|---|---|
| 现状 | TD-05 读侧与开发期写侧均已验证；仅 jar 部署 + 外部 app.upload-dir 场景未验证 |
| 问题 | 打 jar 后以 `--app.upload-dir=<外部目录>` 启动、上传、重启访问的端到端路径未测 |
| 验证步骤 | 见 `docs/agent/tech-debt.md` TD-05 描述中的 7 步验证第 3、4、5 步 |
| 建议 | 首次打 jar 部署前完成验证；验证通过后与 TD-05 一起关闭 |
| 优先级 | 中 |
| 状态 | 待确认 |
