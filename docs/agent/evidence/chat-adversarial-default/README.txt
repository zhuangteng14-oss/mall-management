default profile（鉴权开）· 对抗性压测复跑
日期：2026-10-24
后端：SPRING_PROFILES_ACTIVE=default，日志确认 The following 1 profile is active: "default"
      app.auth.enabled=true；端口 8088；后端 PID 109404。
前端：Vite 8080（/#/customer 等真实页面，未 mock）。
账号：买家 1111；商家 2222（口令只从环境变量 PROBE_MERCHANT_PW 传入，未落盘）。
方式：与 dev 复跑同一份 scripts/probes/probe-chat-adversarial.mjs；
      PROBE_OUT 指到本目录，逐条通过真实客服面板提交并截图。

结果：21/21 条 success=true；严重崩溃 0、体验极差 0、通过 21。
落点：C01/C02 jump→/customer/search；C04/C07→/customer/user/member；C10→/customer/orders；
      C11/C13→/customer/cart；C12→/customer/user/info；C14→/merchant/stats；
      C15→/merchant/user/list；C16→/merchant/user/apply。
写操作：adversarial-writes*.json 均为 []（买卖双方都没有代结算 / 代改地址 / 代删除）。
匿名链路：POST /chat/ask → 401 {"success":false,"msg":"未登录"}；
          匿名 UI 7/7 PASS，见 docs/agent/evidence/chat-default-auth/。

局限：本机 Redis 6379 未监听，default 的 app.auth.token-store=redis 未走到 Bearer 路径；
      登录链路验证的是 JSESSIONID 会话 Cookie。演示前端使用同一路径，已覆盖。
