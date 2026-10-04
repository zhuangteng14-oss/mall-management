# 业务待确认清单 — Mall-management

> 本文件收录**需要业务方裁决**的议题。文档作者不对业务语义代为裁决，只陈述事实与可选项。
> 相关文档：技术建议 → `tech-debt.md`；表结构事实 → `db-schema.md`；并发 → `concurrency-map.md`。

## BD-01 `order.SettlementDate` 语义

| 项 | 内容 |
|---|---|
| **议题** | `order.SettlementDate` 字段到底代表哪个业务时间点 |
| **影响接口** | `orderPayIfUp`（付款）、`orderDeliveryIfUp`（发货）、`orderReceiveIfUp`（收货）、`historicalOrdersSelect`（历史订单展示与排序） |
| **证据** | 写入：`OrderStateMachine:126` —— PAY / DELIVER / RECEIVE **三个动作共用同一条 UPDATE 尾巴** `SettlementDate = NOW()`（三行 javadoc 列于 `:113` / `:115` / `:117`）；读取：`OrderDao:149` / `:152`（`ORDER BY SettlementDate DESC`） |
| **现状** | 付款、发货、收货三处均写 `SettlementDate = NOW()`，**后发生者覆盖先发生者** |
| **实际后果** | 正常流转（付款→发货→收货）下最终值 = 收货时间；只付款未发货时 = 付款时间。该列**无法稳定表示任何一个业务时间点** |
| **读取范围** | ⚠️ **2026-10-02 订正**：原文写「三个查询**均未读取该列**」**为假** —— 四个查询**都读**。`orderSelect`（`OrderDao:116`）、`orderDeliveryIfSelect`（`:123`）、`orderDeliverySelect`（`:138` / `:141`）的列清单里都含 `SettlementDate`，只是**随行返回、不进 WHERE / ORDER BY**；`historicalOrdersSelect`（`:149`）另用它做 `ORDER BY SettlementDate DESC` |
| **可选方案 A** | **不改** —— 维持现状，将该列语义解释为「最后状态变更时间」 |
| **可选方案 B** | **只付款写** —— 发货、收货不再赋值该列；若业务需要那两个时间点则需另加字段 |
| **可选方案 C** | **拆三字段** —— 拆为 `PayTime` / `DeliveryTime` / `ReceiveTime` 三列 |
| **决策依据** | 该列当前**唯一被使用**的方式是「历史订单展示与排序」（`OrderDao:149` / `:152`）—— 其余三个查询只是把它随行返回，不参与条件与排序；需先确认业务上究竟要展示哪个时间点 |
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
| **状态** | ✅ **已裁决**（2026-09-30，`agent.md` 规则 v2）|
| **裁决** | **方案 B** ——「回滚 = 逻辑废弃 + 置 NULL」，不做物理删除。**由 `agent.md` 规则 v2 间接裁决**（v2 采纳 `tech-debt.md` TD-03「建议」第二条，非专门裁决本 BD-02）|

> 与 `tech-debt.md` TD-03 的区别：TD-03 记录的是**规则层面的技术债**（规则本身有缺陷），本议题是需要**规则制定方裁决**的决策。两者角度不同，同时保留。
> ✅ **2026-09-30 间接裁决**：规则 v2 采纳 TD-03「建议」第二条（逻辑废弃）⇒ 本 BD-02 随之落定；TD-03 关闭（改动落在 S1a-2）。

## BD-03 项目鉴权策略

| 项 | 内容 |
|---|---|
| 现状 | 全项目 32 个接口**仍零鉴权**（认证侧未落地）；⚠️ **「无拦截器」自 2026-10-04 起不成立**（已有登录闸 `shared/web/AuthInterceptor`，开关 `app.auth.enabled` **默认 `false`** ⇒ 对现网零影响），无 Spring Security |
| 问题 | 管理端需要「权限分级」时，必须先在服务端实现认证与授权 |
| 可选方案 | A. 拦截器 + Redis Session（半天）/ B. JWT + 拦截器（1 天）/ C. Spring Security + JWT（3 天） |
| 建议时机 | T2：业务功能完成后、管理端之前 |
| 建议方案 | A 或 B，不要 Spring Security |
| 决策人 | 项目负责人 |
| 状态 | 待确认 |
