<template>
  <!-- KPI 卡（可视化段 commit 1）：一个标签 + 一个大数字 + 一行环比。 -->
  <div class="kpi">
    <div class="kpi-k">
      <IconGlyph v-if="glyph" :name="glyph" tone="muted" />
      <span>{{ label }}</span>
    </div>

    <div class="kpi-v">
      <span v-if="loading" class="kpi-skel" aria-hidden="true" />
      <template v-else>
        <span class="kpi-n" :style="numStyle">{{ value }}</span>
        <span v-if="unit" class="kpi-u">{{ unit }}</span>
      </template>
    </div>

    <div class="kpi-d">
      <template v-if="delta !== null && delta !== undefined && !loading">
        <span class="kpi-arrow" :class="'kpi-arrow--' + tone">{{ arrow }}{{ deltaText || Math.abs(delta) + '%' }}</span>
        <span class="kpi-dl">{{ deltaLabel }}</span>
      </template>
      <template v-else-if="hint">{{ hint }}</template>
      <template v-else>&nbsp;</template>
    </div>
  </div>
</template>

<script setup>
/* eslint-disable no-undef */ /* 理由见 `../charts/BaseChart.vue` 同款注释（eslint-plugin-vue 8.7.1 缺宏环境） */
import { computed } from 'vue'
import IconGlyph from './IconGlyph.vue'

const props = defineProps({
  /** 指标名（如 `'成交额（GMV）'`）。必填。 */
  label: { type: String, required: true },
  /** 大数字。**已格式化好的字符串**（`'1,284'` / `'96.4'`）—— 组件不做数字格式化，
   *  因为「千分位 / 保留几位 / 是否缩写」是三端各不相同的显示口径，属于调用方的决定。 */
  value: { type: [String, Number], required: true },
  /** 单位（`'元'` / `'%'`）。跟在大数字后面，小一号。 */
  unit: { type: String, default: '' },
  /** 左上角的字形名（`glyphs.js` 的键）。不传就不画。 */
  glyph: { type: String, default: '' },
  /** 环比百分数（**带符号**：`8.2` = 涨 8.2%，`-0.6` = 跌 0.6%）。`null` = 没有环比。 */
  delta: { type: Number, default: null },
  /** 环比文案的右半句（如 `'较昨日'` / `'较上周'`）。 */
  deltaLabel: { type: String, default: '较昨日' },
  /** 环比文案的左半句。不传则自动用 `▲`/`▼` + `|delta|%`（如改 `'3ms'` 这种绝对量差异）。 */
  deltaText: { type: String, default: '' },
  /**
   * ⚠️ **该指标「涨了是坏事」时置 true**（典型：退款率、异常数、锁等待）。
   * 只影响**配色**（涨=红 / 跌=绿），不影响箭头方向。
   */
  inverse: { type: Boolean, default: false },
  /** 没有环比时的那行说明（如 `'其中 2 条待处置'`）。也没有就留空占位。 */
  hint: { type: String, default: '' },
  /** 加载态：大数字处显示脉冲骨架。 */
  loading: { type: Boolean, default: false },
})

/**
 * ⚠️ 2026-10-11（用户裁决 ⑤「数字越位」）：把**大数字的字符数**交给 CSS 当分母。
 *
 * 为什么不是「按长度分档」（`len>9 → --fs-lg` 那种）：档位是**离散**的，而卡宽是**连续**的
 * （同一个看板在 4 列与 5 列下卡宽差 20%+），两种情形要各调一套阈值 ⇒ 必然有一边不准。
 * 交给 CSS 的 `cqw`（见 `<style>` 里 `.kpi-n`）就只剩一条式子：`字号 = 140cqw / 字符数`
 * —— 卡宽与字符数**同时**进了同一个算式。
 *
 * ⚠️ `|| 1`：空串（`value === ''`）时长度为 0，会让 CSS 里除法分母为 0 ⇒ 整条 `font-size`
 *    非法、回落成上一档声明。给 1 是让算式有个**有定义**的结果（空串本来也没宽度）。
 */
const numStyle = computed(() => ({ '--kpi-chars': String(props.value ?? '').length || 1 }))

/** 箭头方向只看数值符号。 */
const arrow = computed(() => (props.delta > 0 ? '▲ ' : props.delta < 0 ? '▼ ' : ''))
/** 配色看的是「这算好还是坏」——`inverse` 指标在这个点上与常规相反。 */
const tone = computed(() => {
  if (!props.delta) return 'flat'
  const raising = props.delta > 0
  const good = props.inverse ? !raising : raising
  return good ? 'good' : 'bad'
})
</script>

<style scoped>
.kpi {
  display: flex;
  flex-direction: column;
  /* ⚠️ 2026-10-11（用户裁决 ⑥「去 AI 化」）：三条间距/圆角**照稿收一档**。依据 = `_work/audit-density.mjs`
     的并排实测（同一批元素，稿 vs 实现，都取计算值）—— 稿 `丁-运营蓝.html:177-183`
     `.kpi{padding:13px 14px; border-radius:var(--r3)}`、行距靠 `.v{margin-top:4px}`/`.d{margin-top:2px}`；
     实现改前是 `padding:16px 24px`（`--s4 --s5`）/ `--r2`（8px）/ 行间 `--s2`（8px×2）⇒ 卡片实测 145px 高，
     稿 105px。取**最近的 token**（`看板规范.md:9`「颜色/字号/间距一律走 token」，故不写 13/14 这种字面值；
     `--s3`=12 vs 稿 13、`--s4`=16 vs 稿 14，各差 ≤2px）。 */
  gap: var(--s1);
  padding: var(--s3) var(--s4);
  border: 1px solid var(--line);
  border-radius: var(--r3);
  background: var(--surface);
  /* ⚠️ 2026-10-11（用户裁决 ⑤「数字越位」）：本元素建**尺寸容器** ⇒ 下面 `.kpi-n` 能用 `cqw`
     （= 本卡**内容盒**宽的 1%）按「这张卡有多宽」现算字号。改前没有它 ⇒ 一个 36px 的死字号：
     实测（`_work/diag-5.mjs`，VM 真数据）`10,100,856.00` 在 211px 卡里画出 **281px** 宽、
     卡外 95px、连 `420,869.00` 也出卡 30px —— 用户所见「被卡片截断」（截断的是**后一张卡**
     的底色盖上去的，数字本身没被 `overflow` 裁）。
     ⚠️ `inline-size` 这条 containment **只**让「宽」不再由内容决定（这正是要的：宽由上面
     `repeat(auto-fit, minmax(180px, 1fr))` 的轨道给），**高**仍随内容 ⇒ 卡片高度不变。 */
  container-type: inline-size;
}
.kpi-k {
  display: flex;
  align-items: center;
  gap: var(--s2);
  /* ⑥：`--fs-sm`（15px）→ `--fs-xs`（12px）。稿 `.kpi .k` = 11px（`:181`），最近的 token 就是 `--fs-xs`。
     ⚠️ 与 ⑤ 无关：标签是**固定字**，不参与下面那条按字符数算的算式。 */
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.kpi-v {
  display: flex;
  align-items: baseline;
  gap: var(--s1);
  /* ⚠️ 2026-10-11（⑤ 的兜底档）：允许「数字 / 单位」换行 —— 见 `.kpi-n` 末条注释。 */
  flex-wrap: wrap;
  min-height: calc(var(--fs-lg) * 1.1); /* 加载态与常态等高 ⇒ 数据回来时卡片不跳（⑥ 后随数字上限一起落到 `--fs-lg`） */
}
.kpi-n {
  font-family: var(--font-mono);
  /* ⚠️ 房规：数字列一律 tabular-nums（`tokens.css` 「用法规矩」第 6 条）——
     否则多个 KPI 并排时，数字宽度不同会让「元」字左右错位。 */
  font-variant-numeric: tabular-nums;
  /* 兜底档（不支持容器查询时就是它）：`--fs-lg` = 24px = **稿 `.kpi .v` 的字号**（`丁-运营蓝.html:182`）。
     下面 `@supports` 里那条生效时会盖掉它 —— 用 `@supports` 而不是「写两条、后者失效回落前者」，
     是因为本仓房规禁**重复声明**（`UserCenter.vue` 里照稿落 `width` 时记过这条）。
     ⚠️ ⑥ 把上限从 `--fs-2xl`（36px）**降到 `--fs-lg`（24px）** —— 36px 不是「数字档」而是
     **页标题档**（`tokens.css:65` 原话「子页面独有…目前只有用户中心的页标题用」），KPI 数字比
     本页自己的标题还大正是「一眼 AI 味」的来源；稿给的就是 24px/600。 */
  font-size: var(--fs-lg);
  /* ⑥：字重 500→600、加 -.02em 字距，两条都照稿 `.kpi .v`（`:182`）—— 字号小了以后
     600 才撑得住「数字是主角」；字距收紧是让 6 位数以上不至于散开。 */
  font-weight: var(--fw-semi);
  letter-spacing: -.02em;
  line-height: 1.1;
  color: var(--ink);
  /* ⚠️ 最后一道保险：字号已经压到 `--fs-xs` 还装不下的**极端值**（如未来某列 `decimal(20,6)`）
     改为**换行**而不是画到卡外 —— `min-width:0` 是让 flex 子项肯让出宽度（默认 `auto`
     会拒绝缩到 min-content 之下），`overflow-wrap:anywhere` 是让「一长串没有断点的数字」
     本身可断。⚠️ 正常长度**一个像素都不受影响**（这两条只在装不下时才起作用）。 */
  min-width: 0;
  overflow-wrap: anywhere;
}
/* ⚠️⚠️ ⑤ 的正主：**「自适应」的算式**（2026-10-11，用户裁决「数字自适应、不溢出」）。
   依据全是实测（`_work/diag-5.mjs` / `_work/diag-5-admin.png`）：mono 字体下**每字符 ≈ 0.6em**
   —— 281px ÷ 13 字符 ÷ 36px = 0.6007。
   ⇒ 字号 F 要装下 n 个字符，需 `n × 0.6F ≤ 可用宽`。可用宽 ≈ 内容盒宽 − 单位（`人日` 最宽
   = 30px）− 间隙（`--s1`）≈ 内容盒宽的 **90%** 再减一点余量。把内容盒宽记作 100cqw，则
   `F ≤ 0.9 × 100cqw / (0.6n) = 150cqw / n`；**取 140** 留出余量（长数字要留，宁可小 7%）。
   上下夹：上限 `--fs-lg`（24px，⑥ 后 = 稿值）⇒ 短数字（`24` / `—` / `6`）就停在 24px 不再往上长；
   下限 `--fs-xs`（12px）⇒ 再长也不把字缩到读不出来，交给上面那两条兜底。
   ⚠️ `var(--kpi-chars, 8)` 的 8 = 兜底分母（模板里 `:style` 没绑上时的形态）。 */
@supports (container-type: inline-size) {
  .kpi-n {
    font-size: clamp(var(--fs-xs), calc(140cqw / var(--kpi-chars, 8)), var(--fs-lg));
  }
}
.kpi-u {
  /* ⑥：随标签一起收到 `--fs-xs`（单位跟在大数字后面，字比数字大一档会显得浮）。 */
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.kpi-d {
  display: flex;
  align-items: baseline;
  gap: var(--s1);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.kpi-arrow--good { color: var(--success); }
.kpi-arrow--bad { color: var(--danger); }
.kpi-arrow--flat { color: var(--ink-3); }
.kpi-dl { color: var(--ink-3); }

.kpi-skel {
  display: inline-block;
  width: 5.5em;
  /* ⑥：随数字上限落到 `--fs-lg`（0.72em 是「数字的实高」比例，保持同一比例即可）。 */
  height: calc(var(--fs-lg) * 0.72);
  border-radius: var(--r1);
  background: var(--surface-3);
  animation: kpi-pulse 1.2s ease-in-out infinite;
}
@keyframes kpi-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
@media (prefers-reduced-motion: reduce) {
  .kpi-skel { animation: none; }
}
</style>
