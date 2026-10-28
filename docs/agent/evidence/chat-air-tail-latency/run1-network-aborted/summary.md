# Air vs Flash 尾延迟（单句 50 次）

句子：`怎么前往商家工作台`；Prompt 4414 chars；每模型 50 次；nearest-rank 分位。

| 指标 | glm-4-air | glm-4-flash |
|---|---:|---:|
| ok | 17 | 16 |
| http_error | 0 | 0 |
| timeout | 0 | 1 |
| would_timeout_3s | 1 | 11 |
| over_3s | 1 | 12 |
| over_5s | 1 | 11 |
| min | 1064 | 1979 |
| p50 | 1255 | 3509 |
| p95 | 3556 | 27859 |
| p99 | 3556 | 27859 |
| max | 3556 | 27859 |
| mean | 1495.8 | 7088.1 |

**判定：** 样本不足：50 次仍无法区分，建议放宽到 5 秒再评估

> `would_timeout_3s` = HTTP 200 但 wall >3000ms（生产 3s 硬上限下会被切断）；`over_3s` = would_timeout_3s + 真正的 30s 超时。
