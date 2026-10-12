<template>
  <section class="a-mk">
    <!-- ══ 页内 tab（C3 块 5〈折扣〉长出来的，2026-10-12）══════════════════════════
         改前这里**刻意没有 tab 栏**：本页当时只有「券」一个子域，摆一个只有一项的 tab 栏
         等于承诺三个不存在的页面（同 `admin-nav.js` 不给占位页挂图标的那条口径）。
         现在折扣落地 ⇒ 「多块才上 tab」的条件满足了，才长出这一条。
         ⚠️ **只列已落地的三枚**：秒杀仍是计划中的子域，**不预摆灰 tab**（同一条口径）。 -->
    <nav class="a-tabs" role="tablist">
      <button
        v-for="t in TABS" :key="t.k" type="button" role="tab" class="a-tab"
        :class="{ 'a-tab-on': tab === t.k }"
        :aria-selected="tab === t.k ? 'true' : 'false'"
        @click="tab = t.k"
      >{{ t.label }}</button>
    </nav>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <template v-if="tab === 'coupon'">
    <!-- ── ① 状态条（与 `StatsPage.vue` / `OrderPage.vue` / `AccountsPage.vue` 同款，不另立一套）──
         ⚠️ 「券总数」是**全表** `COUNT(*)`（`/list` 的 `total`），不是本次筛选数 —— 后端刻意分开
         回报（同 `AdminOrderService.list` 的口径），本页就把它摆在台面上，免得读成筛选数。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ total }}</b>
        <span>券总数（全表，非本次筛选）</span>
      </div>
      <div class="a-stat">
        <b>{{ rows.length }}</b>
        <span>本次显示{{ truncated ? '（已达上限）' : '' }}</span>
      </div>
      <div class="a-stat">
        <b>{{ statsFailed ? '—' : stats.totals.claimed }}</b>
        <span>累计领取</span>
      </div>
      <div class="a-stat">
        <b>{{ statsFailed ? '—' : stats.totals.used }}</b>
        <span>累计核销</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">UTC</span>
        <span>有效期存库内时钟 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <!-- ══ ② 建券 ═════════════════════════════════════════════════════════════ -->
    <h2 class="a-h2">
      建券
      <span class="a-h2n">类型 / 面值 / 门槛 / 限量 / 适用范围 / 有效期</span>
    </h2>
    <form class="a-filters" @submit.prevent="doCreate">
      <label class="a-f a-f-wide">
        <span>券名 *</span>
        <input v-model.trim="c.Name" type="text" placeholder="如：双十一满减券" />
      </label>
      <label class="a-f">
        <span>类型 *</span>
        <select v-model="c.Type" @change="onTypeChange">
          <option v-for="t in TYPES" :key="t.code" :value="t.code">{{ t.label }}</option>
        </select>
      </label>
      <label class="a-f a-f-narrow">
        <span>{{ faceHint }}</span>
        <input v-model.trim="c.FaceValue" type="text" :placeholder="facePlaceholder" />
      </label>
      <label class="a-f a-f-narrow">
        <span>门槛（元）{{ c.Type === 'threshold' ? ' *' : '' }}</span>
        <input v-model.trim="c.Threshold" type="text" :disabled="c.Type === 'none'" placeholder="0" />
      </label>
      <label class="a-f a-f-narrow">
        <span>发放总量</span>
        <input v-model.trim="c.TotalQty" type="text" placeholder="0 = 不限量" />
      </label>
      <label class="a-f">
        <span>适用范围</span>
        <select v-model="c.Scope">
          <option value="all">全场</option>
          <option value="merchant">部分商家（限商品种类）</option>
        </select>
      </label>
      <!-- 联动：只有「部分商家」才显示类目 —— 「全场」下整块不出现（原来是 `:disabled` 灰着还占位）。
           ⚠️ 「归属商家」字段已按裁决**移除**：它在本仓是正交的元数据（`MerchantAccount`，「空=平台券」），
              原本挂在用户设想的「指定商家」那档上，而那一档在本仓不存在（后端只收 all/merchant）⇒ 无归属。
              列与后端参数都还在，只是建券表单里不再暴露（详见本节注释）。 -->
      <div v-if="c.Scope === 'merchant'" class="a-f a-f-wide a-cat">
        <span>限商品种类 *</span>
        <div class="a-cat-ctl">
          <div v-if="catOpen" class="a-cat-scrim" @click="catOpen = false"></div>
          <button
            type="button"
            class="a-cat-toggle"
            :aria-expanded="catOpen ? 'true' : 'false'"
            @click="catOpen = !catOpen"
          >
            <span v-if="!catPicked.length" class="a-cat-ph">选择类目…</span>
            <span v-else class="a-cat-val">{{ catPicked.join('，') }}</span>
            <span class="a-cat-caret">▾</span>
          </button>
          <button v-if="catPicked.length" type="button" class="a-cat-clear" @click="clearCats">
            清空
          </button>
          <div v-if="catOpen" class="a-cat-menu">
            <label v-for="g in categories" :key="g.Id" class="a-cat-opt">
              <input
                type="checkbox"
                :checked="catPicked.includes(g.GoodType)"
                @change="toggleCat(g.GoodType)"
              />
              <span>{{ g.GoodType }}</span>
            </label>
            <p v-if="!categories.length" class="a-cat-none">暂无类目</p>
          </div>
        </div>
      </div>
      <label class="a-f a-f-wide">
        <span>有效期起（本地时区，可空）</span>
        <input v-model="c.StartAt" type="datetime-local" />
      </label>
      <label class="a-f a-f-wide">
        <span>有效期止（本地时区，可空）</span>
        <input v-model="c.EndAt" type="datetime-local" />
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="creating">
          <IconGlyph name="ok" />{{ creating ? '提交中…' : '建券' }}
        </button>
        <button type="button" class="a-btn" :disabled="creating" @click="resetCreate">清空</button>
      </div>
    </form>
    <p class="a-hint">
      · <b>折扣券的「面值」是折扣率</b>（<code>0.85</code> = 8.5 折，必须小于 1）；满减 / 无门槛券的面值才是<b>减免金额</b>。发满减券时<b>必须</b>给门槛。
      · <b>总量 <code>0</code> = 不限量</b>；范围选「部分商家」<b>必须</b>同时给类目名。
      · 有效期按<b>你浏览器的本地时区</b>挑（<code>{{ tzLabel }}</code>），提交前换算成库内时钟（UTC）——
        列表与明细里显示的时间都是<b>换算回本地</b>的，跟这里填的是同一个口径。
      · <b>「已到有效期起」才算能领</b>：只填「止」不填「起」立即生效；填了「起」而那一刻还没到，领取会被拒
        （话术是<b>「该券还未开始」</b>，不是「已过期」）。
      · 留空的有效期 = 不设那一条边界。
    </p>

    <!-- ══ ③ 券列表 ═══════════════════════════════════════════════════════════ -->
    <h2 class="a-h2">
      券列表
      <span class="a-h2n">状态未知值会被拒（不静默当成「不筛」）</span>
    </h2>
    <form class="a-filters" @submit.prevent="loadList">
      <label class="a-f">
        <span>状态</span>
        <select v-model="f.Status">
          <option value="">全部</option>
          <option value="active">启用中</option>
          <option value="stopped">已停用</option>
        </select>
      </label>
      <label class="a-f a-f-wide">
        <span>券名关键字</span>
        <input v-model.trim="f.keyword" type="text" placeholder="模糊匹配 Name" />
      </label>
      <label class="a-f a-f-narrow">
        <span>上限</span>
        <input v-model.number="f.limit" type="number" min="1" max="500" />
      </label>
      <div class="a-f-actions">
        <button type="submit" class="a-btn a-btn-on" :disabled="loading"><IconGlyph name="ok" />查询</button>
        <button type="button" class="a-btn" :disabled="loading" @click="resetFilters">清空</button>
      </div>
    </form>

    <p v-if="truncated" class="a-note">
      本次结果已达取回上限：仅取回 {{ rows.length }} 条（按 Id 倒序），后面可能还有。
    </p>
    <p v-if="!rows.length" class="a-empty">{{ loading ? '加载中…' : '没有符合条件的券。' }}</p>
    <table v-else class="a-tbl">
      <thead>
        <tr>
          <th>券</th>
          <th>类型 / 面值</th>
          <th>门槛</th>
          <th>范围</th>
          <th class="num">已领 / 总量</th>
          <th>有效期</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <!-- ⚠️ 明细**展开在被点的那一行下方**（用户 2026-10-11 手验要求：原来是页底单独一块，
             占整页高度、且离被点的行很远）。用 `<template v-for>` 包一对兄弟 `<tr>`：
             一对是券行本身，另一对（可选）是它的明细 —— 表里加行不需要动列结构。 -->
        <template v-for="r in rows" :key="r.CouponId">
        <tr>
          <td>
            <div class="a-ink">{{ r.Name }}</div>
            <div class="a-mono a-mini">{{ r.CouponId }}</div>
          </td>
          <td>{{ typeLabel(r.Type) }}<span class="a-mono a-mini"> · {{ faceLabel(r) }}</span></td>
          <td class="num">{{ r.Type === 'none' ? '—' : fmtMoney(r.Threshold) }}</td>
          <td>
            {{ r.Scope === 'merchant' ? '部分商家' : '全场' }}
            <div v-if="r.CategoryMatch" class="a-mono a-mini">{{ r.CategoryMatch }}</div>
            <div v-if="r.MerchantAccount" class="a-mono a-mini">商家 {{ r.MerchantAccount }}</div>
          </td>
          <td class="num">{{ fmtInt(r.ClaimedQty) }} / {{ Number(r.TotalQty) === 0 ? '不限量' : fmtInt(r.TotalQty) }}</td>
          <td class="a-mono a-mini">
            {{ toLocal(r.StartAt) }}<br />{{ toLocal(r.EndAt) }}
          </td>
          <td>
            <span class="a-chip" :class="r.Status === 'active' ? 'ok' : 'off'">
              {{ r.Status === 'active' ? '启用中' : '已停用' }}
            </span>
          </td>
          <td class="a-ops">
            <button
              v-if="r.Status === 'active'"
              type="button"
              class="a-btn a-btn-sm"
              :disabled="busy === r.CouponId"
              @click="doStop(r.CouponId)"
            ><IconGlyph name="no" />停用</button>
            <button
              v-else
              type="button"
              class="a-btn a-btn-sm"
              :disabled="busy === r.CouponId"
              @click="doActivate(r.CouponId)"
            ><IconGlyph name="ok" />启用</button>
            <button
              type="button"
              class="a-btn a-btn-sm"
              :disabled="busy === r.CouponId"
              @click="toggleGrants(r.CouponId)"
            ><IconGlyph name="logs" />{{ picked && picked.CouponId === r.CouponId ? '收起' : '明细' }}</button>
          </td>
        </tr>

        <!-- ── 该行的明细（展开态）—— 原页底那一块整块搬进来，内容一字未改 ── -->
        <tr v-if="picked && picked.CouponId === r.CouponId" class="a-expand">
          <td colspan="8">
            <div class="a-expand-h">
              <b>领取明细 —— {{ picked.Name }}</b>
              <span class="a-mono a-mini">{{ picked.CouponId }} · 按领取时间倒序</span>
            </div>

            <form class="a-filters a-filters-in" @submit.prevent="doGrant">
              <label class="a-f a-f-wide">
                <span>定向发放给（客户业务号）</span>
                <input v-model.trim="grantTo" type="text" placeholder="user.customerid" />
              </label>
              <div class="a-f-actions">
                <button type="submit" class="a-btn a-btn-on" :disabled="granting">
                  <IconGlyph name="msg" />{{ granting ? '发放中…' : '发放' }}
                </button>
                <button type="button" class="a-btn" @click="closeGrants">收起</button>
              </div>
            </form>

            <p v-if="!grantRows.length" class="a-empty">
              {{ grantLoading ? '加载中…' : '这张券还没有人领取。' }}
            </p>
            <table v-else class="a-tbl a-tbl-in">
              <thead>
                <tr>
                  <th>领取人</th>
                  <th>来源</th>
                  <th>状态</th>
                  <th>领取时间</th>
                  <th>核销时间</th>
                  <th>订单</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="g in grantRows" :key="g.GrantId">
                  <td class="a-mono">{{ g.ToAccount }}</td>
                  <td>{{ g.Source === 'grant' ? '定向发放' : '自助领取' }}</td>
                  <td>
                    <span class="a-chip" :class="g.Status === 'used' ? 'ok' : 'wait'">
                      {{ g.Status === 'used' ? '已核销' : '未使用' }}
                    </span>
                  </td>
                  <td class="a-mono a-mini">{{ toLocal(g.GrantedAt) }}</td>
                  <td class="a-mono a-mini">{{ toLocal(g.UsedAt) }}</td>
                  <td class="a-mono a-mini">{{ g.OrderId || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </td>
        </tr>
        </template>
      </tbody>
    </table>

    <!-- ══ ④ 图表（三件齐里的「图」）═══════════════════════════════════════════ -->
    <h2 class="a-h2">
      <IconGlyph name="metrics" />领取 / 核销趋势
      <span class="a-h2n">近 {{ TREND_DAYS }} 天 · 两条线已按日期并集对齐</span>
    </h2>
    <div class="a-card">
      <LineChart
        :categories="trendDays"
        :series="[
          { name: '领取', data: trendClaimed, area: true },
          { name: '核销', data: trendUsed },
        ]"
        :height="'300px'"
        y-formatter="{value} 张"
        :loading="statsLoading"
        :error="statsFailed ? statsMsg : ''"
        empty-text="近两周没有任何领取记录"
        @retry="loadStats"
      />
    </div>

    <h2 class="a-h2">
      <IconGlyph name="marketing" />券类型占比
      <span class="a-h2n">按券定义数（不是领取数）</span>
    </h2>
    <div class="a-card">
      <PieChart
        :data="typePie"
        :height="'280px'"
        :loading="statsLoading"
        :error="statsFailed ? statsMsg : ''"
        empty-text="还没有建过券"
        @retry="loadStats"
      />
    </div>
    </template>

    <!-- ══════════════════════════════════════════════════════════════════════
         ══ 折扣（C3 块 5〈折扣〉）═════════════════════════════════════════════
         两件事，**存的是同一张表 `template`**（⇒ 零新端点，走现成的 `/admin/template/*`）：
           · 类目折扣上下限 → `Type='discount.limit'`，`Name`=类目名，`Body`={"min":6.0,"max":9.5}
           · 折扣模板（填充源）→ `Type='discount.rule'`，`Body`=`6.5折|说明`
         ⚠️ 上下限那张表**按类目列全**（`/goods/goodstype` 有几个类目就有几行），
            没配的类目显示「未设置」而不是 0 —— 「没配」与「配成 0」是两件事（同后端口径）。 -->
    <template v-else-if="tab === 'discount'">
      <h2 class="a-h2">
        <IconGlyph name="marketing" />类目折扣上下限
        <span class="a-h2n">商家填划线原价时按商品类目校验；留空 = 该类目不限</span>
      </h2>
      <div class="a-card">
        <p v-if="limitLoading" class="a-note">加载中…</p>
        <table v-else class="a-tbl">
          <thead>
            <tr><th>类目</th><th>折扣下限（折）</th><th>折扣上限（折）</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="row in limitRows" :key="row.name">
              <td>{{ row.name }}</td>
              <td><input v-model.trim="row.min" class="a-inp-s" type="text" placeholder="不限"></td>
              <td><input v-model.trim="row.max" class="a-inp-s" type="text" placeholder="9.5"></td>
              <td>
                <span class="a-chip" :class="row.Id ? 'ok' : 'off'">
                  {{ row.Id ? '已设置' : '未设置' }}
                </span>
              </td>
              <td><button type="button" class="a-btn-s" @click="saveLimit(row)">保存</button></td>
            </tr>
            <tr v-if="!limitRows.length"><td colspan="5" class="a-empty">还没有类目</td></tr>
          </tbody>
        </table>
      </div>

      <h2 class="a-h2">
        <IconGlyph name="marketing" />折扣模板
        <span class="a-h2n">商家上架时「选折扣模板」的来源；正文 = 建议折数|说明</span>
      </h2>
      <form class="a-filters" @submit.prevent="doCreateRule">
        <label class="a-f a-f-wide">
          <span>模板名 *</span>
          <input v-model.trim="r.Name" type="text" placeholder="如：夏促常规力度">
        </label>
        <label class="a-f a-f-wide">
          <span>正文 *</span>
          <input v-model.trim="r.Body" type="text" placeholder="6.5折|全场通用，抹零处理">
        </label>
        <button type="submit" class="a-btn">新增模板</button>
      </form>
      <div class="a-card">
        <table v-if="ruleRows.length" class="a-tbl">
          <thead><tr><th>模板名</th><th>正文</th><th>状态</th><th></th></tr></thead>
          <tbody>
            <tr v-for="row in ruleRows" :key="row.Id">
              <td>{{ row.Name }}</td>
              <td class="a-mono">{{ row.Body }}</td>
              <td><span class="a-chip" :class="isOn(row.Enabled) ? 'ok' : 'off'">{{ isOn(row.Enabled) ? '启用' : '停用' }}</span></td>
              <td>
                <button v-if="isOn(row.Enabled)" type="button" class="a-btn-s" @click="stopRule(row)">停用</button>
                <button v-else type="button" class="a-btn-s" @click="activateRule(row)">启用</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="a-empty">还没有折扣模板 —— 建一条，商家上架时就能一键折算划线原价</p>
      </div>
    </template>

    <!-- ══ ③ 国补（C3 块 5 · commit 3）════════════════════════════════════════════
         双向制：**平台建规则 → 提名商品 → 商家在消息中心同意 / 拒绝**。
         ⇒ 本页只管「前两段」，第三段在 `views/NotifyInbox.vue`（商家的收件箱里就地同意 / 拒绝）。
         ⚠️ 提名之后**用户端什么都看不到**，直到商家点头（`subsidy_goods.Status='approved'`）——
             这一条必须**写在页面上**（下面「提名商品」那栏的 `a-h2n` 副标题里），
             否则「提了名怎么没生效」会变成反复问的问题。 -->
    <template v-else>
      <h2 class="a-h2">
        <IconGlyph name="marketing" />国补规则
        <span class="a-h2n">按类目配补贴比例与单笔上限；提名商品后需商家在消息中心确认</span>
      </h2>

      <form class="a-filters" @submit.prevent="doCreateRule2">
        <label class="a-f a-f-wide">
          <span>规则名 *</span>
          <input v-model.trim="sb.Name" type="text" placeholder="如：家电以旧换新">
        </label>
        <label class="a-f a-f-wide">
          <span>适用类目</span>
          <input v-model.trim="sb.CategoryMatch" type="text" placeholder="电子用品,生活用品（空 = 不限）">
        </label>
        <label class="a-f">
          <span>补贴比例(%) *</span>
          <input v-model.trim="sb.Ratio" type="number" min="0" max="100" step="0.1" placeholder="15">
        </label>
        <label class="a-f">
          <span>单笔上限(元)</span>
          <input v-model.trim="sb.Cap" type="number" min="0" step="0.01" placeholder="0 = 不封顶">
        </label>
        <label class="a-f">
          <span>生效起</span>
          <input v-model="sb.StartAt" type="datetime-local">
        </label>
        <label class="a-f">
          <span>生效止</span>
          <input v-model="sb.EndAt" type="datetime-local">
        </label>
        <button type="submit" class="a-btn" :disabled="sbBusy">新建规则</button>
      </form>

      <div class="a-card">
        <table v-if="sbRules.length" class="a-tbl">
          <thead>
            <tr>
              <th>规则名</th><th>适用类目</th><th>补贴比例</th><th>单笔上限</th>
              <th>有效期</th><th>状态</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in sbRules" :key="row.RuleId">
              <td>{{ row.Name }}</td>
              <td>{{ row.CategoryMatch || '不限' }}</td>
              <td class="a-mono">{{ fmtRatio(row.Ratio) }}</td>
              <td class="a-mono">{{ fmtCap(row.Cap) }}</td>
              <td class="a-mono">{{ fmtRange(row.StartAt, row.EndAt) }}</td>
              <td><span class="a-chip" :class="row.Status === 'active' ? 'ok' : 'off'">
                {{ row.Status === 'active' ? '启用' : '停用' }}</span></td>
              <td>
                <button v-if="row.Status === 'active'" type="button" class="a-btn-s"
                  :disabled="sbBusy" @click="doStopRule(row)">停用</button>
                <button v-else type="button" class="a-btn-s"
                  :disabled="sbBusy" @click="doActivateRule(row)">启用</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="a-empty">还没有国补规则 —— 先建一条，再提名商品</p>
      </div>

      <h2 class="a-h2">
        <IconGlyph name="marketing" />提名商品
        <span class="a-h2n">提名只写一条待确认记录并给商家发通知；商家同意后才进用户端的「国家补贴」</span>
      </h2>
      <form class="a-filters" @submit.prevent="doNominate">
        <label class="a-f a-f-wide">
          <span>规则 *</span>
          <select v-model="nom.RuleId">
            <option value="">请选择</option>
            <option v-for="row in activeRules" :key="row.RuleId" :value="row.RuleId">
              {{ row.Name }}（{{ fmtRatio(row.Ratio) }}）
            </option>
          </select>
        </label>
        <label class="a-f a-f-wide">
          <span>商品 *</span>
          <select v-model="nom.GoodsId">
            <option value="">请选择</option>
            <option v-for="g in pickGoods" :key="g.Id" :value="g.Id">
              {{ g.GoodName }}（¥{{ fmtMoney(g.Price) }} · {{ g.UserName || '商家' }}）
            </option>
          </select>
        </label>
        <button type="submit" class="a-btn" :disabled="sbBusy">提名</button>
      </form>

      <div class="a-card">
        <table v-if="sbNoms.length" class="a-tbl">
          <thead>
            <tr><th>商品</th><th>商家</th><th>规则</th><th>状态</th><th>商家回复</th><th>提名时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in sbNoms" :key="row.Id">
              <td>{{ row.GoodName || ('#' + row.GoodsId) }}</td>
              <td class="a-mono">{{ row.MerchantId }}</td>
              <td>{{ row.RuleName }}</td>
              <td><span class="a-chip" :class="nomChip(row.Status)">{{ nomLabel(row.Status) }}</span></td>
              <td class="a-note">{{ row.Reason || '—' }}</td>
              <td class="a-mono">{{ toLocal(row.created_at) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="a-empty">还没有提名记录</p>
      </div>
    </template>
  </section>
</template>

<!--
  管理端「营销运营 · 券」页（C3 块 5 · commit 1，2026-10-11）。

  ⚠️ **本页当前只有「券」一个子域 ⇒ 没有页内 tab**（`c3-modules.md` §五 的「多块才上 tab」）。
     commit 2/3/4 落折扣 / 国补 / 秒杀时，本页才该长出 tab 栏 —— 现在先摆一个只有一项的
     tab 栏，等于承诺了三个不存在的页面（同 `admin-nav.js` 对「给占位页挂图标」的那条口径）。

  ⚠️ **核销不在这里**：券的 `unused → used` 发生在**结算**（`orderAdd` 带 `couponId`），
     本页只「读」核销结果（明细里的状态与订单号），块 5 commit 5 才接上结算侧。

  ⚠️ **时间列是库内无时区文本**（`'YYYY-MM-DD HH:MM:SS'`，后端 `DATE_FORMAT` 出来的），**库时钟 = UTC**。
     ⇒ 本页照 `AuditPage.vue` 的**既定口径**（用户 2026-10-08 裁决「库时钟 UTC 是对的，根因是前端没转时区」）：
       · **显示** 库内文本 → 当 UTC 解析 → 按**浏览器时区**渲染（`toLocal`）；
       · **输入** 用户按**本地时区**在 `datetime-local` 里挑 → 反算成 UTC 文本再发（`toSqlTime`）。
     ⚠️ 2026-10-11 手验修的就是这一处：本页**曾经**把表单里的墙钟原样发出去（既不转、也不说明），
        于是「22:01 起」在库内被当成 **UTC 22:01** = 本地次日 06:01 ⇒ 券在用户眼里「刚建好就领不了」，
        而后端回的话术又是「已过期或已领完」（真因是**还没开始**）—— 两个错叠在一起，很难查。
        同一根因的第二个落点在后端 `CouponService.isExpired`（拿 JVM 时钟判过期），已一并收掉。
-->
<script setup>
/* ⚠️ 本仓 `eslint-plugin-vue` 是 8.7.1，`vue3-essential` 不带宏环境 ⇒ 只要用了 `defineProps` /
   `defineEmits` 就必须写这条（理由见 `components/charts/BaseChart.vue` 同款注释）。本页没有宏。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  couponList, couponCreate, couponStop, couponActivate,
  couponGrants, couponGrant, couponStats,
  subsidyRuleList, subsidyRuleCreate, subsidyRuleStop, subsidyRuleActivate,
  subsidyNominate, subsidyNominations,
} from '../../api/marketing'
import { catalogCategories, catalogVisibleGoods } from '../../api/admin'
import {
  templateList, templateAdminCreate, templateAdminUpdate,
} from '../../api/template'
import LineChart from '../../components/charts/LineChart.vue'
import PieChart from '../../components/charts/PieChart.vue'
import IconGlyph from '../../components/ui/IconGlyph.vue'

/** 后端 `CouponService.TREND_DAYS` 的**回显**（图标题上写死一个数会与后端走岔）。 */
const TREND_DAYS = 14

// ── 页内 tab（C3 块 5〈折扣〉，2026-10-12）────────────────────────────────────
// ⚠️ 只列**已落地**的：秒杀还没做 ⇒ 不预摆灰 tab（见模板里那条注释）。
const TABS = [
  { k: 'coupon', label: '券' },
  { k: 'discount', label: '折扣' },
  { k: 'subsidy', label: '国补' },
]
const tab = ref('coupon')

// ── 折扣子域：两个 type 都存 `template` 表，读写走现成的模板端点（零新端点）──
/** 类目折扣上下限：`Type='discount.limit'`，`Name`=类目名，`Body`={"min":..,"max":..}。 */
const limitRows = ref([])
const limitLoading = ref(false)
/** 折扣模板（填充源）：`Type='discount.rule'`，`Body`=`6.5折|说明`。 */
const ruleRows = ref([])
const r = reactive({ Name: '', Body: '' })

/** 已知的两条唯一键前缀：`(Type, Name)` —— 上下限按类目名 upsert，模板按模板名建。 */
const LIMIT_TYPE = 'discount.limit'
const RULE_TYPE = 'discount.rule'

/** `Enabled` 读回来可能是 `true` / `1` / `'1'`（连接器把 `tinyint(1)` 映射成 Boolean）⇒ 宽容判。 */
const isOn = (v) => v === true || v === 1 || v === '1' || v === 'true'

/** 从 `{"min":6.0,"max":9.5}` 里取一个数；没有 ⇒ `''`（**不是** 0）。 */
function pickNum(body, key) {
  const m = new RegExp('"' + key + '"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)').exec(body || '')
  return m ? m[1] : ''
}

/**
 * 类目上下限表：**按类目列全** —— 类目来源是 `goodstype`（不是 `template`），
 * 所以「没配过的类目」也要出一行（显示「未设置」），否则管理员根本不知道该配哪些。
 */
async function loadLimits() {
  limitLoading.value = true
  try {
    if (!categories.value.length) await loadCategories()
    const data = await templateList(LIMIT_TYPE)
    const byName = {}
    ;(data.list || []).forEach((row) => { byName[row.Name] = row })
    limitRows.value = categories.value.map((g) => {
      const hit = byName[g.GoodType]
      return {
        name: g.GoodType,
        Id: hit ? hit.Id : null,
        min: hit ? pickNum(hit.Body, 'min') : '',
        max: hit ? pickNum(hit.Body, 'max') : '',
      }
    })
  } catch (e) {
    limitRows.value = []
  } finally {
    limitLoading.value = false
  }
}

/** 保存一行：有 `Id` ⇒ 改，没有 ⇒ 建（`(Type, Name)` 撞唯一键由库判定，同后端口径）。 */
async function saveLimit(row) {
  const body = JSON.stringify({
    ...(row.min === '' ? {} : { min: Number(row.min) }),
    ...(row.max === '' ? {} : { max: Number(row.max) }),
  })
  try {
    // ⚠️ 后端参数名是**首字母大写**的 `Name` / `Body` / `Enabled`（`AdminTemplateController`），
    //    只有主键那个是 `id` —— 写成小写会**静默不生效**（`required = false` ⇒ 当成"没传"）。
    const res = row.Id
      ? await templateAdminUpdate({ id: row.Id, Name: row.name, Body: body })
      : await templateAdminCreate({ Type: LIMIT_TYPE, Name: row.name, Body: body })
    flash.text = res.success ? `「${row.name}」已保存` : (res.msg || '保存失败')
    flash.kind = res.success ? 'ok' : 'bad'
    if (res.success) await loadLimits()
  } catch (e) {
    flash.text = '保存失败'; flash.kind = 'bad'
  }
}

async function loadRules() {
  try {
    const data = await templateList(RULE_TYPE)
    ruleRows.value = data.list || []
  } catch (e) {
    ruleRows.value = []
  }
}

async function doCreateRule() {
  if (!r.Name || !r.Body) { flash.text = '模板名与正文都不能空'; flash.kind = 'bad'; return }
  try {
    const res = await templateAdminCreate({ Type: RULE_TYPE, Name: r.Name, Body: r.Body })
    flash.text = res.success ? '折扣模板已新增' : (res.msg || '新增失败')
    flash.kind = res.success ? 'ok' : 'bad'
    if (res.success) { r.Name = ''; r.Body = ''; await loadRules() }
  } catch (e) {
    flash.text = '新增失败'; flash.kind = 'bad'
  }
}

/** 启停 —— 走模板的 `update`（`Enabled` 发 `1`/`0`，后端收 `Integer`；读回来是 Boolean）。 */
async function setRuleEnabled(row, on) {
  try {
    const res = await templateAdminUpdate({ id: row.Id, Enabled: on ? 1 : 0 })
    flash.text = res.success ? (on ? '已启用' : '已停用') : (res.msg || '操作失败')
    flash.kind = res.success ? 'ok' : 'bad'
    if (res.success) await loadRules()
  } catch (e) {
    flash.text = '操作失败'; flash.kind = 'bad'
  }
}
const stopRule = (row) => setRuleEnabled(row, false)
const activateRule = (row) => setRuleEnabled(row, true)

/** 切到折扣 / 国补 tab 才拉数（不在 onMounted 里白白多打几条请求）。 */
watch(tab, (v) => {
  if (v === 'discount') { loadLimits(); loadRules() }
  if (v === 'subsidy') loadSubsidy()
})

// ── 国补子域（C3 块 5 · commit 3）：规则 / 提名 ─────────────────────────────────
// ⚠️ 与折扣那边**刻意相反**的口径：折扣把折算结果写进 `goods.OriginalPrice`（那是**商家填的事实**，
//    配置不许事后改掉它）；国补的补率是**平台随时可调的** ⇒ 补贴额一律**后端现算**
//    （`SubsidyAmount = min(Price × Ratio, Cap)`，`Cap = 0` = 不封顶，`PayAmount = Price − SubsidyAmount`）。
//    本页**不复刻**那条算法 —— 复刻出来的第二份一定会在某次改动里跑偏（同 `api/marketing.js` 那条 ⚠️）。
const sbRules = ref([])
const sbNoms = ref([])
/** 提名候选（`goodsSelectHome` 的在架商品）—— 只有它回 `UserName`，页面要显示店名。 */
const pickGoods = ref([])
const sbBusy = ref(false)
const sb = reactive({ Name: '', CategoryMatch: '', Ratio: '', Cap: '', StartAt: '', EndAt: '' })
const nom = reactive({ RuleId: '', GoodsId: '' })

/** 只有 `active` 的规则能被提名（后端也拦；这里**前置**拦，省得用户白点一次再吃回一条错）。 */
const activeRules = computed(() => sbRules.value.filter((row) => row.Status === 'active'))

/** 补贴比例：库里存**小数**（`0.1500` = 15%），页面上按**百分数**显示更好读（`Number(…toFixed)` 去尾零）。 */
const fmtRatio = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? `${Number((n * 100).toFixed(2))}%` : '—'
}

/** 单笔上限：`0` = **不封顶**（不是「补 0 元」）—— 与 `subsidy_rule.Cap` 的建表口径同一个意思。 */
const fmtCap = (v) => (Number(v) > 0 ? `¥${fmtMoney(v)}` : '不封顶')

/** 有效期：起止都可为 `null` = **不限**；两个都空 ⇒ 一行「不限」，比「— ~ —」干净。 */
function fmtRange(start, end) {
  if (!start && !end) return '不限'
  return `${start ? toLocal(start) : '不限'} ~ ${end ? toLocal(end) : '不限'}`
}

/** 提名状态的人话。`pending` 用 `.a-chip.wait`（不抢 `--accent` —— 那色只管价格 / 促销 / 未读）。 */
const NOM_LABELS = { pending: '待商家确认', approved: '商家已同意', rejected: '商家已拒绝' }
const nomLabel = (s) => NOM_LABELS[s] || s || '—'
const nomChip = (s) => (s === 'approved' ? 'ok' : s === 'rejected' ? 'off' : 'wait')

async function loadSubsidyRules() {
  try {
    const data = await subsidyRuleList({ limit: 200 })
    sbRules.value = data && data.success && Array.isArray(data.list) ? data.list : []
  } catch (e) {
    sbRules.value = []
    flash.kind = 'bad'
    flash.text = '国补规则读取失败：' + ((e && e.message) || '未知原因')
  }
}

async function loadNominations() {
  try {
    const data = await subsidyNominations({ limit: 200 })
    sbNoms.value = data && data.success && Array.isArray(data.list) ? data.list : []
  } catch (e) {
    sbNoms.value = []
    flash.kind = 'bad'
    flash.text = '提名列表读取失败：' + ((e && e.message) || '未知原因')
  }
}

/**
 * 提名候选 = `goodsSelectHome` 的在架商品。
 *
 * ⚠️ 按 `ProductCode` **去重**：库里 `GD-101-9552` 有两行（`Id=76` / `Id=120`，同名同价同码），
 *    不去重的话下拉里会出现两条一模一样的选项。⚠️ 那两行的重复本身是**待裁项**——
 *    在那边有结论之前，这里**只做展示层去重，一行数据都不动**。
 */
async function loadGoodsOptions() {
  try {
    const data = await catalogVisibleGoods()
    const rows = data && data.success && Array.isArray(data.list) ? data.list : []
    const seen = new Set()
    pickGoods.value = rows.filter((g) => {
      const code = String(g.ProductCode || '') || `#${g.Id}`
      if (seen.has(code)) return false
      seen.add(code)
      return true
    })
  } catch (e) {
    pickGoods.value = []
  }
}

/** 切到国补 tab 的入口：三条**并发**（串行的话最慢的那条决定整体观感）。 */
async function loadSubsidy() {
  await Promise.all([loadSubsidyRules(), loadNominations(), loadGoodsOptions()])
}

/** 建规则 —— 两个 `datetime-local` 都要经 `toSqlTime` 反算成库内时钟（UTC），见文件头那条时区口径。 */
async function doCreateRule2() {
  if (!sb.Name) { flash.kind = 'bad'; flash.text = '规则名不能为空'; return }
  if (!sb.Ratio) { flash.kind = 'bad'; flash.text = '补贴比例不能为空'; return }
  sbBusy.value = true
  flash.text = ''
  try {
    const data = await subsidyRuleCreate({
      Name: sb.Name,
      CategoryMatch: sb.CategoryMatch,
      Ratio: sb.Ratio,
      // `Cap` 留空 ⇒ 发 0 = 不封顶（后端 `parseDouble(capRaw, 0d)` 同解；显式发比省略少一层歧义）。
      Cap: sb.Cap === '' ? 0 : sb.Cap,
      StartAt: toSqlTime(sb.StartAt, false),
      EndAt: toSqlTime(sb.EndAt, true),
    })
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '建国补规则失败：' + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `国补规则已创建：${data.RuleId}`
    Object.assign(sb, { Name: '', CategoryMatch: '', Ratio: '', Cap: '', StartAt: '', EndAt: '' })
    await loadSubsidyRules()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '建国补规则失败：' + ((e && e.message) || '未知原因')
  } finally {
    sbBusy.value = false
  }
}

/** 停用 / 启用共用一条腿（两个端点只差一个动词）。 */
async function setSubsidyRuleEnabled(row, on) {
  sbBusy.value = true
  flash.text = ''
  const verb = on ? '启用' : '停用'
  try {
    const data = on ? await subsidyRuleActivate(row.RuleId) : await subsidyRuleStop(row.RuleId)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = `${verb}失败：` + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `规则「${row.Name}」已${verb}`
    await loadSubsidyRules()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `${verb}失败：` + ((e && e.message) || '未知原因')
  } finally {
    sbBusy.value = false
  }
}
const doStopRule = (row) => setSubsidyRuleEnabled(row, false)
const doActivateRule = (row) => setSubsidyRuleEnabled(row, true)

/** 提名 —— 成功只写一条 `pending` + 给商家发通知；**用户端此刻什么都还看不到**（要等商家点头）。 */
async function doNominate() {
  if (!nom.RuleId) { flash.kind = 'bad'; flash.text = '请先选规则'; return }
  if (!nom.GoodsId) { flash.kind = 'bad'; flash.text = '请先选商品'; return }
  sbBusy.value = true
  flash.text = ''
  try {
    const data = await subsidyNominate(nom.RuleId, nom.GoodsId)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '提名失败：' + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `已提名「${data.GoodName || data.GoodsId}」，等 ${data.MerchantId || '商家'} 在消息中心确认`
    nom.GoodsId = ''
    await loadNominations()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '提名失败：' + ((e && e.message) || '未知原因')
  } finally {
    sbBusy.value = false
  }
}

const TYPES = [
  { code: 'threshold', label: '满减' },
  { code: 'percent', label: '折扣' },
  { code: 'none', label: '无门槛' },
]

const loading = ref(false)
const creating = ref(false)
const statsLoading = ref(false)
const statsFailed = ref(false)
const statsMsg = ref('')
/** 正在改状态的那一张券（防连点）—— 也是「哪一行的按钮转圈」的判据。 */
const busy = ref('')
const flash = reactive({ text: '', kind: 'ok' })

const rows = ref([])
const total = ref(0)
const limit = ref(100)
const f = reactive({ Status: '', keyword: '', limit: 200 })

const stats = ref({ trend: { days: [], claimed: [], used: [] }, types: [], totals: {} })

const picked = ref(null)
const grantRows = ref([])
const grantLoading = ref(false)
const granting = ref(false)
const grantTo = ref('')

const c = reactive(blankCoupon())

function blankCoupon() {
  return {
    Name: '', Type: 'threshold', FaceValue: '', Threshold: '', TotalQty: '',
    // ⚠️ `MerchantAccount` 仍是提交字段（后端 `/admin/coupon` 有这个参数，空串=平台券）——
    //    只是建券表单里不再有它对应的输入框（那格原属不存在的「指定商家」档，见模板注释）。
    Scope: 'all', MerchantAccount: '', CategoryMatch: '', StartAt: '', EndAt: '',
  }
}

// ── 「限商品种类」多选（数据源 = 既有类目端点 `GET /goods/goodstype`）────────────────────
// ⚠️ **单一真源 = `c.CategoryMatch`（逗号串，= 后端存储格式，不变）**；勾选数组只是它的**读侧派生**。
//    刻意**不用** `v-model` 绑数组 + `watch` 回写：那条路上「数组」成了第二个真源，一旦同步漏一拍，
//    就会发出 `Scope='merchant'` 却没有 CategoryMatch 的券（后端直接拒）—— 提交字段必须当场赋值。
const categories = ref([])
const catOpen = ref(false)
const catPicked = computed(() => {
  const s = (c.CategoryMatch || '').trim()
  return s ? s.split(',').map((x) => x.trim()).filter(Boolean) : []
})

/** 勾 / 取消一个类目 —— 当场把它写回 `c.CategoryMatch`。 */
function toggleCat(name) {
  const next = catPicked.value.includes(name)
    ? catPicked.value.filter((x) => x !== name)
    : catPicked.value.concat([name])
  c.CategoryMatch = next.join(',')
}

function clearCats() {
  c.CategoryMatch = ''
}

// ⚠️ 切回「全场」要把已选清掉：否则会发一张 `Scope='all'` 但 `CategoryMatch='电子用品'` 的券 ——
//    后端只在 merchant 档校验 CategoryMatch，不会拦，列表里就成了「全场」下面还挂一行类目名。
//    顺带把菜单收起来（那一整块本来就要消失）。
watch(() => c.Scope, (s) => {
  if (s !== 'merchant') {
    clearCats()
    catOpen.value = false
  }
})

/** 类目选项：只取**一级**（`ParentId === 0`）。现有 goodstype 是单层，这一步是恒等过滤。 */
async function loadCategories() {
  try {
    const data = await catalogCategories()
    categories.value =
      data && data.success && Array.isArray(data.list)
        ? data.list.filter((g) => g.ParentId === 0)
        : []
  } catch (e) {
    categories.value = []
  }
}

// ── 显示口径（本页自备，不抽公共模块 —— 同 `api/marketing.js` 的 `q()` 口径）──────────
const fmtInt = (v) => (v === null || v === undefined ? '0' : Number(v).toLocaleString('zh-CN'))
const fmtMoney = (v) =>
  Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function typeLabel(type) {
  const t = TYPES.find((x) => x.code === type)
  return t ? t.label : String(type || '—')
}

/** 面值的人话：折扣券读成「8.5 折」，另两类读成「减 ¥N」。 */
function faceLabel(row) {
  const v = Number(row.FaceValue || 0)
  if (row.Type === 'percent') return `${(v * 10).toFixed(1)} 折`
  return `减 ¥${fmtMoney(v)}`
}

const faceHint = computed(() => (c.Type === 'percent' ? '折扣率（0.85 = 8.5 折）*' : '面值（元）*'))
const facePlaceholder = computed(() => (c.Type === 'percent' ? '0.85' : '20'))

const truncated = computed(() => rows.value.length >= limit.value && total.value > rows.value.length)

// ── 时区换算（两个方向必须对称，否则「填进去的」和「显示出来的」对不上）──────────
// ⚠️ 逐字照 `AuditPage.vue` 的同名两函数 —— 本页不另立一套口径（同 `api/marketing.js` 的 `q()`）。
const pad = (n) => String(n).padStart(2, '0')

/** 浏览器时区偏移标签，形如 `UTC+08:00`。 */
const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

/**
 * 展示向：库内时钟文本（`'YYYY-MM-DD HH:MM:SS'`，UTC）→ 浏览器本地时间。
 *
 * ⚠️ 末尾那个 `Z` **不能省**：省掉它 `Date` 会按**本地**时区解析这串数字，等于把 UTC 当本地，
 *    东八区整整差 8 小时，而且**错得很安静**（格式完全正常）。
 */
function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/**
 * 输入向：`<input type="datetime-local">` 的值（`'YYYY-MM-DDTHH:MM'`，**本地时区**语义）
 * → 库内时钟文本（UTC）。
 *
 * 起补 `:00`、止补 `:59`：用户挑到哪一分，就把那一分**用满**。
 * ⚠️ 止补 `:59` 还有一个实际作用：起止挑成同一分钟时（例如 `22:01–22:01`）窗口是 59 秒而不是 0 秒，
 *    否则后端的 `EndAt 必须晚于 StartAt` 校验会当场拒掉一个「用户觉得没问题」的输入。
 */
function toSqlTime(localValue, isEnd) {
  if (!localValue) return ''
  const d = new Date(localValue) // 无偏移的 date-time ⇒ 按**本地**时区解析（ES2016+）
  if (Number.isNaN(d.getTime())) return ''
  d.setSeconds(isEnd ? 59 : 0, 0)
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
  )
}

const trendDays = computed(() => stats.value.trend.days || [])
const trendClaimed = computed(() => stats.value.trend.claimed || [])
const trendUsed = computed(() => stats.value.trend.used || [])
const typePie = computed(() =>
  (stats.value.types || []).map((t) => ({ name: typeLabel(t.Type), value: Number(t.Cnt || 0) }))
)

/** 换类型时把不适用字段清掉 —— 留着「折扣券带门槛 100」会让后端报一条与本意无关的错。 */
function onTypeChange() {
  if (c.Type === 'none') {
    c.Threshold = ''
  }
}

function resetCreate() {
  Object.assign(c, blankCoupon())
  clearCats()
  catOpen.value = false
}

function resetFilters() {
  f.Status = ''
  f.keyword = ''
  f.limit = 200
  loadList()
}

async function loadList() {
  loading.value = true
  try {
    const data = await couponList({ Status: f.Status, keyword: f.keyword, limit: f.limit })
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '券列表读取失败：' + ((data && data.msg) || '未知原因')
      return
    }
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total || 0)
    limit.value = Number(data.limit || 0)
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '券列表读取失败：' + ((e && e.message) || '未知原因')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  statsLoading.value = true
  statsFailed.value = false
  statsMsg.value = ''
  try {
    const data = await couponStats()
    if (!data || !data.success) {
      statsFailed.value = true
      statsMsg.value = (data && data.msg) || '统计读取失败'
      return
    }
    stats.value = {
      trend: data.trend || { days: [], claimed: [], used: [] },
      types: Array.isArray(data.types) ? data.types : [],
      totals: data.totals || {},
    }
  } catch (e) {
    statsFailed.value = true
    statsMsg.value = (e && e.message) || '统计读取失败'
  } finally {
    statsLoading.value = false
  }
}

async function doCreate() {
  creating.value = true
  flash.text = ''
  try {
    // ⚠️ 两个时间字段在表单里是**浏览器本地时区**的 `datetime-local` 值 ⇒ 发出去之前必须反算成
    //    库内时钟（UTC）文本，否则后端存的是「本地墙钟、UTC 语义」，券会晚 8 小时才开始（见文件头）。
    const data = await couponCreate({
      ...c,
      StartAt: toSqlTime(c.StartAt, false),
      EndAt: toSqlTime(c.EndAt, true),
    })
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '建券失败：' + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `建券成功：${data.CouponId}`
    resetCreate()
    await loadList()
    await loadStats()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '建券失败：' + ((e && e.message) || '未知原因')
  } finally {
    creating.value = false
  }
}

/** 停用 / 启用共用的收尾：报结果 → 重拉列表（**状态列要跟着变**）→ 重拉统计。 */
async function changeStatus(couponId, action, verb) {
  busy.value = couponId
  flash.text = ''
  try {
    const data = await action(couponId)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = `${verb}失败：` + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `${verb}成功：${couponId}`
    await loadList()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `${verb}失败：` + ((e && e.message) || '未知原因')
  } finally {
    busy.value = ''
  }
}

const doStop = (id) => changeStatus(id, couponStop, '停用')
const doActivate = (id) => changeStatus(id, couponActivate, '启用')

/**
 * 取某张券的领取明细并**就地**展开/收起。
 *
 * 用户 2026-10-11 手验要求：明细原来在页底单独占一块（点第 1 行的「明细」，
 * 内容出现在整页最下方，得滚到底才看得见，且看不出跟哪一行有关）。改成在被点的
 * **那一行下方**展开 —— 所以这里只维护 `picked`（=「哪一行展开着」），
 * 展开位由模板里那对兄弟 `<tr>` 决定。
 *
 * 点的是已展开的那一行 ⇒ 收起（再点一次不该重新拉一遍）。
 */
async function toggleGrants(couponId) {
  if (picked.value && picked.value.CouponId === couponId) {
    closeGrants()
    return
  }
  await loadGrants(couponId)
}

/** 加载并展开某张券的明细（令牌发放后要**刷新同一行**，故与 toggle 分开）。 */
async function loadGrants(couponId) {
  const row = rows.value.find((r) => r.CouponId === couponId) || null
  picked.value = row
  grantRows.value = []
  grantTo.value = ''
  grantLoading.value = true
  try {
    const data = await couponGrants(couponId, 200)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '明细读取失败：' + ((data && data.msg) || '未知原因')
      return
    }
    picked.value = data.row || row
    grantRows.value = Array.isArray(data.list) ? data.list : []
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '明细读取失败：' + ((e && e.message) || '未知原因')
  } finally {
    grantLoading.value = false
  }
}

function closeGrants() {
  picked.value = null
  grantRows.value = []
  grantTo.value = ''
}


async function doGrant() {
  granting.value = true
  flash.text = ''
  try {
    const data = await couponGrant(picked.value.CouponId, grantTo.value)
    if (!data || !data.success) {
      flash.kind = 'bad'
      flash.text = '发放失败：' + ((data && data.msg) || '未知原因')
      return
    }
    flash.kind = 'ok'
    flash.text = `已发放给 ${grantTo.value}`
    grantTo.value = ''
    await loadGrants(picked.value.CouponId)
    await loadList()
    await loadStats()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = '发放失败：' + ((e && e.message) || '未知原因')
  } finally {
    granting.value = false
  }
}

onMounted(() => {
  loadList()
  loadStats()
  loadCategories()
})
</script>

<style scoped>
.a-mk {
  max-width: 1120px;
}

/* ── 页内 tab（块 5〈折扣〉，2026-10-12）────────────────────────────────────
   形制照 `ShopHome.vue` 的 `.tabs`（同一枚规矩：**底边 2px 下划线**）——
   胶囊 / 分段控件都要再套一层带底容器，那就落进 `tokens.css:36` 的射程（统一大圆角）。 */
.a-tabs {
  display: flex;
  gap: var(--s6);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--s5);
}
.a-tab {
  position: relative;
  padding: var(--s3) 0;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  background: none;
  border: 0;
  cursor: pointer;
  transition: color 0.15s;
}
.a-tab:hover {
  color: var(--ink-2);
}
.a-tab-on {
  color: var(--ink);
  font-weight: var(--fw-med);
}
/* 下划线骑在那条 1px 发丝线上（`bottom: -1px`），不是另起一条 —— 否则会显成两条。 */
.a-tab-on::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--brand);
}
/* 上下限那一列是**窄数字输入**（折数，如 `6.5`），不用撑满表格列。 */
.a-inp-s {
  width: 96px;
  padding: var(--s2) var(--s3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}
.a-inp-s:focus {
  outline: none;
  border-color: var(--brand);
}
/* 表内小按钮（保存 / 启停）—— 比 `.a-btn` 小一档，12 个主按钮会把表变成按钮墙。 */
.a-btn-s {
  padding: var(--s1) var(--s3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}
.a-btn-s:hover {
  border-color: var(--brand);
  color: var(--brand);
}

/* ── ① 状态条（照 `StatsPage.vue`，逐字同源）────────────────────────────── */
.a-status {
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
.a-stat {
  display: flex;
  flex-direction: column;
  min-width: 96px;
}
.a-stat b {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semi);
  line-height: 1.1;
  color: var(--ink);
}
.a-stat span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-tz {
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
.a-chip-utc {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--info);
  background: var(--info-ground);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.a-tz-off {
  font-family: var(--font-mono);
  color: var(--brand-ink);
}

/* ── 反馈条 ──────────────────────────────────────────────────────────────── */
.a-flash {
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
  white-space: pre-wrap;
}
.a-flash.ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.a-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}

/* ── 表单（照 `StatsPage.vue`）──────────────────────────────────────────── */
.a-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  padding: var(--s4);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
}
.a-f {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-f-wide {
  flex: 1 1 180px;
  min-width: 150px;
}
.a-f-narrow {
  flex: 0 1 130px;
  min-width: 110px;
}
.a-f select,
.a-f input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-f input:disabled,
.a-f select:disabled {
  color: var(--ink-3);
  background: var(--surface-3);
  cursor: default;
}
.a-f-actions {
  display: flex;
  gap: var(--s2);
  margin-left: auto;
}

/* ── 「限商品种类」多选下拉 ────────────────────────────────────────────── */
.a-cat-ctl {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--s2);
}
/* 点外面关菜单：整屏透明蒙层（比挂 document 监听干净）。toggle/菜单各抬 z-index 站到它上面。 */
.a-cat-scrim {
  position: fixed;
  inset: 0;
  z-index: 15;
}
.a-cat-toggle {
  position: relative;
  z-index: 21;
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-height: 34px;
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  text-align: left;
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
  cursor: pointer;
}
.a-cat-toggle:hover {
  background: var(--surface-3);
}
.a-cat-ph {
  color: var(--ink-3);
}
.a-cat-val {
  flex: 1 1 auto;
}
.a-cat-caret {
  margin-left: auto;
  color: var(--ink-3);
}
.a-cat-clear {
  position: relative;
  z-index: 21;
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  background: none;
  border: none;
  padding: 0 var(--s1);
  cursor: pointer;
}
.a-cat-clear:hover {
  color: var(--ink);
}
.a-cat-menu {
  position: absolute;
  z-index: 20;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  max-height: 220px;
  overflow-y: auto;
  padding: var(--s2);
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  box-shadow: 0 8px 24px rgb(0 0 0 / 12%);
}
.a-cat .a-cat-opt {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s1) var(--s2);
  font-size: var(--fs-sm);
  color: var(--ink);
  border-radius: var(--r2);
  cursor: pointer;
}
.a-cat .a-cat-opt:hover {
  background: var(--surface-3);
}
/* ⚠️ 必须盖过上面的 `.a-f input`（它把 input 一律当文本输入框给 padding/border/背景）。
   `.a-cat .a-cat-opt input`（0,2,1）> `.a-f input`（0,1,1）⇒ 稳赢，不靠源码顺序。 */
.a-cat .a-cat-opt input {
  width: 14px;
  height: 14px;
  margin: 0;
  padding: 0;
  border: none;
  border-radius: 0;
  background: none;
  accent-color: var(--brand-ink);
  cursor: pointer;
}
.a-cat-none {
  margin: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

.a-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--s1);
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
.a-btn:hover {
  color: var(--ink);
  background: var(--surface-3);
}
.a-btn-on {
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
.a-btn:disabled {
  cursor: default;
  opacity: 0.6;
}
.a-btn-sm {
  padding: var(--s1) var(--s2);
  font-size: var(--fs-xs);
}

.a-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.8;
  margin: var(--s3) 0 var(--s5);
}
.a-hint code {
  font-family: var(--font-mono);
  color: var(--ink-2);
}
.a-note {
  font-size: var(--fs-xs);
  color: var(--warning-deep);
  margin: var(--s3) 0 var(--s2);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6) 0;
}

/* ── 分段标题（照 `StatsPage.vue` / `OrderPage.vue`）───────────────────── */
.a-h2 {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin: var(--s6) 0 var(--s3);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.a-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.a-mk > .a-h2:first-of-type {
  margin-top: 0;
}

/* ── 图卡 ───────────────────────────────────────────────────────────────── */
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4);
  margin-bottom: var(--s4);
}

/* ── 表 ─────────────────────────────────────────────────────────────────── */
.a-tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  overflow: hidden;
}
.a-tbl th,
.a-tbl td {
  text-align: left;
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
  vertical-align: top;
}
.a-tbl th {
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  color: var(--ink-3);
  background: var(--surface-2);
}
.a-tbl .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.a-tbl .mono,
.a-mono {
  font-family: var(--font-mono);
}
.a-mini {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-ink {
  color: var(--ink);
  font-weight: var(--fw-med);
}
.a-ops {
  white-space: nowrap;
}
.a-ops .a-btn + .a-btn {
  margin-left: var(--s1);
}

/* 状态 chip —— 与 `AccountsPage.vue` / `NotifyPage.vue` 同语言（底色 + 前景同族）。 */
.a-chip {
  display: inline-block;
  font-size: var(--fs-xs);
  border-radius: var(--r1);
  padding: 1px 6px;
  white-space: nowrap;
}
.a-chip.ok {
  color: var(--success-deep);
  background: var(--success-ground);
}
.a-chip.off {
  color: var(--ink-3);
  background: var(--surface-3);
}
.a-chip.wait {
  color: var(--warning-deep);
  background: var(--warning-ground);
}

/* ── 行内展开的明细（用户 2026-10-11 手验：从页底搬进被点的那一行下方）───────
   展开行本身**不是**普通数据行：整格 colspan=8，用左色条 + 浅底跟上面的券行分开，
   并给一点内缩，让人一眼看出「这属于上面那一行」。 */
.a-expand > td {
  padding: var(--s4) var(--s3) var(--s5);
  background: var(--surface-2);
  border-left: 3px solid var(--brand-ink);
}
.a-expand-h {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin-bottom: var(--s3);
  font-size: var(--fs-sm);
  color: var(--ink);
}
/* 展开位里的发放表单/明细表：去掉外框（已经在展开格子的底上了），只留内间距。 */
.a-filters-in {
  padding: 0;
  background: transparent;
  border: 0;
  margin-bottom: var(--s3);
}
.a-tbl-in {
  border: 1px solid var(--line-2);
}
</style>
