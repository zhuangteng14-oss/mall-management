# context-pack: monitoring {#pack-monitoring-context-pack}

## 任务 {#pack-monitoring-s2}

引入指标和实时日志

## 必读文档 {#pack-monitoring-s3}

- docs/agent/project-map.md
- docs/agent/concurrency-map.md §7（缺失清单）

## 必读源码 {#pack-monitoring-s4}

- 所有 Controller

## 约束 {#pack-monitoring-s5}

- Micrometer + Actuator
- 日志用 Logback JSON
- SSE 推送到前端

## 验收标准 {#pack-monitoring-s6}

- /actuator/metrics 可用
- 关键操作有指标
- 旧接口行为不变

## 相关 TD / BD {#pack-monitoring-td}

- 无
