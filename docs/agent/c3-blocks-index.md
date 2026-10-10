# C3 开发路线索引（8 块）

> 2026-10-08 用户裁决：C3 **按「业务对象块」切，不按端切**。
> 每块 = **表 + 后端 + 三端页面**，一次做完。
>
> **做 C3 任何一块之前：先读本文件 → 再读该块的 pack → 不要再整目录读 `docs/`。**
> pack 结构固定六段：一句话定位 / 依赖块 / 要读 / 要新建 / 验收 / 扩展阅读。

## 一、块表

| # | 块 | pack | 依赖 | 状态 |
|---|---|---|---|---|
| **1** | **基础设施**（权限与审计 + 系统通知） | [`context-packs/c3-block-1-infra.md`](context-packs/c3-block-1-infra.md) | —— | **已落**（批 A 审计 + I2 系统通知，PR #81 / #82 已 merge 进 main） |
| **2** | **用户块（三端）** | [`context-packs/c3-block-2-user.md`](context-packs/c3-block-2-user.md) | 1 | **已落**（PR #83 已 merge 进 main；提交 `a224840`；登记见 `slot-registry.md §十八`） |
| **3** | **商品块（三端）** | [`context-packs/c3-block-3-goods.md`](context-packs/c3-block-3-goods.md) | 2 | **已落**（PR #84 已 merge 进 main；提交 `5695755` / 合并 `fbf5c00`；A3 产物 `gates.count=30` / `gates.failed=0`、P3 表数 17）；登记见 `slot-registry.md §十九`（**§19-9 = 就地改造**）；方案见 `_work/c3-block3-landing-plan.md`（§十一/§十二） |
| 4 | 订单块（三端，**含购物车拆分**） | [`context-packs/c3-block-4-order.md`](context-packs/c3-block-4-order.md) | 3 | **A 档已裁（2026-10-10）→ 落码完成 + 构建/测试绿 + 两库已落（本机 14 表 / VM 18 表）+ 手验跑一轮（2026-10-10，回库核对；抓到并已修 `truncated` 谎报）；未提交**（A1 新建 `cart` / A2 `order` 加 `Quantity` / A3 只沿既有 CAS 边 + 补 3 端点归属校验）；登记见 `slot-registry.md §二十`、契约见 `api-contract.md 附录 C`；方案见 `_work/c3-block4-landing-plan.md`（`feat/c3-admin-block4`）。**＋补丁〈模拟支付 / 物流全流程〉（2026-10-10）**：`order` +7 列 + 新表 `order_shipping_track`（表数 18 → **19**；A3 门禁 `ORDER`/P3 **7→8 · 18→19**）、调度器**只 INSERT 轨迹不碰状态位**、新增 A5 `/order/orderDetail`、收银台 / 运费单号 / 物流轨迹三处 UI；`mvn test` 258 绿；登记 `slot-registry.md §20-9` / `api-contract.md 附录 D` / `tech-debt.md TD-28…31`；漂移账 `_work/drift-ledger-block4patch.md` |
| 5 | 营销块（三端） | `context-packs/c3-block-5-marketing.md`（待建） | 3 | 未开工 |
| 6 | 内容+消息块（三端） | `context-packs/c3-block-6-content.md`（待建） | 3 | 未开工 |
| 7 | 可观测域（管理端） | `context-packs/c3-block-7-observe.md`（待建） | 4 | 未开工 |
| 8 | 部署 | `context-packs/c3-block-8-deploy.md`（待建） | 全部（**收尾**） | 未开工 |

- 「**三端**」= 用户端 / 商家端 / 管理端；写「管理端」= 只有管理端有面。
- pack 路径命名 `c3-block-<N>-<slug>.md`，slug 见表。块 1 / 2 / 3 / 4 的 pack 已落地，其余**待建**
  （未建时上表路径是**约定**，不是可点链接 —— 这样 `check-context-packs.sh` 不会去追一个还不该存在的文件）。

## 二、依赖图

```
1 基础设施
   ↓
2 用户块
   ↓
3 商品块 ──┬──→ 4 订单块 ──→ 7 可观测域
           ├──→ 5 营销块
           └──→ 6 内容+消息块

8 部署（全部之后，收尾）
```

## 三、与 `c3-modules.md` 的关系

`c3-modules.md` 说「**做什么**」（11 项功能设计，**仍然有效**）；本文件说「**分几笔做**」（8 个交付块）。

两者**不是一一对应**：本表把「权限与审计」与「系统通知」合进块 1；
把可观测域里**不需要新表的 4 项**（攻击模拟器 / 指标 / 状态流转 / 实时日志）合进块 7。
`c3-modules.md` §七 的旧批次表**已作废**（见其 §十 与本文件 §四）。

## 四、口径

- **先规划、后干活**：块内开工前，先把该块 pack 的「要读 / 要新建」补全，且行号**实测**过。
- **表结构一次定，不来回改**：块内建表一次性把该块需要的列定齐（含 `agent.md` 数据库规则 8 的
  `created_at` / `updated_at`），定稿后再建。
- **每块三端一起设计、一起落**：块内**不允许**「先只做管理端，另两端后补」。
- **行号引用必须可校验**：pack 里的 `文件:行号` 由 `scripts/check-context-packs.sh` 校验；
  改了被引文件的行数，必须**同步重指** pack。
- **✅ 已裁决（2026-10-08，取代原先的「待裁决」）**：块 4 的「含购物车拆分」与 §八 已统一：
  后者改写为「购物车拆分属批 4 订单块，随该块一起做」—— 原「C3 后第一笔」口径**已废**。

## 五、校验

```bash
bash scripts/check-context-packs.sh
```

遍历 `context-packs/c3-block-*.md`，抓 `文件:行号` 引用，检查**文件存在** + **该行号指向非空内容**，
报告失效项（不改文件，只报告）。

> ⚠️ 它只查「**指得到**」，**不查「指的是不是那个东西」** —— 指到隔壁一行它照样报 OK。
> 语义对不对仍要人核（这条与 `checker-ok-means-only-what-it-checks` 是同一课）。
