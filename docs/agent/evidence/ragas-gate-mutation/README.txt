RAGAS 门禁变异验收（故意改错一条 FAQ）
=====================================

变异位置：FaqKnowledgeBase 「运费」条目（唯一改动行）
  原答案：运费按订单金额算：满99元包邮；不满99元邮费由买家承担，偏远地区除外。
  变异后：运费一律 20 元，所有订单都不包邮，偏远地区也一样。

时间线（2026-10-26，+0800）：
  10:37  改错 → mvnw compile → dev 后端重启
  10:38:06  gate.py 采集 answers.csv（Z07/Z08/Z11 回答均为 20 元）
  10:39:35  summary.json 落盘：平均 F=0.8483、AR=0.8345、nan=0、exit=1
  10:39:38  恢复原答案并重新编译（此后 FaqKnowledgeBase.java 的 mtime 晚于本目录产物）

判据：
  - 金额硬闸：expected ['99','99'] vs answer ['20'] ⇒ Z07/Z08/Z11 三条 policy_fact_errors
  - RAGAS：F 0.8483 < 0.85（3 次：0.8462 / 0.8558 / 0.8429）；AR 0.8345 ≥ 0.80
  - 任一条不满足 ⇒ 退出码 1（实测 exit=1）

对照：恢复正确政策后 gate.py --runs 3 的平均 F=0.9248、AR=0.8415、nan=0、policy_fact_errors=[]、exit=0
（见 ../ragas-gate/summary.json）。
