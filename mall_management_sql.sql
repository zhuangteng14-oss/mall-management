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
