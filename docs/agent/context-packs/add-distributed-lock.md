# context-pack: add-distributed-lock {#pack-add-distributed-lock-context-pack}

## 任务 {#pack-add-distributed-lock-s2}

引入分布式锁

## 必读文档 {#pack-add-distributed-lock-s3}

- docs/agent/concurrency-map.md
- docs/agent/cache-map.md

## 必读源码 {#pack-add-distributed-lock-s4}

- `order/controller/OrderController.java` 与 `order/service/OrderService.java` 的 order 系列方法（2026-10-02 笔 2 之前，这些都挂在 `mallmanagement_controller/MallManagementGoodsController.java` 里）

## 约束 {#pack-add-distributed-lock-s5}

- 引 Redisson 依赖（需人工授权）
- tryLock(waitTime, leaseTime)
- finally 判断 isHeldByCurrentThread

## 验收标准 {#pack-add-distributed-lock-s6}

- 重复下单被拦截
- 旧接口行为不变

## 相关 TD / BD {#pack-add-distributed-lock-td}

- 无
