<template>
  <!-- ══════════════════════════════════════════════════════════════════════
       公域搜索页 · A 版无左栏全宽布局（2026-10-19 命令四重写）
       ──────────────────────────────────────────────────────────────────────
       分类 / 排序 / 筛选全部横排在顶部；结果网格与首页「为你推荐」同款瀑布流。
       功能一个没少：类目接线、搜索、国补筛选、无限滚动、回程与 saveState。
       ⚠️ 本模板行数刻意保持 148 行：`<script setup>` 被外部按行号引用，脚本起始行不能漂。
       ⚠️ 分类树 `<aside class="cat">` 整块从 DOM 删除（不是隐藏），与用户中心剥侧栏同一口径。
       ⚠️ 公域 / 私域两壳共用本页（`/customer/search` 与 `/merchant/search`），行为一致。
       ══════════════════════════════════════════════════════════════════════ -->
  <div class="goods-select-view">
    <nav class="crumb">
      <a>首页</a><span class="sp">/</span><span class="cur">商品搜索</span>
    </nav>

    <header class="head">
      <div class="head-row">
        <h1 class="page-h">商品搜索</h1>
        <span class="head-note">结果与首页「为你推荐」同一套卡片</span>
      </div>
      <form class="search" @submit.prevent="doSearch">
        <input
          v-model="searchKeyword"
          type="search"
          placeholder="搜索商品、品牌、分类"
          aria-label="搜索商品"
        />
        <button class="go" type="submit">搜索</button>
      </form>
    </header>

    <!-- ⚠️ 分类筛选（C3 块 3 接线，2026-10-10）：A 版公域页不带左栏 ⇒ 一级类目横排成 chip，
         选中一级后在其下方展开二级。数据源仍是 **既有** `/goods/goodstype`、点击仍走
         **新端点** `/goods/categoryGoods`（传 TypeId，不是类目名）；一级自己也能筛，
         故「选中」与「展开」是两件事（见 `pickCat` 的注释）。
         ⚠️ 二级在原实现里是「选中一级才展开」；横排后改成一行子 chip，展开条件不变。
         ⚠️ `kidsOf` 为空的一级不再画「更多」死按钮（原占位随树一起退役）。 -->
    <section class="cats" aria-label="商品分类">
      <span class="cat-h">全部分类</span>
      <div class="cat-l">
        <button
          type="button"
          class="chip"
          :class="{ on: activeType === null }"
          @click="pickCat(null)"
        >全部商品</button>
        <button
          v-for="t in roots"
          :key="t.Id"
          type="button"
          class="chip"
          :class="{ on: activeType === t.Id, open: isOpen(t.Id) }"
          @click="pickCat(t.Id)"
        >{{ t.GoodType }}</button>
      </div>
      <div v-if="openRoot !== null && kidsOf(openRoot).length" class="cat-sub">
        <span class="lbl">{{ (roots.find((t) => t.Id === openRoot) || {}).GoodType || '全部商品' }}</span>
        <button
          v-for="c in kidsOf(openRoot)"
          :key="c.Id"
          type="button"
          class="chip sm"
          :class="{ on: activeType === c.Id }"
          @click="pickCat(c.Id)"
        >{{ c.GoodType }}</button>
      </div>
    </section>

    <div v-if="shownList.length === 0" class="empty">
      <div class="big">没有找到相关商品</div>
      <div>换个关键词，或去掉品牌与价格区间限制再试一次。</div>
    </div>

    <template v-else>
      <!-- ⚠️ 排序 / 筛选条（预留位 · C3〈搜索〉）：仍照稿落 `disabled` + `data-slot` + `title`
           三件套，只是从「左侧竖栏」改为「顶部横向」；真功能链路与 N 值见各控件 title，
           台账 `docs/agent/slot-registry.md`（条目行号随本笔版面调整，仅登记）。 -->
      <div
        class="toolbar"
        data-slot="预留 · 排序 / 筛选（逐项 4～6 处 · 均超档）"
        title="待 C3〈搜索〉——每个控件自己的 N 见其 title。共同点：都要改 searchByKeyword 的 SQL 并加参数，最短链路 4 处（GoodsDao + GoodsService + GoodsController + GoodsSelect.vue）"
      >
        <div class="trow">
          <div class="tgroup">
            <span class="lbl">排序</span>
            <button class="sort on" disabled title="综合 —— 真功能：就是现在的默认返回序，前端不加参数即可，N=0">综合</button>
            <button class="sort" disabled title="销量 —— 动现有代码 4 处（GoodsDao 加 join productsales + GoodsService + GoodsController + GoodsSelect.vue），**超档**。⚠️ 且 productsales.OrderVolume 全仓硬编码传 0、无 SELECT 读出 ⇒ 就算排了序也是全员并列">销量</button>
            <button class="sort" disabled title="价格升序 —— 动现有代码 4 处（GoodsDao 的 SQL 加 ORDER BY + GoodsService + GoodsController + GoodsSelect.vue），**超档**">价格 ↑</button>
            <button class="sort" disabled title="价格降序 —— 同上，4 处，**超档**">价格 ↓</button>
            <button class="sort" disabled title="有货优先 —— 动现有代码 5 处（goods 表**无库存列**：mall_management_sql.sql + GoodsDao + GoodsService + GoodsController + GoodsSelect.vue），**超档**">有货</button>
          </div>
          <!-- ⚠️ 「已加载」不是「共」：分页后本页不知道总数（端点不回 total），
               写「共」会把当前已加载数说成全量。 -->
          <span class="right">已加载 {{ shownList.length }} 件</span>
        </div>
        <div class="trow">
          <div class="tgroup">
            <span class="lbl">品牌</span>
            <button class="pick" disabled title="品牌筛选 —— 动现有代码 ≥5 处（goods 表**无品牌列**：mall_management_sql.sql + GoodsDao + GoodsService + GoodsController + GoodsSelect.vue），**超档**">全部品牌<span class="car"></span></button>
          </div>
          <div class="tgroup">
            <span class="lbl">价格</span>
            <div class="range">
              <input type="text" value="0" disabled title="价格区间 —— 动现有代码 4 处（GoodsDao 的 SQL 加 BETWEEN + GoodsService + GoodsController + GoodsSelect.vue），**超档**" />
              <span class="dash">—</span>
              <input type="text" value="500" disabled title="同上，4 处，**超档**" />
            </div>
          </div>
          <div class="tgroup">
            <span class="lbl">更多</span>
            <button class="pick" disabled title="更多筛选（抽屉）—— 上面各项的聚合，动现有代码 ≥6 处（各项之和 + 抽屉组件），**超档**">更多筛选<span class="car"></span></button>
          </div>
          <div class="tgroup">
            <span class="lbl">国补</span>
            <button class="pick" :class="{ on: subsidyOnly }" :disabled="!subsidyReady" :title="!isLoggedIn() ? '国补商品 —— 登录后可用（端点不在白名单，匿名会 401）' : (subsidyCodes.size ? '只看国家补贴商品（C3 块 5 · commit 3 已有端点 /marketing/subsidy/goods；本页是**客户端过滤**，未改任何 SQL）' : '当前没有国补商品（规则未配 / 商家未确认 / 不在期）')" @click="subsidyOnly = !subsidyOnly">国补商品</button>
          </div>
        </div>
      </div>

      <!-- ⚠️ 商品卡沿用公共组件（C3 块 5，2026-10-12）；`from="search"` 不能省 ——
           `ProductPage.goBack` 按 `route.query.from === 'search'` 决定回程，
           ⚠️ 网格与首页「为你推荐」同一套瀑布流：`column-count: 3` +
              `break-inside: avoid` + 12px 列距 / 下距（用户命令三、四要求两页一致）。
           ⚠️ 卡片本体不设 `display:flex`：多列布局里 flex 列会强行拉平，
              破坏按内容高度收边的能力。
           `:before-nav="saveState"` 承担原来「跳转前保存」的同一件事。 -->
      <div class="grid">
        <ProductCard v-for="goods in shownList" :key="goods.Id" :goods="goods" :before-nav="saveState" from="search" />
      </div>

      <!-- 无限滚动哨兵（块 7 收尾 · P1）：替代原稿 7 枚 disabled 页码。 -->
           不换成分页器：搜索 / 类目是「浏览型」列表，无限滚动与首页同一交互；
           管理端表格才用 `components/Pager.vue`（那种面板有明确的总数与页码语义）。
      <div
        v-if="shownList.length"
        ref="feedSentinel"
        class="list-more"
        :class="{ 'list-more--loading': feedLoading, 'list-more--done': feedDone }"
        @click="!feedDone && feed.retry()"
      >
        <span v-if="feedLoading">正在加载更多…</span>
        <span v-else-if="feedErrMsg">加载失败，点击重试</span>
        <span v-else-if="feedDone">已经到底了</span>
        <span v-else>下拉加载更多</span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'   // ⚠️ `useRouter` 随 `toGoodsDetail` 一起退役（卡片换公共组件后本页没有 `router.push` 了，只剩 `route`：`watch(route.query.kw)` / `onMounted(route.query.back)`）
import { useInfiniteList } from '../composables/useInfiniteList'
import { http, handleApiError, urlOf } from '../api/http'
import { endpoints } from '../api/endpoints'; import ProductCard from '../components/ProductCard.vue'; import { sharedList } from '../config/goods-flags'   // ⚠️ 两条 import **同行**（C3 块 5 ·〈Bug 2/3〉落码，2026-10-12）—— 合并到既有行 = 不新增行号漂移（同下面 `:151` 那条的做法）。`ProductCard` = 本页卡片改用的公共组件（⑤「4 处复用」的第 2 处）；`sharedList` = **共享面列表的唯一定义处**（去重 + 滤已售完），本页与主页 / 两处「猜你喜欢」同源
import { isLoggedIn } from '../api/auth'; import { subsidyGoods } from '../api/marketing'   // ⚠️ 两条 import **同行**（2026-10-12 国补筛选那一笔）—— 合并到既有行 = 不新增行号漂移（同 `ShoppingCart.vue:146` 那条的做法）

// ⚠️ 这个 name 是**两个壳**里 `<KeepAlive :include="['HomeTab', 'GoodsSelect']">` 的
//    **唯一**匹配依据。改名（含改文件名）会让缓存**静默失效** —— 不报错，只是切回来
//    重新挂载（搜索态丢失）。改名前先 grep `include`。下一行那条 `eslint-disable-next-line` 是**必需**的
//    （本仓 `eslint-plugin-vue` 8.7.1 的 `vue3-essential` **不带** `vue/setup-compiler-macros`
//    宏环境 ⇒ 宏被判 `no-undef`）—— 块级写法先例见 `ShoppingCart.vue:73` 的 `/* eslint-disable no-undef */`
//    （R2 前此处引的是本文件自己的 `defineExpose`，那个宏已退役，见文件末按语）。
// eslint-disable-next-line no-undef
defineOptions({ name: 'GoodsSelect' })

const route = useRoute()   // ⚠️ `const router = useRouter()` 已删（同上面那条 import 按语）：本页最后一处 `router.push` 就在 `toGoodsDetail` 里
const searchKeyword = ref('')
const goodsList = ref([])

// 保存搜索状态到 sessionStorage
const saveState = () => {
  sessionStorage.setItem('SEARCH_KEYWORD', searchKeyword.value)
  sessionStorage.setItem('SEARCH_RESULTS', JSON.stringify(goodsList.value))
}

// ── 无限滚动（块 7 收尾 · P1）：搜索 / 类目 / 全部商品三条链共用一台控制器 ──────────
// 三条链的后端都已支持 `limit + offset`（`productSearch` / `categoryGoods` / `goodsSelectHome`），
// 每页 24 条。`mode` 是**下一次请求**的筛选条件 —— 切换筛选时先改 mode 再 reset，
// 旧代际的在途响应会被 `useInfiniteList` 丢弃（否则旧关键词的第二页会拼进新结果）。
const PAGE_SIZE = 24
let mode = { kind: 'home' }
const feed = useInfiniteList(async (offset, limit) => {
  let url
  let body
  if (mode.kind === 'search') {
    url = urlOf(endpoints.goods.productSearch)
    body = new URLSearchParams({ keyword: mode.kw, limit: String(limit), offset: String(offset) })
  } else if (mode.kind === 'category') {
    url = urlOf(endpoints.goodsB3.categoryGoods)
    body = new URLSearchParams({ TypeId: String(mode.typeId), limit: String(limit), offset: String(offset) })
    if (mode.kw) body.set('keyword', mode.kw)
  } else {
    url = urlOf(endpoints.goods.goodsSelectHome)
    body = new URLSearchParams({ limit: String(limit), offset: String(offset) })
  }
  const res = await http.post(url, body)
  const data = await res.json()
  if (!data.success) throw new Error(data.msg || '请求失败')
  return { rows: data.list || [] }
}, PAGE_SIZE)
// 累积结果直接落到页面既有的 `goodsList`（`shownList` / `saveState` 两个消费方不用改）。
watch(feed.items, (rows) => { goodsList.value = rows })
const feedSentinel = feed.sentinel
const feedLoading = feed.loading
const feedDone = feed.done
const feedErrMsg = feed.errMsg

// 从 sessionStorage 加载搜索状态
const loadState = () => {
  const keyword = sessionStorage.getItem('SEARCH_KEYWORD')
  const results = sessionStorage.getItem('SEARCH_RESULTS')
  if (keyword !== null) searchKeyword.value = keyword
  if (results) {
    // ⚠️ 恢复的是「上次已加载的那些行」，不是分页游标：直接标 done，避免哨兵立刻
    //    再要第一页、把同一批行追加一遍。用户想继续看，重新搜索即可。
    feed.reset()
    feed.items.value = JSON.parse(results)
    feed.done.value = true
  }
}

// 执行搜索：根据关键词是否为空调用不同接口
const doSearch = async () => {
  const kw = searchKeyword.value.trim()
  mode = kw === '' ? { kind: 'home' } : { kind: 'search', kw }
  feed.reset()
  await feed.loadNext()
  if (feed.errMsg.value) {
    handleApiError(new Error(feed.errMsg.value), '请求失败')
  } else {
    saveState()
  }
}

// 供父组件调用（主页搜索触发）
const searchByKeyword = async (kw) => {
  // 新搜索前清除旧缓存（注意：空搜索也会清除旧的搜索结果缓存）
  sessionStorage.removeItem('SEARCH_KEYWORD')
  sessionStorage.removeItem('SEARCH_RESULTS')
  searchKeyword.value = kw
  await doSearch()
}

// ── R1（2026-10-05）：客户壳路由化后，搜索关键词改由 URL 承载；R2 起**两壳同一套** ────────
// 两壳的首页搜索框/分类点击 ⇒ `/customer/search?kw=xxx` / `/merchant/search?kw=xxx`
// （`views/HomeTab.vue` 适配器推的，`base` 由路由记录给）⇒ 本条 watch 触发。
// `immediate: true` 覆盖「首挂载就带 kw」（含**直接输 URL** 与**刷新**）。
//
// ⚠️ **R2 起这是唯一的搜索入口通道**（R1 时它只是「纯增量的第二条通道」）：
//    R1 的商家侧走组件 `ref`（`MerchantPage.vue` 直挂 + `defineExpose`），R2 路由化后那条
//    通道整条退役（见文件末的按语）⇒ `searchByKeyword` 仍必须保留（本 watch 在调用它）。
// ⚠️ 下面的 `onMounted`（`back=search` ⇒ `loadState()`）**不能**改成 `onActivated`：
//    理由**已换**（结论不变）—— R2 起商家侧**也有** `<KeepAlive>` 了，但**离开壳会连缓存一起销毁**
//    （`/product` 是 `/merchant` / `/customer` 的**兄弟**路由 ⇒ 壳卸载）⇒ 回到搜索页必**重新挂载**
//    ⇒ `onMounted` 必触发。（R1 写的理由「商家侧直挂、无 KeepAlive」已失效，见 §5-X (3) 末按语。）
watch(() => route.query.kw, (kw) => {
  if (kw) searchByKeyword(kw)
}, { immediate: true })

// ⚠️ **墓碑**：这里原来有两样，本笔（C3 块 5 ·〈Bug 2/3〉落码，2026-10-12）把卡片换成
//    `components/ProductCard.vue` 之后它们**没有调用方了**，留着会被 `eslint no-unused-vars`
//    判红（`npm run build` 直接红，同 `HomePage.vue:314` 那条按语的口径）：
//      · `toGoodsDetail`（原来卡片 `@click` 的跳转，含 `from: 'search'`）⇒ 跳转交给组件的
//        `<router-link>`；`from` 由那个组件的 `from` prop 带（理由见模板那段注释 —— 它是
//        `ProductPage.goBack` 的回程判据，丢不得）；「跳转前保存」由 `:before-nav="saveState"` 承担。
//      · `getPriceInt` / `getPriceDec`（原来本页卡片的价签）⇒ 随卡片搬进 `ProductCard.vue`，
//        那一份现在**只有一处**（改前 `HomePage` / 本页 / `ChatRoom` 三处各一份、逐字同款）。
//    ⚠️ 本段是**墓碑**，不是「待恢复」的东西。

// 组件挂载时，如果是返回场景（URL 带有 back=search），则恢复数据
onMounted(() => {
  if (route.query.back === 'search') {
    loadState()
  }
})

// ── R2（2026-10-05）：`defineExpose({ searchByKeyword })` **已退役**（用户裁决 5）─────────────
// 它 R1 前的唯一调用方是商家壳的 `ref` 通道（`MerchantPage.vue` 的 `goSearch()` / `handlePendingSearch()`
// 通过 `goodsSelectRef.value.searchByKeyword(...)` 直接调）—— R2 把商家壳也路由化后，
// `<router-view>` 里拿不到子组件 ref，那条通道整条消失（关键词改由 `?kw=` 承载，见上一条 watch）。
// ⇒ 删掉「暴露」这件事本身；`searchByKeyword` 函数**保留**（上面的 watch 在调用它）。
// ⚠️ 与 R1 的差别只在**暴露**：R1 时它是「纯增量的第二条通道」，R2 起它是**唯一**通道。

// ══════════════════════════════════════════════════════════════════════════════
// C3 块 3〈商品块〉（2026-10-10）：左侧类目树**接线**（原为静态硬编码占位）
//
// ⚠️ **一读一筛两个端点**：
//   ① 读 —— 沿用**既有** `GET /goods/goodstype`（块 3 裁决：类目树**不新开读端点**）。
//   ② 筛 —— **新端点** `POST /goods/categoryGoods`，传 `TypeId`（**不是**类目名：唯一键换代后
//      「电子用品>配件」与「服装>配件」同名，按名回查会歧义 —— 这正是块 3 加 `TypeId` 列的原因）。
// ⚠️ `doSearch`（`:181-203`）**一行没动**：它管**搜索框**那条链（关键词 → `productSearch`）。
//    点类目走的是另一条链（`TypeId` → `categoryGoods`），两条**互不干扰** —— 点树不清搜索框、
//    点搜索不重置树（`activeType` 与 `searchKeyword` 各活各的）。
// ⚠️ 本段**追加在 `<script setup>` 末尾**：它上面的行被 `ChatRoom.vue:150` / `router/index.js:152`
//    / `router/index.js:215` 等处按行号引，在其上方插行会让它们集体失效。
// ══════════════════════════════════════════════════════════════════════════════

// 类目全表（一级 + 二级都在里面，靠 `ParentId` 分；`ParentId === 0` 即一级）
const cats = ref([])
// 当前选中的类目 `Id`；`null` = 「全部商品」
const activeType = ref(null)
// 展开的一级（同一时刻至多展一条 —— 与稿的 `.open` 单开观感一致）
const openRoot = ref(null)

const roots = computed(() => cats.value.filter((t) => (Number(t.ParentId) || 0) === 0))
const kidsOf = (id) => cats.value.filter((t) => Number(t.ParentId) === Number(id))
const isOpen = (id) => openRoot.value === id

// ⚠️ 一级**自己也能筛**（它不是「纯容器」）⇒「选中」与「展开」是**两件事**：
//    点一级 = 选中 + 展开；再点同一个一级 = 只收起（不改选中）。
const pickCat = async (id) => {
  activeType.value = id
  if (id === null) {
    openRoot.value = null
  } else {
    openRoot.value = openRoot.value === id ? null : id
  }
  await loadByType()
}

const loadCats = async () => {
  try {
    const res = await http.post(endpoints.goods.goodstype, null)
    const data = await res.json()
    if (data.success) cats.value = data.list || []
  } catch (e) {
    handleApiError(e, '获取分类失败')
  }
}

// 按当前 `activeType` 取商品：`null` ⇒ 走**既有**全量面（`goodsSelectHome`，与 `doSearch` 空关键词
// 同一条 URL）；非空 ⇒ 走**新**端点 `categoryGoods`。
// ⚠️ 两条都**不**碰 `searchKeyword`、也**不**调 `saveState()` —— 搜索缓存（`SEARCH_KEYWORD` /
//    `SEARCH_RESULTS`）的语义是「**搜索框**那一次搜索」，类目浏览不该往里塞（否则返回时会串味）。
const loadByType = async () => {
  // ⚠️ 不带搜索词：两条链互不干扰（点树不清搜索框、点搜索不重置树）—— 原实现就是
  //    `TypeId=` 一条，别在这里「顺手」把 searchKeyword 拼进去。
  mode = activeType.value === null
    ? { kind: 'home' }
    : { kind: 'category', typeId: activeType.value }
  feed.reset()
  await feed.loadNext()
  if (feed.errMsg.value) handleApiError(new Error(feed.errMsg.value), '请求失败')
}

onMounted(loadCats)

// ── 国补筛选（C3 块 5 · commit 5 续笔，2026-10-12）────────────────────────────────────
// 用户清单「用户端『国补商品』筛选」那一项：详情页的徽标 commit 3 就有（`ProductPage.vue:100`），
// **列表页此前没有**。本段补的就是它。
// ⚠️ 数据源是**既有的** `/marketing/subsidy/goods`（`subsidyGoods`）—— **不改 SQL、不加端点**：
//    这一层是**客户端过滤**（拿服务端已经算好的国补商品集合与当前列表求交），故**不是**上面
//    `.toolbar` 那条「4～6 处 · 超档」的说法所指 —— 那条针对的是「把筛选条件压进
//    `searchByKeyword` 的 SQL」，本笔一条 SQL 都没碰。
// ⚠️ 端点**不挂闸但也不在白名单** ⇒ 匿名 401 ⇒ **先判登录再发**（同 `ProductPage` 券 / 国补 /
//    秒杀三块的口径；否则 `http.js` 的 `handleUnauthorized` 会把这一页踢回登录页）。
// ⚠️ 命中判据是 **`ProductCode`**（国补按**型号**配）。
//    ⚠️ **已知错配（如实登记，不假装没有）**：本页两个数据源给的 `ProductCode` 是
//    `goods.ProductCode`，而多型号商品的国补行是按 `goods_sku.SkuCode` 配的 ⇒ **这类商品筛不出来**
//    （表现是「有国补却筛不到」，**不会**反过来把没国补的筛成有）。要修得动 `GoodsCacheService`
//    那条 SQL 或国补查询的键，属另一笔。
const subsidyOnly = ref(false)
const subsidyCodes = ref(new Set())
/** 没登录 / 一条国补商品都没有 ⇒ 这枚 chip 不可点（并给出原因），不做一个点了没反应的控件。 */
const subsidyReady = computed(() => isLoggedIn() && subsidyCodes.value.size > 0)
/**
 * 真正渲染的列表 = **共享面那一份**（去重 + 滤已售完）∩ 国补集合。
 * ⚠️ 去重与滤售完**不在本页实现**，走 `config/goods-flags.js` 的 `sharedList` —— 那一处的头注
 *    逐条写着两条判据（键为什么要含 `MerchantId`、为什么留 `MIN(Id)`、为什么先滤后去重要反过来）
 *    以及「哪几个面走这一支、哪几个面不走」。本页是**第 2 个消费方**（另三处：主页「为你推荐」/
 *    两处「猜你喜欢」），四处同源 ⇒ 换了一处就是四处一起换。
 */
const shownList = computed(() => {
  const base = sharedList(goodsList.value)
  return subsidyOnly.value
    ? base.filter((g) => subsidyCodes.value.has(String(g.ProductCode || '')))
    : base
})
const loadSubsidy = async () => {
  if (!isLoggedIn()) return
  try {
    const data = await subsidyGoods({ limit: 100 })
    const list = data && data.success && Array.isArray(data.list) ? data.list : []
    subsidyCodes.value = new Set(list.map((r) => String(r.ProductCode || '')))
  } catch (e) {
    subsidyCodes.value = new Set()
  }
}
onMounted(loadSubsidy)
</script>



<style scoped>
/* ══════════════════════════════════════════════════════════════════════════
   A 版公域搜索页（2026-10-19 用户命令四重写）
   ──────────────────────────────────────────────────────────────────────────
   ① 去掉左分类栏（`<aside class="cat">` 整块从 DOM 退役），页面全宽；
   ② 结果网格与首页「为你推荐」同款瀑布流（`column-count` + `break-inside`）；
   ③ 分类 / 排序 / 筛选全部横排在顶部。
   功能一个没少：类目接线、搜索、国补筛选、无限滚动、`from="search"` 回程、`saveState`。
   ⚠️ 本页与首页同宽：1440 居中 + 左右 `--s6`，与 `HomePage.vue` 的 `.wrap` 对齐。
   ⚠️ scoped 下 `:root` 不生效 ⇒ 只用 `tokens.css` 的既有变量，不在组件里造 token。
   ══════════════════════════════════════════════════════════════════════════ */

/* 基础重置：稿的**全局** `a` / `button` / `input` 规则搬进 scoped（只作用本组件的元素）。 */
button,
input {
  font: inherit;
  color: inherit;
}
button {
  background: none;
  border: 0;
  cursor: pointer;
}
.goods-select-view {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 40px var(--s6) 96px;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头：面包屑 → 标题行 → 搜索框 ───────────────────────────────────── */
.crumb {
  display: flex;
  align-items: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.crumb .sp {
  font-style: normal;
  padding: 0 var(--s2);
}
.crumb .cur {
  color: var(--ink-2);
}
.head {
  margin-top: var(--s4);
  padding-bottom: 26px;
  border-bottom: 1px solid var(--line);
}
.head-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}
.page-h {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-med);
  line-height: 1.15;
  letter-spacing: -0.02em;
}
.head-note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
/* 搜索框：A 版顶栏 `.top-search` 的放大版 —— 方框、发丝线、黑底按钮贴右边框。 */
.search {
  margin-top: var(--s5);
  display: flex;
  align-items: center;
  height: 52px;
  border: 1px solid var(--line-2);
  background: var(--surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.search:focus-within {
  border-color: var(--ink);
  box-shadow: 0 0 0 3px rgba(27, 30, 32, 0.06);
}
.search input {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0 var(--s5);
  border: 0;
  background: none;
  font-size: var(--fs-md);
}
.search input:focus {
  outline: none;
}
.search .go {
  flex: none;
  height: 100%;
  padding: 0 var(--s6);
  border-left: 1px solid var(--line-2);
  background: var(--ink);
  color: var(--surface);
  font-weight: var(--fw-med);
  letter-spacing: 0.04em;
  transition: background 0.15s;
}
.search .go:hover {
  background: var(--brand-hover);
  color: var(--surface);
}

/* ── 分类：横排 chip（原左栏 260px 分类树退役）──────────────────────────
   ⚠️ 一级 chip 选中 = 黑底白字（全页唯一的「当前态」视觉语言，与排序 `.sort.on`、
      国补 `.pick.on` 同族）；展开二级时在下方铺一行浅底子 chip。
   ⚠️ 数据与点击逻辑一行没改：`roots` / `kidsOf` / `pickCat` / `activeType`。 */
.cats {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 18px;
  padding: 18px 0;
  border-bottom: 1px solid var(--line);
}
.cat-h {
  flex: none;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  letter-spacing: 0.08em;
}
.cat-l {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  padding: 7px 14px;
  border: 1px solid var(--line-2);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  line-height: 1.2;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}
.chip:hover {
  border-color: var(--ink);
  color: var(--ink);
}
.chip.on {
  border-color: var(--ink);
  background: var(--ink);
  color: var(--surface);
}
.chip.sm {
  padding: 5px 11px;
  font-size: var(--fs-xs);
}
.cat-sub {
  flex-basis: 100%;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  background: var(--surface-2);
  border-left: 2px solid var(--ink);
}
.cat-sub .lbl {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-right: 2px;
}

/* ── 排序 / 筛选条：横向两行，发丝线分隔 ───────────────────────────────── */
.toolbar {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--s4) 0;
  border-bottom: 1px solid var(--line);
}
.trow {
  display: flex;
  align-items: center;
  gap: var(--s6);
  flex-wrap: wrap;
}
.tgroup {
  display: flex;
  align-items: center;
  gap: var(--s4);
}
.tgroup > .lbl {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
/* ⚠️ 每个排序项都带 padding ⇒ 选中项加底色时**行高不跳**（只在 `.on` 上加样式会推挤邻项）。 */
.sort {
  color: var(--ink-2);
  font-size: var(--fs-sm);
  padding: var(--s1) var(--s3);
  border-radius: var(--r1);
}
.sort:hover {
  color: var(--ink);
}
.sort.on {
  color: var(--brand-ink);
  font-weight: var(--fw-semi);
  background: var(--brand-tint);
}
.sort[disabled] {
  cursor: not-allowed;
}
.range {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
}
.range input {
  width: 72px;
  height: 32px;
  padding: 0 var(--s2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface-2);
  color: var(--ink-2);
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.range .dash {
  color: var(--ink-3);
}
.pick {
  display: flex;
  align-items: center;
  gap: var(--s2);
  color: var(--ink-2);
  font-size: var(--fs-sm);
}
.pick[disabled] {
  cursor: not-allowed;
}
/* ── 国补筛选：`.pick` 的**选中态** ─────────────────────────────────────
   ⚠️ 借同一页 `.sort.on` 的色与字重（`--brand-ink` + 加粗）—— 这是「当前生效的
      排序 / 筛选」既有的视觉语言；不借底色（`.pick` 没有 padding，加底会推挤邻项）。
   ⚠️ 有意不用 `--accent`：朱砂只给价格 / 促销 / 未读，筛选 chip 不属于那三件事。 */
.pick.on {
  color: var(--brand-ink);
  font-weight: var(--fw-med);
}
/* ⚠️ 用 CSS 三角，不用 `▾` 字形 —— 自托管子集里没有该码位，实测回落成一个小点。 */
.pick .car {
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid currentColor;
  margin-left: 2px;
}
.trow .right {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}

/* ── 商品网格：与首页「为你推荐」同一套瀑布流（`HomePage.vue` 的 `.grid`）──
   ⚠️ 用户命令四：搜索结果的排布必须和主页一致 ⇒ 这里逐条照抄首页那一份：
      `column-count: 3` + `column-gap: 12px` + `break-inside: avoid` + 下距 12px。
   ⚠️ 卡片本体不设 `display:flex`：多列布局里 flex 列会把卡片强行拉平，
      破坏瀑布流按内容高度收边的能力。 */
.grid {
  column-count: 3;
  column-gap: 12px;
  margin-top: 24px;
}
.grid > * {
  break-inside: avoid;
  margin: 0 0 12px;
  min-width: 0;
}
.grid > *.pcard {
  border-color: var(--line);
  border-radius: 0;
  box-shadow: none;
  transition: border-color 0.15s linear;
}
.grid > *.pcard:hover {
  border-color: var(--ink);
}
.grid > * :deep(.pcard-img) {
  min-height: 0;
  aspect-ratio: 4 / 3;
}
.grid > * :deep(.pcard-img img) {
  transition: transform 0.55s cubic-bezier(0.2, 0.7, 0.2, 1);
}
.grid > *:hover :deep(.pcard-img img) {
  transform: scale(1.045);
}

.empty {
  padding: var(--s8) 0;
  text-align: center;
  color: var(--ink-3);
}
.empty .big {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  color: var(--ink-2);
  margin-bottom: var(--s3);
}

/* ── 加载更多（块 7 收尾 · P1；取代原稿的 7 枚 disabled 页码占位）──────
   底部渐变 = 「下面还有」的提示；`done` 后淡出。新插入的卡从下方淡入（`.grid > *`
   —— ProductCard 是子组件，但它的**根节点**带本页 scoped 属性，所以选得中）。 */
.grid > * {
  animation: gs-in 0.32s ease both;
}
@keyframes gs-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: none; }
}
.list-more {
  position: relative;
  margin-top: var(--s6);
  padding-top: var(--s6);
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.list-more::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: calc(-1 * var(--s8));
  height: var(--s8);
  pointer-events: none;
  background: linear-gradient(to top, var(--bg) 20%, transparent);
  transition: opacity 0.3s ease;
}
.list-more--done::before {
  opacity: 0;
}
.list-more--loading {
  color: var(--brand-ink);
}
.list-more--loading::after {
  content: '';
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-left: var(--s2);
  border: 2px solid var(--brand-soft);
  border-top-color: var(--brand-ink);
  border-radius: 50%;
  vertical-align: -1px;
  animation: gs-spin 0.8s linear infinite;
}
@keyframes gs-spin {
  to { transform: rotate(360deg); }
}

/* ── 响应式：与首页瀑布流同一组断点（3 → 2 → 1）────────────────────── */
@media (max-width: 1180px) {
  .goods-select-view {
    padding-left: var(--s5);
    padding-right: var(--s5);
  }
}
@media (max-width: 860px) {
  .grid {
    column-count: 2;
  }
  .head-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
  .head-note {
    white-space: normal;
  }
}
@media (max-width: 600px) {
  .grid {
    column-count: 1;
  }
  .search {
    height: 46px;
  }
  .search .go {
    padding: 0 var(--s4);
  }
  .cats {
    gap: 8px 12px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .grid > *,
  .grid > * :deep(.pcard-img img) {
    animation: none;
    transition: none;
  }
  .grid > *:hover :deep(.pcard-img img) {
    transform: none;
  }
}
</style>