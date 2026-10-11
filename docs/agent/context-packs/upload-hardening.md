# context-pack: upload-hardening {#pack-upload-hardening-context-pack}

## 任务 {#pack-upload-hardening-s2}

处理上传相关技术债

## 必读文档 {#pack-upload-hardening-s3}

- docs/agent/tech-debt.md 的 TD-06、TD-07、TD-08、TD-09

## 必读源码 {#pack-upload-hardening-s4}

- Mall-management-server/src/main/java/com/minke/mallmanagement/shared/upload/ImgPath.java
- Mall-management-server/src/main/java/com/minke/mallmanagement/user/controller/UserController.java（当时 `mallmanagement_controller/MallManagementUserController.java:150-200`）
- Mall-management-server/src/main/java/com/minke/mallmanagement/goods/service/GoodsService.java —— **上传处理的现址**：`goodsAdd` 的落盘段 `:96-117`（`file.isEmpty()` 早退 `:99`，`UploadStorage.store` `:109`）、`goodsUp` 的落盘段 `:265-281`（判据 `file != null && !file.isEmpty()` `:268`，`store` `:272`）。⚠️ **原指针 `goods/controller/GoodsController.java`（当时 `mallmanagement_controller/MallManagementGoodsController.java:70-115、270-305`）已失效** —— 2026-10-02 三层第二笔把商品 8 个方法体搬到 `GoodsService`，Controller 只剩路由与入参绑定，那里**没有任何上传代码**了。

## 约束 {#pack-upload-hardening-s5}

- 每次只处理一个 TD，不要一次全改
- 保持接口签名不变

## 验收标准 {#pack-upload-hardening-s6}

- 对应 TD 关闭
- 旧接口行为不变

## 相关 TD / BD {#pack-upload-hardening-td}

- TD-06、TD-07、TD-08、TD-09
