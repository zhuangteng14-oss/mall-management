-- ============================================================
-- Migration: C3 块2 用户块  Date: 2026-10-09
-- 依据：docs/agent/c3-modules.md §1「用户与商家治理」
--       · docs/agent/context-packs/c3-block-2-user.md
-- 内容：① 新表 `merchant_application`（商家入驻申请，一次申请一行、可多次）
--       ② `user` 加 8 列（封禁 3 + 降级 5）
--       ③ `goods` 加 2 列（ShelfState 上架标志 + DelistedAt）
-- 回滚（规则 v2：回滚 = 逻辑废弃，执行前须人工确认）：
--       DROP TABLE `merchant_application`;
--       ALTER TABLE `user` DROP COLUMN `banned`,`banReason`,`bannedAt`,
--         `DowngradeState`,`ScheduledDowngradeAt`,`DowngradeReason`,`FrozenAt`,`FreezeUntil`;
--       ALTER TABLE `goods` DROP COLUMN `ShelfState`,`DelistedAt`;
-- 幂等：新表用 `CREATE TABLE IF NOT EXISTS`；⚠️ 加列**不幂等** —— MySQL **不支持**
--       `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（那是 MariaDB 扩展；见
--       https://dev.mysql.com/doc/refman/8.4/en/alter-table.html ，无此子句）。
--       CI 是**整份重导**（先 DROP 后 CREATE）⇒ 加列语句每轮只执行一次，安全；
--       VM 库手动执行一次即可，**重复执行会报 Duplicate column（预期，非故障）**。
--       真要重跑幂等：先查 INFORMATION_SCHEMA.COLUMNS 判存在，再 PREPARE/EXECUTE。
-- ⚠️ 本块的改动**不写进文件上半部的基线 CREATE TABLE**（同 notification 的处置）：
--    基线区保持历史原样，新列/新表一律以「末尾 Migration 块」表达 ⇒ 全量重导后
--    最终结构 = 基线 + 本块，且**不会**出现「基线已含新列 + 末尾又想 ADD」的重复列。
-- ============================================================
CREATE TABLE IF NOT EXISTS `merchant_application` (
  `Id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `Username` varchar(20) NOT NULL COMMENT '申请人账号（user.username）。⚠️ 软引用，不设 FK（同 notification.TargetId 口径）',
  `ShopName` varchar(64) NOT NULL COMMENT '店铺名',
  `LicenseNo` varchar(64) NOT NULL COMMENT '营业执照号',
  `ContactName` varchar(20) DEFAULT NULL COMMENT '联系人姓名',
  `ContactPhone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `ShopAddress` varchar(128) DEFAULT NULL COMMENT '店铺地址',
  `LicenseImage` varchar(500) DEFAULT NULL COMMENT '证件图 URL；无图 NULL',
  `Status` varchar(16) NOT NULL DEFAULT 'pending' COMMENT '审核状态：pending / approved / rejected（小写，与 CAS 的 WHERE Status=? 字面一致；字符串码，不建 DB ENUM）',
  `RejectReason` varchar(255) DEFAULT NULL COMMENT '驳回理由（Status=rejected 时有值）',
  `BatchId` varchar(36) NOT NULL COMMENT '提交时生成的 UUID；给通知与审计一个跨重导稳定的 TargetId（同 I2 的 BatchId 理由）',
  `SubmittedAt` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时刻（DB 存 UTC）',
  `ReviewedBy` varchar(20) DEFAULT NULL COMMENT '审核的管理员账号名（管理员无独立业务号）',
  `ReviewedAt` datetime(3) DEFAULT NULL COMMENT '审核时刻；未审为 NULL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时刻（审核会 UPDATE，故本表非 append-only）',
  PRIMARY KEY (`Id`),
  KEY `idx_merchant_application_status` (`Status`,`SubmittedAt`),
  KEY `idx_merchant_application_user` (`Username`,`SubmittedAt`),
  KEY `idx_merchant_application_batch` (`BatchId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家入驻申请（C3 块2 新增；一次申请一行，同一账号可多次申请）';

-- ---- user：封禁 3 列 + 降级 5 列（规则 7：默认值或 NULL + COMMENT + 加在表末尾）----
-- ⚠️ 这 8 列都会经 `UserDao.selectUserByUsername`（全仓唯一 SELECT *）出现在
--    /user/me 与 getUserInfo 返回里。**已裁决：不剥**（用户该知道自己被封/被降级；
--    DowngradeReason 本就是给商家看的原因，降级通知里也带着它）。
ALTER TABLE `user`
  ADD COLUMN `banned` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否封禁：1 封 / 0 正常。登录闸与已登录会话校验都查这列',
  ADD COLUMN `banReason` varchar(255) DEFAULT NULL COMMENT '封禁理由（banned=1 时有值）',
  ADD COLUMN `bannedAt` datetime(3) DEFAULT NULL COMMENT '封禁时刻；未封为 NULL',
  ADD COLUMN `DowngradeState` varchar(16) NOT NULL DEFAULT 'none' COMMENT '商家降级状态：none / scheduled（通知期）/ frozen（已降级冻结）',
  ADD COLUMN `ScheduledDowngradeAt` datetime(3) DEFAULT NULL COMMENT '计划降级生效时刻（= 发起时刻 + 1 天通知期）',
  ADD COLUMN `DowngradeReason` varchar(255) DEFAULT NULL COMMENT '降级原因 ⚠️ 这是**给商家看的**（会出现在降级通知与 /user/me），填写时当「会公开」写',
  ADD COLUMN `FrozenAt` datetime(3) DEFAULT NULL COMMENT '实际冻结（降级生效）时刻',
  ADD COLUMN `FreezeUntil` datetime(3) DEFAULT NULL COMMENT '冻结保留截止（= FrozenAt + 30 天）；此后重新申请通过需人工确认才恢复';

-- ---- goods：上架标志（降级冻结用；既有「下架」端点 /goods/goodsDe 是真 DELETE，本列让下架可逆）----
ALTER TABLE `goods`
  ADD COLUMN `ShelfState` tinyint(1) NOT NULL DEFAULT 1 COMMENT '上架状态：1 在架 / 0 已下架（降级冻结只置 0，不删行）',
  ADD COLUMN `DelistedAt` datetime(3) DEFAULT NULL COMMENT '下架时刻；在架为 NULL';
