<template>
  <main class="pd-page">
    <!-- 首屏：左侧大字 + 右侧三步序列（这里确实是顺序流程，才用编号） -->
    <section class="pd-hero">
      <div class="pd-hero-tx">
        <p class="pd-kicker">商家端 · 商品发布</p>
        <h1 class="pd-title">把折扣，<br />写进价格里。</h1>
        <p class="pd-lede">
          售价是底。折扣模板负责回答买家两个问题：比原来便宜了多少，
          会员还能不能再省一点。下面这版把这三层价格摊开给你看。
        </p>
      </div>

      <ol class="pd-steps">
        <li v-for="step in steps" :key="step.n">
          <span class="pd-step-n">{{ step.n }}</span>
          <div>
            <b>{{ step.title }}</b>
            <p>{{ step.desc }}</p>
          </div>
        </li>
      </ol>
    </section>

    <!-- 编辑区：7 / 5 非对称；左边表单，右边买家视角价格卡 -->
    <section class="pd-editor">
      <div class="pd-form">
        <header class="pd-form-head">
          <h2>价格与促销</h2>
          <span>{{ sourceLabel }}</span>
        </header>

        <div class="pd-field">
          <label class="pd-label" for="pd-sale">销售价</label>
          <div class="pd-money-input">
            <span>¥</span>
            <input id="pd-sale" v-model.number="sale" type="number" min="0" step="0.01" />
          </div>
          <p class="pd-hint">买家实际支付的基准价；会员折扣在此价格上计算。</p>
        </div>

        <div class="pd-field">
          <span class="pd-label">折扣模板</span>
          <div class="pd-tpl-row">
            <div v-if="tpl" class="pd-tpl-selected">
              <b>{{ tpl.name }}</b>
              <span>{{ tpl.tag }}</span>
            </div>
            <div v-else class="pd-tpl-empty">还没有选择模板</div>
            <button type="button" class="pd-btn pd-btn--line" @click="drawer = true">
              选折扣模板
            </button>
          </div>
          <p class="pd-hint">{{ tpl ? tpl.desc : '不选模板时，买家只看到销售价。' }}</p>
          <p v-if="previewMsg" class="pd-hint pd-hint--warn">{{ previewMsg }}</p>
        </div>

        <div class="pd-field pd-field--split">
          <div>
            <span class="pd-label">划线价（自动反算）</span>
            <p class="pd-readonly">{{ original == null ? '—' : '¥' + money(original) }}</p>
          </div>
          <div>
            <span class="pd-label">活动时间</span>
            <p class="pd-readonly pd-readonly--sm">{{ validWindow }}</p>
          </div>
        </div>

        <div class="pd-field">
          <div class="pd-toggle-row">
            <div>
              <span class="pd-label">会员再加一档</span>
              <p class="pd-hint">开启后，VIP 买家看到独立会员价，与模板折扣叠加。</p>
            </div>
            <button
              type="button"
              class="pd-switch"
              :class="{ on: memberOn }"
              role="switch"
              :aria-checked="memberOn"
              @click="memberOn = !memberOn"
            >
              <i></i>
            </button>
          </div>
        </div>

        <footer class="pd-form-foot">
          <button type="button" class="pd-btn pd-btn--ghost" @click="notify('静态预览：草稿不会保存')">
            保存草稿
          </button>
          <button type="button" class="pd-btn pd-btn--solid" @click="notify('静态预览：不会提交审核')">
            提交审核
          </button>
        </footer>
      </div>

      <aside class="pd-preview">
        <div class="pd-preview-head">
          <span>买家看到的价格</span>
          <span class="pd-preview-dot"><i></i>实时联动</span>
        </div>

        <div class="pd-goods">
          <div class="pd-goods-img"><PvIcon name="headphones" :size="30" /></div>
          <div>
            <b>无线降噪耳机 Air Pro</b>
            <p>示例商品 · 默认规格</p>
          </div>
        </div>

        <div v-if="tpl && tpl.id !== 'none'" class="pd-price">
          <p class="pd-was">原价 <s>¥{{ money(original) }}</s></p>
          <p class="pd-now"><span>¥</span>{{ money(sale) }}</p>
          <p v-if="memberOn" class="pd-member">
            会员价 <b>¥{{ money(memberPrice) }}</b>
            <em>再省 ¥{{ money(memberSave) }}</em>
          </p>
        </div>
        <div v-else class="pd-price-empty">
          <b>未设置折扣模板</b>
          <p>这里会出现划线原价、实际售价与会员省下的金额。选择「九折」或「八五折」即可预览。</p>
          <button type="button" class="pd-btn pd-btn--line" @click="drawer = true">去选模板</button>
        </div>

        <div class="pd-ticket-line"><span></span></div>

        <dl class="pd-breakdown">
          <div><dt>销售价</dt><dd>¥{{ money(sale) }}</dd></div>
          <div><dt>折扣模板</dt><dd>{{ tpl ? tpl.name : '未选择' }}</dd></div>
          <div><dt>划线价</dt><dd>{{ original == null ? '—' : '¥' + money(original) }}</dd></div>
          <div v-if="memberOn"><dt>会员折扣</dt><dd>全场 9 折</dd></div>
        </dl>

        <button type="button" class="pd-buy" @click="notify('静态预览：不会加入购物车')">
          加入购物车
        </button>
        <p class="pd-preview-note">{{ dataSource === 'live'
          ? '划线价由服务端按所选模板反算；会员价按 VIP 9 折展示，最终以订单为准。'
          : '价格均为示例数据，仅用于确认版式与信息层级。' }}</p>
      </aside>
    </section>

    <!-- 价格语义：整幅横向词条带，与上面的左右分栏刻意不同 -->
    <section class="pd-legend">
      <article v-for="item in legend" :key="item.term">
        <span class="pd-legend-term">{{ item.term }}</span>
        <b>{{ item.value }}</b>
        <p>{{ item.desc }}</p>
      </article>
    </section>

    <!-- 发布前检查：行式清单，不用卡片 -->
    <section class="pd-check">
      <header class="pd-check-head">
        <h2>发布前，再看一眼</h2>
        <span>4 项检查</span>
      </header>
      <ul>
        <li v-for="row in checks" :key="row.text">
          <span class="pd-check-mark"><PvIcon name="check" :size="13" :stroke="2.4" /></span>
          <span class="pd-check-text">{{ row.text }}</span>
          <span class="pd-check-val">{{ row.value }}</span>
        </li>
      </ul>
    </section>

    <!-- 右侧滑出抽屉：模板是行列表，不是卡片网格 -->
    <teleport to="body">
      <transition name="pd-fade">
        <div v-if="drawer" class="pd-drawer-root">
          <div class="pd-mask" @click="drawer = false"></div>
          <aside class="pd-drawer" role="dialog" aria-label="选择折扣模板">
            <header class="pd-drawer-head">
              <div>
                <h3>选折扣模板</h3>
                <p>按销售价 ¥{{ money(sale) }} 实时试算</p>
              </div>
              <button type="button" class="pd-icon-btn" aria-label="关闭" @click="drawer = false">
                <PvIcon name="close" :size="18" />
              </button>
            </header>

            <div class="pd-drawer-list">
              <button
                v-for="item in templates"
                :key="item.id"
                type="button"
                class="pd-tpl-option"
                :class="{ on: selected === item.id }"
                @click="pick(item.id)"
              >
                <span class="pd-tpl-radio"><i></i></span>
                <span class="pd-tpl-main">
                  <span class="pd-tpl-title">
                    <b>{{ item.name }}</b>
                    <em>{{ item.tag }}</em>
                  </span>
                  <span class="pd-tpl-desc">{{ item.desc }}</span>
                  <span class="pd-tpl-math">{{ previewMath(item) }}</span>
                </span>
                <PvIcon v-if="selected === item.id" name="check" :size="16" :stroke="2.4" class="pd-tpl-check" />
              </button>
            </div>

            <footer class="pd-drawer-foot">
              <button type="button" class="pd-btn pd-btn--ghost" @click="clearTemplate">清除模板</button>
              <button type="button" class="pd-btn pd-btn--solid" @click="drawer = false">确认使用</button>
            </footer>
          </aside>
        </div>
      </transition>
    </teleport>

    <transition name="pd-toast">
      <div v-if="toast" class="pd-toast">{{ toast }}</div>
    </transition>
  </main>
</template>

<script setup>
// ⚠️ 2026-10-19（第三批㉖ · 真实数据接入）：本页仍是**预览页**（`/preview/discount`），
//    版式与文案一字未改；改动只有两处数据来源：
//      ① 模板列表 ← `POST /goods/discount/templates`（失败 / 未连接 ⇒ 保留下面的示例模板）；
//      ② 划线价 ← `POST /goods/discount/preview`（服务端 CEILING 反算；未连接 ⇒ 本地公式试算）。
//    ⚠️ 服务端预览**不是**落库判据 —— 提审时 `DiscountService.snapshotForSubmit` 会重算并覆盖。
import { computed, onMounted, ref, watch } from 'vue'
import PvIcon from '../../components/preview/PvIcon.vue'
import { discountPreview, discountTemplates } from '../../api/discount'

const steps = [
  { n: '01', title: '填写销售价', desc: '这是买家实际支付的基准价。' },
  { n: '02', title: '选一个折扣模板', desc: '九折、八五折或直降，划线价自动反算。' },
  { n: '03', title: '确认买家视角', desc: '原价、售价、会员省下的钱，一次看清。' },
]

// 示例模板（后端不可达 / 匿名访客时的兜底）。字段与真实模板行**同形**：
// `id` / `name` / `tag` / `rate`（rate 型）/ `fixed`（cut 型）/ `desc`。
const demoTemplates = [
  { id: 'p9', name: '九折', tag: '最常用', rate: 0.9, desc: '按售价反算划线价，适合日常促销。' },
  { id: 'p85', name: '八五折', tag: '大促', rate: 0.85, desc: '折扣更深，划线价与售价拉开明显差距。' },
  { id: 'm10', name: '直降 10 元', tag: '简单直接', fixed: 10, desc: '不按比例，直接在售价上加回固定金额。' },
  { id: 'none', name: '不设折扣', tag: '原价销售', desc: '不展示划线价，会员折扣仍可单独开启。' },
]

const legend = [
  { term: '销售价', value: '你填的', desc: '买家实际支付的基准价，订单按它记账。' },
  { term: '划线价', value: '反算的', desc: '由模板从销售价反推，只用于对比展示。' },
  { term: '会员价', value: '全场 9 折', desc: '在销售价上再打一档，不与模板互相替代。' },
  { term: '到手价', value: '券后再算', desc: '会员价继续抵扣优惠券，最终以订单为准。' },
]

const templates = ref(demoTemplates)
const dataSource = ref('demo')
const sourceLabel = computed(() => (dataSource.value === 'live'
  ? '实时数据 · 服务端模板'
  : '示例数据 · 未连接后端'))

const sale = ref(99.9)
const selected = ref('p9')
const memberOn = ref(true)
const drawer = ref(false)
const toast = ref('')
const previewMsg = ref('')
const liveOriginal = ref(null)
let toastTimer = null
let previewTimer = null
let previewSeq = 0

const tpl = computed(() => templates.value.find((item) => item.id === selected.value) || null)
const original = computed(() => {
  const s = Number(sale.value)
  if (!tpl.value || tpl.value.id === 'none' || !Number.isFinite(s) || s <= 0) return null
  // 真实模板 ⇒ 只信服务端反算（CEILING 分位）；未连上时才有本地试算。
  if (tpl.value.apiId) return liveOriginal.value
  if (tpl.value.rate) return s / tpl.value.rate
  return s + tpl.value.fixed
})
const memberPrice = computed(() => {
  const s = Number(sale.value)
  if (!Number.isFinite(s) || s <= 0) return 0
  return Math.round(s * 0.9 * 100) / 100
})
const memberSave = computed(() => {
  const s = Number(sale.value)
  if (!Number.isFinite(s) || s <= 0) return 0
  return Math.round((s - memberPrice.value) * 100) / 100
})

const validWindow = computed(() => {
  const item = tpl.value
  if (!item || !item.validFrom || !item.validTo) return '10-19 00:00 至 10-31 23:59'
  return `${shortTime(item.validFrom)} 至 ${shortTime(item.validTo)}`
})

const checks = computed(() => {
  const s = Number(sale.value)
  const rateText = tpl.value && tpl.value.rate
    ? `${Number((tpl.value.rate * 10).toFixed(1))} 折`
    : (tpl.value && tpl.value.fixed ? '直降' : '—')
  return [
    { text: '划线价高于销售价', value: original.value == null ? '待模板' : '通过' },
    { text: '会员价低于销售价', value: Number.isFinite(s) && memberPrice.value < s ? '通过' : '—' },
    { text: '活动结束时间晚于当前', value: tpl.value && tpl.value.validTo ? shortTime(tpl.value.validTo) : '10-31 23:59' },
    { text: '折扣力度未低于平台底线 7 折', value: rateText },
  ]
})

onMounted(loadTemplates)

function money(n) {
  if (n == null || !Number.isFinite(Number(n))) return '—'
  return Number(n).toFixed(2)
}

/** `2026-10-19 00:00:00` → `10-19 00:00`（后端 `DATE_FORMAT` 的形态）。 */
function shortTime(value) {
  const m = String(value).match(/^\d{4}-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/)
  return m ? `${m[1]}-${m[2]} ${m[3]}:${m[4]}` : String(value)
}

/** 服务端模板行 → 本页渲染形状（`Id` 数字进 `apiId`，预览接口只认它）。 */
function mapTemplate(row) {
  const type = String(row.DiscountType || 'rate')
  const rate = Number(row.Rate) || 0
  const cut = Number(row.CutAmount) || 0
  return {
    id: `tpl-${row.Id}`,
    apiId: Number(row.Id),
    name: row.Name || row.TemplateCode || '折扣模板',
    tag: type === 'cut' ? '直降' : `${Number((rate * 10).toFixed(1))} 折`,
    rate: type === 'rate' && rate > 0 ? rate : undefined,
    fixed: type === 'cut' && cut > 0 ? cut : undefined,
    desc: type === 'cut'
      ? `不按比例，在售价上加回 ¥${cut.toFixed(2)} 作为划线价。`
      : `按售价反算划线价，买家按 ${Number((rate * 10).toFixed(1))} 折支付。`,
    validFrom: row.ValidFrom || null,
    validTo: row.ValidTo || null,
  }
}

async function loadTemplates() {
  try {
    const res = await discountTemplates()
    if (res && res.success && Array.isArray(res.list) && res.list.length) {
      templates.value = res.list.map(mapTemplate)
        .concat([demoTemplates[demoTemplates.length - 1]])
      dataSource.value = 'live'
      selected.value = templates.value[0].id
    }
  } catch (e) {
    // fail-open：预览页是公开页，后端不可达时继续用示例数据（不弹窗、不阻塞渲染）。
    console.warn('[DiscountPreview] 折扣模板读取失败，沿用示例数据', e)
  }
}

watch([selected, sale], schedulePreview)

function schedulePreview() {
  previewMsg.value = ''
  liveOriginal.value = null
  if (previewTimer) clearTimeout(previewTimer)
  const item = tpl.value
  const s = Number(sale.value)
  if (!item || !item.apiId || !Number.isFinite(s) || s <= 0) return
  previewTimer = setTimeout(runPreview, 180)
}

async function runPreview() {
  const item = tpl.value
  const s = Number(sale.value)
  if (!item || !item.apiId || !Number.isFinite(s) || s <= 0) return
  const seq = ++previewSeq
  try {
    const res = await discountPreview({ DiscountTemplateId: item.apiId, Price: s })
    // 慢响应回来时模板已换 / 售价已改 ⇒ 丢弃（避免旧结果盖新值）。
    if (seq !== previewSeq) return
    if (res && res.success && res.valid && res.originalPrice != null) {
      liveOriginal.value = Number(res.originalPrice)
    } else {
      previewMsg.value = (res && res.msg) || '该模板不适用于当前售价'
    }
  } catch (e) {
    previewMsg.value = '预览接口暂不可用，本页未显示划线价'
    console.warn('[DiscountPreview] 划线价预览失败', e)
  }
}

function previewMath(item) {
  const s = Number(sale.value) || 0
  if (item.id === 'none') return '不展示划线价'
  if (item.apiId && selected.value === item.id && liveOriginal.value != null) {
    return `划线价 ¥${money(liveOriginal.value)} · 买家省 ¥${money(liveOriginal.value - s)}`
  }
  if (item.rate) {
    const base = s / item.rate
    return `划线价 ¥${money(base)} · 买家省 ¥${money(base - s)}`
  }
  return `划线价 ¥${money(s + item.fixed)} · 买家省 ¥${money(item.fixed)}`
}

function pick(id) {
  selected.value = id
  toast.value = id === 'none' ? '已清除折扣模板' : `已选择「${templates.value.find((t) => t.id === id).name}」`
  scheduleToast()
}

function clearTemplate() {
  selected.value = 'none'
  drawer.value = false
  toast.value = '已清除折扣模板'
  scheduleToast()
}

function notify(text) {
  toast.value = text
  scheduleToast()
}

function scheduleToast() {
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 1800)
}
</script>

<style scoped>
.pd-page {
  max-width: 1060px;
  margin: 0 auto;
  padding: 44px 38px 68px;
  color: #1a241e;
}

/* ── 首屏 ── */
.pd-hero {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 28px;
  padding-bottom: 46px;
  margin-bottom: 52px;
  border-bottom: 1px solid #dde4e0;
}
.pd-hero-tx { grid-column: span 8; }
.pd-kicker {
  font-size: 11px;
  letter-spacing: .1em;
  color: #7f8e86;
  margin-bottom: 18px;
}
.pd-title {
  font-family: var(--font-display);
  font-size: clamp(36px, 4.6vw, 56px);
  font-weight: 500;
  line-height: 1.06;
  letter-spacing: -.03em;
}
.pd-lede {
  margin-top: 22px;
  max-width: 470px;
  font-size: 14px;
  line-height: 1.9;
  color: #6c7972;
}

.pd-steps {
  grid-column: span 4;
  align-self: end;
  margin: 0;
  padding: 0 0 0 22px;
  list-style: none;
  border-left: 1px solid #dde4e0;
}
.pd-steps li {
  display: grid;
  grid-template-columns: 30px 1fr;
  gap: 10px;
  padding: 13px 0;
  border-bottom: 1px dashed #e3e9e5;
}
.pd-steps li:last-child { border-bottom: 0; padding-bottom: 0; }
.pd-step-n {
  font-family: var(--font-mono);
  font-size: 11px;
  color: #9aa8a0;
  padding-top: 2px;
}
.pd-steps b { font-size: 13px; font-weight: 600; color: #24322b; }
.pd-steps p { margin-top: 4px; font-size: 11.5px; line-height: 1.65; color: #8a968f; }

/* ── 编辑区：7 / 5 ── */
.pd-editor {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 28px;
  align-items: start;
  margin-bottom: 52px;
}
.pd-form {
  grid-column: span 7;
  background: #fff;
  border: 1px solid #e1e7e3;
  padding: 6px 28px 22px;
}
.pd-form-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 20px 0 16px;
  border-bottom: 1px solid #edf1ee;
}
.pd-form-head h2 { font-family: var(--font-display); font-size: 20px; font-weight: 500; }
.pd-form-head span { font-size: 10.5px; color: #9aa69f; }

.pd-field {
  padding: 20px 0;
  border-bottom: 1px solid #edf1ee;
}
.pd-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: #3d4c44;
}
.pd-money-input {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 9px;
  width: 200px;
  padding: 0 12px;
  border: 1px solid #d9e2dd;
  border-radius: 8px;
  background: #fbfcfb;
  transition: border-color .18s, box-shadow .18s;
}
.pd-money-input:focus-within {
  border-color: #2f6b58;
  box-shadow: 0 0 0 3px rgba(47, 107, 88, .1);
}
.pd-money-input span { font-size: 15px; color: #64736b; }
.pd-money-input input {
  width: 100%;
  padding: 11px 0;
  border: 0;
  outline: 0;
  background: transparent;
  font-family: var(--font-mono);
  font-size: 20px;
  color: #1a241e;
}
.pd-hint { margin-top: 7px; font-size: 11.5px; line-height: 1.6; color: #8d9992; }
.pd-hint--warn { color: #9a6a1f; }

.pd-tpl-row {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.pd-tpl-selected {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 13px;
  border: 1px solid #cfe2d8;
  background: #f3faf6;
  border-radius: 7px;
}
.pd-tpl-selected b { font-size: 14px; color: #1f5d47; }
.pd-tpl-selected span {
  font-size: 10px;
  color: #5a7d6c;
  border-left: 1px solid #cfe2d8;
  padding-left: 8px;
}
.pd-tpl-empty {
  padding: 9px 13px;
  border: 1px dashed #d5ddd8;
  border-radius: 7px;
  font-size: 12px;
  color: #97a39c;
}

.pd-field--split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}
.pd-readonly {
  margin-top: 8px;
  font-family: var(--font-mono);
  font-size: 19px;
  color: #24322b;
  letter-spacing: -.03em;
}
.pd-readonly--sm { font-size: 12px; letter-spacing: 0; color: #5c6a62; padding-top: 6px; }

.pd-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.pd-switch {
  flex: none;
  width: 44px;
  height: 24px;
  border-radius: 999px;
  border: 1px solid #d5ddd8;
  background: #e9eeeb;
  padding: 2px;
  cursor: pointer;
  transition: background .2s, border-color .2s;
}
.pd-switch i {
  display: block;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(20, 40, 30, .22);
  transition: transform .2s;
}
.pd-switch.on { background: #2f6b58; border-color: #2f6b58; }
.pd-switch.on i { transform: translateX(19px); }

.pd-form-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 20px;
}
.pd-btn {
  height: 38px;
  padding: 0 18px;
  border-radius: 8px;
  font-size: 12.5px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background .18s, border-color .18s, color .18s;
}
.pd-btn--ghost { background: transparent; border-color: #d9e2dd; color: #586861; }
.pd-btn--ghost:hover { background: #f4f7f5; }
.pd-btn--line { background: #fff; border-color: #cfdbd5; color: #2f6b58; }
.pd-btn--line:hover { background: #f3faf6; border-color: #b8d3c5; }
.pd-btn--solid { background: #1d2b24; color: #fff; }
.pd-btn--solid:hover { background: #2f6b58; }

/* ── 右侧买家视角价格卡 ── */
.pd-preview {
  grid-column: span 5;
  position: sticky;
  top: 92px;
  background: #fff;
  border: 1px solid #e1e7e3;
  border-radius: 16px;
  padding: 20px 22px 20px;
  box-shadow: 0 18px 44px -26px rgba(24, 48, 38, .32), 0 2px 6px rgba(24, 48, 38, .04);
}
.pd-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #7f8d86;
}
.pd-preview-dot { display: inline-flex; align-items: center; gap: 5px; color: #2f6b58; }
.pd-preview-dot i {
  width: 5px; height: 5px; border-radius: 50%;
  background: #2f9a6c; box-shadow: 0 0 0 3px rgba(47, 154, 108, .14);
}
.pd-goods {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid #eef2ef;
}
.pd-goods-img {
  width: 48px; height: 48px;
  display: grid; place-items: center;
  border-radius: 10px;
  color: #4c6b5c;
  background: linear-gradient(145deg, #eef4f0, #dfeae4);
  border: 1px solid #e2ebe6;
}
.pd-goods b { font-size: 13px; font-weight: 600; color: #24322b; }
.pd-goods p { margin-top: 3px; font-size: 10.5px; color: #95a19a; }

.pd-price { padding: 18px 0 16px; }
.pd-was { font-size: 12px; color: #9aa59f; }
.pd-was s { text-decoration-thickness: 1px; }
.pd-now {
  margin-top: 4px;
  font-family: var(--font-mono);
  font-size: 40px;
  line-height: 1;
  letter-spacing: -.05em;
  color: #17221c;
}
.pd-now span { font-size: 20px; margin-right: 2px; letter-spacing: 0; }
.pd-member {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 7px 11px;
  border-radius: 7px;
  background: #eaf7ef;
  border: 1px solid #cfe9d9;
  font-size: 11.5px;
  color: #2b6d52;
}
.pd-member b { font-family: var(--font-mono); font-size: 13px; }
.pd-member em {
  font-style: normal;
  padding-left: 10px;
  border-left: 1px solid #cfe9d9;
  color: #1f8a5c;
  font-weight: 600;
}

.pd-price-empty { padding: 20px 0 16px; }
.pd-price-empty b { font-size: 14px; color: #3f4d46; }
.pd-price-empty p {
  margin: 7px 0 12px;
  font-size: 11.5px;
  line-height: 1.7;
  color: #8d9992;
}

.pd-ticket-line { position: relative; height: 17px; margin: 0 -22px; overflow: hidden; }
.pd-ticket-line::before {
  content: '';
  position: absolute;
  left: 12px; right: 12px; top: 8px;
  border-top: 1px dashed #dfe6e2;
}
.pd-ticket-line span {
  position: absolute;
  top: 2px; left: 50%;
  width: 13px; height: 13px;
  margin-left: -6px;
  border-radius: 50%;
  background: #f4f6f8;
  border: 1px solid #e1e7e3;
}

.pd-breakdown { margin: 4px 0 18px; }
.pd-breakdown div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 6px 0;
  font-size: 11.5px;
}
.pd-breakdown dt { color: #8d9992; }
.pd-breakdown dd { color: #35443c; font-family: var(--font-mono); font-size: 11.5px; }

.pd-buy {
  width: 100%;
  height: 42px;
  border: 0;
  border-radius: 9px;
  background: #1d2b24;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: background .18s;
}
.pd-buy:hover { background: #2f6b58; }
.pd-preview-note {
  margin-top: 10px;
  text-align: center;
  font-size: 10px;
  line-height: 1.5;
  color: #a0aaa4;
}

/* ── 价格语义带：四列横向词条，不再是表单/卡片 ── */
.pd-legend {
  display: grid;
  grid-template-columns: 1.1fr 1fr 1fr 1fr;
  border-top: 1px solid #d8e0dc;
  border-bottom: 1px solid #d8e0dc;
  margin-bottom: 52px;
}
.pd-legend article {
  padding: 22px 22px 22px 0;
  border-right: 1px solid #e2e8e4;
}
.pd-legend article:last-child { border-right: 0; }
.pd-legend article + article { padding-left: 22px; }
.pd-legend-term {
  display: block;
  font-size: 11px;
  color: #8a968f;
  margin-bottom: 8px;
}
.pd-legend b { font-family: var(--font-display); font-size: 18px; font-weight: 500; color: #24322b; }
.pd-legend p { margin-top: 7px; font-size: 11px; line-height: 1.7; color: #8d9992; }

/* ── 发布前清单 ── */
.pd-check-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}
.pd-check-head h2 { font-family: var(--font-display); font-size: 22px; font-weight: 500; }
.pd-check-head span { font-size: 11px; color: #939f98; }
.pd-check ul { margin: 0; padding: 0; list-style: none; }
.pd-check li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 2px;
  border-top: 1px solid #e1e7e3;
  font-size: 12.5px;
  color: #435149;
}
.pd-check-mark {
  width: 20px; height: 20px;
  display: grid; place-items: center;
  border-radius: 50%;
  background: #e8f5ed;
  color: #2b8a5d;
}
.pd-check-text { flex: 1; }
.pd-check-val { font-family: var(--font-mono); font-size: 11px; color: #7f8c85; }

/* ── 抽屉 ── */
.pd-drawer-root { position: fixed; inset: 0; z-index: 90; }
.pd-mask {
  position: absolute;
  inset: 0;
  background: rgba(16, 24, 20, .34);
  backdrop-filter: blur(2px);
}
.pd-drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(430px, 92vw);
  display: flex;
  flex-direction: column;
  background: #fbfcfb;
  border-left: 1px solid #e1e7e3;
  box-shadow: -24px 0 60px rgba(15, 30, 22, .18);
}
.pd-drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 24px 24px 18px;
  border-bottom: 1px solid #e5ebe7;
}
.pd-drawer-head h3 { font-family: var(--font-display); font-size: 22px; font-weight: 500; }
.pd-drawer-head p { margin-top: 6px; font-size: 11.5px; color: #8d9992; }
.pd-icon-btn {
  width: 32px; height: 32px;
  display: grid; place-items: center;
  border: 1px solid #e1e7e3;
  border-radius: 50%;
  background: #fff;
  color: #5f6e66;
  cursor: pointer;
}
.pd-icon-btn:hover { background: #f0f4f2; }

.pd-drawer-list { flex: 1; overflow-y: auto; padding: 8px 24px; }
.pd-tpl-option {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 17px 4px;
  border: 0;
  border-bottom: 1px solid #e7ece9;
  background: transparent;
  text-align: left;
  cursor: pointer;
  border-radius: 8px;
  transition: background .16s;
}
.pd-tpl-option:hover { background: #f2f7f4; }
.pd-tpl-option.on { background: #eef8f2; }
.pd-tpl-radio {
  flex: none;
  width: 17px; height: 17px;
  margin-top: 2px;
  border-radius: 50%;
  border: 1px solid #cbd7d1;
  background: #fff;
  display: grid;
  place-items: center;
}
.pd-tpl-option.on .pd-tpl-radio { border-color: #2f6b58; }
.pd-tpl-radio i {
  width: 9px; height: 9px;
  border-radius: 50%;
  background: transparent;
  transition: background .16s;
}
.pd-tpl-option.on .pd-tpl-radio i { background: #2f6b58; }
.pd-tpl-main { flex: 1; min-width: 0; }
.pd-tpl-title { display: flex; align-items: baseline; gap: 8px; }
.pd-tpl-title b { font-size: 15px; font-weight: 600; color: #223029; }
.pd-tpl-title em {
  font-style: normal;
  font-size: 9.5px;
  color: #8b6a1f;
  background: #fbf3df;
  border: 1px solid #f0e0b8;
  padding: 2px 6px;
  border-radius: 999px;
}
.pd-tpl-desc { display: block; margin-top: 5px; font-size: 11.5px; line-height: 1.65; color: #7d8a83; }
.pd-tpl-math {
  display: block;
  margin-top: 6px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: #2f6b58;
}
.pd-tpl-check { color: #2f8a61; flex: none; margin-top: 3px; }

.pd-drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px;
  border-top: 1px solid #e5ebe7;
  background: #fff;
}

.pd-fade-enter-active, .pd-fade-leave-active { transition: opacity .22s ease; }
.pd-fade-enter-from, .pd-fade-leave-to { opacity: 0; }
.pd-fade-enter-active .pd-drawer, .pd-fade-leave-active .pd-drawer { transition: transform .26s cubic-bezier(.22, .8, .32, 1); }
.pd-fade-enter-from .pd-drawer, .pd-fade-leave-to .pd-drawer { transform: translateX(100%); }

.pd-toast {
  position: fixed;
  left: 50%;
  bottom: 40px;
  z-index: 120;
  transform: translateX(-50%);
  padding: 10px 16px;
  border-radius: 8px;
  background: rgba(23, 34, 28, .93);
  color: #fff;
  font-size: 12px;
  box-shadow: 0 12px 30px rgba(10, 20, 14, .28);
}
.pd-toast-enter-active, .pd-toast-leave-active { transition: opacity .2s, transform .2s; }
.pd-toast-enter-from, .pd-toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }

@media (max-width: 900px) {
  .pd-page { padding: 30px 22px 52px; }
  .pd-hero-tx { grid-column: 1 / -1; }
  .pd-steps { grid-column: 1 / -1; border-left: 0; padding-left: 0; }
  .pd-editor { grid-template-columns: 1fr; }
  .pd-form, .pd-preview { grid-column: 1 / -1; }
  .pd-preview { position: static; }
  .pd-legend { grid-template-columns: 1fr 1fr; }
  .pd-legend article:nth-child(2) { border-right: 0; }
  .pd-legend article:nth-child(n+3) { border-top: 1px solid #e2e8e4; }
}
@media (max-width: 560px) {
  .pd-field--split { grid-template-columns: 1fr; gap: 14px; }
  .pd-legend { grid-template-columns: 1fr; }
  .pd-legend article { border-right: 0; padding-left: 0; }
  .pd-legend article + article { padding-left: 0; border-top: 1px solid #e2e8e4; }
  .pd-form { padding: 4px 18px 18px; }
}
</style>
