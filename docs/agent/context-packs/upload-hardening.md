# context-pack: upload-hardening

## 任务

处理上传相关技术债

## 必读文档

- docs/agent/tech-debt.md 的 TD-06、TD-07、TD-08、TD-09

## 必读源码

- Mall-management-server/src/main/java/com/minke/mallmanagement/shared/upload/ImgPath.java
- Mall-management-server/src/main/java/com/minke/mallmanagement/user/controller/UserController.java（当时 `mallmanagement_controller/MallManagementUserController.java:150-200`）
- Mall-management-server/src/main/java/com/minke/mallmanagement/goods/controller/GoodsController.java（当时 `mallmanagement_controller/MallManagementGoodsController.java:70-115、270-305`）

## 约束

- 每次只处理一个 TD，不要一次全改
- 保持接口签名不变

## 验收标准

- 对应 TD 关闭
- 旧接口行为不变

## 相关 TD / BD

- TD-06、TD-07、TD-08、TD-09
