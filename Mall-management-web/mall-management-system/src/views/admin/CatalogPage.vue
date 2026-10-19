<template>
  <section class="c-cat">
    <!-- ── ① 状态条 ──────────────────────────────────────────────────────────
         照 `AccountsPage.vue` / `AuditPage.vue` 那一格：本页所有时间列都是**无时区**的库内文本，
         唯一「错也看不出来」的地方就是偏移，摆在台面上即可一眼核。 -->
    <header class="c-status">
      <div class="c-stat">
        <b>{{ pendingCount }}</b>
        <span>条待审提审{{ appsFiltered ? '（筛选后）' : '' }}</span>
      </div>
      <div class="c-stat">
        <b>{{ cats.length }}</b>
        <span>个类目（含停用）</span>
      </div>
      <div class="c-stat">
        <b>{{ tpls.length }}</b>
        <span>条模板</span>
      </div>
      <div class="c-tz">
        <span class="c-chip-utc">UTC</span>
        <span>库内时钟 → <b>浏览器时区</b></span>
        <span class="c-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="c-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── 页内 tab ──────────────────────────────────────────────────────────
         四块同属「平台上卖什么、怎么分类、长什么样」一个域 ⇒ 页内 tab，不拆成四个地址
         （规范：`docs/agent/c3-modules.md` §五「子项页含多块 ⇒ 页内 tab，tab 数 2–4 个」）。
         ⚠️ 顺序 = 事情的自然顺序：先审商家递上来的 → 再管平台自己的分类骨架 → 再管模板
            → 最后才是对已上架商品的违规处置（处置是例外路径，不是日常）。 -->
    <nav class="c-tabs">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        class="c-tab"
        :class="{ on: tab === t.key }"
        @click="tab = t.key"
      >
        {{ t.label }}<span class="c-tabn">{{ t.note() }}</span>
      </button>
    </nav>

    <!-- ══ ② 商品提审审核（tab 一）═══════════════════════════════════════════ -->
    <template v-if="tab === 'apps'">
    <h2 class="c-h2">商品提审审核 <span class="c-h2n">通过 = `goods` 表新增 1 行（买家即刻可见）；驳回 = 只改提审单、`goods` 零新行</span></h2>

    <p class="c-hint">
      商家在「上架商品」里提交的<b>不再直接上架</b> —— 只落一条待审单（<code>goods_audit</code>）。
      故「买家看不到」是<b>压根没上架</b>，不是被过滤掉了。
      ⚠️ 驳回<b>必须填理由</b> —— 该理由会<b>展示给商家</b>（前端与后端两处各拦一次）。
    </p>

    <form class="c-filters" @submit.prevent="loadApps">
      <label class="c-f">
        <span>状态</span>
        <select v-model="af.status" @change="loadApps">
          <option value="">全部</option>
          <option value="pending">待审</option>
          <option value="approved">已通过</option>
          <option value="rejected">已驳回</option>
        </select>
      </label>
      <label class="c-f c-f-narrow">
        <span>上限</span>
        <select v-model.number="af.limit" @change="loadApps">
          <option v-for="n in [20, 50, 100]" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <div class="c-f-actions">
        <button type="submit" class="c-btn c-btn-on" :disabled="loadingApps">
          {{ loadingApps ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </form>

    <!-- ── 审核通过率 · 类目分布（可视化段 commit 3）──────────────────────────────
         ⚠️ 两张图的数据源都是**本次已取回的 `apps`**（受上面「状态 / 上限」约束，≤ 500），
            不是全库提交量 —— 标题里带「本次 N 条」，与看板页对「区间」的处置同款。
         ⚠️ 最容易被读错的一处：**筛选条一旦选了某个状态**，环图就只剩单色（选「已通过」
            则为 100%、选「已驳回」则为 0%）—— 那不是通过率，是筛子的自拍照。故把
            `appsFiltered` 直接标在标题里，让人一眼看到「这图被筛过」。 -->
    <h2 class="c-h2">
      审核通过率
      <span class="c-h2n">
        本次 {{ apps.length }} 条{{ appsFiltered ? '（⚠️ 已按状态筛选 —— 下面的比例是筛后口径）' : '' }}
        · 已审毕 {{ auditMix.done }} 条（另 {{ auditMix.wait }} 条待审**不计入**通过率）
      </span>
    </h2>
    <div class="c-card">
      <DonutChart
        :data="auditMix.data"
        :height="'260px'"
        :center-value="auditMix.rate === null ? '—' : `${auditMix.rate}%`"
        center-title="通过率（已审毕）"
        :loading="loadingApps"
        empty-text="本次没有已审毕的提审单 —— 待审的还没结果，不算通过也不算驳回"
      />
    </div>

    <h2 class="c-h2">
      类目分布 × 月
      <span class="c-h2n">
        本次提审单按 `GoodType` 分组 · 每月一条 · 降序取前 10<span v-if="catByMonth.more">（另有 {{ catByMonth.more }} 个类目未画）</span>
        <span v-if="catByMonth.moreMonths"> · ⚠️ 只画最近 3 个月，更早的 {{ catByMonth.moreMonths }} 个月未画</span>
      </span>
    </h2>
    <div class="c-card">
      <BarChart
        :categories="catByMonth.cats"
        :series="catByMonth.series"
        :height="'320px'"
        value-formatter="{value} 条"
        :max-bar-width="18"
        :loading="loadingApps"
        empty-text="本次提审单没有可归类的 `GoodType`，或一行都没有可归月的提交时刻"
      />
    </div>

    <ol v-if="apps.length" class="c-stream">
      <li v-for="r in apps" :key="r.Id" class="c-card" :class="{ done: r.Status !== 'pending' }">
        <div class="c-card-l1">
          <code class="c-tag">#{{ r.Id }}</code>
          <b class="c-strong">{{ r.GoodName }}</b>
          <span class="c-badge" :class="r.Status"><IconGlyph :name="statusGlyph(r.Status)" />{{ statusLabel(r.Status) }}</span>
          <span class="c-dim">商家 {{ dash(r.MerchantId) }}</span>
          <span class="c-grow"></span>
          <span class="c-dim">{{ toLocal(r.SubmittedAt) }}</span>
        </div>

        <dl class="c-kv">
          <div><dt>价格</dt><dd>{{ dash(r.Price) }}</dd></div>
          <div><dt>类目</dt><dd>{{ dash(r.GoodType) }}</dd></div>
          <div><dt>类目 Id</dt><dd class="c-mono">{{ r.TypeId == null ? '—（审核通过时买家二级筛选查不到它）' : r.TypeId }}</dd></div>
          <div><dt>商品编码</dt><dd class="c-mono">{{ dash(r.ProductCode) }}</dd></div>
          <div><dt>图片</dt><dd>{{ dash(r.GoodImg) }}</dd></div>
          <div><dt>批次</dt><dd class="c-mono">{{ dash(r.BatchId) }}</dd></div>
          <!-- C3 块 5 · A 组 · A3：**带券的提审单**（商家在「更改商品」里挂的商品券，随商品一起审）。
               ⚠️ 没有 `Coupon` 字段就**整行不出** —— 普通商品单不渲染成一行空白（同 `notify-events.js`
                  那条「任何一行都不许渲染成空白」）。⚠️ 这类单**通过时没有新商品上架**（商品当场就改好了），
                  通过与否的效果是**券生效**（见 `couponText` 尾那截状态）—— 管理员得看得见这件事。 -->
          <div v-if="r.Coupon"><dt>商品券</dt><dd class="c-mono">{{ couponText(r.Coupon) }}</dd></div>
          <div><dt>审毕</dt><dd>{{ r.ReviewedAt ? toLocal(r.ReviewedAt) : '—' }}</dd></div>
          <div><dt>审核人</dt><dd>{{ dash(r.ReviewedBy) }}</dd></div>
          <div><dt>落库商品 Id</dt><dd class="c-mono">{{ r.GoodsId == null ? '—（未通过）' : r.GoodsId }}</dd></div>
        </dl>

        <p v-if="r.Status === 'rejected'" class="c-why">驳回理由：{{ dash(r.RejectReason) }}</p>

        <div v-if="r.Status === 'pending'" class="c-acts">
          <input
            v-model.trim="rejectReasons[r.Id]"
            class="c-inp"
            type="text"
            placeholder="驳回理由（驳回时必填，会展示给商家）"
            @keyup.enter="reject(r)"
          />
          <!-- 「选模板」= 从 `goods.reject` 取一段理由填进上面的输入框（填完**还能改**，见弹窗注释）。 -->
          <button type="button" class="c-btn" @click="openRejectTpl(r)">选模板</button>
          <button type="button" class="c-btn c-btn-on" :disabled="busyKey !== null" @click="approve(r)">
            <IconGlyph name="ok" />通过
          </button>
          <button type="button" class="c-btn c-btn-danger" :disabled="busyKey !== null" @click="reject(r)">
            <IconGlyph name="no" />驳回
          </button>
        </div>
      </li>
    </ol>
    <p v-else-if="!loadingApps" class="c-empty">没有符合条件的提审单。</p>
    <p v-if="appsTruncated" class="c-note">
      已截断：按上限 <b>{{ appsLimit }}</b> 条显示，共命中 <b>{{ appsTotal }}</b> 条。
    </p>

    </template>

    <!-- ══ ③ 类目树（tab 二）═════════════════════════════════════════════════ -->
    <template v-else-if="tab === 'cats'">
    <h2 class="c-h2">类目树 <span class="c-h2n">唯一键 (ParentId, GoodType) ⇒ 不同父级下同名是允许的</span></h2>

    <p class="c-hint">
      树读的是<b>既有</b>公开端点 <code>/goods/goodstype</code>（它<b>不筛</b> <code>State</code>
      ⇒ 停用的类目也在这儿；买家侧的一级 chips 才在前端按 <code>ParentId === 0</code> 过滤）。
      ⚠️ 删 = <b>逻辑废弃</b>（<code>State = 0</code>，行还在）—— 规则 v2「不物理删」。
      ⚠️ 删一级类目<b>不会</b>级联停用它的子类目（只报数，见下方提示文字）。
    </p>

    <form class="c-filters" @submit.prevent="createCat">
      <label class="c-f c-f-wide">
        <span>类目名</span>
        <input v-model.trim="cf.GoodType" type="text" placeholder="如：数码 / 手机" />
      </label>
      <label class="c-f">
        <span>父级</span>
        <select v-model.number="cf.ParentId">
          <option :value="0">（无 —— 建一级类目）</option>
          <option v-for="r in roots" :key="r.Id" :value="r.Id">{{ r.GoodType }}</option>
        </select>
      </label>
      <label class="c-f c-f-narrow">
        <span>排序</span>
        <input v-model.number="cf.Sort" type="number" />
      </label>
      <label class="c-f c-f-narrow">
        <span>新类型</span>
        <select v-model.number="cf.GoodTypeNew">
          <option :value="1">是（1）</option>
          <option :value="0">否（0）</option>
        </select>
      </label>
      <div class="c-f-actions">
        <button type="submit" class="c-btn c-btn-on" :disabled="busyKey !== null">
          {{ busyKey === 'catCreate' ? '新增中…' : '新增类目' }}
        </button>
        <button type="button" class="c-btn" :disabled="loadingCats" @click="loadCats">
          {{ loadingCats ? '刷新中…' : '刷新树' }}
        </button>
      </div>
    </form>

    <p v-if="!cats.length && !loadingCats" class="c-empty">类目表是空的 —— 先建一个一级类目。</p>
    <ol v-else class="c-stream">
      <li v-for="r in roots" :key="r.Id" class="c-card" :class="{ off: r.State === 0 }">
        <div class="c-card-l1">
          <code class="c-tag">#{{ r.Id }}</code>
          <b class="c-strong">{{ r.GoodType }}</b>
          <span class="c-badge" :class="r.State === 0 ? 'rejected' : 'approved'">
            <IconGlyph :name="r.State === 0 ? 'no' : 'ok'" />{{ r.State === 0 ? '已停用' : '启用中' }}
          </span>
          <span class="c-dim">一级 · 排序 {{ dash(r.Sort) }} · 新类型 {{ dash(r.GoodTypeNew) }}</span>
          <span class="c-grow"></span>
          <button type="button" class="c-btn" :disabled="busyKey !== null" @click="toggleEdit('cat', r)">
            {{ editing === 'cat:' + r.Id ? '收起' : '改名 / 排序' }}
          </button>
          <button type="button" class="c-btn" :disabled="busyKey !== null" @click="toggleState(r)">
            {{ r.State === 0 ? '启用' : '停用' }}
          </button>
          <button type="button" class="c-btn c-btn-danger" :disabled="busyKey !== null" @click="removeCat(r)">
            删除
          </button>
        </div>

        <form v-if="editing === 'cat:' + r.Id" class="c-edit" @submit.prevent="saveCat(r)">
          <label class="c-f c-f-wide">
            <span>名称</span>
            <input v-model.trim="ef.GoodType" type="text" />
          </label>
          <label class="c-f c-f-narrow">
            <span>排序</span>
            <input v-model.number="ef.Sort" type="number" />
          </label>
          <label class="c-f">
            <span>父级</span>
            <select v-model.number="ef.ParentId">
              <option :value="0">（无 —— 一级）</option>
              <option v-for="x in roots.filter((o) => o.Id !== r.Id)" :key="x.Id" :value="x.Id">
                {{ x.GoodType }}
              </option>
            </select>
          </label>
          <div class="c-f-actions">
            <button type="submit" class="c-btn c-btn-on" :disabled="busyKey !== null">保存</button>
          </div>
        </form>

        <ul v-if="childrenOf(r.Id).length" class="c-kids">
          <li v-for="k in childrenOf(r.Id)" :key="k.Id" class="c-kid" :class="{ off: k.State === 0 }">
            <code class="c-tag">#{{ k.Id }}</code>
            <span class="c-strong">{{ k.GoodType }}</span>
            <span class="c-badge" :class="k.State === 0 ? 'rejected' : 'approved'">
              <IconGlyph :name="k.State === 0 ? 'no' : 'ok'" />{{ k.State === 0 ? '已停用' : '启用中' }}
            </span>
            <span class="c-dim">二级 · 排序 {{ dash(k.Sort) }}</span>
            <span class="c-grow"></span>
            <button type="button" class="c-btn" :disabled="busyKey !== null" @click="toggleEdit('cat', k)">
              {{ editing === 'cat:' + k.Id ? '收起' : '改名 / 排序' }}
            </button>
            <button type="button" class="c-btn" :disabled="busyKey !== null" @click="toggleState(k)">
              {{ k.State === 0 ? '启用' : '停用' }}
            </button>
            <button type="button" class="c-btn c-btn-danger" :disabled="busyKey !== null" @click="removeCat(k)">
              删除
            </button>

            <form v-if="editing === 'cat:' + k.Id" class="c-edit" @submit.prevent="saveCat(k)">
              <label class="c-f c-f-wide">
                <span>名称</span>
                <input v-model.trim="ef.GoodType" type="text" />
              </label>
              <label class="c-f c-f-narrow">
                <span>排序</span>
                <input v-model.number="ef.Sort" type="number" />
              </label>
              <div class="c-f-actions">
                <button type="submit" class="c-btn c-btn-on" :disabled="busyKey !== null">保存</button>
              </div>
            </form>
          </li>
        </ul>
      </li>
    </ol>

    </template>

    <!-- ══ ④ 模板库（tab 三）═════════════════════════════════════════════════ -->
    <template v-else-if="tab === 'tpl'">
    <h2 class="c-h2">基础模板库 <span class="c-h2n">平台级资源：管理员建，商家上架时选用，买家在商品详情页看到</span></h2>

    <p class="c-hint">
      本页只看<b>商品类</b>模板（<code>TypePrefix = 'goods.'</code>）：<code>goods.detail</code> 详情 /
      <code>goods.spec</code> 规格 / <code>goods.afterSale</code> 售后 / <code>goods.reject</code> 驳回理由。
      ⚠️ 类型码是<b>字符串</b>（点分码），不建 DB ENUM；通用表还有 <code>merchant.*</code> /
      <code>user.*</code> / <code>notification.*</code> 等 type，住别的页。
      ⚠️ 本页是<b>管理端</b>视图 ⇒ 带 <code>includeDisabled</code>，<b>停用的也看得见</b>；
      商家端「选模板」弹窗走公共读 <code>/template/list</code>，<b>只回启用中的</b>。
      ⚠️ 模板是<b>快照源</b>不是引用：商家选完填进输入框还能改，商品存的是<b>最终文本</b>
      ⇒ 这里改模板<b>不影响</b>已上架商品。
      ⚠️ 删 = <b>逻辑废弃</b>（<code>Enabled = 0</code>，规则 v2）。
    </p>

    <form class="c-filters" @submit.prevent="createTpl">
      <label class="c-f">
        <span>类型</span>
        <select v-model="tf.Type">
          <option value="goods.detail">详情（goods.detail）</option>
          <option value="goods.spec">规格（goods.spec）</option>
          <option value="goods.afterSale">售后（goods.afterSale）</option>
          <option value="goods.reject">驳回理由（goods.reject）</option>
        </select>
      </label>
      <label class="c-f">
        <span>形态</span>
        <!-- ⚠️ 2026-10-11（用户裁决 ④）：`Kind` 决定这条模板「怎么用正文」——
             文本 = 填一段话（详情 / 售后 / 规格说明）；结构化 = 正文是**结构定义**
             （规格矩阵的行列：一行一条轴，`轴名=值1,值2,…`），商家上架时按它生成矩阵行。
             只有 `goods.spec` 两种形态都有意义，其余类型选结构化不会破坏什么、只是用不上。 -->
        <select v-model="tf.Kind">
          <option value="text">文本（填一段话）</option>
          <option value="structured">结构化（生成矩阵，仅规格用）</option>
        </select>
      </label>
      <label class="c-f c-f-wide">
        <span>模板名</span>
        <input v-model.trim="tf.Name" type="text" placeholder="如：数码类默认详情" />
      </label>
      <div class="c-f-actions">
        <button type="submit" class="c-btn c-btn-on" :disabled="busyKey !== null">
          {{ busyKey === 'tplCreate' ? '新增中…' : '新增模板' }}
        </button>
        <button type="button" class="c-btn" :disabled="loadingTpls" @click="loadTpls">
          {{ loadingTpls ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </form>

    <label class="c-f c-f-wide c-inline">
      <span>内容（与上面「模板名」一起提交；新增后可在下方逐条改）</span>
      <textarea v-model="tf.Body" rows="3" placeholder="模板正文（纯文本；商家选中后填进输入框、可改，落库是快照）"></textarea>
    </label>

    <ol v-if="tpls.length" class="c-stream">
      <li v-for="t in tpls" :key="t.Id" class="c-card" :class="{ off: !isTplEnabled(t) }">
        <div class="c-card-l1">
          <code class="c-tag">#{{ t.Id }}</code>
          <b class="c-strong">{{ t.Name }}</b>
          <span class="c-badge" :class="isTplEnabled(t) ? 'approved' : 'rejected'">
            <IconGlyph :name="isTplEnabled(t) ? 'ok' : 'no'" />{{ isTplEnabled(t) ? '启用中' : '已停用' }}
          </span>
          <span class="c-dim">{{ tplTypeLabel(t.Type) }}</span>
          <!-- 结构化模板标出来（文本是默认形态，标它等于每条都带一句噪声 ⇒ 只标结构化）。 -->
          <span v-if="t.Kind === 'structured'" class="c-dim">· 结构化</span>
          <span class="c-grow"></span>
          <button type="button" class="c-btn" :disabled="busyKey !== null" @click="toggleEdit('tpl', t)">
            {{ editing === 'tpl:' + t.Id ? '收起' : '改名 / 改内容' }}
          </button>
          <button type="button" class="c-btn" :disabled="busyKey !== null" @click="toggleTpl(t)">
            {{ isTplEnabled(t) ? '停用' : '启用' }}
          </button>
          <button type="button" class="c-btn c-btn-danger" :disabled="busyKey !== null" @click="removeTpl(t)">
            删除
          </button>
        </div>

        <p class="c-body">{{ dash(t.Body) }}</p>

        <form v-if="editing === 'tpl:' + t.Id" class="c-edit" @submit.prevent="saveTpl(t)">
          <label class="c-f c-f-wide">
            <span>名称</span>
            <input v-model.trim="ef.Name" type="text" />
          </label>
          <label class="c-f">
            <span>形态</span>
            <select v-model="ef.Kind">
              <option value="text">文本（填一段话）</option>
              <option value="structured">结构化（生成矩阵，仅规格用）</option>
            </select>
          </label>
          <label class="c-f c-f-wide">
            <span>内容</span>
            <textarea v-model="ef.Body" rows="3"></textarea>
          </label>
          <div class="c-f-actions">
            <button type="submit" class="c-btn c-btn-on" :disabled="busyKey !== null">保存</button>
          </div>
        </form>
      </li>
    </ol>
    <p v-else-if="!loadingTpls" class="c-empty">还没有模板 —— 先建一条。</p>

    </template>

    <!-- ══ ⑤ 违规处置（tab 四）═══════════════════════════════════════════════ -->
    <template v-else>
    <h2 class="c-h2">违规处置 <span class="c-h2n">下架 = `ShelfState=0`（可再上架）；删除 = `DeletedAt=now`（行还在，规则 v2）</span></h2>

    <p class="c-hint">
      候选来自<b>买家可见面</b>（<code>/goods/goodsSelectHome</code>）—— 已下架 / 已删除的行本来就不在这里，
      故不会出现「已处置过还想再处置」。谓词是 <code>(MerchantId, GoodName, ProductCode)</code> <b>三元组</b>，
      不用自增 <code>goods.Id</code>（后者会随全量重导变）。
      ⚠️ 两种处置都<b>不给商家删行</b> —— 只让它从买家侧消失，留行以便追责与恢复。
    </p>

    <form class="c-filters" @submit.prevent="loadGoods">
      <label class="c-f c-f-wide">
        <span>关键字（本地筛：商品名 / 商家号 / 编码）</span>
        <input v-model.trim="vg.keyword" type="text" placeholder="留空 = 全部可见商品" />
      </label>
      <div class="c-f-actions">
        <button type="submit" class="c-btn c-btn-on" :disabled="loadingGoods">
          {{ loadingGoods ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </form>

    <p v-if="!visibleGoods.length && !loadingGoods" class="c-empty">买家侧当前没有可见商品。</p>
    <p v-else-if="goodsFiltered.length === 0" class="c-empty">没有匹配「{{ vg.keyword }}」的商品。</p>
    <ol v-else class="c-stream">
      <li v-for="g in goodsFiltered" :key="g.MerchantId + '\n' + g.ProductCode" class="c-card">
        <div class="c-card-l1">
          <b class="c-strong">{{ g.GoodName }}</b>
          <span class="c-dim">商家 {{ dash(g.UserName || g.MerchantId) }}</span>
          <code class="c-tag">{{ dash(g.ProductCode) }}</code>
          <span class="c-dim">¥{{ dash(g.Price) }}</span>
          <span class="c-grow"></span>
          <button type="button" class="c-btn" :disabled="busyKey !== null" @click="delist(g)">下架</button>
          <button type="button" class="c-btn c-btn-danger" :disabled="busyKey !== null" @click="removeGoods(g)">
            删除
          </button>
        </div>
      </li>
    </ol>

    </template>

    <!-- ⚠️ 只有一个弹窗实例（不是每行一个）：`rejectTplFor` 记住「这次是给哪张提审单挑理由」。
         ⚠️ `<TemplatePicker>` 的 `pick` **不自动关窗**（组件注释写明）⇒ 这里自己清 `rejectTplFor`。 -->
    <TemplatePicker
      v-if="rejectTplFor"
      type="goods.reject"
      title="选择驳回理由模板"
      empty-text="暂无 goods.reject 模板 —— 可先在「模板库」tab 建一条"
      @pick="onPickRejectTpl"
      @close="rejectTplFor = null"
    />
  </section>
</template>

<!--
  商品与类目治理页（C3 块 3〈商品块〉，2026-10-10）—— `/admin/business/catalog` 的真页。

  它是 `StubPage.vue` 预告的「等某一块真拿到内容时再单独拆」的**第三个**实例
  （前两个是 `AuditPage.vue` / `AccountsPage.vue`；名单见 `router/index.js` 的 `children`）。

  【它消费后端四组（11 条写 + 2 条读）】—— 全部经 `api/*.js` 包一层，本页**不直接**碰 `http`：
    · `/admin/catalog/applications` · `/admin/catalog/application/audit`  —— 商品提审（`GoodsAuditService`）
    · `/admin/catalog/category/*`                                        —— 类目（`CategoryService`）
    · `/admin/template/*`                                                —— 模板（`TemplateService`，通用表）
    · `/admin/catalog/violation/*`                                       —— 违规（`GoodsViolationService`）
  另有两条**既有**端点的读（都**不新开**）：
    · `GET /goods/goodstype`  —— 类目树（不筛 `State`，管理端要看见停用的）
    · `POST /goods/goodsSelectHome` —— 违规处置的候选商品（买家可见面）
  ⚠️ **2026-10-10〈模板表通用化〉**：模板那 4 条**搬了家** —— 端点由 `/admin/catalog/template/*`
    换成 `/admin/template/*`（`TemplateService` 通用 CRUD，`Type` 前缀 `'goods.'`），前端封装也从
    `api/admin.js` 挪到 `api/template.js`（`NotifyPage.vue` 同模块、前缀 `'notification.'`）。

  【⚠️ 参数名不统一 —— 这是后端**逐字**的现状，别「顺手」统一】
    · 审核那两条：`id` / `approve` / `rejectReason`（**camelCase**）
    · 类目那三条：`GoodType` / `GoodTypeNew` / `ParentId` / `Sort` / `State`（**PascalCase**）
    · 模板那四条：`Type` / `Name` / `Body` / `Enabled`（**PascalCase**；旧的 `TemplateType` / `Content` 已不存在）
    · 违规那两条：`MerchantId` / `GoodName` / `ProductCode`（**PascalCase**）
    ⇒ 传错大小写 = 后端收到 `null` ⇒ 表现为「参数缺失」而不是报错。改本页时对着 Controller 核。

  【⚠️ 「通过」是唯一会写 `goods` 表的动作】`GoodsAuditService.audit` 在**同一个 `@Transactional`**
    里做 CAS + `insertGoods` + `insertProductsales` + 回填 `GoodsId` + 失效缓存 ⇒ 要么三件都成，
    要么提审单退回 `pending`（不留「approved 但没商品」的半完成态）。页面上 `落库商品 Id` 那一栏
    就是这条的正面证据：**通过了才非空**。

  【⚠️ `outcome === 'already-handled'` 不是失败】并发下另一个管理员先处理了 ⇒ `success:true`
    但**什么都没发生**（不发通知、不记审计）。照 `AccountsPage.vue` 的做法：不报「通过成功」，
    改说「已经被处理过了」，并刷新列表。

  【规范与视觉】照 `c3-modules.md` §五 与 `AccountsPage.vue`：页内 tab（2–4 个）、数字→状态条、
    列表→卡片式叙事、危险动作走 `--danger` 系。⚠️ 本页是**第二个**带页内 tab 的管理页
    ⇒ tab 那一套样式**逐字照抄** `AccountsPage.vue` 的（房规：不许各造各的）。

  【⚠️ 已停用的行怎么画】`.off` 走 `--surface-2` 底（同 `AccountsPage` 的 `.done`）——
    「停用」是**状态**不是**危险**，故不用红；红只留给「删除 / 驳回」这类不可逆动作。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  catalogApplications,
  catalogAudit,
  catalogCategories,
  catalogCategoryCreate,
  catalogCategoryDelete,
  catalogCategoryUpdate,
  catalogViolationDelist,
  catalogViolationDelete,
  catalogVisibleGoods,
} from '../../api/admin'
// ⚠️ 模板 CRUD **已迁出** `api/admin.js`（2026-10-10〈模板表通用化〉）：端点由
//    `/admin/catalog/template/*` 换成通用表 `/admin/template/*`，模块独立成 `api/template.js`
//    （`NotifyPage.vue` 改调同一支，只是 `TypePrefix` 不同：本页 `'goods.'`、它 `'notification.'`）。
import { templateAdminCreate, templateAdminDelete, templateAdminList, templateAdminUpdate } from '../../api/template'
// 「选模板」弹窗（本页只用一次：提审单的驳回理由）。
import TemplatePicker from '../../components/TemplatePicker.vue'
// 图表（可视化段 commit 3）—— 本页只用「审核通过率」环图 + 「类目分布」横条两张。
import BarChart from '../../components/charts/BarChart.vue'
import DonutChart from '../../components/charts/DonutChart.vue'; import IconGlyph from '../../components/ui/IconGlyph.vue'   // ⚠️ 两条 import **同行**（2026-10-11 补图标那一笔）：本文件**曾**被 `progress.md:1983` 按行号钉住（那两个「通过 / 驳回」按钮，**原 114/117 行**；该条 2026-10-11 收口已改「原 N 行」写法、不再钉），加行只会让账更难对。⚠️ 那两行上的 `busyId` 拼写在同笔**一并订正为 `busyKey`**（既有 bug，`progress.md:1983` 记的就是它；本笔顺手修掉，收口时已在该条上追加）—— 现落 **157/160 行**

// ── 时区（照 `AccountsPage.vue` / `AuditPage.vue` 的 `toLocal` / `tzLabel`，逐字同款）──────
// ⚠️ 后端时间列是 `DATE_FORMAT(…, '%Y-%m-%d %H:%i:%s')` 的**无时区文本**，而库里存的是 **UTC**。
//    末尾那个 `Z` 不能省：省掉就按浏览器本地时区解析，显示上整差一个偏移，且**错得很安静**。
const pad = (n) => String(n).padStart(2, '0')

const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)   // 解析不了原样回 —— 不崩、不空白
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

const dash = (v) => (v === null || v === undefined || String(v).trim() === '' ? '—' : String(v))

// ── 页内 tab ─────────────────────────────────────────────────────────────────
// ⚠️ `note()` 是**函数**不是字符串：`pendingCount` / `cats` / `tpls` 都在下面才声明
//    （`const` 有 TDZ），写成字符串会在模块求值期就炸；写成函数则只在**渲染时**求值，安全。
const tab = ref('apps')
const TABS = [
  { key: 'apps', label: '商品审核', note: () => `${pendingCount.value} 待审` },
  { key: 'cats', label: '类目树', note: () => `${cats.value.length} 个` },
  { key: 'tpl', label: '模板库', note: () => `${tpls.value.length} 条` },
  { key: 'vio', label: '违规处置', note: () => `${visibleGoods.value.length} 件可见` },
]

// ── 反馈 ─────────────────────────────────────────────────────────────────────
/** 一次性提示条。`kind` 只有 `'ok'` / `'bad'` 两种。 */
const flash = reactive({ kind: 'ok', text: '' })
function say(kind, text) {
  flash.kind = kind
  flash.text = text
}
/** 把后端的 `{success:false,msg}` 或异常统一转成一句人话。 */
function failOf(e) {
  if (e && e.status) return `HTTP ${e.status}`
  return (e && e.message) || String(e)
}
/**
 * 正在处理的那一条 / 那个动作（防重复点）。`null` = 空闲。
 * ⚠️ 本页**四块共用一个闩**（不像 `AccountsPage` 只护住单块的 `busyId`）—— 因为四块的写动作
 *    都落在同一批表上（`goodstype` / `goods_template` / `goods`），并发点两处最容易出乱子。
 */
const busyKey = ref(null)
/**
 * 正在展开「改名 / 改内容」编辑区的那一行 —— 存的是 **`标签:Id`** 而不是裸 `Id`。
 *
 * <p>⚠️ **必须带标签**：三张表（`goodstype` / `goods_template`）的 `Id` 各自从 1 开始，
 * 裸 `Id` 会**跨 tab 撞上** —— 在类目树里编辑 `#1`、切到模板库，模板 `#1` 会**自己展开**
 * 且草稿里灌的是**类目的值**（`Name`/`Content` 空着），一点「保存」就把模板名写成类目名。
 * 这是**同一个数字在两处都合法**造成的，不是理论风险。
 */
const editing = ref(null)

/** 展开/收起某一行的编辑区，并把当前值灌进草稿（**不就地改行数据** —— 行是后端原样）。 */
const ef = reactive({ GoodType: '', Sort: 0, ParentId: 0, Name: '', Body: '', Kind: 'text' })
function toggleEdit(kind, row) {
  const key = `${kind}:${row.Id}`
  if (editing.value === key) {
    editing.value = null
    return
  }
  ef.GoodType = row.GoodType == null ? '' : String(row.GoodType)
  ef.Sort = Number(row.Sort) || 0
  ef.ParentId = Number(row.ParentId) || 0
  ef.Name = row.Name == null ? '' : String(row.Name)
  // ⚠️ 模板行的正文列叫 **`Body`**（旧 `goods_template.Content` / `notification_template.BodyTemplate`
  //    已随两表删除），类目行没有这一列 ⇒ 读不到就是 `''`（`ef.Body` 是两 tab 共用的草稿字段）。
  ef.Body = row.Body == null ? '' : String(row.Body)
  // 2026-10-11：形态（`Kind`）—— 类目行没有这一列 ⇒ 读不到就是 `'text'`（列默认值）。
  ef.Kind = row.Kind === 'structured' ? 'structured' : 'text'
  editing.value = key
}

// ── ① 提审单 ─────────────────────────────────────────────────────────────────
const af = reactive({ status: '', limit: 100 })
const apps = ref([])
const appsTotal = ref(0)
const appsLimit = ref(100)
const loadingApps = ref(false)
/** 每条提审单的驳回理由输入框（键 = `Id`）。⚠️ 不往行数据上塞字段 —— 那是后端原样。 */
const rejectReasons = reactive({})

/**
 * 「选模板」弹窗当前**为哪张提审单**而开（`null` = 没开）。
 *
 * ⚠️ 存**整行**而不是 `r.Id`：回填要往 `rejectReasons[行.Id]` 上写，而 `Id` 从行上取更直接，
 *    也避免「弹窗开着的时候列表刷新了、`Id` 对不上」这种中间态。
 * ⚠️ 只有一个弹窗实例（不是每行一个 `<TemplatePicker>`）—— 每行一个会让「提审单 100 条」
 *    的页面同时挂 100 个组件、每个 `onMounted` 都去 `POST /template/list`（100 次同样的请求）。
 */
const rejectTplFor = ref(null)
const openRejectTpl = (r) => { rejectTplFor.value = r }
/** 选中的模板正文 → 该行的驳回理由框。**填完仍可手改**（快照语义：模板只「提供思路」）。 */
function onPickRejectTpl(row) {
  if (rejectTplFor.value) rejectReasons[rejectTplFor.value.Id] = row.Body || ''
  rejectTplFor.value = null
}

const appsFiltered = computed(() => af.status !== '')
const appsTruncated = computed(() => appsTotal.value > apps.value.length)
/** 「待审」那个大数**只在看全量时**才准确 —— 筛了别的状态就不该拿它当待审数（会骗人）。 */
const pendingCount = computed(() =>
  af.status === 'pending' ? appsTotal.value : apps.value.filter((r) => r.Status === 'pending').length,
)

function statusLabel(s) {
  if (s === 'pending') return '待审'
  if (s === 'approved') return '已通过'
  if (s === 'rejected') return '已驳回'
  return dash(s)
}

/**
 * `statusLabel` 的图标搭档（2026-10-11 补图标那一笔）—— 与文字**并排**画在状态徽章里。
 * ⚠️ 三枚字形取自 `components/ui/glyphs.js` 的 `wait` / `ok` / `no`（**通用动作符**，
 *    不绑导航项 ⇒ 同字形在别处复用是预期的）。未知状态回落 `wait` —— 与 `statusLabel`
 *    的 `dash(s)` 回落**不同**：文案可以照实显示原值，图标没有「未知」这一枚，
 *    ⚠️ 但**不能**因此就不画（少一枚图标是最不容易被发现的回归）。
 * ⚠️ 与 `statusLabel` 分开写而不是合成一个返回 `{ label, glyph }`：那个函数的调用点
 *    另有 3 处只吃字符串（筛选条 / 计数），改签名会连带改它们。
 */
function statusGlyph(s) {
  if (s === 'approved') return 'ok'
  if (s === 'rejected') return 'no'
  return 'wait'
}

/**
 * 提审单挂的**商品券**一句话（C3 块 5 · A 组 · A3，2026-10-12）。
 *
 * 入参 = 后端 `GoodsAuditService.listForAdmin` 给带券单挂上的 `Coupon`（`coupon` 表整行）。
 * ⚠️ 取值域 `coupon.Type` ∈ `threshold` / `percent` / `none`，逐字写在后端 `CouponService`
 *    （与 `config/coupon-format.js` 的 `couponTypeLabel` **同一份口径的另一份** —— 这里不 import 那个模块：
 *    它服务用户端卡片，本页是管理端治理表，历史上一向各带一份，强行合并会牵动两边）。
 * ⚠️ 券名留空兜底成「（未命名）」：`coupon.Name` NOT NULL 但空串合法，直出会画成一行空白。
 * ⚠️ **未生效 / 已生效**读的是 `coupon.Status`（`stopped` / `active`）：商家提交时后端建的是一张
 *    `stopped` 的券，管理员在这一页点「通过」才转 `active` ⇒ 这里的状态就是**这张单批不批**的直接后果。
 */
function couponText(c) {
  if (!c) return '—'
  const TYPE = { threshold: '满减', percent: '折扣', none: '无门槛' }
  const face = c.Type === 'percent'
    ? (Number(c.FaceValue || 0) * 10).toFixed(1) + ' 折'
    : '¥' + Number(c.FaceValue || 0)
  const th = c.Type !== 'percent' && Number(c.Threshold || 0) > 0 ? ' · 满 ' + c.Threshold + ' 可用' : ''
  const st = c.Status === 'active' ? '已生效' : '未生效'
  return `${c.Name || '（未命名）'} · ${TYPE[c.Type] || dash(c.Type)} · ${face}${th} · 共 ${dash(c.TotalQty)} 张 · ${st}`
}

// ── 审核通过率 / 类目分布（可视化段 commit 3）──────────────────────────────────
/**
 * 本次 `apps` 的审核结果构成 + **通过率**。
 *
 * ⚠️ **通过率的分母是「已审毕」（`approved + rejected`），不含 `pending`** —— 待审的既没通过
 *    也没驳回，把它算进分母等于「把还没发生的事算成不通过」，通过率会被待审积压量拖低。
 *    故环图只画「已通过 / 已驳回」两片，`wait`（待审）在标题里单独报出来。
 * ⚠️ 分母为 0 时 `rate = null`（不是 0）—— 洞里显示 `'—'`，环图自己走空态浮层。
 *    写成 0 会得到一个「0% 通过率」的假数，那是本仓最忌讳的那种「看起来正常」。
 * ⚠️ 值为 0 的片不塞进数组（图例里挂 0 项是噪声）。
 */
const auditMix = computed(() => {
  let ok = 0
  let no = 0
  let wait = 0
  for (const r of apps.value) {
    if (r.Status === 'approved') ok += 1
    else if (r.Status === 'rejected') no += 1
    else if (r.Status === 'pending') wait += 1
  }
  const done = ok + no
  const data = []
  if (ok) data.push({ name: '已通过', value: ok })
  if (no) data.push({ name: '已驳回', value: no })
  return { data, ok, no, wait, done, rate: done ? Math.round((ok / done) * 100) : null }
})

/**
 * 本次 `apps` 按 `GoodType` × **提交月** 的条数 —— 分组柱（x 轴 = 类目，每类里并排各月）。
 *
 * ⚠️ **2026-10-11 复合图整改**：原先是「类目排行榜」单系列横向柱 —— 只回答「哪个类目多」，
 *    读不出**各类目的逐月走势**（上个月多的这个月还多吗）。改法：x 轴仍是类目（保住原榜的
 *    降序读法），把**月改成序列**（每月一条）。月数天然只有 2-3 个，正好落在「一张图最多
 *    2-3 个指标」里；反过来把 10 个类目塞进图例会得到一堵柱林，所以没那样做。
 * ⚠️ 月份取**最近 3 个**（升序画），更早的**不画**、条数报进标题的 `moreMonths`
 *    —— 与「降序取前 10 + `more`」同一套记账口径，**不偷偷截断**。
 * ⚠️ 只画前 10 个类目而把 `more` 报在标题里，**不是**偷偷截断：类目可能有几十个，全画出来是一堵墙。
 * ⚠️ `GoodType` 缺失的行归到 `dash()` 的 `'—'` 这一类（**不丢**）—— 丢掉的话各柱之和
 *    会小于本次条数，读图的人会以为少统计了。**没有可归月的 `SubmittedAt` 的行同样不丢**：
 *    它照样计入该类目的总条数（决定排名），只是所有月那一格都是 0、柱高为 0。
 * ⚠️ 分月沿 `toLocal(r.SubmittedAt)` 再取前 7 字，**不是**直接切 `SubmittedAt`：
 *    后者是 UTC 月，月初/月末那几个小时会被算到隔壁月（与 `AuditPage.vue` 的分桶同一条口径）。
 * ⚠️ 排序的次键用 `localeCompare` 固定下来：同票数的类目在两次渲染间不能换位。
 */
const catByMonth = computed(() => {
  const perCat = new Map() // 类目 → { total, byMonth: Map }
  const months = new Set()
  for (const r of apps.value) {
    const k = dash(r.GoodType)
    if (!perCat.has(k)) perCat.set(k, { total: 0, byMonth: new Map() })
    const e = perCat.get(k)
    e.total += 1
    const m = toLocal(r.SubmittedAt).slice(0, 7)
    if (/^\d{4}-\d{2}$/.test(m)) {
      months.add(m)
      e.byMonth.set(m, (e.byMonth.get(m) || 0) + 1)
    }
  }
  const allM = [...months].sort()
  const kept = allM.slice(-3)
  const all = [...perCat.entries()].sort(
    (a, b) => b[1].total - a[1].total || a[0].localeCompare(b[0])
  )
  const top = all.slice(0, 10)
  return {
    cats: top.map((e) => e[0]),
    series: kept.map((m) => ({ name: m, data: top.map((e) => e[1].byMonth.get(m) || 0) })),
    more: all.length - top.length,
    moreMonths: allM.length - kept.length,
  }
})

async function loadApps() {
  loadingApps.value = true
  try {
    const data = await catalogApplications({ status: af.status, limit: af.limit })
    if (!data || !data.success) {
      apps.value = []
      say('bad', (data && data.msg) || '提审单加载失败')
      return
    }
    apps.value = Array.isArray(data.list) ? data.list : []
    appsTotal.value = Number(data.total) || 0
    appsLimit.value = data.limit == null ? af.limit : data.limit   // 用**后端回显**的上限（它夹到 500）
  } catch (e) {
    apps.value = []
    appsTotal.value = 0
    say('bad', `提审单加载失败：${failOf(e)}`)
    console.error('catalog applications', e)
  } finally {
    loadingApps.value = false
  }
}

/** 通过。副作用是一串（CAS + 写 `goods` + 写 `productsales` + 回填 + 失效缓存，同一事务），故文案要说清。 */
async function approve(r) {
  busyKey.value = 'audit'
  try {
    const data = await catalogAudit({ id: r.Id, approve: true })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '操作失败')
      return
    }
    if (data.outcome === 'already-handled') {
      // ⚠️ `success:true` 但**什么都没发生** —— 别报「通过成功」。
      say('bad', `#${r.Id} 这条已经被处理过了 —— 列表已刷新。`)
    } else {
      say('ok', `#${r.Id} 已通过：商品已上架（「goods」表新增 1 行，Id=${dash(data.goodsId)}），买家即刻可见。`)
    }
    await loadApps()
  } catch (e) {
    say('bad', `通过失败：${failOf(e)}`)
    console.error('catalog approve', e)
  } finally {
    busyKey.value = null
  }
}

/** 驳回。理由**必填** —— 前端先拦一次，后端也拦（两处判据同一句话）。 */
async function reject(r) {
  const reason = rejectReasons[r.Id]
  if (!reason) {
    say('bad', `驳回 #${r.Id} 必须填写理由（该理由会展示给商家）。`)
    return
  }
  busyKey.value = 'audit'
  try {
    const data = await catalogAudit({ id: r.Id, approve: false, rejectReason: reason })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '操作失败')
      return
    }
    if (data.outcome === 'already-handled') {
      say('bad', `#${r.Id} 这条已经被处理过了 —— 列表已刷新。`)
    } else {
      // ⚠️ 说清「商品没上架」—— 驳回的语义是**不动 `goods` 表**，不是「上架后又撤了」。
      say('ok', `#${r.Id} 已驳回（商品未上架、「goods」表零新行）。理由已发给商家。`)
      delete rejectReasons[r.Id]
    }
    await loadApps()
  } catch (e) {
    say('bad', `驳回失败：${failOf(e)}`)
    console.error('catalog reject', e)
  } finally {
    busyKey.value = null
  }
}

// ── ② 类目树 ─────────────────────────────────────────────────────────────────
const cats = ref([])
const loadingCats = ref(false)
const cf = reactive({ GoodType: '', ParentId: 0, Sort: 0, GoodTypeNew: 1 })

/** 一级类目（`ParentId` 缺省 0）。⚠️ 与 `HomePage.vue` 同一判据：`(ParentId || 0) === 0`。 */
const roots = computed(() => cats.value.filter((t) => (t.ParentId || 0) === 0))

/** 某个一级类目的子类目（**含停用** —— 管理端要看见）。 */
function childrenOf(parentId) {
  return cats.value.filter((t) => (Number(t.ParentId) || 0) === Number(parentId))
}

async function loadCats() {
  loadingCats.value = true
  try {
    const data = await catalogCategories()
    if (!data || !data.success) {
      cats.value = []
      say('bad', (data && data.msg) || '类目树加载失败')
      return
    }
    cats.value = Array.isArray(data.list) ? data.list : []
  } catch (e) {
    cats.value = []
    say('bad', `类目树加载失败：${failOf(e)}`)
    console.error('catalog categories', e)
  } finally {
    loadingCats.value = false
  }
}

async function createCat() {
  if (!cf.GoodType) {
    say('bad', '类目名不能为空。')
    return
  }
  busyKey.value = 'catCreate'
  try {
    // ⚠️ 参数名 PascalCase（对着 `AdminCatalogController.categoryCreate` 核）。
    const data = await catalogCategoryCreate({
      GoodType: cf.GoodType,
      GoodTypeNew: cf.GoodTypeNew,
      ParentId: cf.ParentId,
      Sort: cf.Sort,
    })
    if (!data || !data.success) {
      // ⚠️ 同父同名被唯一键拦下时，后端回的是「该父级下已有同名类目」——
      //    **不同父级下同名是允许的**（唯一键换代的目的），别把这条读成「全库重名」。
      say('bad', (data && data.msg) || '新增失败')
      return
    }
    say('ok', `类目「${cf.GoodType}」已新增。`)
    cf.GoodType = ''
    await loadCats()
  } catch (e) {
    say('bad', `新增类目失败：${failOf(e)}`)
    console.error('catalog category create', e)
  } finally {
    busyKey.value = null
  }
}

async function saveCat(row) {
  busyKey.value = 'catSave'
  try {
    // 只发**要改**的三项；`id` 必带。⚠️ `Sort` 发的是数字（`0` 也是有效值，`q()` 不会吞掉）。
    const data = await catalogCategoryUpdate({
      id: row.Id,
      GoodType: ef.GoodType,
      Sort: ef.Sort,
      ParentId: ef.ParentId,
    })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '保存失败')
      return
    }
    say('ok', `类目 #${row.Id} 已更新。`)
    editing.value = null
    await loadCats()
  } catch (e) {
    say('bad', `保存类目失败：${failOf(e)}`)
    console.error('catalog category update', e)
  } finally {
    busyKey.value = null
  }
}

async function toggleState(row) {
  busyKey.value = 'catState'
  try {
    const data = await catalogCategoryUpdate({ id: row.Id, State: row.State === 0 ? 1 : 0 })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '操作失败')
      return
    }
    say('ok', `类目「${row.GoodType}」${row.State === 0 ? '已启用' : '已停用'}。`)
    await loadCats()
  } catch (e) {
    say('bad', `启停失败：${failOf(e)}`)
    console.error('catalog category state', e)
  } finally {
    busyKey.value = null
  }
}

async function removeCat(row) {
  busyKey.value = 'catDel'
  try {
    const data = await catalogCategoryDelete(row.Id)
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '删除失败')
      return
    }
    // ⚠️ 后端回的不是「删了」而是「停用了」（逻辑废弃），且会报**子类目数** —— 如实转述。
    const kids = Number(data.children) || 0
    say('ok', kids > 0
      ? `类目「${row.GoodType}」已停用（逻辑废弃，行还在）。⚠️ 它下面还有 ${kids} 个子类目**未被级联停用**。`
      : `类目「${row.GoodType}」已停用（逻辑废弃，行还在）。`)
    if (editing.value === `cat:${row.Id}`) editing.value = null
    await loadCats()
  } catch (e) {
    say('bad', `停用类目失败：${failOf(e)}`)
    console.error('catalog category delete', e)
  } finally {
    busyKey.value = null
  }
}

// ── ③ 模板库 ─────────────────────────────────────────────────────────────────
const tpls = ref([])
const loadingTpls = ref(false)
const tf = reactive({ Type: 'goods.detail', Name: '', Body: '', Kind: 'text' })

/**
 * `Enabled` 是 `tinyint(1)`，Connector/J 默认（`tinyInt1isBit=true`）把它映射成 **Boolean**
 * ⇒ JSON 里可能是 `true` / `false`，也可能是 `1` / `0`。判据写**宽容**一点，别赌某一种形态
 * （`AccountsPage.isBanned` 是同一个理由、同一种写法）。
 */
function isTplEnabled(t) {
  const v = t && t.Enabled
  return v === true || v === 1 || v === '1' || v === 'true'
}

function tplTypeLabel(t) {
  if (t === 'goods.detail') return '详情'
  if (t === 'goods.spec') return '规格'
  if (t === 'goods.afterSale') return '售后'
  if (t === 'goods.reject') return '驳回理由'
  return dash(t)
}

async function loadTpls() {
  loadingTpls.value = true
  try {
    // ⚠️ `TypePrefix: 'goods.'` ⇒ 本页只看**商品类**（`notification.*` 等住 `NotifyPage.vue`）。
    //    前缀**带那个点**：不带就会把 `goodsXxx` 这种同前缀异族也捞进来。
    // ⚠️ `includeDisabled: true` ⇒ 管理端要看见停用的（商家端弹窗只回启用中的）。
    // ⚠️ 两个都不给时的默认是**全表** —— 别把 `TypePrefix` 省掉，那会把通知模板也列在这一页。
    const data = await templateAdminList({ TypePrefix: 'goods.', includeDisabled: true })
    if (!data || !data.success) {
      tpls.value = []
      say('bad', (data && data.msg) || '模板加载失败')
      return
    }
    tpls.value = Array.isArray(data.list) ? data.list : []
  } catch (e) {
    tpls.value = []
    say('bad', `模板加载失败：${failOf(e)}`)
    console.error('catalog templates', e)
  } finally {
    loadingTpls.value = false
  }
}

async function createTpl() {
  if (!tf.Name) {
    say('bad', '模板名不能为空。')
    return
  }
  busyKey.value = 'tplCreate'
  try {
    // ⚠️ 参数名 PascalCase（对着 `AdminTemplateController.create` 核）：`Type` / `Kind` / `Name` / `Title` / `Body` / `Sort`。
    //    ⚠️ 旧端点的 `TemplateType` / `Content` **已不存在** —— 传旧名 = 后端收到 `null` = 「未知模板类型」。
    //    ⚠️ 商品类模板 `Title` **允许为空**（只有 `notification.*` 强制非空），故这里不发。
    //    `Kind` = 形态（text / structured），后端未知值一律回落 text（`TemplateTypes.normalizeKind`）。
    const data = await templateAdminCreate({
      Type: tf.Type,
      Kind: tf.Kind,
      Name: tf.Name,
      Body: tf.Body,
    })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '新增失败')
      return
    }
    say('ok', `模板「${tf.Name}」已新增。`)
    tf.Name = ''
    tf.Body = ''
    await loadTpls()
  } catch (e) {
    say('bad', `新增模板失败：${failOf(e)}`)
    console.error('catalog template create', e)
  } finally {
    busyKey.value = null
  }
}

async function saveTpl(row) {
  busyKey.value = 'tplSave'
  try {
    // ⚠️ `templateAdminUpdate` **没有 `Type` 参数**（type 落库即定、不许改）—— 别顺手带上：
    //    后端 `update` 端点根本不收它，带了也只是被忽略。
    const data = await templateAdminUpdate({ id: row.Id, Name: ef.Name, Body: ef.Body, Kind: ef.Kind })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '保存失败')
      return
    }
    say('ok', `模板 #${row.Id} 已更新（已上架商品的快照文本**不受影响**）。`)
    editing.value = null
    await loadTpls()
  } catch (e) {
    say('bad', `保存模板失败：${failOf(e)}`)
    console.error('catalog template update', e)
  } finally {
    busyKey.value = null
  }
}

async function toggleTpl(row) {
  busyKey.value = 'tplState'
  try {
    // ⚠️ **写**的这一侧 `Enabled` 是后端 `Integer` ⇒ 发 `1` / `0`
    //    （读回来那个 `Enabled` 是 `tinyint(1)` → Boolean，两个方向形态不同，别互相套用）。
    //    ⚠️ 别改传 `false`/`true`：`"false"` 绑不进 `Integer` ⇒ 400。
    //    ⚠️ 也别把 `0` 当「没传」：`q()` 只丢空串，`String(0) === '0'` 会照发。
    const on = isTplEnabled(row)
    const data = await templateAdminUpdate({ id: row.Id, Enabled: on ? 0 : 1 })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '操作失败')
      return
    }
    say('ok', `模板「${row.Name}」${on ? '已停用' : '已启用'}。`)
    await loadTpls()
  } catch (e) {
    say('bad', `启停失败：${failOf(e)}`)
    console.error('catalog template state', e)
  } finally {
    busyKey.value = null
  }
}

async function removeTpl(row) {
  busyKey.value = 'tplDel'
  try {
    const data = await templateAdminDelete(row.Id)
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '删除失败')
      return
    }
    say('ok', `模板「${row.Name}」已停用（逻辑废弃：「Enabled=0」，行还在）。`)
    if (editing.value === `tpl:${row.Id}`) editing.value = null
    await loadTpls()
  } catch (e) {
    say('bad', `停用模板失败：${failOf(e)}`)
    console.error('catalog template delete', e)
  } finally {
    busyKey.value = null
  }
}

// ── ④ 违规处置 ───────────────────────────────────────────────────────────────
const visibleGoods = ref([])
const loadingGoods = ref(false)
const vg = reactive({ keyword: '' })

/** 关键字**本地筛**（商品名 / 商家号 / 商家名 / 编码）—— 后端那条端点不收参数，不为此新开参数面。 */
const goodsFiltered = computed(() => {
  const k = vg.keyword.trim().toLowerCase()
  if (!k) return visibleGoods.value
  return visibleGoods.value.filter((g) =>
    [g.GoodName, g.MerchantId, g.UserName, g.ProductCode]
      .some((v) => String(v == null ? '' : v).toLowerCase().includes(k)),
  )
})

async function loadGoods() {
  loadingGoods.value = true
  try {
    const data = await catalogVisibleGoods()
    if (!data || !data.success) {
      visibleGoods.value = []
      say('bad', (data && data.msg) || '可见商品加载失败')
      return
    }
    visibleGoods.value = Array.isArray(data.list) ? data.list : []
  } catch (e) {
    visibleGoods.value = []
    say('bad', `可见商品加载失败：${failOf(e)}`)
    console.error('catalog visible goods', e)
  } finally {
    loadingGoods.value = false
  }
}

/** 三元组入参 —— 两条处置端点共用（谓词逐字相同，见 `GoodsViolationService`）。 */
const tripleOf = (g) => ({ MerchantId: g.MerchantId, GoodName: g.GoodName, ProductCode: g.ProductCode })

async function delist(g) {
  busyKey.value = 'vioDelist'
  try {
    const data = await catalogViolationDelist(tripleOf(g))
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '下架失败')
      return
    }
    say('ok', `「${g.GoodName}」已下架（「ShelfState=0」）—— 买家侧即刻查不到。商家已收到「违规下架」通知。`)
    await loadGoods()
  } catch (e) {
    say('bad', `下架失败：${failOf(e)}`)
    console.error('catalog violation delist', e)
  } finally {
    busyKey.value = null
  }
}

async function removeGoods(g) {
  busyKey.value = 'vioDel'
  try {
    const data = await catalogViolationDelete(tripleOf(g))
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '删除失败')
      return
    }
    say('ok', `「${g.GoodName}」已被平台删除（「DeletedAt=now」，行还在）—— 买家侧即刻查不到。`)
    await loadGoods()
  } catch (e) {
    say('bad', `删除失败：${failOf(e)}`)
    console.error('catalog violation delete', e)
  } finally {
    busyKey.value = null
  }
}

// ── 挂载 ─────────────────────────────────────────────────────────────────────
// ⚠️ 四块**并发拉**（互不依赖）—— 一个失败不影响其余三个（各自的 catch 各报各的）。
//    状态条上那三个大数分别来自 apps / cats / tpls ⇒ 首屏就该是准的，不该等切 tab 才填。
onMounted(() => {
  loadApps()
  loadCats()
  loadTpls()
  loadGoods()
})
</script>

<style scoped>
.c-cat {
  max-width: 1120px;
}

/* ── ① 状态条（与 `AccountsPage.vue` / `AuditPage.vue` 同款，不另立一套）──────── */
.c-status {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s5);
  margin-bottom: var(--s5);
}
.c-stat {
  display: flex;
  flex-direction: column;
  min-width: 96px;
}
.c-stat b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semi);
  line-height: 1.1;
  color: var(--ink);
}
.c-stat span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.c-tz {
  margin-left: auto;
  align-self: center;
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.c-chip-utc {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--info);
  background: var(--info-ground);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.c-tz-off {
  font-family: var(--font-mono);
  color: var(--brand-ink);
}

/* ── 反馈条 ──────────────────────────────────────────────────────────────── */
.c-flash {
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
  white-space: pre-wrap;
}
.c-flash.ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.c-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}

/* ── 页内 tab（**逐字**照抄 `AccountsPage.vue` —— 房规：不许各造各的）────────────
   ⚠️ 用的是**下划线**（`border-bottom` 当前项变色），不是 `tokens.css` 房规里禁的
   「选中态用左侧强调条」。 */
.c-tabs {
  display: flex;
  gap: var(--s2);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--s5);
}
.c-tab {
  appearance: none;
  border: 0;
  background: none;
  cursor: pointer;
  padding: var(--s3) var(--s4);
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.c-tab:hover {
  color: var(--ink);
}
.c-tab.on {
  color: var(--ink);
  font-weight: var(--fw-semi);
  border-bottom-color: var(--brand-ink);
}
.c-tabn {
  margin-left: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.c-tab.on .c-tabn {
  color: var(--brand-ink);
}

/* ── 分段标题 ─────────────────────────────────────────────────────────────── */
.c-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin: var(--s7) 0 var(--s4);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.c-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.c-cat > .c-h2:first-of-type {
  margin-top: 0;
}

/* ── 筛选条（照 `AuditPage.vue` / `AccountsPage.vue`）────────────────────── */
.c-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  padding: var(--s4) var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
}
.c-f {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.c-f-narrow {
  max-width: 88px;
}
.c-f-wide {
  flex: 1 1 260px;
  min-width: 200px;
}
.c-f select,
.c-f input,
.c-f textarea {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.c-f textarea {
  resize: vertical;
  line-height: 1.6;
}
.c-f-actions {
  display: flex;
  gap: var(--s2);
  margin-left: auto;
}
/* 筛选条之外单独一行的「内容」域（新增模板用）—— 与筛选条同底色、但**不是**筛选。 */
.c-inline {
  display: flex;
  margin-top: var(--s3);
  padding: var(--s4) var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
}
.c-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  cursor: pointer;
  white-space: nowrap;
}
.c-btn:hover {
  color: var(--ink);
  background: var(--surface-3);
}
.c-btn-on {
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
/* 危险动作（驳回 / 删除 / 违规处置）—— 与「通过 / 启用」在颜色上立刻分得开。 */
.c-btn-danger {
  color: var(--danger);
  background: var(--danger-ground);
  border-color: var(--danger);
}
.c-btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.c-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.7;
  margin: var(--s2) 0 var(--s5);
}
.c-hint code,
.c-why code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.c-note {
  font-size: var(--fs-xs);
  color: var(--warning);
  background: var(--warning-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-top: var(--s3);
}
.c-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6);
  text-align: center;
}

/* ── 卡片式列表 ──────────────────────────────────────────────────────────── */
.c-stream {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  margin-top: var(--s3);
}
.c-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
}
/* 已处理的行（`done`）/ 已停用的行（`off`）—— 都是**状态**，都用同一支灰底。
   ⚠️ 不用红：红只留给「删除 / 驳回」这类不可逆动作（见文件头那条）。 */
.c-card.done,
.c-card.off {
  background: var(--surface-2);
}
.c-card-l1 {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
}
.c-strong {
  font-size: var(--fs-md);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.c-grow {
  flex: 1;
}
.c-tag {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border-radius: var(--r1);
  padding: 2px 6px;
}
.c-badge {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.c-badge.pending,
.c-badge.warn {
  color: var(--warning-deep);
  background: var(--warning-ground);
  border-color: transparent;
}
.c-badge.approved {
  color: var(--success-deep);
  background: var(--success-ground);
  border-color: transparent;
}
.c-badge.rejected {
  color: var(--danger);
  background: var(--danger-ground);
  border-color: transparent;
}
.c-dim {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.c-mono {
  font-family: var(--font-mono);
}

/* 字段清单：`dt` 标签 + `dd` 值，两列流式排布（宽窄都能读，不像宽表那样在窄档塌掉）。 */
.c-kv {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--s2) var(--s5);
  margin: var(--s3) 0 0;
}
.c-kv > div {
  display: flex;
  gap: var(--s2);
  min-width: 0;
  font-size: var(--fs-xs);
}
.c-kv dt {
  flex: none;
  color: var(--ink-3);
  min-width: 64px;
}
.c-kv dd {
  margin: 0;
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.c-why {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border-left: 2px solid var(--line-2);
  border-radius: 0 var(--r1) var(--r1) 0;
  padding: var(--s2) var(--s3);
  margin: var(--s3) 0 0;
}
.c-acts {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  margin-top: var(--s3);
}
.c-inp {
  flex: 1 1 220px;
  min-width: 160px;
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}

/* ── 类目树 ──────────────────────────────────────────────────────────────── */
/* 二级子类目的容器：**缩进 + 左边一条细线**，一眼看出从属关系。 */
.c-kids {
  list-style: none;
  margin: var(--s3) 0 0;
  padding-left: var(--s5);
  border-left: 2px solid var(--line-2);
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.c-kid {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  padding: var(--s2) var(--s3);
  border-radius: var(--r2);
  background: var(--surface-2);
}
.c-kid.off {
  opacity: 0.7;
}

/* 行内编辑区（三类行共用一套）—— 与卡片同底色、上方一条分隔线。 */
.c-edit {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s3);
  margin-top: var(--s3);
  padding-top: var(--s3);
  border-top: 1px dashed var(--line-2);
}

/* 模板正文预览 —— 长文本换行（不用 `pre`：模板可能是富文本片段，按纯文本读最不意外）。 */
.c-body {
  margin: var(--s3) 0 0;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
