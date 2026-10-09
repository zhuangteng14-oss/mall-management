import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn, getRole, getMerchantId } from '../api/auth'
import LoginPage from '../views/LoginPage.vue'
import AppShell from '../views/AppShell.vue'
import { CUSTOMER_NAV, MERCHANT_NAV } from '../config/nav'
import ProductPage from '../views/ProductPage.vue'
import ShoppingCart from '../views/ShoppingCart.vue'
// 👇 引入你新的搜索页面
import GoodsSelect from '../views/GoodsSelect.vue'
// ── R1（2026-10-05）新增：客户壳的子页面组件 ────────────────────────────────────
// ⚠️ R2（2026-10-05）：`HomeTab` 由 `views/customer/` **上移到 `views/`** —— 它适配的
//    `HomePage` 本来就是**两壳共用**的，R2 起它自己也被两个壳共用（用户裁决 4）。
import HomeTab from '../views/HomeTab.vue'
import UserCenter from '../views/UserCenter.vue'
import MessageCenter from '../views/MessageCenter.vue'   // ⚠️ 2026-10-09 合并「消息 / 通知」入口：本行原为 `import ChatRoom from '../views/ChatRoom.vue'`（**同行替换、净零行数**）—— `ChatRoom` 改由 `MessageCenter.vue` 内嵌引入；`ChatRoom.vue` 自身未改一字
import UserInfoPage from '../views/UserInfoPage.vue'
import HistoricalOrders from '../views/HistoricalOrders.vue'
import FriendRoom from '../views/FriendRoom.vue'
// ── R2（2026-10-05）新增：商家壳专属的子页面组件 ────────────────────────────────
import OrderPane from '../views/merchant/OrderPane.vue'
import GoodsAdd from '../views/GoodsAdd.vue'
import GoodsUp from '../views/GoodsUp.vue'

// ── R1（2026-10-05）：`/customer` 由**一条平级记录**改为**父记录 + 子路由** ───────────
// 5 个 tab 各自一条子路由，壳只留顶栏 + `<router-view>`（壳/页面分层）。（R6 后壳 = `AppShell`；原为 `CustomerPage`，两壳已合一。）
// 于是 tab 状态**就是 URL**：可深链、可刷新、可后退、可分享。
//
// ── R2（2026-10-05）：`/merchant` 照同一模板改造（用户裁决：镜像 R1）───────────────
// 6 条子路由（home / search / msg / order / order/cart / user），壳只留顶栏 + `<router-view>`（R6 后壳 = `AppShell`，原为 `MerchantPage`）。
// ⚠️ 两壳的顶栏差异**只有两处**：左项「商城主页」的 `to`（各指自己的壳）+ 右起第 2 项（客户「购物车」⇄ 商家「订单中心」）—— 顶栏项 R2 起已抽到 `src/config/nav.js`（用户裁决 6），R6 起由 `AppShell` 消费。⚠️ **R6（2026-10-05）**：两条父记录的 `component` 换成同一个 `AppShell`，配置走 `props: { nav, base, role }`（`role` 是实质增量：两壳的 `getCurrentUser()` 降级链**顺序有意相反**）；两壳**不合并成一条记录**（`path` / `children` / `subMenu` / `logout` 全不同）⇒ 本句成为「两条记录的全部配置差异」的验收口径。逐条见 progress.md §5-AB。
// ── R3（2026-10-05）：`/` 由「**裸挂 `HomePage` 的页面**」改为「**一条落点 redirect**」（R1/R2 都没碰、留给 R3 的收口）
//    为什么进客户壳 / 未登录怎么办 / 守卫为何搬走 ⇒ 见尾部 `landingFor` 上方的整段注释。
//
// ⚠️ `meta` 各键的分工（**壳与页都读它**，别随手删）：
//   `tab`      —— 顶栏高亮（壳里的 `:class="{ active: route.meta.tab === item.tab }"`）。
//                 ⚠️ 用 `meta.tab` 而**不是** `route.path.startsWith(...)` 之类：
//                 父路径前缀会让「商城主页」在所有子路由上都高亮。
//                 搜索页**故意不设** `tab` ⇒ 停在搜索时所有按钮都不高亮（与路由化前一致）。
//   `layout`   —— 内容区外壳类名（壳里的 `layoutClass`），与路由化前那些兄弟 div
//                 的类名一一对应 ⇒ 布局逐字不变。子记录不写则**继承**父记录的（meta 合并）。
//   `sub`      —— 「用户中心」左菜单高亮（`UserCenter.vue`）。
//   `subMenu`  —— 「用户中心」左菜单**表**（R2 起；A5 起客户 3 项 / 商家 5 项 —— 两侧各去掉「消息」，见下表内的 A5 行）——
//                 `UserCenter.vue` 由它 `v-for` 渲染 ⇒ 两壳共用同一组件（用户裁决 3）。
//   `logout`   —— 退出登录的**分支键**（R2 起）：`'merchant'` ⇒ 另加 `clearMerchantId()`；
//                 缺省（客户侧）⇒ 只 `clearCredentials()`。见 `UserCenter.vue` 的 `toLogin`。
//   `orderTab` —— **仅**商家「订单中心」两条记录用：`OrderPane.vue` 靠它决定渲染哪一面
//                 （`/merchant/order` = `'order'`、`/merchant/order/cart` = `'cart'`）。
// ⚠️ `path: ''` 与 `path: 'info'` 两个记录指**同一个组件**（`UserInfoPage`）、同一个 `sub`：
//    `/…/user` 是「用户中心默认页」的短地址，`/…/user/info` 是显式地址。
//    不设 `redirect` 是为了不依赖「有 children 的记录能不能同时挂 redirect」这一细节。两壳同法。
const routes = [
  { path: '/', redirect: landingFor },   // R3：不再是页面，只是落点（`landingFor` 在文件尾部 —— 函数声明提升，可先用后定义）
  { path: '/login', component: LoginPage, meta: { public: true } },
  {
    path: '/customer', name: 'customer', meta: { roles: ['1', '3'] },   // ← 角色边界（2026-10-06，§5-AG (16)）：只有**客户**与**管理员**可进这个壳；`meta` 沿 `matched` 链合并 ⇒ 5 个子路由**自动继承**，无需逐条标。⚠️ 别挂到下一行的 `props.role` 上 —— 那是「**壳的种类**」，与本键同形异义。⚠️ `name`（C3 块1·I2 加，2026-10-09）：本路由表里**唯一**被用到的 name，只为文件尾 `addRoute('customer', …)` 挂「系统通知」子路由 —— 理由见文件尾那块（同行追加、净零行数）
    component: AppShell, props: { nav: CUSTOMER_NAV, base: '/customer', role: 'customer' },
    children: [
      { path: '', name: 'customerHome', component: HomeTab, props: { base: '/customer' }, meta: { tab: 'home', layout: 'home-container', public: true } },   // ⚠️ C3 块 2（2026-10-09）：**同行**加 `name` —— 只为消一条 vue-router warn。父记录 `:55` 有 `name` 而本子记录**空路径**又**无名**时，`createRouter` 建匹配表就发 `checkChildMissingNameWithEmptyPath`（`vue-router.mjs:848`）（三壳三条，`#/login` 上一样刷）。本 name **没有别的用处**，全仓无 `push({name:'customerHome'})` —— 那正是警告说的「用父名不会渲染空路径子记录」那件事，这里靠**给子记录起名**消掉（警告原话：`add a name to the child route to remove the warning`）。
      { path: 'search', component: GoodsSelect, meta: { layout: 'page', public: true } },
      { path: 'msg', component: MessageCenter, meta: { tab: 'msg', layout: 'page' } },   // ⚠️ 2026-10-09：`component` 由 `ChatRoom` 换成 `MessageCenter`（**同行替换**，两壳各一处）—— 本记录现在还承载「通知」tab（`?tab=notify`）；`meta.tab` 仍是 `'msg'` ⇒ 顶栏「消息」在两个 tab 下都亮
      { path: 'cart', component: ShoppingCart, props: { showSwitch: false }, meta: { tab: 'cart', layout: 'cart-container' } },   // ← ⚠️ **必须**传 false：R1 前客户壳是 `<ShoppingCart :showSwitch="false" />`，R1 落码时丢了 ⇒ 该页凭空多出 📋/🛒 开关（且无人监听 = 死控件）。`fix/legacy-chat-tab` 修（见 progress.md §5-X (16)2）
      {
        path: 'user', name: 'customerUser',   // ⚠️ C3 块 2〈用户块〉：**同行**加 `name`（不新插一行）—— 文件尾那条 `router.addRoute('customerUser', …)` 靠它把「商家入驻」挂成本壳的子页（`addRoute` 的第一个参数**只认 name**）。⚠️ 一旦插行，本组 `:61`-`:122` 的既有行号引用会整体下移。
        component: UserCenter,
        meta: {
          tab: 'user',
          layout: 'user-page',
          subMenu: [
            { label: '个人信息', sub: 'info', to: '/customer/user/info' },
            { label: '历史购买记录', sub: 'history', to: '/customer/user/history' },
            { label: '好友', sub: 'friend', to: '/customer/user/friend' },{ label: '商家入驻', sub: 'apply', to: '/customer/user/apply' },   // ⚠️ C3 块 2〈用户块〉：**同行**追加（净零行数，理由同本行上方 A5 那条）。落点在文件尾 `addRoute('customerUser', { path: 'apply', … })`，**别**在 `children` 里找它 —— 那条记录是挂上去的，不在字面数组里
            // ⚠️ A5（2026-10-07 用户裁定）：原第 4 项「消息」（`sub: 'chat'` → `/customer/user/chat`）**已删** —— 与顶栏「消息」重复；子路由改 `redirect`（见 `children` 末尾）
          ]
        },
        children: [
          { path: '', name: 'customerUserHome', component: UserInfoPage, meta: { sub: 'info' } },   // ⚠️ C3 块 2（2026-10-09）：**同行**加 `name`，理由同 `:58` —— 父 `:63` 那个 `name` 正是**本笔**加的，所以这三条 warn 里有本笔一条。无 `push({name:'customerUserHome'})`；**父名**照旧给文件尾的 `addRoute('customerUser', …)` 用（`checkSameNameAsAncestor` 只禁同名，本 name 与父名不同）。⚠️ `/merchant` 壳下那条**同名**子记录**没有**加 —— 它的父记录本身无 `name`，不发这条 warn，别顺手对称过去。
          { path: 'info', component: UserInfoPage, meta: { sub: 'info' } },
          { path: 'history', component: HistoricalOrders, meta: { sub: 'history' } },
          { path: 'friend', component: FriendRoom, meta: { sub: 'friend' } },
          { path: 'chat', redirect: '/customer/msg' }   // ⚠️ A5：原挂 `ChatRoom`（与 `/customer/msg` **同一个组件**）⇒ 侧边栏去项后本路径**不留死链**；⚠️ 不删记录（手敲/旧书签会白屏 —— 全仓无 catch-all，R4 口径见 `:208-210`），改 redirect 与旧地址兼容
        ]
      }
    ]
  },
  {
    path: '/merchant', name: 'merchant', meta: { roles: ['2', '3'] },   // ← 角色边界（同上）：只有**商家**与**管理员**可进（`'3'` = 管理员**两边都能进**，用户 2026-10-06 裁定；细粒度归 C3）。⚠️ `name` 的来历同上一行（`/customer`）
    component: AppShell, props: { nav: MERCHANT_NAV, base: '/merchant', role: 'merchant' },
    children: [
      { path: '', name: 'merchantHome', component: HomeTab, props: { base: '/merchant' }, meta: { tab: 'home', layout: 'home-container', public: true } },   // ⚠️ C3 块 2：**同行**加 `name`，理由与 `/customer` 那条逐字相同（父 `:86` 有 name）；无任何 `push({name:'merchantHome'})`。
      { path: 'search', component: GoodsSelect, meta: { layout: 'page', public: true } },
      { path: 'msg', component: MessageCenter, meta: { tab: 'msg', layout: 'page' } },   // ⚠️ 2026-10-09：`component` 由 `ChatRoom` 换成 `MessageCenter`（**同行替换**，两壳各一处）—— 本记录现在还承载「通知」tab（`?tab=notify`）；`meta.tab` 仍是 `'msg'` ⇒ 顶栏「消息」在两个 tab 下都亮
      // ⚠️ 两条 order 记录都设 `meta.tab = 'order'` ⇒ 顶栏「订单中心」**两面都亮**（与路由化前
      //    `curPage === 'order'` 的判据一致）；`meta.orderTab` 才是 `OrderPane` 的分面依据。
      // ⚠️ `order/cart` 是**单条多段子路径**，不嵌第二层 `children`（`OrderPane` 自己渲染两面）。
      { path: 'order', component: OrderPane, meta: { tab: 'order', layout: 'order-container', orderTab: 'order' } },
      { path: 'order/cart', component: OrderPane, meta: { tab: 'order', layout: 'order-container', orderTab: 'cart' } },
      {
        path: 'user',
        component: UserCenter,
        meta: {
          tab: 'user',
          layout: 'user-page',
          logout: 'merchant',   // ⚠️ 商家侧退出多清 `merchantid`；客户侧无此键（见 UserCenter.vue 的 toLogin）
          subMenu: [
            { label: '个人信息', sub: 'info', to: '/merchant/user/info' },
            { label: '上架商品', sub: 'add', to: '/merchant/user/add' },
            { label: '查看商品', sub: 'list', to: '/merchant/user/list' },
            { label: '历史购买记录', sub: 'history', to: '/merchant/user/history' },
            { label: '好友', sub: 'friend', to: '/merchant/user/friend' },
            // ⚠️ A5（2026-10-07 用户裁定）：原第 6 项「消息」（`sub: 'chat'` → `/merchant/user/chat`）**已删** —— 同上（客户侧）
          ]
        },
        children: [
          { path: '', component: UserInfoPage, meta: { sub: 'info' } },
          { path: 'info', component: UserInfoPage, meta: { sub: 'info' } },
          { path: 'add', component: GoodsAdd, meta: { sub: 'add' } },
          { path: 'list', component: GoodsUp, meta: { sub: 'list' } },
          { path: 'history', component: HistoricalOrders, meta: { sub: 'history' } },
          { path: 'friend', component: FriendRoom, meta: { sub: 'friend' } },
          { path: 'chat', redirect: '/merchant/msg' }   // ⚠️ A5：同上（客户侧 `children` 末尾那条的理由逐字适用）
        ]
      }
    ]
  },
  { path: '/product', component: ProductPage, meta: { public: true } },
  { path: '/shoppingCart', redirect: cartLanding },   // R4：僵尸裸页收编 —— 按角色落壳内购物车面（尾部 `cartLanding`；⚠️ 它**必须是函数声明**，理由见尾部注释）
  // 👇 新增搜索页面路由
  { path: '/goodsSelect', redirect: searchLanding }   // R4：僵尸裸页收编 —— 按角色落壳内搜索页（尾部 `searchLanding`）
]

const router = createRouter({
  history: createWebHashHistory(),
  routes, scrollBehavior   // ← A1（2026-10-07）：**同行追加**调用名 —— 定义在文件尾部（函数声明提升，同 `landingFor`/`cartLanding`），理由见尾部「滚动行为」块（净零漂移）。治的病：列表页滚到中间点商品 ⇒ 详情页不置顶
})

// ── 兼容重定向（R1 起；R2 合表，2026-10-05）：旧 `?page=` / `?back=search` 深层链接 ⇒ 新子路由 ──
// 旧形态：`#/customer?page=cart|user|chat`（R1 前由 `CustomerPage.vue` 的 `onMounted` + 两个
//   `route.query` watcher 解读）、`#/merchant?page=order|user|chat`（R2 前同理，在 `MerchantPage.vue`）、
//   `#/…?back=search`（`ProductPage.vue:388` 的「返回」推的）。两壳同一套形状 ⇒ **一张表驱动**。
// ⚠️ **这些映射都不能省**，两条理由：
//   ① R5（商品详情页进壳）**不做** ⇒ `ProductPage.vue:361` / `:363` / `:388` / `:398` / `:400`
//      仍推旧 URL（商家的 3 处 + 客户的 2 处，见 `progress.md` §5-Y 的生产者表）；
//   ② 书签 / 浏览器历史 / 手敲 URL 本来就不受代码控制。
// ⚠️ **R1 时这里写的第 ② 条理由「`MerchantPage.vue` R2 才改」已随 R2 落地而消失** ——
//    商家侧的生产者是 `ProductPage`（理由 ①），不是壳自己了。
// 取舍：认出的 `page` 换成路径并**带走其余 query**（`otherId` / `otherType` / `_t` 是 `ChatRoom.vue`
//   读的会话参数，丢了就打不开对应聊天）；⚠️ **客户侧的 `chat` 与 `msg` 两个键都要收** —— `chat`
//   才是**生产者实际推的**键（`FriendRoom.vue:246`、`ProductPage.vue:447`），`msg` 是 R1 前的历史键
//   （旧 watcher 认它、且手敲 URL 也算）；R1 落码时误写成只收 `msg` ⇒ 「咨询商品 / 好友点聊天」
//   两条客户流程落回主页并丢会话参数，`fix/legacy-chat-tab` 加回（见 progress.md §5-X (16)1）。
//   认不出的 `page` 归位该壳的首页（顺带丢掉脏参数）；
//   `back=search` 换成搜索页**且保留 `back`**（`GoodsSelect.vue:158` 仍靠它触发 `loadState()`）。
// ⚠️ **商家表不带 `cart` / `msg`**：商家侧 `ShoppingCart` 只由 `OrderPane` 的页内开关进入、
//    `msg` 全仓无生产者 —— 收进来等于**凭空造一个旧约定**。客户表收到 `msg` 是因为它**历史上真有**
//    （R1 前的 watcher 认它）。两表的差异是有意的，别「对齐」掉。
const LEGACY_TAB = {
  '/customer': { cart: '/customer/cart', user: '/customer/user', msg: '/customer/msg', chat: '/customer/msg' },   // ⚠️ `chat` 与 `msg` 同指消息页、**都要在**（R1 漏 `chat`，`fix/legacy-chat-tab` 加回）
  '/merchant': { order: '/merchant/order', user: '/merchant/user', chat: '/merchant/msg' }
}

router.beforeEach((to) => {
  const table = LEGACY_TAB[to.path]
  if (!table) return true                  // 只认**精确**的 `/customer` 与 `/merchant`；子路由一律放行
  const { page, ...rest } = to.query
  if (page) {
    const target = table[page]
    return target ? { path: target, query: rest } : { path: to.path }   // 认不出 ⇒ 归位该壳首页
  }
  if (to.query.back === 'search') return { path: `${to.path}/search`, query: to.query }
  return true                              // 无 `page`、无 `back` ⇒ 原样（`/…` 本就是首页）
})

// ── 首页落点（R3 起是一条 redirect；前身是止血笔加的那条守卫，2026-10-05）──────────────
// 治的病：`/` 曾裸挂 `HomePage`（**无外壳** ⇒ 无导航栏）；未登录有 `.login-hint-bar` 引导，已登录
//   则提示条不渲染、三条守卫也不触发 ⇒ **卡在裸首页**（§5-U / §5-V；§5-W 把整页锁改成提示条）。⚠️ 该提示条与三条守卫已于 2026-10-06 删除（改判 2，§5-AG (13)）。
// ⚠️ **R3 把「跳哪」从 `beforeEach` 搬到记录上的 `redirect`** —— 语义不变、位置更惯用；唯一的行为
//   变更是**未登录不再停在 `/`，而是落 `/customer`**（匿名本来就进得了壳：`/customer` 子树无守卫）
//   ⇒ 「`/` 裸挂首页」这个事实**在 R3 终结**（R1/R2 只动各自壳的子树）。逐条见 progress.md §5-Z。
//
// ⚠️ **时序耦合（本函数正确性的唯一前提，别挪）**：`landingFor` 读的是 `auth.js` 的**恢复态**；
//    `main.js` 的顺序是 `await restoreIdentity()` ⇒ `app.use(router)`（初始导航由此触发，
//    redirect 也在这一步求值）。若有人把恢复挪到 `app.use(router)` **之后**，`landingFor` 会读到
//    「匿名」⇒ 已登录也被送进客户壳（**不报错、不崩**，最难查）。改那行之前先看这段。
//
// ⚠️ 「仅首次」不需要额外标志位：全仓**没有任何代码 `router.push('/')`**（两壳顶栏「商城主页」自
//    R1/R2 起都是指向自己壳主页的 `<router-link>` —— **是路由跳转，但目标不是 `/`**）⇒ 本 redirect
//    实际只在「应用入口」那一次 + 「手敲 `#/`」时触发（手敲也被送出 = 有意的）。
//    ⚠️ 代价：`#/?a=b` 的 query 会被丢（redirect 不带 query）—— 实测无生产者，只有手敲会产生。
//
// ⚠️ `ROLE_HOME` 是**唯一**的角色→落地页映射表。C3 加管理员：**只在本表加一行** —— ✅ **2026-10-08 已落**（C3 骨架第一笔）
//    `'3': '/admin'`（另需后端把 `role` 加进 `/user/me` 投影 + 前端加 `#/admin` 路由，见 §5-V）。⚠️ **本行是同行追加、不是插行**：本文件最大被引行是 `:378`，在 `:192` 之后插任何一行都会让 `landingFor` / 两条守卫 / `scrollBehavior` 集体漂移（第一版我就在这儿加了 3 行注释，已压回）。⚠️ 顺带一条**不用改**的：`landingFor`（下面那个函数）本来就是 `ROLE_HOME[getRole()] || …` ⇒ 本表加一行它自动认得 `'3'`，**不需要**给它加分支（原任务清单里那一步是多余的）。
const ROLE_HOME = { '1': '/customer', '2': '/merchant', '3': '/admin' }

/** `/` 的落点：未登录 ⇒ 客户壳；已登录 ⇒ 角色首页（role 优先、`getMerchantId()` 兜底 ——
 *  兜底**不是**边角：`getRole()` 的主来源是 `sessionStorage`，新标签页里那份**是空的**（多账户隔离笔
 *  起 `/user/me` 会答 `role`、恢复态能补上，但**没走成恢复**时仍只有兜底；且它须能区分商家）。 */
function landingFor() {
  if (!isLoggedIn()) return '/customer'   // ← R3 的行为变更点（原：`return true`，停在裸页）
  return ROLE_HOME[getRole()] || (getMerchantId() ? '/merchant' : '/customer')
}

// ── 裸页收编（R4，2026-10-05）──────────────────────────────────────────────────────
// 治的病：`/shoppingCart` 与 `/goodsSelect` 是**基线 `9b87ab9` 起就没人生产**的僵尸路由
//   （全仓 `push` / `replace` / `<router-link>` 皆 0 命中，基线树同样 0），却一直**裸挂** ——
//   无外壳 ⇒ 无导航栏（与 R3 前的 `/` 同病）；且裸 `/shoppingCart` 还渲染一个**死控件**：
//   `ShoppingCart` 的 `showSwitch` 默认 `true`（`ShoppingCart.vue:81`），而该记录**不传 `@toggle`**
//   ⇒ 那个 📋/🛒 开关点了没反应 —— 与 §5-X (16)2 是同一类病症的**第三例**（这一例从基线就在）。
// ⚠️ **为什么是 redirect 而不是删记录**：全仓**没有 catch-all**（`grep pathMatch` 只命中本行注释自身）⇒ 删记录 =
//   手敲该 URL **白屏**（不是 404 页，是空白 + 控制台 warning）。R3 对 `/` 的裁定是「手敲也被送出 =
//   有意的」，本笔沿用。
// ⚠️ **为什么按角色分**（而不是「一律指客户壳」）：`/shoppingCart` 的商家落点**换组件**（`OrderPane`
//   的 `meta.orderTab === 'cart'` 面，落点是 `/merchant/order/cart`）、`/goodsSelect` 的商家落点
//   **换壳**。恒指客户壳会让商家手敲落错壳 —— 正是 R3 的 `landingFor` 刻意避开的错。
// ⚠️ **`to.query` 透传**（本笔与 R3 的唯一手法差别）：R3 丢弃 `/` 的 query 是因为**无生产者**；
//   这里 `kw` 有真实语义（`GoodsSelect.vue:124` 的 watch 读 `route.query.kw`）⇒ 手敲
//   `#/goodsSelect?kw=手机` 能落到搜索页并生效。逐条见 `progress.md` §5-AA。
const ROLE_CART = { '1': '/customer/cart', '2': '/merchant/order/cart' }
const ROLE_SEARCH = { '1': '/customer/search', '2': '/merchant/search' }

/** 当前角色键（`role` 优先、`getMerchantId()` 兜底 —— 与 `landingFor` 同口径、同理由：`getRole()` 的
 *  主来源 `sessionStorage` 在新标签页里是空的；多账户隔离笔起恢复态能补上它，没补上时走兜底）。
 *  匿名 ⇒ 两个都拿不到 ⇒ `'1'` ⇒ 落客户壳 —— 与 R3「未登录也进客户壳」的裁定一致（见 §5-Z (4)）。
 *  ⚠️ 与 `landingFor` 的兜底逻辑**是有意的两处**：不合并是为了不动 R3 刚落码登记的 `:192` / `:197`
 *  行号（用户裁决 D7）；R6 抽 `AppShell` 时可一并收敛。 */
function roleKey() {
  return getRole() || (getMerchantId() ? '2' : '1')
}

/** `/shoppingCart` 的落点。⚠️ **必须是函数声明、不能写成 `const` 箭头** —— `const routes` 在**模块体**
 *  里就要求值（记录上写 `redirect: cartLanding` 是**读这个标识符**），箭头函数此刻还在 TDZ ⇒ 直接
 *  `ReferenceError: Cannot access 'cartLanding' before initialization`。`landingFor` 能用是先例。 */
function cartLanding(to) {
  return { path: ROLE_CART[roleKey()], query: to.query }
}

/** `/goodsSelect` 的落点（同上：函数声明，理由见 `cartLanding`）。 */
function searchLanding(to) {
  return { path: ROLE_SEARCH[roleKey()], query: to.query }
}

// ── 前端路由守卫（`feat/router-guard`，2026-10-06）：未登录不得进「私人面」──────────────
// 治的病：手测批 ⑫ —— **未登录 / 无会话时直开受保护页**（`#/customer/user/info` 等）能打开，
//   但页内请求被闸 401、又无人接管 ⇒ **白屏 / 空壳**。逐条见 `progress.md` §5-AE (3) 与 §5-AG。
//
// ⚠️ **前端守卫 = UX，不是安全边界**。真正的边界在后端**两道**：① `AuthInterceptor` 四档
//    （`app.auth.enabled` 自 2026-10-05 为 `true`）+ `AUTH_WHITELIST`；② `IdentityGuard` 的
//    13 端点入参身份校验。任何人改 JS / 直连 API 都能绕过本守卫 —— 它**只**负责「别把用户
//    送进一个注定空白的页」，不负责拦人。故本笔**不动**任何后端判据与白名单。
//
// ⚠️ **形态 = 默认拒绝 + 显式 `meta.public` 白名单**，与后端 `AUTH_WHITELIST` 同构
//    （`WebAuthConfig.java:90-95` 的原话：「白名单是**公开面**，不是『未登录可达面』」）。
//    为什么不用 `meta.requiresAuth` 逐条标注受保护页：那样**漏标一条 = 静默公开**，
//    而默认拒绝下**漏标一条 = 静默受保护**（错得更安全）。红利：C3 加 `#/admin` 时
//    **自动**落在受保护一侧，不需要回来改这里。
//
// ⚠️ **#1 不变量：`/login` 必须公开**（`:53` 的 `meta.public` 别删）。它同时是本守卫的落点与
//    401 出口的落点（`main.js:49`）⇒ 一旦被标成「要登录」，就是**无限重定向**
//    （未登录 → /login → 守卫又要登录 → /login …）。全站只有这一条会成环。
//
// ⚠️ **公开面 = 7 条**（`meta.public: true`）：`/login`、`/register`（C3 块 2〈用户块〉加：与 `/login` **同一个组件、另一个 tab**，记录落在文件尾那块，理由与判据都在那里）、两壳的**首页**子路由、两壳的**搜索**子路由、
//    `/product`。判据不是感觉，是**后端白名单**：这 7 条下的端点在 `AUTH_WHITELIST` 内（`/register` 靠注册 tab 的 `/user/loginAdd`，`WebAuthConfig.java:101` 已有），匿名
//    拿得到数据（`HomePage` 的 `goodsSelectHome`/`goodstype`、`GoodsSelect` 的 `productSearch`、
//    `ProductPage` 的三个商品端点）。反过来，**保护集 = 「该页全部端点都在白名单外」**——
//    即 R3 的 `navFor()` 在匿名时已经藏起来的那三项（消息 / 购物车|订单中心 / 用户中心）。
//    ⚠️ 两处**各自维护、无代码引用关系**（nav 管呈现、守卫管可达）：改一处**回头核另一处**，
//    不会报错 —— 这是本笔唯一「静默分歧」型的风险。
//    ⚠️ `/…/search` 判**公开**：它有数据（白名单）、`R4` 的 T-new-4 也期望匿名深链生效。
//    原本与「首页点搜索仍弹登录提示」（R3 用户裁决 1，三条守卫在 `HomePage.vue`）**并存**；
//    ⚠️ 该不一致已于 2026-10-06 **结清** —— 三条守卫拆除（改判 2，§5-AG (13)），两处判据现在一致。 ⚠️ **再按语（角色边界，2026-10-06，§5-AG (16)）**：本块的「公开面」在**已登录**一侧**收窄**了 —— 4 条**属某个壳**的公开面（两壳首页 / 搜索）从此只对**匿名**「任何人可进」，**已登录的跨壳用户会被踢回本角色首页**（判据在文件末尾第二条守卫）⇒ 读本块时**别把 `public` 当「谁都能进」**；`/login`、`/register` 与 `/product` 这三条**壳外**公开面**不受影响**（仍是任何人）。
//
// ⚠️ **本条的判据只能是 `isLoggedIn()`**（不能是 `getRole()`）：这条划的是**登录**边界。
//    ⚠️ 原文写「**角色**边界（客户能不能进商家壳）是 RBAC = C3 的事，本笔不做」**已于 2026-10-06 由用户改判**（C3 前必须先做）⇒ 落在**文件末尾的第二条守卫**（§5-AG (16)）；分工 = 本条管**匿名**、末尾那条管**壳归属**。⚠️ **两条的先后有意**：`public` 只豁免匿名 —— 若反过来先看 `public`，两壳首页/搜索这 4 条会漏检（正是用户实测到的现象）。
//    ⚠️ `isLoggedIn()` **不是响应式**（读模块态 + `sessionStorage`）—— 守卫**每次导航都跑**，
//    是全仓**唯一**吃它「取一次就够」性质的地方（`navFor` 那边相反，见 `config/nav.js` 的注释）。
//    ⚠️ 时序前提：`landingFor` 上方那段「`main.js` 必须先 `await restoreIdentity()` 再
//    `app.use(router)`」的耦合**本笔之后后果更强** —— 顺序坏了不只是「已登录被送进客户壳」，
//    还会「已登录被判未登录 ⇒ 打不开任何受保护页」。
//
// ⚠️ **未匹配路径放行**（`to.matched.length === 0`）：全仓**没有 catch-all**，那种 URL 无组件
//    可挂 ⇒ **零请求**，不在「受保护页」语义内。改它是 scope creep，本笔保持现状（白屏 + warning）。
//
// ⚠️ **为什么这段在文件末尾**：全仓对 `router/index.js` 有 **27 个**行号引用（`docs/` 26 + `config/nav.js:20` ⚠️ 原文写 `config/nav.js` 的 `30` 行 —— C3 块1·I2（2026-10-09）查明那是个**陈旧错址**：nav.js 里对 router 的引用只在那一行，本笔顺带订正，行号按本笔编辑后的实况写 ⚠️ **I2 按语（2026-10-09）**：「27 个」为当时实测；本笔实测 **91 处 / 62 个不同行号**，随文档增长而变、**不作契约**），
//    **最大 `:227`** ⇒ 插在 `:239`(`searchLanding` 尾) 与 `:241`(`export default`) 之间**零漂移**
//    （与 R3 §5-Z (10) 的「新增行落在被引用行最大行号之后」同一手法）。
//    ⚠️ 放这儿**不影响语义**：注册序 = 执行序，但 `LEGACY_TAB` 归一化返回的是**新导航**
//    （`pushWithRedirect` 递归）⇒ 全部守卫在新目标上**重跑一遍**，所以「谁先注册」对
//    `#/customer?page=user` 这类旧 URL 的判定**不敏感**（实测，见 §5-AG 的探针）。
//
// ⚠️ **`redirect` query 的契约**：值 = `to.fullPath`（**带 query** —— `#/product?merchantId=..&goodName=..`
//    的参数是 `ProductPage` 的必填输入，丢了就回不到原状态）。消费方是 `LoginPage.vue` 的
//    `landingAfterLogin(role)`：它**角色校验 + 公开面豁免**后才回原目标，非法/缺失一律落角色首页
//    （⇒ 无 query 时与本笔之前**逐字等价**）。⚠️ 改这里的 query 键名要同步改 `LoginPage.vue`
//    与 `main.js:49` 两处 —— 三处是**同一条契约**，靠 `grep 'redirect'` 一次全命中。
router.beforeEach((to) => {
  if (to.matched.length === 0) return true      // 未匹配：无组件可挂、零请求，保持现状
  if (to.meta.public === true) return true      // 公开面（6 条，见上）
  if (isLoggedIn()) return true                 // 已登录：放行（⚠️ 本条只管**登录**；**角色**边界 = 文件末尾第二条守卫，2026-10-06，§5-AG (16)）
  return { path: '/login', query: { redirect: to.fullPath } }
})

// ── 角色边界守卫（2026-10-06 用户裁定：C3 前必须做 —— 并入本笔；登记见 progress.md §5-AG (16)）──
// 治的病（用户实测）：已登录**客户**手敲 `#/merchant/user/info` ⇒ 进**商家壳** + 拉**客户**数据
//   （视觉与数据不一致）；反向同理。此前全仓**没有任何角色判据**（上面那条守卫的原文写着
//   「只判登录，不判角色 —— C3」）⇒ 本笔把角色边界提前落地。
//
// ⚠️ **判据 = 路由记录上的 `meta.roles`（单一事实源）**：不新建文件、不新增 import、不另抄一张表。
//   两条壳父记录（`:55` / `:86`）声明「谁能进这个壳」——客户壳 `['1','3']`、商家壳 `['2','3']`
//   （`'3'` = 管理员，**两边都能进**，用户 2026-10-06 裁定；细粒度归 C3）。
//   `meta` 沿 `matched` 链合并 ⇒ 子路由**自动继承**，无需逐条标（与 `meta.public` 同一机制）。
//   ⚠️ `LoginPage.landingAfterLogin` 读的是**同一份数据**（`resolve(target).meta.roles`）——
//      该文件 `:265-268` 早已写死这条口径：「角色校验不另抄一张表，而是复用守卫的 `meta`」。
//   ⚠️ 兜底复用**本文件**的 `roleKey()`（`:225`，函数声明提升）：`getRole()` 的主来源是
//      `sessionStorage`，新标签页里可能为空 ⇒ 与 `landingFor` / `cartLanding` **同一套兜底**，
//      不新造第三份（⚠️ R6 裁决 D10 要合并的那两处**本笔不碰**，避免与待定笔撞车）。
//
// ⚠️ **顺序是本笔唯一容易做错的地方**：匿名判据（第一条 `return`）**必须在** `meta.roles` 之前 ——
//   两壳**首页 / 搜索页是 `public`**，但它们**属某个壳**；若先看 `public` 就放行，已登录的跨壳
//   用户会**漏检**（那正是用户实测的那 4 条）。⇒ 口径：**`public` 只豁免匿名；已登录一律再过 `roles`**。
//
// ⚠️ **为什么另起一条 `beforeEach`、而不是并进上面那条**：上面那条的注释块 + 5 行体被
//   `progress.md` §5-AG (3)/(6) 按 **`:241-299`** / **`:298`** 整段引用 ⇒ 在它内部插行会让那两处
//   **漂移**。⇒ 沿用「新增行落在被引行最大行号之后」的既有手法（R3 §5-Z (10) 先例）。
//   ⚠️ 注册序 = 执行序：上面那条**先**跑 ⇒ 匿名在受保护页**先**被改成 `/login?redirect=…`
//      （该次导航到此为止），角色判定只在「已登录」时才可能生效 —— 与「先登录、后角色」的直觉一致。
//
// ⚠️ **踢回哪 = 本角色首页（`ROLE_HOME`）、且不带 `redirect`**：目标页对这位用户**本就不合法**，
//   登录完再回去也没用（同 `landingAfterLogin` 对非法目标的口径）。⚠️ `ROLE_HOME`（`:192`）今天
//   只有 `'1'`/`'2'`；`'3'` 由 C3 补 —— 管理员两壳都进 ⇒ **走不到这里**，`|| '/customer'` 只是兜底。
router.beforeEach((to) => {
  if (!isLoggedIn()) return true                                        // 匿名：两壳首页 / 搜索照旧（R3 裁定**不变**）
  const roles = to.meta.roles
  if (!Array.isArray(roles) || roles.includes(roleKey())) return true   // 无 `roles`（`/login`、`/product`）或本壳 ⇒ 过
  return { path: ROLE_HOME[roleKey()] || '/customer' }                  // 跨壳 ⇒ 送回**本角色**首页
})

// ── 滚动行为（A1，2026-10-07）：路由切换置顶 / 后退还原 ──────────────────────────────
// 治的病（用户实测）：列表页（`GoodsSelect`）滚到中间 → 点商品进详情 ⇒ 详情页**停在差不多的
//   滚动位置**，不回到顶部。根因**不在**详情页自身，而是「整页滚动」这件事 **7c 之后才成立**：
//   7c 把 `.app-shell` 由 `height:100vh` 改成 `min-height:100vh`（`AppShell.vue:261-275`）⇒
//   滚动由「壳内容器滚动」变成「整页（document）滚动」。而 `scrollBehavior` **全仓本来就没配**
//   ⇒ 既不置顶、也不还原，于是窗口的原滚动位置被原样带进新页。
//   ⚠️ 反过来说：7c 之前补它也**没用** —— 那时滚的是 `.app-shell` 这个容器，不是 `window`
//   （`scrollBehavior` 默认只对 window 生效）。本笔与 7c 是配套的两半，别只留一半。
//
// 口径（用户给定）：有 `savedPosition`（后退 / 前进）⇒ 还原；其余（跳转）⇒ 置顶。
// ⚠️ **若**将来出现「同路径的筛选 / 翻页也被置顶」（本笔实测未出现：`GoodsSelect` 的筛选与
//   翻页各自改的是 query / 页内数据，见探针记录）⇒ 加 `if (to.path === from.path) return false`。
//   本笔**不加**：`不猜、不自裁`，没复现的分支不进代码。
//
// ⚠️ **为什么定义在文件尾部、而调用点在第 133 行**：全仓对 `router/index.js` 有 27+ 处行号引用，
//   **最大 `:298`**（`progress.md:1405` / `:1525`，另有 `:241-299` 整段引用）⇒ 新增行必须落在
//   被引行**之后**（R3 §5-Z (10) 与 §5-AG (16) 的既有手法）；`createRouter` 那行**同行追加**
//   调用名 ⇒ 两处合计**零行号漂移**。函数声明提升，同 `landingFor` / `roleKey` / `cartLanding`。
function scrollBehavior(to, from, savedPosition) {
  if (savedPosition) return restoreScrollWhenSettled(savedPosition)   // 后退 → 恢复原位（⚠️ 用户 2026-10-08 裁决 ⑤：**不能直接 `return savedPosition`** —— 目标页的高度是**异步**长出来的，当场滚会被截断；实测与修法见文件尾部 `restoreScrollWhenSettled` 的注释。函数声明提升，同 `landingFor`）
  return { top: 0 }                         // 跳转 → 置顶
}

export default router

// ── 滚动还原：等目标页「长得够高」再滚（⚠️ **用户 2026-10-08 裁决 ⑤**，修 A1 的残留缺陷）────────────
// 病（A1 落地时已抓到根因，`美化/dbg-a1d.mjs` 逐帧采样）：`scrollBehavior` 原样 `return savedPosition`
//   时，目标页的高度还没长齐 —— 从首页回退的那一帧 `docH` 只有 **1350**（最大滚动量 = 1350 − 900 = **450**），
//   浏览器的 `scrollTo` 当场被**截断**成 450；两帧后 `docH` 长到 **1941**（最大 1041），而 `y` **停在 450**
//   —— 没有第二次补滚。⇒ 实测：设定 **300 还原 300 ✅**；设定 **600 / 900 / 1041 一律还原 450 ❌**。
// 修法：返回 Promise。`scrollBehavior` 的返回值允许是 `Promise<ScrollPosition>`，**Vue Router 在它
//   resolve 之后才执行 `scrollTo`** ⇒ 把「什么时候滚」从「立刻」改成「够得着再滚」。三个出口：
//     ① `maxScroll() >= want - 1` ⇒ 还原（快路径；本机后端实测几帧内就满足）
//     ② 1500ms 兜底 ⇒ 仍按**当时**的最大量尽力还原（目标页始终长不到原高时，行为 = 修之前的那一档）
//     ③ `location.hash` 变了 ⇒ `resolve(false)` = **本次不滚**
//
// ⚠️ **为什么要有第 ③ 条**：rAF 轮询最长跑 1500ms，这期间用户完全可能又导航走了（连点两次后退就是）
//   —— 那时再滚，滚的是**新页**。判据取调用时刻的 `location.hash` 快照（hash 是本 app 唯一的路由
//   载体，`createWebHashHistory`）。⚠️ 哨兵值是 `false` 而**不是** `{ top: 0 }`：后者会把新页**置顶**，
//   同样不是用户点「后退」想要的结果（`false` 在 Vue Router 里就是「这一发不滚」）。
//
// ⚠️ **为什么这段不写进 `scrollBehavior` 本体**：那 4 行（`:354-357`）**被文档按行号引**
//   （`slot-registry.md:2332`），新增行必须落在被引行**之后** ⇒ 挪到 `export default router` 之后，
//   靠**函数声明提升**调用（同 `scrollBehavior` / `landingFor` / `cartLanding` 的既有手法）。
function restoreScrollWhenSettled(savedPosition) {
  const want = savedPosition.top
  const h = location.hash
  const maxScroll = () => document.documentElement.scrollHeight - window.innerHeight
  return new Promise((resolve) => {
    const tick = () => {
      if (location.hash !== h) return resolve(false)               // ③ 这一发已作废（用户又走了）⇒ 不滚
      if (maxScroll() >= want - 1) return resolve(savedPosition)   // ① 够得着了 ⇒ 按原样还原（连 `left` 一起）
      requestAnimationFrame(tick)
    }
    tick()
    setTimeout(() => resolve(location.hash !== h ? false : savedPosition), 1500)   // ② 兜底（rAF 在后台标签页会被节流 ⇒ 不靠它兜底；第 ③ 条在这里也复查一次）
  })
}


// ── C3 骨架第一笔（2026-10-08）：管理员壳 `/admin` ────────────────────────────────────────
// ⚠️ **为什么用 `router.addRoute` 而不是往上面的 `routes` 数组里插一条**：
//   数组在 `:51`-`:129`，而全仓对 `router/index.js` 的最大被引行是 **`:378`**
//   （`slot-registry.md` 引 `:354-357`）⇒ 在数组里插**任何**一行，都会让 `:192`（`ROLE_HOME`）、
//   `:197`（`landingFor`）、`:329-334`（角色边界守卫）、`:354-357`、`:359` 集体漂移。
//   本仓的既有手法是「新增行必须落在最大被引行**之后**」⇒ 挪到文件末尾。
//   ⚠️ 与 `scrollBehavior` / `landingFor` 那几处**不同**：那些靠**函数声明提升**（定义在尾部、
//      调用在中间）；这里靠的是**语句按序执行** —— 它在 `createRouter` 之后，改不到 `routes`
//      数组，所以只能 `addRoute`。两条路都满足「零漂移」，但机制别混。
//
// ⚠️ `meta.roles: ['3']` 与另两壳**不同**：`/customer` 是 `['1','3']`、`/merchant` 是 `['2','3']`
//   （管理员两边都能进）；管理员的**专属**壳只有这一个 ⇒ 只放行 `'3'`。`roles` 沿 `matched`
//   链合并 ⇒ 子路由自动继承（与另两壳同一机制），不需要逐条标。
// ⚠️ 守卫**不用改**：第一条（默认拒绝 + `meta.public` 白名单）本就把 `/admin` 自动归到
//   「受保护」一侧（`:253-254` 的注释早写了这条）；第二条（角色边界）在 `ROLE_HOME` 补了 `'3'`
//   之后，管理员跨壳会被送回 `/admin` —— 也是自动的。
// ⚠️ `AdminShell` 走**懒加载**（`() => import(...)`）而非顶部静态 import：在顶部加一行 import
//   同样会让 `:378` 之前的所有行号漂移 —— 懒加载把这条边收进了 `addRoute` 这一句里。
// ⚠️ `children` 里那条**空路径**记录是必需的：没有它，`#/admin` 会渲染出一个「只有侧边栏、
//   内容区空白」的外壳（父记录命中但 `<router-view>` 没有子记录可渲染）。
//
// ⚠️ **C3 骨架第二笔（2026-10-08）**在这条 `children` 里加了两条域入口；
//   **第三笔（同日）**再把它们改名并补 11 条子项 —— 现共 **14** 条记录（1 概览 + 2 域 + 11 子项）：
//     · `key`   —— **所属域**的点亮键，与 `config/admin-nav.js` 的 `ADMIN_SIDEBAR[].key` **逐字相同**；
//     · `sub`   —— **子项**的点亮键（域入口记录没有这个键），与 `subs[].key` **逐字相同**；
//     · `title` —— 顶栏面包屑（`AdminShell.vue` 读 `route.meta.title`）。
//   ⚠️ **不加 `meta.roles`**：父记录的 `['3']` 沿 `matched` 链**合并**下来（与另两壳同一机制）。
//   ⚠️ **11 条占位记录共用 `StubPage.vue`**（2026-10-08 少一条：`observe/audit` → 真页 `AuditPage.vue`；
//      2026-10-09 再少一条：`business/notify` → 真页 `NotifyPage.vue`，C3 块 1 · I2；2026-10-09 同日 C3 块 2〈用户块〉再少一条：`business/accounts` → 真页 `AccountsPage.vue`）—— 余下 **10** 条**只差数据**，没有一处结构差异；
//      而那正是「不该各写一个文件」的判据（见该组件块首注）。
//   ⚠️ **第三笔的两处命名更正**（用户裁决）：域名「风控域」→「**可观测域**」；
//      URL `/admin/biz` → `/admin/business`、`/admin/risk` → `/admin/observe`。旧名**已废**。
//   ⚠️ **URL 裁决（2026-10-08，第三笔过审后）**：两条子项 URL 改名 —— `content` → **`moderation`**、
//      `flows` → **`timeline`**（其余 9 条保留；理由见 `config/admin-nav.js` 块首）。`meta.sub` 同步改。
//   ⚠️ 以上所有条**都在本文件最大被引行 `:378` 之后**（那份说明在本块上方），故加行**零漂移**。
// ⚠️ **10 条占位记录共用同一个组件**（2 个域入口 + 8 个子项；`observe/audit`、`business/notify`、`business/accounts` 已换真页）⇒ 抽成局部常量，
//   免得同一句 `import()` 抄 11 遍。改名的前因见 `StubPage.vue` 块首（它第二笔叫 `DomainStub`）。
//   ⚠️ 这是**局部**常量（`addRoute` 之前、`:378` 之后），不是顶部 import —— 顶部加行会漂移。
const AdminStub = () => import('../views/admin/StubPage.vue')

router.addRoute({
  path: '/admin',
  meta: { roles: ['3'] },
  component: () => import('../views/AdminShell.vue'),
  // ⚠️ 子项记录写**三个键**，与 `config/admin-nav.js` 的 `ADMIN_SIDEBAR[]` / `subs[]` 逐字对应
  //    （改一处必须改两处）：
  //      · `key`   = **所属域** —— 子项页上也写域的键 ⇒ 侧边栏的**域行保持点亮**，进子项不熄父灯；
  //      · `sub`   = 子项自己 —— 侧边栏据此点亮**子项行**；
  //      · `title` = 顶栏面包屑。
  // ⚠️ 子项路径用**扁平写法**（`'business/accounts'`），**不**嵌进 `business` 记录当 children ——
  //    嵌套要求域入口页自己再放一个 `<router-view>`，而它是**叶子**占位页，没有第二个出口。
  children: [
    { path: '', meta: { key: 'ov', title: '概览' }, component: () => import('../views/admin/AdminHome.vue') },

    { path: 'business', meta: { key: 'business', title: '业务域' }, component: AdminStub },
    { path: 'business/accounts',   meta: { key: 'business', sub: 'accounts',   title: '用户与商家治理' }, component: () => import('../views/admin/AccountsPage.vue') },   // C3 块 2〈用户块〉：占位 → 真页（**同行替换**，照 `:456` notify 那条的做法）
    { path: 'business/catalog',    meta: { key: 'business', sub: 'catalog',    title: '商品与类目治理' }, component: AdminStub },
    { path: 'business/orders',     meta: { key: 'business', sub: 'orders',     title: '订单治理' },       component: AdminStub },
    { path: 'business/marketing',  meta: { key: 'business', sub: 'marketing',  title: '营销运营' },       component: AdminStub },
    { path: 'business/moderation', meta: { key: 'business', sub: 'moderation', title: '消息与内容治理' }, component: AdminStub },
    { path: 'business/notify',     meta: { key: 'business', sub: 'notify',     title: '系统通知' },       component: () => import('../views/admin/NotifyPage.vue') },   // C3 块1·I2：占位 → 真页（同行替换）

    { path: 'observe', meta: { key: 'observe', title: '可观测域' }, component: AdminStub },
    { path: 'observe/attack-sim', meta: { key: 'observe', sub: 'attack-sim', title: '攻击模拟器' }, component: AdminStub },
    { path: 'observe/metrics',    meta: { key: 'observe', sub: 'metrics',    title: '指标面板' },   component: AdminStub },
    { path: 'observe/timeline',   meta: { key: 'observe', sub: 'timeline',   title: '状态流转' },   component: AdminStub },
    { path: 'observe/logs',       meta: { key: 'observe', sub: 'logs',       title: '实时日志' },   component: AdminStub },
    { path: 'observe/audit',      meta: { key: 'observe', sub: 'audit',      title: '权限与审计' }, component: () => import('../views/admin/AuditPage.vue') },

    // C3 块 2〈用户块〉补丁 B1（2026-10-09 手验后）：管理员**共享收件箱**从 `AccountsPage.vue`
    //   里提出来、成侧边栏**顶层独立项**（原来它嵌在那页当第 ⑤ 块，页内挤了 4 块）。
    //   ⚠️ `NotifyInbox` 按 `route.path.startsWith('/admin')` 派生收件人 ⇒ 挂在 `/admin/**` 下
    //   自动取 `ToType='admin'` / `ToId='*'`（哨兵见该文件 `:99-103`）—— **组件一行不改**。
    //   ⚠️ 本行**追加在 children 末尾**（`:462` 是全仓对该文件的最大被引行，插在它之前会集体漂移）。
    { path: 'inbox', meta: { key: 'inbox', title: '通知收件箱' }, component: () => import('../views/NotifyInbox.vue') },
  ],
})

// ── C3 块 1 · I2（2026-10-09）：两壳「系统通知」子路由 —— ⚠️ 同日**已改判为 redirect** ──────────
// ⚠️ **本块的口径当天变过一次，两次都照落**（不是同一句话的两种说法，别只读一半）：
//   ① 建块时（用户口径 A）：**通知与消息的入口必须分开**（后端也是两张表、两套端点、两个 Controller）
//      ⇒ 两壳顶栏各多一项「通知」（`config/nav.js`，`badge:'notify'`），落点由本块两条 `addRoute` 补。
//   ② 同日稍后（用户口径 B，**现行**）：**两个入口合并成一个** —— 顶栏那两项**已从 `nav.js` 删除**
//      （留下墓碑、**净零行数**），落点收成 `views/MessageCenter.vue` 的**页内 tab**。
//   ⇒ 本块今天**只剩两条 redirect**（旧地址没死、只是改道）；`component: NotifyInbox` 那两行**已撤**
//      —— `NotifyInbox.vue` 改由 `MessageCenter.vue` 内嵌，顶部 `:15` 的静态 import 也随之一并撤掉。
//
// ⚠️ **为什么又用 `addRoute`、而不是往上面的 `routes` 数组 / 两壳的 `children` 里插一条**：
//   全仓对 `router/index.js` 的**最大被引行**是 `:462` —— 即 C3 骨架 `/admin` 块的末行
//   （`context-packs/c3-block-1-infra.md:74` 引 `:396-462`；另 `slot-registry.md:2332` 按行号钉着
//   scrollBehavior 那 4 行 `:354-357`，`progress.md` 亦引 `:378`）——
//   而两壳的 `children`（`:57-83` / `:88-122`）、`ROLE_HOME`（`:192`）、`landingFor`（`:197`）、
//   两条守卫（`:241-299` / `:329-334`）**全都在这条线之前** ⇒ 往里插**任何**一行，它们集体漂移。
//   本仓的既有手法就是「新增行落在最大被引行**之后**」（R3 §5-Z (10) 起，本文件已用过三次）。
//
// ⚠️ **父子挂接靠 `name`** ⇒ 上面两个父记录各**同行**加了一个 `name`（`:55` / `:86`，净零行数）。
//   为什么非得这样：`addRoute` 的第一个参数**只认 name**（不认 path）。
//   为什么不用「顶栏入口指去别处」绕开：那样「通知」就不在两个壳里 —— 顶栏与页面分家，正是最坏的结果。
//
// ⚠️ **改成 redirect 而不是删记录**：全仓**没有 catch-all**（见 `:208-210` 那条 R4 口径）⇒
//   删记录 = 手敲 `/customer/notify`、旧书签、浏览器历史一律**白屏**
//   （就是 R4 对 `/shoppingCart` / `/goodsSelect` 的同一条理由，此处第四次适用）。
//
// ⚠️ `redirect` 写成**带 query 的字符串**（`'/customer/msg?tab=notify'`）：vue-router 会解析它
//   ⇒ 落点是「消息中心的**通知 tab**」，不是「消息中心的默认 tab」—— 那正是本笔要治的错。
//   ⚠️ 两条记录**不再写 `meta`**：`tab` / `layout` 由落点记录（`:60` / `:91`）的 `meta` 决定。
//   合并后「通知」**不再是一个独立的高亮键**（`nav.js` 里那项已删）⇒ 写一个没有消费者的
//   `meta.tab: 'notify'` 就是**死配置**（判据同 R6 删 `.empty-page` 那条：全仓 0 使用）。
//   ⚠️ 顺带作废的一条：旧文里「`meta.tab: 'notify'` 与 `nav.js` 那一项逐字相同」**已不成立**
//   —— 那是口径 A 下的契约，口径 B 下那个键连同它那一项一起没了。**别照旧文恢复**。
//
// ⚠️ `views/NotifyInbox.vue` 的**懒加载已撤**（原为 `const NotifyInbox = () => import(...)`）：
//   它现在由 `MessageCenter.vue` **静态**引入（`ChatRoom` 亦然），而 `MessageCenter` 由本文件
//   顶部 `:15` 静态引入 ⇒ 这一页今天随主包一起下。**没有**在顶部新加 import 行 ⇒ 行号零漂移。

router.addRoute('customer', { path: 'notify', redirect: '/customer/msg?tab=notify' })
router.addRoute('merchant', { path: 'notify', redirect: '/merchant/msg?tab=notify' })

// ══════════════════════════════════════════════════════════════════════════════════════════
// ── C3 块 2〈用户块〉（2026-10-09）：注册直达 + 商家入驻 ─────────────────────────────────────
//
// ⚠️ **为什么两条都走 `addRoute`、而不是往 `routes` 数组 / 两壳的 `children` 里插一条**：同上（`:478-484`）——
//   全仓对 `router/index.js` 的**最大被引行**在 `:462`（C3 骨架 `/admin` 块的末行），
//   而 `routes` 数组（`:51-124`）、两壳的 `children`（`:57-83` / `:88-122`）、`ROLE_HOME`（`:192`）、
//   `landingFor`（`:197`）、两条守卫（`:241-299` / `:329-334`）**全在那条线之前**
//   ⇒ 往里插**任何**一行，它们集体漂移。本仓既有手法 = 「新增行落在最大被引行**之后**」（第五次适用）。
//
// ⚠️ **`/register` 不做成独立页**：注册面就是 `LoginPage` 的**另一个 tab**（`isSlide`）——
//   组件同一个、样式同一套；另起一页 ⇒ 两处注册表单立刻分叉（改一处忘一处）。
//   本记录只多带一个 `meta.register`，它**只当「初始 tab」**用（`LoginPage.vue` 的 `data()` 读它），
//   **不参与**任何守卫判据。
// ⚠️ **`meta.public: true` 的判据仍是后端白名单**：注册 tab 的 POST 打 `/user/loginAdd`，
//   它**本来就在** `AUTH_WHITELIST` 内（`WebAuthConfig.java:101`）⇒ 这一条**不是**「把受保护端点放行」，
//   只是给同一个组件补一个能直达注册 tab 的地址。⇒ 公开面 **6 → 7 条**：
//   `:262` / `:263` 那两处的数目与 `:271` 的壳外列举**已同步改**（三处一处不改就是静默分歧）。
//
// ⚠️ **「商家入驻」挂成 `customerUser` 的*子页***（不新开壳、不进 `/merchant/*`）：
//   入口在用户中心左菜单（`:72` 同行追加的那项，`sub: 'apply'`）。`addRoute` 的第一个参数**只认 name**
//   ⇒ 靠 `:63` 同行加的那个 `name: 'customerUser'`。落在此处的 `meta.sub` 让左菜单高亮在「商家入驻」。
//   ⚠️ **为什么不挂 `/merchant/*`**：此刻用户**还不是**商家（`login.Role` 仍是 `'1'`），
//   进商家壳会被**壳归属守卫**（`:329-334`）踢回本角色首页 —— 页面对用户来说等于不存在。
// ══════════════════════════════════════════════════════════════════════════════════════════

router.addRoute('customerUser', { path: 'apply', component: () => import('../views/MerchantApplyPage.vue'), meta: { sub: 'apply' } })
router.addRoute({ path: '/register', component: LoginPage, meta: { public: true, register: true } })
