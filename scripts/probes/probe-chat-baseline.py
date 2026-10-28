#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-baseline.py —— 语义路由 / LLM / 工具链的真链基线（2026-10-24）。

只读：登录（商家 2222 / 客户 1111）后调用真实 `POST /chat/ask`，不 mock 任何一层。
每条请求从响应头 `X-Trace-Id` 拿 traceId，再到后端文件日志里回捞同一条
`CHAT_TRACE key=value …`，从而拿到 semanticScore / finalBranch / degradeReason 等字段。

用法（后端 8088 已起、dev profile）：
    python scripts/probes/probe-chat-baseline.py

产物（默认 docs/agent/evidence/chat-baseline/）：
    runs.json  原始响应 + trace 字段
    runs.csv   扁平表
    runs.md    人读表格
"""
import argparse
import csv
import json
import os
import re
import sys
import time
from pathlib import Path

import requests

BASE = "http://localhost:8088"
DEFAULT_LOG = Path("_work/chat-baseline/logs/mall-management.log")
DEFAULT_OUT = Path("docs/agent/evidence/chat-baseline")

# 用户点名的 7 句，每条跑 3 次（B 组）。
FIXED = [
    "怎么看我上架的商品",
    "怎么前往商家工作台",
    "我花出去的钱去哪了",
    "今天天气不错",
    "怎么处理退款",
    "我的退款进度",
    "直接带我去管理商品",
]
REPEATS = 3

# C 组的扩展固定请求（补齐 FAQ / 问候 / 订单 / 评论 / 商品），各 1 次。
EXTRAS = [
    ("FAQ-policy-return", "拆封了能退货吗", "/merchant"),
    ("FAQ-policy-shipping", "运费怎么算", "/merchant"),
    ("GREETING", "你好", "/merchant"),
    ("ORDER_QUERY", "查我的订单", "/merchant"),
    ("COMMENT_QUERY", "我要看评论", "/merchant"),
    ("GOODS_QUERY", "222:ij-555", "/merchant"),
]

TRACE_RE = re.compile(r"CHAT_TRACE\s+(.*)$")


def login(session, username, password):
    r = session.post(BASE + "/user/loginSelect",
                     data={"username": username, "password": password}, timeout=15)
    r.raise_for_status()
    role = r.text.strip()
    if role not in ("1", "2", "3"):
        raise RuntimeError("登录失败：%s → HTTP %s / body %r" % (username, r.status_code, r.text[:80]))
    return role


def parse_trace(line):
    body = TRACE_RE.search(line).group(1)
    out = {}
    for token in body.split():
        if "=" in token:
            k, v = token.split("=", 1)
            out[k] = v
    return out


def find_trace(log_path, trace_id, timeout=5.0):
    """trace 行在 finally 里同步写盘，正常响应到达时已在文件里；短轮询兜底。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with open(log_path, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if trace_id in line and "CHAT_TRACE" in line:
                        return parse_trace(line), line.strip()
        except FileNotFoundError:
            pass
        time.sleep(0.15)
    return {}, ""


def run_once(session, text, current_path, log_path):
    started = time.time()
    r = session.post(BASE + "/chat/ask",
                     data={"Message": text, "CurrentPath": current_path}, timeout=60)
    elapsed_ms = round((time.time() - started) * 1000)
    trace_id = r.headers.get("X-Trace-Id", "")
    try:
        body = r.json()
    except ValueError:
        body = {"_non_json": r.text[:500]}
    trace, raw_line = find_trace(log_path, trace_id) if trace_id else ({}, "")
    return {
        "http": r.status_code,
        "elapsed_ms": elapsed_ms,
        "traceId": trace_id,
        "trace": trace,
        "trace_line": raw_line,
        "body": body,
    }


def flat_row(group, sentence, run, role, item):
    t = item["trace"]
    body = item["body"]
    return {
        "group": group,
        "sentence": sentence,
        "run": run,
        "role": role,
        "http": item["http"],
        "wallMs": item["elapsed_ms"],
        "traceId": item["traceId"],
        "intent": t.get("intent", body.get("intent", "")),
        "semanticScore": t.get("semanticScore", ""),
        "secondScore": t.get("secondScore", ""),
        "semanticConfident": t.get("semanticConfident", ""),
        "llmUsed": t.get("llmUsed", ""),
        "llmFallbackReason": t.get("llmFallback", ""),
        "toolName": t.get("toolName", ""),
        "toolSuccess": t.get("toolSuccess", ""),
        "totalLatencyMs": t.get("totalLatencyMs", ""),
        "finalBranch": t.get("finalBranch", ""),
        "degradeReason": t.get("degradeReason", ""),
        "actionType": (body.get("action") or {}).get("type", ""),
        "actionTarget": (body.get("action") or {}).get("targetPath", ""),
        "reply": body.get("reply", ""),
    }


def write_md(rows, path):
    cols = ["group", "sentence", "run", "role", "intent", "semanticScore", "secondScore",
            "semanticConfident", "llmUsed", "llmFallbackReason", "toolName", "toolSuccess",
            "totalLatencyMs", "finalBranch", "degradeReason"]
    with open(path, "w", encoding="utf-8") as f:
        f.write("| " + " | ".join(cols) + " |\n")
        f.write("|" + "|".join(["---"] * len(cols)) + "|\n")
        for r in rows:
            f.write("| " + " | ".join(str(r.get(c, "")).replace("|", "\\|") for c in cols) + " |\n")


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=str(DEFAULT_LOG))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--base", default=BASE)
    args = ap.parse_args()

    BASE = args.base
    log_path = Path(args.log)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not log_path.exists():
        print("找不到后端文件日志：%s" % log_path, file=sys.stderr)
        return 2

    merchant = requests.Session()
    customer = requests.Session()
    m_role = login(merchant, "2222", "2222")
    c_role = login(customer, "1111", "1111")
    print("登录成功：2222→role=%s，1111→role=%s" % (m_role, c_role))

    rows = []
    raw = []
    for sentence in FIXED:
        for n in range(1, REPEATS + 1):
            item = run_once(merchant, sentence, "/merchant", log_path)
            row = flat_row("B", sentence, n, 2, item)
            rows.append(row)
            raw.append({"group": "B", "sentence": sentence, "run": n, "role": 2, **item})
            print("[B] %-12s run=%d intent=%-18s sem=%s conf=%s llm=%s branch=%s" % (
                sentence, n, row["intent"], row["semanticScore"],
                row["semanticConfident"], row["llmFallbackReason"], row["finalBranch"]))
            time.sleep(0.25)

    for name, sentence, path in EXTRAS:
        item = run_once(merchant, sentence, path, log_path)
        row = flat_row("C", sentence, 1, 2, item)
        rows.append(row)
        raw.append({"group": "C", "label": name, "sentence": sentence, "run": 1, "role": 2, **item})
        print("[C] %-20s intent=%-18s sem=%s branch=%s" % (
            sentence, row["intent"], row["semanticScore"], row["finalBranch"]))

    # 客户侧补两条：证明买家工具链也能跑（不进 B 的 3 次统计）。
    for sentence in ("我的退款进度", "查我的订单"):
        item = run_once(customer, sentence, "/customer", log_path)
        row = flat_row("C", sentence, 1, 1, item)
        rows.append(row)
        raw.append({"group": "C", "sentence": sentence, "run": 1, "role": 1, **item})
        print("[C-customer] %-12s intent=%-18s branch=%s" % (
            sentence, row["intent"], row["finalBranch"]))

    with open(out_dir / "runs.json", "w", encoding="utf-8") as f:
        json.dump(raw, f, ensure_ascii=False, indent=2)
    with open(out_dir / "runs.csv", "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    write_md(rows, out_dir / "runs.md")
    print("写出 %d 行 → %s" % (len(rows), out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
