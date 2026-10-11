#!/usr/bin/env bash
# 可视化段补丁 · 探针：`SkuSpec` 是**坏 JSON** 时，审核通过不能炸（2026-10-11）
#
# 要证的判据（两个，缺一不可）：
#   ① 审核**照样通过**（`success:true` + `goods` 行真的落下来）；
#   ② `goods_sku` **0 行**（脏快照被跳过，而不是写进半条矩阵）。
# 依据：审核通过是**不可回退**路径（`goods` 行已落、通知已发、CAS 已翻 approved），
# 此时为一个可选的附加快照抛异常 = 把「矩阵填错」升级成「商品上不了架」。
# ⇒ `writeSkusFromAudit` 对空白 / 非数组 / 解析失败一律**跳过不抛**。本探针就是这条的运行时证据。
set -u
BASE="http://localhost:8088"
MID="5N1k5K2guh"
GN="__PROBE_GOODS_B"
MYSQL="C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
Q() { "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -N \
      -e "SELECT $1 FROM mall_management_sql.$2 WHERE $3;" 2>/dev/null; }
OUT="$(dirname "$0")/../../_work/goods-fix-probe5.txt"
TMP="$(dirname "$0")/../../_work/probe-skupic-b.png"
printf '\x89PNG\r\n\x1a\n__probe__' > "$TMP"

{
  echo "=== 0 前置清理（应为 0） ==="
  echo -n "goods="; Q "COUNT(*)" goods "GoodName='$GN'"

  echo "=== 1 goodsAdd，SkuSpec = 半截 JSON（故意坏） ==="
  curl -s -X POST "$BASE/goods/goodsAdd" \
    -F "MerchantId=$MID" -F "GoodName=$GN" -F "Price=2" \
    -F "file=@$TMP;type=image/png" \
    -F "GoodType=服装" -F "GoodTypeNew=0" -F "ProductCode=__PC_B" \
    --form-string 'SkuSpec=[{"spec":"PROBE-X","price":1' \
    -w "\n[HTTP %{http_code}]\n"

  AID=$(Q "MAX(Id)" goods_audit "GoodName='$GN'" | tr -d '\r\n')
  case "$AID" in ''|*NULL*) echo "!! 提审单没建出来 ⇒ 中止"; exit 1 ;; esac
  echo "=== 2 审核通过（auditId=$AID）—— 期望 success:true ==="
  curl -s -X POST "$BASE/admin/catalog/application/audit" \
    -d "id=$AID" -d "approve=true" -w "\n[HTTP %{http_code}]\n"

  echo "--- 2b goods 行应存在（商品照常上架） ---"
  "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -e \
    "SELECT Id, GoodName, Price FROM mall_management_sql.goods WHERE GoodName='$GN'\G" 2>/dev/null

  echo "--- 2c goods_sku **必须 0 行** ---"
  echo -n "goods_sku="; Q "COUNT(*)" goods_sku "GoodName='$GN'"

  echo "=== 3 收尾：清探针行 ==="
  "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -e \
    "DELETE FROM mall_management_sql.goods_sku   WHERE GoodName='$GN';
     DELETE FROM mall_management_sql.goods       WHERE GoodName='$GN';
     DELETE FROM mall_management_sql.goods_audit WHERE GoodName='$GN';" 2>/dev/null
  echo -n "goods=";       Q "COUNT(*)" goods       "GoodName='$GN'"
  echo -n "goods_audit="; Q "COUNT(*)" goods_audit "GoodName='$GN'"
  echo -n "goods_sku=";   Q "COUNT(*)" goods_sku   "GoodName='$GN'"
  rm -f "$TMP"
} > "$OUT" 2>&1

cat "$OUT"
