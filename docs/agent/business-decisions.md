# 业务待确认清单 — Mall-management

> 本文件收录**需要业务方裁决**的议题。文档作者不对业务语义代为裁决，只陈述事实与可选项。
> 相关文档：技术建议 → `tech-debt.md`；表结构事实 → `db-schema.md`；并发 → `concurrency-map.md`。

## BD-01 `order.SettlementDate` 语义

| 项 | 内容 |
|---|---|
| **议题** | `order.SettlementDate` 字段到底代表哪个业务时间点 |
| **影响接口** | `orderPayIfUp`（付款）、`orderDeliveryIfUp`（发货）、`orderReceiveIfUp`（收货）、`historicalOrdersSelect`（历史订单展示与排序） |
| **证据** | `G:524` / `G:546` / `G:568`（三处写入）；`G:502` / `G:505`（唯一读取处与排序） |
| **现状** | 付款、发货、收货三处均写 `SettlementDate = NOW()`，**后发生者覆盖先发生者** |
| **实际后果** | 正常流转（付款→发货→收货）下最终值 = 收货时间；只付款未发货时 = 付款时间。该列**无法稳定表示任何一个业务时间点** |
| **读取范围** | `orderSelect`、`orderDeliveryIfSelect`、`orderDeliverySelect` 三个查询**均未读取该列**；仅 `historicalOrdersSelect` 读取，并按 `SettlementDate DESC` 排序 |
| **可选方案 A** | **不改** —— 维持现状，将该列语义解释为「最后状态变更时间」 |
| **可选方案 B** | **只付款写** —— 发货、收货不再赋值该列；若业务需要那两个时间点则需另加字段 |
| **可选方案 C** | **拆三字段** —— 拆为 `PayTime` / `DeliveryTime` / `ReceiveTime` 三列 |
| **决策依据** | 该列当前实际承担的是「历史订单展示与排序」（`G:502`、`G:505`），需先确认业务上究竟要展示哪个时间点 |
| **决策人** | 业务方 |
| **状态** | **待确认** |

> ⚠️ 变更该列会影响 `historicalOrdersSelect` 的排序结果，修改前必须回归验证历史订单页面。技术侧细节见 `db-schema.md` §4。

## BD-02 `agent.md` 回滚规则冲突

| 项 | 内容 |
|---|---|
| **议题** | `agent.md` 的回滚规则自相矛盾：要求每次变更登记回滚 SQL，同时又禁止 `DROP COLUMN` / `MODIFY COLUMN` |
| **影响** | 所有后续数据库变更 |
| **证据** | `agent.md` 数据库管理规则 5（要求登记回滚 SQL）与规则 4（禁止 `DROP COLUMN` / `MODIFY COLUMN`）；`db-migration.md` 第 28 行的临时折中方案 |
| **可选方案 A** | 允许为「新增字段」类变更编写 `DROP COLUMN` 回滚 SQL，但限制其只在人工确认后执行 |
| **可选方案 B** | 明确「回滚 = 逻辑废弃 + 置 NULL」，不做物理删除 |
| **可选方案 C** | 两者并行，按变更类型选择 |
| **决策人** | 规则制定方 |
| **状态** | **待确认** |

> 与 `tech-debt.md` TD-03 的区别：TD-03 记录的是**规则层面的技术债**（规则本身有缺陷），本议题是需要**规则制定方裁决**的决策。两者角度不同，同时保留。
