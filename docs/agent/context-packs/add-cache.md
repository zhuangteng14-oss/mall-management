# context-pack: add-cache {#pack-add-cache-context-pack}

> ⚠️ **本文已作废（2026-09-27）**：B1 方案已由
> `cache-layer.md` 取代。本文所述「先做 Caffeine、
> 多实例才上 Redis」与 B1 裁决的两级缓存
> （Caffeine + Redis 同时）冲突。
> 保留本文仅为历史上下文，**不要按本文实施**。
> 权威方案见 `cache-layer.md`。

## 任务 {#pack-add-cache-s2}

引入缓存层（从零开始）

## 必读文档 {#pack-add-cache-s3}

- docs/agent/cache-map.md（全文）
- docs/agent/db-schema.md

## 必读源码 {#pack-add-cache-s4}

- 所有 Controller 的只读查询方法

## 约束 {#pack-add-cache-s5}

- 先做 Caffeine 本地缓存
- 后续多实例才上 Redis
- 不缓存高频变化数据

## 验收标准 {#pack-add-cache-s6}

- 字典类接口命中缓存
- 写接口正确 evict
- 旧接口行为不变

## 相关 TD / BD {#pack-add-cache-td}

- 无
