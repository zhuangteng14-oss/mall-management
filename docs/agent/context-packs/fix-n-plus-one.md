# context-pack: fix-n-plus-one

## 任务

修复 4 处 N+1 查询

## 必读文档

- docs/agent/project-map.md §6
- docs/agent/db-schema.md

## 必读源码

- Mall-management-server/src/main/java/com/minke/mallmanagement_controller/MallManagementGoodsController.java:160-210
- Mall-management-server/src/main/java/com/minke/mallmanagement_controller/MallManagementMessage.java:30-70、236-370

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
