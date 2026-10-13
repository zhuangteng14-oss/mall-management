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
| 观测地基 | `MallCacheMetrics` —— **11 个 `mall.cache.*` 指标** + **512 槽事件环**，「计数 + 事件流」合并于同一调用点（防两通道口径漂移）；Actuator 暴露 `health,metrics`；**关闭 Boot 的 Redis health indicator**（它是绕过冷却窗口的第二条探测通道） | `aab3b9d` |
| 分布式锁 | `DistributedLockService.runLocked` 模板方法 + **5 个落点**（`order-add` / `order-pay` / `order-receive` / `order-delivery` / `friend-add`）；`app.lock.wait=5s` / `lease=10s` / `cooldown=30s`；**fail-open**：取锁超时、Redis 不可达、冷却窗口**三路都回落原路径**，不新发任何拒绝文案 | `8810efe` |
| 锁观测 | `MallLockMetrics` —— **4 个 `mall.lock.*` 指标**（`acquires` / `wait.duration` / `holds` / `up`）+ **独立** 512 槽事件环（不共用缓存那份，代价换 C1 一个字不动） | `8810efe`；`application.properties` B2 节 |
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
| 状态 | ⏸ **登记不修（2026-10-11，可视化段 commit 3 补丁）**。 |

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
| 状态 | ⏸ **登记不修（2026-10-12，块 5 收口）**。 |

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
| 状态 | ⏸ **登记不修（2026-10-13，块 6 收口）**；证据 `b6-c7-fake-data.txt` §5 第 2 条。 |

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
| ② 内容域 **13** 个端点全部落地 | 已落 —— `comment` 7 + `report` 3 + `sensitive` 3，逐条见 `api-contract.md` 附录 G。 |
| ③ 敏感词预置 10 词 | 已落 —— Migration ⑮-8 随建表落库（**不是**探针插的），10 词逐条实读见 §4.7。 |
| ④ 评论图片「先审后发」+ 驳回**真删仓库文件** | 已落 —— `comment_image.Status` 三态；`pending` / `approved` 各有样例。⚠️ `rejected` 无常驻样例是**故意**的，见 TD-48。 |
| ⑤ 举报满 **3** 次警告自动封号 | 已落 —— 复用既有 `user.banned`（**不新建封号表**），`WarnCount` 只记计数。 |
| ⑥ 回复带 `@` **自动生成** `comment_mention` 行 + 发 `comment.mention` 通知 | 已落 —— 无前端输入框（后端解析正文），样例见 §4.1 的 `Id=117` + §4.5。 |
| ⑦ 匿名评论**强制 5 星** + 显示「首字*****」 | 已落 —— 样例 `Id=114`（`IsAnonymous=1`、`Rating=5`）。 |
| ⑧ 提问**不可自删** + 问答比例图（环图） | 已落 —— 块 6 整改 commit 3；`ModerationPage` 问答 tab 的解决率环图读 `stats` 的 `Solved` / `Unsolved`（只统计顶层提问）。 |
| ⑨ 管理端「消息与内容治理」页**六 tab 全可操作** | 已落 —— 评论管理 / 问答管理 / 待审图片 / 举报中心 / 敏感词库 / 消息管理（`views/admin/ModerationPage.vue`，`router/index.js` 的 `business/moderation` 由占位**同行替换**成真页）。 |
| 状态 | ✅ **不挂账（2026-10-13，块 6 收口）** —— 本节只记「这九项别被当成待办」。 |

## TD-53 跨块挂账（块 6 之后：块 7 / 块 8 + 四批补充功能 + 数据重填）

| 项 | 内容 |
|---|---|
| 块 6 | ✅ **已收口**（本节之前的 TD-46 – TD-52 是其挂账）。⚠️ 原 TD-45 里「`comment` 与 `user.WarnCount` 未建」的说法**在本笔之后失效** —— 两者块 6 均已落（`db-schema.md §15`）。 |
| 块 7 | **可观测 / 治理域**（最大的一块）—— TD-36（SKU 扣减）与 **TD-38（秒杀真扣减）** 都排在这里；块 6 的 TD-47（举报 `user` 无处置落点）**不**排进块 7（它要的是产品裁决，不是工程排期）。 |
| 块 8 | **部署**。 |
| 四批补充功能 | 未拆细，沿用既有登记。 |
| 数据重填 | 演示数据的一次重灌（口径见 `seed-demo.sql` 头注与 TD-37）。⚠️ 块 6 的验收假数据**不在**种子文件里（见 TD-50）⇒ 重灌前先把 `ProbeB6c7Seed.java` 跑一遍。 |
| 状态 | ⏸ **登记（2026-10-13，块 6 收口）**。 |

## TD-54 引用重指**未应用族**逐条清单（块 6 收口实测；**登记不修**，与 TD-51 同族）

| 项 | 内容 |
|---|---|
| 现状 | 块 6 重指脚本（`_work/b6-repoint-apply.py`）**只应用 `MOVED-SAME`**，其余一律 SKIP 并记理由 ⇒ 收口复跑后仍有 **141 行**「引用者按 `f2b1535` 坐标写就、目标又在块 6 移位」的引用**未被重指**：**源侧 13 行 + 文档侧 128 行**。逐条台账 = `_work/b6-baseera-movedsame-by-reason.txt`（按理由分组）+ `_work/b6-refs-target-head3.tsv`（全量）＋ `_work/b6-repoint-log.tsv`（逐行 SKIP 理由）。 |
| 判据 | 「未应用」不是脚本失败：① 文档侧 47 行落在 `docs/agent/context-packs/`（25）・`docs/agent/evidence/`（21）・`docs/美化/`（1）—— **史料 pack / 证据档**，按护栏 `PREFIX` 一律不碰；② 文档侧 33 行命中 `GUARD`（自述「改前 / 旧引 / 不修 / 误报源」等叙事行）· 18 行命中 `SIBLING`（同行还有别的裸 `:NN`，只改主引会造半新半旧）· 2 行 `PINNED`（台账 B 档 / 「注释初版」自述）· 1 行 `RANGE-DIFF`（区间整段内容不一致）；③ **源侧 13 行**逐条见下（这 13 行才是「落在活代码注释里」的那批）。 |
| 源侧 13 行（逐条，含实测真值） | **族①（TD-51 已登记，共 6 行）** —— 引用者断言 `ProductPage.vue:354` 是「是否现在付款？」弹窗，而基线 `:354` 实为**评价分页按钮**：`order/OrderState.java:43` / `:116`、`order/service/OrderService.java:119` / `:379`、`OrderAddStateGuardSmokeTest.java:33` / `:120`。**族④（本笔补登记，2 行）** —— 同族不同行：`OrderService.java:95` 说「`ProductPage.vue:339-341` 是数量拼接」、`OrderService.java:339` 说「`:321-323` 是价格拼接」，而基线两处的实际内容是**旧评价区的星级分布条与评价卡标记**（真值：`:321-323 → :348-350`、`:339-341 → :412-414`）。**族⑤（本笔补登记，5 行）** —— **基线时本来是对的**，被块 6 的 `ProductPage.vue` 长大顶偏，且因 `SIBLING` / `GUARD` 未自动重指：`router/index.js:141`（`ProductPage.vue:361` / `:363` / `:388` / `:398` / `:400` → 实测 `:533` / `:535` / `:560` / `:570` / `:572`）、`AppShell.vue:227`（`:380` → `:552`）、`HomePage.vue:299`（`:426` → `:598`）、`CashierDialog.vue:103`（`:654` → `:849`）、`ShopHome.vue:266`（`:459` → `:631`；⚠️ `:459` 本身即 TD-51 族②的已知误指）。 |
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
