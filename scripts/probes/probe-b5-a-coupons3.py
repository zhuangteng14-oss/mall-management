# -*- coding: utf-8 -*-
"""
probe-b5-a-coupons3.py —— C3 块 5 · A 组手验脚手架：给**同一件商品**凑出 ≥3 张商家券（2026-10-12）。

为什么要它：A4 那条「两张半 + 第三张截断 + 渐隐 + `》`」的**触发判据是 `claimList.length > CP_VISIBLE`
（> 2）**（`ProductPage.vue` 里 `coupons-cut` / `.cp-more` 两处 `v-if` 同一个式子）⇒ 券不足 3 张时
按设计**不出**渐隐也不出 `》`。用户手验时库里 oppo12 只有 1 张券 ⇒ 看不出版式。
本脚本把券数**补到 TARGET**（默认 3），不多造（幂等：再跑一次不会变成 6 张）。

⚠️ **这是写探针**（每造一张 = 1 张 `coupon` 行 + 1 条 `goods_audit` 行，全落真库）：
   · 只调**真接口**（`/goods/goodsUp` + `/admin/catalog/application/audit`），不写 SQL；
   · `goodsUp` 是**既有直改**路径，商品五件套按现值原样回填 ⇒ 商品行内容不变（同值 update）；
   · 不碰 `coupon_grant`（不领券）。
   ⇒ 造出来的券**没有删除端点**，清不掉就是留着的（用户手验正需要它们）。

用法（后端 a3 起在 8088；**不需要**前端）：
    python scripts/probes/probe-b5-a-coupons3.py [TARGET]
输出：`_work/b5-a-coupons3.json`（逐步原始回体）+ 控制台人读小结。
退出码：0 = 商品券数已 ≥ TARGET；1 = 没凑够（看输出哪一步断了）。

⚠️ 用 python 而不是 curl：本机 GBK 控制台，`curl -d` 传中文商品名会变乱码（同 b5-a 那份的按语）。
"""
import io
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://localhost:8088'
# 目标商品：oppo12（#58 审核单单放行的那件）。改这里 = 换手验对象。
MERCHANT = '5N1k5K2guh'
GOOD_NAME = 'oppo12'
# `/marketing/coupons` 的 `Account` 语义 = `user.customerid`（username='1111'）。
# ⚠️ 券列表**不按这个客户筛**（只回 `Claimed` 标记）⇒ 用谁的号读都行，这里只为有个合法 `Account`。
ACCOUNT = 'uDopHnCeLS'

TARGET = int(sys.argv[1]) if len(sys.argv) > 1 else 3

out = {}


def post(path, params):
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


def merchant_coupons():
    """本店筛（= 详情页领券行发的那一发）当前回几张。"""
    _, r = post('/marketing/coupons', {'Account': ACCOUNT, 'limit': '200', 'MerchantAccount': MERCHANT})
    return r.get('list') or []


def flush(tag):
    out['failTag'] = tag
    json.dump(out, io.open('_work/b5-a-coupons3.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=2)


# ── 0. 前置：商品五件套从真接口取（不手抄，防与库不一致）───────────────────────────
code, home = post('/goods/goodsSelectHome', {})
g = next((x for x in (home.get('list') or [])
          if x.get('MerchantId') == MERCHANT and x.get('GoodName') == GOOD_NAME), None)
if not g:
    flush('前置：没找到目标商品')
    sys.exit('前置失败：%s / %s 不在 goodsSelectHome 里（先确认 a3 profile 与 VM 库可达）' % (MERCHANT, GOOD_NAME))
out['precondition'] = {k: g.get(k) for k in ('Id', 'MerchantId', 'GoodName', 'GoodType', 'ProductCode', 'Price')}
print('目标商品：Id=%s %s / %s（%s）' % (g.get('Id'), MERCHANT, GOOD_NAME, g.get('ProductCode')))

# ── 1. 现有几张 ──────────────────────────────────────────────────────────────────
before = merchant_coupons()
out['before'] = [{'CouponId': c.get('CouponId'), 'Name': c.get('Name'),
                  'Type': c.get('Type'), 'Status': c.get('Status')} for c in before]
print('现有商家券：%d 张 —— %s' % (len(before), ' / '.join(c.get('Name') or '?' for c in before)))

need = max(0, TARGET - len(before))
print('需要补造：%d 张（目标 %d）' % (need, TARGET))

# ── 2. 逐张造：goodsUp（券先落 stopped）→ 找该批次的待审单 → 放行（stopped → active）──
created = []
for i in range(need):
    name = '商品券演示 %d —— %s' % (len(before) + i + 1, GOOD_NAME)
    face, thr = (['20', '300'], ['50', '500'], ['100', '1500'], ['200', '2000'])[i % 4]
    _, r = post('/goods/goodsUp', {
        'MerchantId': MERCHANT, 'GoodName': GOOD_NAME, 'Price': g.get('Price'),
        'GoodType': g.get('GoodType'), 'ProductCode': g.get('ProductCode'),
        'CouponName': name, 'CouponType': 'threshold',
        'CouponFaceValue': face, 'CouponThreshold': thr, 'CouponTotalQty': '200',
    })
    ok = bool(r.get('success')) and bool(r.get('couponPending')) and bool(r.get('couponId'))
    print('  造券 %d/%d：%s  success=%s couponId=%s msg=%s'
          % (i + 1, need, name, r.get('success'), r.get('couponId'), r.get('msg')))
    out.setdefault('goodsUp', []).append({'name': name, 'body': r})
    if not ok:
        flush('goodsUp 建券失败')
        sys.exit('goodsUp 建券失败，已停。')

    # 该批次的待审单（带 Coupon 且券名逐字等于本次）
    _, apps = post('/admin/catalog/applications', {'status': 'pending', 'limit': '200'})
    mine = [x for x in (apps.get('list') or []) if (x.get('Coupon') or {}).get('Name') == name]
    if len(mine) != 1:
        out['applications'] = apps
        flush('待审单不唯一/找不到')
        sys.exit('待审单不唯一或找不到（券名 %s，命中 %d 条），已停。' % (name, len(mine)))
    audit_id, coupon_id = mine[0]['Id'], mine[0]['Coupon']['CouponId']
    _, r = post('/admin/catalog/application/audit', {'id': str(audit_id), 'approve': 'true'})
    ok = bool(r.get('success')) and bool(r.get('isCoupon')) and str(r.get('outcome')) == 'approved'
    out.setdefault('audit', []).append({'auditId': audit_id, 'couponId': coupon_id, 'body': r})
    print('    放行 Id=%s → 券 %s  isCoupon=%s outcome=%s' % (audit_id, coupon_id, r.get('isCoupon'), r.get('outcome')))
    if not ok:
        flush('放行失败')
        sys.exit('放行失败，已停（券仍是 stopped，详情页看不到）。')
    created.append({'CouponId': coupon_id, 'Name': name, 'FaceValue': face, 'Threshold': thr})

# ── 3. 复验：本店筛回几张、本次那几张在不在 ──────────────────────────────────────
after = merchant_coupons()
out['after'] = [{'CouponId': c.get('CouponId'), 'Name': c.get('Name'), 'Type': c.get('Type'),
                 'FaceValue': c.get('FaceValue'), 'Threshold': c.get('Threshold')} for c in after]
ids = {c.get('CouponId') for c in after}
missing = [c['CouponId'] for c in created if c['CouponId'] not in ids]
out['summary'] = {'target': TARGET, 'before': len(before), 'created': len(created),
                  'after': len(after), 'missing': missing, 'createdCoupons': created}
ok = len(after) >= TARGET and not missing
out['ok'] = ok
json.dump(out, io.open('_work/b5-a-coupons3.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)

print('\n本店筛（MerchantAccount=%s）现回 %d 张：' % (MERCHANT, len(after)))
for c in after:
    print('  · %s  %s  满%s减%s' % (c.get('CouponId'), c.get('Name'), c.get('Threshold'), c.get('FaceValue')))
print('\n%s（原始回体：_work/b5-a-coupons3.json）' % ('OK —— 已 ≥ %d 张' % TARGET if ok else 'FAIL —— 没凑够'))
sys.exit(0 if ok else 1)
