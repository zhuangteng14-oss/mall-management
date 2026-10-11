#!/usr/bin/env bash
# 可视化段补丁 · 探针：结构化规格模板的 `Kind` 是否穿过 admin create/update（2026-10-11）
#
# 背景：`template` 表新加 `Kind`（`text` / `structured`），管理端「模板库」要能选形态。
# 探针要证的只有一件事：**Kind 从 HTTP 参数一路落到库里，且 update 能改它** ——
# 中文名会撞 Windows/GBK 的 curl 乱码（见 memory），故本探针**全程用 ASCII 名字**，
# 只验管道，与「结构化」语义无关。
#
# 收尾：探针行 Enabled=0（逻辑废弃）并**用 mysql 直接读回**确认（不用 list —— 列表可能滤掉停用行，
# 那样「读不到」就分不清是收尾生效还是查询口径），不留脏数据给用户手验。
set -u
BASE="http://localhost:8088"
NAME="__PROBE_TPL_KIND"
MYSQL="C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
Q() { "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -N \
      -e "SELECT Id, Kind, Enabled FROM mall_management_sql.template WHERE Name='$NAME';" 2>/dev/null; }
OUT="$(dirname "$0")/../../_work/goods-fix-probe3.txt"

{
  echo "=== 0 前置：同名行应为 0（脏数据检查） ==="; Q; echo "(以上为空即干净)"

  echo "=== 1 create Kind=structured ==="
  curl -s -X POST "$BASE/admin/template/create" \
    --data-urlencode "Type=goods.spec" --data-urlencode "Kind=structured" \
    --data-urlencode "Name=$NAME" --data-urlencode "Body=AxisA=1,2" \
    -w "\n[HTTP %{http_code}]\n"
  echo "--- 直查库（期望 Kind=structured, Enabled=1）---"; Q

  echo "=== 2 /template/list 读回（期望出现该行且 Kind 字段在） ==="
  curl -s -X POST "$BASE/template/list" -d "Type=goods.spec" | tr ',' '\n' | grep -n "Kind\|$NAME" | head -20

  ID=$("$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -N \
       -e "SELECT Id FROM mall_management_sql.template WHERE Name='$NAME';" 2>/dev/null | tr -d '\r\n')
  echo "探针行 Id=$ID"

  echo "=== 3 update Kind=text（只改形态） ==="
  curl -s -X POST "$BASE/admin/template/update" \
    --data-urlencode "id=$ID" --data-urlencode "Kind=text" -w "\n[HTTP %{http_code}]\n"
  echo "--- 直查库（期望 Kind=text）---"; Q

  echo "=== 4 update Kind=BOGUS（期望回落 text，不是原样入库） ==="
  curl -s -X POST "$BASE/admin/template/update" \
    --data-urlencode "id=$ID" --data-urlencode "Kind=BOGUS" -w "\n[HTTP %{http_code}]\n"
  echo "--- 直查库（期望 Kind=text）---"; Q

  echo "=== 5 收尾：Enabled=0 ==="
  curl -s -X POST "$BASE/admin/template/update" \
    --data-urlencode "id=$ID" --data-urlencode "Enabled=0" -w "\n[HTTP %{http_code}]\n"
  echo "--- 直查库（期望 Enabled=0）---"; Q

  echo "=== 6 收尾核对：goods.spec 剩下三行种子应逐字未动 ==="
  "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -N \
    -e "SELECT Id, Kind, Name, Enabled FROM mall_management_sql.template WHERE Type='goods.spec' ORDER BY Id;" 2>/dev/null
} > "$OUT" 2>&1

cat "$OUT"
