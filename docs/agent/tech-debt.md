# 技术债清单 — Mall-management

> 本文件只收录**技术层面**的建议与欠账，不收表结构事实、不收业务决策。
> 相关文档：表结构事实 → `db-schema.md`；业务待裁决 → `business-decisions.md`；并发问题与改造优先级 → `concurrency-map.md` §7；缓存 → `cache-map.md`。⚠️ **口径注**：本文所有 `:<行>` 为 **2026-10-02 实测**；重构后须重测。用户域旧 `U:<n>` 仅保留在明确标注为「修复前 / 原记」的历史行。

## TD-01 空转的 `spring-boot-starter-data-jpa` —— ✅ **已关闭（B 方案，2026-10-04）**

| 项 | 内容 |
|---|---|
| 现状 | `pom.xml:51` 引入 `spring-boot-starter-data-jpa` —— **保留不删**（见「处置」） |
| 实际数据访问 | 全部走 `JdbcTemplate`（`spring-boot-starter-jdbc`，`pom.xml:40`） |
| 证据 | 全项目**不存在任何 `@Entity`**；原 `mallmanagement_class/info.java`（曾是全项目唯一 POJO，仅含 lombok 注解，无 JPA 注解，未被任何类引用）**已于 2026-10-02 笔 1 挪包时删除**（`01df702`）—— 故现在连「无注解的实体类」也没有了，本项证据比原记**更强** |
| 问题 | 该依赖引入了 Hibernate 并激活了 `ddl-auto` 配置（`application.properties:29`），但项目没有任何 JPA 实体可供映射，依赖处于空转状态 |
| 影响 | ① 增大构建产物、延长启动时间（**未处置，仍存在**）；② 保留了 `ddl-auto` 的生效条件，一旦有人误加 `@Entity` 即会反向 `ALTER TABLE`（详见 `db-schema.md` §3）—— ✅ **已由本笔消除** |
| 处置 | **B 方案：依赖保留，只改配置。** `spring.jpa.hibernate.ddl-auto` 由 `update` 改为 **`validate`**（`application.properties:29`）。**选 B 的理由**：① 全局硬性约束 2「禁止删除旧接口、旧字段、旧表、旧配置」（`agent.md:36`）已被项目**两次**明确套用到这个依赖上（`three-layers.md:121`、`:148` 的「保留不删」半句），为省构建体积破约束不值；② 本行「建议」栏原文自己就列了 B 这个等价手段；③ B 恰好关掉**真正的隐患**（影响 ②），而 A（删依赖）的代价是豁免约束 + 改 9 份文档 + 实测连带。⚠️ **为何是 `validate` 而不是 `none`**：两者都**不会**自动改表，差别只在误加 `@Entity` 之后 —— `validate` **启动即失败**（显式炸），`none` **静默通过**、要等运行期某条 SQL 报错才暴露，后者正是 TD-15「静默 / 断言落空」那一族要防的形态。 |
| 优先级 | 中 |
| 状态 | ✅ **已关闭（B 方案，2026-10-04）** —— 分支 `chore/jpa-ddl-validate`。**验收**：① 实测 `validate` 与 `update` **启动时机与失败处置完全一致**（都在启动期建池；库不可达时都只记 ERROR、不终止启动）⇒ 未引入新的启动失败模式，`application-a3.properties:14-17` 与 `concurrency-test.md:70` 的「不需要额外请求」**依旧成立**；② 全量 `./mvnw -B test` = **186 run / 0 fail / 0 error / 19 skipped**，与同日 `main` 基线（同机实测）**逐项相同**；③ 文档同步：19 处引用点一并订正（8 文件）。⚠️ **影响 ① 未处置**（依赖仍在 ⇒ 构建体积与启动时间不变），本项关闭的是影响 ②。 |

## TD-02 `.idea/` 与 Git 忽略规则 — ✅ 已关闭（原文有误，2026-09-20 更正）

| 项 | 内容 |
|---|---|
| 原文现状 | `.idea/` 存在于 `Mall-management-server/.idea/`；**本目录不是 Git 仓库**，不存在索引 |
| 原文问题 | 根目录无 `.gitignore`；子项目 `.gitignore:17` 已包含 `.idea/`，忽略规则本身正确 |
| 原文建议 | 若启用 Git，在根目录新建 `.gitignore` |
| 更正 1 | **「根目录无 `.gitignore`」是错的**。根 `.gitignore`（27 行）由 `9b87ab9` 引入，且**与本 TD 在同一个 commit 落盘**（`git show --stat 9b87ab9 -- .gitignore` = `27 +`）。不存在「记录时正确、之后过时」的时间窗，**本 TD 自诞生起即错** |
| 更正 2 | **「本目录不是 Git 仓库，不存在索引」是错的**。monorepo 初始化后 `Mall-management-server/` 属根仓库；`git ls-files Mall-management-server/.idea` 返回 0 条，是因被根 `.gitignore:14` 的 `.idea/` 规则忽略，**不是**「无仓库」。原句属「结果对、归因错」 |
| 更正 3 | 原文建议已于 `9b87ab9` 落地，无需再动作 |
| 结论 | 关闭。A3（本 feature）**只在根 `.gitignore` 末尾追加**两条 A3 临时产物规则（`scripts/concurrency/reports/`、`scripts/concurrency/classpath.txt`），**不新建、不覆盖** |
| 教训 | 「文件存在与否」类断言必须当场 `ls` / `git ls-files` 取证，不得沿用文档推断（`agent.md` 规则 9.1）。2026-09-20 抽检 TD-01/06/07/08/10/12/13 共 **7 条**，**仅本项有此类错误**（TD-12 行号、TD-13 计数均精确命中），tech-debt.md 不构成系统性失真 |
| 优先级 | — |
| 状态 | 已关闭（2026-09-20） |

> **附：验证忽略规则的正确姿势（避免测试假象）**
>
> `git check-ignore` 对**不存在**的路径会「假设类型」：**无尾斜杠假定为文件，有尾斜杠假定为目录**。故 `**/node_modules/`（尾斜杠 = 只匹配目录）对 `git check-ignore node_modules`（无斜杠）会报「未被忽略」——这是**命令假象，不是规则失效**。实测：
>
> | 命令 | 结果 |
> |---|---|
> | `git check-ignore --no-index -v node_modules` | 未命中（假象） |
> | `git check-ignore --no-index -v node_modules/` | ✅ `.gitignore:2:**/node_modules/` |
> | `git check-ignore --no-index -v some/nonexistent/node_modules/` | ✅ 命中（不存在的路径同样可靠） |
>
> 结论：**查目录规则一律带尾斜杠 + `--no-index`**。

## TD-03 `agent.md` 回滚规则自相矛盾

| 项 | 内容 |
|---|---|
| 现状 | `agent.md` 数据库管理规则同时要求：① 每次变更登记回滚 SQL；② 禁止 `DROP COLUMN` / `MODIFY COLUMN` |
| 问题 | 对「新增字段」类变更，回滚必须 `DROP COLUMN`，与规则 ② 直接冲突。当前 `db-migration.md` 第 28 行采用折中方案（保留列 + 置 NULL），但这是**临时的，非正式规则** |
| 建议 | 修改 `agent.md` 规则：允许为「新增字段」类变更编写 `DROP COLUMN` 回滚 SQL，但限制其只在人工确认后执行；或明确「回滚 = 逻辑废弃 + 置 NULL」，不物理删除 |
| 优先级 | 中（当前无数据库变更，暂不阻塞） |
| 状态 | ✅ **已关闭（2026-09-30）** |
| 关闭依据 | `agent.md` 规则 v2 正是按本项「建议」的**第二种做法**落地：把「回滚 SQL」定义为**逻辑废弃，不是物理删除**（数据库管理规则 4），并据此同步改了硬性约束 3 与数据库管理规则 2。规则落盘 commit **`f19a14e`**；实现侧登记见 `db-migration.md` 注意 1 / 6；同源业务议题 `business-decisions.md` **BD-02** 一并关闭 |

## TD-04 CORS 重复配置与通配风险

| 项 | 内容 |
|---|---|
| 现状 | `ImgConfig.java` 注册全局 `CorsFilter`（~~`addAllowedOriginPattern("*")`~~ ⚠️ **2026-10-06 订正（CORS 收窄笔）：origin 侧已改为字面量白名单** —— `config.addAllowedOrigin(<逐项>)`，键 `app.cors.allowed-origins`，地址见下行「证据」；**通配在机制上已写不出**：配置含 `*` ⇒ 启动期直接失败。完整口径 + 四档取证见 `progress.md` **§5-AH**）；⚠️ ~~同时 **5 个** Controller 各自标注 `@CrossOrigin`~~ **2026-10-04 订正：那 5 处已由 PR #34 `23f5361` 全部删除** ⇒ 现为 **0 处** |
| 证据 | `ImgConfig.java:67-79`（`@Bean corsFilter` 全体；**原记 `:11-22`** —— 本笔新增 3 个 import + 类 javadoc ⇒ 之后行号整体下移）+ `:88-110`（`applyAllowedOrigins`：解析 / 校验 / 启动期打印生效清单，本笔新增）；⚠️ ~~**5 处**类级 `@CrossOrigin`：`GoodsController:38` / `OrderController:24` / `UserController:42` / `MessageController:44` / `HealthController:37`（**2026-10-03 实测**）~~ —— **该 5 处已于 PR #34 `23f5361` 删除**，上列坐标记录的是**删除前**的位置（原记「3 个 Controller」，订单拆出后曾增至 5 处；用户域 `:20` → `:42` 是三层第三笔 `0c07e96` 重写 `UserController` 所致；消息域 `:18` → `:44` 是三层第四笔 `8ed5fc1` 重写 `MessageController` 所致） |
| 问题 | ① 全局 CORS 与局部 CORS 重复配置（**PR #34 前**）；② ~~`allowedOriginPattern("*")` + `allowCredentials(true)` 组合在生产环境有安全风险~~ ⇒ ⚠️ **2026-10-06 订正：② 已消解** —— origin 侧白名单化后，`allowCredentials(true)` **只对白名单 origin 生效**；`allowedHeader` / `allowedMethod` / `maxAge` **刻意未动**（收窄它们挡不住攻击者、却带来真回归面，理由见 §5-AH） |
| 建议 | 保留全局 `CorsFilter`（**PR #34 后已事实上唯一**）；⚠️ ~~移除 **5 个** Controller 的 `@CrossOrigin`~~ **已完成**（PR #34 `23f5361`）；⚠️ ~~**仍待办** —— 生产环境将 `allowedOriginPattern` 收敛为具体域名~~ ⇒ ✅ **已完成（2026-10-06，CORS 收窄笔）**：机制 = `addAllowedOrigin(String)` 逐条字面量（键 `app.cors.allowed-origins`；dev 值 = `http://localhost:8080,http://127.0.0.1:8080`）。⚠️ **生产部署时的唯一动作** = 设环境变量 `APP_CORS_ALLOWED_ORIGINS=https://你的域名`（**整体替换、不追加**）；**不设 = 拒绝全部跨源**（fail-closed + 启动期 WARN，进程照常起） |
| 优先级 | 中 |
| 状态 | ✅ **已关闭（2026-10-06，CORS 收窄笔）** —— **两面均关**：**重复配置面** ✅（PR #34 `23f5361` 删 5 处 `@CrossOrigin`，与本文件末节「公共层 笔 1/3 —— CORS」的记录一致）+ **通配风险面** ✅（本笔：origin 侧字面量白名单，配置含 `*` **启动即失败**；非白名单 origin 由 `DefaultCorsProcessor` **当场拒**，不是「只靠浏览器拦」）。口径 / 四档取证 / 未收窄项的理由见 `progress.md` **§5-AH**。⚠️ 原记「🟡 部分关闭（2026-10-04 订正）」及更早的「待确认」**均已成史料** |

## TD-05 静态资源路径依赖 `user.dir`，jar 部署时会失效

| 项 | 内容 |
|---|---|
| 现状 | **修复前行号**：`ImgPath.java:13`、`U:166`、`G:82`、`G:283`。修复后上述坐标已失效。⚠️ **2026-10-02 复测（原记的「现对应」也已再漂）**：三处 `Paths.get(uploadDir)` 现址 **`UserService.updateAvatar:246`**（原记 `U:172`；2026-10-02 三层第三笔前为 `UserController:185`，现已随方法体下沉）、**`GoodsService:96`**（原记 `G:88`）、**`GoodsService:265`**（原记 `G:289`）；`ImgPath` 的路径拼接已被 `@Value` 注入 + `Paths.get(uploadDir)` 取代（见 **`ImgPath.java:17`** 的 `@Value`、**`:48`** 的 `Paths.get` / **`:49`** 的 `Files.createDirectories`；原记 `:18` / `:25`）。原写法：`System.getProperty("user.dir") + "\src\main\resources\static\img\"` |
| 问题 | 开发期 `user.dir` = 项目根，路径有效；打成 jar 后 `user.dir` = 运行目录，`src/main/resources/static/img/` 不存在 |
| 影响 | ① 图片上传失败 ② `/img/**` 映射失效 ③ 部署到生产环境或换机运行立即暴露 |
| 建议 | 改为可配置路径（`application.properties` 配 `app.upload-dir`），或改用 `classpath:/static/img/` + 外部上传目录分离 |
| 验证步骤 | 1. 开发期不配 app.upload-dir，调 /user/updateAvatar 上传，确认落盘位置与改动前一致<br>2. 访问 http://localhost:8088/img/<文件名>，期望 200<br>3. mvn package 打 jar，在非项目根目录 java -jar 启动，指定 --app.upload-dir=<jar 外可写目录><br>4. 再次上传，确认文件落在配置目录且返回 URL 可访问<br>5. 重启进程后再次访问同一 URL<br>6. 回归 /user/getUserInfo，确认 image 字段格式不变<br>7. 回归 /goods/goodsAdd、/goods/goodsUp，确认 GoodImg 格式不变 —— ⚠️ **例外（A 批 `efe7959` 策略 C）**：同名**不同内容**时，落盘名与 URL 尾段为 `<原名>_<8hex><扩展名>`，`GoodImg` 格式**有意**变化，不是回归。同名同内容仍复用原名，格式不变 |
| 回归保护 | 读侧的 `/img/**` 映射已由 `ImgMappingSmokeTest`（A2 新增）纳入 CI 覆盖，含「已存在文件 200 且内容非空」与「不存在文件 404」正反两条断言，可防 TD-05 读侧回归。jar 场景仍无覆盖（见 TD-09） |
| 优先级 | 高（部署级阻塞） |
| 状态 | 读侧已修复、写侧开发期已验证（1421a0c、c5d74b4），jar 场景待验证 |

## TD-06 图片 URL 前缀硬编码为 localhost:8088

| 项 | 内容 |
|---|---|
| 现状 | `U:192`、`G:111`、`G:300` 三处硬编码 `"http://localhost:8088/img/" + 文件名`。⚠️ **2026-10-02 复测：三处现址** `UserService.updateAvatar:266` / `GoodsService:120`（`goodsAdd`）/ `GoodsService:283`（`goodsUp`），`U:192` / `G:111` / `G:300` 均已失效（用户域那处：`U:192` → 三层第二笔前 `UserController:205` → 第三笔后 `UserService.updateAvatar:266`） |
| 口径补充（2026-09-20） | **本 TD 以「生成落库 URL」口径 = 3 处为准**（`U:192`、`G:111`、`G:300`，即上式；现址见上栏）。另按「出现 `localhost:8088` **字面量**」口径为 **4 处**：多出 `U:55` 的 `@RequestParam(value = "image", defaultValue = "http://localhost:8088/img/default_user.png")`（入参默认值，非生成 URL，但同属换机即失效的硬编码；**2026-10-02 复测仍在，现址 `UserController.loginAdd:63`**）。**修复时需一并处理该处** —— 只改这 3 处则修复不完整，下方「改一处必漏两处」实际是**漏三处** |
| 问题 | 换机、容器化、走反向代理或改端口后，落库的 URL 全部不可用；且前缀散落三处，改一处必漏两处 |
| 建议 | 外置为 `app.upload-url-prefix`，三处统一注入 |
| 优先级 | 中 |
| 状态 | 待确认 |

## TD-07 文件上传失败被静默吞掉

| 项 | 内容 |
|---|---|
| 现状 | ~~`U:187-190` 与 `G:102-108` 用 `catch (Exception)` 捕获后仅 `System.out.println`，接口仍返回 `success:true`~~ ✅ **已修复（A 批 `efe7959`）**：三处上传点统一为**写库之前**返回 `{success:false, msg}` —— 站点① `goodsAdd` / ② `updateAvatar` 原为吞异常继续写库，站点③ `goodsUp` 原为异常冒泡（500），现三处行为一致，不再有 500 也不再有静默脏数据 |
| 问题 | ~~目录不可写、磁盘满、路径不存在时，用户看到「上传成功」但文件未落盘，故障极难定位。**注意 `G:296-298`（`goodsUp`，现址 `GoodsService:273-281`）未加 catch，异常会向上抛**——三处上传点行为不一致~~ **（已随上条修复：三处行为一致，均为写库前显式失败）** |
| 补充（2026-09-20） | **异常消息把失败原因归错了**：两处 catch 都打印「已存在」。`G:106-108`（现址 `GoodsService:110-117`）→ `System.out.println("商品图片已存在，继续上架")`；`U:187-190`（现址 `UserService.updateAvatar:257-264`）→ 注释「就算保存失败，也不崩溃！」+ `System.out.println("文件已存在，继续更新头像")`。但真正的「已存在」已被紧邻上方的 `if (!saveFile.exists())` 拦下，**根本不会进 catch** —— 故 catch 分支里的「已存在」归因**几乎总是错的**（磁盘满 / 目录不可写 / 路径非法一律被说成「已存在」），排查方向被彻底带偏。属「结果对、归因错」在生产代码中的实例，与 TD-02 的错误类型同源<br>**2026-09-30 补**：修复后 catch 分支不再打印「已存在」，改为「商品图片保存失败」/「头像保存失败」+ `printStackTrace()`。⚠️ **仍然没修**的是日志本身 —— `printStackTrace` + 无 logger 级属**病 D**（`defect-survey.md` 病 D「`printStackTrace()` × 20」行），不在本笔范围 |
| 建议 | 捕获后返回 `success:false` + 明确 `msg`，或至少改为 ERROR 级日志 |
| 优先级 | 中 |
| 状态 | ✅ **已修复（A 批 `efe7959`）**；残留：异常日志仍为 `printStackTrace`（归病 D） |

## TD-08 开发期上传目录受 Git 跟踪

| 项 | 内容 |
|---|---|
| 现状 | 开发期上传目录 `src/main/resources/static/img/` 受 Git 跟踪，新上传图片会成为未跟踪文件。**已实测发生**：写侧验证上传头像后，`git status` 出现未跟踪文件（后经人工清理） |
| 问题 | 每轮开发验证都会污染 `git status`，易与真实代码改动混淆；且上传的用户图片被当作源码入库 |
| 建议 | 改用 `.gitignore` 白名单例外（先忽略 `*.jpg/*.png` 再 `!` 放行初始 8 张），或开发期上传目录与生产一致改为 jar 外。**补充（2026-09-18）**：该白名单只覆盖 `*.jpg`/`*.png` 两种扩展名，而 `src/main/resources/static/img/` 下 `b000602fe1f47af8e0e4747ae1b3338c_720.jpeg` 的扩展名是 `.jpeg`，不在放行范围内，方案落地时需一并覆盖 |
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

## TD-10 `contextLoads()` 在无 MySQL 环境跑不通 — ✅ 已关闭（2026-09-20，D1 落地）

| 项 | 内容 |
|---|---|
| 原文现状 | `MallManagementApplicationTests.contextLoads()` 依赖 JPA + `ddl-auto`，**推测**需要真实 MySQL 连接 |
| 原文问题 | CI（无 MySQL）上该测试会红，整个 `mvn test` 失败 |
| 原文处置 | 加 `@Disabled` 临时规避（`c3cd9f6`），Web 层覆盖由 `smoke/` 包承担；未改 `@WebMvcTest`，因会与 smoke 层重复且丢掉全上下文覆盖 |
| **实测推翻原文前半段** | 「无 MySQL 会失败」**是错的**。三个阴性探针实测（数据见 `99206a5` 的 commit message、`worklog-2026-09-20-a3.md` §缺陷记录 4）：**库名不存在（服务器可达）**与**端口无监听（完全无库）**两种情况**都是整个类 `Failures 0` / BUILD SUCCESS**。根因 TD-01 —— `ddl-auto=update` 的 schema 管理是「尽力而为」（连不上只记 ERROR 日志、不终止启动），且本项目**无任何 `@Entity`**、本就无 schema 可导出；两条叠加，启动路径上**没有任何一处「连不上就必须失败」**。故 `@Disabled` 当初的理由本身不成立 |
| 为什么必须补第二条 | 若 D1 只做「`@Disabled` 改环境开关」，CI 会多出一条**「库好不好都绿」**的步骤 —— 与 TD-15（以响应体判成功）、`recLoad()` 死代码同属「断言落空」那一族 |
| 处置（D1，方案 A） | ① `@Disabled` → `@EnabledIfEnvironmentVariable(named="A3_ENABLED", matches="true")`（开关置于类上，将来新增方法不会漏）；② 新增 `databaseIsReachable()`：`JdbcTemplate` 跑一条 `SELECT 1`，**强制真连**。加后探针 2/3 分别变成 `Errors: 1` 与 BUILD FAILURE，且报错**只**指向 `databaseIsReachable` |
| 刻意的克制 | 两个方法职责分开、不合并（`contextLoads` 证明「全上下文能启动」—— smoke 是 `@WebMvcTest` 切片，从不启动完整上下文，这一条本身仍有价值）；**只断言可达性、不断言 `VERSION()` 前缀**（本地开发库是 8.0.32，断言 8.4 会让它在本地变红；版本门禁由 A3 脚本 P1 承担）；未动 `pom.xml`、未动任何生产 profile、未引依赖 |
| CI 接法 | `concurrency` job 加一步：`A3_ENABLED=true ./mvnw -B -Dtest=MallManagementApplicationTests test`。**backend job 不设该变量** —— 那里全量 `mvn test` 自动跳过本类（跳过不是失败），backend 仍零 MySQL |
| 优先级 | — |
| 状态 | 已关闭（2026-09-20） |

## TD-11 前端依赖存在已知漏洞

| 项 | 内容 |
|---|---|
| 现状 | `npm ci` 报告 36 个依赖漏洞：2 critical / 14 high / 18 moderate / 2 low |
| 问题 | 含 2 个 critical、14 个 high，可能影响安全性；当前不影响功能运行 |
| 建议 | 先跑 `npm audit` 看详情，按 severity 分级；critical/high 优先，考虑 `npm audit fix` 或升级依赖 |
| 优先级 | 中（有 critical 和 high） |
| 状态 | 待确认（建议时机：A3 完成后、管理端开发前） |

## TD-12 mall_management_sql.sql 自带建库语句

| 项 | 内容 |
|---|---|
| 现状 | SQL 文件第 15 行 CREATE DATABASE、第 17 行 USE 硬编码库名 mall_management_sql |
| 问题 | 导入到其他库时必须先过滤，实测已触发（A3 前置准备首次执行失败） |
| 建议 | 长期拆分：一份"仅 Schema"供测试，一份"Schema + 建库"供生产 |
| 优先级 | 低 |
| 状态 | 待确认 |

## TD-13 SQL 硬编码 schema 名，导致测试库隔离失效

| 项 | 内容 |
|---|---|
| 现状 | SQL 硬编码 schema 限定名 `mall_management_sql.` **39 处**（2026-10-02 逐处复核；口径 = **SQL 字面量**），已散到 **7 个文件**：`UserDao` 16 / **goods 14** = `GoodsDao` 6 + `GoodsCacheService` 5 + `GoodsTypeCacheService` 3 / **order 9** = `OrderDao` 7 + `OrderStateMachine` 1 + `OrderTimelineWriter` 1；`MessageDao` **0 处**（2026-10-03 三层第四笔 `8ed5fc1` 后口径修正：原记 `MessageController` 0 处 —— 本域 SQL 已全数下沉 `MessageDao`，该文件**仍 0 处**；本笔把原先逐字相同的一对 SQL 合并，**39 口径总数不变**）。⚠️ **总数与原记的 39 相等、分布全变**：开题时集中在 2 个 Controller（`git show 9b87ab9` 实测 = Goods 23 + User 16），而 goods 那份 23 里含**订单端点的 SQL 9 条** ⇒ 订单笔拆出后 **23 = goods 14 + order 9**，文件数 **2 → 7**。⚠️ 本行曾一度记为 **45 处** —— 那是**口径混用**：`GoodsDao` 按字面量取 6，`OrderDao` / `OrderStateMachine` / `OrderTimelineWriter` 却把 javadoc / 注释里的提及一并数了进去。改用统一口径 `grep -o "mall_management_sql\."` 连注释数得 **50 处**（2026-10-03 实测；多出的 **11 处**全在注释行：`OrderDao:22/25/26`、`GoodsDao:33`、`OrderStateMachine:113`、`OrderTimelineWriter:45/56`、`UserDao:20/31`、`MessageDao:36/225` —— 后两项用户域 +2 系三层第三笔 `0c07e96` 新写 `UserDao` 类 javadoc 引入，消息域 +2 系本笔新写 `MessageDao` 类 javadoc 引入；原记「46 处 / 7 处注释」漏记了这 4 处）。逐处分类见 `three-layers.md §F#1`。各 Controller / DAO 共用自动装配的 `JdbcTemplate` Bean，差异只在 SQL 字符串 |
| 问题 | 切换 `spring.datasource.url` **无法**把 `/goods/*`、`/order/*`、`/user/*` 的读写切到其他库——服务连上测试库，SQL 仍定向到 `mall_management_sql`。又因 `/message/*` 不带前缀，同一进程内会**脑裂**：`/message` 的写入跟随 datasource，`/goods`、`/order`、`/user` 的写入留在 `mall_management_sql` |
| 实测触发 | A3 前置准备按 context-pack 建 `mall_management_test` 并导入成功后，隔离对 `/goods`、`/order`、`/user` 实际无效。`orderAdd` 压测数据会落入开发库，而 `order` 表无删除接口，HTTP 层无法清理 |
| 影响 | ① 一切依赖「切库做隔离测试」的方案失效；② 压测/集成测试污染开发库；③ 多实例部署时无法让实例指向不同库 |
| 建议 | **方案 A**：移除 SQL 字符串中的 `mall_management_sql.` 前缀改用非限定表名（由 datasource 决定库），彻底但需改 **39 处**（2026-10-02 实测；口径 = SQL 字面量，见「现状」行）并全量回归。⚠️ **两笔 DAO 重构的裁决都是「逐字保留前缀、不顺手清」**（`OrderDao` / `GoodsDao` 类 javadoc 有理由：只清一域会造出「一半有前缀、一半没有」的中间态）⇒ 方案 A 若要落地，必须是**跨四域的单独一笔**，不能夹带在任何一次分层搬运里；**方案 B**（A3 已采用）：保留硬编码，测试侧一律改用**独立 MySQL 实例**（库名仍为 `mall_management_sql`），绕开该问题 |
| 与 TD-12 的关系 | TD-12 是 `mall_management_sql.sql` 自带建库语句；本项是 Java 源码硬编码库名。同属「库名硬编码」的两个面，触发点与修法不同，分列 |
| 优先级 | 高（静默污染开发库，且在本任务中已实际触发一次流程失败） |
| 状态 | 待确认（A3 采用方案 B 绕过，不阻塞当前里程碑；是否做方案 A 待定） |

## TD-14 `order.OrderId` 无索引，三个 CAS 端点全表扫

| 项 | 内容 |
|---|---|
| 现状 | `SHOW INDEX FROM mall_management_sql.order` 实测**只有 `PRIMARY(Id)` 一个索引**，`OrderId` 上无任何索引。`orderPayIfUp` / `orderDeliveryIfUp` / `orderReceiveIfUp` 三个 CAS 端点的 `WHERE` 均以 `OrderId = ?` 起头（后接 `PayIf` / `DeliveryIf` / `ReceiveIf` 状态位） |
| 问题 | 无索引 → 每次 CAS 都是**全表扫**，扫描成本随 `order` 行数近线性增长；并发下 32 线程各自全表扫，退化被放大。表越大，CAS 端点的可用容量越低 |
| 实测取证 | ① `EXPLAIN` 与 CAS 完全同款 WHERE：`type=ALL`、`possible_keys` 空、`Extra=Using where`；② 只读对照（16 线程 × 25 次，只变行数这一个变量）：**6 行 p50=1ms → 6006 行 p50=51ms → 12006 行 p50=94ms**（12000 填充行测后已删，`order` 回到 6 行） |
| 实测触发（本地 VM） | A3 满负载档（120 QPS × 60s 混合负载）期间 `order` 表约 1 万行（含 S4b 预建池 3600 + 运行中插入 2880 + 同轮未清数据）：**achieved=63.0 QPS、p50=496.1ms**；S4b 长档（60s、从 6 行起）**achieved=96.2 QPS、p50=292.4ms**。⚠️ 早期报出的 68.3 QPS / p50 398.9ms 出自**背压之前**的负载发生器（开环提交 + 无界队列，`achieved` 被算成排空期均值）—— 63.0 不是变差，是变准 |
| 对照实测（CI 首跑，2026-09-21） | CI #8 报告的同一档 S4b：**achieved=119.9 QPS、p50=6.3ms**。同一份 SQL、同一后端、同一断言集，本地 VM 的 p50 是 CI 的 **46–79 倍**（496.1 / 6.3 = 79×，292.4 / 6.3 = 46×） |
| 影响 | ① **慢**：无索引 → 每次 CAS 全表扫，扫描成本随 `order` 行数近线性增长，并发下 32 线程各自全表扫，退化被放大（只读对照：6 行 p50=1ms → 12006 行 p50=94ms）；② 与 §5 行 9 同一索引缺失的**性能面** |
| **定性修正（2026-09-21，CI 首跑带回）** | **「120 QPS 不可达」是本地环境的属性，不是索引缺失本身造成的** —— CI 上同份数据可达 119.9 QPS。TD-14 的性能影响**确凿**（本地 p50 292–496ms vs CI p50 6.3ms），但写成「A3 目标不可达」是归因错误：本地达不到 120 QPS 还叠加了 VM 的网络 / 磁盘瓶颈。索引缺失的代价要用**同一环境内**的行数对照来证（见上「实测取证」②），不能拿跨环境的达成率当证据 |
| 与并发缺陷的关系 | `concurrency-map.md §5` 第 9 行**原**记的是 `OrderId` 无**唯一**索引 → 同 id 可重复插入（**正确性面**，由 A-S2a-1 证明）；本项是同一索引缺失的**性能面**（全表扫）。修法部分重合（先加普通索引即解性能，唯一索引另需先清重复行），验收口径不同，分列。**2026-09-30 S1a 后两面同一次落地**：`uk_order_orderId` 既建索引（性能面 ✅）又唯一（正确性面 ✅），`concurrency-map.md §5` 第 9 行已同步标记「已消除」（`1e2fcfa`） |
| 建议 | ~~给 `order.OrderId` 建普通 B-tree 索引即可消除扫描成本；**A3 不加**：`mall_management_sql.sql` 不得修改~~ → ✅ **已实施（2026-09-30 S1a）**：直接建成 **UNIQUE** `uk_order_orderId`，一次解决性能面与正确性面（原「`.sql` 不得修改」的前提已被规则 v2 取代 —— 规则 v2 现**无条件允许**新增索引） |
| 优先级 | 高 |
| **落地（2026-09-30，S1a-1）** | commit **`1e2fcfa`**；索引名 **`uk_order_orderId`**（UNIQUE，`mall_management_sql.sql:245`，文件末尾 Migration 块 `:236-245`）。性能面与正确性面**同时**落地 |
| **落地验证** | VM：重导 12 表 → `SHOW INDEX FROM mall_management_sql.\`order\`` 得 `uk_order_orderId` `Non_unique=0`；`order.OrderId` 重复行检测 0（原始 / `TRIM` / 空串 / NULL 四项）。A3 全量 **27 项门禁 0 失败 / exitCode 0**（`A-S2a-1a` 增量=20、`A-S2a-1b` rows=1、`A-S2a-1c` 「数据库错误」=0）。CI（PR #14）同结果，且 `A-S2a-1a` 实测 `before=0 after=20` —— **冷启动 404 分支同时被覆盖** |
| 状态 | ✅ **已修复（2026-09-30，`1e2fcfa`）**（原「待确认（A3 登记，不加索引）」，**定性已于 2026-09-21 修正**，见上两行） |

## TD-15 `loginAdd` 在 `usertype` 非法值时返回「添加成功！」但不插任何行

| 项 | 内容 |
|---|---|
| 现状 | `UserController.loginAdd` 的 `usertype` 分支只识别「客户」/「商家」两个值；取到其它值时既不插 `login` 也不插 `user`/`customerimage`，方法仍返回字符串 `添加成功！` |
| 问题 | **返回值与实际行为不符**：调用方无法区分「真的插入了一行」和「参数非法、被静默丢弃」。前端若拼错 `usertype`（或传空）会看到成功提示，而后台无任何数据落库，故障对使用者和日志都不可见 |
| 实测发现 | A3 的 B-2 场景期间连带发现。用 curl 单独复现（该请求未带 `charset`）：响应为 `添加成功！`，而 JDBC 侧 `login` 行数**不变** |
| 影响 | ① 数据静默丢失；② 掩盖前端调用错误；③ 使「以响应体判成功」的测试口径在本端点上失真 —— A3 的 B-2 因此不能只用 `添加成功！` 计数，必须同时用 JDBC 核对行数（已如此实现） |
| 与 TD-07 的关系 | TD-07 是「文件上传失败被静默吞掉」（异常被吞）；本项是**没有异常、正常返回里的语义谎言**。同一类「失败不可见」，成因不同，分列 |
| 建议 | 二选一：① 校验 `usertype` 枚举，非法值返回明确错误（HTTP 4xx 或非成功响应体）；② 保留成功语义但补插默认类型。倾向 ① —— 静默降级比报错更难排查 |
| 优先级 | 中 |
| 状态 | 待确认（A3 登记，不在 A3 修） |

## TD-16 `--reset-db` 是死参数：声明了破坏性语义，实际什么都不做

| 项 | 内容 |
|---|---|
| 现状 | `ConcurrencyRunner` 的 `--reset-db` 全文件只出现 5 处，全是「声明（`Opt.resetDb`）/ 解析（`case "--reset-db"`）/ 启动横幅 / 写进 JSON 报告（`options.resetDb`）/ HELP 文字」，**没有任何执行路径** —— 不打开 `mall_management_sql.sql`，不执行任何 `DROP`，不碰数据库。 |
| 问题 | 不是「少个功能」，是**谎报做了**：库脏了传 `--reset-db`，横幅打 `resetDb : true`、报告也写 `true`、退出码 0，操作者会以为库已重置，实际一行没动；下一次运行 P4 报「库脏」，成因完全不可见。与 `--report-dir`（修复前）、`recLoad()` 同类，但更坏：前两者是「该做的没做」，本项是「声称做了」。 |
| 实测发现 | 2026-09-20 设计 CI 集成时核对，方式是**全文件大小写不敏感搜 `reset`**（得 5 处，逐处读过，无一是读取）。⚠️ 方法论要点：不能只数 `o.<字段>` 的出现次数 —— `Opt` 内的访问器读字段时**不带 `o.` 前缀**（如 `--scenarios` 由 `o.has(...)` 消费，字段名本身只有 2 处命中，会被误判为死参数）。判死参数必须读那几行本身，且要覆盖类内访问器这一形态。 |
| 影响 | ① 唯一的「重置库」手段实际不存在；② 危险语义的空转比缺失更坏，它掩盖了「该清库了」这一事实；③ 差点被写进 CI：设计稿最初假设 CI 用 `--reset-db` 供数，若照此落地，CI 会跑到空库上、在 P4 红，且原因难查（已改为用容器自带 `mysql` 客户端导入）。 |
| 与 TD-12 的关系 | TD-12 记录 SQL 文件自带 `CREATE DATABASE` / `USE`（导入其他库需过滤）；本项是「执行这个文件的那条路径根本不存在」。故库重置至今一直是 VM 上手动执行 S0-1 定稿命令，不是脚本能力。 |
| 建议 | 三选一：① 实现它（读 SQL、按语句切分、执行 11 条 `DROP TABLE IF EXISTS` 后重建）—— 要动 runner 的执行路径，且把 11 条 DROP 交给自动化脚本执行，风险高于其便利；② **删掉这个参数**（连 HELP 说明一并删），让「清库只能手动」成为明确事实；③ 至少在 HELP 与 `--help` 里标注「未实现」。**倾向 ②** —— 一个不工作的破坏性开关，不如没有。**2026-09-30 裁决改选 ③**：② 撞 `agent.md:36-37`「禁止删除旧接口、旧字段、旧表、旧配置」，收益小（只清一个死参数）成本高；③ 成本 ≈ 0。 |
| 优先级 | 中 |
| 附带发现（2026-09-30） | **同一行 HELP 文本还有第二处错**（下引为**原文**，已修）：`  --reset-db   重跑 mall_management_sql.sql（DROP 全部 11 表！仅限一次性实例）`。库现有 **12** 张表，而全文件的 `DROP TABLE IF EXISTS` **语句**只有 **11** 条（`order_state_transition` 不在其中，见 `defect-survey.md §三 病 G`）⇒ 文案里「**全部**」与「**11**」两个说法都不成立，应为「12 张表中的 11 张，且不覆盖 `order_state_transition`」。实测：`DROP TABLE IF EXISTS` **语句 11 条**、`CREATE TABLE` 12 个。⚠️ 不要用 `grep -c 'DROP TABLE IF EXISTS' mall_management_sql.sql` 去核这个数 —— 它会返回 **12**：第 12 处是 S1a 追加块 `:242` 的**注释**里引用了这个短语（「本文件头部含 DROP TABLE IF EXISTS `order`」），不是语句。修复点 **`ConcurrencyRunner.java:181`**（代码文件）—— ✅ **已修（2026-09-30 S1a-3 笔 2，`76b6679`）**：文案改为「DROP 11 张 —— 12 张中除 `order_state_transition` 外」。⚠️ **只修了表数** —— `--reset-db` 仍是死参数（横幅照样打 `resetDb : true`、报告照样写 `true`），**TD-16 主体未动**，选项 ② 删参数 **未采纳**（撞 `agent.md:36-37`「禁止删除旧接口、旧字段、旧表、旧配置」），**选项 ③「标未实现」已采纳（2026-09-30）**：`--help` 那一行现为「（未实现，TD-16）重跑 mall_management_sql.sql（DROP 11 张 —— 12 张中除 `order_state_transition` 外；仅限一次性实例）」，与本表下行「状态」同时标注 —— 但**只是标注**，`--reset-db` **仍是死参数** |
| 状态 | ⚠️ **行为层面仍未实现** —— **选项 ③ 已采纳（2026-09-30）**：`--help` 与本节均标注「未实现」，消除「谎报做了」的误导；但 `--reset-db` **仍是死参数**（横幅照样打 `resetDb : true`、报告照样写 `true`、退出码 0）。原文（已过期）：「待确认（A3 登记；不在 A3 修，也不在 CI 集成阶段实现）」；其中「上一行的文案漂移同样未修」已于 S1a-3 笔 2（`76b6679`）修。 |

## TD-17 `orderAdd` 不校验三个状态位：客户端可直接伪造终态订单

| 项 | 内容 |
|---|---|
| 现状 | `OrderController.orderAdd` 以 `int` 直接接收 `PayIf` / `DeliveryIf` / `ReceiveIf`，经 `OrderService.orderAdd` 原样绑定进 `INSERT ... INTO mall_management_sql.order`，**从入参绑定到落库均无任何取值校验** —— 既无 `{0,1}` 白名单，也无「新建订单只能是初始态」的约束。`rows > 0` 即返回 `success: true`。 |
| 问题 | 三个状态位是**写入方的自选值**，不是状态机的输出：调用方可直接插入一条 `(1,1,1)` 的「已付款 + 已发货 + 已收货」订单，全程不经过付款 / 发货 / 收货。三个 CAS 端点（`orderPayIfUp` 的 `AND PayIf = 0`；`orderReceiveIfUp` 的 `AND PayIf = 1 AND ReceiveIf = 0`；`orderDeliveryIfUp` 的 `AND PayIf = 1 AND DeliveryIf = 0 AND ReceiveIf = 0`）防的是**并发重复流转**，其前提「必须先处于前一状态」在 `orderAdd` 这里根本不存在 —— 于是状态机可以从入口被绕过。 |
| 实测发现 | H3 的 S4a 场景即以此构造前置态（用 `orderAdd` 直接造 `(1,1,1)` 终态订单）—— 一个**测试**手段，反过来说明**生产接口**具备同一能力。⚠️ **2026-10-04 更新（C#6）**：这句话的**前半已不再成立** —— S4a 经本笔改造后，5 个前置态里只有 `(0,0,0)` 与 `(1,0,0)` 走 `orderAdd` 出生，`(1,1,0)`/`(1,1,1)`/`(1,0,1)` 三个改由**合法流转**推进（见「状态」行）；**后半仍然成立**（改造前它确实说明生产接口有同一能力）。旁证（同一方法内，一并可读）：`Price` 是 `float` 且不校验正负（**该条已独立分级**，见 `defect-survey.md` 病 B「服务端零价格校验」——此处保留，因它是本条「伪造状态位」的**同方法实例**，搬走会削弱论证）；`OrderId` 不查重（即 TD-14 / B-1 的 `order.OrderId` 无唯一索引 —— **该条 2026-09-30 S1a 已修**，见 TD-14 落地行）。另：`SettlementDate` **不在** `orderAdd` 的列清单里，故伪造出来的终态单该列为空 —— 与 BD-01「由付款 / 发货 / 收货三处覆写」的假设不一致。 |
| 影响 | ① 业务上可凭空造出「已收货」订单，影响对账与报表口径（连带上一条的 `SettlementDate` 为空）；② 绕过 BD-01 所依赖的流转时序；③ **A3 的 A 组「无非法状态流转」断言只在并发路径上成立**，与本次入口的伪造能力无关 —— 故 A3 不会因它变红。这一点与 TD-15 同因：**都是 A3 的断言口径盖不到的地方**，也是它们至今未被发现的原因。 |
| 与 TD-15 的关系 | TD-15 是「返回成功但什么都没做」（静默说谎）；本项是「老老实实做了不该做的事」（缺校验放行非法输入）。两者同属「端点对输入不做校验」，一个不作为、一个乱作为，故分列。 |
| 建议 | ① 新建订单只允许初始态：三个状态位**不接收客户端传值**，一律写 0（或直接在 SQL 里写常量）；若确需导入历史订单，另开一个带鉴权的管理端点。② 顺带补 `PayIf/DeliveryIf/ReceiveIf ∈ {0,1}` 与 `Price >= 0` 的校验（当前 `int` 连 2 都收）—— 其中 `Price` 一条已**独立分级**，见 `defect-survey.md` 病 B「服务端零价格校验」，此处不重复展开。**落地情况（2026-10-04 C#6）**：② 已由本笔（`{0,1}` 由白名单隐含 —— `isCanonical` 拒掉非二值）+ B#1（`Price` 值域）**两条合起来覆盖**；⚠️ ① **未按字面采纳** —— 本笔取**白名单**而不取「一律写 0」，因为**生产前端 `ProductPage.vue:354` 本来就直传 `PayIf=1`**，「一律写 0」会当场弄坏下单。管理端点**未开**（本笔不做，撞「不动其他订单端点行为」的边界）。 |
| 优先级 | 中 —— 无鉴权（BD-03）放大了可达性；但它造成的是「不合业务时序的数据」而非数据不一致，且本项目定位为内部管理系统。 |
| 状态 | ✅ **已修（C#6，2026-10-04 `fix/order-state-guard`）** —— 出生闸 `OrderState.isInitial` 落在 `OrderService.orderAdd` 的 `runLocked` **之前**、且**排在 B#1 价格闸之后**：接受 ⟺ `(PayIf,DeliveryIf,ReceiveIf) ∈ {(0,0,0),(1,0,0)}`；不通过 ⇒ **HTTP 200 + `{success:false,msg:"订单状态不合法"}`** 且**不取锁、不触库**，同时记 `mall.order.state.birth.rejected{shape}` + 一行 WARN。⚠️ **判据是白名单、不是「合法态」** —— 若照「不变式前移」做，`(1,1,1)`（**正是本 TD 正文举的那个例**）会**原样放行**，等于假修；`(1,1,0)`/`(1,0,1)`/`(1,1,1)` 三条「合法但非初始」被拒是**刻意的**。⚠️ **白名单必含 `(1,0,0)`**：生产前端 `ProductPage.vue:354` 直传 `PayIf=1`（同页 `:342-343` 固定 `DeliveryIf:0`/`ReceiveIf:0`）⇒ 拒它 = 弄坏下单。理由写在**两处**：`OrderState.isInitial` 的 javadoc + `api-contract.md`（防后人误改）。⚠️ **本笔只关入口**：**存量脏行**（已落库的 `(1,1,1)` 等）**不在范围**，`mall.order.state.illegal` **保留不删**、改作兜底观测。⚠️ **D5 裁决「登记不门禁」未被破**：A 组断言仍不覆盖本入口 —— 本笔的报警来自**单测**（`OrderAddStateGuardSmokeTest`，10 用例、`@WebMvcTest` 切片、**无 `A3_ENABLED` 门控** ⇒ 在 `backend` **阻塞** job 真跑）+ **A3 的 C 组**（新增 `A-C6-1`/`A-C6-2`；⚠️ `concurrency` job 是 `continue-on-error: true` ⇒ 只算旁证）。判据细节与 A3 适配见 `progress.md §5-G`。原文（已过期）：「待确认（A3 登记，不在 A3 修 ——「不改业务代码」是 A3 的硬约束）。D5 裁决：**登记不门禁** ……」 |
| 锚点更正（2026-09-30） | 本条原用 `G:` + 行号锚定（`G:390` / `G:398-400` / `G:403-417` / `G:527` / `G:549` / `G:571`），**六处全部漂移**（B2 / B3 往该 controller 加行所致）。已改为**符号名**锚定（`MallManagementGoodsController.orderAdd` / `.orderPayIfUp` / `.orderReceiveIfUp` / `.orderDeliveryIfUp`）；⚠️ **2026-10-02 笔 2 后这四个符号已在 `OrderController`（入参绑定）/ `OrderService`（业务逻辑），行号锚点已全部失效** —— 符号名随之改指 `OrderController.orderAdd` 等，行号锚点一律撤除；2026-09-30 核一次导航用行号：方法 `:386`、三状态位入参 `:393-395`、`INSERT` 绑定 `:435-449`、三 CAS `@PostMapping` `:567` / `:579` / `:591`。⚠️ 其中 `G:390` 是最阴的一种漂：它仍落在 `orderAdd` 方法体内（现为 `Price` 参数行），**看着是对的**。这是 `defect-survey.md §五`「锚点漂移」的**新证人**，也加固了 Class A #8（锚点改符号名）的理由 |

## TD-18 管理员 `/user/token` 铸票路径缺失：cookie 会话能走通，但没有 token 通道

| 项 | 内容 |
|---|---|
| 现状 | 管理员的身份**只由 cookie 会话承载**。C3 骨架第一笔把 `role == 3` 并入了 `UserController.loginSelect` 的建会话分支（见 `slot-registry.md` §十三 13-5 表 ②），此后 `/user/me` 走**识别**口径（`identify`）认得出他。**能走通**：该笔的 10 项页面级验收全绿（同 §十三 13-8），「登录 → 落 `#/admin`」全程**不依赖票**。 |
| 缺口 | 管理员**没有铸票路径**。`/user/token` 走**授权**口径（`AuthSession.currentUserId`），而它对 cookie 里的 `role = 3` **一律折叠成 `null`** ⇒ cookie 管理员打它恒 401 ⇒ **铸不出票**。⇒ 管理端目前**只有会话、没有票**：多设备登录、长会话续期，以及任何靠 `Authorization: Bearer` 的无状态调用，在管理端都做不到。骨架阶段**无业务请求**，故缺口尚不可达；一旦管理端接上第一个要票的接口，它立刻变成硬阻塞。 |
| 保留原因 | `/user/token` 的 javadoc **明写**：若允许 cookie 管理员换票，「绕一圈就得到『**管理员票可无限续期**』的口子」。那是一个**刻意**的 401，不是遗漏 —— 照字面「让 `/user/token` 认 cookie 管理员」＝当场破掉这条成文的安全属性，代价远大于收益。且验收③（「登录 role=3 → 落 /admin」）**不依赖**它 ⇒ 不做它不挡任何既定目标。 |
| 登记不修 | **C3 若需多设备 / 长会话再处理**（或管理端出现第一个要票的接口时）。届时的出路有三，**留待那时裁**：ⓐ 照字面实现、接受该口子；ⓑ 另开一个**凭据驱动**（不走 cookie）的管理员铸票端点，保住现有口径；ⓒ 继续不做。本 TD **只登记、不含任何实现**。 |
| 优先级 | 低 —— 骨架阶段零业务请求，缺口不可达。但它**不是**「不会发生」，是「还没到期」。 |
| 状态 | ⏸ **登记不修（2026-10-08，C3 骨架第一笔）** —— 用户裁决：**判不做，但立 TD**。「静默不做」会掩盖一处已定案的取舍，故留档。 |

## TD-19 通知接收侧不校验 `toId` × `toType` 是否吻合

| 项 | 内容 |
|---|---|
| 现状 | `/notification/*` 四个接收端点用 `IdentityGuard.requireSelfOrAdmin(request, toId)` 授权 —— 它**只看 `toId`**，`toType` 仅作查询条件。⇒ 传 `toId=<某商家号> + toType=customer` **不报错**，只是查不到行（`toType` 是**封闭**取值域，取值域校验在**发送**侧有，接收侧没有）。 |
| 为什么是「接缝」而不是「漏洞」 | 核心安全面**已经在**：非管理员传**别人的** `toId` ⇒ **403**（已实测，见 `slot-registry.md §十五` §五·10 与 `scripts/probes/i2-guard.mjs`）。剩下的错配路径要求调用方**传自己的号**再配一个错的 `toType` —— 结果是**空集**：读不到任何不属于自己的数据，也写不坏任何行。 |
| 保留原因 | 修它要动 `requireSelfOrAdmin` 的**契约**：从「只认 `toId`」变成「`toId` × `toType` 双键校验」，且得为每个 `toType` 去 `user` 表反查归属。那是**公共授权原语**，批 A 起所有端点都在用 ⇒ 回归面远大于收益（把一个无害空集换成一句更明确的报错）。 |
| 登记不修 | 用户 **2026-10-09** 裁决：**登记，不修**。⚠️ 若将来接收端出现「按 `toType` 分流副作用」的写法（现在没有），此条要**重估**。 |
| 优先级 | 低 —— 无害空集。 |
| 状态 | ⏸ **登记不修（2026-10-09，C3 块 1 · I2 收官）**。（⚠️ 另有 **TD-20**，为守零漂移写在**文件尾**，见文末「活跃技术债（尾部续）」） |

## 已完成里程碑

> 本节记录已交付的阶段性成果，与上方「活跃技术债」区分。新里程碑追加在最下方。

### M1 — A2 基建（2026-09-18）

| 项 | 交付物 | 证据 |
|---|---|---|
| smoke test | 4 用例：`LoginSelectSmokeTest`、`OrderPayIfUpSmokeTest`（成功分支 + 失败分支）、`ImgMappingSmokeTest` | `78f3ef1`、`8709e14` |
| CI 配置 | GitHub Actions：后端 `./mvnw -B test` 全量 + 前端 `npm ci && npm run build`；JDK 17 / Node 20.17.0 / `~/.m2` 缓存 / surefire 报告 artifact | `417254e`、`bcada27` |
| CI 实跑 | 5 次运行全绿（Run ID 35328548406、35329177960、35329430908、35330326189、35330643388） | `gh run list` |
| paths-ignore | 纯文档 push 不触发 CI。实证：全仓库 5 次运行无一 `headSha` 为 `23dfaab` | `df99a53`、`c4807d9`、`23dfaab` |
| workflow_dispatch | 手动触发按钮在 main 上可见（GitHub 仅对默认分支显示） | `8ecbf1a` |
| 文档漂移修复 | project-map 目录树与结论、api-contract §4、agent.md 目录说明、Vue 版本事实错误 | `23dfaab` |
| TD-10 处置 | `contextLoads()` 加 `@Disabled`，解除 CI 阻塞 | `c3cd9f6`、`d62e89d`、`4b84ee2` |
| TD-11 登记 | 前端 36 个依赖漏洞（2 critical / 14 high / 18 moderate / 2 low） | `0eefced` |

**硬性前置**：`Mall-management-server/mvnw` 可执行位 `100644 → 100755`，Linux runner 必需 —— `f7d6172`。

**收尾状态**：主源 7 个 `.java`、测试源 5 个；全项目 5 用例 = smoke 层 4 个（CI 实跑）+ `contextLoads` 1 个（`@Disabled`）。

> 上句是 **A2 当时的快照**。A3 已改：`@Disabled` 换成 `A3_ENABLED` 环境开关，并新增 `databaseIsReachable()` → 全项目 6 用例（默认 SKIP 2 个）。见上方 TD-10（已关闭）。

### M2 — A3 并发测试（2026-09-20）

| 项 | 交付物 | 证据 |
|---|---|---|
| 并发脚本 | `scripts/concurrency/ConcurrencyRunner.java` **2270 行**（单文件、不引新依赖，只用 JDK 内置 `HttpClient` + `ExecutorService` + JDBC）+ `scripts/concurrency/run.sh` **151 行**（两步编译 + 驱动解析，校验 jar major=8） | `333ae1a` … `6465655`（阶段 A–E） |
| 负载与校验 | **6 个负载段落**（S1 / S2a / S2b / S3 / S4a / S4b —— 与「4 场景」不矛盾，见 pack §场景计数口径澄清）；负载走 HTTP、断言与清理走 JDBC（五张表都没有删除接口）；preflight P0–P7 全 PASS | pack §验收标准、§脚本内置校验 P0–P7 |
| a3 profile | `application-a3.properties` **57 行** —— 指向独立 MySQL 实例（库名仍为 `mall_management_sql`，TD-13 所迫）。⚠️ **2026-10-11 起 a3 收窄为「CI 专用」**，日常改挂孪生 `application-dev.properties`（job 全开）—— 见 **TD-35** | `c37cb8e` |
| 字符集根治 | 放弃「单文件源码启动」（由启动器就地编译、不采用编译期 `-encoding` → 宿主 `file.encoding=GBK` 使源码中文字面量失真 → 依赖中文响应体的断言**静默落空**）→ 改**两步编译** `javac -encoding UTF-8` + `java -cp <out>`；`charsetSelfCheck()` 每次运行自检，并把 `file.encoding` / 启动方式写进日志与 JSON 报告 | `6ae41d5` |
| CI job | `.github/workflows/ci.yml` 增至 **256 行**，新增第三个 job `concurrency`（`needs: backend`、service container `mysql:8.4`、job 级 `concurrency` 覆盖 `cancel-in-progress`、`continue-on-error: true`） | `af75b97` |
| CI 首跑 | **通过——结论见 artifact `a3-concurrency-report`**。**`concurrency` job 仍是观测跑，不是门禁** —— 门禁地位待裁决，本条在裁决前不改 | `ci.yml:252`；pack §CI 集成 |
| TD 变更 | **登记 TD-14 / TD-15 / TD-16 / TD-17**；**关闭 TD-10**（D1 落地：`@Disabled` → `A3_ENABLED` 环境开关 + 新增 `databaseIsReachable()` 以 `SELECT 1` 强制真连库）；**修正 TD-14 定性**（「120 QPS 不可达」改判为本地环境属性，非索引缺失一因所致） | `6934886` / `1150e89` / `dce2307` / `27d3205` / `99206a5`；`a4225ad` |
| 文档 | pack **356 行** + worklog **246 行** + `environment.md` **70 行**（均为**落盘时**行数；pack 与 worklog 其后因 TD-14 定性修正增至 368 / 247） | `3137776` / `54fdd35` / `e3a9ada` |
| 实测缺陷 | B-2 三处 TOCTOU `rounds=5 / reproRounds=5/5`（两次独立实测逐轮数字一致）；A-S2a-1：**20 并发同参 `orderAdd` 插出 20 行**（`5xx=0`）；S4b 本地 `63.0` / `96.2` QPS vs **CI 同档 `119.9` QPS / p50 6.3ms**；TD-14 行数对照 6 行 p50 1ms → 6006 行 51ms → 12006 行 94ms | pack §实测数据；本地报告 `scripts/concurrency/reports/a3-report-*.json` |

**硬性前置**：`scripts/concurrency/run.sh` 可执行位 `100644 → 100755`，Linux runner 必需 —— `d47371a`。

**收尾状态**：A3 全部合入 `main`（`fc4e0fe`，PR #3）；CI 三 job 全绿；`concurrency` job 仍是观测跑（门禁地位待定）。**零业务 Java 改动**（除 D1 那一个测试类）；全项目 6 用例 = smoke 层 4 + `contextLoads` / `databaseIsReachable` 2（默认 SKIP 2 个，同 M1 脚注口径）。

### M3 — B 阶段优化地基（2026-09-29）

| 项 | 交付物 | 证据 |
|---|---|---|
| 两级缓存 | `TwoLevelCacheManager` + `TwoLevelCache`（Caffeine L1 60s / 上限 1000 + Redis L2 5m）；**8 个 cacheName**：`goodstype`、`userPublic`、`goodsHome`、`goodsByMerchant`、`goodsPrice`、`goodsCodes`、`friendList`、`friendRequests`；写路径 evict 收敛成 Service 单入口（3 个实例） | `3678719`；`79d2a0d` … `52a944f`（8 个 cacheName）；`a3e50cf`（单入口） |
| 缓存三防护（逐项定态） | **击穿** ✅ `sync = true` 单飞（冷 L1 + 冷 L2 打 20 并发：修前 20 次 DB → 修后 1 次）；**穿透** ✅ 负缓存（0 行 → 真 `null` → 被缓存，首个实例 `userPublic`）；**布隆过滤器** ❌ **明确不采纳**（记不采纳，不冒充欠债）；**雪崩（Redis 不可达）** ✅ 冷却窗口 30s + 探测超时 200ms（黑洞端点实测：首个冷读 3.376s → 之后 4~7ms，全程 `5xx = 0`）；**雪崩（TTL 抖动）** ❌ 不实现（触发条件：某缓存族 key 数 > ~10） | `f693bdb`（击穿）；`549f74f`（负缓存）；`84cd200`（冷却窗口）；`cache-layer.md §9.1` |
| L2 探活 | `L2HealthProbe` 单例 + `L2ProbeConfig`（探测通道**不注册为 bean** —— 否则会顶掉主连接工厂，L2 静默跑在无连接池的探测通道上而 A3 看不出）；探测代价实测稳态 **233ms**、进程内首次 **489 / 612 / 772ms**；`mall.cache.l2.up` gauge（⚠️ 0 ≠ Redis 已死） | `437140e`、`84cd200`、`9c245d2` |
| 观测地基 | `MallCacheMetrics` —— **11 个 `mall.cache.*` 指标** + **2048 槽事件环**（**原 512**；2026-10-15 块 7 commit 10b），「计数 + 事件流」合并于同一调用点（防两通道口径漂移）；Actuator 暴露 `health,metrics`；**关闭 Boot 的 Redis health indicator**（它是绕过冷却窗口的第二条探测通道） | `aab3b9d` |
| 分布式锁 | `DistributedLockService.runLocked` 模板方法 + **5 个落点**（`order-add` / `order-pay` / `order-receive` / `order-delivery` / `friend-add`）；`app.lock.wait=5s` / `lease=10s` / `cooldown=30s`；**fail-open**：取锁超时、Redis 不可达、冷却窗口**三路都回落原路径**，不新发任何拒绝文案 | `8810efe` |
| 锁观测 | `MallLockMetrics` —— **4 个 `mall.lock.*` 指标**（`acquires` / `wait.duration` / `holds` / `up`）+ **独立** 2048 槽事件环（不共用缓存那份，代价换 C1 一个字不动；**原 512**，2026-10-15 块 7 commit 10b） | `8810efe`；`application.properties` B2 节 |
| 锁专项场景 | `LockSceneService` 四形状（`WAIT` / `CONFLICT` / `TIMEOUT` / `DEADLOCK`）；完备性不变量「四路计数之和 == 预期取锁次数」，对不上即抛 —— 不返回偏小的分布 | `8810efe`、`LockSceneServiceTest` |
| **缺陷翻转（终态）** | **A-S2a-1 `EXPECTED_DEFECT_PRESENT` → `PASS`**：20 并发同 `OrderId` 的 `orderAdd` 落库行数 **20 → 1**；**B-2 的 friend 那一路 `friendDupRounds` 5/5 → 0/5**（`friendAdd` 原有查重的 TOCTOU 窗口被锁关闭）。**未翻转两路**：`login5xxRounds` 5/5、`goodstypeDupRounds` 5/5 —— 与「5 落点不含 `loginAdd` / `goodstype`」自洽 | 对照 **18 份** B2 前报告（`2026-09-20` ~ `09-29 14:40`，`friendDupRounds` **全部 5**；其中 **12 份**含 S2a 段，`rowsForSameOrderId` **全部 20**）+ `a3-report-b2-0929a-20260929-195531.json`、`a3-report-b2-0929b-20260929-202046.json` |
| 文档 | B1 收尾 worklog `archive/worklog-2026-09-29-b1.md` **327 行**（含「三档数字」可复核性口径）；方案/现状 `context-packs/cache-layer.md` **476 行** | `1e53788`、`7012176` |
| TD 变更 | **无** —— B1 / C1 / B2 三笔均未改动 `tech-debt.md`（该文件最后一次改动即 M2 本身 `c5a230b`）；B1 发现的工程约束记在 `cache-layer.md §11`，未升为 TD | `git log --follow docs/agent/tech-debt.md` |

**硬性前置**：① `pom.xml` 新增 **6 个依赖**（`spring-boot-starter-cache`、`caffeine`、`spring-boot-starter-data-redis`、`commons-pool2`、`spring-boot-starter-actuator`、`redisson:3.46.0`）；② **运行时需 Redis 实例** —— CI 的 `concurrency` job 内以步骤 `docker run -d --name a3-redis -p 6379:6379 redis:7 redis-server --requirepass 123456` 提供（**刻意不用 services**：官方 redis 镜像 entrypoint 不接受环境变量传参，见 `ci.yml:153-164`）。

**收尾状态**：B1 / C1 / B2 三笔全合入 `main`（`dcf2d39` / `3f8c4b7` / `aacd2bb`，PR #4 / #5 / #6）；CI 三 job 全绿；`concurrency` job 仍是观测跑（`continue-on-error: true`，门禁地位同 M2 未裁）。全项目 **51 用例**（10 个可执行测试类，Failures 0 / Errors 0，默认 SKIP 8 = `LockSceneRedisTest` 4 + `MallManagementApplicationTests` 4；2026-09-29 21:04 本地实测）；主源 21 个 `.java`、测试源 11 个。观测出口 2 个，共 **15 个 `mall.*` 指标**。

### M4 — B3 订单状态机 + 异步时间线（2026-09-30）

> 标题日期 = 合并笔 `148e77b` 的**提交日（+0800 本地）** `2026-09-30 00:02`，**不取** GitHub / CI 显示的 `2026-09-29T16:02:35Z`。
> 此即 M2 的覆辙处：`fc4e0fe` 本地为 `2026-09-21 00:10`，M2 标题却写 `2026-09-20`（用了 UTC 日期）。B3 的代码笔全在本地 `09-29 23:10–23:49`，唯合并笔跨了本地零点。

| 项 | 交付物 | 证据 |
|---|---|---|
| 状态机（核心） | `OrderAction`(64) / `OrderState`(124) / `OrderStateMachine`(281) —— **无状态、描述性转移表**：守卫层（SQL 谓词 CAS）与语义层（状态名）显式分离、差集可测；**golden copy 回归闸**钉住线上 SQL 逐字符不变 | `b226357` |
| 四个写方法接入 | `MallManagementGoodsController` +55/-40：`orderAdd`（`orderAddLocked:455` 记 **CREATE** —— 只在 `rows > 0` 之后记，「订单已存在」早退分支不记，因未产生新状态）+ `orderPayIfUp` / `orderReceiveIfUp` / `orderDeliveryIfUp` 三 CAS 端点（统一经 `casTransition` 记转移）。**CREATE 是必需项而非可选项** —— 时间线的第一条就是出生 | `428293f` |
| 异步出口 | `OrderStateExecutor`(289) —— 单池 core2/max4 + `ArrayBlockingQueue(1024)` + discard-oldest-with-callback（**丢观测、不丢业务**）；`OccurredAt` 在**锁临界区内**取 | `428293f` |
| 时间线（两类分工） | `OrderTransitionRecorder`(187) —— 在 UPDATE 返回后**立刻**于临界区内判定/读回三位/埋点/入队（`submitTimeline` → `executor.submit`）；`OrderTimelineWriter`(137) —— 时间线的**唯一写入点**，跑异步线程、**永不抛**（fail-soft，`result=error` 即该条永久丢，不重投）。读回在 UPDATE **之后**、由新态**精确反推** `FromState`（非近似） | `428293f` |
| 观测 | `MallOrderStateMetrics`(267) —— **6 个 `mall.order.state.*` 指标**（`transitions` / `illegal` / `async.duration` / `async.queue.depth` / `async.rejected` / `timeline.writes`） | `428293f` |
| 建表 | `order_state_transition` 追加至 `mall_management_sql.sql:221-235`：bigint PK + `idx_order_state_transition_order(OrderId, OccurredAt)`；**全库唯一带列 COMMENT 的表**、唯一 `datetime(3)` 列 | `e93a499` |
| A3 扩展 | `ConcurrencyRunner.java` +407/-6：新增 **C 组时间线核对**（**record-only**，不门禁；⚠️ **2026-09-30 起升为门禁，见 `5544362`**，代码与 `--help` 的旧注释由 `5f1ad21` 清理）；P3 表数判据改为「随 DDL 是否落盘走」 | `4bc78d2` |
| 配置 | `application.properties` +26（B3 节） | `428293f` |
| 合并与 CI | PR #7 合入 `main`（`148e77b`）；CI `success`（headSha = `148e77b`） | `gh run list` |

**决策（B3 期间）—— 四项选型明确不补 + 登记触发条件**：Resilience4j 限流熔断（触发：出现第 2 个外部依赖，或需按接口限流）、Spring Retry 死锁重试（触发：出现多表写事务）、Logback JSON 编码器（触发：接入日志聚合）、线程池分池（触发：出现第 2 个异步出口且其任务不可丢）。⇒ **四项均三层前不必补**。

**发现（B3 后普查，纯文档，`10da544`）**：

| 文档 | 内容 | 规模 |
|---|---|---|
| `db-schema.md` | schema 完整视图：12 表（11 原始 + B3 追加 1）+ §6–§10 逐列 / 索引 / 外键 / 跨表类型基线 | +287/-4 |
| `defect-survey.md` | 缺陷普查：**七病 + 七前提 + 元发现 + Class A/B 分岔**；三条总判断 ——「局部推理极强、组合推理缺席」/ 七前提 / 测试与缺陷不重叠 | 226 |
| `selection-status.md` | 7 项选型逐项：**3 已落地 + 1 自研替代 + 3 未落地**；「切面 + 注解」整体未落地（全仓 `@interface` = 0） | 90 |

**Class A 8 项 + Class B 6 项待裁决**（`defect-survey.md §7`），为三层前输入。

**待裁决两处**（`selection-status.md §5`，本次**未动 `agent.md`**）：① `agent.md:142`「能引库就不自研」与 B3 自研状态机背离；② 「乐观锁」是否已被谓词守卫等效覆盖 —— 判定为**非等价替换**，是有意的语义差异（TD-17）。

**硬性前置**：**无新增依赖** —— B3 全范围 `pom.xml` 零改动（`git diff --name-only 4ef77f1..148e77b -- Mall-management-server/pom.xml` 为空）。运行时需 Redis，同 M3（CI 内 `docker run ... redis:7 --requirepass`，刻意不用 services）。

**收尾状态**：B3 合入 `main`（`148e77b`，PR #7）；CI 三 job 全绿；`concurrency` job 仍是观测跑（`continue-on-error: true`，门禁地位同 M2 / M3 未裁）。普查三份文档 `10da544` 为纯文档 ⇒ **`paths-ignore` 生效、未触发 CI**（实证：近 25 次运行无一 `headSha` = `10da544`）。全项目 **`@Test` 111 处**（15 个含测试的类；默认 SKIP 8 = `LockSceneRedisTest` 4 + `MallManagementApplicationTests` 4，均 `@EnabledIfEnvironmentVariable(A3_ENABLED)`）；主源 **28** 个 `.java`、测试源 **16** 个。观测出口累计 **21 个 `mall.*` 指标**（11 cache + 4 lock + 6 order.state）。

**TD 变更**：**无** —— `tech-debt.md` 在 B3 全期间未被改动（最后一次改动 `4ef77f1` = M3 本身）。B3 的待落项共三笔，均属「声明过、从未落盘」：`db-migration.md` 的 B3 行、**M4 本条**、`agent.md` 技术选型最小改。

### M5 — 三层重构（四域 + 公共层）🟡（2026-10-04）

> 标题日期 = 收尾笔 PR #45（`473b6c1`）的**提交日（+0800 本地）** `2026-10-04 15:24`，**不取** GitHub / CI 显示的 `2026-10-04T07:24Z`（同 M4 口径）。
> ⚠️ 与 M1–M4「单笔合并日」不同：本条覆盖 **2026-10-02 ~ 10-04 三天、PR #25–#46（22 个 PR / 35 笔非 merge commit / 106 个文件）** —— 因为三层重构本身就是跨多笔的批次（同 M3 覆盖 B1 / C1 / B2 三笔）。
> ⚠️ **「三层重构 ✅」不是干净 ✅**：四域 **4/4** 已完成，但**公共层仍 🟡**（三笔已合，`D#5` 配置收敛**未做** —— 见 `progress.md §3`）。故标题带 🟡、不写「完成」，同 M3 的挂账注。
> ⚠️ **边界**：本条**不含** 2026-09-30 的那一批（A 批 / G 表 / 规则 v2 / S1a 7 笔 / 三层前准备，**PR #8–#24**）。它落在 M4 里程碑落盘（`c57f7e7`，09-30 13:59）**之后** ⇒ M4 收录不到；本条按主题也不收。**该批现状只记在 `progress.md §1.1` / `§2`，尚无任何里程碑收录**（是否另立待裁 —— 已挂账于 `progress.md §5-H (1)`）。
> ⚠️ 下表四域的 Controller / Service 行数为 **2026-10-04 `wc -l` 实测**。`progress.md §3` 表内同项快照（`118` / `313` / `176` / `332` / `107` / `319` / `127`）**已过期** —— 该漂移早在 `progress.md` §3 登记待修，**本次不修**。

| 项 | 交付物 | 证据 |
|---|---|---|
| 订单域三层（**三层第一笔**） | `order/controller/OrderController`(129) + `order/service/OrderService`(495) + `order/dao/OrderDao`(155)，SQL 下沉 DAO；早于「笔 1 / 笔 2」故为三层首笔 | PR #25 `28194b4` |
| DDD 挪包 | **49 文件**按域分层落位（纯挪包）；`info` 孤儿类删除；规划 `ddd-package-plan.md`（31 文件定位 + 七项裁决） | PR #26 `0466230`；PR #27 `01df702` |
| URL 前缀规范化 | 订单 8 端点拆出 `OrderController`（`@RequestMapping("/order")`）+ 三个 Controller 改名对齐域；前端 **31 个调用点** + A3 脚本 **34 处** + CI 预热（`ci.yml:224`/`:229`）跟随 | PR #28 `8c5385e`、`2ced01c`、`c3c29fb`；`81629b5` |
| 商品域三层（**第二笔**） | `GoodsController`(193) + `GoodsService`(459) + `GoodsDao`(211)；**8 条内联 SQL** 下沉 | PR #29 `4c1f31a` |
| 用户域三层（**第三笔**） | `UserController`(121) + `UserService`(321) + `UserDao`(242)；SQL 硬编码 schema 名 **16 处**全部下沉 `UserDao` | PR #32 `0c07e96` |
| 消息域三层（**第四笔**） | `MessageController`(143) + `MessageService`(534) + `MessageDao`(284)；schema 名 **0 处** | PR #33 `8ed5fc1` |
| 公共层 笔 1/3 —— CORS | 删 **5 处**冗余 `@CrossOrigin`（⚠️ 本行只关 **TD-04 的「重复配置面」**；「通配风险面」由后一笔 **CORS 收窄笔**关闭，2026-10-06，见 `progress.md` §5-AH） | PR #34 `23f5361` |
| 公共层 笔 2/3 —— `Result<T>` | 统一响应体 + **逐字节等价回归闸**；**28 个端点**返回类型换成 `Result` | PR #35 `ea2bf02`、`321773e` |
| 公共层 笔 3/3 —— 异常 + 校验 | `GlobalExceptionHandler`（`assignableTypes` 白名单 + 封闭异常清单，**不做通用兜底**）+ 写接口 `@NotBlank` ×14 | PR #36 `573463c`、`1bd05d3` |
| 缓存 笔 ② | 三处列表读不再「把失败当值缓存」（catch 上抛 + 响应组装搬回 Service）；另登记「单飞等待**无超时/无上限**」 | PR #37 `f1c26a8`、`8635487` |
| 收口与去漂 | `selection-status.md §8` 待办与优化总表 **33 项**（10 必做 + 10 触发 + 6 可能补 + 7 登记未修）+ `§9` 新功能引出表 **6 项**；drift-fix 一批（订正 B2 / 三层后已失效的旧结论） | PR #38 `fc17caf`；PR #39 `bc80c40` |
| 清欠账 **4 笔** | `#11` goodstype 唯一约束（DDL `uk_goodstype_GoodType` + catch 拆 `DuplicateKeyException`）/ `C#2` 商品域写事务边界（三处写方法加 `@Transactional` + catch 内带活跃事务判断的 `setRollbackOnly`）/ `B#1` 价格闸 / `C#6` 出生闸 | PR #40 `62c5295`（落盘记录笔 #41 `37cafc3`）；PR #42 `ad39a1d`（记录笔 #43）；PR #44 `830d98f`；PR #45 `473b6c1`（记录笔 #46 `8c7236a`） |
| A3 适配 | ①`c3c29fb` URL 前缀跟随（**判据一字未改** —— 门禁认 `call(...)` 标签、不认 URL 字面量）；②`473b6c1` 把 S4a 前置态由**手改库**改为走**合法流转** + 新增门禁 `A-S4a-0` / `A-C6-1` / `A-C6-2`（`gates.count` **27 → 30**）；③`c5b46da` 夹具推进 URL 补 `/order` 前缀（首轮 CI 实测抓到 **3 条红**，含 `A-S4a-0` 新上线即抓到东西） | `c3c29fb`、`473b6c1`、`c5b46da`；run `37185693143` → `37192169578` |

**硬性前置**：① **新增 1 个依赖** —— `spring-boot-starter-validation`（PR #36 `573463c`，为控制器方法参数上的 `@NotBlank` 供 Validator bean；⚠️ **非「无新增依赖」**：`git diff --name-only 5db3c18..fa2a8e2 -- Mall-management-server/pom.xml` **非空**）。② **唯一 schema 变更 = 一条 DDL** —— `CREATE UNIQUE INDEX uk_goodstype_GoodType ON goodstype(GoodType)`（PR #40，`mall_management_sql.sql` 尾部追加块；⚠️ 对**已有重复行的既有库**须先人工去重再建，见该块注释），此外**无建表、无列变更**。③ **运行时需 Redis**，同 M3 / M4（CI 内 `docker run -d --name a3-redis -p 6379:6379 redis:7 redis-server --requirepass 123456`，**刻意不用 services**）。

**收尾状态**：四域 + 公共层全部合入 `main`（收尾 PR #46 `fa2a8e2`，纯文档 ⇒ `paths-ignore` 生效）；**CI 判据已逐条核过的是 PR #45 那一轮** —— run `37192169578` 的 `a3-concurrency-report`：`gates.count == 30`（27 → 30 对上）、`gates.failed == 0`、`exitCode == 0`，S4a 8 条 + `C-1`/`C-2` 全 PASS；`surefire-reports` `Tests run: 186, Failures: 0, Errors: 0, Skipped: 19`。合并后 `main` 上的 run `37192450667`（`headSha` = `2e9d3f8`）为 `success` —— ⚠️ **run 级 `success` ≠ 三 job 全绿**：`concurrency` job **仍是观测跑**（`continue-on-error: true`），job 颜色**系统性无信息量**，只有拉 artifact 看得见（门禁地位**自 M2 起挂到本条，仍未裁** —— 连续第 4 条）。全项目 **`@Test` 186 处**（26 个含测试的类；默认 SKIP **19** = `MallManagementApplicationTests` 6 + `GoodsWriteTxAtomicityTest` 6 + `LockSceneRedisTest` 4 + `GoodsTypeUniqueIndexTest` 3，均需 `A3_ENABLED` / 真库 / 真 Redis）；2026-10-04 17:47 本地 `./mvnw -B test` 实测 `Tests run: 186, Failures: 0, Errors: 0, Skipped: 19`（BUILD SUCCESS）。主源 **39** 个 `.java`（`shared` 15 / `order` 10 / `goods` 5 / `user` 4 / `message` 4 / 顶层 1）、测试源 **27** 个 `.java`。观测出口累计 **22 个 `mall.*` 指标**（11 cache + 4 lock + **7** order.state —— 较 M4 的 21 多出 `mall.order.state.birth.rejected`，由 C#6 新增）。

**TD 变更**：**关闭 TD-17**（`orderAdd` 不校验三个状态位，2026-10-04 C#6）—— 本期间**唯一**的状态翻转。`tech-debt.md` 在 M5 范围共被改动 **6 次** = 上述 1 次状态翻转（`473b6c1`）+ **5 次坐标 / 计数回归**（`81629b5` / `50bfe65` / `d3af49a` / `72a2e2e` / `481e72b`），逐笔可复核：`git log --no-merges --oneline 5db3c18..fa2a8e2 -- docs/agent/tech-debt.md`。**TD-16 仍挂** —— `--reset-db` **仍是死参数**，本期间只做了「标注未实现」，主体未动。

### M6 — 界面优化段 🟡（2026-10-08）

> 标题日期 = 收尾笔 PR #80（`7865952`）的**提交日（+0800 本地）** `2026-10-08 20:26`，**不取** GitHub / CI 显示的 `2026-10-08T12:26Z`（同 M4 / M5 口径）。
> ⚠️ 与 M1–M4「单笔合并日」不同：本条覆盖 **2026-10-06 ~ 10-08 三天、PR #74–#80（7 个 PR / 26 笔非 merge commit / 386 个文件）** —— 界面优化本身就是跨多笔的批次（A / B / C / D 四笔 + D 笔后调整批次），同 M3 / M5 口径。首笔 `ec5d8d1`（2026-10-06 15:49），末笔 `e2ff7f4`（2026-10-08 20:15）。
> ⚠️ **本条是纯前端段**：`git diff --name-only a4e274d..7865952 -- '*.java'` **为空** —— 后端只被换掉 2 个 `.jpg`（`Mall-Section-1.jpg` / `Mall-Section-2.jpg`，来自 `bad1251`）。下表**无一行是后端代码**，故「硬性前置」里没有依赖 / schema 项。
> ⚠️ **边界 ①**：本条**不含 R1–R6**（路由化那一段）。`git log --merges a4e274d..7865952 | grep -ci router` = **0**；R1–R6 已逐条 `git merge-base --is-ancestor` 证在其**之前**。
> ⚠️ **边界 ②（夹层挂账，同 M5 边界注的情形）**：M5 收尾（PR #46 `fa2a8e2`）到本条起笔（PR #73 `a4e274d`）之间还压着 **PR #47–#73**（**27 个 merge / 39 笔非 merge / 76 个文件 / +8820 −1186**）—— 鉴权批 + CORS origin 收窄 + 路由化 R1–R6 等。**该批至今无任何里程碑收录**（已与 2026-09-30 那批并列挂账于 `progress.md §5-H (1)`）。
> ⚠️ **口径漂移（本条自曝，未立 TD）**：`0db9cb6` 的提交信息自称「脚本进仓 / **产物不入库**」，但 **27 个扫描产物 `scripts/refs-*.txt` 在收尾调整批次 `e2ff7f4` 里被一并提交进仓** —— `git show --diff-filter=A --name-only e2ff7f4 -- 'scripts/refs-*.txt'` = 27 个。是否立条待裁。
> ⚠️ **「界面优化段」不是干净 ✅（故标题带 🟡）**：7 个 PR 全部合入，但**段内挂账三项未清** —— ① Q9 漂移**只报告、未逐条修**（台账 §六，`9015beb`）；② 稿里 49 条 `disabled` 占位**待裁决**（启用会丢数据，非误伤）；③ LXGW 楷体**故意未注册** `@font-face`（稿引了、仓里不落，标题差 3px 属预期）。口径同 M5 的挂账注。

| 项 | 交付物 | 证据 |
|---|---|---|
| A 笔 —— 设计 token 地基 | `src/assets/tokens.css`（186 行；`:root` 自 **58** 行起，**70** 个变量）+ **三族自托管字体 208 个 `.woff2`**（Noto Sans SC 97 / Noto Serif SC 97 / JetBrains Mono 6 + 各语种子集）；**零消费点、零布局变化** | PR #74 `ec5d8d1` |
| B 笔 —— 设计稿定稿 | `docs/美化/` **10 份 HTML 稿**（`pages/`：ChatRoom / GoodsAdd / GoodsSelect / GoodsUp / LoginPage / OrderCenter-merchant / ProductPage / ShoppingCart-customer / UserCenter-customer / UserCenter-merchant）+ **3 份分析**（`v5-字体探索报告` / `v5-视觉层级分析` / `v5-配色分析`） | PR #75 `9dab70a` |
| C 笔 —— 壳落 Vue | `views/AppShell.vue`（现 421 行）+ `views/UserCenter.vue` + `config/nav.js`（69 行：`CUSTOMER_NAV` / `MERCHANT_NAV` / `navFor`）+ `tokens.css` 接线 + 对稿截图 `shots3/` | PR #76 `60020f9` |
| B 线补丁 | 首页两张 jpg 去水印（`Mall-Section-1.jpg` / `Mall-Section-2.jpg`）+ 登录稿取景调整 | PR #77 `bad1251` |
| D 笔前置 + commit 0 | ① 全仓「文件:行号」互引扫描器 `scripts/refs-scan.sh` 落仓；② 壳容器取「中性形态」（1 文件 / 5 条规则 / **行数净零**） | PR #78 `0db9cb6`、`de85b93` |
| D 笔 commit 1–15 | **12 个页面按稿重写**（GoodsSelect / ChatRoom / HomePage / ShoppingCart / OrderCenter / UserInfoPage / HistoricalOrders / FriendRoom / GoodsAdd / GoodsUp / ProductPage / LoginPage；`views/*.vue` 总数不变 **16 → 16**）+ 包装层契约验证笔（**零代码 delta**，19 项断言全绿）+ 壳修补 3 笔（`.wrap` 居中 / 顶栏被压扁 / `sticky` 真生效）+ 台账 §一~§七 | PR #79（**19 笔**，收尾 `1e436a8`） |
| D 笔后调整批次 | 按**用户要求（不按稿）**的调整 —— 台账 §八~§十二 | PR #80 `e2ff7f4` |

**硬性前置**：**零新增依赖** —— `Mall-management-server/pom.xml` 与 `mall-management-system/package.json` 全段零改动（`git diff --stat a4e274d..7865952 -- <这两份>` **为空**）。**零 schema 变更**。**零 `.java` 改动**（仅上表 `bad1251` 那两个二进制）。**CI 配置未动** —— `.github/` 全段零改动 ⇒ **门禁数仍 30，未随本条变化**。新增的唯一「依赖」是 **vendored 前端静态资源**（三族字体 208 个 `.woff2`），不入 `package.json`。

**收尾状态**：7 个 PR 全部合入 `main`（收尾 PR #80 `7865952`）。**本机在 M6 窗口内落了 2 份 CI 产物** —— `scripts/concurrency/reports/a3-report-ci115-20261007-070640.json`（`generatedAt` `2026-10-07T07:06:39Z`）与 `a3-report-ci129-20261008-122420.json`（`2026-10-08T12:24:20Z`）；两份在 `exitCode` / `gates` / `assertions` / `scenarios` 四项上**一致**：`exitCode == 0`、`gates.count == 30`、`gates.failed == 0`、`assertions` **31 条**（`ci129` 逐条查过，无一条 `ok:false`）、`scenarios` **9 个**（S1 / S2a / S2b / S3 / S4a / S4b / B2 / C / cleanup）。⚠️ `concurrency` job **仍是观测跑**（`continue-on-error: true`）⇒ job 颜色**系统性无信息量**，只有拉 artifact 看得见 —— 门禁地位**自 M2 起连续第 5 条未裁**。全项目 **`@Test` 255 处**（较 M5 的 186 增 **69**，**全部落在上边界②那段夹层批里** —— 本条自身 **255 → 255，零 delta**，与「本条零 `.java` 改动」自洽）；主源 **44** 个 `.java` / 测试源 **32** 个（较 M5 的 39 / 27 各增 **5**，同样属夹层批）。前端 `src` 文本行 **6876 → 13006**（+6130，**已排除 208 个二进制字体**；不排除会误算成 53150）；`views/*.vue` **16 → 16**（14 个被重写 + `App.vue`，无新增页面）；`docs/美化` tracked 文件 **19 → 136**（+117）。386 个文件按区域：前端工程 **230**（含 208 个 `.woff2`）/ `docs/美化` **121** / `scripts` **28**（`refs-scan.sh` + 27 份 `refs-*.txt` 产物）/ `docs/agent` **4** / 后端 **2** / `.gitignore` **1**。

**TD 变更**：**无** —— `git log --no-merges --oneline a4e274d..7865952 -- docs/agent/tech-debt.md` **为空**（本条全期间未动本文件，即上面那条自曝的口径漂移**未顺手登记**）。另：`--reset-db`（TD-16）与「`concurrency` job 门禁地位」两项**沿用 M5 的挂账状态，本条未动**。 ⚠️ **窗口外补充（2026-10-08）**：C3 骨架期的工作区**登记了 TD-18**（管理员 `/user/token` 铸票路径缺失，见上「活跃技术债」末条）—— 它落在本条窗口 `a4e274d..7865952` **之后**、尚未提交，故上面那句「本期间未动本文件」**仍然成立**，但「TD 变更：无」已不是本文件当下的全部事实。

## 活跃技术债（尾部续）

> 为守**零漂移**，2026-10-10 起新增的活跃 TD **追加在本节（文件尾）**，不插回上方 `TD-01`…`TD-19` 之间 —— 本文件 `:253` 起的「已完成里程碑」被多份文档按行号互引，插入会让它们集体偏移。先例同 `mall_management_sql.sql` 尾部追加 Migration 块。

## TD-20 静默失败路径缺「专门测试」：不报错、不崩、全绿，但功能失效

| 项 | 内容 |
|---|---|
| 触发 | C3 块 3〈商品块〉就地改造期间**抓到两个真缺陷，同根** |
| 缺陷 ① | `GoodsAuditDao` 里 SQL 占位符 `?` 的**个数/顺序与实参对不上** —— 不抛异常、不报错，但写进库的值**错位/丢值**。（块 3 前一笔） |
| 缺陷 ② | `AuditService.record(..., "template"/"goodstype", null, ...)` 把 `TargetId` 传 `null`；而 `admin_audit.TargetId` 是 **NOT NULL** ⇒ 插入必失败，被 fail-open 吞掉 ⇒ 创建类动作留痕 **100% 丢失**。（详见 `slot-registry.md` §19-9-8；日志/库/证据见 `docs/agent/evidence/c3-block3-snapshot/`） |
| 为什么「三绿 + A3」全拦不住 | 两者都是**静默失败**：不报错、不崩、`mvn test` 绿（246 用例）、前端 `build` 绿、`eslint` 绿、**A3 也绿**（`gates.failed == 0`）。根因是 `AuditService.record` **fail-open**（`try`/`catch` + `log.error`，业务不受影响）⇒ 留痕缺口对测试/CI **系统性不可见**，只有**进程日志**或**真库查询**才看得见。 |
| 缺口 | 现有测试**没有一条**验证「失败路径会留下可观测痕迹」：既无对 DAO SQL 的**参数计数**静态检查，也无对**审计/通知留痕**的真库断言，更无对 **fail-open 分支**的显式用例。 |
| 建议手段（用户 2026-10-10 指定） | ① **DAO SQL 参数计数检查（静态）** —— 占位符 `?` 个数 ⟺ 实参个数，编译期或单测期即可拦；② **审计/通知留痕断言（真库冒烟）** —— 至少对「新增类动作」断言真库落了行、关键列非空；③ **fail-open 路径显式验证** —— 构造「写入必失败」的输入，断言**要么成功留痕、要么有日志证据**，而不是默默吞。 |
| 处理时机 | **块 3 收口时挂**，**块 8 部署前**一并处理。 |
| 优先级 | 中高 —— 它不会让任何现有绿灯变红，只会让**应记录的没记录、应写对的数据写错**；正是「绿得让人放心」的那类隐患。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 3 就地改造）**。两个已发现的实例**已就地修**（见 `slot-registry.md` §19-9-8），本条管的是**同类路径的测试覆盖**。 |
---

## TD-21 `order` 表没有资金态 ⇒ 强制退款「知道但不做」+ 结算部分失败不可回滚

| 项 | 内容 |
|---|---|
| 现状 | `order` 只有 `PayIf` / `DeliveryIf` / `ReceiveIf` **三个状态位**，**没有任何资金列**（金额只有 `Price`，无「已退」「退款中」）。`PayIf` 是**单调**的 0→1（CAS `WHERE PayIf=0`），**回退会破三位单调性**。 |
| 后果 ① | 管理端 `Action = refund` / `dispute` 在 `AdminOrderService.intervene` 被**明确拒绝**（回「强制退款本块未实现：order 表没有资金态…」，**不是**「未知动作」）—— 这是**有意的**，不是漏做。 |
| 后果 ② | `/cart/checkout` 逐行建订单，**无 `@Transactional`**（本仓订单链路的结构性约束）⇒ 中途某行失败时**已建订单不回滚**，返回 `{success:false, msg, list:[已成功行]}`，失败行**留在车里**。账实差由调用方按 `list` 自行对账。⚠️ **2026-10-11 口径更新（可视化段 commit 0b）**：建单粒度由「**逐行**」改为「**按商家分组**」（N 行 ⇒ M 张单，M = 商家数）⇒ 本条**性质不变、颗粒度变粗**：失败时是**整组**不回滚（原先只影响一行）。`list` 的语义（已成功**行**）**未改**。 |
| 处理时机 | **需业务输入**（要不要引入资金态 / 补偿表）⇒ 未排期。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4）**。 |

## TD-22 `cart` 的物理 `DELETE` 是全仓对「规则 v2：逻辑废弃、不物理删」的**唯一破例**

| 项 | 内容 |
|---|---|
| 现状 | `/cart/remove` 直接 `DELETE FROM cart WHERE …`（`CartDao`），**不是**软删。全仓其余 18 张表无一处物理删行。 |
| 为什么可以 | 用户 **2026-10-10 明确批准**：购物车行是**用户可再生成的临时选择**，不是需要留痕的业务事实；软删会引入「已移除但还在表里」的读侧过滤负担。 |
| 代价 | ① 行没了 ⇒ **没有任何「谁什么时候移除了什么」的痕迹**（`cart` 表无审计，接口**未挂 `auditService`**）；② 若将来要「最近删除 / 恢复」，需要新表。 |
| 边界 | 破例**只限 `cart` 表**。不要把它当先例推到 `order` / `goods` / `user` 上 —— 那三张都有历史语义。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4）**。 |

## TD-23 归属校验（`IdentityGuard`）是 **fail-open** ⇒ 身份解析不出来即放行

| 项 | 内容 |
|---|---|
| 现状 | 块 4 给 3 条 CAS 端点（`orderDeliverySelect` / `orderReceiveIfUp` / `orderDeliveryIfUp`）补了 `IdentityGuard.requireSelfOrAdmin`，但该守卫本身是**三档 fail-open**：**解析不出身份 = 放行**（`IdentityGuard.java` 类注释「为什么解析不出身份 = 放行」；`requireSelfOrAdmin` 的第 1 档直接 `return true`）。 |
| 后果 | 新增的归属校验**只在「能认出你是谁」时**才拦得住越权；会话缺失/解析异常时退化回「谁都能改别人的单」—— 与补校验之前**同形**。它不是 fail-closed 的授权。 |
| 为什么这么设计 | 与既有 13 处一行守卫**共用同一套三档语义与同一套 fail-open 纪律**（块 4 刻意不引入第二套语义）。 |
| 处理时机 | 与「鉴权/会话」笔一并裁（是否引入 fail-closed 档）。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4）**。 |

## TD-24 管理端介入的「强制发货 / 强制完成」**各让「该轮被推进的对端」收到两条通知**

| 项 | 内容 |
|---|---|
| 现状 | `AdminOrderService.notifyBoth` 在成功后给**双方**各发一条 `ORDER_INTERVENED`（「管理员介入订单」）；而 `OrderService` 里发货 / 收货**自己还会**触发一条「对端通知」（用户自己操作时也走的那条）⇒ **发货轮买家收 2 条**（`order.notice`「商家已发货」+ `order.intervened`）、**收货轮商家收 2 条**（`order.notice`「买家已确认收货」+ `order.intervened`）；**另一端各只有 1 条** `order.intervened`。2026-10-10 实测（order `CHKM360570571420`，VM 库 `notification`）：发货轮 `65/66` 归买家、`67` 归商家；收货轮 `68/70` 归商家、`69` 归买家。 |
| 为什么有意 | 两条来自**不同事件、由不同 actor 触发**，合并会丢掉「管理员介入了」这个事实（`AdminOrderService` 类注释已写明）。 |
| 代价 | **被推进的对端**观感冗余（不是「买家侧」——完成轮是商家侧）；前端若要合并展示，需按 `BatchId` / 事件类型去重 —— 目前没有去重。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4）**。 |

## TD-25 `ShoppingCart.vue` 的 `showSwitch` / `orderTab` / `@toggle` 三个 prop **全线退役**（块 4 起无消费者）

| 项 | 内容 |
|---|---|
| 现状 | 块 4 把 `/merchant/order/cart` 与 `/customer/cart` 的 `component` 都换成容器 `OrderPane`，并**删掉** `props:{showSwitch:false}`。原 R1 那条「必须传 `false`，否则凭空多出 📋/🛒 开关」的告警**随之作废**。 |
| 残留 | 三个标识符现在**没有任何读者**：容器 `OrderPane` 不认，顶栏走高亮（`meta.tab`）而不走 `toggle`；`ShoppingCart.vue` 被 `OrderPane` 内嵌，不再从路由收 prop。 |
| 风险 | 若有人按旧文档/旧注释再传这三个 prop，会变成**根元素上的野属性**（Vue 不报错，只是把 `showswitch="false"` 写到 DOM 上）。 |
| 处理时机 | 「前端 API 层规范化」笔或块 4 收口时清注释/墓碑。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4）**。 |

## TD-26 `OrderCenter` 的订单表**仍然没有「数量」列**（`Quantity` 落了库、没上屏）

| 项 | 内容 |
|---|---|
| 现状 | 块 4 给 `order` 加了 `Quantity`（A2），`OrderDao.selectByMerchant` 两条 SELECT 都已带上；但商家端 `OrderCenter.vue` 的 `thead` 仍是 **商品 / 订单号 / 买家 / 金额 / 状态 / 下单时间 / 操作** —— **无数量列**。 |
| 后果 | 商家看到的「金额」与「件数」脱钩（多件单只显示总额）。不是算错，是**不显示**。 |
| 为什么没做 | 块 4 的表结构改动只保证**数据到位**；改列结构 = 改那页的固定列宽布局（`OrderCenter.vue` 有一整套实测列宽注释），属另一笔。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4）**。 |

## TD-27 订单异常监控的两个超时阈值（24h / 48h）是**拍的**，本仓没有 SLA 配置

| 项 | 内容 |
|---|---|
| 现状 | `AdminOrderDao.UNPAID_HOURS = 24` / `UNDELIVERED_HOURS = 48` 是**硬编码常量**，随 `/admin/order/monitor` 响应回传（前端不写死）。 |
| 后果 | 「超 24h 未付款」这类判据**没有业务依据**；阈值改了等于换了监控口径，但**没有任何配置面**，只能改代码重发。 |
| 边界 | 阈值是**常量并已标注待裁**（不是埋在 SQL 里当既有事实）—— 真正的时效需业务输入。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4）**。 |

## TD-28 物流「已签收」≠ 订单「已收货」—— 调度器只追加轨迹、不碰状态位的**必然结果**

| 项 | 内容 |
|---|---|
| 现状 | `ShippingTrackAdvanceJob` 按 `ShippedAt` + 阈值补**四条**轨迹（**按时序**：`in_transit`「运输中」→ `picked_up`「驿站已揽收」→ `out_for_delivery`「驿站派送中」→ **`delivered`（「已签收」）**；前两步 2026-10-11 对调，四条全于 2026-10-10 从发货路径搬进本任务，见 TD-32）；它**只 INSERT `order_shipping_track`，绝不 UPDATE `order` 的状态位**（用户 2026-10-10 批准的红线）。⇒ 轨迹走完时 `order.ReceiveIf` **可能仍是 0** —— 那要**买家自己点【收货】**才置 1。 |
| 为何不修 | 这是设计约束的**数学后果**，不是漏写：若让任务顺手把 `ReceiveIf` 置 1，就等于绕开 `OrderStateMachine` 的 CAS 守卫去改状态位 ⇒ 破 A3 的时间线因果链、破「状态位只由三条边推进」的不变量。用户 2026-10-10 明确**接受**这个语义（真实电商亦如此：快递显示签收，用户没点「确认收货」）。 |
| 已做的缓解 | 前端**分两词**：物流时间线说「已签收」，订单状态说「已收货」；管理端两条时间线**并列不合并**；收银台 / 物流弹窗各有明说文案。 |
| 残留风险 | 用户看到「已签收」而订单仍停在「已发货」，可能以为页面卡了 —— 靠文案消解，**无强制对齐机制**。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4 补丁）**。 |

## TD-29 「模拟支付」没有真实网关：`TradeNo` 是生成的演示流水号

| 项 | 内容 |
|---|---|
| 现状 | `payIfUp` 成功后由后端生成 `TradeNo = "PAY" + LocalDate.now().format("yyyyMMdd") + 6 位随机`（`OrderService.newTradeNo()`），写进 `order.TradeNo` 并随响应回传；`PaymentMethod` 记的是收银台下拉选的**标签字符串**（支付宝 / 微信支付 / 银行卡，`ORDER` 表无字典表约束）。 |
| 后果 | ① 序列号**可能重复**（随机 6 位 + 同日 ⇒ 生日碰撞，且**无唯一约束**）；② 无对账 / 退款 / 幂等支付通道；③ `PaymentMethod` 是**自由文本**，不是受控字典。 |
| 边界 | 全部前端文案已明说「演示环境的模拟收银台…不会真的扣款」。这是**演示可取、生产不可取**的一条，须在接入真网关时整体替换（含 `order` 缺资金态，见 TD-21）。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4 补丁）**。 |

## TD-30 `order` 的「状态位 + 明细列」是**两条 UPDATE**，原子性靠订单锁而非事务

| 项 | 内容 |
|---|---|
| 现状 | CAS 改状态位走 `OrderStateMachine.updateSql(action)`（golden copy 钉死，**不得改**）；补丁新增的 7 个明细列走**第二条** UPDATE（`detailUpdateSql(action)`）。两条在同一把 `LockName.ORDER_*` 锁内先后执行。 |
| 后果 | ① 若第二次 UPDATE 失败（库不可达等），**状态位已置、明细列未写** —— 且 Service 是 **fail-soft**（只 WARN，不抛）⇒ 调用方仍收到 `success:true`；② 明细列的**失败是不可见的**（响应体与成功同形），验收必须**回库读列**。 |
| 为何不加事务 | `OrderService` 及其调用链**全项目唯一禁止 `@Transactional`** 处（锁外事务会让 `runLocked` 的临界区 COUNT 读不到前一未提交行，破 `orderAdd` 的查重）—— 判据逐字在该类类注释。 |
| 已做的缓解 | 第二条 UPDATE 在**锁内**（消除「两笔付款并发各写一半」的竞态）；失败只 WARN、不翻转已被 CAS 确认的成功。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4 补丁）**。 |

## TD-31 管理员代发（`intervene` 的 `deliver`）**不带快递信息**

| 项 | 内容 |
|---|---|
| 现状 | `AdminOrderService.intervene` 调 1 参重载 `deliveryIfUp(orderId)` ⇒ `ExpressCompany` / `TrackingNo` 两项落 `NULL`（`ShippedAt` 仍写 `NOW()`）。管理端介入界面**没有**填快递信息的入口。 |
| 后果 | 被管理员强制发货的单，买家在【物流】里看到「承运 / 单号 —」，只能看到轨迹时间线。 |
| 为何不改 | 改成 3 参重载要动 `intervene` 的调用面（用户要求 **`intervene` 零改**）；给管理端加输入框 = 扩大本补丁范围。 |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4 补丁）**。 |

## TD-32 JDBC URL 带 `serverTimezone=Asia/Shanghai` 而库时钟是 **UTC** ⇒ 任何 `java.sql.Timestamp` 参数都会 **+8 小时**

| 项 | 内容 |
|---|---|
| 现状 | 三个 datasource URL（默认 / a3 / 测试）都写着 `serverTimezone=Asia/Shanghai`，而目标库的时钟是 **UTC**（VM 实测 `@@system_time_zone=UTC`、`NOW()` 比本机墙钟晚 8 小时）。`java.sql.Timestamp` 携带的是一个**时刻（instant）**，无时区文本格式可依 ⇒ 驱动按 `serverTimezone` 把它表述成**东八区墙钟**再发出去，落进 UTC 库就成了 **+8h**。 |
| 不受影响的写法 | `LocalDateTime` / `String`（无时区的**文本**）、以及一切在 SQL 里算的时间（`NOW(3)` / `DATE_ADD(?, INTERVAL n SECOND)`）—— 它们不含时刻语义，驱动不折算。**这就是 2026-10-10 定下的统一口径「时刻只由库时钟产生，Java 侧不构造任何带时区的值」。** |
| 已修的点 | ① `OrderTimelineWriter` 从 `new Timestamp(occurredAtMillis)` 改成 `toUtcText(...)`（`ZoneOffset.UTC` 硬编码，注释禁止 `ZoneId.systemDefault()`），`order_state_transition.OccurredAt` 不再 +8h；② `ShippingTrackDao` 去掉 `Timestamp` 参数，改由 `DATE_ADD(发货文本, INTERVAL 秒 SECOND)` 出 `TrackedAt`；③ `ShippingTrackAdvanceJob` 不再用 `System.currentTimeMillis()` 推偏移。存量脏行（2026-10-10 前落的 3 条 `order_shipping_track`，`TrackedAt` 比 `created_at` 大 **28800** 秒）**不回溯改写** —— 轨迹是过程记录（同「逻辑废弃、不 UPDATE」口径）。 |
| 为何登记 | 这条**没有任何机制兜住**：URL 里那一句还在，谁再写一个 `new Timestamp(...)` / `setTimestamp` / 一个 `java.util.Date` 参数，就会**静默**再落一个 +8h，且**看不出来**（格式完全正常，只是差 8 小时）。连 `OrderTimelineWriterTest` 那种 golden test 都只能钉住**当前**取值，钉不住「以后别再用 Timestamp」。 |
| 后果 · 环境耦合 | 统一口径的基准是 **VM 库的时钟 = UTC**。而**默认 profile 的 datasource 指向本机 MySQL**（`application.properties:5` `localhost:3306`，root/123456）—— 本机库的时钟若是机器本地时间（CST），则「库文本 = UTC 文本」这个前提在**默认 profile 手验时并不成立** ⇒ 前端那层「统一转本地」（`OrderMine.vue:185` 等 4 处）会把本机库的值**再推 8 小时**，屏幕上看像是新的 bug（其实是环境差异，同 `api-contract.md` 里 `AuditPage` 那条本地库注记）。**手验时间显示要么指向 VM 库，要么先核本机库的 `@@system_time_zone`。** |
| 状态 | ⏸ **登记不修（2026-10-10，C3 块 4 补丁）** —— 彻底修法是去掉 URL 里的 `serverTimezone`（或改库时区），但那会同时动三个 profile 的连接串与全部既有测试的时间断言，超出本补丁范围。 |

## TD-33 `user` 表的四个时刻列走 **JVM 时钟**，与库时钟（UTC）**不是一条轴** ⇒ 同一页面上两套轴

| 项 | 内容 |
|---|---|
| 现状（实测） | `bannedAt` / `ScheduledDowngradeAt` / `FrozenAt` / `FreezeUntil` **四列全部由 Java 侧 `LocalDateTime.now()` 绑参写入**，不是 SQL 的 `NOW()`：`MerchantDowngradeService:102-103`（`LocalDateTime now = LocalDateTime.now(); LocalDateTime dueAt = now.plusDays(NOTICE_DAYS)` → `markScheduled`）、`:200` / `:252`（`markFrozen(username, now, now.plusDays(FREEZE_DAYS))`）、`UserGovernanceService:78`（`updateBan(username, true, reason, LocalDateTime.now())`）。而 JVM 时区实测是 **CST**（C3 块 4 补丁核验实例 8089 的启动日志逐字：`2026-10-10T22:49:27.590+08:00`）。⇒ 落进库的是 **CST 墙钟字面量**。⚠️ 读取侧 `UserGovernanceDao:54` / `:56-58` 一律 `DATE_FORMAT(…,'%Y-%m-%d %H:%i:%s')` 交回**无时区文本** ⇒ 前端**无从分辨**哪一列是哪条轴。 |
| 后果 ①（同页两套轴） | `AccountsPage.vue` 对同一张表的两组时刻用了**同一个** `toLocal`：`:83`（`SubmittedAt`）/ `:93`（`ReviewedAt`）来自 `merchant_application`、走**库时钟**（对）；`:169`（`bannedAt`）/ `:672`（`ScheduledDowngradeAt`）/ `:675`（`FrozenAt`）/ `:742`（`data.scheduledAt`，源 `MerchantDowngradeService:110` 的 `dueAt.toString()`）全走 **JVM 轴** ⇒ 在 VM / a3 上这 4 处会被**再推 8 小时**。 |
| 后果 ②（本机看不见） | **本地开发库**的时钟 = 机器时钟 = CST，而 JVM 也是 CST ⇒ 两条轴**恰好重合**，这四列的 `toLocal` 表现正常。这正是它至今没被发现的原因 —— ⚠️ **「在本地库上看起来是对的」不能作为判据**（同 `AuditPage.vue:204-206` 已记的那条环境边界 —— 行号 2026-10-11 收口重指，原 161-163 行；本条是它在 `user` 表上的具体后果）。 |
| 后果 ③（原样显示也不对） | `UserInfoPage.vue:333` 把这三个值**原样**打了出来 ⇒ 在 **CST 客户端**上恰好对、在 **UTC 客户端**上差 8 小时。「对不对」取决于谁在看 —— 说明这不是前端括号的问题，是**写入轴**的问题。 |
| ⚠️ 与 TD-32 的关系 | TD-32 的「统一口径」是「**时刻只由库时钟产生**」。这四列**整列违反**该口径 —— 但它们同时又是 `selectDueDowngrades(LocalDateTime now)` 的比较基准（写读同源、调度自洽），**改轴会连带改降级三阶段状态机的语义**，还要同步 `MerchantApplicationService:240` 的自动恢复判据（`LocalDateTime.now().isBefore(LocalDateTime.parse(freezeUntil…))`）与既有演示数据。 |
| 为何本笔不修 | 「这四列到底该用哪条轴」是一次**口径裁决**（要么全改库时钟、要么明确宣告 `user` 表是 JVM 轴并在前端分列处理），不是本补丁顺手能定的。⚠️ 本笔**曾**按「统一口径」给 `UserInfoPage.vue` 加过 `toLocal`，**实测后已撤销** —— 那会让显示**多** 8 小时（拿 JVM 轴的值当 UTC 折）。 |
| 状态 | ⏸ **登记待裁决（2026-10-10，C3 块 4 补丁）**。 |

## TD-34 「超时未付款自动取消」的四个已知边界（`CancelledAt` 是第 4 态，但**不是**一条 CAS 边）

| 项 | 内容 |
|---|---|
| ① 取消**不记** `order_state_transition` | `OrderAction` 仍**只有 4 个**（`CREATE/PAY/DELIVER/RECEIVE`），取消**不新增第 5 个**。理由：`A-S3-2` 等断言按「tag 基数有界（4 个）」读，新增动作会动到那条口径；且取消是**系统按时间**触发的、不是某个人的一次 CAS 转移（`OrderStateMachine` 的三张守卫表也表达不了「时间到了」）。**后果**：`order_state_transition` 里**看不到取消这一笔** —— 想知道「这单被谁/何时取消的」只能读 `order.CancelledAt`（没有 from/to、没有 actor）。 |
| ② 付「已取消」的单返回**既有**文案 | 本笔追加取消谓词后，PAY 的守卫是 `PayIf = 0 AND CancelledAt IS NULL`（`OrderStateMachine.cancelGuard`）⇒ 已取消的单**CAS 命中 0 行**，复用既有的 `noRowsMsg` —— **「订单不存在或已付款」**。语义上「已取消」不等于「不存在」也不等于「已付款」，**这条文案偏窄**。⚠️ 不改它的理由：那条文案被 `A-S3-2` 逐字断言（20 并发支付里 19 个返回它），加一条「订单已取消，无法付款」虽然**只对已取消的单**生效（不动 `A-S3-2` 那条路径），但属**新增拒绝文案**，本笔按「不夹带」留给下一笔。**前端已先兜住**：`OrderMine.vue` 对已取消的单**不渲染付款按钮** ⇒ 这条路径要走到得绕过 UI。 |
| ③ 取消**不发通知** | 用户把「通知买家『订单超时已取消』」标成**可选**，本笔**未做**。`NotificationService` 有现成的发送面，接线是一次独立的小笔。 |
| ④ 阈值是**演示口径** | `mall.order.cancel.timeout-ms` 默认 **60000 ms**、`.fixed-delay-ms` 默认 **15000 ms**（用户 2026-10-10 定的演示节奏）。生产口径（通常是 15–30 分钟）**只需改配置**。⚠️ 与物流四节点同款：`a3` 下 `mall.order.cancel.enabled=false` ⇒ **a3 手验看不到本任务**（调度器总闸本来也不在）。 |
| ⚠️ 与「约束 4（不碰 `OrderStateMachine`）」的**范围关系** | **本次不违约束 4** —— 这是「旧令本就不适用本次」，**不是**「破一次例」。约束 4 管的是**「介入动作」**（新增状态位 / 改守卫表 / 走非既有 CAS 边），**不管「付款业务规则」**；本笔在 `updateSql(PAY)` 尾部追加 `AND CancelledAt IS NULL`（**改的是 WHERE，不是 SET**；**未新增任何状态位**）属**付款业务规则**，落在约束 4 的范围**之外**。用户 2026-10-11 澄清的口径：旧令管介入动作、不管付款规则 ⇒ **本就不适用本次**。收益是 `docs/agent/db-schema.md` **§12.4** 说的**单语句原子**（「先查再 CAS」会留 TOCTOU 窗口，`runLocked` 取锁失败是 fail-open）。 |
| 状态 | ⏸ **登记（2026-10-10，C3 块 4 补丁 · 第 2 笔）**；①②③ 均为**已知边界、非缺陷**。 |

## TD-35 `a3` profile 一 profile 两用（CI 门禁 vs 日常开发）⇒ 拆出孪生 `dev`

| 项 | 内容 |
|---|---|
| 现象 | 用户在 8088 上挂 `a3` 做日常开发 / 手验，**看不到 job 类功能**（物流轨迹不推进、超时未付款不取消）—— 因为 a3 的三个「会自己醒来的东西」开关全关（压测确定性）。2026-10-10 用户报「物流轨迹不更新 / 60 秒取消没实现」，真因即此。 |
| 根因 | `a3` 被赋了两件**需求互斥**的职责：① CI 的 A3 并发门禁（要求 job 全关，同一份输入→同一份结果）；② 日常开发 / 手验（要求 job 全开）。一个 profile 表达不了两个相反的取值。 |
| 连带 | 「攻击模拟器」改自 A3 的**并发脚本框架**（`scripts/concurrency/`），但目的是**演示效果** ⇒ 同样要 job 开；若沿用 a3 profile 会撞同一面墙。复用的是**框架**，不是 profile。 |
| 修法 | 拆 profile（2026-10-11 用户批，A 档）：**a3 收窄为 CI 专用、一行不动**；新建孪生 `application-dev.properties`（= a3 的复制，仅三个 job 开关 false→true，另 env 前缀 `A3_`→`DEV_`、上传目录 `a3-uploads`→`dev-uploads`）。`HealthController` 的 `@Profile` 由 `"a3"` 放宽为 `{"a3","dev"}`（使攻击模拟器可复用 A3 的 P5/P6/P7 探针，它们打 `/health/db`）。 |
| 为什么不是「直接开 a3 的 job」 | A3 门禁断言「120 QPS 下每单恰好 1 次成功」这类**确定性**结论；job 每 15 秒自己醒来扫库改状态，会让基线混进一个与压测无关的变量 ⇒ 断言可能假红 / 假绿，**门禁失去可信度**。 |
| 为什么不是「include a3」 | Boot 的 `spring.profiles.group.dev=a3` 会把 a3 排在 dev **之后** ⇒ 后加载者赢 ⇒ a3 的 `job=false` 反而盖住 dev；反过来 `group.a3=dev` 又让 CI 的 a3 也吃到 `job=true`。分组方向在此是反的，**只能复制**。 |
| 已知代价（登记不修） | **孪生漂移**：a3 的「连库 / Redis / TTL / 池」将来若变，`application-dev.properties` **不会自动跟** —— 必须手工同步。判据：两文件的 diff 应**恒等于**那三行 job 开关（+ 两处命名）。⚠️ **没有机制兜住它**（同 TD-32 的告诫）。 |
| 边界 ① | `dev` 的 `app.auth.enabled` 也是 **false**（与 a3 孪生）⇒ **日常手验仍 ≠ 生产鉴权行为**，「手验绿 ≠ 默认 profile 绿」这条告诫**依然成立** —— 只是「默认 profile」现在指 `application.properties`（`app.auth.enabled=true`）。要验鉴权本身，用默认 profile。 |
| 边界 ② | CI **不受影响**：`ci.yml` 的 concurrency job 仍显式 `SPRING_PROFILES_ACTIVE: a3`；`dev` 只在本机 / 攻击模拟器上用。 |
| 边界 ③ | IDEA run config / 日常启动命令由 `a3` 改 `dev` 是**用户侧操作**（本次不改任何 IDE 文件）。 |
| 状态 | ✅ **已落（2026-10-11，未提交）** —— `application-dev.properties` 新建；`HealthController` 的 `@Profile` 放宽；`application-smoke.properties` 补 `mall.order.cancel.enabled=false`（同族对称）；`environment.md` / `project-map.md` 同步。 |

## TD-36 `goods_sku.Stock` **只存不扣**：下单不会减库存 ⇒「可售量」这个概念在本轮**不存在**

| 项 | 内容 |
|---|---|
| 现状 | 多规格商品（`goods_sku`）的价格 / 库存 / SKU 码由商家**逐格填**，但 `Stock` **只是陈列值**：加入购物车（`/cart/add`）与下单（`/order/orderAdd`）**都不读、不减**它（`db-schema.md §13.3`）。`goods_sku.SkuCode` 在加购时**作为 `ProductCode` 上报** ⇒ 下单链路与购物车链路**一行未改**。 |
| 后果 | ① 前端显示的库存**不随成交变化**（卖完了还显示有货）② 多规格与单型号两条路径的库存语义**一致地「没有」**（`goods` 侧本来也不扣，`productsales.OrderVolume` 恒为 0，见 `db-schema.md §1`）⇒ 本轮**没有引入新的不一致**，只是把「没有库存语义」**显式化**到多规格上 ③ 任何「超卖」判断都不存在。 |
| 处理时机 | 用户 **2026-10-11 已知并同意**：扣减（含并发下的防超卖）进**单独一轮**。未排期。 |
| 状态 | ✅ **已关闭（2026-10-14，块 7 commit 1 `b1477cc`）** —— CAS 扣减 + 下单即扣 + 超时释放落地，`goods_sku.Stock` **不再只是陈列值**；口径见 `db-schema.md §17` 与 `progress.md §7-B7` 提交表的第 3、8 行。 |

## TD-37 演示数据的两处**已知残留**：种子的明文口令 · 未裁的存量脏行

| 项 | 内容 |
|---|---|
| ① 明文口令 | `seed-demo.sql` 的 `login.Password` **= 用户名**（对齐存量 `1/1`、`2222/2222` 的既有惯例，**不是**本笔新开的口径）。⚠️ 实测风险：**明文口令会被 push 侧的 `[Credential Leakage]` 拦下** ⇒ 处置是**改占位符再 `git commit --amend`**，**不硬推**。 |
| ② 未裁两件小事 | `goods` **Id 120 的重复行**、`customerimage` / `merchantimage` **各 1 行残留**（对照表见 `docs/agent/evidence/d-seed/README.md`）—— **用户尚未裁决「清还是留」** ⇒ 本轮**一行未动**。 |
| ③ 已裁保留（不再处理） | **6 笔旧单**（`ProductInformation` 指向已删商品）· **5 月中 → 10 月中空档**（看板用「近 30 天」绕开）⇒ 判定为**保留**，不是待办。 |
| 状态 | ⏸ **登记不修（2026-10-11，可视化段 commit 4 / D 笔）**；② 的裁决权在用户。 |

## TD-38 秒杀**不真扣减**：`seckill_activity.SoldStock` 是**陈列值**（块 5 与 `goods_sku.Stock` 同款）

| 项 | 内容 |
|---|---|
| 现状 | `seckill_activity` 有 `TotalStock` / `SoldStock` 两列，用户端 `/marketing/seckill/live` 每行回**现算的** `SoldOut`（`SoldStock >= TotalStock`）。但**下单不读、不减** `SoldStock` —— 块 5 的「已售完」演示靠**手动置满**（`db-schema.md §14.1` 那一句「块 5 手动置满演示」）。 |
| 后果 | ① 秒杀「限量 N 件」在**当前轮**是**展示语义**不是**约束** ⇒ 同一场秒杀可以被无限下单 ② `SoldOut` 只会随人工改库变化，不随成交变化 ③ **与 TD-36 同源**：本轮的库存语义在「单型号 / 多规格 / 秒杀」三处**一致地不存在**（一致，不等于正确）。 |
| 处理时机 | 用户 **2026-10-12 挂账**：秒杀真扣减（含并发下的防超卖）与 SKU 扣减**一起进块 7**（可观测 / 治理块）。未排期。 |
| 状态 | ✅ **已兑现（2026-10-14，块 7 commit 2c+2d `96232eb`）** —— 秒杀扣减接线 + 超时释放条件化（库存偏账根治）；`seckill_activity.BaselineSoldStock` 同 `goods_sku.BaselineStock`，是「人工四步」载体（`db-schema.md §17.2`）。 |

## TD-39 国补**商家主动申请通道未落**：只落了「平台提名 → 商家应答」的**单向**流程

| 项 | 内容 |
|---|---|
| 现状 | `subsidy_goods` 的两态由**管理端**发起：`AdminSubsidyController.nominate`（管理员提名一件商品）→ 商家侧 `/marketing/subsidy/invitations` 收到「待应答」→ `/marketing/subsidy/confirm` 同意 / 拒绝。**商家自己发起报名**的那条路（对称于秒杀的 `seckill_application`）**没有落** —— `SubsidyController` 只有 invitations / confirm / goods 三条，`AdminSubsidyController` 只有 list / create / stop / activate / nominate / nominations 六条。 |
| 前情 | 块 5 内**曾判「补，必须」**（与秒杀的双向制对齐），但落码时**只落了平台活动 tab 的「待应答」**，商家侧无发起端。⇒ 这与「秒杀是双向制」形成**不对称**：同一件事在秒杀有两条路、在国补只有一条。 |
| 后果 | 商家想参加国补**只能等平台来提名** —— 平台没提名的商品永远进不去，即使它符合类目条件。功能上「能跑通」，产品上是一个**缺口**。 |
| 处理时机 | **单独登记**（用户 2026-10-12 挂账原文：「之前判『补，必须』，但只落了平台活动 tab 的『待应答』，主动申请未落 ⇒ 单独登记」）。**未排期** —— 若补，落法照秒杀的 `SeckillController.apply` + `AdminSeckillController.review` 一对（含 `subsidy_goods.Status` 的 `submitted` 态；⚠️ 该表当前 `Status` 是 pending/confirmed/rejected ⇒ **补这条路要加一个状态值**，属改表语义，需用户裁）。 |
| 状态 | ⏸ **登记不修（2026-10-12，块 5 收口）**。 |

## TD-40 `doc-anchor-check.sh` 的两枚**死锚点**（226 / 267）未清

| 项 | 内容 |
|---|---|
| 现状 | `bash scripts/doc-anchor-check.sh` 的「**死锚点**」档（`{#db-*}` / `{#api-*}` / `{#pack-*}` 三种前缀里**被定义但无人引用**的锚）实读剩 **2 条**，位于本仓 `docs/agent/` 的两个文件（登记时点记作 **226 / 267**）。⚠️ 该脚本把「悬空引用（真错，`exit 1`）」与「死锚点（可能只是预留，`exit 0`）」**分开报** ⇒ 这两条**不会**让脚本红。 |
| 为什么留 | 死锚点**可能只是预留**（脚本自己的设计口径）。删锚点会让指向它的**外部链接失效**；保留只占一点噪音。故用户 2026-10-12 裁：**后续清理**，不夹带在块 5 里。 |
| 处理时机 | 「文档漂移清理笔」（与下面 TD-41 / TD-42 同批）。⚠️ 清单以**当时实跑**为准，别照抄这里的行号 —— 本文件 / `slot-registry.md` / `db-schema.md` 的改动都会让它们漂。 |
| 状态 | ⏸ **登记不修（2026-10-12，块 5 收口）**。 |

## TD-41 `scripts/refs-*.txt` 扫描产物**入库**（体量持续增长，无清理机制）

| 项 | 内容 |
|---|---|
| 现状 | `scripts/` 下 `refs-*.txt` 是 `refs-scan.sh` 的快照产物（`<文件>:<行号>:<token>` 行）。**已入库 30 份 / 4.9 MiB**（2026-10-12 实测）；块 5 收口**又新增 5 份 / 1.3 MiB**（`refs-b5-close-before` / `refs-b5d-after` / `refs-b5h-after` / `refs-b5h2-after` / `refs-before-b5h`）⇒ 其后为 **35 份 / ≈6.2 MiB**。另有 **2 份被 `.gitignore` 忽略**（`refs-before.txt` / `refs-after.txt`，那两条是脚本的默认输出名）。全目录（含被忽略的）**37 份 / 6.6 MiB**。 |
| 为什么留到现在 | 每一份都是**某一笔改动前后**的对照底本（`refs-scan` 的 LOST 判据靠它）⇒ 删早了就**没法复核对账**。且按先例（块 3 / 块 4 的产物**已在库**）本笔**照旧入库**，不擅自开「不入库」的先例。 |
| 后果 | ① 仓库体积随时间线性增长（每笔 100–200 KiB，大笔可达 1 MiB+）② 全仓 `grep` / `refs-scan` 会**反复扫到这些产物本身** —— 脚本已用「跳过 `refs-(before\|after).*\.txt`」兜住，但只兜住**文件名匹配**的那一类。 |
| 处理时机 | 用户 2026-10-12 裁：**后续清理**。可选口径（**待用户选**）：甲 = 只留最近 N 份 / 乙 = 全部转 `_work/`（不入库）/ 丙 = 全删（牺牲可复核性）。 |
| 状态 | ⏸ **登记不修（2026-10-12，块 5 收口）**。 |

## TD-42 `refs-scan` 的「**兄弟 token**」未扫全：同文件内的等价引用不受监控

| 项 | 内容 |
|---|---|
| 现状 | `refs-scan.sh` 抓的是 `文件:行号` 形态的**跨文件**引用。**同一个文件内部**「上面那段说『下面那段』」式的指代（既没有 `文件:` 前缀、也没有行号）、以及**同一语义的另一个写法**（如 `:97-104` 与 `:97`、`第 97 行`），都**不在产物里** ⇒ 它们漂了，扫描器**看不见**（脚本头注自己写的**已知盲区**）。 |
| 后果 | 「LOST = 0」只证明**被扫到的那一类**没丢，**不等于**全仓引用都没漂。⚠️ 这是**口径边界**，不是块 5 引入的缺陷 —— 但块 5 改形了两页（`HomePage.vue` / `ShopHome.vue`）⇒ 这类隐藏引用**可能变多**。 |
| 处理时机 | 用户 2026-10-12 裁：**后续扫全**（把兄弟 token 也纳入判据）。未排期。 |
| 状态 | ⏸ **登记不修（2026-10-12，块 5 收口）**。 |

## TD-43 共享面的「国补」绿标**待复验**：验收时点全库唯一的国补商品被过滤掉了

| 项 | 内容 |
|---|---|
| 现状 | 块 5 手验时点，共享面（主页「为你推荐」/ 搜索页 / 两处「猜你喜欢」）的过滤规则是「滤掉 `IsSoldOut`」（TD 无关，是用户 ② 的口径）；而当时**全库唯一的国补商品恰好就是那件已售完的手环** ⇒ 绿标在共享面**不可见**，只在店铺页可见。⚠️ **验收期间数据变了**：库里多了一件国补商品（`Id=77`「无线蓝牙耳机 声谷 Pro 降噪」）⇒ `goodsSelectHome` 的 `IsSubsidy` 由 1 变 2，主页**确实**出现了绿标（实测带真标签的卡：`无线蓝牙耳机 声谷 Pro 降噪[国补]` / `机械键盘 87 键 茶轴[秒杀商品]`）。 |
| 后果 | 「共享面绿标」这条**已经验过一半**（数据一变就出现了），但**没有一条固定的回归判据** —— 换一台机器 / 重导种子后，若又没有「在售 + 国补」的商品，这条路径会再次**静默地不可见**（而不是报错）。 |
| 处理时机 | 用户 2026-10-12 挂账：**等有在售国补商品后复验共享面绿标**。若要做成**常绿**判据，需在种子数据里固定至少一件「在售 + 国补」商品（**改种子 = 数据变更，需用户点头**）。 |
| 状态 | ⏸ **登记不修（2026-10-12，块 5 收口）**；证据见 `docs/agent/evidence/c3-block5/b5h-bug2-bug3-cards.md` §七 补记 2。 |

## TD-44 块 5 **已落、明确不挂账**的五项（记账，防被当成漏做）

| 项 | 判据 |
|---|---|
| ① 购物车多型号已带 `AxisName` | 已落（块 5 内）。 |
| ② `goods_sku.SkuCode` 索引已加 | 已落 —— `idx_goods_sku_sku_code`（**普通索引，非 UNIQUE**，理由见 `db-schema.md §14.3`），VM 库 `information_schema.STATISTICS` 实测在册。 |
| ③ 二级折扣商品用券规则 | 已落。 |
| ④ 商家报名秒杀入口 | 已落（`/marketing/seckill/apply` 那条路）。 |
| ⑤ 国补 + 秒杀 + 普通商品三类标签 | 已落（`ProductCard.vue` 的类别标签行；⚠️ 普通商品走**隐形占位签** `visibility:hidden`，不是 `display:none` —— 那是「标签行等高」的做法，**不是**「普通商品不显标签」的疏漏；页面探针按 `.pcard-kind` **排除 `.ghost`** 读）。 |
| 状态 | ✅ **不挂账（2026-10-12，块 5 收口）** —— 本节只记「这五项别被当成待办」。 |

## TD-45 跨块挂账（块 6 / 7 / 8 + 四批补充功能 + 数据重填）

| 项 | 内容 |
|---|---|
| 块 6 | **评论 / 举报 / 敏感词** —— 计划已出（`template` 表已预留 `comment.reply` 的 `Type`，见 `db-schema.md §6.15`）。⚠️ `db-schema.md` 表清单里用户点到的 `comment` / `order_item` / `order_shipping_track` / `user.WarnCount` **前三个已在库**（`order_item` 可视化段 / `order_shipping_track` 块 4 补丁），**`comment` 与 `user.WarnCount` 未建**（块 6 未做）。 |
| 块 7 | **可观测 / 治理域**（最大的一块）—— **TD-38（秒杀真扣减）与 TD-36（SKU 扣减）都排在这里**。 |
| 块 8 | **部署**。 |
| 四批补充功能 | 未拆细，沿用既有登记。 |
| 数据重填 | 演示数据的一次重灌（口径见 `seed-demo.sql` 头注与 TD-37）。 |
| 状态 | ⏸ **登记（2026-10-12，块 5 收口）** —— 本节是**跨块**挂账的入口，细节留在各块开笔时的 pack 里。 |

## TD-46 问答超时 Job 在 a3 profile **默认态**下**不醒**（两层总闸叠加）—— 手验绿 ≠ 这条链路绿

| 项 | 内容 |
|---|---|
| 现状 | 「提问超 30 分钟未解决 ⇒ Job 报管理端」（`QuestionTimeoutJob`）在 **a3 profile 下永远不会醒**：① `@EnableScheduling` 挂在 `merchant/job/MerchantDowngradeSchedulingConfig.java:23` 那个**别人的**载体类上，被 `mall.downgrade.job.enabled=false` 关着 ⇒ **整台调度器不存在**，本仓所有 `@Scheduled` 一并失效；② 块 6 另加了一层自己的闸 `mall.comment.question.timeout.enabled=false`（同时写进 `application-a3.properties` 与 `src/test/resources/application-smoke.properties`）。两层是**独立的**，任一层开着都不会醒。 |
| 后果 | 块 6 手验若照 a3 **默认态**跑，**验不到**这条通知链路 —— 验收证据 `docs/agent/evidence/c3-block6/b6-c7-fake-data.txt` §4.9-甲 的 `EventType IN ('question.timeout','report.handled')` **0 行是预期值，不是失败**。要真验一次，必须**手动放开总闸**（`mall.downgrade.job.enabled=true` + `mall.comment.question.timeout.enabled=true`）再删掉已有的那一行通知（判据 `NOT EXISTS`）等下一轮。 |
| 处理时机 | 未排期。⚠️ 这条**不是块 6 引入的缺陷**（总闸是既有的），但块 6 是**第一个**踩到它的功能块。 |
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**。✅ 但**收口笔当场证过一次**：手工放开两道闸 + 删掉 `comment 110` 那行通知，`QuestionTimeoutJob` 于 12:46:40 自己发出新的一条（`_work/b6-close-handverify-backend.log:128`；落库 7 行见 `b6-c7-fake-data.txt` §4.9-乙）。⇒ 本条的**性质是「默认态验不到」，不是「链路坏了」**。同族提醒见 `progress.md §7` 的「a3 手验前必读」与 `application-a3.properties` 尾注释。 |

## TD-47 举报的三种 `TargetType` 里 `user` 没有处置落点

| 项 | 内容 |
|---|---|
| 现状 | `report.TargetType` 允许 `comment` / `goods` / `user` 三种，但管理端「举报中心」的处置动作只对**前两种**有意义：`/report/handle` 的「通过 ⇒ 删内容」对 `user` 无内容可删（用户不是内容）。且页面那枚「看该对象评论」的跳转**只有 `goods` 行跳得动** —— `report` 表没存「这条评论挂在谁身上」的冗余。 |
| 后果 | 举报一个用户 ⇒ 单子能建、能列、能标 `handled`，但「通过」到底做了什么**在界面上体现不出来**（实际只记了 `HandlerId` / `HandleNote`）。 |
| 处理时机 | 未排期（若要真处置用户，需定义「举报用户成立 ⇒ 做什么」—— 警告 / 封号 / 仅留痕）。 |
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**；验收证据 `b6-c7-fake-data.txt` §5 第 3 条。 |

## TD-48 `comment_image.Status='rejected'` 没有常驻样例（**故意不留种子**）

| 项 | 内容 |
|---|---|
| 现状 | 那条态的定义是「**已删仓库文件**」（DDL 列注释逐字）。⇒ 造一条指向不存在文件的种子行，页面上就是一个**破图框**不是「已驳回」态 ⇒ 块 6 的验收假数据**故意只留 `pending` + `approved` 两条**，驳回路径留给**手验现场点一次**。 |
| 后果 | 「已驳回」这一栏在**没有手验过的机器**上恒为空 ⇒ 换机器 / 重灌数据后，这条路径会**静默地不可见**（同 TD-43 那一族的形态：数据不足 ⇒ 不显，而不是报错）。 |
| 处理时机 | 若要常绿，需要在验收假数据里补一条「已驳回 + 图文件真的删掉」的样例（**改数据需用户点头**）。 |
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**；证据 `b6-c7-fake-data.txt` §5 第 2 条。<br>⚠️ **2026-10-13 整改补笔**：这条**在验收面上被顺带满足了** —— 审核记录那轮探针真驳回了一张图（`comment_image.Id=13`，`commentId=115`，回执 `fileDeleted:true`，盘上文件确实被删）⇒ **当前 VM 库里确实存在一条 `rejected` 样例**。但它是**验收产物、不是种子**（重灌库即消失）⇒ **本条的挂账性质不变**（`seed-demo.sql` 里仍然没有 `rejected`），只是「本机现在看得见」而已。 |

## TD-49 敏感词：加词**只扫一次**历史、删词**不回填** `***`

| 项 | 内容 |
|---|---|
| 现状 | ① `/sensitive/add` 加词时**当场扫一遍**历史评论并把命中处就地改写为 `***`（回体 `masked` = 真被改写的行数）；② `/sensitive/delete` 删词时**历史评论里的 `***` 不回填**（原文没有第二份副本）。 |
| 后果 | 误加一个词、又删掉 ⇒ 那些评论**永久带着 `***`**，且没有任何界面能看出「这里曾经被屏蔽过」。⇒ 加词的界面确认文案必须把「不可逆」写清（`SensitiveWordController#delete` 的 javadoc 已写明，前端也要写）。 |
| 处理时机 | 未排期。若要可逆，需要把原文另存一份（**新增列 / 新表 = 契约改动，需用户点头**）。 |
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**。 |

## TD-50 块 6 的验收假数据**不在 `seed-demo.sql` 里** ⇒ 重灌即丢

| 项 | 内容 |
|---|---|
| 现状 | 块 6 的 5 个必做假数据项（超时未解决问答 / 待审图片 / 待处理举报单 / 10 个敏感词 / 若干含匿名·图·回复·点赞的评论）由**一次性探针** `scripts/probes/ProbeB6c7Seed.java` 播种（幂等，哨兵 = goods 76 下 `AuthorId='3333'` 的 review），**没有进** `seed-demo.sql`。⚠️ 10 个敏感词是**例外**——它们随建表（Migration ⑮-8）落库，属结构的一部分。 |
| 后果 | 按 `seed-demo.sql` 重灌演示数据 / 换一台机器 ⇒ 除敏感词外的 4 项**全丢**，「举报中心 / 待审图片 / 问答管理」三个 tab 会回到**空态**（不是报错）。 |
| 处理时机 | 未排期（若要常绿，需把探针那几条 `INSERT` 收进 `seed-demo.sql` —— **改种子 = 数据变更，需用户点头**）。 |
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**；播种脚本 + 证据见 `scripts/probes/ProbeB6c7Seed.java` 与 `docs/agent/evidence/c3-block6/b6-c7-fake-data.txt`。 |

## TD-51 跨文件行号引用的**已知误指族**（块 6 收口实测 3 族，**登记不修**）

| 项 | 内容 |
|---|---|
| 现状 | 块 6 的全仓引用重指（`refs-scan` → 目标漂移 → 只应用 `MOVED-SAME`）实测出 **3 族「块 6 之前就对不上」的既有误指**：① `ProductPage.vue:354` × **17** 条（引用者描述的是「是否现在付款？」弹窗，而基线 `:354` 是分页按钮；`slot-registry.md` 自身把该族登记为「扫描器误报源」）；② `api-contract.md` §2 C.1 表的 `/order/orderAdd` → `ProductPage.vue:393` 与 `/message/send` → `ProductPage.vue:459`（**实测真调用点 = `:913` / `:1018`**）；③ 已退役的「累计评价」占位族（引用者**引述**那段占位注释，而占位本身已在块 6 退役）。 |
| 后果 | 这三族**不是块 6 造成的**，但会被下一笔的扫描器**反复报出来** ⇒ 每次收口都要重新判一次真伪。 |
| 处理时机 | 归「**文档漂移清理笔**」（正文坐标重填本来就是它的活）；块 6 已把实测真值写在 `api-contract.md` 附录 G 末条与 `slot-registry.md §二十三`，且在这两行**行内留了「不修」标记**（属重指脚本的 GUARD 触发词，防下一轮自动重指把它们又平移一次）。⚠️ 本族的**逐条清单** + 同族另两支（**族④** 同页另两行误指 / **族⑤** 本块顶偏但未重指）已另立 **TD-54**。 |
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**。⚠️ 与 TD-42（`refs-scan` 的兄弟 token 盲区）是同一族问题的两面。 |

## TD-52 块 6 **已落、明确不挂账**的九项（记账，防被当成漏做）

| 项 | 判据 |
|---|---|
| ① 六张新表 + `user.WarnCount` 已落 VM 库 | 已落 —— 全库 **36** 张（`information_schema` 实测），逐张命中见 `b6-c7-fake-data.txt` §4.10。 |
| ② 内容域 **13** 个端点全部落地 | 已落 —— `comment` 7 + `report` 3 + `sensitive` 3，逐条见 `api-contract.md` 附录 G。⚠️ **2026-10-13 整改补笔**：用户另批第 **14** 条 `POST /comment/adminList`（甲案，缺陷二）⇒ 内容域现为 **14** 条（`comment` **8**），契约要点见 **G.1.1**。 |
| ③ 敏感词预置 10 词 | 已落 —— Migration ⑮-8 随建表落库（**不是**探针插的），10 词逐条实读见 §4.7。 |
| ④ 评论图片「先审后发」+ 驳回**真删仓库文件** | 已落 —— `comment_image.Status` 三态；`pending` / `approved` 各有样例。⚠️ `rejected` 无常驻样例是**故意**的，见 TD-48。 |
| ⑤ 举报满 **3** 次警告自动封号 | 已落 —— 复用既有 `user.banned`（**不新建封号表**），`WarnCount` 只记计数。 |
| ⑥ 回复带 `@` **自动生成** `comment_mention` 行 + 发 `comment.mention` 通知 | **半落** —— ⚠️ **2026-10-13 就地更正**：本条原记「已落」，**验收结论作废**。`comment_mention` 那一半确已落（无前端输入框、后端按结构解析，样例见 §4.1 的 `Id=117` + §4.5，**未改**）；**通知那一半是假绿** —— `notification.ToId` 写的是**登录名**（契约要**业务号**）⇒ 前端按业务号查收件箱，**一条都读不到**，而当时的探针也拿登录名去拉 ⇒ 两边同错、断言全绿。**已按批文 ②③ 修**（发送方改写业务号；`comment_mention.MentionedId` 仍登录名；自 @ 判定**先判后换算**），重造场景 + 重贴证据见 `evidence/c3-block6/b6-commit3-mention.txt` §七/§八。根因与面上教训见 **TD-59**。 |
| ⑦ 匿名评论**强制 5 星** + 显示「首字*****」 | 已落 —— 样例 `Id=114`（`IsAnonymous=1`、`Rating=5`）。 |
| ⑧ 提问**不可自删** + 问答比例图（环图） | 已落 —— 块 6 整改 commit 3；`ModerationPage` 问答 tab 的解决率环图读 `stats` 的 `Solved` / `Unsolved`（只统计顶层提问）。 |
| ⑨ 管理端「消息与内容治理」页**六 tab 全可操作** | 已落 —— 评论管理 / 问答管理 / 待审图片 / 举报中心 / 敏感词库 / 消息管理（`views/admin/ModerationPage.vue`，`router/index.js` 的 `business/moderation` 由占位**同行替换**成真页）。 |
| 状态 | ✅ **不挂账（2026-10-13，块 6 收口）** —— 本节只记「这九项别被当成待办」。 |

## TD-53 跨块挂账（块 6 之后：块 7 / 块 8 + 四批补充功能 + 数据重填）

| 项 | 内容 |
|---|---|
| 块 6 | ✅ **已收口**（本节之前的 TD-46 – TD-52 是其挂账）。⚠️ 原 TD-45 里「`comment` 与 `user.WarnCount` 未建」的说法**在本笔之后失效** —— 两者块 6 均已落（`db-schema.md §15`）。 |
| 块 7 | ✅ **已收口（2026-10-14，commit 9）** —— 可观测域 4 项 + 库存治理 2 项全落（`progress.md §7-B7-收口`）；TD-36 / TD-38 两笔**已关闭 / 已兑现**（见上），本块新挂账见 **TD-60 – TD-67**。⚠️ 块 6 的 TD-47（举报 `user` 无处置落点）**未**排进块 7（它要的是产品裁决，不是工程排期），仍在册。 |
| 块 8 | **部署**（下一步）。 |
| 四批补充功能 | 未拆细，沿用既有登记。 |
| 数据重填 | 演示数据的一次重灌（口径见 `seed-demo.sql` 头注与 TD-37）。⚠️ 块 6 的验收假数据**不在**种子文件里（见 TD-50）⇒ 重灌前先把 `ProbeB6c7Seed.java` 跑一遍。⚠️ 块 7 的演示种子同理（**只落 4 个 `SEED-%` SKU**，见 **TD-65**）。 |
| 状态 | ⏸ **登记（2026-10-13，块 6 收口）**；**块 7 行已于 2026-10-14（commit 9）改「已收口」**。 |

## TD-54 引用重指**未应用族**逐条清单（块 6 收口实测；**登记不修**，与 TD-51 同族）

| 项 | 内容 |
|---|---|
| 现状 | 块 6 重指脚本（`_work/b6-repoint-apply.py`）**只应用 `MOVED-SAME`**，其余一律 SKIP 并记理由 ⇒ 收口复跑后仍有 **141 行**「引用者按 `f2b1535` 坐标写就、目标又在块 6 移位」的引用**未被重指**：**源侧 13 行 + 文档侧 128 行**。逐条台账 = `_work/b6-baseera-movedsame-by-reason.txt`（按理由分组）+ `_work/b6-refs-target-head3.tsv`（全量）＋ `_work/b6-repoint-log.tsv`（逐行 SKIP 理由）。 |
| 判据 | 「未应用」不是脚本失败：① 文档侧 47 行落在 `docs/agent/context-packs/`（25）・`docs/agent/evidence/`（21）・`docs/美化/`（1）—— **史料 pack / 证据档**，按护栏 `PREFIX` 一律不碰；② 文档侧 33 行命中 `GUARD`（自述「改前 / 旧引 / 不修 / 误报源」等叙事行）· 18 行命中 `SIBLING`（同行还有别的裸 `:NN`，只改主引会造半新半旧）· 2 行 `PINNED`（台账 B 档 / 「注释初版」自述）· 1 行 `RANGE-DIFF`（区间整段内容不一致）；③ **源侧 13 行**逐条见下（这 13 行才是「落在活代码注释里」的那批）。 |
| 源侧 13 行（逐条，含实测真值） | **族①（TD-51 已登记，共 6 行）** —— 引用者断言 `ProductPage.vue:354` 是「是否现在付款？」弹窗，而基线 `:354` 实为**评价分页按钮**：`order/OrderState.java:43` / `:116`、`order/service/OrderService.java:119` / `:379`、`OrderAddStateGuardSmokeTest.java:33` / `:120`。**族④（本笔补登记，2 行）** —— 同族不同行：`OrderService.java:95` 说「`ProductPage.vue:339-341` 是数量拼接」、`OrderService.java:339` 说「`:321-323` 是价格拼接」，而基线两处的实际内容是**旧评价区的星级分布条与评价卡标记**（真值：`:321-323 → :348-350`、`:339-341 → :412-414`）。**族⑤（本笔补登记，5 行）** —— **基线时本来是对的**，被块 6 的 `ProductPage.vue` 长大顶偏，且因 `SIBLING` / `GUARD` 未自动重指：`router/index.js:141`（`ProductPage.vue:361` / `:363` / `:388` / `:398` / `:400` → 实测 `:533` / `:535` / `:560` / `:570` / `:572`）、`AppShell.vue:227`（`:380` → `:552`）、`HomePage.vue:299`（`:426` → `:598`）、`CashierDialog.vue:103`（`:654` → `:849`）、`ShopHome.vue:281`（原 266 行；`:459` → `:631`；⚠️ `:459` 本身即 TD-51 族②的已知误指）。 |
| 后果 | 这 13 行**不影响运行时**（都是注释），但读者按号去找会落到邻行。**族⑤ 与族①④性质不同**：族①④是「块 6 之前就对不上」，族⑤是「本块顶偏、本可修而未修」—— 修族⑤要**整簇**改（`router/index.js:141` 一行含 5 个 token，且 `progress.md` §5-Y 生产者表 / `slot-registry.md` 对照表要同步），单点重指即造半新半旧，故与文档侧同批处置。 |
| 处理时机 | 与 TD-51 同归「**文档漂移清理笔**」。⚠️ 下一轮重指脚本仍会把这 141 行报出来 —— 判据是「引用者行是否仍持 `f2b1535` 坐标」（`token ∈ git show f2b1535:<引用者>`），**不是**重指脚本的 `shift()`。 |
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**。 |

## TD-55 带开关的探针：**位置参数不报错、静默跑全量**（块 6 收口实测，事故）

| 项 | 内容 |
|---|---|
| 现状 | `scripts/probes/probe-b6c7-handverify.py` 的段号开关是 `--stage <n>`（读法 `STAGE = sys.argv[sys.argv.index("--stage") + 1]`）。收口令冒烟试第 0 段时写成了位置参数 —— 位置参数被**静默忽略**，探针从第 1 段**整条跑完**，往库里写了一批新产物（另有副作用：把种子待审图翻态、把一次性作者的警告计数从 3 推到 6）。已用 `_work/b6-hv-revert-accidental.sh` 逐行回退，事故经过与判据记在 `b6-c7-handverify.txt` §6.7。 |
| 判据 | 「传错了」在运行时**没有任何信号**：进程正常、exit 0、日志照样一节一节打 —— 因为开关的缺省值是「全跑」。同类风险：`--exec` 这类「不传就只打印」的开关反过来是安全的（缺省=只读），缺省值取哪一侧决定了误传的代价。 |
| 后果 | 一次性探针误跑 = 库里多一组产物 + 证据档记的实测态被污染。「回退到哪一刻」还要靠「甲/乙两栏」「Id 有洞不是漏行」这类记账撑住 —— 否则整份实录作废。 |
| 处理时机 | 自查清单一条：**凡带开关的探针，冒烟只跑一小段时，走开关本身（`--stage 0`），不传位置参数**；更好的修法是让探针对**未知参数报错**（`argparse` 的默认行为），别把「不认识的东西」当没看见。沿用 `scripts/probes/` 现有探针时按前者办。 |
| 状态 | ⏸ **登记（2026-10-13，块 6 收口）**。 |

## TD-56 「一商品一用户一条评价」的唯一锁（⑯）落地后留下的三处边界

| 项 | 内容 |
|---|---|
| 现状 | 2026-10-13 批文三：`comment` 加 STORED 生成列 `ReviewLockKey` + `UNIQUE uk_comment_review_once`，Service 侧配**窄 catch**（只吞本锁的 `1062`，翻成人话；其它 SQL 异常仍上抛）。锁本身实测通过（`ProbeB6c7Lock` PASS 12 / FAIL 0，含并发档）。落地过程中留下三处**如实记下**的边界：① 迁移前清历史重复时**逻辑删了 `comment.Id=113`**（保留 `114`）；② `comment_mention.Id=12` 的 `CommentId` **指着 113**；③ 清理脚本 `_work/b6-c7-unique-lock-cleanup.sh` **刻意不进 `mall_management_sql.sql`**。 |
| 判据 | ① 113 行**仍在表里**（`Status='deleted'`），不是物理删 —— 页面上、列表里、统计里都不会出现它（`Status='normal'` 白名单）。② `comment_mention` 在 Java 侧**只写不读**（全仓无该表的 SELECT，只有 `insertMention`）⇒ 那条指向 113 的行**今天没有任何读取方**，也不构成悬挂（113 行本体还在）。③ 新库建表时 `comment` 为空 ⇒ 那条 `DELETE` 只对本机这个环境有意义，把它烤进共享重建脚本 = 给下一个人埋雷（`.sql:1671-1672` 逐字记了这条理由）。 |
| 后果 | ① 是**外观无差**的残留，但会让「113 去哪了」成为下一次读库时的疑问 ⇒ 必须在证据档里写明（`b6-c7-rework-accept.txt` §7 已写）。② 若**今后**有人给 `comment_mention` 加一个读取方（例如「被 @ 的人看自己的 @ 列表」），这条指向**已删评论**的行就会第一次浮现 —— 届时要么按 `Status` 过滤、要么加外键/清理。**这是本条的真正挂账点**。③ 换机器 / 重灌库不会重放这条清理 ⇒ 但也不会因此撞 1062（新库无历史重复）。 |
| 处理时机 | ② 与「`comment_mention` 将来加读取方」那笔同批处理（不是现在的待办）。①③ 不排期。 |
| 状态 | ⏸ **登记（2026-10-13，整改补笔）**。⚠️ 另有一条**措辞更正**要一并带走：此前曾写「113 无任何档/表引用」，**实测不成立**（`comment_mention.Id=12` 就是一处）—— 已就地更正。 |

## TD-57 审核记录的 `ActorId` 在 **a3（闸关 + 未登录）** 下为 `NULL` ⇒ 审计链缺「谁做的」这一半

| 项 | 内容 |
|---|---|
| 现状 | 2026-10-13 批文四：块 6 的 5 个治理动作复用 `admin_audit` 落记录（`comment.image.approve` / `comment.image.reject` / `sensitive_word.add` / `sensitive_word.delete` / `report.handle`）。实测落 5 行（`Id=231–235`），其中 **`231–234` 的 `ActorId` 是 `NULL`**，只有 `235`（先登录拿 `role=3` 会话再打）是 `adm1nC3r00t`。 |
| 判据 | `ActorId` 取的是**会话身份**（`AuthSession.identify`）**不是入参** —— 这正是批文要的语义。`a3` profile 的 `app.auth.enabled=false` 把闸关掉 + 未登录 ⇒ 识别不到人 ⇒ 记 `NULL`。**行仍然落**（`TargetId` 是业务键、非空，满足 `admin_audit.TargetId` 的 NOT NULL；`ActorId` 本就可空）。反证：`/report/handle` 在**完全没有会话**时直接拒「无法确定处理人身份」（`_work/_acc/r1_handle.json`）⇒ 说明「记不上是谁」的那几行并不是「没登录也放行」，而是闸关 profile 下**所有人都匿名可达**这一既定形态。 |
| 后果 | **a3 / smoke 这两档下，块 6 的审计记录「做了什么」完整、「谁做的」缺失**。默认 profile（闸开着）下不会出现这种行 —— 那里匿名会先 401，走不到挂钩点。⇒ 拿 a3 的账本去回答「谁删的词 / 谁驳的图」会**答不上来**，而这不是 bug、是 profile 形态。 |
| 处理时机 | 不排期（不修）。⚠️ **读账本的人要先看 profile**：`ActorId IS NULL` 的块 6 记录 = a3 下的探测 / 手验产物，不是「身份识别坏了」。 |
| 状态 | ⏸ **登记（2026-10-13，整改补笔）**；证据 `b6-c7-rework-accept.txt` §5（含逐行 `Action` / `TargetType` / `TargetId` / `ActorId` 与那 5 条 HTTP 回执）。 |

## TD-58 `L2HealthProbeConcurrencyTest.everyWindowHasExactlyOneProber` 是**计时敏感**用例 ⇒ 全量 `test` 会偶发红

| 项 | 内容 |
|---|---|
| 现状 | 2026-10-13 整改轮复跑全量 `./mvnw -B test`：**两次红在同一条用例**（14:48:21 与 15:05:26，`Tests run: 309 / Failures 1 / Errors 0 / Skipped 26`），第三次（**15:16:41，已移除临时工作树之后**）**绿**（`309 / 0 / 0` · `BUILD SUCCESS`）。失败的是「第 N 个窗口：20 并发应恰好 1 次探测」那条断言，`expected: 1 but was: 2`；两次报的窗口号**不同**（`round = 195` 与 `round = 120`）⇒ 随机、非确定性。 |
| 判据（**这一条是本 TD 的立足点**） | 用 **未改动的** `HEAD 392361e` 建临时工作树做对照：两棵树的 `L2HealthProbe.class` 与 `L2HealthProbeConcurrencyTest.class` **md5 逐字节相同**（`82e7cdc6ad6b041a41f80f26dd2b14f5` / `4bea868cce97d207c4d21bd9d0b96175`）⇒ **执行的是同一份字节码**，差异**不可能**来自本轮源码改动（本轮对 `Mall-management-server/src/main` 的改动只有 `AuditActions.java` 加 5 个常量 + `WebAuthConfig.java` 改注释，两者都不在这条用例的类路径上 —— 该用例是纯 JUnit：mock 掉 `RedisConnectionFactory`、`SimpleMeterRegistry`，**不起 Spring 容器**）。 |
| 分布（本机 2026-10-13，单用例 `surefire:test`） | 工作区 **19 次 → 红 3 / 绿 16**；基线 `HEAD 392361e` **19 次 → 红 0 / 绿 19**（含 6 轮**交错**对照：基线 0 红 / 工作区 1 红）。⚠️ 按 Fisher 精确检验，`0/19 vs 3/19` 的 p ≈ 0.23 ⇒ **统计上分不开**；但**字节码相同**这一条已经把「因果」排除了。另补：给基线加 **16 路人为 CPU 负载**跑 3 次 → **0 红** ⇒ 「笼统的机器忙」不足以复现它。 |
| 机理 | 用例自带 20ms 冷却窗口，每轮 `sleep(cooldown + 3ms)` —— **余量只有 3ms**；用例自己的 javadoc 写着「实测跨度 max 0.62ms（2026-09-29，本机 200 轮）⇒ 取 20ms，满足『跨度 × 3 < 窗口』」，并自陈「这类 flaky 比慢几秒贵得多」。⇒ 一旦某轮的 20 线程突发 + GC/JIT 抖动把墙钟跨度顶过剩余的 ~17ms，窗口在断言中途到期，计数就是 2。 |
| 处理时机 | **不排期（本笔不改用例）** —— 改它属于「降级测试的判据」（放宽断言 / 加大窗口都等于把「CAS 是否原子」这条不变量验松），不在本轮批文范围内，需用户单独裁。 |
| 状态 | ⏸ **登记（2026-10-13，整改补笔；当日用户裁决四：接受处置，本轮不改）**。⚠️ **用户手验时若命中这条红 —— 重跑即可，它不是块 6 的缺陷**（两棵树字节码逐字节相同这条判据已排除因果；见上）。⚠️ **别为了消红放宽那条断言**（放宽 / 加大窗口 = 把「CAS 是否原子」这条不变量验松）—— 用户裁决四明确否掉。⚠️ **读的人须知**：跑全量看到这**一条**红，**先按计时抖动处理，别当成回归** —— 复核办法就是上面那条「两棵树 `L2HealthProbe*.class` md5 是否相同」。证据原始输出见 `b6-c7-rework-accept.txt` §8.4。 |

## TD-59 `probe-b6c3-mention.py` 的收件箱口径**与被测缺陷共用同一错误前提** ⇒ 校验器假绿（「校验器报 OK ≠ 没问题」实例）

| 项 | 内容 |
|---|---|
| 现状 | 块 6 的 5 个 `EventType`（`comment.mention` / `comment.reply` / `comment.on.post` / `comment.accepted` / `report.handled`）把**登录名**写进 `notification.ToId`，而 `ToId` 的既定口径是**业务号**（`user.customerid` / `user.merchantid`；见 `db-schema.md` 的 `ToId` 行），前端 `/notification/list` 也按业务号查 ⇒ 这 5 类通知**线上一行都投不进收件箱**。而 `probe-b6c3-mention.py` 的 A1–B2 五条断言，当时同样是**拿登录名当 `ToId`** 去拉收件箱的 ⇒ **发送方与读取方错在同一个号域，断言全绿**。该探针是 commit 3〈@ 自动生成〉的**唯一**验收依据（`b6-c3-mention-probe.txt`、`b6-commit3-mention.txt` §一）。 |
| 判据 | 校验器只问了「`ToId` 是不是**我期望的那个字符串**」，**没问「`ToId` 该属哪个号域」** —— 而那个期望值本身就来自被测方。⇒ 错误前提一旦进了校验器，缺口就以「OK」而**不是「没查」**的形态报出来。**修好发号后，探针必须带一条反向控制**：旧口径（拿登录名当 `ToId`）**恒空**才算真绿 —— 否则连「发号退回登录名」这种回退也照不出来，闸会再次假绿。 |
| 后果 | ① `docs/agent/evidence/c3-block6/b6-c3-mention-probe.txt` 与 `b6-commit3-mention.txt` 里「@ 已落」的验收结论**全部作废**（2026-10-13 ②③ 修复后已重造场景 + 重贴证据，见该文件文末「更正」段）。② 面上教训：**凡「读取端与被测端共用同一个常量 / 口径表」的探针，绿不证明对** —— 评审时要先问「校验器的期望值是从哪来的」。 |
| 处理 | ✅ **已修**（2026-10-13，②③ 补笔）。新增 `BIZ = {登录名: (业务号, 角色)}` 表，**只用于读收件箱**；`inbox()` / `mention_hits()` 收登录名、内部换算成业务号再拉；新增 `stale_domain_hits()` + 段 **N**「反向控制 · 旧口径必须恒空」。⚠️ **`BIZ` 只用于读** —— 写入侧 `insertMention` 仍写登录名（`comment_mention.MentionedId` 保持登录名域，那是**另一张表另一列**的事）。 |
| 同文件第二个缺陷（D1） | `save_ids()` 原本**整份覆盖** `_work/b6c3-probe-ids.json`：重跑时撞 `MSG_DUPLICATE_REVIEW` 抛 `SystemExit`，而 `remember()` 已把**本轮** id 写进文件、把**上一轮**的六个 key（含 B 组两条）**顶掉** ⇒ `--clean` 生成的 `DELETE` 够不着那些行 ⇒ 又成孤儿。**已修**：`save_ids()` 改为维护只增不减的 `_history`，`cleanup_sql()` 并上历轮全部 id（同族孤儿 217–222 即由此产生，已按 `_work/_b6-probe-leftover-snapshot.txt` 清掉并复核归零）。 |
| 状态 | ✅ **探针已修 + 已重跑绿**（`EXIT=0`，证据 `docs/agent/evidence/c3-block6/b6-commit3-mention.txt` 文末「更正」段）；⏸ **登记（2026-10-13，②③ 补笔）** —— 面上判据（「校验器期望值的来源要独立于被测方」）留给后续同类探针评审。 |

## TD-60 块 7 **已关闭 / 已兑现 / 明确不挂账**（记账，防被当成漏做）

| 项 | 内容 |
|---|---|
| TD-36 | ✅ **已关闭** —— `goods_sku.Stock` 只存不扣 ⇒ 块 7 commit 1（`b1477cc`）CAS 扣减 + 下单即扣 + 超时释放落地。 |
| TD-38 | ✅ **已兑现** —— 秒杀不真扣减 ⇒ 块 7 commit 2c+2d（`96232eb`）接线 + 超时释放条件化。 |
| TD-34（超时取消边界） | 块 7 补笔 ②（`b201437`）：超时取消 job **跳过演练带标记的行**（`Source='attack'`），把「演练污染」与「真实业务」在自动 job 里分开。⚠️ 这是**边界加了一条**，不是全消 —— TD-34 其余边界仍在册。 |
| 循环依赖（`ObserveMetricsConfig`） | ✅ **已修** —— 块 7 commit 5b（`5b971f1`）：`MeterFilter` 改 `static`，一个词断环。⚠️ **本仓曾因它整台起不来**，临时起法 `--spring.main.allow-circular-references=true` 仍可作为应急（`application-dev.properties` 未内置它）。 |
| MQ 缺席的语义 | ⚠️ **明确不挂账**：RabbitMQ 不在线时，**上屏（SSE）仍通**、事件**不落台账** —— 这是 commit 8c 刻意设计（`ObservedEventConsumer` 连不上就 5 分钟一条 `[EVENT]` WARN，阈值 `mall.observe.mq.warn-interval-ms=300000`），**不是缺陷**。 |
| 中文标签层兜底 | ⚠️ **明确不挂账**：`src/config/labels/*.js` 里**没有**的 code ⇒ **原样显示原码**（不做 `??` 到空串、不抛错）。`<option>` 的 `:value` **仍是 code**（不改后端契约）。 |
| P99 剔除 SSE | ✅ **已修** —— commit 8b2（`136b27c`）：长连接不进 P99 直方图 + 无样本**留空**（不是 `0`）。 |
| 延迟口径剔除演练启动端点 | ✅ **已修** —— commit 10c-2（`21bae15`）：`/admin/observe/attack/run` 是**同步**请求（挂满整场演练），实测把 `MAX=136417ms` / `p99Ms=120259ms` 写进延迟指标 ⇒ `MetricsSnapshotService.isLatencyTimer` 加 `DRILL_RUN_URI`（判**全等**，非 `/attack/*` 前缀 —— `/stop` 是毫秒级真请求，照算）。⚠️ 这两条排除是**手工白名单**，其脆弱性单列为 **TD-70**。 |
| SSE 异步超时（流被腰斩） | ✅ **已修** —— commit 10b（`1b5afcb`）：Tomcat 异步默认 30s 会在流中途抛 `AsyncRequestTimeoutException` 把 SSE 掐断 ⇒ `application.properties:203-228` 追加 `spring.mvc.async.request-timeout=-1`（该层无超时，优于「给个大数」）。证据链（`spring-boot-autoconfigure` 元数据 + `spring-webmvc` 字节码）与另外三条终止路径（`max-ticks>0` / 客户端断开 / 服务端关停）写在该段注释里；代价：客户端**从不读**就一直占着连接。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口 commit 9）**；最后两行补于 **2026-10-15（commit 10 系列）**。 |

## TD-61 事件台账是「**观看时的过程记录**」，不是全量审计流水

| 项 | 内容 |
|---|---|
| 现状 | `observed_event` 的落库由 `EventBroadcaster.poll()` 驱动，而它的**唯一调用点**是 `EventStreamService:96`（**events 流**；⚠️ **2026-10-15 块 7 commit 10b 重指**：**原 81 行** → **96 行**，且此后落在**每连接一份**帧源里（丙）⇒ 每秒调用次数 = 打开页面数）—— 即由 **SSE 流线程每秒一拍**推进。⇒ **没人打开实时日志页（`/admin/observe/logs`）时，合并环不动、事件不落库。** |
| 判据（本笔实测） | 同一批次演练两轮对照：**[A] 不开流**跑到结束 ⇒ 当场查台账 **0 行**；随后**开流**同一批次 ⇒ **2140 行**落库、且 `OccurredAt` 时间戳**仍落在演练当时**（`23:06:51.719–23:06:59.530`）。⇒ **落库滞后 ≠ 透传丢了**，两者要分开判。证据 `docs/agent/evidence/c3-block7/b7c9-e2e-batch.txt` §A/§B。 |
| 后果 | ① 拿台账回答「某段时间系统发生了什么」会**系统性缺行**（缺的正是没人看的那段）；② 但**演练期间打开页面**就能拿到当批完整记录（§B：3697 行 / 1 批次 / 1194 trace / 5 类）。**这不是缺陷**，是「观测数据按需采集」的形态。 |
| 处理时机 | **不修**。⚠️ **读台账的人须知**：`observed_event` 的行数**取决于有没有人在看**，不能当「系统一共发生了 N 件事」的证据。若将来要全量审计流水，得把 `poll()` 从 SSE 流线程解耦到独立调度（届时另开一笔）。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口）**；同一句已写入 `api-contract.md` 附录 **H.7**。 |

## TD-62 台账保留期 Job 是 `@Scheduled` **第 7 处**，而调度器**总闸仍挂在别人的类上**（a3 下不醒）

| 项 | 内容 |
|---|---|
| 现状 | 块 7 commit 8b 新增 `ObservedEventCleanupJob`（`@Scheduled`），把本仓 `@Scheduled` 实例从 **6** 推到 **7**。⚠️ 本仓**没有**自己的 `@EnableScheduling` 载体 —— `@EnableScheduling` 挂在 `MerchantDowngradeSchedulingConfig` 上，而那个类被 `mall.downgrade.job.enabled` 关着 ⇒ **`a3` 下整台调度器不存在**，**所有** `@Scheduled`（含这第 7 处）**都不醒**。 |
| 判据 | 与 **TD-46**（问答超时 Job 不醒）**同一条根因**，本处是「同族第七处」。本笔另把 `ObservedEventCleanupJob` 的**自己那层开关**在 **dev = `true` / a3 = `false` / smoke = `false`** 三处登记齐 —— 即便总闸放开，a3 下它仍关。 |
| 后果 | 在 `a3`（CI 门禁 + 日常手验常用档）下，**保留期清理一行都不会删** ⇒ 想验「3 天 / 30 天两档保留期」必须**手动放开总闸**（见 `progress.md` 自查清单「调度器总闸」条）。 |
| 处理时机 | **登记不修**（与 TD-46 同批）。⚠️ **本笔不接写入**（写入方是 8c 的 MQ consumer）⇒ 表空、每轮删 **0 行**，属**基础设施提前就位**。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口）**。 |

## TD-63 块 7 三处观测面在 **a3 / dev（闸关）** 下「**身份列**」为空 —— 同 TD-57 形态

| 项 | 内容 |
|---|---|
| 现状 | `a3` 与 `dev` 两档的 `app.auth.enabled` **都是 `false`**（只有默认 profile 是 `true`，见 TD-35 边界 ①）⇒ 未登录也放行、识别不到人。块 7 新增的 `observed_event.Identity` / `admin_audit.ActorId` / `drill_record.OperatorId` 在闸关档下**取不到值**。 |
| 判据 | 取的是**会话身份**（不是入参）；行**仍然落**（身份列可空，业务键非空）。与 TD-57 完全同形：**「做了什么」完整、「谁做的」缺失**。 |
| 后果 | ⚠️ **默认 profile（闸开）下不会出现这种行** —— 匿名会先被 401 挡在挂钩点之前。⇒ 拿 a3 的账本回答「谁删的词 / 谁开的演练」会**答不上来**，这是 profile 形态、**不是 bug**。**手验绿 ≠ 默认 profile 绿**（这条告诫在块 7 依然成立）。 |
| 处理时机 | **不修**。读账本前先看 profile。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口）**。 |

## TD-64 块 7 新增的**破坏性 / 长连**端点**没有角色闸**（与 TD-18 同源）

| 项 | 内容 |
|---|---|
| 现状 | ① 清场端点 `POST /admin/observe/attack/cleanup` 是**本仓第一条真 `DELETE`**（不可逆、无中间态）；② 三条 SSE（events / orders / metrics）是**长连接**。**两批都没有角色闸**。 |
| 判据 | 与 **TD-18**（管理员铸票路径缺失）**同源**：本仓的管理端鉴权目前靠**路径前缀 + 前端路由**，后端不做细粒度角色校验；`a3` / `dev` 档连 `app.auth.enabled` 都关着，更无从谈角色。 |
| 后果 | ② 只是「谁都能挂着看」；① 是**真能删数据** —— 虽只删 `Source='attack'` 或 `SIM-%` 的行（**`drill_record` 两种谓词都排除**），但仍是不设防的破坏面。 |
| 处理时机 | 与 **TD-18** 同批（「防护加固」D 段）。⚠️ **在那之前，手验清场按钮时务必对准 dev + VM 库**，别在别的库上点。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口）**。 |

## TD-65 块 7 的演示种子与批文的**四条前提更正**

| 项 | 内容 |
|---|---|
| ① `BaselineStock` **不能预置** | 批文写「若干 SKU 带基线库存」。实测：`BaselineStock` **不是种子**，是**人工四步**的载体（演示前 `UPDATE … SET BaselineStock = Stock` → 演示后 `SET Stock = BaselineStock` → 收尾 `SET BaselineStock = NULL`）；库里 `NULL` = **未处于演示中**。⇒ 种子**刻意不预置**它。 |
| ② `goods_sku` **没有 `Source` 列** | 批文写「演示数据带 `Source='seed'`」。实测：`Source` 只加在**六张内容/治理表**（⑰-4..⑰-9）与 11 张存量表 —— **`goods_sku` 不在其中**（⑰-2 只给了它 `BaselineStock`）。⇒ 那批 SKU 的「这是演示数据」标记改用**码前缀 `SEED-%`**（实测落 `SEED-GD-101-9552` 等 4 行，`Id 21–24`）。 |
| ③ 敏感词**不重复预置** | 库里已有 **11** 条（`MaxLen=4`），全 `Source=''`。⇒ **不新增、也不改写它们的 `Source`**（改写存量 = 篡改既有数据）。 |
| ④ 「若干用户有 login + user 行」**已满足** | 实测 `custWithLogin = 33` ⇒ 无需预置。 |
| 证据 | `docs/agent/evidence/c3-block7/b7c9-seed-sku.txt`（落库 4/4 幂等 + 读回 `Id 21–24` / `Stock 240/180/120/60` / `BaselineStock=null` + `pickSku` 主靶 = `SEED-GD-101-9552` 240）。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口）**；四条更正的完整表述同见 `slot-registry.md` 24-10。 |

## TD-66 `AbstractSmokeTest.java` 本笔 **+25 行** ⇒ 指向它的行号漂了（**TD-51 族新成员**）

| 项 | 内容 |
|---|---|
| 现状 | 块 7 commit 8b 对该文件 **+25 行**（import 块 **+2** / `@Import` 尾部登记注释 **+23**）⇒ 指向它的外部 `文件:行号` 引用集体下移。commit 9 实测：`progress.md:411` 引的 `AbstractSmokeTest.java:45`，**现实为 `:47`**。 |
| 判据 | 与 **TD-51** 同族（**登记不修**）。⚠️ `context-packs/` 里那 4 处（`:137` / `:137-146` / `:156` / `:163-206`）**在块 2–6 期间就已被 `@Import` 扩容甩到 `:376` 起**，属**既有误指族**，**不是本笔造成的**。 |
| 处理时机 | 与 TD-51 / TD-54 同归「**文档漂移清理笔**」。 |
| 状态 | ⏸ **登记不修（2026-10-14，块 7 收口）**。 |

## TD-67 `drill_record` 里留着**收口验证行**，而清场按钮**刻意不动它**

| 项 | 内容 |
|---|---|
| 现状 | 块 7 收口的跨线程透传端到端验证在本库留了 **2 行**演练记录：`Name = b7c9-e2e`（Id=4）与 `b7c9-e2e2`（Id=5）。清场端点 `POST /admin/observe/attack/cleanup` 的两种谓词（`OrderId LIKE 'SIM-%'` **OR** `Source='attack'`）**都把 `drill_record` 排除**（它是「演练这件事本身」的记录，语义与「演练污染出来的数据」相反）。 |
| 判据 | ⇒ **点了清场，这 2 行仍在**。**这是设计，不是没清干净**（手验清单 §H-2 已写明）。若要真清，只能手工 `DELETE FROM drill_record WHERE Name LIKE 'b7c9-e2e%'`。 |
| 后果 | 演示时若从「空记录」起讲，需先接受「已有几条历史演练」这一现状；反之这正是**本次验证确实跑过**的证据。 |
| 处理时机 | **不修**（保留为证据）。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口）**。 |

## TD-68 commit 9 **报备的批文前提不符**（三条，逐条如实记）

| 项 | 内容 |
|---|---|
| ① 9.3 的前提 | 批文说「照 `AccountsPage` 的二次确认风格」——实测 **`AccountsPage` 没有二次确认**（`grep` 0 命中）⇒ 本笔照的是 **`OrderPage.vue:636` 起的 `armed` 流程** + `AccountsPage` 的**配色**。⚠️ 另：本仓 `eslintConfig.env` **没有 `browser`** ⇒ `window.confirm` / `alert` 会踩 `no-undef`，原生弹窗在自动化探针里**也点不到**。 |
| ② 9.5 的前提 | 见 **TD-65** 四条（`BaselineStock` 不预置 / `goods_sku` 无 `Source` / 敏感词已存在 / 用户已满足）。 |
| ③ 批文点名的行号 | 「`db-migration.md:253` 的『⏳ 待落库』改『已落』」——该处**在 commit 8b 就已改口**；本笔真正漏的是**登记表 `#39` 那一行**（表格与注意段不同步）。⇒ **照批文找行号会漏掉一处**：给的是注意段，漏的在表格里。已就地改正并新增 `#40`。 |
| 状态 | ⏸ **登记（2026-10-14，块 7 收口）**；同见 `slot-registry.md` 24-10。 |

## TD-69 按龄保留期 Job 把**演练副产物**归进「日常」档 ⇒ 多留 30 天

| 项 | 内容 |
|---|---|
| 现状 | `observed_event` 里有一类行：**由演练引起的、却没有 `BatchId`** —— 它们由「非请求上下文」的线程写出（库存扣减 / 审计），拿不到当轮的批次号。对备份 152184 行实测：**1742 条**（种子 SKU 库存事件 1740 —— `ObjectType='sku'` 且 `ObjectId` 全是 `SEED-%`；清场动作自己的审计行 2 —— `ObjectType='attack'`）。 |
| 判据 | 按龄 Job `pruneObservedEvents(attackRetentionDays=3, normalRetentionDays=30)` 的**唯一分档依据是 `BatchId`**：`IS NOT NULL` → 3 天，`IS NULL` → 30 天。⇒ 上面这 1742 条落进 **30 天**档，而它们其实是**演练产物**，本该 3 天。 |
| 后果 | 若某次演练跑完**不点清场按钮**，其副产物要在库里待满 30 天（而同轮带 `BatchId` 的台账 3 天就没了）⇒ 页面「实时日志」上会长期挂着**上一轮**的库存事件。⚠️ 当前实际影响为 **0**：a3 / dev 下调度器总闸关着（见 **TD-62**），这个 Job 根本不醒。 |
| 为什么不改 | 用户 **2026-10-15 裁决：不同步**。两个东西目的不同 —— 清场是「**现在**回到干净态」（资源域判据：批次号 / 种子 SKU 前缀 / 攻击域对象），保留期是「**到点清一切**」（时间域判据）。把清场的三条塞进按龄 Job 会让两边都说不清「这个数为什么是它」。 |
| 处理时机 | 将来若真要分档，前提是「保留期能**认出**演练副产物」—— 今天只有两条资源域线索（`ObjectId LIKE 'SEED-%'`、`ObjectType='attack'`），且它们是**清场谓词**、不是时间判据；直接搬进 Job 等于把两套语义混一起。**先有「这一行属不属于某次演练」的可靠标记，再谈分档**。 |
| 状态 | ⏸ **登记（2026-10-15，块 7 · commit 10c-2）**；清场侧的扩谓词见 commit `ef178da`（`ObservedEventCleanupDao` 第 ③ 段）。 |

## TD-70 延迟口径的「排除名单」是**手工白名单** —— 每多一条「耗时 ≠ 延迟」的端点就要手动加一条判据

| 项 | 内容 |
|---|---|
| 现状 | `MetricsSnapshotService.isLatencyTimer(Timer)` 把「不计入延迟」的端点**逐条硬编码**：`SSE_URI_SUFFIX`（`*​/stream` 后缀，块 8b2）与 `DRILL_RUN_URI`（`/admin/observe/attack/run` 全等，块 10c-2）。除此之外一切 `http.server.requests` 计时器都进 p95/p99/mean/max。 |
| 判据 | 一个端点**是不是**该算延迟，取决于「它的耗时是否代表『服务器处理一次请求要多久』」。长连接（SSE）与同步长任务（`/run`）都是「请求挂着的时长」，不是延迟 —— 但这两类**在 Micrometer 眼里与普通请求长得一模一样**（同一个 `http.server.requests` 计时器，只差一个 `uri` tag）。⇒ 只能靠**人工认出**再补一条判据。 |
| 后果 | 漏一条就误红一次观测页，而且**看着像真慢**：已经漏过两次 —— ① SSE 长连接（块 8b2 修）；② 演练启动端点（块 10c-2 修，实测 p99 被抬到 120259ms）。第三次若是别的新端点（如将来加一个「导出全量报表」的同步端点），症状**完全相同**：某个分位凭空多两位、按下按钮就点亮红。 |
| 为什么不改 | 「自动认出」需要一个「哪些端点算长任务」的**声明**——今天这个声明就是那两个常量本身。做成算法（按时长阈值？按路径前缀？）都会引入新的误判：阈值会随负载浮动、前缀会误伤 `/attack/stop`。⇒ 当前选择是**显式白名单 + 每条写理由 + 用例钉住**（`MetricsSnapshotServiceTest` 的两个突变对照），代价如实登记在此。 |
| 处理时机 | 若将来这类端点**成规模**（例如统一给长任务加一个 `@LongRunning` 注解、由反射生成名单），再谈自动化；在那之前，**新增同步长任务端点时，记得回来看这个白名单**。 |
| 状态 | ⏸ **登记（2026-10-15，块 7 · commit 10c-2）**。 |

## TD-71 启动脚本 / 部署文档**没有固定 `-Xms/-Xmx`** ⇒ 4 核 6G 机器上 JVM 按物理内存 1/4 估堆，会和 MySQL 抢内存

| 项 | 内容 |
|---|---|
| 现状 | 本仓的启动方式（`_work/*-restart.sh`、文档里的 `mvnw spring-boot:run`）**不带任何堆参数**。JVM 缺省 `MaxHeapSize` = 物理内存的 1/4（容器里是容器内存的 1/4）。答辩/部署机的目标是 **4 核 6G**（与 MySQL 同机）。 |
| 判据 | 6G × 1/4 = **约 1.5G** 的默认上限；而目标场景里这台机器**同时**跑 MySQL + 后端 + 前端 dev server + 攻击引擎（32 派发线程）。堆上限跟着**物理内存**走（不是跟着「这台机器还要跑别的」走）⇒ 机器越大反而吃得越多，与「和 MySQL 共处」的约束**方向相反**。 |
| 后果 | 内存紧张时 JVM 与 MySQL 互相挤压：MySQL 可能 OOM 被 kill，或 JVM 被 cgroup OOM-killer 挑中。⚠️ **本机（16G）当前无此症状** —— 16G × 1/4 = 4G，够用；这是**目标部署机**的隐患，不是现在这台的表现。 |
| 为什么不改 | 属「**部署 / 本地化**」阶段，**不在块 7 的边界里**（块 7 的边界是「不改部署配置」）。本条只**登记隐患**，不动任何脚本。 |
| 处理时机 | 本地化时给启动脚本 / 部署文档写明 `-Xms` / `-Xmx`（建议 4 核 6G → `-Xmx1g`），并与 HikariCP 连接池上限、攻击引擎并发一起对齐（三者都吃同一块内存）。 |
| 状态 | ⏸ **登记（2026-10-15，用户口述隐患）** —— 用户明确「这件不在 10d 范围内」。 |

## TD-72 回放的**空批次面**：事件行按 3 天保留期清，而记录行**不** ⇒ 旧批次的〔回放〕必然返回空表

| 项 | 内容 |
|---|---|
| 现状 | `POST /admin/observe/replay` 只从 `observed_event` 读该批次的行（`ObservedEventDao:232` 那条 `WHERE BatchId = ?`（⚠️ **2026-10-17 块 7 · 阶段 3a.3 第二步重指**：**原 158 行**；本笔在类注释、`COLUMNS` 与 Insert 侧增改，`selectByBatch` 随之**下移 74 行**。⚠️ 裸形引用、扫描器收不到 ⇒ **靠人工重指**））。而**两条寿命不一样长**：事件行受按龄保留期管（攻击批次行 `mall.observe.cleanup.attack-retention-days`，缺省 **3** 天，`ObservedEventCleanupJob`），**演练记录行 `drill_record` 不受这条管**（保留期 job 只碰 `observed_event`，见 `api-contract.md#api-h-7`；清场按钮也刻意不动它，见 **TD-67**）。 |
| 判据 | 演练记录列表是**长期**的（`/records` 分页倒序，没有任何按龄删除），而回放的数据源**只有 3 天**。⇒「记录看得见」与「回放放得出」是**两个不同的窗口**。 |
| 后果 | 超过 3 天的演练：记录列表里那行**照旧显示、〔回放〕钮照旧可点**，点下去得到 `list=[] total=0` 的 **200**（契约口径：空批次是正常答案，见 `api-contract.md#api-h-8`）—— 页面于是显示「这个批次没有事件」。用户看到的是**「回放坏了」**，不是「数据过期了」。⚠️ dev / a3 上不会撞见（`mall.observe.cleanup.job.enabled` 关着 + `@EnableScheduling` 载体类另受总闸管 ⇒ job 不醒），**只在调度器真跑起来的部署里**才会出现。 |
| 为什么不改 | 空批次走 200 是**特意**的（「从没有过」与「被清掉了」在**后端**是同一件事，不该编造区别）；而前端今天不做「记录行在、事件行没了 ⇒ 只能是过期」这个推断。改成 404 会把「批次号敲错」也变成一个错误页 —— 那更差。 |
| 处理时机 | 本地化 / 收口时二选一：(甲) 前端在「记录行存在但回放空」时补一句「该批次的事件已过保留期（缺省 3 天）」——**零后端改动，推荐**；(乙) 让 `drill_record` 也受同一条保留期管 —— ⚠️ **与 TD-67 的裁决直接冲突**（那边刚裁「清场与保留期两套语义不要混」），走乙等于把 TD-67 推翻。 |
| 状态 | ⏸ **登记（2026-10-15，块 7 收尾 commit 1）**。 |

## TD-73 「模拟日常」的假数据**只在两处留痕** ⇒ 页面分不出演示态；且它与 **D 段防护接闸**直接冲突

| 项 | 内容 |
|---|---|
| 现状 | 「模拟日常」（块 7 收尾 ②）的假数据**完全在前端造**（`api/demo-stream.js`），既**不打后端、也不落库**；三页各自把数据源从 SSE 切到 `openDemoStream`。 |
| 判据 | 演示态**只**在两处可以看出来：① 日志页顶那枚 MQ 点显示「**未连接**」（演示帧里 `mq=false`，是照实说的 —— 演示事件确实不落库）；② 指标页健康度自报里有一句「**模拟日常（假数据，不入库）**」（`demo-stream.js:644`）。除此之外，页面的长相**与真流量完全一样**（KPI、曲线、事件行、五态计数都照常满屏）。 |
| 后果 | ① **取证风险**：截图 / 录屏里「有数」不等于「后端真有这些数」，判断依据只有那两处字符串 —— 手验清单与证据整理时必须**主动**带上它们。② **D 段联动风险（更要紧）**：演示帧里的 `protections` 是**前端假造的 5 条**（`demo-stream.js:646` 的 `PROTECTIONS.map(...)`），而 D 段要把这 5 个开关**接上真闸**。接完之后，**演示模式下这些读数不会随开关变化** —— 它根本不知道后端开关状态。⇒ D 段 commit 3 的 ⑥「开防护 ⇒ 恢复 / 关防护 ⇒ 崩」这条对照，**在演示模式下走不出来**，只能在**正常模式**下接后端真跑。 |
| 为什么不改 | 演示模式的设计目标就是「不依赖后端」（起一次演示不需要 MQ、不需要库、不需要有人先跑一遍演练）—— 让它去读后端开关状态，等于把这个目标拆掉一半。 |
| 处理时机 | D 段 commit 3 落地 ⑥ 攻防联动演示时，把「对照必须在正常模式下做」写进手验清单（**commit 4 ③** 那份）；若届时仍希望演示模式能演「开关的差别」，那要单独裁一次（例如让 `openDemoStream` 启动时读一次后端开关 —— 但那就不再是纯前端了）。 |
| 状态 | ⏸ **登记（2026-10-15，块 7 收尾 commit 1）**；与 **D 段 context-pack** `docs/agent/context-packs/c3-block-D-hardening.md` 的「前提更正」第 1 条是同一个事实的两面。 |

## TD-74 重试（resilience4j `Retry`）**本段刻意不落码** —— 触发条件是「先有死锁实测」，不是「防护清单里有这一项」

| 项 | 内容 |
|---|---|
| 现状 | D 段防护清单的第 4 项是**重试**。本笔**一行重试代码都没有**：`ProtectionGate.KEYS` 里那个 `retry` 位只做「如实回答自己没生效」（`WIRED_KEYS` 不含它，`ProtectionGate.wired("retry") == false`），面板上标灰 + tooltip（含本条编号）。⚠️ 口径：本段是 **3 键接闸 + 1 键登记 TD + 1 键记录位**，**不是**「4 键已接闸」。 |
| 判据 | 重试**不是**一种「加上去就更稳」的东西：它把「一次失败」放大成 N 次下游调用。加在**死锁**上才是收益（MySQL 死锁是**瞬时、可重放**的，退避后重试大概率成功）；加在**超时 / 连接池耗尽 / 慢查询**上只会把已经过载的下游**压得更死**，而且它恰好会**喂坏**同一段刚落地的熔断窗口（重试进来的失败记录把错误率推过 50% ⇒ 熔断打开 ⇒ 一整套防护自己把自己关掉）。⇒ 落点必须先由**实测**决定：**哪几条 SQL 真的会死锁、且能复现**。本段没有这个实测，所以不落码。 |
| 触发条件（落地的**前置**，不是时间点） | ① 有一份**可复现**的死锁证据：`SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK` 段，或 `performance_schema` / 错误日志里两个事务互相等待的完整现场；② 该死锁能在**本地**按固定步骤重放（不是"生产偶发"）。⚠️ 只有「MySQL 报过一次 too many connections」「演示时抖了一下」这类现象，**不构成**触发条件。 |
| 正确落点 | `GoodsService` 的**三处写方法**：`goodsAdd`（`GoodsService.java:155`）/ `goodsDe`（`:372`）/ `goodsUp`（`:422`）—— 它们是「多表写入 + `@Transactional`」的组合（`goods` 与 `productsales`，一次写两处）⇒ **死锁只可能产生在这类地方**（纯单表写不会与谁互等）。⚠️ 「落这三处」是**用户 2026-10-15 的裁定**，不是「全仓唯一的多表事务」—— `@Transactional` 在全仓 20 个文件里都有，别把本条读成排他性结论。 |
| ⚠️ 不得挂哪两处 | ① **不得挂 `OrderService`**：订单链**刻意**禁 `@Transactional`（`orderAdd` 那一族的注释逐字写着为什么 —— 订单状态机靠 CAS + 锁，不用数据库事务回滚）。挂重试会让「一次调用 = 多次 CAS」，与那套设计直接冲突。② **不得拿 `DistributedLockService` 当重试**：那个 cooldown 窗口是「**锁等待**」的重试（抢不到锁就等一会儿再抢），处理的是**并发**；死锁是**已经拿到锁的两个事务互相等**，锁服务看不见它，也解不了它。两件事的判据、落点、退避策略都不同 —— 混为一谈会写出「抢不到锁就重跑整个事务」这种把死锁变成活锁的东西。 |
| 落地时要注意的三条 | ① **AOP 同类自调用无效**：`@Retry` 与 `@Transactional` 同款，`this.xxx()` 调用不经过代理 ⇒ 重试注解必须挂在**被外部调用**的那一层（本仓的形态就是 `GoodsController` → `GoodsService` 的公开方法，天然满足 —— 但别把被重试的片段再往下抽一层私有方法，那样注解会静默失效）。② **顺序**：重试必须在**事务之外**（每次重试要开一个新事务，否则重试的是同一个已 rollback-only 的事务，重试 N 次仍全败）。本仓的切面 order 实测值（`@CircuitBreaker` = `Integer.MAX_VALUE-4` < 事务切面 = `MAX`）决定「熔断在事务外」；加 `@Retry` 时要**同样实测**它落在哪一层，别照抄。③ **落地前先做实验**：并发压商品写接口（两个事务反向更新同两行）造出真死锁，量出「退避多久 / 试几次」能过 —— 那两个数是**实验值**，不是拍脑袋的缺省值。 |
| 状态 | ✅ **已落码并关闭（2026-10-15 · 阶段 2b）** —— 触发条件已由实测满足、落点经实测订正，逐条见下面〈**TD-74 订正**〉。⚠️ 表里那句「本段不落码 / 面板那枚必须不可点」**只对 D 段 commit 2 那一刻成立**，现已被推翻（`retry` 现在**能拨且有事发生**）。 |

### TD-74 订正（2026-10-15 · D 段 · 阶段 2b）{#td-74-correction} —— 触发条件已满足 ⇒ 已落码；落点与「锁侧」各有一处**实测推翻预判**

> 上面那张表是 commit 2 的登记原文（保留不动）。本节把其中**三条推断**换成**实测**结果。
> ⚠️ 本节**刻意不写「文件:行号」**（除可点的路径外）：本文件处在 refs 扫描根 `docs/` 内，
> 写进去的行号会被反向抄成一条新的引用；要定位请用**符号名**。

| 原判（commit 2） | 实测（阶段 2b） | 依据 |
|---|---|---|
| 触发条件「先有一份**可复现**的死锁证据」**没满足** ⇒ 不落码 | ✅ **已满足**：48 轮 ×（1×A + 4×B），`ER_LOCK_DEADLOCK` 增量 **149**、且 `SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK` 段逐字命中本场景（同时含 `user` / `goods` / `merchant_application` 三表） | `docs/agent/evidence/c3-block7/d2-deadlock-scout-48x4.txt` |
| 落点 = `GoodsService` 的**三处写方法**（`goodsAdd` / `goodsDe` / `goodsUp`） | ⚠️ **订正：落点 = `MerchantDowngradeService.relist`**。全仓**唯一**的反向锁序对是 `MerchantApplicationService.audit`（写序 **user→goods**）× `MerchantDowngradeService.relist`（写序 **goods→user**）；`GoodsService` 那三处**不在任何一个环上** —— 它们是多表写入，但**没有**任何一个事务按相反顺序碰同一对表。⇒ 「多表事务 ⇒ 死锁只可能产生在这类地方」这条**推理链本身是对的**，错的是「本仓的多表事务里恰好有反向对的那一对在哪」 | 同一份证据的 `LATEST DETECTED DEADLOCK` 段（两个事务的 SQL 与持锁/等锁两列） |
| 「两表都只有主键、两条 UPDATE 都全表扫描」 | ⚠️ **订正（半条）**：`goods` 侧那条 `UPDATE ... WHERE MerchantId = ?` **确实**无索引可走 ⇒ 全表扫 + 对扫到的**每一行**加 X 锁，INNODB STATUS 报该事务持 **49 row lock(s)**；但 **`user` 侧不是** —— `UPDATE user ... WHERE username = ?` 走的是 **PRIMARY**（`user` 的主键**就是** `username`），它本来就只锁 1 行。⇒ 那 49 行锁**全部来自 `goods` 侧** | `EXPLAIN` 实测 + 同一份 INNODB STATUS |
| 落地注意三条（AOP 自调用无效 / 重试必须在事务外 / 次数与退避必须是实验值） | ✅ 三条都成立，且**第一条在本项目被换了一种形式踩到**：本笔没用注解 `@Retryable`（要读运行时开关），而是装配 `RetryTemplate`。⚠️ 实测发现 `RetryTemplate` 是「**先问 `canRetry`、再执行**」的循环 ⇒ 把开关直接短路在 `canRetry` 上，会让「关掉重试」变成「**连第一次都不执行**」（表现为抛 `RetryException("Exception in retry")`，且它**没有 cause** —— 原始死锁在异常链上就丢了）。修法一行：`if (context.getLastThrowable() == null) return true;`（先无条件放行第一次） | `RelistDeadlockRetryTest` 7 条全绿 |
| 「重试必须在事务之外（每次重试一个新事务）」 | ✅ 落地形态：`relist` **不挂** `@Transactional`，事务由内层 `TransactionTemplate` 开 ⇒ 每次尝试都是一个新事务。写了两条**结构钉**：① 反射断言 `relist` 上没有 `@Transactional`（有人加回去 ⇒ 重试退化成「在已回滚的事务里重试」）；② 每次尝试各起一个事务（计数替身 `CountingTxManager`） | `RelistDeadlockRetryTest` |
| 面板上那枚 `retry` **必须**不可点（死控件禁令） | ⚠️ **翻转**：它现在**能拨且有事发生**（关掉 ⇒ 一次失败即抛、不再试）⇒ 必须从 `PROTECTIONS_NOT_WIRED` 移出、画成**可拨**。⚠️ 留在那份清单里**反而会造出一个死控件** —— 正是用户在 `commit 2` 明令禁止的那一种 | `ProtectionGate.WIRED_KEYS`（4 键）/ `observe-labels.js` 的 `PROTECTIONS_NOT_WIRED`（只剩 `degrade`） |
| 「不得挂 `OrderService`」/「不得拿 `DistributedLockService` 当重试」 | ✅ 两条都遵守：重试只落在 `MerchantDowngradeService` 这一个类上，与订单链和锁服务都无关 | 同上 |

**结果（判据怎么读 —— 这一条最容易读反）**：落完索引**再**落重试之后重跑同一探针 48 轮 ⇒ **HTTP 5xx 由 139 → 0**，而 `ER_LOCK_DEADLOCK` 增量仍是 **141**（与「只加索引、没重试」那次的 139 同量级）⇒
**结论是「死锁次数不变、5xx 归零」，不是「加了重试所以不死锁了」**。

**因果闭合的那一环**（光看 `5xx=0` 还不够 —— 它还能被「异常被别处吞了」解释掉）：探针跑完立刻读**同进程** actuator ⇒ `mall.protection.events{result=retry_success}` = **141**，与 1213 增量**逐一对上**；`result=retry_failed` **0 条**（说明没有一次用满 3 次尝试）。三份对照表与原始输出见 `docs/agent/evidence/c3-block7/d2-deadlock-scout-with-retry.txt`。

**索引那一条要单独说清**：`goods` 的 `MerchantId` 二级索引（Migration ⑳）治的是**争用窗口的宽度**（49 row lock → 单条 record lock），**它不替代重试、也拆不掉环** —— 环的成因是**两个事务访问顺序相反**，与「各自锁住几行」无关：**只加索引那一次，5xx 仍然一个都没少**（139 → 139）。`user.merchantid` 的同类索引**不加**（实测该侧本来就只锁 1 行，裁定「留实测后再定」的答案就是「不必」）。

**状态**：✅ **TD-74 关闭（2026-10-15 · 阶段 2b）**。留一条**常态约束**：重试的「3 次 / 40ms 起 / ×2 / 封顶 200ms」是**实验值**，若将来下游延迟或 `goods` 数据量显著变化，应重跑 `scripts/probes/d2-deadlock-scout.mjs` 复核（判据③ `HTTP 5xx` 应为 0）。

## TD-75 熔断**只挂 MySQL 两个实例**：Redis / MQ **已有手写熔断** ⇒ 不再叠一层 resilience4j

| 项 | 内容 |
|---|---|
| 现状 | 本段的 r4j 熔断只有两个实例：`mallMysqlRead` / `mallMysqlWrite`（`@CircuitBreaker` 落在 `GoodsService` 5 处 + `OrderService.orderAdd`）。Redis 与 MQ 这两条下游**没有**加 r4j 熔断。 |
| 判据 | 那两条**本仓已经各有一处手写熔断**，而且是完整的「短路 + 半开 + 恢复」三态：① **MQ**：`RabbitEventPublisher` 的 `connected` 闸 + 探测线程 —— 断了就直接短路（发布变 no-op），探测线程按间隔重试连接 = 半开；② **Redis**：`DistributedLockService` 的 cooldown 窗口 + `L2HealthProbe` 的单飞 —— 不可达时窗口内直接走 fail-open，过了窗口放一个探测 = 半开。⇒ 再叠一个 r4j 熔断是**同一件事做两遍**：两个状态机各按自己的窗口判断「下游好没好」，可能出现「手写闸说通了、r4j 闸还开着」（或反之），那时**谁拦下了请求、为什么拦**在日志里会变成两个来源的拼接。 |
| 后果 | 「哪几个下游有熔断」在**代码里**不统一（MySQL 走注解、Redis/MQ 走手写）——这是本条要如实说明的代价。收益是每一处熔断的判据都在**看得见那条下游**的地方。 |
| 为什么不改 | 把手写那两处改写成 r4j 是**重构**、不是本段边界（会让块 2 / 块 7 已验收的降级行为全部重测）。 |
| 处理时机 | 若将来要统一，前提是「手写那两处的语义能被 r4j 完整表达」——⚠️ 至少 `RabbitEventPublisher` 那处不是纯熔断（它还要在断连时**降级成 no-op 而不是抛异常**），r4j 的 `CircuitBreaker` 只能返回异常或调用 fallback，语义要重新设计一次。 |
| 状态 | ⏸ **登记（2026-10-15，D 段 commit 2）**。 |

## TD-76 `goods.MerchantId` 索引（Migration ⑳）**只缩锁粒度、不拆环** —— 它不替代重试，重试也不替代它

> 本条的由来：顾问 2026-10-16 追问问到「索引到底治好了什么」。⚠️ **与 TD-74 不重复** ——
> TD-74 记的是「重试该落在哪、触发条件满没满」，本条记的是「这枚索引本身是什么性质」。
> ⚠️ 本节**刻意不写「文件:行号」**（理由同〈TD-74 订正〉节首）：要定位请用**符号名**。

| 项 | 内容 |
|---|---|
| **现象** | 同一探针、同一场景、同一台库，48 轮 ×（1×A + 4×B）：加索引前 `ER_LOCK_DEADLOCK` 增量 **149**、HTTP 5xx **139**；加索引后 1213 增量 **139**、**HTTP 5xx 仍然是 139**（一个都没少）；再落重试后 1213 增量 **141**、5xx **0**。⇒ 索引只改变了「每次死锁**牵连多少行**」，没有改变「死锁**还发不发生**」。 |
| **性质** | **缩锁粒度、不治本环。** 环的成因是两个事务**访问顺序相反**（`MerchantApplicationService.audit` 写序 user→goods × `MerchantDowngradeService.relist` 写序 goods→user），与各自锁住几行无关。索引把 `relistAllByMerchant` 那条 `UPDATE goods SET ShelfState = 1, DelistedAt = NULL WHERE MerchantId = ?` 的锁，从「全表扫到的**每一行**都加 X 锁（`SHOW ENGINE INNODB STATUS` 报该事务持 **49 row lock(s)**）」压到「`idx_goods_merchant` 上的**单条** record lock」——缩的是**牵连面**，不是**环**。 |
| **价值** | 对**别的**并发写路径有益：任何按 `MerchantId` 写 `goods` 的事务不再顺带锁住整张表（读路径的顺带收益见 `db-schema.md §18`：`GoodsCacheService` 那两处此前也是全表扫）。代价是一枚二级索引（写路径多维护一棵 B+ 树，本仓数据量下可忽略）。 |
| **触发条件** | 若将来**别的**写路径也撞上死锁，**先查这条**：判据 = `SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK` 段里，某一侧持 `N row lock(s)` 且 **N 远大于 1、N ≈ 表行数**（即「无索引 ⇒ 全表扫 ⇒ 行锁 × N」），再去找与它**方向相反**的那个写序。⚠️ **防的是这一种读法**：后人看到「死锁问题 + 有索引」以为索引治好了，把重试删掉 ⇒ 500 立刻回来（判据永远读证据，不读「有没有索引」）。 |
| **状态** | ✅ **已落、已实测、已记账**（2026-10-15 · D 段 · 阶段 2b）：Migration ⑳ 已 apply 到 VM 库。「不拆环、不替代重试」按顾问 2026-10-16 裁定要写进**三处**，三处现状：① 收笔报告（**逐字**）= `reports/块7-阶段2b-死锁修复-报告.txt`；② 迁移块注释（**逐字**）= `mall_management_sql.sql` 段⑳ 内；③ 本表（上方「性质」「触发条件」两行，按要点重述、非逐字）。另：`db-schema.md §18` / `db-migration.md` 注意 24 记的是这条索引自己的落点与读法。证据：`docs/agent/evidence/c3-block7/d2-deadlock-scout-48x4.txt`（基线，含 49 row lock(s) 与两个事务的持锁/等锁两列）· 同目录 `d2-deadlock-scout-after-index.txt`（只加索引：锁粒度已压到单条、5xx 仍 139）· `d2-deadlock-scout-with-retry.txt`（加索引 + 重试：5xx 0）。 |

## TD-77 延迟只有**累计 / 衰减窗口**分位 —— 拿不到**逐拍** P95/P99，要开直方图再对桶差分

> 本条的由来：2026-10-16 · 块 7 · 3a.2b 抓帧笔。原裁定是「延迟图加 P95/P99 的 60 拍折线」，
> 抓完真帧、量完**形状**才发现画不出来 —— 不是没数据，是**数据的口径不是逐拍**。
> ⚠️ 本节**刻意不写「文件:行号」**（同 TD-74 订正 / TD-76）：要定位请用**符号名**。

| 项 | 内容 |
|---|---|
| **现象** | `MetricsSnapshotService` 的 `requests` 段里，`meanMs` 走 `delta()`（`dLatencyMs / dLatencyCount`）= **逐拍真值**；而 `p95Ms` / `p99Ms` 直接取 Micrometer `Timer.percentile(0.95 / 0.99)` = **进程启动至今（衰减窗口）的累计分位**；`maxMs` 取 `Timer.max()` = 衰减窗口最大值，**代码注释自己写明「不是本拍最大」**。 |
| **实测（60 拍抓帧 · dev · 本窗合计 2259 请求）** | `meanMs`：59 拍有值、**55 个不同取值**、相邻变化 58 次 ⇒ 逐拍都在动。`p95Ms`：**4 个取值**、相邻变化 3 次。`p99Ms`：**3 个取值**、相邻变化 2 次。`maxMs`：**3 个取值**、相邻变化 2 次。⇒ 三条分位 / 极值序列画成折线都是**阶梯**（`maxMs` 也是阶梯，**不是**「整窗恒定」）。 |
| **后果** | 分位数**不能相减**（差分对分位无意义）⇒ **前端**无法把累计分位还原成逐拍分位。指标页的「延迟趋势」图**只能用 `meanMs`**；把 P95/P99 画成 60 拍折线会得到一条**看着像数据、实为常数**的线 —— 比一条贴地的直线更坑（贴地直线一眼假，阶梯线一眼真）。 |
| **落点** | 后端对 `http.server.requests` 的 Timer 开 **`publishPercentileHistogram(true)`**（必要时加 `serviceLevelObjectives`），前端 / 快照侧对**桶计数做相邻拍差分**再取分位。 |
| **前置条件与代价** | ① 桶计数同样是**累计**的 ⇒ 必须差分，仍**不能**直接读 `percentile()`；② 差分后的分位是**近似**（受桶宽限制，准确度取决于桶配置）；③ **每个 `uri × method` 一套桶** ⇒ 标签基数与内存上升，落之前先量；④ `isLatencyTimer` 的**排除名单仍必须生效** —— `/stream` 这类长连接一旦进桶，会把分钟级的连接时长混进延迟分位。 |
| **状态** | ⏸ **登记（2026-10-16 · 块 7 · 3a.2b 抓帧笔）**。本轮**不改后端** —— 本轮口径是「只加后端**已有**的数据」，逐拍分位属于**新能力**。3a.2b 指标页的延迟图按 `meanMs` 落地（图题写「平均延迟」，不写「P99 延迟」）。 |
| **证据** | `docs/agent/evidence/c3-block7/b7a2b-trajectory.txt`（逐拍轨迹 +「有值 / 不同取值 / 相邻变化」判读）· 同目录 `b7a2b-coverage.txt`（量级表）· `b7a2b-coverage-run1.txt` / `b7a2b-trajectory-run1.txt`（**同一份 `frames.ndjson` 上重跑一次**，与 `-run2` 逐字节一致 = 复现性对照；⚠️ 它们**不是**两次抓帧 —— 本笔共抓过 3 次窗，前两次的派生产物已作废重跑，见下行「订正」）。 |
| **订正（同一笔内自查，2026-10-16）** | 本条最初写的均值分位四个数（59 / 5 / 4 / 1）与「2772 请求」**都不是本窗的数**：前四个抄的是轨迹探针的**表头**行，而那一版表头把空值占位 `--` 也算进「不同取值」集合 ⇒ 恒比判读行多 1；「2772」出自**更早一次抓帧**。三处已按当前 `frames.ndjson`（60 拍 · 2259 请求）改正为 **55 / 4 / 3 / 3**。教训与 TD-74 订正同源：**引一个数之前直接读本体**（这里指 `frames.ndjson`，或至少探针的**判读行**），不从别的文档、也不从同一份输出的另一处抄。探针的表头与判读行**分叉**已从根上修掉（两处共用同一个 `stat()`）。 |
| **邻条自查（2026-10-16 · 同笔）** | 收笔时按「引一个数先 grep 本体」自查了另一句口头记录 ——「health 不翻的代码依据**已挂 TD-77**」：**不成立**，本条讲的是延迟分位，`tech-debt.md` 里没有任何一条记 health 的翻红判据。真正的依据在 `MetricsSnapshotService.health()`：置 `error` 需 `errorRate ≥ 0.05` **或** `p99Ms ≥ 1000`；置 `warn` 需 `errorRate > 0.01` 或 `lockUp == 0` 或 `l2Up == 0` 或 `stock.available == false`；都不满足则 `ok`，并**主动填一条正面理由**（`"L1 命中率 X%"`）⇒ `health.reasons` 在健康态**非空**是设计如此。本窗三者全不满足（`errorRate` 60/60 恒 0、`p99` 最大 82.838ms、两个 gauge 恒 1、`stock.available` 恒 true）⇒ **数学上不可能翻红**，不是「恰好没翻」。⚠️ D 段接通 `mqDown` / `dbSlow` 后要重看的是「这两条注入**能否**把 `errorRate` 推过 5% 或把 `p99` 推过 1000ms」—— 这是**红态入口**，本轮**未验**。✅ **2026-10-16 用户裁定：单开一条 ⇒ 已落 TD-78**（理由：TD-77 讲延迟分位、health 判据是另一件事，挂在本条下会被埋）；本行从此**只留指针**，内容不再在此处展开。 |
| **相邻账** | 与 **TD-70**（延迟口径的「排除名单」是**手工白名单**）**相邻但不同**：TD-70 管「哪些 URI 不进延迟」，本条管「进了的那些 URI 有没有逐拍分位」。开直方图时两条会被同时触碰。 |

## TD-78 health 的翻红判据只吃 **5xx 与 p99** —— **不看 4xx**；且当前窗口红态入口**不可达**

> 由来：2026-10-16 · 块 7 · 3a.2b 抓帧笔收笔时用户裁定**单开一条**（原先只挂在 TD-77 的「邻条自查」里当指针）。
> ⚠️ 本节**刻意不写「文件:行号」**（同 TD-74 订正 / TD-76 / TD-77）：要定位请用**符号名**。

| 项 | 内容 |
|---|---|
| **现象** | `MetricsSnapshotService.health()` 里**没有 4xx 这一项**。置 `error` 只有两条路：`errorRate ≥ 0.05` **或** `p99Ms ≥ 1000`；置 `warn` 需 `errorRate > 0.01` 或 `lockUp == 0` 或 `l2Up == 0` 或 `stock.available == false`；三条都不满足 ⇒ `ok`，并**主动填一条正面理由**（`"L1 命中率 X%"`）⇒ `health.reasons` 在健康态**也非空**是设计如此。 |
| **依据（读源码，非推演）** | 阈值常量 `ERROR_RATE_ERROR = 0.05` / `ERROR_RATE_WARN = 0.01` / `P99_MS_ERROR = 1000.0`，判据全在 `health()` 内。⚠️ **`clientErrorRate`（4xx）在 `requests` 段里算了、也放进了快照 payload，但 `health()` 从头到尾没读过它** —— 这是本条的**核心事实**，不是「大概不会用」。另：源码注释自认这三个常量**没有业务依据**（本仓没有 SLA 配置），是**演示口径**。 |
| **本窗实测** | 60 拍（2259 请求）里 `errorRate` **60/60 恒 0**、`p99Ms` 最大 **82.838ms**、`l2Up` / `lockUp` 恒 1、`stock.available` 恒 true ⇒ **数学上不可能翻红**，不是「恰好没翻」。 |
| **为什么算债** | ① **4xx 完全不进健康判据** —— 业务侧真出问题（校验风暴 / 参数被刷 / 客户端重试打满状态）只要不落成 5xx，状态带永远显示「健康」；② **红态入口当前不可达** ⇒ 「状态页 / 状态带在异常时长什么样」**从来没被验过**：颜色、图标、文案、以及 60 拍窗口下的过渡，全是未观测形态 —— 那是一份**等着要用、但谁也没见过**的 UI。 |
| **触发条件（满足任一即重审）** | ① D 段接通 `mqDown` / `dbSlow` 两条注入；② 任何一次演示要出「红态截图」；③ 讨论把 4xx 纳入健康判据。 |
| **待验证（D 段接通后必做）** | 这两条注入**能否真触发红态** —— 即能否把 `errorRate` 推过 5% 或把 `p99Ms` 推过 1000ms。推不过 ⇒ 红态入口仍不可达，得另想办法（临时调阈值做一次演示，或承认红态只是理论分支）。**本轮未验**。 |
| **状态** | 📌 **登记（2026-10-16 · 块 7 · 3a.2b 抓帧笔；用户裁定单开一条）**。本轮**不改代码** —— 3a.2b 的口径是「只加后端**已有**数据」，健康判据属**新口径 / 新能力**。 |
| **证据** | `docs/agent/evidence/c3-block7/b7a2b-coverage.txt`（`health.level` 60/60 = ok · `requests.errorRate` 60/60 = 0 · `requests.p99Ms` 最大 82.838）· 同目录 `b7a2b-trajectory.txt`。 |
| **相邻账** | 与 **TD-77** 相邻但不同：TD-77 管「分位口径拿不到**逐拍**」，本条管「健康判据**吃哪些信号** + 红态入口**可达性**」。两条会被同两处触碰：3a.5（状态页）与 D 段。 |

## TD-79 ③ 事件流的**缓存噪声**：当前不治理（用户裁定丁），但**判据留在这里**（免得下次凭「看着多」动手）

> 由来：2026-10-16 · 块 7 · 3a.3 收尾。用户在截图里看到 ③ 被缓存类刷屏（一屏里绝大多数是 `缓存命中`），
> 给的裁定是**丁：不治理** —— 「那批缓存是攻击模拟器打出来的，日常不会这样」，并要求留一句备忘。
> ⚠️ 本节**刻意不写「文件:行号」**（同 TD-74 / TD-76 / TD-77 / TD-78）：要定位请用**符号名**。

| 项 | 内容 |
|---|---|
| **现象** | ③ 那 200 行里 **76%（152 条）是缓存类**，且几乎每条一个独立 `traceId` ⇒ 连续同 trace 的 run 长度绝大多数是 **1**，**天生无法嵌套**，在树形视图里就是一片平铺的叶子。 |
| **依据（实测，可原地复现）** | `scripts/probes/forensics-b7a3-cache-noise.mjs` 读**已入库**的 `docs/agent/evidence/c3-block7/b7a3-events-60tick.ndjson`（60 拍真后端帧），口径 = **复刻页面自己的取数链**（`absorbEvents` → `ranged` → `slice(0, ROW_MAX).reverse()`），不是「把 60 拍拼起来看」。产出 `docs/agent/evidence/c3-block7/b7a3-cache-noise.txt`。 |
| **关键数（都在上面那份证据里）** | ① ③ 的 200 行：cache 152 / lock 20 / identity 16 / order 12。② 窗口内不同 `traceId` **144 个**，**没有 traceId 的行 0 条**。③ 连续 run 长度分布 `{"1":157,"2":2,">=3":10}` ⇒ **连续 ≥2 的只有 12 段**。④ 全量累积 1000 条里不同 `traceId` 只有 **585 个**。 |
| **为什么算债** | 不是「现在有 bug」，而是**判据不能凭观感**：缓存类是**每一个读操作都会写**的一类，它的条数随流量线性涨，永远是 ③ 里最多的那一档。下次再看到刷屏，**不能**用「缓存条数多」当动手理由（那永远成立）；要看的量是**连续 run 长度分布** —— `"1"` 那档占绝大多数 ⇒ 就是本次这种情况（数据本该如此，不是视图压不住），治它只会掩盖真实的数据形态。 |
| **反面记录（本轮实测推翻的一个预判）** | 曾预判「成组判据里那道 `recFor(tid)` 闸会挡住大量分组（收尾记录请求返回时才写）」，并差点据此改 `buildStream`。**逐拍实测否决了它**：把 60 拍按时间顺序一帧帧喂进去、每 5 帧采一次，**12 个采样点上「有收尾记录的比例」都等于 100%**（差值恒 0）⇒ 在本数据上拿掉那道闸**一段也多不出来**。故**不做**这次改动。⚠️ 教训：**稳态快照量不出流式过程**，要判「某道闸会不会挡」，得沿时间轴逐拍量。 |
| **触发条件（满足任一即重审）** | ① 日常（非攻击模拟器）流量下 ③ 仍被单一类刷屏；② 用户明确要求「折叠 / 降级 / 隐藏」某一类；③ 讨论把缓存类改成默认折叠。 |
| **真要治理时先量什么** | 先跑上面那个脚本，看 `"1"` 那档的占比：**占绝大多数 ⇒ 是数据形态**（治理只会把真实条数藏起来）；**`>=3` 那档变大 ⇒ 是视图压不住**（那时折叠/降级才有意义）。 |
| **状态** | 📌 **登记（2026-10-16 · 块 7 · 3a.3 收尾；用户裁定丁）**。本轮**不改代码**，只在组件 ③ 那一段留一行备忘指到本条。 |
| **证据** | `docs/agent/evidence/c3-block7/b7a3-cache-noise.txt`（脚本产出）· 输入 `docs/agent/evidence/c3-block7/b7a3-events-60tick.ndjson`。 |
| **相邻账** | 与 **TD-61**（事件台账是「观看时的过程记录」）同源：那条管**保留期**，本条管**同一窗口内的类分布**。两条都在说「③ 里看到的不是全量」的两件不同的事。 |

## TD-80 事件帧的「主体（谁）」：通道已落，但**两处恒 null 是口径不是缺陷**

> 由来：2026-10-16 · 块 7 · 阶段 3a.3（#42）。3a.1 的「消息扩充」把日志页**缺的那几块**逐条登记成
> TD-80 / TD-81 / TD-82 三条同源账；本笔是它们的**落地笔**（状态按实测改，不按计划改）。
> ⚠️ 本节**刻意不写「文件:行号」**（同 TD-74 / TD-76 / TD-77 / TD-78 / TD-79）：要定位请用**符号名**。

| 项 | 内容 |
|---|---|
| **缺口（本笔之前）** | 事件里**没有任何「谁做的」**：九类事件只有业务键（`key`）与对象字段；`audit` 环的 actor 只写在审计行自己身上、`identity` 环的 `subject` 是**请求路径**不是身份；台账 `observed_event` 更是一列都没有 ⇒ 日志页做不到「按人查」。 |
| **本笔落的** | ① **帧**每条多一个 `actor` 键 —— `{id, role}` **对象**（无身份时**整个是 `null`**，用户裁定 ②）；② **台账**多两列 `ActorId` / `ActorRole`（㉑-1 / ㉑-2，与 `admin_audit` 的两列同宽同义）。取值口径：**9/9 环都从 MDC 现读**（`TraceIdFilter.currentActorId()` / `currentActorRole()`）；**`audit` 环是唯一另有来源的** —— 它取审计行自己的 `Actor` 列（`AuditMetrics.AuditEvent#actorId`），因为审计行有可能不是当前请求写的。 |
| **边界 ⓐ：闸关时恒 null** | `app.auth.enabled=false` 的 profile（**dev 与 a3 两个都是**）下 `AuthSession.identify()` 根本不解身份 ⇒ MDC 里没有 actor ⇒ 手验时**全 null**。这不是缺陷：**手验 actor 必须换默认 profile**。 |
| **边界 ⓑ：非请求线程恒 null** | 定时任务 / SSE 流线程 / 攻击派发线程上没有请求上下文，`TraceIdFilter` 的 MDC 不存在 ⇒ `null`。 |
| **边界 ⓒ：无身份 ⇒ 整个对象为 null** | 不是 `{id:null, role:null}` —— 「根本没有身份」与「有一个身份但两个字段都空」在页面上是两句话。已由 `EventStreamServiceTest` 的 `isNull()` 断言钉住。 |
| **判据（可复核）** | 帧的形状**两侧都有测试**：`EventStreamServiceTest` 里 ① 无身份 ⇒ `actor` `isNull()`；② **装好 MDC ⇒ `{id, role}` 对象**（⚠️ 本条是**本笔补的** —— 在此之前「正向那一半」无任何用例覆盖，而它才是裁定 ② 的形状本身）。突变对照：把 `EventStreamService.actorMap` 改成恒返回 `null` ⇒ **恰那一条转红、其余 8 条仍绿**（判别力实测，2026-10-16）。 |
| **状态** | ✅ **已落**（2026-10-16 · 块 7 · 阶段 3a.3；帧与台账两条通道都通）。⚠️ ⓐ/ⓑ 两条边界**保留为口径**：真要「任何事件都能追到人」，得先给那两处补身份来源，**不是**在页面上把没有身份的写成「未知用户」。 |
| **本笔（3a.3 第二步）的相邻裁决：新的每环字段**不进**公共面** | 本步给库存环加了 `remaining`（扣完那一刻的剩余件数）。它**刻意不走** `Sourced`（九环共用的那个接口），而是照 `key` / `objectType` / `objectId` 的先例，**作为 `EventBroadcaster.add(...)` 的显式形参**逐环传。依据是 `Sourced` 自己的 javadoc 对 `key()` 的裁定原文：「九个环里 `key` 的名字与语义都不一样 …… 那是各环自己的业务字段，归 `ingestXxx` 在调用点逐环给定，**不属于这条公共面**」。⇒ **别把 `remaining` 「顺手」提进 `Sourced`** —— 提进去就等于宣布「九环都有剩余库存」，而实际只有库存环有。⚠️ 代价是 9 个调用点里 8 个要显式写 `/* remaining */ null`（那行内标记就是给这件事留的痕），这是**故意**的。 |
| **相邻账** | 与 **TD-81**（事件级耗时）/ **TD-82**（`detail` 是自由文本）同源：三条都是「日志页的消息里缺的那一块」。 |

## TD-81 事件级耗时：通道已落；**哪些档恒 null 是判定**

> 由来：同 TD-80（3a.1 登记，本笔落地）。⚠️ 同样**不写「文件:行号」**。

| 项 | 内容 |
|---|---|
| **缺口（本笔之前）** | ① 帧里只有**请求级**耗时（`traces[].ms`），**事件级**没有 ⇒ 同一条请求里哪一步慢，页面上看不出来；② 锁环的**等待时长后端其实已经算出来了**，却没带进任何记录（算了丢掉）。 |
| **本笔落的** | ① 帧每条多一个 `durationUs` 键、台账多一列 `DurationUs bigint`（㉑-3）；② 口径 = **本条事件主体的耗时，单位微秒**（用户裁定 ①：L1 缓存命中实测 0.2~1 µs，毫秒列对它永远是 0）—— 由各环在记录那一刻 `durationNanos / 1000`；③ 锁环把**已经算好的**等待时长带出来了（`acquire` 一档），**DEGRADED 那一档刻意落 `null`** —— 它的 `0L` 意思是「在 startNanos 之前就返回、压根没尝试」，是「没测过」不是「测出来是 0」。 |
| **形参形状（约定，不是巧合）** | 「**必然发生**的耗时」传 `long`、「**可能不发生**」传 `Long`（`null` = 没量到 / 不适用）。⚠️ 耗时参数**故意不给重载** —— 本仓栽过「有重载 ⇒ 新调用点顺手用了短的那个 ⇒ 耗时静默丢失」。 |
| **哪些档恒 null（判定，不是缺口）** | `lock.hold`（释放动作本身没有有意义的起止；`lease_lost` 连网络都没碰）、`lock.transition`（翻转是瞬时事实）、`lock.acquire` 的 DEGRADED（没尝试），以及所有**非请求线程**上产生的事件。 |
| **仍未落：`timelineWrite` 的耗时只有失败那一半可见** | 订单轨迹写入这一档，**成功路径没有计时**，只有失败 / 回滚那条算得出耗时 ⇒ 页面上会看到「轨迹写入」有时带 µs、有时没有，**不是字段丢了**。⚠️ **2026-10-17 · 块 7 · 阶段 3a.3 第二步：用户裁定「不补」**（该轮「字段补齐·收尾 5 件裁定」里的一件）⇒ 本条**不再是「待裁决」，是「已裁决：维持现状」**。⇒ 页面上那个「有时带 µs、有时没有」是**长期口径**，别当回归来修。 |
| **状态** | ✅ **已落**（2026-10-16 · 块 7 · 阶段 3a.3；事件级耗时通道 + 锁等待时长都通）。⚠️ 上面那条 `timelineWrite` 已于 **2026-10-17 裁定「不补」**（口径不再变动），本条**没有剩余缺口**。 |
| **相邻账** | 与 **TD-80** / **TD-82** 同源。 |

## TD-82 `detail` 是自由文本、要前端解析：本笔补了结构化 `fields`（**2026-10-17 口径修订后帧与台账都落了**）

> 由来：同 TD-80（3a.1 登记，本笔落地）。⚠️ 同样**不写「文件:行号」**。

| 项 | 内容 |
|---|---|
| **缺口（本笔之前）** | 卡片第二行与抽屉里的业务细节全塞在 `detail` 一个字符串里（`k=v` 拼串）⇒ 前端要拿某一项只能**自己解析文本**；解析规则一改，后端与前端就各自漂移。 |
| **本笔落的** | 帧每条多一个 `fields` 键 = **结构化明细表**（`Map`），与 `detail` **同源**（在同一个 `Emit` 里一起构造，不是两处分别拼）。 |
| **2026-10-17 口径修订（块 7 · 阶段 3a.3 第二步）** | 原口径「**有界标量进列，无界自由文本不进**」把 `fields` 挡在台账外（理由见 ㉑ 那段：`fields` 与 `detail` 说的是同一批事实、含只对当下那一拍有意义的 `src=环名#源seq`、且内容无界）⇒ **回放（H.8）拿不到 `fields`**。用户这轮裁定**加（甲）**，原话：「**加（不然会缺失当时的数据流程，没办法朔源性找寻病因，那这个回放的作用就降低了）**」。⇒ ㉒ 给 `observed_event` 加 `FieldsJson json`，把整表序列化落库。**被推翻的只有「不进列」这一半**：「绝不截断」是为了不写出非法 JSON（截断 = 整批 DLQ）、「序列化失败抛而不落 NULL」两条都**继续有效**，只是实现位置从「不落」变成「落但保真」。 |
| **为什么曾经算「部分落」** | 当时 `fields` **不进台账** ⇒ 帧侧落、台账侧刻意不落；`detail` **本身保留**（它给人读、也进台账）⇒「前端要解析自由文本」这条**没有消失**，只是多了一条可选的结构化路。⚠️ 这一点**修订后仍然成立**：`FieldsJson` 是**新增的一条路**，不是 `detail` 的替代 ⇒ 前端**依旧不该**去解析 `detail`。 |
| **判据（可复核）** | `EventBroadcasterTest` 有一条断言把 `fields` 里的 `result` 与 `detail` 里 `result=` 后面那段**逐字比对** —— 两处分别构造必然漂移，那正是本仓反复吃的亏。落库侧的可复核证据见 `docs/agent/evidence/c3-block7/` 下的 ㉒ 复核产物（台账回读里 `FieldsJson` 被 json 列读回成对象，不是裸字符串）。 |
| **状态** | ✅ **已落**（**2026-10-17 口径修订** · 块 7 · 阶段 3a.3 第二步）：帧侧已落（2026-10-16）、台账侧**改为落**（㉒ 加 `FieldsJson`）。⚠️ 前端读这一列要 `JSON.parse`（驱动把 json 列给成**字符串**，不是对象），见 `api-contract.md` 附录 H。 |
| **相邻账** | 与 **TD-80** / **TD-81** 同源；与 **TD-61**（台账是「观看时的过程记录」）相邻但不同。 |

## TD-83 `observed_event.Kind` 的**实库列注释**缺第 9 类 `protection`（源 `.sql` 有、库里没有）

> 由来：**2026-10-17 · 块 7 · 阶段 3a.3 第二步**（「字段补齐·收尾 5 件裁定」里的【遗留 2】）。⚠️ 同样**不写「文件:行号」**。

| 项 | 内容 |
|---|---|
| **事实（已第一手实测，不是推断）** | `mall_management_sql.sql` 里 `observed_event.Kind` 那行的 `COMMENT` 写的是「……identity / protection（对齐前端 KIND_LABEL；第 9 类 `protection` 由 D 段 commit 2 加，2026-10-15）」，而 **VM 实库里读出来的列注释只有到 `...identity（对齐前端 KIND_LABEL）`**，`/ protection` 那一段**不在**。两侧**曾经一致**，是后来分叉的。 |
| **怎么分叉的（因果）** | 事件九环的**最后一类** `protection` 是 **D 段 commit 2（2026-10-15）** 才加进设计的；而 `observed_event` 这张表是更早的 **⑲ 段**就落库的。⇒ 库里的列注释**停在加 `protection` 之前**，源 `.sql` 那行后来被改过、库没跟着改。**不是**谁写错了字，是**时序**。 |
| **为什么不「顺手改一下」** | 改一列注释在 MySQL 里**没有单列 DDL**，要写整条 `MODIFY COLUMN`（列类型/可空/默认/注释一起给）—— 那是 **A 档变更（DDL，要落库、要留迁移段）**，不是一次纯文档编辑。为一行注释单开一个迁移段不值。 |
| **用户裁定（2026-10-17）** | **乙案：挂 TD，不现在改** —— 等**下次本来就要动 `observed_event` 的 DDL** 时（那时手上已经有一条 `MODIFY COLUMN` 的由头）**顺带**把注释补齐。 |
| **影响面（为什么不算功能缺陷）** | `Kind` 的**取值**该有 `protection` 就有 —— `protection` 是**代码里**写进去的，不依赖列注释。缺的只是**注释文本**，影响的是「打开库看列定义的人」对取值域的认知，不影响读写、不影响前端 `KIND_LABEL`、不影响 ⑲ 迁移的幂等。 |
| **判据（可复核，两处都是第一手）** | ① **源**：`mall_management_sql.sql` 里 `observed_event` 建表那行的 `Kind` `COMMENT`（`CREATE TABLE` 段内）逐字含 `/ protection（对齐前端 KIND_LABEL；第 9 类 \`protection\` 由 D 段 commit 2 加，2026-10-15）`；② **实库**：`docs/agent/evidence/c3-block7/3a3c-migration-21-verify.txt` 的第 4 条（`observed_event` 全部列）里那一行实读 —— 写的是「……identity（对齐前端 KIND_LABEL）」，`/ protection` 与「第 9 类」那一段**都不在**。⚠️ **别用 `SHOW CREATE TABLE` 的输出当判据**（Windows/GBK 终端会把中文注释打乱，比不出「少一段」还是「乱码」）；也别在 `mall_management_sql.sql` 里 grep「protection」就下结论 —— 那只能证明**源**里有，证明不了**库里**有。 |
| **状态** | 🟡 **已知未修（挂 TD）**（2026-10-17 登记）：修复动作 = 一条 `MODIFY COLUMN`，**等下一次动 `observed_event` 的 DDL 时合并做**。 |
| **相邻账** | 与 **TD-82**（同表、同一次 ㉒ 迁移的邻域）相邻；与 **TD-51** / **TD-54**（「文档与实物已分叉、登记不修」的同一族）同类 —— 差别是这两族的实物是**行号**，本条是**列注释**。 |
