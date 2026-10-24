#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-air-ab.py —— Flash vs Air 路由准确率 A/B（2026-10-24）。

只读：
  · 同一份商家 System Prompt（_work/chat-baseline/prompt-merchant.txt，DEBUG 日志提取）
  · 同一批 60 条问题（50 条 golden set + 7 句冷问题 + 3 条边界，保留重复项）
  · 只调用 GLM HTTP API，不碰线上模型、不改 3s 超时、不改 Prompt

评测口径（与生产 LlmIntentClassifier 对齐）：
  · 合法 JSON = 从第一个 { 到最后一个 } 截取后能解析成对象，且 intent 在白名单、
    tool 在商家可用工具集、GUIDE/DIRECT 的 target_path 能按 RouteCatalog 规则解析。
    （生产容忍 markdown 代码块，所以另外单独统计“裸 JSON”合法率。）
  · intent 取工具覆盖后的结果（tool=open_store ⇒ OPEN_STORE，navigate ⇒ GUIDE_NAVIGATION）。
  · target_path 对非导航意图按生产口径记为 null；导航题的期望路径已按商家角色归一
    （/customer/orders → /merchant/orders 等）。
  · OPEN_STORE / ADD_TO_SECKILL 允许用专用工具（open_store / seckill_signup）代替 navigate，
    此时 target_path 为 null 也算“有效导航正确”；同时另给严格路径准确率。

产物（docs/agent/evidence/chat-llm-air-ab/）：
  test-set-60.csv / ab-results-60.csv / ab-summary.json
"""
import argparse
import csv
import json
import re
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / "docs/agent/evidence/chat-baseline/golden-set-50.csv"
PROMPT = ROOT / "_work/chat-baseline/prompt-merchant.txt"
OUT = ROOT / "docs/agent/evidence/chat-llm-air-ab"
URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
CURRENT_PATH = "/merchant"
KEY_FILE = ROOT / "_work/llm-key"

MERCHANT_TOOLS = {
    "merchant_orders", "merchant_refunds", "merchant_comments",
    "query_goods", "navigate", "open_store", "seckill_signup",
}
TOOL_INTENT = {
    "merchant_orders": "ORDER_QUERY",
    "merchant_refunds": "REFUND_QUERY",
    "merchant_comments": "COMMENT_QUERY",
    "query_goods": "GOODS_QUERY",
    "navigate": "GUIDE_NAVIGATION",
    "open_store": "OPEN_STORE",
    "seckill_signup": "ADD_TO_SECKILL",
}
ALLOWED_INTENTS = {
    "ORDER_QUERY", "REFUND_QUERY", "COMMENT_QUERY", "GOODS_QUERY",
    "OPEN_STORE", "ADD_TO_SECKILL", "GUIDE_NAVIGATION", "DIRECT_NAVIGATION",
    # 与 LlmIntentClassifier.ALLOWED 同步：Prompt 列了这两类寒暄就必须认。
    "GREETING", "HELP",
    "NAVIGATE", "FAQ", "UNKNOWN",
}
MERCHANT_PATHS = {
    "/merchant", "/merchant/search", "/merchant/msg", "/merchant/order",
    "/merchant/order/cart", "/merchant/orders", "/merchant/user",
    "/merchant/user/info", "/merchant/user/add", "/merchant/user/list",
    "/merchant/user/history", "/merchant/user/friend", "/merchant/user/member",
    "/merchant/user/apply", "/merchant/user/favorites", "/merchant/stats",
    "/merchant/seckill", "/merchant/after-sales",
}
ALIASES = {
    "/merchant/aftersale": "/merchant/after-sales",
    "/merchant/after_sales": "/merchant/after-sales",
    "/merchant/refund": "/merchant/after-sales",
    "/merchant/publish": "/merchant/user/add",
    "/merchant/goods/add": "/merchant/user/add",
    "/merchant/goods": "/merchant/user/list",
    "/merchant/product": "/merchant/user/list",
    "/merchant/wallet": "/merchant/user/member",
    "/merchant/user/wallet": "/merchant/user/member",
}

# 黄金集 expected_intent 是路由域标签；这里映射成 LLM 意图白名单。
GOLD_INTENT = {
    "GOODS_MANAGE": "GUIDE_NAVIGATION",
    "MERCHANT_DASHBOARD": "GUIDE_NAVIGATION",
    "ASSET_QUERY": "GUIDE_NAVIGATION",
    "IDENTITY_CLAIM": "OPEN_STORE",
    "CLARIFY": "UNKNOWN",
}
DATA_TOOL = {
    "ORDER_QUERY": "merchant_orders",
    "REFUND_QUERY": "merchant_refunds",
    "COMMENT_QUERY": "merchant_comments",
    "GOODS_QUERY": "query_goods",
}


def normalize_path(path):
    if path is None:
        return ""
    p = str(path).strip()
    p = p.split("?", 1)[0].split("#", 1)[0]
    while len(p) > 1 and p.endswith("/"):
        p = p[:-1]
    return p


def resolve_merchant(path):
    p = normalize_path(path)
    if not p:
        return None
    p = ALIASES.get(p, p)
    if p in MERCHANT_PATHS:
        return p
    if p.startswith("/customer"):
        mapped = "/merchant" + p[len("/customer"):]
        if mapped == "/merchant/cart":
            mapped = "/merchant/order/cart"
        return mapped if mapped in MERCHANT_PATHS else None
    return None


def golden_expected_intent(route_intent):
    return GOLD_INTENT.get(route_intent, route_intent)


def acceptable_intents(expected):
    if expected == "OPEN_STORE":
        return ["OPEN_STORE", "GUIDE_NAVIGATION"]
    if expected == "ADD_TO_SECKILL":
        return ["ADD_TO_SECKILL", "GUIDE_NAVIGATION"]
    return [expected]


def acceptable_tools(behavior, expected_intent):
    if behavior == "navigate":
        if expected_intent == "OPEN_STORE":
            return ["open_store", "navigate"]
        if expected_intent == "ADD_TO_SECKILL":
            return ["seckill_signup", "navigate"]
        return ["navigate"]
    if behavior == "data":
        return [DATA_TOOL[expected_intent]]
    if expected_intent == "OPEN_STORE":  # IDENTITY_CLAIM / answer
        return ["open_store", "null"]
    return ["null"]


def expected_path_for(behavior, action):
    if behavior != "navigate":
        return ""
    p = normalize_path(action)
    if p.startswith("/customer"):
        mapped = "/merchant" + p[len("/customer"):]
        if mapped == "/merchant/cart":
            mapped = "/merchant/order/cart"
        p = mapped
    return p


def build_rows():
    rows = []
    with open(GOLDEN, encoding="utf-8-sig", newline="") as f:
        for g in csv.DictReader(f):
            route_intent = g["expected_intent"].strip()
            behavior = g["expected_behavior"].strip()
            exp_intent = golden_expected_intent(route_intent)
            rows.append({
                "id": g["id"],
                "question": g["question"],
                "source": "golden-set-50",
                "expected_intent": exp_intent,
                "acceptable_intents": "|".join(acceptable_intents(exp_intent)),
                "expected_tool": "|".join(acceptable_tools(behavior, exp_intent)),
                "expected_path": expected_path_for(behavior, g["expected_answer_or_action"]),
                "expected_behavior": behavior,
                "note": "",
            })

    cold = [
        ("C01", "怎么看我上架的商品", "GUIDE_NAVIGATION", "navigate", "/merchant/user/list"),
        ("C02", "怎么前往商家工作台", "GUIDE_NAVIGATION", "navigate", "/merchant/user"),
        ("C03", "我花出去的钱去哪了", "GUIDE_NAVIGATION", "navigate", "/merchant/user/member"),
        ("C04", "今天天气不错", "UNKNOWN", "null", ""),
        ("C05", "怎么处理退款", "GUIDE_NAVIGATION", "navigate", "/merchant/after-sales"),
        ("C06", "我的退款进度", "REFUND_QUERY", "merchant_refunds", ""),
        ("C07", "直接带我去管理商品", "DIRECT_NAVIGATION", "navigate", "/merchant/user/list"),
    ]
    for cid, q, intent, tool, path in cold:
        rows.append({
            "id": cid, "question": q, "source": "cold-7",
            "expected_intent": intent, "acceptable_intents": intent,
            "expected_tool": tool, "expected_path": path,
            "expected_behavior": "navigate" if path else ("data" if tool != "null" else "refusal"),
            "note": "重点句：报告 4.3" if cid == "C02" else "",
        })

    boundary = [
        ("B01", "222:ij-555", "GOODS_QUERY", "query_goods", ""),
        ("B02", "XPEM63624472", "GOODS_QUERY", "query_goods", ""),
        ("B03", "怎么前往商家工作台", "GUIDE_NAVIGATION", "navigate", "/merchant/user"),
    ]
    for bid, q, intent, tool, path in boundary:
        rows.append({
            "id": bid, "question": q, "source": "boundary",
            "expected_intent": intent, "acceptable_intents": intent,
            "expected_tool": tool, "expected_path": path,
            "expected_behavior": "navigate" if path else "data",
            "note": "与 C02 同句，测稳定性" if bid == "B03" else "",
        })
    return rows


def extract_json(raw):
    if raw is None:
        return None, "empty"
    text = raw.strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None, "no_json_object"
    try:
        return json.loads(text[start:end + 1]), None
    except Exception as e:
        return None, "json_parse_error:%s" % type(e).__name__


def evaluate(raw):
    """解析一条输出。

    区分三件事，避免把“业务白名单不认”误报成“JSON 格式错”：
      · syntax_valid —— 裸输出本身（含 markdown 代码块时）能否 json.loads；
      · json_valid —— 按生产的前后截取后是否是可解析对象且含非空 intent（验收口径）；
      · production_parse_valid —— 再叠加 ALLOWED 意图、商家工具集、target_path 路由校验。
    """
    out = {
        "syntax_valid": False, "json_valid": False, "production_parse_valid": False,
        "error": "", "intent_raw": "", "intent": "", "tool": None,
        "target_raw": "", "target": None, "target_ignored": False, "fenced": False,
    }
    try:
        json.loads((raw or "").strip())
        out["syntax_valid"] = True
    except Exception:
        out["syntax_valid"] = False
    out["fenced"] = "```" in (raw or "")

    root, err = extract_json(raw)
    if root is None or not isinstance(root, dict):
        out["error"] = err or "not_object"
        return out

    intent_raw = str(root.get("intent", "")).strip().upper()
    out["intent_raw"] = intent_raw
    if not intent_raw:
        out["error"] = "missing_intent"
        return out
    out["json_valid"] = True

    intent = "GUIDE_NAVIGATION" if intent_raw == "NAVIGATE" else intent_raw

    tool_node = root.get("tool")
    if tool_node is None and isinstance(root.get("slots"), dict):
        tool_node = root["slots"].get("tool")
    tool = None
    if tool_node is not None and not isinstance(tool_node, bool):
        name = str(tool_node).strip()
        if name and name.lower() != "null":
            tool = name
    # 生产 withToolIntent：DIRECT_NAVIGATION 先于工具注解，不能被 navigate 改回 GUIDE。
    if intent != "DIRECT_NAVIGATION" and tool is not None and tool in MERCHANT_TOOLS:
        intent = TOOL_INTENT.get(tool, intent)
    if intent == "NAVIGATE":
        intent = "GUIDE_NAVIGATION"
    out["intent"] = intent
    out["tool"] = tool

    target_node = root.get("target_path")
    if target_node is None:
        target_node = root.get("targetPath")
    if target_node is None and isinstance(root.get("slots"), dict):
        target_node = root["slots"].get("target_path")
    target_raw = "" if target_node is None else str(target_node).strip()
    out["target_raw"] = target_raw

    resolved = None
    if intent in ("GUIDE_NAVIGATION", "DIRECT_NAVIGATION"):
        resolved = resolve_merchant(target_raw)
        out["target"] = resolved
    elif target_raw:
        out["target_ignored"] = True

    reasons = []
    if intent_raw not in ALLOWED_INTENTS:
        reasons.append("intent_not_in_allowed:%s" % intent_raw)
    if tool is not None and tool not in MERCHANT_TOOLS:
        reasons.append("unregistered_tool:%s" % tool)
    if intent in ("GUIDE_NAVIGATION", "DIRECT_NAVIGATION") and resolved is None:
        reasons.append("target_path_unresolved:%s" % target_raw)
    out["error"] = ";".join(reasons)
    out["production_parse_valid"] = not reasons
    return out


def pick_mode(evals):
    """按 (intent, tool, target) 完整元组取众数；平票取第一次出现的。"""
    counts = {}
    for e in evals:
        key = (e["intent"], e["tool"], e["target"])
        counts[key] = counts.get(key, 0) + 1
    best = max(counts, key=counts.get)
    return next(e for e in evals if (e["intent"], e["tool"], e["target"]) == best)


def call(model, prompt, question, timeout, retries=2):
    key = KEY_FILE.read_text(encoding="utf-8").strip()
    body = {
        "model": model,
        "temperature": 0.1,
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": question},
        ],
    }
    last = None
    for attempt in range(retries + 1):
        started = time.time()
        try:
            r = requests.post(URL, headers={"Authorization": "Bearer " + key},
                              json=body, timeout=timeout)
            latency = round((time.time() - started) * 1000)
            if r.status_code == 200:
                j = r.json()
                content = j["choices"][0]["message"]["content"]
                return {
                    "content": content, "usage": j.get("usage", {}),
                    "requestId": r.headers.get("x-request-id", ""),
                    "http": 200, "latencyMs": latency, "attempts": attempt + 1,
                }
            last = "HTTP %s: %s" % (r.status_code, r.text[:200])
            if r.status_code not in (429, 500, 502, 503, 504):
                break
        except Exception as e:
            last = "%s: %s" % (type(e).__name__, str(e)[:200])
        time.sleep(1.5 * (attempt + 1))
    return {"content": "", "usage": {}, "requestId": "", "http": 0,
            "latencyMs": 0, "attempts": retries + 1, "error": last}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--prompt", default=str(PROMPT))
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--only-ids", default="")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    prompt = Path(args.prompt).read_text(encoding="utf-8")
    rows = build_rows()

    with open(out_dir / "test-set-60.csv", "w", encoding="utf-8-sig", newline="") as f:
        cols = ["id", "question", "source", "expected_intent", "acceptable_intents",
                "expected_tool", "expected_path", "expected_behavior", "note"]
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)

    only = set(x.strip() for x in args.only_ids.split(",") if x.strip())
    selected = [r for r in rows if not only or r["id"] in only]

    results = []
    for idx, row in enumerate(selected, 1):
        rec = dict(row)
        for label, model in (("flash", "glm-4-flash"), ("air", "glm-4-air")):
            evals, contents, metas = [], [], []
            for _ in range(args.runs):
                call_info = call(model, prompt, row["question"], args.timeout)
                ev = evaluate(call_info.get("content", ""))
                evals.append(ev)
                contents.append(call_info.get("content", ""))
                metas.append({
                    "http": call_info.get("http", 0),
                    "latencyMs": call_info.get("latencyMs", 0),
                    "usage": call_info.get("usage", {}),
                    "requestId": call_info.get("requestId", ""),
                    "callError": call_info.get("error", ""),
                })
                time.sleep(0.2)
            mode = pick_mode(evals)
            mode_idx = next(i for i, e in enumerate(evals) if e == mode)
            rec[label + "_evals"] = evals
            rec[label + "_runs"] = metas
            rec[label + "_content"] = contents[mode_idx]
            rec[label + "_http"] = metas[mode_idx]["http"]
            rec[label + "_latencyMs"] = metas[mode_idx]["latencyMs"]
            rec[label + "_usage"] = json.dumps(metas[mode_idx]["usage"], ensure_ascii=False)
            rec[label + "_requestId"] = metas[mode_idx]["requestId"]
            rec[label + "_callError"] = "; ".join(
                x["callError"] for x in metas if x["callError"])
            for k, v in mode.items():
                rec["%s_%s" % (label, k)] = v
            rec[label + "_json_valid"] = all(e["json_valid"] for e in evals)
            rec[label + "_json_rate"] = round(
                sum(1 for e in evals if e["json_valid"]) / len(evals), 4)
            rec[label + "_production_parse_valid"] = all(
                e["production_parse_valid"] for e in evals)
            rec[label + "_production_parse_rate"] = round(
                sum(1 for e in evals if e["production_parse_valid"]) / len(evals), 4)
            rec[label + "_syntax_valid"] = all(e["syntax_valid"] for e in evals)
            rec[label + "_syntax_rate"] = round(
                sum(1 for e in evals if e["syntax_valid"]) / len(evals), 4)
            rec[label + "_fenced_runs"] = sum(1 for e in evals if e["fenced"])
        results.append(rec)
        print("[%d/%d] %s %s | flash=%s/%s/%s (%d/%d json) | air=%s/%s/%s (%d/%d json)" % (
            idx, len(selected), row["id"], row["question"][:18],
            rec["flash_intent"], rec["flash_tool"], rec["flash_target"],
            sum(1 for e in rec["flash_evals"] if e["json_valid"]), args.runs,
            rec["air_intent"], rec["air_tool"], rec["air_target"],
            sum(1 for e in rec["air_evals"] if e["json_valid"]), args.runs), flush=True)

    # 逐条宽表
    base_cols = ["id", "question", "source", "expected_intent", "acceptable_intents",
                 "expected_tool", "expected_path", "expected_behavior", "note"]
    model_cols = []
    for label in ("flash", "air"):
        model_cols += [label + "_" + x for x in (
            "intent", "tool", "target", "syntax_valid", "json_valid",
            "production_parse_valid", "syntax_rate", "json_rate",
            "production_parse_rate", "error", "target_raw", "fenced",
            "fenced_runs", "latencyMs", "http", "usage", "requestId", "callError")]
    model_cols += ["intent_agree", "tool_agree", "target_agree", "diff_note"]
    with open(out_dir / "ab-results-60.csv", "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=base_cols + model_cols + ["flash_content", "air_content"],
                           extrasaction="ignore")
        w.writeheader()
        for rec in results:
            rec["intent_agree"] = rec.get("flash_intent") == rec.get("air_intent")
            rec["tool_agree"] = rec.get("flash_tool") == rec.get("air_tool")
            rec["target_agree"] = rec.get("flash_target") == rec.get("air_target")
            w.writerow(rec)

    json.dump(results, open(out_dir / "ab-results-60.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    summary = summarize(results)
    json.dump(summary, open(out_dir / "ab-summary.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def _ok_fields(rec, fields, dim):
    """fields 需含 intent/tool/target/target_raw。"""
    if dim == "intent":
        return fields.get("intent") in (rec["acceptable_intents"] or "").split("|")
    if dim == "tool":
        tool = fields.get("tool")
        tool_s = "null" if tool is None else tool
        return tool_s in (rec["expected_tool"] or "").split("|")
    if dim == "target":
        expected = rec["expected_path"] or ""
        target = fields.get("target") or ""
        if rec["expected_behavior"] == "navigate":
            if target == expected:
                return True
            # 专用工具代替 navigate：target 为 null 且没有乱填路径也算有效导航
            dedicated = {"open_store", "seckill_signup"}
            if fields.get("tool") in dedicated and not (fields.get("target_raw") or ""):
                return True
            return False
        # 非导航题：生产会忽略 target_path，但 Prompt 明确要求不要给，按违规计
        return not (fields.get("target_raw") or "")
    raise ValueError(dim)


def _ok(rec, label, dim):
    fields = {
        "intent": rec.get(label + "_intent"),
        "tool": rec.get(label + "_tool"),
        "target": rec.get(label + "_target"),
        "target_raw": rec.get(label + "_target_raw"),
    }
    return _ok_fields(rec, fields, dim)


def summarize(results):
    def rate(label, dim, subset=None):
        rows = subset if subset is not None else results
        if not rows:
            return {"ok": 0, "n": 0, "rate": None}
        ok = sum(1 for r in rows if _ok(r, label, dim))
        return {"ok": ok, "n": len(rows), "rate": round(ok / len(rows), 4)}

    def attempts(label, dim, subset=None):
        rows = subset if subset is not None else results
        ok = total = 0
        for r in rows:
            for e in r.get(label + "_evals", []):
                total += 1
                ok += 1 if _ok_fields(r, e, dim) else 0
        return {"ok": ok, "n": total, "rate": round(ok / total, 4) if total else None}

    nav = [r for r in results if r["expected_behavior"] == "navigate"]
    runs = len(results[0].get("flash_evals", [])) if results else 0
    out = {"n": len(results), "runs_per_item": runs, "duplicates_retained": True}
    for label in ("flash", "air"):
        out[label] = {
            "intent": rate(label, "intent"),
            "tool": rate(label, "tool"),
            "target_all": rate(label, "target"),
            "target_navigate": rate(label, "target", nav),
            "attempts": {
                "intent": attempts(label, "intent"),
                "tool": attempts(label, "tool"),
                "target_all": attempts(label, "target"),
                "target_navigate": attempts(label, "target", nav),
            },
            "json_syntax_attempts": {
                "ok": sum(1 for r in results for e in r.get(label + "_evals", []) if e["syntax_valid"]),
                "n": sum(len(r.get(label + "_evals", [])) for r in results),
            },
            "json_valid_attempts": {
                "ok": sum(1 for r in results for e in r.get(label + "_evals", []) if e["json_valid"]),
                "n": sum(len(r.get(label + "_evals", [])) for r in results),
            },
            "json_valid_items_all": {
                "ok": sum(1 for r in results if r[label + "_json_valid"]),
                "n": len(results),
            },
            "production_parse_attempts": {
                "ok": sum(1 for r in results for e in r.get(label + "_evals", []) if e["production_parse_valid"]),
                "n": sum(len(r.get(label + "_evals", [])) for r in results),
            },
            "production_parse_items_all": {
                "ok": sum(1 for r in results if r[label + "_production_parse_valid"]),
                "n": len(results),
            },
            "fenced_attempts": sum(r.get(label + "_fenced_runs", 0) for r in results),
            "call_errors": sum(1 for r in results if r.get(label + "_callError")),
        }
        for k in ("json_syntax_attempts", "json_valid_attempts", "json_valid_items_all",
                  "production_parse_attempts", "production_parse_items_all"):
            v = out[label][k]
            v["rate"] = round(v["ok"] / v["n"], 4) if v["n"] else None
    out["agreement"] = {
        "intent": round(sum(1 for r in results if r["flash_intent"] == r["air_intent"]) / len(results), 4),
        "tool": round(sum(1 for r in results if r["flash_tool"] == r["air_tool"]) / len(results), 4),
        "target": round(sum(1 for r in results if (r["flash_target"] or "") == (r["air_target"] or "")) / len(results), 4),
    }
    # 唯一问题去重后的敏感度（C02/B03、C03/G27、C04/G49、C05/G34 是重复项）
    seen, unique = set(), []
    for r in results:
        key = (r["question"], r["expected_intent"], r["expected_path"], r["expected_tool"])
        if key in seen:
            continue
        seen.add(key)
        unique.append(r)
    out["unique_n"] = len(unique)
    out["unique"] = {}
    for label in ("flash", "air"):
        out["unique"][label] = {
            "intent": rate(label, "intent", unique),
            "tool": rate(label, "tool", unique),
            "target_all": rate(label, "target", unique),
            "target_navigate": rate(label, "target", [r for r in unique if r["expected_behavior"] == "navigate"]),
        }
    return out


if __name__ == "__main__":
    sys.exit(main())
