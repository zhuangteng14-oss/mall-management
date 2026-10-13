<!-- ReportDialog.vue —— 举报弹窗（C3 块 6 整改 · commit 7；**多个入口共用**）。

  ⚠️ 为什么抽成组件（仓规「同一件事不抄第二份」）：这段弹窗原先只内联在 `views/ProductPage.vue`
     里，替商品 / 评价 / 回复 / 提问四个入口服务。块 6 整改把「举报商家」也补上，落点却在
     **另一页**（`views/ShopHome.vue` 的店铺主页）。同一条「选理由 → 填说明 → `POST /report/add`」
     要出现在两个页面 ⇒ 收在这里，两边都渲染它。以后「举报评论」「举报用户」再添入口，同此。

  ⚠️ 类名统一加 `r` 前缀（`rscrim` / `rdlg` / `rreasons` …）**不是**排版偏好：
     若这里也叫 `.scrim` / `.dlg`，父页面（`ProductPage`）同名的 `scoped` 规则会**同时命中本组件
     根节点**（Vue 会把父作用域属性打在子组件根上）⇒ 两份规则叠加、改一处不知另一处在生效。
     这是 `components/CashierDialog.vue` 当年踩过并写进注释的同一个坑（它用 `c` 前缀），此处照办。

  ⚠️ 本仓无组件库，这份 `<style scoped>` 与 `ProductPage.vue` 的弹窗皮肤（`.scrim` / `.dlg` /
     `.dlg-h` / `.dacts` …）是**同形两份**：那种「整段搬过来」在 `CashierDialog.vue` 已有先例
     （理由同它：`<style scoped>` 只把作用域属性打在**子组件根节点**上，根节点**以内**的元素拿不到
     父页面的样式 ⇒ 组件必须自带完整一份）。⚠️ 父页面那份**不能删** —— `ProductPage.vue` 的
     「付款确认弹窗」还在用它。

  用法：
    <ReportDialog :open="rptOpen" :target="rptTarget" @close="rptOpen = false" />
  `target` 为 null ⇒ 标题退化成「举报」（不该发生 —— 调用方都是先设 `target` 再开）。
  ⚠️ 组件**不自己开关**：`open` 是唯一开关，`@close` 只是通知。理由/说明/回执那三个 ref
     在 `open` 由 false→true 时**重置**（上一单填的「其他」正文不该带到下一单）。
-->
<template>
  <div class="rscrim" v-if="open" @click.self="close">
    <div class="rdlg rdlg-narrow">
      <div class="rdlg-h">举报{{ target ? target.label : '' }}</div>
      <div class="rdlg-sub">请选择举报理由。举报人对被举报人<strong>匿名</strong>，管理员可见你的账号。</div>
      <div class="rreasons">
        <label v-for="rs in REP_REASONS" :key="rs">
          <input type="radio" name="rep-reason" :value="rs" v-model="reason"> {{ rs }}
        </label>
      </div>
      <textarea v-if="reason === '其他'" v-model="desc" class="rdesc" maxlength="100"
                placeholder="请填写说明（必填，最多 100 字）"></textarea>
      <p class="rmsg" v-if="msg">{{ msg }}</p>
      <div class="rdacts">
        <button type="button" class="b no" @click="close">取消</button>
        <button type="button" class="b ok" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '提交举报' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { add as reportAdd } from '../api/report'
import { getUsername } from '../api/auth'
import { handleApiError } from '../api/http'

/* ⚠️ `no-undef` 这两条 disable 是**必需**的（同 `ProductCard.vue:134` / `CashierDialog.vue:51`）：
   本仓 `eslint-plugin-vue` 8.7.1 的 `vue3-essential` **不带** `vue/setup-compiler-macros` ⇒
   `defineProps` / `defineEmits` 一律被判 `no-undef`、`npm run build` 直接红。 */
// eslint-disable-next-line no-undef
const props = defineProps({
  /** 唯一开关 —— 由调用方持有。 */
  open: { type: Boolean, default: false },
  /**
   * 被举报对象 —— `{ targetType, targetId, label }`。
   * ⚠️ `targetId` 的**号域随 `targetType` 变**（见 `api/report.js` 的 `add`）：评论/回复是
   * `comment.Id`、商品是 `goods.Id`、**用户是业务号** —— 别在这里做任何换算，原样透传。
   */
  target: { type: Object, default: null },
})
// eslint-disable-next-line no-undef
const emit = defineEmits(['close'])

/** 举报的四个理由（方案 §4.4 逐字）。⚠️ 与后端 `ReportService.REASONS` 是同一组值（写侧收紧）。 */
const REP_REASONS = ['辱骂', '广告', '违法', '其他']

const reason = ref('')
const desc = ref('')
const submitting = ref(false)
const msg = ref('')

// `open` 由 false→true 时重置 —— 上一单填的「其他」正文与回执不该带到下一单。
watch(() => props.open, (now) => {
  if (now) { reason.value = ''; desc.value = ''; msg.value = ''; submitting.value = false }
})

const close = () => { msg.value = ''; emit('close') }

/**
 * 提交举报（`/report/add`）。
 *
 * ⚠️ 「其他」必须填说明（后端 §4.4 的硬闸）—— 前端先拦一道，给一句人话而不是把后端原话抛出去。
 * ⚠️ 「不能举报自己 / 已举报过」是**业务规则**（后端回 `success:false`）⇒ 就地显示、不弹「系统错误」。
 */
const submit = async () => {
  if (!getUsername()) { msg.value = '登录后才能举报'; return }
  if (!reason.value) { msg.value = '请选择举报理由'; return }
  const d0 = (desc.value || '').trim()
  if (reason.value === '其他' && !d0) { msg.value = '选择「其他」时必须填写说明'; return }
  if (d0.length > 100) { msg.value = '举报说明最多 100 字'; return }
  if (!props.target) { msg.value = '举报对象丢失，请关掉重开'; return }
  submitting.value = true
  try {
    const d = await reportAdd({
      account: getUsername(),
      targetType: props.target.targetType,
      targetId: props.target.targetId,
      reason: reason.value,
      description: d0 || undefined,
    })
    if (!d || !d.success) { msg.value = (d && d.msg) || '举报提交失败'; return }
    close()
    alert(d.msg || '举报已提交，等待管理员处理')
  } catch (e) {
    handleApiError(e, '举报失败：')
    msg.value = '举报提交失败'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
/* ⚠️ 这份皮肤与 `ProductPage.vue` 的 `.scrim`/`.dlg`/`.dacts` **同形**（类名加 `r` 前缀，理由见文件头）。
   改档位时两边都要看一眼 —— 但两边可以**各自演化**：本组件是「窄弹窗 + 单选行」，
   而父页那份还要服务「付款确认弹窗」。不做的共享是**故意的**（`<style scoped>` 下跨组件共享
   只有 `:deep()` / 全局表两条路，都比各持一份更容易出事）。 */
.rscrim { position: fixed; inset: 0; z-index: 200; display: flex; padding: var(--s5); overflow: auto;
          background: color-mix(in srgb, var(--ink) 34%, transparent); }
.rdlg { margin: auto; width: 100%; max-width: 520px; background: var(--surface);
        border: 1px solid var(--line-2); border-radius: var(--r3); padding: var(--s7); }
.rdlg-narrow { max-width: 420px; }
.rdlg-h { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: var(--fw-med);
          letter-spacing: -.01em; }
.rdlg-sub { margin-top: var(--s2); font-size: var(--fs-xs); color: var(--ink-3); }
.rdacts { margin-top: var(--s6); display: flex; gap: var(--s3); }
.rdacts .b { height: 46px; border-radius: var(--r2); font-size: var(--fs-md); font-weight: var(--fw-med); }
.rdacts .ok { flex: 1 1 auto; background: var(--brand); color: var(--bg); }
.rdacts .ok:hover { background: var(--brand-hover); }
.rdacts .no { flex: 0 0 120px; border: 1px solid var(--line-2); color: var(--ink-2); }
.rdacts .no:hover { border-color: var(--ink-3); }

/* `.rreasons` 是**单选行**（四条理由竖排，一条一行，热区整行可点）；`.rdesc` 是「其他」时才出现的
   说明框（上限 100 字，同文案字数口径写在后端 `ReportService.MAX_DESCRIPTION`）；`.rmsg` 是
   业务规则的**回执行**（「已举报过」「不能举报自己」—— 后端 `success:false` 的原话）。
   ⚠️ `.rmsg` 用 `--accent`：全仓告警/驳回类文案已经统一在这一档（同 `ProductPage.vue` 的
   `.wk2.warn` / `.rshots .s.bad`），不是新开一个色。 */
.rreasons { margin-top: var(--s4); display: flex; flex-direction: column; gap: var(--s1); }
.rreasons label { display: flex; align-items: center; gap: var(--s3); padding: var(--s3);
                  border: 1px solid var(--line-2); border-radius: var(--r2); cursor: pointer;
                  font-size: var(--fs-sm); color: var(--ink-2); }
.rreasons label:hover { border-color: var(--ink-3); color: var(--ink); }
.rreasons input { accent-color: var(--brand); margin: 0; }
.rdesc { margin-top: var(--s3); width: 100%; min-height: 84px; padding: var(--s3);
         border: 1px solid var(--line-2); border-radius: var(--r2); background: var(--surface);
         color: var(--ink); font-size: var(--fs-sm); font-family: inherit; line-height: 1.6;
         resize: vertical; }
.rdesc:focus { outline: none; border-color: var(--brand); }
.rmsg { margin-top: var(--s3); font-size: var(--fs-xs); color: var(--accent); }
</style>
