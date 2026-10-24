# 智能客服工具只读审计（2026-10-24）

> 口径：`@AgentTool` 启动扫描 + 真链 `GET /chat/tools`（按角色过滤）。
> 源码：`Mall-management-server/src/main/java/com/minke/mallmanagement/chat/service/ChatToolSet.java`
> 启动扫描原文：`registry-scan.txt`（2026-10-24 14:23:15，10 个方法 → 10 个工具）。
> 实链路清单：`tool-catalog-merchant.txt` / `tool-catalog-customer.txt`（2026-10-24 14:21 实测，角色 2 / 1 各 7 个）。

## 1. 全量清单（10 个，去重后）

| # | 工具 | 意图 | 可见角色 | 调用链（DAO 方法） | SQL 类型 | 行级条件 |
|---|---|---|---|---|---|---|
| 1 | `buyer_orders` | ORDER_QUERY | 1 | `OrderDao.countByCustomer` / `selectRecentByCustomer` / `selectByOrderIdAndCustomer` | SELECT | `WHERE CustomerId = ?` |
| 2 | `merchant_orders` | ORDER_QUERY | 2 | `OrderDao.countByMerchant` / `selectRecentByMerchant` / `selectByOrderIdAndMerchant` | SELECT | `WHERE MerchantId = ?` |
| 3 | `buyer_refunds` | REFUND_QUERY | 1 | `OrderRefundService.myRefunds` → `OrderRefundDao.selectMyRefunds` | SELECT | `WHERE CustomerId = ? AND RefundStatus <> 0` |
| 4 | `merchant_refunds` | REFUND_QUERY | 2 | `OrderRefundService.merchantRefunds` → `OrderRefundDao.selectMerchantRefunds` | SELECT | `WHERE MerchantId = ?` |
| 5 | `buyer_comments` | COMMENT_QUERY | 1 | `CommentDao.selectUsernameByUserId` / `countByAuthor` / `selectRecentByAuthor` | SELECT | 业务号→登录名后 `WHERE AuthorId = ?` |
| 6 | `merchant_comments` | COMMENT_QUERY | 2 | `CommentDao.countByMerchant` / `countUnansweredByMerchant` / `selectRecentByMerchant` | SELECT | `JOIN goods g WHERE g.MerchantId = ?` |
| 7 | `query_goods` | GOODS_QUERY | 全角色 | `GoodsDao.selectForChat` | SELECT | `WHERE DeletedAt IS NULL` + 关键词/编号匹配（公开商品，无身份条件） |
| 8 | `open_store` | OPEN_STORE | 1 / 2 / 3 | `IdentityResolver.resolve` → `BrandProfileDao.selectByMerchant`、`UserCacheService.publicInfo`（内联 SELECT）、`MerchantApplicationDao.selectLatestByUsernameAndType`、`UserGovernanceDao.selectMerchantId` | SELECT | `MerchantId = ?` / `customerid = ? OR merchantid = ?` / `Username = ?` |
| 9 | `seckill_signup` | ADD_TO_SECKILL | 1 / 2 | 同 `open_store`（只做身份分流与导航） | SELECT | 同上 |
| 10 | `navigate` | GUIDE_NAVIGATION | 全角色 | 普通页面**不查库**；目标为入驻/秒杀时复用 `open_store` / `seckill_signup` 的身份解析 | SELECT（或 0 条 SQL） | 同上 |

**非 SELECT 工具：0 个。** 10 个工具均为只读。

## 2. 写保护现状

- `ToolExecutor`（`chat/tool/ToolExecutor.java`）只做「结果 → 响应体」转换，**没有确认闸门、没有幂等键、没有 dry-run**。
- 当前没有写工具，所以该缺口尚未产生实际风险；一旦新增写工具，必须同时补：
  1. 显式确认（前端二次确认或会话内 `confirm=true`）；
  2. 幂等键（`traceId` 或客户端 requestId）；
  3. 失败可回滚/可重试语义。
- 角色过滤只减少误调用（`ToolRegistry.forRole`），**不是**数据隔离的最后防线；数据隔离落在 DAO 的行级条件上。

## 3. 工具失败分类（真链探针，2026-10-24）

数据源：
- `runs.json`（语义路由开启，29 次请求）
- `llm-path/runs.json`（语义路由关闭，29 次请求）
- `docs/agent/evidence/chat-tools/invoke-role-guard.txt`（历史负向用例）

| 类别 | 次数 | 说明 |
|---|---|---|
| 工具异常 / 网络超时 | 0 | 25 次工具调用（10 + 15）全部 `toolSuccess=true`；无 `ToolException`、无工具侧超时 |
| LLM 网络超时（非工具） | 16 / 21 | 仅出现在语义关闭配置；`llmFallback=llm_error:LlmException`，`degradeReason=llm_failed`，随后规则引擎兜底并成功调工具 |
| 业务空结果（正常响应） | 5 + 6 | 语义开：`merchant_refunds`×3、`buyer_refunds`×1、`merchant_comments`×1；语义关：另加 `query_goods`「未找到商品」×1 |
| 参数缺失（工具自兜底） | 2 | 语义关时 `query_goods` 无关键词 ⇒「请告诉我要查哪个商品」，`toolSuccess=true` |
| 参数错误（异常） | 0 | 无非法参数导致抛错 |
| 角色闸拒绝 | 1 | 历史负向用例：商家会话调用 `buyer_orders` ⇒ `{"msg":"工具 buyer_orders 不允许角色 2 调用","success":false}`，属预期 |
| 其他 | 0 | — |

> 结论：当前工具层没有观察到失败；「空结果」是业务事实（无售后/无评论/查无此商品），不应计入错误率。
> 唯一真实的失败面在 **LLM 意图识别超时**（语义关闭时才暴露），其降级路径本身是通的。
