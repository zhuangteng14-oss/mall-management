#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-llm-latency.py —— LLM 意图路径 10 次耗时诊断（2026-10-24）。

前置：后端以 `CHAT_EMBEDDING_ENABLED=false` 启动（语义路由关闭），LLM 才会真正被调用。
只读：登录商家 2222，重复调用真实 `POST /chat/ask`，不改任何业务状态。

每条请求按响应头 `X-Trace-Id` 回捞三行诊断日志：
  · CHAT_TRACE —— 编排层字段（含 llmLatencyMs / llmTimeoutType）
  · LLM_CALL   —— 出网耗时 / 超时类型 / usage / x-request-id
  · LLM_PROMPT —— System Prompt 的字符构成与估算 token

产物（docs/agent/evidence/chat-llm-latency/）：
  runs.json / runs.csv / runs.md
"""
import argparse
import csv
import json
import re
import sys
import time
from pathlib import Path

import requests

BASE = "http://localhost:8088"
LOG = Path("_work/chat-baseline/logs/mall-management.log")
OUT = Path("docs/agent/evidence/chat-llm-latency")
SENTENCE = "怎么前往商家工作台"
REPEATS = 10
TRACE_RE = re.compile(r"(CHAT_TRACE|LLM_CALL|LLM_PROMPT)\s+(.*)$")


def login(session, username, password):
    r = session.post(BASE + "/user/loginSelect",
                     data={"username": username, "password": password}, timeout=15)
    r.raise_for_status()
    role = r.text.strip()
    if role != "2":
        raise RuntimeError("登录 %s 失败：HTTP %s / %r" % (username, r.status_code, r.text[:80]))
    return role


def parse_line(line):
    m = TRACE_RE.search(line)
    if not m:
        return None, {}
    out = {}
    for token in m.group(2).split():
        if "=" in token:
            k, v = token.split("=", 1)
            out[k] = v
    return m.group(1), out


def find_lines(trace_id, timeout=5.0):
    """短轮询日志文件，返回 {marker: fields}。同一 trace 每个 marker 只取第一行。"""
    deadline = time.time() + timeout
    found = {}
    while time.time() < deadline:
        try:
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if trace_id not in line:
                        continue
                    marker, fields = parse_line(line)
                    if marker and marker not in found:
                        found[marker] = fields
            if "CHAT_TRACE" in found and "LLM_CALL" in found:
                return found
        except FileNotFoundError:
            pass
        time.sleep(0.15)
    return found


def main():
    global BASE, LOG
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=str(LOG))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--sentence", default=SENTENCE)
    ap.add_argument("--repeats", type=int, default=REPEATS)
    args = ap.parse_args()

    BASE = args.base
    LOG = Path(args.log)
    log_path = LOG
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    if not log_path.exists():
        print("找不到后端文件日志：%s" % log_path, file=sys.stderr)
        return 2

    session = requests.Session()
    login(session, "2222", "2222")
    print("登录成功：2222→role=2")

    rows = []
    raw = []
    for n in range(1, args.repeats + 1):
        started = time.time()
        r = session.post(BASE + "/chat/ask",
                         data={"Message": args.sentence, "CurrentPath": "/merchant"},
                         timeout=60)
        wall_ms = round((time.time() - started) * 1000)
        caller = r.headers.get("X-Trace-Id", "")
        body = r.json()
        lines = find_lines(caller) if caller else {}
        trace = lines.get("CHAT_TRACE", {})
        call = lines.get("LLM_CALL", {})
        prompt = lines.get("LLM_PROMPT", {})
        timeout_type = trace.get("llmTimeoutType", call.get("cause", "-"))
        row = {
            "run": n,
            "sentence": args.sentence,
            "http": r.status_code,
            "wallMs": wall_ms,
            "traceId": caller,
            "llmUsed": trace.get("llmUsed", ""),
            "llmLatencyMs": trace.get("llmLatencyMs", call.get("latencyMs", "")),
            "timeout": "true" if timeout_type in ("connect_timeout", "request_timeout") else "false",
            "timeoutType": timeout_type,
            "llmFallback": trace.get("llmFallback", ""),
            "intent": trace.get("intent", body.get("intent", "")),
            "finalBranch": trace.get("finalBranch", ""),
            "degradeReason": trace.get("degradeReason", ""),
            "callOutcome": call.get("outcome", ""),
            "callLatencyMs": call.get("latencyMs", ""),
            "inputTokens": call.get("inputTokens", ""),
            "outputTokens": call.get("outputTokens", ""),
            "requestId": call.get("requestId", ""),
            "promptChars": prompt.get("chars", ""),
            "baseChars": prompt.get("baseChars", ""),
            "routeChars": prompt.get("routeChars", ""),
            "toolChars": prompt.get("toolChars", ""),
            "outputChars": prompt.get("outputChars", ""),
            "pathChars": prompt.get("pathChars", ""),
            "estTokens": prompt.get("estTokens", ""),
            "reply": body.get("reply", ""),
        }
        rows.append(row)
        raw.append({"row": row, "trace": trace, "call": call, "prompt": prompt, "body": body})
        print("run=%2d wall=%5sms llm=%5sms timeout=%-5s type=%-15s fb=%-28s intent=%-12s" % (
            n, row["wallMs"], row["llmLatencyMs"], row["timeout"], row["timeoutType"],
            row["llmFallback"], row["intent"]))
        time.sleep(0.4)

    with open(out_dir / "runs.json", "w", encoding="utf-8") as f:
        json.dump(raw, f, ensure_ascii=False, indent=2)
    with open(out_dir / "runs.csv", "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    cols = ["run", "wallMs", "llmLatencyMs", "timeout", "timeoutType", "callOutcome",
            "inputTokens", "outputTokens", "requestId", "llmFallback", "intent", "finalBranch",
            "promptChars", "estTokens"]
    with open(out_dir / "runs.md", "w", encoding="utf-8") as f:
        f.write("# LLM 路径 10 次耗时（%s）\n\n" % args.sentence)
        f.write("| " + " | ".join(cols) + " |\n")
        f.write("|" + "|".join(["---"] * len(cols)) + "|\n")
        for r in rows:
            f.write("| " + " | ".join(str(r.get(c, "")) for c in cols) + " |\n")
    print("写出 %d 行 → %s" % (len(rows), out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
