#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-llm-only-3.py —— 纯 LLM（Embedding 关闭）三条导航基线。

前置：后端以 `CHAT_EMBEDDING_ENABLED=false` 启动（语义路由不可用）。
只读：登录商家账号，顺序调用真实 `POST /chat/ask`。

口径（2026-10-24 裁决 B）：
  · 不要求模型产出三个语义路由专用意图名；
  · 接受 `GUIDE_NAVIGATION` + 正确 `targetPath` 作为降级基线；
  · 同时记录 `dedicated_intent_ok`，供报告如实说明「0/3」。
凭据只从 `_work/demo-pw.txt` 读，不打印、不落盘。

产物（docs/agent/evidence/chat-deepseek-migration/）：llm-only-3.json
"""
import argparse
import json
import re
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://localhost:8088"
ACCOUNT = "chenguang_parts"
OUT = ROOT / "docs/agent/evidence/chat-deepseek-migration"
CASES = [
    ("怎么前往商家工作台", "MERCHANT_DASHBOARD", "/merchant/user"),
    ("怎么看我上架的商品", "GOODS_MANAGE", "/merchant/user/list"),
    ("我要管理我的钱", "ASSET_QUERY", "/merchant/user/member"),
]


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
    if r.text.strip() != "2":
        raise RuntimeError("登录 %s 失败：HTTP %s / %r" % (account, r.status_code, r.text[:80]))


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--account", default=ACCOUNT)
    ap.add_argument("--cred-file", default=str(ROOT / "_work/demo-pw.txt"))
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args()

    BASE = args.base
    session = requests.Session()
    login(session, args.account, read_password(args.account, args.cred_file))
    print("登录成功：%s→role=2" % args.account)

    rows = []
    for question, expected_intent, expected_path in CASES:
        started = time.time()
        r = session.post(BASE + "/chat/ask",
                         data={"Message": question, "CurrentPath": "/merchant"},
                         timeout=60)
        wall_ms = round((time.time() - started) * 1000)
        body = r.json() if r.headers.get("Content-Type", "").startswith("application/json") else {}
        action = body.get("action") if isinstance(body.get("action"), dict) else {}
        row = {
            "q": question,
            "http": r.status_code,
            "wallMs": wall_ms,
            "intent": body.get("intent", ""),
            "actionType": action.get("type", body.get("actionType", "")),
            "targetPath": action.get("targetPath", body.get("targetPath", "")),
            "reply": body.get("reply", ""),
            "expectedIntent": expected_intent,
            "expectedPath": expected_path,
            "dedicatedIntentOk": body.get("intent") == expected_intent,
            "pathOk": action.get("targetPath", body.get("targetPath", "")) == expected_path,
        }
        rows.append(row)
        print("%-12s http=%s %5dms intent=%-18s path=%-20s dedicated=%s path_ok=%s" % (
            question, row["http"], wall_ms, row["intent"], row["targetPath"],
            row["dedicatedIntentOk"], row["pathOk"]))
        time.sleep(0.4)

    summary = {
        "runs": len(rows),
        "http200": sum(1 for x in rows if x["http"] == 200),
        "dedicatedIntentOk": sum(1 for x in rows if x["dedicatedIntentOk"]),
        "pathOk": sum(1 for x in rows if x["pathOk"]),
        "pass": (all(x["http"] == 200 for x in rows)
                 and all(x["pathOk"] for x in rows)),
    }
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = {"summary": summary, "rows": rows}
    (out_dir / "llm-only-3.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
