# Air vs Flash 尾延迟（单句 50 次）

句子：`怎么前往商家工作台`；Prompt 4414 chars；目标每模型 50 次；nearest-rank 分位；分位数只统计 HTTP 200 的有效样本。

| 指标 | glm-4-air | glm-4-flash |
|---|---:|---:|
| n | 50 | 50 |
| n_valid | 50 | 50 |
| ok | 50 | 50 |
| http_error | 0 | 0 |
| conn_error | 0 | 0 |
| exception | 0 | 0 |
| timeout | 0 | 0 |
| would_timeout_3s | 0 | 19 |
| over_3s | 0 | 19 |
| over_5s | 0 | 2 |
| min | 919 | 1789 |
| p50 | 1092 | 2826 |
| p95 | 1706 | 4751 |
| p99 | 1887 | 5743 |
| max | 1887 | 5743 |
| mean | 1146.0 | 3046.9 |

**判定：** 判据外：Air 0/50、Flash 19/50，数据可区分（不是「样本不足」），但三条判据只预设了「Flash 更好或相近」，未覆盖「Flash 明显更差」；是否切换由用户裁决

> `would_timeout_3s` = HTTP 200 但 wall >3000ms（生产 3s 硬上限下会被切断）；`over_3s` = would_timeout_3s + 真正的客户端超时；`conn_error` = 连接未建立，不属于模型延迟，已从分位数剔除。
