<template>
  <div class="login-page">
    <div class="login-box">
      <div 
        class="img-section" 
        :class="{ slide: isSlide }"
        :style="{
          backgroundImage: isSlide
            ? 'url(http://localhost:8088/img/Mall-Section-2.jpg)'
            : 'url(http://localhost:8088/img/Mall-Section-1.jpg)'
        }"
      >
        <div class="img-text">
          {{ isSlide ?  "已有账号？快来登录吧！" : "未有账号？快来注册体验吧！"}}
        </div>
        <button class="slide-btn" @click="toggle">
          {{ isSlide ? 'sign in' : 'sign up' }}
        </button>
      </div>

      <div class="form-section" :class="{ slide: isSlide }">
        <div class="login-form" v-show="!isSlide">
          <h2>登录账号</h2>
          <div class="login-inputs">
            <div class="input-item">
              <input class="input login-input" placeholder="账号" v-model="loginAccount" />
            </div>
            <div class="input-item">
              <input class="input login-input" type="password" placeholder="密码" v-model="loginPwd" />
            </div>
          </div>

          <div class="login-warn">
            {{ loginWarnText }}
          </div>

          <button class="form-btn" @click="login">sign in</button>
        </div>

        <div class="register-form" v-show="isSlide && !showSuccess">
          <h2>注册账号</h2>
          <div class="register-inputs">
            <div class="input-item">
              <input class="input register-input" placeholder="用户名" v-model="username" />
              <div class="err-text">{{ err.username }}</div>
            </div>

            <div class="input-item">
              <div class="radio-row">
                <label><input type="radio" name="userType" value="客户" v-model="userType"> 客户用户</label>
                <label><input type="radio" name="userType" value="商家" v-model="userType" :disabled="isUnder18"> 商家用户</label>
              </div>
              <div class="err-text">{{ err.userType }}</div>
            </div>

            <div class="input-item">
              <input class="input register-input" type="date" v-model="birthDate" @change="calcAge" />
              <div class="err-text">{{ err.birthDate }}</div>
            </div>

            <div class="input-item">
              <input class="input register-input" placeholder="密码" type="password" v-model="pwd" @input="checkPwdStrong" />
              <div class="err-text">{{ err.pwd }}</div>
              <div class="pwd-bar" v-if="pwd">
                <div class="bar-item" :class="{active: strongLevel>=1}"></div>
                <div class="bar-item" :class="{active: strongLevel>=2}"></div>
                <div class="bar-item" :class="{active: strongLevel>=3}"></div>
              </div>
            </div>

            <div class="input-item">
              <div class="radio-row">
                <label><input type="radio" name="gender" value="男" v-model="gender"> 男</label>
                <label><input type="radio" name="gender" value="女" v-model="gender"> 女</label>
              </div>
              <div class="err-text">{{ err.gender }}</div>
            </div>
          </div>

          <button class="form-btn register-btn" @click="register">sign up</button>
          <div v-if="isUnder18" class="tip">未满18周岁，不可选择商家用户</div>
        </div>

        <div class="success-cover" v-show="showSuccess" @click="goToLogin">
          <div class="success-text">
            商城平台【{{ userType }}】账号注册成功，<br>
            点此返回登录界面
          </div>
        </div>

      </div>
    </div>
  </div>
</template>

<script>
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'
import { setMerchantId, setCustomerId, setUsername, setRole, setToken, publishSharedToken } from '../api/auth'
export default {
  name: "LoginPage",
  data() {
    return {
      isSlide: false,
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
      this.clearAllErr();
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

        // 自动保存ID，上架商品可用
        if (userData.merchantid) {
          setMerchantId(userData.merchantid);
        }
        if (userData.customerid) {
          setCustomerId(userData.customerid);
        }

        if(role === "0"){
          this.loginWarnText = "未找到该用户，请查看对应账号密码有无写错，或者前往右侧进行注册";
        }else if(role === "1"){
          setUsername(this.loginAccount); setRole(role); await this.mintToken(role);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md:172` 引的 `:216` 漂移）；P2.6 起 setCredentials → setUsername；多账户隔离笔（2026-10-06）再加铸票（见 mintToken）
          this.$router.push(this.landingAfterLogin(role));   // 前端路由守卫笔（2026-10-06）：落点改由 landingAfterLogin 决定（有合法 redirect 则回原目标，否则 = 原来的 "/customer"）
        }
        else if(role === "2"){
          setUsername(this.loginAccount); setRole(role); await this.mintToken(role);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md:172` 引的 `:216` 漂移）；P2.6 起 setCredentials → setUsername；多账户隔离笔（2026-10-06）再加铸票（见 mintToken）
          this.$router.push(this.landingAfterLogin(role));   // 前端路由守卫笔（2026-10-06）：同上，否则 = 原来的 "/merchant"
        }
      } catch (e) {
        this.loginWarnText = "未识别到账户，请检查对应输入";
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
      const base = role === "2" ? "/merchant" : "/customer";   // ⚠️ `role === "3"`（管理员）在这里会落到 "/customer" —— 归 **C3** 一并补（今天前端**没有** `role === "3"` 分支、DB 也无种子 ⇒ 管理员登录本就走不到这里；见 progress.md §5-AG (16) 的 C3 待办）
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
  }
};
</script>

<style scoped>
/* ── 12a（2026-10-07）：本块**原是全仓唯一一个不带 `scoped` 的 `<style>`**（其余 15 个都带）——
   块内 33 条选择器**全是全局的**。本笔把它收进 `scoped`，并删掉两条**外泄到别的页**的规则。
   逐条取证（不是「看着像」）：

   1) 已删 `* { margin:0; padding:0; box-sizing:border-box }`
      与 `assets/tokens.css:52` **逐字重复**，而 `tokens.css` 由 `main.js:2` 全局引入
      ⇒ 删掉**无渲染变化**（本页内部也由它兜着）。

   2) 已删 `input, button, .success-cover { user-select:text !important; pointer-events:auto !important }`
      全仓 `pointer-events` 只有 5 处，**没有一处依赖它**；它反而**正在压掉**
      `GoodsAdd.vue:617` 的 `.code input { pointer-events: none }` ——
      那是类型单选胶囊里那枚 `width:1px / opacity:0` 的**装饰性 `<input type="radio">`**
      （`GoodsAdd.vue:99` / `:107`，可见的胶囊由原生 radio 承担语义、`<span>` 承担视觉），
      本页明确写了「不可点」，被这条全局 `!important` **顶掉了** ⇒ 删掉它 = 让那条声明恢复生效。
      ⚠️ 实测影响**很小**（1px 的隐形热区，且 `label` 包着 input、点胶囊本来就会转发到它），
      所以这**不是**一个「用户能看出来的 bug」，只是**全局层越权**的证据 —— 如实标注，别夸大。
      另 3 处 `pointer-events`（`HomePage.vue:597` 的 `.glaze-frame` 是 `div`，另有本页自己两处）
      **都不受影响**。`user-select` 在全仓**只有本页**用过。
      本页自己需要的那两处（`.slide-btn,.form-btn`、`.success-cover`）**各自自带** `!important`
      ⇒ 删掉后本页行为不变。

   3) **没收的一条：`.tip`**（上一条 `.tip` 的 `position/right/bottom` 命中 `GoodsAdd.vue` 的
      5 处 `class="tip"`，而 GoodsAdd 自己的 `.tip` 只设 margin-top / font-size / color、
      没有 position ⇒ 作者本意是**就地显示**，那条绝对定位是意外泄漏）。
      ⚠️ 但本页 `:81` **自己也在用** `.tip`（「未满18周岁…」），那条定位是本页的设计
      ⇒ **整条删会伤到本页**，故改为**靠 `scoped` 收敛**：本页那条仍在、GoodsAdd 那边自动脱离。
      （若确要连本页这条也去掉，属 12b 按稿重写时的判断，不在本笔。）

   ⚠️ 行号影响：本笔只动 `:307` 之后。全仓 27 条（19 条单值 + 8 条区间）指向 `LoginPage.vue`
   的行号引用，目标行**全部落在 86–280**（模板/脚本区）⇒ 一条都不漂。 */

.login-page {
  width: 100vw;
  height: 100vh;
  background-image: url("http://localhost:8088/img/Mall-Homepage.jpg");
  background-size: cover;
  background-position: center;
  display: flex;
  justify-content: center;
  align-items: center;
  opacity: 0.9;
  position: relative;
}
.login-box {
  width: calc(70% * 0.8);
  height: calc(70%);
  border-radius: 22px;
  position: relative;
  overflow: hidden;
  box-shadow: 0 0 20px rgba(0,0,0,0.15);
}
.img-section {
  position: absolute;
  width: 35%;
  height: 100%;
  right: 0;
  top: 0;
  background-size: cover;
  background-position: center;
  transition: transform 0.7s ease;
  z-index: 2;
}
.img-section.slide {
  transform: translateX(-185.7%);
}
.form-section {
  position: absolute;
  width: 65%;
  height: 100%;
  left: 0;
  top: 0;
  background: rgba(255,255,255,0.6);
  backdrop-filter: blur(10px);
  transition: transform 0.7s ease;
  z-index: 1;
}
.form-section.slide {
  transform: translateX(53.85%);
}
.img-text {
  position: absolute;
  top: 15%;
  width: 100%;
  text-align: center;
  font-size: 15px;
  font-weight: bold;
  color: #000;
  user-select: none;
}
.slide-btn,.form-btn {
  border: none;
  border-radius: 30px;
  background: #00bcd4;
  color: #fff;
  font-size: 14px;
  padding: 11px 28px;
  cursor: pointer !important;
  pointer-events: auto !important;
  user-select: none !important;
  outline: none !important;
  -webkit-tap-highlight-color: transparent;
}
.slide-btn {
  position: absolute;
  bottom: 35%;
  left: 50%;
  transform: translateX(-50%);
}
.form-btn {
  position: absolute;
  bottom: 20%;
  left: 50%;
  transform: translateX(-50%);
}
.register-btn {
  bottom: 10% !important;
}
.login-form,.register-form {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 5%;
  position: relative;
}
.login-form h2,.register-form h2 {
  height: 20%;
  display: flex;
  align-items: center;
  font-size: 26px;
  color: #333;
}
.input-item {
  display: flex;
  flex-direction: column;
  height: 60px;
  justify-content: center;
}
.login-inputs {
  display: flex;
  flex-direction: column;
  gap: 30px;
  position: relative;
  top: 40px;
  width: 75%;
}
.login-input {
  width: 100%;
  padding: 16px;
  border-radius: 12px;
}
.register-inputs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  position: relative;
  top: 10px;
  width: 75%;
}
.register-input {
  width: 100%;
  padding: 12px;
  border-radius: 8px;
}
.radio-row {
  display: flex;
  justify-content: space-around;
  font-size: 14px;
}
.radio-row label {
  cursor: pointer;
}
.err-text {
  font-size: 12px;
  color: red;
  height: 16px;
  text-align: left;
}
.tip {
  position: absolute;
  right: 40px;
  bottom: 80px;
  color: red;
  font-size: 12px;
}
.input {
  border: none;
  background: #f1f3f5;
  box-shadow: inset 2px 2px 5px rgba(0,0,0,0.08);
  outline: none;
}
.pwd-bar {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}
.bar-item {
  flex: 1;
  height: 6px;
  border-radius: 10px;
  background: #ddd;
}
.bar-item.active {
  background: #00bcd4;
}
.login-warn {
  position: absolute;
  left: 40px;
  bottom: 90px;
  color: red;
  font-size: 13px;
  max-width: 80%;
}
.success-cover {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 99;
  pointer-events: auto !important;
}
.success-text {
  font-size: 30px;
  color: #00bcd4;
  text-align: center;
  line-height: 1.8;
  padding: 0 20px;
  text-shadow: 
    1px 1px 0 #029fb9,
    2px 2px 0 #028fa8,
    3px 3px 0 #028096,
    4px 4px 4px rgba(0,0,0,0.25);
  font-weight: 600;
  letter-spacing: 2px;
}
</style>