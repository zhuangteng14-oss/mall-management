<template>
  <!-- 「模拟日常」数据源开关（C3 块 7 收尾 ②）。
       ⚠️ 它只是**开关**：三页各自 `watch(demo.on)` 并重连（判断在页面里，因为「怎么断、怎么接」
          逐页不同 —— 指标/订单/日志三页的 `connect()` 各有一份）。 -->
  <div class="a-dm" :class="{ 'is-on': on }">
    <span class="a-dm-k">数据源</span>
    <div class="a-seg">
      <button
        type="button"
        class="a-seg-b"
        :class="{ on: !on }"
        :disabled="on === false"
        title="读后端三条真流（1 秒 1 帧）"
        @click="setDemoMode(false)"
      >正常</button>
      <button
        type="button"
        class="a-seg-b"
        :class="{ on }"
        :disabled="on === true"
        title="读本页内置的假数据：5–20 条/秒、总量 500 条、不落库、不进 MQ"
        @click="setDemoMode(true)"
      >模拟日常</button>
    </div>
    <!-- ⚠️ 开了就得**在页面上看得见**（用户 2026-10-15 的硬口径之一：纯前端、不入库 ⇒
         不入库这件事必须自报，不能让一屏假数据冒充真读数）。 -->
    <span class="a-dm-n">
      <template v-if="on">
        假数据 · 不入库 · 已放 <b>{{ demo.emitted }}</b> / {{ DEMO_TOTAL }} 条
        <span class="a-dm-w">（放完就安静，不是断线）</span>
      </template>
      <template v-else>后端真流</template>
    </span>
  </div>
</template>

<!--
  「模拟日常」开关（右上角那一枚）。
  ⚠️ **放在页头而不是外壳（`AdminShell.vue`）**：它切的是**这一页的数据源**，不是全局视图偏好；
     放进外壳就得让外壳知道「哪些路由属于可观测域」（多一层域名耦合），而这里三页各放一枚
     反而把「开关与它管的那条流」摆在一起。
  ⚠️ 三页共用**同一个模块级状态**（`api/demo-stream.js` 的 `demo`）：在日志页开、切到指标页
     仍然是开的 —— 它是「一个模式」，不是「一页一个开关」。
-->
<script setup>
import { computed } from 'vue'
import { DEMO_TOTAL, demo, setDemoMode } from '../../api/demo-stream'

const on = computed(() => demo.on === true)
</script>

<style scoped>
.a-dm {
  display: flex;
  align-items: center;
  gap: var(--s2);
  align-self: center;
  margin-left: var(--s3);
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
/* 开着时整枚染色（`--warning`）：这一屏不是真读数，颜色先把话说在前头。
   ⚠️ 用 `--warning`（不是 `-deep`）：它在浅色是 #966800、深色是 #f59e0b，两套主题下都是**文字色**
   —— 同 `AuditPage.vue:657` 那一行的用法。 */
.a-dm.is-on {
  color: var(--warning);
}
.a-dm-k {
  color: var(--ink-3);
}
.a-dm-n b {
  font-variant-numeric: tabular-nums;
}
.a-dm-w {
  color: var(--ink-3);
}

/* ── 分段控件：与 `views/admin/StatsPage.vue` 的 `.a-seg` 同款同值（不另立一套）── */
.a-seg {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r2);
}
.a-seg-b {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  background: none;
  border: 0;
  border-radius: var(--r1);
  padding: var(--s1) var(--s3);
  cursor: pointer;
  white-space: nowrap;
}
.a-seg-b:hover:not(:disabled) {
  color: var(--ink-2);
}
.a-seg-b.on {
  background: var(--surface);
  color: var(--brand-ink);
  font-weight: var(--fw-med);
}
.a-seg-b:disabled {
  cursor: default;
}
.a-seg-b.on:disabled {
  opacity: 1;
}
</style>
