# default profile 401 链路验证（2026-10-24）

## 目的

此前所有联调都在 `dev`（`app.auth.enabled=false`）下进行，匿名 401 链路从未在生产形态
（`default`，`app.auth.enabled=true`）验证过。本次只切 profile、不改代码，验证 4 条链路：

1. 匿名 `POST /chat/ask` → 401，响应体为 Result 形状；
2. 匿名点客服球发消息 → 前端显示「登录后才能查询」，不跳全局登录页；
3. 登录客户问「我的订单到哪了」→ 200 正常返回；
4. 登录商家问「怎么前往商家工作台」→ `targetPath=/merchant/user`。

## 环境与口径

- 后端：同一份工作区代码，`SPRING_PROFILES_ACTIVE=default`，端口 8088；
  启动日志 `The following 1 profile is active: "default"`，`app.auth.enabled=true`（白名单 15 条）。
- 前端：Vite dev server（8080），未做任何改动。
- 客户账号 `1111`，商家账号 `2222`；口令只从环境变量 `PROBE_CUSTOMER_PW` /
  `PROBE_MERCHANT_PW` 读取，未写进任何产物。
- **局限（必须知道）**：测试时 Redis 6379 未监听，`app.auth.token-store=redis` 的 Bearer
  路径（`/user/token`）没有被走到；三条登录链路用的是 `JSESSIONID` 会话 Cookie。
  即「default profile + 会话 Cookie」已验证，「default profile + Bearer」未验证。

## 结果

### ① 匿名 `POST /chat/ask` → 401（API 探针）

产物：`anonymous-401.txt`

```
HTTP 401
Content-Type: application/json;charset=UTF-8

{"success":false,"msg":"未登录"}
```

与 `AuthInterceptor.BODY_NOT_LOGGED_IN` 字面一致，`success=false` / `msg=未登录`，
符合 Result 形状。PASS。

### ② 匿名点客服球 → 「登录后才能查询」且不跳登录页（UI 探针）

产物：`anonymous-ui-report.txt`（7/7 PASS）、`anonymous-ui-401.png`

| 断言 | 结果 |
|---|---|
| 匿名首页出现客服球 | PASS（hash `#/customer`） |
| 点击客服球打开面板 | PASS |
| 匿名发送后拿到回复 | PASS |
| `/chat/ask` 真实返回 401 | PASS（`status=401`，非 mock） |
| 气泡显示「登录后才能查询」 | PASS |
| 不跳全局登录页 | PASS（前后 hash 均 `#/customer`，无登录表单） |
| 页面无未捕获异常 | PASS（`pageErrors=[]`） |

说明 `api/chat.js` 的 `allowUnauthorized: true` 生效：401 只抛 `UnauthorizedError`，
被 `ChatWidget.vue` 接住转成提示气泡，没有触发 `http.js` 的 `handleUnauthorized()` 全局跳转。

### ③ 登录客户「我的订单到哪了」→ 200

产物：`api-chains.json` 条目 `customer-order-query`

```
HTTP=200 intent=ORDER_QUERY wallMs=3044
reply: 你最近有 3708 笔订单，最新一笔是「Draft-B7-Demo（单号 BROR943184306407），
       实付 ¥155.84，下单时间 2026-10-20 21:18:47」。当前状态：已取消。
dataKeys: total, orders
```

订单数据为真实库数据，非 mock。PASS。注意 wall 3044ms —— 该句走了 LLM 兜底，
在 3s 超时边界之上（与本次尾延迟测量相关，见另一份报告）。

### ④ 登录商家「怎么前往商家工作台」→ `targetPath=/merchant/user`

产物：`api-chains.json` 条目 `merchant-workbench`

```
HTTP=200 intent=MERCHANT_DASHBOARD wallMs=17
action: {type: tour, targetPath: /merchant/user, targetLabel: 个人中心, label: 开始引导}
```

语义路由命中（17ms，未走 LLM），落点与本次裁决的 `/merchant/user` 一致。PASS。

## 汇总

| # | 链路 | 预期 | 实测 | 结论 |
|---|---|---|---|---|
| 1 | 匿名 `/chat/ask` | 401 + Result 形状 | 401 + `{"success":false,"msg":"未登录"}` | 符合 |
| 2 | 匿名 UI 发消息 | 提示 + 不跳登录页 | 提示出现，hash 不变，无异常 | 符合 |
| 3 | 客户订单查询 | 200 | 200 / ORDER_QUERY / 真实订单 | 符合 |
| 4 | 商家工作台 | `targetPath=/merchant/user` | `/merchant/user`（tour） | 符合 |

4 条全部符合预期，无差异需要裁决；未改任何代码。

## 验证后状态

- 已停掉 default 后端，重启 `dev` 后端（PID 103820），日志
  `The following 1 profile is active: "dev"`、`app.auth.enabled=false`，健康检查 200。
- 线上模型仍为 `glm-4-flash`，超时仍 3s，Prompt / 语义阈值 0.75 均未动。

## 复跑方式

```bash
# UI（需 8080 前端 + 8088 后端，default profile）
node scripts/probes/probe-chat-default-auth.mjs

# API（凭据走环境变量）
PROBE_CUSTOMER_PW=... PROBE_MERCHANT_PW=... \
  python scripts/probes/probe-chat-default-auth.py
```
