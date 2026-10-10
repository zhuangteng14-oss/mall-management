<template>
  <!-- ═══ 「选模板」弹窗（C3 块 3 就地改造，2026-10-10）════════════════════════════
       ⚠️ **本组件只有弹窗，没有触发按钮** —— 按钮留在各调用页、沿用该页既有类
       （`GoodsAdd.vue` 用 `.btn-quiet`，管理端用 `.a-btn` / `.c-btn`）。
       理由：6 处入口分布在 3 个壳（用户 / 商家 / 管理）下，各页的按钮样式族本就不同；
       把按钮也塞进来就得给组件开一个 `buttonClass` 口子 —— 那等于把「各页的样式约定」
       泄进一个公共组件。⇒ 组件只负责「弹窗 + 取数 + 回传整行」。

       ⚠️ 结构照抄 `GoodsUp.vue` 的 `.scrim` / `.dlg` 骨架（遮罩 `@click` 关、内容 `@click.stop`、
       右上 `×`）—— 事件分工逐字保留：**遮罩点击关窗、弹窗自己 `@click.stop`**
       （少了 `.stop` 会「点正文也关窗」）。样式取那份的精简版，颜色/间距全走 `assets/tokens.css`
       的 `var(--*)` ⇒ 在 `html[data-theme="admin"]` 暗色主题下自动成立。 -->
  <div class="tp-scrim" @click="close">
    <div class="tp-dlg" @click.stop>
      <div class="tp-h">
        <h2>{{ title }}</h2>
        <button type="button" class="x" title="关闭" @click="close">×</button>
      </div>

      <div class="tp-body">
        <p v-if="loading" class="tp-hint">加载中…</p>

        <!-- ⚠️ 空列表**不是错误**：`template` 表初始就是空的（新建的库一条模板都没有），
             此后端也**不校验未知 type**（回空列表 + `success:true`）—— 两处合起来就是这一句。 -->
        <p v-else-if="error" class="tp-hint tp-err">{{ error }}</p>
        <p v-else-if="rows.length === 0" class="tp-hint">{{ emptyText }}</p>

        <ul v-else class="tp-list">
          <!-- 整行可点（含标题 / 预览）⇒ 用 `<button>` 而不是 `<li @click>` 才拿得到键盘焦点。 -->
          <li v-for="r in rows" :key="r.Id">
            <button type="button" class="tp-row" @click="pick(r)">
              <span class="tp-name">{{ r.Name }}</span>
              <span v-if="r.Title" class="tp-title">{{ r.Title }}</span>
              <span class="tp-preview">{{ r.Body || '（空）' }}</span>
            </button>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
/* ⚠️ 这条 `eslint-disable` 是**必需**的，不是偷懒：本仓 `eslint-plugin-vue` 是 **8.7.1**，
   其 `vue3-essential` **不带** `vue/setup-compiler-macros` 宏环境 ⇒ `defineProps` / `defineEmits`
   一律被判 `no-undef`、`npm run build` 直接红。房内先例：`ShoppingCart.vue` / `OrderCenter.vue`
   的块级同款、`HomeTab.vue` 的逐行同款（那里把原因写全了）。
   ⇒ 本仓**没有任何 .vue 用了这些宏却没加 disable**，别把这条删掉「试试看」。 */
/* eslint-disable no-undef */
import { ref, onMounted } from 'vue'
import { templateList } from '../api/template'

const props = defineProps({
  /** 模板类型码（`'goods.detail'` / `'goods.spec'` / `'goods.afterSale'` / `'goods.reject'` / …）。必填。 */
  type: { type: String, required: true },
  /** 弹窗标题（各页可写成「选择详情模板」这种更具体的话）。 */
  title: { type: String, default: '选择模板' },
  /** 列表为空时的提示语。 */
  emptyText: { type: String, default: '暂无模板' },
})

/**
 * 选了一条 —— 回传**整行**（含 `Id` / `Type` / `Name` / `Title` / `Body` / `Enabled` / `Sort`）。
 *
 * ⚠️ 回传整行而**不是**只回 `Body`：调用页要自己决定「填 `Body`」还是「拼接 `Title` + `Body`」
 * （通知类模板两段都有意义，商品类只有 `Body`）。组件不替调用页做这个判断。
 * ⚠️ **不自动关窗** —— 关窗只走 `close` 事件。两边分开是刻意的：调用页可能想在 `pick` 里
 * 先做点什么（比如另开一个确认）再关。
 */
const emit = defineEmits(['pick', 'close'])

const rows = ref([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    const data = await templateList(props.type)
    // ⚠️ 后端读侧**不抛异常**：`success:false` 也要当错误显示出来（否则会静默显示「暂无模板」，
    //    把「接口坏了」伪装成「还没建模板」—— 两者给人的下一步动作完全相反）。
    if (!data || data.success !== true) {
      error.value = (data && data.msg) || '模板列表加载失败'
      return
    }
    rows.value = data.list || []
  } catch (e) {
    error.value = '模板列表加载失败'
  } finally {
    loading.value = false
  }
})

function pick(row) {
  emit('pick', row)
}

function close() {
  emit('close')
}
</script>

<style scoped>
/* ── 遮罩：`GoodsUp.vue` `.scrim` 的精简版 ───────────────────────────────── */
.tp-scrim {
  position: fixed; inset: 0; z-index: 300; display: flex; padding: var(--s5); overflow: auto;
  /* ⚠️ 不用 rgba()：README 第 2 条要求「颜色只有 token，一个 #hex / rgb() 都不许有」。
     ⚠️ z-index 取 300（**压过 `GoodsUp.vue` 的 200**）：本组件会开在别的弹窗之上
     （典型是管理端在各页的详情面板里点「选模板」），取同一档会随机叠序。 */
  background: color-mix(in srgb, var(--ink) 34%, transparent);
}
/* ⚠️ 居中用子元素 `margin:auto`，**不用** `align-items:center`：视口矮时 center 会让内容
   上下两头同时溢出、顶部标题被切掉且滚不上去（同 `GoodsUp.vue` 那条注释）。 */
.tp-dlg {
  margin: auto; width: 100%; max-width: 560px; background: var(--surface);
  border: 1px solid var(--line-2); border-radius: var(--r3);
}
.tp-h {
  display: flex; align-items: baseline; gap: var(--s4); padding: var(--s5) var(--s6);
  border-bottom: 1px solid var(--line);
}
.tp-h h2 {
  font-family: var(--font-display); font-size: var(--fs-lg);
  font-weight: var(--fw-med); letter-spacing: -.01em;
}
.tp-h .x { margin-left: auto; color: var(--ink-3); font-size: var(--fs-lg); line-height: 1; }

.tp-body { padding: var(--s5) var(--s6); max-height: 60vh; overflow: auto; }
.tp-hint { font-size: var(--fs-sm); color: var(--ink-3); }
.tp-err { color: var(--danger); }

.tp-list { display: flex; flex-direction: column; gap: var(--s3); }
/* 整行是按钮 ⇒ 底色/边框全在 `.tp-row` 上；`li` 只做布局。 */
.tp-row {
  width: 100%; display: flex; flex-direction: column; gap: var(--s2); text-align: left;
  padding: var(--s4); border: 1px solid var(--line-2); border-radius: var(--r2);
  background: var(--surface); color: var(--ink);
}
.tp-row:hover { border-color: var(--brand); background: var(--brand-tint); }
.tp-row:focus-visible { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-tint); }
.tp-name { font-weight: var(--fw-med); }
.tp-title { font-size: var(--fs-sm); color: var(--ink-2); }
.tp-preview {
  font-size: var(--fs-xs); color: var(--ink-3);
  /* 预览截断到 3 行 —— 模板正文是 `mediumtext`，整段铺开会把弹窗撑成一屏。 */
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
  white-space: pre-wrap;
  /* ⚠️ 长串（模板行内 `键=值` 那种）不换行会横向撑破弹窗 ⇒ 必须 break。 */
  word-break: break-word;
}
</style>
