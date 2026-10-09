<template>
  <!-- D 笔 commit 12b（2026-10-07）：按 `docs/美化/pages/LoginPage.html`（B 稿 v5/v6.1）重排层级。
       结构 =「外壳固定 + 只换 .stage」：左 = 品牌面（釉面整块），右 = 表单面（白底 + 线）。
       两态的**上/左边缘、宽度、Tab 位置逐像素相同**，只有 Tab 以下的块在换
       —— 这是稿 v4 的主修项，治的是用户原话「登录 = 整页平铺，注册 = 水平滑动切换，
       同一路由两态，容器形态不一致，看起来像两个不同的页」。

       ⚠️ 与原实现相比**被替换掉的**界面手法：`.login-box` 的左右滑动 + `.img-section`
          `.slide-btn` 那套「sign in / sign up」翻转件，以及 `v-show` 覆盖式 `.success-cover`。
          宿主选择由 `.ctabs`（登录 / 注册）承担，成功面改成一个正常的 `.page`。
       ⚠️ 脚本段未做功能性改动：只按「§口径 7」新增 **3 个 UI 展示状态**（`loginView` /
          `pwdShown` / `regPwdShown`），不碰 API / 状态机 / watch / 轮询 / 业务逻辑。 -->
  <main class="auth">

    <!-- ═══════════ 左：品牌面（logo + 品牌语 + 大图）═══════════ -->
    <aside class="brand">
      <div class="brand-mark">商城</div>

      <h2 class="brand-claim">让挑选与交付，<br>各归其位。</h2>
      <p class="brand-sub">一个账户同时管着买家与卖家两侧：浏览、下单、发货、对账，都在同一处。</p>

      <!-- 稿用 `body[data-state="register"]` 换图；scoped 样式**够不到 `body`** ⇒ 改挂在本元素上。
           判据与 `.ctabs` 高亮**同一个**：登录态（含手机号）用 Mall-Section-2、
           注册态（含成功面）用 Mall-Section-1 —— 而这两档恰好就是 `isSlide` 的两值。 -->
      <div class="brand-fig" role="img" aria-label="商城场景插画" :class="{ reg: isSlide }"></div>

      <dl class="facts">
        <div><dt>商品</dt><dd>目录、库存、上下架</dd></div>
        <div><dt>订单</dt><dd>从下单到收货，一条链</dd></div>
        <div><dt>消息</dt><dd>买家与卖家直接联系</dd></div>
      </dl>
    </aside>

    <!-- ═══════════ 右：表单面（**外壳固定**，只换 .stage 里的内容）═══════════ -->
    <section class="pane">
      <nav class="pane-top">
        <router-link to="/customer">返回商城</router-link>
        <!-- ⚠️ 结构二次调整（2026-10-07）：原「用户中心」(`/customer`) 已删 —— 未登录用户不需要它，且落点与左项重复 -->
      </nav>

      <div class="form-shell">
        <!-- Tab：登录 / 注册 —— 与外壳一起固定，切态时不动。
             ⚠️ 两枚按钮都**不 disabled**：它们恰好对上既有的 `isSlide`
             （稿的 `data-tab` 切换是稿自己的演示脚本，本页用既有状态实现，**没有**新增状态）。
             `isSlide && toggle()` 的写法 = 「已经在这一态就不要再翻」——`toggle()` 是**翻转**语义，
             直接绑会给「点当前 Tab 反而跳走」的反效果。 -->
        <div class="ctabs">
          <button type="button" :class="{ on: !isSlide }" @click="isSlide && toggle()">登录</button>
          <button type="button" :class="{ on: isSlide }" @click="!isSlide && toggle()">注册</button>
        </div>

        <div class="stage">

          <!-- ── 一、登录 · 账号 ─────────────────────────────────────── -->
          <section class="page" data-page="login" :class="{ on: !isSlide && loginView === 'account' }">
            <h1 class="t">欢迎回来</h1>
            <p class="t-sub">用你在本站注册的账号登录，客户与商家共用这一个入口。</p>

            <!-- 账号 / 手机号 分段控件。⚠️ 手机号那一面是**整块预留位**（见下面第二个 `.page`），
                 本枚按钮**真切**（§口径 7：seg 属视图状态，不新增功能）。 -->
            <div class="seg">
              <button type="button" :class="{ on: loginView === 'account' }" @click="loginView = 'account'">账号登录</button>
              <button type="button" :class="{ on: loginView === 'phone' }" @click="loginView = 'phone'">手机号登录</button>
            </div>

            <div class="f">
              <label for="acc">账号</label>
              <div class="ipt"><input id="acc" type="text" placeholder="用户名" v-model="loginAccount"></div>
            </div>

            <div class="f">
              <label for="pwd">密码</label>
              <div class="ipt has-btn">
                <input id="pwd" :type="pwdShown ? 'text' : 'password'" placeholder="密码" v-model="loginPwd">
                <!-- 密码可见切换（稿的平台清单第 5 条）。
                     ⚠️ **稿把那一条记为「既有真功能」是误记**：实测全仓（`src/` 全量 grep
                     `reveal` / `showPwd` / `type === 'password'`）**没有任何密码可见实现**，
                     改前这一页的两枚密码框都是裸 `<input type="password">`。
                     ⇒ 本笔按「§口径 7」把它落成**视图状态**（`pwdShown` / `regPwdShown`），
                     仅切换 `type` 属性，不涉 API 与逻辑。详见提交信息与台账。 -->
                <button class="reveal" type="button" @click="pwdShown = !pwdShown">{{ pwdShown ? '隐藏' : '显示' }}</button>
              </div>
            </div>

            <!-- ① 图形验证码（占位 · N=3）。整块是 `<div class="f">` ⇒ **块级**，
                 按口径只挂 `data-slot` + `title`，**不挂 `disabled`**；块内两枚真控件照稿 `disabled`。 -->
            <div class="f" data-slot="预留 · 图形验证码（动现有代码 3 处）"
                 title="待 C3〈鉴权〉——无验证码端点、无图形生成服务：endpoints.js + AuthController.java + LoginPage.vue = 3 处">
              <label for="cap">验证码</label>
              <div class="caprow">
                <div class="ipt"><input id="cap" type="text" placeholder="填入右侧字符" disabled></div>
                <span class="capfig" aria-hidden="true">7K39</span>
                <button class="again" type="button" disabled>换一张</button>
              </div>
            </div>

            <!-- ② 记住我 / 忘记密码（各 N=3）。⚠️ 角标挂**整个 `.row`**、不分别挂两枚控件：
                 它们各自只有 90 / 72px 宽而角标要 230px，分挂会在中间叠成一团（稿的 1440 截图抓到）。
                 这正是 README「角标只挂区块级」那条纪律。 -->
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

          <!-- ── 二、登录 · 手机号（**整块预留位** · N=4 **超档**）────────────────
               ⚠️ 这一面在实现里**真的能切进来**（`.seg` 那枚按钮真切）—— 按「§口径 7」，
               新增 `loginView` 属视图状态。**不叠放**：它是另一套完整登录表单，
               叠在账号面下面 = 两个登录表单并排 = 语义错误（这正是 commit 13 那条先例
               「四个可叠放内容段落」**不适用**于本页的理由）。 -->
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

          <!-- ── 三、注册（**五项全部既有真功能**，一件都不标 slot）──────────────── -->
          <section class="page" data-page="register" :class="{ on: isSlide && !showSuccess }">
            <h1 class="t">创建账号</h1>
            <p class="t-sub">填写下面五项即可开通。用户类型决定登录后进哪一侧。</p>

            <div class="f">
              <label for="uname">用户名</label>
              <div class="ipt"><input id="uname" type="text" placeholder="登录时使用，注册后不可改" v-model="username"></div>
              <div class="err">{{ err.username }}</div>
            </div>

            <div class="f">
              <label>用户类型</label>
              <div class="radios">
                <label><input type="radio" name="userType" value="客户" v-model="userType"> 客户</label>
                <label><input type="radio" name="userType" value="商家" v-model="userType" :disabled="isUnder18"> 商家</label>
              </div>
              <div class="err">{{ err.userType }}</div>
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
                       v-model="pwd" @input="checkPwdStrong">
                <button class="reveal" type="button" @click="regPwdShown = !regPwdShown">{{ regPwdShown ? '隐藏' : '显示' }}</button>
              </div>
              <!-- ⚠️ 与稿**有意不同**：稿的三段强度条是常显（其中两段 `class="on"` 是稿的演示值），
                   本页保留原实现「没输密码就不显示强度条」的行为（`v-if="pwd"`）——
                   「功能量恒为 0」⇒ 不把「什么时候出现」也改掉。长度值照旧由 `strongLevel` 驱动。 -->
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

            <!-- ⚠️ 与稿**有意不同**（第 2 处）：稿给这枚按钮挂了 `title="本稿只演示跳转；真实注册走
                 endpoints.user.loginAdd（LoginPage.vue:209）"`。按「口径 4」自引行号那半已剥，
                 但剩下那半（「本稿只演示跳转」）说的是**稿自己**的演示行为 ——
                 本页这枚按钮**真的调 `register()`**，照挂就是在页面上写一句假话 ⇒ **整条不落**。 -->
            <button class="btn btn-primary btn-block" type="button" @click="register">注册</button>
            <p class="tip" v-if="isUnder18">未满 18 周岁不可选择「商家」。</p>

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
    </section>
  </main>
</template>

<script>
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'
import { setMerchantId, setCustomerId, clearMerchantId, clearCustomerId, setUsername, setRole, setToken, publishSharedToken } from '../api/auth'
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
        const role = await res.text();

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

        if(role === "0"){
          this.loginWarnText = "未找到该用户，请查看对应账号密码有无写错，或者前往右侧进行注册";
        }else if(role === "1"){
          setUsername(this.loginAccount); setRole(role); await this.mintToken(role);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md:172` 引的 `:216` 漂移）；P2.6 起 setCredentials → setUsername；多账户隔离笔（2026-10-06）再加铸票（见 mintToken）
          this.$router.push(this.landingAfterLogin(role));   // 前端路由守卫笔（2026-10-06）：落点改由 landingAfterLogin 决定（有合法 redirect 则回原目标，否则 = 原来的 "/customer"）
        }
        else if(role === "2"){
          setUsername(this.loginAccount); setRole(role); await this.mintToken(role);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md:172` 引的 `:216` 漂移）；P2.6 起 setCredentials → setUsername；多账户隔离笔（2026-10-06）再加铸票（见 mintToken）
          this.$router.push(this.landingAfterLogin(role));   // 前端路由守卫笔（2026-10-06）：同上，否则 = 原来的 "/merchant"
        }  else if(role === "3"){ setUsername(this.loginAccount); setRole(role); await this.mintToken(role); this.$router.push(this.landingAfterLogin(role)); }   // ✅ C3（2026-10-08，管理员）：⚠️ **整个分支贴在 `}` 尾巴上写完** —— 本文件最大被引行是 `:687`（全仓 15 条引用里最高的一条），`if/else` 链里在 `:326` 之后**插一行**就会让其后 360 行集体 +1、那条引用当场失效 ⇒ 沿用本链上方 `:320`/`:324` 既有的「同行追加保净零」手法。⚠️ `mintToken` 那一发今天**注定 401**（`/user/token` 明文禁止 cookie 管理员换票，是刻意的安全属性，见 slot-registry §十三 13-5）⇒ 被 `mintToken` 内部的 fail-open catch 吞掉，**不影响落点**；管理员身份今天只由 cookie 会话承载，骨架够用
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
/* ══ D 笔 commit 12b（2026-10-07）：整块按 `docs/美化/pages/LoginPage.html`（B 稿 v5 / v6.1）重写 ══
   本块**替代**了原来的 33 条全局选择器（那批的逐条处置见 `slot-registry.md` §一 `commit 12a`；
   本笔整块重写后，`12a` 提到的那三条规则与 `.login-page` / `.success-cover` 等类名**都已不存在**）。

   与稿的三处**必要差异**（不照抄稿的地方，逐条写在这里）：
   1) 稿的 `body{...}` / `:root{--d1,--d2}` 两条在本块里**上移到 `.auth`**：
      scoped 样式会给选择器补 `[data-v-…]`，而 `body` / `:root` 上**没有**这个属性
      ⇒ 照抄就是**两条静默失效**的规则（`--d1` / `--d2` 失效的后果是字号回落，
      eslint / build / 模板扫**都报不出来** —— 这条坑 `ProductPage.vue` 已应验过一次）。
   2) 稿的 `body[data-state="register"]` 换图 ⇒ 改成挂在本组件的 `.brand-fig.reg` 上
      （scoped 够不到 `body`）；判据不变，仍是 `isSlide` 那两档。
   3) 稿里 `.brand-fig` 的图用**稿相对路径**指向后端 `static/img`，本页沿用改前既有的写法
      `http://localhost:8088/img/...`（改前 `.img-section` 就是这么引的，本笔不改这个约定）。
   ⚠️ 行号影响：模板与样式**整体重写** ⇒ 指向本文件的「文件:行号」互引大面积漂移。
      按「锚点漂移只报告」口径，本笔出报告不改别人；逐条修归 commit 14。 */

/* 页面级排版档（稿的 `:root{--d1,--d2}` 搬到这里，理由见上） */
.auth {
  --d1: calc(var(--fs-2xl) * 1.31);   /* 页主视觉 ≈47px */
  --d2: calc(var(--fs-2xl) * 1.00);   /* 次级标题 ≈36px */

  /* 稿的 `body{...}` 搬到这里：`tokens.css` **刻意不设** `body` 的 font/color/background
     （见该文件头注），所以本页自报。 */
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--bg);
  -webkit-font-smoothing: antialiased;
  /* 稿是 `calc(100vh - 46px)`，减的 46px 是**稿自己的预览条**；本页没有那一条 ⇒ 100vh。⚠️ **`min-height` → `height`（第 3 条修复，2026-10-08）**：`min-height` 只是下限，grid 那唯一的 auto 行仍按**内容**定高（= max(左栏 791, 右栏 752)）⇒ 视口比它矮时行高不缩、整页可滑（实测 1366×768 滑 21px / 1280×720 滑 69px）。改成定高 + 下面的 `grid-template-rows: minmax(0,1fr)` 才让行 = 视口，左栏那张图才有可压的余量。 */
  height: 100vh;
}
/* 控件一律继承页面字体 —— 浏览器 UA 样式表会给 `button` / `input` 打上自己的 `font`。
   落法同 `ProductPage.vue` / `GoodsAdd.vue` / `GoodsUp.vue` 三处先例。 */
button, input { font: inherit; color: inherit; }
button { background: none; border: 0; cursor: pointer; }
input::placeholder { color: var(--ink-3); }
/* 全仓**没有**全局 `a` 规则 ⇒ `<router-link>` 渲染出的 `<a>` 默认带下划线，必须自报 */
a { color: inherit; text-decoration: none; }

/* ── 分栏：左品牌 46 / 右表单 54 ──────────────────────────────────────────── */
.auth { display: grid; grid-template-columns: minmax(0, 46fr) minmax(0, 54fr); grid-template-rows: minmax(0, 1fr); }   /* ⚠️ 第 3 条修复：行取 `minmax(0,1fr)` ⇒ 配上面的 `height:100vh`，行高 = 视口高（不是内容高）；`minmax(0,…)` 的下界 0 是必须的 —— 默认 `min-content` 下界会把行顶回内容高。 */

/* ⚠️ 底用 `--glaze-deep` 不是 `--glaze-pale`：后者对纯白的 ΔE00 只有 2.91，
   占满左侧 46% 宽的一整栏时眼睛读成白（「加了底色」与「看得出是块底色」是两件事）。 */
.brand { display: flex; flex-direction: column;
         padding: var(--s7) var(--s7) var(--s6);
         background: var(--glaze-deep); border-right: 1px solid var(--line); min-height: 0; }   /* ⚠️ min-height:0 = 解开 grid 项的 `min-height:auto`（默认 = 内容高 791）⇒ 本列才能被压到行高。第 3 条修复。 */
.brand-mark { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-semi);
              letter-spacing: .06em; color: var(--brand-ink); }
/* 大字的两个「收紧」（负字距 + 紧行高）照稿：参考站点的 display 字号实测如此，
   而模板默认的 tracking-normal + 1.5 行高正是「一眼像模板」的来源之一。 */
/* ⚠️ 用**固定间距** `--s7`，不用 `margin-top:auto`：`.auth` 两列同行拉伸，
   注册态比登录态高，`auto` 会把标语在两态之间推上推下 —— 那正是本页要消灭的「切态时整块在动」。 */
.brand-claim { margin-top: var(--s7); max-width: 9.5em;
               font-family: var(--font-display); font-size: var(--d1); font-weight: var(--fw-med);
               line-height: 1.12; letter-spacing: -.03em; color: var(--ink); }
.brand-sub { margin-top: var(--s4); max-width: 24em; font-size: var(--fs-sm); line-height: 1.85;
             color: var(--ink-2); }
/* 品牌大图（用户最低清单点名）。封 `max-width:440px`：16:9 铺满 46% 栏（566px）时有 318px 高，
   加下方事实表在 1440×900 下会把整栏顶出首屏。
   底留 `--img`：图没加载出来时是一块正常的灰底，不会露出白洞。 */
.brand-fig { margin-top: var(--s6); width: 100%; max-width: 440px; aspect-ratio: 16/9;
             border: 1px solid var(--line); border-radius: var(--r2);
             background-color: var(--img); background-size: cover; background-position: top;
             background-image: url("http://localhost:8088/img/Mall-Section-2.jpg"); flex: 0 1 auto; min-height: 0; }   /* ⚠️ 短视口自缩（第 3 条修复）：本图是左栏里唯一「没有内容语义、压了也不丢信息」的一块 ⇒ 让它在短视口先让位。`min-height:0` 解开 flex 项的自动最小尺寸（默认 = 内容高 248），`flex-shrink:1` 才真能压下去。登记见台账 §九。同行追加，保持净零行数。 */
/* 注册态换另一张（稿 = `body[data-state="register"]`，见块首差异 2） */
.brand-fig.reg { background-image: url("http://localhost:8088/img/Mall-Section-1.jpg"); }
.facts { margin-top: var(--s6); border-top: 1px solid var(--line); }
.facts > div { display: flex; gap: var(--s5); align-items: baseline;
               padding: var(--s3) 0; border-bottom: 1px solid var(--line); }
.facts dt { flex: none; width: 3.5em; font-size: var(--fs-xs); color: var(--ink-3); }
.facts dd { font-size: var(--fs-sm); color: var(--ink-2); }

/* ── 右栏：表单面 ─────────────────────────────────────────────────────────── */
.pane { padding: var(--s6) var(--s7) var(--s6); min-height: 0; }   /* ⚠️ 同 `.brand`：`min-height:0` 才让本列（内容高 752）不把行顶回去。第 3 条修复。 */
.pane-top { display: flex; justify-content: space-between; font-size: var(--fs-xs); color: var(--ink-3); }
.pane-top a { padding: var(--s1) 0; }
.pane-top a:hover { color: var(--brand-ink); }

/* ⚠️ **外壳固定**：`margin:0 auto` —— 不是 `margin:auto`。
   双向 `auto` 会让每一态各自垂直居中（短态浮中间、长态顶到上边），容器的上边缘在两态之间跳。
   `.stage` 只负责换内容，容器的上/左/宽与 Tab 位置逐像素不动。 */
.form-shell { margin: 0 auto; width: 100%; max-width: 392px; padding-top: var(--s5); }

/* 页面级 Tab：登录 / 注册 */
.ctabs { display: flex; gap: var(--s5); border-bottom: 1px solid var(--line-2); }
.ctabs button { padding: var(--s2) 0 var(--s3); font-size: var(--fs-md); color: var(--ink-3);
                border-bottom: 2px solid transparent; margin-bottom: -1px; }
.ctabs button:hover { color: var(--ink-2); }
.ctabs button.on { color: var(--ink); font-weight: var(--fw-med); border-bottom-color: var(--brand); }

.stage { margin-top: var(--s5); }
@keyframes fadein { from { opacity: 0 } to { opacity: 1 } }
.page { display: none; }
.page.on { display: block; animation: fadein .18s ease; }

h1.t { font-family: var(--font-display); font-size: var(--d2); font-weight: var(--fw-med);
       line-height: 1.15; letter-spacing: -.02em; color: var(--ink); }
.t-sub { margin-top: var(--s2); font-size: var(--fs-sm); color: var(--ink-3); }

/* 登录方式切换：账号 / 手机号（分段控件）—— 不用 overflow:hidden 裁圆角，
   改用首/末子元素自带圆角（README：宿主与祖先不许 overflow:hidden） */
.seg { display: inline-flex; margin-top: var(--s4); }
.seg button { height: 30px; padding: 0 var(--s4); border: 1px solid var(--line-2);
              font-size: var(--fs-xs); color: var(--ink-3); }
.seg button + button { border-left: 0; }
.seg button:first-child { border-radius: var(--r1) 0 0 var(--r1); }
.seg button:last-child { border-radius: 0 var(--r1) var(--r1) 0; }
/* ⚠️ 选中态用 `--brand-tint` 不是 `--surface-2`（后者对白 ΔE00 只有 2.63）：
   「哪个被选中」是**功能信息**，不是装饰。这一处改色是修一个看不出来的选中态。 */
.seg button.on { background: var(--brand-tint); color: var(--brand-ink); font-weight: var(--fw-med); }

.f { margin-top: var(--s5); }
.f > label { display: block; margin-bottom: var(--s2); font-size: var(--fs-xs); color: var(--ink-2); }
.ipt { position: relative; display: flex; align-items: center; }
.ipt > input { width: 100%; height: 46px; padding: 0 var(--s3); border: 1px solid var(--line-2);
               border-radius: var(--r2); background: var(--surface);
               transition: border-color .15s, box-shadow .15s; }
.ipt > input:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
.ipt.has-btn > input { padding-right: 64px; }
.reveal { position: absolute; right: 6px; height: 34px; padding: 0 var(--s2); border-radius: var(--r1);
          font-size: var(--fs-xs); color: var(--ink-3); }
.reveal:hover { color: var(--ink-2); background: var(--surface-2); }
/* 验证码行：输入框 + 图形 + 换一张 */
.caprow { display: flex; gap: var(--s3); }
.caprow .ipt { flex: 1 1 auto; }
.capfig { flex: 0 0 96px; height: 46px; border: 1px solid var(--line-2); border-radius: var(--r2);
          background: var(--surface-2); display: flex; align-items: center; justify-content: center;
          font-family: var(--font-mono); font-size: var(--fs-md); letter-spacing: .22em;
          color: var(--ink-3); user-select: none; }
.caprow .again { flex: 0 0 auto; height: 46px; padding: 0 var(--s3); font-size: var(--fs-xs); color: var(--ink-3); }
.err { min-height: 16px; margin-top: var(--s1); font-size: var(--fs-xs); color: var(--danger); }

.row { display: flex; align-items: center; justify-content: space-between;
       margin: var(--s4) 0 0; font-size: var(--fs-xs); }
.chk { display: flex; align-items: center; gap: var(--s2); color: var(--ink-2); }
.chk input[disabled] { cursor: not-allowed; }
.row .dim { color: var(--ink-3); padding: var(--s1) 0; }
.row .dim:hover { color: var(--brand-ink); }

.btn { display: inline-flex; align-items: center; justify-content: center; gap: var(--s2);
       height: 46px; border-radius: var(--r2); font-weight: var(--fw-med); }
.btn-block { width: 100%; margin-top: var(--s6); }
.btn-primary { background: var(--brand); color: var(--bg); }
.btn-primary:hover { background: var(--brand-hover); }
.btn-ghost { border: 1px solid var(--line-2); color: var(--ink-2); }
.btn[disabled] { background: var(--surface-3); color: var(--ink-3); border-color: transparent;
                 cursor: not-allowed; }
.btn-ghost[disabled] { background: none; border: 1px dashed var(--line-2); }
.btn-sm { height: 46px; flex: 0 0 auto; padding: 0 var(--s5); font-size: var(--fs-sm); }

.warn { min-height: 20px; margin-top: var(--s3); font-size: var(--fs-xs); color: var(--danger); }
.swap { margin-top: var(--s6); padding-top: var(--s5); border-top: 1px solid var(--line);
        display: flex; justify-content: space-between; font-size: var(--fs-xs); color: var(--ink-3); }
.swap button { color: var(--brand-ink); font-weight: var(--fw-med); }

/* 注册面专属 */
.radios { display: flex; gap: var(--s5); align-items: center; height: 46px; }
.radios label { display: flex; align-items: center; gap: var(--s2); font-size: var(--fs-sm); color: var(--ink-2); }
.radios label.off { color: var(--ink-3); cursor: not-allowed; }
.strength { display: flex; gap: var(--s1); margin-top: var(--s2); }
.strength i { flex: 1; height: 3px; border-radius: 2px; background: var(--line-2); }
.strength i.on { background: var(--brand); }
.tip { margin-top: var(--s4); font-size: var(--fs-xs); color: var(--warning); }

/* ⚠️ 「收紧档」：实测登录面 626px / 注册面 605px，而左栏固定 792px ⇒ 两态页高随态在 812/800
   之间跳（1440×800 实测）= 切 Tab 时整页在动 =「登录滑动」。结构调整（2026-10-07）把原
   「注册面专属」的两处**字段间距**（0,2,0 稳压 0,1,0）放开到全部 `.page`；下面四条仍只归注册面。 */
.page .f { margin-top: var(--s2); }
.page .f > label { margin-bottom: var(--s1); }
.page[data-page="register"] .err { min-height: 8px; margin-top: 0; }
.page[data-page="register"] .btn-block { margin-top: var(--s5); }
.page[data-page="register"] .tip { margin-top: var(--s2); }
.page[data-page="register"] .swap { margin-top: var(--s4); padding-top: var(--s4); }

/* 注册成功面 */
.done h1 { font-family: var(--font-display); font-size: var(--d2); font-weight: var(--fw-med); line-height: 1.25; }
.done p { margin-top: var(--s4); font-size: var(--fs-sm); line-height: 1.85; color: var(--ink-2); }

/* ── 窄屏（≤1080）：品牌面压成顶部一条 ───────────────────────────────────────
   ⚠️ 这段**必须写在所有组件规则之后**：稿的 v2 首版把它放在 `.auth` 下面，而 `.brand{display:flex}`
   在其后 —— 特异性相同、后者在后 ⇒ 媒体查询里的规则被顶掉，1080 下品牌面仍整块渲染（稿的截图抓到）。
   响应式覆盖一律沉到样式表末尾，是这份稿子的一条硬规矩。 */
@media (max-width: 1080px) {
  .auth { grid-template-columns: 1fr; }
  .brand { padding: var(--s6) var(--s5) var(--s6); border-right: 0; border-bottom: 1px solid var(--line); }
  .brand-claim { margin-top: var(--s5); font-size: var(--d2); }
  .brand-sub, .facts, .brand-fig { display: none; }
  .pane { padding: var(--s6) var(--s5) var(--s8); }
  .form-shell { padding-top: var(--s6); }
}

/* ── 矮屏（≤780）：右栏「表单纵排」再收紧一档 ─────────────────────────────────
   ⚠️ 第 3 条修复的收尾。上面把 `.auth` 改成 `height:100vh` + `grid-template-rows:minmax(0,1fr)`
   之后，**左栏已经可压**（`.brand-fig` 从固有 248 跟着视口缩到 97），所以**剩余的地板全在右栏**：
   登录面 720px / 注册面 762px 是 `.pane` 的固有高（`pane-top` 25 + `.form-shell` 663 + 上下
   `--s6`）—— 视口一旦矮于它，整页仍然可滑（实测 1280×640：登录滑 80px / 注册滑 122px）。
   这一段只收**间距**：字号、输入框/按钮的 46px 高度、`--s5` 之类的横向节奏**一个不动**
   （控件的可点区域是功能，不是装饰）。只在 ≤780 生效 ⇒ 781 以上逐像素不变。
   放在样式表**最末**：原因同上面那条窄屏块。 */
@media (max-height: 780px) {
  .form-shell { padding-top: var(--s3); }
  .stage { margin-top: var(--s3); }
  .page .f { margin-top: var(--s3); }
  .btn-block { margin-top: var(--s4); }
  .swap { margin-top: var(--s4); padding-top: var(--s3); }
  .page[data-page="register"] .btn-block { margin-top: var(--s3); }
  .page[data-page="register"] .swap { margin-top: var(--s3); padding-top: var(--s3); }
}

/* ── 矮屏（≤760）：右栏再让一档（**用户 2026-10-08 裁决 ①**，兑现 9-6 ① 的那条高度档）──────
   ⚠️ **用户给的落点是「压左栏 `.brand`」，实测证明地板不在左栏**（`美化/probe-login-h.mjs`，1440×720）：
   720 高时左栏**已经让到位**（`.brand-fig` 248 → 177；640 时 → 97），而 `docH` **钉死在 735**
   —— 那是**右栏内容**的高（`.pane` 上下 32 + `pane-top` 25 + `.form-shell` 678）；压左栏一格都不降。
   ⇒ 本档收的是**右栏**的纵向间距（口径同上面那条：字号 / 控件的 46px 高 / 横向 `--s5` 节奏**一个不动**）。
   左栏那两条 padding 照用户原文一并加上，但**对 docH 0 影响**，只为极短视口留余量。
   实测（同一支探针）：修前 1440×720 **注册面可滑 15px**（登录面已 0）；修后两态数字见台账 §十。 */
@media (max-height: 760px) {
  .pane { padding-top: var(--s4); padding-bottom: var(--s4); }   /* 32 → 16：地板在右栏，这一条最有效（省 32px） */
  .form-shell { padding-top: 0; }                                 /* 12 → 0 */
  .stage { margin-top: var(--s2); }                               /* 12 → 8 */
  .page .f { margin-top: var(--s2); }                             /* 12 → 8（注册面 5 个字段块） */
  .btn-block { margin-top: var(--s2); }                           /* 16 → 8 */
  .swap { margin-top: var(--s2); padding-top: var(--s2); }        /* 16/12 → 8/8（上面那条给的是 s4/s3） */
  .page[data-page="register"] .btn-block { margin-top: var(--s2); }
  .page[data-page="register"] .swap { margin-top: var(--s2); padding-top: var(--s2); }
  .brand { padding-top: var(--s5); padding-bottom: var(--s4); }   /* 用户原文「压左栏」：照加（32/32 → 24/16；对 docH 0 影响） */
}
</style>