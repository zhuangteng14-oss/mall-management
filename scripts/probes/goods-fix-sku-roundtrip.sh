#!/usr/bin/env bash
# 可视化段补丁 · 探针：SKU 矩阵 + 4 个承诺位**穿过提审制**（2026-10-11）
#
# 为什么必须跑这一条：本仓上架走**提审制** —— `goodsAdd` 只写 `goods_audit(pending)`，
# 管理员通过时才由 `writeGoodsFromAudit` 用快照**重建** `goods` 行。⇒ 新字段漏穿任何一段
# （Controller → Service → AuditDao → writeGoodsFromAudit → Dao.insertGoods / insertSkus）
# 的症状都是「**审核通过后该字段为空**」，编译期看不出来。
#
# 承诺位故意取 **0/1/1/0**（不是全 1、也不是全 0）：全 1 分不清「真值」与「缺省兜底 true」，
# 全 0 分不清「真值」与「列没写进去」。混合值才能同时证伪两种误判。
#
# 全过程 ASCII 标识（中文会撞 Windows/GBK 的 curl 乱码，见 memory）。
# 收尾物理清掉本探针自造的行（含 `goods` / `goods_audit` / `goods_sku` 三张表）并读回 0。
set -u
BASE="http://localhost:8088"
MID="5N1k5K2guh"          # VM 库里既有的商家（login.id）
GN="__PROBE_GOODS_A"
PC="__PC_A"
MYSQL="C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
Q() { "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -N \
      -e "SELECT $1 FROM mall_management_sql.$2 WHERE $3;" 2>/dev/null; }
OUT="$(dirname "$0")/../../_work/goods-fix-probe4.txt"
TMP="$(dirname "$0")/../../_work/probe-skupic.png"
printf '\x89PNG\r\n\x1a\n__probe__' > "$TMP"

SKU='[{"spec":"PROBE-A","price":1.5,"stock":7,"code":"__PC_A_A"},{"spec":"PROBE-B","price":2.5,"stock":9,"code":"__PC_A_B"}]'

{
  echo "=== 0 前置：上次残留应为 0 ==="
  Q "COUNT(*)" goods        "GoodName='$GN'"
  Q "COUNT(*)" goods_audit  "GoodName='$GN'"
  Q "COUNT(*)" goods_sku    "GoodName='$GN'"

  echo "=== 1 goodsAdd（SkuSpec 2 行 / 承诺 0-1-1-0） ==="
  curl -s -X POST "$BASE/goods/goodsAdd" \
    -F "MerchantId=$MID" -F "GoodName=$GN" -F "Price=1" \
    -F "file=@$TMP;type=image/png" \
    -F "GoodType=服装" -F "GoodTypeNew=0" -F "ProductCode=$PC" \
    --form-string "SkuSpec=$SKU" \
    -F "PromiseGenuine=false" -F "PromiseReturn7=true" \
    -F "PromiseFastRefund=true" -F "PromiseFreightIns=false" \
    -w "\n[HTTP %{http_code}]\n"

  echo "--- 1b goods_audit 快照（期望 SkuSpec 非空；承诺 0,1,1,0） ---"
  "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -e \
    "SELECT Id, Status, LEFT(SkuSpec,40) AS SkuSpec, PromiseGenuine, PromiseReturn7, PromiseFastRefund, PromiseFreightIns
       FROM mall_management_sql.goods_audit WHERE GoodName='$GN'\G" 2>/dev/null

  echo "--- 1c 此刻 goods 表应为 0 行（提审制：通过前不上架） ---"
  Q "COUNT(*)" goods "GoodName='$GN'"

  AID=$(Q "MAX(Id)" goods_audit "GoodName='$GN'" | tr -d '\r\n')
  echo "=== 2 管理员通过（auditId=$AID） ==="
  # ⚠️ 上一步没建出提审单时 `MAX(Id)` 回的是字符串 "NULL" ⇒ 别让它混进 `id=NULL` 再吃一个
  #    看不懂的 400（那会掩盖真正的失败点）。这里直接停。
  case "$AID" in ''|*NULL*) echo "!! 提审单没建出来（上一步的 goodsAdd 已失败）⇒ 中止，先看 1b/日志"; exit 1 ;; esac
  curl -s -X POST "$BASE/admin/catalog/application/audit" \
    -d "id=$AID" -d "approve=true" -w "\n[HTTP %{http_code}]\n"

  echo "--- 2b goods 行（期望承诺 0,1,1,0 —— 与审前快照逐位相同） ---"
  "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -e \
    "SELECT Id, GoodName, Price, PromiseGenuine, PromiseReturn7, PromiseFastRefund, PromiseFreightIns
       FROM mall_management_sql.goods WHERE GoodName='$GN'\G" 2>/dev/null

  echo "--- 2c goods_sku（期望 2 行，AxisName/Price/Stock/SkuCode/Sort 逐字对得上） ---"
  "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -N -e \
    "SELECT Id, GoodsId, AxisName, Price, Stock, SkuCode, Sort FROM mall_management_sql.goods_sku
       WHERE GoodName='$GN' ORDER BY Sort;" 2>/dev/null

  echo "=== 3 详情页读口 /goods/skuList（期望 total:2，码为 __PC_A_A/__PC_A_B） ==="
  curl -s -X POST "$BASE/goods/skuList" -d "MerchantId=$MID&GoodName=$GN" -w "\n[HTTP %{http_code}]\n"

  echo "=== 4 收尾：物理清掉探针行 ==="
  "$MYSQL" -h 192.168.229.129 -utest -ptest123 --default-character-set=utf8mb4 -e \
    "DELETE FROM mall_management_sql.goods_sku   WHERE GoodName='$GN';
     DELETE FROM mall_management_sql.goods       WHERE GoodName='$GN';
     DELETE FROM mall_management_sql.goods_audit WHERE GoodName='$GN';" 2>/dev/null

  echo "--- 4b 读回（三张表都应为 0） ---"
  echo -n "goods=";       Q "COUNT(*)" goods       "GoodName='$GN'"
  echo -n "goods_audit="; Q "COUNT(*)" goods_audit "GoodName='$GN'"
  echo -n "goods_sku=";   Q "COUNT(*)" goods_sku   "GoodName='$GN'"

  echo "=== 5 收尾核对：全库 goods_sku 行数（应为 0 —— 探针没留垃圾） ==="
  Q "COUNT(*)" goods_sku "1=1"
  rm -f "$TMP"
} > "$OUT" 2>&1

cat "$OUT"
