<!-- src/components/chat/ChatBubble.vue —— 一条消息（用户 / 客服）2026-10-21
     · 客服回复逐字显示（打字机 24ms/字）；点气泡或「跳过」立刻显示全文。
     · `prefers-reduced-motion` 下直接出全文（不做动画）。
     · 订单 / 售后的结构化数据渲染成紧凑卡片，**不**用 v-html（回复里可能有商品名等库内文本）。
     · 建议项支持两种形状：字符串（再发一句话）与 `{label, routeKey}`（跳页，如「查看全部订单」）。 -->
<template>
  <div class="row" :class="message.role">
    <div class="bubble-wrap">
      <div
        v-if="message.role === 'bot'"
        class="bubble bot"
        :class="{ clickable: !done && !pending }"
        @click="skip"
      >
        <div v-if="pending && !message.text" class="dots" aria-label="正在输入">
          <i /><i /><i />
        </div>
        <p v-else class="txt">{{ shown }}<span v-if="!done" class="caret" /></p>

        <!-- 结构化数据：订单卡 -->
        <div v-if="showExtras && orderList.length" class="cards">
          <article v-for="o in orderList" :key="o.orderId" class="order-card">
            <div class="oc-top">
              <span class="oc-id">单号 {{ o.orderId }}</span>
              <span class="oc-state" :class="{ cancelled: o.cancelled }">{{ o.stateLabel }}</span>
            </div>
            <div class="oc-product">{{ o.product }}<template v-if="o.quantity > 1"> ×{{ o.quantity }}</template></div>
            <div class="oc-meta">
              <span class="oc-amount">¥{{ money(o.amount) }}</span>
              <span v-if="o.createTime" class="oc-time">{{ o.createTime }}</span>
            </div>
          </article>
          <p v-if="data.total > orderList.length" class="more">共 {{ data.total }} 笔，这里显示最近 {{ orderList.length }} 笔。</p>
        </div>

        <!-- 结构化数据：售后卡 -->
        <div v-if="showExtras && refund" class="refund-card">
          <div class="rc-row"><span>订单</span><b>{{ refund.orderId || '—' }}</b></div>
          <div class="rc-row"><span>状态</span><b class="rc-state">{{ refund.refundStatusLabel || '—' }}</b></div>
          <div class="rc-row"><span>退款金额</span><b class="rc-amount">¥{{ money(refund.refundAmount) }}</b></div>
          <div v-if="refund.reason" class="rc-row"><span>原因</span><b>{{ refund.reason }}</b></div>
          <div v-if="refund.applyAt" class="rc-row"><span>申请时间</span><b>{{ refund.applyAt }}</b></div>
          <div v-if="refund.handler" class="rc-row"><span>处理人</span><b>{{ refund.handler }}</b></div>
          <div v-if="refund.handledAt" class="rc-row"><span>处理时间</span><b>{{ refund.handledAt }}</b></div>
        </div>

        <!-- 唯一动作：一键带我去开店 -->
        <button
          v-if="showExtras && message.action && message.action.type === 'tour'"
          type="button"
          class="tour-btn"
          @click.stop="$emit('action', message.action)"
        >
          <span class="tb-icon" aria-hidden="true">↗</span>{{ message.action.label || '一键带我去开店' }}
        </button>

        <!-- 建议 -->
        <div v-if="showExtras && suggestions.length" class="chips">
          <button
            v-for="(s, i) in suggestions"
            :key="i"
            type="button"
            class="chip"
            @click.stop="$emit('suggestion', s)"
          >{{ labelOf(s) }}<span v-if="isRoute(s)" class="chip-go" aria-hidden="true">→</span></button>
        </div>

        <span class="sr-only" aria-live="polite">{{ done ? message.text : '' }}</span>
      </div>

      <div v-else class="bubble user">
        <p class="txt">{{ message.text }}</p>
      </div>

      <div v-if="message.failed" class="failed">
        没发出去
        <button type="button" class="retry" @click="$emit('retry', message)">重试</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/* ⚠️ 本仓 eslint-plugin-vue 8.7.1 的 `vue3-essential` 不带 setup 宏环境 ⇒ 不加这条
   `defineProps` / `defineEmits` 会被判 `no-undef`、`pnpm lint` 直接红。同款先例：
   `components/CashierDialog.vue` 的 `<script setup>` 块首。 */
/* eslint-disable no-undef */
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  message: { type: Object, required: true },
})
const emit = defineEmits(['action', 'suggestion', 'retry', 'typed'])

const CHAR_MS = 24
const reduced = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
  : false

const shown = ref('')
const done = ref(true)
let timer = null

const pending = computed(() => props.message.role === 'bot' && props.message.pending === true)
const data = computed(() => props.message.data || {})
const orderList = computed(() => (Array.isArray(data.value.orders) ? data.value.orders : []))
const refund = computed(() => {
  const d = data.value
  return d && d.refundStatusLabel ? d : null
})
const suggestions = computed(() => (Array.isArray(props.message.suggestions) ? props.message.suggestions : []))
const showExtras = computed(() => done.value && !pending.value)

function labelOf(s) {
  return typeof s === 'string' ? s : (s && s.label) || ''
}
function isRoute(s) {
  return !!(s && typeof s === 'object' && s.routeKey)
}
function money(n) {
  const v = Number(n)
  return Number.isFinite(v) ? v.toFixed(2) : '0.00'
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function finish() {
  stop()
  shown.value = props.message.text || ''
  done.value = true
  if (props.message.role === 'bot') emit('typed')
}

/** 用户消息直接显示；客服消息逐字。 */
function run() {
  stop()
  const text = props.message.text || ''
  if (props.message.role !== 'bot' || reduced || props.message.instant || !text) {
    shown.value = text
    done.value = true
    return
  }
  shown.value = ''
  done.value = false
  let i = 0
  timer = setInterval(() => {
    i += 2
    shown.value = text.slice(0, i)
    if (i >= text.length) finish()
  }, CHAR_MS)
}

function skip() {
  if (done.value || pending.value) return
  finish()
}

watch(() => [props.message.text, props.message.role], run, { immediate: true })
onBeforeUnmount(stop)
</script>

<style scoped>
.row { display: flex; margin: 10px 0; }
.row.user { justify-content: flex-end; }
.bubble-wrap { max-width: 86%; display: flex; flex-direction: column; }
.row.user .bubble-wrap { align-items: flex-end; }

.bubble {
  border: 1px solid var(--line, #dde2e5);
  border-radius: var(--r3, 10px);
  padding: 10px 12px;
  font-size: var(--fs-sm, 15px);
  line-height: 1.6;
  background: var(--surface, #fff);
  color: var(--ink, #1d2b26);
  overflow-wrap: anywhere;
}
.bubble.bot { border-bottom-left-radius: 3px; }
.bubble.user {
  background: var(--brand, #3b7966);
  border-color: var(--brand, #3b7966);
  color: #fff;
  border-bottom-right-radius: 3px;
}
.bubble.clickable { cursor: pointer; }
.txt { margin: 0; white-space: pre-wrap; }
.caret {
  display: inline-block; width: 2px; height: 1em; margin-left: 2px;
  background: currentColor; vertical-align: -2px; animation: blink 1s steps(2) infinite;
}
@keyframes blink { 50% { opacity: 0; } }

.dots { display: inline-flex; gap: 4px; padding: 4px 2px; }
.dots i {
  width: 6px; height: 6px; border-radius: 50%; background: var(--brand, #3b7966);
  opacity: .35; animation: dot 1.1s infinite ease-in-out;
}
.dots i:nth-child(2) { animation-delay: .15s; }
.dots i:nth-child(3) { animation-delay: .3s; }
@keyframes dot { 0%, 80%, 100% { opacity: .3; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }

/* ── 订单卡 ── */
.cards { margin-top: 8px; display: grid; gap: 6px; }
.order-card {
  border: 1px solid var(--line, #dde2e5);
  background: var(--surface-2, #f2f5f7);
  border-radius: var(--r2, 8px);
  padding: 8px 10px;
}
.oc-top { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.oc-id { font-size: var(--fs-xs, 12px); color: var(--muted, #67736e); font-variant-numeric: tabular-nums; }
.oc-state { font-size: var(--fs-xs, 12px); color: var(--brand-ink, #275e4e); font-weight: 600; }
.oc-state.cancelled { color: var(--muted, #67736e); }
.oc-product { margin-top: 3px; font-weight: 500; }
.oc-meta { margin-top: 3px; display: flex; justify-content: space-between; gap: 8px; font-size: var(--fs-xs, 12px); color: var(--muted, #67736e); }
.oc-amount { color: var(--accent, #b2422f); font-weight: 600; font-variant-numeric: tabular-nums; }
.more { margin: 2px 2px 0; font-size: var(--fs-xs, 12px); color: var(--muted, #67736e); }

/* ── 售后卡 ── */
.refund-card {
  margin-top: 8px; padding: 8px 10px;
  border: 1px solid var(--line, #dde2e5); border-radius: var(--r2, 8px);
  background: var(--surface-2, #f2f5f7);
  display: grid; gap: 4px;
}
.rc-row { display: flex; justify-content: space-between; gap: 12px; font-size: var(--fs-xs, 12px); }
.rc-row span { color: var(--muted, #67736e); flex: none; }
.rc-row b { font-weight: 600; text-align: right; }
.rc-state { color: var(--brand-ink, #275e4e); }
.rc-amount { color: var(--accent, #b2422f); font-variant-numeric: tabular-nums; }

/* ── 动作 / 建议 ── */
.tour-btn {
  margin-top: 10px; width: 100%;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  border: 1px solid var(--brand, #3b7966);
  background: var(--brand, #3b7966); color: #fff;
  border-radius: var(--r2, 8px); padding: 9px 12px;
  font: inherit; font-size: var(--fs-sm, 15px); font-weight: 600; cursor: pointer;
  transition: background .15s ease;
}
.tour-btn:hover { background: var(--brand-ink, #275e4e); }
.tb-icon { font-size: 13px; opacity: .9; }

.chips { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  border: 1px solid var(--line, #dde2e5);
  background: var(--surface, #fff);
  color: var(--brand-ink, #275e4e);
  border-radius: 999px; padding: 4px 11px;
  font: inherit; font-size: var(--fs-xs, 12px); cursor: pointer;
  transition: background .15s ease, border-color .15s ease;
}
.chip:hover { background: var(--surface-2, #f2f5f7); border-color: var(--brand, #3b7966); }
.chip-go { margin-left: 3px; opacity: .7; }

.failed { margin-top: 4px; font-size: var(--fs-xs, 12px); color: var(--accent, #b2422f); }
.retry {
  margin-left: 6px; border: 0; background: none; padding: 0;
  color: var(--brand-ink, #275e4e); font: inherit; text-decoration: underline; cursor: pointer;
}

.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

@media (prefers-reduced-motion: reduce) {
  .dots i, .caret { animation: none; }
}
</style>
