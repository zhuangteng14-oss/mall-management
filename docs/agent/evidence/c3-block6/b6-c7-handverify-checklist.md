# 块 6 整改 · **手验清单**（供用户跑；agent 不代跑）

生成时间：2026-10-13
适用分支：`feat/c3-admin-block6`（PR #91，**保持 open / 不 merge**）
⚠️ **手验由用户执行并裁决**；本档只是「跑什么 / 看哪里 / 查哪张表」的清单。
agent 侧的**最小收验**输出另见 `b6-c7-rework-accept.txt`（可复核，不等于手验结论）。

**当前环境（agent 预跑态，可参照起）**
- 后端 a3 跑在 `:8088`（`SPRING_PROFILES_ACTIVE=a3`）——⚠️ **两道总闸在 agent 预跑时是手工放开的**，见 C 组；
- 前端 dev server 在 `:8080`；
- 种子数据：商品 `76`（GD-101-9552，演示评论挂它）· 商品 `80`（GD-105-F28C）。

---

## A. 用户端（HTTP 层，49 绿 + 15 有意红）

| # | 操作步骤 | 期望结果 | 判据（看哪 / 查哪） |
|---|---|---|---|
| A1 | 匿名打开商品详情页 → 「累计评价」区 | 能看到评价列表、星级分布、`N 张待审`提示 | 页面；`POST /comment/list` + `TargetType=goods&TargetId=76&Kind=review` 回 `success:true` |
| A2 | 看 `Id=114` 那条（匿名 5 星、含图） | 作者显示 `首字*****`、**图 1 张显「审核中」1 张正常**、`4 赞 2 踩` | `comment_image` 里 `Id=4 pending`（Url 为 null）/ `Id=5 approved`（有 Url）；`comment_like` 6 行 |
| A3 | 未登录点「评价」 | 按钮 disabled + tooltip | 页面 |
| A4 | 用**没买过**的账号评价 goods 76 | 拒：「购买并确认收货后才能评价」 | `countPurchasedGoods` ⇒ 查该账号 `order` 里该商品的 `ReceiveIf` |
| A5 | 用**买过**的账号对同一商品评第二次 | 拒：「**您已评价过该商品，不能重复评价**」 | `comment` 表 `COUNT(*) WHERE TargetId AND AuthorId AND ParentId IS NULL`；⚠️ **2026-10-13 起这层由 DB 唯一键兜底**，见 D1 |
| A6 | 发一条评价 → 立刻回列表 | **文字立刻可见**（不排队） | `comment.Status` 默认 `normal` |
| A7 | 正文里混入敏感词（占比 ≥40%） | 整条拒收 | `sensitive_word` 命中字符占比 |
| A8 | 同一账号写 `@某人` 回复 | 被 @ 的人能看到通知 | ⚠️ **@ 不是解析正文**，是按**结构**自动生成（顶层→@商家；回复顶层→@顶层作者；回复回复→@被回复人；自己 @ 自己跳过）⇒ 查 `comment_mention` + `notification.EventType='comment.mention'` |
| A9 | 商家回复一次该商品评论 | 该条 `IsMerchantFirst=1` → **置顶**（后续商家回复混排） | `comment.IsMerchantFirst`；列表 `ORDER BY c.ParentId, c.IsMerchantFirst DESC, c.created_at ASC` |
| A10 | 问答区提问 → 另一个账号回答 → 提问者点「采纳」 | 答案置顶、`IsSolved=1`、答主收到通知；**再点采纳 → 拒「该提问已采纳过答案，不能更改」** | `comment.AcceptedReplyId` / `IsSolved`；`notification.EventType='comment.accepted'` |
| A11 | 非提问者尝试采纳 | 拒：「只有提问者本人可以采纳答案」 | **空 cookie** 下测（带管理员 cookie 会被 `isAdmin` 短路） |
| A12 | 提问者自己删提问 | **拒绝**（提问不可自删） | 前端按钮 / 端点 |
| A13 | 举报一条别人的评论（理由「其他」不填说明） | 拒：「选择『其他』时必须填写说明」 | `report.Description` |
| A14 | 举报自己发的内容 | 拒：「不能举报自己发布的内容」 | — |

## B. 管理端（6 tab）

入口：`#/admin/business/moderation`。（管理员会话 cookie 需带 `role="3"`）

| # | tab | 操作 | 期望 | 判据 |
|---|---|---|---|---|
| B1 | **评论管理** | 选「对象类型=goods」+「对象 ID」→ 加载 → 删一条顶层评论 | 顶层删除**连带回复**（文案要写明） | `comment.Status='deleted'` 覆盖子行 |
| B2 | 问答管理 | 筛「未解决」→ 提删一条提问 | 提问进待裁；`IsSolved` 显示两种态 | `comment.Kind='question'` |
| B3 | **待审图片** | 三档切换 → 驳回一条 `pending` 图 | **两次点击**；回执 `fileDeleted`；盘上文件真消失；页面显示「图片违规已删除」 | `comment_image.Status='rejected'`；`%TEMP%\a3-uploads\comment\` 下文件消失（a3 的落盘目录**不是**仓库 `static/img/`） |
| B4 | **举报中心** | 处理 `report.Id=1`（通过） | 不可逆链：删内容 → `WarnCount+1` → 满 3 封号 → 通知双方 | `report.Status='handled'` + `HandlerId` + `HandleNote`；`user.WarnCount`；`notification.EventType='report.handled'` |
| B5 | 举报中心 | 驳回后再让同一个人举报同一条 | **可以**（`revive`，单子回 `pending`，`created_at` 重算） | `report.Status='pending'`、`HandlerId/HandleNote` 被清空 |
| B6 | **敏感词库** | 加一个已在库里的词 | 拒：「该敏感词已存在」 | `uk_sensitive_word` |
| B7 | 敏感词库 | 加一个历史评论里出现过的**新**词 | 回执 `masked=N`（`N>0` 表示真改写了历史评论）；`N=0` 是**正常结果**不是失败 | `comment.Content` 被就地改成 `***` |
| B8 | 敏感词库 | 删词 | 确认文案须含「已变成 `***` 的历史评论**不会还原**」 | 单向，无回填 |
| B9 | **消息管理** | 标一条已读 / 全部已读 | 走 `ToId='*'` 的管理端共享收件箱；`updated:0` 是正常结果（已标过） | `notification.ReadAt` |
| B10 | 任何一个 tab **冷进页面**（不填对象 ID） | **不弹红条**，停在灰底引导文案 | 收口笔修的那处 | 页面 |

## C. ⚠️ a3 下**验不了** / 只有别的 profile 才能验的

| 项 | 为什么 | 怎么才验得了 |
|---|---|---|
| 问答超时 Job（30 分钟） | a3 下**两道闸都关**：`mall.downgrade.job.enabled=false` 关掉 `@EnableScheduling` 的载体类 ⇒ **整台调度器不存在**；`mall.comment.question.timeout.enabled=false` 是第二层 | 起后端时**手工**加 `--mall.downgrade.job.enabled=true --mall.comment.question.timeout.enabled=true --mall.comment.question.timeout.fixed-delay-ms=15000` |
| 「必须登录本人」这类授权闸 | `app.auth.enabled=false` ⇒ `IdentityGuard` fail-open | 默认 profile（不设 `SPRING_PROFILES_ACTIVE`） |
| `/comment/list` 匿名 200（公开面） | 同上，a3 下所有端点都「匿名可达」 | 默认 profile |

---

## D. 本轮整改新增的 5 项（**另起一节，A/B/C 编号不动**）

> 2026-10-13 用户裁决：本节由 agent 追加，专覆盖本轮整改（唯一锁 / 审核记录 / 缺陷一 / 缺陷二）。
> ⚠️ **本节每一行都是「给你跑的操作步骤 + 期望结果 + 判据」，不是 agent 跑过的结论** —— agent 的预跑读数另在 `b6-c7-rework-accept.txt`，两者别混读。
> ⚠️ 本节在 a3 下**部分项也验不了**（授权面），逐条注明。

### D1 · 唯一锁豁免矩阵（「一商品一用户一条评价」的 DB 兜底）

背景：`comment` 加了 **STORED 生成列** `ReviewLockKey` + `UNIQUE KEY uk_comment_review_once`。
生成列 = `CASE WHEN TargetType='goods' AND Kind='review' AND ParentId IS NULL AND Status='normal' THEN CONCAT(TargetType,':',TargetId,':',AuthorId) ELSE NULL END`。

| # | 操作 | 期望 | 判据 |
|---|---|---|---|
| D1.1 | 同一账号对同一商品评**第二次**（顶层、`Kind=review`） | 拒：「您已评价过该商品，不能重复评价」 | `comment.ReviewLockKey` 相同 ⇒ `1062`；**不是**先查后插的业务预检 |
| D1.2 | 同一账号对同一商品**回复**两次 | **两次都成功**（回复不占锁） | 回复行 `ParentId != NULL` ⇒ `ReviewLockKey = NULL`（NULL 在唯一键下互不相等） |
| D1.3 | 同账号对同一商品的**提问 / 回答**各两次 | **都成功** | `Kind` 非 `review` ⇒ `ReviewLockKey = NULL` |
| D1.4 | 同账号在**留言板**（`TargetType='user'`）发两条 | **都成功** | `TargetType != 'goods'` ⇒ `ReviewLockKey = NULL` |
| D1.5 | 作者**删掉**自己的评价后再评一次 | **成功**（锁随 `Status='deleted'` 让开） | 生成列含 `Status='normal'` ⇒ 逻辑删后 `ReviewLockKey = NULL`，同键可再插 |
| 库侧自查 | 跑 `SELECT ReviewLockKey, COUNT(*) FROM comment WHERE ReviewLockKey IS NOT NULL GROUP BY ReviewLockKey HAVING COUNT(*) > 1;` | 返回 **0 行** | 有重复组 = 唯一键没兜住（或建索引前没清干净） |

⚠️ **背景（迁移时已做过的动作，不是你要跑的一步 —— 列在这里只为免你困惑）**：建唯一键之前清过一组历史重复（`goods` / `76` / 同一作者 = `Id=113` + `Id=114`），处置是**逻辑删 113、保留 114**。
⇒ 所以你在库里看 `Id=113` 会是 `Status='deleted'`（另有 `comment_mention.Id=12` 指着它 —— 该表在 Java 里**只写不读**，无悬挂影响）。这是**预期现象**，不是 D1 没生效。

### D2 · 并发（同刻两连接插同一把锁）

| # | 操作 | 期望 | 判据 |
|---|---|---|---|
| D2.1 | 两连接**同刻**插同一 `(goods, <id>, <author>)` 的顶层评价 | 成功 = **1**，被唯一键拒（`1062`）= **1**，落库行数 = **1** | DDL 级唯一键，无业务预检窗口 |
| D2.2 | 同上，但两连接插的是**不同账号** | 两条都成功 | 锁键含 `AuthorId` |

### D3 · 图片可见（缺陷一）

| # | 操作 | 期望 | 判据 |
|---|---|---|---|
| D3.1 | 管理端「待审图片」tab 列表 | **图能真显示**（不是 `naturalWidth=0` 的碎图） | 管理端列表返回的是**可直接用于 `<img src>` 的 URL** |
| D3.2 | 同一条评论的公开面（商品详情页） | **未过审的图 Url 仍为 `null`**（前端显「审核中」） | `POST /comment/list` 的返回里：`pending` 那条 `Images[].Url` 必须是 `null`，`approved` 那条**必须非 null** —— 两者**同时**对照才算判据 |

⚠️ D3.1 与 D3.2 必须**同时**成立：修管理端不能把公开面的「先审后发」破掉。

### D4 · 两个全站列表（缺陷二）

端点：`POST /comment/adminList`（第 **14** 条契约，2026-10-13 用户另批的**甲案**）。
⚠️ 它**不在** `AUTH_WHITELIST`；闸开着（默认 profile）匿名 **401**；a3 闸关下匿名可达 = 该 profile 下**每个**管理端端点的既定形态。

| # | 操作 | 期望 | 判据 |
|---|---|---|---|
| D4.1 | 管理端「**评论管理**」tab 冷进页面（不下钻） | 看到**全站**评论列表（不再要求先填对象 ID） | `Kind=review` 全站分页；`ORDER BY created_at DESC` |
| D4.2 | 管理端「**问答管理**」tab 冷进页面 | 看到**全站**问答列表，且超时未解决的行有标识 | `Kind=question`；后端下发 `IsTimeout`（同一出处 = `QuestionTimeoutJob.TIMEOUT_MINUTES`），前端**只渲染** |
| D4.3 | `Kind` 缺参 / 未知值 | 拒：「缺少参数：Kind（只能是 review / question / answer）」/「未知的评论类型：…」 | 端点 |
| D4.4 | 只给 `TargetType` 或只给 `TargetId` | 拒：「TargetType 与 TargetId 要么都给、要么都不给（都不给 = 全站）」 | 端点 |
| D4.5 | `Kind=review` 时带 `Solved` | 拒：「解决状态只对提问有意义（Kind=question），当前 Kind=review」 | 端点 |
| D4.6 | 公开面 `/comment/list` 不带 `TargetId` | 仍拒：「缺少参数：TargetId」（**公开面没被改语义**） | 端点 |

### D5 · 审核记录（复用 `admin_audit`，不新建表 / 不加列）

判据统一：管理端做一次动作后，`admin_audit` 多一行，且 `Action` / `TargetType` / `TargetId` / `ActorId` 对得上。
⚠️ `ActorId` = **会话身份**（`AuthSession.identify` 识别口径），**不是**入参 —— 请求里传什么账号都不影响它。

| # | 动作 | 期望的 `Action` | `TargetType` / `TargetId` |
|---|---|---|---|
| D5.1 | 加一个敏感词 | `sensitive_word.add` | `sensitive_word` / 词面本身 |
| D5.2 | 删一个敏感词 | `sensitive_word.delete` | `sensitive_word` / 词面本身 |
| D5.3 | 通过一张评论图 | `comment.image.approve` | `comment_image` / 图文件名 |
| D5.4 | 驳回一张评论图 | `comment.image.reject` | `comment_image` / 图文件名 |
| D5.5 | 处理一条举报（**通过**） | `report.handle` | `report` / 自增 `Id` |
| D5.6 | 处理一条举报（**驳回**） | `report.handle`（同一条 tag 覆盖两条路） | `report` / 自增 `Id` |
| D5.7 | 任何一条失败的动作（如 `sensitive/add` 重复词） | **不落记录** | 挂钩点在 `success == true` 之后 |

⚠️ **a3 下 D5.5/D5.6 需要管理员会话**：`/report/handle` 拒绝「无法确定处理人身份」（`AuthSession.identify` 为空）；
先用 `POST /user/loginSelect`（参数名 `username` / `password`，**小写**）拿 `role=3` 的 `JSESSIONID` 再调。

---

## E. 跑完请回

- 逐条「通过 / 不通过 + 判据出处」；
- C 组里哪几项**本轮没验**（a3 验不了的，请明确标注「未验」而不是「通过」）。
