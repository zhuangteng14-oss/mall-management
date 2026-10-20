<template>
  <main class="pb-page">
    <!-- 首屏：左文右证，右侧做成一张浅色「资质牌」而不是又一张统计卡 -->
    <section class="pb-hero">
      <div class="pb-hero-tx">
        <p class="pb-kicker">商家端 · 认证中心</p>
        <h1 class="pb-title">品牌，<br />是第二张营业执照。</h1>
        <p class="pb-lede">
          品牌加盟不是一个徽标，而是一整套买家可见的身份：品牌专区、商品品牌标、
          搜索加权与售后优先。这里把申请、审核、驳回、开通四种状态一次摆出来。
        </p>
        <div class="pb-hero-meta">
          <span><b>3</b> 个工作日审核</span>
          <span><b>0</b> 元年费</span>
          <span><b>8</b> 项品牌权益</span>
        </div>
      </div>

      <aside class="pb-plate" aria-label="品牌资质牌示例">
        <div class="pb-plate-top">
          <PvIcon name="shield" :size="15" />
          <span>品牌加盟资质牌</span>
          <em>示例</em>
        </div>
        <div class="pb-plate-body">
          <div class="pb-plate-seal">
            <span>S</span>
            <i>品牌</i>
          </div>
          <div class="pb-plate-rows">
            <div><span>品牌名称</span><b>山雾 SHEENWOOD</b></div>
            <div><span>品牌编号</span><b>BR-2026-0187</b></div>
            <div><span>开通日期</span><b>2026-08-12</b></div>
            <div><span>状态</span><b class="pb-gold">已开通</b></div>
          </div>
        </div>
        <div class="pb-plate-foot">
          <span>NO. 0187</span>
          <span>MALL BRAND PROGRAM</span>
        </div>
      </aside>
    </section>

    <!-- 四态拼贴：已开通占 7×2，审核中/已驳回各占 5×1，未申请是整幅横条 -->
    <section class="pb-states">
      <header class="pb-states-head">
        <div>
          <h2>四种状态</h2>
          <p>同一张卡在不同阶段应该说不同的话，而不是只换一个颜色。</p>
          <p class="pb-source">当前账号：{{ currentStateText }} · {{ sourceLabel }}</p>
        </div>
        <div class="pb-legend">
          <span><i class="dot none"></i>未申请</span>
          <span><i class="dot pending"></i>审核中</span>
          <span><i class="dot rejected"></i>已驳回</span>
          <span><i class="dot active"></i>已开通</span>
        </div>
      </header>

      <div class="pb-mosaic">
        <!-- 已开通：金边深绿，信息密度最高 -->
        <article class="pb-card pb-card--active">
          <div class="pb-active-top">
            <span class="pb-active-badge"><PvIcon name="crown" :size="13" />已开通</span>
            <span class="pb-active-no">{{ activeBrandNo }}</span>
          </div>
          <div class="pb-active-brand">
            <div class="pb-active-mark">{{ activeInitial }}</div>
            <div>
              <h3>{{ activeBrandName }}</h3>
              <p>{{ activeBrandSub }}</p>
            </div>
          </div>
          <p class="pb-active-copy">
            {{ liveProfile
              ? '品牌档案已生效，商品卡与详情页将展示品牌标。'
              : '品牌专区已上线，24 件商品带品牌标。搜索「山雾」时店铺排在自然结果前三位。' }}
          </p>
          <div class="pb-active-stats">
            <div><b>{{ liveProfile ? '—' : '24' }}</b><span>品牌商品</span></div>
            <div><b>{{ liveProfile ? '—' : '8.6 万' }}</b><span>专区曝光</span></div>
            <div><b>{{ liveProfile ? '—' : '¥42,180' }}</b><span>本月成交</span></div>
          </div>
          <dl class="pb-active-files">
            <div><dt>主营类目</dt><dd>{{ activeCategory }}</dd></div>
            <div><dt>品牌定位</dt><dd>{{ activeLevel }}</dd></div>
            <div><dt>授权期限</dt><dd>{{ activeValidTo }}</dd></div>
          </dl>
          <p class="pb-active-review">{{ activeReviewText }}</p>
          <p v-if="!liveProfile" class="pb-active-quote">
            “让气味成为家里的第一件家具。”
            <span>品牌主理人 · 林晚</span>
          </p>
          <div class="pb-active-link">
            <span>品牌专区</span>
            <code>mall.example/brand/sheenwood</code>
            <button type="button" @click="notify('静态预览：不会复制链接')">复制链接</button>
          </div>
          <div class="pb-active-foot">
            <button type="button" class="pb-btn pb-btn--gold" @click="notify('静态预览：不会进入品牌管理')">
              品牌管理
            </button>
            <button type="button" class="pb-btn pb-btn--outline" @click="notify('静态预览：不会跳转品牌专区')">
              查看品牌专区
            </button>
          </div>
          <span class="pb-active-seal">已开通</span>
        </article>

        <!-- 审核中：时间线是真实序列，才用步骤点 -->
        <article class="pb-card pb-card--pending">
          <div class="pb-card-head">
            <span class="pb-chip pb-chip--pending"><PvIcon name="clock" :size="12" />审核中</span>
            <span class="pb-card-time">提交于 {{ pendingSubmittedAt }}</span>
          </div>
          <h3 class="pb-card-title">山雾 SHEENWOOD</h3>
          <p class="pb-card-sub">品牌授权书与商标注册证已上传，等待平台核验。</p>
          <ol class="pb-timeline">
            <li class="done">
              <i><PvIcon name="check" :size="10" :stroke="2.6" /></i>
              <div><b>资料已提交</b><span>10-19 09:24</span></div>
            </li>
            <li class="current">
              <i></i>
              <div><b>资质审核</b><span>预计 10-21 前完成</span></div>
            </li>
            <li>
              <i></i>
              <div><b>开通品牌权益</b><span>审核通过后自动生效</span></div>
            </li>
          </ol>
          <div class="pb-pending-foot">
            <span>审核期间店铺正常经营，不影响现有商品。</span>
            <button type="button" class="pb-link" @click="notify('静态预览：不会催办')">催一下</button>
          </div>
        </article>

        <!-- 已驳回：警告框 + 一条可执行的修改建议 -->
        <article class="pb-card pb-card--rejected">
          <div class="pb-card-head">
            <span class="pb-chip pb-chip--rejected"><PvIcon name="alert" :size="12" />已驳回</span>
            <span class="pb-card-time">{{ rejectedReviewedAt }}</span>
          </div>
          <h3 class="pb-card-title">资质未通过</h3>
          <div class="pb-warn">
            <b>{{ liveRejected ? '平台驳回理由' : '品牌授权书有效期不足 6 个月' }}</b>
            <p>{{ liveRejected
              ? (rejectReason || '平台未填写驳回理由，请联系招商经理。')
              : '当前授权截止 2027-01-31，平台要求剩余有效期不少于 6 个月。请更新授权书后重新提交。' }}</p>
          </div>
          <dl class="pb-reject-meta">
            <div><dt>驳回项</dt><dd>{{ liveRejected ? '见上方理由' : '品牌授权书' }}</dd></div>
            <div><dt>可重申次数</dt><dd>{{ liveRejected ? remainingAttemptsText : '不限' }}</dd></div>
            <div><dt>已占用审核</dt><dd>{{ attemptsText }}</dd></div>
          </dl>
          <div class="pb-reject-foot">
            <button type="button" class="pb-btn pb-btn--red" @click="openApply('重新提交')">修改并重新提交</button>
            <button type="button" class="pb-link" @click="notify('静态预览：不会打开规则说明')">查看审核规则</button>
          </div>
        </article>

        <!-- 未申请：整幅横条，形状与上面三块刻意不同 -->
        <article class="pb-none">
          <div class="pb-none-icon"><PvIcon name="medal" :size="22" /></div>
          <div class="pb-none-tx">
            <h3>还没有申请品牌加盟</h3>
            <p>提交营业执照、品牌授权书与商标注册证，审核通过后即可获得品牌标与专区入口。</p>
          </div>
          <div class="pb-none-tags">
            <span>品牌标</span><span>品牌专区</span><span>搜索加权</span><span>售后优先</span>
          </div>
          <button type="button" class="pb-btn pb-btn--solid" @click="openApply('品牌加盟申请')">
            开始申请
          </button>
        </article>
      </div>
    </section>

    <!-- 资质档案：行式表格，与上面的拼贴区形成节奏差 -->
    <section class="pb-files">
      <header class="pb-files-head">
        <div>
          <h2>资质档案</h2>
          <p>已开通品牌也需要定期复核，到期前 30 天会收到站内信。</p>
        </div>
        <span class="pb-files-count">4 份文件 · 3 份有效</span>
      </header>
      <div class="pb-table">
        <div class="pb-tr pb-th">
          <span>文件</span><span>编号 / 版本</span><span>有效期</span><span>状态</span><span></span>
        </div>
        <div v-for="file in files" :key="file.name" class="pb-tr">
          <span class="pb-td-name">
            <span class="pb-file-ic"><PvIcon :name="file.icon" :size="15" /></span>
            <b>{{ file.name }}</b>
          </span>
          <span class="pb-mono">{{ file.no }}</span>
          <span class="pb-mono">{{ file.expiry }}</span>
          <span>
            <em class="pb-state" :class="file.tone">{{ file.state }}</em>
          </span>
          <span class="pb-td-act">
            <button type="button" class="pb-link" @click="notify('静态预览：不会打开文件')">查看</button>
          </span>
        </div>
      </div>
    </section>

    <!-- 开通之后：浅色横幅，四个权益横排；与上方表格、下方说明都不同 -->
    <section class="pb-after">
      <div class="pb-after-lead">
        <span class="pb-after-kicker">开通之后</span>
        <h2>买家会在四个地方<br />反复看到你的品牌。</h2>
      </div>
      <div class="pb-after-list">
        <div v-for="item in after" :key="item.title">
          <span class="pb-after-ic"><PvIcon :name="item.icon" :size="17" /></span>
          <b>{{ item.title }}</b>
          <p>{{ item.desc }}</p>
        </div>
      </div>
    </section>

    <!-- 申请弹窗：只做静态演示 -->
    <teleport to="body">
      <transition name="pb-fade">
        <div v-if="applyOpen" class="pb-modal-root">
          <div class="pb-mask" @click="applyOpen = false"></div>
          <div class="pb-modal" role="dialog" aria-label="品牌加盟申请">
            <header class="pb-modal-head">
              <div>
                <h3>{{ applyTitle }}</h3>
                <p>审核 3 个工作日内完成，期间不影响店铺经营。</p>
              </div>
              <button type="button" class="pb-icon-btn" aria-label="关闭" @click="applyOpen = false">
                <PvIcon name="close" :size="17" />
              </button>
            </header>
            <div class="pb-modal-body">
              <label class="pb-field">
                <span>品牌名称</span>
                <input v-model="form.brand" type="text" placeholder="例如：山雾 SHEENWOOD" />
              </label>
              <label class="pb-field">
                <span>品牌授权书编号</span>
                <input v-model="form.license" type="text" placeholder="例如：AUTH-2026-0187" />
              </label>
              <label class="pb-field">
                <span>授权有效期至</span>
                <input v-model="form.expiry" type="text" placeholder="2027-12-31" />
              </label>
              <div class="pb-upload">
                <PvIcon name="upload" :size="20" />
                <b>拖入或点击上传品牌授权书</b>
                <span>PDF / JPG，不超过 10MB；剩余有效期需 ≥ 6 个月</span>
              </div>
            </div>
            <footer class="pb-modal-foot">
              <span>{{ canSubmitApply ? '提交后进入平台审核队列' : '未登录 · 不会发起请求' }}</span>
              <div>
                <button type="button" class="pb-btn pb-btn--ghost" @click="applyOpen = false">取消</button>
                <button type="button" class="pb-btn pb-btn--solid" @click="submitApply">提交申请</button>
              </div>
            </footer>
          </div>
        </div>
      </transition>
    </teleport>

    <transition name="pb-toast">
      <div v-if="toast" class="pb-toast">{{ toast }}</div>
    </transition>
  </main>
</template>

<script setup>
// ⚠️ 2026-10-19（第三批㉗ · 真实数据接入）：本页仍是**四态同屏**的预览页
//    （`/preview/brand`），四张卡一张未删 —— 新增的只有「当前账号处于哪一态」这条数据线：
//      ① `POST /merchant/brand/overview`（登录名判身份）→ 顶部状态行 + 已开通卡的真实档案字段；
//      ② `POST /merchant/brand/apply` → 弹窗提交真实申请（未登录时保持「静态预览」）。
//    ⚠️ 未登录 / 未连接后端 ⇒ 整页回落到下面的示例数据，与改造前逐字一致。
import { computed, onMounted, reactive, ref } from 'vue'
import PvIcon from '../../components/preview/PvIcon.vue'
import { brandApply, brandOverview } from '../../api/brand'
import { getUsername } from '../../api/auth'

const files = [
  { name: '营业执照', icon: 'document', no: '91330106MA2B7X8K2P', expiry: '长期', state: '有效', tone: 'ok' },
  { name: '品牌授权书', icon: 'shield', no: 'AUTH-2026-0187 · v2', expiry: '2027-12-31', state: '有效', tone: 'ok' },
  { name: '商标注册证', icon: 'medal', no: 'TM-2024-55621', expiry: '2034-05-18', state: '有效', tone: 'ok' },
  { name: '质检报告', icon: 'document', no: 'QC-2026-0921', expiry: '2026-11-02', state: '30 天内到期', tone: 'warn' },
]

const after = [
  { icon: 'bag', title: '商品品牌标', desc: '商品卡与详情页展示品牌徽标，和普通商品一眼区分。' },
  { icon: 'star', title: '品牌专区', desc: '获得独立聚合页，首页品牌专区与站内搜索均可直达。' },
  { icon: 'trend', title: '搜索加权', desc: '同关键词下品牌商品优先进入自然结果前三位。' },
  { icon: 'headset', title: '售后优先', desc: '品牌商家的售后申请进入优先队列，减少等待。' },
]

const applyOpen = ref(false)
const applyTitle = ref('品牌加盟申请')
const applying = ref(false)
const form = reactive({
  brand: '山雾 SHEENWOOD',
  license: 'AUTH-2026-0187',
  expiry: '2027-12-31',
})
const toast = ref('')
let timer = null

// ── 真实概览（未登录 / 未连上时保持示例数据）────────────────────────────────────
const overview = ref(null)
const liveProfile = computed(() => (overview.value && overview.value.profile) || null)
const liveRejected = computed(() => !!(overview.value && overview.value.state === 'rejected'))
const sourceLabel = computed(() => (overview.value ? '实时数据 · 当前账号' : '示例数据 · 未登录或未连接'))
const currentStateText = computed(() => (overview.value ? stateText(overview.value.state) : '未读取'))
const canSubmitApply = computed(() => !!getUsername())

const activeBrandName = computed(() => (liveProfile.value && liveProfile.value.BrandName) || '山雾 SHEENWOOD')
const activeBrandNo = computed(() => (liveProfile.value && liveProfile.value.MerchantId) || 'BR-2026-0187')
const activeInitial = computed(() => String(activeBrandName.value).slice(0, 1) || 'S')
const activeBrandSub = computed(() => {
  const p = liveProfile.value
  if (!p) return '家居香氛 · 原创设计品牌'
  return [p.MainCategory, p.BrandLevel].filter(Boolean).join(' · ') || '品牌商家'
})
const activeCategory = computed(() => (liveProfile.value && liveProfile.value.MainCategory) || '家居香氛')
const activeLevel = computed(() => (liveProfile.value && liveProfile.value.BrandLevel) || '原创设计')
const activeValidTo = computed(() => {
  const to = liveProfile.value && liveProfile.value.ValidTo
  return to ? String(to).slice(0, 10) : (liveProfile.value ? '长期有效' : '2027-12-31')
})
const activeReviewText = computed(() => {
  const p = liveProfile.value
  if (!p) return '最近复核 2026-08-12 · 下一轮 2027-08-12 · 由平台品牌组处理'
  const approved = p.ApprovedAt ? String(p.ApprovedAt).slice(0, 10) : '—'
  return `开通于 ${approved} · 有效期至 ${activeValidTo.value} · 由平台品牌组处理`
})
const pendingSubmittedAt = computed(() => {
  const p = overview.value && overview.value.pendingApplication
  return (p && shortTime(p.SubmittedAt)) || '10-19 09:24'
})
const rejectedReviewedAt = computed(() => {
  const a = overview.value && overview.value.application
  return (a && shortTime(a.ReviewedAt)) || '10-18 16:40'
})
const rejectReason = computed(() => (overview.value && overview.value.rejectReason) || '')
const attemptsText = computed(() => {
  const o = overview.value
  if (!o) return '1 / 3 次'
  return `${o.attemptsInWindow} / ${o.maxAttemptsInWindow} 次`
})
const remainingAttemptsText = computed(() => {
  const o = overview.value
  if (!o) return '不限'
  const left = Math.max(0, Number(o.maxAttemptsInWindow || 0) - Number(o.attemptsInWindow || 0))
  return o.canApply ? `还可申请 ${left} 次` : (o.nextApplyAt ? `可于 ${shortTime(o.nextApplyAt)} 后申请` : '暂不可申请')
})

onMounted(loadOverview)

function stateText(state) {
  return { active: '已开通', pending: '审核中', rejected: '已驳回', none: '未申请' }[state] || '未申请'
}

/** `2026-10-19 09:24:00` → `10-19 09:24`。 */
function shortTime(value) {
  const m = String(value || '').match(/^\d{4}-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/)
  return m ? `${m[1]}-${m[2]} ${m[3]}:${m[4]}` : String(value || '')
}

async function loadOverview() {
  const username = getUsername()
  if (!username) return
  try {
    const res = await brandOverview(username)
    if (res && res.success) overview.value = res
  } catch (e) {
    // fail-open：公开预览页不因后端失败阻塞渲染。
    console.warn('[BrandPreview] 品牌概览读取失败，沿用示例数据', e)
  }
}

function openApply(title) {
  const o = overview.value
  if (o && !o.canApply) {
    notify(o.msg || '当前不能申请品牌加盟')
    return
  }
  applyTitle.value = title
  applyOpen.value = true
}

async function submitApply() {
  const username = getUsername()
  const brandName = (form.brand || '').trim()
  if (!username) {
    applyOpen.value = false
    notify('示例表单：未登录，申请未提交')
    return
  }
  if (!brandName) { notify('请填写品牌名称'); return }
  if (applying.value) return
  applying.value = true
  try {
    const res = await brandApply({
      username,
      brandName,
      licenseNo: form.license,
      // ⚠️ 本端点不收「授权有效期」入参（`LicenseExpiry` 不在 Controller 参数表里）；
      //    页面这一格不能丢，先并入 `remark` 保留信息，别让用户白填。
      remark: form.expiry ? `授权有效期至 ${form.expiry}` : '',
    })
    applyOpen.value = false
    notify((res && res.msg) || (res && res.success ? '品牌加盟申请已提交' : '申请未通过'))
    if (res && res.success) await loadOverview()
  } catch (e) {
    console.warn('[BrandPreview] 品牌申请提交失败', e)
    notify('申请提交失败，请稍后重试')
  } finally {
    applying.value = false
  }
}

function notify(text) {
  toast.value = text
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { toast.value = '' }, 1800)
}
</script>

<style scoped>
.pb-page {
  max-width: 1060px;
  margin: 0 auto;
  padding: 44px 38px 68px;
  color: #1a241e;
}

/* ── 首屏 ── */
.pb-hero {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 30px;
  align-items: stretch;
  padding-bottom: 46px;
  margin-bottom: 50px;
  border-bottom: 1px solid #dde4e0;
}
.pb-hero-tx { grid-column: span 7; }
.pb-kicker { font-size: 11px; letter-spacing: .1em; color: #7f8e86; margin-bottom: 18px; }
.pb-title {
  font-family: var(--font-display);
  font-size: clamp(36px, 4.6vw, 56px);
  font-weight: 500;
  line-height: 1.08;
  letter-spacing: -.03em;
}
.pb-lede {
  margin-top: 22px;
  max-width: 500px;
  font-size: 14px;
  line-height: 1.9;
  color: #6c7972;
}
.pb-hero-meta {
  display: flex;
  gap: 26px;
  margin-top: 26px;
  padding-top: 18px;
  border-top: 1px dashed #dce4df;
}
.pb-hero-meta span { font-size: 11.5px; color: #7d8a83; }
.pb-hero-meta b {
  font-family: var(--font-mono);
  font-size: 18px;
  margin-right: 5px;
  color: #24322b;
  letter-spacing: -.04em;
}

/* 浅色资质牌：双线边框 + 印章，和深色会员卡区分开 */
.pb-plate {
  grid-column: span 5;
  align-self: center;
  background: #fbf7ea;
  border: 1px solid #d9c58c;
  outline: 1px solid #efe3c4;
  outline-offset: -6px;
  padding: 14px 18px 12px;
  box-shadow: 0 20px 40px -30px rgba(84, 62, 18, .45);
}
.pb-plate-top {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-bottom: 10px;
  border-bottom: 1px solid #e4d6b2;
  font-size: 10.5px;
  letter-spacing: .08em;
  color: #8b732f;
}
.pb-plate-top em {
  margin-left: auto;
  font-style: normal;
  font-size: 9px;
  border: 1px solid #d9c58c;
  padding: 1px 5px;
  color: #a08a4e;
}
.pb-plate-body {
  display: grid;
  grid-template-columns: 74px 1fr;
  gap: 16px;
  padding: 16px 2px 14px;
  align-items: center;
}
.pb-plate-seal {
  width: 68px;
  height: 68px;
  border-radius: 50%;
  border: 1px solid #b99b4d;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #8b6a1f;
  background: radial-gradient(circle at 35% 30%, #fffdf6, #f6ecd1);
}
.pb-plate-seal span { font-family: var(--font-display); font-size: 26px; line-height: 1; }
.pb-plate-seal i { font-style: normal; font-size: 8px; letter-spacing: .3em; margin-top: 3px; margin-left: 3px; }
.pb-plate-rows div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 4px 0;
  font-size: 10.5px;
}
.pb-plate-rows span { color: #9a8856; }
.pb-plate-rows b { color: #3d3a2e; font-weight: 600; font-family: var(--font-mono); font-size: 10.5px; }
.pb-plate-rows b.pb-gold { color: #8b6a1f; }
.pb-plate-foot {
  display: flex;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid #e4d6b2;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: .1em;
  color: #ab9a70;
}

/* ── 四态拼贴 ── */
.pb-states { margin-bottom: 50px; }
.pb-states-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}
.pb-states-head h2 { font-family: var(--font-display); font-size: 24px; font-weight: 500; }
.pb-states-head p { margin-top: 5px; font-size: 11.5px; color: #8d9992; }
.pb-states-head p.pb-source { color: #2f6b58; }
.pb-legend { display: flex; gap: 13px; flex-wrap: wrap; }
.pb-legend span { display: inline-flex; align-items: center; gap: 5px; font-size: 10.5px; color: #7d8a83; }
.pb-legend .dot { width: 6px; height: 6px; border-radius: 50%; }
.dot.none { background: #b9c2bd; }
.dot.pending { background: #5b8fc9; }
.dot.rejected { background: #c9614e; }
.dot.active { background: #c9a24a; }

.pb-mosaic {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 16px;
}
.pb-card {
  padding: 20px 22px;
  border: 1px solid #dfe6e2;
  background: #fff;
}

/* 已开通：7 列 × 2 行，深绿黑金，信息最密 */
.pb-card--active {
  grid-column: span 7;
  grid-row: span 2;
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-color: #b99b4d;
  background:
    radial-gradient(420px 220px at 100% 0%, rgba(201, 162, 74, .16), transparent 70%),
    linear-gradient(155deg, #1d2a22, #101711 62%);
  color: #e9eadf;
  padding: 24px 26px 22px;
}
.pb-card--active::after {
  content: '';
  position: absolute;
  inset: 6px;
  border: 1px solid rgba(201, 162, 74, .28);
  pointer-events: none;
}
.pb-active-top { display: flex; align-items: center; justify-content: space-between; }
.pb-active-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid rgba(201, 162, 74, .55);
  color: #e6c87d;
  font-size: 10.5px;
  letter-spacing: .06em;
  background: rgba(201, 162, 74, .08);
}
.pb-active-no { font-family: var(--font-mono); font-size: 9px; letter-spacing: .06em; color: #8f9b8d; }
.pb-active-brand { display: flex; align-items: center; gap: 13px; margin-top: 24px; }
.pb-active-mark {
  width: 48px; height: 48px;
  border: 1px solid rgba(201, 162, 74, .6);
  display: grid; place-items: center;
  font-family: var(--font-display);
  font-size: 25px;
  color: #e6c87d;
  background: rgba(201, 162, 74, .07);
}
.pb-active-brand h3 { font-family: var(--font-display); font-size: 24px; font-weight: 500; color: #f4f1e4; }
.pb-active-brand p { margin-top: 4px; font-size: 11px; color: #93a096; }
.pb-active-copy {
  margin-top: 16px;
  max-width: 420px;
  font-size: 12px;
  line-height: 1.8;
  color: #aeb9af;
}
.pb-active-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  margin-top: 20px;
  background: rgba(201, 162, 74, .2);
  border-top: 1px solid rgba(201, 162, 74, .2);
  border-bottom: 1px solid rgba(201, 162, 74, .2);
}
.pb-active-stats div { padding: 13px 12px 12px 0; background: transparent; }
.pb-active-stats div + div { padding-left: 14px; }
.pb-active-stats b {
  display: block;
  font-family: var(--font-mono);
  font-size: 19px;
  font-weight: 500;
  color: #e9cd8a;
  letter-spacing: -.04em;
}
.pb-active-stats span { display: block; margin-top: 4px; font-size: 10px; color: #8f9f93; }
.pb-active-files {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  margin: 14px 0 0;
}
.pb-active-files div { padding: 9px 12px 8px 0; border-top: 1px dashed rgba(212, 175, 55, .2); }
.pb-active-files dt { font-size: 9px; color: #84958a; }
.pb-active-files dd { margin-top: 4px; font-size: 11px; color: #d9d3bf; }
.pb-active-review {
  margin-top: 7px;
  font-size: 9px;
  letter-spacing: .02em;
  color: #7f9084;
}
.pb-active-quote {
  margin-top: 14px;
  padding-left: 11px;
  border-left: 1px solid rgba(201, 162, 74, .45);
  font-family: var(--font-display);
  font-size: 13.5px;
  line-height: 1.5;
  color: #d3cab1;
}
.pb-active-quote span {
  display: block;
  margin-top: 3px;
  font-family: var(--font);
  font-size: 9px;
  color: #7f9084;
}
.pb-active-link {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding: 8px 10px;
  border: 1px solid rgba(212, 175, 55, .16);
  background: rgba(255, 255, 255, .02);
}
.pb-active-link span { font-size: 9px; color: #84958a; }
.pb-active-link code {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 9px;
  color: #c9bfa4;
}
.pb-active-link button {
  flex: none;
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 9px;
  color: #e6c87d;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.pb-active-foot { display: flex; gap: 10px; margin-top: auto; padding-top: 18px; position: relative; z-index: 1; }
.pb-btn {
  height: 36px;
  padding: 0 15px;
  border-radius: 7px;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background .18s, border-color .18s, color .18s;
}
.pb-btn--gold { background: #d8b665; border-color: #d8b665; color: #221d10; }
.pb-btn--gold:hover { background: #e6c87d; }
.pb-btn--outline { background: transparent; border-color: rgba(233, 205, 138, .38); color: #e9cd8a; }
.pb-btn--outline:hover { background: rgba(233, 205, 138, .1); }
.pb-btn--solid { background: #1d2b24; color: #fff; }
.pb-btn--solid:hover { background: #2f6b58; }
.pb-btn--red { background: #b44e3d; color: #fff; }
.pb-btn--red:hover { background: #9c3f30; }
.pb-btn--ghost { background: transparent; border-color: #d9e2dd; color: #586861; }
.pb-btn--ghost:hover { background: #f4f7f5; }
.pb-active-seal {
  position: absolute;
  right: 20px;
  bottom: -4px;
  writing-mode: vertical-rl;
  font-family: var(--font-display);
  font-size: 40px;
  line-height: 1.1;
  letter-spacing: .12em;
  color: rgba(201, 162, 74, .12);
  pointer-events: none;
}

/* 审核中 / 已驳回：5 列各一行 */
.pb-card--pending { grid-column: span 5; background: #f6faff; border-color: #d9e7f6; }
.pb-card--rejected { grid-column: span 5; background: #fdf6f4; border-color: #f0d9d2; }
.pb-card-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.pb-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 10px;
}
.pb-chip--pending { color: #2d659f; background: #e5f0fb; border: 1px solid #cfe2f5; }
.pb-chip--rejected { color: #a1422f; background: #fbe9e5; border: 1px solid #f3d3cc; }
.pb-card-time { font-family: var(--font-mono); font-size: 9.5px; color: #99a49e; }
.pb-card-title { margin-top: 12px; font-family: var(--font-display); font-size: 18px; font-weight: 500; color: #24322b; }
.pb-card-sub { margin-top: 5px; font-size: 11px; line-height: 1.65; color: #859189; }

.pb-timeline { list-style: none; margin: 16px 0 0; padding: 0; }
.pb-timeline li {
  position: relative;
  display: grid;
  grid-template-columns: 18px 1fr;
  gap: 10px;
  padding-bottom: 14px;
}
.pb-timeline li:last-child { padding-bottom: 0; }
.pb-timeline li::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 17px;
  bottom: 0;
  width: 1px;
  background: #d9e5ef;
}
.pb-timeline li:last-child::before { display: none; }
.pb-timeline i {
  width: 17px; height: 17px;
  border-radius: 50%;
  border: 1px solid #cfdde9;
  background: #fff;
  display: grid;
  place-items: center;
  align-self: start;
}
.pb-timeline li.done i { background: #3a7f63; border-color: #3a7f63; color: #fff; }
.pb-timeline li.current i { border-color: #5b8fc9; box-shadow: 0 0 0 4px rgba(91, 143, 201, .12); }
.pb-timeline b { display: block; font-size: 11.5px; font-weight: 600; color: #33443b; }
.pb-timeline li:not(.done):not(.current) b { color: #9aa6a0; }
.pb-timeline span { display: block; margin-top: 3px; font-size: 10px; color: #93a099; }
.pb-pending-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #e1ebf5;
  font-size: 9.5px;
  color: #90a0ac;
}
.pb-link {
  margin-left: auto;
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 11px;
  color: #2f6b58;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.pb-pending-foot .pb-link { flex: none; }

.pb-warn {
  margin-top: 13px;
  padding: 11px 12px;
  border-left: 2px solid #c9614e;
  background: #fbedE9;
}
.pb-warn b { font-size: 11.5px; color: #963d2c; }
.pb-warn p { margin-top: 5px; font-size: 10.5px; line-height: 1.7; color: #a25a4b; }
.pb-reject-meta {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin: 14px 0 0;
}
.pb-reject-meta dt { font-size: 9.5px; color: #a59490; }
.pb-reject-meta dd { margin-top: 3px; font-family: var(--font-mono); font-size: 11px; color: #5c4a45; }
.pb-reject-foot { display: flex; align-items: center; gap: 12px; margin-top: 15px; }
.pb-reject-foot .pb-btn { height: 33px; font-size: 11.5px; }
.pb-reject-foot .pb-link { margin-left: 0; }

/* 未申请：整幅横条 */
.pb-none {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 17px 20px;
  border: 1px dashed #cdd8d2;
  background: rgba(255, 255, 255, .5);
}
.pb-none-icon {
  width: 42px; height: 42px;
  flex: none;
  display: grid; place-items: center;
  border-radius: 50%;
  background: #eef2ef;
  color: #83948a;
}
.pb-none-tx { min-width: 0; }
.pb-none-tx h3 { font-size: 14px; font-weight: 600; color: #58665e; }
.pb-none-tx p { margin-top: 4px; font-size: 11px; line-height: 1.6; color: #98a39c; }
.pb-none-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-left: auto; }
.pb-none-tags span {
  font-size: 9.5px;
  color: #7d8a83;
  border: 1px solid #dfe6e2;
  padding: 3px 8px;
  border-radius: 999px;
}
.pb-none .pb-btn { flex: none; }

/* ── 资质档案表 ── */
.pb-files { margin-bottom: 50px; }
.pb-files-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}
.pb-files-head h2 { font-family: var(--font-display); font-size: 22px; font-weight: 500; }
.pb-files-head p { margin-top: 5px; font-size: 11px; color: #8d9992; }
.pb-files-count { font-family: var(--font-mono); font-size: 10px; color: #98a49d; }
.pb-table { border-top: 1px solid #d8e0dc; }
.pb-tr {
  display: grid;
  grid-template-columns: 2.1fr 1.5fr 1fr .9fr 48px;
  gap: 12px;
  align-items: center;
  padding: 12px 2px;
  border-bottom: 1px solid #e2e8e4;
  font-size: 11.5px;
}
.pb-th {
  padding: 8px 2px;
  font-size: 9.5px;
  letter-spacing: .06em;
  color: #96a19a;
}
.pb-td-name { display: flex; align-items: center; gap: 9px; }
.pb-file-ic {
  width: 28px; height: 28px;
  display: grid; place-items: center;
  border: 1px solid #e0e7e3;
  background: #fff;
  color: #5d7769;
}
.pb-td-name b { font-size: 12px; font-weight: 600; color: #35443c; }
.pb-mono { font-family: var(--font-mono); font-size: 10px; color: #6f7d75; }
.pb-state {
  font-style: normal;
  font-size: 9.5px;
  padding: 3px 7px;
  border-radius: 999px;
}
.pb-state.ok { color: #2b7a58; background: #e9f6ee; }
.pb-state.warn { color: #9a6a18; background: #fbf1db; }
.pb-td-act { text-align: right; }

/* ── 开通之后 ── */
.pb-after {
  display: grid;
  grid-template-columns: 3.5fr 8.5fr;
  gap: 28px;
  padding: 26px 28px;
  border: 1px solid #e4dac2;
  background: #faf6ea;
}
.pb-after-kicker { font-size: 10px; letter-spacing: .1em; color: #9a8856; }
.pb-after-lead h2 {
  margin-top: 10px;
  font-family: var(--font-display);
  font-size: 23px;
  font-weight: 500;
  line-height: 1.35;
  color: #3a3526;
}
.pb-after-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px 24px;
  align-content: center;
}
.pb-after-list div { display: grid; grid-template-columns: 24px 1fr; column-gap: 9px; }
.pb-after-ic { color: #9a7c33; padding-top: 1px; }
.pb-after-list b { font-size: 12px; font-weight: 600; color: #4a4430; }
.pb-after-list p { grid-column: 2; margin-top: 4px; font-size: 10.5px; line-height: 1.65; color: #8a8168; }

/* ── 弹窗 ── */
.pb-modal-root { position: fixed; inset: 0; z-index: 90; }
.pb-mask { position: absolute; inset: 0; background: rgba(18, 26, 22, .36); backdrop-filter: blur(2px); }
.pb-modal {
  position: absolute;
  top: 50%; left: 50%;
  width: min(540px, 92vw);
  max-height: 88vh;
  overflow-y: auto;
  transform: translate(-50%, -50%);
  background: #fff;
  border: 1px solid #e1e7e3;
  box-shadow: 0 30px 80px rgba(15, 30, 22, .22);
}
.pb-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px 16px;
  border-bottom: 1px solid #edf1ee;
}
.pb-modal-head h3 { font-family: var(--font-display); font-size: 22px; font-weight: 500; }
.pb-modal-head p { margin-top: 5px; font-size: 11px; color: #8d9992; }
.pb-icon-btn {
  width: 31px; height: 31px;
  display: grid; place-items: center;
  border: 1px solid #e1e7e3;
  border-radius: 50%;
  background: #fff;
  color: #5f6e66;
  cursor: pointer;
}
.pb-modal-body { padding: 18px 24px 8px; }
.pb-field { display: block; margin-bottom: 14px; }
.pb-field span { display: block; font-size: 11px; font-weight: 600; color: #4c5b53; margin-bottom: 6px; }
.pb-field input {
  width: 100%;
  height: 38px;
  padding: 0 11px;
  border: 1px solid #dce4df;
  border-radius: 7px;
  background: #fbfcfb;
  font-family: var(--font);
  font-size: 12.5px;
  color: #26342c;
  outline: 0;
}
.pb-field input:focus { border-color: #2f6b58; box-shadow: 0 0 0 3px rgba(47, 107, 88, .1); }
.pb-upload {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 18px;
  border: 1px dashed #ccd8d2;
  background: #fafcfb;
  color: #6c8074;
  cursor: pointer;
}
.pb-upload b { font-size: 11.5px; font-weight: 600; color: #4c6155; }
.pb-upload span { font-size: 10px; color: #98a49d; }
.pb-modal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 15px 24px;
  border-top: 1px solid #edf1ee;
  background: #fbfcfb;
}
.pb-modal-foot > span { font-size: 10px; color: #9aa69f; }
.pb-modal-foot > div { display: flex; gap: 9px; }

.pb-fade-enter-active, .pb-fade-leave-active { transition: opacity .2s; }
.pb-fade-enter-from, .pb-fade-leave-to { opacity: 0; }
.pb-fade-enter-active .pb-modal, .pb-fade-leave-active .pb-modal { transition: transform .22s, opacity .22s; }
.pb-fade-enter-from .pb-modal, .pb-fade-leave-to .pb-modal { transform: translate(-50%, -46%); opacity: 0; }

.pb-toast {
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
}
.pb-toast-enter-active, .pb-toast-leave-active { transition: opacity .2s, transform .2s; }
.pb-toast-enter-from, .pb-toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }

@media (max-width: 900px) {
  .pb-page { padding: 30px 22px 52px; }
  .pb-hero-tx, .pb-plate { grid-column: 1 / -1; }
  .pb-plate { max-width: 430px; }
  .pb-card--active { grid-column: 1 / -1; grid-row: auto; }
  .pb-card--pending, .pb-card--rejected { grid-column: 1 / -1; }
  .pb-none { flex-wrap: wrap; }
  .pb-none-tags { margin-left: 58px; }
  .pb-after { grid-template-columns: 1fr; }
  .pb-tr { grid-template-columns: 1.7fr 1.2fr .9fr .9fr 40px; }
}
@media (max-width: 620px) {
  .pb-states-head { flex-direction: column; align-items: flex-start; }
  .pb-after-list { grid-template-columns: 1fr; }
  .pb-none-tags { margin-left: 0; }
  .pb-tr { grid-template-columns: 1.6fr 1fr 40px; }
  .pb-tr > span:nth-child(2), .pb-tr > span:nth-child(3) { display: none; }
  .pb-reject-meta { grid-template-columns: 1fr; gap: 6px; }
  .pb-modal-foot { flex-direction: column; align-items: stretch; }
  .pb-modal-foot > div { justify-content: flex-end; }
}
</style>
