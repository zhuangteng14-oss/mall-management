<template>
  <!-- 回放横幅（2026-10-17 回放重做）—— 三个可观测页的**顶部强提示**。
       用户原话：「顶部必须有一条显眼的横幅：正在回放演练记录（进度 X / Y，
       [暂停/继续] [退出回放]），让用户明确知道当前是『过去时』」。
       ⚠️ 它不是装饰：回放期间实时 SSE 已被切断，横幅是页面上**唯一**能解释
          「为什么数字不跳了 / 为什么时间是旧的」的东西 ⇒ 必须比页头其它读数更显眼。 -->
  <section v-if="s.active" class="a-rb" role="status" aria-live="polite">
    <span class="a-rb-badge">回放</span>
    <div class="a-rb-main">
      <div class="a-rb-line">
        <b class="a-rb-t">正在回放演练记录</b>
        <span class="a-rb-prog">进度 <b>{{ fmtInt(s.cursor) }}</b> / {{ fmtInt(s.count) }}</span>
        <span class="a-rb-clock" :title="clockTitle">回放时钟 <b>{{ clockText }}</b></span>
        <span class="a-rb-speed" :title="`播放时钟：每 1 秒墙钟推进 ${s.speed} 秒日志时间；每帧上限按本批密度自适应（50–200 条，本批 ${s.maxPerFrame}）。回放 1:1，不做同类合并。`">{{ s.speed }}× 时钟 · 1:1 不合并 · 每帧 ≤{{ s.maxPerFrame }} 条<template v-if="s.effectiveRate"> · 实测 {{ fmtInt(s.effectiveRate) }} 条/秒</template></span>
        <span v-if="s.paused" class="a-rb-state is-paused">已暂停</span>
        <span v-else-if="s.done" class="a-rb-state is-done">已放完</span>
        <span v-else class="a-rb-state is-play">播放中</span>
      </div>
      <div
        class="a-rb-bar" role="progressbar"
        :aria-valuenow="pct" aria-valuemin="0" aria-valuemax="100"
        :aria-label="`回放进度 ${pct}%`"
      ><i :style="{ width: pct + '%' }"></i></div>
      <p v-if="truncated" class="a-rb-note">
        这批台账共 {{ fmtInt(s.total) }} 条，本次按上限载入 {{ fmtInt(s.count) }} 条（limit={{ fmtInt(s.limit) }}）—— 回放的是前一段。
      </p>
    </div>
    <div class="a-rb-act">
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
import { computed } from 'vue'
import {
  replaySession as s, stopReplaySession, toggleReplayPause,
} from '../../api/replay-session'

const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')

/** 进度按**条数**算（不按时间）：时间轴上可能有很长一段没有事件，按时间会看着「卡住」。 */
const pct = computed(() => {
  if (!s.count) return s.done ? 100 : 0
  return Math.max(0, Math.min(100, Math.round((s.cursor / s.count) * 100)))
})

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
.a-rb-bar {
  height: 4px;
  border-radius: 2px;
  background: var(--surface-2);
  overflow: hidden;
}
.a-rb-bar > i {
  display: block;
  height: 100%;
  background: var(--warning);
  transition: width 100ms linear;
}
.a-rb-note {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
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
</style>
