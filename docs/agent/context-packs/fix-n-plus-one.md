# context-pack: fix-n-plus-one

## 任务

修复 4 处 N+1 查询

## 必读文档

- docs/agent/project-map.md §6
- docs/agent/db-schema.md

## 必读源码

- Mall-management-server/src/main/java/com/minke/mallmanagement/goods/service/GoodsCacheService.java —— **`goodsSelectHome` 的 N+1 现址**：`listHome()` `:67`（外层全量查 goods + 循环内逐条查 login；⚠️ 本笔要改的就是它）。⚠️ **原指针 `goods/controller/GoodsController.java`（当时 `mallmanagement_controller/MallManagementGoodsController.java:160-210`）已失效** —— 该读路径早在 2026-09-29 B1 缓存笔就搬到 `GoodsCacheService`；2026-10-02 三层第二笔又把 Controller 的方法体搬进 `GoodsService`，`GoodsController:101-104` 现在只剩路由与一行委托。
- Mall-management-server/src/main/java/com/minke/mallmanagement/message/service/FriendCacheService.java —— **两段 friend 读路径的 N+1 现址**：`listFriends()` 的逐行 enrich 循环 `:95-97`、`listRequests()` 的 `:123-125`（两处都调私有 `userInfoById:150`，每次读**三张表**）。⚠️ **原指针 `message/controller/MessageController.java`（当时 `mallmanagement_controller/MallManagementMessage.java:30-70、236-370`）已失效** —— 两段 friend 读路径早在 2026-09-29 B1 缓存笔就搬进 `FriendCacheService`；2026-10-03 三层第四笔（message 域，`8ed5fc1`）又把 `MessageController` 由 417 行重写为 **127 行**（只剩路由，文件内**已无 SQL、无业务体**）。

## 约束

- 不改接口签名、返回值、路径
- 不改数据库
- 不引新依赖

## 验收标准

- 4 处 N+1 全部改写为 JOIN 或批量 IN
- SQL 次数不再随数据量线性增长
- 旧接口行为不变

## 相关 TD / BD

- 无
