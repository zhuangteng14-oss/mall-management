<template>
  <section class="g-page">
    <!-- ── ① 状态条 —— 「数字 → 状态」（`c3-modules.md` §五）───────────────────────
         五个数都是**全量口径**（各走一次 `limit=1` 只取 `total` / `unread`），
         与下面各 tab 里「本次显示 N 条」是**两个口径**，别把两者读成同一个数。 -->
    <header class="g-status">
      <div class="g-stat">
        <b>{{ counts.reportPending }}</b>
        <span>待处理举报</span>
      </div>
      <div class="g-stat">
        <b>{{ counts.imagePending }}</b>
        <span>待审图片</span>
      </div>
      <div class="g-stat">
        <b>{{ counts.words }}</b>
        <span>敏感词</span>
      </div>
      <div class="g-stat">
        <b>{{ counts.commentTotal }}</b>
        <span>本页评论 / 提问</span>
      </div>
      <div class="g-stat">
        <b>{{ counts.msgUnread }}</b>
        <span>未读治理消息</span>
      </div>
      <div class="g-tz">
        <span class="g-chip-utc">UTC</span>
        <span>库内时钟 → <b>浏览器时区</b></span>
        <span class="g-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <!-- 页顶 flash（本仓管理页**没有** `confirm()`/`alert()` —— `eslintConfig` 的 `env`
         里没有 `browser`；二次确认一律「按钮上膛」，见 `armed`） -->
    <p v-if="flash.text" class="g-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── ② 六个 tab ─────────────────────────────────────────────────────────
         用 `v-show` 而**不是** `v-if`：切回来时不该重新拉一遍（每切一次就发 1–3 个
         请求，管理员在「举报 → 看评论 → 回来」这条动线上会来回切好几次）。
         ⚠️ 代价是**六个面板的 DOM 一直在**（含 ECharts 实例）—— 本页数据量是
         管理端量级（几十条），换「切换不闪、不重复请求」是划算的。 -->
    <nav class="g-tabs">
      <button
        v-for="t in TABS"
        :key="t.key"
        type="button"
        class="g-tab"
        :class="{ on: tab === t.key }"
        @click="switchTab(t.key)"
      >
        <IconGlyph :name="t.glyph" :tone="tab === t.key ? 'brand' : 'muted'" />{{ t.label }}
        <span v-if="badgeOf(t.key)" class="g-badge">{{ badgeOf(t.key) }}</span>
      </button>
    </nav>

    <!-- ══════════════════════════════ tab 1 · 评论管理 ══════════════════════ -->
    <div v-show="tab === 'comments'" class="g-panel">
      <p class="g-hint">
        方案 A4 把内容域钉死在 <b>13 条</b>端点，<code>/comment/list</code> 就<b>要求 <code>TargetId</code></b>
        （缺参即拒 —— 后端刻意不让它退化成全表扫描）⇒ <b>没有「全站评论列表」这种东西</b>。
        本栏因此是<b>按对象下钻</b>：先选一个对象，再看它下面的评论与回复。
        从「举报中心」「待审图片」点「<b>看该对象评论</b>」可一键把对象带过来。
      </p>

      <form class="g-filters" @submit.prevent="loadComments()">
        <label class="g-f">
          <span>对象类型</span>
          <select v-model="cs.targetType">
            <option value="goods">goods（商品）</option>
            <option value="user">user（用户）</option>
          </select>
        </label>
        <label class="g-f">
          <span>对象 ID</span>
          <input v-model.trim="cs.targetId" type="text" placeholder="商品跑 goods.Id；用户跑业务号" />
        </label>
        <label class="g-f">
          <span>类型</span>
          <select v-model="cs.kind">
            <option value="review">review（评价）</option>
            <option value="question">question（提问）</option>
            <option value="">不筛（评价 + 提问）</option>
          </select>
        </label>
        <label class="g-f">
          <span>排序</span>
          <select v-model="cs.sort">
            <option value="new">new（最新）</option>
            <option value="hot">hot（最热）</option>
          </select>
        </label>
        <label class="g-f g-f-narrow">
          <span>上限</span>
          <select v-model.number="cs.limit">
            <option v-for="n in [20, 50, 100]" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <div class="g-f-actions">
          <button type="submit" class="g-btn g-btn-on" :disabled="c.loading">
            <IconGlyph name="moderation" />{{ c.loading ? '加载中…' : '加载' }}
          </button>
          <button type="button" class="g-btn" @click="clearScope">清空</button>
        </div>
      </form>

      <p v-if="c.err" class="g-err">{{ c.err }}</p>

      <!-- 冷进页面 / 点过「清空」之后：`TargetId` 为空是**正常初态**，不是失败。
           这里走灰底 `g-note`，别再复用上面那条红 `g-err` —— 手验 `ui-01-overview.png`
           实拍到过「冷进页面就弹红字『缺少参数：TargetId』」。 -->
      <p v-if="!c.loaded && !c.err" class="g-note">
        本栏按对象下钻：选好「对象类型」、填一个「对象 ID」，再点「加载」。
      </p>

      <div v-if="c.loaded" class="g-grid2">
        <div class="g-card">
          <h3 class="g-h3">评分分布<span class="g-h3n">本次显示口径 · 只统计顶层评价</span></h3>
          <DonutChart
            :data="ratingMix"
            :height="'240px'"
            :center-value="String(ratingTotal)"
            center-title="顶层评价"
            :empty-text="cs.kind === 'question' ? '当前筛的是提问 —— 提问没有评分' : '本次没有带评分的顶层评价'"
          />
        </div>
        <div class="g-card">
          <h3 class="g-h3">赞 / 踩<span class="g-h3n">本次显示口径 · 取前 8 条，按总互动降序</span></h3>
          <BarChart
            :categories="voteMix.cats"
            :series="voteMix.series"
            :height="'240px'"
            horizontal
            :max-bar-width="14"
            :label-truncate="14"
            empty-text="本次没有可排序的互动行"
          />
        </div>
      </div>

      <p v-if="c.loaded && !c.rows.length && !c.err" class="g-empty">
        这个对象下没有命中任何内容。换个对象 ID 试试（商品评论要填 <code>goods.Id</code>，不是 <code>ProductCode</code>）。
      </p>

      <ol v-if="c.rows.length" class="g-list">
        <li v-for="row in c.rows" :key="row.Id" class="g-row">
          <div class="g-row-h">
            <span class="g-kind" :class="row.Kind">{{ kindLabel(row.Kind) }}</span>
            <b class="g-author">{{ row.Author || '（无作者）' }}</b>
            <span v-if="anonymous(row)" class="g-tag g-tag-dim">匿名</span>
            <span v-if="row.ProductCode" class="g-tag g-tag-dim"><code>{{ row.ProductCode }}</code></span>
            <span class="g-stars" v-if="row.Rating">{{ stars(row.Rating) }}</span>
            <span class="g-grow"></span>
            <span class="g-dim">{{ toLocal(row.CreatedAt) }}</span>
          </div>

          <p class="g-body">{{ row.Content }}</p>

          <div class="g-row-f">
            <span class="g-dim">Id <code>{{ row.Id }}</code></span>
            <span class="g-dim">赞 {{ row.LikeCount }} · 踩 {{ row.DislikeCount }}</span>
            <span v-if="imgBadge(row)" class="g-tag g-tag-warn">{{ imgBadge(row) }}</span>
            <span class="g-grow"></span>
            <button type="button" class="g-link" @click="jumpToObject(row)">看该对象评论</button>
            <button
              type="button"
              class="g-btn g-btn-danger"
              :class="{ armed: armed === 'c:' + row.Id }"
              :disabled="c.busyId === row.Id"
              @click="deleteContent(row, false)"
            >
              <IconGlyph name="no" />{{ armed === 'c:' + row.Id ? '再点一次确认删除' : '删除' }}
            </button>
          </div>

          <ul v-if="(row.Replies || []).length" class="g-replies">
            <li v-for="rep in row.Replies" :key="rep.Id" class="g-reply">
              <div class="g-row-h">
                <span class="g-kind" :class="rep.Kind">{{ kindLabel(rep.Kind) }}</span>
                <b class="g-author">{{ rep.Author || '（无作者）' }}</b>
                <span v-if="rep.ReplyToName" class="g-dim">回复 @{{ rep.ReplyToName }}</span>
                <span v-if="row.AcceptedReplyId === rep.Id" class="g-tag g-tag-ok">
                  <IconGlyph name="ok" />已采纳
                </span>
                <span v-if="flag(rep.IsMerchantFirst)" class="g-tag g-tag-brand">商家首次回复 · 置顶</span>
                <span class="g-grow"></span>
                <span class="g-dim">{{ toLocal(rep.CreatedAt) }}</span>
              </div>
              <p class="g-body">{{ rep.Content }}</p>
              <div class="g-row-f">
                <span class="g-dim">Id <code>{{ rep.Id }}</code></span>
                <span class="g-dim">赞 {{ rep.LikeCount }} · 踩 {{ rep.DislikeCount }}</span>
                <span class="g-grow"></span>
                <button
                  type="button"
                  class="g-btn g-btn-danger"
                  :class="{ armed: armed === 'c:' + rep.Id }"
                  :disabled="c.busyId === rep.Id"
                  @click="deleteContent(rep, true)"
                >
                  <IconGlyph name="no" />{{ armed === 'c:' + rep.Id ? '再点一次确认删除' : '删除' }}
                </button>
              </div>
            </li>
          </ul>
        </li>
      </ol>

      <p v-if="c.loaded && c.total > c.rows.length" class="g-note">
        只显示了前 {{ c.rows.length }} 条（共 {{ c.total }} 条）—— 把上限调大，或换个更窄的对象。
      </p>
    </div>

    <!-- ══════════════════════════════ tab 2 · 问答管理 ══════════════════════ -->
    <div v-show="tab === 'questions'" class="g-panel">
      <p class="g-hint">
        与「评论管理」同一条口径：<b>按对象下钻</b>，没有全站提问列表。本栏多一个「解决状态」筛子，
        并把 <b>超过 {{ TIMEOUT_MINUTES }} 分钟仍未解决</b>的提问标出
        <span class="g-tag g-tag-warn"><IconGlyph name="wait" />超时未解决</span>
        —— 那个判据与后端 <code>QuestionTimeoutJob</code> 的 <code>TIMEOUT_MINUTES</code> <b>同值同源</b>
        （本页只读不写，真正的超时上报是那台 job 发 <code>question.timeout</code> 给管理端收件箱）。
        ⚠️ a3 profile 下 <code>mall.downgrade.job.enabled=false</code> ⇒ <b>整台调度器不存在</b>，
        本页的「超时」标记是按同一判据<b>现算</b>的，与 job 醒不醒无关。
      </p>

      <form class="g-filters" @submit.prevent="loadQuestions()">
        <label class="g-f">
          <span>对象类型</span>
          <select v-model="qs.targetType">
            <option value="goods">goods（商品）</option>
            <option value="user">user（用户）</option>
          </select>
        </label>
        <label class="g-f">
          <span>对象 ID</span>
          <input v-model.trim="qs.targetId" type="text" placeholder="商品跑 goods.Id" />
        </label>
        <label class="g-f">
          <span>解决状态</span>
          <select v-model="qs.solved">
            <option value="">全部</option>
            <option value="0">未解决</option>
            <option value="1">已解决</option>
          </select>
        </label>
        <label class="g-f g-f-narrow">
          <span>上限</span>
          <select v-model.number="qs.limit">
            <option v-for="n in [20, 50, 100]" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <div class="g-f-actions">
          <button type="submit" class="g-btn g-btn-on" :disabled="q.loading">
            <IconGlyph name="moderation" />{{ q.loading ? '加载中…' : '加载' }}
          </button>
        </div>
      </form>

      <p v-if="q.err" class="g-err">{{ q.err }}</p>

      <div v-if="q.loaded" class="g-grid2">
        <div class="g-card">
          <h3 class="g-h3">解决率<span class="g-h3n">本次显示口径 · 顶层提问</span></h3>
          <DonutChart
            :data="solvedMix"
            :height="'240px'"
            :center-value="solvedRate === null ? '—' : `${solvedRate}%`"
            center-title="已解决占比"
            :empty-text="qs.solved === '1' ? '当前只筛了已解决 —— 分母为零，比例无意义' : '本次没有顶层提问'"
          />
        </div>
        <div class="g-card">
          <h3 class="g-h3">应答深度<span class="g-h3n">本次显示口径 · 每问的回答条数，按条数降序取前 8</span></h3>
          <BarChart
            :categories="answerMix.cats"
            :series="answerMix.series"
            :height="'240px'"
            horizontal
            :max-bar-width="14"
            :label-truncate="16"
            value-formatter="{value} 条"
            empty-text="本次没有可统计的提问"
          />
        </div>
      </div>

      <p v-if="q.loaded && !q.rows.length && !q.err" class="g-empty">这个对象下没有命中任何提问。</p>

      <ol v-if="q.rows.length" class="g-list">
        <li v-for="row in q.rows" :key="row.Id" class="g-row">
          <div class="g-row-h">
            <span class="g-kind question">提问</span>
            <b class="g-author">{{ row.Author || '（无作者）' }}</b>
            <span v-if="flag(row.IsSolved) || row.AcceptedReplyId" class="g-tag g-tag-ok">
              <IconGlyph name="ok" />已解决
            </span>
            <span v-else-if="isOverdue(row)" class="g-tag g-tag-warn">
              <IconGlyph name="wait" />超时未解决 · 已 {{ minutesSince(row.CreatedAt) }} 分钟
            </span>
            <span v-else class="g-tag g-tag-dim">未解决 · 已 {{ minutesSince(row.CreatedAt) }} 分钟</span>
            <span class="g-grow"></span>
            <span class="g-dim">{{ toLocal(row.CreatedAt) }}</span>
          </div>

          <p class="g-body">{{ row.Content }}</p>

          <div class="g-row-f">
            <span class="g-dim">Id <code>{{ row.Id }}</code></span>
            <span class="g-dim">回答 {{ (row.Replies || []).length }} 条</span>
            <span v-if="row.AcceptedReplyId" class="g-dim">已采纳 <code>{{ row.AcceptedReplyId }}</code></span>
            <span class="g-grow"></span>
            <button type="button" class="g-link" @click="jumpToObject(row)">看该对象问答</button>
            <button
              type="button"
              class="g-btn g-btn-danger"
              :class="{ armed: armed === 'q:' + row.Id }"
              :disabled="q.busyId === row.Id"
              @click="deleteQuestion(row)"
            >
              <IconGlyph name="no" />{{ armed === 'q:' + row.Id ? '再点一次确认删除' : '提删' }}
            </button>
          </div>

          <ul v-if="(row.Replies || []).length" class="g-replies">
            <li v-for="rep in row.Replies" :key="rep.Id" class="g-reply">
              <div class="g-row-h">
                <span class="g-kind" :class="rep.Kind">{{ kindLabel(rep.Kind) }}</span>
                <b class="g-author">{{ rep.Author || '（无作者）' }}</b>
                <span v-if="row.AcceptedReplyId === rep.Id" class="g-tag g-tag-ok">
                  <IconGlyph name="ok" />被采纳
                </span>
                <span v-if="flag(rep.IsMerchantFirst)" class="g-tag g-tag-brand">商家首次回复 · 置顶</span>
                <span class="g-grow"></span>
                <span class="g-dim">{{ toLocal(rep.CreatedAt) }}</span>
              </div>
              <p class="g-body">{{ rep.Content }}</p>
            </li>
          </ul>
        </li>
      </ol>

      <p v-if="q.loaded && q.total > q.rows.length" class="g-note">
        只显示了前 {{ q.rows.length }} 条（共 {{ q.total }} 条）—— 把上限调大，或收窄解决状态的筛子。
      </p>
    </div>

    <!-- ══════════════════════════════ tab 3 · 待审图片 ══════════════════════ -->
    <div v-show="tab === 'images'" class="g-panel">
      <p class="g-hint">
        方案 §4.3：图片<b>先审后显</b> —— 落库即 <code>pending</code>，<b>公开列表此时不给 URL</b>
        （这里能看到图，是因为管理端那一支的投影带出了 <code>ImageUrl</code>，与公开口径不同）。
        <b>驳回会真删仓库文件</b>（<code>static/img/comment/</code> 下那张）⇒ 这一发<b>不可逆</b>，走两次点击。
      </p>

      <div class="g-grid2">
        <div class="g-card">
          <h3 class="g-h3">审核进度<span class="g-h3n">全量口径 · 三态各一次计数</span></h3>
          <DonutChart
            :data="imageMix"
            :height="'240px'"
            :center-value="String(counts.imagePending)"
            center-title="待审"
            :empty-text="imageMix.length ? '' : '图库是空的'"
          />
        </div>
        <div class="g-card">
          <h3 class="g-h3">三态计数<span class="g-h3n">全量口径</span></h3>
          <ul class="g-mix">
            <li v-for="s in IMAGE_STATUSES" :key="s.key">
              <span class="g-mix-k"><span class="g-dot" :class="s.key"></span>{{ s.label }}</span>
              <b>{{ counts['image' + s.cap] }}</b>
            </li>
          </ul>
          <p class="g-hint">
            ⚠️ 行数（<code>comment_image</code>）与「评论条数」不是一回事：一条评论可带最多 <b>9</b> 张图。
          </p>
        </div>
      </div>

      <form class="g-filters" @submit.prevent="loadImages">
        <label class="g-f">
          <span>状态</span>
          <select v-model="is.status" @change="loadImages">
            <option v-for="s in IMAGE_STATUSES" :key="s.key" :value="s.key">{{ s.label }}（{{ s.key }}）</option>
          </select>
        </label>
        <label class="g-f g-f-narrow">
          <span>上限</span>
          <select v-model.number="is.limit" @change="loadImages">
            <option v-for="n in [50, 100, 200, 500]" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <div class="g-f-actions">
          <button type="submit" class="g-btn g-btn-on" :disabled="im.loading">
            {{ im.loading ? '加载中…' : '刷新' }}
          </button>
        </div>
      </form>

      <p v-if="im.err" class="g-err">{{ im.err }}</p>

      <p v-if="im.loaded && !im.rows.length && !im.err" class="g-empty">
        「{{ statusLabel(is.status) }}」这一档下没有图片。
      </p>

      <ul v-if="im.rows.length" class="g-shots">
        <li v-for="r in im.rows" :key="r.Id" class="g-shot">
          <div class="g-shot-img">
            <img :src="assetOf(r.ImageUrl)" :alt="`图片 ${r.Id}`" />
          </div>
          <div class="g-shot-meta">
            <span class="g-tag" :class="statusTagClass(r.Status)">
              {{ statusLabel(r.Status) }}
            </span>
            <span class="g-dim">Id <code>{{ r.Id }}</code> · 评论 <code>{{ r.CommentId }}</code></span>
            <span class="g-dim">作者 {{ r.AuthorId || '（已抹）' }}</span>
            <span class="g-dim">对象 {{ r.TargetType }} / <code>{{ r.TargetId }}</code></span>
            <span class="g-dim">{{ toLocal(r.CreatedAt) }}</span>
            <p class="g-shot-body">{{ r.CommentContent }}</p>
          </div>
          <div class="g-shot-acts">
            <button type="button" class="g-link" @click="jumpToObject(r)">看该对象评论</button>
            <button
              type="button"
              class="g-btn g-btn-on"
              :disabled="im.busyId === r.Id || r.Status === 'approved'"
              @click="reviewImage(r, 'approve')"
            >
              <IconGlyph name="ok" />通过
            </button>
            <button
              type="button"
              class="g-btn g-btn-danger"
              :class="{ armed: armed === 'i:' + r.Id }"
              :disabled="im.busyId === r.Id || r.Status === 'rejected'"
              @click="reviewImage(r, 'reject')"
            >
              <IconGlyph name="no" />{{ armed === 'i:' + r.Id ? '再点一次（会删仓库文件）' : '驳回并删除' }}
            </button>
          </div>
        </li>
      </ul>
    </div>

    <!-- ══════════════════════════════ tab 4 · 举报中心 ══════════════════════ -->
    <div v-show="tab === 'reports'" class="g-panel">
      <p class="g-hint">
        方案 §4.4。<b>通过</b>是一条不可逆的链：删内容 → 作者 <code>WarnCount +1</code> →
        <b>满 3 次自动封号</b>（复用「用户与商家治理」的封禁）→ 通知<b>双方</b>。
        <b>驳回</b>也是有效结果（留给举报人再举报的机会）。
        ⚠️ 举报人对被举报人匿名、<b>对管理端实名</b>（<code>ReporterId</code> 原样带出）——
        别把这一列回显到任何面向被举报人的地方。
      </p>

      <div class="g-grid2">
        <div class="g-card">
          <h3 class="g-h3">处理进度<span class="g-h3n">全量口径 · 三态各一次计数</span></h3>
          <DonutChart
            :data="reportMix"
            :height="'240px'"
            :center-value="String(counts.reportPending)"
            center-title="待处理"
            :empty-text="reportMix.length ? '' : '举报表是空的'"
          />
        </div>
        <div class="g-card">
          <h3 class="g-h3">理由分布<span class="g-h3n">本次显示口径（受上面的状态筛选影响）</span></h3>
          <BarChart
            :categories="reasonMix.cats"
            :series="reasonMix.series"
            :height="'240px'"
            value-formatter="{value} 条"
            :max-bar-width="46"
            empty-text="本次没有可归类的举报单"
          />
        </div>
      </div>

      <form class="g-filters" @submit.prevent="loadReports">
        <label class="g-f">
          <span>状态</span>
          <select v-model="rs.status" @change="loadReports">
            <option value="">全部</option>
            <option value="pending">pending（待处理）</option>
            <option value="handled">handled（已处理）</option>
            <option value="rejected">rejected（已驳回）</option>
          </select>
        </label>
        <label class="g-f g-f-narrow">
          <span>上限</span>
          <select v-model.number="rs.limit" @change="loadReports">
            <option v-for="n in [50, 100, 200, 500]" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <div class="g-f-actions">
          <button type="submit" class="g-btn g-btn-on" :disabled="re.loading">
            {{ re.loading ? '加载中…' : '刷新' }}
          </button>
        </div>
      </form>

      <p v-if="re.err" class="g-err">{{ re.err }}</p>

      <p v-if="re.loaded && !re.rows.length && !re.err" class="g-empty">没有命中任何举报单。</p>

      <ol v-if="re.rows.length" class="g-list">
        <li v-for="r in re.rows" :key="r.Id" class="g-row">
          <div class="g-row-h">
            <span class="g-tag" :class="reportTagClass(r.Status)">{{ r.Status }}</span>
            <span class="g-kind">{{ reasonLabel(r.Reason) }}</span>
            <b class="g-target">{{ targetSummary(r) }}</b>
            <span class="g-grow"></span>
            <span class="g-dim">{{ toLocal(r.CreatedAt) }}</span>
          </div>

          <p class="g-body g-quote">{{ targetBody(r) }}</p>
          <p v-if="r.Description" class="g-body">
            <span class="g-dim">举报人补充：</span>{{ r.Description }}
          </p>

          <div class="g-row-f">
            <span class="g-dim">单号 <code>{{ r.Id }}</code></span>
            <span class="g-dim">举报人 <b>{{ r.ReporterId || '（空）' }}</b>（{{ r.ReporterRole }}）</span>
            <span v-if="r.HandlerId" class="g-dim">处理人 <code>{{ r.HandlerId }}</code></span>
            <span class="g-grow"></span>
            <button type="button" class="g-link" @click="jumpToObject(r)">看该对象评论</button>
          </div>

          <div v-if="r.Status === 'pending'" class="g-handle">
            <input
              v-model.trim="noteOf[r.Id]"
              class="g-note"
              type="text"
              maxlength="200"
              placeholder="处理说明（可空，落 HandleNote）"
              :disabled="re.busyId === r.Id"
            />
            <button
              type="button"
              class="g-btn g-btn-on"
              :class="{ armed: armed === 'r:' + r.Id }"
              :disabled="re.busyId === r.Id"
              @click="handleReport(r, true)"
            >
              <IconGlyph name="ok" />{{ armed === 'r:' + r.Id ? '再点一次：删内容 + 警告' : '通过' }}
            </button>
            <button
              type="button"
              class="g-btn"
              :disabled="re.busyId === r.Id"
              @click="handleReport(r, false)"
            >
              <IconGlyph name="no" />驳回
            </button>
          </div>
          <p v-else-if="r.HandleNote" class="g-body">
            <span class="g-dim">处理说明：</span>{{ r.HandleNote }}
          </p>
        </li>
      </ol>
    </div>

    <!-- ══════════════════════════════ tab 5 · 敏感词库 ══════════════════════ -->
    <div v-show="tab === 'sensitive'" class="g-panel">
      <p class="g-hint">
        方案 §4.5。<b>命中占比 = 命中字符数 ÷ 正文长度</b>：&lt; 40% ⇒ 打码 <code>***</code> 后发表；
        <b>≥ 40% ⇒ 直接拒收</b>（不提示用户命中了什么词）。两条<b>有副作用</b>的事：
        ① <b>加词会立刻扫一遍历史评论</b>（就地打码，<b>不删评论</b>、也不回溯套用 40% 那条拒收规则）；
        ② <b>删词不回填</b> —— 已变成 <code>***</code> 的原文没有第二份副本，删词不会把它还原。
      </p>

      <div class="g-grid2">
        <div class="g-card">
          <h3 class="g-h3">加一个词<span class="g-h3n">⚠️ 会产生副作用：立刻扫历史评论</span></h3>
          <form class="g-inline" @submit.prevent="addWord">
            <input
              v-model.trim="sw.newWord"
              type="text"
              maxlength="64"
              placeholder="词面（≤64 字符，本列就是 varchar(64)）"
              :disabled="sw.adding"
            />
            <button type="submit" class="g-btn g-btn-on" :disabled="sw.adding || !sw.newWord">
              <IconGlyph name="ok" />{{ sw.adding ? '提交中…' : '加词并扫历史' }}
            </button>
          </form>
          <p v-if="sw.msg" class="g-note" :class="{ bad: !sw.msg.ok }">{{ sw.msg.text }}</p>
          <p class="g-hint">
            ⚠️ 扫的是<b>整张词表</b>、不是只打新加的这个词 —— 一条老评论可能同时含别的新旧词，
            重跑一遍是<b>幂等</b>的；只替换新词会让结果依赖「之前跑过什么」。
          </p>
        </div>
        <div class="g-card">
          <h3 class="g-h3">词长分布<span class="g-h3n">全量口径 · 本表就是「当前生效的全部词」</span></h3>
          <BarChart
            :categories="wordLenMix.cats"
            :series="wordLenMix.series"
            :height="'240px'"
            value-formatter="{value} 个"
            :max-bar-width="46"
            empty-text="词表是空的 —— 此时任何内容都不会被打码"
          />
        </div>
      </div>

      <table class="g-tbl">
        <thead>
          <tr><th>Id</th><th>词面</th><th>长度</th><th>入库时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="w in sw.rows" :key="w.Id">
            <td><code>{{ w.Id }}</code></td>
            <td class="g-word">{{ w.Word }}</td>
            <td class="g-dim">{{ String(w.Word || '').length }}</td>
            <td class="g-dim">{{ toLocal(w.CreatedAt) }}</td>
            <td class="g-td-act">
              <button
                type="button"
                class="g-btn g-btn-danger"
                :class="{ armed: armed === 'w:' + w.Id }"
                :disabled="sw.busyId === w.Id"
                @click="removeWord(w)"
              >
                <IconGlyph name="no" />{{ armed === 'w:' + w.Id ? '再点一次确认删除' : '删除' }}
              </button>
            </td>
          </tr>
          <tr v-if="!sw.rows.length">
            <td colspan="5" class="g-dim">
              词表为空。<b>这不是故障</b> —— 但此时敏感词闸形同不存在，发表的内容一个字都不会被打码。
            </td>
          </tr>
        </tbody>
      </table>
      <p class="g-hint">共 {{ sw.total }} 个词（本页显示 {{ sw.rows.length }} 个）。</p>
    </div>

    <!-- ══════════════════════════════ tab 6 · 消息管理 ══════════════════════ -->
    <div v-show="tab === 'messages'" class="g-panel">
      <p class="g-hint">
        这是管理端<b>共享收件箱</b>（<code>ToId='*'</code> / <code>ToType='admin'</code>）——
        本块的两条治理回流都落在这儿：<code>question.timeout</code>（问答超时上报）、
        <code>report.handled</code>（举报处理通知）。
        <b>「发」不在这里</b> —— 写通知、群发是「系统通知」那一页（<code>NotifyPage.vue</code>）的事；
        同一条口径的另一半：通知 ≠ 消息，前端入口也不合并。
      </p>

      <div class="g-filters">
        <label class="g-f">
          <span>筛选</span>
          <select v-model="ms.eventType" @change="loadMessages">
            <option value="">全部事件</option>
            <option v-for="o in EVENT_OPTIONS" :key="o.code" :value="o.code">{{ o.label }}</option>
          </select>
        </label>
        <label class="g-f g-f-check">
          <input v-model="ms.onlyUnread" type="checkbox" @change="loadMessages" />
          <span>仅看未读</span>
        </label>
        <div class="g-f-actions">
          <button type="button" class="g-btn g-btn-on" :disabled="ms.loading" @click="loadMessages">
            {{ ms.loading ? '加载中…' : '刷新' }}
          </button>
          <button
            type="button"
            class="g-btn"
            :disabled="!counts.msgUnread || ms.busy"
            @click="markAllRead"
          >
            <IconGlyph name="ok" />全部已读（{{ counts.msgUnread }}）
          </button>
        </div>
      </div>

      <p v-if="ms.err" class="g-err">{{ ms.err }}</p>
      <p v-if="ms.loaded && !ms.rows.length && !ms.err" class="g-empty">
        {{ ms.onlyUnread ? '没有未读的治理消息。' : '收件箱是空的。' }}
      </p>

      <ol v-if="ms.rows.length" class="g-stream">
        <li v-for="m in ms.rows" :key="m.Id" class="g-ev" :class="{ unread: m.unread }">
          <span class="g-ev-hue" :style="{ background: hue(m.EventType) }"></span>
          <span class="g-ev-main">
            <span class="g-ev-l1">
              <b class="g-ev-title">{{ m.Title }}</b>
              <code class="g-tag">{{ labelOf(m.EventType) }}</code>
              <span v-if="m.unread" class="g-tag g-tag-warn">未读</span>
            </span>
            <span class="g-ev-l2">{{ m.Content }}</span>
            <span class="g-ev-l3 g-dim">
              Id <code>{{ m.Id }}</code>
              <template v-if="m.TargetType"> · 对象 {{ m.TargetType }} / <code>{{ m.TargetId }}</code></template>
            </span>
          </span>
          <span class="g-ev-time">
            <b>{{ toLocal(m.CreatedAt) }}</b>
            <button
              v-if="m.unread"
              type="button"
              class="g-link"
              :disabled="ms.busy"
              @click="markRead(m)"
            >标为已读</button>
          </span>
        </li>
      </ol>
      <p v-if="ms.loaded && ms.total > ms.rows.length" class="g-note">
        只显示了最近 {{ ms.rows.length }} 条（共 {{ ms.total }} 条）。
      </p>
    </div>
  </section>
</template>

<!--
  消息与内容治理页（C3 块 6 · commit 6，2026-10-13）—— `/admin/business/moderation` 的真页。

  它是**占位里第六个换成真页的**（原走 `StubPage.vue`，路由记录 `router/index.js` 里
  `component: AdminStub` → 懒加载本组件，**同行替换**）。前五个依次是 `AuditPage`（批 A）、
  `AccountsPage`（块 2）、`CatalogPage`（块 3）、`OrderPage`（块 4）、`MarketingPage`（块 5）；
  第七个是 `NotifyPage`（块 1 · I2，位置在它下面一行）。

  【它消费的是「内容域 13 条」全部】方案 A4 把内容域钉死在 13 条端点，本页是它们的
  **唯一管理端消费面**：
    · `/comment/list` · `/comment/delete`（评论管理 / 问答管理）—— `api/comment.js`
    · `/comment/imageReview`（待审图片，一条端点三个动作）—— `api/comment.js`
    · `/report/list` · `/report/handle`（举报中心）—— `api/report.js`
    · `/sensitive/list|add|delete`（敏感词库）—— `api/sensitive.js`
  另加**公共层**的 `/notification/*`（消息管理那一栏 —— 管理端共享收件箱 `ToId='*'`）：
  那是「收」，与 `NotifyPage.vue` 的「发」（`/admin/notify/*`）**是两个 Controller**，
  本页与那页**有意不合并**（口径同 `api/notify.js` 文件头）。

  【六个 tab 的「可操作」判据】方案 §五 逐字要求六个页签**都可操作**（不是六个只读列表）：
    评论管理 → 删（顶层连带回复 / 回复只删自己）· 问答管理 → 提删 · 待审图片 → 通过 / 驳回·
    举报中心 → 通过（删内容 + 警告 + 满 3 封号）/ 驳回 · 敏感词库 → 加词（并扫历史）/ 删词 ·
    消息管理 → 标记已读 / 全部已读。

  【⚠️ 「评论管理 / 问答管理」为什么是**下钻**而不是全站列表】方案 §五 写的是
  「评论管理（全站评论列表 / 删）」与「问答管理（超时未解决 / 手动删）」，但——
    · 同一份方案 A4 把内容域端点钉死在 **13 条**，其中**没有**任何「全站评论 / 提问列表」；
    · 唯一的列表端点 `/comment/list` **要求 `TargetId`**（缺参即拒，`CommentSmokeTest`
      的 `listWithoutTargetIdIsRejected` 就是钉这一条，后端给了「不让它退化成全表扫描」的理由）。
    ⇒ 要兑现「全站列表」就得**加第 14 个端点**，那是**改契约**（本笔唯一被授权的契约改动只有
      `comment.on.user` 的删 / `comment.on.post`+`comment.reply` 的加，见 commit 1）。
      **不加。** 落到「按对象下钻 + 从一个对象跳到另一个对象」——
      「举报中心 / 待审图片」两栏各自带出了 `TargetType` / `TargetId`，点一下就能把对象喂进
      评论管理（`jumpToObject`），这条动线把「找一条评论」实际要走的步骤补齐了。

  【⚠️ 本页**没有** `confirm()`/`alert()`】`eslintConfig` 的 `env` 里没有 `browser`（用浏览器
  全局会触发 `no-undef`），且本仓管理页的既有惯例（`OrderPage.vue:638-642` 那条 ⚠️）就是
  「提示走页顶 flash 条、二次确认落在按钮自己身上」。⇒ 所有不可逆动作走**两次点击**
  （`armed` 记 `'前缀:Id'`，第一次只上膛 + 发一条 flash，第二次才发请求）。

  【⚠️ 删评论要 `Account`，取的是**管理员自己的登录名**】`/comment/delete` 收 `Account` 并跑
  `IdentityGuard.requireSelfOrAdminByUsername` —— 管理员那一档放行靠的是**会话角色**，
  但**入参不能空**（空会被判缺参）。⇒ 取 `api/auth.js` 的 `getUsername()`（登录名，
  `/user/me` 也答它，见 `restoreFromSession`）。取不到就**不发请求**、直接报一句人话，
  而不是发一个空 `Account` 去撞后端。

  【视觉】按 `c3-modules.md` §五「可观测域视觉化原则」：数字→状态（顶部五个数 + 每个 tab 的
  badge）、比例→图表（`DonutChart` / `BarChart`，色值一律走 `chartTokens`，本页一个 `#hex`
  都没有）、异常高亮（超时未解决 / 未读 / 待审的暖色标签）。⚠️ 类名前缀取 `g-`（治理）：
  `a-`（audit/accounts/marketing/order/home）、`n-`（notify）、`c-`（catalog）都已被占，
  各自的样式块里同名类会误导读者。
-->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getUsername } from '../../api/auth'
import { assetOf } from '../../api/http'
import { imageReview, list as commentList, remove as commentRemove } from '../../api/comment'
import { handle as reportHandle, list as reportList } from '../../api/report'
import { add as sensitiveAdd, list as sensitiveList, remove as sensitiveRemove } from '../../api/sensitive'
import { notifyList, notifyRead, notifyReadAll } from '../../api/notify'
import { EVENT_OPTIONS, eventLabel, isKnownEvent } from '../../config/notify-events'
import DonutChart from '../../components/charts/DonutChart.vue'
import BarChart from '../../components/charts/BarChart.vue'; import IconGlyph from '../../components/ui/IconGlyph.vue'

/**
 * 问答超时阈值 —— 与后端 `QuestionTimeoutJob.TIMEOUT_MINUTES` **同值同源**（方案 §4.2 写死的 30）。
 * ⚠️ 本页**只读不写**：真正的「超时上报」是那台 job 发 `question.timeout` 进管理端收件箱。
 *    这里的红标是**按同一判据现算**的 —— 后台调度器在 a3 profile 下整台不存在
 *    （`mall.downgrade.job.enabled=false` 关掉了 `@EnableScheduling` 的载体类），
 *    但「一条 40 分钟没人答的提问该被看见」这件事不该跟着调度器一起消失。
 */
const TIMEOUT_MINUTES = 30

/** 管理端共享收件箱的哨兵收件人 —— 与后端 `MerchantApplicationController.ADMIN_INBOX_ID` 逐字一致。 */
const ADMIN_INBOX = { toId: '*', toType: 'admin' }

const TABS = [
  { key: 'comments', label: '评论管理', glyph: 'msg' },
  { key: 'questions', label: '问答管理', glyph: 'audit' },
  { key: 'images', label: '待审图片', glyph: 'goods' },
  { key: 'reports', label: '举报中心', glyph: 'moderation' },
  { key: 'sensitive', label: '敏感词库', glyph: 'settings' },
  { key: 'messages', label: '消息管理', glyph: 'notify' },
]

/** 图片三态 —— 顺序即图表与列表的展示顺序。`cap` 用来拼 `counts` 里的键名（`imagePending`…）。 */
const IMAGE_STATUSES = [
  { key: 'pending', label: '待审', cap: 'Pending' },
  { key: 'approved', label: '已通过', cap: 'Approved' },
  { key: 'rejected', label: '已驳回', cap: 'Rejected' },
]

// ── 小工具 ───────────────────────────────────────────────────────────────────
const pad = (n) => String(n).padStart(2, '0')

/** 数值兜底：`null` / `undefined` / 非数字 ⇒ 0（`SUM(条件)` 在空集上回 `NULL`，见 `CommentDao`）。 */
function num(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

/** 宽容布尔：`tinyint(1)` 经 Connector/J 可能是 `true` / `1` / `'1'`（口径同 `NotifyPage.isEnabled`）。 */
function flag(v) {
  return v === true || v === 1 || v === '1' || v === 'true'
}

/** 匿名行后端把 `AuthorId` 置 `null`（防反查）—— 只看这一个字段，别拿 `Author` 里那串 `*****` 反推。 */
const anonymous = (row) => row.AuthorId === null || row.AuthorId === undefined

const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})

/** 库内时钟文本（UTC）→ 浏览器本地时间。⚠️ 末尾 `Z` **不能省**（同 `NotifyPage.toLocal`）。 */
function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/** 距现在多少分钟（向上取整）。两条时间都是**绝对时刻**（库内文本补 `Z` 后即真 UTC），故可直接相减。 */
function minutesSince(sqlTime) {
  if (!sqlTime) return 0
  const t = new Date(`${String(sqlTime).replace(' ', 'T')}Z`).getTime()
  if (Number.isNaN(t)) return 0
  return Math.max(0, Math.round((Date.now() - t) / 60000))
}

/** 「超时未解决」= 未解决 且 已过 30 分钟。判据写在**一处**，模板两处引用都走它。 */
function isOverdue(row) {
  if (flag(row.IsSolved) || row.AcceptedReplyId) return false
  return minutesSince(row.CreatedAt) > TIMEOUT_MINUTES
}

/** 事件类型的稳定配色（照 `NotifyPage.hue` —— 同一 code 永远同一个色相）。 */
function hue(key) {
  const s = String(key || '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return `hsl(${h} 55% 55%)`
}

const labelOf = (code) => (isKnownEvent(code) ? eventLabel(code) : String(code == null ? '' : code))

const KIND_LABELS = { review: '评价', question: '提问', answer: '回答' }
const kindLabel = (k) => KIND_LABELS[k] || String(k == null ? '' : k)

const REASON_LABELS = { 辱骂: '辱骂', 广告: '广告', 违法: '违法', 其他: '其他' }
const reasonLabel = (r) => REASON_LABELS[r] || String(r == null ? '' : r)

/** 1–5 星 → `★★★★☆`（只读展示，不参与任何判据）。 */
function stars(n) {
  const v = Math.max(0, Math.min(5, Math.round(num(n))))
  return '★'.repeat(v) + '☆'.repeat(5 - v)
}

const statusLabel = (s) =>
  ({ pending: '待审', approved: '已通过', rejected: '已驳回' }[s] || String(s == null ? '' : s))

function statusTagClass(s) {
  return s === 'approved' ? 'g-tag-ok' : s === 'rejected' ? 'g-tag-danger' : 'g-tag-warn'
}

function reportTagClass(s) {
  return s === 'handled' ? 'g-tag-ok' : s === 'rejected' ? 'g-tag-danger' : 'g-tag-warn'
}

/** 公开列表里 `pending` / `rejected` 的图**不给 URL**（后端判据在出口一处）—— 这里如实回一句。 */
function imgBadge(row) {
  const imgs = row.Images || []
  if (!imgs.length) return ''
  const wait = imgs.filter((i) => i.Status === 'pending').length
  const gone = imgs.filter((i) => i.Status === 'rejected').length
  const parts = []
  if (wait) parts.push(`${wait} 张审核中`)
  if (gone) parts.push(`${gone} 张违规已删`)
  return parts.join(' · ')
}

// ── 反馈（页顶 flash 条）─────────────────────────────────────────────────────
const flash = reactive({ kind: 'ok', text: '' })
function say(kind, text) {
  flash.kind = kind
  flash.text = text
}

/** 后端 `{success:false,msg}` 或异常 → 一句人话。**别覆盖 `msg`**（各条失败文案互不相同）。 */
function failOf(e) {
  if (e && e.status) return `HTTP ${e.status}`
  return (e && e.message) || String(e)
}

/**
 * 二次确认的「上膛」态 —— 值是 `'前缀:Id'`。
 * 第一次点击只上膛 + 发一条 flash（**不发请求**），再点同一下才真执行。
 */
const armed = ref(null)

/**
 * 统一的「两次点击」闸。返回 `true` = 这次该真执行（调用方继续），`false` = 只是上膛。
 * @param {string} key   `前缀:Id`，同一时刻只允许一颗按钮处于上膛态。
 * @param {string} text  上膛时那条 flash 的话（要写明**成功后会发生什么**）。
 */
function confirmTwice(key, text) {
  if (armed.value !== key) {
    armed.value = key
    say('ok', text)
    return false
  }
  armed.value = null
  return true
}

// ── tab ─────────────────────────────────────────────────────────────────────
const tab = ref('comments')

/** tab 上的角标（`0` 一律不画）—— 五个数都是**全量口径**，与各 tab 内的「本次显示」不同源。 */
function badgeOf(key) {
  if (key === 'reports') return counts.reportPending
  if (key === 'images') return counts.imagePending
  if (key === 'sensitive') return counts.words
  if (key === 'messages') return counts.msgUnread
  if (key === 'comments') return 0
  return 0
}

/** 切 tab：该栏还没加载过就**顺手拉一次**（`loaded` 只置一次，切回来不会重复请求）。 */
function switchTab(key) {
  tab.value = key
  if (key === 'comments' && !c.loaded) loadComments({ silent: true })
  else if (key === 'questions' && !q.loaded) loadQuestions()
  else if (key === 'images' && !im.loaded) loadImages()
  else if (key === 'reports' && !re.loaded) loadReports()
  else if (key === 'sensitive' && !sw.loaded) loadWords()
  else if (key === 'messages' && !ms.loaded) loadMessages()
}

// ── 状态条：五个**全量**数 ───────────────────────────────────────────────────
const counts = reactive({
  reportPending: 0,
  reportHandled: 0,
  reportRejected: 0,
  imagePending: 0,
  imageApproved: 0,
  imageRejected: 0,
  words: 0,
  commentTotal: 0,
  msgUnread: 0,
})

/**
 * 拉状态条那几个数 —— 每个都只取 `limit=1`（**只要 `total` / `unread`**，不要行）。
 * 七个请求一起发（`Promise.all` + 各自 `catch` 成 `null`）：任一个失败**不该**让整条状态条
 * 变成错误页，失败的那格留 `0` 并**不弹错** —— 状态条是导航辅助，不是判据。
 */
async function loadCounts() {
  const one = (p) => p.catch(() => null)
  const [rPending, rHandled, rRejected, iPending, iApproved, iRejected, words, msgs] = await Promise.all([
    one(reportList({ status: 'pending', limit: 1 })),
    one(reportList({ status: 'handled', limit: 1 })),
    one(reportList({ status: 'rejected', limit: 1 })),
    one(imageReview({ action: 'list', status: 'pending', limit: 1 })),
    one(imageReview({ action: 'list', status: 'approved', limit: 1 })),
    one(imageReview({ action: 'list', status: 'rejected', limit: 1 })),
    one(sensitiveList({ limit: 1 })),
    one(notifyList({ ...ADMIN_INBOX, limit: 1 })),
  ])
  counts.reportPending = num(rPending && rPending.total)
  counts.reportHandled = num(rHandled && rHandled.total)
  counts.reportRejected = num(rRejected && rRejected.total)
  counts.imagePending = num(iPending && iPending.total)
  counts.imageApproved = num(iApproved && iApproved.total)
  counts.imageRejected = num(iRejected && iRejected.total)
  counts.words = num(words && words.total)
  counts.msgUnread = num(msgs && msgs.unread)
}

// ── tab 1 · 评论管理 ─────────────────────────────────────────────────────────
const cs = reactive({ targetType: 'goods', targetId: '', kind: 'review', sort: 'new', limit: 20 })
const c = reactive({ rows: [], total: 0, loading: false, loaded: false, err: '', busyId: null, stats: {} })

/** `silent=true` = 由**自动**路径调（挂载时 / 切 tab 时）：`TargetId` 还空着只说明「还没下钻」，
 *  不是失败 ⇒ 不弹红条，让面板停在模板里那条灰提示上。点「加载」走的仍是 `silent=false`。 */
async function loadComments({ silent = false } = {}) {
  if (!cs.targetId) {
    if (silent) return
    c.err = '缺少参数：TargetId —— 本栏按对象下钻，先选对象类型并填一个对象 ID。'
    say('bad', c.err)
    return
  }
  c.loading = true
  c.err = ''
  try {
    const data = await commentList({
      targetType: cs.targetType,
      targetId: cs.targetId,
      kind: cs.kind || undefined,
      sort: cs.sort,
      limit: cs.limit,
      offset: 0,
    })
    if (!data || !data.success) {
      c.err = (data && data.msg) || '加载失败'
      c.rows = []
      return
    }
    c.rows = Array.isArray(data.list) ? data.list : []
    c.total = num(data.total)
    c.stats = data.stats || {}
    counts.commentTotal = c.total
    c.loaded = true
  } catch (e) {
    c.err = failOf(e)
    c.rows = []
  } finally {
    c.loading = false
  }
}

function clearScope() {
  cs.targetId = ''
  c.rows = []
  c.total = 0
  c.stats = {}
  c.loaded = false
  c.err = ''
}

/** 星级分布（`stats` 的 `S5`…`S1`）。⚠️ 只统计**顶层评价**，回复不带评分。 */
const ratingMix = computed(() => {
  const s = c.stats || {}
  return [
    { name: '5 星', value: num(s.S5) },
    { name: '4 星', value: num(s.S4) },
    { name: '3 星', value: num(s.S3) },
    { name: '2 星', value: num(s.S2) },
    { name: '1 星', value: num(s.S1) },
  ].filter((x) => x.value > 0)
})
const ratingTotal = computed(() => ratingMix.value.reduce((a, b) => a + b.value, 0))

/** 赞 / 踩排行（本次显示口径）—— 取总互动前 8 条，类目用「#Id 作者」避免重名。 */
const voteMix = computed(() => {
  const rows = [...c.rows]
    .map((r) => ({ label: `#${r.Id} ${r.Author || ''}`.trim(), like: num(r.LikeCount), dis: num(r.DislikeCount) }))
    .filter((r) => r.like + r.dis > 0)
    .sort((a, b) => b.like + b.dis - (a.like + a.dis))
    .slice(0, 8)
    .reverse()
  return {
    cats: rows.map((r) => r.label),
    series: [
      { name: '赞', data: rows.map((r) => r.like) },
      { name: '踩', data: rows.map((r) => r.dis) },
    ],
  }
})

/**
 * 删一条内容（评论 / 回复 / 提问 / 回答共用 `/comment/delete`）。
 * ⚠️ **删顶层连带删掉它下面的回复**（后端 `softDeleteRepliesUnder`），删回复只删那一条 ——
 * 上膛那句 flash 必须把这件事说清，否则管理员以为「只删了这条」。
 */
async function deleteContent(row, isReply) {
  const login = getUsername()
  if (!login) {
    say('bad', '未取得当前管理员的登录名（会话里没有 username）⇒ 不发请求。`/comment/delete` 需要 Account。')
    return
  }
  const key = 'c:' + row.Id
  if (!confirmTwice(key, isReply
    ? `再点一次「删除」以确认：只删这条回复（Id ${row.Id}），它下面的内容不受影响。`
    : `再点一次「删除」以确认：连同它下面 ${(row.Replies || []).length} 条回复一并删除（Id ${row.Id}）。`)) return
  c.busyId = row.Id
  try {
    const data = await commentRemove(login, row.Id)
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '删除失败')
      return
    }
    say('ok', `已删除 Id ${row.Id}${data.replies ? `，连带回复 ${data.replies} 条` : ''}。列表已刷新。`)
    await loadComments()
    await loadCounts()
  } catch (e) {
    say('bad', `删除失败：${failOf(e)}`)
  } finally {
    c.busyId = null
  }
}

// ── tab 2 · 问答管理 ─────────────────────────────────────────────────────────
const qs = reactive({ targetType: 'goods', targetId: '', solved: '', limit: 20 })
const q = reactive({ rows: [], total: 0, loading: false, loaded: false, err: '', busyId: null, stats: {} })

async function loadQuestions() {
  if (!qs.targetId) {
    q.err = '缺少参数：TargetId —— 本栏按对象下钻，先填一个对象 ID。'
    say('bad', q.err)
    return
  }
  q.loading = true
  q.err = ''
  try {
    const data = await commentList({
      targetType: qs.targetType,
      targetId: qs.targetId,
      kind: 'question',
      solved: qs.solved || undefined,
      limit: qs.limit,
      offset: 0,
    })
    if (!data || !data.success) {
      q.err = (data && data.msg) || '加载失败'
      q.rows = []
      return
    }
    q.rows = Array.isArray(data.list) ? data.list : []
    q.total = num(data.total)
    q.stats = data.stats || {}
    counts.commentTotal = q.total
    q.loaded = true
  } catch (e) {
    q.err = failOf(e)
    q.rows = []
  } finally {
    q.loading = false
  }
}

/** 解决率环图：`stats` 的 `Solved` / `Unsolved`（只统计顶层提问）。 */
const solvedMix = computed(() => {
  const s = q.stats || {}
  const solved = num(s.Solved)
  const unsolved = num(s.Unsolved)
  return [
    { name: '已解决', value: solved },
    { name: '未解决', value: unsolved },
  ].filter((x) => x.value > 0)
})
const solvedRate = computed(() => {
  const s = q.stats || {}
  const total = num(s.Solved) + num(s.Unsolved)
  return total === 0 ? null : Math.round((num(s.Solved) / total) * 100)
})

/** 每问的回答条数排行（本次显示口径，取前 8）。 */
const answerMix = computed(() => {
  const rows = [...q.rows]
    .map((r) => ({ label: `#${r.Id} ${(r.Content || '').slice(0, 12)}`, n: (r.Replies || []).length }))
    .filter((r) => r.n > 0)
    .sort((a, b) => b.n - a.n)
    .slice(0, 8)
    .reverse()
  return { cats: rows.map((r) => r.label), series: [{ name: '回答条数', data: rows.map((r) => r.n) }] }
})

/**
 * 提删一条提问 —— 与「评论管理」用**同一个端点**（`/comment/delete`），差别只在文案：
 * 方案 §4.2 的口径是「提问者不能自删、商家可提删 → 管理端裁判」，管理员这一发就是那次裁判的落点。
 */
async function deleteQuestion(row) {
  const login = getUsername()
  if (!login) {
    say('bad', '未取得当前管理员的登录名（会话里没有 username）⇒ 不发请求。`/comment/delete` 需要 Account。')
    return
  }
  if (!confirmTwice('q:' + row.Id,
    `再点一次「提删」以确认：提问 Id ${row.Id} 连同它下面 ${(row.Replies || []).length} 条回答一并删除。`)) return
  q.busyId = row.Id
  try {
    const data = await commentRemove(login, row.Id)
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '删除失败')
      return
    }
    say('ok', `已删除提问 Id ${row.Id}${data.replies ? `，连带回答 ${data.replies} 条` : ''}。`)
    await loadQuestions()
    await loadCounts()
  } catch (e) {
    say('bad', `删除失败：${failOf(e)}`)
  } finally {
    q.busyId = null
  }
}

// ── tab 3 · 待审图片 ─────────────────────────────────────────────────────────
const is = reactive({ status: 'pending', limit: 50 })
const im = reactive({ rows: [], total: 0, loading: false, loaded: false, err: '', busyId: null })

async function loadImages() {
  im.loading = true
  im.err = ''
  try {
    const data = await imageReview({ action: 'list', status: is.status, limit: is.limit, offset: 0 })
    if (!data || !data.success) {
      im.err = (data && data.msg) || '加载失败'
      im.rows = []
      return
    }
    im.rows = Array.isArray(data.list) ? data.list : []
    im.total = num(data.total)
    im.loaded = true
  } catch (e) {
    im.err = failOf(e)
    im.rows = []
  } finally {
    im.loading = false
  }
}

const imageMix = computed(() =>
  IMAGE_STATUSES
    .map((s) => ({ name: s.label, value: num(counts['image' + s.cap]) }))
    .filter((x) => x.value > 0)
)

/**
 * 通过 / 驳回一张图。`reject` **会真删仓库文件**（`static/img/comment/` 下那张）⇒ 走两次点击。
 * ⚠️ 后端在 `reject` 时把 `Status` 置 `rejected` 并删文件，重复调用是**幂等**的（`fileDeleted`
 *    只有第一次为 `true`），但这里仍然拦在同状态按钮上（`disabled`），免得管理员重复点。
 */
async function reviewImage(row, action) {
  if (action === 'reject' && !confirmTwice('i:' + row.Id,
    `再点一次以确认：这张图（Id ${row.Id}）会被标记为违规，**并从 static/img/comment/ 真删掉文件**。这一发不可逆。`)) return
  im.busyId = row.Id
  try {
    const data = await imageReview({ action, id: row.Id })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '操作失败')
      return
    }
    say('ok', action === 'approve'
      ? `已通过图片 Id ${row.Id}，商品页现在能看见它了。`
      : `已驳回图片 Id ${row.Id}${data.fileDeleted ? '，仓库文件已删除' : '（文件此前已不在）'}。`)
    await loadImages()
    await loadCounts()
  } catch (e) {
    say('bad', `操作失败：${failOf(e)}`)
  } finally {
    im.busyId = null
  }
}

// ── tab 4 · 举报中心 ─────────────────────────────────────────────────────────
const rs = reactive({ status: 'pending', limit: 100 })
const re = reactive({ rows: [], total: 0, loading: false, loaded: false, err: '', busyId: null })
/** 每条举报单的「处理说明」草稿（`Id → 文本`）。用对象而不是数组：行会被重拉、下标会错位。 */
const noteOf = reactive({})

async function loadReports() {
  re.loading = true
  re.err = ''
  try {
    const data = await reportList({ status: rs.status || undefined, limit: rs.limit, offset: 0 })
    if (!data || !data.success) {
      re.err = (data && data.msg) || '加载失败'
      re.rows = []
      return
    }
    re.rows = Array.isArray(data.list) ? data.list : []
    re.total = num(data.total)
    re.loaded = true
  } catch (e) {
    re.err = failOf(e)
    re.rows = []
  } finally {
    re.loading = false
  }
}

const reportMix = computed(() => [
  { name: '待处理', value: num(counts.reportPending) },
  { name: '已处理', value: num(counts.reportHandled) },
  { name: '已驳回', value: num(counts.reportRejected) },
].filter((x) => x.value > 0))

/** 理由分布（**本次显示口径** —— 受状态筛子影响，标题里已写明）。 */
const reasonMix = computed(() => {
  const acc = {}
  re.rows.forEach((r) => {
    const k = reasonLabel(r.Reason)
    acc[k] = (acc[k] || 0) + 1
  })
  const cats = Object.keys(acc)
  return { cats, series: [{ name: '举报单', data: cats.map((k) => acc[k]) }] }
})

/** 举报对象的一句话摘要 —— 三张 LEFT JOIN 表按 `TargetType` 各取一边（后端已经带出来了）。 */
function targetSummary(r) {
  if (r.TargetType === 'comment') {
    return `评论 / ${r.TargetKind || '—'} · 作者 ${r.TargetAuthor || '（已删）'}`
  }
  if (r.TargetType === 'goods') return `商品 · ${r.TargetGoodName || '（已删）'}`
  if (r.TargetType === 'user') return `用户 · ${r.TargetUserName || '（已删）'}`
  return String(r.TargetType || '')
}

/** 被举报内容本体：评论给正文，商品给货号，用户给业务号。 */
function targetBody(r) {
  if (r.TargetType === 'comment') return r.TargetContent || '（这条评论已经不在了 —— 作者自删或已被删）'
  if (r.TargetType === 'goods') return `货号 ${r.TargetId} · 商家 ${r.TargetMerchantId || '—'}`
  return `业务号 ${r.TargetId}`
}

/**
 * 处理一条举报。`approve:true` 是**不可逆的链**（删内容 + 警告 + 可能封号 + 通知双方）⇒ 两次点击。
 * ⚠️ `success:true` 也可能 `contentAffected:0`（内容已被作者自删 / 商品已下架）——那是**正常**的，
 *    别当失败重试（举报单此刻已被消费，重试只会得「该举报已被处理」）。
 */
async function handleReport(r, approve) {
  if (approve && !confirmTwice('r:' + r.Id,
    `再点一次「通过」以确认：删掉被举报内容 + 作者 WarnCount +1（满 3 次自动封号）+ 通知双方。举报单 Id ${r.Id}。`)) return
  re.busyId = r.Id
  try {
    const data = await reportHandle({ id: r.Id, approve, note: noteOf[r.Id] })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '处理失败')
      return
    }
    if (!approve) {
      say('ok', `已驳回举报单 Id ${r.Id} —— 举报人现在可以再举报一次。`)
    } else {
      const bits = [`已通过举报单 Id ${r.Id}`]
      bits.push(data.contentAffected ? `内容已处置 ${data.contentAffected} 条` : '内容已不在（0 条被改动，正常）')
      if (data.warnCount !== undefined && data.warnCount !== null) bits.push(`作者警告累计 ${data.warnCount} 次`)
      if (data.banned) bits.push('⚠️ 该账号已被自动封禁（满 3 次）')
      say('ok', `${bits.join(' · ')}。`)
    }
    await loadReports()
    await loadCounts()
  } catch (e) {
    say('bad', `处理失败：${failOf(e)}`)
  } finally {
    re.busyId = null
  }
}

// ── tab 5 · 敏感词库 ─────────────────────────────────────────────────────────
const sw = reactive({ rows: [], total: 0, loaded: false, adding: false, busyId: null, newWord: '', msg: null })

async function loadWords() {
  sw.msg = null
  try {
    const data = await sensitiveList({ limit: 500 })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '词表加载失败')
      return
    }
    sw.rows = Array.isArray(data.list) ? data.list : []
    sw.total = num(data.total)
    counts.words = sw.total
    sw.loaded = true
  } catch (e) {
    say('bad', `词表加载失败：${failOf(e)}`)
  }
}

/** 词长分布（全量口径 —— 词表就是「当前生效的全部词」，本表不分页）。 */
const wordLenMix = computed(() => {
  const acc = {}
  sw.rows.forEach((w) => {
    const n = String(w.Word || '').length
    const k = `${n} 字`
    acc[k] = (acc[k] || 0) + 1
  })
  const cats = Object.keys(acc).sort((a, b) => parseInt(a, 10) - parseInt(b, 10))
  return { cats, series: [{ name: '词数', data: cats.map((k) => acc[k]) }] }
})

/**
 * 加一个词 —— **有副作用**：后端会立刻扫一遍历史评论并就地打码。
 * ⚠️ 回体里的 `masked` 是**真被改写**的行数，`0` 是**正常结果**（一条都没命中），不是失败。
 *    这里如实分开报，别拿 `success` 一个字段概括。
 */
async function addWord() {
  const w = sw.newWord.trim()
  if (!w) return
  sw.adding = true
  sw.msg = null
  try {
    const data = await sensitiveAdd(w)
    if (!data || !data.success) {
      sw.msg = { ok: false, text: (data && data.msg) || '加词失败' }
      return
    }
    const masked = num(data.masked)
    sw.msg = {
      ok: true,
      text: masked > 0
        ? `已加入「${data.Word}」（Id ${data.Id}）· 已屏蔽 ${masked} 条历史评论`
        : `已加入「${data.Word}」（Id ${data.Id}）· 历史评论命中 0 条（正常结果，不是失败）`,
    }
    sw.newWord = ''
    await loadWords()
    await loadCounts()
  } catch (e) {
    sw.msg = { ok: false, text: `加词失败：${failOf(e)}` }
  } finally {
    sw.adding = false
  }
}

/** 删一个词。⚠️ **不回填**历史评论里的 `***` —— 上膛文案必须说清这一点。 */
async function removeWord(w) {
  if (!confirmTwice('w:' + w.Id,
    `再点一次以确认删除敏感词「${w.Word}」。⚠️ 已经变成 *** 的历史评论**不会**被还原（原文没有第二份副本）。`)) return
  sw.busyId = w.Id
  try {
    const data = await sensitiveRemove(w.Id)
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '删除失败')
      return
    }
    say('ok', `已删除敏感词「${w.Word}」。（历史评论里的 *** 不回填）`)
    await loadWords()
    await loadCounts()
  } catch (e) {
    say('bad', `删除失败：${failOf(e)}`)
  } finally {
    sw.busyId = null
  }
}

// ── tab 6 · 消息管理（管理端共享收件箱）──────────────────────────────────────
const ms = reactive({ rows: [], total: 0, loaded: false, loading: false, err: '', busy: false, onlyUnread: false, eventType: '' })

async function loadMessages() {
  ms.loading = true
  ms.err = ''
  try {
    const data = await notifyList({
      ...ADMIN_INBOX,
      onlyUnread: ms.onlyUnread || undefined,
      limit: 100,
    })
    if (!data || !data.success) {
      ms.err = (data && data.msg) || '加载失败'
      ms.rows = []
      return
    }
    const rows = Array.isArray(data.list) ? data.list : []
    ms.rows = ms.eventType ? rows.filter((m) => m.EventType === ms.eventType) : rows
    ms.total = num(data.total)
    counts.msgUnread = num(data.unread)
    ms.loaded = true
  } catch (e) {
    ms.err = failOf(e)
    ms.rows = []
  } finally {
    ms.loading = false
  }
}

async function markRead(m) {
  ms.busy = true
  try {
    const data = await notifyRead({ ...ADMIN_INBOX, id: m.Id })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '标记失败')
      return
    }
    // ⚠️ `updated:0` 是**正常结果**（这条已经标过了）—— 判据用 `success`，别用 `updated === 1`。
    m.unread = false
    counts.msgUnread = Math.max(0, counts.msgUnread - 1)
  } catch (e) {
    say('bad', `标记失败：${failOf(e)}`)
  } finally {
    ms.busy = false
  }
}

async function markAllRead() {
  ms.busy = true
  try {
    const data = await notifyReadAll({ ...ADMIN_INBOX })
    if (!data || !data.success) {
      say('bad', (data && data.msg) || '全部已读失败')
      return
    }
    say('ok', `已把 ${num(data.updated)} 条标为已读（0 条也是正常结果）。`)
    await loadMessages()
  } catch (e) {
    say('bad', `全部已读失败：${failOf(e)}`)
  } finally {
    ms.busy = false
  }
}

// ── 跨栏跳转：从「举报中心 / 待审图片」带着对象去评论管理 ─────────────────────
/**
 * 「看该对象评论」—— 把这一行带出来的 `TargetType` / `TargetId` 喂进评论管理并**立刻加载**。
 * 这条动线是本页对「没有全站列表」那个缺口的补法：管理员实际要找一条评论时，
 * 手里往往先有一条举报单或一张待审图 —— 从那里点进来比让人手抄业务号可靠。
 */
function jumpToObject(row) {
  const tt = row.TargetType || 'goods'
  const tid = row.TargetId
  if (!tid) {
    say('bad', '这一行没有 TargetId（对象可能已被删除），无法跳转。')
    return
  }
  if (tt !== 'goods' && tt !== 'user') {
    // 举报单的 TargetType 只有 comment / goods / user；「comment」这一档下钻用的是它挂的那个对象，
    // 而 `report` 表只存了 TargetId（评论 Id）—— 没存它挂在谁身上。
    say('bad', `「${tt}」这一档没有可下钻的对象 ID（举报单只存了被举报对象的 Id，没存它挂在谁身上）—— 请手动选对象。`)
    return
  }
  cs.targetType = tt
  cs.targetId = String(tid)
  cs.kind = ''
  tab.value = 'comments'
  loadComments()
}

onMounted(async () => {
  await loadCounts()
  // 冷进页面时 `TargetId` 必然是空的 ⇒ 只拉状态条，**不**调 `loadComments()`：
  // 那一下会把「还没下钻」当成失败，在页面顶部弹一条红字（手验 `ui-01-overview.png` 实拍到）。
  await loadComments({ silent: true })
})
</script>

<style scoped>
/* 骨架与 `NotifyPage.vue` / `OrderPage.vue` 同一套（同一个壳、同一批 token），
   三页在侧边栏来回切时不该有两种密度。⚠️ 类名前缀 `g-`（治理）：`a-` / `n-` / `c-` 都已被占。 */
.g-page {
  padding: var(--s6) var(--s5) var(--s7);
  font-family: var(--font);
  font-size: var(--fs-sm);
  color: var(--ink);
}

/* ── 状态条 ─────────────────────────────────────────────────────────────── */
.g-status {
  display: flex;
  align-items: center;
  gap: var(--s6);
  flex-wrap: wrap;
  padding: var(--s5);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  margin-bottom: var(--s5);
}
.g-stat { display: flex; flex-direction: column; gap: var(--s2); }
.g-stat b {
  font-size: var(--fs-2xl);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  line-height: 1;
}
.g-stat span { font-size: var(--fs-xs); color: var(--ink-3); }
.g-tz {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.g-chip-utc {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-radius: var(--r1);
  padding: 0 var(--s2);
}
.g-tz-off { font-family: var(--font-mono); }

/* ── 反馈条 ─────────────────────────────────────────────────────────────── */
.g-flash {
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
  white-space: pre-wrap;
}
.g-flash.ok { color: var(--success-deep); background: var(--success-ground); }
.g-flash.bad { color: var(--danger); background: var(--danger-ground); }

/* ── tab 条 ─────────────────────────────────────────────────────────────── */
.g-tabs {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  margin-bottom: var(--s5);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
}
.g-tab {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s4);
  cursor: pointer;
}
.g-tab:hover { color: var(--ink); border-color: var(--ink-3); }
.g-tab.on {
  color: #fff;
  background: var(--brand);
  border-color: var(--brand);
}
.g-badge {
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-radius: var(--r1);
  padding: 0 var(--s2);
}
.g-tab.on .g-badge { color: var(--brand); background: #fff; }

.g-panel { animation: g-fade 0.16s ease-out; }
@keyframes g-fade { from { opacity: 0.5; } to { opacity: 1; } }

/* ── 卡片 / 两列网格 ────────────────────────────────────────────────────── */
.g-grid2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: var(--s4);
  margin-bottom: var(--s5);
}
.g-card {
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  padding: var(--s4) var(--s5);
  min-width: 0;
}
.g-h3 {
  margin: 0 0 var(--s3);
  font-size: var(--fs-sm);
  font-weight: 600;
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
}
.g-h3n { font-size: var(--fs-xs); font-weight: 400; color: var(--ink-3); }

/* 三态计数小表（待审图片那一栏的右半） */
.g-mix { list-style: none; margin: 0; padding: 0; }
.g-mix li {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) 0;
  border-bottom: 1px solid var(--line);
}
.g-mix li:last-child { border-bottom: 0; }
.g-mix-k {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.g-mix b { margin-left: auto; font-size: var(--fs-lg); font-variant-numeric: tabular-nums; }
.g-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.g-dot.pending { background: var(--warning, #c98a00); }
.g-dot.approved { background: var(--success, #2f8f4e); }
.g-dot.rejected { background: var(--danger); }

/* ── 筛选条 ─────────────────────────────────────────────────────────────── */
.g-filters {
  display: flex;
  align-items: flex-end;
  gap: var(--s4);
  flex-wrap: wrap;
  margin-bottom: var(--s4);
}
.g-f {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.g-f input,
.g-f select {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
}
.g-f-narrow select { width: 96px; }
.g-f-check { flex-direction: row; align-items: center; gap: var(--s2); }
.g-f-actions { display: flex; gap: var(--s3); flex-wrap: wrap; }

/* ── 按钮 ───────────────────────────────────────────────────────────────── */
.g-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
  cursor: pointer;
}
.g-btn:hover:not(:disabled) { color: var(--ink); border-color: var(--ink-3); }
.g-btn-on { color: #fff; background: var(--brand); border-color: var(--brand); }
.g-btn-on:hover:not(:disabled) { color: #fff; border-color: var(--brand-ink); background: var(--brand-ink); }
.g-btn-danger { color: var(--danger); border-color: var(--line-2); }
.g-btn-danger:hover:not(:disabled) { border-color: var(--danger); background: var(--danger-ground); }
.g-btn-danger.armed {
  color: #fff;
  background: var(--danger);
  border-color: var(--danger);
}
.g-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.g-link {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  background: none;
  border: 0;
  cursor: pointer;
  padding: 0;
}
.g-link:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── 列表 / 行 ──────────────────────────────────────────────────────────── */
.g-list { list-style: none; margin: 0; padding: 0; }
.g-row {
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  padding: var(--s4);
  margin-bottom: var(--s3);
}
.g-row-h {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  flex-wrap: wrap;
  margin-bottom: var(--s2);
}
.g-author { font-size: var(--fs-sm); font-weight: 600; }
.g-target { font-size: var(--fs-sm); font-weight: 600; }
.g-stars { color: var(--warning, #c98a00); letter-spacing: 1px; }
.g-grow { flex: 1; }
.g-body {
  margin: var(--s2) 0;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.g-quote {
  padding-left: var(--s3);
  border-left: 2px solid var(--line-2);
}
.g-row-f {
  display: flex;
  align-items: center;
  gap: var(--s4);
  flex-wrap: wrap;
  margin-top: var(--s3);
}

.g-kind {
  flex: none;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 var(--s2);
}
.g-kind.question { color: var(--info-deep, var(--brand-ink)); border-color: var(--brand); }
.g-kind.answer { color: var(--success-deep); border-color: var(--success, var(--line-2)); }

.g-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--s1);
  flex: none;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 var(--s2);
}
.g-tag code { font-family: var(--font-mono); }
.g-tag-ok { color: var(--success-deep); border-color: var(--success, var(--line-2)); }
.g-tag-warn { color: var(--warning-deep, var(--danger)); border-color: var(--warning, var(--line-2)); }
.g-tag-danger { color: var(--danger); border-color: var(--danger); }
.g-tag-brand { color: var(--brand-ink); border-color: var(--brand); }
.g-tag-dim { color: var(--ink-3); }

/* ── 回复区（缩进）─────────────────────────────────────────────────────── */
.g-replies {
  list-style: none;
  margin: var(--s3) 0 0;
  padding: 0 0 0 var(--s5);
  border-left: 2px solid var(--line);
}
.g-reply { padding: var(--s3) 0; border-bottom: 1px dashed var(--line); }
.g-reply:last-child { border-bottom: 0; }

/* ── 举报处理行 ─────────────────────────────────────────────────────────── */
.g-handle {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
  margin-top: var(--s3);
  padding-top: var(--s3);
  border-top: 1px solid var(--line);
}
.g-note {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
  flex: 1 1 240px;
  min-width: 0;
}

/* ── 待审图片（缩略图网格）─────────────────────────────────────────────── */
.g-shots {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--s4);
}
.g-shot {
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  background: var(--surface);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.g-shot-img {
  height: 168px;
  background: var(--surface-2);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.g-shot-img img { max-width: 100%; max-height: 100%; object-fit: contain; display: block; }
.g-shot-meta {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s3) var(--s4);
  min-width: 0;
}
.g-shot-body {
  margin: var(--s2) 0 0;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  line-height: 1.55;
  max-height: 3.2em;
  overflow: hidden;
}
.g-shot-acts {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  padding: var(--s3) var(--s4);
  border-top: 1px solid var(--line);
  margin-top: auto;
}

/* ── 敏感词表 ───────────────────────────────────────────────────────────── */
.g-inline { display: flex; gap: var(--s3); margin-bottom: var(--s3); }
.g-inline input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: var(--s2) var(--s3);
  flex: 1;
  min-width: 0;
}
.g-tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
  margin-bottom: var(--s3);
}
.g-tbl th,
.g-tbl td {
  text-align: left;
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line);
  vertical-align: middle;
}
.g-tbl th { font-size: var(--fs-xs); color: var(--ink-3); font-weight: 500; }
.g-tbl code { font-family: var(--font-mono); }
.g-word { font-weight: 600; }
.g-td-act { text-align: right; }

/* ── 消息流（照 `NotifyPage.n-ev` 那套）────────────────────────────────── */
.g-stream { list-style: none; margin: 0; padding: 0; }
.g-ev {
  display: grid;
  grid-template-columns: 3px minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--s4);
  padding: var(--s4) var(--s3);
  border-bottom: 1px solid var(--line);
}
.g-ev:hover { background: var(--surface-2); }
.g-ev.unread { background: var(--brand-soft); }
.g-ev-hue { width: 3px; align-self: stretch; border-radius: var(--r1); }
.g-ev-main { display: flex; flex-direction: column; gap: var(--s2); min-width: 0; }
.g-ev-l1 { display: flex; align-items: baseline; gap: var(--s3); min-width: 0; flex-wrap: wrap; }
.g-ev-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.g-ev-l2 {
  font-size: var(--fs-sm);
  color: var(--ink-2);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.g-ev-l3 { font-size: var(--fs-xs); }
.g-ev-time {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--s2);
  font-size: var(--fs-xs);
  white-space: nowrap;
}
.g-ev-time b { font-variant-numeric: tabular-nums; color: var(--ink); }

/* ── 通用文字态 ─────────────────────────────────────────────────────────── */
.g-dim { color: var(--ink-3); font-size: var(--fs-xs); }
.g-dim code, .g-ev-l3 code { font-family: var(--font-mono); }
.g-hint {
  margin: var(--s2) 0 var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.65;
}
.g-hint code { font-family: var(--font-mono); }
.g-err { color: var(--danger); font-size: var(--fs-sm); }
.g-note { font-size: var(--fs-xs); color: var(--ink-2); }
.g-note.bad { color: var(--danger); }
.g-empty { color: var(--ink-3); font-size: var(--fs-sm); padding: var(--s6) 0; }
</style>
