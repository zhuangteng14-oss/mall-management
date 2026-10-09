-- ============================================================
-- Migration: C3 块1-I2 系统通知  Date: 2026-10-09
-- 依据：docs/agent/c3-modules.md §6「系统通知」+ §9.2（被评论 / 被回复 / 被 @）
--       · docs/agent/context-packs/c3-block-1-infra.md「要新建」
-- 用途：平台 → 个人的单向投递（公告 / 群发 / 定向）。**每收件人一行**
--       （已读时刻 ReadAt 落在行上 ⇒ 广播 / 公告必须扇出，不能一行多人共享读态）。
--       与 message 分表：message 是「两人会话」（from / to 都有意义），通知无 from。
-- 回滚：DROP TABLE `notification`;（规则 v2：回滚 = 逻辑废弃；该语句执行前须人工确认）
-- 幂等：「导入 = 先 DROP 后 CREATE」；本块用 IF NOT EXISTS 且**块内不 DROP**
--       ⇒ 全量重导**不清**通知历史（刻意，同 admin_audit：通知不是会被重新播种的种子数据，
--         清空即永久消失，没有任何脚本会补回来）。
-- ⚠️ TargetId 是**软引用**：**不设 FK**。目标对象可能已被删除 / 全量重导后失效；
--    有 FK 会让插入报错或触发级联删；无 FK 由前端渲染时容错
--    （对象不存在 → 显示「（相关对象已删除）」，不崩、不空白）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `notification` (
  `Id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `BatchId` varchar(36) NOT NULL COMMENT '本次投递批次（UUID）；一次群发/公告的扇出各行共享同一值，供管理端「通知历史」按次归组；单发也生成一个',
  `ToId` varchar(50) NOT NULL COMMENT '收件人业务号（user.customerid / user.merchantid），与会话存号口径一致',
  `ToType` varchar(16) NOT NULL COMMENT '收件人类型：customer / merchant（英文，对齐 message.from_type）',
  `EventType` varchar(64) NOT NULL COMMENT '事件类型 code，形如 comment.reply / goods.audit.rejected（对应 notification 域 NotificationEvents 常量；⚠️ 后端不校验白名单，未知 code 也照落）',
  `Title` varchar(128) NOT NULL COMMENT '通知标题',
  `Content` varchar(1024) NOT NULL COMMENT '通知正文',
  `TargetType` varchar(32) DEFAULT NULL COMMENT '关联对象类型（goods / user / comment…）；无关联为 NULL。⚠️ 软引用，无 FK',
  `TargetId` varchar(128) DEFAULT NULL COMMENT '关联对象标识（如 MerchantId:ProductCode）；无关联为 NULL。⚠️ 软引用，无 FK，前端容错',
  `ReadAt` datetime(3) DEFAULT NULL COMMENT '已读时刻；NULL = 未读（唯一判据）',
  `CreatedAt` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '通知产生时刻（毫秒精度，收件箱排序用；DB 存 UTC）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时刻；本表**非** append-only —— 标记已读会 UPDATE，故它随 ReadAt 变化',
  PRIMARY KEY (`Id`),
  KEY `idx_notification_inbox`  (`ToId`,`ToType`,`CreatedAt`),
  KEY `idx_notification_unread` (`ToId`,`ToType`,`ReadAt`),
  KEY `idx_notification_batch`  (`BatchId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统通知（C3 块1-I2 新增；平台→个人单向投递，每收件人一行）';

-- ============================================================
-- Migration: C3 块1-I2 通知模板  Date: 2026-10-09
-- 依据：docs/agent/c3-modules.md §6「消息模板管理」
-- 回滚：DROP TABLE `notification_template`;（规则 v2：回滚 = 逻辑废弃；该语句执行前须人工确认）
-- 幂等：同上（IF NOT EXISTS，块内不 DROP）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `notification_template` (
  `Id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `TemplateKey` varchar(64) NOT NULL COMMENT '模板键（业务唯一），形如 goods.audit.rejected',
  `TitleTemplate` varchar(255) NOT NULL COMMENT '标题模板（可含 {占位符}）',
  `BodyTemplate` varchar(1024) NOT NULL COMMENT '正文模板',
  `Enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用（停用 = 逻辑废弃，不物理删）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时刻',
  PRIMARY KEY (`Id`),
  UNIQUE KEY `uk_notification_template_key` (`TemplateKey`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统通知模板（C3 块1-I2 新增；含模板键/标题模板/正文模板/启用）';
