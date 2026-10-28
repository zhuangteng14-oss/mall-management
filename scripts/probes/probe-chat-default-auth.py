#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-default-auth.py —— default profile（app.auth.enabled=true）API 链路。

验三条：
  ① 匿名 POST /chat/ask ⇒ 401，响应体是 Result 形状（success=false / msg=未登录）；
  ② 登录客户 1111 发「我的订单到哪了」⇒ 200，正常返回订单；
  ③ 登录商家 2222 发「怎么前往商家工作台」⇒ 200，action.targetPath=/merchant/user。

UI 那条（匿名点客服球显示「登录后才能查询」且不跳登录页）见
scripts/probes/probe-chat-default-auth.mjs。

凭据**只从环境变量读**，不写进报告：
  PROBE_CUSTOMER_PW / PROBE_MERCHANT_PW
用法：
  PROBE_CUSTOMER_PW="$(...)" PROBE_MERCHANT_PW="$(...)" \
    python scripts/probes/probe-chat-default-auth.py
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests

OUT = Path("docs/agent/evidence/chat-default-auth")


def post(session, base, message, current_path=None):
    data = {"Message": message}
    if current_path:
        data["CurrentPath"] = current_path
    started = time.time()
    r = session.post(base + "/chat/ask", data=data, timeout=60)
    return r, round((time.time() - started) * 1000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8088")
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--customer-user", default="1111")
    ap.add_argument("--merchant-user", default="2222")
    args = ap.parse_args()

    customer_pw = os.environ.get("PROBE_CUSTOMER_PW", "")
    merchant_pw = os.environ.get("PROBE_MERCHANT_PW", "")
    if not customer_pw or not merchant_pw:
        print("缺少 PROBE_CUSTOMER_PW / PROBE_MERCHANT_PW", file=sys.stderr)
        return 2

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rows = []

    # ① 匿名
    anon = requests.Session()
    r, ms = post(anon, args.base, "我的订单到哪了", "/customer")
    raw = r.text
    try:
        body = r.json()
    except Exception:
        body = None
    rows.append({
        "case": "anonymous-chat-ask",
        "http": r.status_code,
        "wallMs": ms,
        "body": body,
        "raw": raw,
        "pass": r.status_code == 401 and isinstance(body, dict)
                and body.get("success") is False and body.get("msg") == "未登录",
    })
    (out / "anonymous-401.txt").write_text(
        "HTTP %s\nContent-Type: %s\n\n%s\n" % (
            r.status_code, r.headers.get("Content-Type", ""), raw),
        encoding="utf-8")

    # ② 客户
    c = requests.Session()
    lr = c.post(args.base + "/user/loginSelect",
                data={"username": args.customer_user, "password": customer_pw}, timeout=15)
    r, ms = post(c, args.base, "我的订单到哪了", "/customer")
    body = r.json()
    rows.append({
        "case": "customer-order-query",
        "loginRole": lr.text.strip(),
        "http": r.status_code,
        "wallMs": ms,
        "success": body.get("success"),
        "intent": body.get("intent"),
        "reply": body.get("reply"),
        "dataKeys": list((body.get("data") or {}).keys()),
        "pass": lr.text.strip() == "1" and r.status_code == 200
                and body.get("success") is True
                and body.get("intent") == "ORDER_QUERY"
                and bool(body.get("reply")),
    })

    # ③ 商家
    m = requests.Session()
    lr = m.post(args.base + "/user/loginSelect",
                data={"username": args.merchant_user, "password": merchant_pw}, timeout=15)
    r, ms = post(m, args.base, "怎么前往商家工作台", "/merchant")
    body = r.json()
    action = body.get("action") or {}
    rows.append({
        "case": "merchant-workbench",
        "loginRole": lr.text.strip(),
        "http": r.status_code,
        "wallMs": ms,
        "success": body.get("success"),
        "intent": body.get("intent"),
        "action": action,
        "targetPath": action.get("targetPath"),
        "reply": body.get("reply"),
        "pass": lr.text.strip() == "2" and r.status_code == 200
                and body.get("success") is True
                and action.get("targetPath") == "/merchant/user",
    })

    (out / "api-chains.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = []
    for x in rows:
        lines.append("%-24s HTTP=%s pass=%s targetPath=%s intent=%s" % (
            x["case"], x["http"], x["pass"], x.get("targetPath", "-"),
            x.get("intent", "-")))
    (out / "api-chains.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    for line in lines:
        print(line)
    return 0 if all(x["pass"] for x in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
