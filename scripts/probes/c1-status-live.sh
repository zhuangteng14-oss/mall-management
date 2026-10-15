#!/bin/bash
# 块 7 收尾 · 探针：`/attack/status` 的「真在跑」那一支（单测盖不到的那一支）。
#
# 为什么必须有它：`AttackEngineTest` 里那 3 条只能测**空闲**态（`progress()` 全零 / 稳定 /
# stopOnIdle 不留痕）—— 「真在跑时读到非零」需要一轮真的在跑的演练 + 一次真的 HTTP 请求，
# 不是单测能造的。判据（缺一不可）：
#   ① 演练进行中：`Running=true`、`BatchId` 非空且**稳定**、`Sent` 单调不减、`ElapsedMs` 在涨；
#   ② 演练收口后：`Running=false`（不等 `/run` 返回就已经落下）；
#   ③ 全程 `Completed <= Sent`（`Sent` = 已投递含在途，`Completed` = 已收口）。
#
# ⚠️ 名字用纯 ASCII：Windows 下 curl 传中文会变乱码（Mall-management-server 侧列宽截断照收，
#    但回读时对不上账）。⚠️ 端口/profile：dev（`app.auth.enabled=false`，不用票）。
set -u
BASE=http://127.0.0.1:8088
DUR=${1:-8}
OUT=../../_work/t-status-live

curl -s -X POST "$BASE/admin/observe/attack/run" \
  -d "name=statusprobe" -d "concurrency=50" -d "durationSeconds=$DUR" -d "targetQps=120" \
  > "$OUT-run.json" &
RUN_PID=$!

sleep 2
for i in 1 2 3 4 5 6 7; do
  curl -s -X POST "$BASE/admin/observe/attack/status" | tee -a "$OUT-frames.txt"
  echo
  sleep 1.4
done
wait $RUN_PID
echo "=== /run 返回后的一拍 ==="
curl -s -X POST "$BASE/admin/observe/attack/status"; echo
echo "=== /run 响应（截断） ==="
head -c 400 "$OUT-run.json"; echo
