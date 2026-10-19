<template>
  <!--
    A 版骨架（2026-10-19）：300px 左侧身份树。
    ⚠️ 本组件只做「导航呈现」，不动任何既有页面与接口：
       · 买家区 / 商家区的链接全部指向**真实存在的路由**（`router/index.js`），
         不存在的入口（如独立售后页）不画，避免死链；
       · 身份不伪造「切换」——客户 / 商家由后端登录角色决定（`meta.roles` 守卫），
         这里只按**当前角色**呈现可达入口；管理员（`'3'`）才能跨两壳，故只有管理员
         看到「买家端 / 商家端 / 管理控制台」三向切换；
       · 未登录时照常显示入口，点击由路由守卫统一送去 `/login?redirect=…`。
  -->
  <aside class="side-rail" aria-label="身份导航">
    <div class="rail-brand">
      <div class="rail-mark">
        MALL
        <small>DUAL IDENTITY / 双身份账户</small>
      </div>
      <div class="rail-coord">{{ shellLabel }}<br>{{ today }}</div>
    </div>

    <div class="rail-account">
      <div class="who">
        <b>{{ accountName }}</b>
        <span class="tag">{{ accountTag }}</span>
      </div>
      <div class="codes">
        <span>BUYER {{ buyerId || '未登录' }}</span>
        <span>MERCHANT {{ merchantId || '未开通' }}</span>
      </div>
    </div>

    <div class="rail-zones">
      <section class="zone">
        <div class="zone-head">
          <b>买家区</b><span class="fill"></span><span class="state">常驻</span>
        </div>
        <router-link
          v-for="item in buyerLinks"
          :key="item.no"
          class="zone-link"
          :class="{ active: isActive(item) }"
          :to="item.to"
          :aria-current="isActive(item) ? 'page' : null"
        >
          <span class="no">{{ item.no }}</span><span>{{ item.label }}</span>
          <span class="count">{{ item.count == null ? '—' : item.count }}</span>
        </router-link>
      </section>

      <div class="rail-gate">
        <div class="gate-top"><span>身份闸门 / GATE</span><span>{{ gateCode }}</span></div>
        <p v-if="!loggedIn">当前<b>未登录</b>。登录后可进入个人中心、订单、消息与会员中心。</p>
        <p v-else-if="loginRole === '3'">当前以<b>管理员</b>身份浏览。两壳都能进，跨壳操作请走本闸门。</p>
        <p v-else-if="isMerchantShell">当前以<b>商家</b>身份浏览。买家资产（购物车 / 订单 / 历史购买 / 好友）已并入本壳，不会丢失。</p>
        <p v-else>当前是<b>普通买家</b>。开通商家后可发布商品、处理订单与经营数据，同时保留全部买家资产。</p>
        <div class="gate-acts">
          <router-link v-if="!loggedIn" class="rail-cta" to="/login">登录 / 注册</router-link>
          <template v-else-if="loginRole === '3'">
            <router-link class="rail-cta" to="/customer">买家端</router-link>
            <router-link class="rail-cta ghost" to="/merchant">商家端</router-link>
            <router-link class="rail-cta ghost" to="/admin">管理控制台</router-link>
          </template>
          <router-link v-else-if="isMerchantShell" class="rail-cta" to="/merchant/stats">查看经营数据</router-link>
          <router-link v-else class="rail-cta" to="/customer/user/apply">申请商家入驻</router-link>
        </div>
        <span class="gate-note">入驻与升级不改变账户、余额与收货地址。</span>
      </div>

      <section class="zone">
        <div class="zone-head">
          <b>商家区</b><span class="fill"></span><span class="state">{{ merchantEnabled ? '已开通' : '未开通' }}</span>
        </div>
        <template v-if="merchantEnabled">
          <router-link
            v-for="item in merchantLinks"
            :key="item.no"
            class="zone-link"
            :class="{ active: isActive(item) }"
            :to="item.to"
            :aria-current="isActive(item) ? 'page' : null"
          >
            <span class="no">{{ item.no }}</span><span>{{ item.label }}</span>
            <span class="count">{{ item.count == null ? '—' : item.count }}</span>
          </router-link>
        </template>
        <div v-else class="rail-locked">
          <span class="locked-kicker">MERCHANT / LOCKED</span>
          <b>你还不是商家</b>
          <p>商家是买家身份之上的升级层。提交入驻申请并通过审核后，这里才会出现商家工作台与经营入口。</p>
          <router-link class="rail-cta" to="/customer/user/apply">申请商家入驻</router-link>
          <span class="locked-note">升级不影响购物车、订单、会员与余额。</span>
        </div>
      </section>
    </div>

    <div class="rail-foot">
      <router-link :to="`${userBase}/info`">账户设置</router-link>
      <router-link v-if="loginRole === '3'" to="/admin">管理控制台</router-link>
      <button v-if="loggedIn" type="button" @click="logout">退出登录</button>
      <router-link v-else to="/login">登录</router-link>
      <span class="rail-mark-mini">MALL</span>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '../api/http'
import { endpoints } from '../api/endpoints'
import {
  getUsername,
  getRole,
  getMemberLevel,
  getMerchantId,
  getCustomerId,
  isLoggedIn,
  clearCredentials,
  clearMerchantId,
  clearToken
} from '../api/auth'

// 本仓 eslint-plugin-vue 8.7.1 不带宏环境（先例见 AppShell.vue 同款注释）。
// eslint-disable-next-line no-undef
const props = defineProps({
  /** 壳的种类：`'customer'` / `'merchant'`（与 `router/index.js` 的 `props.role` 同义）。 */
  role: { type: String, required: true },
  /** 本壳 URL 前缀（`/customer` / `/merchant`）。 */
  base: { type: String, required: true },
  /** 未读会话数 / 未读通知数 —— 与顶栏红点同源（`AppShell` 的 5s 轮询），不在本组件重复请求。 */
  unread: { type: Number, default: 0 },
  notifyUnread: { type: Number, default: 0 }
})

const route = useRoute()
const router = useRouter()

// ⚠️ 与 `AppShell` 同一条口径：`isLoggedIn()` / `getRole()` 不是响应式，壳每次进出都会重建
//    ⇒ 在这里取一次即可（登录 / 退出 / 401 后必然重算）。
const loggedIn = isLoggedIn()
const loginRole = getRole()

const isMerchantShell = computed(() => props.role === 'merchant')
/**
 * 商家区是否解锁。
 * ⚠️ 不是「前端开关」，而是真实权限：商家壳本身只对 `'2'` / `'3'` 开放；
 *    客户壳里只有管理员（`'3'`）能跨到 `/merchant/*`（`meta.roles` 守卫）。
 */
const merchantEnabled = computed(() => isMerchantShell.value || loginRole === '3')

const buyerId = getCustomerId()
const merchantId = getMerchantId()
const username = getUsername()

const accountName = computed(() => {
  if (username) return username
  if (merchantId) return `商家 ${merchantId}`
  if (buyerId) return `买家 ${buyerId}`
  return '未登录'
})
const accountTag = computed(() => {
  if (loginRole === '3') return '管理员'
  if (loginRole === '2' || isMerchantShell.value) return '商家'
  if (getMemberLevel() > 0) return 'VIP'
  return loggedIn ? '普通买家' : '游客'
})

const shellLabel = computed(() => (isMerchantShell.value ? 'MERCHANT' : 'BUYER'))
const today = (() => {
  const d = new Date()
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
})()

const userBase = computed(() => (isMerchantShell.value ? '/merchant/user' : '/customer/user'))

/**
 * 买家区入口。
 * ⚠️ 商家壳里没有 `/customer/*` 的通行权（`meta.roles`），因此**同一批买家功能**
 *    全部映射到商家壳内的等价地址（购物车 `order/cart`、订单 `order/cart?tab=orders`、
 *    历史购买 `user/history`、好友 `user/friend`、消息 `msg`）—— 功能一个不少，也不造死链。
 */
const buyerLinks = computed(() => {
  const m = isMerchantShell.value
  const base = m ? '/merchant' : '/customer'
  const user = m ? '/merchant/user' : '/customer/user'
  const cart = m ? '/merchant/order/cart' : '/customer/cart'
  const rows = [
    // 商家壳的壳首页就是「商家工作台」（下方商家区 10），再画一条「商城主页」会
    // 指向同一地址 ⇒ 同一页两行同时黑底反白。商家壳不画，顶栏「商城主页」仍在。
    ...(m ? [] : [{ no: '00', label: '商城主页', to: base, path: base, exact: true }]),
    { no: '01', label: '个人中心', to: `${user}/info`, paths: [user, `${user}/info`] },
    { no: '02', label: '购物车', to: cart, path: cart, tabGroup: 'cart', tab: 'cart' },
    { no: '03', label: '我的订单', to: `${cart}?tab=orders`, path: cart, tabGroup: 'cart', tab: 'orders' },
    { no: '04', label: '历史购买记录', to: `${user}/history`, path: `${user}/history`, exact: true },
    { no: '05', label: '好友', to: `${user}/friend`, path: `${user}/friend`, exact: true },
    { no: '06', label: '消息 · 会话', to: `${base}/msg`, path: `${base}/msg`, tabGroup: 'msg', tab: 'chat', count: props.unread || null },
    { no: '07', label: '通知中心', to: `${base}/msg?tab=notify`, path: `${base}/msg`, tabGroup: 'msg', tab: 'notify', count: props.notifyUnread || null },
    { no: '08', label: '秒杀日程', to: '/seckill', path: '/seckill', exact: true },
    { no: '09', label: '品牌专区', to: '/brand', path: '/brand', exact: true }
  ]
  // 会员中心只在客户壳存在（路由挂在 `customerUser` 下，商家角色进不去）⇒ 商家壳不画，
  // 不造「点了被守卫踢走」的入口。
  if (!m) rows.splice(8, 0, { no: '08', label: '会员 · 积分 · 余额', to: `${user}/member`, path: `${user}/member`, exact: true })
  // 编号按最终顺序重排，避免插入后出现两个 08。
  return rows.map((row, i) => ({ ...row, no: String(i).padStart(2, '0') }))
})

const merchantLinks = computed(() => {
  const rows = [
    // ⚠️ 同日修正：`/merchant` 是**公域商城主页**（与 `/customer` 同一个 `HomePage`），
    //    该页不挂身份树 ⇒ 不能把「商家工作台」指过去（点进去左树消失）。商家私域入口
    //    以下面这组为准；首页入口在顶栏「商城主页」。
    { no: '11', label: '订单中心', to: '/merchant/order', path: '/merchant/order', exact: true },
    { no: '12', label: '商品发布', to: '/merchant/user/add', path: '/merchant/user/add', exact: true },
    { no: '13', label: '商品管理', to: '/merchant/user/list', path: '/merchant/user/list', exact: true },
    { no: '14', label: '秒杀报名', to: '/merchant/seckill', path: '/merchant/seckill', exact: true },
    { no: '15', label: '经营数据', to: '/merchant/stats', path: '/merchant/stats', exact: true }
  ]
  if (merchantId) {
    rows.splice(1, 0, {
      no: '11',
      label: '店铺主页',
      to: { path: '/shop', query: { MerchantId: merchantId } },
      path: '/shop',
      exact: true
    })
  }
  return rows.map((row, i) => ({ ...row, no: String(10 + i).padStart(2, '0') }))
})

const gateCode = computed(() => {
  if (!loggedIn) return 'GUEST'
  if (loginRole === '3') return 'ADMIN'
  return isMerchantShell.value ? 'MERCHANT' : 'BUYER'
})

/** 当前项的选中判据：tab 类入口看 `?tab=`，其余看路径（`exact` 防 `/merchant/order` 吃掉子路径）。 */
function isActive(item) {
  if (item.tabGroup) {
    if (route.path !== item.path) return false
    const cur = item.tabGroup === 'msg'
      ? (route.query.tab === 'notify' ? 'notify' : 'chat')
      : (route.query.tab === 'orders' ? 'orders' : 'cart')
    return cur === item.tab
  }
  if (item.paths) return item.paths.includes(route.path)
  if (item.exact) return route.path === item.path
  return route.path === item.path || route.path.startsWith(`${item.path}/`)
}

/**
 * 退出登录 —— 与 `UserCenter.vue` 的 `toLogin()` **逐步等价**（客户侧只清用户名 / 角色，
 * 商家侧另清 merchantid；服务端会话 fail-open 注销，票在 `finally` 里清）。
 * ⚠️ 不并进 `clear()`：那个会多清 `customerid`，是「会话没了」的语义，不是「点退出」。
 */
function logout() {
  clearCredentials()
  if (isMerchantShell.value) clearMerchantId()
  router.push('/login')
  http.post(endpoints.user.logout, null, { allowUnauthorized: true }).catch(() => {}).finally(() => clearToken())
}
</script>

<style scoped>
/* ── A 版左侧身份树 ─────────────────────────────────────────────────────────────
   形态来源 = 已验收的线框 A（`_work/ui-wireframe/a.html` 的 `.rail`）：
   300px、发丝线、等宽编号、选中项黑底反白；不用圆角 / 渐变 / 阴影 / 侧边强调条。
   ⚠️ 颜色全部走现有 token（`--ink` / `--surface-2` / `--line` …），不另造灰阶。 */
.side-rail {
  width: 272px;   /* A 版同日修正：私域中台宽度取 240–280 档（用户 2026-10-19 手验意见） */
  flex: none;
  align-self: flex-start;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--line);
  background: var(--surface-2);
  font-family: var(--font);
  color: var(--ink);
}

/* 品牌区 */
.rail-brand {
  padding: 24px 24px 20px;
  border-bottom: 1px solid var(--line);
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.rail-mark {
  font-family: var(--font-display);
  font-size: 25px;
  line-height: 1;
  letter-spacing: -0.04em;
}
.rail-mark small {
  display: block;
  margin-top: 8px;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 400;
  letter-spacing: 0.13em;
  color: var(--ink-3);
}
.rail-coord {
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 1.6;
  letter-spacing: 0.08em;
  color: var(--ink-3);
  text-align: right;
}

/* 账户 */
.rail-account {
  padding: 16px 24px;
  border-bottom: 1px solid var(--line-2);
}
.rail-account .who {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.rail-account .who b {
  font-size: 15px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag {
  flex: none;
  border: 1px solid var(--line-2);
  background: var(--ink);
  color: var(--bg);
  padding: 1px 6px;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.05em;
  line-height: 1.6;
  white-space: nowrap;
}
.rail-account .codes {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 5px;
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-3);
}

/* 两区 */
.rail-zones {
  position: relative;
  flex: 1;
  padding-bottom: 8px;
}
.rail-zones::before {
  content: '';
  position: absolute;
  left: 13px;
  top: 20px;
  bottom: 20px;
  width: 1px;
  background: var(--line-2);
  pointer-events: none;
}
.zone {
  position: relative;
  padding: 14px 0 6px;
}
.zone-head {
  position: relative;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 24px 8px;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.1em;
  color: var(--ink-3);
}
.zone-head::before {
  content: '';
  position: absolute;
  left: 10px;
  top: 5px;
  width: 7px;
  height: 7px;
  border: 1px solid var(--ink);
  background: var(--surface-2);
}
.zone-head b {
  font-family: var(--font);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
  color: var(--ink);
}
.zone-head .fill {
  flex: 1;
  height: 1px;
  background: var(--line-2);
}
.zone-head .state {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-3);
}

/* 区内的链接：编号 / 名称 / 计数 三列 */
.zone-link {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
  padding: 9px 24px;
  border-top: 1px solid transparent;
  border-bottom: 1px solid transparent;
  font-size: 13px;
  color: var(--ink-2);
  text-decoration: none;
}
.zone-link .no {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-3);
}
.zone-link .count {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-3);
}
.zone-link:hover {
  background: var(--surface-3);
  color: var(--ink);
}
/* 选中 = 黑底反白（线框 A 的 `.zone-link.active`）。 */
.zone-link.active {
  background: var(--ink);
  border-color: var(--ink);
  color: var(--bg);
}
.zone-link.active .no,
.zone-link.active .count {
  color: var(--bg);
}

/* 身份闸门 */
.rail-gate {
  margin: 10px 18px 14px;
  padding: 13px;
  border: 1px solid var(--line);
  background: var(--surface);
}
.gate-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--ink-3);
}
.rail-gate p {
  margin: 7px 0 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-3);
}
.rail-gate p b {
  color: var(--ink);
}
.gate-acts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.gate-note {
  display: block;
  margin-top: 8px;
  font-size: 10px;
  line-height: 1.5;
  color: var(--ink-3);
}

/* 未开通商家区 */
.rail-locked {
  margin: 2px 18px 8px;
  padding: 14px;
  border: 1px solid var(--line);
  background: var(--surface);
}
.locked-kicker {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--ink-3);
}
.rail-locked b {
  display: block;
  margin: 6px 0;
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.2;
}
.rail-locked p {
  margin-bottom: 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-3);
}
.locked-note {
  display: block;
  margin-top: 8px;
  font-size: 10px;
  line-height: 1.5;
  color: var(--ink-3);
}

/* 黑底 CTA（与选中态同一语言，不做圆角/阴影） */
.rail-cta {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 7px 10px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  font-size: 12px;
  text-decoration: none;
}
.rail-cta:hover {
  background: var(--bg);
  color: var(--ink);
}
.rail-cta.ghost {
  background: var(--bg);
  color: var(--ink);
  border-color: var(--line-2);
}
.rail-cta.ghost:hover {
  background: var(--ink);
  color: var(--bg);
  border-color: var(--ink);
}

/* 页脚 */
.rail-foot {
  margin-top: auto;
  padding: 12px 24px;
  border-top: 1px solid var(--line);
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  background: var(--surface-2);
}
.rail-foot a,
.rail-foot button {
  padding: 0 0 1px;
  border: 0;
  border-bottom: 1px solid transparent;
  background: none;
  font: inherit;
  font-size: 11px;
  color: var(--ink-3);
  text-decoration: none;
  cursor: pointer;
}
.rail-foot a:hover,
.rail-foot button:hover {
  color: var(--ink);
  border-bottom-color: var(--ink);
}
.rail-mark-mini {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.13em;
  color: var(--ink-3);
}

/* 窄屏：两区并排；再窄则整条树回到文档流（与线框 A 的断点同口径）。 */
@media (max-width: 1180px) {
  .side-rail {
    width: 240px;
  }
  .rail-brand,
  .rail-account,
  .zone-link,
  .rail-foot {
    padding-left: 18px;
    padding-right: 18px;
  }
  .zone-head {
    padding-left: 18px;
    padding-right: 18px;
  }
  .rail-zones::before {
    left: 10px;
  }
  .zone-head::before {
    left: 7px;
  }
}
@media (max-width: 900px) {
  .side-rail {
    width: 100%;
    height: auto;
    position: static;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .rail-zones {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
  .rail-zones::before {
    display: none;
  }
}
@media (max-width: 640px) {
  .rail-zones {
    display: block;
  }
}
</style>
