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
import { setMerchantId, setCustomerId, setCredentials, setRole } from '../api/auth'
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
        const userRes = await http.post(endpoints.user.getUserInfo, new URLSearchParams({ 
            username: this.loginAccount, 
            password: this.loginPwd 
          }));
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
          setCredentials(this.loginAccount, this.loginPwd); setRole(role);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md:172` 引的 `:216` 漂移）
          this.$router.push("/customer");
        }
        else if(role === "2"){
          setCredentials(this.loginAccount, this.loginPwd); setRole(role);   // ⚠️ 止血笔：同行追加，保持净零行数（防 `api-contract.md:172` 引的 `:216` 漂移）
          this.$router.push("/merchant");
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

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  
}
input, button, .success-cover {
  user-select: text !important;
  pointer-events: auto !important;
}

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