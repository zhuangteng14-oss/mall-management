# -*- coding: utf-8 -*-
"""C3 块 5 · commit 4〈秒杀〉全链路手验探针（2026-10-12）。

用法：python scripts/probes/probe-b5c4-seckill-e2e.py        # 跑链路，打印每一步的 JSON
      python scripts/probes/probe-b5c4-seckill-e2e.py --clean # 只清理（按上一次留下的 id 清单）

⚠️ 为什么用 Python 而不是 curl：Windows 下 curl 传中文会按 GBK 出去、服务端收到乱码
   （探针 `probe-must-verify-precondition` 记过这条）；本探针所有请求体一律 UTF-8 编码。
⚠️ 时间点是**时刻**，库内就是 UTC（a3 库 `@@system_time_zone='UTC'`）⇒ 这里按 UTC 现算，
   不用 `NOW() + INTERVAL 8 HOUR`（那只属于日历运算）。
⚠️ a3 profile 的 `app.auth.enabled=false`（闸关）⇒ 本探针不带 token。**闸关 ⇒ 手验绿 ≠ 默认 profile 绿。**
"""
import datetime
import json
import sys
import urllib.parse
import urllib.request

# ⚠️ Windows 控制台默认 GBK：不重设编码，`print` 一遇到 `⇒` 这种字符就 UnicodeEncodeError 中断
#    （实测踩过 —— 请求已经发出去了、断言还没跑，脚本却死在打印上，看着像「链路断了」）。
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'http://localhost:8088'
WORK = r'C:\Users\86159\Desktop\软件工程\期末项目\_work'
IDS = WORK + r'\b5c4-probe-ids.json'

MERCHANT = 'dRD3gqtbFr'   # 演示店 yunqi_digital
GOODS_A = 79              # 路径 A（管理端邀请 → 商家应答）
GOODS_B = 82              # 路径 B（商家申请 → 管理端审核）

OUT = {}


def post(path, **kw):
    body = urllib.parse.urlencode({k: v for k, v in kw.items() if v is not None}).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=body,
                                 headers={'Content-Type': 'application/x-www-form-urlencoded'})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode('utf-8'))


def utc(hours):
    """当前 UTC 时刻 ± N 小时，`YYYY-MM-DD HH:MM:SS`（后端 DATETIME 正则接受的形状）。"""
    t = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=hours)
    return t.strftime('%Y-%m-%d %H:%M:%S')


def step(n, title, data):
    print('=== %s · %s' % (n, title))
    print(json.dumps(data, ensure_ascii=False, sort_keys=True))
    return data


def save_ids():
    with open(IDS, 'w', encoding='utf-8') as f:
        json.dump(OUT, f, ensure_ascii=False, indent=2)


def main():
    # ── 路径 A：管理端邀请商品 A（窗口横跨当下 ⇒ 应答后当场是 live）────────────────────
    r = step('A1', '管理端邀请', post('/admin/marketing/seckill/invite',
                                 MerchantAccount=MERCHANT, GoodsId=GOODS_A,
                                 SeckillPrice='99', Quantity='10',
                                 ApplyEndAt=utc(-3), StartAt=utc(-2), EndAt=utc(2)))
    assert r.get('success') is True, r
    OUT['invitationId'] = r['Id']
    OUT['invitationBiz'] = r['InvitationId']

    # ── 同商家同商品再来一条「待应答」⇒ 应被挡 ────────────────────────────────────────
    step('A2', '重复邀请应被挡', post('/admin/marketing/seckill/invite',
                                 MerchantAccount=MERCHANT, GoodsId=GOODS_A,
                                 SeckillPrice='99', Quantity='10',
                                 ApplyEndAt=utc(-3), StartAt=utc(-2), EndAt=utc(2)))

    # ── 商家在消息中心同意 ⇒ 当场成立活动 ────────────────────────────────────────────
    r = step('A3', '商家应答（同意）', post('/marketing/seckill/respond',
                                       MerchantAccount=MERCHANT, Id=str(OUT['invitationId']),
                                       Accept='true', Reason=''))
    assert r.get('success') is True, r
    OUT['activityA'] = r['ActivityId']

    # ── 再点一次 ⇒ already-handled（**不是失败**）────────────────────────────────────
    step('A4', '重复应答', post('/marketing/seckill/respond',
                            MerchantAccount=MERCHANT, Id=str(OUT['invitationId']),
                            Accept='true', Reason=''))

    # ── 用户端：应看到刚成立的那场，Stage=live、剩余 10 件 ────────────────────────────
    r = step('A5', '用户端 /seckill/live', post('/marketing/seckill/live', limit='8'))
    hit = [x for x in r['list'] if x.get('ActivityId') == OUT['activityA']]
    assert len(hit) == 1, 'live 里没找到刚成立的活动'
    assert hit[0].get('Stage') == 'live', hit[0]
    assert hit[0].get('SoldOut') is False and hit[0].get('SoldLeft') == 10, hit[0]
    OUT['liveFields'] = sorted(hit[0].keys())
    print('   live 行的列：' + ','.join(OUT['liveFields']))

    # ── 路径 B：商家报名商品 B ⇒ 管理端审核通过（不重叠窗口）────────────────────────
    r = step('B1', '商家报名', post('/marketing/seckill/apply',
                                MerchantAccount=MERCHANT, GoodsId=GOODS_B,
                                SeckillPrice='49', Quantity='5'))
    assert r.get('success') is True, r
    OUT['applicationId'] = r['Id']
    OUT['applicationBiz'] = r['ApplicationId']

    r = step('B2', '管理端审核通过（活动名留空 ⇒ 回落商品名）',
             post('/admin/marketing/seckill/review', Id=str(OUT['applicationId']),
                  Approve='true', Reason='', Name='',
                  ApplyEndAt=utc(-3), StartAt=utc(-2), EndAt=utc(3)))
    assert r.get('success') is True, r
    assert r.get('Name'), '活动名回落失败（应回落商品名）'
    OUT['activityB'] = r['ActivityId']

    # ── 重叠闸（申请路径）：同一商品再报一次，审核时给**重叠**窗口 ⇒ 应被拒 ───────────
    r = step('B3', '同商品再报一次', post('/marketing/seckill/apply',
                                      MerchantAccount=MERCHANT, GoodsId=GOODS_B,
                                      SeckillPrice='39', Quantity='5'))
    assert r.get('success') is True, r
    OUT['applicationId2'] = r['Id']
    step('B4', '审核重叠窗口 ⇒ 应被拒', post('/admin/marketing/seckill/review',
                                         Id=str(OUT['applicationId2']), Approve='true',
                                         Reason='', Name='',
                                         ApplyEndAt=utc(-3), StartAt=utc(-1), EndAt=utc(1)))

    # ── 重叠闸（邀请路径）：商品 A 再来一条重叠窗口的邀请，商家同意 ⇒ 也应被拒 ────────
    r = step('B5', '商品 A 再邀请（重叠窗口）', post('/admin/marketing/seckill/invite',
                                              MerchantAccount=MERCHANT, GoodsId=GOODS_A,
                                              SeckillPrice='89', Quantity='3',
                                              ApplyEndAt=utc(-3), StartAt=utc(-1), EndAt=utc(1)))
    assert r.get('success') is True, r
    OUT['invitationId2'] = r['Id']
    step('B6', '应答重叠窗口 ⇒ 应被拒', post('/marketing/seckill/respond',
                                         MerchantAccount=MERCHANT, Id=str(OUT['invitationId2']),
                                         Accept='true', Reason=''))

    # ── 驳回也要理由（缺理由应被挡，且一条 SQL 都不发）─────────────────────────────
    step('B7', '驳回缺理由 ⇒ 应被拒', post('/admin/marketing/seckill/review',
                                       Id=str(OUT['applicationId2']), Approve='false', Reason=''))

    # ── 停用：用户端应立刻看不到这场 ────────────────────────────────────────────────
    step('C1', '停用活动 A', post('/admin/marketing/seckill/stop', ActivityId=OUT['activityA']))
    r = step('C2', '停用后 /seckill/live', post('/marketing/seckill/live', limit='8'))
    assert not [x for x in r['list'] if x.get('ActivityId') == OUT['activityA']], '停用的活动仍在 live 里'
    step('C3', '管理端活动列表（stopped）', post('/admin/marketing/seckill/activities',
                                            Status='stopped', MerchantAccount=MERCHANT, limit='10'))
    step('C4', '商家活动列表', post('/marketing/seckill/activities',
                                MerchantAccount=MERCHANT, limit='10'))

    # ── 归属校验：商品不属于该商家 ⇒ 应被挡 ─────────────────────────────────────────
    step('C5', '拿别家的商品邀请 ⇒ 应被拒', post('/admin/marketing/seckill/invite',
                                          MerchantAccount='x', GoodsId=GOODS_A,
                                          SeckillPrice='9', StartAt=utc(1), EndAt=utc(2)))

    save_ids()
    print('=== IDS 已写：' + IDS)
    print(json.dumps(OUT, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    if '--clean' in sys.argv:
        print('（清理见 _work/b5c4-clean.sql，本脚本只跑链路）')
        sys.exit(0)
    main()
