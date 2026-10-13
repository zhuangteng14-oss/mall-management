# -*- coding: utf-8 -*-
"""C3 块 6 · commit 3〈@ 自动生成〉· 手验探针（2026-10-13）。

跑什么：方案 §4.1 的三条 @ 规则各走一遍 + 两条边界：
  A1 顶层提问      → @ 该商品商家
  A2 回复顶层      → @ 顶层作者
  A3 回复回复      → @ 被回复人（且**匿名回答**⇒通知正文不得出现作者名）
  A4 作者回复自己的顶层（自 @）⇒ 既不落 comment_mention、也不发通知
  B1 顶层评价      → @ 该商品商家（与提问同一条解析路径，用来验「评价」这一侧）
  B2 作者回复自己的评价（自 @）⇒ 同 A4

每条都验两处副作用：
  ① `comment_mention` 落了哪一行 —— 探针不直连库，把 SQL 打出来（照 `probe-b6c2-accept.py` 口径）
  ② `comment.mention` 通知是否**投得进收件人读得到的抽屉** —— 走 `/notification/list` API 真读
     （⚠️ 这是本探针的关键一测：`ToType` 若与收件人角色不一致，通知照落库却**没人读得到**）

用法：python scripts/probes/probe-b6c3-mention.py
      python scripts/probes/probe-b6c3-mention.py --clean    # 打印清理 SQL（供 `_work/Sql.java --exec`）

⚠️ 为什么用 Python 而不是 curl：Windows 下 curl 传中文按 GBK 出去、服务端收到乱码
   （`probe-must-verify-precondition` 记过）；本探针所有请求体一律 UTF-8。
   正文**刻意全用 ASCII**，把「编码坏了」与「业务判错」两件事分开。
⚠️ a3 profile `app.auth.enabled=false`（闸关）⇒ `IdentityGuard` fail-open 全放行
   ⇒ **闸关 ⇒ 手验绿 ≠ 默认 profile 绿**。
⚠️ 夹具（2026-10-13 VM 库实测）：
   goods 76 的商家 = `yunqi_digital`（goods.MerchantId=dRD3gqtbFr → user.merchantid）；
   `1111`(Role=1) 买过 goods 76 且有 ReceiveIf=1 结单（客户号 uDopHnCeLS）⇒ 可发顶层评价；
   `chenh99`/`gaoq44` 是客户（提问/回答不限购买）。**这些值是数据不是契约，换库要重核。**
"""
import json
import os
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'http://localhost:8088'
WORK = r'C:\Users\86159\Desktop\软件工程\期末项目\_work'
IDS = os.path.join(WORK, 'b6c3-probe-ids.json')
CLEAN_SQL = os.path.join(WORK, 'b6c3-cleanup.sql')

GOODS = 76
MERCHANT = 'yunqi_digital'   # goods 76 的商家登录名
ASKER = 'chenh99'            # 客户
OTHER = 'gaoq44'             # 客户（用来演「回复别人的回答」）
BUYER = '1111'               # 客户，买过 goods 76

MENTION_EVENT = 'comment.mention'
OUT = {}
FAILED = []


def post(path, **kw):
    body = urllib.parse.urlencode({k: v for k, v in kw.items() if v is not None}).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=body,
                                 headers={'Content-Type': 'application/x-www-form-urlencoded'})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode('utf-8'))


def step(n, title, data):
    print('=== %s · %s' % (n, title))
    print(json.dumps(data, ensure_ascii=False, sort_keys=True))
    return data


def check(cond, msg):
    if not cond:
        FAILED.append(msg)
    return cond


def inbox(to_id, to_type):
    """收件人点开消息中心会看到的那批行 —— 走真端点，不读库。"""
    d = post('/notification/list', toId=to_id, toType=to_type, limit=200)
    return d.get('list', []) if d.get('success') else []


def mention_hits(to_id, to_type, comment_id):
    """收件人 `(to_id,to_type)` 收件箱里、指向评论 `comment_id` 的 comment.mention 行。"""
    return [n for n in inbox(to_id, to_type)
            if n.get('EventType') == MENTION_EVENT
            and str(n.get('TargetType')) == 'comment'
            and str(n.get('TargetId')) == str(comment_id)]


def make_question(account, text):
    d = post('/comment/add', Account=account, TargetType='goods', TargetId=GOODS,
             Kind='question', Content=text)
    if not d.get('success'):
        raise SystemExit('造提问失败：%s' % json.dumps(d, ensure_ascii=False))
    return d


def make_review(account, text, anonymous):
    d = post('/comment/add', Account=account, TargetType='goods', TargetId=GOODS,
             Kind='review', Content=text, Rating='4', Anonymous='1' if anonymous else None)
    if not d.get('success'):
        raise SystemExit('造评价失败：%s' % json.dumps(d, ensure_ascii=False))
    return d


def make_reply(account, parent_id, text, reply_to_id=None):
    d = post('/comment/add', Account=account, ParentId=parent_id, ReplyToId=reply_to_id,
             Content=text)
    if not d.get('success'):
        raise SystemExit('造回复失败：%s' % json.dumps(d, ensure_ascii=False))
    return d


def main():
    # ── 造数据 ────────────────────────────────────────────────────────────────
    q = make_question(ASKER, 'Q1 does the widget include a spare cable')
    a_m = make_reply(MERCHANT, q['Id'], 'A1 merchant: yes a spare cable is included', None)
    a_o = make_reply(OTHER, q['Id'], 'A2 follow-up question on the answer', a_m['Id'])
    a_s = make_reply(ASKER, q['Id'], 'A3 self reply on own question', None)
    r = make_review(BUYER, 'R1 solid build quality', anonymous=False)
    r_s = make_reply(BUYER, r['Id'], 'R2 self reply on own review', None)

    OUT.update({'Q': q['Id'], 'A_M': a_m['Id'], 'A_O': a_o['Id'], 'A_S': a_s['Id'],
                'R': r['Id'], 'R_S': r_s['Id'],
                '_top': [[q['Id'], ASKER], [r['Id'], BUYER]]})
    save_ids()
    print('造好：Q=%s(A_M=%s,A_O=%s,A_S=%s) / R=%s(R_S=%s)'
          % (q['Id'], a_m['Id'], a_o['Id'], a_s['Id'], r['Id'], r_s['Id']))
    print()

    # ── A1 顶层提问 → @ 该商品商家 ────────────────────────────────────────────
    hits = mention_hits(MERCHANT, 'merchant', q['Id'])
    check(len(hits) == 1, 'A1 商家收件箱里指向提问 %s 的 comment.mention 行应为 1，实得 %d'
          % (q['Id'], len(hits)))
    step('A1', '顶层提问 → 商家收到 comment.mention（应 1 行）',
         [{'ToId': n.get('ToId'), 'ToType': n.get('ToType'), 'Title': n.get('Title'),
           'Content': n.get('Content')} for n in hits])

    # ── A2 回复顶层 → @ 顶层作者（客户）──────────────────────────────────────
    hits = mention_hits(ASKER, 'customer', a_m['Id'])
    check(len(hits) == 1, 'A2 提问者收件箱里指向回答 %s 的 comment.mention 行应为 1，实得 %d'
          % (a_m['Id'], len(hits)))
    step('A2', '回复顶层 → 顶层作者收到 comment.mention（应 1 行；商家回答实名⇒正文带其名）',
         [{'ToType': n.get('ToType'), 'Content': n.get('Content')} for n in hits])
    check(hits and MERCHANT in (hits[0].get('Content') or ''),
          'A2 商家回答**实名**，正文该出现 %s，实得 %r' % (MERCHANT, hits[0].get('Content') if hits else None))

    # ── A3 回复回复 → @ 被回复人（= A1 的作者，商家）；回答匿名 ⇒ 正文不得露作者名 ──
    hits = mention_hits(MERCHANT, 'merchant', a_o['Id'])
    check(len(hits) == 1, 'A3 被回复人收件箱里指向回答 %s 的 comment.mention 行应为 1，实得 %d'
          % (a_o['Id'], len(hits)))
    step('A3', '回复回复 → 被回复人（商家）收到 comment.mention（应 1 行）',
         [{'ToType': n.get('ToType'), 'Content': n.get('Content')} for n in hits])
    body = hits[0].get('Content') if hits else ''
    check(OTHER not in body, 'A3 回答恒匿名 ⇒ 正文不得出现作者名 %s，实得 %r' % (OTHER, body))
    check('有人' in body, 'A3 匿名正文该以「有人」起头，实得 %r' % body)

    # ── A4 自 @（回复自己的提问）⇒ 无 mention 行 ─────────────────────────────
    hits = mention_hits(ASKER, 'customer', a_s['Id'])
    step('A4', '作者回复自己的提问（自 @）→ 应 0 行 comment.mention', {'hits': len(hits)})
    check(len(hits) == 0, 'A4 自 @ 不该发通知，实得 %d 行' % len(hits))

    # ── B1 顶层评价 → @ 该商品商家 ────────────────────────────────────────────
    hits = mention_hits(MERCHANT, 'merchant', r['Id'])
    check(len(hits) == 1, 'B1 商家收件箱里指向评价 %s 的 comment.mention 行应为 1，实得 %d'
          % (r['Id'], len(hits)))
    step('B1', '顶层评价 → 商家收到 comment.mention（应 1 行；实名评价⇒正文带作者名）',
         [{'ToType': n.get('ToType'), 'Content': n.get('Content')} for n in hits])
    check(hits and BUYER in (hits[0].get('Content') or ''),
          'B1 评价**实名**，正文该出现 %s，实得 %r' % (BUYER, hits[0].get('Content') if hits else None))

    # ── B2 自 @（回复自己的评价）⇒ 无 mention 行 ──────────────────────────────
    hits = mention_hits(BUYER, 'customer', r_s['Id'])
    step('B2', '作者回复自己的评价（自 @）→ 应 0 行 comment.mention', {'hits': len(hits)})
    check(len(hits) == 0, 'B2 自 @ 不该发通知，实得 %d 行' % len(hits))

    # ── 库里那几行（探针不直连库，SQL 打出来给 `_work/Sql.java` 跑）────────────
    print()
    print('=== M · comment_mention 期望恰好这几行（其余为 0）：')
    print('   CommentId=%s → MentionedId=%s   (A1 顶层提问→商家)' % (q['Id'], MERCHANT))
    print('   CommentId=%s → MentionedId=%s   (A2 回复顶层→提问者)' % (a_m['Id'], ASKER))
    print('   CommentId=%s → MentionedId=%s   (A3 回复回复→被回复人)' % (a_o['Id'], MERCHANT))
    print('   CommentId=%s → MentionedId=%s   (B1 顶层评价→商家)' % (r['Id'], MERCHANT))
    print('   且 CommentId IN (%s,%s) 必须**一行都没有**（A4/B2 自 @）' % (a_s['Id'], r_s['Id']))
    print("   SELECT CommentId, MentionedId FROM comment_mention WHERE CommentId IN "
          "(%s,%s,%s,%s,%s,%s) ORDER BY CommentId;"
          % (q['Id'], a_m['Id'], a_o['Id'], a_s['Id'], r['Id'], r_s['Id']))

    print()
    if FAILED:
        print('!! 有 %d 处不符期望：' % len(FAILED))
        for f in FAILED:
            print('   - ' + f)
        return 1
    print('全部符合期望（通知侧）。comment_mention 那几行按上面 SQL 核对。')
    return 0


def cleanup_sql():
    """⚠️ `_work/Sql.java` 一次只吃**一条**语句（JDBC 未开 allowMultiQueries）⇒ 逐条给。"""
    ids = load_ids()
    allc = [str(ids[k]) for k in ('Q', 'A_M', 'A_O', 'A_S', 'R', 'R_S') if k in ids]
    return [
        'DELETE FROM comment_mention WHERE CommentId IN (%s);' % ','.join(allc),
        "DELETE FROM notification WHERE TargetType='comment' AND TargetId IN (%s);" % ','.join(allc),
        'DELETE FROM comment WHERE Id IN (%s);' % ','.join(allc),
    ]


def save_ids():
    with open(IDS, 'w', encoding='utf-8') as f:
        json.dump(OUT, f, ensure_ascii=False, indent=2)


def load_ids():
    with open(IDS, encoding='utf-8') as f:
        return json.load(f)


if __name__ == '__main__':
    if '--clean' in sys.argv:
        stmts = cleanup_sql()
        with open(CLEAN_SQL, 'w', encoding='utf-8') as f:
            f.write('\n'.join(stmts) + '\n')
        print('-- 逐条执行（Sql.java 一次只吃一条语句）；清单已落 %s' % CLEAN_SQL)
        for s in stmts:
            print(s)
        sys.exit(0)
    sys.exit(main())
