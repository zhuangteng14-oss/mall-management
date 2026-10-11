-- ============================================================================
-- seed-verify.sql —— 灌完 `seed-demo.sql` 之后的自查（只读，不改任何行）
-- 跑法：
--   mysql -h<host> -u<user> -p --default-character-set=utf8mb4 <db> < scripts/probes/seed-verify.sql
-- 判据全在列名里（期望值写在注释里）；任何一条不符合就说明种子文件有问题。
-- ============================================================================

-- ① 基线仍在：存量行（Source=''）一条都没被删
SELECT 'baseline_rows_intact' AS `check_item`,
       (SELECT COUNT(*) FROM `login`  WHERE `Source` <> 'seed') AS `login_legacy`,
       (SELECT COUNT(*) FROM `user`   WHERE `Source` <> 'seed') AS `user_legacy`,
       (SELECT COUNT(*) FROM `goods`  WHERE `Source` <> 'seed') AS `goods_legacy`,
       (SELECT COUNT(*) FROM `order`  WHERE `Source` <> 'seed') AS `order_legacy`;
-- 期望：login_legacy 9 / user_legacy 9 / goods_legacy 6 / order_legacy 24（与改前基线一致）

-- ② 时间永不越界：种子里**最晚**一刻也必须早于「今天 00:00」
SELECT 'no_future_timestamps' AS `check_item`,
       MAX(GREATEST(COALESCE(`PaidAt`,'1970-01-02'), COALESCE(`ShippedAt`,'1970-01-02'),
                    COALESCE(`ReceivedAt`,'1970-01-02'), COALESCE(`CancelledAt`,'1970-01-02'))) AS `max_dt_col`,
       MAX(STR_TO_DATE(`CreateTime`, '%Y-%m-%d %H:%i:%s')) AS `max_create_varchar`,
       MIN(STR_TO_DATE(`CreateTime`, '%Y-%m-%d %H:%i:%s')) AS `min_create_varchar`,
       CURDATE() AS `today_midnight`,
       DATEDIFF(MAX(DATE(`CreateTime`)), MIN(DATE(`CreateTime`))) + 1 AS `span_days`
  FROM `order` WHERE `Source` = 'seed';
-- 期望：max_* 三个都 <= 今天 00:00（`today_midnight`）；span_days 落在 30..60

-- ③ `CreateTime` 全是零填充的 'YYYY-MM-DD HH:MM:SS'（否则 `DATE()` 解成 NULL、看板静默漏单）
SELECT 'create_time_zero_padded' AS `check_item`,
       COUNT(*) AS `bad_rows`
  FROM `order`
 WHERE `Source` = 'seed'
   AND (`CreateTime` NOT REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$'
        OR DATE(`CreateTime`) IS NULL);
-- 期望：bad_rows = 0

-- ④ 订单 × 明细 对得上：Σ Subtotal 与 order.Price 逐单相等，且件数一致
SELECT 'order_total_matches_items' AS `check_item`,
       COUNT(*) AS `mismatch_orders`
  FROM (
    SELECT o.`OrderId`, o.`Price`, o.`Quantity`,
           SUM(i.`Subtotal`) AS `item_sum`, SUM(i.`Quantity`) AS `item_qty`
      FROM `order` o JOIN `order_item` i ON i.`OrderId` = o.`OrderId` AND i.`Source` = 'seed'
     WHERE o.`Source` = 'seed'
     GROUP BY o.`OrderId`, o.`Price`, o.`Quantity`
  ) t
 WHERE ABS(t.`Price` - t.`item_sum`) > 0.011 OR t.`Quantity` <> t.`item_qty`;
-- 期望：0

-- ⑤ 没有孤儿明细 / 没有空明细的种子单
SELECT 'no_orphan_or_empty_items' AS `check_item`,
       (SELECT COUNT(*) FROM `order_item` i LEFT JOIN `order` o ON o.`OrderId` = i.`OrderId`
         WHERE i.`Source` = 'seed' AND o.`OrderId` IS NULL) AS `orphan_items`,
       (SELECT COUNT(*) FROM `order` o LEFT JOIN `order_item` i ON i.`OrderId` = o.`OrderId`
         WHERE o.`Source` = 'seed' AND i.`Id` IS NULL) AS `orders_without_items`;
-- 期望：0 / 0

-- ⑥ 全状态覆盖（三态互斥的五档 + 取消；口径同 `OrderMine.vue` 的 `STATE_OF`）
SELECT CASE
         WHEN `CancelledAt` IS NOT NULL                     THEN '4_cancelled'
         WHEN `PayIf`=1 AND `DeliveryIf`=0 AND `ReceiveIf`=0 THEN '1_paid_not_shipped'
         WHEN `PayIf`=1 AND `DeliveryIf`=1 AND `ReceiveIf`=0 THEN '2_shipped_not_received'
         WHEN `PayIf`=1 AND `DeliveryIf`=1 AND `ReceiveIf`=1 THEN '3_done'
         WHEN `PayIf`=0 AND `DeliveryIf`=0 AND `ReceiveIf`=0 THEN '0_unpaid'
         ELSE '9_abnormal'
       END AS `state`,
       COUNT(*) AS `n`
  FROM `order` WHERE `Source` = 'seed'
 GROUP BY `state` ORDER BY `state`;
-- 期望：六档**都有**（`0_unpaid` / `1_*` / `2_*` / `3_done` / `4_cancelled` / `9_abnormal`）

-- ⑦ 工作日高峰 / 周末低谷（MySQL `DAYOFWEEK`：1=周日 … 7=周六）
SELECT IF(DAYOFWEEK(DATE(`CreateTime`)) IN (1,7), 'weekend', 'weekday') AS `kind`,
       COUNT(*) AS `orders`,
       COUNT(DISTINCT DATE(`CreateTime`)) AS `days`,
       ROUND(COUNT(*) / COUNT(DISTINCT DATE(`CreateTime`)), 2) AS `per_day`
  FROM `order` WHERE `Source` = 'seed'
 GROUP BY `kind`;
-- 期望：weekday 的 per_day 明显大于 weekend（约 2-3 倍）

-- ⑧ 连续不跳日：窗口内每一天都要有单
SELECT 'no_day_gap' AS `check_item`,
       DATEDIFF(MAX(DATE(`CreateTime`)), MIN(DATE(`CreateTime`))) + 1 AS `window_days`,
       COUNT(DISTINCT DATE(`CreateTime`)) AS `days_with_orders`
  FROM `order` WHERE `Source` = 'seed';
-- 期望：window_days == days_with_orders（首尾相距 N 天且恰有 N 个不同日期 ⇒ 中间不跳日）

-- ⑨ 图片 / 商品信息没被截断（`Images varchar(100)`、`ProductInformation varchar(500)`）
SELECT 'no_truncation' AS `check_item`,
       MAX(CHAR_LENGTH(`Images`)) AS `max_images_len`,
       MAX(CHAR_LENGTH(`ProductInformation`)) AS `max_pinfo_len`,
       MAX(CHAR_LENGTH(`GoodImg`)) AS `max_goodimg_len`
  FROM `order` o JOIN `goods` g ON g.`Source` = 'seed'
 WHERE o.`Source` = 'seed';
-- 期望：max_images_len <= 100 / max_pinfo_len <= 500 / max_goodimg_len <= 100

-- ⑩ 每单的 `TradeNo` 只在付款时存在；`PaymentMethod` 同理
SELECT 'trade_only_when_paid' AS `check_item`,
       SUM(`PayIf` = 0 AND `TradeNo` IS NOT NULL) AS `unpaid_with_tradeno`,
       SUM(`PayIf` = 1 AND `TradeNo` IS NULL)     AS `paid_without_tradeno`
  FROM `order` WHERE `Source` = 'seed' AND `CancelledAt` IS NULL;
-- 期望：0 / 0

-- ⑪ 用户侧：多角色 6:3:1、资料齐全
SELECT `usertype`, COUNT(*) AS `n`
  FROM `user` WHERE `Source` = 'seed' GROUP BY `usertype` ORDER BY `n` DESC;
-- 期望：客户 24 / 商家 12 / 管理员 4

SELECT 'user_profile_fields' AS `check_item`,
       SUM(`age` BETWEEN 18 AND 60) AS `age_ok`,
       SUM(`address` IS NOT NULL AND CHAR_LENGTH(`address`) BETWEEN 4 AND 20) AS `addr_ok`,
       COUNT(*) AS `total`
  FROM `user` WHERE `Source` = 'seed';
-- 期望：age_ok = addr_ok = total（`address` 是 varchar(20)，超长会写不进去）

-- ⑫ 类目均衡（5 个既有类目，每个都要有商品）
SELECT `GoodType`, COUNT(*) AS `n` FROM `goods` WHERE `Source` = 'seed' GROUP BY `GoodType` ORDER BY `n` DESC;
-- 期望：5 个类目都在，每类 8-10 件

-- ⑬ 商品名 / 用户名里**不该出现**的东西（占位符 / debug 痕迹 / 假时间）
SELECT 'no_placeholder_tokens' AS `check_item`,
       (SELECT COUNT(*) FROM `goods` WHERE `Source`='seed' AND `GoodName` REGEXP '测试|test|TEST|probe|PROBE|0000|占位') AS `bad_goods`,
       (SELECT COUNT(*) FROM `login` WHERE `Source`='seed' AND `UserName` REGEXP 'test|probe|0000') AS `bad_login`,
       (SELECT COUNT(*) FROM `user`  WHERE `Source`='seed' AND `username` REGEXP 'test|probe|0000') AS `bad_user`,
       (SELECT COUNT(*) FROM `order` WHERE `Source`='seed' AND YEAR(STR_TO_DATE(`CreateTime`, '%Y-%m-%d %H:%i:%s')) IN (1970, 2099)) AS `bad_year`;
-- 期望：全 0

-- ⑭ 主键/外键式引用对得上：明细的 ProductCode 必须在种子商品里有对应行
--    （`CartDao` / `selectTopGoods` 都按 `ProductCode` 归类，对不上就统计不到）
SELECT 'item_codes_exist_in_goods' AS `check_item`,
       COUNT(*) AS `dangling`
  FROM `order_item` i
  LEFT JOIN `goods` g ON g.`MerchantId` = i.`MerchantId` AND g.`ProductCode` = i.`ProductCode`
 WHERE i.`Source` = 'seed' AND g.`Id` IS NULL;
-- 期望：0

-- ⑮ 购物车行必须能 JOIN 上商品（`CartDao.selectByCustomer` 是 LEFT JOIN）
SELECT 'cart_rows_join_goods' AS `check_item`,
       COUNT(*) AS `cart_rows`,
       SUM(g.`Id` IS NULL) AS `dangling`
  FROM `cart` c LEFT JOIN `goods` g
    ON g.`MerchantId` = c.`MerchantId` AND g.`ProductCode` = c.`ProductCode`
 WHERE c.`Source` = 'seed';
-- 期望：8 / 0

-- ⑯ 通知：收件人形状对（管理员是哨兵 `*`，其余落在真实业务号上）
SELECT `ToType`, `ToId` = '*' AS `is_admin_sentinel`, COUNT(*) AS `n`
  FROM `notification` WHERE `Source` = 'seed'
 GROUP BY `ToType`, `is_admin_sentinel` ORDER BY `ToType`;
-- 期望：admin 全为 1；customer / merchant 全为 0

-- ⑰ 审计：Action 取的是存量已用过的词汇（不新造动词）
SELECT `Action`, COUNT(*) AS `n` FROM `admin_audit` WHERE `Source` = 'seed' GROUP BY `Action` ORDER BY `n` DESC;
-- 期望：12 种动作，与存量词汇表相交（无新造词）

-- ⑱ 入驻申请：三态都覆盖，非 pending 的必须有审核人与审核时间
SELECT `Status`, COUNT(*) AS `n`,
       SUM(`Status` <> 'pending' AND (`ReviewedBy` IS NULL OR `ReviewedAt` IS NULL)) AS `missing_review`
  FROM `merchant_application` WHERE `Source` = 'seed' GROUP BY `Status`;
-- 期望：pending / approved / rejected 都在；missing_review = 0
