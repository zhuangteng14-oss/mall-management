/*
SQLyog Ultimate v12.09 (64 bit)
MySQL - 8.0.32 : Database - mall_management_sql
*********************************************************************
*/

/*!40101 SET NAMES utf8 */;

/*!40101 SET SQL_MODE=''*/;

/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
CREATE DATABASE /*!32312 IF NOT EXISTS*/`mall_management_sql` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `mall_management_sql`;

/*Table structure for table `customerimage` */

DROP TABLE IF EXISTS `customerimage`;

CREATE TABLE `customerimage` (
  `UserName` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `CustomerId` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  PRIMARY KEY (`UserName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `customerimage` */

insert  into `customerimage`(`UserName`,`CustomerId`,`image`) values ('123','pIzTWafaGp','http://localhost:8088/img/色款.jpg'),('aaa','obkb9birqH','http://localhost:8088/img/default_user.png');

/*Table structure for table `friend` */

DROP TABLE IF EXISTS `friend`;

CREATE TABLE `friend` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customerid` varchar(50) NOT NULL,
  `friendid` varchar(50) NOT NULL,
  `if_friend` int NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `friend` */

insert  into `friend`(`id`,`customerid`,`friendid`,`if_friend`) values (2,'pIzTWafaGp','5N1k5K2guh',1),(3,'5N1k5K2guh','pIzTWafaGp',1),(4,'5gtissg7Mj','pIzTWafaGp',1),(5,'pIzTWafaGp','5gtissg7Mj',1);

/*Table structure for table `goods` */

DROP TABLE IF EXISTS `goods`;

CREATE TABLE `goods` (
  `Id` int NOT NULL AUTO_INCREMENT,
  `MerchantId` char(20) NOT NULL,
  `GoodName` char(40) NOT NULL,
  `Price` double NOT NULL,
  `GoodImg` char(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `GoodType` char(20) DEFAULT NULL,
  `ProductCode` char(40) DEFAULT NULL,
  `GoodTypeNew` int DEFAULT NULL,
  PRIMARY KEY (`Id`)
) ENGINE=InnoDB AUTO_INCREMENT=26 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `goods` */

insert  into `goods`(`Id`,`MerchantId`,`GoodName`,`Price`,`GoodImg`,`GoodType`,`ProductCode`,`GoodTypeNew`) values (20,'5N1k5K2guh','222',5555,'http://localhost:8088/img/色款.jpg','电子用品','IJ-555',0),(22,'5N1k5K2guh','222',54,'http://localhost:8088/img/色款.jpg','电子用品','IF-55',0),(23,'5N1k5K2guh','叠法双方',20,'http://localhost:8088/img/色款.jpg','服装','dffffff',0),(24,'5N1k5K2guh','黑客',9999999,'http://localhost:8088/img/色款.jpg','电子用品','hsdk',0),(25,'5gtissg7Mj','1',1,'http://localhost:8088/img/色款.jpg','服装','1',0);

/*Table structure for table `goodstype` */

DROP TABLE IF EXISTS `goodstype`;

CREATE TABLE `goodstype` (
  `Id` int NOT NULL AUTO_INCREMENT,
  `GoodTypeNew` int NOT NULL DEFAULT '0',
  `GoodType` char(20) NOT NULL,
  PRIMARY KEY (`Id`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `goodstype` */

insert  into `goodstype`(`Id`,`GoodTypeNew`,`GoodType`) values (1,0,'电子用品'),(2,0,'生活用品'),(3,0,'服装'),(4,0,'美食'),(5,0,'运动用品');

/*Table structure for table `login` */

DROP TABLE IF EXISTS `login`;

CREATE TABLE `login` (
  `UserName` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `Password` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `Role` int NOT NULL,
  `Id` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  PRIMARY KEY (`UserName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `login` */

insert  into `login`(`UserName`,`Password`,`Role`,`Id`) values ('1','1',2,'5gtissg7Mj'),('123','123456',1,'pIzTWafaGp'),('2222','2222',2,'5N1k5K2guh'),('aaa','123',1,'obkb9birqH'),('性暗示','AS55641',2,'N7w3UOB5VI');

/*Table structure for table `merchantimage` */

DROP TABLE IF EXISTS `merchantimage`;

CREATE TABLE `merchantimage` (
  `UserName` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `MerchantId` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  PRIMARY KEY (`UserName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `merchantimage` */

insert  into `merchantimage`(`UserName`,`MerchantId`,`image`) values ('1','5gtissg7Mj','http://localhost:8088/img/default_user.png'),('2222','5N1k5K2guh','http://localhost:8088/img/123.jpg'),('性暗示','N7w3UOB5VI','http://localhost:8088/img/default_user.png');

/*Table structure for table `message` */

DROP TABLE IF EXISTS `message`;

CREATE TABLE `message` (
  `id` int NOT NULL AUTO_INCREMENT,
  `from_id` varchar(50) NOT NULL,
  `from_type` varchar(10) NOT NULL,
  `to_id` varchar(50) NOT NULL,
  `to_type` varchar(10) NOT NULL,
  `content` text NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_from_to` (`from_id`,`to_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `message` */

insert  into `message`(`id`,`from_id`,`from_type`,`to_id`,`to_type`,`content`,`create_time`) values (7,'5N1k5K2guh','merchant','pIzTWafaGp','customer','你好？？？？','2026-05-20 23:20:03'),(8,'5N1k5K2guh','merchant','pIzTWafaGp','customer','就回家干活','2026-05-20 23:43:50'),(9,'pIzTWafaGp','customer','5N1k5K2guh','merchant','我就一NSA觉得你','2026-05-20 23:53:50'),(10,'5N1k5K2guh','merchant','pIzTWafaGp','customer','安徽省多久啊是的','2026-05-20 23:53:57'),(11,'pIzTWafaGp','customer','5N1k5K2guh','merchant','都撒打','2026-05-20 23:54:00'),(12,'pIzTWafaGp','customer','5N1k5K2guh','merchant','三试试','2026-05-21 00:03:01'),(13,'pIzTWafaGp','customer','5N1k5K2guh','merchant','ZX从ZXZX','2026-05-21 00:03:11'),(14,'5N1k5K2guh','merchant','pIzTWafaGp','customer','撒大大撒撒','2026-05-21 00:03:27'),(15,'pIzTWafaGp','customer','5N1k5K2guh','merchant','撒大苏打','2026-05-21 00:03:49'),(16,'5N1k5K2guh','merchant','pIzTWafaGp','customer','大叔打','2026-05-21 00:03:55'),(17,'pIzTWafaGp','customer','5N1k5K2guh','merchant','啊飒飒打撒十大','2026-05-21 00:12:10'),(18,'5N1k5K2guh','merchant','pIzTWafaGp','customer','啊沙发上大飒飒大大','2026-05-21 00:15:38'),(19,'pIzTWafaGp','customer','5N1k5K2guh','merchant','大大实打实看到世界大会','2026-05-21 00:28:49'),(20,'pIzTWafaGp','customer','5N1k5K2guh','merchant','你你你你你你你你你','2026-05-21 00:31:19'),(21,'pIzTWafaGp','customer','5N1k5K2guh','merchant','隋1','2026-05-21 00:32:15'),(22,'5N1k5K2guh','merchant','pIzTWafaGp','customer','你好','2026-05-21 00:42:09'),(23,'5N1k5K2guh','merchant','pIzTWafaGp','customer','啊倒萨','2026-05-21 00:42:23'),(24,'pIzTWafaGp','customer','5N1k5K2guh','merchant','dasda','2026-05-21 00:48:39'),(25,'5N1k5K2guh','merchant','pIzTWafaGp','customer','dsdadsads','2026-05-21 00:49:44'),(26,'pIzTWafaGp','customer','5N1k5K2guh','merchant','nnnnnasdasd','2026-05-21 00:50:05'),(27,'pIzTWafaGp','customer','5gtissg7Mj','merchant','为什么呢！','2026-05-21 00:54:24'),(28,'5gtissg7Mj','merchant','pIzTWafaGp','customer','不断刷新阿萨','2026-05-21 00:54:33'),(29,'5gtissg7Mj','merchant','pIzTWafaGp','customer','搜索','2026-05-21 00:54:41'),(30,'pIzTWafaGp','customer','5N1k5K2guh','merchant','黑客','2026-05-21 16:13:18'),(31,'5N1k5K2guh','merchant','pIzTWafaGp','customer','黑哈师大','2026-05-21 16:13:30'),(32,'pIzTWafaGp','customer','5gtissg7Mj','merchant','[PROD]{\"id\":\"25\",\"merchantId\":\"5gtissg7Mj\",\"goodName\":\"1\",\"price\":1,\"goodImg\":\"http://localhost:8088/img/色款.jpg\",\"goodType\":\"服装\",\"productCode\":\"1\"}','2026-05-21 16:52:30'),(33,'pIzTWafaGp','customer','5gtissg7Mj','merchant','你好，这个商品价格能否打个折呢？','2026-05-21 16:53:01'),(34,'5gtissg7Mj','merchant','pIzTWafaGp','customer','亲，该商品快下架了，暂不支持','2026-05-21 16:53:38'),(35,'pIzTWafaGp','customer','5gtissg7Mj','merchant','[PROD]{\"id\":\"25\",\"merchantId\":\"5gtissg7Mj\",\"goodName\":\"1\",\"price\":1,\"goodImg\":\"http://localhost:8088/img/色款.jpg\",\"goodType\":\"服装\",\"productCode\":\"1\"}','2026-05-21 16:53:45');

/*Table structure for table `order` */

DROP TABLE IF EXISTS `order`;

CREATE TABLE `order` (
  `Id` int NOT NULL AUTO_INCREMENT,
  `MerchantId` varchar(40) NOT NULL,
  `CustomerId` varchar(40) NOT NULL,
  `OrderId` varchar(40) NOT NULL,
  `Price` float NOT NULL,
  `ProductInformation` varchar(500) NOT NULL,
  `Images` varchar(100) NOT NULL,
  `PayIf` int NOT NULL,
  `DeliveryIf` int NOT NULL,
  `ReceiveIf` int NOT NULL,
  `CreateTime` varchar(50) NOT NULL,
  `SettlementDate` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`Id`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `order` */

insert  into `order`(`Id`,`MerchantId`,`CustomerId`,`OrderId`,`Price`,`ProductInformation`,`Images`,`PayIf`,`DeliveryIf`,`ReceiveIf`,`CreateTime`,`SettlementDate`) values (18,'5N1k5K2guh','pIzTWafaGp','ZHJC53759811',297,'水晶巴雷特:01','http://localhost:8088/img/色款.jpg',1,1,1,'2026-05-18 21:28:20','2026-05-18 21:34:38'),(19,'5N1k5K2guh','5N1k5K2guh','LFZW31654220',22328,'222:IJ-555；222:IF-55','http://localhost:8088/img/色款.jpg',1,1,1,'2026-05-18 21:40:16','2026-05-18 21:40:51'),(20,'5N1k5K2guh','pIzTWafaGp','DOZD81289614',40,'叠法双方:dffffff','http://localhost:8088/img/色款.jpg',1,1,1,'2026-05-21 00:48:07','2026-05-21 00:48:17'),(21,'5N1k5K2guh','5N1k5K2guh','YYRG19302319',5771,'222:IJ-555；222:IF-55','http://localhost:8088/img/色款.jpg',1,1,1,'2026-05-21 16:19:00','2026-05-21 16:19:12'),(22,'5gtissg7Mj','5gtissg7Mj','NFXC57789451',2,'1:1','http://localhost:8088/img/色款.jpg',1,1,1,'2026-05-21 17:01:12','2026-05-21 17:01:20'),(23,'5gtissg7Mj','pIzTWafaGp','QZEQ72210207',11,'1:1','http://localhost:8088/img/色款.jpg',1,1,1,'2026-05-21 17:07:38','2026-05-21 17:07:49');

/*Table structure for table `productsales` */

DROP TABLE IF EXISTS `productsales`;

CREATE TABLE `productsales` (
  `Id` int NOT NULL AUTO_INCREMENT,
  `ProductCode` char(40) NOT NULL,
  `MerchantId` char(20) NOT NULL,
  `GoodName` char(20) NOT NULL,
  `OrderVolume` int DEFAULT NULL,
  PRIMARY KEY (`Id`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `productsales` */

insert  into `productsales`(`Id`,`ProductCode`,`MerchantId`,`GoodName`,`OrderVolume`) values (22,'IJ-555','5N1k5K2guh','222',0),(24,'IF-55','5N1k5K2guh','222',0),(25,'dffffff','5N1k5K2guh','叠法双方',0),(26,'hsdk','5N1k5K2guh','黑客',0),(27,'1','5gtissg7Mj','1',0);

/*Table structure for table `user` */

DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
  `username` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `usertype` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `customerid` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `merchantid` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `gender` char(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `age` int NOT NULL,
  `email` char(40) DEFAULT NULL,
  `selfintroduction` varchar(100) DEFAULT NULL,
  `address` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `user` */

insert  into `user`(`username`,`usertype`,`customerid`,`merchantid`,`gender`,`age`,`email`,`selfintroduction`,`address`) values ('1','商家',NULL,'5gtissg7Mj','男',19,NULL,NULL,NULL),('123','客户','pIzTWafaGp',NULL,'男',0,'412254asdadsad@163.com','大肆夸奖打卡时间共发生开始广泛LHSKDH','那就好啊开始干活苍溪县回家'),('2222','商家',NULL,'5N1k5K2guh','男',23,'sdadaddadeng14@gmail.com','撒大大撒大苏打倒萨撒大苏打实打实大苏打实打实大苏打实打实阿萨大大撒大大十大大苏打','笑笑笑笑笑笑笑'),('aaa','客户','obkb9birqH',NULL,'男',51,NULL,NULL,NULL),('性暗示','商家',NULL,'N7w3UOB5VI','女',36,NULL,NULL,NULL);

/*Table structure for table `user_conversation_read` */

DROP TABLE IF EXISTS `user_conversation_read`;

CREATE TABLE `user_conversation_read` (
  `user_id` varchar(50) NOT NULL,
  `user_type` varchar(10) NOT NULL,
  `other_id` varchar(50) NOT NULL,
  `other_type` varchar(10) NOT NULL,
  `last_read_time` datetime NOT NULL,
  PRIMARY KEY (`user_id`,`user_type`,`other_id`,`other_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*Data for the table `user_conversation_read` */

insert  into `user_conversation_read`(`user_id`,`user_type`,`other_id`,`other_type`,`last_read_time`) values ('5gtissg7Mj','merchant','pIzTWafaGp','customer','2026-05-21 16:59:18'),('5N1k5K2guh','merchant','pIzTWafaGp','customer','2026-05-21 16:13:50'),('pIzTWafaGp','customer','5gtissg7Mj','merchant','2026-05-21 16:59:18'),('pIzTWafaGp','customer','5N1k5K2guh','merchant','2026-05-21 16:58:59');

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- ============================================================
-- Migration: B3 订单状态流转时间线  Date: 2026-09-29
-- ============================================================
CREATE TABLE IF NOT EXISTS `order_state_transition` (
  `Id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键。⚠️ 不保证同单内与发生顺序一致：落库是 4 条线程并发 INSERT，Id 在 INSERT 时才分配 —— 同单内排序请用 OccurredAt',
  `OrderId` varchar(40) NOT NULL COMMENT '订单号，对应 `order`.OrderId',
  `Action` varchar(16) NOT NULL COMMENT '动作 tag：create/pay/deliver/receive（OrderAction.tag()）',
  `FromState` varchar(40) NOT NULL COMMENT '转移前状态名；出生为 NONE；越界取值写 UNCLASSIFIED',
  `ToState` varchar(40) NOT NULL COMMENT '转移后状态名',
  `OccurredAt` datetime(3) NOT NULL COMMENT '转移发生时刻（在订单锁的临界区内取）；同单内按它排序，与 created_at 之差即队列延迟',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本表 append-only，恒等于 created_at；为规则 8 保留',
  PRIMARY KEY (`Id`),
  KEY `idx_order_state_transition_order` (`OrderId`,`OccurredAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单状态流转时间线（B3 新增；C3 时间线视图的数据源）';
-- ============================================================
-- Migration: S1a 订单号唯一索引  Date: 2026-09-30
-- 依据：TD-14（order.OrderId 无索引）· defect-survey 病 C · P0 #2 第 1 环
-- 前置：order 侧重复行检测为 0（本机 7/7、VM 6/6，2026-09-30 实测）
-- 回滚：DROP INDEX `uk_order_orderId` ON `order`;
--       （规则 v2：回滚 = 逻辑废弃；该语句执行前须人工确认）
-- 幂等：「导入 = 先 DROP 后 CREATE」（本文件头部含 DROP TABLE IF EXISTS `order`），
--       且 MySQL 无 CREATE INDEX IF NOT EXISTS ⇒ 只在全量重导时执行。
-- ============================================================
CREATE UNIQUE INDEX `uk_order_orderId` ON `order` (`OrderId`);
-- ============================================================
-- Migration: goodstype 类型名唯一索引  Date: 2026-10-04
-- 依据：defect-survey 病 G 表 2026-10-04 补登行 · selection-status §8 类 1 #10 / 类 2 #12
-- 根因：写入路径 GoodsTypeCacheService.addType 是「SELECT COUNT(*) 查重 → INSERT」的
--       check-then-insert，且【不在】B2 的 5 个分布式锁落点内 ⇒ 并发下会造重复行。
-- 前置：⚠️ 与 uk_order_orderId 块【不同】—— goodstype 的重复行是【已实测存在】的：
--       A3 的 B-2 场景（CI ci49，2026-10-03）goodstypeDupRounds = 4/5（逐轮 rows 4/1/4/3/3）。
--       故 agent.md 规则 3「每个库重复行检测为 0 才能建」在本条【不成立】。
--       兜法与 order 块相同：本文件头部含 DROP TABLE IF EXISTS `goodstype`，
--       导入 = 先 DROP 后 CREATE ⇒ 全量重导路径上建表时表为空，索引必建成。
--       ⚠️ 对【已有重复行的既有库】必须先人工去重再建，不可直接执行本块。
-- 回滚：DROP INDEX `uk_goodstype_GoodType` ON `goodstype`;
--       （规则 v2：回滚 = 逻辑废弃；该语句执行前须人工确认）
-- 幂等：「导入 = 先 DROP 后 CREATE」，且 MySQL 无 CREATE INDEX IF NOT EXISTS ⇒ 只在全量重导时执行。
-- 应用层：SELECT COUNT(*) 查重【保留】；catch 已区分 DuplicateKeyException（返回「类型已存在」）。
-- ============================================================
CREATE UNIQUE INDEX `uk_goodstype_GoodType` ON `goodstype` (`GoodType`);

-- ============================================================
-- Migration: C3 批 A 管理端审计日志  Date: 2026-10-08
-- 依据：docs/agent/c3-modules.md §11「权限与审计」（C3 批 A）· 用户裁决「批 A 先做」
-- 用途：横切所有写操作，记录「谁 / 何时 / 对象 / 改前 / 改后」—— C3「权限与审计」页的数据源。
--       第一批只挂 **1 个样板钩子**（goods/goodsUp），其余端点随各自那笔挂。
-- 为何 append-only：审计行若能改，「谁改了什么」就不再可信 ⇒ 本表只 INSERT，永不 UPDATE / DELETE。
-- 回滚：DROP TABLE `admin_audit`;（规则 v2：回滚 = 逻辑废弃；该语句执行前须人工确认）
-- 幂等：「导入 = 先 DROP 后 CREATE」。⚠️ 与 order_state_transition 块同款 —— 本块用
--       IF NOT EXISTS 且**不**在头部加 DROP ⇒ 全量重导**不清**历史审计行（刻意：审计跨重导保留）。
--       代价：重导后本表非空，与其余 11 张「重导即清空」的表不同。
-- ⚠️ 只应用于 VM / CI 的专用实例（本文件头部含全量 DROP，绝不可指向有真实数据的库）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `admin_audit` (
  `Id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `ActorId` varchar(50) DEFAULT NULL COMMENT '操作者业务号（会话里的 customerid/merchantid，**不是**登录名）；认不出身份为 NULL。⚠️ 会话存的就是业务号，见 AuthSession',
  `ActorRole` int DEFAULT NULL COMMENT '操作者角色，取自 login.Role：1 客户 / 2 商家 / 3 管理员；认不出为 NULL',
  `Action` varchar(64) NOT NULL COMMENT '动作 tag，形如 goods.update（与 admin 域 AuditActions 常量一一对应）',
  `TargetType` varchar(32) NOT NULL COMMENT '被改对象的类型，形如 goods',
  `TargetId` varchar(128) NOT NULL COMMENT '被改对象的标识，形如 MerchantId:ProductCode',
  `BeforeJson` json DEFAULT NULL COMMENT '**改前**快照（JSON）；新增类动作（无改前态）为 NULL',
  `AfterJson` json DEFAULT NULL COMMENT '**改后**快照（JSON）',
  `OccurredAt` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '动作发生时刻（毫秒精度，同秒内可排序）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本表 append-only，恒等于 created_at；为规则 8 保留',
  PRIMARY KEY (`Id`),
  KEY `idx_admin_audit_occurred` (`OccurredAt`),
  KEY `idx_admin_audit_actor` (`ActorId`,`OccurredAt`),
  KEY `idx_admin_audit_target` (`TargetType`,`TargetId`,`OccurredAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理端审计日志（C3 批 A 新增；append-only，横切所有写操作）';

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

-- ============================================================
-- Migration: C3 块3 商品块  Date: 2026-10-10
-- 依据：docs/agent/c3-modules.md §2「商品与类目治理」
--       · docs/agent/context-packs/c3-block-3-goods.md
--       · _work/c3-block3-landing-plan.md §三（A 档 4 项已裁，2026-10-10）
-- 内容：① `goodstype` 加 3 列（ParentId/Sort/State）+ 唯一键换代（全局 GoodType → (ParentId, GoodType)）
--       ② `goods` 加 5 列（TypeId / 3 个模板 Id / DeletedAt）+ 按名字回填 TypeId
--       ③ 新表 `goods_template`（详情/规格/售后 基础模板库）
--       ④ 新表 `goods_audit`（商品提审历史；提审只写本表，通过才写 goods 行）
-- 回滚（规则 v2：回滚 = 逻辑废弃，执行前须人工确认）：
--       DROP TABLE `goods_audit`;
--       DROP TABLE `goods_template`;
--       ALTER TABLE `goods` DROP COLUMN `TypeId`,`DetailTemplateId`,`SpecTemplateId`,`AfterSaleTemplateId`,`DeletedAt`;
--       DROP INDEX `uk_goodstype_parent_name` ON `goodstype`;
--       CREATE UNIQUE INDEX `uk_goodstype_GoodType` ON `goodstype` (`GoodType`);
--       DROP INDEX `idx_goodstype_parent_sort` ON `goodstype`;
--       ALTER TABLE `goodstype` DROP COLUMN `ParentId`,`Sort`,`State`;
-- 幂等：新表用 `CREATE TABLE IF NOT EXISTS`；⚠️ 加列 / 建索引**不幂等** —— MySQL **不支持**
--       `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（同块2 口径，见
--       https://dev.mysql.com/doc/refman/8.4/en/alter-table.html ，无此子句），
--       `CREATE [UNIQUE] INDEX` 亦无 `IF NOT EXISTS`。CI 是**整份重导**（先 DROP 后 CREATE）
--       ⇒ 每轮只执行一次，安全；VM 库手动执行一次，**重复执行会报
--       Duplicate column (1060) / Duplicate key name (1061)（预期，非故障）**。
-- ⚠️ 本块改动**不写进文件上半部的基线 CREATE TABLE**（同块1-I2 / 块2 处置）：
--    基线区保持历史原样，新列 / 新表 / 索引换代一律以「末尾 Migration 块」表达。
-- 前置：⚠️ 唯一键换代**依赖既有类目 ParentId 全为 0 且名字互异** —— 种子见本文件 `goodstype`
--       的 INSERT（Id 1-5：电子用品 / 生活用品 / 服装 / 美食 / 运动用品），5 条全为一级（ParentId=0）、名互异
--       ⇒ 新键 `(ParentId, GoodType)` 在全量重导路径上**当场成立**，不需先去重。
--       若在【已有重复 (ParentId=0, GoodType) 的既有库】执行，须先人工去重（同块2 uk 块口径）。
-- ============================================================

-- ---- ① goodstype：加 3 列（规则 7）+ 唯一键换代（全局 → 每父级下唯一）----
-- 换代理由：全局唯一会让「电子用品>配件」与「服装>配件」撞键 ⇒ 管理员录不进第二个「配件」。
--           电商类目本就允许不同父级下同名子类目 ⇒ 键改 (ParentId, GoodType)。
--           ⚠️ 连带三件：goods 只按【名字】软引用类目 ⇒ 必须给 goods 加 `TypeId` + 回填 +
--           买家二级筛选改走 Id（见 ② 与前端契约口径）。
-- 顺序：先加列（ParentId 有默认值 ⇒ 既有 5 行自动补 0）→ 建普通索引 → 换唯一键。
ALTER TABLE `goodstype`
  ADD COLUMN `ParentId` int NOT NULL DEFAULT 0 COMMENT '父类目 Id：0 = 一级类目（自引用 goodstype.Id；模型不限深度，UI 只做二级）',
  ADD COLUMN `Sort` int NOT NULL DEFAULT 0 COMMENT '同级排序（升序）；0 = 默认',
  ADD COLUMN `State` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用（逻辑废弃，不物理删）';
CREATE INDEX `idx_goodstype_parent_sort` ON `goodstype` (`ParentId`,`Sort`);
-- ⚠️ 换代：先 DROP 全局唯一键，再建 (ParentId, GoodType) 唯一键（两句须成对，中间无写窗口）。
DROP INDEX `uk_goodstype_GoodType` ON `goodstype`;
CREATE UNIQUE INDEX `uk_goodstype_parent_name` ON `goodstype` (`ParentId`,`GoodType`);

-- ---- ② goods：加 5 列（规则 7）----
-- ⚠️ **不加 `AuditState` / `RejectReason`** —— 自查（2026-10-10）：goods.AuditState 的第二值
--    一种都不存在（违规下架 = ShelfState、违规删除 = DeletedAt、驳回/待审 = goods_audit.Status）
--    ⇒ 恒 'approved' 的死列；goods.RejectReason 亦被 goods_audit 完全覆盖 ⇒ 一并去掉。
ALTER TABLE `goods`
  ADD COLUMN `TypeId` int DEFAULT NULL COMMENT '类目 Id（软引用 goodstype.Id；唯一键换代连带，供二级筛选按 Id 走）',
  ADD COLUMN `DetailTemplateId` int DEFAULT NULL COMMENT '详情模板 Id（软引用 goods_template.Id）；未选为 NULL',
  ADD COLUMN `SpecTemplateId` int DEFAULT NULL COMMENT '规格模板 Id（软引用 goods_template.Id）；未选为 NULL',
  ADD COLUMN `AfterSaleTemplateId` int DEFAULT NULL COMMENT '售后模板 Id（软引用 goods_template.Id）；未选为 NULL',
  ADD COLUMN `DeletedAt` datetime(3) DEFAULT NULL COMMENT '违规删除时刻；正常为 NULL（逻辑删除，不物理删）';

-- ---- ② 续：按名字回填 TypeId（现有 5 条类目名全库唯一 ⇒ 无歧义）----
-- ⚠️ 必须紧跟在上面的 ADD COLUMN 之后、且在【还没有】同名二级子类目插入之前执行
--    —— 此刻 goodstype 里每个名字只可能匹配一行，UPDATE ... JOIN 不会多对一放大。
UPDATE `goods` g JOIN `goodstype` t ON g.`GoodType` = t.`GoodType` SET g.`TypeId` = t.`Id` WHERE g.`TypeId` IS NULL;

-- ---- ③ 新表 goods_template：基础模板库（详情 / 规格 / 售后三类）----
CREATE TABLE IF NOT EXISTS `goods_template` (
  `Id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `TemplateType` varchar(16) NOT NULL COMMENT '模板类型：detail 详情 / spec 规格 / after_sale 售后（字符串码，不建 DB ENUM）',
  `Name` varchar(64) NOT NULL COMMENT '模板名',
  `Content` mediumtext COMMENT '模板内容：详情=富文本 / 规格=规格表 / 售后=保障文案；空为 NULL',
  `Enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用（逻辑废弃，不物理删）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时刻',
  PRIMARY KEY (`Id`),
  KEY `idx_goods_template_type_enabled` (`TemplateType`,`Enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品基础模板库（C3 块3 新增；详情/规格/售后三类，商家上架时选用）';

-- ---- ④ 新表 goods_audit：商品提审历史（镜像块2 的 merchant_application）----
-- 口径：**提审只写本表（不写 goods 行）**；管理员【通过】才写 goods 行 + CAS 置本表
--       Status='approved' + 回填 GoodsId；【驳回】只改本表 Status='rejected' + RejectReason。
--       **再次提审 = 新插一行**（保留完整历史）。故本表可多次 INSERT、可 UPDATE（非 append-only）。
-- 命名：RejectReason 沿用块2 `merchant_application.RejectReason`（同名同义，不用 Reason/AuditRejectReason）；
--       BatchId 照块2（给通知/审计一个**跨重导稳定**的 TargetId）。
CREATE TABLE IF NOT EXISTS `goods_audit` (
  `Id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `MerchantId` varchar(20) NOT NULL COMMENT '提审商家号（goods.MerchantId）。⚠️ 软引用，不设 FK（同 notification.TargetId 口径）',
  `BatchId` varchar(36) NOT NULL COMMENT '提审时生成的 UUID；给通知与审计一个跨重导稳定的 TargetId（同 I2/块2 理由）',
  `GoodName` varchar(40) NOT NULL COMMENT '商品名（提审快照）',
  `Price` double NOT NULL COMMENT '价格（提审快照）',
  `GoodType` varchar(20) DEFAULT NULL COMMENT '类目名（提审快照；仅作展示/兼容，正式引用走 TypeId）',
  `GoodTypeNew` int DEFAULT NULL COMMENT '类目新旧位（提审快照；通过时原样写进 goods.GoodTypeNew）',
  `TypeId` int DEFAULT NULL COMMENT '类目 Id（提审快照；通过时原样写进 goods.TypeId。⚠️ 必须随单走 —— 否则改名后按名字回查会歧义）',
  `ProductCode` varchar(40) DEFAULT NULL COMMENT '商品货号（提审快照）',
  `GoodImg` varchar(255) DEFAULT NULL COMMENT '商品图 URL（提审快照；放宽到 255 容长 URL）',
  `Status` varchar(16) NOT NULL DEFAULT 'pending' COMMENT '审核状态：pending / approved / rejected（小写，与 CAS 的 WHERE Status=? 字面一致；字符串码，不建 DB ENUM）',
  `RejectReason` varchar(255) DEFAULT NULL COMMENT '驳回理由（Status=rejected 时有值）',
  `GoodsId` bigint DEFAULT NULL COMMENT '通过后回填的 goods.Id（软引用）；未通过为 NULL',
  `SubmittedAt` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时刻（DB 存 UTC）',
  `ReviewedBy` varchar(20) DEFAULT NULL COMMENT '审核的管理员账号名（管理员无独立业务号）',
  `ReviewedAt` datetime(3) DEFAULT NULL COMMENT '审核时刻；未审为 NULL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时刻（审核会 UPDATE，故本表非 append-only）',
  PRIMARY KEY (`Id`),
  KEY `idx_goods_audit_status` (`Status`,`SubmittedAt`),
  KEY `idx_goods_audit_merchant` (`MerchantId`,`SubmittedAt`),
  KEY `idx_goods_audit_batch` (`BatchId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品提审历史（C3 块3 新增；提审只写本表，通过才写 goods 行、再次提审插新行）';

-- ============================================================
-- Migration: C3 块3 就地改造 —— 模板表通用化 + 商品侧改「快照」  Date: 2026-10-10
-- 依据：用户 2026-10-10 决策（两套模板表 → 一张通用 `template`；goods 的模板「引用 Id」→「快照文本」）
--       · docs/agent/context-packs/c3-block-3-goods.md
--       · _work/c3-block3-landing-plan.md §十一（三条待裁已被本决策作废）
-- 内容：① 新表 `template`（通用模板库，`Type` 区分用途）
--       ② `goods` 加 3 段快照文本（Detail / Spec / AfterSale）、删 3 个模板引用 Id
--       ③ `goods_audit` 加 3 段快照文本（提审携带；审核通过时原样写进 goods 行）
--       ④ 搬迁 `notification_template` 的行（新 Type = 'notification.' + 原 TemplateKey）
--       ⑤ 物理删两张老表 `goods_template` / `notification_template`
-- 回滚（规则 v2：回滚 = 逻辑废弃，执行前须人工确认）：
--       DROP TABLE `template`;
--       ALTER TABLE `goods` DROP COLUMN `Detail`,`Spec`,`AfterSale`;
--       ALTER TABLE `goods_audit` DROP COLUMN `Detail`,`Spec`,`AfterSale`;
--       （3 个 `*TemplateId` 不回填 —— 逻辑废弃，不恢复）
-- 位置：追加在文件尾（原 515 行之后）。原 1-515 的 `文件:行号` 互引**零漂移**：
--       两张老表的建表块（`notification_template` at 原 335-345 / `goods_template` at 原 474-484）
--       **留在原地不动**，由本块尾部的 `DROP TABLE IF EXISTS` 中和 ——
--       净判据「导入后这两张表不存在」与「物理删」等价。
-- 幂等：MySQL 不支持 `ADD / DROP COLUMN IF NOT EXISTS`（同块2 / 块3 口径，见 8.4 手册
--       alter-table 无此子句）⇒ 本块每个 ALTER 都走 `information_schema` 守卫 + PREPARE。
--       **重复执行是空操作，不报错**（守卫命中后走 `DO 0`）。
-- 前置：⚠️ ③ 要求 `goods_audit` 已存在（即块 3 已跑）。整份重导路径上块 3 在本块之前 ⇒ 成立；
--       若在【没有块 3 的既有库】上**单跑本块**，须先跑块 3（本机库已落块 3，故只需跑本块）。
-- ⚠️ 本块改动**不写进文件上半部的基线 CREATE TABLE**（同块1-I2 / 块2 / 块3 处置）：
--    基线区保持历史原样，新表 / 新列一律以「末尾 Migration 块」表达。
-- ============================================================

-- ---- ① 新表 template：通用模板库 ----
-- 口径：一张表承载**所有**「提供思路」的模板，靠 `Type` 区分用途。Type 是**字符串码**
--       （点分，不建 DB ENUM —— 同 goods_audit.Status / goods_template.TemplateType 口径）：
--         goods.detail / goods.spec / goods.afterSale / goods.reject /
--         merchant.reject / merchant.approve / user.ban / comment.reply /
--         notification.<原 notification_template.TemplateKey>
-- ⚠️ 商品侧是**快照**语义：`Body` 只是「起点内容」，商家选中后填进自己的输入框、可改，
--    落库的是**商品自己那一行文本**（goods.Detail / Spec / AfterSale）——
--    **改模板不影响已上架商品**，与 goods_template 的「引用 Id」语义相反。
-- ⚠️ 通知侧只是**搬家**：`notification.*` 一 type 一条（Name = 原 TemplateKey，Title/Body = 原两列）。
--    块 1 立下的「占位符渲染留后」口径**不变** —— 本块不做渲染接线。
CREATE TABLE IF NOT EXISTS `template` (
  `Id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `Type` varchar(96) NOT NULL COMMENT '模板类型（点分字符串码，不建 DB ENUM）：goods.detail / goods.spec / goods.afterSale / goods.reject / merchant.reject / merchant.approve / user.ban / comment.reply / notification.<原 TemplateKey>。⚠️ 96 = notification.(13) + TemplateKey 上界(64) + 余量；原定 32 装不下（见下方加宽守卫）',
  `Name` varchar(64) NOT NULL COMMENT '模板名（弹窗列表的显示名；同 Type 内唯一）',
  `Title` varchar(255) DEFAULT NULL COMMENT '标题模板（通知类用，可含 {占位符}）；商品类为 NULL',
  `Body` mediumtext COMMENT '正文：商品类 = 纯文本内容（快照的起点，商家可改）；通知类 = 正文模板',
  `Enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用（逻辑废弃，不物理删）',
  `Sort` int NOT NULL DEFAULT 0 COMMENT '同 Type 内排序（升序）；0 = 默认',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时刻',
  PRIMARY KEY (`Id`),
  UNIQUE KEY `uk_template_type_name` (`Type`,`Name`),
  KEY `idx_template_type_enabled_sort` (`Type`,`Enabled`,`Sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用模板表（C3 块3 就地改造；合并原 goods_template + notification_template）';

-- ---- ① 续 template.Type 加宽守卫（幂等）----
-- ⚠️ 2026-10-10 实测缺陷（scratch 库演练 → ERROR 1406 Data too long for column 'Type'）：
--    Type 原定 varchar(32) **装不下**通知侧搬迁值 —— 第 ④ 步写的是 CONCAT('notification.', TemplateKey)，
--    源列 `notification_template`.`TemplateKey` 是 varchar(64)，真实最长见 NotificationEvents
--    `merchant.downgrade.scheduled`（28）⇒ 41 字符 > 32。⇒ **任何有模板行的库跑第 ④ 步必然 1406**。
--    已批决策 4（保留 `notification.` 前缀）不变 ⇒ 只能加宽本列：上界 = 13 + 64 = 77，取 96 留余量。
--    守卫让【已按窄列建过 template 的库】就地补宽（本机库 / VM 库都是先按 32 建出来的）。
--    MAX(...) 在表不存在时为 NULL ⇒ IF 走 else 分支，安全。
SET @s := (SELECT IF(MAX(CHARACTER_MAXIMUM_LENGTH) < 96,
  'ALTER TABLE `template` MODIFY COLUMN `Type` varchar(96) NOT NULL COMMENT ''模板类型（点分字符串码，不建 DB ENUM）：goods.detail / goods.spec / goods.afterSale / goods.reject / merchant.reject / merchant.approve / user.ban / comment.reply / notification.<原 TemplateKey>''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'template' AND COLUMN_NAME = 'Type');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

-- ---- ② goods：加 3 段快照文本 ----
-- 加列守卫：`information_schema` 判「不存在才加」。3 列各一段（Detail / Spec / AfterSale）。
SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `goods` ADD COLUMN `Detail` mediumtext COMMENT ''详情（快照文本；选模板后可由商家改，落库即定稿）''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods' AND COLUMN_NAME = 'Detail');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `goods` ADD COLUMN `Spec` mediumtext COMMENT ''规格（快照文本；约定行内「键=值」，一行一条）''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods' AND COLUMN_NAME = 'Spec');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `goods` ADD COLUMN `AfterSale` mediumtext COMMENT ''售后（快照文本）''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods' AND COLUMN_NAME = 'AfterSale');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

-- ---- ② 续 goods：删 3 个模板引用 Id（改快照后不再引用模板行）----
-- 删列守卫：判「存在才删」。⚠️ 三个 `*TemplateId` 今天全仓零写入（恒 NULL）⇒ 删列无数据损失。
SET @s := (SELECT IF(COUNT(*) = 1,
  'ALTER TABLE `goods` DROP COLUMN `DetailTemplateId`',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods' AND COLUMN_NAME = 'DetailTemplateId');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 1,
  'ALTER TABLE `goods` DROP COLUMN `SpecTemplateId`',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods' AND COLUMN_NAME = 'SpecTemplateId');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 1,
  'ALTER TABLE `goods` DROP COLUMN `AfterSaleTemplateId`',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods' AND COLUMN_NAME = 'AfterSaleTemplateId');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

-- ---- ③ goods_audit：加 3 段快照文本 ----
-- 为什么必须加：提审**只写 goods_audit**（不写 goods 行），审核【通过】时由
--   GoodsAuditService.writeGoodsFromAudit 用**提审行**重建 goods 行 ⇒ 提审若不携带这 3 段，
--   审核通过后 goods.Detail / Spec / AfterSale 会丢。
SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `goods_audit` ADD COLUMN `Detail` mediumtext COMMENT ''详情快照（提审时携带；通过时原样写进 goods.Detail）''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods_audit' AND COLUMN_NAME = 'Detail');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `goods_audit` ADD COLUMN `Spec` mediumtext COMMENT ''规格快照（同上）''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods_audit' AND COLUMN_NAME = 'Spec');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `goods_audit` ADD COLUMN `AfterSale` mediumtext COMMENT ''售后快照（同上）''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods_audit' AND COLUMN_NAME = 'AfterSale');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

-- ---- ④ 搬迁 notification_template 的行（必须早于 ⑤ 的 DROP）----
-- Type = 'notification.' + 原 TemplateKey（一 type 一条）；Name 取原 TemplateKey（弹窗/列表的显示名）。
-- 幂等：`ON DUPLICATE KEY UPDATE Id = template.Id` = 撞唯一键时**啥也不改**（不覆盖已编辑过的模板）；
--       ⚠️ RHS 必须限定到 `template`（2026-10-10 实测）：源表 `notification_template` 也有 `Id`
--       ⇒ 写成裸 `Id = Id` 会在【源表存在】的库上报 1052「Column 'Id' in field list is ambiguous」。
--       本机库源表不存在（走 `DO 0`）故测不出，仅 VM / 全量重导路径暴露。
--       不用 `INSERT IGNORE`（会吞掉无关错误），也不用 `VALUES()`（8.0.20 起废弃且刷告警）。
-- 守卫：源表可能不存在（本机库就没有）。
SET @has_nt := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification_template');
SET @m := IF(@has_nt > 0,
  'INSERT INTO `template` (`Type`,`Name`,`Title`,`Body`,`Enabled`,`Sort`)
     SELECT CONCAT(''notification.'', `TemplateKey`), `TemplateKey`, `TitleTemplate`, `BodyTemplate`, `Enabled`, 0
     FROM `notification_template`
   ON DUPLICATE KEY UPDATE `Id` = `template`.`Id`',
  'DO 0');
PREPARE _p FROM @m; EXECUTE _p; DEALLOCATE PREPARE _p;

-- ---- ⑤ 物理删两张老表 ----
-- 用户 2026-10-10 决策：goods_template 与 notification_template 一并物理删
--   （前者未提交、仅本机库有、0 行；后者已提交、VM 已建、0 行且从未接线 —— 行已在上一步搬入 template）。
-- ⚠️ 这两张表的**基线建表块留在文件原处不动**（零漂移），净行为由本处 DROP 兑现。
DROP TABLE IF EXISTS `goods_template`;
DROP TABLE IF EXISTS `notification_template`;

/* =====================================================================
   Migration 块 6：C3 块 4〈订单块〉（2026-10-10，分支 feat/c3-admin-block4）
   ---------------------------------------------------------------------
   ① 建 `cart` —— 真购物车载体（A1 甲，用户 2026-10-10 逐项裁决）
   ② `order` 加 `Quantity`（A2 甲）
   ⚠️ 幂等：每个 DDL 先查 information_schema 再决定执行 / `DO 0` ⇒ 两库都能跑、重跑是空操作。
   ⚠️ 本块**只增不改**：不删表、不改既有列、不动既有数据（规则 v2：逻辑废弃不物理删）。
   ⚠️ `cart` **不在**文件头（:21 起）的 `DROP TABLE IF EXISTS` 基线清单里
      ⇒ 整份重导**不会清空购物车**（它是用户数据不是种子）。列数变化由 CREATE IF NOT EXISTS 兜住。
   ===================================================================== */

-- ---- ① 新表 `cart`（真购物车）----
-- 列宽一律向**源列**对齐，不照抄既有表的旧偏差：
--   CustomerId / MerchantId ← `order` 的 varchar(40)（:143-144）
--   ProductCode            ← `goods` 的 char(40)（:61）
--   PriceSnapshot          ← `goods`.`Price` 的 double（:58）
--   ⚠️ 刻意**不**照 `order`.`Price` 的 float（:146）—— 那是 `defect-survey.md` 记过的不一致，
--      新列向源列对齐，不复制旧缺陷（谁要对齐 float，先用实测说明为什么）。
-- 唯一键 `UNIQUE(CustomerId, ProductCode)` ⇒ 同商品重复加购**合并数量**，不新开行。
--   ⚠️ 代价：`ProductCode` 跨商家不保证唯一（本仓无店铺表），故**归属以 `MerchantId` 为准**：
--      回读商品名/图一律按 `(MerchantId, ProductCode)` 联 `goods`（与 `GoodsDao.updateGoods` 的
--      既有口径一致），不按单独的 ProductCode 联。
-- ⚠️ `PriceSnapshot` **就是成交价的来源**（不是「仅供参考」）：结算时以它为准 ——
--   用户看到的价与下单落库的价必须是同一个数，否则「购物车里 ¥10、订单里 ¥12」就是改价争议
--   （这正是 A1 选「存快照」的理由，见 `_work/c3-block4-landing-plan.md §一 A1`）。
--   兜底：快照为 NULL 的行（非本链路写入）结算时回 `goods.Price`。
CREATE TABLE IF NOT EXISTS `cart` (
  `Id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `CustomerId` varchar(40) NOT NULL COMMENT '买家业务号（对齐 order.CustomerId）',
  `MerchantId` varchar(40) NOT NULL COMMENT '卖家业务号（加购时从 goods 带入，对齐 order.MerchantId）',
  `ProductCode` char(40) NOT NULL COMMENT '商品编码（对齐 goods.ProductCode）',
  `Quantity` int NOT NULL DEFAULT 1 COMMENT '数量（≥1；改量走 UPDATE，不加行）',
  `PriceSnapshot` double DEFAULT NULL COMMENT '加购时的单价快照 = **结算成交价**（对齐 goods.Price；NULL 时结算回 goods 取现价）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '行更新时刻',
  PRIMARY KEY (`Id`),
  UNIQUE KEY `uk_cart_customer_product` (`CustomerId`,`ProductCode`),
  KEY `idx_cart_customer` (`CustomerId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='真购物车（C3 块4 A1 甲；用户 2026-10-10 裁决）';

-- ---- ①b 列注释订正（同笔）----
-- 起因：初稿把 `PriceSnapshot` 注释成「仅陈列/对账，非成交价」，与 A1 的选型理由（存快照
-- 就是为了**避免改价争议** ⇒ 结算以快照为准）**相反**。语义错的是注释，改注不改列。
-- ⚠️ 为什么必须单独一条：`CREATE TABLE IF NOT EXISTS` 对**已存在**的表是**空操作**
-- ⇒ 已按旧注建过表的库（本机库 / VM，都是本笔几分钟前建的）不会自己更正。
-- 幂等性：`MODIFY COLUMN` 把列定义重写成**同一份**定义 ⇒ 重复执行是空操作（不报错、不改数据）。
ALTER TABLE `cart` MODIFY COLUMN `PriceSnapshot` double DEFAULT NULL COMMENT '加购时的单价快照 = **结算成交价**（对齐 goods.Price；NULL 时结算回 goods 取现价）';

-- ---- ② `order` 加 `Quantity`（A2 甲）----
-- 规则 7：加列一律**表末** + 默认值 + COMMENT。`DEFAULT 1` ⇒ 存量 6 行（:159）全部落到「1 件」，
-- 语义正确（那些单本就只买了 1 件），且**不改行数** ⇒ A3 的 P4 种子计数（order=6）不受影响。
SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `order` ADD COLUMN `Quantity` int NOT NULL DEFAULT 1 COMMENT ''数量（C3 块4 A2 甲；存量行回填 1）''',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'Quantity');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

/* =====================================================================
   Migration 块 7：C3 块 4 补丁〈付款/发货 模拟完整流程〉（2026-10-10，分支 feat/c3-admin-block4）
   ---------------------------------------------------------------------
   ① `order` 加 **7** 列 —— 支付 3（TradeNo / PaymentMethod / PaidAt）+ 物流 3（ExpressCompany /
      TrackingNo / ShippedAt）+ 收货 1（ReceivedAt）。
      ⚠️ 是 7 列，**不是**框架原文写的「8」：其「物流」那一组 4 项里**已含 ReceivedAt**
      ⇒ 列名不重复共 **7** 个（ReceivedAt 归物流组还是收货组只是表述，列的集合不变）。
      定性见 `_work/c3-block4-patch-landing-plan.md §1.1` + 用户 2026-10-10 批复 ①。
   ② 新表 `order_shipping_track` —— 模拟物流轨迹（A2）。
      **只由「发货 / 调度器 / 收货」路径写**；调度器**只追加行、绝不 UPDATE `order` 的状态位**
      （用户 2026-10-10 批准的红线 ⇒ 状态位仍只由三条 CAS 边推进，保 A3 不变量与状态机审计）。
   ⚠️ 幂等：加列先查 information_schema 再决定执行 / `DO 0`；`CREATE TABLE IF NOT EXISTS` 天然幂等。
   ⚠️ 本块**只增不改**：不删表、不改既有列、不动既有数据。7 个新列全 NULL 默认
      ⇒ 存量行零影响、**不改行数** ⇒ A3 的 P4 种子计数（order=6）不受影响。
   ⚠️ `order_shipping_track` **不在**文件头（:21 起）的 `DROP TABLE IF EXISTS` 基线清单里
      ⇒ 整份重导**不清空轨迹**（它是过程记录，不是种子；同 `cart` 的口径）。列结构变更走独立 Migration。
   ⚠️ 回滚（规则 v2：逻辑废弃，**不 DROP**）：7 列 = 保留列 + 停止写入；新表 = 保留表 + 停止写入。
      登记见 `docs/agent/db-migration.md` #10。
   ===================================================================== */

-- ---- ① `order` 加 7 列（A1 修正版）----
-- 规则 7：加列一律**表末** + 默认值（NULL）+ COMMENT。
--   TradeNo / PaymentMethod / PaidAt       —— 付款路径写（OrderStateMachine.detailUpdateSql(PAY)）
--   ExpressCompany / TrackingNo / ShippedAt —— 发货路径写（detailUpdateSql(DELIVER)）
--   ReceivedAt                              —— 收货路径写（detailUpdateSql(RECEIVE)，= NOW()）
-- ⚠️ ReceivedAt 是**订单**收货时刻（买家点「收货」），**非**物流签收
--    —— 调度器那条 status=delivered 的轨迹**不写本列**（语义 gap 见 tech-debt）。
-- ⚠️ 这些列**不进 CAS 的 SET 子句**（updateSql 被 golden copy 逐字符钉死）⇒ 走「同锁内、CAS 命中后」的
--    第二条明细 UPDATE（detailUpdateSql）。理由见 `_work/c3-block4-patch-landing-plan.md §2.1-2.2`。
SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `TradeNo` varchar(64) DEFAULT NULL COMMENT ''支付流水号（PAY+yyyyMMdd+6位随机；模拟网关）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'TradeNo');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `PaymentMethod` varchar(32) DEFAULT NULL COMMENT ''支付方式（alipay/wechat/card；管理员代付=ADMIN）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'PaymentMethod');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `PaidAt` datetime DEFAULT NULL COMMENT ''付款时刻（明细 UPDATE，非 SettlementDate）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'PaidAt');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `ExpressCompany` varchar(64) DEFAULT NULL COMMENT ''快递公司（模拟）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'ExpressCompany');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `TrackingNo` varchar(64) DEFAULT NULL COMMENT ''运单号（模拟）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'TrackingNo');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `ShippedAt` datetime DEFAULT NULL COMMENT ''发货时刻（明细 UPDATE，非 SettlementDate）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'ShippedAt');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `ReceivedAt` datetime DEFAULT NULL COMMENT ''订单收货时刻（买家点收货；非物流签收）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'ReceivedAt');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;

-- ---- ② 新表 `order_shipping_track`（模拟物流轨迹，A2）----
-- 规则 8 满足（InnoDB / 主键 / created_at + updated_at）。
-- ⚠️ **不设外键**：`order` 是全量重导会 `DROP` 的基线表，外键会挡重建（逐字理由见 OrderTimelineWriter 类注释）。
-- ⚠️ `order` 表**自身** `OrderId` 上无索引（既有 Schema），本表自带 `(OrderId, TrackedAt)` —— 查一单的轨迹正是这个形状。
-- ⚠️ `TrackedAt`（节点**发生**时刻，= ShippedAt + 阈值偏移）与 `created_at`（**落库**时刻）**刻意分开**：
--    调度器在阈值被越过后的某一 tick 才补写，两者之差 = 调度延迟（同 order_state_transition.OccurredAt 的理由）。
-- ⚠️ `Status` 取值（有界，4 个，**按时序**）：in_transit（运输中，+15s）/ picked_up（驿站已揽收，+30s）/ out_for_delivery（驿站派送中，+45s）/ delivered（已签收，+60s）—— 四节点**全由调度器**按 `ShippedAt` 偏移补（2026-10-10 前首条 picked_up 在发货时写，已废）。
--    ⚠️ delivered **不**翻 order.ReceiveIf —— 「物流签收 ≠ 订单收货」，前端分两词表述（见 tech-debt）。
CREATE TABLE IF NOT EXISTS `order_shipping_track` (
  `Id`          int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `OrderId`     varchar(40) NOT NULL COMMENT '订单号（对齐 order.OrderId；不设外键）',
  `Status`      varchar(24) NOT NULL COMMENT '轨迹节点码：picked_up / in_transit / out_for_delivery / delivered',
  `Description` varchar(255) DEFAULT NULL COMMENT '节点文案（运输中 / 驿站已揽收 / 驿站派送中 / 已签收，2026-10-11 前两步对调）',
  `TrackedAt`   datetime(3) NOT NULL COMMENT '节点发生时刻（按 ShippedAt 偏移算，非落库时刻）',
  `created_at`  datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行落库时刻（agent.md 数据库管理规则 8）',
  `updated_at`  datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '行更新时刻',
  PRIMARY KEY (`Id`),
  KEY `idx_order_shipping_track_order` (`OrderId`,`TrackedAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单物流轨迹（C3 块4 补丁 A2；模拟物流，调度器只追加行）';

-- ============================================================================
-- 【C3 块4 补丁 · 第 2 笔（2026-10-10）】订单超时未付款自动取消（A 档，用户裁「批全档」）
-- ============================================================================
-- ⚠️ 为什么必须**加列**，不能复用三位状态位：取消的单是 `PayIf=0, DeliveryIf=0, ReceiveIf=0`
--    —— 与「未付款」**三位完全相同** ⇒ 三位表达不出第 4 态，前端也分不出「未付款」与「已取消」。
-- ⚠️ **只加列、不加表** ⇒ 不动 P3 表数门禁（`ConcurrencyRunner` 的 11+6=17）、不动 P4 种子门禁。
-- ⚠️ 阈值（默认 60 秒）与扫描间隔（默认 15 秒）走 properties（`mall.order.cancel.timeout-ms` /
--    `.fixed-delay-ms`），**不入库** —— 演示口径与生产口径只差配置（同物流轨迹四节点的做法）。
-- ⚠️ 时刻由**库时钟**产生（`NOW(3)` / `DATE_SUB(NOW(), …)`），Java 侧不构造任何时间点
--    （2026-10-10 时区统一口径，见 tech-debt.md 的 TD-32）。
-- ⚠️ 幂等：守卫式 `ALTER`（MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，同本文件既有做法）；重跑为 `DO 0`。
SET @s := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `order` ADD COLUMN `CancelledAt` datetime(3) DEFAULT NULL COMMENT ''订单取消时刻（超时未付款，调度器 CAS 写；NULL = 未取消）''', 'DO 0')
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order' AND COLUMN_NAME = 'CancelledAt');
PREPARE _p FROM @s; EXECUTE _p; DEALLOCATE PREPARE _p;
