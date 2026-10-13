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
     ⚠️ **2026-10-13 修复本探针的一个假绿**：收件箱此前是拿**登录名**去拉的，
        而 `notification.ToId` 的契约是**业务号** —— 本探针与当时的缺陷**共用同一错误前提**，
        所以「@ 已落」这条结论一直是绿的而线上一条都收不到。现改为经 `BIZ` 表换算成业务号，
        并加了一条**反向控制**（旧口径必须恒空，见 `stale_domain_hits`）。

用法：python scripts/probes/probe-b6c3-mention.py
      python scripts/probes/probe-b6c3-mention.py --clean    # 打印清理 SQL（供 `_work/Sql.java --exec`）

⚠️ 为什么用 Python 而不是 curl：Windows 下 curl 传中文按 GBK 出去、服务端收到乱码
   （`probe-must-verify-precondition` 记过）；本探针所有请求体一律 UTF-8。
   正文**刻意全用 ASCII**，把「编码坏了」与「业务判错」两件事分开。
⚠️ a3 profile `app.auth.enabled=false`（闸关）⇒ `IdentityGuard` fail-open 全放行
   ⇒ **闸关 ⇒ 手验绿 ≠ 默认 profile 绿**。
⚠️ 夹具（2026-10-13 VM 库实测）：
   goods 76 / 79 的商家都是 `yunqi_digital`（goods.MerchantId=dRD3gqtbFr → user.merchantid）；
   A 组发**提问**（`chenh99` / `gaoq44` 是客户；提问不限购买）⇒ 目标 goods 76；
   B 组发**顶层评价**（要求买过）⇒ 目标 goods 79、买家 `123`（Role=1，customerid=pIzTWafaGp）。
   ⚠️ **B 组为什么不也用 goods 76**：本仓「同一个人在同一件商品上只能发一条顶层评价」
      （`MSG_DUPLICATE_REVIEW`）。goods 76 上两个有 ReceiveIf=1 结单的买家（`1111` / `123`）
      都**已经**有 normal 顶层评价 ⇒ 谁也发不出第二条，B 组在 76 上已无干净夹具。
      goods 79 与 76 同属 `yunqi_digital`（收件人不变），且 `123` 在 79 上一条 comment 都没有
      ⇒ 换目标商品、不换被 @ 的商家，断言强度不变。**这些值是数据不是契约，换库要重核。**
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
MERCHANT = 'yunqi_digital'   # goods 76 / 79 的商家登录名
ASKER = 'chenh99'            # 客户（提问不限购买 ⇒ 可反复造顶层提问）
OTHER = 'gaoq44'             # 客户（用来演「回复别人的回答」）
BUYER = '123'                # 客户，买过 goods 79（B 组发顶层评价用）
GOODS_B = 79                 # B 组的目标商品 —— 同属 MERCHANT 这家店，见文件头 ⚠️

# ⚠️ **2026-10-13 修复：收件箱要拿「业务号」拉，不是登录名。**
#    `notification.ToId` 的契约是**业务号**（`NotificationController` 的 javadoc 逐字；
#    `NotificationDao.selectCustomerIds` 取的就是 `customerid`），而本探针手里的夹具
#    （`comment.AuthorId` / `Account` 入参）与 `login.UserName` 一样全是**登录名** —— 两种号不同域。
#    修复前本探针直接拿登录名去拉收件箱：**它和当时的缺陷共用同一错误前提**，故必然全绿
#    （「校验器报 OK ≠ 没问题」的一个实例 —— 它在查「ToId 相等」，但不查「ToId 是哪个域的号」）。
#    ⇒ 下面这张表是「登录名 → (业务号, 角色)」，只用于**读收件箱**；
#      `/comment/add` 的 `Account` 仍传**登录名**（那是那个端点的契约，一字不改）。
# ⚠️ 这些值是数据不是契约，换库要重核（同上面夹具那段）。
BIZ = {
    MERCHANT: ('dRD3gqtbFr', 'merchant'),   # user.merchantid
    ASKER:    ('bcmJhZaB5D', 'customer'),   # user.customerid
    OTHER:    ('e6BSqKr61h', 'customer'),
    BUYER:    ('pIzTWafaGp', 'customer'),   # user.customerid of `123`
}

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


def mention_hits(login, to_type, comment_id):
    """收件人 `login` 的收件箱里、指向评论 `comment_id` 的 comment.mention 行。

    ⚠️ **传登录名，内部换算成业务号再拉**（见 `BIZ` 那段）。2026-10-13 之前这里直接拿
    登录名去拉 —— 与当时那半个缺陷**共用同一错误前提**，所以怎么跑都是绿的。

    ⚠️ 顺带一条**反向控制**：把「登录名口径」也读一遍，必须**空**。
    见 `stale_domain_hits` / `main` 里的 `check(...)`：修复若被回退（有人把发送方改回写登录名），
    这条会立刻红 —— 而「正向 1 行」那条**不会**（它会跟着一起变绿，因为两边又同域了）。
    """
    return [n for n in inbox(*BIZ[login]) if _is_mention_to(n, comment_id)]


def stale_domain_hits(login, to_type, comment_id):
    """**旧口径**（拿登录名当 ToId 拉）——修复后必须恒空。反向控制，见 `mention_hits` 的 ⚠️。"""
    return [n for n in inbox(login, to_type) if _is_mention_to(n, comment_id)]


def _is_mention_to(n, comment_id):
    return (n.get('EventType') == MENTION_EVENT
            and str(n.get('TargetType')) == 'comment'
            and str(n.get('TargetId')) == str(comment_id))


def make_question(account, text):
    d = post('/comment/add', Account=account, TargetType='goods', TargetId=GOODS,
             Kind='question', Content=text)
    if not d.get('success'):
        raise SystemExit('造提问失败：%s' % json.dumps(d, ensure_ascii=False))
    return d


def make_review(account, goods, text, anonymous):
    d = post('/comment/add', Account=account, TargetType='goods', TargetId=goods,
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
    q = remember('Q', make_question(ASKER, 'Q1 does the widget include a spare cable'))
    a_m = remember('A_M', make_reply(MERCHANT, q['Id'],
                                     'A1 merchant: yes a spare cable is included', None))
    a_o = remember('A_O', make_reply(OTHER, q['Id'], 'A2 follow-up question on the answer',
                                     a_m['Id']))
    a_s = remember('A_S', make_reply(ASKER, q['Id'], 'A3 self reply on own question', None))
    r = remember('R', make_review(BUYER, GOODS_B, 'R1 solid build quality', anonymous=False))
    r_s = remember('R_S', make_reply(BUYER, r['Id'], 'R2 self reply on own review', None))

    OUT['_top'] = [[q['Id'], ASKER], [r['Id'], BUYER]]
    save_ids()
    print('造好：Q=%s(A_M=%s,A_O=%s,A_S=%s) / R=%s@goods%s(R_S=%s)'
          % (q['Id'], a_m['Id'], a_o['Id'], a_s['Id'], r['Id'], GOODS_B, r_s['Id']))
    print()

    # ── 夹具自检：业务号与登录名必须真是**两个**值 ──────────────────────────────
    # 若某天库里把两者设成同值，本探针的「换算」就退化成恒等 ⇒ 又验不出域问题了。
    for _login, (_biz, _t) in BIZ.items():
        check(_biz != _login,
              '夹具异常：%s 的业务号与登录名同值（%s）—— 本探针就验不出「两种号不同域」了' % (_login, _biz))

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
    step('B1', '顶层评价（goods %s）→ 商家收到 comment.mention（应 1 行；实名评价⇒正文带作者名）'
         % GOODS_B,
         [{'ToType': n.get('ToType'), 'Content': n.get('Content')} for n in hits])
    # ⚠️ 用「「123」」连引号一起找，不用裸 `123` —— 裸串在价格 / 日期里到处都是，会假绿。
    check(hits and ('「%s」' % BUYER) in (hits[0].get('Content') or ''),
          'B1 评价**实名**，正文该出现「%s」，实得 %r'
          % (BUYER, hits[0].get('Content') if hits else None))

    # ── B2 自 @（回复自己的评价）⇒ 无 mention 行 ──────────────────────────────
    hits = mention_hits(BUYER, 'customer', r_s['Id'])
    step('B2', '作者回复自己的评价（自 @）→ 应 0 行 comment.mention', {'hits': len(hits)})
    check(len(hits) == 0, 'B2 自 @ 不该发通知，实得 %d 行' % len(hits))

    # ── N · 反向控制：**旧口径**（拿登录名当 ToId 拉收件箱）必须恒空 ──────────────
    # ⚠️ 这一条是本探针的**真闸门**。上面那些「正向应得 1 行」在修复被回退时**照样会绿**
    #    （发送方与读取方又同域了）⇒ 只有「旧口径必须空」能把回退照出来。
    print()
    stale = [(_l, _c, len(stale_domain_hits(_l, BIZ[_l][1], _c)))
             for _l, _c in ((MERCHANT, q['Id']), (ASKER, a_m['Id']),
                            (MERCHANT, a_o['Id']), (MERCHANT, r['Id']))]
    for _l, _c, _n in stale:
        check(_n == 0, '反向控制失败：拿**登录名** %s 当 ToId 竟拉到 %d 行（comment %s）'
                       ' ⇒ 发送方又写回登录名了（见 BIZ 那段）' % (_l, _n, _c))
    step('N', '反向控制 · 旧口径（登录名当 ToId）必须恒空',
         [{'login': _l, 'comment': _c, 'hits': _n} for _l, _c, _n in stale])

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
    # ⚠️ 并上 `_history`（历轮全部 id）—— 重跑失败时本轮的 key 会顶掉上一轮的 key，
    #    只有这份「只增不减」的清单才清得干净。见 `save_ids()` 的 ⚠️。
    for x in (ids.get('_history') or []):
        if str(x) not in allc:
            allc.append(str(x))
    return [
        'DELETE FROM comment_mention WHERE CommentId IN (%s);' % ','.join(allc),
        "DELETE FROM notification WHERE TargetType='comment' AND TargetId IN (%s);" % ','.join(allc),
        'DELETE FROM comment WHERE Id IN (%s);' % ','.join(allc),
    ]


def remember(key, d):
    """**每造一条就立刻落盘** —— 中途失败（如撞 `MSG_DUPLICATE_REVIEW`）也不留孤儿。

    ⚠️ 2026-10-13：上一版是把 6 个 id 攒到最后一次性 `save_ids()`，结果 B1 撞唯一约束
    抛 `SystemExit` 时，前面 4 条已建的评论既没进 ids 文件、也没人清理 ⇒ 成了孤儿
    （`docs/agent/evidence/c3-block6/` 那次清理的来历）。逐条落盘后，
    `--clean` 永远能覆盖到**已经建出来的每一条**。
    """
    OUT[key] = d['Id']
    save_ids()
    return d


def save_ids():
    """把**本轮**的 id 与**历轮**的 id 并起来落盘（只增不减的 `_history`）。

    ⚠️ 2026-10-13 第二次修（实测事故）：上一版这里是**整份覆盖**。重跑时 B1 撞
       `MSG_DUPLICATE_REVIEW` 抛 `SystemExit`，而此时 `remember()` 已把**本轮**的
       Q / A_M / A_O / A_S 写进文件、把**上一轮**的六个 key（含 B 组两条）**顶掉了**
       ⇒ `--clean` 生成的 DELETE 够不着 217–222 ⇒ 那两条又成孤儿。
       ⇒ 判据：`--clean` 必须覆盖「**历轮造过的每一条**」，不是「最近一轮」。
         做法 = 单开一个 `_history`（全部 comment id，排序去重），`cleanup_sql()` 取它的并集。
       ⚠️ 本文件若有 `_history`，它是**数据快照**（本机 VM 库的 Id），换库要重核 —— 同夹具那段。
    """
    prev = {}
    if os.path.exists(IDS):
        try:
            prev = load_ids()
        except Exception:
            prev = {}
    hist = [int(x) for x in (prev.get('_history') or [])]
    for k, v in OUT.items():
        if k.startswith('_'):          # `_top` / `_history` 本身不进
            continue
        if isinstance(v, int) and v not in hist:
            hist.append(v)
    OUT['_history'] = sorted(hist)     # ⚠️ 必须在遍历 `OUT` 之后再写，否则会被自己覆盖
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
