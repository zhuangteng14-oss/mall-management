#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-cross-domain.py —— 跨域购物意图 + 澄清防复读的真链验收（2026-10-24）。

只读：登录买家 / 商家账号，顺序调用真实 `POST /chat/ask`，不写库。
判据：
  ① 买家 5 条基础购物句 ⇒ GOODS_QUERY + `/customer/search?kw=...`；
  ② 商家 2 条买家行为 ⇒ GOODS_QUERY + `/merchant/search?kw=...`，回复不出现「管理商品」；
  ③ 随机乱码连发两次 ⇒ 第二次换话术、胶囊不含「管理商品 / 工作台」。

凭据只从 `_work/demo-pw.txt` 读，不打印、不落盘。
产物：docs/agent/evidence/chat-cross-domain/probe-result.json + probe-report.txt
"""
import json
import re
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://localhost:8088"
OUT = ROOT / "docs/agent/evidence/chat-cross-domain"
CRED_FILE = ROOT / "_work/demo-pw.txt"

BUYER_CASES = [
    ("B01", "oppo12这商品在哪购买？", "/customer/search?kw=oppo12"),
    ("B02", "我想买个护膝", "/customer/search?kw=%E6%8A%A4%E8%86%9D"),
    ("B03", "怎么搜索蓝牙耳机", "/customer/search?kw=%E8%93%9D%E7%89%99%E8%80%B3%E6%9C%BA"),
    ("B04", "这手机多少钱", "/customer/search?kw=%E6%89%8B%E6%9C%BA"),
]
MERCHANT_CASES = [
    ("M01", "护膝在哪买", "/merchant/search?kw=%E6%8A%A4%E8%86%9D"),
    ("M02", "帮我找一个蓝牙耳机", "/merchant/search?kw=%E8%93%9D%E7%89%99%E8%80%B3%E6%9C%BA"),
]
GIBBERISH = ["qzxvbnmplkjhgf", "wrtpomsdfghjkl"]


def read_creds():
    creds = {}
    for line in CRED_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"^([^|\s]+)\s*\|\s*(.+?)\s*$", line)
        if m and m.group(1) not in ("UserName", "=="):
            creds[m.group(1)] = m.group(2)
    return creds


def login(account, password, expected_role):
    s = requests.Session()
    r = s.post(BASE + "/user/loginSelect",
               data={"username": account, "password": password}, timeout=15)
    r.raise_for_status()
    if r.text.strip() != expected_role:
        raise RuntimeError("%s 登录失败：HTTP %s / role=%r" %
                           (account, r.status_code, r.text[:40]))
    return s


def ask(session, message, current_path):
    r = session.post(BASE + "/chat/ask",
                     data={"Message": message, "CurrentPath": current_path}, timeout=60)
    body = r.json() if r.headers.get("Content-Type", "").startswith("application/json") else {}
    return r.status_code, body


def labels(body):
    out = []
    for item in body.get("suggestions", []) or []:
        out.append(item if isinstance(item, str) else item.get("label", ""))
    return out


def action_path(body):
    action = body.get("action")
    return action.get("targetPath", "") if isinstance(action, dict) else ""


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    creds = read_creds()
    rows = []

    def record(case, question, role, expected, body, extra=None):
        path = action_path(body)
        reply = str(body.get("reply", ""))
        chips = labels(body)
        ok = body.get("intent") == "GOODS_QUERY" and path == expected \
            and "管理商品" not in reply
        row = {
            "id": case, "question": question, "role": role, "expectedIntent": "GOODS_QUERY",
            "expectedPath": expected, "intent": body.get("intent"), "actionPath": path,
            "http": 200, "pass": ok, "reply": reply, "chips": chips,
        }
        if extra:
            row.update(extra)
        rows.append(row)
        print("%s  %s  role=%s intent=%s path=%s" %
              ("PASS" if ok else "FAIL", case, role, body.get("intent"), path))
        return row

    buyer = login("1111", creds["1111"], "1")
    for case, question, expected in BUYER_CASES:
        _, body = ask(buyer, question, "/customer")
        record(case, question, 1, expected, body)

    # B05：随机乱码连发两次 —— 第一次 CLARIFY，第二次必须换话术。
    # 先发一条可记忆的业务句重置上下文，避免上一轮手工探针留下的 CLARIFY 影响判据。
    ask(buyer, "查我的订单", "/customer")
    first_q, second_q = GIBBERISH
    _, first = ask(buyer, first_q, "/customer")
    _, second = ask(buyer, second_q, "/customer")
    first_reply = str(first.get("reply", ""))
    second_reply = str(second.get("reply", ""))
    second_chips = labels(second)
    b05_ok = (first.get("intent") == "CLARIFY"
              and second.get("intent") == "CLARIFY"
              and first_reply != second_reply
              and "我还是没太听懂" in second_reply
              and not any(x in "".join(second_chips) for x in ("管理商品", "工作台", "查我的订单")))
    rows.append({
        "id": "B05", "question": "%s → %s" % (first_q, second_q), "role": 1,
        "expectedIntent": "CLARIFY", "intent": second.get("intent"),
        "firstReply": first_reply, "secondReply": second_reply,
        "secondChips": second_chips, "pass": b05_ok,
    })
    print("%s  B05  role=1  第一次=%s  第二次=%s" %
          ("PASS" if b05_ok else "FAIL", first.get("intent"), second.get("intent")))

    merchant = login("chenguang_parts", creds["chenguang_parts"], "2")
    for case, question, expected in MERCHANT_CASES:
        _, body = ask(merchant, question, "/merchant")
        record(case, question, 2, expected, body)

    passed = sum(1 for x in rows if x["pass"])
    result = {"total": len(rows), "passed": passed, "failed": len(rows) - passed, "rows": rows}
    (OUT / "probe-result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["跨域购物意图 + 澄清防复读真链验收（2026-10-24）",
             "合计 %d 项 / %d 通过 / %d 失败" % (len(rows), passed, len(rows) - passed), ""]
    for row in rows:
        lines.append("%s  %s  %s" % ("PASS" if row["pass"] else "FAIL", row["id"], row["question"]))
        if "secondReply" in row:
            lines.append("  第一次：%s" % row["firstReply"])
            lines.append("  第二次：%s" % row["secondReply"])
            lines.append("  第二次胶囊：%s" % " / ".join(row["secondChips"]))
        else:
            lines.append("  intent=%s  action=%s  胶囊=%s" %
                         (row["intent"], row.get("actionPath"), " / ".join(row.get("chips", []))))
            lines.append("  回复：%s" % row.get("reply", ""))
        lines.append("")
    (OUT / "probe-report.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("合计 %d 项 / %d 通过 / %d 失败" % (len(rows), passed, len(rows) - passed))
    return 0 if passed == len(rows) else 1


if __name__ == "__main__":
    sys.exit(main())
