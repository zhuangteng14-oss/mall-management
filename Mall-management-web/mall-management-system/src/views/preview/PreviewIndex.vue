<template>
  <main class="pvi-page">
    <div class="pvi-wrap">
      <!-- 非对称首屏：左侧是大字排版，右侧是三块不同形状的说明；不做「居中大标题 + 三列图标」 -->
      <section class="pvi-hero">
        <div class="pvi-hero-main">
          <p class="pvi-kicker">UI 先行 · 2026-10-19</p>
          <h1 class="pvi-title">
            <span>先看</span>
            <span class="pvi-title-big">五页</span>
            <span>再写业务</span>
          </h1>
          <p class="pvi-lede">
            这一轮只做外观。五张页面全部用 Mock 数据、纯 CSS 与本地交互搭出来，
            不接任何 API。等你看过、指过、改过，再把这套视觉落到真实业务页。
          </p>
          <div class="pvi-hero-foot">
            <span class="pvi-stamp">待确认</span>
            <span class="pvi-hero-note">静态 · 无网络请求 · 可点击</span>
          </div>
        </div>

        <aside class="pvi-hero-side">
          <div class="pvi-side-block pvi-side-block--count">
            <span class="pvi-side-n">05</span>
            <span class="pvi-side-l">个页面</span>
            <p>折扣 · 品牌 · 会员 · 钱包 · 秒杀</p>
          </div>
          <div class="pvi-side-block pvi-side-block--device">
            <span class="pvi-side-l">结构装置</span>
            <p>左侧「价签 / 小票」脊线贯穿五页；内容区按业务做非对称排布。</p>
          </div>
          <div class="pvi-side-block pvi-side-block--rules">
            <span class="pvi-side-l">排版纪律</span>
            <ul>
              <li>相邻区块不共用布局</li>
              <li>不用等宽卡片墙</li>
              <li>区块间距 &gt; 内部间距</li>
              <li>统一 12 栏网格</li>
            </ul>
          </div>
        </aside>
      </section>

      <!-- 页面目录：行式目录，不是卡片网格；偶数行缩进 + 缩略图换边 -->
      <section class="pvi-contents">
        <header class="pvi-contents-head">
          <h2>页面目录</h2>
          <span>从上到下 · 点击进入</span>
        </header>

        <router-link
          v-for="(item, i) in items"
          :key="item.to"
          :to="item.to"
          class="pvi-entry"
          :class="[
            'pvi-entry--' + item.key,
            { 'pvi-entry--featured': i === 0, 'pvi-entry--flip': i % 2 === 1 },
          ]"
        >
          <span class="pvi-entry-n">{{ item.n }}</span>

          <div class="pvi-entry-tx">
            <div class="pvi-entry-tags">
              <span>{{ item.tag }}</span>
              <span v-if="i === 0">先看这页</span>
            </div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.desc }}</p>
          </div>

          <div class="pvi-entry-thumb" :class="'pvi-thumb--' + item.key">
            <template v-if="item.key === 'discount'">
              <div class="pvi-m-line w60"></div>
              <div class="pvi-m-price"><s>¥111.00</s><b>¥99.90</b><em>省 ¥9.99</em></div>
              <div class="pvi-m-chips"><i>9折</i><i>8.5折</i><i>直降</i></div>
            </template>
            <template v-else-if="item.key === 'brand'">
              <div class="pvi-m-brand">
                <i class="grey"></i><i class="blue"></i><i class="red"></i><i class="gold"></i>
              </div>
            </template>
            <template v-else-if="item.key === 'member'">
              <div class="pvi-m-member">
                <span class="dot"></span>
                <span class="line"></span>
                <span class="bar"><i></i></span>
                <span class="cells"><i></i><i></i><i></i><i></i></span>
              </div>
            </template>
            <template v-else-if="item.key === 'wallet'">
              <div class="pvi-m-wallet"><span>¥128.50</span><i>充值</i></div>
              <div class="pvi-m-tx"><span class="dot in"></span><b>+1,299.00</b></div>
              <div class="pvi-m-tx"><span class="dot"></span><b class="dark">-99.00</b></div>
            </template>
            <template v-else>
              <div class="pvi-m-session"><i></i><span>10:00 场</span><b>02:18:36</b></div>
              <div class="pvi-m-products"><i></i><i></i><i></i><i></i></div>
              <div class="pvi-m-progress"><i></i></div>
            </template>
          </div>

          <span class="pvi-entry-go"><PvIcon name="arrow" :size="18" /></span>
        </router-link>
      </section>

      <p class="pvi-foot">
        <PvIcon name="alert" :size="15" />
        字体与中性色沿用现有设计 token；会员页与秒杀页按本轮要求做了更强的主题化处理。
      </p>
    </div>
  </main>
</template>

<script setup>
import PvIcon from '../../components/preview/PvIcon.vue'

const items = [
  {
    key: 'discount', n: '01', tag: '商家端', title: '商品发布 · 折扣价格',
    desc: '销售价 → 折扣模板抽屉 → 划线价 / 售价 / 会员省预览卡。含未选模板的空态与不可用模板态。',
    to: '/preview/discount',
  },
  {
    key: 'brand', n: '02', tag: '商家端', title: '认证中心 · 品牌加盟',
    desc: '品牌加盟四态：未申请、审核中、已驳回、已开通。已开通用金边深色卡，其余按状态换语气。',
    to: '/preview/brand',
  },
  {
    key: 'member', n: '03', tag: '用户端', title: '会员中心 · VIP 黑金卡',
    desc: '深色渐变会员卡、成长进度、4×1 权益矩阵、连续签到 3 天与最近积分流水。',
    to: '/preview/member',
  },
  {
    key: 'wallet', n: '04', tag: '用户端', title: '我的钱包 · 收支明细',
    desc: '超大余额与充值按钮；退款入账绿色，消费扣减中性黑灰；含筛选与静态充值弹窗。',
    to: '/preview/wallet',
  },
  {
    key: 'seckill', n: '05', tag: '重构预览', title: '限时秒杀 · 现代排版',
    desc: '浅灰底、定宽容器、场次卡片；倒计时、商品网格、抢购进度条。红橙只做点缀，不铺大块红底。',
    to: '/preview/seckill',
  },
]
</script>

<style scoped>
.pvi-page {
  padding: 48px 40px 72px;
  background:
    radial-gradient(760px 380px at 18% 0%, rgba(47, 107, 88, .07), transparent 64%),
    radial-gradient(620px 300px at 92% 10%, rgba(201, 162, 74, .07), transparent 62%),
    #f4f6f8;
}
.pvi-wrap { max-width: 1120px; margin: 0 auto; }

/* ── 首屏：7 / 5 非对称，左侧大字，右侧三块不同形状 ── */
.pvi-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(260px, .65fr);
  gap: 56px;
  align-items: start;
  padding-bottom: 54px;
  border-bottom: 1px solid #dbe2de;
}
.pvi-kicker {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: .12em;
  color: #7c8d84;
  margin-bottom: 20px;
}
.pvi-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(38px, 5vw, 66px);
  line-height: .98;
  letter-spacing: -.03em;
  color: #17221c;
}
.pvi-title span { display: block; }
.pvi-title-big {
  margin-left: 8%;
  font-size: 1.55em;
  line-height: .9;
}
.pvi-lede {
  margin-top: 26px;
  max-width: 520px;
  font-size: 14.5px;
  line-height: 1.9;
  color: #68756e;
}
.pvi-hero-foot {
  margin-top: 28px;
  display: flex;
  align-items: center;
  gap: 14px;
}
.pvi-stamp {
  display: inline-flex;
  align-items: center;
  padding: 7px 14px;
  border: 1px solid #c9a24a;
  color: #8b6a1f;
  font-size: 12px;
  letter-spacing: .18em;
  transform: rotate(-2deg);
  background: rgba(255, 255, 255, .55);
}
.pvi-hero-note { font-size: 12px; color: #8b9790; }

.pvi-hero-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 8px;
}
.pvi-side-block {
  padding: 18px 18px 16px;
  border: 1px solid #dde4e0;
  background: rgba(255, 255, 255, .55);
}
.pvi-side-block--count {
  border-left: 3px solid #2f6b58;
  background: #fff;
}
.pvi-side-block--device {
  margin-right: 18px;
  border-style: dashed;
  background: transparent;
}
.pvi-side-block--rules {
  margin-left: 18px;
  background: #17221c;
  border-color: #17221c;
  color: #dfe9e3;
}
.pvi-side-n {
  font-family: var(--font-mono);
  font-size: 44px;
  line-height: .9;
  letter-spacing: -.08em;
  color: #17221c;
}
.pvi-side-l {
  display: inline-block;
  margin-left: 8px;
  font-size: 11px;
  letter-spacing: .14em;
  color: #7e8d85;
}
.pvi-side-block--rules .pvi-side-l { color: #9fb3a8; margin-left: 0; }
.pvi-side-block p {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
  color: #6f7c75;
}
.pvi-side-block--rules p { color: #c6d5cc; }
.pvi-side-block ul {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}
.pvi-side-block li {
  position: relative;
  padding-left: 13px;
  font-size: 12px;
  line-height: 1.9;
  color: #c6d5cc;
}
.pvi-side-block li::before {
  content: '';
  position: absolute;
  left: 0;
  top: .8em;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #c9a24a;
}

/* ── 目录：行式，不是卡片墙；偶数行缩进、缩略图换边 ── */
.pvi-contents { padding-top: 44px; }
.pvi-contents-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}
.pvi-contents-head h2 {
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 500;
  color: #17221c;
}
.pvi-contents-head span { font-size: 11px; color: #96a19a; letter-spacing: .06em; }

.pvi-entry {
  display: grid;
  grid-template-columns: 64px minmax(0, 1.2fr) minmax(180px, .55fr) 28px;
  gap: 26px;
  align-items: center;
  padding: 26px 0;
  border-top: 1px solid #dfe5e2;
  text-decoration: none;
  color: inherit;
  transition: background .2s, padding .2s;
}
.pvi-entry:first-of-type { border-top-color: #cbd6d0; }
.pvi-entry--flip {
  grid-template-columns: 64px minmax(180px, .55fr) minmax(0, 1.2fr) 28px;
  padding-left: 34px;
}
.pvi-entry-n { grid-column: 1; }
.pvi-entry-tx { grid-column: 2; }
.pvi-entry-thumb { grid-column: 3; }
.pvi-entry-go { grid-column: 4; }
.pvi-entry--flip .pvi-entry-thumb { grid-column: 2; }
.pvi-entry--flip .pvi-entry-tx { grid-column: 3; }
.pvi-entry:hover { background: rgba(255, 255, 255, .6); }
.pvi-entry--featured {
  padding-top: 34px;
  padding-bottom: 34px;
}
.pvi-entry-n {
  font-family: var(--font-mono);
  font-size: 15px;
  color: #99a69f;
  letter-spacing: -.02em;
  align-self: start;
  padding-top: 4px;
}
.pvi-entry-tags { display: flex; gap: 7px; flex-wrap: wrap; }
.pvi-entry-tags span {
  font-size: 10px;
  color: #6b7c72;
  border: 1px solid #dbe5df;
  background: rgba(255, 255, 255, .6);
  padding: 3px 8px;
  border-radius: 999px;
}
.pvi-entry-tags span:last-child:not(:first-child) {
  color: #8b6a1f;
  border-color: #ecd9ad;
  background: #fdf7e8;
}
.pvi-entry-tx h3 {
  margin-top: 8px;
  font-family: var(--font-display);
  font-size: 21px;
  font-weight: 500;
  color: #1a251f;
}
.pvi-entry-tx p {
  margin-top: 7px;
  max-width: 520px;
  font-size: 12.5px;
  line-height: 1.75;
  color: #7a8780;
}
.pvi-entry-go {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border: 1px solid #d6dfda;
  border-radius: 50%;
  color: #5d7066;
  transition: transform .2s, background .2s, border-color .2s;
}
.pvi-entry:hover .pvi-entry-go {
  transform: translateX(3px);
  background: #1d2b24;
  border-color: #1d2b24;
  color: #fff;
}

/* ── 目录缩略：五种不同形状，不做同一张卡复制五遍 ── */
.pvi-entry-thumb {
  min-height: 82px;
  border: 1px solid #e2e7e4;
  background: #fff;
  padding: 12px;
  overflow: hidden;
}
.pvi-thumb--member { background: #121316; border-color: #2a2822; }
.pvi-thumb--brand { background: #faf8f2; }
.pvi-thumb--seckill { background: #fbf8f6; }
.pvi-m-line { height: 5px; border-radius: 3px; background: #e3e9e5; }
.pvi-m-line.w60 { width: 60%; }
.pvi-m-price {
  margin-top: 11px;
  display: flex;
  align-items: baseline;
  gap: 7px;
}
.pvi-m-price s { font-size: 9px; color: #a8b1ac; }
.pvi-m-price b { font-family: var(--font-mono); font-size: 19px; color: #1d3028; letter-spacing: -.04em; }
.pvi-m-price em {
  margin-left: auto;
  font-style: normal;
  font-size: 9px;
  color: #2c7a5b;
  background: #e4f5ec;
  padding: 2px 6px;
  border-radius: 999px;
}
.pvi-m-chips { display: flex; gap: 5px; margin-top: 9px; }
.pvi-m-chips i {
  font-style: normal;
  font-size: 9px;
  color: #728078;
  border: 1px solid #e0e7e3;
  border-radius: 4px;
  padding: 2px 6px;
}
.pvi-m-brand { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.pvi-m-brand i { display: block; height: 30px; border-radius: 5px; border: 1px solid #e4e0d5; background: #fff; }
.pvi-m-brand .grey { background: #f0f2f1; border-color: #e2e6e4; }
.pvi-m-brand .blue { background: #eef5fd; border-color: #d9e8f8; }
.pvi-m-brand .red { background: #fdf0ed; border-color: #f6d8d1; }
.pvi-m-brand .gold { background: linear-gradient(145deg, #241e13, #0e0d0b); border-color: #c9a24a; }
.pvi-m-member { padding: 4px; }
.pvi-m-member .dot {
  display: block; width: 18px; height: 18px; border-radius: 50%;
  background: linear-gradient(135deg, #f4d98a, #a47b28);
}
.pvi-m-member .line { display: block; width: 40%; height: 4px; margin-top: 7px; border-radius: 3px; background: rgba(244, 217, 138, .45); }
.pvi-m-member .bar { display: block; height: 3px; margin-top: 9px; border-radius: 3px; background: rgba(255, 255, 255, .12); }
.pvi-m-member .bar i { display: block; width: 68%; height: 100%; border-radius: 3px; background: linear-gradient(90deg, #a47b28, #f4d98a); }
.pvi-m-member .cells { display: flex; gap: 4px; margin-top: 9px; }
.pvi-m-member .cells i { flex: 1; height: 18px; border: 1px solid rgba(212, 175, 55, .18); border-radius: 4px; }
.pvi-m-wallet {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 9px; border-radius: 7px;
  background: linear-gradient(135deg, #103a31, #1a5b4a);
}
.pvi-m-wallet span { font-family: var(--font-mono); font-size: 15px; color: #fff; letter-spacing: -.04em; }
.pvi-m-wallet i { margin-left: auto; font-style: normal; font-size: 8px; color: #0c352b; padding: 3px 7px; border-radius: 999px; background: #a7edcb; }
.pvi-m-tx { display: flex; align-items: center; gap: 6px; margin-top: 6px; padding: 4px 6px; border-radius: 4px; background: #fff; border: 1px solid #e9eeeb; }
.pvi-m-tx .dot { width: 12px; height: 12px; border-radius: 50%; background: #eef1f0; }
.pvi-m-tx .dot.in { background: #dff5e7; }
.pvi-m-tx b { margin-left: auto; font-family: var(--font-mono); font-size: 9px; color: #159258; }
.pvi-m-tx b.dark { color: #313a40; }
.pvi-m-session { display: flex; align-items: center; gap: 6px; }
.pvi-m-session i { width: 6px; height: 6px; border-radius: 50%; background: #ef4b3f; }
.pvi-m-session span { font-size: 10px; color: #514c49; }
.pvi-m-session b { margin-left: auto; font-family: var(--font-mono); font-size: 9px; color: #b44132; }
.pvi-m-products { display: grid; grid-template-columns: repeat(4, 1fr); gap: 5px; margin-top: 8px; }
.pvi-m-products i { display: block; height: 26px; border-radius: 4px; background: #f2e9e4; border: 1px solid #ece2dc; }
.pvi-m-progress { height: 4px; margin-top: 8px; border-radius: 3px; background: #eee7e3; overflow: hidden; }
.pvi-m-progress i { display: block; width: 80%; height: 100%; background: linear-gradient(90deg, #ff8a4c, #ef4b3f); }

.pvi-foot {
  margin-top: 30px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: #8d9891;
}

@media (max-width: 980px) {
  .pvi-page { padding: 36px 24px 56px; }
  .pvi-hero { grid-template-columns: 1fr; gap: 30px; }
  .pvi-hero-side { display: grid; grid-template-columns: 1fr 1fr; }
  .pvi-side-block--device { margin-right: 0; }
  .pvi-side-block--rules { margin-left: 0; grid-column: 1 / -1; }
  .pvi-entry,
  .pvi-entry--flip {
    grid-template-columns: 46px minmax(0, 1fr) 150px 26px;
    gap: 16px;
    padding-left: 0;
  }
  .pvi-entry-n,
  .pvi-entry-tx,
  .pvi-entry-thumb,
  .pvi-entry-go,
  .pvi-entry--flip .pvi-entry-tx,
  .pvi-entry--flip .pvi-entry-thumb { grid-column: auto; }
}
@media (max-width: 680px) {
  .pvi-page { padding: 30px 16px 48px; }
  .pvi-hero-side { grid-template-columns: 1fr; }
  .pvi-entry,
  .pvi-entry--flip {
    grid-template-columns: 36px minmax(0, 1fr);
    row-gap: 12px;
  }
  .pvi-entry-thumb,
  .pvi-entry--flip .pvi-entry-thumb { grid-column: 2; width: 100%; }
  .pvi-entry-tx,
  .pvi-entry--flip .pvi-entry-tx { grid-column: 2; }
  .pvi-entry-go { display: none; }
  .pvi-title-big { margin-left: 4%; }
}
</style>
