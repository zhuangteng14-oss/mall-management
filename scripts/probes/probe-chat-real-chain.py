#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-real-chain.py —— 真实链路顺序 10 次（2026-10-24 DeepSeek 迁移回归）。

只读：登录商家账号，顺序调用真实 `POST /chat/ask`，不改任何业务状态。
默认问句「怎么前往商家工作台」、角色 2、语义路由开启。
判据：HTTP 200 10/10；`targetPath=/merchant/user` 10/10；LLM 超时 0/10。

凭据只从 `_work/demo-pw.txt` 读，不打印、不落盘。
产物（docs/agent/evidence/chat-deepseek-migration/）：real-chain-10.json
"""
import argparse
import json
import re
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://localhost:8088"
SENTENCE = "怎么前往商家工作台"
ACCOUNT = "chenguang_parts"
OUT = ROOT / "docs/agent/evidence/chat-deepseek-migration"
LOG = ROOT / "_work/migration/logs-dev-semantic-on-2/mall-management.log"
TRACE_RE = re.compile(r"CHAT_TRACE\s+(.*)$")


def read_password(account, path):
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"^([^|\s]+)\s*\|\s*(.+?)\s*$", line)
        if m and m.group(1) == account:
            return m.group(2)
    raise RuntimeError("账号不在凭据文件里：%s" % account)


def login(session, account, password):
    r = session.post(BASE + "/user/loginSelect",
                     data={"username": account, "password": password}, timeout=15)
    r.raise_for_status()
    role = r.text.strip()
    if role != "2":
        raise RuntimeError("登录 %s 失败：HTTP %s / %r" % (account, r.status_code, r.text[:80]))
    return role


def trace_fields(trace_id, log_path, timeout=5.0):
    """短轮询后端日志，取同一 traceId 的 CHAT_TRACE 字段。"""
    if not trace_id or not Path(log_path).exists():
        return {}
    deadline = time.time() + timeout
    while time.time() < deadline:
        text = Path(log_path).read_bytes().decode("gbk", errors="replace")
        for line in text.splitlines():
            if trace_id not in line:
                continue
            m = TRACE_RE.search(line)
            if not m:
                continue
            fields = {}
            for token in m.group(1).split():
                if "=" in token:
                    k, v = token.split("=", 1)
                    fields[k] = v
            return fields
        time.sleep(0.15)
    return {}


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--sentence", default=SENTENCE)
    ap.add_argument("--account", default=ACCOUNT)
    ap.add_argument("--cred-file", default=str(ROOT / "_work/demo-pw.txt"))
    ap.add_argument("--log", default=str(LOG))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--repeats", type=int, default=10)
    args = ap.parse_args()

    BASE = args.base
    password = read_password(args.account, args.cred_file)
    session = requests.Session()
    login(session, args.account, password)
    print("登录成功：%s→role=2" % args.account)

    rows = []
    for n in range(1, args.repeats + 1):
        started = time.time()
        r = session.post(BASE + "/chat/ask",
                         data={"Message": args.sentence, "CurrentPath": "/merchant"},
                         timeout=60)
        wall_ms = round((time.time() - started) * 1000)
        body = r.json() if r.headers.get("Content-Type", "").startswith("application/json") else {}
        action = body.get("action") if isinstance(body.get("action"), dict) else {}
        trace = trace_fields(r.headers.get("X-Trace-Id", ""), args.log)
        timeout_type = trace.get("llmTimeoutType", "-")
        row = {
            "n": n,
            "http": r.status_code,
            "wallMs": wall_ms,
            "intent": body.get("intent", ""),
            "actionType": action.get("type", body.get("actionType", "")),
            "targetPath": action.get("targetPath", body.get("targetPath", "")),
            "reply": body.get("reply", ""),
            "llmLatencyMs": trace.get("llmLatencyMs", ""),
            "llmTimeoutType": timeout_type,
            "llmFallback": trace.get("llmFallback", ""),
        }
        rows.append(row)
        print("run=%2d http=%s wall=%5dms intent=%-18s target=%-20s timeout=%s" % (
            n, row["http"], wall_ms, row["intent"], row["targetPath"], timeout_type))
        time.sleep(0.4)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = {
        "runs": len(rows),
        "http200": sum(1 for x in rows if x["http"] == 200),
        "intentMerchantDashboard": sum(1 for x in rows if x["intent"] == "MERCHANT_DASHBOARD"),
        "targetMerchantUser": sum(1 for x in rows if x["targetPath"] == "/merchant/user"),
        "llmTimeouts": sum(1 for x in rows
                           if x["llmTimeoutType"] in ("connect_timeout", "request_timeout")),
        "wallMsList": [x["wallMs"] for x in rows],
        "pass": (all(x["http"] == 200 for x in rows)
                 and all(x["targetPath"] == "/merchant/user" for x in rows)
                 and not any(x["llmTimeoutType"] in ("connect_timeout", "request_timeout")
                             for x in rows)),
    }
    payload = {"summary": summary, "rows": rows}
    (out_dir / "real-chain-10.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
