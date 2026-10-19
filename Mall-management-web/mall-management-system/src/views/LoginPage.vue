<template>
  <!-- A 版线框复刻（2026-10-19）：外壳换成 `_work/ui-wireframe/a.html` 的深色登录卡。
       ⚠️ 只换模板与 scoped 样式，脚本一字未动；所有既有真实逻辑（登录 / 注册 / 两态切换 /
       账号密码 / 角色选择 / 校验 / 错误提示 / redirect 落点 / 密码可见 / 预留位）全部保留。
       ⚠️ 登录态右侧的两张身份卡是**说明**（div，不是按钮）；注册态同一位置才是真实的
       `userType` 单选卡（标签 + 隐藏 radio），点哪张就选中哪张，没有新增假按钮。 -->
  <main class="auth">
    <div class="login-wrap">
      <article class="login-card">
        <!-- 深色卡头：kicker + 标题（A 版 `.login-head`） -->
        <header class="login-head">
          <div class="head-row">
            <div>
              <div class="kicker">SAME ACCOUNT / TWO IDENTITIES</div>
              <h1>登录演示账号</h1>
            </div>
            <router-link class="head-link" to="/customer">返回商城</router-link>
          </div>
        </header>

        <div class="login-body">
          <div class="login-main">
            <!-- ═══════════ 左：表单面（外壳固定，只换 .stage 里的内容）═══════════ -->
            <div class="form-col">
              <!-- Tab：登录 / 注册。两枚按钮都不 disabled；`isSlide && toggle()` 只防「点当前 Tab 反而跳走」。 -->
              <div class="ctabs">
                <button type="button" :class="{ on: !isSlide }" @click="isSlide && toggle()">登录</button>
                <button type="button" :class="{ on: isSlide }" @click="!isSlide && toggle()">注册</button>
              </div>

              <div class="stage">

                <!-- ── 一、登录 · 账号 ─────────────────────────────────────── -->
                <section class="page" data-page="login" :class="{ on: !isSlide && loginView === 'account' }">
                  <h1 class="t">欢迎回来</h1>
                  <p class="t-sub">用你在本站注册的账号登录，客户与商家共用这一个入口。</p>

                  <!-- 账号 / 手机号分段控件：手机号面是整块预留位，本按钮真切（既有 loginView 视图状态）。 -->
                  <div class="seg">
                    <button type="button" :class="{ on: loginView === 'account' }" @click="loginView = 'account'">账号登录</button>
                    <button type="button" :class="{ on: loginView === 'phone' }" @click="loginView = 'phone'">手机号登录</button>
                  </div>
                  <div class="f">
                    <label for="acc">账号</label>
                    <div class="ipt"><input id="acc" type="text" placeholder="用户名" v-model="loginAccount" @keyup.enter="!$event.isComposing && login()"></div>
                  </div>

                  <div class="f">
                    <label for="pwd">密码</label>
                    <div class="ipt has-btn">
                      <input id="pwd" :type="pwdShown ? 'text' : 'password'" placeholder="密码" v-model="loginPwd" @keyup.enter="!$event.isComposing && login()">
                      <!-- 密码可见切换：既有 `pwdShown` 视图状态，只切 type。 -->
                      <button class="reveal" type="button" @click="pwdShown = !pwdShown">{{ pwdShown ? '隐藏' : '显示' }}</button>
                    </div>
                  </div>
                  <!-- ① 图形验证码（占位 · N=3）：只挂 data-slot + title，不挂 disabled；块内两枚真控件 disabled。 -->
                  <div class="f" data-slot="预留 · 图形验证码（动现有代码 3 处）"
                       title="待 C3〈鉴权〉——无验证码端点、无图形生成服务：endpoints.js + AuthController.java + LoginPage.vue = 3 处">
                    <label for="cap">验证码</label>
                    <div class="caprow">
                      <div class="ipt"><input id="cap" type="text" placeholder="填入右侧字符" disabled></div>
                      <span class="capfig" aria-hidden="true">7K39</span>
                      <button class="again" type="button" disabled>换一张</button>
                    </div>
                  </div>

                  <!-- ② 记住我 / 忘记密码（各 N=3）：角标挂整个 .row，沿用既有预留位口径。 -->
                  <div class="row" data-slot="预留 · 记住我 / 忘记密码（各动现有代码 3 处）"
                       title="待 C3〈鉴权〉——「记住我」要一张长效凭据存 localStorage（当前只有 sessionStorage 会话票，api/auth.js）：AuthController.java + 签发逻辑 + LoginPage.vue；「忘记密码」另需端点 + 邮件/短信通道。各 3 处">
                    <label class="chk">
                      <input type="checkbox" disabled
                             title="待 C3〈鉴权〉——需要 localStorage 长效凭据（3 处）">记住我
                    </label>
                    <button class="dim" type="button" disabled
                            title="待 C3〈鉴权〉——无「找回密码」端点，也没有邮件/短信通道（3 处）">忘记密码？</button>
                  </div>

                  <div class="warn">{{ loginWarnText }}</div>
                  <button class="btn btn-primary btn-block" type="button" @click="login">登录</button>
                  <div class="swap">
                    <span>还没有账号？</span>
                    <button type="button" @click="!isSlide && toggle()">注册一个</button>
                  </div>
                </section>

                <!-- ── 二、登录 · 手机号（整块预留位 · N=4 超档）──────────────── -->
                <section class="page" data-page="phone" :class="{ on: !isSlide && loginView === 'phone' }">
                  <h1 class="t">欢迎回来</h1>
                  <p class="t-sub">用你在本站注册的账号登录，客户与商家共用这一个入口。</p>
                  <div class="seg">
                    <button type="button" :class="{ on: loginView === 'account' }" @click="loginView = 'account'">账号登录</button>
                    <button type="button" :class="{ on: loginView === 'phone' }" @click="loginView = 'phone'">手机号登录</button>
                  </div>

                  <div data-slot="预留 · 手机号登录（动现有代码 4 处 · 超档）"
                       title="待 C3〈鉴权〉——无「手机号 + 短信验证码」端点，也没有短信通道：endpoints.js + AuthController.java + mall_management_sql.sql + LoginPage.vue = 4 处。⚠️ `login` 表实测只有 (UserName, Password, Role, Id)，**没有手机号列** ⇒ 超档，因清单点名而画出（见 README 挂账表）。">
                    <div class="f">
                      <label for="ph">手机号</label>
                      <div class="ipt"><input id="ph" type="tel" placeholder="中国大陆 11 位手机号" disabled></div>
                    </div>

                    <div class="f">
                      <label for="sms">短信验证码</label>
                      <div class="caprow">
                        <div class="ipt"><input id="sms" type="text" placeholder="6 位数字" disabled></div>
                        <button class="btn btn-ghost btn-sm" type="button" disabled>获取验证码</button>
                      </div>
                    </div>

                    <div class="row">
                      <label class="chk"><input type="checkbox" disabled> 记住我</label>
                    </div>

                    <button class="btn btn-primary btn-block" type="button" disabled>登录</button>
                  </div>
                  <div class="swap">
                    <span>还没有账号？</span>
                    <button type="button" @click="!isSlide && toggle()">注册一个</button>
                  </div>
                </section>

                <!-- ── 三、注册（五项全部既有真功能）────────────────────────────
                     用户类型单选卡移到右侧身份栏：同一组 `v-model="userType"` / `:disabled="isUnder18"`
                     原样搬过去，仍是原有的选择与校验逻辑。 -->
                <section class="page" data-page="register" :class="{ on: isSlide && !showSuccess }">
                  <h1 class="t">创建账号</h1>
                  <p class="t-sub">填写下面各项即可开通。右侧选择的用户类型决定登录后进哪一侧。</p>
                  <div class="f">
                    <label for="uname">用户名</label>
                    <div class="ipt"><input id="uname" type="text" placeholder="登录时使用，注册后不可改" v-model="username" @keyup.enter="!$event.isComposing && register()"></div>
                    <div class="err">{{ err.username }}</div>
                  </div>

                  <div class="f">
                    <label for="bday">出生日期</label>
                    <div class="ipt"><input id="bday" type="date" v-model="birthDate" @change="calcAge"></div>
                    <div class="err">{{ err.birthDate }}</div>
                  </div>

                  <div class="f">
                    <label for="npwd">密码</label>
                    <div class="ipt has-btn">
                      <input id="npwd" :type="regPwdShown ? 'text' : 'password'" placeholder="至少 8 位，含字母与数字"
                             v-model="pwd" @input="checkPwdStrong" @keyup.enter="!$event.isComposing && register()">
                      <button class="reveal" type="button" @click="regPwdShown = !regPwdShown">{{ regPwdShown ? '隐藏' : '显示' }}</button>
                    </div>
                    <!-- 保留原行为：没输密码就不显示强度条（`v-if="pwd"`），长度值仍由 strongLevel 驱动。 -->
                    <div class="strength" v-if="pwd">
                      <i :class="{ on: strongLevel >= 1 }"></i><i :class="{ on: strongLevel >= 2 }"></i><i :class="{ on: strongLevel >= 3 }"></i>
                    </div>
                    <div class="err">{{ err.pwd }}</div>
                  </div>

                  <div class="f">
                    <label>性别</label>
                    <div class="radios">
                      <label><input type="radio" name="gender" value="男" v-model="gender"> 男</label>
                      <label><input type="radio" name="gender" value="女" v-model="gender"> 女</label>
                    </div>
                    <div class="err">{{ err.gender }}</div>
                  </div>

                  <button class="btn btn-primary btn-block" type="button" @click="register">注册</button>

                  <div class="swap">
                    <span>已经有账号？</span>
                    <button type="button" @click="isSlide && toggle()">去登录</button>
                  </div>
                </section>

                <!-- ── 四、注册成功 ─────────────────────────────────────────── -->
                <section class="page" data-page="success" :class="{ on: showSuccess }">
                  <div class="done">
                    <h1>账号已开通</h1>
                    <p>商城平台【{{ userType }}】账号注册成功。现在可以回到登录页，用刚才的用户名与密码进入。</p>
                    <button class="btn btn-primary btn-block" type="button" @click="goToLogin">返回登录</button>
                  </div>
                </section>

              </div>
            </div>

            <!-- ═══════════ 右：身份说明 / 注册时的真实角色选择 ═══════════ -->
            <aside class="identity" :class="{ 'identity--register': isSlide && !showSuccess }" aria-label="身份说明">
              <div class="kicker">TWO IDENTITIES / 两种身份</div>
              <p class="identity-intro">同一个账号承载买家与商家两种身份：登录后按账号实际角色进入对应中心，不需要注册第二个账号。</p>

              <!-- 注册态：真实的用户类型选择。label 包住隐藏 radio，点卡片任意处都会选中。 -->
              <template v-if="isSlide && !showSuccess">
                <div class="role-label"><span>用户类型</span><span>注册时选择</span></div>
                <div class="role-pick" role="radiogroup" aria-label="用户类型">
                  <label class="role-option" :class="{ on: userType === '客户' }">
                    <input class="sr-only" type="radio" name="userType" value="客户" v-model="userType">
                    <b>买家身份</b>
                    <span>浏览商品、下单与售后<br>与商家身份共用同一个账号</span>
                    <span class="btn btn-sm role-cta" :class="{ solid: userType === '客户' }">{{ userType === '客户' ? '已选择' : '选择买家' }}</span>
                  </label>
                  <label class="role-option" :class="{ on: userType === '商家', off: isUnder18 }">
                    <input class="sr-only" type="radio" name="userType" value="商家" v-model="userType" :disabled="isUnder18">
                    <b>商家身份</b>
                    <span>管理商品、订单与售后<br>在买家身份之上叠加，不丢购物车</span>
                    <span class="btn btn-sm role-cta" :class="{ solid: userType === '商家' }">{{ userType === '商家' ? '已选择' : '选择商家' }}</span>
                  </label>
                </div>
                <div class="err identity-err">{{ err.userType }}</div>
                <p class="tip" v-if="isUnder18">未满 18 周岁不可选择「商家」。</p>
              </template>

              <!-- 登录态 / 成功态：只作身份说明，不可点，也不放假按钮。 -->
              <template v-else>
                <div class="role-pick role-pick-static">
                  <div class="role-option role-static">
                    <b>买家身份</b>
                    <span>浏览商品、下单与售后<br>登录后按账号自动进入买家中心</span>
                    <span class="role-note">同一个账号</span>
                  </div>
                  <div class="role-option role-static">
                    <b>商家身份</b>
                    <span>管理商品、订单与售后<br>在买家身份之上叠加，购物车仍保留</span>
                    <span class="role-note">无需二次注册</span>
                  </div>
                </div>
              </template>
            </aside>

          </div>
        </div>

        <!-- 底部说明条（A 版 `.login-note`） -->
        <footer class="login-note">
          <span>买家身份始终保留；商家身份是买家身份之上的升级层，进入商家工作台不会丢失购物车与历史订单。</span>
          <span>登录与注册共用此入口。</span>
        </footer>

      </article>
    </div>
  </main>
</template>

<script>
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'
import { setMerchantId, setCustomerId, clearMerchantId, clearCustomerId, setUsername, setRole, setToken, publishSharedToken, restoreFromSession } from '../api/auth'   // 批四第二批 commit 3：`restoreFromSession` 让登录后立刻拿到 MemberLevel/Balance/IsBrand（否则本标签页要刷新一次才认会员）
export default {
  name: "LoginPage",
  data() {
    return {
      isSlide: !!(this.$route && this.$route.meta && this.$route.meta.register),   // ⚠️ C3 块 2〈用户块〉：**同行**把常量 `false` 换成「由路由 `meta` 定初值」（净零行数；本文件被 `c3-block-2-user.md` 按 `:161-223` / `:283-287` / `:289-330` / `:359-390` 引 ⇒ 一行都不能插）。真源 = `router/index.js` 文件尾那条 `addRoute({ path: '/register', …, meta: { public: true, register: true } })`：`#/register` 就是「落在这页的注册面」，刷新 / 分享 / 后退都停得住（两态回写 URL 那半在 `toggle()` 的**同行追加**里，别只做一半）。⚠️ 只在 `data()` 里读一次 ⇒ 仅供**初次挂载**（Vue 复用了组件实例，tab 内互切不再重跑 `data()`，正是所要）；`/login` 不写 `meta.register` ⇒ 得 `false`，与改动前逐字一致。
      // ── commit 12b（2026-10-07）：本笔新增 **3 个 UI 展示状态**，按 §口径 7 登记 ────────────
      // 该条口径（用户裁决）：D 笔边界「`script` 一字不动」的**目的 = 功能量恒为 0**
      //   —— 不动 API 调用 / 状态机 / `watch` / 轮询 / 业务逻辑。
      //   **UI 展示状态（tab / seg / 折叠 / 展开）不计入**，故不破「功能量恒为 0」。
      // ⚠️ 用户要求「破先例必须留痕」⇒ 三个字段逐个交代：
      //   · loginView   —— 登录面里「账号 / 手机号」两枚分段按钮的选中项。它**只是换视图**：
      //      手机号那一面整块是预留位（`data-slot`），切过去**不发任何请求**。
      //      （这也是它**不**采用 commit 13 那条「四枚按钮全 disabled + 面板依次全部渲染」先例的理由：
      //        手机号面是**另一套完整登录表单**，叠在账号面下面 = 两个登录表单并排 = **语义错误**；
      //        那条先例成立的前提是「四块都是可叠放的内容段落」。）
      //   · pwdShown    —— 登录密码框的「显示 / 隐藏」。只切 `type` 属性。
      //   · regPwdShown —— 注册密码框的同款。
      // ⚠️ 后两个是本笔**新画**的控件：稿把它记成「既有真功能」，实测**全仓没有**该实现
      //    （模板里有对应注释）。它们同样只切 `type`，不写回任何业务字段。
      // ⚠️ 三个字段**不被任何方法读取**，只被模板读写 ⇒ 把它们删掉，脚本行为一字不变。
      loginView: "account",
      pwdShown: false,
      regPwdShown: false,
      showSuccess: false,
      loginAccount: "",
      loginPwd: "",
      loginWarnText: "",
      username: "",
      userType: "",
      birthDate: "",
      pwd: "",
      gender: "",
      age: 0,
      isUnder18: false,
      strongLevel: 0,
      err: { username: "", userType: "", birthDate: "", pwd: "", gender: "" },
    };
  },
  methods: {
    toggle() {
      this.isSlide = !this.isSlide;
      this.loginWarnText = "";
      this.clearAllErr();   /* ⚠️ C3 块 2〈用户块〉**同行追加**（本文件被 `c3-block-2-user.md` 按 `:283-287` / `:289-330` 引 ⇒ 一行都不能插，故用块注释、语句也压在同一物理行）：把 tab 与 URL 绑回**同一处真源** —— `#/register` 要能刷新 / 分享 / 后退都停在注册面。⚠️ 只在本页那**两条**路由间 `replace`（`/login` / `/register`，两条都 `meta.public` 且无 `meta.roles` ⇒ 两条守卫都放行：`router/index.js:296` / `:332`）；用 `replace` 不用 `push` ⇒ 翻 tab 不往历史里灌垃圾。⚠️ 五个调用点（`:48`/`:49`/`:115`/`:157`/`:221`）都带 `!isSlide &&` / `isSlide &&` 守卫 ⇒ 每次调用后路径必变，**不会**撞 `NavigationDuplicated`。⚠️ 注册成功那处（`:452` **直赋** `this.isSlide = false`）不走本行 ⇒ URL 会停在 `/register` —— 已知、可接受（那一屏是「注册成功」提示面，刷新回到注册面反而更合理）。 */ if (this.$route && (this.$route.path === '/login' || this.$route.path === '/register')) this.$router.replace(this.isSlide ? '/register' : '/login')
    },

    async login() {
      this.loginWarnText = "";
      if (!this.loginAccount || !this.loginPwd) {
        this.loginWarnText = "请输入账号和密码";
        return;
      }
      try {
        const res = await http.post(endpoints.user.loginSelect, new URLSearchParams({
            username: this.loginAccount,
            password: this.loginPwd
          }));
        const role = await res.text(); if (role.trim() === "4") { this.loginWarnText = "账号已被封禁，请联系管理员解封"; return; }   // ⚠️ C3 块 2 用户块**手验后补丁**（2026-10-09，用户第 2 条）：封禁用户登录必须说「已封禁」，而不是落成「未识别到账户」。⚠️ **必须写在 `:306` 的 `/user/me` 之前**：封禁用户后端**不写会话** ⇒ 那一发必 401、先抛，异常被 `:328` 的通用文案接走；写在下面那条 `if/else` 链（`:317` 起）里是**死代码** —— `role === "0"` 那支今天就是这么死的（它同样在 `:306` 之后）。⚠️ 用 `role.trim()` 比对：`res.text()` 是**原始**响应体（A3 的断言也是 `body.trim()`）。同行追加，保净零行数（本文件被 `c3-block-2-user.md` 按 `:283-287` / `:289-330` 引）。

        // 登录成功后立即获取用户信息，保存商家ID / 客户ID
        // P2.6（2026-10-05）：改走**会话驱动**的 /user/me —— 凭据只在上一发 loginSelect 里出现，
        //   本发不再传 username/password。`allowUnauthorized`：role=0 时没有会话 ⇒ 这一发 401 也照抛，
        //   由下面那个 catch 兜成「未识别到账户」（与改前「200 空体 ⇒ res.json() 抛 SyntaxError」同归）。
        const userRes = await http.post(endpoints.user.me, null, { allowUnauthorized: true });
        const userData = await userRes.json();

        // 自动保存ID，上架商品可用。⚠️ 2026-10-08 用户第 3 条（多账户切换 403）：**每答一个业务号就清另一个**
        //   —— `user` 表两行的 id 互斥（商家行 `customerid` 为 NULL、客户行 `merchantid` 为 NULL）⇒ 旧代码
        //   「只写不清」会让上一位的号残留；买家 id 链优先 `customerid`（api/auth.js:123-125）⇒ 换回商家后，
        //   历史订单带着客户的 id + 商家的票 ⇒ IdentityGuard.requireSelfOrAdmin 判 403（实测 docs/美化/dtop4/acct.log）
        if (userData.merchantid) { setMerchantId(userData.merchantid); clearCustomerId(); }   // 商家：清上一位客户的 customerid
        if (userData.customerid) { setCustomerId(userData.customerid); clearMerchantId(); }   // 客户：清上一位商家的 merchantid
        // ⚠️ 清**写在这两个「有值」分支内**：role=0 / `me` 401 那一发 body 里没有 id ⇒ 两个分支都不跑 ⇒ 不会误伤「已登录又登一次、密码打错」的旧身份。

        if(role === "0"){   // ⚠️ **本支不可达**（2026-10-09 核实，非本笔引入）：它排在 `:306` 的 `/user/me` **之后**，而 `role === "0"` 时后端不写会话 ⇒ 那一发必 401、先抛 ⇒ 异常被 `:328` 的通用文案接走，永远轮不到这里。留着只为**不动行号**（本文件最大被引行是 `:687`）。封禁（`"4"`）那支已按同一理由提到 `:300` 去早退。
          this.loginWarnText = "未找到该用户，请查看对应账号密码有无写错，或者前往右侧进行注册";
        }else if(role === "1"){
          setUsername(this.loginAccount); setRole(role); await this.mintToken(role); restoreFromSession(userData);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md#api-5` 引的 `:216` 漂移）；P2.6 起 setCredentials → setUsername；多账户隔离笔（2026-10-06）再加铸票（见 mintToken）；批四第二批 commit 3：`restoreFromSession` 必须排在 `setUsername`/`setRole` **之后**（它们的 `dropRestored()` 会清掉提前贴的恢复态）⇒ 登录后立刻拿到 MemberLevel/Balance
          this.$router.push(this.landingAfterLogin(role));   // 前端路由守卫笔（2026-10-06）：落点改由 landingAfterLogin 决定（有合法 redirect 则回原目标，否则 = 原来的 "/customer"）
        }
        else if(role === "2"){
          setUsername(this.loginAccount); setRole(role); await this.mintToken(role); restoreFromSession(userData);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md#api-5` 引的 `:216` 漂移）；P2.6 起 setCredentials → setUsername；多账户隔离笔（2026-10-06）再加铸票（见 mintToken）；批四第二批 commit 3：同客户支，恢复态提供 MemberLevel/Balance/IsBrand
          this.$router.push(this.landingAfterLogin(role));   // 前端路由守卫笔（2026-10-06）：同上，否则 = 原来的 "/merchant"
        }  else if(role === "3"){ setUsername(this.loginAccount); setRole(role); await this.mintToken(role); this.$router.push(this.landingAfterLogin(role)); restoreFromSession(userData); } // 批四第二批 commit 3：贴在 `setUsername`/`setRole` **之后**（它们的 `dropRestored()` 会清掉提前贴的恢复态）；`userData` 只含 role/username ⇒ 管理端不会带出会员字段，无害。 // ✅ C3（2026-10-08，管理员）：⚠️ **整个分支贴在 `}` 尾巴上写完** —— 本文件最大被引行是 `:687`（全仓 15 条引用里最高的一条），`if/else` 链里在 `:326` 之后**插一行**就会让其后 360 行集体 +1、那条引用当场失效 ⇒ 沿用本链上方 `:320`/`:324` 既有的「同行追加保净零」手法。⚠️ `mintToken` 那一发今天**注定 401**（`/user/token` 明文禁止 cookie 管理员换票，是刻意的安全属性，见 slot-registry §十三 13-5）⇒ 被 `mintToken` 内部的 fail-open catch 吞掉，**不影响落点**；管理员身份今天只由 cookie 会话承载，骨架够用
      } catch (e) {
        this.loginWarnText = (e instanceof TypeError || e.name === 'TimeoutError') ? "连不上服务器：请确认后端已在 8088 运行、且本页源在 CORS 白名单内" : "未识别到账户，请检查对应输入";   // ⚠️ 结构调整（2026-10-07）：连不上（fetch 抛 `TypeError`，含 CORS 被拦 / 超时）**不再**与「账号对不上」同报 —— 原句把环境问题说成用户输错（用户实测「登不上」所见即此句）。判据只放这两类：`UnauthorizedError`（role=0 ⇒ `/user/me` 401，见 :304）**必须仍走原句**，它是「账号对不上」的正规路径。同行追加，保持净零行数。
      }
    },

    checkPwdStrong() {
      let pwd = this.pwd;
      let level = 0;
      if (/[0-9]/.test(pwd)) level = 1;
      if (/[a-zA-Z]/.test(pwd) && /[0-9]/.test(pwd)) level = 2;
      if (/[a-z]/.test(pwd) && /[A-Z]/.test(pwd) && /[0-9]/.test(pwd)) level = 3;
      this.strongLevel = level;
    },
    genUserId() {
      let str = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
      let res = "";
      for(let i=0;i<10;i++){
        res += str.charAt(Math.floor(Math.random()*str.length));
      }
      return res;
    },
    calcAge() {
      if (!this.birthDate) { this.isUnder18 = false; this.age=0; return; }
      const birth = new Date(this.birthDate);
      const now = new Date();
      let age = now.getFullYear() - birth.getFullYear();
      const m = now.getMonth() - birth.getMonth();
      if (m < 0 || (m === 0 && now.getDate() < birth.getDate())) age--;
      this.age = age;
      this.isUnder18 = age < 18;
      if (this.isUnder18) this.userType = "客户";
    },
    async register() {
      this.clearErr();
      let pass = true;
      if (!this.username) { this.err.username = "用户名未填写"; pass=false; }
      if (!this.userType) { this.err.userType = "用户类型未选择"; pass=false; }
      if (!this.birthDate) { this.err.birthDate = "出生日期未选择"; pass=false; }
      if (!this.pwd) { this.err.pwd = "密码未填写"; pass=false; }
      if (!this.gender) { this.err.gender = "性别未选择"; pass=false; }
      if (!pass) return;

      let userid = this.genUserId();
      try {
        const res = await http.post(endpoints.user.loginAdd, new URLSearchParams({
            username: this.username,
            usertype: this.userType,
            userid: userid,
            password: this.pwd,
            age: this.age,
            gender: this.gender
          }).toString());
        const text = await res.text();
        if (text === "添加成功！") {
          this.showSuccess = true;
        } else if (text === "0") {
          alert("已有该账户，无需重复注册");
        } else {
          alert("注册失败：" + text);
        }
      } catch (e) {
        alert("网络异常");
      }
    },

    // ── 多账户隔离笔（2026-10-06）：登录成功后铸一张**本标签页专属**的访问票 ──────────
    // 位置：只在 `role === "1" / "2"` 两个分支里被调用（**与 setRole 同行**追加，为的是保住本文件的净零行数）。
    //   ⚠️ role === "0"（登录失败）**不能**调它：那时没有会话 ⇒ `/user/token` 必 401。
    //   ⚠️ 这一发请求**必然带 cookie 会话**（上一发 `loginSelect` 刚建的）⇒ 后端按**授权口径**认出身份、
    //      把 userId / role 写进票。这正是「票绑 userId 不绑会话」能成立的前提（见 `auth.js` 末尾那节）。
    // 票的两个去向：
    //   · `setToken`      —— 本标签页的 `sessionStorage`（此后所有请求都由 `http.js` 自动带上）；
    //   · `publishSharedToken` —— `localStorage` 的「最近登录」指针，**让新标签页能认领一张票**。
    //     ⚠️ **只有 role ≠ 3 才发布**（用户裁决 D6）。本笔的登录路径只会是 "1"/"2"（管理员没有登录入口
    //        —— 归 C3 笔），判据仍先立在 `role !== "3"` 上，免得将来加管理员入口时**静默**发布出去。
    // ⚠️ **fail-open**：铸票失败**不阻断登录** —— 没票时这一页照旧靠 cookie 认身份（与改前同形），
    //    弹一条错误只会把「登录成功」变成一次报错。故这里**吞掉**异常，也不让 401 出口接管
    //    （`allowUnauthorized: true`：真 401 时静默收场，不跳转 —— 我们正要去 /customer）。
    async mintToken(role) {
      try {
        const res = await http.post(endpoints.user.token, null, { allowUnauthorized: true });
        const data = await res.json();
        if (data && data.token) {
          setToken(data.token);
          if (String(role) !== "3") publishSharedToken(data.token);
        }
      } catch (e) { /* fail-open：见上 */ }
    },
    // ── 前端路由守卫笔（2026-10-06）：登录后落点 —— 有**合法** `redirect` 则回原目标，否则角色首页 ──
    // 上游：`router/index.js` 末尾的守卫把未登录访问受保护页改成 `/login?redirect=<to.fullPath>`；
    //   `main.js:49` 的 401 出口同款（带出事那一页的 fullPath）。三处是**同一条契约**。
    // ⚠️ **无 query / 非法 query 时返回值 = 本笔之前的字面量**（"/customer" / "/merchant"）
    //   ⇒ 「回原目标」是**纯增量**，不改变任何既有登录路径的结果。
    //
    // 校验四条（缺一不可）：
    //   ① 必须是字符串 —— ⚠️ 同名 query 出现两次时 vue-router 给的是**数组**，不判类型会把它塞进 push；
    //   ② 必须以单斜杠开头、且不是 `//` 开头 —— 防开放重定向（`//evil.com` 是协议相对 URL）；
    //   ③ 不能是 `/login` 自身 —— 防自成回路；
    //   ④ `resolve()` 必须**匹配得到**记录（`matched.length`）—— 认不出的不猜（同 `LEGACY_TAB` 口径）。
    //
    // ⚠️ **角色校验不另抄一张表，而是复用守卫的 `meta`**：`this.$router.resolve(任意路径)` 返回的是
    //   **沿 `matched` 链合并后**的 `meta`（实测：三级嵌套也合并）⇒「这页现在这位用户能不能去」
    //   = `meta.public === true || 属于本角色壳`，与 `router/index.js` 的守卫**同一条规则**，
    //   不存在第二份真相。⚠️ **不能 import `router/index.js`**：它 `:3` 已 import 本组件 ⇒ 反向加边成环。
    //   ⚠️ 代价：角色首页表（下面的 `/customer` / `/merchant`）与 `router/index.js` 的 `ROLE_HOME`
    //   是**同构的第二份**；改一处要回头核另一处。⚠️ **按语（角色边界，2026-10-06，§5-AG (16)）**：角色**判定**已改为与守卫**同源**的 `meta.roles`（下面新增的 `roleAllowed`）；但**这张首页表**仍与 `ROLE_HOME` 各一份（**未合并** —— R6 裁决 D10 把它挂给了待定笔，本笔不碰）。
    landingAfterLogin(role) {
      const base = role === "3" ? "/admin" : role === "2" ? "/merchant" : "/customer";   // ✅ C3（2026-10-08）**已补**：原注「`role === "3"` 会落到 /customer —— 归 C3 一并补」当场兑现。⚠️ 本行是**同行改写**（不是插行）⇒ `:687` 及之前的所有被引行零漂移；`role === "1"` / `"2"` 的取值逐字未变
      const target = this.$route.query.redirect;
      if (typeof target !== "string" || !target.startsWith("/") || target.startsWith("//")) return base;
      if (target === "/login") return base;
      const r = this.$router.resolve(target);
      if (r.matched.length === 0) return base;
      if (r.meta && r.meta.public === true) return this.roleAllowed(r.meta, role) ? target : base;   // 公开面（如 `/product`）：**任何人**可去 —— 但「**壳属别家**」的公开面（两壳首页 / 搜索）**不算**（2026-10-06 角色边界，判据与守卫**同源**）
      return (target === base || target.startsWith(base + "/")) ? target : base;   // 受保护面：只回本角色壳
    },
    // ── 角色边界（2026-10-06，§5-AG (16)）：判据 = 路由记录上的 `meta.roles`（与守卫**同源**）──
    // ⚠️ **只被上面 `:278` 调用**：`:279`（受保护面）用的是 `base` 前缀判据，其结论已被 `meta.roles` 蕴含。
    // ⚠️ 无 `roles` 标记的记录（`/login`、`/product`、以及将来没标 meta 的页）⇒ **放行**（与守卫同一口径：缺省 = 不限制）。
    roleAllowed(meta, role) {
      if (!meta || !Array.isArray(meta.roles)) return true;
      return meta.roles.includes(String(role));
    },
    goToLogin() {
      this.showSuccess = false;
      this.isSlide = false;
      this.username = "";
      this.userType = "";
      this.birthDate = "";
      this.pwd = "";
      this.gender = "";
    },
    clearErr() {
      this.err = { username: "", userType: "", birthDate: "", pwd: "", gender: "" };
    },
    clearAllErr() {
      this.clearErr();
    }
  }, watch: { '$route'(to) { this.isSlide = !!(to.meta && to.meta.register) } }   /* ⚠️ C3 块 2〈用户块〉：**同行追加**在 `methods` 的收尾 `}` 之后（本文件被 `c3-block-2-user.md` 按 `:283-287` / `:289-330` / `:359-390` 引 ⇒ 一行都不能插）。【为什么 `data()` 里那次初值**不够**】vue-router 4 的 `<router-view>` **不给组件加 `key`** ⇒ `/login` ⇄ `/register` 是**两条记录、同一个 `LoginPage`**，Vue 会**复用实例**、`data()` **不再跑**。于是「页内点 tab」靠 `toggle()` 那条 `replace`（`:286`）就够，但「**地址栏把 `#/login` 改成 `#/register`**、或别处 `router.push('/register')`」这类**外部导航**不会重跑 `data()` ⇒ 停在登录面（2026-10-09 实测：`Page.navigate` 到 `#/register` 得 `loginPageOn:true`）。本 watch 补的就是这一支。【为什么是 `$route` 而不是 `isSlide`】只认路由这一件事 —— 反着监听 `isSlide` 会在 `toggle()` 里自己触发自己，绕圈。⚠️ 无 `immediate` ⇒ 首次挂载不触发，初值仍由 `data()` 定，两处**同一个判据**（`meta.register`）。⚠️ 它**不导航**（只赋标量）⇒ 不会与 `:286` 的 `replace` 相互触发。 */
};
</script>

<style scoped>
/* ══ A 版线框复刻（2026-10-19）：`_work/ui-wireframe/a.html` 的 `.login-wrap` / `.login-card` /
   `.login-head` / `.role-pick` / `.role-option` / `.login-note`。
   黑 / 白 / 灰三色系：深色卡头、细黑边、白底表单、方形控件；不再使用左品牌面。
   ⚠️ 所有表单控件仍是原来的高度（46px 输入框、可点区域不缩），只换外壳与间距。 */

/* 页面级排版：`tokens.css` 不设 body 字体，故本页自报 */
.auth {
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--bg);
  min-height: 100vh;
  -webkit-font-smoothing: antialiased;
}
button, input { font: inherit; color: inherit; }
button { background: none; border: 0; cursor: pointer; }
input::placeholder { color: var(--ink-3); }
a { color: inherit; text-decoration: none; }

/* 隐藏原生 radio，但保留可聚焦 / 可被 label 选中 */
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
           overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }

/* ── 居中登录卡（A 版 `.login-wrap` / `.login-card`）─────────────────────────── */
.login-wrap { min-height: 100vh; display: grid; place-items: center; padding: var(--s5); }
.login-card { width: min(960px, 100%); background: var(--surface); border: 1px solid var(--ink);
              display: flex; flex-direction: column; }

/* 深色卡头 */
.login-head { background: var(--ink); color: var(--bg); padding: var(--s4) var(--s5); }
.head-row { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s5); }
.kicker { font-family: var(--font-mono); font-size: 10px; letter-spacing: .12em;
          text-transform: uppercase; line-height: 1.2; }
.login-head .kicker { color: #c9c9c9; }
.login-head h1 { margin-top: var(--s1); font-family: var(--font-display);
                 font-size: 32px; font-weight: var(--fw-semi); line-height: 1.1;
                 letter-spacing: -.02em; }
.head-link { flex: none; padding: var(--s1) 0; font-size: var(--fs-xs); color: var(--line-2);
             border-bottom: 1px solid transparent; }
.head-link:hover { color: var(--bg); border-bottom-color: var(--bg); }

/* 卡身：表单在左，身份说明在右 */
.login-body { padding: var(--s5); }
.login-main { display: grid; grid-template-columns: minmax(0, 1.12fr) minmax(0, .88fr);
              gap: var(--s6); align-items: start; }
.form-col { min-width: 0; }

/* 登录 / 注册 Tab */
.ctabs { display: flex; gap: var(--s5); border-bottom: 1px solid var(--ink); }
.ctabs button { padding: var(--s2) 0 var(--s3); font-size: var(--fs-md); color: var(--ink-3);
                border-bottom: 2px solid transparent; margin-bottom: -1px; }
.ctabs button:hover { color: var(--ink); }
.ctabs button.on { color: var(--ink); font-weight: var(--fw-semi); border-bottom-color: var(--ink); }

.stage { margin-top: var(--s4); }
@keyframes fadein { from { opacity: 0 } to { opacity: 1 } }
.page { display: none; }
.page.on { display: block; animation: fadein .18s ease; }

h1.t { font-family: var(--font-display); font-size: 30px; font-weight: var(--fw-semi);
       line-height: 1.15; letter-spacing: -.02em; color: var(--ink); }
.t-sub { margin-top: var(--s2); font-size: var(--fs-sm); line-height: 1.7; color: var(--ink-3); }

/* 登录方式：账号 / 手机号（方形分段控件） */
.seg { display: inline-flex; margin-top: var(--s4); border: 1px solid var(--ink); }
.seg button { height: 30px; padding: 0 var(--s4); font-size: var(--fs-xs); color: var(--ink-3);
              background: var(--surface); }
.seg button + button { border-left: 1px solid var(--ink); }
.seg button.on { background: var(--ink); color: var(--bg); font-weight: var(--fw-med); }

/* 表单字段 */
.f { margin-top: var(--s3); }
.f > label { display: block; margin-bottom: var(--s1); font-size: var(--fs-xs); color: var(--ink-2); }
.ipt { position: relative; display: flex; align-items: center; }
.ipt > input { width: 100%; height: 46px; padding: 0 var(--s3);
               border: 1px solid var(--line-2); border-radius: 0; background: var(--surface);
               transition: border-color .15s, box-shadow .15s; }
.ipt > input:focus { outline: none; border-color: var(--ink); box-shadow: 0 0 0 3px var(--surface-3); }
.ipt.has-btn > input { padding-right: 64px; }
.reveal { position: absolute; right: 4px; height: 34px; padding: 0 var(--s2);
          font-size: var(--fs-xs); color: var(--ink-3); }
.reveal:hover { color: var(--ink); background: var(--surface-2); }

/* 验证码行：输入框 + 图形 + 换一张 */
.caprow { display: flex; gap: var(--s2); }
.caprow .ipt { flex: 1 1 auto; }
.capfig { flex: 0 0 88px; height: 46px; border: 1px solid var(--line-2);
          background: var(--surface-2); display: flex; align-items: center; justify-content: center;
          font-family: var(--font-mono); font-size: var(--fs-md); letter-spacing: .22em;
          color: var(--ink-3); user-select: none; }
.caprow .again { flex: 0 0 auto; height: 46px; padding: 0 var(--s3);
                 font-size: var(--fs-xs); color: var(--ink-3); }
.caprow .again:hover { color: var(--ink); }

.err { min-height: 16px; margin-top: var(--s1); font-size: var(--fs-xs); color: var(--danger); }

.row { display: flex; align-items: center; justify-content: space-between;
       margin: var(--s3) 0 0; font-size: var(--fs-xs); }
.chk { display: flex; align-items: center; gap: var(--s2); color: var(--ink-2); }
.chk input[disabled] { cursor: not-allowed; }
.row .dim { color: var(--ink-3); padding: var(--s1) 0; }
.row .dim:hover { color: var(--ink); }

/* 黑底 / 反白按钮（A 版 `.btn`） */
.btn { display: inline-flex; align-items: center; justify-content: center; gap: var(--s2);
       height: 46px; padding: 0 var(--s5); border: 1px solid var(--ink);
       background: var(--surface); color: var(--ink); font-weight: var(--fw-med);
       transition: background-color .12s linear, color .12s linear; }
.btn:hover { background: var(--ink); color: var(--bg); }
.btn-block { width: 100%; margin-top: var(--s4); }
.btn-primary { background: var(--ink); color: var(--bg); }
.btn-primary:hover { background: var(--surface); color: var(--ink); }
.btn-ghost { border-color: var(--line-2); color: var(--ink-2); }
.btn-ghost:hover { border-color: var(--ink); background: var(--ink); color: var(--bg); }
.btn[disabled] { opacity: .4; cursor: not-allowed; background: var(--surface-2);
                 border-color: var(--line-2); color: var(--ink-3); }
.btn-ghost[disabled] { background: none; border-style: dashed; }
.btn-sm { height: 30px; padding: 0 var(--s3); font-size: var(--fs-xs); }

.warn { min-height: 20px; margin-top: var(--s2); font-size: var(--fs-xs); color: var(--danger); }
.swap { margin-top: var(--s5); padding-top: var(--s4); border-top: 1px solid var(--line);
        display: flex; justify-content: space-between; font-size: var(--fs-xs); color: var(--ink-3); }
.swap button { color: var(--ink); font-weight: var(--fw-med); }
.swap button:hover { text-decoration: underline; }

/* 注册面专属 */
.radios { display: flex; gap: var(--s5); align-items: center; height: 46px; }
.radios label { display: flex; align-items: center; gap: var(--s2);
                font-size: var(--fs-sm); color: var(--ink-2); }
.strength { display: flex; gap: var(--s1); margin-top: var(--s2); }
.strength i { flex: 1; height: 3px; background: var(--line-2); }
.strength i.on { background: var(--ink); }
.tip { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--warning); }

/* 注册成功面 */
.done h1 { font-family: var(--font-display); font-size: 30px; font-weight: var(--fw-semi); line-height: 1.25; }
.done p { margin-top: var(--s3); font-size: var(--fs-sm); line-height: 1.85; color: var(--ink-2); }

/* ── 右侧身份栏（A 版 `.role-pick` / `.role-option`）──────────────────────── */
.identity { min-width: 0; border-left: 1px solid var(--line); padding-left: var(--s6); }
.identity .kicker { color: var(--ink-3); }
.identity-intro { margin-top: var(--s3); font-size: var(--fs-xs); line-height: 1.8; color: var(--ink-2); }

.role-label { display: flex; justify-content: space-between; gap: var(--s3);
              margin-top: var(--s5); font-size: var(--fs-xs); color: var(--ink-2); }
.role-label span:last-child { color: var(--ink-3); }

.role-pick { display: grid; grid-template-columns: 1fr; border: 1px solid var(--ink);
             margin-top: var(--s2); }
.role-option { position: relative; display: flex; flex-direction: column; gap: var(--s2); padding: var(--s4);
               border-bottom: 1px solid var(--line); text-align: left;
               background: var(--surface); cursor: pointer; }
.role-option:last-child { border-bottom: 0; }
.role-option:hover { background: var(--surface-2); }
.role-option b { font-family: var(--font-display); font-size: 22px; font-weight: var(--fw-semi);
                 line-height: 1.2; }
.role-option > span { font-size: 11px; color: var(--ink-3); line-height: 1.6; }
.role-option.on { background: var(--surface-2); }
.role-option.on b { text-decoration: underline; text-underline-offset: 4px; }
.role-option.off { opacity: .45; cursor: not-allowed; }
.role-option.off:hover { background: var(--surface); }
/* 隐藏 radio 仍可被键盘聚焦：把焦点画在整张卡上，而不是 1px 的隐藏元素上 */
.role-option:focus-within { outline: 2px solid var(--ink); outline-offset: -2px; }
/* 注册卡的 CTA：看起来是按钮，实际点击仍由外层 label 的 radio 选择承担 */
.role-option .btn { margin-top: var(--s2); align-self: flex-start; color: var(--ink);
                    background: var(--surface); }
.role-option .btn:hover { background: var(--ink); color: var(--bg); }
.role-option .btn.solid { background: var(--ink); color: var(--bg); }
.role-option .btn.solid:hover { background: var(--surface); color: var(--ink); }

/* 登录态的两张说明卡：不可点、无 CTA（不造假按钮） */
.role-pick-static { border-color: var(--line-2); }
.role-static { cursor: default; }
.role-static:hover { background: var(--surface); }
.role-note { margin-top: var(--s2); padding-top: var(--s2); border-top: 1px solid var(--line);
             font-family: var(--font-mono); font-size: 10px; letter-spacing: .08em;
             color: var(--ink-3); }
.identity-err { margin-top: var(--s1); }

/* 底部说明条 */
.login-note { display: flex; justify-content: space-between; gap: var(--s4);
              padding: var(--s3) var(--s5); border-top: 1px solid var(--ink);
              background: var(--surface-2); font-size: 10px; line-height: 1.6; color: var(--ink-3); }
.login-note span { max-width: 58em; }
.login-note span:last-child { flex: none; }

/* ── 响应式：窄屏把身份栏放到表单下方 ────────────────────────────────────── */
@media (max-width: 900px) {
  .login-wrap { padding: var(--s4) var(--s3); place-items: start center; }
  .login-body { padding: var(--s4); }
  .login-main { grid-template-columns: 1fr; gap: var(--s5); }
  .identity { border-left: 0; border-top: 1px solid var(--line);
              padding-left: 0; padding-top: var(--s4); }
  /* 注册态窄屏：把真实角色选择排在表单之前，避免「注册按钮先于用户类型」 */
  .identity--register { order: -1; border-top: 0; padding-top: 0; }
  .role-pick { grid-template-columns: 1fr 1fr; }
  .role-option { border-bottom: 0; border-right: 1px solid var(--line); }
  .role-option:last-child { border-right: 0; }
}

@media (max-width: 600px) {
  .login-head { padding: var(--s3) var(--s4); }
  .login-head h1 { font-size: 27px; }
  .head-row { gap: var(--s3); }
  .role-pick { grid-template-columns: 1fr; }
  .role-option { border-right: 0; border-bottom: 1px solid var(--line); }
  .role-option:last-child { border-bottom: 0; }
  .login-note { display: block; }
  .login-note span { display: block; }
  .login-note span:last-child { margin-top: var(--s1); }
}
</style>