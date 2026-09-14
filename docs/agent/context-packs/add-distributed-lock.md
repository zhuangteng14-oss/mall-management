# context-pack: add-distributed-lock

## 任务

引入分布式锁

## 必读文档

- docs/agent/concurrency-map.md
- docs/agent/cache-map.md

## 必读源码

- MallManagementGoodsController.java 的 order 系列方法

## 约束

- 引 Redisson 依赖（需人工授权）
- tryLock(waitTime, leaseTime)
- finally 判断 isHeldByCurrentThread

## 验收标准

- 重复下单被拦截
- 旧接口行为不变

## 相关 TD / BD

- 无
