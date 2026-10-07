<template>
  <div class="goods-select-view">
    <nav class="crumb">
      <a>首页</a><span class="sp">/</span><span class="cur">商品搜索</span>
    </nav>
    <h1 class="page-h">商品搜索</h1>

    <div class="search">
      <input
        v-model="searchKeyword"
        type="text"
        placeholder="搜索商品、品牌、分类"
        @keyup.enter="doSearch"
      />
      <button class="go" @click="doSearch">搜索</button>
    </div>

    <div class="grid-wrap">
      <!-- ⚠️ D 笔（2026-10-07 裁决）：左栏三块「超档」预留位**照落 UI** ——
           `disabled` + `data-slot` + `title` 三件套，功能不本笔做，登记 `C3〈搜索〉`
           （台账 `docs/agent/slot-registry.md`）。判据见 `docs/美化/README.md:212-218`：
           它管的是「**功能实现**成本」，不是「UI 落不落」。 -->
      <aside
        class="cat"
        data-slot="预留 · 左分类树（动现有代码 6 处 · 超档）"
        title="待 C3〈搜索〉——★数据源是好的：goodstype 端点现成、表里 5 条真分类（电子用品/生活用品/服装/美食/运动用品）。缺的是**筛选参数**，不是数据。要能动：GoodsDao.java（SQL 加类目 where）+ GoodsService.java + GoodsController.java + GoodsSelect.vue（取值 + 传参）= 4 处；再加二级层级（GoodTypeNew 列存在但**数据全 0**）还要 GoodsTypeCacheService.java 改 SQL + 造数据 = 6 处。**超档**，因清单点名而画出（见 README 挂账表）"
      >
        <div class="cat-h">全部分类</div>
        <ul class="cat-l">
          <li><a class="lv1">全部商品</a></li>
          <li class="open">
            <a class="lv1">电子用品</a>
            <ul>
              <li><a class="on" title="二级分类名为示意 —— goodstype.GoodTypeNew 全为 0，库里无任何二级分类名">耳机 / 音箱</a></li>
              <li><a>充电配件</a></li>
            </ul>
            <div class="cat-more"><button disabled title="暂无更多二级分类">更多<span class="car"></span></button></div>
          </li>
          <li>
            <a class="lv1">生活用品</a>
            <ul>
              <li><a title="二级分类名为示意">保温杯 / 杯具</a></li>
              <li><a>收纳整理</a></li>
            </ul>
          </li>
          <li>
            <a class="lv1">服装</a>
            <ul>
              <li><a title="二级分类名为示意">外套</a></li>
              <li><a>配饰</a></li>
            </ul>
          </li>
          <li>
            <a class="lv1">美食</a>
            <ul>
              <li><a title="二级分类名为示意">烘焙轻食</a></li>
              <li><a>咖啡 / 茶饮</a></li>
            </ul>
          </li>
          <li>
            <a class="lv1">运动用品</a>
            <ul>
              <li><a title="二级分类名为示意">健身器材</a></li>
              <li><a>户外装备</a></li>
            </ul>
          </li>
        </ul>
      </aside>

      <div class="main">
        <div v-if="goodsList.length === 0" class="empty">
          <div class="big">没有找到相关商品</div>
          <div>换个关键词，或去掉品牌与价格区间限制再试一次。</div>
        </div>

        <template v-else>
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
              <span class="right">共 {{ goodsList.length }} 件</span>
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
            </div>
          </div>

          <div class="grid">
            <div v-for="goods in goodsList" :key="goods.Id" class="card" @click="toGoodsDetail(goods)">
              <div class="thumb">
                <img :src="goods.GoodImg" alt="商品图" />
              </div>
              <div class="c-name">{{ goods.GoodName }}：{{ goods.ProductCode }}</div>
              <div class="c-price">
                <span class="sym">¥</span>
                <span class="int">{{ getPriceInt(goods.Price) }}</span>
                <span class="dec">.{{ getPriceDec(goods.Price) }}</span>
              </div>
            </div>
          </div>

          <nav
            class="pager"
            data-slot="预留 · 分页（动现有代码 4 处 · 超档）"
            title="待 C3〈搜索〉——无 LIMIT / COUNT：GoodsDao.java（SQL 加 LIMIT + 一条 count）+ GoodsService.java + GoodsController.java + GoodsSelect.vue = 4 处，**超档**，因清单点名而画出。页码为示意。"
          >
            <button class="pg" disabled title="无分页端点">首页</button>
            <button class="pg" disabled title="无分页端点">上一页</button>
            <button class="pg on" disabled title="无分页端点">1</button>
            <button class="pg" disabled title="无分页端点">2</button>
            <button class="pg" disabled title="无分页端点">3</button>
            <button class="pg" disabled title="无分页端点">下一页</button>
            <button class="pg" disabled title="无分页端点">末页</button>
          </nav>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { http, handleApiError, urlOf } from '../api/http'
import { endpoints } from '../api/endpoints'

// ⚠️ 这个 name 是**两个壳**里 `<KeepAlive :include="['HomeTab', 'GoodsSelect']">` 的
//    **唯一**匹配依据。改名（含改文件名）会让缓存**静默失效** —— 不报错，只是切回来
//    重新挂载（搜索态丢失）。改名前先 grep `include`。下一行那条 `eslint-disable-next-line` 是**必需**的
//    （本仓 `eslint-plugin-vue` 8.7.1 的 `vue3-essential` **不带** `vue/setup-compiler-macros`
//    宏环境 ⇒ 宏被判 `no-undef`）—— 块级写法先例见 `ShoppingCart.vue:73` 的 `/* eslint-disable no-undef */`
//    （R2 前此处引的是本文件自己的 `defineExpose`，那个宏已退役，见文件末按语）。
// eslint-disable-next-line no-undef
defineOptions({ name: 'GoodsSelect' })

const router = useRouter()
const route = useRoute()
const searchKeyword = ref('')
const goodsList = ref([])

// 保存搜索状态到 sessionStorage
const saveState = () => {
  sessionStorage.setItem('SEARCH_KEYWORD', searchKeyword.value)
  sessionStorage.setItem('SEARCH_RESULTS', JSON.stringify(goodsList.value))
}

// 从 sessionStorage 加载搜索状态
const loadState = () => {
  const keyword = sessionStorage.getItem('SEARCH_KEYWORD')
  const results = sessionStorage.getItem('SEARCH_RESULTS')
  if (keyword !== null) searchKeyword.value = keyword
  if (results) goodsList.value = JSON.parse(results)
}

// 执行搜索：根据关键词是否为空调用不同接口
const doSearch = async () => {
  const kw = searchKeyword.value.trim()
  try {
    let url, body;
    if (kw === '') {
      // 空搜索：调用获取全部商品接口
      url = urlOf(endpoints.goods.goodsSelectHome)
      body = ''  // 该接口可能不需要参数，如果必须可传 'action=getAll' 等，根据后端调整
    } else {
      // 有关键词：调用搜索接口
      url = urlOf(endpoints.goods.productSearch)
      body = `keyword=${encodeURIComponent(kw)}`
    }
    const res = await http.post(url, body)
    const data = await res.json()
    if (data.success) {
      goodsList.value = data.list || []
      saveState()
    }
  } catch (e) {
    handleApiError(e, '请求失败')
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

// 跳转到商品详情页，携带 from=search 标记，并保存当前状态
const toGoodsDetail = (goods) => {
  saveState()  // 跳转前保存
  router.push({
    path: '/product',
    query: {
      Id: goods.Id,
      MerchantId: goods.MerchantId,
      GoodName: goods.GoodName,
      Price: goods.Price,
      GoodImg: goods.GoodImg,
      GoodType: goods.GoodType,
      ProductCode: goods.ProductCode,
      from: 'search'
    }
  })
}

// 辅助方法：价格格式化
const getPriceInt = (price) => {
  if (!price) return '0'
  return parseInt(price) || '0'
}
const getPriceDec = (price) => {
  if (!price) return '00'
  const s = String(price)
  const arr = s.split('.')
  return arr[1] ? arr[1].padEnd(2, '0').slice(0, 2) : '00'
}

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
</script>



<style scoped>
/* ⚠️ D 笔（2026-10-07）：全块按 `docs/美化/pages/GoodsSelect.html` 的 `<style>` 重写。
   类名一律取稿名（`crumb` / `page-h` / `search` / `grid-wrap` / `cat` / `toolbar` / `grid` /
   `card` / `thumb` / `c-name` / `c-price` / `pager` / `empty`），旧名（`search-section` /
   `goods-wrap` / `goods-card` / …）全部退役。
   ⚠️ 稿的 `.page` / `.nav-bar` / `.bar` 是预览脚手架，不进 Vue。
   ✅ `.cat`（分类树 N=6）/ `.toolbar`（排序筛选 N=4~6）/ `.pager`（分页 N=4）三块自标「超档」，
   **照落 UI**（用户 2026-10-07 裁决，**覆盖**同日早先的「只登记、不落」）：`README.md:212-218`
   的三档表管的是「**功能实现**成本」（改既有文件几处），**不是**「UI 落不落」⇒ N≤3 功能本笔做、
   N>3 登记 `C3〈搜索〉`，**UI 一律落占位**（`disabled` + `data-slot` + `title`）⇒ 栅格是
   `260px + minmax(0,1fr)` 两栏，不是单栏。台账 `docs/agent/slot-registry.md`。
   ⚠️ 稿的 `--d2` 由各稿自己的 `:root` 持有（`calc(var(--fs-2xl) * 1.00)` = 36px，稿 `:211`）；
   scoped 下 `:root` 不生效 ⇒ 直接写 `var(--fs-2xl)`（数值等价），**不**在组件里造 token。
   ⚠️ `.wrap` 的角色由本页**根** `.goods-select-view` 承接：壳的 `.content` 已归零内距
   （commit 0 / 乙案）⇒ 1180 居中与内距由本页自持。 */

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
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s7) var(--s5) var(--s8);
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}
/* 面包屑：分隔符**与文字同色**（同为 `--ink-3`）—— 用 `--line-2` 时对比度只有 1.47:1。 */
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
.page-h {
  margin-top: var(--s4);
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-med);
  line-height: 1.15;
  letter-spacing: -0.02em;
}
/* 搜索框（主视觉）：56px，按钮嵌在框内 */
.search {
  margin-top: var(--s6);
  display: flex;
  align-items: center;
  max-width: 760px;
  height: 56px;
  border: 1px solid var(--line-2);
  border-radius: var(--r3);
  background: var(--surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.search:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-tint);
}
.search input {
  flex: 1;
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
  margin-right: 6px;
  height: 44px;
  padding: 0 var(--s6);
  border-radius: var(--r2);
  background: var(--brand);
  color: var(--bg);
  font-weight: var(--fw-med);
}
.search .go:hover {
  background: var(--brand-hover);
}
/* ⚠️ 左栏 260px 是被 slots 角标**逼出来**的：分类树角标文案宽约 242px，栏宽 200px 时它会伸到
   右邻的排序条上。260px 也正好落在淘宝/京东分类栏的量级上，不算为调试让步。（稿 `:291-293`）
   `minmax(0,1fr)` 而非 `1fr`：不给主区设下限，长内容不会把栅格撑破。 */
.grid-wrap {
  margin-top: var(--s6);
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: var(--s6);
  align-items: start;
}
.main {
  min-width: 0;
}

/* ── 左分类树（预留位 · C3〈搜索〉）───────────────────────────────────────────
   ⚠️ 稿 v5 删掉了这里原来的 `border-right:1px solid var(--line)`：左栏 ⇄ 主栅格是**同级**两栏
   （不是分区内分组），同级的分隔手段是间距、不是线 ⇒ 改为纯大留白（`--s5` + `--s6` gap）。
   ⚠️ 唯一一条用色规则：**「当前态 = 青瓷绿 50 底」**（`--brand-tint`，ΔE00/白 = 7.30），
   出现在三处 —— 这里选中的二级分类、排序选中项、分页当前页。文字色只管层级，底色才管状态。 */
.cat {
  padding-right: var(--s5);
}
.cat-h {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-bottom: var(--s4);
}
.cat-l,
.cat-l ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.cat-l > li {
  border-bottom: 1px solid var(--line);
  padding: var(--s3) 0;
}
.cat-l > li:first-child {
  border-top: 1px solid var(--line);
}
.lv1 {
  display: block;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  padding: var(--s1) 0;
}
.lv1:hover {
  color: var(--ink);
}
/* ⚠️ 「已展开」和「已选中」必须是**两种**视觉语言，否则同栏里会同时出现两处「绿 + 加粗」，
   读起来像两个都选中了 ⇒ 绿 + 加粗**只留给选中**（`.on`）；「已展开」的一级只深一档灰。 */
li.open > .lv1 {
  color: var(--ink);
}
.cat-l ul {
  margin-top: var(--s2);
  padding-left: var(--s3);
  border-left: 1px solid var(--line);
}
.cat-l ul a {
  display: block;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  padding: 3px 0;
}
.cat-l ul a:hover {
  color: var(--brand-ink);
}
.cat-l ul a.on {
  color: var(--brand-ink);
  font-weight: var(--fw-med);
  background: var(--brand-tint);
  border-radius: var(--r1);
  padding: 3px var(--s2);
  margin-left: calc(var(--s2) * -1);
}
.cat-more {
  padding-left: var(--s3);
  margin-top: var(--s2);
}
.cat-more button {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.cat-more button:hover {
  color: var(--brand-ink);
}
.cat-more .car {
  display: inline-block;
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid currentColor;
  margin-left: 4px;
  vertical-align: middle;
}

/* ── 排序 / 筛选条（预留位 · C3〈搜索〉）：去盒子，上下两条发丝线；**显式两行**，不靠 flex-wrap ─ */
.toolbar {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--s4) 0;
  border-top: 1px solid var(--line);
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

/* 商品栅格：auto-fill + 40px 行距 */
.grid {
  margin-top: var(--s6);
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 40px 24px;
}
/* 卡片 8px 内边距 + 等量负外边距 —— hover 底色块不紧贴图边，而栅格间距一个字没变。
   ⚠️ 稿里 `.card` 是 `<a>`，这里是可点 `div` ⇒ 补 `cursor:pointer`。 */
.card {
  display: block;
  padding: var(--s2);
  margin: calc(var(--s2) * -1);
  border-radius: var(--r2);
  transition: background 0.15s;
  cursor: pointer;
}
.card:hover {
  background: var(--brand-tint);
}
/* 无图可放：底用 `--img`（tokens 里就是为「商品图占位」定义的），只加一圈发丝边。 */
.thumb {
  aspect-ratio: 1 / 1;
  border-radius: var(--r2);
  background: var(--img);
  border: 1px solid var(--line);
  overflow: hidden;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
/* ⚠️ `min-height:3em` = 两行：否则一行名 / 两行名的卡片价格不在同一水平线上。 */
.c-name {
  margin-top: var(--s3);
  font-size: var(--fs-sm);
  line-height: 1.5;
  color: var(--ink-2);
  min-height: 3em;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* 价格三档：¥ 13 / 整数 24 宋体 / 小数 13 */
.c-price {
  margin-top: var(--s2);
  display: flex;
  align-items: baseline;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.c-price .sym {
  font-size: var(--fs-xs);
}
.c-price .int {
  font-family: var(--font-display);
  font-size: var(--fs-lg);
  font-weight: var(--fw-med);
  line-height: 1;
}
.c-price .dec {
  font-size: var(--fs-xs);
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

/* ── 分页（预留位 · C3〈搜索〉）：发丝线文字按钮 ────────────────────────────────
   ⚠️ 当前页用 `--brand-tint` 底（与左树选中项、排序选中项同一条规则）；hover 仍留在
   `--surface-2` —— **hover 是瞬时反馈、当前页是状态**，两者不该同色。 */
.pager {
  margin-top: var(--s8);
  display: flex;
  justify-content: center;
  align-items: center;
  gap: var(--s2);
}
.pg {
  min-width: 40px;
  height: 36px;
  padding: 0 var(--s3);
  border-radius: var(--r2);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  font-variant-numeric: tabular-nums;
}
.pg:hover {
  background: var(--surface-2);
}
.pg.on {
  color: var(--brand-ink);
  font-weight: var(--fw-semi);
  background: var(--brand-tint);
}
.pg[disabled] {
  color: var(--ink-3);
  cursor: not-allowed;
}
.pg[disabled]:hover {
  background: none;
}

/* 1080：分类树**改横排**，主区吃满整宽。
   ⚠️ 不这么做的话，左 260px 会把主区压到约 520px，栅格掉到 2 列、卡片又宽又扁 ——
   与 1440 的观感不是同一张稿。 */
@media (max-width: 1080px) {
  .grid-wrap {
    grid-template-columns: 1fr;
    gap: var(--s5);
  }
  .cat {
    padding-right: 0;
    border-bottom: 1px solid var(--line);
    padding-bottom: var(--s4);
  }
  .cat-l {
    display: flex;
    flex-wrap: wrap;
    gap: 0 var(--s5);
  }
  .cat-l > li {
    border-bottom: 0;
    padding: var(--s2) 0;
  }
  .cat-l > li:first-child {
    border-top: 0;
  }
  /* ⚠️ 横排时**二级分类整层收掉**，只留一级 —— 摊平的话「耳机 / 音箱 充电配件」浮在
     「电子用品 生活用品」下面，读不出谁是谁的子项，反而比不显示更容易误读。 */
  .cat-l ul,
  .cat-more {
    display: none;
  }
}
</style>