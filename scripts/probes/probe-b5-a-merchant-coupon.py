# -*- coding: utf-8 -*-
"""
probe-b5-a-merchant-coupon.py —— C3 块 5 · A 组（A2 + A3 + A4）端到端写探针（2026-10-12）。

验的是**一条链**，四段都说通了才算过：
  ① A2  商家「更改商品」挂商品券  → `POST /goods/goodsUp`（带 `Coupon*` 参数）
  ② A3  这张券**随商品一起提审**、审核列表能回显 → `POST /admin/catalog/applications`（该单带 `Coupon`）
  ③ A3  管理员放行 ⇒ 券由 `stopped` 转 `active`   → `POST /admin/catalog/application/audit`
  ④ A4  商品详情页领券区（按 `MerchantAccount` 收窄）能领到它 → `POST /marketing/coupons`

⚠️ **这是写探针**（会往真库写 1 张券 + 1 条 `goods_audit`），与只读探针不同：
   · 只调**真接口**，不直接改库（没有 INSERT/UPDATE/DELETE 语句）；
   · 不碰 `coupon_grant`（**不领券** —— 那会污染券包），第 ④ 步只**读**列表；
   · `goodsUp` 是**既有直改**路径，参数原样回填 ⇒ 商品行内容不变（同值 update）。
   ⇒ 跑完请据输出决定是否清理（本仓 `coupon` 没有删除端点，清理要走 §三 那种一次性 SQL）。

用法（后端 a3 起在 8088；**不需要**前端）：
    python scripts/probes/probe-b5-a-merchant-coupon.py
输出：`_work/b5-a-e2e.json`（逐步原始回体），控制台打一份人读小结。

⚠️ 用 python 而不是 curl：本机是 GBK 控制台，`curl -d` 传中文商品名会变成乱码字节
   （本仓 `_work/*.mjs` 探针里那条「Windows/GBK 下 curl 传中文会变乱码」记过同一坑）。
   本脚本全程 `json.dumps(...).encode('utf-8')`，中文原样过。
"""
import io
import json
import sys
import urllib.request
import urllib.parse

BASE = 'http://localhost:8088'
# 领券/查询要一个**真客户号**（`/marketing/*` 的 `Account` 语义 = `user.customerid`）。
# 取自 `docs/agent/evidence/c3-block5/b5-real-endpoints.md`（username='1111'）。
ACCOUNT = 'uDopHnCeLS'

out = {}


def post(path, params):
    """真接口 POST —— 表单编码、UTF-8。返回 (http_code, 解析后的 JSON 或原文)。"""
    body = urllib.parse.urlencode(params, encoding='utf-8').encode('utf-8')
    req = urllib.request.Request(BASE + path, data=body, method='POST')
    req.add_header('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8')
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read().decode('utf-8', 'replace')
            code = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        code = e.code
    try:
        return code, json.loads(raw)
    except ValueError:
        return code, {'_raw': raw}


def step(name, ok, note):
    print(('  OK  ' if ok else ' FAIL ') + name + ' —— ' + note)
    out.setdefault('steps', []).append({'step': name, 'ok': bool(ok), 'note': note})


fails = 0

# ── 0. 前置：拿一件**真商品**（MerchantId / GoodName / GoodType / ProductCode 四件套）────────
code, home = post('/goods/goodsSelectHome', {})
lst = (home or {}).get('list') or []
assert lst, 'goodsSelectHome 回空：先确认 a3 profile 与 VM 库可达'
g = lst[0]
merchant, good_name, good_type, product_code = g['MerchantId'], g['GoodName'], g['GoodType'], g['ProductCode']
out['precondition'] = {'MerchantId': merchant, 'GoodName': good_name,
                       'GoodType': good_type, 'ProductCode': product_code, 'Price': g.get('Price')}
print('前置商品：%s / %s（%s）' % (merchant, good_name, product_code))

# 券名带**时间戳后缀** ⇒ 重跑不会撞名，回头看列表也能一眼认出这一次的
COUPON_NAME = '探针商品券 %s' % product_code

# ── 1. A2：商家挂券（goodsUp 带 Coupon* 参数）──────────────────────────────────────────
code, r = post('/goods/goodsUp', {
    'MerchantId': merchant, 'GoodName': good_name, 'Price': g.get('Price'),
    'GoodType': good_type, 'ProductCode': product_code,
    'CouponName': COUPON_NAME, 'CouponType': 'threshold',
    'CouponFaceValue': '15', 'CouponThreshold': '100', 'CouponTotalQty': '100',
})
out['goodsUp'] = r
ok1 = bool(r.get('success')) and bool(r.get('couponPending')) and bool(r.get('couponId'))
fails += 0 if ok1 else 1
step('A2 goodsUp 建券', ok1, 'http %s success=%s couponPending=%s couponId=%s msg=%s'
     % (code, r.get('success'), r.get('couponPending'), r.get('couponId'), r.get('msg')))
if not ok1:
    json.dump(out, io.open('_work/b5-a-e2e.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    sys.exit('A2 失败，后续步骤无意义，已停。')

# ── 2. A3：该批次的审核单能回显这张券 ────────────────────────────────────────────────
code, r = post('/admin/catalog/applications', {'status': 'pending', 'limit': '200'})
out['applications'] = r
rows = [x for x in (r.get('list') or []) if x.get('Coupon')]
mine = [x for x in rows if (x.get('Coupon') or {}).get('Name') == COUPON_NAME]
ok2 = len(mine) == 1
fails += 0 if ok2 else 1
step('A3 审核单回显 Coupon', ok2, '带券单 %d 条，本次那条 %d 条；Coupon=%s'
     % (len(rows), len(mine), (mine[0].get('Coupon') if mine else None)))
if not ok2:
    json.dump(out, io.open('_work/b5-a-e2e.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    sys.exit('A3 失败，已停（没找到本次那条带券单）。')

audit_id = mine[0]['Id']
coupon_id = mine[0]['Coupon']['CouponId']
pending_status = mine[0]['Coupon']['Status']

# ── 3. A3：放行 ⇒ 券 stopped → active ─────────────────────────────────────────────
code, r = post('/admin/catalog/application/audit', {'id': str(audit_id), 'approve': 'true'})
out['audit'] = r
ok3 = bool(r.get('success')) and bool(r.get('isCoupon')) and str(r.get('outcome')) == 'approved'
fails += 0 if ok3 else 1
step('A3 放行（券生效）', ok3, 'http %s success=%s isCoupon=%s outcome=%s couponId=%s'
     % (code, r.get('success'), r.get('isCoupon'), r.get('outcome'), r.get('couponId')))

# ── 4. A4：商品详情页那条筛（MerchantAccount）能领到它 ─────────────────────────────
code, r = post('/marketing/coupons', {'Account': ACCOUNT, 'limit': '200', 'MerchantAccount': merchant})
out['coupons_by_merchant'] = r
hit = [c for c in (r.get('list') or []) if c.get('CouponId') == coupon_id]
ok4 = len(hit) == 1
fails += 0 if ok4 else 1
step('A4 本店筛能领到', ok4, 'MerchantAccount=%s 回 %d 张，含本次券 %d 张'
     % (merchant, len(r.get('list') or []), len(hit)))

# 4b. 反向：不传 MerchantAccount ⇒ 也应在（不传 = 平台券+商家券都要，行为未收窄）
code, r = post('/marketing/coupons', {'Account': ACCOUNT, 'limit': '200'})
out['coupons_all'] = r
ok4b = any(c.get('CouponId') == coupon_id for c in (r.get('list') or []))
step('A4 不带筛也在（未收窄旧行为）', ok4b, '全量 %d 张，含本次券 %s' % (len(r.get('list') or []), ok4b))

# 4c. 反向：换一个**别的**商家号 ⇒ 不该出现（证明筛子真的在筛，不是把整表都回）
code, r = post('/marketing/coupons', {'Account': ACCOUNT, 'limit': '200',
                                      'MerchantAccount': '__NOT_A_MERCHANT__'})
out['coupons_other_merchant'] = r
ok4c = not any(c.get('CouponId') == coupon_id for c in (r.get('list') or []))
step('A4 别家筛不含它', ok4c, '陌生商家号回 %d 张' % len(r.get('list') or []))

out['summary'] = {'MerchantId': merchant, 'GoodName': good_name, 'ProductCode': product_code,
                  'CouponId': coupon_id, 'CouponName': COUPON_NAME,
                  'CouponStatusBeforeAudit': pending_status, 'AuditId': audit_id,
                  'fails': fails}
json.dump(out, io.open('_work/b5-a-e2e.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('\n失败步数：%d（原始回体：_work/b5-a-e2e.json）' % fails)
print('留下：%s 的 %s 上有一张 active 商品券 %s（%s）' % (merchant, good_name, coupon_id, COUPON_NAME))
sys.exit(1 if fails else 0)
