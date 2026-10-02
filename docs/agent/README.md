# docs/agent 文档地图

## 读文档的顺序

新会话开场只读：

1. agent.md（规则）
2. docs/agent/README.md（本文件）
3. 当前任务的 context-pack

## 文档职责

| 文件 | 职责 | 何时读 | 何时更新 |
|---|---|---|---|
| agent.md | 规则约束 | 每个会话开场 | 规则变化时 |
| project-map.md | 项目结构与现状 | 探索新功能时 | 结构变化时 |
| ddd-package-plan.md | DDD 包结构规划（**笔 1 已执行**；笔 2 Controller 拆分待做） | 涉及分包 / 迁移排期时 | 笔 2 完成后移入 archive/ |
| api-contract.md | 接口契约 | 改接口或做前端时 | 接口变化时 |
| db-schema.md | 表结构现状 | 涉及 DB 时 | 表结构变化时 |
| db-migration.md | 数据库变更记录 | 每次 DB 变更 | 每次变更时 |
| environment.md | 本地开发环境连接信息 | 需要连本地 MySQL / 跑服务时 | 环境变化时 |
| concurrency-map.md | 并发、锁、事务现状 | 涉及并发时 | 并发逻辑变化时 |
| cache-map.md | 缓存现状 | 涉及缓存时 | 缓存变化时 |
| defect-survey.md | 缺陷普查（七病 + 七前提 + Class A/B） | 排期时；做三层时的**输入** | 普查更新时 |
| selection-status.md | 技术选型现状（对照 `agent.md` 目标态逐项核） | 涉及选型 / 排期时 | 选型变化时 |
| tech-debt.md | 活跃技术债 + 已完成里程碑 | 排期时 | 新增/关闭时 |
| progress.md | **全项目进度表（唯一真相源）** —— 命名约定见其 §0 | 排期时、开工前 | 状态变化时 |
| business-decisions.md | 待决策议题 | 遇到分歧时 | 决策后 |
| context-packs/*.md | 任务上下文包 | 每个 feature 开场 | 该任务变化时 |
| archive/*.md | 归档内容 | 追溯历史时 | 关闭时移入 |

## 规则

1. 一个 feature 一个会话，做完 /clear。
2. 新会话开场只读：agent.md + README.md + 对应 context-pack。
3. 不要整目录加载。
4. 已完成的技术债移入 archive/。
