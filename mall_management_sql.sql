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
