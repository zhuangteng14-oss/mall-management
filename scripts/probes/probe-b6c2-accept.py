# -*- coding: utf-8 -*-
"""C3 块 6 · commit 2〈问答〉· 采纳答案的手验探针（2026-10-13）。

跑什么：`/comment/accept` 的五道闸逐条走一遍（含「采纳后不可改」的第二通）+ 采纳后的
        **置顶**（`Replies[0]` 是不是被采纳那条）+ `comment.accepted` 通知有没有真落库。

用法：python scripts/probes/probe-b6c2-accept.py
      python scripts/probes/probe-b6c2-accept.py --clean   # 只按上一次的 id 清单清理

⚠️ 为什么用 Python 而不是 curl：Windows 下 curl 传中文按 GBK 出去、服务端收到乱码
   （`probe-must-verify-precondition` 记过）；本探针所有请求体一律 UTF-8 编码。
   本探针的正文**刻意全用 ASCII**：即便编码出岔子，也能一眼看出「是编码坏的」还是
   「是业务判错」，不把两件事混在一起。
⚠️ a3 profile 的 `app.auth.enabled=false`（闸关）⇒ `IdentityGuard` 三处 fail-open 全放行
   ⇒ 本探针**验不到 403 那条线**（它验的是「闸开着 + 传别人的登录名」），只验业务规则。
   **闸关 ⇒ 手验绿 ≠ 默认 profile 绿。**
⚠️ a3 profile 另有 `mall.comment.question.timeout.enabled=false` ⇒ 本探针跑的时候那个
   @Scheduled 不会醒，库里只会有本探针自己造出来的通知行（便于核对条数）。
"""
import json
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')   # 见 `probe-b5c4-seckill-e2e.py` 同款理由

BASE = 'http://localhost:8088'
WORK = r'C:\Users\86159\Desktop\软件工程\期末项目\_work'
IDS = WORK + r'\b6c2-probe-ids.json'

GOODS = 76            # 无线蓝牙耳机 声谷标准版
ASKER = 'chenh99'     # 客户（Role=1）
OTHER = 'gaoq44'      # 另一个客户（用来演「不是提问者」）
MERCHANT = 'beichen_home'

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


def expect(n, title, data, want_msg):
    """断言一句话术。⚠️ 不符期望**先记下来继续跑**，不要在第一次不符就中断 ——
    一次跑完拿到全貌，比跑第八次才知道第八条也不行有用（`mutation-control-criterion`）。"""
    got = data.get('msg')
    ok = (data.get('success') is False) and (got == want_msg)
    if not ok:
        FAILED.append('%s 期望「%s」，实得 success=%s msg=%r' % (n, want_msg, data.get('success'), got))
    step(n, title + ('  [OK]' if ok else '  [!! 不符期望]'), data)
    return data


def save_ids():
    with open(IDS, 'w', encoding='utf-8') as f:
        json.dump(OUT, f, ensure_ascii=False, indent=2)


def load_ids():
    with open(IDS, encoding='utf-8') as f:
        return json.load(f)


def make_question(account, text):
    d = post('/comment/add', Account=account, TargetType='goods', TargetId=GOODS,
             Kind='question', Content=text)
    if not d.get('success'):
        raise SystemExit('造提问失败：%s' % json.dumps(d, ensure_ascii=False))
    return d


def make_answer(account, parent_id, text):
    d = post('/comment/add', Account=account, ParentId=parent_id, Content=text)
    if not d.get('success'):
        raise SystemExit('造回答失败：%s' % json.dumps(d, ensure_ascii=False))
    return d


def main():
    # ── 造数据 ────────────────────────────────────────────────────────────────
    q1 = make_question(ASKER, 'Q1 does this fit a small head?')
    OUT['Q1'] = q1['Id']
    a1 = make_answer(MERCHANT, q1['Id'], 'A1 merchant answer')     # 商家首复 ⇒ 本来就置顶
    a2 = make_answer(OTHER, q1['Id'], 'A2 another user answer')
    OUT['A1'] = a1['Id']
    OUT['A2'] = a2['Id']

    q2 = make_question(OTHER, 'Q2 unrelated question')
    a3 = make_answer(MERCHANT, q2['Id'], 'A3 answer of Q2')
    OUT['Q2'] = q2['Id']
    OUT['A3'] = a3['Id']

    q3 = make_question(ASKER, 'Q3 to be deleted')
    OUT['Q3'] = q3['Id']
    post('/comment/delete', Account=ASKER, Id=q3['Id'])

    # Q4 = **置顶那一测的专用楼**。⚠️ 为什么不能拿 Q1 来测置顶：Q1 的商家首复（A1）本来
    # 就按「商家首复置顶」排在第一位（`selectReplies`）—— 采纳它、它还在第一位，**看不出
    # 置顶有没有生效**。Q4 让商家先答（A4，天然在首位）、客户后答（A5，天然在末位），
    # 再采纳 **A5** ⇒ 「末位跳到首位」才是能证伪的那一测。
    q4 = make_question(ASKER, 'Q4 pinning scenario')
    a4 = make_answer(MERCHANT, q4['Id'], 'A4 merchant first reply')   # 商家首复 ⇒ 天然首位
    a5 = make_answer(OTHER, q4['Id'], 'A5 later customer answer')     # 后到 ⇒ 天然末位
    OUT['Q4'] = q4['Id']
    OUT['A4'] = a4['Id']
    OUT['A5'] = a5['Id']
    OUT['_top'] = [[q1['Id'], ASKER], [q2['Id'], OTHER], [q4['Id'], ASKER]]   # `--clean` 用
    save_ids()

    print('造好：Q1=%s(A1=%s,A2=%s) / Q2=%s(A3=%s) / Q3=%s(已删) / Q4=%s(A4=%s,A5=%s)'
          % (q1['Id'], a1['Id'], a2['Id'], q2['Id'], a3['Id'], q3['Id'], q4['Id'], a4['Id'], a5['Id']))

    # ── 五道闸 ────────────────────────────────────────────────────────────────
    expect('G1', '提问不存在', post('/comment/accept', Account=ASKER, Id=99999999, ReplyId=a1['Id']),
           '提问不存在')
    expect('G2', '已删的提问', post('/comment/accept', Account=ASKER, Id=q3['Id'], ReplyId=a1['Id']),
           '该提问已删除')
    expect('G3', '拿一条「回复」当提问对象',
           post('/comment/accept', Account=MERCHANT, Id=a1['Id'], ReplyId=a2['Id']),
           '只能对顶层提问采纳答案')
    expect('G4', '不是提问者本人（客户）',
           post('/comment/accept', Account=OTHER, Id=q1['Id'], ReplyId=a1['Id']),
           '只有提问者本人可以采纳答案')
    expect('G5', '拿别人提问下的回答来采纳',
           post('/comment/accept', Account=ASKER, Id=q1['Id'], ReplyId=a3['Id']),
           '这条回答不属于该提问')
    expect('G6', '回答不存在',
           post('/comment/accept', Account=ASKER, Id=q1['Id'], ReplyId=99999999),
           '该回答不存在')

    # ── 正路：采纳 ────────────────────────────────────────────────────────────
    ok = step('P1', '采纳 A1（提问者本人）',
              post('/comment/accept', Account=ASKER, Id=q1['Id'], ReplyId=a1['Id']))
    if ok.get('success') is not True:
        FAILED.append('P1 采纳本该成功，实得 %s' % json.dumps(ok, ensure_ascii=False))

    # ── 第六道：采纳后不可改（第二通）─────────────────────────────────────────
    expect('G7', '再采纳一次（同一条）',
           post('/comment/accept', Account=ASKER, Id=q1['Id'], ReplyId=a1['Id']),
           '该提问已采纳过答案，不能更改')
    expect('G8', '已采纳后改采纳另一条',
           post('/comment/accept', Account=ASKER, Id=q1['Id'], ReplyId=a2['Id']),
           '该提问已采纳过答案，不能更改')

    # ── 置顶（Q4：末位 → 首位）+ IsSolved ─────────────────────────────────────
    def replies_of(qid):
        """取某条提问的回复次序（**按 Id 找行**，不是取 `list[0]` —— 列表按时间倒序，
        `list[0]` 是最近那条提问，拿它当 Q1 会读到另一座楼）。"""
        r = post('/comment/list', TargetType='goods', TargetId=GOODS, Kind='question')
        row = next((x for x in r['list'] if x['Id'] == qid), None)
        return r, row, (row.get('Replies', []) if row else [])

    _, _, before = replies_of(q4['Id'])
    print('=== P0 · 采纳**前** Q4 的 Replies 次序（A4 商家首复天然首位、A5 后到末位）')
    print(json.dumps([{'Id': r['Id'], 'Accepted': r.get('Accepted')} for r in before],
                     ensure_ascii=False))
    if [r['Id'] for r in before] != [a4['Id'], a5['Id']]:
        FAILED.append('P0 前置不成立：期望 [A4,A5]=[%s,%s]，实得 %s'
                      % (a4['Id'], a5['Id'], [r['Id'] for r in before]))

    step('P1b', '采纳 A5（**末位**那条，才验得出置顶）',
         post('/comment/accept', Account=ASKER, Id=q4['Id'], ReplyId=a5['Id']))

    lst, q4row, rep = replies_of(q4['Id'])
    print('=== P2 · 采纳**后** Q4 的 Replies 次序与标记')
    print(json.dumps([{'Id': r['Id'], 'Accepted': r.get('Accepted'), 'Role': r.get('AuthorRole')}
                      for r in rep], ensure_ascii=False))
    if not rep or rep[0]['Id'] != a5['Id']:
        FAILED.append('P2 被采纳的 A5 没有置顶：Replies=%s' % json.dumps(
            [r['Id'] for r in rep], ensure_ascii=False))
    if rep and not rep[0].get('Accepted'):
        FAILED.append('P2 置顶那条的 Accepted 不是 true')
    if len(rep) > 1 and rep[1]['Id'] != a4['Id']:
        FAILED.append('P2 未被采纳的 A4 没守住原序：%s' % json.dumps(
            [r['Id'] for r in rep], ensure_ascii=False))

    print('=== P2b · Q4 顶层行的 IsSolved')
    print(json.dumps({'IsSolved': q4row.get('IsSolved') if q4row else None},
                     ensure_ascii=False))
    if q4row and str(q4row.get('IsSolved')) not in ('1', 'True', 'true'):
        FAILED.append('P2b IsSolved 不是 1：%r' % q4row.get('IsSolved'))
    print('=== P2c · stats（问答区那份，整件商品）')
    print(json.dumps(lst.get('stats'), ensure_ascii=False))

    # ── 通知 ──────────────────────────────────────────────────────────────────
    print('=== P3 · 通知期望「一条 comment.accepted，收件人是 A1 的作者」')
    print('  用 SQL 核对（探针不直连库）：')
    print("  SELECT Id,ToId,ToType,EventType,TargetType,TargetId,Title FROM notification")
    print("   WHERE EventType='comment.accepted' AND TargetId=%r ORDER BY Id;" % str(q1['Id']))

    print()
    if FAILED:
        print('!! 有 %d 处不符期望：' % len(FAILED))
        for f in FAILED:
            print('   - ' + f)
        return 1
    print('全部符合期望。')
    return 0


def clean():
    """按上一次留下的清单清理。⚠️ 顶层删除**连带**回复（`softDeleteRepliesUnder`）
    ⇒ 只删两条顶层提问即可，回复不必逐条删（逐条删也删得掉，但没必要多发往返）。
    删的身份用**这条顶层原本的作者**：`CommentService.delete` 判的是 `AuthorId`（不是身份闸）。"""
    ids = load_ids()
    for qid, owner in ids.get('_top', []):
        d = post('/comment/delete', Account=owner, Id=qid)
        print('  删 %s（作者 %s）⇒ %s' % (qid, owner, json.dumps(d, ensure_ascii=False)))
    print('清理完毕。')


if __name__ == '__main__':
    sys.exit(clean() if '--clean' in sys.argv else main())
