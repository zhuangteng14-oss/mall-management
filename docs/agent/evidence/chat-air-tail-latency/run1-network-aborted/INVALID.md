# 本次运行作废（网络中断）

第 19 轮起端点整体不可达，轮 19–50 全部是 `ConnectionError`（5–50ms）。
本目录的 `summary.json` / `summary.md` 由**修复前**的探针生成：连接失败既没从
分位数剔除、也没触发中止，导致 `n=50` 但实际有效样本只有 Air 17、Flash 16，
结论不可用。

保留本目录仅用于记录这次失败与探针缺陷。有效测量见上一级
`runs-50.csv` / `summary.md` / `report.md`。
