-- ============================================================================
-- cleanup-dirty-legacy.sql —— 清掉「过于异常」的存量数据（2026-10-11，用户逐条裁定）
--
-- 判据（用户原话）：
--   清：价格量级异常 / 探针测试残留 / 乱码占位符 / 明显假数据
--   不清：价格正常的旧单（哪怕时间跨度大），尤其 2026-05-18 与 05-21 那两笔
--   一律不动：`Source = 'seed'` 的任何行
--
-- ⚠️ 每条 DELETE 都带 `AND Source <> 'seed'`（该表有这一列时）；没有该列的表
--    （`order_state_transition` / `order_shipping_track` / `goods_sku`）按**显式主键**删，
--    键值全部来自改前的取证，不按模式匹配。
-- ⚠️ 每条 DELETE 后紧跟 `SELECT ROW_COUNT()`：跑完能逐条核对删了几行。
-- ⚠️ `admin_audit` 里的操作记录**不动**（历史审计保留，用户明示）。
-- ============================================================================

-- ---- ⓪ 改前点名（这份输出就是「删了什么」的证据）----
SELECT 'WILL_DELETE_order' AS step, `OrderId`, `Price`, `ProductInformation`, `CreateTime`
  FROM `order` WHERE `OrderId` = 'FUKW236812826218' AND `Source` <> 'seed';
SELECT 'WILL_DELETE_goods' AS step, `Id`, `GoodName`, `Price`, `ProductCode`
  FROM `goods` WHERE `Id` IN (20,22,23,24,25,31) AND `Source` <> 'seed';
SELECT 'WILL_DELETE_goods_audit' AS step, `Id`, `ProductCode`, `Status`
  FROM `goods_audit` WHERE `ProductCode` = '大大大大大大' AND `Source` <> 'seed';
SELECT 'WILL_DELETE_login' AS step, `UserName`, `Role`, `Id`
  FROM `login` WHERE `UserName` IN ('i2probe','性暗示') AND `Source` <> 'seed';
SELECT 'WILL_DELETE_user' AS step, `username`, `usertype`, `customerid`, `merchantid`
  FROM `user` WHERE `username` IN ('i2probe','性暗示') AND `Source` <> 'seed';
-- 第二轮裁定（用户选「全删」）：2026-10-10 那 17 笔存量测试单
SELECT 'WILL_DELETE_order_1010' AS step, `OrderId`, `Price`, `ProductInformation`
  FROM `order` WHERE `Source` <> 'seed' AND LEFT(`CreateTime`,10) = '2026-10-10'
 ORDER BY `Price` DESC;

-- ============================================================================
-- ① 价格量级异常的那一笔（Price = 10000000，商品名「黑客」）
--    连带：order_item / order_shipping_track / order_state_transition（同 OrderId）
-- ============================================================================
DELETE FROM `order_item`            WHERE `OrderId` = 'FUKW236812826218';
SELECT 'del.order_item' AS step, ROW_COUNT() AS deleted;
DELETE FROM `order_shipping_track`  WHERE `OrderId` = 'FUKW236812826218';
SELECT 'del.order_shipping_track' AS step, ROW_COUNT() AS deleted;
DELETE FROM `order_state_transition` WHERE `OrderId` = 'FUKW236812826218';
SELECT 'del.order_state_transition' AS step, ROW_COUNT() AS deleted;
DELETE FROM `order` WHERE `OrderId` = 'FUKW236812826218' AND `Source` <> 'seed';
SELECT 'del.order' AS step, ROW_COUNT() AS deleted;

-- ============================================================================
-- ② 乱码 / 占位符 / 价格量级异常的商品（Id 20/22「222」、23「叠法双方」、
--    24「黑客」价 9999999、25 名与价都是「1」、31「啊实打实」）
--    连带：goods_audit（同 ProductCode）/ goods_sku（同 ProductCode）
-- ============================================================================
DELETE FROM `goods_sku`   WHERE `SkuCode` IN ('IJ-555','IF-55','dffffff','hsdk','1','大大大大大大');
SELECT 'del.goods_sku' AS step, ROW_COUNT() AS deleted;
DELETE FROM `goods_audit` WHERE `ProductCode` IN ('IJ-555','IF-55','dffffff','hsdk','1','大大大大大大') AND `Source` <> 'seed';
SELECT 'del.goods_audit' AS step, ROW_COUNT() AS deleted;
DELETE FROM `goods`       WHERE `Id` IN (20,22,23,24,25,31) AND `Source` <> 'seed';
SELECT 'del.goods' AS step, ROW_COUNT() AS deleted;

-- ============================================================================
-- ③ 探针 / 测试残留账号（`i2probe`；`性暗示` 亦系探针所建的违规名账号）
--    连带：该账号的 notification / cart / merchant_application
--    ⚠️ admin_audit 里的 actor 记录**不动**（用户明示：历史审计保留）
--    ⚠️ customerimage / merchantimage 各有 1 行 —— **不在用户给的清单里**，本文件不动，
--       留作待裁（见交付说明）
-- ============================================================================
DELETE FROM `notification`         WHERE (`ToId` = 'i2probe' AND `ToType` = 'customer')
                                      OR (`ToId` = 'N7w3UOB5VI' AND `ToType` = 'merchant');
SELECT 'del.notification' AS step, ROW_COUNT() AS deleted;
DELETE FROM `cart`                 WHERE `CustomerId` IN ('i2probe','N7w3UOB5VI') OR `MerchantId` IN ('i2probe','N7w3UOB5VI');
SELECT 'del.cart' AS step, ROW_COUNT() AS deleted;
DELETE FROM `merchant_application` WHERE `Username` IN ('i2probe','性暗示');
SELECT 'del.merchant_application' AS step, ROW_COUNT() AS deleted;
DELETE FROM `user`  WHERE `username` IN ('i2probe','性暗示') AND `Source` <> 'seed';
SELECT 'del.user' AS step, ROW_COUNT() AS deleted;
DELETE FROM `login` WHERE `UserName` IN ('i2probe','性暗示') AND `Source` <> 'seed';
SELECT 'del.login' AS step, ROW_COUNT() AS deleted;

-- ============================================================================
-- ④ 2026-10-10 那 17 笔存量测试单（第二轮裁定：全删）
--    判据：`ProductInformation` 逐字就是已点名的乱码/占位符（`222` / `叠法双方` /
--          `1:1` / `取消核验:1`），且它们指向的商品已在 ② 删除 ⇒ 现属「订单指向
--          不存在商品」的悬挂单，计入「探针/测试残留」。
--    连带：order_item / order_shipping_track / order_state_transition（同 OrderId）
--    备份：`_work/_backup-test-orders-1010.sql`（17 单 + 33 轨迹 + 55 流转，可整段回灌）
--    ⚠️ order_shipping_track / order_state_transition **没有 `Source` 列**，只能按 OrderId 删。
-- ============================================================================
DELETE FROM `order_item`             WHERE `OrderId` IN ('XWNS589693227506','CHKM360570571420','VGJB50421020','NHOB029438564024','PIFK37592245','KGKT651544897092','BUEX77899935','EGPC49109165','RNLX55941639','KTLQ27366931','FILM16741477','ZYUW96404840','CU320435A','CU320435C','RJQV62271134','SPZV48561853','OQNB15363921');
SELECT 'del2.order_item' AS step, ROW_COUNT() AS deleted;
DELETE FROM `order_shipping_track`   WHERE `OrderId` IN ('XWNS589693227506','CHKM360570571420','VGJB50421020','NHOB029438564024','PIFK37592245','KGKT651544897092','BUEX77899935','EGPC49109165','RNLX55941639','KTLQ27366931','FILM16741477','ZYUW96404840','CU320435A','CU320435C','RJQV62271134','SPZV48561853','OQNB15363921');
SELECT 'del2.order_shipping_track' AS step, ROW_COUNT() AS deleted;
DELETE FROM `order_state_transition` WHERE `OrderId` IN ('XWNS589693227506','CHKM360570571420','VGJB50421020','NHOB029438564024','PIFK37592245','KGKT651544897092','BUEX77899935','EGPC49109165','RNLX55941639','KTLQ27366931','FILM16741477','ZYUW96404840','CU320435A','CU320435C','RJQV62271134','SPZV48561853','OQNB15363921');
SELECT 'del2.order_state_transition' AS step, ROW_COUNT() AS deleted;
DELETE FROM `order`                  WHERE `OrderId` IN ('XWNS589693227506','CHKM360570571420','VGJB50421020','NHOB029438564024','PIFK37592245','KGKT651544897092','BUEX77899935','EGPC49109165','RNLX55941639','KTLQ27366931','FILM16741477','ZYUW96404840','CU320435A','CU320435C','RJQV62271134','SPZV48561853','OQNB15363921') AND `Source` <> 'seed';
SELECT 'del2.order' AS step, ROW_COUNT() AS deleted;

-- ============================================================================
-- ⑤ 事后核对
-- ============================================================================
-- 种子一行没动（期望：login 40 / user 40 / goods 44 / order 178 / order_item 199）
SELECT 'AFTER_seed_rows' AS step, 'login' AS t, COUNT(*) AS n FROM `login` WHERE `Source` = 'seed'
UNION ALL SELECT 'AFTER_seed_rows', 'user',        COUNT(*) FROM `user`        WHERE `Source` = 'seed'
UNION ALL SELECT 'AFTER_seed_rows', 'goods',       COUNT(*) FROM `goods`       WHERE `Source` = 'seed'
UNION ALL SELECT 'AFTER_seed_rows', 'order',       COUNT(*) FROM `order`       WHERE `Source` = 'seed'
UNION ALL SELECT 'AFTER_seed_rows', 'order_item',  COUNT(*) FROM `order_item`  WHERE `Source` = 'seed'
UNION ALL SELECT 'AFTER_seed_rows', 'notification',COUNT(*) FROM `notification`WHERE `Source` = 'seed';

-- 存量还剩什么（期望：order 6 笔 —— 只剩 2026-05-18/05-21 那几笔；goods 1 件 = Id 120；login 7 / user 7）
SELECT 'AFTER_legacy_orders' AS step, COUNT(*) AS n, MIN(`Price`) AS min_price, MAX(`Price`) AS max_price
  FROM `order` WHERE `Source` <> 'seed';

-- 10 月不应再有存量单（期望：0）
SELECT 'AFTER_legacy_orders_oct' AS step, COUNT(*) AS n
  FROM `order` WHERE `Source` <> 'seed' AND `CreateTime` >= '2026-10-01';
SELECT 'AFTER_legacy_goods' AS step, `Id`, `GoodName`, `Price`, `ProductCode`
  FROM `goods` WHERE `Source` <> 'seed';

-- 「乱码 / 探针痕迹」残留扫描（期望全 0）
SELECT 'AFTER_leftover_scan' AS step,
       (SELECT COUNT(*) FROM `login` WHERE `Source` <> 'seed' AND (`UserName` REGEXP 'i2probe|probe|PROBE|test|TEST|0000')) AS bad_login,
       (SELECT COUNT(*) FROM `goods` WHERE `Source` <> 'seed' AND (`GoodName` REGEXP '^[0-9]+$|啊实打实|叠法双方|黑客|测试|test|probe')) AS bad_goods,
       (SELECT COUNT(*) FROM `goods` WHERE `Source` <> 'seed' AND `Price` >= 1000000) AS bad_price,
       (SELECT COUNT(*) FROM `order` WHERE `Source` <> 'seed' AND `Price` >= 100000) AS bad_order_price;
