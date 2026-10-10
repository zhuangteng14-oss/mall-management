# C3 块 4〈购物车块〉手验证据（2026-10-10）

**环境**：后端 `8088`（`SPRING_PROFILES_ACTIVE=a3`，jdbcUrl=`192.168.229.129:3306/mall_management_sql`）·
前端 `8080`（`vue-cli-service serve`）· 驱动用仓内 CDP harness `docs/美化/_cdp.mjs`（headless Edge，**真后端、无 mock**）。

> ⚠️ **a3 闸关**：`application-a3.properties:98` 的 `app.auth.enabled=false` ⇒ `IdentityGuard` fail-open
> ⇒ **归属校验在本轮测不出**。**手验绿 ≠ 默认 profile 绿**。

| 文件 | 是什么 |
|---|---|
| `hand-verify.txt` | 手验清单逐项结果 + **回库核对**（不看接口 `success`） |
| `truncated-fix.txt` | 手验抓到的**唯一缺陷**（`truncated` 谎报）· 根因 · 修前/修后 + 屏幕复核 |
| `shots/t8-paid-before.png` | 修前：筛 `paid` 屏上**谎报**「已被上限截断」 |
| `shots/t8-paid-after.png` | 修后：筛 `paid` **无**截断提示 |
| `shots/t9-limit1.png` | 修后：`limit=1`（**真**达上限）提示仍在 |

一次性探针脚本（`t0`…`t9`）留在 `_work/b4/`，**不入库**（同 `_dbg*` 口径：临时产物）。
