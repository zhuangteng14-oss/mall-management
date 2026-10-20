# 项目开发 Agent 规范

你是本项目的开发 Agent，只负责一个功能开发任务。必须严格遵守以下规则。

## 项目目录结构（Monorepo）

```
项目根目录（Git 仓库根）/
├── agent.md
├── docs/agent/
├── Mall-management-web/mall-management-system/     ← 前端 Vue 3 工程
└── Mall-management-server/                        ← 后端 Spring Boot
```

【命令执行位置】

- Git 命令：项目根目录
- 前端命令：`cd Mall-management-web/mall-management-system && ...`
- 后端命令：`cd Mall-management-server && ...`

【文件引用格式】

- 前端：Mall-management-web/src/views/Dashboard.vue
- 后端：Mall-management-server/src/main/java/.../OrderService.java

## 项目背景

- 技术栈：Spring Boot + Vue3 + MySQL + Redis
- 代码规模：约 3000 行，含本地数据库
- 核心关注：多线程、并发、异步、Redis 缓存、乐观锁、分布式锁、状态机
- 必须避免：缓存击穿、缓存雪崩、缓存穿透、分布式锁死锁、MySQL 死锁、状态机循环死锁、线程池死锁

## 全局硬性约束

1. 所有功能实现不能阻碍旧功能运作，必须保持向后兼容。
2. 禁止删除旧接口、旧字段、旧表、旧配置。
3. 数据库变更**只允许追加，禁止删除**。追加分两档：
   (a) 无条件允许：新增表、新增字段、新增索引（含 `UNIQUE` 唯一索引）。
   (b) 有条件允许：新增外键（FK）、修改列类型 —— 条件见「数据库管理规则」4。
   仍然禁止：删表、删列、删字段、删已有索引与约束（见「数据库管理规则」2）。
4. 禁止直接修改或推送 main 分支。
4.1 单人项目例外：当项目由单人开发、无独立 reviewer 时，允许以下操作：
    (a) 本地 review + 直接合并 feature 到 main
    (b) 纯文档变更直接提交到 main（代码变更仍须走 feature 分支）
    两种情况都必须满足：
    - 合并/提交前执行 git diff 或 git status 逐项查看
    - commit 信息中标注 self-reviewed（纯文档可选）
    - 有 CI 时确保 CI 通过
5. 禁止对 main、dev 执行 force push、reset --hard、rebase。
6. 只允许对自己的 feature 分支 rebase。
7. 不确定的改动先输出方案，等人工确认，不要直接改代码。
8. 禁止在 Mall-management-web/ 或 Mall-management-server/ 内单独 git init。
9. 【文档准确性】
   1. 禁止凭文件名、路径、命名习惯推断文件用途，必须读内容。
   2. 新增/删除文档、代码、目录后，必须检查所有引用它们的文档并同步更新。
   3. 修改文档前，先 grep 全文找同类错误，不要凭记忆列清单。
      例：修 Vue 版本时，`grep -rn "Vue 2\|Vue2\|Vue 版本待确认" docs/ agent.md`

## 文档三层治理（2026-10-11 立）

文档按**变更纪律**分三层，改文档前先认层：

| 层 | 文档 | 块内 | 收口 |
|---|---|---|---|
| **活文档** | `context-packs/`、`db-schema.md`、`api-contract.md` | 能不改就不改，只加新事实 | 一次性重指 |
| **账本** | `progress.md`、`slot-registry.md`、`tech-debt.md` | 一行不动 | 一次性追加 |
| **档案** | `evidence/`、`archive/`、`progress.md` 的旧条目 | **永不改** | —— |

五条硬规则：

1. **块内不动文档** —— 一个块（一笔开发）进行中不碰上述文档；所有文档变更留到收口时一次性落。
2. **档案类永不改** —— 已归档的记录是**当时的事实**，不追、不订正、不补行号。
3. **跨块引用不改** —— 别的块留下的引用记的是历史，保持原样。
4. **档案里的旧行号不追** —— 读到旧条目里的 `文件:行号` 按「当时事实」理解，不要求它指向今天的行号。
5. **底座引用一律写 `#锚点`** —— 在文档 / 代码 / 注释里引用**活文档**（`db-schema.md` /
   `api-contract.md` / `context-packs/*.md`）时写 `文件.md#锚点`（如 `db-schema.md#db-6-3`），
   **不写 `文件.md:行号`**（行号会随增删漂移，锚点不会）；**账本 / 代码 / 稿的引用仍写行号**。
   两类靠**约定**（落笔自查，无脚本拦）：锚点须已存在、活文档不写行号；收口跑 `doc-anchor-check.sh`。

> 活文档的坐标改用 `#锚点` 后「几乎不漂」：块 5 起跨块零重指。锚点形式 `{#<前缀>-<slug>}`，
> 前缀 `db` / `api` / `pack-<name>`，slug 取标题里**稳定的编号部分**（如 `6.8` → `db-6-8`）。

## 项目级规则（块 4 后陆续立 —— 2026-10-11 一次性落盘）

> 这批规则原先只在对话里，`/clear` 即丢 ⇒ 落盘到本文件。
> **「文档三层治理」「块内不动文档」两条见上一节**，此处不重复。

1. **验收归用户，agent 不写重探针** —— 端到端 / 手工验收由**用户**跑并裁决；agent **不主动**写大型验证探针
   （`scripts/probes/` 下的一次性脚本 = 用户明令才写，产物去向见「开发要求」9）。验收结论以用户为准。
2. **代码优先，文档服务项目** —— 文档为交付服务，不为文档而文档；能靠代码自证的不写文档，**落码优先于落文档**。
3. **加图不动结构** —— 往既有页面加图表 / `KpiCard` 时**只插容器**（新增区块），
   **不重排、不改间距、不动 tokens**（口径见 `docs/美化/看板规范.md`）。
4. **提速规则（A / B / C 三档）** —— 每个决定先归档：**A 必问**（= 落在下条三条硬线上，或撞用户明令）；
   **B 自裁 + 报备**（不落硬线，可自决但要写进方案 / 收口）；**C 自决**（落码时按方案走，不单独问）。
5. **三条硬线（→ 必问）** —— **建表 / 改契约 / 删文件**。任何改动碰到这三条，一律**先出方案、等用户批**再落
   （同「全局硬性约束」7）。
6. **老表优先（同区块优先加列）** —— 需要新字段时，**优先在既有同区块表上加列**，而非新建表；
   确要建新表，须在方案里说明**为何旧表放不下**（建表本身即硬线 ⇒ 必问）。

## 数据库管理规则

1. mall_management_sql.sql 位于项目根目录，是数据库权威 Schema，已纳入 Git。
2. 禁止删除或修改 mall_management_sql.sql 里已有的表、字段、索引、约束。
   本规则与硬性约束 3 精神同形：**禁破坏，不禁演进** —— 允许的演进方式见规则 3。
3. 所有数据库变更只允许追加到 mall_management_sql.sql 末尾，格式：

```sql
-- ============================================================
-- Migration: <功能短名>  Date: YYYY-MM-DD
-- ============================================================
CREATE TABLE IF NOT EXISTS xxx (...);
ALTER TABLE xxx ADD COLUMN new_col VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_xxx ON xxx(col);
CREATE UNIQUE INDEX uk_xxx ON xxx(col);
ALTER TABLE xxx ADD CONSTRAINT fk_xxx FOREIGN KEY (col) REFERENCES yyy(col);
-- 改列类型（禁止 MODIFY COLUMN）：影子列 —— 新增新类型列 → 回填 → 切读 → 停用旧列（**不删旧列**）
ALTER TABLE xxx ADD COLUMN new_col_v2 VARCHAR(128) DEFAULT NULL COMMENT '影子列：替代 old_col（新类型 <T>），旧列停用不删';
UPDATE xxx SET new_col_v2 = <转换表达式> WHERE new_col_v2 IS NULL;  -- 回填；大表须分批
```

> 三类新增 DDL 的前置（UNIQUE / FK / 影子列）：`CREATE UNIQUE INDEX` 须**先跑重复行检测**（每个库都是 0
> 才能建；MySQL 无 `CREATE INDEX IF NOT EXISTS`，幂等性由「导入 = 先 DROP 再 CREATE」保证）；
> `ADD CONSTRAINT ... FOREIGN KEY` 须**先确认子表无孤儿行**；改列类型须**回填完成并切读后**才能停用旧列。

4. 禁止使用 `DROP TABLE`、`DROP COLUMN`；**禁止用 `MODIFY COLUMN` 改变已有列的类型** —— 改类型一律走规则 3 的
   影子列写法。规则 3(b) 的「有条件允许」项，四项前置**缺一不可**：
   ① 正向 SQL（追加到 `.sql` 末尾，格式见规则 3）
   ② 回滚 SQL（**逻辑废弃**语义，见下）
   ③ 迁移脚本（含幂等探测）
   ④ 先在同版本 VM 上验证，再 **A3 全量绿**
   四项不满足一律按禁止处理；变更另须按规则 5 记入 `docs/agent/db-migration.md`。
   ⚠️ **回滚 SQL 的语义 = 逻辑废弃，不是物理删除**：对「新增字段 / 新增表」类变更，回滚写「保留列 / 表 +
   置 NULL / 停用」，**不得**写 `DROP COLUMN` / `DROP TABLE`。这消除本规则与规则 5（须登记回滚 SQL）的既有矛盾。
5. 每次数据库变更必须同步在 docs/agent/db-migration.md 里记录：
   功能名、变更类型、涉及表、SQL、回滚 SQL。
6. 读取表结构时优先读：
   - mall_management_sql.sql
   - docs/agent/db-schema.md

   禁止连 MySQL 查 information_schema。
7. 新增字段必须：
   - 有默认值或允许 NULL
   - 有注释（COMMENT）
   - 加在表末尾，不影响旧列顺序
8. 新增表必须：
   - 指定 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
   - 有主键
   - 有 created_at / updated_at
   - 涉及并发查询的字段加索引

## 分支规则

1. 先执行：

```bash
git checkout main
git pull origin main
```

2. 从 main 切出功能分支：

```bash
git checkout -b feature/<功能短名>
```

3. 所有开发、提交、测试都在 feature 分支完成。
4. 禁止直接 push 到 main。
5. 如果 main 有更新：

```bash
git fetch origin
git rebase origin/main
```

解决冲突后继续。只允许对自己的 feature 分支 rebase。

## 省 Token 规则（必须严格遵守）

1. 禁止整项目重读。只读我指定的文件和 docs/agent/ 下的摘要。
2. 禁止输出完整文件。只输出 unified diff / patch。
3. 禁止重构无关代码。
4. 禁止把全量测试日志贴回来。只回传失败用例和错误栈前 20 行。
5. 分析阶段不写代码，只输出计划；确认后才输出 diff。
6. 输出尽量用结构化 JSON，不要长篇解释。

## 会话管理规则

1. 一个 feature 一个会话。完成后输入 /clear，开新会话。
2. 新会话开场只读四个文件：
   - agent.md
   - docs/agent/README.md
   - docs/agent/context-packs/<当前任务>.md
   - docs/agent/selection-status.md（项目待办与优化总表 §8 + 新功能引出表 §9）
3. 不要整目录加载 docs/agent/。**做 C3 各块**时改成两步：先读 `docs/agent/c3-blocks-index.md`，再读该块的块级 pack（`docs/agent/context-packs/`）—— **不要全读 `docs/`**。
4. 上下文接近满时（Claude 提示或响应变慢）主动建议 /clear。
5. 已完成的工作必须写入文件（文档或 commit），不能只存在于对话。
6. 每次新会话开场，先执行 git log --oneline -5 了解当前位置。

## 工具事实

- `gh` 的可用性**依赖会话环境**：本工具 bash 进程可能继承、也可能不继承用户 PATH。**每次开场直接试 `gh --version`**：若报 `command not found`（退出码 127），改用绝对路径 `"/c/Program Files/GitHub CLI/gh.exe"`。
- **`gh run list --commit <sha>` 会返回假阴性**：已用确知有运行的 `c4807d9` 做对照验证，同样返回空。查某个 commit 对应的 CI run，必须用 `gh run list --json headSha,...` 拉全量后本地比对，**不要用 `--commit` 过滤器**。
- 本仓库为私有仓库，匿名 `api.github.com` 请求返回 404；查 CI 状态一律走 `gh`。

## 技术选型（能引库就不自研）

【本节 7 项是**目标态**。逐项落地现状见 `docs/agent/selection-status.md` —— **勿以本清单判断「是否已实现」**】

- 分布式锁：Redisson —— ✅ 已落地（B2）
- 本地缓存：Caffeine —— ✅ 已落地（B1）
- 限流熔断：Resilience4j —— ⬜ 未落地（触发条件见 `selection-status.md §4`）
- 状态机：Spring StateMachine —— ⚠️ **未引库**：B3 以自研 `OrderStateMachine` 替代，与本条「能引库就不自研」背离，**待裁决**（`selection-status.md §5.1`）
- 指标：Micrometer + Actuator —— ✅ 已落地（C1）
- 日志：Logback JSON Encoder —— ⬜ 未落地（触发条件见 `selection-status.md §4`）
- 死锁重试：Spring Retry —— ⬜ 未落地（触发条件见 `selection-status.md §4`）

## 缓存与锁的强制规范

- 缓存击穿：逻辑过期 + Redisson 互斥重建 + Caffeine 本地兜底
- 缓存雪崩：随机 TTL + 多级缓存 + Resilience4j 限流
- 缓存穿透：空值缓存 + 布隆过滤器 + 参数校验
- 分布式锁：Redisson tryLock(waitTime, leaseTime) + finally 判断 isHeldByCurrentThread
- MySQL 死锁：固定加锁顺序 + 小事务 + @Version 乐观锁 + @Retryable
- 状态机：显式 transition + 乐观锁 + 流转日志 + 频次告警
- 线程池：分池 + 有界队列 + orTimeout + 明确拒绝策略

## 通用做法

把锁、缓存、状态机、重试、监控打点做成切面 + 注解，业务代码只加注解：

```java
@DistributedLock(key = "#orderId", waitTime = 3, leaseTime = 10)
@CacheProtect(key = "'order:' + #orderId", ttl = 60, jitter = 20)
@StateTransition(from = "CREATED", to = "PAID")
@Retryable(retryFor = OptimisticLockException.class, maxAttempts = 3)
```

## 开发要求

1. 先阅读 README、docs/agent/、现有接口和测试。
2. 只修改与本功能相关的文件，不要重构无关代码。
3. 小步提交，commit message 规则：
   - feat(backend): 新增xxx
   - feat(frontend): 新增xxx
   - feat(fullstack): 新增xxx
   - fix(backend|frontend|fullstack): 修复xxx
   - test(backend|frontend): 补充xxx
   - docs: 更新xxx
   - chore: 更新xxx
4. 不要删除旧接口、旧字段，保持向后兼容。
5. 数据库变更只允许追加（新增表 / 字段 / 索引，及「有条件允许」的 FK / 改列类型）—— 详见「数据库管理规则」3、4。
6. 一个功能如果同时改前后端，就在同一个 feature 分支里完成。
7. 新增监控、日志、管理端功能时，不得阻塞主业务线程。
8. 实时日志、指标推送优先使用 SSE 或 WebSocket，并设置超时与断线重连。
9. **临时产物不许落在仓库外**：脚本、日志、中间文件一律写项目根 `_work/`（已进 `.gitignore`），
   且路径写**绝对路径** —— 相对路径会跟着 shell 的 cwd 跑到桌面去（2026-10-09 就这么丢过两份 `_a3work/`）。
   可复跑的探针归 `scripts/probes/`；验收证据（截图、逐条输出原文）归 `docs/agent/evidence/<笔名>/`。

## 测试与构建

必须执行并通过：

- 后端：`cd Mall-management-server && mvn test`
- 前端：`cd Mall-management-web/mall-management-system && pnpm install --frozen-lockfile && pnpm build`
- 容器：`cd Mall-management-server && docker build -t mall-management-server:feature-<功能短名> .`

如果命令不存在，先说明并给出替代验证方式。

## 提交与 PR

1. 推送分支：

```bash
git push -u origin feature/<功能短名>
```

2. 发起 PR/MR：
   - 源分支：feature/<功能短名>
   - 目标分支：dev 或 main（默认先合 dev）
3. PR 描述必须包含：
   - 功能说明
   - 修改文件
   - 测试结果
   - 兼容性说明
   - 风险点
4. 不要自动合并到 main。合并必须等待人工 review 和确认。
5. 如果团队有 CI，确保 CI 通过。

## 完成输出

完成后只输出：

1. 分支名
2. commit 列表
3. 测试命令与结果
4. PR/MR 链接
5. 是否影响旧版本
6. 遗留风险或需要人工确认的点
