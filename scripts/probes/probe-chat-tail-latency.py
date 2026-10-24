#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe-chat-tail-latency.py —— Air vs Flash 单句 50 次尾延迟（2026-10-24）。

目的：上一轮真实链路 1/20 次 3.013s 超时是离群值还是模型固有尾部？
本探针直接打 GLM HTTP 接口（**不切换线上模型**，主后端保持 Flash），
用生产同一份商家 System Prompt、同一句「怎么前往商家工作台」、temperature=0.1，
各跑 50 次，记录 p50/p95/p99 与 >3s 的次数/类型。

口径：
  · 单次请求 client timeout=30s；**超时/HTTP 错误不重试** —— 重试会把尾部失败藏起来。
  · 唯一例外：连接根本没建立（conn_error）时重试（默认 3 次、1s/2s/4s 退避），
    因为它不是模型延迟，拿不到样本就永远测不了；重试次数落 CSV。
  · 连续 8 次连接失败即中止并落部分结果 —— 端点不可达时硬跑 50 次只会把
    异常灌满 CSV（首轮实测轮 19–50 全 conn_error）。
  · 生产是 3s 硬上限：这里把「HTTP 200 但 wall > 3000ms」记为 would_timeout_3s，
    另把真正 30s 超时记为 timeout；over_3s = 两者之和。
  · 50 次按轮交替 Air/Flash，并逐轮交换先手，抵消时段漂移。
  · 分位数只用 HTTP 200 的有效样本，nearest-rank：idx = ceil(p/100*n)-1。
  · 有效样本 Air/Flash 任一 <40 或运行中止 ⇒ 判「样本不足」，不套三分支结论。
  · Key 只从 _work/llm-key 或 GLM_API_KEY 读，不落报告。

产物（docs/agent/evidence/chat-air-tail-latency/）：
  runs-50.csv / summary.json / summary.md
"""
import argparse
import csv
import json
import math
import os
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
PROMPT = ROOT / "_work/chat-baseline/prompt-merchant.txt"
KEY_FILE = ROOT / "_work/llm-key"
URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
OUT = ROOT / "docs/agent/evidence/chat-air-tail-latency"
QUESTION = "怎么前往商家工作台"
MODELS = ["glm-4-air", "glm-4-flash"]


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    idx = max(0, math.ceil(p / 100.0 * len(s)) - 1)
    return s[idx]


def stats(rows, model):
    xs = [r for r in rows if r["model"] == model]
    lat = [r["latencyMs"] for r in xs if r["http"] == 200]
    over3 = [r for r in xs if r["outcome"] in ("ok_slow", "timeout")]
    return {
        "n": len(xs),
        "n_valid": len(lat),
        "ok": sum(1 for r in xs if r["http"] == 200),
        "http_error": sum(1 for r in xs if r["outcome"] == "http_error"),
        "conn_error": sum(1 for r in xs if r["outcome"] == "conn_error"),
        "exception": sum(1 for r in xs if r["outcome"] == "exception"),
        "timeout": sum(1 for r in xs if r["outcome"] == "timeout"),
        "would_timeout_3s": sum(1 for r in xs if r["outcome"] == "ok_slow"),
        "over_3s": len(over3),
        "over_5s": sum(1 for r in xs if r["latencyMs"] > 5000),
        "min": min(lat) if lat else None,
        "p50": pct(lat, 50),
        "p95": pct(lat, 95),
        "p99": pct(lat, 99),
        "max": max(lat) if lat else None,
        "mean": round(sum(lat) / len(lat), 1) if lat else None,
    }


def call(model, prompt, question, key, timeout):
    body = {
        "model": model,
        "temperature": 0.1,
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": question},
        ],
    }
    started = time.perf_counter()
    try:
        r = requests.post(URL, headers={"Authorization": "Bearer " + key},
                          json=body, timeout=timeout)
        ms = round((time.perf_counter() - started) * 1000)
        if r.status_code != 200:
            return {"latencyMs": ms, "http": r.status_code, "outcome": "http_error",
                    "error": r.text[:200], "requestId": r.headers.get("x-request-id", ""),
                    "contentLen": 0, "usage": {}}
        j = r.json()
        content = j["choices"][0]["message"]["content"]
        return {"latencyMs": ms, "http": 200,
                "outcome": "ok_fast" if ms <= 3000 else "ok_slow",
                "error": "", "requestId": r.headers.get("x-request-id", ""),
                "contentLen": len(content), "usage": j.get("usage", {})}
    except requests.exceptions.ConnectionError as e:
        # 连接根本没建立（DNS / TLS / reset / connect timeout）：这不是模型延迟，
        # 不能算进分位数，也不能算成「超时」。由调用方重试或中止。
        ms = round((time.perf_counter() - started) * 1000)
        return {"latencyMs": ms, "http": 0, "outcome": "conn_error",
                "error": "%s: %s" % (type(e).__name__, str(e)[:200]),
                "requestId": "", "contentLen": 0, "usage": {}}
    except requests.exceptions.Timeout as e:
        ms = round((time.perf_counter() - started) * 1000)
        return {"latencyMs": ms, "http": 0, "outcome": "timeout",
                "error": "%s: %s" % (type(e).__name__, str(e)[:200]),
                "requestId": "", "contentLen": 0, "usage": {}}
    except Exception as e:
        ms = round((time.perf_counter() - started) * 1000)
        return {"latencyMs": ms, "http": 0, "outcome": "exception",
                "error": "%s: %s" % (type(e).__name__, str(e)[:200]),
                "requestId": "", "contentLen": 0, "usage": {}}


def call_resilient(model, prompt, question, key, timeout, retries, backoff):
    """只在「连接没建立」时重试；超时 / HTTP 200 / HTTP 错误原样返回，不藏尾部。"""
    tries = 0
    while True:
        tries += 1
        rec = call(model, prompt, question, key, timeout)
        rec["attempts"] = tries
        if rec["outcome"] != "conn_error" or tries > retries:
            return rec
        time.sleep(backoff * (2 ** (tries - 1)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=50)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--gap", type=float, default=0.2)
    ap.add_argument("--conn-retries", type=int, default=3)
    ap.add_argument("--abort-after", type=int, default=8,
                    help="连续 N 次连接失败就中止并落部分结果")
    ap.add_argument("--prompt", default=str(PROMPT))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--question", default=QUESTION)
    ap.add_argument("--from-csv", default="",
                    help="不跑请求，只用已有 runs CSV 重新生成 summary（口径改动后重算用）")
    args = ap.parse_args()

    if args.from_csv:
        src = Path(args.from_csv)
        rows = list(csv.DictReader(open(src, encoding="utf-8-sig")))
        for r in rows:
            for k in ("round", "order", "latencyMs", "http", "attempts"):
                r[k] = int(r[k] or 0)
            r["usage"] = json.loads(r["usage"] or "{}")
        prompt = Path(args.prompt).read_text(encoding="utf-8")
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        rounds = [r["round"] for r in rows]
        return write_summary(out, args, rows, prompt, False, max(rounds) if rounds else None)

    key = os.environ.get("GLM_API_KEY", "").strip()
    if not key and KEY_FILE.exists():
        key = KEY_FILE.read_text(encoding="utf-8").strip()
    if not key:
        print("缺少 GLM_API_KEY / _work/llm-key", file=sys.stderr)
        return 2
    prompt = Path(args.prompt).read_text(encoding="utf-8")
    print("promptChars=%d question=%s runs=%d" % (len(prompt), args.question, args.runs), flush=True)

    rows = []
    consecutive_conn = 0
    aborted = False
    aborted_at = None
    for i in range(1, args.runs + 1):
        order = MODELS if i % 2 == 1 else list(reversed(MODELS))
        for j, model in enumerate(order, 1):
            rec = call_resilient(model, prompt, args.question, key, args.timeout,
                                 args.conn_retries, 1.0)
            rec.update({"round": i, "order": j, "model": model,
                        "question": args.question})
            rows.append(rec)
            if rec["outcome"] == "conn_error":
                consecutive_conn += 1
            else:
                consecutive_conn = 0
            print("round=%02d %-11s order=%d wall=%5sms http=%s outcome=%-11s tries=%d" % (
                i, model, j, rec["latencyMs"], rec["http"], rec["outcome"],
                rec["attempts"]), flush=True)
            time.sleep(args.gap)
            if consecutive_conn >= args.abort_after:
                aborted = True
                aborted_at = i
                print("ABORT: %d consecutive connection failures at round %d"
                      % (consecutive_conn, i), flush=True)
                break
        if aborted:
            break

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    cols = ["round", "order", "model", "question", "latencyMs", "http", "outcome",
            "attempts", "error", "requestId", "contentLen", "usage"]
    with open(out / "runs-50.csv", "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for r in rows:
            w.writerow({k: (json.dumps(r[k], ensure_ascii=False) if k == "usage" else r.get(k, ""))
                        for k in cols})

    return write_summary(out, args, rows, prompt, aborted, aborted_at)


def write_summary(out, args, rows, prompt, aborted, aborted_at):
    summary = {"runs_per_model": max(r["round"] for r in rows) if rows else 0,
               "question": args.question,
               "promptChars": len(prompt), "percentile": "nearest-rank",
               "aborted": aborted, "aborted_at_round": aborted_at,
               "models": {m: stats(rows, m) for m in MODELS}}
    a = summary["models"]["glm-4-air"]
    f = summary["models"]["glm-4-flash"]
    diff = a["over_3s"] - f["over_3s"]
    enough = a["n_valid"] >= 40 and f["n_valid"] >= 40 and not aborted
    if not enough:
        verdict = ("样本不足：有效样本 Air=%d/50、Flash=%d/50（连接失败不计入延迟）；"
                   "未达判定门槛，建议网络稳定后重跑或放宽到 5 秒再评估"
                   % (a["n_valid"], f["n_valid"]))
    elif a["over_3s"] <= 2 and f["over_3s"] >= 1 and abs(diff) <= 2:
        verdict = "Air 可以切：Air 超时<=2/50，且 Flash 也有类似尾部延迟"
    elif a["over_3s"] >= 5 and f["over_3s"] <= a["over_3s"] - 3:
        verdict = "接受回滚：Air 超时>=5/50，且 Flash 明显更少"
    elif a["over_3s"] <= 2 and f["over_3s"] >= a["over_3s"] + 3:
        # 数据可区分，只是方向与判据预设相反：Flash 的尾部比 Air 差。
        verdict = ("判据外：Air %d/50、Flash %d/50，数据可区分（不是「样本不足」），"
                   "但三条判据只预设了「Flash 更好或相近」，未覆盖「Flash 明显更差」；"
                   "是否切换由用户裁决" % (a["over_3s"], f["over_3s"]))
    else:
        verdict = "样本不足：50 次仍无法区分，建议放宽到 5 秒再评估"
    summary["over3_diff_air_minus_flash"] = diff
    summary["verdict"] = verdict
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = ["# Air vs Flash 尾延迟（单句 %d 次）" % args.runs, "",
             "句子：`%s`；Prompt %d chars；目标每模型 %d 次；nearest-rank 分位；"
             "分位数只统计 HTTP 200 的有效样本。" % (
                 args.question, len(prompt), args.runs), ""]
    if aborted:
        lines += ["> **本次运行中止**：第 %d 轮连续 %d 次连接失败（端点不可达），"
                  "已落部分结果。" % (aborted_at, args.abort_after), ""]
    lines += ["| 指标 | glm-4-air | glm-4-flash |", "|---|---:|---:|"]
    for k in ["n", "n_valid", "ok", "http_error", "conn_error", "exception", "timeout",
              "would_timeout_3s", "over_3s", "over_5s",
              "min", "p50", "p95", "p99", "max", "mean"]:
        lines.append("| %s | %s | %s |" % (k, a.get(k), f.get(k)))
    lines += ["", "**判定：** " + verdict, "",
              "> `would_timeout_3s` = HTTP 200 但 wall >3000ms（生产 3s 硬上限下会被切断）；"
              "`over_3s` = would_timeout_3s + 真正的客户端超时；"
              "`conn_error` = 连接未建立，不属于模型延迟，已从分位数剔除。"]
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
