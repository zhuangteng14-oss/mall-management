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

- 技术栈：【目标态】Spring Boot + Vue3 + MySQL + Redis
- 代码规模：约 3000 行，含本地数据库
- 核心关注：多线程、并发、异步、Redis 缓存、乐观锁、分布式锁、状态机
- 必须避免：缓存击穿、缓存雪崩、缓存穿透、分布式锁死锁、MySQL 死锁、状态机循环死锁、线程池死锁

## 全局硬性约束

1. 所有功能实现不能阻碍旧功能运作，必须保持向后兼容。
2. 禁止删除旧接口、旧字段、旧表、旧配置。
3. 数据库变更只允许新增表、新增字段、新增索引；禁止删列、改类型、删表。
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

## 数据库管理规则

1. mall_management_sql.sql 位于项目根目录，是数据库权威 Schema，已纳入 Git。
2. 禁止删除或修改 mall_management_sql.sql 里已有的表、字段、索引、约束。
3. 所有数据库变更只允许追加到 mall_management_sql.sql 末尾，格式：

```sql
-- ============================================================
-- Migration: <功能短名>  Date: YYYY-MM-DD
-- ============================================================
CREATE TABLE IF NOT EXISTS xxx (...);
ALTER TABLE xxx ADD COLUMN new_col VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_xxx ON xxx(col);
```

4. 禁止使用 DROP TABLE、DROP COLUMN、MODIFY COLUMN 改变类型。
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
2. 新会话开场只读三个文件：
   - agent.md
   - docs/agent/README.md
   - docs/agent/context-packs/<当前任务>.md
3. 不要整目录加载 docs/agent/。
4. 上下文接近满时（Claude 提示或响应变慢）主动建议 /clear。
5. 已完成的工作必须写入文件（文档或 commit），不能只存在于对话。
6. 每次新会话开场，先执行 git log --oneline -5 了解当前位置。

## 工具事实（已验证）

- `gh` **不在本工具 bash 的 PATH 中**（Windows 安装器只写入用户 PATH，本工具的 bash 进程未继承），直接调用会得到 `command not found`（退出码 127）。须用绝对路径：`"/c/Program Files/GitHub CLI/gh.exe"`。
- **`gh run list --commit <sha>` 会返回假阴性**：已用确知有运行的 `c4807d9` 做对照验证，同样返回空。查某个 commit 对应的 CI run，必须用 `gh run list --json headSha,...` 拉全量后本地比对，**不要用 `--commit` 过滤器**。
- 本仓库为私有仓库，匿名 `api.github.com` 请求返回 404；查 CI 状态一律走 `gh`。

## 技术选型（能引库就不自研）

【目标态，当前项目尚未引入】

- 分布式锁：Redisson
- 本地缓存：Caffeine
- 限流熔断：Resilience4j
- 状态机：Spring StateMachine
- 指标：Micrometer + Actuator
- 日志：Logback JSON Encoder
- 死锁重试：Spring Retry

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
5. 数据库变更只允许新增表、新增字段，不要直接删列改类型。
6. 一个功能如果同时改前后端，就在同一个 feature 分支里完成。
7. 新增监控、日志、管理端功能时，不得阻塞主业务线程。
8. 实时日志、指标推送优先使用 SSE 或 WebSocket，并设置超时与断线重连。

## 测试与构建

必须执行并通过：

- 后端：`cd Mall-management-server && mvn test`
- 前端：`cd Mall-management-web/mall-management-system && npm run build`
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
