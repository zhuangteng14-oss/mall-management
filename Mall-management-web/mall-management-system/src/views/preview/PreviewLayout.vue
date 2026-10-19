<template>
  <div class="pvl-shell">
    <header class="pvl-top">
      <div class="pvl-top-in">
        <router-link class="pvl-brand" to="/preview">
          <span class="pvl-mark">M</span>
          <span class="pvl-brand-tx">
            <b>Mall UI</b>
            <i>高保真静态预览</i>
          </span>
        </router-link>

        <nav class="pvl-nav" aria-label="预览页导航">
          <router-link
            v-for="item in items"
            :key="item.to"
            :to="item.to"
            class="pvl-nav-a"
            :class="{ on: route.path === item.to }"
          >
            <span class="pvl-nav-n">{{ item.n }}</span>
            <span>{{ item.label }}</span>
          </router-link>
        </nav>

        <div class="pvl-right">
          <span class="pvl-static"><i></i>静态演示 · Mock</span>
          <router-link class="pvl-exit" to="/customer">返回商城</router-link>
        </div>
      </div>
    </header>

    <!-- 贯穿五页的结构装置：左侧「价签 / 小票」脊线。
         竖排的页码与页名 + 虚线撕口 + 上下两枚半圆缺口 —— 五页共用同一条脊线，
         内容区按各自业务做非对称排版，不再是一张张等宽卡片。 -->
    <div class="pvl-stage">
      <aside class="pvl-spine" aria-hidden="true">
        <span class="pvl-spine-n">{{ current.n }}</span>
        <span class="pvl-spine-label">{{ current.label }}</span>
        <span class="pvl-spine-dots"></span>
        <span class="pvl-spine-foot">UI<br />PREVIEW</span>
      </aside>
      <div class="pvl-stage-body">
        <router-view />
      </div>
    </div>

    <footer class="pvl-foot">
      <div class="pvl-foot-in">
        <span>UI 先行阶段 · 五页外观确认稿</span>
        <span class="pvl-foot-sep">/</span>
        <span>纯静态 · 无网络请求 · 数据均为 Mock</span>
        <span class="pvl-foot-right">Mall Management · 2026-10-19</span>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const items = [
  { to: '/preview/discount', n: '01', label: '折扣价格' },
  { to: '/preview/brand', n: '02', label: '品牌加盟' },
  { to: '/preview/member', n: '03', label: '会员中心' },
  { to: '/preview/wallet', n: '04', label: '我的钱包' },
  { to: '/preview/seckill', n: '05', label: '限时秒杀' },
]

const current = computed(() => {
  if (route.path === '/preview' || route.path === '/preview/') return { n: '00', label: '目录' }
  return items.find((item) => item.to === route.path) || items[0]
})
</script>

<style scoped>
.pvl-shell {
  min-height: 100vh;
  background: #f4f6f8;
  color: #17202a;
  font-family: var(--font);
}

/* ── 顶栏：中性白，负责把五个页面串成一套预览工作台 ── */
.pvl-top {
  position: sticky;
  top: 0;
  z-index: 60;
  background: rgba(255, 255, 255, .92);
  border-bottom: 1px solid #e4e8ec;
  backdrop-filter: saturate(160%) blur(14px);
}
.pvl-top-in {
  max-width: 1240px;
  min-height: 68px;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 28px;
}
.pvl-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
  flex: none;
}
.pvl-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, #1e3a34, #2f6b58 58%, #8ac7ac);
  box-shadow: 0 6px 16px rgba(31, 92, 74, .22);
}
.pvl-brand-tx { display: flex; flex-direction: column; line-height: 1.15; }
.pvl-brand-tx b { font-size: 15px; letter-spacing: .01em; }
.pvl-brand-tx i { font-style: normal; font-size: 11px; color: #7c8792; letter-spacing: .08em; }

.pvl-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}
.pvl-nav::-webkit-scrollbar { display: none; }
.pvl-nav-a {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 0 12px;
  border-radius: 9px;
  color: #66717c;
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
  transition: background .18s, color .18s;
}
.pvl-nav-a:hover { background: #f1f4f6; color: #1f2b36; }
.pvl-nav-a.on { background: #1d2924; color: #fff; }
.pvl-nav-n {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .04em;
  opacity: .55;
}
.pvl-nav-a.on .pvl-nav-n { opacity: .72; }

.pvl-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 16px;
  flex: none;
}
.pvl-static {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .1em;
  color: #83909b;
}
.pvl-static i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e6a23c;
  box-shadow: 0 0 0 3px rgba(230, 162, 60, .16);
}
.pvl-exit {
  font-size: 12px;
  color: #2f6b58;
  text-decoration: none;
  padding: 7px 12px;
  border: 1px solid #d3e4dc;
  border-radius: 8px;
  background: #f6fbf8;
  transition: background .18s, border-color .18s;
}
.pvl-exit:hover { background: #edf7f1; border-color: #b9d8c8; }

/* ── 结构装置：左侧价签脊线 + 内容舞台 ── */
.pvl-stage {
  max-width: 1240px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 66px minmax(0, 1fr);
  align-items: stretch;
}
.pvl-spine {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 34px 0 42px;
  border-right: 1px dashed #cdd8d1;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, .5), transparent 70%);
}
/* 上下两枚半圆缺口：小票撕口的视觉锚点 */
.pvl-spine::before,
.pvl-spine::after {
  content: '';
  position: absolute;
  right: -7px;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: #f4f6f8;
  border: 1px dashed #cdd8d1;
}
.pvl-spine::before { top: 18px; }
.pvl-spine::after { bottom: 18px; }
.pvl-spine-n {
  font-family: var(--font-mono);
  font-size: 25px;
  line-height: 1;
  letter-spacing: -.07em;
  color: #1d2924;
  transform: rotate(180deg);
  writing-mode: vertical-rl;
}
.pvl-spine-label {
  margin-top: 16px;
  writing-mode: vertical-rl;
  font-size: 11px;
  letter-spacing: .26em;
  color: #7f8d86;
}
.pvl-spine-dots {
  flex: 1;
  width: 1px;
  margin: 20px 0;
  background-image: radial-gradient(circle, #c6d2cb 1px, transparent 1.2px);
  background-size: 1px 8px;
  background-repeat: repeat-y;
}
.pvl-spine-foot {
  writing-mode: vertical-rl;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: .18em;
  line-height: 1.5;
  color: #a9b4ae;
  text-align: center;
}
.pvl-stage-body { min-width: 0; }

/* ── 页脚 ── */
.pvl-foot {
  border-top: 1px solid #e6eaee;
  background: #fff;
}
.pvl-foot-in {
  max-width: 1240px;
  margin: 0 auto;
  padding: 22px 24px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #8a949e;
}
.pvl-foot-sep { color: #c8cfd6; }
.pvl-foot-right { margin-left: auto; font-family: var(--font-mono); font-size: 10px; letter-spacing: .06em; }

@media (max-width: 980px) {
  .pvl-top-in { gap: 16px; }
  .pvl-static { display: none; }
}
@media (max-width: 860px) {
  .pvl-stage { grid-template-columns: 1fr; }
  .pvl-spine { display: none; }
}
@media (max-width: 760px) {
  .pvl-top-in { min-height: 60px; padding: 0 16px; flex-wrap: wrap; padding-top: 10px; padding-bottom: 10px; }
  .pvl-nav { order: 3; width: 100%; padding-bottom: 2px; }
  .pvl-brand-tx i { display: none; }
  .pvl-right { margin-left: auto; }
  .pvl-foot-in { padding: 18px 16px; }
  .pvl-foot-right { margin-left: 0; width: 100%; }
}
</style>
