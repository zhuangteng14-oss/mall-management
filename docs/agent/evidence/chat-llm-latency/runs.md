# LLM 路径 10 次耗时（怎么前往商家工作台）

| run | wallMs | llmLatencyMs | timeout | timeoutType | callOutcome | inputTokens | outputTokens | requestId | llmFallback | intent | finalBranch | promptChars | estTokens |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3050 | 3030 | true | request_timeout | timeout | - | - | - | llm_error:LlmException | UNKNOWN | switch:UNKNOWN | 4414 | 3263.5 |
| 2 | 3026 | 3009 | true | request_timeout | timeout | - | - | - | llm_error:LlmException | UNKNOWN | switch:UNKNOWN | 4414 | 3263.5 |
| 3 | 2047 | 2046 | false | none | ok | 2157 | 27 | fc830b57-e7b1-40bb-a2f8-3306d125edae | none | GUIDE_NAVIGATION | tool:navigate | 4414 | 3263.5 |
| 4 | 2276 | 2263 | false | none | ok | 2157 | 27 | 6a51b797-2a02-4607-beba-ef4827ba463e | none | GUIDE_NAVIGATION | tool:navigate | 4414 | 3263.5 |
| 5 | 2001 | 1991 | false | none | ok | 2157 | 27 | 3fca8249-bc7e-41b2-92b9-401dae347959 | none | GUIDE_NAVIGATION | tool:navigate | 4414 | 3263.5 |
| 6 | 2369 | 2356 | false | none | ok | 2157 | 27 | 9e44f107-4254-434e-a8d4-4a08c8287f8b | none | GUIDE_NAVIGATION | tool:navigate | 4414 | 3263.5 |
| 7 | 1841 | 1827 | false | none | ok | 2157 | 27 | 0fbd4eb5-c3b3-485d-b2dd-6cbd018c0c6c | none | GUIDE_NAVIGATION | tool:navigate | 4414 | 3263.5 |
| 8 | 2276 | 2260 | false | none | ok | 2157 | 27 | 3b8992b1-483d-4761-9450-43a7ad638643 | none | GUIDE_NAVIGATION | tool:navigate | 4414 | 3263.5 |
| 9 | 3019 | 3002 | true | request_timeout | timeout | - | - | - | llm_error:LlmException | UNKNOWN | switch:UNKNOWN | 4414 | 3263.5 |
| 10 | 3031 | 3008 | true | request_timeout | timeout | - | - | - | llm_error:LlmException | UNKNOWN | switch:UNKNOWN | 4414 | 3263.5 |
