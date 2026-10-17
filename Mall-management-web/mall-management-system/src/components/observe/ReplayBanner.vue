<template>
  <!-- 回放横幅（2026-10-17 回放重做）—— 三个可观测页的**顶部强提示**。
       用户原话：「顶部必须有一条显眼的横幅：正在回放演练记录（进度 X / Y，
       [暂停/继续] [退出回放]），让用户明确知道当前是『过去时』」。
       ⚠️ 它不是装饰：回放期间实时 SSE 已被切断，横幅是页面上**唯一**能解释
          「为什么数字不跳了 / 为什么时间是旧的」的东西 ⇒ 必须比页头其它读数更显眼。
       ⚠️ 2026-10-17 终极修复：**未回放时这里也是一条常驻入口** —— 指标页 / 状态页 /
          日志页顶部都能直接「选择历史批次 → 回放」，不再只能从攻击模拟器页进入。 -->
  <section v-if="s.active" class="a-rb" role="status" aria-live="polite">
    <span class="a-rb-badge">回放</span>
    <div class="a-rb-main">
      <div class="a-rb-line">
        <b class="a-rb-t">正在回放演练记录</b>
        <span class="a-rb-batch" :title="`批次 ${s.batchId}`">批次 {{ shortId }}</span>
        <span class="a-rb-prog">进度 <b>{{ fmtInt(s.cursor) }}</b> / {{ fmtInt(s.count) }}</span>
        <span class="a-rb-clock" :title="clockTitle">回放时钟 <b>{{ clockText }}</b></span>
        <span class="a-rb-speed" :title="`播放时钟：每 1 秒墙钟推进 ${s.speed} 秒日志时间（按历史时间差值播放）；每帧上限按本批密度自适应（50–200 条，本批 ${s.maxPerFrame}）。回放 1:1，不做同类合并。`">{{ s.speed }}× 时钟 · 1:1 不合并 · 每帧 ≤{{ s.maxPerFrame }} 条<template v-if="s.effectiveRate"> · 实测 {{ fmtInt(s.effectiveRate) }} 条/秒</template></span>
        <span v-if="s.paused" class="a-rb-state is-paused">已暂停</span>
        <span v-else-if="s.done" class="a-rb-state is-done">已放完</span>
        <span v-else class="a-rb-state is-play">播放中</span>
      </div>
      <!-- 可拖拽进度条（Seek Slider）——视频播放器式定位。
           ⚠️ 拖拽中（`@input`）**只改这一行的目标时间文字**，不触发任何 seek / 重绘；
              松开鼠标（`@change`）才做一次状态重建。重建期间 `s.seeking` 显示遮罩。 -->
      <div class="a-rb-seek">
        <input
          class="a-rb-range"
          type="range"
          min="0"
          max="100"
          step="0.1"
          :value="dragging ? dragPct : timePct"
          :style="{ '--fill': (dragging ? dragPct : timePct) + '%' }"
          :disabled="s.seeking"
          :aria-valuetext="`${Math.round(dragging ? dragPct : timePct)}%${dragging ? ` · 目标 ${seekClockText}` : ''}`"
          aria-label="回放进度：拖动定位到任意时刻"
          @pointerdown="beginSeek"
          @input="onSeekInput"
          @change="commitSeek"
        >
        <span class="a-rb-seektip" :class="{ on: dragging }">
          <template v-if="dragging">松开 → {{ seekClockText }}（{{ Math.round(dragPct) }}%）</template>
          <template v-else>拖动进度条定位</template>
        </span>
      </div>
      <p v-if="truncated" class="a-rb-note">
        这批台账共 {{ fmtInt(s.total) }} 条，本次按上限载入 {{ fmtInt(s.count) }} 条（limit={{ fmtInt(s.limit) }}）—— 回放的是前一段。
      </p>
    </div>
    <!-- 状态重建遮罩：重建是同步的一大段 apply，遮罩必须在它之前先上屏（会话里 `await nextFrame()`）。 -->
    <div v-if="s.seeking" class="a-rb-mask" role="status" aria-live="assertive">
      <span class="a-rb-spin" aria-hidden="true"></span>
      正在重建 {{ s.seekPct }}% 处的状态：重放历史事件并重绘图表 / 流轨 / 明细…
    </div>
    <!-- 倍速：0.25x / 0.5x / 1x / 2x / 5x / 10x。用户 2026-10-17：「默认 1 倍速；切档不重置进度」，
         并明令在 1x 旁补 0.5x / 0.25x 慢速档。
         ⚠️ 只改 `replaySession.speed`（时钟倍率），不碰 cursor / virtualMs。 -->
    <div class="a-rb-speeds" role="group" aria-label="回放倍速">
      <button
        v-for="sp in REPLAY_SPEEDS"
        :key="sp"
        type="button"
        class="a-rb-sp"
        :class="{ on: s.speed === sp }"
        :aria-pressed="s.speed === sp"
        :title="`${sp}x：每 1 秒墙钟推进 ${sp} 秒日志时间；切换不重置当前进度`"
        @click="setReplaySpeed(sp)"
      >{{ sp }}x</button>
    </div>
    <div class="a-rb-act">
      <!-- 重新开始（重放）：进度归零，从这批台账第一条重播；不退出回放、不重新请求后端。 -->
      <button
        type="button"
        class="a-rb-btn"
        title="重新开始：播放进度归零，从这批台账的第一条重播（批次与倍速不变）"
        @click="restartReplaySession"
      >重新开始</button>
      <button
        v-if="!s.done"
        type="button"
        class="a-rb-btn"
        :title="s.paused ? '继续播放（时钟从暂停处接着走）' : '暂停播放（数据停在当前进度，不丢）'"
        @click="toggleReplayPause"
      >{{ s.paused ? '继续' : '暂停' }}</button>
      <button
        type="button"
        class="a-rb-btn is-exit"
        title="退出回放：放掉本批台账，三页恢复实时流"
        @click="stopReplaySession"
      >退出回放</button>
    </div>
  </section>

  <!-- 未回放：三页顶部常驻的「选择历史批次」入口。
       ⚠️ 只列**有 BatchId 的演练记录**：存量记录当时没写批次，点了也回放不到
          （与攻击页那颗置灰按钮同一判据）。 -->
  <section v-else class="a-rb a-rb--idle" role="region" aria-label="演练回放入口">
    <span class="a-rb-badge is-idle">演练回放</span>
    <div class="a-rb-pick">
      <label class="a-rb-pick-l" for="a-rb-batch">选择历史批次</label>
      <select id="a-rb-batch" v-model="pickId" class="a-rb-sel" :disabled="loading || s.loading">
        <option value="">{{ loading ? '载入批次…' : (batches.length ? '— 请选择 —' : '暂无带批次的演练记录') }}</option>
        <option v-for="b in batches" :key="b.BatchId" :value="b.BatchId" :disabled="b.HasData === false">
          {{ b.Name || '未命名演练' }} · {{ fmtBatchTime(b.CreatedAt) }} · {{ (b.TotalRequests || 0) }} 请求<template v-if="b.HasData === false">（数据已过期清理）</template>
        </option>
      </select>
      <button type="button" class="a-rb-btn" :disabled="loading || s.loading || !pickId || pickedDisabled" :title="pickedDisabled ? '这批台账已过保留期被清理，无法回放' : (pickId ? '读这批台账并按原时间戳回放' : '先选择一个历史批次')" @click="startPicked">
        {{ s.loading ? '载入中…' : '回放' }}
      </button>
      <button type="button" class="a-rb-btn is-exit" :disabled="loading || s.loading" title="重新读取演练记录列表" @click="loadBatches">刷新</button>
      <span v-if="err" class="a-rb-err" :title="err">{{ err }}</span>
      <span v-else-if="s.err" class="a-rb-err" :title="s.err">{{ s.err }}</span>
      <span v-else class="a-rb-hint">顶部直接回放 · 无需先进攻击模拟器页</span>
    </div>
  </section>
</template>

<!--
  回放横幅（共享组件，三页顶部各放一枚）。
  ⚠️ 状态与动作全部来自 `api/replay-session.js` 的**同一个模块级会话**：横幅、日志页的行、
     指标页的「实时已停用」占位读的都是它 —— 不各自复制一份播放状态（那会立刻分叉成
     「横幅说放到一半、列表已经铺满」）。
  ⚠️ 「进度 X / Y」的 Y 是**本次载入条数**（后端 `limit` 截断后的数），不是 `total`；
     被截断时下面那行小字会写清两者差在哪（`truncated`）。
-->
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { attackRecords } from '../../api/observe'
import {
  REPLAY_SPEEDS, pauseReplaySession, replaySession as s, restartReplaySession,
  seekReplaySession, setReplaySpeed, startReplaySession, stopReplaySession, toggleReplayPause,
} from '../../api/replay-session'

const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')

/**
 * 演练记录 `CreatedAt` → 浏览器本地时间文本。
 *
 * ⚠️ 它是**库内 UTC 文本**（`drill_record.created_at` 没在 INSERT 里赋值，走 DB 默认值；
 *    dev 库服务器时区实测为 UTC），与台账 `observed_event.OccurredAt`（Asia/Shanghai 本地）
 *    口径**不同**。不转换的话，下拉框里写 `15:42`、旁边回放时钟写 `23:41`，
 *    看起来像回放错了 8 小时 —— 实际是两列来源不同。
 */
function fmtBatchTime(v) {
  if (!v) return '时间未知'
  const text = String(v).replace(' ', 'T')
  const withTz = /(?:Z|[+-]\d{2}:?\d{2})$/.test(text) ? text : `${text}Z`
  const ms = new Date(withTz).getTime()
  if (Number.isNaN(ms)) return String(v)
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ── 未回放时的「选择历史批次」入口（2026-10-17 终极修复）────────────────────
   ⚠️ 数据源是演练记录列表（`/admin/observe/attack/records`），不是新建的后端端点：
      回放的入口键 `BatchId` 本来就挂在每一行记录上（`DrillRecordDao.COLUMNS`），
      而且「哪一轮演练」的人话名字/时间也只有这张表有。
   ⚠️ 只收 `BatchId` 非空的行：存量记录当时没写批次，点了也读不到台账。 */
const batches = ref([])
const batchLoading = ref(false)
const err = ref('')
const pickId = ref('')

async function loadBatches() {
  if (s.active) return
  batchLoading.value = true
  err.value = ''
  try {
    const data = await attackRecords({ limit: 50, offset: 0 })
    const list = (data && data.list) || []
    batches.value = list.filter((r) => r && r.BatchId)
    if (pickId.value && !batches.value.some((r) => r.BatchId === pickId.value)) pickId.value = ''
  } catch (e) {
    batches.value = []
    err.value = `演练记录读取失败：${(e && (e.message || e)) || '未知错误'}`
  } finally {
    batchLoading.value = false
  }
}

/**
 * 当前选中的批次行 + 「台账已被保留期清理」判据（2026-10-18）。
 *
 * ⚠️ `HasData === false` 是**后端算的**（`DrillRecordDao` 的索引子查询 → 服务层 `HasData`）：
 *    前端不自己拿 `TotalRequests` 猜，也不在选中时才发一次请求去探 —— 列表已经带回来了。
 * ⚠️ 判据写成**严格等于 false** 而不是 `!b.HasData`：存量后端（或旧缓存）没有这个键时
 *    是 `undefined`，那表示「不知道」，不该把还能回放的批次一并置灰。
 */
const pickedRow = computed(() => batches.value.find((r) => r.BatchId === pickId.value) || null)
const pickedDisabled = computed(() => !!pickedRow.value && pickedRow.value.HasData === false)

/** 顶部直接回放：装载成功后横幅自动切到「正在回放」（同一个模块级会话，三页共用）。 */
async function startPicked() {
  const row = batches.value.find((r) => r.BatchId === pickId.value)
  if (!row) return
  if (row.HasData === false) {
    err.value = '该批次历史数据已过期被清理，无法回放（换一批，或重新跑一轮演练）。'
    return
  }
  err.value = ''
  try {
    await startReplaySession(row.BatchId, {
      totalRequests: row.TotalRequests,
      blockedRequests: row.BlockedRequests,
      failedRequests: row.FailedRequests,
      durationMs: row.DurationMs,
    })
  } catch (e) {
    err.value = `回放装载失败：${(e && (e.message || e)) || '未知错误'}`
  }
}

onMounted(() => {
  if (!s.active) loadBatches()
})
// 退出回放后刷新一次：期间可能又跑完了新的演练。
watch(() => s.active, (on) => {
  if (!on) loadBatches()
})

/** 批次 UUID 截短显示；完整值留在 title 里。 */
const shortId = computed(() => {
  const id = String(s.batchId || '')
  return id.length > 12 ? `${id.slice(0, 8)}…` : (id || '—')
})

/* ── 可拖拽进度条（Seek Slider）─────────────────────────────────────────────
   ⚠️ 条的位置按**日志时间**算（`elapsedMs / spanMs`），不是按条数：拖动 40% 就应该
      定位到原时间轴的 40% 处，与「视频播放器」的直觉一致；条数进度仍在上面的
      「进度 X / Y」里如实显示，两者不混用。
   ⚠️ 拖拽中的 `dragPct` 与播放中的 `timePct` 是两个值：前者只跟手指，后者只跟时钟。
      松开鼠标前绝不把 `dragPct` 写回会话 —— 那正是用户点名禁止的「拖一格重绘一次」。 */
const dragging = ref(false)
const dragPct = ref(0)
/** 拖拽开始前是否在播（重建完成后据此续播；拖拽一开始会先暂停时钟）。 */
let dragWasPlaying = false

const timePct = computed(() => {
  if (!s.spanMs) return s.done ? 100 : 0
  const p = ((Number(s.elapsedMs) || 0) / s.spanMs) * 100
  return Math.max(0, Math.min(100, Math.round(p * 10) / 10))
})

/** 拖拽格上的目标时间（原台账时间轴），只用于文字预览。 */
const seekClockText = computed(() => clockOf(s.startTs + (dragPct.value / 100) * (s.spanMs || 0)))

function beginSeek() {
  if (dragging.value || s.seeking) return
  dragWasPlaying = !s.paused && !s.done
  dragPct.value = Math.round(timePct.value)
  dragging.value = true
  // 用户开拖 = 立刻暂停自动播放：否则滑块一边跟手指、一边被播放时钟改写。
  pauseReplaySession()
}

/** `@input`：只改本地目标值 + 那行文字，**不**调用 seek、不写会话。 */
function onSeekInput(e) {
  if (!dragging.value) beginSeek()
  const v = Number(e && e.target && e.target.value)
  if (Number.isFinite(v)) dragPct.value = Math.max(0, Math.min(100, v))
}

/** `@change`（松开鼠标 / 键盘确认）：只在这里做**一次**状态重建。 */
async function commitSeek() {
  if (!dragging.value) return
  const target = dragPct.value
  const resume = dragWasPlaying
  // 重建期间保持 dragging=true：否则 `elapsedMs` 在重建落位时变化，滑块会先跳一下。
  try {
    await seekReplaySession(target / 100, { resume })
  } finally {
    dragging.value = false
  }
}

/** 回放时钟 = 已吐出的最后一条的**原时间戳**（浏览器本地时区）—— 它是「过去时」的直接证据。 */
function clockOf(ms) {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`
}
const clockText = computed(() => clockOf(s.clockTs))

const clockTitle = computed(() => {
  if (!s.startTs) return '回放时钟（本批没有事件）'
  const d = (ms) => {
    const x = new Date(ms)
    const p = (n) => String(n).padStart(2, '0')
    return `${x.getFullYear()}-${p(x.getMonth() + 1)}-${p(x.getDate())} ${p(x.getHours())}:${p(x.getMinutes())}:${p(x.getSeconds())}`
  }
  return `回放时钟跟「已放出的最后一条」走，不是墙钟。\n本批原时间轴：${d(s.startTs)} ~ ${d(s.endTs)}（跨度 ${(s.spanMs / 1000).toFixed(1)} 秒）`
})

/** 被后端 `limit` 截断的判据与旧面板同源：载入条数打满 limit 且 total 更大。 */
const truncated = computed(() => s.count > 0 && s.limit > 0 && s.count >= s.limit && s.total > s.count)
</script>

<style scoped>
.a-rb {
  flex: 0 0 auto;
  /* 三页顶部各一枚；`position: relative` 只为把横幅抬在本页的滚动内容之上，
     不依赖任何页面的遮罩（指标页的旧 `.mp-replay-mask` 已随重做删除）。 */
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s2) var(--s3);
  margin-bottom: var(--s2);
  background: var(--warning-ground);
  border: 1px solid var(--warning);
  border-radius: var(--r2);
  min-width: 0;
}
.a-rb-badge {
  flex: 0 0 auto;
  padding: 2px var(--s2);
  border-radius: var(--r1);
  background: var(--warning);
  color: var(--surface);
  font-size: var(--fs-xs);
  font-weight: var(--fw-med);
  letter-spacing: 0.04em;
}
.a-rb-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.a-rb-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s3);
  min-width: 0;
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.a-rb-t {
  color: var(--warning-deep);
  font-size: var(--fs-sm);
}
.a-rb-prog b,
.a-rb-clock b {
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}
.a-rb-prog,
.a-rb-clock,
.a-rb-speed {
  white-space: nowrap;
}
.a-rb-speed {
  color: var(--ink-3);
}
.a-rb-state {
  padding: 0 var(--s2);
  border-radius: var(--r1);
  border: 1px solid currentColor;
  white-space: nowrap;
}
.a-rb-state.is-play { color: var(--warning-deep); }
.a-rb-state.is-paused { color: var(--warning-deep); font-weight: var(--fw-med); }
.a-rb-state.is-done { color: var(--success); }
/* ── Seek Slider：原生 range + 自定义轨道/滑块 ───────────────────────────── */
.a-rb-seek {
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-width: 0;
}
.a-rb-range {
  -webkit-appearance: none;
  appearance: none;
  flex: 1 1 auto;
  min-width: 80px;
  height: 14px;
  margin: 0;
  background: transparent;
  cursor: pointer;
}
/* 已播放段 = 实心暖色，未播放段 = 浅底；`--fill` 由模板按当前百分比写入。 */
.a-rb-range::-webkit-slider-runnable-track {
  height: 4px;
  border-radius: 2px;
  background: linear-gradient(
    to right,
    var(--warning) 0,
    var(--warning) var(--fill, 0%),
    var(--surface-2) var(--fill, 0%),
    var(--surface-2) 100%
  );
}
.a-rb-range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 12px;
  height: 12px;
  margin-top: -4px;
  border-radius: 50%;
  background: var(--warning);
  border: 2px solid var(--surface);
  box-shadow: 0 0 0 1px var(--warning);
}
.a-rb-range::-moz-range-track {
  height: 4px;
  border-radius: 2px;
  background: var(--surface-2);
}
.a-rb-range::-moz-range-progress {
  height: 4px;
  border-radius: 2px;
  background: var(--warning);
}
.a-rb-range::-moz-range-thumb {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--warning);
  border: 2px solid var(--surface);
  box-shadow: 0 0 0 1px var(--warning);
}
.a-rb-range:focus-visible {
  outline: 2px solid var(--warning);
  outline-offset: 2px;
  border-radius: var(--r1);
}
.a-rb-range:disabled {
  cursor: progress;
  opacity: 0.6;
}
.a-rb-seektip {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.a-rb-seektip.on {
  color: var(--warning-deep);
  font-weight: var(--fw-med);
}

/* ── 重建遮罩：盖住横幅（含倍速/按钮），避免重建中途还能改倍率或退出 ───────── */
.a-rb-mask {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--s2);
  padding: 0 var(--s3);
  background: var(--warning-ground);
  border-radius: var(--r2);
  font-size: var(--fs-xs);
  color: var(--warning-deep);
  font-weight: var(--fw-med);
}
.a-rb-spin {
  width: 12px;
  height: 12px;
  border: 2px solid var(--warning);
  border-top-color: transparent;
  border-radius: 50%;
  animation: a-rb-spin 0.7s linear infinite;
}
@keyframes a-rb-spin {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .a-rb-spin { animation: none; }
}
.a-rb-note {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.a-rb-speeds {
  flex: 0 0 auto;
  display: flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: var(--r1);
  background: var(--surface);
}
.a-rb-sp {
  font: inherit;
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  color: var(--ink-2);
  background: transparent;
  border: 0;
  border-radius: var(--r1);
  padding: 1px var(--s2);
  cursor: pointer;
  white-space: nowrap;
}
.a-rb-sp:hover {
  color: var(--ink);
  background: var(--surface-2);
}
/* 当前档位：实心高亮（与横幅同族），不是只换字色 */
.a-rb-sp.on {
  color: var(--surface);
  background: var(--warning);
  font-weight: var(--fw-med);
}
.a-rb-act {
  flex: 0 0 auto;
  display: flex;
  gap: var(--s2);
}
.a-rb-btn {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--warning-deep);
  background: var(--surface);
  border: 1px solid var(--warning);
  border-radius: var(--r1);
  padding: var(--s1) var(--s3);
  cursor: pointer;
  white-space: nowrap;
}
.a-rb-btn:hover {
  background: var(--warning-ground);
}
.a-rb-btn.is-exit {
  color: var(--ink-2);
  border-color: var(--line);
}
.a-rb-btn.is-exit:hover {
  color: var(--ink);
  background: var(--surface-2);
}
.a-rb-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.a-rb-batch {
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ── 未回放：常驻的批次选择条（三页顶部） ───────────────────────────────── */
.a-rb--idle {
  background: var(--surface);
  border-color: var(--line);
  padding: 3px var(--s3);
  min-height: 30px;
}
.a-rb-badge.is-idle {
  background: var(--surface-2);
  color: var(--ink-2);
  border: 1px solid var(--line);
}
.a-rb-pick {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}
.a-rb-pick-l {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  white-space: nowrap;
}
.a-rb-sel {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r1);
  padding: 1px var(--s2);
  min-width: 260px;
  max-width: 520px;
  height: 24px;
}
.a-rb-sel:disabled {
  color: var(--ink-3);
  background: var(--surface-2);
}
.a-rb-err {
  font-size: var(--fs-xs);
  color: var(--bad, var(--c7));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.a-rb-hint {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
</style>
