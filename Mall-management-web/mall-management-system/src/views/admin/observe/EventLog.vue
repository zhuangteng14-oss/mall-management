<template>
  <section class="a-el">
    <div class="ev-frame">
      <!-- ══ ① 状态条 ══════════════════════════════════════════════════════════════════
           两排：上排读数 / 下排时刻与 MQ。⚠️ 窗口化之后它是**外框里的一条带**（不是浮在区块
           之间的白条）—— 六区共用 `.ev-frame` 一个框，`> * + *` 之间是一条 1px 线。 -->
      <header class="ev-status">
        <div class="r1">
          <span class="hl"><i class="led" :class="{ off: !mqConnected }"></i>{{ phaseText }}</span>
          <!-- ⚠️ 这两个读数自 2026-10-16 起是**范围内**（受 ① 下排那个时间范围约束），
               不是「本页已收」。两者不是一个数：本页已收 {{ fmtInt(events.length) }} 条是原始窗口，
               范围内是它再按时间过一遍的结果 —— `title` 里把两个都写出来，免得读者自己猜。 -->
          <div class="cell" :title="`本页已收 ${fmtInt(events.length)} 条（原始窗口，上限 ${EVENT_CAP}）；当前时间范围内 ${fmtInt(ranged.length)} 条`">
            <b>{{ fmtInt(ranged.length) }}</b>
            <span>范围内事件（上限 {{ EVENT_CAP }}）</span>
          </div>
          <div class="cell">
            <b>{{ fmtRate(rate) }}</b>
            <span>本拍速率（条/秒）</span>
          </div>
          <div class="cell" :title="`本页已收 ${fmtInt(traceList.length)} 条（原始窗口，上限 ${TRACE_CAP}）；当前时间范围内 ${fmtInt(rangedTraces.length)} 条`">
            <b>{{ fmtInt(rangedTraces.length) }}</b>
            <span>范围内链路（上限 {{ TRACE_CAP }}）</span>
          </div>
          <!-- 错误率 —— 与右边那条三色条紧挨着（就近）。⚠️ 动效落点 10 在它身上。
               ⚠️ 它是**本页窗口内累计**（warn+error / 窗口内事件），不是帧里本拍的 counts。 -->
          <div class="cell err" :class="{ alert: errBad > 0 }" data-anim="10">
            <b>{{ errRate }}<i>%</i></b>
            <span>错误率 warn+error</span>
          </div>
          <!-- 严重度 mini 三色条（动效落点 9）：宽度 = 窗口内占比。它是 ④ 严重度横条的紧凑版。 -->
          <span class="sevmini" data-anim="9" :title="sevMiniTitle">
            <i
              v-for="s in SEVERITIES"
              :key="s"
              :style="{ flexGrow: sessionBySev[s] || 0, background: severityColor(s) }"
            ></i>
          </span>
          <div class="cell">
            <b>{{ lat.p99 }}<i>ms</i></b>
            <span>P99 耗时（n={{ lat.n }}）</span>
          </div>
          <!-- 数据源：用户 2026-10-16 裁定 —— **留页头右上**。计数**常驻**（组件开着时自报，
               没开时本页补一行；见下面那个 `v-if="!demo.on"`）。 -->
          <DemoModeToggle />
          <span v-if="!demo.on" class="cnt">已放 {{ fmtInt(demo.emitted) }} / {{ DEMO_TOTAL }} 条（模拟日常未开）</span>
        </div>
        <div class="r2">
          <!-- ── 三枚控件（用户 2026-10-16 裁定 A：**现在就补、不留 TD**，都是真功能）────────
               ⚠️ 三枚都必须能改到**别处看得见的东西**，否则就是把占位换了个说法：
                 时间范围 → `ranged`（③ 列表 / ① 读数 / ② 计数 / ④ 榜全部跟着变）
                 导出     → 真下载一个文件（前端生成，不经后端）
                 密度     → `--row-h`（③ 的行高 22 / 28px）                                    -->
          <span class="seg" :title="'时间范围：③ 的列表 / ① 的读数 / ② 的计数 / ④ 的榜全部只算这个范围内的行'">
            <span class="lab">时间范围</span>
            <button
              v-for="r in RANGES"
              :key="r[0]"
              type="button"
              class="chip"
              :class="{ on: range === r[0] }"
              @click="setRange(r[0])"
            >{{ r[1] }}</button>
            <template v-if="range === 'custom'">
              <input v-model="customFrom" class="dt" type="datetime-local" step="1" title="起（含）· 留空 = 不限" >
              <span class="tilde">~</span>
              <input v-model="customTo" class="dt" type="datetime-local" step="1" title="止（含）· 留空 = 不限" >
            </template>
          </span>
          <span class="seg" :title="'导出当前列表（③ 里看见的那些，时间正序）—— 前端直接生成文件，不经后端'">
            <span class="lab">导出</span>
            <button
              v-for="x in EXPORTS"
              :key="x[0]"
              type="button"
              class="chip"
              :title="`导出当前列表（${filtered.length} 条）为 ${x[1]}：${x[2]}`"
              @click="doExport(x[0])"
            >{{ x[1] }}</button>
            <span v-if="exportNote" class="hint" :title="exportNote">{{ exportNote }}</span>
          </span>
          <span class="seg" :title="'行高：紧凑 22px / 舒适 28px（只影响 ③，不动别的量）'">
            <span class="lab">密度</span>
            <button
              v-for="d in DENSITIES"
              :key="d[0]"
              type="button"
              class="chip"
              :class="{ on: density === d[0] }"
              @click="density = d[0]"
            >{{ d[1] }}</button>
          </span>
          <!-- ⚠️ 必须是**两个 span**（不是一段内联文本）：`.tz` 是 flex column 容器，内联文本的
               每个片段都会各自变成一个 flex item、各占一行 —— 实测撑成 71px。拆成两个 span ⇒ 两行。 -->
          <span class="tz">
            <span>第 <b>{{ seq }}</b> 拍 · 每秒 1 拍 · {{ lastTickAt }}</span>
            <span><i class="mqdot" :class="{ off: !mqConnected }"></i>MQ {{ mqConnected ? '已连接' : '未连接' }}</span>
          </span>
        </div>
      </header>

      <!-- ══ 条件块（动效落点 8）：判据是 `lost`、**不是** `errMsg` ═══════════════════════
           ⚠️ 服务端**正常收尾**（`onEnd(null)`）时 `errMsg` 一路为空，而那种情况下不提示就等于
           「数字静静停在上一次连接的那一拍」—— 页面上一切正常、其实早就不更新了。未触发时 `v-if` 不占位。 -->
      <p v-if="lost" class="ev-cond" data-anim="8">
        <span>{{ lostText }}</span>
        <button type="button" class="ev-btn" @click="connect">立即重连</button>
      </p>
      <!-- MQ 未连接：**压缩成一行**（原文那段长说明搬进 ① 下排的 note 与 ② 栏脚）。 -->
      <p v-else-if="!mqConnected" class="ev-cond is-warn">
        <span>MQ 未连接：八个环的事件都只在<b>进程内存</b>里，<b>不落库</b> —— 这台服务一重启就没了。</span>
        <span class="sub">（后端每 5 秒实探一次；连上后本行自动消失）</span>
      </p>

      <!-- ══ 中段（唯一伸缩的一行）：三栏 ═══════════════════════════════════════════════ -->
      <div class="ev-body">
        <!-- ── ② 过滤栏（图例兼筛选）────────────────────────────────────────────── -->
        <aside class="ev-rail">
          <div class="rl"><span class="idx">②</span> 过滤栏</div>

          <div class="ctl">
            <label class="ln">
              <span class="lab">搜索</span>
              <input v-model="q" class="box" type="text" placeholder="标题 / 明细 / traceId…" >
            </label>
            <span class="ln">
              <span class="lab">批次</span>
              <button type="button" class="chip" :class="{ on: !warnUp }" @click="warnUp = false">全部</button>
              <button type="button" class="chip" :class="{ on: warnUp }" @click="warnUp = true">≥ WARN</button>
            </span>
            <span class="ln">
              <span class="lab">对象</span>
              <span class="box is-ro" :title="objFilter || '点 ③ 里的对象 ⇒ 填到这里'">
                {{ objFilter || '点 ③ 里的对象 ⇒ 填到这里' }}
                <button v-if="objFilter" type="button" class="x" @click="objFilter = ''">×</button>
              </span>
            </span>
          </div>

          <!-- ⚠️ 只有**行清单**滚（`.rows`）；口径 / 严重度 / 「全部打开」钉在栏底不滚 ——
               窗口化之后栏只有一屏高，口径要是跟着滚走了，图例就变成了没有出处的数字。
               ⚠️ 动效落点 4（计数位数滚动）与 7（计数变化闪一下）**落在同一处**（同一个数字上的
               两个效果），标注写成逗号列表是刻意的，不是漏数。 -->
          <div class="rows" data-anim="4,7">
            <button type="button" class="row all" :class="{ off: !isAllOn }" @click="resetFilters">
              <!-- ⚠️ 空 `.ic` 不是漏写：9 类那几行每行都有一个图标列，这里留一个等宽的占位，
                   否则「全部」这一行的勾选框/文字会与下面 9 行**对不齐**（用户在截图里一眼能看出）。 -->
              <span class="sw"></span><span class="ic"></span><span class="k">全部</span>
              <span class="c">{{ fmtInt(ranged.length) }}</span>
            </button>
            <button
              v-for="k in KINDS"
              :key="k"
              type="button"
              class="row"
              :class="{ off: !activeKinds.has(k) }"
              :style="{ '--c': kindColor(k) }"
              :title="`${kindLabel(k)}：范围内 ${sessionByKind[k] || 0} 条（点一下${activeKinds.has(k) ? '关掉' : '打开'}这一类）`"
              @click="toggleKind(k)"
            >
              <span class="sw"></span>
              <!-- 用户 2026-10-16「② 过滤栏 9 类：无 → 每类前加图标」，映射见 `KIND_ICON` 上方注释。 -->
              <span class="ic"><component :is="KIND_ICON[k]" class="ev-ic s" /></span>
              <span class="k">{{ kindLabel(k) }}</span>
              <span class="c">{{ fmtInt(sessionByKind[k] || 0) }}</span>
            </button>

            <!-- ── 设施 3 类（用户 2026-10-16 裁定 D：框架做、**置灰**）──────────────────
                 ⚠️ `disabled` 而不是「点一下弹个提示」：这三类**后端一个写入点都没有**
                    （Java 侧不存在 `SLOW_SQL` / `MQ` / `SCHEDULER`），点得动就是在骗人。
                 虚线边框 = 同 ⑥ 开关栏对「后端 WIRED 清单里没有它」的同一套画法。
                 ⚠️ 计数列写 `—` 而不是 `0`：`0` 读起来像「这一类存在、只是此刻没有」。 -->
            <div class="sep" :title="FACILITY_TIP"><span>设施 3 类 · 后端面待落地</span></div>
            <button
              v-for="f in FACILITY_KINDS"
              :key="f.k"
              type="button"
              class="row is-fac"
              disabled
              :title="FACILITY_TIP"
            >
              <span class="sw"></span><span class="ic"></span>
              <span class="k">{{ f.label }}</span>
              <span class="c">—</span>
            </button>
          </div>

          <!-- ⚠️ 口径必须写在**图例旁边**：数字是**本页自己攒的范围内累计**，帧里那个 `counts`
               是「本拍」的 —— 拿它当图例数字，每次静默的一拍上会全体归零。
               ⚠️ 「覆盖与边界」5 条原文的落点（裁定 C：接受压缩，但要告诉用户全文在哪）：
                 小字 = 这行；全文 = 悬停 `title`（`COVERAGE_FULL`）；出处 = 组件顶部注释 +
                 `_work/3a3/日志页-覆盖与边界-全文.md`。 -->
          <div class="sub" :title="COVERAGE_FULL">
            口径：严重度 · 类计数 = 本页<b>范围内累计</b>（受 ① 时间范围约束；帧里的 counts 是「本拍」的，不用它）
            <br>覆盖与边界 5 条已压缩上屏 —— <b>悬停看全文</b>（全文也在组件顶部注释里）
          </div>
          <div class="sev">
            <button
              v-for="s in SEVERITIES"
              :key="s"
              type="button"
              class="sevchip"
              :class="{ off: !activeSevs.has(s) }"
              :style="{ '--c': severityColor(s) }"
              :title="`严重度 ${severityLabel(s)}：窗口内 ${sessionBySev[s] || 0} 条`"
              @click="toggleSev(s)"
            >
              <i class="dot"></i>{{ severityLabel(s) }} {{ fmtInt(sessionBySev[s] || 0) }}
            </button>
          </div>
          <button type="button" class="foot" :disabled="isAllOn" @click="resetFilters">全部打开 / 清筛选</button>
        </aside>

        <!-- ── ③ 事件流（控制台 / tail -f）───────────────────────────────────────── -->
        <!-- ⚠️ `--row-h` 挂在 `.ev-col`（不是 `.ev-list`）：`.log-head` 与 `.log-line` 是**同一条
             栅格的上下两段**，行高必须由同一个变量喂 —— 只改列表会让表头与行对不上。 -->
        <div class="ev-col" :style="{ '--row-h': ROW_H[density] }">
          <!-- 每秒事件速率折线（动效落点 3：尾部随每一拍右推）。⚠️ 本页自攒 `rateSeries`，
               不是帧里的任一字段（帧只有**本拍**的 rate）。 -->
          <div class="ev-spark" data-anim="3">
            <!-- 图内小字标签（用户 2026-10-16 选乙：**补标签、不补坐标轴**）。四个标：
                 上排 峰值 / 当前（量级），下排 −60s / now（时间范围）。
                 ⚠️ y 的「当前」取序列**最后一拍**（= 右端那个点），不是 `sparkMax`；
                    x 的范围就是 `RATE_CAP` 拍 ≈ 一分钟，故直接写 −{{ RATE_CAP }}s。
                 ⚠️ 只标这两件事，**不画轴线、不画刻度线** —— 画了就是坐标轴，用户的判据是「一眼看懂
                    图上画的是什么、多大量级」，不是「能读出任意一点的值」。 -->
            <div class="ax">
              <span>峰值 {{ fmtRate(sparkMax) }}/s</span>
              <span class="r">当前 {{ fmtRate(sparkLast) }}/s</span>
            </div>
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" height="34" role="img" :aria-label="`每秒事件速率折线：峰值 ${fmtRate(sparkMax)} 条/秒，当前 ${fmtRate(sparkLast)} 条/秒，横轴最近 ${RATE_CAP} 秒`">
              <!-- ⚠️ 描边用 `--brand-ink`（文字用主色）**不是** `--brand-soft`：后者在 admin 暗色块里
                   是 `#15264a`，铺在 `--surface` `#172032` 上对比度约 1.1:1 —— 等于隐形
                   （探针能量到它在、有 4 个点，**肉眼完全看不见**）。这是「探针绿 ≠ 看得见」。 -->
              <polyline :points="sparkPts" fill="none" stroke="var(--brand-ink)" stroke-width="1" stroke-linejoin="round" vector-effect="non-scaling-stroke" />
            </svg>
            <div class="ax">
              <span>−{{ RATE_CAP }}s</span>
              <span class="r">now</span>
            </div>
            <div class="cap">每秒事件速率（本页窗口内最近 {{ RATE_CAP }} 拍）—— 尾部随每一拍右推</div>
          </div>

          <div class="log-head">
            <span></span><span>时刻</span><span></span><span>等级</span><span>类</span><span class="ms">消息（点带 traceId 的行 ⇒ ⑤ 链路展开）</span>
          </div>

          <ul id="ev-list" ref="listEl" class="ev-list" @scroll.passive="onScroll">
            <li v-if="!streamRows.length" class="ev-empty">
              {{ first ? '还没收到第一拍。' : (events.length ? '当前筛选下一条也没有。点 ② 的「全部打开」还原。' : '本会话还没收到任何事件 —— 这台服务起来之后八个环一直没有写入点被触发。') }}
            </li>
            <!-- 窗口化渲染的上下空白（高度 = 未渲染行的实测/估算和），用以撑住 scrollHeight。 -->
            <li v-if="virtualOn" class="vpad" aria-hidden="true" :style="{ height: padTop + 'px' }"></li>
            <li
              v-for="(r, vi) in vRows"
              :key="r.key"
              :data-vidx="virtualOn ? vStart + vi : undefined"
              class="log-line"
              :class="[r.cls, { 'is-openable': !!r.traceId, sel: r.traceId && r.traceId === openedTrace, 'is-tree': r.tree }]"
              :style="{ '--pc': r.pc, '--c': r.color, '--sevc': r.sevColor }"
              :data-d="r.d"
              :role="r.traceId ? 'button' : undefined"
              :tabindex="r.traceId ? 0 : undefined"
              :title="r.traceId ? '点开看这一次请求的完整链路' : '这条不在请求线程上（没有 traceId），看不到链路'"
              @click="openTrace(r)"
              @keydown.enter.prevent="openTrace(r)"
              @keydown.space.prevent="openTrace(r)"
            >
              <!-- `.ln` = 折行的容器（`max-height` 截 2 行，hover / 聚焦展开全文）；`.flow` = 里面那**一行自然流**。
                   ⚠️ 拆两层是为了**将来换截断机制时不动结构**（`max-height` 并不要求有内层，
                      但保留它，`.flow` 也是 `word-break` 的落点）。 -->
              <span class="ln"><span class="flow">
                <!-- 前导符拆两段：`rl` = 竖向脚手架（画淡）、`gl` = 分叉（画亮）—— 见 `buildStream` 的注释。 -->
                <span class="pre"><span class="rl">{{ r.rail }}</span><span class="gl">{{ r.gl }}</span></span>
                <span class="tm">{{ r.t }}</span>
                <span class="ic">
                  <component :is="KIND_ICON[r.kind]" v-if="KIND_ICON[r.kind]" class="ev-ic" />
                  <i v-else-if="r.kind === '__req'" class="reqmark"></i>
                </span>
                <span class="lv">{{ r.lv }}</span>
                <span class="md">{{ r.md }}</span>
                <span class="ms">
                  <b>{{ r.title }}</b>
                  <!-- A 块「自然流」：从句接在标题后（`sentence()` 按 kind 分派产出分段 token）。
                       ⚠️ 整段 `v-if` = **缺槽位就不出现**（空数组 ⇒ 连那个 8px 的外距都不占）。 -->
                  <span v-if="r.mid.length" class="se-mid"><!--
                    --><span v-for="(s, i) in r.mid" :key="'m' + i" :class="'se-' + s.c">{{ s.t }}</span></span>
                  <!-- 方案 D 甲「对象可点」：有 `objectId` 才画成可点（下划线 + 品牌色 + pointer），
                       点一下把 ② 的「对象」框填上 ⇒ ③ 只剩这个对象的日志。没有 id 的对象**不给**
                       可点的样子（画了就是骗人）。 -->
                  <span
                    v-if="r.obj"
                    class="ob"
                    :class="{ p: !!r.objId }"
                    :title="r.objId ? '点一下 ⇒ 只看这个对象的日志（会填进 ② 的「对象」框）' : ''"
                    @click.stop="r.objId && (objFilter = r.objId)"
                  >{{ r.obj }}</span>
                  <!-- 第一层的技术字段：**默认常显**（用户 2026-10-17 收尾批 2）。
                       每一项 = `中文键 值`；`k` 是中文键名、`v` 是人话值（键的原文见 ⑤ 第三层）。 -->
                  <span v-if="r.fields.length" class="dt"><!--
                    --><span v-for="(f, i) in r.fields" :key="'f' + i" class="tf"><span class="tf-k">{{ f.k }}</span> <span class="tf-v">{{ f.v }}</span></span></span>
                  <!-- 五要素里的「谁」：`发起人 M205（商家）`。没有 actor（dev / a3 闸关）⇒ 整段不出现。 -->
                  <span v-if="r.act" class="se-act">发起人 <span class="v">{{ r.act }}</span></span>
                  <!-- `src=环名#源seq` 只在 `detail` 尾部，单独成段 `来源 cache#0`。 -->
                  <span v-if="r.src" class="se-src">来源 <span class="v">{{ r.src }}</span></span>
                  <!-- 括号 = 上下文（`sentence()` 的 `ctx`：trace 短号 · 耗时）。
                       ⚠️ 空数组 ⇒ **整对括号都不画**（不是画一对空括号）。 -->
                  <span v-if="r.ctx.length" class="se-ctx"><!--
                    -->（<span v-for="(s, i) in r.ctx" :key="'c' + i" :class="'se-' + s.c">{{ s.t }}</span>）</span>
                  <!-- 请求行（组头 / 组尾）的原样串；事件行走 `fields` 那一支，这里是空的。 -->
                  <span v-if="r.rawDetail" class="dt raw">{{ r.rawDetail }}</span>
                </span>
              </span></span>
            </li>
            <li v-if="virtualOn" class="vpad" aria-hidden="true" :style="{ height: padBottom + 'px' }"></li>
          </ul>
        </div>

        <!-- ── ④ 实时统计（驾驶舱）──────────────────────────────────────────────── -->
        <aside class="ev-live">
          <div class="ev-h2"><b><span class="idx">④</span> 实时统计</b></div>

          <div class="ev-sub">Top-N 榜（三档可切）</div>
          <!-- 动效落点 12：换档会把列表**重排**（不是三个静态块并排）。 -->
          <div class="ev-top" data-anim="12">
            <div class="tabs">
              <button
                v-for="x in TOP_TAB"
                :key="x[0]"
                type="button"
                class="tb"
                :class="{ on: topTab === x[0] }"
                @click="topTab = x[0]"
              >{{ x[1] }}</button>
            </div>
            <div class="lst">
              <div v-if="!topRows.length" class="empty">
                {{ topTab === 'err' ? '本页范围内 error 0 条 ⇒ 榜单为空（不给假数据）。' : '范围内还没有可统计的对象。' }}
              </div>
              <div v-for="(r, i) in topRows" :key="i" class="tr1">
                <span class="nm" :title="r[0]">{{ r[0] }}</span>
                <span class="v">{{ r[1] }}</span>
                <span class="v">{{ r[2] }}</span>
              </div>
            </div>
            <div class="cap">榜按<b>范围内累计</b>算；慢请求取 traces 记录里的最大耗时（同路径聚合）</div>
          </div>

          <div class="ev-sub">类计数 · 业务 {{ KINDS.length }} 类 + 设施 {{ FACILITY_KINDS.length }} 类（范围内累计）</div>
          <div class="ev-bars">
            <div v-for="k in KINDS" :key="k" class="ev-bar" :title="`${kindLabel(k)} ${sessionByKind[k] || 0} 条`">
              <!-- 用户 2026-10-16「④ 类计数 9 类：无 → 每类前加图标」（与 ② 同一套 `KIND_ICON`）。
                   ⚠️ D 块：图标与文字之间原来是**一个空格**（12px 字号下 ≈3px），达不到 6–8px
                   ⇒ 删掉那个空格、改由 `.nm` 的 `gap` 定间距（间距从此是 CSS 里量得出来的一格）。 -->
              <span class="nm"><component :is="KIND_ICON[k]" class="ev-ic s" :style="{ color: kindColor(k) }" />{{ kindLabel(k) }}</span>
              <span class="track"><span class="fill" :style="{ width: barW(sessionByKind[k] || 0, kindMax) }"></span></span>
              <span class="num">{{ fmtInt(sessionByKind[k] || 0) }}</span>
            </div>
            <!-- 设施 3 类（裁定 D）：横条画出来但**恒空**、**置灰**、计数写 `—` —— 见 ② 里的长注释。 -->
            <div
              v-for="f in FACILITY_KINDS"
              :key="f.k"
              class="ev-bar is-fac"
              :title="FACILITY_TIP"
            >
              <span class="nm">{{ f.label }}</span>
              <span class="track"><span class="fill" style="width: 0"></span></span>
              <span class="num">—</span>
            </div>
          </div>

          <div class="ev-sub">严重度分布（点某一档 ⇒ 过滤 ③）</div>
          <div class="ev-bars sev">
            <button
              v-for="s in SEVERITIES"
              :key="s"
              type="button"
              class="ev-bar"
              :title="`点一下 ⇒ 只看这一档`"
              @click="toggleSev(s)"
            >
              <!-- 用户 2026-10-16「④ 严重度分布：info / warn / error 各加图标」（映射见 `SEV_ICON`）。
                   ⚠️ 类计数那三条用的是**类**的图标，这三条用的是**严重度**的图标 —— 两个维度各自的
                     一套，同一个事件可能同时出现在「缓存」和「error」两个条上，图标不该一样。
                   ⚠️ D 块：图标后那个空格与上面类计数那条**同款**，一起删（间距交给 `.ev-bar .nm`
                     的 `gap`）—— 只删一处会让 ④ 的两排横条间距不同档。 -->
              <span class="nm"><component :is="SEV_ICON[s]" class="ev-ic s" :style="{ color: severityColor(s) }" />{{ severityLabel(s) }}</span>
              <span class="track"><span class="fill" :style="{ width: barW(sessionBySev[s] || 0, sevMax), background: severityColor(s) }"></span></span>
              <span class="num">{{ fmtInt(sessionBySev[s] || 0) }}</span>
            </button>
          </div>

          <div class="ev-sub">读数（错误率在 ①，这里不重复）</div>
          <div class="ev-read">
            <span>本拍事件<i>{{ fmtInt(freshEvents) }}</i></span>
            <span>本拍链路<i>{{ fmtInt(freshTraces) }}</i></span>
            <span>范围内合计<i>{{ fmtInt(ranged.length) }}</i></span>
            <span>丢帧<i>{{ fmtInt(droppedTotal) }}</i></span>
            <span>耗时 P50 / P95 / P99<i>{{ lat.p50 }} / {{ lat.p95 }} / {{ lat.p99 }} ms</i></span>
          </div>
        </aside>
      </div>

      <!-- ══ ⑤ 链路展开（默认塌成一条，点 ③ 的行才拉起）═══════════════════════════════
           ⚠️ 数据源**只有两处**：本页窗口内**带同一个 traceId 的事件** + 后端帧里那条 `traces`
              记录（请求的起止与总耗时）。**没有**「按 traceId 查历史」的端点，也不该加。
           ⚠️ 起止那两行**推不出**：事件只说「某事发生了」，说不了「请求何时结束」。
           ⚠️ 演示模式下帧里的 `traces` 恒空 ⇒ 起止两行必然缺席（显示「起止还没到」），**不伪造**。 -->
      <div class="ev-drawer" data-anim="6" :class="{ 'is-open': !!openedTrace }">
        <div class="hd" @click="openedTrace && (drawerOpen = !drawerOpen)">
          <b><span class="idx">⑤</span> 链路展开</b>
          <template v-if="openedTrace">
            <code class="mono">{{ openedTrace }}</code>
            <span v-if="openedTraceRec" class="st" :class="statusClass(openedTraceRec.status)">{{ openedTraceRec.status }}</span>
            <span v-if="openedTraceRec" class="kv">{{ openedTraceRec.method }} {{ openedTraceRec.path }} · 总耗时 <b>{{ openedTraceRec.ms }} ms</b></span>
            <span class="cnt">{{ openedEvents.length }} 条事件</span>
            <button type="button" class="ev-btn" @click.stop="drawerOpen = !drawerOpen">{{ drawerOpen ? '收起' : '展开' }}</button>
            <button type="button" class="ev-btn" @click.stop="closeTrace">关闭</button>
          </template>
          <span v-else class="hint">点 ③ 里带 traceId 的行 ⇒ 这里显示那一次请求的完整链路（没有 traceId 的行不给点）</span>
        </div>
        <ol v-if="openedTrace && drawerOpen" class="chain">
          <li v-if="openedTraceRec" class="chain-i is-ends">
            <span class="tm">{{ hmsms(openedTraceRec.startMs) }}</span>
            <span class="d">请求开始（{{ startLabel(openedTraceRec) }}）</span>
          </li>
          <li v-else class="chain-i is-miss">
            <span class="tm">—</span>
            <span class="d">这一次请求的「起止」还没到：它的收尾记录在请求返回时才写（长连接与流线程不记；演示模式下恒空）</span>
          </li>
          <li v-for="e in openedEvents" :key="e.seq" class="chain-i">
            <span class="tm">{{ hmsms(e.ts) }}</span>
            <span class="d">
              <!-- D 块：图标↔文字 8px 的落点之一。⚠️ 原来这里是**一个空格**（15px 字号下 ≈4px），
                   达不到 6–8px；改成行内弹性盒 + `gap` ⇒ 间距是**量得出来的**一格，不是字体度量。 -->
              <span class="d-hd"><component :is="KIND_ICON[e.kind]" class="ev-ic s" /><b>{{ kindLabel(e.kind) }}</b></span> · {{ e.title }}<template v-if="objText(e)">（{{ objText(e) }}）</template>
              <!-- 第二层：**技术字段原文**（用户 2026-10-16「打开详细 ⇒ 中英文都要 · 两个都不能省」）。
                   ③ 的预览层只给中文人话，这一层把帧里的原名补齐 —— 中文方便看，英文方便和后端确认。 -->
              <code class="tech">{{ techLine(e) }}</code>
            </span>
          </li>
          <li v-if="openedTraceRec && !openedEvents.length" class="chain-i is-miss">
            <span class="tm">{{ hmsms(openedTraceRec.startMs) }}</span>
            <span class="d">这一次请求<b>没有产生任何事件</b>（它没碰到八个环中的任何一个）</span>
          </li>
          <li v-if="openedTraceRec" class="chain-i is-ends">
            <span class="tm">{{ hmsms(openedTraceRec.endMs) }}</span>
            <span class="d">请求结束 · 总耗时 <b>{{ openedTraceRec.ms }} ms</b></span>
          </li>
        </ol>
      </div>

      <!-- ══ ⑥ 开关栏（3a.6 乙案引入；本页只占位，本轮不接行为）════════════════════ -->
      <div class="ev-switch">
        <b><span class="idx">⑥</span> 开关栏</b>
        <div class="grp"><span class="lab">防护闸</span>
          <span
            v-for="k in PROTECTIONS"
            :key="k"
            class="sw2"
            :class="{ dead: !protectionWired(k) }"
            :title="protectionNotWiredTip(k)"
          >{{ protectionLabel(k) }}<template v-if="!protectionWired(k)"> · 不可开关</template></span>
        </div>
        <div class="grp"><span class="lab">注入</span>
          <span
            v-for="k in INJECTIONS"
            :key="k"
            class="sw2"
            :class="{ dead: INJECTIONS_NOT_WIRED.includes(k) }"
            :title="INJECTIONS_NOT_WIRED.includes(k) ? '未接通：拨了读数不会变（后端 AttackEngine.INJECTIONS_NOT_WIRED 的镜像）' : ''"
          >{{ injectionLabel(k) }}<template v-if="INJECTIONS_NOT_WIRED.includes(k)"> · 不可开关</template></span>
        </div>
        <span class="n">3a.6 乙案引入 · 本页只占位（本轮不接行为）· 虚线 = 后端 WIRED 清单里没有它</span>
      </div>
    </div>
  </section>
</template>

<!--
  管理端「实时日志」页（C3 块 7）。

  ⚠️ **3a.3 第 ⑥ 步（2026-10-16）把本页从「纵向卡片流」重做成「一屏六区的三栏驾驶舱」** ——
     结构来自草图 `docs/美化/c3-admin-preview/3a3-甲-三栏驾驶舱.html`（用户从甲/丙里选了甲）。
     六区：① 状态条 ② 过滤栏 ③ 事件流（核心）④ 实时统计 ⑤ 链路展开 ⑥ 开关栏。
     判据、逐条口径、与草图的差异见 `_work/3a3/3a3-第6步-落地方案.md`。

  ⚠️ **数据源 = `POST /admin/observe/events/stream`**：一帧 = 本拍增量 `events` + 三个汇总
     （`counts` 按类 / `severity` 按严重度 / `rate` 速率）+ 本拍新走完的 `traces`。
     契约与帧字段见 `api/observe.js` 的 `openEventStream` 注释。

  ⚠️ **③ 是 tail -f，不是表格**（用户口径）：时间正序、从底部追加、自动滚到底、行间零 gap、
     不带行边框；**原来的分页器（`Pager`）删了** —— 窗口化之后内部滚取代了分页。
     DOM 只留最近 `ROW_MAX` 行（1000 行 DOM 太重，而滚动窗口之外的行本来看不见）。

  ⚠️ **③ 的树形前导符是现算的，不是数据里带的**：本页的 `events` 没有前导符字段，
     树由「③ 里**连续**同 traceId 的行」在渲染时重建（规则见 `buildStream`）。
     ⚠️ 只有「连续 ≥2 行同 traceId **且** `traces` 里有这条记录」才包组 —— 单条走顶格行。
     演示模式下 `traces` 恒空 ⇒ 只显示顶格行，这是实话不是 bug。

  ⚠️ **页面卸载必须 `close()`**（`onBeforeUnmount`）：SSE 的 `StreamingResponseBody` 在服务端占
     **一条异步线程**，不断开就是一条常驻线程 —— 与 `MetricsPanel.vue` / `OrderFlow.vue` 同一条代价。

  ⚠️ **本页不用 ECharts**：那一张折线是纯 `<svg><polyline>`（34px 高的小图，引一个图表库不值当）。

  ══════════════════════════════════════════════════════════════════════════════════
  3a.3 第 ⑥ 步 · 收尾五件（用户 2026-10-16 四条裁定 A/B/C/D/E + C5 判据修补）
  ══════════════════════════════════════════════════════════════════════════════════

  **A（修正）· ① 第二排的三枚控件补上 —— 不留 TD。** 三个都是真功能，不是占位：
     · 时间范围（近 5 分 / 近 30 分 / 自定义）→ 改 `ranged`，③ 列表 + ① 读数 + ② 计数 + ④ 榜全跟着重算
     · 导出（CSV / JSON / ndjson）→ 前端 `Blob` 生成文件下载，**不经后端**；导出的就是当前列表
     · 密度（紧凑 22px / 舒适 28px）→ `--row-h`，只切 ③ 的行高

  **B（修正）· 切源 = 隔离，不是清空。** 每条事件带 `source: 'normal' | 'demo'`，
     显示层按当前模式取桶（`W.normal` / `W.demo`）；切源**不动任何数据**、只换显示，切回来原样还在。
     ⚠️ 这**改掉了既有语义**：原来是「一套 window + 跨源保留」，实测会显示成「窗口内 159 + 演示 41」混在一起。

  **C（修正）· 压缩 + 告知全文位置。** 「覆盖与边界」5 条压缩上屏在 ② 栏底那行小字，
     全文在它的 `title`（= 下面 `COVERAGE_FULL`）、本注释、以及 `_work/3a3/日志页-覆盖与边界-全文.md`。
     ⚠️ ① 下排原来那条 `.note`（也是这 5 条的一部分）已挪进 ②，为三枚控件腾出一行 —— 信息没删。

  **D · 设施 3 类：框架做、置灰。** ② 的图例与 ④ 的横条各多一组「设施 3 类」（慢 SQL / MQ / 调度），
     `disabled`、虚线、计数写 `—`。⚠️ 后端 Java 侧**根本不存在**这三类 —— 画出来是为了让缺口
     在页面上看得见，不是为了假装有。

  **E · ⑤ 底部塌条**：保持原样（用户接受）。

  ══════════════════════════════════════════════════════════════════════════════════
  3a.3 第 ⑦ 步 · 视觉与呈现（用户 2026-10-16 四条裁定 + 三条视觉修正 + 三条消息规则）
  ══════════════════════════════════════════════════════════════════════════════════

  **滚动条（视觉修正一）· 核实 = 上一轮没落地 ⇒ 本轮重做。**
     `git log -S "webkit-scrollbar"` 对观察目录为空：那条 7px 规则只在第 ④ 步的草图
     `docs/美化/c3-admin-preview/_struct-wire-ev.css`（`.ev-pane`）里，第 ⑥ 步做成真组件时漏搬。
     本轮落到四个滚动容器（② 栏 / ③ 列表 / ④ 栏 / ⑤ 链路）：≤8px、轨道透明、滑块淡灰、hover 加深。
     ⚠️ **刻意不写 `scrollbar-width` / `scrollbar-color`** —— Chrome ≥121 写了会让 `::-webkit-scrollbar` 整块失效。

  **折线图（视觉修正二）· 选乙：图内小字标签，不补坐标轴。**
     上排「峰值 X/s / 当前 Y/s」、下排「−60s / now」，`--ink-3` + `--fs-xs`，不画轴线、不画刻度。
     ⚠️「当前」= 序列**末项**，不是 `sparkMax`（峰值标成当前会在曲线掉回 0 之后继续骗人）。

  **对比度（视觉修正三）· 横条前景/背景 ≥ 3:1，只用现有 token。**
     真凶是 `.ev-bar .fill` 的 `--brand-soft`（`#15264a`）铺在轨道 `--surface-2`（`#1f2a3f`）上
     ≈ **1.05:1** —— 不是「对比度低」，是等于看不见（本页第二次栽在这个色上，第一次是①折线）。
     换成填充档 `--brand`（≈3.1:1）；严重度那三条本来就用 `--success/--warning/--danger`（5～6.6:1）。

  **图标（视觉修正三·第二半）· 4 处，映射照抄用户给的那张表。**
     `KIND_ICON` 相对上一版**换掉五个**（order/audit/notify/sensitive/identity），新增 `SEV_ICON`。
     ② 九类行、④ 类计数九条、④ 严重度三条都加；③ 事件流本来就有（用户「有 ✓ 保留」）。
     ⚠️ 「全部」与设施 3 类那几行放**空 `.ic` 占位**，否则 9 行的左缘参差。

  **消息呈现（收尾批 2 定稿）· 第一层中文主消息 + 全 fields + actor + src；折行最多 2 行、悬挂缩进 2ch。**
     · ③ 数据行从**栅格**改成**一行自然流**（`.ln` 折行容器 + `.flow` 行内流）：栅格里折行只发生在
       格子内部，`1fr` 那格一折就撑高整行、前五格却不缩进 —— 做不出「续行缩进、不带前导符」。
       列宽用等值的 inline-block 宽度 + 8px `margin-right` 复刻原 `gap` ⇒ 与表头的列**逐像素对齐不变**。
     · 技术字段（`.dt`）**默认常显**（`中文键 值` 逐对排），不再等 hover；超过 2 行靠纯 CSS
       `hover` 展开。⚠️ 3a.3 起 `traceShort` 那一格（`.tr`）**删了** —— trace 短号搬进括号里常显。
     · ⑤ 详情层**中英文都要**（用户：「两个都不能省」）：人话一行 + `techLine()` 的
       `中文（英文原键）= 值` 一行（值照原文，不翻）。

     ⚠️ **「紧凑密度量到 25px 而不是 22px」的真因**（第 ⑦ 步返工时抓到，记在这里免得下次再找）：
       数据行改成行内流之后，前导符 / 时刻 / 图标 / 级别 / 类 这五格成了 `inline-block`，
       `line-height` 继承 1.45 ⇒ 盒子 17.39px，比父级行盒（12px 字 × 1.45 = 17.4）**还高**；
       基线对齐时高出来的一截把整行顶到 22.78px ⇒ 加上下 1px 内边距 = 24.78 ≈ 25px，而表头还是 22px。
       解法：这五格 `line-height: 1`（盒子高 = 字号，落回行盒内）⇒ `.ln` 22.78 → 17.39，行高回到 `--row-h`。
       另一条候选 `vertical-align: middle` 也能到 17.39，**不取**：middle 对齐的是盒子中心不是基线，
       会让这五格的字整体下移、与消息列错开 —— 数字对了、形态错了。
       定位手段 = 探针 ⑱ 的**盒解剖**（`DBG=1 node scripts/probes/probe-b7a3-eventlog-rewrite.mjs`）。
     ⚠️ 「最多 2 行」原来用 `-webkit-box` + `-webkit-line-clamp: 2`：那套盒模型**自带 23px 固定高**，
       即使内容是 16px 也撑着 —— 换成 `max-height: 2 × 行高` + `overflow: hidden` 才与行高对得上。
       实测（探针 ㉓5b，注入一段长文本量的）：`.ln` clientHeight 35 = 2 × 17.4、scrollHeight 55 ⇒ 夹住了；
       续行缩进实测 **14.4px = 2.01ch**（相对这一行文本流的首行起点），续行上没有第二遍前导符；
       hover 解除上限 ⇒ clientHeight 追平 55。放大图 `_work/3a3/zoom-b7a3-wrap.png` 可肉眼复核。

  **树形（裁定 B 甲）· 取证结论是「画对了，但看不见」⇒ 改画法，不改判据。**
     真后端 60 拍实测（`scripts/probes/forensics-b7a3-cache-noise.mjs` → `b7a3-cache-noise.txt`）：
     ③ 那 200 行里连续同 trace ≥2 的 run **有 12 段**（逐拍 5～22 段）⇒ 树**一直在画**。
     看起来是平的原因是画法：`├─`/`└─` 用 `--ink-3`、12px、2ch 宽，暗底上读起来像项目符号。
     ⇒ 前缀**拆两段**（`rl` 竖向脚手架画淡、`gl` 分叉画亮）+ 树形行（`is-tree`）整体提亮一档。
     ⚠️ 顺带删掉 `buildStream` 里冗余的 `cnt >= 2`（与 `j - i >= 2` 可证等价）。
     ⚠️ **先预判后实测、被实测推翻**的一条也记在这里：原以为「成组判据里的 `recFor` 闸会挡住大量
        分组」，逐拍实测 **12 个采样点差值恒 0** ⇒ 拿掉它一段也多不出来，故**不做**这次改动。
     ⚠️ 76%（152/200）是缓存类、且几乎每条一个独立 traceId ⇒ **run 长度 1 是数据决定的**
        （方案 C 丁已接受），不是代码舍弃的。见 **TD-79**。

  **对象可点（裁定 D 甲）· 功能已在（`.ob.p` + `objFilter`），本轮补强可点提示 + 修一处真错。**
     ⚠️ 原模板把 `.p` **硬编码**在 `.ob` 上（`class="ob p"`）⇒ 没有 `objectId` 的对象也画着
     下划线 + pointer，点了却什么都不发生 —— 那是**可点提示在骗人**。改成 `:class="{ p: !!r.objId }"`。

  **缓存噪声（裁定 C 丁）· 不治理，挂备忘。** 见 `streamRows` 上方那段 + **TD-79**。

  ── 「覆盖与边界」5 条 · 逐字全文（原出处 = `git show HEAD:` 本文件那一节，重做成窗口形态时压掉）──

  ① 事件是内存里的、有界且会被覆盖：八个源环各留最近 512 条，合并环再留最近 512 条；本页自己留最近
     1000 条。⇒「窗口内」是本页能看到的全部，不是历史总量。
  ② 首帧可能很大：冷启动时八个环可能各自攒了一屏，首帧会把合并环留得下的最后 512 条一次送出
     （页面按 1000 条封顶）。之后每拍只送真正新发生的（正常是个位数）。
  ③ 规则表在后端：严重度那三档（info / warn / error）由后端 severityOf 一处判定，本页只负责把 tag
     画成人话 —— 前端不重推一遍判据。
  ④ 对象列八类不是同一种东西：锁/缓存/通知取各自的键、订单取订单号、库存取 SKU、审计取对象类型 + #Id、
     敏感词取命中的词、身份取请求路径（逐类口径见 config/labels/observe-labels.js 的 OBJECT_FIELD_LABEL）。
  ⑤ 中文标签 commit 9 已全部收敛：本页 + 指标 / 状态流转 / 攻击模拟器四页的中文表只在 config/labels/
     下那一处（可观测域是本目录的 observe-labels.js）；页面只负责把后端帧里的 tag 画成人话，
     不重推任何判据。
-->

<script setup>
/* eslint-disable no-undef */ /* 理由见 `ui/KpiCard.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  Database, Lock, ShoppingCart, Package, Clipboard, Bell, AlertTriangle, Shield, ShieldCheck,
  Info, AlertCircle, AlertOctagon,
} from '@lucide/vue'
import { openEventStream } from '../../../api/observe'
// 演示模式（块 7 收尾 ②）：`DemoModeToggle` 摆开关，`demo` 是被 `watch` 的那个状态，
// `openDemoStream` 是与 `openEventStream` **同形**的假流 —— 见 `connect()` 里那处三元式。
import { DEMO_TOTAL, demo, openDemoStream } from '../../../api/demo-stream'
import DemoModeToggle from '../../../components/observe/DemoModeToggle.vue'
import {
  KINDS, SEVERITIES,
  kindLabel, kindColor, severityLabel, severityColor,
  objectFieldLabel,
  // 3a.3 收尾批 2：事件帧 `fields` 的键名中文（真帧键集，认不出回落英文原键）。
  fieldLabel, eventKeyLabel, NOTIFY_TO_LABEL, STOCK_KIND_LABEL,
  // ⑥ 开关栏的两组（PROTECTION_* / INJECTION_*）：**唯一出处是后端**
  //（`ProtectionGate.WIRED_KEYS` / `AttackEngine.INJECTION_KEYS` 的镜像）—— 本页只读它，不自造清单。
  PROTECTIONS, protectionLabel, protectionWired, protectionNotWiredTip,
  INJECTIONS, injectionLabel, INJECTIONS_NOT_WIRED,
} from '../../../config/labels/observe-labels'
// 3a.3 收尾批 2：顶层 `actor` = `{id, role}`，role 走用户域那张唯一表（1 客户 / 2 商家 / 3 管理员）。
import { roleLabel } from '../../../config/labels/user-labels'
// commit 9：审计那一类的 `objectType` 是英文域前缀（`goods` / `comment_image`…）⇒ 复用审计域那张表。
import { targetTypeLabel, auditActionLabel } from '../../../config/labels/audit-labels'
// 3a.3 · C 块：订单动作 / 状态两张表本来就在（`OrderFlow.vue` 在用）——本页拼句子时也用它们。
import { orderActionLabel, orderStateLabel } from '../../../config/labels/order-labels'
// 3a.3 · C 块新增的**端点表**：本页只有一个消费点（⑤ 链首「请求开始」那行的中文短名），
// 但它必须与其余五张表同处 `config/labels/`（那是本仓「code → 中文」的唯一落点）。
// ⚠️ 锁名 / 缓存区名两张也在那个文件里：本页**不直接引**它们的表，只走 `lockLabel` / `cacheAreaLabel`
//    两个访问器 —— 表是给人维护的，页面只认访问器（认不出回落原值的兜底就在访问器里）。
import { apiLabel, lockLabel, cacheAreaLabel } from '../../../config/labels/api-labels'

/** 本页自己累积的事件上限。⚠️ 必须有：SSE 每秒推若干条，不封顶跑一晚上就是几万条。 */
const EVENT_CAP = 1000
/** 本页自己累积的 `traces`（请求起止）上限。一次请求一行，比事件稀得多。 */
const TRACE_CAP = 300
/** ③ 的窗口化渲染阈值（用户 2026-10-17 性能微调）：行数 **≤** 这个值 ⇒ 全量渲染
 *  （小数据量窗口化没有收益，而且整表可见本身就是现有探针的判据）；**超过** ⇒ 只渲染
 *  视口内 + 上下各 `OVERSCAN` 行，数据侧仍保留最近 `EVENT_CAP`（1000）条。
 *  ⚠️ 行高不固定（1 行 22/28、2 行 36.78）⇒ 用「估算 + 实测回填」，见 `rowH`。 */
const VIRTUAL_MIN = 200
/** 窗口上下各多渲染几行：快速滚动时先有内容，再靠下一帧实测高度修正。 */
const OVERSCAN = 5
/** 未实测过的行高估值（px）。取 2 行态 34.78 + 上下 padding 2 ≈ 37 的近似；
 *  实测回填后会立刻接近真实值。⚠️ 不写死成 `--row-h`：那只代表单行态。 */
const EST_ROW_H = 36
/** ① 折线的拍数（本页自攒的速率序列长度）。60 ≈ 一分钟。 */
const RATE_CAP = 60
/** ④ Top-N 榜取前几。 */
const TOP_N = 5

/** 九类的真图标 —— **换成 `@lucide/vue`**（用户 2026-10-16 裁定 B：「换 lucide、按 9 类」）。
 *  ⚠️ `observe-labels.js` 的内联 SVG 版 `kindIcon` 仍然留着（别的地方可能用），本页不再消费它。
 *  用户 2026-10-16 给了**逐类的 lucide 名**（「图标缺失 —— 4 处都加」那条），照抄，不自己挑：
 *    cache→database / lock→lock / order→shopping-cart / stock→package / audit→clipboard /
 *    notify→bell / sensitive→alert-triangle / identity→shield / protection→shield-check
 *  ⚠️ 相对上一版**换了五个**（order 原 `FileText`、audit 原 `Scale`、notify 原 `Mail`、
 *    sensitive 原 `EyeOff`、identity 原 `User`）—— 不是笔误，是按用户给的映射重配。 */
const KIND_ICON = {
  cache: Database, lock: Lock, order: ShoppingCart, stock: Package, audit: Clipboard,
  notify: Bell, sensitive: AlertTriangle, identity: Shield, protection: ShieldCheck,
}
/** 严重度三档的图标（同一条裁定的后半句：「④ 严重度分布：info/warn/error 各加图标」）。
 *  ⚠️ 与 `KIND_ICON` 分开：严重度是**另一个维度**（同一类事件可以是 info 也可以是 error），
 *    两套图标各自独立，不互相借用。 */
const SEV_ICON = { info: Info, warn: AlertCircle, error: AlertOctagon }
/** ④ Top-N 榜的三档（值 = 数据取法，标签 = tab 文字）。 */
const TOP_TAB = [['obj', '对象'], ['slow', '慢请求'], ['err', '错误']]

/** ① 第二排的三枚控件（用户 2026-10-16 裁定 A：**现在就补、不留 TD**，是三个真功能不是占位）。 */

/** 时间范围：只改 `WINDOW` 一个量 ⇒ ③ 的列表、① 的读数、② 的计数、④ 的榜**全部**跟着重算。 */
const RANGES = [['5m', '近 5 分'], ['30m', '近 30 分'], ['custom', '自定义']]
const RANGE_MS = { '5m': 5 * 60 * 1000, '30m': 30 * 60 * 1000 }
/** 导出：**前端生成文件下载，不经后端**。三档 = 给人看的（CSV）/ 给程序看的（JSON）/ 原始帧形状（ndjson）。 */
const EXPORTS = [
  ['csv', 'CSV', '逗号分隔，Excel 直接打开（带 BOM，中文不乱码）'],
  ['json', 'JSON', '格式化数组，字段名 = 帧里的原名'],
  ['ndjson', 'ndjson', '一行一条，与 SSE 帧里的对象**逐字同形**（原始）'],
]
/** 密度：只切 ③ 的行高（用户给的 22 / 28px），不动任何别的量。 */
const DENSITIES = [['compact', '紧凑'], ['cozy', '舒适']]
const ROW_H = { compact: '22px', cozy: '28px' }

/** ② / ④ 里「设施 3 类」的两组框架（用户 2026-10-16 裁定 D：**框架做，设施 3 置灰**）。
 *  ⚠️ 后端 Java 侧**根本不存在这三类**（`SLOW_SQL` / `MQ` / `SCHEDULER` 一个写入点都没有，
 *     `config/labels/observe-labels.js` 的 `KINDS` 里也没有）⇒ 它们是**后端面待落地**的占位，
 *     不是「本轮没做」。故：**画出来、置灰、不可点、不进筛选** —— 让缺口在页面上看得见，
 *     而不是把草图里的三类悄悄删掉（那也是把缺口藏起来）。 */
const FACILITY_KINDS = [
  { k: 'slow_sql', label: '慢 SQL' },
  { k: 'mq', label: 'MQ' },
  { k: 'scheduler', label: '调度' },
]
const FACILITY_TIP = '后端面待落地：这一类在 Java 侧一个写入点都没有，先占位、不参与筛选、不进任何计数'

/** 「覆盖与边界」5 条 —— **压缩上屏、全文在 `title` 里**（用户 2026-10-16 裁定 C：接受压缩，
 *  但要告诉用户全文在哪能查到）。⚠️ 逐字全文的**原始出处** = `git show HEAD:` 本文件里那一节，
 *  重做成窗口形态时被压掉了散文；现在同时留在 ① 本常量（= ② 栏底那行小字的 `title`）
 *  ② 本文件顶部那段注释 ③ `_work/3a3/日志页-覆盖与边界-全文.md`。 */
const COVERAGE_FULL = [
  '① 事件是内存里的、有界且会被覆盖：八个源环各留最近 512 条，合并环再留最近 512 条；本页自己留最近 1000 条。⇒「窗口内」是本页能看到的全部，不是历史总量。',
  '② 首帧可能很大：冷启动时八个环可能各自攒了一屏，首帧会把合并环留得下的最后 512 条一次送出（页面按 1000 条封顶）。之后每拍只送真正新发生的（正常是个位数）。',
  '③ 规则表在后端：严重度那三档（info / warn / error）由后端 severityOf 一处判定，本页只负责把 tag 画成人话 —— 前端不重推一遍判据。',
  '④ 对象列八类不是同一种东西：锁/缓存/通知取各自的键、订单取订单号、库存取 SKU、审计取对象类型 + #Id、敏感词取命中的词、身份取请求路径（逐类口径见 config/labels/observe-labels.js 的 OBJECT_FIELD_LABEL）。',
  '⑤ 中文标签 commit 9 已全部收敛：本页 + 指标 / 状态流转 / 攻击模拟器四页的中文表只在 config/labels/ 下那一处（可观测域是本目录的 observe-labels.js）；页面只负责把后端帧里的 tag 画成人话，不重推任何判据。',
].join('\n')

/**
 * MQ 桥接状态 —— 数据源 = 实时日志帧的第 10 个键 `mq`（`EventStreamService.frame()` 里
 * `publisher.connected()`）。后端侧的实探点：`RabbitEventPublisher` 有一条 daemon 探针线程，
 * 每 5 秒握一次手 —— 故这里的值最多滞后 5 秒，与后端日志同源、同一个判据。
 * ⚠️ 它**只用于上屏**，不参与任何业务判定；红/黄点表达的是「这台服务此刻有没有把事件交给队列」，
 *    不是「事件有没有问题」（严重度是另一套配色）。
 * ⚠️ `f.mq === true` 而不是 `!!f.mq`：帧里缺这个键的后端会让 `!!undefined` 变成 false ⇒
 *    与「恒假常量」逐字同行为，页面不会因为升级错位而闪一个「已连接」。
 */
/* ── 两个数据源各自的窗口状态（用户 2026-10-16 裁定 B：**切源 = 隔离，不是清空**）────────
   ⚠️ 这是本轮的行为改动，改的是**既有语义**：原来 `events` / `traceList` / `rateSeries` 是
      **一套、跨源保留**，切到「模拟日常」时列表里还留着切换前真流的事件 ——
      实测就是「窗口内 159 + 演示 41」混在一起（探针第一次就是这么红的）。
   现在：每个窗口量**按源分桶**，显示层只取 `cur` 那一桶。切源**不动任何数据**，只是显示变了，
   切回来原样还在（不丢数据 / 两源彻底隔离 / 切来切去不丢窗口）。用户原话：
      「模拟的数据日常不显示不就好了，日常数据也不在模拟时显示。」
   ⚠️ `phase` / `lost` / `errMsg` **不**分桶：它们描述的是「这一条 SSE 连接」，连接只有一条。 */
const W = reactive({
  normal: { events: [], traces: [], series: [], dropped: 0, seq: 0, ts: 0, rate: 0, freshE: 0, freshT: 0, mq: false },
  demo: { events: [], traces: [], series: [], dropped: 0, seq: 0, ts: 0, rate: 0, freshE: 0, freshT: 0, mq: false },
})

/* ── 页面状态 ───────────────────────────────────────────────────────────────── */
const phase = ref('connecting')            // connecting | live | stopped
const errMsg = ref('')
/**
 * 断线状态：`null` = 没断；否则 `{ err, attempt, nextRetryInMs }`（`openResilientStream` 的
 * `onLost` 原样存下来）。⚠️ 它和 `errMsg` 是**两个判据**：`errMsg` 只装**真错误**，
 * 而「服务端正常收尾」那一格 `errMsg` 是空的 —— 断线条必须覆盖它（见模板里那条注释）。
 */
const lost = ref(null)
/** ① 的时间范围（裁定 A）：`5m` / `30m` / `custom`。 */
const range = ref('5m')
/** 「自定义」的起止 —— 存 `datetime-local` 的字符串（本地时区，`step="1"` ⇒ 秒级）。 */
const customFrom = ref('')
const customTo = ref('')
/** 时间范围里的「现在」。⚠️ 必须是**会走的**响应式量：`近 5 分` 是滑动窗口，不重算的话，
 *  一页开着不动就会把 5 分钟前的行一直留在列表里。每 1 秒推一次（见 `onMounted`）。 */
const nowMs = ref(Date.now())
/** ③ 的行高（裁定 A）：紧凑 22px / 舒适 28px。只影响 ③，不动别的量。 */
const density = ref('compact')
/** 导出后那行小字（「已导出 N 条 → 文件名」）。⚠️ 没有它，下载被浏览器拦掉时用户看不出
 *  发生过什么 —— 那不是「静默失败」而是「静默成功」，两者都查不出来。 */
const exportNote = ref('')
const activeKinds = ref(new Set(KINDS))    // 开着的类（空集 = 一条也不显示，是**允许**的状态）
const activeSevs = ref(new Set(SEVERITIES))// 开着的严重度
const openedTrace = ref('')                // ⑤ 抽屉打开的 traceId
const drawerOpen = ref(true)               // ⑤ 是否拉起（塌着的时候只有一条 48px 的带）
const q = ref('')                          // ② 搜索框
const objFilter = ref('')                  // ② 对象筛选（点 ③ 里的对象徽标填进来）
const warnUp = ref(false)                  // ② ≥ WARN 快捷筛选
const topTab = ref('obj')                  // ④ Top-N 当前档
const listEl = ref(null)                   // ③ 的滚动容器（自动到底用）

/** 当前数据源 = 当前该显示的那一桶。它就是「切源 = 隔离」的那个开关。 */
const cur = computed(() => (demo.on ? 'demo' : 'normal'))
const win = computed(() => W[cur.value])
/** ⚠️ 下面这一排 computed 把「按源分桶」对**模板与既有代码**完全遮住了：模板里照旧写
 *  `{{ seq }}` / `{{ events.length }}`，读到的都是**当前源**的那一份。 */
const events = computed(() => win.value.events)
const traceList = computed(() => win.value.traces)
const rateSeries = computed(() => win.value.series)
const freshEvents = computed(() => win.value.freshE)
const freshTraces = computed(() => win.value.freshT)
const droppedTotal = computed(() => win.value.dropped)
const seq = computed(() => win.value.seq)
const ts = computed(() => win.value.ts)
const rate = computed(() => win.value.rate)
const mqConnected = computed(() => win.value.mq)

let stream = null
/** 已见过的合并环 seq。⚠️ 必须有：重连/重挂时后端按**它自己**的游标续送，页面这边若被重放了
 *  已经画过的帧，会把同一批卡片画两遍。`seq` 是**全局单调、永不复用**的（`getAndIncrement`）
 *  ⇒ 拿它当去重键是安全的（换比它更弱的键，比如 `(kind,title,ts)`，同刻同环的两条会被误并）。
 *  ⚠️ 它的规模**不是无界的**：每收一批就把被 `EVENT_CAP` 挤出去的那几条 seq 从这里摘掉
 *  （见 `absorbEvents`）⇒ 集合大小 ≤ `events.length` ≤ `EVENT_CAP`。这正是易漏项②要求的那一步。 */
const seenSeq = new Set()
/** `traces` 按 traceId 索引（⑤ 的起止要用），与 `traceList` 是同一批数据的两种取法。
 *  ⚠️ 行里带 `source`（裁定 B）：取用一律走 `recFor()`，**不直接 `.get()`** ——
 *    否则演示源的一条事件可能挂到真流留下的同 id 记录上（两套 traceId 形状不同，
 *    真撞上的概率约等于零，但「约等于零」正是这种 bug 藏身的地方）。 */
const tracesById = reactive(new Map())
/** 自动滚到底：用户手动往上滚就关掉，滚回底部再打开（见 `onScroll`）。 */
let autoBottom = true

/* ── 小工具（本页自备：与 `OrderFlow.vue` 的口径同源，不抽公共模块）────────────── */
const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')
const fmtRate = (v) => num(v).toFixed(1)
function pad(n, w) {
  return String(n).padStart(w, '0')
}
/** `HH:mm:ss.SSS`。⚠️ 写到**毫秒**是刻意的：一拍内跨环的事件墙钟常常同毫秒，
 *  只到秒的话 ⑤ 里的链路会看起来像「什么都没发生」。 */
function hmsms(v) {
  const n = num(v)
  if (!n) return '—'
  const d = new Date(n)
  if (Number.isNaN(d.getTime())) return '—'
  return `${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}.${pad(d.getMilliseconds(), 3)}`
}
/** 对象列那一格：`说明词 值`。八类的说明词不同（见 `observe-labels.js` 的 `OBJECT_FIELD_LABEL`）。
 *  ⚠️ `audit` 那一类把 `objectType` 也带上（`商品 #20`）—— 它那一格的「是什么」由类型说。
 *  ⚠️ commit 9 起 `objectType` 走 `targetTypeLabel()`（原先是英文域前缀直出）。 */
function objText(ev) {
  const id = ev.objectId
  if (id === null || id === undefined || id === '') return ''
  const label = objectFieldLabel(ev.kind)
  const type = ev.kind === 'audit' && ev.objectType ? `${targetTypeLabel(ev.objectType)} #` : ''
  return `${label} ${type}${id}`
}
/** ⑤ 每条事件下面那行**技术字段**（用户 2026-10-17 裁定：`中文（英文原键）= 值`）。
 *  上一层是中文人话（类 + 标题 + 对象），这一层把帧里的**原名与值都留着**，逐字可 grep：
 *  中文方便看链路形状，英文原键方便拿去和后端人员/日志对。**两层都不能省**。
 *  ⚠️ 这一层的**值一律照原文**（`severity=info` 就写 `info`，不翻成「信息」）—— 用户给的
 *    样例就是 `severity（级别）= info` / `kind（类型）= lock`。翻译只发生在第一层的人话里。
 *  ⚠️ 帧顶层键与 `fields` 键**分开列**：顶层 `kind` 是事件类、`fields.kind` 是库存操作，
 *    同名不同物；后者用 `fieldLabel(kind, key)` 逐环取词。 */
function techLine(e) {
  const parts = []
  const addTop = (key, value) => {
    if (value === null || value === undefined || value === '') return
    parts.push(`${eventKeyLabel(key)}（${key}）= ${value}`)
  }
  addTop('kind', e.kind)
  addTop('severity', e.severity)
  addTop('objectType', e.objectType)
  if (e.objectId !== null && e.objectId !== undefined && e.objectId !== '') addTop('objectId', e.objectId)
  if (e.key !== null && e.key !== undefined && e.key !== '') addTop('key', e.key)
  addTop('traceId', e.traceId)
  addTop('batchId', e.batchId)
  const us = num(e.durationUs)
  if (e.durationUs !== null && e.durationUs !== undefined && us > 0) addTop('durationUs', `${us} µs`)
  if (e.remaining !== null && e.remaining !== undefined) addTop('remaining', `${e.remaining} 件`)
  if (e.actor && e.actor.id !== null && e.actor.id !== undefined && e.actor.id !== '') {
    const role = e.actor.role === null || e.actor.role === undefined || e.actor.role === ''
      ? ''
      : `（role=${e.actor.role}）`
    addTop('actor', `${e.actor.id}${role}`)
  }
  const f = e.fields
  if (f && typeof f === 'object') {
    for (const k of Object.keys(f)) {
      const v = f[k]
      if (v === null || v === undefined || v === '') continue
      const text = typeof v === 'object' ? JSON.stringify(v) : String(v)
      parts.push(`${fieldLabel(e.kind, k)}（${k}）= ${text}`)
    }
  }
  const src = srcOf(e)
  if (src) addTop('src', src)
  return parts.join(' · ')
}

/** `detail` 尾部的 `src=环名#源seq`（后端只把它写在 `detail` 里，**不在 `fields` 里**）。
 *  取不到 ⇒ 空串（`detail` 缺失 / 旧帧 / 请求行都走这一支）。 */
function srcOf(e) {
  const m = /(?:^|\s)src=([^\s]+)\s*$/.exec(String((e && e.detail) || ''))
  return m ? m[1] : ''
}

/** 顶层 `actor` → `M205（商家）`（无角色时只写 id；无 id ⇒ 空串，整段不出现）。 */
function actorText(e) {
  const a = e && e.actor
  if (!a || a.id === null || a.id === undefined || a.id === '') return ''
  if (a.role === null || a.role === undefined || a.role === '') return String(a.id)
  return `${a.id}（${roleLabel(a.role)}）`
}

/** `fields` 的**值** → 第一层的人话（只翻有表可查的那几个；其余原样）。
 *  ⚠️ 与 `techLine()` 的分工：这里翻值、那里留原文 —— 两层各自完整，不是同一串的两种写法。 */
function fieldValueText(kind, key, value) {
  if (value === null || value === undefined || value === '') return ''
  const k = String(key)
  if (k === 'cache') return cacheAreaLabel(value)
  if (k === 'lock') return lockLabel(value)
  if (k === 'action') {
    if (kind === 'audit') return auditActionLabel(value)
    if (kind === 'order') return orderActionLabel(value)
    return String(value)
  }
  if (kind === 'order' && (k === 'from' || k === 'to')) return orderStateLabel(value)
  if (kind === 'notify' && k === 'to') {
    return Object.prototype.hasOwnProperty.call(NOTIFY_TO_LABEL, value) ? NOTIFY_TO_LABEL[value] : String(value)
  }
  if (kind === 'stock' && k === 'kind') {
    return Object.prototype.hasOwnProperty.call(STOCK_KIND_LABEL, value) ? STOCK_KIND_LABEL[value] : String(value)
  }
  if (k === 'qty') return `${value} 件`
  if (k === 'atBirth') return value ? '是' : '否'
  return String(value)
}

/** 第一层（默认可见）的**全部技术字段**：`[{ k: 中文键, v: 值 }]`。
 *  ⚠️ 数据源优先 `fields`；旧帧 / 演示帧没有 `fields` 时回落解析 `detail`（同一套 `parseDetail`）。
 *  ⚠️ **去重只去「对象列已经念过的同一件事」**：
 *     · `key` / `sku` / `order` 的值等于 `objectId` ⇒ 对象列已经写着 `键 xxx` / `SKU xxx`，不再念第二遍；
 *     · `fields.actor` 等于顶层 `actor.id` ⇒ 由「发起人 M205（商家）」那一格说（带角色，信息更全）。
 *     其余键**一个都不省**（用户裁定「全 fields」）。 */
function firstLayerFields(e) {
  const out = []
  // `protection` 也在内：防护环的 `fields.protection` 与 `objectId` 是同一个闸的 key（真帧如此）。
  const objectCarrying = ['key', 'sku', 'order', 'protection']
  const push = (key, value) => {
    if (value === null || value === undefined || value === '') return
    const id = e.objectId
    if (id !== null && id !== undefined && id !== '' &&
        objectCarrying.includes(key) && String(value) === String(id)) return
    if (key === 'actor' && e.actor && e.actor.id !== null && e.actor.id !== undefined &&
        String(e.actor.id) === String(value)) return
    out.push({ k: fieldLabel(e.kind, key), v: fieldValueText(e.kind, key, value) })
  }
  if (e.fields && typeof e.fields === 'object') {
    for (const key of Object.keys(e.fields)) push(key, e.fields[key])
  } else {
    const parsed = parseDetail(e.detail)
    for (const key of Object.keys(parsed)) {
      if (key === 'src') continue   // src 单独成段（`来源 cache#0`），不混进键值对
      push(key, parsed[key])
    }
  }
  // 「剩余 N 件」是帧的**顶层键**（`e.remaining`，只有库存扣减档有）—— 不在 `fields` 里。
  if (e.remaining !== null && e.remaining !== undefined) {
    out.push({ k: '剩余', v: `${e.remaining} 件` })
  }
  return out
}

/* ── ③ 的消息：**自然流拼接**（用户 2026-10-17 放行的 A 块 / 收尾批 2 改口径）──────────
   语法 = `{标题} {对象} {全 fields（中文键 值）} 发起人 {id（角色）} 来源 {环#seq}（trace · 耗时）`。
   ⚠️ **缺槽位就少一段，不写占位符** —— 句子里出现「—」「undefined」「null」比少说一句更糟：
      前者会被读成「这一格的值是空」，而真相是「这件事压根没有这个量」。
   ⚠️ **fields 优先，detail 只做回落**：`fields` 是后端 3a.3 起给前端拼句子用的结构化表
      （`EventBroadcaster.Emit` 的第四个参数），`detail` 是它的**人读孪生**。回落只在
      「帧里没带 fields」（旧后端 / 演示帧）时走 —— 抠不出来就跳过，不猜。
   ⚠️ **从句那一层已取消**：fields / actor / src 都已常显在第一层，从句再念一遍就是同一件事
      写两遍。`sentence()` 现在只剩括号里的 `trace` 与 `耗时` 两格。 */

/** 分段 token 的**色档**（CSS 在 `.log-line .ms .se-*`，一把锁在 admin 主题的 `tokens.css`）：
 *  `k` 键（`--ink`）/ `v` 值（`--ink-2`）/ `a` 可点（`--brand-ink` + 点状下划线）/
 *  `x` 中性连接词（`--ink-3`）。 */
const TK = (t) => ({ t: String(t), c: 'k' })
const TV = (t) => ({ t: String(t), c: 'v' })
const TA = (t) => ({ t: String(t), c: 'a' })
const TX = (t) => ({ t: String(t), c: 'x' })

/** 从 `detail` 里**尽力**抠出 `k=v` 对（回落路径专用）。
 *  ⚠️ 只能尽力：这套 `detail` 是**人读串**、形状逐类不同 —— `order#O-1` 那种不带等号、
 *     敏感词把两个量塞进一个 token（`hit=9/20`）、防护的 `extra` 可以带空格。
 *     抠不出来的一律**跳过**（`firstLayerFields()` 不会把它放进键值对），不猜、不填占位符。 */
function parseDetail(detail) {
  const m = {}
  if (!detail) return m
  for (const tok of String(detail).split(/\s+/)) {
    if (!tok) continue
    const hash = /^([A-Za-z_]\w*)#(.+)$/.exec(tok)
    if (hash) { m[hash[1]] = hash[2]; continue }
    const eq = /^([^\s=]+)=(.*)$/.exec(tok)
    if (!eq) continue
    if (eq[1] === 'hit' && eq[2].includes('/')) { const [h, t] = eq[2].split('/'); m.hit = h; m.total = t; continue }
    if (eq[2] === '') continue
    m[eq[1]] = eq[2]
  }
  return m
}

/**
 * 一条事件的**上下文括号**（分段 token，模板渲染成 `<span class="se-…">`）。
 *
 * ⚠️ 3a.3 收尾批 2 起，本函数**不再产从句**：用户把「全 fields + actor + src」提到了第一层
 *    常显（见 `firstLayerFields()` / `actorText()` / `srcOf()`），从句再念一遍就是同一条事实
 *    写两遍。这里只剩括号里的两格：`trace 短号` 与 `耗时`。
 *
 * @param e 帧里那条事件（本页 `absorbEvents` 抄下来的形状）
 * @param rec 这一条 traceId 的**收尾记录**（`recFor()` 取；查不到 ⇒ `null`）。
 *            ⚠️ 只用来兜「事件自己没有耗时、但这次请求有」的那一格。
 * @returns {{mid: Array, ctx: Array}} `mid` 恒为空数组（保留返回形状，模板/调用方不用分叉）；
 *          `ctx` = 括号里那串，可能是空数组（= 整对括号不出现）。
 */
function sentence(e, rec) {
  const mid = []
  const ctx = []

  // ── 括号的尾巴：trace 短号 + 耗时（两样都没有 ⇒ 整对括号不出现）────────────────
  if (e.traceId) {
    if (ctx.length) ctx.push(TX(' · '))
    // 短号画成**可点**的样式（`se-a`）。⚠️ 它不做「点了不许骗人」的例外：这一格只在
    // `traceId` 非空时存在，而行本身也正是靠 `traceId` 才可点（模板里的 `role`/`tabindex`）
    // ⇒ 这一格的可点提示与真实行为**同源**，不会出现「画了下划线却打不开」。
    for (const t of [TK('trace'), TX(' '), TA(String(e.traceId).slice(0, 8))]) ctx.push(t)
  }
  const us = num(e.durationUs)
  if (e.durationUs !== null && e.durationUs !== undefined && us > 0) {
    // ⚠️ 帧里这个数是**微秒**（TD-81：只有部分环有值）⇒ 亚毫秒的照实写 µs，
    //    不四舍五入成 `0 ms`（那会把「很快」写成「没花时间」）。
    if (ctx.length) ctx.push(TX(' · '))
    for (const t of [TK('耗时'), TX(' '), TV(us >= 1000 ? `${Math.round(us / 1000)} ms` : `${us} µs`)]) ctx.push(t)
  } else if (rec && num(rec.ms) > 0) {
    // 事件自己没带耗时、但这次请求有收尾记录 ⇒ 报**请求级**的，并且**标签也换成「请求耗时」**
    // —— 两个数的口径不同（一个是这条事件，一个是整次请求），共用一个词就是让读者读错。
    if (ctx.length) ctx.push(TX(' · '))
    for (const t of [TK('请求耗时'), TX(' '), TV(`${num(rec.ms)} ms`)]) ctx.push(t)
  }
  return { mid, ctx }
}

/** ⑤ 链首那行括号里的写法（`请求开始（<中文名> · POST /goods/goodsUp）`）。
 *  ⚠️ `apiLabel()` **认不出时回落原值** ⇒ 直接拼会得到「POST /x · POST /x」这种重复
 *     ⇒ 这里先比一次：认出来了才写两段（中文名 + 原标题），没认出来只留原标题。 */
function startLabel(rec) {
  if (!rec) return ''
  const raw = `${rec.method} ${rec.path}`
  const zh = apiLabel(rec.method, rec.path)
  return zh === raw ? raw : `${zh} · ${raw}`
}
/** ⑤ 上那枚状态码的色：2xx 中性、4xx 黄、5xx 红。⚠️ 只影响那一个 chip。 */
function statusClass(status) {
  const s = num(status)
  if (s >= 500) return 'is-bad'
  if (s >= 400) return 'is-warn'
  return 'is-ok'
}

/* ── ① 时间范围（裁定 A）─────────────────────────────────────────────────────── */
/** `yyyy-MM-ddTHH:mm:ss`（**本地时区**）—— `input[type=datetime-local]` 的取值格式。
 *  ⚠️ 不能上 `toISOString()`：那是 UTC，喂给 `datetime-local` 会整体偏一个时区（东八区偏 −8h）。 */
function toLocalInput(ms) {
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1, 2)}-${pad(d.getDate(), 2)}`
    + `T${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}`
}
/** 文件名上的时间戳：`yyyyMMdd-HHmmss`（本地）。⚠️ 用本地不用 UTC —— 文件名是给人认的。 */
function stampStr() {
  const d = new Date()
  return `${d.getFullYear()}${pad(d.getMonth() + 1, 2)}${pad(d.getDate(), 2)}`
    + `-${pad(d.getHours(), 2)}${pad(d.getMinutes(), 2)}${pad(d.getSeconds(), 2)}`
}
/** 反向：`datetime-local` 的字符串 → 毫秒。空串 = 那一端不限。 */
function parseLocal(s) {
  const t = String(s || '').trim()
  if (!t) return null
  const n = new Date(t).getTime()
  return Number.isNaN(n) ? null : n
}
/** 换范围。⚠️ 从「近 5 分」切到「自定义」时**把当前窗口的起止填进去**（不是留两个空框）——
 *  用户点「自定义」十有八九是想在眼前这个范围上改，而不是从零开始选。 */
function setRange(v) {
  if (v === 'custom' && !customFrom.value && !customTo.value) {
    const b = rangeBounds.value
    customFrom.value = toLocalInput(b ? Math.max(0, b.from) : nowMs.value - RANGE_MS['5m'])
    customTo.value = toLocalInput(nowMs.value)
  }
  range.value = v
}

/* ── ① 导出（裁定 A：前端生成文件下载，不经后端）────────────────────────────── */
/** 导出列 = 事件的**全部 10 个字段**（`ObservedEvent` 扩到 10 字段那一次的口径）逐字保留，
 *  外加本页打的 `source` 标记。
 *  ⚠️ 3a.3 起帧扩到 **15 键**，CSV **只并进 3 列**（`actorId` / `actorRole` / `remaining`），
 *     另外两个**刻意不并**，逐条理由写在这里（少一条都会让后人以为是漏了）：
 *       · `batchId` —— 它是**传输批次**（演练批次号），不是这条事件的事实；同一批次的几百条
 *         会把这列填成一个常数，加进来只会把表撑宽。
 *       · `durationUs` —— 只有部分环有值（TD-81「有时带 µs」是长期口径），而且 CSV 里
 *         `detail` 已经带着这一条的全部原始串；要按耗时筛就走 JSON/ndjson 那两档。
 *       · `fields` —— 嵌套对象，塞进 CSV 会破坏「一行一条」的表形（要么变成一坨转义 JSON、
 *         要么得摊平成不定数列）。它的正经回放口是台账的 json 列与 JSON/ndjson 两档。
 *  ⚠️ `actor` 在帧里是 `{id, role}` **对象或 null**（无身份时整个为 null）⇒ CSV 摊成两列，
 *     两个都空就是「这条没有身份」，**不写占位符**（同「缺槽位跳过」的口径）。
 *  `ts` 与「时刻」两列都给：前者是帧里的原值（可对齐、可复算），后者是给人看的 ——
 *  只留一个都会让某一种用法变难。 */
const EXPORT_HEAD = ['seq', 'ts', '时刻', 'severity', 'kind', '类', 'title', 'detail', 'key', 'objectType', 'objectId', 'traceId', 'actorId', 'actorRole', 'remaining', 'source']
const exportCells = (e) => [
  e.seq, e.ts, hmsms(e.ts), e.severity, e.kind, kindLabel(e.kind),
  e.title, e.detail, e.key, e.objectType, e.objectId, e.traceId,
  e.actor ? e.actor.id : '', e.actor ? e.actor.role : '', e.remaining === null ? '' : e.remaining,
  e.source,
]
/** 机器那两档（JSON / ndjson）用**帧里的原名**，不掺任何本页加工的列 ——
 *  故 15 键里新加的**五个全带**（含 CSV 排除掉的 `batchId` / `durationUs` / `fields`）。 */
const exportRaw = (e) => ({
  seq: e.seq, ts: e.ts, kind: e.kind, severity: e.severity, title: e.title, detail: e.detail,
  key: e.key, objectType: e.objectType, objectId: e.objectId, traceId: e.traceId,
  batchId: e.batchId, actor: e.actor, durationUs: e.durationUs, remaining: e.remaining, fields: e.fields,
  source: e.source,
})
const csvCell = (v) => {
  const s = v === null || v === undefined ? '' : String(v)
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}
/** 导出的**就是当前列表**（③ 里看见的那些，时间正序）—— 筛选、时间范围、数据源全部生效。
 *  「所见即所得」比「导全量」好解释：用户在页面上调完筛选再导出，拿到的是他刚在看的东西。 */
function exportRows() {
  return filtered.value.slice().sort((a, b) => a.ts - b.ts || a.seq - b.seq)
}
function exportText(fmt, rows) {
  if (fmt === 'ndjson') return rows.map((e) => JSON.stringify(exportRaw(e))).join('\n') + (rows.length ? '\n' : '')
  if (fmt === 'json') return JSON.stringify(rows.map(exportRaw), null, 2) + '\n'
  const lines = [EXPORT_HEAD.join(',')].concat(rows.map((e) => exportCells(e).map(csvCell).join(',')))
  // ⚠️ BOM 是给 Excel 的：没有它，Windows 上的 Excel 会把 UTF-8 的中文按 GBK 解 ⇒ 乱码。
  //    写成 `U+FEFF` 转义而**不是**一个字面的 BOM 字符 —— 后者会被 eslint 的
  //    `no-irregular-whitespace` 判成不规则空白（本轮实测踩过）。
  const bom = String.fromCharCode(0xfeff)
  return bom + lines.join('\r\n') + '\r\n'
}
function download(name, text, mime) {
  const url = URL.createObjectURL(new Blob([text], { type: mime }))
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  // ⚠️ **不能立刻 revoke**：blob 是异步读的，立刻撤会让下载变成 0 字节（页面上什么都看不出来）。
  //    留 4 秒 —— 比任何一次真实下载都长，又不至于把 URL 攒在内存里。
  setTimeout(() => URL.revokeObjectURL(url), 4000)
}
let exportNoteTimer = null
function doExport(fmt) {
  const rows = exportRows()
  const ext = fmt === 'json' ? 'json' : (fmt === 'ndjson' ? 'ndjson' : 'csv')
  const srcTag = cur.value === 'demo' ? '模拟日常' : '正常'
  const name = `事件流-${srcTag}-${range.value}-${stampStr()}.${ext}`
  const mime = fmt === 'csv' ? 'text/csv;charset=utf-8' : (fmt === 'json' ? 'application/json' : 'application/x-ndjson')
  download(name, exportText(fmt, rows), mime)
  // ⚠️ 措辞必须是「**刚刚**导出 N 条」并 10 秒后自己消失：写成「已导出 N 条」挂在那儿，
  //    用户改完筛选再看它就是个**过时的数**（同一张页面上，别的数都跟着筛选变，就它不变）。
  exportNote.value = `刚刚导出 ${rows.length} 条 → ${name}`
  clearTimeout(exportNoteTimer)
  exportNoteTimer = setTimeout(() => { exportNote.value = '' }, 10000)
}

/* ── 派生量 ─────────────────────────────────────────────────────────────────── */
const first = computed(() => seq.value === 0 && !errMsg.value)
const phaseText = computed(() => (phase.value === 'live' ? '已连接' : (phase.value === 'stopped' ? '已断开' : '连接中')))
/**
 * 断线条那句话。⚠️ 两种都要写全：真错误（`err`）与**服务端正常收尾**（`err === null`）——
 * 后者不是故障，但页面上的数字同样停在断开前那一拍，不说清楚就回到「静默陈旧」。
 */
const lostText = computed(() => {
  const s = lost.value
  if (!s) return ''
  const head = s.err
    ? `实时日志流中断：${(s.err && s.err.message) || '连接中断'}`
    : '实时日志流已断开（服务端正常收尾）'
  return `${head} —— 上面的数字停在断开前最后一拍，${s.nextRetryInMs / 1000} 秒后自动重连（第 ${s.attempt} 次）`
})
const lastTickAt = computed(() => (ts.value ? hmsms(ts.value) : '—'))
const isAllOn = computed(() => activeKinds.value.size === KINDS.length && activeSevs.value.size === SEVERITIES.length)

/* ── ① 时间范围（裁定 A）⇒「范围内」是 ②③④ 的统一口径 ──────────────────────── */
/** 当前时间范围的 `[from, to]`（毫秒，闭区间）。`null` = **不过滤**（自定义两栏都空）。 */
const rangeBounds = computed(() => {
  if (range.value === 'custom') {
    const f = parseLocal(customFrom.value)
    const t = parseLocal(customTo.value)
    if (f === null && t === null) return null
    return { from: f === null ? -Infinity : f, to: t === null ? Infinity : t }
  }
  const w = RANGE_MS[range.value]
  return { from: nowMs.value - w, to: Infinity }
})
/** ⚠️ **②③④ 全部口径统一挂在 `ranged` 上**，不再直接读 `events`：类计数、严重度、错误率、
 *  Top-N 榜、③ 的列表、读数的「范围内合计」都是同一批行 —— 一处一处各读各的，迟早会算出
 *  「列表里 9 条、图例里 159 条」这种对不上的数（比一个过时的数更难查）。 */
const ranged = computed(() => {
  const b = rangeBounds.value
  if (!b) return events.value
  return events.value.filter((e) => e.ts >= b.from && e.ts <= b.to)
})
/** 当前数据源的链路记录，同样按时间范围过（用**收尾时刻**判，一次请求只落一个时刻）。 */
const rangedTraces = computed(() => {
  const b = rangeBounds.value
  if (!b) return traceList.value
  return traceList.value.filter((t) => t.endMs >= b.from && t.endMs <= b.to)
})
/** ⑤ / ③ 取某条 traceId 的收尾记录。**唯一的取用口**（按源隔离，见 `tracesById` 的注释）。 */
function recFor(tid) {
  const r = tid ? tracesById.get(tid) : null
  return r && r.source === cur.value ? r : null
}

/** **范围内**按类计数（② 的行清单 / ④ 的横条）。⚠️ **本页自己攒的**，不是帧里那个本拍的
 *  `counts` —— 拿帧里的数当图例数字，每次静默的一拍上会全体归零。 */
const sessionByKind = computed(() => {
  const m = {}
  for (const e of ranged.value) m[e.kind] = (m[e.kind] || 0) + 1
  return m
})
const sessionBySev = computed(() => {
  const m = {}
  for (const e of ranged.value) m[e.severity] = (m[e.severity] || 0) + 1
  return m
})
const kindMax = computed(() => Math.max(1, ...KINDS.map((k) => sessionByKind.value[k] || 0)))
const sevMax = computed(() => Math.max(1, ...SEVERITIES.map((s) => sessionBySev.value[s] || 0)))
function barW(v, mx) {
  return `${((v / mx) * 100).toFixed(1)}%`
}

/** ① 错误率 = (warn+error) / **范围内**事件。⚠️ 范围内累计，不是本拍。 */
const errBad = computed(() => (sessionBySev.value.warn || 0) + (sessionBySev.value.error || 0))
const errRate = computed(() => {
  const total = ranged.value.length
  return total ? ((errBad.value / total) * 100).toFixed(1) : '0.0'
})
const sevMiniTitle = computed(() =>
  SEVERITIES.map((s) => `${severityLabel(s)} ${fmtInt(sessionBySev.value[s] || 0)}`).join(' · '))

/** ① 的 P99 与 ④ 的分位：都从**范围内** `traces` 的耗时现算（没有后端分位接口）。 */
const lat = computed(() => {
  const xs = rangedTraces.value.map((t) => num(t.ms)).sort((a, b) => a - b)
  const n = xs.length
  const at = (p) => (n ? xs[Math.min(n - 1, Math.floor((p / 100) * n))] : 0)
  return { n, p50: at(50), p95: at(95), p99: at(99) }
})

/** ① 的折线：本页自攒的速率序列。 */
const sparkPts = computed(() => {
  const r = rateSeries.value
  if (r.length < 2) return ''
  const mx = Math.max(...r, 1)
  return r.map((v, i) => `${((i / (r.length - 1)) * 100).toFixed(2)},${(100 - (v / mx) * 92).toFixed(2)}`).join(' ')
})
const sparkMax = computed(() => (rateSeries.value.length ? Math.max(...rateSeries.value) : 0))
/** 「当前」= 序列的**最后一拍**（图上最右那个点）。⚠️ **不是 `sparkMax`** —— 峰值是这段窗口
 *  的历史最高，把它标成「当前」会在曲线早就掉回 0 之后继续显示 19，是**读数骗人**。
 *  ⚠️ 取 `rateSeries`（本页自攒的序列）的末项，与折线右端**同一个数**（`sparkPts` 也是按它画的）。 */
const sparkLast = computed(() => (rateSeries.value.length ? rateSeries.value[rateSeries.value.length - 1] : 0))

/** ②③ 的筛选链：**时间范围** → 类 → 严重度 → ≥WARN → 对象 → 搜索。
 *  ⚠️ 时间范围是**第一道**（它决定「范围内」这个分母），其余都是在这批行里再挑。
 *  它同时也是**导出**取的行（见 `exportRows`）⇒ 用户在页面上看见什么就导出什么。 */
const filtered = computed(() => {
  const query = q.value.trim().toLowerCase()
  return ranged.value.filter((e) => {
    if (!activeKinds.value.has(e.kind) || !activeSevs.value.has(e.severity)) return false
    if (warnUp.value && e.severity === 'info') return false
    if (objFilter.value && String(e.objectId || '') !== objFilter.value) return false
    if (query) {
      const hay = `${e.title} ${e.detail} ${e.traceId} ${e.objectId || ''}`.toLowerCase()
      if (!hay.includes(query)) return false
    }
    return true
  })
})

/**
 * ③ 的行：**时间正序**（tail -f 方向）→ 树形重建。
 * ⚠️ `events` 是「新的在前」存的，这里翻过来 —— 列表要「先发生的在最上面」，
 *    与之前的分页表**刻意相反**（那是「最新的在最上面」）。
 * ⚠️ 数据侧不再截 200 行（`ROW_MAX` 已删）：截断改在 **DOM 侧**做窗口化（见下方
 *    `VIRTUAL_MIN` / `vRows`）—— 1000 条数据都在，首屏只铺看得见的那几十行。
 */
const streamRows = computed(() => buildStream(filtered.value.slice().reverse()))

/* ── ③ 窗口化渲染（虚拟滚动）───────────────────────────────────────────────────
   用户 2026-10-17 性能微调：1000 条数据在内存里，向上翻时掉帧。
   · 渲染侧只铺「视口 + 上下各 `OVERSCAN` 行」；上下用 `vpad` 空白撑开 scrollHeight。
   · 行高可变（1 行 / 2 行折行、两档密度）⇒ 不写死固定高：未测过的行给 `EST_ROW_H`，
     渲染后用 `.ln` 的 scrollHeight（夹到 2 行）回填 `rowH`，再重算窗口。
   · 滚动事件走 `@scroll.passive` + rAF 合帧；**不劫持滚动、不做平滑**
     （平滑滚动在日志列表里只会带来粘滞感）。
   · 行数 ≤ `VIRTUAL_MIN` 时全量渲染 —— 小数据量下窗口化没有收益，整表可见本身就是判据。 */
const rowH = new Map()          // key → 实测行高（px）；key 稳定 ⇒ 跨重渲染保留
const vStart = ref(0)
const vEnd = ref(VIRTUAL_MIN + OVERSCAN * 2)
let winRaf = 0

const virtualOn = computed(() => streamRows.value.length > VIRTUAL_MIN)
/** 前缀和：`off[i]` = 第 i 行上边缘。每次行数 / 实测高变化重算（≤1000 行，成本可忽略）。 */
const rowOffsets = computed(() => {
  const rows = streamRows.value
  const off = new Array(rows.length + 1)
  off[0] = 0
  for (let i = 0; i < rows.length; i++) off[i + 1] = off[i] + (rowH.get(rows[i].key) || EST_ROW_H)
  return off
})
const vRows = computed(() => (virtualOn.value ? streamRows.value.slice(vStart.value, vEnd.value) : streamRows.value))
const padTop = computed(() => (virtualOn.value ? rowOffsets.value[vStart.value] : 0))
const padBottom = computed(() => {
  if (!virtualOn.value) return 0
  const n = streamRows.value.length
  return rowOffsets.value[n] - rowOffsets.value[Math.min(vEnd.value, n)]
})

/** 二分：前缀和里找第一个「上边缘 > y」的行。 */
function rowAtY(off, n, y) {
  let lo = 0
  let hi = n
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (off[mid + 1] <= y) lo = mid + 1
    else hi = mid
  }
  return lo
}
function updateWindow() {
  const el = listEl.value
  if (!el || !virtualOn.value) return
  const off = rowOffsets.value
  const n = streamRows.value.length
  const top = el.scrollTop
  const bottom = top + (el.clientHeight || 1)
  const s = Math.max(0, rowAtY(off, n, top) - OVERSCAN)
  const e = Math.min(n, rowAtY(off, n, bottom) + OVERSCAN + 1)
  if (s !== vStart.value) vStart.value = s
  if (e !== vEnd.value) vEnd.value = e
}
/** 渲染后回填实测行高。⚠️ 量「夹到 2 行」的高度：hover 展开时 `.ln` 的盒子会变高，
 *  但 `scrollHeight` 仍是全文高度 ⇒ `Math.min(·, 2 × lineHeight)` 取回默认态行高。 */
function measureRows() {
  const el = listEl.value
  if (!el) return
  const rows = streamRows.value
  let changed = false
  for (const li of el.querySelectorAll('.log-line[data-vidx]')) {
    const i = Number(li.dataset.vidx)
    const row = rows[i]
    const ln = li.querySelector('.ln')
    if (!row || !ln) continue
    const lh = parseFloat(getComputedStyle(ln).lineHeight) || 17.4
    const h = Math.min(ln.scrollHeight, 2 * lh) + 2   // +2 = `.log-line` 上下各 1px padding
    const prev = rowH.get(row.key)
    if (prev === undefined || Math.abs(prev - h) > 0.5) { rowH.set(row.key, h); changed = true }
  }
  if (changed) updateWindow()
}
/** 滚动 / 数据变化后的合帧入口：先按当前 scrollTop 定窗口，等 DOM 铺完再量高度。 */
function scheduleWindow() {
  if (winRaf) return
  winRaf = requestAnimationFrame(() => {
    winRaf = 0
    updateWindow()
    nextTick(measureRows)
  })
}
watch(streamRows, () => nextTick(() => {
  if (autoBottom) scrollBottom()
  updateWindow()
  scheduleWindow()
}))
// 密度只改 `.lv` 等格子的 min-height ⇒ 行高变了，旧实测值作废。
watch(density, () => { rowH.clear(); nextTick(() => { updateWindow(); scheduleWindow() }) })

/* ── 备忘 · 缓存噪声：**本轮刻意不治理**（用户 2026-10-16 裁定丁）────────────────────
   现象：现状下 ③ 常被「缓存命中」刷屏 —— 实测那 200 行里 **76%（152 条）是缓存类**。
   裁定：**保持现状**。理由（用户给的）：截图里那批是**攻击模拟器**打出来的，日常不会这样。
   挂账：**TD-79**（`docs/agent/tech-debt.md`），那里写着判据与复现脚本。
   ⚠️ 下次看到刷屏**别凭观感动手**：先跑 `scripts/probes/forensics-b7a3-cache-noise.mjs`，
      看连续 run 长度分布里 `"1"` 那档的占比 ——
        · `"1"` 占绝大多数 ⇒ **是数据形态**（每条缓存写各自一个请求，本该平铺），治它只会把
          真实条数藏起来（本次实测 `{"1":157,"2":2,">=3":10}`，属这一档）。
        · `">=3"` 那档变大 ⇒ **是视图压不住**，那时「折叠 / 降级 / 隐藏」才有意义。
   ⚠️ 缓存类是「每个读操作都会写」的一类，条数随流量线性涨，**永远是 ③ 里最多的那档** ——
      所以「缓存条数多」不能当判据（它恒真）。 */

/**
 * 树形重建。**不抄任何现成的前导符**（`events` 里根本没有这个字段）——
 * 规则（用户 2026-10-16 三条）：
 *   ① `│` = 父级还有后续兄弟；② 缩进 = 层级（每层 3 字符）；③ `└─` = 本层最后一个。
 * ⚠️ **连续**同 traceId 的行才打包成一组（≥2 行**且** `traces` 里有这条记录）——
 *    不做全局按 trace 重排：那会让时间序错乱，也让「正在看的行」在拍与拍之间跳走。
 * ⚠️ 祖先竖线的分组 = 「左起第 j 组字符管第 j 层」，从当前行往上走 `dep - j` 次
 *    （写成 `j + 1` 在 1 层时同解、2 层起两组竖线左右颠倒 —— 见 `_work/3a3/tree-shape-check.mjs`）。
 */
function buildStream(list) {
  const n = list.length
  // 先切成 [组 | 单行]
  // ⚠️ 用户 2026-10-16 裁定 B 甲「修树形 bug」——**取证结论是「画对了，但看不见」**，
  //    所以这一轮**没动分组判据**（原样保留 `recFor` 那道闸），只动了画法（前缀拆两段 + 提亮）。
  //    下面的三个数都是**实测**（`_work/3a3/cache-noise-forensics.mjs` 跑
  //    `_work/3a3/frames-events.ndjson` 的 60 拍真后端帧，口径 = 复刻本组件的取数链）：
  //      · ③ 那 200 行里，连续同 trace ≥2 的 run **12 段**（末拍）/ 5~22 段（逐拍演进）
  //        ⇒ 树形**一直在画**，不是没触发。「看起来是平的」的原因在 CSS（见 `.log-line .pre`）。
  //      · 其中「查得到收尾记录」的同样是 **12 段**，逐拍演进 12 个采样点**差值恒 0**
  //        ⇒ 把 `recFor` 从判据里拿掉，在本数据上**一段也多不出来** —— 那是一个漂亮的
  //          「理论上有道理、实测无收益」的改动，故**不做**（不做无实测支撑的行为变更）。
  //      · 为什么 200 行只有 12 段：**76%（152/200）是缓存类**，而攻击模拟器打出来的
  //        每一条缓存写入都是**独立请求**（全量 1000 条里不同的 traceId 有 585 个）
  //        ⇒ run 长度 1 是**数据决定的**，不是代码舍弃的（方案 C 丁已接受）。
  // ⚠️ 顺带删掉原来的 `(cnt.get(tid) || 0) >= 2`（`cnt` 那张表一并删）：它与 `j - i >= 2`
  //    **可证等价** —— 在一个「同 tid 的极大连续段」里长度 ≥2 ⇔ 该 tid 在整个 list 里出现 ≥2 次
  //    （这一段自己就贡献了 ≥2 次）。两个条件并列写会让人以为它们各管一件事，实际后者恒真。
  const marks = []
  let i = 0
  while (i < n) {
    const tid = list[i].traceId
    let j = i + 1
    while (tid && j < n && list[j].traceId === tid) j++
    if (tid && j - i >= 2 && recFor(tid)) {
      const rec = recFor(tid)
      marks.push({ head: true, tid, rec })
      for (let k = i; k < j; k++) marks.push({ child: true, tid, ev: list[k] })
      marks.push({ tail: true, tid, rec })
    } else {
      for (let k = i; k < j; k++) marks.push({ ev: list[k] })
    }
    i = j
  }
  // 再画前导符：只有两级。⚠️ 两件事**必须**由同一个判据决定，否则末组会长成自相矛盾的形状：
  //   ① 顶层条目（组头 / 单行）自己的 `└─` vs `├─`；② 它那组子行左槽的竖线 `│` 还是空格。
  //   父级是最后一个兄弟时，子行左槽必须是**空格**（标准 tree 输出）——
  //   分两处各自判会得到「`└─ 请求开始` 下面还挂 `│  ├─`」，那条竖线指向一个不存在的后续。
  let lastTop = -1
  for (let m = 0; m < marks.length; m++) if (!marks[m].child && !marks[m].tail) lastTop = m
  const rows = []
  let topIsLast = false
  for (let m = 0; m < marks.length; m++) {
    const mk = marks[m]
    const isTop = !mk.child && !mk.tail
    if (isTop) topIsLast = m === lastTop
    // 前导符**拆成两段**发（用户 2026-10-16「所有行看起来是平的」⇒ 修可读性）：
    //   `rail` = 竖线/空格（脚手架，画得**淡**）+ `gl` = 分叉 `├─`/`└─`（结构，画得**亮**）。
    //   合成一个字符串就只能整段一个颜色，而「一列同样亮的 ├─」正是被读成项目符号的原因。
    //   ⚠️ 两段**拼起来的宽度不变**（3·depth + 2 字符）⇒ `--pc`、时刻列位置、探针的
    //       「成员比组头右移 22px」全部照旧。
    const rail = isTop ? '' : (topIsLast ? '   ' : '│  ')
    const gl = (isTop ? topIsLast : !!mk.tail) ? '└─' : '├─'
    rows.push(makeRow(mk, rail, gl, isTop ? 0 : 1))
  }
  return rows
}

/** ③ 三层的呈现规则（用户 2026-10-17 收尾批 2 定稿）：
 *    · 第一层（默认）= **中文主消息 + 全 fields（中文键名）+ 发起人 + 来源 + 括号(trace/耗时)**；
 *      技术字段不再等 hover —— 用户要的是「一睁眼就看得见五要素」。
 *    · 第二层 = **纯 CSS 2 行截断**：`.ln` 的 `max-height` 夹住，hover / 键盘聚焦展开全文；
 *      ≤2 行的行 hover 前后高度不变（没有 JS 测量、没有 `is-clip` 类）。
 *    · 第三层 = ⑤ 链里的 `中文（英文原键）= 值`（见 `techLine()`）。
 *  ⚠️ 缺槽位就少一段，不写占位符（同一页的老规矩）。 */

function makeRow(mk, rail, gl, depth) {
  const pc = `calc(${3 * depth + 2}ch + 8px)`
  const base = {
    key: mk.tail ? `t-${mk.tid}` : mk.head ? `h-${mk.tid}` : `e-${mk.ev.seq}`,
    rail, gl, pre: `${rail}${gl}`, pc, d: depth,
    traceId: mk.tid || (mk.ev && mk.ev.traceId) || '',
    // 「这一行有树形」（组头 / 组尾 / 成员）—— 只用来决定前缀提不提亮（见 CSS `.log-line.is-tree`）。
    // ⚠️ 与 `d`（缩进层级）**不是一回事**：组头是 `d=0` 但属于树形。两者各自管各自的事。
    tree: !!(mk.head || mk.tail || depth > 0),
    cls: '',
  }
  if (mk.ev) {
    const e = mk.ev
    // ⚠️ `recFor()` 取的是**当前源**那条收尾记录（按源隔离，见 `tracesById` 的注释）——
    //    句子里那一格「请求耗时」只有它给得出。取不到就传 null，`sentence()` 会跳过那一格。
    const sent = sentence(e, recFor(e.traceId))
    return Object.assign(base, {
      key: `e-${e.seq}`, kind: e.kind, color: kindColor(e.kind), sevColor: severityColor(e.severity),
      t: hmsms(e.ts), lv: { info: 'INFO', warn: 'WARN', error: 'ERROR' }[e.severity] || '',
      md: kindLabel(e.kind),
      title: e.title, detail: e.detail, obj: objText(e), objId: String(e.objectId || ''),
      fields: firstLayerFields(e), act: actorText(e), src: srcOf(e), rawDetail: '',
      mid: sent.mid, ctx: sent.ctx,
      cls: `lv-${e.severity.slice(0, 1)}`,
    })
  }
  if (mk.tail) {
    return Object.assign(base, {
      key: `t-${mk.tid}`, kind: '__req', color: 'var(--ink-3)', sevColor: 'var(--ink-3)',
      t: hmsms(mk.rec.endMs), lv: '', md: '请求', title: '请求结束', detail: `总耗时 ${mk.rec.ms} ms`, obj: '', objId: '',
      // ⚠️ 这一行**不是事件**（它来自 `traces` 记录）⇒ 没有 `fields`、没有 kind、没有从句。
      //    括号里只放它真正有的那一个数：总耗时；`rawDetail` 留空，免得同一句话在行内出现两遍。
      fields: [], act: '', src: '', rawDetail: '',
      mid: [], ctx: [TK('总耗时'), TX(' '), TV(`${mk.rec.ms} ms`)], cls: 'req',
    })
  }
  // 组头：括号里放**看得懂的中文名**，`rawDetail` 放原样的一行（方法 + 路径）。
  // （C 块的 `apiLabel()` 在本页的消费点之一；⑤ 链首那行是另一个，见 `startLabel()`。）
  const headRaw = `${mk.rec.method} ${mk.rec.path}`
  const headZh = apiLabel(mk.rec.method, mk.rec.path)
  return Object.assign(base, {
    key: `h-${mk.tid}`, kind: '__req', color: 'var(--ink-3)', sevColor: 'var(--ink-3)',
    t: hmsms(mk.rec.startMs), lv: '', md: '请求', title: '请求开始', detail: headRaw,
    obj: '', objId: '',
    fields: [], act: '', src: '', rawDetail: headRaw,
    mid: headZh === headRaw ? [] : [TV(headZh)], ctx: [], cls: 'req',
  })
}

/* ── ④ Top-N 榜（三档）──────────────────────────────────────────────────────── */
const topRows = computed(() => {
  const t = topTab.value
  if (t === 'obj') {
    const m = new Map()
    for (const e of ranged.value) {
      const k = objText(e)
      if (k) m.set(k, (m.get(k) || 0) + 1)
    }
    return [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, TOP_N).map(([k, n]) => [k, `${fmtInt(n)} 条`, ''])
  }
  if (t === 'slow') {
    const m = new Map()
    for (const tr of rangedTraces.value) {
      const k = `${tr.method} ${tr.path}`
      const cur = m.get(k) || { ms: 0, n: 0 }
      m.set(k, { ms: Math.max(cur.ms, num(tr.ms)), n: cur.n + 1 })
    }
    return [...m.entries()].sort((a, b) => b[1].ms - a[1].ms).slice(0, TOP_N).map(([k, v]) => [k, `${v.ms} ms`, `${v.n}`])
  }
  const m = new Map()
  for (const e of ranged.value) {
    if (e.severity !== 'error') continue
    const k = `${kindLabel(e.kind)} · ${e.title}`
    m.set(k, (m.get(k) || 0) + 1)
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, TOP_N).map(([k, n]) => [k, `${n}`, ''])
})

/* ── ⑤ 链路展开 ─────────────────────────────────────────────────────────────── */
const openedTraceRec = computed(() => recFor(openedTrace.value))
/** ⑤ 中间那一段：带这个 traceId 的事件，按墙钟**升序**（链路方向 = 时间方向）。
 *  ⚠️ 取的是 `ranged`（当前源 + 当前时间范围），**不是** `filtered` ——
 *    链路的完整性不该被「② 里关掉了一类」这种查看动作切掉。 */
const openedEvents = computed(() =>
  openedTrace.value
    ? ranged.value.filter((e) => e.traceId === openedTrace.value).slice().sort((a, b) => a.ts - b.ts || a.seq - b.seq)
    : []
)

/* ── 筛选 / 抽屉 ────────────────────────────────────────────────────────────── */
function toggleKind(k) {
  const next = new Set(activeKinds.value)
  if (next.has(k)) next.delete(k)
  else next.add(k)
  activeKinds.value = next
}
function toggleSev(s) {
  const next = new Set(activeSevs.value)
  if (next.has(s)) next.delete(s)
  else next.add(s)
  activeSevs.value = next
}
function resetFilters() {
  activeKinds.value = new Set(KINDS)
  activeSevs.value = new Set(SEVERITIES)
  warnUp.value = false
  objFilter.value = ''
  q.value = ''
}
function openTrace(r) {
  // ⚠️ 没有 traceId 的行点了不做事（模板也没给它 `role`/`tabindex`）。
  if (!r || !r.traceId) return
  if (openedTrace.value === r.traceId) { drawerOpen.value = !drawerOpen.value; return }
  openedTrace.value = r.traceId
  drawerOpen.value = true
}
function closeTrace() {
  openedTrace.value = ''
  drawerOpen.value = true
}

/* ── ③ 的内部滚动：自动到底 + 用户手动滚就不抢 ──────────────────────────────── */
function onScroll() {
  const el = listEl.value
  if (!el) return
  autoBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24
  // 窗口化：只改「渲染哪一段」，不碰 scrollTop —— 滚动始终由浏览器原生处理。
  scheduleWindow()
}
function scrollBottom() {
  const el = listEl.value
  if (el && autoBottom) el.scrollTop = el.scrollHeight
}

/* ── 收帧 ──────────────────────────────────────────────────────────────────── */
/**
 * 收一拍的 `events`：去重（按 `seq`）→ 本拍内按 `ts`/`seq` **倒序** → 整批**前插** → 封顶。
 * ⚠️ 存储是「新的在前」（列表渲染时翻成时间正序，见 `streamRows`）。
 * ⚠️ 「整批前插、不全局重排」是刻意的：后端一拍内的事件是**按环分组**的（`poll()` 一环一环并），
 *    照 `ts` 做全局重排会让用户正在看的行在两拍之间跳走。本拍内部排一次，正是为了把那个
 *    「按环分组」的假顺序在**⑤/③ 显示层**纠正掉。
 */
function absorbEvents(batch, src) {
  const fresh = []
  for (const raw of batch) {
    const s = num(raw.seq)
    if (seenSeq.has(s)) continue
    seenSeq.add(s)
    fresh.push({
      seq: s,
      ts: num(raw.ts),
      kind: raw.kind,
      severity: raw.severity,
      title: raw.title,
      detail: raw.detail,
      key: raw.key,
      objectType: raw.objectType,
      objectId: raw.objectId,
      traceId: raw.traceId === null || raw.traceId === undefined ? '' : String(raw.traceId),
      // ── 3a.3 起帧多出来的 5 个键（帧现共 15 键）。⚠️ 从前这里**只抄 10 个**，
      //    多出来的五个被**静默丢掉**（页面看不出任何异常，只是拼句子时永远少那几样）。
      //    抄进来之后它们才可能出现在：③ 的句子里（actor/remaining 之外的走 fields）、
      //    ⑤ 与导出三档。
      // ⚠️ `batchId` 只进导出（JSON/ndjson），**不进任何显示层** —— 它是传输批次不是事件事实。
      batchId: raw.batchId === null || raw.batchId === undefined ? null : String(raw.batchId),
      // ⚠️ `actor` 在帧里是 `{id, role}` **对象或 null**（无身份时整个为 null，不是一个空对象）
      //    ⇒ 这里照抄这个形状：`null` 就是 `null`，不造 `{id:null, role:null}`（造了会让
      //    「有没有身份」这个判断在页面上永远为真）。`role` 是**角色号**（1 客户 / 2 商家 / 3 管理员）。
      actor: raw.actor && typeof raw.actor === 'object'
        ? {
          id: raw.actor.id === null || raw.actor.id === undefined ? null : String(raw.actor.id),
          role: raw.actor.role === null || raw.actor.role === undefined ? null : num(raw.actor.role),
        }
        : null,
      durationUs: raw.durationUs === null || raw.durationUs === undefined ? null : num(raw.durationUs),
      // ⚠️ 只有**库存环的扣减档**有值（后端九个调用点里八个恒传 null）⇒ 这里的 null 是
      //    「这件事没有这个量」，不是「没测到」（同 `remaining()` 的注释口径）。
      remaining: raw.remaining === null || raw.remaining === undefined ? null : num(raw.remaining),
      // ⚠️ `fields` 是「袋子」，形状逐类不同（九个环各一套，见 `EventBroadcaster` 的 `Emit`）
      //    ⇒ 本页**原样存**、不在这里做任何归一化：拼句子时才按 kind 取值，取不到就跳过。
      fields: raw.fields && typeof raw.fields === 'object' ? raw.fields : null,
      // ⚠️ 源标记（裁定 B）：写进来的那一刻就定死，之后**永不改** ——
      //    切源只换显示、不动这一位，所以切回来还是同一批对象、同一份顺序。
      source: src,
    })
  }
  if (!fresh.length) return
  fresh.sort((a, b) => b.ts - a.ts || b.seq - a.seq)
  // 封顶：丢掉的是**最早**的那些（数组尾部）。同时把它们的 seq 从去重集里摘掉 ——
  // ⚠️ 不摘的话这个 Set 会随会话无界增长（它是本页唯一不封顶的东西）。
  const kept = W[src].events.slice(0, Math.max(0, EVENT_CAP - fresh.length))
  const dropped = W[src].events.slice(Math.max(0, EVENT_CAP - fresh.length))
  W[src].events = fresh.concat(kept)
  W[src].dropped += dropped.length
  for (const d of dropped) seenSeq.delete(d.seq)
}

/** 收一拍的 `traces`：按 traceId 更新索引 + 前插列表（同一次请求只会出现在一拍里）。
 *  ⚠️ 行里带 `source`：索引是**跨源共用的一张表**（traceId 两套不重叠），
 *     取用一律走 `recFor()` 把源对上。 */
function absorbTraces(batch, src) {
  const fresh = []
  for (const raw of batch) {
    const id = raw.traceId === null || raw.traceId === undefined ? '' : String(raw.traceId)
    if (!id) continue
    const row = {
      traceId: id,
      method: raw.method,
      path: raw.path,
      status: num(raw.status),
      startMs: num(raw.startMs),
      endMs: num(raw.endMs),
      ms: num(raw.ms),
      source: src,
    }
    tracesById.set(id, row)
    fresh.push(row)
  }
  if (!fresh.length) return
  fresh.sort((a, b) => b.endMs - a.endMs)
  // ⚠️ 被挤出去的 = **合并后超出上限的那一截**（`merged` 是「新在前」，故那一截一定是最旧的）。
  const merged = fresh.concat(W[src].traces)
  const gone = merged.slice(TRACE_CAP)
  const kept = merged.slice(0, TRACE_CAP)
  W[src].traces = kept
  // ⚠️ 索引里的旧行要摘掉，否则 `tracesById` 随会话无界增长（`traces` 会封顶，它不会）。
  for (const g of gone) {
    if (!kept.some((k) => k.traceId === g.traceId)) tracesById.delete(g.traceId)
  }
}

/** 收一拍。⚠️ `src` 由 `connect()` 在**建这条流的那一刻**定死（不是在这里读 `demo.on`）：
 *  帧回来时用户可能已经切走了，读 `demo.on` 会把旧流的帧记到新源头上。 */
function onFrame(f, src) {
  W[src].seq = num(f.seq)
  W[src].ts = num(f.ts) || Date.now()
  W[src].rate = num(f.rate)
  W[src].mq = f.mq === true
  const ev = Array.isArray(f.events) ? f.events : []
  const tr = Array.isArray(f.traces) ? f.traces : []
  W[src].freshE = ev.length
  W[src].freshT = tr.length
  absorbEvents(ev, src)
  absorbTraces(tr, src)
  // ① 折线：本页自攒（帧只有本拍一个数，画不出线）。按源各攒一条。
  W[src].series = W[src].series.concat([num(f.rate)]).slice(-RATE_CAP)
  // ③ 自动到底：等 DOM 铺完再滚，不然滚的是旧高度。
  // ⚠️ 只在**当前源**的帧上滚：另一条流的帧回来时不该动现在这一屏的滚动位置。
  if (src === cur.value) nextTick(scrollBottom)
}

function connect() {
  closeStream()
  errMsg.value = ''
  lost.value = null
  phase.value = 'connecting'
  autoBottom = true
  // ⚠️ `openEventStream` 是**带自动重连**的那一层（`onEnd` → `onLost`）：`onLost` **每次**断开
  //    都报，含「服务端正常收尾」那一格 —— 这是断线条要治的那一格。
  // ⚠️ 演示模式：**只换数据源这一处**，三个回调两边共用。演示帧的 `mq` 一律 `false` ⇒ 黄条会亮，
  //    那是实话不是 bug（演示事件确实只在内存里、不落库）。演示帧的 `traces` 是空数组 ⇒ ⑤ 无起止。
  // ⚠️ 源在建流的**这一刻**定死，交给 `onFrame` 的是这个常量而不是 `demo.on`：
  //    帧是异步回来的，用户可能在帧到达之前就切走了 —— 那时读 `demo.on` 会把旧流的
  //    一整批帧记到新源头上去（显示出来就是「刚切到模拟，列表里先闪出一批真流事件」）。
  const src = demo.on ? 'demo' : 'normal'
  const handlers = {
    onOpen() {
      phase.value = 'live'
      lost.value = null
      errMsg.value = ''
    },
    onFrame: (f) => onFrame(f, src),
    onLost(s) {
      phase.value = 'stopped'
      lost.value = s
      errMsg.value = s.err ? ((s.err && s.err.message) || '连接中断') : ''
    },
  }
  stream = demo.on ? openDemoStream('events', handlers) : openEventStream(handlers)
}

function closeStream() {
  if (stream) {
    stream.close()
    stream = null
  }
}

/** 每秒推一次「现在」（`近 5 分` / `近 30 分` 的滑动窗口靠它）。 */
let tickTimer = null
onMounted(() => {
  connect()
  tickTimer = setInterval(() => { nowMs.value = Date.now() }, 1000)
})
// ⚠️ 切数据源（`正常` ⇄ `模拟日常`）⇒ 立刻重连（`connect()` 会先 `closeStream()`）。
//    ⚠️ **切源 = 隔离，不是清空**（用户 2026-10-16 裁定 B）：`W.normal` / `W.demo` 两桶
//    各自留着、谁也不动，显示层只取 `cur` 那一桶 ⇒ 切回来原样还在。
//    ⚠️ 那条按 `seq` 去重的集合 `seenSeq` 仍然**跨源共用**（它不封顶，两桶各留一份没有意义）⇒
//    **两套 seq 必须不重叠**，这条由演示世界保证（它的 `evSeq` 从一个高位起，
//    见 `api/demo-stream.js` 的 `newWorld`）。
watch(() => demo.on, connect)
// ⚠️ 切源要**关掉抽屉**：`openedTrace` 指向的是另一桶里的某一组，留着会把「上一源的链路」
//    挂在「这一源的列表」旁边。关掉是 UI 状态、不是数据 —— 两桶的数据一行没动。
watch(cur, closeTrace)
onBeforeUnmount(() => {
  closeStream()
  if (tickTimer) clearInterval(tickTimer)
  if (winRaf) cancelAnimationFrame(winRaf)
  clearTimeout(exportNoteTimer)
})
</script>

<style scoped>
/* ── 外框：一屏六区 ──────────────────────────────────────────────────────────
   ⚠️ **这里必须是视口推导的确定高，不能写 `height: 100%`。**实测（`probe-b7a3-eventlog-rewrite`）：
      外壳 `.admin-shell` 是 `min-height: 100vh`（**不是** `height`）⇒ 内容一高，它的高就由内容
      撑开；`.a-main` 是 `display: block`（不是 flex 容器）⇒ 它的高同样是内容驱动
      ⇒ `.a-el` 的 `height:100%` **没有可解析的父高**，按 `auto` 处理（实测算到 3724.12px，
      正是内容高）⇒ 窗口形态**整条失效**：外框 3724px、整页滚 2748px、③ 一行也不内滚。
   ⇒ 用 `calc(100vh − 124px)`。那 124px 是从 `AdminShell.vue` 读出来的两段：
      `.a-top { height: 60px }`（`:237-239`）+ `.a-main { padding: var(--s6) }` = 32×2（`:297-301`）。
      ⚠️ 外壳那两处一旦改，这个常数就错 —— 由探针的 ⑪「外框装在一屏内」把住。
   ⚠️ `min-height` 是**退化档**：视口极矮（< ~484px）时兜一个可用高度，代价是那种尺寸下页面会滚。 */
.a-el {
  height: calc(100vh - 124px);
  min-height: 360px;
  display: flex;
  flex-direction: column;
}
.ev-frame {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  overflow: hidden;
}
/* 区与区之间：一条线，不是一段白 */
.ev-frame > * + * {
  border-top: 1px solid var(--line);
}
.idx {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-right: 4px;
}

/* ── ① 状态条 ───────────────────────────────────────────────────────────────── */
.ev-status {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--s2) var(--s3);
}
.ev-status .r1,
.ev-status .r2 {
  display: flex;
  align-items: center;
  gap: var(--s4);
  min-width: 0;
}
.ev-status .r1 {
  min-height: 34px;
  /* ⚠️ 必须允许换行：主区可用宽 1129px（1440 − side 232 − padding 32×2 − 滚动条 15），
     5 格读数 + sevmini + 数据源开关 + 计数合计约 1060px + 间隙 —— 差一点就溢出，
     而 flex 默认不换行的表现是**右侧被切掉**（不是滚动）。 */
  flex-wrap: wrap;
}
.ev-status .r2 {
  min-height: 24px;
  gap: var(--s3);
  /* ⚠️ 窗口形态下 `.r2` **只能是一行**：`.ev-status` 是 `flex: 0 0 auto`，它长高就从
     `.ev-body`（唯一伸缩的那一行）嘴里抢高度 ⇒ `flex-wrap: nowrap`，绝不换行。
     ⚠️ **但 `overflow: hidden` 单独用是不够的**（2026-10-16 实测）：它不会报警，只会在
     最右边**静默切掉** `.tz`（拍/MQ 未连接那颗点）—— 那是真数据，不是提示。实测宽度
     （1440 视口，`.r2` 可用 1118px）：默认态内容 755px；「自定义」一起止两栏 → 1013px；
     再加上那条 240px 的导出小字 → **1257px 溢出 139px**（触发者其实是那行**临时**小字）。
     所以：`overflow: hidden` 留着兜底，但三枚 `.seg` 都要**能收缩**，并按「谁有 title 全文
     就先压谁」排序 —— `.hint`（有 `title` 全文）先压、`.dt`（压了看得出被切、点开有原生
     日期选择器）次之、`.tz`（压了没有任何提示）**最后且不可压**。同裁定 C 的口径。
     （探针把着三条：默认态 / 导出提示在时 / 自定义态，见 `probe-b7a3-eventlog-rewrite` ⑰b·⑲·⑳。） */
  flex-wrap: nowrap;
  overflow: hidden;
}
/* ① 第二排的三枚控件（裁定 A）。共用一套「分段控件」外观：一枚 `.lab` + N 枚 `.chip`。 */
.ev-status .seg {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  /* `0 1 auto`：允许收缩（`flex-basis: auto` = 内容宽），但**不许涨**。
     没有这条时，默认态下三枚控件只占 755px、剩下的 363px 全空着，而「自定义」一出来就
     直接顶穿 —— 收缩能力必须**提前**给，不能等溢出发生。 */
  flex: 0 1 auto;
  min-width: 0;
}
.ev-status .seg .lab {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  /* 同 `.chip`：名字被切一半（「时间范…」）比溢出更难读，不给它收缩权。 */
  flex: 0 0 auto;
  white-space: nowrap;
}
.ev-status .seg .chip {
  font: inherit;
  font-size: var(--fs-xs);
  line-height: 18px;
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 6px;
  cursor: pointer;
  white-space: nowrap;
  /* ⚠️ 必须显式挡住：.seg 上一旦开了可收缩，flex 的默认 `flex-shrink: 1` 会把**按钮**也拉进来
     一起压 —— 压出来的是「近 5 …」这种被切了一半的按钮，比溢出更糟（按钮切了就看不出能点）。
     收缩只交给 `.hint` 与 `.dt` 两个有交代的东西。 */
  flex: 0 0 auto;
}
.ev-status .seg .chip:hover {
  color: var(--ink);
}
.ev-status .seg .chip.on {
  color: var(--brand-ink);
  border-color: var(--brand-soft);
  font-weight: var(--fw-med);
}
/* 「自定义」的起止：用原生 `input[type=datetime-local]`。⚠️ 只改外边那一圈 —— 自造一个
   日历弹层是另一种「编」，这个控件本来就自带一个（`color-scheme: dark` 让它跟着暗色走）。 */
.ev-status .seg .dt {
  font: inherit;
  font-size: var(--fs-xs);
  /* 用 `flex-basis` 而不是 `width`：这样它才**参与收缩**（`width` 是刚性尺寸，收缩要靠
     `flex-basis`）。138px 是 Chrome 里 `step=1` 的 datetime-local 刚放得下
     `2026/10/16 21:27:15` 的宽度；`min-width: 88px` 是地板 —— 到底了还挤，就轮到
     `overflow: hidden` 去切，而不是无限压成一条线。 */
  flex: 0 1 138px;
  min-width: 88px;
  color: var(--ink);
  color-scheme: dark;
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 4px;
}
.ev-status .seg .tilde {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  flex: 0 0 auto;
}
/* 导出后那行小字。⚠️ 它必须有，且必须**看得见**：下载被浏览器拦掉时，用户唯一的线索就是它
   （没有它，「点了没反应」和「导出了但没找到文件」长得一模一样）。
   ⚠️ 同时它也是 `.r2` 那条「谁先被压」链上的**第一顺位**（240px 是这一排最大的弹性余量，
   而它又有 `title` 全文兜底）—— 所以 `flex: 0 1 auto` + `min-width: 0`，压到 0 也不许
   把 `.tz` 顶出去。这一点在 ⑳ 那条断言里被钉住（自定义态 + 提示同时在 = 最宽的一态）。 */
.ev-status .seg .hint {
  font-size: var(--fs-xs);
  color: var(--brand-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 240px;
  flex: 0 1 auto;
  min-width: 0;
}
.ev-status .hl {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  white-space: nowrap;
}
.led {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  flex: none;
}
.led.off {
  background: var(--line-2);
}
.ev-status .cell {
  display: flex;
  flex-direction: column;
  min-width: 96px;
}
.ev-status .cell b {
  font-family: var(--font-mono);
  font-size: var(--fs-md);
  font-weight: var(--fw-semi);
  line-height: 1.15;
  color: var(--ink);
}
.ev-status .cell span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.ev-status .cell.err {
  min-width: 84px;
}
.ev-status .cell.err b {
  display: flex;
  align-items: baseline;
  gap: 1px;
}
.ev-status .cell.err b i {
  font-size: var(--fs-xs);
  font-style: normal;
  color: var(--ink-3);
}
/* 翻红：错误率 > 0 时把读数染色 + 加一条下划线（不加边框、不改字号，避免整条跳动） */
.ev-status .cell.err.alert b,
.ev-status .cell.err.alert b i {
  color: var(--danger);
}
.ev-status .cell.err.alert {
  border-bottom: 2px solid var(--danger);
  margin-bottom: -2px;
}
/* 严重度 mini 三色条：宽度 = 窗口内占比（动效落点 9） */
.ev-status .sevmini {
  display: flex;
  gap: 1px;
  width: 84px;
  height: 10px;
  flex: none;
  align-self: center;
}
.ev-status .sevmini i {
  height: 100%;
  transition: flex-grow 0.4s ease;
}
.cnt {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
/* ⚠️ 这里**不给** `margin-left: auto`：`.r2` 里两个子元素都带 auto 外边距时，
   富余空间会在两个 auto 之间**均分** ⇒ `.tz` 被推到中间而不是靠左。只让 `.note` 吃 auto。 */
.ev-status .tz {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  /* ⚠️ **不可压**（收缩链上的最后一环）：它被切掉时页面上没有任何提示，而拍数/MQ 状态
     是这里唯一的出处。三枚 `.seg` 可收缩就是靠它 `0 0 auto` 兜住的。 */
  flex: 0 0 auto;
}
.ev-status .tz > span {
  white-space: nowrap;
}
.ev-status .tz b {
  color: var(--brand-ink);
}
.mqdot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  margin-right: 4px;
}
.mqdot.off {
  background: transparent;
  border: 1px solid var(--line-2);
}
.ev-status .note {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  margin-left: auto;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── 条件块（断线 / MQ）────────────────────────────────────────────────────── */
.ev-cond {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s2) var(--s3);
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-ground);
}
.ev-cond.is-warn {
  color: var(--warning);
  background: var(--warning-ground);
}
.ev-cond .sub {
  color: var(--ink-3);
  font-size: var(--fs-xs);
}
.ev-btn {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: 1px var(--s3);
  cursor: pointer;
  white-space: nowrap;
}
.ev-btn:hover:not([disabled]) {
  color: var(--ink);
  background: var(--surface-3);
}
.ev-btn[disabled] {
  color: var(--ink-3);
  cursor: not-allowed;
}

/* ── 中段：三栏（甲）────────────────────────────────────────────────────────── */
.ev-body {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 300px;
}
.ev-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.ev-col + .ev-col,
.ev-body > * + * {
  border-left: 1px solid var(--line);
}

/* ── ② 过滤栏 ───────────────────────────────────────────────────────────────── */
.ev-rail {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.ev-rail .rl {
  flex: 0 0 auto;
  padding: var(--s1) var(--s3);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.ev-rail .ctl {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: var(--s2) var(--s3);
  border-bottom: 1px solid var(--line);
}
.ev-rail .ln {
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-width: 0;
}
.ev-rail .lab {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  flex: 0 0 30px;
}
.ev-rail .box {
  flex: 1 1 auto;
  min-width: 0;
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 2px var(--s2);
}
.ev-rail .box.is-ro {
  display: flex;
  align-items: center;
  gap: var(--s2);
  color: var(--ink-3);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ev-rail .box .x {
  margin-left: auto;
  font: inherit;
  color: var(--ink-2);
  background: none;
  border: 0;
  cursor: pointer;
}
.ev-rail .chip {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 var(--s2);
  cursor: pointer;
}
.ev-rail .chip.on {
  color: var(--brand-ink);
  border-color: var(--brand-soft);
  font-weight: var(--fw-med);
}
/* 行清单：**只有它滚** */
.ev-rail .rows {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s1) 0;
}
.ev-rail .row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  width: 100%;
  font: inherit;
  font-size: var(--fs-sm);
  color: var(--ink);
  background: none;
  border: 0;
  padding: var(--s1) var(--s3);
  cursor: pointer;
  text-align: left;
}
.ev-rail .row:hover {
  background: var(--surface-2);
}
.ev-rail .row .sw {
  width: 10px;
  height: 10px;
  flex: none;
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  background: var(--brand-soft);
}
.ev-rail .row .k {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 9 类每类前的那枚图标（用户 2026-10-16）。⚠️ 固定 12px 宽 + `flex: none`：
   `.row` 是 flex 容器，不给固定宽的话图标列会随类名长短**一行一个宽度**，9 行左缘参差。
   ⚠️ 「全部」与设施 3 类那几行放的是**空** `.ic` 占位 —— 见的模板注释。 */
.ev-rail .row .ic {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 12px;
  flex: none;
  /* 用户 2026-10-17：② 的类图标要与 ③ 同类**同色**（截图实测这里原来是 `--ink-2` 灰白，
     `KIND_COLOR` 只喂到了 ③ 的 `.log-line .ic`）。`--c` 由行上的内联样式给（= `kindColor(k)`）；
     认不出时回落中性灰（与 `kindColor()` 的兜底同一档）。`.row.off` 那条规则特异性更高，
     关掉该类时仍然整行转灰 —— 关掉的类不该继续亮着。 */
  color: var(--c, var(--ink-2));
}
.ev-rail .row.off .ic {
  color: var(--ink-3);
}
.ev-rail .row .c {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}
.ev-rail .row.off .sw {
  background: var(--surface);
}
.ev-rail .row.off .k,
.ev-rail .row.off .c {
  color: var(--ink-3);
}
.ev-rail .row.all {
  font-weight: var(--fw-med);
  border-bottom: 1px solid var(--line-2);
}
/* ── 设施 3 类（裁定 D：框架做、置灰）──────────────────────────────────────────
   虚线边框 = 同 ⑥ 开关栏「后端 WIRED 清单里没有它」的同一套画法（全页只有这两种地方用虚线，
   都在说同一件事：**后端面没接上**）。`cursor: not-allowed` + `[disabled]` 双重表达不可点。 */
.ev-rail .rows .sep {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s2) var(--s3) 2px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.ev-rail .row.is-fac,
.ev-rail .row.is-fac:hover {
  color: var(--ink-3);
  background: none;
  cursor: not-allowed;
}
.ev-rail .row.is-fac .sw {
  background: none;
  border-style: dashed;
}
.ev-rail .row.is-fac .c {
  color: var(--ink-3);
}
/* 口径 / 严重度 / 全部打开：**钉在栏底**，不跟着行清单滚 */
.ev-rail .sub {
  flex: 0 0 auto;
  padding: var(--s2) var(--s3);
  border-top: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.5;
}
.ev-rail .sub b {
  color: var(--ink-2);
}
.ev-rail .sev {
  flex: 0 0 auto;
  display: flex;
  gap: var(--s2);
  padding: 0 var(--s3) var(--s2);
}
.ev-rail .sevchip {
  display: inline-flex;
  align-items: center;
  /* D 块（用户 2026-10-17「图标 + 等级文字 6–8px」）：**4px → 8px**。
     ⚠️ 这一处就是那句里的「等级文字」—— ② 栏底那三枚严重度开关（圆点 + 信息/警告/错误）。
     同一栏里类清单那九行早就是 `gap: var(--s2)`（8px），两处现在同一档。 */
  gap: var(--s2);
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: none;
  border: 0;
  cursor: pointer;
}
.ev-rail .sevchip .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c);
  flex: none;
}
.ev-rail .sevchip.off {
  color: var(--ink-3);
}
.ev-rail .sevchip.off .dot {
  background: var(--line-2);
}
.ev-rail .foot {
  flex: 0 0 auto;
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--surface-2);
  border: 0;
  border-top: 1px solid var(--line);
  padding: var(--s2) var(--s3);
  cursor: pointer;
  text-align: left;
}
.ev-rail .foot[disabled] {
  color: var(--ink-3);
  cursor: not-allowed;
}

/* ── ③ 事件流 ───────────────────────────────────────────────────────────────── */
.ev-spark {
  flex: 0 0 auto;
  padding: var(--s2) var(--s3) 0;
}
.ev-spark svg {
  display: block;
  width: 100%;
}
/* 折线图的两排小字标签（用户 2026-10-16 选乙：图内小字，**不补坐标轴**）。
   ⚠️ 只是两排 `space-between` 的小字，**不画轴线、不画刻度线** —— 画了就是坐标轴，用户明确不要。
   ⚠️ 用 `--ink-3`：不抢戏，但 12px 在 `--surface` 上是 4.9:1，看得清（对比度账见下方的量法）。 */
.ev-spark .ax {
  display: flex;
  justify-content: space-between;
  gap: var(--s3);
  padding: 0 2px;
  font-size: var(--fs-xs);
  line-height: 1.3;
  color: var(--ink-3);
  white-space: nowrap;
}
.ev-spark .ax .r {
  text-align: right;
}
.ev-spark .cap {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  padding-bottom: 2px;
}
/* ③ 的行：**表头仍是栅格、数据行改成「一行自然流」**（用户 2026-10-16「消息折行 / 树内行折行」）。
   ⚠️ 为什么数据行不能再用栅格：栅格里每个单元各自一格，**折行只会发生在格子内部**，
      `1fr` 那格一折就撑高整行、前五格却还留在第一行且**不缩进**（第二行从最左边重新开始）。
      用户要的是「续行缩进 2-3 字符、不带前导符」——那是**悬挂缩进**，只有行内流能做。
   ⚠️ 列宽用**等值的 inline-block 宽度**复刻（`.pre` 的 `--pc`、`.tm` 84px…），
      再各补一个 8px `margin-right`（= 原栅格的 `gap`）⇒ 数据行与表头的列**逐像素对齐不变**，
      探针里「成员比组头右移 22px」那类相对判据全部照旧。`ch` 跟着等宽字体走，不写死 px。 */
.log-head {
  display: grid;
  grid-template-columns: var(--pc, calc(2ch + 8px)) 84px 14px 34px 40px minmax(0, 1fr);
  gap: 0 8px;
  align-items: center;
  font: var(--fs-xs) / 1.45 var(--font-mono);
  /* ① 的「密度」控件（裁定 A）：紧凑 22px / 舒适 28px，由 `.ev-col` 上的 `--row-h` 喂进来。
     ⚠️ 表头与数据行**必须共用这一个变量** —— 只改行不改表头，两段对不上列。 */
  min-height: var(--row-h, 22px);
}
.log-line {
  display: block;
  font: var(--fs-xs) / 1.45 var(--font-mono);
  min-height: var(--row-h, 22px);
}
/* 折行容器：**最多 2 行**（用户裁定：22px × 5 行 = 110px，一条事件占半屏会破节奏）。
   `padding-left: 2ch` + `text-indent: -2ch` = **悬挂缩进** —— 首行从 `--s3` 起（前导符在那儿），
   续行停在 `--s3 + 2ch`：缩进 2 字符、**不叠加树前导符宽度**、也**不对齐到消息列**（那会留一大段空白）。

   ⚠️ 用 `max-height` **不是** `-webkit-line-clamp`：实测（探针 ⑱ 的盒模型解剖）clamp 那套
     （`display: -webkit-box` + `-webkit-box-orient` + `-webkit-line-clamp`）在**单行**时
     自己就有 **23px** 高 —— 而它的内容只有 16~17px（`.flow` 16 / `.pre` 17）⇒ 紧凑态行高
      22px 变成 **25px**，与表头对不上。那是遗留盒模型自带的高度，不是我们要的「2 行」。
   ⚠️ `2 * 1.45em` 里的 `1.45` **必须**与 `.log-line` 那条 `font: … / 1.45 …` 的 `line-height`
      逐字一致（`em` 取本元素的 `font-size` = `--fs-xs`，故这里就是「2 行」的精确高度）。
      改行高时两处一起改，否则截断位置会落在第 2 行的中间。
   ⚠️ 第 3 行起是**硬截**（用户：「第 3 行开始截断」+「删 text-overflow: ellipsis」）——
      `overflow: hidden` 直接切，不加省略号。 */
.log-line .ln {
  display: block;
  max-height: calc(2 * 1.45em);
  overflow: hidden;
  padding-left: 2ch;
  text-indent: -2ch;
  /* 渲染隔离（用户 2026-10-17 性能微调）：单行的重排 / 重绘不扩散到整张表。
     ⚠️ 与下面的窗口化一起用 —— `contain` 只能把「一行」的代价压小，不能减少行数。 */
  contain: layout paint style;
}
/* hover（或键盘聚焦）⇒ **展开全文**：只解除 2 行上限（收尾批 2 起技术字段已常显，
   这里不再兼职「显不显示」）。纯 CSS，没有 JS 测量 / `is-clip` 类。 */
.log-line:hover .ln,
.log-line:focus-visible .ln,
.log-line:focus-within .ln {
  max-height: none;
  overflow: visible;
}
.log-line .ln > .flow {
  /* 行内流容器：**不加任何 `display`/`flex`** —— 一加就又变成盒子，折行就不发生在文本上了。 */
  word-break: break-word;
}
.log-head {
  flex: 0 0 auto;
  padding: 1px var(--s3) 2px;
  border-bottom: 1px solid var(--line-2);
  color: var(--ink-3);
}
.log-head .ms {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ev-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  list-style: none;
  padding: 0;
  margin: 0;
  /* 告诉合成器这个容器就是滚动主体（用户 2026-10-17 性能微调）。
     ⚠️ 只写在**滚动容器**上；不要给每行加 `will-change: transform` —— 那是图层爆炸。 */
  will-change: scroll-position;
}
/* 窗口化渲染的上下空白：只撑高度，不画任何东西。 */
.ev-list .vpad {
  display: block;
  margin: 0;
  padding: 0;
  pointer-events: none;
}
/* ── 滚动条：压窄 + 淡色（用户 2026-10-16「滚动条太厚」）──────────────────────────
   ⚠️ **核实结论：上一轮没落地。** `git log -S "webkit-scrollbar"` 对观察目录为空 ——
      那条 7px 规则只活在**第 ④ 步的矩形草图** `docs/美化/c3-admin-preview/_struct-wire-ev.css`
      （提交 ab33b89，选择器 `.ev-pane`）里；第 ⑥ 步把草图做成真组件时**没有搬过来**，
      所以打到真后端上看到的仍是 Chromium 默认的 15px。这一轮是真落地，不是「再压一点」。
   ⚠️ **刻意不写 `scrollbar-width` / `scrollbar-color`**（草图里留的那条按语）：Chrome ≥ 121
      只要写了这两个属性（值非 `auto`），整段 `::-webkit-scrollbar` 会被**整块忽略** ——
      `scrollbar-width: thin` 拿到的是固定 10px，只写 `scrollbar-color` 直接退回 15px。
      ⇒ 两套只留一套，留能精确到 px 的这套。
   ⚠️ 配色不新造：`--line-2`（#3a4a63）当滑块、hover 换 `--ink-3`（#7b8aa3），
      对 `.ev-rail` 的底（`--surface-2` #1f2a3f）约 1.6:1 / 3.2:1 —— 判据是
      「滚动条不该是视觉焦点」，所以**默认态刻意低于 3:1**（那是给文字/图标定的线），hover 才亮起来。 */
.ev-rail .rows::-webkit-scrollbar,
.ev-list::-webkit-scrollbar,
.ev-live::-webkit-scrollbar,
.chain::-webkit-scrollbar {
  width: 7px;
  height: 7px;
}
.ev-rail .rows::-webkit-scrollbar-track,
.ev-list::-webkit-scrollbar-track,
.ev-live::-webkit-scrollbar-track,
.chain::-webkit-scrollbar-track {
  background: transparent;
}
.ev-rail .rows::-webkit-scrollbar-thumb,
.ev-list::-webkit-scrollbar-thumb,
.ev-live::-webkit-scrollbar-thumb,
.chain::-webkit-scrollbar-thumb {
  background: var(--line-2);
  border-radius: 4px;
}
.ev-rail .rows::-webkit-scrollbar-thumb:hover,
.ev-list::-webkit-scrollbar-thumb:hover,
.ev-live::-webkit-scrollbar-thumb:hover,
.chain::-webkit-scrollbar-thumb:hover {
  background: var(--ink-3);
}
.ev-rail .rows::-webkit-scrollbar-corner,
.ev-list::-webkit-scrollbar-corner,
.ev-live::-webkit-scrollbar-corner,
.chain::-webkit-scrollbar-corner {
  background: transparent;
}
.log-line {
  padding: 1px var(--s3);
  cursor: default;
}
/* 行间 gap = 0、不加行边框：前导符与 hover 已经给足了行感（docker logs 也没有行线）。 */
.log-line:hover {
  background: var(--surface-2);
}
.log-line.is-openable {
  cursor: pointer;
}
.log-line.is-openable:focus-visible {
  outline: none;
  background: var(--surface-3);
}
/* 点某行 ⇒ **同 trace 整组**背景微染（动效落点 6：只改背景，不加边框、不加色块） */
.log-line.sel,
.log-line.sel:hover {
  background: var(--surface-3);
}
/* 前导符：脚手架（`rl` 竖向线）画**淡**、分叉（`gl` 的 ├─/└─）画**亮** ——
   一列同样亮的 `├─` 会被读成项目符号而不是树（用户：「所有行看起来是平的」的观感来源之一）。 */
.log-line .pre {
  display: inline-block;
  width: var(--pc, calc(2ch + 8px));
  margin-right: 8px;
  color: var(--ink-3);
  white-space: pre;
}
.log-line .pre .rl {
  color: var(--line-2);
}
.log-line .pre .gl {
  color: var(--ink-3);
}
/* 「这一行有树形」（组头 / 组尾 / 成员）⇒ 前缀整体提亮一档，让分组从一列平铺的行里跳出来。
   ⚠️ 只提亮**树形行**，纯平铺的行保持 `--ink-3`：全都提亮等于都没提亮。 */
.log-line.is-tree .pre .gl {
  color: var(--ink-2);
}
.log-line.is-tree .pre .rl {
  color: var(--ink-3);
}
.log-line .tm {
  display: inline-block;
  width: 84px;
  margin-right: 8px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.log-line .ic {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-right: 8px;
  vertical-align: -2px;
  color: var(--c, var(--ink-3));
}
.ev-ic {
  width: 14px;
  height: 14px;
  stroke: currentColor;
  stroke-width: 1.4;
  flex: none;
}
.ev-ic.s {
  width: 12px;
  height: 12px;
  vertical-align: -2px;
}
/* 请求开始/结束那两行没有真图标（真 `KIND_ICON` 里没有「请求」这一类）⇒ 一条短横线。
   ⚠️ `inline-block` 必须有：行内流里的裸 `<i>` 是 `display: inline`，`width/height` 会被忽略
     （那它就成了一个 0×0 的隐形元素，图标那一格空着 —— 而且不会报错）。 */
.reqmark {
  display: inline-block;
  width: 11px;
  height: 2px;
  vertical-align: middle;
  background: var(--ink-3);
  flex: none;
}
.log-line .lv {
  display: inline-block;
  width: 34px;
  margin-right: 8px;
  font-size: var(--fs-xs);
  /* ⚠️ 行内喂的是 `--sevc`，**不是** `--c2`：`--c2` 是色板里的「锁」色，两套变量曾经撞名
     ⇒ 锁行的 `--c: var(--c2)` 会解析成该行的严重度色（INFO 青 / WARN 橙 / ERROR 红），
     同一个「锁」随等级变色。改名后类色与严重度互不干扰（用户 2026-10-17 微调时量出来的）。 */
  color: var(--sevc, var(--ink-3));
  white-space: nowrap;
}
.log-line .md {
  display: inline-block;
  width: 40px;
  margin-right: 8px;
  /* 类名与图标**同色**（用户 2026-10-17 微调）：`--c` 由行上的内联样式喂 = `kindColor(kind)`。
     请求行（`.log-line.req`）在下面单独特判成 `--ink`，不受这条影响。 */
  color: var(--c, var(--ink-2));
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  /* ⚠️ 必须显式归零：`.ln` 的悬挂缩进是 `text-indent: -2ch`，而 `text-indent` **会被继承**，
     行内块自己又是个块容器 ⇒ `.md` 里的首行被左移 24px、再被 `overflow: hidden` 裁掉
     ⇒ 类名列只剩第二个字（「缓存」显示成「存」、「请求」显示成「求」）。
     这是收尾批 2 目检时量出来的（`.md` clientWidth 40 / scrollWidth 54），不是猜的。 */
  text-indent: 0;
}
/* ⚠️ 前导符 / 时刻 / 图标 / 级别 / 类 这五格是 `inline-block`（原栅格列改行内流时留下的）。
   它们的 `line-height` 继承 `.log-line` 的 1.45 ⇒ **盒子高 17.39px**，比父级的行盒还高
   （12px 字 × 1.45 = 17.4）。基线对齐时，这高出来的一截会把整行的行盒顶到 22.78px
   ⇒ 紧凑密度量到 **25px 而不是 22px**（表头 22、数据行 25，两段栅格对不上）。
   把这几格的 `line-height` 收到 1：盒子高 = 字号，落回父级行盒之内 ⇒ 行高重新由 `--row-h` 说了算。
   实测（`DBG=1` 的盒解剖，见探针 ⑱ 上方那段）：`.ln` 22.78 → 17.39。
   ⚠️ 另一条能达到同样 17.39 的候选是给这几格 `vertical-align: middle`，**不取**：
     `middle` 对齐的是盒子中心（父级基线 + 半个 x-height）、不是基线，会把这几格的字整体下移，
     与消息列错开 —— 数字对了、形态错了。 */
.log-line .pre,
.log-line .tm,
.log-line .ic,
.log-line .lv,
.log-line .md {
  line-height: 1;
  /* ⚠️ `text-indent` **会被继承**：`.ln` 的悬挂缩进 -2ch 会进到每个 inline-block 里，
     把它们的首行整体左移（实测 -14.4px）—— 于是 `.lv` 的「INFO」压到图标上、
     `.pre` 的树形前缀和 `.tm` 的时刻也各自左移。五个格子必须逐个归零。
     （`.md` 在收尾批 2 已经单独归过零；这里统一收口，避免再漏下一格。） */
  text-indent: 0;
}
.log-line.req .md {
  color: var(--ink);
}
/* 消息那一格 = **行内流**（原来是 `display: flex`）：折行要发生在**文本**上，不是元素之间。
   ⚠️ 原来那套 `flex: 0 N auto` + `text-overflow: ellipsis` 全部删掉 —— 用户裁定
     「不截断：删 text-overflow: ellipsis」「自然折行（尽量塞满一行）」，`flex` 与 `ellipsis`
     正是它做不到的原因。行数上限改由 `.ln` 的 `max-height: 2 × 行高` 管（原来那套
     `-webkit-box` + `-webkit-line-clamp` 自带 23px 固定高，把紧凑行高顶成了 25px）。 */
.log-line .ms {
  display: inline;
}
.log-line .ms b {
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.log-line .ms .ob {
  /* ⚠️ `margin-left` 必须显式给：模板里 `<b>` 与 `<span class="ob">` 之间的空白是**换行**，
     Vue 的空白压缩会把它删掉 ⇒ 行内流里标题与对象会**贴在一起**（「缓存命中商品类型 全部」）。 */
  margin-left: var(--s2);
  color: var(--ink-2);
}
.log-line .ms .ob.p {
  /* ⚠️ B 块「可点 ⇒ `--brand-ink` + 点状下划线」：**默认态就是品牌色**（原来是 `--ink-2`，
     只有 hover 才变蓝）—— 可点提示若不常显，用户在列表里根本发现不了它能点。
     ⚠️ 「可点不许骗人」由模板把住（`:class="{ p: !!r.objId }"`）：没有 id 的对象连 `.p`
     都不会挂上 ⇒ 这一条规则**碰不到**它，画不出一个点不动的下划线。 */
  color: var(--brand-ink);
  text-decoration: underline dotted;
  text-underline-offset: 2px;
  cursor: pointer;
}
.log-line .ms .ob.p:hover {
  /* hover 只把点线换成实线（颜色已经是品牌色，再改色就没有第二档了）。 */
  text-decoration-style: solid;
}
/* ── B 块：句子的**分段着色**（键 / 值 / 值·最次要 / 可点），全部取 admin 主题的 token ──
   用户 2026-10-17 给的色系：键 → `--ink` · 值 → `--ink-2` · 值·最次要（长缓存键）→ `--ink-3`
   · 可点（对象 / trace 短号）→ `--brand-ink` + 点状下划线。
   ⚠️ 类名由 `sentence()` 产的 token 里那个 `c` 直接拼出来（`'se-' + s.c`）⇒ 这里加一档
     就必须在 `sentence()` 的色档常量里也加一个，两处是**同一张表的两半**。 */
.log-line .ms .se-k { color: var(--ink); }
.log-line .ms .se-v { color: var(--ink-2); }
.log-line .ms .se-v3 { color: var(--ink-3); }
.log-line .ms .se-x { color: var(--ink-3); }
.log-line .ms .se-a {
  color: var(--brand-ink);
  text-decoration: underline dotted;
  text-underline-offset: 2px;
  cursor: pointer;
}
/* 从句与括号各占一段、与前面那段的间距 = `--s2`（8px，与 `.ob` 同一档）。
   ⚠️ 必须显式给：模板里这些 `<span>` 之间的空白是**换行**，Vue 的空白压缩会把它删掉
     （同 `.ob` 那条按语的成因）⇒ 不给外距就会贴在一起。 */
.log-line .ms .se-mid,
.log-line .ms .se-ctx {
  margin-left: var(--s2);
}
/* ── 第一层：技术字段 / 发起人 / 来源（用户 2026-10-17 收尾批 2：**默认常显**）──────
   ⚠️ 3a.3 之前这里是 `display: none → hover inline`；收尾批 2 明确取消 hover 才显：
     用户要的是「一睁眼就看得见五要素」。行高仍由 `.ln` 的 2 行上限夹住 —— 超过 2 行
     仍然靠 hover 展开（纯 CSS，见上面 `.log-line:hover .ln`）。
   ⚠️ 每一对 `中文键 值` 之间给 `--s2` 外距（Vue 会压掉模板换行空白，同 `.ob` 那条的成因）；
     键用 `--ink-3`、值用 `--ink-2` —— 键是说明词、值才是内容。 */
.log-line .ms .dt {
  color: var(--ink-3);
  margin-left: var(--s2);
}
.log-line .ms .dt .tf + .tf {
  margin-left: var(--s2);
}
.log-line .ms .dt .tf-k {
  color: var(--ink-3);
}
.log-line .ms .dt .tf-v {
  color: var(--ink-2);
}
.log-line .ms .se-act,
.log-line .ms .se-src {
  margin-left: var(--s2);
  color: var(--ink-3);
}
.log-line .ms .se-act .v,
.log-line .ms .se-src .v {
  color: var(--ink-2);
}
.ev-empty {
  font-size: var(--fs-sm);
  color: var(--ink-3);
  padding: var(--s4) var(--s3);
}

/* ── ④ 实时统计 ─────────────────────────────────────────────────────────────── */
.ev-live {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: var(--s2);
}
.ev-h2 {
  flex: 0 0 auto;
  padding: var(--s1) var(--s3);
  border-bottom: 1px solid var(--line-2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.ev-sub {
  padding: var(--s2) var(--s3) 2px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.ev-top .tabs {
  display: flex;
  gap: 2px;
  padding: 0 var(--s3);
}
.ev-top .tb {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 var(--s2);
  cursor: pointer;
}
.ev-top .tb.on {
  color: var(--brand-ink);
  border-color: var(--brand-soft);
  font-weight: var(--fw-med);
}
.ev-top .lst {
  padding: var(--s1) var(--s3) 0;
}
.ev-top .tr1 {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  padding: 1px 0;
}
.ev-top .tr1 .nm {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ev-top .tr1 .v {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink-3);
}
.ev-top .empty {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.5;
}
.ev-top .cap {
  padding: var(--s1) var(--s3) 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.4;
}
.ev-bars {
  padding: 0 var(--s3);
}
.ev-bar {
  display: grid;
  /* 首列 56 → 72px（用户 2026-10-16「④ 类计数 9 类：每类前加图标」）：
     最长那三个字（敏感词 / 状态流转…）12px 字号下 36px，加 12px 图标 + 一个空格 ≈ 51px，
     56px 只剩 5px 余量、稍一换字体就被 `text-overflow` 切掉字（切成「敏感…」）。 */
  grid-template-columns: 72px minmax(0, 1fr) 48px;
  align-items: center;
  gap: var(--s2);
  width: 100%;
  font: inherit;
  background: none;
  border: 0;
  padding: 1px 0;
  text-align: left;
  cursor: default;
}
button.ev-bar {
  cursor: pointer;
}
.ev-bar .nm {
  /* D 块（用户 2026-10-17）：图标与文字之间 6–8px。⚠️ 原来靠模板里**一个空格**
     （12px 字号下 ≈3px）—— 那是**字体度量**，换字体就变，量也量不准；
     改成弹性盒 + `gap` 之后间距是 CSS 里的一格，探针能直接量到。 */
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ev-bar .track {
  height: 8px;
  background: var(--surface-2);
  border-radius: var(--r1);
  overflow: hidden;
}
.ev-bar .fill {
  display: block;
  height: 100%;
  /* 用户 2026-10-16「进度条对比度太低：前景/背景对比 ≥ 3:1 · 用现有 token · 不为对比度新造色」。
     ⚠️ 原来是 `--brand-soft`（admin 块里 = `#15264a`），铺在轨道 `--surface-2`（`#1f2a3f`）上
        的相对亮度比只有 **≈1.05:1** —— 那不是「对比度低」，是**等于看不见**（探针能量到 `fill`
        在、`width` 也对，但肉眼完全看不出进度）。这是本页第二次栽在 `--brand-soft` 上
        （上一次是①折线，见那段的按语）⇒ 这里换成填充色 `--brand`（`#2f6fed`）。
     ⚠️ 用 `--brand`（填充档）**不是** `--brand-ink`（文字档）：`tokens.css` 那两行的分工就是
        「`--brand` 用于按钮底、图标」，进度条填充属于这一档。实测 `--brand` 对轨道 **≈3.1:1**、
        对 `.ev-live` 的底（`--surface` `#172032`）≈3.6:1，都过 3:1 线；`--brand-ink` 做填充会
        更亮（≈4.6:1），但那是文字档，用了就等于把两档合并 —— 所以**不**顺手换更亮的那个。
     ⚠️ 严重度那三条本来就用 `severityColor()`（= `--success` / `--warning` / `--danger`，
        对轨道 6.3 / 6.6 / 5.1:1），不用改；缺的只有类计数这 9 条。 */
  background: var(--brand);
  transition: width 0.4s ease;
}
.ev-bar .num {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  text-align: right;
}
/* 设施 3 类（裁定 D）：横条画出来但**恒空**、整行压暗、轨道改成虚线（同 ② 的 `.sw` 与 ⑥ 的
   `.sw2.dead`：全页第三处虚线，说的还是「后端面待落地」）。计数列写 `—` 不写 `0`。 */
.ev-bar.is-fac .nm,
.ev-bar.is-fac .num {
  color: var(--ink-3);
}
/* ⚠️ 设施 3 类那三条**没有图标**（不在这 9 类里）⇒ 文字会比其他 9 条左移一格。
   补一个等宽的左内边距（12px 图标 + 一个空格 ≈ 15px）把两种行的文字左缘对齐；
   不补的话同一个列表里两组的标签会参差 —— 那是画法不一致，不是「有意义的差异」。 */
.ev-bar.is-fac .nm {
  padding-left: 15px;
}
.ev-bar.is-fac .track {
  background: none;
  border: 1px dashed var(--line-2);
  opacity: 0.7;
}
.ev-read {
  display: flex;
  flex-wrap: wrap;
  gap: 2px var(--s4);
  padding: 0 var(--s3);
}
.ev-read span {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.ev-read i {
  font-family: var(--font-mono);
  font-style: normal;
  color: var(--ink);
  margin-left: 4px;
  font-variant-numeric: tabular-nums;
}

/* ── ⑤ 链路展开（默认塌成一条）─────────────────────────────────────────────── */
.ev-drawer {
  flex: 0 0 auto;
  max-height: 42%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.ev-drawer .hd {
  display: flex;
  align-items: center;
  gap: var(--s3);
  min-height: 34px;
  padding: var(--s1) var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  cursor: default;
}
.ev-drawer.is-open .hd {
  cursor: pointer;
}
.ev-drawer .hd .mono {
  font-family: var(--font-mono);
  color: var(--brand-ink);
}
.ev-drawer .hd .kv {
  color: var(--ink-2);
}
.ev-drawer .hd .kv b {
  color: var(--ink);
}
.ev-drawer .hd .cnt {
  color: var(--ink-3);
}
.ev-drawer .hd .hint {
  color: var(--ink-3);
}
.ev-drawer .hd .ev-btn {
  margin-left: 0;
}
.ev-drawer .hd .ev-btn:last-child {
  margin-left: auto;
}
.ev-drawer .st {
  font-family: var(--font-mono);
  border-radius: var(--r1);
  padding: 0 5px;
}
.ev-drawer .st.is-ok {
  color: var(--success);
  background: var(--success-ground);
}
.ev-drawer .st.is-warn {
  color: var(--warning);
  background: var(--warning-ground);
}
.ev-drawer .st.is-bad {
  color: var(--danger);
  background: var(--danger-ground);
}
.chain {
  list-style: none;
  margin: 0;
  padding: 0 var(--s3) var(--s3) var(--s3);
  overflow-y: auto;
  border-top: 1px dashed var(--line);
}
.chain-i {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: var(--s3);
  padding: 1px 0;
  font-size: var(--fs-sm);
  color: var(--ink);
}
.chain-i .tm {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.chain-i .d b {
  color: var(--ink);
  font-weight: var(--fw-semi);
}
/* D 块「同类检查」的第三处：⑤ 链里那枚类图标与类名之间原来也是**一个空格**（15px 字号下
   ≈4px）。⚠️ 只包住「图标 + 类名」这一小段（`.d` 里后面还有中文人话与 `display:block`
   的技术行）—— 整条 `.d` 改成弹性盒会把那两段也拉成一行。 */
.chain-i .d .d-hd {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
}
.chain-i.is-ends .d b {
  color: var(--brand-ink);
}
.chain-i.is-miss {
  color: var(--ink-3);
}
/* ⑤ 每条事件下面那行技术字段原文（用户 2026-10-16「打开详细 ⇒ 中英文都要」）。
   ⚠️ 单独一行（`display: block`）而不是接在人话后面：它比人话长得多，接在一行会把
      「中文那句」挤到折行、两种语言混在一起 —— 分层的前提是**两层各自成行**。
   ⚠️ 等宽字体 + `--ink-3`：一眼分清这是「机器那层」，且可原样复制去 grep。 */
.chain-i .d .tech {
  display: block;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
  word-break: break-all;
}

/* ── ⑥ 开关栏（占位）───────────────────────────────────────────────────────── */
.ev-switch {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s2) var(--s4);
  padding: var(--s1) var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.ev-switch .grp {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
}
.ev-switch .lab {
  color: var(--ink-3);
}
.ev-switch .sw2 {
  border: 1px solid var(--line-2);
  border-radius: var(--r1);
  padding: 0 6px;
  background: var(--surface-2);
}
.ev-switch .sw2.dead {
  border-style: dashed;
  color: var(--ink-3);
  background: none;
}
.ev-switch .n {
  color: var(--ink-3);
  margin-left: auto;
}

/* ── 窄屏：三栏塌成两栏（③ 让位），再窄就堆叠 ──────────────────────────────── */
@media (max-width: 1100px) {
  .ev-body {
    grid-template-columns: 190px minmax(0, 1fr);
  }
  .ev-live {
    display: none;
  }
}
</style>
