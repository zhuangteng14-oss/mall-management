# context-pack: add-distributed-lock

## 任务

引入分布式锁

## 必读文档

- docs/agent/concurrency-map.md
- docs/agent/cache-map.md

## 必读源码

- `order/controller/OrderController.java` 与 `order/service/OrderService.java` 的 order 系列方法（2026-10-02 笔 2 之前，这些都挂在 `mallmanagement_controller/MallManagementGoodsController.java` 里）

## 约束

- 引 Redisson 依赖（需人工授权）
- tryLock(waitTime, leaseTime)
- finally 判断 isHeldByCurrentThread

## 验收标准

- 重复下单被拦截
- 旧接口行为不变

## 相关 TD / BD

- 无
