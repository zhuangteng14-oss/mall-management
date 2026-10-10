#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# c3b3-snapshot-verify.sh —— C3 块 3〈就地改造〉的**手验**：证明「模板是快照、不是引用」。
#
# 判据（`_work/c3-block3-landing-plan.md §六.1` / `slot-registry.md §19-9-6`）：
#   ① 建模板 goods.detail Body='AAA'  → ② 商家提审（Detail 手改成 'AAA-SHOP-EDITED'）
#   → ③ 管理员通过 → ④ **把模板改成 'BBB'** → ⑤ 重查商品 Detail **仍是 'AAA-SHOP-EDITED'**
#   + 结构：goods 无 %TemplateId% 列；SHOW TABLES LIKE '%_template' 只有 template
#
# ⚠️ 前置：后端已按**新代码 + a3 profile** 起（`app.auth.enabled=false`，闸关）；
#    `application-a3.properties` 的 jdbc 指向 **VM 192.168.229.129**（不是本机库）。
# ⚠️ 本脚本**会写 VM 库**（1 条 template + 1 条 goods_audit + 1 条 goods），
#    跑完由 `--cleanup` 段**删回**（只删本脚本自己造的 ProductCode=SNAP-1 那一行）。
#    清理是**读回双重确认**的：删完再 SELECT COUNT 必须为 0。
#
# 用法：bash scripts/probes/c3b3-snapshot-verify.sh
# ═══════════════════════════════════════════════════════════════════════════════
set -u
BASE=http://127.0.0.1:8088
MY="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
VM=(-h192.168.229.129 -utest -ptest123 -D mall_management_sql)
PNG_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/_work/snapshot-probe.png"
# ⚠️ curl 是 Windows 版：`/c/...` 与**中文路径**它都认不了 ⇒ 先转成盘符形式。
#    （Windows/GBK 下 curl 传中文还会变乱码 —— 本脚本的 HTTP 参数刻意全用 ASCII。）
cp "$PNG_SRC" /tmp/snap-probe.png
PNG="$(cygpath -w /tmp/snap-probe.png)"

MID=5N1k5K2guh          # VM 既有商家（ASCII，绕开 Windows/GBK 中文变乱码的坑）
CODE=SNAP-1
NAME=SnapProbe
TNAME=snap-probe-AAA
OLD=AAA-SHOP-EDITED     # 提审时**手改**后的文本（≠ 模板原文）
NEW=BBB                 # 后来把模板改成这个

q() { "$MY" "${VM[@]}" -N -e "$1" 2>/dev/null; }
hr() { echo; echo "── $* ─────────────────────────────────────────"; }

hr "0. 前置：后端版本 + profile + 库"
echo -n "old /catalog/templates 应 404 -> "; curl -s -m 10 -o /dev/null -w "%{http_code}\n" -X POST "$BASE/catalog/templates"
echo -n "/template/list 应 200 -> "; curl -s -m 10 -X POST "$BASE/template/list" -d "Type=goods.detail"; echo
echo -n "VM goods 行数（基线）-> "; q "SELECT COUNT(*) FROM goods;"

hr "1. 管理端建模板 goods.detail（Body=AAA）"
curl -s -m 10 -X POST "$BASE/admin/template/create" \
  --data-urlencode "Type=goods.detail" --data-urlencode "Name=$TNAME" \
  --data-urlencode "Body=AAA" --data-urlencode "Sort=0"; echo
echo "模板行:"; q "SELECT Id,Type,Name,Body,Enabled,Sort FROM template WHERE Type='goods.detail';"
TID=$(q "SELECT Id FROM template WHERE Type='goods.detail' AND Name='$TNAME';")
echo "模板 Id = $TID"

hr "2. 商家提审（Detail 手改成 $OLD；Spec/AfterSale 各给一段）"
curl -s -m 20 -X POST "$BASE/goods/goodsAdd" \
  -F "MerchantId=$MID" -F "GoodName=$NAME" -F "Price=9.9" \
  -F "file=@$PNG;type=image/png" -F "GoodType=TestType" \
  -F "ProductCode=$CODE" -F "GoodTypeNew=0" \
  -F "Detail=$OLD" -F "Spec=k1=v1" -F "AfterSale=aftersale-probe"; echo
echo "① goods 表应**零新行**（提审不写 goods）:"; q "SELECT COUNT(*) FROM goods WHERE ProductCode='$CODE';"
echo "② goods_audit 行（Detail 应 = $OLD）:"
q "SELECT Id,BatchId,Status,Detail,Spec,AfterSale FROM goods_audit WHERE ProductCode='$CODE';"
AID=$(q "SELECT Id FROM goods_audit WHERE ProductCode='$CODE' ORDER BY Id DESC LIMIT 1;")
echo "goods_audit.Id = $AID"

hr "3. 管理员通过"
curl -s -m 15 -X POST "$BASE/admin/catalog/application/audit" \
  --data-urlencode "id=$AID" --data-urlencode "approve=true"; echo
echo "③ goods 表应新增 1 行，Detail 应 = $OLD（不是模板的 AAA）:"
q "SELECT Id,MerchantId,ProductCode,Detail,Spec,AfterSale FROM goods WHERE ProductCode='$CODE';"
echo "④ goods_audit 应 Status=approved 且 GoodsId 回填:"
q "SELECT Id,Status,GoodsId,Detail FROM goods_audit WHERE Id=$AID;"

hr "4. 改模板 Body ⇒ $NEW（快照语义的关键一步）"
curl -s -m 10 -X POST "$BASE/admin/template/update" \
  --data-urlencode "id=$TID" --data-urlencode "Body=$NEW"; echo
echo "模板现在是:"; q "SELECT Id,Type,Name,Body FROM template WHERE Id=$TID;"

hr "5. 重查商品 —— Detail **必须仍是 $OLD**（不是 $NEW）⇒ 快照而非引用"
q "SELECT Id,ProductCode,Detail FROM goods WHERE ProductCode='$CODE';"

hr "6. 结构断言"
echo "goods 里 %TemplateId% 列（应为 0 行）:"
q "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='mall_management_sql' AND TABLE_NAME='goods' AND COLUMN_NAME LIKE '%TemplateId%';"
echo "名字含 template 的表（应只有 template；⚠️ 别写 LIKE 百分号+下划线+template —— 下划线是通配符）:"
q "SELECT table_name FROM information_schema.TABLES WHERE TABLE_SCHEMA='mall_management_sql' AND table_name LIKE '%template%';"
echo "goods_audit 快照三列（应 3 行）:"
q "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='mall_management_sql' AND TABLE_NAME='goods_audit' AND COLUMN_NAME IN ('Detail','Spec','AfterSale');"

hr "7. 清理（删回本脚本造的行；删完读回确认 0）"
q "DELETE FROM goods WHERE ProductCode='$CODE';"
q "DELETE FROM goods_audit WHERE ProductCode='$CODE';"
q "DELETE FROM template WHERE Id=$TID;"
echo -n "goods 残留 -> ";     q "SELECT COUNT(*) FROM goods WHERE ProductCode='$CODE';"
echo -n "goods_audit 残留 -> "; q "SELECT COUNT(*) FROM goods_audit WHERE ProductCode='$CODE';"
echo -n "template 残留 -> ";  q "SELECT COUNT(*) FROM template WHERE Id=$TID;"
echo -n "VM goods 总行数（应回到基线 5）-> "; q "SELECT COUNT(*) FROM goods;"
echo; echo "完成。"
