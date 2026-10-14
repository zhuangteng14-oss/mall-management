<template>
  <div class="ws" :class="{ 'ws--off': !on }">
    <div class="ws-head">
      <span class="ws-dot" aria-hidden="true"></span>
      <span class="ws-name">{{ label }}</span>
      <span class="ws-val">{{ on ? modelValue + '%' : '未勾选' }}</span>
    </div>

    <input
      class="ws-range"
      type="range"
      :min="0"
      :max="max"
      :step="step"
      :value="modelValue"
      :disabled="disabled"
      :aria-label="label + ' 权重'"
      @input="emitValue"
    />

    <p v-if="hint" class="ws-hint">{{ hint }}</p>
    <p v-if="need" class="ws-need">人工前提：{{ need }}</p>
  </div>
</template>

<!--
  权重滑条（块 7 · commit 5）—— 攻击模拟器「自定义编排」里的一行。

  【它只管一件事：把一根 0..max 的滑条画成「这个场景参不参与、占多少权重」】
  其余（勾了哪些、权重合计多少、能不能开跑）全在 `views/admin/observe/AttackConsole.vue` 里
  —— 那是**跨行**的判断，一行滑条看不见别的行。

  【`0% = 没勾` 是后端定的，不是本组件的口味】
  `AttackEngine.parse` 把**权重为 0 的场景直接剔除**（`api/observe.js#runAttack` 的注释逐字：
  「权重为 0 的场景**等于没勾**，别用 0 表达『跑一下试试』」）⇒ 本组件把 `0` 画成
  「未勾选」而不是「0%」：两者在后端是同一条分支，画成两个样子会让用户以为「0 也跑一点」。
  ⚠️ 也**不做**「复选框 + 滑条」两件套：勾选框与滑条是同一份状态的两种写法，
     一旦分开就要处理「勾着但权重 0」这种自相矛盾的中间态。

  【样式照 `components/ui/KpiCard.vue` 的口径】色值 / 字号 / 间距一律走 `tokens.css` 的
  变量，不写字面值；`.ws--off` 只是把整行降一档灰度 —— 不隐藏，因为「没勾的那个」也要能被
  看见并拖回来。
-->
<script setup>
/* eslint-disable no-undef */ /* 理由见 `../charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'

const props = defineProps({
  /** 场景中文名（如 `'秒杀抢购'`）。必填。 */
  label: { type: String, required: true },
  /** 权重。**0 = 没勾**（见块首），上限由 `max` 给。 */
  modelValue: { type: Number, default: 0 },
  /** 滑条下面那行小字：这个场景同时演示哪几条防护。 */
  hint: { type: String, default: '' },
  /** 再下面那行：跑出效果所需的**人工前提**（profile / 开关 / 等 job 时钟）。 */
  need: { type: String, default: '' },
  /** 权重上限（后端按**比例**分线程，故 100 只是「合计凑整」的惯例，不是硬上限）。 */
  max: { type: Number, default: 100 },
  /** 步长。取 5 —— 答辩现场用鼠标拖得动、又能拖到 0（`step` 为 1 时拖到「正好 0」会很难）。 */
  step: { type: Number, default: 5 },
  /** 演练跑着的时候禁掉：跑动中改权重不会影响已经在跑的那一轮（线程计划开跑前就定好了）。 */
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])

/** `0` 与「没勾」是同一件事（见块首）⇒ 亮/暗只看「有没有大于 0」。 */
const on = computed(() => Number(props.modelValue) > 0)

/**
 * `input` 事件 → `update:modelValue`。
 * ⚠️ 用 `:value` + `@input` 而不是 `v-model`：本组件要**同时**支持默认 `v-model`
 * （父级 `:model-value` + `@update:model-value`）与「父级只读地传一个值下来」两种写法，
 * 手写这一对是唯一能保证「值只从 props 来、只从 emit 走」的写法（组件内不留第二份状态）。
 * ⚠️ 兜底成 0（不是 NaN）：`<input type="range">` 理论上只会给出区间内的数字，
 * 但 `Number('')` = 0 而 `Number('abc')` = NaN —— 让 NaN 漏进 props 会一路传到
 * `JSON.stringify` 变成 `null`，后端按「没给」处理，于是**页面显示的和实际跑的不一致**。
 */
function emitValue(e) {
  const n = Number(e.target.value)
  emit('update:modelValue', Number.isFinite(n) ? n : 0)
}
</script>

<style scoped>
.ws {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
  padding: var(--s2) var(--s3);
  border-radius: var(--r2);
  border: 1px solid transparent;
}
/* 没勾的整行降一档灰度 —— 不隐藏（见块首最后一段） */
.ws--off {
  background: var(--surface-2);
  border-color: var(--line);
}
.ws-head {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  font-size: var(--fs-sm);
  color: var(--ink);
}
/* 一个纯色小点 = 「这条在不在参与」的第二种写法（颜色之外还有实心/空心之差） */
.ws-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand-ink);
  align-self: center;
}
.ws--off .ws-dot {
  background: transparent;
  border: 1px solid var(--line-2);
}
.ws-name {
  font-weight: var(--fw-med);
}
.ws-val {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  color: var(--brand-ink);
}
.ws--off .ws-val {
  color: var(--ink-3);
}

.ws-range {
  width: 100%;
  /* ⚠️ 只声明一次 `accent-color`：本仓房规禁重复声明（见 `KpiCard.vue` 的那条记账）。 */
  accent-color: var(--brand-ink);
}
.ws-range:disabled {
  cursor: default;
  opacity: 0.6;
}

.ws-hint,
.ws-need {
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.ws-need {
  color: var(--warning);
}
</style>
