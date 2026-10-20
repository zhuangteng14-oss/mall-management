<template>
  <!--
    管理员专属登录页（2026-10-19 用户令：管理员登录彻底独立）。
    · 深色主题与 `admin.html` 的 `data-theme="admin"` 同一套色值（#101828 / 运营蓝），
      但本页**自带**颜色，不依赖 html 主题 —— 从商城入口 `#/admin/login` 进来也是深色。
    · 表单只有管理员账号 + 密码，无注册入口；右上角固定「返回普通登录」。
    · 鉴权：走 TD-18 的 `POST /user/adminToken`（账号 + 口令 ⇒ 可撤销 Bearer 票），只接受 `role=3`；
      成功后写**独立**的管理员会话（`api/auth.js#setAdminSession`），不碰用户/商家的身份键与票。
  -->
  <main class="admin-login">
    <router-link class="al-back" to="/login">← 返回普通登录</router-link>

    <div class="al-grid">
      <section class="al-brand" aria-label="管理后台">
        <div class="al-mark">MALL<small>管理后台 / ADMIN</small></div>

        <h1>平台管理，<br>从独立入口开始。</h1>
        <p class="al-lead">
          账号治理、商品审核、订单与售后、营销与可观测数据，集中在同一套管理后台。
          管理员身份与商城买家、商家身份彼此独立。
        </p>

        <dl class="al-facts">
          <div><dt>IDENTITY</dt><dd>管理员账号独立登录</dd></div>
          <div><dt>SCOPE</dt><dd>用户 · 商品 · 订单 · 营销 · 风控</dd></div>
          <div><dt>ACCESS</dt><dd>仅限平台分配账号</dd></div>
        </dl>
      </section>

      <section class="al-card" aria-label="管理员登录表单">
        <header class="al-card-h">
          <span class="al-kicker">ADMIN PORTAL</span>
          <h2>管理员登录</h2>
          <p>请使用平台分配的管理员账号进入后台。</p>
        </header>

        <form class="al-form" @submit.prevent="submit">
          <label class="al-field">
            <span>管理员账号</span>
            <input
              ref="accountEl"
              v-model.trim="account"
              type="text"
              name="adminAccount"
              autocomplete="username"
              placeholder="请输入管理员账号"
              :disabled="busy"
            />
          </label>

          <label class="al-field">
            <span>密码</span>
            <span class="al-pwd">
              <input
                v-model="password"
                :type="showPwd ? 'text' : 'password'"
                name="adminPassword"
                autocomplete="current-password"
                placeholder="请输入密码"
                :disabled="busy"
              />
              <button type="button" class="al-eye" tabindex="-1" @click="showPwd = !showPwd">
                {{ showPwd ? '隐藏' : '显示' }}
              </button>
            </span>
          </label>

          <p v-if="err" class="al-err" role="alert">{{ err }}</p>

          <button class="al-submit" type="submit" :disabled="busy">
            {{ busy ? '正在验证…' : '进入管理后台' }}
          </button>
        </form>

        <footer class="al-card-f">
          管理员账号由平台分配，不提供自助注册。
        </footer>
      </section>
    </div>
  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { urlOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { setAdminSession } from '../api/auth'

const router = useRouter()
const accountEl = ref(null)
const account = ref('')
const password = ref('')
const showPwd = ref(false)
const busy = ref(false)
const err = ref('')

onMounted(() => {
  const el = accountEl.value
  if (el && typeof el.focus === 'function') el.focus()
})

/**
 * 管理员登录**不走 `http.post`**：那一层会自动带上 `Authorization: Bearer <authToken>`。
 * 本页要求「不覆盖用户/商家的 Token 状态」，而票优先会让后端把管理员认成上一位登录者。
 * 这里用裸 `fetch` + `credentials:'include'`，只打 TD-18 的凭据铸票端点。
 *
 * ⚠️ TD-18（2026-10-20）：端点同时做**凭据校验 + 失败锁定 + 弱口令拒绝 + 铸票**，
 * 不再先走 `loginSelect`（那一步只建 cookie 会话、没有票）。响应是 JSON。
 */
async function adminToken(username, pwd) {
  const res = await fetch(urlOf(endpoints.adminAuth.token), {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password: pwd }).toString()
  })
  let data = null
  try { data = await res.json() } catch (e) { /* 非 JSON（网关/404）⇒ 下面按状态码报错 */ }
  if (!res.ok) {
    const rejection = new Error((data && data.msg) || `HTTP ${res.status}`)
    rejection.status = res.status
    rejection.retryAfter = res.headers.get('Retry-After')
    throw rejection
  }
  return data
}

async function submit() {
  if (busy.value) return
  err.value = ''
  if (!account.value || !password.value) {
    err.value = '请输入管理员账号和密码'
    return
  }

  busy.value = true
  try {
    const data = await adminToken(account.value, password.value)
    if (!data || !data.success || !data.token) {
      err.value = (data && data.msg) || '登录失败，请稍后重试'
      return
    }

    // 只写独立的管理员会话键：不动 loginUsername / role / authToken / merchantid / customerid。
    // 管理员票另有独立存储键（adminAuthToken），由 `http.js` 在管理员会话期间取用。
    setAdminSession(account.value, data.token)
    await router.push('/admin')
  } catch (e) {
    if (e.status === 401) {
      err.value = e.message || '账号或密码不正确，请检查后重试'
    } else if (e.status === 403) {
      err.value = e.message || '该账号不能登录管理后台'
    } else if (e.status === 429) {
      err.value = e.message || '登录失败次数过多，账号已临时锁定'
    } else if (e.status === 503) {
      err.value = e.message || '认证服务暂不可用，请稍后重试'
    } else {
      err.value = '连不上服务器：请确认后端已在 8088 运行，且本页源在 CORS 白名单内'
    }
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.admin-login {
  --al-bg: #101828;
  --al-surface: #172032;
  --al-inset: #1f2a3f;
  --al-line: #2b394f;
  --al-line-2: #3a4a63;
  --al-ink: #e8ecf3;
  --al-ink-2: #a3aec2;
  --al-ink-3: #7b8aa3;
  --al-brand: #2f6fed;
  --al-brand-hi: #4a85f5;
  --al-brand-ink: #5b90ff;
  --al-danger: #f87171;

  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 64px 40px;
  background:
    radial-gradient(900px 520px at 18% 12%, rgba(47, 111, 237, .18), transparent 62%),
    radial-gradient(760px 420px at 88% 92%, rgba(56, 189, 248, .08), transparent 60%),
    var(--al-bg);
  color: var(--al-ink);
  font-family: var(--font);
  overflow: hidden;
}

/* 极细网格：只做背景肌理，不承载信息。 */
.admin-login::before {
  content: "";
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, .028) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, .028) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse at 50% 40%, #000 30%, transparent 78%);
  pointer-events: none;
}

.al-back {
  position: absolute;
  top: 26px;
  right: 32px;
  z-index: 2;
  padding: 8px 14px;
  border: 1px solid var(--al-line-2);
  border-radius: 8px;
  color: var(--al-ink-2);
  font-size: 13px;
  text-decoration: none;
  background: rgba(23, 32, 50, .72);
  transition: color .15s, border-color .15s;
}
.al-back:hover { color: var(--al-ink); border-color: var(--al-brand-ink); }
.al-back:focus-visible { outline: 2px solid var(--al-brand-ink); outline-offset: 2px; }

.al-grid {
  position: relative;
  z-index: 1;
  width: min(1080px, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(360px, .82fr);
  gap: 72px;
  align-items: center;
}

.al-brand { max-width: 520px; }
.al-mark {
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: .18em;
  color: var(--al-ink);
}
.al-mark small {
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 400;
  letter-spacing: .14em;
  color: var(--al-ink-3);
}

.al-brand h1 {
  margin: 26px 0 0;
  font-family: var(--font-display);
  font-size: clamp(30px, 3.6vw, 46px);
  font-weight: 600;
  line-height: 1.18;
  letter-spacing: -.02em;
  color: var(--al-ink);
}
.al-lead {
  margin: 20px 0 0;
  max-width: 46ch;
  font-size: 14px;
  line-height: 1.85;
  color: var(--al-ink-2);
}

.al-facts {
  margin: 40px 0 0;
  padding: 0;
  border-top: 1px solid var(--al-line);
}
.al-facts div {
  display: flex;
  align-items: baseline;
  gap: 18px;
  padding: 12px 0;
  border-bottom: 1px solid var(--al-line);
}
.al-facts dt {
  flex: 0 0 92px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .14em;
  color: var(--al-ink-3);
}
.al-facts dd {
  margin: 0;
  font-size: 13px;
  color: var(--al-ink-2);
}

.al-card {
  padding: 38px 36px 28px;
  border: 1px solid var(--al-line);
  border-radius: 14px;
  background: linear-gradient(180deg, #1a2438 0%, var(--al-surface) 58%);
  box-shadow: 0 28px 80px rgba(4, 9, 18, .55);
}

.al-kicker {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .18em;
  color: var(--al-brand-ink);
}
.al-card-h h2 {
  margin: 10px 0 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -.01em;
  color: var(--al-ink);
}
.al-card-h p {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--al-ink-3);
}

.al-form { margin-top: 28px; }
.al-field { display: block; }
.al-field + .al-field { margin-top: 18px; }
.al-field > span:first-child {
  display: block;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--al-ink-2);
}
.al-field input {
  width: 100%;
  height: 46px;
  padding: 0 14px;
  border: 1px solid var(--al-line-2);
  border-radius: 8px;
  background: var(--al-inset);
  color: var(--al-ink);
  font-family: var(--font);
  font-size: 14px;
  outline: none;
  transition: border-color .15s, box-shadow .15s;
  box-sizing: border-box;
}
.al-field input::placeholder { color: #61708a; }
.al-field input:focus {
  border-color: var(--al-brand-ink);
  box-shadow: 0 0 0 3px rgba(91, 144, 255, .16);
}
.al-field input:disabled { opacity: .6; }

.al-pwd { position: relative; display: block; }
.al-pwd input { padding-right: 60px; }
.al-eye {
  position: absolute;
  top: 50%;
  right: 10px;
  transform: translateY(-50%);
  padding: 4px 6px;
  border: 0;
  background: none;
  color: var(--al-ink-3);
  font-family: var(--font);
  font-size: 12px;
  cursor: pointer;
}
.al-eye:hover { color: var(--al-brand-ink); }

.al-err {
  margin: 14px 0 0;
  padding: 9px 12px;
  border: 1px solid rgba(248, 113, 113, .34);
  border-radius: 8px;
  background: #2a1d28;
  color: var(--al-danger);
  font-size: 12px;
  line-height: 1.6;
}

.al-submit {
  width: 100%;
  height: 46px;
  margin-top: 24px;
  border: 0;
  border-radius: 8px;
  background: var(--al-brand);
  color: #fff;
  font-family: var(--font);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: .02em;
  cursor: pointer;
  transition: background .15s;
}
.al-submit:hover:not(:disabled) { background: var(--al-brand-hi); }
.al-submit:disabled { opacity: .62; cursor: default; }
.al-submit:focus-visible { outline: 2px solid var(--al-brand-ink); outline-offset: 2px; }

.al-card-f {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--al-line);
  font-size: 11px;
  line-height: 1.7;
  color: var(--al-ink-3);
  text-align: center;
}

@media (max-width: 900px) {
  .admin-login { padding: 72px 20px 40px; overflow: auto; }
  .al-grid { grid-template-columns: minmax(0, 1fr); gap: 36px; }
  .al-brand h1 { font-size: 30px; }
  .al-facts { margin-top: 24px; }
  .al-back { top: 18px; right: 18px; }
}
</style>
