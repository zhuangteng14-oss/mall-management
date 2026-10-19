<template>
  <section class="a-ac">
    <!-- ── ① 状态条 —— 与 `AuditPage.vue` / `StatsPage.vue` 同款（同域、最近邻），不另立一套 ──
         四个数都是「这一页自己能不能用」的判据（场景取到没有 / 勾了几个 / 有没有记录 / 权重合计
         是多少），不是业务读数 —— 业务读数在下面每一条记录里。 -->
    <header class="a-status">
      <div class="a-stat">
        <b>{{ scenarios.length }}</b>
        <span>预置场景</span>
      </div>
      <div class="a-stat">
        <b>{{ picked.length }}</b>
        <span>本次勾选</span>
      </div>
      <div class="a-stat">
        <b>{{ weightSum }}</b>
        <span>权重合计</span>
      </div>
      <div class="a-stat">
        <b>{{ total }}</b>
        <span>演练记录</span>
      </div>
      <div class="a-tz">
        <span class="a-chip-utc">库时钟</span>
        <span>记录时间 → <b>浏览器时区</b></span>
        <span class="a-tz-off">{{ tzLabel }}</span>
      </div>
    </header>

    <p v-if="flash.text" class="a-flash" :class="flash.kind">{{ flash.text }}</p>

    <!-- ── 演练环境被压测打空时的**恢复入口**（2026-10-18 用户批文）────────────────
         触发条件只有一条：`fire()` 的 catch（`/run` 报「库里挑不到可演练的商品」、
         或任何一轮没跑起来）。⚠️ 不常驻显示：环境正常时多一枚「重置」按钮会让人误以为
         它是个日常动作；它只在**已经失败**的那一屏出现，且点一次就会自动重试上一轮参数。 -->
    <div v-if="showReset" class="a-recover" role="alert">
      <span class="a-recover-t">
        演练环境可能已被压测打空（种子 SKU 库存为 0 / 父商品下架 / 商家冻结）。
        清场只删数据、删不回库存 —— 点右边复位后会自动重跑上一轮参数。
      </span>
      <button
        type="button"
        class="a-btn a-btn-danger"
        :disabled="resetting || running"
        @click="doResetEnvironment"
      >
        <IconGlyph name="ok" />{{ resetting ? '复位中…' : '一键重置演练商品库存' }}
      </button>
    </div>

    <!-- ── ② 固定一键：8 个预置场景，各一键 ──────────────────────────────────────
         用**当前**并发 / 持续秒数跑**这一个**场景（权重 100）。与下面「自定义」的区别只有两处：
         勾选固定成一个场景、参数仍取面板上的值 —— 这样「先把并发调到 300 再点秒杀风暴」也成立。 -->
    <h2 class="a-h2">
      固定一键
      <span class="a-h2n">点一下 = 单独跑这一个场景（按下面的并发 / 持续秒数 / 档位）</span>
    </h2>

    <p v-if="!scenarios.length" class="a-empty">
      {{ loadingScenarios ? '正在取场景清单…' : '场景清单没取到 —— 见上方提示。' }}
    </p>
    <div v-else class="a-presets">
      <button
        v-for="s in scenarios"
        :key="s.Key"
        type="button"
        class="a-preset"
        :disabled="running"
        @click="runPreset(s)"
      >
        <span class="a-preset-n"><IconGlyph name="attack" tone="muted" />{{ s.Name }}</span>
        <span class="a-preset-p">{{ s.Protections }}</span>
        <span class="a-preset-w">缺省权重 {{ s.DefaultWeight }}%</span>
      </button>
    </div>

    <!-- ── ③ 自定义编排：8 根权重滑条 + 总并发 + 持续秒数 + 开始 ───────────────── -->
    <h2 class="a-h2">
      自定义编排
      <span class="a-h2n">权重定的是「分几个线程」，不是「百分之几的请求」· 每场景保底 1 个线程</span>
    </h2>
    <div class="a-card">
      <div class="a-sims">
        <WeightSlider
          v-for="s in scenarios"
          :key="s.Key"
          :label="s.Name"
          :hint="s.Protections"
          :need="s.Needs"
          :model-value="weightOf(s.Key)"
          :disabled="running"
          @update:model-value="(v) => setWeight(s.Key, v)"
        />
      </div>

      <!-- ── ③b 压力档位（丁 · commit 10c）───────────────────────────────────────
           三选一：120 / 400 / 拉满。「档位」= **目标 QPS**（后端 `AttackEngine.Pacer` 真的按它
           限速），与上面的「总并发数」是**两把各管各的旋钮**：并发 = 同时几个在飞，档位 = 一秒
           发几个。只给并发不给档位的话，实际 QPS = 在途 ÷ 响应耗时，那是**后端速度决定的**一个数
           ⇒ 120 与 400 会打出同一个读数（那种档位是假旋钮）。
           ⚠️ 「拉满」= `targetQps=0` = 不限速：读数是这台机器 ＋ 后端的**真实上限**，不是我们设
              的一个数。引用出处：本机 200 并发 × 300 秒实测 **854.1 QPS**（`_work/c10-200x300.json`
              的 `actualQps`），后端 `AttackEngine.java` 的 `WORKER_THREADS` / `Pacer` 两处类注释
              引的也是这个数（「32 条足够推满 854 QPS 一档」「验收判据里的 ≥854」）。⚠️ 它是**本机**
              的读数，不是任何机器上都成立 → 页面上只当「量级参考」，不当承诺。 -->
      <h3 class="a-sub">
        压力档位
        <span class="a-subn">目标 QPS · 与并发是两把旋钮（拉满 = 不限速，读数为真实上限）</span>
      </h3>
      <div class="a-tiers">
        <button
          v-for="t in TIERS"
          :key="t.qps"
          type="button"
          class="a-tier"
          :class="{ 'is-on': tier === t.qps }"
          :aria-pressed="tier === t.qps ? 'true' : 'false'"
          :title="t.hint"
          :disabled="running"
          @click="tier = t.qps"
        >
          <b>{{ t.label }}</b>
          <span>{{ t.note }}</span>
        </button>
      </div>

      <!-- ── ③c 防护 / 注入开关（丁 · commit 10c；D 段 · commit 2 起分化）────────────
           ⚠️ 【D 段起：这五枚**不再一样**，逐档说清】后端 `ProtectionGate` 的口径是
              **4 键接闸（限流 / 熔断 / 重试 / 分池）+ 1 键记录位（降级）**
              （2026-10-15 · 阶段 2b 起。此前是「3 键接闸 + 1 键登记 TD（重试）+ 1 键记录位」，
              阶段 2b 把死锁重试真的落进了 `MerchantDowngradeService.relist`）：
                · 有闸的四枚 —— **拨了真的改读数**，这就是 D 段的演示点
                  （`retry` 那一枚演的是「同一个死锁：开着答 200、关着答 500」）；
                · `degrade` 一枚 —— 面板上**标灰 + 不可开关 + tooltip 说理由**
                  （理由见 `PROTECTIONS_NOT_WIRED` / `PROTECTION_NOT_WIRED_TIP`）。
              ⚠️ **禁止死控件**（用户 2026-10-15 逐字）：能点但无效果的开关一律不允许 ⇒
              这一枚一律 `disabled`（不是「拨得动但标未接通」——那是注入那组的处置，
              那组**真的会进引擎并写进记录**，这一枚不会改变任何读数，形状不同就该长得不同）。
           ⚠️ 五枚都照旧进 `drill_record`（后端 `AttackEngine.buildScenarioJson` 写进 `Result.Engine`）⇒ 「这一轮到底是按哪套开关跑的」事后可复现仍是它们的价值之一。
           ⚠️ 缺省 = 防护**全开** / 注入**全关**（后端白名单：表外的 key 一律 400，不静默忽略）。
           ⚠️ D3（2026-10-18）起四个注入**全部真接通**（见 `INJECTIONS_NOT_WIRED` 现为空）：
              `mqDown` 只拦发布侧、`dbSlow` 只延迟 SELECT、`jobDown` 只让定时任务整轮跳过。 -->
      <!-- 共享配置状态行（2026-10-18 · D3 手验修复）：开关不再是死控件，拨动即
           `POST /admin/observe/attack/config`（整组替换）。只有写入中 / 写入失败时才出现。 -->
      <p v-if="configSaving || configErr" class="a-window-note" :class="{ 'is-bad': configErr }">
        {{ configErr || '共享开关正在写入服务端…' }}
      </p>
      <h3 class="a-sub">
        防护开关
        <span class="a-subn">默认全开 · 限流 / 熔断 / 重试 / 分池已接闸，改读数；降级不可开关</span>
      </h3>
      <div class="a-switches">
        <button
          v-for="k in PROTECTIONS"
          :key="k"
          type="button"
          class="a-sw"
          :class="{ 'is-off': !protections.has(k), 'is-locked': !protectionWired(k) }"
          :aria-pressed="protections.has(k) ? 'true' : 'false'"
          :aria-disabled="protectionWired(k) ? 'false' : 'true'"
          :title="protectionWired(k)
            ? `${protectionLabel(k)}：${protections.has(k) ? '已开' : '已关'}（点一下拨动）—— 已接闸，读数会变`
            : `${protectionLabel(k)}：${protectionNotWiredTip(k)}`"
          :disabled="running || !protectionWired(k)"
          @click="toggleProtection(k)"
        >
          <i class="a-sw-dot" aria-hidden="true"></i>
          <span class="a-sw-n">{{ protectionLabel(k) }}</span>
          <span v-if="!protectionWired(k)" class="a-sw-locked">不可开关</span>
        </button>
      </div>

      <h3 class="a-sub">
        故障注入
        <span class="a-subn">默认全关 · 四枚都会改变读数（MQ 只拦发布侧、慢 SQL 只延迟 SELECT、调度停摆不中断在途）</span>
      </h3>
      <div class="a-switches">
        <button
          v-for="k in INJECTIONS"
          :key="k"
          type="button"
          class="a-sw"
          :class="{ 'is-off': !injections.has(k) }"
          :aria-pressed="injections.has(k) ? 'true' : 'false'"
          :title="`${injectionLabel(k)}：${injections.has(k) ? '已开' : '已关'}（点一下拨动）—— 这一路真接通了`"
          :disabled="running"
          @click="toggleInjection(k)"
        >
          <i class="a-sw-dot" aria-hidden="true"></i>
          <span class="a-sw-n">{{ injectionLabel(k) }}</span>
        </button>
      </div>

      <div class="a-run">
        <label class="a-f a-f-narrow">
          <span>总并发数</span>
          <input v-model.number="concurrency" type="number" :min="CONC_MIN" :max="CONC_MAX" :disabled="running" />
        </label>
        <label class="a-f a-f-narrow">
          <span>持续（秒）</span>
          <input v-model.number="durationSeconds" type="number" :min="DUR_MIN" :max="DUR_MAX" :disabled="running" />
        </label>
        <p class="a-run-note">
          子场景数与并发的关系是后端判的：<b>并发数 &lt; 勾选场景数 ⇒ 400</b>（不悄悄少跑几个）。
        </p>
        <button
          type="button"
          class="a-btn a-btn-on"
          :disabled="running || !canRun"
          @click="runCustom"
        >
          <IconGlyph name="ok" />{{ running ? '演练中…' : '开始' }}
        </button>
        <!-- [暂停]（commit 10c · A 档第 5 条）—— 走 `POST /admin/observe/attack/stop`：
             语义是「**停止派发 + 排空在途**」，**不掐断**这条 `/run` 连接 ⇒ 本轮不再发新请求，
             已经在途的那批跑完（3 秒宽限）就收口，等 `/run` 的**同一个** await 几秒内拿到结果
             （带 `Engine.Stopped=true`）。⇒ 本页不用换通路、也不用加超时。
             ⚠️ 只在 `running` 时出现：它是**本页自己发起的那一轮**的伴生按钮。页面半路刷新后，
                后端那一轮还在跑而本页已经没有 `running` ⇒ 这里也画不出暂停（后端那枚令旗是真的，
                只是本页不知道 —— 那种情况用 curl 打 `/attack/stop`，它照样停）。 -->
        <button
          v-if="running"
          type="button"
          class="a-btn"
          :disabled="stopping"
          @click="stopRun"
        >
          <IconGlyph name="no" />{{ stopping ? '叫停中…' : '停止' }}
        </button>
      </div>

      <p v-if="blockReason && !running" class="a-warn">{{ blockReason }}</p>

      <!-- 这里**两行不同出处**，别混着读（块 7 收尾把第二行从「估」换成了「读」）：
           ① 进度条 + 「已等 N 秒 / 预计 M 秒」：**估的**（`/run` 是同步请求，跑完才回；
              后端不给「还剩多久」这个面）⇒ 不写成「实时进度」，页面不知道后端跑到哪一步。
           ② 辅助条那三个数（已发 / 拦下 / 平均 QPS）：**后端读数**（`/attack/status`，1 秒一拍，
              见脚本里的 `prog` / `pollStatus`）—— 用户点名的 6 个数里，「已发多少条」「完成多少
              次拦截」前端**估不出来**，估一个出来就是编数据。 -->
      <div v-if="running" class="a-prog">
        <div class="a-prog-bar"><i :style="{ width: pct + '%' }"></i></div>
        <p class="a-prog-t">
          已等 <b>{{ elapsedSec }}</b> 秒 / 预计 <b>{{ plannedSeconds }}</b> 秒
          —— 后端<b>跑完才回</b>，这条进度条是按已用时间估的，不是后端读数（另加 5 秒 Redis 断开阶段）。
        </p>
        <!-- ⚠️ 平均 QPS 的分母用**后端**的 `ElapsedMs`（不是上面那截墙钟估出来的秒数）：
             「已发」与「已跑秒数」同源才算得自洽 —— 拿估的秒数去除真读数，会在演练收尾那一拍
             算出一个假的 QPS（分子已经不动了、估的分母还在涨）。 -->
        <p class="a-prog-t">
          已发 <b>{{ dash(prog.sent) }}</b> 条 · 拦下 <b>{{ dash(prog.blocked) }}</b> 条 · 平均 <b>{{ avgQps }}</b> QPS
          <span class="a-dim">（后端读数 · 1 秒一拍{{ prog.err ? ' · 读数异常：' + prog.err : '' }}）</span>
        </p>
      </div>
    </div>

    <!-- ── ③c 实时预览（批四 · 第一批 1）────────────────────────────────────────
         插在「自定义编排」下方、「演练记录」上方：跑之前先看见三条流现在是活的，
         跑完也能在这一页确认「刚才那轮压出来的东西长什么样」，不必来回切页。
         ⚠️ 三列都是**纯文字滚动**（不 import 任何 `components/charts/*`）—— 与块 7 那条
            「攻击页不画图」的裁决不冲突：这里展示的是流本身，不是图表。
         ⚠️ 流由子组件自己开、自己关（`onBeforeUnmount` 三条一起 close）；本页不持有句柄。 -->
    <h2 class="a-h2">
      实时预览
      <span class="a-h2n">指标 / 订单 / 日志三条流 · 只读滚动 · 切走本页自动断开</span>
    </h2>
    <LivePreview />

    <!-- ── ④ 演练记录（`drill_record` 时间倒序）─────────────────────────────── -->
    <h2 class="a-h2">
      演练记录
      <span class="a-h2n">时间倒序 · 共 {{ total }} 条</span>
    </h2>

    <form class="a-filters" @submit.prevent="loadRecords">
      <div class="a-f-actions">
        <button type="submit" class="a-btn" :disabled="loadingRecords">
          <IconGlyph name="ok" />{{ loadingRecords ? '载入中…' : '刷新' }}
        </button>
      </div>
    </form>

    <p v-if="recErr" class="a-err">{{ recErr }}</p>

    <ol v-if="records.length" class="a-stream">
      <li v-for="r in records" :key="r.Id" class="a-ev" :class="{ on: openId === r.Id }">
        <button type="button" class="a-ev-head" @click="toggle(r)">
          <span class="a-ev-main">
            <span class="a-ev-l1">
              <code class="a-tag">{{ r.Name || '（未命名）' }}</code>
              <span class="a-ev-target">
                总 {{ num(r.TotalRequests) }} · 拦下 {{ num(r.BlockedRequests) }} · 失败 {{ num(r.FailedRequests) }}
              </span>
            </span>
            <span class="a-ev-l2">
              <span class="a-who-chip">{{ r.OperatorId || '（认不出身份）' }}</span>
              <span class="a-dim">角色 {{ roleLabel(r.OperatorRole) }}</span>
              <span class="a-dim">耗时 {{ fmtMs(r.DurationMs) }}</span>
            </span>
          </span>
          <span class="a-ev-time">
            <b>{{ toLocal(r.CreatedAt) }}</b>
            <span class="a-dim">库内 {{ r.CreatedAt }}</span>
          </span>
          <span class="a-ev-caret">{{ openId === r.Id ? '▾' : '▸' }}</span>
        </button>

        <div v-if="openId === r.Id" class="a-ev-body">
          <!-- ⚠️ `Result` 为 null 只可能是**后端解析失败**（`DrillRecordService.split` 那一条：
               解析不了就把原串挂 `ResultRaw` + 标 `ResultParseError`）。这一支**单独画**，
               不并进下面那条正常渲染 —— 否则一条坏行会画成一个「0 请求 0 拦下」的成功演练。 -->
          <p v-if="r.Result == null" class="a-corrupt">
            ⚠️ 这条记录的结果读不出来：<code>{{ r.ResultParseError || 'Result 为空' }}</code>
            <template v-if="r.ResultRaw"> —— 原串：<code class="a-rawwire">{{ r.ResultRaw }}</code></template>
          </p>

          <template v-else>
            <div class="a-res">
              <div class="a-res-cell"><span>总请求</span><b>{{ num(r.TotalRequests) }}</b></div>
              <div class="a-res-cell"><span>拦下</span><b class="a-ok">{{ num(r.BlockedRequests) }}</b></div>
              <div class="a-res-cell"><span>失败</span><b :class="{ 'a-bad': Number(r.FailedRequests) > 0 }">{{ num(r.FailedRequests) }}</b></div>
              <div class="a-res-cell"><span>耗时</span><b>{{ fmtMs(r.DurationMs) }}</b></div>
            </div>

            <!-- 回放（块 7 收尾）—— **入口就在这一行**：它拿的是这一行的 `BatchId`（后端
                 `DrillRecordDao.COLUMNS` 里那一列，用户 2026-10-15 裁「甲」补的）。
                 ⚠️ 存量记录 / 引擎没写批次的那些行 `BatchId` 是 `null` ⇒ 按钮**置灰**并说清
                    为什么，不画一个点了没反应的按钮。 -->
            <div class="a-rp-in">
              <span class="a-dim">本批事件台账</span>
              <code class="a-tag">{{ r.BatchId || '（这一行没有批次）' }}</code>
              <span class="a-dim">· {{ num(r.EventCount) }} 条</span>
              <button
                type="button"
                class="a-btn"
                :disabled="!r.BatchId || r.HasData === false || replaySession.loading"
                :title="r.HasData === false ? '这批台账已过保留期被清理，无法回放' : '读这批台账并按原时间戳回放'"
                @click="startReplay(r)"
              >
                <IconGlyph name="ok" />{{ replaySession.loading && replaySession.batchId === r.BatchId ? '载入中…' : '回放' }}
              </button>
              <span v-if="!r.BatchId" class="a-dim">引擎当时没写批次 ⇒ 回放不到（存量记录）</span>
              <span v-else-if="r.HasData === false" class="a-bad">
                该批次历史数据已过期被清理（台账 0 条）⇒ 无法回放
              </span>
              <span v-else-if="replaySession.err && replaySession.batchId === r.BatchId" class="a-bad">
                回放装载失败：{{ replaySession.err }}
              </span>
            </div>

            <h3 class="a-sub">场景配置</h3>
            <p class="a-kv">
              并发 <b>{{ cfgOf(r).c }}</b> · 持续 <b>{{ cfgOf(r).d }}</b> 秒 · 勾选
              <template v-if="cfgOf(r).s.length">
                <code v-for="x in cfgOf(r).s" :key="x.k" class="a-chip" :title="x.k">{{ scenarioLabel(x.k) }}={{ x.w }}%</code>
              </template>
              <template v-else>—（记录里没有勾选清单）</template>
            </p>

            <h3 class="a-sub">各场景读数</h3>
            <table class="a-tbl">
              <thead>
                <tr><th>场景</th><th>请求</th><th>放行</th><th>拦下</th><th>失败</th></tr>
              </thead>
              <tbody>
                <tr v-for="p in listOf(r, 'Scenarios')" :key="p.Key">
                  <td class="a-tk">
                    <b>{{ p.Name }}</b>
                    <span class="a-dim2">{{ p.Protections }}</span>
                  </td>
                  <td>{{ num(p.Requests) }}</td>
                  <td>{{ num(p.Passed) }}</td>
                  <td class="a-ok">{{ num(p.Blocked) }}</td>
                  <td :class="{ 'a-bad': Number(p.Failed) > 0 }">{{ num(p.Failed) }}</td>
                </tr>
                <tr v-if="!listOf(r, 'Scenarios').length">
                  <td colspan="5" class="a-dim2">（这一轮没有分场景读数）</td>
                </tr>
              </tbody>
            </table>

            <!-- 库存：秒杀场景的判据**在库存上**，不在「拦下」上 —— 这一点写进页面，
                 否则读数会被读反（`deductStocks` 是 fail-open，超卖那部分照常 success:true）。 -->
            <h3 class="a-sub">库存（秒杀场景的判据）</h3>
            <p class="a-kv">
              <code>{{ stockOf(r).SkuCode || '—' }}</code> ·
              演练前 <b>{{ dash(stockOf(r).Before) }}</b> → 演练后 <b>{{ dash(stockOf(r).After) }}</b>
              <template v-if="stockOf(r).Deducted != null">（扣了 <b>{{ stockOf(r).Deducted }}</b>）</template>
              · 基线 <b>{{ dash(stockOf(r).Baseline) }}</b>
              · 秒杀放行单 <b>{{ num(stockOf(r).SeckillOrders) }}</b>
            </p>

            <h3 class="a-sub">Redis 断开（阶段 B）</h3>
            <p class="a-kv">
              <template v-if="rdOf(r).Ran">
                单独跑了 <b>{{ rdOf(r).Seconds }}</b> 秒 ·
                <template v-if="rdOf(r).Injected === true">
                  已注入故障、<template v-if="rdOf(r).Restored">已恢复</template><template v-else>未恢复</template>
                </template>
                <template v-else-if="rdOf(r).Injected === false">未注入（{{ rdOf(r).Note }}）</template>
                <template v-else>注入状态未记</template>
              </template>
              <template v-else>这一轮没勾选 Redis 断开。</template>
            </p>

            <h3 class="a-sub">攻击数据标记</h3>
            <p class="a-kv">
              订单业务串 <code>{{ mkOf(r).OrderIdPrefix || '—' }}</code> ·
              评论正文 <code>{{ mkOf(r).CommentContentPrefix || '—' }}</code> ·
              本轮序号 <b>{{ num(mkOf(r).RunSeq) }}</b>
              <template v-if="samplesOf(r).length">
                · 样本 <code>{{ samplesOf(r).join(' ') }}</code>
              </template>
            </p>

            <h3 class="a-sub">备注</h3>
            <ul class="a-notes">
              <li v-for="(n, i) in listOf(r, 'Notes')" :key="i">{{ n }}</li>
              <li v-if="!listOf(r, 'Notes').length" class="a-dim2">（这一轮没有备注）</li>
            </ul>
          </template>
        </div>
      </li>
    </ol>
    <p v-else-if="!loadingRecords && !recErr" class="a-empty">还没有任何演练记录。</p>

    <!-- 块 7 收尾 · P1：标准分页（默认每页 20，可选 10/20/50/100），由后端 `total` 驱动。 -->
    <Pager
      :total="total"
      :limit="f.limit"
      :offset="f.offset"
      :sizes="[10, 20, 50, 100]"
      unit="条"
      label="演练记录"
      :disabled="loadingRecords"
      @change="onRecordsPage"
    />

    <!-- ── ⑤ 清场（破坏性 · commit 9 补上 8b 遗留的按钮）─────────────────────────
         ⚠️ 本页**唯一**会真删数据的动作，也是**唯一**两段式确认的按钮（同 `OrderPage.vue`
            的 `armed`）：第一次点只上膛（按钮转危险色 + 页顶一行提示），第二次点才发请求。
         ⚠️ 它清的是**演练标记出来的业务数据**（`SIM-` 单号前缀 **OR** `Source='attack'`，
            后端 `ObservedEventCleanupDao` 判的），**不含**上面那份 `drill_record` 自己。 -->
    <h2 class="a-h2">
      清理并恢复环境
      <span class="a-h2n">真删 · 不可撤销 · 删演练写进业务表的行，并把种子库存 / 在架 / 冻结状态复位</span>
    </h2>
    <div class="a-card">
      <p class="a-note">
        按演练留下的标记清（`SIM-` 开头单号 **OR** `Source='attack'` 两种一起判，缺一会漏）：
        订单 / 评论 / 事件台账等业务行会被**真删**；随后把种子 SKU 的库存、父商品的在架状态、
        演示商家的冻结状态复位（判据 `SEED-%` / `Source='seed'`，真实业务行不动）。
        清完回显**删了几行 + 复位了几行**两部分。
      </p>
      <div class="a-f-actions">
        <button
          type="button"
          class="a-btn"
          :class="{ 'a-btn-danger': armedClean }"
          :disabled="cleaning || running"
          @click="doCleanup"
        >
          <IconGlyph name="no" />
          {{ cleaning ? '清理并恢复中…' : armedClean ? '再点一次：确认清理并恢复（不可撤销）' : '清理并恢复环境' }}
        </button>
      </div>
    </div>

    <!-- ── ⑥ 事件台账存储水位 / 彻底清空（2026-10-19）──────────────────────────
         与 ⑤ 的区别写在卡片里：⑤ 只删**演练标记**命中的行，⑥ 的按钮删**整张**
         `observed_event`（含日常事件）。⚠️ ⑥ 的按钮同样两段式确认（真删、不可撤销）。
         水位熔断由后端在 `/run` 开跑前与定时 job 里执行，本卡片只**读**当前刻度；
         触发时 `/run` 的响应会带 `EvictionWarning`，由 `fire()` 原样显示在页顶。 -->
    <h2 class="a-h2">
      可观测事件台账存储
      <span class="a-h2n">超过高水位时，开跑前自动淘汰最旧数据 —— 牺牲历史，保全当下</span>
    </h2>
    <div class="a-card">
      <p v-if="storage.err" class="a-err">水位读取失败：{{ storage.err }}</p>
      <p class="a-kv">
        当前 <b>{{ storage.count == null ? '—' : storage.count.toLocaleString() }}</b> 行
        / 高水位 <b>{{ storage.highWatermark.toLocaleString() }}</b> 行
        · 安全线 <b>{{ storage.targetWatermark.toLocaleString() }}</b> 行
        · 保留最近 <b>{{ storage.retentionDays }}</b> 天
        · 每批 {{ storage.batchSize.toLocaleString() }} 行
      </p>
      <div class="a-water" role="img" :aria-label="`台账水位 ${storagePct}%`">
        <span
          class="a-water-fill"
          :class="{ 'is-high': storage.above }"
          :style="{ width: storagePct + '%' }"
        ></span>
      </div>
      <p v-if="storage.above" class="a-warn">
        ⚠️ 当前已超过高水位：下一次「开始演练」会先按时间淘汰最旧数据，直到回到安全线以下。
      </p>
      <div class="a-f-actions">
        <button
          type="button"
          class="a-btn"
          :disabled="loadingStorage || purging || running"
          @click="loadStorage"
        >
          {{ loadingStorage ? '读取中…' : '刷新水位' }}
        </button>
        <button
          type="button"
          class="a-btn"
          :class="{ 'a-btn-danger': armedPurge }"
          :disabled="purging || cleaning || running"
          @click="doPurge"
        >
          <IconGlyph name="no" />
          {{ purging ? '彻底清空中…' : armedPurge ? '再点一次：确认彻底清空（不可撤销）' : '彻底清空可观测历史数据' }}
        </button>
      </div>
      <p class="a-note">
        与上面的「清理并恢复环境」不同：那个只删**演练标记**命中的行，这个删**整张**
        `observed_event`（含日常事件）。后端按批删除，积压多时需要几十秒，请勿重复点击。
      </p>
    </div>
  </section>
</template>

<!--
  攻击模拟器页（块 7 · commit 5）—— `/admin/observe/attack-sim` 的真页（原走 `StubPage.vue`）。

  【它仍然是块 7 四页里**唯一不画图**的一页 —— 但批四起多了一块「文字预览」】
  《块7参考》§1.1 逐字：「攻击页 = AttackConsole.vue，只做**编排 + 跑 + 演练记录**；
  三面板已独立成页（D2 后期改判），不塞进攻击页」。⇒ 本页**不 import 任何图表组件**
  （`components/charts/*` 一个都不引），指标 / 状态流转 / 日志**图表**仍在另外三页。
  ⚠️ 那条「不塞三面板」是**用户 2026-10-13 的改判**（原 D2 = 三面板同屏当总控台）；
     别按更早的方案把**图表**合回来。
  ⚠️ **批四 · 第一批（2026-10-18 用户批文）另加**：③c 的 `LivePreview` —— 复用三条 SSE 流，
     三列**纯文字滚动**（不暂停、不画图、不筛选）。它与上面那条裁决不冲突：用户要的是
     「跑的时候能看见流在动」，不是把三张图表塞回来。

  【它消费的后端端点，全部经 `api/observe.js`】
    · `attackScenarios()` —— 8 个预置场景（Key / Name / Protections / Needs / DefaultWeight）；
    · `runAttack(p)`      —— 跑一轮（**同步跑到结束**，见下）；
    · `attackRecords(p)`  —— 演练记录（`Result` 长文本已由后端拆成字段）；
    · `cleanupAttack()`   —— 清理并恢复（commit 9 补的按钮，破坏性、两段式确认）；
    · `stopAttack()`      —— 叫停正在跑的那一轮（commit 10c 的 `[暂停]`）；
    · `attackStatus()`    —— 演练期间的实时读数（块 7 收尾，1 秒一拍）；
    · `resetAttackEnvironment()` —— 复位演练环境（2026-10-18，失败态按钮 + 复位后自动重跑）；
    · `attackStorage()` / `purgeAttackEvents()` —— 事件台账水位 / 彻底清空（2026-10-19，⑥ 区）；
    · `openMetricsStream` / `openOrderStream` / `openEventStream` —— 批四 ③c 预览的三条 SSE
      （在 `LivePreview.vue` 里开与关，本页只挂组件）。
  ⚠️ 本页**不出现任何后端路径字面量**（那条判据可 grep 自动化：路径只许出现在
     `api/endpoints.js`）。七个函数名即全部后端面。

  【commit 10c 给本页加的三样东西（丁 · 开关面板）】
    · **压力档位**（120 / 400 / 拉满）—— 值就是 `targetQps`；与「总并发数」是两把各管各的旋钮，
      见 `TIERS` 的注释（「拉满」给的是**不限速**，854 是本机实测的参考量级，不是设出来的目标）；
    · **5 个防护开关 + 3 个注入开关** —— 防护那组自 **D 段 commit 2** 起有真闸、**阶段 2b 起四键**
      （限流 / 熔断 / 重试 / 分池真生效），只剩 `degrade`（记录位）**画成不可开关** ⇒ 拨得动的
      那些会改读数、拨不动的那一枚不下发；两组都进 `drill_record` ⇒ 「这一轮按哪套开关跑的」事后可复现；
    · **`[暂停]`** —— 走 `/attack/stop`，语义是「**停止派发 + 排空在途**」而不是掐断：本轮不再发
      新请求，在途那批走 3 秒宽限，等 `/run` 的**同一个** await 几秒内拿到结果
      （`Engine.Stopped=true`）⇒ 本页不换通路、不加超时。
    ⚠️ 中文名（限流 / 熔断 / 降级 / 重试 / 分池 等）**不写在本页** —— 去
       `config/labels/observe-labels.js` 取（那条「可观测域的中文只有一份」的判据见该文件头）。

  【⚠️ 跑一轮是**同步**请求 —— 这是本页最容易写错的一处】
  `AttackController#run` 阻塞到演练跑完（几十秒）才回。两条后果都落在本页：
    ① **不给超时**：`api/observe.js#runAttack` 已经内置 `durationSeconds*1000 + 60s`
       （`http.post` 默认 10 秒会把 60 秒的演练当场掐断，而后端照跑完照落库，页面上只剩一条超时）
       ⇒ **本页不再传 `timeout`**，传了就是把这个算式抄第二遍。
    ② **「还剩多久」后端仍然不给，「已经发了多少」块 7 收尾给了**：本仓没有任务表 ⇒ 「跑到哪
       一步了」这个面不存在，进度条只能按「已用时间 / 预计时长」估（见 `pct`），上限压在 96%
       —— 它是「还在跑」的指示器、不是读数；辅助条那三个数才是读数（见 `prog` / `pollStatus`）。

  【跑完之后为什么不再自己拼一行】
  `run` 的响应与 `attackRecords` 的行**同源**（都是 `AttackEngine.DrillRun.result()`），但响应里
  **没有** `CreatedAt` / `OperatorId` / `OperatorRole` / `Scenario`（那四列是落库时才有的）
  ⇒ 拼一行出来只会得到一条「时间空着」的假记录。改为：跑完 `loadRecords()` 再按 `Id` 展开
  —— 多一次往返，换掉一整条「本地拼行」的重复渲染路径（本页只有一条渲染路径）。

  【时区 —— 与 `AuditPage.vue` 同一口径、同一暗礁】
  `drill_record.created_at` 由 DAO 用 `DATE_FORMAT(…)` 读成**无时区文本**，库内是 **UTC**
  ⇒ 显示时按 UTC 解析、按浏览器时区渲染（`toLocal` 末尾那个 `Z` 不能省，理由与
  `AuditPage.vue#toLocal` 逐字相同）。
-->

<script setup>
/* eslint-disable no-undef */ /* 理由见 `../../../components/charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
// 回放是**跨页面**的会话（2026-10-17 重做）：本页只负责「装载 + 跳去日志页」，
// 播放时钟、行、横幅都在 `api/replay-session.js` 与日志页那条链上。
import { useRouter } from 'vue-router'
// ⚠️ 三层 `../`（不是管理端其它页的两层）：本页在 `views/admin/observe/`，比 `views/admin/` 深一层。
import {
  attackRecords, attackScenarios, attackStatus, attackStorage, cleanupAttack,
  purgeAttackEvents, readAttackConfig, resetAttackEnvironment, runAttack, stopAttack,
  updateAttackConfig,
} from '../../../api/observe'
import { replaySession, startReplaySession } from '../../../api/replay-session'
import LivePreview from '../../../components/observe/LivePreview.vue'
import WeightSlider from '../../../components/observe/WeightSlider.vue'
import IconGlyph from '../../../components/ui/IconGlyph.vue'
import Pager from '../../../components/Pager.vue'
// commit 9 收敛：场景 key → 中文（`config/labels/observe-labels.js` 的 `SCENARIO_LABEL`）+ 角色码 → 中文。
// ⚠️ commit 10c 起本页还吃两张新表：`PROTECTIONS`/`PROTECTION_LABEL`（五个防护开关）与
//    `INJECTIONS`/`INJECTION_LABEL`（三个注入）—— 连同声明序一起去 `labels/` 取，本页**不写死**
//    任何开关中文（那条判据见 `observe-labels.js` 文件头：可观测域的中文只有一份，在那）。
import {
  INJECTIONS,
  injectionLabel,
  PROTECTIONS,
  protectionLabel,
  protectionNotWiredTip,
  protectionWired,
  scenarioLabel,
} from '../../../config/labels/observe-labels'
import { roleLabel } from '../../../config/labels/user-labels'

// ── 入参上下限（**与后端逐字对齐**）──────────────────────────────────────────
// 后端 `AttackEngine.clampInt` 的口径：缺省 100 / 1..500（并发）、缺省 60 / 1..600（秒）。
// ⚠️ 前端**不重复实现**那套 clamp（抄第二遍迟早与后端走岔），这里只在**发请求前**把用户
//    填的空 / 小数 / 越界值收成一个「像样的数」，真判据仍然后端说了算（它回 400 就照 400 显示）。
const CONC_MIN = 1
// 2026-10-15（块 7 · commit 10c-2）：500 → 4000，跟上后端 MAX_CONCURRENCY ——
// 提在途量是瓶颈定位/演示线的一环，页面若还卡在 500，那条线在界面上就走不到头。
const CONC_MAX = 4000
const CONC_DEFAULT = 100
const DUR_MIN = 1
const DUR_MAX = 600
const DUR_DEFAULT = 60

/** 跑一轮的预计耗时里，`durationSeconds` 之外的那一段（Redis 断开阶段 B 的 5 秒）。 */
const REDIS_DOWN_GRACE_MS = 5000

/**
 * 三个**压力档位**（丁 · commit 10c）—— 值就是后端 `/attack/run` 的 `targetQps`。
 *
 * ⚠️ 【`0` 是「不限速」而不是「0 QPS」】后端 `AttackEngine` 的口径是 `targetQps <= 0` ⇒
 *    `Pacer.reserve()` 恒返 0、限速器形同不存在，实际上限交给在途许可 —— 即「拉满」。
 *    ⇒ 三个档是 `120 / 400 / 0(拉满)`，界面上把 `0` 显示成「拉满」，**不显示成 0**。
 *
 * ⚠️ 【「854」是**本机实测**、不是我们设的目标值】档位里唯一一个带数字的第三档给的是
 *    **不限速**，854 是「不限速时这台机器 ＋ 后端读出来多少」的**参考量级**，出处是
 *    `_work/c10-200x300.json` 的 `actualQps`（200 并发 × 300 秒 = 854.1）；后端
 *    `AttackEngine.WORKER_THREADS` 与 `Pacer` 两处类注释引的也是这个数。⇒ 它写在 `note` 里
 *    当量级参考，**不写成目标值**（写成目标就成了我们自己编的一个数）。
 *
 * ⚠️ 【缺省我选了温和档 120 —— 这是本笔**有意改的一处默认行为**】改前本页**根本不发**
 *    `targetQps`（那个入参是 commit 10c 才有的）⇒ 后端缺省 `0` = 拉满，也就是「按一下开始
 *    就是满速」。缺省挪到 120 是为了让「随手点一下」不先把后端打满 —— 要复现改前的读数，
 *    点「拉满」。这条如实登记在 commit 报文里，不是笔误。
 */
const TIERS = [
  { qps: 120, label: '120', note: '温和 · 真限速', hint: '目标 120 QPS（后端限速器真的按它隔开）' },
  { qps: 400, label: '400', note: '中档 · 真限速', hint: '目标 400 QPS（后端限速器真的按它隔开）' },
  { qps: 0, label: '拉满', note: '不限速 · 本机实测 854', hint: '不限速：读数是这台机器 ＋ 后端的真实上限，不是我们设的一个数（本机 200 并发实测 854 QPS）' },
]

/** 回放装好后跳到日志页（用户 2026-10-17：回放的画面在日志页，不在本页的内联面板）。 */
const router = useRouter()

const scenarios = ref([])
const loadingScenarios = ref(false)
/** `Key → 权重`。缺省值来自后端（见 `loadScenarios`），本页不写死一套分布。 */
const weights = reactive({})
const concurrency = ref(CONC_DEFAULT)
const durationSeconds = ref(DUR_DEFAULT)

// ── 丁：档位 + 两组开关（commit 10c）──────────────────────────────────────────
/** 当前档位 = `targetQps`。缺省取 `TIERS[0]`（120）—— 为什么不是「拉满」见 `TIERS` 的第三条 ⚠️。 */
const tier = ref(TIERS[0].qps)
/**
 * 开着的**防护**（缺省**全开** —— 用户 2026-10-14 定稿）。
 * ⚠️ 用 `Set` 而不是对象字面量：判据是「含不含」，写成一个 `{rateLimit: true, …}` 就要靠
 *    `!!x[k]` 去读，多一层可以写错的地方（同 `EventLog.vue#activeKinds`）。
 */
const protections = ref(new Set(PROTECTIONS))
/** 开着的**注入**（缺省**全关**）。 */
const injections = ref(new Set())

const running = ref(false)
/** 叫停请求在途（按钮显示「叫停中…」并禁用，避免连点出两发）。 */
const stopping = ref(false)
const flash = reactive({ text: '', kind: '' })
const plannedSeconds = ref(DUR_DEFAULT)
const elapsedMs = ref(0)
let expectedMs = 0
let startedAt = 0
let timer = null
/**
 * `/attack/status` 的**当前一拍**读数（块 7 收尾的辅助条）。
 * ⚠️ 初值是 `null` 而**不是 0** —— 第一拍回来之前界面画「—」（`dash()`），画 0 是在报一个
 *    没读到过的数（`api/observe.js#attackStatus` 那条「别把缺值当成读数」的同一条理由）。
 * ⚠️ 这里面**没有 `running`**：本页的 `running` 只归 `fire()` 的 `finally` 管，不跟后端那个
 *    `Running` 字段抢写（那会造出第二个「谁说了算」的地方）。
 */
const prog = reactive({ sent: null, blocked: null, elapsedMs: 0, err: '' })
let statusTimer = null
/** 这一页当前显示的 `running` 是「切回来时从后端恢复」的（不是本页 `fire()` 发起的）。 */
const restoredRun = ref(false)

/* ── 跨路由持久化（用户 2026-10-17 报「切页后整页重置」）──────────────────────
 * 两件不同的东西，存两把键：
 *   ① `FORM_KEY`：表单（权重 / 并发 / 秒数 / 档位 / 两组开关）—— 用户拖好的值不该因为
 *      去别的页看了一眼就回到缺省；
 *   ② `RUN_KEY`：**本页发起的那一轮**的开始信息（计划秒数 / 起点墙钟）—— 只靠
 *      `/attack/status` 恢复不了「预计跑多久」，它只回 `ElapsedMs`（见 `api/observe.js`）。
 *      ⚠️ 它**不是**后端账本：只用于本页画进度条，后端 `Running` 才是唯一判据。
 * 用 `sessionStorage`（不是 `localStorage`）：这些是**这一次打开管理端**的事，关掉标签页就该忘。
 */
const FORM_KEY = 'mall.observe.attack.form.v1'
const RUN_KEY = 'mall.observe.attack.run.v1'

function readStore(key) {
  try {
    const raw = sessionStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}
function writeStore(key, value) {
  try {
    sessionStorage.setItem(key, JSON.stringify(value))
  } catch (e) {
    /* 隐私模式 / 配额满：持久化是增强，不是主路径；失败不打断页面。 */
  }
}
function dropStore(key) {
  try {
    sessionStorage.removeItem(key)
  } catch (e) {
    /* 同上 */
  }
}

/** 把表单恢复成上次离开这一页时的样子（在 `loadScenarios()` 之前跑，缺省权重才不会盖掉它）。 */
function restoreForm() {
  const v = readStore(FORM_KEY)
  if (!v || typeof v !== 'object') return
  if (v.weights && typeof v.weights === 'object') {
    for (const [k, n] of Object.entries(v.weights)) {
      const x = Number(n)
      if (Number.isFinite(x) && x >= 0) weights[k] = x
    }
  }
  if (Number.isFinite(Number(v.concurrency))) concurrency.value = normInt(v.concurrency, CONC_MIN, CONC_MAX, CONC_DEFAULT)
  if (Number.isFinite(Number(v.durationSeconds))) durationSeconds.value = normInt(v.durationSeconds, DUR_MIN, DUR_MAX, DUR_DEFAULT)
  if (TIERS.some((t) => t.qps === Number(v.tier))) tier.value = Number(v.tier)
  if (Array.isArray(v.protections)) protections.value = new Set(v.protections.filter((k) => PROTECTIONS.includes(k) && protectionWired(k)))
  if (Array.isArray(v.injections)) injections.value = new Set(v.injections.filter((k) => INJECTIONS.includes(k)))
}
function persistForm() {
  writeStore(FORM_KEY, {
    weights: { ...weights },
    concurrency: concurrency.value,
    durationSeconds: durationSeconds.value,
    tier: tier.value,
    protections: [...protections.value],
    injections: [...injections.value],
  })
}

const records = ref([])
const total = ref(0)
const loadingRecords = ref(false)
const recErr = ref('')
const f = reactive({ limit: 20, offset: 0 })   // 块 7 收尾 · P1：标准分页（默认每页 20）
const openId = ref(null)

// ── 清场（破坏性 · commit 9 补上 8b 遗留的按钮）──────────────────────────────
/** 「上膛」态：点了第一次**不发请求**（见 `doCleanup`）。 */
const armedClean = ref(false)
const cleaning = ref(false)

// ── 事件台账存储水位 / 彻底清空（2026-10-19）────────────────────────────────
/**
 * 当前水位快照。`count` 初值 `null`（**不是 0**）—— 第一拍回来之前页面画「—」，
 * 画 0 是在报一个没读到过的数（与 `prog` 同一条口径）。
 * 其余键的缺省值与后端 `application.properties` 同源，读不到时页面仍能画出刻度。
 */
const storage = reactive({
  count: null,
  highWatermark: 300000,
  targetWatermark: 250000,
  retentionDays: 3,
  batchSize: 5000,
  above: false,
  err: '',
})
const loadingStorage = ref(false)
/** 「上膛」态：与 `armedClean` 同款（`confirm()` 在本仓不可用，见 `doCleanup` 注释）。 */
const armedPurge = ref(false)
const purging = ref(false)

// ── 演练环境复位（2026-10-18）────────────────────────────────────────────────
/** `/run` 失败后的恢复入口是否显示（只在失败那一屏出现，成功后收起）。 */
const showReset = ref(false)
const resetting = ref(false)
/** 上一次 `fire()` 的参数 —— 复位成功后**自动重跑同一轮**（用户批文第二节第 2 条）。 */
let lastRun = null

// ── 编排状态 ─────────────────────────────────────────────────────────────────
const weightOf = (key) => Number(weights[key]) || 0
function setWeight(key, v) {
  weights[key] = Number(v) || 0
}

// ── 丁：开关的拨动（commit 10c）──────────────────────────────────────────────
/**
 * 防护开关**有没有真闸**（阶段 2b 起只剩 `degrade` 这一枚）—— 判据取自 `PROTECTIONS_NOT_WIRED`（后端
 * `ProtectionGate.WIRED_KEYS` 的镜像，见 `observe-labels.js` 那张表）。页面**不自己判断**
 * 哪几枚没接闸（那是后端事实，前端写第二遍迟早对不上）。
 * ⚠️ D3（2026-10-18）起四个**注入**全部真接通，本页不再有「注入是否接通」的第二个判据。
 */
const protectionOn = (key) => protectionWired(key)

/** 拨一枚防护开关。写法与 `EventLog.vue#toggleKind` 同款（`new Set(旧)` → 整体替换）。
 *  ⚠️ 没接闸的那些枚**直接不响应**（按钮已经是 `disabled`，这里再兜一道）：这条守卫是
 *  「禁止死控件」的**落点** —— 让它在代码里**不可能**被拨动，而不是只在模板上画灰
 *  （模板一改就会漏，比如将来有人把它挪进一个 `v-if` 分支里）。 */
function toggleProtection(key) {
  if (!protectionOn(key)) return
  const next = new Set(protections.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  protections.value = next
  pushConfig()
}

/** 拨一枚注入开关（同上）。D3 起四枚都真接通，拨动即生效（后端 `AttackSwitchConfig` 共享配置）。 */
function toggleInjection(key) {
  const next = new Set(injections.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  injections.value = next
  pushConfig()
}

// ── 共享配置读写（2026-10-18 · 修「关掉 mqDown 但指标页横幅不消失」）──────────
// 根因：此前这两组开关只改本地 `Set` + sessionStorage，**从未 POST 过**配置 ⇒ 后端
// `AttackSwitchConfig` 里的注入一直开着，`mall.observe.mq.up` 恒为 0，指标页横幅自然是
// 诚实的「还断着」。修法不是前端藏横幅，而是把开关真的接到后端那份进程内共享配置上。
//
// ⚠️ 写请求**串行化**：每次拨动都是「整组替换」，连点两枚时若两发并行，回包顺序颠倒
//    会让后到的旧快照把新状态盖回去（页面与服务端各说各话）。串成一条 promise 链即可。
// ⚠️ 失败时**重新读**服务端快照，不留下「页面显示已关、后端其实还开着」的假象。
const configSaving = ref(false)
const configErr = ref('')
let configWrite = Promise.resolve()
let configPending = 0

function applyConfig(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') return
  if (Array.isArray(snapshot.Protections)) {
    protections.value = new Set(snapshot.Protections.filter((k) => PROTECTIONS.includes(k) && protectionWired(k)))
  }
  if (Array.isArray(snapshot.Injections)) {
    injections.value = new Set(snapshot.Injections.filter((k) => INJECTIONS.includes(k)))
  }
}

async function loadSharedConfig() {
  try {
    const data = await readAttackConfig()
    if (data && data.success === false) {
      configErr.value = data.msg || '共享开关读取失败（页面暂用本地缓存）'
      return
    }
    applyConfig(data)
    configErr.value = ''
  } catch (e) {
    // 配置面读不到不该让整页不可用：本地表单仍可编辑，开跑时引擎读的是服务端那份。
    configErr.value = `共享开关读取失败（页面暂用本地缓存）：${errText(e)}`
    console.error('attack config read', e)
  }
}

function pushConfig() {
  const payload = {
    protections: [...protections.value],
    injections: [...injections.value],
  }
  configPending += 1
  configSaving.value = true
  configErr.value = ''
  configWrite = configWrite
    .then(() => updateAttackConfig(payload))
    .then(async (data) => {
      if (data && data.success === false) {
        // 先按服务端真相回填，再显示失败文案（loadSharedConfig 成功时会清空 configErr）。
        await loadSharedConfig()
        configErr.value = data.msg || '共享开关保存失败'
        return
      }
      applyConfig(data)
    })
    .catch(async (e) => {
      console.error('attack config write', e)
      await loadSharedConfig()
      configErr.value = `共享开关保存失败：${errText(e)}`
    })
    .finally(() => {
      configPending -= 1
      if (configPending <= 0) configSaving.value = false
    })
  return configWrite
}

/** 勾选 = 权重 > 0（后端 `parse` 把 0 权重直接剔除，见 `WeightSlider.vue` 块首）。 */
const picked = computed(() =>
  scenarios.value.filter((s) => weightOf(s.Key) > 0).map((s) => ({ key: s.Key, weight: weightOf(s.Key) })),
)
const weightSum = computed(() => picked.value.reduce((a, c) => a + c.weight, 0))

/**
 * 能不能开跑 + 不能的话为什么。
 * ⚠️ 「并发 < 勾选场景数」是**后端会回 400** 的一条（`AttackEngine`：不悄悄少跑几个）——
 *    前端先拦一道纯粹是为了省掉一次几十秒的往返（400 是立刻回的，但用户会以为「点了没反应」）。
 *    判据仍以后端为准，前端这条只是提前说同一句话。
 */
const canRun = computed(() => picked.value.length > 0 && normInt(concurrency.value, CONC_MIN, CONC_MAX, CONC_DEFAULT) >= picked.value.length)
const blockReason = computed(() => {
  if (!scenarios.value.length) return '场景清单还没取到，先等它加载完（或看上方提示）。'
  if (!picked.value.length) return '至少勾一个场景：把任意一根滑条拖到 0 以上即为勾选（0% = 没勾）。'
  const c = normInt(concurrency.value, CONC_MIN, CONC_MAX, CONC_DEFAULT)
  if (c < picked.value.length) return `总并发数必须 ≥ 勾选场景数（现勾了 ${picked.value.length} 个，并发填的是 ${c}）—— 后端对这条回 400。`
  return ''
})

/** 进度条的估算百分比。⚠️ 压在 **96%**：它是「还在跑」的指示器，不是后端读数（见文件头）。 */
const pct = computed(() => {
  if (!running.value || expectedMs <= 0) return 0
  return Math.min(96, Math.round((elapsedMs.value / expectedMs) * 100))
})
const elapsedSec = computed(() => Math.floor(elapsedMs.value / 1000))

/**
 * 辅助条那个「平均 QPS」= 已发 ÷ 已跑秒数（`Sent ÷ ElapsedMs`）。
 * ⚠️ 分子分母**都取后端读数**（见 `prog`）：拿估的墙钟秒数去除真读数会在收尾那一拍算歪。
 * ⚠️ 第一拍之前 / 不足 1 秒 ⇒ 画「—」而不是画一个从 0 除出来的数。
 */
const avgQps = computed(() => {
  if (prog.sent == null || !prog.elapsedMs || prog.elapsedMs < 1000) return '—'
  return (prog.sent / (prog.elapsedMs / 1000)).toFixed(1)
})

// ── 时区（与 `AuditPage.vue` 同一口径的**逐字副本**，见文件头）────────────────
const pad = (n) => String(n).padStart(2, '0')
const tzLabel = computed(() => {
  const mins = -new Date().getTimezoneOffset()
  const a = Math.abs(mins)
  return `UTC${mins >= 0 ? '+' : '-'}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
})
function toLocal(sqlTime) {
  if (!sqlTime) return '—'
  const d = new Date(`${String(sqlTime).replace(' ', 'T')}Z`)
  if (Number.isNaN(d.getTime())) return String(sqlTime)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

// ── 小工具 ───────────────────────────────────────────────────────────────────
/** 空 / 非数 → 缺省；否则四舍五入并收进 `[min, max]`。**只用于发请求前**（见常量那节）。 */
function normInt(raw, min, max, fallback) {
  const s = String(raw ?? '').trim()
  if (s === '') return fallback
  const n = Number(s)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, Math.round(n)))
}

const num = (v) => (v == null || v === '' ? '—' : String(v))
const dash = (v) => (v == null ? '—' : String(v))

function fmtMs(ms) {
  if (ms == null || ms === '') return '—'
  const n = Number(ms)
  if (!Number.isFinite(n)) return String(ms)
  return n < 1000 ? `${n} ms` : `${(n / 1000).toFixed(1)} s`
}

/**
 * 后端 `Result` 里取一段**数组**（`Scenarios` / `Notes` 用）。
 * ⚠️ 四处兜底是必要的：`Result` 可能是个**没有这些键的对象**（序列化失败时后端落的是
 *    `{"error": …}`，见 `DrillRecordService.toJson`）⇒ 直接 `.Notes.length` 会整页白屏。
 */
function listOf(row, key) {
  const v = row && row.Result && row.Result[key]
  return Array.isArray(v) ? v : []
}

/** `Result.Stock`（对象，缺失时给空对象 ⇒ 模板里的 `dash()` 会画成 `—`）。 */
function stockOf(row) {
  const v = row && row.Result && row.Result.Stock
  return v && typeof v === 'object' ? v : {}
}
function rdOf(row) {
  const v = row && row.Result && row.Result.RedisDown
  return v && typeof v === 'object' ? v : {}
}
function mkOf(row) {
  const v = row && row.Result && row.Result.Markers
  return v && typeof v === 'object' ? v : {}
}

/**
 * `Result.Markers.SampleOrderIds`。
 * ⚠️ 单独一个函数而不是复用 `listOf(r, 'SampleOrderIds')`：它是**挂在 `Markers` 下面**的，
 *    不是 `Result` 的直接子键 —— 用 `listOf` 取会**永远**得到空数组，而这一行的写法是
 *    `v-if="…length"` ⇒ 那个错不会报任何异常，只会把这一行静默藏掉（本笔实测踩到过）。
 */
function samplesOf(row) {
  const mk = mkOf(row)
  return Array.isArray(mk.SampleOrderIds) ? mk.SampleOrderIds : []
}

/**
 * `drill_record.Scenario`（配置 JSON）→ 可读的三个数。
 * 键是**短名**（`{c,d,s:[{k,w}]}`）—— `AttackEngine.buildScenarioJson` 手写的那份，
 * 用的是短键（列只有 500 字符，勾满 8 个场景时长键名会把余量吃掉一半），故这里按短键读。
 * ⚠️ 超长截断后**不再是合法 JSON** ⇒ 后端会落到 `ScenarioRaw` + `ScenarioParseError`
 *    并把 `Scenario` 置 null —— 那种行走 `cfgOf` 的兜底（`—`），不画成空勾选。
 */
function cfgOf(row) {
  const o = row && row.Scenario
  if (!o || typeof o !== 'object' || Array.isArray(o)) return { c: '—', d: '—', s: [] }
  return {
    c: o.c == null ? '—' : o.c,
    d: o.d == null ? '—' : o.d,
    s: Array.isArray(o.s) ? o.s.filter((x) => x && typeof x === 'object') : [],
  }
}

/**
 * 错误 → 一句话。
 * ⚠️ **先读 `e.body.msg`**：入参非法走后端统一形状的 `400 + {success:false,msg}`
 *    （`AttackController` 把 `IllegalArgumentException` 翻成 400），那句 msg 是「哪个参数不对」
 *    的**唯一**出处（`http.js` 的 `HttpError` 把解析后的 body 挂在 `.body` 上）。
 *    只显示「HTTP 400」等于把后端写好的那句话扔掉。
 */
function errText(e) {
  const msg = e && e.body && (e.body.msg || e.body.message)
  if (msg) return msg
  if (e && e.status) return `HTTP ${e.status}`
  return (e && e.message) || String(e)
}

// ── 取数 ─────────────────────────────────────────────────────────────────────
async function loadScenarios() {
  loadingScenarios.value = true
  try {
    const data = await attackScenarios()
    const list = (data && data.list) || []
    scenarios.value = list
    // ⚠️ 缺省权重**取自后端**（现为 15/15/10/10/10/10/15/15，合计 100 ——《块7参考》§1.2 的分布）
    //    不在前端写死一套：分布的唯一出处是 `AttackEngine.SCENARIOS`，抄第二遍必走岔。
    // ⚠️ 只在**首次**赋值（`undefined` 才填）：重取清单不该把用户拖过的滑条打回缺省。
    list.forEach((s) => {
      if (weights[s.Key] === undefined) weights[s.Key] = Number(s.DefaultWeight) || 0
    })
  } catch (e) {
    scenarios.value = []
    flash.kind = 'bad'
    flash.text = `场景清单加载失败：${errText(e)}`
  } finally {
    loadingScenarios.value = false
  }
}

async function loadRecords() {
  loadingRecords.value = true
  recErr.value = ''
  try {
    const data = await attackRecords({ limit: f.limit, offset: f.offset })
    records.value = (data && data.list) || []
    total.value = data && data.total != null ? Number(data.total) : 0
    // ⚠️ 用**后端回显**的生效上限（请求 9999 会被夹到 200），同 `AuditPage.vue` 的处置。
    if (data && data.limit != null) f.limit = Number(data.limit)
    if (data && data.offset != null) f.offset = Number(data.offset)
  } catch (e) {
    records.value = []
    total.value = 0
    recErr.value = `演练记录加载失败：${errText(e)}`
  } finally {
    loadingRecords.value = false
  }
}

/** `Pager.vue` 的回调：它只报 `{limit, offset}`，发请求仍由本页做。 */
function onRecordsPage({ limit, offset }) {
  f.limit = limit
  f.offset = offset
  loadRecords()
}

// ── 清场（破坏性 · 见 `api/observe.js#cleanupAttack` 的三条 ⚠️）──────────────
/**
 * 清掉攻击演练写进业务表的行。**两次点击**才算数（第一次上膛、第二次执行）——
 * 这条是真 `DELETE`、**没有撤销**，而本仓管理页的既有惯例里**没有** `confirm()`
 * （`eslintConfig.env` 没有 `browser`，用浏览器全局会触发 `no-undef`，见文件头）
 * ⇒ 二次确认落在按钮自己身上，与 `OrderPage.vue` 的 `armed` 同一套（按钮变红 + 页顶一行话）。
 *
 * ⚠️ 必须回显后端 `summary`（如「共 10 行（comment 1 / order 3 / …）」）—— 只报一句「成功」
 *    等于把「到底删了几行」藏起来（`api/observe.js#cleanupAttack` 注释原话）。
 * ⚠️ 清完 `loadRecords()`：清的是**演练标记出来的业务数据**（`SIM-%` 前缀 OR `Source='attack'`），
 *    `drill_record` 本身不在范围内，但页顶计数与列表口径要跟库一致 ⇒ 顺手刷新。
 */
async function doCleanup() {
  if (!armedClean.value) {
    armedClean.value = true
    flash.kind = 'ok'
    flash.text = '再点一次「确认清理并恢复」：会真删掉攻击演练写进业务表的行（订单 / 评论 / 事件台账等），'
      + '并把种子库存 / 在架 / 冻结状态复位；删除不可撤销。'
    return
  }
  armedClean.value = false
  cleaning.value = true
  try {
    const data = await cleanupAttack()
    flash.kind = 'ok'
    flash.text = `已清理并恢复：${(data && data.summary) || '共 0 行（库里没有待清的攻击数据）'}`
    await loadRecords()
    // 清场第 ③ 段会删 observed_event 的演练行 ⇒ 水位读数跟着刷新。
    await loadStorage()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `清场失败：${errText(e)}`
  } finally {
    cleaning.value = false
  }
}

// ── 事件台账存储水位 / 彻底清空（2026-10-19）────────────────────────────────
/** 水位条宽度（0–100）：`count / highWatermark`。读不到值时画 0 —— 不编一个比例。 */
const storagePct = computed(() => {
  const n = Number(storage.count)
  const h = Number(storage.highWatermark)
  if (!Number.isFinite(n) || !Number.isFinite(h) || h <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((n / h) * 100)))
})

/**
 * 拉一次存储水位。⚠️ 失败**只记不抛**：水位是辅助读数，读不到不该把整页的其他动作拖停；
 * `storage.err` 在卡片里单独显示，下一次刷新（跑完 / 清理后 / 挂载）会自己好。
 */
async function loadStorage() {
  loadingStorage.value = true
  try {
    const data = await attackStorage()
    storage.count = Number(data && data.count) || 0
    storage.highWatermark = Number(data && data.highWatermark) || storage.highWatermark
    storage.targetWatermark = Number(data && data.targetWatermark) || storage.targetWatermark
    storage.retentionDays = Number(data && data.retentionDays) || storage.retentionDays
    storage.batchSize = Number(data && data.batchSize) || storage.batchSize
    storage.above = !!(data && data.aboveHighWatermark)
    storage.err = ''
  } catch (e) {
    storage.err = errText(e)
  } finally {
    loadingStorage.value = false
  }
}

/**
 * 【彻底清空可观测历史数据】—— 两次点击才算数（与 `doCleanup` 同一套上膛机制）。
 *
 * ⚠️ 与「清理并恢复环境」的区别：后者只删**演练标记**命中的行，日常事件行不动；
 *    本按钮删**整张** `observed_event`。⚠️ 真删、不可撤销 ⇒ 第一次点击只上膛。
 * ⚠️ 后端分批删（5000 行 + 100ms 间歇），积压多时远超默认 10s —— 超时在
 *    `api/observe.js#purgeAttackEvents` 里已加到 180 秒，本页不重复传。
 */
async function doPurge() {
  if (!armedPurge.value) {
    armedPurge.value = true
    flash.kind = 'warn'
    flash.text = '再点一次「彻底清空」：会真删掉可观测事件台账里的**全部**行（不只演练批次，'
      + '日常事件也会一起清空），删除不可撤销。'
    return
  }
  armedPurge.value = false
  purging.value = true
  try {
    const data = await purgeAttackEvents()
    flash.kind = 'ok'
    flash.text = (data && data.summary) || '已彻底清空事件台账'
    await loadStorage()
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `彻底清空失败：${errText(e)}`
  } finally {
    purging.value = false
  }
}

// ── 演练环境复位（`/attack/reset` · 2026-10-18）──────────────────────────────
/**
 * 把压测打空的种子库存 / 下架的父商品 / 冻结的演示商家复位，然后**自动重跑上一轮**。
 *
 * <p>用户批文第二节：错误态给一颗显眼按钮，点完恢复库存并「自动重试拉起演练」。
 * 重跑参数取 {@link lastRun}（`fire()` 每次开跑时记下的 name + chosen），不读当前面板 ——
 * 用户在失败之后可能又拨了滑条，而这次重跑要复现的是**刚才失败的那一轮**。
 *
 * <p>⚠️ 回显后端 `summary`（「演练环境已复位：共 N 行（…）」）：只报「已重置」等于把
 * 「到底改了几行」藏起来（与 `doCleanup` 同一口径）。
 * ⚠️ `resetting` 在**重跑之前**落下：`fire()` 自己会接管 `running` 态；否则「复位中…」
 * 会跟着整轮演练（最长 600 秒）一直亮着。
 */
async function doResetEnvironment() {
  if (resetting.value || running.value) return
  const rerun = lastRun
  resetting.value = true
  try {
    const data = await resetAttackEnvironment()
    flash.kind = 'ok'
    // 后端 `summary` 自己就以「演练环境已复位：共 N 行（…）」开头 ⇒ 这里不再加前缀，否则叠成
    // 「已复位：演练环境已复位：…」（2026-10-18 截图里看到）。
    flash.text = `${(data && data.summary) || '演练环境已复位'}${rerun ? '；正在用上一轮参数重跑…' : ''}`
    showReset.value = false
    resetting.value = false
    if (rerun) await fire(rerun.name, rerun.chosen, { keepFlash: true })
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `复位失败：${errText(e)}`
  } finally {
    resetting.value = false
  }
}

// ── 实时读数（`/attack/status` · 块 7 收尾）──────────────────────────────────
/**
 * 拉**一拍**后端读数并回填 `prog`（用户 2026-10-14 点名的 6 个数里那两个真数的唯一来源）。
 *
 * ⚠️ 失败**只记不抛**：这只是辅助条上的旁路读数，一次失败不该把一轮演练拖垮
 *    （`/run` 那条 await 才是主角）—— 保持上一拍的值，下一拍自己会好。
 * ⚠️ 后端说 `Running=false`（那一轮已经收口了）⇒ **本页主动停轮询**：否则会在收尾后的
 *    那几秒里空打十几次，每次都白过一遍鉴权。
 * ⚠️ **不改 `running`** —— 它归 `fire()` 的 `finally`（`/run` 的响应几秒内就到了）。
 */
async function pollStatus() {
  try {
    const data = await attackStatus()
    prog.sent = Number(data && data.Sent) || 0
    prog.blocked = Number(data && data.Blocked) || 0
    prog.elapsedMs = Number(data && data.ElapsedMs) || 0
    prog.err = ''
    if (data && data.Running === false) {
      stopStatusPolling()
      // ⚠️ 只有「恢复来的」那一轮要在这里收口：本页 `fire()` 发起的那轮有 `/run` 的
      //    `finally` 收 `running`，这里再写一次就变成两个地方抢写（见 `running` 的注释）。
      //    恢复来的那轮没有 `/run` 的 await 挂在页面上了，后端说停了就是停了。
      if (restoredRun.value) await finishRestoredRun()
    }
  } catch (e) {
    prog.err = errText(e)
  }
}

function stopElapsedTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 恢复来的那一轮在后端收口了：退出「演练中」态、刷新记录、说明结果去哪看。 */
async function finishRestoredRun() {
  restoredRun.value = false
  running.value = false
  stopping.value = false
  stopElapsedTimer()
  dropStore(RUN_KEY)
  await loadRecords()
  flash.kind = 'info'
  flash.text = '后端那一轮演练已经收口，进度显示已退出；结果已刷新到「演练记录」。'
}

/**
 * 起 1 秒轮询（`fire()` 开跑时调）。
 * ⚠️ 立刻打**第一拍**、再排 interval：辅助条一出现就该有读数，空 1 秒会让人以为它坏了。
 * ⚠️ 先清再起：连着跑两轮不会留下两个 `setInterval`（同 `fire()` 里那条计时器的处置）。
 * @param {boolean} reset 是否把读数清成「未读到」（恢复场景要保留刚拿到的第一拍）。
 */
function startStatusPolling(reset = true) {
  stopStatusPolling()
  if (reset) {
    prog.sent = null
    prog.blocked = null
    prog.elapsedMs = 0
    prog.err = ''
  }
  pollStatus()
  statusTimer = setInterval(pollStatus, 1000)
}

/** 停轮询（跑完 / 被叫停 / 后端说没在跑 / 组件卸载）—— 读数**留着**不清零（那半截提示还要读它）。 */
function stopStatusPolling() {
  if (statusTimer) {
    clearInterval(statusTimer)
    statusTimer = null
  }
}

// ── 跑 ───────────────────────────────────────────────────────────────────────
/**
 * 跑一轮（两种入口共用这一条路径）。
 *
 * @param {string} name   场景名（进 `drill_record.Name`，后端按列宽截断到 64）
 * @param {Array<{key:string,weight:number}>} chosen 勾选与权重
 * @param {{keepFlash?:boolean}} [opts] `keepFlash:true` 时**不清空**上一句提示 ——
 *        复位入口的「已复位：…」要留到重跑出结果（否则同一帧被本函数的 `flash.text=''`
 *        抹掉，用户只会看到重跑结果、看不到复位摘要；2026-10-18 实测踩到）。
 */
async function fire(name, chosen, opts) {
  if (running.value) return
  // 复位入口只属于**上一次失败**：新一轮开始时先收起，失败时再由 catch 打开。
  showReset.value = false
  // 记下参数（浅拷贝）：复位按钮要能「用同一套参数自动重跑」，而调用方传进来的
  // `chosen` 是 `picked.value.map(…)` 的新数组、`name` 是字符串 —— 但仍拷一份，
  // 不依赖调用方在 await 期间不碰它。
  lastRun = { name, chosen: (chosen || []).map((c) => ({ ...c })) }
  running.value = true
  if (!(opts && opts.keepFlash)) flash.text = ''
  openId.value = null

  const secs = normInt(durationSeconds.value, DUR_MIN, DUR_MAX, DUR_DEFAULT)
  plannedSeconds.value = secs
  expectedMs = secs * 1000 + REDIS_DOWN_GRACE_MS
  startedAt = Date.now()
  elapsedMs.value = 0
  // 记下这一轮的「计划秒数 / 起点」：切走再回来时 `/attack/status` 只回已跑毫秒数，
  // 没有计划时长就画不出「已等 N / 预计 M」那条进度（见 FORM_KEY 上方的说明）。
  writeStore(RUN_KEY, { plannedSeconds: secs, expectedMs, startedAt })
  timer = setInterval(() => {
    elapsedMs.value = Date.now() - startedAt
  }, 200)
  // 辅助条那三个数的真源（块 7 收尾）。⚠️ 与上面那个 200ms 计时器**两条独立的命**：
  // 一条是本地墙钟（估时长），一条是后端读数（真数）—— 一条失败不该拖停另一条。
  startStatusPolling()

  try {
    // ⚠️ **不传 opts**：`runAttack` 自己带 `durationSeconds*1000 + 60s` 的长超时
    //    （见 `api/observe.js` 文件头那条 ⚠️ 与 `AttackController` 类注释）。
    const data = await runAttack({
      name,
      concurrency: normInt(concurrency.value, CONC_MIN, CONC_MAX, CONC_DEFAULT),
      durationSeconds: secs,
      targetQps: tier.value,
      scenarios: chosen,
      // ⚠️ 3a.1（乙案）：两组开关**不再随这一轮发出** —— 它们是共享配置，由
      //    `GET/POST /admin/observe/attack/config` 读写；引擎开跑时**自己**去读那一份。
      //    ⇒ 这里刻意**什么都不传**：传了后端会直接 400 并指路（不静默忽略）。
      //    ⚠️ 本页开关现在**当场**写共享配置（见 `pushConfig`）；`/run` 仍不传 ——
      //    引擎开跑时自己读那一份，且过程中两页都还能改（乙案）。
      //    ⚠️ **别**为了「让这一轮用上刚拨的开关」再 POST 一次：多一次无意义写入，
      //    还可能把日志页刚改的覆盖回去。
    })
    // 甲：这一轮有没有被叫停过、有没有背压弃投 / 取消。后两个数是 `DrillRecordService.run()`
    // 在**响应根上**单列出来的（`ShedRequests` / `AbandonedRequests`），**不并进「失败」那一档**
    // ——「没发出去」与「发出去失败了」是两件事。非 0 才写进这行提示（0 是常态，不必占屏）。
    const eng = (data && data.Result && data.Result.Engine) || {}
    const engNotes = []
    if (Number(data.ShedRequests) > 0) engNotes.push(`背压弃投 ${data.ShedRequests}（没发出去，不算失败）`)
    if (Number(data.AbandonedRequests) > 0) engNotes.push(`取消 ${data.AbandonedRequests}`)
    // ⚠️ 2026-10-19：开跑前的容量水位熔断如果真触发了，后端在 `/run` 响应里带
    //    `EvictionWarning`（用户批文原句）+ `Evicted` / `StorageCount`。提示必须**原样**显示，
    //    不能只报「跑完了」—— 否则用户不知道「这一轮为什么清理了几万行旧历史」。
    const evicted = Number(data.Evicted) || 0
    flash.kind = data.EvictionWarning ? 'warn' : 'ok'
    flash.text =
      (data.EvictionWarning ? `${data.EvictionWarning} ` : '') +
      `${eng.Stopped ? '已叫停' : `「${data.Name || name}」跑完`}：总请求 ${data.TotalRequests} · ` +
      `拦下 ${data.BlockedRequests} · 失败 ${data.FailedRequests} · 耗时 ${fmtMs(data.DurationMs)}。` +
      (engNotes.length ? ` ${engNotes.join(' · ')}。` : '') +
      (evicted > 0 ? ` 开跑前已淘汰最旧 ${evicted} 行事件（现 ${data.StorageCount} 行）。` : '') +
      `记录 Id=${data.Id} 已刷新到「演练记录」并展开。`
    // 跑完刷新列表并按 Id 展开 —— 理由见文件头「跑完之后为什么不再自己拼一行」。
    await loadRecords()
    // 一轮演练会写进大量事件 ⇒ 水位随之上涨；顺手刷新卡片里的刻度。
    await loadStorage()
    if (data.Id != null) openId.value = data.Id
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `演练没跑起来：${errText(e)}`
    // 「挑不到商品」是库存被打空的典型症状；但**任何**没跑起来都值得给一次复位入口
    // （下架 / 冻结也是同一组判据）⇒ 不按错误文案匹配，失败即显示。
    showReset.value = true
  } finally {
    running.value = false
    stopStatusPolling()
    stopElapsedTimer()
    dropStore(RUN_KEY)
  }
}

/** 固定一键：**这一个**场景、权重 100（并发 / 持续秒数仍取面板上的值）。 */
function runPreset(s) {
  fire(s.Name, [{ key: s.Key, weight: 100 }])
}

/** 自定义编排：按滑条勾选跑。 */
function runCustom() {
  fire('自定义', picked.value.map((c) => ({ key: c.key, weight: c.weight })))
}

/**
 * 叫停正在跑的那一轮（`[暂停]` · commit 10c）。
 *
 * ⚠️ **回显后端自己的 `summary`**，不在前端重写那句话 —— 「停的是什么、在途那批怎么处理」的
 *    唯一出处在 `AttackController#stop`（与 `doCleanup` 回显 `summary` 同一口径）。
 * ⚠️ 后端 `stopped=false`（当时没有在跑的演练）**不是失败** —— 多半是这一轮刚好自己跑完了
 *    ⇒ 照实说（中性提示，不给红条），不编造成一个「已停止」。
 * ⚠️ 这个函数**不发**第二轮演练、也**不改** `running`：`running` 归 `fire()` 的 `finally` 管
 *    （叫停之后 `/run` 那个 await 几秒内就会回来，届时报文里带 `Engine.Stopped=true`）。
 */
async function stopRun() {
  if (!running.value || stopping.value) return
  stopping.value = true
  try {
    const data = await stopAttack()
    flash.kind = data && data.stopped ? 'ok' : 'info'
    flash.text = (data && data.summary)
      || (data && data.stopped ? '已发出停止令。' : '当时没有正在跑的演练。')
  } catch (e) {
    flash.kind = 'bad'
    flash.text = `叫停没发出去：${errText(e)}`
  } finally {
    stopping.value = false
  }
}

function toggle(r) {
  openId.value = openId.value === r.Id ? null : r.Id
}

/**
 * 开始回放某一批（`[回放]` 钮）—— **装载 + 跳页**，播放本身不在这里。
 *
 * 【为什么不再留在本页】
 *   用户 2026-10-17：「点击回放后，日志页和指标页瞬间被历史数据铺满，没有任何『录像播放』
 *   的滚动感」。旧实现只在本页底部铺一个内联列表（`replay.played`），既没有清空日志页、
 *   也没有切断实时流 —— 页面之间各说各话。现在装载交给 `api/replay-session.js` 的共享会话，
 *   播放时钟与行都落在日志页，指标页在回放期间停掉实时（见两页的 `watch(replaySession.active)`）。
 *
 * ⚠️ 只打**一发**（一次几千行的 SELECT）：这个 await 回来之后网络就与播放无关了。
 * ⚠️ 失败**不跳页**：`startReplaySession` 只在装载成功后才把 `active` 置 true，
 *    失败就留在本页把错误显示在按钮旁边（跳到一页「横幅写着回放、实际空着」更糟）。
 */
async function startReplay(r) {
  const batchId = r && r.BatchId
  if (!batchId) return
  // 2026-10-18：台账已被保留期清空的批次 —— 按钮已置灰，这里再挡一层（置灰只是视觉，
  // 键盘 / 脚本仍可能触发点击）。错误挂在 `replaySession.err` 上，模板按 BatchId 显示。
  if (r.HasData === false) {
    replaySession.err = '该批次历史数据已过期被清理，无法回放'
    replaySession.batchId = batchId
    return
  }
  try {
    // 记录级聚合快照（用户 2026-10-17 裁定的「B 为主 + C」）：这是**本轮总量**，不是时间序列，
    // 只用于指标页右栏「记录快照」；逐秒指标由前端按台账事件推算（`api/replay-metrics.js`）。
    // ⚠️ 不用本页的 `num`：它在文案层是**字符串**格式化器（空值回 '—'），不是数值转换。
    const n0 = (v) => {
      const n = Number(v)
      return Number.isFinite(n) ? n : 0
    }
    const snapshot = {
      totalRequests: n0(r.TotalRequests),
      blockedRequests: n0(r.BlockedRequests),
      failedRequests: n0(r.FailedRequests),
      durationMs: n0(r.DurationMs),
      name: r.Name || '',
    }
    await startReplaySession(batchId, snapshot)
    // `replace` 还是 `push`：用 push，用户能从日志页按返回键回到演练记录 —— 回放不是一次
    // 「离开原列表」的跳转，它只是换了个看同一批数据的地方。
    await router.push('/admin/observe/logs')
  } catch (e) {
    // 错误已经落在 `replaySession.err`（模板里按 batchId 显示）；这里不再叠一条页顶 flash ——
    // 那条是「跑演练」的，两件事混在一起会看不懂哪句在说谁。
  }
}

/**
 * 切回本页时把**后端仍在跑**的那一轮接回来（用户 2026-10-17 报「切页后按钮回到开始、
 * 进度条没了」）。判据只有一个：`POST /attack/status` 的 `Running`（`attackStatus()`，实际就是
 * POST —— 用户口径里的 `GET` 是这件事的语义，不是方法名）。
 *
 * ⚠️ 为什么不恢复 `openId` / 结果：那一轮还没结束，`drill_record` 尚未落行；等
 *    `pollStatus()` 看到 `Running=false` 时由 `finishRestoredRun()` 刷新列表。
 * ⚠️ 恢复的是**显示**，不是控制：本页没有那条 `/run` 的 await，点「停止」走的是同一个
 *    `/attack/stop`，后端照停；本页靠下一拍 `Running=false` 退出「演练中」。
 */
async function restoreRunState() {
  let data
  try {
    data = await attackStatus()
  } catch (e) {
    return // 读不到就按「没在跑」处理：这是切页恢复，不该把页面顶成一条红错误
  }
  if (!data || data.Running !== true) {
    dropStore(RUN_KEY)
    return
  }
  const meta = readStore(RUN_KEY)
  const elapsed = Number(data.ElapsedMs) || 0
  restoredRun.value = true
  running.value = true
  stopping.value = false
  openId.value = null
  plannedSeconds.value =
    meta && Number.isFinite(Number(meta.plannedSeconds))
      ? normInt(meta.plannedSeconds, DUR_MIN, DUR_MAX, DUR_DEFAULT)
      : DUR_DEFAULT
  expectedMs =
    meta && Number(meta.expectedMs) > 0
      ? Number(meta.expectedMs)
      // 没有本地记录（比如那一轮是别的标签页发起的）：至少让进度条从「已跑时长 + 宽限」起步
      : Math.max(elapsed, 1000) + REDIS_DOWN_GRACE_MS
  startedAt =
    meta && Number(meta.startedAt) > 0 ? Number(meta.startedAt) : Date.now() - elapsed
  // 以「后端已跑毫秒」和「本地墙钟」中较大的为准：前者不会因系统时钟回拨变小，
  // 后者在本地记录存在时更连续。
  elapsedMs.value = Math.max(elapsed, Date.now() - startedAt)
  stopElapsedTimer()
  timer = setInterval(() => {
    elapsedMs.value = Date.now() - startedAt
  }, 200)
  prog.sent = Number(data.Sent) || 0
  prog.blocked = Number(data.Blocked) || 0
  prog.elapsedMs = elapsed
  prog.err = ''
  // 保留刚拿到的第一拍，只接上 1 秒轮询（`reset=false`）。
  startStatusPolling(false)
  flash.kind = 'info'
  flash.text = '检测到后端还有一轮演练在跑：已恢复进度条与实时读数；跑完会自动刷新「演练记录」。'
}

onMounted(() => {
  // 顺序有讲究：先恢复表单，再取场景清单 —— `loadScenarios()` 只给「还没有值」的键填缺省权重，
  // 反过来的话用户拖过的滑条会被后端缺省覆盖（这正是切页后「权重全回默认」的成因之一）。
  restoreForm()
  // 两组开关的**权威值在服务端**（`attack/config` 是共享配置，日志页也读写同一份）；
  // sessionStorage 只是读不到时的兜底。必须在 restoreForm 之后覆盖，否则页面会显示
  // 上一次本地勾选、而后端其实已被人从另一页改掉。
  loadSharedConfig()
  loadScenarios()
  loadRecords()
  // 2026-10-19：存储水位是「这一页开跑之前要不要熔断」的可读判据，挂载即拉一次。
  loadStorage()
  restoreRunState()
  watch([weights, concurrency, durationSeconds, tier, protections, injections], persistForm, { deep: true })
})

// ⚠️ 页面切走要停掉本地的计时器 —— 不停就一直在给一个没人看的组件写状态
//    （演练本身在后端照常跑完、照常落库，本页只是不再等它）。
// 🔴 两条都要停：那条 1 秒轮询更贵 —— 它不停就是**切走之后还在往后端打请求**（每条都要过鉴权）；
//    另一条是执行进度计时。
// ⚠️ **回放的 rAF 不在这里停**（2026-10-17 全链路联动）：它已经搬进 `api/replay-session.js`
//    的模块级会话，切页正是「三页共用同一根时钟」的前提。旧实现里那句 `stopReplayRaf()`
//    随迁出成了悬空引用（切页时抛 ReferenceError，把路由导航整条打断）—— 已删。
onBeforeUnmount(() => {
  stopStatusPolling()
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.a-ac {
  max-width: 1120px;
}

/* ── ① 状态条 / 通用件（与 `AuditPage.vue` 同款同值，不另立一套）───────────── */
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
  font-variant-numeric: tabular-nums;
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

.a-h2 {
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
.a-h2n {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-reg);
  color: var(--ink-3);
}
.a-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s4) var(--s5);
}
.a-flash {
  font-size: var(--fs-sm);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-flash.ok {
  /* ⚠️ 用 `--success` 不用 `--success-deep`：深色主题下 deep(#154c38) 压在 ground(#133230)
     上几乎看不见（2026-10-18 截「已复位」时实测）；`--success` 亮绿在两种主题下都够。 */
  color: var(--success);
  background: var(--success-ground);
}
.a-flash.bad {
  color: var(--danger);
  background: var(--danger-ground);
}
/* 中性提示（commit 10c 新增）：既不是成功也不是失败 —— 现只用于「叫停时后端说没有在跑的
   一轮」。⚠️ 不拿 `.ok`（绿）去装它：那是「做成了」的意思，会把这个读数读反。 */
.a-flash.info {
  color: var(--ink-2);
  background: var(--surface-2);
}
/* 水位熔断提示（2026-10-19）：跑**成功**了、但历史被淘汰了一部分 ⇒ 既不是绿也不是红。
   用 `.a-warn` 同源的警告色，避免把「牺牲历史」读成「演练失败」。 */
.a-flash.warn {
  color: var(--warning);
  background: var(--warning-ground);
}
/* 事件台账水位条（2026-10-19）：宽度 = count / highWatermark，超线时变警告色。 */
.a-water {
  height: 10px;
  margin: var(--s3) 0;
  border-radius: 999px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  overflow: hidden;
}
.a-water-fill {
  display: block;
  height: 100%;
  background: var(--brand-ink);
  transition: width 0.3s ease;
}
.a-water-fill.is-high {
  background: var(--warning);
}
/* 演练环境复位入口（2026-10-18）：只在 `/run` 失败那一屏出现。
   ⚠️ 用危险色系画边框与按钮 —— 它是「环境已经坏了」的提示，不是普通信息条。 */
.a-recover {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  flex-wrap: wrap;
  margin-bottom: var(--s4);
  padding: var(--s3) var(--s4);
  border: 1px solid var(--danger);
  border-left-width: 4px;
  border-radius: var(--r2);
  background: var(--danger-ground);
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.a-recover-t {
  flex: 1 1 320px;
  min-width: 0;
}
.a-err {
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-note,
.a-warn {
  font-size: var(--fs-xs);
  color: var(--warning);
  background: var(--warning-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s4);
}
.a-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s6);
  text-align: center;
}

/* ── ② 固定一键 ───────────────────────────────────────────────────────────── */
.a-presets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(232px, 1fr));
  gap: var(--s3);
}
.a-preset {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font: inherit;
  text-align: left;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  padding: var(--s3) var(--s4);
  cursor: pointer;
}
.a-preset:hover:not(:disabled) {
  border-color: var(--brand-ink);
  background: var(--surface-2);
}
.a-preset:disabled {
  cursor: default;
  opacity: 0.55;
}
.a-preset-n {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
}
.a-preset-p {
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.a-preset-w {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}

/* ── ③ 自定义编排 ─────────────────────────────────────────────────────────── */
.a-sims {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--s2);
}
/* ── ③b 档位 / ③c 开关（丁 · commit 10c）────────────────────────────────────
   两排都是「一排小按钮」。样式照 `EventLog.vue` 的图例兼筛选（`.a-el-kind` / `.is-off`）同款：
   同域、最近邻，不另立一套 —— 那边也是「一排可拨的 chip，关掉的压暗并去色」。
   ⚠️ 那两处的样式各自 scoped，**不能跨组件复用**，所以这里是**同款而不是同一个类**。 */
.a-tiers,
.a-switches {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s2);
}
.a-tier,
.a-sw {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s3);
  cursor: pointer;
}
.a-tier:hover:not(:disabled),
.a-sw:hover:not(:disabled) {
  background: var(--surface-3);
}
.a-tier:disabled,
.a-sw:disabled {
  cursor: default;
  opacity: 0.55;
}
/* 档位的「选中的那一个」：品牌色（同 `.a-btn-on` 的一组变量）。 */
.a-tier.is-on {
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
.a-tier b {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-variant-numeric: tabular-nums;
}
.a-tier span {
  color: var(--ink-3);
}
.a-tier.is-on span {
  color: inherit;
  opacity: 0.8;
}
/* 开关「关掉」态：**整枚压暗**（同 `.a-el-kind.is-off` 的处置 —— 留一点色会被读成还开着）。 */
.a-sw.is-off {
  opacity: 0.45;
}
.a-sw-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c3);
  flex: none;
}
.a-sw.is-off .a-sw-dot {
  background: var(--ink-3);
}
.a-sw-n {
  font-size: var(--fs-xs);
}
/* ⚠️ D3（2026-10-18）起注入四枚全部真接通：原先给 `mqDown` / `dbSlow` 的
   `.a-sw.is-na`（虚线边）与 `.a-sw-na`（「未接通」角标）**已删除** —— 留着会让读代码的人
   以为还有占位开关。页面不再存在「拨了也不变读数」的注入。 */
/* 「不可开关」的那一枚防护开关（D 段 · commit 2 立案、阶段 2b 起只剩
   `degrade` = 记录位 —— `retry` 已接闸、不再是这一类）—— **标灰 + 虚线边 + 中性色角标**。
   ⚠️ 它说的是「**能不能拨**」（不可开关），与「注入了没有」是两件事；
   共用类名会让防护开关看起来「也能拨」，而它们的模板 `disabled` 是常开的。 */
.a-sw.is-locked {
  border-style: dashed;
  background: transparent;
  color: var(--ink-3);
  opacity: 0.7;
}
.a-sw.is-locked .a-sw-dot {
  background: var(--ink-3);
}
/* 「不可开关」的角标 —— **中性灰**（不是 `--warning` 的橙）：它不是警告，是「这一位没有闸」。 */
.a-sw-locked {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border: 1px dashed var(--line-2);
  border-radius: var(--r1);
  padding: 0 var(--s1);
}
.a-run {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  margin-top: var(--s4);
  padding-top: var(--s4);
  border-top: 1px solid var(--line);
}
.a-f {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-f-narrow {
  max-width: 132px;
}
.a-f input {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-run-note {
  flex: 1 1 240px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-btn {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s4);
  cursor: pointer;
}
.a-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-3);
}
.a-btn-on {
  color: var(--brand-ink);
  background: var(--brand-soft);
  border-color: var(--brand-ink);
}
/* 「上膛」态 = 危险动作（同 `OrderPage.vue` / `AccountsPage.vue` 的 `.a-btn-danger`，色值取同一组变量）。 */
.a-btn-danger {
  color: var(--danger);
  background: var(--danger-ground);
  border-color: var(--danger);
}
.a-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.a-prog {
  margin-top: var(--s4);
}
.a-prog-bar {
  height: 6px;
  border-radius: var(--r1);
  background: var(--surface-3);
  overflow: hidden;
}
.a-prog-bar i {
  display: block;
  height: 100%;
  background: var(--brand-ink);
  transition: width 0.2s linear;
}
.a-prog-t {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-top: var(--s2);
}

/* ── ④ 演练记录 ───────────────────────────────────────────────────────────── */
.a-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s4);
  padding: var(--s4) var(--s5);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  margin-bottom: var(--s4);
}
.a-f select {
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-f-actions {
  display: flex;
  gap: var(--s2);
  margin-left: auto;
}

.a-stream {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.a-ev {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  overflow: hidden;
}
.a-ev.on {
  border-color: var(--brand-ink);
}
.a-ev-head {
  display: flex;
  align-items: center;
  gap: var(--s4);
  width: 100%;
  font: inherit;
  text-align: left;
  background: none;
  border: 0;
  padding: var(--s3) var(--s4);
  cursor: pointer;
  color: inherit;
}
.a-ev-head:hover {
  background: var(--surface-2);
}
.a-ev-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s1);
}
.a-ev-l1 {
  display: flex;
  align-items: center;
  gap: var(--s3);
  min-width: 0;
}
.a-tag {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border-radius: var(--r1);
  padding: 2px 6px;
}
.a-ev-target {
  font-size: var(--fs-sm);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.a-ev-l2 {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
}
.a-who-chip {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r1);
  padding: 1px 6px;
}
.a-ev-time {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.a-ev-time b {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--ink);
}
.a-dim {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-dim2 {
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.a-ev-caret {
  flex: none;
  color: var(--ink-3);
}

.a-ev-body {
  border-top: 1px solid var(--line);
  padding: var(--s4);
  background: var(--surface-2);
}
.a-corrupt {
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
  border-radius: var(--r2);
  padding: var(--s3) var(--s4);
}
.a-rawwire {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  word-break: break-all;
}
.a-res {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: var(--s3);
  margin-bottom: var(--s3);
}
.a-res-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
  padding: var(--s2) var(--s3);
}
.a-res-cell span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-res-cell b {
  font-family: var(--font-mono);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: var(--ink);
}
.a-sub {
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  color: var(--ink-3);
  margin: var(--s3) 0 var(--s2);
}
/* 小标题右边那半句「这东西是怎么用的」（commit 10c 新增；同 `.a-h2n` 的处置，小一号）。 */
.a-subn {
  font-weight: var(--fw-reg);
  color: var(--ink-3);
  opacity: 0.85;
  margin-left: var(--s2);
}
.a-kv, .a-window-note {  /* ⚠️ 后者 = 共享开关写入中 / 写入失败的状态行（批四已清掉旧的过期提示文案） */
  font-size: var(--fs-xs);
  color: var(--ink-2);
  line-height: 1.9;
}
.a-window-note.is-bad { color: var(--danger); }
.a-kv code {
  font-family: var(--font-mono);
  color: var(--ink);
}
.a-chip {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r1);
  padding: 1px 5px;
  margin-right: var(--s1);
}
.a-tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.a-tbl th {
  text-align: left;
  font-weight: var(--fw-med);
  color: var(--ink-3);
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
}
.a-tbl td {
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
  vertical-align: top;
  color: var(--ink-2);
}
.a-tbl tr:last-child td {
  border-bottom: 0;
}
.a-tk {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.a-tk b {
  color: var(--ink);
  font-weight: var(--fw-med);
}
/* 「拦下」是本页唯一的好数字（拦得越多越对）；「失败」是坏数字 —— 只有 >0 时才染色
   （模板里判 `Number(...) > 0`），避免满屏绿色把真异常淹掉。 */
.a-ok {
  color: var(--success);
}
.a-bad {
  color: var(--danger);
}
.a-notes {
  list-style: disc;
  padding-left: var(--s5);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  line-height: 1.7;
}

/* ── 回放（块 7 收尾）───────────────────────────────────────────────────────── */
/* 记录行里那个入口：一行横排（标签 + 批次码 + 按钮），窄屏自动折行。 */
.a-rp-in {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s3);
  padding: var(--s3) 0;
  border-bottom: 1px dashed var(--line);
  margin-bottom: var(--s3);
}
</style>
