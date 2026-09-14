# context-pack: add-cache

## 任务

引入缓存层（从零开始）

## 必读文档

- docs/agent/cache-map.md（全文）
- docs/agent/db-schema.md

## 必读源码

- 所有 Controller 的只读查询方法

## 约束

- 先做 Caffeine 本地缓存
- 后续多实例才上 Redis
- 不缓存高频变化数据

## 验收标准

- 字典类接口命中缓存
- 写接口正确 evict
- 旧接口行为不变

## 相关 TD / BD

- 无
