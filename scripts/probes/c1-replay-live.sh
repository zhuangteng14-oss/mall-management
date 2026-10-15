#!/bin/bash
# 块 7 收尾 · 探针：`/admin/observe/replay` 的四条判据（真库，不是替身）。
#
# 判据：
#   ① `batchId` 空白 ⇒ 400（不是 200 + success:false）
#   ② 不认识的批次 ⇒ 200 + list 空 + total 0
#   ③ **要真的读得到**：先开一条事件流（= 扮演「当时开着日志页」），再跑一轮演练
#      ⇒ 台账落行 ⇒ 用那一轮的 batchId 回放，`list` 非空
#      （⚠️ 不开流就跑，台账**一行都不会有** —— 这是 TD-61 一族的既有口径，
#       也正是页面上那句「回放数据来自「当时开着日志页的演练」」的由来）
#   ④ 排序：`OccurredAt` 升序；同毫秒内 `Id` 升序；`limit` 截断时 `total` 仍是整批
set -u
BASE=http://127.0.0.1:8088
W=../../_work/t-replay
DUR=${1:-6}

echo "== ① blank batchId =="
curl -s -o "$W-blank.json" -w "HTTP=%{http_code} " -X POST "$BASE/admin/observe/replay" -d "batchId="
head -c 200 "$W-blank.json"; echo

echo "== ② unknown batch =="
curl -s -o "$W-unknown.json" -w "HTTP=%{http_code} " -X POST "$BASE/admin/observe/replay" \
  -d "batchId=00000000-0000-0000-0000-000000000000"
head -c 200 "$W-unknown.json"; echo

echo "== ③ 开事件流 + 跑一轮（8 秒）=="
curl -s -N -X POST "$BASE/admin/observe/events/stream" > "$W-stream.txt" 2>&1 &
STREAM_PID=$!
sleep 1
curl -s -X POST "$BASE/admin/observe/attack/run" -d "name=replayprobe" -d "concurrency=50" \
  -d "durationSeconds=$DUR" -d "targetQps=120" > "$W-run.json"
kill $STREAM_PID 2>/dev/null
# ⚠️ `batchId` 只有一个来源：`/attack/records` 每行的 `BatchId`（块 7 收尾补的那一列）。
#    `/attack/run` 的响应根上**没有**它（实测 10 个键，无 BatchId）—— 这不是漏读，是本笔
#    之前的实情：那个值只在库里，从来没往响应里露过。
BATCH=$(curl -s -X POST "$BASE/admin/observe/attack/records" -d "limit=1" \
  | python -c "import json,sys;d=json.load(sys.stdin);print(d['list'][0].get('BatchId') or '')" 2>/dev/null)
echo "batchId=$BATCH（流收到 $(grep -c '^data:' "$W-stream.txt") 帧）"
[ -z "$BATCH" ] && { echo "拿不到 batchId，停"; exit 1; }

echo "== ③b 回放该批次 =="
curl -s -o "$W-full.json" -w "HTTP=%{http_code} " -X POST "$BASE/admin/observe/replay" -d "batchId=$BATCH"
python - "$W-full.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
rows = d.get('list', [])
print("success=%s total=%s limit=%s rows=%d" % (d.get('success'), d.get('total'), d.get('limit'), len(rows)))
if rows:
    print("first:", json.dumps(rows[0], ensure_ascii=False))
    print("last :", json.dumps(rows[-1], ensure_ascii=False))
    ts = [r['OccurredAt'] for r in rows]
    ids = [r['Id'] for r in rows]
    print("OccurredAt 升序:", ts == sorted(ts))
    # 同毫秒内 Id 升序（按 (OccurredAt, Id) 排序是否等于原序）
    print("(OccurredAt, Id) 升序:", list(zip(ts, ids)) == sorted(zip(ts, ids)))
    print("毫秒位样例:", ts[0][-4:], "| 列名:", list(rows[0].keys()))
PY

echo "== ④ limit=5（total 必须仍是整批）=="
curl -s -o "$W-lim.json" -X POST "$BASE/admin/observe/replay" -d "batchId=$BATCH" -d "limit=5"
python - "$W-lim.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
print("rows=%d total=%s limit=%s" % (len(d.get('list', [])), d.get('total'), d.get('limit')))
PY

echo "== ⑤ limit=abc（必须 400）=="
curl -s -o /dev/null -w "HTTP=%{http_code}\n" -X POST "$BASE/admin/observe/replay" -d "batchId=$BATCH" -d "limit=abc"
