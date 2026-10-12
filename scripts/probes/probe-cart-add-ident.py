# -*- coding: utf-8 -*-
"""只读诊断：加购报「商品不存在或已下架」—— 判 `goods.ProductCode` 与 SKU 码是不是同一个东西。

背景（用户 2026-10-12 报障）：某商品有 4 个型号 asdsad / sadad / dddddd / aaaaaa，
选 `dddddd` 加购，回「sadasd: 商品不存在或已下架」「dddddd: 商品不存在或已下架」。

用法：python scripts/probes/probe-cart-add-ident.py
      只发 GET/POST 读端点，**不写任何表**。

⚠️ Windows 控制台默认 GBK ⇒ 先重设 stdout（同 probe-b5c4-seckill-e2e.py）。
⚠️ 本探针**不连库**、不读凭据（a3 后端已连着库，够用）。
"""
import json
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'http://localhost:8088'
SUSPECTS = ['asdsad', 'sadad', 'sadasd', 'dddddd', 'aaaaaa']


def post(path, **kw):
    body = urllib.parse.urlencode({k: v for k, v in kw.items() if v is not None}).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=body,
                                 headers={'Content-Type': 'application/x-www-form-urlencoded'})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode('utf-8'))


home = post('/goods/goodsSelectHome')
rows = home.get('list') or []
print('goodsSelectHome 行数 =', len(rows))
if rows:
    print('第一行的键 =', sorted(rows[0].keys()))

# ① `goods` 表里有没有直接以那几个型号码充当 ProductCode 的行
codes = {str(r.get('ProductCode')) for r in rows}
print('\n① goods.ProductCode 里命中嫌疑码的 =', sorted(codes & set(SUSPECTS)) or '（一个都没有）')

# ② 逐商品问 SKU 表；找出型号集合与用户描述吻合的那一件
print('\n② 逐商品问 /goods/skuList（只打有意/有型号的行）：')
hits = []
for r in rows:
    mid, name = r.get('MerchantId'), r.get('GoodName')
    try:
        d = post('/goods/skuList', MerchantId=mid, GoodName=name)
    except Exception as e:
        print('   ! skuList 失败', mid, name, e)
        continue
    skus = d.get('list') or []
    sku_codes = [str(s.get('SkuCode')) for s in skus]
    if set(SUSPECTS) & set(sku_codes):
        hits.append((r, skus))
for r, skus in hits:
    print('   ★', r.get('MerchantId'), '|', r.get('GoodName'))
    print('        goods.ProductCode =', repr(r.get('ProductCode')))
    print('        goods.ShelfState  =', r.get('ShelfState'), ' DeletedAt =', r.get('DeletedAt'), ' Id =', r.get('Id'))
    print('        goods.Price       =', r.get('Price'))
    print('        skuList           =', json.dumps(skus, ensure_ascii=False))
if not hits:
    print('   （没有任何商品的 SKU 与嫌疑码相交 —— 请把报障时选的商品名告诉我）')

# ③ 拿 SKU 码当 ProductCode 打一次 /cart/add，看是不是**逐字复现**用户看到的那条 msg。
#    ⚠️ 预期**不写库**：`selectGoodsSnapshot` 查不到 ⇒ `add()` 在 INSERT **之前**就 fail 了。
#       万一某一条真写进去了（无 SKU 表的商品会命中 goods.ProductCode），本探针**就地删掉并读回**，
#       不把探针行留在 `cart` 里（同 `probe-output-roundtrip` 那条：破坏性操作要读回双重确认）。
PROBE_CUST = '__probe_ident__'
print('\n③ 用 SKU 码当 ProductCode 打 /cart/add（复现用户看到的那条 msg）：')
written = []
for r, skus in hits[:1]:
    for s in skus:
        code = str(s.get('SkuCode'))
        d = post('/cart/add', CustomerId=PROBE_CUST, MerchantId=r.get('MerchantId'),
                 ProductCode=code, Quantity=1)
        print('   ProductCode=%-8s ⇒ %s' % (code, json.dumps(d, ensure_ascii=False)))
        if d.get('success'):
            written.append((r.get('MerchantId'), code))

if written:
    print('\n   ⚠️ 有探针行真的写进去了，按具体 ProductCode 逐条删（不整表清）：')
    for mid, code in written:
        print('      remove', mid, code, '⇒', json.dumps(
            post('/cart/remove', CustomerId=PROBE_CUST, ProductCode=code), ensure_ascii=False))
print('   读回 /cart/list ⇒', json.dumps(post('/cart/list', CustomerId=PROBE_CUST), ensure_ascii=False))

