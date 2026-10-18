<template>
  <!--
    「敬请期待」静态页（批四 · 第一批 4）。

    【它替掉的是什么】主页二级导航里原先三枚 `disabled` 的灰按钮（品牌专区 / 会员中心 /
      客户服务）。灰按钮的问题不是「没做」，而是**连点都点不动** —— 用户看不出这是「还没做」
      还是「坏了」。现在三枚都是真链接，落到这一页，把「后面会做」说清楚。

    【为什么是同一页 + 参数】三个入口的差别只有文案（名字 / 一句说明 / 图标），业务逻辑都
      还没有 ⇒ 一份页面按 `:feature` 取文案，别复制三份 90% 相同的模板。第二批 / 第三批
      真正落功能时，把对应路由从这里改到真页面即可，入口不用动。

    【路由】`/coming-soon/:feature`，`meta.public` —— 主页本身是公开面，按钮不能把匿名用户
      踢去登录页。见 `router/index.js` 文件尾那条 `addRoute`。
  -->
  <main class="cs">
    <div class="cs-card">
      <span class="cs-badge"><IconGlyph :name="view.icon" size="1.6em" /></span>
      <p class="cs-kicker">COMING SOON</p>
      <h1 class="cs-title">敬请期待</h1>
      <p class="cs-name">{{ view.name }}</p>
      <p class="cs-desc">{{ view.desc }}</p>

      <div class="cs-actions">
        <button type="button" class="cs-btn cs-btn-ghost" @click="goBack">返回上一页</button>
        <router-link class="cs-btn cs-btn-solid" to="/">回到首页</router-link>
      </div>

      <p class="cs-note">该模块正在建设中，上线后入口不变。</p>
    </div>
  </main>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import IconGlyph from '../components/ui/IconGlyph.vue'

const route = useRoute()
const router = useRouter()

/**
 * 三个入口的文案表。⚠️ 键与 `HomePage.vue` 二级导航里的 `to` 一一对应；
 * 认不出（手输 URL / 以后新增入口忘了登记）回落通用文案，**不白屏**。
 */
const VIEWS = {
  brand: {
    icon: 'merchants',
    name: '品牌专区',
    desc: '按品牌逛正品好物 —— 品牌数据模型落地后，这里会是品牌馆与品牌榜单。',
  },
  member: {
    icon: 'users',
    name: '会员中心',
    desc: '会员等级、成长值与专属权益 —— 会员体系落地后，这里会是你的会员主页。',
  },
  service: {
    icon: 'msg',
    name: '客户服务',
    desc: '在线客服、常见问题与售后入口 —— 客服能力落地后，这里会是一站式服务中心。',
  },
}

const view = computed(() => VIEWS[String(route.params.feature || '')] || {
  icon: 'wait',
  name: '新功能',
  desc: '这个入口正在建设中，稍后回来看看。',
})

function goBack() {
  // 直接输 URL 进来时没有上一页可回 ⇒ 回首页，而不是把用户留在原地（点了没反应）。
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<style scoped>
.cs {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s8) var(--s4);
  background:
    radial-gradient(900px 420px at 50% -10%, var(--brand-soft, rgba(28, 116, 255, .10)), transparent 70%),
    var(--bg);
}
.cs-card {
  width: min(560px, 100%);
  padding: var(--s7) var(--s8);
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r3);
}
.cs-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--brand-soft, rgba(28, 116, 255, .10));
  color: var(--brand);
}
.cs-kicker {
  margin: var(--s5) 0 var(--s2);
  font-size: var(--fs-xs);
  letter-spacing: .18em;
  color: var(--ink-3);
}
.cs-title {
  margin: 0;
  font-family: var(--font);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.cs-name {
  margin: var(--s2) 0 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-med);
  color: var(--brand);
}
.cs-desc {
  margin: var(--s4) auto 0;
  max-width: 42ch;
  font-size: var(--fs-sm);
  line-height: 1.9;
  color: var(--ink-2);
}
.cs-actions {
  display: flex;
  justify-content: center;
  gap: var(--s3);
  margin-top: var(--s6);
  flex-wrap: wrap;
}
.cs-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 120px;
  height: 40px;
  padding: 0 var(--s5);
  border-radius: var(--r1);
  font-size: var(--fs-sm);
  text-decoration: none;
  cursor: pointer;
}
.cs-btn-ghost {
  border: 1px solid var(--line-2);
  background: var(--surface);
  color: var(--ink-2);
}
.cs-btn-ghost:hover { border-color: var(--ink-3); color: var(--ink); }
.cs-btn-solid {
  border: 1px solid var(--brand);
  background: var(--brand);
  color: #fff;
}
.cs-btn-solid:hover { filter: brightness(1.05); }
.cs-note {
  margin: var(--s5) 0 0;
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
</style>
