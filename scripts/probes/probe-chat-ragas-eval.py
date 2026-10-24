#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-ragas-eval.py —— 用真链给 RAGAS 评测集采集答案（2026-10-24）。

输入：docs/agent/evidence/chat-baseline/ragas-eval-set.csv（问题 + 期望 + contexts）
动作：按每行的 role 登录（1=1111 客户 / 2=2222 商家），逐条调用真实 /chat/ask。
输出：
    ragas-eval-answers.csv —— 原表 + answer / actual_intent / actual_action / trace 字段
    ragas-dataset.json     —— RAGAS 需要的 question / answer / contexts / ground_truth
"""
import csv
import json
import re
import sys
import time
from pathlib import Path

import requests

BASE = "http://localhost:8088"
LOG = Path("_work/chat-baseline/logs/mall-management.log")
OUT = Path("docs/agent/evidence/chat-baseline")
TRACE_RE = re.compile(r"CHAT_TRACE\s+(.*)$")
ACCOUNTS = {1: ("1111", "1111"), 2: ("2222", "2222")}


def parse_trace(line):
    out = {}
    for token in TRACE_RE.search(line).group(1).split():
        if "=" in token:
            k, v = token.split("=", 1)
            out[k] = v
    return out


def find_trace(trace_id, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with open(LOG, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if trace_id in line and "CHAT_TRACE" in line:
                    return parse_trace(line)
        time.sleep(0.15)
    return {}


def main():
    rows = list(csv.DictReader(open(OUT / "ragas-eval-set.csv", encoding="utf-8-sig")))
    sessions = {}
    for role in (1, 2):
        u, p = ACCOUNTS[role]
        s = requests.Session()
        r = s.post(BASE + "/user/loginSelect", data={"username": u, "password": p}, timeout=15)
        role_ret = r.text.strip()
        if role_ret != str(role):
            raise RuntimeError("登录 %s 期望 role=%s，实得 %r" % (u, role, role_ret))
        sessions[role] = s
    print("登录完成：1111→1，2222→2")

    ds = []
    for row in rows:
        role = int(row["role"])
        session = sessions[role]
        current_path = "/merchant" if role == 2 else "/customer"
        started = time.time()
        r = session.post(BASE + "/chat/ask",
                         data={"Message": row["question"], "CurrentPath": current_path},
                         timeout=60)
        wall = round((time.time() - started) * 1000)
        body = r.json()
        trace_id = r.headers.get("X-Trace-Id", "")
        trace = find_trace(trace_id) if trace_id else {}
        action = body.get("action") or {}
        row["answer"] = body.get("reply", "")
        row["actual_intent"] = body.get("intent", "")
        row["actual_action"] = action.get("targetPath", "")
        row["traceId"] = trace_id
        row["wallMs"] = wall
        row["semanticScore"] = trace.get("semanticScore", "")
        row["finalBranch"] = trace.get("finalBranch", "")
        row["degradeReason"] = trace.get("degradeReason", "")
        ds.append({
            "question": row["question"],
            "answer": row["answer"],
            "contexts": [c.strip() for c in row["contexts"].split("||") if c.strip()],
            "ground_truth": row["expected_answer_or_action"],
        })
        print("%-3s %-28s -> %-18s %-22s %s" % (
            row["id"], row["question"], row["actual_intent"],
            row["actual_action"] or "-", row["finalBranch"]))
        time.sleep(0.25)

    fields = list(rows[0].keys())
    with open(OUT / "ragas-eval-answers.csv", "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    with open(OUT / "ragas-dataset.json", "w", encoding="utf-8") as f:
        json.dump(ds, f, ensure_ascii=False, indent=2)
    print("写出 %d 条 → %s" % (len(ds), OUT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
