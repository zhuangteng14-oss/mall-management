<template>
  <!--
    D 笔 commit 11 —— 本页承载稿 `docs/美化/pages/GoodsUp.html`（575 行）的 `<main>` 那一支。
    宿主：`/merchant/user/list`（`router/index.js:117`），meta.layout = `user-page`、meta.sub = `list`
    ⇒ 壳内：顶栏右起第 3 项「用户中心」高亮，左菜单「查看商品」选中。
    ⚠️ 稿的 `.nav-bar` / `.wrap` / `.body` / `.uc-menu` **不进本组件** —— 落点是 `AppShell.vue`
       （顶栏 + `.wrap` 居中）与 `UserCenter.vue`（左菜单那一支）⇒ 本页只承载右栏
       （与 `UserInfoPage.vue` / `GoodsAdd.vue` 同槽位）。
    ⚠️ 稿是**三视图**（`data-page="list"` / `"empty"` / `"dlg"`），那是它自己的预览切页工具：
       · `list` ⇒ 落成本页的表格态（`showGoodsList` 非空）；
       · `empty` ⇒ 落到 `v-if="showGoodsList.length === 0"` 的 `.empty`；
       · `dlg` ⇒ 落到 `v-if="showModal"` 的既有弹窗（`.scrim` / `.dlg`）。
    ⚠️ 稿的**结构改动照落**：v4 把「四列卡片墙」换成**商品表格**（勾选 / 图 / 名 / 类型 / 价 /
       库存 / 销量 / 状态 / 操作 + 分页）—— 稿头注写明这是 v4 判据「商家商品管理页长什么样」
       直接要求、且用户逐页清单点名要表格 ⇒ 不是本笔自选。
       ⚠️ 卡片墙那个「点整卡开弹窗」是**既有真事件** ⇒ 本笔**保留**（落在 `<tr>` 上），
          同时按稿补出「操作」列里的【编辑】。两处都开同一个 `openModal(item)`。
  -->
  <main class="uc-main">

    <!-- 页头（稿 `.head` `:175-181`）：身份并进来，不再单独占一条横带。
         ⚠️ 稿 `.sub` 是「声谷小店 · 共 8 件上架中」——「声谷小店」是它 mock 的店名（编的名 ⇒
            照判据不落）⇒ 副题只留**真陈述**：`共 N 件上架中`，N 取 `onshelfCount`（**在架条数** —— C3 块 3 起表格并进了待审 / 驳回行，本计数扣掉它们）。
         ⚠️ 稿 `.ava` 是灰方块（`background:var(--img)`）⇒ 保留**既有真头像**
            （`userInfo.image || assetOf('/img/default_user.png')`，取值链与原实现逐字一致）。 -->
    <div class="head">
      <div class="ava">
        <img :src="userInfo.image || assetOf('/img/default_user.png')" alt="头像">
      </div>
      <div>
        <h1>查看商品</h1>
        <div class="sub">共 {{ onshelfCount }} 件上架中</div>
      </div>
    </div>

    <!-- ═══ 工具条（稿 `.tools` `:183-208`）：**显式两行** ═══════════════════════════
         ⚠️ 稿注写明第一版用 `flex-wrap` 一行排到底时 `.bulk` 的 `border-left` 会折到第二行
            行首成一条悬空竖线 ⇒ 本页照稿的 `r1` / `r2` 两行结构落。 -->
    <div class="tools">
      <div class="r1">
        <!-- 预留位 ①（稿 `:367`）—— **C3 块 3〈商品块〉已接线（2026-10-10）**：原 `data-slot`
             与五件的 `disabled` + `title` 全退场。⚠️ 四件筛选**都是前端过滤** —— 行数据已在
             `rows` 里（两路都按 `MerchantId` 取全量、`goodsSelect` 无分页参数）⇒ **新端点 0 条**。 -->
        <span
          class="find"
          title="C3 块 3〈商品块〉已接线：四件均为前端过滤（行数据已在 rows 里）；「状态」取自 goodsSelect（已上架）∪ myApplications（待审 / 驳回）的并表结果"
          aria-label="筛选 / 搜索"
        >
          <input type="text" placeholder="商品名" v-model.trim="flt.name">
          <input type="text" placeholder="ProductCode" v-model.trim="flt.code">
          <select v-model="flt.state">
            <option value="">全部状态</option><option v-for="s in stateOptions" :key="s" :value="s">{{ STATE_TEXT[s] }}</option>
          </select>
          <select v-model="flt.type">
            <option value="">全部分类</option><option v-for="t in typeOptions" :key="t" :value="t">{{ t }}</option>
          </select>
          <button type="button" class="btn-quiet" @click="applyFlt">查询</button>
        </span>

        <!-- 【上架商品】= **真入口**（指向已存在路由 `/merchant/user/add`，`router/index.js:116`
             的 `path: 'add'`）⇒ 照判据落**真 `<router-link>`**，不是死链接、不 `disabled`。
             ⚠️ 稿这里是 `<button>`（`:375`）⇒ 本笔只换成 `<router-link>`；`.btn-primary` 补
             `display:inline-flex;align-items:center`（`<a>` 是行内元素，不补则 36px 高度不生效）。 -->
        <router-link class="btn-primary" to="/merchant/user/add">上架商品</router-link>
      </div>

      <div class="r2">
        <!-- 预留位 ②（稿 `:379`）—— 块级。内部 4 件（全选 + 三枚批量钮）是稿外死控件。
             ⚠️ 只有「全选」那枚稿里自带 `title`（逐字取稿）；三枚批量钮稿里只有 `disabled`。 -->
        <span
          class="bulk"
          data-slot="预留 · 批量操作（动现有代码 3 处）"
          title="待 C3〈商品〉——批量上架 / 批量下架 / 批量删除均无端点（现有下架只能一件一件来）：endpoints.js + GoodsController.java + GoodsUp.vue = 3 处。全选框本身是纯前端"
        >
          <button
            type="button"
            class="ck"
            disabled
            aria-checked="false"
            title="全选（纯前端；批量动作无端点）"
          ></button>
          <span class="lbl">全选</span>
          <button type="button" class="btn-quiet" disabled title="待 C3〈商品〉——批量上架无端点（3 处，占位档）">批量上架</button>
          <button type="button" class="btn-quiet" disabled title="同上">批量下架</button>
          <button type="button" class="btn-quiet" disabled title="同上">批量删除</button>
        </span>
      </div>
    </div>

    <!-- ═══ 空态 / 商品表格 ═══════════════════════════════════════════════════════
         ⚠️ **空态只换表格**：稿的空态面（`:438-464`）把 `.tools` 与「按名称精确下架」也一并
            去掉了 —— 那是它的一次性预览。本笔**不照做**：`.tools` 里有**真入口**【上架商品】、
            `.sec` 是**既有真功能**（`doDeleteGoods`），藏起来 = 把真功能藏起来。（已登记） -->
    <div v-if="visibleList.length === 0" class="empty">
      <div class="big">{{ rows.length === 0 ? '暂无任何上架内容' : '没有符合条件的商品' }}</div>
      <!-- 稿 `:459` 的原文（既有实现只到上一行；这一行是稿补的指引，照落）。⚠️ C3 块 3 起补 `v-if`：「去上架」那句只对**真的一件都没有**成立，筛出 0 行时指路就是误导。 -->
      <div v-if="rows.length === 0">在「上架商品」里添加第一件商品。</div>
    </div>

    <template v-else>
      <!-- 预留位 ③（稿 `:391`）—— 块级 `.tbl-zone` ⇒ 只挂 `data-slot` + `title`。
           ⚠️ 稿注写明**不能挂 `.tbl-wrap`**：`overflow-x:auto` 按规范会把另一轴的 `visible`
              计算成 `auto` ⇒ 角标（`top:-11px` 的伪元素）被滚动容器裁掉。外面这层 `.tbl-zone`
              是**零溢出**的专门挂载点。⚠️ 角标本身不进 Vue，挂载点只承 `data-slot` + `title`。 -->
      <div
        class="tbl-zone"
        data-slot="预留 · 库存 / 销量列（动现有代码 5 / 3 处 · 库存超档）"
        title="只有这两列是占位，且代价不同：库存 = mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsUp.vue = 5 处（**超档**，因用户逐页清单点名而画出，见 README 挂账表）；销量 = productsales.OrderVolume 已有列但 INSERT 恒为 0，要真跑需 写(订单侧) + 读(GoodsDao) + 显示(GoodsUp.vue) = 3 处。其余七列（勾选 / 图 / 名 / 类型 / 价 / 状态 / 操作）都是真功能。"
      >
        <div class="tbl-wrap">
          <table class="tbl">
            <thead>
              <tr>
                <th class="c-ck"></th>
                <th class="c-img">图</th>
                <th class="c-name">商品名</th>
                <th>类型</th><th title="商品类别（2026-10-12 用户裁定 ④ 问 2）—— 国补（绿）/ 普通商品（白）/ 秒杀商品（红）。⚠️ 与左边「类型」不是一回事：那列是 `goods.GoodType` 类目（电子用品…），这列是**这件商品此刻挂在哪个营销活动里**。判据 = 该商品名下的全部 `ProductCode` 有没有落进 `subsidy_goods` / `seckill_activity` 的码集合（见脚本里 `kindsOf`）。⚠️ 多型号商品**并排画多枚**（有几类画几枚），不二选一；一件商品可以既国补又秒杀。">商品类别</th>
                <th class="c-num">单价</th>
                <th class="c-num" title="占位：goods 表无库存列">库存</th>
                <th class="c-num" title="占位：productsales.OrderVolume 恒为 0 且无 SELECT 读出">销量</th>
                <th title="C3 块 3 起是真状态：已上架（goods 表）／待审・已驳回（goods_audit 表）—— 两表并渲染">状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <!-- ⚠️ 行级点击**保留既有真事件**（原卡片墙是 `@click="openModal(item)"` 打在整卡上）⇒ 表体照落
                   同一事件；「操作」列那格用 `@click.stop` 拦住冒泡，免得【编辑】触发两次 `openModal`。⚠️ C3 块 3
                   起加**前置判据**：只有 `onshelf` 行可点（待审 / 驳回行在 `goods` 表里压根不存在，取不到规格）。 -->
              <tr v-for="item in pageRows" :key="item.state + ':' + item.goodName" @click="item.state === 'onshelf' && openModal(item)">
                <td class="c-ck">
                  <!-- 稿外死控件（稿 `:529` 的行内模板）：稿里带 `title` ⇒ 逐字取稿 -->
                  <button
                    type="button"
                    class="ck"
                    disabled
                    aria-checked="false"
                    title="勾选（纯前端；批量动作无端点）"
                  ></button>
                </td>
                <td class="c-img">
                  <!-- 稿 `:530` 的 `.th-img` 是灰方块（`background:var(--img)`）⇒ 保留**既有真图**
                       （`item.goodImg`）。⚠️ 补 `v-if`：`goodImg` 为空时 `<img src="">` 画的是浏览器
                       的**破图图标**，加 `v-if` 正好露出稿的灰底（同 `HistoricalOrders.vue:66` 先例）。 -->
                  <div class="th-img"><img v-if="item.goodImg" :src="item.goodImg" alt="商品图"></div>
                </td>
                <td class="c-name">
                  <div class="nm">
                    {{ item.goodName }}
                    <!-- ⚠️ 稿 `:531` 的这行码是它**编的**（`STD-GRAY` 等 8 个），而本页的真值就在
                         `goodsList` 里（`showGoodsList` 的同名合并只留了最后一条、没带 ProductCode）
                         ⇒ 用模板内联表达式按名取回**真码**，**不新增 script 函数**（D 笔边界）。
                         口径同 commit 10 的「单规格」：编的码不落，能取到真值就落真值。 -->
                    <div class="pcode">{{ goodsList.filter(g => g.GoodName === item.goodName).map(g => g.ProductCode).filter(Boolean).join(' / ') || '—' }}</div>
                  </div>
                </td>
                <td>{{ item.goodType }}</td><td title="同表头。⚠️ 本表已按 `GoodName` 合并（同名只留一条）⇒ 多型号商品在此只有一行，故按名回 `goodsList` 取回**全部** `ProductCode` 再求类别，见脚本里 `kindsOf`。"><span v-for="k in kindsOf(item.goodName)" :key="k" class="kind" :class="k">{{ KIND_TEXT[k] }}</span></td>
                <td class="c-num">
                  <span class="px"><span class="u">¥</span><span class="i">{{ formatPrice(item.price).int }}</span><span class="d">.{{ formatPrice(item.price).dec }}</span></span>
                </td>
                <td class="c-num"><span class="na" title="占位：goods 表无库存列">—</span></td>
                <td class="c-num"><span class="na" title="占位：OrderVolume 恒为 0 且无 SELECT 读出">—</span></td>
                <!-- C3 块 3：状态列**不再恒为常量** —— `rows[].state` 有三值（`onshelf` / `pending` /
                     `rejected`），驳回理由挂在 `title` 上（不占列宽）。⚠️ 稿 `:537` 的常量是块 3 前口径。 -->
                <td><span class="state" :class="item.state" :title="item.state === 'rejected' ? ('驳回理由：' + (item.rejectReason || '—')) : (item.state === 'pending' ? '已提交，等待管理员审核' : '在架')"><i class="d"></i>{{ STATE_TEXT[item.state] }}</span></td>
                <td>
                  <div v-if="item.state === 'onshelf'" class="op" @click.stop>
                    <!-- 【编辑】既有真功能 `openModal`（稿 `:539` 的 title 去掉尾部自引后逐字取稿）。⚠️ 待审 / 驳回行**整格不出操作**，见上一行的 `v-if` -->
                    <button type="button" title="打开商品详情（现有功能 openModal）" @click="openModal(item)">编辑</button>
                    <!-- 【下架】稿外死控件：稿 `:540` 那儿是 `<button class="del" title="下架这一件（现有端点
                         goods.goodsDe）">`（**无 `disabled`**）⇒ 按 §三 落 `disabled` + **逐字取稿**的 title；
                         ⚠️ 不接线的理由**不进 title**（逐字取稿优先），归属 `C3〈商品〉`，理由见 slot-registry §三 -->
                    <button
                      type="button"
                      class="del"
                      disabled
                      title="下架这一件（现有端点 goods.goodsDe）"
                    >下架</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 预留位 ④（稿 `:414`）—— 块级。内部 3 枚页码钮是稿外死控件（稿里只有 `disabled`）。
           ⚠️ 稿 `.sum` 写死「共 8 件」⇒ 落**真条数**，与页头同一口径。 -->
      <!-- 块 7 收尾 · P1：原「预留 · 分页（动现有代码 3 处）」占位档已兑现 ——
           `goodsSelect` 后端硬夹 200 行（`GoodsCacheService.java:227-229`），本页按合并后的行做前端分页（默认每页 20）。 -->
      <Pager
        :total="visibleList.length"
        :limit="pageLimit"
        :offset="pageOffset"
        :sizes="[20, 50, 100]"
        unit="件"
        label="商品"
        @change="onPageChange"
      />
    </template>

    <!-- ── 按名称精确下架（稿 `.sec` `:260-272` / 模板 `:422-431`）────────────────────
         ⚠️ **既有真功能**（`doDeleteGoods`，逐字未动、**不 `disabled`**）。
            稿注（`:103-105`）写它原与工具条那个搜索框长得几乎一样、用途完全不同（一个查一个删）
            ⇒ v4 把它从表格**上方**挪到**下方**并加了小标题，本笔照稿的位置落。
         ⚠️ `v-model` 两个绑定与原实现一致（`delGoodName` / `delProductCode`）。 -->
    <section class="sec">
      <div class="sec-h">按名称精确下架</div>
      <div class="sec-note">现有实现：按商品名与 ProductCode 精确下架，两个都要填。</div>
      <div class="delbar">
        <input v-model="delGoodName" type="text" placeholder="商品名">
        <input v-model="delProductCode" type="text" placeholder="ProductCode">
        <button type="button" class="btn-danger" @click="doDeleteGoods"><IconGlyph name="no" />下架商品</button>
      </div>
    </section>
  </main>

  <!-- ═══ 商品详情弹窗（稿 `data-page="dlg"` 面 `:467-512`）═════════════════════════
       ⚠️ 遮罩 / 内容的事件分配**逐字保留原实现**：遮罩点击关窗、`.dlg` 自己 `@click.stop`。 -->
  <div v-if="showModal" class="scrim" @click="closeModal">
    <div class="dlg" @click.stop>
      <div class="dlg-h">
        <h2>商品详情</h2>
        <!-- 【×】= 既有真功能（`closeModal`）。稿 `:476` 的 title 是「关闭（现有功能）」⇒ 逐字取稿。 -->
        <button type="button" class="x" title="关闭（现有功能）" @click="closeModal">×</button>
      </div>

      <div class="dlg-body">
        <div>
          <!-- 稿 `:480` 的 `.pic` 是灰方块（`background:var(--img)`）⇒ 保留**既有真预览**
               `<img :src="editForm.goodImg">`（原实现就有，取值链未动），点击走同一个 `chooseNewPic`。 -->
          <div class="pic" title="点击更换商品图（现有功能 chooseNewPic）" @click="chooseNewPic">
            <img :src="editForm.goodImg" alt="商品">
          </div>
          <input ref="picFile" type="file" accept="image/*" class="file-hidden" @change="doChangePic">
          <div class="pic-hint">点击图片更换</div>
        </div>

        <div>
          <div class="fl">
            <label>商品名称</label>
            <input v-model="editForm.goodName" type="text">
          </div>
          <div class="fl">
            <label>商品类型</label>
            <!-- ⚠️ 这个 `disabled` 是**既有实现就锁死的**（原 `<input v-model="editForm.goodType" disabled>`），
                 **不是占位** ⇒ 不挂 `data-slot`、不进 §三。稿 `:487-489` 的注释逐字写的就是这一条。 -->
            <input v-model="editForm.goodType" type="text" disabled title="既有实现锁死：商品类型不可改">
          </div>
          <div class="fl">
            <label>商品规格（ProductCode）</label>
            <!-- 规格标签 = **既有真功能**（`switchPrice(c)` 会去查该规格的价格 + 图片）⇒ 真事件。 -->
            <div class="skus">
              <button
                v-for="c in editForm.productCodeList"
                :key="c"
                type="button"
                class="sku"
                :class="{ on: editForm.productCode == c }"
                @click="switchPrice(c)"
              >{{ c }}</button>
            </div>
          </div>
          <div class="fl">
            <label>商品价格</label>
            <input v-model="editForm.price" type="text">
          </div>

          <!-- 预留位 ⑤（稿 `:499`）—— 块级 `.fl` ⇒ 只挂 `data-slot` + `title`。
               ⚠️ 内部那个 `<input class="slot" value="—" disabled>` 是稿外死控件（稿里带 `title`）。 -->
          <div
            class="fl"
            data-slot="预留 · 库存（动现有代码 5 处 · 超档）"
            title="待 C3〈商品〉——goods 表无库存列（INSERT 列序实测 7 列）：mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsUp.vue = 5 处，**超档**"
          >
            <label>库存</label>
            <input class="slot" type="text" value="—" disabled title="待 C3〈商品〉——goods 无库存列，5 处改动（超档）">
          </div>

          <!-- ═══ 商品券（C3 块 5 · A 组 · A2 + A3，2026-10-12）══════════════════════════
               商家券 = **本商品独有**的一类券：在这一块填、随「更新商品数据」**一起提交**，
               生不生效由**同一条商品审核链**（`goods_audit`）定 —— 管理员在「商品审核」里一并放行 / 驳回
               （后端 `GoodsController.goodsUp` 建券 + 提审、`GoodsAuditService.audit` 通过时启用）。
               ⚠️ **判据是「券名非空」**（后端逐字 `CouponName != null && !CouponName.trim().isEmpty()`）：
                  整块留空 = 这回只改商品、不发券。**没有「删券」路径** —— 留空不会撤掉已生效的券。
               ⚠️ **归属不由用户选**：`Scope='merchant'` + `MerchantAccount=本店` 全在后端钉死
                  ⇒ 这里刻意**没有**「归属商家」输入框（A1 从管理端拿掉的就是那个字段；商家发券只可能是自己的）。
               ⚠️ 校验**以后端为准**（`CouponService.validate`）：类型 / 面值（折扣须 `< 1`）/ 面值>0 /
                  总量>0 / 止晚于起 —— 前端不抄一份，免得两边判据各说各话（提交失败会原话回显在 `alert` 里）。
               ⚠️ 起止在表单里按**浏览器本地时区**挑（`datetime-local`）⇒ 发出去前**必须**反算成库内
                  时钟文本（UTC），同 `admin/MarketingPage.vue` 的 `toSqlTime`（那边 2026-10-11 手验踩过
                  「刚建好就领不了」，根因就是漏了这一步）。 -->
          <div class="fl">
            <label>商品券 （可空 · 填了随商品一起提交审核）</label>
            <div class="cp-form">
              <input v-model="editForm.couponName" class="cp-wide" type="text" placeholder="券名（如「满 100 减 10」）">
              <select v-model="editForm.couponType">
                <option value="threshold">满减</option>
                <option value="percent">折扣</option>
                <option value="none">无门槛</option>
              </select>
              <input
                v-model="editForm.couponFaceValue"
                type="text"
                :placeholder="editForm.couponType === 'percent' ? '折扣率（0.85 = 8.5 折）' : '面值 ¥'"
              >
              <input
                v-if="editForm.couponType !== 'none'"
                v-model="editForm.couponThreshold"
                type="text"
                placeholder="门槛 ¥（可空 = 无门槛）"
              >
              <input v-model="editForm.couponTotalQty" type="text" placeholder="发放总量">
              <input v-model="editForm.couponStartAt" type="datetime-local" title="有效期起（本地时区，可空）">
              <input v-model="editForm.couponEndAt" type="datetime-local" title="有效期止（本地时区，可空）">
            </div>
          </div>
        </div>
      </div>

      <div class="dlg-foot">
        <!-- 【取消】= 稿 `:507` 补的一枚（原实现只有「×」）⇒ 接**同一个** `closeModal`
             （不新增能力，功能量 +0）。 -->
        <button type="button" class="no" @click="closeModal">取消</button>
        <button type="button" class="save" title="现有端点 goods.goodsUp" @click="doUpdateGoods"><IconGlyph name="ok" />更新商品数据</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { http, handleApiError, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'; import { subsidyGoods, seckillLive } from '../api/marketing'   // ⚠️ 同样**同行**（同上一条手法）：本文件被 `api-contract.md` 与 `slot-registry.md` 按行号钉住 ⇒ 合并到既有行 = 零漂移
import { getMerchantId, isLoggedIn } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'; import Pager from '../components/Pager.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移（`api-contract.md` 按 `GoodsUp.vue:170/215/275/327` 钉住本文件）

const goodsList = ref([])
const userInfo = ref({})
const showGoodsList = ref([])

// 下架
const delGoodName = ref('')
const delProductCode = ref('')

// 弹窗
const showModal = ref(false)
const editForm = ref({
  goodName: '',
  goodType: '',
  goodImg: '',
  price: '',
  productCode: '',
  productCodeList: [],
  // ── 商品券（A2/A3，2026-10-12）──────────────────────────────────────────────────
  // ⚠️ 默认**全空** = 这次改商品**不挂券**（后端只看 `couponName` 非空）；`couponType` 给个
  //    `'threshold'` 只是让下拉有个初始态，**不是**「默认发满减券」—— 券名空着就整块不发。
  couponName: '',
  couponType: 'threshold',
  couponFaceValue: '',
  couponThreshold: '',
  couponTotalQty: '',
  couponStartAt: '',
  couponEndAt: '',
})
const picFile = ref(null)

// ── 券的时间字段：本地 `datetime-local` → 库内时钟文本（UTC）（A2/A3，2026-10-12）────
// ⚠️ 与 `admin/MarketingPage.vue` 的 `toSqlTime` **同款**（那边 2026-10-11 手验修的正是这一步）。
//    本仓既有做法是**各文件自带一份**（`coupon-format.js` 里 `toLocal` 已有 7 份同款副本），
//    ⇒ 这里不抽公共模块，抄同款（两边都被按行号引，动一处要连带核另一处）。
// 起补 `:00`、止补 `:59`：用户挑到哪一分就用满那一分。⚠️ 止补 `:59` 还有个实际作用 —— 起止挑成
// 同一分钟时窗口是 59 秒而不是 0 秒，否则后端「止必须晚于起」的校验会当场拒掉一个看着没问题的输入。
const pad = (n) => String(n).padStart(2, '0')
const toSqlTime = (localValue, isEnd) => {
  if (!localValue) return ''
  const d = new Date(localValue) // 无偏移的 date-time ⇒ 按**本地**时区解析（ES2016+）
  if (Number.isNaN(d.getTime())) return ''
  d.setSeconds(isEnd ? 59 : 0, 0)
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  )
}

// ------------------------------
// 初始化
// ------------------------------
onMounted(() => {
  getUserInfo()
  getGoodsList()
})

// ------------------------------
// 获取用户信息
// ------------------------------
const getUserInfo = async () => {
  if (!isLoggedIn()) return   // P2.6：判据由「有 username」改成「有登录态」（会话恢复来的身份同样算）
  // ⚠️ 不再读密码（`getPassword` 已随 P2.6 退场）：本页只取展示用资料，不做凭据校验。

  try {
    // P2.6：改走**会话驱动**的 /user/me（无参）；`allowUnauthorized` 让 401 照抛给下面的 catch
    const res = await http.post(endpoints.user.me, null, { allowUnauthorized: true })
    const data = await res.json()
    userInfo.value = data
  } catch (e) {
    handleApiError(e, '获取用户信息失败')
  }
}

// ------------------------------
// 获取商品列表 + 同名合并（只显示一个）
// ------------------------------
const getGoodsList = async () => {
  const merchantId = getMerchantId()
  if (!merchantId) return

  try {
    const res = await http.post(endpoints.goods.goodsSelect, new URLSearchParams({ MerchantId: merchantId }))
    const data = await res.json()
    if (data.success) {
      goodsList.value = data.list

      // 同名合并，价格取最后一个
      const map = {}
      data.list.forEach(item => {
        map[item.GoodName] = item
      })
      showGoodsList.value = Object.values(map).map(item => ({
        goodName: item.GoodName,
        price: item.Price,
        goodImg: item.GoodImg,
        goodType: item.GoodType
      }))
    }
  } catch (e) {
    handleApiError(e, '获取商品失败')
  }
}

// ------------------------------
// 价格格式化
// ------------------------------
const formatPrice = (val) => {
  const num = parseFloat(val || 0).toFixed(2)
  const [intPart, decPart] = num.split('.')
  return { int: intPart, dec: decPart }
}

// ------------------------------
// 下架商品
// ------------------------------
const doDeleteGoods = async () => {
  if (!delGoodName.value || !delProductCode.value) {
    alert('请输入商品名和ProductCode')
    return
  }
  const merchantId = getMerchantId()
  try {
    const res = await http.post(endpoints.goods.goodsDe, new URLSearchParams({
        MerchantId: merchantId,
        GoodName: delGoodName.value,
        ProductCode: delProductCode.value
      }))
    const data = await res.json()
    if (data.success) {
      alert('下架成功')
      getGoodsList()
      // 清空输入框
      delGoodName.value = ''
      delProductCode.value = ''
    } else {
      alert('失败：' + data.msg)
    }
  } catch (e) {
    alert('请求失败')
  }
}

// ------------------------------
// 打开弹窗
// ------------------------------
const openModal = async (item) => {
  const all = goodsList.value.filter(i => i.GoodName === item.goodName)
  const codeList = [...new Set(all.map(i => i.ProductCode))]

  editForm.value = {
    goodName: item.goodName,
    goodType: item.goodType,
    goodImg: item.goodImg,
    price: item.price,
    productCode: codeList[0],
    productCodeList: codeList,
    // ⚠️ **每次开窗都清空**：这块是「本次提交要新建的券」，不是「这件商品已发的券」的回显
    //    （本页**没有**查「该商品已有券」的端点 ⇒ 别在这里假装回显，那会让人以为改的是同一张券）。
    couponName: '',
    couponType: 'threshold',
    couponFaceValue: '',
    couponThreshold: '',
    couponTotalQty: '',
    couponStartAt: '',
    couponEndAt: ''
  }

  // 🔥 修复：自动加载默认选中的第一个规格价格
  if (codeList.length > 0) {
    await switchPrice(codeList[0])
  }

  showModal.value = true
}

const closeModal = () => {
  showModal.value = false
}


// 切换 ProductCode → 刷新价格 + 图片
const switchPrice = async (code) => {
  const merchantId = getMerchantId()
  try {
    const res = await http.post(endpoints.goods.productCodePrice, new URLSearchParams({
        MerchantId: merchantId,
        GoodName: editForm.value.goodName,
        ProductCode: code
      }))
    const data = await res.json()
    if (data.success) {
      editForm.value.price = data.price       // 价格
      editForm.value.goodImg = data.goodImg   // 🔥 图片同步更新
      editForm.value.productCode = code
    }
  } catch (e) {
    // 空 catch 块修复
  }
}

// ------------------------------
// 更换图片
// ------------------------------
const chooseNewPic = () => {
  picFile.value.click()
}

const doChangePic = (e) => {
  const file = e.target.files[0]
  if (file) {
    editForm.value.goodImg = URL.createObjectURL(file)
  }
}

// ------------------------------
// 更新商品（含图片上传）
// ------------------------------
const doUpdateGoods = async () => {
  const merchantId = getMerchantId()
  const formData = new FormData()

  formData.append('MerchantId', merchantId)
  formData.append('GoodName', editForm.value.goodName)
  formData.append('Price', editForm.value.price)
  formData.append('GoodType', editForm.value.goodType)
  formData.append('ProductCode', editForm.value.productCode)

  if (picFile.value.files.length > 0) {
    formData.append('file', picFile.value.files[0])
  }

  // ── 商品券（A2/A3）：**「券名」非空才算挂了券**（与后端判据逐字一致，见模板那段注释）──
  // ⚠️ 只 append **非空**的键：后端全是 `required=false` + Service 手判，一个空串会被当成
  //    「传了空值」而非「没传」（同 `api/marketing.js` 里 `q()` 那条理由）。
  if (editForm.value.couponName.trim()) {
    const put = (k, v) => {
      if (v !== undefined && v !== null && String(v).trim() !== '') formData.append(k, String(v).trim())
    }
    put('CouponName', editForm.value.couponName)
    put('CouponType', editForm.value.couponType)
    put('CouponFaceValue', editForm.value.couponFaceValue)
    put('CouponThreshold', editForm.value.couponThreshold)
    put('CouponTotalQty', editForm.value.couponTotalQty)
    // ⚠️ 起止必须**反算成库内时钟（UTC）**再发 —— 表单里是本地时区的 `datetime-local`。
    put('CouponStartAt', toSqlTime(editForm.value.couponStartAt, false))
    put('CouponEndAt', toSqlTime(editForm.value.couponEndAt, true))
  }

  try {
    const res = await http.post(endpoints.goods.goodsUp, formData)
    const data = await res.json()
    if (data.success) {
      // ⚠️ 回了 `msg` 就用它 —— 带券成功时后端说的是「商品更新成功；商品券已提交审核」、
      //    券没建成时是「…商品券未提交：<原因>」。两种情况**都不是**一句「更新成功」能说清的
      //    （商品那半确实成功了，券那半没有）⇒ 别把它压成一句。
      alert(data.msg ? data.msg : '更新成功')
      closeModal()
      getGoodsList()
    } else {
      alert('失败：' + data.msg)
    }
  } catch (e) {
    alert('更新失败')
  }
}

// ══════════════════════════════════════════════════════════════════════════════
// C3 块 3〈商品块〉（2026-10-10）：状态列**并表** + 筛选接线
//
// ⚠️ **为什么必须并表**：提审制下 `goodsAdd` 只写 `goods_audit(pending)`、**不**写 `goods` 行
//    ⇒ `goodsSelect` 永远只回已上架的，商家刚提交的东西在本页**看不见**。故并
//    `/goods/myApplications`（商家号维度，带 `Status` / `RejectReason`）。
// ⚠️ **为什么四个筛选全在前端**：`rows` 已是本商家商品的**全集**（两路都按 `MerchantId` 取全量，
//    `goodsSelect` 的 `SELECT *` 无 LIMIT、无分页参数）⇒ 再发一轮请求既没必要、也没有那个端点
//    （服务端筛选才是「动现有代码 3 处」那档）。本笔**新端点 0 条**。
// ⚠️ 本段一律**追加在 `<script setup>` 末尾**：它上面的行被 `slot-registry.md` §十七的漂移表
//    （`:1725-1736`，带「现应指」列）按行号引 —— 在其上方插行会让整列失效。
// ══════════════════════════════════════════════════════════════════════════════

// 提审单（待审 / 驳回）—— 与 `showGoodsList`（已上架）并成 `rows`
const appsList = ref([])

// 筛选：`flt` 是**编辑中**的值，`applied` 是**已生效**的快照 —— 【查询】那一下才提交
// （不点【查询】就按上一次的快照筛；两个对象同时换，避免「改一个字段即时生效」的不一致观感）
const flt = ref({ name: '', code: '', state: '', type: '' })
const applied = ref({ name: '', code: '', state: '', type: '' })
const applyFlt = () => { applied.value = { ...flt.value }; pageOffset.value = 0 }   // 换筛选 ⇒ 回第一页

// 状态三态的唯一文案表（`rows[].state` 的取值）= 值；下拉与状态列**共用**它，避免两处写法飘
const STATE_TEXT = { onshelf: '已上架', pending: '待审', rejected: '已驳回' }
const stateOptions = Object.keys(STATE_TEXT)

// 分类下拉：**从已加载的行里取**（本商家自己的商品类型）—— 不发 `goodstype`，0 端点
const typeOptions = computed(() => [...new Set(rows.value.map((r) => r.goodType).filter(Boolean))])

// 两路并表：已上架（`showGoodsList`，同名合并后）∪ 待审 / 驳回（`appsList`）
// ⚠️ `approved` 的单**不进**本表 —— 通过后商品已落在 `goods` 里（`goodsSelect` 会回），
//    再并一次就是同一件商品出现两行。
// ⚠️ 判重口径 `(GoodName, ProductCode)`：与后端 CAS 谓词同粒度（`GoodsAuditService`）。
const rows = computed(() => {
  const out = showGoodsList.value.map((g) => ({ ...g, state: 'onshelf' }))
  const seen = new Set()
  appsList.value.forEach((a) => {
    if (a.Status !== 'pending' && a.Status !== 'rejected') return
    const key = `${a.GoodName}\u0000${a.ProductCode}`
    if (seen.has(key)) return
    seen.add(key)
    out.push({
      goodName: a.GoodName,
      price: a.Price,
      goodImg: a.GoodImg,
      goodType: a.GoodType,
      state: a.Status,
      rejectReason: a.RejectReason,
    })
  })
  return out
})

// 生效筛选后的可见行（表格 `v-for` / 分页计数 / 空态都读它）
const visibleList = computed(() => {
  const f = applied.value
  return rows.value.filter((r) => {
    if (f.state && r.state !== f.state) return false
    if (f.type && r.goodType !== f.type) return false
    if (f.name && !String(r.goodName || '').includes(f.name)) return false
    if (f.code) {
      // ProductCode 不在 `rows` 里（同名合并只留了最后一条）—— 按名回查 `goodsList`，同 `:153` 的口径
      const codes = goodsList.value.filter((g) => g.GoodName === r.goodName).map((g) => g.ProductCode)
      if (!codes.some((c) => String(c || '').includes(f.code))) return false
    }
    return true
  })
})

// 在架条数：页头「共 N 件上架中」只说**在架**（待审 / 驳回不是「上架中」）
const onshelfCount = computed(() => rows.value.filter((r) => r.state === 'onshelf').length)

// ── 分页（块 7 收尾 · P1）───────────────────────────────────────────────────
// `goodsSelect` 本身已由后端硬夹到 200 行（`GoodsCacheService.listByMerchant`），这里在
// **已取回的合并行**上前端分页：默认每页 20，标准分页器（`components/Pager.vue`）。
// ⚠️ 它分的是「上架 ∪ 待审/驳回」的合并结果（`visibleList`），不是服务端的一页 —— 因为本表
//    本来就要并两个来源。>200 件的商家登记在《长列表接口盘点报告》，真正修法是后端真分页。
const pageLimit = ref(20)
const pageOffset = ref(0)
const pageRows = computed(() => {
  const total = visibleList.value.length
  const maxOffset = Math.max(0, Math.ceil(total / pageLimit.value) - 1) * pageLimit.value
  const off = Math.min(Math.max(0, pageOffset.value), maxOffset)
  return visibleList.value.slice(off, off + pageLimit.value)
})
function onPageChange({ limit, offset }) {
  pageLimit.value = limit
  pageOffset.value = offset
}

// 提审历史（待审 / 驳回的来源）。⚠️ `MerchantId` 是「调用者自己」⇒ 后端过 `IdentityGuard`。
const getMyApplications = async () => {
  const merchantId = getMerchantId()
  if (!merchantId) return
  try {
    const res = await http.post(endpoints.goodsB3.myApplications, new URLSearchParams({ MerchantId: merchantId }))
    const data = await res.json()
    if (data.success) appsList.value = data.list || []
  } catch (e) {
    handleApiError(e, '获取提审状态失败')
  }
}

// ⚠️ **另起一个** `onMounted` 而不是并进 `:319` 那个：并进去要往上插行（见本段块首第 3 条）。
onMounted(() => { getMyApplications() })

// ── C3 块 5〈商品类别〉：本店商品挂在哪个营销活动里（2026-10-12 用户裁定 ④ 问 2）─────────────
// 需求逐字：「商家『查看商品』加一列 · 列名：商品类别 · 值：国补（绿）/ 普通商品（白）/
// 秒杀商品（红）· 多型号商品 → 显示所有涉及类别标签。」
//
// 【判据】「这件商品在不在国补 / 秒杀里」——两个活动侧都按 **`ProductCode`** 记（`subsidy_goods`
// / `seckill_activity` 的列；两个端点的 `list[]` 形状见 `api/marketing.js` 各自 javadoc 逐字列出的
// 字段：`subsidyGoods` 有 `ProductCode`，`seckillLive` 是「活动列 + GoodImg/GoodType/ProductCode」）
// ⇒ 取两个端点各自的 `ProductCode` 集合，再看要判的这一行**名下的全部** `ProductCode` 落不落在里面。
// ⚠️ **为什么是「名下的全部 ProductCode」而不是「这一行自己的」**：本表 `showGoodsList` 是**按
// `GoodName` 合并**过的（同名只留最后一条、没带码 —— 见本文件 `getGoodsList` 上方那段注释）
// ⇒ 多型号商品（同一个 `GoodName`、几个 `ProductCode`）在本表里**只有一行**。用户裁定「多型号商品
// 显示所有涉及类别标签」⇒ 必须按名回 `goodsList` 取回全部码求并，**不能**只看合并后剩下的那一个。
//    （手法与「商品编号」那列的模板内联表达式**同源** —— 同一个 `goodsList.filter(g => g.GoodName
//     === …)`，此处只是把取回的码拿去查集合，不重复造取数路径。）
//
// ⚠️ **两个端点都是「登录可见、匿名 401」**（`api/marketing.js` 两段段头逐字：不挂 `IdentityGuard`，
//    也**不在** `AUTH_WHITELIST`）⇒ 未登录直接不发。失败时**两个集合都清空**、退化成「普通商品」，
//    并且**不弹错、不把整页打成错误态** —— 这是本表的一个**附加列**，它取不到不该连累商品列表。
//    （口径同「数据不足不显」的邻居：宁可少画一枚标签，也不画错一枚。）
//
// ⚠️ **一件商品可以既国补又秒杀** ⇒ 标签是**数组**、有几类画几类，**不做二选一**。
//    `ProductPage.vue` 的型号按钮那里有同样的「两者都命中」情形，**那里**的裁量是取秒杀（因为
//    它要选**一个**底色）；本表是**列类别**、不是选状态 ⇒ 两枚都画。两处口径不同是**有意的**。
//
// ⚠️ **不过滤 `Stage`**（2026-10-12 自裁，报备）：`seckillLive` 回的 `Stage` 是后端按**此刻**算出来的
//    （`signup` / `upcoming` / `live`）—— 那是**状态**不是**类别**。按 `live` 过滤会让一场秒杀
//    结束的当天，商品就从「秒杀商品」掉回「普通商品」，而本列问的是「这商品**属于**秒杀商品」。
//    故这里一律不过滤（已结束 / 已停用的活动后端本来就挡了，见同一段 javadoc）。
const KIND_TEXT = { subsidy: '国补', seckill: '秒杀商品', normal: '普通商品' }
const subsidyCodes = ref(new Set())
const seckillCodes = ref(new Set())

const loadPromoKinds = async () => {
  if (!isLoggedIn()) return
  try {
    const [sub, sek] = await Promise.all([subsidyGoods({ limit: 200 }), seckillLive({ limit: 200 })])
    subsidyCodes.value = new Set(((sub && sub.list) || []).map((r) => String(r.ProductCode || '')).filter(Boolean))
    seckillCodes.value = new Set(((sek && sek.list) || []).map((r) => String(r.ProductCode || '')).filter(Boolean))
  } catch (e) {
    // 见上面第 3 条 ⚠️：失败 = 不画标签，不是画错标签；也不打扰用户（附加列，不是主数据）
    subsidyCodes.value = new Set()
    seckillCodes.value = new Set()
  }
}

// 一行的类别标签。`normal` 是**兜底**：两类都不沾 ⇒ 普通商品（不是「未知」——「没挂活动」就是普通）。
const kindsOf = (goodName) => {
  const codes = goodsList.value
    .filter((g) => g.GoodName === goodName)
    .map((g) => String(g.ProductCode || ''))
    .filter(Boolean)
  const out = []
  if (codes.some((c) => subsidyCodes.value.has(c))) out.push('subsidy')
  if (codes.some((c) => seckillCodes.value.has(c))) out.push('seckill')
  return out.length ? out : ['normal']
}

// ⚠️ **另起一个** `onMounted`（理由同上面 `getMyApplications` 那条）：并进既有块要往上插行。
onMounted(() => { loadPromoKinds() })
</script>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════════════
   取自 `docs/美化/pages/GoodsUp.html`：
     `:149-153`（基础重设 + `body`）`:175-181`（`.head*` / `.ava`）
     `:183-208`（`.tools*` / `.find*` / `.bulk*` / `.btn-quiet` / `.btn-primary*` / `.ck*`）
     `:210-251`（`.tbl-zone` / `.tbl-wrap` / `.tbl*` / `.th-img` / `.nm*` / `.px*` / `.na` /
                  `.state*` / `.op*` / `.empty*`）
     `:253-258`（`.pager*`）`:260-272`（`.sec*` / `.delbar*` / `.btn-danger*`）
     `:274-312`（`.scrim` / `.dlg*` / `.pic*` / `.fl*` / `.skus` / `.sku*` / `.dlg-foot*`）
     `:314-328`（两条 `@media`）

   本次「从稿到 Vue」的**适配**，逐条登记：
     ① 稿的全局重设 `body{}` / `a{}` / `button,input{}` / `button{}`（稿 `:149-153`）里的
        后三件**必须搬进 scoped 且限定本页** —— 稿的 `.ck` / `.btn-quiet` / `.op button` /
        `.pager button` 等都是裸 `<button>`，不重设就会带上 UA 的 `background: rgb(240,240,240)` /
        `border: 2px outset` / `font-family: Arial`（commit 7 / 8 / 10 都实踩过）。
        ⚠️ 本页多两件稿里没有的：`a{}`（【上架商品】现在是 `<router-link>` ⇒ 渲染成 `<a>`，
        不给它 `color:inherit;text-decoration:none` 就是蓝字带下划线）；`select{}`（本页的
        分类 / 状态两个下拉要跟 `input` 同字体）。
        ⚠️ `body{font-family|font-size|color}` + `background` **落到本页根 `.uc-main`**，
        `background` 不搬（壳 `.app-shell` 已铺 `var(--bg)`，再铺一次会把右栏画成另一块底）。
     ② 稿的 `.nav-bar` / `.wrap` / `.body` / `.uc-menu` **不进本组件**（落点 `AppShell.vue` /
        `UserCenter.vue`）⇒ 未搬。
     ③ ⚠️ **稿 `:root{--d2: calc(var(--fs-2xl) * 1.00)}`（稿 `:128`）必须跟着搬** —— 稿 `:179`
        的 `.head h1` 写的是 `font-size:var(--d2)`；`tokens.css` 里**没有** `--d2`（它只定义
        `--fs-*`）⇒ scoped 块若不自己定义，`var(--d2)` 在**计算值阶段**判为无效、整条声明被丢弃，
        标题**静默回落到继承来的 `--fs-sm`（15px）**，与正文同大。落法同 `UserInfoPage.vue:393` /
        `GoodsAdd.vue:619`（写在本页根 `.uc-main` 上，`calc(x * 1.00)` ≡ `x` ⇒ 写 `var(--fs-2xl)`；⚠️ 2026-10-10〈模板表通用化〉重指：原 513 行 ⇒ 该文件插模板段后 +104）。
        ⚠️ 这只在**算出来的** 36px 上看得出来，肉眼扫模板扫不出 ⇒ `shot11.mjs` 已加 `h1Fs` 断言。
     ④ `.btn-primary` 由稿的 `<button>` 改成 `<router-link>`（判据：入口指向已存在路由）⇒
        **补 `display:inline-flex;align-items:center`**：`<a>` 是行内元素，不补则稿的
        `height:36px` / `padding:0 var(--s5)` 全部不生效（按钮会缩成一行文字）。
     ⑤ 三张真图各有新增的适配类（稿里这三处都是灰方块，没有 `<img>`）：
        `.ava img`（既有真头像）/ `.th-img img`（既有真商品图）/ `.pic img`（弹窗里的既有真预览）
        —— 一律 `object-fit:cover` 撑满稿的框，并 `border-radius:inherit` 跟着外框圆角。
     ⑥ `.file-hidden` 取代原实现那个 file input 的行内 `style="display:none"`。
     ⑦ 表格行**保留既有真事件**（原卡片墙打在整卡上的 `@click="openModal(item)"`）⇒ 补一行
        `.tbl tbody tr{cursor:pointer}` 给点击一个视觉提示（稿的表格行只做 hover 底色，不可点）。
     ⑧ 稿的两条 `@media` 里 `.body` / `.uc-menu` 两条不搬（不在本组件）⇒ 见文末。
   ⚠️ `box-sizing` **不在此重复** —— `tokens.css` 的 `*` 重设已是全局。
   ══════════════════════════════════════════════════════════════════════════════ */

/* ── 稿 `:151-153` 的重设，限定本页 ─────────────────────────────────────────── */
button, input, select { font: inherit; color: inherit; }
button { background: none; border: 0; cursor: pointer; }
a { color: inherit; text-decoration: none; }

/* ── 本页根 ────────────────────────────────────────────────────────────────── */
.uc-main {
  --d2: var(--fs-2xl);          /* 稿 `:128` 的 `:root` 落点，见上面适配 ③ */
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 页头（稿 `:175-181`）—— 身份并进来，不再单独占一条横带 ──────────────────── */
.head { display: flex; align-items: center; gap: var(--s5); padding-bottom: var(--s5); border-bottom: 1px solid var(--line-2); }
.ava { width: 64px; height: 64px; flex: 0 0 auto; border-radius: var(--r2); background: var(--img); border: 1px solid var(--line); overflow: hidden; }
.ava img { width: 100%; height: 100%; object-fit: cover; display: block; }
.head h1 {
  font-family: var(--font-display);
  font-size: var(--d2); font-weight: var(--fw-med); line-height: 1.15; letter-spacing: -.02em;
}
.head .sub { margin-top: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 工具条：**显式两行**（稿 `:183-208`；折行只发生在行内）──────────────────── */
.tools { margin-top: var(--s6); display: flex; flex-direction: column; gap: var(--s5); }
.tools .r1, .tools .r2 { display: flex; align-items: center; gap: var(--s3); flex-wrap: wrap; }
.find { display: flex; gap: var(--s2); align-items: center; }
.find input, .find select {
  height: 36px; padding: 0 var(--s3); border: 1px solid var(--line-2);
  border-radius: var(--r2); background: var(--surface); color: var(--ink);
}
.find input { width: 160px; }
.find select { width: 132px; }
/* ⚠️ C3 块 3：这四件已**启用** ⇒ 稿那条 `.find input[disabled] / select[disabled]` 的「不可用」光标规则
   **退场**（不再有 `disabled` 可命中）；底色 / 字色也由 `--surface-2` / `--ink-3` 提回 `--surface` / `--ink`。 */
.bulk { display: flex; align-items: center; gap: var(--s3); }
.btn-quiet {
  height: 36px; padding: 0 var(--s4); border: 1px solid var(--line-2); border-radius: var(--r2);
  color: var(--ink-3); font-size: var(--fs-xs); white-space: nowrap;
}
.btn-quiet[disabled] { cursor: not-allowed; }
.btn-primary {
  /* ⚠️ 见适配 ④：`<a>` 要补这两条，36px 高度才生效 */
  display: inline-flex; align-items: center;
  margin-left: auto; height: 36px; padding: 0 var(--s5); border-radius: var(--r2);
  background: var(--brand); color: var(--bg); font-weight: var(--fw-med); white-space: nowrap;
}
.btn-primary:hover { background: var(--brand-hover); }
.ck {
  width: 16px; height: 16px; border: 1px solid var(--line-2); border-radius: var(--r1);
  background: var(--surface); flex: 0 0 auto; padding: 0;
}
.ck:not([disabled]) { cursor: pointer; }
.ck[aria-checked="true"] { background: var(--brand); border-color: var(--brand); }
/* 占位态（稿里两处 `.ck` 都 `disabled`）*/
.ck[disabled] { cursor: not-allowed; }
.bulk .lbl { font-size: var(--fs-xs); color: var(--ink-3); white-space: nowrap; }

/* ── 商品表格（稿 `:210-251`；v4：卡片墙 → 表格）───────────────────────────────
   ⚠️ `.tbl-zone` 是**零溢出**的专门挂载点：`.tbl-wrap` 有 `overflow-x:auto`，而按 CSS 规范
   一轴非 `visible` 时另一轴的 `visible` 会计算成 `auto` ⇒ 滚动容器会把自己子元素的伪元素
   一起裁掉（稿注实测角标被切掉下半截字）。 */
.tbl-zone { margin-top: var(--s6); }
.tbl-wrap { overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.tbl th {
  text-align: left; font-size: var(--fs-xs); font-weight: var(--fw-reg); color: var(--ink-3);
  padding: var(--s3) var(--s3); border-bottom: 1px solid var(--line-2); white-space: nowrap;
}
.tbl td {
  padding: var(--s3) var(--s3); border-bottom: 1px solid var(--line);
  vertical-align: middle; white-space: nowrap;
}
.tbl tbody tr:hover { background: var(--surface-2); }
/* 适配 ⑦：行可点（既有真事件），给一个视觉提示 */
.tbl tbody tr { cursor: pointer; }
.tbl .c-ck { width: 36px; padding-right: 0; }
.tbl .c-img { width: 56px; }
/* ⚠️ `width:100%` 不是「占满」而是「**吃掉余量**」：其余列都是 nowrap 的自然宽，表格又是
   `width:100%`，多出来的空间若没人认领，浏览器会平摊给第一列（稿注实测「勾选」列被撑到 45px） */
.tbl .c-name { width: 100%; white-space: normal; min-width: 240px; }
.tbl .c-num { text-align: right; font-variant-numeric: tabular-nums; }
.th-img {
  width: 44px; aspect-ratio: 1/1; border-radius: var(--r1);
  background: var(--img); border: 1px solid var(--line); overflow: hidden;
}
.th-img img { width: 100%; height: 100%; object-fit: cover; display: block; }
.nm { line-height: 1.45; color: var(--ink); }
/* ⚠️ 类名是 `.pcode` 而**不是** `.code` —— 稿注写明弹窗的规格钮原名 `.code`，两个规则撞在一起
   会让表格里的 ProductCode 被套上 `height:34px;border:1px solid`、渲染成一个**输入框**。
   ⚠️ 本页弹窗的规格钮已改名 `.sku`（稿 `:301-305`），两处不再复用类名。 */
.nm .pcode { margin-top: 2px; font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-3); }
.px { color: var(--accent); font-variant-numeric: tabular-nums; }
.px .u { font-size: var(--fs-xs); }
.px .i { font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-med); }
.px .d { font-size: var(--fs-xs); }
/* 库存 / 销量列：无列可查 ⇒ 用 `—` 而不是画一个假数字 */
.na { color: var(--ink-3); }
.state { font-size: var(--fs-xs); color: var(--success-deep); }
.state .d {
  display: inline-block; width: 6px; height: 6px; margin-right: var(--s2);
  border-radius: 50%; background: var(--success); vertical-align: middle;
}
/* C3 块 3：状态三态 —— 上面那组 `--success*` 是「在架」的默认值，另两态在这两行各改一次
   （`--warning` = 待审、`--danger` = 驳回；都是 `tokens.css:138-140` 那族里已有的 token）。 */
.state.pending { color: var(--warning-deep); }
.state.pending .d { background: var(--warning); }
.state.rejected { color: var(--danger); }
.state.rejected .d { background: var(--danger); }
.op { display: flex; gap: var(--s4); }
.op button { font-size: var(--fs-xs); color: var(--brand-ink); font-weight: var(--fw-med); padding: 0; }
.op .del { color: var(--danger); }
.op .del[disabled] { cursor: not-allowed; }
.empty { padding: var(--s8) 0; text-align: center; color: var(--ink-3); }
.empty .big { font-family: var(--font-display); font-size: var(--fs-lg); color: var(--ink-2); margin-bottom: var(--s3); }

/* ── 分页（稿 `:253-258`）──────────────────────────────────────────────────── */
.pager { margin-top: var(--s6); display: flex; align-items: center; gap: var(--s2); }
.pager .sum { margin-right: auto; font-size: var(--fs-xs); color: var(--ink-3); }
.pager button {
  min-width: 32px; height: 32px; padding: 0 var(--s3); border: 1px solid var(--line-2);
  border-radius: var(--r1); font-size: var(--fs-xs); color: var(--ink-3); white-space: nowrap;
}
.pager button[disabled] { cursor: not-allowed; }
.pager .on { border-color: var(--brand); color: var(--brand-ink); font-weight: var(--fw-med); }

/* ── 按名称精确下架（稿 `:260-272`）—— 既有实现，挪到表格下方 ─────────────────── */
.sec { margin-top: var(--s8); padding-top: var(--s6); border-top: 1px solid var(--line-2); }
.sec-h { font-size: var(--fs-xs); color: var(--ink-3); }
.sec-note { margin-top: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }
.delbar { margin-top: var(--s3); display: flex; gap: var(--s3); flex-wrap: wrap; }
.delbar input {
  width: 200px; height: 36px; padding: 0 var(--s3); border: 1px solid var(--line-2);
  border-radius: var(--r2); background: var(--surface);
}
.delbar input:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
/* 危险动作走**功能色族**的 `--danger`（tokens.css:140 那一行，与 success/warning/info 同族等重），
   **不借 `--accent`**：tokens.css:123 逐字写明朱砂「只给价格、促销和未读提示」，本页价格已经占了它 */
.btn-danger {
  height: 36px; padding: 0 var(--s5); border: 1px solid var(--danger); border-radius: var(--r2);
  color: var(--danger); font-weight: var(--fw-med);
}
.btn-danger:hover { background: var(--danger-ground); }

/* ── 弹窗（稿 `:274-312`）──────────────────────────────────────────────────── */
.scrim {
  position: fixed; inset: 0; z-index: 200; display: flex; padding: var(--s5); overflow: auto;
  /* ⚠️ 不用 rgba()：README 第 2 条要求「颜色只有 token，一个 #hex / rgb() 都不许有」 */
  background: color-mix(in srgb, var(--ink) 34%, transparent);
}
/* ⚠️ 居中用子元素 `margin:auto`，**不用** `align-items:center`：1080 下弹窗高于视口时，
   center 会让内容**上下两头同时溢出** —— 顶部「商品详情」被切掉且滚不上去。 */
.dlg {
  margin: auto; width: 100%; max-width: 720px; background: var(--surface);
  border: 1px solid var(--line-2); border-radius: var(--r3);
}
.dlg-h {
  display: flex; align-items: baseline; gap: var(--s4); padding: var(--s5) var(--s7);
  border-bottom: 1px solid var(--line);
}
.dlg-h h2 { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med); letter-spacing: -.01em; }
.dlg-h .x { margin-left: auto; color: var(--ink-3); font-size: var(--fs-lg); line-height: 1; }
.dlg-body { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: var(--s7); padding: var(--s7); }
.pic {
  width: 200px; height: 200px; border-radius: var(--r2); background: var(--img);
  border: 1px solid var(--line); cursor: pointer; overflow: hidden;
}
.pic:hover { border-color: var(--line-2); }
.pic img { width: 100%; height: 100%; object-fit: cover; display: block; }
.pic-hint { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); text-align: center; }
.fl { margin-bottom: var(--s5); }
.fl:last-child { margin-bottom: 0; }
.fl label { display: block; font-size: var(--fs-xs); color: var(--ink-3); margin-bottom: var(--s2); }
.fl input {
  width: 100%; height: 38px; padding: 0 var(--s3); border: 1px solid var(--line-2);
  border-radius: var(--r2); background: var(--surface); color: var(--ink);
}
.fl input:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
/* ⚠️ 下面这个 disabled 是**既有实现就锁死的**（商品类型），上面 `.slot` 那条是**占位**（库存无列） */
.fl input[disabled] { background: var(--surface-2); color: var(--ink-3); cursor: not-allowed; }
.fl input.slot { background: var(--surface-2); color: var(--ink-3); cursor: not-allowed; }
.skus { display: flex; gap: var(--s3); flex-wrap: wrap; }
/* ── 商品券表单（A2/A3）────────────────────────────────────────────────────────
   ⚠️ 两块：`.cp-form` 是**双列网格**（券名横跨两列）；`.cp-form input` / `select` 要**自己重设**
      `width/height` —— 上面 `.fl input` 是 `width:100%; height:38px`（表单里那些大字输入框的尺寸），
      券这几个是小号辅助字段，不重设就会被撑成主字段那么大、把弹窗拉长。 */
.cp-form { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s2); }
.cp-form .cp-wide { grid-column: 1 / -1; }
.cp-form input, .cp-form select {
  width: 100%; height: 34px; padding: 0 var(--s2); border: 1px solid var(--line-2);
  border-radius: var(--r1); background: var(--surface); color: var(--ink); font-size: var(--fs-xs);
}
.cp-form input:focus, .cp-form select:focus {
  outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint);
}
.sku {
  height: 34px; padding: 0 var(--s5); border: 1px solid var(--line-2); border-radius: var(--r1);
  color: var(--ink-2); font-size: var(--fs-sm);
}
.sku:hover { border-color: var(--ink-3); }
.sku.on { border-color: var(--brand); color: var(--brand-ink); font-weight: var(--fw-med); }
.dlg-foot {
  display: flex; justify-content: flex-end; gap: var(--s3);
  padding: var(--s5) var(--s7); border-top: 1px solid var(--line);
}
.dlg-foot .save {
  height: 44px; padding: 0 var(--s7); border-radius: var(--r2);
  background: var(--brand); color: var(--bg); font-weight: var(--fw-med);
}
.dlg-foot .save:hover { background: var(--brand-hover); }
.dlg-foot .no {
  height: 44px; padding: 0 var(--s5); border: 1px solid var(--line-2); border-radius: var(--r2);
  color: var(--ink-2);
}

/* ── 原实现的行内 `style="display:none"` ⇒ 收敛成类（适配 ⑥）────────────────── */
.file-hidden { display: none; }

/* ── 窄档（稿 `:314-328`）—— `.body` / `.uc-menu` 两件不在本组件 ⇒ 只搬其余 ────── */
@media (max-width: 1080px) {
  .btn-primary { margin-left: 0; }
  .dlg-body { grid-template-columns: 1fr; gap: var(--s5); justify-items: start; }
  .fl input { max-width: 380px; }
  .pic { width: 160px; height: 160px; }
}
@media (max-width: 720px) {
  .delbar input { width: 100%; }
  .find input, .find select { width: 100%; }
  .find { width: 100%; }
}

/* ── C3 块 5〈商品类别〉三色标签（2026-10-12 用户裁定 ④ 问 2）────────────────────────────────
   国补（绿）/ 普通商品（白）/ 秒杀商品（红）—— 与 `ProductPage.vue` 的型号按钮**同一套类别语义**
   （同一批配色令），但**那里的形状不能照抄到这里**：
     · 那边是**按钮** ⇒ 要选中态、要给选中的加 `outline` 环（`tokens.css` 的用法规矩：态用 outline
       画，不靠侧边条）；这边是**标签**、没有选中这回事 ⇒ 只吃底色 + 字色，**不做态、不加环**。
     · 那边「两者都命中」只能选**一个**底色（裁量：取秒杀）；这边并排画**两枚**（见脚本那段 ⚠️）。
   ⚠️ 三枚的**内距统一** `1px var(--s2)`；「普通商品」多一圈 `--line` 边，是为了**白底上仍看得出
   这是一枚标签**（`--surface` 与卡片面同色，不描边就看不出来，会像只写了四个字）。
   ⚠️ 多型号商品**并排画多枚** ⇒ 用 `inline-block` + 右外边距（不要 flex，flex 会把它们拉成等宽）。 */
.kind { display: inline-block; margin-right: var(--s2); padding: 1px var(--s2); border-radius: var(--r1); font-size: var(--fs-xs); white-space: nowrap; }
.kind:last-child { margin-right: 0; }
.kind.normal { background: var(--surface); border: 1px solid var(--line); color: var(--ink-2); }
.kind.subsidy { background: var(--brand-tint); color: var(--brand-ink); }
.kind.seckill { background: var(--cin-100); color: var(--cin-700); }
</style>
