#!/usr/bin/env node
/**
 * seed-demo-gen.mjs —— 生成 `seed-demo.sql`（可视化段 D：演示数据填充）。
 *
 * 用法：
 *   node scripts/seed-demo-gen.mjs            # 写到仓库根 `seed-demo.sql`
 *   node scripts/seed-demo-gen.mjs --check    # 只打印规模统计，不落盘
 *
 * 为什么是「生成器 + 产物」两个文件，而不是手写一份 SQL：
 *   ① 44 件商品 / 40 个用户 / ~180 单 / 配套明细 —— 手写必然不整齐（要么全是 1 件，
 *      要么价格凑整），而「不整齐划一」是本次的硬要求；
 *   ② 随机数来自 `mulberry32(固定种子)` ⇒ **同一天重跑产出逐字相同**（可复核），
 *      换一天重跑只有时间基准变、形状不变。
 *
 * ⚠️ 时间一律写成 **相对式** `DATE_SUB(CURDATE(), INTERVAL k DAY)`（k = 1..49），
 *    所以：任何时候执行都自动落在「最近 49 天」；**永不出现未来日期**（见 `sqlDayStart`）。
 *    代价：具体哪一天是周末由**执行日**决定 —— 这一点不是缺陷，反而是本文件刻意要的：
 *    周末低谷**按执行时的真实星期**算（`DAYOFWEEK`），而不是按生成时的相位钉死。
 *    详见产物里 `order` / `order_item` 那两条 INSERT 的 `WHERE` 子句。
 *
 * 产物设计口径、幂等做法、前置条件，见产物文件头（生成时一并写出）。
 */

import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(HERE, '..', 'seed-demo.sql')
const CHECK_ONLY = process.argv.includes('--check')

/* ==================== 确定性随机 ==================== */

function mulberry32(a) {
  return function () {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rnd = mulberry32(20261011)
const ri = (a, b) => a + Math.floor(rnd() * (b - a + 1))
const pick = (arr) => arr[Math.floor(rnd() * arr.length)]
const chance = (p) => rnd() < p
const shuffle = (arr) => {
  const a = arr.slice()
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1))
    const t = a[i]
    a[i] = a[j]
    a[j] = t
  }
  return a
}
const BASE62 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
const randId = () => {
  let s = BASE62[ri(0, 51)] // 首字符取字母（对齐存量 `uDopHnCeLS` 的形状：不保证，但多数如此）
  for (let i = 0; i < 9; i++) s += BASE62[ri(0, 61)]
  return s
}
const uuid = () => {
  const hex = (n) => Array.from({ length: n }, () => '0123456789abcdef'[ri(0, 15)]).join('')
  return `${hex(8)}-${hex(4)}-4${hex(3)}-${'89ab'[ri(0, 3)]}${hex(3)}-${hex(12)}`
}

/* ==================== SQL 字面量 / 时间表达式 ==================== */

const q = (s) => (s === null || s === undefined ? 'NULL' : `'${String(s).replace(/'/g, "''")}'`)
const n = (v) => String(v)

/** 第 `k` 天前（k>=1）的 00:00:00 起偏移 `sec` 秒 —— 一个 datetime 表达式。 */
const TS = (k, sec) => `DATE_ADD(DATE_SUB(CURDATE(), INTERVAL ${k} DAY), INTERVAL ${sec} SECOND)`
/** 同上，但格式化成 `'YYYY-MM-DD HH:MM:SS'` 字符串 —— 给 `order.CreateTime` 这类 **varchar** 列。
 *  ⚠️ 必须零填充：`StatsDao.aggregateSite` 用 `DATE(CreateTime)` 分期，不零填充的行 `DATE()` 解成
 *     NULL，会被 `GROUP BY` 静默漏掉（订单进不了看板，且**不报错**）。 */
const TSV = (k, sec) => `DATE_FORMAT(${TS(k, sec)}, '%Y-%m-%d %H:%i:%s')`

/* ==================== 主体数据 ==================== */

/** 演示窗口：49 天（30-60 天区间内，奇数天让「7 天周期」不整对齐，形状更好看）。 */
const DAYS = 49

/** 12 家店铺 —— 摊在 5 个既有类目上（电子 3 / 生活 3 / 服装 2 / 美食 2 / 运动 2）。 */
const SHOPS = [
  { un: 'yunqi_digital', shop: '云栖数码旗舰店', contact: '周立', phone: '13801234567', city: '广东省深圳市南山区', cats: [1] },
  { un: 'jixin_digital', shop: '极芯数码专营店', contact: '马骏', phone: '13902345678', city: '广东省东莞市松山湖', cats: [1] },
  { un: 'chenguang_parts', shop: '晨光数码配件', contact: '许乐', phone: '15800123456', city: '湖北省武汉市洪山区', cats: [1] },
  { un: 'shanyin_life', shop: '山隐生活馆', contact: '陈望', phone: '13703456789', city: '浙江省杭州市西湖区', cats: [2] },
  { un: 'beichen_home', shop: '北辰家居日用', contact: '吴清源', phone: '18806789012', city: '北京市朝阳区', cats: [2] },
  { un: 'xinghe_kitchen', shop: '星河厨具', contact: '孙博', phone: '18608901234', city: '山东省青岛市市南区', cats: [2] },
  { un: 'muxi_cloth', shop: '木棉衣舍', contact: '林知夏', phone: '13505678901', city: '江苏省苏州市姑苏区', cats: [3] },
  { un: 'qinghe_cloth', shop: '青禾服饰', contact: '郭倩', phone: '15002345678', city: '浙江省宁波市鄞州区', cats: [3] },
  { un: 'nanfeng_food', shop: '南风食集', contact: '何嘉禾', phone: '13604567890', city: '福建省厦门市思明区', cats: [4] },
  { un: 'luyuan_tea', shop: '麓原茶事', contact: '郑宛', phone: '18907890123', city: '四川省成都市武侯区', cats: [4] },
  { un: 'qingye_sport', shop: '青野运动', contact: '赵一鸣', phone: '18709012345', city: '上海市徐汇区', cats: [5] },
  { un: 'taoran_gear', shop: '陶然运动装备', contact: '黄楠', phone: '15901234567', city: '湖南省长沙市岳麓区', cats: [5] }
]

/** 真实姓名池（生成 24 个客户 / 4 个管理员用）。 */
const SURNAMES = ['王', '李', '张', '刘', '陈', '杨', '黄', '赵', '吴', '周', '徐', '孙', '马', '朱', '胡', '郭', '何', '高', '林', '罗', '郑', '梁', '谢', '宋', '唐', '许', '韩', '冯']
const GIVEN = ['婷', '磊', '静', '敏', '浩', '洋', '艳', '娟', '涛', '明', '超', '平', '刚', '鑫', '波', '斌', '娜', '雪', '畅', '梓涵', '雨欣', '思远', '雅雯', '书瑶', '子墨', '若辰', '佳怡', '俊杰', '嘉豪', '宇轩', '诗涵', '浩宇', '明轩', '梓萱', '睿泽']

/** 客户用户名池（**手工写死**，不用随机拼 —— 随机拼容易出 `aaa111` 这种「像占位符」的号）。 */
const CUSTOMER_NAMES = [
  'wangtt26', 'lijie88', 'zhangm06', 'liuyx15', 'chenh99', 'yangf77', 'huangl31',
  'zhaom42', 'wuzt58', 'zhouj63', 'xuy84', 'sunb19', 'majl71', 'zhut27', 'huj35',
  'guox50', 'hesm91', 'gaoq44', 'linz82', 'luox16', 'zhengw23', 'liangy39', 'xies67',
  'songl74'
]
/** 管理员用户名池（4 个 = 6:3:1 里的那个「1」）。 */
const ADMIN_NAMES = ['ops_yang', 'ops_duan', 'ops_qin', 'ops_wen']

/** 44 件商品 —— 5 类均衡（电子 10 / 生活 10 / 服装 8 / 美食 8 / 运动 8），价格带 19.9 – 1299。 */
const PRODUCTS = [
  // ---- 电子用品 (TypeId 1) ----
  ['无线蓝牙耳机 声谷标准版', 1, 299],
  ['无线蓝牙耳机 声谷 Pro 降噪', 1, 599],
  ['27 英寸 2K 显示器 锐视 S27', 1, 1299],
  ['机械键盘 87 键 茶轴', 1, 329],
  ['Type-C 快充数据线 1.5m', 1, 19.9],
  ['便携蓝牙音箱 防水款', 1, 189],
  ['智能手环 5 代 血氧版', 1, 249],
  ['USB-C 扩展坞 七合一', 1, 159],
  ['静音无线鼠标 双模', 1, 89],
  ['氮化镓充电器 65W 双口', 1, 119],
  // ---- 生活用品 (TypeId 2) ----
  ['304 不锈钢保温杯 500ml', 2, 79],
  ['微纤维浴巾 加厚两条装', 2, 59],
  ['竹纤维四件套 1.8m 床', 2, 269],
  ['陶瓷餐盘 8 英寸 四只装', 2, 99],
  ['香薰蜡烛 雪松木质调', 2, 129],
  ['加厚收纳箱 60L 两件套', 2, 89],
  ['硅胶铲勺厨具八件套', 2, 69],
  ['洗衣凝珠 60 颗装', 2, 45.9],
  ['星河不粘炒锅 32cm', 2, 239],
  ['云感记忆枕 护颈款', 2, 159],
  // ---- 服装 (TypeId 3) ----
  ['男士休闲夹克 春秋薄款', 3, 319],
  ['女士针织开衫 中长款', 3, 219],
  ['纯棉圆领 T 恤 三件装', 3, 129],
  ['直筒牛仔裤 弹力微阔', 3, 199],
  ['羊毛混纺围巾 格纹', 3, 149],
  ['连帽加绒卫衣 380g', 3, 229],
  ['真丝睡裙 夏季薄款', 3, 399],
  ['保暖内衣套装 德绒', 3, 169],
  // ---- 美食 (TypeId 4) ----
  ['云南小粒咖啡豆 中深烘 500g', 4, 89],
  ['武夷山正山小种 罐装 250g', 4, 168],
  ['新疆和田枣 特级 1kg', 4, 59],
  ['手工红糖姜茶 20 条', 4, 39.9],
  ['海盐黄油曲奇 铁盒装', 4, 68],
  ['山核桃仁 罐装 260g', 4, 79.9],
  ['五常稻花香大米 5kg', 4, 79],
  ['潮汕手打牛肉丸 500g', 4, 45],
  // ---- 运动用品 (TypeId 5) ----
  ['加厚防滑瑜伽垫 8mm', 5, 89],
  ['可调节哑铃 20kg 一对', 5, 399],
  ['跑步腰包 防水轻量', 5, 49],
  ['竞速钢丝跳绳', 5, 39.9],
  ['碳素羽毛球拍 对拍', 5, 259],
  ['折叠登山杖 两支装', 5, 189],
  ['速干运动短裤 男款', 5, 119],
  ['运动加压护膝 一对', 5, 69]
]

/** 既有 `goodstype` 的 5 行（种子商品的类目只能落在这 5 个里，否则前端类目筛选挂不上）。 */
const CAT = { 1: '电子用品', 2: '生活用品', 3: '服装', 4: '美食', 5: '运动用品' }

/** 存量演示账号 —— 种子要**顺带**给他们订单/通知，否则用户拿 `1111` / `123` 登进去两手空空。 */
const LEGACY_CUSTOMERS = ['uDopHnCeLS', 'pIzTWafaGp', 'obkb9birqH'] // 1111 / 123 / aaa
const LEGACY_MERCHANTS = ['5N1k5K2guh', '5gtissg7Mj'] // 2222 / 1
const LEGACY_ADMIN = 'adm1nC3r00t'

/** 存量 `login` 里 `CreateTime` 为空的行（种子补上；`WHERE CreateTime IS NULL` 守卫 ⇒ 可重复执行）。
 *  ⚠️ 刻意**不含** `i2probe`（探针残留账号，见本次交付说明）与已非空的 `3333`。 */
const LEGACY_LOGIN_NEEDS_TIME = [
  { un: '1', k: 44, sec: 34121 },
  { un: '1111', k: 41, sec: 45123 },
  { un: '123', k: 38, sec: 51234 },
  { un: '2222', k: 43, sec: 39777 },
  { un: 'aaa', k: 35, sec: 60321 },
  { un: 'admin', k: 48, sec: 28800 },
  { un: '性暗示', k: 36, sec: 55012 }
]

const SELF_INTRO = [
  '喜欢慢慢挑，希望发货快一点。',
  '常买日用和零食，认准的老店会回购。',
  '比较在意细节，收到会认真评价。',
  '给家里买的居多，请帮忙打包结实些。',
  '上班族，只有晚上能看消息。',
  '喜欢尝新，看到合适的就下单。',
  '周末喜欢户外，常看运动装备。',
  '囤货型买家，一次会买几件。',
  '喜欢喝茶，也爱买茶具。',
  '对码数比较敏感，买衣服先问客服。',
  '租房党，喜欢收纳类的小东西。',
  '偶尔买礼物送人，希望包装体面。'
]
const ADDRESS_POOL = [
  '北京市海淀区', '北京市朝阳区', '上海市浦东新区', '上海市徐汇区', '广东省广州市天河区',
  '广东省深圳市福田区', '江苏省南京市鼓楼区', '浙江省杭州市余杭区', '四川省成都市锦江区',
  '湖北省武汉市武昌区', '湖南省长沙市天心区', '山东省青岛市市北区', '福建省厦门市湖里区',
  '陕西省西安市雁塔区', '重庆市渝北区', '辽宁省沈阳市和平区'
]
const MAIL_DOMAINS = ['163.com', '126.com', 'qq.com', 'foxmail.com', 'outlook.com']

/* ==================== 构建：用户 / 店铺 ==================== */

/** 全部 40 个种子账号（24 客户 : 12 商家 : 4 管理员 = 6:3:1）。 */
const seeded = []
const usedIds = new Set()
const newId = () => {
  let s
  do {
    s = randId()
  } while (usedIds.has(s))
  usedIds.add(s)
  return s
}

const person = (un, role) => {
  const gender = chance(0.5) ? '男' : '女'
  const g = ri(0, GIVEN.length - 1)
  return {
    un,
    role, // 1 客户 / 2 商家 / 3 管理员
    id: newId(),
    gender,
    name: SURNAMES[ri(0, SURNAMES.length - 1)] + GIVEN[g],
    age: ri(18, 56),
    email: chance(0.7) ? `${un}@${pick(MAIL_DOMAINS)}` : null,
    intro: chance(0.55) ? pick(SELF_INTRO) : null,
    addr: pick(ADDRESS_POOL)
  }
}

// 商家 12：与 `SHOPS` 一一对应（顺序即下标，商品靠下标找店主）
const merchants = SHOPS.map((s) => Object.assign(person(s.un, 2), { shop: s.shop, contact: s.contact, phone: s.phone, city: s.city, cats: s.cats }))
// 客户 24
const customers = CUSTOMER_NAMES.map((un) => person(un, 1))
// 管理员 4
const admins = ADMIN_NAMES.map((un) => person(un, 3))
seeded.push(...merchants, ...customers, ...admins)

// 客户按注册先后排（用户名池本身无时间语义，索引当序即可）
const loginRows = seeded.map((u) => {
  const k = ri(1, DAYS)
  const sec = ri(8 * 3600, 22 * 3600)
  return { u, k, sec }
})
// 商家要多一点时间攒店（注册更早）：把商家的天偏移压进 30..49
loginRows.filter((r) => r.u.role === 2).forEach((r) => {
  r.k = ri(30, DAYS)
})
// 40 行里「净增」的形状要看得见：越近的天越少（否则净增线是条斜直线）
loginRows.sort((a, b) => b.k - a.k)
loginRows.forEach((r, i) => {
  r.k = Math.max(1, Math.min(DAYS, r.k - Math.floor(i / 8)))
})

/* ==================== 构建：商品 ==================== */

const catMerchants = { 1: [], 2: [], 3: [], 4: [], 5: [] }
merchants.forEach((m, i) => m.cats.forEach((c) => catMerchants[c].push(i)))
const catCursor = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 }

const HEX4 = '0123456789ABCDEF'
const goodsRows = PRODUCTS.map(([name, typeId, price], i) => {
  const owners = catMerchants[typeId]
  const mIdx = owners[catCursor[typeId]++ % owners.length]
  let suffix = ''
  for (let j = 0; j < 4; j++) suffix += HEX4[ri(0, 15)]
  const code = `GD-${typeId}${String(i + 1).padStart(2, '0')}-${suffix}`
  return {
    name,
    typeId,
    price,
    code,
    mIdx,
    merchantId: merchants[mIdx].id,
    img: `https://picsum.photos/seed/${code}/400`,
    shelf: chance(0.9) ? 1 : 0
  }
})

/* ==================== 构建：订单 ==================== */

const dayStart = (k) => k * 86400 // 第 k 天 00:00 距今天 00:00 的秒数
const PAY_METHODS = ['支付宝', '微信支付', '银行卡']
const EXPRESS = ['顺丰速运', '中通快递', '圆通速递', '韵达快递', '京东物流']
const TRACK_PREFIX = { 顺丰速运: 'SF', 中通快递: 'ZT', 圆通速递: 'YT', 韵达快递: 'YD', 京东物流: 'JD' }
const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
const randOrderId = () => {
  let s = ''
  for (let i = 0; i < 4; i++) s += LETTERS[ri(0, 25)]
  for (let i = 0; i < 8; i++) s += ri(0, 9)
  return s
}
const round2 = (v) => Math.round(v * 100) / 100

/** 买家池：存量演示账号权重更高 —— 用户手验时用 `1111` / `123` 登进去才有历史单。 */
const buyerPool = []
LEGACY_CUSTOMERS.forEach((id) => {
  for (let i = 0; i < 3; i++) buyerPool.push(id)
})
customers.forEach((c) => buyerPool.push(c.id))

/** 目标态权重 —— 五位三态 + 取消，覆盖面按真实电商的驼峰形。 */
const STATE_POOL = [
  ['done', 62], ['shipped', 9], ['paid', 8], ['unpaid', 8], ['cancelled', 6], ['abnormal', 7]
]
const STATE_TOTAL = STATE_POOL.reduce((s, [, w]) => s + w, 0)
const pickState = () => {
  let x = rnd() * STATE_TOTAL
  for (const [s, w] of STATE_POOL) {
    x -= w
    if (x < 0) return s
  }
  return 'done'
}

const orders = []
const items = []
const orderIdSeen = new Set()

/**
 * 造一单。`k` 天前、`sec` 秒（当地 00:00 起）。
 *
 * ⚠️ **不出现未来日期**的判据：`dayStart(k) = k*86400` 正是「今天 00:00」这一刻 ——
 *    所以任何 `offset <= dayStart(k)` 的时间戳都**不晚于今天零点**（当然也就不过未来）。
 *    订单的后续时间戳（付款/发货/收货/结算）都从这个预算里**顺序扣**，扣不下就不走那一步
 *    （状态自然降级）—— 于是「最近两天的单多半还没发货」是算出来的，不是编出来的。
 */
function buildOrder(k, sec, gate) {
  const budget = dayStart(k) - sec

  // 1-2 件商品（多品用全角「；」分隔 —— 与 `ProductPage.buildProductInformation` 同形，
  // 也是后端 `inferItemsFromProductInformation` 唯一认的分隔符）
  // ⚠️ **一单只挂一个商家**：多品时也从这个商家的货里挑第二件。这不是省事 —— `order.MerchantId`
  //    是全单唯一的字段（购物车结算按商家分组建单，commit 0b），若第二件来自别家，
  //    `order_item.MerchantId` 就跟 `order.MerchantId` 互斥，`selectTopGoods` 的
  //    `(MerchantId, ProductCode)` 归类会去 `goods` 里找不着行（本轮第一次灌就踩了 18 行）。
  const byMerchant = new Map()
  goodsRows.forEach((g) => {
    if (!byMerchant.has(g.merchantId)) byMerchant.set(g.merchantId, [])
    byMerchant.get(g.merchantId).push(g)
  })
  const owner = pick(goodsRows).merchantId
  const nItems = chance(0.12) ? 2 : 1
  const picked = shuffle(byMerchant.get(owner)).slice(0, nItems)
  let subtotal = 0
  let qty = 0
  const lines = []
  for (const g of picked) {
    // 数量长尾：多数 1-2 件，偶尔 4-6 件（囤货）
    const q = chance(0.08) ? ri(4, 6) : ri(1, 2)
    const sub = round2(g.price * q)
    subtotal = round2(subtotal + sub)
    qty += q
    lines.push({ g, q, sub })
  }
  const merchantId = owner

  const state = pickState()
  let off = sec
  let payif = 0
  let dlyif = 0
  let rcvif = 0
  // ⚠️ 0 = 「这一步没发生」的哨兵。**刻意不用 -1**：`VALUES ROW()` 的列类型是整表推断的，
  //    全是正数的列会被推成 UNSIGNED，负哨兵在那儿退化成 0 —— 显式用 0 就没这个歧义。
  let payoff = 0
  let shipoff = 0
  let rcvoff = 0
  let canceloff = 0
  let settleoff = 0
  let trade = ''
  let pm = ''
  let shipco = ''
  let track = ''

  const pay = () => {
    const d = ri(120, 5400)
    if (off + d > dayStart(k)) return false
    off += d
    payoff = off
    settleoff = off + ri(60, 300)
    payif = 1
    trade = String(ri(100000, 999999)) // 尾号；「PAY + 付款日 + 尾号」在 SQL 侧拼（日期要按行取）
    pm = pick(PAY_METHODS)
    return true
  }
  const ship = () => {
    const d = ri(2 * 3600, 40 * 3600)
    if (off + d > dayStart(k)) return false
    off += d
    shipoff = off
    dlyif = 1
    shipco = pick(EXPRESS)
    track = TRACK_PREFIX[shipco] + String(ri(100000000000, 999999999999))
    return true
  }
  const receive = () => {
    const d = ri(6 * 3600, 5 * 86400)
    if (off + d > dayStart(k)) return false
    off += d
    rcvoff = off
    rcvif = 1
    return true
  }

  if (state === 'paid') pay()
  else if (state === 'shipped') {
    pay() && ship()
  } else if (state === 'done') {
    pay() && ship() && receive()
  } else if (state === 'abnormal') {
    // 三态互斥里的异常位：**没付款却发了货**（人工介入/系统重放留下的行）
    ship()
    payif = 0
    payoff = 0
    settleoff = 0
    trade = ''
    pm = ''
  } else if (state === 'cancelled') {
    if (chance(0.5)) pay()
    const d = ri(1, 3) * 86400
    if (off + d <= dayStart(k)) canceloff = off + d
    payif = 0
    dlyif = 0
    rcvif = 0
    payoff = 0
    settleoff = 0
    trade = ''
    pm = ''
    shipoff = 0
    rcvoff = 0
    shipco = ''
    track = ''
  }

  // ⚠️ 异常位要求「发货了但没付款」：若预算不够发货，这一单退回「未付款」而不是留个自相矛盾的行
  if (state === 'abnormal' && shipoff === 0) {
    dlyif = 0
  }

  let oid
  do {
    oid = randOrderId()
  } while (orderIdSeen.has(oid))
  orderIdSeen.add(oid)

  const row = {
    k,
    sec,
    gate,
    oid,
    merchantId,
    customerId: pick(buyerPool),
    price: subtotal,
    qty,
    pinfo: lines.map((l) => `${l.g.name}:${l.g.code}`).join('；'),
    imgs: lines.map((l) => l.g.img).join(','),
    payif,
    dlyif,
    rcvif,
    trade,
    pm,
    shipco,
    track,
    payoff,
    shipoff,
    rcvoff,
    canceloff,
    settleoff
  }
  orders.push(row)
  lines.forEach((l) => items.push({ k, gate, oid, merchantId, code: l.g.code, name: l.g.name, price: l.g.price, q: l.q, sub: l.sub, off: sec }))
}

for (let k = 1; k <= DAYS; k++) {
  const base = ri(1, 2) // 每一天都有单 ⇒ 趋势图**连续不跳日**
  const extra = ri(2, 4) // 工作日追加（`gate=1`，SQL 侧按执行日的真实星期过筛）
  for (let i = 0; i < base; i++) buildOrder(k, ri(9 * 3600, 22 * 3600), 0)
  for (let i = 0; i < extra; i++) buildOrder(k, ri(9 * 3600, 22 * 3600), 1)
}

/* --- 订单里的 `TradeNo`（存量形如 `PAY20261011195847`）在 SQL 侧拼：`v.trade` 只存 6 位尾号，
       前面的 `PAY` + 付款日由 `DATE_FORMAT(PaidAt, '%Y%m%d')` 现算 —— 日期要按行取，写不成常量。 --- */

/* ==================== 构建：购物车 / 通知 / 审计 / 入驻申请 ==================== */

const cartRows = []
for (let i = 0; i < 8; i++) {
  const g = pick(goodsRows)
  cartRows.push({
    customerId: pick([...LEGACY_CUSTOMERS, ...customers.map((c) => c.id)]),
    merchantId: g.merchantId,
    code: g.code,
    q: ri(1, 3),
    price: g.price,
    k: ri(1, 12),
    sec: ri(8 * 3600, 22 * 3600)
  })
}

/** 通知模板 —— `EventType` / `ToType` 全部取自**存量已用过的词汇**（见交付说明的取证）。 */
const NOTIFY_TPL = [
  { ev: 'order.notice', to: 'customer', title: '订单状态更新', body: (c) => `您的订单 ${c.oid} 已付款成功，商家备货中。` },
  { ev: 'order.notice', to: 'customer', title: '订单已发货', body: (c) => `您的订单 ${c.oid} 已由${c.co}揽收，运单号 ${c.track}。` },
  { ev: 'order.notice', to: 'merchant', title: '您有一笔新订单', body: (c) => `订单 ${c.oid} 已付款，请在 48 小时内发货。` },
  { ev: 'order.notice', to: 'merchant', title: '订单即将超时', body: (c) => `订单 ${c.oid} 距离发货时限不足 6 小时。` },
  { ev: 'order.intervened', to: 'customer', title: '订单已被平台介入', body: (c) => `订单 ${c.oid} 的物流状态由平台更正，给您带来的不便敬请谅解。` },
  { ev: 'order.intervened', to: 'merchant', title: '平台介入提醒', body: (c) => `订单 ${c.oid} 的状态被管理员手动更正，请核对发货记录。` },
  { ev: 'goods.audit.passed', to: 'merchant', title: '商品审核通过', body: (c) => `您提交的「${c.goods}」已通过审核，现已上架。` },
  { ev: 'goods.audit.rejected', to: 'merchant', title: '商品审核未通过', body: (c) => `您提交的「${c.goods}」未通过审核，原因：${c.reason}。请修改后重新提交。` },
  { ev: 'goods.apply.submitted', to: 'admin', title: '有新的商品提审', body: (c) => `${c.shop} 提交了「${c.goods}」，等待审核。` },
  { ev: 'merchant.apply.submitted', to: 'admin', title: '有新的商家入驻申请', body: (c) => `${c.contact} 提交了入驻申请「${c.shop}」，等待审核。` },
  { ev: 'merchant.audit.rejected', to: 'customer', title: '入驻申请未通过', body: (c) => `您申请的「${c.shop}」未通过审核，原因：${c.reason}。` },
  { ev: 'merchant.downgrade.scheduled', to: 'merchant', title: '店铺降级已排定', body: () => '因连续 30 天未发货率超标，您的店铺将在 3 个自然日后降级为普通商家。' },
  { ev: 'user.banned', to: 'merchant', title: '账号已被封禁', body: () => '您的账号因违规发布商品已被封禁，如有异议请联系平台客服。' },
  { ev: 'user.unbanned', to: 'merchant', title: '账号已恢复', body: () => '您的账号封禁已解除，现已恢复正常使用。' },
  { ev: 'system.notice', to: 'merchant', title: '平台规则更新', body: () => '《商家发货时效规则》已更新，自下月 1 日起生效，请及时查看。' },
  { ev: 'system.notice', to: 'customer', title: '平台公告', body: () => '双十一预售活动即将开启，部分商品支持跨店满减。' }
]
const REJECT_REASONS = ['商品图片不清晰，请更换主图', '标题含违规词，请修改后重提', '类目选择与商品不符', '价格与同类商品差异过大，请补充说明', '资质材料缺失，请补齐品牌授权']
const DOWNGRADE_REASONS = ['连续 30 天未发货率超标', '近 90 天买家投诉率超标', '商品审核驳回率过高']

const notifRows = []
for (let i = 0; i < 52; i++) {
  const tpl = pick(NOTIFY_TPL)
  const o = pick(orders)
  const g = pick(goodsRows)
  const m = pick(merchants)
  const toId = tpl.to === 'admin' ? '*' : tpl.to === 'merchant' ? pick([...LEGACY_MERCHANTS, ...merchants.map((x) => x.id)]) : pick([...LEGACY_CUSTOMERS, ...customers.map((x) => x.id)])
  const ctx = {
    oid: o.oid,
    co: o.shipco || '顺丰速运',
    track: o.track || 'SF000000000000',
    goods: g.name,
    shop: m.shop,
    contact: m.contact || '陈望',
    reason: pick(REJECT_REASONS)
  }
  let targetType = null
  let targetId = null
  if (tpl.ev.startsWith('order.')) {
    targetType = 'order'
    targetId = o.oid
  } else if (tpl.ev.startsWith('goods.')) {
    targetType = 'goods'
    targetId = g.code
  } else if (tpl.ev === 'merchant.apply.submitted') {
    targetType = 'merchant_application'
    targetId = null // Id 是自增，运行时才知道 —— SQL 侧用子查询补（见产物）
  } else if (tpl.ev.startsWith('merchant.') || tpl.ev.startsWith('user.')) {
    targetType = 'merchant'
    targetId = m.id
  }
  notifRows.push({
    ev: tpl.ev,
    toType: tpl.to,
    toId,
    title: tpl.title,
    body: tpl.body(ctx),
    targetType,
    targetId,
    read: chance(0.6),
    k: ri(1, DAYS),
    sec: ri(8 * 3600, 22 * 3600)
  })
}

/** `admin_audit` —— `Action` / `TargetType` 取值全部取自存量已用过的词汇。 */
const ADMIN_ACTIONS = [
  ['order.deliver', 'order', 14],
  ['order.receive', 'order', 10],
  ['order.intervene', 'order', 4],
  ['goods.apply.audit', 'goods_audit', 8],
  ['goods.update', 'goods', 4],
  ['goods.apply.create', 'goods_audit', 3],
  ['user.ban', 'user', 3],
  ['user.unban', 'user', 3],
  ['merchant.downgrade.schedule', 'merchant', 5],
  ['merchant.apply.audit', 'merchant_application', 3],
  ['notification.send', 'notification', 2],
  ['goodstype.create', 'goodstype', 1]
]
const auditRows = []
for (const [action, targetType, cnt] of ADMIN_ACTIONS) {
  for (let i = 0; i < cnt; i++) {
    const o = pick(orders)
    const adminId = chance(0.12) ? LEGACY_ADMIN : pick(admins).id
    const targetId =
      targetType === 'order'
        ? o.oid
        : targetType === 'user'
          ? pick([...LEGACY_CUSTOMERS, ...customers.map((x) => x.id)])
          : targetType === 'merchant'
            ? pick(merchants).id
            : targetType === 'goods'
              ? pick(goodsRows).code
              : pick(goodsRows).code
    auditRows.push({
      action,
      targetType,
      targetId,
      adminId: chance(0.08) ? null : adminId, // 8% 留空 = 存量里那一档「无操作者」
      before: chance(0.55) ? JSON.stringify(jsonFor(action, targetId, 'before')) : null,
      after: JSON.stringify(jsonFor(action, targetId, 'after')),
      k: ri(1, DAYS),
      sec: ri(8 * 3600, 22 * 3600)
    })
  }
}

function jsonFor(action, targetId, phase) {
  if (action.startsWith('order.')) {
    return phase === 'before' ? { PayIf: 1, DeliveryIf: 0, ReceiveIf: 0 } : { PayIf: 1, DeliveryIf: 1, ReceiveIf: 0 }
  }
  if (action.startsWith('goods.')) {
    return phase === 'before' ? { ShelfState: 0, Stock: 0 } : { ShelfState: 1, Stock: 60 }
  }
  if (action.startsWith('user.')) {
    const banned = action === 'user.ban'
    return phase === 'before' ? { banned: banned ? 0 : 1 } : { banned: banned ? 1 : 0 }
  }
  if (action === 'merchant.downgrade.schedule') {
    return phase === 'before' ? { DowngradeState: 'none' } : { DowngradeState: 'scheduled' }
  }
  if (action === 'merchant.apply.audit') {
    return phase === 'before' ? { Status: 'pending' } : { Status: chance(0.7) ? 'approved' : 'rejected' }
  }
  if (action === 'notification.send') {
    return phase === 'before' ? { audience: null } : { audience: 'all', title: '平台公告' }
  }
  return phase === 'before' ? { Name: '未分类' } : { Name: '数码配件' }
}

/** `merchant_application` —— 14 条，覆盖 pending / approved / rejected。 */
const APPLY_STATUS = ['pending', 'pending', 'pending', 'pending', 'pending', 'approved', 'approved', 'approved', 'approved', 'approved', 'rejected', 'rejected', 'rejected', 'rejected']
const APP_SHOP_POOL = [
  '拾光文具铺', '溪山陶瓷', '一木手作', '朴野农产', '原野户外', '朝露茶行', '木言家具',
  '小满童装', '次元数码', '禾下粮食', '清欢素食', '南山布艺', '时雨咖啡', '青岚花艺'
]
const applyRows = APPLY_STATUS.map((status, i) => {
  const u = customers[i % customers.length]
  const k = ri(6, DAYS)
  const sec = ri(8 * 3600, 22 * 3600)
  const reviewed = status !== 'pending'
  return {
    username: u.un,
    shop: APP_SHOP_POOL[i],
    license: '91' + String(ri(1000000000000000, 9999999999999999)),
    contact: u.name,
    phone: `1${pick(['3', '5', '7', '8', '9'])}${String(ri(100000000, 999999999))}`,
    addr: u.addr + ' ' + pick(['文化路', '解放大道', '人民路', '滨江路', '和平街']) + ri(1, 288) + ' 号',
    img: `https://picsum.photos/seed/license${i + 1}/400`,
    status,
    reason: status === 'rejected' ? pick(REJECT_REASONS) : null,
    batch: uuid(),
    k,
    sec,
    rk: reviewed ? Math.max(1, k - ri(1, 4)) : null,
    rsec: reviewed ? ri(9 * 3600, 18 * 3600) : null,
    reviewer: reviewed ? (chance(0.7) ? pick(admins).un : 'admin') : null
  }
})

/* ==================== 构建：货审（goods_audit） ==================== */

/** 24 条提审：8 pending / 8 approved / 8 rejected，商品名取自种子商品（像真、不重复造一批）。 */
const AUDIT_STATUS = ['pending', 'pending', 'pending', 'pending', 'pending', 'pending', 'pending', 'pending',
  'approved', 'approved', 'approved', 'approved', 'approved', 'approved', 'approved', 'approved',
  'rejected', 'rejected', 'rejected', 'rejected', 'rejected', 'rejected', 'rejected', 'rejected']
const goodsAuditRows = AUDIT_STATUS.map((status, i) => {
  const g = goodsRows[i % goodsRows.length]
  const m = merchants[g.mIdx]
  const k = ri(3, DAYS)
  const sec = ri(8 * 3600, 22 * 3600)
  const reviewed = status !== 'pending'
  return {
    merchantId: m.id,
    batch: uuid(),
    name: g.name,
    price: g.price,
    typeId: g.typeId,
    code: g.code,
    img: g.img,
    status,
    reason: status === 'rejected' ? pick(REJECT_REASONS) : null,
    // 通过的行挂到**已经存在的**商品行上（`GoodsId` = 快照重建出来的那条 `goods.Id`）
    goodsCode: status === 'approved' ? g.code : null,
    k,
    sec,
    rk: reviewed ? Math.max(1, k - ri(1, 3)) : null,
    rsec: reviewed ? ri(9 * 3600, 18 * 3600) : null,
    reviewer: reviewed ? (chance(0.7) ? pick(admins).un : 'admin') : null
  }
})

/* ==================== 产出 SQL ==================== */

const L = []
const w = (s = '') => L.push(s)

const stat = {
  login: seeded.length,
  user: seeded.length,
  goods: goodsRows.length,
  goods_audit: goodsAuditRows.length,
  order: orders.length,
  order_item: items.length,
  cart: cartRows.length,
  notification: notifRows.length,
  admin_audit: auditRows.length,
  merchant_application: applyRows.length
}

w(`-- ============================================================================`)
w(`-- seed-demo.sql —— 演示数据种子（可视化段 D）`)
w(`-- ⚠️ **本文件由 \`scripts/seed-demo-gen.mjs\` 生成，不要手改** —— 改生成器再重跑：`)
w(`--       node scripts/seed-demo-gen.mjs`)
w(`-- 生成日：2026-10-11（随机数种子 \`20261011\`，同一天重跑产物逐字相同）`)
w(`-- ============================================================================`)
w(`--`)
w(`-- 【怎么跑】`)
w(`--   1) 先确保 11 张表已有 \`Source\` 列：\`mall_management_sql.sql\` 末尾的 Migration 块`)
w(`--      （2026-10-11「演示种子标记列」）。本文件**不复制那份 DDL** —— 建列只留一处，`)
w(`--      跑本文件会先做一次前置检查；缺列时下面第一句 DELETE 会以 ERROR 1054 直接中止，`)
w(`--      **不会写进半截数据**（这是故意的，不做静默兜底）。`)
w(`--   2) mysql -h<host> -u<user> -p --default-character-set=utf8mb4 <db> < seed-demo.sql`)
w(`--      ⚠️ 必须带库名（或 \`USE\`），否则 ERROR 1046；\`--default-character-set=utf8mb4\` 必给，`)
w(`--         否则 Windows 控制台 GBK 会把中文写坏。`)
w(`--   3) 最后打一次「全量重建」让统计表自洽：`)
w(`--      POST /admin/stats/rebuild   （或管理端「经营数据 → 立即全量重建」）`)
w(`--      ⚠️ \`stats_daily\` **不在本文件里**：它的行由 \`StatsService.computeAndUpsert\` 唯一写出，`)
w(`--         那次 rebuild 会先 deleteAll 再从 \`order\` 重算 ⇒ 手写进去的会被抹掉。`)
w(`--`)
w(`-- 【幂等】每次执行先按 \`Source = 'seed'\` **先子后父**删掉上一次的种子行，再整批插入。`)
w(`--   存量数据（\`Source = ''\`）一行不删、一行不改 —— 唯一的例外是下面那段`)
w(`--   \`login.CreateTime\` 补值，它带 \`WHERE CreateTime IS NULL\` 守卫，同样可重复执行。`)
w(`--   撤种子：\`DELETE FROM \`order_item\` WHERE Source='seed'\` …（照本文件删除段的顺序即可）`)
w(`--`)
w(`-- 【规模】${DAYS} 天窗口；` + Object.entries(stat).map(([k, v]) => `${k} ${v}`).join(' / '))
w(`--`)
w(`-- 【数据口径】`)
w(`--   · 时间一律写成相对式 \`DATE_SUB(CURDATE(), INTERVAL k DAY)\`（k = 1..${DAYS}）⇒ 任何时候执行`)
w(`--     都落在「最近 ${DAYS} 天」，**永不出现未来日期**；精确到秒。`)
w(`--   · 周末低谷 / 工作日高峰按**执行时的真实星期**过筛（\`DAYOFWEEK(...) BETWEEN 2 AND 6\`，`)
w(`--     见 \`order\` / \`order_item\` 两条 INSERT 的 \`WHERE\`）—— 所以它不会随生成日漂移。`)
w(`--     每天保底 1-2 单（趋势图**连续不跳日**），工作日再加 2-4 单。`)
w(`--   · \`order.Price\` 是**这一单的小计**、\`order.Quantity\` 是**总件数**（同 \`OrderService\` 口径）；`)
w(`--     \`order_item.Subtotal\` 逐行小计，\`Σ Subtotal = order.Price\` ⇒ 看板 GMV（\`SUM(order.Price)\`）`)
w(`--     与明细自洽。`)
w(`--   · 订单后续时间戳从「当天 00:00 到今天 00:00」的预算里**顺序扣**：扣不下就不走那一步、`)
w(`--     状态自然降级 ⇒ 「最近两天的单多半还没发货」是算出来的，不是编的。`)
w(`--   · \`order.Id\` / \`goods.Id\` 等自增列不写死，交给 AUTO_INCREMENT。`)
w(`--   · 商品图 \`https://picsum.photos/seed/<商品编码>/400\` —— 联网可取，不占用仓库、不用真商品图。`)
w(`--   · 演示口令**不入库**：\`Password\` 一律写占位符 \`<demo-pwd>\`（本仓不落明文口令）。`)
w(`--     本地演示要登录时用 \`DEMO_PWD=... node scripts/seed-demo-gen.mjs\` 重新生成；上生产前必须改。`)
w(`-- ============================================================================`)
w(``)
w(`-- ---- ⓪ 前置检查：11 张表都要有 \`Source\` 列（期望输出 11/11）----`)
w(`SELECT CONCAT('seed 前置检查：Source 列 ', COUNT(*), '/11 张表就位') AS \`seed_precheck\``)
w(`  FROM information_schema.COLUMNS`)
w(` WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'Source'`)
w(`   AND TABLE_NAME IN ('login','user','goods','goods_audit','order','order_item','cart',`)
w(`                      'notification','admin_audit','merchant_application','stats_daily');`)
w(``)
w(`-- ---- ① 清上一次的种子行（**先子后父**；存量行 Source='' 一律不动）----`)
w(`DELETE FROM \`order_item\`           WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`order\`                WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`cart\`                 WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`notification\`         WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`admin_audit\`          WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`goods_audit\`          WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`merchant_application\` WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`goods\`                WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`user\`                 WHERE \`Source\` = 'seed';`)
w(`DELETE FROM \`login\`                WHERE \`Source\` = 'seed';`)
w(``)
w(`-- ---- ② 存量账号补 \`CreateTime\`（用户要求的「login 补 CreateTime」）----`)
w(`-- 存量 9 行里有 7 行 \`CreateTime\` 为空 ⇒ 它们在「用户增长」图上没有日期、画不出来。`)
w(`-- ⚠️ 守卫是 \`AND CreateTime IS NULL\`：补过一次就不再命中 ⇒ **本段可重复执行**。`)
w(`-- ⚠️ 刻意跳过 \`i2probe\`：那是本轮之前探针脚本留下的账号，不是演示数据（已在交付说明里单列）。`)
LEGACY_LOGIN_NEEDS_TIME.forEach((r) => {
  w(`UPDATE \`login\` SET \`CreateTime\` = ${TS(r.k, r.sec)} WHERE \`UserName\` = ${q(r.un)} AND \`CreateTime\` IS NULL;`)
})
w(``)

const DEMO_PWD = process.env.DEMO_PWD || '<demo-pwd>';
/* ---- ③ login ---- */
w(`-- ---- ③ \`login\`：${seeded.length} 个演示账号（客户 24 : 商家 12 : 管理员 4 = 6:3:1）----`)
w(`-- \`UserName\` 是主键；\`Id\` 就是业务号（客户/管理员走 \`user.customerid\`，商家走 \`user.merchantid\`）。`)
w(`INSERT INTO \`login\` (\`UserName\`,\`Password\`,\`Role\`,\`Id\`,\`CreateTime\`,\`Source\`) VALUES`)
w(
  loginRows
    .map((r) => `  (${q(r.u.un)}, ${q(DEMO_PWD)}, ${r.u.role}, ${q(r.u.id)}, ${TS(r.k, r.sec)}, 'seed')`)
    .join(',\n') + ';'
)
w(``)

/* ---- ④ user ---- */
w(`-- ---- ④ \`user\`：配套资料（\`usertype\` 存中文，与存量一致）----`)
w(`INSERT INTO \`user\` (\`username\`,\`usertype\`,\`customerid\`,\`merchantid\`,\`gender\`,\`age\`,\`email\`,\`selfintroduction\`,\`address\`,\`Source\`) VALUES`)
w(
  seeded
    .map((u) => {
      const type = u.role === 2 ? '商家' : u.role === 3 ? '管理员' : '客户'
      const cid = u.role === 2 ? null : u.id
      const mid = u.role === 2 ? u.id : null
      return `  (${q(u.un)}, ${q(type)}, ${q(cid)}, ${q(mid)}, ${q(u.gender)}, ${n(u.age)}, ${q(u.email)}, ${q(u.intro)}, ${q(u.addr)}, 'seed')`
    })
    .join(',\n') + ';'
)
w(``)

/* ---- ⑤ goods ---- */
w(`-- ---- ⑤ \`goods\`：${goodsRows.length} 件商品，摊在既有的 5 个类目上（电子 10 / 生活 10 / 服装 8 / 美食 8 / 运动 8）----`)
w(`-- ⚠️ \`GoodTypeNew\` 恒 0：与存量 6 行一致（那是旧字段，前端不按它筛）。\`TypeId\` 才是类目。`)
w(`-- ⚠️ \`Detail\`/\`Spec\`/\`AfterSale\` 留 NULL：详情页对空值本来就有兜底，编一段假正文反而容易自相矛盾。`)
w(`INSERT INTO \`goods\``)
w(`  (\`MerchantId\`,\`GoodName\`,\`Price\`,\`GoodImg\`,\`GoodType\`,\`ProductCode\`,\`GoodTypeNew\`,\`ShelfState\`,\`TypeId\`,\`Source\`) VALUES`)
w(
  goodsRows
    .map(
      (g) =>
        `  (${q(g.merchantId)}, ${q(g.name)}, ${n(g.price)}, ${q(g.img)}, ${q(CAT[g.typeId])}, ${q(g.code)}, 0, ${g.shelf}, ${g.typeId}, 'seed')`
    )
    .join(',\n') + ';'
)
w(``)

/* ---- ⑥ goods_audit ---- */
w(`-- ---- ⑥ \`goods_audit\`：${goodsAuditRows.length} 条提审，覆盖 pending / approved / rejected 三态 ----`)
w(`-- 通过的行 \`GoodsId\` 用标量子查询挂到**已经存在的**商品行上（自增 Id 运行时才知道）。`)
w(`INSERT INTO \`goods_audit\``)
w(`  (\`MerchantId\`,\`BatchId\`,\`GoodName\`,\`Price\`,\`GoodType\`,\`GoodTypeNew\`,\`TypeId\`,\`ProductCode\`,\`GoodImg\`,${' `Status`,'}\`RejectReason\`,\`GoodsId\`,\`SubmittedAt\`,\`ReviewedBy\`,\`ReviewedAt\`,\`Source\`) VALUES`)
w(
  goodsAuditRows
    .map((r) => {
      const goodsId = r.goodsCode ? `(SELECT \`Id\` FROM \`goods\` WHERE \`ProductCode\` = ${q(r.goodsCode)} LIMIT 1)` : 'NULL'
      const reviewedBy = r.reviewer ? q(r.reviewer) : 'NULL'
      const reviewedAt = r.rk ? TS(r.rk, r.rsec) : 'NULL'
      return (
        `  (${q(r.merchantId)}, ${q(r.batch)}, ${q(r.name)}, ${n(r.price)}, ${q(CAT[r.typeId])}, 0, ${r.typeId}, ` +
        `${q(r.code)}, ${q(r.img)}, ${q(r.status)}, ${q(r.reason)}, ${goodsId}, ${TS(r.k, r.sec)}, ${reviewedBy}, ${reviewedAt}, 'seed')`
      )
    })
    .join(',\n') + ';'
)
w(``)

/* ---- ⑦ order ---- */
w(`-- ---- ⑦ \`order\`：~${orders.length} 单，全状态覆盖 + ${DAYS} 天长跨度 ----`)
w(`-- \`WHERE\` 是本文件唯一「按执行日算」的地方：\`gate=1\` 的行只在**工作日**落入（周末低谷）。`)
w(`-- \`gate=0\` 的行每天都落 ⇒ 趋势图连续不跳日。`)
w(`INSERT INTO \`order\``)
w(`  (\`MerchantId\`,\`CustomerId\`,\`OrderId\`,\`Price\`,\`ProductInformation\`,\`Images\`,\`PayIf\`,\`DeliveryIf\`,\`ReceiveIf\`,`)
w(`   \`CreateTime\`,\`SettlementDate\`,\`Quantity\`,\`TradeNo\`,\`PaymentMethod\`,\`PaidAt\`,\`ExpressCompany\`,\`TrackingNo\`,\`ShippedAt\`,\`ReceivedAt\`,\`CancelledAt\`,\`Source\`)`)
w(`SELECT v.mid, v.cid, v.oid, v.price, v.pinfo, v.imgs, v.payif, v.dlyif, v.rcvif,`)
w(`       ${TSV('v.k', 'v.sec')},`)
w(`       IF(v.settleoff = 0, NULL, ${TSV('v.k', 'v.settleoff')}),`)
w(`       v.qty,`)
w(`       IF(v.trade = '', NULL, CONCAT('PAY', DATE_FORMAT(${TS('v.k', 'v.payoff')}, '%Y%m%d'), v.trade)),`)
w(`       NULLIF(v.pm, ''),`)
w(`       IF(v.payoff   = 0, NULL, ${TS('v.k', 'v.payoff')}),`)
w(`       NULLIF(v.shipco, ''), NULLIF(v.track, ''),`)
w(`       IF(v.shipoff  = 0, NULL, ${TS('v.k', 'v.shipoff')}),`)
w(`       IF(v.rcvoff   = 0, NULL, ${TS('v.k', 'v.rcvoff')}),`)
w(`       IF(v.canceloff = 0, NULL, ${TS('v.k', 'v.canceloff')}),`)
w(`       'seed'`)
w(`FROM (VALUES`)
w(
  orders
    .map(
      (o) =>
        `  ROW(${o.k}, ${o.sec}, ${o.gate}, ${q(o.merchantId)}, ${q(o.customerId)}, ${q(o.oid)}, ${n(o.price)}, ` +
        `${q(o.pinfo)}, ${q(o.imgs)}, ${o.payif}, ${o.dlyif}, ${o.rcvif}, ${o.qty}, ` +
        `${q(o.trade)}, ${q(o.pm)}, ${o.payoff}, ${q(o.shipco)}, ${q(o.track)}, ` +
        `${o.shipoff}, ${o.rcvoff}, ${o.canceloff}, ${o.settleoff})`
    )
    .join(',\n')
)
w(`  ) AS v(k,sec,gate,mid,cid,oid,price,pinfo,imgs,payif,dlyif,rcvif,qty,trade,pm,payoff,shipco,track,shipoff,rcvoff,canceloff,settleoff)`)
w(`WHERE (v.gate = 0 OR DAYOFWEEK(DATE_SUB(CURDATE(), INTERVAL v.k DAY)) BETWEEN 2 AND 6);`)
w(``)

/* ---- ⑧ order_item ---- */
w(`-- ---- ⑧ \`order_item\`：配套明细（Σ Subtotal = 对应 order.Price）；过筛条件与上一段**逐字相同** ----`)
w(`-- ⇒ 不会出现「有明细没订单」或「有订单没明细」的半截对。`)
w(`INSERT INTO \`order_item\` (\`OrderId\`,\`MerchantId\`,\`ProductCode\`,\`GoodName\`,\`Price\`,\`Quantity\`,\`Subtotal\`,\`OrderedAt\`,\`Source\`)`)
w(`SELECT v.oid, v.mid, v.code, v.name, v.price, v.q, v.sub, ${TS('v.k', 'v.sec')}, 'seed'`)
w(`FROM (VALUES`)
w(
  items
    .map(
      (it) =>
        `  ROW(${it.k}, ${it.gate}, ${q(it.oid)}, ${q(it.merchantId)}, ${q(it.code)}, ${q(it.name)}, ${n(it.price)}, ${it.q}, ${n(it.sub)}, ${it.off})`
    )
    .join(',\n')
)
w(`  ) AS v(k,gate,oid,mid,code,name,price,q,sub,sec)`)
w(`WHERE (v.gate = 0 OR DAYOFWEEK(DATE_SUB(CURDATE(), INTERVAL v.k DAY)) BETWEEN 2 AND 6);`)
w(``)

/* ---- ⑨ cart ---- */
w(`-- ---- ⑨ \`cart\`：${cartRows.length} 行演示购物车 ----`)
w(`-- ⚠️ \`MerchantId\` + \`ProductCode\` 必须命中一件**种子商品**：\`CartDao.selectByCustomer\` 是`)
w(`--    \`LEFT JOIN goods ON g.MerchantId=c.MerchantId AND g.ProductCode=c.ProductCode\`，`)
w(`--    对不上就只有购物车行、没有商品名/图（页面上是空行）。`)
w(`INSERT INTO \`cart\` (\`CustomerId\`,\`MerchantId\`,\`ProductCode\`,\`Quantity\`,\`PriceSnapshot\`,\`created_at\`,\`updated_at\`,\`Source\`) VALUES`)
w(
  cartRows
    .map(
      (c) =>
        `  (${q(c.customerId)}, ${q(c.merchantId)}, ${q(c.code)}, ${c.q}, ${n(c.price)}, ${TS(c.k, c.sec)}, ${TS(c.k, c.sec)}, 'seed')`
    )
    .join(',\n') + ';'
)
w(``)

/* ---- ⑩ merchant_application（放在 notification 之前：通知要引它的 Id） ---- */
w(`-- ---- ⑩ \`merchant_application\`：${applyRows.length} 条入驻申请，覆盖 pending / approved / rejected ----`)
w(`-- 申请人取的是种子客户账号（\`Username\` 指向 \`login.UserName\`）；审核人取种子管理员。`)
w(`INSERT INTO \`merchant_application\``)
w(`  (\`Username\`,\`ShopName\`,\`LicenseNo\`,\`ContactName\`,\`ContactPhone\`,\`ShopAddress\`,\`LicenseImage\`,`)
w(`   \`Status\`,\`RejectReason\`,\`BatchId\`,\`SubmittedAt\`,\`ReviewedBy\`,\`ReviewedAt\`,\`Source\`) VALUES`)
w(
  applyRows
    .map(
      (a) =>
        `  (${q(a.username)}, ${q(a.shop)}, ${q(a.license)}, ${q(a.contact)}, ${q(a.phone)}, ${q(a.addr)}, ${q(a.img)}, ` +
        `${q(a.status)}, ${q(a.reason)}, ${q(a.batch)}, ${TS(a.k, a.sec)}, ${a.reviewer ? q(a.reviewer) : 'NULL'}, ` +
        `${a.rk ? TS(a.rk, a.rsec) : 'NULL'}, 'seed')`
    )
    .join(',\n') + ';'
)
w(``)

/* ---- ⑪ notification ---- */
w(`-- ---- ⑪ \`notification\`：${notifRows.length} 条；\`EventType\`/\`ToType\` 全部取自存量已用过的词汇 ----`)
w(`-- 收件人：客户取演示号 \`uDopHnCeLS\`(1111) / \`pIzTWafaGp\`(123) / \`obkb9birqH\`(aaa) 与种子客户；`)
w(`-- 商家同理；管理员是哨兵 \`ToId='*'\`（与 \`NotifyInbox.vue\` → \`ADMIN_INBOX_ID\` 逐字一致）。`)
w(`INSERT INTO \`notification\``)
w(`  (\`BatchId\`,\`ToId\`,\`ToType\`,\`EventType\`,\`Title\`,\`Content\`,\`TargetType\`,\`TargetId\`,\`ReadAt\`,\`CreatedAt\`,\`Source\`) VALUES`)
w(
  notifRows
    .map((r) => {
      const targetId =
        r.targetType === 'merchant_application'
          ? `(SELECT \`Id\` FROM \`merchant_application\` WHERE \`Source\` = 'seed' AND \`Status\` = 'pending' ORDER BY \`Id\` LIMIT 1)`
          : r.targetId
            ? q(r.targetId)
            : 'NULL'
      const readAt = r.read ? TS(Math.max(1, r.k - ri(1, 3)), r.sec) : 'NULL'
      return (
        `  (${q(uuid())}, ${q(r.toId)}, ${q(r.toType)}, ${q(r.ev)}, ${q(r.title)}, ${q(r.body)}, ` +
        `${r.targetType ? q(r.targetType) : 'NULL'}, ${targetId}, ${readAt}, ${TS(r.k, r.sec)}, 'seed')`
      )
    })
    .join(',\n') + ';'
)
w(``)

/* ---- ⑫ admin_audit ---- */
w(`-- ---- ⑫ \`admin_audit\`：${auditRows.length} 条；\`Action\` 取自存量 15 种动作里的 12 种 ----`)
w(`-- \`ActorRole\` 是 \`login.Role\` 的取值（1 客户 / 2 商家 / 3 管理员）—— 演示数据全是管理员操作。`)
w(`INSERT INTO \`admin_audit\` (\`ActorId\`,\`ActorRole\`,\`Action\`,\`TargetType\`,\`TargetId\`,\`BeforeJson\`,\`AfterJson\`,\`OccurredAt\`,\`Source\`) VALUES`)
w(
  auditRows
    .map(
      (r) =>
        `  (${r.adminId ? q(r.adminId) : 'NULL'}, ${r.adminId ? 3 : 'NULL'}, ${q(r.action)}, ${q(r.targetType)}, ${q(r.targetId)}, ` +
        `${q(r.before)}, ${q(r.after)}, ${TS(r.k, r.sec)}, 'seed')`
    )
    .join(',\n') + ';'
)
w(``)

/* ---- ⑬ 尾注 ---- */
w(`-- ---- ⑬ 收尾：让统计表自洽 ----`)
w(`-- \`stats_daily\` 不在这里写（见文件头）。灌完本文件后**必须**打一次全量重建：`)
w(`--     POST /admin/stats/rebuild`)
w(`-- 或管理端「经营数据 → 立即全量重建」。重建会先清空 \`stats_daily\`、再从上面的 \`order\``)
w(`-- 全量重算 ⇒ 看板的 GMV / 成单数 / 买家数与你刚灌的订单一一对应。`)
w(``)
w(`-- ---- 自检（可选，跑完看一眼）----`)
w(`SELECT 'login' AS \`table\`, COUNT(*) AS \`seed_rows\` FROM \`login\` WHERE \`Source\`='seed'`)
Object.keys(stat)
  .filter((t) => t !== 'login')
  .forEach((t) => {
    w(`UNION ALL SELECT ${q(t)}, COUNT(*) FROM \`${t}\` WHERE \`Source\`='seed'`)
  })
w(`UNION ALL SELECT 'orders_done', COUNT(*) FROM \`order\` WHERE \`Source\`='seed' AND \`PayIf\`=1 AND \`DeliveryIf\`=1 AND \`ReceiveIf\`=1`)
w(`UNION ALL SELECT 'orders_unpaid', COUNT(*) FROM \`order\` WHERE \`Source\`='seed' AND \`PayIf\`=0 AND \`DeliveryIf\`=0 AND \`ReceiveIf\`=0 AND \`CancelledAt\` IS NULL`)
w(`UNION ALL SELECT 'orders_cancelled', COUNT(*) FROM \`order\` WHERE \`Source\`='seed' AND \`CancelledAt\` IS NOT NULL`)
w(`UNION ALL SELECT 'order_span_days', COUNT(DISTINCT DATE(\`CreateTime\`)) FROM \`order\` WHERE \`Source\`='seed';`)
w(``)

const sql = L.join('\n')

if (CHECK_ONLY) {
  console.log('--check：未落盘')
  console.table(stat)
  process.exit(0)
}
writeFileSync(OUT, sql, 'utf8')
console.log(`wrote ${OUT}  (${(Buffer.byteLength(sql, 'utf8') / 1024).toFixed(1)} KiB, ${L.length} lines)`)
console.table(stat)
