#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RAGAS 政策问答门禁（2026-10-26）。

流程：
  1. 从真实后端 `/chat/ask` 逐条采集 `policy-faq-eval.csv` 的答案（默认买家 1111）。
  2. 用 DeepSeek-V4.1-Flash 作 judge、SiliconFlow bge-m3 作向量，跑 Faithfulness +
     Answer Relevancy，默认 3 次。GLM 资源包耗尽后 4-plus / embedding-3 会 429/1113，
     4-flash 虽可用但反问生成质量偏低（AR 实测 0.755），故默认走 DeepSeek。
  3. 取 3 次总分的平均值，F < 0.85 或 AR < 0.80 ⇒ 退出码 1。
  4. 另加一条确定性闸：政策答案里的金额（如「满99元」）必须与标准答案一致。
     RAGAS 只有 20–30 条时，单条错误 FAQ 的掉分会被判分噪声淹没（实测 3 次平均
     0.8473，只低 0.0027）；金额硬校验让政策数字被改错时必定红。

中文适配与 2026-10-24 基线一致（只改断句与提示词，不改 RAGAS 公式）：
  - AnswerRelevancy 的「由答案反推问题」用中文提示词；
  - Faithfulness 用 pysbd 中文断句，并允许「。」「！」「？」结尾；
  - GLM 只接受 temperature>=0.01、strictness=1。

用法：
  python scripts/ragas/gate.py                 # 3 次，采集 + 评测
  python scripts/ragas/gate.py --runs 1        # 变异验收时可只跑 1 次
  python scripts/ragas/gate.py --answers x.csv # 复用已采集答案，不重新打后端

Key：judge 优先 DEEPSEEK_API_KEY，其次 `_work/ds-key`；向量优先 SILICONFLOW_API_KEY，
其次 `_work/sf-key`；缺任意一把 ⇒ 退出码 2。
"""
import argparse
import csv
import json
import os
import re
import sys
from pathlib import Path

import pandas as pd
import requests
from datasets import Dataset
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import RunConfig, evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.llms.prompt import Prompt
from ragas.metrics import AnswerRelevancy, Faithfulness
from ragas.metrics._answer_relevance import _output_instructions
from ragas.metrics._faithfulness import get_segmenter

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SET = ROOT / "scripts/ragas/policy-faq-eval.csv"
DEFAULT_OUT = ROOT / "docs/agent/evidence/ragas-gate"
JUDGE_BASE_URL = os.environ.get("RAGAS_JUDGE_URL", "https://api.deepseek.com")
JUDGE = os.environ.get("RAGAS_JUDGE_MODEL", "deepseek-flash")
EMBEDDING_BASE_URL = os.environ.get("RAGAS_EMBEDDING_URL", "https://api.siliconflow.cn/v1")
EMBEDDING = os.environ.get("RAGAS_EMBEDDING_MODEL", "BAAI/bge-m3")
THRESHOLDS = {"faithfulness": 0.85, "answer_relevancy": 0.80}
MONEY = re.compile(r"(\d+(?:\.\d+)?)\s*元")


class GlmChatOpenAI(ChatOpenAI):
    """GLM 要求 temperature ∈ [0,2] 且最多两位小数，1e-8 会被拒（code 1210）。"""

    def _get_request_payload(self, input_, *, stop=None, **kwargs):
        t = kwargs.get("temperature")
        if t is not None and t < 0.01:
            kwargs["temperature"] = 0.01
        return super()._get_request_payload(input_, stop=stop, **kwargs)


class ZhFaithfulness(Faithfulness):
    """只放宽断句白名单：中文句子以「。」「？」「！」结尾也算完整句。"""

    def _create_statements_prompt(self, row):
        answer, question = row["answer"], row["question"]
        sentences = self.sentence_segmenter.segment(answer)
        sentences = [
            s for s in sentences
            if s.strip().endswith((".", "。", "！", "？", "!", "?"))
        ]
        sentences = "\n".join(f"{i}:{s}" for i, s in enumerate(sentences))
        return self.statement_prompt.format(
            question=question, answer=answer, sentences=sentences)


QUESTION_GEN_ZH = Prompt(
    name="question_generation",
    instruction=(
        "根据给定的回答，生成一个能引出该回答的问题，并判断回答是否含糊其辞"
        "（noncommittal）。若回答在回避、含糊、不确定（例如“我不知道”“我不确定”），"
        "noncommittal 记为 1；若回答明确给出了信息，记为 0。问题必须使用中文。"
    ),
    output_format_instruction=_output_instructions,
    examples=[
        {"answer": "本店满99包邮，偏远地区除外。", "context": "政策：运费 / 包邮。",
         "output": {"question": "你们店多少金额包邮？", "noncommittal": 0}},
        {"answer": "支持7天无理由，但需保证不影响二次销售哦。",
         "context": "政策：拆封退货 / 七天无理由。",
         "output": {"question": "拆封之后还能七天无理由退货吗？", "noncommittal": 0}},
        {"answer": "这个我不清楚。", "context": "政策：发票开具。",
         "output": {"question": "可以开发票吗？", "noncommittal": 1}},
    ],
    input_keys=["answer", "context"],
    output_key="output",
    output_type="json",
    language="chinese",
)


def read_key(env_name, fallback):
    key = (os.environ.get(env_name) or "").strip()
    if key:
        return key
    path = ROOT / fallback
    if path.exists():
        return path.read_text(encoding="utf-8").strip()
    return ""


def policy_fact_errors(answers):
    """政策金额一致性：标准答案里承诺的金额必须原样出现在实际回答里。

    只比金额（「N 元」），不比所有数字：天数 / 小时这类写法可能中英混用，
    硬比会产生假红；金额是政策里最容易被静默改错、也最不能含糊的一类事实。
    """
    errors = []
    for a in answers:
        want = MONEY.findall(a["ground_truth"])
        got = MONEY.findall(a["answer"])
        if sorted(want) != sorted(got):
            errors.append({
                "id": a["id"],
                "question": a["question"],
                "expected_amounts": want,
                "answer_amounts": got,
                "answer": a["answer"],
            })
    return errors


def collect_answers(rows, base_url):
    """登录买家 1111，逐条走真实 /chat/ask；答案与意图一起落盘。"""
    session = requests.Session()
    login = session.post(base_url + "/user/loginSelect",
                         data={"username": "1111", "password": "1111"}, timeout=15)
    if login.text.strip() != "1":
        raise RuntimeError("买家 1111 登录失败，返回 %r" % login.text[:80])
    out = []
    for row in rows:
        resp = session.post(base_url + "/chat/ask",
                            data={"Message": row["question"], "CurrentPath": "/customer"},
                            timeout=120)
        if resp.status_code != 200:
            raise RuntimeError("%s HTTP %s" % (row["id"], resp.status_code))
        body = resp.json()
        answer = (body.get("reply") or "").strip()
        if not answer:
            raise RuntimeError("%s 空回复" % row["id"])
        out.append({
            "id": row["id"],
            "question": row["question"],
            "ground_truth": row["ground_truth"],
            "contexts": row["contexts"],
            "answer": answer,
            "actual_intent": body.get("intent"),
            "actual_action": (body.get("action") or {}).get("targetPath", ""),
        })
    return out


def make_metrics(llm, emb, seed):
    relevancy = AnswerRelevancy(
        llm=llm, embeddings=emb, question_generation=QUESTION_GEN_ZH, strictness=1)
    # Faithfulness 会把 _reproducibility 原样当作 judge 的 n（自洽投票次数）。
    # DeepSeek 只接受 n=1，传 2/3 会让每一次 NLI 调用都 400 ⇒ 该轮全部 NaN。
    # 「跑 3 次取平均」由 main 里的 3 次独立评测完成，这里固定 1。
    faithfulness = ZhFaithfulness(
        llm=llm, sentence_segmenter=get_segmenter(language="chinese", clean=False),
        _reproducibility=1)
    return [faithfulness, relevancy]


def run_once(answers, llm, emb, seed):
    dataset = Dataset.from_dict({
        "question": [a["question"] for a in answers],
        "answer": [a["answer"] for a in answers],
        "contexts": [[c.strip() for c in a["contexts"].split("||") if c.strip()]
                     for a in answers],
        "ground_truth": [a["ground_truth"] for a in answers],
    })
    result = evaluate(
        dataset,
        metrics=make_metrics(llm, emb, seed),
        llm=llm,
        embeddings=emb,
        raise_exceptions=False,
        # max_workers 必须为 -1：ragas 0.1.21 在 Windows 上只要传正数就会报
        # "Future attached to a different loop"（实测 4 也必现）。
        run_config=RunConfig(timeout=180, max_retries=3, max_wait=30, max_workers=-1),
    )
    df = result.to_pandas()
    summary = {}
    for metric in ("faithfulness", "answer_relevancy"):
        series = df[metric] if metric in df else pd.Series(dtype=float)
        summary[metric] = {
            "mean": round(float(series.dropna().mean()), 4) if series.notna().any() else None,
            "valid": int(series.notna().sum()),
            "nan": int(series.isna().sum()),
        }
    return df, summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", default=str(DEFAULT_SET))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--answers", default="")
    parser.add_argument("--base-url", default="http://localhost:8088")
    args = parser.parse_args()

    judge_key = read_key("DEEPSEEK_API_KEY", "_work/ds-key")
    embed_key = read_key("SILICONFLOW_API_KEY", "_work/sf-key")
    if not judge_key or not embed_key:
        print("缺少 DEEPSEEK_API_KEY / SILICONFLOW_API_KEY（或 _work/ds-key、_work/sf-key），"
              "无法评测", file=sys.stderr)
        return 2

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader(open(args.set, encoding="utf-8-sig")))
    if not 20 <= len(rows) <= 30:
        print("评测集应为 20–30 条，实得 %d" % len(rows), file=sys.stderr)
        return 2

    if args.answers:
        answers = list(csv.DictReader(open(args.answers, encoding="utf-8-sig")))
        if len(answers) != len(rows):
            print("答案数与评测集不一致：%d vs %d" % (len(answers), len(rows)),
                  file=sys.stderr)
            return 2
    else:
        answers = collect_answers(rows, args.base_url)
        with open(out_dir / "answers.csv", "w", encoding="utf-8-sig", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=list(answers[0].keys()))
            writer.writeheader()
            writer.writerows(answers)

    bad_intents = [a for a in answers if a.get("actual_intent") != "FAQ"]
    if bad_intents:
        print("以下政策问题没有走 FAQ 分支：", file=sys.stderr)
        for a in bad_intents:
            print("  %s %s -> %s" % (a["id"], a["question"], a.get("actual_intent")),
                  file=sys.stderr)
        return 1

    fact_errors = policy_fact_errors(answers)
    if fact_errors:
        print("政策金额与标准答案不一致：", file=sys.stderr)
        for e in fact_errors:
            print("  %s %s -> 期望 %s，实际 %s（%s）" % (
                e["id"], e["question"], e["expected_amounts"],
                e["answer_amounts"], e["answer"]), file=sys.stderr)

    llm = LangchainLLMWrapper(GlmChatOpenAI(
        model=JUDGE, base_url=JUDGE_BASE_URL, api_key=judge_key,
        temperature=0.01, timeout=120, max_retries=2))
    emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=EMBEDDING, base_url=EMBEDDING_BASE_URL, api_key=embed_key,
        timeout=120, check_embedding_ctx_length=False))

    runs = []
    frames = []
    for i in range(1, args.runs + 1):
        df, summary = run_once(answers, llm, emb, seed=i)
        df.insert(0, "run", i)
        df.insert(1, "id", [a["id"] for a in answers])
        frames.append(df)
        runs.append(summary)
        print("run %d  F=%s  AR=%s  nan=%s/%s" % (
            i,
            summary["faithfulness"]["mean"], summary["answer_relevancy"]["mean"],
            summary["faithfulness"]["nan"], summary["answer_relevancy"]["nan"]))

    average = {}
    for metric in ("faithfulness", "answer_relevancy"):
        values = [r[metric]["mean"] for r in runs if r[metric]["mean"] is not None]
        average[metric] = round(sum(values) / len(values), 4) if values else None
    nan_total = sum(r[m]["nan"] for r in runs for m in ("faithfulness", "answer_relevancy"))
    passed = (
        nan_total == 0
        and not fact_errors
        and average["faithfulness"] is not None
        and average["answer_relevancy"] is not None
        and average["faithfulness"] >= THRESHOLDS["faithfulness"]
        and average["answer_relevancy"] >= THRESHOLDS["answer_relevancy"]
    )

    summary = {
        "samples": len(answers),
        "runs": args.runs,
        "judge_model": JUDGE,
        "embedding_model": EMBEDDING,
        "thresholds": THRESHOLDS,
        "average": average,
        "runs_detail": runs,
        "nan_total": nan_total,
        "policy_fact_errors": fact_errors,
        "pass": passed,
        "answers": [a["answer"] for a in answers],
        "adaptations": [
            "answer_relevancy: 中文 question_generation 提示词",
            "faithfulness: pysbd 中文断句 + 句末标点白名单加入 。！？",
            "answer_relevancy.strictness=1 / faithfulness._reproducibility=1",
            "  （DeepSeek judge 只支持 n=1；3 次平均由外层 3 次独立评测实现）",
            "temperature 从 0 夹到 0.01（GLM code 1210，DeepSeek 同样接受）",
        ],
    }
    pd.concat(frames, ignore_index=True).to_csv(
        out_dir / "scores.csv", index=False, encoding="utf-8-sig")
    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: summary[k] for k in
                      ("samples", "runs", "average", "thresholds", "nan_total",
                       "policy_fact_errors", "pass")},
                     ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
