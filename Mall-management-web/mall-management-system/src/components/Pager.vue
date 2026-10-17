<!-- eslint-disable vue/multi-word-component-names -- 文件名是**用户裁决指定**的（块 7 批文 Q6「抽共享 `Pager.vue`」），不是疏漏。`plugin:vue/vue3-essential` 的这条规则要求组件名多词，本仓其余组件（`KpiCard` / `LineChart` / `AttackConsole`…）都天然满足；本组件是管理端**唯一**的分页控件，语义上「Pager」已经是一个完整的词，硬凑第二个词（如 `PagerBar`）只会让调用点更难认。同款处置见本文件 `<script setup>` 里的 `no-undef` 那条（同样是为一个已知且正当的规则例外写明理由）。 -->
<template>
  <!--
    分页控件（C3 块 7 · commit 6）—— **管理端统一的那一个**。

    ⚠️ 判据：**分页走后端**（`offset` / `limit` / `total`），本组件**不认识数据、也不切数组**。
      它是纯受控件：拿 `total/limit/offset` 三个数画控件，用户点了就 `emit('change', {limit, offset})`
      —— 由**调用方**去发请求。理由与 `WeightSlider.vue` 同款：组件里存一份「当前页」的副本，
      就会和调用方手上那份走岔（请求失败、并发两次点击、外部重置筛选……都会分叉）。

    ⚠️ 视觉语言**照稿**（`GoodsSelect.vue:709-739` 的 `.pager` / `.pg` / `.pg.on` 逐字同源
      —— 那一处是稿里画出来的分页条，只是当时后端没有分页端点、只能 `disabled`）。
      本组件是它的**可用版**：同一个 class 名、同一组 token，不另立一套样式。

    ⚠️ **每页条数由调用方定，不一刀切**（用户 2026-10-14 口径）：卡片列表 20、审计日志 100、
      评论列表 50 —— 卡片大/行窄的排布不同，同一个数字必然有一边难看。故 `sizes` 是**入参**，
      且**允许为空**（空 = 不给用户改，只按调用方给的 `limit` 翻页）。
  -->
  <nav v-if="total > 0" class="pager" :aria-label="label || '分页'">
    <span class="pager-sum">
      <template v-if="label">{{ label }} · </template>共 <b>{{ fmtInt(total) }}</b> {{ unit }}
    </span>

    <label v-if="sizes.length" class="pager-size">
      <span>每页</span>
      <select :value="limit" :disabled="disabled" @change="onSize">
        <option v-for="s in sizes" :key="s" :value="s">{{ s }} {{ unit }}</option>
      </select>
    </label>

    <div class="pager-btns">
      <button type="button" class="pg" :disabled="disabled || page <= 1" @click="go(1)">首页</button>
      <button type="button" class="pg" :disabled="disabled || page <= 1" @click="go(page - 1)">上一页</button>
      <template v-for="(t, i) in tokens" :key="i">
        <span v-if="t === '…'" class="pg-gap" aria-hidden="true">…</span>
        <button
          v-else
          type="button"
          class="pg"
          :class="{ on: t === page }"
          :disabled="disabled"
          :aria-current="t === page ? 'page' : undefined"
          @click="go(t)"
        >{{ t }}</button>
      </template>
      <button type="button" class="pg" :disabled="disabled || page >= pageCount" @click="go(page + 1)">下一页</button>
      <button type="button" class="pg" :disabled="disabled || page >= pageCount" @click="go(pageCount)">末页</button>
    </div>
  </nav>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `ui/KpiCard.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'

const props = defineProps({
  /** 命中总条数（后端 `total`）。**必需** —— 没有它算不出页数。 */
  total: { type: Number, required: true },
  /** 每页条数（后端 `limit`）。**必需**，且必须与请求里发出去的那个数**逐字相同**。 */
  limit: { type: Number, required: true },
  /** 已跳过条数（后端 `offset`）。⚠️ 是**条**不是**页** —— 本组件自己除。 */
  offset: { type: Number, default: 0 },
  /** 可选的每页条数档位（如 `[20, 50, 100]`）。**空数组 = 不给用户改**。 */
  sizes: { type: Array, default: () => [] },
  /** 计数单位（`'条'` / `'批'` / `'行'`）。 */
  unit: { type: String, default: '条' },
  /** 可选的左侧说明（如 `'演练记录'`）。 */
  label: { type: String, default: '' },
  /** 请求进行中：整条置灰、点不动（避免并发两次翻页）。 */
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['change'])

/** 总页数。⚠️ `total > 0` 才渲染本组件（见模板 `v-if`）⇒ 这里最小也是 1，不会出现 0 页。 */
const pageCount = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.limit))))

/**
 * 当前页。⚠️ **由 `offset/limit` 反算、并夹到 `[1, pageCount]`**，不存内部状态：
 *   调用方把 `offset` 拨到界外（如筛完之后总条数变少）时，这里会自动落到最后一页，
 *   而不是显示一个「第 9 页 / 共 3 页」的鬼页。
 */
const page = computed(() =>
  Math.min(pageCount.value, Math.max(1, Math.floor(props.offset / Math.max(1, props.limit)) + 1))
)

/**
 * 页码窗口：`1 … 4 5 6 … 10` 这种。⚠️ 类目少（≤7 页）就全列，不插省略号
 *   —— 7 个页码横排本来就装得下，插一个 `…` 反而要用户多点一次。
 */
const tokens = computed(() => {
  const last = pageCount.value
  const cur = page.value
  if (last <= 7) return Array.from({ length: last }, (_, i) => i + 1)
  let s = Math.max(2, cur - 1)
  let e = Math.min(last - 1, cur + 1)
  if (cur <= 4) { s = 2; e = 5 }
  if (cur >= last - 3) { s = last - 4; e = last - 1 }
  const out = [1]
  if (s > 2) out.push('…')
  for (let p = s; p <= e; p++) out.push(p)
  if (e < last - 1) out.push('…')
  out.push(last)
  return out
})

const fmtInt = (v) => Number(v || 0).toLocaleString('zh-CN')

/** 翻到第 `p` 页。⚠️ 同一页**不重复 emit**（点了「当前页」不该再发一次请求）。 */
function go(p) {
  const next = Math.min(pageCount.value, Math.max(1, Number(p) || 1))
  if (next === page.value) return
  emit('change', { limit: props.limit, offset: (next - 1) * props.limit })
}

/**
 * 改每页条数。⚠️ **保住「第一条可见记录」的位置**，不是保住「第几页」：
 *   50 条/页 的第 3 页（第 101 条起）切成 20 条/页，应当落在第 6 页（第 101 条起）附近
 *   —— 换算写成 `floor(offset/limit)*newLimit` 比 `(page-1)*newLimit` 更贴这个意图。
 */
function onSize(e) {
  const newLimit = Number(e.target.value) || props.limit
  if (newLimit === props.limit) return
  const firstIdx = Math.floor(props.offset / Math.max(1, props.limit)) * props.limit
  const newOffset = Math.floor(firstIdx / newLimit) * newLimit
  emit('change', { limit: newLimit, offset: newOffset })
}
</script>

<style scoped>
/* ── 分页条（逐字照 `GoodsSelect.vue:709-739`，只把只读态换成可用态）────────────── */
.pager {
  margin-top: var(--s5);
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s2);
  font-size: var(--fs-sm);
  color: var(--ink-3);
}
.pager-sum {
  margin-right: auto;
  font-variant-numeric: tabular-nums;
}
.pager-sum b {
  color: var(--ink);
  font-weight: var(--fw-semi);
}
.pager-size {
  display: inline-flex;
  align-items: center;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.pager-size select {
  font: inherit;
  font-size: var(--fs-xs);
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r2);
  padding: var(--s1) var(--s2);
}
.pager-btns {
  display: flex;
  align-items: center;
  gap: var(--s2);
}
.pg {
  min-width: 40px;
  height: 36px;
  padding: 0 var(--s3);
  border: 1px solid transparent;
  border-radius: var(--r2);
  color: var(--ink-2);
  background: none;
  font: inherit;
  font-size: var(--fs-sm);
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.pg:hover:not([disabled]) {
  background: var(--surface-2);
}
.pg.on {
  color: var(--brand-ink);
  font-weight: var(--fw-semi);
  background: var(--brand-tint);
  border-color: var(--brand-soft);
}
.pg[disabled] {
  color: var(--ink-3);
  cursor: not-allowed;
}
.pg[disabled]:hover {
  background: none;
}
/* 省略号与页码同宽高，免得窗口左右跳一格。 */
.pg-gap {
  min-width: 24px;
  text-align: center;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

/* 窄屏：计数字与「每页」先靠边，按钮组换行居中。 */
@media (max-width: 720px) {
  .pager-sum { margin-right: 0; width: 100%; text-align: center; }
  .pager-btns { width: 100%; justify-content: center; }
}
</style>
