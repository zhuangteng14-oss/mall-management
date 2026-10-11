<template>
  <!--
    D 笔 commit 10 —— 本页承载稿 `docs/美化/pages/GoodsAdd.html`（661 行）的 `<main>` 那一支。
    宿主：`/merchant/user/add`（`router/index.js:117`），meta.layout = `user-page` ⇒ 壳内右栏。
    ⚠️ 稿的 `.nav-bar` / `.wrap` / `.body` / `.uc-menu` **不进本组件** —— 它们的落点是
       `AppShell.vue`（顶栏 + `.wrap` 居中）与 `UserCenter.vue`（左菜单那一支）
       ⇒ 本页只承载右栏（与 `UserInfoPage.vue` / `HistoricalOrders.vue` 同槽位）。
    ⚠️ 稿是**三视图**（`data-page="form"` / `"bad"` / `"ok"`），那是它自己的预览切页工具：
       · `form` ⇒ 落成本页模板（本笔主体）；
       · `bad`（校验态）⇒ 由**既有** `showTip` 驱动，逐字段落 `.tip` + `.inp.err` / `.codes.err`
         / `.picbox.err`，**不新增 script 状态**；
       · `ok`（提交成功）⇒ 那时实现是 `alert('商品上架成功！')`（C3 块 3 提审制后改为 `alert('已提交审核')`），稿画的是**行内成功面**。
         那需要新增一个 script 状态（`v-if="justSubmitted"`）才成立 ⇒ **超出 D 笔边界
         （`<template>` + `<style scoped>`），本笔不落**，已在 `slot-registry.md` §一 commit 10 登记。
  -->
  <main class="uc-main">

    <!-- 面包屑（稿 `:336-338`）。⚠️「用户中心」指向**已存在路由** `/merchant/user`
         （`router/index.js:98` 的 `path: 'user'` 就是 `:104-110` 那组 `subMenu` 的父路径）
         ⇒ 判据落**真 `<router-link>`**，不是死链接。 -->
    <nav class="crumb">
      <router-link to="/merchant/user">用户中心</router-link>
      <i class="sp">/</i>
      <span class="cur">上架商品</span>
    </nav>

    <!-- 页头（稿 `.head` `:185-188`）。
         ⚠️ 稿的 `:341` 副题是「声谷小店 · 新建一件商品，…」——「声谷小店」是它 mock 的店名
         （编的名 ⇒ 照判据不落）⇒ 副题只留**真陈述**：那五项就是 `submitGoods` 的必填判据
         （`GoodName` / `Price` / `selectedFile` / `GoodType` / `ProductCode`），逐字对得上。
         ⚠️ **类名用本页稿自己的 `.head` / `.head h1` / `.head .sub`**，不去套 `UserInfoPage.vue`
         的 `.page-h`：两份稿**自己就不同**（本页 `.head` 带一条发丝线 + `--fs-xs` 副题；
         UserCenter 稿 `.page-h` 无发丝线 + `--fs-sm` 副题）⇒「同壳一套类名」的口径**在此不适用**。 -->
    <div class="head">
      <h1>上架商品</h1>
      <div class="sub">新建一件商品，名称 / 价格 / 图片 / 类型 / 型号五项必填</div>
    </div>

    <form class="form" @submit.prevent>

      <!-- 校验汇总条（稿 `:519`，只在「校验态」出现）。稿写死「还有 5 项未完成」—— 那是它
           一次全空的示意值 ⇒ 本页**实时算**：数的是 `submitGoods` 那 5 个必填判据里的空项。
           ⚠️ 用模板内联表达式、**不新增 script 状态**（D 笔边界）⇒ 数组写了两遍，是有意的。
           ⚠️ 全填满时 `some(...)` 为假 ⇒ 整条不渲染（不会出现「还有 0 项未完成」这个假陈述）。 -->
      <div
        v-if="showTip && [GoodName, Price, selectedFile, GoodType, ProductCode].some(v => !v)"
        class="note-bad"
      >
        还有 {{ [GoodName, Price, selectedFile, GoodType, ProductCode].filter(v => !v).length }} 项未完成，请补齐后再提交。
      </div>

      <!-- ═══ 01 基本信息（稿 `:347-375`）══════════════════════════════════════ -->
      <section class="sec">
        <div class="sec-h"><span class="idx">01</span><h2>基本信息</h2></div>

        <div class="grid2">
          <div class="f">
            <label for="g-name">商品名称</label>
            <input
              id="g-name"
              v-model="GoodName"
              class="inp"
              :class="{ err: showTip && !GoodName }"
              type="text"
              placeholder="请输入商品名称"
            >
            <div v-if="showTip && !GoodName" class="tip">请输入商品名称</div>
          </div>

          <div class="f">
            <label for="g-code">商品型号（ProductCode）</label>
            <input
              id="g-code"
              v-model="ProductCode"
              class="inp"
              :class="{ err: showTip && !ProductCode }"
              type="text"
              placeholder="请输入商品型号"
            >
            <!-- 稿 `:532-535` 的规矩：出错时**撤掉**那条灰字解释 —— 左列只有红字，
                 本列若红字下再挂灰字，两列高度不齐且灰字会被读成「错误的一部分」。 -->
            <div v-if="showTip && !ProductCode" class="tip">请输入商品型号</div>
            <div v-else class="hint">同一商品的不同规格靠它区分。</div>
          </div>
        </div>

        <div class="f mt">
          <span class="lb">商品类型</span>
          <!-- ⚠️ 稿 `:362-367` 画的是 `<button class="code" aria-checked>` 单选组；
               本页保留**既有真控件** `<input type="radio" v-model="GoodType">`（机制逐字未动），
               只取其视觉（发丝线胶囊 + 选中态染 `--brand`）。`aria-checked` 的语义由
               **原生 radio** 承担 ⇒ 比 `<button>` 更准，不是降级。 -->
          <div class="codes" :class="{ err: showTip && !GoodType }">
            <label
              v-for="item in typeList"
              :key="item.Id"
              class="code"
              :class="{ on: GoodType === item.GoodType }"
            >
              <input v-model="GoodType" type="radio" name="goodtype" :value="item.GoodType">
              <span>{{ item.GoodType }}</span>
            </label>
            <label
              v-if="tempNewType"
              class="code"
              :class="{ on: GoodType === tempNewType }"
            >
              <input v-model="GoodType" type="radio" name="goodtype" :value="tempNewType">
              <span>{{ tempNewType }}（新类型）</span>
            </label>
          </div>
          <!-- 稿 `:546` 的规矩：提示必须**紧贴它管的那组控件**，不掉到发丝线下面。 -->
          <div v-if="showTip && !GoodType" class="tip">请选择商品类型</div>

          <!-- 临时新增类型：**既有机制**（`addTempType`），不是占位（稿 `:368-373`）。
               稿注要它落在**次行**、用发丝线与标签行分开（Q3 第一轮修的就是这件事）。 -->
          <div class="addtype">
            <input v-model="newType" type="text" placeholder="输入新类型名">
            <button type="button" class="btn-quiet" @click="addTempType">临时添加类型</button>
            <span class="hint">本次上架先用，提交成功后写入类型库</span>
          </div>
        </div>
      </section>

      <!-- ═══ 02 价格库存（稿 `:378-403`）══════════════════════════════════════ -->
      <section class="sec">
        <div class="sec-h"><span class="idx">02</span><h2>价格库存</h2></div>

        <div class="grid2">
          <div class="f">
            <label for="g-price">商品价格</label>
            <span class="money" :class="{ err: showTip && !Price }">
              <span class="u">¥</span>
              <input id="g-price" v-model="Price" type="number" placeholder="0.00">
            </span>
            <div v-if="showTip && !Price" class="tip">请输入商品价格</div>
          </div>

          <!-- 预留位 ①（稿 `:386-392`）—— 块级 `<div class="f">` ⇒ 只挂 `data-slot` + `title`。
               ⚠️ 内部那个输入框是**稿外死控件**（无 `data-slot`）⇒ 落 `disabled` + `title`，见 §三。 -->
          <div
            class="f"
            data-slot="预留 · 划线原价 / 折扣（动现有代码 5 处 · 超档）"
            title="待 C3〈商品〉——goods 表只有 Price 一列，无原价 / 折扣列：mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsAdd.vue = 5 处，**超档**，因清单点名而画出（见 README 挂账表）"
          >
            <span class="lb">划线原价 / 折扣</span>
            <span class="money" data-off>
              <span class="u">¥</span>
              <input
                type="number"
                placeholder="0.00"
                disabled
                title="待 C3〈商品〉——goods 无原价列，5 处改动（超档）"
              >
            </span>
            <div class="hint">填了原价才会在商品详情页显示划线价与折扣角标。</div>
          </div>
        </div>

        <!-- 预留位 ②（稿 `:395-402`）—— 块级。输入框同款死控件。 -->
        <div
          class="f mt"
          data-slot="预留 · 库存（动现有代码 5 处 · 超档）"
          title="待 C3〈商品〉——goods 表无库存列（GoodsDao.java:76 的 INSERT 列序实测 7 列）：mall_management_sql.sql + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsAdd.vue = 5 处，**超档**，因清单点名而画出（见 README 挂账表）"
        >
          <label for="g-stock">库存</label>
          <input
            id="g-stock"
            class="inp narrow"
            type="number"
            placeholder="—"
            disabled
            title="待 C3〈商品〉——goods 无库存列，5 处改动（超档）"
          >
          <div class="hint">库存要能随下单扣减才算真跑起来，所以后端要一起动。</div>
        </div>
      </section>

      <!-- ═══ 03 商品图（稿 `:406-424`）════════════════════════════════════════ -->
      <section class="sec">
        <div class="sec-h">
          <span class="idx">03</span><h2>商品图</h2>
          <span class="note">主图随上架请求一起上传（真功能）</span>
        </div>

        <div class="pics">
          <!-- 主图 = **既有真功能**（`chooseImage` → `previewImg`，原实现逐字未动）。
               ⚠️ 稿 `:411` 的 `.picbox.main` 里是一行文字；本页**保留既有真预览**
               `<img v-if="previewImg">`（原实现就有），无图时才显那行文字。 -->
          <button
            type="button"
            class="picbox main"
            :class="{ err: showTip && !selectedFile }"
            @click="chooseImage"
            title="点击选择主图"
          >
            <img v-if="previewImg" :src="previewImg" class="picbox-img" alt="商品主图预览">
            <span v-else>点击选择主图</span>
          </button>

          <!-- 预留位 ③（稿 `:413-421`）—— 块级 `<div class="pics">` ⇒ 只挂 `data-slot` + `title`。
               内两只 `.picbox` 是**稿外死控件**（稿里是 `<span>`、无 `disabled`；
               本笔照 §三 口径**不改成真控件**，改为 `span` + `off` 态样式 + `title`）⇒ 见 §三。 -->
          <div
            class="pics"
            data-slot="预留 · 多图（图集）（动现有代码 ≥4 处 · 超档）"
            title="待 C3〈商品〉——goods 表只有 GoodImg 一列（单图）：加一张图集子表或改列结构 + GoodsDao.java + GoodsService.java + GoodsController.java + GoodsAdd.vue = ≥4 处，**超档**，因清单点名而画出"
          >
            <span class="picbox off" title="待 C3〈商品〉——goods 只有 GoodImg 一列（≥4 处，超档）">
              <span class="tagline">＋ 第 2 张<br>（待 C3）</span>
            </span>
            <span class="picbox off" title="待 C3〈商品〉——同上">
              <span class="tagline">＋ 第 3 张<br>（待 C3）</span>
            </span>
          </div>
        </div>

        <div v-if="showTip && !selectedFile" class="tip">请选择商品图片</div>
        <div v-else class="pic-hint">建议 1:1 方图，长边 ≥ 800px。主图为空不能提交。</div>

        <input
          ref="fileInput"
          type="file"
          accept="image/*"
          class="file-hidden"
          @change="handleImageChange"
        >
      </section>

      <!-- ═══ 04 商品详情（稿 `:427-436`）══════════════════════════════════════ -->
      <section class="sec">
        <div class="sec-h"><span class="idx">04</span><h2>商品详情</h2></div>

        <!-- ⚠️ **2026-10-10〈模板表通用化〉：预留位 ④ 就地转真功能** ——
             这一格此前是 `disabled` 的**占位** textarea（原 `data-slot="预留 · 详情富文本…"`），
             依据是「goods 表无详情列」。本笔给 goods 加了 `Detail` / `Spec` / `AfterSale` 三列
             （快照文本）⇒ **那个前提已不成立**，占位若留着就是一句假陈述 ⇒ 原地改成真输入框
             （不是「另开一处真控件、把假控件也留着」——那会变成两个详情框）。
             ⚠️ 语义是**快照不是引用**：「选模板」把模板正文**填进这个框**，商家**可改**，
             提交时落库的是**改完的最终文本**，**不是模板 Id** ⇒ 事后改模板**不影响**已上架商品。
             ⚠️ 非必填（后端 `Detail` 是 `required = false`）—— `submitGoods` 的 5 项必填判据不动。 -->
        <div class="f">
          <div class="f-head">
            <label for="g-desc">详情描述</label>
            <button type="button" class="btn-quiet" @click="showDetailTpl = true">选模板</button>
          </div>
          <textarea
            id="g-desc"
            v-model="Detail"
            class="inp"
            placeholder="可直接填写，或点右上「选模板」从模板库取一段再改"
          ></textarea>
          <div class="hint">展示在商品详情页的「商品详情」Tab。</div>
        </div>
      </section>

      <!-- ═══ 05 规格 SKU（稿 `:439-463`）══════════════════════════════════════ -->
      <section class="sec">
        <div class="sec-h">
          <span class="idx">05</span><h2>规格 SKU</h2>
          <span class="note">单规格 = 上面的「商品型号」；多规格 = 下面的矩阵，两条都是真功能</span>
        </div>

        <div class="f">
          <span class="lb">单规格（现有实现）</span>
          <!-- ⚠️ 稿 `:445-447` 画的是**三枚示意码**（`STD-GRAY` / `ANC-BLACK` / `PRO-WHITE`，
               那是它编的值）⇒ 照判据「编的名 ⇒ 落 `—`」，本页落**真值单枚**：
               现有实现就是 `ProductCode` 一个字段承载规格（`submitGoods` 的必填项之一）。 -->
          <div class="codes">
            <span class="code on static">{{ ProductCode || '—' }}</span>
          </div>
          <div class="hint">这个码就是「商品型号」的取值 —— 现有实现用 ProductCode 一个字段承载规格。</div>
        </div>

        <!-- 规格说明（**2026-10-10〈模板表通用化〉新列 `goods.Spec`**）—— 落 ProductPage「规格参数」Tab。
             ⚠️ 约定是**行内 `键=值`、一行一条**（列类型 `mediumtext` 纯文本，不建子表）——
             与上面「单规格 / 多规格矩阵」是两件事：那条说的是**型号 / 组合**，这条是**参数清单**。
             ⚠️ 非必填。 -->
        <div class="f mt">
          <div class="f-head">
            <span class="lb">规格说明</span>
            <button type="button" class="btn-quiet" @click="showSpecTpl = true">选模板</button>
          </div>
          <textarea
            id="g-spec"
            v-model="Spec"
            class="inp"
            placeholder="一行一条，写法：键=值（例：品牌=声谷 SG）"
          ></textarea>
          <div class="hint">展示在商品详情页的「规格参数」Tab，按 `键=值` 逐行拆成左右两列。</div>
        </div>

        <!-- ⚠️ 2026-10-11（用户裁决）：预留位 ⑤「多规格矩阵」**就地转真功能** ——
             依据是「无 SKU 表 / 无组合价 / 库存列」这个前提**已不成立**（本轮新建 `goods_sku` 表，
             见 `mall_management_sql.sql` 迁移块 ④）⇒ 占位若留着就是一句假陈述。
             矩阵结构**来自模板**：「选规格模板」按结构化模板（`template.Kind='structured'`）各轴的取值
             做**笛卡尔积**自动生成行；每格的价格 / 库存 / 编码由商家填（正是用户「省去手动一行行加」的口径）。
             ⚠️ 提交时矩阵以 JSON 串随提审走（`SkuSpec`），审核通过后才落 `goods_sku`（提审制）。 -->
        <div class="f mt">
          <div class="f-head">
            <span class="lb">多规格矩阵（颜色 × 尺码，逐行定价）</span>
            <button type="button" class="btn-quiet" @click="showSkuTpl = true">选规格模板</button>
          </div>
          <div class="matrix">
            <div class="hd"><span>规格</span><span>价格</span><span>库存</span><span>编码</span><span></span></div>
            <div class="rw" v-for="(r, i) in skuRows" :key="i">
              <span class="nm"><input v-model.trim="r.spec" class="minp" type="text" placeholder="如 深空灰 / M" /></span>
              <span><input v-model="r.price" class="minp" type="text" placeholder="0.00" /></span>
              <span><input v-model="r.stock" class="minp" type="text" placeholder="0" /></span>
              <span><input v-model.trim="r.code" class="minp" type="text" placeholder="SKU 编码" /></span>
              <span class="op"><button type="button" class="rm" title="删除这一行" @click="removeSkuRow(i)">×</button></span>
            </div>
            <div class="rw empty" v-if="skuRows.length === 0">
              <span>还没有规格行 —— 点右上「选规格模板」自动生成，或点下面「加一行」自己填。</span>
            </div>
          </div>
          <div class="f-head mt-s">
            <button type="button" class="btn-quiet" @click="addSkuRow">加一行</button>
          </div>
          <div class="hint">每行有自己的编码 —— 买家在商品详情页选中的那行编码，就是加入购物车时上报的商品编码。</div>
        </div>
      </section>

      <!-- ═══ 06 服务承诺（稿 `:466-479`）══════════════════════════════════════ -->
      <section class="sec">
        <div class="sec-h">
          <span class="idx">06</span><h2>服务承诺</h2>
          <span class="note">文案与商品详情页承诺条逐字一致</span>
        </div>

        <!-- 售后说明（**2026-10-10〈模板表通用化〉新列 `goods.AfterSale`**）—— 落 ProductPage
             「售后保障」Tab。⚠️ 与下面「预留位 ⑥ 服务承诺勾选」**不是一回事、两条都留**：
             这条是**自由文本**（`goods.AfterSale` 一列 `mediumtext`），那条是**四项布尔勾选**
             （正品保障 / 7 天无理由 / 极速退款 / 运费险）—— goods 表**仍然没有**承载后者的列，
             所以那个占位它的依据（「goods 表无承诺列，选了什么存不下来」）**依旧成立、不是假陈述**
             ⇒ 不删。填了这条之后，详情页那一 Tab 显示这段文字（留空则显示平台默认政策）。 -->
        <div class="f">
          <div class="f-head">
            <label for="g-after">售后说明</label>
            <button type="button" class="btn-quiet" @click="showAfterSaleTpl = true">选模板</button>
          </div>
          <textarea
            id="g-after"
            v-model="AfterSale"
            class="inp"
            placeholder="可直接填写，或点右上「选模板」从模板库取一段再改"
          ></textarea>
          <div class="hint">展示在商品详情页的「售后保障」Tab。</div>
        </div>

        <!-- ⚠️ 2026-10-11（用户裁决）：预留位 ⑥「服务承诺勾选」**就地转真功能** ——
             依据是「goods 表无承诺列，选了什么存不下来」这个前提**已不成立**（本轮给 goods 加了
             `PromiseGenuine` / `PromiseReturn7` / `PromiseFastRefund` / `PromiseFreightIns` 四列，
             默认 1；见 `mall_management_sql.sql` 迁移块 ①②）。
             ⚠️ 四条文案与 `ProductPage.vue` 的承诺条**必须逐字一致**（稿的跨页共性组件登记）。
             ⚠️ 默认全勾：与改前「详情页对每件商品都显示四条」一致（保持现状、向后兼容）。 -->
        <div class="f">
          <div class="svcs">
            <span class="svc"><button class="ck" type="button" :aria-checked="String(PromiseGenuine)" title="正品保障" @click="PromiseGenuine = !PromiseGenuine"></button>正品保障</span>
            <span class="svc"><button class="ck" type="button" :aria-checked="String(PromiseReturn7)" title="7 天无理由" @click="PromiseReturn7 = !PromiseReturn7"></button>7 天无理由</span>
            <span class="svc"><button class="ck" type="button" :aria-checked="String(PromiseFastRefund)" title="极速退款" @click="PromiseFastRefund = !PromiseFastRefund"></button>极速退款</span>
            <span class="svc"><button class="ck" type="button" :aria-checked="String(PromiseFreightIns)" title="运费险" @click="PromiseFreightIns = !PromiseFreightIns"></button>运费险</span>
          </div>
          <div class="hint">勾选后会显示在商品详情页的承诺条上。</div>
        </div>
      </section>

      <!-- ── 底部动作条（稿 `:482-489`）───────────────────────────────────── -->
      <div class="acts">
        <!-- 预留位 ⑦（稿 `:483-485`）—— 这里**本身就是控件** ⇒ 照 §三/口径 2 落 `disabled` + `data-slot` + `title`。 -->
        <button
          type="button"
          class="btn-draft"
          disabled
          data-slot="预留 · 保存草稿（动现有代码 ≥4 处 · 超档）"
          title="待 C3〈商品〉——无草稿态：goods 表无状态列（下架 = 删行），草稿要先能存而不出现在「查看商品」里：mall_management_sql.sql + GoodsDao.java + GoodsController.java + GoodsUp.vue（列表要过滤掉草稿）= ≥4 处，**超档**，因清单点名而画出"
        >保存草稿</button>

        <button type="button" class="btn-main" @click="submitGoods"><IconGlyph name="ok" />上架商品</button>
        <div class="hint">提交成功后会清空表单，可继续上架下一件。</div>
      </div>
    </form>

    <!-- ═══ 「选模板」弹窗 ×3（2026-10-10〈模板表通用化〉）═════════════════════════
         ⚠️ 触发按钮**留在各自字段里**（`.btn-quiet`，本页既有类），组件只出弹窗 —— 见
         `components/TemplatePicker.vue` 块首「为什么组件不含按钮」。
         ⚠️ 三个 `v-if` 各自独立 ⇒ 同时只可能开一个（不会叠两个遮罩）。 -->
    <TemplatePicker
      v-if="showDetailTpl"
      type="goods.detail"
      title="选择详情模板"
      empty-text="暂无详情模板（管理端建一条即可）"
      @pick="onPickDetailTpl"
      @close="showDetailTpl = false"
    />
    <!-- ⚠️ 2026-10-11：`goods.spec` 这一个 type 下**两种形态**并存 ⇒ 两个入口各取各的：
         「规格说明」文本域取**文本模板**（`kind="text"`），多规格矩阵取**结构化模板**（`kind="structured"`）。 -->
    <TemplatePicker
      v-if="showSpecTpl"
      type="goods.spec"
      kind="text"
      title="选择规格模板（文本）"
      empty-text="暂无文本规格模板（管理端建一条即可）"
      @pick="onPickSpecTpl"
      @close="showSpecTpl = false"
    />
    <!-- 多规格矩阵的模板：选完 → `onPickSkuTpl` 把各轴取值做笛卡尔积填成矩阵行。 -->
    <TemplatePicker
      v-if="showSkuTpl"
      type="goods.spec"
      kind="structured"
      title="选择规格模板（生成矩阵）"
      empty-text="暂无结构化规格模板（管理端建一条，形态选「结构化」即可）"
      @pick="onPickSkuTpl"
      @close="showSkuTpl = false"
    />
    <TemplatePicker
      v-if="showAfterSaleTpl"
      type="goods.afterSale"
      title="选择售后模板"
      empty-text="暂无售后模板（管理端建一条即可）"
      @pick="onPickAfterSaleTpl"
      @close="showAfterSaleTpl = false"
    />

  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId } from '../api/auth'; import IconGlyph from '../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）—— 合并到既有行 = 不新增行号漂移（`api-contract.md` 按 `GoodsAdd.vue:135/166/217` 钉住本文件）
import TemplatePicker from '../components/TemplatePicker.vue'

const fileInput = ref(null)
const previewImg = ref('')
const selectedFile = ref(null)

const GoodName = ref('')
const Price = ref('')
const GoodType = ref('')
const ProductCode = ref('')
const GoodTypeNew = ref(0)

// ── 2026-10-10〈模板表通用化〉：goods 的三段**快照文本**（`Detail` / `Spec` / `AfterSale`）──
// ⚠️ 语义是**快照不是引用**：模板只「提供思路」，选完填进框里、商家可改，提交落库的是**最终文本**。
// ⇒ 事后改模板，**已上架的商品不变**（这与块 3 首版「商品存模板 Id」是**相反**的语义）。
const Detail = ref('')
const Spec = ref('')
const AfterSale = ref('')

// 三个「选模板」弹窗的开关（各自独立 ⇒ 同时只可能开一个）
const showDetailTpl = ref(false)
const showSpecTpl = ref(false)
const showAfterSaleTpl = ref(false)

// ── 2026-10-11（用户裁决）：服务承诺四勾 + 多规格矩阵 ──────────────────────────
// 承诺位：默认**全真** —— 与改前「详情页对每件商品都无条件显示四条」一致（硬线：不动旧行为）。
// 提交时落 `goods.Promise*` 四列（提审制下先落 `goods_audit`，通过时再写 goods）。
const PromiseGenuine = ref(true)
const PromiseReturn7 = ref(true)
const PromiseFastRefund = ref(true)
const PromiseFreightIns = ref(true)

// 多规格矩阵：一行 = 一个规格组合。行列结构来自结构化模板，价格/库存/编码由商家逐格填。
// ⚠️ 空数组 = 不用多规格 ⇒ 提交的 `SkuSpec` 为空 ⇒ 该商品就是「单规格」（与今日行为一致）。
const skuRows = ref([])
const showSkuTpl = ref(false)

const typeList = ref([])
const newType = ref('')
const tempNewType = ref('')
const showTip = ref(false)

onMounted(() => {
  loadGoodsType()
})

const loadGoodsType = async () => {
  try {
    const res = await http.get(endpoints.goods.goodstype)
    const data = await res.json()
    if (data.success) {
      typeList.value = data.list
    }
  } catch (err) {
    handleApiError(err, '加载类型失败')
  }
}

const addTempType = () => {
  if (!newType.value) return
  tempNewType.value = newType.value
  GoodType.value = newType.value
  GoodTypeNew.value = 1
  newType.value = ''
}

const chooseImage = () => {
  fileInput.value.click()
}

// ── 「选模板」三处回填（2026-10-10）────────────────────────────────────────────
// ⚠️ 三个都只取 `row.Body`：`goods.*` 类模板的 `Title` 留空（正文就是全部）——
//    通知类模板才两段都有意义，那是 `NotifyPage` 的面，不是这里。
// ⚠️ **直接覆写**输入框内容（不做「追加」也不做「先问」）：模板是「提供思路」，
//    商家选完自己再改 —— 追加会让「连选两次」变成把两条模板粘在一起。
const onPickDetailTpl = (row) => { Detail.value = row.Body || ''; showDetailTpl.value = false }
const onPickSpecTpl = (row) => { Spec.value = row.Body || ''; showSpecTpl.value = false }
const onPickAfterSaleTpl = (row) => { AfterSale.value = row.Body || ''; showAfterSaleTpl.value = false }

// ── 多规格矩阵（2026-10-11）────────────────────────────────────────────────────
/** 加一行空规格（手填路径）。 */
const addSkuRow = () => { skuRows.value = [...skuRows.value, { spec: '', price: '', stock: '', code: '' }] }
/** 删第 i 行。 */
const removeSkuRow = (i) => { skuRows.value = skuRows.value.filter((_, idx) => idx !== i) }

/** 组合数上限 —— 防「5 轴 × 每轴 8 值」这种把页面撑爆的手误（3125 行）。 */
const SKU_MAX_COMBOS = 200

/**
 * 选**结构化**规格模板 → 按各轴取值做**笛卡尔积** → 生成矩阵行。
 *
 * <p>模板正文约定：一行一条轴，`轴名=值1,值2,…`（全/半角逗号都认）。
 * 例：`颜色=深空灰,曜石黑,珍珠白` + `尺码=S,M,L,XL` ⇒ 3 × 4 = 12 行，组合标签 `深空灰 / S` …
 *
 * <p>⚠️ 覆写 `skuRows`（不是追加）：模板是「行列结构的起点」，连选两次不该把两套结构粘起来
 * （同三段快照文本「直接覆写」的口径）。商家填过的价格 / 库存 / 编码会随之丢掉 —— 这是**有意的**，
 * 因为换了结构，旧格子的归属已经不明。
 */
const onPickSkuTpl = (row) => {
  showSkuTpl.value = false
  const axes = String(row.Body || '')
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s !== '')
    .map((line) => {
      const i = line.indexOf('=')
      if (i < 0) return null
      const name = line.slice(0, i).trim()
      const vals = line.slice(i + 1).split(/[,，]/).map((v) => v.trim()).filter((v) => v !== '')
      return name && vals.length ? { name, vals } : null
    })
    .filter(Boolean)
  if (!axes.length) {
    alert('这条模板没有可解析的规格轴。\n每行应写成：轴名=值1,值2,…（例：颜色=深空灰,曜石黑）')
    return
  }
  let combos = ['']
  for (const ax of axes) {
    const next = []
    for (const c of combos) for (const v of ax.vals) next.push(c ? c + ' / ' + v : v)
    combos = next
    if (combos.length > SKU_MAX_COMBOS) {
      alert(`规格组合太多（超过 ${SKU_MAX_COMBOS} 行），请减少轴上取值，或手填矩阵。`)
      return
    }
  }
  skuRows.value = combos.map((spec) => ({ spec, price: '', stock: '', code: '' }))
}

const handleImageChange = (e) => {
  const file = e.target.files[0]
  if (!file) return
  selectedFile.value = file
  previewImg.value = URL.createObjectURL(file)
}

const saveNewTypeToDB = async (typeName) => {
  try {
    await http.post(endpoints.goods.goodstype, new URLSearchParams({
        GoodType: typeName,
        GoodTypeNew: 1
      }))
  } catch (e) {
    handleApiError(e, '保存类型失败')
  }
}

// ✅ 最终修复版：完全适配你的后端接口
const submitGoods = async () => {
  showTip.value = true

  if (!GoodName.value || !Price.value || !selectedFile.value || !GoodType.value || !ProductCode.value) {
    return
  }

  const needSaveNewType = tempNewType.value && GoodType.value === tempNewType.value
  const merchantid = getMerchantId()

  // ==============================================
  // 🔥 核心修复：从数据库取真实 GoodTypeNew
  // ==============================================
  let realGoodTypeNew = 0

  // 如果是本次临时新增的类型 → 1
  if (needSaveNewType) {
    realGoodTypeNew = 1
  }
  // 如果是数据库里已有的类型 → 用数据库里的值
  else {
    const type = typeList.value.find(t => t.GoodType === GoodType.value)
    if (type) {
      realGoodTypeNew = type.GoodTypeNew
    }
  }

  const formData = new FormData()
  formData.append('MerchantId', merchantid)
  formData.append('GoodName', GoodName.value)
  formData.append('Price', Price.value)
  formData.append('file', selectedFile.value)
  formData.append('GoodType', GoodType.value)
  formData.append('ProductCode', ProductCode.value)
  formData.append('GoodTypeNew', realGoodTypeNew) // 用真实值！
  // C3 块 3：附类目 Id（换代后类目名不全库唯一，按名回查会歧义；同名时与后端 selectTypeIdByName 同取首条）
  const pickedType = typeList.value.find(t => t.GoodType === GoodType.value)
  if (pickedType && pickedType.Id != null) formData.append('TypeId', String(pickedType.Id))
  // 2026-10-10〈模板表通用化〉：三段快照文本随提审一起走（后端三参 `required = false`）。
  // ⚠️ **必须随提审携带**：提审只写 `goods_audit`，审核通过时后端用提审行**重建** `goods` 行
  //    （`writeGoodsFromAudit`）—— 不带这三段，通过后新行里它们会是空的。
  formData.append('Detail', Detail.value)
  formData.append('Spec', Spec.value)
  formData.append('AfterSale', AfterSale.value)
  // 2026-10-11：多规格矩阵快照（只收「有组合名」的行 —— 用户点了「加一行」却没填的空行不算）
  // + 四个服务承诺位。⚠️ 与三段快照文本同理：这些必须**随提审携带**，否则审核通过后 goods 新行 / SKU 为空。
  const skuPayload = skuRows.value
    .filter((r) => String(r.spec || '').trim() !== '')
    .map((r) => ({
      spec: String(r.spec).trim(),
      price: Number(r.price) || 0,
      stock: Number(r.stock) || 0,
      code: String(r.code || '').trim()
    }))
  if (skuPayload.length) formData.append('SkuSpec', JSON.stringify(skuPayload))
  formData.append('PromiseGenuine', String(PromiseGenuine.value))
  formData.append('PromiseReturn7', String(PromiseReturn7.value))
  formData.append('PromiseFastRefund', String(PromiseFastRefund.value))
  formData.append('PromiseFreightIns', String(PromiseFreightIns.value))

  try {
    const res = await http.post(endpoints.goods.goodsAdd, formData)
    const data = await res.json()

    if (data.success) {
      if (needSaveNewType) {
        await saveNewTypeToDB(tempNewType.value)
      }

      alert('已提交审核') // C3 块 3 提审制：goodsAdd 只落待审单、不写 goods 行 ⇒ 原文案「商品上架成功！」失真；本句与后端 msg 逐字同
      GoodName.value = ''
      Price.value = ''
      GoodType.value = ''
      ProductCode.value = ''
      previewImg.value = ''
      selectedFile.value = null
      tempNewType.value = ''
      showTip.value = false
      // 三段快照文本也清空（同其余字段：提交成功即开下一件）
      Detail.value = ''
      Spec.value = ''
      AfterSale.value = ''
      // 多规格矩阵清空；承诺位回到默认「全勾」（新增商品默认全承诺，同 ② 的默认值）。
      skuRows.value = []
      PromiseGenuine.value = true
      PromiseReturn7.value = true
      PromiseFastRefund.value = true
      PromiseFreightIns.value = true

      loadGoodsType()
    } else {
      alert('失败：' + data.msg)
    }
  } catch (err) {
    alert('上架请求失败')
  }
}
</script>

<style scoped>
/* ══════════════════════════════════════════════════════════════════════════════
   取自 `docs/美化/pages/GoodsAdd.html`：
     `:181-183`（`.crumb*`）`:185-188`（`.head*`）`:191`（`.form`）`:192-193`（`.sec`）
     `:194-198`（`.sec-h*`）`:199`（`.grid2`）`:201-204`（`.f*`）`:205-208`（`.inp*`）
     `:209-210`（`.inp[disabled]`）`:213`（`textarea.inp`）`:214`（`.tip`）`:215-216`（`.note-bad`）
     `:219-227`（`.money*`）`:230-238`（`.pics` / `.picbox*` / `.pic-hint`）
     `:241-254`（`.codes*` / `.code*` / `.addtype*`）`:257-266`（`.svcs*` / `.ck*` / `.btn-quiet*`）
     `:269-274`（`.matrix*`）`:277-286`（`.acts*` / `.btn-main` / `.btn-draft*`）
     `:296-308`（两条 `@media`）

   本次「从稿到 Vue」的**适配**，逐条登记：
     ① 稿的全局重设 `body{}` / `a{}` / `button,input,select{}` / `button{}`（稿 `:157-161`）里的
        后三件**必须搬进 scoped 且限定本页** —— 稿的 `.code` / `.ck` / `.btn-quiet` / `.btn-draft`
        都是裸 `<button>`，不重设就会带上 UA 的 `background: rgb(240,240,240)` /
        `border: 2px outset` / `font-family: Arial`（commit 7 / 8 都实踩过）。
        ⚠️ `body{font-family|font-size|color}` + `background` **落到本页根 `.uc-main`**，
        `background` 不搬（壳 `.app-shell` 已铺 `var(--bg)`，再铺一次会把右栏画成另一块底）。
     ② 稿的 `.wrap` / `.body` / `.uc-menu` / `.nav-bar` **不进本组件**（落点 `AppShell.vue` /
        `UserCenter.vue`）⇒ 未搬。
     ③ 稿里三处行内 `style="margin-top:var(--s5)"` ⇒ 收敛成类 `.mt`（`:199` 那个 `.grid2`
        之后的三个块用它）；`.inp` 的行内 `style="max-width:240px"` ⇒ 收敛成 `.narrow`。
     ④ 稿 `.picbox` 的 `cursor:not-allowed;border-style:dashed;opacity:.75`（`:415` / `:418`
        的行内样式）⇒ 收敛成 `.picbox.off`（多图那两只）。
     ⑤ 稿 `.code[aria-checked="true"]` ⇒ 本页是**原生 radio**（见模板注释）⇒ 改绑类 `.code.on`
        （`:class="{ on: GoodType === … }"`）。`.code.static` 是 05 那枚**只读**展示码
        （它不是控件 ⇒ 不显手型）。
     ⑥ `.picbox-img` 是**原实现就有的预览图**（`previewImg`）新增的适配类（稿无此件，
        稿的主图框里只有一行文字）—— `object-fit:cover` 撑满稿的 120×120。
     ⑦ `.file-hidden` 取代原实现的行内 `style="display: none"`。
     ⑧ ⚠️ **稿 `:root{--d2: calc(var(--fs-2xl) * 1.00)}`（稿 `:128`）必须跟着搬** —— 稿 `:186`
        的 `.head h1` 写的是 `font-size:var(--d2)`；`tokens.css` 里**没有** `--d2`（它只定义
        `--fs-*`）⇒ scoped 块若不自己定义，`var(--d2)` 在**计算值阶段**判为无效、整条声明被丢弃，
        标题**静默回落到继承来的 `--fs-sm`（15px）**，与正文同大。落法同 `UserInfoPage.vue:393`
        （写在本页根 `.uc-main` 上，`calc(x * 1.00)` ≡ `x` ⇒ 写 `var(--fs-2xl)`）。
        ⚠️ 这只在**算出来的** 36px 上看得出来，肉眼扫模板扫不出 ⇒ `shot10.mjs` 已加 `h1Fs` 断言。
   ⚠️ `box-sizing` **不在此重复** —— `tokens.css` 的 `*` 重设已是全局。
   ══════════════════════════════════════════════════════════════════════════════ */

/* ── 稿 `:160-161` 的重设，限定本页 ─────────────────────────────────────────── */
button, input, select { font: inherit; color: inherit; }
button { background: none; border: 0; cursor: pointer; }

/* ── 本页根 ────────────────────────────────────────────────────────────────── */
.uc-main {
  --d2: var(--fs-2xl);          /* 稿 `:128` 的 `:root` 落点，见上面适配 ⑧ */
  flex: 1;
  min-width: 0;
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 面包屑（稿 `:181-183`）—— 分隔符与文字**同色**，不用浅灰 ───────────────── */
.crumb { display: flex; align-items: center; font-size: var(--fs-xs); color: var(--ink-3); margin-bottom: var(--s5); }
.crumb .sp { font-style: normal; padding: 0 var(--s2); }
.crumb .cur { color: var(--ink-2); }
.crumb a:hover { color: var(--brand-ink); }

/* ── 页头（稿 `:185-188`）──────────────────────────────────────────────────── */
.head { padding-bottom: var(--s5); border-bottom: 1px solid var(--line-2); }
.head h1 {
  font-family: var(--font-display);
  font-size: var(--d2); font-weight: var(--fw-med); line-height: 1.15; letter-spacing: -.02em;
}
.head .sub { margin-top: var(--s1); font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 分区：靠**序号 + 发丝线 + 留白**分层，不靠字号（稿 `:191-199`）────────────── */
.form { margin-top: var(--s6); max-width: 680px; }
.sec { padding-top: var(--s6); margin-top: var(--s6); border-top: 1px solid var(--line-2); }
.sec:first-of-type { border-top: 0; margin-top: 0; padding-top: 0; }
.sec-h { display: flex; align-items: baseline; gap: var(--s3); margin-bottom: var(--s5); }
.sec-h .idx {
  font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.sec-h h2 { font-family: var(--font-display); font-size: var(--fs-md); font-weight: var(--fw-reg); }
.sec-h .note { margin-left: auto; font-size: var(--fs-xs); color: var(--ink-3); }
.grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--s5); }

/* ── 字段（稿 `:201-216`）──────────────────────────────────────────────────── */
.f { margin-bottom: var(--s5); }
.f:last-child { margin-bottom: 0; }
.f > label, .f .lb { display: block; font-size: var(--fs-xs); color: var(--ink-3); margin-bottom: var(--s2); }
/* 2026-10-10：标签行右侧挂「选模板」（详情 / 规格 / 售后三处）—— 标签与按钮**同一行**，
   按钮不占下方正文的宽度。⚠️ 按钮自带 `height:34px`（`.btn-quiet`），比标签行高 ⇒
   `align-items:center` 而不是 `baseline`，否则按钮会被顶得比文字基线低一截。 */
.f-head { display: flex; align-items: center; gap: var(--s3); margin-bottom: var(--s2); }
.f-head > label, .f-head > .lb { margin-bottom: 0; }
.f-head .btn-quiet { margin-left: auto; height: 28px; padding: 0 var(--s3); }
.f .hint { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); }
.mt { margin-top: var(--s5); }
.mt-s { margin-top: var(--s3); }

.inp {
  width: 100%; height: 40px; padding: 0 var(--s3);
  border: 1px solid var(--line-2); border-radius: var(--r2); background: var(--surface);
}
.inp:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
.inp.err { border-color: var(--danger); }
/* 占位输入必须**读成惰性**：底色降一档 + 字变灰，否则跟可填的框长得一样 */
.inp[disabled] { background: var(--surface-2); color: var(--ink-3); cursor: not-allowed; }
.inp.narrow { max-width: 240px; }
textarea.inp { height: 88px; padding: var(--s3); resize: none; font-family: inherit; line-height: 1.6; }

.tip { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--danger); }
.note-bad {
  margin-bottom: var(--s5); padding: var(--s3) var(--s4); border-radius: var(--r2);
  background: var(--danger-ground); color: var(--danger-deep); font-size: var(--fs-xs);
}

/* ── 价格 / 原价：¥ 与数字同框（稿 `:218-227`，与 GoodsUp 弹窗同款）───────────── */
.money {
  display: flex; align-items: center; width: 100%; max-width: 240px; height: 40px;
  padding: 0 var(--s3); gap: var(--s2);
  border: 1px solid var(--line-2); border-radius: var(--r2); background: var(--surface);
}
.money:focus-within { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
.money.err { border-color: var(--danger); }
.money[data-off] { background: var(--surface-2); }
.money .u { font-family: var(--font-display); color: var(--ink-3); }
.money input {
  flex: 1; min-width: 0; height: 100%; border: 0; outline: none; background: none;
  font-variant-numeric: tabular-nums;
}
.money input[disabled] { background: none; cursor: not-allowed; }

/* ── 图片：实线内框 + 虚线外框（稿 `:229-238`）───────────────────────────────── */
.pics { display: flex; gap: var(--s3); flex-wrap: wrap; }
.picbox {
  width: 120px; height: 120px; border: 1px dashed var(--line-2); border-radius: var(--r2);
  background: var(--surface-2); display: flex; align-items: center; justify-content: center;
  font-size: var(--fs-xs); color: var(--ink-3); cursor: pointer; text-align: center; line-height: 1.4;
}
.picbox:hover { border-color: var(--brand); color: var(--brand-ink); }
.picbox.err { border-color: var(--danger); }
.picbox.main { border-style: solid; border-color: var(--line-2); }
/* 无图时露 `.picbox` 自己的底；有图时预览铺满（原实现的 `previewImg`） */
.picbox-img { width: 100%; height: 100%; object-fit: cover; border-radius: inherit; display: block; }
/* 占位多图框：稿 `:415` / `:418` 的行内态（不可点 ⇒ 不显手型、虚线、降透明） */
.picbox.off { cursor: not-allowed; border-style: dashed; opacity: .75; }
.picbox.off:hover { border-color: var(--line-2); color: var(--ink-3); }
.picbox .tagline { font-size: var(--fs-xs); color: var(--ink-3); }
.pic-hint { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 类型 / 规格：与 GoodsUp 弹窗里的规格标签**同一个控件**（稿 `:240-254`）──────── */
.codes { display: flex; gap: var(--s3); flex-wrap: wrap; }
/* 单选组出错：光靠下面那行红字，用户看不出「错在这一组标签上」——给整组描一圈红
   （用 outline 而不是 border，是因为标签本身已经各有一条自己的边线）*/
.codes.err { outline: 1px solid var(--danger); outline-offset: 6px; border-radius: var(--r1); }
.code {
  position: relative; height: 34px; padding: 0 var(--s5);
  border: 1px solid var(--line-2); border-radius: var(--r1);
  color: var(--ink-2); font-size: var(--fs-sm);
  display: inline-flex; align-items: center;
}
.code:hover { border-color: var(--ink-3); }
.code.on { border-color: var(--brand); color: var(--brand-ink); font-weight: var(--fw-med); }
/* 原生 radio 视觉隐藏但保留可聚焦 / 可键盘切换（稿的 `aria-checked` 语义由它承担） */
.code input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.code:focus-within { outline: 2px solid var(--brand); outline-offset: 2px; }
/* 只读展示码（05 的单规格）：它不是控件 ⇒ 不显手型、不响应 hover */
.code.static { cursor: default; }
.code.static:hover { border-color: var(--brand); }
.addtype {
  margin-top: var(--s4); padding-top: var(--s4); border-top: 1px solid var(--line);
  display: flex; gap: var(--s3); align-items: center; flex-wrap: wrap;
}
.addtype input {
  width: 200px; height: 34px; padding: 0 var(--s3);
  border: 1px solid var(--line-2); border-radius: var(--r1); background: var(--surface);
}
.addtype input:focus { outline: none; border-color: var(--brand); }
.addtype .hint { margin: 0; font-size: var(--fs-xs); color: var(--ink-3); }

/* ── 服务承诺勾选：文案必须与 ProductPage 承诺条逐字一致（稿 `:256-266`）────────── */
.svcs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--s3) var(--s5); }
.svc { display: flex; align-items: center; gap: var(--s3); font-size: var(--fs-sm); color: var(--ink-2); }
.ck {
  width: 16px; height: 16px; flex: 0 0 auto; border: 1px solid var(--line-2);
  border-radius: var(--r1); background: var(--surface); padding: 0; cursor: pointer;
}
.ck[aria-checked="true"] { background: var(--brand); border-color: var(--brand); }
.btn-quiet {
  height: 34px; padding: 0 var(--s4); border: 1px solid var(--line-2);
  border-radius: var(--r1); color: var(--ink-2);
}
.btn-quiet[disabled] { color: var(--ink-3); cursor: not-allowed; }

/* ── 多规格矩阵：表头 + 行（**真表格**：每格可编辑；稿 `:268-274` 的视觉）──────────
   ⚠️ 2026-10-11：占位块转真功能 ⇒ ① 虚线框改实线（它不再是「预留」）② 末列加一枚删除键
   ⇒ 网格从 4 列改 5 列（末列 `auto`，只放那枚 ×）③ 格子里放真 `<input>`。 */
.matrix { margin-top: var(--s4); border: 1px solid var(--line-2); border-radius: var(--r2); overflow: hidden; }
.matrix .hd, .matrix .rw { display: grid; grid-template-columns: 1.2fr 1fr 1fr 1.2fr auto; gap: 1px; background: var(--line); }
.matrix .hd > * {
  background: var(--surface-2); color: var(--ink-3); font-size: var(--fs-xs);
  padding: var(--s3) var(--s3);
}
.matrix .rw > * { background: var(--surface); padding: var(--s2) var(--s3); color: var(--ink-3); font-size: var(--fs-sm); min-width: 0; }
.matrix .nm { color: var(--ink-2); }
/* 格内输入：去掉盒子（格线已经画出格子了），聚焦时给一圈 brand 环 */
.matrix .minp { width: 100%; border: 0; background: transparent; font: inherit; color: var(--ink); padding: var(--s1) 0; }
.matrix .minp::placeholder { color: var(--ink-3); }
.matrix .minp:focus { outline: none; box-shadow: 0 0 0 2px var(--brand-tint); border-radius: var(--r1); }
.matrix .op { display: flex; align-items: center; justify-content: center; }
.matrix .rm { width: 24px; height: 24px; border: 1px solid var(--line-2); border-radius: var(--r1); color: var(--ink-3); }
.matrix .rm:hover { color: var(--danger); border-color: var(--danger); }
/* 空态那一行：一句提示横跨全部列 */
.matrix .empty > span { grid-column: 1 / -1; color: var(--ink-3); font-size: var(--fs-sm); }

/* ── 底部动作条（稿 `:276-286`）────────────────────────────────────────────── */
.acts {
  margin-top: var(--s8); padding-top: var(--s5); border-top: 1px solid var(--line-2);
  display: flex; align-items: center; gap: var(--s4);
}
.btn-main {
  height: 44px; padding: 0 var(--s7); border-radius: var(--r2); background: var(--brand);
  color: var(--bg); font-weight: var(--fw-med); white-space: nowrap;
}
.btn-main:hover { background: var(--brand-hover); }
.btn-draft {
  height: 44px; padding: 0 var(--s6); border: 1px solid var(--line-2); border-radius: var(--r2);
  color: var(--ink-2); white-space: nowrap;
}
.btn-draft:not([disabled]):hover { border-color: var(--ink-3); }
.btn-draft[disabled] { color: var(--ink-3); cursor: not-allowed; }
.acts .hint { margin-left: auto; font-size: var(--fs-xs); color: var(--ink-3); text-align: right; }

/* ── 原实现的行内 `style="display:none"` ⇒ 收敛成类 ────────────────────────── */
.file-hidden { display: none; }

/* ── 窄档（稿 `:296-308`）—— `.body` / `.uc-menu` 两件不在本组件 ⇒ 只搬后两条 ──── */
@media (max-width: 1080px) {
  .form { max-width: none; }
}
@media (max-width: 720px) {
  .grid2 { grid-template-columns: 1fr; gap: var(--s5); }
  .svcs { grid-template-columns: 1fr; }
  .acts { flex-direction: column; align-items: stretch; }
  .btn-main { order: -1; }
  .acts .hint { margin-left: 0; text-align: left; }
}
</style>
